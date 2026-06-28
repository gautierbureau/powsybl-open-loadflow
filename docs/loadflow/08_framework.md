(ch:framework)=

# The equation framework
Everything so far --- bus balances, branch terms, the Jacobian --- is expressed in a small, generic, event-driven framework (`com.powsybl.openloadflow.equations`) that is shared by the AC and DC solvers. Understanding it makes the rest of the source navigable. This chapter is the "software mathematics": how variables, equations, terms and vectors are represented and kept consistent.

## Variables, equations, terms

::: description
an unknown, identified by an element number and a type $V$ (for AC: `BUS_V`, `BUS_PHI`, `BRANCH_ALPHA1`, `BRANCH_RHO1`, `SHUNT_B`, `DUMMY_P`, `DUMMY_Q`, ..., see `AcVariableType`). Each variable owns a *row* in the state vector.

a scalar equation, identified by an element number and a type $E$ (for AC: `BUS_TARGET_P`, `BUS_TARGET_Q`, `BUS_TARGET_V`, ..., see `AcEquationType`). Each active equation owns a *column*.

one additive contribution to an equation's left-hand side. It can `eval()` its value at the current state and `der(variable)` its analytic derivative. The branch power expressions of Chapter {ref}`ch:branch` are equation terms.
:::

An equation is literally a list of terms; its value is their sum (§{ref}`sec:assembly`). A term may be a *variable term* (the bare value of a variable, e.g. the "$V$" in $V=V^{\text{spec}}$) or a composite expression, and terms can be scaled (`multiply`) or negated (`minus`) --- this is how the sharing equations of §{ref}`sec:outer` attach coefficients like $\text{qPercent}_i$ or $1/N$.

## The four vectors

::: center
| Object | Holds | Indexed by |
|:---|:---|:---|
| `StateVector` $\xx$ | current values of all variables | variable row |
| `EquationVector` $\ff(\xx)$ | current LHS values of all active equations | equation column |
| `TargetVector` $\bm t$ | constant RHS of all active equations | equation column |
| `JacobianMatrix` $\Jmat\T$ | $\partial\ff/\partial\xx$ | (row,col) |
:::

The Newton mismatch is $\bm g=\ff-\bm t$ (`EquationVector.minus(TargetVector)`), and the step solves $\Jmat\T\Delta\xx=\bm g$ (§{ref}`sec:nriter`). The state, equation and Jacobian objects are connected by listeners so that consistency is maintained *lazily* (§{ref}`sec:lazy`).

## The index: who is active, what column

`EquationSystemIndex` maintains the bijection between *active* equations and matrix columns, and between variables (appearing in at least one active equation) and matrix rows. When an equation is activated or deactivated (§{ref}`sec:active`), the index renumbers columns and notifies the Jacobian (`onEquationChange`$\to$`STRUCTURE_INVALID`). The crucial invariant is 

$$
\#\{\text{active equations}\}=\#\{\text{variables to find}\}
$$

 i.e. the system stays **square**; `JacobianMatrix.initDer` throws if it ever isn't. Every control that adds an equation also adds a variable, and every PV/PQ switch swaps one equation for another of equal count --- the squareness is preserved by construction.

(sec:events)=

## Event-driven invalidation
The framework is reactive. Three listener interfaces propagate change:

- `StateVectorListener`: the Newton update $\xx\leftarrow\xx-\Delta\xx$ fires `onStateUpdate`, marking $\ff$ and $\Jmat$ values stale.

- `EquationSystemIndexListener`: structural edits (equation/variable add/remove, reordering) mark $\Jmat$ structure stale.

- `LfNetworkListener`: a control changing a target (e.g. a new $V^{\text{spec}}$, a tap move, a frozen $Q$) invalidates the affected target-vector entries (`TargetVector` listens and recomputes only those).

Because of this, an outer-loop action like "freeze generator $Q$ at its limit" (§{ref}`reactive-limit switching <sec:qlim>`) need only flip a couple of equation flags and one target; the framework figures out the minimal recomputation (often just a numeric refactor).

## Equation arrays: vectorised assembly

For performance on large networks, many identical equations (e.g. the bus $P$-balance over all buses) are stored not as individual objects but as an `EquationArray` with a contiguous block of columns and a parallel `EquationTermArray`. The mathematics is unchanged --- it is the same sum of terms --- but evaluation and differentiation run over primitive arrays in tight loops rather than chasing object references. The Jacobian builder (§{ref}`sec:assembly`) interleaves single equations and equation arrays by column order so the sparse matrix is still filled column-by-column.

## Putting it together: one Newton step in framework terms

1.  State changed last step $\Rightarrow$ `EquationVector` and `JacobianMatrix` are `VALUES_INVALID`.

2.  `JacobianMatrix.getMatrix()` lazily recomputes the non-zeros (`updateDer`) and refactors LU incrementally.

3.  `solveTransposed(g)` overwrites $\bm g$ with $\Delta\xx$.

4.  `StateVector.minus(`$\Delta\xx$`)` updates $\xx$ and fires `onStateUpdate`.

5.  `EquationVector.minus(TargetVector)` recomputes $\bm g$ for the convergence test.

This is precisely the loop of §{ref}`sec:nriter`, now seen from the data-structure side. The same framework, with `DcVariableType`/`DcEquationType`, drives the DC solver --- but that is out of scope here.
