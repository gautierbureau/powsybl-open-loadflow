/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.equations.fixed;

import com.powsybl.math.matrix.DenseMatrix;
import com.powsybl.math.matrix.LUDecomposition;
import com.powsybl.math.matrix.Matrix;
import com.powsybl.math.matrix.MatrixException;
import com.powsybl.math.matrix.MatrixFactory;
import com.powsybl.math.matrix.SparseMatrix;
import com.powsybl.math.matrix.StaticLUDecomposition;
import com.powsybl.openloadflow.equations.EquationSystem;
import com.powsybl.openloadflow.equations.Quantity;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.DoubleBuffer;

/**
 * The Jacobian of a {@link FixedEquationSystem}, factorised. The matrix is created once on the pattern, through the
 * matrix factory: with OLF's default one, a sparse matrix in compressed columns, whose values are then filled in
 * place. Its LU decomposition is analysed once: afterwards only the numerical factorisation is updated, as long as
 * the pattern does not grow.
 *
 * <p>Right-hand sides and solutions are in the numbering of OLF's index, like those of {@code JacobianMatrix}: by
 * equation column and by variable row. They are moved to the slots and back. A slot that holds no equation gets a
 * zero right-hand side: its variable does not move.
 *
 * @author Gautier Bureau {@literal <gautier.bureau at rte-france.com>}
 */
public class FixedJacobian<V extends Enum<V> & Quantity, E extends Enum<E> & Quantity> implements AutoCloseable {

    /** Right-hand sides are moved to the slots, solved together and moved back by groups of at most this many values. */
    private static final int GROUP_VALUE_COUNT = 1 << 20;

    /**
     * Factorises with the static-pivot LU of the native math library first, when it has one, and with KLU only when
     * the static pivots are not good, when this system property is true. An experiment: off by default.
     */
    public static final String STATIC_LU_PROPERTY = "olf.fixedJacobian.staticLu";

    private final FixedEquationSystem<V, E> fixed;
    private final MatrixFactory matrixFactory;

    private Matrix matrix;
    private LUDecomposition lu;
    /** The row indices of the pattern the matrix was created on: another array means another pattern. */
    private int[] patternRowIndices;
    /** For a matrix that does not store its values in the order of the pattern: where each one goes. */
    private int[] elementIndices;
    private boolean valid;

    private int[] rowOfSlot;
    /** The placement {@link #rowOfSlot} and {@link #columnOfSlot} are of. */
    private int placementOfSlots = -1;
    private int[] columnOfSlot;
    private double[] bySlot;

    private int analysisCount;
    private int pivotingCount;
    /** True when the next numerical factorisation chooses its pivots instead of trying those of the previous one. */
    private boolean freshPivots;

    private final boolean staticLuEnabled;
    private StaticLUDecomposition staticLu;
    /** True when the current factors are those of {@link #staticLu}, false when they are those of {@link #lu}. */
    private boolean staticFactors;
    private int staticFactorisationCount;
    private int staticFallbackCount;

    public FixedJacobian(EquationSystem<V, E> equationSystem, Placement<V, E> placement, MatrixFactory matrixFactory) {
        this.fixed = new FixedEquationSystem<>(equationSystem, placement, FixedEquationSystem.Scope.ALL);
        this.matrixFactory = matrixFactory;
        this.staticLuEnabled = Boolean.getBoolean(STATIC_LU_PROPERTY) && StaticLUDecomposition.isAvailable();
    }

    /** The fixed view of the equation system the matrix is filled from. */
    public FixedEquationSystem<V, E> getFixedEquationSystem() {
        return fixed;
    }

    /** To call when the state, an activation or a constant of the equation system changes. */
    public void invalidate() {
        valid = false;
    }

    private boolean slotsAreCurrent(FixedStructure<V, E> structure) {
        for (int slot = 0; slot < rowOfSlot.length; slot++) {
            int equation = fixed.getEquationOfSlot(slot);
            if (rowOfSlot[slot] != structure.getVariable(slot).getRow()
                    || columnOfSlot[slot] != (equation < 0 ? -1 : structure.getColumnInEquationSystem(equation))) {
                return false;
            }
        }
        return true;
    }

