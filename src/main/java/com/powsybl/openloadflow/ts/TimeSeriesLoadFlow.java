/**
 * Copyright (c) 2025, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.ts;

import com.powsybl.commons.PowsyblException;
import com.powsybl.commons.report.ReportNode;
import com.powsybl.iidm.network.ComponentConstants;
import com.powsybl.iidm.network.Network;
import com.powsybl.loadflow.LoadFlowParameters;
import com.powsybl.loadflow.LoadFlowResult;
import com.powsybl.loadflow.resultswriter.NetworkResultWriter;
import com.powsybl.loadflow.resultswriter.NetworkResultWriterFactory;
import com.powsybl.math.matrix.MatrixFactory;
import com.powsybl.math.matrix.SparseMatrixFactory;
import com.powsybl.openloadflow.OpenLoadFlowParameters;
import com.powsybl.openloadflow.ac.AcLoadFlowContext;
import com.powsybl.openloadflow.ac.AcLoadFlowParameters;
import com.powsybl.openloadflow.ac.AcLoadFlowResult;
import com.powsybl.openloadflow.ac.AcloadFlowEngine;
import com.powsybl.openloadflow.dc.DcLoadFlowContext;
import com.powsybl.openloadflow.dc.DcLoadFlowEngine;
import com.powsybl.openloadflow.dc.DcLoadFlowParameters;
import com.powsybl.openloadflow.dc.DcLoadFlowResult;
import com.powsybl.openloadflow.graph.EvenShiloachGraphDecrementalConnectivityFactory;
import com.powsybl.openloadflow.graph.GraphConnectivityFactory;
import com.powsybl.openloadflow.network.LfBranch;
import com.powsybl.openloadflow.network.LfBus;
import com.powsybl.openloadflow.network.LfGenerator;
import com.powsybl.openloadflow.network.LfNetwork;
import com.powsybl.openloadflow.network.LfNetworkParameters;
import com.powsybl.openloadflow.network.LfTopoConfig;
import com.powsybl.openloadflow.network.LfZeroImpedanceNetwork;
import com.powsybl.openloadflow.network.LoadFlowModel;
import com.powsybl.openloadflow.network.NetworkState;
import com.powsybl.openloadflow.network.impl.LfNetworkList;
import com.powsybl.openloadflow.network.impl.Networks;
import com.powsybl.openloadflow.network.util.ZeroImpedanceFlows;
import com.powsybl.openloadflow.util.PerUnit;
import com.powsybl.timeseries.DoubleTimeSeries;
import com.powsybl.timeseries.TimeSeriesIndex;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Time-series (multi-step) load flow: solves the <b>same</b> network many times, changing only the active-power targets
 * of generators from one step to the next (the "generation plan"). The network structure — buses, branches, switches,
 * controls — is fixed, so each step is a cheap re-solve on a persistent load flow context (the equation system, the
 * Jacobian structure and, in DC, the factorization are built once and only the target vector changes), exactly like a
 * security-analysis post-contingency re-solve minus the topology change.
 *
 * <p>Each step is <b>independent</b>: the network is restored to its as-loaded state before the step's targets are
 * applied, and the step is then solved with the full outer loop configuration of the supplied
 * {@link LoadFlowParameters}. A step therefore yields the same result as running a classical
 * {@link com.powsybl.loadflow.LoadFlow} on the network with that step's generator targets, and results do not depend on
 * the order the steps are solved in nor on how they are partitioned across threads.
 *
 * <p>The generation plan is supplied as powsybl-core {@link DoubleTimeSeries}: one series per controllable generator
 * (its {@link com.powsybl.timeseries.TimeSeriesMetadata#getName() name} is the generator id, its values are the
 * per-step active-power targets in MW). All series share one {@link TimeSeriesIndex}, which defines the number of steps
 * and their instants.
 *
 * <p>Because the result set ({@code branches x steps}, plus buses and generators) does not fit in memory for large
 * runs, the per-step results are <b>streamed</b> to a {@link NetworkResultWriter} (CSV or Parquet). Only a compact
 * per-step summary is returned in memory (see {@link TimeSeriesLoadFlowResult}). Steps are partitioned across threads
 * ({@link TimeSeriesLoadFlowParameters#getThreadCount()}); each partition writes its own lock-free part file.
 *
 * @author Gautier Bureau {@literal <gautier.bureau at rte-france.com>}
 */
public final class TimeSeriesLoadFlow {

    private static final Logger LOGGER = LoggerFactory.getLogger(TimeSeriesLoadFlow.class);

