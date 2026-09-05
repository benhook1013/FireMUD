#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
ALLOCATOR="$ROOT_DIR/dev-tools/hosted/preview/allocate-preview-capacity.sh"
TEMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TEMP_DIR"' EXIT

mkdir -p "$TEMP_DIR/bin"
cat > "$TEMP_DIR/bin/kubectl" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
if [[ "$*" == *"get namespaces"* ]]; then
  printf '%b' "${FAKE_NAMESPACE_ROWS:-}"
  exit 0
fi
namespace="${3:-}"
case "$namespace" in
  pr-101)
    case "$*" in
      *pr-number*) printf '%s' "${FAKE_PR_101_OWNER:-101}" ;;
      *creationTimestamp*) printf '2026-01-01T00:00:00Z' ;;
      *preview-allocated-at*) printf '2026-01-02T00:00:00Z' ;;
      *last-preview-head-sha*) printf 'head-101' ;;
      *last-preview-image-tag*) printf 'image-101' ;;
    esac
    ;;
  pr-102)
    case "$*" in
      *pr-number*) printf '102' ;;
      *creationTimestamp*) printf '2026-01-03T00:00:00Z' ;;
      *preview-allocated-at*) printf '2026-01-04T00:00:00Z' ;;
      *last-preview-head-sha*) printf 'head-102' ;;
      *last-preview-image-tag*) printf 'image-102' ;;
    esac
    ;;
  pr-901)
    case "$*" in
      *pr-number*) printf '%s' "${FAKE_PR_901_OWNER:-}" ;;
      *last-preview-head-sha*) printf '%s' "${FAKE_PR_901_HEAD:-}" ;;
    esac
    ;;
  *) exit 1 ;;
esac
EOF

