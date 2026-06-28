# The equation framework

The `com.powsybl.openloadflow.equations` package is the linear-algebra heart of
PowSyBl Open Load Flow. It turns a physical [`LfNetwork`](lfnetwork.md) into a
system of equations $\mathbf f(\mathbf x)=\mathbf t$ and drives the Newton–Raphson
(AC) or single (DC) solve. The *mathematics* of that system is described in the
modeling reference ([the equation framework](../loadflow/08_framework.md),
[Newton–Raphson](../loadflow/06_newton_raphson.md),
[the Jacobian](../loadflow/07_jacobian.md)); this page is the **code-level** view —
the classes, who owns what, and how a change propagates.

The whole framework is generic over two enum types,
`<V extends Enum<V> & Quantity, E extends Enum<E> & Quantity>` — `V` the variable
kinds, `E` the equation kinds — so the *same* machinery serves AC, DC and
unbalanced problems; each problem only supplies its enums and a creator. All paths
below are under `com/powsybl/openloadflow`.

## The container: `EquationSystem`

[`equations/EquationSystem.java`](https://github.com/powsybl/powsybl-open-loadflow/blob/main/src/main/java/com/powsybl/openloadflow/equations/EquationSystem.java)
owns everything and is the factory for equations. It holds the scalar equations
(keyed by `(elementNum, type)`), the vectorised equation arrays (see
[equation arrays](equation_array.md)), the shared `VariableSet`, the `StateVector`,
the `EquationSystemIndex`, and the listener list. It also keeps a
per-`LfElement` index of the equations and terms that touch each element, so a
topology or state change can find exactly what to invalidate.

The main entry point is `createEquation(element, type)` (get-or-create): it
validates that `element.getType() == type.getElementType()`, then either returns
an array-backed element (if that type is vectorised) or creates a `SingleEquation`,
seeding its `active` flag from `element.isDisabled()`. Equation/term mutations fan
out through `notify…Change(...)` to the listeners (the source of the event-driven
model below).

## Variables: identity, rows, interning

A **variable** is a `(elementNum, type)` pair — e.g. "voltage magnitude of bus 7".
Three small classes carry the notion:

- [`Quantity`](https://github.com/powsybl/powsybl-open-loadflow/blob/main/src/main/java/com/powsybl/openloadflow/equations/Quantity.java) —
  the interface every `V`/`E` enum implements (`getSymbol()`, `getElementType()`);
  it binds an enum constant to a physical `ElementType` (BUS, BRANCH, …).
- [`Variable`](https://github.com/powsybl/powsybl-open-loadflow/blob/main/src/main/java/com/powsybl/openloadflow/equations/Variable.java) —
  identity is `(elementNum, type)`; it carries a mutable **row** (its Jacobian row,
  `-1` when not in the system).
- [`VariableSet`](https://github.com/powsybl/powsybl-open-loadflow/blob/main/src/main/java/com/powsybl/openloadflow/equations/VariableSet.java) —
  an interning pool: `getVariable(elementNum, type)` returns the *same* `Variable`
  object for every reference to the same quantity. That object identity is what
  lets the index **reference-count** a variable across all the terms that use it.

## Equations and terms: the residual

An **equation's value is the sum of its active terms.**
[`Equation`](https://github.com/powsybl/powsybl-open-loadflow/blob/main/src/main/java/com/powsybl/openloadflow/equations/Equation.java) /
[`SingleEquation`](https://github.com/powsybl/powsybl-open-loadflow/blob/main/src/main/java/com/powsybl/openloadflow/equations/SingleEquation.java):
`eval()` = Σ `term.eval()`, and `evalLhs()` = Σ `term.evalLhs()` (lhs = `eval - rhs`).
A `SingleEquation` keeps its terms grouped by the variable they depend on
(`termsByVariable`, a `TreeMap`, iterated in row order) and a cache of matrix-slot
indices for fast value updates.

A **term**
([`EquationTerm`](https://github.com/powsybl/powsybl-open-loadflow/blob/main/src/main/java/com/powsybl/openloadflow/equations/EquationTerm.java) /
`SingleEquationTerm`, base `AbstractEquationTerm`) implements `eval()`,
`der(variable)`, `getVariables()` and an optional right-hand side; it reads the
state through a `StateVector`. The concrete physics lives in `ac/equations/*` and
`dc/equations/*` (e.g. `ClosedBranchSide1ActiveFlowEquationTerm`). `multiply(...)`
and `minus()` wrap a term in a `MultiplyByScalarEquationTerm`; a
`VariableEquationTerm` is just "x itself", used for trivial target equations
($x=\text{target}$).

`SingleEquation.der(handler)` walks `termsByVariable`, sums `term.der(v)` over
active terms for each variable that currently has a row, and calls
`handler.onDer(variable, value, …)` — the Jacobian-assembly callback. It emits a
derivative slot even when terms are momentarily inactive, so reactivation is a
cheap value update rather than a structural change.

## The vectors and the mismatch

The solve is expressed with four vectors:

- [`StateVector`](https://github.com/powsybl/powsybl-open-loadflow/blob/main/src/main/java/com/powsybl/openloadflow/equations/StateVector.java)
  — $\mathbf x$, a `double[]` plus listeners; `set(...)`/`minus(...)` mutate it and
  fire `onStateUpdate`, the trigger that invalidates everything derived.
- [`EquationVector`](https://github.com/powsybl/powsybl-open-loadflow/blob/main/src/main/java/com/powsybl/openloadflow/equations/EquationVector.java)
  — $\mathbf f(\mathbf x)$, one entry per equation column
  (`array[eq.getColumn()] = eq.evalLhs()`); it is a `StateVectorListener`, so a
  state change marks its values invalid.
- [`TargetVector`](https://github.com/powsybl/powsybl-open-loadflow/blob/main/src/main/java/com/powsybl/openloadflow/equations/TargetVector.java)
  — $\mathbf t$, built by a per-equation `Initializer` (`ac/AcTargetVector`,
  `dc/DcTargetVector`); it registers an `LfNetworkListener`, so a setpoint/load/tap
  change invalidates it independently of the state.
- The mismatch $\mathbf g=\mathbf f-\mathbf t$ is `equationVector.minus(targetVector)`
  — the residual the solver drives to zero.

The derived vectors share a lazy three-state base (`AbstractVector`): a plain state
change invalidates only *values*; a structural change (an equation/variable added
or removed) invalidates the *vector*.

## The Jacobian

[`JacobianMatrix`](https://github.com/powsybl/powsybl-open-loadflow/blob/main/src/main/java/com/powsybl/openloadflow/equations/JacobianMatrix.java)
assembles $\partial\mathbf f/\partial\mathbf x$ on a PowSyBl-Math `MatrixFactory`
(sparse → KLU LU in production). Two design points matter to a developer:

- **It stores the transpose.** `initDer()` writes each derivative at
  `matrix.add(row = variable.getRow(), column = equation.getColumn(), value)` —
  variables index rows, equations index columns — which is $\mathbf J^{\mathsf T}$.
  Consequently the Newton step $\mathbf J\,\Delta\mathbf x=\mathbf g$ is obtained by
  calling **`solveTransposed`** on the residual. The `add` returns a stable slot
  index that the equation caches, so `updateDer()` re-pushes values without
  rebuilding structure.
- **It reuses the LU factorisation.** A status machine
  (`STRUCTURE_INVALID > VALUES_AND_ZEROS_INVALID > VALUES_INVALID > VALID`), driven
  by index/state listener callbacks, does the minimal rebuild: a value change
  refreshes the existing factors (`lu.update`), only a sparsity-pattern change
  re-decomposes. (`JacobianMatrixFastDecoupled` is the fast-decoupled variant.)

The math behind the transpose trick and the LU reuse is in
[the native math layer chapter](../loadflow/10_linear_solver.md).

## Numbering: `EquationSystemIndex`

[`EquationSystemIndex`](https://github.com/powsybl/powsybl-open-loadflow/blob/main/src/main/java/com/powsybl/openloadflow/equations/EquationSystemIndex.java)
owns row (variable) and column (equation) numbering. Active equations are sorted
and assigned columns; variables are **reference-counted** across all active terms
and assigned rows — a variable gets a row only while at least one active term needs
it, and loses it (row `-1`) when the count drops to zero. Renumbering is **lazy**:
activation events just flip `equationsIndexValid` / `variablesIndexValid`, and the
actual renumber happens on the next `getRowCount()`/`getSorted…()` call.
`updateWithSeparation(...)` partitions the index into two blocks for fast-decoupled.

## The lazy, event-driven update model

This is the property that makes contingencies, outer-loop actions and topology
changes cheap. Two listener layers fan out from `EquationSystem`:

1. **`EquationSystemListener`** — equation/term created/removed/activated/
   deactivated events; the `EquationSystemIndex` consumes them to add/remove
   equations and ref-count variables.
2. **`EquationSystemIndexListener`** — emitted by the index after structural
   changes; consumed by `JacobianMatrix` and the derived vectors to set their
   invalidation status.

Plus **`StateVectorListener`**: a state update marks `EquationVector` and
`JacobianMatrix` values invalid (structure untouched). The net effect: a state
change only re-evaluates values, while activating/disabling an element surgically
adds or removes the affected rows/columns and forces exactly the needed structural
rebuild — nothing recomputes globally.

## Defining a problem's equation types

Each problem supplies a pair of `Quantity` enums and a creator/updater/target
trio:

- **AC** — `ac/equations/AcVariableType` (`BUS_V`, `BUS_PHI`, `SHUNT_B`,
  `BRANCH_RHO1`, …) and `AcEquationType` (`BUS_TARGET_P/Q/V/PHI`, `DISTR_Q`,
  `ZERO_V`, …), wired by `AcEquationSystemCreator` / `AcEquationSystemUpdater` and
  the `AcTargetVector` initializer.
- **DC** — `dc/equations/DcVariableType` / `DcEquationType` (`BUS_TARGET_P`,
  `BUS_TARGET_PHI`, `BRANCH_TARGET_ALPHA1`, `ZERO_PHI`, `DUMMY_TARGET_P`), wired by
  `DcEquationSystemCreator` / `DcEquationSystemUpdater` / `DcTargetVector`.

Each enum constant carries a symbol (for pretty-printing equations) and its
`ElementType`; the creator builds, per element, the equations and adds the physics
terms. The AC engine and DC engine pages cover these creators.

## End-to-end: one Newton iteration

Driven by the AC solver, the pieces above compose into:

```
x  = StateVector                              current state
f  = EquationVector                           f(x) = Σ active terms, per column
g  = equationVector.minus(targetVector)       residual  g = f − t
JacobianMatrix.solveTransposed(g)             Jᵀ stored ⇒ solves J·Δx = g; g ← Δx
StateVector.minus(Δx)                         x ← x − Δx   (fires onStateUpdate)
                                              → EquationVector & Jacobian values invalid
g  = equationVector.minus(targetVector)       recompute residual
StoppingCriteria.test(g)                      converged when ‖g‖ is small
```

Between solves, a topology or control change flips equation/term active flags; the
index renumbers lazily and the Jacobian rebuilds only the affected structure,
reusing the LU factorisation whenever the sparsity pattern is unchanged. The
scalar-vs-vectorised representation of the equations themselves is the subject of
the [equation arrays](equation_array.md) page.
