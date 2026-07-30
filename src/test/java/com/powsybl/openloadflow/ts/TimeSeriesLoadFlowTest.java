/**
 * Copyright (c) 2025, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.ts;

import com.powsybl.commons.PowsyblException;
import com.powsybl.iidm.network.Branch;
import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.PhaseTapChanger;
import com.powsybl.iidm.network.Substation;
import com.powsybl.iidm.network.TopologyKind;
import com.powsybl.iidm.network.TwoWindingsTransformer;
import com.powsybl.iidm.network.VoltageLevel;
import com.powsybl.iidm.network.extensions.HvdcAngleDroopActivePowerControl;
import com.powsybl.iidm.network.extensions.HvdcAngleDroopActivePowerControlAdder;
import com.powsybl.iidm.network.extensions.LoadDetailAdder;
import com.powsybl.iidm.network.test.EurostagTutorialExample1Factory;
import com.powsybl.iidm.network.test.FourSubstationsNodeBreakerFactory;
import com.powsybl.iidm.network.test.PhaseShifterTestCaseFactory;
import com.powsybl.loadflow.LoadFlow;
import com.powsybl.loadflow.LoadFlowParameters;
import com.powsybl.loadflow.LoadFlowResult;
import com.powsybl.loadflow.resultswriter.CsvNetworkResultWriter;
import com.powsybl.loadflow.resultswriter.CsvNetworkResultWriterFactory;
import com.powsybl.loadflow.resultswriter.NetworkResultWriterFactory;
import com.powsybl.math.matrix.DenseMatrixFactory;
import com.powsybl.openloadflow.OpenLoadFlowParameters;
import com.powsybl.openloadflow.OpenLoadFlowProvider;
import com.powsybl.openloadflow.network.HvdcNetworkFactory;
import com.powsybl.timeseries.DoubleTimeSeries;
import com.powsybl.timeseries.RegularTimeSeriesIndex;
import com.powsybl.timeseries.TimeSeries;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the time-series (multi-step) load flow: streaming of the three datasets, value correctness versus an
 * independent load flow, DC mode, and single-thread / multi-thread equivalence.
 *
 * @author Gautier Bureau {@literal <gautier.bureau at rte-france.com>}
 */
class TimeSeriesLoadFlowTest {

    private RegularTimeSeriesIndex index;
    private List<DoubleTimeSeries> plan;
    private double[] targets;

    @BeforeEach
    void setUp() {
        // three hourly steps
        index = RegularTimeSeriesIndex.create(Instant.parse("2025-01-01T00:00:00Z"),
                Instant.parse("2025-01-01T02:00:00Z"), Duration.ofHours(1));
        targets = new double[] {600.0, 500.0, 700.0};
        plan = List.of(TimeSeries.createDouble("GEN", index, targets));
    }

    private static Function<String, Writer> csvSink(Map<String, StringWriter> writers) {
        return dataset -> writers.computeIfAbsent(dataset, k -> new StringWriter());
    }

    @Test
    void streamsBranchBusAndGeneratorDatasets() {
        Network network = EurostagTutorialExample1Factory.create();
        Map<String, StringWriter> csv = new HashMap<>();

        TimeSeriesLoadFlowResult result = TimeSeriesLoadFlow.run(network, plan, new TimeSeriesLoadFlowParameters(),
                partitionIndex -> new CsvNetworkResultWriter(csvSink(csv)));

        assertEquals(3, result.getStepResults().size());
        assertTrue(result.getStepResults().stream()
                .allMatch(s -> s.status() == LoadFlowResult.ComponentResult.Status.CONVERGED));
        assertEquals(Instant.parse("2025-01-01T01:00:00Z"), result.getStepResults().get(1).timestamp());

        // branches: header + 3 steps x 4 branches
        String branches = csv.get("branches").toString();
        assertTrue(branches.startsWith("stateId;subStateId;status;branchId;p1;q1;i1;p2;q2;i2;flowTransfer"),
                () -> "unexpected branch header: " + branches);
        assertEquals(12, branches.strip().lines().skip(1).count());
        assertTrue(branches.contains("2025-01-01T00:00:00Z;;CONVERGED;NHV1_NHV2_1;"));
        assertTrue(branches.contains("2025-01-01T02:00:00Z;;CONVERGED;NHV1_NHV2_1;"));

        // buses and generators streamed too
        assertTrue(csv.get("buses").toString().startsWith("stateId;subStateId;status;busId;v;angle"));
        String generators = csv.get("generators").toString();
        assertTrue(generators.startsWith("stateId;subStateId;status;generatorId;targetP;p"));
        assertTrue(generators.contains(";CONVERGED;GEN;"));
    }

    @Test
    void streamedFlowsMatchIndependentLoadFlow() {
        Network tsNetwork = EurostagTutorialExample1Factory.create();
        Map<String, StringWriter> csv = new HashMap<>();
        TimeSeriesLoadFlow.run(tsNetwork, plan, new TimeSeriesLoadFlowParameters(),
                partitionIndex -> new CsvNetworkResultWriter(csvSink(csv)));

        // streamed p1 keyed by "timestamp|branchId"
        Map<String, Double> streamedP1 = new HashMap<>();
        csv.get("branches").toString().strip().lines().skip(1).forEach(line -> {
            String[] c = line.split(";");
            streamedP1.put(c[0] + "|" + c[3], Double.parseDouble(c[4]));
        });

        // reference: an independent load flow per step, with the same generator target applied
        LoadFlow.Runner runner = new LoadFlow.Runner(new OpenLoadFlowProvider(new DenseMatrixFactory()));
        for (int step = 0; step < targets.length; step++) {
            Network ref = EurostagTutorialExample1Factory.create();
            ref.getGenerator("GEN").setTargetP(targets[step]);
            LoadFlowResult refResult = runner.run(ref, new LoadFlowParameters());
            assertTrue(refResult.isFullyConverged());
            double refP1 = ref.getLine("NHV1_NHV2_1").getTerminal1().getP();
            String ts = index.getInstantAt(step).toString();
            assertEquals(refP1, streamedP1.get(ts + "|NHV1_NHV2_1"), 1e-2, "p1 mismatch at step " + step);
        }
    }

