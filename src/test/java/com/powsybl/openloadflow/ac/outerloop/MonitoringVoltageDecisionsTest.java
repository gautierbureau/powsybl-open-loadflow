/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.ac.outerloop;

import org.junit.jupiter.api.Test;

import static com.powsybl.openloadflow.ac.outerloop.MonitoringVoltageDecisions.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * @author Gautier Bureau {@literal <gautier.bureau at rte-france.com>}
 */
class MonitoringVoltageDecisionsTest {

    @Test
    void decisions() {
        assertEquals(DECISION_NONE, decision(1.0, 0.95, 1.05));
        // the thresholds themselves do not switch
        assertEquals(DECISION_NONE, decision(1.05, 0.95, 1.05));
        assertEquals(DECISION_NONE, decision(0.95, 0.95, 1.05));
        assertEquals(DECISION_TO_PV_HIGH, decision(Math.nextUp(1.05), 0.95, 1.05));
        assertEquals(DECISION_TO_PV_LOW, decision(Math.nextDown(0.95), 0.95, 1.05));
        assertEquals(DECISION_TO_PV_HIGH_THEN_LOW, decision(1.0, 1.05, 0.95));
        // no voltage control: undefined voltage
        assertEquals(DECISION_NONE, decision(Double.NaN, 0.95, 1.05));
        assertTrue(switchesAtHigh(DECISION_TO_PV_HIGH_THEN_LOW) && switchesAtLow(DECISION_TO_PV_HIGH_THEN_LOW));
        assertFalse(switchesAtLow(DECISION_TO_PV_HIGH) || switchesAtHigh(DECISION_TO_PV_LOW));
    }

    @Test
    void vector() {
        MonitoringVoltageVector m = new MonitoringVoltageVector();
        m.reset(2);
        m.controlledV[0] = 1.0;
        m.lowThreshold[0] = 0.95;
        m.highThreshold[0] = 1.05;
        m.controlledV[1] = 1.1;
        m.lowThreshold[1] = 0.95;
        m.highThreshold[1] = 1.05;
        decideEntries(m);
        assertArrayEquals(new int[] {DECISION_NONE, DECISION_TO_PV_HIGH}, m.decision);
        assertTrue(changesNetwork(m));
        m.reset(1);
        decideEntries(m);
        assertFalse(changesNetwork(m));
    }
}
