#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
ALLOCATOR="$ROOT_DIR/dev-tools/hosted/preview/allocate-preview-capacity.sh"
PRUNER="$ROOT_DIR/dev-tools/hosted/preview/prune-stale-preview-namespaces.sh"
TEMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TEMP_DIR"' EXIT

extract_workflow_step_run() {
  local workflow="$1"
  local step_name="$2"
  local output="$3"
  local body_type="${4:-run}"
  python3 - "$workflow" "$step_name" "$output" "$body_type" <<'PY'
import sys
from pathlib import Path

import yaml

workflow = yaml.safe_load(Path(sys.argv[1]).read_text(encoding="utf-8"))
for job in workflow["jobs"].values():
    for step in job.get("steps", []):
        if step.get("name") == sys.argv[2]:
            body = (
                step["run"]
                if sys.argv[4] == "run"
                else step.get("with", {}).get("script")
            )
            if not isinstance(body, str):
                raise SystemExit(
                    f"workflow step {sys.argv[2]} has no {sys.argv[4]} body"
                )
            Path(sys.argv[3]).write_text(body, encoding="utf-8")
            raise SystemExit(0)
raise SystemExit(f"workflow step not found: {sys.argv[2]}")
PY
}

mkdir -p "$TEMP_DIR/bin"
cat > "$TEMP_DIR/bin/kubectl" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
if [[ "$*" == *"get namespaces"* ]]; then
  if [[ "${FAKE_NAMESPACE_QUERY_FAIL:-false}" == "true" ]]; then
    exit 1
  fi
  printf '%b' "${FAKE_NAMESPACE_ROWS:-}"
  exit 0
fi
namespace="${3:-}"
case "$namespace" in
  pr-101)
    if [[ "$*" == *"-o json"* ]]; then
      count=0
      if [[ -f "$FAKE_NAMESPACE_JSON_CALLS" ]]; then
        count="$(<"$FAKE_NAMESPACE_JSON_CALLS")"
      fi
      count=$((count + 1))
      printf '%s' "$count" > "$FAKE_NAMESPACE_JSON_CALLS"
      if [[ "${FAKE_NAMESPACE_JSON_QUERY_FAIL:-false}" == "true" ]]; then
        exit 1
      fi
      if [[ "${FAKE_NAMESPACE_JSON_PARSE_FAIL:-false}" == "true" ]]; then
        printf '%s' '{not-json'
        exit 0
      fi
      jq -cn \
        --arg owner "${FAKE_PR_101_OWNER:-101}" \
        --arg image "${FAKE_PR_101_IMAGE:-image-101}" \
        '{metadata:{name:"pr-101",creationTimestamp:"2026-01-01T00:00:00Z",labels:{"firemud.dev/pr-number":$owner},annotations:{"firemud.dev/preview-allocated-at":"2026-01-02T00:00:00Z","firemud.dev/last-preview-head-sha":"head-101","firemud.dev/last-preview-image-tag":$image}}}'
      exit 0
    fi
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
if [[ "${1:-}" == "run" && "${2:-}" == "list" ]]; then
  printf '%s\n' "$*" >> "$FAKE_RECONCILER_RUN_LIST_LOG"
  printf '%s' "${FAKE_ACTIVE_PREVIEW_RUN_ID:-}"
  exit 0
fi
resource=""
has_jq=false
for arg in "$@"; do
  if [[ "$arg" == repos/* ]]; then
    resource="$arg"
  elif [[ "$arg" == "--jq" ]]; then
    has_jq=true
  fi
done
fake_labels_json() {
  local priority="$1"
  local paused="$2"
  local labels_valid="$3"
  local labels_json='[]'
  if [[ "$labels_valid" != valid ]]; then
    labels_json='{}'
  elif [[ "$priority" == true && "$paused" == true ]]; then
    labels_json='[{"name":"preview:priority"},{"name":"preview:paused"}]'
  elif [[ "$priority" == true ]]; then
    labels_json='[{"name":"preview:priority"}]'
  elif [[ "$paused" == true ]]; then
    labels_json='[{"name":"preview:paused"}]'
  fi
  printf '%s' "$labels_json"
}
encode_fake_labels() {
  fake_labels_json "$@" | base64 | tr -d '\n'
}
case "$resource" in
  */pulls\?state=*)
    if [[ "${FAKE_PRIORITY_QUERY_FAIL:-false}" == "true" ]]; then
      exit 1
    fi
    if [[ "${FAKE_RECONCILER_MODE:-false}" == "true" ]]; then
      printf '%b' "${FAKE_RECONCILER_PR_ROWS:-}"
    else
      printf '%b' "${FAKE_OPEN_PRIORITY_ROWS:-}"
    fi
    ;;
  */pulls/900)
    priority="${FAKE_TARGET_PRIORITY:-true}"
    paused="${FAKE_TARGET_PAUSED:-false}"
    labels_valid="${FAKE_TARGET_LABELS_VALID:-valid}"
    if [[ "$has_jq" != true ]]; then
      if [[ "${FAKE_TARGET_RAW_QUERY_FAIL:-false}" == true ]]; then
        exit 1
      fi
      raw_count=0
      if [[ -f "$FAKE_TARGET_RAW_CALLS" ]]; then
        raw_count="$(<"$FAKE_TARGET_RAW_CALLS")"
      fi
      raw_count=$((raw_count + 1))
      printf '%s' "$raw_count" > "$FAKE_TARGET_RAW_CALLS"
      repository="${FAKE_TARGET_REPOSITORY:-example/FireMUD}"
      base="${FAKE_TARGET_BASE_REF:-develop}"
      author="${FAKE_TARGET_AUTHOR:-human}"
      if (( raw_count > 1 )); then
        case "${FAKE_TARGET_CONTRACT_MUTATION:-}" in
          repository) repository=other/FireMUD ;;
          base) base=feature/stack ;;
          author) author='renovate[bot]' ;;
        esac
      fi
      labels_json="$(fake_labels_json "$priority" "$paused" "$labels_valid")"
      jq -cn \
        --arg state "${FAKE_TARGET_STATE:-open}" \
        --arg head "$FAKE_TARGET_HEAD" \
        --arg repository "$repository" \
        --arg base "$base" \
        --arg author "$author" \
        --argjson labels "$labels_json" \
        '{state: $state, head: {sha: $head, repo: {full_name: $repository}}, base: {ref: $base}, user: {login: $author}, labels: $labels}'
      exit 0
    fi
    if [[ -n "${FAKE_TARGET_METADATA:-}" ]]; then
      printf '%b' "$FAKE_TARGET_METADATA"
      exit 0
    fi
    count=0
    if [[ -f "$FAKE_TARGET_CALLS" ]]; then
      count="$(<"$FAKE_TARGET_CALLS")"
    fi
    count=$((count + 1))
    printf '%s' "$count" > "$FAKE_TARGET_CALLS"
    if [[ "${FAKE_TARGET_LOSES_PRIORITY:-false}" == "true" && "$count" -gt 1 ]]; then
      priority=false
    fi
    printf 'open\t%s\t%s\n' "$FAKE_TARGET_HEAD" "$(encode_fake_labels "$priority" "$paused" "$labels_valid")"
    ;;
  */pulls/101)
    if [[ "${FAKE_PRUNE_QUERY_FAIL:-false}" == "true" ]]; then
      exit 1
    fi
    if [[ "${FAKE_PRUNE_JQ_FAIL:-false}" == "true" ]]; then
      jq_query='.'
      previous=''
      for arg in "$@"; do
        if [[ "$previous" == '--jq' ]]; then
          jq_query="$arg"
          break
        fi
        previous="$arg"
      done
      jq -r "$jq_query" <<<'{invalid-json'
      exit 0
    fi
    if [[ -n "${FAKE_PRUNE_METADATA:-}" ]]; then
      printf '%b' "$FAKE_PRUNE_METADATA"
      exit 0
    fi
    count=0
    if [[ -f "$FAKE_PR_101_CALLS" ]]; then
      count="$(<"$FAKE_PR_101_CALLS")"
    fi
    count=$((count + 1))
    printf '%s' "$count" > "$FAKE_PR_101_CALLS"
    priority="${FAKE_PR_101_PRIORITY:-false}"
    paused="${FAKE_PR_101_PAUSED:-false}"
    labels_valid="${FAKE_PR_101_LABELS_VALID:-valid}"
    if [[ "${FAKE_PR_101_GAINS_PRIORITY:-false}" == "true" && "$count" -gt 1 ]]; then
      priority=true
    fi
    printf 'open\thead-101\t%s\n' "$(encode_fake_labels "$priority" "$paused" "$labels_valid")"
  ;;
  */pulls/102) printf 'open\thead-102\t%s\n' "$(encode_fake_labels "${FAKE_PR_102_PRIORITY:-true}" "${FAKE_PR_102_PAUSED:-false}" valid)" ;;
  */actions/workflows/preview.yml/dispatches)
    printf '%s\n' "$*" >> "$FAKE_RECONCILER_DISPATCH_LOG"
    ;;
  */issues/comments/*)
    if [[ "$*" == *"--method DELETE"* ]]; then
      printf 'DELETE %s\n' "${resource##*/}" >> "$FAKE_COMMENT_METHOD_LOG"
      if [[ "${FAKE_COMMENT_DELETE_FAIL:-false}" == "true" ]]; then
        exit 1
      fi
    elif [[ "$*" == *"--method PATCH"* ]]; then
      printf '%s\n' PATCH >> "$FAKE_COMMENT_METHOD_LOG"
      printf '%s\n' "${resource##*/}" > "${FAKE_COMMENT_TARGET_LOG:-/dev/null}"
      for arg in "$@"; do
        if [[ "$arg" == body=@* ]]; then
          cp "${arg#body=@}" "$FAKE_COMMENT_BODY"
        fi
      done
    elif [[ -n "${FAKE_PREVIOUS_COMMENT_BODY:-}" ]]; then
      printf '%s\n' "${resource##*/}" > "${FAKE_PREVIOUS_COMMENT_ID_LOG:-/dev/null}"
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
    elif [[ -n "${FAKE_COMMENT_JSON:-}" ]]; then
      jq_query='.'
      jq_query_previous=''
      for arg in "$@"; do
        if [[ "$jq_query_previous" == '--jq' ]]; then
          jq_query="$arg"
          break
        fi
        jq_query_previous="$arg"
      done
      jq -r "$jq_query" <<<"$FAKE_COMMENT_JSON"
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
if [[ " ${FAKE_PUBLISH_FAIL_PHASES:-} " == *" $phase "* ]]; then
  exit 1
