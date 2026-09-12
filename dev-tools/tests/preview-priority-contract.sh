#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
ALLOCATOR="$ROOT_DIR/dev-tools/hosted/preview/allocate-preview-capacity.sh"
PRUNER="$ROOT_DIR/dev-tools/hosted/preview/prune-stale-preview-namespaces.sh"
DELETE_HOSTED_NAMESPACE="$ROOT_DIR/dev-tools/hosted/shared/delete-hosted-namespace.sh"
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
matches = []
for job in workflow["jobs"].values():
    for step in job.get("steps", []):
        if step.get("name") == sys.argv[2]:
            matches.append(step)
if len(matches) != 1:
    raise SystemExit(
        f"workflow step {sys.argv[2]} matched {len(matches)} times; expected exactly one"
    )
body = (
    matches[0].get("run")
    if sys.argv[4] == "run"
    else matches[0].get("with", {}).get("script")
)
if not isinstance(body, str):
    raise SystemExit(f"workflow step {sys.argv[2]} has no {sys.argv[4]} body")
Path(sys.argv[3]).write_text(body, encoding="utf-8")
PY
}

cardinality_workflow="$TEMP_DIR/step-cardinality.yml"
cat >"$cardinality_workflow" <<'YAML'
jobs:
  first:
    steps:
      - name: Duplicate step
        run: echo first
  second:
    steps:
      - name: Duplicate step
        run: echo second
YAML
if extract_workflow_step_run \
  "$cardinality_workflow" "Missing step" "$TEMP_DIR/missing-step.sh" \
  2>"$TEMP_DIR/missing-step.err"; then
  echo "workflow extraction accepted a missing step" >&2
  exit 1
fi
grep -qx 'workflow step Missing step matched 0 times; expected exactly one' \
  "$TEMP_DIR/missing-step.err"
if extract_workflow_step_run \
  "$cardinality_workflow" "Duplicate step" "$TEMP_DIR/duplicate-step.sh" \
  2>"$TEMP_DIR/duplicate-step.err"; then
  echo "workflow extraction accepted duplicate steps" >&2
  exit 1
fi
grep -qx 'workflow step Duplicate step matched 2 times; expected exactly one' \
  "$TEMP_DIR/duplicate-step.err"

mkdir -p "$TEMP_DIR/bin"
cat > "$TEMP_DIR/bin/kubectl" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
if [[ "$*" == *"get namespaces"* ]]; then
  if [[ "${FAKE_NAMESPACE_LIST_ERROR:-false}" == true ]]; then
    exit 1
  fi
  printf '%b' "${FAKE_NAMESPACE_ROWS:-}"
  exit 0
fi
if [[ "$1" == get && "$2" == namespace && "$3" == pr-901 &&
  "$*" == *"--ignore-not-found -o json"* ]]; then
  printf '%s\n' "$*" >> "$FAKE_NAMESPACE_SNAPSHOT_LOG"
  count=0
  if [[ -f "$FAKE_NAMESPACE_SNAPSHOT_CALLS" ]]; then
    count="$(<"$FAKE_NAMESPACE_SNAPSHOT_CALLS")"
  fi
  count=$((count + 1))
  printf '%s' "$count" > "$FAKE_NAMESPACE_SNAPSHOT_CALLS"
  if [[ "${FAKE_NAMESPACE_SNAPSHOT_ERROR:-false}" == true ]] ||
    [[ "${FAKE_NAMESPACE_SNAPSHOT_RECHECK_ERROR:-false}" == true && "$count" -gt 1 ]]; then
    exit 1
  fi
  if [[ "${FAKE_PR_901_NAMESPACE_ABSENT:-false}" == true ]]; then
    exit 0
  fi
  if [[ "${FAKE_NAMESPACE_SNAPSHOT_PARSE_FAIL:-false}" == true ]] ||
    [[ "${FAKE_NAMESPACE_SNAPSHOT_RECHECK_PARSE_FAIL:-false}" == true && "$count" -gt 1 ]]; then
    printf '%s' '{"metadata":{"annotations":[]}}'
    exit 0
  fi
  if [[ -f "${FAKE_ANNOTATE_FAILURE_MARKER:?}" ]]; then
    rm -f "${FAKE_ANNOTATE_FAILURE_MARKER:?}"
    if [[ "${FAKE_ANNOTATE_CONFIRMATION_ERROR:-false}" == true ]]; then
      exit 1
    fi
    if [[ "${FAKE_ANNOTATE_NAMESPACE_ABSENT_AFTER_FAILURE:-false}" == true ]]; then
      exit 0
    fi
  fi
  snapshot_head="${FAKE_PR_901_HEAD:-}"
  snapshot_requested_head="${FAKE_PR_901_REQUESTED_HEAD:-}"
  if [[ "$count" -gt 1 ]]; then
    if [[ -v FAKE_PR_901_RECHECK_HEAD ]]; then
      snapshot_head="$FAKE_PR_901_RECHECK_HEAD"
    fi
    if [[ -v FAKE_PR_901_RECHECK_REQUESTED_HEAD ]]; then
      snapshot_requested_head="$FAKE_PR_901_RECHECK_REQUESTED_HEAD"
    fi
  fi
  jq -cn \
    --arg head "$snapshot_head" \
    --arg requested_head "$snapshot_requested_head" \
    '{metadata:{name:"pr-901",resourceVersion:"rv-901",annotations:{"firemud.dev/last-preview-head-sha":$head,"firemud.dev/requested-preview-head-sha":$requested_head}}}'
  exit 0
fi
if [[ "$1" == get && "$2" == namespace &&
  "$*" == *"--ignore-not-found -o json"* ]]; then
  printf '%s\n' "$*" >> "$FAKE_RUNTIME_KUBECTL_LOG"
  if [[ "${FAKE_RUNTIME_LOOKUP_ERROR:-false}" == true ]]; then
    exit 1
  fi
  if [[ "${FAKE_RUNTIME_NAMESPACE_PRESENT:-true}" == false ]] ||
    [[ -f "$FAKE_RUNTIME_NAMESPACE_DELETED_MARKER" ]]; then
    exit 0
  fi
  if [[ "${FAKE_RUNTIME_NAMESPACE_JSON_PARSE_FAIL:-false}" == true ]]; then
    printf '%s' '{not-json'
    exit 0
  fi
  runtime_name="${FAKE_RUNTIME_LOOKUP_IDENTITY:-$3}"
  runtime_uid="${FAKE_RUNTIME_NAMESPACE_UID:-uid-$3}"
  if [[ "${FAKE_RUNTIME_NAMESPACE_UID_MISSING:-false}" == true ]]; then
    runtime_uid=""
  fi
  if [[ "$3" == dev ]]; then
    jq -cn \
      --arg name "$runtime_name" \
      --arg uid "$runtime_uid" \
      --arg dev_demo "${FAKE_RUNTIME_DEV_DEMO_LABEL:-true}" \
      --arg environment_class "${FAKE_RUNTIME_ENVIRONMENT_CLASS:-dev-demo-cluster}" \
      '{metadata:{name:$name,uid:$uid,labels:{"firemud.dev/dev-demo":$dev_demo,"firemud.dev/environment-class":$environment_class}}}'
  else
    jq -cn \
      --arg name "$runtime_name" \
      --arg uid "$runtime_uid" \
      --arg preview "${FAKE_RUNTIME_PREVIEW_LABEL:-true}" \
      --arg owner "${FAKE_RUNTIME_PR_NUMBER_LABEL:-${3#pr-}}" \
      '{metadata:{name:$name,uid:$uid,labels:{"firemud.dev/preview":$preview,"firemud.dev/pr-number":$owner}}}'
  fi
  exit 0
fi
if [[ "$1" == get && "$2" == namespace && "$*" == *"--ignore-not-found -o name"* ]]; then
  printf '%s\n' "$*" >> "$FAKE_RUNTIME_KUBECTL_LOG"
  if [[ -f "$FAKE_REQUESTED_HEAD_NOT_FOUND_MARKER" ]]; then
    rm -f "$FAKE_REQUESTED_HEAD_NOT_FOUND_MARKER"
    if [[ "${FAKE_REQUESTED_HEAD_RECHECK_ERROR:-false}" == true ]]; then
      exit 1
    fi
    exit 0
  fi
  if [[ -f "$FAKE_RUNTIME_WAIT_MARKER" ]]; then
    if [[ "${FAKE_RUNTIME_RECHECK_ERROR:-false}" == true ]]; then
      exit 1
    fi
    if [[ "${FAKE_RUNTIME_NAMESPACE_PRESENT_AFTER_WAIT:-true}" == false ]]; then
      exit 0
    fi
  fi
  if [[ "${FAKE_RUNTIME_LOOKUP_ERROR:-false}" == true ]]; then
    exit 1
  fi
  if [[ "${FAKE_RUNTIME_NAMESPACE_PRESENT:-true}" == false ]] ||
    [[ -f "$FAKE_RUNTIME_NAMESPACE_DELETED_MARKER" ]]; then
    if [[ -n "${FAKE_OPERATION_SEQUENCE:-}" && "${FAKE_RECORD_RUNTIME_CHECK:-false}" == true ]]; then
      printf 'runtime-check\n' >> "$FAKE_OPERATION_SEQUENCE"
    fi
    exit 0
  fi
  printf 'namespace/%s\n' "${FAKE_RUNTIME_LOOKUP_IDENTITY:-$3}"
  exit 0
fi
if [[ "$1" == get && "$2" == namespace && "$*" == *"requested-preview-head-sha"* ]]; then
  printf '%s\n' "$*" >> "$FAKE_REQUESTED_HEAD_LOG"
  if [[ "${FAKE_REQUESTED_HEAD_READ_ERROR:-false}" == true ]]; then
    if [[ "${FAKE_REQUESTED_HEAD_NOT_FOUND:-false}" == true &&
      "$*" == *"--ignore-not-found"* ]]; then
      : > "$FAKE_REQUESTED_HEAD_NOT_FOUND_MARKER"
      exit 0
    fi
    exit 1
  fi
  printf '%s' "${FAKE_PR_901_REQUESTED_HEAD:-}"
  exit 0
fi
if [[ "$1" == delete && "$2" == --raw && "$4" == -f && "$5" == - ]]; then
  printf '%s\n' "$*" >> "$FAKE_RUNTIME_KUBECTL_LOG"
  delete_options="$(cat)"
  if ! delete_uid="$(jq -e -r '.preconditions.uid | select(type == "string" and length > 0)' <<<"$delete_options")"; then
    exit 1
  fi
  printf '%s\n' "$delete_uid" >> "$FAKE_RUNTIME_DELETE_UID_LOG"
  if [[ "${FAKE_RUNTIME_NAMESPACE_ABSENT_ON_DELETE:-false}" == true ]]; then
    : > "$FAKE_RUNTIME_NAMESPACE_DELETED_MARKER"
    exit 1
  fi
  if [[ "${FAKE_RUNTIME_DELETE_ERROR:-false}" == true ]] ||
    [[ "$3" == */"${FAKE_RUNTIME_DELETE_ERROR_NAMESPACE:-__none__}" ]]; then
    exit 1
  fi
  expected_delete_uid="${FAKE_RUNTIME_DELETE_CURRENT_UID:-${FAKE_RUNTIME_NAMESPACE_UID:-uid-${3##*/}}}"
  if [[ "$delete_uid" != "$expected_delete_uid" ]]; then
    exit 1
  fi
  exit 0
fi
if [[ "$1" == delete && "$2" == namespace ]]; then
  printf '%s\n' "$*" >> "$FAKE_RUNTIME_KUBECTL_LOG"
  if [[ "${FAKE_RUNTIME_DELETE_ERROR:-false}" == true ]] ||
    [[ -n "${FAKE_RUNTIME_DELETE_ERROR_NAMESPACE:-}" &&
      "$3" == "$FAKE_RUNTIME_DELETE_ERROR_NAMESPACE" ]]; then
    exit 1
  fi
  exit 0
