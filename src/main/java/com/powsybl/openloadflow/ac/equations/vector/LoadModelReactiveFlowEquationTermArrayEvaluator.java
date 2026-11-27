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
public class LoadModelReactiveFlowEquationTermArrayEvaluator extends AbstractLoadModelEquationTermArrayEvaluator {

    public LoadModelReactiveFlowEquationTermArrayEvaluator(AcLoadVector loadVector, AcBusVector busVector, VariableSet<AcVariableType> variableSet) {
        super(loadVector, busVector, variableSet);
    }

    @Override
    public String getName() {
        return "ac_load_q_array";
    }

    @Override
    public double[] eval() {
        return loadVector.busQ;
    }

    @Override
    public double eval(int busNum) {
        return loadVector.busQ[busNum];
    }

    @Override
    public double[][] evalDer() {
        return new double[][] {
            loadVector.busDqdv
        };
    }
}
