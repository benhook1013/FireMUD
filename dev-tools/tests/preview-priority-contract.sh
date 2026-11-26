#!/usr/bin/env bash
set -euo pipefail
report_contract_failure() {
  printf 'Preview priority contract failed at line %s (exit %s)\n' "$2" "$1" >&2
}
trap 'report_contract_failure "$?" "$LINENO"' ERR

# Python assertions are part of the contract, including the native tuple markers.
python3 -c 'import sys; sys.exit(0 if __debug__ else "Preview priority contract requires Python assertions; unset PYTHONOPTIMIZE.")'

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
bash "$ROOT_DIR/dev-tools/tests/preview-proof-retry-lock-contract.sh"
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

python3 - "$ROOT_DIR/.github/workflows/preview.yml" <<'PY'
import sys, yaml
steps = yaml.safe_load(open(sys.argv[1]))["jobs"]["preview-plan"]["steps"]
resolved = [(i, s) for i, s in enumerate(steps) if s.get("name", "").startswith("Preview resolved tuple ")]
assert len(resolved) == 1
index, marker = resolved[0]
assert marker["run"] == ":"
assert marker["if"] == "${{ github.event_name == 'pull_request' && steps.derive.outputs.action == 'deploy' && steps.eligibility.outputs.eligible == 'true' }}"
assert marker["name"] == "Preview resolved tuple pr-${{ steps.derive.outputs.pr_number }} base-${{ steps.derive.outputs.base_sha }} head-${{ steps.derive.outputs.head_sha }} merge-${{ steps.derive.outputs.merge_sha }}"
assert index > next(i for i,s in enumerate(steps) if s.get("id") == "derive")
assert index > next(i for i,s in enumerate(steps) if s.get("id") == "eligibility")
event_markers = [(i, s) for i, s in enumerate(steps) if s.get("name", "").startswith("Preview event tuple ")]
assert len(event_markers) == 1
event_index, event_marker = event_markers[0]
assert event_marker["run"] == ":"
assert event_marker["if"] == "${{ github.event_name == 'pull_request' }}"
assert event_marker["name"] == "Preview event tuple pr-${{ github.event.pull_request.number }} base-${{ github.event.pull_request.base.sha }} head-${{ github.event.pull_request.head.sha }} merge-${{ github.sha }}"
assert event_index < next(i for i,s in enumerate(steps) if s.get("id") == "derive")
PY

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
if [[ "$*" == "-n firemud-system get configmap firemud-preview-proof-retries -o json --request-timeout=10s" ]]; then
  if [[ "${FAKE_RETRY_HOLDER_MISSING:-false}" == true ]]; then exit 1; fi
  if [[ -f "$FAKE_RETRY_HOLDER" ]]; then
    cat "$FAKE_RETRY_HOLDER"
  else
    printf '%s\n' '{"apiVersion":"v1","kind":"ConfigMap","metadata":{"namespace":"firemud-system","name":"firemud-preview-proof-retries","uid":"uid-holder","resourceVersion":"1"},"data":{"schemaVersion":"1"}}'
  fi
  exit 0
fi
if [[ "${1:-}" == -n && "${2:-}" == firemud-system && "${3:-}" == patch &&
  "${4:-}" == configmap && "${5:-}" == firemud-preview-proof-retries && "${6:-}" == --type=json &&
  "${7:-}" == --patch && "${9:-}" == -o && "${10:-}" == json && "${11:-}" == --request-timeout=10s ]]; then
  printf '%s\n' "$8" >> "$FAKE_RETRY_PATCH_LOG"
  case "${FAKE_RETRY_PATCH_CASE:-}" in
    denied) echo 'Error from server (Forbidden): holder patch denied' >&2; exit 1 ;;
  esac
  state="$(kubectl -n firemud-system get configmap firemud-preview-proof-retries -o json --request-timeout=10s)"
  target_phase="$(jq -r '.[2].value | if type=="string" then fromjson | .phase // empty else empty end' <<<"$8")"
  if [[ "${FAKE_RETRY_PATCH_CASE:-}" == denied-start && "$target_phase" == SEND_STARTED ]]; then exit 1; fi
  next="$(jq -ec --argjson patch "$8" '
    select(($patch | length) == 3
      and $patch[0] == {op:"test",path:"/metadata/uid",value:.metadata.uid}
      and $patch[1] == {op:"test",path:"/metadata/resourceVersion",value:.metadata.resourceVersion}
      and ($patch[2].path | test("^/data/pr-[1-9][0-9]*$")))
    | ($patch[2].path | ltrimstr("/data/")) as $key
    | if $patch[2].op == "remove" then del(.data[$key])
      elif $patch[2].op == "add" then .data[$key] = $patch[2].value
      else error("unexpected operation") end
    | .metadata.resourceVersion |= (tonumber + 1 | tostring)
  ' <<<"$state")" || exit 1
  if [[ "${FAKE_RETRY_PATCH_CASE:-}" == different-nonce ]]; then
    next="$(jq -c '.data["pr-901"] |= (fromjson | .nonce = "12345678-1234-4234-8234-123456789abc" | tojson)' <<<"$next")"
  fi
  printf '%s\n' "$next" > "$FAKE_RETRY_HOLDER"
  if [[ "${FAKE_RETRY_PATCH_CASE:-}" == different-nonce ||
    ( "${FAKE_RETRY_PATCH_CASE:-}" == lost-reserve && "$target_phase" == RESERVED ) ||
    ( "${FAKE_RETRY_PATCH_CASE:-}" == lost-send && "$target_phase" == SEND_STARTED ) ||
    ( "${FAKE_RETRY_PATCH_CASE:-}" == competing-start && "$target_phase" == SEND_STARTED ) ]]; then
    exit 1
  fi
  case "${FAKE_RETRY_PATCH_CASE:-}" in
    wrong-holder) jq -c '.metadata.uid = "replacement-holder"' <<<"$next" ;;
    unchanged-version) jq -c --argjson old "$state" '.metadata.resourceVersion = $old.metadata.resourceVersion' <<<"$next" ;;
    wrong-row) jq -c '.data["pr-901"] |= (fromjson | .count = 3 | tojson)' <<<"$next" ;;
    *) printf '%s\n' "$next" ;;
  esac
  exit 0
fi
if [[ "$*" == *"get namespaces"* ]]; then
  if [[ "${FAKE_NAMESPACE_LIST_ERROR:-false}" == true ]]; then
    exit 1
  fi
  if [[ "$*" == *"-o name"* && -n "${FAKE_NAMESPACE_COUNT_SEQUENCE:-}" ]]; then
    call_count=0
    if [[ -f "$FAKE_NAMESPACE_LIST_CALLS" ]]; then
      call_count="$(<"$FAKE_NAMESPACE_LIST_CALLS")"
    fi
    call_count=$((call_count + 1))
    printf '%s' "$call_count" > "$FAKE_NAMESPACE_LIST_CALLS"
    IFS=',' read -r -a namespace_counts <<<"$FAKE_NAMESPACE_COUNT_SEQUENCE"
    sequence_index=$((call_count - 1))
    if (( sequence_index >= ${#namespace_counts[@]} )); then
      sequence_index=$((${#namespace_counts[@]} - 1))
    fi
    for ((namespace_index = 1; namespace_index <= namespace_counts[sequence_index]; namespace_index++)); do
      printf 'namespace/fake-%s\n' "$namespace_index"
    done
    exit 0
  fi
  printf '%b' "${FAKE_NAMESPACE_ROWS:-}"
  exit 0
fi
if [[ "$1" == get && "$2" == namespace && "$3" == pr-901 &&
  "$*" == *"--ignore-not-found -o json"* && "$*" != *jsonpath* ]]; then
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
  if [[ "${FAKE_PR_901_NAMESPACE_ABSENT:-false}" == true ]] ||
    [[ "${FAKE_PR_901_NAMESPACE_ABSENT_ON_INITIAL:-false}" == true && "$count" -eq 1 ]] ||
    [[ "${FAKE_PR_901_NAMESPACE_ABSENT_ON_RECHECK:-false}" == true && "$count" -gt 1 ]]; then
    exit 0
  fi
  if [[ "${FAKE_NAMESPACE_SNAPSHOT_PARSE_FAIL:-false}" == true ]] ||
    [[ "${FAKE_NAMESPACE_SNAPSHOT_RECHECK_PARSE_FAIL:-false}" == true && "$count" -gt 1 ]]; then
    printf '%s' '{"metadata":{"annotations":[]}}'
    exit 0
  fi
  if [[ -f "${FAKE_ANNOTATE_FAILURE_MARKER:?}" ]]; then
    if [[ "${FAKE_ANNOTATE_CONFIRMATION_ERROR:-false}" == true ]]; then
      exit 1
    fi
    if [[ "${FAKE_ANNOTATE_NAMESPACE_ABSENT_AFTER_FAILURE:-false}" == true ]]; then
      exit 0
    fi
    rm -f "${FAKE_ANNOTATE_FAILURE_MARKER:?}"
  fi
  if [[ "${FAKE_RETRY_RUNTIME_DELETED_AFTER_REQUEST:-false}" == true &&
    -f "$FAKE_RUNTIME_DELETE_UID_LOG" ]]; then
    exit 0
  fi
  canonical_head() {
    case "${1:-}" in
      head-901) printf '%s' aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa ;;
      *) printf '%s' "${1:-}" ;;
    esac
  }
  snapshot_head="$(canonical_head "${FAKE_PR_901_SNAPSHOT_HEAD-${FAKE_PR_901_HEAD:-}}")"
  snapshot_requested_head="$(canonical_head "${FAKE_PR_901_REQUESTED_HEAD:-}")"
  if [[ "$count" -gt 1 ]]; then
    if [[ -v FAKE_PR_901_RECHECK_HEAD ]]; then
      snapshot_head="$(canonical_head "$FAKE_PR_901_RECHECK_HEAD")"
    fi
    if [[ -v FAKE_PR_901_RECHECK_REQUESTED_HEAD ]]; then
      snapshot_requested_head="$(canonical_head "$FAKE_PR_901_RECHECK_REQUESTED_HEAD")"
    fi
  fi
  snapshot_base="${FAKE_PR_901_BASE_SHA:-bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb}"
  snapshot_merge="${FAKE_PR_901_MERGE_SHA:-cccccccccccccccccccccccccccccccccccccccc}"
  snapshot_image="${FAKE_PR_901_IMAGE_TAG:-$snapshot_base}"
  if [[ -n "$snapshot_requested_head" ]]; then
    snapshot_requested_base="${FAKE_PR_901_REQUESTED_BASE_SHA:-$snapshot_base}"
    snapshot_requested_merge="${FAKE_PR_901_REQUESTED_MERGE_SHA:-$snapshot_merge}"
    snapshot_requested_image="${FAKE_PR_901_REQUESTED_IMAGE_TAG:-$snapshot_image}"
  else
    snapshot_requested_base=""
    snapshot_requested_merge=""
    snapshot_requested_image=""
  fi
  snapshot_uid="${FAKE_PR_901_NAMESPACE_UID:-uid-pr-901}"
  if [[ "$count" -gt 1 && -v FAKE_PR_901_RECHECK_NAMESPACE_UID ]]; then
    snapshot_uid="$FAKE_PR_901_RECHECK_NAMESPACE_UID"
  fi
  snapshot_resource_version=rv-901
  if [[ "${FAKE_RETRY_RUNTIME_CHANGE_BEFORE_SEND:-false}" == true && "$count" -gt 2 ]]; then
    snapshot_resource_version=rv-drift
  fi
  proof_complete="${FAKE_PR_901_PROOF_COMPLETE:-true}"
  if [[ "$count" -gt 1 && "${FAKE_PR_901_PROOF_REMOVED_ON_RECHECK:-false}" == true ]]; then
    proof_complete=false
  fi
  if [[ "${FAKE_PR_901_PROOF_COMPLETE_AFTER_CONSUMER_COMPLETION:-false}" == true &&
    -f "${FAKE_TRUSTED_CONSUMER_COMPLETED_MARKER:-}" ]]; then
    proof_complete=true
  fi
  if [[ "${FAKE_PR_901_PROOF_COMPLETES_ON_FRESH_REFRESH:-false}" == true && "$count" -gt 2 ]]; then
    proof_complete=true
  fi
  if [[ "${FAKE_PR_901_PROOF_REMOVED_ON_FRESH_REFRESH:-false}" == true && "$count" -gt 2 ]]; then
    proof_complete=false
  fi
  if [[ "$proof_complete" == true ]]; then
    snapshot_proof_base="$snapshot_base"
    snapshot_proof_head="$snapshot_head"
    snapshot_proof_merge="$snapshot_merge"
    snapshot_proof_image="$snapshot_image"
    snapshot_proof_uid="$snapshot_uid"
  else
    snapshot_proof_base="${FAKE_PR_901_PROOF_BASE_SHA:-}"
    snapshot_proof_head="${FAKE_PR_901_PROOF_HEAD_SHA:-}"
    snapshot_proof_merge="${FAKE_PR_901_PROOF_MERGE_SHA:-}"
    snapshot_proof_image="${FAKE_PR_901_PROOF_IMAGE_TAG:-}"
    snapshot_proof_uid="${FAKE_PR_901_PROOF_NAMESPACE_UID:-}"
  fi
  jq -cn \
    --arg base "$snapshot_base" \
    --arg head "$snapshot_head" \
    --arg merge "$snapshot_merge" \
    --arg image "$snapshot_image" \
    --arg requested_base "$snapshot_requested_base" \
    --arg requested_head "$snapshot_requested_head" \
    --arg requested_merge "$snapshot_requested_merge" \
    --arg requested_image "$snapshot_requested_image" \
    --arg proof_base "$snapshot_proof_base" \
    --arg proof_head "$snapshot_proof_head" \
    --arg proof_merge "$snapshot_proof_merge" \
    --arg proof_image "$snapshot_proof_image" \
    --arg proof_uid "$snapshot_proof_uid" \
    --arg legacy_retry "${FAKE_LEGACY_RETRY_JSON:-null}" \
    --arg uid "$snapshot_uid" \
    --arg resource_version "$snapshot_resource_version" \
    '{metadata:{name:"pr-901",uid:$uid,resourceVersion:$resource_version,
        labels:{"firemud.dev/preview":"true","firemud.dev/pr-number":"901"},
        annotations:{"firemud.dev/last-preview-base-sha":$base,"firemud.dev/last-preview-head-sha":$head,
          "firemud.dev/last-preview-merge-sha":$merge,"firemud.dev/last-preview-image-tag":$image,
          "firemud.dev/requested-preview-base-sha":$requested_base,"firemud.dev/requested-preview-head-sha":$requested_head,
          "firemud.dev/requested-preview-merge-sha":$requested_merge,"firemud.dev/requested-preview-image-tag":$requested_image,
          "firemud.dev/proof-preview-base-sha":$proof_base,"firemud.dev/proof-preview-head-sha":$proof_head,
          "firemud.dev/proof-preview-merge-sha":$proof_merge,"firemud.dev/proof-preview-image-tag":$proof_image,
          "firemud.dev/proof-preview-namespace-uid":$proof_uid}}}
      | if $legacy_retry != "null" then .metadata.annotations += ($legacy_retry | fromjson) else . end'
  exit 0
fi
if [[ "$1" == get && "$2" == namespace &&
  "$*" == *"--ignore-not-found -o json"* && "$*" != *jsonpath* ]]; then
  printf '%s\n' "$*" >> "$FAKE_RUNTIME_KUBECTL_LOG"
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
    exit 0
  fi
  if [[ "${FAKE_RUNTIME_NAMESPACE_JSON_PARSE_FAIL:-false}" == true ]]; then
    printf '%s' '{not-json'
    exit 0
  fi
  runtime_name="${FAKE_RUNTIME_LOOKUP_IDENTITY:-$3}"
  if [[ -f "$FAKE_RUNTIME_WAIT_MARKER" && -n "${FAKE_RUNTIME_RECHECK_IDENTITY:-}" ]]; then
    runtime_name="$FAKE_RUNTIME_RECHECK_IDENTITY"
  fi
  runtime_uid="${FAKE_RUNTIME_NAMESPACE_UID:-uid-$3}"
  if [[ -f "$FAKE_RUNTIME_WAIT_MARKER" && -n "${FAKE_RUNTIME_RECHECK_UID:-}" ]]; then
    runtime_uid="$FAKE_RUNTIME_RECHECK_UID"
  fi
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
      --arg resource_version "${FAKE_RUNTIME_NAMESPACE_RESOURCE_VERSION-rv-initial}" \
      '{metadata:{name:$name,uid:$uid,resourceVersion:$resource_version,labels:{"firemud.dev/preview":$preview,"firemud.dev/pr-number":$owner}}}'
  fi
  exit 0
fi
if [[ "$1" == get && "$2" == namespace && "$*" == *"--ignore-not-found -o name"* ]]; then
  printf '%s\n' "$*" >> "$FAKE_RUNTIME_KUBECTL_LOG"
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
  if [[ "${FAKE_RUNTIME_DELETE_ERROR:-false}" == true ]]; then
    exit 1
  fi
  expected_delete_uid="${FAKE_RUNTIME_DELETE_CURRENT_UID:-${FAKE_RUNTIME_NAMESPACE_UID:-uid-${3##*/}}}"
  if [[ "$delete_uid" != "$expected_delete_uid" ]]; then
    exit 1
  fi
  delete_resource_version="$(jq -r '.preconditions.resourceVersion // ""' <<<"$delete_options")"
  if [[ -n "${FAKE_RUNTIME_DELETE_CURRENT_VERSION:-}" && -n "$delete_resource_version" &&
    "$delete_resource_version" != "$FAKE_RUNTIME_DELETE_CURRENT_VERSION" ]]; then
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
if [[ "$*" == "-n firemud-system get hostedenvironmentidentities.platform.firemud.dev -o json" ]]; then
  printf '%s\n' "$*" >> "$FAKE_IDENTITY_LOG"
  printf '%s\n' "${KUBECONFIG:-}" >> "$FAKE_IDENTITY_LIST_KUBECONFIG_LOG"
  if [[ "${FAKE_IDENTITY_LIST_FAIL:-false}" == true ]]; then
    exit 1
  fi
  printf '%s' "${FAKE_IDENTITY_LIST_JSON:?}"
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
        '{metadata:{name:"pr-101",creationTimestamp:"2026-01-01T00:00:00Z",labels:{"firemud.dev/pr-number":$owner},annotations:{"firemud.dev/preview-allocated-at":"2026-01-02T00:00:00Z","firemud.dev/last-preview-base-sha":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb","firemud.dev/last-preview-head-sha":"head-101","firemud.dev/last-preview-merge-sha":"cccccccccccccccccccccccccccccccccccccccc","firemud.dev/last-preview-image-tag":$image}}}'
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
    if [[ "${FAKE_PRIORITY_CANDIDATE_NAMESPACE_LOOKUP_ERROR:-false}" == true ]]; then
      exit 1
    fi
    if [[ "${FAKE_PRIORITY_CANDIDATE_NAMESPACE_ABSENT:-false}" == true ]]; then
      exit 0
    fi
    if [[ "$*" == *last-preview-image-tag* && "$*" == *last-preview-base-sha* ]]; then
      printf '%s\t%s\t%s\t%s\t%s' \
        "${FAKE_PR_901_OWNER:-901}" \
        "${FAKE_PR_901_BASE_SHA:-bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb}" \
        "${FAKE_PR_901_HEAD:-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa}" \
        "${FAKE_PR_901_MERGE_SHA:-cccccccccccccccccccccccccccccccccccccccc}" \
        "${FAKE_PR_901_IMAGE_TAG:-bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb}"
      exit 0
    fi
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

cat > "$TEMP_DIR/bin/sleep" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "$*" >> "$FAKE_SLEEP_LOG"
EOF

cat > "$TEMP_DIR/bin/gh" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
resource=""
has_jq=false
has_include=false
for arg in "$@"; do
  if [[ "$arg" == repos/* ]]; then
    resource="$arg"
  elif [[ "$arg" == "--jq" ]]; then
    has_jq=true
  elif [[ "$arg" == "--include" ]]; then
    has_include=true
  fi
done

fake_labels_json() {
  local priority="$1"
  local labels_valid="$2"
  local labels_json='[]'
  if [[ "$labels_valid" != valid ]]; then
    labels_json='{}'
  elif [[ "$priority" == true ]]; then
    labels_json='[{"name":"preview:priority"}]'
  fi
  printf '%s' "$labels_json"
}
if [[ "$1" == api && "$resource" == 'repos/example/FireMUD/actions/workflows/preview.yml' ]]; then
  printf '%s\n' '{"id":173511323,"name":"PR Preview Environment","path":".github/workflows/preview.yml"}'
  exit 0
fi
if [[ "$1" == api && "$resource" == 'repos/example/FireMUD/actions/workflows/hosted-identity-request.yml' ]]; then
  printf '%s\n' '{"id":357129350,"name":"Trusted Hosted Identity Request","path":".github/workflows/hosted-identity-request.yml"}'
  exit 0
fi
if [[ "$1" == api && "$resource" == 'repos/example/FireMUD/actions/workflows/173511323/runs' ]]; then
  if [[ "$*" == *"event=repository_dispatch"* && -n "${FAKE_COMPLETED_RUN_DATA:-}" ]]; then
    if [[ "$*" != *"--method GET"* || "$*" != *"status=completed"* || "$*" != *"branch=develop"* ]]; then
      echo "completed partition omitted its original query filters: $*" >&2
      exit 1
    fi
    range=""
    for arg in "$@"; do
      [[ "$arg" != created=* ]] || range="${arg#created=}"
    done
    probe=false
    if [[ "$*" == *"per_page=1 "* || "$*" == *"per_page=1" ]]; then
      probe=true
      if [[ -z "$range" || "$*" == *"--paginate"* || "$*" == *"--slurp"* ]]; then
        echo "invalid completed partition probe: $*" >&2
        exit 1
      fi
    elif [[ "$*" != *"per_page=100"* || "$*" != *"--paginate"* || "$*" != *"--slurp"* ]]; then
      echo "completed partition inventory must paginate and slurp full pages: $*" >&2
      exit 1
    fi
    if [[ -z "$range" ]]; then
      printf '%s\n' '[{"total_count":1001,"workflow_runs":[]}]'
      exit 0
    fi
    jq -c --arg after "${range%..*}" --arg before "${range#*..}" \
      --argjson probe "$probe" \
      --arg broken "${FAKE_BROKEN_COMPLETED_PARTITION:-false}" '
        map(select(.created_at >= $after and .created_at <= $before)) as $rows
        | ($rows|length) as $count
        | if $probe then {total_count:$count,workflow_runs:$rows[0:1]}
          elif $broken == "true" and $count > 0 then [{total_count:$count,workflow_runs:[]}]
          else [range(0; $count; 100) as $i | {total_count:$count,workflow_runs:$rows[$i:$i+100]}] end
      ' "$FAKE_COMPLETED_RUN_DATA"
    exit 0
  fi
  if [[ "$*" != *"--method GET"* || "$*" != *"status=completed"* ||
    "$*" != *"per_page=100"* || "$*" != *"--paginate --slurp"* ||
    "$*" == *"created="* || "$*" == *"head="* ]]; then
    echo "recent source inventory must paginate without a creation-time cutoff: $*" >&2
    exit 1
  fi
  if [[ "$*" == *"event=pull_request"* ]]; then
    if [[ "$*" != *"head_sha=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"* ]]; then
      echo "recent pull_request inventory omitted its exact head SHA filter: $*" >&2
      exit 1
    fi
    payload="${FAKE_RECENT_PREVIEW_SOURCE_RUNS_JSON:-}"
  elif [[ "$*" == *"event=repository_dispatch"* ]]; then
    if [[ "$*" != *"branch=develop"* || "$*" == *"head_sha="* ]]; then
      echo "recent repository_dispatch inventory did not use the trusted default-branch filter: $*" >&2
      exit 1
    fi
    payload="${FAKE_RECENT_PREVIEW_DISPATCH_RUNS_JSON:-}"
  else
    echo "recent source inventory used an unexpected event filter: $*" >&2
    exit 1
  fi
  [[ -n "$payload" ]] || payload='{"total_count":0,"workflow_runs":[]}'
  jq -c 'if type == "array" then . else [.] end' <<<"$payload"
  exit 0
fi
if [[ "$1" == api && "$resource" == 'repos/example/FireMUD/actions/workflows/357129350/runs' ]]; then
  if [[ "$*" != *"--method GET"* || "$*" != *"event=workflow_run"* ||
    "$*" != *"branch=develop"* || "$*" == *"head="* ]]; then
    echo "fresh consumer inventory did not use bounded GET/branch query parameters: $*" >&2
    exit 1
  fi
  printf '%s\n' "$*" >> "$FAKE_RECENT_HANDOFF_LOOKUP_LOG"
  if [[ -v FAKE_RECENT_TRUSTED_CONSUMER_RUNS_JSON ]]; then
    printf '%s' "$FAKE_RECENT_TRUSTED_CONSUMER_RUNS_JSON"
  else
    printf '%s' '{"total_count":0,"workflow_runs":[]}'
  fi
  exit 0
fi
if [[ "$1" == api && ("$resource" == */actions/workflows/173511323/runs\?branch=* || "$resource" == */actions/workflows/173511323/runs\?event=pull_request\&status=* || "$resource" == */actions/workflows/173511323/runs\?event=pull_request_target\&status=* || "$resource" == */actions/workflows/357129350/runs\?event=workflow_run\&status=*) ]]; then
  active_query_status="${resource##*&status=}"
  active_query_status="${active_query_status%%&*}"
  if [[ "$resource" == */actions/workflows/173511323/runs\?branch=* ]]; then
    active_query_scope=branch
  elif [[ "$resource" == */actions/workflows/173511323/runs\?event=pull_request\&status=* ]]; then
    active_query_scope=pull_request
  elif [[ "$resource" == */actions/workflows/173511323/runs\?event=pull_request_target\&status=* ]]; then
    active_query_scope=pull_request_target
  else
    active_query_scope=workflow_run
  fi
  if [[ "${FAKE_ACTIVE_PREVIEW_RUNS_API_ERROR:-false}" == true ]]; then
    exit 1
  fi
  if [[ "${FAKE_ACTIVE_PREVIEW_RUNS_API_MALFORMED:-false}" == true ]]; then
    printf '%s' '[{"total_count":1,"workflow_runs":[{"id":"not-a-number"}]}]'
  elif [[ "${FAKE_ACTIVE_INVENTORY_TOTAL:-}" == 1000 ]]; then
    jq -cn --arg status "$active_query_status" --arg scope "$active_query_scope" '
      [range(10) as $page | {
        total_count:1000,
        workflow_runs:[range(100) | {
          id:(10000 + ($page * 100) + .), run_attempt:1, status:$status,
          event:(if $scope == "branch" then "repository_dispatch" else $scope end),
          head_branch:"develop", display_title:"Unrelated preview fixture"
        }]
      }]'
  elif [[ -n "${FAKE_ACTIVE_INVENTORY_TOTAL:-}" ]]; then
    printf '[{"total_count":%s,"workflow_runs":[]}]' "$FAKE_ACTIVE_INVENTORY_TOTAL"
  elif [[ -v FAKE_ACTIVE_PREVIEW_RUN_PAGES_JSON ]]; then
    jq -c \
      --arg requested_status "$active_query_status" \
      --arg requested_scope "$active_query_scope" \
      '[.[] | .workflow_runs = [.workflow_runs[] | select(.status == $requested_status) | select((.event // $requested_scope) == $requested_scope or ($requested_scope == "branch" and .event == "repository_dispatch"))]] | (map(.workflow_runs | length) | add) as $count | map(.total_count = $count)' \
      <<<"$FAKE_ACTIVE_PREVIEW_RUN_PAGES_JSON"
  else
    jq -cn \
      --argjson runs "${FAKE_ACTIVE_PREVIEW_RUNS_JSON:-[]}" \
      --arg requested_status "$active_query_status" \
      --arg requested_scope "$active_query_scope" \
      '[{workflow_runs:[$runs[] | select(.status == $requested_status and $requested_scope == "branch") | {
        id:.databaseId,run_attempt:1,workflow_id:173511323,event:"repository_dispatch",
        path:".github/workflows/preview.yml",name:.displayTitle,head_branch:"develop",
        head_sha:"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
        repository:{full_name:"example/FireMUD"},head_repository:{full_name:"example/FireMUD"},
        display_title:.displayTitle,status:.status}]}] | map(.total_count = (.workflow_runs | length))'
  fi
  exit 0
fi
if [[ "$1" == api && "$resource" == 'repos/example/FireMUD/actions/runs/5254/attempts/2/jobs?per_page=100' ]]; then
  [[ "${FAKE_PREVIEW_PLAN_JOBS_FAIL:-false}" != true ]] || exit 1
  printf '%s' "${FAKE_PREVIEW_PLAN_JOBS_JSON:-null}"
  exit 0
fi
if [[ "$1" == api && "$resource" == 'repos/example/FireMUD/actions/runs/5254' ]]; then
  [[ "${FAKE_PREVIEW_SOURCE_LATEST_FAIL:-false}" != true ]] || exit 1
  printf '%s' "${FAKE_PREVIEW_SOURCE_LATEST_JSON:-null}"
  exit 0
fi
if [[ "$1" == api && "$resource" == 'repos/example/FireMUD/actions/runs/5255/attempts/1/jobs?per_page=100' ]]; then
  printf '%s' "${FAKE_PREVIEW_DISPATCH_JOBS_JSON:-null}"
  exit 0
fi
if [[ "$1" == api && "$resource" == 'repos/example/FireMUD/actions/runs/5255' ]]; then
  printf '%s' "${FAKE_PREVIEW_DISPATCH_LATEST_JSON:-null}"
  exit 0
fi
if [[ "$1" == api && "$resource" =~ ^repos/example/FireMUD/actions/runs/(525[6-9])/attempts/1/jobs\?per_page=100$ ]]; then
  [[ "${FAKE_TRUSTED_CONSUMER_JOBS_FAIL:-false}" != true ]] || exit 1
  run_id="${BASH_REMATCH[1]}"
  if [[ "$run_id" == 5256 ]]; then
    printf '%s' "${FAKE_TRUSTED_CONSUMER_JOBS_JSON:-null}"
  else
    jq -c --argjson id "$run_id" '.jobs[] |= (.run_id = $id | .steps[].name |= sub("merge-[0-9a-f]{40}$"; "merge-dddddddddddddddddddddddddddddddddddddddd"))' \
      <<<"${FAKE_TRUSTED_CONSUMER_JOBS_JSON:-null}"
  fi
  exit 0