fi
if [[ "$1" == wait && "$2" == --for=delete ]]; then
  printf '%s\n' "$*" >> "$FAKE_RUNTIME_KUBECTL_LOG"
  : > "$FAKE_RUNTIME_WAIT_MARKER"
  if [[ "${FAKE_RUNTIME_WAIT_ERROR:-false}" == true ]]; then
    exit 1
  fi
  exit 0
fi
if [[ "$1" == annotate && "$2" == namespace ]]; then
  printf '%s\n' "$*" >> "$FAKE_ANNOTATE_LOG"
  if [[ "${FAKE_ANNOTATE_ERROR:-false}" == true ]]; then
    : > "${FAKE_ANNOTATE_FAILURE_MARKER:?}"
    exit 1
  fi
  exit 0
fi
if [[ "$*" == *"get hostedenvironmentidentity"* ]]; then
  printf '%s\n' "$*" >> "$FAKE_IDENTITY_LOG"
  if [[ -n "${FAKE_IDENTITY_LOOKUP_FAIL_NAMESPACE:-}" &&
    "${5:-}" == "$FAKE_IDENTITY_LOOKUP_FAIL_NAMESPACE" ]]; then
    exit 1
  fi
  printf '%s' "${FAKE_IDENTITY_JSON:-}"
  exit 0
fi
if [[ "$*" == *"delete hostedenvironmentidentity"* ]]; then
  printf '%s\n' "$*" >> "$FAKE_IDENTITY_LOG"
  if [[ -n "${FAKE_OPERATION_SEQUENCE:-}" ]]; then
    printf 'identity-delete\n' >> "$FAKE_OPERATION_SEQUENCE"
  fi
  if [[ "${FAKE_IDENTITY_DELETE_FAIL:-false}" == "true" ]]; then
    exit 1
  fi
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

cat > "$TEMP_DIR/bin/helm" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "$*" >> "$FAKE_HELM_LOG"
EOF

cat > "$TEMP_DIR/bin/gh" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
resource=""
has_jq=false
for arg in "$@"; do
  if [[ "$arg" == repos/* ]]; then
    resource="$arg"
  elif [[ "$arg" == "--jq" ]]; then
    has_jq=true
  fi
done
encode_fake_labels() {
  local priority="$1"
  local labels_valid="$2"
  local labels_json='[]'
  if [[ "$labels_valid" != valid ]]; then
    labels_json='{}'
  elif [[ "$priority" == true ]]; then
    labels_json='[{"name":"preview:priority"}]'
  fi
  printf '%s' "$labels_json" | base64 | tr -d '\n'
}
if [[ "$1" == run && "$2" == list ]]; then
  exit 0
fi
if [[ "$*" == *"actions/workflows/preview.yml/dispatches"* ]]; then
  printf '%s\n' "$*" >> "$FAKE_DISPATCH_LOG"
  exit 0
fi
case "$resource" in
  */actions/runs/42)
    if [[ "$has_jq" != true ]]; then
      printf '%s' '{"conclusion":"success","head_sha":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","path":".github/workflows/preview.yml","event":"pull_request","repository":{"full_name":"example/FireMUD"},"pull_requests":[{"number":900}]}'
    elif [[ "$*" == *".path"* ]]; then
      printf '%s' '.github/workflows/preview.yml'
    else
      printf '%s' 900
    fi
    ;;
  */actions/runs/42/artifacts\?per_page=100)
    if [[ -v FAKE_SOURCE_ARTIFACTS_JSON ]]; then
      printf '%s' "$FAKE_SOURCE_ARTIFACTS_JSON"
    else
      printf '%s' '[{"artifacts":[{"name":"preview-render-pr-900-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","expired":false}]}]'
    fi
    ;;
  */pulls\?state=*)
    if [[ "${FAKE_PRIORITY_QUERY_FAIL:-false}" == "true" ]]; then
      exit 1
    fi
    if [[ "$*" == *"per_page=1&page=1001"* ]]; then
      if [[ "${FAKE_PRIORITY_OVERFLOW:-false}" == "true" ]]; then
        printf '%b' "${FAKE_OPEN_PRIORITY_ROWS:-}"
      fi
      exit 0
    fi
    printf '%b' "${FAKE_OPEN_PRIORITY_ROWS:-}"
    ;;
  */pulls/900)
    priority="${FAKE_TARGET_PRIORITY:-true}"
    labels_valid="${FAKE_TARGET_LABELS_VALID:-valid}"
    if [[ "$has_jq" != true ]]; then
      if [[ "${FAKE_TARGET_RAW_QUERY_FAIL:-false}" == true ]]; then
        exit 1
      fi
      labels_json="$(encode_fake_labels "$priority" "$labels_valid" | base64 --decode)"
      jq -cn \
        --arg state "${FAKE_TARGET_STATE:-open}" \
        --arg head "$FAKE_TARGET_HEAD" \
        --arg repository "${FAKE_TARGET_REPOSITORY:-example/FireMUD}" \
        --arg base "${FAKE_TARGET_BASE_REF:-develop}" \
        --arg author "${FAKE_TARGET_AUTHOR:-human}" \
        --argjson labels "$labels_json" \
        '{state: $state, head: {sha: $head, repo: {full_name: $repository}}, base: {ref: $base}, user: {login: $author}, labels: $labels}'
      exit 0
    fi
    if [[ "${FAKE_TARGET_JQ_QUERY_FAIL:-false}" == true ]]; then
      exit 1
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
    printf '%s\t%s\t%s\n' "${FAKE_TARGET_STATE:-open}" "$FAKE_TARGET_HEAD" "$(encode_fake_labels "$priority" "$labels_valid")"
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
    labels_valid="${FAKE_PR_101_LABELS_VALID:-valid}"
    if [[ "${FAKE_PR_101_GAINS_PRIORITY:-false}" == "true" && "$count" -gt 1 ]]; then
      priority=true
    fi
    printf 'open\thead-101\t%s\n' "$(encode_fake_labels "$priority" "$labels_valid")"
  ;;
  */pulls/102)
    if [[ "${FAKE_PRUNE_MULTI_TEST:-false}" == true ]]; then
      printf 'open\tfeature/stack\thuman\t%s\n' "$(encode_fake_labels "${FAKE_PR_102_PRIORITY:-true}" valid)"
    else
      printf 'open\thead-102\t%s\n' "$(encode_fake_labels "${FAKE_PR_102_PRIORITY:-true}" valid)"
    fi
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
if [[ -n "${FAKE_DELETE_TIMEOUT_LOG:-}" ]]; then
  printf '%s\n' "${PREVIEW_NAMESPACE_DELETE_TIMEOUT_SECONDS:-unset}" \
    >> "$FAKE_DELETE_TIMEOUT_LOG"
fi
if [[ -n "${FAKE_OPERATION_SEQUENCE:-}" ]]; then
  printf 'runtime-delete\n' >> "$FAKE_OPERATION_SEQUENCE"
fi
if [[ "${FAKE_DELETE_FAIL:-false}" == "true" ]] ||
  [[ -n "${FAKE_DELETE_FAIL_NAMESPACE:-}" && "$1" == "$FAKE_DELETE_FAIL_NAMESPACE" ]]; then
  exit 1
fi
EOF

cat > "$TEMP_DIR/identity-request" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "$*" >> "$FAKE_IDENTITY_REQUEST_LOG"
if [[ -n "${FAKE_OPERATION_SEQUENCE:-}" ]]; then
  printf 'identity-request\n' >> "$FAKE_OPERATION_SEQUENCE"
fi
if [[ "${FAKE_IDENTITY_REQUEST_FAIL:-false}" == "true" ]]; then
  exit 1
fi
EOF

cat > "$TEMP_DIR/identity-wait" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "$*" >> "$FAKE_IDENTITY_WAIT_LOG"
if [[ -n "${FAKE_OPERATION_SEQUENCE:-}" ]]; then
  printf 'identity-wait\n' >> "$FAKE_OPERATION_SEQUENCE"
fi
if [[ "${FAKE_IDENTITY_WAIT_FAIL:-false}" == "true" ]]; then
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
  "$TEMP_DIR/bin/helm" \
  "$TEMP_DIR/delete" \
  "$TEMP_DIR/identity-request" \
  "$TEMP_DIR/identity-wait" \
  "$TEMP_DIR/publish"

export PATH="$TEMP_DIR/bin:$PATH"
export GITHUB_REPOSITORY="example/FireMUD"
export GH_TOKEN="test-token"
export PREVIEW_DELETE_SCRIPT="$TEMP_DIR/delete"
export PREVIEW_RECLAIMED_PUBLISH_SCRIPT="$TEMP_DIR/publish"
export PREVIEW_RECLAIM_PUBLISH_RETRY_DELAY_SECONDS=0
export FAKE_DELETE_LOG="$TEMP_DIR/delete.log"
export FAKE_DELETE_TIMEOUT_LOG="$TEMP_DIR/delete-timeout.log"
export FAKE_PUBLISH_LOG="$TEMP_DIR/publish.log"
export FAKE_PUBLISHED_STATE="$TEMP_DIR/published-state"
export FAKE_PUBLISH_CALLS="$TEMP_DIR/publish-calls"
export FAKE_COMMENT_METHOD_LOG="$TEMP_DIR/comment-method.log"
export FAKE_ANNOTATE_LOG="$TEMP_DIR/annotate.log"
export FAKE_ANNOTATE_FAILURE_MARKER="$TEMP_DIR/annotate-failure.marker"
export FAKE_REQUESTED_HEAD_LOG="$TEMP_DIR/requested-head.log"
export FAKE_REQUESTED_HEAD_NOT_FOUND_MARKER="$TEMP_DIR/requested-head-not-found"
export FAKE_DISPATCH_LOG="$TEMP_DIR/dispatch.log"
export FAKE_COMMENT_TARGET_LOG="$TEMP_DIR/comment-target.log"
export FAKE_PREVIOUS_COMMENT_ID_LOG="$TEMP_DIR/previous-comment-id.log"
export FAKE_TARGET_CALLS="$TEMP_DIR/target-calls"
export FAKE_PR_101_CALLS="$TEMP_DIR/pr-101-calls"
export FAKE_NAMESPACE_JSON_CALLS="$TEMP_DIR/namespace-json-calls"
export FAKE_NAMESPACE_SNAPSHOT_LOG="$TEMP_DIR/namespace-snapshot.log"
export FAKE_NAMESPACE_SNAPSHOT_CALLS="$TEMP_DIR/namespace-snapshot-calls"
export FAKE_RUNTIME_KUBECTL_LOG="$TEMP_DIR/runtime-kubectl.log"
export FAKE_RUNTIME_DELETE_UID_LOG="$TEMP_DIR/runtime-delete-uid.log"
export FAKE_RUNTIME_NAMESPACE_DELETED_MARKER="$TEMP_DIR/runtime-namespace-deleted"
export FAKE_RUNTIME_WAIT_MARKER="$TEMP_DIR/runtime-wait-failed"
export FAKE_HELM_LOG="$TEMP_DIR/helm.log"
export FAKE_IDENTITY_LOG="$TEMP_DIR/identity.log"
export FAKE_IDENTITY_REQUEST_LOG="$TEMP_DIR/identity-request.log"
export FAKE_IDENTITY_WAIT_LOG="$TEMP_DIR/identity-wait.log"
export FAKE_OPERATION_SEQUENCE="$TEMP_DIR/operation-sequence.log"
export HOSTED_IDENTITY_REQUEST_SCRIPT="$TEMP_DIR/identity-request"
export HOSTED_IDENTITY_WAIT_SCRIPT="$TEMP_DIR/identity-wait"
export HOSTED_IDENTITY_REQUESTER_KUBECONFIG="$TEMP_DIR/requester.kubeconfig"
touch "$HOSTED_IDENTITY_REQUESTER_KUBECONFIG"
export FAKE_TARGET_HEAD="aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
priority_candidate_head="bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
export FAKE_NAMESPACE_ROWS='2026-01-01T00:00:00Z|pr-101|101|2026-01-02T00:00:00Z|head-101|image-101\n2026-01-03T00:00:00Z|pr-102|102|2026-01-04T00:00:00Z|head-102|image-102\n'
priority_labels_base64="$(printf '%s' '[{"name":"preview:priority"}]' | base64 | tr -d '\n')"
adversarial_priority_labels_base64="$(printf '%s' '[{"name":"preview:priority"},{"name":"quote\"slash\\label"}]' | base64 | tr -d '\n')"
adversarial_labels_base64="$(printf '%s' '[{"name":"custom:label"},{"name":"quote\"slash\\label"}]' | base64 | tr -d '\n')"
invalid_json_labels_base64="$(printf '%s' '{invalid-json' | base64 | tr -d '\n')"

