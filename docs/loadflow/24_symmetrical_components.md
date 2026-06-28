(part:asym)=

(ch:seq)=

# Symmetrical components and the three-sequence model
Everything so far assumed the three phases are balanced, so a single complex phasor per bus --- the *positive sequence* --- describes the whole network. Reality is often not balanced: single-phase traction loads, untransposed distribution feeders, a permanently open phase, or simply unequal per-phase loads make the three phase voltages differ in magnitude *and* relative angle. An *unbalanced* (asymmetric) load flow solves for all three phases at once. OLF does this not in phase coordinates but in *symmetrical components*, where the problem very nearly decouples --- and that near-decoupling is the whole reason the method is efficient. This part is enabled by the `asymmetrical` parameter ([`network/LfNetworkParameters.java`](https://github.com/powsybl/powsybl-open-loadflow/blob/main/src/main/java/com/powsybl/openloadflow/network/LfNetworkParameters.java)) and reuses, unchanged, the Newton/Jacobian/LU machinery of the AC load flow.

## The Fortescue transform

Fortescue's theorem writes any three phase phasors $(\Vc_a,\Vc_b,\Vc_c)$ as a sum of three balanced sets --- a *zero* (homopolar), a *positive* (direct) and a *negative* (inverse) sequence. With the rotation operator $a=\e^{\jj2\pi/3}=-\tfrac12+\jj\tfrac{\sqrt3}{2}$, OLF's convention ([`util/Fortescue.java`](https://github.com/powsybl/powsybl-open-loadflow/blob/main/src/main/java/com/powsybl/openloadflow/util/Fortescue.java)) orders the sequences $(0,+,-)$ and uses 

$$
\begin{bmatrix}\Vc_a\\\Vc_b\\\Vc_c\end{bmatrix}
  =\underbrace{\begin{bmatrix}1&1&1\\ 1&a^{2}&a\\ 1&a&a^{2}\end{bmatrix}}_{\Amat_F}
  \begin{bmatrix}\Vc_0\\\Vc_{+}\\\Vc_{-}\end{bmatrix},
  \qquad
  \begin{bmatrix}\Vc_0\\\Vc_{+}\\\Vc_{-}\end{bmatrix}
  =\underbrace{\frac13\begin{bmatrix}1&1&1\\ 1&a&a^{2}\\ 1&a^{2}&a\end{bmatrix}}_{\Amat_F^{-1}}
  \begin{bmatrix}\Vc_a\\\Vc_b\\\Vc_c\end{bmatrix}.
$$ (eq:fortescue)

 The code stores each complex quantity in Cartesian form $(x,y)=(\Re,\Im)$, so $\Amat_F$ is realised as a $6\times6$ matrix in which every complex entry $z=x+\jj y$ becomes the $2\times2$ block $\left(\begin{smallmatrix}x&-y\\ y&x\end{smallmatrix}\right)$ (`createMatrix`, `createInverseMatrix`). Sequence quantities are tagged *h* (homopolar/zero), *d* (direct/positive) and *i* (inverse/negative) throughout the source.

## Why sequences: the near-decoupling

The transform earns its keep because of one structural fact. A symmetric (transposed) three-phase line has a *circulant* phase-impedance matrix 

$$
\bm Z_{abc}=\begin{bmatrix}z_s&z_m&z_m\\ z_m&z_s&z_m\\ z_m&z_m&z_s\end{bmatrix},
$$

 and $\Amat_F$ *diagonalises* every circulant matrix: $\Amat_F^{-1}\bm Z_{abc}\Amat_F=\operatorname{diag}(z_0,z_+,z_-)$ with $z_0=z_s+2z_m$, $z_\pm=z_s-z_m$. So in sequence coordinates a balanced line is three *independent* single-phase $\pi$-models (Chapter {ref}`ch:branch`) --- one per sequence, with no coupling between them. The three sequence networks therefore run side by side and only *interact where the phase symmetry is broken*: at unbalanced loads, at rotating machines (whose negative/zero-sequence impedance differs from positive), at shunts, and on branches with an open phase.

:::{admonition} Remark - The shape of the problem
:class: seealso
An unbalanced load flow is thus *three coupled copies* of the balanced one: the positive-sequence network of Parts I--II, plus a negative- and a zero-sequence network, stitched together only at the handful of unbalanced elements. The bulk of the network (the lines) stays block-diagonal --- which keeps the enlarged Jacobian sparse and the Newton solve tractable. The next chapter builds the coupled equation system; Chapter {ref}`ch:asymsolve` solves it and recovers the phases.

:::
