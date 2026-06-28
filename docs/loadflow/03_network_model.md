(ch:network)=

# The network model
Before any equation can be written, OLF reduces the rich external grid model to a compact numerical object, the `LfNetwork`, made of `LfBus`es, `LfBranch`es, `LfShunt`s, `LfLoad`s and `LfGenerator`s. This chapter describes the *electrical* content of that model: the branch $\pi$-model and how the physical nameplate data become the coefficients $y,\xi,g_1,b_1,g_2,b_2,\rho,\alpha$ that the flow equations of Chapter {ref}`ch:branch` consume.

(sec:pimodel)=

## The branch $\pi$-model
Every transmission line, cable or two-winding transformer is represented by the same universal building block: a **$\pi$ (pi) two-port** with an ideal transformer (ratio and phase shifter) on side 1. {numref}`fig:pi` shows it.

```{figure} pi-model.svg
:name: fig:pi
:width: 80%

The branch $\pi$-model: an ideal complex transformer in series with a series admittance, with shunt admittances at each end.
```

(sec:ymodel)=

### Series admittance: $y$ and $\xi$
The branch stores the per-unit series resistance $r$ and reactance $x$. OLF does *not* store $g_{12},b_{12}$ directly; it stores a *modulus* and an *angle* that turn out to linearise the trigonometry of the flow equations.

