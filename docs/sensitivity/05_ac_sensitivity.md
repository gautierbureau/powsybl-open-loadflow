(ch:acsensi)=

# AC sensitivities
AC sensitivities use the *same* fundamental identity (Chapter {ref}`ch:adjoint`) --- one linear solve against the already-factorised Jacobian, then a dot product --- but with the **full, converged AC Jacobian** in place of the constant DC $\bm B'$. They are therefore *operating-point dependent* (they hold only at the state $\xx^\star$ at which they were computed) and they capture the voltage-magnitude and reactive effects that the DC model throws away. This chapter explains, step by step, exactly what is reused from the load flow, what is different from the DC computation of Chapter {ref}`ch:dcsensi`, and how the voltage and reactive sensitivities --- the part with no DC analogue --- arise.

## What changes relative to DC

The skeleton "build the right-hand side $\bm E$, one `solveTransposed` ($\bm J\smat=\bm E$), one `calculateSensi` dot product per factor, then unscale" is *identical* to DC. Three things change, and they are exactly the three ingredients of the fundamental identity {eq}`eq:states`--{eq}`eq:funcsens`:

1.  **The matrix $\bm J$.** It is the AC Jacobian $\bm J=\partial\ff/\partial\xx$ evaluated at the solved state $\xx^\star$ ({ref}`part:ac`, Ch. {ref}`ch:jac`), *already factorised* by the load flow. OLF asserts that a load flow ran first and reuses that factorisation untouched [`sensi/AcSensitivityAnalysis.java`](https://github.com/powsybl/powsybl-open-loadflow/blob/main/src/main/java/com/powsybl/openloadflow/sensi/AcSensitivityAnalysis.java). Unlike $\bm B'$, $\bm J$ is not constant: it depends on the voltages and angles, so a different operating point gives different sensitivities.

2.  **The function row $\partial h/\partial\xx$.** For a branch active flow it is the *full* AC derivative row, with up to six non-zero entries 

$$
\Bigl(\pdv{P}{\varphi_1},\ \pdv{P}{\varphi_2},\
                    \pdv{P}{V_1},\ \pdv{P}{V_2},\
                    \pdv{P}{\alpha},\ \pdv{P}{\rho}\Bigr)
$$

 read straight from {ref}`part:ac`'s {numref}`tab:derivs` --- not the two constants $\pm\beta_\ell$ of DC {eq}`eq:dcderiv`. In particular the magnitude entries $\partial P/\partial V$ are generally non-zero, which is why AC "PTDFs" react to the local voltage profile.

3.  **The variable and function catalogues.** AC additionally supports `BUS_TARGET_VOLTAGE` and `INJECTION_REACTIVE_POWER` *variables*, and `BUS_VOLTAGE`, `BUS_REACTIVE_POWER` and branch `CURRENT`/`REACTIVE_POWER` *functions* (Chapter {ref}`ch:factors`). All of these involve $V$ and $Q$, which the DC state vector does not contain.

::: center
|  | DC | AC |
|:---|:---|:---|
| matrix | constant $\bm B'$ (Laplacian) | converged $\bm J(\xx^\star)$ |
| function row | two constants $\pm\beta_\ell$ | up to six analytic entries |
| state variables | angles $\bm\varphi$ only | $(\bm V,\bm\varphi)$ |
| operating point | irrelevant (exact, constant) | required (local linearisation) |
| contingency | Woodbury low-rank update | full re-solve (§{ref}`sec:accont`) |
:::

(sec:acstructure)=

## The AC Jacobian and the function rows
Recall the converged system ({ref}`part:ac`) $\ff(\xx)=\bm t$ with state $\xx=(\bm V,\bm\varphi)$ and one balance equation per bus (`BUS_TARGET_P`, `BUS_TARGET_Q`, plus the voltage/slack rows). The state sensitivity to a variable $p$ is the single solve {eq}`eq:states`, $\bm J\,\bm s_p=\bm e_p$, where $\bm e_p$ is the perturbation $p$ makes to the targets. Because $\xx$ now carries voltage magnitudes, $\bm s_p$ has *both* a $\partial\bm V/\partial p$ block and a $\partial\bm\varphi/\partial p$ block: a single solve already contains the angle *and* the magnitude response.

The function sensitivity is the dot product {eq}`eq:funcsens` of the function's analytic derivative row with $\bm s_p$. Spelling it out for a branch active power $P_\ell$ on branch $\ell=(i,j)$ and an injection variable at bus $k$: 

$$
\pdv{P_\ell}{P_k}
   = \underbrace{\pdv{P_\ell}{\varphi_i}s^{\varphi}_{k,i}
                +\pdv{P_\ell}{\varphi_j}s^{\varphi}_{k,j}}_{\text{angle response (the only DC terms)}}
   + \underbrace{\pdv{P_\ell}{V_i}s^{V}_{k,i}
                +\pdv{P_\ell}{V_j}s^{V}_{k,j}}_{\text{magnitude response (AC-only)}} ,
$$ (eq:acptdf)

 where $s^{\varphi}_{k,\cdot},s^{V}_{k,\cdot}$ are the angle/magnitude rows of $\bm s_k=\bm J^{-1}\bm e_k$. The first bracket is the DC PTDF {eq}`eq:ptdf`; the second is the correction the AC model adds. In OLF the whole right-hand side of {eq}`eq:acptdf` is one call to the branch term's `calculateSensi(states, group)`, which reads $\bm s_k$ at the rows of its own four state variables and contracts with the derivatives it already computed during the load flow ({ref}`part:ac`, §{ref}`sec:branchderiv`); the function never needs to know *which* variable produced the column.

### The whole thing as one matrix product.

It is worth drawing the computation as an explicit "row $\times$ inverse-Jacobian $\times$ column" sandwich, because that picture *is* the data flow in the code. For a single factor (function $h$, variable $p$), 

$$
\pdv{h}{p}=
  \begin{bmatrix}
    \pdv{h}{V_1} & \cdots & \pdv{h}{V_n} &
    \pdv{h}{\varphi_1} & \cdots & \pdv{h}{\varphi_n}
  \end{bmatrix}
  \;\bm J^{-1}\;
  \begin{bmatrix}
    \pdv{P_1}{p}\\[2pt] \vdots\\ \pdv{P_n}{p}\\[4pt]
    \pdv{Q_1}{p}\\[2pt] \vdots\\ \pdv{Q_n}{p}
  \end{bmatrix} ,
$$ (eq:sensimatrix)

 where the **row** is the function derivative $\partial h/\partial\xx$ (supplied by `calculateSensi`, and non-zero only at the few buses $h$ touches), the **column** is the right-hand side $\bm e_p=\partial\bm t/\partial p$ with its active- and reactive-target blocks (built by `fillRhs`, §{ref}`sec:acrhs`), and $\bm J^{-1}$ is applied by the factorised solve. Grouping the middle-and-right factors, $\bm J^{-1}\bm e_p=\bm s_p$, recovers the state sensitivity {eq}`eq:states`; reading left-to-right instead is exactly the forward association of §{ref}`sec:adjoint`.

Stacking many functions as rows and many variables as columns turns {eq}`eq:sensimatrix` into the *entire* sensitivity matrix in one expression: 

$$
\underbrace{\begin{bmatrix}
    \bigl(\partial h_1/\partial\xx\bigr)^{\!\top}\\[2pt] \vdots\\[2pt]
    \bigl(\partial h_k/\partial\xx\bigr)^{\!\top}
  \end{bmatrix}}_{k\ \text{functions}}
  \;\bm J^{-1}\;
  \underbrace{\begin{bmatrix} \bm e_{p_1} & \cdots & \bm e_{p_m}\end{bmatrix}}_{\bm E:\ m\ \text{variable groups}}
  =\Bigl[\,\pdv{h_a}{p_b}\,\Bigr]_{a,b} .
$$ (eq:sensimatfull)

 OLF computes the middle block $\smat=\bm J^{-1}\bm E$ *once* --- one factorisation, $m$ back-substitutions over the dense $\bm E$, the cost driver of §{ref}`sec:groups` --- and then reads off every $(a,b)$ entry as a cheap row--column dot product. This is the same two-level picture --- a single function, then a stack of functions --- one sketches on a whiteboard, made literal.

As in DC, OLF stores $\bm J$ transposed and so realises the mathematical solve $\bm J\smat=\bm E$ by calling `solveTransposed` on the stored matrix (§{ref}`transposed Jacobian storage <sec:transpose>`); the columns of the dense $\bm E$ are the per-variable right-hand sides and are back-substituted in one shot.[^1]

(sec:acrhs)=

## Right-hand sides for the AC variable types
The only genuinely new code relative to DC is how each AC variable fills its column of $\bm E$ `sensi/AbstractSensitivityAnalysis.java (SingleVariableFactorGroup.fillRhs)`. Each variable perturbs one (or a few) target rows by a unit amount:

::: center
| Variable | RHS row(s) it writes | value |
|:---|:---|:---|
| `INJECTION_ACTIVE_POWER` | bus `BUS_TARGET_P` $+$ slack participants | $+1,\ -\kappa_i$ |
| `INJECTION_REACTIVE_POWER` | bus `BUS_TARGET_Q` | $+1$ |
| `BUS_TARGET_VOLTAGE` | calculated-$V$ (`BUS_TARGET_V`) equation | $+1$ |
| `TRANSFORMER_PHASE` | branch `BRANCH_TARGET_ALPHA1` equation | $\mathrm{rad}(1^\circ)$ |
:::

Three points are worth making explicit, because they encode real physics:

- **Active injection carries a slack term, reactive does not.** An active-power change must be balanced globally (generation $=$ load $+$ losses), so `INJECTION_ACTIVE_POWER` writes $+1$ at the variable bus and withdraws it across the slack-distribution participants $-\kappa_i$ (§{ref}`sec:slacksub`); the slack bus, whose $P$ equation is relaxed, is skipped. Reactive power has *no* such global constraint --- it is produced and absorbed locally --- so `INJECTION_REACTIVE_POWER` writes a bare $+1$ in the bus reactive balance and nothing else `sensi/AbstractSensitivityAnalysis.java (addBusInjection, addBusReactiveInjection)`.

- **A row is written only if its equation is active.** A reactive injection at a PV bus, or a target-voltage variable at an uncontrolled bus, lands on an *inactive* equation; `fillRhs` then leaves the column empty and the sensitivity is reported as $0$. This is the algebraic statement that "you cannot ask how voltage responds to a $Q$ injection at a bus whose voltage is already fixed."

- **The $\mathrm{rad}(1^\circ)$ factor** on a phase variable bakes the degree$\to$radian conversion into the RHS, so the reported $\partial P/\partial\alpha$ is already "per degree" and needs no rescaling (§{ref}`ch:factors`).

(sec:acphaserhs)=

### Explicit right-hand side for a phase-shifter variable
The single `BRANCH_TARGET_ALPHA1` entry in the table is a convenient abbreviation: a $1^\circ$ tap on the phase shifter of branch $(i,j)$ does not perturb one target but *four*, because that branch's own active and reactive flows sit in the balances of both end buses. Writing the per-degree active and reactive perturbations 

$$
\alpha=-\,\rho_i v_i\,Y\,\rho_j v_j\cos\theta\,\frac{\pi}{180},\qquad
  \beta = \rho_i v_i\,Y\,\rho_j v_j\sin\theta\,\frac{\pi}{180},
$$ (eq:phasealphabeta)

 the right-hand-side column carries (all other entries zero) 

$$
(\bm e_p)_{P\text{-}i}=+\alpha,\quad
  (\bm e_p)_{P\text{-}j}=-\alpha,\quad
  (\bm e_p)_{Q\text{-}i}=+\beta,\quad
  (\bm e_p)_{Q\text{-}j}=-\beta,
$$

 where the two reactive entries are present *only* if the corresponding bus is PQ (a PV or slack bus has no active reactive-balance row to perturb). Here $Y,\Xi$ are the series-admittance modulus and angle, $\rho_i,A_i$ the ratio and phase-shift on side $i$, and $\theta=\Xi-A_i+A_j-\varphi_i+\varphi_j$ --- the same $\pi$-model constants as {ref}`part:ac`, §{ref}`sec:branchderiv` (there $\xi\equiv\Xi$, $y\equiv Y$, $\alpha\equiv A$). The opposite signs apply when the shifter is on side $j$.

## The computation, line by line

[`sensi/AcSensitivityAnalysis.java`](https://github.com/powsybl/powsybl-open-loadflow/blob/main/src/main/java/com/powsybl/openloadflow/sensi/AcSensitivityAnalysis.java)

1.  run the AC load flow to convergence --- this fixes $\xx^\star$ and leaves the factorised $\bm J$ in the context;

2.  $\bm E=\texttt{initFactorsRhs}(\dots)$ --- one dense column per variable *group* (§{ref}`sec:groups`), each built by the `fillRhs` of §{ref}`sec:acrhs`, including the slack/GLSK subtraction of §{ref}`sec:slacksub`;

3.  $\texttt{j.solveTransposed}(\bm E)$ --- overwrites $\bm E$ in place with the state sensitivities $\smat=[\bm s_p]$, i.e. solves $\bm J\smat=\bm E$ for every column at once (one factorisation, many back-substitutions);

4.  for each factor, $\partial h/\partial p=
            h.\texttt{calculateSensi}(\smat,\text{group})$ --- the dot product {eq}`eq:funcsens`/{eq}`eq:acptdf` over the function term's own variables;

5.  the function reference (the base-case value reported next to each sensitivity) is $h.\texttt{eval}()$ at $\xx^\star$ `sensi/AcSensitivityAnalysis.java (setFunctionReferences)`;

6.  unscale each raw per-unit factor to engineering units by the function/variable base ratio (§{ref}`ch:adjoint`).

Factors whose variable left the main connected component but whose function did not are flagged `VALID_ONLY_FOR_FUNCTION`: their sensitivity is known to be $0$ and only the reference is written `sensi/AcSensitivityAnalysis.java (calculateSensitivityValues)`.

(sec:acvq)=

## Voltage and reactive sensitivities
The quantities DC cannot provide fall out of the same solve, because the AC state already contains $\bm V$. Three are worth naming because OLF uses them directly.

- **Voltage propagation $\partial V_m/\partial V^{\text{spec}}_k$.** For a `BUS_TARGET_VOLTAGE` variable at a voltage-controlled bus $k$, the RHS is a unit in $k$'s calculated-$V$ equation (§{ref}`sec:acrhs`). Solving $\bm J\bm s=\bm e_k$ and dotting the result with another bus $m$'s calculated-voltage row gives $\partial V_m/\partial V^{\text{spec}}_k$ --- how much raising the set-point of generator $k$ by $1$ p.u. moves the voltage at bus $m$. It decays with electrical distance and is the natural measure of a generator's "voltage reach".

- **The $Q$--$V$ stiffness $\partial V/\partial Q$.** For an `INJECTION_REACTIVE_POWER` variable the unit sits in the bus reactive balance; dotting the solved state with that bus's voltage row gives $\partial V/\partial Q$, the local sensitivity of voltage to reactive injection. Its reciprocal is the short-circuit-like *reactive stiffness* of the bus: a small $\partial V/\partial Q$ means a strong bus (lots of reactive support nearby), a large one means a weak bus where voltage sags sharply under reactive load. This is precisely the signal voltage-stability screening looks for.

- **Control gains $\partial V/\partial\rho$ and $\partial V/\partial b$.** The incremental transformer- and shunt-voltage outer loops of the load flow ({ref}`part:ac`, §{ref}`sec:tfovc`--§{ref}`sec:shuntvc`) must know how much a tap or a shunt section moves the controlled voltage before they decide their step. That gain is exactly {eq}`eq:funcsens` with a ratio (resp. susceptance) RHS and a voltage function row --- the *same* kernel, run inside the load flow rather than exposed to the user (see the closing remark).

If a requested `BUS_TARGET_VOLTAGE` variable is itself controlled by a ratio tap changer, OLF turns transformer voltage control on for the solve and rebuilds the equation system in its pre-rounding form, so the differentiated system is the continuous one the control actually acts on `sensi/AcSensitivityAnalysis.java (hasTransformerBusTargetVoltage)`.

(sec:accurrent)=

## Current-magnitude sensitivities, and the direct term
A `BRANCH_CURRENT` function needs more than {numref}`tab:derivs`: the current magnitude is a *nonlinear* function of the Cartesian current, so its derivative row is assembled by a chain rule through $\Re(\Ic)$ and $\Im(\Ic)$ [`ac/equations/ClosedBranchSide1CurrentMagnitudeEquationTerm.java`](https://github.com/powsybl/powsybl-open-loadflow/blob/main/src/main/java/com/powsybl/openloadflow/ac/equations/ClosedBranchSide1CurrentMagnitudeEquationTerm.java). Using the $\pi$-model constants of §{ref}`sec:acphaserhs` and the scale $N_f=1000/\sqrt3$ (per-unit current $\to$ amperes, carrying the three-phase $\sqrt3$), the current leaving side $i$ of branch $(i,j)$ is 

$$
\begin{aligned}
    w_i&=\rho_i v_i, & w_j&=Y\rho_j v_j, & \gamma&=\Xi-A_i+A_j+\varphi_j,\\
    \Re(\Ic)&=N_f\,\rho_i\bigl(w_i(G_i\cos\varphi_i-B_i\sin\varphi_i+Y\sin(\Xi+\varphi_i))-w_j\sin\gamma\bigr),\\
    \Im(\Ic)&=N_f\,\rho_i\bigl(w_i(G_i\sin\varphi_i+B_i\cos\varphi_i-Y\cos(\Xi+\varphi_i))+w_j\cos\gamma\bigr),\\
    \abs{\Ic}&=\sqrt{\Re(\Ic)^2+\Im(\Ic)^2}.
  \end{aligned}
$$

 Differentiating the Cartesian parts gives their eight state derivatives, 

$$
\begin{aligned}
    \pdv{\Re(\Ic)}{v_i}&=N_f\rho_i^2(G_i\cos\varphi_i-B_i\sin\varphi_i+Y\sin(\Xi+\varphi_i)),
      & \pdv{\Re(\Ic)}{v_j}&=-N_f\rho_i Y\rho_j\sin\gamma,\\
    \pdv{\Re(\Ic)}{\varphi_i}&=N_f\rho_i w_i(-G_i\sin\varphi_i-B_i\cos\varphi_i+Y\cos(\Xi+\varphi_i)),
      & \pdv{\Re(\Ic)}{\varphi_j}&=-N_f\rho_i w_j\cos\gamma,\\
    \pdv{\Im(\Ic)}{v_i}&=N_f\rho_i^2(G_i\sin\varphi_i+B_i\cos\varphi_i-Y\cos(\Xi+\varphi_i)),
      & \pdv{\Im(\Ic)}{v_j}&=N_f\rho_i Y\rho_j\cos\gamma,\\
    \pdv{\Im(\Ic)}{\varphi_i}&=N_f\rho_i w_i(G_i\cos\varphi_i-B_i\sin\varphi_i+Y\sin(\Xi+\varphi_i)),
      & \pdv{\Im(\Ic)}{\varphi_j}&=-N_f\rho_i w_j\sin\gamma,
  \end{aligned}
$$

 and the function row is their $\abs{\Ic}$-normalised combination --- the chain rule on $\abs{\Ic}=\sqrt{\Re^2+\Im^2}$ --- for each state $u\in\{v_i,v_j,\varphi_i,\varphi_j\}$: 

$$
\pdv{\abs{\Ic}}{u}=\frac{\Re(\Ic)\,\partial_u\Re(\Ic)+\Im(\Ic)\,\partial_u\Im(\Ic)}{\abs{\Ic}},
$$ (eq:currow)

 all other entries zero. These four numbers are the current function's $\partial h/\partial\xx$ row in {eq}`eq:sensimatrix`; the solve and the dot product are unchanged.

### The direct term $g_p$.

The chain rule {eq}`eq:funcsens` silently assumed the function depends on the variable *only* through the state. That is true for an injection, but not when the monitored branch is the one carrying the phase shifter: then $p=\alpha$ enters $h$ explicitly and a *direct term* $g_p$ adds to the dot product, 

$$
\pdv{h}{p}=g_p+\Bigl(\pdv{h}{\xx}\Bigr)^{\!\top}\bm s_p,
  \qquad
  g_p=\begin{cases}
    \rho_i v_i\,Y\rho_j v_j\cos\theta\,\dfrac{\pi}{180} & \text{(power function)},\\[10pt]
    -\dfrac{\Re(\Ic)\,\partial_{\varphi_j}\Re(\Ic)+\Im(\Ic)\,\partial_{\varphi_j}\Im(\Ic)}{\abs{\Ic}}\,\dfrac{\pi}{180} & \text{(current function)},
  \end{cases}
$$

 with the opposite sign when the shifter sits on side $j$. For an injection variable $g_p=0$, which is why {eq}`eq:funcsens` and {eq}`eq:sensimatrix` could omit it.

(sec:accont)=

## Per-contingency AC sensitivities
Unlike DC (Chapter {ref}`ch:woodbury`), AC has *no* low-rank short-cut: a contingency changes the network nonlinearly --- voltages, losses and the Jacobian all move --- so the elegant Woodbury rank-one update does not apply. For each contingency OLF therefore does the expensive thing `sensi/AcSensitivityAnalysis.java (calculatePostContingencySensitivityValues)`:

1.  if the contingency loses active power and slack is distributed, run the active-power distribution on the post-contingency network first, so the balance is restored before differentiating;

2.  *re-run the AC load flow* on the modified network to a new converged state $\xx^{\star}_{c}$ and a new factorised Jacobian $\bm J_c$ (if it fails to converge, the contingency is written as `FAILURE` and skipped);

3.  if any GLSK (multi-variable) bus was lost, rescale the remaining weights so the key still sums correctly over the surviving buses `sensi/AcSensitivityAnalysis.java (rescaleGlsk)`;

4.  rebuild $\bm E$ on the post-contingency equation system, one `solveTransposed` against $\bm J_c$, and the usual `calculateSensi` dot products.

The base-case factorisation cannot be reused, and step (2) is a full nonlinear solve --- which is why AC contingency sensitivity costs roughly "one load flow per contingency" and is far more expensive than the single back-substitution DC needs. For large $N\!-\!1$ screening this is exactly why DC-with-Woodbury (Chapter {ref}`ch:woodbury`) is the default workhorse and AC is reserved for the cases where the reactive/voltage coupling genuinely matters.

:::{admonition} Remark - Same kernel, three users
:class: seealso
The triple "build RHS, `solveTransposed`, `calculateSensi`" is the single computational kernel shared by (i) AC sensitivity analysis, (ii) DC sensitivity analysis, and (iii) every incremental control outer loop of the load flow. Recognising this unifies a large part of the codebase: the outer loops are sensitivity analyses run in a loop, and the user-facing sensitivity analysis is one outer-loop step exposed through the public API.

:::

:::{admonition} Remark - Why operating-point dependence is a feature, not a bug
:class: seealso
Because $\bm J$ and the function rows are evaluated at $\xx^\star$, an AC sensitivity is the *exact* first-order response of the true nonlinear network *at that point* --- it already knows the current loading, voltage profile and which generators are at their reactive limits. The price is that it is only valid locally: move far from $\xx^\star$ (a large injection shift, a topology change) and it must be recomputed. DC trades this fidelity for a constant, reusable matrix.

:::

[^1]: The transposed solve mirrors the adjoint formulation; see <https://people.montefiore.uliege.be/vct/elec0029/lf.pdf>, eq. (32).
