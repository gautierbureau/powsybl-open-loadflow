/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.ac.outerloop;

import org.junit.jupiter.api.Test;

import static com.powsybl.openloadflow.ac.outerloop.ReactiveLimitsDecisions.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The reactive limits decisions on numbers alone, without a network.
 *
 * @author Gautier Bureau {@literal <gautier.bureau at rte-france.com>}
 */
class ReactiveLimitsDecisionsTest {

    private static final double TOL = 1e-4;
    private static final double V_LOW = 0.5 * 1.02;
    private static final double V_HIGH = 2 / 1.02;

    @Test
    void pvBusSwitchesOutsideItsLimitsByMoreThanTheTolerance() {
        assertEquals(DECISION_NONE, pvBusDecision(0.5, -1, 1, TOL, false, 1, V_LOW, V_HIGH));
        // at the limit, and beyond it by less than the tolerance: stays PV
        assertEquals(DECISION_NONE, pvBusDecision(-1, -1, 1, TOL, false, 1, V_LOW, V_HIGH));
        assertEquals(DECISION_NONE, pvBusDecision(-1 - TOL / 2, -1, 1, TOL, false, 1, V_LOW, V_HIGH));
        assertEquals(DECISION_NONE, pvBusDecision(1 + TOL / 2, -1, 1, TOL, false, 1, V_LOW, V_HIGH));
        assertEquals(DECISION_TO_PQ_MIN_Q, pvBusDecision(-1 - 2 * TOL, -1, 1, TOL, false, 1, V_LOW, V_HIGH));
        assertEquals(DECISION_TO_PQ_MAX_Q, pvBusDecision(1 + 2 * TOL, -1, 1, TOL, false, 1, V_LOW, V_HIGH));
        // an empty range: the minimum is tested first
        assertEquals(DECISION_TO_PQ_MIN_Q, pvBusDecision(0, 1, -1, TOL, false, 1, V_LOW, V_HIGH));
        // unlimited
        assertEquals(DECISION_NONE, pvBusDecision(1e6, -Double.MAX_VALUE, Double.MAX_VALUE, TOL, false, 1, V_LOW, V_HIGH));
        // an undefined reactive power compares false with everything
        assertEquals(DECISION_NONE, pvBusDecision(Double.NaN, -1, 1, TOL, false, 1, V_LOW, V_HIGH));
    }

    @Test
    void remoteControllerInRobustModeSwitchesOutOfTheRealisticBand() {
        assertEquals(DECISION_TO_PQ_MIN_REALISTIC_V, pvBusDecision(0, -1, 1, TOL, true, 0.4, V_LOW, V_HIGH));
        assertEquals(DECISION_TO_PQ_MAX_REALISTIC_V, pvBusDecision(0, -1, 1, TOL, true, 2.1, V_LOW, V_HIGH));
        assertEquals(DECISION_NONE, pvBusDecision(0, -1, 1, TOL, true, V_LOW, V_LOW, V_HIGH));
        assertEquals(DECISION_NONE, pvBusDecision(0, -1, 1, TOL, true, V_HIGH, V_LOW, V_HIGH));
        // not a remote controller, or not in robust mode: its own voltage is not looked at
        assertEquals(DECISION_NONE, pvBusDecision(0, -1, 1, TOL, false, 0.4, V_LOW, V_HIGH));
        // the reactive limits come first
        assertEquals(DECISION_TO_PQ_MAX_Q, pvBusDecision(2, -1, 1, TOL, true, 0.4, V_LOW, V_HIGH));
    }

    @Test
    void voltageIsResetForARemoteControllerLeavingPvOutOfTheBand() {
        assertTrue(resetsV(DECISION_TO_PQ_MIN_REALISTIC_V, true, 0.4, V_LOW, V_HIGH));
        assertTrue(resetsV(DECISION_TO_PQ_MAX_REALISTIC_V, true, 2.1, V_LOW, V_HIGH));
        assertTrue(resetsV(DECISION_TO_PQ_MAX_Q, true, 0.4, V_LOW, V_HIGH));
        assertFalse(resetsV(DECISION_TO_PQ_MAX_Q, true, 1, V_LOW, V_HIGH));
        assertFalse(resetsV(DECISION_TO_PQ_MAX_Q, false, 0.4, V_LOW, V_HIGH));
        assertFalse(resetsV(DECISION_NONE, true, 0.4, V_LOW, V_HIGH));
    }

