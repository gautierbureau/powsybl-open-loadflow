/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.network;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static com.powsybl.openloadflow.network.DiscretePositionDecisions.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * @author Gautier Bureau {@literal <gautier.bureau at rte-france.com>}
 */
class DiscretePositionDecisionsTest {

    private static final double[] VALUES = {0.9, 0.95, 1.0, 1.05, 1.1};

    @Test
    void allowedRange() {
        assertEquals(2, allowedFrom(ALLOWED_INCREASE, 2, 0));
        assertEquals(4, allowedTo(ALLOWED_INCREASE, 2, 5));
        assertEquals(1, allowedFrom(ALLOWED_DECREASE, 2, 1));
        assertEquals(2, allowedTo(ALLOWED_DECREASE, 2, 5));
        assertEquals(0, allowedFrom(ALLOWED_BOTH, 2, 0));
        assertEquals(4, allowedTo(ALLOWED_BOTH, 2, 5));
        assertEquals(ALLOWED_INCREASE, toCode(AllowedDirection.INCREASE));
        assertEquals(ALLOWED_DECREASE, toCode(AllowedDirection.DECREASE));
        assertEquals(ALLOWED_BOTH, toCode(AllowedDirection.BOTH));
    }

    @Test
    void closest() {
        // strictly closer only: a tie keeps the lowest index reached first, the current one when it is among them
        assertEquals(3, closestPosition(VALUES, 2, 0, 4, Integer.MAX_VALUE, 1.06, Math.abs(1.06 - 1.0)));
        assertEquals(2, closestPosition(VALUES, 2, 0, 4, Integer.MAX_VALUE, 1.0, 0));
        // the shift bound
        assertEquals(3, closestPosition(VALUES, 2, 0, 4, 1, 2.0, 1.0));
        assertEquals(4, closestPosition(VALUES, 2, 0, 4, 2, 2.0, 1.0));
        // the allowed range
        assertEquals(2, closestPosition(VALUES, 2, 0, 2, Integer.MAX_VALUE, 2.0, 1.0));
        // a larger initial distance than the current position's: the requested change of a shunt section
        assertEquals(1, closestPosition(VALUES, 2, 0, 4, Integer.MAX_VALUE, 0.96, 0.04));
    }

    @Test
    void firstAbove() {
        assertEquals(3, firstPositionAbove(VALUES, 2, 0, 4, Integer.MAX_VALUE, 0.02));
        assertEquals(4, firstPositionAbove(VALUES, 2, 0, 4, Integer.MAX_VALUE, 0.07));
        assertEquals(3, firstPositionAbove(VALUES, 2, 0, 4, 1, 0.07));
        assertEquals(0, firstPositionAbove(VALUES, 2, 0, 4, Integer.MAX_VALUE, -1));
        assertEquals(2, firstPositionAbove(VALUES, 2, 0, 4, Integer.MAX_VALUE, 0));
        assertEquals(2, firstPositionAbove(VALUES, 2, 0, 2, Integer.MAX_VALUE, 0.02));
    }

    @Test
    void shiftOne() {
        assertEquals(3, shiftOnePosition(VALUES, 2, 1.0, true));
        assertEquals(1, shiftOnePosition(VALUES, 2, 1.0, false));
        assertEquals(4, shiftOnePosition(VALUES, 4, 1.1, true));
        assertEquals(0, shiftOnePosition(VALUES, 0, 0.9, false));
        double[] reversed = {1.1, 1.05, 1.0};
        assertEquals(0, shiftOnePosition(reversed, 1, 1.05, true));
    }

    /** The rounding of a shunt's continuous susceptance on arrays, against {@link LfShunt.Controller}'s positions. */
    @Test
    void dispatchMatchesControllers() {
        List<Double> sections1 = List.of(0.0, 0.1, 0.2, 0.3);
        List<Double> sections2 = List.of(0.0, 0.05, 0.1);
        LfShunt.Controller c1 = new LfShunt.Controller("c1", sections1, sections1, 0, 0);
        LfShunt.Controller c2 = new LfShunt.Controller("c2", sections2, sections2, 2, 1);
        int[] positions = {c1.getPosition(), c2.getPosition()};
        double residue = dispatch(0.27, new double[][] {c1.getSectionBs(), c2.getSectionBs()},
                new int[] {c1.getMinPosition(), c2.getMinPosition()}, positions);
        assertArrayEquals(new int[] {1, 2}, positions);
        assertEquals(0.27 - 0.1 - 0.1, residue, 0);
    }

    @Test
    void shuntSectionUpdate() {
        List<Double> sections = List.of(0.0, 0.1, 0.2, 0.3);
        LfShunt.Controller c = new LfShunt.Controller("c", sections, sections, 1, 0);
        assertEquals(Optional.of(Direction.INCREASE), c.updateSectionB(0.15, 3, AllowedDirection.BOTH));
        assertEquals(2, c.getPosition());
        assertEquals(Optional.empty(), c.updateSectionB(-0.15, 3, AllowedDirection.INCREASE));
        assertEquals(Optional.of(Direction.DECREASE), c.updateSectionB(-0.2, 1, AllowedDirection.DECREASE));
        assertEquals(1, c.getPosition());
    }
}
