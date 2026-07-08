#!/usr/bin/env bash
# Setup MinIO and Velero for local FireMUD backups.
# Deploys MinIO, creates the backup bucket, and installs Velero using
# the provided Helm values.
set -euo pipefail

ROOT_DIR="$(git rev-parse --show-toplevel)"

MINIO_MANIFEST="$ROOT_DIR/k8s/velero/minio.yaml"
VALUES_FILE="$ROOT_DIR/k8s/velero/values-minio.yaml"
BUCKET="firemud-backups"
MINIO_ROOT_USER_DEFAULT="firemud-local-minio"
MINIO_ROOT_USER_WAS_SET=false
MINIO_ROOT_PASSWORD_WAS_SET=false

if [[ -n "${MINIO_ROOT_USER:-}" ]]; then
  MINIO_ROOT_USER_WAS_SET=true
fi
if [[ -n "${MINIO_ROOT_PASSWORD:-}" ]]; then
  MINIO_ROOT_PASSWORD_WAS_SET=true
fi
MINIO_ROOT_USER="${MINIO_ROOT_USER:-$MINIO_ROOT_USER_DEFAULT}"

# Ensure the namespace and local MinIO credentials secret exist before
# applying the manifest that references them.
kubectl create namespace minio --dry-run=client -o yaml | kubectl apply -f - >/dev/null

MINIO_SECRET_EXISTS=false
MINIO_DEPLOYMENT_EXISTS=false
EXISTING_ACCESS_KEY=""
EXISTING_SECRET_KEY=""
if kubectl get secret minio-creds -n minio >/dev/null 2>&1; then
  MINIO_SECRET_EXISTS=true
  EXISTING_ACCESS_KEY=$(kubectl get secret minio-creds -n minio -o jsonpath='{.data.accesskey}' | base64 -d)
  EXISTING_SECRET_KEY=$(kubectl get secret minio-creds -n minio -o jsonpath='{.data.secretkey}' | base64 -d)
  if [[ "$MINIO_ROOT_USER_WAS_SET" == false ]]; then
    MINIO_ROOT_USER="$EXISTING_ACCESS_KEY"
  fi
  if [[ "$MINIO_ROOT_PASSWORD_WAS_SET" == false ]]; then
    MINIO_ROOT_PASSWORD="$EXISTING_SECRET_KEY"
    echo "Reusing existing MinIO credentials from secret minio/minio-creds." >&2
  fi
fi

if [[ -z "${MINIO_ROOT_PASSWORD:-}" ]]; then
  if ! command -v openssl >/dev/null 2>&1; then
    echo "MINIO_ROOT_PASSWORD is not set and openssl is unavailable for password generation." >&2
    echo "Set MINIO_ROOT_PASSWORD explicitly and rerun this script." >&2
    exit 1
  fi
  MINIO_ROOT_PASSWORD="$(openssl rand -hex 24)"
  echo "Generated local MINIO_ROOT_PASSWORD for this setup run." >&2
fi

if kubectl get deployment minio -n minio >/dev/null 2>&1; then
  MINIO_DEPLOYMENT_EXISTS=true
fi

MINIO_CREDENTIALS_CHANGED=false
if [[ "$MINIO_SECRET_EXISTS" == true ]] && {
  [[ "$MINIO_ROOT_USER" != "$EXISTING_ACCESS_KEY" ]] ||
    [[ "$MINIO_ROOT_PASSWORD" != "$EXISTING_SECRET_KEY" ]];
}; then
  MINIO_CREDENTIALS_CHANGED=true
fi

kubectl create secret generic minio-creds -n minio \
  --from-literal=accesskey="$MINIO_ROOT_USER" \
  --from-literal=secretkey="$MINIO_ROOT_PASSWORD" \
  --dry-run=client -o yaml | kubectl apply -f - >/dev/null

# Apply MinIO manifest
kubectl apply -f "$MINIO_MANIFEST"

if [[ "$MINIO_DEPLOYMENT_EXISTS" == true && "$MINIO_CREDENTIALS_CHANGED" == true ]]; then
  echo "MinIO credentials changed; restarting deployment/minio to pick up the new secret." >&2
  kubectl rollout restart deployment/minio -n minio >/dev/null
fi

# Wait for MinIO deployment to be ready
kubectl wait --for=condition=available --timeout=120s deployment/minio -n minio

# Read credentials from secret
ACCESS_KEY=$(kubectl get secret minio-creds -n minio -o jsonpath='{.data.accesskey}' | base64 -d)
SECRET_KEY=$(kubectl get secret minio-creds -n minio -o jsonpath='{.data.secretkey}' | base64 -d)

# Configure MinIO client and create bucket
mc alias set local http://minio.minio.svc.cluster.local:9000 "$ACCESS_KEY" "$SECRET_KEY" >/dev/null
mc mb local/$BUCKET || true

# Create or update Velero credentials secret
kubectl create namespace velero --dry-run=client -o yaml | kubectl apply -f - >/dev/null
kubectl create secret generic velero-minio-creds -n velero \
  --from-literal=cloud="[default]\naws_access_key_id=$ACCESS_KEY\naws_secret_access_key=$SECRET_KEY" \
  --dry-run=client -o yaml | kubectl apply -f -

# Install or upgrade Velero
helm repo add vmware-tanzu https://vmware-tanzu.github.io/helm-charts >/dev/null
helm upgrade --install velero vmware-tanzu/velero -n velero --create-namespace -f "$VALUES_FILE"

echo "Local backup setup complete."
