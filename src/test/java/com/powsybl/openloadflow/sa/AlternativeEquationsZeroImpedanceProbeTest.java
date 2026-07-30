/*
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.sa;

import com.powsybl.action.Action;
import com.powsybl.action.SwitchAction;
import com.powsybl.commons.report.ReportNode;
import com.powsybl.contingency.BranchContingency;
import com.powsybl.contingency.Contingency;
import com.powsybl.contingency.ContingencyContext;
import com.powsybl.contingency.strategy.OperatorStrategy;
import com.powsybl.contingency.strategy.condition.TrueCondition;
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
import com.powsybl.security.results.OperatorStrategyResult;
import com.powsybl.security.results.PostContingencyResult;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The zero impedance path of the alternative equations. Buses connected to a zero impedance branch are kept on the
 * legacy modeling, but that is not enough: on a node-breaker network whose couplers are retained, a plain branch
 * contingency already gives a post-contingency flow that differs from the legacy modeling, without any remedial action
 * and without the equation system becoming non square.
 *
 * <p>Results must be identical to the legacy modeling, whether that is reached natively or through a fallback.
 *
 * @author Gautier Bureau {@literal <gautier.bureau at gmail.com>}
 */
class AlternativeEquationsZeroImpedanceProbeTest extends AbstractOpenSecurityAnalysisTest {

    AlternativeEquationsZeroImpedanceProbeTest(CommonTestConfig commonTestConfig) {
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

        List<Contingency> contingencies = Stream.of("L1", "L3", "L2")
                .map(id -> new Contingency(id, new BranchContingency(id)))
                .toList();
        // closing a coupler adds a zero impedance branch to buses that had none when the equation system was created
        List<Action> actions = List.of(new SwitchAction("action1", "C1", false),
                new SwitchAction("action3", "C2", false));
        List<OperatorStrategy> operatorStrategies = List.of(
                new OperatorStrategy("strategyL1", ContingencyContext.specificContingency("L1"), new TrueCondition(), List.of("action1")),
                new OperatorStrategy("strategyL3", ContingencyContext.specificContingency("L3"), new TrueCondition(), List.of("action3")),
                new OperatorStrategy("strategyL2", ContingencyContext.specificContingency("L2"), new TrueCondition(), List.of("action1", "action3")));
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
    @Disabled("reproduces an open bug of the alternative equations on the zero impedance path: on a node-breaker "
            + "network whose couplers are retained (so modelled as zero impedance branches), a plain branch "
            + "contingency gives a post-contingency flow of 299.9997 MW on L2 against 301.8633 MW on the legacy "
            + "modeling. No remedial action and no operator strategy are needed, and the result is wrong silently: it "
            + "converges and the equation system stays square, so neither the non-square fallback nor any other check "
            + "detects it. Reproduced identically on the alternative equations branch alone, so it predates the "
            + "security analysis performance and fallback work. Enable once the zero impedance path is either "
            + "supported by the alternative modeling or excluded from it.")
    void zeroImpedanceContingencyGivesLegacyResultsTest() {
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

        // the operator strategies are what close the couplers, i.e. what adds the zero impedance branches
        assertEquals(legacyResult.getOperatorStrategyResults().size(), result.getOperatorStrategyResults().size());
        for (int i = 0; i < legacyResult.getOperatorStrategyResults().size(); i++) {
            OperatorStrategyResult legacyOperatorStrategyResult = legacyResult.getOperatorStrategyResults().get(i);
            OperatorStrategyResult operatorStrategyResult = result.getOperatorStrategyResults().get(i);
            assertEquals(legacyOperatorStrategyResult.getOperatorStrategy().getId(), operatorStrategyResult.getOperatorStrategy().getId());
            compareNetworkResults(legacyOperatorStrategyResult.getNetworkResult(), operatorStrategyResult.getNetworkResult());
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
