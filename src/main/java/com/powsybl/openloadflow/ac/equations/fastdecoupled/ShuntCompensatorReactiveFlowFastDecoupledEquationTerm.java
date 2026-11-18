/**
 * Copyright (c) 2025, Coreso SA (https://www.coreso.eu/) and TSCNET Services GmbH (https://www.tscnet.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.ac.equations.fastdecoupled;

import com.powsybl.openloadflow.ac.equations.AcVariableType;
import com.powsybl.openloadflow.ac.equations.ShuntCompensatorReactiveFlowEquationTerm;
import com.powsybl.openloadflow.ac.equations.vector.ShuntCompensatorReactiveFlowEquationTermArrayEvaluator;
import com.powsybl.openloadflow.equations.Variable;
import java.util.Objects;
import java.util.function.DoubleSupplier;

import static com.powsybl.openloadflow.ac.equations.ShuntCompensatorReactiveFlowEquationTerm.dqdv;

/**
 * @author Jeanne Archambault {@literal <jeanne.archambault at artelys.com>}
 */
public class ShuntCompensatorReactiveFlowFastDecoupledEquationTerm implements FastDecoupledEquationTerm {

    private final Variable<AcVariableType> vVar;

    private final Variable<AcVariableType> bVar;

    private final DoubleSupplier b;

    public ShuntCompensatorReactiveFlowFastDecoupledEquationTerm(ShuntCompensatorReactiveFlowEquationTerm shuntCompensatorReactiveFlowEquationTerm) {
        vVar = shuntCompensatorReactiveFlowEquationTerm.getVVar();
        bVar = shuntCompensatorReactiveFlowEquationTerm.getbVar();
        b = shuntCompensatorReactiveFlowEquationTerm::b;
    }

    public ShuntCompensatorReactiveFlowFastDecoupledEquationTerm(ShuntCompensatorReactiveFlowEquationTermArrayEvaluator shuntCompensatorReactiveFlowEvaluator, int shuntNum) {
        vVar = shuntCompensatorReactiveFlowEvaluator.getVVar(shuntNum);
        bVar = shuntCompensatorReactiveFlowEvaluator.getBVar(shuntNum);
        b = () -> shuntCompensatorReactiveFlowEvaluator.b(shuntNum);
    }

    private static double dqdbFastDecoupled(double v) {
        return -v;
    }

    public double derFastDecoupled(Variable<AcVariableType> variable) {
        Objects.requireNonNull(variable);
        if (variable.equals(vVar)) {
            return dqdv(1, b.getAsDouble());
        } else if (variable.equals(bVar)) {
            return dqdbFastDecoupled(1);
        } else {
            throw new IllegalStateException("Unknown variable: " + variable);
        }
    }
}
