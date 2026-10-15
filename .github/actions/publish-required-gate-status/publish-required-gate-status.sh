#!/usr/bin/env bash
set -euo pipefail

fail_closed() { echo "$*" >&2; exit 1; }
report() { printf 'publication=%s\n' "$1" >>"${GITHUB_OUTPUT}"; }
unchanged() { report unchanged; exit 0; }
for variable in GH_TOKEN GITHUB_REPOSITORY GITHUB_EVENT_NAME GITHUB_EVENT_PATH GITHUB_RUN_ID GITHUB_RUN_ATTEMPT GITHUB_OUTPUT BASE_SHA HEAD_SHA PR_NUMBER ASSESSMENT REQUIRED_GATE_NAME EXPECTED_WORKFLOW_NAME EXPECTED_WORKFLOW_FILE EXPECTED_WORKFLOW_PATH; do
  [[ -n "${!variable:-}" ]] || fail_closed "Status publisher is missing ${variable}."
done
[[ "${GITHUB_EVENT_NAME}" == pull_request && "${BASE_SHA}" =~ ^[0-9a-fA-F]{40}$ &&
   "${HEAD_SHA}" =~ ^[0-9a-fA-F]{40}$ && "${PR_NUMBER}" =~ ^[1-9][0-9]*$ &&
   "${GITHUB_RUN_ID}" =~ ^[1-9][0-9]*$ && "${GITHUB_RUN_ATTEMPT}" =~ ^[1-9][0-9]*$ &&
   "${GITHUB_REPOSITORY}" =~ ^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$ ]] ||
  fail_closed "Status publisher requires an exact pull-request identity."
case "${ASSESSMENT}" in success|dependency-deferred) ;; *) fail_closed "Invalid canonical assessment." ;; esac
case "${EXPECTED_WORKFLOW_FILE}" in
  ci.yml) workflow_name='CI — Validation'; gate_name='Validation Gate' ;;
  security.yml) workflow_name='Security Gate'; gate_name='Security Gate' ;;
  codeql.yml) workflow_name='CodeQL Analysis'; gate_name='CodeQL Gate' ;;
  license-scan.yml) workflow_name='License Gate'; gate_name='License Gate' ;;
  smoke.yml) workflow_name='PR Smoke Gate'; gate_name='Smoke Gate' ;;
  *) fail_closed "Unsupported required gate workflow." ;;
esac
[[ "${EXPECTED_WORKFLOW_PATH}" == ".github/workflows/${EXPECTED_WORKFLOW_FILE}" &&
   "${EXPECTED_WORKFLOW_NAME}" == "${workflow_name}" && "${REQUIRED_GATE_NAME}" == "${gate_name}" ]] ||
  fail_closed "Required gate and owning workflow disagree."
api_root="/repos/${GITHUB_REPOSITORY}"
api_url="https://api.github.com/repos/${GITHUB_REPOSITORY}"
run_url="https://github.com/${GITHUB_REPOSITORY}/actions/runs/"
current_target="${run_url}${GITHUB_RUN_ID}/attempts/${GITHUB_RUN_ATTEMPT}"
current_title="${workflow_name} pr-${PR_NUMBER} base-${BASE_SHA} head-${HEAD_SHA}"
jq -e --arg repo "${GITHUB_REPOSITORY}" --argjson pr "${PR_NUMBER}" --arg base "${BASE_SHA}" --arg head "${HEAD_SHA}" '
  .action == "edited" and .repository.full_name == $repo and .number == $pr and
  .changes.base.ref == null and .pull_request.number == $pr and
  .pull_request.base.repo.full_name == $repo and .pull_request.base.sha == $base and
  .pull_request.head.sha == $head' "${GITHUB_EVENT_PATH}" >/dev/null ||
  fail_closed "Status publication requires the bound metadata-only event."
