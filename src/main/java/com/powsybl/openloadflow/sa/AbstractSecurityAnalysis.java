/*
 * Copyright (c) 2020-2025, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.sa;

import com.google.common.base.Stopwatch;
import com.powsybl.action.Action;
import com.powsybl.commons.PowsyblException;
import com.powsybl.commons.report.ReportNode;
import com.powsybl.computation.CompletableFutureTask;
import com.powsybl.computation.ComputationManager;
import com.powsybl.contingency.ContingenciesProvider;
import com.powsybl.contingency.Contingency;
import com.powsybl.contingency.ContingencyContext;
import com.powsybl.contingency.strategy.ConditionalActions;
import com.powsybl.contingency.strategy.OperatorStrategy;
import com.powsybl.contingency.strategy.condition.*;
import com.powsybl.contingency.violations.LimitViolation;
import com.powsybl.contingency.violations.LimitViolationType;
import com.powsybl.iidm.network.ComponentConstants;
import com.powsybl.iidm.network.Network;
import com.powsybl.loadflow.LoadFlowParameters;
import com.powsybl.loadflow.LoadFlowResult;
import com.powsybl.math.matrix.MatrixFactory;
import com.powsybl.openloadflow.OpenLoadFlowParameters;
import com.powsybl.openloadflow.equations.Quantity;
import com.powsybl.openloadflow.graph.GraphConnectivityFactory;
import com.powsybl.openloadflow.lf.AbstractLoadFlowParameters;
import com.powsybl.openloadflow.lf.LoadFlowContext;
import com.powsybl.openloadflow.lf.LoadFlowEngine;
import com.powsybl.openloadflow.network.*;
import com.powsybl.openloadflow.network.action.Actions;
import com.powsybl.openloadflow.network.action.LfAction;
import com.powsybl.openloadflow.network.action.LfActionUtils;
import com.powsybl.openloadflow.network.action.OperatorStrategies;
import com.powsybl.openloadflow.network.impl.LfNetworkList;
import com.powsybl.openloadflow.network.impl.Networks;
import com.powsybl.openloadflow.network.impl.PropagatedContingency;
import com.powsybl.openloadflow.network.impl.PropagatedContingencyCreationParameters;
import com.powsybl.openloadflow.sa.extensions.ContingencyLoadFlowParameters;
import com.powsybl.openloadflow.util.Indexed;
import com.powsybl.openloadflow.util.Lists2;
import com.powsybl.openloadflow.util.PerUnit;
import com.powsybl.openloadflow.util.Reports;
import com.powsybl.openloadflow.util.mt.ContingencyMultiThreadHelper;
import com.powsybl.security.*;
import com.powsybl.security.limitreduction.LimitReduction;
import com.powsybl.security.monitor.StateMonitor;
import com.powsybl.security.monitor.StateMonitorIndex;
import com.powsybl.security.results.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.event.Level;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * @author Geoffroy Jamgotchian {@literal <geoffroy.jamgotchian at rte-france.com>}
 */
