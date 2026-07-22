# Detailed implementation plan — DC sensitivity RHS chunking

## Context

In DC sensitivity analysis, the factor-state right-hand side (RHS) is a **dense**
matrix allocated in `AbstractSensitivityAnalysis.initFactorsRhs`
(`AbstractSensitivityAnalysis.java:784`), called from
`DcSensitivityAnalysis.calculateFactorStates` (`DcSensitivityAnalysis.java:178`):

```java
DenseMatrix rhs = new DenseMatrix(equationCount, factorsGroupCount);   // line 784
...
loadFlowContext.getJacobianMatrix().solveTransposed(factorStates);      // line 179, solved in place
```

- **rows** = `equationCount` (equations in the DC system, roughly the number of buses)
- **columns** = `factorsGroupCount` = number of *distinct sensitivity variables*
  (factor groups, after grouping by `(variableType, variableId)` in
  `createFactorGroups`, `AbstractSensitivityAnalysis.java:732`)

`DenseMatrix` is backed by a **single contiguous `ByteBuffer`** with a hard cap:

```java
public static final int MAX_ELEMENT_COUNT = Integer.MAX_VALUE / Double.BYTES;   // ~268M doubles ≈ 2 GB
// constructor: Math.multiplyExact(rowCount, columnCount, Double.BYTES) → MatrixException on overflow
```

Today, an oversized request throws in the existing guard at
`AbstractSensitivityAnalysis.java:778`:

```java
int maxFactorsGroups = Integer.MAX_VALUE / (equationCount * Double.BYTES);
if (factorsGroupCount > maxFactorsGroups) { throw new PowsyblException("Too many factors groups..."); }
```

So asking for many variables either hard-fails with an exception or hits an OOM
before that. Example: 100k equations caps you at ~2,680 variable groups; 500k
equations caps you at ~536.

### Memory peak (per DC analysis)

Two matrices scale with `factorsGroupCount`, so the peak is **2× the RHS size**:

| Matrix | Size | Line |
|---|---|---|
| `baseFactorStates` | `equationCount × factorsGroupCount` | `DcSensitivityAnalysis.java:603` |
| `workingFactorStates` (full copy, reused per contingency) | `equationCount × factorsGroupCount` | `:605` |
| `baseFlowStates` / `workingFlowStates` | `equationCount × 1` (negligible) | `:598/:600` |
| `contingenciesStates` | `equationCount × #contingencyElements` | `ConnectivityBreakAnalysis.java:318` |
| `actionsStates` | `equationCount × #actionElements` | `:632` |

Only the first two are driven by how many sensitivities are requested.
`contingenciesStates`/`actionsStates` scale with the network/contingency size, not
with factors.

## Goal & scope

Bound the peak RAM of the DC factor-state RHS by processing factor groups (RHS
columns) in **passes/chunks**, with two ways to pick the chunk size:

- **Auto (feasibility):** chunk only when the full matrix would exceed the
  `DenseMatrix` hard limit — replacing today's thrown exception with a working,
  chunked computation.
- **RAM cap (control):** the user sets a max-bytes budget for the RHS working set;
  the chunk size is derived from it to keep peaks well under the hard limit.

**DC only.** `AcSensitivityAnalysis` is left untouched (it shares `initFactorsRhs`,
but its factor states are entangled with the Newton-Raphson state — out of scope).
Default behaviour is bit-for-bit unchanged: when everything fits and no cap is set,
it is a single chunk.

## Core insight (keeps the change small)

`SensitivityFactorGroup.getIndex()` is used **symmetrically**:

- write side — `fillRhs` → `rhs.add/set(column, getIndex(), …)`
  (`AbstractSensitivityAnalysis.java:470/504/525`)
- read side — `p1.calculateSensi(factorStates, factorGroup.getIndex())`
  (`DcSensitivityAnalysis.java:139`)

So if, for each chunk, we **re-number that chunk's groups to local indices
`0..k-1`** before building the RHS, both the write and the read line up with the
smaller matrix automatically. **No signature change is needed** to
`initFactorsRhs`, `fillRhs`, `calculateSensi`, `createBranchSensitivityValue`,
`calculateSensitivityValues`, or the Woodbury engine.

The group index is *only* an RHS column pointer — the sensitivity result output
uses `factor.getIndex()` (a different, per-factor index), so re-indexing groups per
chunk has no effect on result addressing.

### Why column chunking is numerically safe

Everything the RHS columns go through is column-independent:

- `solveTransposed` solves each column against the same (already-decomposed)
  Jacobian LU.
- The Woodbury update `toPostContingencyAndOperatorStrategyStates` iterates
  `for (columnIndex ...)` independently (`WoodburyEngine.java:335`).
- The sensitivity read `calculateSensi(factorStates, factorGroup.getIndex())`
  reads exactly one column per group.

