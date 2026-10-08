#!/usr/bin/env bash
set -euo pipefail

fail_closed() {
  echo "$*" >&2
  exit 1
}
resolution_phase="${1:-admit}"
case "${resolution_phase}" in
  preflight|admit) ;;
  *) fail_closed "Unsupported resolver phase." ;;
esac
report_eligible() {
  if [[ "${resolution_phase}" == preflight ]]; then
    [[ -n "${GITHUB_OUTPUT:-}" ]] || fail_closed "Preflight requires GITHUB_OUTPUT."
    printf 'eligible=%s\n' "$1" >>"${GITHUB_OUTPUT}"
  elif [[ "$1" == false && -n "${GITHUB_OUTPUT:-}" ]]; then
    # Successful early no-ops also need an explicit empty confirmation.
    printf 'accepted_job_ids=[]\n' >>"${GITHUB_OUTPUT}"
  fi
}

[[ "${GITHUB_EVENT_NAME:-}" == workflow_run ]] || fail_closed "Resolver requires workflow_run context."
for required_variable in GH_TOKEN GITHUB_REPOSITORY GITHUB_EVENT_PATH GITHUB_RUN_ID; do
  [[ -n "${!required_variable:-}" ]] || fail_closed "Resolver is missing ${required_variable}."
done
[[ "${resolution_phase}" != admit || -n "${GITHUB_OUTPUT:-}" ]] ||
  fail_closed "Admission requires GITHUB_OUTPUT for acknowledged job IDs."
source_run_id="$(jq -er '.workflow_run.id | select(type == "number" and . > 0 and . == floor)' "${GITHUB_EVENT_PATH}")"
source_json="$(gh api --method GET "/repos/${GITHUB_REPOSITORY}/actions/runs/${source_run_id}")"
workflow_path="$(jq -er '.path | select(type == "string")' <<<"${source_json}")"
workflow_file="${workflow_path%%@*}"
case "${workflow_file}" in
  .github/workflows/ci.yml) workflow_name='CI — Validation'; gate_name='Validation Gate' ;;
  .github/workflows/security.yml) workflow_name='Security Gate'; gate_name='Security Gate' ;;
  .github/workflows/codeql.yml) workflow_name='CodeQL Analysis'; gate_name='CodeQL Gate' ;;
  .github/workflows/license-scan.yml) workflow_name='License Gate'; gate_name='License Gate' ;;
  .github/workflows/smoke.yml) workflow_name='PR Smoke Gate'; gate_name='Smoke Gate' ;;
  *) fail_closed "Source does not own a supported required gate." ;;
esac
workflow_filename="${workflow_file##*/}"
workflow_json="$(gh api --method GET "/repos/${GITHUB_REPOSITORY}/actions/workflows/${workflow_filename}")"
workflow_id="$(jq -er --arg name "${workflow_name}" --arg path "${workflow_file}" '
  select(.name == $name and .path == $path)
  | .id | select(type == "number" and . > 0 and . == floor)' <<<"${workflow_json}")"
source_title="$(jq -er '.display_title' <<<"${source_json}")"
title_identity="$(jq -ern --arg title "${source_title}" --arg name "${workflow_name}" '
  $title | capture("^(?<name>.*) pr-(?<pr>[1-9][0-9]*) base-(?<base>[0-9A-Fa-f]{40}) head-(?<head>[0-9A-Fa-f]{40})$")
  | select(.name == $name) | [.pr, .base, .head] | @tsv')"
IFS=$'\t' read -r pr_number base_sha head_sha <<<"${title_identity}"
head_branch="$(jq -er '.head_branch | select(type == "string" and length > 0)' <<<"${source_json}")"
jq -e --argjson id "${source_run_id}" --argjson workflow_id "${workflow_id}" \
  --arg repository "${GITHUB_REPOSITORY}" --arg name "${workflow_name}" \
  --arg title "${source_title}" --arg path "${workflow_file}" --arg head "${head_sha}" '
  .id == $id and .workflow_id == $workflow_id and
  (.name == $name or .name == $title) and
  (.path == $path or (.path | startswith($path + "@") and length > ($path | length) + 1)) and
  .repository.full_name == $repository and .event == "pull_request" and
  .status == "completed" and .head_sha == $head' <<<"${source_json}" >/dev/null ||
  fail_closed "Source workflow identity is not attributable."
