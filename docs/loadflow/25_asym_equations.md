(ch:asymeq)=

# The asymmetric equation system
The asymmetric system extends the balanced one (`AsymmetricalAcEquationSystemCreator` subclasses the ordinary `AcEquationSystemCreator`, [`ac/equations/asym/AsymmetricalAcEquationSystemCreator.java`](https://github.com/powsybl/powsybl-open-loadflow/blob/main/src/main/java/com/powsybl/openloadflow/ac/equations/asym/AsymmetricalAcEquationSystemCreator.java)): it keeps every positive-sequence variable and equation of the AC load flow and adds the two extra sequences on top. The mathematically distinctive choice is the *mix of coordinates* --- power balance in the positive sequence, *current* balance in the others --- which this chapter motivates and then assembles element by element.

## Variables: three voltages per bus

Each bus carries its positive-sequence voltage $(V,\varphi)$ exactly as before, plus a zero- and a negative-sequence voltage ([`ac/equations/AcVariableType.java`](https://github.com/powsybl/powsybl-open-loadflow/blob/main/src/main/java/com/powsybl/openloadflow/ac/equations/AcVariableType.java)): 

$$
\underbrace{\texttt{BUS\_V},\ \texttt{BUS\_PHI}}_{\text{positive }(+)}
  \quad\big|\quad
  \underbrace{\texttt{BUS\_V\_ZERO},\ \texttt{BUS\_PHI\_ZERO}}_{\text{zero }(h)}
  \quad\big|\quad
  \underbrace{\texttt{BUS\_V\_NEGATIVE},\ \texttt{BUS\_PHI\_NEGATIVE}}_{\text{negative }(i)} .
$$

 The state vector $\bm x$ of the AC load flow simply grows by four entries per (unbalanced) bus; $\bm f(\bm x)$ grows to match. Nothing in the Newton/Jacobian/LU framework changes --- it is the *same* solver on a larger system.

(sec:mixedcoords)=

## Equations: power for $+$, current for $0$ and $-$
In the positive sequence the bus injection is a specified *power* (generator schedules, balanced load), so OLF keeps the familiar active/reactive balance $\texttt{BUS\_TARGET\_P}$, $\texttt{BUS\_TARGET\_Q}$ (Chapter {ref}`ch:bus`). In the zero and negative sequences there is no scheduled power --- those sequences exist only as the *response* to unbalance --- and the natural conserved quantity is *current*. OLF therefore writes a real/imaginary current balance per off-sequence bus ([`ac/equations/AcEquationType.java`](https://github.com/powsybl/powsybl-open-loadflow/blob/main/src/main/java/com/powsybl/openloadflow/ac/equations/AcEquationType.java)): 

$$
\begin{aligned}
  &\underbrace{\texttt{BUS\_TARGET\_IX\_ZERO},\ \texttt{BUS\_TARGET\_IY\_ZERO}}_{I_x^{0}=0,\ I_y^{0}=0}\\[4pt]
  &\underbrace{\texttt{BUS\_TARGET\_IX\_NEGATIVE},\ \texttt{BUS\_TARGET\_IY\_NEGATIVE}}_{I_x^{-}=0,\ I_y^{-}=0},
  \end{aligned}
$$

 i.e. the sum of sequence currents leaving each bus (line terms $+$ shunt/load terms) must vanish, written in Cartesian parts $I_x=\Re\Ic$, $I_y=\Im\Ic$.

:::{admonition} Remark - Why current, not power
:class: seealso
Using $(I_x,I_y)$ in the off-sequences has three virtues. First, it is the linearly natural balance: line contributions are $\Ic=\Yc\,\Vc$, *bilinear* in admittance and voltage, with no division. Second, it avoids dividing by the (near-zero) off-sequence voltages a $P/Q$ form would require. Third, the coupling elements (loads, machines) are most simply expressed as sequence *current* injections. The positive sequence keeps $P/Q$ because that is where the data --- scheduled power --- actually lives.

:::

## Lines: block-diagonal, except on open phases

A line contributes a sequence $\pi$-model per sequence: `LfAsymLine` holds `piZeroComponent`, `piPositiveComponent`, `piNegativeComponent` ([`network/LfAsymLine.java`](https://github.com/powsybl/powsybl-open-loadflow/blob/main/src/main/java/com/powsybl/openloadflow/network/LfAsymLine.java)). For a symmetric line these are independent (§{ref}`ch:seq`), so each sequence's current terms involve only that sequence's voltages --- the classic closed-branch $I_x/I_y$ terms, added to the matching sequence balance. The full description is the $6\times6$ sequence-admittance matrix $\bm Y_{0+-}$ (terminals $\times$ sequences) built by `LfAsymLineAdmittanceMatrix` ([`network/LfAsymLineAdmittanceMatrix.java`](https://github.com/powsybl/powsybl-open-loadflow/blob/main/src/main/java/com/powsybl/openloadflow/network/LfAsymLineAdmittanceMatrix.java)) from the per-sequence $g_{12}=R/(R^2+X^2)$, $b_{12}=-X/(R^2+X^2)$.

When a phase is *open*, symmetry breaks: the admittance is assembled in phase coordinates with the open-phase constraint and transformed back, $\bm Y_{0+-}=\bm{\mathcal F}^{-1}\bm Y_{abc}\bm{\mathcal F}$, which populates the *off-diagonal* sequence blocks. `isCoupled()` detects this; the branch then uses the *coupled* current term (`AsymmetricalClosedBranchCoupledCurrentEquationTerm`, [`ac/equations/asym/AsymmetricalClosedBranchCoupledCurrentEquationTerm.java`](https://github.com/powsybl/powsybl-open-loadflow/blob/main/src/main/java/com/powsybl/openloadflow/ac/equations/asym/AsymmetricalClosedBranchCoupledCurrentEquationTerm.java)), whose injected current at terminal $i$, sequence $g$, sums over the remote terminal $j$ and *all* sequences $h$: 

$$
\Ic_i^{\,g}=\sum_{j}\sum_{h\in\{0,+,-\}}
    \rho_i\rho_j\,\e^{\jj(a_j-a_i)}\;\conj{\Yc}_{ij}^{\,gh}\,\Vc_j^{\,h},
$$ (eq:coupled)

 with real/imaginary parts `ix`/`iy` expanded from the stored $y_{ij}^{gh}$ entries. The double sum over $h$ is precisely the inter-sequence coupling that an open phase introduces.

## Machines and shunts: a sequence admittance

A rotating machine presents different impedances to the three sequences. At a voltage-controlled bus, OLF accumulates the generators' zero- and negative-sequence admittances into an equivalent shunt $(g_z+\jj b_z)$, $(g_n+\jj b_n)$ on the bus (`LfAsymGenerator`, `LfAsymBus`; [`network/LfAsymBus.java`](https://github.com/powsybl/powsybl-open-loadflow/blob/main/src/main/java/com/powsybl/openloadflow/network/LfAsymBus.java)). Each such shunt injects a sequence current $\Ic=(g+\jj b)\Vc$, contributed to the off-sequence balance through the Fortescue shunt terms ([`ac/equations/asym/ShuntFortescueIxEquationTerm.java`](https://github.com/powsybl/powsybl-open-loadflow/blob/main/src/main/java/com/powsybl/openloadflow/ac/equations/asym/ShuntFortescueIxEquationTerm.java)): 

$$
I_x = g\,V\cos\varphi - b\,V\sin\varphi,\qquad
  I_y = g\,V\sin\varphi + b\,V\cos\varphi,
$$ (eq:shuntseq)

 for the relevant sequence's $(V,\varphi)$, with closed-form derivatives feeding the Jacobian. These admittances are what tie the negative/zero networks to ground and make them non-trivially solvable.

## Unbalanced loads: the coupling heart

A per-phase constant-power load is where all three sequences meet (`LoadFortescuePowerEquationTerm`, [`ac/equations/asym/LoadFortescuePowerEquationTerm.java`](https://github.com/powsybl/powsybl-open-loadflow/blob/main/src/main/java/com/powsybl/openloadflow/ac/equations/asym/LoadFortescuePowerEquationTerm.java)). Given the specified phase powers $\Sc_a,\Sc_b,\Sc_c$ (the balanced part plus per-phase deltas $\Delta P_a,\dots$ stored on `LfAsymBus`), the load's sequence-current injection is obtained by a round trip through Fortescue:

1.  reconstruct the phase voltages from the sequence unknowns, $\Vc_{abc}=\Amat_F\,\Vc_{0+-}$;

2.  form each phase's constant-power current conjugate $\conj{\Ic}_{p}=\Sc_p/\Vc_{p}$ for $p\in\{a,b,c\}$;

3.  transform back to sequence currents, $\Ic_{0+-}=\Amat_F^{-1}\,\Ic_{abc}$.

The zero- and negative-sequence results are added (as $I_x,I_y$) to the off-sequence current balances, while the positive-sequence result enters the ordinary $P/Q$ balance as $\Sc_+ = \Vc_+\conj{\Ic}_+$. Because step 2 divides power by the *phase* voltage --- itself a Fortescue combination of all three sequence unknowns --- this single element couples every sequence to every other, which is exactly why an unbalanced load drives the negative and zero networks. The term ships with its full analytic Jacobian $\partial(I_x,I_y)/\partial(V_0,\varphi_0,
V_+,\varphi_+,V_-,\varphi_-)$ (`dpq`).

:::{admonition} Remark - Power vs. current at the branch
:class: seealso
The branch *power* coupled term (`AsymmetricalClosedBranchCoupledPowerEquationTerm`) mirrors {eq}`eq:coupled` but carries *both* terminal voltages $V_i^{g}V_j^{h}$, used where a sequence *power* flow is wanted (e.g. a positive-sequence $P/Q$ on a coupled branch); the current term carries only the remote voltage. The two are the asymmetric generalisations of the side-1 power and current equations of Chapter {ref}`ch:branch`.

:::
