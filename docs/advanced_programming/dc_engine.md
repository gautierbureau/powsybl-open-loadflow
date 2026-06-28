# The DC load flow engine

The `dc` package solves the linearised model $\mathbf B'\,\boldsymbol\varphi=\mathbf P$
as a **single linear solve** — no Newton iteration. Because the DC branch
derivatives are constant, the "Jacobian" *is* the constant $\mathbf B'$ matrix: it
is factorised once and that LU is reused for every subsequent solve (outer-loop
iterations, contingencies, sensitivities). The mathematics is
[the DC load flow chapter](../loadflow/15_dc_model.md); this is the code map. All
paths are under `com/powsybl/openloadflow`.

## Entry and orchestration

| Class | Role |
|---|---|
| [`DcLoadFlowEngine`](https://github.com/powsybl/powsybl-open-loadflow/blob/main/src/main/java/com/powsybl/openloadflow/dc/DcLoadFlowEngine.java) | Orchestrator: slack distribution, the single linear solve, the outer loops. |
| `DcLoadFlowContext` | Lazy holder of the `EquationSystem`, the `JacobianMatrix` (= $\mathbf B'$) and the `DcTargetVector`; built once and reused. |
| `DcLoadFlowParameters` | `distributedSlack`, `balanceType`, `outerLoops`, `maxOuterLoopIterations`, mismatch tolerances, the matrix factory, the DC equation-creation parameters. |
| `DcLoadFlowResult` | `solverSuccess`, `OuterLoopResult`, slack mismatch, distributed power; `isSuccess()` = solve succeeded **and** outer loops `STABLE`. |

### The single solve

`DcLoadFlowContext.getJacobianMatrix()` builds one `JacobianMatrix` from the
equation system; for DC every branch term has a *constant* derivative, so this is
the constant $\mathbf B'$. Its LU is computed once and cached
([the native math layer](../loadflow/10_linear_solver.md)). `DcLoadFlowEngine.solve(target, jacobianMatrix, …)`
calls `jacobianMatrix.solveTransposed(target)` — the system is stored transposed,
and the `target` array is overwritten **in place** with the solution
$\boldsymbol\varphi$ (callers `clone()` it first to keep it reusable).

### `run()` flow

1. Filter outer loops by `isNeeded(context)`, create a `DcOuterLoopContext` each,
   `initialize`.
2. Seed the state vector with a uniform initializer (BUS_PHI / BRANCH_ALPHA1 /
   DUMMY_P).
3. Compute the initial slack-bus active-power mismatch.
4. **Slack distribution** up front, if enabled (next section).
5. The **single required solve**: `clone()` the target, `solve(...)`, push the
   solution into the state vector, and `updateNetwork(...)` (write $\varphi$ into
   `LfBus.angle`, $\alpha_1$ into the π-models).
6. **Outer-loop loop**: each `runOuterLoop` repeatedly calls `outerLoop.check(...)`;
   on `UNSTABLE` it **re-solves the cloned target with the same cached LU** and
   updates the network — bounded by `maxOuterLoopIterations`.
7. Finalize, accumulate the distributed power, build the result.

## The equation system

DC reuses the generic [equation framework](equation_framework.md) unchanged; only
the term derivatives differ.
[`DcEquationSystemCreator`](https://github.com/powsybl/powsybl-open-loadflow/blob/main/src/main/java/com/powsybl/openloadflow/dc/equations/DcEquationSystemCreator.java)
builds the buses, branches and HVDCs. The enums are `DcVariableType` (`BUS_PHI`,
`BRANCH_ALPHA1`, `DUMMY_P`) and `DcEquationType` (`BUS_TARGET_P`, `BUS_TARGET_PHI`
the angle reference, `BRANCH_TARGET_ALPHA1`, `ZERO_PHI`, `DUMMY_TARGET_P`).

The branch flow terms (`AbstractClosedBranchDcFlowEquationTerm.computePower()`) give
the constant $\beta=b\,(\rho_1 R_2)$ with $b=1/x$ (`IGNORE_R`, the default) or
$b=x/(r^2+x^2)$ (`IGNORE_G`); `eval = -\beta(\varphi_2-\varphi_1+A_2-\alpha_1)`,
`der = \pm\beta` — constant, hence the constant $\mathbf B'$. Zero-impedance
branches are replaced by a `ZERO_PHI` equation ($\varphi_1=\varphi_2$) plus a
`DUMMY_P` injection on each side and an inactive `DUMMY_TARGET_P` equation that
activates if the branch opens, keeping the system square.
`DcEquationSystemUpdater` keeps all this consistent on topology changes (a switch
open/close toggles `ZERO_PHI` ↔ `DUMMY_TARGET_P`), `DcTargetVector` builds the
right-hand-side $\mathbf P$.

## Outer loops and slack distribution

DC supports a **limited** set of outer loops — HVDC AC-emulation limits, incremental
phase control, and area interchange control (`DcAreaInterchangeControlOuterLoop`,
`DcIncrementalPhaseControlOuterLoop`, `DcHvdcAcEmulationLimitsOuterLoop`), wired by
`lf/outerloop/config/DefaultDcOuterLoopConfig`.

**Slack distribution in DC is not an outer loop.** It runs once, up front, inside
`run()`: `DcLoadFlowEngine.distributeSlack(...)` delegates to
`util/ActivePowerDistribution`, modifying generator/load targets, which feeds the
right-hand-side $\mathbf P$ *before* the single solve (respecting
`SlackDistributionFailureBehavior`). The area-interchange case instead leaves the
residue on the slack and lets its outer loop resolve it (see
[area interchange control](../loadflow/12_outer_loops.md)).

## Fast DC and the Woodbury update

For fast DC security analysis and DC sensitivity, the base $\mathbf B'$ is factorised
**once** and each contingency or action is treated as a low-rank ($\pm1$)
modification, resolved by a **Sherman–Morrison–Woodbury** update instead of a
refactorisation — the mathematics is
[the Woodbury contingency chapter](../sensitivity/06_contingency_woodbury.md). The
machinery lives in `dc/fastdc/`:

| Class | Role |
|---|---|
| `ComputedElement` (+ `AbstractComputedElement`) | A branch-level modification; statics build the $\pm1$ RHS and `calculateElementsStates(...)` solves it against the cached LU to get the base-case sensitivity columns. |
| `ComputedContingencyElement`, `ComputedSwitchBranchElement`, `ComputedTapPositionChangeElement` | The concrete modifications (branch outage, switch action, tap action). |
| `ConnectivityBreakAnalysis` | Pre-classifies contingencies into connectivity-breaking vs not — a cheap sensitivity criterion ($\sum\lvert\text{sensi}\rvert\approx1$) confirmed by a [graph](connectivity.md) check; returns the base $\pm1$ states and the disabled buses/branches. |
| `WoodburyEngine` | The low-rank updater: `setAlphas(...)` computes the flow-transfer factors (a scalar for one element, a tiny dense LU for several), and `toPostContingency…States(...)` returns `pre-state + Σ αᵢ · stateColumnᵢ`. |

The callers are `sa/WoodburyDcSecurityAnalysis` ([security analysis](security_analysis.md))
and `sensi/DcSensitivityAnalysis` ([sensitivity analysis](sensitivity_analysis.md)).

## Relationship to AC

- **DC initialises AC.** `DcValueVoltageInitializer` runs a private DC solve to warm
  start the AC angles (the `voltageInitMode` based on DC values), saving/restoring
  bus state so the init does not perturb the AC start.
- **Shared framework.** DC and AC use the *same* `EquationSystem`/`EquationTerm`/
  `Variable`/`TargetVector`/`JacobianMatrix` and the same matrix layer,
  parameterised by the DC vs AC enum pair. The only structural difference is that DC
  derivatives are constant — so the matrix is factorised exactly once and there is
  no Newton iteration, whereas AC re-evaluates and re-factorises each iteration.
- Both also share `lf.LoadFlowEngine`, the abstract contexts/parameters/results, the
  equation-system updater base, and the `lf.outerloop.*` loops (the AC and DC
  variants share common bases).
