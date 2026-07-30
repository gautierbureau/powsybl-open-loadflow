#!/bin/bash
# Run the benchmark against whatever OLF 2.4.0-SNAPSHOT is installed in the local .m2.
# The INFO loggers below are the ones that answer "where did the setup time go":
#   Networks                     -> LF network build, with the IIDM variant clone timed separately
#   ContingencyMultiThreadHelper -> multi-threaded setup / copy / run phases
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
CP="${HERE}/target/olf-network-store-bench-1.0.jar:$(cat "${HERE}/target/cp.txt")"

exec java -Xmx"${BENCH_XMX:-6g}" -Dhttp.nonProxyHosts='localhost|127.0.0.1' \
  -Dorg.slf4j.simpleLogger.defaultLogLevel=warn \
  -Dorg.slf4j.simpleLogger.log.com.powsybl.openloadflow.util.mt=info \
  -Dorg.slf4j.simpleLogger.log.com.powsybl.openloadflow.network.impl.Networks=info \
  -Dorg.slf4j.simpleLogger.showDateTime=true \
  -Dorg.slf4j.simpleLogger.dateTimeFormat="HH:mm:ss.SSS" \
  -cp "$CP" bench.NsBench "$@"
