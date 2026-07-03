/*
 * Copyright (c) 2024-2025, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.sa;

import com.google.common.base.Stopwatch;
import com.powsybl.action.Action;
import com.powsybl.commons.report.ReportNode;
import com.powsybl.contingency.Contingency;
import com.powsybl.contingency.strategy.OperatorStrategy;
import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.extensions.HvdcAngleDroopActivePowerControl;
import com.powsybl.loadflow.LoadFlowParameters;
import com.powsybl.loadflow.LoadFlowResult;
import com.powsybl.math.matrix.DenseMatrix;
import com.powsybl.math.matrix.MatrixFactory;
import com.powsybl.openloadflow.OpenLoadFlowParameters;
import com.powsybl.openloadflow.dc.DcLoadFlowContext;
import com.powsybl.openloadflow.dc.DcLoadFlowParameters;
import com.powsybl.openloadflow.dc.equations.AbstractClosedBranchDcFlowEquationTerm;
import com.powsybl.openloadflow.dc.equations.DcApproximationType;
import com.powsybl.openloadflow.dc.equations.DcVariableType;
import com.powsybl.openloadflow.dc.fastdc.ComputedContingencyElement;
import com.powsybl.openloadflow.dc.fastdc.ComputedElement;
import com.powsybl.openloadflow.dc.fastdc.ConnectivityBreakAnalysis;
import com.powsybl.openloadflow.dc.fastdc.ConnectivityBreakAnalysis.ConnectivityAnalysisResult;
import com.powsybl.openloadflow.dc.fastdc.WoodburyEngine;
import com.powsybl.openloadflow.equations.Variable;
import com.powsybl.openloadflow.graph.GraphConnectivityFactory;
import com.powsybl.openloadflow.network.*;
import com.powsybl.openloadflow.network.action.*;
import com.powsybl.openloadflow.network.impl.PropagatedContingency;
import com.powsybl.openloadflow.util.Evaluable;
import com.powsybl.openloadflow.util.Indexed;
import com.powsybl.openloadflow.util.PerUnit;
import com.powsybl.openloadflow.util.Reports;
import com.powsybl.security.LimitViolationsResult;
import com.powsybl.security.PostContingencyComputationStatus;
import com.powsybl.security.SecurityAnalysisParameters;
import com.powsybl.security.SecurityAnalysisResult;
import com.powsybl.security.limitreduction.LimitReduction;
import com.powsybl.security.monitor.StateMonitor;
import com.powsybl.security.monitor.StateMonitorIndex;
import com.powsybl.security.results.*;
import org.slf4j.event.Level;

import java.util.*;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static com.powsybl.openloadflow.dc.DcLoadFlowEngine.updateNetwork;
import static com.powsybl.openloadflow.network.impl.PropagatedContingency.cleanContingencies;

/**
 * @author Pierre Arvy {@literal <pierre.arvy at artelys.com>}
 */
public class WoodburyDcSecurityAnalysis extends DcSecurityAnalysis {

    /**
     * When true, the post-contingency branch violation detection only checks the branches whose flow can possibly
     * exceed a limit given the contingency's bus-angle change (see {@link BranchLimitScreen}), instead of every branch
     * carrying a limit. Kept as a toggle so the screened and full-scan detections can be compared for equivalence.
     */
    static boolean incrementalViolationDetection = true;

    // a branch phase shift is considered unchanged (so the screening bound holds) below this absolute delta in radians
    private static final double PHASE_SHIFT_DELTA_TOLERANCE = 1e-9;

    /**
     * When true, the per-contingency {@code updateNetwork} that writes every bus angle back to the network model is
     * skipped when no output needs those angles (no monitored voltage level, no voltage angle limit). Branch flows used
     * for violation and branch-result computation are read from the state vector, not from the model bus angles, so
     * skipping this network-wide write is transparent in that case. Kept as a toggle to compare with the full update.
     */
    static boolean restrictBusStateUpdate = true;

    private record WoodburyContext(DcLoadFlowContext dcLoadFlowContext, Map<String, List<Indexed<OperatorStrategy>>> operatorStrategiesByContingencyId, Map<String, LfAction> lfActionById,
                                   boolean createResultExtension, SecurityAnalysisParameters.IncreasedViolationsParameters violationsParameters,
                                   List<LimitReduction> limitReductions, SecurityAnalysisParameters.ModifiedMonitoredElementsParameters modifiedMonitoredElementsParameters,
                                   List<LimitViolationManager.BranchLimitsToCheck> branchLimitsToCheck, BranchLimitScreen branchLimitScreen, boolean updateBusStates) {
    }

    /**
     * Whether the post-contingency bus angles must be written back to the network model, i.e. whether any output reads
     * them: a monitored voltage level (producing bus results) or a voltage angle limit (checked on bus angles). Branch
     * flows and branch results are computed from the state vector and never need this write.
     */
    private static boolean isBusStateUpdateNeeded(LfNetwork lfNetwork, StateMonitorIndex monitorIndex, StateMonitorIndex zeroImpedanceMonitorIndex) {
        return !lfNetwork.getVoltageAngleLimits().isEmpty()
                || monitorsVoltageLevel(monitorIndex) || monitorsVoltageLevel(zeroImpedanceMonitorIndex);
    }

    private static boolean monitorsVoltageLevel(StateMonitorIndex monitorIndex) {
        if (monitorIndex == null) {
            return false;
        }
        if (monitorIndex.getAllStateMonitor() != null && !monitorIndex.getAllStateMonitor().getVoltageLevelIds().isEmpty()) {
            return true;
        }
        return monitorIndex.getSpecificStateMonitors().values().stream()
                .anyMatch(monitor -> !monitor.getVoltageLevelIds().isEmpty());
    }