fi
if [[ "$1" == api && "$resource" =~ ^repos/example/FireMUD/actions/runs/(525[6-9])$ ]]; then
  run_id="${BASH_REMATCH[1]}"
  latest_json="$(jq -c --argjson id "$run_id" '.id = $id' <<<"${FAKE_TRUSTED_CONSUMER_LATEST_JSON:-null}")"
  if [[ "$run_id" == 5256 ]] && jq -e '.status == "completed"' <<<"$latest_json" >/dev/null; then
    : > "${FAKE_TRUSTED_CONSUMER_COMPLETED_MARKER:?}"
  fi
  printf '%s' "$latest_json"
  exit 0
fi
if [[ "$*" == *"repos/example/FireMUD/dispatches"* ]]; then
  printf '%s\n' "$*" >> "$FAKE_DISPATCH_LOG"
  if [[ "${FAKE_RETRY_CUSTODY_CHANGED_AT_POST:-false}" != false ]]; then
    jq -c --arg change "$FAKE_RETRY_CUSTODY_CHANGED_AT_POST" '
      .metadata.resourceVersion |= (tonumber + 1 | tostring)
      | if $change == "changed" then
          .data["pr-901"] |= (fromjson | .nonce = "12345678-1234-4234-8234-123456789abc" | tojson)
        else
          .data["pr-902"] = (.data["pr-901"] | fromjson | .namespace = "pr-902" | tojson)
        end
    ' "$FAKE_RETRY_HOLDER" > "${FAKE_RETRY_HOLDER}.next"
    mv "${FAKE_RETRY_HOLDER}.next" "$FAKE_RETRY_HOLDER"
  fi
  if [[ "${FAKE_DISPATCH_MISSING_STATUS:-false}" == true ]]; then
    exit "${FAKE_DISPATCH_GH_EXIT_CODE:-1}"
  fi
  dispatch_status="${FAKE_DISPATCH_HTTP_STATUS:-204}"
  case "$dispatch_status" in
    204) dispatch_reason='No Content' ;;
    422) dispatch_reason='Unprocessable Content' ;;
    500) dispatch_reason='Internal Server Error' ;;
    403) dispatch_reason='Forbidden' ;;
    429) dispatch_reason='Too Many Requests' ;;
    404) dispatch_reason='Not Found' ;;
    *) dispatch_reason='Test Status' ;;
  esac
  if [[ "$has_include" == true ]]; then
    printf 'HTTP/2.0 %s %s\n' "$dispatch_status" "$dispatch_reason"
    if [[ -n "${FAKE_DISPATCH_RETRY_AFTER:-}" ]]; then
      printf 'Retry-After: %s\n' "$FAKE_DISPATCH_RETRY_AFTER"
    fi
    if [[ -n "${FAKE_DISPATCH_RATE_REMAINING:-}" ]]; then
      printf 'X-RateLimit-Remaining: %s\n' "$FAKE_DISPATCH_RATE_REMAINING"
    fi
    if [[ -n "${FAKE_DISPATCH_RATE_RESET:-}" ]]; then
      printf 'X-RateLimit-Reset: %s\n' "$FAKE_DISPATCH_RATE_RESET"
    fi
    printf '\n'
  fi
  if [[ "$dispatch_status" != 204 ]]; then
    jq -cn --arg message "${FAKE_DISPATCH_ERROR_MESSAGE:-Forbidden}" '{message:$message}'
  fi
  if [[ "$dispatch_status" == 204 ]]; then
    exit 0
  fi
  exit "${FAKE_DISPATCH_GH_EXIT_CODE:-1}"
fi
case "$resource" in
  */git/ref/heads/*)
    if [[ -n "${FAKE_RECONCILER_RESOLUTION_LOG:-}" ]]; then
      printf '%s\n' "$resource" >> "$FAKE_RECONCILER_RESOLUTION_LOG"
    fi
    if [[ "${FAKE_PRUNE_BASE_REF_STATUS:-}" == 404 ]]; then
      echo 'gh: Not Found (HTTP 404)' >&2
      exit 1
    elif [[ -n "${FAKE_PRUNE_BASE_REF_STATUS:-}" ]]; then
      echo "gh: request failed (HTTP ${FAKE_PRUNE_BASE_REF_STATUS})" >&2
      exit 1
    fi
    if [[ "$has_jq" == true ]]; then
      printf '%s' "${FAKE_PRUNE_BASE_REF_SHA:-bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb}"
    else
      jq -cn \
        --arg sha "${FAKE_PRUNE_BASE_REF_SHA:-bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb}" \
        '{object:{sha:$sha}}'
    fi
    ;;
  */pulls/901/files\?per_page=100)
    if [[ -n "${FAKE_RECONCILER_RESOLUTION_LOG:-}" ]]; then
      printf '%s\n' "$resource" >> "$FAKE_RECONCILER_RESOLUTION_LOG"
    fi
    printf '%s' '[[{"filename":"README.md"}]]'
    ;;
  */pulls/902/files\?per_page=100)
    printf '%s' '[[{"filename":"README.md"}]]'
    ;;
  */pulls/101/files\?per_page=100|*/pulls/102/files\?per_page=100)
    printf '%s' '[[{"filename":"README.md"}]]'
    ;;
  */commits/*)
    if [[ -n "${FAKE_RECONCILER_RESOLUTION_LOG:-}" ]]; then
      printf '%s\n' "$resource" >> "$FAKE_RECONCILER_RESOLUTION_LOG"
    fi
    commit_head="${FAKE_PR_901_HEAD:-head-901}"
    case "$commit_head" in
      head-901|stale-head) commit_head=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa ;;
    esac
    jq -cn \
      --arg head "$commit_head" \
      '{sha:"cccccccccccccccccccccccccccccccccccccccc",parents:[{sha:"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"},{sha:$head}]}'
    ;;
  */actions/runs/42)
    if [[ "$has_jq" != true ]]; then
      printf '%s' '{"conclusion":"success","head_sha":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","path":".github/workflows/preview.yml","event":"pull_request","repository":{"full_name":"example/FireMUD"},"pull_requests":[{"number":900,"head":{"sha":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}}]}'
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
    printf '%s\n' "$resource" >> "$FAKE_PRIORITY_QUERY_LOG"
    if [[ "$*" == *"per_page=100&page=11"* ]]; then
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
      labels_json="$(fake_labels_json "$priority" "$labels_valid")"
      mergeable_json=true
      mergeable_state=clean
      if [[ -n "${FAKE_TARGET_MERGEABILITY_SEQUENCE:-}" ]]; then
        metadata_count=0
        if [[ -f "$FAKE_TARGET_METADATA_CALLS" ]]; then
          metadata_count="$(<"$FAKE_TARGET_METADATA_CALLS")"
        fi
        metadata_count=$((metadata_count + 1))
        printf '%s' "$metadata_count" > "$FAKE_TARGET_METADATA_CALLS"
        IFS=',' read -r -a metadata_sequence <<<"$FAKE_TARGET_MERGEABILITY_SEQUENCE"
        sequence_index=$((metadata_count - 1))
        if (( sequence_index >= ${#metadata_sequence[@]} )); then
          sequence_index=$((${#metadata_sequence[@]} - 1))
        fi
        case "${metadata_sequence[$sequence_index]}" in
          unknown) mergeable_state=unknown ;;
          clean) : ;;
          conflict) mergeable_json=false; mergeable_state=conflicting ;;
          malformed) mergeable_json='"invalid"'; mergeable_state=clean ;;
          *) echo "unsupported mergeability fixture state" >&2; exit 1 ;;
        esac
      fi
      jq -cn \
        --arg state "${FAKE_TARGET_STATE:-open}" \
        --arg head "$FAKE_TARGET_HEAD" \
        --arg repository "${FAKE_TARGET_REPOSITORY:-example/FireMUD}" \
        --arg base "${FAKE_TARGET_BASE_REF:-develop}" \
        --arg author "${FAKE_TARGET_AUTHOR:-human}" \
        --argjson labels "$labels_json" \
        --arg base_sha "${FAKE_TARGET_BASE_SHA:-bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb}" \
        --arg merge_sha cccccccccccccccccccccccccccccccccccccccc \
        --argjson mergeable "$mergeable_json" \
        --arg mergeable_state "$mergeable_state" \
        '{state: $state, head: {sha: $head, repo: {full_name: $repository}}, base: {ref: $base, sha: $base_sha, repo: {full_name: "example/FireMUD"}}, merge_commit_sha: $merge_sha, mergeable: $mergeable, mergeable_state: $mergeable_state, user: {login: $author}, labels: $labels}'
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
    printf '%s\t%s\t%s\n' "${FAKE_TARGET_STATE:-open}" "$FAKE_TARGET_HEAD" "$(fake_labels_json "$priority" "$labels_valid" | base64 | tr -d '\n')"
    ;;
  */pulls/901)
    if [[ -n "${FAKE_RETRY_MERGE_STATE:-}" ]]; then
      jq -cn --arg result "$FAKE_RETRY_MERGE_STATE" '
        {number:901,state:"closed",merged:($result != "unmerged"),
         head:{repo:{full_name:"example/FireMUD"}},
         base:{repo:{full_name:(if $result == "wrong-owner" then "example/another" else "example/FireMUD" end)}}}'
      exit 0
    fi
    live_head="${FAKE_PR_901_HEAD:-head-901}"
    case "$live_head" in
      head-901|stale-head) live_head=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa ;;
    esac
    live_state="${FAKE_PR_901_STATE:-open}"
    if [[ "$has_jq" == true ]]; then
      printf '%s\t%s\t%s\n' \
        "$live_state" \
        "$live_head" \
        "$(fake_labels_json "${FAKE_PR_901_PRIORITY:-true}" valid | base64 | tr -d '\n')"
      exit 0
    fi
    mergeable_state_json="$(jq -cn --arg value "${FAKE_PR_901_MERGEABLE_STATE:-clean}" '$value')"
    case "${FAKE_PR_901_MERGEABLE_STATE:-clean}" in
      true|false|null)
        mergeable_state_json="${FAKE_PR_901_MERGEABLE_STATE}"
        ;;
    esac
    jq -cn \
      --arg state "$live_state" \
      --arg head "$live_head" \
      --arg base_ref "${FAKE_PR_901_BASE_REF:-develop}" \
      --arg head_repository "${FAKE_PR_901_HEAD_REPOSITORY:-example/FireMUD}" \
      --arg head_ref "${FAKE_PR_901_HEAD_REF:-feature-preview}" \
      --arg base_repository "${FAKE_PR_901_BASE_REPOSITORY:-example/FireMUD}" \
      --arg mergeable "${FAKE_PR_901_MERGEABLE:-true}" \
      --argjson mergeable_state "$mergeable_state_json" \
      --arg priority "${FAKE_PR_901_PRIORITY:-true}" \
      '{state:$state,head:{sha:$head,ref:$head_ref,repo:{full_name:$head_repository}},base:{ref:$base_ref,repo:{full_name:$base_repository}},merge_commit_sha:"cccccccccccccccccccccccccccccccccccccccc",changed_files:1,mergeable:($mergeable == "true"),mergeable_state:$mergeable_state,user:{login:"human"},labels:(if $priority == "true" then [{name:"preview:priority"}] else [] end)}'
    ;;
  */pulls/902)
    if [[ "$has_jq" == true ]]; then
      printf 'open\t%s\t%s\n' "${FAKE_PR_902_HEAD:-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa}" \
        "$(fake_labels_json false valid | base64 | tr -d '\n')"
      exit 0
    fi
    jq -cn \
      --arg head "${FAKE_PR_902_HEAD:-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa}" \
      --arg head_ref "${FAKE_PR_902_HEAD_REF:-feature-preview}" \
      '{state:"open",head:{sha:$head,ref:$head_ref,repo:{full_name:"example/FireMUD"}},base:{ref:"develop",repo:{full_name:"example/FireMUD"}},merge_commit_sha:"cccccccccccccccccccccccccccccccccccccccc",changed_files:1,mergeable:true,mergeable_state:"clean",user:{login:"human"},labels:[]}'
    ;;
  */pulls/101)
    if [[ -n "${FAKE_PRUNE_QUERY_LOG:-}" ]]; then
      printf '%s\n' "$resource" >> "$FAKE_PRUNE_QUERY_LOG"
    fi
    if [[ "${FAKE_PRUNE_QUERY_FAIL:-false}" == "true" ]]; then
      exit 1
    fi
    if [[ "${FAKE_PRUNE_JQ_FAIL:-false}" == "true" ]]; then
      printf '%s' '{invalid-json'
      exit 0
    fi
    if [[ "$has_jq" == true ]]; then
      prune_priority="${FAKE_PR_101_PRIORITY:-false}"
      prune_calls=0
      if [[ -f "$FAKE_PR_101_CALLS" ]]; then
        prune_calls="$(<"$FAKE_PR_101_CALLS")"
      fi
      prune_calls=$((prune_calls + 1))
      printf '%s' "$prune_calls" > "$FAKE_PR_101_CALLS"
      if [[ "${FAKE_PR_101_GAINS_PRIORITY:-false}" == true && "$prune_calls" -gt 1 ]]; then
        prune_priority=true
      fi
      printf 'open\thead-101\t%s\n' "$(fake_labels_json "$prune_priority" "${FAKE_PR_101_LABELS_VALID:-valid}" | base64 | tr -d '\n')"
      exit 0
    fi
    if [[ -n "${FAKE_PRUNE_METADATA:-}" ]]; then
      prune_metadata_lines=()
      mapfile -t prune_metadata_lines < <(printf '%b' "$FAKE_PRUNE_METADATA")
      if (( ${#prune_metadata_lines[@]} == 1 )); then
        prune_record="${prune_metadata_lines[0]}"
        prune_remainder=''
      else
        prune_record="${prune_metadata_lines[0]:-}"
        prune_remainder='extra-records'
      fi
      IFS=$'\t' read -r prune_state prune_base_ref prune_author prune_labels_base64 prune_extra <<<"$prune_record"
      if [[ -n "$prune_remainder" || -n "${prune_extra:-}" || -z "${prune_labels_base64:-}" ]]; then
        prune_state='invalid'
        prune_base_ref='invalid'
        prune_author='invalid'
        prune_labels_json='{}'
      elif ! prune_labels_json="$(printf '%s' "$prune_labels_base64" | base64 --decode 2>/dev/null)" ||
        ! jq -e 'type == "array"' <<<"$prune_labels_json" >/dev/null 2>&1; then
        prune_labels_json='{}'
      fi
    else
      prune_state='open'
      prune_base_ref='develop'
      prune_author='human'
      prune_labels_json='[]'
    fi
    prune_mergeable_json="$(jq -cn --arg value "${FAKE_PRUNE_MERGEABLE:-true}" '$value == "true"')"
    case "${FAKE_PRUNE_MERGEABLE_SHAPE:-boolean}" in
      boolean) ;;
      null) prune_mergeable_json=null ;;
      string) prune_mergeable_json='"false"' ;;
      object) prune_mergeable_json='{}' ;;
      *) exit 1 ;;
    esac
    prune_mergeable_state_json="$(jq -cn --arg value "${FAKE_PRUNE_MERGEABLE_STATE:-clean}" '$value')"
    case "${FAKE_PRUNE_MERGEABLE_STATE_SHAPE:-string}" in
      string) ;;
      null) prune_mergeable_state_json=null ;;
      boolean) prune_mergeable_state_json=true ;;
      object) prune_mergeable_state_json='{}' ;;
      *) exit 1 ;;
    esac
    jq -cn \
      --arg state "$prune_state" \
      --arg base_ref "$prune_base_ref" \
      --arg author "$prune_author" \
      --arg head_repository "${FAKE_PRUNE_HEAD_REPOSITORY:-example/FireMUD}" \
      --arg base_repository "${FAKE_PRUNE_BASE_REPOSITORY:-example/FireMUD}" \
      --argjson mergeable "$prune_mergeable_json" \
      --argjson mergeable_state "$prune_mergeable_state_json" \
      --argjson labels "$prune_labels_json" \
      '{state:$state,head:{sha:"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",repo:{full_name:$head_repository}},base:{ref:$base_ref,repo:{full_name:$base_repository}},merge_commit_sha:"cccccccccccccccccccccccccccccccccccccccc",changed_files:1,mergeable:$mergeable,mergeable_state:$mergeable_state,user:{login:$author},labels:$labels}'
    exit 0
  ;;
  */pulls/102)
    if [[ -n "${FAKE_PRUNE_QUERY_LOG:-}" ]]; then
      printf '%s\n' "$resource" >> "$FAKE_PRUNE_QUERY_LOG"
    fi
    if [[ "$has_jq" != true ]]; then
      prune_state="open"
      prune_base_ref="develop"
      prune_labels_json='[]'
      if [[ "${FAKE_PRUNE_MULTI_TEST:-false}" == true ]]; then
        prune_base_ref='feature/stack'
        prune_labels_json="$(fake_labels_json "${FAKE_PR_102_PRIORITY:-true}" valid)"
      fi
      if [[ -n "${FAKE_PRUNE_METADATA:-}" ]]; then
        prune_metadata_payload="$(printf '%b' "$FAKE_PRUNE_METADATA")"
        IFS=$'\t' read -r prune_state metadata_base_ref _ <<<"${prune_metadata_payload%%$'\n'*}"
        if [[ "${FAKE_PRUNE_MULTI_TEST:-false}" != true ]]; then
          prune_base_ref="$metadata_base_ref"
        fi
      fi
      jq -cn \
        --arg state "$prune_state" \
        --arg base_ref "$prune_base_ref" \
        --arg head_repository "${FAKE_PRUNE_HEAD_REPOSITORY:-example/FireMUD}" \
        --arg base_repository "${FAKE_PRUNE_BASE_REPOSITORY:-example/FireMUD}" \
        --arg mergeable "${FAKE_PRUNE_MERGEABLE:-true}" \
        --arg mergeable_state "${FAKE_PRUNE_MERGEABLE_STATE:-clean}" \
        --argjson labels "$prune_labels_json" \
        '{state:$state,head:{sha:"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",repo:{full_name:$head_repository}},base:{ref:$base_ref,repo:{full_name:$base_repository}},merge_commit_sha:"cccccccccccccccccccccccccccccccccccccccc",changed_files:1,mergeable:($mergeable == "true"),mergeable_state:$mergeable_state,user:{login:"human"},labels:$labels}'
      exit 0
    fi
    if [[ "${FAKE_PRUNE_MULTI_TEST:-false}" == true ]]; then
      printf 'open\tfeature/stack\thuman\t%s\n' "$(fake_labels_json "${FAKE_PR_102_PRIORITY:-true}" valid | base64 | tr -d '\n')"
    else
      printf 'open\thead-102\t%s\n' "$(fake_labels_json "${FAKE_PR_102_PRIORITY:-true}" valid | base64 | tr -d '\n')"
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
if [[ -n "${FAKE_IDENTITY_HELPER_STDIN_LOG:-}" ]]; then
  helper_stdin=''
  if IFS= read -r helper_stdin; then
    printf '%s\n' "$helper_stdin" >> "$FAKE_IDENTITY_HELPER_STDIN_LOG"
  else
    printf '<eof>\n' >> "$FAKE_IDENTITY_HELPER_STDIN_LOG"
  fi
fi
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
cat > "$TEMP_DIR/revalidate-source-parent-advance" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
[[ "$#" -eq 5 ]] || exit 1
[[ "$1" == 900 && "$2" == "$FAKE_TARGET_HEAD" && "$3" == "$FAKE_TARGET_BASE_SHA" && "$4" == "$FAKE_TARGET_MERGE_SHA" ]] || exit 1
count=0
if [[ -f "$FAKE_SOURCE_BINDING_CALLS" ]]; then
  count="$(<"$FAKE_SOURCE_BINDING_CALLS")"
fi
count=$((count + 1))
printf '%s' "$count" > "$FAKE_SOURCE_BINDING_CALLS"
if (( count >= 3 )); then
  echo "parent base advanced while the child head remained unchanged" >&2
  exit 1
fi
EOF
chmod +x \
  "$TEMP_DIR/bin/kubectl" \
  "$TEMP_DIR/bin/gh" \
  "$TEMP_DIR/bin/helm" \
  "$TEMP_DIR/bin/sleep" \
  "$TEMP_DIR/delete" \
  "$TEMP_DIR/identity-request" \
  "$TEMP_DIR/identity-wait" \
  "$TEMP_DIR/publish" \
  "$TEMP_DIR/revalidate-source-parent-advance"

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
export FAKE_SOURCE_BINDING_CALLS="$TEMP_DIR/source-binding-calls"
export FAKE_COMMENT_METHOD_LOG="$TEMP_DIR/comment-method.log"
export FAKE_ANNOTATE_LOG="$TEMP_DIR/annotate.log"
export FAKE_ANNOTATE_FAILURE_MARKER="$TEMP_DIR/annotate-failure.marker"
export FAKE_DISPATCH_LOG="$TEMP_DIR/dispatch.log"
export FAKE_RETRY_HOLDER="$TEMP_DIR/proof-retry-holder.json"
export FAKE_RETRY_PATCH_LOG="$TEMP_DIR/proof-retry-patch.log"
export FAKE_SLEEP_LOG="$TEMP_DIR/sleep.log"
export FAKE_NAMESPACE_LIST_CALLS="$TEMP_DIR/namespace-list-calls"
export FAKE_RECENT_HANDOFF_LOOKUP_LOG="$TEMP_DIR/recent-handoff-lookup.log"
export FAKE_TRUSTED_CONSUMER_COMPLETED_MARKER="$TEMP_DIR/trusted-consumer-completed.marker"
export FAKE_COMMENT_TARGET_LOG="$TEMP_DIR/comment-target.log"
export FAKE_PREVIOUS_COMMENT_ID_LOG="$TEMP_DIR/previous-comment-id.log"
export FAKE_TARGET_CALLS="$TEMP_DIR/target-calls"
export FAKE_TARGET_METADATA_CALLS="$TEMP_DIR/target-metadata-calls"
export FAKE_PR_101_CALLS="$TEMP_DIR/pr-101-calls"
export FAKE_PRUNE_QUERY_LOG="$TEMP_DIR/prune-query.log"
export FAKE_PRIORITY_QUERY_LOG="$TEMP_DIR/priority-query.log"
export FAKE_NAMESPACE_JSON_CALLS="$TEMP_DIR/namespace-json-calls"
export FAKE_RECONCILER_RESOLUTION_LOG="$TEMP_DIR/reconciler-resolution.log"
export FAKE_NAMESPACE_SNAPSHOT_LOG="$TEMP_DIR/namespace-snapshot.log"
export FAKE_NAMESPACE_SNAPSHOT_CALLS="$TEMP_DIR/namespace-snapshot-calls"
export FAKE_RUNTIME_KUBECTL_LOG="$TEMP_DIR/runtime-kubectl.log"
export FAKE_RUNTIME_DELETE_UID_LOG="$TEMP_DIR/runtime-delete-uid.log"
export FAKE_RUNTIME_NAMESPACE_DELETED_MARKER="$TEMP_DIR/runtime-namespace-deleted"
export FAKE_RUNTIME_WAIT_MARKER="$TEMP_DIR/runtime-wait-failed"
export FAKE_HELM_LOG="$TEMP_DIR/helm.log"
export FAKE_IDENTITY_LOG="$TEMP_DIR/identity.log"
export FAKE_IDENTITY_LIST_KUBECONFIG_LOG="$TEMP_DIR/identity-list-kubeconfig.log"
export FAKE_IDENTITY_REQUEST_LOG="$TEMP_DIR/identity-request.log"
export FAKE_IDENTITY_WAIT_LOG="$TEMP_DIR/identity-wait.log"
export FAKE_OPERATION_SEQUENCE="$TEMP_DIR/operation-sequence.log"
export HOSTED_IDENTITY_REQUEST_SCRIPT="$TEMP_DIR/identity-request"
export HOSTED_IDENTITY_WAIT_SCRIPT="$TEMP_DIR/identity-wait"
export HOSTED_IDENTITY_REQUESTER_KUBECONFIG="$TEMP_DIR/requester.kubeconfig"
touch "$HOSTED_IDENTITY_REQUESTER_KUBECONFIG"
export FAKE_TARGET_HEAD="aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
export FAKE_TARGET_BASE_SHA="bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
export FAKE_TARGET_MERGE_SHA="cccccccccccccccccccccccccccccccccccccccc"
priority_candidate_head="aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
export FAKE_NAMESPACE_ROWS='2026-01-01T00:00:00Z|pr-101|101|2026-01-02T00:00:00Z|bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb|head-101|cccccccccccccccccccccccccccccccccccccccc|image-101\n2026-01-03T00:00:00Z|pr-102|102|2026-01-04T00:00:00Z|base-102|head-102|merge-102|image-102\n'
priority_labels_base64="$(printf '%s' '[{"name":"preview:priority"}]' | base64 | tr -d '\n')"
ordinary_labels_base64="$(printf '%s' '[]' | base64 | tr -d '\n')"
adversarial_priority_labels_base64="$(printf '%s' '[{"name":"preview:priority"},{"name":"quote\"slash\\label"}]' | base64 | tr -d '\n')"
adversarial_labels_base64="$(printf '%s' '[{"name":"custom:label"},{"name":"quote\"slash\\label"}]' | base64 | tr -d '\n')"
invalid_json_labels_base64="$(printf '%s' '{invalid-json' | base64 | tr -d '\n')"

reset_case() {
  rm -f "$FAKE_DELETE_LOG" "$FAKE_DELETE_TIMEOUT_LOG" "$FAKE_PUBLISH_LOG" "$FAKE_PUBLISHED_STATE" "$FAKE_PUBLISH_CALLS" "$FAKE_SOURCE_BINDING_CALLS" "$FAKE_COMMENT_METHOD_LOG" "$FAKE_COMMENT_TARGET_LOG" "$FAKE_PREVIOUS_COMMENT_ID_LOG" "$FAKE_ANNOTATE_LOG" "$FAKE_ANNOTATE_FAILURE_MARKER" "$FAKE_DISPATCH_LOG" "$FAKE_RETRY_PATCH_LOG" "$FAKE_RETRY_HOLDER" "$FAKE_TRUSTED_CONSUMER_COMPLETED_MARKER" "$FAKE_SLEEP_LOG" "$FAKE_NAMESPACE_LIST_CALLS" "$FAKE_RECENT_HANDOFF_LOOKUP_LOG" "$FAKE_TARGET_CALLS" "$FAKE_TARGET_METADATA_CALLS" "$FAKE_PR_101_CALLS" "$FAKE_PRUNE_QUERY_LOG" "$FAKE_PRIORITY_QUERY_LOG" "$FAKE_NAMESPACE_JSON_CALLS" "$FAKE_RECONCILER_RESOLUTION_LOG" "$FAKE_NAMESPACE_SNAPSHOT_LOG" "$FAKE_NAMESPACE_SNAPSHOT_CALLS" "$FAKE_RUNTIME_KUBECTL_LOG" "$FAKE_RUNTIME_DELETE_UID_LOG" "$FAKE_RUNTIME_NAMESPACE_DELETED_MARKER" "$FAKE_RUNTIME_WAIT_MARKER" "$FAKE_HELM_LOG" "$FAKE_IDENTITY_LOG" "$FAKE_IDENTITY_LIST_KUBECONFIG_LOG" "$FAKE_IDENTITY_REQUEST_LOG" "$FAKE_IDENTITY_WAIT_LOG" "$FAKE_OPERATION_SEQUENCE" "$TEMP_DIR/output"
  export GITHUB_OUTPUT="$TEMP_DIR/output"
  export FAKE_TARGET_PRIORITY=true
  export FAKE_TARGET_LABELS_VALID=valid
  export FAKE_TARGET_RAW_QUERY_FAIL=false
  export FAKE_TARGET_JQ_QUERY_FAIL=false
  export FAKE_TARGET_STATE=open
  export FAKE_TARGET_REPOSITORY=example/FireMUD
  export FAKE_TARGET_BASE_REF=develop
  export FAKE_TARGET_BASE_SHA="bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
  export FAKE_TARGET_AUTHOR=human
  export FAKE_TARGET_LOSES_PRIORITY=false
  unset FAKE_TARGET_MERGEABILITY_SEQUENCE
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
  export FAKE_PRIORITY_CANDIDATE_NAMESPACE_LOOKUP_ERROR=false
  export FAKE_PRIORITY_CANDIDATE_NAMESPACE_ABSENT=false
  export FAKE_ACTIVE_PREVIEW_RUNS_JSON='[]'
  unset FAKE_ACTIVE_PREVIEW_RUN_PAGES_JSON
  export FAKE_RECENT_PREVIEW_SOURCE_RUNS_JSON='{"total_count":0,"workflow_runs":[]}'
  export FAKE_RECENT_PREVIEW_DISPATCH_RUNS_JSON='{"total_count":0,"workflow_runs":[]}'
  export FAKE_RECENT_TRUSTED_CONSUMER_RUNS_JSON='{"total_count":0,"workflow_runs":[]}'
  export FAKE_PREVIEW_DISPATCH_JOBS_JSON=null
  export FAKE_PREVIEW_DISPATCH_LATEST_JSON=null
  export FAKE_PR_901_OWNER=''
  export FAKE_PR_901_HEAD="$priority_candidate_head"
  export FAKE_PR_901_BASE_REF=develop
  export FAKE_PR_901_PRIORITY=true
  export FAKE_PR_901_STATE=open
  unset FAKE_PR_901_MERGEABLE FAKE_PR_901_MERGEABLE_STATE
  export FAKE_PR_901_REQUESTED_HEAD=''
  export FAKE_PR_901_NAMESPACE_ABSENT=false
  export FAKE_PR_901_NAMESPACE_ABSENT_ON_RECHECK=false
  unset FAKE_PR_901_RECHECK_HEAD FAKE_PR_901_RECHECK_REQUESTED_HEAD \
    FAKE_PR_901_RECHECK_NAMESPACE_UID \
    FAKE_PR_901_BASE_SHA FAKE_PR_901_MERGE_SHA FAKE_PR_901_IMAGE_TAG \
    FAKE_PR_901_REQUESTED_BASE_SHA FAKE_PR_901_REQUESTED_MERGE_SHA \
    FAKE_PR_901_REQUESTED_IMAGE_TAG
  unset FAKE_PR_901_PROOF_RETRY_COUNT FAKE_PR_901_PROOF_RETRY_BASE_SHA \
    FAKE_PR_901_PROOF_RETRY_HEAD_SHA FAKE_PR_901_PROOF_RETRY_MERGE_SHA \
    FAKE_PR_901_PROOF_RETRY_IMAGE_TAG
  export FAKE_NAMESPACE_SNAPSHOT_ERROR=false
  export FAKE_NAMESPACE_SNAPSHOT_RECHECK_ERROR=false
  export FAKE_NAMESPACE_SNAPSHOT_PARSE_FAIL=false
  export FAKE_NAMESPACE_SNAPSHOT_RECHECK_PARSE_FAIL=false
  export FAKE_ANNOTATE_ERROR=false
  export FAKE_ANNOTATE_CONFIRMATION_ERROR=false
  export FAKE_ANNOTATE_NAMESPACE_ABSENT_AFTER_FAILURE=false
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
  export FAKE_PRUNE_HEAD_REPOSITORY=example/FireMUD
  export FAKE_PRUNE_BASE_REPOSITORY=example/FireMUD
  export FAKE_PRUNE_MERGEABLE=true
  export FAKE_PRUNE_MERGEABLE_STATE=clean
  unset FAKE_PRUNE_MERGEABLE_SHAPE FAKE_PRUNE_MERGEABLE_STATE_SHAPE
  unset FAKE_PRUNE_BASE_REF_STATUS FAKE_PRUNE_BASE_REF_SHA
  export FAKE_PRUNE_QUERY_FAIL=false
  export FAKE_PRUNE_JQ_FAIL=false
  export FAKE_IDENTITY_REQUEST_FAIL=false
  export FAKE_IDENTITY_WAIT_FAIL=false
  export FAKE_IDENTITY_DELETE_FAIL=false
  export FAKE_IDENTITY_LIST_FAIL=false
  export FAKE_IDENTITY_LIST_JSON='{"apiVersion":"platform.firemud.dev/v1alpha1","kind":"HostedEnvironmentIdentityList","items":[]}'
  export FAKE_NAMESPACE_LIST_ERROR=false
  export FAKE_NAMESPACE_COUNT_SEQUENCE=''
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
  unset FAKE_RUNTIME_NAMESPACE_RESOURCE_VERSION FAKE_RUNTIME_DELETE_CURRENT_VERSION
  export FAKE_RUNTIME_DELETE_ERROR=false
  export FAKE_RUNTIME_WAIT_ERROR=false
  export FAKE_RUNTIME_RECHECK_ERROR=false
  export FAKE_RUNTIME_RECHECK_IDENTITY=''
  export FAKE_RUNTIME_RECHECK_UID=''
  export FAKE_RUNTIME_NAMESPACE_PRESENT_AFTER_WAIT=true
  export FAKE_RECORD_RUNTIME_CHECK=false
  export FAKE_IDENTITY_JSON='{"apiVersion":"platform.firemud.dev/v1alpha1","kind":"HostedEnvironmentIdentity","metadata":{"namespace":"firemud-system","name":"pr-101"}}'
  export HOSTED_IDENTITY_MODE=standalone
  unset PREVIEW_NAMESPACE_DELETE_TIMEOUT_SECONDS PREVIEW_DELETE_TIMEOUT
  export FAKE_ELIGIBILITY_OUTPUT=''
  export PREVIEW_ELIGIBILITY_SCRIPT="$ROOT_DIR/dev-tools/hosted/preview/preview-eligibility.py"
  export FAKE_NAMESPACE_ROWS='2026-01-01T00:00:00Z|pr-101|101|2026-01-02T00:00:00Z|bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb|head-101|cccccccccccccccccccccccccccccccccccccccc|image-101\n2026-01-03T00:00:00Z|pr-102|102|2026-01-04T00:00:00Z|base-102|head-102|merge-102|image-102\n'
  unset PREVIEW_REVALIDATE_SOURCE_BINDING_SCRIPT
}

