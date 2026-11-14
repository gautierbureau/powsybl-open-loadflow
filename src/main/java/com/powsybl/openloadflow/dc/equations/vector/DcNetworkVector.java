/**
 * Copyright (c) 2025, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.dc.equations.vector;

import com.powsybl.iidm.network.TwoSides;
import com.powsybl.openloadflow.dc.equations.DcEquationSystemCreationParameters;
import com.powsybl.openloadflow.dc.equations.DcEquationType;
import com.powsybl.openloadflow.dc.equations.DcVariableType;
import com.powsybl.openloadflow.equations.EquationArray;
import com.powsybl.openloadflow.equations.EquationSystem;
import com.powsybl.openloadflow.equations.EquationSystemIndexListener;
import com.powsybl.openloadflow.equations.EquationTermArray;
import com.powsybl.openloadflow.equations.SingleEquation;
import com.powsybl.openloadflow.equations.SingleEquationTerm;
import com.powsybl.openloadflow.equations.StateVectorListener;
import com.powsybl.openloadflow.equations.Variable;
import com.powsybl.openloadflow.network.AbstractLfNetworkListener;
import com.powsybl.openloadflow.network.ElementType;
import com.powsybl.openloadflow.network.LfBranch;
import com.powsybl.openloadflow.network.LfElement;
import com.powsybl.openloadflow.network.LfNetwork;

import java.util.Arrays;
import java.util.Objects;

import static com.powsybl.openloadflow.network.PiModel.A2;

/**
 * Vectorized view of the network and variables of the DC equation system.
 *
 * <p>Mirrors {@code AcNetworkVector} but is considerably simpler: DC flows are affine so the partial
 * derivatives are constant (held in {@link DcBranchVector}) and only the flow values are refreshed on
 * each state update.
 *
 * @author Claude
 */