    /**
     * Screening data letting the post-contingency violation detection skip branches that provably cannot violate.
     *
     * <p>In DC a branch active power is {@code p = -power * (phi1 - phi2 - alpha)}, so between the base case and a
     * contingency {@code |dp| <= |power| * |dphi1 - dphi2| <= |power| * spread}, where {@code spread} is the max minus
     * min of the per-bus angle change. A branch is therefore safe when {@code |power| * spread <= L - |p_base|}, i.e.
     * when {@code spread < tau} with {@code tau = (L - |p_base|) / |power|} (L being the branch's smallest limit,
     * expressed as an active power; current limits are converted with the DC power factor, apparent power limits are
     * undefined in DC and ignored). Branches are sorted by ascending {@code tau} so that, for a given contingency
     * spread, only a prefix of the list needs checking.</p>
     */
    private record BranchLimitScreen(List<LimitViolationManager.BranchLimitsToCheck> sortedBranches, double[] sortedThresholds,
                                     int[] busPhiRows, double[] baseBusAngles,
                                     int[] phaseShiftRows, double[] basePhaseShifts, LimitViolationManager.BranchLimitsToCheck[] phaseShiftBranches) {

        /**
         * Number of branches (a prefix of {@link #sortedBranches}) that may violate for the given angle spread, i.e.
         * whose threshold is at most the spread. The others provably cannot violate and are skipped.
         */
        int checkCount(double angleDeltaSpread) {
            int lo = 0;
            int hi = sortedThresholds.length;
            while (lo < hi) {
                int mid = (lo + hi) >>> 1;
                if (sortedThresholds[mid] <= angleDeltaSpread) {
                    lo = mid + 1;
                } else {
                    hi = mid;
                }
            }
            return lo;
        }

        /**
         * The max minus min of the per-bus angle change between the base case and the given post-contingency state,
         * or NaN if any angle is not finite (e.g. a disconnected island), in which case the caller falls back to a
         * full scan.
         */
        double angleDeltaSpread(double[] postContingencyStates) {
            double min = Double.POSITIVE_INFINITY;
            double max = Double.NEGATIVE_INFINITY;
            for (int k = 0; k < busPhiRows.length; k++) {
                double delta = postContingencyStates[busPhiRows[k]] - baseBusAngles[k];
                if (!Double.isFinite(delta)) {
                    return Double.NaN;
                }
                min = Math.min(min, delta);
                max = Math.max(max, delta);
            }
            return max - min;
        }

        /**
         * The limit-carrying branches whose own phase shift changed versus the base case (e.g. a phase tap changer
         * action), or null if none. The angle-spread bound does not cover the extra phase shift term of these branches,
         * so they are always checked; the angle change they induce on the other branches is already captured by the
         * spread. Checking a branch that is also in the screened prefix is harmless (violations are de-duplicated).
         */
        List<LimitViolationManager.BranchLimitsToCheck> phaseShiftedBranchesToCheck(double[] postContingencyStates) {
            List<LimitViolationManager.BranchLimitsToCheck> branches = null;
            for (int k = 0; k < phaseShiftRows.length; k++) {
                if (Math.abs(postContingencyStates[phaseShiftRows[k]] - basePhaseShifts[k]) > PHASE_SHIFT_DELTA_TOLERANCE) {
                    if (branches == null) {
                        branches = new ArrayList<>();
                    }
                    branches.add(phaseShiftBranches[k]);
                }
            }
            return branches;
        }
    }

    private record ToFastDcResults(Function<ConnectivityAnalysisResult, double[]> toPostContingencyStates,
                                   BiFunction<ConnectivityAnalysisResult, LfOperatorStrategy, ConnectivityAnalysisResult> toPostContingencyAndOperatorStrategyConnectivityAnalysisResult,
                                   Function<ConnectivityAnalysisResult, double[]> toPostContingencyAndOperatorStrategyStates) {
    }

    private record SecurityAnalysisSimulationResults(PreContingencyNetworkResult preContingencyNetworkResult, LimitViolationManager preContingencyLimitViolationManager,
                                                     List<PostContingencyResult> postContingencyResults, List<OperatorStrategyResult> operatorStrategyResults) {
    }

    protected WoodburyDcSecurityAnalysis(Network network, MatrixFactory matrixFactory, GraphConnectivityFactory<LfBus, LfBranch> connectivityFactory,
                                         List<StateMonitor> stateMonitors, ReportNode reportNode) {
        super(network, matrixFactory, connectivityFactory, stateMonitors, reportNode);
        this.logLevel = Level.DEBUG;
    }

    @Override
    protected ReportNode createSaRootReportNode() {
        return Reports.createWoodburyDcSecurityAnalysis(reportNode, network.getId());
    }

    @Override
    protected DcLoadFlowParameters createParameters(LoadFlowParameters lfParameters, OpenLoadFlowParameters lfParametersExt, boolean breakers, boolean areas) {
        DcLoadFlowParameters dcParameters = super.createParameters(lfParameters, lfParametersExt, breakers, areas);
        LfNetworkParameters lfNetworkParameters = dcParameters.getNetworkParameters();
        boolean hasDroopControl = lfNetworkParameters.isHvdcAcEmulation() && network.getHvdcLineStream().anyMatch(l -> {
            HvdcAngleDroopActivePowerControl droopControl = l.getExtension(HvdcAngleDroopActivePowerControl.class);
            return droopControl != null && droopControl.isEnabled();
        });
        if (hasDroopControl) {
            Reports.reportAcEmulationDisabledInWoodburyDcSecurityAnalysis(reportNode);
        }
        lfNetworkParameters.setMinImpedance(true) // connectivity break analysis does not handle zero impedance lines
                           .setHvdcAcEmulation(false); // ac emulation is not yet supported
        // needed an equation to force angle to zero when a PST is lost
        dcParameters.getEquationSystemCreationParameters().setForcePhaseControlOffAndAddAngle1Var(true);
        return dcParameters;
    }

    /**
     * Calculate post contingency states for a contingency.
     * In case of connectivity break, a pre-computation is done to reset active power flow of hvdc lines on which one bus is lost.
     * If connectivity, a generator, a load or a phase tap changer is lost due to the contingency, the pre contingency flowStates are overridden.
     * @return the post contingency states for the contingency.
     */
    private double[] calculatePostContingencyStates(DcLoadFlowContext loadFlowContext, DenseMatrix contingenciesStates, double[] flowStates,
                                                    ConnectivityAnalysisResult connectivityAnalysisResult, Map<String, ComputedContingencyElement> contingencyElementByBranch,
                                                    ReportNode reportNode) {
        return calculatePostContingencyAndOperatorStrategyStates(loadFlowContext, contingenciesStates, flowStates, connectivityAnalysisResult, contingencyElementByBranch,
                Collections.emptyMap(), DenseMatrix.EMPTY, reportNode);
    }