run_proof_retry_contract() (
  local retry_reconciler="$ROOT_DIR/dev-tools/hosted/preview/reconcile-preview-deploys.sh"
  local base=bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb
  local head=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
  local merge=cccccccccccccccccccccccccccccccccccccccc
  local other_base=dddddddddddddddddddddddddddddddddddddddd
  local nonce=01234567-0123-4123-8123-0123456789ab
  local case_output="$TEMP_DIR/durable-proof-retry.out"

  seed_retry_holder() {
    local count="$1" seed_base="${2:-$base}" repo="${3:-$GITHUB_REPOSITORY}"
    jq -cn --argjson count "$count" --arg base "$seed_base" --arg head "$head" \
      --arg merge "$merge" --arg repo "$repo" --arg nonce "$nonce" '
      {apiVersion:"v1",kind:"ConfigMap",
       metadata:{namespace:"firemud-system",name:"firemud-preview-proof-retries",uid:"uid-holder",resourceVersion:"1"},
       data:{schemaVersion:"1","pr-901":({repository:$repo,namespace:"pr-901",namespaceUid:"uid-pr-901",
         nonce:$nonce,count:$count,base:$base,head:$head,merge:$merge,image:$base} | tojson)}}' > "$FAKE_RETRY_HOLDER"
  }

  seed_reserved_holder_with_old_namespace_version() {
    seed_retry_holder 1
    jq -c '.data["pr-901"] |= (fromjson
      | . + {stageVersion:2,phase:"RESERVED",predecessor:null,
             namespaceResourceVersion:"rv-before-metadata-update",
             holderUid:"uid-holder",holderResourceVersion:"0"}
      | tojson)' "$FAKE_RETRY_HOLDER" > "$FAKE_RETRY_HOLDER.next"
    mv "$FAKE_RETRY_HOLDER.next" "$FAKE_RETRY_HOLDER"
  }

  seed_started_holder() {
    seed_retry_holder 1
    jq -c '.data["pr-901"] |= (fromjson as $prior
      | $prior + {count:2,stageVersion:2,phase:"SEND_STARTED",predecessor:$prior,
        namespaceResourceVersion:"rv-901",holderUid:"uid-holder",holderResourceVersion:"0"}
      | tojson)' "$FAKE_RETRY_HOLDER" >"$FAKE_RETRY_HOLDER.next"
    mv "$FAKE_RETRY_HOLDER.next" "$FAKE_RETRY_HOLDER"
  }

  fixture_reconcile() (
    cd "$ROOT_DIR"
    FAKE_OPEN_PRIORITY_ROWS="1\t901\thead-901\thuman\tdevelop\topen\t${adversarial_labels_base64}\n" \
      FAKE_PR_901_HEAD=head-901 FAKE_PR_901_REQUESTED_HEAD=head-901 \
      FAKE_PR_901_PROOF_COMPLETE=false DEFAULT_BRANCH=develop PREVIEW_MAX_ACTIVE=2 \
      bash "$retry_reconciler"
  )

  assert_retry_count() {
    jq -e --argjson count "$1" '(.data["pr-901"] | fromjson).count == $count' "$FAKE_RETRY_HOLDER" >/dev/null
  }

  reset_case
  fixture_reconcile >"$case_output"
  grep -Fq 'Recorded proof retry 1/3 for PR #901' "$case_output"
  assert_retry_count 1
  test "$(wc -l < "$FAKE_DISPATCH_LOG")" -eq 1
  test ! -e "$FAKE_ANNOTATE_LOG"
  jq -se 'length == 2 and .[0][0].value == "uid-holder" and .[0][1].value == "1"
    and (.[0][2].value|fromjson|.phase=="RESERVED") and (.[1][2].value|fromjson|.phase=="SEND_STARTED")' "$FAKE_RETRY_PATCH_LOG" >/dev/null

  # A same-UID, same-deployment metadata update can refresh a proven unsent
  # reservation's Namespace fence without allocating a new nonce or charge.
  reset_case
  seed_reserved_holder_with_old_namespace_version
  original_reserved="$(jq -c '.data["pr-901"] | fromjson' "$FAKE_RETRY_HOLDER")"
  fixture_reconcile >"$case_output"
  grep -Fq 'Refreshed Namespace resource-version fence for retained proof-retry reservation' "$case_output"
  test "$(wc -l < "$FAKE_DISPATCH_LOG")" -eq 1
  test "$(wc -l < "$FAKE_RETRY_PATCH_LOG")" -eq 2
  jq -se --argjson original "$original_reserved" '
    length == 2
    and ([ (.[0][2].value | fromjson), (.[1][2].value | fromjson) ] as [$refreshed,$started]
      | $refreshed.phase == "RESERVED"
       and $refreshed.nonce == $original.nonce
       and $refreshed.count == $original.count
       and $refreshed.predecessor == $original.predecessor
       and $refreshed.namespaceUid == $original.namespaceUid
       and $refreshed.namespaceResourceVersion == "rv-901"
       and ($refreshed | del(.namespaceResourceVersion)) == ($original | del(.namespaceResourceVersion))
       and $started.phase == "SEND_STARTED")
  ' "$FAKE_RETRY_PATCH_LOG" >/dev/null
  assert_retry_count 1

  reset_case
  seed_reserved_holder_with_old_namespace_version
  FAKE_PR_901_PROOF_REMOVED_ON_FRESH_REFRESH=true fixture_reconcile >"$case_output"
  test "$(wc -l < "$FAKE_DISPATCH_LOG")" -eq 1
  jq -e '.data["pr-901"] | fromjson | .phase == "SEND_STARTED"' "$FAKE_RETRY_HOLDER" >/dev/null

  reset_case
  seed_reserved_holder_with_old_namespace_version
  FAKE_PR_901_PROOF_COMPLETE=false FAKE_PR_901_PROOF_COMPLETES_ON_FRESH_REFRESH=true \
    fixture_reconcile >"$case_output"
  grep -Fq 'Released proof-retry reservation' "$case_output"
  test ! -e "$FAKE_DISPATCH_LOG"
  jq -e '.data["pr-901"] == null' "$FAKE_RETRY_HOLDER" >/dev/null
  test "$(wc -l < "$FAKE_RETRY_PATCH_LOG")" -eq 2

  reset_case
  seed_retry_holder 2
  if FAKE_DISPATCH_HTTP_STATUS=500 fixture_reconcile >"$case_output" 2>&1; then
    echo 'an unknown provider outcome must surface after charging the reservation' >&2
    exit 1
  fi
  grep -Fq 'proof-retry budget remains charged' "$case_output"
  assert_retry_count 3
  # A fresh callback after deletion, and then an unannotated replacement,
  # recognize the same exhausted tuple without dispatching or patching it.
  FAKE_PR_901_NAMESPACE_ABSENT=true fixture_reconcile >"$case_output" 2>&1
  grep -Fq 'leaving it charged' "$case_output"
  FAKE_PR_901_SNAPSHOT_HEAD='' FAKE_PR_901_NAMESPACE_UID=replacement-uid fixture_reconcile >"$case_output" 2>&1
  grep -Fq 'leaving it charged' "$case_output"
  test "$(wc -l < "$FAKE_DISPATCH_LOG")" -eq 1
  test "$(wc -l < "$FAKE_RETRY_PATCH_LOG")" -eq 2

  reset_case
  seed_retry_holder 3 "$other_base"
  FAKE_PR_901_NAMESPACE_ABSENT=true fixture_reconcile >"$case_output"
  grep -Fq 'Dispatching preview render for PR #901' "$case_output"
  assert_retry_count 3
  fixture_reconcile >"$case_output"
  assert_retry_count 1
  jq -e --arg base "$base" '(.data["pr-901"] | fromjson).base == $base' "$FAKE_RETRY_HOLDER" >/dev/null
  test "$(wc -l < "$FAKE_DISPATCH_LOG")" -eq 2

  for patch_case in denied different-nonce wrong-holder unchanged-version wrong-row lost-reserve; do
    reset_case
    seed_retry_holder 1
    local status=0
    FAKE_RETRY_PATCH_CASE="$patch_case" fixture_reconcile >"$case_output" 2>&1 || status=$?
    if [[ "$patch_case" == lost-reserve ]]; then
      test "$(wc -l < "$FAKE_RETRY_PATCH_LOG")" -eq 2
      test "$status" -eq 0
      test "$(wc -l < "$FAKE_DISPATCH_LOG")" -eq 1
      assert_retry_count 2
    else
      test "$status" -ne 0
      test "$(wc -l < "$FAKE_RETRY_PATCH_LOG")" -eq 1
      grep -Fq 'Unable to prove proof-retry reservation custody' "$case_output"
      test ! -e "$FAKE_DISPATCH_LOG"
    fi
  done

  reset_case
  if FAKE_RETRY_HOLDER_MISSING=true fixture_reconcile >"$case_output" 2>&1; then
    echo 'missing holder authorized dispatch' >&2
    exit 1
  fi
  grep -Fq 'Unable to verify the proof-retry holder' "$case_output"
  test ! -e "$FAKE_DISPATCH_LOG"

  reset_case
  seed_retry_holder 2 "$base" example/another
  if fixture_reconcile >"$case_output" 2>&1; then
    echo 'record with the wrong repository authorized dispatch' >&2
    exit 1
  fi
  test ! -e "$FAKE_RETRY_PATCH_LOG"
  test ! -e "$FAKE_DISPATCH_LOG"

  reset_case
  seed_retry_holder 1
  FAKE_RETRY_RUNTIME_CHANGE_BEFORE_SEND=true fixture_reconcile >"$case_output" 2>&1
  grep -Fq 'Namespace fence changed before proof retry dispatch' "$case_output"
  assert_retry_count 2
  test "$(wc -l < "$FAKE_RETRY_PATCH_LOG")" -eq 1
  test ! -e "$FAKE_DISPATCH_LOG"

  reset_case
  seed_retry_holder 1
  if FAKE_RETRY_PATCH_CASE=denied-start fixture_reconcile >"$case_output" 2>&1; then
    echo 'unacknowledged send-start authorized dispatch' >&2; exit 1
  fi
  test ! -e "$FAKE_DISPATCH_LOG"
  assert_retry_count 2
  retained="$(jq -er '.data["pr-901"] | fromjson | select(.phase=="RESERVED").nonce' "$FAKE_RETRY_HOLDER")"
  fixture_reconcile >"$case_output"
  assert_retry_count 2
  jq -e --arg nonce "$retained" '
    .data["pr-901"] | fromjson | .phase=="SEND_STARTED" and .nonce==$nonce
  ' "$FAKE_RETRY_HOLDER" >/dev/null
  test "$(wc -l < "$FAKE_DISPATCH_LOG")" -eq 1

  for transition_case in lost-send competing-start; do
    reset_case
    seed_retry_holder 1
    if FAKE_RETRY_PATCH_CASE="$transition_case" fixture_reconcile >"$case_output" 2>&1; then
      echo 'a send-start without an original acknowledgement authorized dispatch' >&2; exit 1
    fi
    test ! -e "$FAKE_DISPATCH_LOG"
    assert_retry_count 2
    jq -e '.data["pr-901"] | fromjson | .phase=="SEND_STARTED"' "$FAKE_RETRY_HOLDER" >/dev/null
    fixture_reconcile >"$case_output"
    grep -Fq 'no guarded rejection; leaving it charged' "$case_output"
    test ! -e "$FAKE_DISPATCH_LOG"
  done

  # An exact terminal no-op receiver preserves the old charge while allowing
  # the next reservation to proceed through the ordinary cap and CAS path.
  reset_case
  seed_started_holder
  local old_nonce retry_title source_jobs source_latest source_inventory
  old_nonce="$(jq -er '.data["pr-901"] | fromjson | .nonce' "$FAKE_RETRY_HOLDER")"
  retry_title="Preview retry ${old_nonce} pr-901-base-${base}-head-${head}-merge-${merge}"
  source_jobs="$(jq -cn --arg head "$base" '
    def step($name): {name:$name,status:"completed",conclusion:"success"};
    {total_count:2,jobs:[
      {name:"Preview Plan",run_id:5255,run_attempt:1,head_sha:$head,status:"completed",conclusion:"success",
       steps:[step("Explain preview skip")]},
      {name:"Render Sanitized Preview Artifact",run_id:5255,run_attempt:1,head_sha:$head,status:"completed",conclusion:"skipped",steps:[]}
    ]}')"
  source_latest="$(jq -cn --arg title "$retry_title" --arg head "$base" --arg now "$(date -u +%Y-%m-%dT%H:%M:%SZ)" '
    {id:5255,run_attempt:1,head_sha:$head,event:"repository_dispatch",workflow_id:173511323,
     name:"PR Preview Environment",display_title:$title,head_branch:"develop",
     repository:{full_name:"example/FireMUD"},head_repository:{full_name:"example/FireMUD"},
     path:".github/workflows/preview.yml",status:"completed",conclusion:"success",updated_at:$now}')"
  source_inventory="$(jq -cn --arg title "$retry_title" --arg head "$base" --arg now "$(date -u +%Y-%m-%dT%H:%M:%SZ)" '
    {total_count:1,workflow_runs:[{id:5255,run_attempt:1,head_sha:$head,event:"repository_dispatch",
     workflow_id:173511323,status:"completed",conclusion:"success",name:"PR Preview Environment",
     display_title:$title,head_branch:"develop",path:".github/workflows/preview.yml",
     repository:{full_name:"example/FireMUD"},head_repository:{full_name:"example/FireMUD"},
     updated_at:$now}]}')"
  FAKE_PREVIEW_DISPATCH_JOBS_JSON="$source_jobs" \
    FAKE_PREVIEW_DISPATCH_LATEST_JSON="$source_latest" \
    FAKE_RECENT_PREVIEW_DISPATCH_RUNS_JSON="$source_inventory" \
    fixture_reconcile >"$case_output"
  test "$(wc -l < "$FAKE_RETRY_PATCH_LOG")" -eq 3
  jq -se --arg old_nonce "$old_nonce" --argjson old_count 2 --argjson next_count 3 '
    select(length == 3) as $patches
    | ($patches[0][2].value | fromjson) as $settled
    | ($settled.count == $old_count and $settled.nonce == $old_nonce
       and ($settled | has("phase") | not))
      and (($patches[1][2].value | fromjson).count == $next_count)
      and (($patches[2][2].value | fromjson).phase == "SEND_STARTED")
      and (($patches[2][2].value | fromjson).nonce != $old_nonce)
  ' "$FAKE_RETRY_PATCH_LOG" >/dev/null
  jq -e --argjson count 3 --arg old_nonce "$old_nonce" \
    '(.data["pr-901"] | fromjson) | .count == $count and .phase == "SEND_STARTED" and .nonce != $old_nonce' \
    "$FAKE_RETRY_HOLDER" >/dev/null
  test "$(wc -l < "$FAKE_DISPATCH_LOG")" -eq 1

  # A terminal-looking inventory row with a different nonce cannot release the
  # original staged charge or authorize a replacement dispatch.
  reset_case
  seed_started_holder
  old_nonce="$(jq -er '.data["pr-901"] | fromjson | .nonce' "$FAKE_RETRY_HOLDER")"
  retry_title="Preview retry 12345678-1234-4234-8234-123456789abc pr-901-base-${base}-head-${head}-merge-${merge}"
  source_latest="$(jq -cn --arg title "$retry_title" --arg head "$base" --arg now "$(date -u +%Y-%m-%dT%H:%M:%SZ)" '
    {id:5255,run_attempt:1,head_sha:$head,event:"repository_dispatch",workflow_id:173511323,
     name:"PR Preview Environment",display_title:$title,head_branch:"develop",
     repository:{full_name:"example/FireMUD"},head_repository:{full_name:"example/FireMUD"},
     path:".github/workflows/preview.yml",status:"completed",conclusion:"success",updated_at:$now}')"
  source_inventory="$(jq -cn --arg title "$retry_title" --arg head "$base" --arg now "$(date -u +%Y-%m-%dT%H:%M:%SZ)" '
    {total_count:1,workflow_runs:[{id:5255,run_attempt:1,head_sha:$head,event:"repository_dispatch",
     workflow_id:173511323,status:"completed",conclusion:"success",name:"PR Preview Environment",
     display_title:$title,head_branch:"develop",path:".github/workflows/preview.yml",
     repository:{full_name:"example/FireMUD"},head_repository:{full_name:"example/FireMUD"},updated_at:$now}]}')"
  FAKE_PREVIEW_DISPATCH_JOBS_JSON="$source_jobs" \
    FAKE_PREVIEW_DISPATCH_LATEST_JSON="$source_latest" \
    FAKE_RECENT_PREVIEW_DISPATCH_RUNS_JSON="$source_inventory" \
    fixture_reconcile >"$case_output" 2>&1
  jq -e --arg nonce "$old_nonce" \
    '(.data["pr-901"] | fromjson) | .phase == "SEND_STARTED" and .nonce == $nonce and .count == 2' \
    "$FAKE_RETRY_HOLDER" >/dev/null
  test ! -e "$FAKE_RETRY_PATCH_LOG"
  test ! -e "$FAKE_DISPATCH_LOG"

  for custody in exact updated changed; do
    reset_case
    seed_retry_holder 2
    local mutate="$custody"
    [[ "$custody" != exact ]] || mutate=false
    if FAKE_RETRY_CUSTODY_CHANGED_AT_POST="$mutate" FAKE_DISPATCH_HTTP_STATUS=422 fixture_reconcile >"$case_output" 2>&1; then
      echo 'confirmed rejection was reported successful' >&2
      exit 1
    fi
    test "$(wc -l < "$FAKE_DISPATCH_LOG")" -eq 1
    if [[ "$custody" != changed ]]; then
      assert_retry_count 2
      jq -e --arg nonce "$nonce" '(.data["pr-901"] | fromjson).nonce == $nonce' "$FAKE_RETRY_HOLDER" >/dev/null
      test "$(wc -l < "$FAKE_RETRY_PATCH_LOG")" -eq 3
      if [[ "$custody" == updated ]]; then
        jq -e '.data | has("pr-902")' "$FAKE_RETRY_HOLDER" >/dev/null
      fi
    else
      assert_retry_count 3
      test "$(wc -l < "$FAKE_RETRY_PATCH_LOG")" -eq 2
    fi
  done

  reset_case
  if FAKE_DISPATCH_HTTP_STATUS=422 fixture_reconcile >"$case_output" 2>&1; then
    echo 'confirmed initial rejection was reported successful' >&2
    exit 1
  fi
  jq -e '.data == {schemaVersion:"1"}' "$FAKE_RETRY_HOLDER" >/dev/null

  reset_case
  seed_retry_holder 2
  if FAKE_DISPATCH_HTTP_STATUS=429 FAKE_DISPATCH_RETRY_AFTER=30 fixture_reconcile >"$case_output" 2>&1; then
    echo 'rate-limited rejection was reported successful' >&2
    exit 1
  fi
  assert_retry_count 2
  test "$(<"$FAKE_SLEEP_LOG")" = 30

  reset_case
  local legacy
  legacy="$(jq -cn --arg base "$base" --arg head "$head" --arg merge "$merge" '
    {"firemud.dev/proof-retry-count":"3","firemud.dev/proof-retry-base-sha":$base,
     "firemud.dev/proof-retry-head-sha":$head,"firemud.dev/proof-retry-merge-sha":$merge,
     "firemud.dev/proof-retry-image-tag":$base}')"
  FAKE_LEGACY_RETRY_JSON="$legacy" FAKE_RETRY_RUNTIME_DELETED_AFTER_REQUEST=true \
    FAKE_RUNTIME_NAMESPACE_UID=uid-pr-901 bash "$DELETE_HOSTED_NAMESPACE" pr-901 pr-901 >"$case_output"
  assert_retry_count 3
  test "$(<"$FAKE_RUNTIME_DELETE_UID_LOG")" = uid-pr-901
  # Stop between deletion and restore. There is no recreated Namespace or
  # workflow output to recover, and the next callback still sees count three.
  FAKE_PR_901_NAMESPACE_ABSENT=true fixture_reconcile >"$case_output" 2>&1
  grep -Fq 'proof retry budget exhausted' "$case_output"
  test ! -e "$FAKE_DISPATCH_LOG"

  reset_case
  if FAKE_LEGACY_RETRY_JSON='{"firemud.dev/proof-retry-count":""}' \
    bash "$DELETE_HOSTED_NAMESPACE" pr-901 pr-901 >"$case_output" 2>&1; then
    echo 'malformed legacy record permitted deletion' >&2
    exit 1
  fi
  test ! -e "$FAKE_RUNTIME_DELETE_UID_LOG"

  reset_case
  # Benign multiline placeholder text is not retention evidence. Exercise the
  # deletion entry point with the local fake; the retain and delete mocks must
  # both remain untouched when the metadata validator refuses it.
  local invalid_multiline_legacy
  invalid_multiline_legacy="$(jq -c '
    .["firemud.dev/proof-retry-image-tag"] = ("not an image tag" + "\n" + "still not an image tag")
  ' <<<"$legacy")"
  if FAKE_LEGACY_RETRY_JSON="$invalid_multiline_legacy" FAKE_RUNTIME_NAMESPACE_UID=uid-pr-901 \
    bash "$DELETE_HOSTED_NAMESPACE" pr-901 pr-901 >"$case_output" 2>&1; then
    echo 'multiline legacy record permitted deletion' >&2
    exit 1
  fi
  grep -Fq 'unable to read legacy proof-retry evidence for pr-901; refusing deletion' "$case_output"
  test ! -e "$FAKE_RUNTIME_DELETE_UID_LOG"
  test ! -e "$FAKE_RETRY_PATCH_LOG"

  # Validate the retained annotation before any namespace operations are possible.
  (
    # shellcheck source=/dev/null
    source "$ROOT_DIR/dev-tools/hosted/preview/preview-proof-retry-record.shlib"
    snapshot="$(jq -cn --argjson legacy "$legacy" '
      {metadata:{name:"pr-901",uid:"uid-pr-901",
        labels:{"firemud.dev/preview":"true","firemud.dev/pr-number":"901"},
        annotations:$legacy}}')"
    legacy_preview_retry_entry "$snapshot" pr-901 |
      jq -e --arg image "$base" '.count == 3 and .image == $image' >/dev/null
    multiline_snapshot="$(jq -c --arg image "$base" '
      .metadata.annotations["firemud.dev/proof-retry-image-tag"] = ($image + "\n" + $image)
    ' <<<"$snapshot")"
    if legacy_preview_retry_entry "$multiline_snapshot" pr-901 >"$case_output" 2>&1; then
      echo 'legacy retry validator accepted a multiline field' >&2
      exit 1
    fi
    grep -Fq 'malformed legacy preview proof-retry annotations' "$case_output"
  )

  for terminal_case in unmerged wrong-owner runtime-live merged; do
    reset_case
    seed_retry_holder 3
    local namespace_absent=true
    [[ "$terminal_case" != runtime-live ]] || namespace_absent=false
    FAKE_NAMESPACE_ROWS='' FAKE_PR_901_NAMESPACE_ABSENT="$namespace_absent" \
      FAKE_RETRY_MERGE_STATE="$terminal_case" bash "$PRUNER" --apply --retire-proof-retries >"$case_output"
    if [[ "$terminal_case" == merged ]]; then
      jq -e '.data == {schemaVersion:"1"}' "$FAKE_RETRY_HOLDER" >/dev/null
      grep -Fq 'Retired the proof-retry record for merged PR #901' "$case_output"
    else
      assert_retry_count 3
      test ! -e "$FAKE_RETRY_PATCH_LOG"
    fi
  done
  echo 'Preview durable proof-retry contract passed.'
)

run_retry_retirement_cursor_contract() (
  set -euo pipefail
  # The retry helper calls the three subshell-local test doubles below.
  # shellcheck source=/dev/null
  source "$ROOT_DIR/dev-tools/hosted/preview/preview-proof-retry-record.shlib"
  export GITHUB_REPOSITORY=owner/fixture
  holder_file="$TEMP_DIR/c15-holder" audit="$TEMP_DIR/c15-gh" patches="$TEMP_DIR/c15-patch"
  false_repo=false
  jq -n '{
      apiVersion:"v1", kind:"ConfigMap", metadata:{namespace:"firemud-system",
      name:"firemud-preview-proof-retries",uid:"fixture-holder",resourceVersion:"1",
      annotations:{untouched:"must remain"}},
      data:({schemaVersion:"1"} + (reduce range(101;131) as $n ({};
        ("pr-"+($n|tostring)) as $key | .[$key] = ({repository:"owner/fixture",
         namespace:$key,namespaceUid:"old",count:3,image:"fixture",
         base:("a"*40),head:("b"*40),merge:("c"*40),nonce:""} | tojson))))
    }' >"$holder_file"
  : >"$audit"; : >"$patches"

  # shellcheck disable=SC2329 # Invoked indirectly by the sourced retry helper.
  timeout() {
    [[ "$1" == 10s ]] || return 2
    shift
    "$@"
  }
  # shellcheck disable=SC2329 # Invoked indirectly by the sourced retry helper.
  gh() {
    [[ "$1" == api && "$2" == repos/owner/fixture/pulls/* ]] || return 2
    local num="${2##*/}" merged=false repo=owner/fixture
    printf '%s\n' "$num" >>"$audit"
    [[ "$num" != 101 ]] || return 1  # unavailable evidence must not pin the cursor
    [[ "$num" != 130 && "$num" != 129 && "$num" != 128 ]] || merged=true
    [[ "$num" != 128 ]] || repo=other/fixture
    jq -cn --argjson n "$num" --argjson m "$merged" --arg repo "$repo" '
      {number:$n,state:"closed",merged:$m,head:{repo:{full_name:$repo}},base:{repo:{full_name:"owner/fixture"}}}'
  }
  # shellcheck disable=SC2329 # Invoked indirectly by the sourced retry helper.
  kubectl() {
    case "$*" in
      '-n firemud-system get configmap firemud-preview-proof-retries -o json --request-timeout=10s')
        cat "$holder_file"
        ;;
      'get namespace pr-130 --ignore-not-found -o json --request-timeout=10s')
        return 0
        ;;
      'get namespace pr-129 --ignore-not-found -o json --request-timeout=10s')
        # Another authorized action changed this same row after the scan snapshot.
        jq '.metadata.resourceVersion="77" | .data["pr-129"] |=
          (fromjson | .namespaceUid="replacement" | tojson)' "$holder_file" >"$holder_file.next"
        mv "$holder_file.next" "$holder_file"
        ;;
      '-n firemud-system patch configmap firemud-preview-proof-retries --type=json --patch '*)
        local patch="$8"
        jq -en --argjson p "$patch" --slurpfile f "$holder_file" '
          $p[0] == {op:"test",path:"/metadata/uid",value:$f[0].metadata.uid}
          and $p[1] == {op:"test",path:"/metadata/resourceVersion",value:$f[0].metadata.resourceVersion}
          and ($p|length)==3' >/dev/null || return 2
        printf '%s\n' "$patch" >>"$patches"
        jq --argjson p "$patch" '
          .metadata.resourceVersion |= (tonumber+1|tostring)
          | if $p[2].path == "/metadata/annotations/firemud.dev~1proof-retry-retirement-cursor"
            and $p[2].op=="add"
            then .metadata.annotations["firemud.dev/proof-retry-retirement-cursor"]=$p[2].value
            elif $p[2].path=="/data/pr-130" and $p[2].op=="remove" then del(.data["pr-130"])
            else error("unexpected mock write") end
        ' "$holder_file" >"$holder_file.next" || return 2
        mv "$holder_file.next" "$holder_file"
        if [[ "$false_repo" == true ]] && jq -ne '$p[2].path|startswith("/metadata/annotations/")' --argjson p "$patch" </dev/null >/dev/null; then
          return 1
        fi
        cat "$holder_file"
        ;;
      *) printf 'unexpected mock kubectl: %s\n' "$*" >&2; return 2 ;;
    esac
  }

  retire_merged_preview_retry_entries
  [[ "$(wc -l <"$audit")" == 12 && "$(tail -1 "$audit")" == 112 ]]
  false_repo=true  # exercise lost cursor-patch response with exact readback
  retire_merged_preview_retry_entries
  [[ "$(wc -l <"$audit")" == 24 && "$(tail -1 "$audit")" == 124 ]]
  false_repo=false
  retire_merged_preview_retry_entries
  [[ "$(wc -l <"$audit")" == 36 && "$(tail -1 "$audit")" == 106 ]]
  jq -e '
    .metadata.annotations.untouched=="must remain"
    and .metadata.annotations["firemud.dev/proof-retry-retirement-cursor"]=="owner/fixture:pr-106"
    and (.data | length)==30 and (.data | has("pr-130") | not)
    and (.data["pr-129"]|fromjson|.namespaceUid)=="replacement"
    and (.data["pr-101"]|fromjson|.count)==3
  ' "$holder_file" >/dev/null
  jq '.metadata.annotations["firemud.dev/proof-retry-retirement-cursor"]="other/repo:pr-112"' \
    "$holder_file" >"$holder_file.next"
  mv "$holder_file.next" "$holder_file"
  if retire_merged_preview_retry_entries; then
    echo 'Malformed or wrong-repository cursor unexpectedly accepted' >&2
    exit 1
  fi
  [[ "$(wc -l <"$audit")" == 36 && "$(wc -l <"$patches")" == 4 ]]
  echo 'Preview proof-retry retirement cursor contract passed.'
)

