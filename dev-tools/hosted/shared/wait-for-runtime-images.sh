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

wait_mode, image_tag, merge_sha, base_sha, head_sha, *native = sys.argv[1:]
try:
    payload = json.load(sys.stdin)
except json.JSONDecodeError:
    raise SystemExit(1)
if native:
    payload = {"workflow_runs": [payload]}
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
if not matching_runs:
    print("missing")
    raise SystemExit(0)

run = sorted(matching_runs, key=lambda item: item.get("created_at", ""), reverse=True)[0]
# Every tab-delimited field must be nonempty so Bash IFS cannot shift positions.
def clean_field(value):
    return isinstance(value, str) and bool(value) and not any(character.isspace() or ord(character) < 32 or ord(character) == 127 for character in value)

run_attempt = run.get("run_attempt") if wait_mode == "pull-request" else "-"
if wait_mode == "pull-request" and (type(run_attempt) is not int or run_attempt <= 0):
    raise SystemExit(1)
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
print(
    "found\t{}\t{}\t{}\t{}\t{}\t{}\t{}".format(
        run_id,
        run["status"],
        conclusion,
        run["html_url"],
        run["event"],
        created_at,
        run_attempt,
    )
)
' "${wait_mode}" "${image_tag}" "${merge_sha}" "${base_sha}" "${head_sha}" "$@"
}

fetch_publisher_runs() {
  python3 - "${GITHUB_REPOSITORY}" "$1" "$2" "$3" <<'PY_PUBLISHERS'
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

wait_mode, image_tag, merge_sha, base_sha, head_sha, source_run_id, source_run_attempt = sys.argv[1:]
if not all(re.fullmatch(r"[1-9][0-9]*", value) for value in (source_run_id, source_run_attempt)):
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
    source_suffix = f"source-run-{source_run_id} attempt-{source_run_attempt}"
    matching_runs = [
        run for run in workflow_runs
        if (run.get("event") == "repository_dispatch" and
            run.get("display_title") == f"Publish PR Runtime Images {source_suffix}")
        or (run.get("event") == "workflow_run" and
        (tokens := run.get("display_title", "").split())[:8] == [
            "Publish", "PR", "Runtime", "Images", "Build", "Runtime", "Images",
            "secure-pr-artifact",
        ]
        and len(tokens) == 15
        and re.fullmatch(r"pr-[1-9][0-9]{0,50}", tokens[8])
        and tokens[9] == f"base-{base_sha}"
        and tokens[10] == f"head-{head_sha}"
        and tokens[11] == f"merge-{merge_sha}"
        and tokens[12] == "mode-required"
        and " ".join(tokens[13:]) == source_suffix)
    ]
else:
    matching_runs = []
if not matching_runs:
    print("missing")
    raise SystemExit(0)

run = sorted(matching_runs, key=lambda item: item.get("created_at", ""), reverse=True)[0]
attempt = run.get("run_attempt")
if type(attempt) is not int or attempt <= 0:
    raise SystemExit(1)
url = run.get("html_url") or "-"
print(
    "found\t{}\t{}\t{}\t{}\t{}".format(
        run["id"], run.get("status"), run.get("conclusion"), url, attempt,
    )
)
' "${wait_mode}" "${image_tag}" "${merge_sha}" "${base_sha}" "${head_sha}" "$1" "$2"
}

verify_publisher_attempt() {
  local run_id="$1" run_attempt="$2" remaining="$3" jobs
  (( remaining > 0 )) || return 1
  if ! jobs="$(timeout "${remaining}s" gh api "repos/${GITHUB_REPOSITORY}/actions/runs/${run_id}/attempts/${run_attempt}/jobs?per_page=100&page=1")"; then
    return 1
  fi
  python3 -c '
import json
import sys

run_id, attempt = map(int, sys.argv[1:])
try:
    payload = json.load(sys.stdin)
except json.JSONDecodeError:
    raise SystemExit(1)
if not isinstance(payload, dict):
    raise SystemExit(1)
jobs = payload.get("jobs")
total = payload.get("total_count")
if type(total) is not int or not 1 <= total <= 100 or not isinstance(jobs, list) or len(jobs) != total:
    raise SystemExit(1)
seen = set()
publish = []
for job in jobs:
    if not isinstance(job, dict):
        raise SystemExit(1)
    job_id = job.get("id")
    if (type(job_id) is not int or job_id <= 0 or job_id in seen
            or type(job.get("run_id")) is not int or job["run_id"] != run_id
            or type(job.get("run_attempt")) is not int or job["run_attempt"] != attempt):
        raise SystemExit(1)
    seen.add(job_id)
    if job.get("name") == "Publish Fixed PR Merge Image Tags":
        publish.append(job)
if len(publish) != 1 or publish[0].get("status") != "completed" or publish[0].get("conclusion") != "success":
    raise SystemExit(1)
steps = publish[0].get("steps")
if not isinstance(steps, list) or any(not isinstance(step, dict) for step in steps):
    raise SystemExit(1)
for name in ("Download successful PR image artifacts",
             "Validate exact PR source and artifact before registry login",
             "Publish fixed PR image tags"):
    matching = [step for step in steps if step.get("name") == name]
    if len(matching) != 1 or matching[0].get("status") != "completed" or matching[0].get("conclusion") != "success":
        raise SystemExit(1)
' "${run_id}" "${run_attempt}" <<<"${jobs}"
}