    /**
     * On a network where several generators participate in slack distribution but only one is in the plan, each step
     * must still equal an independent load flow: the generators outside the plan have to start every step from their
     * own dispatch, not from the slack-distributed dispatch of the base solve.
     */
    @Test
    void nonPlanGeneratorsMatchIndependentLoadFlow() {
        double[] gh1Targets = {80.0, 120.0, 60.0};
        List<DoubleTimeSeries> gh1Plan = List.of(TimeSeries.createDouble("GH1", index, gh1Targets));
        List<String> generatorIds = List.of("GH1", "GH2", "GH3", "GTH1", "GTH2");

        Map<String, StringWriter> csv = new HashMap<>();
        TimeSeriesLoadFlowParameters parameters = new TimeSeriesLoadFlowParameters();
        parameters.getLoadFlowParameters().setDistributedSlack(true);
        TimeSeriesLoadFlowResult result = TimeSeriesLoadFlow.run(FourSubstationsNodeBreakerFactory.create(), gh1Plan,
                parameters, partitionIndex -> new CsvNetworkResultWriter(csvSink(csv)));
        assertTrue(result.getStepResults().stream()
                .allMatch(s -> s.status() == LoadFlowResult.ComponentResult.Status.CONVERGED));

        // streamed generator dispatch keyed by "timestamp|generatorId"
        Map<String, Double> streamedP = new HashMap<>();
        Map<String, Double> streamedTargetP = new HashMap<>();
        csv.get("generators").toString().strip().lines().skip(1).forEach(line -> {
            String[] c = line.split(";");
            streamedTargetP.put(c[0] + "|" + c[3], Double.parseDouble(c[4]));
            streamedP.put(c[0] + "|" + c[3], Double.parseDouble(c[5]));
        });

        LoadFlow.Runner runner = new LoadFlow.Runner(new OpenLoadFlowProvider(new DenseMatrixFactory()));
        for (int step = 0; step < gh1Targets.length; step++) {
            Network ref = FourSubstationsNodeBreakerFactory.create();
            ref.getGenerator("GH1").setTargetP(gh1Targets[step]);
            assertTrue(runner.run(ref, new LoadFlowParameters().setDistributedSlack(true)).isFullyConverged());

            String ts = index.getInstantAt(step).toString();
            for (String id : generatorIds) {
                assertEquals(-ref.getGenerator(id).getTerminal().getP(), streamedP.get(ts + "|" + id), 1e-2,
                        () -> "dispatch mismatch for " + id + " at " + ts);
                // the requested target is the plan value for GH1, and the network's own setpoint for the others
                double expectedTarget = "GH1".equals(id) ? gh1Targets[step]
                        : FourSubstationsNodeBreakerFactory.create().getGenerator(id).getTargetP();
                assertEquals(expectedTarget, streamedTargetP.get(ts + "|" + id), 1e-2,
                        () -> "requested targetP mismatch for " + id + " at " + ts);
            }
        }
    }

    @Test
    void dcModeConverges() {
        Network network = EurostagTutorialExample1Factory.create();
        TimeSeriesLoadFlowParameters parameters = new TimeSeriesLoadFlowParameters();
        parameters.getLoadFlowParameters().setDc(true);

        TimeSeriesLoadFlowResult result = TimeSeriesLoadFlow.run(network, plan, parameters, NetworkResultWriterFactory.NO_OP);

        assertEquals(3, result.getStepResults().size());
        assertTrue(result.getStepResults().stream()
                .allMatch(s -> s.status() == LoadFlowResult.ComponentResult.Status.CONVERGED));
    }

    @Test
    void multiThreadedRunEqualsSingleThreaded(@TempDir Path dir) throws IOException {
        Network single = EurostagTutorialExample1Factory.create();
        TimeSeriesLoadFlow.run(single, plan, new TimeSeriesLoadFlowParameters().setThreadCount(1),
                new CsvNetworkResultWriterFactory(dir.resolve("single")));

        Network multi = EurostagTutorialExample1Factory.create();
        TimeSeriesLoadFlow.run(multi, plan, new TimeSeriesLoadFlowParameters().setThreadCount(3),
                new CsvNetworkResultWriterFactory(dir.resolve("multi")));

        assertEquals(readBranchRows(dir.resolve("single")), readBranchRows(dir.resolve("multi")));
        // 3 steps over 3 threads => 3 part files
        try (Stream<Path> parts = Files.list(dir.resolve("multi").resolve("branches"))) {
            assertEquals(3, parts.count());
        }
    }

    /**
     * A step that does not converge still streams its rows, carrying its own status: callers filter on the status
     * column rather than having to reconcile a gap in the dataset against the step summary. The emitted values are the
     * solver's last iterate, which is exactly why the status must be there to tell them apart. A failed step must not
     * affect its neighbours either — each step is restored and solved independently.
     */
    @Test
    void nonConvergedStepIsStreamedWithItsStatus() {
        Map<String, StringWriter> csv = new HashMap<>();
        // the middle step is unreachable and does not converge
        List<DoubleTimeSeries> divergingPlan = List.of(TimeSeries.createDouble("GEN", index, 600.0, 1e7, 700.0));

        TimeSeriesLoadFlowResult result = TimeSeriesLoadFlow.run(EurostagTutorialExample1Factory.create(),
                divergingPlan, new TimeSeriesLoadFlowParameters(),
                partitionIndex -> new CsvNetworkResultWriter(csvSink(csv)));

        assertEquals(LoadFlowResult.ComponentResult.Status.CONVERGED, result.getStepResults().get(0).status());
        assertEquals(LoadFlowResult.ComponentResult.Status.MAX_ITERATION_REACHED, result.getStepResults().get(1).status());
        assertEquals(LoadFlowResult.ComponentResult.Status.CONVERGED, result.getStepResults().get(2).status());

        // every step is streamed, the failed one included, each row tagged with that step's status
        Map<String, List<String>> statusesByStep = new HashMap<>();
        csv.get("branches").toString().strip().lines().skip(1).forEach(line -> {
            String[] c = line.split(";");
            statusesByStep.computeIfAbsent(c[0], k -> new ArrayList<>()).add(c[2]);
        });
        assertEquals(3, statusesByStep.size());
        assertEquals(List.of("MAX_ITERATION_REACHED", "MAX_ITERATION_REACHED", "MAX_ITERATION_REACHED", "MAX_ITERATION_REACHED"),
                statusesByStep.get("2025-01-01T01:00:00Z"));

        // the failed step does not leak into the next one: step 2 still equals an independent load flow
        Network ref = EurostagTutorialExample1Factory.create();
        ref.getGenerator("GEN").setTargetP(700.0);
        assertTrue(new LoadFlow.Runner(new OpenLoadFlowProvider(new DenseMatrixFactory()))
                .run(ref, new LoadFlowParameters()).isFullyConverged());
        double streamedP1 = csv.get("branches").toString().strip().lines()
                .filter(l -> l.startsWith("2025-01-01T02:00:00Z;;CONVERGED;NHV1_NHV2_1;"))
                .map(l -> Double.parseDouble(l.split(";")[4]))
                .findFirst().orElseThrow();
        assertEquals(ref.getLine("NHV1_NHV2_1").getTerminal1().getP(), streamedP1, 1e-2);
    }

    @Test
    void dcStepsMatchIndependentDcLoadFlow() {
        Map<String, StringWriter> csv = new HashMap<>();
        TimeSeriesLoadFlowParameters parameters = new TimeSeriesLoadFlowParameters();
        parameters.getLoadFlowParameters().setDc(true);
        TimeSeriesLoadFlow.run(EurostagTutorialExample1Factory.create(), plan, parameters,
                partitionIndex -> new CsvNetworkResultWriter(csvSink(csv)));

        Map<String, Double> streamedP1 = new HashMap<>();
        csv.get("branches").toString().strip().lines().skip(1).forEach(line -> {
            String[] c = line.split(";");
            streamedP1.put(c[0] + "|" + c[3], Double.parseDouble(c[4]));
        });

        LoadFlow.Runner runner = new LoadFlow.Runner(new OpenLoadFlowProvider(new DenseMatrixFactory()));
        for (int step = 0; step < targets.length; step++) {
            Network ref = EurostagTutorialExample1Factory.create();
            ref.getGenerator("GEN").setTargetP(targets[step]);
            assertTrue(runner.run(ref, new LoadFlowParameters().setDc(true)).isFullyConverged());
            String ts = index.getInstantAt(step).toString();
            assertEquals(ref.getLine("NHV1_NHV2_1").getTerminal1().getP(), streamedP1.get(ts + "|NHV1_NHV2_1"), 1e-2,
                    "DC p1 mismatch at step " + step);
        }
    }

