(ch:branch)=

# Branch power flows: full derivation
This is the mathematical core of the load flow. We derive, *from first principles and without skipping a single step*, the four power-flow expressions $P_1,Q_1,P_2,Q_2$ of a closed branch, all of their partial derivatives (the Jacobian entries), the current-magnitude expression, the reduced \"open branch\" expressions, and the HVDC AC-emulation flow. Each boxed result is annotated with the exact Java method that implements it.

## Setup and notation

Consider the $\pi$-branch of {numref}`fig:pi` between bus 1 ($\Vc_1=V_1\e^{\jj\theta_1}$, written $v_1,\varphi_1$ in code) and bus 2 ($\Vc_2=V_2\e^{\jj\theta_2}$). Recall from §{ref}`branch π-model <sec:pimodel>`: 

$$
\underline{y}_{12}=y\,\e^{\jj(\xi-\pi/2)}\ \text{(series)},\quad
  g_1+\jj b_1,\ g_2+\jj b_2\ \text{(shunts)},\quad
  \rho=R_1,\ \alpha=A_1,\ R_2=1,\ A_2=0 .
$$

 The ideal transformers give the internal voltages 

$$
\Vc_1' = R_1\,\e^{\jj A_1}\,\Vc_1 = R_1 V_1\,\e^{\jj(\theta_1+A_1)},
  \qquad
  \Vc_2' = R_2\,\e^{\jj A_2}\,\Vc_2 = V_2\,\e^{\jj\theta_2}.
$$ (eq:internalV)

### The two auxiliary angles $\theta_1,\theta_2$

It is convenient to define the two angles that appear in the final formulas [`ac/equations/AbstractClosedBranchAcFlowEquationTerm.java`](https://github.com/powsybl/powsybl-open-loadflow/blob/main/src/main/java/com/powsybl/openloadflow/ac/equations/AbstractClosedBranchAcFlowEquationTerm.java): 

$$
\boxed{\;
  \theta_1=\xi-A_1+A_2-\theta_1^{\text{bus}}+\theta_2^{\text{bus}}
          =\xi-\alpha-\varphi_1+\varphi_2,
  \quad
  \theta_2=\xi+\alpha+\varphi_1-\varphi_2 .\;}
$$ (eq:thetas)

 (We use $\varphi_i$ for the bus angle $\theta_i^{\text{bus}}$ to avoid clashing with $\theta_1,\theta_2$.) Note $\theta_1$ and $\theta_2$ differ by the sign of $(\alpha+\varphi_1-\varphi_2)$ and the $-2\xi$ shift; both reduce to $\xi\mp(\varphi_1-\varphi_2)$ for a plain line.

(sec:p1q1)=

## Side-1 power: derivation
The current leaving bus 1 *into* the branch, referred to the internal node $1'$, is the sum of the shunt current and the series current: 

$$
\Ic_1' = \underbrace{(g_1+\jj b_1)\,\Vc_1'}_{\text{shunt at }1'}
         + \underbrace{\underline{y}_{12}\,(\Vc_1'-\Vc_2')}_{\text{series }1'\to 2'} .
$$ (eq:I1)

 Because the ideal transformer is lossless, the complex power entering at bus 1 equals the complex power entering the internal node, so we may compute 

$$
\Sc_1=P_1+\jj Q_1=\Vc_1'\,\conj{\Ic_1'}
   =\underbrace{(g_1+\jj b_1)^{*}\,|\Vc_1'|^2}_{\text{(A) shunt}}
   +\underbrace{\conj{\underline y_{12}}\,|\Vc_1'|^2}_{\text{(B) series self}}
   -\underbrace{\conj{\underline y_{12}}\,\Vc_1'\conj{\Vc_2'}}_{\text{(C) series mutual}} .
$$ (eq:S1expand)

 We now evaluate the three terms. Using $|\Vc_1'|^2=R_1^2V_1^2$ and $\conj{\underline y_{12}}=y\,\e^{-\jj(\xi-\pi/2)}=y(\sin\xi+\jj\cos\xi)$:

### (A) Shunt.

$(g_1-\jj b_1)R_1^2V_1^2$. Real part $=g_1R_1^2V_1^2$; imaginary part $=-b_1R_1^2V_1^2$.

### (B) Series self.

$y(\sin\xi+\jj\cos\xi)\,R_1^2V_1^2$. Real part $=y R_1^2V_1^2\sin\xi$; imaginary part $=y R_1^2 V_1^2\cos\xi$.

### (C) Series mutual.

First, $\Vc_1'\conj{\Vc_2'}=R_1V_1V_2\,\e^{\jj(\theta_1+A_1-\theta_2)}
 =R_1V_1V_2\,\e^{\jj(\varphi_1+\alpha-\varphi_2)}$, and $\conj{\underline y_{12}}=y\,\e^{\jj(\pi/2-\xi)}$. Hence 