fi
if (( count <= ${FAKE_PUBLISH_FAIL_COUNT:-0} )); then
  exit 1
fi
printf '%s\n' "$phase" > "$FAKE_PUBLISHED_STATE"
EOF

cat > "$TEMP_DIR/revalidate" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\t%s\n' "$1" "$2" >> "$FAKE_REVALIDATE_LOG"
if [[ "${FAKE_REVALIDATE_FAIL:-false}" == true ]]; then
  exit 1
fi
EOF

cat > "$TEMP_DIR/eligibility-fail.py" <<'EOF'
raise SystemExit(1)
EOF
cat > "$TEMP_DIR/eligibility-output.py" <<'EOF'
import os
import sys

sys.stdout.write(os.environ["FAKE_ELIGIBILITY_OUTPUT"])
EOF
chmod +x \
  "$TEMP_DIR/bin/kubectl" \
  "$TEMP_DIR/bin/gh" \
  "$TEMP_DIR/delete" \
  "$TEMP_DIR/publish" \
  "$TEMP_DIR/revalidate"

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
export FAKE_COMMENT_TARGET_LOG="$TEMP_DIR/comment-target.log"
export FAKE_PREVIOUS_COMMENT_ID_LOG="$TEMP_DIR/previous-comment-id.log"
export FAKE_TARGET_CALLS="$TEMP_DIR/target-calls"
export FAKE_TARGET_RAW_CALLS="$TEMP_DIR/target-raw-calls"
export FAKE_PR_101_CALLS="$TEMP_DIR/pr-101-calls"
export FAKE_NAMESPACE_JSON_CALLS="$TEMP_DIR/namespace-json-calls"
export FAKE_RECONCILER_RUN_LIST_LOG="$TEMP_DIR/reconciler-run-list.log"
export FAKE_RECONCILER_DISPATCH_LOG="$TEMP_DIR/reconciler-dispatch.log"
export FAKE_REVALIDATE_LOG="$TEMP_DIR/revalidate.log"
export FAKE_TARGET_HEAD="head-900"
export FAKE_NAMESPACE_ROWS='2026-01-01T00:00:00Z|pr-101|101|2026-01-02T00:00:00Z|head-101|image-101\n2026-01-03T00:00:00Z|pr-102|102|2026-01-04T00:00:00Z|head-102|image-102\n'
priority_labels_base64="$(printf '%s' '[{"name":"preview:priority"}]' | base64 | tr -d '\n')"
paused_labels_base64="$(printf '%s' '[{"name":"preview:priority"},{"name":"preview:paused"}]' | base64 | tr -d '\n')"
adversarial_priority_labels_base64="$(printf '%s' '[{"name":"preview:priority"},{"name":"quote\"slash\\label"}]' | base64 | tr -d '\n')"
adversarial_paused_labels_base64="$(printf '%s' '[{"name":"preview:paused"},{"name":"quote\"slash\\label"}]' | base64 | tr -d '\n')"
invalid_json_labels_base64="$(printf '%s' '{invalid-json' | base64 | tr -d '\n')"

