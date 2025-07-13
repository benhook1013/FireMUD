#!/usr/bin/env bash
# Restores a PostgreSQL backup created by backup-db.sh.
set -euo pipefail

FILE=${1:?"Usage: restore-db.sh <backup-file>"}

pg_restore -h "${FIREMUD_POSTGRES_HOST:-localhost}" \
           -U "${FIREMUD_POSTGRES_USER:-firemud}" \
           -d "${FIREMUD_POSTGRES_DB:-firemud}" \
           --clean "$FILE"

echo "Database restored from $FILE"
