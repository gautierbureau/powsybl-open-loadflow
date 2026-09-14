/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.dc.fastdc;

import com.powsybl.commons.PowsyblException;
import com.powsybl.contingency.BranchContingency;
import com.powsybl.math.matrix.DenseMatrix;
import com.powsybl.openloadflow.dc.DcLoadFlowContext;
import com.powsybl.openloadflow.dc.equations.ClosedBranchSide1DcFlowEquationTerm;
import com.powsybl.openloadflow.dc.equations.DcEquationSystemCreationParameters;
import com.powsybl.openloadflow.dc.equations.DcEquationType;
import com.powsybl.openloadflow.dc.equations.DcVariableType;
import com.powsybl.openloadflow.equations.EquationSystem;
import com.powsybl.openloadflow.network.LfBranch;
import com.powsybl.openloadflow.network.LfNetwork;
import com.powsybl.openloadflow.network.LoadFlowModel;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ForkJoinPool;
import java.util.stream.IntStream;

/**
 * Fast computation of LODF (Line Outage Distribution Factor) matrices, based on the same building blocks as the
 * Woodbury based fast DC security analysis.
 *
 * <p>The LODF of a monitored branch {@code l} for the outage of a branch {@code k} is the ratio between the active power
 * flow change on {@code l} caused by the outage of {@code k} and the pre-outage flow of {@code k}. It is computed as
 * {@code LODF(l, k) = PTDF(l, k) / (1 - PTDF(k, k))}, where {@code PTDF(., k)} is the sensitivity of branch flows to a
 * +1/-1 active power injection at the terminals of {@code k}. The needed sensitivities are obtained from batched
 * multiple right-hand side resolutions of the DC linear system (a single resolution when all the outages fit in one
 * batch), so the computation cost is a sparse solve plus dense arithmetic, whatever the number of monitored branches
 * and outages. The Jacobian matrix is factorized only once and reused across batches. This formula remains exact for
 * branches with a non-zero phase shift, as the phase shift contribution cancels out in the ratio.
 *
 * <p>Note that LODF factors only depend on the network topology and impedances: no load flow needs to be run beforehand.
 *
 * <p>{@link #computeLodfMatrix} returns the result as a single {@link DenseMatrix}, so the number of monitored branches
 * times the number of outaged branches must not exceed {@link DenseMatrix#MAX_ELEMENT_COUNT}. For a full N-1 analysis of
 * a very large network (where all branches are both monitored and outaged), the matrix would not fit: use
 * {@link #computeLodf} instead, which streams the factors to a {@link LodfResultWriter} without materializing the matrix.
 *
 * @author Gautier Bureau {@literal <gautier.bureau at gmail.com>}
 */
public final class LodfCalculator {

    private static final double CONNECTIVITY_LOSS_THRESHOLD = 1e-6;

    /**
     * Soft cap on the memory footprint of the transient +1/-1 injection states matrix of a single batch of outages.
     * Keeping it modest bounds the peak memory when computing LODF for a very large number of outages, at the negligible
     * cost of a few extra right-hand side resolutions (the Jacobian factorization is reused across batches).
     */
    private static final int MAX_INJECTION_STATES_BYTES_PER_BATCH = 256 * 1024 * 1024;

    private LodfCalculator() {
    }

    /**
     * Computes the LODF matrix of the given monitored branches for single outages of the given branches.
     *
     * <p>The returned matrix is indexed by the positions in the given lists: row {@code l} corresponds to the
     * {@code l}-th monitored branch and column {@code k} to the outage of the {@code k}-th outaged branch. By
     * convention, the LODF of a branch for its own outage is -1 (it loses all of its flow). The LODF factors of an
     * outage breaking the network connectivity are undefined, and the corresponding column is filled with {@link Double#NaN}.
     * Monitored branches without a DC flow equation (open on at least one side, or of zero impedance) also get {@link Double#NaN}.
     *
     * @param loadFlowContext the DC load flow context, whose Jacobian matrix is reused (and factorized only once)
     * @param monitoredBranches the branches on which the flow change is observed
     * @param outagedBranches the branches whose outages are simulated, which must be connected on both sides and of non-zero impedance
     * @return the LODF matrix, with one row per monitored branch and one column per outaged branch
     */
    public static DenseMatrix computeLodfMatrix(DcLoadFlowContext loadFlowContext, List<LfBranch> monitoredBranches, List<LfBranch> outagedBranches) {
        return computeLodfMatrix(loadFlowContext, monitoredBranches, outagedBranches, 1);
    }