    /**
     * Calculate post contingency and post operator strategy states, for a contingency and operator strategy actions.
     * In case of connectivity break, a pre-computation is done to reset active power flow of hvdc lines on which one bus is lost.
     * If connectivity, a generator, a load or a phase tap changer is lost/modified due to the contingency/operator strategy, the pre contingency flowStates are overridden.
     * @return the post contingency and operator strategy states.
     */
    private double[] calculatePostContingencyAndOperatorStrategyStates(DcLoadFlowContext loadFlowContext, DenseMatrix contingenciesStates, double[] flowStates,
                                                                       ConnectivityAnalysisResult connectivityAnalysisResult, Map<String, ComputedContingencyElement> contingencyElementByBranch,
                                                                       Map<LfAction, List<ComputedElement>> actionElementByLfAction, DenseMatrix actionsStates, ReportNode reportNode) {
        PropagatedContingency contingency = connectivityAnalysisResult.getPropagatedContingency();
        Set<LfBus> disabledBuses = connectivityAnalysisResult.getDisabledBuses();
        Set<LfBranch> partialDisabledBranches = connectivityAnalysisResult.getPartialDisabledBranches();
        Set<String> elementsToReconnect = connectivityAnalysisResult.getElementsToReconnect();
        List<LfAction> operatorStrategyLfActions = connectivityAnalysisResult.getOperatorStrategy() != null ? connectivityAnalysisResult.getOperatorStrategy().getActions() : Collections.emptyList();

        // reset active flow of hvdc line without power
        connectivityAnalysisResult.getHvdcsWithoutPower().forEach(hvdcWithoutPower -> {
            contingency.getGeneratorIdsToLose().add(hvdcWithoutPower.getConverterStation1().getId());
            contingency.getGeneratorIdsToLose().add(hvdcWithoutPower.getConverterStation2().getId());
        });

        List<ComputedContingencyElement> contingencyElements = contingency.getBranchIdsToOpen().keySet().stream()
                .filter(element -> !elementsToReconnect.contains(element))
                .map(contingencyElementByBranch::get)
                .toList();
        List<ComputedElement> actionElements = operatorStrategyLfActions.stream()
                .map(actionElementByLfAction::get)
                .flatMap(Collection::stream)
                .filter(actionElement -> !elementsToReconnect.contains(actionElement.getLfBranch().getId()))
                .toList();

        var lfNetwork = loadFlowContext.getNetwork();
        Set<LfBranch> disabledBranches = contingency.getBranchIdsToOpen().keySet().stream().map(lfNetwork::getBranchById).collect(Collectors.toSet());
        disabledBranches.addAll(partialDisabledBranches);
        DisabledNetwork disabledNetwork = new DisabledNetwork(disabledBuses, disabledBranches);

        WoodburyEngine engine = new WoodburyEngine(loadFlowContext.getParameters().getEquationSystemCreationParameters(), contingencyElements, contingenciesStates, actionElements, actionsStates);
        double[] newFlowStates = flowStates;
        if (contingency.getGeneratorIdsToLose().isEmpty() && contingency.getLoadIdsToLose().isEmpty()) {

            // get the lost phase tap changers for this contingency
            Set<LfBranch> lostPhaseControllers = contingency.getBranchIdsToOpen().keySet().stream()
                    .filter(element -> !elementsToReconnect.contains(element))
                    .map(contingencyElementByBranch::get)
                    .map(ComputedContingencyElement::getLfBranch)
                    .filter(LfBranch::hasPhaseControllerCapability)
                    .collect(Collectors.toSet());

            // if a phase tap changer is lost or if the connectivity have changed, we must recompute load flows
            // same if there is an action, as they are only on pst for now
            if (!disabledBuses.isEmpty() || !lostPhaseControllers.isEmpty() || !operatorStrategyLfActions.isEmpty()) {
                newFlowStates = WoodburyEngine.runDcLoadFlowWithModifiedTargetVector(loadFlowContext, disabledNetwork, operatorStrategyLfActions, reportNode);
            }
            engine.toPostContingencyAndOperatorStrategyStates(newFlowStates);
        } else {
            // if we have a contingency including the loss of a DC line or a generator or a load
            // save dc buses' base state for later restoration after processing lost power changes
            DcLoadFlowParameters lfParameters = loadFlowContext.getParameters();
            List<BusDcState> busStates = ElementState.save(lfNetwork.getBuses(), BusDcState::save);
            connectivityAnalysisResult.toLfContingency()
                    // only process the power shifts due to the loss of loads, generators, and HVDCs
                    // the loss of buses and phase shifts are taken into account in the override of the flow states
                    .ifPresent(lfContingency -> lfContingency.processLostPowerChanges(lfParameters.getBalanceType(), false));
            newFlowStates = WoodburyEngine.runDcLoadFlowWithModifiedTargetVector(loadFlowContext, disabledNetwork, operatorStrategyLfActions, reportNode);
            engine.toPostContingencyAndOperatorStrategyStates(newFlowStates);
            ElementState.restore(busStates);
        }

        return newFlowStates;
    }

