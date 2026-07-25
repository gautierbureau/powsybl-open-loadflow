#!/usr/bin/env bash
# Copyright (c) 2025, RTE (http://www.rte-france.com)
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at http://mozilla.org/MPL/2.0/.
# SPDX-License-Identifier: MPL-2.0
#
# Before/after benchmark for one powsybl-open-loadflow branch.
#
# It rebuilds+installs the OLF artifact for the base ref and the PR ref in turn (both are 2.4.0-SNAPSHOT, so each
# install simply replaces what the fixed benchmark jar resolves), runs the same benchmark against each, and prints a
# before/after comparison. The benchmark itself never changes between refs.
#
# Usage:
#   ./run-before-after.sh <olf-repo> <base-ref> <pr-ref> -- <benchmark args...>
# Example (PR #16, fast DC SA on pegase 13k, all N-1):
#   ./run-before-after.sh ~/powsybl-open-loadflow main pr/fastdc-sa-limit-snapshot -- \
#       --network /data/case13659pegase.xiidm --compute SA --dc true --fast-dc true --contingencies 1000 \
#       --warmup 3 --measure 10
set -euo pipefail

OLF_REPO="$1"; BASE_REF="$2"; PR_REF="$3"; shift 3
[ "$1" = "--" ] && shift
BENCH_ARGS=("$@")

BENCH_DIR="$(cd "$(dirname "$0")" && pwd)"
JAR="$BENCH_DIR/target/olf-perf-bench.jar"
CP_FILE="$BENCH_DIR/cp.txt"

# build the fixed benchmark jar + classpath once
( cd "$BENCH_DIR" && mvn -q -DskipTests package && mvn -q dependency:build-classpath -Dmdep.outputFile=cp.txt >/dev/null )
CP="$JAR:$(cat "$CP_FILE")"

run_ref() {
  local ref="$1" label="$2"
  echo ">>> [$label] building OLF at $ref ..." >&2
  git -C "$OLF_REPO" checkout --quiet "$ref"
  ( cd "$OLF_REPO" && mvn -q -DskipTests -Dcheckstyle.skip=true -Dmaven.javadoc.skip=true -Dmaven.source.skip=true install >/dev/null )
  echo ">>> [$label] running benchmark ..." >&2
  java -cp "$CP" com.powsybl.bench.Benchmark "${BENCH_ARGS[@]}" 2>/dev/null | grep '^RESULT'
}

BEFORE="$(run_ref "$BASE_REF" before)"
AFTER="$(run_ref "$PR_REF" after)"

echo
echo "args: ${BENCH_ARGS[*]}"
echo "before ($BASE_REF): $BEFORE"
echo "after  ($PR_REF): $AFTER"
# median delta
bm="$(sed -E 's/.*median=([0-9.]+)ms.*/\1/' <<<"$BEFORE")"
am="$(sed -E 's/.*median=([0-9.]+)ms.*/\1/' <<<"$AFTER")"
awk -v b="$bm" -v a="$am" 'BEGIN { printf "median: %.1fms -> %.1fms (%+.1f%%)\n", b, a, (a-b)/b*100 }'