    /**
     * Same as {@link #computeLodfMatrix(DcLoadFlowContext, List, List)}, computing the matrix with the given number of
     * threads. The result is bit-for-bit identical whatever the thread count.
     *
     * @param threadCount the number of threads used to fill the matrix (1 for a sequential computation)
     */
    public static DenseMatrix computeLodfMatrix(DcLoadFlowContext loadFlowContext, List<LfBranch> monitoredBranches,
                                                List<LfBranch> outagedBranches, int threadCount) {
        Objects.requireNonNull(monitoredBranches);
        Objects.requireNonNull(outagedBranches);
        // the result is a single DenseMatrix: check its size up front to fail with an explicit message
        long resultSize = (long) monitoredBranches.size() * outagedBranches.size();
        if (resultSize > DenseMatrix.MAX_ELEMENT_COUNT) {
            throw new PowsyblException("LODF matrix is too large (" + monitoredBranches.size() + " monitored branches x "
                    + outagedBranches.size() + " outaged branches = " + resultSize + " elements, maximum is "
                    + DenseMatrix.MAX_ELEMENT_COUNT + "): use computeLodf with a streaming LodfResultWriter, or split the "
                    + "monitored or the outaged branches into groups");
        }
        DenseMatrix lodfMatrix = new DenseMatrix(monitoredBranches.size(), outagedBranches.size());
        // DenseMatrix.set on distinct (row, column) cells is thread-safe, so the parallel fill can write it directly
        computeLodf(loadFlowContext, monitoredBranches, outagedBranches, lodfMatrix::set, threadCount);
        return lodfMatrix;
    }

    /**
     * Streams the LODF factors of the given monitored branches for single outages of the given branches to the given
     * writer, one factor per (monitored branch, outaged branch) pair, without ever materializing the full matrix. This
     * is the way to run a full N-1 analysis of a very large network, whose matrix would exceed
     * {@link DenseMatrix#MAX_ELEMENT_COUNT}. See {@link #computeLodfMatrix} for the value conventions.
     *
     * @param loadFlowContext the DC load flow context, whose Jacobian matrix is reused (and factorized only once)
     * @param monitoredBranches the branches on which the flow change is observed
     * @param outagedBranches the branches whose outages are simulated, which must be connected on both sides and of non-zero impedance
     * @param writer the consumer of the streamed LODF factors
     */
    public static void computeLodf(DcLoadFlowContext loadFlowContext, List<LfBranch> monitoredBranches,
                                   List<LfBranch> outagedBranches, LodfResultWriter writer) {
        computeLodf(loadFlowContext, monitoredBranches, outagedBranches, writer, 1);
    }

    /**
     * Same as {@link #computeLodf(DcLoadFlowContext, List, List, LodfResultWriter)}, streaming the factors with the
     * given number of threads. The injection states are still solved sequentially (they share the Jacobian
     * factorization), but the factors of each batch are computed in parallel. With more than one thread the writer may
     * be called concurrently for different outaged branches, so it must be thread-safe (this holds for the
     * {@link DenseMatrix} filled by {@link #computeLodfMatrix}).
     *
     * @param threadCount the number of threads used to compute the factors (1 for a sequential computation)
     */
    public static void computeLodf(DcLoadFlowContext loadFlowContext, List<LfBranch> monitoredBranches,
                                   List<LfBranch> outagedBranches, LodfResultWriter writer, int threadCount) {
        Objects.requireNonNull(loadFlowContext);
        computeLodf(loadFlowContext, monitoredBranches, outagedBranches, writer,
                computeOutageBatchSize(loadFlowContext.getEquationSystem()), threadCount);
    }

