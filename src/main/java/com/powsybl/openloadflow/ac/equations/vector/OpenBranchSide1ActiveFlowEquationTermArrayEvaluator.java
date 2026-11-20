/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.ac.equations.vector;

import com.powsybl.openloadflow.ac.equations.AcVariableType;
import com.powsybl.openloadflow.equations.VariableSet;

/**
 * @author Gautier Bureau {@literal <gautier.bureau at rte-france.com>}
 */
public class OpenBranchSide1ActiveFlowEquationTermArrayEvaluator extends AbstractOpenSide1BranchEquationTermArrayEvaluator {

    public OpenBranchSide1ActiveFlowEquationTermArrayEvaluator(AcBranchVector branchVector, VariableSet<AcVariableType> variableSet) {
        super(branchVector, variableSet);
    }

    @Override
    public String getName() {
        return "ac_p_array_open_1";
    }

    @Override
    public double[] eval() {
        return branchVector.openP2;
    }

    @Override
    public double eval(int branchNum) {
        return branchVector.openP2[branchNum];
    }

    @Override
    public double[][] evalDer() {
        return new double[][] {
            branchVector.openDp2dv2
        };
    }
}