jq -e --argjson id "${source_run_id}" --argjson workflow_id "${workflow_id}" --arg head "${head_sha}" '
  .action == "completed" and .workflow_run.id == $id and
  .workflow_run.workflow_id == $workflow_id and .workflow_run.head_sha == $head
  and .workflow_run.event == "pull_request"' "${GITHUB_EVENT_PATH}" >/dev/null ||
  fail_closed "Completion event does not match the source workflow."

# Resolver reruns retain their ID, and latest job snapshots can hide an older
# ambiguous admission. Neither the current nor historical resolver may rerun
# automatically; explicit recovery must establish a separate safe boundary.
resolver_path='.github/workflows/resolve-required-gates.yml'
resolver_json="$(gh api --method GET "/repos/${GITHUB_REPOSITORY}/actions/workflows/resolve-required-gates.yml")"
resolver_id="$(jq -er --arg path "${resolver_path}" '
  select(.name == "Resolve Required Gates" and .path == $path)
  | .id | select(type == "number" and . > 0 and . == floor)' <<<"${resolver_json}")"
current_resolver_json="$(gh api --method GET "/repos/${GITHUB_REPOSITORY}/actions/runs/${GITHUB_RUN_ID}")"
jq -e --argjson id "${GITHUB_RUN_ID}" --argjson workflow_id "${resolver_id}" \
  --arg repository "${GITHUB_REPOSITORY}" --arg path "${resolver_path}" \
  --arg title "Resolve Required Gates workflow-${workflow_id} head-${head_sha} source-${source_run_id}" '
  .id == $id and .workflow_id == $workflow_id and .repository.full_name == $repository and
  .event == "workflow_run" and (.name == "Resolve Required Gates" or .name == $title) and
  (.path == $path or (.path | startswith($path + "@") and length > ($path | length) + 1)) and
  .display_title == $title and .run_attempt == 1 and .status == "in_progress" and
  (.head_sha | type) == "string" and (.head_sha | test("^[0-9A-Fa-f]{40}$")) and
  (try (.created_at | fromdateiso8601 | type == "number") catch false)' <<<"${current_resolver_json}" >/dev/null ||
  fail_closed "Current resolver identity or first attempt is unproved; explicit recovery is required."
current_resolver_created="$(jq -er '.created_at' <<<"${current_resolver_json}")"

current_pr() {
  gh api --method GET "/repos/${GITHUB_REPOSITORY}/pulls/${pr_number}"
}
pr_is_current() {
  jq -e --arg repository "${GITHUB_REPOSITORY}" --arg head "${head_sha}" \
    --arg base "${base_sha}" --arg branch "${head_branch}" --argjson pr "${pr_number}" \
    --arg base_branch "${base_branch:-}" --arg head_repository "${head_repository:-}" '
    .number == $pr and .state == "open" and .base.repo.full_name == $repository and
    .base.sha == $base and .head.sha == $head and .head.ref == $branch and
    (.base.ref | type) == "string" and (.base.ref | length) > 0 and
    (.head.repo.full_name | type) == "string" and (.head.repo.full_name | length) > 0 and
    ($base_branch == "" or .base.ref == $base_branch) and
    ($head_repository == "" or .head.repo.full_name == $head_repository)' <<<"$1" >/dev/null
}
pr_json="$(current_pr)"
if ! pr_is_current "${pr_json}"; then
  echo "Source tuple is obsolete; no gate is rerun."
  report_eligible false
  exit 0
fi
head_repository="$(jq -er '.head.repo.full_name' <<<"${pr_json}")"
base_branch="$(jq -er '.base.ref' <<<"${pr_json}")"

