(ch:saw)=

# Fast DC security analysis: Woodbury superposition
`WoodburyDcSecurityAnalysis` screens an entire contingency list against a *single* factorisation of the base DC matrix $\bm B'$. Chapter {ref}`ch:woodbury` derived the post-contingency *sensitivities* from the rank-$k$ update of $\bm B'$; here we need the post-contingency *state* itself --- the angles and hence the branch flows --- for every contingency, and OLF computes it in the equivalent but more economical *compensation* form. This chapter derives that form, matches it to `WoodburyEngine`, and explains the connectivity fallback.

## An outage as a compensating injection

Recall the DC model ({eq}`eq:dcsystem`): $\bm B'\bm\varphi=\bm P$ with $\bm B'=\Amat\T\bm\Pi\Amat$, where the branch "power" coefficient $\Pi_\ell$ (the code's `power`, $\Pi_\ell=b_\ell\rho_\ell R_2$) plays the role of a susceptance and $\bm a_\ell=\bm 1_i-\bm 1_j$ is the incidence column of branch $\ell=(i,j)$. Opening $\ell$ removes its rank-one term, 

$$
\bm B'_{c}=\bm B'-\Pi_\ell\,\bm a_\ell\bm a_\ell\T
  \qquad\text{(a single outage),}
$$ (eq:sarank1)

 exactly as in {eq}`eq:rank1`. Rather than re-solve $\bm B'_c\bm\varphi_c=\bm P$, we keep the base matrix and *compensate*: an outage that was carrying flow $P_\ell^{(0)}$ is reproduced, on the intact network, by injecting an unknown compensating power $\alpha_\ell$ at bus $i$ and $-\alpha_\ell$ at bus $j$, 

$$
\bm B'\,\bm\varphi_c=\bm P+\alpha_\ell\,\bm a_\ell .
$$ (eq:sacomp)

 The value $\alpha_\ell$ must be chosen so that, in the compensated state, branch $\ell$ carries *no* flow (it is open). This is the Woodbury identity rewritten around the small "capacitance" scalar instead of the large matrix --- and it is what makes the base factorisation reusable.

## The flow-transfer factor

Define the network's response to a unit transfer across $\ell$, 

$$
\bm\psi_\ell \;=\; \bm B'^{-1}\bm a_\ell
  \qquad(\text{one back-substitution on the \emph{base} factors}),
$$ (eq:sapsi)

 which is precisely the column `contingenciesStates` that `ComputedElement` builds: its right-hand side has $+1$ in the $\texttt{BUS\_TARGET\_P}$ row of bus $i$ and $-1$ in that of bus $j$ (the slack row being dropped), then `solveTransposed` applies $\bm B'^{-1}$ ([`dc/fastdc/ComputedElement.java`](https://github.com/powsybl/powsybl-open-loadflow/blob/main/src/main/java/com/powsybl/openloadflow/dc/fastdc/ComputedElement.java)). Superposing {eq}`eq:sacomp` on the base solution $\bm\varphi^{(0)}=\bm B'^{-1}\bm P$ gives 

$$
\bm\varphi_c=\bm\varphi^{(0)}+\alpha_\ell\,\bm\psi_\ell .
$$ (eq:saupdate)

 Imposing zero flow on the open branch, $P_\ell(\bm\varphi_c)=\Pi_\ell\,
\bm a_\ell\T\bm\varphi_c=0$, and using $\bm a_\ell\T\bm\varphi^{(0)}
=P_\ell^{(0)}/\Pi_\ell$, yields a *scalar* equation for $\alpha_\ell$: 

$$
\boxed{\;\Bigl(\tfrac{1}{\Pi_\ell}-\bm a_\ell\T\bm\psi_\ell\Bigr)\,\alpha_\ell
         \;=\;\bm a_\ell\T\bm\varphi^{(0)} .\;}
$$ (eq:saalpha)

 This is exactly `setAlphas` in the single-element branch ([`dc/fastdc/WoodburyEngine.java`](https://github.com/powsybl/powsybl-open-loadflow/blob/main/src/main/java/com/powsybl/openloadflow/dc/fastdc/WoodburyEngine.java)): the matrix value $a=\tfrac1{\Pi_\ell}-(\psi_{\ell,i}-\psi_{\ell,j})$ is `getAlphaMatrixValue` on the diagonal ($1/\texttt{power}$ minus the self-response), the right-hand side $b=\varphi^{(0)}_i-\varphi^{(0)}_j$ is `getAlphaRhsValue`, and $\alpha_\ell=b/a$.

### Connection to the LODF.

Writing the self-PTDF $\mathrm{PTDF}_{\ell\ell}=\Pi_\ell\,\bm a_\ell\T\bm\psi_\ell$, {eq}`eq:saalpha` gives the transferred power 

$$
\alpha_\ell=\frac{P_\ell^{(0)}}{1-\mathrm{PTDF}_{\ell\ell}},
$$ (eq:salodf)

 the classical line-outage distribution factor denominator: the flow that branch $\ell$ was carrying is pushed back into the rest of the network, amplified by the feedback factor $1/(1-\mathrm{PTDF}_{\ell\ell})$. Equation {eq}`eq:saupdate` then spreads it over every branch via $\bm\psi_\ell$.

## Simultaneous outages: the small dense system

For an $N-k$ contingency the same construction holds with $k$ compensating injections. Stack the outaged incidence columns as $\Amat_c=[\bm a_{\ell_1}\;
\cdots\;\bm a_{\ell_k}]$ and their responses as $\bm\Psi=\bm B'^{-1}\Amat_c$ (the $k$ columns of `contingenciesStates`). The zero-flow conditions on all $k$ open branches become a $k\times k$ dense system for $\bm\alpha=(\alpha_{\ell_1},\dots,\alpha_{\ell_k})\T$: 

$$
\boxed{\;\bm M\,\bm\alpha=\bm b,\qquad
    M_{pq}=\frac{\delta_{pq}}{\Pi_{\ell_p}}-\bm a_{\ell_p}\T\bm\psi_{\ell_q},
    \qquad b_p=\bm a_{\ell_p}\T\bm\varphi^{(0)} .\;}
$$ (eq:saM)

 $\bm M$ is the Woodbury *capacitance matrix* of §{ref}`sec:bordered`: tiny ($k\times k$, with $k\ll n$), dense, and assembled entry-by-entry in `setAlphas` --- the diagonal from $1/\texttt{power}$, the off-diagonals from $-(\psi_{q,i_p}-\psi_{q,j_p})$ --- then solved by a dense LU ([`dc/fastdc/WoodburyEngine.java`](https://github.com/powsybl/powsybl-open-loadflow/blob/main/src/main/java/com/powsybl/openloadflow/dc/fastdc/WoodburyEngine.java)). The post-contingency state is the superposition 

$$
\bm\varphi_c=\bm\varphi^{(0)}+\sum_{q=1}^{k}\alpha_{\ell_q}\,\bm\psi_{\ell_q}
             =\bm\varphi^{(0)}+\bm\Psi\,\bm\alpha,
$$ (eq:saksuper)

 applied row-by-row in `toPostContingencyStates`. Post-contingency branch flows then follow from the DC flow law $P_\ell=\Pi_\ell(\varphi_i-\varphi_j+\alpha_\ell^{\text{ps}})$ ({eq}`eq:dcp`) and are handed to the limit checker of Chapter {ref}`ch:limits`.

:::{admonition} Remark - Why this beats re-solving
:class: seealso
Every contingency reuses the *one* factorisation of $\bm B'$. The cost of a case is: $k$ back-substitutions to form $\bm\Psi$ (already amortised across contingencies that share branches), assembling $\bm M$ in $O(k^2)$, and one $O(k^3)$ dense solve. With $k$ a handful, this is negligible beside the $O(n^{1.5})$ re-factorisation that `DcSecurityAnalysis` pays *per contingency*. The two agree to machine precision when connectivity is preserved.

:::

(sec:saconn)=

## When the outage breaks connectivity
The compensation argument assumes $\bm B'_c$ is still invertible on the surviving buses --- i.e. the outage does *not* island the grid. If §{ref}`sec:connectivity` flagged a connectivity break, the matrix {eq}`eq:sarank1` is singular (a disconnected island has no reference) and {eq}`eq:saM` cannot be solved as-is. For those cases the engine does *not* use pure superposition; it instead performs a genuine reduced DC solve on the post-contingency topology, with the lost buses' injections zeroed and the target vector re-built --- `runDcLoadFlowWithModifiedTargetVector` ([`dc/fastdc/WoodburyEngine.java`](https://github.com/powsybl/powsybl-open-loadflow/blob/main/src/main/java/com/powsybl/openloadflow/dc/fastdc/WoodburyEngine.java)). Concretely that routine:

1.  drops the disabled buses' $\texttt{BUS\_TARGET\_P}$ entries from the target;

2.  zeroes the phase-shift ($\texttt{BRANCH\_TARGET\_ALPHA1}$) contribution of disabled branches;

3.  optionally re-distributes slack over the *surviving* buses of each island (§{ref}`sec:dcslack`);

4.  back-substitutes through the base factors to get the island states.

Because the connectivity filter {eq}`eq:conncrit` proved that only a small minority of contingencies need this path, the fast engine keeps its overall advantage: the common case is pure superposition, the rare islanding case is a single extra back-substitution with an edited right-hand side. This is the precise reason the connectivity break and the Woodbury update are designed together --- the former tells the latter exactly when its low-rank assumption is valid.

:::{admonition} Remark - Multiple right-hand-side columns
:class: seealso
`toPostContingencyStates` loops over the *columns* of the pre-contingency state matrix and recomputes $\bm\alpha$ per column. A single column is the plain $N-k$ case of this chapter; multiple columns arise when several base states must be updated at once (e.g. distinct phase-shifter configurations), each carrying its own impedances and therefore its own $\bm M$. The remedial-action machinery, when added, will reuse this same multi-column path with an enlarged $\bm\alpha=[\bm\alpha_{\text{contingency}};\bm\alpha_{\text{action}}]$ and the bordered $\bm M$ already visible in `setAlphas`.

:::
