/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.ac.outerloop;

import org.junit.jupiter.api.Test;

import static com.powsybl.openloadflow.ac.outerloop.AcDcConverterPowerLimitsDecisions.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * @author Gautier Bureau {@literal <gautier.bureau at rte-france.com>}
 */
class AcDcConverterPowerLimitsDecisionsTest {

    @Test
    void selection() {
        boolean[] candidate = {true, true, false};
        double[] minP = {-1, -1, -1};
        double[] maxP = {1, 1, 1};
        // within the limits, or beyond by no more than the mismatch
        assertEquals(-1, selectConverter(3, candidate, new double[] {0.5, 1.0005, 5}, minP, maxP, 1e-3));
        // the furthest beyond a limit, a converter not controlling the DC voltage is ignored
        assertEquals(1, selectConverter(3, candidate, new double[] {1.25, -1.5, 5}, minP, maxP, 1e-3));
        // a tie keeps the first
        assertEquals(0, selectConverter(3, candidate, new double[] {1.5, -1.5, 5}, minP, maxP, 1e-3));
        assertEquals(-1.0, newTargetP(-1.5, -1, 1));
        assertEquals(1.0, newTargetP(1.5, -1, 1));
    }
}
