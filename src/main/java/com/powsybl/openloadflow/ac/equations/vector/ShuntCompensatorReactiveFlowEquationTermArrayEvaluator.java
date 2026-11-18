/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.ac.equations.vector;

import com.powsybl.math.matrix.DenseMatrix;
import com.powsybl.openloadflow.ac.equations.AcVariableType;
import com.powsybl.openloadflow.ac.equations.ShuntCompensatorReactiveFlowEquationTerm;
import com.powsybl.openloadflow.equations.Derivative;
import com.powsybl.openloadflow.equations.VariableSet;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The reactive power of every shunt, on arrays: the formulas are those of {@link ShuntCompensatorReactiveFlowEquationTerm}.
 *
 * @author Gautier Bureau {@literal <gautier.bureau at rte-france.com>}
 */
public class ShuntCompensatorReactiveFlowEquationTermArrayEvaluator extends AbstractShuntCompensatorEquationTermArrayEvaluator {

    public ShuntCompensatorReactiveFlowEquationTermArrayEvaluator(AcShuntVector shuntVector, AcBusVector busVector, VariableSet<AcVariableType> variableSet) {
        super(shuntVector, busVector, variableSet);
    }

    @Override
    public String getName() {
        return "ac_q_array_shunt";
    }

    @Override
    public double calculateSensi(int shuntNum, DenseMatrix dx, int column) {
        Objects.requireNonNull(dx);
        double dv = dx.get(busVector.vRow[shuntVector.busNum[shuntNum]], column);
        int bRow = shuntVector.bRow[shuntNum];
        double db = bRow != -1 ? dx.get(bRow, column) : 0;
        return ShuntCompensatorReactiveFlowEquationTerm.calculateSensi(v(shuntNum), b(shuntNum), dv, db);
    }

    @Override
    public double[] eval() {
        return shuntVector.q;
    }

    @Override
    public double eval(int shuntNum) {
        return shuntVector.q[shuntNum];
    }

    @Override
    public double[][] evalDer() {
        return new double[][] {
            shuntVector.dqdv,
            shuntVector.dqdb
        };
    }

    @Override
    public List<Derivative<AcVariableType>> getDerivatives(int shuntNum) {
        List<Derivative<AcVariableType>> derivatives = new ArrayList<>(2);
        derivatives.add(new Derivative<>(getVVar(shuntNum), 0));
        if (shuntVector.deriveB[shuntNum]) {
            derivatives.add(new Derivative<>(getBVar(shuntNum), 1));
        }
        return derivatives;
    }
}
