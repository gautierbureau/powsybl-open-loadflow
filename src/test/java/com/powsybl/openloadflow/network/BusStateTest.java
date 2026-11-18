/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.network;

import com.powsybl.openloadflow.network.LfGenerator.GeneratorControlType;
import com.powsybl.openloadflow.network.impl.Networks;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * @author Gautier Bureau {@literal <gautier.bureau at rte-france.com>}
 */
class BusStateTest {

    private LfGenerator g3;
    private LfGenerator g4;

    @BeforeEach
    void setUp() {
        LfNetwork network = Networks.load(DistributedSlackNetworkFactory.create(), new FirstSlackBusSelector()).getFirst();
        g3 = network.getGeneratorById("g3");
        g4 = network.getGeneratorById("g4");
        assertSame(g3.getBus(), g4.getBus());
        // two different states, so that a generator given the other one's shows
        setState(g3, 1.1, 1.2, true, false, GeneratorControlType.VOLTAGE);
        setState(g4, 2.1, 2.2, false, true, GeneratorControlType.OFF);
    }

    private static void setState(LfGenerator generator, double targetP, double initialTargetP, boolean participating,
                                 boolean disabled, GeneratorControlType controlType) {
        generator.setTargetP(targetP);
        generator.setInitialTargetP(initialTargetP);
        generator.setParticipating(participating);
        generator.setDisabled(disabled);
        generator.setGeneratorControlType(controlType);
    }

    private static void assertDcState(LfGenerator generator, double targetP, double initialTargetP, boolean participating,
                                      boolean disabled) {
        assertEquals(targetP, generator.getTargetP());
        assertEquals(initialTargetP, generator.getInitialTargetP());
        assertEquals(participating, generator.isParticipating());
        assertEquals(disabled, generator.isDisabled());
    }

    @Test
    void testEachGeneratorGetsItsOwnStateBack() {
        BusState busState = BusState.save(g3.getBus());
        setState(g3, 3.1, 3.2, false, true, GeneratorControlType.OFF);
        setState(g4, 4.1, 4.2, true, false, GeneratorControlType.VOLTAGE);
        busState.restore();
        assertDcState(g3, 1.1, 1.2, true, false);
        assertDcState(g4, 2.1, 2.2, false, true);
        assertEquals(GeneratorControlType.VOLTAGE, g3.getGeneratorControlType());
        assertEquals(GeneratorControlType.OFF, g4.getGeneratorControlType());
    }

    @Test
    void testEachGeneratorGetsItsOwnDcStateBack() {
        BusDcState busState = BusDcState.save(g3.getBus());
        setState(g3, 3.1, 3.2, false, true, GeneratorControlType.OFF);
        setState(g4, 4.1, 4.2, true, false, GeneratorControlType.VOLTAGE);
        busState.restore();
        assertDcState(g3, 1.1, 1.2, true, false);
        assertDcState(g4, 2.1, 2.2, false, true);
    }
}