Partitioning the factor groups into chunks of columns therefore yields **identical
results**. The Jacobian LU, `contingenciesStates`/`actionsStates`, and the
connectivity analysis are all independent of the factor chunk and are computed
**once** and reused across chunks. Total solve/Woodbury work on the factor columns
is unchanged (same column count spread across passes).

## Parameters

Add to `OpenSensitivityAnalysisParameters` (`:21`), following the existing
`threadCount` pattern exactly (field, `*_PARAM_NAME`, `*_DEFAULT_VALUE`,
getter/fluent setter, add to `SPECIFIC_PARAMETERS_NAMES`, wire into both
`load(PlatformConfig)` and `load(Map)`, plus `update`/`toMap` if present):

- `long maxSensitivityRhsMemoryMib` — default `0` = auto only (chunk solely to
  respect the hard `DenseMatrix` limit). `> 0` = also cap the RHS factor-state
  working set to this many MiB.

Only one new parameter is needed; "auto chunking" is not a toggle — it is always on
as a correctness fallback (it never triggers unless the 2 GB matrix limit is
exceeded).

## Chunk-size computation

New small helper (in `DcSensitivityAnalysis`, or a static util) computing
columns-per-chunk from `equationCount` and `factorsGroupCount`:

```
maxColsHard = DenseMatrix.MAX_ELEMENT_COUNT / equationCount        // ByteBuffer / int-capacity limit
maxColsRam  = cap == 0 ? MAX : (cap_bytes) / (2L * equationCount * Double.BYTES)   // base + working copy
chunkCols   = clamp(min(factorsGroupCount, maxColsHard, maxColsRam), 1, factorsGroupCount)
nbPasses    = ceil(factorsGroupCount / chunkCols)
```

- `×2` because both `baseFactorStates` and `workingFactorStates` are held
  simultaneously (`DcSensitivityAnalysis.java:603/605`).
- Floor of `1` guarantees progress; a single column overflowing the hard limit
  would require `equationCount > 268M` (unrealistic) — leave the existing overflow
  exception as the backstop for that.
- `contingenciesStates`/`actionsStates` are **not** part of this budget (they do not
  scale with requested sensitivities); document that the cap governs the
  factor-state RHS working set specifically.

Relax the hard throw at `AbstractSensitivityAnalysis.java:779` for the DC path — it
is superseded by chunking. Keep the `initFactorsRhs` overflow guard as a safety net,
but chunk sizing should keep each pass under it.

## Refactor `DcSensitivityAnalysis.analyse`

Split the body around the factor-state work into "once" vs "per chunk".

**Computed once (outside the chunk loop)** — none of these scale with the factor
count:

- Jacobian (already once, in `DcLoadFlowContext`).
- `baseFlowStates` / `workingFlowStates` (`:598/:600`) — 1 column each.
- `ConnectivityBreakAnalysis.run(...)` → `connectivityBreakAnalysisResults` incl.
  `contingenciesStates` (`:625`).
- `actionElementsIndexByLfAction` and `actionsStates` (`:628/:632`).
- `contingenciesWithFactors` filtering (`:613`) and its `writeStateStatus` calls.
- `factorGroups = createFactorGroups(...)` (`:587`) — build the full group list once
  to know `factorsGroupCount`.

**Per chunk (new extracted method, e.g. `processFactorGroupChunk(...)`)**, receiving
the full-analysis context plus the chunk's group sublist:

1. Assign local indices `0..k-1` to the chunk's groups (`setIndex`).
2. Build a `SensitivityFactorGroupList` wrapping just this sublist.
3. Build a **chunk-scoped factor holder** (see next section).
4. `baseFactorStates = calculateFactorStates(ctx, chunkGroupList, participatingElements)`
   → `equationCount × k`.
5. `workingFactorStates = new DenseMatrix(equationCount, k)`.
6. Run exactly today's sequence, but with the chunk's group list + chunk factor
   holder:
   - pre-contingency `calculateSensitivityValues(...)` (`:607-610`)
   - non-breaking + breaking contingencies via
     `operatorStrategiesSensitivityCalculation(...)` (`:638/:646`)
   - operator-strategy blocks (`:653-700`).

Because all the deep methods already take `factorGroups`, `baseFactorStates`,
`workingFactorStates`, and a `validFactorHolder`, the extraction is mostly **moving
lines 587–700 into a method and wrapping it in
`for (chunk : partition(factorGroups, chunkCols))`** — their internals do not change.

Since a group belongs to exactly one chunk, there is no cross-chunk index collision.
After the whole loop, indices can be left as-is (nothing downstream reads them).

## Per-chunk factor filtering (correctness-critical)

Each chunk must write results for **only its factors**, or you get
duplicates/misindexed reads. Build a chunk-scoped `SensitivityFactorHolder` by
replaying `validFactorHolder.getAllFactors()` through a fresh holder's `addFactor`
(which preserves ALL/NONE/SPECIFIC routing, `:1071`), keeping only:

