/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.ac.outerloop.tap;

import org.junit.jupiter.api.Test;

import static com.powsybl.openloadflow.ac.outerloop.tap.TransformerRatioDecisions.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * @author Gautier Bureau {@literal <gautier.bureau at rte-france.com>}
 */
class TransformerRatioDecisionsTest {

    @Test
    void range() {
        assertFalse(isOutOfRange(1.0, 0.9, 1.1));
        assertFalse(isOutOfRange(1.1, 0.9, 1.1));
        assertTrue(isOutOfRange(Math.nextUp(1.1), 0.9, 1.1));
        assertEquals(1.1, extremeR1(1.2, 0.9, 1.1));
        assertEquals(0.9, extremeR1(0.8, 0.9, 1.1));
    }

    @Test
    void followTheGroup() {
        // the group moves half way up from 1.0 to 1.25: a member from 0.5 to 1.5 moves half way from its 1.0 too
        assertEquals(1.25, updatedR1(1.125, 1.0, 0.5, 1.5, 0.75, 1.25, 1.0));
        assertEquals(0.75, updatedR1(0.875, 1.0, 0.5, 1.5, 0.75, 1.25, 1.0));
        assertEquals(1.0, updatedR1(1.0, 1.0, 0.5, 1.5, 0.75, 1.25, 1.0));
    }

    @Test
    void group() {
        assertEquals(0.75, shareFromMax(1.0, 0.75, 1.75));
        assertEquals(0.25, shareFromMin(1.0, 0.75, 1.75));
        assertEquals(1.0, shareFromMax(1.0, 1.0, 1.0));
        assertEquals(1.0, shareFromMin(1.0, 1.0, 1.0));
        assertEquals((0.75 * 0.75 + 1.75 * 0.25) / 1, groupInitialR1(1.75, 0.75, 0.75, 0.25, 1));
    }
}
