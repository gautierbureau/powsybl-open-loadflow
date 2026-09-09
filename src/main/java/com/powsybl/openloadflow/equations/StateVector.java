/**
 * Copyright (c) 2021, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.equations;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * @author Geoffroy Jamgotchian {@literal <geoffroy.jamgotchian at rte-france.com>}
 */
public class StateVector {

    private double[] array;

    private final List<StateVectorListener> listeners = new ArrayList<>();

    public StateVector() {
        this(null);
    }

    public StateVector(double[] array) {
        this.array = array;
    }

    public void set(double[] array) {
        this.array = Objects.requireNonNull(array);
        notifyStateUpdate(false);
    }

    /**
     * Set the state, declaring that only derived VALUES will be read from it — no Jacobian will be
     * built before the next full {@link #set(double[])}. Listeners are notified through
     * {@link StateVectorListener#onStateUpdate(boolean)} and may skip refreshing derivatives, which on
     * the vectorized AC system is the bulk of the work: refreshing every branch's flows AND all their
     * partial derivatives costs about five times refreshing the flows alone.
     *
     * <p>Callers that build a Jacobian from the state MUST use {@link #set(double[])}: the derivatives
     * left behind by this method belong to the previous state.
     */
    public void setValuesOnly(double[] array) {
        this.array = Objects.requireNonNull(array);
        notifyStateUpdate(true);
    }

    public double[] get() {
        return array;
    }

    public double get(int variableNum) {
        return array[variableNum];
    }

    public void set(int variableNum, double value) {
        array[variableNum] = value;
        notifyStateUpdate();
    }

    public void minus(double[] b) {
        Vectors.minus(array, b);
        notifyStateUpdate();
    }

    public void minusWithRange(double[] b, int begin) {
        for (int i = 0; i < b.length; i++) {
            array[begin + i] -= b[i];
        }
        notifyStateUpdate();
    }

    private void notifyStateUpdate() {
        notifyStateUpdate(false);
    }

    private void notifyStateUpdate(boolean valuesOnly) {
        for (StateVectorListener listener : listeners) {
            listener.onStateUpdate(valuesOnly);
        }
    }

    public void addListener(StateVectorListener listener) {
        listeners.add(Objects.requireNonNull(listener));
    }

    public void removeListener(StateVectorListener listener) {
        listeners.remove(Objects.requireNonNull(listener));
    }
}
