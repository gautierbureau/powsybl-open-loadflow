/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.math.matrix;

import com.powsybl.math.AbstractMathNative;

import java.nio.ByteBuffer;
import java.util.Objects;

/**
 * A sparse LU decomposition with static pivots, from the native math library: the order (AMD) and the fill are
 * computed once from the pattern, and each factorisation then replays a flat list of operations on the values, with
 * the pivots on the diagonal of the ordered matrix. It does not pivot: {@link #factor(double[])} tells how many pivots
 * were too small or multipliers too large, and the caller falls back to a pivoting decomposition (KLU) when there are.
 *
 * <p>The class sits in the package of powsybl-core's matrices, whose natives are in the same library. It needs a
 * powsybl-math-native that has them: {@link #isAvailable()} tells.
 *
 * @author Gautier Bureau {@literal <gautier.bureau at rte-france.com>}
 */
public final class StaticLUDecomposition extends AbstractMathNative implements AutoCloseable {

    private static final boolean AVAILABLE = checkAvailable();

    private long handle;
    private final int size;
    private final int nonZeroCount;

    private static native long create(int[] columnStart, int[] rowIndices);

    private static native int factor(long handle, double[] values);

    private static native void solve(long handle, double[] b, boolean transpose);

    private static native void solve2(long handle, int m, int n, ByteBuffer b, boolean transpose);

    private static native void release(long handle);

    private static boolean checkAvailable() {
        try {
            // a pattern with a single value: creating it loads the library and links the natives
            release(create(new int[] {0, 1}, new int[] {0}));
            return true;
        } catch (UnsatisfiedLinkError | MatrixException e) {
            return false;
        }
    }

    /** True when the native math library has the static LU. */
    public static boolean isAvailable() {
        return AVAILABLE;
    }

    /** Analyses a square pattern given in compressed columns: column j holds the rows rowIndices[columnStart[j]..columnStart[j+1]-1]. */
    public StaticLUDecomposition(int[] columnStart, int[] rowIndices) {
        Objects.requireNonNull(columnStart);
        Objects.requireNonNull(rowIndices);
        if (!AVAILABLE) {
            throw new MatrixException("The native math library has no static LU decomposition");
        }
        size = columnStart.length - 1;
        nonZeroCount = columnStart[size];
        handle = create(columnStart, rowIndices);
    }

    private void checkOpen() {
        if (handle == 0) {
            throw new IllegalStateException("The static LU decomposition is closed");
        }
    }

    /**
     * Factorises the values, in the order of the pattern. Returns how many pivots were too small or multipliers too
     * large: when it is not zero the factors must not be used.
     */
    public int factor(double[] values) {
        checkOpen();
        if (values.length < nonZeroCount) {
            throw new MatrixException("Expected " + nonZeroCount + " values, got " + values.length);
        }
        return factor(handle, values);
    }

    /** Solves A x = b in place. */
    public void solve(double[] b) {
        checkOpen();
        checkSize(b.length);
        solve(handle, b, false);
    }

    /** Solves A^T x = b in place. */
    public void solveTransposed(double[] b) {
        checkOpen();
        checkSize(b.length);
        solve(handle, b, true);
    }

    /** Solves A X = B in place, for the columns of a dense matrix whose values are in a direct buffer. */
    public void solve(DenseMatrix b) {
        solve(b, false);
    }

    /** Solves A^T X = B in place, for the columns of a dense matrix whose values are in a direct buffer. */
    public void solveTransposed(DenseMatrix b) {
        solve(b, true);
    }

    private void solve(DenseMatrix b, boolean transpose) {
        checkOpen();
        checkSize(b.getRowCount());
        solve2(handle, b.getRowCount(), b.getColumnCount(), b.getBuffer(), transpose);
    }

    private void checkSize(int length) {
        if (length != size) {
            throw new MatrixException("Expected " + size + " values in the right-hand side, got " + length);
        }
    }

    public int getSize() {
        return size;
    }

    @Override
    public void close() {
        if (handle != 0) {
            release(handle);
            handle = 0;
        }
    }
}
