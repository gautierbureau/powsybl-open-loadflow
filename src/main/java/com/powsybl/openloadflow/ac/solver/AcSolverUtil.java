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
import com.powsybl.openloadflow.equations.StateVector;
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
        double[] x = stateArray(equationSystem, equationSystem.getIndex().getSortedVariablesToFind().size());
        for (Variable<AcVariableType> v : equationSystem.getIndex().getSortedVariablesToFind()) {
            x[v.getRow()] = initValue(network, v, initializer);
        }
        equationSystem.getStateVector().set(x);
    }

    /** The initial value of a variable. */
    static double initValue(LfNetwork network, Variable<AcVariableType> v, VoltageInitializer initializer) {
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

    public static void updateNetwork(LfNetwork network, EquationSystem<AcVariableType, AcEquationType> equationSystem) {
        // update state variable: the voltages and angles of the buses straight into the arrays the network keeps them
        // in, as LfBus.setV and setAngle would write them, without going through each bus
        StateVector stateVector = equationSystem.getStateVector();
        double[] busVoltages = network.getBusVoltages();
        double[] busAngles = network.getBusAngles();
        double[] busNominalVoltages = network.getBusNominalVoltages();
        for (Variable<AcVariableType> v : equationSystem.getIndex().getSortedVariablesToFind()) {
            switch (v.getType()) {
                case BUS_V:
                    busVoltages[v.getElementNum()] = stateVector.get(v.getRow()) * busNominalVoltages[v.getElementNum()];
                    break;

                case BUS_PHI:
                    busAngles[v.getElementNum()] = stateVector.get(v.getRow());
                    break;

                case BUS_V_ZERO:
                    network.getBus(v.getElementNum()).getAsym().setVz(stateVector.get(v.getRow()));
                    break;

                case BUS_PHI_ZERO:
                    network.getBus(v.getElementNum()).getAsym().setAngleZ(stateVector.get(v.getRow()));
                    break;

                case BUS_V_NEGATIVE:
                    network.getBus(v.getElementNum()).getAsym().setVn(stateVector.get(v.getRow()));
                    break;

                case BUS_PHI_NEGATIVE:
                    network.getBus(v.getElementNum()).getAsym().setAngleN(stateVector.get(v.getRow()));
                    break;

                case SHUNT_B:
                    network.getShunt(v.getElementNum()).setB(stateVector.get(v.getRow()));
                    break;

                case BRANCH_ALPHA1:
                    network.getBranch(v.getElementNum()).getPiModel().setA1(stateVector.get(v.getRow()));
                    break;

                case BRANCH_RHO1:
                    network.getBranch(v.getElementNum()).getPiModel().setR1(stateVector.get(v.getRow()));
                    break;

                case DUMMY_P,
                     DUMMY_Q:
                    // nothing to do
                    break;

                case DC_BUS_V:
                    network.getDcBus(v.getElementNum()).setV(stateVector.get(v.getRow()));
                    break;

                case CONV_P_AC:
                    network.getVoltageSourceConverter(v.getElementNum()).setPac(stateVector.get(v.getRow()));
                    break;

                case CONV_Q_AC:
                    network.getVoltageSourceConverter(v.getElementNum()).setQac(stateVector.get(v.getRow()));
                    break;

                default:
                    throw new IllegalStateException("Unknown variable type " + v.getType());
            }
        }
        network.onStateWrite();
    }
}
