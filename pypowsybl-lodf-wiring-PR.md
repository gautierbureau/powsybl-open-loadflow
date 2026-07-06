# Add LODF matrix computation API wired to powsybl-open-loadflow

## How to apply on the fork

```bash
git clone git@github.com:gautierbureau/pypowsybl.git && cd pypowsybl
git checkout -b lodf-wiring
curl -sL https://github.com/gautierbureau/powsybl-open-loadflow/raw/claude/lodf-factors-dc-sa-i1ttsi/pypowsybl-lodf-wiring.patch | git am
git push -u origin lodf-wiring
# then open a PR: base gautierbureau:main <- head lodf-wiring
```

## What is done

Adds `pypowsybl.sensitivity.compute_lodf_matrix(network, monitored_branch_ids, outaged_branch_ids, parameters=None)`,
returning a pandas DataFrame with one row per monitored branch and one column per outaged branch
(-1 on the diagonal, NaN columns for connectivity-breaking outages).

Wired through all layers:
- `@CEntryPoint computeLodfMatrix` in `SensitivityAnalysisCFunctions`
- C++ wrapper (`powsybl-cpp.h/.cpp`) + pybind11 binding + `.pyi` stub
- Python API (`compute_lodf_matrix`) and tests

Delegates to open-loadflow's `LodfComputer`: all factors from a single multiple right-hand side
sparse solve of the DC system (a 16049x1000 matrix on the 9241-bus Pegase case computes in ~1 s).

## Verified

Full GraalVM native build; all 162 pypowsybl Java tests and all 20 Python sensitivity tests pass,
including the new LODF tests. IEEE-14 values also validated against brute-force DC load flows.

## Temporary (second commit, to drop after the next OLF release)

Pins `powsybl-open-loadflow` 2.4.0-SNAPSHOT + powsybl-core 7.3.0, declares
`powsybl-loadflow-validation` explicitly, and adapts `OperationalLimitsDataframeAdder` to the
iidm 7.3.0 `Collection`->`List` change.

Note: OLF's vectorized-AC default shifts AC results by ~1e-12, which trips
`SecurityAnalysisTest.testStateMonitors`' 1e-12 tolerance — worth addressing when the vectorized
OLF ships.
