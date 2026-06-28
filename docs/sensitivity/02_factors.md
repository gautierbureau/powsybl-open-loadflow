(ch:factors)=

# Sensitivity factors: variables and functions
A sensitivity factor pairs a *function* (the output being differentiated) with a *variable* (the input). This short chapter fixes the taxonomy exactly as OLF implements it, because the rest of the volume is just "how to compute $\partial(\text{function})/\partial(\text{variable})$ for each pair".

## Functions (outputs)

`sensi/AbstractSensitivityAnalysis.java (getFunctionEquationTerm)` Each function type maps to an *equation term* $h(\xx)$ already built by the load flow ({ref}`part:ac`, Ch. {ref}`ch:branch`), so its value and its derivatives $\partial h/\partial\xx$ are available for free:

::: center
| Function type               | term $h(\xx)$                 | AC / DC |
|:----------------------------|:------------------------------|:--------|
| `BRANCH_ACTIVE_POWER_1/2`   | $P_1,P_2$ branch flow         | both    |
| `BRANCH_CURRENT_1/2`        | $I_1,I_2$ current magnitude   | AC      |
| `BRANCH_REACTIVE_POWER_1/2` | $Q_1,Q_2$ branch flow         | AC      |
| `BUS_VOLTAGE`               | $V$ (calculated voltage term) | AC      |
| `BUS_REACTIVE_POWER`        | bus $Q$ injection             | AC      |
:::

DC restricts functions to active-power branch flows (the only thing the DC model represents); AC offers the full set.

## Variables (inputs)

`sensi/AbstractSensitivityAnalysis.java (SingleVariableFactorGroup.fillRhs)` Each variable type maps to a *right-hand-side vector* $\bm e$ --- the perturbation injected into the equation system (Chapter {ref}`ch:adjoint`):

::: center
| Variable type | where the RHS unit goes | value |
|:---|:---|:---|
| `INJECTION_ACTIVE_POWER` | bus active-power equation $+$ slack | $+1$, $-\kappa_i$ |
| `INJECTION_REACTIVE_POWER` | bus reactive-power equation | $+1$ |
| `TRANSFORMER_PHASE` | branch $\alpha_1$ equation | $\mathrm{rad}(1^\circ)$ |
| `BUS_TARGET_VOLTAGE` | calculated-$V$ equation | $+1$ |
| `HVDC_LINE_ACTIVE_POWER` | both converter buses | $\pm$weight |
| (GLSK set) | many buses | $w_i/\sum_j\lvert w_j\rvert$ |
:::

### Why a phase variable is scaled by $\mathrm{rad}(1^\circ)$.

Phase shifters are tapped in degrees, so the reported sensitivity is "per degree". Placing $\mathrm{rad}(1^\circ)=\pi/180$ in the right-hand side bakes the degree$\to$radian conversion into the solve, so the result needs no rescaling.

### GLSK and HVDC are multi-variable.

A Generation/Load Shift Key (GLSK) distributes a $+1$ injection over many buses with weights $w_i$ normalised by $\sum_j\lvert w_j\rvert$; an HVDC variable injects at *both* converter buses with opposite signs. These are `MultiVariablesFactorGroup`s and are otherwise treated identically (Chapter {ref}`ch:adjoint`).

(sec:groups)=

## Factor groups: one solve per variable
`sensi/AbstractSensitivityAnalysis.java (createFactorGroups)` The cost of a sensitivity is dominated by the linear solve (Chapter {ref}`ch:adjoint`), and that solve depends only on the *variable*, not the function. OLF therefore **groups** all factors that share the same $(\text{variableType},\text{variableId})$ into a `SensitivityFactorGroup` and assigns it one matrix column. Consequences:

- the number of linear solves $=$ the number of *distinct variables*, not the number of factors;

- all functions sharing a variable (e.g. "flow on every line per MW at bus $k$") are obtained from the *same* solved state column by different dot products (§{ref}`sec:dotproduct`).

This is the single most important performance idea of the analysis, and it is why a full PTDF matrix (every line vs every injection) costs one solve per *injection*, not one per (line, injection) pair.