    private void update() {
        if (valid) {
            return;
        }
        FixedStructure<V, E> structure = fixed.getStructure();
        if (patternRowIndices != structure.getRowIndices()) {
            closeLu();
            createMatrix(structure);
        }
        if (elementIndices == null) {
            fixed.fill(((SparseMatrix) matrix).getValues());
        } else {
            fixed.fill();
            matrix.reset();
            for (int i = 0; i < elementIndices.length; i++) {
                matrix.addAtIndex(elementIndices[i], fixed.getValues()[i]);
            }
        }
        if (staticLuEnabled && elementIndices == null && factorStatic(structure)) {
            staticFactors = true;
        } else {
            staticFactors = false;
            factorKlu();
        }
        // the rows and the columns of the slots change only with a placement
        if (fixed.getPlacementCount() != placementOfSlots) {
            fixed.getRowsAndColumnsOfSlots(rowOfSlot, columnOfSlot);
            placementOfSlots = fixed.getPlacementCount();
        }
        assert slotsAreCurrent(structure) : "The rows and columns of the slots kept from the last placement are not those of OLF's index";
        valid = true;
    }

    /** Factorises with the static pivots; false when they were not good, and KLU has to factorise. */
    private boolean factorStatic(FixedStructure<V, E> structure) {
        if (staticLu == null) {
            staticLu = new StaticLUDecomposition(structure.getColumnStart(), structure.getRowIndices());
        }
        staticFactorisationCount++;
        if (staticLu.factor(((SparseMatrix) matrix).getValues()) == 0) {
            return true;
        }
        staticFallbackCount++;
        return false;
    }

    private void factorKlu() {
        try {
            if (lu == null) {
                lu = matrix.decomposeLU();
                analysisCount++;
            } else {
                updateLu();
            }
        } catch (MatrixException e) {
            closeLu();
            throw e;
        }
    }

    /**
     * The numerical factorisation only, on the same analysis. The pivots of the previous one are tried first, whatever
     * changed since, values or the equations the slots hold: the decomposition checks their growth and chooses new
     * ones when they are no longer good. When one of them has become zero the update fails, and new ones are asked for.
     */
    private void updateLu() {
        if (freshPivots) {
            freshPivots = false;
            pivotingCount++;
            lu.update(false);
            return;
        }
        try {
            lu.update(true);
        } catch (MatrixException e) {
            pivotingCount++;
            lu.update(false);
        }
    }

    private void createMatrix(FixedStructure<V, E> structure) {
        int slotCount = structure.getSlotCount();
        int[] columnStart = structure.getColumnStart();
        int[] rowIndices = structure.getRowIndices();
        matrix = matrixFactory.create(slotCount, slotCount, rowIndices.length);
        int[] indices = new int[rowIndices.length];
        boolean inPatternOrder = matrix instanceof SparseMatrix;
        for (int slot = 0; slot < slotCount; slot++) {
            for (int i = columnStart[slot]; i < columnStart[slot + 1]; i++) {
                indices[i] = matrix.addAndGetIndex(rowIndices[i], slot, 0);
                inPatternOrder &= indices[i] == i;
            }
        }
        elementIndices = inPatternOrder ? null : indices;
        patternRowIndices = rowIndices;
        rowOfSlot = new int[slotCount];
        columnOfSlot = new int[slotCount];
        placementOfSlots = -1;
        bySlot = new double[slotCount];
    }

    private void solve(double[] b, boolean transposed) {
        update();
        int[] from = transposed ? columnOfSlot : rowOfSlot;
        int[] to = transposed ? rowOfSlot : columnOfSlot;
        for (int slot = 0; slot < bySlot.length; slot++) {
            bySlot[slot] = from[slot] >= 0 ? b[from[slot]] : 0;
        }
        if (staticFactors) {
            if (transposed) {
                staticLu.solveTransposed(bySlot);
            } else {
                staticLu.solve(bySlot);
            }
        } else if (transposed) {
            lu.solveTransposed(bySlot);
        } else {
            lu.solve(bySlot);
        }
        for (int slot = 0; slot < bySlot.length; slot++) {
            if (to[slot] >= 0) {
                b[to[slot]] = bySlot[slot];
            }
        }
    }

