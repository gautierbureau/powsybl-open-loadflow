# Profiling of the KLU sparse LU usage

This page reports a profiling of how Open LoadFlow uses the KLU sparse LU solver (through powsybl-math-native) on a
real-size network, and discusses why `klu_refactor` is only used in some cases.

## How Open LoadFlow drives KLU

`JacobianMatrix` keeps one `LUDecomposition` per Jacobian and tracks what changed since the last decomposition:

| Jacobian status | Typical cause | LU operation |
|---|---|---|
| `VALUES_INVALID` | new Newton-Raphson iteration (state update) | `update(true)`: `klu_refactor` + `klu_rgrowth`, full `klu_factor` if reciprocal pivot growth < 1e-10 |
| `VALUES_AND_ZEROS_INVALID` | an equation term enabled/disabled (e.g. a branch opened by a contingency) | `update(false)`: full `klu_factor` |
| `STRUCTURE_INVALID` | an equation or variable added/removed (e.g. PV → PQ switch, outer loop action) | matrix rebuilt, `klu_analyze` + `klu_factor` |

So `klu_refactor` is already used for Newton-Raphson iterations at constant structure; the question is whether it can
also be used in the other cases.

## Setup

- Network: `case6515rte` (6 520 buses, 7 422 lines, 1 620 two-winding transformers, 1 389 generators),
  enriched IIDM version. Jacobian size: n = 13 040, about 86 500 non-zeros.
- Load flow parameters: DC values voltage initialization, distributed slack, reactive limits.
- Instrumentation: a `MatrixFactory` wrapping `SparseMatrix` / `LUDecomposition` to count and time every
  `decomposeLU`, `update` and `solveTransposed` call, and to dump the matrices and Newton right-hand sides.
- Pure KLU timings are obtained by replaying the dumped Jacobians in a small C program linked against KLU
  (SuiteSparse 7.6), outside of the JVM.
- Machine: 4 vCPU cloud container. Wall-clock times are best of several runs and have a noise of a few
  percent; operation counts are exact.

The LU policy was varied to measure the effect of using `klu_refactor` more broadly:

- **baseline**: Open LoadFlow before the change described at the end of this page.
- **refactor everywhere**: `klu_refactor` + `klu_rgrowth` also on `VALUES_AND_ZEROS_INVALID`.
- **refactor everywhere, no rgrowth check**: same, but a refactorization is never rejected on pivot growth.
- **always factor**: `klu_factor` on every update.

## Results

### Cost of each KLU operation (pure KLU, no JNI)

| Operation | Time |
|---|--:|
| `klu_analyze` | 12.4 ms |
| `klu_factor` | 12.9 ms |
| `klu_refactor` | 4.5 ms |
| `klu_rgrowth` | 1.6 ms |
| `klu_rcond` | 0.07 ms |
| `klu_tsolve` | 0.8 ms |

`klu_refactor` is about 2.9 times faster than `klu_factor`, but the `klu_rgrowth` check adds about 35 % to its cost.
Through JNI, the measured times are close: about 7.0 ms for refactor + rgrowth and 13 ms for a factorization, so
the JNI overhead is small.

### Single load flow

| Scenario | NR iterations | `klu_analyze` + `klu_factor` | `klu_refactor` | LU total |
|---|--:|--:|--:|--:|
| no reactive limits | 4 | 2 | 3 | 69 ms |
| reactive limits | 7 | 4 | 4 | 145 ms |
| reactive limits + transformer, shunt and phase control | 17 | 6 | 17 | 278 ms |

The load flow itself takes 0.8 to 1.7 s, so the LU accounts for about 10 to 15 % of it. No `VALUES_AND_ZEROS_INVALID`
update happens in a single load flow: refactorization is already used whenever possible.

### AC security analysis, 300 N-1 line contingencies

