#!/usr/bin/env bash
# Verify that Velero backups exist and the object store is reachable.
# Intended for periodic checks via CI or a Kubernetes CronJob.
set -euo pipefail

# shellcheck disable=SC1090,SC1091 # The helper path is resolved from this script.
source "$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)/pg-dump-s3-selection.shlib"

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

  if ! KEY=$(select_latest_pg_dump_key "$PG_DUMP_BUCKET" "${AWS_ENDPOINT_ARGS[@]}"); then
    echo "Unable to list pg_dump objects in bucket $PG_DUMP_BUCKET" >&2
    exit 1
  fi
  case "$KEY" in
    *.sql.gz) ;;
    *)
      echo "No valid .sql.gz pg_dump files found in bucket $PG_DUMP_BUCKET" >&2
      exit 1
      ;;
  esac
  echo "Latest pg_dump: $KEY"
fi