    private TimeSeriesLoadFlow() {
    }

    /**
     * Run a time-series load flow.
     *
     * @param network       the network (its structure is fixed across all steps)
     * @param plan          the generation plan: one {@link DoubleTimeSeries} per generator (series name = generator id,
     *                      values = per-step active-power targets in MW); all series must share the same time-series index
     * @param parameters    the time-series load flow parameters (AC/DC, thread count, which datasets to stream)
     * @param writerFactory builds one {@link NetworkResultWriter} per partition; use
     *                      {@link NetworkResultWriterFactory#NO_OP} to disable streaming
     * @return the per-step summary
     */
    public static TimeSeriesLoadFlowResult run(Network network, List<DoubleTimeSeries> plan,
                                               TimeSeriesLoadFlowParameters parameters, NetworkResultWriterFactory writerFactory) {
        Objects.requireNonNull(network, "network");
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(parameters, "parameters");
        Objects.requireNonNull(writerFactory, "writerFactory");
        if (plan.isEmpty()) {
            throw new PowsyblException("Time-series load flow requires at least one generation plan series");
        }

        TimeSeriesIndex index = plan.get(0).getMetadata().getIndex();
        int stepCount = index.getPointCount();
        for (DoubleTimeSeries series : plan) {
            if (series.getMetadata().getIndex().getPointCount() != stepCount) {
                throw new PowsyblException("All generation plan series must share the same time-series index length");
            }
        }
        List<String> unknownGenerators = plan.stream()
                .map(series -> series.getMetadata().getName())
                .filter(id -> network.getGenerator(id) == null)
                .toList();
        if (!unknownGenerators.isEmpty()) {
            throw new PowsyblException("Unknown generator id(s) in the generation plan: " + unknownGenerators);
        }

        if (stepCount == 0) {
            return new TimeSeriesLoadFlowResult(List.of());
        }

        LoadFlowParameters lfParameters = parameters.getLoadFlowParameters();
        MatrixFactory matrixFactory = new SparseMatrixFactory();
        GraphConnectivityFactory<LfBus, LfBranch> connectivityFactory = new EvenShiloachGraphDecrementalConnectivityFactory<>();

        int threadCount = Math.min(parameters.getThreadCount(), stepCount);
        List<int[]> ranges = partitionRanges(stepCount, threadCount);
        String workingVariantId = network.getVariantManager().getWorkingVariantId();

        LOGGER.info("Running {} time-series load flow on {} steps over {} thread(s)",
                lfParameters.isDc() ? "DC" : "AC", stepCount, ranges.size());

        List<StepResult> results;
        if (ranges.size() == 1) {
            try (NetworkResultWriter writer = writerFactory.create(0)) {
                EnginePlan enginePlan = buildEnginePlan(network, parameters, matrixFactory, connectivityFactory);
                network.getVariantManager().setWorkingVariant(workingVariantId);
                try (LfNetworkList lfNetworks = Networks.loadWithReconnectableElements(network, new LfTopoConfig(),
                        enginePlan.networkParameters(), ReportNode.NO_OP)) {
                    results = runSteps(lfNetworks, ranges.get(0), enginePlan, plan, index, parameters, writer);
                }
            }
        } else {
            results = runMultiThread(network, workingVariantId, plan, index, ranges, parameters,
                    matrixFactory, connectivityFactory, writerFactory);
        }

        results.sort(Comparator.comparingInt(StepResult::stepIndex));
        return new TimeSeriesLoadFlowResult(results);
    }

