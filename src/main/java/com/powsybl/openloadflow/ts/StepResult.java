/**
 * Copyright (c) 2025, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.ts;

import com.powsybl.loadflow.LoadFlowResult;

import java.time.Instant;

/**
 * Compact, in-memory summary of one time-series load flow step. The bulk per-element results (branch flows, bus
 * voltages, generator dispatch) are streamed to disk; only this small summary per step is kept in memory, so the peak
 * memory of a run is independent of the network size.
 *
 * @param stepIndex                    the 0-based index of the step in the time-series
 * @param timestamp                    the instant of the step (from the time-series index)
 * @param status                       the (worst across connected components) computation status of the step
 * @param slackBusActivePowerMismatch  the slack bus active power mismatch (MW), summed over connected components
 * @param distributedActivePower       the active power distributed by slack distribution (MW), summed over components
 *
 * @author (design proposal)
 */
public record StepResult(int stepIndex,
                         Instant timestamp,
                         LoadFlowResult.ComponentResult.Status status,
                         double slackBusActivePowerMismatch,
                         double distributedActivePower) {
}
