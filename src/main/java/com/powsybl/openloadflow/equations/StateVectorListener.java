/**
 * Copyright (c) 2021, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.equations;

/**
 * @author Geoffroy Jamgotchian {@literal <geoffroy.jamgotchian at rte-france.com>}
 */
public interface StateVectorListener {

    void onStateUpdate();

    /**
     * Notified instead of {@link #onStateUpdate()} when the caller declares it will only READ derived
     * values from the new state and will not build a Jacobian from it (see
     * {@link StateVector#setValuesOnly(double[])}). A listener that maintains both values and
     * derivatives may then refresh only the values; one that merely invalidates a cache must still do
     * so, which is why the default simply delegates.
     */
    default void onStateUpdate(boolean valuesOnly) {
        onStateUpdate();
    }

    /**
     * Notified instead of {@link #onStateUpdate(boolean)} for a values-only update whose caller will read derived
     * values of the listed closed branches ONLY, before the next full update (see
     * {@link StateVector#setValuesOnly(double[], int[])}). A listener may refresh just those; the default refreshes
     * everything, as for any values-only update.
     */
    default void onStateUpdate(int[] closedBranchNums) {
        onStateUpdate(true);
    }
}
