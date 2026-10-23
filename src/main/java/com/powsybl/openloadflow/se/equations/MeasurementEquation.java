/**
 * Copyright (c) 2026, Elia Group (https://www.eliagroup.eu)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.se.equations;

import com.powsybl.openloadflow.ac.equations.AcVariableType;
import com.powsybl.openloadflow.equations.Variable;

import java.util.Collection;

/**
 * One row of a measurement model: the function h_i(x) belonging to a single meter,
 * and with it one row of the measurement Jacobian H.
 *
 * @author Šime Pavlić {@literal <sime.pavlic at kickstage.com>}
 */
public interface MeasurementEquation {

    /** Predicted meter reading at the current state, per unit. */
    double eval();

    /** State variables this prediction depends on. */
    Collection<Variable<AcVariableType>> variables();

    /** Partial derivative of the prediction with respect to one state variable. */
    double der(Variable<AcVariableType> variable);

    /** Receives the partial derivatives of one equation, one variable at a time. */
    @FunctionalInterface
    interface DerivativeConsumer {

        void accept(Variable<AcVariableType> variable, double value);
    }

    /**
     * Passes every variable of {@link #variables()} to the consumer once, with its partial derivative: the whole
     * Jacobian row in one call. The result is the same as calling {@link #der(Variable)} for each variable, which is
     * what this default does; an equation summing several terms overrides it to accumulate them in a single pass.
     */
    default void derivatives(DerivativeConsumer consumer) {
        for (Variable<AcVariableType> variable : variables()) {
            consumer.accept(variable, der(variable));
        }
    }
}
