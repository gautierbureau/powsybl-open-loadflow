/**
 * Copyright (c) 2025, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.dc.equations.vector;

import com.powsybl.openloadflow.dc.equations.DcVariableType;
import com.powsybl.openloadflow.equations.VariableSet;

/**
 * @author Claude
 */
public class ClosedBranchSide1DcFlowEquationTermArrayEvaluator extends AbstractClosedBranchDcFlowEquationTermArrayEvaluator {

    public ClosedBranchSide1DcFlowEquationTermArrayEvaluator(DcBranchVector branchVector, VariableSet<DcVariableType> variableSet) {
        super(branchVector, variableSet);
    }

    @Override
    public String getName() {
        return "dc_p_array_closed_1";
    }

    @Override
    protected double[] dph1() {
        return branchVector.dp1dph1;
    }

    @Override
    protected double[] dph2() {
        return branchVector.dp1dph2;
    }

    @Override
    protected double[] da1() {
        return branchVector.dp1da1;
    }

    @Override
    public double rhs(int branchNum) {
        return branchVector.rhs1[branchNum];
    }

    @Override
    public double[] eval() {
        return branchVector.p1;
    }

    @Override
    public double eval(int branchNum) {
        return branchVector.p1[branchNum];
    }
}