reset_case() {
  rm -f "$FAKE_DELETE_LOG" "$FAKE_DELETE_TIMEOUT_LOG" "$FAKE_PUBLISH_LOG" "$FAKE_PUBLISHED_STATE" "$FAKE_PUBLISH_CALLS" "$FAKE_COMMENT_METHOD_LOG" "$FAKE_COMMENT_TARGET_LOG" "$FAKE_PREVIOUS_COMMENT_ID_LOG" "$FAKE_ANNOTATE_LOG" "$FAKE_ANNOTATE_FAILURE_MARKER" "$FAKE_REQUESTED_HEAD_LOG" "$FAKE_REQUESTED_HEAD_NOT_FOUND_MARKER" "$FAKE_DISPATCH_LOG" "$FAKE_TARGET_CALLS" "$FAKE_PR_101_CALLS" "$FAKE_NAMESPACE_JSON_CALLS" "$FAKE_NAMESPACE_SNAPSHOT_LOG" "$FAKE_NAMESPACE_SNAPSHOT_CALLS" "$FAKE_RUNTIME_KUBECTL_LOG" "$FAKE_RUNTIME_DELETE_UID_LOG" "$FAKE_RUNTIME_NAMESPACE_DELETED_MARKER" "$FAKE_RUNTIME_WAIT_MARKER" "$FAKE_HELM_LOG" "$FAKE_IDENTITY_LOG" "$FAKE_IDENTITY_REQUEST_LOG" "$FAKE_IDENTITY_WAIT_LOG" "$FAKE_OPERATION_SEQUENCE" "$TEMP_DIR/output"
  export GITHUB_OUTPUT="$TEMP_DIR/output"
  export FAKE_TARGET_PRIORITY=true
  export FAKE_TARGET_LABELS_VALID=valid
  export FAKE_TARGET_RAW_QUERY_FAIL=false
  export FAKE_TARGET_JQ_QUERY_FAIL=false
  export FAKE_TARGET_STATE=open
  export FAKE_TARGET_REPOSITORY=example/FireMUD
  export FAKE_TARGET_BASE_REF=develop
  export FAKE_TARGET_AUTHOR=human
  export FAKE_TARGET_LOSES_PRIORITY=false
  export FAKE_PR_101_PRIORITY=false
  export FAKE_PR_101_LABELS_VALID=valid
  export FAKE_PR_101_GAINS_PRIORITY=false
  export FAKE_PR_101_OWNER=101
  export FAKE_PR_101_IMAGE='image-101'
  export FAKE_NAMESPACE_JSON_QUERY_FAIL=false
  export FAKE_NAMESPACE_JSON_PARSE_FAIL=false
  export FAKE_PR_102_PRIORITY=true
  export FAKE_PRUNE_MULTI_TEST=false
  export FAKE_IDENTITY_LOOKUP_FAIL_NAMESPACE=''
  export FAKE_OPEN_PRIORITY_ROWS=''
  export FAKE_PRIORITY_OVERFLOW=false
  export FAKE_PRIORITY_QUERY_FAIL=false
  export FAKE_PR_901_OWNER=''
  export FAKE_PR_901_HEAD=''
  export FAKE_PR_901_REQUESTED_HEAD=''
  export FAKE_PR_901_NAMESPACE_ABSENT=false
  unset FAKE_PR_901_RECHECK_HEAD FAKE_PR_901_RECHECK_REQUESTED_HEAD
  export FAKE_NAMESPACE_SNAPSHOT_ERROR=false
  export FAKE_NAMESPACE_SNAPSHOT_RECHECK_ERROR=false
  export FAKE_NAMESPACE_SNAPSHOT_PARSE_FAIL=false
  export FAKE_NAMESPACE_SNAPSHOT_RECHECK_PARSE_FAIL=false
  export FAKE_ANNOTATE_ERROR=false
  export FAKE_ANNOTATE_CONFIRMATION_ERROR=false
  export FAKE_ANNOTATE_NAMESPACE_ABSENT_AFTER_FAILURE=false
  export FAKE_REQUESTED_HEAD_READ_ERROR=false
  export FAKE_REQUESTED_HEAD_NOT_FOUND=false
  export FAKE_REQUESTED_HEAD_RECHECK_ERROR=false
  export FAKE_DELETE_FAIL=false
  export FAKE_DELETE_FAIL_NAMESPACE=''
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
  export FAKE_IDENTITY_REQUEST_FAIL=false
  export FAKE_IDENTITY_WAIT_FAIL=false
  export FAKE_IDENTITY_DELETE_FAIL=false
  export FAKE_NAMESPACE_LIST_ERROR=false
  export FAKE_RUNTIME_LOOKUP_ERROR=false
  export FAKE_RUNTIME_LOOKUP_IDENTITY=''
  export FAKE_RUNTIME_NAMESPACE_PRESENT=true
  export FAKE_RUNTIME_NAMESPACE_UID=''
  export FAKE_RUNTIME_NAMESPACE_UID_MISSING=false
  export FAKE_RUNTIME_NAMESPACE_JSON_PARSE_FAIL=false
  export FAKE_RUNTIME_PREVIEW_LABEL=true
  export FAKE_RUNTIME_PR_NUMBER_LABEL=''
  export FAKE_RUNTIME_DEV_DEMO_LABEL=true
  export FAKE_RUNTIME_ENVIRONMENT_CLASS=dev-demo-cluster
  export FAKE_RUNTIME_NAMESPACE_ABSENT_ON_DELETE=false
  export FAKE_RUNTIME_DELETE_CURRENT_UID=''
  export FAKE_RUNTIME_DELETE_ERROR=false
  export FAKE_RUNTIME_DELETE_ERROR_NAMESPACE=''
  export FAKE_RUNTIME_WAIT_ERROR=false
  export FAKE_RUNTIME_RECHECK_ERROR=false
  export FAKE_RUNTIME_NAMESPACE_PRESENT_AFTER_WAIT=true
  export FAKE_RECORD_RUNTIME_CHECK=false
  export FAKE_IDENTITY_JSON='{"apiVersion":"platform.firemud.dev/v1alpha1","kind":"HostedEnvironmentIdentity","metadata":{"namespace":"firemud-system","name":"pr-101"}}'
  export HOSTED_IDENTITY_MODE=standalone
  unset PREVIEW_NAMESPACE_DELETE_TIMEOUT_SECONDS PREVIEW_DELETE_TIMEOUT
  export FAKE_ELIGIBILITY_OUTPUT=''
  export PREVIEW_ELIGIBILITY_SCRIPT="$ROOT_DIR/dev-tools/hosted/preview/preview-eligibility.py"
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
export FAKE_OPEN_PRIORITY_ROWS="901\t${priority_candidate_head}\texample/FireMUD\thuman\tdevelop\topen\t${priority_labels_base64}\n"
export FAKE_NAMESPACE_ROWS='2026-01-01T00:00:00Z|pr-900|900|2026-01-01T00:00:00Z|head-900|image-900\n2026-01-02T00:00:00Z|pr-101|101|2026-01-02T00:00:00Z|head-101|image-101\n'
if ! bash "$ALLOCATOR" pr-900 2 900 "$FAKE_TARGET_HEAD"; then
  echo "existing ordinary preview was blocked by an unsatisfied priority PR" >&2
  exit 1
fi
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
export FAKE_OPEN_PRIORITY_ROWS="901\t${priority_candidate_head}\texample/FireMUD\thuman\tdevelop\topen\t${priority_labels_base64}\n"
if bash "$ALLOCATOR" pr-900 3 900 "$FAKE_TARGET_HEAD"; then
  echo "ordinary allocation did not yield to an unsatisfied priority PR" >&2
  exit 1
fi
test ! -e "$FAKE_DELETE_LOG"

for ineligible_priority_row in \
  "901\thead-901\tother/FireMUD\thuman\tdevelop\topen\t${priority_labels_base64}\n" \
  "901\thead-901\tother/FireMUD\thuman\tdevelop\topen\t${invalid_json_labels_base64}\n" \
  "901\t${priority_candidate_head}\texample/FireMUD\tdependabot[bot]\tdevelop\topen\t${priority_labels_base64}\n" \
  "901\t${priority_candidate_head}\texample/FireMUD\thuman\tfeature/stack\topen\t${priority_labels_base64}\n"
do
  reset_case
  export FAKE_TARGET_PRIORITY=false
  export FAKE_OPEN_PRIORITY_ROWS="$ineligible_priority_row"
  bash "$ALLOCATOR" pr-900 3 900 "$FAKE_TARGET_HEAD"
  test ! -e "$FAKE_DELETE_LOG"
done

reset_case
export FAKE_TARGET_PRIORITY=false
export FAKE_OPEN_PRIORITY_ROWS="901\t${priority_candidate_head}\texample/FireMUD\thuman\tdevelop\topen\t${adversarial_labels_base64}\n"
bash "$ALLOCATOR" pr-900 3 900 "$FAKE_TARGET_HEAD"
test ! -e "$FAKE_DELETE_LOG"

reset_case
export FAKE_TARGET_PRIORITY=false
export FAKE_OPEN_PRIORITY_ROWS="901\t${priority_candidate_head}\texample/FireMUD\thuman\tdevelop\topen\t${invalid_json_labels_base64}\n"
if bash "$ALLOCATOR" pr-900 3 900 "$FAKE_TARGET_HEAD"; then
  echo "ordinary allocation did not fail closed on malformed priority labels" >&2
  exit 1
fi
test ! -e "$FAKE_DELETE_LOG"

reset_case
export FAKE_TARGET_PRIORITY=false
export FAKE_PRIORITY_QUERY_FAIL=true
if bash "$ALLOCATOR" pr-900 3 900 "$FAKE_TARGET_HEAD"; then
  echo "ordinary allocation did not fail closed when priority query failed" >&2
  exit 1
fi

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
    metadata) export FAKE_TARGET_JQ_QUERY_FAIL=true ;;
  esac
  target_contract_output="$TEMP_DIR/target-${target_contract_case}.output"
  if bash "$ALLOCATOR" pr-900 3 900 "${expected_target_head:-$FAKE_TARGET_HEAD}" \
    >"$target_contract_output" 2>&1; then
    echo "allocator accepted invalid live target ${target_contract_case} metadata" >&2
    exit 1
  fi
  if [[ "$target_contract_case" == metadata ]]; then
    grep -Fxq \
      'Refusing capacity action for target PR #900: current metadata is unavailable' \
      "$target_contract_output"
  fi
  test ! -e "$FAKE_DELETE_LOG"
  unset expected_target_head
done

reset_case
export FAKE_TARGET_PRIORITY=false
export FAKE_OPEN_PRIORITY_ROWS="901\t${priority_candidate_head}\texample/FireMUD\thuman\tdevelop\topen\t${priority_labels_base64}\n"
export PREVIEW_ELIGIBILITY_SCRIPT="$TEMP_DIR/eligibility-fail.py"
if bash "$ALLOCATOR" pr-900 3 900 "$FAKE_TARGET_HEAD"; then
  echo "ordinary allocation did not fail closed when eligibility evaluation failed" >&2
  exit 1
