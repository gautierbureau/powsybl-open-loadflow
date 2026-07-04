# Design note: flyweight (Structure-of-Arrays) sensitivity factor model

**Status:** proposal · **Scope:** `com.powsybl.openloadflow.sensi` (AC + DC)

> Internal design/performance note (not part of the published documentation). It records the analysis
> behind the sensitivity-analysis performance work and a proposal for the remaining, larger
> optimization. The companion `powsybl-core-lazy-sensitivity-result-index.patch` in this folder is the
> result-egress fix that must be upstreamed to powsybl-core.

## 1. Motivation (measured)

On PEGASE `case13659pegase` (13 659 buses) with **2 000 000** sensitivity factors, base case, the
sensitivity-analysis phase splits (min-of-runs, `-Xmx13g`) as:

| Phase | Time | Note |
|---|---|---|
| Result egress (core) | ~2.7 s | fixed by a powsybl-core lazy-index patch (see companion `.patch`) |
| Base AC load flow | ~1.5 s | inherent |
| **Factor ingestion (`readAndCheckFactors`)** | **~0.65 s CPU + 0–1.4 s GC** | this note |
| Value computation | ~0.5 s | |
| RHS + transposed solve + refs | ~0.25 s | solve alone ~75 ms |

Isolating CPU from GC with EpsilonGC (no collections) + warmed JIT showed `readAndCheckFactors`
is **~650 ms of stable CPU work**, but under a normal collector the same phase swings
**623 → 2083 ms**. That variance is **GC pause time driven by allocation volume**: ingestion
allocates on the order of **one `LfSensitivityFactor` object per factor** — ~2M objects,
~130–250 MB of young-gen churn per analysis, on top of the 2M input `SensitivityFactor` objects.

The only way to remove the GC variance is to **stop allocating one object per factor**. This note
proposes replacing the per-factor object graph with a Structure-of-Arrays (SoA) store fronted by a
reusable flyweight cursor, so the existing pipeline keeps iterating the same `LfSensitivityFactor`
interface while (almost) no per-factor objects are created.

## 2. Current model (what we must preserve)

`AbstractSensitivityAnalysis.java`:

- Interface `LfSensitivityFactor<V, E>` — the contract every pipeline stage consumes.
- `AbstractLfSensitivityFactor<V, E>` (~72 bytes/instance, single-variable) with fields:
  `index`, `variableId` (String), `functionId` (String), `functionElement` (LfElement),
  `functionType` (enum ref), `variableType` (enum ref), `contingencyContext`,
  `sensitivityValuePredefinedResult` (Double, boxed, nullable),
  `functionPredefinedResult` (Double, boxed, nullable), `functionReference` (double),
  `status` (enum ref), `group` (back-reference to its `SensitivityFactorGroup`).
- `SingleVariableLfSensitivityFactor` adds `variableElement` (LfElement) — **~99% of factors**.
- `MultiVariablesLfSensitivityFactor` adds `weightedVariableElements` (`Map<LfElement,Double>`)
  and `originalVariableSetIds` (`Set<String>`) — GLSK / HVDC, **rare, structurally heavy**.

Lifecycle and every consumer that constrains the design:

1. **Read** — `readAndCheck(...)` builds one factor per input, buckets it in
   `SensitivityFactorHolder` (`commonFactors` / `additionalFactorsNoContingency` /
   `additionalFactorsPerContingency`).
2. **Validate** — `writeInvalidFactors(...)` iterates all factors, reads `status`, may write ZERO/SKIP
   output, returns a holder of the still-valid ones.
3. **Group** — `createFactorGroups(...)` keys factors by `(variableType, variableId)` into
   `SingleVariableFactorGroup` / `MultiVariablesFactorGroup`, calls `factor.setGroup(group)` and
   `group.addFactor(factor)`. Each group later exposes `getFactors()` and an `index` (RHS column).
4. **RHS / solve** — group-level, index-based; does **not** touch individual factors.
5. **Function references** — `setFunctionReferences(...)` writes `functionReference` per factor via
   `factor.getFunctionEquationTerm().eval()`.
6. **Predefined results** (post-contingency) — `setPredefinedResults(...)` reads `status` and the
   connectivity/contingency predicates, and writes the two predefined `Double` fields; reset to
   `null` each contingency (`AcSensitivityAnalysis.computeLfContingency`).
