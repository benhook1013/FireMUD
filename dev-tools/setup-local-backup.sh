#!/usr/bin/env bash
# Setup MinIO and Velero for local FireMUD backups.
# Deploys MinIO, creates the backup bucket, and installs Velero using
# the provided Helm values.
set -euo pipefail

ROOT_DIR="$(git rev-parse --show-toplevel)"

MINIO_MANIFEST="$ROOT_DIR/k8s/velero/minio.yaml"
VALUES_FILE="$ROOT_DIR/k8s/velero/values-minio.yaml"
BUCKET="firemud-backups"

# Apply MinIO manifest
kubectl apply -f "$MINIO_MANIFEST"

# Wait for MinIO deployment to be ready
kubectl wait --for=condition=available --timeout=120s deployment/minio -n minio

# Read credentials from secret
ACCESS_KEY=$(kubectl get secret minio-creds -n minio -o jsonpath='{.data.accesskey}' | base64 -d)
SECRET_KEY=$(kubectl get secret minio-creds -n minio -o jsonpath='{.data.secretkey}' | base64 -d)

# Configure MinIO client and create bucket
mc alias set local http://minio.minio.svc.cluster.local:9000 "$ACCESS_KEY" "$SECRET_KEY" >/dev/null
mc mb local/$BUCKET || true

# Create or update Velero credentials secret
kubectl create secret generic velero-minio-creds -n velero \
  --from-literal=cloud="[default]\naws_access_key_id=$ACCESS_KEY\naws_secret_access_key=$SECRET_KEY" \
  --dry-run=client -o yaml | kubectl apply -f -

# Install or upgrade Velero
helm repo add vmware-tanzu https://vmware-tanzu.github.io/helm-charts >/dev/null
helm upgrade --install velero vmware-tanzu/velero -n velero --create-namespace -f "$VALUES_FILE"

echo "Local backup setup complete."
