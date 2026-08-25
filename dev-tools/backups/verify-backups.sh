#!/usr/bin/env bash
# Verify that Velero backups exist and the object store is reachable.
# Intended for periodic checks via CI or a Kubernetes CronJob.
set -euo pipefail

NAMESPACE=${FIREMUD_K8S_NAMESPACE:-firemud}

# Ensure Velero CLI is available
command -v velero >/dev/null 2>&1 || {
  echo "Velero CLI not found" >&2
  exit 1
}

# List backups and ensure at least one exists
if ! BACKUP_LIST=$(velero backup get -n "$NAMESPACE"); then
  echo "Unable to list Velero backups in namespace $NAMESPACE" >&2
  exit 1
fi
BACKUP_COUNT=$(printf '%s\n' "$BACKUP_LIST" | awk 'NR > 1 && NF { count++ } END { print count + 0 }')
if [ "$BACKUP_COUNT" -eq 0 ]; then
  echo "No Velero backups found in namespace $NAMESPACE" >&2
  exit 1
fi

echo "Found $BACKUP_COUNT Velero backups in $NAMESPACE"

if [ -n "${PG_DUMP_BUCKET:-}" ]; then
  command -v aws >/dev/null 2>&1 || {
    echo "aws CLI not found" >&2
    exit 1
  }

  AWS_ENDPOINT_ARGS=()
  if [ -n "${PG_DUMP_ENDPOINT:-}" ]; then
    AWS_ENDPOINT_ARGS=(--endpoint-url "$PG_DUMP_ENDPOINT")
  fi

  # shellcheck disable=SC2016 # AWS JMESPath requires literal backticks.
  AWS_QUERY='Contents[?starts_with(Key, `15min/firemud_`) && ends_with(Key, `.sql.gz`)].[LastModified, Key]'
  TAB=$(printf '\t')
  if ! LISTING=$(aws s3api list-objects-v2 --bucket "$PG_DUMP_BUCKET" \
        --prefix "15min/" \
        "${AWS_ENDPOINT_ARGS[@]}" \
        --query "$AWS_QUERY" \
        --output text); then
    echo "Unable to list pg_dump objects in bucket $PG_DUMP_BUCKET" >&2
    exit 1
  fi
  KEY=$(printf '%s\n' "$LISTING" |
        LC_ALL=C sort -t "$TAB" -k1,1r -k2,2r |
        awk -F "$TAB" '$1 != "None" && NF >= 2 && $2 ~ /^15min\/firemud_[0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9]\.sql\.gz$/ { if (!key) key = substr($0, index($0, FS) + 1) } END { if (key) print key }') || KEY=
  case "$KEY" in
    *.sql.gz) ;;
    *)
      echo "No valid .sql.gz pg_dump files found in bucket $PG_DUMP_BUCKET" >&2
      exit 1
      ;;
  esac
  echo "Latest pg_dump: $KEY"
fi
