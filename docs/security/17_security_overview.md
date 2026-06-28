(part:security)=

(ch:saoverview)=

# The contingency-analysis problem
A power system is operated to the *$N-1$ security criterion*: it must survive the loss of any single element (a line, transformer, generator, busbar, ...) without violating an operating limit. A *security analysis* checks this by replaying a list of *contingencies* against a base case and reporting, for each one, which limits are breached. With $N-k$ contingencies (simultaneous losses) and large lists --- tens of thousands of cases --- the only thing that makes this tractable is that every post-contingency state is a *small perturbation* of the same base case, so the expensive factorisations of Parts I and II can be reused. That reuse is the mathematical heart of this part.

This part covers the *base* security analysis only: the $N$ (pre-contingency) and $N-k$ (post-contingency) simulations and their limit checks. *Remedial actions* (operator strategies / curative simulations) are deliberately out of scope here; the orchestration below shows exactly where they will later plug in.

## What a security analysis computes

The inputs are a network, a *contingency list* $\{c_1,\dots,c_m\}$, and a set of operational limits. The output is a *pre-contingency result* (the base-case flows, voltages and any limits already violated in state $N$) plus, for each contingency $c$, a *post-contingency result*: a convergence status and the list of limit violations, filtered to those that are *new or worsened* relative to the base case (Chapter {ref}`ch:limits`).

:::{admonition} Definition - Security-analysis loop
:class: note
:name: def:saloop
The generic orchestration, shared by all engines ([`sa/AbstractSecurityAnalysis.java`](https://github.com/powsybl/powsybl-open-loadflow/blob/main/src/main/java/com/powsybl/openloadflow/sa/AbstractSecurityAnalysis.java)), is:

1.  Solve the base case $N$ (a full load flow, AC or DC). Detect pre-contingency violations. *Save* the network state.

2.  For each contingency $c$:

    1.  build the post-contingency network (Chapter {ref}`ch:contingency`);

    2.  solve the post-contingency state $N-k$;

    3.  detect violations and compare them to the base case;

    4.  *restore* the saved base state.

:::

The save/restore step (1 and 2d) is what makes the loop correct: each contingency is applied to the *pristine* base case, never to the residue of the previous one. The contingencies are independent, so the loop parallelises trivially over a thread pool (`threadCount`, [`util/mt/ContingencyMultiThreadHelper.java`](https://github.com/powsybl/powsybl-open-loadflow/blob/main/src/main/java/com/powsybl/openloadflow/util/mt/ContingencyMultiThreadHelper.java)).

## Three engines, three notions of "re-solve"

The three providers differ only in *how* step 2b is performed --- and that single choice spans three orders of magnitude in cost per contingency.

`AcSecurityAnalysis`

:   Each post-contingency state is a *full AC Newton solve*, including all the outer loops. It is warm-started from the converged base case via `PreviousValueVoltageInitializer` ([`sa/AcSecurityAnalysis.java`](https://github.com/powsybl/powsybl-open-loadflow/blob/main/src/main/java/com/powsybl/openloadflow/sa/AcSecurityAnalysis.java)), so typically only one or two Newton iterations are needed --- but each iteration still re-factorises the AC Jacobian. This is the most accurate and the most expensive engine.

`DcSecurityAnalysis`

:   Each post-contingency state is a *full DC solve*: one linear system $\bm B'\bm\varphi=\bm P$ per contingency, re-built on the contingency's topology ([`sa/DcSecurityAnalysis.java`](https://github.com/powsybl/powsybl-open-loadflow/blob/main/src/main/java/com/powsybl/openloadflow/sa/DcSecurityAnalysis.java)). No iteration, but the modified $\bm B'$ is re-factorised for every case.

`WoodburyDcSecurityAnalysis`

:   (enabled by `dcFastMode`). The base $\bm B'$ is factorised *once*; every post-contingency state is obtained from that single factorisation by a Sherman--Morrison--Woodbury superposition --- a handful of back-substitutions and a tiny dense solve, *no* re-factorisation ([`sa/WoodburyDcSecurityAnalysis.java`](https://github.com/powsybl/powsybl-open-loadflow/blob/main/src/main/java/com/powsybl/openloadflow/sa/WoodburyDcSecurityAnalysis.java)). This is the subject of Chapter {ref}`ch:saw`.

:::{admonition} Remark - Cost hierarchy
:class: seealso
Writing $n$ for the number of buses and $k$ for the contingency size, the per-contingency cost is roughly: AC $\sim$ (a few) Jacobian factorisations; DC $\sim$ one $\bm B'$ factorisation $O(n^{1.5})$ (sparse); Woodbury $\sim$ $k$ back-substitutions $O(kn)$ plus a $k\times k$ dense solve $O(k^3)$ with $k\ll n$. Only the last is genuinely cheap enough for exhaustive $N-1$ screening of a continental grid.

:::

## Post-contingency status

A post-contingency simulation does not merely produce numbers; it produces a *status* that tells the operator whether those numbers are trustworthy. In AC, the base-case load-flow result is mapped to a `PostContingencyComputationStatus` ([`sa/AcSecurityAnalysis.java`](https://github.com/powsybl/powsybl-open-loadflow/blob/main/src/main/java/com/powsybl/openloadflow/sa/AcSecurityAnalysis.java)):

::: center
| Load-flow outcome | Post-contingency status |
|:---|:---|
| solver converged, outer loops stable | `CONVERGED` |
| Newton hit the iteration cap | `MAX_ITERATION_REACHED` |
| an outer loop failed | `FAILED` |
| the linear solver failed | `SOLVER_FAILED` |
| state physically unrealistic (e.g. $V$ out of range) | `FAILED` |
| contingency removed nothing solvable | `NO_IMPACT` |
:::

DC and Woodbury, being linear and non-iterative, report a simple converged/failed outcome (the Woodbury engine treats every solvable case as `CONVERGED`, since the base factorisation already succeeded).

(sec:samulti)=

## Multi-component networks
Everything above assumes a single connected component. A real grid model can hold *several* separate synchronous components in the base case (e.g. two asynchronous areas, or islands left disconnected on purpose). The security analysis handles this with a deliberately simple orchestration [`sa/AbstractSecurityAnalysis.java`](https://github.com/powsybl/powsybl-open-loadflow/blob/main/src/main/java/com/powsybl/openloadflow/sa/AbstractSecurityAnalysis.java): it runs an *independent* security analysis on each separated component and merges the per-component results once all are done. This is distinct from islanding *caused by* a contingency (§{ref}`sec:connectivity`, §{ref}`sec:saconn`), where a single base network is split by the outage being studied; here the components are already separate before any contingency.

This minimal implementation carries three limitations until the core security-analysis API can represent per-component state, and they are worth stating explicitly because they affect how results should be read:

- the convergence status of *secondary* components is not surfaced in the pre-contingency status (only the main component's);

- for a contingency that affects *several* components at once, only the first analysed component's status is reported;

- an action that would *connect components not joined in the base case* (a `TerminalsConnectionAction` bridging two islands) is not supported --- topology actions are resolved within a single component (Chapter {ref}`ch:actions`).

The next three chapters take these in dependency order: how a contingency is turned into a perturbed network (Chapter {ref}`ch:contingency`); how the fast DC engine exploits that perturbation's low rank (Chapter {ref}`ch:saw`); and how violations are detected and filtered (Chapter {ref}`ch:limits`).