run_proof_retry_contract
run_retry_retirement_cursor_contract
if [[ "${PREVIEW_RETRY_CONTRACT_ONLY:-false}" == true ]]; then
  exit 0
fi

run_allocator() {
  bash "$ALLOCATOR" "$1" "$2" "$3" "$4" \
    "$FAKE_TARGET_BASE_SHA" "$FAKE_TARGET_MERGE_SHA"
}

expect_capacity_unavailable() {
  local status
  if run_allocator "$@"; then
    echo "allocator unexpectedly allocated capacity" >&2
    return 1
  else
    status=$?
  fi
  [[ "$status" -eq 75 ]] || {
    echo "allocator returned ${status}, expected reserved unavailable status 75" >&2
    return 1
  }
  grep -Fqx 'allocation_status=unavailable' "$GITHUB_OUTPUT"
}

reset_case
for invalid_capacity in 0 3; do
  if run_allocator pr-900 "$invalid_capacity" 900 "$FAKE_TARGET_HEAD"; then
    echo "allocator accepted invalid configured capacity ${invalid_capacity}" >&2
    exit 1
  fi
done

SUMMARY_WRITER="$ROOT_DIR/dev-tools/hosted/preview/write-preview-summary.sh"
unset PREVIEW_EXPOSURE_MODE
neutral_failure_summary="$(bash "$SUMMARY_WRITER" failure 101 head-101 image-101 pr-101.preview.firedevops.net unavailable cleanup)"
grep -Fqx -- '- TCP: unavailable' <<<"$neutral_failure_summary"
if grep -Fq 'telnet ' <<<"$neutral_failure_summary"; then
  echo "mode-unknown failure summary invented a public Telnet endpoint" >&2
  exit 1
fi
neutral_cleanup_summary="$(bash "$SUMMARY_WRITER" cleanup 101 head-101 image-101 pr-101.preview.firedevops.net unavailable)"
grep -Fqx -- '- TCP: unavailable' <<<"$neutral_cleanup_summary"
if invalid_mode_error="$(PREVIEW_EXPOSURE_MODE=unavailable bash "$SUMMARY_WRITER" failure 101 head-101 image-101 pr-101.preview.firedevops.net unavailable cleanup 2>&1)"; then
  echo "explicit unavailable exposure mode was accepted" >&2
  exit 1
fi
grep -Fqx 'PREVIEW_EXPOSURE_MODE must be private or public' <<<"$invalid_mode_error"
if success_mode_error="$(bash "$SUMMARY_WRITER" success 101 head-101 image-101 pr-101.preview.firedevops.net unavailable 2>&1)"; then
  echo "success summary accepted an absent exposure mode" >&2
  exit 1
fi
grep -Fqx 'PREVIEW_EXPOSURE_MODE must be private or public for success summaries' <<<"$success_mode_error"
public_failure_summary="$(PREVIEW_EXPOSURE_MODE=public bash "$SUMMARY_WRITER" failure 101 head-101 image-101 pr-101.preview.firedevops.net 32001 cleanup)"
grep -Fqx -- '- TCP: unavailable' <<<"$public_failure_summary"
private_failure_summary="$(PREVIEW_EXPOSURE_MODE=private bash "$SUMMARY_WRITER" failure 101 head-101 image-101 pr-101.preview.firedevops.net unavailable cleanup)"
grep -Fqx -- '- TCP: private Gateway ↔ TCP Proxy bridge unavailable (no public Telnet)' <<<"$private_failure_summary"

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
run_allocator pr-900 2 900 "$FAKE_TARGET_HEAD"
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
grep -q -- '- TCP: unavailable' "$FAKE_COMMENT_BODY"
if grep -q 'telnet pr-101.preview.firedevops.net' "$FAKE_COMMENT_BODY"; then
  echo "reclaim failure summary invented a public Telnet endpoint" >&2
  exit 1
fi
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
run_allocator pr-900 2 900 "$FAKE_TARGET_HEAD"
grep -qx '200' "$FAKE_PREVIOUS_COMMENT_ID_LOG"

reset_case
export FAKE_TARGET_PRIORITY=false
expect_capacity_unavailable pr-900 2 900 "$FAKE_TARGET_HEAD"
test ! -e "$FAKE_DELETE_LOG"

reset_case
export FAKE_PR_101_PRIORITY=true
expect_capacity_unavailable pr-900 2 900 "$FAKE_TARGET_HEAD"
test ! -e "$FAKE_DELETE_LOG"

reset_case
export PREVIEW_REVALIDATE_SOURCE_BINDING_SCRIPT="$TEMP_DIR/revalidate-source-parent-advance"
if run_allocator pr-900 2 900 "$FAKE_TARGET_HEAD"; then
  echo "reclaim proceeded after the parent advanced with an unchanged child head" >&2
  exit 1
fi
test ! -e "$FAKE_DELETE_LOG"
grep -qx '101 900 reclaiming' "$FAKE_PUBLISH_LOG"
grep -qx '101 900 retained' "$FAKE_PUBLISH_LOG"
test "$(<"$FAKE_SOURCE_BINDING_CALLS")" -eq 3
unset PREVIEW_REVALIDATE_SOURCE_BINDING_SCRIPT

reset_case
export FAKE_TARGET_LOSES_PRIORITY=true
if run_allocator pr-900 2 900 "$FAKE_TARGET_HEAD"; then
  echo "reclaim proceeded after the target lost priority" >&2
  exit 1
fi
test ! -e "$FAKE_DELETE_LOG"
grep -qx '101 900 reclaiming' "$FAKE_PUBLISH_LOG"
grep -qx '101 900 retained' "$FAKE_PUBLISH_LOG"
grep -qx 'retained' "$FAKE_PUBLISHED_STATE"

reset_case
export FAKE_PR_101_GAINS_PRIORITY=true
if run_allocator pr-900 2 900 "$FAKE_TARGET_HEAD"; then
  echo "reclaim proceeded after the victim gained priority" >&2
  exit 1
fi
test ! -e "$FAKE_DELETE_LOG"
grep -qx '101 900 reclaiming' "$FAKE_PUBLISH_LOG"
grep -qx '101 900 retained' "$FAKE_PUBLISH_LOG"
grep -qx 'retained' "$FAKE_PUBLISHED_STATE"

reset_case
export FAKE_PR_101_OWNER=999
if run_allocator pr-900 2 900 "$FAKE_TARGET_HEAD"; then
  echo "reclaim proceeded after victim namespace ownership changed" >&2
  exit 1
fi
test ! -e "$FAKE_DELETE_LOG"
grep -qx '101 900 failure' "$FAKE_PUBLISH_LOG"
grep -qx 'failure' "$FAKE_PUBLISHED_STATE"

reset_case
export FAKE_NAMESPACE_JSON_QUERY_FAIL=true
if run_allocator pr-900 2 900 "$FAKE_TARGET_HEAD"; then
  echo "reclaim proceeded after victim namespace snapshot failed" >&2
  exit 1
fi
test ! -e "$FAKE_DELETE_LOG"
grep -qx '101 900 failure' "$FAKE_PUBLISH_LOG"
grep -qx 'failure' "$FAKE_PUBLISHED_STATE"
test "$(<"$FAKE_NAMESPACE_JSON_CALLS")" -eq 1

reset_case
export FAKE_NAMESPACE_JSON_PARSE_FAIL=true
if run_allocator pr-900 2 900 "$FAKE_TARGET_HEAD"; then
  echo "reclaim proceeded after victim namespace snapshot was malformed" >&2
  exit 1
fi
test ! -e "$FAKE_DELETE_LOG"
grep -qx '101 900 failure' "$FAKE_PUBLISH_LOG"
grep -qx 'failure' "$FAKE_PUBLISHED_STATE"
test "$(<"$FAKE_NAMESPACE_JSON_CALLS")" -eq 1

reset_case
export FAKE_PR_101_IMAGE=$'image-101\n'
if run_allocator pr-900 2 900 "$FAKE_TARGET_HEAD"; then
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
  if run_allocator pr-900 2 900 "$FAKE_TARGET_HEAD"; then
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
export FAKE_NAMESPACE_ROWS='2026-01-01T00:00:00Z|pr-900|900|2026-01-01T00:00:00Z|base-900|head-900|merge-900|image-900\n2026-01-02T00:00:00Z|pr-101|101|2026-01-02T00:00:00Z|base-101|head-101|merge-101|image-101\n'
if ! run_allocator pr-900 2 900 "$FAKE_TARGET_HEAD"; then
  echo "existing ordinary preview was blocked by an unsatisfied priority PR" >&2
  exit 1
fi
test ! -e "$FAKE_DELETE_LOG"

reset_case
export FAKE_NAMESPACE_ROWS='2026-01-01T00:00:00Z|preview-101|101|2026-01-02T00:00:00Z|base-101|head-101|merge-101|image-101\n'
if run_allocator pr-900 1 900 "$FAKE_TARGET_HEAD"; then
  echo "reclaim selected a noncanonical preview namespace" >&2
  exit 1
fi
test ! -e "$FAKE_DELETE_LOG"

reset_case
export FAKE_PUBLISH_FAIL_PHASE=reclaiming
if run_allocator pr-900 2 900 "$FAKE_TARGET_HEAD"; then
  echo "reclaim proceeded after conservative status publication failed" >&2
  exit 1
fi
test ! -e "$FAKE_DELETE_LOG"
test "$(grep -c '101 900 reclaiming' "$FAKE_PUBLISH_LOG")" -eq 3
test ! -e "$FAKE_PUBLISHED_STATE"

reset_case
export FAKE_DELETE_FAIL=true
if run_allocator pr-900 2 900 "$FAKE_TARGET_HEAD"; then
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
if run_allocator pr-900 2 900 "$FAKE_TARGET_HEAD"; then
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
if run_allocator pr-900 2 900 "$FAKE_TARGET_HEAD"; then
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
if run_allocator pr-900 2 900 "$FAKE_TARGET_HEAD"; then
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
run_allocator pr-900 2 900 "$FAKE_TARGET_HEAD"
test "$(grep -c '101 900 reclaiming' "$FAKE_PUBLISH_LOG")" -eq 3
grep -qx '101 900 reclaimed' "$FAKE_PUBLISH_LOG"
grep -qx 'reclaimed' "$FAKE_PUBLISHED_STATE"

reset_case
export FAKE_TARGET_PRIORITY=false
export FAKE_OPEN_PRIORITY_ROWS="901\t${priority_candidate_head}\texample/FireMUD\thuman\tdevelop\topen\t${priority_labels_base64}\n"
export FAKE_PRIORITY_CANDIDATE_NAMESPACE_ABSENT=true
if run_allocator pr-900 2 900 "$FAKE_TARGET_HEAD"; then
  echo "ordinary allocation did not yield to an unsatisfied priority PR" >&2
  exit 1
fi
test ! -e "$FAKE_DELETE_LOG"

# A priority candidate can close after the bounded open-PR snapshot.  Its old
# priority label must not continue blocking an ordinary allocation.
reset_case
export FAKE_TARGET_PRIORITY=false
export FAKE_PR_901_STATE=closed
export FAKE_OPEN_PRIORITY_ROWS="901\t${priority_candidate_head}\texample/FireMUD\thuman\tdevelop\topen\t${priority_labels_base64}\n"
export FAKE_NAMESPACE_ROWS='2026-01-01T00:00:00Z|pr-101|101|2026-01-02T00:00:00Z|base-101|head-101|merge-101|image-101\n'
if ! run_allocator pr-900 2 900 "$FAKE_TARGET_HEAD"; then
  echo "ordinary allocation was blocked by a priority PR that had closed" >&2
  exit 1
fi
test ! -e "$FAKE_DELETE_LOG"

# Likewise, a live removal of preview:priority releases the ordinary slot.
reset_case
export FAKE_TARGET_PRIORITY=false
export FAKE_PR_901_PRIORITY=false
export FAKE_OPEN_PRIORITY_ROWS="901\t${priority_candidate_head}\texample/FireMUD\thuman\tdevelop\topen\t${priority_labels_base64}\n"
export FAKE_NAMESPACE_ROWS='2026-01-01T00:00:00Z|pr-101|101|2026-01-02T00:00:00Z|base-101|head-101|merge-101|image-101\n'
if ! run_allocator pr-900 2 900 "$FAKE_TARGET_HEAD"; then
  echo "ordinary allocation was blocked after live priority-label removal" >&2
  exit 1
fi
test ! -e "$FAKE_DELETE_LOG"

reset_case
export FAKE_TARGET_PRIORITY=false
export FAKE_PR_901_MERGEABLE=false
export FAKE_PR_901_MERGEABLE_STATE=dirty
export FAKE_OPEN_PRIORITY_ROWS="901\t${priority_candidate_head}\texample/FireMUD\thuman\tdevelop\topen\t${priority_labels_base64}\n"
export FAKE_NAMESPACE_ROWS='2026-01-01T00:00:00Z|pr-101|101|2026-01-02T00:00:00Z|base-101|head-101|merge-101|image-101\n'
if ! run_allocator pr-900 2 900 "$FAKE_TARGET_HEAD"; then
  echo "ordinary allocation was blocked by a confirmed non-deployable priority PR" >&2
  exit 1
fi
test ! -e "$FAKE_DELETE_LOG"

reset_case
export FAKE_TARGET_PRIORITY=false
export FAKE_OPEN_PRIORITY_ROWS="901\t${priority_candidate_head}\texample/FireMUD\thuman\tdevelop\topen\t${priority_labels_base64}\n"
export FAKE_NAMESPACE_ROWS='2026-01-01T00:00:00Z|pr-101|101|2026-01-02T00:00:00Z|partial-base|partial-head\n'
if run_allocator pr-900 1 900 "$FAKE_TARGET_HEAD"; then
  echo "ordinary allocation accepted a partially annotated namespace tuple" >&2
  exit 1
fi
test ! -e "$FAKE_DELETE_LOG"

reset_case
export FAKE_TARGET_PRIORITY=false
export FAKE_OPEN_PRIORITY_ROWS="901\t${priority_candidate_head}\texample/FireMUD\thuman\tdevelop\topen\t${priority_labels_base64}\n"
export FAKE_PRIORITY_CANDIDATE_NAMESPACE_LOOKUP_ERROR=true
if run_allocator pr-900 2 900 "$FAKE_TARGET_HEAD" \
  >"$TEMP_DIR/priority-candidate-namespace-error.output" 2>&1; then
  echo "ordinary allocation proceeded after a priority candidate namespace lookup failed" >&2
  exit 1
fi
grep -Fxq 'Refusing ordinary allocation because priority intent could not be evaluated' \
  "$TEMP_DIR/priority-candidate-namespace-error.output"
test ! -e "$FAKE_DELETE_LOG"

for ineligible_priority_row in \
  "901\thead-901\tother/FireMUD\thuman\tdevelop\topen\t${priority_labels_base64}\n" \
  "901\thead-901\tother/FireMUD\thuman\tdevelop\topen\t${invalid_json_labels_base64}\n" \
  "901\t${priority_candidate_head}\texample/FireMUD\tdependabot[bot]\tdevelop\topen\t${priority_labels_base64}\n"
do
  reset_case
  export FAKE_TARGET_PRIORITY=false
  export FAKE_OPEN_PRIORITY_ROWS="$ineligible_priority_row"
  expect_capacity_unavailable pr-900 2 900 "$FAKE_TARGET_HEAD"
  test ! -e "$FAKE_DELETE_LOG"
done

# A maintainer-labeled, same-repository stacked PR is a valid priority
# candidate.  Its live base is revalidated before ordinary capacity is yielded.
reset_case
export FAKE_TARGET_PRIORITY=false
export FAKE_PR_901_BASE_REF=feature/stack
export FAKE_OPEN_PRIORITY_ROWS="901\t${priority_candidate_head}\texample/FireMUD\thuman\tfeature/stack\topen\t${priority_labels_base64}\n"
if run_allocator pr-900 2 900 "$FAKE_TARGET_HEAD"; then
  echo "ordinary allocation did not yield to a labeled same-repository stacked PR" >&2
  exit 1
fi
test ! -e "$FAKE_DELETE_LOG"

# A non-string mergeability state is malformed metadata and must not satisfy
# the typed candidate guard or block ordinary allocation ambiguously.
reset_case
export FAKE_TARGET_PRIORITY=false
export FAKE_PR_901_MERGEABLE_STATE=true
export FAKE_OPEN_PRIORITY_ROWS="901\t${priority_candidate_head}\texample/FireMUD\thuman\tdevelop\topen\t${priority_labels_base64}\n"
if run_allocator pr-900 2 900 "$FAKE_TARGET_HEAD"; then
  echo "ordinary allocation accepted a non-string priority mergeability state" >&2
  exit 1
fi
test ! -e "$FAKE_DELETE_LOG"
unset FAKE_PR_901_MERGEABLE_STATE

valid_open_pr_row="901"$'\t'"${priority_candidate_head}"$'\t'"example/FireMUD"$'\t'"human"$'\t'"develop"$'\t'"open"$'\t'"${priority_labels_base64}"
for missing_identity_field in 1 2 4 5 6 7; do
  reset_case
  export FAKE_TARGET_PRIORITY=false
  FAKE_OPEN_PRIORITY_ROWS="$(
    awk -F '\t' -v OFS='\t' -v missing="$missing_identity_field" \
      '{$missing = ""; print}' <<<"$valid_open_pr_row"
  )"$'\n' \
    run_allocator pr-900 2 900 "$FAKE_TARGET_HEAD" \
    >"$TEMP_DIR/missing-open-pr-field-${missing_identity_field}.output" 2>&1 && {
      echo "ordinary allocation accepted open PR metadata with missing identity field ${missing_identity_field}" >&2
      exit 1
    }
  grep -Fxq 'Open pull request metadata is missing required identity fields' \
    "$TEMP_DIR/missing-open-pr-field-${missing_identity_field}.output"
  test ! -e "$FAKE_DELETE_LOG"
done

reset_case
export FAKE_TARGET_PRIORITY=false
export FAKE_OPEN_PRIORITY_ROWS="901\t${priority_candidate_head}\t\thuman\tdevelop\topen\t${priority_labels_base64}\n"
expect_capacity_unavailable pr-900 2 900 "$FAKE_TARGET_HEAD"
test ! -e "$FAKE_DELETE_LOG"

for ignored_untrusted_row in \
  $'901\t\tother/FireMUD\t\t\t\t{malformed-label-transport}\n' \
  "901\t\t\thuman\tdevelop\topen\t${priority_labels_base64}\n"
do
  reset_case
  export FAKE_TARGET_PRIORITY=false
  export FAKE_OPEN_PRIORITY_ROWS="$ignored_untrusted_row"
  expect_capacity_unavailable pr-900 2 900 "$FAKE_TARGET_HEAD"
  test ! -e "$FAKE_DELETE_LOG"
done

reset_case
export FAKE_TARGET_PRIORITY=false
export FAKE_OPEN_PRIORITY_ROWS="901\t${priority_candidate_head}\texample/FireMUD\thuman\tdevelop\topen\t${priority_labels_base64}\textra\n"
if run_allocator pr-900 2 900 "$FAKE_TARGET_HEAD" \
  >"$TEMP_DIR/malformed-open-pr-framing.output" 2>&1; then
  echo "ordinary allocation accepted malformed open PR row framing" >&2
  exit 1
fi
grep -Fxq 'Open pull request metadata is missing required identity fields' \
  "$TEMP_DIR/malformed-open-pr-framing.output"
test ! -e "$FAKE_DELETE_LOG"

reset_case
export FAKE_TARGET_PRIORITY=false
export FAKE_OPEN_PRIORITY_ROWS="901\t${priority_candidate_head}\texample/FireMUD\thuman\tdevelop\topen\t${adversarial_labels_base64}\n"
expect_capacity_unavailable pr-900 2 900 "$FAKE_TARGET_HEAD"
test ! -e "$FAKE_DELETE_LOG"

reset_case
export FAKE_TARGET_PRIORITY=false
export FAKE_OPEN_PRIORITY_ROWS="901\t${priority_candidate_head}\texample/FireMUD\thuman\tdevelop\topen\t${invalid_json_labels_base64}\n"
if run_allocator pr-900 2 900 "$FAKE_TARGET_HEAD"; then
  echo "ordinary allocation did not fail closed on malformed priority labels" >&2
  exit 1
fi
test ! -e "$FAKE_DELETE_LOG"

reset_case
export FAKE_TARGET_PRIORITY=false
export FAKE_PRIORITY_QUERY_FAIL=true
if run_allocator pr-900 2 900 "$FAKE_TARGET_HEAD"; then
  echo "ordinary allocation did not fail closed when priority query failed" >&2
  exit 1
fi

reset_case
export FAKE_TARGET_LABELS_VALID=invalid
if run_allocator pr-900 2 900 "$FAKE_TARGET_HEAD"; then
  echo "malformed target labels were treated as eligible" >&2
  exit 1
fi
test ! -e "$FAKE_DELETE_LOG"

for target_contract_case in repository head base author metadata; do
  reset_case
  case "$target_contract_case" in
    repository) export FAKE_TARGET_REPOSITORY=other/FireMUD ;;
    head) expected_target_head=other-head ;;
    base)
      export FAKE_TARGET_BASE_REF=feature/stack
      export FAKE_TARGET_PRIORITY=false
      ;;
    author) export FAKE_TARGET_AUTHOR='renovate[bot]' ;;
    metadata) export FAKE_TARGET_JQ_QUERY_FAIL=true ;;
  esac
  target_contract_output="$TEMP_DIR/target-${target_contract_case}.output"
  if run_allocator pr-900 2 900 "${expected_target_head:-$FAKE_TARGET_HEAD}" \
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
if run_allocator pr-900 2 900 "$FAKE_TARGET_HEAD"; then
  echo "ordinary allocation did not fail closed when eligibility evaluation failed" >&2
  exit 1
fi

reset_case
export FAKE_TARGET_PRIORITY=false
export FAKE_OPEN_PRIORITY_ROWS="901\t${priority_candidate_head}\texample/FireMUD\thuman\tdevelop\topen\t${adversarial_priority_labels_base64}\n"
if run_allocator pr-900 2 900 "$FAKE_TARGET_HEAD"; then
  echo "ordinary allocation ignored a priority label alongside quoted and backslashed label data" >&2
  exit 1
fi
test ! -e "$FAKE_DELETE_LOG"

reset_case
export FAKE_NAMESPACE_ROWS='pr-101\t101\n'
export FAKE_PRUNE_METADATA="open\tfeature/stack\thuman\t${adversarial_labels_base64}\n"
export PREVIEW_DELETE_TIMEOUT=37
bash "$PRUNER" --apply
grep -qx 'pr-101 pr-101' "$FAKE_DELETE_LOG"
grep -qx '37' "$FAKE_DELETE_TIMEOUT_LOG"

# Ordinary develop/main previews are retained only while their live merge and
# base branch remain valid. Known terminal state is authoritative; ambiguous
# API or metadata state remains fail-closed against destructive cleanup.
reset_case
export FAKE_NAMESPACE_ROWS='pr-101\t101\n'
export FAKE_PRUNE_METADATA="open\tdevelop\thuman\t${adversarial_labels_base64}\n"
export FAKE_PRUNE_MERGEABLE=false
export FAKE_PRUNE_MERGEABLE_STATE=dirty
bash "$PRUNER" --apply >"$TEMP_DIR/prune-ordinary-conflict.out"
grep -qx 'pr-101 pr-101' "$FAKE_DELETE_LOG"
grep -Fq 'PR #101 is not preview-eligible (reason=merge-conflict)' \
  "$TEMP_DIR/prune-ordinary-conflict.out"

reset_case
export FAKE_NAMESPACE_ROWS='pr-101\t101\n'
export FAKE_PRUNE_METADATA="open\tdevelop\thuman\t${adversarial_labels_base64}\n"
export FAKE_PRUNE_MERGEABLE=false
export FAKE_PRUNE_MERGEABLE_STATE=dirty
export HOSTED_IDENTITY_MODE=hosted-controller
export FAKE_RUNTIME_NAMESPACE_PRESENT=false
export FAKE_RECORD_RUNTIME_CHECK=true
export FAKE_IDENTITY_JSON='{"apiVersion":"platform.firemud.dev/v1alpha1","kind":"HostedEnvironmentIdentity","metadata":{"namespace":"firemud-system","name":"pr-101"}}'
bash "$PRUNER" --apply --retire-terminal-identities >"$TEMP_DIR/prune-conflict-no-retire.out"
test "$(<"$FAKE_OPERATION_SEQUENCE")" = $'runtime-delete'
test ! -e "$FAKE_IDENTITY_REQUEST_LOG"
test ! -e "$FAKE_IDENTITY_WAIT_LOG"

reset_case
export FAKE_NAMESPACE_ROWS='pr-101\t101\n'
export FAKE_PRUNE_METADATA="open\tmain\thuman\t${adversarial_labels_base64}\n"
export FAKE_PRUNE_BASE_REF_STATUS=404
bash "$PRUNER" --apply >"$TEMP_DIR/prune-ordinary-missing-base.out"
grep -qx 'pr-101 pr-101' "$FAKE_DELETE_LOG"
grep -Fq 'PR #101 is not preview-eligible (reason=missing-base-branch)' \
  "$TEMP_DIR/prune-ordinary-missing-base.out"

reset_case
export FAKE_NAMESPACE_ROWS='pr-101\t101\n'
export FAKE_PRUNE_METADATA="open\tdevelop\thuman\t${adversarial_labels_base64}\n"
export FAKE_PRUNE_BASE_REF_STATUS=500
bash "$PRUNER" --apply >"$TEMP_DIR/prune-ordinary-base-error.out"
test ! -e "$FAKE_DELETE_LOG"
grep -Fq 'Keeping pr-101: PR #101 base branch lookup failed' \
  "$TEMP_DIR/prune-ordinary-base-error.out"

reset_case
export FAKE_NAMESPACE_ROWS='pr-101\t101\n'
export FAKE_PRUNE_METADATA="open\tdevelop\thuman\t${adversarial_labels_base64}\n"
export FAKE_PRUNE_MERGEABLE=false
export FAKE_PRUNE_MERGEABLE_STATE=unknown
bash "$PRUNER" --apply >"$TEMP_DIR/prune-ordinary-unknown-merge.out"
test ! -e "$FAKE_DELETE_LOG"
grep -Fq 'Keeping pr-101: PR #101 mergeability is unknown' \
  "$TEMP_DIR/prune-ordinary-unknown-merge.out"

reset_case
export FAKE_NAMESPACE_ROWS='pr-101\t101\n'
export FAKE_PRUNE_METADATA="open\tdevelop\thuman\t${adversarial_labels_base64}\n"
export FAKE_PRUNE_MERGEABLE=false
export FAKE_PRUNE_MERGEABLE_SHAPE=string
export FAKE_PRUNE_MERGEABLE_STATE=dirty
bash "$PRUNER" --apply >"$TEMP_DIR/prune-ordinary-malformed-merge.out"
test ! -e "$FAKE_DELETE_LOG"
grep -Fq 'Keeping pr-101: PR #101 mergeability is unknown' \
  "$TEMP_DIR/prune-ordinary-malformed-merge.out"

reset_case
export FAKE_NAMESPACE_ROWS='pr-101\t101\n'
export FAKE_PRUNE_METADATA="open\tdevelop\thuman\t${adversarial_labels_base64}\n"
export FAKE_PRUNE_MERGEABLE=true
export FAKE_PRUNE_MERGEABLE_STATE=clean
export FAKE_PRUNE_MERGEABLE_STATE_SHAPE=null
bash "$PRUNER" --apply >"$TEMP_DIR/prune-ordinary-null-merge-state.out"
test ! -e "$FAKE_DELETE_LOG"
grep -Fq 'Keeping pr-101: PR #101 mergeability is unknown' \
  "$TEMP_DIR/prune-ordinary-null-merge-state.out"