head_branch="$(jq -er '.pull_request.head.ref | select(type == "string" and length > 0)' "${GITHUB_EVENT_PATH}")"
head_repository="$(jq -er '.pull_request.head.repo.full_name | select(type == "string" and length > 0)' "${GITHUB_EVENT_PATH}")"
base_branch="$(jq -er '.pull_request.base.ref | select(type == "string" and length > 0)' "${GITHUB_EVENT_PATH}")"
workflow="$(gh api --method GET "${api_root}/actions/workflows/${EXPECTED_WORKFLOW_FILE}")"
workflow_id="$(jq -er --arg name "${workflow_name}" --arg path "${EXPECTED_WORKFLOW_PATH}" '
  select(.name == $name and .path == $path) | .id | select(type == "number" and . > 0 and . == floor)' <<<"${workflow}")"
current_pr() { gh api --method GET "${api_root}/pulls/${PR_NUMBER}"; }
pr_matches() {
  jq -e --arg repo "${GITHUB_REPOSITORY}" --argjson pr "${PR_NUMBER}" --arg head "${HEAD_SHA}" \
    --arg base "${BASE_SHA}" --arg branch "${head_branch}" --arg head_repo "${head_repository}" --arg base_ref "${base_branch}" '
    .number == $pr and .state == "open" and .base.repo.full_name == $repo and
    .base.sha == $base and .base.ref == $base_ref and .head.sha == $head and
    .head.ref == $branch and .head.repo.full_name == $head_repo' <<<"$1" >/dev/null
}
# Complete one-page inventories keep API work bounded; overflow refuses writes.
latest_status() {
  local inventory
  inventory="$(gh api --method GET "${api_root}/commits/${HEAD_SHA}/status" -f per_page=100)" || return 1
  jq -c --arg head "${HEAD_SHA}" --arg context "${gate_name}" '
    if type != "object" or .sha != $head or (.statuses | type) != "array" or
      (.total_count | type) != "number" or .total_count < 0 or .total_count > 100 or
      (.total_count | floor) != .total_count or (.statuses | length) != .total_count or
      any(.statuses[]; type != "object" or (.context | type) != "string" or (.context | length) == 0 or
        (.id | type) != "number" or .id <= 0 or (.id | floor) != .id) or
      ([.statuses[].id] | unique | length) != .total_count then error("incomplete status evidence") else . end
    | [.statuses[] | select(.context == $context)]
    | if length == 0 then null elif length == 1 then .[0] else error("ambiguous context") end' <<<"${inventory}"
}
# This proves ownership, not original source success. Old base SHA and failed or
# cancelled publisher steps identify provenance only and cannot grant proof.
native_identity() {
  local run_id="$1" attempt="$2" current="$3" run title title_base jobs check check_id
  if [[ "${current}" == true ]]; then
    run="$(gh api --method GET "${api_root}/actions/runs/${run_id}")" || return 1
  else
    run="$(gh api --method GET "${api_root}/actions/runs/${run_id}/attempts/${attempt}")" || return 1
  fi
  title="$(jq -er '.display_title | select(type == "string")' <<<"${run}")" || return 1
  title_base="$(jq -ern --arg title "${title}" --arg name "${workflow_name}" --arg pr "${PR_NUMBER}" --arg head "${HEAD_SHA}" '
    $title | capture("^(?<name>.*) pr-(?<pr>[1-9][0-9]*) base-(?<base>[0-9A-Fa-f]{40}) head-(?<head>[0-9A-Fa-f]{40})$")
    | select(.name == $name and .pr == $pr and .head == $head) | .base')" || return 1
  [[ "${current}" != true || "${title_base}" == "${BASE_SHA}" ]] || return 1
  jq -e --argjson id "${run_id}" --argjson attempt "${attempt}" --argjson workflow "${workflow_id}" \
    --arg repo "${GITHUB_REPOSITORY}" --arg head_repo "${head_repository}" --arg branch "${head_branch}" \
    --arg head "${HEAD_SHA}" --arg title "${title}" --arg name "${workflow_name}" --arg path "${EXPECTED_WORKFLOW_PATH}" \
    --argjson pr "${PR_NUMBER}" --argjson current "${current}" --argjson current_number "${current_run_number}" \
    --argjson current_run "${GITHUB_RUN_ID}" --argjson current_attempt "${GITHUB_RUN_ATTEMPT}" '
    .id == $id and .run_attempt == $attempt and .workflow_id == $workflow and .event == "pull_request" and
    (.run_number | type == "number" and . > 0 and . == floor) and
    (if $current then .run_number == $current_number else
      .run_number < $current_number or
      (.run_number == $current_number and .id == $current_run and .run_attempt <= $current_attempt) end) and
    .repository.full_name == $repo and .head_repository.full_name == $head_repo and
    .head_sha == $head and .head_branch == $branch and .display_title == $title and
    (.name == $name or .name == $title) and
    (.path == $path or (.path | startswith($path + "@") and length > ($path | length) + 1)) and
    (.pull_requests | type == "array") and
    ((.pull_requests | length) == 0 or any(.pull_requests[]; .number == $pr)) and
    (if $current then .status == "in_progress" and .conclusion == null else
      (.status == "completed" and (.conclusion | type == "string" and length > 0)) or
      ((.status == "in_progress" or .status == "queued" or .status == "pending" or .status == "waiting" or .status == "requested") and .conclusion == null) end)' <<<"${run}" >/dev/null || return 1
  jobs="$(gh api --method GET "${api_root}/actions/runs/${run_id}/attempts/${attempt}/jobs" -f per_page=100)" || return 1
  jobs="$(jq -ce --arg name "${gate_name}" --arg head "${HEAD_SHA}" --argjson run "${run_id}" --argjson current "${current}" '
    if type != "object" or (.jobs | type) != "array" or (.total_count | type) != "number" or
      .total_count < 0 or .total_count > 100 or (.total_count | floor) != .total_count or
      (.jobs | length) != .total_count or any(.jobs[]; type != "object" or (.id | type) != "number" or .id <= 0 or (.id | floor) != .id) or
      ([.jobs[].id] | unique | length) != .total_count then error("incomplete jobs") else . end
    | [.jobs[] | select(.name == $name)] | if length == 1 then .[0] else error("ambiguous native gate") end
    | select(.run_id == $run and .head_sha == $head)
    | select((.status == "completed" and (.conclusion | type == "string" and length > 0)) or
      ((.status == "in_progress" or .status == "queued" or .status == "pending" or .status == "waiting" or .status == "requested") and .conclusion == null))
    | select([.steps[]? | select(.name == "Publish pending required gate status" and
        ((.status == "in_progress" and .conclusion == null) or
         (.status == "completed" and (.conclusion == "success" or .conclusion == "failure" or
          .conclusion == "cancelled" or .conclusion == "timed_out" or .conclusion == "action_required"))))] | length == 1)
    | select(if $current then .status == "in_progress" and .conclusion == null and
        ([.steps[]? | select(.name == "Preserve successful required gate on metadata-only edit" and
          .status == "completed" and .conclusion == "success")] | length == 1) else true end)' <<<"${jobs}")" || return 1
  check_id="$(jq -er '.check_run_url | capture("/check-runs/(?<id>[1-9][0-9]*)$").id' <<<"${jobs}")" || return 1
  check="$(gh api --method GET "${api_root}/check-runs/${check_id}")" || return 1
  jq -e --argjson id "${check_id}" --arg name "${gate_name}" --arg head "${HEAD_SHA}" --argjson job "${jobs}" '
    .id == $id and .app.id == 15368 and .app.slug == "github-actions" and .name == $name and
    .head_sha == $head and .status == $job.status and .conclusion == $job.conclusion' <<<"${check}" >/dev/null
}
owned_status() {
  local suffix run_id attempt
  jq -e --arg url "${api_url}/statuses/${HEAD_SHA}" '
    .creator.id == 41898282 and .creator.login == "github-actions[bot]" and .creator.type == "Bot" and
    .url == $url and (.state == "pending" or .state == "success" or .state == "failure" or .state == "error")' <<<"$1" >/dev/null || return 1
  suffix="$(jq -er '.target_url' <<<"$1")" || return 1
  [[ "${suffix}" == "${run_url}"* ]] || return 1
  suffix="${suffix#"${run_url}"}"
  [[ "${suffix}" =~ ^([1-9][0-9]*)/attempts/([1-9][0-9]*)$ ]] || return 1
  run_id="${BASH_REMATCH[1]}"; attempt="${BASH_REMATCH[2]}"
  native_identity "${run_id}" "${attempt}" false
}
pr="$(current_pr)"
pr_matches "${pr}" || unchanged
# Validate the running metadata workflow even on a no-write success path.
run="$(gh api --method GET "${api_root}/actions/runs/${GITHUB_RUN_ID}")"
jq -e --arg title "${current_title}" --argjson id "${GITHUB_RUN_ID}" --argjson attempt "${GITHUB_RUN_ATTEMPT}" \
  --argjson workflow "${workflow_id}" --arg repo "${GITHUB_REPOSITORY}" --arg head "${HEAD_SHA}" \
  --arg head_repo "${head_repository}" --arg branch "${head_branch}" --arg name "${workflow_name}" \
  --arg path "${EXPECTED_WORKFLOW_PATH}" --argjson pr "${PR_NUMBER}" '
  .id == $id and .run_attempt == $attempt and .workflow_id == $workflow and .repository.full_name == $repo and
  (.run_number | type == "number" and . > 0 and . == floor) and
  .event == "pull_request" and .display_title == $title and .head_sha == $head and
  .head_repository.full_name == $head_repo and .head_branch == $branch and
  (.name == $name or .name == $title) and
  (.path == $path or (.path | startswith($path + "@") and length > ($path | length) + 1)) and
  (.pull_requests | type == "array") and
  ((.pull_requests | length) == 0 or any(.pull_requests[]; .number == $pr)) and
  .status == "in_progress" and .conclusion == null' <<<"${run}" >/dev/null || fail_closed "Current metadata run is not attributable."
