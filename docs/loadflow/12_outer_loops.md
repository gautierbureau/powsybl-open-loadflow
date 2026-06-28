(ch:outer)=

(sec:outer)=
# The outer loops: controls
The Newton solver of Chapters {ref}`ch:nr`--{ref}`ch:jac` solves a *fixed* nonlinear system. But a real load flow must also honour *controls* that are discrete (tap positions), *limited* (generator reactive capability), or *global* (slack distribution). These cannot be expressed as smooth equations solvable in one Newton pass, so OLF wraps Newton in **outer loops**: after each converged inner solve, each outer loop inspects the solution, adjusts the control, and asks Newton to re-solve. This chapter derives every AC outer loop relevant to a load flow.

(sec:driver)=

## The outer-loop driver
[`ac/AcloadFlowEngine.java`](https://github.com/powsybl/powsybl-open-loadflow/blob/main/src/main/java/com/powsybl/openloadflow/ac/AcloadFlowEngine.java) The engine runs an initial Newton solve, then repeatedly sweeps the ordered list of outer loops. Each loop's `check` returns a status:

- `STABLE` --- nothing to change, the control is already satisfied;

- `UNSTABLE` --- the loop changed the network/equations; Newton must re-run (warm-started from the previous solution, §{ref}`sec:dcinit`);

- `FAILED` --- the control cannot be satisfied; abort.

The structure is a fixed point: keep sweeping until *all* loops report `STABLE` in a sweep (no further Newton iterations were needed), or a cap `maxOuterLoopIterations` is hit. The loops are *nested*: the first in the list is innermost (driven to its own fixed point before the next is checked), so e.g. reactive-limit switching stabilises inside each slack-distribution step. The overall picture is {numref}`fig:flow`.

$$
\boxed{
\begin{aligned}
&\xx \leftarrow \text{Newton}(\xx_0)\\
&\textbf{repeat}\\
&\quad \textbf{for each } \text{outer loop } \ell:\\
&\qquad s \leftarrow \ell.\text{check}(\xx)\\
&\qquad \textbf{if } s=\text{UNSTABLE}: \ \xx \leftarrow \text{Newton}(\xx)\ \text{(warm start)}\\
&\textbf{until all } s=\text{STABLE} \ \text{or iteration cap.}
\end{aligned}}
$$

(sec:distslack)=

## Distributed slack / active-power balance
`ac/outerloop/DistributedSlackOuterLoop.java, network/util/ActivePowerDistribution.java`

### The problem

The slack bus absorbs the network active imbalance (§{ref}`sec:slackeq`); after the first solve it carries a mismatch $\Delta P_{\text{slack}}=\sum_{\text{slack}}\!\bigl(P^{\text{spec}}_i-P_i(\xx)\bigr)$ that physically corresponds to "unscheduled" generation. Leaving it all on one bus is unrealistic. The distributed-slack loop re-allocates $\Delta P_{\text{slack}}$ across participating generators (or loads) according to a chosen rule, then lets Newton re-solve.

### Participation factors

Each participating element $e$ gets a participation factor $\kappa_e\ge0$ depending on the balance type [`network/util/GenerationActivePowerDistributionStep.java`](https://github.com/powsybl/powsybl-open-loadflow/blob/main/src/main/java/com/powsybl/openloadflow/network/util/GenerationActivePowerDistributionStep.java): 

$$
\kappa_e=
  \begin{cases}
    P_{\max,e}/\text{droop}_e & \text{proportional to } P_{\max} \text{ (droop)},\\
    |P_{\text{target},e}| & \text{proportional to target } P,\\
    \text{participationFactor}_e & \text{explicit factors},\\
    \max(0,\ P_{\max,e}-P_{\text{target},e})\ \text{or}\ \max(0,\ P_{\text{target},e}-P_{\min,e}) & \text{remaining margin (sign of }\Delta P\text{)},
  \end{cases}
$$

 or, for load-based balancing, proportional to each load's $P$ (optionally keeping power factor constant). The factors are normalised so $\sum_e\kappa_e=1$.

### The iterative dispatch

The mismatch is shared in proportion to $\kappa_e$, but a generator that would cross a limit ($P_{\min}$, $P_{\max}$, or the forbidden sign change at $0$) is clamped *and removed* from the pool; the unallocated remainder is then re-shared among the rest. Formally, at dispatch iteration $t$ with remaining mismatch $\Delta^{(t)}$ and active pool $\mathcal A^{(t)}$: 

$$
P_{\text{target},e}\leftarrow \operatorname{clip}\!\bigl(P_{\text{target},e}+\Delta^{(t)}\hat\kappa_e,\ P_{\min,e},P_{\max,e}\bigr),
  \qquad \hat\kappa_e=\frac{\kappa_e}{\sum_{e'\in\mathcal A^{(t)}}\kappa_{e'}},
$$

 removing any clipped $e$ from $\mathcal A^{(t+1)}$ and updating $\Delta^{(t+1)}$ by what was actually placed. The dispatch repeats until $|\Delta^{(t)}|<\varepsilon_P$ ($\varepsilon_P=10^{-5}\pu$) or the pool empties. `network/util/ActivePowerDistribution.java (run)`

### Outcome and failure handling

If injections moved appreciably the loop returns `UNSTABLE` (Newton re-runs with the new targets, which changes losses, which changes $\Delta P_{\text{slack}}$, hence the iteration). If the pool empties with a residual, a configurable policy decides: leave it on the slack bus, distribute it onto the reference generator, or `FAIL`. The total moved power is reported as the load flow's "distributed active power".

(sec:qlim)=

## Reactive limits: PV $\leftrightarrow$ PQ switching
[`ac/outerloop/ReactiveLimitsOuterLoop.java`](https://github.com/powsybl/powsybl-open-loadflow/blob/main/src/main/java/com/powsybl/openloadflow/ac/outerloop/ReactiveLimitsOuterLoop.java)

A voltage-controlling generator can only hold its set-point while its reactive output stays within its capability $[Q_{\min},Q_{\max}]$. The Newton solve treats a PV bus with *unbounded* $Q$ (its `BUS_TARGET_V` equation is active, $Q$ free). After convergence this loop checks each controller's realised $Q$ and enforces the limits.

### PV$\to$PQ

If the computed reactive injection violates a limit, with tolerance $\varepsilon_Q$, 

$$
Q_i < Q_{\min,i}-\varepsilon_Q \ \Rightarrow\ \text{freeze } Q_i=Q_{\min,i},
  \qquad
  Q_i > Q_{\max,i}+\varepsilon_Q \ \Rightarrow\ \text{freeze } Q_i=Q_{\max,i}.
$$

 Mathematically the bus changes type: `BUS_TARGET_V` is deactivated and `BUS_TARGET_Q` reactivated with target equal to the violated limit (`freezeGenerationTargetQ­And­Disable­GeneratorVoltageControl`). The bus voltage becomes a free unknown again and $Q$ is pinned --- exactly the PQ definition of §{ref}`sec:bustypes`. If *all* PV buses would switch, the electrically strongest one (highest nominal voltage, then highest $P$) is kept PV so the network retains a voltage anchor.

### PQ$\to$PV (un-clamping)

A previously clamped bus is released back to PV when the voltage has moved to the "right" side of its set-point, indicating the limit is no longer binding: 

$$
\text{clamped at } Q_{\min}\ \text{and}\ V_i<V_i^{\text{spec}}
   \ \Rightarrow\ \text{restore PV};\qquad
  \text{clamped at } Q_{\max}\ \text{and}\ V_i>V_i^{\text{spec}}
   \ \Rightarrow\ \text{restore PV}.
$$

 To prevent infinite oscillation, each bus may switch back PQ$\to$PV at most `maxPqPvSwitch` times (default $3$); after that it stays PQ. A clamped bus whose limit *value* changed (because its $P$ or $V$ moved) simply updates its frozen $Q$ without a type change.

### Robust mode

With remote voltage control, a generator can drive its own terminal to an unrealistic voltage while still inside its $Q$ limits. In robust mode such a controller is also switched off (frozen at its initial $Q$, voltage reset to $1\pu$) and flagged `MIN/MAX_REALISTIC_V`. This loop can therefore *fix* an otherwise `UNREALISTIC_STATE` (§{ref}`sec:stopcrit`).

(sec:distq)=

## Shared voltage control and the $Q$-distribution equation
`ac/equations/AcEquationSystemCreator.java (createGeneratorReactivePowerDistributionEquations)`

When several generators jointly control the *same* bus voltage, the single equation $V=V^{\text{spec}}$ does not say how to split the reactive burden among them. OLF keeps *one* controller on voltage duty and replaces each other controller $i$'s reactive equation by a **reactive-power sharing** equation `DISTR_Q`: its share of total reactive should equal its key $\text{qPercent}_i$, 

$$
q_i=\text{qPercent}_i\sum_j q_j
  \quad\Longleftrightarrow\quad
  \boxed{\;0=(\text{qPercent}_i-1)\,q_i+\text{qPercent}_i\!\!\sum_{j\ne i} q_j\;}
$$

 where $q_i$ is the net reactive generation at controller bus $i$ (sum of its incident branch $Q$ terms, shunt $Q$, dummy-$Q$). The constant load part moves to the target: $t=(\text{qPercent}_i-1)Q^{\text{spec}}_i+\text{qPercent}_i\sum_{j\ne
i}Q^{\text{spec}}_j$. The keys $\text{qPercent}_i=k_i/\sum_j k_j$ come from each generator's reactive key, defaulting to its reactive range $Q_{\max}-Q_{\min}$, defaulting again to uniform sharing `network/GeneratorVoltageControl.java, Control.java`.

This makes the system square again: $N$ shared controllers contribute $1$ voltage equation $+$ $(N-1)$ sharing equations $=N$ equations for the $N$ free reactive outputs. Activation/deactivation of these equations (which controller is on voltage, which on sharing) is handled generically by `updateRemoteVoltageControlEquations` and toggled by the reactive-limits loop as controllers hit limits.

(sec:incmachinery)=

## Incremental controls: the shared sensitivity machinery
Five outer loops --- incremental transformer voltage, shunt voltage, phase (active power and current limiter), and transformer reactive power --- all move *discrete* taps/sections using the *same* matrix computation. Because this computation is exactly the sensitivity-analysis kernel (the subject of the companion sensitivity volume), we derive it once here.

Let $\bm J=\partial\ff/\partial\xx$ be the converged AC Jacobian (Chapter {ref}`ch:jac`) and let $u_k$ be a control (a transformer ratio $\rho_k$, a shunt $b_k$, or a phase $\alpha_k$). Differentiating the load-flow identity $\ff(\xx,\bm u)-\bm t=\bm 0$ with respect to $u_k$ gives the implicit-function relation 

$$
\bm J\,\pdv{\xx}{u_k} = -\,\pdv{\ff}{u_k}=:\bm e_k,
  \qquad\Longrightarrow\qquad
  \pdv{\xx}{u_k}=\bm J^{-1}\bm e_k=:\bm s_k .
$$ (eq:incsens)

 In practice $\bm e_k$ is a *unit* right-hand side: a single $1$ placed in the row of the control's "constant" equation (`BRANCH_TARGET_RHO1` for $\rho$, `SHUNT_TARGET_B` for $b$, $\mathrm{rad}(1^\circ)$ in `BRANCH_TARGET_ALPHA1` for $\alpha$). The state sensitivity column $\bm s_k$ is obtained by *one transposed solve* on the already-factorised Jacobian, $\bm J^{\top}\bm s_k=\bm e_k$ (`j.solveTransposed(rhs)`, §{ref}`transposed Jacobian storage <sec:transpose>`). The sensitivity of any monitored quantity $g(\xx)$ (a controlled-bus voltage, a branch flow, a current) is then the **dot product** 

$$
\boxed{\;
  \pdv{g}{u_k}=\Bigl(\pdv{g}{\xx}\Bigr)^{\!\top}\bm s_k
   =\sum_{v\in\text{vars}(g)}\pdv{g}{v}\,s_{k,v}\;}
$$ (eq:incdot)

 computed by the equation term's `calculateSensi` (the very method exposed by the branch terms in §{ref}`sec:current`, §{ref}`sec:branchderiv`). Given a target mismatch $\Delta g=g^{\text{target}}-g$, the required *continuous* control change is the one Newton step of the reduced $1\times1$ system, 

$$
\Delta u_k=\frac{\Delta g}{\partial g/\partial u_k},
$$ (eq:incstep)

 which is then **discretised** to a physical tap/section (§{ref}`transformer ratio and phase shift <sec:transfo>`). Two anti-oscillation safeguards apply [`lf/outerloop/IncrementalContextData.java`](https://github.com/powsybl/powsybl-open-loadflow/blob/main/src/main/java/com/powsybl/openloadflow/lf/outerloop/IncrementalContextData.java): a tap moves only if the mismatch exceeds a half-dead-band $|\Delta g|>\tfrac12\,\text{db}$, and after `MAX_DIRECTION_CHANGE`$=3$ reversals of move direction the direction is *locked* to prevent hunting.

(sec:tfovc)=

## Transformer voltage control (ratio tap)
A ratio-tap transformer holds a controlled-bus voltage by adjusting $\rho$. Two flavours exist.

### Continuous then rounded (simple/full)

`ac/outerloop/{Simple,}TransformerVoltageControlOuterLoop.java` $\rho$ is made a *continuous* variable `BRANCH_RHO1` and the controlled bus gets `BUS_TARGET_V` active, so Newton finds the continuous $\rho^\star$ that hits $V^{\text{spec}}$ (the branch terms already provide $\partial P/\partial\rho,\ \partial Q/\partial\rho$, §{ref}`sec:branchderiv`). The outer loop then **rounds** $\rho^\star$ to the nearest physical tap (§{ref}`transformer ratio and phase shift <sec:transfo>`), pins $\rho$ there (`BRANCH_TARGET_RHO1`), and re-solves. For several transformers controlling one bus, a $\rho$-sharing equation `DISTR_RHO` (identical algebra to `DISTR_Q` but forcing equal ratios) keeps the system square: 

$$
0=\Bigl(\tfrac1N-1\Bigr)\rho_i+\tfrac1N\sum_{j\ne i}\rho_j,\qquad N=\#\{\text{enabled controllers}\}.
$$

(sec:inctfo)=

### Incremental (sensitivity-based)
[`ac/outerloop/IncrementalTransformerVoltageControlOuterLoop.java`](https://github.com/powsybl/powsybl-open-loadflow/blob/main/src/main/java/com/powsybl/openloadflow/ac/outerloop/IncrementalTransformerVoltageControlOuterLoop.java) Instead of a continuous variable, the incremental loop keeps $\rho$ discrete and moves taps using the machinery of §{ref}`sec:incmachinery`. The monitored quantity is the controlled-bus voltage, so $g=V$ and the sensitivity $\partial V/\partial\rho$ comes from {eq}`eq:incsens`--{eq}`eq:incdot`; the target mismatch is $\Delta V=V^{\text{spec}}-V$ and the continuous ratio change is 

$$
\Delta\rho=\frac{V^{\text{spec}}-V}{\partial V/\partial\rho},
$$

 applied only when $|\Delta V|>\tfrac12\,\text{db}$ (half dead-band, default $0.1\,\text{kV}/V_{\text{nom}}$). `updateTapPositionToReachNewR1` then snaps $\rho+\Delta\rho$ to the *closest* tap within `maxTapShift` and the allowed direction. For several transformers on one bus, taps are moved *round-robin one step at a time*, the residual being updated after each move by $\Delta V\mathrel{-}=(\rho^{\text{new}}-\rho^{\text{old}})\,(\partial
V/\partial\rho)$, which spreads the taps evenly. This avoids the continuous-then-round mismatch of the simple variant and scales to many discrete controllers.

(sec:shuntvc)=

## Shunt voltage control
`ac/outerloop/{,Incremental}ShuntVoltageControlOuterLoop.java` Identical in spirit to transformer voltage control, with the susceptance $b$ (`SHUNT_B`) as the control. The continuous variant lets Newton find the $b^\star$ that meets $V^{\text{spec}}$ using $\partial Q_{\text{sh}}/\partial
b=-V^2$ (§{ref}`sec:shunt`), then rounds to the nearest switchable section. The incremental variant uses §{ref}`sec:incmachinery` with $g=V$: the section change is $\Delta b=(V^{\text{spec}}-V)/(\partial V/\partial b)$, snapped to the closest section one step at a time (at most `MAX_SECTION_SHIFT`$=3$ per outer iteration), residual updated by $\Delta V\mathrel{-}=(b^{\text{new}}-b^{\text{old}})
(\partial V/\partial b)$. Shared shunt control uses `DISTR_SHUNT_B` with the same equal-sharing algebra as `DISTR_RHO`.

(sec:phasecontrol)=

## Phase control (phase-shifting transformer)
`ac/outerloop/{PhaseControlOuterLoop,AcIncrementalPhaseControlOuterLoop}.java` A phase shifter regulates an *active-power* flow (or limits a current) by adjusting $\alpha$. The angle becomes a variable `BRANCH_ALPHA1`; when the control is active the constant-shift equation `BRANCH_TARGET_ALPHA1` is swapped for the flow target `BRANCH_TARGET_P`: 

$$
P_{\text{controlled side}}(\xx)=P^{\text{target}},
$$

 using the analytic $\partial P/\partial\alpha=\partial P/\partial\varphi_1$ of §{ref}`sec:branchderiv`. The **incremental** variant [`ac/outerloop/AcIncrementalPhaseControlOuterLoop.java`](https://github.com/powsybl/powsybl-open-loadflow/blob/main/src/main/java/com/powsybl/openloadflow/ac/outerloop/AcIncrementalPhaseControlOuterLoop.java) uses §{ref}`sec:incmachinery` in two modes:

- *active-power* (CONTROLLER): when $|P-P^{\text{target}}|>\tfrac12\text{db}$ (db $\ge1$ MW), move $\Delta\alpha=(P^{\text{target}}-P)/(\partial
          P/\partial\alpha)$ and snap to the *closest* tap;

- *current limiter* (LIMITER): only when overloaded ($I>I^{\text{target}}$, no dead-band), move $\Delta\alpha=(I^{\text{target}}-I)/(\partial
          I/\partial\alpha)$ and snap to the *first* tap that *exceeds* the shift (overshoot toward safety).

The right-hand side $\bm e_k$ is scaled by $\mathrm{rad}(1^\circ)$, so the sensitivities are per-degree. A cross-coupling check warns when one phase shifter's move would, through $\partial I_{\text{other}}/\partial\alpha$, push another overloaded shifter past $0.75$ of its own violation.

(sec:tforeactive)=

## Transformer reactive-power control
[`ac/outerloop/IncrementalTransformerReactivePowerControlOuterLoop.java`](https://github.com/powsybl/powsybl-open-loadflow/blob/main/src/main/java/com/powsybl/openloadflow/ac/outerloop/IncrementalTransformerReactivePowerControlOuterLoop.java) A ratio-tap transformer can instead regulate a *reactive* flow on a controlled branch. The incremental machinery of §{ref}`sec:incmachinery` applies with $g=Q$ (the controlled-side $Q_1$ or $Q_2$): when $|Q-Q^{\text{target}}|>\tfrac12\text{db}$ (db default $0.1$ Mvar), move 

$$
\Delta\rho=\frac{Q^{\text{target}}-Q}{\partial Q/\partial\rho},
$$

 and snap to the closest tap within `maxTapShift`. The sensitivity $\partial Q/\partial\rho$ is the dot product {eq}`eq:incdot` with the branch reactive-flow derivatives of {numref}`tab:derivs`.

(sec:qcontrol)=

## Generator remote reactive-power control
`ac/equations/AcEquationSystemCreator.java (createGeneratorReactivePowerControlBranchEquation)` A generator may regulate the reactive flow on a remote branch instead of a voltage. The branch reactive flow is pinned to a target, 

$$
\texttt{BRANCH\_TARGET\_Q}:\quad Q_{\text{branch}}(\xx)=Q^{\text{target}},
$$

 and, for several generators sharing the duty, the same `DISTR_Q` sharing machinery of §{ref}`shared voltage control <sec:distq>` applies (the reactive keys come from `GeneratorReactivePowerControl`). Hitting a generator $Q$ limit converts the controller to a plain PQ bus via the reactive-limits loop.

(sec:svc)=

## Secondary voltage control
[`ac/outerloop/SecondaryVoltageControlOuterLoop.java`](https://github.com/powsybl/powsybl-open-loadflow/blob/main/src/main/java/com/powsybl/openloadflow/ac/outerloop/SecondaryVoltageControlOuterLoop.java) Secondary voltage control coordinates a *group* of generators to hold the voltage of a designated **pilot bus** at a set-point, while sharing reactive effort in fixed proportions. Following the cited research, the loop linearises the pilot-bus voltage and the generators' reactive levels about the current operating point using *sensitivities* extracted from the factorised Jacobian: 

$$
\Delta V_{\text{pilot}}=\bm s^{\T}\,\Delta\bm V^{\text{spec}}_{\text{group}},
$$

 where $\bm s=\partial V_{\text{pilot}}/\partial\bm V^{\text{spec}}_{\text{group}}$ is computed by solving $\Jmat\T\bm s=\bm e_{\text{pilot}}$-type systems. It then chooses the group set-point corrections $\Delta\bm V^{\text{spec}}$ that (i) drive $V_{\text{pilot}}\to V^{\text{spec}}_{\text{pilot}}$ and (ii) equalise the generators' reactive utilisation, solving a small linear system (a $K$-matrix condition) at each outer iteration. The corrected set-points feed back into the ordinary generator voltage control of §{ref}`shared voltage control <sec:distq>`, and Newton re-solves.

(sec:hvdcloop)=

## HVDC AC-emulation limits
[`ac/outerloop/AcHvdcAcEmulationLimitsOuterLoop.java`](https://github.com/powsybl/powsybl-open-loadflow/blob/main/src/main/java/com/powsybl/openloadflow/ac/outerloop/AcHvdcAcEmulationLimitsOuterLoop.java) The droop term {eq}`eq:hvdc` is kept *unsaturated* inside Newton for smoothness (§{ref}`sec:hvdc`). This loop enforces the converter power limits $P^{\text{raw}}\in[-P_{\max},P_{\max}]$ *after* convergence: if a link's emulated power exceeds its limit, the link is frozen at the limiting power (a constant injection) and Newton re-solves. Releasing the freeze when the angle difference recedes mirrors the PQ$\to$PV un-clamping of §{ref}`reactive-limit switching <sec:qlim>`.

(sec:area)=

## Area interchange control
A generalisation of distributed slack to *multiple control areas*: each area must meet a scheduled net tie-line interchange, not just a global balance. Enabled by `areaInterchangeControl`, it runs as an outer loop on top of an ordinary solve, in both AC [`ac/outerloop/AcAreaInterchangeControlOuterLoop.java`](https://github.com/powsybl/powsybl-open-loadflow/blob/main/src/main/java/com/powsybl/openloadflow/ac/outerloop/AcAreaInterchangeControlOuterLoop.java) and DC [`dc/DcAreaInterchangeControlOuterLoop.java`](https://github.com/powsybl/powsybl-open-loadflow/blob/main/src/main/java/com/powsybl/openloadflow/dc/DcAreaInterchangeControlOuterLoop.java); the shared logic lives in `lf/outerloop/AbstractAreaInterchangeControlOuterLoop.java`. If the network has no areas at all it degrades exactly to the distributed-slack loop of §{ref}`sec:distslack` (the AC variant literally delegates to it).

### The area total mismatch.

For each area $A$ the loop forms `lf/outerloop/AbstractAreaInterchangeControlOuterLoop.java (getInterchangeMismatchWithSlack)` 

$$
\Delta_A \;=\; I_A \;-\; I_A^{\text{target}} \;+\; \sigma_A ,
$$ (eq:areamis)

 where $I_A$ is the area's *interchange* --- the sum of branch flows at its declared boundaries in load sign convention (positive for imports) --- $I_A^{\text{target}}$ is its scheduled interchange, and $\sigma_A$ is the slack-bus active-power mismatch *attributed* to $A$ (below). The $+\sigma_A$ term means an unbalanced slack inside the area is treated as an extra "interchange to the void" that must also be resolved. The active power $\Delta_A$ is distributed over $A$'s own participating injections by the participation-factor dispatch of §{ref}`sec:distslack`, applied area by area.

### Two-stage convergence.

The loop first drives every area whose $\lvert\Delta_A\rvert$ exceeds the tolerance $\varepsilon_I=$ `areaInterchangePMaxMismatch` back below it (re-solving in between). Only once *all* areas pass does it test the *interchange-only* mismatch $I_A-I_A^{\text{target}}$ against $\varepsilon_I$ for every area *and* the global slack mismatch against $\varepsilon_s=$ `slackBusPMaxMismatch`; it reports `STABLE` only if both hold. Otherwise the leftover slack is redistributed (next paragraph) and another iteration is forced.

### Attributing the slack to an area.

Which area owns the slack-bus mismatch is decided per slack bus `lf/outerloop/AbstractAreaInterchangeControlOuterLoop.java (allocateSlackDistributionParticipationFactors)`:

- slack bus *in* an area $\to$ its mismatch is added to that area's $\sigma_A$;

- slack bus with *no* area, connected to other no-area buses $\to$ handled as a plain no-area mismatch;

- no-area slack connected only to area buses: if *every* connecting branch is a boundary of those areas, the mismatch is already inside the interchange figures and is left to resolve itself; if *some* connecting branch is not a boundary, the mismatch is split equally among the connected areas.

### Distributing the residual slack.

When all $\lvert\Delta_A\rvert$ are within tolerance but a slack mismatch $\Delta_{\text{slack}}$ remains, it is first pushed onto buses with no area; any still-undistributed remainder is shared over *all* areas. Each area's share is set by a factor that keeps it from being driven past its own tolerance `lf/outerloop/AbstractAreaInterchangeControlOuterLoop.java (getSlackDistributionFactorByArea)` 

$$
F_A=\operatorname{sign}(\Delta_{\text{slack}})\,\Delta_A+\varepsilon_I,
  \qquad \text{then normalised so } \sum_A F_A=1 .
$$ (eq:areafactor)

 The dispatch is iterative *within* the outer-loop step: an area that cannot absorb its full share is dropped and its remainder re-shared (factors re-normalised) on the next pass; it fails only if every area is saturated yet mismatch remains.

### Boundaries, validity and zero-impedance.

An area's interchange sums its declared boundary flows `network/impl/LfNetworkLoaderImpl.java (addBranchAreaBoundaries)`, which IIDM expresses either through an equipment terminal or through a `BoundaryLine` (the boundary-side flow is used, for both unpaired boundary lines and ones paired in a tie line). An area is *ignored* by the loop if it has no interchange target, no boundaries, or boundaries spanning several synchronous/connected components `network/impl/LfNetworkLoaderImpl.java (createAreas, checkBoundariesComponent)`; the remaining valid areas are still controlled. Finally, when `lowImpedanceBranchMode` is `REPLACE_BY_ZERO_IMPEDANCE_LINE` a zero-impedance branch used as a boundary is given a small impedance equal to `lowImpedanceThreshold` so that a boundary flow is well defined `network/LfNetwork.java (fix)`.

(sec:discrete)=

## Discrete-event loops (no Jacobian sensitivity)
Three further AC loops act by *discrete logic* rather than a sensitivity solve.

### Automation systems / overload management.

For each overload-management system, read the monitored branch current $I=I_1$ or $I_2$. A tripping rule with threshold $I_{\text{th}}$ fires when 

$$
I>I_{\text{th}}\ \text{ and the target branch is not already in the requested state,}
$$

 opening (or closing) the designated branch. After applying the topology change the loop recomputes bus/branch connectivity and returns `UNSTABLE` to force a re-solve. [`ac/outerloop/AutomationSystemOuterLoop.java`](https://github.com/powsybl/powsybl-open-loadflow/blob/main/src/main/java/com/powsybl/openloadflow/ac/outerloop/AutomationSystemOuterLoop.java)

### Voltage monitoring (SVC standby automaton).

A static var compensator on standby (currently PQ) carries a voltage band $[V_{\text{low}},V_{\text{high}}]$. If the controlled-bus voltage leaves the band, the SVC is switched PQ$\to$PV with the corresponding target: 

$$
V>V_{\text{high}}\ \Rightarrow\ V^{\text{spec}}=V^{\text{high}}_{\text{target}},
  \qquad
  V<V_{\text{low}}\ \Rightarrow\ V^{\text{spec}}=V^{\text{low}}_{\text{target}},
$$

 re-enabling generator voltage control at that bus (a PV equation swap as in §{ref}`reactive-limit switching <sec:qlim>`) and returning `UNSTABLE`. [`ac/outerloop/MonitoringVoltageOuterLoop.java`](https://github.com/powsybl/powsybl-open-loadflow/blob/main/src/main/java/com/powsybl/openloadflow/ac/outerloop/MonitoringVoltageOuterLoop.java)

### HVDC AC-emulation freezing.

A two-step state machine used for robustness. On initialisation, every AC-emulation link with valid terminal angles is *frozen*: its droop law {eq}`eq:hvdc` is replaced by a constant injection equal to the previously computed power, so the first solve sees a fixed, well-behaved injection. The `check` step then *unfreezes* (restores $P_0+k(\varphi_1-\varphi_2)$) and returns `UNSTABLE` once, forcing a final re-solve under the true emulation law. [`ac/outerloop/FreezingHvdcACEmulationOuterloop.java`](https://github.com/powsybl/powsybl-open-loadflow/blob/main/src/main/java/com/powsybl/openloadflow/ac/outerloop/FreezingHvdcACEmulationOuterloop.java)

## Summary: equations added/removed per control

::: center
| Control | variable(s) | active/inactive equation swap |
|:---|:---|:---|
| distributed slack | --- (changes targets) | none (target $P$ re-allocated) |
| reactive limits | --- | `BUS_TARGET_V`$\leftrightarrow$`BUS_TARGET_Q` |
| shared $V$ control | --- | one `BUS_TARGET_V` $+$ $(N{-}1)$ `DISTR_Q` |
| transformer $V$ (continuous) | $\rho$=`BRANCH_RHO1` | `BUS_TARGET_V`/`DISTR_RHO`$\leftrightarrow$`BRANCH_TARGET_RHO1` |
| shunt $V$ | $b$=`SHUNT_B` | `BUS_TARGET_V`/`DISTR_SHUNT_B`$\leftrightarrow$`SHUNT_TARGET_B` |
| phase control | $\alpha$=`BRANCH_ALPHA1` | `BRANCH_TARGET_P`$\leftrightarrow$`BRANCH_TARGET_ALPHA1` |
| transformer $Q$ control | $\rho$=`BRANCH_RHO1` | incremental, $\partial Q/\partial\rho$ (no swap) |
| generator $Q$ control | --- | `BRANCH_TARGET_Q` ($+$ `DISTR_Q`) |
| automation / monitoring / HVDC freeze | --- | discrete logic, §{ref}`sec:discrete` |
:::

Every row keeps the system square (§{ref}`sec:events`); the framework's listeners restructure only the affected rows/columns and the KLU symbolic factorisation is redone only when the structure actually changes (§{ref}`sec:lazy`).