reset_case
export FAKE_NAMESPACE_ROWS='pr-101\t101\n'
export FAKE_PRUNE_METADATA="open\tdevelop\thuman\t${adversarial_labels_base64}\n"
export FAKE_PRUNE_BASE_REF_SHA=malformed
bash "$PRUNER" --apply >"$TEMP_DIR/prune-ordinary-malformed-base.out"
test ! -e "$FAKE_DELETE_LOG"
grep -Fq 'Keeping pr-101: PR #101 base branch metadata is malformed' \
  "$TEMP_DIR/prune-ordinary-malformed-base.out"

# Scheduled terminal cleanup may retire an existing identity only through the
# explicit hosted-controller mode. Runtime deletion, retirement request, the
# terminal status wait, and CR deletion remain one ordered sequence.
reset_case
export FAKE_NAMESPACE_ROWS='pr-101\t101\n'
export FAKE_PRUNE_METADATA="open\tfeature/stack\thuman\t${adversarial_labels_base64}\n"
export HOSTED_IDENTITY_MODE=hosted-controller
export FAKE_RUNTIME_NAMESPACE_PRESENT=false
export FAKE_RECORD_RUNTIME_CHECK=true
export PREVIEW_DELETE_TIMEOUT=37
bash "$PRUNER" --apply --retire-terminal-identities
test "$(<"$FAKE_OPERATION_SEQUENCE")" = $'runtime-delete\nruntime-check\nidentity-request\nidentity-wait\nidentity-delete'
grep -qx 'pr-101 Retired' "$FAKE_IDENTITY_REQUEST_LOG"
grep -qx -- '--retired pr-101 37' "$FAKE_IDENTITY_WAIT_LOG"
grep -Fqx -- '-n firemud-system get hostedenvironmentidentity pr-101 --ignore-not-found -o json' \
  "$FAKE_IDENTITY_LOG"
grep -Fqx -- '-n firemud-system delete hostedenvironmentidentity pr-101 --ignore-not-found --wait=true --timeout=37s' \
  "$FAKE_IDENTITY_LOG"

# The terminal janitor also rediscovers stranded identity requests after their
# runtime namespace is already gone. Every recoverable phase is revalidated
# against current PR eligibility and then uses the same exact-absence retirement path.
reset_case
export FAKE_NAMESPACE_ROWS='pr-101\t101\n'
export FAKE_PRUNE_METADATA="open\tdevelop\thuman\t${adversarial_labels_base64}\n"
export FAKE_PRUNE_MULTI_TEST=true
export FAKE_PR_102_PRIORITY=false
export HOSTED_IDENTITY_MODE=hosted-controller
export FAKE_RUNTIME_NAMESPACE_PRESENT=false
export FAKE_RECORD_RUNTIME_CHECK=true
export FAKE_IDENTITY_JSON='{"apiVersion":"platform.firemud.dev/v1alpha1","kind":"HostedEnvironmentIdentity","metadata":{"namespace":"firemud-system","name":"pr-102"}}'
export FAKE_IDENTITY_LIST_JSON='{"apiVersion":"platform.firemud.dev/v1alpha1","kind":"HostedEnvironmentIdentityList","items":[{"apiVersion":"platform.firemud.dev/v1alpha1","kind":"HostedEnvironmentIdentity","metadata":{"namespace":"firemud-system","name":"pr-101"},"spec":{"desiredState":"Active"},"status":{"phase":"RuntimeAbsent"}},{"apiVersion":"platform.firemud.dev/v1alpha1","kind":"HostedEnvironmentIdentity","metadata":{"namespace":"firemud-system","name":"pr-102"},"spec":{"desiredState":"Active"},"status":{"phase":"RuntimeAbsent"}}]}'
bash "$PRUNER" --apply --retire-terminal-identities \
  >"$TEMP_DIR/recover-observed-and-absent.out"
# PR #101 is evaluated once by the namespace loop; stranded recovery must skip
# that observed runtime without a second eligibility query.
# Retention derives both eligibility and ownership/mergeability from one full
# live PR payload per retained namespace.
test "$(grep -Fxc 'repos/example/FireMUD/pulls/101' "$FAKE_PRUNE_QUERY_LOG")" -eq 1
test "$(grep -Fxc 'repos/example/FireMUD/pulls/102' "$FAKE_PRUNE_QUERY_LOG")" -eq 1
test "$(<"$FAKE_OPERATION_SEQUENCE")" = $'runtime-check\nidentity-request\nidentity-wait\nidentity-delete'
grep -Fqx 'pr-102 Retired' "$FAKE_IDENTITY_REQUEST_LOG"
if grep -Fq 'pr-101' "$FAKE_IDENTITY_REQUEST_LOG"; then
  echo "stranded recovery reconsidered an identity with an observed runtime" >&2
  exit 1
fi
grep -Fq 'Keeping pr-101: PR #101 remains preview-eligible' \
  "$TEMP_DIR/recover-observed-and-absent.out"
grep -Fq 'Recovering HostedEnvironmentIdentity/pr-102 from phase RuntimeAbsent: PR #102 is not preview-eligible' \
  "$TEMP_DIR/recover-observed-and-absent.out"
for stranded_phase in RuntimeAbsent Retiring Retired; do
  reset_case
  export FAKE_NAMESPACE_ROWS=''
  export FAKE_PRUNE_METADATA="closed\tdevelop\thuman\t${adversarial_labels_base64}\n"
  export HOSTED_IDENTITY_MODE=hosted-controller
  export FAKE_RUNTIME_NAMESPACE_PRESENT=false
  export FAKE_RECORD_RUNTIME_CHECK=true
  export PREVIEW_DELETE_TIMEOUT=41
  FAKE_IDENTITY_LIST_JSON="$(
    jq -nc --arg phase "$stranded_phase" '{
      apiVersion: "platform.firemud.dev/v1alpha1",
      kind: "HostedEnvironmentIdentityList",
      items: [{
        apiVersion: "platform.firemud.dev/v1alpha1",
        kind: "HostedEnvironmentIdentity",
        metadata: {namespace: "firemud-system", name: "pr-101"},
        spec: {desiredState: (if $phase == "RuntimeAbsent" then "Active" else "Retired" end)},
        status: {phase: $phase}
      }]
    }'
  )"
  export FAKE_IDENTITY_LIST_JSON
  bash "$PRUNER" --apply --retire-terminal-identities \
    >"$TEMP_DIR/recover-${stranded_phase}.out"
  test "$(<"$FAKE_OPERATION_SEQUENCE")" = $'runtime-check\nidentity-request\nidentity-wait\nidentity-delete'
  test ! -e "$FAKE_DELETE_LOG"
  grep -Fqx 'pr-101 Retired' "$FAKE_IDENTITY_REQUEST_LOG"
  grep -Fqx -- '--retired pr-101 41' "$FAKE_IDENTITY_WAIT_LOG"
  grep -Fqx -- '-n firemud-system delete hostedenvironmentidentity pr-101 --ignore-not-found --wait=true --timeout=41s' \
    "$FAKE_IDENTITY_LOG"
  grep -Fqx "$HOSTED_IDENTITY_REQUESTER_KUBECONFIG" \
    "$FAKE_IDENTITY_LIST_KUBECONFIG_LOG"
  grep -Fq "Recovering HostedEnvironmentIdentity/pr-101 from phase ${stranded_phase}" \
    "$TEMP_DIR/recover-${stranded_phase}.out"
done

reset_case
export FAKE_NAMESPACE_ROWS=''
export FAKE_PRUNE_METADATA="open\tdevelop\thuman\t${adversarial_labels_base64}\n"
export FAKE_PRUNE_MERGEABLE=false
export FAKE_PRUNE_MERGEABLE_STATE=dirty
export HOSTED_IDENTITY_MODE=hosted-controller
export FAKE_RUNTIME_NAMESPACE_PRESENT=false
export FAKE_RECORD_RUNTIME_CHECK=true
FAKE_IDENTITY_LIST_JSON="$({
  jq -nc '{
    apiVersion: "platform.firemud.dev/v1alpha1",
    kind: "HostedEnvironmentIdentityList",
    items: [{
      apiVersion: "platform.firemud.dev/v1alpha1",
      kind: "HostedEnvironmentIdentity",
      metadata: {namespace: "firemud-system", name: "pr-101"},
      spec: {desiredState: "Active"},
      status: {phase: "RuntimeAbsent"}
    }]
  }'
})"
export FAKE_IDENTITY_LIST_JSON
bash "$PRUNER" --apply --retire-terminal-identities >"$TEMP_DIR/recover-conflict-no-retire.out"
test ! -e "$FAKE_IDENTITY_REQUEST_LOG"
test ! -e "$FAKE_IDENTITY_WAIT_LOG"
grep -Fq 'eligibility reason is not authoritative for retirement (reason=merge-conflict)' \
  "$TEMP_DIR/recover-conflict-no-retire.out"

# Recovery rows must use a dedicated descriptor so request helpers retain the
# caller's stdin instead of consuming the candidate-row transport.
reset_case
export FAKE_NAMESPACE_ROWS=''
export FAKE_PRUNE_METADATA="closed\tdevelop\thuman\t${adversarial_labels_base64}\n"
export HOSTED_IDENTITY_MODE=hosted-controller
export FAKE_RUNTIME_NAMESPACE_PRESENT=false
export FAKE_RECORD_RUNTIME_CHECK=true
export FAKE_IDENTITY_LIST_JSON='{"apiVersion":"platform.firemud.dev/v1alpha1","kind":"HostedEnvironmentIdentityList","items":[{"apiVersion":"platform.firemud.dev/v1alpha1","kind":"HostedEnvironmentIdentity","metadata":{"namespace":"firemud-system","name":"pr-101"},"spec":{"desiredState":"Retired"},"status":{"phase":"Retired"}}]}'
export FAKE_IDENTITY_HELPER_STDIN_LOG="$TEMP_DIR/identity-helper-stdin.log"
printf 'caller-stdin-survives\n' | bash "$PRUNER" --apply --retire-terminal-identities \
  >"$TEMP_DIR/recover-stdin.out"
grep -Fqx 'caller-stdin-survives' "$FAKE_IDENTITY_HELPER_STDIN_LOG"

reset_case
export FAKE_NAMESPACE_ROWS=''
export FAKE_PRUNE_METADATA="open\tdevelop\thuman\t${adversarial_labels_base64}\n"
export HOSTED_IDENTITY_MODE=hosted-controller
export FAKE_IDENTITY_LIST_JSON='{"apiVersion":"platform.firemud.dev/v1alpha1","kind":"HostedEnvironmentIdentityList","items":[{"apiVersion":"platform.firemud.dev/v1alpha1","kind":"HostedEnvironmentIdentity","metadata":{"namespace":"firemud-system","name":"pr-101"},"spec":{"desiredState":"Active"},"status":{"phase":"RuntimeAbsent"}}]}'
bash "$PRUNER" --apply --retire-terminal-identities \
  >"$TEMP_DIR/recover-eligible.out"
grep -Fq 'PR #101 remains preview-eligible' "$TEMP_DIR/recover-eligible.out"
test ! -e "$FAKE_IDENTITY_REQUEST_LOG"
test ! -e "$FAKE_IDENTITY_WAIT_LOG"

reset_case
export FAKE_NAMESPACE_ROWS=''
export HOSTED_IDENTITY_MODE=hosted-controller
export FAKE_IDENTITY_LIST_FAIL=true
if bash "$PRUNER" --apply --retire-terminal-identities \
  >"$TEMP_DIR/recover-list-failure.out" 2>&1; then
  echo "pruner suppressed a stranded identity discovery failure" >&2
  exit 1
fi
grep -Fqx '0 hosted runtime deletion(s) and 1 hosted identity retirement(s) failed; stale cleanup is incomplete.' \
  "$TEMP_DIR/recover-list-failure.out"
grep -Fqx "$HOSTED_IDENTITY_REQUESTER_KUBECONFIG" \
  "$FAKE_IDENTITY_LIST_KUBECONFIG_LOG"

# One failed retirement must not stop stale cleanup. The next runtime is still
# deleted and its identity is fully retired, while the overall command reports
# the aggregate failure after preserving operation ordering across both runtimes.
reset_case
export FAKE_NAMESPACE_ROWS=$'pr-101\t101\npr-102\t102\n'
export FAKE_PRUNE_METADATA=$'open\tfeature/stack\thuman\t'"${adversarial_labels_base64}"$'\n'
export FAKE_PR_102_PRIORITY=false
export FAKE_PRUNE_MULTI_TEST=true
export HOSTED_IDENTITY_MODE=hosted-controller
export FAKE_RUNTIME_NAMESPACE_PRESENT=false
export FAKE_RECORD_RUNTIME_CHECK=true
export PREVIEW_DELETE_TIMEOUT=43
export FAKE_IDENTITY_LOOKUP_FAIL_NAMESPACE=pr-101
export FAKE_IDENTITY_JSON='{"apiVersion":"platform.firemud.dev/v1alpha1","kind":"HostedEnvironmentIdentity","metadata":{"namespace":"firemud-system","name":"pr-102"}}'
if bash "$PRUNER" --apply --retire-terminal-identities >"$TEMP_DIR/multiple-retire.out" 2>&1; then
  echo "pruner suppressed an aggregate hosted identity retirement failure" >&2
  exit 1
fi
grep -Fqx 'pr-101 pr-101' "$FAKE_DELETE_LOG"
grep -Fqx 'pr-102 pr-102' "$FAKE_DELETE_LOG"
test "$(<"$FAKE_OPERATION_SEQUENCE")" = $'runtime-delete\nruntime-check\nruntime-delete\nruntime-check\nidentity-request\nidentity-wait\nidentity-delete'
grep -Fqx 'pr-102 Retired' "$FAKE_IDENTITY_REQUEST_LOG"
grep -Fqx -- '--retired pr-102 43' "$FAKE_IDENTITY_WAIT_LOG"
grep -Fqx -- '-n firemud-system delete hostedenvironmentidentity pr-102 --ignore-not-found --wait=true --timeout=43s' \
  "$FAKE_IDENTITY_LOG"
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

# An empty resource version is never sufficient to delete a PR namespace.
reset_case
export FAKE_RUNTIME_NAMESPACE_RESOURCE_VERSION=''
if bash "$DELETE_HOSTED_NAMESPACE" pr-101 pr-101; then
  echo "hosted deletion accepted a preview namespace without a resource version" >&2
  exit 1
fi
test ! -e "$FAKE_RUNTIME_DELETE_UID_LOG"

# A first legacy retry-record write changes the resource version, not the UID.
reset_case
export FAKE_RUNTIME_NAMESPACE_RESOURCE_VERSION=rv-no-record
export FAKE_RUNTIME_DELETE_CURRENT_VERSION=rv-first-record
export FAKE_RUNTIME_NAMESPACE_PRESENT_AFTER_WAIT=false
if bash "$DELETE_HOSTED_NAMESPACE" pr-101 pr-101; then
  echo "hosted deletion crossed a first legacy retry-record write" >&2
  exit 1
fi
grep -qx 'uid-pr-101' "$FAKE_RUNTIME_DELETE_UID_LOG"
test ! -e "$FAKE_RUNTIME_WAIT_MARKER"
test ! -e "$FAKE_RUNTIME_NAMESPACE_DELETED_MARKER"
test "$(grep -Fc 'delete --raw /api/v1/namespaces/pr-101 -f -' "$FAKE_RUNTIME_KUBECTL_LOG")" -eq 1

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
test "$(grep -Fc 'get namespace pr-101 --ignore-not-found -o json' "$FAKE_RUNTIME_KUBECTL_LOG")" -eq 2
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
export FAKE_RUNTIME_NAMESPACE_UID=uid-pr-101-original
export FAKE_RUNTIME_RECHECK_UID=uid-pr-101-recreated
if bash "$DELETE_HOSTED_NAMESPACE" pr-101 pr-101 \
  >"$TEMP_DIR/recreated-runtime-namespace.out" 2>&1; then
  echo "hosted deletion accepted a same-name recreated namespace" >&2
  exit 1
fi
grep -Fqx \
  'hosted namespace pr-101 was recreated with a new UID while deletion completed' \
  "$TEMP_DIR/recreated-runtime-namespace.out"

reset_case
export FAKE_RUNTIME_RECHECK_IDENTITY=pr-102
if bash "$DELETE_HOSTED_NAMESPACE" pr-101 pr-101; then
  echo "hosted deletion accepted an unexpected post-wait namespace identity" >&2
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
if run_allocator pr-900 1 900 "$FAKE_TARGET_HEAD"; then
  echo "priority allocation reclaimed only one slot from an over-capacity pool" >&2
  exit 1
fi
test ! -e "$FAKE_DELETE_LOG"

reset_case
export FAKE_NAMESPACE_ROWS='2026-01-01T00:00:00Z|pr-900|900|2026-01-01T00:00:00Z|base-900|head-900|merge-900|image-900\n2026-01-02T00:00:00Z|pr-101|101|2026-01-02T00:00:00Z|base-101|head-101|merge-101|image-101\n2026-01-03T00:00:00Z|pr-102|102|2026-01-04T00:00:00Z|base-102|head-102|merge-102|image-102\n'
run_allocator pr-900 1 900 "$FAKE_TARGET_HEAD"
test ! -e "$FAKE_DELETE_LOG"

trusted_workflow="$ROOT_DIR/.github/workflows/hosted-identity-request.yml"
reconciler_workflow="$ROOT_DIR/.github/workflows/preview-reconciler.yml"
eligibility_script="$ROOT_DIR/dev-tools/hosted/preview/preview-eligibility.py"
RECONCILER_RUN="$ROOT_DIR/dev-tools/hosted/preview/reconcile-preview-deploys.sh"
for holder_verb in get patch; do
  grep -Fq "kubectl auth can-i $holder_verb configmaps/firemud-preview-proof-retries -n firemud-system" "$reconciler_workflow"
done
grep -Fq 'kubectl -n firemud-system get configmap firemud-preview-proof-retries -o json' "$reconciler_workflow"
preview_base_sha=bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb
preview_head_sha=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
preview_merge_sha=cccccccccccccccccccccccccccccccccccccccc
# The ordinary fake PR changes only README.md, so the resolver preserves the
# proven develop base image. A separate stacked case below proves that an
# unpublished feature-branch SHA is never selected as an image identity.
preview_image_tag="${preview_base_sha}"
stacked_preview_image_tag="pr-merge-${preview_merge_sha}"
export DEFAULT_BRANCH=develop
grep -Fq 'set -euo pipefail' "$RECONCILER_RUN"
grep -Fq 'repair_requested_tuple() {' "$RECONCILER_RUN"
# shellcheck disable=SC2016 # Assert literal shell and jq expressions in workflow source.
grep -Fq 'max_active="${PREVIEW_MAX_ACTIVE:-2}"' "$RECONCILER_RUN"
for repair_status_assignment in \
  'readonly repair_success=0' \
  'readonly repair_skip=10' \
  'readonly repair_dispatch=11' \
  'readonly repair_missing_proof=12' \
  'readonly repair_abort=20'; do
  grep -Fq "$repair_status_assignment" "$RECONCILER_RUN"
done
# shellcheck disable=SC2016 # Assert the literal repair status dispatch in workflow source.
grep -Fq 'case "$repair_status" in' "$RECONCILER_RUN"
grep -Fq -- 'gh api --paginate --slurp' "$RECONCILER_RUN"
# shellcheck disable=SC2016 # Assert literal workflow source expressions.
grep -Fq -- 'actions/workflows/${preview_workflow_id}/runs?branch=${DEFAULT_BRANCH}&status=${active_status}&per_page=100' "$RECONCILER_RUN"
# shellcheck disable=SC2016 # Event scopes include automatic renders and direct closed cleanup.
grep -Fq -- 'for active_run_scope in branch pull_request pull_request_target workflow_run' "$RECONCILER_RUN"
# shellcheck disable=SC2016 # Assert literal workflow query variables.
grep -Fq -- 'actions/workflows/${preview_workflow_id}/runs?event=${active_run_scope}&status=${active_status}&per_page=100' "$RECONCILER_RUN"
# shellcheck disable=SC2016 # Assert workflow_run consumers are included without a branch filter.
grep -Fq -- 'actions/workflows/${consumer_workflow_id}/runs?event=workflow_run&status=${active_status}&per_page=100' "$RECONCILER_RUN"
# shellcheck disable=SC2016 # Assert each supported active status is queried independently.
grep -Fq -- 'for active_status in requested queued in_progress waiting pending; do' "$RECONCILER_RUN"
# shellcheck disable=SC2016 # Assert the active-run lookup is fenced to the candidate dispatch title.
grep -Fq -- '--arg run_name "Preview dispatch pr-${pr_number}-base-${base_sha}-head-${head_sha}-merge-${merge_sha}"' "$RECONCILER_RUN"
# shellcheck disable=SC2016 # Assert the literal jq display-title comparison in workflow source.
grep -Fq -- '.display_title == $run_name' "$RECONCILER_RUN"
# shellcheck disable=SC2016 # Assert literal jq expressions in workflow source.
grep -Fq -- '.name == $workflow_name' "$RECONCILER_RUN"
# shellcheck disable=SC2016 # Assert literal jq expressions in workflow source.
grep -Fq -- '.head_branch == $default_branch' "$RECONCILER_RUN"
for proof_annotation in \
  'firemud.dev/proof-preview-base-sha' \
  'firemud.dev/proof-preview-head-sha' \
  'firemud.dev/proof-preview-merge-sha' \
  'firemud.dev/proof-preview-image-tag' \
  'firemud.dev/proof-preview-namespace-uid'; do
  grep -Fq "$proof_annotation" "$RECONCILER_RUN"
done
for active_status in requested queued in_progress waiting pending; do
  grep -Fq -- ".status == \"${active_status}\"" "$RECONCILER_RUN"
done
if grep -Fq '| head -n 1' "$RECONCILER_RUN"; then
  echo "reconciler must select an active preview run without a pipefail-unsafe head" >&2
  exit 1
fi

reset_case
reconciler_valid_output="$TEMP_DIR/reconciler-valid.out"
(
  cd "$ROOT_DIR"
  FAKE_OPEN_PRIORITY_ROWS="1\t901\thead-901\thuman\tdevelop\topen\t${adversarial_labels_base64}\n" \
    FAKE_PR_901_HEAD=head-901 \
    FAKE_PR_901_REQUESTED_HEAD=head-901 \
PREVIEW_MAX_ACTIVE=2 \
    bash "$RECONCILER_RUN"
) > "$reconciler_valid_output"
if ! grep -Fqx "Preview pr-901 already aligned to ${preview_base_sha}/${preview_head_sha}/${preview_merge_sha}/${preview_image_tag}" "$reconciler_valid_output"; then
  echo "reconciler did not preserve an aligned preview with transported labels" >&2
  sed 's/^/reconciler output: /' "$reconciler_valid_output" >&2
  exit 1
fi
test "$(<"$FAKE_NAMESPACE_SNAPSHOT_CALLS")" -eq 1

reset_case
reconciler_missing_proof_output="$TEMP_DIR/reconciler-missing-proof.out"
(
  cd "$ROOT_DIR"
  FAKE_OPEN_PRIORITY_ROWS="1\t901\thead-901\thuman\tdevelop\topen\t${adversarial_labels_base64}\n" \
    FAKE_PR_901_HEAD=head-901 \
    FAKE_PR_901_REQUESTED_HEAD=head-901 \
    FAKE_PR_901_PROOF_COMPLETE=false \
PREVIEW_MAX_ACTIVE=2 \
    bash "$RECONCILER_RUN"
) > "$reconciler_missing_proof_output"
grep -Fqx \
  "Preview pr-901 deployed tuple is missing proof for ${preview_base_sha}/${preview_head_sha}/${preview_merge_sha}/${preview_image_tag}; dispatching retry." \
  "$reconciler_missing_proof_output"
grep -Fqx \
  "Dispatching preview render for PR #901 (${preview_base_sha}/${preview_head_sha}/${preview_merge_sha}/${preview_image_tag}) from trusted ref develop" \
  "$reconciler_missing_proof_output"
grep -Fq 'repos/example/FireMUD/dispatches -f event_type=preview-deploy' "$FAKE_DISPATCH_LOG"

reset_case
reconciler_recreated_namespace_output="$TEMP_DIR/reconciler-recreated-namespace.out"
(
  cd "$ROOT_DIR"
  FAKE_OPEN_PRIORITY_ROWS="1\t901\thead-901\thuman\tdevelop\topen\t${adversarial_labels_base64}\n" \
    FAKE_PR_901_HEAD=head-901 \
    FAKE_PR_901_REQUESTED_HEAD=head-901 \
    FAKE_PR_901_PROOF_COMPLETE=false \
    FAKE_PR_901_NAMESPACE_UID=uid-observed \
    FAKE_PR_901_RECHECK_NAMESPACE_UID=uid-recreated \
    PREVIEW_MAX_ACTIVE=2 \
    bash "$RECONCILER_RUN"
) > "$reconciler_recreated_namespace_output" 2>&1
grep -Fq \
  'namespace pr-901 was recreated before proof retry dispatch; refusing to charge its retry budget.' \
  "$reconciler_recreated_namespace_output"
test ! -e "$FAKE_ANNOTATE_LOG"
test ! -e "$FAKE_DISPATCH_LOG"

reset_case
reconciler_unrelated_consumer_output="$TEMP_DIR/reconciler-trusted-consumer.out"
(
  cd "$ROOT_DIR"
  FAKE_OPEN_PRIORITY_ROWS="1\t901\thead-901\thuman\tdevelop\topen\t${adversarial_labels_base64}\n" \
    FAKE_PR_901_HEAD=head-901 \
    FAKE_PR_901_REQUESTED_HEAD=head-901 \
    FAKE_PR_901_PROOF_COMPLETE=false \
    FAKE_ACTIVE_PREVIEW_RUN_PAGES_JSON="$(jq -cn \
      --arg title "Preview dispatch pr-901-base-${preview_base_sha}-head-${preview_head_sha}-merge-${preview_merge_sha}" \
      '[{workflow_runs:[{id:5252,path:".github/workflows/hosted-identity-request.yml",name:"Trusted Hosted Identity Request",head_branch:"develop",display_title:$title,status:"in_progress"}]}]')" \
PREVIEW_MAX_ACTIVE=2 \
    bash "$RECONCILER_RUN"
) > "$reconciler_unrelated_consumer_output"
grep -Fqx \
  "Dispatching preview render for PR #901 (${preview_base_sha}/${preview_head_sha}/${preview_merge_sha}/${preview_image_tag}) from trusted ref develop" \
  "$reconciler_unrelated_consumer_output"
test -s "$FAKE_DISPATCH_LOG"

reset_case
reconciler_source_title_consumer_output="$TEMP_DIR/reconciler-unqualified-consumer.out"
(
  cd "$ROOT_DIR"
  FAKE_OPEN_PRIORITY_ROWS="1\t901\thead-901\thuman\tdevelop\topen\t${adversarial_labels_base64}\n" \
    FAKE_PR_901_HEAD=head-901 \
    FAKE_PR_901_REQUESTED_HEAD=head-901 \
    FAKE_PR_901_PROOF_COMPLETE=false \
    FAKE_ACTIVE_PREVIEW_RUN_PAGES_JSON='[{"workflow_runs":[{"id":5253,"path":".github/workflows/hosted-identity-request.yml","head_sha":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","pull_requests":[{"number":901}],"name":"Trusted Hosted Identity Request","head_branch":"develop","display_title":"PR preview 901","status":"queued"}]}]' \
PREVIEW_MAX_ACTIVE=2 \
    bash "$RECONCILER_RUN"
) > "$reconciler_source_title_consumer_output"
grep -Fqx \
  "Dispatching preview render for PR #901 (${preview_base_sha}/${preview_head_sha}/${preview_merge_sha}/${preview_image_tag}) from trusted ref develop" \
  "$reconciler_source_title_consumer_output"
test -s "$FAKE_DISPATCH_LOG"

