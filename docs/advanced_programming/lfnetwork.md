# The LfNetwork model

Open Load Flow does not compute on the IIDM grid model directly. It first reads the
IIDM `Network` **once** into a lightweight, per-unit numeric model — the
`LfNetwork` — and from then on every solver, equation and outer loop operates only
on that model; results are written back to IIDM at the end. This decoupling is what
lets the solvers be fast (plain `double`s, dense element numbering) and
independent of the input format.

All paths below are under `com/powsybl/openloadflow`.

## `LfNetwork` and its synchronous networks

[`LfNetwork`](https://github.com/powsybl/powsybl-open-loadflow/blob/main/src/main/java/com/powsybl/openloadflow/network/LfNetwork.java)
represents **one connected component**, identified by `numCC`. Loading an IIDM
network yields a *list* of `LfNetwork`s — one per connected component — sorted by
ascending component number (the main component first), each solved independently
(see also [security analysis on multi-component networks](../security/17_security_overview.md#multi-component-networks)).

A connected component may itself span several **synchronous components** — for
example an AC–DC network whose DC links tie otherwise-asynchronous areas into one
connected component (the relaxed [`acDcNetwork`](../loadflow/parameters.md#acdcnetwork)
case). Each synchronous component is modelled by an
[`LfSynchronousNetwork`](https://github.com/powsybl/powsybl-open-loadflow/blob/main/src/main/java/com/powsybl/openloadflow/network/LfSynchronousNetwork.java)
held by the `LfNetwork` (`getSynchronousNetworks()` / `getSynchronousNetwork(numSC)`),
created lazily as the buses of a new synchronous component are added. Because the
voltage-angle reference is per synchronous *area*, the **slack buses, the reference
bus and the reference generator live on the `LfSynchronousNetwork`, not on the
`LfNetwork`** — as does the per-area validity check (`validateBuses`);
`LfSynchronousNetwork.getBuses()` is a view of the `LfNetwork`'s buses filtered to
that synchronous component. `LfNetwork.updateSlackBusesAndReferenceBus()` simply
drives each synchronous network's selection, and the first slack bus of the *first*
synchronous network is pinned as the connectivity graph's main vertex. `getId()`
reads e.g. `"{CC0 SC0}"`, or `"{CC0 SC0,1}"` when one component spans several
synchronous areas.

Each `LfNetwork` holds its elements both as **ordered lists indexed by element
number** (`busesByIndex`, `branches`, …) and as **id maps** (`busesById`, …), plus
the per-component solver context: graph connectivity, zero-impedance subnetworks,
and listeners — all computed lazily and invalidated on topology change. (Slack and
reference selection is per synchronous network, as above.)

## Loading: IIDM → `List<LfNetwork>`

The conversion is an SPI,
[`LfNetworkLoader`](https://github.com/powsybl/powsybl-open-loadflow/blob/main/src/main/java/com/powsybl/openloadflow/network/LfNetworkLoader.java),
whose IIDM implementation is
[`LfNetworkLoaderImpl`](https://github.com/powsybl/powsybl-open-loadflow/blob/main/src/main/java/com/powsybl/openloadflow/network/impl/LfNetworkLoaderImpl.java).
It groups IIDM buses by connected component — and, within each, by synchronous
component — and builds one `LfNetwork` per connected component, calling `createAc(...)`
once per synchronous component to populate it, in this order: buses → branches (lines,
two-/three-winding transformers, tie lines, dangling lines, HVDC) → areas →
voltage/reactive/transformer/shunt controls → switches (only if breaker topology is
requested) → secondary voltage controls → automation systems → the
post-processor callback. Loading is driven by
[`LfNetworkParameters`](https://github.com/powsybl/powsybl-open-loadflow/blob/main/src/main/java/com/powsybl/openloadflow/network/LfNetworkParameters.java)
(slack selection mode, AC/DC model, breakers, impedance thresholds, asymmetrical,
connectivity factory, …) and reports loading quality through
`LfNetworkLoadingReport`.

### Element numbering

`addBus`/`addBranch`/… assign `num = list.size()` then append, so **`getNum()` is a
dense 0-based index into the per-type list**. The *same* number indexes the parallel
arrays of the [network vectors](#network-vectors-struct-of-arrays) and is the
`elementNum` of the [equation-system](equation_framework.md) variables and
equations. One consequence to keep in mind: the number is a position, **not a stable
identity** — `removeBranch` renumbers the remaining branches to keep the indices
contiguous.

## The element model

[`LfElement`](https://github.com/powsybl/powsybl-open-loadflow/blob/main/src/main/java/com/powsybl/openloadflow/network/LfElement.java)
is the base of everything: `getId()`, `getType()` → `ElementType`
(`BUS`, `BRANCH`, `SHUNT_COMPENSATOR`, `HVDC`, `AREA`, `DC_LINE`, `DC_BUS`,
`CONVERTER`), `getNum()`, and `isDisabled()/setDisabled()` (which fires a listener
event). The main element types:

- **`LfBus`** (`LfBusImpl`, `LfStarBus`, …) — a node: `getV()`/`getAngle()` (state),
  its generators/loads/shunts, its voltage controls, and `updateState(...)`.
- **`LfBranch`** (`LfBranchImpl`, `LfLegBranch` for 3-winding, `LfTieLineBranch`,
  `LfSwitch`, …) — an edge: its `PiModel`, the two end buses, phase/voltage/
  reactive-power control, and the flow accessors `getP1/Q1/I1/…` exposed as
  `Evaluable`s pointing at equation terms.
- **`LfGenerator`** (`LfGeneratorImpl`, `LfStaticVarCompensatorImpl`,
  `LfVscConverterStationImpl`, …), **`LfShunt`**, **`LfLoad`** (with optional
  `LfLoadModel`), **`LfHvdc`** (with AC-emulation control), **`LfArea`**.

### The π-model

[`PiModel`](https://github.com/powsybl/powsybl-open-loadflow/blob/main/src/main/java/com/powsybl/openloadflow/network/PiModel.java)
is the branch's electrical model in per-unit ($r,x,g_1,b_1,g_2,b_2,\rho_1,\alpha_1$,
with $\rho_2=1,\alpha_2=0$), plus tap manipulation. Two implementations:

- `SimplePiModel` — one fixed set of values, no taps (used for lines and fixed
  branches);
- `PiModelArray` — a list of per-tap models with a current tap index, implementing
  real tap changing for transformers and phase shifters.

(The mathematics of this model is [the branch π-model](../loadflow/03_network_model.md).)

## Per-unit

[`util/PerUnit.java`](https://github.com/powsybl/powsybl-open-loadflow/blob/main/src/main/java/com/powsybl/openloadflow/util/PerUnit.java)
fixes the base ($S_{\mathrm B}=100$ MVA, $z_b=V_{\text{nom}}^2/S_{\mathrm B}$).
**Conversion happens at load time, inside the element impls**: impedances are
divided by $z_b$, powers by $S_{\mathrm B}$, etc. The whole `LfNetwork` — targets,
voltages, angles, flows — is therefore in per-unit; reporting and `updateState`
multiply back by $S_{\mathrm B}$ when writing to IIDM. (See
[electrical fundamentals](../loadflow/02_fundamentals.md).)

## Network vectors (struct-of-arrays)

To feed the vectorised AC equations with cache-friendly memory, the branch/bus data
*and* the Jacobian terms are also stored as **parallel primitive arrays indexed by
element number**, in `ac/equations/vector/`:

- `AcBusVector` — `v[]`, `ph[]`, `disabled[]`, and the equation-row indices
  `vRow[]`, `phRow[]`;
- `AcBranchVector` — topology (`bus1Num[]`, `connected1/2[]`, …), the constant
  π-model data (`y, ksi, g1, b1, r1, a1, …`), the row indices, and the **computed
  flows and all their partial derivatives** (`p1, q1, …, dp1dv1, …`);
- `AcNetworkVector` — owns both and ties them to the equation system. It is an
  `LfNetworkListener` *and* a `StateVectorListener`: `onStateUpdate` recomputes all
  flows/derivatives in tight loops (the per-iteration hot path), while listener
  callbacks (`onDisableChange`, `onTapPositionChange`, …) keep the arrays coherent
  with the object model.

This duplicates data already on the objects, purely for locality in the
mismatch/Jacobian loops; it is what the [equation arrays](equation_array.md) page
calls the evaluator's "struct-of-arrays" backing.

## Listeners and in-place mutation

During outer loops and contingencies the model is **edited in place** rather than
rebuilt. [`LfNetworkListener`](https://github.com/powsybl/powsybl-open-loadflow/blob/main/src/main/java/com/powsybl/openloadflow/network/LfNetworkListener.java)
is the observer for those edits: control changes, target changes,
`onDisableChange`, `onBranchConnectionStatusChange`, `onTapPositionChange`,
`onShuntSusceptanceChange`, zero-impedance split/merge, etc. Its consumers are the
equation-system updaters (`AcEquationSystemUpdater`/`DcEquationSystemUpdater`,
which rewire/invalidate equations) and the `AcNetworkVector` (which refreshes its
arrays). This is the mechanism by which a contingency or an outer-loop action
invalidates exactly the affected part of the equation system without reloading the
network.

## Loader post-processor

[`LfNetworkLoaderPostProcessor`](https://github.com/powsybl/powsybl-open-loadflow/blob/main/src/main/java/com/powsybl/openloadflow/network/LfNetworkLoaderPostProcessor.java)
is a `ServiceLoader`-discovered SPI with hooks (`onBusAdded`, `onBranchAdded`,
`onInjectionAdded`, `onLfNetworkLoaded`) invoked during loading — the entry point
for attaching custom data to the model. It has its own
[dedicated page](lfnetwork_loader_postprocessor.md).

## Downstream and state management

`LfNetwork` is the single source of structure for everything downstream: the
[equation system](equation_framework.md) is generated from it (variables/equations
keyed by `(ElementType, num)`), the target vectors read targets from its
buses/branches, and the solvers iterate the state vector — each iteration pushing
state into the network vectors. After convergence,
`LfNetwork.updateState(...)` writes V/angle/flows/taps/controls back to IIDM. For
security analysis, `NetworkState.save()/restore()` snapshots and rolls back the
`LfNetwork` around each contingency.

## Things to remember

- Element `num` is a **mutable dense index, not an identity**; the same index
  addresses the object lists, the vector arrays, and the equation variables.
- Slack/reference selection is **per synchronous network** (`LfSynchronousNetwork`),
  while connectivity and zero-impedance subnetworks are per connected component
  (`LfNetwork`); all are **lazily computed** and invalidated on topology mutation.
- Validity is checked per synchronous component: one with no generator is marked
  invalid and silently dropped; AC additionally requires at least one
  voltage-controlling bus.
