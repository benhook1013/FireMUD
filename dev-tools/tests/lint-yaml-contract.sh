#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SCRIPT="$ROOT_DIR/dev-tools/validation/lint-yaml.sh"
TEMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TEMP_DIR"' EXIT

BIN_DIR="$TEMP_DIR/bin"
ARGS_FILE="$TEMP_DIR/yamllint-args"
GIT_ARGS_FILE="$TEMP_DIR/git-ls-files-args"
mkdir -p "$BIN_DIR"

cat > "$BIN_DIR/git" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail

if [[ "${1:-}" != "ls-files" ]]; then
  echo "unexpected git invocation: $*" >&2
  exit 1
fi

printf '%s\n' "${@:2}" > "$GIT_LS_FILES_ARGS_FILE"
printf '%s\n' \
  '.github/workflows/ci.yml' \
  'services/account-service/src/main/resources/application.yml' \
  'services/account-service/src/main/resources/openapi.yaml' \
  'design/architecture/system-architecture-authz-route-matrix.yaml' \
  'design/operations/environments/production/expected-bindings.yaml'
EOF

cat > "$BIN_DIR/yamllint" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail

if [[ "${1:-}" != "-c" || -z "${2:-}" ]]; then
  echo "unexpected yamllint invocation: $*" >&2
  exit 1
fi

printf '%s\n' "${@:3}" > "$YAMLLINT_ARGS_FILE"
EOF

chmod +x "$BIN_DIR/git" "$BIN_DIR/yamllint"
PATH="$BIN_DIR:$PATH" \
  GIT_LS_FILES_ARGS_FILE="$GIT_ARGS_FILE" \
  YAMLLINT_ARGS_FILE="$ARGS_FILE" \
  bash "$SCRIPT"

cat > "$TEMP_DIR/expected-yamllint-args" <<'EOF'
.github/workflows/ci.yml
services/account-service/src/main/resources/application.yml
services/account-service/src/main/resources/openapi.yaml
design/architecture/system-architecture-authz-route-matrix.yaml
design/operations/environments/production/expected-bindings.yaml
EOF

cat > "$TEMP_DIR/expected-git-ls-files-args" <<'EOF'
.github/workflows/*.yml
docker/*.yml
services/*/src/main/resources/*.yml
services/*/src/main/resources/*.yaml
services/*/src/test/resources/*.yml
services/*/src/test/resources/*.yaml
design/architecture/*.yml
design/architecture/*.yaml
design/architecture/**/*.yml
design/architecture/**/*.yaml
design/operations/**/*.yml
design/operations/**/*.yaml
k8s/**/*.yml
k8s/**/*.yaml
:!:k8s/base/**
:!:k8s/minio/**
:!:k8s/monitoring/**
:!:k8s/network-policies/**
:!:k8s/postgres/**
:!:k8s/preview/cluster-issuers.yaml
:!:k8s/preview/preview-deployer-rbac.yaml
:!:k8s/velero/minio.yaml
:!:k8s/velero/schedule.yaml
:!:k8s/velero/verify-backups-cronjob.yaml
:!:k8s/helm/**/templates/**
EOF

diff -u "$TEMP_DIR/expected-yamllint-args" "$ARGS_FILE"
diff -u "$TEMP_DIR/expected-git-ls-files-args" "$GIT_ARGS_FILE"

echo "lint YAML enumeration contract checks passed"
