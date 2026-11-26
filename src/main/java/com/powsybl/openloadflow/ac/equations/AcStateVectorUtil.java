/**
 * Copyright (c) 2023, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.ac.equations;

import com.powsybl.openloadflow.equations.EquationSystem;
import com.powsybl.openloadflow.equations.Variable;
import com.powsybl.openloadflow.network.LfNetwork;
import com.powsybl.openloadflow.network.util.PreviousValueVoltageInitializer;
import com.powsybl.openloadflow.network.util.VoltageInitializer;

/**
 * @author Geoffroy Jamgotchian {@literal <geoffroy.jamgotchian at rte-france.com>}
 */
public final class AcStateVectorUtil {

    private AcStateVectorUtil() {
    }

    public static void initStateVector(LfNetwork network, EquationSystem<AcVariableType, AcEquationType> equationSystem, VoltageInitializer initializer) {
        double[] x = new double[equationSystem.getIndex().getSortedVariablesToFind().size()];
        for (Variable<AcVariableType> v : equationSystem.getIndex().getSortedVariablesToFind()) {
            x[v.getRow()] = initValue(network, v, initializer);
        }
        equationSystem.getStateVector().set(x);
    }

    /** The initial value of a variable. */
    public static double initValue(LfNetwork network, Variable<AcVariableType> v, VoltageInitializer initializer) {
        switch (v.getType()) {
            case BUS_V:
                return initializer.getMagnitude(network.getBus(v.getElementNum()));

            case BUS_PHI:
                return initializer.getAngle(network.getBus(v.getElementNum()));

            case SHUNT_B:
                return network.getShunt(v.getElementNum()).getB();

            case BRANCH_ALPHA1:
                return network.getBranch(v.getElementNum()).getPiModel().getA1();

            case BRANCH_RHO1:
                return network.getBranch(v.getElementNum()).getPiModel().getR1();

            case DUMMY_P,
                 DUMMY_Q,
                 BUS_PHI_ZERO,
                 BUS_PHI_NEGATIVE:
                return 0;

            case BUS_V_ZERO,
                 BUS_V_NEGATIVE:
                // when balanced, zero and negative sequence should be zero
                // v_zero and v_negative initially set to zero will bring a singularity to the Jacobian
                // We chose to set the initial value to a small one, but different from zero
                // By construction if the system does not carry any asymmetry in its structure,
                // the resolution of the system on the three sequences will bring a singularity
                // Therefore, if the system is balanced by construction, we should run a balanced load flow only
                return 0.1;

            case DC_BUS_V:
                return initializer.getDcVoltage(network.getDcBus(v.getElementNum()));

            case CONV_P_AC:
                double p = network.getVoltageSourceConverter(v.getElementNum()).getPac();
                return Double.isNaN(p) || !(initializer instanceof PreviousValueVoltageInitializer) ? 0.0 : p;

            case CONV_Q_AC:
                double q = network.getVoltageSourceConverter(v.getElementNum()).getQac();
                return Double.isNaN(q) || !(initializer instanceof PreviousValueVoltageInitializer) ? 0.0 : q;
            default:
                throw new IllegalStateException("Unknown variable type " + v.getType());
        }
    }
}
