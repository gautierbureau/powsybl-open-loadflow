# The AC load flow engine

The `ac` package solves the full nonlinear AC power flow: an **inner numerical
solver** drives the mismatch $\mathbf g=\mathbf f(\mathbf x)-\mathbf t$ to zero, and
an **outer-loop framework** wrapped around it enforces the engineering controls
(distributed slack, voltage/phase/reactive controls, limits). The mathematics is
the [AC load flow modeling reference](../loadflow/01_introduction.md); this page is
the code-level map. All paths are under `com/powsybl/openloadflow`.

## Entry and orchestration

| Class | Role |
|---|---|
| [`AcloadFlowEngine`](https://github.com/powsybl/powsybl-open-loadflow/blob/main/src/main/java/com/powsybl/openloadflow/ac/AcloadFlowEngine.java) | Orchestrates the whole AC solve for one `LfNetwork`: init state → inner solve → drive outer loops → build result. |
| `AcLoadFlowContext` | Per-network holder; lazily builds and caches the `EquationSystem`, `AcJacobianMatrix`, `AcTargetVector`, `EquationVector`. `AutoCloseable`. |
| `AcLoadFlowParameters` | All AC configuration (solver factory, equation-creation params, outer loops, voltage initializer, realistic-voltage bounds, `vectorized`, `asymmetrical`, …). |
| `AcLoadFlowResult` | Solver iteration count, `AcSolverStatus`, `OuterLoopResult`, slack mismatch, distributed power; `isSuccess()` = `CONVERGED` **and** outer loops `STABLE`. |
| `OpenLoadFlowProvider` | The public PowSyBl SPI entry; builds the parameters via `OpenLoadFlowParameters.createAcParameters(...)`, loads the `LfNetwork`s and calls `AcloadFlowEngine.run(...)`. |

### `run()` flow

For each valid `LfNetwork` (wrapped in an `AcLoadFlowContext`):

1. `voltageInitializer.prepare(...)` — seed the starting voltages. A *DC* initializer
   builds a temporary DC system here, so it runs **before** the AC equation system
   is created (see the [DC engine](dc_engine.md)).
2. Guard: if no generator-voltage-controlled bus exists, return `SOLVER_FAILED`.
3. `solverFactory.create(...)` — this lazily triggers creation of the equation
   system, Jacobian and target vector through the context getters.
4. Select the active outer loops (`outerLoop.isNeeded(context)`), build one
   `AcOuterLoopContext` each, and `initialize` them.
5. **Initial inner solve** (`solver.run(...)`); on `CONVERGED`, optionally reject an
   *unrealistic* state (a bus voltage outside `[minRealisticVoltage,
   maxRealisticVoltage]`, default `[0.5, 2.0]` p.u.) as `UNREALISTIC_STATE`.
6. **Outer-loop driving loop**: iterate the ordered loops (innermost first); while a
   loop reports `UNSTABLE`, re-run the inner solver warm-started from the previous
   solution (`PreviousValueVoltageInitializer`), until all loops are stable, a
   failure occurs, or `maxOuterLoopIterations` is reached.
7. Finalize (loops `cleanup()` in reverse), extract the distributed active power,
   and build the `AcLoadFlowResult`.

## Building the equation system

`AcLoadFlowContext` chooses the creator (all under `ac/equations/`):

- `asymmetrical` → `AsymmetricalAcEquationSystemCreator` (adds the zero- and
  negative-sequence Fortescue equations; see
  [the unbalanced model](../loadflow/24_symmetrical_components.md));
- else `vectorized` (default `true`) → `AcVectorizedEquationSystemCreator`;
- else → `AcEquationSystemCreator` (scalar).

[`AcEquationSystemCreator`](https://github.com/powsybl/powsybl-open-loadflow/blob/main/src/main/java/com/powsybl/openloadflow/ac/equations/AcEquationSystemCreator.java)
is the base: it builds the bus active/reactive balance equations from the per-side
branch-flow terms, the control equations (`DISTR_Q`, `DISTR_RHO`, `DISTR_SHUNT_B`,
phase/voltage targets), the zero-impedance `ZERO_V`/`ZERO_PHI` coupling with dummy
P/Q variables, the AC-DC and HVDC AC-emulation terms, then attaches the
`AcEquationSystemUpdater` as a network listener. The vectorized creator extends it
and replaces only the four closed-branch bus-injection contributions (P1/P2/Q1/Q2)
with `EquationTermArray`s backed by the `AcNetworkVector` struct-of-arrays — see
[equation arrays](equation_array.md); everything else falls through to scalar.

The variable and equation kinds are the enums `AcVariableType` (`BUS_V`, `BUS_PHI`,
`SHUNT_B`, `BRANCH_RHO1`, the sequence/DC-converter variants, …) and
`AcEquationType` (`BUS_TARGET_P/Q/V/PHI`, `DISTR_*`, `ZERO_*`, …), as described in
[the equation framework](equation_framework.md#defining-a-problems-equation-types).

## The solver SPI and the concrete solvers

The inner solver is an SPI, not a fixed enum — solvers are discovered by
**name** through `ServiceLoader`:

| Class | Role |
|---|---|
| `ac/solver/AcSolver` | Interface: `getName()`, `AcSolverResult run(VoltageInitializer, ReportNode)`. |
| `ac/solver/AbstractAcSolver` | Base: holds the network, equation system, Jacobian `j`, target and equation vectors, and mismatch-reporting helpers. |
| `ac/solver/AcSolverFactory` | `@AutoService` factory SPI: `findAll()`/`find(name)`, `createParameters(...)`, `create(...)`. |
| `NewtonRaphson` / `NewtonRaphsonFactory` | Default, name `NEWTON_RAPHSON`. |
| `NewtonKrylov` / `NewtonKrylovFactory` | Jacobian-free (KINSOL), name `NEWTON_KRYLOV`. |
| `FastDecoupled` / `FastDecoupledFactory` | Decoupled Pθ/QV, name `FAST_DECOUPLED`; rejects asymmetrical, HVDC AC-emulation and AC-DC networks. |

Selection is by the string `OpenLoadFlowParameters.acSolverType` (default
`NEWTON_RAPHSON`), resolved to a factory in `createAcParameters`. `AcSolverStatus`
is `CONVERGED`, `MAX_ITERATION_REACHED`, `SOLVER_FAILED`, `NO_CALCULATION`, or
`UNREALISTIC_STATE`.

**The Newton–Raphson iteration** (`NewtonRaphson.runIteration`) is the literal code
form of [the Newton–Raphson chapter](../loadflow/06_newton_raphson.md):

```
initStateVector(...)                     seed x
loop until maxIterations:
  j.solveTransposed(equationVector)      solve J·dx = f(x); dx overwrites the array
  svScaling.apply(dx, ...)               step scaling (see below)
  stateVector.minus(dx)                  x ← x − dx  (f(x) auto-recomputed)
  equationVector.minus(targetVector)     mismatch g = f − t
  if stoppingCriteria.test(g): break     converged
on CONVERGED: AcSolverUtil.updateNetwork(...)   write x back into the LfNetwork
```

(`AcSolverUtil` provides `initStateVector` and `updateNetwork`.) Two pluggable
pieces tune convergence, matching [convergence control](../loadflow/11_stopping_scaling.md):

- **Stopping criteria** (`NewtonRaphsonStoppingCriteria`): `Default…` (one uniform
  per-equation tolerance, default $10^{-4}$ p.u.) or `PerEquationType…` (a tolerance
  per physical type), chosen by `NewtonRaphsonStoppingCriteriaType`.
- **State-vector scaling** (`StateVectorScaling`, mode `StateVectorScalingMode`):
  `NONE`, `LINE_SEARCH`, or `MAX_VOLTAGE_CHANGE`, applied before (`apply`) and after
  (`applyAfter`) the mismatch recompute.

`NewtonKrylov` instead hands a residual callback and a Jacobian-update callback to
KINSOL; `FastDecoupled` partitions the index into Pθ and QV subsystems
(`updateWithSeparation`), keeps two constant `JacobianMatrixFastDecoupled`, and
solves each per iteration with max-voltage-change clamping plus line search.

## Inner ↔ outer loops

`AcloadFlowEngine` owns one inner `AcSolver` and the ordered list of `AcOuterLoop`s
(innermost first). After the initial solve, each outer loop's `check(...)` returns
an `OuterLoopStatus`; while any returns `UNSTABLE` the engine re-runs the *same*
inner solver (warm-started), bounded by `maxOuterLoopIterations`. The outer-loop
framework — the loop SPI, contexts, and the individual control loops in
`ac/outerloop/` — is the subject of [the outer-loop framework page](outerloop_framework.md)
and the [outer loops modeling chapter](../loadflow/12_outer_loops.md).

## Reacting to model changes: `AcEquationSystemUpdater`

[`AcEquationSystemUpdater`](https://github.com/powsybl/powsybl-open-loadflow/blob/main/src/main/java/com/powsybl/openloadflow/ac/equations/AcEquationSystemUpdater.java)
is registered as an `LfNetwork` listener at the end of equation-system creation.
When an outer loop or a contingency edits the model in place, it
activates/deactivates (or recreates) only the affected equations rather than
rebuilding the system: control toggles flip `BUS_TARGET_V`/`DISTR_*` against the
control equations; `onDisableChange` activates/deactivates the element's equations;
`onBranchConnectionStatusChange` swaps a branch's effective flow terms between
closed-side, open-side and zero; `onZeroImpedanceNetworkSplit/Merge` recreates the
distribution equations; `onTapPositionChange` refreshes the branch coefficients
(unnecessary in vectorized mode, where the `AcNetworkVector` keeps them current).
This is the engine-side half of the event-driven update model described in
[the equation framework](equation_framework.md#the-lazy-event-driven-update-model).
