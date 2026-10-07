#!/usr/bin/env bash
set -euo pipefail

if [[ "${GITHUB_EVENT_NAME:-}" != "pull_request" ]]; then
  echo "Required-gate preservation requires pull_request context; refusing to preserve." >&2
  exit 1
fi
for required_variable in GH_TOKEN BASE_SHA HEAD_SHA PR_NUMBER REQUIRED_GATE_NAME EXPECTED_WORKFLOW_NAME EXPECTED_WORKFLOW_FILE EXPECTED_WORKFLOW_PATH GITHUB_REPOSITORY GITHUB_RUN_ID; do
  if [[ -z "${!required_variable:-}" ]]; then
    echo "Required-gate preservation is missing ${required_variable}; refusing to preserve." >&2
    exit 1
  fi
done
is_github_sha() {
  [[ "$1" =~ ^[0-9A-Fa-f]{40}$ ]]
}
if ! is_github_sha "${BASE_SHA}"; then
  echo "Required-gate preservation requires a valid pull request base SHA; refusing to preserve." >&2
  exit 1
fi
if ! is_github_sha "${HEAD_SHA}"; then
  echo "Required-gate preservation requires a valid pull request head SHA; refusing to preserve." >&2
  exit 1
fi
if [[ ! "${PR_NUMBER}" =~ ^[1-9][0-9]*$ ]]; then
  echo "Required-gate preservation requires a valid pull request number; refusing to preserve." >&2
  exit 1
fi
if [[ ! "${EXPECTED_WORKFLOW_FILE}" =~ ^[A-Za-z0-9._-]+\.(yml|yaml)$ ||
  ! "${EXPECTED_WORKFLOW_PATH}" =~ ^\.github/workflows/[A-Za-z0-9._-]+\.(yml|yaml)$ ||
  "${EXPECTED_WORKFLOW_PATH}" != ".github/workflows/${EXPECTED_WORKFLOW_FILE}" ]]; then
  echo "Required-gate preservation received invalid workflow or job identity; refusing to preserve." >&2
  exit 1
fi
expected_display_title="${EXPECTED_WORKFLOW_NAME} pr-${PR_NUMBER} base-${BASE_SHA} head-${HEAD_SHA}"
report_assessment() {
  if [[ -n "${GITHUB_OUTPUT:-}" ]]; then
    printf 'assessment=%s\n' "$1" >>"${GITHUB_OUTPUT}"
  else
    echo "One-assessment preservation requires GITHUB_OUTPUT; refusing to preserve." >&2
    exit 1
  fi
}
defer_dependency() {
  echo "Required-gate proof is not yet conclusive; deferring without polling." >&2
  report_assessment dependency-deferred
  exit 0
}
is_retryable_gh_failure() {
  local exit_status="$1"
  local error_text="${2,,}"
  if [[ "$error_text" =~ http[[:space:]]+5[0-9]{2} ]]; then
    return 0
  fi
  if [[ "$error_text" =~ http[[:space:]]+(403|429) ]] &&
    [[ "$error_text" =~ (rate[[:space:]-]*limit|abuse) ]]; then
    return 0
  fi
  if [[ "$exit_status" -ne 0 ]] &&
    [[ "$error_text" =~ (could[[:space:]]not[[:space:]]resolve|failed[[:space:]]to[[:space:]](connect|make[[:space:]]request)|error[[:space:]]connecting|unable[[:space:]]to[[:space:]]connect|connection[[:space:]](reset|refused|timed|closed)|network[[:space:]]is[[:space:]]unreachable|timed[[:space:]]out|timeout|temporary[[:space:]]failure|tls|eof) ]]; then
    return 0
  fi
  return 1
}

is_github_timestamp() {
  [[ "$1" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$ ]]
}

api_error_file="$(mktemp)"
trap 'rm -f "$api_error_file"' EXIT
expected_workflow_id=""
declare -A workflow_run_cache=()
declare -A job_cache=()

