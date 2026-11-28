/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.ac.outerloop;

/**
 * The decisions of {@link AcDcConverterPowerLimitsOuterLoop} on the converters of one DC component, as arrays: the
 * converter controlling the DC voltage (V_DC or DC_DROOP mode) whose active power is the furthest beyond one of its
 * limits, by more than the maximum active power mismatch, switches to P_PCC mode with its target at that limit. At most
 * one converter per DC component, per check.
 *
 * <p>{@link #newTargetP} is an expression over primitives; {@link #selectConverter} walks the arrays. Nothing reads or
 * writes a network.
 *
 * @author Gautier Bureau {@literal <gautier.bureau at rte-france.com>}
 */
public final class AcDcConverterPowerLimitsDecisions {

    private AcDcConverterPowerLimitsDecisions() {
    }

    /**
     * The converter to switch to P_PCC mode: the largest distance beyond a limit wins, the lower limit tested before the
     * upper one, the first converter on a tie. A distance must exceed {@code maxActivePowerMismatch}, so that a violation
     * within the solver's error is ignored.
     *
     * @param controlsDcVoltage the converter is in V_DC or DC_DROOP mode; the others are not candidates
     * @return the index of the converter, -1 when none is beyond its limits
     */
    public static int selectConverter(int count, boolean[] controlsDcVoltage, double[] pac, double[] minP, double[] maxP,
                                      double maxActivePowerMismatch) {
        double maxDistanceToAnyLimit = maxActivePowerMismatch;
        int selected = -1;
        for (int i = 0; i < count; i++) {
            if (controlsDcVoltage[i]) {
                double distanceToLowerLimit = minP[i] - pac[i];
                if (distanceToLowerLimit > maxDistanceToAnyLimit) {
                    // Converter is below its lower limit
                    maxDistanceToAnyLimit = distanceToLowerLimit;
                    selected = i;
                }
                double distanceToHigherLimit = pac[i] - maxP[i];
                if (distanceToHigherLimit > maxDistanceToAnyLimit) {
                    // Converter is above its higher limit
                    maxDistanceToAnyLimit = distanceToHigherLimit;
                    selected = i;
                }
            }
        }
        return selected;
    }

    /** The target P of a converter switched to P_PCC mode: the limit it exceeds. */
    public static double newTargetP(double pac, double minP, double maxP) {
        return pac < minP ? minP : maxP;
    }
}
