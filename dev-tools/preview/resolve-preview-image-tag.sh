#!/usr/bin/env bash
set -euo pipefail

if [[ $# -lt 1 ]]; then
  echo "usage: $0 <requested_image_tag> [branch] [base_branch]" >&2
  exit 1
fi

if [[ -z "${GITHUB_REPOSITORY:-}" || -z "${GH_TOKEN:-}" ]]; then
  echo "GITHUB_REPOSITORY and GH_TOKEN are required" >&2
  exit 1
fi

requested_image_tag="$1"
branch="${2:-${GITHUB_HEAD_REF:-${GITHUB_REF_NAME:-}}}"
base_branch="${3:-develop}"

services=(
  account-service
  automation-scripting-service
  entity-management-service
  game-design-service
  game-logic-service
  game-session-service
  logging-admin-service
  social-groups-service
  spring-cloud-gateway
  tcp-proxy-service
  world-management-service
)

images_available() {
  local image_tag="$1"
  local service
  for service in "${services[@]}"; do
    if ! docker manifest inspect "ghcr.io/benhook1013/${service}:${image_tag}" >/dev/null 2>&1; then
      return 1
    fi
  done
  return 0
}

runtime_run_exists_for_tag() {
  local image_tag="$1"
  local branch_name="$2"
  gh run list \
    --workflow runtime-images.yml \
    --branch "$branch_name" \
    --limit 20 \
    --json headSha \
    --jq "map(select(.headSha == \"${image_tag}\")) | length > 0"
}

latest_available_tag_for_branch() {
  local branch_name="$1"
  local candidate

  while IFS= read -r candidate; do
    [[ -n "$candidate" ]] || continue
    if images_available "$candidate"; then
      printf '%s\n' "$candidate"
      return 0
    fi
  done < <(
    gh run list \
      --workflow runtime-images.yml \
      --branch "$branch_name" \
      --limit 30 \
      --json headSha,status,conclusion \
      --jq '.[] | select(.status == "completed" and .conclusion == "success") | .headSha'
  )

  return 1
}

if images_available "$requested_image_tag"; then
  echo "$requested_image_tag"
  exit 0
fi

if [[ -n "$branch" ]] && [[ "$(runtime_run_exists_for_tag "$requested_image_tag" "$branch")" == "true" ]]; then
  echo "$requested_image_tag"
  exit 0
fi

if [[ -n "$branch" ]]; then
  fallback_tag="$(latest_available_tag_for_branch "$branch" || true)"
  if [[ -n "${fallback_tag:-}" ]]; then
    echo "$fallback_tag"
    exit 0
  fi
fi

if [[ -n "$base_branch" ]]; then
  fallback_tag="$(latest_available_tag_for_branch "$base_branch" || true)"
  if [[ -n "${fallback_tag:-}" ]]; then
    echo "$fallback_tag"
    exit 0
  fi
fi

echo "$requested_image_tag"
