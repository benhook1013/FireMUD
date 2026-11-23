#!/usr/bin/env bash
# Setup MinIO and Velero for local FireMUD backups.
# Deploys MinIO, creates the backup bucket, and installs Velero using
# the provided Helm values.
set -euo pipefail

ROOT_DIR="$(git rev-parse --show-toplevel)"

MINIO_MANIFEST="$ROOT_DIR/k8s/velero/minio.yaml"
VALUES_FILE="$ROOT_DIR/k8s/velero/values-minio.yaml"
VELERO_CHART_VERSION=12.2.5
MINIO_ROOT_USER_DEFAULT="firemud-local-minio"
MINIO_ROOT_USER_WAS_SET=false
MINIO_ROOT_PASSWORD_WAS_SET=false

kube() {
  kubectl --request-timeout=150s "$@"
}

CREDENTIALS_DIR=""
BUCKET_JOB=""
cleanup() {
  local status=$?
  if [[ -n "$CREDENTIALS_DIR" ]]; then
    rm -rf -- "$CREDENTIALS_DIR" || status=1
  fi
  if [[ -n "$BUCKET_JOB" ]]; then
    kube -n minio delete "$BUCKET_JOB" --ignore-not-found --wait=false >/dev/null || status=1
  fi
  exit "$status"
}
trap cleanup EXIT
trap 'exit 129' HUP
trap 'exit 130' INT
trap 'exit 143' TERM

if [[ -n "${MINIO_ROOT_USER:-}" ]]; then
  MINIO_ROOT_USER_WAS_SET=true
fi
if [[ -n "${MINIO_ROOT_PASSWORD:-}" ]]; then
  MINIO_ROOT_PASSWORD_WAS_SET=true
fi
MINIO_ROOT_USER="${MINIO_ROOT_USER:-$MINIO_ROOT_USER_DEFAULT}"

# Ensure the namespace and local MinIO credentials secret exist before
# applying the manifest that references them.
kube create namespace minio --dry-run=client -o yaml | kube apply -f - >/dev/null

MINIO_SECRET_EXISTS=false
MINIO_DEPLOYMENT_EXISTS=false
EXISTING_ACCESS_KEY=""
EXISTING_SECRET_KEY=""
if kube get secret minio-creds -n minio >/dev/null 2>&1; then
  MINIO_SECRET_EXISTS=true
  EXISTING_ACCESS_KEY=$(kube get secret minio-creds -n minio -o jsonpath='{.data.accesskey}' | base64 -d)
  EXISTING_SECRET_KEY=$(kube get secret minio-creds -n minio -o jsonpath='{.data.secretkey}' | base64 -d)
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

if kube get deployment minio -n minio >/dev/null 2>&1; then
  MINIO_DEPLOYMENT_EXISTS=true
fi

MINIO_CREDENTIALS_CHANGED=false
if [[ "$MINIO_SECRET_EXISTS" == true ]] && {
  [[ "$MINIO_ROOT_USER" != "$EXISTING_ACCESS_KEY" ]] ||
    [[ "$MINIO_ROOT_PASSWORD" != "$EXISTING_SECRET_KEY" ]];
}; then
  MINIO_CREDENTIALS_CHANGED=true
fi

# The shared AWS credentials file requires one value per line.
if [[ "$MINIO_ROOT_USER$MINIO_ROOT_PASSWORD" == *[$'\r\n']* ]]; then
  echo "MinIO credentials must not contain line breaks." >&2
  exit 1
fi
umask 077
CREDENTIALS_DIR="$(mktemp -d)"
printf '%s' "$MINIO_ROOT_USER" > "$CREDENTIALS_DIR/accesskey"
printf '%s' "$MINIO_ROOT_PASSWORD" > "$CREDENTIALS_DIR/secretkey"
kube create secret generic minio-creds -n minio \
  --from-file=accesskey="$CREDENTIALS_DIR/accesskey" \
  --from-file=secretkey="$CREDENTIALS_DIR/secretkey" \
  --dry-run=client -o yaml | kube apply -f - >/dev/null

# Apply MinIO manifest
kube apply -f "$MINIO_MANIFEST"

if [[ "$MINIO_DEPLOYMENT_EXISTS" == true && "$MINIO_CREDENTIALS_CHANGED" == true ]]; then
  echo "MinIO credentials changed; restarting deployment/minio to pick up the new secret." >&2
  kube rollout restart deployment/minio -n minio >/dev/null
fi

# Wait for the current rollout, including any credential-triggered restart.
kube rollout status deployment/minio -n minio --timeout=120s

# Resolve the service from inside the cluster. The generated name scopes cleanup
# to this run; the TTL also covers a lost create response or interrupted cleanup.
# Use the immutable client image from docker/docker-compose.yml.
BUCKET_JOB=$(kube -n minio create -f - -o name <<'YAML'
apiVersion: batch/v1
kind: Job
metadata:
  generateName: firemud-local-backup-bucket-
spec:
  backoffLimit: 0
  activeDeadlineSeconds: 90
  ttlSecondsAfterFinished: 300
  template:
    spec:
      automountServiceAccountToken: false
      restartPolicy: Never
      containers:
        - name: create-bucket
          image: ghcr.io/benhook1013/minio-client@sha256:28c57b6c6564fa6b39bb99a68cd61b3494a730b08938c9d97be14c2b6c9f1dcf
          env:
            - name: MINIO_ACCESS_KEY
              valueFrom:
                secretKeyRef:
                  name: minio-creds
                  key: accesskey
            - name: MINIO_SECRET_KEY
              valueFrom:
                secretKeyRef:
                  name: minio-creds
                  key: secretkey
          command: ["sh", "-ec"]
          args:
            - |
              mc alias set local http://minio:9000 "$MINIO_ACCESS_KEY" "$MINIO_SECRET_KEY" >/dev/null 2>&1 || {
                echo "Could not configure the MinIO client." >&2
                exit 1
              }
              mc mb --ignore-existing local/firemud-backups >/dev/null 2>&1 || {
                echo "Could not create the MinIO backup bucket." >&2
                exit 1
              }
YAML
)
if ! kube -n minio wait --for=condition=complete --timeout=120s "$BUCKET_JOB"; then
  echo "MinIO bucket creation did not complete; Velero was not installed or upgraded." >&2
  exit 1
fi

# Create or update Velero credentials secret
kube create namespace velero --dry-run=client -o yaml | kube apply -f - >/dev/null
printf '[default]\naws_access_key_id=%s\naws_secret_access_key=%s\n' \
  "$MINIO_ROOT_USER" "$MINIO_ROOT_PASSWORD" > "$CREDENTIALS_DIR/cloud"
kube create secret generic velero-minio-creds -n velero \
  --from-file=cloud="$CREDENTIALS_DIR/cloud" \
  --dry-run=client -o yaml | kube apply -f - >/dev/null

# Install or upgrade Velero
helm repo add vmware-tanzu https://vmware-tanzu.github.io/helm-charts --force-update >/dev/null
helm upgrade --install velero vmware-tanzu/velero --version "$VELERO_CHART_VERSION" \
  -n velero --create-namespace -f "$VALUES_FILE" --timeout 5m

echo "Local backup setup complete."
