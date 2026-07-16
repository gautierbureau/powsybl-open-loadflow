# Scoping — Time‑series load flow API (fixed structure, varying generation plan)

**Status:** Implemented for generators — `com.powsybl.openloadflow.ts`. Sections 8 and 9 have been
reconciled with the engine as built and measured; loads/HVDC setpoints, relative setpoints and the
sequential warm‑start mode below remain proposals.
**Scope:** a new `com.powsybl.openloadflow.ts` API in powsybl‑open‑loadflow, reusing the
security‑analysis compute machinery and the streaming‑output seam introduced by **PR #23**
(*"Stream all branch flows out of a security analysis (CSV / Parquet)"*) and its companion
powsybl‑core PR.
**Author:** design proposal

> **One‑line summary.** Run *many* load flows on **one fixed network topology** where only the
> **injection setpoints change** per time step (the generation plan, plus optionally loads),
> parallelised across steps, streaming the (large) per‑step results to a columnar dataset on
> disk instead of accumulating them in memory. The per‑step re‑solve, the network
> save/restore, the multi‑threaded partitioning and the streaming writer are **already present
> in the security‑analysis code path** — this API reuses them rather than starting from
> scratch.

---

## 1. Motivation

OLF today exposes single‑shot load flow (`LoadFlow.run`) and security analysis (N‑1 + operator
strategies). There is **no API for a *time series* / *multi‑variant* load flow**: the same
network solved repeatedly while only the **production plan of generators** (their active‑power
targets) — and optionally loads — changes from one step to the next.

Use cases: hourly/quarter‑hourly market or adequacy runs, Monte‑Carlo generation dispatches,
RES scenario sweeps. Two properties dominate the requirements:

1. **The structure is fixed.** Same buses, branches, switches, controls — only injection
   *targets* move. This is much cheaper than security analysis: no topology change, no
   connectivity recomputation, no Jacobian **structure** change.
2. **The output is huge.** `branches × steps` (and `buses × steps`, `generators × steps`) is
   easily tens/hundreds of millions of rows. Accumulating it in memory does not scale — the
   result must be **streamed** to a columnar file, exactly the problem PR #23 solved for
   security analysis.

## 2. Key reuse finding — the mechanism already exists

The costly primitive ("hold the structure fixed, change the targets, re‑solve efficiently") is
**already implemented and battle‑tested** in the security‑analysis / action path:

- **Target changes auto‑invalidate the RHS.** `TargetVector`
  (`equations/TargetVector.java:54`) registers an `LfNetworkListener` and calls
  `invalidateValues()` on `onGenerationActivePowerTargetChange` /
  `onLoadActivePowerTargetChange` / `onGenerationReactivePowerTargetChange` /
  `onGeneratorVoltageControlTargetChange` … So calling `LfGenerator.setTargetP(...)` (as
  `LfGeneratorAction.apply` already does, `network/action/LfGeneratorAction.java:62`) is enough
  to make the next solve pick up the new target — **no equation‑system rebuild**.
- **Re‑solve on a persistent context.** `AbstractSecurityAnalysis.runPostContingencySimulation`
  re‑runs `createLoadFlowEngine(context).run()` on the *same* `context` after each contingency
  (`sa/AbstractSecurityAnalysis.java:641`). The equation system, the Jacobian sparsity pattern
  and (for DC) the matrix factorisation live in `context` and are reused across solves. A
  time‑step re‑solve is the *same call*, minus the topology change.
- **Cheap save/restore between states.** `NetworkState.save(lfNetwork)` /
  `state.restore()` (`network/NetworkState.java:47/61`) snapshot bus/branch/hvdc/area state and
  reset generator initial targets; SA uses this to reset between contingencies
  (`sa/AbstractSecurityAnalysis.java:551`). We reuse it to reset between steps.
- **Multi‑threaded partitioning.** `ContingencyMultiThreadHelper`
  (`util/mt/ContingencyMultiThreadHelper.java`) builds **one `LfNetwork` per partition on its
  own thread** (locking only network creation/close, `allowVariantMultiThreadAccess(true)`),
  then runs a body over that partition. We partition **time steps** instead of contingencies.
