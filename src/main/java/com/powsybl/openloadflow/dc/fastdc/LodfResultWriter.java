/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.dc.fastdc;

/**
 * Consumer of the LODF factors streamed by {@link LodfCalculator#computeLodf}. Each factor is reported by the positions
 * of the monitored and outaged branches in the lists passed to the computation, so that the full matrix never has to be
 * materialized: this allows a full N-1 analysis of a very large network, whose matrix would not fit in a single
 * {@link com.powsybl.math.matrix.DenseMatrix}, to be persisted or accumulated on the fly.
 *
 * @author Gautier Bureau {@literal <gautier.bureau at gmail.com>}
 */
@FunctionalInterface
public interface LodfResultWriter {

    /**
     * Called once per (monitored branch, outaged branch) pair, with the corresponding LODF factor. By convention the
     * value is -1 for a branch monitored under its own outage, and {@link Double#NaN} when it is undefined (outage
     * breaking the network connectivity, or monitored branch without a closed DC flow).
     *
     * @param monitoredBranchIndex the index of the monitored branch in the monitored branches list
     * @param outagedBranchIndex the index of the outaged branch in the outaged branches list
     * @param lodf the LODF factor of the monitored branch for the outage of the outaged branch
     */
    void writeLodf(int monitoredBranchIndex, int outagedBranchIndex, double lodf);
}
