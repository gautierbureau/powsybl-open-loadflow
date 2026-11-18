/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.ac.equations;

import com.powsybl.openloadflow.equations.fixed.FixedEquationSystem;
import com.powsybl.openloadflow.equations.fixed.Placement;

/**
 * The pairing of the AC equations for a {@link FixedEquationSystem}: a balance with the angle or the magnitude of its bus, a control equation with the
 * variable it frees or fixes. The equations OLF toggles in pairs share a variable, so they share a slot.
 *
 * <p>The equations left without a pairing here find their slot by themselves: the reactive balance of a bus whose
 * voltage is held by a remote generator, a transformer or a shunt takes the slot that control frees, and so does
 * {@code BRANCH_TARGET_Q1} and {@code BRANCH_TARGET_Q2}.
 *
 * @author Gautier Bureau {@literal <gautier.bureau at rte-france.com>}
 */
public final class AcPlacement implements Placement<AcVariableType, AcEquationType> {

    public static final AcPlacement INSTANCE = new AcPlacement();

    private AcPlacement() {
    }

    @Override
    public AcVariableType variableType(AcEquationType equationType) {
        return switch (equationType) {
            case BUS_TARGET_P, BUS_TARGET_PHI, BUS_DISTR_SLACK_P -> AcVariableType.BUS_PHI;
            case BUS_TARGET_Q, BUS_TARGET_V, DISTR_Q -> AcVariableType.BUS_V;
            case SHUNT_TARGET_B, DISTR_SHUNT_B -> AcVariableType.SHUNT_B;
            case BRANCH_TARGET_ALPHA1, BRANCH_TARGET_P1, BRANCH_TARGET_P2 -> AcVariableType.BRANCH_ALPHA1;
            case BRANCH_TARGET_RHO1, DISTR_RHO -> AcVariableType.BRANCH_RHO1;
            case DUMMY_TARGET_P, ZERO_PHI -> AcVariableType.DUMMY_P;
            case DUMMY_TARGET_Q, ZERO_V -> AcVariableType.DUMMY_Q;
            default -> null;
        };
    }

    @Override
    public boolean fixesVariable(AcEquationType equationType) {
        return switch (equationType) {
            case BUS_TARGET_P, BUS_TARGET_Q, BRANCH_TARGET_P1, BRANCH_TARGET_P2, ZERO_PHI, ZERO_V -> false;
            default -> true;
        };
    }
}
