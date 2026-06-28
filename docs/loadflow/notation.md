(ch:notation)=

# Notation and symbol table
## Mathematical symbols

::: center
| Symbol | Meaning | Code / unit |
|:---|:---|:---|
| $\jj$ | imaginary unit, $\jj^2=-1$ | --- |
| $\Vc_i=V_i\e^{\jj\theta_i}$ | complex bus voltage (phasor) | --- |
| $V_i$ | voltage magnitude at bus $i$ | `BUS_V` (`v`), p.u. |
| $\varphi_i,\theta_i$ | voltage angle at bus $i$ | `BUS_PHI` ($\varphi$), rad |
| $\Ic$ | current phasor | p.u. (or $\times I_{\mathrm B}$ for A) |
| $\Sc=P+\jj Q$ | complex power $\Vc\conj{\Ic}$ | p.u. ($\times\SB$ for MVA) |
| $P,Q$ | active / reactive power | p.u. |
| $\underline z=r+\jj x$ | series impedance | p.u. |
| $\Yc=g+\jj b$ | admittance | p.u. |
| $y$ | series admittance modulus $1/\sqrt{r^2+x^2}$ | `getY` |
| $\xi$ | series admittance angle $\operatorname{atan2}(r,x)$ | `getKsi` |
| $g_{12},b_{12}$ | series conductance/susceptance $ry^2,-xy^2$ | `g12,b12` |
| $g_1,b_1,g_2,b_2$ | shunt half-admittances (sides 1,2) | `getG1…` |
| $\rho=R_1$ | transformer voltage ratio (side 1) | `BRANCH_RHO1` ($\rho$) |
| $\alpha=A_1$ | phase-shift angle (side 1) | `BRANCH_ALPHA1` ($\alpha$) |
| $R_2{=}1,A_2{=}0$ | reference-side ratio/phase | constants |
| $\theta_1,\theta_2$ | auxiliary angles, eq. {eq}`eq:thetas` | --- |
| $b$ | shunt susceptance (controlled) | `SHUNT_B` |
| $P_{\text{dum}},Q_{\text{dum}}$ | zero-impedance dummy flows | `DUMMY_P/Q` |
| $\xx$ | state vector (unknowns) | `StateVector` |
| $\ff(\xx)$ | equation vector (LHS) | `EquationVector` |
| $\bm t$ | target vector (RHS) | `TargetVector` |
| $\bm g=\ff-\bm t$ | mismatch | --- |
| $\Jmat=\partial\ff/\partial\xx$ | Jacobian | `JacobianMatrix` (stored $\Jmat\T$) |
| $\Delta\xx$ | Newton step | --- |
| $\mu$ | step-scaling factor | §{ref}`sec:scaling` |
| $\varepsilon$ | per-equation convergence tolerance | `convEpsPerEq`, $10^{-4}$ |
| $\kappa_e$ | active-power participation factor | §{ref}`sec:distslack` |
| $\text{qPercent}_i$ | reactive-sharing key | §{ref}`shared voltage control <sec:distq>` |
| $\SB$ | base power $=100$ MVA | `PerUnit.SB` |
| $Z_{\mathrm B},I_{\mathrm B}$ | base impedance / current | `zb`, `ib` |
:::

## Key AC variable types (`AcVariableType`)

::: center
| Type                | Unknown                     |
|:--------------------|:----------------------------|
| `BUS_V`             | bus voltage magnitude $V$   |
| `BUS_PHI`           | bus voltage angle $\varphi$ |
| `BRANCH_RHO1`       | transformer ratio $\rho$    |
| `BRANCH_ALPHA1`     | phase-shift $\alpha$        |
| `SHUNT_B`           | shunt susceptance $b$       |
| `DUMMY_P`,`DUMMY_Q` | zero-impedance branch flows |
:::

## Key AC equation types (`AcEquationType`)

::: center
| Type | Equation |
|:---|:---|
| `BUS_TARGET_P` | active power balance, eq. {eq}`eq:Pbalance` |
| `BUS_TARGET_Q` | reactive power balance, eq. {eq}`eq:Qbalance` |
| `BUS_TARGET_V` | voltage set-point $V=V^{\text{spec}}$ (PV bus) |
| `BUS_TARGET_PHI` | reference angle $\varphi=0$ |
| `BRANCH_TARGET_P` | phase-shifter active-flow target |
| `BRANCH_TARGET_Q` | generator reactive-flow target |
| `BRANCH_TARGET_ALPHA1/RHO1` | constant $\alpha$ / $\rho$ |
| `SHUNT_TARGET_B` | constant shunt $b$ |
| `DISTR_Q` | reactive sharing, §{ref}`shared voltage control <sec:distq>` |
| `DISTR_RHO`,`DISTR_SHUNT_B` | ratio / susceptance sharing |
| `ZERO_V`,`ZERO_PHI` | zero-impedance constraints, §{ref}`sec:zeroimp` |
| `BUS_DISTR_SLACK_P` | multi-slack sharing, §{ref}`sec:multislack` |
:::

## Source-tree map

::: center
| Package | Content (this document) |
|:---|:---|
| `network` | `LfNetwork`, `PiModel`, per-unit (Ch. {ref}`ch:network`) |
| `ac.equations` | branch/bus/shunt/load terms (Ch. {ref}`ch:branch`--{ref}`ch:bus`) |
| `equations` | generic framework, Jacobian (Ch. {ref}`ch:jac`--{ref}`ch:framework`) |
| `ac.solver` | Newton--Raphson, scaling, stopping (Ch. {ref}`ch:nr`,{ref}`ch:stop`) |
| `ac` | engine, target vector, initialisers (Ch. {ref}`ch:init`,{ref}`ch:outer`) |
| `ac.outerloop` | control outer loops (Ch. {ref}`ch:outer`) |
| `network.util` | slack selection, distribution, init (Ch. {ref}`ch:network`,{ref}`ch:init`) |
| (PowSyBl Math, native) | sparse storage, LU/KLU (Ch. {ref}`ch:linsolve`) |
:::
