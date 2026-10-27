# Implementation plan: sparse DC LODF matrix (first version)

Hand-off plan for the agent that implements it. It replaces the approach of
[gautierbureau/powsybl-open-loadflow#6](https://github.com/gautierbureau/powsybl-open-loadflow/pull/6) with a minimal
version: no dense result, no streaming, no threads. **Delete this file in the implementation commit.**

- Base: current `main` (do **not** stack on `pr/vectorized-dc`; vectorized DC gave LODF no speedup in #6's benchmark).
- Size target: about 300 lines of main code (javadoc included), 250 lines of tests, one docs page.
- Every OLF and powsybl-core API named below was checked against the OLF `main` and powsybl-core sources. The code
  sketches were not compiled: treat them as the intended shape, not as final code.

## 1. What to compute

For a monitored branch `l` and the outage of a branch `k`:

```
PTDF(l, k) = flow of l for a +1/-1 injection at the terminals of k
LODF(l, k) = PTDF(l, k) / (1 - PTDF(k, k))        LODF(k, k) = -1
```

`PTDF(., k)` comes from one sparse solve of the DC system per outaged branch (right-hand side +1/-1 on the two terminal
buses), batched, reusing the LU decomposition. This is exactly what `ComputedElement.calculateElementsStates` already
does for the Woodbury fast DC security analysis, and `calculateSensi` on the monitored branch's
`ClosedBranchSide1DcFlowEquationTerm` turns those states into PTDF.

**Only the factors with `|LODF| >= threshold` are stored**, in a compressed sparse column matrix (one column per
outage, filled in order). That is the whole point of this version: memory scales with the outages, not outages x
monitored branches. Measured with a DC model of MATPOWER cases (full N-1, every branch monitored and outaged):

| case | branches | bridges | dense matrix | kept per outage at 5% (mean / max) | sparse at 5% | sparse at 1% |
|---|--:|--:|--:|--:|--:|--:|
| case6515rte | 9 037 | 2 563 | 0.65 GB | 28 / 181 | 2.2 MB | 6.8 MB |
| case9241pegase | 16 049 | 1 665 | 2.06 GB | 32 / 194 | 5.7 MB | 19 MB |
| case13659pegase | 20 467 | 6 083 | 3.35 GB | 32 / 196 | 5.7 MB | 19 MB |

Compute time is unchanged (every pair is still evaluated, about 18 s single-threaded for the full N-1 of
case13659pegase per #6's benchmark). Only memory drops.

## 2. Scope

Do:

| file | change |
|---|---|
| `src/main/java/com/powsybl/openloadflow/dc/fastdc/LodfResult.java` | new: result record |
| `src/main/java/com/powsybl/openloadflow/dc/fastdc/LodfEngine.java` | new: the computation on an `LfNetwork` |
| `src/main/java/com/powsybl/openloadflow/sensi/LodfAnalysis.java` | new: entry point on an iidm `Network` and branch ids |
| `src/main/java/com/powsybl/openloadflow/dc/fastdc/ConnectivityBreakAnalysis.java` | `CONNECTIVITY_LOSS_THRESHOLD`: `private` -> package-private (one word) |
| `src/test/java/com/powsybl/openloadflow/dc/fastdc/LodfEngineTest.java` | new |
| `src/test/java/com/powsybl/openloadflow/sensi/LodfAnalysisTest.java` | new |
| `docs/sensitivity/lodf.md` | new page |
| `docs/sensitivity/index.md` | add `lodf.md` to the toctree, after `parameters.md` |

Keep these names, they follow OLF's conventions:

- `*Engine` for a computation on a DC load flow context, like `WoodburyEngine` in the same package;
- `*Analysis` for the entry point on an iidm network, like `DcSensitivityAnalysis` and `WoodburyDcSecurityAnalysis`;
- `*Result` for the result, like `DcLoadFlowResult`;
- `run` for the entry methods, like `DcLoadFlowEngine.run` and `ConnectivityBreakAnalysis.run`;
- no `Calculator` or `Computer` suffix (OLF has none), no `Dc` prefix (OLF adds it only where an AC counterpart
  exists, and LODF only exists in DC), and `Lodf` rather than `LODF`.

Do **not** add (each was in #6, or is a later step):

- multithreading, a streaming `LodfResultWriter`, a `DenseMatrix` result or its size guard;
- a reusable `Context`, a benchmark class, the pypowsybl patch and notes, vectorized DC parameterized tests;
- GLODF (multi-branch outages) and the PTDF matrix: follow-ups that will reuse this code (section 8);
- a graph-based connectivity check, a configurable tolerance, new `OpenLoadFlowParameters` entries;
- any change to `ComputedElement`, `ComputedContingencyElement`, `WoodburyEngine`.

## 3. Conventions (the contract tests must pin down)

| situation | result |
|---|---|
| `\|LODF\| >= threshold` | stored |
| `\|LODF\| < threshold` | not stored (reads as 0) |
| threshold = 0 | everything stored, exact zeros included (meant for small networks and tests) |
| monitored branch is the outaged branch | -1, stored (for any threshold <= 1) |
| monitored branch not connected on both sides, or not in the LF network (`null`) | empty row: its flow is 0 and stays 0 |
| outaged branch not connected on both sides, or not in the LF network (`null`) | empty column, index listed in `undefinedOutageIndices` |
| outage with `1 - PTDF(k, k) < ConnectivityBreakAnalysis.CONNECTIVITY_LOSS_THRESHOLD` (1e-6) | empty column, index listed in `undefinedOutageIndices` |
| empty monitored or outaged list | `PowsyblException("LODF computation needs at least one monitored branch and one outaged branch")` |
| threshold negative or NaN | `PowsyblException("LODF threshold must be positive or zero: " + threshold)` |
| id unknown to the iidm network (`LodfAnalysis` only) | `PowsyblException("Branch '" + id + "' not found")` |

Why the 1e-6 criterion is enough for a first version (addresses review point 1 of #6 by documenting it, no new code):

- it catches outages breaking the connectivity (bridges, `1 - PTDF(k, k)` is round-off, below 1e-12 in practice);
- it also catches outages of zero impedance branches replaced by a 1e-8 p.u. minimal impedance: nearly all of the +1/-1
  transfer goes through the branch itself, so `1 - PTDF(k, k)` is around 1e-7 and the factor would be noise;
- on case6515rte, case9241pegase and case13659pegase, every real branch has `1 - PTDF(k, k)` either below 1e-12 or
  above 1e-4: nothing sits near the threshold;
- it is the same tolerance `ConnectivityBreakAnalysis` uses for the same purpose, hence reusing its constant.

## 4. Code sketches

Follow these shapes; keep comments as sparse and factual as the surrounding fast DC code.

### 4.1 `LodfResult`

```java
/**
 * Copyright (c) 2026, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.dc.fastdc;

import com.powsybl.math.matrix.SparseMatrix;

import java.util.List;

/**
 * Result of a DC LODF computation, see {@link LodfEngine}.
 *
 * @param lodfMatrix the LODF matrix, with one row per monitored branch and one column per outaged branch, in the order
 *                   of the given lists. Only the factors whose absolute value is greater than or equal to the threshold
 *                   are stored, the other ones read as 0.
 * @param undefinedOutageIndices the indexes of the outaged branches whose LODF are undefined, and whose column is empty:
 *                               branches not connected on both sides, outages breaking the connectivity and outages of
 *                               zero impedance branches
 *
 * @author Gautier Bureau {@literal <gautier.bureau at gmail.com>}
 */
public record LodfResult(SparseMatrix lodfMatrix, List<Integer> undefinedOutageIndices) {
}
```

### 4.2 `LodfEngine`

Instance fields avoid passing seven parameters around (same pattern as `WoodburyEngine`); the only entry points are
static.

```java
package com.powsybl.openloadflow.dc.fastdc;

import com.powsybl.commons.PowsyblException;
import com.powsybl.contingency.BranchContingency;
import com.powsybl.math.matrix.DenseMatrix;
import com.powsybl.math.matrix.SparseMatrix;
import com.powsybl.openloadflow.dc.DcLoadFlowContext;
import com.powsybl.openloadflow.dc.equations.ClosedBranchSide1DcFlowEquationTerm;
import com.powsybl.openloadflow.dc.equations.DcEquationType;
import com.powsybl.openloadflow.dc.equations.DcVariableType;
import com.powsybl.openloadflow.equations.EquationSystem;
import com.powsybl.openloadflow.network.ElementType;
import com.powsybl.openloadflow.network.LfBranch;
import gnu.trove.list.array.TDoubleArrayList;
import gnu.trove.list.array.TIntArrayList;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Computes DC LODF (Line Outage Distribution Factor) matrices, with the building blocks of the Woodbury based fast DC
 * security analysis.
 *
 * <p>The LODF of a monitored branch {@code l} for the outage of a branch {@code k} is the change of the active power flow
 * of {@code l} caused by the outage of {@code k}, divided by the pre-outage active power flow of {@code k}:
 * {@code LODF(l, k) = PTDF(l, k) / (1 - PTDF(k, k))}, where {@code PTDF(l, k)} is the flow of {@code l} for a +1/-1
 * injection at the terminals of {@code k}. The PTDF come from one resolution of the DC system per batch of outages,
 * reusing the LU decomposition of the Jacobian matrix. No load flow is needed: the factors only depend on the topology
 * and the impedances.
 *
 * <p>A full LODF matrix of a large network does not fit in memory, but most of its factors are tiny: only the factors
 * whose absolute value is greater than or equal to a threshold are kept, in a sparse matrix.
 *
 * @author Gautier Bureau {@literal <gautier.bureau at gmail.com>}
 */
public final class LodfEngine {

    /**
     * Number of outages solved together: bounds the dense (equation count x batch size) states matrix, at no cost as the
     * LU decomposition of the Jacobian matrix is reused across batches.
     */
    private static final int OUTAGE_BATCH_SIZE = 500;

    private final DcLoadFlowContext loadFlowContext;

    private final List<LfBranch> monitoredBranches;

    private final List<ClosedBranchSide1DcFlowEquationTerm> monitoredBranchEquations; // null for a branch carrying no flow

    private final double threshold;

    // LODF matrix in compressed sparse column format, filled one outage (column) after the other
    private final int[] columnStart;
    private final TIntArrayList rowIndices = new TIntArrayList();
    private final TDoubleArrayList values = new TDoubleArrayList();
    private final List<Integer> undefinedOutageIndices = new ArrayList<>();

    private LodfEngine(DcLoadFlowContext loadFlowContext, List<LfBranch> monitoredBranches, int outageCount, double threshold) {
        this.loadFlowContext = loadFlowContext;
        this.monitoredBranches = monitoredBranches;
        EquationSystem<DcVariableType, DcEquationType> equationSystem = loadFlowContext.getEquationSystem();
        this.monitoredBranchEquations = monitoredBranches.stream()
                .map(branch -> isClosed(branch)
                        ? equationSystem.getEquationTerm(ElementType.BRANCH, branch.getNum(), ClosedBranchSide1DcFlowEquationTerm.class)
                        : null)
                .toList();
        this.threshold = threshold;
        this.columnStart = new int[outageCount + 1];
    }

    /**
     * Computes the LODF of the given monitored branches for the single outages of the given branches, keeping only the
     * factors whose absolute value is greater than or equal to the threshold.
     *
     * <p>The DC equation system of the context must have been created with phase control off and with zero impedance
     * branches replaced by minimal impedance ones, as done by {@link com.powsybl.openloadflow.sensi.LodfAnalysis}.
     *
     * <p>A {@code null} entry stands for a branch that is not part of the network: like a branch that is not connected
     * on both sides, it gets no factor as a monitored branch (its flow is 0), and its outage is undefined.
     *
     * @param loadFlowContext the DC load flow context, whose Jacobian matrix is factorized only once
     * @param monitoredBranches the branches whose flow change is observed, the rows of the matrix
     * @param outagedBranches the branches whose single outages are simulated, the columns of the matrix
     * @param threshold the factors whose absolute value is lower than this threshold are not kept, 0 keeps all of them
     * @return the sparse LODF matrix and the indexes of the outages whose factors are undefined
     */
    public static LodfResult run(DcLoadFlowContext loadFlowContext, List<LfBranch> monitoredBranches,
                                 List<LfBranch> outagedBranches, double threshold) {
        return run(loadFlowContext, monitoredBranches, outagedBranches, threshold, OUTAGE_BATCH_SIZE);
    }

    /**
     * Same as {@link #run(DcLoadFlowContext, List, List, double)} with an explicit batch size, for tests.
     */
    static LodfResult run(DcLoadFlowContext loadFlowContext, List<LfBranch> monitoredBranches,
                          List<LfBranch> outagedBranches, double threshold, int outageBatchSize) {
        Objects.requireNonNull(loadFlowContext);
        Objects.requireNonNull(monitoredBranches);
        Objects.requireNonNull(outagedBranches);
        if (monitoredBranches.isEmpty() || outagedBranches.isEmpty()) {
            throw new PowsyblException("LODF computation needs at least one monitored branch and one outaged branch");
        }
        if (Double.isNaN(threshold) || threshold < 0) {
            throw new PowsyblException("LODF threshold must be positive or zero: " + threshold);
        }
        LodfEngine engine = new LodfEngine(loadFlowContext, monitoredBranches, outagedBranches.size(), threshold);
        for (int batchStart = 0; batchStart < outagedBranches.size(); batchStart += outageBatchSize) {
            int batchEnd = Math.min(batchStart + outageBatchSize, outagedBranches.size());
            engine.addColumns(outagedBranches.subList(batchStart, batchEnd), batchStart);
        }
        return engine.createResult();
    }

    /**
     * Adds the columns of a batch of outages, from a single resolution of the DC system with one +1/-1 right-hand side
     * per outaged branch.
     */
    private void addColumns(List<LfBranch> outagedBranches, int firstOutageIndex) {
        // null for an outaged branch carrying no flow
        List<ComputedContingencyElement> outageElements = outagedBranches.stream()
                .map(branch -> isClosed(branch)
                        ? new ComputedContingencyElement(new BranchContingency(branch.getId()), loadFlowContext.getNetwork(), loadFlowContext.getEquationSystem())
                        : null)
                .toList();
        List<ComputedContingencyElement> elementsToSolve = outageElements.stream().filter(Objects::nonNull).toList();
        ComputedElement.setComputedElementIndexes(elementsToSolve);
        DenseMatrix states = elementsToSolve.isEmpty() ? DenseMatrix.EMPTY
                : ComputedElement.calculateElementsStates(loadFlowContext, elementsToSolve);
        for (int i = 0; i < outageElements.size(); i++) {
            int outageIndex = firstOutageIndex + i;
            columnStart[outageIndex] = values.size();
            ComputedContingencyElement outageElement = outageElements.get(i);
            if (outageElement == null) {
                undefinedOutageIndices.add(outageIndex);
            } else {
                addColumn(outageIndex, outageElement, states);
            }
        }
    }

    private void addColumn(int outageIndex, ComputedContingencyElement outageElement, DenseMatrix states) {
        int stateColumn = outageElement.getComputedElementIndex();
        double denominator = 1d - outageElement.getLfBranchEquation().calculateSensi(states, stateColumn);
        if (denominator < ConnectivityBreakAnalysis.CONNECTIVITY_LOSS_THRESHOLD) {
            // all the +1/-1 transfer goes through the outaged branch: its outage breaks the connectivity (or it is a zero
            // impedance branch replaced by a minimal impedance one), the factors are undefined
            undefinedOutageIndices.add(outageIndex);
            return;
        }
        for (int row = 0; row < monitoredBranches.size(); row++) {
            ClosedBranchSide1DcFlowEquationTerm monitoredBranchEquation = monitoredBranchEquations.get(row);
            if (monitoredBranchEquation != null) {
                double lodf = monitoredBranches.get(row) == outageElement.getLfBranch() ? -1d
                        : monitoredBranchEquation.calculateSensi(states, stateColumn) / denominator;
                if (Math.abs(lodf) >= threshold) {
                    rowIndices.add(row);
                    values.add(lodf);
                }
            }
        }
    }

    private LodfResult createResult() {
        int outageCount = columnStart.length - 1;
        columnStart[outageCount] = values.size();
        SparseMatrix lodfMatrix = new SparseMatrix(monitoredBranches.size(), outageCount, columnStart, rowIndices.toArray(), values.toArray());
        return new LodfResult(lodfMatrix, undefinedOutageIndices);
    }

    /**
     * Only a branch connected on both sides has a flow equation term (and carries a flow).
     */
    private static boolean isClosed(LfBranch branch) {
        return branch != null && branch.isConnectedAtBothSides();
    }
}
```

### 4.3 `LodfAnalysis`

```java
package com.powsybl.openloadflow.sensi;

import com.powsybl.commons.PowsyblException;
import com.powsybl.commons.report.ReportNode;
import com.powsybl.iidm.network.Network;
import com.powsybl.loadflow.LoadFlowParameters;
import com.powsybl.math.matrix.SparseMatrixFactory;
import com.powsybl.openloadflow.OpenLoadFlowParameters;
import com.powsybl.openloadflow.dc.DcLoadFlowContext;
import com.powsybl.openloadflow.dc.DcLoadFlowParameters;
import com.powsybl.openloadflow.dc.fastdc.LodfEngine;
import com.powsybl.openloadflow.dc.fastdc.LodfResult;
import com.powsybl.openloadflow.graph.EvenShiloachGraphDecrementalConnectivityFactory;
import com.powsybl.openloadflow.network.LfBranch;
import com.powsybl.openloadflow.network.LfNetwork;
import com.powsybl.openloadflow.network.impl.Networks;

import java.util.List;
import java.util.Objects;

/**
 * Network level entry point of the DC LODF computation of {@link LodfEngine}.
 *
 * @author Gautier Bureau {@literal <gautier.bureau at gmail.com>}
 */
public final class LodfAnalysis {

    private LodfAnalysis() {
    }

    /**
     * Computes the DC LODF of the given monitored branches for the single outages of the given branches, on the main
     * synchronous component of the network, keeping only the factors whose absolute value is greater than or equal to
     * the threshold. Phase shifters keep their current tap (phase control off), HVDC lines are fixed injections and zero
     * impedance branches are replaced by minimal impedance ones, as in the Woodbury based fast DC security analysis.
     *
     * <p>A branch outside the main synchronous component, or disconnected on both sides, gets no factor as a monitored
     * branch, and its outage is undefined.
     *
     * @param network the network
     * @param monitoredBranchIds the ids of the branches whose flow change is observed, the rows of the matrix
     * @param outagedBranchIds the ids of the branches whose single outages are simulated, the columns of the matrix
     * @param threshold the factors whose absolute value is lower than this threshold are not kept, 0 keeps all of them
     * @param parameters the load flow parameters, defining the DC model
     * @return the sparse LODF matrix and the indexes of the outages whose factors are undefined
     */
    public static LodfResult run(Network network, List<String> monitoredBranchIds, List<String> outagedBranchIds,
                                 double threshold, LoadFlowParameters parameters) {
        Objects.requireNonNull(network);
        Objects.requireNonNull(monitoredBranchIds);
        Objects.requireNonNull(outagedBranchIds);
        Objects.requireNonNull(parameters);

        OpenLoadFlowParameters parametersExt = OpenLoadFlowParameters.get(parameters);
        DcLoadFlowParameters dcParameters = OpenLoadFlowParameters.createDcParameters(network, parameters, parametersExt,
                new SparseMatrixFactory(), new EvenShiloachGraphDecrementalConnectivityFactory<>(), false);
        dcParameters.getNetworkParameters()
                .setComponentMode(LoadFlowParameters.ComponentMode.MAIN_SYNCHRONOUS)
                .setPhaseControl(false) // LODF are computed at fixed phase shifts
                .setHvdcAcEmulation(false) // HVDC lines are fixed injections
                .setMinImpedance(true); // zero impedance branches have no flow equation term otherwise

        LfNetwork lfNetwork = Networks.load(network, dcParameters.getNetworkParameters(), ReportNode.NO_OP).stream()
                .filter(n -> n.getValidity() == LfNetwork.Validity.VALID)
                .findFirst()
                .orElseThrow(() -> new PowsyblException("Network '" + network.getId() + "' has no valid main synchronous component"));
        List<LfBranch> monitoredBranches = getLfBranches(network, lfNetwork, monitoredBranchIds);
        List<LfBranch> outagedBranches = getLfBranches(network, lfNetwork, outagedBranchIds);
        try (DcLoadFlowContext loadFlowContext = new DcLoadFlowContext(lfNetwork, dcParameters, false)) {
            return LodfEngine.run(loadFlowContext, monitoredBranches, outagedBranches, threshold);
        }
    }

    /**
     * Returns the LF branches of the given ids, with a null entry for a branch that is not part of the LF network.
     */
    private static List<LfBranch> getLfBranches(Network network, LfNetwork lfNetwork, List<String> branchIds) {
        return branchIds.stream()
                .map(branchId -> {
                    LfBranch branch = lfNetwork.getBranchById(branchId);
                    if (branch == null && network.getIdentifiable(branchId) == null) {
                        throw new PowsyblException("Branch '" + branchId + "' not found");
                    }
                    return branch;
                })
                .toList();
    }
}
```

## 5. Pitfalls (each one verified on `main`)

1. `EquationSystem.getEquationTerm(...)` **throws** `PowsyblException("Equation term not found")` when the term is
   missing. It does not return `null`. Call it only for branches connected on both sides. The
   `ComputedContingencyElement` constructor calls it too.
2. Do not fill the result with `SparseMatrix.set()`: trailing empty columns keep `-1` in `getColumnStart()`
   (`fillLastEmptyColumns` is private and only called by `decomposeLU`, `times`, `add`, `transpose`). Build `columnStart`,
   `rowIndices` and `values` yourself and use the public constructor
   `new SparseMatrix(rowCount, columnCount, columnStart, rowIndices, values)`, passing exact-length arrays
   (`TIntArrayList.toArray()`): they map one to one onto `scipy.sparse.csc_matrix((values, rowIndices, columnStart))`.
3. `SparseMatrix` needs at least one row and one column, hence the empty-list check.
4. Do not call `ComputedElement.calculateElementsStates` with an empty element list (a batch where every outaged branch
   is open): use `DenseMatrix.EMPTY`.
5. Write `Lodf` in identifiers, never `LODF`: checkstyle's `AbbreviationAsWordInName` allows 3 capitals in a row.
6. Checkstyle runs in the `validate` phase on main and test sources (`checks` profile of powsybl-parent, on by default):
   imports ordered third-party (alphabetical, `com.*` before `gnu.*`) then `java.*` then static; no unused imports; no
   trailing spaces; no two consecutive blank lines; lines up to 200 characters; lambda bodies up to 20 lines; utility
   classes `final` with a private constructor.
7. Load test networks like the existing fast DC tests:
   `LfNetwork.load(network, new LfNetworkLoaderImpl(), dcParameters.getNetworkParameters()).getFirst()`.
8. In `LodfEngineTest`, use `new DcLoadFlowParameters()` (phase control off, minimal impedance off): do not put zero
   impedance branches in those tests (no flow term). Zero impedance branches are tested through `LodfAnalysis`.

## 6. Tests

Copyright header, package-private classes, JUnit 5, `@author` tag, `private static final double DELTA_LODF = 1e-6;`.
Port the post-outage flow helpers (`calculateFlows`, `checkLodfMatrixAgainstPostOutageFlows`) from #6's
`LodfCalculatorTest`. They are on branch `claude/lodf-factors-dc-sa-i1ttsi`. Adapt them to read
`result.lodfMatrix().toDense()`, and drop the vectorized parameterization.

`LodfEngineTest` (package `com.powsybl.openloadflow.dc.fastdc`):

| test | network | checks |
|---|---|---|
| `testAgainstPostOutageFlows` | `FourBusNetworkFactory.create()`, branches `l14 l12 l23 l34 l13`, threshold 0 | every factor equals `(postFlow - preFlow) / preFlow(k)` from DC load flows with `k` disconnected, diagonal -1, no undefined outage |
| `testWithPhaseShift` | `PhaseControlFactory.createWithOneT2wtTwoLines()` with `PS1` tap position 2, branches `L1 L2 PS1`, threshold 0 | same check: exact with a non-zero phase shift |
| `testThreshold` | four-bus, threshold 0 as reference, then a threshold that drops some factors and keeps others | stored factors are exactly the reference ones with `\|value\| >= threshold`, and none is missing; assert the stored count is strictly between 0 and the reference count |
| `testOutageBatchingGivesSameResult` | four-bus, batch sizes 1, 2, 3 vs default | `toDense()` identical with delta 0, same `getValueCount()`, same `undefinedOutageIndices` |
| `testParallelLinesAndConnectivityBreak` | `EurostagFactory.fix(EurostagTutorialExample1Factory.create())`, branches `NHV1_NHV2_1 NHV1_NHV2_2 NGEN_NHV1 NHV2_NLOAD`, threshold 0 | the two parallel lines get factor 1 for each other's outage; transformers get 0 for line outages; transformer outages (indexes 2 and 3) are undefined and their columns are empty |
| `testOpenBranch` | four-bus with `l23` terminal 1 disconnected | `l23` monitored: empty row; `l23` outaged: undefined |
| `testInvalidInputs` | four-bus | empty monitored list, empty outaged list, threshold -1 and NaN: `PowsyblException` with the messages of section 3 |

`LodfAnalysisTest` (package `com.powsybl.openloadflow.sensi`), `new LoadFlowParameters()`:

| test | network | checks |
|---|---|---|
| `testLodf` | Eurostag fixed, same four branches, threshold 0.5 | parallel lines: factor 1; transformer outages: undefined; no stored value below 0.5 in absolute value |
| `testZeroImpedanceBranch` | four-bus with `l13` R = X = 0, monitored `l13 l12`, outaged `l12 l13`, threshold 0 | `l13` monitored under the `l12` outage has a stored factor (minimal impedance); the `l13` outage is undefined |
| `testDisconnectedBranch` | four-bus with both terminals of `l23` disconnected | no exception; `l23` monitored: empty row; `l23` outaged: undefined |
| `testUnknownBranch` | four-bus | `PowsyblException("Branch 'unknown' not found")` |
| `testPhaseShifterRegulationOn` (optional) | PST network, `setPhaseShifterRegulationOn(true)` | same matrix as with regulation off |

## 7. Docs

`docs/sensitivity/lodf.md`, MyST with `$$` math (the `dollarmath` extension is on). Sections:

1. **LODF matrix (DC)**: one paragraph, link to `../security/parameters.md#dcfastmode`.
2. **Definition**: the two formulas of section 1; no load flow is needed.
3. **Sparse result**: why (dense size), the threshold rule, the table of section 1, threshold 0 keeps everything.
4. **Conventions**: the table of section 3 without the exception rows.
5. **Usage**:
   ```java
   LodfResult result = LodfAnalysis.run(network, monitoredBranchIds, outagedBranchIds, 0.05, new LoadFlowParameters());
   SparseMatrix lodfMatrix = result.lodfMatrix(); // rows: monitored branches, columns: outaged branches
   List<Integer> undefinedOutageIndices = result.undefinedOutageIndices();
   ```
6. **Modeling**: main synchronous component, phase control off, HVDC lines as fixed injections, minimal impedance for
   zero impedance branches.

## 8. Not in this version (follow-ups, in this order)

1. **GLODF** (several branches outaged together): one column per (contingency, branch),
   `GLODF(l, K) = PTDF(l, K) (I - PTDF(K, K))^-1`, the same small system `WoodburyEngine` solves. Compute it directly,
   not from the thresholded N-1 matrix: the factors between outaged branches may have been dropped.
2. **PTDF matrix**: same batched solve and sparse column fill, with a unit injection per bus. Decide first: columns per
   bus or per injection, and single or distributed slack. It is less sparse: 263 values per bus at 5% with a single slack
   on case13659pegase, 156 with distributed slack.
3. Graph confirmation of connectivity breaks (as in `ConnectivityBreakAnalysis`), only if a real case needs it.
4. Speed: read each states column once instead of `calculateSensi` per cell, or threads as an opt-in.
5. pypowsybl binding returning `scipy.sparse.csc_matrix` plus the undefined outages.

## 9. Verification and delivery

```bash
./mvnw --batch-mode -Dtest='LodfEngineTest,LodfAnalysisTest' test   # checkstyle runs too (validate phase)
./mvnw --batch-mode install                                            # full build before pushing
```

- The first build downloads every dependency: allow several minutes.
- Upstream OLF commits are signed off (`git commit -s`).
- One commit is enough: "Add sparse DC LODF matrix computation". It also deletes this file.
- PR description:
  - what it computes, and the table of section 1;
  - how #6's review points are handled:
    1. tolerance: documented, see section 3;
    2. streaming writer and threads: removed;
    3. batch sizing division by zero: fixed batch size;
    4. phase-control precondition: in the javadoc, and enforced by `LodfAnalysis`;
    5. nits: the pypowsybl files are not included.
  - that it supersedes gautierbureau/powsybl-open-loadflow#6.
