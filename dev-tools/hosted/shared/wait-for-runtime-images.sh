#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 1 && $# -ne 3 ]]; then
  echo "usage: $0 <image_tag> | $0 <merge_sha> <base_sha> <head_sha>" >&2
  exit 1
fi

if [[ -z "${GH_TOKEN:-}" || -z "${GITHUB_REPOSITORY:-}" ]]; then
  echo "GH_TOKEN and GITHUB_REPOSITORY are required" >&2
  exit 1
fi

if [[ $# -eq 1 ]]; then
  wait_mode=branch
  branch_name=develop
  image_tag="$1"
  merge_sha=""
  base_sha=""
  head_sha="$1"
else
  wait_mode=pull-request
  branch_name=""
  merge_sha="$1"
  base_sha="$2"
  head_sha="$3"
  for value_name in merge_sha base_sha head_sha; do
    value="${!value_name}"
    if [[ ! "$value" =~ ^[0-9a-fA-F]{40}$ ]]; then
      echo "${value_name} must be exactly 40 hexadecimal characters" >&2
      exit 1
    fi
    printf -v "$value_name" '%s' "${value,,}"
  done
  image_tag="pr-merge-${merge_sha}"
fi
timeout_seconds="${HOSTED_IMAGE_WAIT_TIMEOUT_SECONDS:-${PREVIEW_IMAGE_WAIT_TIMEOUT_SECONDS:-1800}}"
sleep_seconds="${HOSTED_IMAGE_WAIT_SLEEP_SECONDS:-${PREVIEW_IMAGE_WAIT_SLEEP_SECONDS:-10}}"
missing_workflow_timeout_seconds="${HOSTED_IMAGE_WAIT_MISSING_WORKFLOW_TIMEOUT_SECONDS:-${PREVIEW_IMAGE_WAIT_MISSING_WORKFLOW_TIMEOUT_SECONDS:-180}}"
publisher_timeout_seconds="${HOSTED_IMAGE_PUBLISHER_WAIT_TIMEOUT_SECONDS:-${timeout_seconds}}"
start_epoch="${SECONDS}"
deadline=$((SECONDS + timeout_seconds))

fetch_workflow_runs() {
  local endpoint="$1"
  local wait_context="$2"
  local payload

  if ! payload="$(gh api --paginate --slurp "${endpoint}")"; then
    printf 'GitHub API poll failed while %s; retrying within the existing wait deadline.\n' \
      "${wait_context}" >&2
    return 1
  fi

  printf '%s' "${payload}"
}

read_run_state() {
  python3 -c '
import json
import sys
import re
from datetime import datetime, timezone

wait_mode, image_tag, merge_sha, base_sha, head_sha = sys.argv[1:6]
excluded_id = sys.argv[6] if len(sys.argv) > 6 else ""
alternates = len(sys.argv) > 7 and sys.argv[7] == "alternates"
try:
    payload = json.load(sys.stdin)
except json.JSONDecodeError:
    raise SystemExit(1)
pages = payload if isinstance(payload, list) else [payload]
if not pages or any(
    not isinstance(page, dict) or not isinstance(page.get("workflow_runs"), list)
    for page in pages
):
    raise SystemExit(1)
workflow_runs = [
    run
    for page in pages
    for run in page["workflow_runs"]
]
if any(not isinstance(run, dict) for run in workflow_runs):
    raise SystemExit(1)

def is_matching_run(run):
    if wait_mode == "branch":
        display_title = run.get("display_title", "")
        tokens = display_title.split()
        return (
            run.get("event") == "workflow_run"
            and display_title.startswith("Build Runtime Images trusted-branch ")
            and "branch-develop" in tokens
            and f"sha-{head_sha}" in tokens
            and len(tokens) == 6
        )

    # A base refresh is dispatched as the typed pr-runtime-base-refresh event;
    # the workflow-run API exposes its source only as repository_dispatch.
    if run.get("event") not in {"pull_request", "repository_dispatch"}:
        return False
    display_title = run.get("display_title", "")
    tokens = display_title.split()
    return (
        display_title.startswith("Build Runtime Images secure-pr-artifact ")
        and f"base-{base_sha}" in tokens
        and f"head-{head_sha}" in tokens
        and f"merge-{merge_sha}" in tokens
        and display_title.endswith(" mode-required")
    )

matching_runs = [run for run in workflow_runs if is_matching_run(run)]
if excluded_id:
    matching_runs = [run for run in matching_runs if str(run.get("id")) != excluded_id
                     and run.get("status") == "completed" and run.get("conclusion") == "success"]
if not matching_runs:
    print("missing")
    raise SystemExit(0)

ranked = sorted(matching_runs, key=lambda item: item.get("created_at", ""), reverse=True)
# Every tab-delimited field must be nonempty so Bash IFS cannot shift positions.
def clean_field(value):
    return isinstance(value, str) and bool(value) and not any(character.isspace() or ord(character) < 32 or ord(character) == 127 for character in value)

def format_run(run):
    run_id = run.get("id")
    if type(run_id) is not int or run_id <= 0:
        raise SystemExit(1)
    if not all(clean_field(run.get(field)) for field in ("status", "html_url", "event")):
        raise SystemExit(1)
    conclusion = run.get("conclusion")
    if conclusion is None or conclusion == "":
        if run["status"] == "completed":
            raise SystemExit(1)
        conclusion = "pending"
    elif not clean_field(conclusion):
        raise SystemExit(1)
    created_at = run.get("created_at")
    if not isinstance(created_at, str) or not re.fullmatch(r"[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z", created_at):
        raise SystemExit(1)
    try:
        created = datetime.strptime(created_at, "%Y-%m-%dT%H:%M:%SZ").replace(tzinfo=timezone.utc)
    except ValueError:
        raise SystemExit(1)
    if created > datetime.now(timezone.utc):
        raise SystemExit(1)
    return (
        "found\t{}\t{}\t{}\t{}\t{}\t{}".format(
            run_id,
            run["status"],
            conclusion,
            run["html_url"],
            run["event"],
            created_at,
        )
    )

# Bound positive native lookups per polling pass and avoid duplicate API work.
seen = set()
for run in ranked[:8] if alternates else ranked[:1]:
    line = format_run(run)
    if run["id"] not in seen:
        print(line)
        seen.add(run["id"])
' "${wait_mode}" "${image_tag}" "${merge_sha}" "${base_sha}" "${head_sha}" "$@"
}

fetch_publisher_runs() {
  local -a bound=()
  if [[ -n "${4:-}" ]]; then
    bound=(timeout --kill-after=1s "$4")
  fi
  "${bound[@]}" python3 - "${GITHUB_REPOSITORY}" "$1" "$2" "$3" <<'PY_PUBLISHERS'
import json
import re
import subprocess
import sys
from datetime import datetime, timezone

repository, event, lower_text, upper_text = sys.argv[1:]

def unavailable(reason):
    print(f"Publisher coverage unavailable: {reason}.", file=sys.stderr)
    raise SystemExit(1)

def timestamp(value):
    if not isinstance(value, str) or not re.fullmatch(r"[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z", value):
        unavailable("creation-window timestamp is malformed")
    try:
        return datetime.strptime(value, "%Y-%m-%dT%H:%M:%SZ").replace(tzinfo=timezone.utc)
    except ValueError:
        unavailable("creation-window timestamp is invalid")

lower, upper = timestamp(lower_text), timestamp(upper_text)
if lower > upper or event not in {"workflow_run", "repository_dispatch"}:
    unavailable("creation window or event is invalid")
endpoint = f"repos/{repository}/actions/workflows/publish-pr-runtime-images.yml/runs?event={event}&created={lower_text}..{upper_text}&per_page=100"
records, seen, total = [], set(), None
page_number = 1
while True:
    result = subprocess.run(["gh", "api", f"{endpoint}&page={page_number}"], capture_output=True, text=True)
    if result.returncode:
        unavailable("exact-window GitHub API lookup failed")
    try:
        page = json.loads(result.stdout)
    except json.JSONDecodeError:
        unavailable("exact-window API response is malformed")
    if not isinstance(page, dict) or type(page.get("total_count")) is not int or not isinstance(page.get("workflow_runs"), list):
        unavailable("exact-window API envelope is malformed")
    count = page["total_count"]
    if count < 0 or count > 1000:
        unavailable("exact-window run count exceeds the 1000-record filtered API ceiling")
    if total is None:
        total = count
    elif total != count:
        unavailable("exact-window page totals changed")
    runs = page["workflow_runs"]
    if len(runs) != min(100, total - len(records)):
        unavailable("exact-window page coverage is incomplete")
    for run in runs:
        if not isinstance(run, dict) or type(run.get("id")) is not int or run["id"] <= 0 or run["id"] in seen or run.get("event") != event:
            unavailable("exact-window run identity or event is malformed")
        if not lower <= timestamp(run.get("created_at")) <= upper:
            unavailable("run creation time is outside the exact window")
        seen.add(run["id"])
        records.append(run)
    if len(records) == total:
        break
    page_number += 1
print(json.dumps({"workflow_runs": records}))
PY_PUBLISHERS
}

read_publisher_state() {
  python3 -c '
import json
import re
import sys

wait_mode, image_tag, merge_sha, base_sha, head_sha, source_run_id = sys.argv[1:7]
if not re.fullmatch(r"[1-9][0-9]*", source_run_id):
    raise SystemExit(1)
try:
    payload = json.load(sys.stdin)
except json.JSONDecodeError:
    raise SystemExit(1)
pages = payload if isinstance(payload, list) else [payload]
if not pages or any(
    not isinstance(page, dict) or not isinstance(page.get("workflow_runs"), list)
    for page in pages
):
    raise SystemExit(1)
workflow_runs = [
    run
    for page in pages
    for run in page["workflow_runs"]
]
if any(not isinstance(run, dict) for run in workflow_runs):
    raise SystemExit(1)
if wait_mode == "pull-request":
    matching_runs = [
        run for run in workflow_runs
        if (run.get("event") == "repository_dispatch" and
            run.get("display_title") == f"Publish PR Runtime Images source-run-{source_run_id}")
        or (run.get("event") == "workflow_run" and
        (tokens := run.get("display_title", "").split())[:8] == [
            "Publish", "PR", "Runtime", "Images", "Build", "Runtime", "Images",
            "secure-pr-artifact",
        ]
        and len(tokens) == 13
        and re.fullmatch(r"pr-[1-9][0-9]{0,50}", tokens[8])
        and tokens[9] == f"base-{base_sha}"
        and tokens[10] == f"head-{head_sha}"
        and tokens[11] == f"merge-{merge_sha}"
        and tokens[12] == "mode-required")
    ]
else:
    matching_runs = []
all_candidates = len(sys.argv) > 9 and sys.argv[9] == "all"
if len(sys.argv) > 7:
    event, title = sys.argv[7:9]
    matching_runs = [run for run in matching_runs if run.get("event") == event
                     and run.get("display_title") == title
                     and run.get("status") == "completed" and run.get("conclusion") == "success"]
if not matching_runs:
    print("missing")
    raise SystemExit(0)

ranked = sorted(matching_runs, key=lambda item: item.get("created_at", ""), reverse=True)
for run in ranked if all_candidates else ranked[:1]:
    print(
        "found\t{}\t{}\t{}\t{}".format(
            run.get("id", ""),
            run.get("status", ""),
            run.get("conclusion", ""),
            run.get("html_url", ""),
        )
    )
' "${wait_mode}" "${image_tag}" "${merge_sha}" "${base_sha}" "${head_sha}" "$@"
}

fetch_alternate_run_metadata() {
  local lookup_deadline="$1"
  local remaining=$((lookup_deadline - SECONDS))
  (( remaining > 0 )) || return 1
  timeout --kill-after=1s "$remaining" gh api "$2"
}

# A typed publication binds the source run ID. A native callback binds the
# immutable tuple, which the trusted publisher revalidates before writing.
# Both paths need a positive publication step in the exact current attempt.
use_completed_duplicate_publication() {
  local state older_id older_created source_event inventory upper publisher_lines publisher_id publisher_status publisher_conclusion
  local source_state="$1" lookup_deadline="$2" remaining
  local source source_attempt source_title source_latest publisher_event expected_title
  local run attempt jobs latest publisher_path default_branch repository_info
  IFS=$'\t' read -r state older_id _ _ _ source_event older_created <<<"$source_state"
  [[ "$state" == "found" ]] || return 1
  source="$(fetch_alternate_run_metadata "$lookup_deadline" "repos/${GITHUB_REPOSITORY}/actions/runs/${older_id}")" || return 1
  [[ "$(jq -c '{workflow_runs:[.]}' <<<"$source" | read_run_state)" == "$source_state" ]] || return 1
  source_title="$(jq -er '.display_title | select(type == "string")' <<<"$source")" || return 1
  source_attempt="$(jq -er --arg repo "$GITHUB_REPOSITORY" --arg head "$head_sha" '
    select(.repository.full_name == $repo and .head_repository.full_name == $repo)
    | select(.event != "pull_request" or .head_sha == $head)
    | .run_attempt | select(type == "number" and . >= 1 and floor == .)
  ' <<<"$source")" || return 1
  if [[ "$source_event" == "pull_request" ]]; then
    publisher_event="workflow_run"
    expected_title="Publish PR Runtime Images ${source_title}"
  else
    publisher_event="repository_dispatch"
    expected_title="Publish PR Runtime Images source-run-${older_id}"
  fi
  upper="$(date -u +%Y-%m-%dT%H:%M:%SZ)" || return 1
  remaining=$((lookup_deadline - SECONDS))
  (( remaining > 0 )) || return 1
  inventory="$(fetch_publisher_runs "$publisher_event" "$older_created" "$upper" "$remaining")" || return 1
  publisher_lines="$(read_publisher_state "$older_id" "$publisher_event" "$expected_title" all <<<"$inventory")" || return 1
  [[ "$publisher_lines" != "missing" ]] || return 1
  repository_info="$(fetch_alternate_run_metadata "$lookup_deadline" "repos/${GITHUB_REPOSITORY}")" || return 1
  default_branch="$(jq -er --arg repo "$GITHUB_REPOSITORY" '
    select(.full_name == $repo) | .default_branch
    | select(type == "string" and test("^[A-Za-z0-9][A-Za-z0-9._/-]*$"))
  ' <<<"$repository_info")" || return 1
  while IFS=$'\t' read -r state publisher_id publisher_status publisher_conclusion _; do
    [[ "$state" == "found" && "$publisher_id" =~ ^[1-9][0-9]*$ &&
       "$publisher_status" == "completed" && "$publisher_conclusion" == "success" ]] || continue
    (( SECONDS < lookup_deadline )) || break
    run="$(fetch_alternate_run_metadata "$lookup_deadline" "repos/${GITHUB_REPOSITORY}/actions/runs/${publisher_id}")" || continue
    publisher_path="$(jq -er '.path | select(type == "string")' <<<"$run")" || continue
    # Either event runs on the default branch; a short path suffix must agree.
    [[ "$publisher_path" == ".github/workflows/publish-pr-runtime-images.yml" ||
       "$publisher_path" == ".github/workflows/publish-pr-runtime-images.yml@${default_branch}" ]] || continue
    attempt="$(jq -er --argjson id "$publisher_id" --arg repo "$GITHUB_REPOSITORY" --arg path "$publisher_path" --arg branch "$default_branch" \
      --arg title "$expected_title" --arg event "$publisher_event" '
        select(.id == $id and .event == $event and .display_title == $title
          and .path == $path and .head_branch == $branch
          and .repository.full_name == $repo and .head_repository.full_name == $repo
          and .status == "completed" and .conclusion == "success")
        | .run_attempt | select(type == "number" and . >= 1 and floor == .)
      ' <<<"$run")" || continue
    jobs="$(fetch_alternate_run_metadata "$lookup_deadline" "repos/${GITHUB_REPOSITORY}/actions/runs/${publisher_id}/attempts/${attempt}/jobs?per_page=100")" || continue
    jq -e --argjson id "$publisher_id" --argjson attempt "$attempt" '
      (.jobs | type == "array") and (.total_count == (.jobs | length)) and
      ([.jobs[] | select(.name == "Publish Fixed PR Merge Image Tags")] as $jobs |
        ($jobs | length) == 1 and ($jobs[0] |
          .run_id == $id and ((has("run_attempt") | not) or .run_attempt == $attempt) and
          .status == "completed" and .conclusion == "success" and
          ([.steps[] | select(.name == "Publish fixed PR image tags")] as $steps |
            ($steps | length) == 1 and $steps[0].status == "completed" and $steps[0].conclusion == "success")))
    ' <<<"$jobs" >/dev/null || continue
    latest="$(fetch_alternate_run_metadata "$lookup_deadline" "repos/${GITHUB_REPOSITORY}/actions/runs/${publisher_id}")" || continue
    jq -e --argjson id "$publisher_id" --argjson attempt "$attempt" --arg path "$publisher_path" --arg branch "$default_branch" \
      --arg title "$expected_title" --arg event "$publisher_event" --arg repo "$GITHUB_REPOSITORY" '
      .id == $id and .run_attempt == $attempt and .status == "completed" and .conclusion == "success"
      and .path == $path and .head_branch == $branch and .event == $event and .display_title == $title
      and .repository.full_name == $repo and .head_repository.full_name == $repo
    ' <<<"$latest" >/dev/null || continue
    source_latest="$(fetch_alternate_run_metadata "$lookup_deadline" "repos/${GITHUB_REPOSITORY}/actions/runs/${older_id}")" || continue
    jq -e --argjson attempt "$source_attempt" --arg repo "$GITHUB_REPOSITORY" --arg title "$source_title" --arg head "$head_sha" '
      .run_attempt == $attempt and .repository.full_name == $repo and .head_repository.full_name == $repo
      and .display_title == $title and (.event != "pull_request" or .head_sha == $head)
    ' <<<"$source_latest" >/dev/null || continue
    [[ "$(jq -c '{workflow_runs:[.]}' <<<"$source_latest" | read_run_state)" == "$source_state" ]] || continue
    (( SECONDS < lookup_deadline )) || break
    printf 'Trusted PR image publisher %s succeeded for %s via an alternate producer.\n' "$publisher_id" "$image_tag"
    return 0
  done <<<"$publisher_lines"
  return 1
}

find_completed_alternate_publication() {
  local alternate_sources="$1" lookup_deadline="$2" older_state
  while IFS= read -r older_state; do
    (( SECONDS < lookup_deadline )) || break
    if use_completed_duplicate_publication "$older_state" "$lookup_deadline"; then
      return 0
    fi
  done <<<"$alternate_sources"
  return 1
}

wait_for_pr_publisher() {
  local source_run_id="$1"
  local source_created_at="$2"
  local alternate_sources="$3"
  local coverage_unavailable=false
  local publisher_start_epoch="${SECONDS}"
  local publisher_deadline=$((SECONDS + publisher_timeout_seconds))
  while (( SECONDS < publisher_deadline )); do
    local publisher_payload publisher_state event_payload publisher_event fetch_failed
    publisher_payload=""
    fetch_failed=false
    local poll_upper_bound
    poll_upper_bound="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    # Preserve complete discovery within the verified source creation window.
    for publisher_event in workflow_run repository_dispatch; do
      if ! event_payload="$(
        fetch_publisher_runs "${publisher_event}" "${source_created_at}" "${poll_upper_bound}"
      )"; then
        fetch_failed=true
        break
      fi
      publisher_payload+="${publisher_payload:+,}${event_payload}"
    done
    local state run_id run_status run_conclusion run_url
    publisher_payload="[${publisher_payload}]"
    publisher_state=""
    if [[ "${fetch_failed}" == "false" ]] &&
      publisher_state="$(read_publisher_state "${source_run_id}" <<<"${publisher_payload}")"; then
      IFS=$'\t' read -r state run_id run_status run_conclusion run_url <<<"${publisher_state}"
      if [[ "${run_status}" == "completed" && "${run_conclusion}" == "success" ]]; then
        printf 'Trusted PR image publisher %s succeeded for %s.\n' "${run_id}" "${image_tag}"
        return 0
      fi
    fi
    local refreshed_source_payload refreshed_alternates
    if refreshed_source_payload="$(fetch_workflow_runs \
      "repos/${GITHUB_REPOSITORY}/actions/workflows/runtime-images.yml/runs?per_page=100" \
      "refreshing alternate runtime-image sources while waiting for publication")"; then
      if refreshed_alternates="$(read_run_state "${source_run_id}" alternates <<<"${refreshed_source_payload}")"; then
        if [[ "${refreshed_alternates}" == "missing" ]]; then
          alternate_sources=""
        else
          alternate_sources="${refreshed_alternates}"
        fi
      fi
    fi
    if find_completed_alternate_publication "$alternate_sources" "$publisher_deadline"; then
      return 0
    fi
    if [[ "${fetch_failed}" == "true" ]]; then
      coverage_unavailable=true
      sleep "${sleep_seconds}"
      continue
    fi
    coverage_unavailable=false
    if [[ -z "${publisher_state}" ]]; then
      printf 'GitHub API response was empty or invalid while waiting for the trusted PR image publisher; retrying.\n' >&2
      sleep "${sleep_seconds}"
      continue
    fi

    if [[ "${state}" == "missing" ]]; then
      local elapsed_seconds=$((SECONDS - publisher_start_epoch))
      if (( elapsed_seconds >= missing_workflow_timeout_seconds )); then
        printf 'No trusted PR image publisher appeared for %s after %ss.\n' \
          "${image_tag}" "${elapsed_seconds}" >&2
        exit 1
      fi
      printf 'Waiting for trusted PR image publisher for %s after %ss.\n' \
        "${image_tag}" "${elapsed_seconds}"
      sleep "${sleep_seconds}"
      continue
    fi

    if [[ "${run_status}" == "completed" ]]; then
      printf 'Trusted PR image publisher %s completed with %s for %s.\n' \
        "${run_id}" "${run_conclusion}" "${image_tag}" >&2
      [[ -z "${run_url}" ]] || printf 'Workflow URL: %s\n' "${run_url}" >&2
      exit 1
    fi

    printf 'Waiting for trusted PR image publisher %s for %s. status=%s conclusion=%s\n' \
      "${run_id}" "${image_tag}" "${run_status}" "${run_conclusion:-pending}"
    sleep "${sleep_seconds}"
  done

  if [[ "${coverage_unavailable}" == "true" ]]; then
    printf 'Publisher coverage unavailable at the wait deadline for %s.\n' "${image_tag}" >&2
    exit 1
  fi
  printf 'Timed out waiting for trusted PR image publisher for %s.\n' "${image_tag}" >&2
  exit 1
}

while (( SECONDS < deadline )); do
  if ! workflow_payload="$(
    fetch_workflow_runs \
      "repos/${GITHUB_REPOSITORY}/actions/workflows/runtime-images.yml/runs?per_page=100" \
      "waiting for the runtime-images workflow"
  )"; then
    sleep "${sleep_seconds}"
    continue
  fi
  if ! run_state="$(read_run_state <<<"${workflow_payload}")"; then
    printf 'GitHub API response was empty or invalid while waiting for the runtime-images workflow; retrying.\n' >&2
    sleep "${sleep_seconds}"
    continue
  fi

  IFS=$'\t' read -r state run_id run_status run_conclusion run_url _run_event run_created_at <<<"${run_state}"

  if [[ "${state}" == "missing" ]]; then
    elapsed_seconds=$((SECONDS - start_epoch))
    if (( elapsed_seconds >= missing_workflow_timeout_seconds )); then
      if [[ "${wait_mode}" == "branch" ]]; then
        printf 'No trusted branch runtime-image publication appeared for branch %s and exact head SHA %s after %ss.\n' \
          "${branch_name}" "${head_sha}" "${elapsed_seconds}" >&2
      else
        printf 'No runtime-images workflow appeared for %s after %ss.\n' \
          "${image_tag}" "${elapsed_seconds}" >&2
        printf 'The PR image source or trusted current-base refresh did not appear for the exact merge SHA.\n' >&2
      fi
      exit 1
    fi

    printf 'Waiting for runtime-images workflow for %s after %ss. Matching run not visible yet.\n' \
      "${image_tag}" "${elapsed_seconds}"
    sleep "${sleep_seconds}"
    continue
  fi

  if [[ "${run_status}" == "completed" && "${run_conclusion}" == "success" ]]; then
    printf 'Matching runtime-images workflow %s succeeded for %s after %ss.\n' \
      "${run_id}" "${image_tag}" "$((SECONDS - start_epoch))"
    if [[ "${wait_mode}" == "pull-request" ]]; then
      older_states=""
      if [[ "$SECONDS" -lt "$deadline" ]]; then
        older_states="$(read_run_state "$run_id" alternates <<<"${workflow_payload}")" || older_states=""
      fi
      wait_for_pr_publisher "${run_id}" "${run_created_at}" "$older_states"
    fi
    exit 0
  fi

  older_states=""
  if [[ "$wait_mode" == "pull-request" && "$SECONDS" -lt "$deadline" ]]; then
    if older_states="$(read_run_state "$run_id" alternates <<<"$workflow_payload")" &&
      find_completed_alternate_publication "$older_states" "$deadline"; then
      exit 0
    fi
  fi

  if [[ "${run_status}" == "completed" ]]; then
    printf 'Matching runtime-images workflow %s completed with %s for %s.\n' \
      "${run_id}" "${run_conclusion}" "${image_tag}" >&2
    if [[ -n "${run_url}" ]]; then
      printf 'Workflow URL: %s\n' "${run_url}" >&2
    fi
    exit 1
  fi

  printf 'Waiting for runtime-images workflow %s for %s after %ss. status=%s conclusion=%s\n' \
    "${run_id}" "${image_tag}" "$((SECONDS - start_epoch))" "${run_status}" "${run_conclusion:-pending}"
  if [[ -n "${run_url}" ]]; then
    printf 'Workflow URL: %s\n' "${run_url}"
  fi
  sleep "${sleep_seconds}"
done

printf 'Timed out waiting for runtime-images workflow for %s after %ss.\n' \
  "${image_tag}" "$((SECONDS - start_epoch))" >&2
exit 1
