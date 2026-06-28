(part:dc)=

(ch:dcmodel)=

# The DC load flow: model and equations
The *DC load flow* is a linearisation of the AC problem that keeps only active power and bus angles. It is exact for no model (it is an approximation), but it is *linear*, hence solved by a *single* matrix factorisation with no iteration --- making it the workhorse for fast screening and the angle initialiser of the AC solver (§{ref}`ch:init`). This chapter derives the model from the AC equations of {ref}`part:ac` and assembles its linear system.

## The four DC approximations

Start from the exact AC side-1 active flow {eq}`eq:P1` and apply, in order:

1.  **flat voltages**: $V_1=V_2=1\pu$ (magnitudes are not modelled);

2.  **small angles**: $\sin(\varphi_1-\varphi_2)\approx\varphi_1-\varphi_2$ and $\cos(\varphi_1-\varphi_2)\approx1$;

3.  **lossless branches**: neglect the series resistance in the active flow ($r\ll x$, so $\xi\to0$, $\sin\xi\to0$);

4.  **no shunts**: drop $g_1,b_1,g_2,b_2$ for active power.

Under these, the bilinear, trigonometric $P_1$ collapses to a *linear* function of the angle difference and the phase shift.

## The DC branch flow

[`dc/equations/ClosedBranchSide1DcFlowEquationTerm.java`](https://github.com/powsybl/powsybl-open-loadflow/blob/main/src/main/java/com/powsybl/openloadflow/dc/equations/ClosedBranchSide1DcFlowEquationTerm.java) With $A_2=0$ and writing $a_1=\alpha$ for the phase shift, the implemented DC flows are 

$$
\boxed{\;
  P_1=-\,\Pi\,(\varphi_2-\varphi_1-\alpha)=\Pi\,(\varphi_1-\varphi_2+\alpha),
  \qquad
  P_2=-P_1,\;}
$$ (eq:dcp)

 where the constant $\Pi$ (the code's `power`) is the branch susceptance times the ratio [`dc/equations/AbstractClosedBranchDcFlowEquationTerm.java`](https://github.com/powsybl/powsybl-open-loadflow/blob/main/src/main/java/com/powsybl/openloadflow/dc/equations/AbstractClosedBranchDcFlowEquationTerm.java) 

$$
\Pi=b\cdot \rho_1 R_2,\qquad
  b=\begin{cases}\dfrac{1}{x} & \text{approximation \texttt{IGNORE\_R}},\\[6pt]
                 \dfrac{x}{r^2+x^2} & \text{approximation \texttt{IGNORE\_G}}.\end{cases}
$$ (eq:dcpi)

 The two choices differ only in whether resistance is dropped ($1/x$) or kept in the susceptance ($x/(r^2+x^2)=-\,\Im\,\underline y_{12}$, the exact series susceptance). Crucially, the derivatives are *constants*: 

$$
\pdv{P_1}{\varphi_1}=\Pi,\qquad
  \pdv{P_1}{\varphi_2}=-\Pi,\qquad
  \pdv{P_1}{\alpha}=\Pi .
$$ (eq:dcder)

 Because these never depend on the state, the DC Jacobian is *constant* --- the single fact from which everything else follows.

## The nodal system: $\bm B'\bm\varphi=\bm P$

Summing the incident branch flows at each bus (KCL) gives the linear nodal balance [`dc/equations/DcEquationSystemCreator.java`](https://github.com/powsybl/powsybl-open-loadflow/blob/main/src/main/java/com/powsybl/openloadflow/dc/equations/DcEquationSystemCreator.java) 

$$
\boxed{\;\bm B'\bm\varphi=\bm P,\qquad \bm B'=\Amat^{\top}\bm\Pi\,\Amat,\;}
$$ (eq:dcsystem)

 with $\Amat$ the branch--bus incidence matrix, $\bm\Pi=\operatorname{diag}(\Pi_\ell)$ the branch susceptances, and $\bm P$ the vector of net bus injections. $\bm B'$ is the weighted graph Laplacian (the same matrix whose inverse yields the PTDF in {ref}`part:sensi`); it is symmetric, sparse, and singular by the angle gauge. The DC "Jacobian" that OLF factorises is exactly this $\bm B'$, and the AC machinery of {ref}`part:ac` (variables, equations, sparse assembly) is reused verbatim with $\texttt{DcVariableType}=\{\texttt{BUS\_PHI},\texttt{BRANCH\_ALPHA1},
\texttt{DUMMY\_P}\}$.

### Reference and slack

The gauge and the global balance are fixed exactly as in AC (§{ref}`sec:refeq`--§{ref}`sec:slackeq`):

- the **reference bus** gets `BUS_TARGET_PHI`: $\varphi_{\text{ref}}=0$, removing the singular null-direction;

- the first **slack bus** has its `BUS_TARGET_P` row *deactivated* --- it absorbs the active imbalance.

(sec:dctarget)=

## The DC target vector
[`dc/DcTargetVector.java`](https://github.com/powsybl/powsybl-open-loadflow/blob/main/src/main/java/com/powsybl/openloadflow/dc/DcTargetVector.java) The right-hand side $\bm P$ collects, per bus, the net scheduled injection 

$$
P_i=\text{(generation)}-\text{(load)}=\texttt{bus.getTargetP()},
$$

 plus two kinds of *constant* contributions that are folded in through each term's `rhs()` (subtracted as `targets[col] -= equation.rhs()`):

- **phase shifters with fixed $\alpha$**: a branch flow term contributes $\texttt{rhs}=-\Pi(A_2-\alpha)=\Pi\,\alpha$ to its two buses --- the phase shift enters the load flow as a pair of fixed injections $\pm\Pi\alpha$. (When $\alpha$ is a *control variable*, this dependence moves into the matrix and `rhs` drops to $0$.)

- **HVDC in AC emulation**: the set-point $P_0$ of the droop law $P=P_0+k(\varphi_1-\varphi_2)$ contributes $\texttt{rhs}=\pm P_0$, again a fixed injection pair, while the $k(\varphi_1-\varphi_2)$ part enters $\bm B'$ (§{ref}`sec:dchvdc`).

For multiple slack buses the extra slack rows carry the corrective target $P_i+\Delta/n_{\text{slack}}$, where $\Delta=-\sum_i(\text{gen}_i-\text{load}_i)$ is the total mismatch --- the DC analogue of the multi-slack sharing of §{ref}`sec:multislack`.

(sec:dchvdc)=

## HVDC AC emulation in DC
[`dc/equations/HvdcAcEmulationSide1DCFlowEquationTerm.java`](https://github.com/powsybl/powsybl-open-loadflow/blob/main/src/main/java/com/powsybl/openloadflow/dc/equations/HvdcAcEmulationSide1DCFlowEquationTerm.java) The droop law linearises trivially (no losses, no saturation inside the term): 

$$
P_1=k\,(\varphi_1-\varphi_2)+P_0,\qquad k=\text{droop}\cdot\frac{180}{\pi},
$$

 with constant derivatives $\partial P_1/\partial\varphi_1=k$, $\partial P_1/\partial\varphi_2=-k$, and $P_0$ moved to the target (§{ref}`sec:dctarget`). The $\nicefrac{180}{\pi}$ converts the configured MW/degree droop to MW/radian, exactly as in the AC term (§{ref}`sec:hvdc`).

## Zero-impedance branches in DC

A zero-impedance branch cannot use $\Pi=1/x=\infty$. As in AC (§{ref}`sec:zeroimp`), OLF imposes the angle-equality constraint and carries the unknown flow as a dummy variable [`dc/equations/DcEquationSystemCreator.java`](https://github.com/powsybl/powsybl-open-loadflow/blob/main/src/main/java/com/powsybl/openloadflow/dc/equations/DcEquationSystemCreator.java): 

$$
\texttt{ZERO\_PHI}:\ \varphi_1-\varphi_2=\alpha,\qquad
  \text{dummy }P=\texttt{DUMMY\_P}\ \text{injected as }\pm P\text{ at the two buses,}
$$

 keeping the system square. This is the DC restriction of the AC zero-impedance device (there is no $V$-equality and no $Q$-dummy, since DC has neither).
