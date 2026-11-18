/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.sa;

import com.powsybl.action.Action;
import com.powsybl.action.LoadActionBuilder;
import com.powsybl.commons.report.ReportNode;
import com.powsybl.computation.local.LocalComputationManager;
import com.powsybl.contingency.BranchContingency;
import com.powsybl.contingency.Contingency;
import com.powsybl.contingency.ContingencyContext;
import com.powsybl.contingency.strategy.OperatorStrategy;
import com.powsybl.contingency.strategy.condition.TrueCondition;
import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.test.EurostagTutorialExample1Factory;
import com.powsybl.math.matrix.DenseMatrixFactory;
import com.powsybl.openloadflow.graph.dtree.DTreeGraphConnectivityFactory;
import com.powsybl.openloadflow.network.LfContingency;
import com.powsybl.openloadflow.network.LfNetwork;
import com.powsybl.openloadflow.network.NetworkState;
import com.powsybl.security.PostContingencyComputationStatus;
import com.powsybl.security.SecurityAnalysisParameters;
import com.powsybl.security.SecurityAnalysisResult;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A security analysis subclass is handed the restores between two contingencies and between two operator strategies.
 *
 * @author Gautier Bureau {@literal <gautier.bureau at rte-france.com>}
 */
class SecurityAnalysisRestoreHooksTest {

    private static SecurityAnalysisResult run(AcSecurityAnalysis securityAnalysis, Network network) throws Exception {
        List<Contingency> contingencies = List.of(new Contingency("NHV1_NHV2_1", new BranchContingency("NHV1_NHV2_1")),
                                                  new Contingency("NHV1_NHV2_2", new BranchContingency("NHV1_NHV2_2")));
        // two strategies for one contingency: OLF then restores the post-contingency state after each of them
        List<Action> actions = List.of(
                new LoadActionBuilder().withId("action1").withLoadId("LOAD").withRelativeValue(false).withActivePowerValue(500).build(),
                new LoadActionBuilder().withId("action2").withLoadId("LOAD").withRelativeValue(false).withActivePowerValue(550).build());
        List<OperatorStrategy> operatorStrategies = List.of(
                new OperatorStrategy("strategy1", ContingencyContext.specificContingency("NHV1_NHV2_1"), new TrueCondition(), List.of("action1")),
                new OperatorStrategy("strategy2", ContingencyContext.specificContingency("NHV1_NHV2_1"), new TrueCondition(), List.of("action2")));
        return securityAnalysis.run(network.getVariantManager().getWorkingVariantId(), new SecurityAnalysisParameters(),
                n -> contingencies, LocalComputationManager.getDefault(), operatorStrategies, actions, List.of()).get().getResult();
    }

    @Test
    void restoresGoThroughTheHooks() throws Exception {
        Network network = EurostagTutorialExample1Factory.create();
        List<String> restores = new ArrayList<>();
        AcSecurityAnalysis securityAnalysis = new AcSecurityAnalysis(network, new DenseMatrixFactory(),
                new DTreeGraphConnectivityFactory<>(), Collections.emptyList(), ReportNode.NO_OP) {
            @Override
            protected void restoreBaseState(NetworkState networkState, LfNetwork lfNetwork, LfContingency lfContingency) {
                restores.add("base after " + lfContingency.getId());
                super.restoreBaseState(networkState, lfNetwork, lfContingency);
            }

            @Override
            protected void restorePostContingencyState(NetworkState postContingencyNetworkState, LfNetwork lfNetwork,
                                                       LfContingency lfContingency) {
                restores.add("post-contingency after a strategy of " + lfContingency.getId());
                super.restorePostContingencyState(postContingencyNetworkState, lfNetwork, lfContingency);
            }
        };
        SecurityAnalysisResult result = run(securityAnalysis, network);

        // each strategy's simulation is undone, then the contingency's, and no restore follows the last contingency
        assertEquals(List.of("post-contingency after a strategy of NHV1_NHV2_1", "post-contingency after a strategy of NHV1_NHV2_1",
                "base after NHV1_NHV2_1"), restores);

        // the defaults are the full restores: the second contingency gets the default analysis' result
        Network referenceNetwork = EurostagTutorialExample1Factory.create();
        SecurityAnalysisResult reference = run(new AcSecurityAnalysis(referenceNetwork, new DenseMatrixFactory(),
                new DTreeGraphConnectivityFactory<>(), Collections.emptyList(), ReportNode.NO_OP), referenceNetwork);
        assertEquals(2, result.getOperatorStrategyResults().size());
        assertEquals(PostContingencyComputationStatus.CONVERGED, result.getPostContingencyResults().get(1).getStatus());
        assertEquals(reference.getPostContingencyResults().get(1).getLimitViolationsResult().getLimitViolations().size(),
                result.getPostContingencyResults().get(1).getLimitViolationsResult().getLimitViolations().size());
    }
}
