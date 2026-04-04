#!/usr/bin/env bash
set -euo pipefail

if [[ $# -lt 1 ]]; then
  echo "usage: $0 <image_tag>" >&2
  exit 1
fi

if [[ -z "${PREVIEW_GHCR_USERNAME:-}" || -z "${PREVIEW_GHCR_TOKEN:-}" ]]; then
  echo "PREVIEW_GHCR_USERNAME and PREVIEW_GHCR_TOKEN are required" >&2
  exit 1
fi

image_tag="$1"
timeout_seconds="${PREVIEW_IMAGE_WAIT_TIMEOUT_SECONDS:-1800}"
sleep_seconds="${PREVIEW_IMAGE_WAIT_SLEEP_SECONDS:-10}"

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

deadline=$((SECONDS + timeout_seconds))

while (( SECONDS < deadline )); do
  missing=()
  for service in "${services[@]}"; do
    image="ghcr.io/benhook1013/${service}:${image_tag}"
    if ! docker manifest inspect "$image" >/dev/null 2>&1; then
      missing+=("$service")
    fi
  done

  if (( ${#missing[@]} == 0 )); then
    echo "All required preview images are available for ${image_tag}."
    exit 0
  fi

  echo "Waiting for preview images for ${image_tag}. Missing: ${missing[*]}"
  sleep "$sleep_seconds"
done

echo "Timed out waiting for preview images for ${image_tag}." >&2
printf 'Still missing: %s\n' "${missing[*]:-unknown}" >&2
exit 1
