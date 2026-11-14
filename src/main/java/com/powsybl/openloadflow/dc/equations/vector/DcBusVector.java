/**
 * Copyright (c) 2025, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.dc.equations.vector;

import com.powsybl.openloadflow.network.LfBus;

import java.util.List;

/**
 * Vectorized (structure-of-arrays) view of the buses for the DC equation system.
 *
 * @author Claude
 */
public class DcBusVector {

    public final int[] phRow;

    final boolean[] disabled;

    public DcBusVector(List<LfBus> buses) {
        int size = buses.size();
        phRow = new int[size];
        disabled = new boolean[size];
        for (int i = 0; i < size; i++) {
            disabled[i] = buses.get(i).isDisabled();
        }
    }

    public int getSize() {
        return disabled.length;
    }
}
