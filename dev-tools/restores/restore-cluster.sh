#!/usr/bin/env bash
# Restore the namespaced Kubernetes resources from a Velero backup into the
# isolated recovery-drill namespace.
#
# This is deliberately a manifest/resource restore drill. It is not the
# player-facing cold_start_restore workflow and it does not own quarantine,
# coordination reset, credential hardening, recovery-controller continuation,
# or workload restart/reopen.
set -euo pipefail

usage() {
  echo "Usage: restore-cluster.sh <backup-name>" >&2
  exit 1
}

[ $# -eq 1 ] || usage
BACKUP_NAME="$1"
SOURCE_NAMESPACE="${FIREMUD_RESTORE_SOURCE_NAMESPACE:-firemud}"
TARGET_NAMESPACE="${FIREMUD_K8S_NAMESPACE:-restore-test}"
VELERO_NAMESPACE="${FIREMUD_VELERO_NAMESPACE:-}"

# Velero's namespace mapping is intentionally fixed to the one supported
# isolated drill contract. Arbitrary mappings are not safe to infer from a
# workflow-dispatch input and are rejected.
if [[ ! "$BACKUP_NAME" =~ ^[a-z0-9]([-a-z0-9]*[a-z0-9])?$ ]] || ((${#BACKUP_NAME} > 63)); then
  echo "Backup name must be a DNS label (lowercase letters, digits, and hyphens; max 63 characters)." >&2
  exit 1
fi
if [[ "$SOURCE_NAMESPACE" != "firemud" ]]; then
  echo "Restore source namespace is fixed to firemud; refusing arbitrary source namespace." >&2
  exit 1
fi
if [[ "$TARGET_NAMESPACE" != "restore-test" ]]; then
  echo "Restore target namespace is fixed to restore-test; refusing arbitrary target namespace." >&2
  exit 1
fi
if [[ ! "$VELERO_NAMESPACE" =~ ^[a-z0-9]([-a-z0-9]*[a-z0-9])?$ ]] || ((${#VELERO_NAMESPACE} > 63)); then
  echo "FIREMUD_VELERO_NAMESPACE is required and must be a lowercase DNS label of at most 63 characters." >&2
  exit 1
fi

# The target must be provisioned and explicitly labeled by the cluster owner.
# This check prevents this helper from creating or treating an arbitrary
# namespace as an isolated recovery destination.
if [[ "$(kubectl get namespace "$TARGET_NAMESPACE" -o jsonpath='{.metadata.labels.firemud\.io/recovery-drill}' 2>/dev/null)" != "isolated" ]]; then
  echo "Target namespace $TARGET_NAMESPACE must pre-exist with firemud.io/recovery-drill=isolated." >&2
  exit 1
fi

# Restore namespaced resources with the only supported source/target mapping.
velero restore create \
  --namespace "$VELERO_NAMESPACE" \
  --from-backup "$BACKUP_NAME" \
  --include-namespaces "$SOURCE_NAMESPACE" \
  --include-cluster-resources=false \
  --restore-volumes=false \
  --exclude-resources pods,replicationcontrollers,deployments.apps,statefulsets.apps,daemonsets.apps,replicasets.apps,jobs.batch,cronjobs.batch \
  --namespace-mappings "$SOURCE_NAMESPACE:$TARGET_NAMESPACE" \
  --wait

echo "Manifest/resource restore drill completed for $BACKUP_NAME: $SOURCE_NAMESPACE -> $TARGET_NAMESPACE."
echo "No workload restart or player-facing recovery proof was performed."
