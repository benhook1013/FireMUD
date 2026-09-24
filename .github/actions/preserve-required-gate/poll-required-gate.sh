#!/usr/bin/env bash
set -euo pipefail

if [[ "${GITHUB_EVENT_NAME:-}" != "pull_request" ]]; then
  echo "Required-gate preservation requires pull_request context; refusing to poll." >&2
  exit 1
fi
for required_variable in GH_TOKEN BASE_SHA HEAD_SHA PR_NUMBER REQUIRED_GATE_NAME EXPECTED_WORKFLOW_NAME EXPECTED_WORKFLOW_FILE EXPECTED_WORKFLOW_PATH GITHUB_REPOSITORY GITHUB_RUN_ID; do
  if [[ -z "${!required_variable:-}" ]]; then
    echo "Required-gate preservation is missing ${required_variable}; refusing to poll." >&2
    exit 1
  fi
done
is_github_sha() {
  [[ "$1" =~ ^[0-9A-Fa-f]{40}$ ]]
}
if ! is_github_sha "${BASE_SHA}"; then
  echo "Required-gate preservation requires a valid pull request base SHA; refusing to poll." >&2
  exit 1
fi
if ! is_github_sha "${HEAD_SHA}"; then
  echo "Required-gate preservation requires a valid pull request head SHA; refusing to poll." >&2
  exit 1
fi
if [[ ! "${PR_NUMBER}" =~ ^[1-9][0-9]*$ ]]; then
  echo "Required-gate preservation requires a valid pull request number; refusing to poll." >&2
  exit 1
fi
if [[ ! "${EXPECTED_WORKFLOW_FILE}" =~ ^[A-Za-z0-9._-]+\.(yml|yaml)$ ||
  ! "${EXPECTED_WORKFLOW_PATH}" =~ ^\.github/workflows/[A-Za-z0-9._-]+\.(yml|yaml)$ ||
  "${EXPECTED_WORKFLOW_PATH}" != ".github/workflows/${EXPECTED_WORKFLOW_FILE}" ]]; then
  echo "Required-gate preservation received invalid workflow or job identity; refusing to poll." >&2
  exit 1
fi
expected_display_title="${EXPECTED_WORKFLOW_NAME} pr-${PR_NUMBER} base-${BASE_SHA} head-${HEAD_SHA}"
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
# Every required-gate caller allows 95 minutes. Retain the shorter missing-run
# budget, but leave five minutes of setup/cleanup room when a verified
# substantive predecessor's gate is delayed behind its dependency graph.
# Neither budget permits an unverified candidate to satisfy the required check.
poll_interval_seconds=15
poll_timeout_seconds=$((23 * 60))
active_poll_timeout_seconds=$((90 * 60))
max_attempts=92
active_max_attempts=$((active_poll_timeout_seconds / poll_interval_seconds))
poll_attempt_limit="${max_attempts}"
poll_started_seconds="${SECONDS}"
poll_deadline=$((poll_started_seconds + poll_timeout_seconds))
active_poll_deadline=$((poll_started_seconds + active_poll_timeout_seconds))
max_preservation_step_refresh_attempts=8
expected_workflow_id=""
declare -A workflow_run_cache=()
declare -A job_cache=()
declare -A terminal_job_cache=()
declare -A preservation_step_refresh_attempts=()
declare -A verified_substantive_run_cache=()
last_uncertain_substantive_workflow=false
substantive_wait_extended=false
sleep_until_poll_deadline() {
  local remaining=$((poll_deadline - SECONDS))
  if (( remaining <= 0 )); then
    return 1
  fi
  local delay="${poll_interval_seconds}"
  if (( delay > remaining )); then
    delay=${remaining}
    echo "Polling delay bounded to ${delay}s by the deadline." >&2
  fi
  sleep "${delay}"
}
extend_for_active_substantive_run() {
  if [[ "${poll_attempt_limit}" != "${active_max_attempts}" ]]; then
    poll_attempt_limit="${active_max_attempts}"
    poll_deadline="${active_poll_deadline}"
    substantive_wait_extended=true
    echo "Verified substantive workflow is active before ${REQUIRED_GATE_NAME} was published; extending the bounded wait to ${active_poll_timeout_seconds}s." >&2
  fi
}

