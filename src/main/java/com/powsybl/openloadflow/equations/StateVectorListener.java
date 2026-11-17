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
     * Notified instead of {@link #onStateUpdate()} by {@link StateVector#setValuesOnly(double[])} when
     * {@code valuesOnly}: the caller only reads derived values from the new state, and builds no Jacobian from it. A
     * listener may then skip what only a Jacobian needs. By default, the full update.
     */
    default void onStateUpdate(boolean valuesOnly) {
        onStateUpdate();
    }

    /**
     * Notified by {@link StateVector#setValuesOnly(double[], int[])}: a values-only update whose caller reads the
     * derived values of the given closed branches only, before the next full update. By default, as any values-only
     * update.
     */
    default void onStateUpdate(int[] closedBranchNums) {
        onStateUpdate(true);
    }
}
