#!/usr/bin/env bash
# Creates a PostgreSQL backup using pg_dump.
set -euo pipefail

BACKUP_DIR=${1:-backups}
mkdir -p "$BACKUP_DIR"

FILE="$BACKUP_DIR/firemud_$(date +%Y%m%d%H%M%S).dump"

pg_dump -h "${FIREMUD_POSTGRES_HOST:-localhost}" \
        -U "${FIREMUD_POSTGRES_USER:-firemud}" \
        -d "${FIREMUD_POSTGRES_DB:-firemud}" \
        -Fc -f "$FILE"

echo "Backup saved to $FILE"