    private void solve(DenseMatrix b, boolean transposed) {
        update();
        int[] from = transposed ? columnOfSlot : rowOfSlot;
        int[] to = transposed ? rowOfSlot : columnOfSlot;
        int slotCount = bySlot.length;
        int groupColumnCount = Math.max(1, GROUP_VALUE_COUNT / Math.max(1, slotCount));
        DenseMatrix group = null;
        DoubleBuffer groupValues = null;
        double[] values = null;
        for (int first = 0; first < b.getColumnCount(); first += groupColumnCount) {
            int columnCount = Math.min(groupColumnCount, b.getColumnCount() - first);
            if (group == null || group.getColumnCount() != columnCount) {
                ByteBuffer buffer = ByteBuffer.allocateDirect(slotCount * columnCount * Double.BYTES).order(ByteOrder.LITTLE_ENDIAN);
                group = new DenseMatrix(slotCount, columnCount, () -> buffer);
                groupValues = buffer.asDoubleBuffer();
                values = new double[slotCount * columnCount];
            }
            for (int j = 0; j < columnCount; j++) {
                for (int slot = 0; slot < slotCount; slot++) {
                    values[j * slotCount + slot] = from[slot] >= 0 ? b.get(from[slot], first + j) : 0;
                }
            }
            groupValues.put(0, values);
            if (staticFactors) {
                if (transposed) {
                    staticLu.solveTransposed(group);
                } else {
                    staticLu.solve(group);
                }
            } else if (transposed) {
                lu.solveTransposed(group);
            } else {
                lu.solve(group);
            }
            groupValues.get(0, values);
            for (int j = 0; j < columnCount; j++) {
                for (int slot = 0; slot < slotCount; slot++) {
                    if (to[slot] >= 0) {
                        b.set(to[slot], first + j, values[j * slotCount + slot]);
                    }
                }
            }
        }
    }

    /** Solves for a right-hand side by variable row; the solution is by equation column. */
    public void solve(double[] b) {
        solve(b, false);
    }

    /** Solves the transposed system, the one of a Newton step: right-hand side by equation column, solution by variable row. */
    public void solveTransposed(double[] b) {
        solve(b, true);
    }

    /** The same as {@link #solve(double[])} for a block of right-hand sides, one per column. */
    public void solve(DenseMatrix b) {
        solve(b, false);
    }

    /** The same as {@link #solveTransposed(double[])} for a block of right-hand sides, one per column. */
    public void solveTransposed(DenseMatrix b) {
        solve(b, true);
    }

    /** How many times the matrix was analysed: once, plus once each time the pattern grew or a factorisation failed. */
    public int getAnalysisCount() {
        return analysisCount;
    }

    /** How many times a pivot of the previous factorisation had become zero, so that new ones were chosen. */
    /**
     * The next numerical factorisation chooses its pivots, and the later ones try those first. A computation whose
     * results must not depend on the ones solved before it calls this when it starts: the pivots it factorises with
     * then depend on its own matrices only.
     */
    public void forgetPivots() {
        freshPivots = true;
    }

    public int getPivotingCount() {
        return pivotingCount;
    }

    /** How many zeros the diagonal holds in the current state: see {@link FixedEquationSystem#getZeroDiagonalCount()}. */
    public int getZeroDiagonalCount() {
        return fixed.getZeroDiagonalCount();
    }

    /** How many times the static LU factorised, when it is on. */
    public int getStaticFactorisationCount() {
        return staticFactorisationCount;
    }

    /** How many of the static factorisations had bad pivots, so that KLU factorised instead. */
    public int getStaticFallbackCount() {
        return staticFallbackCount;
    }

    private void closeLu() {
        if (lu != null) {
            lu.close();
            lu = null;
        }
        if (staticLu != null) {
            staticLu.close();
            staticLu = null;
        }
        staticFactors = false;
    }

    @Override
    public void close() {
        closeLu();
        fixed.close();
    }
}
