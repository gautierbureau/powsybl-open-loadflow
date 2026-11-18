/**
 * Copyright (c) 2025, Coreso SA (https://www.coreso.eu/) and TSCNET Services GmbH (https://www.tscnet.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.ac.equations.fastdecoupled;

import com.powsybl.openloadflow.ac.equations.AcVariableType;
import com.powsybl.openloadflow.ac.equations.OpenBranchSide2ReactiveFlowEquationTerm;
import com.powsybl.openloadflow.ac.equations.vector.OpenBranchSide2ReactiveFlowEquationTermArrayEvaluator;
import com.powsybl.openloadflow.equations.Variable;
import net.jafama.FastMath;

import java.util.Objects;

import static com.powsybl.openloadflow.ac.equations.OpenBranchSide2ReactiveFlowEquationTerm.dq1dv1;

/**
 * @author Jeanne Archambault {@literal <jeanne.archambault at artelys.com>}
 */
public class OpenBranchSide2ReactiveFlowFastDecoupledEquationTerm implements FastDecoupledEquationTerm {

    private final Variable<AcVariableType> v1Var;

    private final double y;
    private final double ksi;
    private final double b1;
    private final double g2;
    private final double b2;
    private final double r1;

    public OpenBranchSide2ReactiveFlowFastDecoupledEquationTerm(OpenBranchSide2ReactiveFlowEquationTerm openBranchSide2ReactiveFlowEquationTerm) {
        v1Var = openBranchSide2ReactiveFlowEquationTerm.getV1Var();
        y = openBranchSide2ReactiveFlowEquationTerm.y();
        ksi = openBranchSide2ReactiveFlowEquationTerm.ksi();
        b1 = openBranchSide2ReactiveFlowEquationTerm.b1();
        g2 = openBranchSide2ReactiveFlowEquationTerm.g2();
        b2 = openBranchSide2ReactiveFlowEquationTerm.b2();
        r1 = openBranchSide2ReactiveFlowEquationTerm.r1();
    }

    public OpenBranchSide2ReactiveFlowFastDecoupledEquationTerm(OpenBranchSide2ReactiveFlowEquationTermArrayEvaluator openBranchSide2ReactiveFlowEvaluator, int branchNum) {
        v1Var = openBranchSide2ReactiveFlowEvaluator.getV1Var(branchNum);
        y = openBranchSide2ReactiveFlowEvaluator.y(branchNum);
        ksi = openBranchSide2ReactiveFlowEvaluator.ksi(branchNum);
        b1 = openBranchSide2ReactiveFlowEvaluator.b1(branchNum);
        g2 = openBranchSide2ReactiveFlowEvaluator.g2(branchNum);
        b2 = openBranchSide2ReactiveFlowEvaluator.b2(branchNum);
        r1 = openBranchSide2ReactiveFlowEvaluator.r1(branchNum);
    }

    public double derFastDecoupled(Variable<AcVariableType> variable) {
        Objects.requireNonNull(variable);
        if (variable.equals(v1Var)) {
            return dq1dv1(y, FastMath.cos(ksi), FastMath.sin(ksi), b1, g2, b2, 1, r1);
        } else {
            throw new IllegalStateException("Unknown variable: " + variable);
        }
    }
}
