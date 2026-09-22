# LODF matrix (DC)

Open Load Flow provides a fast computation of the LODF (Line Outage Distribution Factor) matrix in DC, based on the same
building blocks as the [Woodbury based fast DC security analysis](../security/parameters.md#dcfastmode).

## Definition

The LODF of a monitored branch $l$ for the outage of a branch $k$ is the ratio between the active power flow change on
$l$ caused by the outage of $k$ and the pre-outage active power flow of $k$:

$$ \text{LODF}(l, k) = \frac{\Delta P_l}{P_k} $$

It only depends on the network topology and impedances: no load flow needs to be run beforehand, and the factors are
independent of the operating point.

## Modeling and equations

Losing branch $k = (i, j)$ is modeled, in the DC approximation, by a pair of compensating injections of $+1$ and $-1$ at
its terminals. Solving the DC system for this right-hand side gives an angle response $\theta^{(k)}$, from which the
PTDF (Power Transfer Distribution Factor) of any branch to this injection pair is retrieved:

$$ \text{PTDF}(l, k) = \frac{\theta^{(k)}_a - \theta^{(k)}_b}{X_l} \quad \text{for } l = (a, b) $$

The LODF is then obtained from the PTDFs with the classical formula:

$$ \text{LODF}(l, k) = \frac{\text{PTDF}(l, k)}{1 - \text{PTDF}(k, k)} $$

All the needed PTDFs come from a single multiple right-hand side resolution of the DC system (one column per outaged
branch), reusing the LU decomposition of the Jacobian matrix. The computation cost is therefore one sparse solve plus
dense arithmetic, whatever the number of monitored branches and outages. The formula remains exact for branches with a
non-zero phase shift, as the phase shift contribution cancels out in the ratio.

## Conventions

- The LODF of a branch for its own outage is $-1$ (it loses all of its flow).
- The LODF factors of an outage breaking the network connectivity are undefined (the outaged branch is the only path for
  its flow, i.e. $\text{PTDF}(k, k) \approx 1$): the corresponding column is filled with `NaN`.
- A monitored branch without a closed DC flow equation (open on at least one side, or of zero impedance) gets `NaN`.

The computation is done on the main connected component, with phase control forced off (factors are computed at fixed
phase tap positions) and zero impedance branches replaced by minimal impedance ones.

## Usage

The `LodfComputer` class is the network level entry point. It takes an iidm network, the ids of the monitored and
outaged branches, and the load flow parameters:

```java
DenseMatrix lodfMatrix = LodfComputer.computeLodfMatrix(network, monitoredBranchIds, outagedBranchIds, loadFlowParameters);
```

The returned matrix has one row per monitored branch and one column per outaged branch, in the order of the given lists.

To run several LODF queries on the same network without reloading and refactorizing each time, create a reusable
context (loading the network and factorizing the DC Jacobian only once) and reuse it:

```java
try (LodfComputer.Context context = LodfComputer.createContext(network, loadFlowParameters, matrixFactory, reportNode)) {
    DenseMatrix lodf1 = context.computeLodfMatrix(monitoredBranchIds1, outagedBranchIds1);
    DenseMatrix lodf2 = context.computeLodfMatrix(monitoredBranchIds2, outagedBranchIds2);
}
```

### Large networks

The result of `computeLodfMatrix` is a single dense matrix, so the number of monitored branches times the number of
outaged branches must not exceed the dense matrix element limit. For a full N-1 analysis of a very large network (all
branches both monitored and outaged), the matrix would not fit: the `computeLodf` methods instead **stream** each factor
to a `LodfResultWriter` callback, without ever materializing the matrix:

```java
LodfComputer.computeLodfMatrix(network, monitoredBranchIds, outagedBranchIds, loadFlowParameters); // materialized matrix
// or, for a full N-1 that does not fit in a single matrix:
context.computeLodf(monitoredBranchIds, outagedBranchIds, (monitoredIndex, outagedIndex, lodf) -> { /* persist */ });
```

### Multithreading

The `computeLodfMatrix` and `computeLodf` methods accept an optional `threadCount`. The sparse solves stay sequential
(they share the Jacobian factorization), but the factor fill is parallelized across the outaged branches. The result is
identical whatever the thread count. With more than one thread the `LodfResultWriter` may be called concurrently for
different outaged branches, so it must be thread-safe; the dense matrix filled by `computeLodfMatrix` is.
