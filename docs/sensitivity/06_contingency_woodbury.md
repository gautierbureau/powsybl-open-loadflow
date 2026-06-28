(ch:woodbury)=

# Post-contingency DC sensitivities: Woodbury
A contingency screening evaluates thousands of single- (and multi-) branch outages. Re-factorising $\bm B'$ for each would be ruinous. Because a branch outage is a *low-rank* change to $\bm B'$, the Sherman--Morrison--Woodbury identity gives the post-contingency state from the *base-case* factorisation plus a tiny dense solve. This chapter derives the formula and maps it to OLF's `WoodburyEngine`.

## An outage is a rank-one change

Removing branch $\ell=(i,j)$ sets its susceptance $\beta_\ell\to0$. With the incidence column $\bm a_\ell$ (the vector with $+1$ at bus $i$, $-1$ at bus $j$), the Laplacian {eq}`eq:BAA` changes by exactly one rank-one term: 

$$
\bm B'_{\text{post}}=\bm B'-\beta_\ell\,\bm a_\ell\bm a_\ell^{\top}.
$$ (eq:rank1)

 A simultaneous outage of $K$ branches is a rank-$K$ change $\bm B'_{\text{post}}=\bm B'-\Amat_C^{\top}\bm\beta_C\Amat_C$, where $\Amat_C$ stacks the $K$ outaged incidence rows.

### Which entries actually move.

The rank-one term $\beta_\ell\bm a_\ell\bm a_\ell^{\top}$ with $\bm a_\ell=\bm 1_i-\bm 1_j$ touches only the four entries at the intersection of rows/columns $i$ and $j$ ({numref}`fig:rank1`): the two diagonals lose $\beta_\ell$, the two off-diagonals gain $\beta_\ell$. Everything else is untouched --- the change is *local and tiny*, which is exactly what makes a low-rank update worthwhile instead of a full refactorisation.

```{figure} ../_figures/rank_one.svg
:name: fig:rank1
:width: 80%

A branch outage is a *rank-one* edit of $\bm B'$: only the $2\times2$ block on the two end-buses changes.
```

## The Sherman--Morrison--Woodbury identity

For an invertible $\bm B'$ and the rank-one update {eq}`eq:rank1`, 