# An automatic run must prove exactly this tuple in its current native attempt.
for tuple_case in current qualified target old_merge missing ambiguous stale_attempt wrong_list_path wrong_latest_path wrong_list_id wrong_latest_repo jobs_unavailable latest_unavailable old_event_current_plan event_only latest_missing_head latest_missing_path jobs_incomplete jobs_malformed stale_attempt_old_tuple job_attempt_missing job_attempt_mismatch job_attempt_null completed_failed_plan completed_success completed_plan_only completed_missing_upload completed_success_old_tuple completed_noop completed_old_noop active_noop; do
  reset_case
  reconciler_pull_request_output="$TEMP_DIR/reconciler-pull-request-$tuple_case.out"
  tuple_name="Preview resolved tuple pr-901 base-${preview_base_sha} head-${preview_head_sha} merge-${preview_merge_sha}"
  old_tuple_name="Preview resolved tuple pr-901 base-${preview_base_sha} head-${preview_head_sha} merge-dddddddddddddddddddddddddddddddddddddddd"
  plan_jobs="$(jq -cn --arg tuple "$tuple_name" --arg old "$old_tuple_name" \
    --arg head "$preview_head_sha" --arg variant "$tuple_case" '
      def step($name): {name:$name,status:"completed",conclusion:"success"};
      [{name:"Preview Plan",run_id:5254,
        run_attempt:(if $variant == "job_attempt_mismatch" then 1 elif $variant == "job_attempt_null" then null else 2 end),
        head_sha:$head,status:"completed",
        conclusion:(if $variant == "completed_failed_plan" then "failure" else "success" end),
        steps:(if $variant == "old_merge" or $variant == "stale_attempt_old_tuple" then [step($old)]
               elif $variant == "completed_success_old_tuple" then [step($old)]
               elif $variant == "ambiguous" then [step($tuple),step($old)]
               elif $variant == "event_only" then [step($tuple | sub("resolved"; "event"))]
               elif $variant == "completed_noop" then [step($tuple | sub("resolved"; "event")), (step($tuple) | .conclusion = "skipped"), step("Explain preview skip")]
               elif $variant == "active_noop" then [step($tuple | sub("resolved"; "event")),step("Explain preview skip")]
               elif $variant == "completed_old_noop" then [step($old | sub("resolved"; "event")),step("Explain preview skip")]
               elif $variant == "old_event_current_plan" then [step($old | sub("resolved"; "event")),step($tuple)]
               else [step($tuple),step($tuple | sub("resolved"; "event"))] end)}]
      + (if $variant == "completed_success" or $variant == "completed_missing_upload" then
          [{name:"Render Sanitized Preview Artifact",run_id:5254,run_attempt:2,head_sha:$head,
            status:"completed",conclusion:"success",
            steps:(if $variant == "completed_success" then [step("Upload sanitized render artifact")] else [] end)}]
         else [] end) |
      if $variant == "job_attempt_missing" then map(del(.run_attempt)) else . end |
      if $variant == "missing" then {jobs:[],total_count:0}
      elif $variant == "jobs_incomplete" then {jobs:.,total_count:2}
      elif $variant == "jobs_malformed" then {jobs:null,total_count:1}
      else {jobs:.,total_count:length} end')"
  latest_source="$(jq -cn --arg head "$preview_head_sha" --arg variant "$tuple_case" '
    {id:5254,run_attempt:(if $variant == "stale_attempt" or $variant == "stale_attempt_old_tuple" then 3 else 2 end),
     head_sha:$head,event:(if $variant == "target" then "pull_request_target" else "pull_request" end),workflow_id:173511323,display_title:"PR preview 901",repository:{full_name:(if $variant == "wrong_latest_repo" then "example/Other" else "example/FireMUD" end)},head_repository:{full_name:"example/FireMUD"},pull_requests:[{number:901}],path:(if $variant == "qualified" then ".github/workflows/preview.yml@refs/pull/901/merge" elif $variant == "wrong_latest_path" then ".github/workflows/preview.yml@refs/pull/902/merge" else ".github/workflows/preview.yml" end),status:"in_progress"}
     | if $variant == "latest_missing_head" then del(.head_sha)
       elif $variant == "latest_missing_path" then del(.path)
       elif $variant == "completed_failed_plan" then .status = "completed" | .conclusion = "failure"
       elif $variant == "completed_success" or $variant == "completed_plan_only" or $variant == "completed_missing_upload" or $variant == "completed_success_old_tuple" or $variant == "completed_noop" or $variant == "completed_old_noop" then .status = "completed" | .conclusion = "success"
       else . end')"
  (
    cd "$ROOT_DIR"
    FAKE_OPEN_PRIORITY_ROWS="1\t901\thead-901\thuman\tdevelop\topen\t${adversarial_labels_base64}\n" \
      FAKE_PR_901_HEAD=head-901 \
      FAKE_PR_901_REQUESTED_HEAD=head-901 \
      FAKE_PR_901_PROOF_COMPLETE=false \
      FAKE_PREVIEW_PLAN_JOBS_FAIL="$([[ "$tuple_case" == jobs_unavailable ]] && echo true || echo false)" \
      FAKE_PREVIEW_SOURCE_LATEST_FAIL="$([[ "$tuple_case" == latest_unavailable ]] && echo true || echo false)" \
      FAKE_PREVIEW_PLAN_JOBS_JSON="$plan_jobs" \
      FAKE_PREVIEW_SOURCE_LATEST_JSON="$latest_source" \
      FAKE_ACTIVE_PREVIEW_RUN_PAGES_JSON="$(jq -cn --arg variant "$tuple_case" '[{workflow_runs:[{id:5254,run_attempt:2,event:(if $variant == "target" then "pull_request_target" else "pull_request" end),workflow_id:(if $variant == "wrong_list_id" then 1 else 173511323 end),repository:{full_name:"example/FireMUD"},head_repository:{full_name:"example/FireMUD"},path:(if $variant == "qualified" then ".github/workflows/preview.yml@refs/pull/901/merge" elif $variant == "wrong_list_path" then ".github/workflows/preview.yml@refs/pull/902/merge" else ".github/workflows/preview.yml" end),head_sha:"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",pull_requests:[{number:901}],name:"PR Preview Environment",head_branch:"feature-preview",display_title:"PR preview 901",status:"in_progress"}]}]')" \
      PREVIEW_MAX_ACTIVE=2 \
      bash "$RECONCILER_RUN"
  ) > "$reconciler_pull_request_output"
  if [[ "$tuple_case" == current || "$tuple_case" == qualified || "$tuple_case" == old_event_current_plan || "$tuple_case" == job_attempt_missing ]]; then
    grep -Fqx \
      "Skipping proof retry for PR #901: preview source or trusted consumer run 5254 for ${preview_base_sha}/${preview_head_sha}/${preview_merge_sha} is already queued or in progress." \
      "$reconciler_pull_request_output"
    test ! -e "$FAKE_ANNOTATE_LOG"
    test ! -e "$FAKE_DISPATCH_LOG"
  elif [[ "$tuple_case" == target || "$tuple_case" == missing || "$tuple_case" == ambiguous || "$tuple_case" == jobs_unavailable || "$tuple_case" == latest_unavailable || "$tuple_case" == event_only || "$tuple_case" == stale_attempt || "$tuple_case" == latest_missing_head || "$tuple_case" == latest_missing_path || "$tuple_case" == jobs_incomplete || "$tuple_case" == jobs_malformed || "$tuple_case" == stale_attempt_old_tuple || "$tuple_case" == job_attempt_mismatch || "$tuple_case" == job_attempt_null || "$tuple_case" == active_noop || "$tuple_case" == completed_plan_only || "$tuple_case" == completed_missing_upload ]]; then
    grep -Fqx \
      "Skipping proof retry for PR #901: active preview tuple lookup unavailable; retry budget unchanged." \
      "$reconciler_pull_request_output"
    test ! -e "$FAKE_ANNOTATE_LOG"
    test ! -e "$FAKE_RETRY_PATCH_LOG"
    test ! -e "$FAKE_DISPATCH_LOG"
  elif [[ "$tuple_case" == completed_success ]]; then
    grep -Fqx \
      "Deferring proof retry for PR #901: successful exact preview source run 5254 may still be handing off to the trusted consumer." \
      "$reconciler_pull_request_output"
    test ! -e "$FAKE_ANNOTATE_LOG"
    test ! -e "$FAKE_DISPATCH_LOG"
  else
    grep -Fqx \
      "Dispatching preview render for PR #901 (${preview_base_sha}/${preview_head_sha}/${preview_merge_sha}/${preview_image_tag}) from trusted ref develop" \
      "$reconciler_pull_request_output"
    test -s "$FAKE_DISPATCH_LOG"
  fi
done

make_recent_source_evidence() {
  local updated_at="$1"
  local tuple_name="Preview resolved tuple pr-901 base-${preview_base_sha} head-${preview_head_sha} merge-${preview_merge_sha}"
  FAKE_PREVIEW_PLAN_JOBS_JSON="$(jq -cn --arg tuple "$tuple_name" --arg head "$preview_head_sha" '
    {total_count:2,jobs:[{name:"Preview Plan",run_id:5254,run_attempt:2,head_sha:$head,
      status:"completed",conclusion:"success",steps:[{name:$tuple,status:"completed",conclusion:"success"}]},
      {name:"Render Sanitized Preview Artifact",run_id:5254,run_attempt:2,head_sha:$head,
       status:"completed",conclusion:"success",steps:[{name:"Upload sanitized render artifact",
         status:"completed",conclusion:"success"}]}]}')"
  export FAKE_PREVIEW_PLAN_JOBS_JSON
  FAKE_PREVIEW_SOURCE_LATEST_JSON="$(jq -cn --arg head "$preview_head_sha" --arg updated_at "$updated_at" '
    {id:5254,run_attempt:2,head_sha:$head,event:"pull_request",workflow_id:173511323,
      repository:{full_name:"example/FireMUD"},head_repository:{full_name:"example/FireMUD"},
      pull_requests:[{number:901}],path:".github/workflows/preview.yml",status:"completed",
      display_title:"PR preview 901",conclusion:"success",updated_at:$updated_at}')"
  export FAKE_PREVIEW_SOURCE_LATEST_JSON
  FAKE_RECENT_PREVIEW_SOURCE_RUNS_JSON="$(jq -cn --arg head "$preview_head_sha" \
    --arg updated_at "$updated_at" '
    {total_count:1,workflow_runs:[{id:5254,run_attempt:2,workflow_id:173511323,event:"pull_request",
      status:"completed",conclusion:"success",name:"PR preview 901",display_title:"PR preview 901",
      head_branch:"feature-preview",head_sha:$head,path:".github/workflows/preview.yml",
      repository:{full_name:"example/FireMUD"},head_repository:{full_name:"example/FireMUD"},
      pull_requests:[{number:901}],created_at:"2026-01-01T00:00:00Z",updated_at:$updated_at}]}')"
  export FAKE_RECENT_PREVIEW_SOURCE_RUNS_JSON
}

make_recent_dispatch_source_evidence() {
  local updated_at="$1" outcome="${2:-render}"
  local dispatch_title="Preview dispatch pr-901-base-${preview_base_sha}-head-${preview_head_sha}-merge-${preview_merge_sha}"
  FAKE_PREVIEW_DISPATCH_JOBS_JSON="$(jq -cn --arg head "$preview_base_sha" --arg outcome "$outcome" '
    def step($name): {name:$name,status:"completed",conclusion:"success"};
    [{name:"Preview Plan",run_id:5255,run_attempt:1,head_sha:$head,status:"completed",conclusion:"success",
      steps:(if $outcome == "skip" then [step("Explain preview skip")] else [] end)}]
    + (if $outcome == "render" then [{name:"Render Sanitized Preview Artifact",run_id:5255,
        run_attempt:1,head_sha:$head,status:"completed",conclusion:"success",
        steps:[step("Upload sanitized render artifact")]}] else [] end)
    | {jobs:.,total_count:length}')"
  export FAKE_PREVIEW_DISPATCH_JOBS_JSON
  FAKE_PREVIEW_DISPATCH_LATEST_JSON="$(jq -cn --arg head "$preview_base_sha" \
    --arg title "$dispatch_title" --arg updated_at "$updated_at" '
    {id:5255,run_attempt:1,head_sha:$head,event:"repository_dispatch",workflow_id:173511323,
      name:"PR Preview Environment",display_title:$title,head_branch:"develop",
      repository:{full_name:"example/FireMUD"},head_repository:{full_name:"example/FireMUD"},
      pull_requests:[],path:".github/workflows/preview.yml",status:"completed",conclusion:"success",
      updated_at:$updated_at}')"
  export FAKE_PREVIEW_DISPATCH_LATEST_JSON
  FAKE_RECENT_PREVIEW_DISPATCH_RUNS_JSON="$(jq -cn --arg head "$preview_base_sha" \
    --arg title "$dispatch_title" --arg updated_at "$updated_at" '
    {total_count:1,workflow_runs:[{id:5255,run_attempt:1,workflow_id:173511323,event:"repository_dispatch",
      status:"completed",conclusion:"success",name:"PR Preview Environment",display_title:$title,
      head_branch:"develop",head_sha:$head,path:".github/workflows/preview.yml",
      repository:{full_name:"example/FireMUD"},head_repository:{full_name:"example/FireMUD"},
      pull_requests:[],updated_at:$updated_at}]}')"
  export FAKE_RECENT_PREVIEW_DISPATCH_RUNS_JSON
}

reset_case
recent_source_updated_at="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
make_recent_source_evidence "$recent_source_updated_at"
reconciler_recent_source_handoff_output="$TEMP_DIR/reconciler-recent-source-handoff.out"
(
  cd "$ROOT_DIR"
  FAKE_OPEN_PRIORITY_ROWS="1\t901\thead-901\thuman\tdevelop\topen\t${adversarial_labels_base64}\n" \
    FAKE_PR_901_HEAD=head-901 \
    FAKE_PR_901_REQUESTED_HEAD=head-901 \
    FAKE_PR_901_PROOF_COMPLETE=false \
    FAKE_ACTIVE_PREVIEW_RUN_PAGES_JSON='[{"workflow_runs":[]}]' \
    PREVIEW_MAX_ACTIVE=2 \
    bash "$RECONCILER_RUN"
) > "$reconciler_recent_source_handoff_output"
grep -Fqx \
  "Deferring proof retry for PR #901: exact successful preview source run 5254 is within the 600-second trusted-consumer handoff window." \
  "$reconciler_recent_source_handoff_output"
test ! -e "$FAKE_ANNOTATE_LOG"
test ! -e "$FAKE_DISPATCH_LOG"
test -s "$FAKE_RECENT_HANDOFF_LOOKUP_LOG"

reset_case
recent_source_updated_at="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
make_recent_dispatch_source_evidence "$recent_source_updated_at" render
reconciler_recent_dispatch_handoff_output="$TEMP_DIR/reconciler-recent-dispatch-handoff.out"
(
  cd "$ROOT_DIR"
  FAKE_OPEN_PRIORITY_ROWS="1\t901\thead-901\thuman\tdevelop\topen\t${adversarial_labels_base64}\n" \
    FAKE_PR_901_HEAD=head-901 \
    FAKE_PR_901_REQUESTED_HEAD=head-901 \
    FAKE_PR_901_PROOF_COMPLETE=false \
    FAKE_ACTIVE_PREVIEW_RUN_PAGES_JSON='[{"workflow_runs":[]}]' \
    PREVIEW_MAX_ACTIVE=2 \
    bash "$RECONCILER_RUN"
) > "$reconciler_recent_dispatch_handoff_output"
grep -Fqx \
  'Deferring proof retry for PR #901: exact successful preview source run 5255 is within the 600-second trusted-consumer handoff window.' \
  "$reconciler_recent_dispatch_handoff_output"
test ! -e "$FAKE_ANNOTATE_LOG"
test ! -e "$FAKE_DISPATCH_LOG"

# Capped all-history listings split by creation across the full history. The
# matching old-created attempt still counts when it finished this cycle.
completed_history="$TEMP_DIR/completed-history.json"
jq -c '
  .workflow_runs[0] as $current
  | [range(1000) as $i | $current | .id=(10000+$i) |
      .created_at=(if $i < 500 then "2008-01-01T00:00:00Z" else "2025-01-01T00:00:00Z" end) |
      .updated_at="2025-01-01T00:01:00Z"]
    + [$current | .created_at="2008-01-02T00:00:00Z"]
' <<<"$FAKE_RECENT_PREVIEW_DISPATCH_RUNS_JSON" > "$completed_history"
for partition in complete broken; do
  rm -f -- "$FAKE_ANNOTATE_LOG" "$FAKE_DISPATCH_LOG"
  partition_output="$TEMP_DIR/reconciler-completed-partition-$partition.out"
  partition_status=0
  (
    cd "$ROOT_DIR"
    FAKE_OPEN_PRIORITY_ROWS="1\t901\thead-901\thuman\tdevelop\topen\t${adversarial_labels_base64}\n" \
      FAKE_PR_901_HEAD=head-901 \
      FAKE_PR_901_REQUESTED_HEAD=head-901 \
      FAKE_PR_901_PROOF_COMPLETE=false \
      FAKE_ACTIVE_PREVIEW_RUN_PAGES_JSON='[{"workflow_runs":[]}]' \
      FAKE_COMPLETED_RUN_DATA="$completed_history" \
      FAKE_BROKEN_COMPLETED_PARTITION="$([[ "$partition" == broken ]] && echo true || echo false)" \
      PREVIEW_MAX_ACTIVE=2 \
      bash "$RECONCILER_RUN"
  ) > "$partition_output" 2>&1 || partition_status=$?
  if [[ "$partition" == complete ]]; then
    [[ "$partition_status" == 0 ]]
    grep -Fq 'exact successful preview source run 5255 is within the 600-second trusted-consumer handoff window' "$partition_output"
  else
    [[ "$partition_status" != 0 ]]
    grep -Fq 'completed preview-source inventory read failed' "$partition_output"
  fi
  test ! -e "$FAKE_ANNOTATE_LOG"
  test ! -e "$FAKE_DISPATCH_LOG"
done

reset_case
recent_source_updated_at="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
make_recent_dispatch_source_evidence "$recent_source_updated_at" skip
reconciler_completed_dispatch_skip_output="$TEMP_DIR/reconciler-completed-dispatch-skip.out"
(
  cd "$ROOT_DIR"
  FAKE_OPEN_PRIORITY_ROWS="1\t901\thead-901\thuman\tdevelop\topen\t${adversarial_labels_base64}\n" \
    FAKE_PR_901_HEAD=head-901 \
    FAKE_PR_901_REQUESTED_HEAD=head-901 \
    FAKE_PR_901_PROOF_COMPLETE=false \
    FAKE_ACTIVE_PREVIEW_RUN_PAGES_JSON='[{"workflow_runs":[]}]' \
    PREVIEW_MAX_ACTIVE=2 \
    bash "$RECONCILER_RUN"
) > "$reconciler_completed_dispatch_skip_output"
grep -Fqx \
  "Recorded proof retry 1/3 for PR #901 (${preview_base_sha}/${preview_head_sha}/${preview_merge_sha}/${preview_image_tag})." \
  "$reconciler_completed_dispatch_skip_output"
test -s "$FAKE_RETRY_PATCH_LOG"
test -s "$FAKE_DISPATCH_LOG"

reset_case
recent_source_updated_at="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
make_recent_dispatch_source_evidence "$recent_source_updated_at" unproven
reconciler_unproven_dispatch_output="$TEMP_DIR/reconciler-unproven-dispatch.out"
(
  cd "$ROOT_DIR"
  FAKE_OPEN_PRIORITY_ROWS="1\t901\thead-901\thuman\tdevelop\topen\t${adversarial_labels_base64}\n" \
    FAKE_PR_901_HEAD=head-901 \
    FAKE_PR_901_REQUESTED_HEAD=head-901 \
    FAKE_PR_901_PROOF_COMPLETE=false \
    FAKE_ACTIVE_PREVIEW_RUN_PAGES_JSON='[{"workflow_runs":[]}]' \
    PREVIEW_MAX_ACTIVE=2 \
    bash "$RECONCILER_RUN"
) > "$reconciler_unproven_dispatch_output" 2>&1
grep -Fq \
  'Skipping proof retry for PR #901: recent preview handoff lookup unavailable; retry budget unchanged.' \
  "$reconciler_unproven_dispatch_output"
test ! -e "$FAKE_ANNOTATE_LOG"
test ! -e "$FAKE_RETRY_PATCH_LOG"
test ! -e "$FAKE_DISPATCH_LOG"

reset_case
reconciler_inventory_failed_output="$TEMP_DIR/reconciler-inventory-failed.out"
if (
  cd "$ROOT_DIR"
  FAKE_OPEN_PRIORITY_ROWS="1\t901\thead-901\thuman\tdevelop\topen\t${adversarial_labels_base64}\n" \
    FAKE_PR_901_HEAD=head-901 \
    FAKE_PR_901_REQUESTED_HEAD=head-901 \
    FAKE_PR_901_PROOF_COMPLETE=false \
    FAKE_RECENT_PREVIEW_SOURCE_RUNS_JSON='{"total_count":1,"workflow_runs":[]}' \
    PREVIEW_MAX_ACTIVE=2 \
    bash "$RECONCILER_RUN"
) > "$reconciler_inventory_failed_output" 2>&1; then
  echo "incomplete handoff inventory made the reconcile job successful" >&2
  exit 1
fi
grep -Fq 'completed preview-source inventory read failed.' "$reconciler_inventory_failed_output"
test ! -e "$FAKE_ANNOTATE_LOG"
test ! -e "$FAKE_DISPATCH_LOG"

reset_case
make_recent_dispatch_source_evidence "$(date -u +%Y-%m-%dT%H:%M:%SZ)" unproven
dispatch_title="Preview dispatch pr-901-base-${preview_base_sha}-head-${preview_head_sha}-merge-${preview_merge_sha}"
FAKE_PREVIEW_DISPATCH_LATEST_JSON="$(jq -c 'del(.conclusion,.updated_at) | .status = "in_progress"' \
  <<<"$FAKE_PREVIEW_DISPATCH_LATEST_JSON")"
export FAKE_PREVIEW_DISPATCH_LATEST_JSON
FAKE_ACTIVE_PREVIEW_RUN_PAGES_JSON="$(jq -cn --arg title "$dispatch_title" --arg head "$preview_base_sha" '
  [{workflow_runs:[{id:5255,run_attempt:1,workflow_id:173511323,event:"repository_dispatch",
    repository:{full_name:"example/FireMUD"},head_repository:{full_name:"example/FireMUD"},
    path:".github/workflows/preview.yml",head_branch:"develop",head_sha:$head,
    name:"PR Preview Environment",display_title:$title,status:"in_progress"}]}]')"
export FAKE_ACTIVE_PREVIEW_RUN_PAGES_JSON
reconciler_active_unproven_dispatch_output="$TEMP_DIR/reconciler-active-unproven-dispatch.out"
(
  cd "$ROOT_DIR"
  FAKE_OPEN_PRIORITY_ROWS="1\t901\thead-901\thuman\tdevelop\topen\t${adversarial_labels_base64}\n" \
    FAKE_PR_901_HEAD=head-901 \
    FAKE_PR_901_REQUESTED_HEAD=head-901 \
    FAKE_PR_901_PROOF_COMPLETE=false \
    PREVIEW_MAX_ACTIVE=2 \
    bash "$RECONCILER_RUN"
) > "$reconciler_active_unproven_dispatch_output" 2>&1
grep -Fq \
  "Skipping proof retry for PR #901: preview source or trusted consumer run 5255 for ${preview_base_sha}/${preview_head_sha}/${preview_merge_sha} is already queued or in progress." \
  "$reconciler_active_unproven_dispatch_output"
test ! -e "$FAKE_ANNOTATE_LOG"
test ! -e "$FAKE_DISPATCH_LOG"

reset_case
recent_source_updated_at="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
make_recent_source_evidence "$recent_source_updated_at"
recent_consumer_tuple="Trusted preview deploy tuple pr-901 base-${preview_base_sha} head-${preview_head_sha} merge-${preview_merge_sha}"
FAKE_TRUSTED_CONSUMER_JOBS_JSON="$(jq -cn --arg tuple "$recent_consumer_tuple" '
  {total_count:1,jobs:[{name:"Validate Trusted Preview Target",run_id:5256,run_attempt:1,
    head_sha:"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",status:"completed",conclusion:"success",
    steps:[{name:$tuple,status:"completed",conclusion:"success"}]}]}')"
export FAKE_TRUSTED_CONSUMER_JOBS_JSON
export FAKE_TRUSTED_CONSUMER_LATEST_JSON='{"id":5256,"run_attempt":1,"event":"workflow_run","workflow_id":357129350,"path":".github/workflows/hosted-identity-request.yml","name":"Trusted Hosted Identity Request","head_branch":"develop","head_sha":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb","display_title":"PR preview 901","repository":{"full_name":"example/FireMUD"},"head_repository":{"full_name":"example/FireMUD"},"status":"queued"}'
export FAKE_RECENT_TRUSTED_CONSUMER_RUNS_JSON='{"total_count":1,"workflow_runs":[{"id":5256,"run_attempt":1,"event":"workflow_run","workflow_id":357129350,"path":".github/workflows/hosted-identity-request.yml","name":"Trusted Hosted Identity Request","head_branch":"develop","head_sha":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb","display_title":"PR preview 901","repository":{"full_name":"example/FireMUD"},"head_repository":{"full_name":"example/FireMUD"},"status":"queued"}]}'
reconciler_handoff_race_output="$TEMP_DIR/reconciler-handoff-race.out"
(
  cd "$ROOT_DIR"
  FAKE_OPEN_PRIORITY_ROWS="1\t901\thead-901\thuman\tdevelop\topen\t${adversarial_labels_base64}\n" \
    FAKE_PR_901_HEAD=head-901 \
    FAKE_PR_901_REQUESTED_HEAD=head-901 \
    FAKE_PR_901_PROOF_COMPLETE=false \
    FAKE_ACTIVE_PREVIEW_RUN_PAGES_JSON='[{"workflow_runs":[]}]' \
    PREVIEW_MAX_ACTIVE=2 \
    bash "$RECONCILER_RUN"
) > "$reconciler_handoff_race_output"
grep -Fq 'trusted consumer run 5256' "$reconciler_handoff_race_output"
test -s "$FAKE_RECENT_HANDOFF_LOOKUP_LOG"
test ! -e "$FAKE_ANNOTATE_LOG"
test ! -e "$FAKE_DISPATCH_LOG"

reset_case
recent_source_updated_at="$(date -u -d '20 minutes ago' +%Y-%m-%dT%H:%M:%SZ)"
make_recent_source_evidence "$recent_source_updated_at"
# Keep the exact qualifying run 5254 in the inventory and change only its age;
# older matching runs must not overflow the handoff candidate bound.
FAKE_RECENT_PREVIEW_SOURCE_RUNS_JSON="$(jq -cn --arg head "$preview_head_sha" \
  --arg updated_at "$recent_source_updated_at" '[range(5154;5255) as $id | {
    id:$id,run_attempt:(if $id == 5254 then 2 else 1 end),workflow_id:173511323,event:"pull_request",status:"completed",
    conclusion:"success",name:"PR preview 901",display_title:"PR preview 901",
    head_branch:"feature-preview",head_sha:$head,path:".github/workflows/preview.yml",
    repository:{full_name:"example/FireMUD"},head_repository:{full_name:"example/FireMUD"},
    pull_requests:[{number:901}],updated_at:$updated_at}] as $runs
    | [{total_count:($runs | length),workflow_runs:$runs[:100]},
       {total_count:($runs | length),workflow_runs:$runs[100:]}]')"
export FAKE_RECENT_PREVIEW_SOURCE_RUNS_JSON
reconciler_expired_source_output="$TEMP_DIR/reconciler-expired-source.out"
(
  cd "$ROOT_DIR"
  FAKE_OPEN_PRIORITY_ROWS="1\t901\thead-901\thuman\tdevelop\topen\t${adversarial_labels_base64}\n" \
    FAKE_PR_901_HEAD=head-901 \
    FAKE_PR_901_REQUESTED_HEAD=head-901 \
    FAKE_PR_901_PROOF_COMPLETE=false \
    FAKE_ACTIVE_PREVIEW_RUN_PAGES_JSON='[{"workflow_runs":[]}]' \
    PREVIEW_MAX_ACTIVE=2 \
    bash "$RECONCILER_RUN"
) > "$reconciler_expired_source_output"
grep -Fq 'Dispatching preview render for PR #901' "$reconciler_expired_source_output"
test -s "$FAKE_DISPATCH_LOG"

for observation_case in duplicate-rows lookup-overflow workflow-run-only consumer-title-only consumer-stale-attempt consumer-finished consumer-finished-without-proof consumer-active-jobs-unavailable consumer-job-attempt-missing consumer-job-attempt-mismatch consumer-job-attempt-null consumer-steps-missing; do
reset_case
reconciler_active_status_advance_output="$TEMP_DIR/reconciler-active-status-advance-${observation_case}.out"
consumer_run="$(jq -cn '{id:5256,run_attempt:1,event:"workflow_run",workflow_id:357129350,
  path:".github/workflows/hosted-identity-request.yml",name:"Trusted Hosted Identity Request",
  head_branch:"develop",head_sha:"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",display_title:"PR preview 901",
  repository:{full_name:"example/FireMUD"},head_repository:{full_name:"example/FireMUD"},status:"in_progress"}')"
namespace_absent_on_initial=false
proof_complete=false
proof_after_consumer_completion=false
if [[ "$observation_case" == consumer-finished || "$observation_case" == consumer-finished-without-proof ]]; then
  namespace_absent_on_initial=true
fi
if [[ "$observation_case" == consumer-finished ]]; then
  proof_after_consumer_completion=true
fi
(
  cd "$ROOT_DIR"
  FAKE_OPEN_PRIORITY_ROWS="1\t901\thead-901\thuman\tdevelop\topen\t${adversarial_labels_base64}\n" \
    FAKE_PR_901_HEAD=head-901 \
    FAKE_PR_901_REQUESTED_HEAD=head-901 \
    FAKE_PR_901_NAMESPACE_ABSENT_ON_INITIAL="$namespace_absent_on_initial" \
    FAKE_PR_901_PROOF_COMPLETE="$proof_complete" \
    FAKE_PR_901_PROOF_COMPLETE_AFTER_CONSUMER_COMPLETION="$proof_after_consumer_completion" \
    FAKE_TRUSTED_CONSUMER_JOBS_FAIL="$([[ "$observation_case" == consumer-finished-without-proof || "$observation_case" == consumer-active-jobs-unavailable ]] && echo true || echo false)" \
    FAKE_TRUSTED_CONSUMER_JOBS_JSON="$(jq -cn \
      --arg observation "$observation_case" \
      --arg tuple "Trusted preview deploy tuple pr-901 base-${preview_base_sha} head-${preview_head_sha} merge-${preview_merge_sha}" '
      {total_count:1,jobs:[{name:"Validate Trusted Preview Target",run_id:5256,
        run_attempt:(if $observation == "consumer-job-attempt-mismatch" then 2 elif $observation == "consumer-job-attempt-null" then null else 1 end),
        head_sha:"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",status:"completed",conclusion:"success",
        steps:[{name:(if $observation == "consumer-title-only" then "Verify workflow source and immutable PR metadata" else $tuple end),
          status:"completed",conclusion:"success"}]}]}
        | if $observation == "consumer-job-attempt-missing" then .jobs[0] |= del(.run_attempt)
          elif $observation == "consumer-steps-missing" then .jobs[0] |= del(.steps)
          else . end')" \
    FAKE_TRUSTED_CONSUMER_LATEST_JSON="$(jq -c --arg observation "$observation_case" \
      'if $observation == "consumer-stale-attempt" then .run_attempt = 2
       elif $observation | startswith("consumer-finished") then .status = "completed"
       else . end' <<<"$consumer_run")" \
    FAKE_ACTIVE_PREVIEW_RUN_PAGES_JSON="$(jq -cn \
      --arg observation "$observation_case" \
      --argjson consumer "$consumer_run" '
        [{workflow_runs:(
          if $observation == "duplicate-rows" then
            [$consumer, (5257, 5258 | $consumer + {id:., status:("queued", "in_progress")})]
          elif $observation == "lookup-overflow" then
            [range(5256; 5260) | $consumer + {id:.}]
          else
            [$consumer]
          end)}]')" \
    PREVIEW_MAX_ACTIVE=2 \
    bash "$RECONCILER_RUN"
) > "$reconciler_active_status_advance_output" 2>&1
if [[ "$observation_case" == duplicate-rows || "$observation_case" == workflow-run-only || "$observation_case" == consumer-job-attempt-missing ]]; then
  grep -Fqx \
    "Skipping proof retry for PR #901: preview source or trusted consumer run 5256 for ${preview_base_sha}/${preview_head_sha}/${preview_merge_sha} is already queued or in progress." \
    "$reconciler_active_status_advance_output"
  test ! -e "$FAKE_ANNOTATE_LOG"
  test ! -e "$FAKE_DISPATCH_LOG"
