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
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import static com.powsybl.openloadflow.network.PiModel.A2;

/**
 * Vectorized view of the network and of a set of variables.
 * <p>
 * The variables and the state are read through an {@link AcVariableIndex}, so the view works on top of an
 * {@link EquationSystem} (the load flow) as well as on top of variables and a state vector managed by another
 * solver.
 *
 * @author Geoffroy Jamgotchian <geoffroy.jamgotchian at rte-france.com>
 */
public class AcNetworkVector extends AbstractLfNetworkListener
        implements AcVariableIndex.Listener, StateVectorListener {

    private static final Logger LOGGER = LoggerFactory.getLogger(AcNetworkVector.class);

    private final LfNetwork network;
    private final AcVariableIndex variableIndex;
    private final AcBusVector busVector;
    private final AcBranchVector branchVector;
    private boolean variablesInvalid = true;

    public AcNetworkVector(LfNetwork network, EquationSystem<AcVariableType, AcEquationType> equationSystem,
                           AcEquationSystemCreationParameters creationParameters) {
        this(network, AcVariableIndex.of(equationSystem), creationParameters);
    }

    public AcNetworkVector(LfNetwork network, AcVariableIndex variableIndex,
                           AcEquationSystemCreationParameters creationParameters) {
        this.network = Objects.requireNonNull(network);
        this.variableIndex = Objects.requireNonNull(variableIndex);
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

        // update vectorized variables and then listen for variables change in the index
        updateVariables();
        variableIndex.addListener(this);

        // listen for state vector change to update vectorized network
        variableIndex.getStateVector().addListener(this);
    }

    /**
     * Update vectorized view of the variables from the variable index.
     */
    public void updateVariables() {
        if (!variablesInvalid) {
            return;
        }

        Stopwatch stopwatch = Stopwatch.createStarted();

        Arrays.fill(busVector.vRow, -1);
        Arrays.fill(busVector.phRow, -1);
        Arrays.fill(branchVector.a1Row, -1);
        Arrays.fill(branchVector.r1Row, -1);
        Arrays.fill(branchVector.v1Row, -1);
        Arrays.fill(branchVector.ph1Row, -1);
        Arrays.fill(branchVector.v2Row, -1);
        Arrays.fill(branchVector.ph2Row, -1);

        for (Variable<AcVariableType> v : variableIndex.getVariablesWithRow()) {
            int num = v.getElementNum();
            int row = v.getRow();
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

        copyVariablesToBranches();

        stopwatch.stop();
        LOGGER.debug("AC variable vector update in {} us", stopwatch.elapsed(TimeUnit.MICROSECONDS));

        variablesInvalid = false;
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
        var w = new DoubleWrapper();

        for (int branchNum = 0; branchNum < branchVector.getSize(); branchNum++) {

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
                    updateP1AndDerivatives(branchNum, v1, r1, v2, sinTheta1, cosTheta1);

                    // q1
                    updateQ1AndDerivatives(branchNum, v1, r1, v2, sinTheta1, cosTheta1);

                    // i1
                    branchVector.i1[branchNum] = FastMath.hypot(branchVector.p1[branchNum], branchVector.q1[branchNum]) / v1;

                    // p2
                    updateP2AndDerivatives(branchNum, v1, r1, v2, sinTheta2, cosTheta2);

                    // q2
                    updateQ2AndDerivatives(branchNum, v1, r1, v2, sinTheta2, cosTheta2);

                    // i2
                    branchVector.i2[branchNum] = FastMath.hypot(branchVector.p2[branchNum], branchVector.q2[branchNum]) / v2;
                }
            }
        }
    }

    // Current magnitude derivatives, computed on demand from the flows and flow derivatives of the last update rather
    // than stored, so that the load flow, which never reads them, does not pay for them at each state update.
    // With i = sqrt(p² + q²) / v: di/dx = (p dp/dx + q dq/dx) / (v² i), minus i / v when x is v itself.
    // Like the scalar current magnitude terms, they are undefined where the current is zero, for instance from a flat
    // start on a branch without shunt admittance. Valid only for a branch where AcBranchVector#isClosed is true.

    private double di1(int branchNum, double dp1, double dq1) {
        double v1 = busVector.v[branchVector.bus1Num[branchNum]];
        return (branchVector.p1[branchNum] * dp1 + branchVector.q1[branchNum] * dq1) / (v1 * v1 * branchVector.i1[branchNum]);
    }

    private double di2(int branchNum, double dp2, double dq2) {
        double v2 = busVector.v[branchVector.bus2Num[branchNum]];
        return (branchVector.p2[branchNum] * dp2 + branchVector.q2[branchNum] * dq2) / (v2 * v2 * branchVector.i2[branchNum]);
    }

    public double di1dv1(int branchNum) {
        double v1 = busVector.v[branchVector.bus1Num[branchNum]];
        return di1(branchNum, branchVector.dp1dv1[branchNum], branchVector.dq1dv1[branchNum]) - branchVector.i1[branchNum] / v1;
    }

    public double di1dv2(int branchNum) {
        return di1(branchNum, branchVector.dp1dv2[branchNum], branchVector.dq1dv2[branchNum]);
    }

    public double di1dph1(int branchNum) {
        return di1(branchNum, branchVector.dp1dph1[branchNum], branchVector.dq1dph1[branchNum]);
    }

    public double di1dph2(int branchNum) {
        return di1(branchNum, branchVector.dp1dph2[branchNum], branchVector.dq1dph2[branchNum]);
    }

    public double di1da1(int branchNum) {
        return di1(branchNum, branchVector.dp1da1[branchNum], branchVector.dq1da1[branchNum]);
    }

    public double di1dr1(int branchNum) {
        return di1(branchNum, branchVector.dp1dr1[branchNum], branchVector.dq1dr1[branchNum]);
    }

    public double di2dv1(int branchNum) {
        return di2(branchNum, branchVector.dp2dv1[branchNum], branchVector.dq2dv1[branchNum]);
    }

    public double di2dv2(int branchNum) {
        double v2 = busVector.v[branchVector.bus2Num[branchNum]];
        return di2(branchNum, branchVector.dp2dv2[branchNum], branchVector.dq2dv2[branchNum]) - branchVector.i2[branchNum] / v2;
    }

    public double di2dph1(int branchNum) {
        return di2(branchNum, branchVector.dp2dph1[branchNum], branchVector.dq2dph1[branchNum]);
    }

    public double di2dph2(int branchNum) {
        return di2(branchNum, branchVector.dp2dph2[branchNum], branchVector.dq2dph2[branchNum]);
    }

    public double di2da1(int branchNum) {
        return di2(branchNum, branchVector.dp2da1[branchNum], branchVector.dq2da1[branchNum]);
    }

    public double di2dr1(int branchNum) {
        return di2(branchNum, branchVector.dp2dr1[branchNum], branchVector.dq2dr1[branchNum]);
    }

    public void updateNetworkState() {
        Stopwatch stopwatch = Stopwatch.createStarted();

        double[] state = variableIndex.getStateVector().get();
        updateBuses(state);
        updateClosedBranches(state);
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
    public void onVariablesChange() {
        variablesInvalid = true;
    }

    @Override
    public void onStateUpdate() {
        updateVariables();
        updateNetworkState();
    }

    private void updateP1AndDerivatives(int branchNum, double v1, double r1, double v2, double sinTheta1, double cosTheta1) {
        branchVector.p1[branchNum] = ClosedBranchSide1ActiveFlowEquationTerm.p1(
            branchVector.y[branchNum],
            branchVector.sinKsi[branchNum],
            branchVector.g1[branchNum],
            v1,
            r1,
            v2,
            sinTheta1);

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

    private void updateQ1AndDerivatives(int branchNum, double v1, double r1, double v2, double sinTheta1, double cosTheta1) {
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

    private void updateP2AndDerivatives(int branchNum, double v1, double r1, double v2, double sinTheta2, double cosTheta2) {
        branchVector.p2[branchNum] = ClosedBranchSide2ActiveFlowEquationTerm.p2(
            branchVector.y[branchNum],
            branchVector.sinKsi[branchNum],
            branchVector.g2[branchNum],
            v1,
            r1,
            v2,
            sinTheta2);

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

    private void updateQ2AndDerivatives(int branchNum, double v1, double r1, double v2, double sinTheta2, double cosTheta2) {
        branchVector.q2[branchNum] = ClosedBranchSide2ReactiveFlowEquationTerm.q2(
            branchVector.y[branchNum],
            branchVector.cosKsi[branchNum],
            branchVector.b2[branchNum],
            v1,
            r1,
            v2,
            cosTheta2);

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
