# Security analysis

Security analysis answers "is the network still within limits after each
contingency, and do the planned remedial actions fix it?". The `sa` package runs a
base-case (N) load flow, then for every contingency a post-contingency solve, then
for every operator strategy a curative solve — detecting limit violations at each
state. The methodology is the [security modeling
reference](../security/17_security_overview.md); this page is the code map. All
paths are under `com/powsybl/openloadflow`.

## Three engines

[`OpenSecurityAnalysisProvider`](https://github.com/powsybl/powsybl-open-loadflow/blob/main/src/main/java/com/powsybl/openloadflow/sa/OpenSecurityAnalysisProvider.java)
is the SPI entry; it picks one of three engines, all extending the generic
[`AbstractSecurityAnalysis`](https://github.com/powsybl/powsybl-open-loadflow/blob/main/src/main/java/com/powsybl/openloadflow/sa/AbstractSecurityAnalysis.java)
which holds the whole loop:

| Engine | Each contingency is… |
|---|---|
| `AcSecurityAnalysis` | a **full AC Newton–Raphson re-solve** (most accurate, most expensive) |
| `DcSecurityAnalysis` | a **full DC linear solve** |
| `WoodburyDcSecurityAnalysis` (extends `DcSecurityAnalysis`) | a **Woodbury low-rank update** of the base DC factorisation (fastest) — selected by `dcFastMode` |

AC forces a single slack bus and disables the network cache; the Woodbury engine is
chosen when the load flow is DC and `OpenSecurityAnalysisParameters.isDcFastMode()`.
One subtlety worth knowing: when operator strategies are present or automation
systems are simulated, the provider forces the slower
[`NaiveGraphConnectivityFactory`](connectivity.md) because the fast decremental
connectivity does not yet support the topology *additions* those paths need.

## The analysis loop

`runSync` resolves parameters, loads the contingencies, registers the switches/taps
the actions and contingencies touch (`LfTopoConfig`), and builds the
`PropagatedContingency` list; then `runSimulations` runs per `LfNetwork`
(component):

1. **Pre-contingency (N) solve** via the load-flow engine; on convergence, build the
   `PreContingencyNetworkResult` and detect base-case violations with a
   `LimitViolationManager`.
2. Snapshot the base state: `NetworkState networkState = NetworkState.save(lfNetwork)`.
3. **For each contingency**: `toLfContingency(lfNetwork)` (skip if no impact);
   `lfContingency.apply(balanceType)`; redistribute the lost active power
   (`ContingencyActivePowerLossDistribution`); re-solve; build a post-contingency
   `LimitViolationManager` that references the pre-contingency one (for
   de-duplication) and detect violations → `PostContingencyResult`.
4. **For each operator strategy** of that contingency: evaluate its condition; if it
   fires, apply the selected actions (`LfActionUtils.applyListOfActions`), re-solve,
   detect violations against the pre-contingency reference → `OperatorStrategyResult`.
   With several strategies, the post-contingency state is itself saved and restored
   between them.
5. `networkState.restore()` before the next contingency, and reset any overridden
   parameters.

`NetworkState.save/restore` ([network state](lfnetwork.md#downstream-and-state-management))
snapshots and rolls back the bus/branch/HVDC/area states, so the whole list runs
against one loaded network. On a multi-component model, `runSimulations` runs on
each component and **merges** the results (concatenating per-contingency and
per-strategy results, summing distributed power), warning when a sub-component's
status diverges — see [multi-component networks](../security/17_security_overview.md#multi-component-networks).

## From IIDM contingency to applied `LfContingency`

[`PropagatedContingency`](https://github.com/powsybl/powsybl-open-loadflow/blob/main/src/main/java/com/powsybl/openloadflow/network/impl/PropagatedContingency.java)
turns an IIDM `Contingency` into the sets of elements to disable. With contingency
propagation on, `ContingencyTripping` walks through closed switches to find the
*full* set of terminals/switches that open, then `complete(...)` translates that into
branches to open, generators/loads to lose (with a `PowerShift`), shunts to shift,
and HVDC to open.

`toLfContingency(lfNetwork)` maps those IDs to LF objects and, crucially, runs a
[connectivity](connectivity.md) temporary-change transaction: it cuts the opened
branches, reads the **islanded buses** (`getVerticesRemovedFromMainComponent`),
counts the created synchronous components, **relocates the slack bus** if it became
isolated, and identifies HVDC links left without power. The result is an
[`LfContingency`](https://github.com/powsybl/powsybl-open-loadflow/blob/main/src/main/java/com/powsybl/openloadflow/network/LfContingency.java):
its `apply(balanceType)` disables the branches/buses/HVDCs, applies shunt shifts,
and adjusts the lost loads' and generators' targets; `getActivePowerLoss()` =
disconnected generation − disconnected load drives the post-contingency slack
redistribution.

## Detecting violations

[`LimitViolationManager`](https://github.com/powsybl/powsybl-open-loadflow/blob/main/src/main/java/com/powsybl/openloadflow/sa/LimitViolationManager.java)`.detectViolations(...)`
iterates the non-disabled branches and buses: per branch side and limit group it
checks CURRENT, ACTIVE_POWER and APPARENT_POWER against the sorted temporary limits
(reporting the first one exceeded); per bus it checks high/low voltage; plus
voltage-angle limits. Violations are keyed (subject, side, limit group) in an
ordered map for de-duplication, and when the manager is built with the
**pre-contingency manager as reference** plus `IncreasedViolationsParameters`, a
post-contingency violation that is not meaningfully worse than the base case is
dropped (`violationWeakenedOrEquivalent`). `LimitReductionManager` applies any
configured limit reductions. This is the [limit-violation chapter](../security/20_limit_violations.md)
in code.

## Results

Results are accumulated into the powsybl-security record types:
`SecurityAnalysisResult` = `PreContingencyResult` + `List<PostContingencyResult>` +
`List<OperatorStrategyResult>`, wrapped in a `SecurityAnalysisReport`. The
monitored values come from the `AbstractNetworkResult` builders
(`Pre`/`PostContingencyNetworkResult`), driven by a `StateMonitorIndex`, collecting
`BranchResult` (P/Q/I per side), `BusResult` (V) and three-winding-transformer
results. Each post-contingency state carries a `PostContingencyComputationStatus`:
AC maps the outer-loop and solver status (CONVERGED / MAX_ITERATION_REACHED /
SOLVER_FAILED / FAILED / NO_IMPACT), DC reports CONVERGED/FAILED, and the Woodbury
engine reports CONVERGED (the linear update always succeeds).

## Operator strategies and actions

`OperatorStrategies` indexes strategies by contingency and validates their
references; `LfActionUtils` converts the needed IIDM `Action`s to `LfAction`s and
`applyListOfActions(...)` mutates the LF network (branch open/close, tap, shunt
section, load/generator change), updating connectivity for topology actions. A
strategy's **condition** is evaluated in `checkCondition`: `TrueCondition`, the
violation conditions (`Any`/`AtLeastOne`/`AllViolationCondition`) against the
post-contingency violations, and threshold conditions delegated to
`ThresholdConditionEvaluator` (branch/3wt flow or injection compared to a
threshold). Selected actions are applied **on top of** the already-applied
contingency, then re-solved — the curative state of the [operator-strategies
chapter](../security/21_operator_strategies.md).

## The Woodbury fast-DC engine

`WoodburyDcSecurityAnalysis` overrides `runSimulations` to avoid refactorising
$\mathbf B'$ per contingency, using the `dc/fastdc` machinery (see
[the DC engine](dc_engine.md#fast-dc-and-the-woodbury-update)). The flow:

1. Solve the pre-contingency states once against the cached factorisation.
2. `ConnectivityBreakAnalysis.run(...)` precomputes the $\pm1$ contingency
   sensitivity columns and partitions contingencies into connectivity-breaking and
   non-breaking (a cheap sensitivity test confirmed by a graph check only on
   candidates).
3. For each contingency (and operator strategy): if it loses no generation/load, no
   PST and does not break connectivity, the post state is a pure
   `WoodburyEngine.toPostContingencyAndOperatorStrategyStates(...)` low-rank update;
   otherwise it falls back to a lightweight DC re-solve with a modified target
   vector. Then it sets the state, applies the `LfContingency`, updates the network
   result and detects violations.

Because the base factorisation is shared, this engine turns "one load flow per
contingency" into "one back-substitution per contingency" for the common case — the
reason it is the default for large $N\!-\!1$ screening.
