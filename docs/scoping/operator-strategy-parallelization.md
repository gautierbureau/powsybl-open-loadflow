# Scoping: Parallelization of Operator Strategies within a Contingency

## Context

Today, the security analysis (SA) of PowSyBl Open Load Flow parallelizes work at the
**contingency** granularity. The proposal scoped here is to add a second axis of
parallelization: running the **operator strategies of a given contingency** in parallel.

This document describes the current design, why an additional parallelization axis is
useful, the technical challenges, the candidate designs, and a recommended path forward.
It is a scoping document — no behavioral change is made yet.

## 1. How parallelization works today

### 1.1 Contingency-level partitioning

Entry point: `AbstractSecurityAnalysis.runSync(...)`
(`src/main/java/com/powsybl/openloadflow/sa/AbstractSecurityAnalysis.java`).

- The number of threads is driven by the extension parameter
  `OpenSecurityAnalysisParameters.threadCount` (`threadCount`, default `1`).
- When `threadCount == 1`, the whole analysis runs on a single thread and a single
  `LfNetworkList`.
- When `threadCount > 1`:
  - The **contingency list** is split into `threadCount` partitions via
    `Lists2.partition(contingencies, threadCount)`.
  - `ContingencyMultiThreadHelper.createLFNetworksPerContingencyPartitionAndRunAnalysis(...)`
    submits one `CompletableFutureTask` per non-empty partition to the SA `Executor`
    (`computationManager.getExecutor()`).
  - Each task (`ContingencyMultiThreadHelper.runTask`) builds **its own full
    `LfNetworkList`** from a cloned IIDM working variant (network duplication), then
    runs `runSimulationsOnAllComponents(...)` on its partition.
  - Results are written into pre-allocated slots indexed by partition number
    (`partitionResults.set(partitionNum, ...)`) so the merged output is deterministic
    regardless of thread completion order.
  - Per-thread `ReportNode`s are merged afterwards
    (`ContingencyMultiThreadHelper.mergeReportThreadResults`).

Key constraints already handled by the current MT helper:

- IIDM variant cloning / removal is not thread-safe → network creation is guarded by a
  `ReentrantLock` and `LfNetworkList` closing is deferred out of the worker threads.
- `network.getVariantManager().allowVariantMultiThreadAccess(true)` is required because
  the Lf network reads back into the original IIDM variant.

### 1.2 Operator strategies today (sequential, within a contingency)

Within a single network/component, `runSimulations(...)` iterates the contingencies and,
for each, calls `processContingency(...)`. That method:

1. Applies the contingency and runs the post-contingency load flow
   (`runPostContingencySimulation`).
2. Looks up the operator strategies for the contingency via
   `operatorStrategiesByContingencyId` (built by
   `OperatorStrategies.indexByContingencyId`).
3. Runs the strategies **sequentially**:
   - **1 strategy**: no state save; `setGeneratorsInitialTargetPToTargetP()` then
     `runActionSimulation(...)`.
   - **N strategies**: `NetworkState.save(lfNetwork)` of the post-contingency state,
     then for each strategy `runActionSimulation(...)` followed by
     `postContingencyNetworkState.restore()` to return to the post-contingency state
     before the next strategy.
4. Restores the base (pre-contingency) state before moving to the next contingency.

Each `runActionSimulation` applies the strategy's actions on top of the post-contingency
state and re-runs the load flow engine on the same `LfNetwork` / load flow `context`.

### 1.3 Consequences

The unit of parallel work is the *contingency*, and everything below a contingency
(post-contingency LF + all its operator strategies) is serial on one thread that owns one
network copy.

## 2. Why a second parallelization axis

Contingency-level partitioning gives no speed-up when the workload is **skewed toward
operator strategies** rather than contingencies:

- **Few contingencies, many strategies each.** If there are fewer contingencies than
  `threadCount`, spare threads sit idle. In the extreme of **a single contingency with
  many operator strategies**, `Lists2.partition` puts all the work in one partition and
  the analysis is effectively single-threaded no matter how high `threadCount` is set.
