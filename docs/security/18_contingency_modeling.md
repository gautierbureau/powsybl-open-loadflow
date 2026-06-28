(ch:contingency)=

# Modelling a contingency
Before any solve can happen, an abstract contingency ("trip line `L1`") must be turned into a concrete edit of the linear-algebra objects: which buses disappear, which branches open, how much injection is lost, and whether the network stays connected. This chapter is engine-agnostic --- the same `PropagatedContingency` feeds the AC, DC and Woodbury solvers.

## Propagation: from a fault to a set of disabled elements

A contingency is declared on *network* elements, but the solver works on the *bus/branch* model (Chapter {ref}`ch:network`). In a node-breaker substation, opening a single piece of equipment may, through the switching arrangement, isolate more than the named element. `ContingencyTripping` therefore performs a graph traversal of the node-breaker topology ([`network/impl/ContingencyTripping.java`](https://github.com/powsybl/powsybl-open-loadflow/blob/main/src/main/java/com/powsybl/openloadflow/network/impl/ContingencyTripping.java)): starting from the faulted terminal it walks the connectivity graph, stopping at the first *openable* switch on each path (which is retained as "to be opened") and continuing through already-open or non-retained switches. The result is the true set of equipment the contingency disconnects. This switch *propagation* can be disabled with the `contingencyPropagation` parameter, in which case only the explicitly named elements are tripped.

The outcome is a `PropagatedContingency` ([`network/impl/PropagatedContingency.java`](https://github.com/powsybl/powsybl-open-loadflow/blob/main/src/main/java/com/powsybl/openloadflow/network/impl/PropagatedContingency.java)), which `complete()`s into a normalised description: branches to open (with a *side* flag), buses lost, and the injection/admittance shifts created by lost generators, loads and shunts.

## The disabled network

Applying the propagated contingency produces a `DisabledNetwork` --- the delta against the base case. Each kind of element maps to a specific algebraic edit ([`network/LfContingency.java`](https://github.com/powsybl/powsybl-open-loadflow/blob/main/src/main/java/com/powsybl/openloadflow/network/LfContingency.java)):

::: center
| Lost element | Algebraic effect | Data |
|:---|:---|:---|
| Branch (line, 2WT) | remove equation term; one or both ends | `branchesStatus`: `BOTH_SIDES`/`SIDE_1`/`SIDE_2` |
| Bus / busbar | drop its $P$/$Q$ equations | `buses` |
| Generator | remove its $P$ (and $V$/$Q$) contribution | `lostGenerators` |
| Load | subtract a `PowerShift` $(\Delta P,\Delta Q)$ | `lostLoads` |
| Shunt | apply an `AdmittanceShift` $(\Delta G,\Delta B)$ | `shuntsShift` |
| HVDC | drop the converter injections | `hvdcsWithoutPower` |
| 3-winding transformer | per-leg branch removal | leg branches |
:::

A `DisabledBranchStatus` of `SIDE_1` or `SIDE_2` (rather than `BOTH_SIDES`) records a branch left dangling at one end --- still electrically present from the other side. This distinction matters for the DC target vector and for connectivity bookkeeping (§{ref}`sec:connectivity`).

(sec:salack)=

## Active-power balance after the loss
Losing generators or loads breaks the power balance the base case had so carefully achieved (§{ref}`sec:slack`). The mismatch introduced by the contingency is exactly 

$$
\Delta P_{\text{loss}}
  \;=\;\underbrace{\textstyle\sum P_{g}^{\text{lost}}}_{\text{lost generation}}
  \;-\;\underbrace{\textstyle\sum P_{\text{load}}^{\text{lost}}}_{\text{lost load}},
$$ (eq:saloss)

 the net active power that was being injected by the now-disconnected elements (`getActivePowerLoss()`). If slack distribution (or area interchange control) is active, this mismatch is shared over the surviving participating units exactly as in the base case, by re-running the active-power distribution of the AC load flow on the *remaining* network ([`sa/DefaultContingencyActivePowerLossDistribution.java`](https://github.com/powsybl/powsybl-open-loadflow/blob/main/src/main/java/com/powsybl/openloadflow/sa/DefaultContingencyActivePowerLossDistribution.java)): 

$$
\Delta P_{\text{loss}}
  \;=\;\sum_{u\,\in\,\text{survivors}} \Delta P_u \;+\; r,
$$

 where each $\Delta P_u$ follows the configured balance type (proportional to remaining generation, to load, to conforming load, ...) and any un-distributable remainder $r$ falls on the slack bus. If slack distribution is *off*, the whole of {eq}`eq:saloss` is absorbed by the slack bus, just as in a plain load flow. In DC this redistribution is, once again, a pure right-hand-side edit (§{ref}`sec:dcslack`): no matrix changes.

:::{admonition} Remark - Where remedial actions will attach
:class: seealso
The post-contingency state produced here is the *starting point* for any curative operator strategy. A relative active-power action would add its $\Delta P$ on top of the balanced state of this section; that layer is out of scope for now.

:::

(sec:connectivity)=

## Connectivity: does the contingency island the grid?
An $N-k$ outage may *split* the network into separate energised islands (or shed a radial feeder entirely). Two things then change: the disconnected buses must be removed from the solved system, and each surviving island must balance its own power. Detecting this cheaply --- for every contingency, before solving --- is the job of `ConnectivityBreakAnalysis` ([`dc/fastdc/ConnectivityBreakAnalysis.java`](https://github.com/powsybl/powsybl-open-loadflow/blob/main/src/main/java/com/powsybl/openloadflow/dc/fastdc/ConnectivityBreakAnalysis.java)), which runs in two stages.

(stage-1-a-sensitivity-worst-case-filter .unnumbered)=

### Stage 1 --- a sensitivity "worst-case" filter
Most contingencies do *not* break connectivity, and we want to skip the graph machinery for those. The filter rests on a clean fact about the DC model. Inject a unit transfer $+1/-1$ across each outaged branch and measure, through the *base* sensitivities, how much of that injected flow returns through the *other* outaged branches. If the outaged set carries the *entire* injected transfer --- the absolute sensitivities sum to one --- then there is no alternative path, and removing the set must disconnect something: 

$$
\sum_{\ell'\in c}\;\Bigl|\,\mathrm{PTDF}_{\ell'\!,\,\ell}\,\Bigr|
  \;>\; 1-\varepsilon
  \quad\Longrightarrow\quad
  \text{potential connectivity break},
$$ (eq:conncrit)

 for some outaged branch $\ell\in c$, with $\varepsilon=$`CONNECTIVITY_LOSS_THRESHOLD` $=10^{-6}$. The sensitivities are read straight from the same $\bm\Psi=\bm B'^{-1}\Amat_c$ columns the Woodbury engine already needs (Chapter {ref}`ch:saw`), so the filter is essentially free. Crucially the test is *conservative*: if the sum is below the threshold, connectivity is provably intact and stage 2 is skipped.

(stage-2-graph-confirmation .unnumbered)=

### Stage 2 --- graph confirmation
For the few contingencies that pass the filter, the analysis temporarily removes the outaged edges from the connectivity graph and recomputes the connected components (`GraphConnectivity`, [`dc/fastdc/ConnectivityBreakAnalysis.java`](https://github.com/powsybl/powsybl-open-loadflow/blob/main/src/main/java/com/powsybl/openloadflow/dc/fastdc/ConnectivityBreakAnalysis.java)). It then records:

- *disabled buses* --- vertices severed from the main component;

- *partially disabled branches* --- branches with one end now in a dead island;

- *HVDC without power* --- links with one converter lost;

- the number of new synchronous islands created.

A small greedy pass (`computeElementsToReconnect`) finds the minimal set of outaged branches that, if *not* cut, would keep everything connected --- the information the fast DC engine uses to decide which cases it can update by superposition and which it must re-solve island-by-island (§{ref}`sec:saconn`).

:::{admonition} Example - Radial feeder
:class: tip
Tripping the single line that feeds a radial load pocket makes {eq}`eq:conncrit` an equality: *all* of the test injection must flow back through that same line, so its self-sensitivity is $1$. The filter flags it, stage 2 confirms the pocket is islanded, and its buses are marked disabled. A meshed line, by contrast, returns only a fraction and is never flagged.

:::
