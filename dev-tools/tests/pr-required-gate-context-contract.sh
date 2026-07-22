#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

while IFS='|' read -r workflow gate; do
  [[ -n "$workflow" ]] || continue
  path="$ROOT_DIR/.github/workflows/$workflow"
  expected="name: \${{ github.event_name == 'pull_request' && github.event.action == 'edited' && github.event.changes.base.ref == null && 'PR Metadata Edit ($gate)' || '$gate' }}"
  if ! grep -Fq "$expected" "$path"; then
    echo "$workflow must isolate metadata-only edits from the required $gate context" >&2
    exit 1
  fi

  group_line=$(grep -m1 '^  group:' "$path" || true)
  if [[ "$group_line" != *"&& 'metadata' || 'required' }}"* ]]; then
    echo "$workflow concurrency group must separate metadata and required PR runs" >&2
    exit 1
  fi
  if [[ "$group_line" != *"github.event.pull_request.number"* ]]; then
    echo "$workflow concurrency group must remain scoped to the PR number" >&2
    exit 1
  fi
  if ! grep -Fq '  cancel-in-progress: true' "$path"; then
    echo "$workflow must cancel only within its metadata/required concurrency namespace" >&2
    exit 1
  fi
  if grep -Fq "cancel-in-progress: \${{ github.event_name != 'pull_request' || github.event.action != 'edited'" "$path"; then
    echo "$workflow still uses shared-group conditional cancellation that can race required contexts" >&2
    exit 1
  fi
done <<'EOF'
ci.yml|Validation Gate
security.yml|Security Gate
license-scan.yml|License Gate
smoke.yml|Smoke Gate
codeql.yml|CodeQL Gate
validate-kustomize-overlays.yml|validate-overlays
EOF

CODEQL_WORKFLOW="$ROOT_DIR/.github/workflows/codeql.yml"
LICENSE_WORKFLOW="$ROOT_DIR/.github/workflows/license-scan.yml"
OVERLAY_WORKFLOW="$ROOT_DIR/.github/workflows/validate-kustomize-overlays.yml"

grep -Fq 'needs: [changes, analyze]' "$CODEQL_WORKFLOW" || {
  echo "CodeQL gate must depend directly on change detection" >&2
  exit 1
}
grep -Fq 'needs.changes.result' "$CODEQL_WORKFLOW" || {
  echo "CodeQL gate must fail closed when change detection fails" >&2
  exit 1
}
grep -Fq 'needs.changes.result' "$LICENSE_WORKFLOW" || {
  echo "License gate must fail closed when change detection fails" >&2
  exit 1
}
grep -Fq 'types: [opened, synchronize, reopened, edited]' "$OVERLAY_WORKFLOW" || {
  echo "Overlay validation must rerun when a pull request base is edited" >&2
  exit 1
}
grep -Fq "github.actor != 'dependabot[bot]' && (github.event_name != 'pull_request' || github.event.action != 'edited' || github.event.changes.base.ref != null)" "$OVERLAY_WORKFLOW" || {
  echo "Overlay validation must skip metadata-only edits without replacing the required context" >&2
  exit 1
}

echo "PR required-gate context contract checks passed"