$$
\conj{\underline y_{12}}\,\Vc_1'\conj{\Vc_2'}
   = y\,R_1 V_1 V_2\,\e^{\jj(\varphi_1+\alpha-\varphi_2+\pi/2-\xi)} .
$$

 Its real part is $y R_1 V_1 V_2\cos(\varphi_1+\alpha-\varphi_2+\tfrac{\pi}{2}-\xi)
 =-y R_1 V_1 V_2\sin(\varphi_1+\alpha-\varphi_2-\xi)$. Since $\theta_1=\xi-\alpha-\varphi_1+\varphi_2=-(\varphi_1+\alpha-\varphi_2-\xi)$ and $\sin$ is odd, $\sin(\varphi_1+\alpha-\varphi_2-\xi)=-\sin\theta_1$, so the real part of (C) equals $+y R_1 V_1 V_2\sin\theta_1$. Because (C) is *subtracted* in {eq}`eq:S1expand`, its contribution to $P_1$ is $-yR_1V_1V_2\sin\theta_1$. Its imaginary part: $\sin(\varphi_1+\alpha-\varphi_2+\tfrac\pi2-\xi)
=\cos(\varphi_1+\alpha-\varphi_2-\xi)=\cos\theta_1$ (cosine is even), contributing $-yR_1V_1V_2\cos\theta_1$ to $Q_1$.

### Assembling.

Collecting real and imaginary parts (and writing $r_1$ for $R_1$ to match the code) gives the boxed results `ac/equations/ClosedBranchSide1{Active,Reactive}FlowEquationTerm.java`: 

$$
\boxed{\,P_1 = r_1 V_1\bigl(g_1 r_1 V_1 + y\,r_1 V_1\sin\xi - y\,R_2 V_2\sin\theta_1\bigr)\,}
$$ (eq:P1)

$$
\boxed{\,Q_1 = r_1 V_1\bigl(-b_1 r_1 V_1 + y\,r_1 V_1\cos\xi - y\,R_2 V_2\cos\theta_1\bigr)\,}
$$ (eq:Q1)

 ($R_2=1$ is kept symbolic to mirror the source.) The structure is universal: a *self* term quadratic in $V_1$ (shunt $+$ series), and a *mutual* term bilinear in $V_1V_2$ carrying all the angle dependence.

(sec:p2q2)=

