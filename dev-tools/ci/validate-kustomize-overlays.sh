#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(git rev-parse --show-toplevel)"

STAGE_OVERLAY="$ROOT_DIR/k8s/overlays/stage"
PROD_OVERLAY="$ROOT_DIR/k8s/overlays/prod"

require_cmd() {
  command -v "$1" >/dev/null 2>&1 || {
    echo "Missing required command: $1" >&2
    exit 1
  }
}

require_cmd kubectl
require_cmd docker
require_cmd python3

render_overlay() {
  local overlay="$1"
  kubectl kustomize "$overlay"
}

extract_images() {
  grep -E '^[[:space:]]*image:[[:space:]]*' | sed -E 's/^[[:space:]]*image:[[:space:]]*//' | awk '{print $1}' | sort -u
}

check_images_exist() {
  local name="$1"
  local overlay="$2"

  echo "::group::Render $name overlay"
  local rendered
  rendered="$(render_overlay "$overlay")"
  echo "::endgroup::"

  echo "::group::Check $name images exist in registry"
  local images
  images="$(printf '%s\n' "$rendered" | extract_images)"
  if [ -z "$images" ]; then
    echo "No images found in rendered $name overlay" >&2
    exit 1
  fi

  while IFS= read -r image; do
    [ -n "$image" ] || continue
    echo "Inspecting $image"
    docker buildx imagetools inspect "$image" >/dev/null
  done <<<"$images"
  echo "::endgroup::"
}

check_stage_has_no_backup_schedules_unless_enabled() {
  local enabled_marker="$STAGE_OVERLAY/STAGING_BACKUPS_ENABLED"

  echo "::group::Render stage overlay"
  local rendered
  rendered="$(render_overlay "$STAGE_OVERLAY")"
  echo "::endgroup::"

  local has_backup_cronjobs="false"
  if printf '%s\n' "$rendered" | grep -qE '^[[:space:]]*name:[[:space:]]*(firemud-pg-dump|verify-velero-backups)[[:space:]]*$'; then
    has_backup_cronjobs="true"
  fi

  local has_velero_schedules="false"
  if printf '%s\n' "$rendered" | grep -qE '^apiVersion:[[:space:]]*velero\\.io/v1[[:space:]]*$' && printf '%s\n' "$rendered" | grep -qE '^kind:[[:space:]]*Schedule[[:space:]]*$'; then
    has_velero_schedules="true"
  fi

  if [ "$has_backup_cronjobs" = "true" ] || [ "$has_velero_schedules" = "true" ]; then
    if [ ! -f "$enabled_marker" ]; then
      echo "Stage overlay appears to include backup-related resources (CronJobs and/or Velero schedules), but $enabled_marker is missing." >&2
      echo "If staging backups are intentionally enabled, add the marker file to acknowledge the operational change." >&2
      exit 1
    fi
  fi
}

run_preflight_policy_checks() {
  echo "::group::Run canonical preflight policy checks (ci-static)"
  FIREMUD_PREFLIGHT_CONTEXT=ci-static \
    FIREMUD_PREFLIGHT_OUTPUT=/tmp/firemud-preflight-staging.json \
    "$ROOT_DIR/dev-tools/deploy/preflight.sh" staging

  FIREMUD_PREFLIGHT_CONTEXT=ci-static \
    FIREMUD_PREFLIGHT_OUTPUT=/tmp/firemud-preflight-production.json \
    "$ROOT_DIR/dev-tools/deploy/preflight.sh" production
  echo "::endgroup::"
}

check_stage_has_no_backup_schedules_unless_enabled
run_preflight_policy_checks
check_images_exist "stage" "$STAGE_OVERLAY"
check_images_exist "prod" "$PROD_OVERLAY"

echo "Kustomize overlay validation passed."
