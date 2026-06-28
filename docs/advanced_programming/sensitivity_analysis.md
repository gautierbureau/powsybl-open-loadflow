# Sensitivity analysis

A sensitivity is the partial derivative of an output (a *function* — branch flow,
current, bus voltage) with respect to an input (a *variable* — an injection, a
phase-shifter angle, a voltage set-point), evaluated at the solved operating point.
The `sensi` package computes these almost for free by reusing the load flow's
already-factorised Jacobian: build a right-hand side, one `solveTransposed`, then a
dot product per factor. The mathematics is the [sensitivity modeling
reference](../sensitivity/01_introduction.md); this page is the code map. All paths
are under `com/powsybl/openloadflow`.

## Two engines

[`OpenSensitivityAnalysisProvider`](https://github.com/powsybl/powsybl-open-loadflow/blob/main/src/main/java/com/powsybl/openloadflow/sensi/OpenSensitivityAnalysisProvider.java)
is the SPI entry; it selects, on `loadFlowParameters.isDc()`, one of two engines
extending the generic
[`AbstractSensitivityAnalysis`](https://github.com/powsybl/powsybl-open-loadflow/blob/main/src/main/java/com/powsybl/openloadflow/sensi/AbstractSensitivityAnalysis.java)
(which holds the factor model and the shared kernel):

- `AcSensitivityAnalysis` — reuses the **converged AC Jacobian** from the
  pre-contingency Newton–Raphson solve; for each contingency it re-applies the
  outage, **re-runs the AC load flow** and re-solves on the *new* Jacobian.
- `DcSensitivityAnalysis` — factorises the constant DC $\mathbf B'$ **once** and
  derives every post-contingency/post-action result by a [Woodbury](dc_engine.md#fast-dc-and-the-woodbury-update)
  update of that base factorisation; it also supports operator strategies.

## The factor model

A factor pairs a **function** with a **variable**. On the input side the SPI is
push-based: a `SensitivityFactorReader` feeds factors and a `SensitivityResultWriter`
receives `writeSensitivityValue(factorIndex, contingencyIndex, operatorStrategyIndex,
value, functionReference)` (index `-1` = base case).

Internally each factor is an
[`LfSensitivityFactor`](https://github.com/powsybl/powsybl-open-loadflow/blob/main/src/main/java/com/powsybl/openloadflow/sensi/AbstractSensitivityAnalysis.java):
`getFunctionEquationTerm()` maps the function type to the LF equation term that
already exists (`LfBranch.getP1()`, `LfBus.getCalculatedV()`, …). A `Status`
classifies computability — `VALID`, `VALID_ONLY_FOR_FUNCTION` (variable outside the
slack component ⇒ sensitivity ≡ 0 but reference still computed), `ZERO` (function
element absent ⇒ 0), `SKIP` (both absent ⇒ NaN) — refined per contingency. Two
shapes exist: `SingleVariableLfSensitivityFactor` (one element, optionally carrying
the `A1`/calculated-`V` equation for phase/voltage variables) and
`MultiVariablesLfSensitivityFactor` for **GLSK** (a weighted bus set) and **HVDC**
(a bi-variable ± set-point).

The key performance idea is grouping: `createFactorGroups(...)` groups all `VALID`
factors by `(variableType, variableId)` into a `SensitivityFactorGroup` and assigns
each group **one matrix column**, so the number of linear solves equals the number
of distinct *variables*, not the number of factors.

## The computational kernel

This is the same "build RHS → `solveTransposed` → `calculateSensi`" adjoint kernel
the [outer loops](outerloop_framework.md) use; sensitivity analysis just supplies
its own right-hand side:

1. **`initFactorsRhs(equationSystem, factorGroups, participationByBus)`** allocates a
   dense $\mathbf E$ of size `equationCount × #groups` and fills one column per group
   via each group's `fillRhs`.
2. **`JacobianMatrix.solveTransposed(E)`** solves $\mathbf J\,\mathbf S=\mathbf E$ in
   place (the transpose is the adjoint/sensitivity form).
3. **`calculateSensi`** — per factor,
   `sensi = functionEquationTerm.calculateSensi(states, group.index)`: the dot product
   of the function term's derivative row with the solved state column. The function
   *reference* is the term's `eval()` (AC) or its value on the flow-states matrix (DC).
4. **`unscaleSensitivity`** converts per-unit to engineering units
   (`sensi · functionBase / variableBase`); tiny magnitudes are filtered out before
   writing.

### Right-hand side per variable type (`fillRhs`)

- `INJECTION_ACTIVE_POWER` — `+1` on the variable bus's active-balance column, minus
  the slack-distribution participation on each participating bus (the balanced PTDF;
  see [the slack subtraction](../sensitivity/03_adjoint.md));
- `INJECTION_REACTIVE_POWER` — `+1` on the bus reactive-balance column;
- `BUS_TARGET_VOLTAGE` — `+1` on the bus voltage-equation column;
- `TRANSFORMER_PHASE` — `rad(1°)` on the branch $\alpha_1$ column;
- **GLSK** spreads `weight/Σ|weight|` over its buses, **HVDC** puts the
  ±set-point multipliers on the two converter buses.

The participation factors come from `ActivePowerDistribution` (proportional to
generation Pmax / generation P / load), normalised, appearing as `−factor` in the
column.

## Contingencies

- **DC** reuses the base $\mathbf B'$ factorisation: `ConnectivityBreakAnalysis`
  precomputes the $\pm1$ contingency columns and splits breaking from non-breaking
  cases; each post-contingency (and post-action) result is a `WoodburyEngine`
  update `state += Σ αᵢ · stateᵢ` applied to both the flow-states and the
  factor-states matrices — no refactorisation. The factor states are only
  recomputed when the RHS actually changes (slack participants or GLSK weights lost,
  via `rescaleGlsk`), and the flow states only when a PST is lost or connectivity
  breaks. Both preventive and curative (operator-strategy) actions are handled
  here, reusing the same update with the action columns; the supported action types
  are phase-shifter tap changes, branch open/close, and generator and load changes.
- **AC** has no low-rank shortcut: it saves the network state, then per contingency
  re-applies the outage, redistributes the lost power, **re-runs the AC load flow**,
  and re-solves on the new converged Jacobian (rescaling GLSK if connectivity
  broke), restoring the state afterwards.

Per-state status is written as `SUCCESS`, `FAILURE` (AC divergence) or `NO_IMPACT`;
per-factor uncomputable cases use the predefined `ZERO`/`VALID_ONLY_FOR_FUNCTION`/
`SKIP` results above (the [return codes](../sensitivity/06_contingency_woodbury.md#return-codes-for-uncomputable-factors)).

## Relation to the load flow

The engine builds **no new equations**. It reuses the load flow's
[`EquationSystem`](equation_framework.md), the factorised `JacobianMatrix` (the
converged NR Jacobian in AC, the constant $\mathbf B'$ in DC) and the equation
terms' `calculateSensi`/`eval`. Sensitivity analysis is, in effect, the same
incremental-sensitivity kernel the control [outer loops](outerloop_framework.md) run
internally — exposed to the user, with its own right-hand sides and many function
rows read out of each solved state column.
