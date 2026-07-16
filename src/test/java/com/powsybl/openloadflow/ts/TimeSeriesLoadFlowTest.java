/**
 * Copyright (c) 2025, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.ts;

import com.powsybl.commons.PowsyblException;
import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.test.EurostagTutorialExample1Factory;
import com.powsybl.iidm.network.test.FourSubstationsNodeBreakerFactory;
import com.powsybl.loadflow.LoadFlow;
import com.powsybl.loadflow.LoadFlowParameters;
import com.powsybl.loadflow.LoadFlowResult;
import com.powsybl.loadflow.resultswriter.CsvNetworkResultWriter;
import com.powsybl.loadflow.resultswriter.CsvNetworkResultWriterFactory;
import com.powsybl.loadflow.resultswriter.NetworkResultWriterFactory;
import com.powsybl.math.matrix.DenseMatrixFactory;
import com.powsybl.openloadflow.OpenLoadFlowProvider;
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

    @Test
    void unknownGeneratorInPlanThrows() {
        Network network = EurostagTutorialExample1Factory.create();
        List<DoubleTimeSeries> badPlan = List.of(TimeSeries.createDouble("MISSING", index, targets));
        PowsyblException e = assertThrows(PowsyblException.class,
                () -> TimeSeriesLoadFlow.run(network, badPlan, new TimeSeriesLoadFlowParameters(), NetworkResultWriterFactory.NO_OP));
        assertTrue(e.getMessage().contains("Unknown generator id"));
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
