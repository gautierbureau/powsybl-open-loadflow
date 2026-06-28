(ch:linsolve)=

(ch:linsolver)=
# Solving the linear system: the native math layer
Each Newton iteration solves one sparse linear system $\Jmat\T\Delta\xx=\bm g$ (§{ref}`sec:nriter`). This is where the overwhelming majority of CPU time goes, and it is handled not in OLF itself but in the *PowSyBl Math* layer, which wraps a native sparse **LU** solver (KLU from the SuiteSparse collection). This chapter explains, self-contained, what that solver does: sparse storage, LU factorisation with partial pivoting, fill-reducing ordering, and the factor-once / solve-many and incremental-refactor optimisations OLF relies on.

## Sparse matrix storage (CSC)

A dense $n\times n$ matrix needs $n^2$ numbers; the Jacobian has only $O(n)$ non-zeros (§{ref}`sec:assembly`), so it is stored in **compressed sparse column** (CSC) format: three arrays 

$$
\texttt{values}[k],\quad \texttt{rowIndices}[k],\quad \texttt{columnStart}[j],
$$

 listing, column by column, the non-zero `values` and their row indices, with `columnStart` marking where each column begins. This is exactly the layout that OLF's column-by-column assembly (§{ref}`sec:assembly`) produces, and exactly what KLU consumes --- no format conversion is needed. A matrix element's position in `values` is remembered (the `matrixElementIndex`) so that re-evaluating the Jacobian on the next Newton step overwrites values in place (`addAtIndex`) without touching the structure.

## LU factorisation

To solve $\Amat\bm z=\bm b$ for many right-hand sides, one *factorises* $\Amat$ once. **LU decomposition** with partial pivoting writes 

$$
\Pmat\,\Amat = \Lmat\,\Umat,
$$ (eq:lu)

 with $\Pmat$ a permutation (row pivoting), $\Lmat$ unit lower-triangular, $\Umat$ upper-triangular. Once $\Lmat,\Umat,\Pmat$ are known, each solve is two cheap triangular sweeps: 

$$
\Amat\bm z=\bm b
  \;\Longleftrightarrow\;
  \Lmat\bm y=\Pmat\bm b\ \text{(forward substitution)},\quad
  \Umat\bm z=\bm y\ \text{(back substitution)} .
$$

 Forward substitution solves $y_i=(\Pmat\bm b)_i-\sum_{k<i}L_{ik}y_k$ top-down; back substitution solves $z_i=(y_i-\sum_{k>i}U_{ik}z_k)/U_{ii}$ bottom-up. The factorisation is $O(n^3)$ for a dense matrix but only $O(n^{1.x})$ for a well-ordered sparse one --- hence the importance of ordering.

### Partial pivoting.

At each elimination step the algorithm swaps in the row with the largest available pivot ($\Pmat$), which bounds the multipliers $|L_{ik}|\le1$ and keeps the factorisation numerically stable. A (near-)zero pivot signals a (numerically) singular Jacobian --- in load-flow terms, a lost reference, an islanded sub-network, or a degenerate control --- and surfaces as a `MatrixException`/`SOLVER_FAILED` (§{ref}`sec:nriter`).

## Transposed solve

OLF stores $\Jmat\T$ and wants $\Delta\xx$ from $\Jmat\,\Delta\xx=\bm g$, i.e. it must solve a system with the *transpose* of the stored matrix (§{ref}`transposed Jacobian storage <sec:transpose>`). Given the factors of the stored matrix $\Amat=\Jmat\T=\Lmat\Umat$ (ignoring $\Pmat$ for clarity), $\Amat\T=\Umat\T\Lmat\T$, so a transposed solve reuses the *same* $\Lmat,\Umat$ with the roles of forward/back substitution and the transposed triangular factors swapped --- no re-factorisation, no explicit transpose. `solveTransposed` exposes exactly this.

## Fill-reducing ordering (the KLU pipeline)

Naive Gaussian elimination on a sparse matrix creates **fill-in**: zeros that become non-zero during elimination, inflating $\Lmat,\Umat$ far beyond $\Amat$. The cost of the factorisation is dominated by how much fill it generates, and fill depends drastically on the *order* in which variables are eliminated. KLU's strategy, applied once in a *symbolic* pre-analysis:

1.  **BTF (block triangular form)**: permute to expose a block upper-triangular structure; only the diagonal blocks need full LU, the rest is substitution. For a connected power network this is usually one block, but it cleanly isolates weakly-coupled sub-systems.

2.  **Fill-reducing permutation** (AMD --- approximate minimum degree, or COLAMD) on each block: choose an elimination order that heuristically minimises fill by always eliminating the node of smallest current degree (fewest connections) next. On grid-like graphs this keeps $\Lmat,\Umat$ nearly as sparse as $\Amat$.

3.  **Symbolic factorisation**: with the order fixed, pre-compute the non-zero pattern of $\Lmat,\Umat$ once.

4.  **Numeric factorisation** (Gilbert--Peierls left-looking algorithm with partial pivoting): fill in the actual values.

The symbolic steps (1--3) depend only on the *structure* of $\Jmat$, which is fixed for a given set of active equations. They are done once per structure and reused across all Newton iterations --- this is the payoff of the `STRUCTURE_INVALID` vs `VALUES_INVALID` distinction of §{ref}`sec:lazy`.

## Factor once, refactor incrementally

OLF maps the cache states of §{ref}`sec:lazy` onto KLU operations [`equations/JacobianMatrix.java`](https://github.com/powsybl/powsybl-open-loadflow/blob/main/src/main/java/com/powsybl/openloadflow/equations/JacobianMatrix.java):

- `VALUES_INVALID` (ordinary Newton step): the structure is intact, only numbers changed. KLU does a **numeric refactorisation** (`klu_refactor`) reusing the symbolic analysis and the previous pivot order --- the cheap path. OLF requests an *incremental* update and, if the pivoting turns out invalid, falls back to a full refactor.

- `VALUES_AND_ZEROS_INVALID` (a term toggled): refactor without the incremental short-cut.

- `STRUCTURE_INVALID` (an outer loop changed which equations are active): redo the symbolic analysis *and* the numeric factorisation.

Because a converging Newton run keeps the same structure, the expensive symbolic analysis happens essentially once at the start, and each subsequent iteration pays only a numeric refactor plus two triangular solves. This is the single biggest reason OLF is fast.

## Why a direct solver (and when not)

Power-system Jacobians are sparse, irregular, and change only in values between iterations --- the ideal case for a sparse *direct* LU solver: robust, no tuning, exact up to round-off. For extremely large systems where even sparse LU fill becomes costly, OLF offers the Newton--Krylov variant (§{ref}`sec:nriter`) which replaces the factorisation by an iterative linear solver and never forms $\Lmat,\Umat$. The default, and the subject of this document, is direct LU via KLU.
