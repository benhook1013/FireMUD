#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
HELPER="$ROOT_DIR/dev-tools/restores/restore-cluster.sh"
TEMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TEMP_DIR"' EXIT

BIN_DIR="$TEMP_DIR/bin"
KUBECTL_ARGS_FILE="$TEMP_DIR/kubectl-args"
VELERO_ARGS_FILE="$TEMP_DIR/velero-args"
STDOUT_FILE="$TEMP_DIR/stdout"
STDERR_FILE="$TEMP_DIR/stderr"
mkdir -p "$BIN_DIR"
: > "$KUBECTL_ARGS_FILE"
: > "$VELERO_ARGS_FILE"

cat > "$BIN_DIR/kubectl" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail

printf '%s\n' "$@" > "$KUBECTL_ARGS_FILE"
if [[ "$#" -eq 5 && "$1" == "get" && "$2" == "namespace" && "$4" == "-o" ]]; then
  printf '%s\n' "${KUBECTL_LABEL:-isolated}"
  exit 0
fi

echo "unexpected kubectl operation (namespace creation or workload restart may be attempted)" >&2
exit 99
EOF

cat > "$BIN_DIR/velero" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail

printf '%s\n' "$@" > "$VELERO_ARGS_FILE"
EOF

chmod +x "$BIN_DIR/kubectl" "$BIN_DIR/velero"
export PATH="$BIN_DIR:$PATH"
export KUBECTL_ARGS_FILE VELERO_ARGS_FILE

fail() {
  echo "FAIL: $*" >&2
  exit 1
}

assert_rejected_without_restore() {
  local name="$1"
  shift
  : > "$KUBECTL_ARGS_FILE"
  : > "$VELERO_ARGS_FILE"
  if "$@" > "$STDOUT_FILE" 2> "$STDERR_FILE"; then
    fail "$name was accepted"
  fi
  if [[ -s "$VELERO_ARGS_FILE" ]]; then
    fail "$name invoked Velero restore"
  fi
}

assert_rejected_without_restore \
  "invalid backup name" \
  env FIREMUD_VELERO_NAMESPACE=velero "$HELPER" 'Bad_Backup'

assert_rejected_without_restore \
  "arbitrary source namespace" \
  env FIREMUD_RESTORE_SOURCE_NAMESPACE=other FIREMUD_VELERO_NAMESPACE=velero "$HELPER" backup-1

assert_rejected_without_restore \
  "arbitrary target namespace" \
  env FIREMUD_K8S_NAMESPACE=other FIREMUD_VELERO_NAMESPACE=velero "$HELPER" backup-1

assert_rejected_without_restore \
  "missing Velero namespace" \
  env -u FIREMUD_VELERO_NAMESPACE "$HELPER" backup-1

assert_rejected_without_restore \
  "invalid Velero namespace" \
  env FIREMUD_VELERO_NAMESPACE='bad_namespace' "$HELPER" backup-1

assert_rejected_without_restore \
  "unlabelled target namespace" \
  env KUBECTL_LABEL=not-isolated FIREMUD_VELERO_NAMESPACE=velero "$HELPER" backup-1

: > "$KUBECTL_ARGS_FILE"
: > "$VELERO_ARGS_FILE"
env KUBECTL_LABEL=isolated FIREMUD_VELERO_NAMESPACE=velero "$HELPER" backup-1 > "$STDOUT_FILE" 2> "$STDERR_FILE"

expected_kubectl_args=(
  get
  namespace
  restore-test
  -o
  'jsonpath={.metadata.labels.firemud\.io/recovery-drill}'
)
mapfile -t actual_kubectl_args < "$KUBECTL_ARGS_FILE"
if [[ "${actual_kubectl_args[*]}" != "${expected_kubectl_args[*]}" ]]; then
  fail "positive case used an unexpected kubectl operation"
fi

expected_velero_args=(
  restore
  create
  --namespace
  velero
  --from-backup
  backup-1
  --include-namespaces
  firemud
  --include-cluster-resources=false
  --restore-volumes=false
  --exclude-resources
  'pods,replicationcontrollers,deployments.apps,statefulsets.apps,daemonsets.apps,replicasets.apps,jobs.batch,cronjobs.batch'
  --namespace-mappings
  firemud:restore-test
  --wait
)
mapfile -t actual_velero_args < "$VELERO_ARGS_FILE"
if [[ "${actual_velero_args[*]}" != "${expected_velero_args[*]}" ]]; then
  fail "positive case used unexpected Velero arguments"
fi

echo "restore-cluster.sh contract passed"
