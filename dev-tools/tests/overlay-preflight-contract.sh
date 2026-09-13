#!/usr/bin/env bash
set -euo pipefail
# shellcheck disable=SC2016 # Assertions intentionally match literal workflow and shell source.

REPO_ROOT="$(git rev-parse --show-toplevel)"
OUTPUT_FILE="$(mktemp)"
trap 'rm -f "$OUTPUT_FILE"' EXIT

WORKFLOW="$REPO_ROOT/.github/workflows/validate-kustomize-overlays.yml"
VALIDATOR="$REPO_ROOT/dev-tools/deploy/validate-kustomize-overlays.sh"

assert_workflow_contains() {
  local expected="$1"
  if ! grep -Fq -- "$expected" "$WORKFLOW"; then
    echo "Overlay validation workflow is missing required contract: $expected" >&2
    exit 1
  fi
}

assert_workflow_absent() {
  local forbidden="$1"
  if grep -Fq -- "$forbidden" "$WORKFLOW"; then
    echo "Overlay validation workflow retains redundant build contract: $forbidden" >&2
    exit 1
  fi
}

# The checked-in overlays are digest-pinned, so PR validation must inspect those
# registry references directly. The local image fast path remains a capability of
# the generic validator for local developer workflows.
for forbidden in \
  'actions/setup-java@' \
  'gradle/actions/setup-gradle@' \
  'Build service jars for overlay validation' \
  'Build PR images for overlay validation' \
  'bootJar' \
  'docker build' \
  ':latest'; do
  assert_workflow_absent "$forbidden"
done

# shellcheck disable=SC2016
for required in \
  'permissions:' \
  'contents: read' \
  'packages: read' \
  'uses: step-security/harden-runner@' \
  'egress-policy: audit' \
  'uses: actions/checkout@' \
  'fetch-depth: 0' \
  'uses: ./.github/actions/setup-python' \
  'requirements: yaml' \
  'uses: ./.github/actions/setup-kubectl' \
  'uses: docker/setup-buildx-action@' \
  'uses: docker/login-action@' \
  'registry: ghcr.io' \
  'password: ${{ secrets.GITHUB_TOKEN }}' \
  'run: bash dev-tools/deploy/validate-kustomize-overlays.sh'; do
  assert_workflow_contains "$required"
done

# shellcheck disable=SC2016
for required in \
  'kubectl kustomize "$overlay"' \
  'docker buildx imagetools inspect "$image"' \
  'FIREMUD_PREFLIGHT_CONTEXT=ci-static' \
  'python3 "$ROOT_DIR/dev-tools/deploy/preflight.py" production'; do
  if ! grep -Fq -- "$required" "$VALIDATOR"; then
    echo "Overlay validator is missing required correctness contract: $required" >&2
    exit 1
  fi
done

for overlay in stage prod; do
  rendered_overlay="$(kubectl kustomize "$REPO_ROOT/k8s/overlays/$overlay")"
  while IFS= read -r image; do
    case "$image" in
      ghcr.io/benhook1013/*)
        if [[ ! "$image" =~ @sha256:[0-9a-f]{64}$ ]]; then
          echo "$overlay overlay contains a mutable FireMUD image reference: $image" >&2
          exit 1
        fi
        ;;
    esac
  done < <(printf '%s\n' "$rendered_overlay" | sed -n 's/^[[:space:]]*image:[[:space:]]*//p' | awk '{print $1}')
done

(
  # shellcheck disable=SC1091
  # shellcheck disable=SC1090
  source "$VALIDATOR"
  render_overlay() {
    printf '%s\n' 'image: ghcr.io/benhook1013/example-service@sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa'
  }
  docker() {
    if [[ "${1:-}" = image && "${2:-}" = inspect ]]; then
      return 1
    fi
    if [[ "${1:-}" = buildx && "${2:-}" = imagetools && "${3:-}" = inspect ]]; then
      [[ "${4:-}" = ghcr.io/benhook1013/example-service@sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa ]]
      return
    fi
    echo "unexpected docker invocation: $*" >&2
    return 1
  }
  check_images_exist "contract" "$REPO_ROOT/k8s/overlays/stage"
)

set +e
(
  set -e
  # shellcheck disable=SC1091
  # shellcheck disable=SC1090
  source "$VALIDATOR"
  render_overlay() {
    printf '%s\n' 'image: ghcr.io/benhook1013/example-service@sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa'
  }
  docker() {
    if [[ "${1:-}" = image && "${2:-}" = inspect ]]; then
      return 1
    fi
    if [[ "${1:-}" = buildx && "${2:-}" = imagetools && "${3:-}" = inspect ]]; then
      return 1
    fi
    return 1
  }
  check_images_exist "contract" "$REPO_ROOT/k8s/overlays/stage"
)
registry_validation_status=$?
set -e
if [[ "$registry_validation_status" -eq 0 ]]; then
  echo "Overlay image validation did not fail closed when registry inspection failed" >&2
  exit 1
fi

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

if (
  # shellcheck disable=SC1091
  source "$REPO_ROOT/dev-tools/deploy/validate-kustomize-overlays.sh"
  changed_files_between_base_and_head() {
    printf '%s\n' \
      'k8s/overlays/prod/kustomization.yaml' \
      'design/operations/deployments/production/attestations/one.json' \
      'design/operations/deployments/production/attestations/two.json'
  }
  GITHUB_EVENT_NAME=pull_request GITHUB_BASE_REF=develop run_preflight_policy_checks
) >"$OUTPUT_FILE" 2>&1; then
  echo "Production-applicable validation accepted multiple attestations" >&2
  exit 1
fi

grep -q "must include exactly one attestation file" "$OUTPUT_FILE" || {
  echo "Multiple-attestation failure did not explain the production-applicable contract" >&2
  cat "$OUTPUT_FILE" >&2
  exit 1
}

if ! (
  # shellcheck disable=SC1091
  source "$REPO_ROOT/dev-tools/deploy/validate-kustomize-overlays.sh"
  changed_files_between_base_and_head() {
    printf '%s\n' \
      'k8s/overlays/prod/kustomization.yaml' \
      'design/operations/deployments/production/attestations/deploy-123.json'
  }
  python3() {
    if [[ "${1:-}" == "-" ]]; then
      printf 'rollback-compatible\n'
    else
      printf 'validated production attestation\n'
    fi
  }
  GITHUB_EVENT_NAME=pull_request GITHUB_BASE_REF=develop run_preflight_policy_checks
) >"$OUTPUT_FILE" 2>&1; then
  echo "Production-applicable validation rejected exactly one current-PR attestation" >&2
  cat "$OUTPUT_FILE" >&2
  exit 1
fi

grep -q "validated production attestation" "$OUTPUT_FILE" || {
  echo "Exactly-one-attestation path did not invoke production preflight" >&2
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

grep -q "Skipping static preflight policy enforcement" "$OUTPUT_FILE" || {
  echo "Non-production overlay validation did not take the policy-skip path" >&2
  cat "$OUTPUT_FILE" >&2
  exit 1
}

echo "overlay preflight contract checks passed"