reset_case() {
  rm -f "$FAKE_DELETE_LOG" "$FAKE_PUBLISH_LOG" "$FAKE_PUBLISHED_STATE" "$FAKE_PUBLISH_CALLS" "$FAKE_COMMENT_METHOD_LOG" "$FAKE_COMMENT_TARGET_LOG" "$FAKE_PREVIOUS_COMMENT_ID_LOG" "$FAKE_TARGET_CALLS" "$FAKE_TARGET_RAW_CALLS" "$FAKE_PR_101_CALLS" "$FAKE_NAMESPACE_JSON_CALLS" "$FAKE_RECONCILER_RUN_LIST_LOG" "$FAKE_RECONCILER_DISPATCH_LOG" "$FAKE_REVALIDATE_LOG" "$TEMP_DIR/output"
  export GITHUB_OUTPUT="$TEMP_DIR/output"
  export FAKE_TARGET_PRIORITY=true
  export FAKE_TARGET_PAUSED=false
  export FAKE_TARGET_LABELS_VALID=valid
  export FAKE_TARGET_RAW_QUERY_FAIL=false
  export FAKE_TARGET_CONTRACT_MUTATION=''
  export FAKE_TARGET_METADATA=''
  export FAKE_TARGET_STATE=open
  export FAKE_TARGET_REPOSITORY=example/FireMUD
  export FAKE_TARGET_BASE_REF=develop
  export FAKE_TARGET_AUTHOR=human
  export FAKE_TARGET_LOSES_PRIORITY=false
  export FAKE_REVALIDATE_FAIL=false
  export FAKE_PR_101_PRIORITY=false
  export FAKE_PR_101_PAUSED=false
  export FAKE_PR_101_LABELS_VALID=valid
  export FAKE_PR_101_GAINS_PRIORITY=false
  export FAKE_PR_101_OWNER=101
  export FAKE_PR_101_IMAGE='image-101'
  export FAKE_NAMESPACE_JSON_QUERY_FAIL=false
  export FAKE_NAMESPACE_JSON_PARSE_FAIL=false
  export FAKE_PR_102_PRIORITY=true
  export FAKE_PR_102_PAUSED=false
  export FAKE_OPEN_PRIORITY_ROWS=''
  export FAKE_PRIORITY_QUERY_FAIL=false
  export FAKE_PR_901_OWNER=''
  export FAKE_PR_901_HEAD=''
  export FAKE_DELETE_FAIL=false
  export FAKE_COMMENT_DELETE_FAIL=false
  export FAKE_PUBLISH_FAIL_PHASE=''
  export FAKE_PUBLISH_FAIL_PHASES=''
  export FAKE_PUBLISH_FAIL_COUNT=0
  export FAKE_EXISTING_COMMENT_ID=''
  export FAKE_COMMENT_JSON=''
  export FAKE_PREVIOUS_COMMENT_BODY=''
  export FAKE_PRUNE_METADATA=''
  export FAKE_PRUNE_QUERY_FAIL=false
  export FAKE_PRUNE_JQ_FAIL=false
  export FAKE_ELIGIBILITY_OUTPUT=''
  export FAKE_RECONCILER_MODE=false
  export FAKE_RECONCILER_PR_ROWS=''
  export FAKE_ACTIVE_PREVIEW_RUN_ID=''
  export FAKE_NAMESPACE_QUERY_FAIL=false
  export PREVIEW_ELIGIBILITY_SCRIPT="$ROOT_DIR/dev-tools/hosted/preview/preview-eligibility.py"
  unset PREVIEW_REVALIDATE_DEPLOY_SCRIPT
  export FAKE_NAMESPACE_ROWS='2026-01-01T00:00:00Z|pr-101|101|2026-01-02T00:00:00Z|head-101|image-101\n2026-01-03T00:00:00Z|pr-102|102|2026-01-04T00:00:00Z|head-102|image-102\n'
}

assert_marker_count() {
  local marker="$1"
  local expected="$2"
  local actual

  actual="$(
    awk -v marker="$marker" '
      {
        line = $0
        while ((position = index(line, marker)) > 0) {
          count++
          line = substr(line, position + length(marker))
        }
      }
      END { print count + 0 }
    ' "$FAKE_COMMENT_BODY"
  )"
  if [[ "$actual" -ne "$expected" ]]; then
    echo "expected ${expected} occurrences of ${marker}, found ${actual}" >&2
    exit 1
  fi
}

reset_case
export PREVIEW_REVALIDATE_DEPLOY_SCRIPT="$TEMP_DIR/revalidate"
bash "$ALLOCATOR" pr-900 3 900 "$FAKE_TARGET_HEAD"
grep -qx $'900\thead-900' "$FAKE_REVALIDATE_LOG"
test ! -e "$FAKE_DELETE_LOG"

reset_case
export PREVIEW_REVALIDATE_DEPLOY_SCRIPT="$TEMP_DIR/revalidate"
export FAKE_REVALIDATE_FAIL=true
if bash "$ALLOCATOR" pr-900 2 900 "$FAKE_TARGET_HEAD" 2>"$TEMP_DIR/revalidate-failure.stderr"; then
  echo "capacity allocation continued after deploy revalidation failed" >&2
  exit 1
fi
grep -qx $'900\thead-900' "$FAKE_REVALIDATE_LOG"
grep -q 'target deploy eligibility could not be revalidated' "$TEMP_DIR/revalidate-failure.stderr"
test ! -e "$FAKE_DELETE_LOG"
test ! -e "$FAKE_PUBLISH_LOG"
test ! -e "$GITHUB_OUTPUT"

reset_case
export FAKE_NAMESPACE_QUERY_FAIL=true
if bash "$ALLOCATOR" pr-900 3 900 "$FAKE_TARGET_HEAD" 2>"$TEMP_DIR/namespace-query-failure.stderr"; then
  echo "capacity allocation continued after namespace enumeration failed" >&2
  exit 1
fi
grep -q 'Unable to list current preview namespaces; refusing capacity action' "$TEMP_DIR/namespace-query-failure.stderr"
test ! -e "$FAKE_DELETE_LOG"
test ! -e "$FAKE_PUBLISH_LOG"
test ! -e "$GITHUB_OUTPUT"

reset_case
bash "$ALLOCATOR" pr-900 2 900 "$FAKE_TARGET_HEAD"
grep -qx 'pr-101 pr-101' "$FAKE_DELETE_LOG"
grep -qx '101 900 reclaiming' "$FAKE_PUBLISH_LOG"
grep -qx '101 900 reclaimed' "$FAKE_PUBLISH_LOG"
grep -qx 'reclaimed' "$FAKE_PUBLISHED_STATE"
grep -qx 'reclaimed_pr=101' "$GITHUB_OUTPUT"
test "$(<"$FAKE_NAMESPACE_JSON_CALLS")" -eq 1

export FAKE_COMMENT_BODY="$TEMP_DIR/comment-body"
bash "$ROOT_DIR/dev-tools/hosted/preview/publish-preview-reclaimed.sh" \
  101 900 head-101 image-101 pr-101.preview.firedevops.net \
  $'<!-- firemud-preview-summary -->\n## ✅ Preview Ready' reclaiming
grep -q '<!-- firemud-preview-reclaiming -->' "$FAKE_COMMENT_BODY"
grep -q 'Preview Reclaim In Progress' "$FAKE_COMMENT_BODY"
grep -q 'unavailable for use during guarded reclaim' "$FAKE_COMMENT_BODY"
grep -q '## ✅ Preview Ready' "$FAKE_COMMENT_BODY"
assert_marker_count '<!-- firemud-preview-summary -->' 1
assert_marker_count '<!-- firemud-preview-reclaimed -->' 1
assert_marker_count '<!-- firemud-preview-reclaiming -->' 1
export FAKE_EXISTING_COMMENT_ID=777
bash "$ROOT_DIR/dev-tools/hosted/preview/publish-preview-reclaimed.sh" \
  101 900 head-101 image-101 pr-101.preview.firedevops.net \
  $'<!-- firemud-preview-summary -->\n<!-- firemud-preview-reclaimed -->\n## ⚠️ Preview Slot Reassigned' reclaimed
grep -qx 'POST' "$FAKE_COMMENT_METHOD_LOG"
grep -qx 'PATCH' "$FAKE_COMMENT_METHOD_LOG"
grep -q '<!-- firemud-preview-reclaimed -->' "$FAKE_COMMENT_BODY"
if grep -q '<!-- firemud-preview-reclaiming -->' "$FAKE_COMMENT_BODY"; then
  echo "final reclaimed status retained the in-progress marker" >&2
  exit 1
fi
grep -q 'Reassigned to priority PR: #900' "$FAKE_COMMENT_BODY"
grep -q 'Previous preview result (historical)' "$FAKE_COMMENT_BODY"
grep -q '## ⚠️ Preview Slot Reassigned' "$FAKE_COMMENT_BODY"
assert_marker_count '<!-- firemud-preview-summary -->' 1
assert_marker_count '<!-- firemud-preview-reclaimed -->' 1
bash "$ROOT_DIR/dev-tools/hosted/preview/publish-preview-reclaimed.sh" \
  101 900 head-101 image-101 pr-101.preview.firedevops.net \
  $'<!-- firemud-preview-summary -->\n<!-- firemud-preview-reclaimed -->\n<!-- firemud-preview-reclaiming -->\n## ⚠️ Preview Reclaim In Progress' retained
grep -q '<!-- firemud-preview-reclaim-cancelled -->' "$FAKE_COMMENT_BODY"
if grep -q '<!-- firemud-preview-reclaiming -->' "$FAKE_COMMENT_BODY"; then
  echo "retained status remained stuck in reclaiming" >&2
  exit 1
