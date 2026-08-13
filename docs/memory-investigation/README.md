# Network cache memory investigation

Harnesses and findings from an investigation into memory growth of long-lived
scripts running many loadflows with `networkCacheEnabled`.

These are **diagnostic harnesses, not tests**. They are named `*IT` / `*Main` so
that surefire does not pick them up (its default includes are `*Test`, `Test*`,
`*Tests`, `*TestCase`). They are deliberately kept out of the fix PRs and are not
proposed for upstream — they are here so the measurements can be reproduced.

## Harnesses

All Java harnesses live in `src/test/java/com/powsybl/openloadflow/`.

| File | What it measures |
|---|---|
| `HeapVsRssIT` | Java heap (`MemoryMXBean`, after a forced GC) against process RSS (`/proc/self/status`), for one network reused across many runs. Separates a heap leak from a native / off-heap one. |
| `HeapHistogramIT` | Dumps a live-object class histogram (`jcmd GC.class_histogram`) early and late in a long run, so the growing classes can be found by diffing. |
| `NewNetworkCacheIT` | The classical pattern: constant parameters, a fresh network per iteration. Reports `NetworkCache.getEntryCount()` alongside heap and RSS. |
| `ListenerLeakPerfIT` | Per-block wall time when loadflow parameters alternate between runs, which makes the cache entry be evicted and recreated at every run. |
| `LeakMain` | Same scenario as `HeapVsRssIT` but a plain `main`, so JVM flags are fully under control (surefire overrides `argLine`, which makes `-XX:StartFlightRecording` unusable through maven). |
| `ReportGrowthMain` | Counts the report tree of the cached `LfNetwork` across successive cached runs. |

`diff_hist.py` diffs two `jcmd GC.class_histogram` dumps and ranks classes by
retained-bytes growth.

## Running them

Through maven (surefire ignores `argLine` overrides, so no custom JVM flags):

```bash
mvn test -Dtest=HeapVsRssIT -DfailIfNoTests=false -Dolf.it.cache=true -Dolf.it.iterations=4000
mvn test -Dtest=NewNetworkCacheIT -DfailIfNoTests=false -Dolf.it.iterations=600
mvn test -Dtest=HeapHistogramIT -DfailIfNoTests=false -Dolf.it.outDir=/tmp
python3 docs/memory-investigation/diff_hist.py /tmp/hist_early.txt /tmp/hist_late.txt 3500
```

Standalone, when JVM flags matter (bounded heap, flight recorder):

```bash
mvn -q dependency:build-classpath -Dmdep.outputFile=/tmp/cp.txt -Dmdep.includeScope=test
mvn -q test-compile -DskipTests
java -Xmx1G -Duser.language=en -Duser.region=US \
     -XX:StartFlightRecording=settings=profile,filename=/tmp/leak.jfr,dumponexit=true \
     -XX:FlightRecorderOptions=stackdepth=96 \
     -cp "target/classes:target/test-classes:$(cat /tmp/cp.txt)" \
     -Dolf.it.iterations=4000 com.powsybl.openloadflow.LeakMain
jfr print --events OldObjectSample /tmp/leak.jfr
```

## Findings

### Raw RSS is not evidence

The GraalVM native image (pypowsybl) and the JVM both grow the heap
opportunistically when there is no memory pressure, so RSS climbs and then
plateaus. Every conclusion below is based either on heap measured after a forced
GC, or on a run with a bounded heap. A first pass on RSS alone suggested a
massive leak that turned out to be reclaimable.

### 1. A fresh network per run is not a leak (but looks like one)

Constant parameters, a new network each iteration, `networkCacheEnabled=true`:

| | 1500 iterations | peak |
|---|---|---|
| cache on, default heap | 193 → 2512 MB | 2512 MB |
| cache off, default heap | 192 → 521 MB | 521 MB |
| cache on, `-Xmx1G` | plateaus ~1250 MB, then **drops to 911 MB** | 1256 MB |

`NewNetworkCacheIT` shows why: entry count stays at 0 and heap shows no trend
once a GC has run. Entries hold their `LfNetwork` strongly but the IIDM network
only weakly, and `evictDeadEntries()` reclaims them on the next `get()` once the
weak reference is cleared. Without memory pressure that never happens, so
entries pile up and RSS balloons — reclaimable, but alarming.

Two practical points:

* The cache cannot help here at all. `findEntry` matches on network **identity**,
  so a fresh network every iteration never hits: every run pays a full rebuild
  and leaves a heavyweight entry behind. Measured cache-on was both heavier
  (2512 vs 521 MB) and slower (91.4 vs 54.7 s) than cache-off.
* Bounding the heap is not a general answer. It worked here only because
  IEEE300's `LfNetwork` is small; on a real network the retained payload per
  stale entry is the full `LfNetwork`, equation system, Jacobian and LU
  factorization, and a large heap is needed for the network itself.

The underlying weakness — a static list holding heavy payloads strongly, with
eviction deferred to the next `get()` — is a design question for the
maintainers, not addressed by either fix below.

### 2. Leaked IIDM `NetworkListener` (fixed)

`NetworkCache.AbstractEntry` registers itself as a `NetworkListener` in its
constructor but `close()` never deregistered it. Since `LfInput.hasChanged()`
compares the whole parameter set, any parameter change between two runs on the
same network evicts and recreates the entry, leaking one listener each time.

The dominant symptom is CPU: result write-back notifies the whole listener list,
so cost grows linearly with leaked listeners. 2000 runs alternating
`distributedSlack`, `-Xmx1G`: **151.6 s** versus **12.1 s** with constant
parameters, per-250-block times climbing 7.2 → 30.6 s while the control stayed
flat at ~1.5 s.

### 3. Report node accumulation (fixed)

A cached `LfNetwork` kept the `ReportNode` it was built with, so every later run
appended its reports to the first run's tree — misrouting the reports and growing
a tree kept alive by the cache.

`ReportGrowthMain`: direct children went 7, 11, 15, 19, … (+4 per run, scaling
with outer loop iterations). Heap over 4000 cached runs: **1.32 kB/run**, linear,
no plateau; **0.025 kB/run** after the fix.

Found by diffing live-object histograms (`ReportNodeImpl`, `TypedValue`,
`LinkedHashMap` accounted for all the growth) and confirmed with a JFR
`OldObjectSample` recording pointing at `Reports.reportVoltageInitializer` ←
`PreviousValueVoltageInitializer.prepare` ← `AcloadFlowEngine.run`.

Note this happens even when the caller passes `ReportNode.NO_OP`:
`Reports.createRootLfNetworkReportNode` builds a real root unconditionally.
