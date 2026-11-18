/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.equations.fixed;

import com.powsybl.openloadflow.equations.EquationSystem;
import com.powsybl.openloadflow.equations.EquationVector;
import com.powsybl.openloadflow.equations.Quantity;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * OLF's equation vector, computed from the arrays of a fixed equation system: the one the Jacobian solves on, so that
 * the structure compiled for the matrix also gives the mismatches. It holds the same values as an
 * {@link EquationVector}, to the last bit. Should the structure not hold every active equation, the vector is
 * computed as OLF computes it.
 *
 * @author Gautier Bureau {@literal <gautier.bureau at rte-france.com>}
 */
public class FixedEquationVector<V extends Enum<V> & Quantity, E extends Enum<E> & Quantity> extends EquationVector<V, E> {

    private final Supplier<FixedEquationSystemIndex<V, E>> fixed;

    private int fallbackCount;

    /** The fixed equation system is asked for at the first evaluation: it is the one of the Jacobian, created when needed. */
    public FixedEquationVector(EquationSystem<V, E> equationSystem, Supplier<FixedEquationSystemIndex<V, E>> fixed) {
        super(equationSystem);
        this.fixed = Objects.requireNonNull(fixed);
    }

    @Override
    protected void updateArray(double[] array) {
        if (array.length != equationSystem.getIndex().getColumnCount()) {
            throw new IllegalArgumentException("Bad equation vector length: " + array.length);
        }
        if (fixed.get().evalEquations(array) != array.length) {
            fallbackCount++;
            super.updateArray(array);
        }
    }

    /** How many times the vector was computed as OLF computes it. */
    public int getFallbackCount() {
        return fallbackCount;
    }
}