fi
grep -q 'Preview Reclaim Cancelled' "$FAKE_COMMENT_BODY"
grep -q '## ⚠️ Preview Reclaim In Progress' "$FAKE_COMMENT_BODY"
assert_marker_count '<!-- firemud-preview-summary -->' 1
assert_marker_count '<!-- firemud-preview-reclaim-cancelled -->' 1
assert_marker_count '<!-- firemud-preview-reclaimed -->' 0
assert_marker_count '<!-- firemud-preview-reclaiming -->' 0
bash "$ROOT_DIR/dev-tools/hosted/preview/publish-preview-reclaimed.sh" \
  101 900 head-101 image-101 pr-101.preview.firedevops.net \
  '## ✅ Preview Ready' failure
grep -q '<!-- firemud-preview-reclaim-failed -->' "$FAKE_COMMENT_BODY"
if grep -q '<!-- firemud-preview-reclaiming -->' "$FAKE_COMMENT_BODY"; then
  echo "failure status remained stuck in reclaiming" >&2
  exit 1
fi
grep -q '## ❌ Preview Failed' "$FAKE_COMMENT_BODY"
assert_marker_count '<!-- firemud-preview-summary -->' 1
assert_marker_count '<!-- firemud-preview-reclaim-failed -->' 1

reset_case
export FAKE_COMMENT_BODY="$TEMP_DIR/comment-body"
export FAKE_COMMENT_JSON='[
  {"id":300,"created_at":"2026-01-06T00:00:00Z","updated_at":"2026-01-04T00:00:00Z","user":{"login":"github-actions[bot]"},"body":"<!-- firemud-preview-summary -->\n### Preview Summary\nGenerated duplicate"},
  {"id":200,"created_at":"2026-01-02T00:00:00Z","updated_at":"2026-01-05T00:00:00Z","user":{"login":"github-actions[bot]"},"body":"### Preview Summary\nLegacy canonical"},
  {"id":100,"created_at":"2026-01-01T00:00:00Z","updated_at":"2026-01-05T00:00:00Z","user":{"login":"github-actions[bot]"},"body":"<!-- firemud-preview-summary -->\n### Preview Summary\nGenerated duplicate"},
  {"id":400,"created_at":"2026-01-03T00:00:00Z","user":{"login":"github-actions[bot]"},"body":"### Preview Summary\nLegacy duplicate"},
  {"id":999,"created_at":"2026-01-07T00:00:00Z","updated_at":"2026-01-07T00:00:00Z","user":{"login":"human"},"body":"### Preview Summary\nHuman comment"}
]'
bash "$ROOT_DIR/dev-tools/hosted/preview/publish-preview-reclaimed.sh" \
  101 900 head-101 image-101 pr-101.preview.firedevops.net \
  'previous preview result' reclaimed
test "$(sed -n '1p' "$FAKE_COMMENT_METHOD_LOG")" = PATCH
grep -qx 'DELETE 100' "$FAKE_COMMENT_METHOD_LOG"
grep -qx 'DELETE 300' "$FAKE_COMMENT_METHOD_LOG"
grep -qx 'DELETE 400' "$FAKE_COMMENT_METHOD_LOG"
grep -qx '200' "$FAKE_COMMENT_TARGET_LOG"
if grep -q 'DELETE 200' "$FAKE_COMMENT_METHOD_LOG"; then
  echo "canonical latest preview comment was deleted" >&2
  exit 1
fi

reset_case
export FAKE_COMMENT_BODY="$TEMP_DIR/comment-body"
export FAKE_COMMENT_JSON='[
  {"id":300,"created_at":"2026-01-06T00:00:00Z","updated_at":"2026-01-04T00:00:00Z","user":{"login":"github-actions[bot]"},"body":"<!-- firemud-preview-summary -->\n### Preview Summary\nGenerated duplicate"},
  {"id":200,"created_at":"2026-01-02T00:00:00Z","updated_at":"2026-01-05T00:00:00Z","user":{"login":"github-actions[bot]"},"body":"### Preview Summary\nLegacy canonical"},
  {"id":100,"created_at":"2026-01-01T00:00:00Z","updated_at":"2026-01-05T00:00:00Z","user":{"login":"github-actions[bot]"},"body":"<!-- firemud-preview-summary -->\n### Preview Summary\nGenerated duplicate"},
  {"id":400,"created_at":"2026-01-03T00:00:00Z","user":{"login":"github-actions[bot]"},"body":"### Preview Summary\nLegacy duplicate"}
]'
export FAKE_COMMENT_DELETE_FAIL=true
if ! bash "$ROOT_DIR/dev-tools/hosted/preview/publish-preview-reclaimed.sh" \
  101 900 head-101 image-101 pr-101.preview.firedevops.net \
  'previous preview result' reclaimed 2>"$TEMP_DIR/comment-delete.stderr"; then
  echo "duplicate deletion failure suppressed canonical preview publication" >&2
  exit 1
fi
test "$(sed -n '1p' "$FAKE_COMMENT_METHOD_LOG")" = PATCH
grep -qx '200' "$FAKE_COMMENT_TARGET_LOG"
grep -q '<!-- firemud-preview-reclaimed -->' "$FAKE_COMMENT_BODY"
grep -q 'DELETE 100' "$FAKE_COMMENT_METHOD_LOG"
grep -q 'DELETE 300' "$FAKE_COMMENT_METHOD_LOG"
grep -q 'DELETE 400' "$FAKE_COMMENT_METHOD_LOG"
grep -q 'warning: failed to delete duplicate preview comment 100' "$TEMP_DIR/comment-delete.stderr"

reset_case
export FAKE_COMMENT_JSON='[
  {"id":300,"created_at":"2026-01-06T00:00:00Z","updated_at":"2026-01-04T00:00:00Z","user":{"login":"github-actions[bot]"},"body":"<!-- firemud-preview-summary -->\n### Preview Summary\nGenerated duplicate"},
  {"id":200,"created_at":"2026-01-02T00:00:00Z","updated_at":"2026-01-05T00:00:00Z","user":{"login":"github-actions[bot]"},"body":"### Preview Summary\nLegacy canonical"},
  {"id":100,"created_at":"2026-01-01T00:00:00Z","updated_at":"2026-01-05T00:00:00Z","user":{"login":"github-actions[bot]"},"body":"<!-- firemud-preview-summary -->\n### Preview Summary\nGenerated duplicate"},
  {"id":400,"created_at":"2026-01-03T00:00:00Z","user":{"login":"github-actions[bot]"},"body":"### Preview Summary\nLegacy duplicate"}
]'
export FAKE_PREVIOUS_COMMENT_BODY=$'### Preview Summary\nLegacy canonical'
bash "$ALLOCATOR" pr-900 2 900 "$FAKE_TARGET_HEAD"
grep -qx '200' "$FAKE_PREVIOUS_COMMENT_ID_LOG"

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

for target_contract_mutation in repository base author; do
  reset_case
  export FAKE_TARGET_CONTRACT_MUTATION="$target_contract_mutation"
  if bash "$ALLOCATOR" pr-900 2 900 "$FAKE_TARGET_HEAD" 2>"$TEMP_DIR/target-${target_contract_mutation}-race.stderr"; then
    echo "reclaim proceeded after target ${target_contract_mutation} changed" >&2
    exit 1
  fi
  test ! -e "$FAKE_DELETE_LOG"
  grep -qx '101 900 reclaiming' "$FAKE_PUBLISH_LOG"
  grep -qx '101 900 retained' "$FAKE_PUBLISH_LOG"
  grep -qx 'retained' "$FAKE_PUBLISHED_STATE"
  grep -q 'complete deploy contract could not be revalidated' "$TEMP_DIR/target-${target_contract_mutation}-race.stderr"
  test "$(<"$FAKE_TARGET_RAW_CALLS")" -eq 2
