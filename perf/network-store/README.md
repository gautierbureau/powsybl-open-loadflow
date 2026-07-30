# Benchmarking OLF against a local powsybl-network-store

How to stand up a PostgreSQL + powsybl-network-store server, load a Pégase 13k network into it, and
run an Open Load Flow security analysis against it single- and multi-threaded.

This exists because OLF behaves differently on network-store than on `powsybl-iidm-impl`: every IIDM
read can be a REST call, cloning a variant is a server-side copy, and **network-store does not
support IIDM variant multi-thread access at all** — `VariantManagerImpl.allowVariantMultiThreadAccess(true)`
throws `PowsyblException("Network store implementation does not support multi-thread access yet")`.
Multi-threaded analyses therefore have to be validated against a real store, not just against
`iidm-impl`.

Everything below was run end to end on a 4-core / 15 GB Linux box with no Docker daemon, so
PostgreSQL and the server run natively.

## 1. Prerequisites

- JDK 21, Maven 3.9+
- PostgreSQL 16 (`postgresql` package; only the server and `psql` are needed)
- Python 3 with `scipy` + `numpy`, **only** if you want to convert a MATPOWER `.m` case
  (`pip install scipy numpy`)

## 2. Build the network-store client and server

The two live in separate repositories. Build the client first: the server depends on it.

```bash
git clone --depth 1 https://github.com/powsybl/powsybl-network-store.git
cd powsybl-network-store
mvn -q install -DskipTests -Dcheckstyle.skip -Dmaven.javadoc.skip=true -Djacoco.skip=true

cd ..
git clone --depth 1 https://github.com/powsybl/powsybl-network-store-server.git
cd powsybl-network-store-server
mvn -q install -DskipTests -Dcheckstyle.skip -Dmaven.javadoc.skip=true -Djacoco.skip=true \
    -pl network-store-server -am
```

This produces `network-store-server/target/powsybl-network-store-server-*-exec.jar`.

**Check the powsybl-core versions line up.** The server prints its versions at startup. It must be
the same `powsybl-core` as the `<powsybl-core.version>` in OLF's root `pom.xml`, otherwise the
benchmark will fail in confusing ways. At the time of writing both are `7.3.0` (network-store pins
it through `powsybl-dependencies`).

Note the server resolves its own network-store version through `powsybl-ws-dependencies`, which may
be a *released* version rather than the SNAPSHOT you just built. Use the version the server prints
for the client dependency in `pom.xml` (see step 5).

## 3. Start PostgreSQL and the server

```bash
./setup-db.sh
SERVER_JAR=/path/to/powsybl-network-store-server-*-exec.jar ./start-server.sh
```

`setup-db.sh` creates the `iidm` database with `postgres`/`postgres`, which is what the server's
`local` Spring profile expects. `start-server.sh` runs it detached (`setsid`, so it survives the
calling shell) and waits for `/actuator/health`.

Two operational notes learned the hard way:

- **Memory.** The server, the benchmark JVM and PostgreSQL share the box. With default heaps the
  OOM killer takes the server (and sometimes PostgreSQL) mid-import, with *no* stack trace in the
  log — the process just disappears and the client reports `ClosedChannelException`. Cap both:
  `SERVER_XMX=4g`, `BENCH_XMX=6g` on a 15 GB box.
- **Restarts.** If the server fails at startup with `Connection to localhost:5432 refused`,
  PostgreSQL is down — re-run `setup-db.sh`.

## 4. Get a Pégase 13k network

Two variants, and they exercise different code paths. Use both.

### Bus/breaker, from MATPOWER

`case13659pegase` is a standard MATPOWER case: 13,659 buses, 4,092 generators, 20,467 branches. It
has **no switches**, so an OLF security analysis on it never retains a switch and therefore never
clones an IIDM variant.

powsybl's MATPOWER importer reads the binary `.mat` format, while MATPOWER distributes `.m`
scripts, so convert first:

```bash
curl -sSL -o case13659pegase.m \
    https://raw.githubusercontent.com/MATPOWER/matpower/master/data/case13659pegase.m
python3 tools/matpower_m_to_mat.py case13659pegase.m case13659pegase.mat
```

The converter writes a MAT v5 file with the `mpc` struct (`version`, `baseMVA`, `bus`, `gen`,
`branch`) that `MatpowerReader` expects.

### Node-breaker, from a fixture

A full node-breaker Pégase 13k — one busbar section per configured bus, every feeder on a
disconnector + breaker bay, limits and tap changers preserved — is the case that actually exercises
the retained-switch path. Such a fixture is much larger: 73,112 bus/breaker buses, 13,666 busbar
sections, **118,892 switches**, 10,545 voltage levels.

Two things have to be dropped for the network store to accept it:

| dropped | why |
|---|---|
| `OverloadManagementSystem` | no adder in the network-store IIDM implementation — the copy fails with `NullPointerException: ... "adder" is null` |
| `referenceTerminals` extension | `ExtensionAdderProvider not found for ExtensionAdder ReferenceTerminalsAdder for implementation NetworkStore` |

`NsBench` does both automatically on import and prints what it dropped. Neither affects a default
security analysis: `referenceTerminals` is a load flow *output* extension, and overload management
systems are only simulated when OLF's `simulateAutomationSystems` parameter is on (off by default).

Such a fixture may also not converge from a flat start — `NsBench` runs with
`voltageInitMode = DC_VALUES`.

## 5. Build and run the benchmark

`pom.xml` depends on `powsybl-open-loadflow:2.4.0-SNAPSHOT` from the local `.m2`, plus its
**test-jar** (for the OLF network factories) and `powsybl-network-store-client`. Set the client
version to the one the server printed at startup.

