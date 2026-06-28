# The network cache

Two unrelated things in Open Load Flow are called a "cache":

- the **intra-solve** caches — the lazy four-state Jacobian cache and the reused LU
  factorisation *within a single run* (see [the equation framework](equation_framework.md)
  and [the Jacobian chapter](../loadflow/07_jacobian.md));
- the **network cache** (`networkCacheEnabled`) — keeping the whole built
  `LfNetwork`, its equation system and its factorised Jacobian alive *between
  successive runs on the same IIDM network*, so a follow-up run after a light change
  reuses everything instead of rebuilding.

This page is a deep look at the second one — its use and its consequences. It is the
mechanism behind fast "what-if" loops (flip a switch, re-run; nudge a target,
re-run), and main recently extended it from AC to DC. All paths are under
`com/powsybl/openloadflow`.

## When it pays off (and when it does not)

The expensive parts of a load flow are *building* the `LfNetwork` and the equation
system from IIDM, and *factorising* the Jacobian. If you run many load flows on the
**same network** with only **small input changes** between them, repeating that work
each time dominates the cost. The network cache amortises it: build once, then each
later run reuses the model and (where the matrix structure is unchanged) the LU
factorisation, re-solving from a warm start.

It is therefore the right tool for: operator "what-if" loops, time-series where only
injections or targets move, and switch-based studies on a fixed topology. It is the
wrong tool for: one-shot runs, workflows that keep changing the *structure* of the
network (each such edit throws the cache away), very large actionable-switch lists
(see [consequences](#consequences)), and multi-variant sweeps (the cache is keyed by
working variant). Security and sensitivity analyses do not use it — they have their
own reuse machinery and run with the cache disabled.

## Enabling it

Two parameters drive it (documented under
[`networkCacheEnabled`](../loadflow/parameters.md#networkcacheenabled) and
[`actionableSwitchesIds`](../loadflow/parameters.md#actionableswitchesids)):

- `networkCacheEnabled` (default `false`) turns the feature on;
- `actionableSwitchesIds` lists the switches you intend to open/close between runs.
  Those switches are kept **retained** in the `LfNetwork` — i.e. modelled as real
  branches that can be toggled without rebuilding — which is what lets a switch
  change be absorbed incrementally.

The user-facing contract is deliberately narrow: a follow-up run reuses the cache
only for *light* modifications (principally voltage-target changes and the open
status of the listed switches); anything else falls back to a full rebuild,
transparently.

## What is cached, and for how long

[`NetworkCache`](https://github.com/powsybl/powsybl-open-loadflow/blob/main/src/main/java/com/powsybl/openloadflow/NetworkCache.java)
is a **process-wide** store, with two singletons — `AC_LF_INSTANCE` and
`DC_LF_INSTANCE` (the DC one is the recent addition). It is keyed by IIDM `Network`
**and working variant**, and holds each network by a **weak reference**, so a cache
entry never keeps a network alive; dead entries are evicted (`evictDeadEntries`,
`getEntryCount()` reports the live count). Access is guarded by a `ReentrantLock`, so
concurrent runs on different networks are safe.

Each `Entry` stores a `Value` — `AcLfValue` wraps the `AcLoadFlowContext`,
`DcLfValue` the `DcLoadFlowContext` — i.e. the built `LfNetwork`, equation system,
target vector and factorised Jacobian. It also remembers the `Input` (`LfInput`,
essentially the `LoadFlowParameters`): on the next run `Input.hasChanged(...)` is
checked first, and **any parameter change evicts the entry** and forces a clean
rebuild.

## How reuse stays correct

The entry can only be reused if the cached model still matches the IIDM network, so
each `Entry` registers itself as an **IIDM `NetworkListener`** and watches every
mutation. The design is *safe by default*: `onUpdate` starts each change as
`unsupportedUpdate` and only downgrades to "handled" for attributes it explicitly
knows how to apply incrementally —

- state write-backs (`v`, `angle`, `p`, `q`, `p1…q3`) are **ignored** (they are Open
  Load Flow's own results, not inputs);
- generator `targetV`/`targetP`, battery `targetP`, shunt `sectionCount`,
  transformer `ratioTapChanger.tapPosition` and `…regulationValue`, and a retained
  switch's `open` flag are **applied incrementally** to the cached model;
- a structural edit (`onCreation`/`afterRemoval`), a property change, a variant
  change, or **any other attribute** triggers `reset(reason)` — the cached values
  are closed, the entry is marked invalid, and the next run rebuilds from scratch.

So an unrecognised or structural modification never produces a stale answer; it
silently downgrades to a normal full run. (The reasons are logged, and an invalidated
entry simply rebuilds next time.) While Open Load Flow writes its results back into
the IIDM network, the listener is **paused** so its own writes — and the ignored
`v/angle/p/q` attributes — do not invalidate the entry.

## The run path

Selection is on `OpenLoadFlowParameters.isNetworkCacheEnabled()` in
`OpenLoadFlowProvider`: AC runs go through
[`AcLoadFlowFromCache`](https://github.com/powsybl/powsybl-open-loadflow/blob/main/src/main/java/com/powsybl/openloadflow/AcLoadFlowFromCache.java),
DC runs through `DcLoadFlowFromCache` (both extending `AbstractLoadFlowFromCache`,
whose `configureTopoConfig` registers the actionable switches as retained). The
run calls `NetworkCache.get(network, input)`, which under the lock either returns an
existing valid entry, evicts-and-rebuilds on a parameter change, or creates a fresh
entry; then, if the entry still holds values, it **restarts** from the previous
state.

The two engines differ in how aggressively they reuse:

- **AC** warm-starts: on reuse, if the previous result converged, the voltage
  initializer is switched to `PreviousValueVoltageInitializer`, so Newton resumes
  from the last solution — typically one or two iterations — and a retained-switch
  toggle is absorbed incrementally through the cache and connectivity machinery,
  without rebuilding.
- **DC** reuses the cached context and factorisation but is more conservative: the
  provider rebuilds when no usable entry exists *or any value is flagged
  `topologyUpdated`* (a switch action), and otherwise re-solves against the reused
  factorisation (DC's `restart()` is a no-op — there is nothing to warm-start in a
  single linear solve).

## Consequences

Turning the cache on is not free, and the trade-offs are the main thing to
understand before using it:

- **Correctness is preserved unconditionally.** Because the listener treats any
  unknown change as `unsupportedUpdate`, the cache can only ever *speed up* a run or
  *fall back* to a full one — it cannot return a result inconsistent with the current
  network. The only behavioural change you may notice is the warm start (AC resumes
  from the previous solution), which can affect which local solution a non-unique
  problem converges to.
- **Retained switches enlarge the system.** Every id in `actionableSwitchesIds` is
  kept as a retained branch, so it stays in the bus/branch model and the Jacobian
  even when closed — a larger, slightly slower factorisation on *every* run. Keep the
  list to the switches you actually toggle; a long list can make the cached runs
  slower than uncached ones.
- **Connectivity falls back to the naive engine.** A cached, actionable-switch
  scenario must be able to *add* edges back (close a switch) and persist topology
  changes across runs, which the default decremental (Even–Shiloach) connectivity
  cannot do — so OLF switches to the naive `GraphConnectivity` implementation (the
  `isNetworkCacheEnabled() && !actionableSwitchesIds.isEmpty()` branch of
  [the connectivity-factory choice](connectivity.md#factories-and-selection)). That
  recomputes components from scratch on each change: another reason the actionable
  set should be small.
- **Memory lives as long as the network.** The cache is a static, process-wide store;
  an entry (with its `LfNetwork`, equation system and factorisation) survives every
  run until the IIDM `Network` is garbage-collected. Long-lived networks therefore
  pin that memory; `getEntryCount()` lets you observe it.
- **It is per variant and per parameter set.** Switching the working variant skips the
  cache, and changing any load-flow parameter evicts the entry. A sweep that varies
  parameters gains nothing.
- **Structural churn defeats it.** If the workflow keeps creating/removing equipment,
  every such edit resets the entry, so you pay the listener overhead *and* the
  rebuild — worse than not caching.

In short: enable it for repeated runs on a stable topology with light, supported
input changes and a short actionable-switch list; leave it off otherwise. It never
trades correctness for speed — only speed for the (small, bounded) costs above.