done

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
grep -qx '101 900 failure' "$FAKE_PUBLISH_LOG"
grep -qx 'failure' "$FAKE_PUBLISHED_STATE"

reset_case
export FAKE_NAMESPACE_JSON_QUERY_FAIL=true
if bash "$ALLOCATOR" pr-900 2 900 "$FAKE_TARGET_HEAD"; then
  echo "reclaim proceeded after victim namespace snapshot failed" >&2
  exit 1
fi
test ! -e "$FAKE_DELETE_LOG"
grep -qx '101 900 failure' "$FAKE_PUBLISH_LOG"
grep -qx 'failure' "$FAKE_PUBLISHED_STATE"
test "$(<"$FAKE_NAMESPACE_JSON_CALLS")" -eq 1

reset_case
export FAKE_NAMESPACE_JSON_PARSE_FAIL=true
if bash "$ALLOCATOR" pr-900 2 900 "$FAKE_TARGET_HEAD"; then
  echo "reclaim proceeded after victim namespace snapshot was malformed" >&2
  exit 1
fi
test ! -e "$FAKE_DELETE_LOG"
grep -qx '101 900 failure' "$FAKE_PUBLISH_LOG"
grep -qx 'failure' "$FAKE_PUBLISHED_STATE"
test "$(<"$FAKE_NAMESPACE_JSON_CALLS")" -eq 1

reset_case
export FAKE_PR_101_IMAGE=$'image-101\n'
if bash "$ALLOCATOR" pr-900 2 900 "$FAKE_TARGET_HEAD"; then
  echo "reclaim proceeded after victim image tag contained a trailing newline" >&2
  exit 1
fi
test ! -e "$FAKE_DELETE_LOG"
grep -qx '101 900 failure' "$FAKE_PUBLISH_LOG"
grep -qx 'failure' "$FAKE_PUBLISHED_STATE"
test "$(<"$FAKE_NAMESPACE_JSON_CALLS")" -eq 1

for unsafe_image in \
  'image-101|' \
  $'image-101\t' \
  $'image-101\r' \
  $'image-101\u0001' \
  $'image-101\u007f'
do
  reset_case
  export FAKE_PR_101_IMAGE="$unsafe_image"
  if bash "$ALLOCATOR" pr-900 2 900 "$FAKE_TARGET_HEAD"; then
    echo "reclaim proceeded after victim image tag contained an unsafe character" >&2
    exit 1
  fi
  test ! -e "$FAKE_DELETE_LOG"
  grep -qx '101 900 failure' "$FAKE_PUBLISH_LOG"
  grep -qx 'failure' "$FAKE_PUBLISHED_STATE"
  test "$(<"$FAKE_NAMESPACE_JSON_CALLS")" -eq 1
done

reset_case
export FAKE_TARGET_PRIORITY=false
export FAKE_OPEN_PRIORITY_ROWS="901\thead-901\texample/FireMUD\thuman\tdevelop\topen\t${priority_labels_base64}\n"
export FAKE_NAMESPACE_ROWS='2026-01-01T00:00:00Z|pr-900|900|2026-01-01T00:00:00Z|head-900|image-900\n2026-01-02T00:00:00Z|pr-101|101|2026-01-02T00:00:00Z|head-101|image-101\n'
if bash "$ALLOCATOR" pr-900 2 900 "$FAKE_TARGET_HEAD" 2>"$TEMP_DIR/existing-ordinary-priority.stderr"; then
  echo "existing ordinary preview did not yield to an unsatisfied priority PR" >&2
  exit 1
fi
grep -q 'Yielding ordinary PR #900: priority PR #901 has no current preview' "$TEMP_DIR/existing-ordinary-priority.stderr"
test ! -e "$FAKE_DELETE_LOG"

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
grep -qx '101 900 failure' "$FAKE_PUBLISH_LOG"
if grep -q ' reclaimed$' "$FAKE_PUBLISH_LOG"; then
  echo "delete failure was incorrectly published as reclaimed" >&2
  exit 1
fi
grep -qx 'failure' "$FAKE_PUBLISHED_STATE"

reset_case
export FAKE_DELETE_FAIL=true
export FAKE_PUBLISH_FAIL_PHASES='failure'
if bash "$ALLOCATOR" pr-900 2 900 "$FAKE_TARGET_HEAD"; then
  echo "reclaim reported success after deletion and failure-state compensation failed" >&2
  exit 1
fi
grep -qx 'pr-101 pr-101' "$FAKE_DELETE_LOG"
grep -qx '101 900 reclaiming' "$FAKE_PUBLISH_LOG"
test "$(grep -c '101 900 failure' "$FAKE_PUBLISH_LOG")" -eq 3
if grep -q ' reclaimed$' "$FAKE_PUBLISH_LOG"; then
  echo "delete failure compensation incorrectly published as reclaimed" >&2
  exit 1
fi
grep -qx 'reclaiming' "$FAKE_PUBLISHED_STATE"

reset_case
export FAKE_PUBLISH_FAIL_PHASE=reclaimed
if bash "$ALLOCATOR" pr-900 2 900 "$FAKE_TARGET_HEAD"; then
  echo "reclaim reported success after final status publication failed" >&2
  exit 1
fi
grep -qx 'pr-101 pr-101' "$FAKE_DELETE_LOG"
grep -qx '101 900 reclaiming' "$FAKE_PUBLISH_LOG"
test "$(grep -c '101 900 reclaimed' "$FAKE_PUBLISH_LOG")" -eq 3
grep -qx '101 900 failure' "$FAKE_PUBLISH_LOG"
if grep -qx 'reclaimed_pr=101' "$GITHUB_OUTPUT"; then
  echo "reclaim emitted a reclaimed output after final status publication failed" >&2
  exit 1
fi
grep -qx 'failure' "$FAKE_PUBLISHED_STATE"

reset_case
export FAKE_PUBLISH_FAIL_PHASE=reclaimed
export FAKE_PUBLISH_FAIL_PHASES='failure'
if bash "$ALLOCATOR" pr-900 2 900 "$FAKE_TARGET_HEAD"; then
  echo "reclaim reported success after final status and failure-state repair failed" >&2
  exit 1
fi
grep -qx 'pr-101 pr-101' "$FAKE_DELETE_LOG"
grep -qx '101 900 reclaiming' "$FAKE_PUBLISH_LOG"
test "$(grep -c '101 900 reclaimed' "$FAKE_PUBLISH_LOG")" -eq 3
test "$(grep -c '101 900 failure' "$FAKE_PUBLISH_LOG")" -eq 3
if grep -qx 'reclaimed_pr=101' "$GITHUB_OUTPUT"; then
  echo "reclaim emitted a reclaimed output after failure-state repair failed" >&2
  exit 1
fi
grep -qx 'reclaiming' "$FAKE_PUBLISHED_STATE"

reset_case
export FAKE_PUBLISH_FAIL_COUNT=2
bash "$ALLOCATOR" pr-900 2 900 "$FAKE_TARGET_HEAD"
test "$(grep -c '101 900 reclaiming' "$FAKE_PUBLISH_LOG")" -eq 3
grep -qx '101 900 reclaimed' "$FAKE_PUBLISH_LOG"
grep -qx 'reclaimed' "$FAKE_PUBLISHED_STATE"

reset_case
export FAKE_TARGET_PRIORITY=false
export FAKE_OPEN_PRIORITY_ROWS="901\thead-901\texample/FireMUD\thuman\tdevelop\topen\t${priority_labels_base64}\n"
if bash "$ALLOCATOR" pr-900 3 900 "$FAKE_TARGET_HEAD"; then
  echo "ordinary allocation did not yield to an unsatisfied priority PR" >&2
  exit 1
