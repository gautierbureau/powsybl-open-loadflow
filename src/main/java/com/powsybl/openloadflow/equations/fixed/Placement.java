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
 * Which variable an equation is paired with: its column is that variable's slot. The only model knowledge a
 * {@link FixedEquationSystem} needs.
 *
 * @author Gautier Bureau {@literal <gautier.bureau at rte-france.com>}
 */
public interface Placement<V extends Enum<V> & Quantity, E extends Enum<E> & Quantity> {

    /**
     * The type of the variable, of the equation's own element, that an equation of this type is paired with, or null
     * when it has none: the equation then takes a slot that the active equations leave free.
     */
    V variableType(E equationType);

    /**
     * True for an equation that fixes its variable ({@code x = target}). When two active equations are paired with one
     * variable, this one keeps the slot and the other takes a free one.
     */
    boolean fixesVariable(E equationType);
}
