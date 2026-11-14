/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.dc.fastdc;

import com.powsybl.contingency.BranchContingency;
import com.powsybl.contingency.Contingency;
import com.powsybl.iidm.network.Network;
import com.powsybl.openloadflow.dc.DcLoadFlowContext;
import com.powsybl.openloadflow.dc.DcLoadFlowParameters;
import com.powsybl.openloadflow.network.ConnectedComponentNetworkFactory;
import com.powsybl.openloadflow.network.LfBus;
import com.powsybl.openloadflow.network.LfNetwork;
import com.powsybl.openloadflow.network.LfTopoConfig;
import com.powsybl.openloadflow.network.impl.LfNetworkLoaderImpl;
import com.powsybl.openloadflow.network.impl.PropagatedContingency;
import com.powsybl.openloadflow.network.impl.PropagatedContingencyCreationParameters;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The connectivity analysis entry points, for a caller that selects the potentially breaking contingencies itself.
 *
 * @author Gautier Bureau {@literal <gautier.bureau at rte-france.com>}
 */
class ConnectivityBreakAnalysisTest {

    @Test
    void connectivityAnalysisOfOneContingency() {
        // two triangles linked by l34: losing l34 splits the network, losing l12 does not
        Network network = ConnectedComponentNetworkFactory.createTwoCcLinkedByASingleLine();
        List<PropagatedContingency> contingencies = PropagatedContingency.createList(network,
                List.of(new Contingency("l34", new BranchContingency("l34")), new Contingency("l12", new BranchContingency("l12"))),
                new LfTopoConfig(), new PropagatedContingencyCreationParameters());
        DcLoadFlowParameters dcParameters = new DcLoadFlowParameters();
        LfNetwork lfNetwork = LfNetwork.load(network, new LfNetworkLoaderImpl(), dcParameters.getNetworkParameters()).getFirst();
        try (DcLoadFlowContext context = new DcLoadFlowContext(lfNetwork, dcParameters)) {
            Map<String, ComputedContingencyElement> elementByBranch = ConnectivityBreakAnalysis.createContingencyElementsIndexByBranchId(
                    contingencies, lfNetwork, context.getEquationSystem(), dcParameters.getEquationSystemCreationParameters());
            assertEquals(Set.of("l34", "l12"), elementByBranch.keySet());

            ConnectivityBreakAnalysis.ConnectivityAnalysisResult withComponent = ConnectivityBreakAnalysis.computeConnectivityAnalysisResult(
                    lfNetwork, contingencies.get(0), elementByBranch, null, Collections.emptyMap(), true, false).orElseThrow();
            ConnectivityBreakAnalysis.ConnectivityAnalysisResult withoutComponent = ConnectivityBreakAnalysis.computeConnectivityAnalysisResult(
                    lfNetwork, contingencies.get(0), elementByBranch, null, Collections.emptyMap(), false, false).orElseThrow();

            // the same analysis, with or without the slack bus's component
            assertEquals(3, withComponent.getDisabledBuses().size());
            assertEquals(ids(withComponent.getDisabledBuses()), ids(withoutComponent.getDisabledBuses()));
            assertEquals(withComponent.getElementsToReconnect(), withoutComponent.getElementsToReconnect());
            assertEquals(3, withComponent.getSlackConnectedComponent().size());
            assertNull(withoutComponent.getSlackConnectedComponent());

            assertTrue(ConnectivityBreakAnalysis.computeConnectivityAnalysisResult(
                    lfNetwork, contingencies.get(1), elementByBranch, null, Collections.emptyMap(), false, false).isEmpty());
        }
    }

    private static Set<String> ids(Set<LfBus> buses) {
        return buses.stream().map(LfBus::getId).collect(Collectors.toSet());
    }
}