# A metadata-only run emits this same gate name. Its preservation step
# identifies the recursive run; substantive gates skip that step and
# remain authoritative, including failures.
: >"${api_error_file}"
if [[ -z "${expected_workflow_id}" ]]; then
  set +e
  workflow_json="$(gh api --method GET \
    "/repos/${GITHUB_REPOSITORY}/actions/workflows/${EXPECTED_WORKFLOW_FILE}" \
    2>"${api_error_file}")"
  workflow_status=$?
  set -e
  if [[ "${workflow_status}" -ne 0 ]]; then
    api_error="$(<"${api_error_file}")"
    if ! is_retryable_gh_failure "${workflow_status}" "${api_error}"; then
      echo "Permanent GitHub API/configuration failure while resolving the expected ${EXPECTED_WORKFLOW_PATH}; refusing to preserve." >&2
      [[ -z "${api_error}" ]] || printf '%s\n' "${api_error}" >&2
      exit 1
    fi
    echo "GitHub API failure while resolving the expected workflow identity; refusing to preserve." >&2
    [[ -z "${api_error}" ]] || printf '%s\n' "${api_error}" >&2
    exit 1
  fi
  set +e
  expected_workflow_id="$(jq -r \
    --arg expected_name "${EXPECTED_WORKFLOW_NAME}" \
    --arg expected_path "${EXPECTED_WORKFLOW_PATH}" \
    'if (.id | type) == "number" and
        .name == $expected_name and
        .path == $expected_path
      then (.id | tostring) else "__missing__" end' \
    <<<"${workflow_json}" 2>>"${api_error_file}")"
  workflow_query_status=$?
  set -e
  if [[ "${workflow_query_status}" -ne 0 ||
    ! "${expected_workflow_id}" =~ ^[0-9]+$ ]]; then
    echo "GitHub API returned malformed expected workflow identity; refusing to preserve." >&2
    exit 1
  fi
fi
set +e
check_runs_json="$(gh api --method GET \
  "/repos/${GITHUB_REPOSITORY}/commits/${HEAD_SHA}/check-runs" \
  -f check_name="${REQUIRED_GATE_NAME}" \
  -f filter=all \
  -f per_page=100 \
  --paginate \
  --slurp \
  2>"${api_error_file}")"
api_status=$?
set -e
if [[ "${api_status}" -ne 0 ]]; then
  api_error="$(<"${api_error_file}")"
  if ! is_retryable_gh_failure "${api_status}" "${api_error}"; then
    echo "Permanent GitHub API/configuration failure while checking the prior ${REQUIRED_GATE_NAME}; refusing to retry." >&2
    [[ -z "${api_error}" ]] || printf '%s\n' "${api_error}" >&2
    exit 1
  fi
  echo "GitHub API failure while checking prior required-gate evidence; refusing to preserve." >&2
  [[ -z "${api_error}" ]] || printf '%s\n' "${api_error}" >&2
  exit 1
fi

set +e
jq -e '
  if type != "array" or length == 0 then false
  elif any(.[]; type != "object" or (.check_runs | type) != "array") then false
  else
    .[0].total_count as $total
    | all(.[]; (.total_count | type) == "number" and .total_count >= 0 and
        .total_count == (.total_count | floor) and .total_count == $total)
      and ([.[].check_runs[]] | length) == $total
      and all(.[].check_runs[]; type == "object" and (.id | type) == "number" and
        .id > 0 and .id == (.id | floor))
      and ([.[].check_runs[].id] | unique | length) == $total
  end
' <<<"${check_runs_json}" >/dev/null 2>>"${api_error_file}"
check_runs_shape_status=$?
set -e
if [[ "${check_runs_shape_status}" -ne 0 ]]; then
  echo "GitHub API returned malformed or incomplete check-run data for the prior ${REQUIRED_GATE_NAME}; refusing to preserve." >&2
  exit 1
fi

