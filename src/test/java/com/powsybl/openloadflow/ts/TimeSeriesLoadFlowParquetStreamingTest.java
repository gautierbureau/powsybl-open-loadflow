/**
 * Copyright (c) 2025, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.ts;

import blue.strategic.parquet.Hydrator;
import blue.strategic.parquet.HydratorSupplier;
import blue.strategic.parquet.ParquetReader;
import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.test.EurostagTutorialExample1Factory;
import com.powsybl.loadflow.LoadFlow;
import com.powsybl.loadflow.LoadFlowParameters;
import com.powsybl.loadflow.resultswriter.ParquetNetworkResultWriterFactory;
import com.powsybl.math.matrix.DenseMatrixFactory;
import com.powsybl.openloadflow.OpenLoadFlowProvider;
import com.powsybl.timeseries.DoubleTimeSeries;
import com.powsybl.timeseries.RegularTimeSeriesIndex;
import com.powsybl.timeseries.TimeSeries;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end test: a time-series load flow streams its three datasets to Parquet, read back with parquet-floor.
 *
 * @author Gautier Bureau {@literal <gautier.bureau at rte-france.com>}
 */
class TimeSeriesLoadFlowParquetStreamingTest {

    private static final double[] TARGETS = {600.0, 500.0, 700.0};

    private final RegularTimeSeriesIndex index = RegularTimeSeriesIndex.create(Instant.parse("2025-01-01T00:00:00Z"),
            Instant.parse("2025-01-01T02:00:00Z"), Duration.ofHours(1));

    @Test
    void streamsThreeDatasetsToParquet(@TempDir Path dir) throws IOException {
        Network network = EurostagTutorialExample1Factory.create();
        List<DoubleTimeSeries> plan = List.of(TimeSeries.createDouble("GEN", index, TARGETS));

        TimeSeriesLoadFlow.run(network, plan, new TimeSeriesLoadFlowParameters(),
                new ParquetNetworkResultWriterFactory(dir));

        List<Map<String, Object>> branches = readParquet(dir.resolve("branches").resolve("part-0.parquet"));
        List<Map<String, Object>> buses = readParquet(dir.resolve("buses").resolve("part-0.parquet"));
        List<Map<String, Object>> generators = readParquet(dir.resolve("generators").resolve("part-0.parquet"));

        // one row per element per step: 4 branches, 4 buses and 1 generator over 3 steps
        assertEquals(12, branches.size());
        assertEquals(12, buses.size());
        assertEquals(3, generators.size());

        // the step timestamp round-trips as the state id, and time-series rows carry no sub-state
        Set<String> expectedStateIds = Set.of("2025-01-01T00:00:00Z", "2025-01-01T01:00:00Z", "2025-01-01T02:00:00Z");
        assertEquals(expectedStateIds, branches.stream().map(r -> String.valueOf(r.get("stateId"))).collect(Collectors.toSet()));
        assertTrue(branches.stream().allMatch(r -> String.valueOf(r.get("subStateId")).isEmpty()));
        assertTrue(branches.stream().allMatch(r -> "CONVERGED".equals(String.valueOf(r.get("status")))));

        // values survive the round-trip: each branch flow still matches an independent load flow
        LoadFlow.Runner runner = new LoadFlow.Runner(new OpenLoadFlowProvider(new DenseMatrixFactory()));
        for (int step = 0; step < TARGETS.length; step++) {
            Network ref = EurostagTutorialExample1Factory.create();
            ref.getGenerator("GEN").setTargetP(TARGETS[step]);
            assertTrue(runner.run(ref, new LoadFlowParameters()).isFullyConverged());

            String stateId = index.getInstantAt(step).toString();
            Map<String, Object> row = branches.stream()
                    .filter(r -> stateId.equals(String.valueOf(r.get("stateId")))
                            && "NHV1_NHV2_1".equals(String.valueOf(r.get("branchId"))))
                    .findFirst().orElseThrow();
            assertEquals(ref.getLine("NHV1_NHV2_1").getTerminal1().getP(), (double) row.get("p1"), 1e-2,
                    "p1 mismatch at step " + step);

            Map<String, Object> generatorRow = generators.stream()
                    .filter(r -> stateId.equals(String.valueOf(r.get("stateId"))))
                    .findFirst().orElseThrow();
            assertEquals(TARGETS[step], (double) generatorRow.get("targetP"), 1e-2);
            assertEquals("GEN", String.valueOf(generatorRow.get("generatorId")));
        }

        // bus voltages are streamed in kV with a plausible magnitude, not in per-unit
        assertTrue(buses.stream().allMatch(r -> (double) r.get("v") > 1.0));
    }

    private static List<Map<String, Object>> readParquet(Path file) throws IOException {
        HydratorSupplier<Map<String, Object>, Map<String, Object>> supplier = HydratorSupplier.constantly(new Hydrator<>() {
            @Override
            public Map<String, Object> start() {
                return new HashMap<>();
            }

            @Override
            public Map<String, Object> add(Map<String, Object> target, String heading, Object value) {
                target.put(heading, value);
                return target;
            }

            @Override
            public Map<String, Object> finish(Map<String, Object> target) {
                return target;
            }
        });
        try (Stream<Map<String, Object>> stream = ParquetReader.streamContent(file.toFile(), supplier)) {
            return stream.toList();
        }
    }
}