    @Test
    void planSeriesWithDifferentIndexLengthThrows() {
        RegularTimeSeriesIndex shorterIndex = RegularTimeSeriesIndex.create(Instant.parse("2025-01-01T00:00:00Z"),
                Instant.parse("2025-01-01T01:00:00Z"), Duration.ofHours(1));
        List<DoubleTimeSeries> mixedPlan = List.of(TimeSeries.createDouble("GEN", index, targets),
                TimeSeries.createDouble("GEN2", shorterIndex, 1.0, 2.0));
        PowsyblException e = assertThrows(PowsyblException.class,
                () -> TimeSeriesLoadFlow.run(EurostagTutorialExample1Factory.createWithMultipleConnectedComponents(),
                        mixedPlan, new TimeSeriesLoadFlowParameters(), NetworkResultWriterFactory.NO_OP));
        assertTrue(e.getMessage().contains("same time-series index length"));
    }

    @Test
    void emptyPlanThrows() {
        PowsyblException e = assertThrows(PowsyblException.class,
                () -> TimeSeriesLoadFlow.run(EurostagTutorialExample1Factory.create(), List.of(),
                        new TimeSeriesLoadFlowParameters(), NetworkResultWriterFactory.NO_OP));
        assertTrue(e.getMessage().contains("at least one plan series"));
    }

    /**
     * Two series naming the same element would otherwise silently leave only the last one applied.
     */
    @Test
    void duplicatedGeneratorInPlanThrows() {
        List<DoubleTimeSeries> duplicatedPlan = List.of(TimeSeries.createDouble("GEN", index, targets),
                TimeSeries.createDouble("GEN", index, 1.0, 2.0, 3.0));
        PowsyblException e = assertThrows(PowsyblException.class,
                () -> TimeSeriesLoadFlow.run(EurostagTutorialExample1Factory.create(), duplicatedPlan,
                        new TimeSeriesLoadFlowParameters(), NetworkResultWriterFactory.NO_OP));
        assertTrue(e.getMessage().contains("Duplicated generator or load id(s) in the plan: [GEN]"));
    }

    @Test
    void unknownGeneratorInPlanThrows() {
        Network network = EurostagTutorialExample1Factory.create();
        List<DoubleTimeSeries> badPlan = List.of(TimeSeries.createDouble("MISSING", index, targets));
        PowsyblException e = assertThrows(PowsyblException.class,
                () -> TimeSeriesLoadFlow.run(network, badPlan, new TimeSeriesLoadFlowParameters(), NetworkResultWriterFactory.NO_OP));
        assertTrue(e.getMessage().contains("Unknown generator, load or HVDC id"));
    }

    /**
     * The whole contract, for a load: each step must come out of the engine equal to a load flow run on its own, on a
     * network whose p0 is that step's value.
     */
    @Test
    void loadStepsMatchIndependentLoadFlow() {
        double[] p0s = {600.0, 800.0, 400.0};
        List<DoubleTimeSeries> loadPlan = List.of(TimeSeries.createDouble("LOAD", index, p0s));
        Map<String, StringWriter> csv = new HashMap<>();
        TimeSeriesLoadFlow.run(EurostagTutorialExample1Factory.create(), loadPlan, new TimeSeriesLoadFlowParameters(),
                partitionIndex -> new CsvNetworkResultWriter(csvSink(csv)));

        Map<String, Double> streamedP1 = new HashMap<>();
        csv.get("branches").toString().strip().lines().skip(1).forEach(line -> {
            String[] c = line.split(";");
            streamedP1.put(c[0] + "|" + c[3], Double.parseDouble(c[4]));
        });

        LoadFlow.Runner runner = new LoadFlow.Runner(new OpenLoadFlowProvider(new DenseMatrixFactory()));
        for (int step = 0; step < p0s.length; step++) {
            Network ref = EurostagTutorialExample1Factory.create();
            ref.getLoad("LOAD").setP0(p0s[step]);
            assertTrue(runner.run(ref, new LoadFlowParameters()).isFullyConverged());
            String ts = index.getInstantAt(step).toString();
            assertEquals(ref.getLine("NHV1_NHV2_1").getTerminal1().getP(), streamedP1.get(ts + "|NHV1_NHV2_1"), 1e-2,
                    "p1 mismatch at step " + step);
        }
    }

    /**
     * A second load, on its own bus, so that slack distributed over the loads has a split to get wrong. With a single
     * load its participation factor is 1 whatever p0 does, which is how a one-load golden test can pass on a network
     * whose participation factors are stale.
     */
    private static Network networkWithTwoLoadBuses() {
        Network network = EurostagTutorialExample1Factory.create();
        network.getVoltageLevel("VLHV2").newLoad()
                .setId("LOAD2")
                .setBus("NHV2")
                .setConnectableBus("NHV2")
                .setP0(200)
                .setQ0(60)
                .add();
        return network;
    }

    /**
     * Slack distributed over the loads is the case the model had no way to get right: the participation factors come
     * from p0, so a step that moves p0 has to move them with it or it distributes by the first step's proportions.
     */
    @Test
    void loadStepsMatchIndependentLoadFlowWhenSlackIsDistributedOnLoads() {
        double[] p0s = {600.0, 1200.0, 300.0};
        List<DoubleTimeSeries> loadPlan = List.of(TimeSeries.createDouble("LOAD", index, p0s));
        TimeSeriesLoadFlowParameters parameters = new TimeSeriesLoadFlowParameters();
        parameters.getLoadFlowParameters().setBalanceType(LoadFlowParameters.BalanceType.PROPORTIONAL_TO_LOAD);
        Map<String, StringWriter> csv = new HashMap<>();
        TimeSeriesLoadFlow.run(networkWithTwoLoadBuses(), loadPlan, parameters,
                partitionIndex -> new CsvNetworkResultWriter(csvSink(csv)));

        Map<String, Double> streamedP1 = new HashMap<>();
        csv.get("branches").toString().strip().lines().skip(1).forEach(line -> {
            String[] c = line.split(";");
            streamedP1.put(c[0] + "|" + c[3], Double.parseDouble(c[4]));
        });

        LoadFlow.Runner runner = new LoadFlow.Runner(new OpenLoadFlowProvider(new DenseMatrixFactory()));
        for (int step = 0; step < p0s.length; step++) {
            Network ref = networkWithTwoLoadBuses();
            ref.getLoad("LOAD").setP0(p0s[step]);
            assertTrue(runner.run(ref, new LoadFlowParameters()
                    .setBalanceType(LoadFlowParameters.BalanceType.PROPORTIONAL_TO_LOAD)).isFullyConverged());
            String ts = index.getInstantAt(step).toString();
            // every branch, not just one: both loads sit behind NHV1_NHV2_1, so its flow is the same whichever way the
            // slack splits between them. NHV2_NLOAD carries LOAD alone and is what a wrong split actually shows up on.
            for (Branch<?> branch : ref.getBranches()) {
                assertEquals(branch.getTerminal1().getP(), streamedP1.get(ts + "|" + branch.getId()), 1e-2,
                        "p1 mismatch on " + branch.getId() + " at step " + step);
            }
        }
    }

    /**
     * The same conform loads, with the fixed/variable split a LoadDetail describes. A plan moves p0 and nothing else,
     * so fixed + variable no longer add up to p0 -- which is what a grid model whose p0 was set and whose LoadDetail
     * was not looks like, and is exactly what the reference run below is built as.
     */
    private static Network networkWithTwoConformLoadBuses() {
        Network network = networkWithTwoLoadBuses();
        network.getLoad("LOAD").newExtension(LoadDetailAdder.class)
                .withFixedActivePower(400).withVariableActivePower(200)
                .withFixedReactivePower(150).withVariableReactivePower(50)
                .add();
        network.getLoad("LOAD2").newExtension(LoadDetailAdder.class)
                .withFixedActivePower(150).withVariableActivePower(50)
                .withFixedReactivePower(40).withVariableReactivePower(20)
                .add();
        return network;
    }