# Determine whether an active workflow for the exact PR/base/head tuple is
# substantive. Metadata-only runs skip their `changes` job, so a matching run
# is not enough. A queued or in-progress change detector is intentionally
# inconclusive: do not extend the wait until the detector has completed
# successfully and has therefore proved that this is a substantive run.
find_active_substantive_workflow() {
  active_substantive_workflow=false
  uncertain_substantive_workflow=false
  active_workflow_runs_json='[]'
  # The workflow-runs endpoint accepts one status filter per request. Query
  # each active status separately so polling does not walk completed history;
  # keep pagination because a status can still contain more than 100 runs.
  for active_workflow_status in in_progress queued requested waiting pending; do
    : >"${api_error_file}"
    set +e
    active_workflow_status_json="$(gh api --method GET \
      "/repos/${GITHUB_REPOSITORY}/actions/workflows/${EXPECTED_WORKFLOW_FILE}/runs" \
      -f event=pull_request \
      -f status="${active_workflow_status}" \
      -f per_page=100 \
      --paginate \
      --slurp \
      2>"${api_error_file}")"
    active_workflow_status_result=$?
    set -e
    if [[ "${active_workflow_status_result}" -ne 0 ]]; then
      active_workflow_status_error="$(<"${api_error_file}")"
      if is_retryable_gh_failure "${active_workflow_status_result}" "${active_workflow_status_error}"; then
        uncertain_substantive_workflow=true
        echo "Retryable GitHub API failure while checking for an active substantive ${EXPECTED_WORKFLOW_NAME} run; retaining the short fail-closed wait." >&2
        return 0
      fi
      echo "Permanent GitHub API/configuration failure while checking active substantive workflow runs; refusing to preserve." >&2
      [[ -z "${active_workflow_status_error}" ]] || printf '%s\n' "${active_workflow_status_error}" >&2
      exit 1
    fi
    set +e
    active_workflow_runs_json="$(jq -n \
      --slurpfile existing /dev/fd/3 \
      --slurpfile next /dev/fd/4 \
      '$existing[0] + $next[0]' 3<<<"${active_workflow_runs_json}" \
      4<<<"${active_workflow_status_json}" 2>>"${api_error_file}")"
    active_workflow_runs_combine_status=$?
    set -e
    if [[ "${active_workflow_runs_combine_status}" -ne 0 ]]; then
      echo "GitHub API returned malformed active workflow-run data; refusing to preserve." >&2
      exit 1
    fi
  done
  set +e
  jq -e '
    type == "array"
    and all(.[]; type == "object" and (.workflow_runs | type) == "array")
  ' <<<"${active_workflow_runs_json}" >/dev/null 2>>"${api_error_file}"
  active_workflow_runs_shape_status=$?
  set -e
  if [[ "${active_workflow_runs_shape_status}" -ne 0 ]]; then
    echo "GitHub API returned malformed active workflow-run data; refusing to preserve." >&2
    exit 1
  fi

  set +e
  active_run_rows="$(jq -r \
    --arg current_run_id "${GITHUB_RUN_ID}" \
    --arg expected_workflow_id "${expected_workflow_id}" \
    --arg expected_workflow_name "${EXPECTED_WORKFLOW_NAME}" \
    --arg expected_workflow_path "${EXPECTED_WORKFLOW_PATH}" \
    --arg expected_head "${HEAD_SHA}" \
    --arg expected_base "${BASE_SHA}" \
    --arg expected_repository "${GITHUB_REPOSITORY}" \
    --arg expected_display_title "${expected_display_title}" \
    --arg expected_pr "${PR_NUMBER}" '
      .[].workflow_runs[]?
      | select((.id | type) == "number")
      | select((.id | tostring) != $current_run_id)
      | select((.workflow_id | type) == "number" and (.workflow_id | tostring) == $expected_workflow_id)
      | select(.name == $expected_workflow_name or .name == $expected_display_title)
      | select(.path == $expected_workflow_path or
          ((.path | startswith($expected_workflow_path + "@")) and
            ((.path | ltrimstr($expected_workflow_path + "@")) | test("^.+$"))))
      | select(.repository.full_name == $expected_repository)
      | select(.event == "pull_request")
      | select((.display_title // "") == $expected_display_title)
      | select((.status // "") | IN("queued", "in_progress", "requested", "waiting", "pending"))
      | select(((.pull_requests // null) | type) == "array")
      | select(any(.pull_requests[]?;
          ((.number // null) | tostring) == $expected_pr and
          ((.base.sha // "") == $expected_base) and
          ((.head.sha // "") == $expected_head)) or
        ((.pull_requests | length) == 0 and
          (.display_title == $expected_display_title)))
      | [(.id | tostring), (.status // "__missing__")]
      | @tsv' <<<"${active_workflow_runs_json}" 2>>"${api_error_file}")"
  active_run_rows_status=$?
  set -e
  if [[ "${active_run_rows_status}" -ne 0 ]]; then
    echo "GitHub API returned malformed active workflow-run identity data; refusing to preserve." >&2
    exit 1
  fi

  while IFS=$'\t' read -r active_run_id active_run_status; do
    [[ -z "${active_run_id}${active_run_status}" ]] && continue
    if [[ "${verified_substantive_run_cache[${active_run_id}]:-false}" == "true" ]]; then
      active_substantive_workflow=true
      continue
    fi
    : >"${api_error_file}"
    set +e
    active_run_jobs_json="$(gh api --method GET \
      "/repos/${GITHUB_REPOSITORY}/actions/runs/${active_run_id}/jobs" \
      -f per_page=100 \
      --paginate \
      --slurp \
      2>"${api_error_file}")"
    active_run_jobs_status=$?
    set -e
    if [[ "${active_run_jobs_status}" -ne 0 ]]; then
      active_run_jobs_error="$(<"${api_error_file}")"
      if is_retryable_gh_failure "${active_run_jobs_status}" "${active_run_jobs_error}"; then
        uncertain_substantive_workflow=true
        continue
      fi
      echo "Permanent GitHub API/configuration failure while identifying the active workflow's change-detection job; refusing to preserve." >&2
      [[ -z "${active_run_jobs_error}" ]] || printf '%s\n' "${active_run_jobs_error}" >&2
      exit 1
    fi
    set +e
    jq -e '
      type == "array"
      and all(.[]; type == "object" and (.jobs | type) == "array")
    ' <<<"${active_run_jobs_json}" >/dev/null 2>>"${api_error_file}"
    active_run_jobs_shape_status=$?
    set -e
    if [[ "${active_run_jobs_shape_status}" -ne 0 ]]; then
      echo "GitHub API returned malformed active workflow job data; refusing to preserve." >&2
      exit 1
    fi
    set +e
    change_job_rows="$(jq -r '
      [.[].jobs[]?
        | select((.name // "") | test("^Detect .+-Relevant Changes$"))
        | [(.status // "__missing__"), (.conclusion // "")]
      ]
      | .[]
      | @tsv' <<<"${active_run_jobs_json}" 2>>"${api_error_file}")"
    change_job_rows_status=$?
    set -e
    if [[ "${change_job_rows_status}" -ne 0 ]]; then
      echo "GitHub API returned malformed change-detection job data; refusing to preserve." >&2
      exit 1
    fi
    change_job_count=0
    change_job_completed_successfully=false
    change_job_inconclusive=false
    while IFS=$'\t' read -r change_job_status change_job_conclusion; do
      [[ -z "${change_job_status}${change_job_conclusion}" ]] && continue
      change_job_count=$((change_job_count + 1))
      if [[ "${change_job_status}" == "completed" && "${change_job_conclusion}" == "success" ]]; then
        change_job_completed_successfully=true
      elif [[ "${change_job_status}" != "completed" ]]; then
        change_job_inconclusive=true
      fi
    done <<<"${change_job_rows}"
    if (( change_job_count != 1 )); then
      uncertain_substantive_workflow=true
    elif [[ "${change_job_completed_successfully}" == "true" ]]; then
      active_substantive_workflow=true
      verified_substantive_run_cache["${active_run_id}"]=true
    elif [[ "${change_job_inconclusive}" == "true" ]]; then
      uncertain_substantive_workflow=true
    fi
  done <<<"${active_run_rows}"
}

refresh_active_workflow_state() {
  local should_discover=false
  if [[ "${substantive_wait_extended}" != "true" ]] &&
    { (( attempt == 1 || (attempt - 1) % 4 == 0 )) || (( attempt == poll_attempt_limit )); }; then
    should_discover=true
  fi
  if [[ "${should_discover}" == "true" ]]; then
    find_active_substantive_workflow
    last_uncertain_substantive_workflow="${uncertain_substantive_workflow}"
  else
    active_substantive_workflow=false
    uncertain_substantive_workflow="${last_uncertain_substantive_workflow}"
  fi
}

for attempt in $(seq 1 "${active_max_attempts}"); do
  if (( SECONDS >= poll_deadline )); then
    echo "Timed out waiting for the prior ${REQUIRED_GATE_NAME} on unchanged head ${HEAD_SHA}." >&2
    exit 1
  fi
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
      echo "Retryable GitHub API failure while resolving the expected ${EXPECTED_WORKFLOW_PATH}; retrying attempt ${attempt}/${poll_attempt_limit}." >&2
      if [ "${attempt}" -eq "${poll_attempt_limit}" ] || (( SECONDS >= poll_deadline )); then
        echo "Timed out resolving the expected workflow identity for ${REQUIRED_GATE_NAME}." >&2
        exit 1
      fi
      if ! sleep_until_poll_deadline; then
        echo "Timed out resolving the expected workflow identity for ${REQUIRED_GATE_NAME}." >&2
        exit 1
      fi
      continue
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
    echo "Retryable GitHub API failure while checking the prior ${REQUIRED_GATE_NAME}; retrying attempt ${attempt}/${poll_attempt_limit}." >&2
    if [ "${attempt}" -eq "${poll_attempt_limit}" ] || (( SECONDS >= poll_deadline )); then
      echo "Timed out waiting for the prior ${REQUIRED_GATE_NAME} on unchanged head ${HEAD_SHA}." >&2
      exit 1
    fi
    if ! sleep_until_poll_deadline; then
      echo "Timed out waiting for the prior ${REQUIRED_GATE_NAME} on unchanged head ${HEAD_SHA}." >&2
      exit 1
    fi
    continue
  fi

  set +e
  jq -e '
    type == "array"
    and all(.[]; type == "object" and (.check_runs | type) == "array")
  ' <<<"${check_runs_json}" >/dev/null 2>>"${api_error_file}"
  check_runs_shape_status=$?
  set -e
  if [[ "${check_runs_shape_status}" -ne 0 ]]; then
    echo "GitHub API returned malformed check-run data for the prior ${REQUIRED_GATE_NAME}; refusing to preserve." >&2
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
    if [[ -z "${job_json}" || "${job_is_pending}" == "true" ||
      "${terminal_job_cache[${job_id}]:-false}" != "true" ]]; then
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
        # A completed check can briefly precede the jobs API's final
        # step snapshot. Restart the candidate scan and rebuild its
        # rows through the existing retry path for bounded attempts.
        preservation_step_refresh_attempts["${job_id}"]=$((
          ${preservation_step_refresh_attempts[${job_id}]:-0} + 1
        ))
        if (( preservation_step_refresh_attempts["${job_id}"] <= max_preservation_step_refresh_attempts )); then
          job_lookup_retryable=true
          retry_reason="preservation-step snapshot refresh"
          break
        fi
        # An unresolved step on a potentially authoritative completed
        # check cannot be allowed to mask a failure or otherwise
        # preserve from an older candidate.
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
          if [[ "${check_status}" == "completed" ]]; then
            terminal_job_cache["${job_id}"]=true
          fi
          continue
          ;;
        *) candidate_ambiguous=true; continue ;;
      esac
    fi

    if [[ "${check_status}" == "completed" ]]; then
      terminal_job_cache["${job_id}"]=true
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
    if [[ "${retry_reason}" == *"API lookup" ]]; then
      echo "Retryable GitHub API failure during ${retry_reason} for the prior ${REQUIRED_GATE_NAME}; retrying attempt ${attempt}/${poll_attempt_limit}." >&2
    else
      echo "Retrying ${retry_reason} for the prior ${REQUIRED_GATE_NAME}; retrying attempt ${attempt}/${poll_attempt_limit}." >&2
    fi
    if [ "${attempt}" -eq "${poll_attempt_limit}" ] || (( SECONDS >= poll_deadline )); then
      echo "Timed out waiting for the prior ${REQUIRED_GATE_NAME} on unchanged head ${HEAD_SHA}." >&2
      exit 1
    fi
    if [[ "${retry_reason}" == *"API lookup" ]]; then
      [[ -z "${retry_error_text}" ]] || printf '%s\n' "${retry_error_text}" >&2
    fi
    if ! sleep_until_poll_deadline; then
      echo "Timed out waiting for the prior ${REQUIRED_GATE_NAME} on unchanged head ${HEAD_SHA}." >&2
      exit 1
    fi
    continue
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
      exit 0
    fi
    echo "Prior ${REQUIRED_GATE_NAME} for unchanged head ${HEAD_SHA} concluded ${prior_conclusion:-unknown}." >&2
    exit 1
  fi
  if [ "${prior_status}" = "none" ]; then
    refresh_active_workflow_state
    if [[ "${active_substantive_workflow}" == "true" || "${substantive_wait_extended}" == "true" ]]; then
      extend_for_active_substantive_run
      echo "A verified substantive workflow is active with ${REQUIRED_GATE_NAME} unpublished; retrying attempt ${attempt}/${poll_attempt_limit}." >&2
    elif [[ "${uncertain_substantive_workflow}" == "true" ]]; then
      echo "A possible substantive workflow is active, but its non-skipped change-detection job is not yet proven; retaining the short fail-closed bound (attempt ${attempt}/${poll_attempt_limit})." >&2
    else
      echo "No attributable substantive workflow is visible yet; retrying attempt ${attempt}/${poll_attempt_limit}." >&2
    fi
    if [ "${attempt}" -eq "${poll_attempt_limit}" ] || (( SECONDS >= poll_deadline )); then
      echo "Timed out waiting for a prior ${REQUIRED_GATE_NAME} on unchanged head ${HEAD_SHA} to appear." >&2
      exit 1
    fi
    if ! sleep_until_poll_deadline; then
      echo "Timed out waiting for a prior ${REQUIRED_GATE_NAME} on unchanged head ${HEAD_SHA} to appear." >&2
      exit 1
    fi
    continue
  fi
  if [ "${prior_status}" != "pending" ]; then
    echo "Unexpected prior ${REQUIRED_GATE_NAME} selection state ${prior_status:-unknown}; refusing to preserve." >&2
    exit 1
  fi
  if [[ "${prior_preserve_step}" == "skipped" ]]; then
    extend_for_active_substantive_run
  else
    refresh_active_workflow_state
    if [[ "${active_substantive_workflow}" == "true" ]]; then
      extend_for_active_substantive_run
    fi
  fi
  if [ "${attempt}" -eq "${poll_attempt_limit}" ] || (( SECONDS >= poll_deadline )); then
    echo "Timed out waiting for the relevant prior ${REQUIRED_GATE_NAME} on unchanged head ${HEAD_SHA} to complete." >&2
    exit 1
  fi
  if [[ "${prior_preserve_step}" == "skipped" || "${active_substantive_workflow:-false}" == "true" ||
    "${substantive_wait_extended}" == "true" ]]; then
    echo "A verified substantive ${REQUIRED_GATE_NAME} is still pending; retrying attempt ${attempt}/${poll_attempt_limit}." >&2
  else
    echo "A prior ${REQUIRED_GATE_NAME} is pending but its substantive identity is not yet verified; retaining the short fail-closed bound (attempt ${attempt}/${poll_attempt_limit})." >&2
  fi
  if ! sleep_until_poll_deadline; then
    echo "Timed out waiting for the relevant prior ${REQUIRED_GATE_NAME} on unchanged head ${HEAD_SHA} to complete." >&2
    exit 1
  fi
done
echo "Required-gate preservation exhausted its polling loop unexpectedly; refusing to preserve." >&2
exit 1
