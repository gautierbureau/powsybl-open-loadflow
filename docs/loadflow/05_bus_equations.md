(ch:bus)=

# Bus equations and the nonlinear system
Chapter {ref}`ch:branch` gave the power that *one* branch pushes into a bus. This chapter assembles all branches, shunts, loads and generators at a bus into a **power-balance equation**, stacks those over the whole network into the nonlinear system $\ff(\xx)=\bm t$, and shows exactly which equations are active for each bus type. A small three-bus network is carried through as a running matrix illustration.

## Kirchhoff as a power balance

Kirchhoff's current law at bus $i$ says the injected current equals the sum of currents into the connected branches. Multiplying by $\conj{\Vc_i}$ turns it into a *power* balance: the net power injected by generators and loads at bus $i$ must equal the sum of the branch flows leaving bus $i$. Splitting into real and imaginary parts gives the two scalar equations OLF actually solves, for every non-slack bus $i$: 

$$
\boxed{\;
  \underbrace{\sum_{b\ni i} P_{b,i}(\xx)\;+\;P^{\text{sh}}_i(V_i)\;+\;P^{\text{load}}_i(V_i)}_{\textstyle \text{terms of equation }\texttt{BUS\_TARGET\_P}}
  \;=\; P_i^{\text{spec}},\;}
$$ (eq:Pbalance)

 

$$
\boxed{\;
  \sum_{b\ni i} Q_{b,i}(\xx)\;+\;Q^{\text{sh}}_i(V_i)\;+\;Q^{\text{load}}_i(V_i)
  \;=\; Q_i^{\text{spec}}.\;}
$$ (eq:Qbalance)

 Here $\sum_{b\ni i}$ runs over the branches incident to bus $i$, and $P_{b,i}$ is either $P_1$ (if bus $i$ is side 1 of branch $b$) or $P_2$ (if side 2) from {eq}`eq:P1`/{eq}`eq:P2`. The right-hand sides $P_i^{\text{spec}},
Q_i^{\text{spec}}$ are the scheduled injections (generation minus the constant-power part of load), assembled in the *target vector* (§{ref}`sec:targets`).

`ac/equations/AcEquationSystemCreator.java (createBusEquation, createImpedantBranchEquations)`

In code, each bus gets an equation object `BUS_TARGET_P` and `BUS_TARGET_Q`; as each branch is created, its $P_1$ term is `addTerm`-ed to bus 1's P-equation and its $P_2$ term to bus 2's P-equation (and likewise for Q). The left-hand side of {eq}`eq:Pbalance` is thus built incrementally, branch by branch. Shunt and voltage-dependent load terms are added to the same two equations.

(sec:vectors)=

## The state vector, equation vector and target vector
Stack the unknowns of all buses into the **state vector** $\xx$ and the left-hand sides into the **equation vector** $\ff(\xx)$: 

$$
\xx=\bigl[\,\dots,V_i,\varphi_i,\dots,\ \text{(plus control vars }\rho,\alpha,b,\dots)\bigr]\T,
  \qquad
  \ff(\xx)=\bigl[\,\dots, \textstyle\sum P_{b,i}+\dots,\ \sum Q_{b,i}+\dots,\dots\bigr]\T .
$$

 The load flow is then the square nonlinear root-finding problem 

$$
\boxed{\;\ff(\xx)=\bm t\;}\qquad\Longleftrightarrow\qquad \bm g(\xx):=\ff(\xx)-\bm t=\bm 0,
$$ (eq:system)

 with $\bm t$ the target vector. OLF keeps $\ff$ and $\bm t$ as *separate* arrays (classes `EquationVector` and `TargetVector`); the mismatch $\bm g=\ff-\bm t$ is what Newton drives to zero (§{ref}`sec:nriter`). Counting: each PQ bus contributes 2 equations and 2 unknowns $(V,\varphi)$; each PV bus contributes 2 equations ($P$-balance and $V=V^{\text{spec}}$) and 2 unknowns ($\varphi$ and the reactive output, the latter recovered *after* convergence); the slack/reference bus fixes $V,\varphi$ and drops its $P$ equation. The system is therefore square --- a necessary condition for a unique local solution and for $\Jmat$ to be invertible (Chapter {ref}`ch:jac`).

