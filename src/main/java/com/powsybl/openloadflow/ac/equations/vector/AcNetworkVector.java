/**
 * Copyright (c) 2023, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.ac.equations.vector;

import com.google.common.base.Stopwatch;
import com.powsybl.iidm.network.TwoSides;
import com.powsybl.openloadflow.ac.equations.*;
import com.powsybl.openloadflow.equations.*;
import com.powsybl.openloadflow.network.*;
import net.jafama.DoubleWrapper;
import net.jafama.FastMath;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import static com.powsybl.openloadflow.network.PiModel.A2;

/**
 * Vectorized view of the network and variables of the equation system.
 *
 * @author Geoffroy Jamgotchian <geoffroy.jamgotchian at rte-france.com>
 */
public class AcNetworkVector extends AbstractLfNetworkListener
        implements EquationSystemIndexListener<AcVariableType, AcEquationType>, StateVectorListener {

    private static final Logger LOGGER = LoggerFactory.getLogger(AcNetworkVector.class);

    private final LfNetwork network;
    private final EquationSystem<AcVariableType, AcEquationType> equationSystem;
    private final AcBusVector busVector;
    private final AcBranchVector branchVector;
    private boolean variablesInvalid = true;

    // the variables-index generation these row arrays were last synced to (-1: never)
    private long syncedVariablesGeneration = -1;

    private static final boolean VARIABLES_CHECK = System.getenv("OLF_VARIDX_CHECK") != null;

    public AcNetworkVector(LfNetwork network, EquationSystem<AcVariableType, AcEquationType> equationSystem,
                           AcEquationSystemCreationParameters creationParameters) {
        this.network = Objects.requireNonNull(network);
        this.equationSystem = Objects.requireNonNull(equationSystem);
        busVector = new AcBusVector(network.getBuses());
        branchVector = new AcBranchVector(network.getBranches(), creationParameters);
    }

    public AcBusVector getBusVector() {
        return busVector;
    }

    public AcBranchVector getBranchVector() {
        return branchVector;
    }

    public void startListening() {
        // listen for branch disabling status update
        network.addListener(this);

        // update vectorized variables and then listen for variables change in equation system
        updateVariables();
        equationSystem.getIndex().addListener(this);

        // listen for state vector change to update vectorized network
        equationSystem.getStateVector().addListener(this);
    }

    /**
     * Update vectorized view of the variables from the equation system.
     */
    public void updateVariables() {
        if (!variablesInvalid) {
            return;
        }

        Stopwatch stopwatch = Stopwatch.createStarted();

        // only the variable rows are read here: do not force the equations-to-solve side of the index too
        var index = equationSystem.getIndex();
        List<Variable<AcVariableType>> variables = index.getSortedVariablesToFindOnly();
        long generation = index.getVariablesUpdateGeneration();
        if (syncedVariablesGeneration >= 0 && generation == syncedVariablesGeneration + 1
                && index.isLastVariablesUpdateIncremental()) {
            // exactly one incremental update since the last sync: only the removed variables and the rows from the
            // first changed position on can differ (a contingency removes a handful of the tens of thousands)
            for (Variable<AcVariableType> v : index.getLastRemovedVariables()) {
                setRow(v, -1);
            }
            for (int i = index.getLastFirstChangedRow(); i < variables.size(); i++) {
                Variable<AcVariableType> v = variables.get(i);
                setRow(v, v.getRow());
            }
            if (VARIABLES_CHECK) {
                checkRowsAgainstFullWalk(variables);
            }
        } else {
            fullRowWalk(variables);
        }
        syncedVariablesGeneration = generation;

        copyVariablesToBranches();

        stopwatch.stop();
        LOGGER.debug("AC variable vector update in {} us", stopwatch.elapsed(TimeUnit.MICROSECONDS));

        variablesInvalid = false;
    }

    private void setRow(Variable<AcVariableType> v, int row) {
        int num = v.getElementNum();
        switch (v.getType()) {
            case BUS_V:
                busVector.vRow[num] = row;
                break;

            case BUS_PHI:
                busVector.phRow[num] = row;
                break;

            case BRANCH_ALPHA1:
                branchVector.a1Row[num] = branchVector.deriveA1[num] ? row : -1;
                break;

            case BRANCH_RHO1:
                branchVector.r1Row[num] = branchVector.deriveR1[num] ? row : -1;
                break;

            default:
                break;
        }
    }

    private void fullRowWalk(List<Variable<AcVariableType>> variables) {
        Arrays.fill(busVector.vRow, -1);
        Arrays.fill(busVector.phRow, -1);
        Arrays.fill(branchVector.a1Row, -1);
        Arrays.fill(branchVector.r1Row, -1);
        Arrays.fill(branchVector.v1Row, -1);
        Arrays.fill(branchVector.ph1Row, -1);
        Arrays.fill(branchVector.v2Row, -1);
        Arrays.fill(branchVector.ph2Row, -1);
        for (Variable<AcVariableType> v : variables) {
            setRow(v, v.getRow());
        }
    }

    private void checkRowsAgainstFullWalk(List<Variable<AcVariableType>> variables) {
        int[] v = busVector.vRow.clone();
        int[] ph = busVector.phRow.clone();
        int[] a1 = branchVector.a1Row.clone();
        int[] r1 = branchVector.r1Row.clone();
        fullRowWalk(variables);
        if (!Arrays.equals(v, busVector.vRow) || !Arrays.equals(ph, busVector.phRow)
                || !Arrays.equals(a1, branchVector.a1Row) || !Arrays.equals(r1, branchVector.r1Row)) {
            throw new IllegalStateException("Incremental AcNetworkVector rows differ from the full walk");
        }
    }

    public void copyVariablesToBranches() {
        for (int branchNum = 0; branchNum < branchVector.getSize(); branchNum++) {
            if (branchVector.bus1Num[branchNum] != -1) {
                branchVector.v1Row[branchNum] = busVector.vRow[branchVector.bus1Num[branchNum]];
                branchVector.ph1Row[branchNum] = busVector.phRow[branchVector.bus1Num[branchNum]];
            }
            if (branchVector.bus2Num[branchNum] != -1) {
                branchVector.v2Row[branchNum] = busVector.vRow[branchVector.bus2Num[branchNum]];
                branchVector.ph2Row[branchNum] = busVector.phRow[branchVector.bus2Num[branchNum]];
            }
        }
    }

    private boolean isBranchConnectedSide1(int branchNum) {
        return branchVector.bus1Num[branchNum] != -1 && branchVector.connected1[branchNum];
    }

    private boolean isBranchConnectedSide2(int branchNum) {
        return branchVector.bus2Num[branchNum] != -1 && branchVector.connected2[branchNum];
    }

    public static double theta1(double ksi, double ph1, double a1, double ph2) {
        return ksi - a1 + A2 - ph1 + ph2;
    }

    public static double theta2(double ksi, double ph1, double a1, double ph2) {
        return ksi + a1 - A2 + ph1 - ph2;
    }

    public void updateBuses(double[] state) {
        for (int busNum = 0; busNum < busVector.v.length; busNum++) {
            if (!busVector.disabled[busNum]) {
                if (busVector.vRow[busNum] != -1) {
                    busVector.v[busNum] = state[busVector.vRow[busNum]];
                }
                if (busVector.phRow[busNum] != -1) {
                    busVector.ph[busNum] = state[busVector.phRow[busNum]];
                }
            }
        }
    }

    /**
     * Update all power flows and their derivatives.
     */
    public void updateClosedBranches(double[] state) {
        updateClosedBranches(state, true);
    }

    public void updateClosedBranches(double[] state, boolean withDerivatives) {
        var w = new DoubleWrapper();
        for (int branchNum = 0; branchNum < branchVector.getSize(); branchNum++) {
            updateClosedBranch(branchNum, state, withDerivatives, w);
        }
    }

    /**
     * Update the power flows of the given branches only (values, no derivatives): every other branch keeps what it
     * had, so this is only for a caller that reads no other branch before the next full update.
     */
    public void updateClosedBranches(double[] state, int[] branchNums) {
        var w = new DoubleWrapper();
        for (int branchNum : branchNums) {
            updateClosedBranch(branchNum, state, false, w);
        }
    }

    private void updateClosedBranch(int branchNum, double[] state, boolean withDerivatives, DoubleWrapper w) {
        if (!branchVector.disabled[branchNum]) {

            branchVector.r1State[branchNum] = branchVector.r1Row[branchNum] != -1 ? state[branchVector.r1Row[branchNum]]
                    : branchVector.r1[branchNum];
            branchVector.a1State[branchNum] = branchVector.a1Row[branchNum] != -1 ? state[branchVector.a1Row[branchNum]]
                    : branchVector.a1[branchNum];

            if (isBranchConnectedSide1(branchNum) && isBranchConnectedSide2(branchNum)) {
                double ph1 = state[branchVector.ph1Row[branchNum]];
                double ph2 = state[branchVector.ph2Row[branchNum]];
                double a1 = branchVector.a1State[branchNum];

                double theta1 = theta1(
                        branchVector.ksi[branchNum],
                        ph1,
                        a1,
                        ph2);
                double theta2 = theta2(
                        branchVector.ksi[branchNum],
                        ph1,
                        a1,
                        ph2);
                double sinTheta1 = FastMath.sinAndCos(theta1, w);
                double cosTheta1 = w.value;
                double sinTheta2 = FastMath.sinAndCos(theta2, w);
                double cosTheta2 = w.value;

                double v1 = state[branchVector.v1Row[branchNum]];
                double v2 = state[branchVector.v2Row[branchNum]];
                double r1 = branchVector.r1State[branchNum];

                // p1
                updateP1AndDerivatives(branchNum, v1, r1, v2, sinTheta1, cosTheta1, withDerivatives);

                // q1
                updateQ1AndDerivatives(branchNum, v1, r1, v2, sinTheta1, cosTheta1, withDerivatives);

                // i1
                branchVector.i1[branchNum] = FastMath.hypot(branchVector.p1[branchNum], branchVector.q1[branchNum]) / v1;

                // p2
                updateP2AndDerivatives(branchNum, v1, r1, v2, sinTheta2, cosTheta2, withDerivatives);

                // q2
                updateQ2AndDerivatives(branchNum, v1, r1, v2, sinTheta2, cosTheta2, withDerivatives);

                // i2
                branchVector.i2[branchNum] = FastMath.hypot(branchVector.p2[branchNum], branchVector.q2[branchNum]) / v2;
            }
        }
    }

    public void updateNetworkState() {
        updateNetworkState(true);
    }

    /**
     * @param withDerivatives false to refresh only the branch FLOW VALUES and skip their partial
     *        derivatives — valid only when no Jacobian will be built before the next full update
     *        (see {@link com.powsybl.openloadflow.equations.StateVector#setValuesOnly(double[])}).
     *        The derivatives are about four fifths of this method's work.
     */
    public void updateNetworkState(boolean withDerivatives) {
        Stopwatch stopwatch = Stopwatch.createStarted();

        double[] state = equationSystem.getStateVector().get();
        updateBuses(state);
        updateClosedBranches(state, withDerivatives);
        stopwatch.stop();
        LOGGER.debug("AC network vector update in {} us", stopwatch.elapsed(TimeUnit.MICROSECONDS));
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
        PiModel piModel = branch.getPiModel();

        double y = piModel.getY();
        branchVector.y[branch.getNum()] = y;
        double ksi = piModel.getKsi();
        var w = new DoubleWrapper();
        branchVector.ksi[branch.getNum()] = ksi;
        branchVector.sinKsi[branch.getNum()] = FastMath.sinAndCos(ksi, w);
        branchVector.cosKsi[branch.getNum()] = w.value;
        branchVector.b1[branch.getNum()] = piModel.getB1();
        branchVector.b2[branch.getNum()] = piModel.getB2();
        branchVector.g1[branch.getNum()] = piModel.getG1();
        branchVector.g2[branch.getNum()] = piModel.getG2();
        branchVector.a1[branch.getNum()] = piModel.getA1();
        branchVector.r1[branch.getNum()] = piModel.getR1();
    }

    @Override
    public void onShuntSusceptanceChange(LfShunt shunt, double b) {
        // do nothing
    }

    @Override
    public void onLoadActivePowerTargetChange(LfLoad load, double oldTargetP, double newTargetP) {
        // do nothing
    }

    @Override
    public void onLoadReactivePowerTargetChange(LfLoad load, double oldTargetQ, double newTargetQ) {
        // do nothing
    }

    @Override
    public void onVariableChange(Variable<AcVariableType> variable, ChangeType changeType) {
        variablesInvalid = true;
    }

    @Override
    public void onEquationChange(SingleEquation<AcVariableType, AcEquationType> equation, ChangeType changeType) {
        // nothing to do
    }

    @Override
    public void onEquationTermChange(SingleEquationTerm<AcVariableType, AcEquationType> term) {
        // nothing to do
    }

    @Override
    public void onEquationArrayChange(EquationArray<AcVariableType, AcEquationType> equationArray, ChangeType changeType) {
        // nothing to do
    }

    @Override
    public void onEquationTermArrayChange(EquationTermArray<AcVariableType, AcEquationType> equationTermArray, int termNum, ChangeType changeType) {
        // nothing to do
    }

    @Override
    public void onEquationIndexOrderChanged() {
        // nothing to do
    }

    @Override
    public void onStateUpdate() {
        onStateUpdate(false);
    }

    @Override
    public void onStateUpdate(boolean valuesOnly) {
        updateVariables();
        updateNetworkState(!valuesOnly);
    }

    @Override
    public void onStateUpdate(int[] closedBranchNums) {
        updateVariables();
        double[] state = equationSystem.getStateVector().get();
        updateBuses(state);
        updateClosedBranches(state, closedBranchNums);
    }

    private void updateP1AndDerivatives(int branchNum, double v1, double r1, double v2, double sinTheta1, double cosTheta1, boolean withDerivatives) {
        branchVector.p1[branchNum] = ClosedBranchSide1ActiveFlowEquationTerm.p1(
            branchVector.y[branchNum],
            branchVector.sinKsi[branchNum],
            branchVector.g1[branchNum],
            v1,
            r1,
            v2,
            sinTheta1);

        if (!withDerivatives) {
            return;                                          // values-only state update: the caller
        }                                                    // will not build a Jacobian from this state

        branchVector.dp1dv1[branchNum] = ClosedBranchSide1ActiveFlowEquationTerm.dp1dv1(
            branchVector.y[branchNum],
            branchVector.sinKsi[branchNum],
            branchVector.g1[branchNum],
            v1,
            r1,
            v2,
            sinTheta1);

        branchVector.dp1dv2[branchNum] = ClosedBranchSide1ActiveFlowEquationTerm.dp1dv2(
            branchVector.y[branchNum],
            v1,
            r1,
            sinTheta1);

        branchVector.dp1dph1[branchNum] = ClosedBranchSide1ActiveFlowEquationTerm.dp1dph1(
            branchVector.y[branchNum],
            v1,
            r1,
            v2,
            cosTheta1);

        branchVector.dp1dph2[branchNum] = ClosedBranchSide1ActiveFlowEquationTerm.dp1dph2(
            branchVector.y[branchNum],
            v1,
            r1,
            v2,
            cosTheta1);

        branchVector.dp1da1[branchNum] = ClosedBranchSide1ActiveFlowEquationTerm.dp1da1(
            branchVector.y[branchNum],
            v1,
            r1,
            v2,
            cosTheta1);

        branchVector.dp1dr1[branchNum] = ClosedBranchSide1ActiveFlowEquationTerm.dp1dr1(
            branchVector.y[branchNum],
            branchVector.sinKsi[branchNum],
            branchVector.g1[branchNum],
            v1,
            r1,
            v2,
            sinTheta1);
    }

    private void updateQ1AndDerivatives(int branchNum, double v1, double r1, double v2, double sinTheta1, double cosTheta1, boolean withDerivatives) {
        branchVector.q1[branchNum] = ClosedBranchSide1ReactiveFlowEquationTerm.q1(
            branchVector.y[branchNum],
            branchVector.cosKsi[branchNum],
            branchVector.b1[branchNum],
            v1,
            r1,
            v2,
            cosTheta1);
        branchVector.dq1dv1[branchNum] = ClosedBranchSide1ReactiveFlowEquationTerm.dq1dv1(
            branchVector.y[branchNum],
            branchVector.cosKsi[branchNum],
            branchVector.b1[branchNum],
            v1,
            r1,
            v2,
            cosTheta1);

        if (!withDerivatives) {
            return;                                          // values-only state update: the caller
        }                                                    // will not build a Jacobian from this state

        branchVector.dq1dv2[branchNum] = ClosedBranchSide1ReactiveFlowEquationTerm.dq1dv2(
            branchVector.y[branchNum],
            v1,
            r1,
            cosTheta1);

        branchVector.dq1dph1[branchNum] = ClosedBranchSide1ReactiveFlowEquationTerm.dq1dph1(
            branchVector.y[branchNum],
            v1,
            r1,
            v2,
            sinTheta1);

        branchVector.dq1dph2[branchNum] = ClosedBranchSide1ReactiveFlowEquationTerm.dq1dph2(
            branchVector.y[branchNum],
            v1,
            r1,
            v2,
            sinTheta1);

        branchVector.dq1da1[branchNum] = ClosedBranchSide1ReactiveFlowEquationTerm.dq1da1(
            branchVector.y[branchNum],
            v1,
            r1,
            v2,
            sinTheta1);

        branchVector.dq1dr1[branchNum] = ClosedBranchSide1ReactiveFlowEquationTerm.dq1dr1(
            branchVector.y[branchNum],
            branchVector.cosKsi[branchNum],
            branchVector.b1[branchNum],
            v1,
            r1,
            v2,
            cosTheta1);
    }

    private void updateP2AndDerivatives(int branchNum, double v1, double r1, double v2, double sinTheta2, double cosTheta2, boolean withDerivatives) {
        branchVector.p2[branchNum] = ClosedBranchSide2ActiveFlowEquationTerm.p2(
            branchVector.y[branchNum],
            branchVector.sinKsi[branchNum],
            branchVector.g2[branchNum],
            v1,
            r1,
            v2,
            sinTheta2);

        if (!withDerivatives) {
            return;                                          // values-only state update: the caller
        }                                                    // will not build a Jacobian from this state

        branchVector.dp2dv1[branchNum] = ClosedBranchSide2ActiveFlowEquationTerm.dp2dv1(
            branchVector.y[branchNum],
            r1,
            v2,
            sinTheta2);

        branchVector.dp2dv2[branchNum] = ClosedBranchSide2ActiveFlowEquationTerm.dp2dv2(
            branchVector.y[branchNum],
            branchVector.sinKsi[branchNum],
            branchVector.g2[branchNum],
            v1,
            r1,
            v2,
            sinTheta2);

        branchVector.dp2dph1[branchNum] = ClosedBranchSide2ActiveFlowEquationTerm.dp2dph1(
            branchVector.y[branchNum],
            v1,
            r1,
            v2,
            cosTheta2);

        branchVector.dp2dph2[branchNum] = ClosedBranchSide2ActiveFlowEquationTerm.dp2dph2(
            branchVector.y[branchNum],
            v1,
            r1,
            v2,
            cosTheta2);

        branchVector.dp2da1[branchNum] = ClosedBranchSide2ActiveFlowEquationTerm.dp2da1(
            branchVector.y[branchNum],
            v1,
            r1,
            v2,
            cosTheta2);

        branchVector.dp2dr1[branchNum] = ClosedBranchSide2ActiveFlowEquationTerm.dp2dr1(
            branchVector.y[branchNum],
            v1,
            v2,
            sinTheta2);
    }

    private void updateQ2AndDerivatives(int branchNum, double v1, double r1, double v2, double sinTheta2, double cosTheta2, boolean withDerivatives) {
        branchVector.q2[branchNum] = ClosedBranchSide2ReactiveFlowEquationTerm.q2(
            branchVector.y[branchNum],
            branchVector.cosKsi[branchNum],
            branchVector.b2[branchNum],
            v1,
            r1,
            v2,
            cosTheta2);

        if (!withDerivatives) {
            return;                                          // values-only state update: the caller
        }                                                    // will not build a Jacobian from this state

        branchVector.dq2dv1[branchNum] = ClosedBranchSide2ReactiveFlowEquationTerm.dq2dv1(
            branchVector.y[branchNum],
            r1,
            v2,
            cosTheta2);

        branchVector.dq2dv2[branchNum] = ClosedBranchSide2ReactiveFlowEquationTerm.dq2dv2(
            branchVector.y[branchNum],
            branchVector.cosKsi[branchNum],
            branchVector.b2[branchNum],
            v1,
            r1,
            v2,
            cosTheta2);

        branchVector.dq2dph1[branchNum] = ClosedBranchSide2ReactiveFlowEquationTerm.dq2dph1(
            branchVector.y[branchNum],
            v1,
            r1,
            v2,
            sinTheta2);

        branchVector.dq2dph2[branchNum] = ClosedBranchSide2ReactiveFlowEquationTerm.dq2dph2(
            branchVector.y[branchNum],
            v1,
            r1,
            v2,
            sinTheta2);

        branchVector.dq2da1[branchNum] = ClosedBranchSide2ReactiveFlowEquationTerm.dq2da1(
            branchVector.y[branchNum],
            v1,
            r1,
            v2,
            sinTheta2);

        branchVector.dq2dr1[branchNum] = ClosedBranchSide2ReactiveFlowEquationTerm.dq2dr1(
            branchVector.y[branchNum],
            v1,
            v2,
            cosTheta2);
    }
}