7. **Value extraction** — `calculateSensitivityValues(...)` reads `status`,
   `getFunctionEquationTerm().calculateSensi(states, group.getIndex())`, `functionReference`, the two
   predefined fields, and writes the value out.

Key derived accessor: `getFunctionEquationTerm()` is computed **on demand** from
`(functionType, functionElement)` — it is *not* stored, so it costs nothing to keep computing it.

Two properties the design must honour:

- **Factors are used as long-lived references** in exactly two places: `group.getFactors()` (a list)
  and `factor.getGroup()` (a back-reference). Everywhere else a factor is touched transiently inside
  a loop.
- **Mutable per-factor state:** `functionReference`, `status`, `group`, and the two predefined
  results. Everything else is write-once at read time.

## 3. Goals & non-goals

**Goals**
- Cut ingestion allocation from ~2M objects to O(number of columns/arrays), removing the GC-driven
  variance (the 0–1.4 s tail).
- Preserve exact numerical results and all status/predefined-result semantics, AC and DC.
- Keep the change **localized to `com.powsybl.openloadflow.sensi`**; no powsybl-core API change.

**Non-goals**
- Changing the powsybl-core `SensitivityFactor` input API or the reader/writer contracts.
- Optimizing the multi-variable (GLSK/HVDC) path — it stays object-based (rare, complex, low volume).
- Changing grouping math, RHS assembly, or the solve.

## 4. Proposed design

### 4.1 Structure-of-Arrays store

Introduce `LfSensitivityFactorStore<V, E>` holding **column arrays** indexed by a dense factor row id
(`0..n-1`, which also becomes the factor `index`). Only the **single-variable** factors live here;
multi-variable factors stay as objects in a sparse side map (see 4.4).

```java
final class LfSensitivityFactorStore<V extends Enum<V> & Quantity, E extends Enum<E> & Quantity> {
    // write-once at read time
    int[]      functionElementRef;   // index into elements[] (or -1)
    int[]      variableElementRef;   // index into elements[] (or -1)
    int[]      variableIdRef;        // index into ids[]  (interned)
    int[]      functionIdRef;        // index into ids[]  (interned)
    byte[]     functionType;         // SensitivityFunctionType.ordinal()
    byte[]     variableType;         // SensitivityVariableType.ordinal()
    byte[]     contingencyContextRef;// small interned table (ALL / NONE / SPECIFIC+id)

    // mutable during analysis
    byte[]     status;               // LfSensitivityFactor.Status.ordinal()
    int[]      groupIndex;           // RHS column, -1 until grouped
    double[]   functionReference;    // 0 until set
    double[]   sensitivityPredefined; // value; presence tracked by the bitset below
    double[]   functionPredefined;
    boolean[]  sensitivityPredefinedSet;
    boolean[]  functionPredefinedSet;

    // dedup side tables (small)
    LfElement[] elements;            // deduplicated LfElement pool
    String[]    ids;                 // deduplicated id pool
    ContingencyContext[] contingencyContexts;
}
```

Memory: for 2M factors the hot arrays are ~4 `int[]` + 3 `byte[]` + 3 `double[]` + 2 `boolean[]`
≈ **~90 MB of flat, contiguous, GC-friendly arrays** with **~a few dozen live objects** (the dedup
pools), versus **~2M objects + their String/Double references** today. There is no per-factor object
to trace, so young-GC work during ingestion collapses.

The `elements`/`ids`/`contingencyContexts` pools also **deduplicate**: in an N×M matrix there are
only M distinct variables and N distinct functions, so the pools hold thousands of entries, not
millions.

### 4.2 Flyweight cursor

A single reusable object implements the existing interface, backed by `(store, row)`:

```java
final class LfFactorCursor<V, E> implements LfSensitivityFactor<V, E> {
    private final LfSensitivityFactorStore<V, E> store;
    private int row;
    LfFactorCursor<V, E> at(int row) { this.row = row; return this; }

    public int getIndex()                    { return row; }
    public SensitivityFunctionType getFunctionType() { return FUNCTION_TYPES[store.functionType[row]]; }
    public LfElement getFunctionElement()    { int r = store.functionElementRef[row]; return r < 0 ? null : store.elements[r]; }
    public double getFunctionReference()     { return store.functionReference[row]; }
    public void   setFunctionReference(double v) { store.functionReference[row] = v; }
    public Status getStatus()                { return STATUSES[store.status[row]]; }
    public void   setStatus(Status s)        { store.status[row] = (byte) s.ordinal(); }
    public Double getSensitivityValuePredefinedResult() { return store.sensitivityPredefinedSet[row] ? store.sensitivityPredefined[row] : null; }
    public SensitivityFactorGroup<V, E> getGroup() { return groups.get(store.groupIndex[row]); }
    public void setGroup(SensitivityFactorGroup<V, E> g) { store.groupIndex[row] = g.getIndex(); }
    // getFunctionEquationTerm(): unchanged logic, computed on demand from getFunctionType()/getFunctionElement()
    // isVariableConnectedToSlackComponent / isVariableInContingency: same switch on variableType, reading store fields.
}
```

