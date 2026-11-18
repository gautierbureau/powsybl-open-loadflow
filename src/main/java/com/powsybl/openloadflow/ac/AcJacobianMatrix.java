/**
 * Copyright (c) 2023, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.ac;

import com.powsybl.math.matrix.DenseMatrix;
import com.powsybl.math.matrix.MatrixFactory;
import com.powsybl.openloadflow.ac.equations.AcEquationType;
import com.powsybl.openloadflow.ac.equations.AcPlacement;
import com.powsybl.openloadflow.ac.equations.AcVariableType;
import com.powsybl.openloadflow.equations.EquationSystem;
import com.powsybl.openloadflow.equations.JacobianMatrix;
import com.powsybl.openloadflow.equations.fixed.FixedJacobian;
import com.powsybl.openloadflow.network.AbstractLfNetworkListener;
import com.powsybl.openloadflow.network.LfLoad;
import com.powsybl.openloadflow.network.LfNetwork;
import com.powsybl.openloadflow.network.LfNetworkListener;

import java.util.Objects;

/**
 * The Jacobian matrix of the AC equations. Whatever the computation, it solves on a {@link FixedJacobian}: a matrix,
 * sparse with the default matrix factory, whose pattern stays the same when buses hold or release their voltage, when
 * branches or buses are lost and when controls are switched, so that its LU decomposition is analysed once.
 * Right-hand sides and solutions are those of {@link JacobianMatrix}, in the numbering of the index.
 *
 * <p>{@link #getMatrix()} is still the matrix in the numbering of the index, built on demand: it has no LU
 * decomposition any more.
 *
 * @author Geoffroy Jamgotchian {@literal <geoffroy.jamgotchian at rte-france.com>}
 */
public class AcJacobianMatrix extends JacobianMatrix<AcVariableType, AcEquationType> {

    private final LfNetwork network;

    private final FixedJacobian<AcVariableType, AcEquationType> fixedJacobian;

    private final LfNetworkListener networkListener = new AbstractLfNetworkListener() {

        private void onLoadTargetChange(LfLoad load) {
            load.getLoadModel().ifPresent(ignored -> updateStatus(Status.VALUES_INVALID));
        }

        @Override
        public void onLoadActivePowerTargetChange(LfLoad load, double oldTargetP, double newTargetP) {
            onLoadTargetChange(load);
        }

        @Override
        public void onLoadReactivePowerTargetChange(LfLoad load, double oldTargetQ, double newTargetQ) {
            onLoadTargetChange(load);
        }
    };

    public AcJacobianMatrix(EquationSystem<AcVariableType, AcEquationType> equationSystem, MatrixFactory matrixFactory,
                            LfNetwork network) {
        super(equationSystem, matrixFactory);
        this.network = Objects.requireNonNull(network);
        fixedJacobian = new FixedJacobian<>(equationSystem, new AcPlacement(network), matrixFactory);
        network.addListener(networkListener);
    }

    public FixedJacobian<AcVariableType, AcEquationType> getFixedJacobian() {
        return fixedJacobian;
    }

    @Override
    protected void updateStatus(Status status) {
        super.updateStatus(status);
        fixedJacobian.invalidate();
    }

    @Override
    public void solve(double[] b) {
        checkSquare();
        fixedJacobian.solve(b);
    }

    @Override
    public void solveTransposed(double[] b) {
        checkSquare();
        fixedJacobian.solveTransposed(b);
    }

    @Override
    public void solve(DenseMatrix b) {
        checkSquare();
        fixedJacobian.solve(b);
    }

    @Override
    public void solveTransposed(DenseMatrix b) {
        checkSquare();
        fixedJacobian.solveTransposed(b);
    }

    @Override
    public void close() {
        super.close();
        fixedJacobian.close();
        network.removeListener(networkListener);
    }
}
