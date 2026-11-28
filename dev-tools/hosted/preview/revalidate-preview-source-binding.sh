#!/usr/bin/env bash
set -euo pipefail

require_current_run_attempt() {
  local run_id="$1" attempt="$2" stage="$3" run_json
  if [[ ! "$run_id" =~ ^[1-9][0-9]*$ || ! "$attempt" =~ ^[1-9][0-9]*$ ]] ||
    ! run_json="$(gh api "repos/${GITHUB_REPOSITORY}/actions/runs/${run_id}")" ||
    ! jq -es --arg run_id "$run_id" --arg attempt "$attempt" --arg repo "$GITHUB_REPOSITORY" '
      length == 1 and (.[0] |
        (.id | type == "number" and tostring == $run_id) and
        (.run_attempt | type == "number" and tostring == $attempt) and
        .repository.full_name == $repo and .status == "completed" and .conclusion == "success")
    ' <<<"$run_json" >/dev/null; then
    echo "::error title=Preview source attempt changed::The pinned preview render is no longer a verified current completed success${stage:+ $stage}." >&2
    return 1
  fi
}

if [[ ${1:-} == --run-attempt && $# -ge 3 && $# -le 4 ]]; then
  require_current_run_attempt "$2" "$3" "${4:-}"
  exit
fi
if [[ $# -ne 4 && $# -ne 5 && $# -ne 7 ]]; then
  echo "usage: $0 PR_NUMBER EXPECTED_HEAD_SHA EXPECTED_BASE_SHA EXPECTED_MERGE_SHA [stage [SOURCE_RUN_ID SOURCE_RUN_ATTEMPT]]" >&2
  echo "       $0 --run-attempt SOURCE_RUN_ID SOURCE_RUN_ATTEMPT [stage]" >&2
  exit 2
fi

PR_NUMBER="$1"
EXPECTED_HEAD_SHA="$2"
EXPECTED_BASE_SHA="$3"
EXPECTED_MERGE_SHA="$4"
STAGE="${5:-}"

pull_request_json="$(gh api "repos/${GITHUB_REPOSITORY}/pulls/${PR_NUMBER}")"
if jq -e '(.mergeable == null) or (.mergeable_state == "unknown")' \
  <<<"$pull_request_json" >/dev/null 2>&1; then
  retry_attempts="${PREVIEW_METADATA_RETRY_ATTEMPTS:-3}"
  retry_delay_seconds="${PREVIEW_METADATA_RETRY_DELAY_SECONDS:-1}"
  if ! [[ "$retry_attempts" =~ ^[1-9][0-9]*$ ]] ||
    ! [[ "$retry_delay_seconds" =~ ^[0-9]+([.][0-9]+)?$ ]]; then
    echo "::error title=Preview source binding changed::Preview mergeability retry settings are invalid." >&2
    exit 1
  fi
  for ((retry_attempt = 1; retry_attempt <= retry_attempts; retry_attempt++)); do
    if ! refreshed_pull_request_json="$(gh api "repos/${GITHUB_REPOSITORY}/pulls/${PR_NUMBER}")"; then
      echo "::error title=Preview source binding changed::The current PR metadata could not be refreshed${STAGE:+ $STAGE}." >&2
      exit 1
    fi
    if ! jq -e 'type == "object"' <<<"$refreshed_pull_request_json" >/dev/null 2>&1; then
      echo "::error title=Preview source binding changed::The refreshed PR metadata is malformed${STAGE:+ $STAGE}." >&2
      exit 1
    fi
    pull_request_json="$refreshed_pull_request_json"
    if ! jq -e '(.mergeable == null) or (.mergeable_state == "unknown")' \
      <<<"$pull_request_json" >/dev/null 2>&1; then
      break
    fi
    if (( retry_attempt < retry_attempts )); then
      sleep "$retry_delay_seconds"
    fi
  done
fi
base_ref="$(jq -er '.base.ref | select(type == "string" and length > 0)' <<<"$pull_request_json")" || {
  echo "::error title=Preview source binding changed::The current PR base ref is missing or invalid${STAGE:+ $STAGE}." >&2
  exit 1
}
if [[ ! "$base_ref" =~ ^[A-Za-z0-9._/-]+$ ]]; then
  echo "::error title=Preview source binding changed::The current PR base ref is invalid${STAGE:+ $STAGE}." >&2
  exit 1
fi
current_base_ref_json="$(gh api "repos/${GITHUB_REPOSITORY}/git/ref/heads/${base_ref}")" || {
  echo "::error title=Preview source binding changed::The current PR base branch ref could not be read${STAGE:+ $STAGE}." >&2
  exit 1
}
current_base_sha="$(jq -er '.object.sha | select(type == "string")' <<<"$current_base_ref_json")" || {
  echo "::error title=Preview source binding changed::The current PR base branch ref has no valid SHA${STAGE:+ $STAGE}." >&2
  exit 1
}
current_base_sha="${current_base_sha,,}"
current_head_sha="$(jq -er '.head.sha | select(type == "string")' <<<"$pull_request_json")" || {
  echo "::error title=Preview source binding changed::The current PR head SHA is missing${STAGE:+ $STAGE}." >&2
  exit 1
}
current_head_sha="${current_head_sha,,}"
current_merge_sha="$(jq -er '.merge_commit_sha | select(type == "string")' <<<"$pull_request_json")" || {
  echo "::error title=Preview source binding changed::The current PR merge SHA is missing${STAGE:+ $STAGE}." >&2
  exit 1
}
current_merge_sha="${current_merge_sha,,}"
if [[ ! "$current_base_sha" =~ ^[0-9a-f]{40}$ ]] ||
  [[ ! "$current_head_sha" =~ ^[0-9a-f]{40}$ ]] ||
  [[ ! "$current_merge_sha" =~ ^[0-9a-f]{40}$ ]] ||
  [[ ! "${EXPECTED_HEAD_SHA,,}" =~ ^[0-9a-f]{40}$ ]] ||
  [[ ! "${EXPECTED_BASE_SHA,,}" =~ ^[0-9a-f]{40}$ ]] ||
  [[ ! "${EXPECTED_MERGE_SHA,,}" =~ ^[0-9a-f]{40}$ ]]; then
  echo "::error title=Preview source binding changed::The current PR source tuple contains an invalid SHA${STAGE:+ $STAGE}." >&2
  exit 1
fi
merge_commit_json="$(gh api "repos/${GITHUB_REPOSITORY}/commits/${current_merge_sha}")" || {
  echo "::error title=Preview source binding changed::The current PR merge commit could not be read${STAGE:+ $STAGE}." >&2
  exit 1
}
jq -e \
  --arg repository "$GITHUB_REPOSITORY" \
  --arg expected_head "${EXPECTED_HEAD_SHA,,}" \
  --arg expected_base "${EXPECTED_BASE_SHA,,}" \
  --arg expected_merge "${EXPECTED_MERGE_SHA,,}" \
  --arg current_base "$current_base_sha" \
  --arg current_head "$current_head_sha" \
  --arg current_merge "$current_merge_sha" \
  '.state == "open" and
   (.mergeable | type) == "boolean" and
   .mergeable == true and
   (.mergeable_state | type) == "string" and
   .mergeable_state != "unknown" and
   .mergeable_state != "dirty" and
   .mergeable_state != "conflicting" and
   .head.repo.full_name == $repository and
   .base.repo.full_name == $repository and
   .head.sha == $expected_head and
   $current_head == $expected_head and
   $current_base == $expected_base and
   $current_merge == $expected_merge and
   .merge_commit_sha == $expected_merge' \
  <<<"$pull_request_json" >/dev/null || {
  echo "::error title=Preview source binding changed::The current PR head, base, merge, repository, or mergeability no longer matches the validated artifact${STAGE:+ $STAGE}." >&2
  exit 1
}
jq -e \
  --arg expected_merge "${EXPECTED_MERGE_SHA,,}" \
  --arg expected_base "${EXPECTED_BASE_SHA,,}" \
  --arg expected_head "${EXPECTED_HEAD_SHA,,}" \
  '.sha == $expected_merge and
   (.parents | type) == "array" and
   (.parents | length) == 2 and
   .parents[0].sha == $expected_base and
   .parents[1].sha == $expected_head' \
  <<<"$merge_commit_json" >/dev/null || {
  echo "::error title=Preview source binding changed::The current PR merge commit does not have the expected base and head parents${STAGE:+ $STAGE}." >&2
  exit 1
}

if [[ $# -eq 7 ]]; then
  require_current_run_attempt "$6" "$7" "$STAGE"
fi