# A capped exact-head query bounds both target discovery and the native history
# window. Resolver completions cannot predate their source creation. Twenty
# retained sources is the source-discovery limit. Resolver history uses
# separate bounded pagination; partial evidence always requires recovery.
evidence_limit=20
source_listing_read_limit=3
source_listing_attempt=0
source_listing_result=''
while (( source_listing_attempt < source_listing_read_limit )); do
  source_listing_attempt=$((source_listing_attempt + 1))
  run_pages="$(gh api --method GET "/repos/${GITHUB_REPOSITORY}/actions/workflows/${workflow_filename}/runs" \
    -f event=pull_request -f head_sha="${head_sha}" -f per_page="${evidence_limit}")"
  if ! source_listing_result="$(jq -cer --argjson limit "${evidence_limit}" --argjson source "${source_run_id}" \
    --argjson workflow_id "${workflow_id}" --arg repository "${GITHUB_REPOSITORY}" --arg head "${head_sha}" '
    def listed_count:
      if type != "object" then null
      elif (.workflow_runs | type) == "array" then (.workflow_runs | length) else null end;
    def source_present:
      if type != "object" then false
      elif (.workflow_runs | type) == "array" then
        any(.workflow_runs[]; if type == "object" then .id == $source else false end)
      else false end;
    def outcome($classification): {
      classification: $classification,
      total_count: (if type == "object" then (.total_count // null) else null end),
      returned_rows: listed_count,
      source_present: source_present
    };
    if type != "object" then outcome("malformed-response")
    elif (.workflow_runs | type) != "array" then outcome("malformed-workflow-runs")
    elif (.total_count | type) != "number" or .total_count < 0 or .total_count != (.total_count | floor) then
      outcome("malformed-total-count")
    elif .total_count > $limit then outcome("overflow-total-count")
    elif any(.workflow_runs[]; type != "object") then outcome("malformed-row")
    elif any(.workflow_runs[];
      (.id | type) != "number" or .id <= 0 or .id != (.id | floor)) then outcome("malformed-row-id")
    elif any(.workflow_runs[]; (.repository | type) != "object") then outcome("malformed-row-repository")
    elif any(.workflow_runs[];
      .workflow_id != $workflow_id or .repository.full_name != $repository or
      .head_sha != $head or .event != "pull_request") then outcome("identity-mismatch")
    elif any(.workflow_runs[];
      (.created_at | type) != "string" or
      (try (.created_at | fromdateiso8601 | type == "number") catch false | not)) then
      outcome("malformed-created-at")
    elif ([.workflow_runs[].id] | unique | length) != (.workflow_runs | length) then
      outcome("duplicate-row-id")
    elif (.workflow_runs | length) > .total_count then outcome("malformed-row-count")
    elif (.workflow_runs | length) < .total_count then outcome("incomplete-row-count")
    elif (source_present | not) then outcome("incomplete-missing-source")
    else outcome("complete") end' <<<"${run_pages}")"; then
    fail_closed "Exact-head source listing response is not valid JSON."
  fi
  source_listing_classification="$(jq -er '.classification' <<<"${source_listing_result}")"
  case "${source_listing_classification}" in
    complete)
      break
      ;;
    incomplete-row-count|incomplete-missing-source)
      source_listing_diagnostics="$(jq -er '[.classification, (.total_count // "unknown"), (.returned_rows // "unknown"), .source_present] | @tsv' \
        <<<"${source_listing_result}")"
      if (( source_listing_attempt == source_listing_read_limit )); then
        fail_closed "Exact-head source coverage remains incomplete after ${source_listing_attempt} reads: classification=${source_listing_classification}, total_count=$(jq -r '.total_count // "unknown"' <<<"${source_listing_result}"), returned_rows=$(jq -r '.returned_rows // "unknown"' <<<"${source_listing_result}"), source_present=$(jq -r '.source_present' <<<"${source_listing_result}"), limit=${evidence_limit}."
      fi
      echo "Exact-head source listing is incomplete (read ${source_listing_attempt}/${source_listing_read_limit}): ${source_listing_diagnostics}; limit=${evidence_limit}." >&2
      if (( source_listing_attempt == 1 )); then sleep 1; else sleep 2; fi
      ;;
    *)
      fail_closed "Exact-head source coverage rejected: classification=${source_listing_classification}, total_count=$(jq -r '.total_count // "unknown"' <<<"${source_listing_result}"), returned_rows=$(jq -r '.returned_rows // "unknown"' <<<"${source_listing_result}"), source_present=$(jq -r '.source_present' <<<"${source_listing_result}"), limit=${evidence_limit}."
      ;;
  esac
