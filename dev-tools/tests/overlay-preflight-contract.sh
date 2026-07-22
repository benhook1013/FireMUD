#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="$(git rev-parse --show-toplevel)"
OUTPUT_FILE="$(mktemp)"
trap 'rm -f "$OUTPUT_FILE"' EXIT

assert_production_change_requires_attestation() {
  if (
    # shellcheck disable=SC1091
    source "$REPO_ROOT/dev-tools/deploy/validate-kustomize-overlays.sh"
    export TEST_CHANGED_FILE="$1"
    changed_files_between_base_and_head() {
      printf '%s\n' "$TEST_CHANGED_FILE"
    }
    GITHUB_EVENT_NAME=pull_request GITHUB_BASE_REF=develop run_preflight_policy_checks
  ) >"$OUTPUT_FILE" 2>&1; then
    echo "Production-applicable validation accepted $1 without an attestation" >&2
    exit 1
  fi

  grep -q "must include exactly one attestation file" "$OUTPUT_FILE" || {
    echo "Missing-attestation failure did not explain the production-applicable contract for $1" >&2
    cat "$OUTPUT_FILE" >&2
    exit 1
  }
}

for changed_file in \
  'k8s/overlays/prod/kustomization.yaml' \
  'k8s/base/account-service.yaml' \
  'k8s/postgres/pg-dump-cronjob.yaml' \
  'k8s/velero/schedule.yaml'; do
  assert_production_change_requires_attestation "$changed_file"
done

(
  # shellcheck disable=SC1091
  source "$REPO_ROOT/dev-tools/deploy/validate-kustomize-overlays.sh"
  changed_files_between_base_and_head() {
    printf '%s\n' 'k8s/overlays/stage/kustomization.yaml'
  }
  GITHUB_EVENT_NAME=pull_request GITHUB_BASE_REF=develop run_preflight_policy_checks
) >"$OUTPUT_FILE" 2>&1

grep -q "Skipping static preflight policy enforcement" "$OUTPUT_FILE" || {
  echo "Non-production overlay validation did not take the policy-skip path" >&2
  cat "$OUTPUT_FILE" >&2
  exit 1
}

echo "overlay preflight contract checks passed"
