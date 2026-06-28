# Connectivity

Many calculations need to know, quickly and repeatedly, **which buses are still
electrically connected** after some branches are opened: a contingency may split
the network into islands, a remedial action may reconnect one, slack distribution
must stay within a component, and the fast-DC Woodbury update must detect when an
outage breaks connectivity. The `graph` package provides this as a small SPI with
several interchangeable algorithms. All paths are under `com/powsybl/openloadflow`.

In Open Load Flow the graph **is** the [`LfNetwork`](lfnetwork.md): vertices are
`LfBus`, edges are `LfBranch`, so everything is a
`GraphConnectivity<LfBus, LfBranch>`.

## The SPI

[`graph/GraphConnectivity`](https://github.com/powsybl/powsybl-open-loadflow/blob/main/src/main/java/com/powsybl/openloadflow/graph/GraphConnectivity.java)
is an incremental/decremental connectivity engine with an **undoable
"temporary changes" transaction** at its core. Its operations fall into three
groups:

- **Graph mutation** — `addVertex`, `addEdge`, `removeEdge`. The contingency path is
  decremental: it `removeEdge`s the branches it opens.
- **The temporary-change transaction** — `startTemporaryChanges()` opens a journaled
  level; every mutation after it is recorded; `undoTemporaryChanges()` replays those
  mutations' `undo` in reverse and restores the prior state exactly. This is what
  lets *one* base graph serve an entire contingency list with no rebuild.
- **Component queries** (valid inside an open transaction) — `getComponentNumber(v)`
  (0 = the main component, larger = progressively smaller islands),
  `getNbConnectedComponents()`, `getConnectedComponent(v)`,
  `getLargestConnectedComponent()`, and `setMainComponentVertex(v)` to pin which
  component is "main".
- **Delta accessors** — the payload a contingency actually consumes, relative to the
  main component: `getVerticesRemovedFromMainComponent()` /
  `getEdgesRemovedFromMainComponent()` (what islanded) and
  `getVerticesAddedToMainComponent()` / `getEdgesAddedToMainComponent()` (what a
  closing action reconnected).

## Implementations

[`AbstractGraphConnectivity`](https://github.com/powsybl/powsybl-open-loadflow/blob/main/src/main/java/com/powsybl/openloadflow/graph/AbstractGraphConnectivity.java)
provides the common machinery: it holds the graph and a stack of
`ModificationsContext` (one per open temporary-changes level), applies each mutation
as a journaled `GraphModification` (`VertexAdd`/`EdgeAdd`/`EdgeRemove`, each with
`apply`/`undo`), and caches the component sets. Three concrete strategies trade off
differently:

| Implementation | How it works | Good for |
|---|---|---|
| [`EvenShiloachGraphDecrementalConnectivity`](https://github.com/powsybl/powsybl-open-loadflow/blob/main/src/main/java/com/powsybl/openloadflow/graph/EvenShiloachGraphDecrementalConnectivity.java) (**default**) | Even–Shiloach decremental algorithm over a BFS level structure; each `removeEdge` runs cooperating split-detection and re-levelling passes, whichever halts first wins | many edge removals on a stable base graph (contingencies); cheap per cut |
| `MinimumSpanningTreeGraphConnectivity` | maintains a Kruskal spanning forest via union–find; a tree-edge cut invalidates and recomputes the forest | workloads that **add** edges/vertices or need **nested** transactions |
| `NaiveGraphConnectivity` | recomputes connected components from scratch on any change | maximal robustness; structurally changing topology |

The Even–Shiloach default is fast for the dominant case (open branches against a
single-component base) but has constraints: **edge/vertex additions are unsupported
once a transaction is open**, only **one** (non-nested) level is allowed, and the
initial graph must be a single connected component. When those constraints can't
hold, OLF falls back to the naive implementation.

## Factories and selection

Each implementation has a `GraphConnectivityFactory`
([`graph/GraphConnectivityFactory`](https://github.com/powsybl/powsybl-open-loadflow/blob/main/src/main/java/com/powsybl/openloadflow/graph/GraphConnectivityFactory.java)).
The choice is made in `OpenLoadFlowParameters.getConnectivityFactory(...)`: the
`NaiveGraphConnectivityFactory` (keyed on `LfBus::getNum`) is used when the scenario
needs edge additions, nesting or permanent topology changes — i.e. **actionable
switches with the [network cache](network_cache.md) enabled, or automation-system
simulation** — and the
`EvenShiloachGraphDecrementalConnectivityFactory` is the default otherwise. The
chosen factory is stored on `LfNetworkParameters` and threaded into the network
loader.

## How `LfNetwork` exposes it

`LfNetwork.getConnectivity()` builds the connectivity lazily on first use:
`factory.create()`, then `addVertex` for every bus and `addEdge` for every branch
connected on both sides, and `setMainComponentVertex(slackBus)`. Implementations
that support nesting (naive/MST) open one base transaction that is never reverted,
so outer loops (e.g. automation systems) can commit *permanent* topology changes;
Even–Shiloach skips that step.

## The temporary-change pattern (a contingency)

Each contingency is evaluated transactionally against the single base graph:

```java
GraphConnectivity<LfBus, LfBranch> c = network.getConnectivity();
c.startTemporaryChanges();                       // open a journaled level
try {
    branchesToOpen.forEach(c::removeEdge);       // cut the contingency's branches
    int created   = c.getNbConnectedComponents() - 1;        // new islands
    Set<LfBus> lost = c.getVerticesRemovedFromMainComponent(); // islanded buses
} finally {
    c.undoTemporaryChanges();                    // restore the base graph exactly
}
```

`startTemporaryChanges` snapshots which vertices were outside the main component;
each `removeEdge` is journaled and fed to the algorithm; the delta getters diff
main-component membership before/after; `undoTemporaryChanges` resets the
algorithm's state and replays the undos in reverse — leaving the base graph pristine
for the next contingency.

## Where it is used

- **Contingencies** — `PropagatedContingency` cuts the opened branches, optionally
  relocates an isolated slack, and reads the new component count and the islanded
  buses (used e.g. to zero HVDC flows). See [security analysis](security_analysis.md).
- **Fast-DC Woodbury** — `dc/fastdc/ConnectivityBreakAnalysis` decides which outages
  actually break connectivity (comparing the two end-buses' component numbers) and
  computes the minimal set of branches to reconnect; this is what splits the cheap
  low-rank update path from the islanding path (see [the DC engine](dc_engine.md)).
- **Remedial actions** — branch open/close actions read both the removed and the
  *added* deltas to know what an action reconnected.
- **Slack distribution and transformer-control fixing after a split**, and
  **GLSK rescaling** in [sensitivity analysis](sensitivity_analysis.md) (dropping
  participants that left the main component and renormalising), both query the
  per-component membership.

The mathematics of islanding detection in the DC/Woodbury setting is
[the connectivity-loss section](../sensitivity/06_contingency_woodbury.md#detecting-and-handling-connectivity-loss)
of the sensitivity volume.