fi

reset_case
export FAKE_TARGET_PRIORITY=false
export FAKE_OPEN_PRIORITY_ROWS="901\t${priority_candidate_head}\texample/FireMUD\thuman\tdevelop\topen\t${adversarial_priority_labels_base64}\n"
if bash "$ALLOCATOR" pr-900 3 900 "$FAKE_TARGET_HEAD"; then
  echo "ordinary allocation ignored a priority label alongside quoted and backslashed label data" >&2
  exit 1
fi
test ! -e "$FAKE_DELETE_LOG"

reset_case
export FAKE_NAMESPACE_ROWS='pr-101\t101\n'
export FAKE_PRUNE_METADATA="open\tfeature/stack\thuman\t${adversarial_labels_base64}\n"
bash "$PRUNER" --apply
grep -qx 'pr-101 pr-101' "$FAKE_DELETE_LOG"

# Scheduled terminal cleanup may retire an existing identity only through the
# explicit hosted-controller mode. Runtime deletion, retirement request, the
# terminal status wait, and CR deletion remain one ordered sequence.
reset_case
export FAKE_NAMESPACE_ROWS='pr-101\t101\n'
export FAKE_PRUNE_METADATA="open\tfeature/stack\thuman\t${adversarial_labels_base64}\n"
export HOSTED_IDENTITY_MODE=hosted-controller
export FAKE_RUNTIME_NAMESPACE_PRESENT=false
export FAKE_RECORD_RUNTIME_CHECK=true
bash "$PRUNER" --apply --retire-terminal-identities
test "$(<"$FAKE_OPERATION_SEQUENCE")" = $'runtime-delete\nruntime-check\nidentity-request\nidentity-wait\nidentity-delete'
grep -qx 'pr-101 Retired' "$FAKE_IDENTITY_REQUEST_LOG"
grep -qx -- '--retired pr-101 600' "$FAKE_IDENTITY_WAIT_LOG"
grep -Fqx -- '-n firemud-system get hostedenvironmentidentity pr-101 --ignore-not-found -o json' \
  "$FAKE_IDENTITY_LOG"
grep -Fqx -- '-n firemud-system delete hostedenvironmentidentity pr-101 --ignore-not-found --wait=true --timeout=180s' \
  "$FAKE_IDENTITY_LOG"

reset_case
export FAKE_NAMESPACE_ROWS=$'pr-101\t101\npr-102\t102\n'
export FAKE_PRUNE_METADATA=$'open\tfeature/stack\thuman\t'"${adversarial_labels_base64}"$'\n'
export FAKE_PR_102_PRIORITY=false
export FAKE_PRUNE_MULTI_TEST=true
export HOSTED_IDENTITY_MODE=hosted-controller
export FAKE_RUNTIME_NAMESPACE_PRESENT=false
export FAKE_RECORD_RUNTIME_CHECK=true
export FAKE_IDENTITY_LOOKUP_FAIL_NAMESPACE=pr-101
export FAKE_IDENTITY_JSON='{"apiVersion":"platform.firemud.dev/v1alpha1","kind":"HostedEnvironmentIdentity","metadata":{"namespace":"firemud-system","name":"pr-102"}}'
if bash "$PRUNER" --apply --retire-terminal-identities >"$TEMP_DIR/multiple-retire.out" 2>&1; then
  echo "pruner suppressed an aggregate hosted identity retirement failure" >&2
  exit 1
fi
grep -Fqx 'pr-101 pr-101' "$FAKE_DELETE_LOG"
grep -Fqx 'pr-102 pr-102' "$FAKE_DELETE_LOG"
test "$(<"$FAKE_OPERATION_SEQUENCE")" = $'runtime-delete\nruntime-check\nruntime-delete\nruntime-check\nidentity-request\nidentity-wait\nidentity-delete'
grep -Fqx '0 hosted runtime deletion(s) and 1 hosted identity retirement(s) failed; stale cleanup is incomplete.' \
  "$TEMP_DIR/multiple-retire.out"

for identity_failure in request wait delete; do
  reset_case
  export FAKE_NAMESPACE_ROWS='pr-101\t101\n'
  export FAKE_PRUNE_METADATA="open\tfeature/stack\thuman\t${adversarial_labels_base64}\n"
  export HOSTED_IDENTITY_MODE=hosted-controller
  export FAKE_RUNTIME_NAMESPACE_PRESENT=false
  export FAKE_RECORD_RUNTIME_CHECK=true
  case "$identity_failure" in
    request) export FAKE_IDENTITY_REQUEST_FAIL=true; expected_sequence=$'runtime-delete\nruntime-check\nidentity-request' ;;
    wait) export FAKE_IDENTITY_WAIT_FAIL=true; expected_sequence=$'runtime-delete\nruntime-check\nidentity-request\nidentity-wait' ;;
    delete) export FAKE_IDENTITY_DELETE_FAIL=true; expected_sequence=$'runtime-delete\nruntime-check\nidentity-request\nidentity-wait\nidentity-delete' ;;
  esac
  if bash "$PRUNER" --apply --retire-terminal-identities >"$TEMP_DIR/identity-${identity_failure}.out" 2>&1; then
    echo "pruner suppressed a ${identity_failure} failure" >&2
    exit 1
  fi
  test "$(<"$FAKE_OPERATION_SEQUENCE")" = "$expected_sequence"
  grep -Fqx '0 hosted runtime deletion(s) and 1 hosted identity retirement(s) failed; stale cleanup is incomplete.' \
    "$TEMP_DIR/identity-${identity_failure}.out"
done

reset_case
export FAKE_NAMESPACE_ROWS='pr-101\t101\n'
export FAKE_PRUNE_METADATA="open\tfeature/stack\thuman\t${adversarial_labels_base64}\n"
export HOSTED_IDENTITY_MODE=hosted-controller
export FAKE_IDENTITY_JSON=''
export FAKE_RUNTIME_NAMESPACE_PRESENT=false
export FAKE_RECORD_RUNTIME_CHECK=true
bash "$PRUNER" --apply --retire-terminal-identities
test "$(<"$FAKE_OPERATION_SEQUENCE")" = $'runtime-delete\nruntime-check'
test ! -e "$FAKE_IDENTITY_REQUEST_LOG"
test ! -e "$FAKE_IDENTITY_WAIT_LOG"

reset_case
export FAKE_NAMESPACE_ROWS='pr-101\t101\n'
export FAKE_PRUNE_METADATA="open\tfeature/stack\thuman\t${adversarial_labels_base64}\n"
if bash "$PRUNER" --apply --retire-terminal-identities 2>"$TEMP_DIR/retire-mode.error"; then
  echo "terminal identity retirement was allowed outside hosted-controller mode" >&2
  exit 1
fi
grep -Fq 'HOSTED_IDENTITY_MODE=hosted-controller' "$TEMP_DIR/retire-mode.error"
test ! -e "$FAKE_DELETE_LOG"

reset_case
export FAKE_NAMESPACE_ROWS='pr-101\t101\n'
export FAKE_PRUNE_METADATA="open\tfeature/stack\thuman\t${adversarial_labels_base64}\n"
export HOSTED_IDENTITY_MODE=hosted-controller
bash "$PRUNER" --apply
test ! -e "$FAKE_IDENTITY_LOG"
test ! -e "$FAKE_IDENTITY_REQUEST_LOG"
test ! -e "$FAKE_IDENTITY_WAIT_LOG"

for invalid_preview_row in \
  'production\t101' \
  'pr-101\t102' \
  'pr-101\t001' \
  'pr-01\t1'; do
  reset_case
  export FAKE_NAMESPACE_ROWS="${invalid_preview_row}\n"
  bash "$PRUNER" --apply
  if [[ -e "$FAKE_DELETE_LOG" ]]; then
    echo "pruner invoked deletion for noncanonical or mismatched preview identity" >&2
    exit 1
  fi
done

reset_case
bash "$PRUNER" --delete-runtime pr-101
grep -qx 'pr-101 pr-101' "$FAKE_DELETE_LOG"
grep -qx '600' "$FAKE_DELETE_TIMEOUT_LOG"
test ! -e "$FAKE_RUNTIME_KUBECTL_LOG"

for invalid_runtime_namespace in dev production pr-0 pr-01; do
  reset_case
  if bash "$PRUNER" --delete-runtime "$invalid_runtime_namespace"; then
    echo "pruner accepted noncanonical direct runtime namespace ${invalid_runtime_namespace}" >&2
    exit 1
  fi
  test ! -e "$FAKE_DELETE_LOG"
done

for invalid_preview_delete_timeout in 0 invalid 3601 99999999999999999999; do
  reset_case
  export PREVIEW_DELETE_TIMEOUT="$invalid_preview_delete_timeout"
  if bash "$PRUNER" --delete-runtime pr-101; then
    echo "pruner accepted an invalid PREVIEW_DELETE_TIMEOUT" >&2
    exit 1
  fi
  test ! -e "$FAKE_DELETE_LOG"
done

for valid_preview_delete_timeout in 1 3600; do
  reset_case
  export PREVIEW_DELETE_TIMEOUT="$valid_preview_delete_timeout"
  bash "$PRUNER" --delete-runtime pr-101
  grep -qx 'pr-101 pr-101' "$FAKE_DELETE_LOG"
  grep -qx "$valid_preview_delete_timeout" "$FAKE_DELETE_TIMEOUT_LOG"
done

reset_case
export FAKE_DELETE_FAIL=true
if bash "$PRUNER" --delete-runtime pr-101; then
  echo "runtime deletion suppressed the shared deletion helper failure" >&2
  exit 1
fi

for invalid_hosted_identity in \
  'production production' \
  'pr-101 pr-102' \
  'dev dev-demo' \
  'pr-01 pr-01'; do
  reset_case
  read -r invalid_namespace invalid_release <<<"$invalid_hosted_identity"
  if bash "$DELETE_HOSTED_NAMESPACE" "$invalid_namespace" "$invalid_release"; then
    echo "hosted deletion accepted noncanonical namespace/release identity" >&2
    exit 1
  fi
  test ! -e "$FAKE_HELM_LOG"
  test ! -e "$FAKE_RUNTIME_KUBECTL_LOG"
done

reset_case
export FAKE_RUNTIME_NAMESPACE_PRESENT=false
bash "$DELETE_HOSTED_NAMESPACE" dev dev
grep -qx 'get namespace dev --ignore-not-found -o json' "$FAKE_RUNTIME_KUBECTL_LOG"
test ! -e "$FAKE_HELM_LOG"
test "$(wc -l < "$FAKE_RUNTIME_KUBECTL_LOG")" -eq 1

reset_case
export FAKE_RUNTIME_NAMESPACE_PRESENT_AFTER_WAIT=false
bash "$DELETE_HOSTED_NAMESPACE" dev dev
test ! -e "$FAKE_HELM_LOG"
grep -qx 'delete --raw /api/v1/namespaces/dev -f -' \
  "$FAKE_RUNTIME_KUBECTL_LOG"
grep -qx 'uid-dev' "$FAKE_RUNTIME_DELETE_UID_LOG"
grep -qx 'wait --for=delete namespace/dev --timeout=180s' "$FAKE_RUNTIME_KUBECTL_LOG"

reset_case
export FAKE_RUNTIME_LOOKUP_ERROR=true
if bash "$DELETE_HOSTED_NAMESPACE" pr-101 pr-101; then
  echo "hosted deletion treated an initial namespace lookup error as absence" >&2
  exit 1
fi
test ! -e "$FAKE_HELM_LOG"
test "$(wc -l < "$FAKE_RUNTIME_KUBECTL_LOG")" -eq 1

reset_case
export FAKE_RUNTIME_LOOKUP_IDENTITY=pr-102
if bash "$DELETE_HOSTED_NAMESPACE" pr-101 pr-101; then
  echo "hosted deletion accepted an unexpected namespace lookup identity" >&2
  exit 1