fi
test ! -e "$FAKE_DELETE_LOG"

for ineligible_priority_row in \
  "901\thead-901\tother/FireMUD\thuman\tdevelop\topen\t${priority_labels_base64}\n" \
  "901\thead-901\texample/FireMUD\tdependabot[bot]\tdevelop\topen\t${priority_labels_base64}\n" \
  "901\thead-901\texample/FireMUD\thuman\tfeature/stack\topen\t${priority_labels_base64}\n"
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
export FAKE_TARGET_PAUSED=true
if bash "$ALLOCATOR" pr-900 3 900 "$FAKE_TARGET_HEAD"; then
  echo "paused target was allowed to enter preview allocation" >&2
  exit 1
fi
test ! -e "$FAKE_DELETE_LOG"

reset_case
export FAKE_TARGET_LABELS_VALID=invalid
if bash "$ALLOCATOR" pr-900 3 900 "$FAKE_TARGET_HEAD"; then
  echo "malformed target labels were treated as eligible" >&2
  exit 1
fi
test ! -e "$FAKE_DELETE_LOG"

for target_contract_case in repository head base author metadata; do
  reset_case
  case "$target_contract_case" in
    repository) export FAKE_TARGET_REPOSITORY=other/FireMUD ;;
    head) expected_target_head=other-head ;;
    base) export FAKE_TARGET_BASE_REF=feature/stack ;;
    author) export FAKE_TARGET_AUTHOR='renovate[bot]' ;;
    metadata) export FAKE_TARGET_RAW_QUERY_FAIL=true ;;
  esac
  if bash "$ALLOCATOR" pr-900 3 900 "${expected_target_head:-$FAKE_TARGET_HEAD}"; then
    echo "allocator accepted invalid live target ${target_contract_case} metadata" >&2
    exit 1
  fi
  test ! -e "$FAKE_DELETE_LOG"
  unset expected_target_head
done

for malformed_target_metadata in \
  $'open\thead-900' \
  "open\thead-900\t${priority_labels_base64}\textra" \
  $'open\thead-900\t'"${priority_labels_base64}"$'\nclosed\tforged-head\t'"${priority_labels_base64}"
do
  reset_case
  export FAKE_TARGET_METADATA="$malformed_target_metadata"
  if bash "$ALLOCATOR" pr-900 3 900 "$FAKE_TARGET_HEAD"; then
    echo "allocator accepted malformed target metadata record framing" >&2
    exit 1
  fi
  test ! -e "$FAKE_DELETE_LOG"
done

reset_case
export FAKE_TARGET_PRIORITY=false
# The centralized authority must derive pause state from the transported labels.
export FAKE_OPEN_PRIORITY_ROWS="901\thead-901\texample/FireMUD\thuman\tdevelop\topen\t${paused_labels_base64}\n"
bash "$ALLOCATOR" pr-900 3 900 "$FAKE_TARGET_HEAD"
test ! -e "$FAKE_DELETE_LOG"

reset_case
export FAKE_TARGET_PRIORITY=false
export FAKE_OPEN_PRIORITY_ROWS="901\thead-901\texample/FireMUD\thuman\tdevelop\topen\t${priority_labels_base64}\n"
export PREVIEW_ELIGIBILITY_SCRIPT="$TEMP_DIR/eligibility-fail.py"
if bash "$ALLOCATOR" pr-900 3 900 "$FAKE_TARGET_HEAD"; then
  echo "ordinary allocation did not fail closed when eligibility evaluation failed" >&2
  exit 1
fi

reset_case
export FAKE_TARGET_PRIORITY=false
export FAKE_OPEN_PRIORITY_ROWS="901\thead-901\texample/FireMUD\thuman\tdevelop\topen\t${adversarial_priority_labels_base64}\n"
if bash "$ALLOCATOR" pr-900 3 900 "$FAKE_TARGET_HEAD"; then
  echo "ordinary allocation ignored a priority label alongside quoted and backslashed label data" >&2
  exit 1
fi
test ! -e "$FAKE_DELETE_LOG"

reset_case
export FAKE_NAMESPACE_ROWS='pr-101\t101\n'
export FAKE_PRUNE_METADATA="open\tdevelop\thuman\t${adversarial_paused_labels_base64}\n"
bash "$PRUNER" --apply
grep -qx 'pr-101 pr-101' "$FAKE_DELETE_LOG"

for prune_failure in FAKE_PRUNE_QUERY_FAIL FAKE_PRUNE_JQ_FAIL; do
  reset_case
  export FAKE_NAMESPACE_ROWS='pr-101\t101\n'
  export "$prune_failure"=true
  bash "$PRUNER" --apply
  if [[ -e "$FAKE_DELETE_LOG" ]]; then
    echo "prune deleted a namespace after ${prune_failure}" >&2
    exit 1
  fi
done

reset_case
export FAKE_NAMESPACE_ROWS='pr-101\t101\n'
export FAKE_PRUNE_METADATA=$'open\tdevelop\thuman\tnot-valid-base64!\n'
bash "$PRUNER" --apply
if [[ -e "$FAKE_DELETE_LOG" ]]; then
  echo "prune deleted a namespace after malformed label transport" >&2
  exit 1
fi

reset_case
export FAKE_NAMESPACE_ROWS='pr-101\t101\n'
export FAKE_PRUNE_METADATA=$'open\tdevelop\thuman\tmalformed\n'
bash "$PRUNER" --apply
if [[ -e "$FAKE_DELETE_LOG" ]]; then
  echo "prune deleted a namespace after explicit malformed-label metadata" >&2
  exit 1
fi

reset_case
export FAKE_NAMESPACE_ROWS='pr-101\t101\n'
export FAKE_PRUNE_METADATA="open\tdevelop\thuman\t${invalid_json_labels_base64}\n"
bash "$PRUNER" --apply
if [[ -e "$FAKE_DELETE_LOG" ]]; then
  echo "prune deleted a namespace after decoded label JSON was invalid" >&2
  exit 1
fi

for malformed_pr_metadata in \
  $'open\tdevelop\thuman\n' \
  "open\tdevelop\thuman\t${priority_labels_base64}\textra\n" \
  "open\tdevelop\thuman\t${priority_labels_base64}\nclosed\tdevelop\thuman\t${priority_labels_base64}\n"
do
  reset_case
  export FAKE_NAMESPACE_ROWS='pr-101\t101\n'
  export FAKE_PRUNE_METADATA="$malformed_pr_metadata"
  bash "$PRUNER" --apply
  if [[ -e "$FAKE_DELETE_LOG" ]]; then
    echo "prune deleted a namespace after malformed metadata record framing" >&2
    exit 1
  fi
done

reset_case
export FAKE_NAMESPACE_ROWS='pr-101\t101\n'
export FAKE_PRUNE_METADATA="open\tdevelop\thuman\t${priority_labels_base64}\n"
export PREVIEW_ELIGIBILITY_SCRIPT="$TEMP_DIR/eligibility-fail.py"
bash "$PRUNER" --apply
if [[ -e "$FAKE_DELETE_LOG" ]]; then
  echo "prune deleted a namespace after eligibility evaluation failed" >&2
  exit 1
fi

for malformed_eligibility_output in \
  'eligible=false' \
  $'eligible=false\nreason=preview-paused\neligible=false' \
  $'eligible=false\nreason=preview-paused\nextra=value' \
  $'reason=preview-paused\neligible=false' \
  $'eligible=maybe\nreason=preview-paused' \
  $'eligible=false\nreason='