    private static List<StepResult> runMultiThread(Network network, String workingVariantId, List<DoubleTimeSeries> plan,
                                                   TimeSeriesIndex index, List<int[]> ranges, TimeSeriesLoadFlowParameters parameters,
                                                   MatrixFactory matrixFactory, GraphConnectivityFactory<LfBus, LfBranch> connectivityFactory,
                                                   NetworkResultWriterFactory writerFactory) {
        boolean oldAllowVariantMultiThreadAccess = network.getVariantManager().isVariantMultiThreadAccessAllowed();
        network.getVariantManager().allowVariantMultiThreadAccess(true);
        ExecutorService executor = Executors.newFixedThreadPool(ranges.size());
        List<PartitionJob> jobs = new ArrayList<>();
        try {
            // Build one LfNetworkList per partition on the main thread: cloning an IIDM variant (done by the loader) is
            // not thread-safe, so all clones happen here, sequentially, before any parallel work starts.
            for (int i = 0; i < ranges.size(); i++) {
                EnginePlan enginePlan = buildEnginePlan(network, parameters, matrixFactory, connectivityFactory);
                network.getVariantManager().setWorkingVariant(workingVariantId);
                LfNetworkList lfNetworks = Networks.loadWithReconnectableElements(network, new LfTopoConfig(),
                        enginePlan.networkParameters(), ReportNode.NO_OP);
                jobs.add(new PartitionJob(i, ranges.get(i), enginePlan, lfNetworks));
            }

            // Solve each partition in parallel: the step loop only reads the network and mutates in-memory Lf state, no
            // IIDM variant is cloned or removed here, so it is lock-free.
            List<CompletableFuture<List<StepResult>>> futures = new ArrayList<>();
            for (PartitionJob job : jobs) {
                futures.add(CompletableFuture.supplyAsync(() -> {
                    try (NetworkResultWriter writer = writerFactory.create(job.partitionIndex())) {
                        return runSteps(job.lfNetworks(), job.range(), job.enginePlan(), plan, index, parameters, writer);
                    }
                }, executor));
            }

            List<StepResult> all = new ArrayList<>();
            for (CompletableFuture<List<StepResult>> future : futures) {
                all.addAll(future.join());
            }
            return all;
        } finally {
            // Wait for every worker to stop before removing the cloned variants: if one partition fails, join()
            // rethrows while the other workers are still solving, and closing their networks under them would corrupt
            // the run and hide the original failure.
            awaitTermination(executor);
            // Close (remove the cloned variants) on the main thread, after all parallel work is done.
            jobs.forEach(job -> job.lfNetworks().close());
            network.getVariantManager().allowVariantMultiThreadAccess(oldAllowVariantMultiThreadAccess);
        }
    }

