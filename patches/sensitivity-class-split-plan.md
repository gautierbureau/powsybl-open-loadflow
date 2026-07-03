# Plan: split the big sensitivity-analysis classes

**Status:** planned, NOT executed. This note records how to break up
`AbstractSensitivityAnalysis` (a ~1650-line god-class) into per-concern files, and — importantly — **when**
to do it so it does not clash with the performance changes on `claude/ac-sensitivity-refactor-3qrbqn`.

## Why not now — the ordering constraint

A structural move (code → new files) and content edits to the *same* class cannot both be independent
branches off `main`; whoever merges second conflicts. The perf pieces already edit
`AbstractSensitivityAnalysis`:

- **Phase 0** (`perf/ac-sensi-phase0-list-copies`) adds `getFactorCount()` / `hasInvalidFactors()`
  **inside `SensitivityFactorHolder`** — the exact class this split moves to its own file. **Direct clash.**
- **Ingestion memoization** (`perf/ac-sensi-ingestion-memoization`) edits the `readAndCheck` region, which
  this split does *not* move — probably auto-merges, but same-file churn.

**Rule: do this split LAST, on top of the perf work.** Either
1. merge the 5 perf PRs first, then run this split on the updated `main` (cleanest history), or
2. base the split branch on the umbrella branch (which already has the perf changes) so it extracts the
   *already-optimized* classes; the split PR then stacks on the perf PRs.

Do **not** base it on bare `main` in parallel with the perf pieces.

## What to extract (one file per class, package-private)

The nested types are all `protected static` generics that use **no outer instance state**, so extracting
them to top-level package-private classes in `com.powsybl.openloadflow.sensi` is mechanical and
behaviour-preserving. Line ranges below are approximate (from `main`); re-locate by class name, they shift
with the perf changes.

### Factor model (~345 lines out)
| New file | Nested type (approx lines on main) |
|---|---|
| `LfSensitivityFactor.java` | `interface LfSensitivityFactor` + `enum Status` (72–122) |
| `AbstractLfSensitivityFactor.java` | `AbstractLfSensitivityFactor` (124–293) |
| `SingleVariableLfSensitivityFactor.java` | `SingleVariableLfSensitivityFactor` (295–362) |
| `MultiVariablesLfSensitivityFactor.java` | `MultiVariablesLfSensitivityFactor` (364–417) |

### Factor group model (~310 lines out)
| New file | Nested type |
|---|---|
| `SensitivityFactorGroup.java` | `interface SensitivityFactorGroup` (419–430) |
| `AbstractSensitivityFactorGroup.java` | `AbstractSensitivityFactorGroup` (432–481) — also give it `createVariableTypeNotImplementedException` (see below) |
| `SingleVariableFactorGroup.java` | `SingleVariableFactorGroup` (487–651); keeps `public static computeBranchFlowParameterPartials` used by `AcSensitivityAnalysis` |
| `MultiVariablesFactorGroup.java` | `MultiVariablesFactorGroup` (653–710) |
| `SensitivityFactorGroupList.java` | `SensitivityFactorGroupList` (712–730) |

### Factor holder (~80 lines out)
| New file | Nested type |
|---|---|
| `SensitivityFactorHolder.java` | `SensitivityFactorHolder` (1048–1129; on the perf lineage it also carries `getFactorCount`/`hasInvalidFactors`) |

Result: `AbstractSensitivityAnalysis` drops from ~1650 to ~900 lines, keeping only the orchestration
methods (`createFactorGroups`, `initFactorsRhs`, `fillRhsSensitivityVariable`, `setPredefinedResults`,
`getPredefinedResults`, `rescaleGlsk`, `writeInvalidFactors`, the `check*` helpers,
`readAndCheckFactors`/`readAndCheck`, `InjectionVariableIdToBusIdCache`,
`VariablesTargetVoltageInfo`, `getVariableTargetVoltageInfo`). Factor *reading* (readAndCheck +
`InjectionVariableIdToBusIdCache`) is intentionally left in place for this pass — it couples to
`parameters`; extract it later as a separate step if desired.

