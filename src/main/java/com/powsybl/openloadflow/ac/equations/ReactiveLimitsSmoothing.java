/*
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.ac.equations;

/**
 * Settings of the smoothed reactive limits of local generator voltage control (see
 * {@link GeneratorReactiveSplitFbEquationTerm}): instead of switching a bus between PV and PQ, each controller bus
 * gets its reactive generation and two voltage slacks as unknowns, tied by two smoothed complementarity equations.
 *
 * @param mu           smoothing of the complementarity corner, per unit on the base power ({@code > 0}): the
 *                     smaller, the closer to the PV/PQ switch
 * @param voltageScale weight of a per unit voltage slack against a per unit reactive headroom in the
 *                     complementarity ({@code > 0}), of the order of the reactive power that moves a bus voltage by
 *                     1 per unit. Too small, every bus controlling its voltage droops as if near its limit: at a bus
 *                     {@code a} from its limit the voltage moves by about {@code mu^2 / (a^2 voltageScale)} per unit
 *                     of reactive power.
 * @author Gautier Bureau {@literal <gautier.bureau at rte-france.com>}
 */
public record ReactiveLimitsSmoothing(double mu, double voltageScale) {

    public static final double DEFAULT_MU = 0.05;

    /** Keeps the IEEE-14 gradients within a few percent of the exact ones away from a limit, and continuous across it. */
    public static final double DEFAULT_VOLTAGE_SCALE = 50.0;

    public ReactiveLimitsSmoothing {
        if (!(mu > 0) || !Double.isFinite(mu)) {
            throw new IllegalArgumentException("Reactive limits smoothing mu must be finite and > 0: " + mu);
        }
        if (!(voltageScale > 0) || !Double.isFinite(voltageScale)) {
            throw new IllegalArgumentException("Reactive limits smoothing voltage scale must be finite and > 0: " + voltageScale);
        }
    }

    public ReactiveLimitsSmoothing(double mu) {
        this(mu, DEFAULT_VOLTAGE_SCALE);
    }
}
