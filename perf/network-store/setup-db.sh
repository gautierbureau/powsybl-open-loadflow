#!/bin/bash
# Start a local PostgreSQL and create the network-store database.
# Assumes a Debian/Ubuntu postgresql package (pg_ctlcluster). Adjust PG_VERSION if needed.
set -euo pipefail

PG_VERSION="${PG_VERSION:-16}"
DB_NAME="${DB_NAME:-iidm}"
DB_USER="${DB_USER:-postgres}"
DB_PASSWORD="${DB_PASSWORD:-postgres}"

if ! pg_isready -h localhost -p 5432 >/dev/null 2>&1; then
    echo "starting PostgreSQL ${PG_VERSION}"
    pg_ctlcluster "${PG_VERSION}" main start || service postgresql start
    for _ in $(seq 1 30); do
        pg_isready -h localhost -p 5432 >/dev/null 2>&1 && break
        sleep 1
    done
fi
pg_isready -h localhost -p 5432

su postgres -c "psql -c \"ALTER USER ${DB_USER} WITH PASSWORD '${DB_PASSWORD}';\""
su postgres -c "psql -tc \"SELECT 1 FROM pg_database WHERE datname='${DB_NAME}'\"" | grep -q 1 \
    || su postgres -c "createdb ${DB_NAME}"

echo "database ${DB_NAME} ready"
