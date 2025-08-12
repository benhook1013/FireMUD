#!/usr/bin/env bash
# Restore a FireMUD Kubernetes cluster from a Velero backup and restart services.
#
# This helper is intended for production emergencies. It automates the
# Velero restore workflow so operators can quickly rehydrate volumes,
# restore resources, and restart pods with a single command.
set -euo pipefail

BACKUP_NAME=${1:?"Usage: restore-cluster.sh <backup-name>"}
NAMESPACE=${FIREMUD_K8S_NAMESPACE:-firemud}

# Restore the backup and wait for completion
velero restore create --from-backup "$BACKUP_NAME" --wait

# Restart all deployments and statefulsets to ensure they pick up restored volumes
kubectl rollout restart deployment -n "$NAMESPACE"
kubectl rollout restart statefulset -n "$NAMESPACE"

echo "Cluster restored from $BACKUP_NAME and services restarted"