$$
\boxed{\;
  \bm B'^{-1}_{\text{post}}
   =\bm B'^{-1}
   +\bm B'^{-1}\bm a_\ell\,
     \underbrace{\Bigl(\tfrac{1}{\beta_\ell}-\bm a_\ell^{\top}\bm B'^{-1}\bm a_\ell\Bigr)^{-1}}_{\textstyle \text{scalar }1/M_\ell}\,
     \bm a_\ell^{\top}\bm B'^{-1}.\;}
$$ (eq:smw)

 *Derivation.* Verify $\bm B'_{\text{post}}\bm B'^{-1}_{\text{post}}=\bm I$ by multiplying {eq}`eq:rank1` into {eq}`eq:smw` and collecting the $\bm a_\ell\bm a_\ell^\top$ terms; the scalar $M_\ell=1/\beta_\ell-\bm
a_\ell^{\top}\bm B'^{-1}\bm a_\ell$ is precisely what makes the cross terms cancel. For the rank-$K$ case the scalar becomes the $K\times K$ **capacitance matrix** 

$$
\bm M=\operatorname{diag}\!\bigl(1/\beta_\ell\bigr)-\Amat_C\bm B'^{-1}\Amat_C^{\top},
$$ (eq:capac)

 and $1/M_\ell$ is replaced by $\bm M^{-1}$.

## From inverse to state: the OLF form

We never want $\bm B'^{-1}_{\text{post}}$; we want the post-contingency state $\bm\varphi_{\text{post}}=\bm B'^{-1}_{\text{post}}\bm P$. Multiplying {eq}`eq:smw` by $\bm P$ and recognising $\bm\varphi_{\text{pre}}=\bm
B'^{-1}\bm P$ gives the update used in code [`dc/fastdc/WoodburyEngine.java`](https://github.com/powsybl/powsybl-open-loadflow/blob/main/src/main/java/com/powsybl/openloadflow/dc/fastdc/WoodburyEngine.java): 

$$
\boxed{\;
  \bm\varphi_{\text{post}}=\bm\varphi_{\text{pre}}+\bigl(\bm B'^{-1}\Amat_C^{\top}\bigr)\,\bm\alpha,
  \qquad
  \bm M\,\bm\alpha=\Amat_C\,\bm\varphi_{\text{pre}} .\;}
$$ (eq:woodupdate)

 The vector $\bm\alpha$ is the *flow transfer* on the outaged branches; it is found by the small dense solve $\bm M\bm\alpha=\Amat_C\bm\varphi_{\text{pre}}$ ($K\times K$, $K$ tiny), then a rank-$K$ correction restores the full state.

### Mapping to `WoodburyEngine`

- **Contingency states** $\bm B'^{-1}\Amat_C^{\top}$: each outaged branch gets a $+1/-1$ right-hand side on its two bus equations (`ComputedElement.fillRhs`); `calculateElementsStates` solves the *transposed* factorised base Jacobian against them --- this is $\bm B'^{-1}\bm a_\ell$, one column per outaged branch, reusing the base factorisation.

- **Capacitance matrix** $\bm M$ {eq}`eq:capac`: `getAlphaMatrixValue` builds the diagonal $1/\text{power}_\ell=1/\beta_\ell$ and the off-diagonals $-\bm a_\ell^\top(\bm B'^{-1}\bm a_m)=
          -(s_{m,i}-s_{m,j})$ read from the contingency-state columns.

- **The $\alpha$ solve** $\bm M\bm\alpha=\Amat_C\bm\varphi_{\text{pre}}$: `setAlphas` forms the right-hand side $b_\ell=\varphi_{\text{pre},i}-\varphi_{\text{pre},j}$ and solves (a scalar division for $K=1$, an LU for $K>1$).

- **The update** {eq}`eq:woodupdate`: `toPostContingencyStates` adds $\sum_\ell\alpha_\ell\,(\bm B'^{-1}\bm a_\ell)$ to the base state.

### Post-contingency sensitivities reuse the same engine

Crucially, the *factor states* (§{ref}`ch:dcsensi`) are pushed through the identical update: a sensitivity column $\bm s_k$ becomes $\bm s_k^{\text{post}}=\bm s_k+(\bm B'^{-1}\Amat_C^{\top})\bm\alpha_k$ with its own $\bm\alpha_k$ from $\bm M\bm\alpha_k=\Amat_C\bm s_k$. So post-contingency PTDFs cost, per contingency, only the $K\times K$ solve plus rank-$K$ corrections --- *no* re-factorisation of $\bm B'$. This is the basis of OLF's fast DC contingency analysis.

### Operator-strategy actions reuse it too

A remedial **action** is, mathematically, just another low-rank edit of the base system, so DC sensitivity folds it into the *same* update {eq}`eq:woodupdate`. A branch open/close (`ComputedSwitchBranchElement`) or a phase-shifter tap change (`ComputedTapPositionChangeElement`) contributes another $\pm1$/angle column to $\Amat_C$, exactly like an outage; a generator or load change (`LfGeneratorAction`, `LfLoadAction`) instead perturbs the right-hand side $\bm P$. The capacitance matrix then grows to size $K+L$ ($K$ outaged branches $+$ $L$ topology actions), gaining the contingency--action and action--action cross terms, and `setAlphas` solves that single small dense system; `toPostContingencyAndOperatorStrategyStates` superposes both the contingency and the action columns onto the state and the factor states. *Preventive* actions are applied to the base case before the contingencies, *curative* (operator-strategy) actions after. Either way post-action DC sensitivities cost only the enlarged $K+L$ solve --- still no re-factorisation of $\bm B'$. If a topology action changes connectivity, `ConnectivityBreakAnalysis` re-evaluates it (§{ref}`sec:woodconn`).

(sec:bordered)=

## The bordered-system view (why no refactorisation)
There is an illuminating way to see *why* the update works without ever touching $\bm B'$. The modified system $\bm B'_{\text{post}}\bm\varphi_{\text{post}}=\bm P$ is *exactly equivalent* to the larger **bordered** system 

$$
\boxed{\;
  \begin{bmatrix}
    \bm B' & -\Amat_C^{\top}\\[2pt]
    -\Amat_C & \operatorname{diag}(1/\beta_\ell)
  \end{bmatrix}
  \begin{bmatrix}\bm\varphi_{\text{post}}\\[2pt] \bm\alpha\end{bmatrix}
  =
  \begin{bmatrix}\bm P\\[2pt] \bm 0\end{bmatrix}.\;}
$$ (eq:bordered)

 *Proof of equivalence.* The bottom block-row reads $-\Amat_C\bm\varphi_{\text{post}}+\operatorname{diag}(1/\beta_\ell)\bm\alpha=\bm 0$, i.e. $\bm\alpha=\bm\beta_C\Amat_C\bm\varphi_{\text{post}}$. Substituting into the top block-row $\bm B'\bm\varphi_{\text{post}}-\Amat_C^{\top}\bm\alpha=\bm P$ gives $(\bm B'-\Amat_C^{\top}\bm\beta_C\Amat_C)\bm\varphi_{\text{post}}=\bm P$, which is {eq}`eq:rank1`. $\square$

Now solve {eq}`eq:bordered` by **block elimination on the big $\bm B'$ block** --- a Schur complement. Eliminating $\bm\varphi_{\text{post}}=
\bm B'^{-1}(\bm P+\Amat_C^{\top}\bm\alpha)=\bm\varphi_{\text{pre}}+\bm B'^{-1}
\Amat_C^{\top}\bm\alpha$ from the bottom row yields the small system 

$$
\underbrace{\Bigl(\operatorname{diag}(1/\beta_\ell)-\Amat_C\bm B'^{-1}\Amat_C^{\top}\Bigr)}_{\textstyle \bm M}\,\bm\alpha
   =\Amat_C\bm\varphi_{\text{pre}},
$$

 which is precisely {eq}`eq:woodupdate`. The capacitance matrix $\bm M$ is the **Schur complement** of $\bm B'$ in the bordered matrix {eq}`eq:bordered`. The pay-off is structural and worth stating plainly:

::: center
:::

{numref}`fig:bordered` contrasts the two routes.

```{figure} ../_figures/bordered_routes.svg
:name: fig:bordered
:width: 80%

Two routes to the post-contingency state. The Woodbury route never refactorises $\bm B'$.
```

## Matrix illustration (single outage)

Take the 4-bus example of §{ref}`ch:dcsensi` and outage branch $e=(2,4)$, $\bm a_e=[\,0,1,0,-1\,]^\top$ (reference bus 1 removed leaves the $3\times3$ reduced system on buses $2,3,4$). The base solve already gives the column 

$$
\bm c_e=\bm B'^{-1}\bm a_e=
  \begin{bmatrix} c_{e,2}\\ c_{e,3}\\ c_{e,4}\end{bmatrix},
  \qquad
  M_e=\frac{1}{\beta_e}-(c_{e,2}-c_{e,4}),
  \qquad
  \alpha_e=\frac{\varphi_{\text{pre},2}-\varphi_{\text{pre},4}}{M_e},
$$

 and the post-outage angles are $\bm\varphi_{\text{post}}=\bm\varphi_{\text{pre}}+\alpha_e\,\bm c_e$. Every post-contingency flow is then $\beta_\ell(\varphi_{\text{post},i}-
\varphi_{\text{post},j}+\alpha_\ell)$ --- one dot product, no new factorisation. If $M_e\to0$ the outage *islands* the network (the branch was a bridge); OLF detects this by connectivity and handles it separately rather than through {eq}`eq:woodupdate`.

(sec:woodconn)=

## Detecting and handling connectivity loss
An outage set $\{(m_q,k_q)\}_{q=1}^{n}$ that disconnects the graph makes the capacitance matrix $\bm M$ {eq}`eq:capac` singular: the Woodbury coefficients $\bm\alpha$ cannot be formed. OLF must therefore decide *whether* a given outage islands the network, and what to do when it does.

### A cheap islanding test.

Running a graph traversal for every contingency would be costly. Instead OLF reuses the columns $\bm c_p=\bm B'^{-1}\bm a_{p}$ it already computed for the capacitance matrix. For each outaged branch $p$ form 

$$
\sigma_p=\sum_{q=1}^{n}\beta_{q}\,\bigl\lvert (\bm c_p)_{m_q}-(\bm c_p)_{k_q}\bigr\rvert ,
  \qquad \beta_q=\tfrac{1}{x_{(m_q,k_q)}},
$$ (eq:sigmatest)

 the total magnitude of the flow that branch $p$'s unit dipole drives through all outaged branches. If *every* $\sigma_p<1$ the network is provably still connected; if some $\sigma_p\ge1$ a connectivity loss is *likely* and a real graph analysis is triggered. The $\sigma_p$ are computed one at a time and the scan stops at the first $\sigma_p\ge1$. For a single outage this reduces to $\sigma_1=\beta_e\lvert(\bm c_e)_{m}-(\bm c_e)_{k}\rvert$, which equals $1$ exactly when $M_e=1/\beta_e-((\bm c_e)_m-(\bm c_e)_k)=0$ --- the same bridge condition as above.

### Sensitivities after a split.

A factor couples two elements (the variable's bus and the monitored branch); both must lie in the component that carries the slack for the factor to be meaningful. Once islanding is confirmed:

- **single branch:** the base-case sensitivities remain valid for every factor whose elements are in the slack component --- nothing to recompute;

- **several branches:** the post-contingency network splits into $t\le
          n+1$ components. OLF *reconnects* $t-1$ of the outaged lines to rejoin everything into one component; reconnecting exactly $t-1$ lines adds no loop, and because both elements of any retained factor sit in the slack component, *no flow crosses the reconnected lines*. The sensitivities of the reconnected network --- whose $\bm M$ *is* invertible --- therefore equal those of the true post-contingency network.

### Slack and reference flows under a split.

Slack-distribution participants that fall outside the slack component are dropped and the remaining participation factors are rescaled to sum to $1$. The same machinery computes post-contingency *reference flows*: only the right-hand side changes (it becomes the bus injections, so the capacitance system's $\bm c$ changes through the base solve), while $\bm M$ --- which depends only on *which* lines are outaged --- is reused unchanged; injections outside the slack component are zeroed.

(sec:woodreturn)=

## Return codes for uncomputable factors
Not every requested factor can be produced. OLF reports, rather than silently guessing [`sensi/AbstractSensitivityAnalysis.java`](https://github.com/powsybl/powsybl-open-loadflow/blob/main/src/main/java/com/powsybl/openloadflow/sensi/AbstractSensitivityAnalysis.java):

- a variable or function element that *does not exist* in the network is a hard error --- the analysis terminates;

- a *variable* that left the main (slack) connected component after an outage $\to$ a warning, and the factor is not computed;

- a *function* that left the main component $\to$ the sensitivity is $0$ (the function still has a well-defined, here zero, response);

- if *both* are outside the main component the variable takes priority: a warning, factor not computed.

The third case is the `VALID_ONLY_FOR_FUNCTION` status of §{ref}`sec:accont`.