    private static void awaitTermination(ExecutorService executor) {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(1, TimeUnit.HOURS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private static List<StepResult> runSteps(LfNetworkList lfNetworks, int[] range, EnginePlan enginePlan,
                                             List<DoubleTimeSeries> plan, TimeSeriesIndex index,
                                             TimeSeriesLoadFlowParameters parameters, NetworkResultWriter writer) {
        LoadFlowParameters lfParameters = parameters.getLoadFlowParameters();
        LoadFlowModel loadFlowModel = enginePlan.loadFlowModel();
        LfNetworkParameters networkParameters = enginePlan.networkParameters();
        double dcPowerFactor = lfParameters.getDcPowerFactor();

        List<LfNetwork> networks = networksToSimulate(lfNetworks, lfParameters.getComponentMode());
        List<NetworkRun> runs = new ArrayList<>();
        try {
            for (LfNetwork lfNetwork : networks) {
                StepEngine engine = enginePlan.createEngine(lfNetwork);
                List<GeneratorSetpoint> setpoints = resolveSetpoints(lfNetwork, plan);
                // Snapshot the network as loaded, before anything is solved. Solving mutates far more than the
                // generator targets it distributes the slack over: outer loops move tap positions and shunt sections,
                // and switch buses between PV and PQ. Restoring a snapshot taken after a solve would make every step
                // start from that solve's control state instead of the network's own, so a step would no longer equal
                // an independent load flow run. There is nothing to warm-start from either: the solver initializes its
                // state vector from the configured voltage initializer, not from the restored voltages.
                NetworkState baseState = NetworkState.save(lfNetwork);
                runs.add(new NetworkRun(lfNetwork, engine, setpoints, baseState));
            }

            List<StepResult> results = new ArrayList<>();
            for (int step = range[0]; step < range[1]; step++) {
                Instant timestamp = index.getInstantAt(step);
                String stateId = timestamp.toString();
                LoadFlowResult.ComponentResult.Status status = runs.isEmpty()
                        ? LoadFlowResult.ComponentResult.Status.FAILED
                        : LoadFlowResult.ComponentResult.Status.CONVERGED;
                double distributedActivePower = 0;
                double slackBusActivePowerMismatch = 0;
                for (NetworkRun run : runs) {
                    run.baseState().restore();
                    applySetpoints(run.setpoints(), step, networkParameters);
                    SolveResult solveResult = run.engine().solve();
                    status = mergeStatus(status, solveResult.status());
                    distributedActivePower += solveResult.distributedActivePower();
                    slackBusActivePowerMismatch += solveResult.slackBusActivePowerMismatch();
                    emit(run.lfNetwork(), stateId, solveResult.status(), loadFlowModel, dcPowerFactor, parameters, writer);
                }
                results.add(new StepResult(step, timestamp, status, slackBusActivePowerMismatch, distributedActivePower));
            }
            return results;
        } finally {
            runs.forEach(run -> run.engine().close());
        }
    }

    private static void applySetpoints(List<GeneratorSetpoint> setpoints, int step, LfNetworkParameters networkParameters) {
        for (GeneratorSetpoint setpoint : setpoints) {
            double targetP = setpoint.series().get(step) / PerUnit.SB;
            LfGenerator generator = setpoint.generator();
            generator.setTargetP(targetP);
            generator.setInitialTargetP(targetP);
            generator.reApplyActivePowerControlChecks(networkParameters, null);
        }
    }

    private static List<GeneratorSetpoint> resolveSetpoints(LfNetwork lfNetwork, List<DoubleTimeSeries> plan) {
        List<GeneratorSetpoint> setpoints = new ArrayList<>();
        for (DoubleTimeSeries series : plan) {
            LfGenerator generator = lfNetwork.getGeneratorById(series.getMetadata().getName());
            if (generator != null) {
                setpoints.add(new GeneratorSetpoint(generator, series));
            }
        }
        return setpoints;
    }

    private static void emit(LfNetwork lfNetwork, String stateId, LoadFlowResult.ComponentResult.Status status,
                             LoadFlowModel loadFlowModel, double dcPowerFactor, TimeSeriesLoadFlowParameters parameters,
                             NetworkResultWriter writer) {
        String statusName = status.name();
        if (parameters.isStreamBranchResults()) {
            Map<String, LfBranch.LfBranchResults> zeroImpedanceFlows = computeZeroImpedanceFlows(lfNetwork, loadFlowModel, dcPowerFactor);
            LfBranch.BranchFlowConsumer sink = (branchId, p1, q1, i1, p2, q2, i2, flowTransfer) ->
                    writer.writeBranchResult(stateId, "", statusName, branchId, p1, q1, i1, p2, q2, i2, flowTransfer);
            for (LfBranch branch : lfNetwork.getBranches()) {
                if (!branch.isDisabled()) {
                    branch.emitBranchResults(Double.NaN, Double.NaN, zeroImpedanceFlows, loadFlowModel, sink);
                }
            }
        }
        if (parameters.isStreamBusResults()) {
            for (LfBus bus : lfNetwork.getBuses()) {
                if (!bus.isDisabled()) {
                    writer.writeBusResult(stateId, "", statusName, bus.getId(),
                            bus.getV() * bus.getNominalV(), Math.toDegrees(bus.getAngle()));
                }
            }
        }
        if (parameters.isStreamGeneratorResults()) {
            for (LfBus bus : lfNetwork.getBuses()) {
                if (!bus.isDisabled()) {
                    for (LfGenerator generator : bus.getGenerators()) {
                        // targetP: the requested setpoint (before slack distribution); p: the actual power after it.
                        writer.writeGeneratorResult(stateId, "", statusName, generator.getId(),
                                generator.getInitialTargetP() * PerUnit.SB, generator.getTargetP() * PerUnit.SB);
                    }
                }
            }
        }
    }

    private static Map<String, LfBranch.LfBranchResults> computeZeroImpedanceFlows(LfNetwork lfNetwork,
                                                                                   LoadFlowModel loadFlowModel, double dcPowerFactor) {
        Map<String, LfBranch.LfBranchResults> zeroImpedanceFlows = new HashMap<>();
        for (LfZeroImpedanceNetwork zeroImpedanceNetwork : lfNetwork.getZeroImpedanceNetworks(loadFlowModel)) {
            new ZeroImpedanceFlows(zeroImpedanceNetwork.getGraph(), zeroImpedanceNetwork.getSpanningTree(), loadFlowModel, dcPowerFactor)
                    .computeFlows(true, zeroImpedanceFlows);
        }
        return zeroImpedanceFlows;
    }

    private static EnginePlan buildEnginePlan(Network network, TimeSeriesLoadFlowParameters parameters,
                                              MatrixFactory matrixFactory, GraphConnectivityFactory<LfBus, LfBranch> connectivityFactory) {
        LoadFlowParameters lfParameters = parameters.getLoadFlowParameters();
        OpenLoadFlowParameters parametersExt = OpenLoadFlowParameters.get(lfParameters);
        if (lfParameters.isDc()) {
            DcLoadFlowParameters dcParameters = OpenLoadFlowParameters.createDcParameters(network, lfParameters,
                    parametersExt, matrixFactory, connectivityFactory, false);
            return new EnginePlan() {
                @Override
                public LfNetworkParameters networkParameters() {
                    return dcParameters.getNetworkParameters();
                }

                @Override
                public LoadFlowModel loadFlowModel() {
                    return LoadFlowModel.DC;
                }

                @Override
                public StepEngine createEngine(LfNetwork lfNetwork) {
                    DcLoadFlowContext context = new DcLoadFlowContext(lfNetwork, dcParameters);
                    return new StepEngine() {
                        @Override
                        public SolveResult solve() {
                            DcLoadFlowResult result = new DcLoadFlowEngine(context).run();
                            return new SolveResult(result.toComponentResultStatus().status(),
                                    result.getSlackBusActivePowerMismatch(), result.getDistributedActivePower());
                        }

                        @Override
                        public void close() {
                            context.close();
                        }
                    };
                }
            };
        } else {
            AcLoadFlowParameters acParameters = OpenLoadFlowParameters.createAcParameters(network, lfParameters,
                    parametersExt, matrixFactory, connectivityFactory);
            return new EnginePlan() {
                @Override
                public LfNetworkParameters networkParameters() {
                    return acParameters.getNetworkParameters();
                }

                @Override
                public LoadFlowModel loadFlowModel() {
                    return LoadFlowModel.AC;
                }

                @Override
                public StepEngine createEngine(LfNetwork lfNetwork) {
                    AcLoadFlowContext context = new AcLoadFlowContext(lfNetwork, acParameters);
                    return new StepEngine() {
                        @Override
                        public SolveResult solve() {
                            AcLoadFlowResult result = new AcloadFlowEngine(context).run();
                            return new SolveResult(result.toComponentResultStatus().status(),
                                    result.getSlackBusActivePowerMismatch(), result.getDistributedActivePower());
                        }

                        @Override
                        public void close() {
                            context.close();
                        }
                    };
                }
            };
        }
    }

    private static List<LfNetwork> networksToSimulate(LfNetworkList lfNetworks, LoadFlowParameters.ComponentMode mode) {
        return switch (mode) {
            case MAIN_CONNECTED -> lfNetworks.getList().stream()
                    .filter(n -> n.getNumCC() == ComponentConstants.MAIN_NUM && n.getValidity() == LfNetwork.Validity.VALID)
                    .toList();
            case MAIN_SYNCHRONOUS -> lfNetworks.getList().stream()
                    .filter(n -> n.getValidity() == LfNetwork.Validity.VALID
                            && !n.getSynchronousNetworks().isEmpty()
                            && n.getSynchronousNetworks().getFirst().getNumSC() == ComponentConstants.MAIN_NUM)
                    .toList();
            case ALL_CONNECTED -> lfNetworks.getList().stream()
                    .filter(n -> n.getValidity() == LfNetwork.Validity.VALID)
                    .toList();
        };
    }

    private static LoadFlowResult.ComponentResult.Status mergeStatus(LoadFlowResult.ComponentResult.Status current,
                                                                     LoadFlowResult.ComponentResult.Status componentStatus) {
        // the step status is the first non-converged component status, if any
        return current == LoadFlowResult.ComponentResult.Status.CONVERGED ? componentStatus : current;
    }

    static List<int[]> partitionRanges(int stepCount, int partitionCount) {
        List<int[]> ranges = new ArrayList<>();
        int base = stepCount / partitionCount;
        int remainder = stepCount % partitionCount;
        int start = 0;
        for (int i = 0; i < partitionCount; i++) {
            int size = base + (i < remainder ? 1 : 0);
            ranges.add(new int[] {start, start + size});
            start += size;
        }
        return ranges;
    }

    private interface EnginePlan {
        LfNetworkParameters networkParameters();

        LoadFlowModel loadFlowModel();

        StepEngine createEngine(LfNetwork lfNetwork);
    }

    private interface StepEngine extends AutoCloseable {
        SolveResult solve();

        @Override
        void close();
    }

    private record SolveResult(LoadFlowResult.ComponentResult.Status status,
                               double slackBusActivePowerMismatch,
                               double distributedActivePower) {
    }

    private record GeneratorSetpoint(LfGenerator generator, DoubleTimeSeries series) {
    }

    private record NetworkRun(LfNetwork lfNetwork, StepEngine engine, List<GeneratorSetpoint> setpoints, NetworkState baseState) {
    }

    private record PartitionJob(int partitionIndex, int[] range, EnginePlan enginePlan, LfNetworkList lfNetworks) {
    }
}
