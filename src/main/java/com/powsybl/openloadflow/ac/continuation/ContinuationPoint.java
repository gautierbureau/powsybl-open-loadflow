/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.ac.continuation;

import java.util.Map;

/**
 * A single converged point of the continuation, i.e. one point of the P-V curve.
 *
 * @param loadFactor                the load increase factor (lambda) at this point ({@code 0} is the base case)
 * @param participatingLoadTargetPMw the sum of the active power targets of the participating loads, in MW
 * @param minVoltage                the lowest bus voltage magnitude in per unit at this point
 * @param minVoltageBusId           the id of the bus carrying the lowest voltage magnitude (the weakest bus)
 * @param stable                    whether the point is on the upper (stable) branch of the P-V curve; points
 *                                  traced beyond the nose, on the lower branch, are unstable
 * @param busVoltages               per-bus voltage magnitude in per unit (empty when voltage recording is off)
 * @param busDvDlambda              per-bus voltage sensitivity dV/dlambda in per unit (empty for the base case or
 *                                  when voltage recording is off), a finite difference with the previous point
 *
 * @author Claude
 */
public record ContinuationPoint(double loadFactor,
                                double participatingLoadTargetPMw,
                                double minVoltage,
                                String minVoltageBusId,
                                boolean stable,
                                Map<String, Double> busVoltages,
                                Map<String, Double> busDvDlambda) {

    /**
     * Per-bus voltage sensitivity dV/dlambda between {@code previous} and a point at {@code loadFactor} carrying
     * {@code voltages}, by finite difference. Returns an empty map when it cannot be computed (no previous point,
     * no recorded voltages, or a negligible load factor change).
     */
    public static Map<String, Double> derivativeVsLoadFactor(Map<String, Double> voltages, double loadFactor,
                                                             ContinuationPoint previous) {
        if (previous == null || voltages.isEmpty() || previous.busVoltages().isEmpty()) {
            return Map.of();
        }
        double dLambda = loadFactor - previous.loadFactor();
        if (Math.abs(dLambda) < 1e-12) {
            return Map.of();
        }
        Map<String, Double> result = new java.util.LinkedHashMap<>();
        for (var e : voltages.entrySet()) {
            Double previousVoltage = previous.busVoltages().get(e.getKey());
            if (previousVoltage != null) {
                result.put(e.getKey(), (e.getValue() - previousVoltage) / dLambda);
            }
        }
        return result;
    }
}
