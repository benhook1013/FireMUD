#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

while IFS='|' read -r workflow gate; do
  [[ -n "$workflow" ]] || continue
  path="$ROOT_DIR/.github/workflows/$workflow"
  if ! grep -Fq "    name: $gate" "$path"; then
    echo "$workflow must always emit the required $gate context" >&2
    exit 1
  fi
  if ! grep -Fq "REQUIRED_GATE_NAME: $gate" "$path"; then
    echo "$workflow must verify a prior successful $gate on metadata-only edits" >&2
    exit 1
  fi
  if ! grep -Fq "Preserve successful required gate on metadata-only edit" "$path"; then
    echo "$workflow must preserve the required $gate context on metadata-only edits" >&2
    exit 1
  fi
  if ! grep -Fq '/check-runs' "$path"; then
    echo "$workflow metadata preservation must inspect exact-head check runs" >&2
    exit 1
  fi
  # shellcheck disable=SC2016 # Assert literal workflow interpolation syntax.
  if ! grep -Fq '.app.slug == \"github-actions\"' "$path" ||
    ! grep -Fq 'contains(\"/actions/runs/${GITHUB_RUN_ID}/\") | not' "$path" ||
    ! grep -Fq 'sort_by(.started_at // .created_at) | last' "$path" ||
    ! grep -Fq 'prior_status' "$path"; then
    echo "$workflow metadata preservation must follow the latest prior GitHub Actions gate result" >&2
    exit 1
  fi
  # shellcheck disable=SC2016 # Assert literal workflow shell syntax.
  if ! grep -Fq 'for attempt in $(seq 1 80)' "$path" ||
    ! grep -Fq 'sleep 15' "$path" ||
    ! grep -Fq 'Timed out waiting for the prior' "$path"; then
    echo "$workflow metadata preservation must wait boundedly for a running prior gate" >&2
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
grep -Fq "(github.base_ref == 'develop' || github.base_ref == 'main')" "$CODEQL_WORKFLOW" || {
  echo "CodeQL gate must run for both protected pull request bases" >&2
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
grep -Fq "name: \${{ github.event_name == 'pull_request' && github.event.action == 'edited' && github.event.changes.base.ref == null && 'PR Metadata Edit (validate-overlays)' || 'validate-overlays' }}" "$OVERLAY_WORKFLOW" || {
  echo "Overlay validation must isolate metadata-only edits from its optional context" >&2
  exit 1
}
echo "PR required-gate context contract checks passed"
