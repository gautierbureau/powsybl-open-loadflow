/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.lf.outerloop;

import com.powsybl.openloadflow.lf.util.ActivePowerDistribution;
import com.powsybl.openloadflow.util.PerUnit;
import org.junit.jupiter.api.Test;

import static com.powsybl.openloadflow.lf.outerloop.AreaInterchangeControlDecisions.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * @author Gautier Bureau {@literal <gautier.bureau at rte-france.com>}
 */
class AreaInterchangeControlDecisionsTest {

    @Test
    void mismatches() {
        assertEquals(0.5, interchangeMismatch(1.5, 1.0));
        assertEquals(0.5 + 0.25 * 2.0, interchangeMismatchWithSlack(1.5, 1.0, 0.25, 2.0));
        assertTrue(isWithinMaxMismatch(2 / PerUnit.SB, 2));
        assertFalse(isWithinMaxMismatch(Math.nextUp(2 / PerUnit.SB), 2));
        assertTrue(isWithinMaxMismatch(ActivePowerDistribution.P_RESIDUE_EPS, 0));
    }

    @Test
    void slackSpread() {
        assertEquals(-0.5 + 2 / PerUnit.SB, slackMargin(1.0, -0.5, 2));
        assertEquals(0.5 + 2 / PerUnit.SB, slackMargin(-1.0, -0.5, 2));
        assertEquals(0.25, areaShare(1.0, 0.25));
        // below the residue: a little more than it, with the mismatch's sign
        assertEquals(-1.01 * ActivePowerDistribution.P_RESIDUE_EPS, areaShare(-1e-6, 1e-3));
        assertTrue(isAboveResidue(-1));
        assertFalse(isAboveResidue(ActivePowerDistribution.P_RESIDUE_EPS));
        assertEquals(1.0 - 0.25 + 0.125, remainingAfter(1.0, 0.25, 0.125));
    }
}
