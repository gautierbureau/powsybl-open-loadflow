/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.ac.outerloop;

import org.junit.jupiter.api.Test;

import static com.powsybl.openloadflow.ac.outerloop.AutomationSystemDecisions.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * @author Gautier Bureau {@literal <gautier.bureau at rte-france.com>}
 */
class AutomationSystemDecisionsTest {

    @Test
    void trippings() {
        assertEquals(DECISION_NONE, trippingDecision(1.0, 1.0, true, false, true));
        assertEquals(DECISION_OPEN, trippingDecision(1.1, 1.0, true, false, true));
        assertEquals(DECISION_CLOSE, trippingDecision(1.1, 1.0, false, true, false));
        // already as the tripping wants it
        assertEquals(DECISION_NONE, trippingDecision(1.1, 1.0, true, true, false));
        // enabled but open at one side: neither opened nor closed
        assertEquals(DECISION_NONE, trippingDecision(1.1, 1.0, true, false, false));
    }

    @Test
    void firstThatApplies() {
        double[] threshold = {2.0, 1.0, 0.5};
        boolean[] branchOpen = {true, true, false};
        boolean[] disabled = {false, false, true};
        boolean[] inService = {true, true, false};
        assertEquals(1, selectTripping(1.5, 0, 3, threshold, branchOpen, disabled, inService));
        assertEquals(0, selectTripping(2.5, 0, 3, threshold, branchOpen, disabled, inService));
        assertEquals(-1, selectTripping(0.4, 0, 3, threshold, branchOpen, disabled, inService));
    }
}
