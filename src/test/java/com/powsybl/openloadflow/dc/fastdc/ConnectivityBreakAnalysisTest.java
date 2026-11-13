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
import com.powsybl.iidm.network.test.EurostagTutorialExample1Factory;
import com.powsybl.openloadflow.dc.DcLoadFlowContext;
import com.powsybl.openloadflow.dc.DcLoadFlowEngine;
import com.powsybl.openloadflow.dc.DcLoadFlowParameters;
import com.powsybl.openloadflow.network.EurostagFactory;
import com.powsybl.openloadflow.network.LfBus;
import com.powsybl.openloadflow.network.LfNetwork;
import com.powsybl.openloadflow.network.LfTopoConfig;
import com.powsybl.openloadflow.network.impl.LfNetworkLoaderImpl;
import com.powsybl.openloadflow.network.impl.PropagatedContingency;
import com.powsybl.openloadflow.network.impl.PropagatedContingencyCreationParameters;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * @author Gautier Bureau {@literal <gautier.bureau at rte-france.com>}
 */
class ConnectivityBreakAnalysisTest {

    /**
     * Without the slack bus's connected component, the analysis finds the same breaking contingencies and the same
     * disabled buses; only that component is left out.
     */
    @Test
    void slackConnectedComponentOnlyWhenAsked() {
        Network network = EurostagFactory.fix(EurostagTutorialExample1Factory.create());
        List<Contingency> contingencies = network.getBranchStream()
                .map(b -> new Contingency(b.getId(), new BranchContingency(b.getId())))
                .toList();
        DcLoadFlowParameters dcParameters = new DcLoadFlowParameters();
        LfNetwork lfNetwork = LfNetwork.load(network, new LfNetworkLoaderImpl(), dcParameters.getNetworkParameters()).getFirst();
        List<PropagatedContingency> propagatedContingencies = PropagatedContingency.createList(network, contingencies, new LfTopoConfig(),
                new PropagatedContingencyCreationParameters());
        try (DcLoadFlowContext context = new DcLoadFlowContext(lfNetwork, dcParameters)) {
            new DcLoadFlowEngine(context).run();
            var with = ConnectivityBreakAnalysis.run(context, propagatedContingencies, Collections.emptyList(), true);
            var without = ConnectivityBreakAnalysis.run(context, propagatedContingencies, Collections.emptyList(), false);

            assertFalse(with.connectivityBreakingAnalysisResults().isEmpty(), "a contingency breaks connectivity");
            assertEquals(with.connectivityBreakingAnalysisResults().size(), without.connectivityBreakingAnalysisResults().size());
            assertEquals(with.nonBreakingConnectivityAnalysisResults().size(), without.nonBreakingConnectivityAnalysisResults().size());
            for (int i = 0; i < with.connectivityBreakingAnalysisResults().size(); i++) {
                var w = with.connectivityBreakingAnalysisResults().get(i);
                var wo = without.connectivityBreakingAnalysisResults().get(i);
                assertEquals(w.getPropagatedContingency().getContingency().getId(), wo.getPropagatedContingency().getContingency().getId());
                assertEquals(ids(w.getDisabledBuses()), ids(wo.getDisabledBuses()));
                assertNotNull(w.getSlackConnectedComponent());
                assertFalse(w.getSlackConnectedComponent().isEmpty());
                assertNull(wo.getSlackConnectedComponent());
            }
        }
    }

    private static Set<String> ids(Set<LfBus> buses) {
        return buses.stream().map(LfBus::getId).collect(Collectors.toSet());
    }
}