set +e
candidate_rows="$(jq -r --arg run_id "${GITHUB_RUN_ID}" '
      [.[].check_runs[]?
        | select(((.details_url // "") | contains("/actions/runs/\($run_id)/") | not))
      ] as $candidates
      | $candidates[]
      | [
          (.app.slug // "__missing__"),
          (.name // "__missing__"),
          (.details_url // "__missing__"),
          (if (.id | type) == "number" then (.id | tostring) else "__missing__" end),
          (.status // "__missing__"),
          (.conclusion // "__null__"),
          (.completed_at // "__null__"),
          (.started_at // "__null__")
        ]
      | @tsv' <<<"${check_runs_json}" 2>>"${api_error_file}")"
query_status=$?
set -e
if [[ "${query_status}" -ne 0 ]]; then
  echo "GitHub API returned malformed check-run data for the prior ${REQUIRED_GATE_NAME}; refusing to preserve." >&2
  exit 1
fi

authoritative_rows=()
pending_rows=()
job_lookup_retryable=false
retry_reason=""
retry_error_text=""
candidate_ambiguous=false
while IFS=$'\t' read -r app_slug check_name details_url check_run_id check_status conclusion completed_at started_at; do
  if [[ -z "${app_slug}${check_name}${details_url}${check_run_id}${check_status}${conclusion}${completed_at}${started_at}" ]]; then
    continue
  fi
  if [[ "${app_slug}" != "github-actions" ||
    "${check_name}" == "__missing__" ||
    "${check_name}" != "${REQUIRED_GATE_NAME}" ||
    "${details_url}" == "__missing__" ||
    "${check_run_id}" == "__missing__" ||
    "${check_status}" == "__missing__" ||
    ! "${check_run_id}" =~ ^[0-9]+$ ]]; then
    candidate_ambiguous=true
    continue
  fi
  [[ "${conclusion}" == "__null__" ]] && conclusion=""
  [[ "${completed_at}" == "__null__" ]] && completed_at=""
  [[ "${started_at}" == "__null__" ]] && started_at=""
  if [[ ! "${details_url}" =~ /actions/runs/([0-9]+)/job/([0-9]+)([?#].*)?$ ]]; then
    candidate_ambiguous=true
    continue
  fi
  workflow_run_id="${BASH_REMATCH[1]}"
  job_id="${BASH_REMATCH[2]}"

  workflow_run_json="${workflow_run_cache[${workflow_run_id}]:-}"
  if [[ -z "${workflow_run_json}" ]]; then
    : >"${api_error_file}"
    set +e
    workflow_run_json="$(gh api --method GET \
      "/repos/${GITHUB_REPOSITORY}/actions/runs/${workflow_run_id}" \
      2>"${api_error_file}")"
    workflow_run_status=$?
    set -e
    if [[ "${workflow_run_status}" -ne 0 ]]; then
      workflow_run_error="$(<"${api_error_file}")"
      if is_retryable_gh_failure "${workflow_run_status}" "${workflow_run_error}"; then
        job_lookup_retryable=true
        retry_reason="workflow-run API lookup"
        retry_error_text="${workflow_run_error}"
        break
      fi
      echo "Permanent GitHub API/configuration failure while identifying prior ${REQUIRED_GATE_NAME} workflow run; refusing to preserve." >&2
      [[ -z "${workflow_run_error}" ]] || printf '%s\n' "${workflow_run_error}" >&2
      exit 1
    fi
    workflow_run_cache["${workflow_run_id}"]="${workflow_run_json}"
  fi
  set +e
  workflow_run_identity_state="$(jq -r \
    --arg expected_id "${workflow_run_id}" \
    --arg expected_workflow_id "${expected_workflow_id}" \
    --arg expected_name "${EXPECTED_WORKFLOW_NAME}" \
    --arg expected_path "${EXPECTED_WORKFLOW_PATH}" \
    --arg expected_head "${HEAD_SHA}" \
    --arg expected_base "${BASE_SHA}" \
    --arg expected_repository "${GITHUB_REPOSITORY}" \
    --arg expected_pr "${PR_NUMBER}" \
    --arg expected_display_title "${expected_display_title}" \
    '. as $run
    | ((try (.display_title | capture("^(?<name>.*) pr-(?<pr>[1-9][0-9]*) base-(?<base>[0-9A-Fa-f]{40}) head-(?<head>[0-9A-Fa-f]{40})$")) catch null) // {}) as $title
    | if ($run.id | type) == "number" and
          ($run.workflow_id | type) == "number" and
          ($run.id == ($expected_id | tonumber)) and
          ($run.workflow_id == ($expected_workflow_id | tonumber)) and
        ($run.name == $expected_name or $run.name == $expected_display_title) and
        (
          $run.path == $expected_path or
          (
            ($run.path | startswith($expected_path + "@")) and
            (($run.path | ltrimstr($expected_path + "@")) | test("^.+$"))
          )
        ) and
        $run.head_sha == $expected_head and
        $run.repository.full_name == $expected_repository and
        $run.event == "pull_request" and
        (($run.pull_requests // null) | type) == "array" and
        (
          (($run.pull_requests | length) > 0 and
            any($run.pull_requests[]; ((.number // null) | tostring) == $expected_pr)) or
          (($run.pull_requests | length) == 0 and
            ($run.display_title // null) == $expected_display_title)
        )
      then
        if ($run.display_title // null) == $expected_display_title then
          "current"
        elif (($run.pull_requests | length) > 0) and
          $title.name == $expected_name and $title.pr == $expected_pr and
          any($run.pull_requests[];
            ((.number // null) | tostring) == $expected_pr and
            (.base.sha // "") == $title.base and
            (.head.sha // "") == $title.head and
            ((.base.sha // "") != $expected_base or (.head.sha // "") != $expected_head))
        then
          "stale"
        else
          "invalid"
        end
      else "invalid" end' \
    <<<"${workflow_run_json}" 2>>"${api_error_file}")"
  workflow_run_query_status=$?
  set -e
  if [[ "${workflow_run_query_status}" -ne 0 ||
    ( "${workflow_run_identity_state}" != "current" &&
      "${workflow_run_identity_state}" != "stale" ) ]]; then
    candidate_ambiguous=true
    continue
  fi
  if [[ "${workflow_run_identity_state}" == "stale" ]]; then
    continue
  fi
  job_json="${job_cache[${job_id}]:-}"
  job_is_pending=false
  job_was_fetched=false
  case "${check_status}" in
    queued|in_progress|requested|waiting|pending) job_is_pending=true ;;
  esac
  if [[ -z "${job_json}" || "${job_is_pending}" == "true" ]]; then
    : >"${api_error_file}"
    set +e
    job_json="$(gh api --method GET \
      "/repos/${GITHUB_REPOSITORY}/actions/jobs/${job_id}" \
      2>"${api_error_file}")"
    job_status=$?
    job_was_fetched=true
    set -e
    if [[ "${job_status}" -ne 0 ]]; then
      job_error="$(<"${api_error_file}")"
      if is_retryable_gh_failure "${job_status}" "${job_error}"; then
        job_lookup_retryable=true
        retry_reason="job API lookup"
        retry_error_text="${job_error}"
        break
      fi
      echo "Permanent GitHub API/configuration failure while identifying a prior ${REQUIRED_GATE_NAME} run; refusing to preserve." >&2
      [[ -z "${job_error}" ]] || printf '%s\n' "${job_error}" >&2
      exit 1
    fi
  fi

  set +e
  job_identity_ok="$(jq -r \
    --arg expected_job_id "${job_id}" \
    --arg expected_run_id "${workflow_run_id}" \
    --arg expected_gate_name "${REQUIRED_GATE_NAME}" \
    --arg expected_workflow_name "${EXPECTED_WORKFLOW_NAME}" \
    --arg expected_display_title "${expected_display_title}" \
    --arg expected_head "${HEAD_SHA}" \
    --arg expected_check_run_id "${check_run_id}" \
    'if (.id | type) == "number" and
          (.run_id | type) == "number" and
          .id == ($expected_job_id | tonumber) and
          .run_id == ($expected_run_id | tonumber) and
          .name == $expected_gate_name and
          (.workflow_name == $expected_workflow_name or
            .workflow_name == $expected_display_title) and
          .head_sha == $expected_head and
          ((.check_run_url // "") | endswith("/check-runs/\($expected_check_run_id)"))
      then "true" else "false" end' \
    <<<"${job_json}" 2>>"${api_error_file}")"
  job_identity_query_status=$?
  set -e
  if [[ "${job_identity_query_status}" -ne 0 ||
    "${job_identity_ok}" != "true" ]]; then
    candidate_ambiguous=true
    continue
  fi
  if [[ "${job_was_fetched}" == "true" ]]; then
    job_cache["${job_id}"]="${job_json}"
  fi
  set +e
  preserve_step_conclusion="$(jq -r '
    [.steps[]?
      | select(.name == "Preserve successful required gate on metadata-only edit")
      | (.conclusion // "")
    ]
    | if length == 1 then .[0] else "unknown" end
  ' <<<"${job_json}" 2>>"${api_error_file}")"
  job_query_status=$?
  set -e
  if [[ "${job_query_status}" -ne 0 ]]; then
    candidate_ambiguous=true
    continue
  fi
  if [[ "${preserve_step_conclusion}" == "unknown" ||
    -z "${preserve_step_conclusion}" ]]; then
    if [[ "${check_status}" == "completed" ]]; then
      case "${conclusion}" in
        cancelled|skipped|stale) continue ;;
      esac
      # An unresolved completed snapshot may hide substantive failure.
      candidate_ambiguous=true
      continue
    elif [[ "${job_is_pending}" == "true" ]]; then
      : # A nonterminal job may not have reached its preservation step yet.
    else
      candidate_ambiguous=true
      continue
    fi
  elif [[ "${preserve_step_conclusion}" != "skipped" ]]; then
    case "${preserve_step_conclusion}" in
      success|failure|neutral|cancelled|timed_out|action_required|stale)
        continue
        ;;
      *) candidate_ambiguous=true; continue ;;
    esac
  fi

  case "${check_status}" in
    completed)
      case "${conclusion}" in
        success|failure|neutral|cancelled|skipped|timed_out|action_required|stale) ;;
        *) candidate_ambiguous=true; continue ;;
      esac
      if [[ -n "${conclusion}" && -n "${completed_at}" &&
        -n "${started_at}" ]] &&
        is_github_timestamp "${completed_at}" &&
        is_github_timestamp "${started_at}"; then
        if [[ "${conclusion}" != "cancelled" &&
          "${conclusion}" != "skipped" &&
          "${conclusion}" != "stale" ]]; then
          authoritative_rows+=("${completed_at}"$'\t'"${workflow_run_id}"$'\t'"${check_run_id}"$'\t'"${job_id}"$'\tcompleted\t'"${conclusion}")
        fi
      else
        candidate_ambiguous=true
      fi
      ;;
    queued|in_progress|requested|waiting|pending)
      if [[ -z "${conclusion}" && -z "${completed_at}" ]] &&
        { [[ -z "${started_at}" ]] || is_github_timestamp "${started_at}"; }; then
        started_at="${started_at:-9999-12-31T23:59:59Z}"
        pending_rows+=("${started_at}"$'\t'"${workflow_run_id}"$'\t'"${check_run_id}"$'\t'"${job_id}"$'\tpending\t'"${check_status}"$'\t'"${preserve_step_conclusion}")
      else
        candidate_ambiguous=true
      fi
      ;;
    *) candidate_ambiguous=true ;;
  esac
done <<<"${candidate_rows}"

if [[ "${job_lookup_retryable}" == "true" ]]; then
  echo "Required-gate assessment could not resolve ${retry_reason}; refusing to preserve." >&2
  [[ -z "${retry_error_text}" ]] || printf '%s\n' "${retry_error_text}" >&2
  exit 1
fi
if [[ "${candidate_ambiguous}" == "true" ]]; then
  echo "Ambiguous prior ${REQUIRED_GATE_NAME} run metadata; refusing to preserve." >&2
  exit 1
fi

prior_status=""
prior_conclusion=""
if ((${#authoritative_rows[@]} > 0)); then
  prior_row="$(printf '%s\n' "${authoritative_rows[@]}" | LC_ALL=C sort -t $'\t' -k1,1 -k2,2n -k3,3n -k4,4n | tail -n 1)"
elif ((${#pending_rows[@]} > 0)); then
  prior_row="$(printf '%s\n' "${pending_rows[@]}" | LC_ALL=C sort -t $'\t' -k1,1 -k2,2n -k3,3n -k4,4n | tail -n 1)"
else
  prior_row=$'none\tnone\tnone\tnone\tnone\t'
fi
IFS=$'\t' read -r _prior_sort _prior_run _prior_check _prior_job prior_status prior_conclusion prior_preserve_step <<< "${prior_row}"
if [ "${prior_status}" = "completed" ]; then
  if [ "${prior_conclusion}" = "success" ]; then
    report_assessment success
    exit 0
  fi
  echo "Prior ${REQUIRED_GATE_NAME} for unchanged head ${HEAD_SHA} concluded ${prior_conclusion:-unknown}." >&2
  exit 1
fi
if [[ "${prior_status}" == none || "${prior_status}" == pending ]]; then
  defer_dependency
fi
echo "Unexpected prior ${REQUIRED_GATE_NAME} selection state ${prior_status:-unknown}; refusing to preserve." >&2
exit 1
