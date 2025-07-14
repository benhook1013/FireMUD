#!/usr/bin/env bash
# Download the most recent pg_dump from object storage and restore the database.
# Requires PG_DUMP_BUCKET and PG_DUMP_ENDPOINT environment variables.
set -euo pipefail

BUCKET=${PG_DUMP_BUCKET:?PG_DUMP_BUCKET must be set}
ENDPOINT=${PG_DUMP_ENDPOINT:?PG_DUMP_ENDPOINT must be set}

command -v aws >/dev/null 2>&1 || {
  echo "aws CLI not found" >&2
  exit 1
}

TMP_DIR=$(mktemp -d)
FILE="$TMP_DIR/latest.sql.gz"

KEY=$(aws s3api list-objects-v2 --bucket "$BUCKET" --prefix "daily/" \
      --endpoint-url "$ENDPOINT" \
      --query 'sort_by(Contents,&LastModified)[-1].Key' --output text)

if [ "$KEY" = "None" ]; then
  echo "No dumps found in bucket $BUCKET" >&2
  exit 1
fi

aws s3 cp "s3://$BUCKET/$KEY" "$FILE" --endpoint-url "$ENDPOINT"

echo "Restoring $KEY"

gunzip -c "$FILE" | psql -h "${FIREMUD_POSTGRES_HOST:-localhost}" \
                    -U "${FIREMUD_POSTGRES_USER:-firemud}" \
                    -d "${FIREMUD_POSTGRES_DB:-firemud}"

echo "Database restored from $KEY"
