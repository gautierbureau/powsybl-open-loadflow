/**
 * Copyright (c) 2025, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.dc.equations.vector;

import com.powsybl.math.matrix.DenseMatrix;
import com.powsybl.openloadflow.dc.equations.DcVariableType;
import com.powsybl.openloadflow.equations.Derivative;
import com.powsybl.openloadflow.equations.EquationTermArray;
import com.powsybl.openloadflow.equations.VariableSet;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Base class for the vectorized DC closed-branch active power flow terms. DC flows are affine in the
 * bus angles and phase shift, so the partial derivatives (held in {@link DcBranchVector}) are constant.
 *
 * @author Claude
 */
public abstract class AbstractClosedBranchDcFlowEquationTermArrayEvaluator implements EquationTermArray.Evaluator<DcVariableType> {

    protected final DcBranchVector branchVector;

    protected final VariableSet<DcVariableType> variableSet;

    protected AbstractClosedBranchDcFlowEquationTermArrayEvaluator(DcBranchVector branchVector, VariableSet<DcVariableType> variableSet) {
        this.branchVector = Objects.requireNonNull(branchVector);
        this.variableSet = Objects.requireNonNull(variableSet);
    }

    @Override
    public boolean isDisabled(int branchNum) {
        return branchVector.disabled[branchNum];
    }

    @Override
    public List<Derivative<DcVariableType>> getDerivatives(int branchNum) {
        int bus1Num = branchVector.bus1Num[branchNum];
        int bus2Num = branchVector.bus2Num[branchNum];
        List<Derivative<DcVariableType>> derivatives = new ArrayList<>(3);
        derivatives.add(new Derivative<>(variableSet.getVariable(bus1Num, DcVariableType.BUS_PHI), 0));
        derivatives.add(new Derivative<>(variableSet.getVariable(bus2Num, DcVariableType.BUS_PHI), 1));
        if (branchVector.deriveA1[branchNum]) {
            derivatives.add(new Derivative<>(variableSet.getVariable(branchNum, DcVariableType.BRANCH_ALPHA1), 2));
        }
        return derivatives;
    }

    // partial derivatives (constant), ordered as in getDerivatives: [dph1, dph2, da1]
    protected abstract double[] dph1();

    protected abstract double[] dph2();

    protected abstract double[] da1();

    @Override
    public double calculateSensi(int branchNum, DenseMatrix dx, int column) {
        Objects.requireNonNull(dx);
        double dph1 = dx.get(branchVector.ph1Row[branchNum], column);
        double dph2 = dx.get(branchVector.ph2Row[branchNum], column);
        int a1Row = branchVector.a1Row[branchNum];
        double da1 = a1Row != -1 ? dx.get(a1Row, column) : 0;
        return dph1()[branchNum] * dph1 + dph2()[branchNum] * dph2 + da1()[branchNum] * da1;
    }

    @Override
    public double[][] evalDer() {
        return new double[][] {
            dph1(),
            dph2(),
            da1()
        };
    }
}
