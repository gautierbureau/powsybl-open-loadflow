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

    private boolean keepLoadPowerFactorConstant = false;

    private boolean dcBatchedSolve = false;

    /**
     * Whether a load series moves the load's reactive power along with its active power, keeping the power factor the
     * load was built with. Off by default: a plan series describes active power, and q0 is left where the grid model
     * put it.
     *
     * <p>Not to be confused with {@code OpenLoadFlowParameters.isLoadPowerFactorConstant()}, which is about the
     * reactive power of a load that <b>slack distribution</b> moved. This one is about the plan.
     */
    public boolean isKeepLoadPowerFactorConstant() {
        return keepLoadPowerFactorConstant;
    }

    /**
     * When on, a load series with a value of {@code p} applies {@code q = p * q0 / p0}, both taken as the network was
     * loaded. A planned load whose {@code p0} is zero has no power factor to keep, and is rejected rather than
     * silently left alone.
     */
    public TimeSeriesLoadFlowParameters setKeepLoadPowerFactorConstant(boolean keepLoadPowerFactorConstant) {
        this.keepLoadPowerFactorConstant = keepLoadPowerFactorConstant;
        return this;
    }

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

    /**
     * Whether, in DC, the steps of a partition are solved in one batch: each step's right hand side is assembled and
     * they are solved together on the single shared factorization, instead of one linear solve per step. It streams
     * byte-identical results to the per-step path -- {@code TimeSeriesLoadFlowTest} asserts this by running a plan both
     * ways -- and falls back to the per-step path wherever the batch would not be equivalent: an AC plan, or a DC
     * network with an active outer loop (phase control, area interchange, HVDC AC emulation limits) whose per-step
     * re-solves cannot be pre-batched, or any chunk with a slack distribution failure or a singular system.
     *
     * <p><b>Off by default.</b> Measured against the per-step path it is faster where the linear solve is a real part
     * of the step -- ~1.6-1.7x on IEEE 118 / 300 with DC slack distribution off -- and neutral where it is not: with
     * DC slack distribution on, the per-step distribution dominates the step (roughly ten times the rest) and is run
     * per step in both paths, so batching the solve neither gains nor costs (~1.0x). The gain, when it comes, is the
     * blocked {@code solveTransposed(DenseMatrix)} amortizing the per-call solve overhead a single-column solve pays
     * every step -- worth ~2-5x on the solve alone -- not a change in the linear algebra. It stays off by default while
     * it earns broader coverage (it falls back to the per-step path for a zero-impedance subnetwork, among other
     * cases), but it is a win to turn on for a DC plan whose steps are dominated by the solve rather than by slack
     * distribution.
     */
    public boolean isDcBatchedSolve() {
        return dcBatchedSolve;
    }

    public TimeSeriesLoadFlowParameters setDcBatchedSolve(boolean dcBatchedSolve) {
        this.dcBatchedSolve = dcBatchedSolve;
        return this;
    }
}
