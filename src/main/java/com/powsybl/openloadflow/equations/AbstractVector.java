/**
 * Copyright (c) 2022, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.equations;

import java.util.Arrays;
import java.util.Objects;

/**
 * @author Geoffroy Jamgotchian {@literal <geoffroy.jamgotchian at rte-france.com>}
 */
public abstract class AbstractVector<V extends Enum<V> & Quantity, E extends Enum<E> & Quantity> implements Vector {

    protected final EquationSystem<V, E> equationSystem;

    private double[] array;

    private enum Status {
        VALID,
        VECTOR_INVALID, // structure has changed
        VALUES_INVALID // same structure but values have to be updated
    }

    private Status status = Status.VECTOR_INVALID;

    private final EquationSystemIndexListener<V, E> equationSystemIndexListener = new EquationSystemIndexListener<>() {

        @Override
        public void onEquationChange(SingleEquation<V, E> equation, ChangeType changeType) {
            invalidateVector();
        }

        @Override
        public void onVariableChange(Variable<V> variable, ChangeType changeType) {
            // nothing to do
        }

        @Override
        public void onEquationTermChange(SingleEquationTerm<V, E> term) {
            // nothing to do
        }

        @Override
        public void onEquationArrayChange(EquationArray<V, E> equationArray, ChangeType changeType) {
            invalidateVector();
        }

        @Override
        public void onEquationTermArrayChange(EquationTermArray<V, E> equationTermArray, int termNum, ChangeType changeType) {
            // nothing to do
        }

        @Override
        public void onEquationIndexOrderChanged() {
            invalidateVector();
        }

        @Override
        public void onEquationTermConstantsChanged() {
            // nothing to do
        }

        @Override
        public void onInactiveColumnsChange() {
            // same columns, other inactive columns: zero at the inactive columns
            invalidateValues();
        }
    };

    protected AbstractVector(EquationSystem<V, E> equationSystem) {
        this.equationSystem = Objects.requireNonNull(equationSystem);
        equationSystem.getIndex().addListener(equationSystemIndexListener);
    }

    protected void invalidateValues() {
        if (status == Status.VALID) {
            status = Status.VALUES_INVALID;
        }
    }

    protected void invalidateVector() {
        status = Status.VECTOR_INVALID;
    }

    protected void validate() {
        status = Status.VALID;
    }

    @Override
    public double[] getArray() {
        // the changes the index has pending may change the layout of the vector: resolved first
        equationSystem.getIndex().validate();
        switch (status) {
            case VECTOR_INVALID:
                array = createArray();
                validate();
                break;

            case VALUES_INVALID:
                updateArray(array);
                validate();
                break;

            default:
                // nothing to do
                break;
        }
        return array;
    }

    /**
     * An array of the given length for {@link #createArray()}, filled with zeros: the one of the vector when it already
     * has that length, so that a change of structure that keeps the size does not allocate a new array. Nothing keeps
     * the array of a vector across a change of its structure.
     */
    protected double[] reuseOrCreateArray(int length) {
        if (array != null && array.length == length) {
            Arrays.fill(array, 0);
            return array;
        }
        return new double[length];
    }

    protected abstract double[] createArray();

    protected abstract void updateArray(double[] array);

    @Override
    public void minus(Vector other) {
        Objects.requireNonNull(other);
        Vectors.minus(getArray(), other.getArray());
    }
}
