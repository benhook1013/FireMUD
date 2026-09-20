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
  image_tag="$1"
  merge_sha=""
  base_sha=""
  head_sha="$1"
else
  wait_mode=pull-request
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

wait_mode, image_tag, merge_sha, base_sha, head_sha = sys.argv[1:]
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

    if run.get("event") != "pull_request":
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
print(
    "found\t{}\t{}\t{}\t{}\t{}".format(
        run.get("id", ""),
        run.get("status", ""),
        run.get("conclusion", ""),
        run.get("html_url", ""),
        run.get("event", ""),
    )
)
' "${wait_mode}" "${image_tag}" "${merge_sha}" "${base_sha}" "${head_sha}"
}

read_publisher_state() {
  python3 -c '
import json
import re
import sys

wait_mode, image_tag, merge_sha, base_sha, head_sha = sys.argv[1:]
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
        if (tokens := run.get("display_title", "").split())[:8] == [
            "Publish", "PR", "Runtime", "Images", "Build", "Runtime", "Images",
            "secure-pr-artifact",
        ]
        and len(tokens) == 13
        and re.fullmatch(r"pr-[1-9][0-9]{0,50}", tokens[8])
        and tokens[9] == f"base-{base_sha}"
        and tokens[10] == f"head-{head_sha}"
        and tokens[11] == f"merge-{merge_sha}"
        and tokens[12] == "mode-required"
    ]
else:
    matching_runs = []
if not matching_runs:
    print("missing")
    raise SystemExit(0)

run = sorted(matching_runs, key=lambda item: item.get("created_at", ""), reverse=True)[0]
print(
    "found\t{}\t{}\t{}\t{}".format(
        run.get("id", ""),
        run.get("status", ""),
        run.get("conclusion", ""),
        run.get("html_url", ""),
    )
)
' "${wait_mode}" "${image_tag}" "${merge_sha}" "${base_sha}" "${head_sha}"
}

wait_for_pr_publisher() {
  local publisher_start_epoch="${SECONDS}"
  local publisher_deadline=$((SECONDS + publisher_timeout_seconds))
  while (( SECONDS < publisher_deadline )); do
    local publisher_payload publisher_state
    if ! publisher_payload="$(
      fetch_workflow_runs \
        "repos/${GITHUB_REPOSITORY}/actions/workflows/publish-pr-runtime-images.yml/runs?event=workflow_run&per_page=100" \
        "waiting for the trusted PR image publisher"
    )"; then
      sleep "${sleep_seconds}"
      continue
    fi
    if ! publisher_state="$(read_publisher_state <<<"${publisher_payload}")"; then
      printf 'GitHub API response was empty or invalid while waiting for the trusted PR image publisher; retrying.\n' >&2
      sleep "${sleep_seconds}"
      continue
    fi

    local state run_id run_status run_conclusion run_url
    IFS=$'\t' read -r state run_id run_status run_conclusion run_url <<<"${publisher_state}"

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
      printf 'Trusted PR image publisher %s succeeded for %s.\n' "${run_id}" "${image_tag}"
      return 0
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

  IFS=$'\t' read -r state run_id run_status run_conclusion run_url run_event <<<"${run_state}"

  if [[ "${state}" == "missing" ]]; then
    elapsed_seconds=$((SECONDS - start_epoch))
    if (( elapsed_seconds >= missing_workflow_timeout_seconds )); then
      printf 'No runtime-images workflow appeared for %s after %ss.\n' \
        "${image_tag}" "${elapsed_seconds}" >&2
      printf 'This usually means the runtime-images pull_request trigger did not fire for the head SHA.\n' >&2
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
    if [[ "${wait_mode}" == "pull-request" && "${run_event}" == "pull_request" ]]; then
      wait_for_pr_publisher
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
