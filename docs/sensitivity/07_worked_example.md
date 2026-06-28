(ch:worked)=

# A fully worked numeric example
We now carry a concrete 3-bus network through the entire DC sensitivity computation with real numbers --- the $\bm B'$ matrix, its inverse, the PTDF column, and a Woodbury contingency update that we cross-check against a full re-solve. Every number can be reproduced by hand.

## The network

```{figure} ../_figures/three_bus.svg
:name: fig:3bus
:width: 80%

The worked three-bus example: bus 1 is the slack and angle reference.
```

## The reduced $\bm B'$ matrix

Removing the reference bus 1 (§{ref}`ch:dcsensi`) leaves the unknown angles $(\varphi_2,\varphi_3)$ and the $2\times2$ Laplacian {eq}`eq:Bprime`: 

$$
B'_{22}=\beta_a+\beta_b=20,\quad
  B'_{33}=\beta_b+\beta_c=15,\quad
  B'_{23}=B'_{32}=-\beta_b=-10,
$$

 

$$
\bm B'=\begin{bmatrix}20 & -10\\ -10 & 15\end{bmatrix},
  \qquad
  \det\bm B'=200,
  \qquad
  \bm B'^{-1}=\frac{1}{200}\begin{bmatrix}15 & 10\\ 10 & 20\end{bmatrix}
   =\begin{bmatrix}0.075 & 0.05\\ 0.05 & 0.10\end{bmatrix}.
$$

## PTDF for an injection at bus 2

Inject $+1\pu$ at bus 2, balanced by the slack at bus 1: the right-hand side (§{ref}`sec:slacksub`) reduces to $\bm e_2=[\,1,\,0\,]^\top$ on buses $(2,3)$. The state sensitivity {eq}`eq:dcsolve` is the solve $\bm B'\bm s_2=\bm e_2$: 

$$
\bm s_2=\bm B'^{-1}\bm e_2=\begin{bmatrix}0.075\\ 0.05\end{bmatrix}
  \quad\Rightarrow\quad
  \pdv{\varphi_2}{P_2}=0.075,\ \ \pdv{\varphi_3}{P_2}=0.05 .
$$

 Now apply the PTDF dot product {eq}`eq:ptdf` (recall $\varphi_1=0$): 

$$
\begin{aligned}
  \PTDF_{a,2}&=\beta_a(\,s_{2,1}-s_{2,2}\,)=10\,(0-0.075)=-0.75,\\
  \PTDF_{b,2}&=\beta_b(\,s_{2,2}-s_{2,3}\,)=10\,(0.075-0.05)=+0.25,\\
  \PTDF_{c,2}&=\beta_c(\,s_{2,1}-s_{2,3}\,)=5\,(0-0.05)=-0.25 .
\end{aligned}
$$

 **Interpretation and sanity check.** Of the $1\pu$ injected at bus 2, $0.75$ flows directly to the slack on branch $a$ (the minus sign just means the flow is $2\!\to\!1$), and $0.25$ takes the detour $2\!\to\!3\!\to\!1$ on branches $b$ then $c$. The injection splits inversely to path impedance: the direct path $a$ has $x=0.1$, the detour $b{+}c$ has $x=0.3$, so the split is $\tfrac{1/0.1}{1/0.1+1/0.3}=0.75$ vs $0.25$ --- exactly the PTDF. Power balance holds at every bus.

## Contingency on branch $a$ by Woodbury

Outage branch $a=(1,2)$. Its reduced incidence column (to-bus 2 entry $-1$) is $\bm a_a=[\,-1,\,0\,]^\top$. Following §{ref}`ch:woodbury`, all quantities reuse the *base* $\bm B'^{-1}$: 

$$
\bm c_a=\bm B'^{-1}\bm a_a=\begin{bmatrix}-0.075\\ -0.05\end{bmatrix},
  \qquad
  M_a=\frac{1}{\beta_a}-\bm a_a^{\top}\bm c_a=0.1-0.075=0.025 .
$$

 The flow-transfer scalar for the bus-2 injection state $\bm\varphi_{\text{pre}}=\bm s_2=[0.075,0.05]^\top$ is 

$$
\alpha_a=\frac{\bm a_a^{\top}\bm\varphi_{\text{pre}}}{M_a}
   =\frac{-0.075}{0.025}=-3.0,
$$

 and the post-contingency state {eq}`eq:woodupdate` is 

$$
\bm\varphi_{\text{post}}=\bm\varphi_{\text{pre}}+\alpha_a\,\bm c_a
   =\begin{bmatrix}0.075\\0.05\end{bmatrix}
    +(-3.0)\begin{bmatrix}-0.075\\-0.05\end{bmatrix}
   =\begin{bmatrix}0.30\\0.20\end{bmatrix}.
$$

### Cross-check by direct re-solve.

With branch $a$ removed, $B'_{22}=\beta_b=10$, so 

$$
\bm B'_{\text{post}}=\begin{bmatrix}10&-10\\-10&15\end{bmatrix},\quad
  \bm B'^{-1}_{\text{post}}=\frac{1}{50}\begin{bmatrix}15&10\\10&10\end{bmatrix},\quad
  \bm B'^{-1}_{\text{post}}\bm e_2=\begin{bmatrix}0.30\\0.20\end{bmatrix}.
$$

 Identical to the Woodbury result --- but obtained *without* re-factorising, using only the base $\bm B'^{-1}$, one column $\bm c_a$, and a scalar division. The post-contingency flows follow at once: $P_b=\beta_b(\varphi_2-\varphi_3)=10(0.30-0.20)=1.0$ and $P_c=\beta_c(0-\varphi_3)=5(-0.20)=-1.0$: with branch $a$ gone, the full $1\pu$ injection is forced along $2\!\to\!3\!\to\!1$, as physics demands.

## What OLF actually executed

In OLF terms, this example is: one DC load flow for the reference state; one `solveTransposed` producing the column $\bm s_2$; three `calculateSensi` dot products for the three branch PTDFs; and, for the contingency, one `ComputedElement` state $\bm c_a$, one $1\times1$ capacitance "matrix" $M_a$, and the rank-one update --- all reusing the single base-case factorisation. Scaled up, this is how OLF returns full PTDF matrices across large contingency lists at a cost dominated by one matrix factorisation.

## Where to go next

This section covered the sensitivity *mathematics*: the factor taxonomy, the adjoint identity, the DC PTDF, the AC reuse of the converged Jacobian, and the Woodbury contingency update. The natural continuations --- the full security (contingency) analysis with operator strategies and remedial actions (the {ref}`Security analysis <part:security>` section), the multi-threaded factor reader, and the AC contingency engine --- build directly on the kernel established here.
