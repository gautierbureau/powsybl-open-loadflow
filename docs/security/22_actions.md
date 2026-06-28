(ch:actions)=

# The action catalogue
A remedial action is, mathematically, a small edit to the load-flow model --- the same kinds of edit a contingency makes (Chapter {ref}`ch:contingency`), but applied *additively on top of* the post-contingency state and generally *constructive* rather than destructive. This chapter catalogues the nine supported action types by the object they perturb, then shows how a list of them is applied as a single combined edit.

## A taxonomy by what is perturbed

Every action edits one of three things: an *injection* (the right-hand side $\bm P$/$\bm Q$), the *topology* (the incidence $\Amat$, hence which buses and branches exist), or a *branch/bus parameter* (an entry of $\bm B'$ or the AC admittance, or a control target). The grouping mirrors how each action enters the solver and, in Chapter {ref}`ch:cufast`, which ones the fast DC path can absorb.

### Injection actions (edit the right-hand side)

#### Load action

(`LfLoadAction`, [`network/action/LfLoadAction.java`](https://github.com/powsybl/powsybl-open-loadflow/blob/main/src/main/java/com/powsybl/openloadflow/network/action/LfLoadAction.java)). Shifts a load's active and reactive consumption by a `PowerShift`. The action may be *relative* or *absolute*; both are reduced to a relative shift on the post-contingency target, 

$$
\Delta P=\begin{cases}\;a & \text{(relative)}\\[2pt]
                        \;a-P_0 & \text{(absolute: reach value }a)\end{cases}
  \qquad
  P^{\text{load}}\leftarrow P^{\text{load}}+\Delta P,
$$

 and likewise for $\Delta Q$ (the variable part is zeroed for a non-participating load). The bus $P$/$Q$ targets move; the matrices do not.

#### Generator action

(`LfGeneratorAction`, [`network/action/LfGeneratorAction.java`](https://github.com/powsybl/powsybl-open-loadflow/blob/main/src/main/java/com/powsybl/openloadflow/network/action/LfGeneratorAction.java)). Changes a generator's active setpoint, 

$$
P_g\leftarrow\begin{cases}\;P_g+\Delta & \text{(relative)}\\[2pt]
                            \;\Delta & \text{(absolute)},\end{cases}
$$

 and re-snapshots it as the new *initial* target ($\texttt{setInitialTargetP}$) so that any subsequent slack distribution measures participation from this new baseline, before re-applying the unit's active-power-control checks.

#### HVDC action

(`LfHvdcAction`, [`network/action/LfHvdcAction.java`](https://github.com/powsybl/powsybl-open-loadflow/blob/main/src/main/java/com/powsybl/openloadflow/network/action/LfHvdcAction.java)). *Disables* AC emulation: the link stops being an implicit angle-droop variable (§{ref}`sec:outer`) and freezes into two fixed converter injections set to the link's *current* flow, 

$$
P^{\text{conv}}_1\leftarrow-P_1,\qquad P^{\text{conv}}_2\leftarrow-P_2,
$$

 after which the emulation equations are dropped. (Only disabling is supported; re-enabling is not.)

#### Area-interchange target action

(`LfAreaInterchangeTargetAction`, [`network/action/LfAreaInterchangeTargetAction.java`](https://github.com/powsybl/powsybl-open-loadflow/blob/main/src/main/java/com/powsybl/openloadflow/network/action/LfAreaInterchangeTargetAction.java)). Re-sets an area's scheduled net interchange, changing the right-hand side of the area-balance constraint solved by the area-interchange outer loop (§{ref}`sec:outer`).

### Topology actions (edit the incidence)

#### Switch / terminals-connection action

(`LfSwitchAction`, `LfTerminalsConnectionAction`; [`network/action/AbstractLfBranchAction.java`](https://github.com/powsybl/powsybl-open-loadflow/blob/main/src/main/java/com/powsybl/openloadflow/network/action/AbstractLfBranchAction.java)). Opens or closes a switch or a branch/3-winding transformer. Opening is the destructive edit of a contingency (remove the branch's equation, drop its incidence column); *closing* is its constructive inverse (re-insert the column). Either way the connectivity graph changes, so the set of energised buses and branches is recomputed (`updateBusesAndBranchStatus`): closing a switch can *re-connect* an island that a contingency had shed, and opening one can create a new island. This is the only action family that alters connectivity, and the reason actions and `ConnectivityBreakAnalysis` (§{ref}`sec:connectivity`) are coupled.

### Parameter / control actions (edit a matrix entry or target)

#### Phase-tap-changer action

(`LfPhaseTapChangerAction`). Steps the tap to a new position, swapping in the corresponding pre-computed $\pi$-model (`TapPositionChange.getNewPiModel`) and hence a new phase shift $\alpha=A_1$. In DC this changes the branch's constant phase-shift term $P_\ell=\Pi_\ell(\varphi_i-\varphi_j+\alpha)$ ({eq}`eq:dcp`); the new position is $t_0+\delta$ (relative) or $\delta$ (absolute).

#### Ratio-tap-changer action

(`LfRatioTapChangerAction`). Same mechanism but for the voltage ratio $\rho$: a new $\pi$-model with a different $\rho$, which rescales the branch admittance terms in the AC system.

#### Shunt-position action

(`LfShuntCompensatorPositionAction`, [`network/action/LfShuntCompensatorPositionAction.java`](https://github.com/powsybl/powsybl-open-loadflow/blob/main/src/main/java/com/powsybl/openloadflow/network/action/LfShuntCompensatorPositionAction.java)). Moves a switched shunt to a new section count, changing the bus shunt susceptance by $\Delta B=(\text{new}-\text{old})\,b_{\text{section}}$ --- a diagonal edit of the AC bus admittance.

::: center
| Action | Perturbs | Edit | Relative baseline |
|:---|:---|:---|:---|
| Load | injection $\bm P,\bm Q$ | target shift $\Delta P,\Delta Q$ | post-contingency target |
| Generator | injection $\bm P$ | setpoint $P_g$, re-snapshot initial | post-contingency target |
| HVDC | injection $\bm P$ | emulation $\to$ fixed converter $P$ | current flow |
| Area interchange | injection (constraint) | area target | --- |
| Switch / terminals | incidence $\Amat$ | open/close branch, recompute islands | --- |
| Phase tap | $\bm B'$ / admittance | new $\pi$-model, new $\alpha=A_1$ | post-contingency tap |
| Ratio tap | admittance | new $\pi$-model, new $\rho$ | post-contingency tap |
| Shunt position | bus admittance | $\Delta B$ on the diagonal | --- |
:::

(sec:applytogether)=

## Applying a list of actions together
The actions of all fired blocks are applied as one combined edit before a single curative solve --- not one-action-one-solve. `LfActionUtils.applyListOfActions` ([`network/action/LfActionUtils.java`](https://github.com/powsybl/powsybl-open-loadflow/blob/main/src/main/java/com/powsybl/openloadflow/network/action/LfActionUtils.java)) does this in two passes, and the ordering matters:

1.  **Topology first.** All connectivity-changing branch actions are applied inside a *nested* temporary connectivity context: the post-contingency disabled edges are removed, then the action edges are opened/closed, and the energised-component bookkeeping is recomputed in one shot (`updateBusesAndBranchStatus`). Doing this first fixes *which* buses and branches exist before any injection or parameter edit is written onto them.

2.  **Everything else second.** The injection and parameter actions (load, generator, shunt, HVDC, area, taps) are then applied sequentially on the settled topology.

Because the edits commute on disjoint objects and the topology is resolved up front, the combined application is well-defined regardless of the order the operator listed the actions, and the curative state is obtained from *one* re-solve of the fully edited model.

:::{admonition} Remark - The relative chain, end to end
:class: seealso
A relative active-power action threads through all three states of {eq}`eq:threestates`. A participating generator's target evolves as 

$$
P_g^{(N)}=P_g^{\text{set}}+\Delta P_g^{\text{slack}(N)}
  \;\to\;
  P_g^{(N\!-\!k)}=P_g^{(N)}+\Delta P_g^{\text{slack}(c)}
  \;\to\;
  P_g^{(C)}=P_g^{(N\!-\!k)}+\Delta_{\text{action}}+\Delta P_g^{\text{slack}(C)},
$$

 i.e. the action's $\Delta_{\text{action}}$ is layered on the post-contingency value and the curative state then gets its *own* slack distribution (§{ref}`sec:slack`). This is the precise meaning of "relative to the post-contingency state".

:::
