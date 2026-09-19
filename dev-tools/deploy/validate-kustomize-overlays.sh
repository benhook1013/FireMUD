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

changed_files_between_base_and_head() {
  local base_ref="$1"
  git fetch origin "$base_ref" --quiet
  local merge_base
  merge_base="$(git merge-base "origin/$base_ref" HEAD)"
  git diff --name-only "$merge_base"...HEAD
}

production_policy_applies_to_changes() {
  local changed_files="$1"
  local changed_file

  while IFS= read -r changed_file; do
    case "$changed_file" in
      k8s/overlays/prod|k8s/overlays/prod/*|k8s/base|k8s/base/*|k8s/postgres|k8s/postgres/*|k8s/velero|k8s/velero/*)
        return 0
        ;;
    esac
  done <<<"$changed_files"

  return 1
}

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
    if [[ "$image" == ghcr.io/benhook1013/* ]]; then
      if docker image inspect "$image" >/dev/null 2>&1; then
        echo "Found local PR-built image for $image"
        continue
      fi
    fi

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
  local promotion_attestation=""
  local backup_readiness=""
  local deployment_ref=""
  local production_pr_validation="false"

  if [[ "${GITHUB_EVENT_NAME:-}" = "pull_request" && -n "${GITHUB_BASE_REF:-}" ]]; then
    local changed_files
    changed_files="$(changed_files_between_base_and_head "$GITHUB_BASE_REF")"

    if production_policy_applies_to_changes "$changed_files"; then
      mapfile -t attestation_files < <(printf '%s\n' "$changed_files" | grep '^design/operations/deployments/production/attestations/.*\.json$' || true)
      if [[ "${#attestation_files[@]}" -ne 1 ]]; then
        echo "Production-applicable Kubernetes PRs must include exactly one attestation file under design/operations/deployments/production/attestations/." >&2
        exit 1
      else
        production_pr_validation="true"
        promotion_attestation="${attestation_files[0]}"
        deployment_ref="$(basename "$promotion_attestation" .json)"

        local rollback_mode
        rollback_mode="$(python3 - <<'PY' "$ROOT_DIR/$promotion_attestation"
import json
import pathlib
import sys

path = pathlib.Path(sys.argv[1])
data = json.loads(path.read_text(encoding="utf-8"))
print(str(data.get("rollbackMode", "")))
PY
)"

        if [[ "$rollback_mode" = "roll-forward-only" ]]; then
          mapfile -t backup_files < <(printf '%s\n' "$changed_files" | grep '^design/operations/deployments/production/backup-readiness/.*\.json$' || true)
          if [[ "${#backup_files[@]}" -ne 1 ]]; then
            echo "Roll-forward-only production overlay PRs must include exactly one backup-readiness file under design/operations/deployments/production/backup-readiness/." >&2
            exit 1
          fi
          backup_readiness="${backup_files[0]}"
        fi
      fi
    fi
  fi

  if [[ "$production_pr_validation" = "true" ]]; then
    FIREMUD_PREFLIGHT_CONTEXT=ci-static \
      FIREMUD_DEPLOYMENT_REF="$deployment_ref" \
      FIREMUD_PREFLIGHT_OUTPUT=/tmp/firemud-preflight-production.json \
      FIREMUD_PROMOTION_ATTESTATION="$promotion_attestation" \
      FIREMUD_BACKUP_READINESS_EVIDENCE="$backup_readiness" \
      python3 "$ROOT_DIR/dev-tools/deploy/preflight.py" production
  else
    echo "Skipping static preflight policy enforcement because no production attestation context is present."
    echo "Overlay render and image validation still run below."
  fi
  echo "::endgroup::"
}

main() {
  require_cmd kubectl
  require_cmd docker
  require_cmd python3

  check_stage_has_no_backup_schedules_unless_enabled
  run_preflight_policy_checks
  check_images_exist "stage" "$STAGE_OVERLAY"
  check_images_exist "prod" "$PROD_OVERLAY"

  echo "Kustomize overlay validation passed."
}

if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then
  main "$@"
fi
