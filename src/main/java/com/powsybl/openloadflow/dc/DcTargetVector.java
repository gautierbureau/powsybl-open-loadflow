/**
 * Copyright (c) 2022, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * SPDX-License-Identifier: MPL-2.0
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.dc;

import com.powsybl.openloadflow.dc.equations.DcEquationType;
import com.powsybl.openloadflow.dc.equations.DcVariableType;
import com.powsybl.openloadflow.equations.EquationArray;
import com.powsybl.openloadflow.equations.EquationSystem;
import com.powsybl.openloadflow.equations.SingleEquation;
import com.powsybl.openloadflow.equations.TargetVector;
import com.powsybl.openloadflow.network.LfBranch;
import com.powsybl.openloadflow.network.LfBus;
import com.powsybl.openloadflow.network.LfNetwork;
import com.powsybl.openloadflow.network.LfSynchronousNetwork;

/**
 * @author Jean-Luc Bouchot (Artelys) {@literal <jlbouchot at gmail.com>}
 */
public class DcTargetVector extends TargetVector<DcVariableType, DcEquationType> {

    public static void init(SingleEquation<DcVariableType, DcEquationType> equation, LfNetwork network, double[] targets) {
        switch (equation.getType()) {
            case BUS_TARGET_P:
                LfBus bus = network.getBus(equation.getElementNum());
                targets[equation.getColumn()] = bus.getTargetP();
                // Only used for multi slack (BUS_TARGET_P equation is disabled for first slack bus)
                if (bus.isSlack()) {
                    LfSynchronousNetwork lfScNetwork = network.getSynchronousNetwork(bus.getNumSC());
                    targets[equation.getColumn()] += DcLoadFlowEngine.getActivePowerMismatch(lfScNetwork.getBuses()) / lfScNetwork.getSlackBuses().size();
                }
                break;

            case BUS_TARGET_PHI,
                 DUMMY_TARGET_P:
                targets[equation.getColumn()] = 0;
                break;

            case BRANCH_TARGET_ALPHA1:
                targets[equation.getColumn()] = network.getBranch(equation.getElementNum()).getPiModel().getA1();
                break;

            case ZERO_PHI:
                targets[equation.getColumn()] = LfBranch.getA(network.getBranch(equation.getElementNum()));
                break;

            default:
                throw new IllegalStateException("Unknown state variable type: " + equation.getType());
        }

        targets[equation.getColumn()] -= equation.rhs();
    }

    /**
     * Vectorized counterpart of {@link #init(SingleEquation, LfNetwork, double[])} for the (only)
     * arrayed DC equation {@code BUS_TARGET_P}. Kept numerically identical to the scalar path: the base
     * active power target (plus the multi-slack adjustment) minus the sum of the active branch-term
     * right-hand sides (non-zero only for fixed phase shifters).
     */
    public static void init(EquationArray<DcVariableType, DcEquationType> equationArray, LfNetwork network, double[] targets) {
        if (equationArray.getType() != DcEquationType.BUS_TARGET_P) {
            throw new IllegalStateException("Unexpected DC equation array type: " + equationArray.getType());
        }
        for (int elementNum = 0; elementNum < equationArray.getElementCount(); elementNum++) {
            if (equationArray.isElementActive(elementNum)) {
                int column = equationArray.getElementNumToColumn(elementNum);
                LfBus bus = network.getBus(elementNum);
                targets[column] = bus.getTargetP();
                // Only used for multi slack (BUS_TARGET_P equation is disabled for first slack bus)
                if (bus.isSlack()) {
                    LfSynchronousNetwork lfScNetwork = network.getSynchronousNetwork(bus.getNumSC());
                    targets[column] += DcLoadFlowEngine.getActivePowerMismatch(lfScNetwork.getBuses()) / lfScNetwork.getSlackBuses().size();
                }
                targets[column] -= equationArray.getRhs(elementNum);
            }
        }
    }

    public DcTargetVector(LfNetwork network, EquationSystem<DcVariableType, DcEquationType> equationSystem) {
        super(network, equationSystem, new Initializer<>() {
            @Override
            public void initialize(SingleEquation<DcVariableType, DcEquationType> equation, LfNetwork network, double[] targets) {
                DcTargetVector.init(equation, network, targets);
            }

            @Override
            public void initialize(EquationArray<DcVariableType, DcEquationType> equationArray, LfNetwork network, double[] targets) {
                DcTargetVector.init(equationArray, network, targets);
            }
        });
    }
}
