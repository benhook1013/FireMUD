#!/usr/bin/env bash
# Download the most recent pg_dump from object storage and restore the database.
# Requires PG_DUMP_BUCKET; PG_DUMP_ENDPOINT is optional (useful for MinIO or other S3-compatible endpoints).
set -euo pipefail

# shellcheck disable=SC1090,SC1091 # The helper path is resolved from this script.
source "$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)/../backups/pg-dump-s3-selection.shlib"

BUCKET=${PG_DUMP_BUCKET:?PG_DUMP_BUCKET must be set}
ENDPOINT=${PG_DUMP_ENDPOINT:-}

command -v aws >/dev/null 2>&1 || {
  echo "aws CLI not found" >&2
  exit 1
}

TMP_DIR=$(mktemp -d)
FILE="$TMP_DIR/latest.sql.gz"

AWS_ENDPOINT_ARGS=()
if [ -n "$ENDPOINT" ]; then
  AWS_ENDPOINT_ARGS=(--endpoint-url "$ENDPOINT")
fi

if ! KEY=$(select_latest_pg_dump_key "$BUCKET" "${AWS_ENDPOINT_ARGS[@]}"); then
  echo "Unable to list pg_dump objects in bucket $BUCKET; check AWS credentials, endpoint, and network access" >&2
  exit 1
fi

case "$KEY" in
  *.sql.gz) ;;
  *)
    echo "No valid .sql.gz dumps found in bucket $BUCKET" >&2
    exit 1
    ;;
esac

aws s3 cp "s3://$BUCKET/$KEY" "$FILE" "${AWS_ENDPOINT_ARGS[@]}"

echo "Restoring $KEY"

gunzip -c "$FILE" | psql -v ON_ERROR_STOP=1 --single-transaction \
                    -h "${FIREMUD_POSTGRES_HOST:-localhost}" \
                    -U "${FIREMUD_POSTGRES_USER:-firemud}" \
                    -d "${FIREMUD_POSTGRES_DB:-firemud}"

echo "Database restored from $KEY"