- **Unbalanced strategy counts.** Even with several contingencies, one contingency
  carrying a disproportionate number of strategies becomes the long pole; the partition
  holding it dominates wall-clock time.

An operator strategy simulation is, computationally, an independent
"apply actions on the post-contingency state + solve load flow + detect violations".
Given an independent network copy to work on, the strategies of a contingency are
**embarrassingly parallel**. This makes operator-strategy parallelization a natural and
high-value second axis, especially for studies that explore many remedial-action
combinations against a small set of severe contingencies.

## 3. Technical challenges

1. **Network / solver state is single-threaded.** A `runActionSimulation` mutates the
   shared `LfNetwork` (applies actions, re-solves) and relies on `NetworkState.save/restore`
   to serialize strategies. Running strategies concurrently requires each concurrent
   worker to own an **independent `LfNetwork` + load flow `context`** (Jacobian / KLU
   factorization etc. are not thread-safe and are bound to one network).

2. **Reaching the branch point is shared work.** To evaluate any strategy of a
   contingency you must first reach the post-contingency state: pre-contingency LF (once
   per network) → apply contingency → post-contingency LF. That work is common to all
   strategies of the contingency. A naive "clone per strategy" design would redo the
   pre/post-contingency solve for every strategy, which is wasteful when strategies are
   numerous.

3. **Shared mutable instance state on the SA object.** `zeroImpedanceMonitoredIndex` is
   stored as a field on `AbstractSecurityAnalysis` and read during post-contingency /
   action result building. Any intra-analysis parallelism must remove or isolate such
   shared mutable state.

4. **Determinism of results.** The current code guarantees stable ordering via
   partition-indexed slots. Parallel operator strategy execution must likewise write
   results into pre-allocated, index-stable slots so output ordering is reproducible.

5. **Reporting.** `ReportNode` trees are not thread-safe; per-worker report nodes must be
   created and merged, mirroring `mergeReportThreadResults`. The operator-strategy report
   nodes (`Reports.createOperatorStrategySimulation`) currently hang under the
   post-contingency node on the owning thread.

6. **Woodbury DC path.** `WoodburyDcSecurityAnalysis` has its own action-simulation flow
   and would need matching treatment; the fast DC / Woodbury sensitivity reuse assumptions
   must be checked for thread safety before enabling parallelism there.

7. **Thread pool sharing / oversubscription.** Operator-strategy parallelism must share
   the same `Executor` and a single global concurrency budget with contingency-level
   parallelism, otherwise nesting the two axes can oversubscribe CPU
   (`threadCount_contingency × threadCount_strategy`).

## 4. Candidate designs

### Option A — Re-partition on (contingency × strategy) pairs

Change the unit of partitioning from `Contingency` to a `(contingency, operatorStrategy)`
task, and reuse the existing per-partition full-network-clone machinery.

- **Pros:** Maximum reuse of `ContingencyMultiThreadHelper`; balances load across the full
  (contingency × strategy) space in one shot; naturally handles the "1 contingency, many
  strategies" case.
- **Cons:** Each task independently re-runs pre-contingency + contingency +
  post-contingency LF to reach its strategy's branch point → **redundant post-contingency
  solves** proportional to the number of strategies. Acceptable only when a strategy solve
  is expensive relative to the post-contingency solve, or when strategies-per-contingency
  is small. Also multiplies memory (one full network clone per worker) as today.

### Option B — Fork the post-contingency state, fan strategies out (recommended)

Keep contingency handling as-is, but inside `processContingency`, when a contingency has
several strategies, solve the contingency **once** and then distribute the strategies over
a pool of independent post-contingency network copies.

- Solve pre-contingency + contingency + post-contingency LF once.
- Materialize `K = min(#strategies, availableThreads)` independent post-contingency
  `LfNetwork` + `context` copies (via the same variant-clone + `Networks.loadWithReconnectableElements`
  approach used per partition, or a cheaper `NetworkState`-based fork if one can be built).
- Assign strategies round-robin to the copies; each copy runs its assigned strategies
  sequentially with `NetworkState.save/restore` between them (as today), but the copies run
  in parallel.
