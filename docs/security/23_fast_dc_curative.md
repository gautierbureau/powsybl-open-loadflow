(ch:cufast)=

# Fast DC curative: actions in the bordered system
The fast DC engine of Chapter {ref}`ch:saw` screened contingencies without re-factorising $\bm B'$. Remedial actions fit the *same* mould: a switch operation or a phase-tap step is a low-rank edit of $\bm B'$, indistinguishable in form from a branch outage. So instead of a separate curative solve, the engine *enlarges* the small capacitance system to carry the actions alongside the contingency, and reads the curative state off the one base factorisation. This is exactly what `WoodburyEngine`'s second constructor and `toPostContingencyAndOperatorStrategyStates` implement.

## An action is another compensating injection

Recall the compensation form (§{ref}`ch:saw`, {eq}`eq:sacomp`): an outaged branch is reproduced on the intact network by an unknown injection $\alpha$ along it. A remedial branch action is the same idea with a different target:

Opening a switch

:   on branch $\ell$ is *identical* to a contingency on $\ell$ --- drive its flow to zero. Its compensation column is the incidence $\bm a_\ell$ and its self term is $1/\Pi_\ell$, the very same entries a contingency contributes ([`dc/fastdc/WoodburyEngine.java`](https://github.com/powsybl/powsybl-open-loadflow/blob/main/src/main/java/com/powsybl/openloadflow/dc/fastdc/WoodburyEngine.java), `computeDeltaXForDiagonalValues` with $\texttt{newPower}=0$, $\texttt{oldPower}=\Pi_\ell$, giving $1/(\Pi_\ell-0)=1/\Pi_\ell$).

Closing a switch

:   on an idle branch is the constructive inverse: the branch must now *carry* flow consistent with the angles across it. The self term flips sign, $1/(0-\Pi_\ell)=-1/\Pi_\ell$ ($\texttt{oldPower}=0$, $\texttt{newPower}=\Pi_\ell$).

Stepping a phase tap

:   on branch $\ell$ changes its constant phase shift $\alpha=A_1$ and possibly its susceptance $\Pi_\ell$. A pure phase shift is equivalent to injecting $\Pi_\ell\,\Delta A_1$ at one end and withdrawing it at the other; the engine carries this through the right-hand side, with the self term $1/(\Pi_\ell^{\text{old}}-\Pi_\ell^{\text{new}})$ absorbing any impedance change.

Each such action becomes a `ComputedElement` with exactly the same incidence right-hand side as a contingency ([`dc/fastdc/ComputedSwitchBranchElement.java`](https://github.com/powsybl/powsybl-open-loadflow/blob/main/src/main/java/com/powsybl/openloadflow/dc/fastdc/ComputedSwitchBranchElement.java), [`dc/fastdc/ComputedTapPositionChangeElement.java`](https://github.com/powsybl/powsybl-open-loadflow/blob/main/src/main/java/com/powsybl/openloadflow/dc/fastdc/ComputedTapPositionChangeElement.java)); its response column $\bm\psi_a=\bm B'^{-1}\bm a_a$ (`actionsStates`) is one more back-substitution on the base factors.

## The bordered capacitance system

Stack the $k$ contingency elements and the $m$ action elements. The flow-transfer factors $\bm\alpha=[\bm\alpha_c;\bm\alpha_a]$ solve a single $(k+m)\times(k+m)$ bordered system --- the contingency capacitance matrix of {eq}`eq:saM` extended with an action block ([`dc/fastdc/WoodburyEngine.java`](https://github.com/powsybl/powsybl-open-loadflow/blob/main/src/main/java/com/powsybl/openloadflow/dc/fastdc/WoodburyEngine.java), `setAlphas`): 

$$
\boxed{\;
  \begin{bmatrix}\bm M_{cc} & \bm M_{ca}\\[2pt] \bm M_{ac} & \bm M_{aa}\end{bmatrix}
  \begin{bmatrix}\bm\alpha_c\\[2pt]\bm\alpha_a\end{bmatrix}
  =\begin{bmatrix}\bm b_c\\[2pt]\bm b_a\end{bmatrix},\;}
$$ (eq:cuborder)

 with the blocks assembled entry-by-entry from the base sensitivities, 

$$
M_{pq}=\frac{\delta_{pq}}{\Pi_p^{\,\star}}-\bm a_p\T\bm\psi_q,
  \qquad
  (b_c)_p=\bm a_p\T\bm\varphi^{(0)},
  \qquad
  (b_a)_p=\bm a_p\T\bm\varphi^{(0)}+\Delta A_{1,p},
$$ (eq:borderblocks)

 where $1/\Pi_p^{\,\star}$ is the appropriate self term ($1/\Pi_p$ for a contingency or switch-open, $-1/\Pi_p$ for switch-close, $1/(\Pi_p^{\text{old}}-\Pi_p^{\text{new}})$ for a tap), $\bm\psi_q$ is the $q$-th response column (contingency or action), and $\Delta A_{1,p}=A_1^{\text{new}}$ is the phase-tap term in the right-hand side (`getAlphaRhsValue`). The off-diagonal couplings $\bm M_{ca}$, $\bm M_{ac}$ are the mutual responses $-\bm a_p\T\bm\psi_q$ between a contingency and an action --- they encode that a remedial switching changes how the outage redistributes, and vice versa. The block is tiny and dense, solved by one LU ([`dc/fastdc/WoodburyEngine.java`](https://github.com/powsybl/powsybl-open-loadflow/blob/main/src/main/java/com/powsybl/openloadflow/dc/fastdc/WoodburyEngine.java)).

The curative state is then the full superposition of base case, contingency compensations and action compensations, 

$$
\bm\varphi_C=\bm\varphi^{(0)}
    +\sum_{p=1}^{k}\alpha_{c,p}\,\bm\psi_{c,p}
    +\sum_{q=1}^{m}\alpha_{a,q}\,\bm\psi_{a,q},
$$ (eq:curstate)

 applied row-by-row in `toPostContingencyAndOperatorStrategyStates`: the contingency sum reproduces {eq}`eq:saksuper`, the action sum is the new term. Curative branch flows then follow from the DC flow law as before, and feed the limit checker of Chapter {ref}`ch:limits`.

:::{admonition} Remark - Refreshing the base before superposing
:class: seealso
When the strategy contains actions (or the contingency islanded buses or dropped a phase controller), the engine first re-evaluates the base flow states on the edited target vector --- `runDcLoadFlowWithModifiedTargetVector` with the action list ([`dc/fastdc/WoodburyEngine.java`](https://github.com/powsybl/powsybl-open-loadflow/blob/main/src/main/java/com/powsybl/openloadflow/dc/fastdc/WoodburyEngine.java)) --- which writes the new phase-shift targets $A_1^{\text{new}}$ and zeroes disabled branches, and only then applies the bordered $\bm\alpha$-update {eq}`eq:curstate`. The factorisation is still never rebuilt: this refresh is one more back-substitution with an edited right-hand side.

:::

## What the fast path accepts

Fast DC admits an action either when it is a branch-incidence low-rank edit (so the bordered construction applies) or when it only shifts injections (so the right-hand side $\bm P$ changes while $\bm B'$ and its factorisation stay put). That gives **five** accepted action types --- `SwitchAction`, `TerminalsConnectionAction` (opening), and `PhaseTapChangerTapPositionAction` (the incidence edits), plus `GeneratorAction` and `LoadAction` (injection shifts, solved by re-using the cached factorisation with a modified target vector) --- and *rejects* the rest up front (`Actions.checkWoodburySupported`, [`network/action/Actions.java`](https://github.com/powsybl/powsybl-open-loadflow/blob/main/src/main/java/com/powsybl/openloadflow/network/action/Actions.java)): shunt, HVDC, area and ratio-tap actions change admittances that are neither a single incidence column nor a plain injection shift, and closing a transformer is likewise disallowed. Those actions are not silently approximated --- enabling `dcFastMode` with them raises an error, and the analyst must use the full `DcSecurityAnalysis` (one $\bm B'$ re-solve per curative state) or `AcSecurityAnalysis` (full Newton), both of which support the entire catalogue of Chapter {ref}`ch:actions`.

## Connectivity and multiple tap configurations

Two refinements complete the picture.

### Connectivity under actions.

Because switching actions can both sever and *restore* connectivity, the connectivity test of §{ref}`sec:connectivity` is run in a *nested* temporary context --- contingency edges removed, then action edges applied --- so islands created by the outage but reconnected by a remedial close are correctly counted (`LfActionUtils`). Branches that straddle a genuine connectivity break are placed in `elementsToReconnect` and excluded from the bordered system, which keeps $\bm M$ non-singular; the islanded part is handled by the modified-target re-solve of Chapter {ref}`ch:saw`, §{ref}`sec:saconn`.

### Multiple phase-shifter configurations.

When several phase-tap configurations must be evaluated, the pre-contingency state is held as a *matrix* with one column per configuration, and `toPostContingencyAndOperatorStrategyStates` loops over columns, re-solving $\bm\alpha$ per column because each tap set carries its own impedances and hence its own $\bm M$ and $A_1^{\text{new}}$. A single column is the ordinary one-strategy case of this chapter.

:::{admonition} Remark - Cost
:class: seealso
A curative state costs: $m$ extra back-substitutions for the action columns (amortised across strategies that share a switch or PST), assembling the enlarged $\bm M$ in $O((k+m)^2)$, and one $O((k+m)^3)$ dense solve with $k+m\ll n$ --- versus the $O(n^{1.5})$ re-factorisation a full curative DC solve pays. The remedial layer thus inherits the whole speed advantage of the fast contingency screen, which is what makes exhaustive "contingency $\times$ strategy" studies tractable.

:::

This closes the security analysis. A contingency is propagated and balanced (Chapter {ref}`ch:contingency`), solved by re-solve or Woodbury superposition (Chapter {ref}`ch:saw`); if a condition fires, remedial actions are layered on (Chapter {ref}`ch:curative`--{ref}`ch:actions`) and the curative state is obtained, in fast DC, from the same factorisation via the bordered system above; and every state is screened against the operating limits with timeline-aware de-duplication (Chapter {ref}`ch:limits`). The unbalanced (three-sequence) extension is covered in {ref}`part:asym`.
