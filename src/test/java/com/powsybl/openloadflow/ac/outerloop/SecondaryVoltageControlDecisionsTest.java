/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.ac.outerloop;

import org.junit.jupiter.api.Test;

import static com.powsybl.openloadflow.ac.outerloop.SecondaryVoltageControlDecisions.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * @author Gautier Bureau {@literal <gautier.bureau at rte-france.com>}
 */
class SecondaryVoltageControlDecisionsTest {

    @Test
    void k() {
        assertEquals(-1.0, qToK(-2, -2, 2));
        assertEquals(1.0, qToK(2, -2, 2));
        assertEquals(0.0, qToK(0, -2, 2));
        double[] ks = {-0.5, 0.25, 0.5};
        assertEquals(1.0, dkDiffMax(ks));
        assertEquals(0.25 / 3, kAverage(ks), 1e-15);
        assertFalse(allAtLimits(ks));
        assertTrue(allAtLimits(new double[] {1.5, 1.25}));
        assertTrue(allAtLimits(new double[] {-1.5, -1.25}));
    }

    @Test
    void adjust() {
        // away from the target, unless saturated in the direction to move
        assertTrue(shouldAdjust(0.01, 0, 0, false));
        assertFalse(shouldAdjust(0.01, 0.96, 0, false));
        assertTrue(shouldAdjust(-0.01, 0.96, 0, false));
        // at the target: only to align, and not when saturated or all at a limit
        assertFalse(shouldAdjust(0, 0, DK_DIFF_MAX_EPS, false));
        assertTrue(shouldAdjust(0, 0, 0.1, false));
        assertFalse(shouldAdjust(0, 0, 0.1, true));
        assertFalse(shouldAdjust(0, 0.96, 0.1, false));
    }

    @Test
    void stall() {
        assertEquals(0, noProgressCountAfter(false, 1, 1, 0, 0, 5));
        assertEquals(0, noProgressCountAfter(true, 0.5, 1, 1, 1, 1));
        assertEquals(2, noProgressCountAfter(true, 1, 1, 1, 1, 1));
        assertFalse(isStalled(1));
        assertTrue(isStalled(MAX_NO_PROGRESS_COUNT));
    }

    @Test
    void clipping() {
        // 400 kV: the largest step is 2 kV, 0.005 pu
        assertEquals(0.004, clippedStep(0.004, 400));
        assertEquals(2.0 / 400, clippedStep(0.01, 400));
        assertEquals(-2.0 / 400, clippedStep(-0.01, 400));
        assertTrue(isStepClipped(0.01, 400));
        assertFalse(isStepClipped(0.004, 400));
        assertEquals(1.2, clipToPlausibleRange(1.3, 0.8, 1.2));
        assertEquals(0.8, clipToPlausibleRange(0.7, 0.8, 1.2));
        assertTrue(isOutOfPlausibleRange(0.7, 0.8, 1.2));
        assertFalse(isOutOfPlausibleRange(1.2, 0.8, 1.2));
    }
}
