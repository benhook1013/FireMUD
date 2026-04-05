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
manifest_timeout_seconds="${PREVIEW_IMAGE_MANIFEST_TIMEOUT_SECONDS:-20}"
start_epoch="${SECONDS}"

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
accept_header='application/vnd.oci.image.index.v1+json, application/vnd.oci.image.manifest.v1+json, application/vnd.docker.distribution.manifest.list.v2+json, application/vnd.docker.distribution.manifest.v2+json'

while (( SECONDS < deadline )); do
  loop_start="${SECONDS}"
  missing=()
  probe_summary=()
  for service in "${services[@]}"; do
    manifest_url="https://ghcr.io/v2/benhook1013/${service}/manifests/${image_tag}"
    if curl \
      --silent \
      --show-error \
      --fail \
      --head \
      --location \
      --max-time "${manifest_timeout_seconds}" \
      --user "${PREVIEW_GHCR_USERNAME}:${PREVIEW_GHCR_TOKEN}" \
      --header "Accept: ${accept_header}" \
      "${manifest_url}" >/dev/null 2>&1; then
      probe_summary+=("${service}:ok")
    else
      rc=$?
      if [[ $rc -eq 28 ]]; then
        probe_summary+=("${service}:timeout")
      else
        probe_summary+=("${service}:missing")
      fi
      missing+=("$service")
    fi
  done

  if (( ${#missing[@]} == 0 )); then
    printf 'All required preview images are available for %s after %ss.\n' \
      "${image_tag}" "$((SECONDS - start_epoch))"
    exit 0
  fi

  printf 'Waiting for preview images for %s after %ss. Missing: %s\n' \
    "${image_tag}" "$((SECONDS - start_epoch))" "${missing[*]}"
  printf 'Probe summary: %s\n' "${probe_summary[*]}"
  if (( SECONDS - loop_start >= manifest_timeout_seconds )); then
    printf 'Manifest probe loop consumed at least %ss this round; inspect GHCR availability or docker manifest latency.\n' \
      "${manifest_timeout_seconds}"
  fi
  sleep "$sleep_seconds"
done

printf 'Timed out waiting for preview images for %s after %ss.\n' \
  "${image_tag}" "$((SECONDS - start_epoch))" >&2
printf 'Still missing: %s\n' "${missing[*]:-unknown}" >&2
exit 1
