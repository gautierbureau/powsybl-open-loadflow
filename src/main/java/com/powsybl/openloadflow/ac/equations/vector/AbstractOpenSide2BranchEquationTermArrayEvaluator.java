/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.ac.equations.vector;

import com.powsybl.openloadflow.ac.equations.AcVariableType;
import com.powsybl.openloadflow.equations.Derivative;
import com.powsybl.openloadflow.equations.Variable;
import com.powsybl.openloadflow.equations.VariableSet;

import java.util.List;

/**
 * The flow left at side 1 of the branches open at side 2, as a term array: values and derivatives are read from
 * {@link AcBranchVector}, which {@link AcNetworkVector} computes at each state update with the formulas of the term
 * classes. It only depends on the voltage magnitude of the bus at side 1.
 *
 * @author Gautier Bureau {@literal <gautier.bureau at rte-france.com>}
 */
public abstract class AbstractOpenSide2BranchEquationTermArrayEvaluator extends AbstractBranchEquationTermArrayEvaluator {

    protected AbstractOpenSide2BranchEquationTermArrayEvaluator(AcBranchVector branchVector, VariableSet<AcVariableType> variableSet) {
        super(branchVector, variableSet);
    }

    public Variable<AcVariableType> getV1Var(int branchNum) {
        return variableSet.getVariable(branchVector.bus1Num[branchNum], AcVariableType.BUS_V);
    }

    @Override
    public List<Derivative<AcVariableType>> getDerivatives(int branchNum) {
        return List.of(new Derivative<>(getV1Var(branchNum), 0));
    }
}
