/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.ac.equations.vector;

import com.powsybl.openloadflow.ac.equations.AcVariableType;
import com.powsybl.openloadflow.equations.StateVector;
import com.powsybl.openloadflow.equations.Variable;

import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * {@link AcVariableIndex} over a fixed set of variables and a state vector owned by the caller.
 *
 * @author Gautier Bureau {@literal <gautier.bureau at rte-france.com>}
 */
class FixedAcVariableIndex implements AcVariableIndex {

    private final List<Variable<AcVariableType>> variables;
    private final StateVector stateVector;

    FixedAcVariableIndex(Collection<Variable<AcVariableType>> variables, StateVector stateVector) {
        this.variables = List.copyOf(Objects.requireNonNull(variables));
        this.stateVector = Objects.requireNonNull(stateVector);
        for (Variable<AcVariableType> variable : this.variables) {
            if (variable.getRow() == -1) {
                throw new IllegalArgumentException("Variable " + variable + " has no row in the state vector");
            }
        }
    }

    @Override
    public Collection<Variable<AcVariableType>> getVariablesWithRow() {
        return variables;
    }

    @Override
    public StateVector getStateVector() {
        return stateVector;
    }

    @Override
    public void addListener(Listener listener) {
        Objects.requireNonNull(listener);
        // the set of variables is fixed, so there is never anything to notify
    }
}
