#!/usr/bin/env bash
# Download the most recent pg_dump from object storage and restore the database.
# Requires PG_DUMP_BUCKET; PG_DUMP_ENDPOINT is optional (useful for MinIO or other S3-compatible endpoints).
set -euo pipefail

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

# shellcheck disable=SC2016 # AWS JMESPath requires literal backticks.
AWS_QUERY='Contents[?starts_with(Key, `15min/firemud_`) && ends_with(Key, `.sql.gz`)].[LastModified, Key]'
TAB=$(printf '\t')
if ! LISTING=$(aws s3api list-objects-v2 --bucket "$BUCKET" --prefix "15min/" \
      "${AWS_ENDPOINT_ARGS[@]}" \
      --query "$AWS_QUERY" --output text); then
  echo "Unable to list pg_dump objects in bucket $BUCKET; check AWS credentials, endpoint, and network access" >&2
  exit 1
fi
# The capture timestamp in the canonical key is the artifact ordering
# authority; LastModified is only a deterministic tie-breaker.
KEY=$(printf '%s\n' "$LISTING" |
      LC_ALL=C sort -t "$TAB" -k2,2r -k1,1r |
      awk -F "$TAB" '
        $1 != "None" && NF >= 2 && $2 ~ /^15min\/firemud_[0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9]\.sql\.gz$/ {
          if (first_key == "") {
            first_key = substr($0, index($0, FS) + 1)
          }
        }
        END {
          if (first_key != "") {
            print first_key
          }
        }')

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
