/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.ac.equations.vector;

import com.powsybl.openloadflow.ac.equations.AcVariableType;
import com.powsybl.openloadflow.ac.equations.ShuntCompensatorActiveFlowEquationTerm;
import com.powsybl.openloadflow.equations.Derivative;
import com.powsybl.openloadflow.equations.VariableSet;

import java.util.List;

/**
 * The active power of every shunt, on arrays: the formulas are those of {@link ShuntCompensatorActiveFlowEquationTerm}.
 *
 * @author Gautier Bureau {@literal <gautier.bureau at rte-france.com>}
 */
public class ShuntCompensatorActiveFlowEquationTermArrayEvaluator extends AbstractShuntCompensatorEquationTermArrayEvaluator {

    public ShuntCompensatorActiveFlowEquationTermArrayEvaluator(AcShuntVector shuntVector, AcBusVector busVector, VariableSet<AcVariableType> variableSet) {
        super(shuntVector, busVector, variableSet);
    }

    @Override
    public String getName() {
        return "ac_p_array_shunt";
    }

    @Override
    public double[] eval() {
        return shuntVector.p;
    }

    @Override
    public double eval(int shuntNum) {
        return shuntVector.p[shuntNum];
    }

    @Override
    public double[][] evalDer() {
        return new double[][] {
            shuntVector.dpdv
        };
    }

    @Override
    public List<Derivative<AcVariableType>> getDerivatives(int shuntNum) {
        return List.of(new Derivative<>(getVVar(shuntNum), 0));
    }
}
