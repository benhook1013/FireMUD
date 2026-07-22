#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="$(git rev-parse --show-toplevel)"
OUTPUT_FILE="$(mktemp)"
trap 'rm -f "$OUTPUT_FILE"' EXIT

if (
  # shellcheck disable=SC1091
  source "$REPO_ROOT/dev-tools/deploy/validate-kustomize-overlays.sh"
  changed_files_between_base_and_head() {
    printf '%s\n' 'k8s/overlays/prod/kustomization.yaml'
  }
  GITHUB_EVENT_NAME=pull_request GITHUB_BASE_REF=develop run_preflight_policy_checks
) >"$OUTPUT_FILE" 2>&1; then
  echo "Production overlay validation accepted a PR without an attestation" >&2
  exit 1
fi

grep -q "must include exactly one attestation file" "$OUTPUT_FILE" || {
  echo "Missing-attestation failure did not explain the production contract" >&2
  cat "$OUTPUT_FILE" >&2
  exit 1
}

(
  # shellcheck disable=SC1091
  source "$REPO_ROOT/dev-tools/deploy/validate-kustomize-overlays.sh"
  changed_files_between_base_and_head() {
    printf '%s\n' 'k8s/overlays/stage/kustomization.yaml'
  }
  GITHUB_EVENT_NAME=pull_request GITHUB_BASE_REF=develop run_preflight_policy_checks
) >"$OUTPUT_FILE" 2>&1

echo "overlay preflight contract checks passed"
