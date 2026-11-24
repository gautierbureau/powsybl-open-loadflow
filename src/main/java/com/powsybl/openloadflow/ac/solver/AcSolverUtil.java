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
import com.powsybl.openloadflow.equations.Variable;
import com.powsybl.openloadflow.network.LfNetwork;
import com.powsybl.openloadflow.network.util.PreviousValueVoltageInitializer;
import com.powsybl.openloadflow.network.util.VoltageInitializer;

/**
 * @author Geoffroy Jamgotchian {@literal <geoffroy.jamgotchian at rte-france.com>}
 */
public final class AcSolverUtil {

    private AcSolverUtil() {
    }

    /**
     * An array for a new state of the given size: the one of the state vector, which the new state replaces, when it
     * has that size, so that each solve does not allocate one. Nothing keeps the state array across a solve.
     */
    static double[] stateArray(EquationSystem<AcVariableType, AcEquationType> equationSystem, int size) {
        double[] current = equationSystem.getStateVector().get();
        return current != null && current.length == size ? current : new double[size];
    }

    public static void initStateVector(LfNetwork network, EquationSystem<AcVariableType, AcEquationType> equationSystem, VoltageInitializer initializer) {
        EquationSystemIndex<AcVariableType, AcEquationType> index = equationSystem.getIndex();
        double[] x = stateArray(equationSystem, index.getSortedVariablesToFind().size());
        // by type, from the rows and element numbers of the index: each variable is given its value at its row
        for (AcVariableType type : AcVariableType.values()) {
            int[] rows = index.getRowsOfType(type);
            int[] elementNums = index.getElementNumsOfType(type);
            for (int i = 0; i < rows.length; i++) {
                x[rows[i]] = initValue(network, type, elementNums[i], initializer);
            }
        }
        equationSystem.getStateVector().set(x);
    }

    /** The initial value of a variable. */
    static double initValue(LfNetwork network, Variable<AcVariableType> v, VoltageInitializer initializer) {
        return initValue(network, v.getType(), v.getElementNum(), initializer);
    }

    /** The initial value of the variable of a type and an element. */
    static double initValue(LfNetwork network, AcVariableType type, int elementNum, VoltageInitializer initializer) {
        switch (type) {
            case BUS_V:
                return initializer.getMagnitude(network.getBus(elementNum));

            case BUS_PHI:
                return initializer.getAngle(network.getBus(elementNum));

            case SHUNT_B:
                return network.getShunt(elementNum).getB();

            case BRANCH_ALPHA1:
                return network.getBranch(elementNum).getPiModel().getA1();

            case BRANCH_RHO1:
                return network.getBranch(elementNum).getPiModel().getR1();

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
                return initializer.getDcVoltage(network.getDcBus(elementNum));

            case CONV_P_AC:
                double p = network.getVoltageSourceConverter(elementNum).getPac();
                return Double.isNaN(p) || !(initializer instanceof PreviousValueVoltageInitializer) ? 0.0 : p;

            case CONV_Q_AC:
                double q = network.getVoltageSourceConverter(elementNum).getQac();
                return Double.isNaN(q) || !(initializer instanceof PreviousValueVoltageInitializer) ? 0.0 : q;
            default:
                throw new IllegalStateException("Unknown variable type " + type);
        }
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
