/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.network.impl;

import com.powsybl.commons.PowsyblException;
import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.extensions.LoadDetailAdder;
import com.powsybl.iidm.network.test.EurostagTutorialExample1Factory;
import com.powsybl.openloadflow.network.LfLoad;
import com.powsybl.openloadflow.network.LfNetwork;
import com.powsybl.openloadflow.network.LfNetworkParameters;
import com.powsybl.openloadflow.network.NetworkState;
import com.powsybl.openloadflow.util.PerUnit;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Changing the active power set point of one original load in place must leave the network equivalent to one built
 * from a grid model that already carries that set point. Every test here is that comparison.
 *
 * @author Gautier Bureau {@literal <gautier.bureau at rte-france.com>}
 */
class LfLoadImplP0Test {

    private static final double TOLERANCE = 1e-12;

    /**
     * Two loads on the same bus, so that the aggregate can be told apart from the load being moved.
     */
    private static Network twoLoadsOnOneBus() {
        Network network = EurostagTutorialExample1Factory.create();
        network.getVoltageLevel("VLLOAD").newLoad()
                .setId("LOAD2")
                .setBus("NLOAD")
                .setConnectableBus("NLOAD")
                .setP0(100)
                .setQ0(50)
                .add();
        return network;
    }

    private static Network twoConformLoadsOnOneBus() {
        Network network = twoLoadsOnOneBus();
        network.getLoad("LOAD").newExtension(LoadDetailAdder.class)
                .withFixedActivePower(400).withVariableActivePower(200)
                .withFixedReactivePower(150).withVariableReactivePower(50)
                .add();
        network.getLoad("LOAD2").newExtension(LoadDetailAdder.class)
                .withFixedActivePower(60).withVariableActivePower(40)
                .withFixedReactivePower(30).withVariableReactivePower(20)
                .add();
        return network;
    }

    private static LfNetwork build(Network network, boolean distributedOnConformLoad) {
        return Networks.load(network, new LfNetworkParameters().setDistributedOnConformLoad(distributedOnConformLoad)).get(0);
    }

    private static void assertEquivalent(LfLoad expected, LfLoad actual) {
        assertEquals(expected.getOriginalLoadsP0(), actual.getOriginalLoadsP0(), "original loads p0");
        assertEquals(expected.getTargetP(), actual.getTargetP(), TOLERANCE, "targetP");
        assertEquals(expected.getInitialTargetP(), actual.getInitialTargetP(), TOLERANCE, "initialTargetP");
        assertEquals(expected.getTargetQ(), actual.getTargetQ(), TOLERANCE, "targetQ");
        assertEquals(expected.getAbsVariableTargetP(), actual.getAbsVariableTargetP(), TOLERANCE, "absVariableTargetP");
        assertEquals(expected.ensurePowerFactorConstantByLoad(), actual.ensurePowerFactorConstantByLoad(), "ensurePowerFactorConstantByLoad");
        assertEquals(expected.getBus().getLoadTargetP(), actual.getBus().getLoadTargetP(), TOLERANCE, "bus load targetP");
        // reaches the per-load variable target P map and the per-load power factors, which have no accessor of their own
        assertEquals(expected.calculateNewTargetQ(0.1), actual.calculateNewTargetQ(0.1), TOLERANCE, "calculateNewTargetQ");
    }

    /**
     * The slack is distributed proportionally to p0 here, so the participation factors have to follow it.
     */
    @Test
    void movingP0MatchesANetworkBuiltWithIt() {
        double newP0 = 800;

        LfNetwork subject = build(twoLoadsOnOneBus(), false);
        subject.getLoadById("LOAD").setOriginalLoadP0("LOAD", newP0 / PerUnit.SB);

        Network referenceNetwork = twoLoadsOnOneBus();
        referenceNetwork.getLoad("LOAD").setP0(newP0);
        LfNetwork reference = build(referenceNetwork, false);

        assertEquivalent(reference.getLoadById("LOAD"), subject.getLoadById("LOAD"));
        assertEquals(9.0, subject.getLoadById("LOAD").getAbsVariableTargetP(), TOLERANCE, "800 MW + 100 MW of variable load");
    }

    /**
     * On conform load the participation comes from the LoadDetail variable active power, which p0 does not change --
     * so here the very same operation must leave absVariableTargetP alone.
     */
    @Test
    void movingP0OnConformLoadMatchesANetworkBuiltWithIt() {
        double newP0 = 800;

        LfNetwork subject = build(twoConformLoadsOnOneBus(), true);
        double absVariableTargetPBefore = subject.getLoadById("LOAD").getAbsVariableTargetP();
        subject.getLoadById("LOAD").setOriginalLoadP0("LOAD", newP0 / PerUnit.SB);

        Network referenceNetwork = twoConformLoadsOnOneBus();
        referenceNetwork.getLoad("LOAD").setP0(newP0);
        LfNetwork reference = build(referenceNetwork, true);

        assertEquivalent(reference.getLoadById("LOAD"), subject.getLoadById("LOAD"));
        assertEquals(2.4, absVariableTargetPBefore, TOLERANCE, "200 MW + 40 MW of variable active power");
        assertEquals(absVariableTargetPBefore, subject.getLoadById("LOAD").getAbsVariableTargetP(), TOLERANCE,
                "variable active power is a LoadDetail attribute, untouched by a new p0");
    }

    @Test
    void movingOneLoadLeavesTheOthersOnTheBusAlone() {
        LfNetwork subject = build(twoLoadsOnOneBus(), false);
        LfLoad load = subject.getLoadById("LOAD");

        load.setOriginalLoadP0("LOAD", 800 / PerUnit.SB);

        assertEquals(1.0, load.getOriginalLoadP0("LOAD2"), TOLERANCE, "LOAD2 must not absorb any of LOAD's change");
        assertEquals(8.0, load.getOriginalLoadP0("LOAD"), TOLERANCE);
        assertEquals(9.0, load.getTargetP(), TOLERANCE, "the aggregate carries both");
    }

    /**
     * The flag is an OR over the original loads, so it has to be able to go back off again.
     */
    @Test
    void powerFactorConstantByLoadFollowsP0BothWays() {
        LfNetwork subject = build(twoLoadsOnOneBus(), false);
        LfLoad load = subject.getLoadById("LOAD");
        assertFalse(load.ensurePowerFactorConstantByLoad(), "two plain positive loads");

        load.setOriginalLoadP0("LOAD", -50 / PerUnit.SB);
        assertTrue(load.ensurePowerFactorConstantByLoad(), "a negative p0 needs its own power factor");

        load.setOriginalLoadP0("LOAD", 600 / PerUnit.SB);
        assertFalse(load.ensurePowerFactorConstantByLoad(), "and the flag has to come back off");
    }

    @Test
    void restoreUndoesAP0Change() {
        LfNetwork subject = build(twoLoadsOnOneBus(), false);
        LfLoad load = subject.getLoadById("LOAD");
        NetworkState state = NetworkState.save(subject);

        load.setOriginalLoadP0("LOAD", -50 / PerUnit.SB);
        state.restore();

        LfNetwork reference = build(twoLoadsOnOneBus(), false);
        assertEquivalent(reference.getLoadById("LOAD"), load);
    }

    @Test
    void movingP0OfAnUnknownLoadThrows() {
        LfNetwork subject = build(twoLoadsOnOneBus(), false);
        LfLoad load = subject.getLoadById("LOAD");
        PowsyblException e = assertThrows(PowsyblException.class, () -> load.setOriginalLoadP0("UNKNOWN", 1.0));
        assertTrue(e.getMessage().contains("UNKNOWN"), e.getMessage());
    }
}
