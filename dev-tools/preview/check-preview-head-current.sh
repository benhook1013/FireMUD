#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 2 ]]; then
  echo "usage: $0 <pr_number> <expected_head_sha>" >&2
  exit 2
fi

if [[ -z "${GITHUB_REPOSITORY:-}" ]]; then
  echo "GITHUB_REPOSITORY is required" >&2
  exit 2
fi

pr_number="$1"
expected_head_sha="$2"

actual_head_sha="$(gh api "repos/${GITHUB_REPOSITORY}/pulls/${pr_number}" --jq '.head.sha')"

if [[ -z "${actual_head_sha}" ]]; then
  echo "Unable to resolve current PR head SHA for PR #${pr_number}" >&2
  exit 3
fi

if [[ "${actual_head_sha}" != "${expected_head_sha}" ]]; then
  echo "Preview target superseded: PR #${pr_number} moved from ${expected_head_sha} to ${actual_head_sha}."
  exit 10
fi

echo "Preview target still current for PR #${pr_number}: ${actual_head_sha}"
