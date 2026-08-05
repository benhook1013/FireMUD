#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
CI_WORKFLOW="$ROOT_DIR/.github/workflows/ci.yml"
SECURITY_WORKFLOW="$ROOT_DIR/.github/workflows/security.yml"
PREVIEW_WORKFLOW="$ROOT_DIR/.github/workflows/preview.yml"
ZAP_WORKFLOW="$ROOT_DIR/.github/workflows/zap-baseline.yml"
CLASSIFIER="$ROOT_DIR/.github/scripts/classify-change-scope.cjs"

node --test "$ROOT_DIR/.github/scripts/classify-change-scope.test.cjs"

require_contains() {
  local path="$1"
  local expected="$2"
  if ! grep -Fq -- "$expected" "$path"; then
    echo "$path: missing lightweight-scope contract: $expected" >&2
    exit 1
  fi
}

for expected in \
  'function isDocumentation(file)' \
  'function isValidationPython(file)' \
  'lightweightOnly:'; do
  require_contains "$CLASSIFIER" "$expected"
done

for expected in \
  'lightweight_only: ${{ steps.compute.outputs.lightweight_only }}' \
  'ruff check "${python_files[@]}"' \
  'python3 -m mkdocs build --clean' \
  'needs.changes.outputs.lightweight_only'; do
  require_contains "$CI_WORKFLOW" "$expected"
done

for expected in \
  'Detect Security-Relevant Changes' \
  'needs: [changes, trivy-scan, secret-compliance]' \
  'LIGHTWEIGHT_ONLY: ${{ needs.changes.outputs.lightweight_only }}'; do
  require_contains "$SECURITY_WORKFLOW" "$expected"
done

for expected in \
  "      - 'design/**'" \
  "      - 'dev-tools/docs/**'" \
  "      - 'dev-tools/validation/**/*.py'"; do
  require_contains "$PREVIEW_WORKFLOW" "$expected"
done

for expected in \
  "      - '.github/workflows/zap-baseline.yml'" \
  "      - 'web-client/**'"; do
  require_contains "$ZAP_WORKFLOW" "$expected"
done

echo "CI lightweight scope contract passed"