cat > "$TEMP_DIR/bin/gh" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
resource=""
for arg in "$@"; do
  if [[ "$arg" == repos/* ]]; then
    resource="$arg"
    break
  fi
done
case "$resource" in
  */pulls\?state=*)
    if [[ "${FAKE_PRIORITY_QUERY_FAIL:-false}" == "true" ]]; then
      exit 1
    fi
    printf '%b' "${FAKE_OPEN_PRIORITY_ROWS:-}"
    ;;
  */pulls/900)
    count=0
    if [[ -f "$FAKE_TARGET_CALLS" ]]; then
      count="$(<"$FAKE_TARGET_CALLS")"
    fi
    count=$((count + 1))
    printf '%s' "$count" > "$FAKE_TARGET_CALLS"
    priority="${FAKE_TARGET_PRIORITY:-true}"
    if [[ "${FAKE_TARGET_LOSES_PRIORITY:-false}" == "true" && "$count" -gt 1 ]]; then
      priority=false
    fi
    printf 'open\t%s\t%s\n' "$FAKE_TARGET_HEAD" "$priority"
    ;;
  */pulls/101)
    count=0
    if [[ -f "$FAKE_PR_101_CALLS" ]]; then
      count="$(<"$FAKE_PR_101_CALLS")"
    fi
    count=$((count + 1))
    printf '%s' "$count" > "$FAKE_PR_101_CALLS"
    priority="${FAKE_PR_101_PRIORITY:-false}"
    if [[ "${FAKE_PR_101_GAINS_PRIORITY:-false}" == "true" && "$count" -gt 1 ]]; then
      priority=true
    fi
    printf 'open\thead-101\t%s\n' "$priority"
    ;;
  */pulls/102) printf 'open\thead-102\t%s\n' "${FAKE_PR_102_PRIORITY:-true}" ;;
  */issues/comments/*)
    if [[ "$*" == *"--method PATCH"* ]]; then
      printf '%s\n' PATCH >> "$FAKE_COMMENT_METHOD_LOG"
      for arg in "$@"; do
        if [[ "$arg" == body=@* ]]; then
          cp "${arg#body=@}" "$FAKE_COMMENT_BODY"
        fi
      done
    elif [[ -n "${FAKE_PREVIOUS_COMMENT_BODY:-}" ]]; then
      printf '%s\n' "$FAKE_PREVIOUS_COMMENT_BODY"
    fi
    ;;
  */issues/*/comments)
    if [[ "$*" == *"--method POST"* && -n "${FAKE_COMMENT_BODY:-}" ]]; then
      printf '%s\n' POST >> "$FAKE_COMMENT_METHOD_LOG"
      for arg in "$@"; do
        if [[ "$arg" == body=@* ]]; then
          cp "${arg#body=@}" "$FAKE_COMMENT_BODY"
        fi
      done
    elif [[ -n "${FAKE_EXISTING_COMMENT_ID:-}" ]]; then
      printf '%s\n' "$FAKE_EXISTING_COMMENT_ID"
    fi
    ;;
  *) exit 1 ;;
esac
EOF

cat > "$TEMP_DIR/delete" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
printf '%s %s\n' "$1" "$2" >> "$FAKE_DELETE_LOG"
if [[ "${FAKE_DELETE_FAIL:-false}" == "true" ]]; then
  exit 1
fi
EOF

cat > "$TEMP_DIR/publish" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
phase="$7"
printf '%s %s %s\n' "$1" "$2" "$phase" >> "$FAKE_PUBLISH_LOG"
count=0
if [[ -f "$FAKE_PUBLISH_CALLS" ]]; then
  count="$(<"$FAKE_PUBLISH_CALLS")"
fi
count=$((count + 1))
printf '%s' "$count" > "$FAKE_PUBLISH_CALLS"
if [[ "${FAKE_PUBLISH_FAIL_PHASE:-}" == "$phase" ]]; then
  exit 1
fi
if (( count <= ${FAKE_PUBLISH_FAIL_COUNT:-0} )); then
  exit 1
fi
printf '%s\n' "$phase" > "$FAKE_PUBLISHED_STATE"
EOF

cat > "$TEMP_DIR/eligibility-fail.py" <<'EOF'
raise SystemExit(1)
EOF
chmod +x "$TEMP_DIR/bin/kubectl" "$TEMP_DIR/bin/gh" "$TEMP_DIR/delete" "$TEMP_DIR/publish"

export PATH="$TEMP_DIR/bin:$PATH"
export GITHUB_REPOSITORY="example/FireMUD"
export GH_TOKEN="test-token"
export PREVIEW_DELETE_SCRIPT="$TEMP_DIR/delete"
export PREVIEW_RECLAIMED_PUBLISH_SCRIPT="$TEMP_DIR/publish"
export PREVIEW_RECLAIM_PUBLISH_RETRY_DELAY_SECONDS=0
export FAKE_DELETE_LOG="$TEMP_DIR/delete.log"
export FAKE_PUBLISH_LOG="$TEMP_DIR/publish.log"
export FAKE_PUBLISHED_STATE="$TEMP_DIR/published-state"
export FAKE_PUBLISH_CALLS="$TEMP_DIR/publish-calls"
export FAKE_COMMENT_METHOD_LOG="$TEMP_DIR/comment-method.log"
export FAKE_TARGET_CALLS="$TEMP_DIR/target-calls"
export FAKE_PR_101_CALLS="$TEMP_DIR/pr-101-calls"
export FAKE_TARGET_HEAD="head-900"
export FAKE_NAMESPACE_ROWS='2026-01-01T00:00:00Z|pr-101|101|2026-01-02T00:00:00Z|head-101|image-101\n2026-01-03T00:00:00Z|pr-102|102|2026-01-04T00:00:00Z|head-102|image-102\n'

reset_case() {
  rm -f "$FAKE_DELETE_LOG" "$FAKE_PUBLISH_LOG" "$FAKE_PUBLISHED_STATE" "$FAKE_PUBLISH_CALLS" "$FAKE_COMMENT_METHOD_LOG" "$FAKE_TARGET_CALLS" "$FAKE_PR_101_CALLS" "$TEMP_DIR/output"
  export GITHUB_OUTPUT="$TEMP_DIR/output"
  export FAKE_TARGET_PRIORITY=true
  export FAKE_TARGET_LOSES_PRIORITY=false
  export FAKE_PR_101_PRIORITY=false
  export FAKE_PR_101_GAINS_PRIORITY=false
  export FAKE_PR_101_OWNER=101
  export FAKE_PR_102_PRIORITY=true
  export FAKE_OPEN_PRIORITY_ROWS=''
  export FAKE_PRIORITY_QUERY_FAIL=false
  export FAKE_PR_901_OWNER=''
  export FAKE_PR_901_HEAD=''
  export FAKE_DELETE_FAIL=false
  export FAKE_PUBLISH_FAIL_PHASE=''
  export FAKE_PUBLISH_FAIL_COUNT=0
  export FAKE_EXISTING_COMMENT_ID=''
  export FAKE_PREVIOUS_COMMENT_BODY=''
  export PREVIEW_ELIGIBILITY_SCRIPT="$ROOT_DIR/dev-tools/hosted/preview/preview-eligibility.py"
  export FAKE_NAMESPACE_ROWS='2026-01-01T00:00:00Z|pr-101|101|2026-01-02T00:00:00Z|head-101|image-101\n2026-01-03T00:00:00Z|pr-102|102|2026-01-04T00:00:00Z|head-102|image-102\n'
}

reset_case
bash "$ALLOCATOR" pr-900 2 900 "$FAKE_TARGET_HEAD"
grep -qx 'pr-101 pr-101' "$FAKE_DELETE_LOG"
grep -qx '101 900 reclaiming' "$FAKE_PUBLISH_LOG"
grep -qx '101 900 reclaimed' "$FAKE_PUBLISH_LOG"
grep -qx 'reclaimed' "$FAKE_PUBLISHED_STATE"
grep -qx 'reclaimed_pr=101' "$GITHUB_OUTPUT"

export FAKE_COMMENT_BODY="$TEMP_DIR/comment-body"
bash "$ROOT_DIR/dev-tools/hosted/preview/publish-preview-reclaimed.sh" \
  101 900 head-101 image-101 pr-101.preview.firedevops.net \
  '## ✅ Preview Ready' reclaiming
grep -q '<!-- firemud-preview-reclaiming -->' "$FAKE_COMMENT_BODY"
grep -q 'Preview Reclaim In Progress' "$FAKE_COMMENT_BODY"
grep -q 'unavailable for use during guarded reclaim' "$FAKE_COMMENT_BODY"
grep -q '## ✅ Preview Ready' "$FAKE_COMMENT_BODY"
export FAKE_EXISTING_COMMENT_ID=777
bash "$ROOT_DIR/dev-tools/hosted/preview/publish-preview-reclaimed.sh" \
  101 900 head-101 image-101 pr-101.preview.firedevops.net \
  '## ✅ Preview Ready' reclaimed
grep -qx 'POST' "$FAKE_COMMENT_METHOD_LOG"
grep -qx 'PATCH' "$FAKE_COMMENT_METHOD_LOG"
grep -q '<!-- firemud-preview-reclaimed -->' "$FAKE_COMMENT_BODY"
if grep -q '<!-- firemud-preview-reclaiming -->' "$FAKE_COMMENT_BODY"; then
  echo "final reclaimed status retained the in-progress marker" >&2
  exit 1
fi
grep -q 'Reassigned to priority PR: #900' "$FAKE_COMMENT_BODY"
grep -q 'Previous preview result (historical)' "$FAKE_COMMENT_BODY"
grep -q '## ✅ Preview Ready' "$FAKE_COMMENT_BODY"
bash "$ROOT_DIR/dev-tools/hosted/preview/publish-preview-reclaimed.sh" \
  101 900 head-101 image-101 pr-101.preview.firedevops.net \
  '## ✅ Preview Ready' retained
grep -q '<!-- firemud-preview-reclaim-cancelled -->' "$FAKE_COMMENT_BODY"
if grep -q '<!-- firemud-preview-reclaiming -->' "$FAKE_COMMENT_BODY"; then
  echo "retained status remained stuck in reclaiming" >&2
  exit 1
fi
grep -q 'Preview Reclaim Cancelled' "$FAKE_COMMENT_BODY"

reset_case
export FAKE_TARGET_PRIORITY=false
if bash "$ALLOCATOR" pr-900 2 900 "$FAKE_TARGET_HEAD"; then
  echo "ordinary PR unexpectedly reclaimed a full preview pool" >&2
  exit 1
fi
test ! -e "$FAKE_DELETE_LOG"

reset_case
export FAKE_PR_101_PRIORITY=true
if bash "$ALLOCATOR" pr-900 2 900 "$FAKE_TARGET_HEAD"; then
  echo "priority PR unexpectedly reclaimed another priority preview" >&2
  exit 1
fi
test ! -e "$FAKE_DELETE_LOG"

reset_case
export FAKE_TARGET_LOSES_PRIORITY=true
if bash "$ALLOCATOR" pr-900 2 900 "$FAKE_TARGET_HEAD"; then
  echo "reclaim proceeded after the target lost priority" >&2
  exit 1
fi
test ! -e "$FAKE_DELETE_LOG"
grep -qx '101 900 reclaiming' "$FAKE_PUBLISH_LOG"
grep -qx '101 900 retained' "$FAKE_PUBLISH_LOG"
grep -qx 'retained' "$FAKE_PUBLISHED_STATE"

reset_case
export FAKE_PR_101_GAINS_PRIORITY=true
if bash "$ALLOCATOR" pr-900 2 900 "$FAKE_TARGET_HEAD"; then
  echo "reclaim proceeded after the victim gained priority" >&2
  exit 1
fi
test ! -e "$FAKE_DELETE_LOG"
grep -qx '101 900 reclaiming' "$FAKE_PUBLISH_LOG"
grep -qx '101 900 retained' "$FAKE_PUBLISH_LOG"
grep -qx 'retained' "$FAKE_PUBLISHED_STATE"

reset_case
export FAKE_PR_101_OWNER=999
if bash "$ALLOCATOR" pr-900 2 900 "$FAKE_TARGET_HEAD"; then
  echo "reclaim proceeded after victim namespace ownership changed" >&2
  exit 1
fi
test ! -e "$FAKE_DELETE_LOG"
grep -qx 'reclaiming' "$FAKE_PUBLISHED_STATE"

reset_case
export FAKE_NAMESPACE_ROWS='2026-01-01T00:00:00Z|preview-101|101|2026-01-02T00:00:00Z|head-101|image-101\n'
if bash "$ALLOCATOR" pr-900 1 900 "$FAKE_TARGET_HEAD"; then
  echo "reclaim selected a noncanonical preview namespace" >&2
  exit 1
fi
test ! -e "$FAKE_DELETE_LOG"

reset_case
export FAKE_PUBLISH_FAIL_PHASE=reclaiming
if bash "$ALLOCATOR" pr-900 2 900 "$FAKE_TARGET_HEAD"; then
  echo "reclaim proceeded after conservative status publication failed" >&2
  exit 1
fi
test ! -e "$FAKE_DELETE_LOG"
test "$(grep -c '101 900 reclaiming' "$FAKE_PUBLISH_LOG")" -eq 3
test ! -e "$FAKE_PUBLISHED_STATE"

reset_case
export FAKE_DELETE_FAIL=true
if bash "$ALLOCATOR" pr-900 2 900 "$FAKE_TARGET_HEAD"; then
  echo "reclaim proceeded after namespace deletion failed" >&2
  exit 1
fi
grep -qx 'pr-101 pr-101' "$FAKE_DELETE_LOG"
grep -qx '101 900 reclaiming' "$FAKE_PUBLISH_LOG"
if grep -q ' reclaimed$' "$FAKE_PUBLISH_LOG"; then
  echo "delete failure was incorrectly published as reclaimed" >&2
  exit 1
fi
grep -qx 'reclaiming' "$FAKE_PUBLISHED_STATE"

reset_case
export FAKE_PUBLISH_FAIL_PHASE=reclaimed
bash "$ALLOCATOR" pr-900 2 900 "$FAKE_TARGET_HEAD"
grep -qx 'pr-101 pr-101' "$FAKE_DELETE_LOG"
grep -qx '101 900 reclaiming' "$FAKE_PUBLISH_LOG"
test "$(grep -c '101 900 reclaimed' "$FAKE_PUBLISH_LOG")" -eq 3
grep -qx 'reclaimed_pr=101' "$GITHUB_OUTPUT"
grep -qx 'reclaiming' "$FAKE_PUBLISHED_STATE"

reset_case
export FAKE_PUBLISH_FAIL_COUNT=2
bash "$ALLOCATOR" pr-900 2 900 "$FAKE_TARGET_HEAD"
test "$(grep -c '101 900 reclaiming' "$FAKE_PUBLISH_LOG")" -eq 3
grep -qx '101 900 reclaimed' "$FAKE_PUBLISH_LOG"
grep -qx 'reclaimed' "$FAKE_PUBLISHED_STATE"

reset_case
export FAKE_TARGET_PRIORITY=false
export FAKE_OPEN_PRIORITY_ROWS='901\thead-901\texample/FireMUD\thuman\tdevelop\topen\n'
if bash "$ALLOCATOR" pr-900 3 900 "$FAKE_TARGET_HEAD"; then
  echo "ordinary allocation did not yield to an unsatisfied priority PR" >&2
  exit 1
fi
test ! -e "$FAKE_DELETE_LOG"

for ineligible_priority_row in \
  '901\thead-901\tother/FireMUD\thuman\tdevelop\topen\n' \
  '901\thead-901\texample/FireMUD\tdependabot[bot]\tdevelop\topen\n' \
  '901\thead-901\texample/FireMUD\thuman\tfeature/stack\topen\n'
do
  reset_case
  export FAKE_TARGET_PRIORITY=false
  export FAKE_OPEN_PRIORITY_ROWS="$ineligible_priority_row"
  bash "$ALLOCATOR" pr-900 3 900 "$FAKE_TARGET_HEAD"
  test ! -e "$FAKE_DELETE_LOG"
done

reset_case
export FAKE_TARGET_PRIORITY=false
export FAKE_PRIORITY_QUERY_FAIL=true
if bash "$ALLOCATOR" pr-900 3 900 "$FAKE_TARGET_HEAD"; then
  echo "ordinary allocation did not fail closed when priority query failed" >&2
  exit 1
fi

reset_case
export FAKE_TARGET_PRIORITY=false
export FAKE_OPEN_PRIORITY_ROWS='901\thead-901\texample/FireMUD\thuman\tdevelop\topen\n'
export PREVIEW_ELIGIBILITY_SCRIPT="$TEMP_DIR/eligibility-fail.py"
if bash "$ALLOCATOR" pr-900 3 900 "$FAKE_TARGET_HEAD"; then
  echo "ordinary allocation did not fail closed when eligibility evaluation failed" >&2
  exit 1
fi

reset_case
if bash "$ALLOCATOR" pr-900 1 900 "$FAKE_TARGET_HEAD"; then
  echo "priority allocation reclaimed only one slot from an over-capacity pool" >&2
  exit 1
fi
test ! -e "$FAKE_DELETE_LOG"

reset_case
export FAKE_NAMESPACE_ROWS='2026-01-01T00:00:00Z|pr-900|900|2026-01-01T00:00:00Z|head-900|image-900\n2026-01-02T00:00:00Z|pr-101|101|2026-01-02T00:00:00Z|head-101|image-101\n2026-01-03T00:00:00Z|pr-102|102|2026-01-04T00:00:00Z|head-102|image-102\n'
bash "$ALLOCATOR" pr-900 1 900 "$FAKE_TARGET_HEAD"
test ! -e "$FAKE_DELETE_LOG"

preview_workflow="$ROOT_DIR/.github/workflows/preview.yml"
reconciler_workflow="$ROOT_DIR/.github/workflows/preview-reconciler.yml"
janitor_workflow="$ROOT_DIR/.github/workflows/preview-janitor.yml"
grep -q 'github.event.label.name == '\''preview:priority'\''' "$preview_workflow"
test "$(grep -h -c 'group: preview-allocation-lifecycle' "$preview_workflow" "$janitor_workflow" | awk '{ total += $1 } END { print total }')" -eq 3
grep -q 'Skipping ordinary PR #' "$reconciler_workflow"
grep -q 'another preview repair was already dispatched this cycle' "$reconciler_workflow"

echo "preview priority contract checks passed"