- **`Status.VALID` factors whose `getGroup()` is in this chunk's group set** — these
  need the RHS column.
- **`Status.VALID_ONLY_FOR_FUNCTION` factors — assigned to exactly one chunk
  (chunk 0 only).** These read only `flowStates` (column-independent, `:192`), so they
  must be emitted once. Status is static (set at read time, lines 161/306/377;
  per-contingency connectivity goes through `getPredefinedResults`, not status), so a
  fixed "chunk 0" assignment is safe across all contingencies.
- `Status.ZERO` / `Status.SKIP` factors are written up-front in `writeInvalidFactors`
  (`:873/:879`) — exclude from all chunks.

This localizes the entire filtering concern to the chunk-loop setup; no predicate
threading through `processContingencyAndOperatorStrategy` →
`…ForContingencyAndOperatorStrategy` → `calculateSensitivityValues`.

Edge case: a single-chunk run (default) must reproduce today's behaviour exactly —
the chunk-0 holder then contains every factor, identical to `validFactorHolder`.

## Redundant work under chunking (acceptable, documented)

Per chunk we repeat, for contingencies that need it:

- `calculateFlowStates` DC re-solves for PST-loss / connectivity-break /
  gen-load-PST-action contingencies (`:275/:332`) — 1-column solves.
- `NetworkState.save/restore` per such contingency (`:292/:338`).

These are independent of the factor chunk, so they are recomputed `nbPasses` times.
For the common simple-branch, no-break contingency they cost nothing extra (reuse
`baseFlowStates`). Total solve/Woodbury work on the *factor* columns is unchanged.
Log the pass count so the redundancy is visible.

## Logging & reporting

- At `:567`, extend the existing "Running DC sensitivity analysis with N factors…"
  log with `factorsGroupCount`, `chunkCols`, and `nbPasses`.
- Add a per-pass `LOGGER.info("DC sensitivity pass {}/{} ({} variable groups)", …)`.
- If `maxColsRam < maxColsHard` and forces >1 pass, one info line explaining the RAM
  cap is active. If auto-chunking triggers (would-have-thrown case), an info line
  noting the matrix exceeded the single-allocation limit.

## Tests

- **Equivalence:** pick an existing multi-factor, multi-contingency,
  operator-strategy DC test; run it once normally and once with
  `maxSensitivityRhsMemoryMib` set low enough to force `chunkCols` = 1 and = 2;
  assert **identical** `SensitivityAnalysisResult` values/status. This is the key
  guarantee (column independence ⇒ equality).
- Cover: GLSK/multi-variable groups (ensure a multi-variable group stays intact in
  one chunk), `VALID_ONLY_FOR_FUNCTION` factors (function value written exactly once),
  connectivity-breaking contingencies, and pre-/post-contingency operator strategies.
- **Chunk-size math** unit test: hard-limit and RAM-cap formulas, floor-of-1,
  single-chunk default.
- Parameter round-trip test (config load / `SPECIFIC_PARAMETERS_NAMES`), mirroring
  existing `threadCount` param tests.

## Files touched

| File | Change |
|---|---|
| `OpenSensitivityAnalysisParameters.java` | new `maxSensitivityRhsMemoryMib` param (field, name, default, getter/setter, `SPECIFIC_PARAMETERS_NAMES`, `load` ×2) |
| `DcSensitivityAnalysis.java` | chunk-size helper; extract `processFactorGroupChunk(...)`; wrap `:587–700` in the pass loop; build per-chunk group list + factor holder; local re-indexing; logging |
| `AbstractSensitivityAnalysis.java` | relax the DC-path hard throw at `:779` (keep `initFactorsRhs` overflow guard); possibly expose a small partition/holder-copy helper if reused |
| `DcSensitivityAnalysisTest.java` (+ maybe a new `*ChunkingTest`) | equivalence + edge-case + param tests |
| docs (sensitivity parameters page) | document the new parameter and its semantics |

## Step ordering (safe, incremental)

1. Add the parameter (+ round-trip test) — no behaviour change.
2. Add chunk-size helper + unit test.
3. Extract `processFactorGroupChunk` with a **single** chunk (pure refactor) — full
   test suite must stay green (proves the extraction is behaviour-preserving).
4. Introduce the pass loop + per-chunk factor holder + local re-indexing.
5. Relax the hard throw; add equivalence tests forcing 1- and 2-column chunks.
6. Logging + docs.

## Risks & effort

- **Numerics:** none — columns are provably independent through `solveTransposed`
  and the Woodbury update; equivalence tests enforce it.
- **Main risk:** the per-chunk factor-holder filtering and the "write function-only
  factors once" rule — covered by targeted tests.
- **Effort:** ~1–1.5 days incl. tests; the refactor (steps 3–4) is the bulk,
  everything else is mechanical.