## Side-2 power
By the identical argument applied at node $2'$ --- current $\Ic_2'=(g_2+\jj b_2)\Vc_2'+\underline y_{12}(\Vc_2'-\Vc_1')$, power $\Sc_2=\Vc_2'\conj{\Ic_2'}$ --- and using $\theta_2$ of {eq}`eq:thetas` one obtains `ac/equations/ClosedBranchSide2{Active,Reactive}FlowEquationTerm.java` 

$$
\boxed{\,P_2 = R_2 V_2\bigl(g_2 R_2 V_2 - y\,r_1 V_1\sin\theta_2 + y\,R_2 V_2\sin\xi\bigr)\,}
$$ (eq:P2)

$$
\boxed{\,Q_2 = R_2 V_2\bigl(-b_2 R_2 V_2 - y\,r_1 V_1\cos\theta_2 + y\,R_2 V_2\cos\xi\bigr)\,}
$$ (eq:Q2)

 The asymmetry between {eq}`eq:P1` and {eq}`eq:P2` (which side carries $r_1$) is exactly the transformer ratio sitting on side 1.

:::{admonition} Remark - Sign/flow convention
:class: seealso
$P_1,Q_1$ are powers *injected into the branch at bus 1*; $P_2,Q_2$ at bus 2. With losses, $P_1+P_2\ge 0$ equals the branch active losses $y\sin\xi\,(\dots)$, never zero except for a lossless line. These four quantities are the terms that the bus balance equations of Chapter {ref}`ch:bus` sum up.

:::

(sec:branchderiv)=

## Partial derivatives (Jacobian entries)
Newton--Raphson needs $\partial\{P_1,Q_1,P_2,Q_2\}/\partial\{V_1,V_2,
\varphi_1,\varphi_2,\alpha,\rho\}$. Differentiating {eq}`eq:P1`--{eq}`eq:Q2` is mechanical once one notes $\partial\theta_1/\partial\varphi_1=-1,\
\partial\theta_1/\partial\varphi_2=+1,\
\partial\theta_1/\partial\alpha=-1$, and the mirror signs for $\theta_2$. We list the full set for side 1 active power; the others follow the same pattern and are collected in {numref}`tab:derivs`.

### Derivatives of $P_1$

[`ClosedBranchSide1ActiveFlowEquationTerm.java`](https://github.com/powsybl/powsybl-open-loadflow/blob/main/src/main/java/com/powsybl/openloadflow/ClosedBranchSide1ActiveFlowEquationTerm.java) 

$$
\begin{aligned}
  \pdv{P_1}{V_1} &= r_1\bigl(2g_1 r_1 V_1 + 2y r_1 V_1\sin\xi - y R_2 V_2\sin\theta_1\bigr),\\
  \pdv{P_1}{V_2} &= -y\,r_1 R_2 V_1\sin\theta_1,\\
  \pdv{P_1}{\varphi_1} &= y\,r_1 R_2 V_1 V_2\cos\theta_1,\\
  \pdv{P_1}{\varphi_2} &= -\pdv{P_1}{\varphi_1},
  \qquad
  \pdv{P_1}{\alpha}=\pdv{P_1}{\varphi_1},\\
  \pdv{P_1}{\rho}\Big|_{\rho=r_1} &= V_1\bigl(2 r_1 V_1(g_1+y\sin\xi) - y R_2 V_2\sin\theta_1\bigr).
\end{aligned}
$$

 The relation $\partial P_1/\partial\varphi_2=-\partial P_1/\partial\varphi_1$ and $\partial P_1/\partial\alpha=\partial P_1/\partial\varphi_1$ holds for *every* one of the four powers, because $\theta_1$ depends on $\varphi_1,\varphi_2,\alpha$ only through the combination $-\varphi_1+\varphi_2-\alpha$. This is why the code defines each $\varphi_2$- and $\alpha$-derivative as $\pm$ the $\varphi_1$-derivative (`dp1dph2 = -dp1dph1`, `dp1da1 = dp1dph1`).

:::{table} The non-trivial partial derivatives of the four closed-branch power flows. In every row $\partial/\partial\varphi_2=-\partial/\partial\varphi_1$; $\partial/\partial\alpha=\partial/\partial\varphi_1$ for side 1 and $\partial/\partial\alpha=\partial/\partial\varphi_1$ for side 2 as well (see code).
:name: tab:derivs

|  | $\partial/\partial V_1$ | $\partial/\partial V_2$ | $\partial/\partial\varphi_1$ |
|:---|:---|:---|:---|
| $P_1$ | $r_1(2g_1r_1V_1+2yr_1V_1\sin\xi-yR_2V_2\sin\theta_1)$ | $-yr_1R_2V_1\sin\theta_1$ | $yr_1R_2V_1V_2\cos\theta_1$ |
| $Q_1$ | $r_1(-2b_1r_1V_1+2yr_1V_1\cos\xi-yR_2V_2\cos\theta_1)$ | $-yr_1R_2V_1\cos\theta_1$ | $-yr_1R_2V_1V_2\sin\theta_1$ |
| $P_2$ | $-yr_1R_2V_2\sin\theta_2$ | $R_2(2g_2R_2V_2-yr_1V_1\sin\theta_2+2yR_2V_2\sin\xi)$ | $-yr_1R_2V_1V_2\cos\theta_2$ |
| $Q_2$ | $-yr_1R_2V_2\cos\theta_2$ | $R_2(-2b_2R_2V_2-yr_1V_1\cos\theta_2+2yR_2V_2\cos\xi)$ | $+yr_1R_2V_1V_2\sin\theta_2$ |
:::

:::{admonition} Remark - Why analytic derivatives matter
:class: seealso
OLF never approximates the Jacobian by finite differences. Every entry above is the *exact* analytic derivative, evaluated at the current state. This is what gives Newton--Raphson its hallmark *quadratic* convergence (Chapter {ref}`ch:nr`): near the solution the error squares at each step, so 3--5 iterations typically suffice.

:::

(sec:current)=

## Branch current magnitude
Some controls and all monitoring need the *current magnitude* $I_1$ at a terminal, not just power. From {eq}`eq:I1`, write the per-unit terminal current $\Ic_1=\Ic_1'$ (referred appropriately) in Cartesian form $\Ic_1=I_1^{\Re}+\jj I_1^{\Im}$. The code groups the bus-1-only part into [`ac/equations/ClosedBranchSide1CurrentMagnitudeEquationTerm.java`](https://github.com/powsybl/powsybl-open-loadflow/blob/main/src/main/java/com/powsybl/openloadflow/ac/equations/ClosedBranchSide1CurrentMagnitudeEquationTerm.java) 

$$
\begin{aligned}
  I_1^{\Re} &= r_1\bigl(r_1 V_1[\,g_1\cos\varphi_1-b_1\sin\varphi_1+y\sin(\varphi_1+\xi)\,]
              - y R_2 V_2\sin\vartheta\bigr),\\
  I_1^{\Im} &= r_1\bigl(r_1 V_1[\,g_1\sin\varphi_1+b_1\cos\varphi_1-y\cos(\varphi_1+\xi)\,]
              + y R_2 V_2\cos\vartheta\bigr),
\end{aligned}
$$

 with the shorthand $\vartheta=\xi-\alpha+A_2+\varphi_2$. The magnitude and its derivatives are then obtained by the chain rule on $I_1=\sqrt{(I_1^{\Re})^2+(I_1^{\Im})^2}$: 

$$
I_1=\bigl\lVert(I_1^{\Re},I_1^{\Im})\bigr\rVert,\qquad
  \pdv{I_1}{u}=\frac{I_1^{\Re}\,\partial_u I_1^{\Re}+I_1^{\Im}\,\partial_u I_1^{\Im}}{I_1},
  \quad u\in\{V_1,V_2,\varphi_1,\varphi_2,\alpha\}.
$$ (eq:dI)

 The derivative with respect to $\rho$ is not implemented (it throws): current magnitude is only ever used where $\rho$ is constant. To convert per-unit current to amperes, multiply by the base current $I_{\mathrm B}=\nicefrac{1000\,\SB}
{(\sqrt3\,V_{\text{nom}})}$ of §{ref}`sec:perunit`.

(sec:openbranch)=

## Reduced "open branch" flows
When a branch is connected at only *one* end (e.g. a line opened by a switch on side 1 but still energised from side 2), the dangling side carries no current but the energised side still sees the series impedance in series with the *far* shunt, all to ground. Eliminating the floating internal node algebraically yields a one-port admittance. For a branch open on side 1 (only bus 2 connected), the active and reactive injections at bus 2 are `ac/equations/OpenBranchSide1{Active,Reactive}FlowEquationTerm.java` 

$$
\begin{aligned}
  P_2 &= R_2^2 V_2^2\Bigl(g_2 + \tfrac{y^2 g_1}{s}
        + \tfrac{(b_1^2+g_1^2)\,y\sin\xi}{s}\Bigr),\\
  Q_2 &= -R_2^2 V_2^2\Bigl(b_2 + \tfrac{y^2 b_1}{s}
        - \tfrac{(b_1^2+g_1^2)\,y\cos\xi}{s}\Bigr),
\end{aligned}
$$

 where the denominator 

$$
s=(g_1+y\sin\xi)^2+(-b_1+y\cos\xi)^2
$$

 is the squared magnitude of (far shunt $+$ series) admittance [`AbstractOpenSide1BranchAcFlowEquationTerm.java`](https://github.com/powsybl/powsybl-open-loadflow/blob/main/src/main/java/com/powsybl/openloadflow/AbstractOpenSide1BranchAcFlowEquationTerm.java). These depend on a single voltage, so their only derivative is $\partial P_2/\partial V_2=2P_2/V_2$ etc. The symmetric \"open on side 2\" formulas swap the subscripts $1\leftrightarrow 2$ and carry the $r_1^2$ factor.

(sec:hvdc)=

## HVDC links
An HVDC link joins the AC network through two converter *stations* --- line-commutated (LCC) or voltage-source (VSC). How its active power is set splits into two regimes: a fixed *set-point* (LCC, or VSC in set-point mode), and the angle-following *AC emulation* (VSC only). Both share the same loss model.

(sec:hvdcloss)=

### Converter and cable losses
Power crossing a station is reduced by a per-station loss factor $\lambda$ (a percentage of the through-power), and the cable itself dissipates a Joule loss. The cable loss is computed at nominal DC voltage (powsybl-core's `HvdcUtils.getHvdcLineLosses`) 

$$
P^{\text{cable}}=R\,i^2,\qquad i=\frac{P_1}{V},
$$ (eq:hvdcloss)

 with $R$ the line resistance, $P_1$ the active power leaving the controller (rectifier) station on the DC side, and $V$ the nominal DC voltage --- equal to $1\pu$ in the per-unit equation terms. Throughout, the rectifier is the station power flows *from* and the inverter the one it flows *to*.

(sec:hvdcsetpoint)=

### Active-power set-point mode
In set-point mode the AC-side active power transmitted from rectifier to inverter equals a target $P$ (powsybl-core's `HvdcUtils.getConverterStationTargetP`). Applying the rectifier loss factor, then the cable loss {eq}`eq:hvdcloss`, then the inverter loss factor gives 

$$
P_{\text{rect}}=P,\qquad
  P_{\text{inv}}=-(1-\lambda_{\text{inv}})\Bigl[(1-\lambda_{\text{rect}})\,P-P^{\text{cable}}\Bigr],
$$ (eq:hvdcsetpoint)

 (load sign convention: the rectifier AC terminal absorbs, the inverter injects, hence the minus). LCC and VSC use the *same* formula {eq}`eq:hvdcsetpoint`; they differ only in reactive behaviour (below).

### AC emulation (VSC)

A VSC link in *AC emulation* mimics an AC line by imposing a *droop*: the DC active power follows the angle difference of its two AC terminals. The raw set-point power is [`ac/equations/AbstractHvdcAcEmulationFlowEquationTerm.java`](https://github.com/powsybl/powsybl-open-loadflow/blob/main/src/main/java/com/powsybl/openloadflow/ac/equations/AbstractHvdcAcEmulationFlowEquationTerm.java) 

$$
P^{\text{raw}}=P_0+k\,(\varphi_1-\varphi_2),\qquad
  k=\text{droop}\cdot\frac{180}{\pi},
$$ (eq:hvdc)

 where the $\nicefrac{180}{\pi}$ converts the configured droop (MW per *degree*) to MW per *radian*, because $\varphi$ is in radians (the droop and $P_0$ are additionally per-unitised on $\SB$). The power actually injected at side 1 is $P^{\text{raw}}$ if converter 1 is the rectifier ($P^{\text{raw}}\ge 0$), otherwise the negative of the losses-adjusted power $-(1-\lambda_2)\bigl[(1-\lambda_1)|P^{\text{raw}}|-P^{\text{cable}}\bigr]$ --- the same loss chain as {eq}`eq:hvdcsetpoint` with $P\!\to\!|P^{\text{raw}}|$. The Jacobian entries are piecewise-constant: 

$$
\pdv{P_1}{\varphi_1}=\begin{cases}
     k & P^{\text{raw}}\ge 0,\\
     k\,(1-\lambda_1)(1-\lambda_2) & P^{\text{raw}}<0,
  \end{cases}
  \qquad \pdv{P_1}{\varphi_2}=-\pdv{P_1}{\varphi_1},
$$

 (the cable-loss derivative is neglected).

### Power limits

In both regimes the transmitted power is capped by a maximum $P_{\max}$ --- either the link's `maxP`, or, if present, direction-specific values from the HVDC operator active-power-range extension [`network/impl/LfHvdcImpl.java`](https://github.com/powsybl/powsybl-open-loadflow/blob/main/src/main/java/com/powsybl/openloadflow/network/impl/LfHvdcImpl.java). In AC emulation the cap is *not* applied inside the smooth term {eq}`eq:hvdc`; saturation is enforced after the solve by a dedicated outer loop (§{ref}`sec:hvdcloop`), which exists for both the AC and DC load flows. This keeps Newton--Raphson seeing a differentiable equation.

### Reactive power

The reactive behaviour distinguishes the two technologies. An **LCC** station always *absorbs* reactive power, set by its configured power factor (powsybl-core's `HvdcUtils.getLccConverterStationLoadTargetQ`): 

$$
Q=\bigl\lvert P\,\tan(\arccos(\text{pf}))\bigr\rvert .
$$ (eq:lccq)

 A **VSC** station behaves like a generator: if voltage regulation is on it holds its AC voltage set-point (and $Q$ is free); otherwise it injects its configured reactive set-point.
