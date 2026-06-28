(ch:adjoint)=

# The fundamental identity
This chapter derives the one equation that underlies every sensitivity OLF computes. It turns a derivative of the *solved* state into a single linear solve against the already-factorised Jacobian, plus a dot product. Everything in the DC and AC chapters is an application of it.

## Implicit differentiation of the load flow

Write the converged load-flow system ({ref}`part:ac`, eq. {eq}`eq:system`) with the input variable $p$ shown explicitly: 

$$
\bm g(\xx,p)=\ff(\xx)-\bm t(p)=\bm 0 .
$$ (eq:lfp)

 At the solution $\xx^\star(p)$ this holds for all $p$, so differentiate both sides with respect to $p$ (chain rule, treating $\xx^\star$ as a function of $p$): 

$$
\pdv{\ff}{\xx}\,\pdv{\xx^\star}{p}-\pdv{\bm t}{p}=\bm 0
  \quad\Longrightarrow\quad
  \bm J\,\pdv{\xx^\star}{p}=\pdv{\bm t}{p}=:\bm e_p,
$$

 with $\bm J=\partial\ff/\partial\xx$ the load-flow Jacobian. Hence the **state sensitivity** 

$$
\boxed{\;\pdv{\xx^\star}{p}=\bm J^{-1}\bm e_p=:\bm s_p\;}
$$ (eq:states)

 is obtained by *one linear solve* with right-hand side $\bm e_p$ --- the perturbation of the targets/equations caused by the variable $p$ (the `fillRhs` vectors of §{ref}`ch:factors`). No new factorisation: $\bm J$ was already factorised by the load flow.

(sec:dotproduct)=

## The function sensitivity: a dot product
A function $h(\xx)$ (a branch flow, a voltage) has total derivative, again by the chain rule, 

$$
\boxed{\;
  \pdv{h}{p}=\Bigl(\pdv{h}{\xx}\Bigr)^{\!\top}\pdv{\xx^\star}{p}
   =\Bigl(\pdv{h}{\xx}\Bigr)^{\!\top}\bm s_p
   =\sum_{v}\pdv{h}{v}\,s_{p,v}.\;}
$$ (eq:funcsens)

 The row vector $\partial h/\partial\xx$ is non-zero only at the few state variables the function actually depends on (the two end-bus voltages/angles of a branch), so the dot product is cheap. In OLF this is precisely the `calculateSensi(states, group)` method of the function's equation term, which reads $\bm s_p$ at the rows of its variables and contracts with its analytic derivatives ({ref}`part:ac`, §{ref}`sec:branchderiv`).

(sec:adjoint)=

## Forward vs adjoint, and why OLF solves the transpose
Equation {eq}`eq:funcsens` can be associated two ways: 

$$
\pdv{h}{p}=\underbrace{\Bigl(\pdv{h}{\xx}\Bigr)^{\!\top}\bigl(\bm J^{-1}\bm e_p\bigr)}_{\text{forward: solve per variable }p}
            =\underbrace{\Bigl(\bm J^{-\top}\pdv{h}{\xx}\Bigr)^{\!\top}\bm e_p}_{\text{adjoint: solve per function }h}.
$$

 *Forward* solves $\bm J\bm s_p=\bm e_p$ once per *variable* and reuses $\bm s_p$ for all functions; *adjoint* solves once per *function* and reuses it for all variables. OLF uses the forward association --- one solve per variable group (§{ref}`sec:groups`) --- because sensitivity requests typically have far fewer distinct variables than (function$\times$variable) pairs.

Concretely, OLF stores the Jacobian *transposed* ({ref}`part:ac`, §{ref}`transposed Jacobian storage <sec:transpose>`): the stored matrix has entry $(\text{row}=v,\ \text{col}=\text{eq})=
\partial(\text{eq})/\partial v$. Solving $\bm J\bm s=\bm e$ against the mathematical $\bm J$ is therefore done by calling `solveTransposed` on the stored matrix [`sensi/*SensitivityAnalysis.java`](https://github.com/powsybl/powsybl-open-loadflow/blob/main/src/main/java/com/powsybl/openloadflow/sensi/*SensitivityAnalysis.java): 

$$
\texttt{j.solveTransposed(rhs)}\quad\Longleftrightarrow\quad \bm J\,\smat=\bm E,
$$

 where the columns of $\bm E$ are the per-variable right-hand sides and the columns of the result $\smat$ are the state sensitivities $\bm s_p$. A *dense* right-hand-side matrix $\bm E$ ($n_{\text{eq}}\times n_{\text{groups}}$) is solved in one shot: $\bm J$ is factorised once and all columns are back-substituted --- a multi-RHS solve.

(sec:slacksub)=

## The slack/GLSK subtraction
An active-power injection cannot be added at one bus alone: the network must stay balanced ({ref}`part:ac`, §{ref}`sec:slackeq`). So an `INJECTION_ACTIVE_POWER` variable injects $+1$ at the variable bus *and* withdraws it across the slack-distribution participants. The right-hand-side column is built as `sensi/AbstractSensitivityAnalysis.java (fillRhs)` 

$$
\bm e_p = \bm 1_{\text{bus }p} \;-\; \sum_{i\in\text{participants}}\kappa_i\,\bm 1_{\text{bus }i},
  \qquad \sum_i\kappa_i=1,
$$

 so the column sums to zero except for the $+1$ at bus $p$. The participation factors $\kappa_i$ are exactly those of the distributed-slack outer loop ({ref}`part:ac`, §{ref}`sec:distslack`); with no distributed slack the single slack bus takes $\kappa=1$. The resulting sensitivity is thus the response to a *balanced* injection shift --- the physically meaningful PTDF. The participation set is read from the *initial* network state, for the balance types `PROPORTIONAL_TO_GENERATION_P_MAX`, `PROPORTIONAL_TO_GENERATION_P` and `PROPORTIONAL_TO_LOAD`.

Building the subtraction into the right-hand side is equivalent to a *post-correction* of the unbalanced factors: solving with a bare $\bm e_p=\bm 1_p$ for both the requested bus $p$ and every participant $g$, the balanced factor on any function is 

$$
s^{c}_{p}=s_{p}-\sum_{g\in U}\kappa_g\,s_{g},
$$ (eq:slackcorr)

 with $U$ the participants and $\kappa_g$ their factors. OLF uses the RHS form above (one solve per variable instead of one per participant), but {eq}`eq:slackcorr` is the same quantity and is how the correction is usually written.

## Scaling back to engineering units

The raw factor {eq}`eq:funcsens` is in per-unit; it is unscaled to the reported units by `sensi/AbstractSensitivityAnalysis.java (unscaleSensitivity)` 

$$
\text{reported} = \pdv{h}{p}\cdot\frac{\text{functionBase}}{\text{variableBase}},
$$

 e.g. MW-per-MW for a PTDF (dimensionless), or A-per-degree for a current/phase factor (the $\mathrm{rad}(1^\circ)$ already in the RHS makes the variable base $1$). With this, the entire computation is: build $\bm E$, one `solveTransposed`, a dot product per factor, one rescale.