fi
test ! -e "$FAKE_HELM_LOG"
test ! -e "$FAKE_RUNTIME_DELETE_UID_LOG"

for invalid_runtime_metadata in \
  FAKE_RUNTIME_NAMESPACE_JSON_PARSE_FAIL \
  FAKE_RUNTIME_NAMESPACE_UID_MISSING \
  FAKE_RUNTIME_PREVIEW_LABEL \
  FAKE_RUNTIME_PR_NUMBER_LABEL; do
  reset_case
  case "$invalid_runtime_metadata" in
    FAKE_RUNTIME_NAMESPACE_JSON_PARSE_FAIL) export "$invalid_runtime_metadata"=true ;;
    FAKE_RUNTIME_NAMESPACE_UID_MISSING) export "$invalid_runtime_metadata"=true ;;
    FAKE_RUNTIME_PREVIEW_LABEL) export "$invalid_runtime_metadata"=false ;;
    FAKE_RUNTIME_PR_NUMBER_LABEL) export "$invalid_runtime_metadata"=102 ;;
  esac
  if bash "$DELETE_HOSTED_NAMESPACE" pr-101 pr-101; then
    echo "hosted deletion accepted invalid preview metadata from ${invalid_runtime_metadata}" >&2
    exit 1
  fi
  test ! -e "$FAKE_HELM_LOG"
  test ! -e "$FAKE_RUNTIME_DELETE_UID_LOG"
done

for invalid_dev_metadata in FAKE_RUNTIME_DEV_DEMO_LABEL FAKE_RUNTIME_ENVIRONMENT_CLASS; do
  reset_case
  if [[ "$invalid_dev_metadata" == FAKE_RUNTIME_DEV_DEMO_LABEL ]]; then
    export "$invalid_dev_metadata"=false
  else
    export "$invalid_dev_metadata"=pr-preview
  fi
  if bash "$DELETE_HOSTED_NAMESPACE" dev dev; then
    echo "hosted deletion accepted invalid dev-demo metadata from ${invalid_dev_metadata}" >&2
    exit 1
  fi
  test ! -e "$FAKE_HELM_LOG"
  test ! -e "$FAKE_RUNTIME_DELETE_UID_LOG"
done

reset_case
export FAKE_RUNTIME_NAMESPACE_UID=uid-pr-101-original
export FAKE_RUNTIME_DELETE_CURRENT_UID=uid-pr-101-replacement
if bash "$DELETE_HOSTED_NAMESPACE" pr-101 pr-101; then
  echo "hosted deletion suppressed a namespace UID precondition conflict" >&2
  exit 1
fi
grep -qx 'uid-pr-101-original' "$FAKE_RUNTIME_DELETE_UID_LOG"
test ! -e "$FAKE_HELM_LOG"
if grep -q '^wait ' "$FAKE_RUNTIME_KUBECTL_LOG"; then
  echo "hosted deletion waited after a namespace UID precondition conflict" >&2
  exit 1
fi

reset_case
export FAKE_RUNTIME_NAMESPACE_ABSENT_ON_DELETE=true
bash "$DELETE_HOSTED_NAMESPACE" pr-101 pr-101
grep -qx 'uid-pr-101' "$FAKE_RUNTIME_DELETE_UID_LOG"
test ! -e "$FAKE_HELM_LOG"
test "$(grep -Fc 'get namespace pr-101 --ignore-not-found -o name' "$FAKE_RUNTIME_KUBECTL_LOG")" -eq 1
if grep -q '^wait ' "$FAKE_RUNTIME_KUBECTL_LOG"; then
  echo "hosted deletion waited after the namespace became absent during delete" >&2
  exit 1
fi

reset_case
export FAKE_NAMESPACE_ROWS=$'pr-101\t101\npr-102\t102\n'
export FAKE_PRUNE_METADATA=$'open\tfeature/stack\thuman\t'"${adversarial_labels_base64}"$'\n'
export FAKE_PR_102_PRIORITY=false
export FAKE_PRUNE_MULTI_TEST=true
export FAKE_DELETE_FAIL_NAMESPACE=pr-101
export HOSTED_IDENTITY_MODE=hosted-controller
export FAKE_RUNTIME_NAMESPACE_PRESENT=false
export FAKE_RECORD_RUNTIME_CHECK=true
export FAKE_IDENTITY_JSON='{"apiVersion":"platform.firemud.dev/v1alpha1","kind":"HostedEnvironmentIdentity","metadata":{"namespace":"firemud-system","name":"pr-102"}}'
if bash "$PRUNER" --apply --retire-terminal-identities >"$TEMP_DIR/multiple-runtime-delete.out" 2>&1; then
  echo "pruner suppressed an aggregate hosted runtime deletion failure" >&2
  exit 1
fi
grep -Fqx 'pr-101 pr-101' "$FAKE_DELETE_LOG"
grep -Fqx 'pr-102 pr-102' "$FAKE_DELETE_LOG"
test "$(wc -l < "$FAKE_DELETE_LOG")" -eq 2
test "$(grep -Fc 'runtime-delete' "$FAKE_OPERATION_SEQUENCE")" -eq 2
test "$(grep -Fc 'runtime-check' "$FAKE_OPERATION_SEQUENCE")" -eq 1
grep -Fqx 'pr-102 Retired' "$FAKE_IDENTITY_REQUEST_LOG"
if grep -Fq 'pr-101 Retired' "$FAKE_IDENTITY_REQUEST_LOG"; then
  echo "pruner retired an identity after runtime deletion failed" >&2
  exit 1
fi
grep -Fqx '1 hosted runtime deletion(s) and 0 hosted identity retirement(s) failed; stale cleanup is incomplete.' \
  "$TEMP_DIR/multiple-runtime-delete.out"

reset_case
export FAKE_RUNTIME_DELETE_ERROR=true
if bash "$DELETE_HOSTED_NAMESPACE" pr-101 pr-101; then
  echo "hosted deletion suppressed a namespace delete failure" >&2
  exit 1
fi
grep -qx 'delete --raw /api/v1/namespaces/pr-101 -f -' \
  "$FAKE_RUNTIME_KUBECTL_LOG"
grep -qx 'uid-pr-101' "$FAKE_RUNTIME_DELETE_UID_LOG"
test ! -e "$FAKE_HELM_LOG"
if grep -q '^wait ' "$FAKE_RUNTIME_KUBECTL_LOG"; then
  echo "hosted deletion continued after a namespace delete failure" >&2
  exit 1
fi

reset_case
export FAKE_RUNTIME_NAMESPACE_PRESENT_AFTER_WAIT=false
bash "$DELETE_HOSTED_NAMESPACE" pr-101 pr-101
test ! -e "$FAKE_HELM_LOG"
grep -qx 'get namespace pr-101 --ignore-not-found -o json' "$FAKE_RUNTIME_KUBECTL_LOG"
test "$(grep -Fc 'get namespace pr-101 --ignore-not-found -o name' "$FAKE_RUNTIME_KUBECTL_LOG")" -eq 1
grep -qx 'uid-pr-101' "$FAKE_RUNTIME_DELETE_UID_LOG"
grep -qx 'wait --for=delete namespace/pr-101 --timeout=180s' "$FAKE_RUNTIME_KUBECTL_LOG"

reset_case
export FAKE_RUNTIME_WAIT_ERROR=true
export FAKE_RUNTIME_NAMESPACE_PRESENT_AFTER_WAIT=false
bash "$DELETE_HOSTED_NAMESPACE" pr-101 pr-101

reset_case
export FAKE_RUNTIME_WAIT_ERROR=true
if bash "$DELETE_HOSTED_NAMESPACE" pr-101 pr-101; then
  echo "hosted deletion suppressed a wait failure while the namespace remained present" >&2
  exit 1
fi

reset_case
export FAKE_RUNTIME_NAMESPACE_PRESENT_AFTER_WAIT=false
export FAKE_RUNTIME_RECHECK_ERROR=true
if bash "$DELETE_HOSTED_NAMESPACE" pr-101 pr-101; then
  echo "hosted deletion suppressed a final namespace lookup failure" >&2
  exit 1
fi

for invalid_delete_timeout in 0 invalid 3601 99999999999999999999; do
  reset_case
  export PREVIEW_NAMESPACE_DELETE_TIMEOUT_SECONDS="$invalid_delete_timeout"
  if bash "$DELETE_HOSTED_NAMESPACE" dev dev; then
    echo "hosted deletion accepted an unbounded or invalid timeout" >&2
    exit 1
  fi
  test ! -e "$FAKE_RUNTIME_KUBECTL_LOG"
done

for valid_delete_timeout in 1 3600; do
  reset_case
  export PREVIEW_NAMESPACE_DELETE_TIMEOUT_SECONDS="$valid_delete_timeout"
  export FAKE_RUNTIME_NAMESPACE_PRESENT_AFTER_WAIT=false
  bash "$DELETE_HOSTED_NAMESPACE" dev dev
  grep -qx \
    "wait --for=delete namespace/dev --timeout=${valid_delete_timeout}s" \
    "$FAKE_RUNTIME_KUBECTL_LOG"
done

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
export FAKE_NAMESPACE_LIST_ERROR=true
if bash "$PRUNER" --apply >"$TEMP_DIR/prune-list-error.out" 2>&1; then
  echo "prune treated a namespace-listing failure as an empty preview set" >&2
  exit 1
fi
grep -Fqx 'Unable to list current preview namespaces; refusing stale cleanup' \
  "$TEMP_DIR/prune-list-error.out"
test ! -e "$FAKE_DELETE_LOG"

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
  $'eligible=false\nreason=dependency-bot\neligible=false' \
  $'eligible=false\nreason=dependency-bot\nextra=value' \
  $'reason=dependency-bot\neligible=false' \
  $'eligible=maybe\nreason=dependency-bot' \
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

for non_authoritative_reason in malformed-label-metadata preview-paused future-eligibility-reason; do
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

for authoritative_prune_reason in dependency-bot unsupported-base-branch pr-not-open; do
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

trusted_workflow="$ROOT_DIR/.github/workflows/hosted-identity-request.yml"
reconciler_workflow="$ROOT_DIR/.github/workflows/preview-reconciler.yml"
eligibility_script="$ROOT_DIR/dev-tools/hosted/preview/preview-eligibility.py"
RECONCILER_RUN="$TEMP_DIR/preview-reconciler.sh"
extract_workflow_step_run \
  "$reconciler_workflow" \
  "Dispatch preview deploys for drifted PRs" \
  "$RECONCILER_RUN"
grep -Fq 'set -euo pipefail' "$RECONCILER_RUN"
grep -Fq -- \
  '--jq '\''first(.[] | select(.status == "queued" or .status == "in_progress" or .status == "waiting" or .status == "pending") | .databaseId) // empty'\''' \
  "$RECONCILER_RUN"
if grep -Fq '| head -n 1' "$RECONCILER_RUN"; then
  echo "reconciler must select an active preview run without a pipefail-unsafe head" >&2
  exit 1
fi

reset_case
reconciler_valid_output="$TEMP_DIR/reconciler-valid.out"
(
  cd "$ROOT_DIR"
  FAKE_OPEN_PRIORITY_ROWS="1\t901\tfeature-901\thead-901\thuman\tdevelop\topen\tfalse\t${adversarial_labels_base64}\n" \
    FAKE_PR_901_HEAD=head-901 \
    FAKE_PR_901_REQUESTED_HEAD=head-901 \
    PREVIEW_MAX_ACTIVE=3 \
    bash "$RECONCILER_RUN"
) > "$reconciler_valid_output"
if ! grep -qx 'Preview pr-901 already aligned to head-901' "$reconciler_valid_output"; then
  echo "reconciler did not preserve an aligned preview with transported labels" >&2
  sed 's/^/reconciler output: /' "$reconciler_valid_output" >&2
  exit 1
fi
test "$(<"$FAKE_NAMESPACE_SNAPSHOT_CALLS")" -eq 1

