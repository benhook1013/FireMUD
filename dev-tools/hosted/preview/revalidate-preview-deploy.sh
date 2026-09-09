#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 2 ]]; then
  echo "usage: $0 <pr_number> <expected_head_sha>" >&2
  exit 1
fi

pr_number="$1"
expected_head_sha="$2"
script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
eligibility_script="${PREVIEW_ELIGIBILITY_SCRIPT:-${script_dir}/preview-eligibility.py}"

if ! [[ "$pr_number" =~ ^[1-9][0-9]*$ ]]; then
  echo "PR number must be a positive integer" >&2
  exit 1
fi
if ! [[ "$expected_head_sha" =~ ^[0-9a-fA-F]{40}$ ]]; then
  echo "expected head SHA must be exactly 40 hexadecimal characters" >&2
  exit 1
fi
if [[ -z "${GITHUB_REPOSITORY:-}" || -z "${GH_TOKEN:-}" ]]; then
  echo "GITHUB_REPOSITORY and GH_TOKEN are required" >&2
  exit 1
fi

refuse_preview() {
  echo "::error::Refusing preview deploy for PR #${pr_number}: $1"
  echo "Refusing preview deploy for PR #${pr_number}: $1" >&2
  exit 1
}

if ! pull_request_json="$(gh api "repos/${GITHUB_REPOSITORY}/pulls/${pr_number}")"; then
  refuse_preview "current pull request metadata is unavailable"
fi
if ! refusal_reason="$(python3 "$eligibility_script" \
  --revalidate-deploy \
  --expected-repository "$GITHUB_REPOSITORY" \
  --expected-head-sha "$expected_head_sha" <<<"$pull_request_json")"; then
  refuse_preview "${refusal_reason:-preview eligibility evaluation failed}"
fi
