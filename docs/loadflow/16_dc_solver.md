(ch:dcsolver)=

# The DC solver and its outer loops
The DC system $\bm B'\bm\varphi=\bm P$ is linear, so its "solver" is one matrix solve --- no Newton iteration. This short chapter states that solve precisely, explains why distributed slack is *not* an outer loop in DC, and derives the three genuine DC outer loops.

## One factorisation, no iteration

[`dc/DcLoadFlowEngine.java`](https://github.com/powsybl/powsybl-open-loadflow/blob/main/src/main/java/com/powsybl/openloadflow/dc/DcLoadFlowEngine.java) Because $\bm B'$ and the derivatives {eq}`eq:dcder` are constant, the exact angles are obtained by a *single* solve against the factorised $\bm B'$: 

$$
\boxed{\;\bm\varphi=\bm B'^{-1}\bm P\quad\text{by one call}\quad
  \texttt{jacobianMatrix.solveTransposed(targetVector)}.\;}
$$ (eq:dcsolve2)

 There is no convergence loop, no stopping criterion, no state-vector scaling: the entire content of Chapters {ref}`ch:nr`--{ref}`ch:stop` reduces, in DC, to one LU back-substitution (Chapter {ref}`ch:linsolve`). The transposed solve is used for the same storage-convention reason as in AC (§{ref}`transposed Jacobian storage <sec:transpose>`). After the solve the angles are written to the network and the branch flows are recovered by substituting into {eq}`eq:dcp`.

(sec:dcslack)=

## Distributed slack is a right-hand-side edit, not a loop
In AC, distributing the slack changes the losses, which changes the mismatch, which requires re-solving --- hence an *outer loop* (§{ref}`sec:distslack`). In DC there are *no losses*: the active balance is exact and linear, so the total imbalance 

$$
\Delta=-\sum_i\bigl(\text{generation}_i-\text{load}_i\bigr)
$$

 is known *before* the solve. OLF therefore redistributes $\Delta$ onto participating generators/loads by the same participation-factor dispatch (§{ref}`sec:distslack`, `ActivePowerDistribution`) *directly on the targets*, then performs the single solve {eq}`eq:dcsolve2` [`dc/DcLoadFlowEngine.java`](https://github.com/powsybl/powsybl-open-loadflow/blob/main/src/main/java/com/powsybl/openloadflow/dc/DcLoadFlowEngine.java). No re-solve is needed: distributed slack in DC is a one-shot edit of $\bm P$, not an iterative loop. (The same failure policies apply --- leave on slack, distribute on reference generator, or fail.)

(sec:dcouter)=

## The DC outer loops
[`lf/outerloop/config/DefaultDcOuterLoopConfig.java`](https://github.com/powsybl/powsybl-open-loadflow/blob/main/src/main/java/com/powsybl/openloadflow/lf/outerloop/config/DefaultDcOuterLoopConfig.java) Only controls that genuinely change the *linear system* between solves are outer loops. The DC list, innermost to outermost, is:

### HVDC AC-emulation limits

[`dc/DcHvdcAcEmulationLimitsOuterLoop.java`](https://github.com/powsybl/powsybl-open-loadflow/blob/main/src/main/java/com/powsybl/openloadflow/dc/DcHvdcAcEmulationLimitsOuterLoop.java) After the solve, each AC-emulation link's computed power is compared to its converter limit $P_{\max}$. If a limit is hit, the link is switched from the free droop law (which lives in $\bm B'$, §{ref}`sec:dchvdc`) to a fixed injection at $P_{\max}$ (which lives in $\bm P$); this changes the system, so the loop returns `UNSTABLE` and {eq}`eq:dcsolve2` is re-run.

(sec:dcphase)=

### Incremental phase control
[`dc/DcIncrementalPhaseControlOuterLoop.java`](https://github.com/powsybl/powsybl-open-loadflow/blob/main/src/main/java/com/powsybl/openloadflow/dc/DcIncrementalPhaseControlOuterLoop.java) A phase shifter regulating active power moves its tap using the *sensitivity* machinery of §{ref}`sec:incmachinery`, now exact and constant. The state sensitivity is one transposed solve $\bm B'\bm s_k=\bm e_k$ with a unit ($\mathrm{rad}(1^\circ)$) in the controller's `BRANCH_TARGET_ALPHA1` row; the flow sensitivity is the dot product $\partial P/\partial\alpha$ with the constant DC derivatives {eq}`eq:dcder`. When the controlled flow is outside its half-dead-band ($\ge\tfrac12\max(\text{db},1\,\text{MW})$), 

$$
\Delta\alpha=\frac{P^{\text{target}}-P}{\partial P/\partial\alpha}
$$

 is applied and snapped to the closest tap; a tap move returns `UNSTABLE`. This is the literal DC instance of the incremental sensitivity kernel that unifies the controls and the sensitivity analysis ({ref}`part:sensi`).

### Area interchange control

[`dc/DcAreaInterchangeControlOuterLoop.java`](https://github.com/powsybl/powsybl-open-loadflow/blob/main/src/main/java/com/powsybl/openloadflow/dc/DcAreaInterchangeControlOuterLoop.java) Each control area must meet its scheduled net tie-line interchange. The loop computes per area the mismatch 

$$
\Delta_{\text{area}}=\text{interchange}-\text{interchange}^{\text{target}}+\text{slack injection},
$$

 and redistributes it among that area's participating injections by the participation-factor dispatch of §{ref}`sec:distslack`, area by area (residuals spread proportionally to interchange margin); any bus move returns `UNSTABLE`. With no areas defined it degenerates to a no-op, since plain slack is already handled by §{ref}`sec:dcslack`.

## DC as the AC angle initialiser

Beyond being a product in its own right, the DC solve is OLF's best angle *initialiser* for AC: `DcValueVoltageInitializer` runs exactly the single solve {eq}`eq:dcsolve2` and seeds $\varphi_i^{(0)}=\varphi_i^{\text{DC}}$, $V_i^{(0)}=1$ for the Newton iteration (§{ref}`sec:dcinit`). Because the DC angles already satisfy the linearised active balance, AC Newton typically converges in a couple of iterations from this start.

## Why DC, and its place in this document

DC trades all voltage/reactive information for a single, robust, non-iterative linear solve. That same constant, factorised $\bm B'$ underpins three things in this document: the DC load flow (this chapter), the angle initialiser for AC (§{ref}`sec:dcinit`), and --- via the Woodbury low-rank update --- the fast DC sensitivity and contingency analysis of {ref}`part:sensi` (Chapter {ref}`ch:woodbury`). The DC and AC load flows together complete the *load-flow* core; the sensitivity analysis built on top of them is the subject of {ref}`part:sensi`.
