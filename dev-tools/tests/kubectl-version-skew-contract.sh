#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
mkdir -p "$tmp/bin"
cat > "$tmp/bin/kubectl" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
if [[ "${1:-}" != version || "${2:-}" != --output=json ]]; then
  echo "unexpected kubectl invocation" >&2
  exit 1
fi
printf '%s\n' "${KUBECTL_VERSION_JSON:?}"
EOF
chmod 0755 "$tmp/bin/kubectl"

run_case() {
  local name="$1"
  local expected="$2"
  local json="$3"
  local output
  if output="$(PATH="$tmp/bin:$PATH" KUBECTL_VERSION_JSON="$json" bash "$ROOT_DIR/dev-tools/hosted/shared/check-kubectl-version-skew.sh" 2>&1)"; then
    if [[ "$expected" != pass ]]; then
      echo "$name unexpectedly passed: $output" >&2
      exit 1
    fi
  elif [[ "$expected" = pass ]]; then
    echo "$name unexpectedly failed: $output" >&2
    exit 1
  fi
}

run_case same pass '{"clientVersion":{"gitVersion":"v1.34.5"},"serverVersion":{"gitVersion":"v1.34.5+k3s1"}}'
run_case client-newer pass '{"clientVersion":{"gitVersion":"v1.35.8"},"serverVersion":{"gitVersion":"v1.34.5+k3s1"}}'
run_case client-older pass '{"clientVersion":{"gitVersion":"v1.33.9"},"serverVersion":{"gitVersion":"v1.34.5+k3s1"}}'
run_case too-new fail '{"clientVersion":{"gitVersion":"v1.36.0"},"serverVersion":{"gitVersion":"v1.34.5+k3s1"}}'
run_case too-old fail '{"clientVersion":{"gitVersion":"v1.32.0"},"serverVersion":{"gitVersion":"v1.34.5+k3s1"}}'
run_case missing-server fail '{"clientVersion":{"gitVersion":"v1.34.5"}}'

for workflow in preview.yml dev-demo.yml preview-reconciler.yml dev-demo-reconciler.yml preview-janitor.yml; do
  if ! grep -Fq 'dev-tools/hosted/shared/check-kubectl-version-skew.sh' "$ROOT_DIR/.github/workflows/$workflow"; then
    echo "$workflow must invoke the shared kubectl version skew preflight" >&2
    exit 1
  fi
done

echo "kubectl version skew contract passed"
