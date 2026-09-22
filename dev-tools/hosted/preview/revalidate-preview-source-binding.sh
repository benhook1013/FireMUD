#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 4 && $# -ne 5 ]]; then
  echo "usage: $0 PR_NUMBER EXPECTED_HEAD_SHA EXPECTED_BASE_SHA EXPECTED_MERGE_SHA [stage]" >&2
  exit 2
fi

PR_NUMBER="$1"
EXPECTED_HEAD_SHA="$2"
EXPECTED_BASE_SHA="$3"
EXPECTED_MERGE_SHA="$4"
STAGE="${5:-}"

pull_request_json="$(gh api "repos/${GITHUB_REPOSITORY}/pulls/${PR_NUMBER}")"
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