- **The streaming‑output seam.** PR #23 added, in powsybl‑core, a provider‑agnostic
  `SecurityAnalysisResultWriter` (primitive per‑row `writeBranchResult(...)`, a `NO_OP`), a
  per‑partition `…WriterFactory` (`create(int partitionIndex)` → one lock‑free `part-<i>` file
  per thread), and CSV + Parquet (`security-analysis-parquet`, parquet‑floor, Hadoop‑free)
  implementations. On the OLF side it added a **vectorised, allocation‑free emit path**:
  `LfBranch.BranchFlowConsumer` + `LfBranch.emitBranchResults(...)` + a shared
  `AbstractImpedantLfBranch.emitBranchFlows(...)` that both the streaming path and
  `createBranchResult` call, so results are byte‑identical. Per‑partition writers are bound via
  a `ThreadLocal` in a `runPartition(index, body)` wrapper.

**Conclusion:** the time‑series API is essentially *security‑analysis‑without‑contingencies*: it
reuses the LfNetwork loading, the persistent `LfLoadFlowContext`, the re‑solve, the
save/restore, the MT partitioning and the streaming writer — and replaces "apply a contingency"
with "apply this step's injection setpoints".

## 3. How security analysis does it today (the parts we reuse)

Per partition/thread (`AbstractSecurityAnalysis.runSimulations`, `sa/AbstractSecurityAnalysis.java:498`):

1. Build the `LfNetwork` list + a `LfLoadFlowContext` **once** (`createLoadFlowContext`, `:520`).
2. Run the **base** load flow (`createLoadFlowEngine(context).run()`, `:526`).
3. `NetworkState networkState = NetworkState.save(lfNetwork)` (`:551`).
4. For each contingency: apply it (`lfContingency.apply(...)`, `:768`), re‑solve
   (`runPostContingencySimulation`, `:631` → `:641`), collect results, then
   `networkState.restore()` between iterations.
5. Results are **accumulated in memory** into a `SecurityAnalysisResult` — the memory wall PR #23
   removes by streaming and returning an empty in‑memory `NetworkResult` when `monitorAllBranches`
   is on.

