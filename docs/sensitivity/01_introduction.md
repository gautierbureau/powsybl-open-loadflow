(part:sensi)=

(ch:s-intro)=

# Introduction and scope
## What a sensitivity is

A load flow (Parts {ref}`part:ac`--{ref}`part:dc`) answers the question *"what is the state of the network?"*. A **sensitivity analysis** answers the differential question *"how does an output change when an input changes?"*. Formally, a sensitivity is a partial derivative 

$$
\boxed{\;\text{sensitivity}=\pdv{(\text{function})}{(\text{variable})}\;}
$$

 evaluated at the solved operating point, where a *function* is some measurable output (a branch active-power flow, a branch current, a bus voltage) and a *variable* is some controllable input (an injection at a bus, a phase-shifter angle, an HVDC set-point, a voltage target). The archetypal example is the **Power Transfer Distribution Factor** (PTDF): the change in a branch's active power per MW of injection shift between two buses.

## Why it is (almost) free given a load flow

The decisive fact, developed in Chapter {ref}`ch:adjoint`, is that a sensitivity needs *no new nonlinear solve*. The load flow already produced a factorised Jacobian $\Jmat$ ({ref}`part:ac`, Ch. {ref}`ch:jac`--{ref}`ch:linsolve`); a sensitivity is obtained by a single *linear* solve against that already-factorised matrix, followed by a dot product. Hundreds of factors that share the same input variable are obtained from *one* solve. This is why OLF can return large PTDF matrices cheaply.

## Scope of this section

- **Included**: the mathematical definition of sensitivity factors and their taxonomy (Chapter {ref}`ch:factors`); the fundamental adjoint / chain-rule identity that turns a derivative into a linear solve (Chapter {ref}`ch:adjoint`); the **DC** sensitivity computation in full, with matrix illustrations and a numeric worked example (Chapters {ref}`ch:dcsensi`, {ref}`ch:worked`); the **AC** sensitivity computation reusing the converged AC Jacobian (Chapter {ref}`ch:acsensi`); and the **Woodbury** low-rank update for post-contingency sensitivities (Chapter {ref}`ch:woodbury`).

- **Assumed**: the reader knows the load-flow machinery from Parts {ref}`part:ac`--{ref}`part:dc` --- the equation/variable framework, the Jacobian $\Jmat$, `solveTransposed`, and the branch flow equation terms with their `calculateSensi` methods. We reuse that notation without re-deriving it.

- **Deferred**: the full security-analysis contingency engine, remedial actions, and the multi-threaded factor reader are touched only where they bear on the sensitivity mathematics.

## The one-paragraph summary

Write the load-flow system as $\bm g(\xx,\bm p)=\ff(\xx)-\bm t(\bm p)=\bm 0$, where $\bm p$ are the input variables. Implicit differentiation gives $\Jmat\,\dd\xx = -\,\partial\bm g/\partial\bm p\,\dd\bm p$, i.e. $\dd\xx/\dd p = \Jmat^{-1}\rhsv_p$ with $\rhsv_p=-\partial\bm g/\partial p$ a single right-hand-side vector. The sensitivity of a function $h(\xx)$ is then the chain rule $\dd h/\dd p=(\partial h/\partial\xx)\,\dd\xx/\dd p$. In OLF the right-hand side $\rhsv_p$ is built by `fillRhs`, the solve is `Jacobian.solveTransposed`, and the dot product is the function term's `calculateSensi`. Everything else is bookkeeping: grouping factors that share $\bm p$, handling the slack distribution, and updating the solve after a contingency by the Woodbury formula. The rest of this section expands each step.
