/**
 * Copyright (c) 2023, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.ac.solver;

import com.powsybl.openloadflow.ac.equations.AcEquationType;
import com.powsybl.openloadflow.ac.equations.AcVariableType;
import com.powsybl.openloadflow.equations.EquationSystem;
import com.powsybl.openloadflow.equations.EquationSystemIndex;
import com.powsybl.openloadflow.network.LfNetwork;

/**
 * @author Geoffroy Jamgotchian {@literal <geoffroy.jamgotchian at rte-france.com>}
 */
public final class AcSolverUtil {

    private AcSolverUtil() {
    }

    public static void updateNetwork(LfNetwork network, EquationSystem<AcVariableType, AcEquationType> equationSystem) {
        // update state variable, by type from the rows and element numbers of the index: the voltages and angles of
        // the buses straight into the arrays the network keeps them in, as LfBus.setV and setAngle would write them,
        // without going through each bus or each variable
        double[] state = equationSystem.getStateVector().get();
        EquationSystemIndex<AcVariableType, AcEquationType> index = equationSystem.getIndex();
        double[] busVoltages = network.getBusVoltages();
        double[] busNominalVoltages = network.getBusNominalVoltages();
        int[] rows = index.getRowsOfType(AcVariableType.BUS_V);
        int[] elementNums = index.getElementNumsOfType(AcVariableType.BUS_V);
        for (int i = 0; i < rows.length; i++) {
            busVoltages[elementNums[i]] = state[rows[i]] * busNominalVoltages[elementNums[i]];
        }
        double[] busAngles = network.getBusAngles();
        rows = index.getRowsOfType(AcVariableType.BUS_PHI);
        elementNums = index.getElementNumsOfType(AcVariableType.BUS_PHI);
        for (int i = 0; i < rows.length; i++) {
            busAngles[elementNums[i]] = state[rows[i]];
        }
        for (AcVariableType type : AcVariableType.values()) {
            if (type == AcVariableType.BUS_V || type == AcVariableType.BUS_PHI) {
                continue;
            }
            rows = index.getRowsOfType(type);
            elementNums = index.getElementNumsOfType(type);
            for (int i = 0; i < rows.length; i++) {
                updateVariable(network, type, elementNums[i], state[rows[i]]);
            }
        }
        network.onStateWrite();
    }

    /** Writes the value of a variable, other than a bus voltage or angle, into the network. */
    private static void updateVariable(LfNetwork network, AcVariableType type, int elementNum, double value) {
        switch (type) {
            case BUS_V_ZERO:
                network.getBus(elementNum).getAsym().setVz(value);
                break;

            case BUS_PHI_ZERO:
                network.getBus(elementNum).getAsym().setAngleZ(value);
                break;

            case BUS_V_NEGATIVE:
                network.getBus(elementNum).getAsym().setVn(value);
                break;

            case BUS_PHI_NEGATIVE:
                network.getBus(elementNum).getAsym().setAngleN(value);
                break;

            case SHUNT_B:
                network.getShunt(elementNum).setB(value);
                break;

            case BRANCH_ALPHA1:
                network.getBranch(elementNum).getPiModel().setA1(value);
                break;

            case BRANCH_RHO1:
                network.getBranch(elementNum).getPiModel().setR1(value);
                break;

            case DUMMY_P,
                 DUMMY_Q:
                // nothing to do
                break;

            case DC_BUS_V:
                network.getDcBus(elementNum).setV(value);
                break;

            case CONV_P_AC:
                network.getVoltageSourceConverter(elementNum).setPac(value);
                break;

            case CONV_Q_AC:
                network.getVoltageSourceConverter(elementNum).setQac(value);
                break;

            default:
                throw new IllegalStateException("Unknown variable type " + type);
        }
    }
}
