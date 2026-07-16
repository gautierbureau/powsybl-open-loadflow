/**
 * Copyright (c) 2025, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.ts;

import com.powsybl.loadflow.LoadFlowParameters;

import java.util.Objects;

/**
 * Parameters of a time-series load flow: the underlying {@link LoadFlowParameters} (AC/DC, slack distribution, ...),
 * the number of threads to spread the steps over, and which result datasets to stream out.
 *
 * <p>The network structure is fixed across all steps; only injection active-power targets change from one step to the
 * next (see {@link TimeSeriesLoadFlow}). Bulk per-step results are streamed to a
 * {@link com.powsybl.loadflow.resultswriter.NetworkResultWriter}; only a compact per-step summary is returned in memory
 * (see {@link TimeSeriesLoadFlowResult}).
 *
 * @author Gautier Bureau {@literal <gautier.bureau at rte-france.com>}
 */
public class TimeSeriesLoadFlowParameters {

    public static final int DEFAULT_THREAD_COUNT = 1;

    private LoadFlowParameters loadFlowParameters = new LoadFlowParameters();

    private int threadCount = DEFAULT_THREAD_COUNT;

    private boolean streamBranchResults = true;

    private boolean streamBusResults = true;

    private boolean streamGeneratorResults = true;

    public LoadFlowParameters getLoadFlowParameters() {
        return loadFlowParameters;
    }

    public TimeSeriesLoadFlowParameters setLoadFlowParameters(LoadFlowParameters loadFlowParameters) {
        this.loadFlowParameters = Objects.requireNonNull(loadFlowParameters);
        return this;
    }

    public int getThreadCount() {
        return threadCount;
    }

    public TimeSeriesLoadFlowParameters setThreadCount(int threadCount) {
        if (threadCount < 1) {
            throw new IllegalArgumentException("threadCount must be >= 1");
        }
        this.threadCount = threadCount;
        return this;
    }

    public boolean isStreamBranchResults() {
        return streamBranchResults;
    }

    public TimeSeriesLoadFlowParameters setStreamBranchResults(boolean streamBranchResults) {
        this.streamBranchResults = streamBranchResults;
        return this;
    }

    public boolean isStreamBusResults() {
        return streamBusResults;
    }

    public TimeSeriesLoadFlowParameters setStreamBusResults(boolean streamBusResults) {
        this.streamBusResults = streamBusResults;
        return this;
    }

    public boolean isStreamGeneratorResults() {
        return streamGeneratorResults;
    }

    public TimeSeriesLoadFlowParameters setStreamGeneratorResults(boolean streamGeneratorResults) {
        this.streamGeneratorResults = streamGeneratorResults;
        return this;
    }
}
