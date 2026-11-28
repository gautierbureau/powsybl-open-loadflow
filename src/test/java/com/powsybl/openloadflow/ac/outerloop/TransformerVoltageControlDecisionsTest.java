/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.ac.outerloop;

import com.powsybl.openloadflow.lf.outerloop.IncrementalContextData;
import com.powsybl.openloadflow.lf.outerloop.IncrementalControlDecisions;
import com.powsybl.openloadflow.network.AllowedDirection;
import com.powsybl.openloadflow.network.Direction;
import com.powsybl.openloadflow.network.DiscretePositionDecisions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * @author Gautier Bureau {@literal <gautier.bureau at rte-france.com>}
 */
class TransformerVoltageControlDecisionsTest {

    private static final double[] TAPS = {0.9, 0.925, 0.95, 0.975, 1.0, 1.025, 1.05};

    private static TransformerVoltageControlVector vector(double diffV, double halfDeadband, double... sensitivities) {
        TransformerVoltageControlVector v = new TransformerVoltageControlVector();
        v.reset(1, sensitivities.length);
        v.diffV[0] = diffV;
        v.halfTargetDeadband[0] = halfDeadband;
        v.firstController[0] = 0;
        v.firstController[1] = sensitivities.length;
        for (int c = 0; c < sensitivities.length; c++) {
            v.sensitivity[c] = sensitivities[c];
            v.tapR1s[c] = TAPS;
            v.initialTapIndex[c] = 3;
            v.initialR1[c] = TAPS[3];
            v.maxDirectionChange[c] = 3;
            v.currentDirection[c] = IncrementalControlDecisions.DIRECTION_NONE;
            v.allowedDirection[c] = DiscretePositionDecisions.ALLOWED_BOTH;
        }
        return v;
    }

    @Test
    void singleController() {
        // 0.05 pu with a sensitivity of 1: two taps up at once, within the maximum shift
        TransformerVoltageControlVector v = vector(0.05, 0.001, 1.0);
        TransformerVoltageControlDecisions.decide(v, 3, 0.05);
        assertEquals(5, v.tapIndex[0]);
        assertEquals(1, v.moveCount);
        assertFalse(v.allControllersAtLimit[0]);
        // bounded by the maximum shift, and at the last tap
        v = vector(0.5, 0.001, 1.0);
        TransformerVoltageControlDecisions.decide(v, 3, 0.05);
        assertEquals(6, v.tapIndex[0]);
        assertTrue(v.allControllersAtLimit[0]);
        // insensitive: nothing moves
        v = vector(0.05, 0.001, 0.01);
        TransformerVoltageControlDecisions.decide(v, 3, 0.05);
        assertTrue(v.insensitive[0]);
        assertEquals(0, v.moveCount);
    }

    @Test
    void severalControllersTakeTurns() {
        // 0.05 pu shared by two controllers of sensitivity 1: one tap each, in turn
        TransformerVoltageControlVector v = vector(0.05, 0.001, 1.0, 1.0);
        TransformerVoltageControlDecisions.decide(v, 3, 0.05);
        assertEquals(4, v.tapIndex[0]);
        assertEquals(4, v.tapIndex[1]);
        assertEquals(2, v.moveCount);
        assertArrayEquals(new int[] {0, 1}, java.util.Arrays.copyOf(v.moveController, 2));
        assertFalse(v.allControllersAtLimit[0]);
    }

    @Test
    void ratchetMatchesControllerContext() {
        IncrementalContextData.ControllerContext context = new IncrementalContextData.ControllerContext(1);
        int count = 0;
        int current = IncrementalControlDecisions.DIRECTION_NONE;
        int allowed = DiscretePositionDecisions.ALLOWED_BOTH;
        for (Direction direction : new Direction[] {Direction.INCREASE, Direction.DECREASE, Direction.INCREASE, Direction.DECREASE}) {
            int d = direction == Direction.INCREASE ? IncrementalControlDecisions.DIRECTION_INCREASE : IncrementalControlDecisions.DIRECTION_DECREASE;
            int newCount = IncrementalControlDecisions.directionChangeCountAfter(count, 1, current, d);
            int newCurrent = IncrementalControlDecisions.currentDirectionAfter(count, 1, current, d);
            allowed = IncrementalControlDecisions.allowedDirectionAfter(count, 1, allowed, d);
            count = newCount;
            current = newCurrent;
            context.updateAllowedDirection(direction);
            assertEquals(count, context.getDirectionChangeCount());
            assertEquals(current, context.getCurrentDirectionCode());
            assertEquals(allowed, context.getAllowedDirectionCode());
        }
        // one change allowed: the third move fixes the direction to its own, the fourth to its own
        assertEquals(AllowedDirection.DECREASE, context.getAllowedDirection());
    }
}
