#!/usr/bin/env bash
set -euo pipefail

if [[ $# -lt 1 ]]; then
  echo "usage: $0 <image_tag>" >&2
  exit 1
fi

if [[ -z "${GH_TOKEN:-}" || -z "${GITHUB_REPOSITORY:-}" ]]; then
  echo "GH_TOKEN and GITHUB_REPOSITORY are required" >&2
  exit 1
fi

image_tag="$1"
timeout_seconds="${HOSTED_IMAGE_WAIT_TIMEOUT_SECONDS:-${PREVIEW_IMAGE_WAIT_TIMEOUT_SECONDS:-1800}}"
sleep_seconds="${HOSTED_IMAGE_WAIT_SLEEP_SECONDS:-${PREVIEW_IMAGE_WAIT_SLEEP_SECONDS:-10}}"
missing_workflow_timeout_seconds="${HOSTED_IMAGE_WAIT_MISSING_WORKFLOW_TIMEOUT_SECONDS:-${PREVIEW_IMAGE_WAIT_MISSING_WORKFLOW_TIMEOUT_SECONDS:-180}}"
start_epoch="${SECONDS}"
deadline=$((SECONDS + timeout_seconds))

read_run_state() {
  python3 -c '
import json
import sys

head_sha = sys.argv[1]
payload = json.load(sys.stdin)
workflow_runs = payload.get("workflow_runs", [])
matching_runs = [run for run in workflow_runs if run.get("head_sha") == head_sha]
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
' "${image_tag}"
}

read_publisher_state() {
  python3 -c '
import json
import sys

head_sha = sys.argv[1]
expected_title = f"Publish PR Runtime Images head-{head_sha}"
payload = json.load(sys.stdin)
workflow_runs = payload.get("workflow_runs", [])
matching_runs = [
    run for run in workflow_runs if run.get("display_title") == expected_title
]
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
' "${image_tag}"
}

wait_for_pr_publisher() {
  local publisher_start_epoch="${SECONDS}"
  local publisher_deadline=$((SECONDS + timeout_seconds))
  while (( SECONDS < publisher_deadline )); do
    local publisher_state
    publisher_state="$(
      gh api "repos/${GITHUB_REPOSITORY}/actions/workflows/publish-pr-runtime-images.yml/runs?event=workflow_run&per_page=100" \
        | read_publisher_state
    )"

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
  run_state="$(
    gh api "repos/${GITHUB_REPOSITORY}/actions/workflows/runtime-images.yml/runs?per_page=100" \
      | read_run_state
  )"

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
    if [[ "${run_event}" == "pull_request" ]]; then
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