do
  reset_case
  export FAKE_NAMESPACE_ROWS='pr-101\t101\n'
  export FAKE_PRUNE_METADATA="open\tdevelop\thuman\t${priority_labels_base64}\n"
  export PREVIEW_ELIGIBILITY_SCRIPT="$TEMP_DIR/eligibility-output.py"
  export FAKE_ELIGIBILITY_OUTPUT="$malformed_eligibility_output"
  bash "$PRUNER" --apply
  if [[ -e "$FAKE_DELETE_LOG" ]]; then
    echo "prune deleted a namespace after malformed eligibility output" >&2
    exit 1
  fi
done

for non_authoritative_reason in malformed-label-metadata future-eligibility-reason; do
  reset_case
  export FAKE_NAMESPACE_ROWS='pr-101\t101\n'
  export FAKE_PRUNE_METADATA="open\tdevelop\thuman\t${priority_labels_base64}\n"
  export PREVIEW_ELIGIBILITY_SCRIPT="$TEMP_DIR/eligibility-output.py"
  export FAKE_ELIGIBILITY_OUTPUT="eligible=false
reason=${non_authoritative_reason}"
  bash "$PRUNER" --apply
  if [[ -e "$FAKE_DELETE_LOG" ]]; then
    echo "prune deleted a namespace for non-authoritative reason ${non_authoritative_reason}" >&2
    exit 1
  fi
done

for authoritative_prune_reason in preview-paused dependency-bot unsupported-base-branch pr-not-open; do
  reset_case
  export FAKE_NAMESPACE_ROWS='pr-101\t101\n'
  export FAKE_PRUNE_METADATA="open\tdevelop\thuman\t${priority_labels_base64}\n"
  export PREVIEW_ELIGIBILITY_SCRIPT="$TEMP_DIR/eligibility-output.py"
  export FAKE_ELIGIBILITY_OUTPUT="eligible=false
reason=${authoritative_prune_reason}"
  bash "$PRUNER" --apply
  grep -qx 'pr-101 pr-101' "$FAKE_DELETE_LOG"
done

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
PREVIEW_DERIVE_RUN="$TEMP_DIR/preview-derive.sh"
PREVIEW_CLEANUP_SCRIPT="$TEMP_DIR/preview-cleanup.js"
RECONCILER_RUN="$TEMP_DIR/preview-reconciler.sh"
extract_workflow_step_run "$preview_workflow" "Derive preview identifiers" "$PREVIEW_DERIVE_RUN"
extract_workflow_step_run \
  "$preview_workflow" \
  "Revalidate preview cleanup target before deletion" \
  "$PREVIEW_CLEANUP_SCRIPT" \
  script
extract_workflow_step_run "$reconciler_workflow" "Dispatch preview deploys for drifted PRs" "$RECONCILER_RUN"

run_preview_derive() {
  local event_action="$1"
  local labels_json="$2"
  local output="$3"
  rm -f "$output"
  (
    cd "$ROOT_DIR"
    EVENT_NAME=pull_request \
      EVENT_ACTION="$event_action" \
      EVENT_LABELS_JSON="$labels_json" \
      EVENT_PR_NUMBER=900 \
      EVENT_HEAD_SHA=head-900 \
      EVENT_BASE_SHA=base-900 \
      INPUT_PR_NUMBER='' \
      INPUT_HEAD_SHA='' \
      INPUT_PREVIEW_DOMAIN='' \
      INPUT_ACTION='' \
      INPUT_IMAGE_TAG='' \
      GITHUB_SHA=head-900 \
      GITHUB_OUTPUT="$output" \
      bash "$PREVIEW_DERIVE_RUN"
  )
}

adversarial_paused_labels_json='[{"name":"preview:paused"},{"name":"quote\"slash\\label"}]'
for paused_event_action in opened synchronize reopened; do
  derive_output="$TEMP_DIR/derive-${paused_event_action}.out"
  run_preview_derive "$paused_event_action" "$adversarial_paused_labels_json" "$derive_output"
  grep -qx 'action=destroy' "$derive_output"
done

for pause_present in true false; do
  PREVIEW_PR_NUMBER=900 \
    EXPECTED_HEAD_SHA=head-900 \
    PREVIEW_ACTION=destroy \
    node - "$PREVIEW_CLEANUP_SCRIPT" "$pause_present" <<'NODE'
const fs = require("node:fs");

const [scriptPath, pausePresent] = process.argv.slice(2);
const script = fs.readFileSync(scriptPath, "utf8");
let failure = "";
const github = {
  rest: {
    pulls: {
      get: async () => ({
        data: {
          state: "open",
          head: { sha: "head-900" },
          labels: pausePresent === "true" ? [{ name: "preview:paused" }] : [],
        },
      }),
    },
  },
};
const context = {
  eventName: "pull_request",
  payload: { action: "synchronize" },
  repo: { owner: "example", repo: "FireMUD" },
};
const core = {
  info: () => {},
  setFailed: (message) => {
    failure = message;
  },
};
const AsyncFunction = Object.getPrototypeOf(async function () {}).constructor;
(async () => {
  await new AsyncFunction("github", "context", "core", script)(github, context, core);
  if (pausePresent === "true" && failure) {
    throw new Error(`paused synchronize cleanup was rejected: ${failure}`);
  }
  if (pausePresent === "false" && !failure) {
    throw new Error("paused synchronize cleanup ignored removal of preview:paused");
  }
})().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
NODE
done

run_preview_derive synchronize '{}' "$TEMP_DIR/derive-malformed.out"
grep -qx 'action=deploy' "$TEMP_DIR/derive-malformed.out"
malformed_eligibility="$(
  python3 "$ROOT_DIR/dev-tools/hosted/preview/preview-eligibility.py" \
    --operation deploy \
    --state open \
    --base-ref develop \
    --author human \
    --labels-json '{}'
)"
grep -qx 'eligible=false' <<<"$malformed_eligibility"
grep -qx 'reason=malformed-label-metadata' <<<"$malformed_eligibility"

run_preview_derive unlabeled '[]' "$TEMP_DIR/derive-unpaused.out"
grep -qx 'action=deploy' "$TEMP_DIR/derive-unpaused.out"

fork_repository_base64="$(printf '%s' '"fork/FireMUD"' | base64 | tr -d '\n')"
same_repository_base64="$(printf '%s' '"example/FireMUD"' | base64 | tr -d '\n')"
empty_labels_base64="$(printf '%s' '[]' | base64 | tr -d '\n')"
reconciler_rows="101\t${fork_repository_base64}\tshared-ref\tfork-head\thuman\tdevelop\topen\t${empty_labels_base64}\n102\t${same_repository_base64}\tshared-ref\tsame-head\thuman\tdevelop\topen\t${empty_labels_base64}\n"

run_reconciler_fixture() {
  local script="$1"
  local output="$2"
  FAKE_RECONCILER_MODE=true \
    FAKE_RECONCILER_PR_ROWS="$reconciler_rows" \
    FAKE_NAMESPACE_ROWS='' \
    PREVIEW_MAX_ACTIVE=2 \
    bash "$script" >"$output"
}

reset_case
run_reconciler_fixture "$RECONCILER_RUN" "$TEMP_DIR/reconciler.out"
grep -Fq 'Skipping preview reconcile for PR #101: untrusted head repository fork/FireMUD' "$TEMP_DIR/reconciler.out"
grep -Fq 'inputs[pr_number]=102' "$FAKE_RECONCILER_DISPATCH_LOG"
if grep -Fq 'inputs[pr_number]=101' "$FAKE_RECONCILER_DISPATCH_LOG"; then
  echo "preview reconciler dispatched the same-ref fork PR" >&2
  exit 1
fi
test "$(wc -l < "$FAKE_RECONCILER_RUN_LIST_LOG")" -eq 1

MUTATED_RECONCILER_RUN="$TEMP_DIR/preview-reconciler-without-head-repository-guard.sh"
python3 - "$RECONCILER_RUN" "$MUTATED_RECONCILER_RUN" <<'PY'
import sys
from pathlib import Path

