/*
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.sa;

import com.powsybl.commons.report.ReportNode;
import com.powsybl.contingency.BranchContingency;
import com.powsybl.contingency.Contingency;
import com.powsybl.iidm.network.Network;
import com.powsybl.loadflow.LoadFlowParameters;
import com.powsybl.openloadflow.CommonTestConfig;
import com.powsybl.openloadflow.OpenLoadFlowParameters;
import com.powsybl.openloadflow.network.NodeBreakerNetworkFactory;
import com.powsybl.security.SecurityAnalysisParameters;
import com.powsybl.security.SecurityAnalysisResult;
import com.powsybl.security.monitor.StateMonitor;
import com.powsybl.security.results.BranchResult;
import com.powsybl.security.results.NetworkResult;
import com.powsybl.security.results.PostContingencyResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * With alternative equations, contingencies are classified before the simulation loop so that the ones preserving the
 * matrix structure run first. Classifying a contingency means building its {@code LfContingency}, which is not free of
 * side effects: a contingency isolating the slack bus relocates it, and that relocation outlives the call. Running the
 * classification up front must therefore not leak anything into the first contingency simulated.
 *
 * <p>Here the last contingency (L2) isolates the slack bus, so before the fix the first contingency (L1) was simulated
 * with a relocated slack bus and converged to a different, wrong flow.
 *
 * <p>Results must be identical to the legacy modeling.
 *
 * @author Gautier Bureau {@literal <gautier.bureau at gmail.com>}
 */
class AlternativeEquationsSlackRelocationOrderingTest extends AbstractOpenSecurityAnalysisTest {

    AlternativeEquationsSlackRelocationOrderingTest(CommonTestConfig commonTestConfig) {
        super(commonTestConfig);
    }

    private SecurityAnalysisResult run(boolean alternativeEquations) {
        Network network = NodeBreakerNetworkFactory.create3Bars();
        network.getSwitch("C1").setRetained(true);
        network.getSwitch("C2").setRetained(true);
        network.getLineStream().forEach(line -> {
            if (line.getCurrentLimits1().isPresent()) {
                line.getCurrentLimits1().orElseThrow().setPermanentLimit(310);
            }
            if (line.getCurrentLimits2().isPresent()) {
                line.getCurrentLimits2().orElseThrow().setPermanentLimit(310);
            }
        });

        // L2, the contingency isolating the slack bus, is deliberately not the first one simulated
        List<Contingency> contingencies = Stream.of("L1", "L3", "L2")
                .map(id -> new Contingency(id, new BranchContingency(id)))
                .toList();
        List<StateMonitor> monitors = createAllBranchesMonitors(network);

        LoadFlowParameters lfParameters = new LoadFlowParameters();
        lfParameters.setDistributedSlack(false);
        setSlackBusId(lfParameters, "VL2_0");
        OpenLoadFlowParameters.create(lfParameters)
                .setAlternativeEquations(alternativeEquations);
        SecurityAnalysisParameters saParameters = new SecurityAnalysisParameters();
        saParameters.setLoadFlowParameters(lfParameters);

        return runSecurityAnalysis(network, contingencies, monitors, saParameters, List.of(), List.of(), ReportNode.NO_OP);
    }

    @Test
    void contingencyClassificationDoesNotRelocateSlackBusTest() {
        SecurityAnalysisResult legacyResult = run(false);
        SecurityAnalysisResult result = run(true);

        assertEquals(legacyResult.getPreContingencyResult().getStatus(), result.getPreContingencyResult().getStatus());
        compareNetworkResults(legacyResult.getPreContingencyResult().getNetworkResult(), result.getPreContingencyResult().getNetworkResult());

        assertEquals(legacyResult.getPostContingencyResults().size(), result.getPostContingencyResults().size());
        for (int i = 0; i < legacyResult.getPostContingencyResults().size(); i++) {
            PostContingencyResult legacyPostContingencyResult = legacyResult.getPostContingencyResults().get(i);
            PostContingencyResult postContingencyResult = result.getPostContingencyResults().get(i);
            assertEquals(legacyPostContingencyResult.getContingency().getId(), postContingencyResult.getContingency().getId());
            assertEquals(legacyPostContingencyResult.getStatus(), postContingencyResult.getStatus());
            compareNetworkResults(legacyPostContingencyResult.getNetworkResult(), postContingencyResult.getNetworkResult());
        }
    }

    private static void compareNetworkResults(NetworkResult legacyNetworkResult, NetworkResult networkResult) {
        assertEquals(legacyNetworkResult.getBranchResults().size(), networkResult.getBranchResults().size());
        for (BranchResult legacyBranchResult : legacyNetworkResult.getBranchResults()) {
            BranchResult branchResult = networkResult.getBranchResult(legacyBranchResult.getBranchId());
            assertEquals(legacyBranchResult.getP1(), branchResult.getP1(), 1e-2, "p1 mismatch on branch " + legacyBranchResult.getBranchId());
            assertEquals(legacyBranchResult.getQ1(), branchResult.getQ1(), 1e-2, "q1 mismatch on branch " + legacyBranchResult.getBranchId());
            assertEquals(legacyBranchResult.getP2(), branchResult.getP2(), 1e-2, "p2 mismatch on branch " + legacyBranchResult.getBranchId());
            assertEquals(legacyBranchResult.getQ2(), branchResult.getQ2(), 1e-2, "q2 mismatch on branch " + legacyBranchResult.getBranchId());
        }
    }
}