elif [[ "$observation_case" == consumer-stale-attempt || "$observation_case" == lookup-overflow || "$observation_case" == consumer-active-jobs-unavailable || "$observation_case" == consumer-job-attempt-mismatch || "$observation_case" == consumer-job-attempt-null || "$observation_case" == consumer-steps-missing ]]; then
  grep -Fqx \
    "Skipping proof retry for PR #901: active preview tuple lookup unavailable; retry budget unchanged." \
    "$reconciler_active_status_advance_output"
  test ! -e "$FAKE_ANNOTATE_LOG"
  test ! -e "$FAKE_RETRY_PATCH_LOG"
  test ! -e "$FAKE_DISPATCH_LOG"
elif [[ "$observation_case" == consumer-finished ]]; then
  grep -Fqx 'Skipping PR #901: proof completed before retry dispatch.' "$reconciler_active_status_advance_output"
  test "$(<"$FAKE_NAMESPACE_SNAPSHOT_CALLS")" -eq 2
  test -e "$FAKE_TRUSTED_CONSUMER_COMPLETED_MARKER"
  test ! -e "$FAKE_ANNOTATE_LOG"
  test ! -e "$FAKE_DISPATCH_LOG"
elif [[ "$observation_case" == consumer-finished-without-proof ]]; then
  grep -Fqx \
    "Recorded proof retry 1/3 for PR #901 (${preview_base_sha}/${preview_head_sha}/${preview_merge_sha}/${preview_image_tag})." \
    "$reconciler_active_status_advance_output"
  test -s "$FAKE_RETRY_PATCH_LOG"
  test -s "$FAKE_DISPATCH_LOG"
else
  grep -Fqx \
    "Dispatching preview render for PR #901 (${preview_base_sha}/${preview_head_sha}/${preview_merge_sha}/${preview_image_tag}) from trusted ref develop" \
    "$reconciler_active_status_advance_output"
  test -s "$FAKE_DISPATCH_LOG"
fi

done

for namespace_count_sequence in 2,1 2,2; do
  reset_case
  reconciler_capacity_refresh_output="$TEMP_DIR/reconciler-capacity-refresh-${namespace_count_sequence//,/-}.out"
  export FAKE_NAMESPACE_COUNT_SEQUENCE="$namespace_count_sequence"
  (
    cd "$ROOT_DIR"
    FAKE_OPEN_PRIORITY_ROWS="1\t901\thead-901\thuman\tdevelop\topen\t${ordinary_labels_base64}\n" \
      FAKE_PR_901_HEAD=head-901 \
      FAKE_PR_901_PRIORITY=false \
      FAKE_PR_901_NAMESPACE_ABSENT=true \
      PREVIEW_MAX_ACTIVE=2 \
      bash "$RECONCILER_RUN"
  ) > "$reconciler_capacity_refresh_output"
  if [[ "$namespace_count_sequence" == 2,1 ]]; then
    # A newly available slot proceeds through the final dispatch fence,
    # requiring an additional capacity sample while the Namespace is absent.
    test "$(<"$FAKE_NAMESPACE_LIST_CALLS")" -eq 3
    grep -Fq 'Dispatching preview render for PR #901' "$reconciler_capacity_refresh_output"
    test -s "$FAKE_DISPATCH_LOG"
  else
    test "$(<"$FAKE_NAMESPACE_LIST_CALLS")" -eq 2
    grep -Fq 'Skipping ordinary PR #901: preview capacity is still full after refresh.' \
      "$reconciler_capacity_refresh_output"
    test ! -e "$FAKE_DISPATCH_LOG"
  fi
done

for namespace_count_sequence in 2,1 2,2; do
  reset_case
  reconciler_capacity_refresh_output="$TEMP_DIR/reconciler-final-capacity-refresh-${namespace_count_sequence//,/-}.out"
  export FAKE_NAMESPACE_COUNT_SEQUENCE="$namespace_count_sequence"
  (
    cd "$ROOT_DIR"
    FAKE_OPEN_PRIORITY_ROWS="1\t901\thead-901\thuman\tdevelop\topen\t${ordinary_labels_base64}\n" \
      FAKE_PR_901_HEAD=head-901 \
      FAKE_PR_901_PRIORITY=false \
      FAKE_PR_901_SNAPSHOT_HEAD=dddddddddddddddddddddddddddddddddddddddd \
      FAKE_PR_901_NAMESPACE_ABSENT_ON_RECHECK=true \
      PREVIEW_MAX_ACTIVE=2 \
      bash "$RECONCILER_RUN"
  ) > "$reconciler_capacity_refresh_output"
  test "$(<"$FAKE_NAMESPACE_SNAPSHOT_CALLS")" -eq 2
  test "$(<"$FAKE_NAMESPACE_LIST_CALLS")" -eq 2
  test ! -e "$FAKE_ANNOTATE_LOG"
  if [[ "$namespace_count_sequence" == 2,1 ]]; then
    grep -Fq 'Dispatching preview render for PR #901' "$reconciler_capacity_refresh_output"
    test -s "$FAKE_DISPATCH_LOG"
  else
    grep -Fq 'Skipping ordinary PR #901: preview capacity is still full after refresh.' \
      "$reconciler_capacity_refresh_output"
    test ! -e "$FAKE_DISPATCH_LOG"
  fi
done

# Reservation-specific storage and interruption proof runs below with the
# durable holder, while capacity and provider inventory remain independent.
reset_case
reconciler_paginated_active_output="$TEMP_DIR/reconciler-paginated-active.out"
(
  cd "$ROOT_DIR"
  FAKE_OPEN_PRIORITY_ROWS="1\t901\thead-901\thuman\tdevelop\topen\t${adversarial_labels_base64}\n" \
    FAKE_PR_901_NAMESPACE_ABSENT=true \
    FAKE_ACTIVE_PREVIEW_RUN_PAGES_JSON="$(jq -cn \
      --arg title "Preview dispatch pr-901-base-${preview_base_sha}-head-${preview_head_sha}-merge-${preview_merge_sha}" \
      '[
        {workflow_runs: []},
        {workflow_runs: [{id: 4242, run_attempt: 1, workflow_id: 173511323, event: "repository_dispatch", path: ".github/workflows/preview.yml", name: $title, head_branch: "develop", head_sha: "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", repository: {full_name: "example/FireMUD"}, head_repository: {full_name: "example/FireMUD"}, display_title: $title, status: "queued"}]}
      ]')" \
PREVIEW_MAX_ACTIVE=2 \
    bash "$RECONCILER_RUN"
) > "$reconciler_paginated_active_output"
grep -Fqx \
  "Skipping proof retry for PR #901: preview source or trusted consumer run 4242 for ${preview_base_sha}/${preview_head_sha}/${preview_merge_sha} is already queued or in progress." \
  "$reconciler_paginated_active_output"
test ! -e "$FAKE_DISPATCH_LOG"

reset_case
reconciler_active_api_error_output="$TEMP_DIR/reconciler-active-api-error.out"
if (
  cd "$ROOT_DIR"
  FAKE_OPEN_PRIORITY_ROWS="1\t901\thead-901\thuman\tdevelop\topen\t${adversarial_labels_base64}\n" \
    FAKE_PR_901_NAMESPACE_ABSENT=true \
    FAKE_ACTIVE_PREVIEW_RUNS_API_ERROR=true \
PREVIEW_MAX_ACTIVE=2 \
    bash "$RECONCILER_RUN"
) > "$reconciler_active_api_error_output" 2>&1; then
  echo "reconciler dispatched after an active-run API error" >&2
  exit 1
fi
grep -Fqx \
  'Unable to inspect active preview workflow runs; refusing preview reconcile.' \
  "$reconciler_active_api_error_output"
test ! -e "$FAKE_DISPATCH_LOG"

reset_case
reconciler_active_api_malformed_output="$TEMP_DIR/reconciler-active-api-malformed.out"
if (
  cd "$ROOT_DIR"
  FAKE_OPEN_PRIORITY_ROWS="1\t901\thead-901\thuman\tdevelop\topen\t${adversarial_labels_base64}\n" \
    FAKE_PR_901_NAMESPACE_ABSENT=true \
    FAKE_ACTIVE_PREVIEW_RUNS_API_MALFORMED=true \
PREVIEW_MAX_ACTIVE=2 \
    bash "$RECONCILER_RUN"
) > "$reconciler_active_api_malformed_output" 2>&1; then
  echo "reconciler dispatched after malformed active-run API data" >&2
  exit 1
fi
grep -Fqx \
  'Unable to parse active preview workflow runs; refusing preview reconcile.' \
  "$reconciler_active_api_malformed_output"
test ! -e "$FAKE_DISPATCH_LOG"

for active_inventory_total in 1 1000; do
  reset_case
  inventory_output="$TEMP_DIR/reconciler-active-inventory-$active_inventory_total.out"
  if (
    cd "$ROOT_DIR"
    FAKE_OPEN_PRIORITY_ROWS="1\t901\thead-901\thuman\tdevelop\topen\t${adversarial_labels_base64}\n" \
      FAKE_PR_901_NAMESPACE_ABSENT=true \
      FAKE_ACTIVE_INVENTORY_TOTAL="$active_inventory_total" \
      PREVIEW_MAX_ACTIVE=2 bash "$RECONCILER_RUN"
  ) > "$inventory_output" 2>&1; then
    echo "reconciler accepted an incomplete or saturated active inventory" >&2
    exit 1
  fi
  grep -Fq 'active workflow run inventory is incomplete or saturated' "$inventory_output"
  test ! -e "$FAKE_DISPATCH_LOG"
done

reset_case
reconciler_stacked_output="$TEMP_DIR/reconciler-stacked.out"
(
  cd "$ROOT_DIR"
  FAKE_OPEN_PRIORITY_ROWS="1\t901\thead-901\thuman\tfeature/stack\topen\t${priority_labels_base64}\n" \
    FAKE_PR_901_BASE_REF=feature/stack \
    FAKE_PR_901_HEAD=head-901 \
    FAKE_PR_901_REQUESTED_HEAD=head-901 \
PREVIEW_MAX_ACTIVE=2 \
    bash "$RECONCILER_RUN"
) > "$reconciler_stacked_output"
grep -Fqx "Dispatching preview render for PR #901 (${preview_base_sha}/${preview_head_sha}/${preview_merge_sha}/${stacked_preview_image_tag}) from trusted ref develop" \
  "$reconciler_stacked_output"

reset_case
reconciler_empty_deployed_output="$TEMP_DIR/reconciler-empty-deployed.out"
(
  cd "$ROOT_DIR"
  FAKE_OPEN_PRIORITY_ROWS="1\t901\thead-901\thuman\tdevelop\topen\t${adversarial_labels_base64}\n" \
    FAKE_PR_901_HEAD='' \
    FAKE_PR_901_REQUESTED_HEAD=head-901 \
PREVIEW_MAX_ACTIVE=2 \
    bash "$RECONCILER_RUN"
) > "$reconciler_empty_deployed_output"
grep -Fqx "Dispatching preview render for PR #901 (${preview_base_sha}/${preview_head_sha}/${preview_merge_sha}/${preview_image_tag}) from trusted ref develop" \
  "$reconciler_empty_deployed_output"
test ! -e "$FAKE_ANNOTATE_LOG"
test "$(<"$FAKE_NAMESPACE_SNAPSHOT_CALLS")" -eq 2

reset_case
reconciler_namespace_absent_output="$TEMP_DIR/reconciler-namespace-absent.out"
(
  cd "$ROOT_DIR"
  FAKE_OPEN_PRIORITY_ROWS="1\t901\thead-901\thuman\tdevelop\topen\t${adversarial_labels_base64}\n" \
    FAKE_PR_901_HEAD=head-901 \
    FAKE_PR_901_NAMESPACE_ABSENT=true \
PREVIEW_MAX_ACTIVE=2 \
    bash "$RECONCILER_RUN"
) > "$reconciler_namespace_absent_output"
grep -Fqx "Dispatching preview render for PR #901 (${preview_base_sha}/${preview_head_sha}/${preview_merge_sha}/${preview_image_tag}) from trusted ref develop" \
  "$reconciler_namespace_absent_output"
test ! -e "$FAKE_ANNOTATE_LOG"
test "$(<"$FAKE_NAMESPACE_SNAPSHOT_CALLS")" -eq 2
grep -Fqx 'get namespace pr-901 --ignore-not-found -o json' "$FAKE_NAMESPACE_SNAPSHOT_LOG"

reset_case
reconciler_unvalidated_priority_output="$TEMP_DIR/reconciler-unvalidated-priority.out"
(
  cd "$ROOT_DIR"
  FAKE_OPEN_PRIORITY_ROWS="0\t901\thead-901\thuman\tdevelop\topen\t${adversarial_labels_base64}\n" \
    FAKE_PR_901_PRIORITY=false \
    FAKE_PR_901_NAMESPACE_ABSENT=true \
    PREVIEW_MAX_ACTIVE=1 \
    bash "$RECONCILER_RUN"
) > "$reconciler_unvalidated_priority_output"
grep -qx 'Skipping ordinary PR #901: preview capacity is still full after refresh.' \
  "$reconciler_unvalidated_priority_output"
test ! -e "$FAKE_DISPATCH_LOG"
test ! -e "$FAKE_RECONCILER_RESOLUTION_LOG"

reset_case
reconciler_validated_priority_output="$TEMP_DIR/reconciler-validated-priority.out"
(
  cd "$ROOT_DIR"
  FAKE_OPEN_PRIORITY_ROWS="1\t901\thead-901\thuman\tdevelop\topen\t${priority_labels_base64}\n" \
    FAKE_PR_901_NAMESPACE_ABSENT=true \
    PREVIEW_MAX_ACTIVE=1 \
    bash "$RECONCILER_RUN"
) > "$reconciler_validated_priority_output"
grep -Fqx "Dispatching preview render for PR #901 (${preview_base_sha}/${preview_head_sha}/${preview_merge_sha}/${preview_image_tag}) from trusted ref develop" \
  "$reconciler_validated_priority_output"
grep -Fq \
  'repos/example/FireMUD/dispatches -f event_type=preview-deploy' \
  "$FAKE_DISPATCH_LOG"

reset_case
reconciler_stale_active_run_output="$TEMP_DIR/reconciler-stale-active-run.out"
(
  cd "$ROOT_DIR"
  FAKE_OPEN_PRIORITY_ROWS="1\t901\thead-901\thuman\tdevelop\topen\t${adversarial_labels_base64}\n" \
    FAKE_PR_901_NAMESPACE_ABSENT=true \
    FAKE_ACTIVE_PREVIEW_RUNS_JSON='[{"databaseId":41,"status":"in_progress","displayTitle":"Preview dispatch pr-901-base-bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb-head-deadbeef-merge-cccccccccccccccccccccccccccccccccccccccc"}]' \
PREVIEW_MAX_ACTIVE=2 \
    bash "$RECONCILER_RUN"
) > "$reconciler_stale_active_run_output"
grep -Fqx "Dispatching preview render for PR #901 (${preview_base_sha}/${preview_head_sha}/${preview_merge_sha}/${preview_image_tag}) from trusted ref develop" \
  "$reconciler_stale_active_run_output"
grep -Fq \
  'repos/example/FireMUD/dispatches -f event_type=preview-deploy' \
  "$FAKE_DISPATCH_LOG"

reset_case
reconciler_current_active_run_output="$TEMP_DIR/reconciler-current-active-run.out"
(
  cd "$ROOT_DIR"
  FAKE_OPEN_PRIORITY_ROWS="1\t901\thead-901\thuman\tdevelop\topen\t${adversarial_labels_base64}\n" \
    FAKE_PR_901_NAMESPACE_ABSENT=true \
    FAKE_ACTIVE_PREVIEW_RUNS_JSON='[{"databaseId":42,"status":"queued","displayTitle":"Preview dispatch pr-901-base-bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb-head-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa-merge-cccccccccccccccccccccccccccccccccccccccc"}]' \
PREVIEW_MAX_ACTIVE=2 \
    bash "$RECONCILER_RUN"
) > "$reconciler_current_active_run_output"
grep -qx \
  "Skipping proof retry for PR #901: preview source or trusted consumer run 42 for ${preview_base_sha}/${preview_head_sha}/${preview_merge_sha} is already queued or in progress." \
  "$reconciler_current_active_run_output"
test ! -e "$FAKE_DISPATCH_LOG"

reset_case
reconciler_namespace_absent_on_recheck_output="$TEMP_DIR/reconciler-namespace-absent-on-recheck.out"
(
  cd "$ROOT_DIR"
  FAKE_OPEN_PRIORITY_ROWS="1\t901\thead-901\thuman\tdevelop\topen\t${adversarial_labels_base64}\n" \
    FAKE_PR_901_HEAD=head-901 \
    FAKE_PR_901_REQUESTED_HEAD='' \
    FAKE_PR_901_NAMESPACE_ABSENT_ON_RECHECK=true \
PREVIEW_MAX_ACTIVE=2 \
    bash "$RECONCILER_RUN"
) > "$reconciler_namespace_absent_on_recheck_output"
grep -Fqx "Dispatching preview render for PR #901 (${preview_base_sha}/${preview_head_sha}/${preview_merge_sha}/${preview_image_tag}) from trusted ref develop" \
  "$reconciler_namespace_absent_on_recheck_output"
test ! -e "$FAKE_ANNOTATE_LOG"
test "$(<"$FAKE_NAMESPACE_SNAPSHOT_CALLS")" -eq 3
test "$(grep -Fc 'get namespace pr-901 --ignore-not-found -o json' "$FAKE_NAMESPACE_SNAPSHOT_LOG")" -eq 3

reset_case
reconciler_namespace_error_output="$TEMP_DIR/reconciler-namespace-error.out"
if (
  cd "$ROOT_DIR"
  FAKE_OPEN_PRIORITY_ROWS="1\t901\thead-901\thuman\tdevelop\topen\t${adversarial_labels_base64}\n" \
    FAKE_PR_901_HEAD=head-901 \
    FAKE_NAMESPACE_SNAPSHOT_ERROR=true \
PREVIEW_MAX_ACTIVE=2 \
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
  FAKE_OPEN_PRIORITY_ROWS="0\t901\thead-901\thuman\tdevelop\topen\t${priority_labels_base64}\n1\t101\tnew-head-101\thuman\tdevelop\topen\t${adversarial_labels_base64}\n" \
    FAKE_NAMESPACE_SNAPSHOT_PARSE_FAIL=true \
PREVIEW_MAX_ACTIVE=2 \
    bash "$RECONCILER_RUN"
) > "$reconciler_namespace_parse_output" 2>&1
grep -qx 'Skipping PR #901: unable to parse namespace pr-901 snapshot.' \
  "$reconciler_namespace_parse_output"
grep -Fqx "Dispatching preview render for PR #101 (${preview_base_sha}/${preview_head_sha}/${preview_merge_sha}/${preview_image_tag}) from trusted ref develop" \
  "$reconciler_namespace_parse_output"
grep -Fq \
  'repos/example/FireMUD/dispatches -f event_type=preview-deploy' \
  "$FAKE_DISPATCH_LOG"

reset_case
reconciler_namespace_recheck_parse_output="$TEMP_DIR/reconciler-namespace-recheck-parse.out"
(
  cd "$ROOT_DIR"
  FAKE_OPEN_PRIORITY_ROWS="0\t901\thead-901\thuman\tdevelop\topen\t${priority_labels_base64}\n1\t101\tnew-head-101\thuman\tdevelop\topen\t${adversarial_labels_base64}\n" \
    FAKE_PR_901_HEAD=head-901 \
    FAKE_PR_901_REQUESTED_HEAD='' \
    FAKE_NAMESPACE_SNAPSHOT_RECHECK_PARSE_FAIL=true \
PREVIEW_MAX_ACTIVE=2 \
    bash "$RECONCILER_RUN"
) > "$reconciler_namespace_recheck_parse_output" 2>&1
grep -qx 'Skipping PR #901: unable to parse namespace pr-901 recheck.' \
  "$reconciler_namespace_recheck_parse_output"
grep -Fqx "Dispatching preview render for PR #101 (${preview_base_sha}/${preview_head_sha}/${preview_merge_sha}/${preview_image_tag}) from trusted ref develop" \
  "$reconciler_namespace_recheck_parse_output"
grep -Fq \
  'repos/example/FireMUD/dispatches -f event_type=preview-deploy' \
  "$FAKE_DISPATCH_LOG"

reset_case
reconciler_missing_requested_output="$TEMP_DIR/reconciler-missing-requested.out"
(
  cd "$ROOT_DIR"
  FAKE_OPEN_PRIORITY_ROWS="1\t901\thead-901\thuman\tdevelop\topen\t${adversarial_labels_base64}\n" \
    FAKE_PR_901_HEAD=head-901 \
    FAKE_PR_901_REQUESTED_HEAD='' \
PREVIEW_MAX_ACTIVE=2 \
    bash "$RECONCILER_RUN"
) > "$reconciler_missing_requested_output"
grep -qx 'Repaired missing requested preview tuple for pr-901' \
  "$reconciler_missing_requested_output"
grep -Fqx \
  "annotate namespace pr-901 firemud.dev/requested-preview-base-sha=${preview_base_sha} firemud.dev/requested-preview-head-sha=${preview_head_sha} firemud.dev/requested-preview-merge-sha=${preview_merge_sha} firemud.dev/requested-preview-image-tag=${preview_image_tag} --overwrite --resource-version rv-901" \
  "$FAKE_ANNOTATE_LOG"
test "$(<"$FAKE_NAMESPACE_SNAPSHOT_CALLS")" -eq 2
test "$(grep -Fc 'get namespace pr-901 --ignore-not-found -o json' "$FAKE_NAMESPACE_SNAPSHOT_LOG")" -eq 2
test ! -e "$FAKE_DISPATCH_LOG"

reset_case
reconciler_stale_requested_output="$TEMP_DIR/reconciler-stale-requested.out"
(
  cd "$ROOT_DIR"
  FAKE_OPEN_PRIORITY_ROWS="1\t901\thead-901\thuman\tdevelop\topen\t${adversarial_labels_base64}\n" \
    FAKE_PR_901_HEAD=head-901 \
    FAKE_PR_901_REQUESTED_HEAD=stale-head \
PREVIEW_MAX_ACTIVE=2 \
    bash "$RECONCILER_RUN"
) > "$reconciler_stale_requested_output"
grep -qx 'Repaired stale requested preview tuple for pr-901' \
  "$reconciler_stale_requested_output"
grep -Fqx \
  "annotate namespace pr-901 firemud.dev/requested-preview-base-sha=${preview_base_sha} firemud.dev/requested-preview-head-sha=${preview_head_sha} firemud.dev/requested-preview-merge-sha=${preview_merge_sha} firemud.dev/requested-preview-image-tag=${preview_image_tag} --overwrite --resource-version rv-901" \
  "$FAKE_ANNOTATE_LOG"
test "$(<"$FAKE_NAMESPACE_SNAPSHOT_CALLS")" -eq 2
test "$(grep -Fc 'get namespace pr-901 --ignore-not-found -o json' "$FAKE_NAMESPACE_SNAPSHOT_LOG")" -eq 2
test ! -e "$FAKE_DISPATCH_LOG"

reset_case
reconciler_annotation_deleted_output="$TEMP_DIR/reconciler-annotation-deleted.out"
(
  cd "$ROOT_DIR"
  FAKE_OPEN_PRIORITY_ROWS="1\t901\thead-901\thuman\tdevelop\topen\t${adversarial_labels_base64}\n" \
    FAKE_PR_901_HEAD=head-901 \
    FAKE_PR_901_REQUESTED_HEAD='' \
    FAKE_ANNOTATE_ERROR=true \
    FAKE_ANNOTATE_NAMESPACE_ABSENT_AFTER_FAILURE=true \
PREVIEW_MAX_ACTIVE=2 \
    bash "$RECONCILER_RUN"
) > "$reconciler_annotation_deleted_output"
grep -qx 'Namespace pr-901 was deleted during requested-head repair; dispatching replacement.' \
  "$reconciler_annotation_deleted_output"
grep -Fqx \
  "annotate namespace pr-901 firemud.dev/requested-preview-base-sha=${preview_base_sha} firemud.dev/requested-preview-head-sha=${preview_head_sha} firemud.dev/requested-preview-merge-sha=${preview_merge_sha} firemud.dev/requested-preview-image-tag=${preview_image_tag} --overwrite --resource-version rv-901" \
  "$FAKE_ANNOTATE_LOG"
test "$(<"$FAKE_NAMESPACE_SNAPSHOT_CALLS")" -eq 4
grep -Fq \
  'repos/example/FireMUD/dispatches -f event_type=preview-deploy' \
  "$FAKE_DISPATCH_LOG"

reset_case
reconciler_annotation_existing_output="$TEMP_DIR/reconciler-annotation-existing.out"
(
  cd "$ROOT_DIR"
  FAKE_OPEN_PRIORITY_ROWS="0\t901\thead-901\thuman\tdevelop\topen\t${priority_labels_base64}\n1\t101\tnew-head-101\thuman\tdevelop\topen\t${adversarial_labels_base64}\n" \
    FAKE_PR_901_HEAD=head-901 \
    FAKE_PR_901_REQUESTED_HEAD='' \
    FAKE_ANNOTATE_ERROR=true \
PREVIEW_MAX_ACTIVE=2 \
    bash "$RECONCILER_RUN"
) > "$reconciler_annotation_existing_output" 2>&1
grep -qx \
  'Skipping PR #901: unable to annotate namespace pr-901; namespace still exists.' \
  "$reconciler_annotation_existing_output"
grep -Fqx "Dispatching preview render for PR #101 (${preview_base_sha}/${preview_head_sha}/${preview_merge_sha}/${preview_image_tag}) from trusted ref develop" \
  "$reconciler_annotation_existing_output"
grep -Fq \
  'repos/example/FireMUD/dispatches -f event_type=preview-deploy' \
  "$FAKE_DISPATCH_LOG"
test "$(<"$FAKE_NAMESPACE_SNAPSHOT_CALLS")" -eq 3

reset_case
reconciler_annotation_confirmation_error_output="$TEMP_DIR/reconciler-annotation-confirmation-error.out"
if (
  cd "$ROOT_DIR"
  FAKE_OPEN_PRIORITY_ROWS="1\t901\thead-901\thuman\tdevelop\topen\t${adversarial_labels_base64}\n" \
    FAKE_PR_901_HEAD=head-901 \
    FAKE_PR_901_REQUESTED_HEAD='' \
    FAKE_ANNOTATE_ERROR=true \
    FAKE_ANNOTATE_CONFIRMATION_ERROR=true \
PREVIEW_MAX_ACTIVE=2 \
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
  FAKE_OPEN_PRIORITY_ROWS="1\t901\thead-901\thuman\tdevelop\topen\t${adversarial_labels_base64}\n" \
    FAKE_PR_901_HEAD=head-901 \
    FAKE_PR_901_REQUESTED_HEAD='' \
    FAKE_NAMESPACE_SNAPSHOT_RECHECK_ERROR=true \
PREVIEW_MAX_ACTIVE=2 \
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
  FAKE_OPEN_PRIORITY_ROWS="1\t901\thead-901\thuman\tdevelop\topen\t${adversarial_labels_base64}\n" \
    FAKE_PR_901_HEAD=head-901 \
    FAKE_PR_901_REQUESTED_HEAD='' \
    FAKE_PR_901_RECHECK_REQUESTED_HEAD=raced-head \
PREVIEW_MAX_ACTIVE=2 \
    bash "$RECONCILER_RUN"
) > "$reconciler_changed_requested_output"
grep -qx \
  'Skipping preview repair for PR #901: requested preview tuple changed during annotation repair check.' \
  "$reconciler_changed_requested_output"
test ! -e "$FAKE_ANNOTATE_LOG"
test ! -e "$FAKE_DISPATCH_LOG"
test "$(<"$FAKE_NAMESPACE_SNAPSHOT_CALLS")" -eq 2

reset_case
reconciler_changed_stale_requested_output="$TEMP_DIR/reconciler-changed-stale-requested.out"
(
  cd "$ROOT_DIR"
  FAKE_OPEN_PRIORITY_ROWS="1\t901\thead-901\thuman\tdevelop\topen\t${adversarial_labels_base64}\n" \
    FAKE_PR_901_HEAD=head-901 \
    FAKE_PR_901_REQUESTED_HEAD=stale-head \
    FAKE_PR_901_RECHECK_REQUESTED_HEAD=other-stale-head \
PREVIEW_MAX_ACTIVE=2 \
    bash "$RECONCILER_RUN"
) > "$reconciler_changed_stale_requested_output"
grep -qx \
  'Skipping preview repair for PR #901: requested preview tuple changed during annotation repair check.' \
  "$reconciler_changed_stale_requested_output"
test ! -e "$FAKE_ANNOTATE_LOG"
test ! -e "$FAKE_DISPATCH_LOG"
test "$(<"$FAKE_NAMESPACE_SNAPSHOT_CALLS")" -eq 2

reset_case
reconciler_changed_deployed_output="$TEMP_DIR/reconciler-changed-deployed.out"
(
  cd "$ROOT_DIR"
  FAKE_OPEN_PRIORITY_ROWS="1\t901\thead-901\thuman\tdevelop\topen\t${adversarial_labels_base64}\n" \
    FAKE_PR_901_HEAD=head-901 \
    FAKE_PR_901_REQUESTED_HEAD='' \
    FAKE_PR_901_RECHECK_HEAD=changed-head \
PREVIEW_MAX_ACTIVE=2 \
    bash "$RECONCILER_RUN"
) > "$reconciler_changed_deployed_output"
grep -qx \
  'Skipping preview repair for PR #901: deployed preview tuple changed during annotation repair check.' \
  "$reconciler_changed_deployed_output"
test ! -e "$FAKE_ANNOTATE_LOG"
test ! -e "$FAKE_DISPATCH_LOG"
test "$(<"$FAKE_NAMESPACE_SNAPSHOT_CALLS")" -eq 2

