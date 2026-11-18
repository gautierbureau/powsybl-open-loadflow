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
public class HvdcAcEmulationSide2ActiveFlowEquationTermArrayEvaluator extends AbstractHvdcAcEmulationEquationTermArrayEvaluator {

    public HvdcAcEmulationSide2ActiveFlowEquationTermArrayEvaluator(AcHvdcVector hvdcVector, VariableSet<AcVariableType> variableSet) {
        super(hvdcVector, variableSet);
    }

    @Override
    public String getName() {
        return "ac_emulation_p_array_2";
    }

    @Override
    public double[] eval() {
        return hvdcVector.p2;
    }

    @Override
    public double eval(int hvdcNum) {
        return hvdcVector.p2[hvdcNum];
    }

    @Override
    public double[][] evalDer() {
        return new double[][] {
            hvdcVector.dp2dph1,
            hvdcVector.dp2dph2
        };
    }
}
