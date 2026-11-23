/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.equations.fixed;

/**
 * The members of a {@link FixedStructure} that the tests of the other packages read.
 *
 * @author Gautier Bureau {@literal <gautier.bureau at rte-france.com>}
 */
public final class FixedStructures {

    private FixedStructures() {
    }

    public static int[] getRows(FixedStructure<?, ?> structure, int equation) {
        return structure.getRows(equation);
    }

    public static boolean dependsOnNow(FixedStructure<?, ?> structure, int equation, int position) {
        return structure.dependsOnNow(equation, position);
    }
}
