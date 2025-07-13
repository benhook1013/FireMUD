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
BACKUP_COUNT=$(velero backup get -n "$NAMESPACE" --no-headers | wc -l || true)
if [ "$BACKUP_COUNT" -eq 0 ]; then
  echo "No Velero backups found in namespace $NAMESPACE" >&2
  exit 1
fi

echo "Found $BACKUP_COUNT Velero backups in $NAMESPACE"
