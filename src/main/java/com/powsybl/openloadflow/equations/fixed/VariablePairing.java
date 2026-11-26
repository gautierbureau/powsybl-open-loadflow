/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.equations.fixed;

import com.powsybl.openloadflow.equations.Quantity;

/**
 * Which variable an equation is paired with: its column is that variable's position. The only model knowledge a
 * {@link FixedEquationSystemIndex} needs.
 *
 * @author Gautier Bureau {@literal <gautier.bureau at rte-france.com>}
 */
public interface VariablePairing<V extends Enum<V> & Quantity, E extends Enum<E> & Quantity> {

    /**
     * The type of the variable, of the equation's own element, that an equation of this type is paired with, or null
     * when it has none: the equation then takes a position that the active equations leave free.
     */
    V variableType(E equationType);

    /**
     * True for an equation that fixes its variable ({@code x = target}). When two active equations are paired with one
     * variable, this one keeps the position and the other takes a free one.
     */
    boolean fixesVariable(E equationType);

    /**
     * The positions an equation may take besides its own, each given as the variable of the position: where a balance goes
     * when the equation that fixes its variable holds its position. They are in the matrix structure from the start, so that it
     * does not grow the first time the equation is paired there. None by default: the matrix structure then grows as the
     * pairings are met.
     */
    default void forEachControllerVariable(ControllerVariableConsumer<V, E> consumer) {
    }

    /**
     * The kind of a variable. An equation that would leave a zero on the diagonal where it is moves to the position of
     * another variable of the kind of its own: there its derivative is as strong as on its own position. One kind by
     * default.
     */
    default int kind(V variableType) {
        return 0;
    }

    /** An equation, by type and element, and the variable of a position it may take, by type and element. */
    @FunctionalInterface
    interface ControllerVariableConsumer<V extends Enum<V> & Quantity, E extends Enum<E> & Quantity> {

        void accept(E equationType, int equationElementNum, V variableType, int variableElementNum);
    }
}