    /**
     * Core implementation, with an explicit number of outages processed per batch. Package-private, mainly to let tests
     * exercise the batching with small batch sizes.
     */
    static void computeLodf(DcLoadFlowContext loadFlowContext, List<LfBranch> monitoredBranches,
                            List<LfBranch> outagedBranches, LodfResultWriter writer, int outageBatchSize, int threadCount) {
        Objects.requireNonNull(loadFlowContext);
        Objects.requireNonNull(monitoredBranches);
        Objects.requireNonNull(outagedBranches);
        Objects.requireNonNull(writer);
        if (threadCount < 1) {
            throw new PowsyblException("Thread count must be at least 1, was " + threadCount);
        }
        EquationSystem<DcVariableType, DcEquationType> equationSystem = loadFlowContext.getEquationSystem();
        DcEquationSystemCreationParameters creationParameters = loadFlowContext.getParameters().getEquationSystemCreationParameters();

        // validate all outaged branches up front, so we fail before doing any computation
        outagedBranches.forEach(LodfCalculator::checkOutagedBranch);

        // flow equation terms of the monitored branches, built once and shared (read-only) across batches; work with
        // both the scalar and the vectorized DC equation systems; null for branches without a closed DC flow term
        List<ClosedBranchSide1DcFlowEquationTerm> monitoredBranchEquations = monitoredBranches.stream()
                .map(branch -> ComputedElement.createBranchEquation(branch, equationSystem, creationParameters))
                .toList();

        // pool used to fill each batch in parallel; the injection states solves stay sequential (shared factorization)
        ForkJoinPool pool = threadCount > 1 ? new ForkJoinPool(threadCount) : null;
        try {
            // The injection states of all outages cannot always be solved in a single pass: the +1/-1 right-hand side is a
            // dense (equationCount x outages) matrix, whose size in bytes must fit in an int (see ComputedElement.initRhs)
            // and whose memory footprint grows with the number of outages. We therefore process the outages in batches,
            // reusing the Jacobian factorization (cached in the context) so that only the cheap per-batch resolution is redone.
            for (int batchStart = 0; batchStart < outagedBranches.size(); batchStart += outageBatchSize) {
                int batchEnd = Math.min(batchStart + outageBatchSize, outagedBranches.size());
                streamLodfColumns(loadFlowContext, monitoredBranches, monitoredBranchEquations,
                        outagedBranches.subList(batchStart, batchEnd), batchStart, writer, pool);
            }
        } finally {
            if (pool != null) {
                pool.shutdown();
            }
        }
    }

    /**
     * Streams the LODF factors of the columns {@code [batchStart, batchStart + batch.size())} for the given batch of
     * outaged branches, from a single multiple right-hand side resolution of the DC linear system. When a pool is
     * given, the columns of the batch are filled in parallel (each column writes a disjoint set of outaged indices).
     */
    private static void streamLodfColumns(DcLoadFlowContext loadFlowContext, List<LfBranch> monitoredBranches,
                                          List<ClosedBranchSide1DcFlowEquationTerm> monitoredBranchEquations,
                                          List<LfBranch> outagedBranchesBatch, int batchStart, LodfResultWriter writer, ForkJoinPool pool) {
        LfNetwork lfNetwork = loadFlowContext.getNetwork();
        EquationSystem<DcVariableType, DcEquationType> equationSystem = loadFlowContext.getEquationSystem();
        DcEquationSystemCreationParameters creationParameters = loadFlowContext.getParameters().getEquationSystemCreationParameters();

        List<ComputedContingencyElement> outageElements = outagedBranchesBatch.stream()
                .map(branch -> new ComputedContingencyElement(new BranchContingency(branch.getId()), lfNetwork, equationSystem, creationParameters))
                .toList();
        ComputedElement.setComputedElementIndexes(outageElements);

        // single multiple right-hand side sparse resolution for this batch, giving the angle response
        // to a +1/-1 active power injection at the terminals of each outaged branch
        DenseMatrix injectionStates = ComputedElement.calculateElementsStates(loadFlowContext, outageElements);

        if (pool == null) {
            for (int j = 0; j < outageElements.size(); j++) {
                streamLodfColumn(j, outageElements, monitoredBranches, monitoredBranchEquations, injectionStates, batchStart, writer);
            }
        } else {
            // the fill of the batch columns is embarrassingly parallel: each column reads the shared read-only injection
            // states and writes a disjoint set of outaged indices, so the result is deterministic
            try {
                pool.submit(() -> IntStream.range(0, outageElements.size()).parallel().forEach(j ->
                        streamLodfColumn(j, outageElements, monitoredBranches, monitoredBranchEquations, injectionStates, batchStart, writer))).get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new PowsyblException("LODF computation was interrupted", e);
            } catch (ExecutionException e) {
                throw new PowsyblException("LODF computation failed", e.getCause());
            }
        }
    }