current_run_number="$(jq -er '.run_number' <<<"${run}")"
latest="$(latest_status)" || fail_closed "Latest context is incomplete or ambiguous."
owned=false
if [[ "${latest}" != null ]] && owned_status "${latest}"; then owned=true; fi
if [[ "${ASSESSMENT}" == success ]]; then
  if [[ "${owned}" != true || "$(jq -r '.state' <<<"${latest}")" == success ]]; then unchanged; fi
  assessment_output="$(mktemp)"
  trap 'rm -f "$assessment_output"' EXIT
  if ! GITHUB_OUTPUT="${assessment_output}" bash "$(dirname "${BASH_SOURCE[0]}")/../preserve-required-gate/assess-required-gate.sh"; then
    fail_closed "Refreshed canonical assessment failed; no status is published."
  fi
  ASSESSMENT="$(sed -n 's/^assessment=//p' "${assessment_output}")"
  case "${ASSESSMENT}" in success|dependency-deferred) ;; *) fail_closed "Malformed refreshed assessment." ;; esac
fi
if [[ "${latest}" != null && "${owned}" != true ]]; then unchanged; fi
state=pending
[[ "${ASSESSMENT}" != success ]] || state=success
native_identity "${GITHUB_RUN_ID}" "${GITHUB_RUN_ATTEMPT}" true || fail_closed "Current metadata native gate is not attributable."
pr="$(current_pr)"
pr_matches "${pr}" || unchanged
fresh="$(latest_status)" || fail_closed "Latest context became unavailable."
[[ "${fresh}" == "${latest}" ]] || unchanged
# One POST, no retry. Its native REST shape has creator and URL, not sha/app_id.
ack="$(gh api --method POST "${api_root}/statuses/${HEAD_SHA}" -f state="${state}" -f context="${gate_name}" \
  -f target_url="${current_target}" -f description="Metadata gate original proof: ${state}")" ||
  fail_closed "Status POST was denied or ambiguous; do not replay."
jq -se --arg url "${api_url}/statuses/${HEAD_SHA}" --arg context "${gate_name}" --arg state "${state}" --arg target "${current_target}" '
  if length != 1 then error("ambiguous acknowledgment") else .[0] end
  | type == "object" and (.id | type == "number" and . > 0 and . == floor) and .url == $url and
  .context == $context and .state == $state and .target_url == $target and
  .creator.id == 41898282 and .creator.login == "github-actions[bot]" and .creator.type == "Bot" and
  (try (.created_at | fromdateiso8601 | type == "number") catch false) and
  (try (.updated_at | fromdateiso8601 | type == "number") catch false)' <<<"${ack}" >/dev/null ||
  fail_closed "Status POST acknowledgment is malformed or misattributed."
report "${state}-acknowledged"
