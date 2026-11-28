/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.network;

/**
 * What keeps the power factor of each load of a {@link LfLoad} constant when its active power target moves, as
 * arrays, one entry per original load: its reactive power (pu), its power factor (Q0 over P0, 1 when P0 is zero) and
 * its share of the {@link LfLoad}'s variable active power.
 *
 * @author Gautier Bureau {@literal <gautier.bureau at rte-france.com>}
 */
public record LoadPowerFactorTerms(double[] q0, double[] powerFactor, double[] participationFactor) {

    /** The reactive power target once the active power target moved by {@code diffTargetP} from its initial value. */
    public double newTargetQ(double diffTargetP) {
        double newLoadTargetQ = 0;
        for (int i = 0; i < q0.length; i++) {
            newLoadTargetQ += q0[i] + powerFactor[i] * diffTargetP * participationFactor[i];
        }
        return newLoadTargetQ;
    }
}
