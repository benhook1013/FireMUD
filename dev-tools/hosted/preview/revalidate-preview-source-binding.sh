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

normalize_sha() {
  local value="$1"
  [[ "$value" =~ ^[0-9a-fA-F]{40}$ ]] || return 1
  printf '%s' "${value,,}"
}

EXPECTED_HEAD_SHA="$(normalize_sha "$EXPECTED_HEAD_SHA")" || {
  echo "::error title=Invalid preview head SHA::Expected a canonical 40-character hexadecimal head SHA." >&2
  exit 1
}
EXPECTED_BASE_SHA="$(normalize_sha "$EXPECTED_BASE_SHA")" || {
  echo "::error title=Invalid preview base SHA::Expected a canonical 40-character hexadecimal base SHA." >&2
  exit 1
}
EXPECTED_MERGE_SHA="$(normalize_sha "$EXPECTED_MERGE_SHA")" || {
  echo "::error title=Invalid preview merge SHA::Expected a canonical 40-character hexadecimal merge SHA." >&2
  exit 1
}

pull_request_json="$(gh api "repos/${GITHUB_REPOSITORY}/pulls/${PR_NUMBER}")"
state="$(jq -r '.state // empty' <<<"$pull_request_json")"
repository="$(jq -r '.head.repo.full_name // empty' <<<"$pull_request_json")"
current_head_sha="$(jq -r '.head.sha // empty' <<<"$pull_request_json")"
base_ref="$(jq -r '.base.ref // empty' <<<"$pull_request_json")"
rest_base_sha="$(jq -r '.base.sha // empty' <<<"$pull_request_json")"
raw_merge_sha="$(jq -r '.merge_commit_sha // empty' <<<"$pull_request_json")"

if ! current_head_sha="$(normalize_sha "$current_head_sha")" ||
  ! rest_base_sha="$(normalize_sha "$rest_base_sha")"; then
  echo "::error title=Preview source binding changed::The current PR metadata contains a malformed head or base SHA${STAGE:+ $STAGE}." >&2
  exit 1
fi
if [[ "$state" != open || "$repository" != "$GITHUB_REPOSITORY" ||
  "$current_head_sha" != "$EXPECTED_HEAD_SHA" ]]; then
  echo "::error title=Preview source binding changed::The current PR head, state, or repository no longer matches the validated artifact${STAGE:+ $STAGE}." >&2
  exit 1
fi
if [[ "$base_ref" != main && "$base_ref" != develop ]]; then
  echo "::error title=Preview source binding changed::The current PR base branch is not an eligible preview branch${STAGE:+ $STAGE}." >&2
  exit 1
fi

if ! base_ref_json="$(gh api "repos/${GITHUB_REPOSITORY}/git/ref/heads/${base_ref}")" ||
  ! authoritative_base_sha="$(jq -er --arg expected_ref "refs/heads/${base_ref}" '
    if .ref == $expected_ref and .object.type == "commit" and (.object.sha | type) == "string" then
      .object.sha
    else
      empty
    end' <<<"$base_ref_json")" ||
  ! authoritative_base_sha="$(normalize_sha "$authoritative_base_sha")"; then
  echo "::error title=Preview source binding changed::The current PR base branch ref could not be resolved to a canonical commit${STAGE:+ $STAGE}." >&2
  exit 1
fi
if [[ "$authoritative_base_sha" != "$EXPECTED_BASE_SHA" ]]; then
  echo "::error title=Preview source binding changed::The current PR base branch ref no longer matches the validated artifact${STAGE:+ $STAGE}." >&2
  exit 1
fi

if [[ -n "$raw_merge_sha" ]]; then
  if ! current_merge_sha="$(normalize_sha "$raw_merge_sha")" ||
    [[ "$current_merge_sha" != "$EXPECTED_MERGE_SHA" ]]; then
    echo "::error title=Preview source binding changed::The current PR merge no longer matches the validated artifact${STAGE:+ $STAGE}." >&2
    exit 1
  fi
  if ! merge_commit_json="$(gh api "repos/${GITHUB_REPOSITORY}/commits/${current_merge_sha}")" ||
    ! merge_parents="$(jq -er '
      if (.parents | type) != "array" or (.parents | length) != 2 then
        empty
      else
        [.parents[0].sha, .parents[1].sha]
        | if all(.[]; type == "string" and test("^[0-9A-Fa-f]{40}$")) then
            map(ascii_downcase) | join(" ")
          else
            empty
          end
      end' <<<"$merge_commit_json")" ||
    [[ "$merge_parents" != "$EXPECTED_BASE_SHA $EXPECTED_HEAD_SHA" ]]; then
    echo "::error title=Preview source binding changed::The current PR merge is not exactly the validated two-parent merge of the authoritative base and head${STAGE:+ $STAGE}." >&2
    exit 1
  fi
elif [[ "$EXPECTED_MERGE_SHA" != "$EXPECTED_HEAD_SHA" ]]; then
  echo "::error title=Preview source binding changed::The current PR has no generated test merge for the validated artifact${STAGE:+ $STAGE}." >&2
  exit 1
fi