    /**
     * The mode where slack participation must <b>not</b> follow p0: on conform load it comes from the LoadDetail
     * variable active power, which the plan does not touch. Recomputing it from p0 here would be as wrong as never
     * recomputing it in PROPORTIONAL_TO_LOAD, and only a reference run can tell.
     */
    @Test
    void loadStepsMatchIndependentLoadFlowWhenSlackIsDistributedOnConformLoad() {
        double[] p0s = {600.0, 1200.0, 300.0};
        List<DoubleTimeSeries> loadPlan = List.of(TimeSeries.createDouble("LOAD", index, p0s));
        TimeSeriesLoadFlowParameters parameters = new TimeSeriesLoadFlowParameters();
        parameters.getLoadFlowParameters().setBalanceType(LoadFlowParameters.BalanceType.PROPORTIONAL_TO_CONFORM_LOAD);
        Map<String, StringWriter> csv = new HashMap<>();
        TimeSeriesLoadFlow.run(networkWithTwoConformLoadBuses(), loadPlan, parameters,
                partitionIndex -> new CsvNetworkResultWriter(csvSink(csv)));

        Map<String, Double> streamedP1 = new HashMap<>();
        csv.get("branches").toString().strip().lines().skip(1).forEach(line -> {
            String[] c = line.split(";");
            streamedP1.put(c[0] + "|" + c[3], Double.parseDouble(c[4]));
        });

        LoadFlow.Runner runner = new LoadFlow.Runner(new OpenLoadFlowProvider(new DenseMatrixFactory()));
        for (int step = 0; step < p0s.length; step++) {
            Network ref = networkWithTwoConformLoadBuses();
            ref.getLoad("LOAD").setP0(p0s[step]);
            assertTrue(runner.run(ref, new LoadFlowParameters()
                    .setBalanceType(LoadFlowParameters.BalanceType.PROPORTIONAL_TO_CONFORM_LOAD)).isFullyConverged());
            String ts = index.getInstantAt(step).toString();
            for (Branch<?> branch : ref.getBranches()) {
                assertEquals(branch.getTerminal1().getP(), streamedP1.get(ts + "|" + branch.getId()), 1e-2,
                        "p1 mismatch on " + branch.getId() + " at step " + step);
            }
        }
    }

    @Test
    void dcLoadStepsMatchIndependentDcLoadFlow() {
        double[] p0s = {600.0, 1200.0, 300.0};
        List<DoubleTimeSeries> loadPlan = List.of(TimeSeries.createDouble("LOAD", index, p0s));
        TimeSeriesLoadFlowParameters parameters = new TimeSeriesLoadFlowParameters();
        parameters.getLoadFlowParameters().setDc(true)
                .setBalanceType(LoadFlowParameters.BalanceType.PROPORTIONAL_TO_LOAD);
        Map<String, StringWriter> csv = new HashMap<>();
        TimeSeriesLoadFlow.run(networkWithTwoLoadBuses(), loadPlan, parameters,
                partitionIndex -> new CsvNetworkResultWriter(csvSink(csv)));

        Map<String, Double> streamedP1 = new HashMap<>();
        csv.get("branches").toString().strip().lines().skip(1).forEach(line -> {
            String[] c = line.split(";");
            streamedP1.put(c[0] + "|" + c[3], Double.parseDouble(c[4]));
        });

        LoadFlow.Runner runner = new LoadFlow.Runner(new OpenLoadFlowProvider(new DenseMatrixFactory()));
        for (int step = 0; step < p0s.length; step++) {
            Network ref = networkWithTwoLoadBuses();
            ref.getLoad("LOAD").setP0(p0s[step]);
            assertTrue(runner.run(ref, new LoadFlowParameters().setDc(true)
                    .setBalanceType(LoadFlowParameters.BalanceType.PROPORTIONAL_TO_LOAD)).isFullyConverged());
            String ts = index.getInstantAt(step).toString();
            for (Branch<?> branch : ref.getBranches()) {
                assertEquals(branch.getTerminal1().getP(), streamedP1.get(ts + "|" + branch.getId()), 1e-2,
                        "DC p1 mismatch on " + branch.getId() + " at step " + step);
            }
        }
    }

    private static TimeSeriesLoadFlowParameters dcParameters(boolean batched) {
        return dcParameters(batched, true);
    }

    private static TimeSeriesLoadFlowParameters dcParameters(boolean batched, boolean distributedSlack) {
        TimeSeriesLoadFlowParameters parameters = new TimeSeriesLoadFlowParameters().setDcBatchedSolve(batched);
        parameters.getLoadFlowParameters().setDc(true).setDistributedSlack(distributedSlack)
                .setBalanceType(LoadFlowParameters.BalanceType.PROPORTIONAL_TO_LOAD);
        return parameters;
    }