Enum arrays (`FUNCTION_TYPES = SensitivityFunctionType.values()` etc.) turn stored ordinals back into
enums with no allocation. `equals`/`hashCode` are **identity** and must never be used as map keys
(see risks).

### 4.3 Iteration API

The store exposes index-based iteration; every pipeline loop reuses one cursor per thread:

```java
void forEach(IntConsumer body);                  // over all rows
void forEach(IntArrayList rows, IntConsumer);     // over a subset (a group, a holder bucket)
LfSensitivityFactor<V, E> cursor(int row);        // the thread's cursor positioned at row
```

`SensitivityFactorHolder` bucket lists become `IntArrayList` (row ids). `SensitivityFactorGroup`
stores `IntArrayList rows`; `getFactors()` becomes `forEachFactor(IntConsumer)` so callers never hold
a factor across iterations.

### 4.4 Multi-variable factors stay objects

`MultiVariablesLfSensitivityFactor` (GLSK/HVDC) keeps its current object form, stored in a
`Int2ObjectMap<MultiVariablesLfSensitivityFactor> multiByRow`. Its rows still occupy a slot in the
scalar arrays (so indexing/bucketing/grouping are uniform), and the cursor delegates to the object
for the multi-variable-only methods when `multiByRow.containsKey(row)`. This keeps the rare, heavy
case simple and correct while the common single-variable case is fully flat.

### 4.5 Predefined results without boxing

The two `Double` fields become a `double[]` value + a `boolean[]` "set" flag. Reset per contingency is
a single `Arrays.fill(sensitivityPredefinedSet, false)` over the contingency's rows — no 2M
null-assignments, no boxing when set. (Note: in the *object* model, moving these two fields to
primitives is **not** on its own an ingestion win — see §5, phase 0.)

### 4.6 Grouping and back-reference

- `factor.setGroup(g)` → `store.groupIndex[row] = g.getIndex()`.
- `factor.getGroup()` → `groups.get(store.groupIndex[row])` (groups kept in an indexable list).
- `group.addFactor(factor)` → `group.rows.add(row)`.
- `createFactorGroups` keys by `(variableType, variableId)` exactly as today, but the group holds row
  ids. This removes the object back-reference cycle entirely.

## 5. Migration strategy (phased, each independently shippable & measurable)

### Phase 0 — remove redundant transient allocation (DONE)

**A common misconception, corrected.** The obvious "slim the object" idea — replacing the two boxed
`Double` predefined fields with `double` + a flag — was analysed and **rejected as an ingestion
optimization**: at read time those fields are `null` (no box is allocated), so inlining two `double`s
actually *grows* the per-factor object (~72 → ~80 bytes) and would *increase* base-case ingestion
allocation. That change only helps the post-contingency boxing and is deferred to the SoA store
(§4.5), where it is free.

What was actually implemented (behaviour-preserving, no object-model change):

- **`SensitivityFactorHolder.getFactorCount()`** — count factors by summing bucket sizes instead of
  materializing the whole `getAllFactors()` list. The AC path built that 2M-element list purely to
  read `.size()` in a log line.
- **`writeInvalidFactors` short-circuit** — when no factor is invalid (no `ZERO`/`SKIP`, the common
  case, detected by a non-copying `SensitivityFactorHolder.hasInvalidFactors()` scan), return the
  input holder directly instead of copying the full factor list and rebuilding an identical valid
  holder.

**Measured (EpsilonGC, warmed, 2M factors):** `writeInvalidFactors` drops from ~90 ms to ~25 ms and
the analysis allocates ~48 MB less transient garbage per run (one full factor-list copy plus the
rebuilt holder), which also relieves GC pressure. Full AC+DC suites pass. This is small — the list
copies are cheap `System.arraycopy`s — and does **not** touch the dominant cost (the ~2M per-factor
objects), which is exactly what motivates the SoA rewrite below.