    /**
     * Returns the post contingency result associated to given contingency and post contingency states.
     */
    private PostContingencyResult computePostContingencyResultFromPostContingencyStates(WoodburyContext woodburyContext, Contingency contingency, LfContingency lfContingency,
                                                                                        LimitViolationManager preContingencyLimitViolationManager,
                                                                                        PreContingencyNetworkResult preContingencyNetworkResult,
                                                                                        double[] postContingencyStates, Predicate<LfBranch> isBranchDisabledDueToContingency) {
        DcLoadFlowContext loadFlowContext = woodburyContext.dcLoadFlowContext;
        LfNetwork lfNetwork = loadFlowContext.getNetwork();

        // update network state with post contingency states
        loadFlowContext.getEquationSystem().getStateVector().set(postContingencyStates);
        if (woodburyContext.updateBusStates()) {
            updateNetwork(lfNetwork, loadFlowContext.getEquationSystem(), postContingencyStates);
        }

        lfContingency.apply(loadFlowContext.getParameters().getBalanceType());

        // update post contingency network result
        var postContingencyNetworkResult = new PostContingencyNetworkResult(lfNetwork, new AbstractNetworkResult.StateMonitorIndexes(monitorIndex, zeroImpedanceMonitoredIndex),
                woodburyContext.createResultExtension, preContingencyNetworkResult, contingency,
                LoadFlowModel.DC, woodburyContext.dcLoadFlowContext().getParameters().getEquationSystemCreationParameters().getDcPowerFactor(),
                woodburyContext.modifiedMonitoredElementsParameters());
        postContingencyNetworkResult.update(isBranchDisabledDueToContingency);

        // detect violations
        // in DC the bus voltages are left undefined (set to NaN), so bus voltage violations cannot occur and the
        // network-wide bus scan is skipped
        boolean detectBusVoltageViolations = !loadFlowContext.getParameters().isSetVToNan();
        var postContingencyLimitViolationManager = new LimitViolationManager(preContingencyLimitViolationManager, woodburyContext.limitReductions, woodburyContext.violationsParameters);
        List<LimitViolationManager.BranchLimitsToCheck> branchesToCheck = branchesToCheck(woodburyContext, postContingencyStates);
        postContingencyLimitViolationManager.detectViolations(lfNetwork, isBranchDisabledDueToContingency, branchesToCheck, detectBusVoltageViolations);

        // connectivity result due to the contingency
        var connectivityResult = new ConnectivityResult(
                lfContingency.getCreatedSynchronousComponentsCount(), 0,
                lfContingency.getDisconnectedLoadActivePower() * PerUnit.SB,
                lfContingency.getDisconnectedGenerationActivePower() * PerUnit.SB,
                lfContingency.getDisconnectedElementIds());

        return new PostContingencyResult(
                contingency,
                PostContingencyComputationStatus.CONVERGED,
                new LimitViolationsResult(postContingencyLimitViolationManager.getLimitViolations()),
                new NetworkResult(postContingencyNetworkResult.getBranchResults(),
                postContingencyNetworkResult.getBusResults(),
                postContingencyNetworkResult.getThreeWindingsTransformerResults()),
                connectivityResult,
                Double.NaN  // TODO: report distributed active power in Fast DC SA
        );
    }

    /**
     * Returns the operator strategy result associated to the given post contingency and post operator strategy states.
     */
    private OperatorStrategyResult computeOperatorStrategyResultFromPostContingencyAndOperatorStrategyStates(WoodburyContext woodburyContext,
                                                                                                             LfContingency lfContingency,
                                                                                                             OperatorStrategy operatorStrategy,
                                                                                                             List<LfAction> operatorStrategyLfActions,
                                                                                                             LimitViolationManager preContingencyLimitViolationManager,
                                                                                                             Contingency contingency,
                                                                                                             PreContingencyNetworkResult preContingencyNetworkResult,
                                                                                                             double[] postContingencyAndOperatorStrategyStates,
                                                                                                             Predicate<LfBranch> isBranchDisabledDueToContingency) {
        DcLoadFlowContext loadFlowContext = woodburyContext.dcLoadFlowContext;
        LfNetwork lfNetwork = loadFlowContext.getNetwork();

        // apply modifications first, so that branch flows (which, in the vectorized equation system, are
        // computed on state vector update) are evaluated on the final network topology and tap positions
        lfContingency.apply(loadFlowContext.getParameters().getBalanceType());
        LfActionUtils.applyListOfActions(operatorStrategyLfActions, lfNetwork, lfContingency, loadFlowContext.getParameters().getNetworkParameters());

        // update network state with post contingency and post operator strategy states
        loadFlowContext.getEquationSystem().getStateVector().set(postContingencyAndOperatorStrategyStates);
        if (woodburyContext.updateBusStates()) {
            updateNetwork(lfNetwork, loadFlowContext.getEquationSystem(), postContingencyAndOperatorStrategyStates);
        }

        // update network result
        var postActionsNetworkResult = new PostContingencyNetworkResult(lfNetwork, new AbstractNetworkResult.StateMonitorIndexes(monitorIndex, zeroImpedanceMonitoredIndex),
                woodburyContext.createResultExtension, preContingencyNetworkResult, contingency, LoadFlowModel.DC,
                loadFlowContext.getParameters().getEquationSystemCreationParameters().getDcPowerFactor(),
                woodburyContext.modifiedMonitoredElementsParameters);
        postActionsNetworkResult.update(isBranchDisabledDueToContingency);

        // detect violations
        // in DC the bus voltages are left undefined (set to NaN), so bus voltage violations cannot occur and the
        // network-wide bus scan is skipped
        boolean detectBusVoltageViolations = !loadFlowContext.getParameters().isSetVToNan();
        var postActionsViolationManager = new LimitViolationManager(preContingencyLimitViolationManager,
                woodburyContext.limitReductions, woodburyContext.violationsParameters);
        List<LimitViolationManager.BranchLimitsToCheck> branchesToCheck = branchesToCheck(woodburyContext, postContingencyAndOperatorStrategyStates);
        postActionsViolationManager.detectViolations(lfNetwork, isBranchDisabledDueToContingency, branchesToCheck, detectBusVoltageViolations);

        return new OperatorStrategyResult(operatorStrategy,
            List.of(
                new OperatorStrategyResult.ConditionalActionsResult(
                    operatorStrategy.getId(), PostContingencyComputationStatus.CONVERGED,
                    new LimitViolationsResult(postActionsViolationManager.getLimitViolations()),
                    new NetworkResult(postActionsNetworkResult.getBranchResults(),
                        postActionsNetworkResult.getBusResults(),
                        postActionsNetworkResult.getThreeWindingsTransformerResults()),
                    Double.NaN) // TODO: report distributed active power in Fast DC SA
            )
        );
    }

    /**
     * Add the post contingency and operator strategy results, associated to given connectivity analysis result, in given security analysis simulation results.
     *
     * @param woodburyContext the context in which the security analysis is conducted.
     * @param toFastDcResults the functions used to computed post contingency and post operator strategy states and connectivity analysis result.
     * @param restorePreContingencyStates the runnable to restore the pre contingency states after the computation of post contingency and post operator strategy states.
     */
    private void addPostContingencyAndOperatorStrategyResults(WoodburyContext woodburyContext, ConnectivityAnalysisResult connectivityAnalysisResult,
                                                              ToFastDcResults toFastDcResults, Runnable restorePreContingencyStates,
                                                              SecurityAnalysisSimulationResults securityAnalysisSimulationResults) {
        // process results only if contingency impacts the network
        connectivityAnalysisResult.toLfContingency()
            .ifPresent(lfContingency -> processContingency(woodburyContext, connectivityAnalysisResult,
                toFastDcResults, restorePreContingencyStates, securityAnalysisSimulationResults, lfContingency));
    }