- Write `OperatorStrategyResult`s into index-stable slots and merge.

- **Pros:** Post-contingency solve is done once; parallelism is applied only to the
  strategy solves, which is the expensive part. Best scaling for the "few contingencies,
  many strategies" target case.
- **Cons:** Needs a mechanism to fork a *post-contingency* network state into K independent
  runnable networks efficiently. Rebuilding K networks and replaying the contingency on
  each is one route (still cheaper than Option A because it is bounded by K, not by
  #strategies). More invasive to `processContingency`.

### Option C — Unified task/work-stealing pool over both axes

Model the whole SA as a set of independent tasks over a shared, bounded thread pool:
contingency tasks that, upon reaching the post-contingency state, spawn strategy sub-tasks
into the same pool. A shared concurrency limit prevents oversubscription; idle threads
steal strategy work from busy contingencies.

- **Pros:** Single tunable degree of parallelism; automatically balances the two axes;
  handles all skew profiles.
- **Cons:** Largest refactor; hardest to keep deterministic ordering and report merging;
  network-copy lifecycle management becomes more dynamic (copies created/destroyed as tasks
  are stolen).

## 5. Recommendation

- **Target the real pain point first:** the "few contingencies (down to one), many
  operator strategies" profile. That points at **Option B** as the primary design — solve
  the contingency once, fan the strategies out across independent post-contingency network
  copies — because it avoids the redundant post-contingency solves of Option A while
  staying far less invasive than Option C.
- **Reuse existing infrastructure:** build the strategy-level fan-out on the same
  variant-clone + `LfNetworkList` + `Executor` foundation as `ContingencyMultiThreadHelper`,
  and reuse the index-stable result-slot and report-merge patterns for determinism.
- **Share one concurrency budget** between contingency-level and strategy-level parallelism
  to avoid CPU oversubscription (a single pool / a single effective degree of parallelism).
- **Introduce a dedicated parameter** rather than overloading `threadCount`, e.g.
  `operatorStrategyThreadCount` (or a single `maxThreadCount` plus an internal scheduler),
  documented alongside `threadCount`, defaulting to `1` (no behavior change).

### Prerequisites / clean-ups before implementation

1. Remove/isolate the shared `zeroImpedanceMonitoredIndex` instance field so per-worker
   state is not shared (challenge #3).
2. Confirm each parallel worker gets its own load flow `context` and `NetworkState`.
3. Extend the deterministic result-slot + report-merge pattern to operator strategy
   results.
4. Decide the Woodbury/DC scope: land AC first, then evaluate `WoodburyDcSecurityAnalysis`.

### Suggested phasing

1. **Phase 0 — Refactor for safety:** eliminate shared mutable SA state; make
   `runActionSimulation` self-contained on an explicit `(network, context, state)` triplet.
2. **Phase 1 — Strategy fan-out (Option B), AC only:** parallelize strategies of a single
   contingency behind a new parameter, default off; add benchmarks for the "1 contingency,
   N strategies" case.
3. **Phase 2 — Unify the concurrency budget** across the two axes to prevent
   oversubscription and expose a single tuning knob.
4. **Phase 3 — Extend to DC / Woodbury** once AC is validated.

## 6. Implementation status (Phase 1)

A first implementation has landed. It follows **Option A** (spread the operator strategies over the existing
per-partition machinery) rather than Option B, because it reuses the fully-tested `ContingencyMultiThreadHelper`
network-cloning / executor / report-merge foundation and introduces **no new intra-network concurrency**: each thread
still owns a single network that it mutates serially, exactly as before. The parallelism comes from different threads
running different operator strategies of the same contingency, each on its own network clone.

What was added:

- A new opt-in parameter `OpenSecurityAnalysisParameters.operatorStrategyParallelization` (default `false`), so the
  historical behaviour is unchanged unless explicitly enabled. It only has an effect when `threadCount > 1`.
- `SecurityAnalysisPartitioner` (`util.mt`), which balances the partitions over the operator strategies instead of over
  the contingencies. The strategies of a contingency can be spread over several partitions; a contingency then appears
  in several partitions, each re-simulating the post-contingency state (the accepted redundant cost of Option A).
- Result merging in `AbstractSecurityAnalysis.runSync` deduplicates the post-contingency results by contingency id when
  balancing is active (operator strategy results stay disjoint), so the merged result is identical to a single-threaded
  run.
- Safety guard: balancing is only applied when **every operator strategy targets a specific contingency**
  (`ContingencyContextType.SPECIFIC`); a strategy with any broader context applies to all contingencies and is
  incompatible with spreading a contingency over several partitions, so the analysis falls back to contingency-level
  parallelization. This is enforced by `SecurityAnalysisPartitioner.canBalanceOperatorStrategies`.

Because the filtering (per-partition operator strategy list) and the post-contingency deduplication happen at the
`runSync` level, the feature works uniformly for the AC, default DC and Woodbury DC paths without touching their
respective `runSimulations` implementations.

Tests: `SecurityAnalysisPartitionerTest` (partitioner behaviour, coverage, fallback) and
`OpenSecurityAnalysisWithActionsTest#testOperatorStrategyParallelization` (parallel results equal the single-threaded
reference for thread counts 2/3/4, including the extreme single-contingency / many-strategies case).

### Composition with the LfNetwork copy (Option B foundation)

The measured few-contingency bottleneck was the serialized network build per partition, not the (warm-started)
post-contingency re-solves. That bottleneck is addressed by the `LfNetworkCopier` work (`NetworkPerThreadMode.COPY`,
default): the network is built once and each thread gets a lock-free deep copy instead of rebuilding from IIDM, and a
`NetworksPresolver` runs the pre-contingency load flow once so the copies skip it. Operator strategy balancing composes
with it directly — the partitioner still decides which contingencies/strategies go to each partition, while the copy
mode decides how each partition's network is provisioned.

Because a copy is much cheaper than a rebuild, the scheduler's per-partition fixed cost is mode dependent:
`PARTITION_FIXED_COST_REBUILD` (6 load flows) for rebuild mode and `PARTITION_FIXED_COST_COPY` (2.5) for copy mode. With
the cheaper copy cost the balancer spreads operator strategies more aggressively — e.g. on PEGASE 13659 with a single
contingency carrying 12 operator strategies, copy mode spreads them over the threads (operator-strategy-parallel beats
contingency-parallel) whereas the rebuild cost would have kept them on one partition.

#### Disjoint partition blocks

The spread partitioner gives each strategy-bearing contingency its own **disjoint block** of partitions, sized by the
largest remainder method proportionally to its operator strategy count (at least one partition, never more than its
strategy count), and distributes that contingency's strategies round-robin over its block. This replaces an earlier
greedy that let every contingency leak into every partition (so a two-contingency workload ended up with both
contingencies — and both their post-contingency solves — on all partitions). With disjoint blocks a contingency is
re-simulated only within its block, keeping redundant post-contingency solves minimal.

The runtime benefit is however strongly hardware dependent. On a machine with only four cores, spreading pays off
clearly when contingency-level parallelization leaves most threads idle (a single, or very few, contingencies with many
operator strategies — the feature's main target), but for a workload that already fills a good fraction of the cores
(e.g. two contingencies on four cores) the extra copies and redundant post-contingency solves can offset the finer
parallelism. The `PARTITION_FIXED_COST_COPY` value is a heuristic tuned to spread the clear wins while staying close to
contingency-level parallelization otherwise; a machine with many more cores than contingencies would benefit from a
lower value.

Still open for later phases: unifying the concurrency budget across both axes, and removing the shared mutable
`zeroImpedanceMonitoredIndex` field.

### Benchmark

`SecurityAnalysisParallelizationBenchmark` (package `com.powsybl.openloadflow.sa.benchmark`) compares the wall-clock
time of a security analysis run single-threaded, parallelized on contingencies only, and parallelized on operator
strategies. It targets a workload skewed towards operator strategies (few contingencies, many strategies each). It is
meant to run on a large network such as PEGASE 13659, which carries no branch current limits, so the benchmark adds a
permanent current limit to every branch (derived from the base-case flow) to give limit-violation detection realistic
work.

The network is not bundled; it is passed via `-Dbenchmark.network=...` (so the benchmark is skipped in CI). Both PowSyBl
native formats and the MATPOWER `.m` text format are accepted — `case13659pegase.m` is downloadable from the MATPOWER
project, and `MatpowerCaseParser` parses it before round-tripping through the official `powsybl-matpower-converter`
importer (added as a test dependency).

```
mvn test -Dtest=SecurityAnalysisParallelizationBenchmark \
    -Dbenchmark.network=/path/to/case13659pegase.m \
    -Dbenchmark.contingencies=1 -Dbenchmark.strategiesPerContingency=32 -Dbenchmark.threads=4
```

Illustrative results on PEGASE 13659 (13659 buses, 20467 branches), 4 cores:

*1 contingency carrying 32 operator strategies — contingency-level parallelization brings nothing:*

| configuration | threads | time | speed-up |
| --- | --- | --- | --- |
| single-threaded | 1 | 9280 ms | x1.00 |
| contingency-parallel | 4 | 9122 ms | x1.02 |
| operator-strategy-parallel | 4 | 7915 ms | x1.17 |

*2 contingencies carrying 16 operator strategies each — contingency-level parallelization already fills the threads:*

| configuration | threads | time | speed-up |
| --- | --- | --- | --- |
| single-threaded | 1 | 9954 ms | x1.00 |
| contingency-parallel | 4 | 6211 ms | x1.60 |
| operator-strategy-parallel | 4 | 6298 ms | x1.58 |

When there is a single contingency, operator strategy parallelization spreads its strategies over the threads and wins;
when there are enough contingencies to fill the threads, it matches contingency-level parallelization instead of
regressing. The speed-up is bounded here by the low core count and by the overheads (one serialized network build and
pre-contingency solve per partition, plus the redundant post-contingency solves when a contingency is spread); it grows
with the number of strategies per contingency and the number of available cores.

#### Build-cost-aware scheduling

An early version always spread a contingency's strategies over the threads, which *regressed* below contingency-level
parallelization when there were already enough contingencies (each extra partition pays a serialized network build plus
a redundant post-contingency solve that outweighed the strategy balancing). The partitioner now builds two candidate
plans — spread, and one-partition-per-contingency — and keeps whichever has the lower estimated makespan under a model
that charges `PARTITION_FIXED_COST` per non-empty partition (its serialized build + pre-contingency solve). Because the
one-partition-per-contingency plan is always a candidate, balancing can never do worse than plain contingency-level
parallelization, while still spreading when a contingency carries enough strategies to justify the extra builds.

Note the measurements confirmed that the redundant post-contingency solves are *not* the dominant overhead (contingency
and action load flows already warm-start via `PreviousValueVoltageInitializer`); the dominant few-contingency cost is
the serialized network build in `ContingencyMultiThreadHelper`. Reducing that (e.g. a cheaper network fork, or
parallelizing the builds) — rather than avoiding the warm re-solves — is the main remaining lever for a future phase.

## 7. Key code references

- `AbstractSecurityAnalysis.runSync` — thread-count branch and contingency partitioning
  (`threadCount == 1` vs `> 1`).
- `AbstractSecurityAnalysis.processContingency` — the sequential operator-strategy loop
  (single vs. multiple strategy paths, `NetworkState.save/restore`).
- `AbstractSecurityAnalysis.runActionSimulation` — a single strategy's action application +
  load flow solve.
- `OperatorStrategies.indexByContingencyId` — mapping contingency → strategies.
- `ContingencyMultiThreadHelper` — per-partition network cloning, executor submission,
  deterministic slots, report merge (the reusable MT foundation).
- `OpenSecurityAnalysisParameters.threadCount` — existing parallelization parameter and the
  place to add a strategy-level counterpart.
- `WoodburyDcSecurityAnalysis` — separate action-simulation path to cover in a later phase.
