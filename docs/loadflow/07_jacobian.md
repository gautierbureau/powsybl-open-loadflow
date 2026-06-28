(ch:jac)=

# The Jacobian: assembly and structure
The Jacobian is the single most important data structure in the solver. This chapter shows how OLF builds it from the elementary equation-term derivatives of Chapter {ref}`ch:branch`, what its sparsity and block structure look like, and how it is kept up to date across Newton iterations and outer-loop changes with minimal recomputation.

(sec:assembly)=

## From equation terms to matrix entries
Recall (§{ref}`sec:vectors`) that each active equation is a sum of *equation terms*, and each term knows its own variables and analytic derivatives (the `der(variable)` methods of Chapter {ref}`ch:branch`). The derivative of an equation with respect to a variable is, by linearity, the sum of the term derivatives that depend on that variable `equations/SingleEquation.java (der)`: 

$$
\pdv{(\text{equation }e)}{(\text{variable }v)}
   =\sum_{\substack{\text{terms }\tau\in e\\ v\in\text{vars}(\tau)}}\ \pdv{\tau}{v}.
$$

 OLF iterates equations in *column* order and, for each, asks every term for its derivatives, writing each value into the sparse matrix at $(\text{row}=v.\text{row},\ \text{col}=e.\text{column})$ `equations/JacobianMatrix.java (initDer)`. As noted in §{ref}`transposed Jacobian storage <sec:transpose>`, "row $=$ variable, column $=$ equation" means the stored matrix is $\Jmat\T$.

:::{admonition} Remark - One pass, many equations share a variable
:class: seealso
Several equations may have a derivative with respect to the same variable (e.g. two branches meeting at a bus both depend on that bus's $V$). Each contributes a separate matrix entry in its own column; the *column* is the equation, so there is no collision. Within one equation, multiple terms sharing a variable are *summed* (the formula above) before being written once.

:::

## Sparsity pattern

A bus equation's left-hand side contains only the flows of its incident branches, the bus's own shunt/load, and (for controls) a few extra variables. Therefore $\partial(\text{bus }i\text{ equation})/\partial(\text{variable of bus }j)\ne0$ only when $i=j$ or buses $i,j$ are directly connected. The Jacobian's sparsity pattern is thus essentially the network's **adjacency matrix**, replicated over the $2\times2$ $(P,Q)\times(\varphi,V)$ sub-blocks. In a real grid each bus has on the order of 2--4 neighbours, so $\Jmat$ has $O(n)$ non-zeros out of $n^2$ --- below one part in a thousand for a national grid. Exploiting this is not optional; it is what makes the load flow tractable (Chapter {ref}`ch:linsolve`).

```{figure} ../_figures/jacobian_sparsity.svg
:name: fig:sparse
:width: 80%

Typical Jacobian sparsity for a small network: the structure mirrors the network graph, which is what KLU exploits.
```

## Block structure: $H,N,J,L$

Ordering variables as $(\bm\varphi,\bm V)$ and equations as $(\bm P,\bm Q)$ exposes the four classical blocks 

$$
\Jmat=
  \begin{bmatrix}
    \displaystyle\pdv{\bm P}{\bm\varphi} & \displaystyle\pdv{\bm P}{\bm V}\\[10pt]
    \displaystyle\pdv{\bm Q}{\bm\varphi} & \displaystyle\pdv{\bm Q}{\bm V}
  \end{bmatrix}
  =\begin{bmatrix} \bm H & \bm N\\ \bm J & \bm L\end{bmatrix}.
$$

 From the branch derivatives of {numref}`tab:derivs` one reads the well-known qualitative facts: $\bm H=\partial\bm P/\partial\bm\varphi$ and $\bm L=\partial\bm Q/\partial\bm V$ are "large" (active power is mostly driven by angle, reactive power by magnitude), while the cross blocks $\bm N,\bm J$ are "small" for lightly-loaded, mostly-reactive networks. The fast-decoupled solver (§{ref}`sec:nriter`) sets $\bm N=\bm J=0$ and freezes $\bm H,\bm L$; full Newton, the default, keeps all four exact.

For a PV bus the $Q$ row is replaced by the trivial $V=V^{\text{spec}}$ row (a single $1$ on the $V$ column), and for the slack bus the $P$ row is absent --- the active/inactive flag mechanism of §{ref}`sec:active` simply removes those rows and columns from the index, so $\Jmat$ stays square and non-singular.

## Control variables enlarge the system

Each control adds its own variable *and* its own equation, keeping the system square:

::: center
| Control | extra variable | extra equation |
|:---|:---|:---|
| phase shifter (active-power) | $\alpha=\texttt{BRANCH\_ALPHA1}$ | `BRANCH_TARGET_P` or `_ALPHA1` |
| ratio tap (voltage) | $\rho=\texttt{BRANCH\_RHO1}$ | `BUS_TARGET_V` / `DISTR_RHO` |
| shunt (voltage) | $b=\texttt{SHUNT\_B}$ | `BUS_TARGET_V` / `DISTR_SHUNT_B` |
| shared $Q$ control | --- | `DISTR_Q` |
| zero-impedance branch | $\texttt{DUMMY\_P},\texttt{DUMMY\_Q}$ | `ZERO_V`,`ZERO_PHI` |
:::

The branch flow terms already expose the $\partial/\partial\alpha$ and $\partial/\partial\rho$ derivatives of §{ref}`sec:branchderiv`, so enlarging the Jacobian costs nothing beyond a few extra non-zeros. The detailed equations are in Chapter {ref}`ch:outer` (controls) and Chapter {ref}`ch:special` (zero impedance).

(sec:lazy)=

## Lazy updates: the four-state cache
Refactorising the Jacobian every Newton step is unavoidable (its values change with $\xx$), but rebuilding its *structure* or its *LU symbolic factorisation* is not always necessary. OLF tracks a status with four levels of increasing severity [`equations/JacobianMatrix.java`](https://github.com/powsybl/powsybl-open-loadflow/blob/main/src/main/java/com/powsybl/openloadflow/equations/JacobianMatrix.java):

1.  `VALID` --- nothing to do.

2.  `VALUES_INVALID` --- the state $\xx$ changed (normal Newton step): recompute the numerical entries, keep the structure, and *update* the LU factors incrementally.

3.  `VALUES_AND_ZEROS_INVALID` --- a term was toggled, so some entries that were zero may now be non-zero: recompute values, refactor without the incremental short-cut.

4.  `STRUCTURE_INVALID` --- an equation/variable was added or removed (an outer loop changed the system, §{ref}`sec:active`): rebuild the sparse structure and redo the symbolic factorisation from scratch.

The status is driven by *listeners*: changing the state vector fires `onStateUpdate` ($\Rightarrow$ `VALUES_INVALID`); activating an equation fires `onEquationChange` ($\Rightarrow$ `STRUCTURE_INVALID`); and so on. This event-driven invalidation (Chapter {ref}`ch:framework`) is what lets a Newton step pay only for what actually changed --- typically a cheap numeric refactor reusing the symbolic analysis from the very first iteration.
