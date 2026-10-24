#!/usr/bin/env bash
set -euo pipefail

if [[ "$#" -ne 21 ]]; then
  echo "Malformed or unbound exact-attempt evidence: expected 21 identity arguments." >&2
  exit 2
fi
run_id="$1"
attempt="$2"
job_id="$3"
check_id="$4"
gate_name="$5"
workflow_id="$6"
workflow_name="$7"
workflow_path="$8"
display_title="$9"
pr_number="${10}"
base_sha="${11}"
head_sha="${12}"
head_branch="${13}"
repository="${14}"
head_repository="${15}"
expected_status="${16}"
expected_conclusion="${17}"
expected_details_url="${18}"
expected_started_at="${19}"
expected_completed_at="${20}"
require_skipped="${21}"

fail_evidence() {
  echo "Malformed or unbound exact-attempt evidence: $1" >&2
  exit 2
}
fail_api() {
  echo "GitHub API error while reading exact-attempt $1." >&2
  exit 1
}
for identity in "$run_id" "$attempt" "$job_id" "$check_id" "$workflow_id" "$pr_number"; do
  [[ "$identity" =~ ^[1-9][0-9]*$ ]] || fail_evidence "non-positive native identity"
done
for sha in "$base_sha" "$head_sha"; do
  [[ "$sha" =~ ^[0-9A-Fa-f]{40}$ ]] || fail_evidence "invalid PR tuple SHA"
done
[[ "$expected_status" == completed && "$require_skipped" =~ ^(true|false)$ ]] ||
  fail_evidence "unsupported selected gate state"
[[ -n "$gate_name" && -n "$workflow_name" && -n "$workflow_path" && -n "$display_title" &&
  -n "$head_branch" && -n "$repository" && -n "$head_repository" ]] || fail_evidence "incomplete expected source tuple"

if ! attempt_run="$(gh api --method GET "/repos/${repository}/actions/runs/${run_id}/attempts/${attempt}" 2>/dev/null)"; then
  fail_api "workflow run"
