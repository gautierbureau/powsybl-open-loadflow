# Scoping — Streaming all branch flows during (AC) security analysis

**Status:** Draft / scoping + **first prototype (CSV) in progress**
**Scope:** `com.powsybl.openloadflow.sa` (AC path primarily; design kept compatible with the DC and Woodbury‑DC paths) + a small provider‑agnostic seam in powsybl‑core
**Author:** (design proposal)

> **Prototype status (this branch).** A first CSV‑only slice is implemented across both repos:
> - **powsybl‑core** (`security-analysis-api`): a provider‑agnostic streaming seam —
>   `com.powsybl.security.writer.SecurityAnalysisResultWriter` (interface + `NO_OP`), a
>   `CsvSecurityAnalysisResultWriter`, and a `resultWriter` field on
>   `AbstractSecurityAnalysisRunParameters` (getter/setter). Requires core `7.4.0‑SNAPSHOT`.
> - **powsybl‑open‑loadflow**: `OpenSecurityAnalysisParameters.monitorAllBranches`; the provider
>   synthesizes an all‑branches `StateMonitor` and sets the writer; `AbstractSecurityAnalysis` streams
>   the pre‑contingency and each post‑contingency result and drops the streamed post‑contingency
>   `NetworkResult` from memory (bounded‑memory bypass). OLF now targets core `7.4.0‑SNAPSHOT`.
>
> Parquet, partitioned/per‑thread output, and DC parity are **not** in this slice — see §7.

---

## 1. Motivation

When running a full AC security analysis today, there is no built‑in way to obtain **all
branch flows for every contingency**. The only mechanism is the powsybl‑core
`StateMonitor` API: the caller must enumerate, up‑front, the exact branch / voltage‑level /
three‑winding‑transformer IDs to report, per contingency context. To "see everything" a
user has to build a `StateMonitor` listing every branch in the network — which is both
awkward to set up and, more importantly, does not scale in memory.

We want:

1. A capability to **monitor all flows** without manually enumerating monitored elements.
2. A way to **stream** those results out per contingency (e.g. to a Parquet file) instead
   of accumulating them in memory, because for large networks the full
   `branches × contingencies` result set does not fit in memory.

---

## 2. How it works today

### 2.1 Entry point and contingency loop

- SPI entry: `OpenSecurityAnalysisProvider.run(...)`
  (`src/main/java/com/powsybl/openloadflow/sa/OpenSecurityAnalysisProvider.java:62`).
  Monitors arrive as `runParameters.getMonitors()` (a `List<StateMonitor>`) and are handed
  to the concrete analysis constructor (`:89/:91/:94`).
- `AbstractSecurityAnalysis` builds `this.monitorIndex = new StateMonitorIndex(stateMonitors)`
  (`AbstractSecurityAnalysis.java:96`).
- Orchestration: `run` → `runSync` → `runSimulationsOnAllComponents` → `runSimulations`
  (`AbstractSecurityAnalysis.java:106 / 125 / 225 / 498`).
- The contingency loop is `while (contingencyIt.hasNext() ...)`
  (`AbstractSecurityAnalysis.java:564`) → `processContingency(...)` (`:751`) →
  `runPostContingencySimulation(...)` (`:631`), which reruns the load flow on the
  post‑contingency equation system (`:641`), builds a `PostContingencyResult` (`:666`) and
  appends it: `postContingencyResults.add(postContingencyResult)` (`:777`).

### 2.2 Where monitored flows are produced

- `PostContingencyNetworkResult.update()` (`PostContingencyNetworkResult.java:141`) reads the
  monitor for this contingency
  (`monitorIndex.getSpecificStateMonitors().get(contingency.getId())`, falling back to
  `getAllStateMonitor()`) and calls `AbstractNetworkResult.addResults(...)`
  (`AbstractNetworkResult.java:59`).
- `addResults` walks `network.getBranches()` and keeps those whose `getOriginalIds()`
  intersect `monitor.getBranchIds()` (`AbstractNetworkResult.java:62‑73`), likewise buses by
  voltage level and 3WTs by id.
