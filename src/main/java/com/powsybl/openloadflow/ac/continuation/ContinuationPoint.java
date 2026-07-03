/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.ac.continuation;

/**
 * A single converged point of the continuation, i.e. one point of the P-V curve.
 *
 * @param loadFactor                the load increase factor (lambda) at this point ({@code 0} is the base case)
 * @param participatingLoadTargetPMw the sum of the active power targets of the participating loads, in MW
 * @param minVoltage                the lowest bus voltage magnitude in per unit at this point
 * @param minVoltageBusId           the id of the bus carrying the lowest voltage magnitude (the weakest bus)
 *
 * @author Claude
 */
public record ContinuationPoint(double loadFactor,
                                double participatingLoadTargetPMw,
                                double minVoltage,
                                String minVoltageBusId) {
}
