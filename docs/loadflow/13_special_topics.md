(ch:special)=

# Special topics
This chapter collects three mechanisms that do not fit the per-branch / per-bus narrative but are essential to a production load flow: zero-impedance branches, multiple slack buses, and the final back-conversion of the solution into physical results.

(sec:zeroimp)=

## Zero-impedance branches and subnetworks
A closed switch, a busbar coupler, or a transformer modelled with negligible impedance has $r=x=0$, so the admittance $y=1/z$ blows up (§{ref}`series admittance <sec:ymodel>`) and the flow equations of Chapter {ref}`ch:branch` are unusable. OLF handles such branches by a *completely different* algebraic device: instead of computing the flow *from* the voltages, it imposes the voltages to be equal and treats the flow as a free unknown.

### The constraint equations

For a zero-impedance branch between buses 1 and 2 with ideal ratio $\rho$ and phase $\alpha$, the two physical facts "no impedance $\Rightarrow$ no voltage drop" become two linear equations [`ac/equations/AcEquationSystemCreator.java`](https://github.com/powsybl/powsybl-open-loadflow/blob/main/src/main/java/com/powsybl/openloadflow/ac/equations/AcEquationSystemCreator.java): 

$$
\texttt{ZERO\_V}:\quad     V_1-\rho\,V_2 = 0,
$$ (eq:zerov)

$$
\texttt{ZERO\_PHI}:\quad   \varphi_1-\varphi_2 = \alpha.
$$ (eq:zerophi)

 {eq}`eq:zerov` ties the magnitudes through the ratio; {eq}`eq:zerophi` ties the angles through the phase shift (target $=\alpha=\texttt{LfBranch.getA}$). These replace the missing $P_1,Q_1,P_2,Q_2$ flow relations.

### The dummy-power variables

The constraints removed four equations' worth of information but the branch still carries (finite, unknown) power. OLF introduces two *dummy* state variables $P_{\text{dum}}=\texttt{DUMMY\_P}$ and $Q_{\text{dum}}=\texttt{DUMMY\_Q}$ representing the active and reactive power flowing through the branch, and injects them into the bus balances with opposite signs: 

$$
\text{bus 1 }P\text{-balance } \mathrel{+}= +P_{\text{dum}},\qquad
  \text{bus 2 }P\text{-balance } \mathrel{+}= -P_{\text{dum}},
$$

 and likewise $\pm Q_{\text{dum}}$. The dummy power is whatever it must be to make the two constraint equations {eq}`eq:zerov`--{eq}`eq:zerophi` hold; Newton solves for it like any other unknown. The bookkeeping is exact (no impedance, no loss) and the count stays square: $+2$ variables ($P_{\text{dum}},Q_{\text{dum}}$), $+2$ equations (`ZERO_V`,`ZERO_PHI`).

### Subnetworks and spanning trees

A cluster of buses joined by zero-impedance branches forms a *zero-impedance subnetwork* (an equipotential island up to ratios/shifts). To avoid redundant constraints (a cycle of equalities is over-determined), OLF applies {eq}`eq:zerov`--{eq}`eq:zerophi` only along a **spanning tree** of the subnetwork; chords carry a dummy flow but no extra constraint. An inactive `DUMMY_TARGET_P` equation is kept ready so that, if a switch *opens* during a contingency or remedial action, the branch's dummy power can be forced to zero by simply activating that equation --- no rebuild required. This makes topology changes cheap, which is why the design matters even for a plain load flow.

(sec:multislack)=

## Multiple slack buses
Large interconnections sometimes warrant several slack buses sharing the reference duty. OLF keeps *one* reference angle (§{ref}`sec:refeq`) but lets $K$ buses be slack. The active imbalance is shared among them by $K-1$ *slack-distribution* equations `BUS_DISTR_SLACK_P` that force the extra slack buses to track the first: 

$$
\texttt{BUS\_DISTR\_SLACK\_P}:\quad
  P_k(\xx)-P_1(\xx)=P^{\text{spec}}_k-P^{\text{spec}}_1,\qquad k=2,\dots,K,
$$

 i.e. each additional slack bus carries the same *deviation from its schedule* as the first slack bus. Together with the single relaxed balance this keeps the system square while spreading the reference injection. The distributed-slack outer loop (§{ref}`sec:distslack`) afterwards reallocates the *total* slack to real generators.

(sec:update)=

## From the solution to physical results
When Newton converges (and all outer loops are stable), the per-unit state vector $\xx$ is written back into the `LfNetwork` and then de-normalised `ac/solver/AcSolverUtil.java (updateNetwork)`:

- bus voltages $V_i,\varphi_i$ $\to$ `LfBus.setV/setAngle` ($V$ multiplied by $V_{\text{nom}}$ for kV, $\varphi$ converted to degrees);

- control variables $\rho,\alpha,b$ $\to$ the pi-model / shunt (after tap rounding, §{ref}`sec:tfovc`);

- branch flows $P_1,Q_1,P_2,Q_2$ are re-evaluated from {eq}`eq:P1`--{eq}`eq:Q2` and multiplied by $\SB$ for MW/Mvar; currents from {eq}`eq:dI` multiplied by $I_{\mathrm B}$ for amperes;

- generator reactive outputs (free on PV buses) are recovered from the converged $Q$-balance, and the slack/distributed active power is reported.

The final mismatch on the slack bus(es), $\Delta P_{\text{slack}}=\sum_{\text{slack}}\bigl(P^{\text{spec}}_i-P_i(\xx)\bigr)
\cdot\SB$, is reported in MW as a quality indicator. At this point every quantity the user reads --- bus voltages, branch flows, losses, reactive productions --- is a direct substitution of the converged $\xx$ into the equations derived in Chapter {ref}`ch:branch`.

## Closing remarks

The AC load flow, stripped to its mathematics, is a remarkably small set of ideas: the complex-power definition $\Sc=\Vc\conj{\Ic}$ applied to a $\pi$-branch (Chapter {ref}`ch:branch`), summed into bus balances (Chapter {ref}`ch:bus`), solved by Newton--Raphson with an exact analytic Jacobian (Chapters {ref}`ch:nr`--{ref}`ch:jac`) and sparse LU (Chapter {ref}`ch:linsolve`), all wrapped in fixed-point outer loops that enforce the engineering controls (Chapter {ref}`ch:outer`). Everything else --- the framework, the initialisers, the scaling, the zero-impedance algebra --- exists to make those core ideas *robust* and *fast* on real, messy networks. {ref}`part:dc` (the DC load flow) and the {ref}`Sensitivity analysis <part:sensi>` section build directly on the machinery established here; the unbalanced extension is covered in {ref}`part:asym` and contingency calculations in the {ref}`Security analysis <part:security>` section.