Multi‑thread (`:188`): `Lists2.partition(contingencies, threadCount)` →
`ContingencyMultiThreadHelper.createLFNetworksPerContingencyPartitionAndRunAnalysis(...)`; each
partition wrapped in `runPartition(partitionNum, …)` so its writer is a per‑thread `part-<i>`
file (PR #23).

The time‑series engine keeps steps 1–3 verbatim, replaces step 4's "apply contingency" with
"apply setpoints", and reuses PR #23's streaming for the output instead of accumulating.

## 4. Requirements

- **R1 — Fixed structure, varying targets.** Change generator (and optional load) active‑power
  targets per step; solve; repeat. No topology/contingency handling.
- **R2 — Efficient re‑solve.** Reuse the equation system, Jacobian pattern and (DC) matrix
  factorisation across steps; only the target vector changes.
- **R3 — Multithreaded.** Partition steps across threads, one `LfNetwork` + context per
  partition, lock‑free per‑partition output (reuse the SA MT helper + PR #23 writer).
- **R4 — Streamed, bounded‑memory output.** *Output* peak memory independent of the number of
  steps: the bulk (branch flows, bus voltages, generator dispatch) goes to a columnar dataset
  (CSV / Parquet); only a compact per‑step status summary stays in memory. Note this constrains the
  output only — the *input* plan is read once and so scales with `generators × steps`; see the
  known limitation in §8.
- **R5 — Standard input.** The production plan is supplied as **powsybl‑core time series**
  (`DoubleTimeSeries`), so it interoperates with existing PowSyBl TS tooling.
- **R6 — Reuse, don't fork.** Reuse PR #23's writer seam (generalised to be provider‑agnostic),
  the vectorised emit path, `NetworkState`, and the MT helper. Do not duplicate them.
- **R7 — AC and DC.** Both engines; DC gets an extra fast path (§8).

## 5. Chosen decisions (from design review)

- **Input model — powsybl‑core `TimeSeries`.** The plan is a `List<DoubleTimeSeries>` sharing a
  common `TimeSeriesIndex`; each series maps to a controllable injection (generator/load). See §6.
- **Output seam — generalise PR #23's core writer.** Rename the SA‑specific
  `SecurityAnalysisResultWriter` into a **provider‑agnostic** `NetworkResultWriter` with a
  generic state key, reused by both security analysis (`stateId = contingencyId`,
  `subStateId = operatorStrategyId`) and the time‑series LF (`stateId = timestamp/step`,
  `subStateId = ""`). One CSV impl + one Parquet module serve both. See §7.
- **Streamed content — three sibling datasets:** branch flows (PR #23 schema), bus voltages, and
  generator dispatch (§7.2).

## 6. Input — the production plan as time series

**Type:** `List<DoubleTimeSeries>` (powsybl‑core `com.powsybl.timeseries`) sharing one
`TimeSeriesIndex` (`RegularTimeSeriesIndex` or `IrregularTimeSeriesIndex`). The index defines:

- **the number of steps** = `index.getPointCount()`, and
- **the per‑step timestamp** (`index.getInstantAt(i)`), used as the output state key.

**Series → equipment mapping.** By default a series' *name* is the IIDM equipment id
(generator or load); a step applies, for each series `s`, `value = s.at(i)` as the new
active‑power target of the mapped injection. An explicit
`Map<String, InjectionType>`/mapping override is supported for the cases where the series name
is not the equipment id, and a per‑series flag distinguishes **absolute** vs **relative** (delta
on the base target) setpoints — mirroring `GeneratorAction.isActivePowerRelativeValue`
(`network/action/LfGeneratorAction.java:36-41`).

**Applying a step** reuses the existing primitive: `LfGenerator.setTargetP(newP)` +
`setInitialTargetP(newP)` + `reApplyActivePowerControlChecks(...)` (exactly
`LfGeneratorAction.apply`), and the load equivalent (`LfLoadAction` /
`onLoadActivePowerTargetChange`). Both already trigger `TargetVector.invalidateValues()`.

**Partitioning for MT.** The step range `[0, pointCount)` is split into contiguous blocks (one
per thread). Each thread reads only its block's values from the plan, which is read **once on the
main thread** into an immutable `double[]` per series (see §8) — threads never touch a
`DoubleTimeSeries` themselves, so there is no locking and no lazy‑init race on the input.

## 7. Output — generalised streaming writer (built on PR #23)

### 7.1 Generalise the core seam

PR #23's core interface is branch‑flow‑shaped but SA‑named. We rename it to a provider‑agnostic
abstraction and give it two more per‑row methods for the extra datasets. Proposed core shape
(package e.g. `com.powsybl.computation…`/`com.powsybl.loadflow.results` — final home TBD with core
maintainers; it must be reachable from both `security-analysis-api` and OLF):

```java
public interface NetworkResultWriter extends AutoCloseable {
    // branch flows — identical columns to PR #23, generic leading keys
    void writeBranchResult(String stateId, String subStateId, String status, String branchId,
                           double p1, double q1, double i1, double p2, double q2, double i2, double flowTransfer);
    // bus voltages
    void writeBusResult(String stateId, String subStateId, String status, String busId,
                        double v, double angle);
    // generator dispatch (after slack distribution / balancing)
    void writeGeneratorResult(String stateId, String subStateId, String status, String generatorId,
                              double targetP, double p);
    @Override void close();

    NetworkResultWriter NO_OP = /* all methods no‑op */;
}

public interface NetworkResultWriterFactory {
    NetworkResultWriter create(int partitionIndex);   // one lock‑free part‑file set per partition
    NetworkResultWriterFactory NO_OP = partitionIndex -> NetworkResultWriter.NO_OP;
}
```

- **Security analysis is unchanged behaviourally:** its existing call
  `writeBranchResult(contingencyId, operatorStrategyId, status, branchId, …)` maps onto the
  generic `(stateId, subStateId, …)` one‑to‑one — the two leading columns just become generic. SA
  keeps streaming only branches (its `writeBus/GeneratorResult` calls are simply absent).
- **CSV** (zero‑dep) and **Parquet** (`parquet-floor`, Hadoop‑free, the module PR #23 adds) each
  gain the two extra datasets: sibling files `branches/part-<i>`, `buses/part-<i>`,
  `generators/part-<i>` (each its own schema). This mirrors PR #23's resolved decision that
  buses/3WTs, if added, are *sibling datasets with their own schema*.

### 7.2 Streamed schemas (one row per element per step)

| dataset | columns |
|---|---|
| **branches** | `stateId`(timestamp/step), `subStateId`(""), `status`, `branchId`, `p1,q1,i1,p2,q2,i2,flowTransfer` (double, SI) |
| **buses** | `stateId`, `subStateId`, `status`, `busId`, `v`, `angle` |
| **generators** | `stateId`, `subStateId`, `status`, `generatorId`, `targetP`(requested), `p`(actual, after slack distribution) |

`flowTransfer` is `NaN` in the time‑series context (no contingency baseline); kept for schema
parity with the SA branch dataset. The `subStateId` column is `""` for time‑series rows (it
carries the operator‑strategy id in SA); we keep it so both producers share **one** file schema.
The **primary key** is `stateId` = the step's timestamp (ISO‑8601) — optionally a companion
integer `stepIndex` column can be added if pure‑integer keys are preferred.

### 7.3 Reused emit path

- **Branches:** reuse `LfBranch.emitBranchResults(NaN, NaN, zeroImpedanceFlows, loadFlowModel, sink)`
  verbatim (PR #23) — the allocation‑free, vectorised, per‑subclass path that already skips
  switches and 3WT legs and shares SI‑scaling with `createBranchResult`.
- **Buses:** iterate `lfNetwork.getBuses()`, emit `bus.getV() * nominalV`, `Math.toDegrees(bus.getAngle())`
  (or the existing `BusResult` conversion) — a handful of primitives, no per‑row object.
- **Generators:** iterate `lfNetwork.getGenerators()`, emit `getTargetP()*SB` and the post‑solve
  actual `getP()` (`getCalculatedP`) so callers see how slack distribution reshaped the plan.

### 7.4 Bounded‑memory bypass (R4)

Like PR #23, when streaming is on the per‑step bulk results are **not** retained. The API returns
a compact `TimeSeriesLoadFlowResult`: a `List<StepResult>` of
`{stepIndex, timestamp, status, iterationCount, slackBusActivePowerMismatch, distributedActivePower}`
— O(steps) small rows, independent of network size.

## 8. Per‑step solve & efficiency

Per partition/thread:

Read the plan once, on the main thread, **before** any partition thread exists:

```
plan = [(generatorId, series.getDoubleTimeSeriesValues()) for series in plan]
```

`DoubleTimeSeries.get(int)` is a convenience method, **not** an accessor: the default
implementation materialises the whole series (`getDoubleTimeSeriesValues()` → `toArray()`) on
*every* call, and no implementation overrides it. Calling it per generator per step costs
`O(steps² × generators)` and allocates a full array per call — measured at ~10 s versus ~29 ms
(**343×**) just to walk a one‑year hourly plan over 50 generators. It is also the only part unsafe
to touch concurrently: `CalculatedTimeSeries` lazily computes and caches its index in a plain
non‑volatile field, so partitions sharing a plan would race on it (fixed upstream in core PR #33,
but the engine no longer depends on it either way). Reading once fixes both.

> **Known limitation — plan memory.** Reading once materialises the full `generators × steps`
> matrix of doubles, which this document previously ruled out ("never materialise the full
> `injections × steps` matrix"). That promise was traded for the 343× above, deliberately and with
> eyes open. The cost is bounded: 500 generators × 8760 hourly steps ≈ **35 MB**, quarter‑hourly for
> a year ≈ **140 MB**. For a plan of `StoredDoubleTimeSeries` with uncompressed chunks the values
> are already in memory at that size, so the peak roughly doubles. Where it would bite is
> **compressed or calculated plans** — a run‑length‑encoded flat profile living in kilobytes, or a
> `CalculatedTimeSeries` that is a small expression tree, both expand to full size when read.
>
> No plan has hurt yet, so this is recorded rather than fixed. If one does, the fix belongs **in
> this engine, not in core** — neither option needs new core API:
>
> 1. **Materialise per partition.** Read only each partition's step range; peak memory drops by the
>    thread count, still one read per series. Smallest change.
> 2. **Stream with `DoubleTimeSeries.iterator(List<DoubleTimeSeries>)` / `DoubleMultiPoint`** — the
>    existing bulk‑read path for walking many aligned series step by step, materialising nothing.
>    Restores the original promise in full.
>
> What is **not** the answer is random access on `DoubleDataChunk` (a `getValue(int)` SPI plus a
> per‑chunk prefix index over the run‑length encoding). Our access pattern is "every step of every
> series, once": indexing a `double[]` already beats a chunk search per point, so it would be new
> permanent public API in core, slower for its only caller here.

Then, per partition/thread:

```
build LfNetwork + LfLoadFlowContext once            // fixed structure, built ONE time
NetworkState base = NetworkState.save(lfNetwork)     // as loaded, BEFORE any solve
bind partition writer (ThreadLocal, PR #23 runPartition)
for step in partitionSteps:
    base.restore()                                   // deterministic, order‑independent
    applySetpoints(step)                             // setTargetP/… -> TargetVector auto‑invalidated
    status = createLoadFlowEngine(context).run()     // reuses equations + jacobian structure
    if converged:
        emit branch / bus / generator rows to partition writer
    record compact StepResult
close partition writer
```

- **No base solve.** The snapshot must be taken **before** anything is solved. Solving mutates far
  more than the generator targets it distributes the slack over: outer loops move tap positions and
  shunt sections and switch buses between PV and PQ, and `NetworkState.save()` additionally calls
  `setGeneratorsInitialTargetPToTargetP()`. A post‑solve snapshot bakes all of that in, so each step
  restarts from that solve's control state and no longer equals an independent run. A base solve
  would buy nothing anyway: the solver initialises its state vector from the configured voltage
  initializer, so restored voltages are overwritten. (SA *does* warm‑start, but only because
  `AcSecurityAnalysis` explicitly opts into `PreviousValueVoltageInitializer`; the TS engine keeps
  the classical default so a step matches `LoadFlow.run`.)
- **AC:** the Jacobian **structure** is fixed → the equation system and the symbolic factorisation
  in `context` are reused across steps; only values are refreshed. This is SA's post‑contingency
  re‑solve minus the topology delta, minus the warm start.
- **DC — measured, not the "one factorisation total" originally claimed here.** The DC state matrix
  depends only on the fixed structure, so the *symbolic* factorisation is built **once per
  partition** and reused — confirmed: a 3‑step DC run logs one `Jacobian matrix built` and one
  `LU decomposition done`. But each step still logs `Jacobian matrix values updated` +
  `LU decomposition updated`: writing the solution back into the state vector fires
  `onStateUpdate()`, which marks the Jacobian `VALUES_INVALID`. So steps are *not* pure
  back‑substitutions against a cached LU. For DC that invalidation is pessimistic (the susceptance
  matrix is target‑independent), and skipping it is a real remaining lever — but it is pre‑existing
  OLF behaviour that SA shares, so it belongs in its own change, not here.
- **Restore vs. warm‑start trade‑off.** Default = `restore()` to base each step →
  reproducible and independent of partitioning/order. An **opt‑in** "sequential warm start"
  mode skips the restore and starts each step from the previous converged solution (faster when
  consecutive steps are close), at the cost of order‑dependence within a partition; off by
  default.
- **No connectivity / no contingency machinery** is instantiated — the time‑series path is
  strictly lighter than SA.

## 9. Public API (OLF‑native — no core SPI needed)

New package `com.powsybl.openloadflow.ts`:

- `TimeSeriesLoadFlow` — a static facade (`run(network, plan, parameters, writerFactory, …)`),
  modelled on `LoadFlow.run` / the SA runner. Returns `TimeSeriesLoadFlowResult`.
- `TimeSeriesLoadFlowParameters` — `LoadFlowParameters` + OLF extension holding `threadCount`,
  the dataset‑selection flags (branches/buses/generators), the setpoint mapping, and (once added)
  the sequential warm‑start mode. `NetworkResultWriterFactory` is passed programmatically (like PR #23 passes the
  factory via `SecurityAnalysisRunParameters`), so a caller can stream to a directory (CSV /
  Parquet) or to a custom sink.
- `TimeSeriesLoadFlowResult` / `StepResult` — the compact in‑memory summary (§7.4).

We **reuse building blocks, not inherit** `AbstractSecurityAnalysis` (which is coupled to
contingencies/operator strategies/limit violations). The shared pieces —
LfNetwork loading (`Networks.load…`), `LfLoadFlowContext`, `createLoadFlowEngine`,
`NetworkState`, `LfBranch.emitBranchResults`, the writer seam, and the MT partition helper — are
factored so both SA and TS call them. Where the MT helper is contingency‑typed today
(`ContingencyMultiThreadHelper`, `PropagatedContingency`), extract a generic
"build one LfNetwork per partition, lock creation, delay close, run a body over the partition"
core that both consumers share (or add a lean TS‑specific sibling if extraction proves invasive).

## 10. Phased implementation plan

1. **Core seam generalisation.** In powsybl‑core, rename PR #23's `SecurityAnalysisResultWriter`
   → `NetworkResultWriter`, add `writeBusResult` / `writeGeneratorResult`, extend the CSV and
   `security-analysis-parquet` writers with the two sibling datasets, and re‑point SA's branch
   call onto the generic keys. *Depends on / builds atop PR #23's core companion — coordinate so
   both consumers share one seam.*
2. **OLF API surface.** `com.powsybl.openloadflow.ts`: `TimeSeriesLoadFlow`,
   `TimeSeriesLoadFlowParameters`, `TimeSeriesLoadFlowResult` + `StepResult`.
3. **Single‑thread engine.** Build LfNetwork/context once; `NetworkState.save` **before any
   solve**;
   per‑step `restore → applySetpoints → run → emit`. Branch emit reuses
   `LfBranch.emitBranchResults`; add bus + generator emit. Prove golden equality vs. N
   independent `LoadFlow.run`s on a small network.
4. **Time‑series input adapter.** `List<DoubleTimeSeries>` + `TimeSeriesIndex` → per‑step
   setpoints; name→equipment mapping; absolute/relative flag.
5. **Multithread + per‑partition writer.** Partition the step range; reuse/generalise the SA MT
   helper (one LfNetwork per partition) and PR #23's `runPartition` ThreadLocal writer → one
   `part-<i>` set per thread; validate the union equals the single‑thread output.
6. **DC fast path.** *Verified (§8):* the symbolic factorisation is reused across steps, but each
   step still triggers a numeric LU update because `onStateUpdate()` invalidates the Jacobian
   values. A targets‑only re‑solve that skips it is a real lever, but it is pre‑existing OLF
   behaviour shared with SA — track it separately. Benchmark vs. AC.
7. **Parquet + docs.** Wire the Parquet writer for all three datasets; document parameters under
   `docs/`.

## 11. Testing strategy

- **Golden:** per‑step streamed values equal an independent `LoadFlow.run` with the same
  setpoints applied to a fresh network (small network, a few steps), AC and DC.
- **Memory:** large synthetic network + many steps → peak heap flat in the number of steps
  (streaming) vs. accumulation.
- **Concurrency:** `threadCount > 1` produces N `part-<i>` sets whose union equals the
  single‑thread output; per‑thread lock‑free writers.
- **DC factorisation reuse:** assert the matrix is factorised once per partition (e.g. via a
  solve/factorisation counter) and results still match.
- **Parquet round‑trip:** write → read back for each of the three datasets; schema + row counts +
  sampled values.
- **Input mapping:** absolute vs relative setpoints, series‑name→equipment mapping, mismatched /
  missing ids.
- **SA regression:** the generalised writer leaves security‑analysis output byte‑identical to
  PR #23.

## 12. Open questions

1. **Core package/home for the generalised `NetworkResultWriter`** — inside `security-analysis-api`
   (SA already there, OLF depends on it) vs. a new neutral module both depend on. To settle with
   core maintainers; PR #23 being unmerged gives room to shape it.
2. **Timestamp encoding** of `stateId` — ISO‑8601 string vs. an added integer `stepIndex` (+
   epoch‑millis) column. Leaning: both a `timestamp` and a `stepIndex` column, `stateId` = ISO
   string for schema‑sharing with SA.
3. **Which injections are controllable** — generators only (v1) vs. generators + loads (+ HVDC
   setpoints) from step one.
4. **Voltage/angle extension columns** — always emit V/angle, or gate like PR #23's
   `createResultExtension` (`OlfBranchResult`).
5. **Non‑convergent steps** — emit an empty/`status`‑only row, or omit the step's bulk rows
   entirely (status still recorded in the in‑memory summary either way).
6. **Sequential warm‑start mode** — expose it, and if so, document its order‑dependence within a
   partition.