    /**
     * Streams the LODF factors of a single outaged branch (the {@code j}-th of the current batch) for all monitored branches.
     */
    private static void streamLodfColumn(int j, List<ComputedContingencyElement> outageElements, List<LfBranch> monitoredBranches,
                                         List<ClosedBranchSide1DcFlowEquationTerm> monitoredBranchEquations, DenseMatrix injectionStates,
                                         int batchStart, LodfResultWriter writer) {
        ComputedContingencyElement outageElement = outageElements.get(j);
        double selfPtdf = outageElement.getLfBranchEquation().calculateSensi(injectionStates, outageElement.getComputedElementIndex());
        // a self-PTDF of 1 means that all the flow of the outaged branch goes through itself,
        // i.e. that its outage breaks the network connectivity: LODF factors are undefined
        boolean breaksConnectivity = Math.abs(selfPtdf) > 1d - CONNECTIVITY_LOSS_THRESHOLD;
        int outagedIndex = batchStart + j;
        for (int row = 0; row < monitoredBranches.size(); row++) {
            writer.writeLodf(row, outagedIndex, computeLodfValue(injectionStates, outageElement, monitoredBranches.get(row),
                    monitoredBranchEquations.get(row), selfPtdf, breaksConnectivity));
        }
    }

    private static double computeLodfValue(DenseMatrix injectionStates, ComputedContingencyElement outageElement,
                                           LfBranch monitoredBranch, ClosedBranchSide1DcFlowEquationTerm monitoredBranchEquation,
                                           double selfPtdf, boolean breaksConnectivity) {
        if (breaksConnectivity || monitoredBranchEquation == null) {
            return Double.NaN;
        }
        if (monitoredBranch == outageElement.getLfBranch()) {
            return -1d;
        }
        double ptdf = monitoredBranchEquation.calculateSensi(injectionStates, outageElement.getComputedElementIndex());
        return ptdf / (1d - selfPtdf);
    }

    /**
     * Returns the number of outages whose injection states can be solved together in one batch, respecting both the
     * hard constraint that the {@code (equationCount x outages)} matrix size in bytes fits in an int, and a soft memory
     * cap on the transient injection states matrix.
     */
    private static int computeOutageBatchSize(EquationSystem<DcVariableType, DcEquationType> equationSystem) {
        int equationCount = equationSystem.getIndex().getColumnCount();
        int bytesPerColumn = equationCount * Double.BYTES;
        // hard limit: the (equationCount x columns) matrix size in bytes must fit in an int (same guard as ComputedElement.initRhs)
        int maxColumns = Integer.MAX_VALUE / bytesPerColumn;
        // soft cap: keep the transient injection states matrix of a batch below MAX_INJECTION_STATES_BYTES_PER_BATCH
        int memoryCap = MAX_INJECTION_STATES_BYTES_PER_BATCH / bytesPerColumn;
        return Math.max(1, Math.min(maxColumns, memoryCap));
    }

    private static void checkOutagedBranch(LfBranch branch) {
        if (branch.getBus1() == null || branch.getBus2() == null) {
            throw new PowsyblException("Branch '" + branch.getId() + "' must be connected on both sides to compute LODF factors for its outage");
        }
        if (branch.isZeroImpedance(LoadFlowModel.DC)) {
            throw new PowsyblException("LODF factors cannot be computed for the outage of zero impedance branch '" + branch.getId() + "'");
        }
    }
}
