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
jq -e \
  --arg repository "$GITHUB_REPOSITORY" \
  --arg expected_head "$EXPECTED_HEAD_SHA" \
  --arg expected_base "$EXPECTED_BASE_SHA" \
  --arg expected_merge "$EXPECTED_MERGE_SHA" \
  '(.state == "open") and
   (.head.repo.full_name == $repository) and
   (.head.sha == $expected_head) and
   (.base.sha == $expected_base) and
   ((.merge_commit_sha // .head.sha) == $expected_merge)' \
  <<<"$pull_request_json" >/dev/null || {
    echo "::error title=Preview source binding changed::The current PR head, base, merge, or repository no longer matches the validated artifact${STAGE:+ $STAGE}." >&2
    exit 1
  }
