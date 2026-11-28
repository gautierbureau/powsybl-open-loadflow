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

    /** How many times a state was set, as opposed to updated by {@link #minus(double[])}: a new start. */
    private long setCount;

    public void set(double[] array) {
        this.array = Objects.requireNonNull(array);
        setCount++;
        notifyStateUpdate();
    }

    public long getSetCount() {
        return setCount;
    }

    /**
     * Sets the state for a caller that will only read derived values from it: no Jacobian is built from it before the
     * next {@link #set(double[])}. Listeners are notified through {@link StateVectorListener#onStateUpdate(boolean)},
     * and may then skip what only a Jacobian needs, such as the partial derivatives. A caller that builds a Jacobian
     * from the state must use {@link #set(double[])}.
     */
    public void setValuesOnly(double[] array) {
        this.array = Objects.requireNonNull(array);
        setCount++;
        for (StateVectorListener listener : listeners) {
            listener.onStateUpdate(true);
        }
    }

    /**
     * As {@link #setValuesOnly(double[])}, for a caller that then reads the derived values of the given closed branches
     * only (and of the buses), before the next full update. Listeners are notified through
     * {@link StateVectorListener#onStateUpdate(int[])}, and may leave every other branch's derived values stale.
     */
    public void setValuesOnly(double[] array, int[] closedBranchNums) {
        this.array = Objects.requireNonNull(array);
        Objects.requireNonNull(closedBranchNums);
        setCount++;
        for (StateVectorListener listener : listeners) {
            listener.onStateUpdate(closedBranchNums);
        }
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
        for (StateVectorListener listener : listeners) {
            listener.onStateUpdate();
        }
    }

    public void addListener(StateVectorListener listener) {
        listeners.add(Objects.requireNonNull(listener));
    }

    public void removeListener(StateVectorListener listener) {
        listeners.remove(Objects.requireNonNull(listener));
    }
}