(sec:targets)=

## Targets: the right-hand side
[`ac/AcTargetVector.java`](https://github.com/powsybl/powsybl-open-loadflow/blob/main/src/main/java/com/powsybl/openloadflow/ac/AcTargetVector.java) The target value for each equation type is a *constant* (it does not depend on $\xx$), recomputed only when a control changes it. The principal cases:

::: center
| Equation | Target $t$ | Meaning |
|:---|:---|:---|
| `BUS_TARGET_P` | $P_i^{\text{spec}}=\sum P_g-\sum P_{\text{load}}^{(0)}$ | net scheduled active injection |
| `BUS_TARGET_Q` | $Q_i^{\text{spec}}$ | net scheduled reactive injection (PQ bus) |
| `BUS_TARGET_V` | $V_i^{\text{spec}}$ | voltage set-point (PV bus) |
| `BUS_TARGET_PHI` | $0$ | reference-bus angle origin |
| `BRANCH_TARGET_ALPHA1` | $\alpha_0$ | phase-shifter constant shift |
| `BRANCH_TARGET_RHO1` | $\rho_0$ | transformer constant ratio |
| `SHUNT_TARGET_B` | $b_0$ | shunt constant susceptance |
| `DISTR_Q, DISTR_RHO, DISTR_SHUNT_B` | see §{ref}`sec:outer` | sharing equations (target 0 or a $Q$ combination) |
:::

One subtlety: when an equation contains a pure *variable* term on its left-hand side (e.g. $V=V^{\text{spec}}$ uses the term "$V$"), the constant part of any right-hand-side term is moved into $t$ (`targets[col] -= equation.rhs()`); this is the mechanism that lets the constant-power load and the SVC-slope corrections live on the target side.

(sec:active)=

## Active/inactive equations: bus types emerge
OLF does not label a bus "PV" or "PQ". Instead, every bus owns *all* candidate equations, and a subset is marked *active*. Only active equations enter $\ff$, $\bm t$ and $\Jmat$. The rules `ac/equations/AcEquationSystemCreator.java, AcEquationSystemUpdater.java`:

- **Slack bus**: `BUS_TARGET_P` *deactivated* (its injection is the free slack); see §{ref}`sec:slackeq`.

- **Reference bus**: `BUS_TARGET_PHI` ($\varphi=0$) *activated*; see §{ref}`sec:refeq`.

- **PV (voltage-controlled) bus**: `BUS_TARGET_V` *activated*, `BUS_TARGET_Q` *deactivated*.

- **PQ bus**: `BUS_TARGET_Q` active, `BUS_TARGET_V` inactive.

The reactive-limits outer loop (§{ref}`reactive-limit switching <sec:qlim>`) and all the voltage controls work purely by flipping these flags --- no matrix is rebuilt from scratch, the sparse framework (Chapter {ref}`ch:framework`) only restructures the affected rows/columns.

(sec:remotecontrol)=

### Remote, shared and sloped voltage control
The same flag-flipping expresses controls that act *away* from the controller. When a generator on bus $b_1$ regulates the voltage of a *remote* bus $b_2$, the controller keeps only its active balance --- a "P-bus", with `BUS_TARGET_V` *not* on $b_1$ --- while $b_2$ becomes a PQV bus that additionally activates 

$$
\texttt{BUS\_TARGET\_V}\ \text{on }b_2:\quad V_{b_2}=V^{c}_{b_1},
$$

 i.e. its magnitude is pinned to the controller's set-point. Several generators sharing one controlled bus split the reactive duty through the `DISTR_Q` sharing equation (§{ref}`shared voltage control <sec:distq>`). A remote *reactive*-power control is the same idea on a branch: the controller is a P-bus and the controlled branch carries `BRANCH_TARGET_Q`: $Q_{\text{branch}}=Q^{c}_{b_1}$ (§{ref}`sec:qcontrol`).

A static var compensator regulating its *local* voltage with a non-zero *slope* $s$ replaces the plain $V=V^c$ row by a sloped one 

$$
\texttt{BUS\_TARGET\_V}\ \text{(sloped)}:\quad V_{b_1}+s\,q_{\text{svc}}=V^{c}_{b_1},
$$

 so the held voltage drifts slightly with the SVC's reactive output (the slope correction sits on the target side, §{ref}`sec:targets`). OLF supports this only in the clean case: exactly one voltage-regulating generator on the bus (any others must be on local reactive control), the control is local, and no remote controller also regulates that bus.

(sec:refeq)=

### The reference-angle equation
Only angle *differences* are physical, so the absolute angle level is a gauge freedom. It is fixed by pinning one bus: 

$$
\texttt{BUS\_TARGET\_PHI}:\quad \varphi_{\text{ref}}=0,
$$

 a single linear equation whose Jacobian row is a lone $1$ on the $\varphi_{\text{ref}}$ column. Without it the Jacobian would be singular (a whole null direction "add a constant to every angle").

(sec:slackeq)=

### The slack-bus equation
Total generation cannot be scheduled to exactly match load $+$ losses because losses are unknown a priori. One bus (or several, §{ref}`sec:multislack`) therefore has its active balance *relaxed*: `BUS_TARGET_P` is deactivated, so the solver is free to let whatever active power is needed flow out of that bus. After convergence the leftover, the *slack active power mismatch* $\Delta P_{\text{slack}}=\sum_{\text{slack}}P^{\text{spec}}_i-\sum_{\text{slack}}P_i(\xx)$, is the imbalance that the distributed-slack outer loop hands back to real generators (§{ref}`sec:distslack`).

(sec:3bus)=

## Worked matrix illustration: a 3-bus network
Consider three buses: bus 1 = slack/reference, bus 2 = PV (generator with voltage set-point), bus 3 = PQ (load), fully meshed by three lines. The unknowns are 

$$
\xx=[\,\varphi_2,\ \varphi_3,\ V_3\,]\T
$$

 ($V_1,\varphi_1$ fixed by slack/reference; $V_2$ fixed by the PV set-point; $\varphi_2,\varphi_3,V_3$ unknown). The active equations are 

$$
\ff(\xx)=
  \begin{bmatrix}
    P_2(\xx)\\[2pt] P_3(\xx)\\[2pt] Q_3(\xx)
  \end{bmatrix}
  =
  \begin{bmatrix}
    P_2^{\text{spec}}\\[2pt] P_3^{\text{spec}}\\[2pt] Q_3^{\text{spec}}
  \end{bmatrix}=\bm t,
$$

 where $P_2=\sum_{b\ni 2}P_{b,2}$ etc. The Jacobian $\Jmat=\partial\ff/\partial\xx$ is the dense $3\times3$ 

$$
\Jmat=
  \begin{bmatrix}
    \pdv{P_2}{\varphi_2} & \pdv{P_2}{\varphi_3} & \pdv{P_2}{V_3}\\[6pt]
    \pdv{P_3}{\varphi_2} & \pdv{P_3}{\varphi_3} & \pdv{P_3}{V_3}\\[6pt]
    \pdv{Q_3}{\varphi_2} & \pdv{Q_3}{\varphi_3} & \pdv{Q_3}{V_3}
  \end{bmatrix},
$$

 each entry assembled by summing the per-branch derivatives of {numref}`tab:derivs` over the branches touching the bus. Two structural facts are already visible and generalise to any network:

- **Sparsity**: $\partial P_2/\partial(\cdot)$ is non-zero only for variables of bus 2 and of buses directly connected to bus 2. In a large network each bus touches only a handful of neighbours, so $\Jmat$ is very sparse --- the reason a sparse solver (Chapter {ref}`ch:linsolve`) is essential.

- **Block structure**: ordering the equations as $(P\!-\!\text{rows},Q\!-\!\text{rows})$ and the variables as $(\varphi,V)$ reveals the classic $\bigl[\begin{smallmatrix}H&N\\J&L\end{smallmatrix}\bigr]$ blocks, $H=\partial P/\partial\varphi$, $N=\partial P/\partial V$, $J=\partial Q/\partial\varphi$, $L=\partial Q/\partial V$. The fast-decoupled approximation drops $N,J$; OLF's full Newton keeps them all.

The full anatomy of $\Jmat$ --- how OLF assembles it from equation terms, why it factorises the *transpose*, and its block sparsity --- is the subject of Chapter {ref}`ch:jac`, after we recall the Newton--Raphson method itself in Chapter {ref}`ch:nr`.
