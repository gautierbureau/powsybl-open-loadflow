/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.math.matrix;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * @author Gautier Bureau {@literal <gautier.bureau at rte-france.com>}
 */
class StaticLUDecompositionTest {

    // A = [[4, 1, 0], [2, 5, 1], [0, 3, 6]] in compressed columns
    private static final int[] COLUMN_START = {0, 2, 5, 7};
    private static final int[] ROW_INDICES = {0, 1, 0, 1, 2, 1, 2};
    private static final double[] VALUES = {4, 2, 1, 5, 3, 1, 6};
    private static final double[][] A = {{4, 1, 0}, {2, 5, 1}, {0, 3, 6}};

    @BeforeAll
    static void checkAvailable() {
        assumeTrue(StaticLUDecomposition.isAvailable(), "The native math library has no static LU");
    }

    private static double[] multiply(double[] x, boolean transposed) {
        double[] y = new double[3];
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                y[i] += (transposed ? A[j][i] : A[i][j]) * x[j];
            }
        }
        return y;
    }

    @Test
    void solves() {
        try (StaticLUDecomposition lu = new StaticLUDecomposition(COLUMN_START, ROW_INDICES)) {
            assertEquals(0, lu.factor(VALUES));
            double[] b = {1, 2, 3};
            double[] x = b.clone();
            lu.solve(x);
            assertArrayEquals(b, multiply(x, false), 1e-12);
            x = b.clone();
            lu.solveTransposed(x);
            assertArrayEquals(b, multiply(x, true), 1e-12);
        }
    }

    @Test
    void solvesColumns() {
        try (StaticLUDecomposition lu = new StaticLUDecomposition(COLUMN_START, ROW_INDICES)) {
            assertEquals(0, lu.factor(VALUES));
            ByteBuffer buffer = ByteBuffer.allocateDirect(3 * 2 * Double.BYTES).order(ByteOrder.nativeOrder());
            DenseMatrix b = new DenseMatrix(3, 2, () -> buffer);
            double[][] columns = {{1, 2, 3}, {-1, 0, 2}};
            for (int j = 0; j < 2; j++) {
                for (int i = 0; i < 3; i++) {
                    b.set(i, j, columns[j][i]);
                }
            }
            lu.solveTransposed(b);
            for (int j = 0; j < 2; j++) {
                double[] x = {b.get(0, j), b.get(1, j), b.get(2, j)};
                assertArrayEquals(columns[j], multiply(x, true), 1e-12);
            }
        }
    }

    @Test
    void tellsBadPivots() {
        // the same pattern with a zero on the diagonal that elimination does not fill: it needs pivoting
        try (StaticLUDecomposition lu = new StaticLUDecomposition(COLUMN_START, ROW_INDICES)) {
            assertTrue(lu.factor(new double[] {0, 2, 1, 5, 3, 1, 6}) > 0);
            // the same analysis factorises good values again
            assertEquals(0, lu.factor(VALUES));
        }
    }

    @Test
    void checksSizes() {
        try (StaticLUDecomposition lu = new StaticLUDecomposition(COLUMN_START, ROW_INDICES)) {
            assertThrows(MatrixException.class, () -> lu.factor(new double[2]));
            assertEquals(0, lu.factor(VALUES));
            assertThrows(MatrixException.class, () -> lu.solve(new double[2]));
        }
    }
}