    /**
     * Restore the pre contingency state of the network elements collected by the given collector (i.e. the elements
     * modified by the contingency and its operator strategy actions), then clear the collector for the next
     * contingency. The collector is disabled during the restoration so that the setter calls it performs do not
     * collect back the elements being restored.
     */
    private static void restoreModifiedNetworkElements(NetworkState networkState, ModifiedElementsCollector modifiedElementsCollector) {
        modifiedElementsCollector.setEnabled(false);
        networkState.restore(modifiedElementsCollector.getModifiedBuses(), modifiedElementsCollector.getModifiedBranches(),
            modifiedElementsCollector.getModifiedHvdcs());
        modifiedElementsCollector.reset();
        modifiedElementsCollector.setEnabled(true);
    }

    private void processContingency(WoodburyContext woodburyContext, ConnectivityAnalysisResult connectivityAnalysisResult,
                                    ToFastDcResults toFastDcResults, Runnable restorePreContingencyStates,
                                    SecurityAnalysisSimulationResults securityAnalysisSimulationResults,
                                    LfContingency lfContingency) {

        DcLoadFlowContext dcLoadFlowContext = woodburyContext.dcLoadFlowContext;
        LfNetwork lfNetwork = dcLoadFlowContext.getNetwork();

        Contingency contingency = connectivityAnalysisResult.getPropagatedContingency().getContingency();
        ReportNode postContSimReportNode = Reports.createPostContingencySimulation(lfNetwork.getReportNode(), contingency.getId());
        lfNetwork.setReportNode(postContSimReportNode);

        // predicate to determine if a branch is disabled or not due to the contingency
        // note that branches with one side opened due to the contingency are considered as disabled
        Map<LfBranch, DisabledBranchStatus> disabledBranchesStatus = lfContingency.getDisabledNetwork().getBranchesStatus();
        Predicate<LfBranch> isBranchDisabled = disabledBranchesStatus::containsKey;

        // process post contingency result with supplier giving post contingency states
        logPostContingencyStart(lfNetwork, lfContingency);
        Stopwatch stopwatch = Stopwatch.createStarted();

        double[] postContingencyStates = toFastDcResults.toPostContingencyStates.apply(connectivityAnalysisResult);
        PostContingencyResult postContingencyResult = computePostContingencyResultFromPostContingencyStates(woodburyContext, contingency, lfContingency,
            securityAnalysisSimulationResults.preContingencyLimitViolationManager, securityAnalysisSimulationResults.preContingencyNetworkResult, postContingencyStates, isBranchDisabled);

        stopwatch.stop();
        logPostContingencyEnd(lfNetwork, lfContingency, stopwatch);
        securityAnalysisSimulationResults.postContingencyResults.add(postContingencyResult);

        // restore pre contingency states for next calculation
        restorePreContingencyStates.run();

        List<Indexed<OperatorStrategy>> operatorStrategiesForThisContingency = woodburyContext.operatorStrategiesByContingencyId.get(contingency.getId());
        if (operatorStrategiesForThisContingency != null) {
            for (Indexed<OperatorStrategy> operatorStrategy : operatorStrategiesForThisContingency) {
                ReportNode osSimReportNode = Reports.createOperatorStrategySimulation(postContSimReportNode, operatorStrategy.value().getId());
                lfNetwork.setReportNode(osSimReportNode);

                // get the actions associated to the operator strategy
                List<String> actionIds = checkCondition(operatorStrategy.value(), postContingencyResult.getLimitViolationsResult(), lfNetwork);
                List<LfAction> operatorStrategyLfActions = actionIds.stream()
                    .map(woodburyContext.lfActionById::get)
                    .filter(Objects::nonNull)
                    .toList();
                LfOperatorStrategy lfOperatorStrategy = new LfOperatorStrategy(operatorStrategy, operatorStrategyLfActions);

                logActionStart(lfNetwork, operatorStrategy.value());
                stopwatch = Stopwatch.createStarted();

                // process post contingency and operator strategy connectivity result with given supplier
                // operator strategy actions might have changed the post contingency connectivity results
                ConnectivityAnalysisResult postContingencyAndOperatorStrategyConnectivityAnalysisResult = toFastDcResults
                    .toPostContingencyAndOperatorStrategyConnectivityAnalysisResult.apply(connectivityAnalysisResult, lfOperatorStrategy);

                // predicate to determine if a branch is disabled or not due to the contingency and operator strategy actions
                // the connectivity results are used to determine which branches have been disabled, due to the contingency or connectivity loss
                // note that branches with one side opened due to the modifications are considered as disabled
                Predicate<LfBranch> isBranchDisabledDueToContingencyAndOperatorStrategy = branch -> {
                    Set<LfBranch> disabledBranches = postContingencyAndOperatorStrategyConnectivityAnalysisResult.getPropagatedContingency()
                        .getBranchIdsToOpen().keySet().stream().map(lfNetwork::getBranchById).collect(Collectors.toSet());
                    disabledBranches.addAll(postContingencyAndOperatorStrategyConnectivityAnalysisResult.getPartialDisabledBranches());
                    return disabledBranches.contains(branch);
                };

                double[] postContingencyAndOperatorStrategyStates = toFastDcResults.toPostContingencyAndOperatorStrategyStates
                    .apply(postContingencyAndOperatorStrategyConnectivityAnalysisResult);
                OperatorStrategyResult operatorStrategyResult = computeOperatorStrategyResultFromPostContingencyAndOperatorStrategyStates(woodburyContext,
                    lfContingency, operatorStrategy.value(), operatorStrategyLfActions,
                    securityAnalysisSimulationResults.preContingencyLimitViolationManager, contingency, securityAnalysisSimulationResults.preContingencyNetworkResult,
                    postContingencyAndOperatorStrategyStates, isBranchDisabledDueToContingencyAndOperatorStrategy);
                securityAnalysisSimulationResults.operatorStrategyResults.add(operatorStrategyResult);

                stopwatch.stop();
                logActionEnd(lfNetwork, operatorStrategy.value(), stopwatch);

                // restore pre contingency states for next calculation
                restorePreContingencyStates.run();
            }
        }
    }