reset_case
reconciler_empty_deployed_output="$TEMP_DIR/reconciler-empty-deployed.out"
(
  cd "$ROOT_DIR"
  FAKE_OPEN_PRIORITY_ROWS="1\t901\tfeature-901\thead-901\thuman\tdevelop\topen\tfalse\t${adversarial_labels_base64}\n" \
    FAKE_PR_901_HEAD='' \
    FAKE_PR_901_REQUESTED_HEAD=head-901 \
    PREVIEW_MAX_ACTIVE=3 \
    bash "$RECONCILER_RUN"
) > "$reconciler_empty_deployed_output"
grep -qx 'Dispatching preview deploy for PR #901 (head-901) on ref feature-901' \
  "$reconciler_empty_deployed_output"
test ! -e "$FAKE_ANNOTATE_LOG"
test "$(<"$FAKE_NAMESPACE_SNAPSHOT_CALLS")" -eq 1

reset_case
reconciler_namespace_absent_output="$TEMP_DIR/reconciler-namespace-absent.out"
(
  cd "$ROOT_DIR"
  FAKE_OPEN_PRIORITY_ROWS="1\t901\tfeature-901\thead-901\thuman\tdevelop\topen\tfalse\t${adversarial_labels_base64}\n" \
    FAKE_PR_901_HEAD=head-901 \
    FAKE_PR_901_NAMESPACE_ABSENT=true \
    PREVIEW_MAX_ACTIVE=3 \
    bash "$RECONCILER_RUN"
) > "$reconciler_namespace_absent_output"
grep -qx 'Dispatching preview deploy for PR #901 (head-901) on ref feature-901' \
  "$reconciler_namespace_absent_output"
test ! -e "$FAKE_ANNOTATE_LOG"
test "$(<"$FAKE_NAMESPACE_SNAPSHOT_CALLS")" -eq 1
grep -Fqx 'get namespace pr-901 --ignore-not-found -o json' "$FAKE_NAMESPACE_SNAPSHOT_LOG"

reset_case
reconciler_namespace_error_output="$TEMP_DIR/reconciler-namespace-error.out"
if (
  cd "$ROOT_DIR"
  FAKE_OPEN_PRIORITY_ROWS="1\t901\tfeature-901\thead-901\thuman\tdevelop\topen\tfalse\t${adversarial_labels_base64}\n" \
    FAKE_PR_901_HEAD=head-901 \
    FAKE_NAMESPACE_SNAPSHOT_ERROR=true \
    PREVIEW_MAX_ACTIVE=3 \
    bash -e "$RECONCILER_RUN"
) > "$reconciler_namespace_error_output" 2>&1; then
  echo "reconciler suppressed an initial namespace snapshot API error" >&2
  exit 1
fi
grep -q 'Unable to inspect namespace pr-901' "$reconciler_namespace_error_output"
test ! -e "$FAKE_ANNOTATE_LOG"
test ! -e "$FAKE_DISPATCH_LOG"
test "$(<"$FAKE_NAMESPACE_SNAPSHOT_CALLS")" -eq 1

reset_case
reconciler_namespace_parse_output="$TEMP_DIR/reconciler-namespace-parse.out"
(
  cd "$ROOT_DIR"
  FAKE_OPEN_PRIORITY_ROWS="0\t901\tfeature-901\thead-901\thuman\tdevelop\topen\ttrue\t${priority_labels_base64}\n1\t101\tfeature-101\tnew-head-101\thuman\tdevelop\topen\tfalse\t${adversarial_labels_base64}\n" \
    FAKE_NAMESPACE_SNAPSHOT_PARSE_FAIL=true \
    PREVIEW_MAX_ACTIVE=3 \
    bash "$RECONCILER_RUN"
) > "$reconciler_namespace_parse_output" 2>&1
grep -qx 'Skipping PR #901: unable to parse namespace pr-901 snapshot.' \
  "$reconciler_namespace_parse_output"
grep -qx 'Dispatching preview deploy for PR #101 (new-head-101) on ref feature-101' \
  "$reconciler_namespace_parse_output"
grep -Fq \
  'actions/workflows/preview.yml/dispatches -f ref=feature-101' \
  "$FAKE_DISPATCH_LOG"

reset_case
reconciler_namespace_recheck_parse_output="$TEMP_DIR/reconciler-namespace-recheck-parse.out"
(
  cd "$ROOT_DIR"
  FAKE_OPEN_PRIORITY_ROWS="0\t901\tfeature-901\thead-901\thuman\tdevelop\topen\ttrue\t${priority_labels_base64}\n1\t101\tfeature-101\tnew-head-101\thuman\tdevelop\topen\tfalse\t${adversarial_labels_base64}\n" \
    FAKE_PR_901_HEAD=head-901 \
    FAKE_PR_901_REQUESTED_HEAD='' \
    FAKE_NAMESPACE_SNAPSHOT_RECHECK_PARSE_FAIL=true \
    PREVIEW_MAX_ACTIVE=3 \
    bash "$RECONCILER_RUN"
) > "$reconciler_namespace_recheck_parse_output" 2>&1
grep -qx 'Skipping PR #901: unable to parse namespace pr-901 recheck.' \
  "$reconciler_namespace_recheck_parse_output"
grep -qx 'Dispatching preview deploy for PR #101 (new-head-101) on ref feature-101' \
  "$reconciler_namespace_recheck_parse_output"
grep -Fq \
  'actions/workflows/preview.yml/dispatches -f ref=feature-101' \
  "$FAKE_DISPATCH_LOG"

reset_case
reconciler_missing_requested_output="$TEMP_DIR/reconciler-missing-requested.out"
(
  cd "$ROOT_DIR"
  FAKE_OPEN_PRIORITY_ROWS="1\t901\tfeature-901\thead-901\thuman\tdevelop\topen\tfalse\t${adversarial_labels_base64}\n" \
    FAKE_PR_901_HEAD=head-901 \
    FAKE_PR_901_REQUESTED_HEAD='' \
    PREVIEW_MAX_ACTIVE=3 \
    bash "$RECONCILER_RUN"
) > "$reconciler_missing_requested_output"
grep -qx 'Repaired missing requested head for aligned preview pr-901' \
  "$reconciler_missing_requested_output"
grep -Fqx \
  'annotate namespace pr-901 firemud.dev/requested-preview-head-sha=head-901 --overwrite --resource-version rv-901' \
  "$FAKE_ANNOTATE_LOG"
test "$(<"$FAKE_NAMESPACE_SNAPSHOT_CALLS")" -eq 2
test "$(grep -Fc 'get namespace pr-901 --ignore-not-found -o json' "$FAKE_NAMESPACE_SNAPSHOT_LOG")" -eq 2
test ! -e "$FAKE_DISPATCH_LOG"

reset_case
reconciler_stale_requested_output="$TEMP_DIR/reconciler-stale-requested.out"
(
  cd "$ROOT_DIR"
  FAKE_OPEN_PRIORITY_ROWS="1\t901\tfeature-901\thead-901\thuman\tdevelop\topen\tfalse\t${adversarial_labels_base64}\n" \
    FAKE_PR_901_HEAD=head-901 \
    FAKE_PR_901_REQUESTED_HEAD=stale-head \
    PREVIEW_MAX_ACTIVE=3 \
    bash "$RECONCILER_RUN"
) > "$reconciler_stale_requested_output"
grep -qx 'Repaired stale requested head for aligned preview pr-901' \
  "$reconciler_stale_requested_output"
grep -Fqx \
  'annotate namespace pr-901 firemud.dev/requested-preview-head-sha=head-901 --overwrite --resource-version rv-901' \
  "$FAKE_ANNOTATE_LOG"
test "$(<"$FAKE_NAMESPACE_SNAPSHOT_CALLS")" -eq 2
test "$(grep -Fc 'get namespace pr-901 --ignore-not-found -o json' "$FAKE_NAMESPACE_SNAPSHOT_LOG")" -eq 2
test ! -e "$FAKE_DISPATCH_LOG"

reset_case
reconciler_annotation_deleted_output="$TEMP_DIR/reconciler-annotation-deleted.out"
(
  cd "$ROOT_DIR"
  FAKE_OPEN_PRIORITY_ROWS="1\t901\tfeature-901\thead-901\thuman\tdevelop\topen\tfalse\t${adversarial_labels_base64}\n" \
    FAKE_PR_901_HEAD=head-901 \
    FAKE_PR_901_REQUESTED_HEAD='' \
    FAKE_ANNOTATE_ERROR=true \
    FAKE_ANNOTATE_NAMESPACE_ABSENT_AFTER_FAILURE=true \
    PREVIEW_MAX_ACTIVE=3 \
    bash "$RECONCILER_RUN"
) > "$reconciler_annotation_deleted_output"
grep -qx 'Namespace pr-901 was deleted during requested-head repair; continuing.' \
  "$reconciler_annotation_deleted_output"
grep -Fqx \
  'annotate namespace pr-901 firemud.dev/requested-preview-head-sha=head-901 --overwrite --resource-version rv-901' \
  "$FAKE_ANNOTATE_LOG"
test "$(<"$FAKE_NAMESPACE_SNAPSHOT_CALLS")" -eq 3
test ! -e "$FAKE_DISPATCH_LOG"

reset_case
reconciler_annotation_existing_output="$TEMP_DIR/reconciler-annotation-existing.out"
if (
  cd "$ROOT_DIR"
  FAKE_OPEN_PRIORITY_ROWS="1\t901\tfeature-901\thead-901\thuman\tdevelop\topen\tfalse\t${adversarial_labels_base64}\n" \
    FAKE_PR_901_HEAD=head-901 \
    FAKE_PR_901_REQUESTED_HEAD='' \
    FAKE_ANNOTATE_ERROR=true \
    PREVIEW_MAX_ACTIVE=3 \
    bash "$RECONCILER_RUN"
) > "$reconciler_annotation_existing_output" 2>&1; then
  echo "reconciler continued after annotation failure while namespace still existed" >&2
  exit 1
fi
grep -qx \
  'Unable to annotate namespace pr-901; namespace still exists, refusing preview reconcile.' \
  "$reconciler_annotation_existing_output"
test "$(<"$FAKE_NAMESPACE_SNAPSHOT_CALLS")" -eq 3
test ! -e "$FAKE_DISPATCH_LOG"

reset_case
reconciler_annotation_confirmation_error_output="$TEMP_DIR/reconciler-annotation-confirmation-error.out"
if (
  cd "$ROOT_DIR"
  FAKE_OPEN_PRIORITY_ROWS="1\t901\tfeature-901\thead-901\thuman\tdevelop\topen\tfalse\t${adversarial_labels_base64}\n" \
    FAKE_PR_901_HEAD=head-901 \
    FAKE_PR_901_REQUESTED_HEAD='' \
    FAKE_ANNOTATE_ERROR=true \
    FAKE_ANNOTATE_CONFIRMATION_ERROR=true \
    PREVIEW_MAX_ACTIVE=3 \
    bash -e "$RECONCILER_RUN"
) > "$reconciler_annotation_confirmation_error_output" 2>&1; then
  echo "reconciler suppressed an annotation-failure confirmation error" >&2
  exit 1
fi
grep -qx \
  'Unable to recheck namespace pr-901 after annotation failure; refusing preview reconcile.' \
  "$reconciler_annotation_confirmation_error_output"
test "$(<"$FAKE_NAMESPACE_SNAPSHOT_CALLS")" -eq 3
test ! -e "$FAKE_DISPATCH_LOG"

reset_case
reconciler_namespace_recheck_error_output="$TEMP_DIR/reconciler-namespace-recheck-error.out"
if (
  cd "$ROOT_DIR"
  FAKE_OPEN_PRIORITY_ROWS="1\t901\tfeature-901\thead-901\thuman\tdevelop\topen\tfalse\t${adversarial_labels_base64}\n" \
    FAKE_PR_901_HEAD=head-901 \
    FAKE_PR_901_REQUESTED_HEAD='' \
    FAKE_NAMESPACE_SNAPSHOT_RECHECK_ERROR=true \
    PREVIEW_MAX_ACTIVE=3 \
    bash -e "$RECONCILER_RUN"
) > "$reconciler_namespace_recheck_error_output" 2>&1; then
  echo "reconciler suppressed a namespace recheck API error" >&2
  exit 1
