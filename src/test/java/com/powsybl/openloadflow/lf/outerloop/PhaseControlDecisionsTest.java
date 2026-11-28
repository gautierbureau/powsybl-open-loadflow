/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.lf.outerloop;

import org.junit.jupiter.api.Test;

import static com.powsybl.openloadflow.lf.outerloop.PhaseControlDecisions.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * @author Gautier Bureau {@literal <gautier.bureau at rte-france.com>}
 */
class PhaseControlDecisionsTest {

    @Test
    void discreteLimiter() {
        assertEquals(DECISION_NONE, discreteLimiterDecision(1.0, 1.0, true));
        assertEquals(DECISION_SHIFT_ONE_TAP_DOWN, discreteLimiterDecision(Math.nextUp(1.0), 1.0, true));
        assertEquals(DECISION_SHIFT_ONE_TAP_UP, discreteLimiterDecision(1.1, 1.0, false));
        assertEquals(DECISION_NONE, discreteLimiterDecision(Double.NaN, 1.0, false));
    }

    @Test
    void deadband() {
        assertEquals(MIN_TARGET_DEADBAND / 2, halfTargetDeadband(0), 0);
        assertEquals(0.05, halfTargetDeadband(0.1), 0);
        assertFalse(isOutOfDeadband(1.25, 1.0, 0.25));
        assertTrue(isOutOfDeadband(Math.nextUp(1.25), 1.0, 0.25));
        assertTrue(isOutOfDeadband(Math.nextDown(0.75), 1.0, 0.25));
    }

    @Test
    void incremental() {
        // limiter: above its limit only, the deadband is not read
        assertEquals(DECISION_NONE, incrementalDecision(true, 1.0, 1.0, Double.NaN, Double.NaN));
        assertEquals(DECISION_EXCEED_PHASE_SHIFT, incrementalDecision(true, 1.2, 1.0, Double.NaN, -0.5));
        assertEquals(DECISION_INSENSITIVE, incrementalDecision(true, 1.2, 1.0, Double.NaN, SENSI_EPS));
        // controller: out of its deadband both ways
        assertEquals(DECISION_NONE, incrementalDecision(false, 1.0, 1.01, 0.05, Double.NaN));
        assertEquals(DECISION_REACH_PHASE_SHIFT, incrementalDecision(false, 0.9, 1.0, 0.05, 0.2));
        assertEquals(DECISION_REACH_PHASE_SHIFT, incrementalDecision(false, 1.1, 1.0, 0.05, -0.2));
        assertEquals(Math.toRadians((1.0 - 0.9) / 0.2), phaseShift(0.9, 1.0, 0.2), 0);
    }

    @Test
    void vector() {
        PhaseControlVector v = new PhaseControlVector();
        v.reset(2);
        v.limiter[0] = true;
        v.value[0] = 1.2;
        v.target[0] = 1.0;
        v.sensitivity[0] = -0.5;
        v.limiter[1] = false;
        v.value[1] = 1.0;
        v.target[1] = 1.0;
        v.halfTargetDeadband[1] = 0.05;
        decideIncrementalEntries(v);
        assertArrayEquals(new int[] {DECISION_EXCEED_PHASE_SHIFT, DECISION_NONE}, v.decision);
        assertEquals(Math.toRadians((1.0 - 1.2) / -0.5), v.phaseShift[0], 0);
        assertTrue(Double.isNaN(v.phaseShift[1]));
    }

    @Test
    void crossImpact() {
        assertTrue(isSignificantCrossImpact(Math.toRadians(1), 0.8, 2.0, 1.0, 0.75));
        assertFalse(isSignificantCrossImpact(Math.toRadians(1), 0.7, 2.0, 1.0, 0.75));
    }
}
