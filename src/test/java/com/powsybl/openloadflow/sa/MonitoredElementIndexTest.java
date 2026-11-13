/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.sa;

import com.powsybl.contingency.ContingencyContext;
import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.test.EurostagTutorialExample1Factory;
import com.powsybl.openloadflow.network.FirstSlackBusSelector;
import com.powsybl.openloadflow.network.LfBranch;
import com.powsybl.openloadflow.network.LfBus;
import com.powsybl.openloadflow.network.LfNetwork;
import com.powsybl.openloadflow.network.impl.LfNetworkLoaderImpl;
import com.powsybl.security.monitor.StateMonitor;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * @author Gautier Bureau {@literal <gautier.bureau at rte-france.com>}
 */
class MonitoredElementIndexTest {

    @Test
    void selectsInNetworkOrderAndSearchesOnce() {
        Network network = EurostagTutorialExample1Factory.create();
        LfNetwork lfNetwork = LfNetwork.load(network, new LfNetworkLoaderImpl(), new FirstSlackBusSelector()).get(0);
        // the monitor lists the ids out of network order, plus ids the network does not have
        StateMonitor monitor = new StateMonitor(ContingencyContext.all(), Set.of("NHV1_NHV2_2", "NGEN_NHV1", "unknown"),
                Set.of("VLLOAD", "VLGEN", "unknownVoltageLevel"), Set.of());
        MonitoredElementIndex index = new MonitoredElementIndex();

        List<LfBranch> branches = index.getBranches(lfNetwork, monitor);
        List<String> expectedBranches = lfNetwork.getBranches().stream()
                .filter(b -> b.getOriginalIds().stream().anyMatch(monitor.getBranchIds()::contains))
                .map(LfBranch::getId)
                .toList();
        assertEquals(expectedBranches, branches.stream().map(LfBranch::getId).toList());
        assertEquals(2, branches.size());

        List<LfBus> buses = index.getBuses(lfNetwork, monitor);
        List<String> expectedBuses = lfNetwork.getBuses().stream()
                .filter(b -> monitor.getVoltageLevelIds().contains(b.getVoltageLevelId()))
                .map(LfBus::getId)
                .toList();
        assertEquals(expectedBuses, buses.stream().map(LfBus::getId).toList());
        assertEquals(2, buses.size());

        // searched once per network and monitor
        assertSame(branches, index.getBranches(lfNetwork, monitor));
        assertSame(buses, index.getBuses(lfNetwork, monitor));

        // a monitor without branches or voltage levels selects nothing
        StateMonitor empty = new StateMonitor(ContingencyContext.all(), Set.of(), Set.of(), Set.of());
        assertTrue(index.getBranches(lfNetwork, empty).isEmpty());
        assertTrue(index.getBuses(lfNetwork, empty).isEmpty());
    }
}
