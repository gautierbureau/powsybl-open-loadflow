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
import gnu.trove.list.array.TIntArrayList;
import net.jafama.DoubleWrapper;
import net.jafama.FastMath;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
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
    private final AcShuntVector shuntVector;
    private final AcHvdcVector hvdcVector;
    private final AcLoadVector loadVector;
    private boolean variablesInvalid = true;

    /*
     * The state the flows and their derivatives were last computed for, and whether an input other than the state
     * changed since. A solve that starts from the state the last one ended on (outer loop iterations, see
     * AcKeptState) finds them computed already: as long as no event of the network came in between, they are not
     * computed again. The GPU fuses this evaluation with its fill and has nothing to keep.
     */
    private double[] computedState;

    /*
     * The flows of the closed branches computed for a state that was set (a start of a solve), kept to be copied back
     * when a solve starts again from the same state: in a security analysis each contingency starts from the voltages of
     * the base case, restored. A snapshot reused is kept; one never reused is replaced by the next state set. The
     * branches whose parameters changed since the snapshot are computed again. It is dropped when the rows change.
     */
    private double[] snapshotState;
    private double[][] snapshotValues;
    private boolean[][] snapshotFlags;
    /** For each branch, whether the snapshot holds its r1 and a1 (closed and enabled), and its flows (connected too). */
    private boolean[] snapshotHasClosed;
    private boolean[] snapshotHasFlows;
    private boolean snapshotHit;
    private boolean[] changedSinceSnapshot;
    private final TIntArrayList changedBranches = new TIntArrayList();
    private final TIntArrayList keptClosed = new TIntArrayList();
    private final TIntArrayList keptFlows = new TIntArrayList();
    private final TIntArrayList recomputed = new TIntArrayList();
    private double[] keptValues;
    private long lastSetCount = -1;
    /** True when the update in progress is for a state that was set: a start, not a Newton step. */
    private boolean startUpdate;
    private int snapshotReuseCount;
    private boolean inputsChanged = true;
    private int skipCount;

    /**
     * Notes the events of the network that change what the flows are computed from: an element disabled or enabled, a
     * side of a branch connected or not, a tap, a susceptance, the target of a load that follows a model. The others
     * (targets of generators, controls switched, the slack, the status of an HVDC in AC emulation, which switches its
     * terms and not what they are computed from) do not.
     */
    private final LfNetworkListener inputListener = new AbstractLfNetworkListener() {

        @Override
        public void onLoadActivePowerTargetChange(LfLoad load, double oldTargetP, double newTargetP) {
            inputsChanged = true;
        }

        @Override
        public void onLoadReactivePowerTargetChange(LfLoad load, double oldTargetQ, double newTargetQ) {
            inputsChanged = true;
        }

        @Override
        public void onDisableChange(LfElement element, boolean disabled) {
            inputsChanged = true;
        }

        @Override
        public void onTapPositionChange(LfBranch branch, int oldPosition, int newPosition, boolean modifiedTapImpedance) {
            inputsChanged = true;
        }

        @Override
        public void onShuntSusceptanceChange(LfShunt shunt, double b) {
            inputsChanged = true;
        }

        @Override
        public void onBranchConnectionStatusChange(LfBranch branch, TwoSides side, boolean connected) {
            inputsChanged = true;
        }
    };

    public AcNetworkVector(LfNetwork network, EquationSystem<AcVariableType, AcEquationType> equationSystem,
                           AcEquationSystemCreationParameters creationParameters) {
        this.network = Objects.requireNonNull(network);
        this.equationSystem = Objects.requireNonNull(equationSystem);
        busVector = new AcBusVector(network.getBuses());
        branchVector = new AcBranchVector(network.getBranches(), creationParameters);
        shuntVector = new AcShuntVector(network.getShunts());
        hvdcVector = new AcHvdcVector(network.getHvdcs());
        loadVector = new AcLoadVector(network.getBuses().size());
    }

    public AcBusVector getBusVector() {
        return busVector;
    }

    public AcBranchVector getBranchVector() {
        return branchVector;
    }

    public AcShuntVector getShuntVector() {
        return shuntVector;
    }

    public AcHvdcVector getHvdcVector() {
        return hvdcVector;
    }

    public AcLoadVector getLoadVector() {
        return loadVector;
    }

    public void startListening() {
        // listen for branch disabling status update
        network.addListener(this);
        network.addListener(inputListener);

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
        // the changes the index has pending may move the rows of the variables: resolved first
        equationSystem.getIndex().validate();
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
        Arrays.fill(shuntVector.bRow, -1);

        for (Variable<AcVariableType> v : equationSystem.getIndex().getSortedVariablesToFind()) {
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

                case SHUNT_B:
                    shuntVector.bRow[num] = shuntVector.deriveB[num] ? row : -1;
                    break;

                default:
                    break;
            }
        }

        copyVariablesToBranches();

        stopwatch.stop();
        LOGGER.debug("AC variable vector update in {} us", stopwatch.elapsed(TimeUnit.MICROSECONDS));

        variablesInvalid = false;
        // other rows: the snapshot of the flows is for a state laid out on the previous ones
        snapshotState = null;
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
     * Update the power flows and their derivatives of the branches that have terms for when they are closed at both
     * sides (not of a zero impedance branch).
     */
    /** The values the closed branch flows write, by branch: the state of r1 and a1, the flows and their derivatives. */
    private double[][] closedBranchValues() {
        AcBranchVector b = branchVector;
        return new double[][] {b.r1State, b.a1State, b.v1OfI, b.v2OfI,
            b.p1, b.dp1dv1, b.dp1dv2, b.dp1dph1, b.dp1dph2, b.dp1da1, b.dp1dr1,
            b.q1, b.dq1dv1, b.dq1dv2, b.dq1dph1, b.dq1dph2, b.dq1da1, b.dq1dr1,
            b.p2, b.dp2dv1, b.dp2dv2, b.dp2dph1, b.dp2dph2, b.dp2da1, b.dp2dr1,
            b.q2, b.dq2dv1, b.dq2dv2, b.dq2dph1, b.dq2dph2, b.dq2da1, b.dq2dr1};
    }

    private boolean[][] closedBranchFlags() {
        return new boolean[][] {branchVector.i1Computed, branchVector.i2Computed};
    }

    private void takeSnapshot(double[] state) {
        double[][] values = closedBranchValues();
        boolean[][] flags = closedBranchFlags();
        if (snapshotValues == null) {
            snapshotValues = new double[values.length][];
            snapshotFlags = new boolean[flags.length][];
            changedSinceSnapshot = new boolean[branchVector.getSize()];
            snapshotHasClosed = new boolean[branchVector.getSize()];
            snapshotHasFlows = new boolean[branchVector.getSize()];
        }
        for (int branchNum = 0; branchNum < branchVector.getSize(); branchNum++) {
            snapshotHasClosed[branchNum] = isWritten(branchNum, false);
            snapshotHasFlows[branchNum] = isWritten(branchNum, true);
        }
        for (int i = 0; i < values.length; i++) {
            snapshotValues[i] = values[i].clone();
        }
        for (int i = 0; i < flags.length; i++) {
            snapshotFlags[i] = flags[i].clone();
        }
        snapshotState = state.clone();
        snapshotHit = false;
        for (int i = 0; i < changedBranches.size(); i++) {
            changedSinceSnapshot[changedBranches.getQuick(i)] = false;
        }
        changedBranches.resetQuick();
    }

    /**
     * The flows of the snapshot, for the branches the full computation writes now (the closed branches enabled, and
     * their flows when connected at both sides), and the branches whose parameters changed since computed again: what
     * {@link #updateClosedBranches(double[])} writes, without computing it.
     */
    private void restoreSnapshot(double[] state) {
        snapshotHit = true;
        snapshotReuseCount++;
        double[][] values = closedBranchValues();
        boolean[][] flags = closedBranchFlags();
        int size = branchVector.getSize();
        // the branches the full computation leaves as they are now (disabled, or not closed, or open at one side): few,
        // their values are kept across the copy of the whole arrays
        keptClosed.resetQuick();
        keptFlows.resetQuick();
        recomputed.resetQuick();
        for (int branchNum = 0; branchNum < size; branchNum++) {
            boolean closed = isWritten(branchNum, false);
            boolean flows = closed && isWritten(branchNum, true);
            if (!closed) {
                keptClosed.add(branchNum);
            }
            if (!flows) {
                keptFlows.add(branchNum);
            }
            if (closed && !snapshotHasClosed[branchNum] || flows && !snapshotHasFlows[branchNum]) {
                // computed now, not held by the snapshot (disabled or open when it was taken)
                recomputed.add(branchNum);
            }
        }
        int keptValueCount = 2 * keptClosed.size() + (values.length - 2) * keptFlows.size() + flags.length * keptFlows.size();
        if (keptValues == null || keptValues.length < keptValueCount) {
            keptValues = new double[Math.max(keptValueCount, 16)];
        }
        int k = 0;
        for (int i = 0; i < values.length; i++) {
            TIntArrayList kept = i < 2 ? keptClosed : keptFlows;
            for (int j = 0; j < kept.size(); j++) {
                keptValues[k++] = values[i][kept.getQuick(j)];
            }
        }
        for (boolean[] flag : flags) {
            for (int j = 0; j < keptFlows.size(); j++) {
                keptValues[k++] = flag[keptFlows.getQuick(j)] ? 1 : 0;
            }
        }
        for (int i = 0; i < values.length; i++) {
            System.arraycopy(snapshotValues[i], 0, values[i], 0, size);
        }
        for (int i = 0; i < flags.length; i++) {
            System.arraycopy(snapshotFlags[i], 0, flags[i], 0, size);
        }
        k = 0;
        for (int i = 0; i < values.length; i++) {
            TIntArrayList kept = i < 2 ? keptClosed : keptFlows;
            for (int j = 0; j < kept.size(); j++) {
                values[i][kept.getQuick(j)] = keptValues[k++];
            }
        }
        for (boolean[] flag : flags) {
            for (int j = 0; j < keptFlows.size(); j++) {
                flag[keptFlows.getQuick(j)] = keptValues[k++] != 0;
            }
        }
        var w = new DoubleWrapper();
        for (int i = 0; i < recomputed.size(); i++) {
            updateClosedBranch(recomputed.getQuick(i), state, w);
        }
        for (int i = 0; i < changedBranches.size(); i++) {
            updateClosedBranch(changedBranches.getQuick(i), state, w);
        }
        assert snapshotSameAsComputedAgain(state);
    }

    private boolean isWritten(int branchNum, boolean flows) {
        return !branchVector.disabled[branchNum] && branchVector.closable[branchNum]
                && (!flows || isBranchConnectedSide1(branchNum) && isBranchConnectedSide2(branchNum));
    }

    private boolean snapshotSameAsComputedAgain(double[] state) {
        double[][] values = closedBranchValues();
        double[][] kept = new double[values.length][];
        for (int i = 0; i < values.length; i++) {
            kept[i] = values[i].clone();
        }
        updateClosedBranches(state);
        for (int i = 0; i < values.length; i++) {
            if (!Arrays.equals(kept[i], values[i])) {
                throw new AssertionError("A flow copied from the snapshot differs from the one computed again");
            }
        }
        return true;
    }

    /** How many state updates took the flows of the closed branches from the snapshot. */
    public int getSnapshotReuseCount() {
        return snapshotReuseCount;
    }

    private void onBranchParametersChange(int branchNum) {
        if (changedSinceSnapshot != null && !changedSinceSnapshot[branchNum]) {
            changedSinceSnapshot[branchNum] = true;
            changedBranches.add(branchNum);
        }
    }

    public void updateClosedBranches(double[] state) {
        var w = new DoubleWrapper();
        for (int branchNum = 0; branchNum < branchVector.getSize(); branchNum++) {
            updateClosedBranch(branchNum, state, w);
        }
    }

    private void updateClosedBranch(int branchNum, double[] state, DoubleWrapper w) {
        if (!branchVector.disabled[branchNum] && branchVector.closable[branchNum]) {

            branchVector.r1State[branchNum] = branchVector.r1Row[branchNum] != -1 ? state[branchVector.r1Row[branchNum]]
                    : branchVector.r1[branchNum];
            branchVector.a1State[branchNum] = branchVector.a1Row[branchNum] != -1 ? state[branchVector.a1Row[branchNum]]
                    : branchVector.a1[branchNum];

            if (isBranchConnectedSide1(branchNum) && isBranchConnectedSide2(branchNum)) {
                double ph1 = state[branchVector.ph1Row[branchNum]];
                double ph2 = state[branchVector.ph2Row[branchNum]];
                double a1 = branchVector.a1State[branchNum];

                // theta1 = ksi + d and theta2 = ksi - d: one sine and cosine, combined with those of ksi, kept by branch
                double d = ph2 - ph1 - a1 + A2;
                double sinD = FastMath.sinAndCos(d, w);
                double cosD = w.value;
                double sinKsi = branchVector.sinKsi[branchNum];
                double cosKsi = branchVector.cosKsi[branchNum];
                double sinTheta1 = sinKsi * cosD + cosKsi * sinD;
                double cosTheta1 = cosKsi * cosD - sinKsi * sinD;
                double sinTheta2 = sinKsi * cosD - cosKsi * sinD;
                double cosTheta2 = cosKsi * cosD + sinKsi * sinD;

                double v1 = state[branchVector.v1Row[branchNum]];
                double v2 = state[branchVector.v2Row[branchNum]];
                double r1 = branchVector.r1State[branchNum];

                // p1
                updateP1AndDerivatives(branchNum, v1, r1, v2, sinTheta1, cosTheta1);

                // q1
                updateQ1AndDerivatives(branchNum, v1, r1, v2, sinTheta1, cosTheta1);

                // i1, computed when read
                branchVector.v1OfI[branchNum] = v1;
                branchVector.i1Computed[branchNum] = true;

                // p2
                updateP2AndDerivatives(branchNum, v1, r1, v2, sinTheta2, cosTheta2);

                // q2
                updateQ2AndDerivatives(branchNum, v1, r1, v2, sinTheta2, cosTheta2);

                // i2, computed when read
                branchVector.v2OfI[branchNum] = v2;
                branchVector.i2Computed[branchNum] = true;
            }
        }
    }

    /**
     * Update the active and reactive power of all shunts and their derivatives. The susceptance and the conductance
     * are read from the shunts each time: a contingency or an outer loop changes them.
     */
    public void updateShunts(double[] state) {
        for (int shuntNum = 0; shuntNum < shuntVector.getSize(); shuntNum++) {
            updateShunt(shuntNum, state);
        }
    }

    private void updateShunt(int shuntNum, double[] state) {
        int busNum = shuntVector.busNum[shuntNum];
        if (busNum != -1) {
            LfShunt shunt = shuntVector.shunts[shuntNum];
            double v = busVector.v[busNum];
            int bRow = shuntVector.bRow[shuntNum];
            double b = bRow != -1 ? state[bRow] : shunt.getB();
            double g = shunt.getG();
            shuntVector.b[shuntNum] = b;
            shuntVector.q[shuntNum] = ShuntCompensatorReactiveFlowEquationTerm.q(v, b);
            shuntVector.dqdv[shuntNum] = ShuntCompensatorReactiveFlowEquationTerm.dqdv(v, b);
            shuntVector.dqdb[shuntNum] = ShuntCompensatorReactiveFlowEquationTerm.dqdb(v);
            shuntVector.p[shuntNum] = ShuntCompensatorActiveFlowEquationTerm.p(v, g);
            shuntVector.dpdv[shuntNum] = ShuntCompensatorActiveFlowEquationTerm.dpdv(v, g);
        }
    }

    /**
     * Update the active power at both ends of all HVDC lines in AC emulation, and their derivatives: also of a line
     * whose terms are off, as the outer loop that takes it out of its limits reads them.
     */
    public void updateHvdcs() {
        for (int hvdcNum = 0; hvdcNum < hvdcVector.getSize(); hvdcNum++) {
            if (hvdcVector.bus1Num[hvdcNum] != -1) {
                double p0 = hvdcVector.p0[hvdcNum];
                double k = hvdcVector.k[hvdcNum];
                double r = hvdcVector.r[hvdcNum];
                double lossFactor1 = hvdcVector.lossFactor1[hvdcNum];
                double lossFactor2 = hvdcVector.lossFactor2[hvdcNum];
                double ph1 = busVector.ph[hvdcVector.bus1Num[hvdcNum]];
                double ph2 = busVector.ph[hvdcVector.bus2Num[hvdcNum]];
                hvdcVector.p1[hvdcNum] = HvdcAcEmulationSide1ActiveFlowEquationTerm.p1(p0, k, lossFactor1, lossFactor2, r, ph1, ph2);
                hvdcVector.dp1dph1[hvdcNum] = HvdcAcEmulationSide1ActiveFlowEquationTerm.dp1dph1(p0, k, lossFactor1, lossFactor2, ph1, ph2);
                hvdcVector.dp1dph2[hvdcNum] = HvdcAcEmulationSide1ActiveFlowEquationTerm.dp1dph2(p0, k, lossFactor1, lossFactor2, ph1, ph2);
                hvdcVector.p2[hvdcNum] = HvdcAcEmulationSide2ActiveFlowEquationTerm.p2(p0, k, lossFactor1, lossFactor2, r, ph1, ph2);
                hvdcVector.dp2dph1[hvdcNum] = HvdcAcEmulationSide2ActiveFlowEquationTerm.dp2dph1(p0, k, lossFactor1, lossFactor2, ph1, ph2);
                hvdcVector.dp2dph2[hvdcNum] = HvdcAcEmulationSide2ActiveFlowEquationTerm.dp2dph2(p0, k, lossFactor1, lossFactor2, ph1, ph2);
            }
        }
    }

    /**
     * Update the flow left at the connected side of the branches that have terms for when they are open at one side,
     * and its derivative: also of a branch that is closed, whose terms are off, so that opening it only switches them
     * on. As a term held as an object does, the ratio is read from the branch each time, and is not a variable.
     */
    /**
     * Update the flow of the branches open at one side, at their other side. A branch has terms for when it is open
     * at a side as soon as a contingency of the list may open it there, but their values are read only while it is
     * open at that side: the flows of the others are not computed. A change of connection is an input change, so
     * that the flows of a branch that has just been opened are computed before they are read.
     */
    public void updateOpenBranches() {
        for (int i = 0; i < branchVector.openableSide1Nums.size(); i++) {
            int branchNum = branchVector.openableSide1Nums.getQuick(i);
            if (branchVector.connected1[branchNum] && branchVector.bus1Num[branchNum] >= 0) {
                continue;
            }
            double y = branchVector.y[branchNum];
            double cosKsi = branchVector.cosKsi[branchNum];
            double sinKsi = branchVector.sinKsi[branchNum];
            double g1 = branchVector.g1[branchNum];
            double b1 = branchVector.b1[branchNum];
            double g2 = branchVector.g2[branchNum];
            double b2 = branchVector.b2[branchNum];
            double v2 = busVector.v[branchVector.bus2Num[branchNum]];
            branchVector.openP2[branchNum] = OpenBranchSide1ActiveFlowEquationTerm.p2(y, cosKsi, sinKsi, g1, b1, g2, v2);
            branchVector.openDp2dv2[branchNum] = OpenBranchSide1ActiveFlowEquationTerm.dp2dv2(y, cosKsi, sinKsi, g1, b1, g2, v2);
            branchVector.openQ2[branchNum] = OpenBranchSide1ReactiveFlowEquationTerm.q2(y, cosKsi, sinKsi, g1, b1, b2, v2);
            branchVector.openDq2dv2[branchNum] = OpenBranchSide1ReactiveFlowEquationTerm.dq2dv2(y, cosKsi, sinKsi, g1, b1, b2, v2);
        }
        for (int i = 0; i < branchVector.openableSide2Nums.size(); i++) {
            int branchNum = branchVector.openableSide2Nums.getQuick(i);
            if (branchVector.connected2[branchNum] && branchVector.bus2Num[branchNum] >= 0) {
                continue;
            }
            double y = branchVector.y[branchNum];
            double cosKsi = branchVector.cosKsi[branchNum];
            double sinKsi = branchVector.sinKsi[branchNum];
            double g1 = branchVector.g1[branchNum];
            double b1 = branchVector.b1[branchNum];
            double g2 = branchVector.g2[branchNum];
            double b2 = branchVector.b2[branchNum];
            double v1 = busVector.v[branchVector.bus1Num[branchNum]];
            double r1 = network.getBranch(branchNum).getPiModel().getR1();
            branchVector.openR1[branchNum] = r1;
            branchVector.openP1[branchNum] = OpenBranchSide2ActiveFlowEquationTerm.p1(y, cosKsi, sinKsi, g1, g2, b2, v1, r1);
            branchVector.openDp1dv1[branchNum] = OpenBranchSide2ActiveFlowEquationTerm.dp1dv1(y, cosKsi, sinKsi, g1, g2, b2, v1, r1);
            branchVector.openQ1[branchNum] = OpenBranchSide2ReactiveFlowEquationTerm.q1(y, cosKsi, sinKsi, b1, g2, b2, v1, r1);
            branchVector.openDq1dv1[branchNum] = OpenBranchSide2ReactiveFlowEquationTerm.dq1dv1(y, cosKsi, sinKsi, b1, g2, b2, v1, r1);
        }
    }

    /**
     * Update the power of the loads that follow a model and its derivative, for each load and for each bus. As a term
     * held as an object does, the target of a load is read from the load each time.
     */
    public void updateLoads() {
        for (int i = 0; i < loadVector.busNums.size(); i++) {
            int busNum = loadVector.busNums.getQuick(i);
            double v = busVector.v[busNum];
            int first = loadVector.firstLoad[busNum];
            for (int loadNum = first; loadNum < first + loadVector.loadCount[busNum]; loadNum++) {
                LfLoad load = loadVector.loads.get(loadNum);
                LfLoadModel loadModel = load.getLoadModel().orElseThrow();
                double p = AbstractLoadModelEquationTerm.f(v, load.getTargetP(), loadModel.getExpTermsP());
                double dpdv = AbstractLoadModelEquationTerm.dfdv(v, load.getTargetP(), loadModel.getExpTermsP());
                double q = AbstractLoadModelEquationTerm.f(v, load.getTargetQ(), loadModel.getExpTermsQ());
                double dqdv = AbstractLoadModelEquationTerm.dfdv(v, load.getTargetQ(), loadModel.getExpTermsQ());
                loadVector.p.setQuick(loadNum, p);
                loadVector.q.setQuick(loadNum, q);
                // the first load of the bus sets the sums, so that a bus with one load has the very values of its load
                boolean isFirst = loadNum == first;
                loadVector.busP[busNum] = isFirst ? p : loadVector.busP[busNum] + p;
                loadVector.busDpdv[busNum] = isFirst ? dpdv : loadVector.busDpdv[busNum] + dpdv;
                loadVector.busQ[busNum] = isFirst ? q : loadVector.busQ[busNum] + q;
                loadVector.busDqdv[busNum] = isFirst ? dqdv : loadVector.busDqdv[busNum] + dqdv;
            }
        }
    }

    public void updateNetworkState() {
        Stopwatch stopwatch = Stopwatch.createStarted();

        double[] state = equationSystem.getStateVector().get();
        if (computedState == null || computedState.length != state.length) {
            computedState = state.clone();
        } else {
            System.arraycopy(state, 0, computedState, 0, state.length);
        }
        inputsChanged = false;
        updateBuses(state);
        boolean started = startUpdate;
        startUpdate = false;
        if (started && snapshotState != null && Arrays.equals(snapshotState, state)) {
            restoreSnapshot(state);
        } else {
            updateClosedBranches(state);
            if (started && !snapshotHit) {
                takeSnapshot(state);
            }
        }
        updateOpenBranches();
        updateShunts(state);
        updateHvdcs();
        updateLoads();
        stopwatch.stop();
        LOGGER.debug("AC network vector update in {} us", stopwatch.elapsed(TimeUnit.MICROSECONDS));
    }

    @Override
    public void onDisableChange(LfElement element, boolean disabled) {
        if (element.getType() == ElementType.BUS) {
            busVector.disabled[element.getNum()] = disabled;
        } else if (element.getType() == ElementType.BRANCH) {
            branchVector.disabled[element.getNum()] = disabled;
        } else if (element.getType() == ElementType.SHUNT_COMPENSATOR) {
            shuntVector.disabled[element.getNum()] = disabled;
        } else if (element.getType() == ElementType.HVDC) {
            hvdcVector.disabled[element.getNum()] = disabled;
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
    public void onTapPositionChange(LfBranch branch, int oldPosition, int newPosition, boolean modifiedTapPosition) {
        PiModel piModel = branch.getPiModel();
        onBranchParametersChange(branch.getNum());

        if (modifiedTapPosition) {
            double y = piModel.getY();
            branchVector.y[branch.getNum()] = y;
        }
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
        double[] state = equationSystem.getStateVector().get();
        if (state != null) {
            updateShunt(shunt.getNum(), state);
        }
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
    public void onEquationTermConstantsChanged() {
        // nothing to do
    }

    @Override
    public void onStateUpdate() {
        // a start is noted before an update found unnecessary: the next one, a Newton step, is not a start
        long setCount = equationSystem.getStateVector().getSetCount();
        startUpdate = setCount != lastSetCount;
        lastSetCount = setCount;
        if (!variablesInvalid && !inputsChanged && Arrays.equals(equationSystem.getStateVector().get(), computedState)) {
            // computed for this state already, and nothing else changed since
            skipCount++;
            assert sameAsComputedAgain();
            return;
        }
        updateVariables();
        updateNetworkState();
    }

    /** How many state updates found the flows computed for that state already. */
    public int getSkipCount() {
        return skipCount;
    }

    /** Computes everything again and checks that it gives the values kept, to the last bit. */
    private boolean sameAsComputedAgain() {
        Object[] vectors = {busVector, branchVector, shuntVector, hvdcVector, loadVector};
        java.util.List<double[]> kept = new java.util.ArrayList<>();
        for (Object vector : vectors) {
            for (double[] array : doubleArrays(vector)) {
                kept.add(array.clone());
            }
        }
        updateNetworkState();
        int i = 0;
        for (Object vector : vectors) {
            for (double[] array : doubleArrays(vector)) {
                if (!Arrays.equals(kept.get(i++), array)) {
                    throw new AssertionError("A value of " + vector.getClass().getSimpleName() + " kept for an unchanged state differs from the one computed again");
                }
            }
        }
        return true;
    }

    private static java.util.List<double[]> doubleArrays(Object vector) {
        java.util.List<double[]> arrays = new java.util.ArrayList<>();
        for (java.lang.reflect.Field field : vector.getClass().getFields()) {
            if (field.getType() == double[].class) {
                try {
                    arrays.add((double[]) field.get(vector));
                } catch (IllegalAccessException e) {
                    throw new IllegalStateException(e);
                }
            }
        }
        return arrays;
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
