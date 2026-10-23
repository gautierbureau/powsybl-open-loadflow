/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.ac.equations.vector;

import com.powsybl.openloadflow.ac.equations.AcEquationType;
import com.powsybl.openloadflow.ac.equations.AcVariableType;
import com.powsybl.openloadflow.equations.EquationSystem;
import com.powsybl.openloadflow.equations.StateVector;
import com.powsybl.openloadflow.equations.Variable;

import java.util.Collection;

/**
 * The variables and the state an {@link AcNetworkVector} reads.
 * <p>
 * This is what the vectorized network needs from an {@link EquationSystem}, and nothing more: which variables
 * have a row in the state vector, the state vector itself, and a notification when the set of variables changes.
 * Keeping it separate lets a solver that does not build its rows through an {@link EquationSystem} (a weighted
 * least squares state estimator, whose Jacobian is rectangular) reuse the vectorized branch flows and derivatives.
 *
 * @author Gautier Bureau {@literal <gautier.bureau at rte-france.com>}
 */
public interface AcVariableIndex {

    interface Listener {

        /**
         * Called when variables have been added to or removed from the index, or their rows changed.
         */
        void onVariablesChange();
    }

    /**
     * The variables that currently have a row in the state vector.
     */
    Collection<Variable<AcVariableType>> getVariablesWithRow();

    StateVector getStateVector();

    void addListener(Listener listener);

    /**
     * The index of an equation system, as used by the load flow.
     */
    static AcVariableIndex of(EquationSystem<AcVariableType, AcEquationType> equationSystem) {
        return new EquationSystemAcVariableIndex(equationSystem);
    }

    /**
     * A fixed set of variables, whose rows have already been set, over a state vector owned by the caller.
     * The set never changes, so listeners are never notified.
     */
    static AcVariableIndex of(Collection<Variable<AcVariableType>> variables, StateVector stateVector) {
        return new FixedAcVariableIndex(variables, stateVector);
    }
}