fi
jq -e --argjson id "$run_id" --argjson attempt "$attempt" --argjson workflow "$workflow_id" \
  --arg repo "$repository" --arg head_repo "$head_repository" --arg name "$workflow_name" \
  --arg path "$workflow_path" --arg title "$display_title" --arg branch "$head_branch" \
  --arg head "$head_sha" --arg base "$base_sha" --argjson pr "$pr_number" '
  .id == $id and .run_attempt == $attempt and .workflow_id == $workflow and
  (.name == $name or .name == $title) and
  (.path == $path or (.path | startswith($path + "@") and length > ($path | length) + 1)) and
  .display_title == $title and .repository.full_name == $repo and
  .head_repository.full_name == $head_repo and
  .head_sha == $head and .head_branch == $branch and .event == "pull_request" and
  .status == "completed" and (.pull_requests | type) == "array" and
  ((.pull_requests | length) == 0 or
    (any(.pull_requests[]; .number == $pr) and
     all(.pull_requests[] | select(.number == $pr);
       ((.base.sha // $base) == $base and (.head.sha // $head) == $head))) )
' <<<"$attempt_run" >/dev/null || fail_evidence "workflow attempt identity or PR tuple differs"

if ! attempt_jobs="$(gh api --method GET "/repos/${repository}/actions/runs/${run_id}/attempts/${attempt}/jobs" \
  -f per_page=100 2>/dev/null)"; then
  fail_api "complete workflow job snapshot"
fi
attempt_job="$(jq -cer --argjson run "$run_id" --argjson attempt "$attempt" --argjson job "$job_id" \
  --argjson check "$check_id" --arg repo "$repository" --arg head "$head_sha" \
  --arg name "$workflow_name" --arg title "$display_title" --arg branch "$head_branch" --arg gate "$gate_name" \
  --arg status "$expected_status" --arg conclusion "$expected_conclusion" \
  --arg details "$expected_details_url" --arg started "$expected_started_at" \
  --arg completed "$expected_completed_at" --argjson require_skipped "$([[ "$require_skipped" == true ]] && echo true || echo false)" '
  if type != "object" or (.jobs | type) != "array" then error("malformed attempt jobs") else . end
  | .total_count as $total | .jobs as $jobs
  | if ($total | type) != "number" or $total < 1 or $total > 100 or $total != ($total | floor) or
       ($jobs | length) != $total or
       any($jobs[]; type != "object" or (.id | type) != "number" or .id < 1 or .id != (.id | floor)) or
       ([$jobs[].id] | unique | length) != $total or
       any($jobs[]; .run_id != $run or .run_attempt != $attempt or .head_sha != $head or .head_branch != $branch or
         (.workflow_name != $name and .workflow_name != $title) or
         ((.check_run_url // "") | startswith("https://api.github.com/repos/" + $repo + "/check-runs/") | not) or
         (((.check_run_url // "") | ltrimstr("https://api.github.com/repos/" + $repo + "/check-runs/")) |
           test("^[1-9][0-9]*$") | not)) then
      error("incomplete or unbound attempt jobs")
    else
      [$jobs[] | select(.name == $gate)] as $gate_jobs
      | [$jobs[] | select(.id == $job)] as $matches
      | if ($gate_jobs | length) != 1 or ($matches | length) != 1 or $gate_jobs[0].id != $job then
          error("attempt gate identity is not unique")
        else $matches[0] end
      | . as $selected
      | ($selected.html_url // "") as $html
      | ("https://github.com/" + $repo + "/actions/runs/" + ($run | tostring) + "/job/") as $prefix
      | if .name != $gate or .run_id != $run or .run_attempt != $attempt or .head_sha != $head or
           (.workflow_name != $name and .workflow_name != $title) or
           .check_run_url != ("https://api.github.com/repos/" + $repo + "/check-runs/" + ($check | tostring)) or
           (($html | startswith($prefix)) | not) or
           (($html | ltrimstr($prefix) | (try capture("^(?<id>[1-9][0-9]*)([?#].*)?$").id catch "")) != ($job | tostring)) or
           .status != $status or (.conclusion // "") != $conclusion or
           ($details != "" and $html != $details) or
           ($started != "" and (.started_at // "") != $started) or
           ($completed != "" and (.completed_at // "") != $completed) or
           (.steps | type) != "array" then
          error("attempt gate identity differs")
        else . end
      | [.steps[] | select(.name == "Preserve successful required gate on metadata-only edit")] as $preserve
      | if ($preserve | length) > 1 or
           ($require_skipped and (($preserve | length) != 1 or $preserve[0].status != "completed" or $preserve[0].conclusion != "skipped")) then
          error("attempt preservation-step evidence differs")
        else . end
    end
  ' <<<"$attempt_jobs")" || fail_evidence "complete structured workflow jobs or selected gate differ"

if ! check="$(gh api --method GET "/repos/${repository}/check-runs/${check_id}" 2>/dev/null)"; then
  fail_api "selected native CheckRun"
fi
jq -e --argjson id "$check_id" --argjson job "$attempt_job" --arg head "$head_sha" \
  --arg name "$gate_name" --arg status "$expected_status" --arg conclusion "$expected_conclusion" \
  --arg details "$expected_details_url" --arg started "$expected_started_at" --arg completed "$expected_completed_at" '
  .id == $id and .app.id == 15368 and .app.slug == "github-actions" and
  .head_sha == $head and .name == $name and .status == $status and
  (.conclusion // "") == $conclusion and .details_url == $job.html_url and
  (.started_at // "") == ($job.started_at // "") and
  (.completed_at // "") == ($job.completed_at // "") and
  ($details == "" or .details_url == $details) and
  ($started == "" or (.started_at // "") == $started) and
  ($completed == "" or (.completed_at // "") == $completed)
' <<<"$check" >/dev/null || fail_evidence "selected native CheckRun differs from its exact-attempt gate job"

jq -cnS --argjson run "$attempt_run" --argjson jobs "$attempt_jobs" \
  --argjson job "$attempt_job" --argjson check "$check" \
  '{run: $run, jobs: ($jobs.jobs | sort_by(.id)), job: $job, check: $check}'
