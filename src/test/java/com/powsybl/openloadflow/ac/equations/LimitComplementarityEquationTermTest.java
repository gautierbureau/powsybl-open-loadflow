/*
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.ac.equations;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * @author Gautier Bureau {@literal <gautier.bureau at rte-france.com>}
 */
class LimitComplementarityEquationTermTest {

    private static final double MU = 0.05;

    @Test
    void derivativesMatchCentralDifferences() {
        double h = 1e-7;
        for (double a : new double[] {-0.3, 0, 0.01, 0.2, 5}) {
            for (double b : new double[] {0, 0.004, 0.1, 2}) {
                double fdA = (LimitComplementarityEquationTerm.phi(a + h, b, MU) - LimitComplementarityEquationTerm.phi(a - h, b, MU)) / (2 * h);
                double fdB = (LimitComplementarityEquationTerm.phi(a, b + h, MU) - LimitComplementarityEquationTerm.phi(a, b - h, MU)) / (2 * h);
                assertEquals(fdA, LimitComplementarityEquationTerm.dphida(a, b, MU), 1e-6, "dphi/da at " + a + ", " + b);
                assertEquals(fdB, LimitComplementarityEquationTerm.dphidb(a, b, MU), 1e-6, "dphi/db at " + a + ", " + b);
            }
        }
    }

    @Test
    void weighsTheHeadroomAndTheSlackByTheDistanceToTheSwitch() {
        // far inside the limit, no slack (PV): the row holds the slack, not the reactive generation
        assertEquals(1, LimitComplementarityEquationTerm.dphidb(2, 0, MU), 1e-12);
        assertEquals(0, LimitComplementarityEquationTerm.dphida(2, 0, MU), 1e-3);
        // at the limit with a large slack (PQ): the row holds the reactive generation
        assertEquals(1, LimitComplementarityEquationTerm.dphida(0, 2, MU), 1e-12);
        assertEquals(0, LimitComplementarityEquationTerm.dphidb(0, 2, MU), 1e-3);
        // at the switch: both, equally
        assertEquals(1, LimitComplementarityEquationTerm.dphida(0, 0, MU), 1e-12);
        assertEquals(1, LimitComplementarityEquationTerm.dphidb(0, 0, MU), 1e-12);
        // the residual vanishes on the smoothed complementarity curve a b = mu^2
        assertEquals(0, LimitComplementarityEquationTerm.phi(0.1, MU * MU / 0.1, MU), 1e-12);
    }

    @Test
    void doesNotOverflowOnAnUnboundedLimit() {
        assertTrue(Double.isFinite(LimitComplementarityEquationTerm.phi(Double.MAX_VALUE / 2, 0.1, MU)));
        assertTrue(Double.isFinite(LimitComplementarityEquationTerm.dphida(Double.MAX_VALUE / 2, 0.1, MU)));
    }

    @Test
    void rejectsANonPositiveSmoothing() {
        assertThrows(IllegalArgumentException.class, () -> new LimitsSmoothing(0, LimitsSmoothing.REACTIVE_DEFAULT_VOLTAGE_SCALE));
        assertThrows(IllegalArgumentException.class, () -> new LimitsSmoothing(0.05, -1));
    }
}
