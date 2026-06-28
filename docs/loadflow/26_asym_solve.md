(ch:asymsolve)=

# Solving and recovering the phases
The enlarged system of Chapter {ref}`ch:asymeq` is handed to the *same* Newton--Raphson solver, Jacobian and sparse LU as the balanced load flow (Chapters {ref}`ch:nr`--{ref}`ch:jac`). Asymmetry is not a different algorithm --- it is a larger $\bm f(\bm x)=\bm 0$. This short chapter records the three things that genuinely differ: which solver, how to start, and how to read the phases back out.

## Same Newton, bigger system

The equation system is selected at build time: with `asymmetrical` set, the context instantiates `AsymmetricalAcEquationSystemCreator` instead of the balanced creator ([`ac/AcLoadFlowContext.java`](https://github.com/powsybl/powsybl-open-loadflow/blob/main/src/main/java/com/powsybl/openloadflow/ac/AcLoadFlowContext.java)); everything downstream is unchanged. The Jacobian $\Jmat=\partial\bm f/\partial\bm x$ now carries, besides the positive-sequence blocks of the AC load flow:

- diagonal sequence blocks $\partial(I_x^{0},I_y^{0})/\partial(V_0,\varphi_0)$ and $\partial(I_x^{-},I_y^{-})/\partial(V_-,\varphi_-)$ from the line and shunt terms;

- dense $6\times6$ coupling blocks $\partial(I_x,I_y)/\partial(V_0,\varphi_0,V_+,\varphi_+,V_-,\varphi_-)$ at every unbalanced load and every open-phase branch.

Away from those few coupling elements the added rows are block-diagonal, so the Jacobian stays sparse and the KLU factorisation of Chapter {ref}`ch:linsolve` scales as before. One restriction: the fast-decoupled solver, which relies on the $P$--$\varphi$/$Q$--$V$ split of the *balanced* model, is incompatible with asymmetry and is rejected ([`ac/solver/FastDecoupledFactory.java`](https://github.com/powsybl/powsybl-open-loadflow/blob/main/src/main/java/com/powsybl/openloadflow/ac/solver/FastDecoupledFactory.java)); a full-Jacobian Newton solve is required.

## Initialisation away from the singular point

Initialisation needs one asymmetry-specific care ([`ac/solver/AcSolverUtil.java`](https://github.com/powsybl/powsybl-open-loadflow/blob/main/src/main/java/com/powsybl/openloadflow/ac/solver/AcSolverUtil.java)). The positive sequence starts as usual (flat or DC-warm, Chapter {ref}`ch:init`). The off-sequence *angles* start at $0$, but their *magnitudes* are seeded to a small non-zero value ($0.1\pu$), *not* zero: 

$$
V_0^{(0)}=V_-^{(0)}=0.1\pu,\qquad \varphi_0^{(0)}=\varphi_-^{(0)}=0 .
$$

 The reason is that a perfectly balanced operating point has $\Vc_0=\Vc_-=0$, and at exactly that point the off-sequence current equations {eq}`eq:shuntseq`--{eq}`eq:coupled` have a vanishing voltage and their Jacobian rows degenerate --- a removable singularity. Starting a hair away from zero lets Newton converge to the true (possibly very small) off-sequence voltages without tripping on the singular point. It is the asymmetric analogue of never starting an ordinary load flow from $V=0$.

## Recovering per-phase quantities

The solver returns sequence voltages, written back per bus as $(V_0,\varphi_0)$, $(V_+,\varphi_+)$, $(V_-,\varphi_-)$ (`LfAsymBus` setters). The physical, per-phase answer an engineer wants is one inverse Fortescue transform away, applied bus by bus: 

$$
\begin{bmatrix}\Vc_a\\\Vc_b\\\Vc_c\end{bmatrix}
  =\Amat_F\begin{bmatrix}\Vc_0\\\Vc_+\\\Vc_-\end{bmatrix},
$$

 and likewise for branch currents from the sequence currents of {eq}`eq:coupled`. The *voltage unbalance factor* $|\Vc_-|/|\Vc_+|$ (and its zero-sequence counterpart) --- the quantities standards such as EN 50160 limit --- falls straight out of the sequence magnitudes the solver already produced.

:::{admonition} Remark - Relation to fault calculations
:class: seealso
The three sequence networks assembled here are the very same ones a short-circuit study uses; the difference is the boundary condition. This part computes the *steady-state unbalanced operating point* (given per-phase loads and machine sequence impedances); a fault study would instead impose a sequence interconnection at the fault bus. Sharing the sequence model means the unbalanced load flow and fault analysis rest on a single, consistent foundation.

:::

This completes the mathematics of PowSyBl Open Load Flow as implemented today: the AC load flow and its controls, its DC linearisation, the sensitivities that ride on the converged Jacobian, the security analysis that screens contingencies by Woodbury superposition, the remedial actions layered on top, and --- here --- the three-sequence extension to unbalanced networks. Every equation has been tied to the line of Java that evaluates it.
