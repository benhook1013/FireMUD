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
timeout_seconds="${PREVIEW_IMAGE_WAIT_TIMEOUT_SECONDS:-1800}"
sleep_seconds="${PREVIEW_IMAGE_WAIT_SLEEP_SECONDS:-10}"
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
    "found\t{}\t{}\t{}\t{}".format(
        run.get("id", ""),
        run.get("status", ""),
        run.get("conclusion", ""),
        run.get("html_url", ""),
    )
)
' "${image_tag}"
}

while (( SECONDS < deadline )); do
  run_state="$(
    gh api "repos/${GITHUB_REPOSITORY}/actions/workflows/runtime-images.yml/runs?per_page=100" \
      | read_run_state
  )"

  IFS=$'\t' read -r state run_id run_status run_conclusion run_url <<<"${run_state}"

  if [[ "${state}" == "missing" ]]; then
    printf 'Waiting for runtime-images workflow for %s after %ss. Matching run not visible yet.\n' \
      "${image_tag}" "$((SECONDS - start_epoch))"
    sleep "${sleep_seconds}"
    continue
  fi

  if [[ "${run_status}" == "completed" && "${run_conclusion}" == "success" ]]; then
    printf 'Matching runtime-images workflow %s succeeded for %s after %ss.\n' \
      "${run_id}" "${image_tag}" "$((SECONDS - start_epoch))"
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
