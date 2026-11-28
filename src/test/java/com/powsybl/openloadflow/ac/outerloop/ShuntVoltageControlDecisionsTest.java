/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.ac.outerloop;

import com.powsybl.openloadflow.lf.outerloop.IncrementalControlDecisions;
import com.powsybl.openloadflow.network.DiscretePositionDecisions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * @author Gautier Bureau {@literal <gautier.bureau at rte-france.com>}
 */
class ShuntVoltageControlDecisionsTest {

    private static final double[] SECTIONS = {0.0, 0.25, 0.5, 0.75, 1.0};

    /** One bus, one shunt of {@code controllers} section controllers at section 0. */
    private static ShuntVoltageControlVector vector(double diffV, double sensitivity, int controllers) {
        ShuntVoltageControlVector v = new ShuntVoltageControlVector();
        v.reset(1, 1, controllers);
        v.diffV[0] = diffV;
        v.halfTargetDeadband[0] = 0.01;
        v.firstShunt[0] = 0;
        v.firstShunt[1] = 1;
        v.sensitivity[0] = sensitivity;
        v.firstController[0] = 0;
        v.firstController[1] = controllers;
        for (int k = 0; k < controllers; k++) {
            v.indexInShunt[k] = k;
            v.sectionBs[k] = SECTIONS;
            v.maxDirectionChange[k] = 3;
            v.currentDirection[k] = IncrementalControlDecisions.DIRECTION_NONE;
            v.allowedDirection[k] = DiscretePositionDecisions.ALLOWED_BOTH;
        }
        return v;
    }

    @Test
    void oneSectionAtATimeUpToTheMaximumShift() {
        // a voltage difference of 1 pu with a sensitivity of 1: the susceptance would have to go up by 1
        ShuntVoltageControlVector v = vector(1.0, 1.0, 1);
        ShuntVoltageControlDecisions.decide(v, 3);
        assertEquals(3, v.position[0]);
        assertEquals(3, v.sectionShift[0]);
        assertEquals(3, v.moveCount);
    }

    @Test
    void controllersTakeTurns() {
        ShuntVoltageControlVector v = vector(0.5, 1.0, 2);
        ShuntVoltageControlDecisions.decide(v, 3);
        assertEquals(1, v.position[0]);
        assertEquals(1, v.position[1]);
        assertArrayEquals(new int[] {0, 1}, java.util.Arrays.copyOf(v.moveController, v.moveCount));
    }

    @Test
    void inDeadband() {
        ShuntVoltageControlVector v = vector(0.005, 1.0, 1);
        ShuntVoltageControlDecisions.decide(v, 3);
        assertEquals(0, v.moveCount);
        assertEquals(0, v.position[0]);
    }
}
