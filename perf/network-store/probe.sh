#!/bin/bash
# run.sh plus the network-store REST client logger and thread names: shows which thread issues
# which REST call, so a run can be checked for worker-side IIDM access. Window the output between
# the "copy phase (parallel) completed" and "run phase completed" lines to isolate the parallel
# run phase; there should be no REST call in that window.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
CP="${HERE}/target/olf-network-store-bench-1.0.jar:$(cat "${HERE}/target/cp.txt")"
exec java -Xmx"${BENCH_XMX:-6g}" -Dhttp.nonProxyHosts='localhost|127.0.0.1' \
  -Dorg.slf4j.simpleLogger.defaultLogLevel=warn \
  -Dorg.slf4j.simpleLogger.log.com.powsybl.openloadflow.util.mt=info \
  -Dorg.slf4j.simpleLogger.log.com.powsybl.openloadflow.network.impl.Networks=info \
  -Dorg.slf4j.simpleLogger.log.com.powsybl.network.store.client.RestNetworkStoreClient=info \
  -Dorg.slf4j.simpleLogger.showThreadName=true \
  -Dorg.slf4j.simpleLogger.showDateTime=true \
  -Dorg.slf4j.simpleLogger.dateTimeFormat="HH:mm:ss.SSS" \
  -cp "$CP" bench.NsBench "$@"
