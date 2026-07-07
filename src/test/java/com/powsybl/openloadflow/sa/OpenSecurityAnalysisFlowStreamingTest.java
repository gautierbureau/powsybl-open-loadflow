/**
 * Copyright (c) 2025, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.sa;

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
import com.powsybl.security.results.PostContingencyResult;
import com.powsybl.security.writer.CsvSecurityAnalysisResultWriter;
import org.junit.jupiter.api.Test;

import java.io.StringWriter;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Prototype test for streaming all branch flows out of an AC security analysis (see
 * {@code design/ac-security-analysis-flow-streaming.md}).
 *
 * @author (design proposal)
 */
class OpenSecurityAnalysisFlowStreamingTest extends AbstractOpenSecurityAnalysisTest {

    OpenSecurityAnalysisFlowStreamingTest(CommonTestConfig commonTestConfig) {
        super(commonTestConfig);
    }

    private SecurityAnalysisResult run(Network network, List<Contingency> contingencies,
                                       SecurityAnalysisParameters saParameters, StringWriter csv) {
        ContingenciesProvider provider = n -> contingencies;
        SecurityAnalysisRunParameters runParameters = new SecurityAnalysisRunParameters()
                .setComputationManager(computationManager)
                .setSecurityAnalysisParameters(saParameters)
                .setResultWriter(new CsvSecurityAnalysisResultWriter(csv));
        SecurityAnalysisReport report = securityAnalysisProvider.run(network,
                network.getVariantManager().getWorkingVariantId(), provider, runParameters).join();
        return report.getResult();
    }

    @Test
    void monitorAllBranchesStreamsToCsv() {
        Network network = EurostagTutorialExample1Factory.create();
        List<Contingency> contingencies = List.of(
                new Contingency("NHV1_NHV2_1", new BranchContingency("NHV1_NHV2_1")),
                new Contingency("NHV1_NHV2_2", new BranchContingency("NHV1_NHV2_2")));

        SecurityAnalysisParameters saParameters = new SecurityAnalysisParameters();
        saParameters.addExtension(OpenSecurityAnalysisParameters.class,
                new OpenSecurityAnalysisParameters().setMonitorAllBranches(true));

        StringWriter csv = new StringWriter();
        SecurityAnalysisResult result = run(network, contingencies, saParameters, csv);

        String content = csv.toString();
        String[] lines = content.strip().split("\n");

        // header + base case (4 branches) + 2 contingencies x (branches still connected)
        assertEquals("contingencyId;status;branchId;p1;q1;i1;p2;q2;i2;flowTransfer", lines[0].strip());

        // base case: every branch reported (4 branches in the Eurostag network)
        long baseCaseRows = List.of(lines).stream().skip(1).filter(l -> l.startsWith(";CONVERGED;")).count();
        assertEquals(4, baseCaseRows);

        // each contingency produced at least one monitored branch row
        assertTrue(content.contains("NHV1_NHV2_1;CONVERGED;"));
        assertTrue(content.contains("NHV1_NHV2_2;CONVERGED;"));

        // in-memory bypass: the post-contingency network results are emptied (flows went to the CSV)
        for (PostContingencyResult postContingencyResult : result.getPostContingencyResults()) {
            assertTrue(postContingencyResult.getNetworkResult().getBranchResults().isEmpty(),
                    "post-contingency branch results should be streamed out, not kept in memory");
        }
        // ... but the pre-contingency result is still available in memory as the loop baseline
        assertFalse(result.getPreContingencyResult().getNetworkResult().getBranchResults().isEmpty());
    }
}
