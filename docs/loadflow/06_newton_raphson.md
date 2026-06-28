(ch:nr)=

# The Newton--Raphson method
The system $\bm g(\xx)=\ff(\xx)-\bm t=\bm 0$ of {eq}`eq:system` is nonlinear and has no closed-form solution. OLF solves it with the **Newton--Raphson** (NR) iteration, the default AC solver. This chapter develops NR from the one-dimensional idea up to the exact vector iteration coded in `NewtonRaphson.run`, including the subtle sign and transpose conventions.

## One dimension: the tangent idea

To solve $g(x)=0$ for a scalar, expand $g$ to first order around the current guess $x^{(k)}$: 

$$
g(x)\approx g(x^{(k)})+g'(x^{(k)})\,(x-x^{(k)}).
$$

 Setting the right-hand side to zero and solving for $x$ gives the next iterate 

$$
x^{(k+1)}=x^{(k)}-\frac{g(x^{(k)})}{g'(x^{(k)})}.
$$

 Geometrically: follow the tangent at $x^{(k)}$ down to the axis. If $x^\star$ is a simple root and $g$ is smooth, the error obeys $|x^{(k+1)}-x^\star|\le C\,|x^{(k)}-x^\star|^2$ --- **quadratic convergence**: the number of correct digits roughly doubles each step.

## Many dimensions: the Jacobian

For a vector map $\bm g:\real^n\!\to\real^n$ the derivative is the **Jacobian matrix** 

$$
\Jmat(\xx)=\pdv{\bm g}{\xx}
   =\begin{bmatrix}
      \partial g_1/\partial x_1 & \cdots & \partial g_1/\partial x_n\\
      \vdots & \ddots & \vdots\\
      \partial g_n/\partial x_1 & \cdots & \partial g_n/\partial x_n
    \end{bmatrix}.
$$

 The first-order (multivariate Taylor) expansion is $\bm g(\xx)\approx \bm g(\xx^{(k)})+\Jmat(\xx^{(k)})\,(\xx-\xx^{(k)})$. Because $\bm t$ is constant, $\partial\bm g/\partial\xx=\partial\ff/\partial\xx$, so the Jacobian of the *mismatch* equals the Jacobian of the equation vector $\ff$ --- exactly the matrix built from the branch derivatives of §{ref}`sec:branchderiv`. Setting the linearisation to zero gives the **Newton step** $\Delta\xx^{(k)}$: 

$$
\boxed{\;\Jmat(\xx^{(k)})\,\Delta\xx^{(k)} = \bm g(\xx^{(k)}) = \ff(\xx^{(k)})-\bm t,
  \qquad
  \xx^{(k+1)}=\xx^{(k)}-\Delta\xx^{(k)}.\;}
$$ (eq:newton)

 Each iteration thus requires (i) evaluating the mismatch $\bm g$, (ii) building $\Jmat$, (iii) solving *one linear system*, and (iv) updating $\xx$. Step (iii) is the expensive part and is delegated to the sparse LU solver of Chapter {ref}`ch:linsolve`.

(sec:nriter)=

## The OLF iteration, line by line
`ac/solver/NewtonRaphson.java (runIteration), AbstractAcSolver.java`

The implemented loop maps onto {eq}`eq:newton` as follows. Let $\bm f := \ff(\xx)-\bm t$ be the current mismatch array (already formed at the end of the previous iteration / at initialisation).

1.  **Solve for the step.** `j.solveTransposed(equationVector)` overwrites the mismatch array in place with the solution of $\Jmat\T\,\Delta\xx=\bm f$. (The transpose is explained in §{ref}`transposed Jacobian storage <sec:transpose>`.) After this call the array *is* $\Delta\xx$.

2.  **Scale the step (optional).** `svScaling.apply(...)` may shrink $\Delta\xx$ to keep the iteration stable (Chapter {ref}`ch:stop`); by default it does nothing.

3.  **Update the state.** `stateVector.minus(`$\Delta\xx$`)` performs $\xx\leftarrow\xx-\Delta\xx$, the second half of {eq}`eq:newton`. Updating the state *automatically invalidates* the cached equation and Jacobian values through the listener mechanism (§{ref}`sec:lazy`).

4.  **Recompute the mismatch.** `equationVector.minus(targetVector)` re-evaluates $\ff(\xx^{(k+1)})$ and subtracts $\bm t$, so the array again holds $\bm g(\xx^{(k+1)})$.

5.  **Test convergence.** The stopping criterion (§{ref}`sec:stopcrit`) examines $\norm{\bm g}$. If satisfied, return `CONVERGED`; else loop.

:::{admonition} Remark - Sign convention
:class: seealso
OLF solves $\Jmat\,\Delta\xx=+\bm g$ and then *subtracts* ($\xx\leftarrow\xx-\Delta\xx$), which is identical to the textbook $\xx\leftarrow\xx+\Delta\xx$ with $\Jmat\,\Delta\xx=-\bm g$. The two sign choices are equivalent; OLF's keeps the mismatch array reusable as both the right-hand side and (after the solve) the step.

:::

(sec:transpose)=

## Why $\Jmat$ is factorised *transposed*
OLF assembles the Jacobian in **column-major** form: for each equation/column it stores the derivatives with respect to all variables (rows). Concretely, the matrix element written at $(\text{row}=\text{variable},\
\text{col}=\text{equation})$ holds $\partial(\text{equation})/\partial
(\text{variable})$. That layout is the *transpose* of the mathematical $\Jmat$ whose $(i,j)$ entry is $\partial g_i/\partial x_j$. Solving $\Jmat\,\Delta\xx=\bm g$ is therefore done by calling `solveTransposed` on the stored matrix --- the storage convention and the solve convention cancel out, and no explicit transpose is ever formed. This is a performance decision: it lets the column-wise assembly (§{ref}`sec:assembly`) write directly into the sparse structure the solver expects.

## Convergence behaviour and its limits

NR converges quadratically *near* a solution but is only *locally* convergent: a poor initial guess can diverge or oscillate. OLF mitigates this with

- good **initialisation** (Chapter {ref}`ch:init`) --- flat start, a DC pre-solve for angles, or a linear magnitude pre-solve;

- **state-vector scaling** (Chapter {ref}`ch:stop`) --- line search or a cap on the per-step voltage/angle change, which globalise NR by preventing wild overshoots;

- a hard **iteration cap** (`maxIterations`, default modest) that returns `MAX_ITERATION_REACHED` rather than spinning forever;

- a **realistic-state check**: if any bus voltage leaves a plausible band $[V_{\min},V_{\max}]$, the result is flagged `UNREALISTIC_STATE` (the engine, §{ref}`sec:driver`).

## Variants

OLF ships three NR-family solvers behind the same interface [`ac/solver/`](https://github.com/powsybl/powsybl-open-loadflow/blob/main/src/main/java/com/powsybl/openloadflow/ac/solver/):

- **Newton--Raphson** (default): the exact method above with the full analytic Jacobian.

- **Newton--Krylov**: replaces the direct LU solve by an iterative (Krylov) linear solver --- useful for very large systems where factorisation is costly.

- **Fast-decoupled**: exploits the weak $P$--$V$ and $Q$--$\varphi$ coupling to use two *constant*, pre-factorised matrices instead of re-factorising the full $\Jmat$ each step; cheaper per iteration but more iterations, and only the $H$ and $L$ blocks of §{ref}`sec:3bus` are kept.

All three reuse the equation/Jacobian framework of Chapter {ref}`ch:framework`; they differ only in how {eq}`eq:newton` is (approximately) solved.

(sec:fastdecoupled)=

### Fast-decoupled in detail
Selected by `acSolverType` $=$ `FAST_DECOUPLED`, this solver attacks the *same* equation system but replaces the one full Jacobian by two smaller matrices [`ac/solver/`](https://github.com/powsybl/powsybl-open-loadflow/blob/main/src/main/java/com/powsybl/openloadflow/ac/solver/): one couples the active-power equations to the voltage *angles*, the other the reactive-power equations to the voltage *magnitudes*. Discarding the weak cross terms (the $N,J$ blocks of §{ref}`sec:3bus`) leaves an approximation of $\Jmat$ that is, up to multiplication by a diagonal matrix, *constant*. Both matrices are therefore LU-factorised *once*, at the first iteration, and reused --- the per-iteration cost drops to two back-substitutions. ("Fast-decoupled" is the standard academic name, not a claim that it beats Newton--Raphson in wall-clock terms.)

#### Scaling.

Because each step is built from a deliberately simplified view of the system, the solver needs both the max-voltage-change clamp *and* the line-search of §{ref}`sec:scaling` to converge on realistic large networks; without them it tends to diverge. The `stateVectorScalingMode` parameter is *ignored* (these two routines are always applied).

#### Limitations.

The current implementation is incompatible with `asymmetrical` ({ref}`part:asym`) and with HVDC AC emulation (`hvdcAcEmulation`); requesting either alongside `FAST_DECOUPLED` throws. Since the default parameters are tuned for Newton--Raphson, the recommended settings when fast-decoupled is used are `maxNewtonRaphsonIterations` $=75$, `lineSearchStateVectorScalingMaxIteration` $=4$, and `lineSearchStateVectorScalingStepFold` $=1.5$.
