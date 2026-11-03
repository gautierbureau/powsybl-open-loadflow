/*
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.ac.equations;

/**
 * Settings of the smoothed limits of a voltage controller (see {@link LimitComplementarityEquationTerm}): instead of
 * switching between holding its voltage and being held at a limit, the controller gets two voltage slacks as
 * unknowns, tied to its control variable by two smoothed complementarity equations. Used for the reactive limits of
 * generators and for the ratio limits of tap changers, with different values.
 *
 * @param mu           smoothing of the complementarity corner, in the unit of the control variable ({@code > 0}):
 *                     the smaller, the closer to the switch
 * @param voltageScale weight of a per unit voltage slack against a headroom of the control variable in the
 *                     complementarity ({@code > 0}), of the order of the change of the control variable that moves the
 *                     controlled voltage by 1 per unit. Too small, every controller acts as if near its limit: at
 *                     {@code a} from its limit the voltage moves by about {@code mu^2 / (a^2 voltageScale)} per unit of
 *                     the control variable.
 * @author Gautier Bureau {@literal <gautier.bureau at rte-france.com>}
 */
public record LimitsSmoothing(double mu, double voltageScale) {

    /** Generator reactive limits: per unit on the base power. */
    public static final double REACTIVE_DEFAULT_MU = 0.05;

    /** Keeps the IEEE-14 gradients within a few percent of the exact ones away from a limit, and continuous across it. */
    public static final double REACTIVE_DEFAULT_VOLTAGE_SCALE = 50.0;

    /** Tap changer ratio limits: about one tap step. */
    public static final double RATIO_DEFAULT_MU = 0.01;

    /** A ratio change moves the voltage it controls by about as much, per unit. */
    public static final double RATIO_DEFAULT_VOLTAGE_SCALE = 1.0;

    public LimitsSmoothing {
        if (!(mu > 0) || !Double.isFinite(mu)) {
            throw new IllegalArgumentException("Limits smoothing mu must be finite and > 0: " + mu);
        }
        if (!(voltageScale > 0) || !Double.isFinite(voltageScale)) {
            throw new IllegalArgumentException("Limits smoothing voltage scale must be finite and > 0: " + voltageScale);
        }
    }
}
