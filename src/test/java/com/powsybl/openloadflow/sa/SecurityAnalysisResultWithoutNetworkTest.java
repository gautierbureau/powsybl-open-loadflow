/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.sa;

import com.powsybl.commons.report.ReportNode;
import com.powsybl.computation.local.LocalComputationManager;
import com.powsybl.contingency.BranchContingency;
import com.powsybl.contingency.Contingency;
import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.test.EurostagTutorialExample1Factory;
import com.powsybl.math.matrix.DenseMatrixFactory;
import com.powsybl.openloadflow.ac.AcLoadFlowContext;
import com.powsybl.openloadflow.graph.EvenShiloachGraphDecrementalConnectivityFactory;
import com.powsybl.openloadflow.network.LfContingency;
import com.powsybl.openloadflow.network.LfNetwork;
import com.powsybl.openloadflow.network.NetworkState;
import com.powsybl.openloadflow.network.impl.PropagatedContingency;
import com.powsybl.security.LimitViolationsResult;
import com.powsybl.security.PostContingencyComputationStatus;
import com.powsybl.security.SecurityAnalysisParameters;
import com.powsybl.security.SecurityAnalysisResult;
import com.powsybl.security.limitscaling.LimitScaling;
import com.powsybl.security.results.ConnectivityResult;
import com.powsybl.security.results.NetworkResult;
import com.powsybl.security.results.PostContingencyResult;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A security analysis subclass answers a contingency from a result it computed elsewhere, without the network.
 *
 * @author Gautier Bureau {@literal <gautier.bureau at rte-france.com>}
 */
class SecurityAnalysisResultWithoutNetworkTest {

    private static final String ANSWERED = "NHV1_NHV2_1";

    private static final String SIMULATED = "NHV1_NHV2_2";

    @Test
    void subclassAnswersAContingencyWithoutTheNetwork() throws Exception {
        Network network = EurostagTutorialExample1Factory.create();
        List<String> asked = new ArrayList<>();
        List<Boolean> answeredBranchDisabled = new ArrayList<>();
        AcSecurityAnalysis securityAnalysis = new AcSecurityAnalysis(network, new DenseMatrixFactory(),
                new EvenShiloachGraphDecrementalConnectivityFactory<>(), Collections.emptyList(), ReportNode.NO_OP) {
            @Override
            protected Optional<PostContingencyResult> resultWithoutNetwork(LfNetwork lfNetwork, AcLoadFlowContext context,
                                                                           PropagatedContingency propagatedContingency,
                                                                           LfContingency lfContingency, NetworkState networkState,
                                                                           LimitViolationManager preContingencyLimitViolationManager,
                                                                           SecurityAnalysisParameters securityAnalysisParameters,
                                                                           PreContingencyNetworkResult preContingencyNetworkResult,
                                                                           boolean createResultExtension, List<LimitScaling> limitScalings,
                                                                           ReportNode reportNode) {
                asked.add(lfContingency.getId());
                if (!ANSWERED.equals(lfContingency.getId())) {
                    return Optional.empty();
                }
                answeredBranchDisabled.add(lfNetwork.getBranchById(ANSWERED).isDisabled());
                return Optional.of(new PostContingencyResult(propagatedContingency.getContingency(), PostContingencyComputationStatus.MAX_ITERATION_REACHED,
                        new LimitViolationsResult(List.of()), new NetworkResult(List.of(), List.of(), List.of()),
                        new ConnectivityResult(0, 0, 0, 0, Set.of()), 0, List.of()));
            }
        };

        List<Contingency> contingencies = List.of(new Contingency(ANSWERED, new BranchContingency(ANSWERED)),
                                                  new Contingency(SIMULATED, new BranchContingency(SIMULATED)));
        SecurityAnalysisResult result = securityAnalysis.run(network.getVariantManager().getWorkingVariantId(), new SecurityAnalysisParameters(),
                n -> contingencies, LocalComputationManager.getDefault(), List.of(), List.of(), List.of()).get().getResult();

        // every contingency without operator strategies is offered to the subclass, before anything is applied
        assertEquals(List.of(ANSWERED, SIMULATED), asked);
        assertEquals(List.of(false), answeredBranchDisabled);

        // the answered contingency gets the subclass's result, the other one is simulated as usual
        Map<String, PostContingencyResult> resultById = new HashMap<>();
        result.getPostContingencyResults().forEach(r -> resultById.put(r.getContingency().getId(), r));
        assertEquals(PostContingencyComputationStatus.MAX_ITERATION_REACHED, resultById.get(ANSWERED).getStatus());
        assertEquals(PostContingencyComputationStatus.CONVERGED, resultById.get(SIMULATED).getStatus());
    }
}