wait_for_pr_publisher() {
  local source_run_id="$1"
  local source_run_attempt="$2"
  local source_created_at="$3"
  local source_event="$4"
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
    if [[ "${fetch_failed}" == "true" ]]; then
      coverage_unavailable=true
      sleep "${sleep_seconds}"
      continue
    fi
    coverage_unavailable=false
    publisher_payload="[${publisher_payload}]"
    if ! publisher_state="$(read_publisher_state "${source_run_id}" "${source_run_attempt}" <<<"${publisher_payload}")"; then
      printf 'GitHub API response was empty or invalid while waiting for the trusted PR image publisher; retrying.\n' >&2
      sleep "${sleep_seconds}"
      continue
    fi

    local state run_id run_status run_conclusion run_url publisher_attempt
    IFS=$'\t' read -r state run_id run_status run_conclusion run_url publisher_attempt <<<"${publisher_state}"

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

    if [[ "${run_status}" == "completed" && "${run_conclusion}" == "success" ]]; then
      if ! verify_publisher_attempt "${run_id}" "${publisher_attempt}" "$((publisher_deadline - SECONDS))"; then
        printf 'Exact PR image publisher job and step success is unavailable for run %s attempt %s.\n' \
          "${run_id}" "${publisher_attempt}" >&2
        sleep "${sleep_seconds}"
        continue
      fi
      local remaining=$((publisher_deadline - SECONDS)) current_payload current_state
      local current_kind current_id current_status current_conclusion _current_url current_event _current_created_at current_attempt
      if (( remaining <= 0 )) ||
        ! current_payload="$(timeout "${remaining}s" gh api "repos/${GITHUB_REPOSITORY}/actions/runs/${source_run_id}")" ||
        ! current_state="$(read_run_state native <<<"${current_payload}")"; then
        printf 'Current runtime-image producer evidence is unavailable for run %s attempt %s.\n' \
          "${source_run_id}" "${source_run_attempt}" >&2
        return 1
      fi
      IFS=$'\t' read -r current_kind current_id current_status current_conclusion _current_url current_event _current_created_at current_attempt <<<"${current_state}"
      if (( SECONDS >= publisher_deadline )) ||
        [[ "${current_kind}" != "found" || "${current_id}" != "${source_run_id}" ||
        "${current_attempt}" != "${source_run_attempt}" || "${current_event}" != "${source_event}" ||
        "${current_status}" != "completed" || "${current_conclusion}" != "success" ]]; then
        printf 'Current runtime-image producer does not match the successful run %s attempt %s for %s.\n' \
          "${source_run_id}" "${source_run_attempt}" "${image_tag}" >&2
        return 1
      fi
      printf 'Trusted PR image publisher %s succeeded for %s.\n' "${run_id}" "${image_tag}"
      return 0
    fi
    if [[ "${run_status}" == "completed" ]]; then
      printf 'Trusted PR image publisher %s completed with %s for %s.\n' \
        "${run_id}" "${run_conclusion}" "${image_tag}" >&2
      [[ -z "${run_url}" || "${run_url}" == "-" ]] || printf 'Workflow URL: %s\n' "${run_url}" >&2
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

  IFS=$'\t' read -r state run_id run_status run_conclusion run_url run_event run_created_at run_attempt <<<"${run_state}"

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
      wait_for_pr_publisher "${run_id}" "${run_attempt}" "${run_created_at}" "${run_event}"
    fi
    exit 0
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
