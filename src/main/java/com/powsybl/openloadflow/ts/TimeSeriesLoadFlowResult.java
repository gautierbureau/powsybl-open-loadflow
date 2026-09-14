/**
 * Copyright (c) 2025, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.ts;

import java.util.List;
import java.util.Objects;

/**
 * Result of a {@link TimeSeriesLoadFlow} run: the ordered list of per-step summaries (see {@link StepResult}). The bulk
 * results are streamed out through the {@link com.powsybl.loadflow.resultswriter.NetworkResultWriter} and are not held
 * here.
 *
 * @author Gautier Bureau {@literal <gautier.bureau at rte-france.com>}
 */
public class TimeSeriesLoadFlowResult {

    private final List<StepResult> stepResults;

    public TimeSeriesLoadFlowResult(List<StepResult> stepResults) {
        this.stepResults = List.copyOf(Objects.requireNonNull(stepResults));
    }

    /**
     * The per-step summaries, ordered by step index.
     */
    public List<StepResult> getStepResults() {
        return stepResults;
    }
}
