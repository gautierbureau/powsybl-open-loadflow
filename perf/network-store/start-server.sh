#!/bin/bash
# Start the powsybl-network-store server against the local PostgreSQL, detached, and wait until
# it answers. Liquibase creates the schema on the first start.
set -euo pipefail

SERVER_JAR="${SERVER_JAR:?set SERVER_JAR to the powsybl-network-store-server *-exec.jar}"
SERVER_XMX="${SERVER_XMX:-4g}"
LOG_FILE="${LOG_FILE:-/tmp/network-store-server.log}"

# detached from the calling shell's process group so it survives the script
setsid nohup java -Xmx"${SERVER_XMX}" -Dspring.profiles.active=local -jar "${SERVER_JAR}" \
    > "${LOG_FILE}" 2>&1 < /dev/null &

for _ in $(seq 1 60); do
    if curl -sS -m 5 --noproxy '*' http://localhost:8080/actuator/health 2>/dev/null | grep -q '"UP"'; then
        echo "network-store server up on http://localhost:8080/ (log: ${LOG_FILE})"
        curl -sS -m 20 --noproxy '*' http://localhost:8080/v1/networks
        echo
        exit 0
    fi
    sleep 2
done

echo "server did not come up, see ${LOG_FILE}" >&2
tail -40 "${LOG_FILE}" >&2
exit 1
