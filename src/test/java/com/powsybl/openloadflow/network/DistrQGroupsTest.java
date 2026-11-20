/*
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.network;

import com.powsybl.iidm.network.Network;
import com.powsybl.loadflow.LoadFlowParameters;
import com.powsybl.math.matrix.DenseMatrixFactory;
import com.powsybl.openloadflow.OpenLoadFlowParameters;
import com.powsybl.openloadflow.ac.AcLoadFlowContext;
import com.powsybl.openloadflow.ac.AcLoadFlowParameters;
import com.powsybl.openloadflow.ac.AcloadFlowEngine;
import com.powsybl.openloadflow.graph.EvenShiloachGraphDecrementalConnectivityFactory;
import com.powsybl.openloadflow.network.impl.Networks;
import org.junit.jupiter.api.Test;

import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The remote voltage control groups as tables ({@link DistrQGroups}): their shares, computed on the tables from the
 * active members, are OLF's reactive keys to the last bit, before and after members switch to PQ.
 *
 * @author Gautier Bureau {@literal <gautier.bureau at rte-france.com>}
 */
class DistrQGroupsTest {

    /** Runs a load flow with reactive limits on the network with three generators controlling one bus remotely. */
    private static void runLoadFlow(Consumer<Network> change, Consumer<LfNetwork> afterwards) {
        Network network = VoltageControlNetworkFactory.createWithGeneratorRemoteControl();
        change.accept(network);
        LoadFlowParameters parameters = new LoadFlowParameters().setUseReactiveLimits(true).setDistributedSlack(false);
        OpenLoadFlowParameters.create(parameters).setSlackBusSelectionMode(SlackBusSelectionMode.MOST_MESHED).setVoltageRemoteControl(true);
        AcLoadFlowParameters acParameters = OpenLoadFlowParameters.createAcParameters(network, parameters, OpenLoadFlowParameters.get(parameters),
                new DenseMatrixFactory(), new EvenShiloachGraphDecrementalConnectivityFactory<>(), false, false);
        LfNetwork lfNetwork = Networks.load(network, acParameters.getNetworkParameters()).get(0);
        try (AcLoadFlowContext context = new AcLoadFlowContext(lfNetwork, acParameters)) {
            assertTrue(new AcloadFlowEngine(context).run().isSuccess());
            afterwards.accept(lfNetwork);
        }
    }

    /** The shares computed on the tables, against the keys OLF holds on the buses. */
    private static void assertSharesAreOlfs(LfNetwork network, DistrQGroups groups) {
        double[] shares = groups.distribute(groups.getActiveMembers());
        for (int m = 0; m < groups.getMembers().length; m++) {
            LfBus bus = network.getBus(groups.getMembers()[m]);
            assertEquals(Double.doubleToLongBits(bus.getRemoteControlReactivePercent()), Double.doubleToLongBits(shares[m]), bus.getId());
        }
    }

    @Test
    void allMembersControl() {
        runLoadFlow(n -> { }, network -> {
            DistrQGroups groups = DistrQGroups.of(network);
            assertEquals(1, groups.getGroupCount());
            assertEquals(3, groups.getMembers().length);
            assertArrayEquals(new int[] {0, 3}, groups.getGroupStart());
            boolean[] active = groups.getActiveMembers();
            assertTrue(active[0] && active[1] && active[2]);
            assertSharesAreOlfs(network, groups);
        });
    }

    @Test
    void oneMemberAtItsLimit() {
        runLoadFlow(n -> n.getGenerator("g1").newMinMaxReactiveLimits().setMinQ(-50).setMaxQ(50).add(), network -> {
            DistrQGroups groups = DistrQGroups.of(network);
            boolean[] active = groups.getActiveMembers();
            int inactive = 0;
            for (boolean a : active) {
                inactive += a ? 0 : 1;
            }
            assertEquals(1, inactive); // g1 switched to PQ
            assertSharesAreOlfs(network, groups);
            double[] shares = groups.distribute(active);
            double sum = 0;
            for (int m = 0; m < shares.length; m++) {
                assertEquals(active[m], shares[m] > 0);
                sum += shares[m];
            }
            assertEquals(1, sum, 1e-15);
        });
    }

    @Test
    void sharesFollowTheActiveMembers() {
        runLoadFlow(n -> { }, network -> {
            DistrQGroups groups = DistrQGroups.of(network);
            // the second member leaves the control: OLF's keys on the buses, and the tables, give it nothing
            LfBus second = network.getBus(groups.getMembers()[1]);
            second.setGeneratorVoltageControlEnabledAndRecomputeTargetQ(false);
            second.getGeneratorVoltageControl().orElseThrow().<GeneratorVoltageControl>getMainVoltageControl().updateReactiveKeys();
            assertFalse(groups.getActiveMembers()[1]);
            assertSharesAreOlfs(network, groups);
            assertEquals(0, second.getRemoteControlReactivePercent());
        });
    }

    @Test
    void generatorsWithoutRemoteKeys() {
        // no coordinated reactive control extension: the keys come from the reactive power ranges
        Consumer<Network> ranges = n -> n.getGeneratorStream()
                .forEach(g -> g.newMinMaxReactiveLimits().setMinQ(-100 * (1 + g.getId().charAt(1) - '1')).setMaxQ(100 * (1 + g.getId().charAt(1) - '1')).add());
        runLoadFlow(ranges, network -> {
            DistrQGroups groups = DistrQGroups.of(network);
            assertSharesAreOlfs(network, groups);
            assertNotEquals(groups.getRawKeys()[0], groups.getRawKeys()[2]);
        });
    }
}