### Phase 1 — introduce the store behind the interface

Add `LfSensitivityFactorStore`, `LfFactorCursor`, and the row-based holder/group, keeping the existing
object classes. A feature flag (`OpenSensitivityAnalysisParameters`, default off) selects
store-vs-object at `readAndCheckFactors`. All downstream code is adapted to the interface only;
single-variable path routes through the store, multi-variable through objects.

### Phase 2 — flip the default

After the equivalence + performance gates (§7) pass on AC and DC, make store-mode the default; remove
the object single-variable class once the flag has been on by default for a release.

## 6. Risks and mitigations

| Risk | Mitigation |
|---|---|
| **Flyweight aliasing** — code holding a factor reference across iterations sees it mutate. | Only `group.getFactors()` and `getGroup()` retain factors today; both become row ids. Forbid storing the cursor; expose `forEachFactor(IntConsumer)`. A debug build can hand out a fresh cursor per row to flush out captures. |
| **Factor used as a map/set key.** | Grep for `Map<...LfSensitivityFactor>` / `Set<...LfSensitivityFactor>`; replace with row-id keyed collections. Cursor `hashCode`/`equals` left as identity so accidental use fails loudly in tests. |
| **Thread-safety** — MT sensitivity runs `readAndCheckFactors` per thread. | One store per `analyzeContingencySet` invocation (already per-thread); one cursor per thread. No shared mutable cursor. |
| **Interface leakage** returning live element refs. | Elements are immutable network handles; returning pooled refs is safe. |
| **Enum ordinal fragility.** | Ordinals are in-memory only, never serialized; `values()` arrays cached in statics. |
| **Behavioural drift AC vs DC.** | Both consume only the interface; the equivalence gate runs both. |

## 7. Validation plan

1. **Equivalence gate** — for a corpus (IEEE14/57/118/300 + PEGASE 1354/9241/13659, ×{AC,DC},
   ×{base, N-1, GLSK, HVDC, distributed/!distributed}), assert store-mode and object-mode produce
   **bit-identical** `SensitivityValue`s and statuses. Parameterize over the flag.
2. **Full suite** — existing `Ac*`/`Dc*SensitivityAnalysis*Test` green in both modes.
3. **Allocation gate** — JFR/`-Xlog:gc` on the 2M-factor benchmark: ingestion allocation and GC pause
   count drop by the expected order of magnitude (target: ingestion young-GCs → ~0).
4. **Performance gate** — the `PegaseAcSensitivityBenchmark` (`-Dbench=true`): the 0–1.4 s GC tail on
   `readAndCheckFactors` collapses and total wall-clock variance shrinks.

## 8. Expected outcome

- **Ingestion:** ~130–250 MB/run of factor objects → ~90 MB of flat arrays + dedup pools; the
  GC-driven 0–1.4 s tail on `readAndCheckFactors` effectively removed. CPU floor roughly unchanged
  (~0.5 s), now the whole story.
- **Post-contingency:** boxed-`Double` predefined-result allocations removed (§4.5).
- **Whole analysis (2M factors):** with the core lazy-index patch and phase 0 already landed, removes
  the last large, variable allocation source in the OLF sensitivity path.

## 9. Effort estimate

| Phase | Effort | Risk | Win |
|---|---|---|---|
| 0 — redundant-copy removal (done) | landed | very low | ~65 ms + ~48 MB/run |
| 1 — store behind flag (single-var) + multi-var side map | ~1–1.5 weeks | medium | full SoA, flag-gated |
| 2 — flip default, remove old class | ~2–3 days + a release soak | medium | — |

Recommendation: phase 0 has landed. Commit to phase 1/2 only if the extreme-factor-count / large
matrix workload is a real target — the SoA rewrite is worthwhile precisely at high factor counts,
where its payoff (removing the GC tail) is largest. Otherwise phase 0 + the element-resolution
memoization + GC tuning is a reasonable stopping point.

## Appendix — companion powsybl-core patch

The result-egress fix lives in powsybl-core, not OLF: `SensitivityAnalysisResult` eagerly built three
per-value lookup indexes even for callers that only iterate `getValues()`. Building them lazily on
first key access cut the 2M-factor base case by ~39% (~6.4 s → ~3.9 s). The patch is kept alongside
this note as `powsybl-core-lazy-sensitivity-result-index.patch` (apply with `git am` in a
powsybl-core checkout); it needs to be upstreamed to take effect in a released core.
