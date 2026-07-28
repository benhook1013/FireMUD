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
  'let[[:space:]]+fullSmokeJob[[:space:]]*=[[:space:]]*null;' \
  "Smoke Gate must retain the selected full-stack job across bounded snapshot retries"
require_smoke_pattern \
  'completedJobSnapshotAttempt[[:space:]]*<=[[:space:]]*maxCompletedJobSnapshotRetries' \
  "Smoke Gate must bound the inner job-snapshot retry loop"
require_smoke_pattern \
  'run_id:[[:space:]]*matching[.]id' \
  "Smoke Gate must retry job snapshots for the already-selected workflow run"
require_smoke_pattern \
  'completedJobSnapshotAttempt[[:space:]]*<[[:space:]]*maxCompletedJobSnapshotRetries' \
  "Smoke Gate must retry only before the bounded final snapshot attempt"
require_smoke_pattern \
  'did not expose a terminal PR Full-Stack Smoke job after' \
  "Smoke Gate must fail closed after bounded snapshot retries"
require_smoke_pattern \
  'Runtime images run \$\{matching[.]id\} succeeded, but PR Full-Stack Smoke job did not complete successfully:' \
  "Smoke Gate must describe the full-stack job failure after the runtime run succeeded"
require_smoke_pattern \
  'Stopping obsolete failed full-smoke gate for' \
  "Smoke Gate must preserve the obsolete-PR-head check before full-stack job failure"

echo "PR required-gate context contract checks passed"
