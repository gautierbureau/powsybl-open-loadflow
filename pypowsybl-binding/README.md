# pypowsybl binding for the continuation power flow

This directory carries the **pypowsybl** (Python) binding for the continuation power flow / voltage-collapse
analysis implemented in `com.powsybl.openloadflow.ac.continuation`. The binding does not belong in this
repository long-term — it is kept here as a patch so the work is version-controlled alongside the Java engine
until it can be contributed to [powsybl/pypowsybl](https://github.com/powsybl/pypowsybl).

## `continuation-binding.patch`

A feature-only diff (13 files, no build-environment workarounds) that adds, across the pypowsybl binding layers:

- **Java C-API** — `com/powsybl/python/continuation/ContinuationCFunctions.java` (the `@CEntryPoint`s
  `runContinuationPowerFlow`, `getContinuationCurve/Breakpoints/Summary`, `freeContinuationResult`) and
  `ContinuationDataframes.java` (the `DataframeMapper`s for the P-V curve, the breakpoints and the summary).
- **C++** — `runContinuationPowerFlow` + `getContinuation*` in `cpp/powsybl-cpp/powsybl-cpp.{h,cpp}` and the
  `m.def(...)` pybind11 bindings in `cpp/pypowsybl-cpp/bindings.cpp`.
- **Python** — the `pypowsybl/continuation/` package (`run`, `ContinuationParameters`, `ContinuationEngine`,
  `ContinuationResult` exposing `curve` / `breakpoints` / `summary` DataFrames, `max_load_factor`,
  `critical_bus_id`, `pv_curve(bus_id)`), registered in `pypowsybl/__init__.py`, with `tests/test_continuation.py`.
- **Docs** — `docs/reference/continuation.rst` (API reference) and `docs/user_guide/continuation.rst` (narrative
  guide with examples), added to the reference and user-guide toctrees.
- `CONTINUATION_PROTOTYPE.md` — design notes.

Apply it on top of a pypowsybl checkout with:

```bash
cd pypowsybl
git apply /path/to/continuation-binding.patch
```

## Status

Verified working end-to-end from Python (Python → pybind11 → C++ → GraalVM native image → Java engine → pandas):

```python
import pypowsybl.network as pn
import pypowsybl.continuation as continuation

r = continuation.run(pn.create_eurostag_tutorial_example1_network())
# r.status == 'NOSE_POINT_REACHED', r.max_load_factor == 0.86039, r.critical_bus_id == 'VLLOAD_0'
# r.curve : pandas DataFrame indexed by (point, bus_id) with load_factor, voltage, dv_dlambda, stable
```

## Build recipe used to verify it

The binding depends on OpenLoadFlow classes directly (a first for the pypowsybl C layer, since continuation is
OpenLoadFlow-specific rather than a powsybl-core SPI). It builds against a powsybl-core aligned with the
OpenLoadFlow that contains the continuation package:

1. Install this OpenLoadFlow to the local Maven repo: `./mvnw -DskipTests install` (publishes `2.3.0-SNAPSHOT`).
2. Use the pypowsybl `ci/core-7.3.0-SNAPSHOT` branch (its code targets powsybl-core 7.3.0). Since its
   `powsybl-dependencies:2026.1.0-SNAPSHOT` BOM is an internal snapshot, synthesize a local one from the released
   `2026.0.1` BOM with `powsybl-core -> 7.3.0-RC2` and `powsybl-open-loadflow -> 2.3.0-SNAPSHOT`, and install it.
3. Apply this patch, then build with GraalVM `native-image` (Oracle GraalVM for JDK 21) via the usual
   `python setup.py develop` (or the CMake targets).

Notes:
- The orthogonal RAO module needed a newer open-rao than is released; it was back-ported to `open-rao 7.3.0`
  only to make the whole native image build. That change is not part of this patch.
- Once the continuation work is released in an OpenLoadFlow that pypowsybl's BOM references, none of these
  version workarounds are needed — the patch applies cleanly and builds against released artifacts.