| LU policy | Failed contingencies | `klu_analyze` + `klu_factor` | `klu_factor` | `klu_refactor` | LU total | Security analysis | max \|ΔV\| vs baseline |
|---|--:|--:|--:|--:|--:|--:|--:|
| baseline | 0 | 179 | 447 | 208 | 12.2 s | 57.4 s | – |
| refactor everywhere, exception propagated | **41** (`KLU_SINGULAR`) | | | | | | |
| refactor everywhere, previous `JacobianMatrix` retry | **35** | | | | | | |
| refactor everywhere + factor on failure | 0 | 179 | 40 | 655 (40 failed) | 10.1 s | 52.9 s | 1.5e-9 kV |
| refactor everywhere + factor on failure, no rgrowth check | 0 | 179 | 40 | 655 (40 failed) | 9.7 s | 50.8 s | 1.7e-5 kV |
| always factor | 0 | 179 | 655 | 0 | 13.0 s | 54.7 s | 2e-10 kV |

## Why `klu_refactor` is not safe everywhere

`klu_refactor` reuses the pivot sequence chosen by the last `klu_factor`. This is only valid if those pivots remain
numerically acceptable for the new matrix values.

1. **Exact zero pivots when terms are disabled.** The sparsity pattern is kept when an equation term is
   disabled: its derivatives become explicit zeros. When KLU had selected one of these entries as a pivot,
   `klu_refactor` fails with `KLU_SINGULAR`. This happened on 40 of the 655 `VALUES_AND_ZEROS_INVALID` updates,
   i.e. about 13 % of the contingencies. Before the change below, `JacobianMatrix.updateValues` only retried with a full
   factorization when an incremental update was requested, so simply forcing `update(true)` on this path made
   those contingencies fail.
2. **Tiny pivots after value drift.** Even at constant structure, pivots can degrade along the Newton-Raphson
   iterations. In the base case, the reciprocal pivot growth after refactorization fell to 6e-11 and the relative
   error of the Newton step reached 3.9e-6 compared to a fresh factorization. This is the situation the existing
   1e-10 rgrowth threshold catches. Without the check, Newton-Raphson still converged here, but results differ
   by up to 1.7e-5 kV and nothing detects a bad factorization.
3. **Structure changes.** 179 of the 300 contingencies require a full `klu_analyze` + `klu_factor` because PV/PQ
   switching and outer loops change the equation system structure. Refactorization cannot be used there at all.

## Where the time goes

The LU accounts for about 21 % of the security analysis time (12.2 s out of 57.4 s). Using refactorization
everywhere (with a fallback) saves about 2.2 s of LU time, i.e. 4 to 8 % of the security analysis. The remaining
~45 s are spent outside the linear solver (network and equation system updates per contingency, outer loops,
results).

## Refactorization with fallback on `VALUES_AND_ZEROS_INVALID`

`JacobianMatrix` now also tries an incremental update (`klu_refactor` + `klu_rgrowth`) when some non-zero values may
have become zero. A pivot that became an exact zero makes the refactorization fail and the matrix is factorized
again (the existing retry of `updateValues`); a pivot that became too small is caught by the pivot growth check.

Baseline and patched versions measured in the same session, on the same machine (4 runs each, 300 N-1 line
contingencies; this machine is faster than the one used for the tables above):

| Version | Failed contingencies | `klu_factor` | `klu_refactor` | LU total | Security analysis | max \|ΔV\| vs baseline |
|---|--:|--:|--:|--:|--:|--:|
| baseline | 0 | 447 | 208 | 7.45 – 7.82 s (mean 7.69 s) | 28.6 – 31.4 s (mean 29.9 s) | – |
| refactor + fallback | 0 | 40 | 655 (40 failed and factorized again) | 5.83 – 6.09 s (mean 5.95 s) | 26.2 – 28.0 s (mean 27.3 s) | 1.5e-9 kV |

LU time is reduced by about 23 % and the security analysis by about 9 %, with identical contingency statuses.
Single load flows are unaffected (no `VALUES_AND_ZEROS_INVALID` update), and the whole test suite passes unchanged.

## Other possible improvements

- **Replace the `klu_rgrowth` check by a `klu_rcond` based check** (e.g. reject the refactorization when the
  reciprocal condition estimate drops by many orders of magnitude compared to the last full factorization):
  0.07 ms instead of 1.6 ms per update.
- **Reuse the symbolic analysis on `STRUCTURE_INVALID`** when the resulting sparsity pattern is unchanged, or keep a
  fixed superset pattern: about 12 ms of `klu_analyze` saved per rebuild, 179 times in this study.