## Mechanics / gotchas

- **Visibility:** `protected static class X` → top-level `class X` (package-private). `AcSensitivityAnalysis`,
  `DcSensitivityAnalysis`, `LfSensitivityFactorStore` and the tests are all in the same package, so
  package-private preserves the access they need. Keep the same generic signature
  `<V extends Enum<V> & Quantity, E extends Enum<E> & Quantity>`.
- **Imports:** `AbstractSensitivityAnalysis` already uses wildcard imports
  (`com.powsybl.openloadflow.network.*`, `...ac.equations.*`, `...equations.*`, `com.powsybl.sensitivity.*`,
  `java.util.*`), and the project's checkstyle does not forbid star imports — reuse the needed wildcards per
  file to avoid fiddly per-symbol imports. Specific imports each file is likely to need:
  `Derivable`, `Evaluable` (factor); `Matrix`/`DenseMatrix`, `ArrayUtils`, `NotImplementedException`,
  `Pair`, the `ClosedBranchSide*` terms (group); `ContingencyContextType` (holder). Remove unused specific
  imports — checkstyle's import-order check runs, and unused *specific* imports fail.
- **Move `createVariableTypeNotImplementedException`** (currently `private static` in
  `AbstractSensitivityAnalysis`, ~483) into `AbstractSensitivityFactorGroup` as `protected static`, since only
  `SingleVariableFactorGroup` / `MultiVariablesFactorGroup` use it (their `fillRhs`).
- **Qualified references to rewrite** (`AbstractSensitivityAnalysis.X` → `X`):
  - `DcSensitivityAnalysis.java` — `AbstractSensitivityAnalysis.LfSensitivityFactor` (~615).
  - `DcSensitivityAnalysisTest.java` — `AbstractSensitivityAnalysis.SensitivityFactorGroupList`,
    `AbstractSensitivityAnalysis.SensitivityFactorGroup` (~1219–1220, Mockito mocks).
  - On the perf/umbrella lineage only: `LfSensitivityFactorStore.java` and `SoaFactorStoreExperiment.java`
    reference `AbstractSensitivityAnalysis.LfSensitivityFactor.Status` and
    `AbstractSensitivityAnalysis.SingleVariableLfSensitivityFactor` → change to
    `LfSensitivityFactor.Status` / `SingleVariableLfSensitivityFactor`.
- **Unqualified references need no change:** `AcSensitivityAnalysis`/`DcSensitivityAnalysis` reach the types
  through inheritance today; as same-package top-level classes they still resolve unqualified.
- **`LfSensitivityFactorStore`** already lives as a top-level class in this package — it is the template for
  how the extracted classes should look.

## Suggested execution order (stage + compile between each)

1. Extract the **factor model** (4 files), delete the nested versions, fix imports → `mvn -q test-compile`.
2. Extract the **group model** (5 files) + move `createVariableTypeNotImplementedException` → compile.
3. Extract the **holder** (1 file) → compile.
4. Update the qualified references listed above → compile.
5. Validate: `mvn test -Dtest='AcSensitivityAnalysisTest,AcSensitivityAnalysisContingenciesTest,
   DcSensitivityAnalysisTest,DcSensitivityAnalysisContingenciesTest,AcSvcPilotPointSensitivityTest'`.
   Behaviour is unchanged, so all tests must pass with no edits to assertions.

## Scope notes

- `AcSensitivityAnalysis` (~660 lines) and `DcSensitivityAnalysis` (~770 lines) are large but are mostly one
  big `analyse` / `analyzeContingencySet` flow each — less naturally splittable than the god-class, smaller
  payoff. Leave them for a separate pass if wanted.
- This is a pure structural refactor: no behaviour change, no new tests needed beyond the existing suite.