path = Path(sys.argv[1])
source = path.read_text(encoding="utf-8")
guard = '''if [[ "$head_repository" != "$GITHUB_REPOSITORY" ]]; then
    echo "Skipping preview reconcile for PR #${pr_number}: untrusted head repository ${head_repository}"
    continue
  fi
'''
if source.count(guard) != 1:
    raise SystemExit("expected one head-repository guard in reconciler fixture")
Path(sys.argv[2]).write_text(source.replace(guard, ""), encoding="utf-8")
PY
reset_case
run_reconciler_fixture "$MUTATED_RECONCILER_RUN" "$TEMP_DIR/reconciler-mutated.out"
grep -Fq 'inputs[pr_number]=101' "$FAKE_RECONCILER_DISPATCH_LOG"

janitor_workflow="$ROOT_DIR/.github/workflows/preview-janitor.yml"
python3 - "$preview_workflow" <<'PY'
import sys
import re
from pathlib import Path

import yaml

workflow = yaml.safe_load(Path(sys.argv[1]).read_text(encoding="utf-8"))
actual = workflow["jobs"]["preview-plan"]["if"]
if not isinstance(actual, str) or not actual.strip().startswith("${{"):
    raise SystemExit(f"preview-plan if condition is not a workflow expression: {actual}")

expression = actual.strip()[3:-2].strip()


def evaluate_workflow_condition(event_name, action, label, same_repository=True):
    context = {
        "github.event_name": event_name,
        "github.event.action": action,
        "github.event.label.name": label,
        "github.event.pull_request.head.repo.full_name": (
            "example/FireMUD" if same_repository else "fork/FireMUD"
        ),
        "github.repository": "example/FireMUD",
    }
    translated = expression
    references = sorted(
        set(re.findall(r"\b(?:github|inputs)(?:\.[A-Za-z_][A-Za-z0-9_-]*)+", expression)),
        key=len,
        reverse=True,
    )
    for reference in references:
        if reference not in context:
            raise SystemExit(f"unmapped workflow expression reference: {reference}")
        translated = translated.replace(reference, repr(context[reference]))
    translated = translated.replace("&&", " and ").replace("||", " or ")
    translated = re.sub(r"!(?!=)", " not ", translated)
    try:
        result = eval(translated, {"__builtins__": {}}, {})
    except (SyntaxError, NameError, TypeError, ValueError) as error:
        raise SystemExit(f"could not evaluate preview-plan workflow condition: {error}")
    if not isinstance(result, bool):
        raise SystemExit(f"preview-plan workflow condition did not return a boolean: {result}")
    return result


cases = (
    ("pull_request", "unlabeled", "preview:priority", True, False),
    ("pull_request", "labeled", "preview:priority", True, True),
    ("pull_request", "unlabeled", "preview:paused", True, True),
    ("pull_request", "labeled", "preview:paused", True, True),
    ("pull_request", "unlabeled", "unrelated", True, False),
    ("pull_request", "unlabeled", "", True, False),
    ("pull_request", "labeled", "unrelated", True, False),
    ("pull_request", "labeled", "", True, False),
    ("pull_request", "synchronize", "", True, True),
    ("pull_request", "opened", "", False, False),
    ("workflow_dispatch", "", "", False, True),
)
for event_name, action, label, same_repository, expected_result in cases:
    actual_result = evaluate_workflow_condition(event_name, action, label, same_repository)
    if actual_result != expected_result:
        raise SystemExit(
            "preview-plan event condition mismatch for "
            f"{event_name}/{action}/{label or '-'}: {actual_result}"
        )
PY
grep -q 'github.event.label.name == '\''preview:priority'\''' "$preview_workflow"
grep -q 'github.event.label.name == '\''preview:paused'\''' "$preview_workflow"
grep -q '^      - unlabeled$' "$preview_workflow"
# shellcheck disable=SC2016 # Assert literal event-to-environment bindings in workflow source.
grep -Fq 'EVENT_ACTION: ${{ github.event.action }}' "$preview_workflow"
# shellcheck disable=SC2016 # Assert literal event-to-environment bindings in workflow source.
grep -Fq 'EVENT_LABELS_JSON: ${{ toJSON(github.event.pull_request.labels) }}' "$preview_workflow"
# shellcheck disable=SC2016 # Assert literal shell source in the workflow.
grep -Fq -- '--inspect-labels --labels-json "$EVENT_LABELS_JSON"' "$preview_workflow"
# shellcheck disable=SC2016 # Assert literal shell source in the workflow.
grep -Fq '[ "$labels_valid" = "true" ] && [ "$paused" = "true" ]' "$preview_workflow"
grep -Fq '(requiresPausedLabel && !pauseStillPresent) ||' "$preview_workflow"
grep -Fq 'currentPullRequest.labels.some(label => label?.name === "preview:paused")' "$preview_workflow"
revalidation_helper="$ROOT_DIR/dev-tools/hosted/preview/revalidate-preview-deploy.sh"
test "$(grep -Fc -- 'revalidate-preview-deploy.sh' "$preview_workflow")" -eq 1
test "$(grep -Fc -- 'revalidate-preview-deploy.sh' "$ALLOCATOR")" -eq 1
test "$(grep -Fc -- '--revalidate-deploy' "$revalidation_helper")" -eq 1
# shellcheck disable=SC2016 # Assert literal shell source in the revalidation helper.
grep -Fq -- '--expected-repository "$GITHUB_REPOSITORY"' "$revalidation_helper"
# shellcheck disable=SC2016 # Assert literal shell source in the revalidation helper.
grep -Fq -- '--expected-head-sha "$expected_head_sha"' "$revalidation_helper"
# shellcheck disable=SC2016 # These assertions intentionally match literal workflow source.
grep -q -- '--inspect-labels --labels-json "$labels_json"' "$reconciler_workflow"
grep -Fq '(.head.repo.full_name | tojson | @base64)' "$reconciler_workflow"
# shellcheck disable=SC2016 # Assert literal shell source in the workflow.
grep -Fq '[[ "$head_repository" != "$GITHUB_REPOSITORY" ]]' "$reconciler_workflow"
# shellcheck disable=SC2016 # This assertion intentionally matches literal shell source.
grep -q -- '--inspect-labels --labels-json "$labels_json"' "$ROOT_DIR/dev-tools/hosted/preview/allocate-preview-capacity.sh"
grep -q -- "--labels-json \"\$labels_json\"" "$ROOT_DIR/dev-tools/hosted/preview/allocate-preview-capacity.sh"
grep -q -- '--operation retain' "$ROOT_DIR/dev-tools/hosted/preview/prune-stale-preview-namespaces.sh"
grep -Fq '(.labels | tojson | @base64)' "$ROOT_DIR/dev-tools/hosted/preview/prune-stale-preview-namespaces.sh"
grep -q -- "--labels-json \"\$pr_labels_json\"" "$ROOT_DIR/dev-tools/hosted/preview/prune-stale-preview-namespaces.sh"
if grep -Eq 'def labels_valid:|all\(\.labels\[\]\?; \(type == "object"\)' \
  "$preview_workflow" \
  "$reconciler_workflow" \
  "$ROOT_DIR/dev-tools/hosted/preview/allocate-preview-capacity.sh" \
  "$ROOT_DIR/dev-tools/hosted/preview/prune-stale-preview-namespaces.sh"; then
  echo "A live preview callsite duplicates the centralized label predicate" >&2
  exit 1
fi
test "$(grep -h -c 'group: preview-allocation-lifecycle' "$preview_workflow" "$janitor_workflow" | awk '{ total += $1 } END { print total }')" -eq 3
grep -q 'Skipping ordinary PR #' "$reconciler_workflow"
grep -q 'another preview repair was already dispatched this cycle' "$reconciler_workflow"

echo "preview priority contract checks passed"