    /**
     * The branches whose limits must be checked for the given post-contingency state: when screening is enabled and
     * applicable, only the branches that can possibly violate given the contingency's bus-angle change; otherwise all
     * the branches carrying a limit. Falls back to the full list when a bus angle is not finite (disconnected island)
     * or a phase shift changed, cases the screening bound does not cover.
     */
    private static List<LimitViolationManager.BranchLimitsToCheck> branchesToCheck(WoodburyContext woodburyContext, double[] postContingencyStates) {
        BranchLimitScreen screen = woodburyContext.branchLimitScreen();
        if (screen != null) {
            double angleDeltaSpread = screen.angleDeltaSpread(postContingencyStates);
            if (Double.isFinite(angleDeltaSpread)) {
                List<LimitViolationManager.BranchLimitsToCheck> screened = screen.sortedBranches().subList(0, screen.checkCount(angleDeltaSpread));
                // branches whose own phase shift changed (e.g. a phase tap changer action) are not covered by the
                // spread bound and must be checked in addition to the screened prefix
                List<LimitViolationManager.BranchLimitsToCheck> phaseShifted = screen.phaseShiftedBranchesToCheck(postContingencyStates);
                if (phaseShifted == null) {
                    return screened;
                }
                List<LimitViolationManager.BranchLimitsToCheck> branches = new ArrayList<>(screened);
                branches.addAll(phaseShifted);
                return branches;
            }
        }
        return woodburyContext.branchLimitsToCheck();
    }

    /**
     * Build the screening data used to skip branches that provably cannot violate a limit after a contingency. Must be
     * called with the base (pre-contingency) state loaded in the network, as it reads the base branch flows.
     */
    private static BranchLimitScreen buildBranchLimitScreen(DcLoadFlowContext context,
                                                            List<LimitViolationManager.BranchLimitsToCheck> branchLimitsToCheck,
                                                            double[] preContingencyStates) {
        var creationParameters = context.getParameters().getEquationSystemCreationParameters();
        boolean useTransformerRatio = creationParameters.isUseTransformerRatio();
        DcApproximationType dcApproximationType = creationParameters.getDcApproximationType();
        double dcPowerFactor = creationParameters.getDcPowerFactor();

        // limit-carrying branches indexed by branch number, to link phase shift variables back to the branch to check
        Map<Integer, LimitViolationManager.BranchLimitsToCheck> limitedBranchByNum = new HashMap<>();
        for (LimitViolationManager.BranchLimitsToCheck branchToCheck : branchLimitsToCheck) {
            limitedBranchByNum.put(branchToCheck.branch().getNum(), branchToCheck);
        }

        // collect the bus angle rows (and base values) used to bound the flow change, and the phase shift rows of the
        // limit-carrying branches (and the branches they belong to) whose change the spread bound does not cover
        List<Integer> busPhiRows = new ArrayList<>();
        List<Integer> phaseShiftRows = new ArrayList<>();
        List<LimitViolationManager.BranchLimitsToCheck> phaseShiftBranches = new ArrayList<>();
        for (Variable<DcVariableType> variable : context.getEquationSystem().getIndex().getSortedVariablesToFind()) {
            if (variable.getType() == DcVariableType.BUS_PHI) {
                busPhiRows.add(variable.getRow());
            } else if (variable.getType() == DcVariableType.BRANCH_ALPHA1) {
                LimitViolationManager.BranchLimitsToCheck branchToCheck = limitedBranchByNum.get(variable.getElementNum());
                if (branchToCheck != null) {
                    phaseShiftRows.add(variable.getRow());
                    phaseShiftBranches.add(branchToCheck);
                }
            }
        }

        // per-branch screening threshold, sorted ascending
        List<LimitViolationManager.BranchLimitsToCheck> sortedBranches = new ArrayList<>(branchLimitsToCheck);
        Map<LimitViolationManager.BranchLimitsToCheck, Double> thresholdByBranch = new IdentityHashMap<>();
        for (LimitViolationManager.BranchLimitsToCheck branchToCheck : sortedBranches) {
            thresholdByBranch.put(branchToCheck, screeningThreshold(branchToCheck, useTransformerRatio, dcApproximationType, dcPowerFactor));
        }
        sortedBranches.sort(Comparator.comparingDouble(thresholdByBranch::get));

        double[] sortedThresholds = new double[sortedBranches.size()];
        for (int i = 0; i < sortedThresholds.length; i++) {
            sortedThresholds[i] = thresholdByBranch.get(sortedBranches.get(i));
        }
        return new BranchLimitScreen(sortedBranches, sortedThresholds,
                toIntArray(busPhiRows), baseValues(busPhiRows, preContingencyStates),
                toIntArray(phaseShiftRows), baseValues(phaseShiftRows, preContingencyStates),
                phaseShiftBranches.toArray(new LimitViolationManager.BranchLimitsToCheck[0]));
    }

    private static double screeningThreshold(LimitViolationManager.BranchLimitsToCheck branchToCheck, boolean useTransformerRatio,
                                             DcApproximationType dcApproximationType, double dcPowerFactor) {
        LfBranch branch = branchToCheck.branch();
        double power = Math.abs(AbstractClosedBranchDcFlowEquationTerm.computePower(useTransformerRatio, dcApproximationType, branch.getPiModel()));
        if (power == 0) {
            // a branch that does not carry flow (should not happen, zero impedance branches are excluded) cannot violate
            return Double.POSITIVE_INFINITY;
        }
        double threshold1 = sideScreeningThreshold(branchToCheck.bus1(), branch.getP1(), branchToCheck.activePowerLimits1(),
                branchToCheck.currentLimits1(), dcPowerFactor, power);
        double threshold2 = sideScreeningThreshold(branchToCheck.bus2(), branch.getP2(), branchToCheck.activePowerLimits2(),
                branchToCheck.currentLimits2(), dcPowerFactor, power);
        return Math.min(threshold1, threshold2);
    }

