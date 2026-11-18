/**
 * Copyright (c) 2025, Coreso SA (https://www.coreso.eu/) and TSCNET Services GmbH (https://www.tscnet.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.ac.equations.fastdecoupled;

import com.powsybl.openloadflow.ac.equations.AcVariableType;
import com.powsybl.openloadflow.ac.equations.OpenBranchSide1ReactiveFlowEquationTerm;
import com.powsybl.openloadflow.ac.equations.vector.OpenBranchSide1ReactiveFlowEquationTermArrayEvaluator;
import com.powsybl.openloadflow.equations.Variable;
import net.jafama.FastMath;

import java.util.Objects;

import static com.powsybl.openloadflow.ac.equations.OpenBranchSide1ReactiveFlowEquationTerm.dq2dv2;

/**
 * @author Jeanne Archambault {@literal <jeanne.archambault at artelys.com>}
 */
public class OpenBranchSide1ReactiveFlowFastDecoupledEquationTerm implements FastDecoupledEquationTerm {

    private final Variable<AcVariableType> v2Var;

    private final double y;
    private final double ksi;
    private final double g1;
    private final double b1;
    private final double b2;

    public OpenBranchSide1ReactiveFlowFastDecoupledEquationTerm(OpenBranchSide1ReactiveFlowEquationTerm openBranchSide1ReactiveFlowEquationTerm) {
        v2Var = openBranchSide1ReactiveFlowEquationTerm.getV2Var();
        y = openBranchSide1ReactiveFlowEquationTerm.y();
        ksi = openBranchSide1ReactiveFlowEquationTerm.ksi();
        g1 = openBranchSide1ReactiveFlowEquationTerm.g1();
        b1 = openBranchSide1ReactiveFlowEquationTerm.b1();
        b2 = openBranchSide1ReactiveFlowEquationTerm.b2();
    }

    public OpenBranchSide1ReactiveFlowFastDecoupledEquationTerm(OpenBranchSide1ReactiveFlowEquationTermArrayEvaluator openBranchSide1ReactiveFlowEvaluator, int branchNum) {
        v2Var = openBranchSide1ReactiveFlowEvaluator.getV2Var(branchNum);
        y = openBranchSide1ReactiveFlowEvaluator.y(branchNum);
        ksi = openBranchSide1ReactiveFlowEvaluator.ksi(branchNum);
        g1 = openBranchSide1ReactiveFlowEvaluator.g1(branchNum);
        b1 = openBranchSide1ReactiveFlowEvaluator.b1(branchNum);
        b2 = openBranchSide1ReactiveFlowEvaluator.b2(branchNum);
    }

    public double derFastDecoupled(Variable<AcVariableType> variable) {
        Objects.requireNonNull(variable);
        if (variable.equals(v2Var)) {
            return dq2dv2(y, FastMath.cos(ksi), FastMath.sin(ksi), g1, b1, b2, 1);
        } else {
            throw new IllegalStateException("Unknown variable: " + variable);
        }
    }
}
