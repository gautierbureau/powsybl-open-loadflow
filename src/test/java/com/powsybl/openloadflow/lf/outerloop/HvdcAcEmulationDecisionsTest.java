/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.lf.outerloop;

import org.junit.jupiter.api.Test;

import static com.powsybl.openloadflow.lf.outerloop.HvdcAcEmulationDecisions.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * @author Gautier Bureau {@literal <gautier.bureau at rte-france.com>}
 */
class HvdcAcEmulationDecisionsTest {

    @Test
    void linear() {
        assertEquals(DECISION_NONE, decision(STATUS_LINEAR_MODE, 1.0, -0.9, 1.0, 1.0));
        assertEquals(DECISION_TO_SATURATION_FROM_CS1_TO_CS2, decision(STATUS_LINEAR_MODE, 1.1, -1.0, 1.0, 1.0));
        assertEquals(DECISION_TO_SATURATION_FROM_CS2_TO_CS1, decision(STATUS_LINEAR_MODE, -1.0, 1.1, 1.0, 1.0));
        assertEquals(DECISION_NONE, decision(STATUS_LINEAR_MODE, -0.5, 0.5, 1.0, 1.0));
    }

    @Test
    void saturated() {
        // back within a limit
        assertEquals(DECISION_TO_LINEAR_MODE, decision(STATUS_SATURATION_MODE_FROM_CS1_TO_CS2, 0.5, -0.5, 1.0, 1.0));
        // at the limit: stays
        assertEquals(DECISION_NONE, decision(STATUS_SATURATION_MODE_FROM_CS1_TO_CS2, 1.0, -1.0, 1.0, 1.0));
        // reversed
        assertEquals(DECISION_TO_SATURATION_FROM_CS2_TO_CS1, decision(STATUS_SATURATION_MODE_FROM_CS1_TO_CS2, -1.5, 1.5, 1.0, 1.0));
        assertEquals(DECISION_TO_SATURATION_FROM_CS1_TO_CS2, decision(STATUS_SATURATION_MODE_FROM_CS2_TO_CS1, 1.5, -1.5, 1.0, 1.0));
        assertEquals(DECISION_NONE, decision(STATUS_FROZEN, 5, -5, 1.0, 1.0));
    }
}
