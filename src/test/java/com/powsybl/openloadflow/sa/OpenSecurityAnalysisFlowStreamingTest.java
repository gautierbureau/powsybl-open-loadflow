/**
 * Copyright (c) 2025, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.sa;

import com.powsybl.commons.PowsyblException;
import com.powsybl.contingency.BranchContingency;
import com.powsybl.contingency.ContingenciesProvider;
import com.powsybl.contingency.Contingency;
import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.test.EurostagTutorialExample1Factory;
import com.powsybl.openloadflow.CommonTestConfig;
import com.powsybl.security.SecurityAnalysisParameters;
import com.powsybl.security.SecurityAnalysisReport;
import com.powsybl.security.SecurityAnalysisResult;
import com.powsybl.security.SecurityAnalysisRunParameters;
import com.powsybl.security.results.BranchResult;
import com.powsybl.security.results.PostContingencyResult;
import com.powsybl.security.writer.CsvSecurityAnalysisResultWriter;
import com.powsybl.security.writer.CsvSecurityAnalysisResultWriterFactory;
import com.powsybl.security.writer.SecurityAnalysisResultWriterFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Prototype tests for streaming all branch flows out of a security analysis (see
 * {@code design/ac-security-analysis-flow-streaming.md}): the vectorized monitor-all path, the in-memory bypass,
 * value correctness versus the state-monitor path, and lock-free per-partition output.
 *
 * @author (design proposal)
 */
class OpenSecurityAnalysisFlowStreamingTest extends AbstractOpenSecurityAnalysisTest {

    OpenSecurityAnalysisFlowStreamingTest(CommonTestConfig commonTestConfig) {
        super(commonTestConfig);
    }

    private static SecurityAnalysisParameters monitorAllParameters() {
        SecurityAnalysisParameters saParameters = new SecurityAnalysisParameters();
        saParameters.addExtension(OpenSecurityAnalysisParameters.class,
                new OpenSecurityAnalysisParameters().setMonitorAllBranches(true));
        return saParameters;
    }

    private SecurityAnalysisResult run(Network network, List<Contingency> contingencies,
                                       SecurityAnalysisParameters saParameters, SecurityAnalysisResultWriterFactory writerFactory) {
        ContingenciesProvider provider = n -> contingencies;
        SecurityAnalysisRunParameters runParameters = new SecurityAnalysisRunParameters()
                .setComputationManager(computationManager)
                .setSecurityAnalysisParameters(saParameters)
                .setResultWriterFactory(writerFactory);
        SecurityAnalysisReport report = securityAnalysisProvider.run(network,
                network.getVariantManager().getWorkingVariantId(), provider, runParameters).join();
        return report.getResult();
    }

    @Test
    void monitorAllBranchesStreamsToCsvAndBypassesMemory() {
        Network network = EurostagTutorialExample1Factory.create();
        List<Contingency> contingencies = List.of(
                new Contingency("NHV1_NHV2_1", new BranchContingency("NHV1_NHV2_1")),
                new Contingency("NHV1_NHV2_2", new BranchContingency("NHV1_NHV2_2")));

        StringWriter csv = new StringWriter();
        SecurityAnalysisResult result = run(network, contingencies, monitorAllParameters(),
                partitionIndex -> new CsvSecurityAnalysisResultWriter(csv));

        String content = csv.toString();
        List<String> lines = content.strip().lines().toList();

        assertEquals("contingencyId;status;branchId;p1;q1;i1;p2;q2;i2;flowTransfer", lines.get(0).strip());

        // base case: every one of the 4 branches reported with an empty contingency id
        long baseCaseRows = lines.stream().skip(1).filter(l -> l.startsWith(";CONVERGED;")).count();
        assertEquals(4, baseCaseRows);

        // each contingency streamed its (remaining, connected) branches
        assertTrue(content.contains("NHV1_NHV2_1;CONVERGED;"));
        assertTrue(content.contains("NHV1_NHV2_2;CONVERGED;"));

        // in-memory bypass: the streamed post-contingency network results are empty
        for (PostContingencyResult postContingencyResult : result.getPostContingencyResults()) {
            assertTrue(postContingencyResult.getNetworkResult().getBranchResults().isEmpty());
        }
    }

    @Test
    void streamedBaseCaseValuesMatchStateMonitorPath() {
        Network network = EurostagTutorialExample1Factory.create();

        // reference: the existing state-monitor path (all branches), values kept in memory
        SecurityAnalysisResult reference = runSecurityAnalysis(network, List.of(), createAllBranchesMonitors(network));
        Map<String, Double> referenceP1 = new HashMap<>();
        for (BranchResult branchResult : reference.getPreContingencyResult().getNetworkResult().getBranchResults()) {
            referenceP1.put(branchResult.getBranchId(), branchResult.getP1());
        }

        // vectorized monitor-all path, streamed to CSV
        StringWriter csv = new StringWriter();
        run(network, List.of(), monitorAllParameters(), partitionIndex -> new CsvSecurityAnalysisResultWriter(csv));

        Map<String, Double> streamedP1 = new HashMap<>();
        csv.toString().strip().lines().skip(1).forEach(line -> {
            String[] c = line.split(";");
            streamedP1.put(c[2], Double.parseDouble(c[3]));
        });

        assertEquals(referenceP1.keySet(), streamedP1.keySet());
        referenceP1.forEach((branchId, p1) -> assertEquals(p1, streamedP1.get(branchId), 1e-3,
                "p1 mismatch on branch " + branchId));
    }

    @Test
    void multiThreadedRunWritesOneLockFreePartFilePerPartition(@TempDir Path dir) throws IOException {
        Network network = EurostagTutorialExample1Factory.create();
        List<Contingency> contingencies = List.of(
                new Contingency("NHV1_NHV2_1", new BranchContingency("NHV1_NHV2_1")),
                new Contingency("NHV1_NHV2_2", new BranchContingency("NHV1_NHV2_2")));

        SecurityAnalysisParameters saParameters = monitorAllParameters();
        saParameters.getExtension(OpenSecurityAnalysisParameters.class).setThreadCount(2);

        run(network, contingencies, saParameters, new CsvSecurityAnalysisResultWriterFactory(dir));

        Path part0 = dir.resolve("part-0.csv");
        Path part1 = dir.resolve("part-1.csv");
        assertTrue(Files.exists(part0));
        assertTrue(Files.exists(part1));

        // the base case is streamed by partition 0 only (no duplication across partitions)
        assertTrue(readRows(part0).stream().anyMatch(l -> l.startsWith(";CONVERGED;")));
        assertFalse(readRows(part1).stream().anyMatch(l -> l.startsWith(";CONVERGED;")));

        // both contingencies were streamed, one per partition
        String all = readRows(part0).toString() + readRows(part1);
        assertTrue(all.contains("NHV1_NHV2_1;CONVERGED;"));
        assertTrue(all.contains("NHV1_NHV2_2;CONVERGED;"));
    }

    @Test
    void monitorAllBranchesWithoutWriterFactoryThrows() {
        Network network = EurostagTutorialExample1Factory.create();
        PowsyblException e = assertThrows(PowsyblException.class,
                () -> run(network, List.of(), monitorAllParameters(), SecurityAnalysisResultWriterFactory.NO_OP));
        assertTrue(e.getMessage().contains("monitorAllBranches requires a result writer factory"));
    }

    private static List<String> readRows(Path file) {
        try {
            // drop the CSV header
            return Files.readAllLines(file).stream().skip(1).toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