    private static Map<String, String> runCapturingCsv(Network network, List<DoubleTimeSeries> plan,
                                                       TimeSeriesLoadFlowParameters parameters) {
        Map<String, StringWriter> csv = new HashMap<>();
        TimeSeriesLoadFlow.run(network, plan, parameters, partitionIndex -> new CsvNetworkResultWriter(csvSink(csv)));
        return csv.entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().toString()));
    }

    /**
     * The batched DC path is an optimization: solving a partition's steps together on the shared factorization must
     * stream exactly what the per-step path streams. Same plan, run both ways, every dataset byte-identical.
     */
    @Test
    void batchedDcMatchesPerStepDc() {
        double[] p0s = {600.0, 1200.0, 300.0};
        List<DoubleTimeSeries> loadPlan = List.of(TimeSeries.createDouble("LOAD", index, p0s));

        // both slack settings: with distribution on (the dispatch the batched path captures moves) and off (where its
        // step accounting comes from the pre-solve mismatch instead of a distributed one)
        for (boolean distributedSlack : new boolean[] {true, false}) {
            Map<String, String> perStep = runCapturingCsv(networkWithTwoLoadBuses(), loadPlan, dcParameters(false, distributedSlack));
            Map<String, String> batched = runCapturingCsv(networkWithTwoLoadBuses(), loadPlan, dcParameters(true, distributedSlack));

            assertEquals(perStep.keySet(), batched.keySet());
            assertEquals(perStep.get("branches"), batched.get("branches"), "branches, distributedSlack=" + distributedSlack);
            assertEquals(perStep.get("buses"), batched.get("buses"), "buses, distributedSlack=" + distributedSlack);
            assertEquals(perStep.get("generators"), batched.get("generators"), "generators, distributedSlack=" + distributedSlack);
        }
    }

    /**
     * A plan longer than {@code DC_BATCH_SIZE} forces the batched path to chunk: still byte-identical to the per-step
     * path, so nothing is lost at a chunk boundary.
     */
    @Test
    void batchedDcSpansMultipleChunksIdenticallyToPerStep() {
        int steps = 600; // > DC_BATCH_SIZE (256), so at least three chunks
        RegularTimeSeriesIndex bigIndex = RegularTimeSeriesIndex.create(Instant.parse("2025-01-01T00:00:00Z"),
                Instant.parse("2025-01-01T00:00:00Z").plus(Duration.ofHours(steps - 1L)), Duration.ofHours(1));
        double[] p0s = new double[steps];
        for (int i = 0; i < steps; i++) {
            p0s[i] = 400.0 + (i % 40) * 20.0; // varies per step, all converging in DC
        }
        List<DoubleTimeSeries> loadPlan = List.of(TimeSeries.createDouble("LOAD", bigIndex, p0s));

        Map<String, String> perStep = runCapturingCsv(networkWithTwoLoadBuses(), loadPlan, dcParameters(false));
        Map<String, String> batched = runCapturingCsv(networkWithTwoLoadBuses(), loadPlan, dcParameters(true));

        assertEquals(perStep.get("branches"), batched.get("branches"));
        assertEquals(perStep.get("buses"), batched.get("buses"));
        assertEquals(perStep.get("generators"), batched.get("generators"));
    }

    /**
     * The direct check the transitive chain (batched equals per-step, per-step equals an independent load flow) leaves
     * implicit: the batched path, which solves a chunk of steps together, must equal a classical
     * {@link com.powsybl.loadflow.LoadFlow} run on the network carrying each step's values <b>one step at a time</b> --
     * an oracle that shares none of the batched path's machinery and never solves two steps together. Both slack
     * settings, since distribution shapes the right hand side the batch stacks.
     */
    @Test
    void batchedDcMatchesIndependentDcLoadFlow() {
        double[] p0s = {600.0, 1200.0, 300.0};
        List<DoubleTimeSeries> loadPlan = List.of(TimeSeries.createDouble("LOAD", index, p0s));
        LoadFlow.Runner runner = new LoadFlow.Runner(new OpenLoadFlowProvider(new DenseMatrixFactory()));

        for (boolean distributedSlack : new boolean[] {true, false}) {
            Map<String, String> batched = runCapturingCsv(networkWithTwoLoadBuses(), loadPlan, dcParameters(true, distributedSlack));
            Map<String, Double> streamedP1 = new HashMap<>();
            batched.get("branches").strip().lines().skip(1).forEach(line -> {
                String[] c = line.split(";");
                streamedP1.put(c[0] + "|" + c[3], Double.parseDouble(c[4]));
            });

            for (int step = 0; step < p0s.length; step++) {
                Network ref = networkWithTwoLoadBuses();
                ref.getLoad("LOAD").setP0(p0s[step]);
                assertTrue(runner.run(ref, new LoadFlowParameters().setDc(true).setDistributedSlack(distributedSlack)
                        .setBalanceType(LoadFlowParameters.BalanceType.PROPORTIONAL_TO_LOAD)).isFullyConverged());
                String ts = index.getInstantAt(step).toString();
                for (Branch<?> branch : ref.getBranches()) {
                    assertEquals(branch.getTerminal1().getP(), streamedP1.get(ts + "|" + branch.getId()), 1e-2,
                            "batched DC p1 on " + branch.getId() + " at step " + step + ", distributedSlack=" + distributedSlack);
                }
            }
        }
    }

    /**
     * The plan drives a generator, not a load: the batched path builds its right hand side from the generation target
     * the plan set and the slack distribution over it, so a generator-driven plan is a distinct case from a
     * load-driven one. Validated against an independent one-step-at-a-time load flow, both slack settings.
     */
    @Test
    void batchedDcGeneratorPlanMatchesIndependentDcLoadFlow() {
        double[] targetPs = {700.0, 500.0, 607.0};
        List<DoubleTimeSeries> genPlan = List.of(TimeSeries.createDouble("GEN", index, targetPs));
        LoadFlow.Runner runner = new LoadFlow.Runner(new OpenLoadFlowProvider(new DenseMatrixFactory()));

        for (boolean distributedSlack : new boolean[] {true, false}) {
            Map<String, String> batched = runCapturingCsv(EurostagTutorialExample1Factory.create(), genPlan, dcParameters(true, distributedSlack));
            Map<String, Double> streamedP1 = new HashMap<>();
            batched.get("branches").strip().lines().skip(1).forEach(line -> {
                String[] c = line.split(";");
                streamedP1.put(c[0] + "|" + c[3], Double.parseDouble(c[4]));
            });

            for (int step = 0; step < targetPs.length; step++) {
                Network ref = EurostagTutorialExample1Factory.create();
                ref.getGenerator("GEN").setTargetP(targetPs[step]);
                assertTrue(runner.run(ref, new LoadFlowParameters().setDc(true).setDistributedSlack(distributedSlack)
                        .setBalanceType(LoadFlowParameters.BalanceType.PROPORTIONAL_TO_LOAD)).isFullyConverged());
                String ts = index.getInstantAt(step).toString();
                for (Branch<?> branch : ref.getBranches()) {
                    assertEquals(branch.getTerminal1().getP(), streamedP1.get(ts + "|" + branch.getId()), 1e-2,
                            "batched DC gen-plan p1 on " + branch.getId() + " at step " + step + ", distributedSlack=" + distributedSlack);
                }
            }
        }
    }

    /**
     * A network of two connected components: the batched path solves each as its own run, and their rows and per-step
     * accounting must aggregate exactly as an independent load flow on the whole two-component network does. Exercises
     * the per-run loop a single-component network never does.
     */
    @Test
    void batchedDcTwoComponentsMatchIndependentDcLoadFlow() {
        double[] loadAs = {90.0, 130.0, 60.0};
        double[] loadBs = {110.0, 70.0, 140.0};
        List<DoubleTimeSeries> twoComponentPlan = List.of(
                TimeSeries.createDouble("LOADA", index, loadAs),
                TimeSeries.createDouble("LOADB", index, loadBs));
        LoadFlow.Runner runner = new LoadFlow.Runner(new OpenLoadFlowProvider(new DenseMatrixFactory()));

        // simulate every component, not just the main one, so the batched path actually has more than one run
        TimeSeriesLoadFlowParameters parameters = dcParameters(true, false);
        parameters.getLoadFlowParameters().setComponentMode(LoadFlowParameters.ComponentMode.ALL_CONNECTED);
        Map<String, String> batched = runCapturingCsv(twoComponentNetwork(), twoComponentPlan, parameters);
        Map<String, Double> streamedP1 = new HashMap<>();
        batched.get("branches").strip().lines().skip(1).forEach(line -> {
            String[] c = line.split(";");
            streamedP1.put(c[0] + "|" + c[3], Double.parseDouble(c[4]));
        });

        for (int step = 0; step < loadAs.length; step++) {
            Network ref = twoComponentNetwork();
            ref.getLoad("LOADA").setP0(loadAs[step]);
            ref.getLoad("LOADB").setP0(loadBs[step]);
            assertTrue(runner.run(ref, new LoadFlowParameters().setDc(true).setDistributedSlack(false)
                    .setComponentMode(LoadFlowParameters.ComponentMode.ALL_CONNECTED)).isFullyConverged());
            String ts = index.getInstantAt(step).toString();
            for (Branch<?> branch : ref.getBranches()) {
                assertEquals(branch.getTerminal1().getP(), streamedP1.get(ts + "|" + branch.getId()), 1e-2,
                        "batched DC two-component p1 on " + branch.getId() + " at step " + step);
            }
        }
    }

    /**
     * A DC network with a zero-impedance subnetwork: the batched path cannot serve it (its second pass has no per-step
     * injections to recompute the zero-impedance flows from), so it must fall back to the per-step path and stream the
     * same rows. Same network run with the flag off is the reference; identical output means the fall-back fired.
     */
    @Test
    void batchedDcFallsBackForZeroImpedanceNetwork() {
        double[] p0s = {600.0, 1200.0, 300.0};
        List<DoubleTimeSeries> loadPlan = List.of(TimeSeries.createDouble("LOAD", index, p0s));

        Map<String, String> perStep = runCapturingCsv(zeroImpedanceNetwork(), loadPlan, zeroImpedanceDcParameters(false));
        Map<String, String> batched = runCapturingCsv(zeroImpedanceNetwork(), loadPlan, zeroImpedanceDcParameters(true));

        assertEquals(perStep.get("branches"), batched.get("branches"));
        assertEquals(perStep.get("buses"), batched.get("buses"));
        assertEquals(perStep.get("generators"), batched.get("generators"));
    }

    /**
     * A DC network with an active phase-control outer loop: its per-step re-solves cannot be pre-batched, so the
     * batched path must fall back to the per-step path. Identical output with the flag on and off shows it did.
     */
    @Test
    void batchedDcFallsBackForActiveOuterLoop() {
        double[] loads = {80.0, 120.0, 60.0};
        List<DoubleTimeSeries> loadPlan = List.of(TimeSeries.createDouble("LD2", index, loads));

        Map<String, String> perStep = runCapturingCsv(phaseControlNetwork(), loadPlan, phaseControlDcParameters(false));
        Map<String, String> batched = runCapturingCsv(phaseControlNetwork(), loadPlan, phaseControlDcParameters(true));

        assertEquals(perStep.get("branches"), batched.get("branches"));
        assertEquals(perStep.get("buses"), batched.get("buses"));
        assertEquals(perStep.get("generators"), batched.get("generators"));
    }

    /** Two independent single-line islands, each a generator bus feeding a load bus; two DC connected components. */
    private static Network twoComponentNetwork() {
        Network network = Network.create("two-components", "test");
        for (String island : List.of("A", "B")) {
            Substation s = network.newSubstation().setId("S" + island).add();
            VoltageLevel vl1 = s.newVoltageLevel().setId("VL1" + island).setNominalV(400)
                    .setTopologyKind(TopologyKind.BUS_BREAKER).add();
            vl1.getBusBreakerView().newBus().setId("B1" + island).add();
            VoltageLevel vl2 = s.newVoltageLevel().setId("VL2" + island).setNominalV(400)
                    .setTopologyKind(TopologyKind.BUS_BREAKER).add();
            vl2.getBusBreakerView().newBus().setId("B2" + island).add();
            vl1.newGenerator().setId("GEN" + island).setBus("B1" + island).setConnectableBus("B1" + island)
                    .setTargetP(100).setMinP(0).setMaxP(300).setTargetV(400).setVoltageRegulatorOn(true).add();
            vl2.newLoad().setId("LOAD" + island).setBus("B2" + island).setConnectableBus("B2" + island)
                    .setP0(100).setQ0(10).add();
            network.newLine().setId("L" + island).setVoltageLevel1("VL1" + island).setBus1("B1" + island)
                    .setConnectableBus1("B1" + island).setVoltageLevel2("VL2" + island).setBus2("B2" + island)
                    .setConnectableBus2("B2" + island).setR(0.1).setX(3).setG1(0).setB1(0).setG2(0).setB2(0).add();
        }
        return network;
    }

    /** Two load buses with one line made zero-impedance, so the DC network has a zero-impedance subnetwork. */
    private static Network zeroImpedanceNetwork() {
        Network network = networkWithTwoLoadBuses();
        network.getLine("NHV1_NHV2_2").setR(0).setX(0);
        return network;
    }

    private static TimeSeriesLoadFlowParameters zeroImpedanceDcParameters(boolean batched) {
        TimeSeriesLoadFlowParameters parameters = dcParameters(batched);
        OpenLoadFlowParameters.create(parameters.getLoadFlowParameters())
                .setLowImpedanceBranchMode(OpenLoadFlowParameters.LowImpedanceBranchMode.REPLACE_BY_ZERO_IMPEDANCE_LINE);
        return parameters;
    }

    /** The phase-shifter network with its transformer set to regulate active power, so a DC phase-control outer loop runs. */
    private static Network phaseControlNetwork() {
        Network network = PhaseShifterTestCaseFactory.create();
        TwoWindingsTransformer ps1 = network.getTwoWindingsTransformer("PS1");
        ps1.getPhaseTapChanger().getStep(0).setAlpha(-5);
        ps1.getPhaseTapChanger().getStep(2).setAlpha(5);
        ps1.getPhaseTapChanger().setTargetDeadband(10).setRegulationValue(-80)
                .setRegulationMode(PhaseTapChanger.RegulationMode.ACTIVE_POWER_CONTROL).setRegulating(true);
        return network;
    }

    private static TimeSeriesLoadFlowParameters phaseControlDcParameters(boolean batched) {
        TimeSeriesLoadFlowParameters parameters = new TimeSeriesLoadFlowParameters().setDcBatchedSolve(batched);
        parameters.getLoadFlowParameters().setDc(true).setPhaseShifterRegulationOn(true);
        return parameters;
    }

    /**
     * Asking for the batched solve on an AC plan is a no-op, not an error: the DC-only optimization does not apply, so
     * the engine keeps the per-step path and streams the same rows as with the flag off.
     */
    @Test
    void batchedFlagOnAcFallsBackToPerStep() {
        Map<String, String> off = runCapturingCsv(EurostagTutorialExample1Factory.create(), plan,
                new TimeSeriesLoadFlowParameters());
        Map<String, String> on = runCapturingCsv(EurostagTutorialExample1Factory.create(), plan,
                new TimeSeriesLoadFlowParameters().setDcBatchedSolve(true));

        assertEquals(off.get("branches"), on.get("branches"));
        assertEquals(off.get("buses"), on.get("buses"));
        assertEquals(off.get("generators"), on.get("generators"));
    }

    /**
     * With the option on, a step must equal a load flow on a network carrying both this step's p0 and the q0 that keeps
     * the load's power factor -- the same contract as everything else here, over two values instead of one.
     */
    @Test
    void loadStepsKeepingPowerFactorMatchIndependentLoadFlow() {
        // 1200 MW converges with q0 left at 200 MVar but collapses once q0 follows it to 400, so this stops at 1000
        double[] p0s = {600.0, 1000.0, 300.0};
        List<DoubleTimeSeries> loadPlan = List.of(TimeSeries.createDouble("LOAD", index, p0s));
        TimeSeriesLoadFlowParameters parameters = new TimeSeriesLoadFlowParameters().setKeepLoadPowerFactorConstant(true);
        Map<String, StringWriter> csv = new HashMap<>();
        TimeSeriesLoadFlow.run(networkWithTwoLoadBuses(), loadPlan, parameters,
                partitionIndex -> new CsvNetworkResultWriter(csvSink(csv)));

        Map<String, Double> streamedP1 = new HashMap<>();
        Map<String, Double> streamedQ1 = new HashMap<>();
        csv.get("branches").toString().strip().lines().skip(1).forEach(line -> {
            String[] c = line.split(";");
            streamedP1.put(c[0] + "|" + c[3], Double.parseDouble(c[4]));
            streamedQ1.put(c[0] + "|" + c[3], Double.parseDouble(c[5]));
        });

        LoadFlow.Runner runner = new LoadFlow.Runner(new OpenLoadFlowProvider(new DenseMatrixFactory()));
        for (int step = 0; step < p0s.length; step++) {
            Network ref = networkWithTwoLoadBuses();
            // LOAD is 600 MW / 200 MVar as built, so keeping its power factor means q0 = p0 / 3
            ref.getLoad("LOAD").setP0(p0s[step]).setQ0(p0s[step] / 600.0 * 200.0);
            assertTrue(runner.run(ref, new LoadFlowParameters()).isFullyConverged());
            String ts = index.getInstantAt(step).toString();
            for (Branch<?> branch : ref.getBranches()) {
                assertEquals(branch.getTerminal1().getP(), streamedP1.get(ts + "|" + branch.getId()), 1e-2,
                        "p1 mismatch on " + branch.getId() + " at step " + step);
                assertEquals(branch.getTerminal1().getQ(), streamedQ1.get(ts + "|" + branch.getId()), 1e-2,
                        "q1 mismatch on " + branch.getId() + " at step " + step);
            }
        }
    }

    /**
     * The option is what makes reactive power move. Without it the same plan must leave q0 where the grid model put it,
     * which is the difference the two tests exist to hold apart.
     */
    @Test
    void keepingPowerFactorIsWhatMovesReactivePower() {
        double[] p0s = {600.0, 1000.0, 300.0};
        List<DoubleTimeSeries> loadPlan = List.of(TimeSeries.createDouble("LOAD", index, p0s));

        Map<String, StringWriter> scaled = new HashMap<>();
        TimeSeriesLoadFlow.run(networkWithTwoLoadBuses(), loadPlan,
                new TimeSeriesLoadFlowParameters().setKeepLoadPowerFactorConstant(true),
                partitionIndex -> new CsvNetworkResultWriter(csvSink(scaled)));

        Map<String, StringWriter> untouched = new HashMap<>();
        TimeSeriesLoadFlow.run(networkWithTwoLoadBuses(), loadPlan, new TimeSeriesLoadFlowParameters(),
                partitionIndex -> new CsvNetworkResultWriter(csvSink(untouched)));

        Function<Map<String, StringWriter>, Map<String, Double>> q1Of = csv -> {
            Map<String, Double> q1 = new HashMap<>();
            csv.get("branches").toString().strip().lines().skip(1).forEach(line -> {
                String[] c = line.split(";");
                q1.put(c[0] + "|" + c[3], Double.parseDouble(c[5]));
            });
            return q1;
        };
        Map<String, Double> scaledQ1 = q1Of.apply(scaled);
        Map<String, Double> untouchedQ1 = q1Of.apply(untouched);

        // step 0 replays the load as built, so the option has nothing to change there
        String step0 = index.getInstantAt(0).toString();
        assertEquals(untouchedQ1.get(step0 + "|NHV2_NLOAD"), scaledQ1.get(step0 + "|NHV2_NLOAD"), 1e-2,
                "at the as-built p0 there is no scaling to do");
        // at 1000 MW q0 follows from 200 to 333 MVar, and the flow to the load bus has to show it
        String step1 = index.getInstantAt(1).toString();
        assertTrue(Math.abs(scaledQ1.get(step1 + "|NHV2_NLOAD") - untouchedQ1.get(step1 + "|NHV2_NLOAD")) > 100.0,
                () -> "expected the option to move q1 at step 1, got " + scaledQ1.get(step1 + "|NHV2_NLOAD")
                        + " against " + untouchedQ1.get(step1 + "|NHV2_NLOAD"));
    }

    @Test
    void keepingPowerFactorOfAZeroP0LoadThrows() {
        Network network = networkWithTwoLoadBuses();
        network.getLoad("LOAD").setP0(0).setQ0(50);
        List<DoubleTimeSeries> loadPlan = List.of(TimeSeries.createDouble("LOAD", index, 600.0, 800.0, 400.0));
        PowsyblException e = assertThrows(PowsyblException.class,
                () -> TimeSeriesLoadFlow.run(network, loadPlan,
                        new TimeSeriesLoadFlowParameters().setKeepLoadPowerFactorConstant(true),
                        NetworkResultWriterFactory.NO_OP));
        assertTrue(e.getMessage().contains("zero p0"), e.getMessage());
        assertTrue(e.getMessage().contains("LOAD"), e.getMessage());
    }

    @Test
    void generatorAndLoadCanBePlannedTogether() {
        List<DoubleTimeSeries> mixedPlan = List.of(TimeSeries.createDouble("GEN", index, targets),
                TimeSeries.createDouble("LOAD", index, 600.0, 800.0, 400.0));
        Map<String, StringWriter> csv = new HashMap<>();
        TimeSeriesLoadFlowResult result = TimeSeriesLoadFlow.run(EurostagTutorialExample1Factory.create(), mixedPlan,
                new TimeSeriesLoadFlowParameters(), partitionIndex -> new CsvNetworkResultWriter(csvSink(csv)));

        assertEquals(3, result.getStepResults().size());
        assertTrue(result.getStepResults().stream()
                .allMatch(s -> s.status() == LoadFlowResult.ComponentResult.Status.CONVERGED));

        Map<String, Double> streamedP1 = new HashMap<>();
        csv.get("branches").toString().strip().lines().skip(1).forEach(line -> {
            String[] c = line.split(";");
            streamedP1.put(c[0] + "|" + c[3], Double.parseDouble(c[4]));
        });

        LoadFlow.Runner runner = new LoadFlow.Runner(new OpenLoadFlowProvider(new DenseMatrixFactory()));
        double[] p0s = {600.0, 800.0, 400.0};
        for (int step = 0; step < targets.length; step++) {
            Network ref = EurostagTutorialExample1Factory.create();
            ref.getGenerator("GEN").setTargetP(targets[step]);
            ref.getLoad("LOAD").setP0(p0s[step]);
            assertTrue(runner.run(ref, new LoadFlowParameters()).isFullyConverged());
            String ts = index.getInstantAt(step).toString();
            assertEquals(ref.getLine("NHV1_NHV2_1").getTerminal1().getP(), streamedP1.get(ts + "|NHV1_NHV2_1"), 1e-2,
                    "p1 mismatch at step " + step);
        }
    }

    /**
     * A plan series carries active power only. q0 is a separate input it does not describe, so it stays where the grid
     * model put it and the load's power factor moves across steps.
     */
    @Test
    void loadPlanLeavesReactivePowerAlone() {
        double[] p0s = {600.0, 800.0, 400.0};
        List<DoubleTimeSeries> loadPlan = List.of(TimeSeries.createDouble("LOAD", index, p0s));
        Map<String, StringWriter> csv = new HashMap<>();
        TimeSeriesLoadFlow.run(EurostagTutorialExample1Factory.create(), loadPlan, new TimeSeriesLoadFlowParameters(),
                partitionIndex -> new CsvNetworkResultWriter(csvSink(csv)));

        // q1 of the line feeding the load bus, per step, against a run whose p0 moved and whose q0 did not
        Map<String, Double> streamedQ1 = new HashMap<>();
        csv.get("branches").toString().strip().lines().skip(1).forEach(line -> {
            String[] c = line.split(";");
            streamedQ1.put(c[0] + "|" + c[3], Double.parseDouble(c[5]));
        });

        LoadFlow.Runner runner = new LoadFlow.Runner(new OpenLoadFlowProvider(new DenseMatrixFactory()));
        for (int step = 0; step < p0s.length; step++) {
            Network ref = EurostagTutorialExample1Factory.create();
            ref.getLoad("LOAD").setP0(p0s[step]);
            assertEquals(200.0, ref.getLoad("LOAD").getQ0(), 1e-9, "q0 is not the plan's to move");
            assertTrue(runner.run(ref, new LoadFlowParameters()).isFullyConverged());
            String ts = index.getInstantAt(step).toString();
            assertEquals(ref.getLine("NHV1_NHV2_1").getTerminal1().getQ(), streamedQ1.get(ts + "|NHV1_NHV2_1"), 1e-2,
                    "q1 mismatch at step " + step);
        }
    }

    private static Network networkWithDroopHvdc() {
        Network network = HvdcNetworkFactory.createWithHvdcInAcEmulation();
        network.getHvdcLine("hvdc34").newExtension(HvdcAngleDroopActivePowerControlAdder.class)
                .withDroop(180).withP0(0).withEnabled(true).add();
        return network;
    }

    private static float droopP0(Network network) {
        return network.getHvdcLine("hvdc34").getExtension(HvdcAngleDroopActivePowerControl.class).getP0();
    }

    /**
     * The whole contract, for an angle-droop HVDC: its flow is p0 + k(theta1 - theta2), and it is p0 the plan moves.
     * Each step must come out equal to a load flow on a network whose droop p0 is that step's value.
     */
    @Test
    void hvdcDroopStepsMatchIndependentLoadFlow() {
        double[] p0s = {-100.0, 200.0, 0.0};
        List<DoubleTimeSeries> hvdcPlan = List.of(TimeSeries.createDouble("hvdc34", index, p0s));
        Map<String, StringWriter> csv = new HashMap<>();
        TimeSeriesLoadFlow.run(networkWithDroopHvdc(), hvdcPlan, new TimeSeriesLoadFlowParameters(),
                partitionIndex -> new CsvNetworkResultWriter(csvSink(csv)));

        Map<String, Double> streamedP1 = new HashMap<>();
        csv.get("branches").toString().strip().lines().skip(1).forEach(line -> {
            String[] c = line.split(";");
            streamedP1.put(c[0] + "|" + c[3], Double.parseDouble(c[4]));
        });

        LoadFlow.Runner runner = new LoadFlow.Runner(new OpenLoadFlowProvider(new DenseMatrixFactory()));
        for (int step = 0; step < p0s.length; step++) {
            Network ref = networkWithDroopHvdc();
            ref.getHvdcLine("hvdc34").getExtension(HvdcAngleDroopActivePowerControl.class).setP0((float) p0s[step]);
            assertTrue(runner.run(ref, new LoadFlowParameters()).isFullyConverged());
            String ts = index.getInstantAt(step).toString();
            for (Branch<?> branch : ref.getBranches()) {
                assertEquals(branch.getTerminal1().getP(), streamedP1.get(ts + "|" + branch.getId()), 1e-2,
                        "p1 mismatch on " + branch.getId() + " at step " + step);
            }
        }

        // p0 genuinely moves the flow between steps -- the golden check above would pass on a saturated flat line too
        assertTrue(Math.abs(streamedP1.get(index.getInstantAt(0).toString() + "|l45")
                - streamedP1.get(index.getInstantAt(1).toString() + "|l45")) > 0.1,
                "the droop p0 has to change the flow across steps");
    }

    /**
     * A restored step must leave the droop p0 where the network was built, not where the previous step left it. Same
     * plan, but the failing step in the middle would corrupt its neighbour if the offset were not part of the snapshot.
     */
    @Test
    void hvdcDroopStepIsIsolatedFromItsNeighbours() {
        double[] p0s = {0.0, 200.0, 0.0};
        List<DoubleTimeSeries> hvdcPlan = List.of(TimeSeries.createDouble("hvdc34", index, p0s));
        Map<String, StringWriter> csv = new HashMap<>();
        TimeSeriesLoadFlow.run(networkWithDroopHvdc(), hvdcPlan, new TimeSeriesLoadFlowParameters(),
                partitionIndex -> new CsvNetworkResultWriter(csvSink(csv)));

        Map<String, Double> streamedP1 = new HashMap<>();
        csv.get("branches").toString().strip().lines().skip(1).forEach(line -> {
            String[] c = line.split(";");
            streamedP1.put(c[0] + "|" + c[3], Double.parseDouble(c[4]));
        });

        // steps 0 and 2 carry the same p0, so they must produce the same flow: the p0=200 middle step leaves nothing behind
        assertEquals(streamedP1.get(index.getInstantAt(0).toString() + "|l45"),
                streamedP1.get(index.getInstantAt(2).toString() + "|l45"), 1e-9,
                "two steps at the same p0 must match; the offset is restored between steps");
    }

    /**
     * The whole contract, for a fixed-set-point VSC HVDC: the series value is the line set point, split between the two
     * converter stations. Each step must equal a load flow on a network carrying that set point. FourSubstations HVDC1
     * is VSC with no angle-droop control, so it takes the fixed path.
     */
    @Test
    void fixedVscHvdcStepsMatchIndependentLoadFlow() {
        double[] setpoints = {10.0, 100.0, 50.0};
        List<DoubleTimeSeries> hvdcPlan = List.of(TimeSeries.createDouble("HVDC1", index, setpoints));
        Map<String, StringWriter> csv = new HashMap<>();
        TimeSeriesLoadFlow.run(FourSubstationsNodeBreakerFactory.create(), hvdcPlan, new TimeSeriesLoadFlowParameters(),
                partitionIndex -> new CsvNetworkResultWriter(csvSink(csv)));

        Map<String, Double> streamedP1 = new HashMap<>();
        csv.get("branches").toString().strip().lines().skip(1).forEach(line -> {
            String[] c = line.split(";");
            streamedP1.put(c[0] + "|" + c[3], Double.parseDouble(c[4]));
        });
        // the converter station dispatch is streamed in the generators dataset; targetP is the requested set point
        Map<String, Double> streamedGenTargetP = new HashMap<>();
        csv.get("generators").toString().strip().lines().skip(1).forEach(line -> {
            String[] c = line.split(";");
            streamedGenTargetP.put(c[0] + "|" + c[3], Double.parseDouble(c[4]));
        });

        LoadFlow.Runner runner = new LoadFlow.Runner(new OpenLoadFlowProvider(new DenseMatrixFactory()));
        for (int step = 0; step < setpoints.length; step++) {
            Network ref = FourSubstationsNodeBreakerFactory.create();
            ref.getHvdcLine("HVDC1").setActivePowerSetpoint(setpoints[step]);
            assertTrue(runner.run(ref, new LoadFlowParameters()).isFullyConverged());
            String ts = index.getInstantAt(step).toString();
            for (Branch<?> branch : ref.getBranches()) {
                assertEquals(branch.getTerminal1().getP(), streamedP1.get(ts + "|" + branch.getId()), 1e-2,
                        "p1 mismatch on " + branch.getId() + " at step " + step);
            }
            // the rectifier's streamed targetP tracks the plan set point (it consumes it, generator convention: negative)
            assertEquals(-setpoints[step], streamedGenTargetP.get(ts + "|VSC1"), 1e-2,
                    "VSC1 targetP must reflect the planned set point at step " + step);
        }
    }

    @Test
    void planningAnLccHvdcThrows() {
        // FourSubstations HVDC2 is an LCC line, whose stations fold into a bus load: not supported yet
        List<DoubleTimeSeries> hvdcPlan = List.of(TimeSeries.createDouble("HVDC2", index, 10.0, 20.0, 30.0));
        PowsyblException e = assertThrows(PowsyblException.class,
                () -> TimeSeriesLoadFlow.run(FourSubstationsNodeBreakerFactory.create(), hvdcPlan,
                        new TimeSeriesLoadFlowParameters(), NetworkResultWriterFactory.NO_OP));
        assertTrue(e.getMessage().contains("HVDC2"), e.getMessage());
        assertTrue(e.getMessage().contains("LCC HVDC"), e.getMessage());
    }

    private static Set<String> readBranchRows(Path datasetDir) throws IOException {
        try (Stream<Path> parts = Files.list(datasetDir.resolve("branches"))) {
            return parts.flatMap(part -> {
                try {
                    // drop the per-file CSV header
                    return Files.readAllLines(part).stream().skip(1);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }).collect(Collectors.toSet());
        }
    }
}
