(ch:limits)=

# Limit violations and reporting
A solved post-contingency state is only half the answer; the security analysis must decide whether that state is *acceptable*. This chapter covers the quantities checked, the permanent/temporary limit logic, limit reductions, and --- the part specific to contingency analysis --- how a post-contingency violation is compared against the base case so the operator sees only what the contingency *added*.

## Checked quantities

For every surviving branch, bus and monitored angle pair, `LimitViolationManager` evaluates the operating limits ([`sa/LimitViolationManager.java`](https://github.com/powsybl/powsybl-open-loadflow/blob/main/src/main/java/com/powsybl/openloadflow/sa/LimitViolationManager.java)). Each quantity is compared in per-unit against its limit, scaled back to engineering units only for reporting:

::: center
| Type | Quantity | Where | Scale |
|:---|:---|:---|:---|
| `CURRENT` | $I=\lvert P\rvert/V$ (per side) | each branch end | $I_{\mathrm{B}}(V_{\text{nom}})$ |
| `ACTIVE_POWER` | $\lvert P\rvert$ (per side) | each branch end | $\SB$ |
| `APPARENT_POWER` | $S=\sqrt{P^2+Q^2}$ (per side) | each branch end | $\SB$ |
| `HIGH/LOW_VOLTAGE` | $V$ | each bus | $V_{\text{nom}}$ |
| `HIGH/LOW_VOLTAGE_ANGLE` | $\Delta\theta=\theta_{\text{from}}-\theta_{\text{to}}$ | monitored pairs | degrees |
:::

Branch limits are checked on *both* ends (`TwoSides.ONE` and `TwoSides.TWO`), because the current and apparent power differ between the two terminals of an impedant branch. In a DC analysis only active power is meaningful, so the current/voltage rows degenerate (voltages are flat, $Q\equiv0$); the active-power and angle checks carry the analysis.

## Permanent and temporary limits

An operational limit is not a single number but a *ladder*: a permanent limit (indefinitely admissible) plus a sequence of temporary limits, each admissible only for a bounded duration --- the higher the overload, the shorter the time the equipment may carry it. The limits are stored sorted by severity, and the manager walks them reporting the *first* (most restrictive) one the value exceeds ([`sa/LimitViolationManager.java`](https://github.com/powsybl/powsybl-open-loadflow/blob/main/src/main/java/com/powsybl/openloadflow/sa/LimitViolationManager.java)): 

$$
\text{report the limit of smallest acceptable duration } \tau
  \text{ such that } \text{value} > \text{limit}(\tau),
$$

 where $\tau=0$ denotes the permanent limit and $\tau>0$ (e.g. $900$ s, $60$ s) a temporary one. Reporting the first crossing means the violation carries the *acceptable duration* the operator has to act --- the tightest binding rung of the ladder.

## Limit reductions

Operators frequently apply a safety coefficient, checking against a *reduced* limit. `LimitReductionManager` builds these reductions from criteria on nominal-voltage interval and on limit kind (permanent, or temporary within a duration range) ([`sa/LimitReductionManager.java`](https://github.com/powsybl/powsybl-open-loadflow/blob/main/src/main/java/com/powsybl/openloadflow/sa/LimitReductionManager.java)). A reduction $r\in[0,1]$ turns the comparison into 

$$
\text{value} \;>\; (1-r)\cdot\text{limit}
  \qquad\Longleftrightarrow\qquad
  \text{violation},
$$

 i.e. the manager tests against the `getReducedValue()` of each rung rather than its nominal value. With $r=0$ the original limit is recovered.

## Pre- versus post-contingency comparison

The defining feature of a *contingency* limit check is that not every violation is news. A line already loaded to $99\%$ in the base case will be flagged in every post-contingency state too, drowning the operator in noise. So the post-contingency manager is constructed with the pre-contingency manager as a *reference* ([`sa/LimitViolationManager.java`](https://github.com/powsybl/powsybl-open-loadflow/blob/main/src/main/java/com/powsybl/openloadflow/sa/LimitViolationManager.java)): a post-contingency violation is kept only if it is *new*, or *worse* than the same violation already present in the base case.

:::{admonition} Definition - Worsened violation
:class: note
For a violation keyed by (equipment, side, type), let $(\text{limit}_N,\,v_N,\,\tau_N)$ and $(\text{limit}_c,\,v_c,\,\tau_c)$ be the base- and post-contingency limit, value and acceptable duration. The post-contingency violation is *reported* unless it is *weakened or equivalent* relative to the base case --- broadly, the overload did not grow and the available reaction time did not shrink ($v_c\lesssim v_N$ and $\tau_c\geq\tau_N$). The exact predicate is governed by the `IncreasedViolations` parameters, which set the relative/absolute thresholds on $v_c-v_N$ for current, power and voltage.

:::

The net effect is that the post-contingency result lists precisely the violations the contingency *caused or aggravated*, which is the actionable information. Pre-contingency violations are reported separately in the base-case result, so nothing is hidden --- only de-duplicated.

## The result objects

The two managers feed two parallel result structures ([`sa/PreContingencyNetworkResult.java`](https://github.com/powsybl/powsybl-open-loadflow/blob/main/src/main/java/com/powsybl/openloadflow/sa/PreContingencyNetworkResult.java), [`sa/PostContingencyNetworkResult.java`](https://github.com/powsybl/powsybl-open-loadflow/blob/main/src/main/java/com/powsybl/openloadflow/sa/PostContingencyNetworkResult.java)), both extending `AbstractNetworkResult`: the pre-contingency result holds the base flows and its own violation list; each post-contingency result holds its status (Chapter {ref}`ch:saoverview`), its filtered violation list, and --- if requested via `createResultExtension` --- the per-branch flows for downstream inspection. Together they are exactly the inputs a future operator-strategy layer will consult to decide *whether* a remedial action is needed and, afterwards, to confirm the curative state cleared the violation.

This completes the base security analysis: a contingency is propagated into a disabled network (Chapter {ref}`ch:contingency`), solved --- by full AC/DC re-solve or by Woodbury superposition (Chapter {ref}`ch:saw`) --- and screened against the operating limits with base-case de-duplication (this chapter). Remedial actions and operator strategies build directly on top of these post-contingency states and results (Chapter {ref}`ch:curative`--{ref}`ch:cufast`).