done
target_ids="$(jq -er '.workflow_runs[].id' <<<"${run_pages}")"

# Native resolver job/step evidence carries the conservative recovery boundary
# across invocations. A failed or incomplete admission may have sent a POST;
# subsequent callbacks must not guess from a still-visible target attempt 1.
assert_no_ambiguous_admission() {
  local resolver_pages resolver_page history_total history_page history_page_count expected_page_records
  local prior_ids prior_id prior_json prior_jobs prior_head equal_time_waiting
  local prior_admitted_ids prior_admitted_id
  # The REST search ceiling is 1,000 records. Fetch explicit pages so neither
  # an oversized response nor pagination can start an unbounded history scan.
  resolver_pages='[]'
  history_total=0
  history_page_count=1
  for (( history_page=1; history_page<=history_page_count; history_page++ )); do
    resolver_page="$(gh api --method GET "/repos/${GITHUB_REPOSITORY}/actions/workflows/resolve-required-gates.yml/runs" \
      -f event=workflow_run -f created=">=${history_start}" -f per_page=100 -f page="${history_page}")"
    jq -e '(.workflow_runs | type) == "array" and
      (.total_count | type) == "number" and .total_count > 0 and
      .total_count == (.total_count | floor) and .total_count <= 1000' \
      <<<"${resolver_page}" >/dev/null ||
      fail_closed "Resolver history is malformed or exceeds the 1,000-record API ceiling."
    if (( history_page == 1 )); then
      history_total="$(jq -er '.total_count' <<<"${resolver_page}")"
      history_page_count=$(( (history_total + 99) / 100 ))
    fi
    expected_page_records=$(( history_total - (history_page - 1) * 100 ))
    if (( expected_page_records > 100 )); then expected_page_records=100; fi
    jq -e --argjson total "${history_total}" --argjson count "${expected_page_records}" '
      .total_count == $total and (.workflow_runs | length) == $count' \
      <<<"${resolver_page}" >/dev/null ||
      fail_closed "Resolver history page coverage or total count is inconsistent."
    resolver_pages="$(printf '%s\n%s\n' "${resolver_pages}" "${resolver_page}" | \
      jq -cs '.[0] + .[1].workflow_runs')"
  done
  prior_ids="$(jq -r --argjson workflow_id "${resolver_id}" --argjson current "${GITHUB_RUN_ID}" \
    --argjson total "${history_total}" --arg created "${history_start}" \
    --arg repository "${GITHUB_REPOSITORY}" --arg path "${resolver_path}" \
    --arg prefix "Resolve Required Gates workflow-${workflow_id} head-${head_sha} source-" '
    if length != $total or ([.[].id] | unique | length) != $total or
        (any(.[]; .id == $current) | not) then error("incomplete or duplicate resolver history") else . end
    | if any(.[];
        (.id | type) != "number" or .id <= 0 or .id != (.id | floor) or
        .workflow_id != $workflow_id or .repository.full_name != $repository or .event != "workflow_run" or
        (.display_title | type) != "string" or
        (try ((.created_at | fromdateiso8601) >= ($created | fromdateiso8601)) catch false | not))
      then error("unattributable resolver history") else . end
    | [.[]
      | select(.workflow_id == $workflow_id and .id != $current and
          .repository.full_name == $repository and .event == "workflow_run" and
          (.path == $path or (.path | startswith($path + "@") and length > ($path | length) + 1)) and
          (.display_title | startswith($prefix)) and
          (.display_title | ltrimstr($prefix) | test("^[1-9][0-9]*$")))
      | .id | select(type == "number" and . > 0 and . == floor)] | unique | .[]' <<<"${resolver_pages}")"
  while IFS= read -r prior_id; do
    [[ -n "${prior_id}" ]] || continue
    prior_json="$(jq -cer --argjson id "${prior_id}" '.[] | select(.id == $id)' <<<"${resolver_pages}")"
    jq -e '.run_attempt == 1' <<<"${prior_json}" >/dev/null ||
      fail_closed "Matching resolver history contains a rerun or unknown attempt; explicit recovery is required."
    prior_head="$(jq -er '.head_sha | select(type == "string" and test("^[0-9A-Fa-f]{40}$"))' <<<"${prior_json}")"
    # Under this exact workflow/head concurrency group, a later first-attempt
    # callback still pending or queued cannot have reached admission. Require
    # native creation ordering; listener revision movement cannot start it.
    # Missing steps in any
    # other state remain potentially admitting evidence, never a clean no-op.
    if jq -e --arg created "${current_resolver_created}" '
      (.status == "pending" or .status == "queued") and .conclusion == null and
      (try ((.created_at | fromdateiso8601) > ($created | fromdateiso8601)) catch false)' \
      <<<"${prior_json}" >/dev/null; then
      continue
    fi
    # Native creation timestamps have second precision. A tied callback needs
    # explicit first-attempt job evidence that nothing started, without guessing
    # order from run IDs or ignoring arbitrary missing admission steps.
    equal_time_waiting=false
    if jq -e --arg created "${current_resolver_created}" '
      (.status == "pending" or .status == "queued") and .conclusion == null and
      (try ((.created_at | fromdateiso8601) == ($created | fromdateiso8601)) catch false)' \
      <<<"${prior_json}" >/dev/null; then
      equal_time_waiting=true
    fi
    prior_jobs="$(gh api --method GET "/repos/${GITHUB_REPOSITORY}/actions/runs/${prior_id}/attempts/1/jobs" \
      -f per_page="${evidence_limit}")"
    jq -e --argjson limit "${evidence_limit}" '
      (.jobs | type) == "array" and (.total_count | type) == "number" and
      .total_count >= 0 and .total_count <= $limit and
      .total_count == (.total_count | floor) and (.jobs | length) == .total_count and
      ([.jobs[].id] | unique | length) == .total_count' <<<"${prior_jobs}" >/dev/null ||
      fail_closed "Prior resolver job coverage is malformed or incomplete."
    # Concurrency may evict a pending callback before allocating any job. Only
    # an attributable cancelled first attempt with complete explicit zero-job
    # coverage proves it never reached admission; missing jobs are not proof.
    if jq -e '.status == "completed" and .conclusion == "cancelled"' <<<"${prior_json}" >/dev/null &&
      jq -e '.total_count == 0 and .jobs == []' <<<"${prior_jobs}" >/dev/null; then
      continue
    fi
    prior_admitted_ids="$(jq -ce --argjson prior_id "${prior_id}" --argjson limit "${evidence_limit}" \
      --arg head "${prior_head}" --argjson equal_time_waiting "${equal_time_waiting}" '
      [.jobs[] | select(.name == "Resolve deferred metadata gate")]
      | if length != 1 then error("missing resolver job") else .[0] end
      | if (.id | type) != "number" or .id <= 0 or .id != (.id | floor) or
          .run_id != $prior_id or .head_sha != $head then error("unattributable resolver job") else . end
      | if $equal_time_waiting then
          if .status == "queued" and .conclusion == null and
          has("started_at") and .started_at == null and
          has("completed_at") and .completed_at == null and
          (.steps | type) == "array" and
          all(.steps[]; .status == "queued" and has("conclusion") and .conclusion == null)
          then [] else error("waiting resolver job may have started") end
        elif .conclusion == "skipped" then []
        else
          [.steps[]? | select(.name == "Admit deferred gate reruns")] as $admission
          | if ($admission | length) != 1 or $admission[0].status != "completed" then
              error("missing or incomplete admission")
            elif $admission[0].conclusion == "skipped" then []
            elif $admission[0].conclusion == "success" then
              "Confirm admitted deferred gate jobs " as $prefix
              | [.steps[]? | select((.name | type) == "string" and (.name | startswith($prefix)))] as $confirmation
              | if ($confirmation | length) != 1 or $confirmation[0].status != "completed" or
                  $confirmation[0].conclusion != "success" then error("unconfirmed admission") else . end
              | $confirmation[0].name | ltrimstr($prefix) as $encoded
              | $encoded | fromjson
              | if type != "array" then error("malformed admitted job IDs") else . end
              | if length > $limit or (unique | length) != length or tojson != $encoded or
                  (all(.[]; type == "number" and . > 0 and . == floor) | not) then
                  error("malformed admitted job IDs") else . end
            else error("ambiguous admission") end
        end' <<<"${prior_jobs}")" ||
      fail_closed "Prior matching resolver admission is ambiguous or failed; explicit recovery is required."
    while IFS= read -r prior_admitted_id; do
      previously_admitted_jobs["${prior_admitted_id}"]=true
    done < <(jq -r '.[]' <<<"${prior_admitted_ids}")
  done <<<"${prior_ids}"
}
declare -A previously_admitted_jobs=()
# Return a gate job identity only from a completed first attempt with the distinct
# deferred failure, created no later than this resolver. This keeps every
# admitting resolver inside the retained target's history window even when an
# older source expires. Per-attempt lookup avoids mistaking old jobs for a current
# attempt; any human rerun also consumes the automatic allowance.
deferred_job_identity() {
  local target_run_id="$1" target_json jobs_json
  target_json="$(gh api --method GET "/repos/${GITHUB_REPOSITORY}/actions/runs/${target_run_id}")" || return 1
  if ! jq -e --argjson id "${target_run_id}" --argjson workflow_id "${workflow_id}" \
    --arg repository "${GITHUB_REPOSITORY}" --arg head_repository "${head_repository}" \
    --arg head "${head_sha}" --arg branch "${head_branch}" --arg title "${source_title}" \
    --arg name "${workflow_name}" --arg path "${workflow_file}" --argjson pr "${pr_number}" \
    --arg resolver_created "${current_resolver_created}" '
    .id == $id and .workflow_id == $workflow_id and
    (.name == $name or .name == $title) and
    (.path == $path or (.path | startswith($path + "@") and length > ($path | length) + 1)) and
    .repository.full_name == $repository and .head_repository.full_name == $head_repository and
    .head_sha == $head and .head_branch == $branch and .display_title == $title and
    .event == "pull_request" and .status == "completed" and .conclusion == "failure" and
    .run_attempt == 1 and (.created_at | type) == "string" and
    (try ((.created_at | fromdateiso8601) <= ($resolver_created | fromdateiso8601)) catch false) and
    (.pull_requests | type) == "array" and
    ((.pull_requests | length) == 0 or any(.pull_requests[]; .number == $pr))' \
    <<<"${target_json}" >/dev/null; then
    return 0
  fi
  jobs_json="$(gh api --method GET "/repos/${GITHUB_REPOSITORY}/actions/runs/${target_run_id}/attempts/1/jobs" \
    -f per_page=100 --paginate --slurp)" || return 1
  jq -er --arg name "${gate_name}" --arg head "${head_sha}" --argjson run_id "${target_run_id}" \
    --arg created "$(jq -er .created_at <<<"${target_json}")" '
    if type != "array" or length == 0 then error("malformed or incomplete target jobs")
    elif any(.[]; type != "object" or (.jobs | type) != "array") then error("malformed or incomplete target jobs")
    else
      .[0].total_count as $total
      | if all(.[]; (.total_count | type) == "number" and .total_count >= 0 and
            .total_count == (.total_count | floor) and .total_count == $total)
          and ([.[].jobs[]] | length) == $total
          and all(.[].jobs[]; type == "object" and (.id | type) == "number" and
            .id > 0 and .id == (.id | floor))
          and ([.[].jobs[].id] | unique | length) == $total
        then . else error("malformed or incomplete target jobs") end
    end
    | [.[].jobs[] | select(.name == $name)]
    | if length != 1 then empty else .[0] end
    | select(.run_id == $run_id and .head_sha == $head and .status == "completed" and .conclusion == "failure")
    | select([.steps[]? | select(.name == "Preserve successful required gate on metadata-only edit" and
        .status == "completed" and .conclusion == "success")] | length == 1)
    | select([.steps[]? | select(.name == "Report dependency-deferred required gate" and
        .status == "completed" and .conclusion == "failure")] | length == 1)
    | select(.id | type == "number" and . > 0 and . == floor)
    | [.id, $created] | @tsv' <<<"${jobs_json}" || {
      local jq_status=$?
      # jq exits 4 for an empty selection: an ordinary failure is not deferred.
      [[ "${jq_status}" == 4 ]] || return "${jq_status}"
    }
}