fi
grep -q 'Unable to recheck namespace pr-901 before annotation repair' \
  "$reconciler_namespace_recheck_error_output"
test ! -e "$FAKE_ANNOTATE_LOG"
test ! -e "$FAKE_DISPATCH_LOG"
test "$(<"$FAKE_NAMESPACE_SNAPSHOT_CALLS")" -eq 2

reset_case
reconciler_changed_requested_output="$TEMP_DIR/reconciler-changed-requested.out"
(
  cd "$ROOT_DIR"
  FAKE_OPEN_PRIORITY_ROWS="1\t901\tfeature-901\thead-901\thuman\tdevelop\topen\tfalse\t${adversarial_labels_base64}\n" \
    FAKE_PR_901_HEAD=head-901 \
    FAKE_PR_901_REQUESTED_HEAD='' \
    FAKE_PR_901_RECHECK_REQUESTED_HEAD=raced-head \
    PREVIEW_MAX_ACTIVE=3 \
    bash "$RECONCILER_RUN"
) > "$reconciler_changed_requested_output"
grep -qx \
  'Skipping preview repair for PR #901: requested head changed during annotation repair check.' \
  "$reconciler_changed_requested_output"
test ! -e "$FAKE_ANNOTATE_LOG"
test ! -e "$FAKE_DISPATCH_LOG"
test "$(<"$FAKE_NAMESPACE_SNAPSHOT_CALLS")" -eq 2

reset_case
reconciler_changed_stale_requested_output="$TEMP_DIR/reconciler-changed-stale-requested.out"
(
  cd "$ROOT_DIR"
  FAKE_OPEN_PRIORITY_ROWS="1\t901\tfeature-901\thead-901\thuman\tdevelop\topen\tfalse\t${adversarial_labels_base64}\n" \
    FAKE_PR_901_HEAD=head-901 \
    FAKE_PR_901_REQUESTED_HEAD=stale-head \
    FAKE_PR_901_RECHECK_REQUESTED_HEAD=other-stale-head \
    PREVIEW_MAX_ACTIVE=3 \
    bash "$RECONCILER_RUN"
) > "$reconciler_changed_stale_requested_output"
grep -qx \
  'Skipping preview repair for PR #901: requested head changed during annotation repair check.' \
  "$reconciler_changed_stale_requested_output"
test ! -e "$FAKE_ANNOTATE_LOG"
test ! -e "$FAKE_DISPATCH_LOG"
test "$(<"$FAKE_NAMESPACE_SNAPSHOT_CALLS")" -eq 2

reset_case
reconciler_changed_deployed_output="$TEMP_DIR/reconciler-changed-deployed.out"
(
  cd "$ROOT_DIR"
  FAKE_OPEN_PRIORITY_ROWS="1\t901\tfeature-901\thead-901\thuman\tdevelop\topen\tfalse\t${adversarial_labels_base64}\n" \
    FAKE_PR_901_HEAD=head-901 \
    FAKE_PR_901_REQUESTED_HEAD='' \
    FAKE_PR_901_RECHECK_HEAD=changed-head \
    PREVIEW_MAX_ACTIVE=3 \
    bash "$RECONCILER_RUN"
) > "$reconciler_changed_deployed_output"
grep -qx \
  'Skipping preview repair for PR #901: deployed head changed during annotation repair check.' \
  "$reconciler_changed_deployed_output"
test ! -e "$FAKE_ANNOTATE_LOG"
test ! -e "$FAKE_DISPATCH_LOG"
test "$(<"$FAKE_NAMESPACE_SNAPSHOT_CALLS")" -eq 2

reset_case
malformed_labels_base64="$(printf '%s' '{}' | base64 | tr -d '\n')"
reconciler_malformed_output="$TEMP_DIR/reconciler-malformed.out"
(
  cd "$ROOT_DIR"
  FAKE_OPEN_PRIORITY_ROWS="1\t901\tfeature-901\thead-901\thuman\tdevelop\topen\tfalse\t${malformed_labels_base64}\n" \
    PREVIEW_MAX_ACTIVE=3 \
    bash "$RECONCILER_RUN"
) > "$reconciler_malformed_output" 2>&1
grep -qx 'Skipping PR #901: label metadata could not be decoded as a JSON array.' \
  "$reconciler_malformed_output"
if grep -q '^Dispatching preview deploy' "$reconciler_malformed_output"; then
  echo "reconciler dispatched after malformed label metadata" >&2
  exit 1
fi

TRUSTED_TARGET_RUN="$TEMP_DIR/hosted-identity-target.sh"
extract_workflow_step_run \
  "$trusted_workflow" \
  "Verify workflow source and immutable PR metadata" \
  "$TRUSTED_TARGET_RUN"

run_trusted_target_fixture() {
  local source_artifacts_json="$1"
  local labels_valid="$2"
  local expected_head="$3"
  local output="$4"
  rm -f "$output"
  (
    cd "$ROOT_DIR"
    FAKE_SOURCE_ARTIFACTS_JSON="$source_artifacts_json" \
      FAKE_TARGET_LABELS_VALID="$labels_valid" \
      EVENT_NAME=workflow_run \
      EVENT_ACTION=completed \
      WORKFLOW_RUN_ID=42 \
      WORKFLOW_RUN_HEAD_SHA="$expected_head" \
      EVENT_PR_NUMBER='' \
      EVENT_HEAD_SHA='' \
      INPUT_PR_NUMBER='' \
      INPUT_HEAD_SHA='' \
      INPUT_ACTION='' \
      GITHUB_OUTPUT="$output" \
      bash "$TRUSTED_TARGET_RUN"
  )
}

unsupported_target_output="$TEMP_DIR/trusted-target-unsupported.out"
if (
  cd "$ROOT_DIR"
  env \
    PATH="$TEMP_DIR/bin:$PATH" \
    GH_TOKEN=fake \
    GITHUB_REPOSITORY=example/FireMUD \
    EVENT_NAME=workflow_dispatch \
    EVENT_ACTION='' \
    WORKFLOW_RUN_ID='' \
    WORKFLOW_RUN_HEAD_SHA='' \
    EVENT_PR_NUMBER='' \
    EVENT_HEAD_SHA='' \
    GITHUB_OUTPUT="$unsupported_target_output" \
    bash "$TRUSTED_TARGET_RUN"
) >"$TEMP_DIR/trusted-target-unsupported.stdout" 2>"$TEMP_DIR/trusted-target-unsupported.stderr"; then
  echo "trusted identity target accepted an unsupported event" >&2
  exit 1
fi
grep -Fqx \
  '::error title=Unsupported lifecycle event::Cannot validate hosted identity request for event workflow_dispatch.' \
  "$TEMP_DIR/trusted-target-unsupported.stderr"
test ! -s "$unsupported_target_output"

# The successor lifecycle resolver remains checked in but dormant. An exact
# validated artifact can resolve deploy once a successor producer is activated.
reset_case
run_trusted_target_fixture \
  '[{"artifacts":[{"name":"preview-render-pr-900-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","expired":false}]}]' \
  valid "$FAKE_TARGET_HEAD" "$TEMP_DIR/trusted-target-exact-artifact.out"
grep -qx 'action=deploy' "$TEMP_DIR/trusted-target-exact-artifact.out"
grep -qx 'head_sha=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa' "$TEMP_DIR/trusted-target-exact-artifact.out"

# The prerequisite's old preview producer publishes no validated artifact, so
# its only reachable workflow_run source must resolve no action.
reset_case
run_trusted_target_fixture \
  '[{"artifacts":[]}]' valid "$FAKE_TARGET_HEAD" \
  "$TEMP_DIR/trusted-target-no-artifact.out"
grep -qx 'action=none' "$TEMP_DIR/trusted-target-no-artifact.out"

reset_case
run_trusted_target_fixture \
  '[{"artifacts":[{"name":"preview-render-pr-900-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","expired":false}]}]' \
  malformed "$FAKE_TARGET_HEAD" "$TEMP_DIR/trusted-target-malformed.out"
grep -qx 'action=none' "$TEMP_DIR/trusted-target-malformed.out"

reset_case
run_trusted_target_fixture \
  '[{"artifacts":[{"name":"preview-render-pr-900-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","expired":false}]}]' \
  valid bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb "$TEMP_DIR/trusted-target-stale.out"
grep -qx 'action=none' "$TEMP_DIR/trusted-target-stale.out"

TRUSTED_WORKFLOW="$trusted_workflow" python3 - <<'PY'
import os
from pathlib import Path

import yaml

workflow = yaml.safe_load(
    Path(os.environ["TRUSTED_WORKFLOW"]).read_text(encoding="utf-8")
)
triggers = workflow.get("on", workflow.get(True))
assert list(triggers) == ["workflow_run", "pull_request_target"], triggers
assert triggers["workflow_run"] == {
    "workflows": ["PR Preview Environment"],
    "types": ["completed"],
}
assert triggers["pull_request_target"] == {"types": ["closed"]}

jobs = workflow["jobs"]
expected_gates = {
    "deploy-runtime": "needs.validate-target.outputs.action == 'deploy'",
    "verify-runtime": "needs.validate-target.outputs.action == 'deploy'",
    "destroy-runtime": "needs.validate-target.outputs.action == 'destroy'",
    "retire-identity": "needs.validate-target.outputs.action == 'destroy'",
}
assert set(jobs) == {"validate-target", *expected_gates}, jobs.keys()
for job_name, required_gate in expected_gates.items():
    condition = jobs[job_name].get("if", "")
    assert required_gate in condition, (job_name, condition)
PY

janitor_workflow="$ROOT_DIR/.github/workflows/preview-janitor.yml"
grep -q 'timeout-minutes: 60' "$janitor_workflow"
# Dormant successor deployment and cleanup code remains source-bound and
# fail-closed until successor triggers and producer artifacts are activated.
grep -q 'ACTION=deploy' "$trusted_workflow"
test "$(grep -Fc -- '            ACTION=deploy' "$trusted_workflow")" -eq 1
grep -q 'emit_no_action' "$trusted_workflow"
# shellcheck disable=SC2016 # Assert the exact workflow-run artifact name.
grep -Fq 'expected_artifact_name="preview-render-pr-${PR_NUMBER}-${EXPECTED_HEAD_SHA}"' "$trusted_workflow"
grep -q 'Revalidate preview cleanup target before runtime deletion' "$trusted_workflow"
grep -q 'Revalidate preview cleanup target before identity retirement' "$trusted_workflow"
# shellcheck disable=SC2016 # Assert centralized label inspection in trusted workflow source.
grep -q -- '--inspect-labels --labels-json "$labels_json"' "$trusted_workflow"
grep -q 'malformed-label-metadata' "$eligibility_script"
# shellcheck disable=SC2016 # Assert centralized exact-label inspection in trusted workflow source.
test "$(grep -Fc -- '--inspect-labels --labels-json "$labels_json"' "$trusted_workflow")" -eq 1
test "$(grep -Fc -- 'revalidate-preview-deploy.sh' "$trusted_workflow")" -eq 7
# shellcheck disable=SC2016 # Assert literal cleanup helper arguments.
test "$(grep -Fc -- '--cleanup "$PR_NUMBER" "$EXPECTED_HEAD_SHA"' "$trusted_workflow")" -eq 2
test "$(grep -Fc -- '--revalidate-deploy' "$trusted_workflow")" -eq 0
test "$(grep -Fc -- '--operation deploy' "$trusted_workflow")" -eq 0
revalidation_helper="$ROOT_DIR/dev-tools/hosted/preview/revalidate-preview-deploy.sh"
test "$(grep -Fc -- 'revalidate-preview-deploy.sh' "$ALLOCATOR")" -eq 1
# shellcheck disable=SC2016 # Assert literal helper mode selection.
grep -Fq -- 'revalidation_mode="--revalidate-${mode}"' "$revalidation_helper"
# shellcheck disable=SC2016 # Assert literal evaluator argument forwarding.
grep -Fq -- '"$revalidation_mode"' "$revalidation_helper"
# shellcheck disable=SC2016 # Assert literal shell source in the revalidation helper.
grep -Fq -- '--expected-repository "$GITHUB_REPOSITORY"' "$revalidation_helper"
# shellcheck disable=SC2016 # Assert literal shell source in the revalidation helper.
grep -Fq -- '--expected-head-sha "$expected_head_sha"' "$revalidation_helper"
# shellcheck disable=SC2016 # This assertion intentionally matches literal shell source.
grep -q -- '--batch-deploy-candidates' "$ROOT_DIR/dev-tools/hosted/preview/allocate-preview-capacity.sh"
# shellcheck disable=SC2016 # This assertion intentionally matches literal shell source.
grep -q -- '--expected-repository "$GITHUB_REPOSITORY"' "$ROOT_DIR/dev-tools/hosted/preview/allocate-preview-capacity.sh"
grep -q 'max_priority_candidates=1000' "$ROOT_DIR/dev-tools/hosted/preview/allocate-preview-capacity.sh"
ALLOCATOR_PATH="$ROOT_DIR/dev-tools/hosted/preview/allocate-preview-capacity.sh" python3 - <<'PY'
import os
from pathlib import Path

