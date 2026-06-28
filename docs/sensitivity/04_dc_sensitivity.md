(ch:dcsensi)=

# DC sensitivities and the PTDF
The DC model is *linear*, so its sensitivities are exact, constant, and the cleanest illustration of Chapter {ref}`ch:adjoint`. This chapter writes out every matrix involved on a small network and arrives at the classical Power Transfer Distribution Factor.

## The DC model in one page

DC load flow keeps only active power and approximates ({ref}`part:dc`, §{ref}`ch:dcmodel`): $V_i\equiv1$, $\sin(\varphi_i-\varphi_j)\approx\varphi_i-\varphi_j$, $\cos\approx1$, lossless lines. The active flow on branch $\ell$ from bus $i$ to bus $j$ (with phase shift $\alpha_\ell$) collapses to the linear term [`dc/equations/ClosedBranchSide1DcFlowEquationTerm.java`](https://github.com/powsybl/powsybl-open-loadflow/blob/main/src/main/java/com/powsybl/openloadflow/dc/equations/ClosedBranchSide1DcFlowEquationTerm.java) 

$$
P_\ell = -\,\beta_\ell\,(\varphi_j-\varphi_i+A_2-\alpha_\ell)
         = \beta_\ell\,(\varphi_i-\varphi_j+\alpha_\ell),
  \qquad \beta_\ell=\frac{1}{x_\ell},
$$ (eq:dcflow)

 (more precisely $\beta_\ell=b_\ell\,\rho_1 R_2$, but $\rho_1=R_2=1$ for a line). Its only non-zero derivatives are the boxed constants 

$$
\pdv{P_\ell}{\varphi_i}=\beta_\ell,\qquad
  \pdv{P_\ell}{\varphi_j}=-\beta_\ell,\qquad
  \pdv{P_\ell}{\alpha_\ell}=\beta_\ell .
$$ (eq:dcderiv)

## The nodal system: the $\bm B'$ matrix

Summing branch flows into each bus (KCL) gives the nodal active-power balance $\bm P=\bm B'\bm\varphi$, where the DC Jacobian $\bm B'$ is the weighted *graph Laplacian* of the network: 

$$
B'_{ii}=\sum_{\ell\ni i}\beta_\ell,\qquad
  B'_{ij}=-\!\!\sum_{\ell=(i,j)}\beta_\ell\ \ (i\ne j).
$$ (eq:Bprime)

 Equivalently, with the branch--bus **incidence matrix** $\Amat$ ($A_{\ell i}=+1$ if $\ell$ leaves $i$, $-1$ if it enters $i$, else $0$) and the diagonal $\bm\beta=\operatorname{diag}(\beta_\ell)$, 

$$
\boxed{\;\bm B'=\Amat^{\top}\bm\beta\,\Amat,\qquad \bm P=\bm B'\bm\varphi.\;}
$$ (eq:BAA)

 $\bm B'$ is singular (a constant added to every angle changes nothing), so one row/column --- the reference bus --- is removed, exactly the `BUS_TARGET_PHI` gauge of the load flow. The reduced $\bm B'$ *is* the DC Jacobian $\bm J$ that OLF factorises.

### Illustration (4-bus).

Label buses $1,2,3,4$ with bus $1$ the reference, and branches $a{=}(1,2),b{=}(2,3),c{=}(3,4),d{=}(1,4),e{=}(2,4)$. The incidence $\Amat$ (rows $a,b,c,d,e$; columns $1,2,3,4$) and the $4\times4$ Laplacian (before removing the reference) are 

$$
\Amat=
\begin{bmatrix}
 1 & -1 & 0 & 0\\
 0 & 1 & -1 & 0\\
 0 & 0 & 1 & -1\\
 1 & 0 & 0 & -1\\
 0 & 1 & 0 & -1
\end{bmatrix},
\quad
\bm B'=
\begin{bmatrix}
\beta_a{+}\beta_d & -\beta_a & 0 & -\beta_d\\
-\beta_a & \beta_a{+}\beta_b{+}\beta_e & -\beta_b & -\beta_e\\
0 & -\beta_b & \beta_b{+}\beta_c & -\beta_c\\
-\beta_d & -\beta_e & -\beta_c & \beta_c{+}\beta_d{+}\beta_e
\end{bmatrix}.
$$

 The sparsity of $\bm B'$ mirrors the graph: $B'_{ij}\ne0$ iff buses $i,j$ are adjacent --- the same pattern as the AC Jacobian ({ref}`part:ac`, {numref}`fig:sparse`).

## The injection sensitivity (PTDF) by the fundamental identity

Apply Chapter {ref}`ch:adjoint` with variable $p=$ injection at bus $k$ (slack $s$). The right-hand side (§{ref}`sec:slacksub`) is $\bm e_k=\bm 1_k-\bm 1_s$ (single slack), and the state sensitivity {eq}`eq:states` is the solve 

$$
\bm B'\,\bm s_k=\bm e_k
  \quad\Longleftrightarrow\quad
  \bm s_k=\frac{\partial\bm\varphi}{\partial P_k}=\bm B'^{-1}(\bm 1_k-\bm 1_s),
$$ (eq:dcsolve)

 performed in OLF by `j.solveTransposed(rhs)` on the dense RHS whose columns are the $\bm e_k$ for all requested injections `sensi/DcSensitivityAnalysis.java (calculateFactorStates)`. The PTDF of branch $\ell=(i,j)$ is then the dot product {eq}`eq:funcsens` with the DC branch derivatives {eq}`eq:dcderiv`: 

$$
\boxed{\;
  \PTDF_{\ell,k}=\pdv{P_\ell}{P_k}
   =\beta_\ell\bigl(s_{k,i}-s_{k,j}\bigr)
   =\beta_\ell\bigl[\bm B'^{-1}(\bm 1_k-\bm 1_s)\bigr]_{i}
    -\beta_\ell\bigl[\bm B'^{-1}(\bm 1_k-\bm 1_s)\bigr]_{j}.\;}
$$ (eq:ptdf)

 In compact matrix form over *all* branches and *all* buses, the full PTDF matrix is 

$$
\boxed{\;\bm\Phi=\bm\beta\,\Amat\,\bm B'^{-1}\;}\qquad(\text{reference column/row removed}),
$$ (eq:ptdfmat)

 the product of the branch-susceptance diagonal, the incidence, and the inverse Laplacian. OLF never forms $\bm B'^{-1}$ explicitly: it computes the needed columns $\bm B'^{-1}\bm e_k$ by back-substitution and contracts each with a row of $\bm\beta\Amat$ via `calculateSensi`.

## Phase-shifter sensitivity

For a phase variable $\alpha_m$ on branch $m$, the right-hand side is the $\partial\bm t/\partial\alpha_m$ vector --- a $+\beta_m$/$-\beta_m$ pair at the two end buses of branch $m$ (scaled by $\mathrm{rad}(1^\circ)$, §{ref}`ch:factors`) --- and the same machinery gives 

$$
\pdv{P_\ell}{\alpha_m}=\beta_\ell\bigl(s_{m,i}-s_{m,j}\bigr),
  \qquad \bm B'\,\bm s_m=\beta_m(\bm 1_{m\text{-from}}-\bm 1_{m\text{-to}}).
$$

 This is the *phase-shift distribution factor*: how a $1^\circ$ tap on branch $m$ redistributes flow onto branch $\ell$. The incremental phase-control outer loop ({ref}`part:ac`, §{ref}`sec:phasecontrol`) uses exactly this quantity, $\partial
P/\partial\alpha$, to size its tap steps --- the outer-loop control and the sensitivity analysis are literally the same computation.

### The self term.

When the monitored branch *is* the shifter branch ($\ell=m$) the tap also enters the flow $P_m=\beta_m(\varphi_i-\varphi_j+\alpha_m)$ *directly*, so a constant $\beta_m\,\mathrm{rad}(1^\circ)$ adds to the distribution term (the DC analogue of the direct term $g_p$ of §{ref}`sec:accurrent`): 

$$
\pdv{P_m}{\alpha_m}=\beta_m\bigl(s_{m,i}-s_{m,j}\bigr)+\beta_m\,\mathrm{rad}(1^\circ).
$$

## Reference flows

The base-case branch flows (the "function reference" reported alongside each sensitivity) come from one ordinary DC solve $\bm B'\bm\varphi=\bm P$ (a *forward*, non-transposed solve) followed by $P_\ell=\beta_\ell(\varphi_i-\varphi_j+\alpha_\ell)$ --- again `calculateSensi` applied to the flow state column `sensi/DcSensitivityAnalysis.java (calculateFlowStates)`.

:::{admonition} Remark - Constant and exact
:class: seealso
Because $\bm B'$ and {eq}`eq:dcderiv` are constant, DC sensitivities do not depend on the operating point and are exact for the DC model. This is what makes them the workhorse of contingency screening: the same $\bm B'^{-1}$ serves the base case and, via the Woodbury update of Chapter {ref}`ch:woodbury`, every single-contingency case.

:::
