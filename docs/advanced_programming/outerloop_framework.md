# The outer-loop framework

The inner numerical solver (AC Newton–Raphson, DC linear solve) finds a steady
state for **fixed** control set-points. The outer loops sit around it and adjust the
*controls* — distribute the slack, move tap positions, switch PV↔PQ at reactive
limits, change shunt sections, hold pilot-point voltages — then ask the engine to
re-solve, repeating until every loop reports `STABLE`. The control mathematics is
[the outer-loops chapter](../loadflow/12_outer_loops.md); how to *configure* the
loop list is [the outer-loops configuration page](outerloop_configuration.md); this
page is the **framework architecture**. All paths are under
`com/powsybl/openloadflow`.

## The SPI

[`OuterLoop<V,E,P,C,O>`](https://github.com/powsybl/powsybl-open-loadflow/blob/main/src/main/java/com/powsybl/openloadflow/lf/outerloop/OuterLoop.java)
is the generic interface (variable/equation enums, parameters, load-flow context,
outer-loop context). Its lifecycle methods:

- `getName()` — a stable id, also the key for the loop's per-type iteration counter;
- `initialize(context)` — build the loop's scratch state and stash it on the context;
- **`check(context, report)`** — the only abstract method: inspect the converged
  state, mutate the model if a control is off-target, and return an
  `OuterLoopStatus` — `STABLE` (nothing to do), `UNSTABLE` (re-solve me) or `FAILED`
  (abort);
- `cleanup(context)` — called in reverse order at the end;
- `isNeeded(context)` — lets the engine drop loops whose controls are absent before
  the run starts.

[`OuterLoopContext`](https://github.com/powsybl/powsybl-open-loadflow/blob/main/src/main/java/com/powsybl/openloadflow/lf/outerloop/OuterLoopContext.java)
carries state *across* re-solves: the shared `LfNetwork`, the iteration counters,
the load-flow context (equation system, Jacobian, target vector) and an opaque
`getData()/setData(...)` slot for the loop's own scratch (e.g.
`IncrementalContextData`). `OuterLoopStatus` is `{STABLE, UNSTABLE, FAILED}` and
`OuterLoopResult` is a `(name, status, statusText)` record.

There are AC and DC specialisations: `AcOuterLoop` adds
`canFixUnrealisticState()` (a loop that may rescue out-of-range voltages, so the
unrealistic-state check is deferred until after it) and `AcOuterLoopContext` carries
the `lastSolverResult`; `DcOuterLoop`/`DcOuterLoopContext` are thin.

## How the engine drives the loops

The loop list is ordered **innermost first**. The engine runs each loop to its own
stabilisation, then sweeps the whole list again until a full sweep triggers no new
inner solve. In [`AcloadFlowEngine`](ac_engine.md):

1. Drop loops where `isNeeded(context)` is false; pair each with a fresh
   `AcOuterLoopContext` and `initialize` it.
2. Initial inner solve; continue only if `CONVERGED`.
3. **Sweep** (outer do-while) over the list. Each loop is driven by `runOuterLoop`,
   an inner do-while that: sets the iteration counters and `lastSolverResult` on the
   context, calls `check`, and — while it returns `UNSTABLE` and the solver still
   converges and the iteration cap is not hit — **re-runs the inner solver warm-
   started** (`PreviousValueVoltageInitializer`). The sweep repeats while it keeps
   producing new inner iterations.
4. `cleanup` in **reverse order**; `maxOuterLoopIterations` (counted as the
   cross-type total) is the global circuit-breaker; the final status is `FAILED` if
   any loop failed, else `STABLE`/`UNSTABLE` depending on the cap.

`DcLoadFlowEngine` follows the same skeleton, except the "solve" is a single
`solveTransposed` of the (cloned) target and slack distribution is done up front
rather than as a loop (see [the DC engine](dc_engine.md)).

## The incremental-sensitivity pattern

Several control loops need the sensitivity of a controlled quantity to a control
variable at the converged point. Rather than finite differences, they exploit the
**already-factorised Jacobian**: put a unit right-hand side on the control
variable's equation (the $\mathbf e_k$ column) and `solveTransposed` once. This is
the same kernel as [sensitivity analysis](sensitivity_analysis.md) and is anchored
in `AbstractIncrementalPhaseControlOuterLoop` (inner `AbstractSensitivityContext`):
it builds a dense RHS with `rad(1°)` on each controller branch's
`BRANCH_TARGET_ALPHA1` column, does one back-solve for all columns, and converts a
target gap into a required phase shift via `term.calculateSensi(...)`, driving
`PiModel.updateTapPositionToReachNewA1(...)`.

Anti-oscillation lives in
[`IncrementalContextData`](https://github.com/powsybl/powsybl-open-loadflow/blob/main/src/main/java/com/powsybl/openloadflow/lf/outerloop/IncrementalContextData.java):
each controller tracks its move direction and, after `MAX_DIRECTION_CHANGE` (3)
reversals, freezes its allowed direction. The same one-back-solve idea recurs in the
incremental transformer- and shunt-voltage loops. Shared behavioural bases include
`AbstractActivePowerDistributionOuterLoop` (slack/balance, exposing
`getDistributedActivePower`), `AbstractAreaInterchangeControlOuterLoop`, and
`AbstractHvdcAcEmulationLimitsOuterLoop`.

## The AC catalogue

The concrete AC loops (`ac/outerloop/`), each with its `getName()`:

| Loop | Role |
|---|---|
| `DistributedSlackOuterLoop` | distribute the slack mismatch over generators/loads |
| `ReactiveLimitsOuterLoop` | PV↔PQ switching when generators hit a reactive limit |
| `SimpleTransformerVoltageControlOuterLoop` / `TransformerVoltageControlOuterLoop` / `IncrementalTransformerVoltageControlOuterLoop` | ratio-tap voltage control (continuous / discrete / incremental) |
| `IncrementalTransformerReactivePowerControlOuterLoop` | transformer reactive-power control |
| `ShuntVoltageControlOuterLoop` / `IncrementalShuntVoltageControlOuterLoop` | shunt-section voltage control |
| `PhaseControlOuterLoop` / `AcIncrementalPhaseControlOuterLoop` | phase-shifter active-power/current control (discrete / sensitivity-based) |
| `SecondaryVoltageControlOuterLoop` | pilot-point (secondary) voltage control |
| `MonitoringVoltageOuterLoop` | SVC standby / voltage-band monitoring |
| `AutomationSystemOuterLoop` | overload-disconnection automation systems |
| `AcHvdcAcEmulationLimitsOuterLoop` / `FreezingHvdcACEmulationOuterloop` | HVDC AC-emulation limits |
| `AcAreaInterchangeControlOuterLoop` | area net-interchange control |

DC supports a smaller set (`dc/`): `DcIncrementalPhaseControlOuterLoop`,
`DcAreaInterchangeControlOuterLoop` (with a no-area fallback) and
`DcHvdcAcEmulationLimitsOuterLoop`; DC slack distribution is done by the engine, not
a loop.

## How a loop mutates the model and signals a re-solve

A loop never touches the equation system directly. In `check` it edits the shared
[`LfNetwork`](lfnetwork.md) objects — e.g.
`LfBus.setGeneratorVoltageControlEnabledAndRecomputeTargetQ(...)` (reactive limits),
`PiModel.updateTapPositionToReachNewA1(...)` (phase/transformer control), or a
generator/load target-P change (distributed slack). Those setters fire
`LfNetworkListener` callbacks, and the registered listener — the
[`AcEquationSystemUpdater`](ac_engine.md#reacting-to-model-changes-acequationsystemupdater) —
activates or deactivates the corresponding equations and terms so the next solve
uses the new control configuration. The loop then **signals the engine purely by
returning `UNSTABLE`** from `check`; the engine re-runs the inner solver. `STABLE`
means no edit was needed; `FAILED` aborts. This is the control half of the
event-driven update model of [the equation framework](equation_framework.md#the-lazy-event-driven-update-model).

## The configuration layer

Which loops end up in the ordered list, and in what order, is decided by an
`OuterLoopConfig`: `DefaultAcOuterLoopConfig`/`DefaultDcOuterLoopConfig` derive the
list automatically from the enabled controls in `OpenLoadFlowParameters`, while
`ExplicitAcOuterLoopConfig`/`ExplicitDcOuterLoopConfig` build it from a user-named
ordered set. The chosen config's output becomes `parameters.getOuterLoops()` — the
list the engine drives above. This is covered in detail on
[the outer-loops configuration page](outerloop_configuration.md).