# Freeze eligible native run/job identities before choosing the history floor.
# Later eligibility changes cannot introduce a target outside this covered set.
declare -A candidate_identities=()
candidate_ids=()
candidate_created=()
while IFS= read -r target_run_id; do
  [[ -n "${target_run_id}" ]] || continue
  identity="$(deferred_job_identity "${target_run_id}")"
  [[ -n "${identity}" ]] || continue
  candidate_ids+=("${target_run_id}")
  candidate_identities["${target_run_id}"]="${identity}"
  candidate_created+=("${identity#*$'\t'}")
done <<<"${target_ids}"
if (( ${#candidate_ids[@]} == 0 )); then
  report_eligible false
  exit 0
fi
history_start="$(jq -nr --args '$ARGS.positional | min_by(fromdateiso8601)' "${candidate_created[@]}")"
if [[ "${resolution_phase}" == admit ]]; then
  assert_no_ambiguous_admission
fi

assessment_output="$(mktemp)"
trap 'rm -f "$assessment_output"' EXIT
eligible=false
admitted_job_ids=()
for target_run_id in "${candidate_ids[@]}"; do
  identity="${candidate_identities["${target_run_id}"]}"
  job_id="${identity%%$'\t'*}"
  [[ "${previously_admitted_jobs["${job_id}"]:-}" != true ]] || continue
  : >"${assessment_output}"
  # The child assesses an already-validated target pull_request context. It
  # shares the action's exact source selection and cannot write remote state.
  if ! GITHUB_EVENT_NAME=pull_request GITHUB_RUN_ID="${target_run_id}" \
    BASE_SHA="${base_sha}" HEAD_SHA="${head_sha}" PR_NUMBER="${pr_number}" \
    REQUIRED_GATE_NAME="${gate_name}" EXPECTED_WORKFLOW_NAME="${workflow_name}" \
    EXPECTED_WORKFLOW_FILE="${workflow_filename}" EXPECTED_WORKFLOW_PATH="${workflow_file}" \
    GITHUB_OUTPUT="${assessment_output}" \
    bash "$(dirname "${BASH_SOURCE[0]}")/assess-required-gate.sh"; then
    fail_closed "Original proof is inconclusive or failed; no gate is rerun."
  fi
  [[ "$(<"${assessment_output}")" == 'assessment=success' ]] || continue
  pr_json="$(current_pr)"
  pr_is_current "${pr_json}" || continue
  # Immediate re-read under workflow-level serialization. This suppresses
  # duplicates practically; the API does not document conditional POSTs.
  fresh_identity="$(deferred_job_identity "${target_run_id}")"
  [[ "${fresh_identity}" == "${identity}" ]] || continue
  eligible=true
  [[ "${resolution_phase}" != preflight ]] || continue
  if ! gh api --method POST "/repos/${GITHUB_REPOSITORY}/actions/jobs/${job_id}/rerun" >/dev/null; then
    fail_closed "Targeted rerun outcome is ambiguous or rejected; do not replay the POST. Explicit recovery is required."
  fi
  admitted_job_ids+=("${job_id}")
  previously_admitted_jobs["${job_id}"]=true
  echo "Admitted one targeted rerun of deferred gate job ${job_id} in run ${target_run_id}."
done
if [[ "${resolution_phase}" == preflight ]]; then
  report_eligible "${eligible}"
else
  # Publish only after all POSTs were acknowledged. Partial/ambiguous execution
  # has no successful confirmation step and blocks subsequent automation.
  admitted_ids_json="$(jq -cn --args '$ARGS.positional | map(tonumber)' "${admitted_job_ids[@]}")"
  jq -e --argjson limit "${evidence_limit}" '
    type == "array" and length <= $limit and (unique | length) == length and
    all(.[]; type == "number" and . > 0 and . == floor)' <<<"${admitted_ids_json}" >/dev/null ||
    fail_closed "Acknowledged job IDs are not a bounded attributable set."
  printf 'accepted_job_ids=%s\n' "${admitted_ids_json}" >>"${GITHUB_OUTPUT}"
fi
