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
import com.powsybl.openloadflow.equations.*;

import java.util.Collection;
import java.util.Objects;

/**
 * {@link AcVariableIndex} backed by the index of an {@link EquationSystem}.
 *
 * @author Gautier Bureau {@literal <gautier.bureau at rte-france.com>}
 */
class EquationSystemAcVariableIndex implements AcVariableIndex {

    private final EquationSystem<AcVariableType, AcEquationType> equationSystem;

    EquationSystemAcVariableIndex(EquationSystem<AcVariableType, AcEquationType> equationSystem) {
        this.equationSystem = Objects.requireNonNull(equationSystem);
    }

    @Override
    public Collection<Variable<AcVariableType>> getVariablesWithRow() {
        return equationSystem.getIndex().getSortedVariablesToFind();
    }

    @Override
    public StateVector getStateVector() {
        return equationSystem.getStateVector();
    }

    @Override
    public void addListener(Listener listener) {
        Objects.requireNonNull(listener);
        equationSystem.getIndex().addListener(new EquationSystemIndexListener<>() {
            @Override
            public void onVariableChange(Variable<AcVariableType> variable, ChangeType changeType) {
                listener.onVariablesChange();
            }

            @Override
            public void onEquationChange(SingleEquation<AcVariableType, AcEquationType> equation, ChangeType changeType) {
                // nothing to do
            }

            @Override
            public void onEquationTermChange(SingleEquationTerm<AcVariableType, AcEquationType> term) {
                // nothing to do
            }

            @Override
            public void onEquationArrayChange(EquationArray<AcVariableType, AcEquationType> equationArray, ChangeType changeType) {
                // nothing to do
            }

            @Override
            public void onEquationTermArrayChange(EquationTermArray<AcVariableType, AcEquationType> equationTermArray, int termNum, ChangeType changeType) {
                // nothing to do
            }

            @Override
            public void onEquationIndexOrderChanged() {
                // nothing to do
            }
        });
    }
}