public class DcNetworkVector extends AbstractLfNetworkListener
        implements EquationSystemIndexListener<DcVariableType, DcEquationType>, StateVectorListener {

    private final LfNetwork network;
    private final EquationSystem<DcVariableType, DcEquationType> equationSystem;
    private final DcBusVector busVector;
    private final DcBranchVector branchVector;
    private boolean variablesInvalid = true;

    public DcNetworkVector(LfNetwork network, EquationSystem<DcVariableType, DcEquationType> equationSystem,
                           DcEquationSystemCreationParameters creationParameters) {
        this.network = Objects.requireNonNull(network);
        this.equationSystem = Objects.requireNonNull(equationSystem);
        busVector = new DcBusVector(network.getBuses());
        branchVector = new DcBranchVector(network.getBranches(), creationParameters);
    }

    public DcBusVector getBusVector() {
        return busVector;
    }

    public DcBranchVector getBranchVector() {
        return branchVector;
    }

    public void startListening() {
        network.addListener(this);
        updateVariables();
        equationSystem.getIndex().addListener(this);
        equationSystem.getStateVector().addListener(this);
    }

    /**
     * Update vectorized view of the variables from the equation system.
     */
    public void updateVariables() {
        if (!variablesInvalid) {
            return;
        }

        Arrays.fill(busVector.phRow, -1);
        Arrays.fill(branchVector.a1Row, -1);
        Arrays.fill(branchVector.ph1Row, -1);
        Arrays.fill(branchVector.ph2Row, -1);

        for (Variable<DcVariableType> v : equationSystem.getIndex().getSortedVariablesToFind()) {
            int num = v.getElementNum();
            int row = v.getRow();
            switch (v.getType()) {
                case BUS_PHI:
                    busVector.phRow[num] = row;
                    break;

                case BRANCH_ALPHA1:
                    branchVector.a1Row[num] = branchVector.deriveA1[num] ? row : -1;
                    break;

                default:
                    break;
            }
        }

        for (int branchNum = 0; branchNum < branchVector.getSize(); branchNum++) {
            if (branchVector.bus1Num[branchNum] != -1) {
                branchVector.ph1Row[branchNum] = busVector.phRow[branchVector.bus1Num[branchNum]];
            }
            if (branchVector.bus2Num[branchNum] != -1) {
                branchVector.ph2Row[branchNum] = busVector.phRow[branchVector.bus2Num[branchNum]];
            }
        }

        variablesInvalid = false;
    }

    /**
     * Update all DC power flows from the state vector.
     *
     * <p>The closed branch flow is computed whenever both bus angle variables exist, mirroring the scalar
     * {@code ClosedBranchSide{1,2}DcFlowEquationTerm.eval()} which always evaluates {@code -power * deltaPhase}
     * from the current state. Reporting a zero flow for a disconnected branch is handled downstream by the
     * {@code branch.setP1/setP2} evaluable swap in {@code DcEquationSystemUpdater}, not by gating here (gating
     * on the cached disabled/connected status would return a stale zero for branches reconnected through the
     * Woodbury connectivity machinery).
     */
    public void updateClosedBranches(double[] state) {
        for (int branchNum = 0; branchNum < branchVector.getSize(); branchNum++) {
            if (!branchVector.zeroImpedance[branchNum]
                    && branchVector.ph1Row[branchNum] != -1 && branchVector.ph2Row[branchNum] != -1) {
                double a1 = branchVector.a1Row[branchNum] != -1 ? state[branchVector.a1Row[branchNum]]
                        : branchVector.a1[branchNum];
                branchVector.a1State[branchNum] = a1;
                double deltaPhase = state[branchVector.ph2Row[branchNum]] - state[branchVector.ph1Row[branchNum]] + A2 - a1;
                branchVector.p1[branchNum] = -branchVector.power[branchNum] * deltaPhase;
                branchVector.p2[branchNum] = branchVector.power[branchNum] * deltaPhase;
            } else {
                branchVector.p1[branchNum] = 0;
                branchVector.p2[branchNum] = 0;
            }
        }
    }

    public void updateNetworkState() {
        updateClosedBranches(equationSystem.getStateVector().get());
    }

    @Override
    public void onDisableChange(LfElement element, boolean disabled) {
        if (element.getType() == ElementType.BUS) {
            busVector.disabled[element.getNum()] = disabled;
        } else if (element.getType() == ElementType.BRANCH) {
            branchVector.disabled[element.getNum()] = disabled;
        }
    }

    @Override
    public void onBranchConnectionStatusChange(LfBranch branch, TwoSides side, boolean connected) {
        if (side == TwoSides.ONE) {
            branchVector.connected1[branch.getNum()] = connected;
        } else {
            branchVector.connected2[branch.getNum()] = connected;
        }
    }

    @Override
    public void onTapPositionChange(LfBranch branch, int oldPosition, int newPosition) {
        if (!branchVector.zeroImpedance[branch.getNum()]) {
            branchVector.updateBranchModel(branch.getNum(), branch);
        }
    }

    @Override
    public void onVariableChange(Variable<DcVariableType> variable, ChangeType changeType) {
        variablesInvalid = true;
    }

    @Override
    public void onEquationChange(SingleEquation<DcVariableType, DcEquationType> equation, ChangeType changeType) {
        // nothing to do
    }

    @Override
    public void onEquationTermChange(SingleEquationTerm<DcVariableType, DcEquationType> term) {
        // nothing to do
    }

    @Override
    public void onEquationArrayChange(EquationArray<DcVariableType, DcEquationType> equationArray, ChangeType changeType) {
        // nothing to do
    }

    @Override
    public void onEquationTermArrayChange(EquationTermArray<DcVariableType, DcEquationType> equationTermArray, int termNum, ChangeType changeType) {
        // nothing to do
    }

    @Override
    public void onEquationIndexOrderChanged() {
        // nothing to do
    }

    @Override
    public void onStateUpdate() {
        updateVariables();
        updateNetworkState();
    }
}