- Per branch, `LfBranch.createBranchResult(...)` produces a core
  `BranchResult(branchId, p1,q1,i1, p2,q2,i2, flowTransfer)`; values come from the solved
  `Evaluable` flow expressions `p1.eval()/q1.eval()/i1.eval()/...` scaled to SI
  (`AbstractImpedantLfBranch.java:507‑533`), with V/angle available via the optional
  `OlfBranchResult` extension (`LfBranchImpl.java:224‑229`).
- Post‑contingency results are additionally **diffed** against the pre‑contingency baseline
  and dropped when unchanged within `ModifiedMonitoredElementsParameters` thresholds
  (`PostContingencyNetworkResult.java:82‑134`).

### 2.3 The memory wall

Everything accumulates eagerly:

- `List<PostContingencyResult> postContingencyResults` (`AbstractSecurityAnalysis.java:531`,
  appended at `:777`), each `PostContingencyResult` carrying a `NetworkResult` with its
  branch/bus/3WT results.
- These are folded into one `SecurityAnalysisResult` / `SecurityAnalysisReport`
  (`:582 / :222`), which is the return value of the whole API.
- In the multi‑thread path, each partition builds its own full list and they are
  concatenated at the end (`:209‑215`).

There is **no callback / listener / streaming seam** anywhere in the loop today (confirmed
across the `sa` package). The only asynchrony is the top‑level `CompletableFuture` and
contingency‑partition threading.

**Order‑of‑magnitude:** a `BranchResult` (+ its container overhead) is on the order of
100–200 bytes. For a 15 000‑branch network with 5 000 contingencies, "monitor all" =
15 000 × 5 000 = 75 M results ≈ **7–15 GB** retained until the run finishes. This is the
problem to solve; hence streaming to a columnar file rather than keeping results in memory.

---

## 3. Requirements & constraints

- **R1 — Monitor‑all switch:** a way to report all branches (optionally buses/3WTs) without
  enumerating IDs.
- **R2 — Streaming output:** per‑contingency results written incrementally to disk and not
  retained in memory.
