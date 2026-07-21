#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SCRIPT="$ROOT_DIR/dev-tools/validation/lint-yaml.sh"
TEMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TEMP_DIR"' EXIT

BIN_DIR="$TEMP_DIR/bin"
ARGS_FILE="$TEMP_DIR/yamllint-args"
mkdir -p "$BIN_DIR"

cat > "$BIN_DIR/git" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail

if [[ "${1:-}" != "ls-files" ]]; then
  echo "unexpected git invocation: $*" >&2
  exit 1
fi

for pathspec in "${@:2}"; do
  if [[ "$pathspec" == "design/architecture/*.yaml" ]]; then
    printf '%s\n' 'design/architecture/system-architecture-authz-route-matrix.yaml'
    exit 0
  fi
done

exit 0
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
PATH="$BIN_DIR:$PATH" YAMLLINT_ARGS_FILE="$ARGS_FILE" bash "$SCRIPT"

grep -Fqx 'design/architecture/system-architecture-authz-route-matrix.yaml' "$ARGS_FILE"

echo "lint YAML enumeration contract checks passed"