    private static double sideScreeningThreshold(LfBus bus, Evaluable activePower, List<LfBranch.LfLimitsGroup> activePowerLimits,
                                                 List<LfBranch.LfLimitsGroup> currentLimits, double dcPowerFactor, double power) {
        if (bus == null) {
            return Double.POSITIVE_INFINITY;
        }
        // smallest limit that can be violated on this side, as an active power: active power limits are compared to |p|
        // directly, current limits are compared to |p| / dcPowerFactor so their active power equivalent is value * dcPowerFactor
        double smallestActivePowerLimit = Double.POSITIVE_INFINITY;
        for (LfBranch.LfLimitsGroup limitsGroup : activePowerLimits) {
            for (LfBranch.LfLimit limit : limitsGroup.getSortedLimits()) {
                smallestActivePowerLimit = Math.min(smallestActivePowerLimit, limit.getReducedValue());
            }
        }
        for (LfBranch.LfLimitsGroup limitsGroup : currentLimits) {
            for (LfBranch.LfLimit limit : limitsGroup.getSortedLimits()) {
                smallestActivePowerLimit = Math.min(smallestActivePowerLimit, limit.getReducedValue() * dcPowerFactor);
            }
        }
        if (smallestActivePowerLimit == Double.POSITIVE_INFINITY) {
            return Double.POSITIVE_INFINITY;
        }
        // (L - |p_base|) / |power|; negative when the branch already violates at base, so it is always checked
        return (smallestActivePowerLimit - Math.abs(activePower.eval())) / power;
    }

    private static int[] toIntArray(List<Integer> values) {
        int[] array = new int[values.size()];
        for (int i = 0; i < array.length; i++) {
            array[i] = values.get(i);
        }
        return array;
    }

    private static double[] baseValues(List<Integer> rows, double[] states) {
        double[] array = new double[rows.size()];
        for (int i = 0; i < array.length; i++) {
            array[i] = states[rows.get(i)];
        }
        return array;
    }

    @Override
    protected void checkSupportedActions(List<Action> actions) {
        Actions.checkWoodburySupported(network, actions);
    }