public abstract class AbstractSecurityAnalysis<V extends Enum<V> & Quantity, E extends Enum<E> & Quantity,
                                               P extends AbstractLoadFlowParameters<P>,
                                               C extends LoadFlowContext<V, E, P>,
                                               R extends com.powsybl.openloadflow.lf.LoadFlowResult> {

    protected static final Logger LOGGER = LoggerFactory.getLogger(AbstractSecurityAnalysis.class);

    /** {@code OLF_SA_PROFILE=1}: split the per-contingency wall time into the four things the engine
     *  does around the simulation itself — building the LfContingency (which runs the connectivity
     *  analysis), applying it, pre-distributing the lost active power, and restoring the base network
     *  state. Printed once per component after the contingency loop. Off by default and read once, so
     *  the timers cost nothing normally. */
    private static final boolean SA_PROFILE = System.getenv("OLF_SA_PROFILE") != null;

    private static final java.util.concurrent.atomic.LongAdder TO_LF_NS = new java.util.concurrent.atomic.LongAdder();
    private static final java.util.concurrent.atomic.LongAdder APPLY_NS = new java.util.concurrent.atomic.LongAdder();
    private static final java.util.concurrent.atomic.LongAdder LOSS_NS = new java.util.concurrent.atomic.LongAdder();
    private static final java.util.concurrent.atomic.LongAdder SIM_NS = new java.util.concurrent.atomic.LongAdder();
    private static final java.util.concurrent.atomic.LongAdder RESTORE_NS = new java.util.concurrent.atomic.LongAdder();

    protected final Network network;

    protected final MatrixFactory matrixFactory;

    protected final GraphConnectivityFactory<LfBus, LfBranch> connectivityFactory;

    protected final StateMonitorIndex monitorIndex;

    protected StateMonitorIndex zeroImpedanceMonitoredIndex;

    protected final ReportNode reportNode;

    protected Level logLevel = Level.INFO; // level of the post contingency and action logs

    protected AbstractSecurityAnalysis(Network network, MatrixFactory matrixFactory, GraphConnectivityFactory<LfBus, LfBranch> connectivityFactory,
                                       List<StateMonitor> stateMonitors, ReportNode reportNode) {
        this.network = Objects.requireNonNull(network);
        this.matrixFactory = Objects.requireNonNull(matrixFactory);
        this.connectivityFactory = Objects.requireNonNull(connectivityFactory);
        this.monitorIndex = new StateMonitorIndex(stateMonitors);
        this.reportNode = Objects.requireNonNull(reportNode);
    }

    protected abstract LoadFlowModel getLoadFlowModel();

    protected static SecurityAnalysisResult createNoResult() {
        return new SecurityAnalysisResult(new LimitViolationsResult(Collections.emptyList()), LoadFlowResult.ComponentResult.Status.FAILED, Collections.emptyList());
    }

    public CompletableFuture<SecurityAnalysisReport> run(String workingVariantId, SecurityAnalysisParameters securityAnalysisParameters,
                                                         ContingenciesProvider contingenciesProvider, ComputationManager computationManager,
                                                         List<OperatorStrategy> operatorStrategies, List<Action> actions, List<LimitReduction> limitReductions) {
        Objects.requireNonNull(workingVariantId);
        Objects.requireNonNull(securityAnalysisParameters);
        Objects.requireNonNull(contingenciesProvider);
        return CompletableFutureTask.runAsync(() -> runSync(securityAnalysisParameters, contingenciesProvider, operatorStrategies,
            actions, limitReductions, workingVariantId, computationManager.getExecutor()), computationManager.getExecutor());
    }

    protected abstract ReportNode createSaRootReportNode();

    protected abstract boolean isShuntCompensatorVoltageControlOn(LoadFlowParameters lfParameters);

    protected abstract P createParameters(LoadFlowParameters lfParameters, OpenLoadFlowParameters lfParametersExt, boolean breakers, boolean areas);

    protected void checkSupportedActions(List<Action> actions) {
    }

    SecurityAnalysisReport runSync(SecurityAnalysisParameters securityAnalysisParameters, ContingenciesProvider contingenciesProvider,
                                   List<OperatorStrategy> operatorStrategies, List<Action> actions, List<LimitReduction> limitReductions,
                                   String workingVariantId, Executor executor) throws ExecutionException {
        var saReportNode = createSaRootReportNode();

        Stopwatch stopwatch = Stopwatch.createStarted();

        LoadFlowParameters lfParameters = securityAnalysisParameters.getLoadFlowParameters();
        OpenLoadFlowParameters lfParametersExt = OpenLoadFlowParameters.get(securityAnalysisParameters.getLoadFlowParameters());
        OpenSecurityAnalysisParameters securityAnalysisParametersExt = OpenSecurityAnalysisParameters.getOrDefault(securityAnalysisParameters);

        if ((lfParametersExt.isAcDcNetwork()) && (lfParameters.isDc())) {
            throw new PowsyblException("Security analysis does not support DC load flow on AC-DC networks");
        }

        network.getVariantManager().setWorkingVariant(workingVariantId);

        // load contingencies
        List<Contingency> contingencies = contingenciesProvider.getContingencies(network);

        LOGGER.info("Running {} security analysis on {} contingencies on {} threads",
                getLoadFlowModel() == LoadFlowModel.AC ? "AC" : "DC", contingencies.size(), securityAnalysisParametersExt.getThreadCount());

        // check all actions are supported
        checkSupportedActions(actions);

        // check actions validity
        Actions.checkValidity(network, actions);

        // try for find all switches to be operated as actions.
        LfTopoConfig topoConfig = new LfTopoConfig();
        Actions.addAllSwitchesToOperate(topoConfig, network, actions);

        // try to find all ptc and rtc to retain because involved in ptc and rtc actions
        Actions.addAllPtcToOperate(topoConfig, actions);
        Actions.addAllRtcToOperate(topoConfig, actions);
        // try to find all shunts which section can change through actions.
        Actions.addAllShuntsToOperate(topoConfig, actions);
        // try to find disconnected shunts that a terminals connection action may reconnect.
        Actions.addAllShuntsToClose(topoConfig, network, actions);

        // try to find branches (lines and two windings transformers).
        // tie lines and three windings transformers missing.
        Actions.addAllBranchesToClose(topoConfig, network, actions);

        // try to find all switches impacted by at least one contingency and for each contingency the branches impacted
        PropagatedContingencyCreationParameters creationParameters = new PropagatedContingencyCreationParameters()
                .setContingencyPropagation(securityAnalysisParametersExt.isContingencyPropagation())
                .setShuntCompensatorVoltageControlOn(isShuntCompensatorVoltageControlOn(lfParameters))
                .setSlackDistributionOnConformLoad(lfParameters.getBalanceType() == LoadFlowParameters.BalanceType.PROPORTIONAL_TO_CONFORM_LOAD)
                .setHvdcAcEmulation(lfParameters.isHvdcAcEmulation());

        SecurityAnalysisResult finalResult;

        if (securityAnalysisParametersExt.getThreadCount() == 1) {
            List<PropagatedContingency> propagatedContingencies = PropagatedContingency.createList(network, contingencies, topoConfig, creationParameters);

            var parameters = createParameters(lfParameters, lfParametersExt, topoConfig.isBreaker(), isAreaInterchangeControl(lfParametersExt, contingencies));

            // create networks including all necessary switches
            try (LfNetworkList lfNetworks = Networks.loadWithReconnectableElements(network, topoConfig, parameters.getNetworkParameters(), saReportNode)) {
                finalResult = runSimulationsOnAllComponents(lfNetworks, propagatedContingencies, parameters,
                        securityAnalysisParameters, operatorStrategies, actions, limitReductions, lfParameters);
            }

        } else {
            var contingenciesPartitions = Lists2.partition(contingencies, securityAnalysisParametersExt.getThreadCount());

            OperatorStrategies.check(operatorStrategies, contingencies, actions);

            // we pre-allocate the results so that threads can set result in a stable order (using the partition number)
            // so that we always get results in the same order whatever threads completion order is.
            // init to no result in case of cancel
            List<SecurityAnalysisResult> partitionResults = Collections.synchronizedList(new ArrayList<>(Collections.nCopies(contingenciesPartitions.size(), createNoResult())));

            ContingencyMultiThreadHelper.ParameterProvider<P> parameterProvider = partitionTopoConfig ->
                createParameters(lfParameters, lfParametersExt, partitionTopoConfig.isBreaker(), isAreaInterchangeControl(lfParametersExt, contingencies));
            ContingencyMultiThreadHelper.ContingencyRunner<P> contingencyRunner = (partitionNum, lfNetworks, propagatedContingencies, parameters) ->
                    partitionResults.set(partitionNum, runSimulationsOnAllComponents(
                            lfNetworks, propagatedContingencies, parameters, securityAnalysisParameters, operatorStrategies,
                            actions, limitReductions, lfParameters));
            ContingencyMultiThreadHelper.ReportMerger reportMerger = ContingencyMultiThreadHelper::mergeReportThreadResults;
            ContingencyMultiThreadHelper.createLFNetworksPerContingencyPartitionAndRunAnalysis(network, workingVariantId, contingenciesPartitions, creationParameters, topoConfig,
                    parameterProvider, contingencyRunner, saReportNode, reportMerger, executor);

            // we just need to merge post contingency and operator strategy results, all pre contingency are the same
            List<PostContingencyResult> postContingencyResults = new ArrayList<>();
            List<OperatorStrategyResult> operatorStrategyResults = new ArrayList<>();
            for (var partitionResult : partitionResults) {
                postContingencyResults.addAll(partitionResult.getPostContingencyResults());
                operatorStrategyResults.addAll(partitionResult.getOperatorStrategyResults());
            }
            finalResult = new SecurityAnalysisResult(partitionResults.get(0).getPreContingencyResult(), postContingencyResults, operatorStrategyResults);
        }

        stopwatch.stop();
        LOGGER.info("Security analysis {} in {} ms", Thread.currentThread().isInterrupted() ? "cancelled" : "done",
                stopwatch.elapsed(TimeUnit.MILLISECONDS));

        return new SecurityAnalysisReport(finalResult);
    }

    SecurityAnalysisResult runSimulationsOnAllComponents(LfNetworkList networks, List<PropagatedContingency> propagatedContingencies, P parameters,
                                                         SecurityAnalysisParameters securityAnalysisParameters, List<OperatorStrategy> operatorStrategies,
                                                         List<Action> actions, List<LimitReduction> limitReductions,
                                                         LoadFlowParameters lfParameters) {
        for (LfNetwork lfNetwork : networks.getList()) {
            if (lfNetwork.getSynchronousNetworks().size() > 1) {
                throw new PowsyblException("Security analysis does not support AC-DC networks with multiple synchronous components");
            }
        }
        List<LfNetwork> networkToSimulate = new ArrayList<>(getNetworksToSimulate(networks, lfParameters.getComponentMode()));
        OpenSecurityAnalysisParameters openSecurityAnalysisParameters = OpenSecurityAnalysisParameters.getOrDefault(securityAnalysisParameters);
        ContingencyActivePowerLossDistribution contingencyActivePowerLossDistribution = ContingencyActivePowerLossDistribution.find(
            openSecurityAnalysisParameters.getContingencyActivePowerLossDistribution());

        if (networkToSimulate.isEmpty()) {
            return createNoResult();
        }

        // run simulation on first lfNetwork to initialize results structures
        LfNetwork firstNetwork = networkToSimulate.removeFirst();
        SecurityAnalysisResult result = runSimulations(firstNetwork, propagatedContingencies, parameters, securityAnalysisParameters,
                operatorStrategies, actions, limitReductions, contingencyActivePowerLossDistribution);
        double preContingencyDistributedActivePower = result.getPreContingencyResult().getDistributedActivePower();

        List<PostContingencyResult> postContingencyResults = result.getPostContingencyResults();
        List<OperatorStrategyResult> operatorStrategyResults = result.getOperatorStrategyResults();
        NetworkResult mergedPreContingencyNetworkResult = result.getPreContingencyResult().getNetworkResult();
        List<LimitViolation> preContingencyViolations = result.getPreContingencyResult().getLimitViolationsResult().getLimitViolations();

        Map<String, PostContingencyResult> postContingencyResultMap = new LinkedHashMap<>();
        Map<String, OperatorStrategyResult> operatorStrategyResultMap = new LinkedHashMap<>();
        postContingencyResults.forEach(r -> postContingencyResultMap.put(r.getContingency().getId(), r));
        operatorStrategyResults.forEach(r -> operatorStrategyResultMap.put(r.getOperatorStrategy().getId(), r));

        // Ensure the lists are writable and can be extended
        preContingencyViolations = new ArrayList<>(preContingencyViolations);

        for (LfNetwork n : networkToSimulate) {
            SecurityAnalysisResult resultOtherComponent = runSimulations(n, propagatedContingencies, parameters, securityAnalysisParameters,
                    operatorStrategies, actions, limitReductions, contingencyActivePowerLossDistribution);

            // Merge into first result
            preContingencyDistributedActivePower += resultOtherComponent.getPreContingencyResult().getDistributedActivePower();
            // PreContingency results first
            preContingencyViolations.addAll(resultOtherComponent.getPreContingencyResult().getLimitViolationsResult().getLimitViolations());
            mergedPreContingencyNetworkResult = mergeNetworkResult(mergedPreContingencyNetworkResult, resultOtherComponent.getPreContingencyResult().getNetworkResult());

            // PostContingency and OperatorStrategies results
            mergeSecurityAnalysisResult(resultOtherComponent, postContingencyResultMap, operatorStrategyResultMap, n.getNumCC());
        }
        postContingencyResults = postContingencyResultMap.values().stream().toList();
        operatorStrategyResults = operatorStrategyResultMap.values().stream().toList();

        PreContingencyResult mergedPrecontingencyResult =
            new PreContingencyResult(result.getPreContingencyResult().getStatus(),
                new LimitViolationsResult(preContingencyViolations),
                mergedPreContingencyNetworkResult, preContingencyDistributedActivePower);
        return new SecurityAnalysisResult(mergedPrecontingencyResult, postContingencyResults, operatorStrategyResults);
    }

    static List<LfNetwork> getNetworksToSimulate(LfNetworkList networks, LoadFlowParameters.ComponentMode mode) {
        return switch (mode) {
            case MAIN_CONNECTED -> networks.getList().stream()
                    .filter(n -> n.getNumCC() == ComponentConstants.MAIN_NUM && n.getValidity().equals(LfNetwork.Validity.VALID)).toList();
            case MAIN_SYNCHRONOUS -> networks.getList().stream().filter(n -> {
                // Security analysis does not support LfNetwork with several synchronous networks. An earlier stage
                // safeguard allows to assume there is only one synchronous network in each LfNetwork.
                assert n.getSynchronousNetworks().size() == 1;
                return n.getSynchronousNetworks().getFirst().getNumSC() == ComponentConstants.MAIN_NUM && n.getValidity().equals(LfNetwork.Validity.VALID);
            }).toList();
            case ALL_CONNECTED -> networks.getList().stream()
                    .filter(n -> n.getValidity().equals(LfNetwork.Validity.VALID)).toList();
        };
    }

    void mergeSecurityAnalysisResult(SecurityAnalysisResult resultToMerge, Map<String, PostContingencyResult> postContingencyResults,
                                     Map<String, OperatorStrategyResult> operatorStrategyResults, int connectedComponentNum) {
        resultToMerge.getPostContingencyResults().forEach(postContingencyResult -> {
            String contingencyId = postContingencyResult.getContingency().getId();
            PostContingencyResult originalResult = postContingencyResults.get(contingencyId);

            if (originalResult != null) {
                warnDifferentStatus(originalResult.getStatus(), postContingencyResult.getStatus(), connectedComponentNum,
                    "post contingency", postContingencyResult.getContingency().getId());
                NetworkResult mergedNetworkResult = mergeNetworkResult(originalResult.getNetworkResult(), postContingencyResult.getNetworkResult());
                List<LimitViolation> violations = new ArrayList<>(postContingencyResult.getLimitViolationsResult().getLimitViolations());
                violations.addAll(originalResult.getLimitViolationsResult().getLimitViolations());

                PostContingencyResult mergedPostContingencyResult =
                        new PostContingencyResult(originalResult.getContingency(), originalResult.getStatus(),
                                new LimitViolationsResult(violations), mergedNetworkResult, originalResult.getConnectivityResult(),
                                originalResult.getDistributedActivePower() + postContingencyResult.getDistributedActivePower());
                postContingencyResults.put(contingencyId, mergedPostContingencyResult);
            } else {
                postContingencyResults.put(contingencyId, postContingencyResult);
            }
        });

        resultToMerge.getOperatorStrategyResults().forEach(operatorStrategyResult -> {
            String strategyId = operatorStrategyResult.getOperatorStrategy().getId();
            OperatorStrategyResult originalResult = operatorStrategyResults.get(strategyId);
            if (originalResult != null) {
                List<OperatorStrategyResult.ConditionalActionsResult> conditionalActionsResults = new ArrayList<>();

                operatorStrategyResult.getConditionalActionsResults()
                    .forEach(conditionalActionsResult -> addConditionalActionsResults(conditionalActionsResult, originalResult, connectedComponentNum, conditionalActionsResults));
                operatorStrategyResults.put(strategyId, new OperatorStrategyResult(originalResult.getOperatorStrategy(), conditionalActionsResults));
            } else {
                operatorStrategyResults.put(strategyId, operatorStrategyResult);
            }
        });
    }

    private void addConditionalActionsResults(OperatorStrategyResult.ConditionalActionsResult conditionalActionsResult,
                                              OperatorStrategyResult originalResult, int connectedComponentNum,
                                              List<OperatorStrategyResult.ConditionalActionsResult> conditionalActionsResults) {
        Optional<OperatorStrategyResult.ConditionalActionsResult> originalRes = originalResult.getConditionalActionsResults().stream()
            .filter(originalConditionalActionResult -> originalConditionalActionResult.getConditionalActionsId().equals(conditionalActionsResult.getConditionalActionsId()))
            .findAny();
        if (originalRes.isPresent()) {
            warnDifferentStatus(originalRes.get().getStatus(), conditionalActionsResult.getStatus(),
                connectedComponentNum, "conditional actions", conditionalActionsResult.getConditionalActionsId());
            NetworkResult mergedNetworkResult = mergeNetworkResult(originalRes.get().getNetworkResult(), conditionalActionsResult.getNetworkResult());
            List<LimitViolation> violations = new ArrayList<>(conditionalActionsResult.getLimitViolationsResult().getLimitViolations());
            violations.addAll(originalResult.getLimitViolationsResult().getLimitViolations());

            OperatorStrategyResult.ConditionalActionsResult mergedConditionalActionResult
                = new OperatorStrategyResult.ConditionalActionsResult(conditionalActionsResult.getConditionalActionsId(),
                conditionalActionsResult.getStatus(), new LimitViolationsResult(violations), mergedNetworkResult,
                originalRes.get().getDistributedActivePower() + conditionalActionsResult.getDistributedActivePower());
            conditionalActionsResults.add(mergedConditionalActionResult);

        } else {
            conditionalActionsResults.add(conditionalActionsResult);
        }
    }

    void warnDifferentStatus(PostContingencyComputationStatus mainStatus, PostContingencyComputationStatus subComponentStatus, int subComponentNum, String stage, String stageId) {
        if (mainStatus != subComponentStatus) {
            LOGGER.warn("Component {} {} {} result being merged has status {} while main connected component has status {}." +
                    " Status of component {} will not be represented in the output.",
                subComponentNum, stage, stageId, subComponentStatus, mainStatus, subComponentNum);
        }
    }

    private static <T> ArrayList<T> ensureMutable(List<T> orig) {
        return orig instanceof ArrayList<T> arrayList ? arrayList : new ArrayList<>(orig);
    }

    static NetworkResult mergeNetworkResult(NetworkResult source, NetworkResult target) {
        // Copy the lists if they are not writable
        ArrayList<BranchResult> branchResults = ensureMutable(source.getBranchResults());
        ArrayList<ThreeWindingsTransformerResult> twtResults = ensureMutable(source.getThreeWindingsTransformerResults());
        ArrayList<BusResult> busResults = ensureMutable(source.getBusResults());
        branchResults.addAll(target.getBranchResults());
        twtResults.addAll(target.getThreeWindingsTransformerResults());
        busResults.addAll(target.getBusResults());
        return new NetworkResult(branchResults, busResults, twtResults);
    }

    protected abstract PostContingencyComputationStatus postContingencyStatusFromLoadFlowResult(R result);

    private static boolean checkCondition(ConditionalActions conditionalActions, LimitViolationsResult limitViolationsResult, LfNetwork lfNetwork) {
        switch (conditionalActions.getCondition().getType()) {
            case TrueCondition.NAME:
                return true;
            case AnyViolationCondition.NAME: {
                AnyViolationCondition anyCondition = (AnyViolationCondition) conditionalActions.getCondition();
                var limitViolationEquipmentIds = filterLimitViolationEquipmentIds(anyCondition.getFilters(), limitViolationsResult);
                return !limitViolationEquipmentIds.isEmpty();
            }
            case AtLeastOneViolationCondition.NAME: {
                AtLeastOneViolationCondition atLeastOneCondition = (AtLeastOneViolationCondition) conditionalActions.getCondition();
                var limitViolationEquipmentIds = filterLimitViolationEquipmentIds(atLeastOneCondition.getFilters(), limitViolationsResult);
                Set<String> commonEquipmentIds = atLeastOneCondition.getViolationIds().stream()
                        .distinct()
                        .filter(limitViolationEquipmentIds::contains)
                        .collect(Collectors.toSet());
                return !commonEquipmentIds.isEmpty();
            }
            case AllViolationCondition.NAME: {
                AllViolationCondition allCondition = (AllViolationCondition) conditionalActions.getCondition();
                var limitViolationEquipmentIds = filterLimitViolationEquipmentIds(allCondition.getFilters(), limitViolationsResult);
                Set<String> commonEquipmentIds = allCondition.getViolationIds().stream()
                        .distinct()
                        .filter(limitViolationEquipmentIds::contains)
                        .collect(Collectors.toSet());
                return commonEquipmentIds.equals(new HashSet<>(allCondition.getViolationIds()));
            }
            case BranchThresholdCondition.NAME, ThreeWindingsTransformerThresholdCondition.NAME, InjectionThresholdCondition.NAME: {
                return ThresholdConditionEvaluator.evaluate(lfNetwork, conditionalActions.getCondition());
            }
            default:
                throw new UnsupportedOperationException("Unsupported condition type: " + conditionalActions.getCondition().getType());
        }
    }

    private static Set<String> filterLimitViolationEquipmentIds(Set<LimitViolationType> filters, LimitViolationsResult limitViolationsResult) {
        return limitViolationsResult.getLimitViolations().stream()
            .filter(violation -> filters.isEmpty() || filters.contains(violation.getLimitType()))
            .map(LimitViolation::getSubjectId)
            .collect(Collectors.toSet());
    }

    protected List<String> checkCondition(OperatorStrategy operatorStrategy, LimitViolationsResult limitViolationsResult, LfNetwork postContingencyState) {
        List<String> actionsIds = new ArrayList<>();
        for (ConditionalActions conditionalActions : operatorStrategy.getConditionalActions()) {
            if (checkCondition(conditionalActions, limitViolationsResult, postContingencyState)) {
                actionsIds.addAll(conditionalActions.getActionIds());
            }
        }
        return actionsIds;
    }

    boolean isAreaInterchangeControl(OpenLoadFlowParameters lfParametersExt, List<Contingency> contingencies) {
        return lfParametersExt.isAreaInterchangeControl() ||
                contingencies.stream()
                        .map(contingency -> contingency.getExtension(ContingencyLoadFlowParameters.class))
                        .filter(Objects::nonNull)
                        .map(ContingencyLoadFlowParameters.class::cast)
                        .anyMatch(contingencyParameters -> contingencyParameters.isAreaInterchangeControl().orElse(false));
    }

    protected abstract C createLoadFlowContext(LfNetwork lfNetwork, P parameters);

    protected abstract LoadFlowEngine<V, E, P, R> createLoadFlowEngine(C context);

    private boolean checkZeroImpedanceLine(LfNetwork lfNetwork, String id) {
        LfBranch lfBranch = lfNetwork.getBranchById(id);
        return lfBranch != null && lfBranch.isZeroImpedance(getLoadFlowModel());
    }

    private boolean checkZeroImpedanceT3WT(LfNetwork lfNetwork, String id) {
        String leg = "_leg_";
        LfBranch leg1 = lfNetwork.getBranchById(id + leg + 1);
        if (leg1 != null && leg1.isZeroImpedance(getLoadFlowModel())) {
            return true;
        }
        LfBranch leg2 = lfNetwork.getBranchById(id + leg + 2);
        if (leg2 != null && leg2.isZeroImpedance(getLoadFlowModel())) {
            return true;
        }
        LfBranch leg3 = lfNetwork.getBranchById(id + leg + 3);
        return leg3 != null && leg3.isZeroImpedance(getLoadFlowModel());
    }

    private StateMonitor extractZeroImpedanceStateMonitor(StateMonitor stateMonitor, LfNetwork lfNetwork) {
        ContingencyContext contingencyContext = stateMonitor.getContingencyContext();
        Set<String> branchIds = stateMonitor.getBranchIds().stream().filter(id -> checkZeroImpedanceLine(lfNetwork, id)).collect(Collectors.toSet());
        Set<String> threeWindingTransformerIds = stateMonitor.getThreeWindingsTransformerIds().stream().filter(id -> checkZeroImpedanceT3WT(lfNetwork, id)).collect(Collectors.toSet());
        return new StateMonitor(contingencyContext, branchIds, new HashSet<>(), threeWindingTransformerIds);
    }

    protected List<StateMonitor> extractZeroImpedanceStateMonitors(LfNetwork lfNetwork) {
        List<StateMonitor> zeroImpedanceStateMonitors = new ArrayList<>();
        // All
        zeroImpedanceStateMonitors.add(extractZeroImpedanceStateMonitor(this.monitorIndex.getAllStateMonitor(), lfNetwork));
        // None
        zeroImpedanceStateMonitors.add(extractZeroImpedanceStateMonitor(this.monitorIndex.getNoneStateMonitor(), lfNetwork));
        // Contingency related
        List<StateMonitor> specificStateMonitors = this.monitorIndex.getSpecificStateMonitors().values().stream().map(sm -> extractZeroImpedanceStateMonitor(sm, lfNetwork)).toList();
        zeroImpedanceStateMonitors.addAll(specificStateMonitors);

        return zeroImpedanceStateMonitors;
    }

    protected P copyParameters(P parameters) {
        return parameters;
    }

    protected void afterPreContingencySimulation(P parameters) {
    }

    protected SecurityAnalysisResult runSimulations(LfNetwork lfNetwork, List<PropagatedContingency> propagatedContingencies, P acParameters,
                                                    SecurityAnalysisParameters securityAnalysisParameters, List<OperatorStrategy> operatorStrategies,
                                                    List<Action> actions, List<LimitReduction> limitReductions, ContingencyActivePowerLossDistribution contingencyActivePowerLossDistribution) {
        Map<String, Action> actionsById = Actions.indexById(actions);

        // In MT the operator strategy check is performed before running the simulations
        boolean checkOperatorStrategies = OpenSecurityAnalysisParameters.getOrDefault(securityAnalysisParameters).getThreadCount() == 1;

        Map<String, List<Indexed<OperatorStrategy>>> operatorStrategiesByContingencyId =
                OperatorStrategies.indexByContingencyId(propagatedContingencies, operatorStrategies, actionsById,
                        checkOperatorStrategies);
        Set<Action> neededActions = OperatorStrategies.getNeededActions(operatorStrategiesByContingencyId, actionsById);

        Map<String, LfAction> lfActionById = LfActionUtils.createLfActions(lfNetwork, neededActions, network); // only convert needed actions

        LoadFlowParameters loadFlowParameters = securityAnalysisParameters.getLoadFlowParameters();
        OpenLoadFlowParameters openLoadFlowParameters = OpenLoadFlowParameters.get(loadFlowParameters);
        OpenSecurityAnalysisParameters openSecurityAnalysisParameters = OpenSecurityAnalysisParameters.getOrDefault(securityAnalysisParameters);
        boolean createResultExtension = openSecurityAnalysisParameters.isCreateResultExtension();

        P p = copyParameters(acParameters);

        try (C context = createLoadFlowContext(lfNetwork, p)) {
            ReportNode networkReportNode = lfNetwork.getReportNode();
            ReportNode preContSimReportNode = Reports.createPreContingencySimulation(networkReportNode);
            lfNetwork.setReportNode(preContSimReportNode);

            // run pre-contingency simulation
            R preContingencyLoadFlowResult = createLoadFlowEngine(context)
                    .run();

            boolean preContingencyComputationOk = preContingencyLoadFlowResult.isSuccess();
            var preContingencyLimitViolationManager = new LimitViolationManager(limitReductions);
            List<PostContingencyResult> postContingencyResults = new ArrayList<>();
            LoadFlowModel loadFlowModel = securityAnalysisParameters.getLoadFlowParameters().isDc() ? LoadFlowModel.DC : LoadFlowModel.AC;
            List<StateMonitor> zeroImpedanceStateMonitors = extractZeroImpedanceStateMonitors(lfNetwork);
            this.zeroImpedanceMonitoredIndex = new StateMonitorIndex(zeroImpedanceStateMonitors);
            var preContingencyNetworkResult = new PreContingencyNetworkResult(lfNetwork,
                new AbstractNetworkResult.StateMonitorIndexes(monitorIndex, zeroImpedanceMonitoredIndex), createResultExtension,
                    loadFlowModel, securityAnalysisParameters.getLoadFlowParameters().getDcPowerFactor());
            List<OperatorStrategyResult> operatorStrategyResults = new ArrayList<>();

            // only run post-contingency simulations if pre-contingency simulation is ok
            if (preContingencyComputationOk) {
                afterPreContingencySimulation(p);

                // update network result
                preContingencyNetworkResult.update();

                // detect violations
                preContingencyLimitViolationManager.detectViolations(lfNetwork);

                // save base state for later restoration after each contingency
                NetworkState networkState = NetworkState.save(lfNetwork);

                // Reset parameters for next component
                Consumer<P> componentParametersResetter = createParametersResetter(p);

                // openLoadFlow parameters can be overriden by security analys parameters - may modify p
                OpenLoadFlowParameters contingencyOpenLoadFlowParameters = applyGenericContingencyParameters(p, loadFlowParameters, openLoadFlowParameters, openSecurityAnalysisParameters);

                // Reset parameters between contingencies
                Consumer<P> contingencyParametersResetter = createParametersResetter(p);

                // start a simulation for each of the contingency
                Iterator<PropagatedContingency> contingencyIt = propagatedContingencies.iterator();
                while (contingencyIt.hasNext() && !Thread.currentThread().isInterrupted()) {
                    PropagatedContingency propagatedContingency = contingencyIt.next();
                    long tToLf = System.nanoTime();
                    Optional<LfContingency> lfContingencyOpt = propagatedContingency.toLfContingency(lfNetwork);
                    if (SA_PROFILE) {
                        TO_LF_NS.add(System.nanoTime() - tToLf);
                    }
                    lfContingencyOpt
                            .ifPresent(lfContingency -> processContingency(lfNetwork, securityAnalysisParameters,
                                limitReductions, contingencyActivePowerLossDistribution,
                                networkReportNode, lfContingency, p, networkState,
                                propagatedContingency, context, lfActionById,
                                loadFlowParameters, contingencyOpenLoadFlowParameters,
                                createResultExtension, preContingencyLimitViolationManager,
                                preContingencyNetworkResult, postContingencyResults,
                                contingencyParametersResetter, operatorStrategiesByContingencyId,
                                operatorStrategyResults, contingencyIt));
                }

                if (SA_PROFILE) {
                    System.err.printf("SA_PROFILE toLfContingency=%d ms apply=%d ms lossDistribution=%d ms "
                                    + "runPostContingencySimulation=%d ms networkState.restore=%d ms%n",
                            TO_LF_NS.sum() / 1_000_000, APPLY_NS.sum() / 1_000_000, LOSS_NS.sum() / 1_000_000,
                            SIM_NS.sum() / 1_000_000, RESTORE_NS.sum() / 1_000_000);
                    System.err.println("SA_PROFILE detectViolations " + LimitViolationManager.profile());
                }

                // Restore parameters in case they are used for another component
                componentParametersResetter.accept(p);
            }

            return new SecurityAnalysisResult(
                    new PreContingencyResult(
                            preContingencyLoadFlowResult.toComponentResultStatus().status(),
                            new LimitViolationsResult(preContingencyLimitViolationManager.getLimitViolations()),
                            new NetworkResult(preContingencyNetworkResult.getBranchResults(), preContingencyNetworkResult.getBusResults(),
                            preContingencyNetworkResult.getThreeWindingsTransformerResults()),
                            preContingencyLoadFlowResult.getDistributedActivePower() * PerUnit.SB),
                    postContingencyResults, operatorStrategyResults);
        }
    }

    /**
     * @return a consumer for Ac/DcLoadFlowParameters that resets them to their original state, in case they have been modified according
     * to the ContingencyLoadFlowParameters extension with {@link #applySpecificContingencyParameters}.
     */
    protected abstract Consumer<P> createParametersResetter(P parameters);

    /**
     * Applies the custom parameters overridden by the openSecurityAnalysisParameters
     */
    protected abstract OpenLoadFlowParameters applyGenericContingencyParameters(P parameters,
                                                              LoadFlowParameters loadFlowParameters, OpenLoadFlowParameters openLoadFlowParameters,
                                                              OpenSecurityAnalysisParameters openSecurityAnalysisParameters);

    /**
     * Applies the custom parameters that are contained in the ContingencyLoadFlowParameters extension for a specific contingency.
     * If the extension is present, modifies the ac/dcLoadFlowParameters contained in the LoadFlowContext accordingly.
     */
    protected abstract void applySpecificContingencyParameters(P parameters, ContingencyLoadFlowParameters contingencyParameters,
                                                               LoadFlowParameters loadFlowParameters, OpenLoadFlowParameters openLoadFlowParameters);

    private Optional<OperatorStrategyResult> runActionSimulation(LfNetwork network, C context, OperatorStrategy operatorStrategy,
                                                                 LimitViolationManager preContingencyLimitViolationManager,
                                                                 SecurityAnalysisParameters securityAnalysisParameters,
                                                                 Map<String, LfAction> lfActionById, boolean createResultExtension, LfContingency lfContingency,
                                                                 Contingency contingency, PreContingencyNetworkResult preContingencyNetworkResult,
                                                                 LimitViolationsResult postContingencyLimitViolations, LfNetworkParameters networkParameters,
                                                                 List<LimitReduction> limitReductions) {
        OperatorStrategyResult operatorStrategyResult = null;

        List<String> actionIds = checkCondition(operatorStrategy, postContingencyLimitViolations, network);
        if (!actionIds.isEmpty()) {
            operatorStrategyResult = runActionSimulation(network, context, operatorStrategy, actionIds, preContingencyLimitViolationManager,
                    securityAnalysisParameters, lfActionById, createResultExtension, lfContingency, contingency, preContingencyNetworkResult, networkParameters, limitReductions);
        }

        return Optional.ofNullable(operatorStrategyResult);
    }

    protected PostContingencyResult runPostContingencySimulation(LfNetwork network, C context, Contingency contingency, LfContingency lfContingency,
                                                                 LimitViolationManager preContingencyLimitViolationManager,
                                                                 SecurityAnalysisParameters securityAnalysisParameters,
                                                                 PreContingencyNetworkResult preContingencyNetworkResult, boolean createResultExtension,
                                                                 List<LimitReduction> limitReductions, double preDistributedActivePower) {
        // Label every debug trace below with the contingency it belongs to (OlfTraceScope): DS_RUN /
        // DS_SPLIT / DS_SAT / DS_PASS / RL_SWITCH carry no id otherwise, so in a thousand-contingency
        // run none of them can be attributed.
        com.powsybl.openloadflow.util.OlfTraceScope.enter(contingency.getId());
        try {
            PostContingencyResult r = runPostContingencySimulationTraced(network, context, contingency, lfContingency,
                    preContingencyLimitViolationManager, securityAnalysisParameters,
                    preContingencyNetworkResult, createResultExtension, limitReductions, preDistributedActivePower);
            dumpEquationOnlyVariables(context, network);
            return r;
        } finally {
            com.powsybl.openloadflow.util.OlfTraceScope.leave();
        }
    }

    /**
     * {@code OLF_DUMMY_DUMP=<file>}: the CONVERGED value of every equation-only variable — the
     * zero-impedance couplers' {@code DUMMY_P}/{@code DUMMY_Q} — after this contingency's solve,
     * labelled by contingency and filtered by {@code OLF_TRACE_CTG}.
     *
     * <p>These are the only state variables {@code NetworkState}/{@code BusState} do NOT save, so
     * {@code restore()} between contingencies leaves them holding the PREVIOUS contingency's values:
     * measured on rte6515 BUS-4467_BBS as DISTR_Q terms evaluating to 519 pu at the post-contingency
     * start, which is a ~780 pu dummy and no physical coupler flow. Harmless for this solver, which
     * re-converges them — but it means the two arms of a GPU comparison enter each scenario from
     * DIFFERENT dummy values (a device that tiles the base state starts from the base's), and if the
     * dummy subsystem is underdetermined they can converge to different points of its null space.
     * Bus voltages would still agree while a DISTR_Q that REFERENCES a dummy enforces a different
     * reactive split. This dump is the test: do the converged dummies differ or not.
     */
    private void dumpEquationOnlyVariables(C context, LfNetwork network) {
        // OLF_DUMMY_DUMP: the equation-only variables alone. OLF_VAR_DUMP: EVERY state variable, keyed
        // by ELEMENT ID rather than element num, so the file joins directly against a device-side dump
        // without a row legend — which is what localizing a ~2e-08 state difference needs (an aggregate
        // norm says only that one exists; see tasks #66).
        String path = System.getenv("OLF_VAR_DUMP") != null
                ? System.getenv("OLF_VAR_DUMP") : System.getenv("OLF_DUMMY_DUMP");
        boolean all = System.getenv("OLF_VAR_DUMP") != null;
        if (path == null || !com.powsybl.openloadflow.util.OlfTraceScope.wanted()) {
            return;
        }
        try (java.io.Writer w = new java.io.FileWriter(path, true)) {
            var es = context.getEquationSystem();
            var sv = es.getStateVector();
            w.write("# VARS ctg=" + com.powsybl.openloadflow.util.OlfTraceScope.current()
                    + (all ? " all" : " dummyOnly") + "\n");
            for (com.powsybl.openloadflow.equations.Variable<V> v : es.getIndex().getSortedVariablesToFind()) {
                String t = v.getType().name();
                if (!all && !t.startsWith("DUMMY_")) {
                    continue;
                }
                String id;
                try {
                    id = switch (v.getType().getElementType()) {
                        case BUS -> network.getBus(v.getElementNum()).getId();
                        case BRANCH -> network.getBranch(v.getElementNum()).getId();
                        case SHUNT_COMPENSATOR -> network.getShunt(v.getElementNum()).getId();
                        default -> "#" + v.getElementNum();
                    };
                } catch (RuntimeException e) {
                    id = "#" + v.getElementNum();
                }
                w.write(t + " " + id + " " + sv.get(v.getRow()) + "\n");
            }
            // ...and, AT THE SAME POINT, every active equation's VALUE and TARGET. Both are read here,
            // post-solve: the equation vector was invalidated by the solve's state updates so getArray()
            // re-evaluates at the converged state, and the target vector carries the targets as the
            // reactive-limits loop and the DISTR_Q maintenance left them. That matters because a
            // BUS_TARGET_Q target is NOT constant through a solve -- a pin rewrites it with
            // setGenerationTargetQ(limit). Computing a residual from the PRE-solve targets printed by
            // EQ_DUMP_CTG's F0 section against a POST-solve state is meaningless, and doing exactly that
            // produced a false conclusion that this solver leaves 4.2e-03 on its own equations
            // (retracted, tasks #66). One point, one file, both quantities.
            if (all) {
                // A FRESH EquationVector rather than the context's: getEquationVector() is on the AC
                // context, not the generic one, and a fresh vector evaluates at the CURRENT (converged)
                // state, which is the point this dump is about. AutoCloseable -- it deregisters its
                // index listener, so it leaves the system exactly as it found it.
                double[] tg = context.getTargetVector().getArray();
                try (var ev = new com.powsybl.openloadflow.equations.EquationVector<>(context.getEquationSystem())) {
                double[] fx = ev.getArray();
                for (int c = 0; c < fx.length; c++) {
                    var eq = context.getEquationSystem().getIndex().getEquationAtColumn(c);
                    String id;
                    try {
                        id = switch (eq.getType().getElementType()) {
                            case BUS -> network.getBus(eq.getElementNum()).getId();
                            case BRANCH -> network.getBranch(eq.getElementNum()).getId();
                            case SHUNT_COMPENSATOR -> network.getShunt(eq.getElementNum()).getId();
                            default -> "#" + eq.getElementNum();
                        };
                    } catch (RuntimeException ex) {
                        id = "#" + eq.getElementNum();
                    }
                    double tv = c < tg.length ? tg[c] : 0.0;
                    w.write("EQ " + eq.getType() + " " + id + " " + fx[c] + " " + tv
                            + " " + (fx[c] - tv) + "\n");
                }
                }
            }
        } catch (java.io.IOException | RuntimeException e) {
            LOGGER.warn("variable dump failed: {}", e.toString());
        }
    }

    private PostContingencyResult runPostContingencySimulationTraced(LfNetwork network, C context, Contingency contingency,
                                                                 LfContingency lfContingency,
                                                                 LimitViolationManager preContingencyLimitViolationManager,
                                                                 SecurityAnalysisParameters securityAnalysisParameters,
                                                                 PreContingencyNetworkResult preContingencyNetworkResult, boolean createResultExtension,
                                                                 List<LimitReduction> limitReductions, double preDistributedActivePower) {
        logPostContingencyStart(network, lfContingency);

        Stopwatch stopwatch = Stopwatch.createStarted();

        // restart LF on post contingency equation system
        // Tag the distributed-slack pass trace with this contingency (OLF_DS_TRACE): the DS loop
        // stops on a 1 MW band, so the operating point depends on the SEQUENCE of passes, and a
        // sequence is only comparable against another implementation's once it can be attributed.
        R result;
        com.powsybl.openloadflow.ac.outerloop.DistributedSlackOuterLoop.CURRENT_CONTINGENCY.set(contingency.getId());
        try {
            result = createLoadFlowEngine(context).run();
        } finally {
            com.powsybl.openloadflow.ac.outerloop.DistributedSlackOuterLoop.CURRENT_CONTINGENCY.remove();
        }
        PostContingencyComputationStatus status = postContingencyStatusFromLoadFlowResult(result);
        // OLF_LF_STEPS_TRACE=<id>[,<id>]|all: how many steps this contingency took. Two
        // implementations that solve the same system in the same number of iterations agree to
        // machine precision; when they do not, the first thing to establish is whether the SEQUENCE
        // differed, not by how much the answers do. The distributed-slack loop stops on a 1 MW band,
        // so one pass more or less is worth ~0.1 MW of distributed power while every voltage still
        // looks converged.
        if (System.getenv("OLF_LF_STEPS_TRACE") != null && result instanceof com.powsybl.openloadflow.ac.AcLoadFlowResult acr) {
            String want = System.getenv("OLF_LF_STEPS_TRACE");
            boolean show = "all".equals(want);
            if (!show) {
                for (String w : want.split(",")) {
                    show = show || w.trim().equals(contingency.getId());
                }
            }
            if (show) {
                System.err.printf("LF_STEPS ctg=%s solverIterations=%d outerLoopIterations=%d "
                        + "status=%s distributedActivePower=%s slackMismatch=%s%n",
                        contingency.getId(), acr.getSolverIterations(), acr.getOuterLoopIterations(),
                        acr.getSolverStatus(), acr.getDistributedActivePower(),
                        acr.getSlackBusActivePowerMismatch());
            }
        }
        // OLF_RL_FINAL_TRACE: the pin state this contingency ENDED on, per controller bus. The
        // residual of another implementation's answer is taken against the targets the equation
        // system holds when that answer is injected - BEFORE these outer loops run - so it flags
        // every row the loops legitimately move and cannot say whether the two arms ended on the
        // same pins. This can: it is the FINAL state, printed once per contingency.
        if (System.getenv("OLF_RL_FINAL_TRACE") != null
                && matchesCtgFilter("OLF_RL_FINAL_TRACE_CTG", contingency.getId())) {
            String want = System.getenv("OLF_RL_FINAL_TRACE");
            network.<LfBus>getControllerElements(VoltageControl.Type.GENERATOR).forEach(bus -> {
                if (!"all".equals(want) && !bus.getId().startsWith(want)) {
                    return;
                }
                System.err.printf("RL_FINAL ctg=%s bus=%s qLimitType=%s vcEnabled=%b genTargetQ=%.12f v=%.12f%n",
                        contingency.getId(), bus.getId(),
                        bus.getQLimitType().map(Enum::name).orElse("none"),
                        bus.isGeneratorVoltageControlEnabled(), bus.getGenerationTargetQ(), bus.getV());
            });
        }
        var postContingencyLimitViolationManager = new LimitViolationManager(preContingencyLimitViolationManager, limitReductions, securityAnalysisParameters.getIncreasedViolationsParameters());

        LoadFlowModel loadFlowModel = securityAnalysisParameters.getLoadFlowParameters().isDc() ? LoadFlowModel.DC : LoadFlowModel.AC;
        var postContingencyNetworkResult = new PostContingencyNetworkResult(network, new AbstractNetworkResult.StateMonitorIndexes(monitorIndex, zeroImpedanceMonitoredIndex),
                createResultExtension, preContingencyNetworkResult, contingency, loadFlowModel, securityAnalysisParameters.getLoadFlowParameters().getDcPowerFactor(),
                securityAnalysisParameters.getModifiedMonitoredElementsParameters());

        if (status.equals(PostContingencyComputationStatus.CONVERGED)) {
            // update network result
            if (System.getenv("OLF_Q_PROBE") != null) {
                // The FINAL post-contingency q at a controller bus, computed exactly as
                // ReactiveLimitsOuterLoop.checkControllerBus does. The q printed by the RL trace is the
                // value AT THE CHECK, before the loop pins and re-solves — comparing that against another
                // implementation's converged q compares two different moments.
                for (LfBus b : network.getBuses()) {
                    if (java.util.Arrays.stream(System.getenv("OLF_Q_PROBE").split(","))
                            .anyMatch(b.getId()::startsWith)) {
                        double q = b.getQ().eval() + b.getLoadTargetQ();
                        // DISTR_Q shares the DEVIATION from targetQ, not q itself: its target is
                        // (qPct-1)*targetQ_i + qPct*sum_j targetQ_j (AcTargetVector), so two members
                        // with different targetQ legitimately settle at different q. Print the parts.
                        System.err.printf("Q_PROBE_CPU ctg=%s bus=%s q=%.9f genTgtQ=%.9f loadTgtQ=%.9f "
                                + "qPct=%.9f maxQ=%.9f overMaxBy=%.9f%n",
                                lfContingency.getId(), b.getId(), q, b.getGenerationTargetQ(), b.getLoadTargetQ(),
                                b.getRemoteControlReactivePercent(), b.getMaxQ(), q - b.getMaxQ());
                        // A CURVE reactive limit is a function of the GENERATOR's targetP, so a limit
                        // that disagrees between arms is a P disagreement, not a limit-model one.
                        for (var g : b.getGenerators()) {
                            System.err.printf("Q_PROBE_CPU_GEN ctg=%s bus=%s gen=%s targetP=%.9f minQ=%.9f maxQ=%.9f%n",
                                    lfContingency.getId(), b.getId(), g.getId(), g.getTargetP(), g.getMinQ(), g.getMaxQ());
                        }
                    }
                }
            }
            postContingencyNetworkResult.update();

            // detect violations
            postContingencyLimitViolationManager.detectViolations(network);
        }

        stopwatch.stop();
        logPostContingencyEnd(network, lfContingency, stopwatch);

        var connectivityResult = new ConnectivityResult(lfContingency.getCreatedSynchronousComponentsCount(), 0,
                lfContingency.getDisconnectedLoadActivePower() * PerUnit.SB,
                lfContingency.getDisconnectedGenerationActivePower() * PerUnit.SB,
                lfContingency.getDisconnectedElementIds());

        return new PostContingencyResult(
                contingency,
                status,
                new LimitViolationsResult(postContingencyLimitViolationManager.getLimitViolations()),
                new NetworkResult(postContingencyNetworkResult.getBranchResults(),
                postContingencyNetworkResult.getBusResults(),
                postContingencyNetworkResult.getThreeWindingsTransformerResults()),
                connectivityResult,
                (preDistributedActivePower + result.getDistributedActivePower()) * PerUnit.SB);
    }

    protected void logPostContingencyStart(LfNetwork network, LfContingency lfContingency) {
        LOGGER.atLevel(logLevel).log("Start post contingency '{}' simulation on network {}", lfContingency.getId(), network);
        LOGGER.debug("Contingency '{}' impact on network {}: remove {} buses, remove {} branches, remove {} generators, shift {} shunts, shift {} loads",
                lfContingency.getId(), network, lfContingency.getDisabledNetwork().getBuses(), lfContingency.getDisabledNetwork().getBranchesStatus(),
                lfContingency.getLostGenerators(), lfContingency.getShuntsShift(), lfContingency.getLostLoads());
    }

    protected void logPostContingencyEnd(LfNetwork network, LfContingency lfContingency, Stopwatch stopwatch) {
        LOGGER.atLevel(logLevel).log("Post contingency '{}' simulation done on network {} in {} ms", lfContingency.getId(),
                network, stopwatch.elapsed(TimeUnit.MILLISECONDS));
    }

    protected OperatorStrategyResult runActionSimulation(LfNetwork network, C context, OperatorStrategy operatorStrategy,
                                                         List<String> actionsIds,
                                                         LimitViolationManager preContingencyLimitViolationManager,
                                                         SecurityAnalysisParameters securityAnalysisParameters,
                                                         Map<String, LfAction> lfActionById, boolean createResultExtension, LfContingency lfContingency,
                                                         Contingency contingency, PreContingencyNetworkResult preContingencyNetworkResult,
                                                         LfNetworkParameters networkParameters, List<LimitReduction> limitReductions) {
        logActionStart(network, operatorStrategy);

        // get LF action for this operator strategy, as all actions have been previously checked against IIDM
        // network, an empty LF action means it is for another component (so another LF network) so we can
        // skip it
        List<LfAction> operatorStrategyLfActions = actionsIds.stream()
                .map(lfActionById::get)
                .filter(Objects::nonNull)
                .toList();

        LfActionUtils.applyListOfActions(operatorStrategyLfActions, network, lfContingency, networkParameters);

        Stopwatch stopwatch = Stopwatch.createStarted();

        // restart LF on post contingency and post actions equation system
        R result = createLoadFlowEngine(context).run();
        PostContingencyComputationStatus status = postContingencyStatusFromLoadFlowResult(result);
        var postActionsViolationManager = new LimitViolationManager(preContingencyLimitViolationManager, limitReductions, securityAnalysisParameters.getIncreasedViolationsParameters());
        LoadFlowModel loadFlowModel = securityAnalysisParameters.getLoadFlowParameters().isDc() ? LoadFlowModel.DC : LoadFlowModel.AC;
        var postActionsNetworkResult = new PostContingencyNetworkResult(network, new AbstractNetworkResult.StateMonitorIndexes(monitorIndex, zeroImpedanceMonitoredIndex), createResultExtension,
                preContingencyNetworkResult, contingency, loadFlowModel, securityAnalysisParameters.getLoadFlowParameters().getDcPowerFactor(),
                securityAnalysisParameters.getModifiedMonitoredElementsParameters());

        if (status.equals(PostContingencyComputationStatus.CONVERGED)) {
            // update network result
            postActionsNetworkResult.update();

            // detect violations
            postActionsViolationManager.detectViolations(network);
        }

        stopwatch.stop();

        logActionEnd(network, operatorStrategy, stopwatch);

        return new OperatorStrategyResult(operatorStrategy,
                List.of(new OperatorStrategyResult.ConditionalActionsResult(operatorStrategy.getId(), status,
                                          new LimitViolationsResult(postActionsViolationManager.getLimitViolations()),
                                          new NetworkResult(postActionsNetworkResult.getBranchResults(),
                                                            postActionsNetworkResult.getBusResults(),
                                                            postActionsNetworkResult.getThreeWindingsTransformerResults()),
                        result.getDistributedActivePower() * PerUnit.SB)
                ));
    }

    protected void logActionStart(LfNetwork network, OperatorStrategy operatorStrategy) {
        LOGGER.atLevel(logLevel).log("Start operator strategy '{}' after contingency '{}' simulation on network {}", operatorStrategy.getId(),
                operatorStrategy.getContingencyContext().getContingencyId(), network);
    }

    protected void logActionEnd(LfNetwork network, OperatorStrategy operatorStrategy, Stopwatch stopwatch) {
        LOGGER.atLevel(logLevel).log("Operator strategy '{}' after contingency '{}' simulation done on network {} in {} ms", operatorStrategy.getId(),
                operatorStrategy.getContingencyContext().getContingencyId(), network, stopwatch.elapsed(TimeUnit.MILLISECONDS));
    }

    /**
     * Put the network back in its base (pre-contingency) state after a contingency, before the next one is applied.
     * The default restores every saved bus, branch, HVDC and area state; an implementation that knows what a
     * contingency's simulation changed may restore less, provided the network ends up identical.
     *
     * @param networkState the base state, saved once after the pre-contingency simulation
     * @param network the network
     * @param lfContingency the contingency just simulated
     */
    protected void restoreBaseState(NetworkState networkState, LfNetwork network, LfContingency lfContingency) {
        networkState.restore();
    }

    private void processContingency(LfNetwork lfNetwork, SecurityAnalysisParameters securityAnalysisParameters,
                                    List<LimitReduction> limitReductions, ContingencyActivePowerLossDistribution contingencyActivePowerLossDistribution,
                                    ReportNode networkReportNode, LfContingency lfContingency, P p, NetworkState networkState,
                                    PropagatedContingency propagatedContingency, C context, Map<String, LfAction> lfActionById,
                                    LoadFlowParameters loadFlowParameters, OpenLoadFlowParameters contingencyOpenLoadFlowParameters,
                                    boolean createResultExtension, LimitViolationManager preContingencyLimitViolationManager,
                                    PreContingencyNetworkResult preContingencyNetworkResult, List<PostContingencyResult> postContingencyResults,
                                    Consumer<P> contingencyParametersResetter, Map<String, List<Indexed<OperatorStrategy>>> operatorStrategiesByContingencyId,
                                    List<OperatorStrategyResult> operatorStrategyResults, Iterator<PropagatedContingency> contingencyIt) {
        ReportNode postContSimReportNode = Reports.createPostContingencySimulation(networkReportNode, lfContingency.getId());
        lfNetwork.setReportNode(postContSimReportNode);

        ContingencyLoadFlowParameters contingencyLoadFlowParameters = propagatedContingency.getContingency().getExtension(ContingencyLoadFlowParameters.class);
        if (contingencyLoadFlowParameters != null) {
            applySpecificContingencyParameters(context.getParameters(), contingencyLoadFlowParameters, loadFlowParameters, contingencyOpenLoadFlowParameters);
        }

        long tApply = System.nanoTime();
        lfContingency.apply(loadFlowParameters.getBalanceType());

        long tLoss = System.nanoTime();
        // The loss pre-distribution is an ActivePowerDistribution run like the slack loop's, and its
        // SPLIT across generators is what the first post-contingency solve sees: tag it with the
        // contingency too (the tag is set again, harmlessly, around the load flow run below).
        com.powsybl.openloadflow.ac.outerloop.DistributedSlackOuterLoop.CURRENT_CONTINGENCY.set(propagatedContingency.getContingency().getId());
        double preDistributedActivePower = contingencyActivePowerLossDistribution.run(lfNetwork, lfContingency,
            propagatedContingency.getContingency(), securityAnalysisParameters, contingencyLoadFlowParameters, postContSimReportNode);

        long tSim = System.nanoTime();
        if (System.getenv("OLF_NR_TRACE") != null) {
            // Which contingency the NR_ENTER/NR_ITER/NR_EXIT lines that follow belong to. Without it
            // a multi-contingency run's traces cannot be attributed at all, and comparing the two
            // arms' SOLVE SEQUENCES - not their final quantities - is how a divergence gets located.
            System.err.println("CPU_CTG ctg=" + propagatedContingency.getContingency().getId());
        }
        if (com.powsybl.openloadflow.ac.AcloadFlowEngine.OL_STATS) {
            com.powsybl.openloadflow.ac.AcloadFlowEngine.lastRunStats();   // clear a stale record
        }
        var postContingencyResult = runPostContingencySimulation(lfNetwork, context, propagatedContingency.getContingency(),
            lfContingency, preContingencyLimitViolationManager,
            securityAnalysisParameters,
            preContingencyNetworkResult, createResultExtension, limitReductions, preDistributedActivePower);
        if (com.powsybl.openloadflow.ac.AcloadFlowEngine.OL_STATS) {
            // One line per contingency: did the state come right out of the first inner Newton, or did outer
            // loops re-solve it, and at what cost. "nosolve" = no load flow ran for it on this path.
            String st = com.powsybl.openloadflow.ac.AcloadFlowEngine.lastRunStats();
            System.err.println("CPU_OL_STATS ctg=" + propagatedContingency.getContingency().getId() + " "
                    + (st != null ? st : "nosolve"));
        }
        if (System.getenv("OLF_DS_TARGET_DUMP") != null) {
            // Every participating bus's net P target at the END of the contingency, for a direct
            // comparison against the device's distributed targets. Double.toString round-trips.
            for (LfBus b : lfNetwork.getBuses()) {
                if (b.isParticipating() && !b.isDisabled() && !b.isFictitious()) {
                    System.err.println("CPU_DS_TARGET ctg=" + propagatedContingency.getContingency().getId()
                            + " bus=" + b.getId() + " target=" + Double.toString(b.getTargetP()));
                }
            }
        }
        if (SA_PROFILE) {
            APPLY_NS.add(tLoss - tApply);
            LOSS_NS.add(tSim - tLoss);
            SIM_NS.add(System.nanoTime() - tSim);
        }
        postContingencyResults.add(postContingencyResult);

        if (contingencyLoadFlowParameters != null &&
            Objects.equals(ContingencyLoadFlowParameters.Scope.CONTINGENCY_ONLY, contingencyLoadFlowParameters.getScope())) {
            // reset parameters
            contingencyParametersResetter.accept(context.getParameters());
        }

        List<Indexed<OperatorStrategy>> operatorStrategiesForThisContingency = operatorStrategiesByContingencyId.get(lfContingency.getId());
        if (operatorStrategiesForThisContingency != null) {
            // we have at least one operator strategy for this contingency.
            if (operatorStrategiesForThisContingency.size() == 1) {
                // only one operator strategy, no need to do a complete save of network state,
                // but need to set generators initialTargetP positions to the current (=postContingency) targetP
                lfNetwork.setGeneratorsInitialTargetPToTargetP();
                OperatorStrategy operatorStrategy = operatorStrategiesForThisContingency.get(0).value();
                ReportNode osSimReportNode = Reports.createOperatorStrategySimulation(postContSimReportNode, operatorStrategy.getId());
                lfNetwork.setReportNode(osSimReportNode);
                runActionSimulation(lfNetwork, context,
                    operatorStrategy, preContingencyLimitViolationManager,
                    securityAnalysisParameters, lfActionById,
                    createResultExtension, lfContingency, propagatedContingency.getContingency(),
                    preContingencyNetworkResult, postContingencyResult.getLimitViolationsResult(),
                    p.getNetworkParameters(), limitReductions)
                    .ifPresent(operatorStrategyResults::add);
            } else {
                // multiple operator strategies, save post contingency state for later restoration after action
                NetworkState postContingencyNetworkState = NetworkState.save(lfNetwork);
                for (Indexed<OperatorStrategy> operatorStrategy : operatorStrategiesForThisContingency) {
                    ReportNode osSimReportNode = Reports.createOperatorStrategySimulation(postContSimReportNode, operatorStrategy.value().getId());
                    lfNetwork.setReportNode(osSimReportNode);
                    runActionSimulation(lfNetwork, context,
                        operatorStrategy.value(), preContingencyLimitViolationManager,
                        securityAnalysisParameters, lfActionById,
                        createResultExtension, lfContingency, propagatedContingency.getContingency(),
                        preContingencyNetworkResult, postContingencyResult.getLimitViolationsResult(),
                        p.getNetworkParameters(), limitReductions)
                        .ifPresent(result -> {
                            operatorStrategyResults.add(result);
                            postContingencyNetworkState.restore();
                        });
                }
            }
        }
        if (contingencyIt.hasNext()) {
            // restore base state
            long tRestore = System.nanoTime();
            restoreBaseState(networkState, lfNetwork, lfContingency);
            if (SA_PROFILE) {
                RESTORE_NS.add(System.nanoTime() - tRestore);
            }
            if (contingencyLoadFlowParameters != null &&
                Objects.equals(ContingencyLoadFlowParameters.Scope.CONTINGENCY_AND_OPERATOR_STRATEGY, contingencyLoadFlowParameters.getScope())) {
                // reset parameters
                contingencyParametersResetter.accept(context.getParameters());
            }
        }

    }
    /** {@code <VAR>=<id>[,<id>]} restricts a per-contingency trace to those ids; unset means all.
     *  A security analysis prints these for every contingency it runs, which on a 10540-contingency
     *  scope is millions of lines for the handful that are under the lens. */
    protected static boolean matchesCtgFilter(String var, String contingencyId) {
        String want = System.getenv(var);
        if (want == null || want.isEmpty()) {
            return true;
        }
        for (String w : want.split(",")) {
            if (w.trim().equals(contingencyId)) {
                return true;
            }
        }
        return false;
    }

}
