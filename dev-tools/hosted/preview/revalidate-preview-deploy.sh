#!/usr/bin/env bash
set -euo pipefail

mode=deploy
if [[ "${1:-}" == --cleanup ]]; then
  mode=cleanup
  shift
elif [[ "${1:-}" == --open-cleanup ]]; then
  mode=open-cleanup
  shift
fi
if [[ $# -ne 2 ]]; then
  echo "usage: $0 [--cleanup|--open-cleanup] <pr_number> <expected_head_sha>" >&2
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
  if [[ "$mode" != deploy ]]; then
    echo "::error::Refusing preview cleanup for PR #${pr_number}: $1"
    echo "Refusing preview cleanup for PR #${pr_number}: $1" >&2
  else
    echo "::error::Refusing preview deploy for PR #${pr_number}: $1"
    echo "Refusing preview deploy for PR #${pr_number}: $1" >&2
  fi
  exit 1
}

if ! pull_request_json="$(gh api "repos/${GITHUB_REPOSITORY}/pulls/${pr_number}")"; then
  refuse_preview "current pull request metadata is unavailable"
fi
if jq -e '(.mergeable == null) or (.mergeable_state == "unknown")' \
  <<<"$pull_request_json" >/dev/null 2>&1; then
  retry_attempts="${PREVIEW_METADATA_RETRY_ATTEMPTS:-3}"
  retry_delay_seconds="${PREVIEW_METADATA_RETRY_DELAY_SECONDS:-1}"
  if ! [[ "$retry_attempts" =~ ^[1-9][0-9]*$ ]] ||
    ! [[ "$retry_delay_seconds" =~ ^[0-9]+([.][0-9]+)?$ ]]; then
    refuse_preview "preview mergeability retry settings are invalid"
  fi
  for ((retry_attempt = 1; retry_attempt <= retry_attempts; retry_attempt++)); do
    if ! refreshed_pull_request_json="$(gh api "repos/${GITHUB_REPOSITORY}/pulls/${pr_number}")"; then
      refuse_preview "current pull request metadata is unavailable"
    fi
    if ! jq -e 'type == "object"' <<<"$refreshed_pull_request_json" >/dev/null 2>&1; then
      refuse_preview "current pull request metadata is malformed"
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
if [[ "$mode" == deploy ]]; then
  if ! jq -e 'type == "object"' <<<"$pull_request_json" >/dev/null 2>&1; then
    refuse_preview "current pull request metadata is malformed"
  fi
  base_ref="$(jq -er '.base.ref // empty' <<<"$pull_request_json" 2>/dev/null || true)"
  base_repository="$(jq -er '.base.repo.full_name // empty' <<<"$pull_request_json" 2>/dev/null || true)"
  if [[ -z "$base_ref" || -z "$base_repository" ]]; then
    refuse_preview "current pull request base metadata is missing"
  fi
  if [[ "$base_repository" != "$GITHUB_REPOSITORY" ]]; then
    refuse_preview "base repository is not trusted (expected=$GITHUB_REPOSITORY, current=$base_repository)"
  fi
  if ! base_ref_json="$(gh api "repos/${GITHUB_REPOSITORY}/git/ref/heads/${base_ref}")"; then
    refuse_preview "base branch ${base_ref} is unavailable"
  fi
  current_base_sha="$(jq -er '.object.sha // empty' <<<"$base_ref_json" 2>/dev/null || true)"
  if ! [[ "$current_base_sha" =~ ^[0-9a-fA-F]{40}$ ]]; then
    refuse_preview "current base branch ref SHA is not canonical"
  fi
  if ! jq -e '
      .mergeable == true and
      (.mergeable_state | type) == "string" and
      .mergeable_state != "dirty" and
      .mergeable_state != "conflicting" and
      .mergeable_state != "unknown"
    ' <<<"$pull_request_json" >/dev/null; then
    refuse_preview "current pull request is not mergeable"
  fi
fi
revalidation_mode="--revalidate-${mode}"
if ! refusal_reason="$(python3 "$eligibility_script" \
  "$revalidation_mode" \
  --expected-repository "$GITHUB_REPOSITORY" \
  --expected-head-sha "$expected_head_sha" <<<"$pull_request_json")"; then
  refuse_preview "${refusal_reason:-preview eligibility evaluation failed}"
fi