reset_case
reconciler_recreated_output="$TEMP_DIR/reconciler-recreated-during-repair.out"
(
  cd "$ROOT_DIR"
  FAKE_OPEN_PRIORITY_ROWS="1\t901\thead-901\thuman\tdevelop\topen\t${adversarial_labels_base64}\n" \
    FAKE_PR_901_HEAD=head-901 FAKE_PR_901_REQUESTED_HEAD='' \
    FAKE_PR_901_NAMESPACE_UID=uid-observed FAKE_PR_901_RECHECK_NAMESPACE_UID=uid-recreated \
    FAKE_PR_901_PROOF_REMOVED_ON_RECHECK=true PREVIEW_MAX_ACTIVE=2 \
    bash "$RECONCILER_RUN"
) > "$reconciler_recreated_output"
grep -Fqx 'Skipping preview repair for PR #901: namespace UID changed during annotation repair check.' \
  "$reconciler_recreated_output"
test ! -e "$FAKE_ANNOTATE_LOG"
test ! -e "$FAKE_DISPATCH_LOG"

reset_case
malformed_labels_base64="$(printf '%s' '{}' | base64 | tr -d '\n')"
reconciler_malformed_output="$TEMP_DIR/reconciler-malformed.out"
(
  cd "$ROOT_DIR"
  FAKE_OPEN_PRIORITY_ROWS="1\t901\thead-901\thuman\tdevelop\topen\t${malformed_labels_base64}\n" \
PREVIEW_MAX_ACTIVE=2 \
    bash "$RECONCILER_RUN"
) > "$reconciler_malformed_output" 2>&1
grep -qx 'Skipping PR #901: label metadata could not be decoded as a JSON array.' \
  "$reconciler_malformed_output"
if grep -q '^Dispatching preview deploy' "$reconciler_malformed_output"; then
  echo "reconciler dispatched after malformed label metadata" >&2
  exit 1
fi

janitor_workflow="$ROOT_DIR/.github/workflows/preview-janitor.yml"
TRUSTED_WORKFLOW="$trusted_workflow" JANITOR_WORKFLOW="$janitor_workflow" python3 - <<'PY'
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
    "prepare-runtime": "needs.validate-target.outputs.action == 'deploy'",
    "deploy-runtime": "needs.validate-target.outputs.action == 'deploy'",
    "verify-runtime": "needs.validate-target.outputs.action == 'deploy'",
    "publish-preview-proof": "needs.validate-target.outputs.action == 'deploy'",
    "destroy-runtime": "needs.validate-target.outputs.action == 'destroy'",
    "retire-identity": "needs.validate-target.outputs.action == 'destroy'",
}
assert set(jobs) == {"validate-target", *expected_gates}, jobs.keys()
for job_name, required_gate in expected_gates.items():
    condition = jobs[job_name].get("if", "")
    assert required_gate in condition, (job_name, condition)

janitor = yaml.safe_load(
    Path(os.environ["JANITOR_WORKFLOW"]).read_text(encoding="utf-8")
)
janitor_job = janitor["jobs"]["prune-stale-preview-namespaces"]
assert janitor_job["timeout-minutes"] == 60
janitor_steps = janitor_job["steps"]
requester_check = next(
    step for step in janitor_steps if step.get("id") == "requester-credentials"
)
assert requester_check["if"] == (
    "${{ steps.certificate-identity.outputs.mode == 'hosted-controller' }}"
)
assert "available=false" in requester_check["run"]
assert "available=true" in requester_check["run"]
requester_write = next(
    step for step in janitor_steps if step.get("name") == "Write hosted identity requester kubeconfig"
)
assert requester_write["if"] == (
    "${{ steps.certificate-identity.outputs.mode == 'hosted-controller' && "
    "steps.requester-credentials.outputs.available == 'true' }}"
)
prune_step = next(
    step for step in janitor_steps if step.get("name") == "Prune stale preview namespaces"
)
prune_run = prune_step["run"]
assert "HOSTED_IDENTITY_REQUESTER_CREDENTIALS_AVAILABLE" in prune_step["env"]
assert "--retire-terminal-identities" in prune_run
assert "prune-stale-preview-namespaces.sh --apply" in prune_run
assert "HOSTED_IDENTITY_REQUESTER_CREDENTIALS_AVAILABLE\" == true" in prune_run


def logical_commands(run):
    commands = []
    continuation = ""
    for raw_line in run.splitlines():
        line = raw_line.strip()
        if not line:
            continue
        if line.endswith("\\"):
            continuation += line[:-1].rstrip() + " "
            continue
        commands.append((continuation + line).strip())
        continuation = ""
    assert not continuation, continuation
    return commands


revalidation_helper = (
    "bash ./dev-tools/hosted/preview/revalidate-preview-deploy.sh"
)
deploy_revalidation = (
    f'{revalidation_helper} "$PR_NUMBER" "$EXPECTED_HEAD_SHA"'
)
cleanup_revalidation = (
    f'{revalidation_helper} --cleanup "$PR_NUMBER" "$EXPECTED_HEAD_SHA"'
)
open_cleanup_revalidation = (
    f'{revalidation_helper} --open-cleanup "$PR_NUMBER" "$EXPECTED_HEAD_SHA"'
)
actual_revalidations = {}
for job_name, job in jobs.items():
    for step in job.get("steps", []):
        run = step.get("run")
        if not isinstance(run, str):
            continue
        calls = [
            command
            for command in logical_commands(run)
            if command.startswith(revalidation_helper)
        ]
        if calls:
            key = (job_name, step.get("name"))
            assert key not in actual_revalidations, key
            actual_revalidations[key] = calls
assert {job_name for job_name, _ in actual_revalidations} == set(expected_gates)
for (job_name, step_name), calls in actual_revalidations.items():
    expected_calls = (
        {cleanup_revalidation, open_cleanup_revalidation}
        if job_name == "destroy-runtime"
        else {cleanup_revalidation}
        if job_name == "retire-identity"
        else {deploy_revalidation}
    )
    assert set(calls) == expected_calls, (
        job_name,
        step_name,
        calls,
    )
PY

# The hosted-identity controller contract exercises the trusted target's source,
# artifact, stale-head, and malformed-label paths. This priority contract checks
# the lifecycle gate and revalidation placement without duplicating that harness.
revalidation_helper="$ROOT_DIR/dev-tools/hosted/preview/revalidate-preview-deploy.sh"
test "$(grep -Fc -- 'revalidate-preview-deploy.sh' "$ALLOCATOR")" -eq 1
# shellcheck disable=SC2016 # Assert literal helper mode selection.
grep -Fq -- 'revalidation_mode="--revalidate-${mode}"' "$revalidation_helper"
# shellcheck disable=SC2016 # Assert literal evaluator argument forwarding.
grep -Fq -- '"$revalidation_mode"' "$revalidation_helper"
# Eligibility-only deploy revalidation trusts the live branch ref rather than
# the pull-request API's potentially lagging base.sha field. The source-binding
# helper remains responsible for exact tuple equality.
reset_case
export FAKE_TARGET_BASE_SHA=cccccccccccccccccccccccccccccccccccccccc
if ! (
  cd "$ROOT_DIR"
  PATH="$TEMP_DIR/bin:$PATH" GH_TOKEN=fake GITHUB_REPOSITORY=example/FireMUD \
    bash "$revalidation_helper" 900 "$FAKE_TARGET_HEAD"
); then
  echo "deploy revalidation rejected a lagging pull-request base SHA" >&2
  exit 1
fi

reset_case
export FAKE_TARGET_BASE_SHA=cccccccccccccccccccccccccccccccccccccccc
export FAKE_TARGET_MERGEABILITY_SEQUENCE='unknown,clean'
export PREVIEW_METADATA_RETRY_DELAY_SECONDS=0
if ! (
  cd "$ROOT_DIR"
  PATH="$TEMP_DIR/bin:$PATH" GH_TOKEN=fake GITHUB_REPOSITORY=example/FireMUD \
    bash "$revalidation_helper" 900 "$FAKE_TARGET_HEAD"
); then
  echo "deploy revalidation rejected a transient unknown mergeability state" >&2
  exit 1
fi
test "$(<"$FAKE_TARGET_METADATA_CALLS")" -eq 2

reset_case
export FAKE_TARGET_BASE_SHA=cccccccccccccccccccccccccccccccccccccccc
export FAKE_TARGET_MERGEABILITY_SEQUENCE='unknown,unknown,unknown'
export PREVIEW_METADATA_RETRY_ATTEMPTS=3
export PREVIEW_METADATA_RETRY_DELAY_SECONDS=0
if (
  cd "$ROOT_DIR"
  PATH="$TEMP_DIR/bin:$PATH" GH_TOKEN=fake GITHUB_REPOSITORY=example/FireMUD \
    bash "$revalidation_helper" 900 "$FAKE_TARGET_HEAD"
) >"$TEMP_DIR/revalidate-mergeability-exhausted.out" 2>&1; then
  echo "deploy revalidation accepted exhausted unknown mergeability" >&2
  exit 1
fi
test "$(<"$FAKE_TARGET_METADATA_CALLS")" -eq 4
grep -Fq 'current pull request is not mergeable' \
  "$TEMP_DIR/revalidate-mergeability-exhausted.out"
unset PREVIEW_METADATA_RETRY_ATTEMPTS PREVIEW_METADATA_RETRY_DELAY_SECONDS

source_revalidation_helper="$ROOT_DIR/dev-tools/hosted/preview/revalidate-preview-source-binding.sh"
reset_case
export FAKE_TARGET_MERGEABILITY_SEQUENCE='unknown,clean'
export PREVIEW_METADATA_RETRY_DELAY_SECONDS=0
if ! (
  cd "$ROOT_DIR"
  PATH="$TEMP_DIR/bin:$PATH" GH_TOKEN=fake GITHUB_REPOSITORY=example/FireMUD \
    bash "$source_revalidation_helper" 900 \
      "$FAKE_TARGET_HEAD" "$FAKE_TARGET_BASE_SHA" "$FAKE_TARGET_MERGE_SHA"
); then
  echo "source binding rejected a transient unknown mergeability state" >&2
  exit 1
fi
test "$(<"$FAKE_TARGET_METADATA_CALLS")" -eq 2

reset_case
export FAKE_TARGET_MERGEABILITY_SEQUENCE='unknown,unknown,unknown'
export PREVIEW_METADATA_RETRY_ATTEMPTS=3
export PREVIEW_METADATA_RETRY_DELAY_SECONDS=0
if (
  cd "$ROOT_DIR"
  PATH="$TEMP_DIR/bin:$PATH" GH_TOKEN=fake GITHUB_REPOSITORY=example/FireMUD \
    bash "$source_revalidation_helper" 900 \
      "$FAKE_TARGET_HEAD" "$FAKE_TARGET_BASE_SHA" "$FAKE_TARGET_MERGE_SHA"
) >"$TEMP_DIR/source-revalidate-mergeability-exhausted.out" 2>&1; then
  echo "source binding accepted exhausted unknown mergeability" >&2
  exit 1
fi
test "$(<"$FAKE_TARGET_METADATA_CALLS")" -eq 4
grep -Fq 'current PR head, base, merge, repository, or mergeability' \
  "$TEMP_DIR/source-revalidate-mergeability-exhausted.out"
unset PREVIEW_METADATA_RETRY_ATTEMPTS PREVIEW_METADATA_RETRY_DELAY_SECONDS

reset_case
export FAKE_PRUNE_BASE_REF_SHA=malformed
if (
  cd "$ROOT_DIR"
  PATH="$TEMP_DIR/bin:$PATH" GH_TOKEN=fake GITHUB_REPOSITORY=example/FireMUD \
    bash "$revalidation_helper" 900 "$FAKE_TARGET_HEAD"
) >"$TEMP_DIR/revalidate-malformed-live-base.out" 2>&1; then
  echo "deploy revalidation accepted a malformed live base SHA" >&2
  exit 1
fi
grep -Fq 'current base branch ref SHA is not canonical' \
  "$TEMP_DIR/revalidate-malformed-live-base.out"
# shellcheck disable=SC2016 # Assert literal shell source in the revalidation helper.
grep -Fq -- '--expected-repository "$GITHUB_REPOSITORY"' "$revalidation_helper"
# shellcheck disable=SC2016 # Assert literal shell source in the revalidation helper.
grep -Fq -- '--expected-head-sha "$expected_head_sha"' "$revalidation_helper"
# shellcheck disable=SC2016 # This assertion intentionally matches literal shell source.
grep -q -- '--batch-deploy-candidates' "$ROOT_DIR/dev-tools/hosted/preview/allocate-preview-capacity.sh"
# shellcheck disable=SC2016 # This assertion intentionally matches literal shell source.
grep -q -- '--expected-repository "$GITHUB_REPOSITORY"' "$ROOT_DIR/dev-tools/hosted/preview/allocate-preview-capacity.sh"
# Keep typed mergeability extraction rooted at each field rather than the full
# pull-request object; malformed values must remain fail-closed.
grep -Fq \
  'if (.mergeable | type) == "boolean" then (.mergeable | tostring) else "invalid" end' \
  "$ALLOCATOR"
grep -Fq \
  'if (.mergeable_state | type) == "string" then .mergeable_state else "invalid" end' \
  "$ALLOCATOR"
ALLOCATOR_PATH="$ROOT_DIR/dev-tools/hosted/preview/allocate-preview-capacity.sh" \
  ELIGIBILITY_PATH="$eligibility_script" python3 - <<'PY'
import ast
import os
import re
from pathlib import Path

source = Path(os.environ["ALLOCATOR_PATH"]).read_text(encoding="utf-8")
eligibility_source = Path(os.environ["ELIGIBILITY_PATH"]).read_text(encoding="utf-8")
candidate_limit_match = re.search(
    r"^  local max_open_pr_candidates=([0-9]+)$", source, re.MULTILINE
)
assert candidate_limit_match is not None
allocator_candidate_limit = int(candidate_limit_match.group(1))
page_size_match = re.search(
    r"^  local open_pr_page_size=([0-9]+)$", source, re.MULTILINE
)
assert page_size_match is not None
open_pr_page_size = int(page_size_match.group(1))
eligibility_tree = ast.parse(eligibility_source)
eligibility_candidate_limits = [
    node.value.value
    for node in eligibility_tree.body
    if isinstance(node, ast.Assign)
    and any(
        isinstance(target, ast.Name) and target.id == "MAX_PRIORITY_CANDIDATES"
        for target in node.targets
    )
    and isinstance(node.value, ast.Constant)
    and isinstance(node.value.value, int)
]
assert eligibility_candidate_limits == [1000]
assert allocator_candidate_limit == eligibility_candidate_limits[0]
assert open_pr_page_size == 100
assert allocator_candidate_limit > 0 and open_pr_page_size > 0
assert allocator_candidate_limit % open_pr_page_size == 0
assert allocator_candidate_limit // open_pr_page_size == 10
assert allocator_candidate_limit // open_pr_page_size + 1 == 11
start = source.index("find_unsatisfied_priority_pr()")
end = source.index("\n# Fail closed", start)
body = source[start:end]
assert "inspect_labels" not in body
assert "--batch-deploy-candidates" in body
assert body.count('python3 "$eligibility_script"') == 1
assert "--operation deploy" not in body
assert "open_pr_page_size=100" in body
assert "max_open_pr_candidates <= 0 || open_pr_page_size <= 0" in body
assert "max_open_pr_candidates % open_pr_page_size != 0" in body
assert "max_open_pr_pages=$((max_open_pr_candidates / open_pr_page_size))" in body
assert "overflow_page=$((max_open_pr_pages + 1))" in body
assert (
    'per_page=${open_pr_page_size}&page=${page}' in body
)
assert (
    'per_page=${open_pr_page_size}&page=${overflow_page}' in body
)
assert 'candidate limit exceeded' in body
assert "--paginate" not in body
PY

if printf '%s\n' "901"$'\t'"${priority_candidate_head}"$'\t'"example/FireMUD"$'\t'"human"$'\t'"develop"$'\t'"open"$'\t'"${priority_labels_base64}" |
  python3 "$eligibility_script" --batch-deploy-candidates --expected-repository '' \
  >"$TEMP_DIR/empty-expected-repository.output" 2>"$TEMP_DIR/empty-expected-repository.error"; then
  echo "batch candidate evaluation accepted an empty expected repository" >&2
  exit 1
fi
grep -Fxq 'expected repository is required' "$TEMP_DIR/empty-expected-repository.error"

# The fake API reuses these 100 rows across 10 mock pages to reach the configured
# 1,000-candidate limit, then uses a one-record overflow probe to reject record 1001.
priority_limit_rows=""
for _ in $(seq 1 100); do
  priority_limit_rows+="901"$'\t'"${priority_candidate_head}"$'\texample/FireMUD\thuman\tdevelop\topen\t'"${priority_labels_base64}"$'\n'
done
reset_case
export FAKE_TARGET_PRIORITY=false
export FAKE_OPEN_PRIORITY_ROWS="$priority_limit_rows"
export FAKE_PRIORITY_CANDIDATE_NAMESPACE_ABSENT=true
if run_allocator pr-900 2 900 "$FAKE_TARGET_HEAD" \
  >"$TEMP_DIR/priority-limit.output" 2>&1; then
  echo "ordinary allocation unexpectedly succeeded with an unsatisfied priority PR" >&2
  exit 1
fi
if grep -Fq 'candidate limit exceeded' "$TEMP_DIR/priority-limit.output"; then
  echo "exactly max_open_pr_candidates was rejected" >&2
  exit 1
fi
grep -Fq 'Yielding ordinary PR #900' "$TEMP_DIR/priority-limit.output"
test "$(wc -l < "$FAKE_PRIORITY_QUERY_LOG")" -eq 11
grep -Fxq \
  'repos/example/FireMUD/pulls?state=open&per_page=100&page=11' \
  "$FAKE_PRIORITY_QUERY_LOG"

reset_case
export FAKE_TARGET_PRIORITY=false
export FAKE_OPEN_PRIORITY_ROWS="$priority_limit_rows"
export FAKE_PRIORITY_OVERFLOW=true
if run_allocator pr-900 2 900 "$FAKE_TARGET_HEAD" \
  >"$TEMP_DIR/priority-overflow.output" 2>&1; then
  echo "allocation unexpectedly succeeded with priority candidate 1001" >&2
  exit 1
fi
grep -Fq 'candidate limit exceeded' "$TEMP_DIR/priority-overflow.output"
test "$(wc -l < "$FAKE_PRIORITY_QUERY_LOG")" -eq 11
grep -Fxq \
  'repos/example/FireMUD/pulls?state=open&per_page=100&page=11' \
  "$FAKE_PRIORITY_QUERY_LOG"

grep -q -- '--operation retain' "$ROOT_DIR/dev-tools/hosted/preview/prune-stale-preview-namespaces.sh"
grep -Fq '(.labels | tojson | @base64)' "$ROOT_DIR/dev-tools/hosted/preview/prune-stale-preview-namespaces.sh"
grep -q -- "--labels-json \"\$pr_labels_json\"" "$ROOT_DIR/dev-tools/hosted/preview/prune-stale-preview-namespaces.sh"
PRUNER_PATH="$ROOT_DIR/dev-tools/hosted/preview/prune-stale-preview-namespaces.sh" python3 - <<'PY'
import os
from pathlib import Path

source = Path(os.environ["PRUNER_PATH"]).read_text(encoding="utf-8")
timeout_error = 'PREVIEW_DELETE_TIMEOUT must be an integer between 1 and 3600'
assert source.count(timeout_error) == 1
assert source.index(timeout_error) < source.index(
    'if [[ "${1:-}" == "--delete-runtime" ]]'
)
assert source.count(
    'PREVIEW_NAMESPACE_DELETE_TIMEOUT_SECONDS="$preview_delete_timeout"'
) == 2
assert 'bash "$identity_wait_script" --retired "$identity_name" "$preview_delete_timeout"' in source
assert '--ignore-not-found --wait=true --timeout="${preview_delete_timeout}s"' in source
PY
grep -Fq "printf 'identity=%s\\nphase=Retired\\n' \"\$identity_name\"" \
  "$ROOT_DIR/dev-tools/hosted/preview/wait-for-hosted-identity.sh"
# shellcheck disable=SC2016 # Assert literal default helper selection.
grep -Fq 'delete_script="${PREVIEW_DELETE_SCRIPT:-${script_dir}/../shared/delete-hosted-namespace.sh}"' \
  "$ROOT_DIR/dev-tools/hosted/preview/prune-stale-preview-namespaces.sh"
if grep -Fq 'delete_runtime_namespace()' \
  "$ROOT_DIR/dev-tools/hosted/preview/prune-stale-preview-namespaces.sh"; then
  echo "pruner retained a duplicate runtime namespace deletion implementation" >&2
  exit 1
fi
if grep -Eq 'def labels_valid:|all\(\.labels\[\]\?; \(type == "object"\)' \
  "$trusted_workflow" \
  "$reconciler_workflow" \
  "$RECONCILER_RUN" \
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
python3 - "$ROOT_DIR/.github/workflows/preview.yml" "$trusted_workflow" "$reconciler_workflow" <<'PY'
import sys
from pathlib import Path

import yaml

preview = yaml.safe_load(Path(sys.argv[1]).read_text(encoding="utf-8"))
trusted = yaml.safe_load(Path(sys.argv[2]).read_text(encoding="utf-8"))
reconciler = yaml.safe_load(Path(sys.argv[3]).read_text(encoding="utf-8"))
assert set(preview["jobs"]) == {
    "preview-plan",
    "preview-runtime-base-refresh",
    "preview-render",
    "preview-destroy-intent",
}
assert trusted["jobs"]["destroy-runtime"]["timeout-minutes"] == 60
assert reconciler["jobs"]["reconcile-previews"]["timeout-minutes"] == 60
reconciler_steps = reconciler["jobs"]["reconcile-previews"]["steps"]
checkout = reconciler_steps[0]
assert checkout["uses"].startswith("actions/checkout@")
assert checkout["with"] == {
    "ref": "${{ github.event.repository.default_branch }}",
    "persist-credentials": False,
}
dispatch = next(step for step in reconciler_steps if step.get("name") == "Dispatch preview deploys for drifted PRs")
assert dispatch["run"] == "bash ./dev-tools/hosted/preview/reconcile-preview-deploys.sh"
reconciler_manager_write = next(
    step
    for step in reconciler["jobs"]["reconcile-previews"]["steps"]
    if step.get("name") == "Write trusted namespace-manager kubeconfig"
)
assert reconciler_manager_write["uses"] == "./.github/actions/write-kubeconfig"
assert reconciler_manager_write["with"] == {
    "content": "${{ secrets.TRUSTED_HOSTED_PREVIEW_NAMESPACE_MANAGER_KUBECONFIG }}",
    "path": "${{ runner.temp }}/preview-namespace-manager.kubeconfig",
    "export-to-github-env": "false",
}
preview_run_scripts = [
    step["run"]
    for job in preview["jobs"].values()
    for step in job.get("steps", [])
    if isinstance(step.get("run"), str)
]
canonical_pr_number_pattern = '"$PR_NUMBER" =~ ^[1-9][0-9]{0,50}$'
assert sum(
    script.count(canonical_pr_number_pattern) for script in preview_run_scripts
) == 1
assert not any(
    '"$PR_NUMBER" =~ ^[1-9][0-9]*$' in script for script in preview_run_scripts
)
PY
grep -q -- '--retire-terminal-identities' "$ROOT_DIR/dev-tools/hosted/preview/prune-stale-preview-namespaces.sh"
grep -q 'Skipping ordinary PR #' "$RECONCILER_RUN"
grep -q 'preview dispatch cycle already stopped after a request attempt' "$RECONCILER_RUN"
# Proof repair is bounded by the exact retained tuple and must not duplicate
# an active source or trusted consumer run.
retry_record_helper="$ROOT_DIR/dev-tools/hosted/preview/preview-proof-retry-record.shlib"
grep -Fq 'read_preview_retry_holder' "$RECONCILER_RUN"
grep -Fq 'write_preview_retry_entry' "$RECONCILER_RUN"
grep -Fq 'proof retry budget exhausted' "$RECONCILER_RUN"
grep -Fq '{op:"test",path:"/metadata/uid",value:.metadata.uid}' "$retry_record_helper"
grep -Fq '{op:"test",path:"/metadata/resourceVersion",value:.metadata.resourceVersion}' "$retry_record_helper"
# shellcheck disable=SC2016 # Assert canonical workflow lookup and native custody.
grep -Fq -- 'actions/workflows/preview.yml" | jq' "$RECONCILER_RUN"
# shellcheck disable=SC2016 # Assert the literal jq workflow and repository checks.
grep -Fq -- '.workflow_id == $workflow_id' "$RECONCILER_RUN"
# shellcheck disable=SC2016 # Assert the literal jq head repository check.
grep -Fq -- '.head_repository.full_name == $repository' "$RECONCILER_RUN"
grep -Fq 'Skipping proof retry for PR #' "$RECONCILER_RUN"
grep -Fq "startsWith(github.event.workflow_run.display_title, 'Preview retry ')" "$trusted_workflow"
# shellcheck disable=SC2016 # Assert one fail-closed JSON snapshot feeds each namespace decision.
grep -Fq 'kubectl get namespace "${namespace}" --ignore-not-found -o json' \
  "$RECONCILER_RUN"
# shellcheck disable=SC2016 # Assert the final runtime fence is read independently of reservation custody.
grep -Fq 'kubectl get namespace "$namespace" --ignore-not-found -o json --request-timeout=10s' "$RECONCILER_RUN"
# shellcheck disable=SC2016 # Assert the fresh namespace snapshot fences repair.
grep -Fq 'requested preview tuple changed during annotation repair check' "$RECONCILER_RUN"
# The janitor keeps explicit kubeconfig selection on the consuming kubectl steps;
# no preceding restore step may imply a different runner-wide kubeconfig.
if grep -Fq 'Restore preview runtime kubeconfig' "$janitor_workflow"; then
  echo "Preview janitor retained an ineffective runtime kubeconfig restore step" >&2
  exit 1
fi
# shellcheck disable=SC2016 # Assert explicit kubeconfig selection on janitor consumers.
grep -Fq 'KUBECONFIG: ${{ runner.temp }}/preview-namespace-manager.kubeconfig' "$janitor_workflow"
# shellcheck disable=SC2016 # Assert labels are encoded as one safe row field.
grep -Fq '(.labels | map({name: .name}) | tojson | @base64)' "$RECONCILER_RUN"
# shellcheck disable=SC2016 # Assert decoded labels reach the centralized parser.
grep -Fq -- '--labels-json "$labels_json"' "$RECONCILER_RUN"
# shellcheck disable=SC2016 # Assert capacity uses centralized validated priority.
grep -Fq -- 'priority="$(sed -n '\''s/^priority=//p'\'' <<<"$eligibility_output")"' \
  "$RECONCILER_RUN"
# Reject the redundant transported raw-priority field and read placeholder while
# retaining the leading numeric key used only for stable priority ordering.
if grep -Fq '_sort_priority' "$RECONCILER_RUN"; then
  echo "reconciler retained a redundant transported raw-priority field" >&2
  exit 1
fi
python3 - "$RECONCILER_RUN" <<'PY'
import sys
from pathlib import Path

run = Path(sys.argv[1]).read_text(encoding="utf-8")
assert run.count('any(. == "preview:priority")') == 1
assert "then 0 else 1 end" in run
assert "sort -t $'\\t' -k1,1n -k2,2n" in run
assert (
    "read -r _ pr_number head_sha pr_author pr_base_ref pr_state "
    "labels_json_base64"
) in run
function_start = run.index("dispatch_candidates()")
function_end = run.index("\n}\n\ncandidate_rows=", function_start)
function_body = run[function_start:function_end]
assert "gh api --paginate --slurp" not in function_body
assert run.count(
    "actions/workflows/${preview_workflow_id}/runs?branch=${DEFAULT_BRANCH}&status=${active_status}&per_page=100"
) == 1
candidate_rows_start = run.index("candidate_rows=")
active_runs_start = run.index("active_preview_run_sets=()", candidate_rows_start)
dispatch_call = run.index("dispatch_candidates <<<", active_runs_start)
assert candidate_rows_start < active_runs_start < dispatch_call
eligible_guard = run.index('if [[ "$eligible" != "true" ]]')
dispatch_guard = run.index('if [[ "$dispatch_sent" == "true" ]]', eligible_guard)
namespace_snapshot = run.index('if ! namespace_json="$(\n', dispatch_guard)
base_ref_resolution = run.index('if ! base_ref_json=', namespace_snapshot)
assert eligible_guard < dispatch_guard < namespace_snapshot < base_ref_resolution

reserve = run[run.index("start_reserved_proof_retry()") : run.index("compensate_rejected_proof_retry()")]
assert reserve.index('write_preview_retry_entry "$holder" "$namespace" "$started" true') < reserve.index(
    'current="$(read_preview_retry_holder)"'
)
assert '.metadata.uid == $uid' in reserve
assert '[[ "$current_row" != "$started" ]]' in reserve
assert 'proof_retry_namespace_matches "$namespace" "$started"' in reserve
assert '.metadata.resourceVersion =' not in reserve

handoff = run[run.index("recent_successful_source_handoff()") : run.index("evaluate_recent_source_handoff()")]
refresh = handoff.index("completed_preview_inventory repository_dispatch")
last_native = handoff.rindex("completed_preview_inventory pull_request")
assert refresh < last_native < handoff.rindex("evaluate_recent_source_handoff")
assert "return 4" in handoff[last_native:]

post = function_body[function_body.index('"${dispatch_http_status}" == 204 ]]; then') :]
assert "dispatch_sent=true\n    break\n  elif" in post
assert "dispatch_sent=true\n    break\n  fi" in post
assert "compensate_rejected_proof_retry" in post
assert 'client_payload[proof_retry_nonce]' in run
assert 'settle_terminal_preview_retry' in run
assert 'validate-preview-artifact.py retry-receipt' in run
assert 'and .status == "completed" and .conclusion == "success"' in run[run.index("trusted_consumer_has_tuple()") :]
assert 'source-run-${run_id}-attempt-${attempt}' in run
assert 'Preview retry ${nonce}' in run
PY
# shellcheck disable=SC2016 # Assert malformed label metadata fails closed before eligibility.
grep -Fq -- 'if ! labels_json="$(printf '\''%s'\'' "$labels_json_base64" | base64 --decode 2>/dev/null)" ||' \
  "$RECONCILER_RUN"
# shellcheck disable=SC2016 # Assert literal shell source in the workflow.
grep -Fq -- '[[ -z "$labels_json" ]] ||' "$RECONCILER_RUN"
# shellcheck disable=SC2016 # Assert literal shell source in the workflow.
grep -Fq -- '! jq -e '\''type == "array"'\'' <<<"$labels_json" >/dev/null' \
  "$RECONCILER_RUN"

echo "preview priority contract checks passed"
