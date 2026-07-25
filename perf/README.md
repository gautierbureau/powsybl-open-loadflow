# OLF performance harness (branch-independent)

A small standalone project to produce **before/after** performance numbers for powsybl-open-loadflow perf PRs, on the
Pégase 9k / 13k networks, for load flow, security analysis or sensitivity analysis.

It depends only on the **public** powsybl API, so the *same* benchmark jar measures any OLF build. Since every feature
branch is version `2.4.0-SNAPSHOT`, "before/after" is just: install branch A's OLF → run → install branch B's OLF → run.
Nothing in this project changes between branches.

## Build

```bash
mvn -q -DskipTests package
```

Requires the OLF artifact (`com.powsybl:powsybl-open-loadflow:2.4.0-SNAPSHOT`) installed in the local `.m2` — the
`run-before-after.sh` script installs it for you per ref.

## Run once (against whatever OLF is installed)

```bash
mvn -q dependency:build-classpath -Dmdep.outputFile=cp.txt
CP="target/olf-perf-bench.jar:$(cat cp.txt)"
java -cp "$CP" com.powsybl.bench.Benchmark --network ieee300 --compute SA --dc true --fast-dc true --contingencies all
```

Options: `--network <ieee14|ieee57|ieee118|ieee300 | /path/to/case.xiidm|.mat|UCTE>`, `--compute <LF|SA|SENSI>`,
`--dc <bool>`, `--fast-dc <bool>`, `--threads <n>`, `--contingencies <n|all>`, `--factors <n>`, `--warmup <n>`,
`--measure <n>`. Output is one `RESULT ... min=.. median=.. mean=.. p90=..` line (ms).

The built-in `ieeeNNN` cases are only for validating the harness. For real numbers point `--network` at a Pégase file
(see below).

## Before/after for one PR

```bash
./run-before-after.sh <olf-repo> <base-ref> <pr-ref> -- <benchmark args...>
```

It rebuilds+installs OLF at each ref and prints `before`, `after` and the median delta %.

## Pégase cases

`case9241pegase` and `case13659pegase` are standard MATPOWER cases (the PEGASE project). They are **not** bundled here —
supply them as a file readable by an importer on the classpath (`.xiidm` via iidm-serde, `.mat` via matpower-converter,
or UCTE). Then use e.g. `--network /data/case13659pegase.xiidm`.

## Which computation to benchmark per PR

| PR / topic | networks | command |
|---|---|---|
| #15 fast-DC cheaper violation detection | 9k, 13k (rated) | `--compute SA --dc true --fast-dc true --contingencies 1000` |
| #16 precompute branch limit groups | 9k, 13k (rated) | `--compute SA --dc true --fast-dc true --contingencies 1000` |
| #18 skip per-contingency bus-angle write | 9k, 13k | `--compute SA --dc true --fast-dc true --contingencies 1000` |
| #20 skip branches without limits | 9k, 13k (unrated vs rated) | `--compute SA --dc true --fast-dc true --contingencies 1000` |
| #22 COPY-mode MT (SA + AC sensi) | 9k, 13k | `--compute SA --threads 4 --contingencies 1000` **and** `--compute SENSI --dc false --contingencies 200 --factors 6960` |
| vectorized DC | 9k, 13k | `--compute LF --dc true` **and** `--compute SA --dc true --fast-dc true --contingencies 1000` |

Notes:
- The fast-DC SA PRs (#15/#16/#18/#20) profile *rated* networks, so add operational limits to the pégase branches (an
  active-power limit on every branch) to reproduce the PR figures; on an unrated network #16/#20 are near no-ops.
- `--threads > 1` exercises the multi-thread COPY path for #22.
- Report the `RESULT` line (or the `median: X -> Y (+/-Z%)` summary) in the PR body.

## Methodology notes

- JVM warmup matters: keep `--warmup >= 3` and `--measure >= 10`; report `median` (robust to GC blips) and `min` (best
  case). `p90` flags variance.
- Results are wall-clock on one machine; only before/after deltas on the *same* machine/run are meaningful, not absolute
  ms across machines.
- Load flow runs each iteration on a fresh clone of the initial variant so it always starts cold; SA/sensi restore their
  own state, so repeated runs are fair.
