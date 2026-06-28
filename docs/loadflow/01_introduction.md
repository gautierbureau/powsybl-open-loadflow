(part:ac)=

# Introduction and scope

## What this document is

This document is a *complete* mathematical description of the **alternating-current (AC) load flow** as implemented in the open-source solver [PowSyBl Open Load Flow]{.smallcaps} (hereafter *OLF*). It is written to be read by someone who has *no prior knowledge of power systems* and who wants to understand, down to the last partial derivative, what the program computes and why.

Every important formula is

- **derived**, not merely stated --- we start from Maxwell-level circuit facts (Ohm's and Kirchhoff's laws in phasor form) and build up to the full Newton--Raphson iteration;

- **matched to the code** --- a marginal source reference like [`ac/equations/ClosedBranchSide1ActiveFlowEquationTerm.java`](https://github.com/powsybl/powsybl-open-loadflow/blob/main/src/main/java/com/powsybl/openloadflow/ac/equations/ClosedBranchSide1ActiveFlowEquationTerm.java) tells you exactly which Java class implements the equation, so the document doubles as a reading guide to the source tree;

- **illustrated with matrices** --- wherever a vector or a Jacobian appears, we write it out in full on a small example so that the sparsity pattern and the block structure become visible.

## What a load flow is

A power network is a set of *buses* (electrical nodes) connected by *branches* (transmission lines, cables, transformers). At each bus, generators inject power and loads consume power. The **load-flow** (or *power-flow*) problem is:

> *Given the topology of the network, the impedances of all branches, the power injected or consumed at every bus, and a small number of voltage set-points, find the complex voltage (magnitude and angle) at every bus such that power is conserved everywhere.*

Once the bus voltages are known, *everything else* --- the current and the power flowing in each branch, the losses, the reactive production of each generator --- follows by simple substitution. The difficulty is that the power-balance equations are *nonlinear* in the voltages (they contain products $V_iV_j$ and trigonometric functions of angle differences), so they must be solved iteratively. OLF uses the **Newton--Raphson** method (Chapter {ref}`ch:nr`).

## Scope and boundaries

This *Load flow* section covers the power-flow calculation in full:

- **{ref}`part:ac` --- AC load flow** (this part): the balanced, positive-sequence AC load flow. The network model (Chapter {ref}`ch:network`), the branch flow equations and their derivatives (Chapters {ref}`ch:branch`--{ref}`ch:bus`), the Newton--Raphson solver and its Jacobian (Chapters {ref}`ch:nr`--{ref}`ch:jac`), the generic equation framework (Chapter {ref}`ch:framework`), voltage initialisation (Chapter {ref}`ch:init`), the sparse KLU linear algebra (Chapter {ref}`ch:linsolve`), convergence control (Chapter {ref}`ch:stop`), and all the *control outer loops* --- distributed slack, generator/transformer/shunt voltage control, reactive limits, phase control, secondary voltage control, HVDC AC emulation (Chapter {ref}`ch:outer`).

- **{ref}`part:dc` --- DC load flow**: the linear active-power model, its single-solve (non-iterative) solver, and its outer loops.

- **{ref}`part:asym` --- Unbalanced (three-sequence) load flow**: the symmetrical-components extension for asymmetrical calculations.

The **{ref}`Sensitivity analysis <part:sensi>`** and the **{ref}`Security analysis <part:security>`** (contingency analysis with remedial actions) build directly on this machinery and are documented in their own sections.

(sec:overview)=

## How OLF is organised (the 10-thousand-foot view)
OLF separates cleanly into three layers, and this document follows the same separation.

1.  **The network model** (`com.powsybl.openloadflow.network`). The external grid model is converted into a lightweight, numeric *LfNetwork*: buses (`LfBus`), branches (`LfBranch`) with their $\pi$-model (`PiModel`), shunts, loads and generators. All quantities are converted to the *per-unit* system (Chapter {ref}`ch:fundamentals`).

2.  **The equation system** (`com.powsybl.openloadflow.equations` and `ac.equations`). A generic, sparse, event-driven framework builds a vector of *equations* $\ff(\xx)$, a vector of *variables* $\xx$, a *target* vector and the *Jacobian* $\Jmat=\partial\ff/\partial\xx$. The AC instantiation (`AcEquationSystemCreator`) wires the branch-flow terms into the bus power-balance equations.

3.  **The solvers** (`ac.solver` and the native math). The inner *Newton--Raphson* solver drives $\ff(\xx)$ to the target; the outer loops adjust controls (Chapter {ref}`ch:outer`) and re-run Newton until everything is consistent. The linear systems are factorised by the KLU sparse LU solver wrapped by *PowSyBl Math*.

{numref}`fig:flow` shows the overall control flow; the rest of the document fills in each box.

```{figure} ../_figures/ac_two_loops.svg
:name: fig:flow
:width: 80%

The two nested loops of an AC load flow: the inner Newton loop solves $\ff(\xx)=0$ for fixed controls; the outer loop adjusts controls and re-solves.
```

## A note on conventions

We use the engineering imaginary unit $\jj$ (so $\jj^2=-1$). Complex quantities (phasors) are underlined: $\Vc,\Ic,\Sc,\Yc$. Bold symbols are real vectors or matrices: $\xx,\ff,\Jmat$. A complex conjugate is written $\conj{\Vc}$. All electrical quantities are in the per-unit system of Chapter {ref}`ch:fundamentals` unless a physical unit is named explicitly. A complete symbol table is given in Chapter {ref}`ch:notation`.
