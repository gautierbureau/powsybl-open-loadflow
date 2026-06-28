(ch:fundamentals)=

(ch:fund)=
# Electrical fundamentals
This chapter recalls the minimum amount of electrical-engineering background needed to read the rest of the document: phasors, complex power, the admittance of a component, and the *per-unit* normalisation. A reader who already knows these can skip to Chapter {ref}`ch:network`.

## Sinusoidal steady state and phasors

In an AC network operating in sinusoidal steady state at angular frequency $\omega = 2\pi f$, every voltage and current is a sinusoid 

$$
v(t) = \sqrt{2}\,V\cos(\omega t + \theta),
$$

 characterised by only two numbers: its *root-mean-square (RMS) magnitude* $V$ and its *phase* $\theta$. It is therefore represented by a single complex number, the **phasor** 

$$
\Vc = V\,\e^{\jj\theta} = V(\cos\theta + \jj\sin\theta).
$$

 The load flow never deals with $t$ directly: it solves for the phasors $\Vc_i$ at every bus $i$. We write $\Vc_i = V_i\,\e^{\jj\theta_i}$, and in OLF the two real unknowns per bus are exactly $V_i$ (symbol `v`, variable type `BUS_V`) and $\theta_i$ (symbol $\varphi$, variable type `BUS_PHI`).

### Why two unknowns per bus.

A complex number has two real degrees of freedom. The whole AC load flow is, at heart, the statement that the complex unknowns $\Vc_i$ must satisfy the complex power-balance equations $\Sc_i = \Sc_i^{\text{spec}}$ at every bus --- two real equations per complex equation. Counting is everything in Chapter {ref}`ch:bus`.

## Impedance, admittance and Ohm's law

A passive two-terminal element is described by its complex **impedance** $\underline{z}=r+\jj x$ (resistance $r$, reactance $x$) or, equivalently, its complex **admittance** 

$$
\Yc = \frac{1}{\underline{z}} = \frac{1}{r+\jj x}
        = \frac{r-\jj x}{r^2+x^2}
        = g + \jj b ,
\qquad
  g=\frac{r}{r^2+x^2},\quad b=\frac{-x}{r^2+x^2}.
$$

 $g$ is the *conductance* and $b$ the *susceptance*. Ohm's law in phasor form relates the current through the element to the voltage across it: $\Ic = \Yc\,\Delta\Vc$. Kirchhoff's current law (KCL) states that, at every bus, the sum of all currents leaving through the connected branches equals the current injected by the generators/loads at that bus. The load flow is KCL written in terms of *power* rather than current.

OLF stores the branch series parameters as $r$ and $x$ and derives the modulus of the admittance and an angle from them (Chapter {ref}`ch:network`); the precise definitions 

$$
y \;=\; \abs{\Yc} \;=\; \frac{1}{\sqrt{r^2+x^2}},
  \qquad
  \xi \;=\; \operatorname{atan2}(r,x)
$$

 are introduced and motivated in §{ref}`series admittance <sec:ymodel>`.

(sec:cpower)=

## Complex power
The **complex power** delivered into an element whose terminal voltage is $\Vc$ and whose injected current is $\Ic$ is, by definition, 

$$
\boxed{\;\Sc \;=\; \Vc\,\conj{\Ic} \;=\; P + \jj Q\;}
$$

 where $P$ (watts) is the *active* power and $Q$ (vars) the *reactive* power, and $\conj{\Ic}$ is the complex conjugate of the current phasor. The conjugate is what makes $P=\Re(\Vc\conj{\Ic})$ equal to the time-average of $v(t)i(t)$. This single definition, combined with Ohm's law, generates *all* of the branch flow equations of Chapter {ref}`ch:branch`: there is nothing else.

:::{admonition} Remark
:class: seealso
Sign convention in OLF: injections (generation) are positive, consumptions (loads) are negative, and a branch flow $P_1$ is counted positive when it *leaves* bus 1 into the branch. These conventions are fixed once and for all in the target vector (§{ref}`sec:targets`).

:::

(sec:perunit)=

## The per-unit system
Real networks span voltages from a few hundred volts to 765 kV and powers from kVA to GVA. Working with SI units would mix numbers of wildly different magnitude in the same matrix and wreck the conditioning of the linear solves. The remedy, universal in power engineering, is to express every quantity as a fraction of a *base* value. OLF fixes a single, system-wide base power and, per voltage level, a base voltage equal to the nominal voltage.

[`util/PerUnit.java`](https://github.com/powsybl/powsybl-open-loadflow/blob/main/src/main/java/com/powsybl/openloadflow/util/PerUnit.java) 

$$
\SB = 100~\text{MVA} \quad(\text{base power, constant}),\qquad
  V_{\mathrm{B}} = V_{\text{nom}} \quad(\text{base voltage, per voltage level}).
$$

 From these two, every other base is derived by dimensional analysis: 

$$
Z_{\mathrm{B}} = \frac{V_{\text{nom}}^{2}}{\SB}\ \ (\Omega),
  \qquad
  I_{\mathrm{B}} = \frac{\SB}{\sqrt{3}\,V_{\text{nom}}}\ \ (\text{three-phase}),
  \qquad
  Y_{\mathrm{B}} = \frac{1}{Z_{\mathrm{B}}}.
$$

 In code, `zb(nominalV)`$=V_{\text{nom}}^2/\SB$, and the current base carries the factor $\sqrt3$ and a $1000$ for the kV$\to$V / kA bookkeeping: `ib`$=\nicefrac{1000\,\SB}{(\sqrt3\,V_{\text{nom}})}$.

The conversions actually applied when the `LfNetwork` is built are [`network/impl/LfBranchImpl.java`](https://github.com/powsybl/powsybl-open-loadflow/blob/main/src/main/java/com/powsybl/openloadflow/network/impl/LfBranchImpl.java) 

$$
\begin{aligned}
  r &= R/Z_{\mathrm B}, & x &= X/Z_{\mathrm B}
      &&\text{(series impedance $\div$ base impedance)},\\
  g_k &= G_k\,Z_{\mathrm B}, & b_k &= B_k\,Z_{\mathrm B}
      &&\text{(shunt admittance $\times$ base impedance), } k\in\{1,2\}.
\end{aligned}
$$

 Powers are divided by $\SB$ and angles are left in radians. From here on, *every symbol is in per-unit* unless a physical unit is stated. The great convenience is that a nominal bus then sits at $V\approx 1\pu$, so a flat start ($V=1,\ \theta=0$) is an excellent initial guess (Chapter {ref}`ch:init`).

:::{admonition} Example - Base conversion
:class: tip
A line of $X=20~\Omega$ on a $400~\text{kV}$ level has $Z_{\mathrm B}=400^2/100 = 1600~\Omega$, hence $x = 20/1600 = 0.0125\pu$. A generator producing $250~\text{MW}$ injects $P=250/100 = 2.5\pu$.

:::
