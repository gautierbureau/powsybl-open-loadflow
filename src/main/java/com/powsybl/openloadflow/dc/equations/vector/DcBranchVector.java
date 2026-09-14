/**
 * Copyright (c) 2025, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.dc.equations.vector;

import com.powsybl.openloadflow.dc.equations.AbstractClosedBranchDcFlowEquationTerm;
import com.powsybl.openloadflow.dc.equations.DcEquationSystemCreationParameters;
import com.powsybl.openloadflow.dc.equations.DcEquationSystemCreator;
import com.powsybl.openloadflow.network.LfBranch;
import com.powsybl.openloadflow.network.LfBus;
import com.powsybl.openloadflow.network.LoadFlowModel;
import com.powsybl.openloadflow.network.PiModel;
import com.powsybl.openloadflow.util.Evaluable;

import java.util.List;

import static com.powsybl.openloadflow.network.PiModel.A2;

/**
 * Vectorized (structure-of-arrays) view of the branches for the DC equation system.
 *
 * <p>DC closed-branch flows are affine in the bus angles and the phase shift, so the partial
 * derivatives are constant (equal to {@code ±power}) and are computed only at construction and on tap
 * position changes. Only the flow values {@link #p1}/{@link #p2} depend on the state vector and are
 * refreshed by {@link DcNetworkVector} on each state update.
 *
 * @author Claude
 */
public class DcBranchVector {

    final int[] bus1Num;
    final int[] bus2Num;

    final boolean[] connected1;
    final boolean[] connected2;
    final boolean[] disabled;

    final boolean[] deriveA1;

    // affine coefficient b * (r1 * R2) (see AbstractClosedBranchDcFlowEquationTerm.computePower)
    final double[] power;
    // fixed phase shift angle (used when a1 is not a variable)
    final double[] a1;
    final boolean[] zeroImpedance;

    public final int[] ph1Row;
    public final int[] ph2Row;
    public final int[] a1Row;

    // a1 value used for the current state (variable value when phase controlled, else the fixed a1)
    final double[] a1State;

    // flow values, refreshed on each state update
    final double[] p1;
    final double[] p2;

    // constant partial derivatives (= ±power)
    final double[] dp1dph1;
    final double[] dp1dph2;
    final double[] dp1da1;
    final double[] dp2dph1;
    final double[] dp2dph2;
    final double[] dp2da1;

    // constant right-hand side contributions (non-zero only for fixed phase shifters)
    final double[] rhs1;
    final double[] rhs2;

    private final boolean useTransformerRatio;
    private final DcEquationSystemCreationParameters creationParameters;

    public DcBranchVector(List<LfBranch> branches, DcEquationSystemCreationParameters creationParameters) {
        this.creationParameters = creationParameters;
        this.useTransformerRatio = creationParameters.isUseTransformerRatio();
        int size = branches.size();
        bus1Num = new int[size];
        bus2Num = new int[size];
        connected1 = new boolean[size];
        connected2 = new boolean[size];
        disabled = new boolean[size];
        deriveA1 = new boolean[size];
        power = new double[size];
        a1 = new double[size];
        zeroImpedance = new boolean[size];
        ph1Row = new int[size];
        ph2Row = new int[size];
        a1Row = new int[size];
        a1State = new double[size];
        p1 = new double[size];
        p2 = new double[size];
        dp1dph1 = new double[size];
        dp1dph2 = new double[size];
        dp1da1 = new double[size];
        dp2dph1 = new double[size];
        dp2dph2 = new double[size];
        dp2da1 = new double[size];
        rhs1 = new double[size];
        rhs2 = new double[size];

        for (int i = 0; i < size; i++) {
            LfBranch branch = branches.get(i);
            LfBus bus1 = branch.getBus1();
            LfBus bus2 = branch.getBus2();
            bus1Num[i] = bus1 != null ? bus1.getNum() : -1;
            bus2Num[i] = bus2 != null ? bus2.getNum() : -1;
            connected1[i] = branch.isConnectedSide1();
            connected2[i] = branch.isConnectedSide2();
            disabled[i] = branch.isDisabled();
            deriveA1[i] = DcEquationSystemCreator.isDeriveA1(branch, creationParameters);
            zeroImpedance[i] = branch.isZeroImpedance(LoadFlowModel.DC);
            if (!zeroImpedance[i] && bus1 != null && bus2 != null) {
                updateBranchModel(i, branch);
            }
        }
    }

    /**
     * Refresh the affine model (power, fixed a1, derivatives and rhs) of a branch from its pi model.
     * Called at construction and whenever the tap position changes.
     */
    void updateBranchModel(int branchNum, LfBranch branch) {
        PiModel piModel = branch.getPiModel();
        double p = AbstractClosedBranchDcFlowEquationTerm.computePower(useTransformerRatio, creationParameters.getDcApproximationType(), piModel);
        power[branchNum] = p;
        a1[branchNum] = piModel.getA1();
        // P1 = -power * (ph2 - ph1 + A2 - a1) ; P2 = power * (ph2 - ph1 + A2 - a1)
        dp1dph1[branchNum] = p;
        dp1dph2[branchNum] = -p;
        dp1da1[branchNum] = p;
        dp2dph1[branchNum] = -p;
        dp2dph2[branchNum] = p;
        dp2da1[branchNum] = -p;
        // rhs moves the constant (A2 and, when a1 is not a variable, the fixed a1) to the target vector
        if (deriveA1[branchNum]) {
            rhs1[branchNum] = -p * A2;
            rhs2[branchNum] = p * A2;
        } else {
            rhs1[branchNum] = -p * (A2 - a1[branchNum]);
            rhs2[branchNum] = p * (A2 - a1[branchNum]);
        }
    }

    public int getSize() {
        return disabled.length;
    }

    public Evaluable getP1(int branchNum) {
        return () -> p1[branchNum];
    }

    public Evaluable getP2(int branchNum) {
        return () -> p2[branchNum];
    }
}
