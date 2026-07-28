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
SMOKE_WORKFLOW="$ROOT_DIR/.github/workflows/smoke.yml"

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
require_smoke_pattern() {
  local pattern="$1"
  local message="$2"
  if ! grep -Eq "$pattern" "$SMOKE_WORKFLOW"; then
    echo "$message" >&2
    exit 1
  fi
}

require_smoke_pattern \
  'const[[:space:]]+maxCompletedJobSnapshotRetries[[:space:]]*=[[:space:]]*[1-9][0-9]*;' \
  "Smoke Gate must bound retries for eventually consistent completed-job snapshots"
require_smoke_pattern \
  'const[[:space:]]+matchingRuns[[:space:]]*=[[:space:]]+runs[.]filter[[:space:]]*[(][[:space:]]*[(]run[)]' \
  "Smoke Gate must collect all current matching workflow runs before selecting one"
require_smoke_pattern \
  'const[[:space:]]+matching[[:space:]]*=[[:space:]]+matchingRuns[.]reduce[[:space:]]*[(]' \
  "Smoke Gate must select the newest matching workflow run explicitly"
require_smoke_pattern \
  'let[[:space:]]+completedJobSnapshotRunId[[:space:]]*=[[:space:]]*null;' \
  "Smoke Gate must bind completed-job snapshot retries to a workflow run ID"
require_smoke_pattern \
  'completedJobSnapshotRunId[[:space:]]*!==[[:space:]]*matching[.]id' \
  "Smoke Gate must reset stale snapshot retries when the selected run changes"
require_smoke_pattern \
  'completedJobSnapshotRetries[[:space:]]*\+=[[:space:]]*1;' \
  "Smoke Gate must count stale completed-workflow job snapshots for retry"
require_smoke_pattern \
  'completedJobSnapshotRetries[[:space:]]*<=[[:space:]]*maxCompletedJobSnapshotRetries' \
  "Smoke Gate must bound stale completed-workflow snapshot retries"
require_smoke_pattern \
  'core[.]setFailed[[:space:]]*[(]' \
  "Smoke Gate must fail closed after bounded snapshot retries"

echo "PR required-gate context contract checks passed"