[`network/SimplePiModel.java`](https://github.com/powsybl/powsybl-open-loadflow/blob/main/src/main/java/com/powsybl/openloadflow/network/SimplePiModel.java) 

$$
\boxed{\;
  z=\sqrt{r^2+x^2},\qquad
  y=\frac{1}{z}=\frac{1}{\sqrt{r^2+x^2}},\qquad
  \xi=\operatorname{atan2}(r,x).\;}
$$ (eq:yksi)

 Note carefully the *argument order* `atan2(r,x)`: the angle $\xi$ is measured from the *reactance* axis, so 

$$
\sin\xi=\frac{r}{z}=r\,y,\qquad \cos\xi=\frac{x}{z}=x\,y.
$$

 This is exactly the angle that makes the series admittance come out as a clean phasor. Indeed, the series admittance is 

$$
\underline{y}_{12}=\frac{1}{r+\jj x}
  =\frac{r-\jj x}{r^2+x^2}
  = r\,y^2 - \jj\,x\,y^2
  = g_{12}+\jj b_{12},
$$

 which matches the code [`ac/equations/AbstractBranchAcFlowEquationTerm.java`](https://github.com/powsybl/powsybl-open-loadflow/blob/main/src/main/java/com/powsybl/openloadflow/ac/equations/AbstractBranchAcFlowEquationTerm.java) 

$$
g_{12}=r\,y^2,\qquad b_{12}=-x\,y^2 .
$$

 Substituting $r\,y=\sin\xi$ and $x\,y=\cos\xi$ gives the compact polar form used throughout Chapter {ref}`ch:branch`: 

$$
\underline{y}_{12}= y\sin\xi-\jj\,y\cos\xi
                    = y\bigl(\sin\xi-\jj\cos\xi\bigr)
                    = -\jj\,y\,\e^{\jj\xi}
                    = y\,\e^{\jj(\xi-\pi/2)} .
$$ (eq:yser-polar)

 So the series admittance has *modulus* $y$ and *argument* $\xi-\nicefrac{\pi}{2}$. For a typical line $r\ll x$, hence $\xi\to 0$ and $\underline{y}_{12}\to -\jj y$, i.e. almost purely susceptive --- the familiar \"lines are mostly reactive\" fact.

### Shunt half-admittances

The two shunt branches model the line charging (capacitance to ground) and the magnetising/iron losses of a transformer. They are stored directly as the per-unit values $g_1,b_1$ (side 1) and $g_2,b_2$ (side 2), obtained from the nameplate $G_k,B_k$ by $g_k=G_k Z_{\mathrm B}$, $b_k=B_k Z_{\mathrm B}$ (§{ref}`sec:perunit`). For a simple symmetric line one typically has $g_1=g_2=G/2$ and $b_1=b_2=B/2$, the two halves of the total line charging.

(sec:transfo)=

### The transformer: ratio $\rho$ and phase shift $\alpha$
A transformer changes the voltage *magnitude* by a ratio $\rho$ and a phase shifter changes the voltage *angle* by $\alpha$. OLF puts the whole ratio on side 1 and fixes side 2 as the reference [`network/PiModel.java`](https://github.com/powsybl/powsybl-open-loadflow/blob/main/src/main/java/com/powsybl/openloadflow/network/PiModel.java): 

$$
R_1=\rho \ (\texttt{getR1}),\quad A_1=\alpha \ (\texttt{getA1}),\qquad
  R_2=1,\quad A_2=0 .
$$

 The ideal transformer maps the bus voltage $\Vc_1$ to an internal voltage $\Vc_1'=\rho\,\e^{\jj\alpha}\,\Vc_1$ on the series-element side, and likewise $\Vc_2'=R_2\,\e^{\jj A_2}\Vc_2=\Vc_2$. A plain line is the special case $\rho=1,\ \alpha=0$ (defaults in `SimplePiModel`). These two quantities may be *state variables* when a control acts on them: $\alpha$ becomes the variable `BRANCH_ALPHA1` for a phase-shifting transformer regulating active power (§{ref}`sec:phasecontrol`), and $\rho$ becomes `BRANCH_RHO1` for a ratio-tap transformer regulating voltage (§{ref}`sec:tfovc`).

### Discrete taps: `PiModelArray`

A real tap changer is *discrete*: only a finite set of $(\rho,\alpha)$ positions exists. `PiModelArray` stores one `PiModel` per tap and indexes it by $\text{tapPositionIndex}=\text{tapPosition}-\text{lowTapPosition}$. During the continuous Newton solve, the controls may move $\rho$ or $\alpha$ off a tap; the value is later snapped back to the nearest physical tap by $\arg\min_{\text{tap}}\bigl|\,\text{target}-\text{value}(\text{tap})\,\bigr|$ (`ClosestTapPositionFinder`). This continuous-then-round strategy is the mathematical heart of the discrete voltage/phase controls of Chapter {ref}`ch:outer`.

### The low-impedance cut

A genuinely zero-impedance branch ($r=x=0$) would make $y=\infty$. Two defences exist. Branches whose impedance is below a threshold but non-zero are *clamped* while *preserving the angle* $\xi$ [`network/SimplePiModel.java`](https://github.com/powsybl/powsybl-open-loadflow/blob/main/src/main/java/com/powsybl/openloadflow/network/SimplePiModel.java): 

$$
z<z_{\min}\ \Rightarrow\ r\leftarrow z_{\min}\sin\xi,\quad
  x\leftarrow z_{\min}\cos\xi ,
$$

 and truly zero-impedance branches are handled by a completely different mechanism (the $\texttt{ZERO\_V}/\texttt{ZERO\_PHI}$ equations of §{ref}`sec:zeroimp`), never through $y$.

(sec:shunt)=

## Shunt compensators
A shunt compensator is a pure susceptance $b$ (and small conductance $g$) connected from a bus to ground. Its power contribution to the bus, at voltage $V$, is derived directly from $\Sc=\Vc\conj{\Ic}=\Vc\,\conj{(g+\jj b)\Vc}
=(g-\jj b)V^2$, giving `ac/equations/ShuntCompensator{Active,Reactive}FlowEquationTerm.java` 

$$
P_{\text{sh}}(V)=g\,V^2,
  \qquad
  Q_{\text{sh}}(V)=-b\,V^2 .
$$ (eq:shunt)

 A positive $b$ is capacitive and *injects* reactive power, which is why it enters the reactive balance with a minus sign. When a shunt regulates voltage by switching sections, $b$ itself becomes the variable `SHUNT_B` (§{ref}`sec:shuntvc`), and we will need $\partial Q_{\text{sh}}/\partial b=-V^2$.

(sec:load)=

## Loads: the exponential (ZIP) model
A constant-power load draws a fixed $P,Q$ regardless of voltage. Real loads are voltage-dependent, and OLF models this with a sum of *exponential terms* [`network/LfLoadModel.java`](https://github.com/powsybl/powsybl-open-loadflow/blob/main/src/main/java/com/powsybl/openloadflow/network/LfLoadModel.java). If $P_0,Q_0$ are the nominal demands, the voltage-dependent part is 

$$
P_{\text{load}}(V)=P_0\!\!\sum_{n\ne 0}\! c^{P}_n\,V^{\,n},
  \qquad
  Q_{\text{load}}(V)=Q_0\!\!\sum_{n\ne 0}\! c^{Q}_n\,V^{\,n},
$$ (eq:zip)

 with $\partial P_{\text{load}}/\partial V = P_0\sum_{n\ne0} c^{P}_n\,n\,V^{n-1}$ [`ac/equations/AbstractLoadModelEquationTerm.java`](https://github.com/powsybl/powsybl-open-loadflow/blob/main/src/main/java/com/powsybl/openloadflow/ac/equations/AbstractLoadModelEquationTerm.java). The classic **ZIP** model is the special case $n\in\{0,1,2\}$: constant-power ($n=0$), constant-current ($n=1$), constant-impedance ($n=2$). Crucially, the **$n=0$ term is excluded** from these equation terms because the constant-power part is folded into the fixed bus target (§{ref}`sec:targets`); only the voltage-*dependent* part needs a derivative in the Jacobian. The only state variable of a load term is the bus voltage $V$.

(sec:bustypes)=

## Generators and bus types
A generator injects a scheduled active power $P_g$ and either holds a voltage set-point (*voltage control*) or injects a scheduled reactive power $Q_g$. This produces the classical taxonomy of buses, which is the backbone of Chapter {ref}`ch:bus`:

::: center
| Bus type | Known (target) | Unknown (state) |
|:---|:---|:---|
| **PQ** (load) | $P,\ Q$ injection | $V,\ \theta$ |
| **PV** (generator, $V$-control) | $P$ injection, $V$ magnitude | $\theta,\ Q$ |
| **slack** (reference) | $V,\ \theta$ | $P,\ Q$ |
:::

In OLF this taxonomy is not hard-coded but *emerges* from which equations are active at each bus (§{ref}`sec:active`): a PV bus has its `BUS_TARGET_V` equation active and its `BUS_TARGET_Q` inactive, and vice-versa for a PQ bus. The reactive-limits outer loop (§{ref}`reactive-limit switching <sec:qlim>`) switches a bus between PV and PQ simply by toggling those two equations.

(sec:slack)=

## Slack and reference bus selection
Exactly one *angle reference* is needed (only angle *differences* are physical), and at least one *slack* bus must absorb the network-wide active power imbalance (generation $\ne$ load + losses, and losses are unknown until the flow is solved). OLF offers several selectors [`network/*SlackBusSelector.java`](https://github.com/powsybl/powsybl-open-loadflow/blob/main/src/main/java/com/powsybl/openloadflow/network/*SlackBusSelector.java):

- **Most-meshed** (default): keep the buses at the highest nominal voltage (precisely, the $95^{\text{th}}$ percentile of the nominal-voltage set), then pick those with the largest number of branches connected at both ends. Rationale: the electrically \"strongest\" node is the most numerically stable reference.

- **First**: the first non-fictitious bus (test/debug).

- **Largest generator**: the bus whose generators have the greatest total $P_{\max}$.

- **By name**: an explicit bus or voltage-level id, with fall-back to the most-meshed selector.

The *reference* bus (angle origin) defaults to the first slack bus; it gets the extra equation $\texttt{BUS\_TARGET\_PHI}:\ \theta=0$ (§{ref}`sec:refeq`). The slack bus(es) get their active-power balance equation *deactivated* (§{ref}`sec:active`) because their injection is whatever the rest of the network requires --- this is the unknown that the distributed-slack outer loop later re-allocates to real generators (§{ref}`sec:distslack`).
