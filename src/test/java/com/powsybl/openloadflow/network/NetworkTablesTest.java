/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.network;

import com.powsybl.ieeecdf.converter.IeeeCdfNetworkFactory;
import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.TwoSides;
import com.powsybl.iidm.network.extensions.SecondaryVoltageControlAdder;
import com.powsybl.openloadflow.network.impl.Networks;
import com.powsybl.openloadflow.test.networks.AutomationSystemNetworkFactory;
import com.powsybl.openloadflow.test.networks.MultiAreaNetworkFactory;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The network tables built for engines working on arrays hold what the network objects hold, in their order.
 *
 * @author Gautier Bureau {@literal <gautier.bureau at rte-france.com>}
 */
class NetworkTablesTest {

    @Test
    void overloadManagementSystems() {
        Network network = AutomationSystemNetworkFactory.createWithBranchTripping();
        LfNetwork lfNetwork = Networks.load(network, new LfNetworkParameters().setSimulateAutomationSystems(true)).getFirst();
        OverloadManagementSystems systems = OverloadManagementSystems.of(lfNetwork);
        assertSame(systems, OverloadManagementSystems.of(lfNetwork));
        List<LfOverloadManagementSystem> expected = lfNetwork.getOverloadManagementSystems();
        assertFalse(expected.isEmpty());
        assertEquals(expected.size(), systems.count);
        for (int s = 0; s < systems.count; s++) {
            LfOverloadManagementSystem system = expected.get(s);
            assertEquals(system.getMonitoredBranch().getNum(), systems.monitoredBranchNum[s]);
            assertEquals(system.getMonitoredSide() == TwoSides.ONE ? 1 : 2, systems.monitoredSide[s]);
            List<LfOverloadManagementSystem.LfBranchTripping> trippings = system.getBranchTrippingList();
            assertEquals(trippings.size(), systems.firstTripping[s + 1] - systems.firstTripping[s]);
            for (int k = 0; k < trippings.size(); k++) {
                int t = systems.firstTripping[s] + k;
                assertEquals(trippings.get(k).branchToOperate().getNum(), systems.branchToOperateNum[t]);
                assertEquals(trippings.get(k).branchOpen(), systems.branchOpen[t]);
                assertEquals(trippings.get(k).threshold(), systems.threshold[t]);
            }
        }
    }

    @Test
    void areaBoundaries() {
        Network network = MultiAreaNetworkFactory.createTwoAreasWithTieLine();
        LfNetwork lfNetwork = Networks.load(network, new LfNetworkParameters().setAreaInterchangeControl(true)
                .setAreaInterchangeControlAreaType("ControlArea")).stream().filter(LfNetwork::hasArea).findFirst().orElseThrow();
        AreaBoundaries areas = AreaBoundaries.of(lfNetwork);
        assertEquals(lfNetwork.getAreas().size(), areas.count);
        assertTrue(areas.count > 0);
        for (int a = 0; a < areas.count; a++) {
            LfArea area = lfNetwork.getAreas().get(a);
            assertEquals(area.getInterchangeTarget(), areas.interchangeTarget[a]);
            int k = areas.firstBoundary[a];
            for (LfArea.Boundary boundary : area.getBoundaries()) {
                assertEquals(boundary.getBranch().getNum(), areas.boundaryBranchNum[k]);
                assertEquals(boundary.getSide() == TwoSides.ONE ? 1 : 2, areas.boundarySide[k]);
                k++;
            }
            assertEquals(areas.firstBoundary[a + 1], k);
            for (LfBus bus : area.getBuses()) {
                assertEquals(a, areas.busArea[bus.getNum()]);
            }
        }
    }

    @Test
    void secondaryVoltageControlZones() {
        Network network = IeeeCdfNetworkFactory.create14();
        network.newExtension(SecondaryVoltageControlAdder.class)
                .newControlZone()
                .withName("z1")
                .newPilotPoint().withTargetV(13).withBusbarSectionsOrBusesIds(List.of("B10")).add()
                .newControlUnit().withId("B6-G").add()
                .newControlUnit().withId("B8-G").add()
                .add()
                .add();
        LfNetwork lfNetwork = Networks.load(network, new LfNetworkParameters().setSecondaryVoltageControl(true)).getFirst();
        SecondaryVoltageControlZones zones = SecondaryVoltageControlZones.of(lfNetwork);
        assertEquals(1, zones.count);
        LfSecondaryVoltageControl zone = lfNetwork.getSecondaryVoltageControls().getFirst();
        assertEquals(zone.getPilotBus().getNum(), zones.pilotBusNum[0]);
        assertEquals(zone.getTargetValue(), zones.targetV[0]);
        assertTrue(zones.enabled[0]);
        List<LfBus> controllers = zone.getControllerBuses();
        assertEquals(2, controllers.size());
        for (int c = 0; c < controllers.size(); c++) {
            assertEquals(controllers.get(c).getNum(), zones.controllerBusNum[c]);
            assertEquals(controllers.get(c).getGeneratorVoltageControl().orElseThrow().getControlledBus().getNum(), zones.controlledBusNum[c]);
        }
    }

    @Test
    void participatingGenerators() {
        Network network = IeeeCdfNetworkFactory.create14();
        LfNetwork lfNetwork = Networks.load(network, new LfNetworkParameters()).getFirst();
        ParticipatingGenerators generators = ParticipatingGenerators.of(lfNetwork.getBuses());
        assertEquals(lfNetwork.getBuses().size(), generators.busCount);
        int expected = 0;
        for (int b = 0; b < generators.busCount; b++) {
            LfBus bus = lfNetwork.getBuses().get(b);
            for (int i = generators.firstGenerator[b]; i < generators.firstGenerator[b + 1]; i++) {
                LfGenerator generator = bus.getGenerators().get(generators.indexInBus[i]);
                assertTrue(generator.isParticipating());
                assertEquals(generator.getTargetP(), generators.targetP[i]);
                assertEquals(generator.getMaxTargetP(), generators.maxTargetP[i]);
                assertEquals(generator.getDroop(), generators.droop[i]);
            }
            expected += (int) bus.getGenerators().stream().filter(LfGenerator::isParticipating).count();
        }
        assertEquals(expected, generators.count);
        assertTrue(generators.count > 0);
    }
}
