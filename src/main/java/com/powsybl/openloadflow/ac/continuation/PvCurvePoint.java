/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.ac.continuation;

/**
 * One point of a single bus P-V curve, directly plottable (load factor on the x-axis, voltage on the y-axis).
 *
 * @param loadFactor the load increase factor (lambda)
 * @param voltage    the bus voltage magnitude in per unit
 * @param dvDlambda  the voltage sensitivity dV/dlambda at this point ({@code NaN} for the base case); its
 *                   magnitude diverges towards the nose, which makes it a voltage collapse proximity indicator
 * @param stable     whether the point is on the upper (stable) branch
 *
 * @author Claude
 */
public record PvCurvePoint(double loadFactor, double voltage, double dvDlambda, boolean stable) {
}