    @Test
    void frozenTargetQIsTheLimitOrTheInitialTarget() {
        assertEquals(-1, frozenQ(DECISION_TO_PQ_MIN_Q, -1, 1, 0.3));
        assertEquals(1, frozenQ(DECISION_TO_PQ_MAX_Q, -1, 1, 0.3));
        assertEquals(0.3, frozenQ(DECISION_TO_PQ_MIN_REALISTIC_V, -1, 1, 0.3));
        assertEquals(0.3, frozenQ(DECISION_TO_PQ_MAX_REALISTIC_V, -1, 1, 0.3));
    }

    @Test
    void pqBusSwitchesBackOnTheControlledVoltage() {
        // at the minimum: back when the controlled voltage is under its target, strictly
        assertEquals(DECISION_TO_PV, pqBusDecision(LIMIT_MIN_Q, 0.99, 1, true, -1, 1, -1, TOL));
        assertEquals(DECISION_NONE, pqBusDecision(LIMIT_MIN_Q, 1, 1, true, -1, 1, -1, TOL));
        assertEquals(DECISION_NONE, pqBusDecision(LIMIT_MIN_Q, 1.01, 1, true, -1, 1, -1, TOL));
        // at the maximum: back when it is over its target, strictly
        assertEquals(DECISION_TO_PV, pqBusDecision(LIMIT_MAX_Q, 1.01, 1, true, -1, 1, 1, TOL));
        assertEquals(DECISION_NONE, pqBusDecision(LIMIT_MAX_Q, 1, 1, true, -1, 1, 1, TOL));
        assertEquals(DECISION_NONE, pqBusDecision(LIMIT_MAX_Q, 0.99, 1, true, -1, 1, 1, TOL));
        // a realistic-voltage switch reads like the limit of its side
        assertEquals(DECISION_TO_PV, pqBusDecision(LIMIT_MIN_REALISTIC_V, 0.99, 1, true, -1, 1, 0.3, TOL));
        assertEquals(DECISION_TO_PV, pqBusDecision(LIMIT_MAX_REALISTIC_V, 1.01, 1, true, -1, 1, 0.3, TOL));
        // no voltage control: nothing compares
        assertEquals(DECISION_NONE, pqBusDecision(LIMIT_MIN_Q, Double.NaN, Double.NaN, true, -1, 1, -1, TOL));
    }

    @Test
    void pqBusFollowsAReactiveLimitThatMoved() {
        // the limit moved by more than the tolerance and the bus does not switch back
        assertEquals(DECISION_REFREEZE_AT_LIMIT, pqBusDecision(LIMIT_MIN_Q, 1.01, 1, true, -1.1, 1, -1, TOL));
        assertEquals(DECISION_REFREEZE_AT_LIMIT, pqBusDecision(LIMIT_MAX_Q, 0.99, 1, true, -1, 0.9, 1, TOL));
        assertEquals(DECISION_NONE, pqBusDecision(LIMIT_MIN_Q, 1.01, 1, true, -1 - TOL / 2, 1, -1, TOL));
        // the switch back comes first
        assertEquals(DECISION_TO_PV, pqBusDecision(LIMIT_MIN_Q, 0.99, 1, true, -1.1, 1, -1, TOL));
        // a bus that wants back but cannot (a generator with a slope) still follows its limit
        assertEquals(DECISION_REFREEZE_AT_LIMIT, pqBusDecision(LIMIT_MIN_Q, 0.99, 1, false, -1.1, 1, -1, TOL));
        assertEquals(DECISION_NONE, pqBusDecision(LIMIT_MIN_Q, 0.99, 1, false, -1, 1, -1, TOL));
        // a realistic-voltage switch holds its initial target Q whatever the limits do
        assertEquals(DECISION_NONE, pqBusDecision(LIMIT_MIN_REALISTIC_V, 1.01, 1, true, -1.1, 1, 0.3, TOL));
        assertEquals(DECISION_NONE, pqBusDecision(LIMIT_MAX_REALISTIC_V, 0.99, 1, true, -1, 0.9, 0.3, TOL));
    }

    /** Three controlling buses out of their limits and one bus switched to PQ that wants back. */
    private static ReactiveLimitsVector vector() {
        ReactiveLimitsVector r = new ReactiveLimitsVector();
        r.reset(4);
        for (int i = 0; i < 3; i++) {
            r.busNum[i] = i;
            r.controlEnabled[i] = true;
            r.minQ[i] = -1;
            r.maxQ[i] = 1;
            r.q[i] = i == 1 ? -2 : 2;
            r.v[i] = 1;
            r.initialTargetQ[i] = Double.NaN;
        }
        r.busNum[3] = 3;
        r.controlEnabled[3] = false;
        r.qLimitType[3] = LIMIT_MIN_Q;
        r.canSwitchToPv[3] = true;
        r.minQ[3] = -1;
        r.maxQ[3] = 1;
        r.frozenTargetQ[3] = -1;
        r.controlledV[3] = 0.99;
        r.targetV[3] = 1;
        return r;
    }