source = Path(os.environ["ALLOCATOR_PATH"]).read_text(encoding="utf-8")
start = source.index("find_unsatisfied_priority_pr()")
end = source.index("\n# Fail closed", start)
body = source[start:end]
assert "inspect_labels" not in body
assert "--batch-deploy-candidates" in body
assert body.count('python3 "$eligibility_script"') == 1
assert "--operation deploy" not in body
assert "priority_page_size=100" in body
assert "max_priority_pages=$((max_priority_candidates / priority_page_size))" in body
assert 'page=${page}' in body
assert 'per_page=1&page=$((max_priority_candidates + 1))' in body
assert 'candidate limit exceeded' in body
assert "--paginate" not in body
PY

if printf '%s\n' "901${TAB:-$'\t'}${priority_candidate_head}${TAB:-$'\t'}example/FireMUD${TAB:-$'\t'}human${TAB:-$'\t'}develop${TAB:-$'\t'}open${TAB:-$'\t'}${priority_labels_base64}" |
  python3 "$eligibility_script" --batch-deploy-candidates --expected-repository '' \
  >"$TEMP_DIR/empty-expected-repository.output" 2>"$TEMP_DIR/empty-expected-repository.error"; then
  echo "batch candidate evaluation accepted an empty expected repository" >&2
  exit 1
fi
grep -Fxq 'expected repository is required' "$TEMP_DIR/empty-expected-repository.error"

# Accept exactly the configured candidate limit, then use only a one-record
# overflow probe to reject candidate 1001.
priority_limit_rows=""
for _ in $(seq 1 100); do
  priority_limit_rows+="901"$'\t'"${priority_candidate_head}"$'\texample/FireMUD\thuman\tdevelop\topen\t'"${priority_labels_base64}"$'\n'
done
reset_case
export FAKE_TARGET_PRIORITY=false
export FAKE_OPEN_PRIORITY_ROWS="$priority_limit_rows"
if bash "$ALLOCATOR" pr-900 2 900 "$FAKE_TARGET_HEAD" \
  >"$TEMP_DIR/priority-limit.output" 2>&1; then
  echo "ordinary allocation unexpectedly succeeded with an unsatisfied priority PR" >&2
  exit 1
fi
if grep -Fq 'candidate limit exceeded' "$TEMP_DIR/priority-limit.output"; then
  echo "exactly max_priority_candidates was rejected" >&2
  exit 1
fi
grep -Fq 'Yielding ordinary PR #900' "$TEMP_DIR/priority-limit.output"

reset_case
export FAKE_TARGET_PRIORITY=false
export FAKE_OPEN_PRIORITY_ROWS="$priority_limit_rows"
export FAKE_PRIORITY_OVERFLOW=true
if bash "$ALLOCATOR" pr-900 2 900 "$FAKE_TARGET_HEAD" \
  >"$TEMP_DIR/priority-overflow.output" 2>&1; then
  echo "allocation unexpectedly succeeded with priority candidate 1001" >&2
  exit 1
fi
grep -Fq 'candidate limit exceeded' "$TEMP_DIR/priority-overflow.output"

grep -q -- '--operation retain' "$ROOT_DIR/dev-tools/hosted/preview/prune-stale-preview-namespaces.sh"
grep -Fq '(.labels | tojson | @base64)' "$ROOT_DIR/dev-tools/hosted/preview/prune-stale-preview-namespaces.sh"
grep -q -- "--labels-json \"\$pr_labels_json\"" "$ROOT_DIR/dev-tools/hosted/preview/prune-stale-preview-namespaces.sh"
PRUNER_PATH="$ROOT_DIR/dev-tools/hosted/preview/prune-stale-preview-namespaces.sh" python3 - <<'PY'
import os
from pathlib import Path

source = Path(os.environ["PRUNER_PATH"]).read_text(encoding="utf-8")
assert source.index('if [[ "${1:-}" == "--delete-runtime" ]]') < source.index(
    'PREVIEW_DELETE_TIMEOUT must be an integer between 1 and 3600'
)
PY
grep -Fq "printf 'identity=%s\\nphase=Retired\\n' \"\$identity_name\"" \
  "$ROOT_DIR/dev-tools/hosted/preview/wait-for-hosted-identity.sh"
# shellcheck disable=SC2016 # Assert literal default helper selection.
grep -Fq 'delete_script="${PREVIEW_DELETE_SCRIPT:-${script_dir}/../shared/delete-hosted-namespace.sh}"' \
  "$ROOT_DIR/dev-tools/hosted/preview/prune-stale-preview-namespaces.sh"
# shellcheck disable=SC2016 # Assert literal delegated timeout.
grep -Fq 'PREVIEW_NAMESPACE_DELETE_TIMEOUT_SECONDS="$preview_delete_timeout"' \
  "$ROOT_DIR/dev-tools/hosted/preview/prune-stale-preview-namespaces.sh"
# shellcheck disable=SC2016 # Assert literal delegated helper arguments.
grep -Fq 'bash "$delete_script" "$runtime_namespace" "$runtime_namespace"' \
  "$ROOT_DIR/dev-tools/hosted/preview/prune-stale-preview-namespaces.sh"
if grep -Fq 'delete_runtime_namespace()' \
  "$ROOT_DIR/dev-tools/hosted/preview/prune-stale-preview-namespaces.sh"; then
  echo "pruner retained a duplicate runtime namespace deletion implementation" >&2
  exit 1
fi
# shellcheck disable=SC2016 # Assert literal helper invocation arguments.
grep -Fq 'bash "$delete_script" "$namespace" "$release_name"' \
  "$ROOT_DIR/dev-tools/hosted/preview/prune-stale-preview-namespaces.sh"
if grep -Eq 'def labels_valid:|all\(\.labels\[\]\?; \(type == "object"\)' \
  "$trusted_workflow" \
  "$reconciler_workflow" \
  "$ROOT_DIR/dev-tools/hosted/preview/allocate-preview-capacity.sh" \
  "$ROOT_DIR/dev-tools/hosted/preview/prune-stale-preview-namespaces.sh"; then
  echo "A live preview callsite duplicates the centralized label predicate" >&2
  exit 1
fi
grep -q 'group: preview-allocation-lifecycle' "$trusted_workflow"
test "$(grep -Fc 'group: preview-allocation-lifecycle' "$trusted_workflow")" -eq 4
test "$(grep -Fc 'cancel-in-progress: false' "$trusted_workflow")" -eq 4
test "$(grep -Fc 'queue: max' "$trusted_workflow")" -eq 4
grep -q 'group: preview-allocation-lifecycle' "$janitor_workflow"
grep -q 'uses: ./.github/actions/resolve-certificate-identity-mode' "$janitor_workflow"
grep -q "steps.certificate-identity.outputs.mode == 'hosted-controller'" "$janitor_workflow"
grep -q 'HOSTED_IDENTITY_REQUESTER_KUBECONFIG' "$janitor_workflow"
# shellcheck disable=SC2016 # Assert the explicit hosted-controller retirement branch.
grep -Fq -- '--retire-terminal-identities' "$janitor_workflow"
# Ordinary runtime cleanup callers must not opt into retained-identity retirement.
test "$(grep -Fc -- '--retire-terminal-identities' "$ROOT_DIR/.github/workflows/preview.yml")" -eq 0
test "$(grep -Fc -- '--retire-terminal-identities' "$trusted_workflow")" -eq 0
python3 - "$ROOT_DIR/.github/workflows/preview.yml" "$reconciler_workflow" <<'PY'
import sys
from pathlib import Path

import yaml

preview = yaml.safe_load(Path(sys.argv[1]).read_text(encoding="utf-8"))
reconciler = yaml.safe_load(Path(sys.argv[2]).read_text(encoding="utf-8"))
assert preview["jobs"]["preview-destroy"]["timeout-minutes"] == 60
assert reconciler["jobs"]["reconcile-previews"]["timeout-minutes"] == 60
PY
grep -q -- '--retire-terminal-identities' "$ROOT_DIR/dev-tools/hosted/preview/prune-stale-preview-namespaces.sh"
grep -q 'Skipping ordinary PR #' "$reconciler_workflow"
grep -q 'another preview repair was already dispatched this cycle' "$reconciler_workflow"
# shellcheck disable=SC2016 # Assert one fail-closed JSON snapshot feeds each namespace decision.
grep -Fq 'kubectl get namespace "${namespace}" --ignore-not-found -o json' \
  "$reconciler_workflow"
# shellcheck disable=SC2016 # Assert the initial and fresh namespace snapshots.
test "$(grep -Fc 'kubectl get namespace "${namespace}" --ignore-not-found -o json' "$reconciler_workflow")" -eq 3
# shellcheck disable=SC2016 # Assert the fresh namespace snapshot fences repair.
grep -Fq 'requested head changed during annotation repair check' "$reconciler_workflow"
# The janitor keeps explicit kubeconfig selection on the consuming kubectl steps;
# no preceding restore step may imply a different runner-wide kubeconfig.
if grep -Fq 'Restore preview runtime kubeconfig' "$janitor_workflow"; then
  echo "Preview janitor retained an ineffective runtime kubeconfig restore step" >&2
  exit 1
fi
# shellcheck disable=SC2016 # Assert explicit kubeconfig selection on janitor consumers.
grep -Fq 'KUBECONFIG: ${{ runner.temp }}/preview-kubeconfig.yaml' "$janitor_workflow"
# shellcheck disable=SC2016 # Assert labels are encoded as one safe row field.
grep -Fq '(.labels | map({name: .name}) | tojson | @base64)' "$reconciler_workflow"
# shellcheck disable=SC2016 # Assert decoded labels reach the centralized parser.
grep -Fq -- '--labels-json "$labels_json"' "$reconciler_workflow"
# shellcheck disable=SC2016 # Assert malformed label metadata fails closed before eligibility.
grep -Fq -- 'if ! labels_json="$(printf '\''%s'\'' "$labels_json_base64" | base64 --decode 2>/dev/null)" ||' \
  "$reconciler_workflow"
# shellcheck disable=SC2016 # Assert literal shell source in the workflow.
grep -Fq -- '[[ -z "$labels_json" ]] ||' "$reconciler_workflow"
# shellcheck disable=SC2016 # Assert literal shell source in the workflow.
grep -Fq -- '! jq -e '\''type == "array"'\'' <<<"$labels_json" >/dev/null' \
  "$reconciler_workflow"

echo "preview priority contract checks passed"
