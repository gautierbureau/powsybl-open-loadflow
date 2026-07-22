/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.dc.equations;

import com.powsybl.math.matrix.Matrix;
import com.powsybl.math.matrix.MatrixFactory;
import com.powsybl.openloadflow.equations.EquationSystem;
import com.powsybl.openloadflow.equations.JacobianMatrix;
import com.powsybl.openloadflow.network.LfBranch;
import com.powsybl.openloadflow.network.LfNetwork;
import com.powsybl.openloadflow.network.PiModel;
import com.powsybl.openloadflow.network.PiModelArray;

import java.util.List;
import java.util.Objects;

/**
 * A DC Jacobian matrix that rebuilds its values when they have actually changed, rather than whenever the state
 * vector moves.
 *
 * <p>No DC derivative reads the state vector: {@link ClosedBranchSide1DcFlowEquationTerm#der} and its side 2
 * counterpart return the branch power, and the HVDC AC emulation terms return the droop, all of them functions of the
 * network rather than of the unknowns. The state appears in {@code eval} and {@code rhs} only. So the generic
 * invalidation on a state update -- right for AC, where the derivatives are functions of V and phi -- costs a full
 * derivative recomputation and a numeric LU update per solve here, and gives back a matrix identical to the one
 * discarded. Repeated solves on one context, which is how a security analysis and a time-series load flow spend their
 * time, pay it once per solve.
 *
 * <p>What does move a DC derivative is the pi model, read live by
 * {@link AbstractClosedBranchDcFlowEquationTerm#getPower()} on a branch carrying a {@link PiModelArray} -- a
 * transformer whose tap OLF may change. Those writes cannot be caught by listening: a tap position change notifies the
 * network listeners, but {@code PiModelArray.setR1} and {@code setA1} are plain field writes, and
 * {@code BranchState.restore} calls them between contingencies and between time steps. So instead of predicting the
 * change we measure it, comparing the three quantities {@code computePower} is built from against the last values the
 * matrix was filled with. That costs three comparisons per movable branch per solve, against a full derivative pass
 * and an LU update, and it is exact whoever did the writing.
 *
 * <p>A branch on a plain pi model is not watched: its power is computed once, in the equation term constructor, so
 * writing to that pi model does not reach the derivative in the first place.
 *
 * <p>Structure and equation-term changes are untouched: a disabled branch or a contingency still invalidates through
 * {@code onEquationChange} / {@code onEquationTermChange}, as before.
 *
 * @author Gautier Bureau {@literal <gautier.bureau at rte-france.com>}
 */
public class DcJacobianMatrix extends JacobianMatrix<DcVariableType, DcEquationType> {

    // the inputs of AbstractClosedBranchDcFlowEquationTerm.computePower, in the order they are watched
    private static final int WATCHED_PER_BRANCH = 3;

    private final List<LfBranch> movableBranches;

    private final double[] watched;

    public DcJacobianMatrix(EquationSystem<DcVariableType, DcEquationType> equationSystem, MatrixFactory matrixFactory,
                            LfNetwork network) {
        super(equationSystem, matrixFactory);
        Objects.requireNonNull(network);
        // a PiModelArray is built only for a tap changer that regulates or that was retained for an action; every
        // other branch has its power frozen at equation term creation and so cannot move a derivative
        movableBranches = network.getBranches().stream()
                .filter(branch -> branch.getPiModel() instanceof PiModelArray)
                .toList();
        watched = new double[WATCHED_PER_BRANCH * movableBranches.size()];
        watchPiModels();
    }

    /**
     * Records what the pi models of the movable branches carry, and says whether that differs from what was recorded
     * last time -- which is to say, whether the matrix was filled from something else than the network now holds.
     */
    private boolean watchPiModels() {
        boolean changed = false;
        for (int i = 0; i < movableBranches.size(); i++) {
            PiModel piModel = movableBranches.get(i).getPiModel();
            int offset = WATCHED_PER_BRANCH * i;
            changed |= watch(offset, piModel.getR());
            changed |= watch(offset + 1, piModel.getX());
            changed |= watch(offset + 2, piModel.getR1());
        }
        return changed;
    }

    private boolean watch(int index, double value) {
        if (watched[index] == value) {
            return false;
        }
        watched[index] = value;
        return true;
    }

    @Override
    public void onStateUpdate() {
        // Deliberately not invalidating: a DC derivative is not a function of the state vector. What it is a function
        // of is watched in getMatrix below.
    }

    @Override
    public Matrix getMatrix() {
        if (watchPiModels()) {
            updateStatus(Status.VALUES_INVALID);
        }
        return super.getMatrix();
    }
}