```bash
mvn -q package
```

Then install the OLF build you want to measure and import a network:

```bash
(cd /path/to/powsybl-open-loadflow && mvn -q install -DskipTests -Dcheckstyle.skip)

./run.sh import http://localhost:8080/ case13659pegase.mat
# -> UUID=633478d4-...
```

`import` also accepts `ieee118`, `ieee300`, `nodebreaker` (built-in factories) or any file
`Network.read` can open, including `.xiidm.gz`.

Then run:

```
./run.sh run <baseUri> <uuid> <preloading> <threads,csv> <repeats> <contingencies|all> [busContingencies] [propagation]
```

- `preloading` — `NONE`, `COLLECTION` or `ALL_COLLECTIONS_NEEDED_FOR_BUS_VIEW`. **This dominates
  the results.** At `COLLECTION` the client caches a whole collection on first touch, so worker-side
  IIDM reads are cache hits; at `NONE` every read is a REST call.
- `busContingencies` — how many of the contingencies are busbar-section (node-breaker) or bus
  (bus/breaker) contingencies rather than branch contingencies.
- `propagation` — OLF's `contingencyPropagation`. See below: it decides whether the variant clone
  happens at all.

Each run creates a fresh `NetworkStoreService` and reloads the network, so the client cache is cold,
matching a process that loads the network once per analysis.

```bash
./run.sh run http://localhost:8080/ <uuid> COLLECTION 1,4 3 200 20 true
```

## 6. Reaching the IIDM variant clone

OLF clones the IIDM working variant in `Networks.loadWithReconnectableElements`, and only when
`LfTopoConfig.isBreaker()` — i.e. when there are switches to open/close or buses to lose. Getting
there needs the right combination, which is easy to miss:

| network | contingencies | propagation | variant clone? |
|---|---|---|---|
| bus/breaker | branch | any | no |
| bus/breaker | bus | any | yes (`busIdsToLose`) |
| node-breaker | branch | on | no |
| node-breaker | busbar section | **on** | no — the tripping traverses *past* the switches to the equipment terminals |
| node-breaker | busbar section | **off** | yes — `createBusbarSectionMinimalTripping` retains the surrounding switches |

So on a node-breaker network, use busbar-section contingencies **with propagation off**:

```bash
./run.sh run http://localhost:8080/ <uuid> COLLECTION 1,4 1 100 20 false
```

which logs, once per analysis:

```
LF networks built with reconnectable elements in 18194 ms (IIDM variant clone 3519 ms, switch retaining 549 ms)
```

> This log line does not exist on `main`. It is added by the `claude/lfnetwork-clone-mt-perf-vl7zo1`
> branch (`Networks.loadWithReconnectableElements`). On a build without it, the clone is invisible,
> folded into the `networks build` figure of the multi-threaded setup log — which is exactly why it
> was added. Everything else in this pipeline works against any OLF build.

## 7. Measured results

4 cores (so ~3 effective worker threads), 100–200 contingencies, `COLLECTION` preloading, AC SA.
Wall-clock medians; single runs on this box vary by ±15%, so repeat at least 3 times before
concluding anything.

**Variant clone count** — the question this pipeline was built to settle:

| network | threads | `LF networks built ...` log lines |
|---|--:|--:|
| bus/breaker 13k, bus contingencies | 1 | 1 |
| bus/breaker 13k, bus contingencies | 4 | 1 |
| bus/breaker 13k, bus contingencies | 8 | 1 |
| node-breaker 13k, busbar contingencies, propagation off | 1 | 1 |
| node-breaker 13k, busbar contingencies, propagation off | 4 | 1 |

One clone per analysis, on the calling thread, whatever the thread count: the multi-threaded
analysis builds the LF networks once and deep-copies them, it does not clone one IIDM variant per
thread. The clone is not cheap on network-store though — 1.7–3.5 s on the node-breaker 13k, a
server-side copy — but it is the same single cost single- and multi-threaded.

**Wall clock**, node-breaker 13k, 100 contingencies, busbar contingencies, propagation off:

| threads | SA | of which LF network build | run phase |
|--:|--:|--:|--:|
| 1 | 64.0 s | 18.2 s | — |
| 4 | 44.5 s | 14.7 s | 23.8 s |

**Preloading matters more than anything else.** At `COLLECTION`, worker-side IIDM reads are served
from the client cache, so changes that remove those reads are not measurable — two OLF branches that
differ exactly in that respect came out within noise of each other (MT medians 35.7 s vs 36.2 s over
3 repeats). Benchmark at `NONE` if you want to see the cost of worker-side IIDM access.

## 8. Gotchas

- **`Several NetworkFactoryService implementations found ([NetworkStore, Default])`** — with the
  network-store client on the classpath, `Network.read` cannot pick a factory. Select the in-memory
  one in `~/.itools/config.yml`:
  ```yaml
  network:
    default-impl-name: Default
  ```
  The benchmark passes `service.getNetworkFactory()` explicitly when it wants the store.
- **`logback.xml` appender silently not attached** (logback 1.5.x here: `Appender named [STDOUT]
  could not be found` even though it was just processed) — the harness uses `slf4j-simple` and
  `-Dorg.slf4j.simpleLogger.log.<logger>=info` instead.
- **A multi-threaded SA on OLF `main` fails immediately** on network-store with
  `Network store implementation does not support multi-thread access yet`. That is the
  `allowVariantMultiThreadAccess(true)` call in the legacy one-network-build-per-thread mode, not a
  configuration problem.