    @Override
    protected SecurityAnalysisResult runSimulations(LfNetwork lfNetwork, List<PropagatedContingency> propagatedContingencies, DcLoadFlowParameters dcParameters,
                                                    SecurityAnalysisParameters securityAnalysisParameters, List<OperatorStrategy> operatorStrategies,
                                                    List<Action> actions, List<LimitReduction> limitReductions, ContingencyActivePowerLossDistribution contingencyActivePowerLossDistribution) {
        // DC security analysis does not support AC-DC networks.
        // Therefore, we can also assume that lfNetwork contains only one synchronous network

        Map<String, Action> actionsById = Actions.indexById(actions);
        Map<String, List<Indexed<OperatorStrategy>>> operatorStrategiesByContingencyId =
                OperatorStrategies.indexByContingencyId(propagatedContingencies, operatorStrategies, actionsById, true);
        Set<Action> neededActions = OperatorStrategies.getNeededActions(operatorStrategiesByContingencyId, actionsById);
        Map<String, LfAction> lfActionById = LfActionUtils.createLfActions(lfNetwork, neededActions, network); // only convert needed actions

        OpenSecurityAnalysisParameters openSecurityAnalysisParameters = OpenSecurityAnalysisParameters.getOrDefault(securityAnalysisParameters);
        boolean createResultExtension = openSecurityAnalysisParameters.isCreateResultExtension();

        try (DcLoadFlowContext context = new DcLoadFlowContext(lfNetwork, dcParameters, false)) {
            ReportNode networkReportNode = lfNetwork.getReportNode();
            ReportNode preContSimReportNode = Reports.createPreContingencySimulation(networkReportNode);
            lfNetwork.setReportNode(preContSimReportNode);

            // prepare contingencies for connectivity analysis and Woodbury engine
            // note that contingencies on branches connected only on one side are removed,
            // this is a difference with dc security analysis
            cleanContingencies(lfNetwork, propagatedContingencies);

            // compute the pre-contingency states
            double[] preContingencyStates = WoodburyEngine.runDcLoadFlowWithModifiedTargetVector(context, new DisabledNetwork(), reportNode);
            // create workingContingencyStates that will be a working copy of pre-contingency states
            double[] workingContingencyStates = new double[preContingencyStates.length];
            System.arraycopy(preContingencyStates, 0, workingContingencyStates, 0, preContingencyStates.length);

            // set pre contingency angle states as state vector of equation system
            context.getEquationSystem().getStateVector().set(preContingencyStates);

            // Update network voltages with pre contingency states
            updateNetwork(lfNetwork, context.getEquationSystem(), preContingencyStates);
            if (context.getParameters().isSetVToNan()) {
                for (LfBus bus : lfNetwork.getBuses()) {
                    bus.setV(Double.NaN);
                }
            }

            // update network result
            List<StateMonitor> zeroImpedanceStateMonitors = extractZeroImpedanceStateMonitors(lfNetwork);
            this.zeroImpedanceMonitoredIndex = new StateMonitorIndex(zeroImpedanceStateMonitors);
            var preContingencyNetworkResult = new PreContingencyNetworkResult(lfNetwork, new AbstractNetworkResult.StateMonitorIndexes(monitorIndex, zeroImpedanceMonitoredIndex),
                createResultExtension, LoadFlowModel.DC, securityAnalysisParameters.getLoadFlowParameters().getDcPowerFactor());
            preContingencyNetworkResult.update();

            // detect violations
            var preContingencyLimitViolationManager = new LimitViolationManager(limitReductions);
            preContingencyLimitViolationManager.detectViolations(lfNetwork);
            // branch limits do not change between contingencies: resolve once the limit groups of the branches carrying
            // limits so that the post contingency violation detection reuses them instead of looking up the limits of
            // every branch of the network on each contingency
            List<LimitViolationManager.BranchLimitsToCheck> branchLimitsToCheck =
                    LimitViolationManager.getBranchLimitsToCheck(lfNetwork, preContingencyLimitViolationManager.getLimitReductionManager());
            // optionally, precompute the per-branch screening thresholds so the post-contingency detection can skip the
            // branches whose flow cannot reach a limit given the contingency's bus-angle change (base state is loaded here)
            BranchLimitScreen branchLimitScreen = incrementalViolationDetection
                    ? buildBranchLimitScreen(context, branchLimitsToCheck, preContingencyStates) : null;
            // the per-contingency updateNetwork writes every bus angle back to the model; skip it when no output reads
            // those angles (branch flows come from the state vector), keeping it when it is disabled by the toggle
            boolean updateBusStates = !restrictBusStateUpdate || isBusStateUpdateNeeded(lfNetwork, monitorIndex, zeroImpedanceMonitoredIndex);
            WoodburyContext woodburyContext = new WoodburyContext(context, operatorStrategiesByContingencyId, lfActionById, createResultExtension,
                    securityAnalysisParameters.getIncreasedViolationsParameters(), limitReductions,
                    securityAnalysisParameters.getModifiedMonitoredElementsParameters(), branchLimitsToCheck, branchLimitScreen, updateBusStates);

            // compute states with +1 -1 to model the contingencies and run connectivity analysis
            ConnectivityBreakAnalysis.ConnectivityBreakAnalysisResults connectivityBreakAnalysisResults = ConnectivityBreakAnalysis.run(context, propagatedContingencies);

            // the map is indexed by lf actions as different kind of actions can be given on the same branch
            Map<LfAction, List<ComputedElement>> actionElementsIndexByLfAction = ComputedElement.createActionElementsIndexByLfAction(lfActionById, context.getEquationSystem(),
                    context.getParameters().getEquationSystemCreationParameters());

            // compute states with +1 -1 to model the actions in Woodbury engine
            // note that the number of columns in the matrix depends on the number of distinct branches affected by the action elements
            DenseMatrix actionsStates = ComputedElement.calculateElementsStates(context, actionElementsIndexByLfAction.values().stream().flatMap(Collection::stream).toList());

            // save base state for later restoration after each contingency/action
            NetworkState networkState = NetworkState.save(lfNetwork);

            // collect the elements modified by each contingency (and its operator strategy actions) so that only
            // those are restored afterwards, instead of the whole network
            ModifiedElementsCollector modifiedElementsCollector = new ModifiedElementsCollector();
            lfNetwork.addListener(modifiedElementsCollector);

            List<PostContingencyResult> postContingencyResults = new ArrayList<>();
            List<OperatorStrategyResult> operatorStrategyResults = new ArrayList<>();
            SecurityAnalysisSimulationResults securityAnalysisSimulationResults = new SecurityAnalysisSimulationResults(preContingencyNetworkResult,
                preContingencyLimitViolationManager, postContingencyResults, operatorStrategyResults);

            // supplier to compute post contingency states
            // no need to distribute active mismatch due to connectivity modifications
            // this is handled when the slack is distributed in pre contingency states override
            Function<ConnectivityAnalysisResult, double[]> toPostContingencyStates = postContingencyConnectivityAnalysisResult ->
                calculatePostContingencyStates(context, connectivityBreakAnalysisResults.contingenciesStates(), workingContingencyStates,
                    postContingencyConnectivityAnalysisResult, connectivityBreakAnalysisResults.contingencyElementByBranch(), reportNode);

            // function to compute post contingency and post operator strategy connectivity result, with post contingency connectivity result and operator strategy actions
            // due to branch enabling/disabling actions, connectivity results may have changed
            BiFunction<ConnectivityAnalysisResult, LfOperatorStrategy, ConnectivityAnalysisResult> toPostContingencyAndOperatorStrategyConnectivityAnalysisResult =
                (postContingencyConnectivityAnalysisResult, operatorStrategyLfActions) ->
                    ConnectivityBreakAnalysis.processPostContingencyAndPostOperatorStrategyConnectivityAnalysisResult(
                            context, postContingencyConnectivityAnalysisResult, connectivityBreakAnalysisResults.contingencyElementByBranch(), connectivityBreakAnalysisResults.contingenciesStates(),
                            operatorStrategyLfActions, actionElementsIndexByLfAction, actionsStates);

            // function to compute post contingency and post operator strategy states
            Function<ConnectivityAnalysisResult, double[]> toPostContingencyAndOperatorStrategyStates = postContingencyAndOperatorStrategyConnectivityAnalysisResult ->
                    calculatePostContingencyAndOperatorStrategyStates(context, connectivityBreakAnalysisResults.contingenciesStates(),
                            workingContingencyStates, postContingencyAndOperatorStrategyConnectivityAnalysisResult,
                            connectivityBreakAnalysisResults.contingencyElementByBranch(), actionElementsIndexByLfAction, actionsStates, reportNode);
            ToFastDcResults toFastDcResults = new ToFastDcResults(toPostContingencyStates, toPostContingencyAndOperatorStrategyConnectivityAnalysisResult, toPostContingencyAndOperatorStrategyStates);

            LOGGER.info("Processing post contingency results for contingencies with no connectivity break");
            connectivityBreakAnalysisResults.nonBreakingConnectivityAnalysisResults().forEach(connectivityAnalysisResult -> {
                // runnable to restore pre contingency states, after modifications applied to the lfNetwork
                Runnable restorePreContingencyStates = () -> {
                    // update workingContingencyStates as it may have been updated by post contingency states calculation
                    System.arraycopy(preContingencyStates, 0, workingContingencyStates, 0, preContingencyStates.length);
                    // restore pre contingency state of the elements modified by the contingency
                    restoreModifiedNetworkElements(networkState, modifiedElementsCollector);
                };
                addPostContingencyAndOperatorStrategyResults(woodburyContext, connectivityAnalysisResult, toFastDcResults, restorePreContingencyStates, securityAnalysisSimulationResults);
            });

            LOGGER.info("Processing post contingency results for contingencies breaking connectivity");
            connectivityBreakAnalysisResults.connectivityBreakingAnalysisResults().forEach(connectivityAnalysisResult -> {
                // runnable to restore pre contingency states, after modifications applied to the lfNetwork
                // no need to update workingContingencyStates as an override of flow states will be computed
                Runnable restorePreContingencyStates = () -> restoreModifiedNetworkElements(networkState, modifiedElementsCollector);
                addPostContingencyAndOperatorStrategyResults(woodburyContext, connectivityAnalysisResult, toFastDcResults, restorePreContingencyStates, securityAnalysisSimulationResults);
            });

            lfNetwork.removeListener(modifiedElementsCollector);

            return new SecurityAnalysisResult(
                    new PreContingencyResult(LoadFlowResult.ComponentResult.Status.CONVERGED,
                            new LimitViolationsResult(preContingencyLimitViolationManager.getLimitViolations()),
                            new NetworkResult(preContingencyNetworkResult.getBranchResults(), preContingencyNetworkResult.getBusResults(),
                            preContingencyNetworkResult.getThreeWindingsTransformerResults()),
                            Double.NaN), // TODO: report distributed active power in Fast DC SA
                            postContingencyResults, operatorStrategyResults);
        }
    }
}
