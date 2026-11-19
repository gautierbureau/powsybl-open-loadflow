/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.ac.equations.vector;

import com.powsybl.openloadflow.ac.equations.AcVariableType;
import com.powsybl.openloadflow.equations.EquationTermArray;
import com.powsybl.openloadflow.equations.Variable;
import com.powsybl.openloadflow.equations.VariableSet;

import java.util.Objects;

/**
 * @author Gautier Bureau {@literal <gautier.bureau at rte-france.com>}
 */
public abstract class AbstractShuntCompensatorEquationTermArrayEvaluator implements EquationTermArray.Evaluator<AcVariableType> {

    protected final AcShuntVector shuntVector;

    protected final AcBusVector busVector;

    protected final VariableSet<AcVariableType> variableSet;

    protected AbstractShuntCompensatorEquationTermArrayEvaluator(AcShuntVector shuntVector, AcBusVector busVector, VariableSet<AcVariableType> variableSet) {
        this.shuntVector = Objects.requireNonNull(shuntVector);
        this.busVector = Objects.requireNonNull(busVector);
        this.variableSet = Objects.requireNonNull(variableSet);
    }

    @Override
    public boolean isDisabled(int shuntNum) {
        return shuntVector.disabled[shuntNum];
    }

    public double v(int shuntNum) {
        return busVector.v[shuntVector.busNum[shuntNum]];
    }

    public double b(int shuntNum) {
        return shuntVector.b[shuntNum];
    }

    public Variable<AcVariableType> getVVar(int shuntNum) {
        return variableSet.getVariable(shuntVector.busNum[shuntNum], AcVariableType.BUS_V);
    }

    /** The susceptance of the shunt as a variable, or null when it is not one. */
    public Variable<AcVariableType> getBVar(int shuntNum) {
        return shuntVector.deriveB[shuntNum] ? variableSet.getVariable(shuntNum, AcVariableType.SHUNT_B) : null;
    }
}
