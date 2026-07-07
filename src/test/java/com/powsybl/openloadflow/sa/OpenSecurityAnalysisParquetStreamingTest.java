/**
 * Copyright (c) 2025, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.sa;

import blue.strategic.parquet.Hydrator;
import blue.strategic.parquet.HydratorSupplier;
import blue.strategic.parquet.ParquetReader;
import com.powsybl.contingency.BranchContingency;
import com.powsybl.contingency.ContingenciesProvider;
import com.powsybl.contingency.Contingency;
import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.test.EurostagTutorialExample1Factory;
import com.powsybl.openloadflow.CommonTestConfig;
import com.powsybl.security.SecurityAnalysisParameters;
import com.powsybl.security.SecurityAnalysisRunParameters;
import com.powsybl.security.writer.parquet.ParquetSecurityAnalysisResultWriterFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end test: a monitor-all AC security analysis streams every branch flow to a Parquet dataset, read back with
 * parquet-floor (see {@code design/ac-security-analysis-flow-streaming.md}).
 *
 * @author (design proposal)
 */
class OpenSecurityAnalysisParquetStreamingTest extends AbstractOpenSecurityAnalysisTest {

    OpenSecurityAnalysisParquetStreamingTest(CommonTestConfig commonTestConfig) {
        super(commonTestConfig);
    }

    @Test
    void monitorAllBranchesStreamsToParquet(@TempDir Path dir) throws IOException {
        Network network = EurostagTutorialExample1Factory.create();
        List<Contingency> contingencies = List.of(new Contingency("NHV1_NHV2_1", new BranchContingency("NHV1_NHV2_1")));

        SecurityAnalysisParameters saParameters = new SecurityAnalysisParameters();
        saParameters.addExtension(OpenSecurityAnalysisParameters.class,
                new OpenSecurityAnalysisParameters().setMonitorAllBranches(true));

        ContingenciesProvider provider = n -> contingencies;
        SecurityAnalysisRunParameters runParameters = new SecurityAnalysisRunParameters()
                .setComputationManager(computationManager)
                .setSecurityAnalysisParameters(saParameters)
                .setResultWriterFactory(new ParquetSecurityAnalysisResultWriterFactory(dir));
        securityAnalysisProvider.run(network, network.getVariantManager().getWorkingVariantId(), provider, runParameters).join();

        List<Map<String, Object>> rows = readParquet(dir.resolve("part-0.parquet"));

        // 4 base-case branches + 3 remaining branches after the single-line contingency
        long baseCaseRows = rows.stream().filter(r -> String.valueOf(r.get("contingencyId")).isEmpty()).count();
        long contingencyRows = rows.stream().filter(r -> "NHV1_NHV2_1".equals(String.valueOf(r.get("contingencyId")))).count();
        assertEquals(4, baseCaseRows);
        assertEquals(3, contingencyRows);

        // sanity: a known branch has a non-zero active power in the base case
        Map<String, Object> transfoBaseCase = rows.stream()
                .filter(r -> String.valueOf(r.get("contingencyId")).isEmpty() && "NGEN_NHV1".equals(String.valueOf(r.get("branchId"))))
                .findFirst().orElseThrow();
        assertTrue(Math.abs((double) transfoBaseCase.get("p1")) > 0.0);
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