- **R3 — Bounded memory:** peak memory independent of the number of contingencies (only one
  contingency's worth of flows live at a time, per thread).
- **R4 — Columnar/compressed format:** Parquet is the target (columnar, compressed, ideal
  for wide `branch × contingency` analytical data); format should be pluggable.
- **R5 — Thread‑safe:** must work with `threadCount > 1`
  (`OpenSecurityAnalysisParameters.getThreadCount()`), where contingencies are partitioned
  across threads (`AbstractSecurityAnalysis.java:188‑216`).
- **R6 — API‑preserving:** the powsybl‑core `SecurityAnalysisProvider.run(...)` signature and
  return type (`SecurityAnalysisReport`) cannot change. Streaming is a **side‑channel**;
  limit violations and status still return in the report as usual.
- **R7 — Opt‑in / backward compatible:** default behaviour unchanged when the feature is off.

---

## 4. Proposed design

Three independent pieces that compose: a **monitor‑all mode**, a **streaming writer seam**,
and an **in‑memory bypass**.

### 4.1 Monitor‑all mode

Add an internal notion of "monitor every branch" rather than forcing a giant `StateMonitor`.
Cleanest: a small flag consulted in `AbstractNetworkResult.addResults(...)` /
`PostContingencyNetworkResult.update(...)` that, when set, iterates all (non‑disabled)
branches directly instead of intersecting with `monitor.getBranchIds()`. This avoids
allocating a set of every ID and keeps the hot path allocation‑free.

Granularity to decide (see §9): branches only vs. branches + buses + 3WTs.

### 4.2 Streaming writer seam

Introduce an interface, e.g.:

```java
public interface SecurityAnalysisFlowWriter extends AutoCloseable {
    void writePreContingency(NetworkFlows flows);
    void writeContingency(String contingencyId,
                          PostContingencyComputationStatus status,
                          NetworkFlows flows);          // called once per contingency
    @Override void close();
}
```

where `NetworkFlows` is a light, streaming‑friendly view over the branch (and optionally
bus/3WT) results for one state — ideally emitting rows without first materializing a
`List<BranchResult>`.

- The writer is invoked from `processContingency` immediately after
  `runPostContingencySimulation` computes the post‑contingency flows
  (`AbstractSecurityAnalysis.java:773‑777`), and once for the pre‑contingency state
  (`:545`).
- `close()` is called at the end of `runSimulations` / the run, flushing footers.
- A no‑op default writer keeps current behaviour when the feature is off.

### 4.3 In‑memory bypass

When streaming is on, the per‑contingency `NetworkResult` returned inside
`PostContingencyResult` should be **empty** (flows went to the file). Limit‑violation
detection and `LimitViolationsResult` are unaffected and still returned in the report — the
compact, decision‑relevant summary stays in memory; the bulk flow data goes to disk. This is
what actually delivers R3.

### 4.4 Output format & schema (Parquet)

One row per (branch, contingency) with a proposed schema:

| column            | type    | notes                                            |
|-------------------|---------|--------------------------------------------------|
| `contingency_id`  | string  | `""` / sentinel for the pre‑contingency (N) state|
| `status`          | string  | post‑contingency computation status              |
| `branch_id`       | string  |                                                  |
| `p1,q1,i1`        | double  | SI, side 1                                       |
| `p2,q2,i2`        | double  | SI, side 2                                       |
| `flow_transfer`   | double  | already computed by `buildBranchResult`          |
| `v1,v2,angle1,angle2` | double | optional, from the `OlfBranchResult` extension |

Notes:
- Partition the dataset by `contingency_id` (or by thread, see §4.5) so it reads back as a
  standard partitioned Parquet dataset.
- `contingency_id` and `status` are extremely low‑cardinality within a row group → dictionary
  encoding + zstd/snappy compresses the redundant columns very well.
- Buses / 3WTs, if included, go to sibling files with their own schema (different column
  shape), not the branch schema.

### 4.5 Threading / partitioned output

With `threadCount > 1`, contingencies are partitioned across threads
(`AbstractSecurityAnalysis.java:188‑216`). Rather than synchronizing a single writer,
give **one writer (one part‑file) per partition/thread**, e.g.
`flows/part-0.parquet`, `flows/part-1.parquet`, …. This is the natural Parquet dataset
layout, needs no locking, and preserves throughput. A small factory creates a writer per
partition; the pre‑contingency state can be written once (partition 0) or replicated.

### 4.6 Public API / configuration

Two complementary surfaces:

1. **Parameter‑driven (standard powsybl path).** Add fields to
   `OpenSecurityAnalysisParameters` (`OpenSecurityAnalysisParameters.java`) — e.g.
   `monitorAllBranches` (bool), `flowResultsOutputPath` (string/dir), `flowResultsFormat`
   (enum: `NONE` default / `PARQUET` / `CSV`), plus the corresponding entries in
   `SPECIFIC_PARAMETERS_NAMES`, `load`, `update`, and the JSON serializer
   (`OpenSecurityAnalysisParameterJsonSerializer.java`). The provider reads these, builds the
   writer factory, and threads it into the analysis. This works from JSON config and
   `itools` with no code.
2. **Programmatic seam (embedding).** Allow passing a `SecurityAnalysisFlowWriter` factory
   directly to the OLF‑specific analysis for callers who want to stream somewhere other than
   a file (custom sink, socket, in‑process consumer). Same interface, no file path.

---

## 5. Parquet library options (decision needed)

The OLF jar is dependency‑light and Java 21. Standard `parquet-mr` drags in Hadoop, which is
heavy and undesirable in a core compute jar. Recommendation: **keep the format pluggable and
isolate the Parquet dependency in a separate optional Maven module** so the core OLF jar stays
clean and a zero‑dependency CSV writer is always available.

| Option | Pros | Cons |
|--------|------|------|
| **CSV (built‑in, zero‑dep)** | trivial, no deps, good first milestone | large, uncompressed, not columnar |
| **`parquet-mr` (`parquet-avro`)** | canonical, battle‑tested | pulls Hadoop `Configuration`/deps; fat |
| **Carpet** (records→Parquet, shades Hadoop) | maps Java records directly, Apache‑2.0, no visible Hadoop | newer, extra transitive weight |
| **`parquet-floor`** | minimal Hadoop‑free Parquet writer | smaller community |
| **DuckDB JDBC `COPY ... TO parquet`** | one jar, simple, also queryable | native lib; SQL‑ish write path |

Suggested path: ship CSV first (validates the streaming seam end‑to‑end with no dependency
risk), then add a `powsybl-open-loadflow-parquet` module using Carpet or parquet‑floor.

---

## 6. Backward compatibility

- Feature is fully opt‑in: default `flowResultsFormat = NONE` ⇒ no writer, no bypass,
  identical behaviour and identical `SecurityAnalysisResult` to today.
- Existing `StateMonitor`‑based monitoring is untouched and can coexist (monitor‑all is an
  additional mode, not a replacement).
- No change to the powsybl‑core SPI signature.

---

## 7. Phased implementation plan

1. **Streaming seam (no format yet).** Add `SecurityAnalysisFlowWriter` (+ no‑op default),
   wire the two call sites in `processContingency` / pre‑contingency, and the `close()`
   lifecycle. Add a CSV writer. Prove end‑to‑end on a small network. *No public API change
   beyond an internal factory.*
2. **Monitor‑all mode + in‑memory bypass.** Add the monitor‑all short‑circuit in
   `AbstractNetworkResult` / `PostContingencyNetworkResult`, and empty the in‑memory
   `NetworkResult` when streaming. Verify bounded memory on a large network.
3. **Parameters & JSON.** Extend `OpenSecurityAnalysisParameters` (+ serializer, +
   `load`/`update`, + docs under `docs/security/parameters.md`).
4. **Parquet module.** New optional Maven module + `SecurityAnalysisFlowWriter` impl;
   partitioned output; compression choice.
5. **Multi‑thread output.** One writer per partition; validate with `threadCount > 1`.
6. **DC / Woodbury‑DC parity** (`DcSecurityAnalysis`, `WoodburyDcSecurityAnalysis`) — same
   `update()` seams exist there (`WoodburyDcSecurityAnalysis.java:208/261/430`).

---

## 8. Testing strategy

- Unit: writer receives exactly one pre‑contingency + one row‑set per contingency; values
  match the current `BranchResult` values for a small network (golden comparison vs. today's
  in‑memory results).
- Parquet round‑trip: write then read back, assert schema + row counts + sampled values.
- Memory: large synthetic network, assert peak heap is flat in the number of contingencies
  (streaming) vs. the current accumulation.
- Concurrency: `threadCount > 1` produces N part‑files whose union equals the single‑thread
  output.
- Regression: feature‑off run produces byte‑identical `SecurityAnalysisResult` to `main`.

---

## 9. Open questions / decisions

1. **Monitored granularity:** branches only, or branches + buses + 3WTs in v1?
2. **Diff filtering:** keep the pre/post `changed()` threshold filtering
   (`PostContingencyNetworkResult.java:82‑134`) for streamed output, or always emit every
   branch (full matrix)? Full matrix is simpler to consume; filtering shrinks output.
3. **Parquet library:** Carpet vs. parquet‑floor vs. parquet‑mr vs. DuckDB (§5).
4. **API home:** OLF‑only (as scoped here) vs. proposing the streaming‑writer abstraction
   upstream in powsybl‑core so other providers can reuse it.
5. **Output layout:** partition by contingency vs. by thread; single dataset dir vs. single
   file (single file is incompatible with lock‑free multi‑thread writing).
6. **Extensions:** always include V/angle (`OlfBranchResult`) columns, or gate on
   `createResultExtension`?
7. **Pre‑contingency (N) state:** include in the same dataset with a sentinel
   `contingency_id`, or a separate file?
