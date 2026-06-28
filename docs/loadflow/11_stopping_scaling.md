(ch:stop)=

# Convergence control: stopping and step scaling
Two mechanisms govern *when* Newton stops and *how far* it is allowed to step: the **stopping criterion** and the **state-vector scaling**. Both are mathematically simple but decisive for robustness, and both are derived here exactly as coded.

(sec:stopcrit)=

## Stopping criterion
After each iteration OLF measures the mismatch vector $\bm g=\ff(\xx)-\bm t$ (§{ref}`sec:nriter`) and declares convergence when its Euclidean norm is small relative to the system size [`ac/solver/DefaultNewtonRaphsonStoppingCriteria.java`](https://github.com/powsybl/powsybl-open-loadflow/blob/main/src/main/java/com/powsybl/openloadflow/ac/solver/DefaultNewtonRaphsonStoppingCriteria.java): 

$$
\boxed{\;\norm{\bm g}_2 < \sqrt{\varepsilon^2\,m}
   \quad\Longleftrightarrow\quad
   \frac{\norm{\bm g}_2}{\sqrt m} < \varepsilon\;}
$$ (eq:stop)

 where $m$ is the number of equations and $\varepsilon$ is the per-equation tolerance (`convEpsPerEq`, default $10^{-4}\pu$, i.e. $0.01$ MVA on the $\SB=100$ MVA base). The right-hand form makes the meaning explicit: the *root-mean-square* mismatch per equation must fall below $\varepsilon$. Using an RMS rather than a max-norm means a few slightly-off equations are tolerated as long as the overall residual is tiny; dividing by $\sqrt m$ makes the threshold independent of network size.

### Per-equation-type criterion.

An alternative (`PerEquationTypeStoppingCriteria`) tests each *physical* residual against its own engineering tolerance --- active-power, reactive-power and voltage mismatches in their natural units --- which is stricter and more interpretable for reporting. The mismatch is always reported per type ($\max|\Delta P|,\max|\Delta Q|,\max|\Delta V|$) regardless of which criterion stops the loop [`ac/solver/AbstractAcSolver.java`](https://github.com/powsybl/powsybl-open-loadflow/blob/main/src/main/java/com/powsybl/openloadflow/ac/solver/AbstractAcSolver.java).

(sec:scaling)=

## State-vector scaling (globalisation)
The pure Newton step $\xx\leftarrow\xx-\Delta\xx$ can overshoot when the current guess is far from the solution, sending voltages or angles to nonphysical values and stalling or diverging the iteration. *State-vector scaling* multiplies the step by a factor $\mu\in(0,1]$, 

$$
\xx\leftarrow\xx-\mu\,\Delta\xx,
$$

 chosen to keep the iteration well-behaved. OLF implements three modes [`ac/solver/StateVectorScaling.java`](https://github.com/powsybl/powsybl-open-loadflow/blob/main/src/main/java/com/powsybl/openloadflow/ac/solver/StateVectorScaling.java).

### None

$\mu=1$: the textbook full step. Fastest when the start is good; the default in benign cases.

(sec:maxvchange)=

### Maximum voltage/angle change
[`ac/solver/MaxVoltageChangeStateVectorScaling.java`](https://github.com/powsybl/powsybl-open-loadflow/blob/main/src/main/java/com/powsybl/openloadflow/ac/solver/MaxVoltageChangeStateVectorScaling.java) Cap how much any voltage magnitude or angle may move in one step. With per-step limits $\Delta V_{\max}$ (default $0.1\pu$) and $\Delta\varphi_{\max}$ (default $10^\circ$), compute 

$$
\mu=\min\!\Biggl(1,\ \min_{i:\,|\Delta V_i|>\Delta V_{\max}}
        \frac{\Delta V_{\max}}{|\Delta V_i|},\
        \min_{i:\,|\Delta\varphi_i|>\Delta\varphi_{\max}}
        \frac{\Delta\varphi_{\max}}{|\Delta\varphi_i|}\Biggr),
$$

 and apply the *single* $\mu$ to the whole step (so the step's direction is preserved, only its length is clipped to the most-violating component). This tames the early iterations of a difficult case without changing the eventual quadratic endgame, where $\mu$ naturally returns to $1$.

(sec:linesearch)=

### Line search
[`ac/solver/LineSearchStateVectorScaling.java`](https://github.com/powsybl/powsybl-open-loadflow/blob/main/src/main/java/com/powsybl/openloadflow/ac/solver/LineSearchStateVectorScaling.java) A backtracking line search that *guarantees the residual does not increase*. Starting from $\mu=1$, while the new norm $\norm{\bm g(\xx-\mu\Delta\xx)}$ is not smaller than the previous norm, shrink 

$$
\mu \leftarrow \mu_{\text{fold}}^{-i},\qquad \mu_{\text{fold}}=\tfrac{4}{3},\quad i=1,2,\dots,
$$

 up to a maximum number of trials (default $10$). Each trial recomputes the mismatch at the trial point and its norm; the first $\mu$ that reduces the norm is accepted. This is the most robust mode: it turns NR into a *descent* method on $\norm{\bm g}$, sacrificing a little speed for near-global convergence on ill-conditioned networks.

:::{admonition} Remark - Where scaling sits in the loop
:class: seealso
Scaling acts in two places (§{ref}`sec:nriter`): `apply` reshapes $\Delta\xx$ *before* the state update (max-change mode clips it there; line search merely records it), and `applyAfter` runs the backtracking *after* the tentative update, using the freshly recomputed mismatch. The returned test result feeds straight into the stopping criterion {eq}`eq:stop`.

:::

## Solver outcomes

A Newton run ends in one of [`ac/solver/AcSolverStatus.java`](https://github.com/powsybl/powsybl-open-loadflow/blob/main/src/main/java/com/powsybl/openloadflow/ac/solver/AcSolverStatus.java): `CONVERGED` (criterion {eq}`eq:stop` met), `MAX_ITERATION_REACHED`, `SOLVER_FAILED` (singular Jacobian, §{ref}`ch:linsolve`), or `UNREALISTIC_STATE` (a bus voltage outside the plausible band, checked by the engine, §{ref}`sec:driver`). Only `CONVERGED` lets the outer loops proceed; the others abort or trigger a robustness fallback.