    @Test
    void entriesAreDecidedFromTheirInputs() {
        ReactiveLimitsVector r = vector();
        decideEntries(r, TOL, false, V_LOW, V_HIGH);
        assertArrayEquals(new int[] {DECISION_TO_PQ_MAX_Q, DECISION_TO_PQ_MIN_Q, DECISION_TO_PQ_MAX_Q, DECISION_TO_PV}, r.decision);
        assertEquals(1, r.qLimit[0]);
        assertEquals(-1, r.qLimit[1]);
        assertEquals(3, countSwitchesToPq(r));
        assertEquals(0, countRemainingPv(r));
        assertEquals(1, count(r, DECISION_TO_PV));
        assertTrue(changesNetwork(r));

        // a bus switched to PQ without a limit type is left alone
        r = vector();
        r.qLimitType[3] = LIMIT_NONE;
        decideEntries(r, TOL, false, V_LOW, V_HIGH);
        assertEquals(DECISION_NONE, r.decision[3]);

        // one entry at a time gives the same decisions
        ReactiveLimitsVector byEntry = vector();
        for (int i = byEntry.count - 1; i >= 0; i--) {
            decideEntry(byEntry, i, TOL, false, V_LOW, V_HIGH);
        }
        ReactiveLimitsVector all = vector();
        decideEntries(all, TOL, false, V_LOW, V_HIGH);
        assertArrayEquals(all.decision, byEntry.decision);
    }

    @Test
    void theStrongestBusStaysPvWhenEveryOneWouldSwitch() {
        // highest controlled nominal voltage first
        ReactiveLimitsVector r = vector();
        decideEntries(r, TOL, false, V_LOW, V_HIGH);
        r.controlledNominalV = new double[] {225, 400, 225, 0};
        r.targetP = new double[] {5, 1, 9, 0};
        r.idRank = new int[] {0, 1, 2, 0};
        assertEquals(1, keepStrongestPv(r));
        assertArrayEquals(new int[] {DECISION_TO_PQ_MAX_Q, DECISION_KEPT_PV, DECISION_TO_PQ_MAX_Q, DECISION_TO_PV}, r.decision);
        assertEquals(2, countSwitchesToPq(r));

        // then highest target P
        r = vector();
        decideEntries(r, TOL, false, V_LOW, V_HIGH);
        r.controlledNominalV = new double[] {400, 400, 400, 0};
        r.targetP = new double[] {5, 1, 9, 0};
        r.idRank = new int[] {0, 1, 2, 0};
        assertEquals(2, keepStrongestPv(r));

        // then lowest id
        r = vector();
        decideEntries(r, TOL, false, V_LOW, V_HIGH);
        r.controlledNominalV = new double[] {400, 400, 400, 0};
        r.targetP = new double[] {5, 5, 5, 0};
        r.idRank = new int[] {2, 0, 1, 0};
        assertEquals(1, keepStrongestPv(r));

        // a single candidate is kept, and then nothing changes the network but the switch back
        r = vector();
        r.q[0] = 0;
        r.q[1] = 0;
        decideEntries(r, TOL, false, V_LOW, V_HIGH);
        r.decision[3] = DECISION_NONE;
        assertEquals(2, keepStrongestPv(r));
        assertFalse(changesNetwork(r));

        // no candidate
        r = vector();
        r.reset(0);
        assertEquals(-1, keepStrongestPv(r));
    }

    @Test
    void aBusThatSwitchedTooManyTimesStaysPq() {
        ReactiveLimitsVector r = vector();
        decideEntries(r, TOL, false, V_LOW, V_HIGH);
        r.pvPqSwitchCount[3] = 2;
        blockSwitchesToPv(r, 3);
        assertEquals(DECISION_TO_PV, r.decision[3]);
        r.pvPqSwitchCount[3] = 3;
        blockSwitchesToPv(r, 3);
        assertEquals(DECISION_TO_PV_BLOCKED, r.decision[3]);

        // a blocked switch back alone does not change the network
        for (int i = 0; i < 3; i++) {
            r.decision[i] = DECISION_NONE;
        }
        assertFalse(changesNetwork(r));
    }

    @Test
    void aVectorIsReusedFromOneCheckToTheNext() {
        ReactiveLimitsVector r = vector();
        decideEntries(r, TOL, false, V_LOW, V_HIGH);
        r.reset(2);
        assertEquals(2, r.count);
        assertArrayEquals(new int[] {DECISION_NONE, DECISION_NONE}, new int[] {r.decision[0], r.decision[1]});
        assertEquals(0, countSwitchesToPq(r));
        r.reset(6);
        assertEquals(6, r.busNum.length);
    }
}
