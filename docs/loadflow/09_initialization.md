(ch:init)=

# Voltage initialisation
Newton--Raphson is only locally convergent (§{ref}`sec:nriter`), so the starting point $\xx_0$ matters. OLF offers several *voltage initialisers*, from the trivial flat start to two that solve an auxiliary *linear* system to seed the angles or the magnitudes. This chapter derives each.

## Flat start (uniform values)

The simplest and most common start, exploiting the per-unit normalisation (§{ref}`sec:perunit`) under which every nominal bus sits near $1\pu$: [`network/util/UniformValueVoltageInitializer.java`](https://github.com/powsybl/powsybl-open-loadflow/blob/main/src/main/java/com/powsybl/openloadflow/network/util/UniformValueVoltageInitializer.java) 

$$
V_i^{(0)}=1\pu,\qquad \varphi_i^{(0)}=0 .
$$

 For a well-conditioned, lightly-loaded network this is within the basin of attraction and converges in a handful of iterations. State variables that are not voltages are seeded from the model: $\rho,\alpha$ from the current tap, $b$ from the shunt section, dummy variables from $0$ [`ac/solver/AcSolverUtil.java`](https://github.com/powsybl/powsybl-open-loadflow/blob/main/src/main/java/com/powsybl/openloadflow/ac/solver/AcSolverUtil.java).

## Warm start (previous values)

[`network/util/PreviousValueVoltageInitializer.java`](https://github.com/powsybl/powsybl-open-loadflow/blob/main/src/main/java/com/powsybl/openloadflow/network/util/PreviousValueVoltageInitializer.java) $V_i^{(0)}=V_i^{\text{prev}}$, $\varphi_i^{(0)}=\varphi_i^{\text{prev}}$, reusing the last solved state. This is what the *outer loop* uses to restart Newton after a small control change (§{ref}`sec:driver`): the previous solution is an excellent guess for the slightly-perturbed problem, so the restart costs only one or two iterations.

(sec:dcinit)=

## DC-based angle initialisation
When angles may be far from zero (heavily loaded, large phase shifts), OLF first solves a *DC load flow* and uses its angles to seed the AC run [`dc/DcValueVoltageInitializer.java`](https://github.com/powsybl/powsybl-open-loadflow/blob/main/src/main/java/com/powsybl/openloadflow/dc/DcValueVoltageInitializer.java): 

$$
V_i^{(0)}=1\pu,\qquad \varphi_i^{(0)}=\varphi_i^{\text{DC}} .
$$

 The DC load flow is the linearisation of the active-power balance under the standard approximations $V_i\approx1$, $\sin(\varphi_i-\varphi_j)\approx
\varphi_i-\varphi_j$, $\cos\approx1$, and lossless lines, which collapse {eq}`eq:Pbalance` into a *linear* system $\bm B'\bm\varphi=\bm P$ with $\bm B'$ the susceptance matrix. It is solved by one LU factorisation (no iteration), making it a very cheap pre-conditioner for the AC angles. (The DC model itself is out of scope; here it is only an initialiser.)

(sec:vminit)=

## Voltage-magnitude initialisation by a linear interpolation
For networks with transformers whose ratios are far from $1\pu$ (so magnitudes span a wide range), a flat $V=1$ start is poor. OLF then solves a bespoke *linear* system for the magnitudes only [`ac/VoltageMagnitudeInitializer.java`](https://github.com/powsybl/powsybl-open-loadflow/blob/main/src/main/java/com/powsybl/openloadflow/ac/VoltageMagnitudeInitializer.java). The idea: voltage-controlled buses are pinned to their set-points, and every other bus's magnitude is the weighted average of its neighbours, the weights being the branch susceptances and ratios.

### The two equation types

- **Controlled (PV) bus** $i$: $\;V_i = V_i^{\text{spec}}$.

- **Other bus** $i$: 

$$
0=\frac{\sum_{j\sim i}\rho_{ij}\,b_{j}\,V_j}{\sum_{j\sim i} b_{j}} - V_i,
$$

 where the sum is over neighbours $j$, $b_j=1/x$ is the branch susceptance magnitude (using $x\leftarrow\max(|x|,x_{\min})$ to avoid negative-reactance trouble), and $\rho_{ij}$ is the branch voltage ratio seen from $i$ ($\rho_{ij}=1/\rho$ if $i$ is side 1, $\rho$ if side 2), averaged over parallel branches.

Stacked over all buses this is a sparse linear system $\bm A\,\bm V=\bm V^{\text{spec}}$ solved by a single transposed LU solve (`j.solveTransposed(targets)`); the angles are left at $0$ and the AC Newton solve then corrects both. Mathematically it is one step of a Jacobi-type voltage interpolation made exact by solving the whole coupled system at once.

:::{admonition} Remark
:class: seealso
The magnitude initialiser uses the *same* generic equation framework (Chapter {ref}`ch:framework`) with its own tiny variable/equation enums (`InitVm*`), demonstrating how reusable that framework is: a completely different linear problem is expressed and solved with the identical machinery.

:::

## Choosing an initialiser

In practice: flat start for ordinary cases; DC-angle init when angles are large or convergence is marginal; magnitude init when transformer ratios spread the voltages; warm start for every outer-loop restart and for re-running a slightly modified network. The choice trades a cheap auxiliary solve against a reduced risk of slow or failed Newton convergence.
