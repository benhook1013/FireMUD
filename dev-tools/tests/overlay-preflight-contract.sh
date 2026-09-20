#!/usr/bin/env bash
set -euo pipefail
# shellcheck disable=SC2016,SC2317 # Assertions match literal source; test stubs are invoked indirectly.

REPO_ROOT="$(git rev-parse --show-toplevel)"
OUTPUT_FILE="$(mktemp)"
trap 'rm -f "$OUTPUT_FILE"' EXIT

WORKFLOW="$REPO_ROOT/.github/workflows/validate-kustomize-overlays.yml"
VALIDATOR="$REPO_ROOT/dev-tools/deploy/validate-kustomize-overlays.sh"

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

python3 - "$WORKFLOW" <<'PY'
import pathlib
import sys

import yaml

workflow_path = pathlib.Path(sys.argv[1])
workflow = yaml.safe_load(workflow_path.read_text(encoding="utf-8"))
job = workflow.get("jobs", {}).get("validate-overlays")
if not isinstance(job, dict):
    raise SystemExit("Overlay validation workflow is missing jobs.validate-overlays")

effective_permissions = job.get("permissions", workflow.get("permissions"))
expected_permissions = {"contents": "read", "packages": "read"}
if effective_permissions != expected_permissions:
    raise SystemExit(
        "Overlay validation job permissions changed: "
        f"expected {expected_permissions!r}, got {effective_permissions!r}"
    )

expected_steps = {
    "Harden runner": {
        "uses": "step-security/harden-runner@ab7a9404c0f3da075243ca237b5fac12c98deaa5",
        "with": {"egress-policy": "audit"},
    },
    "⬇️ Checkout Code": {
        "uses": "actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1",
        "with": {"fetch-depth": 0},
    },
    "Set up canonical Python dependencies": {
        "uses": "./.github/actions/setup-python",
        "with": {"requirements": "yaml"},
    },
    "🧰 Set up kubectl": {"uses": "./.github/actions/setup-kubectl"},
    "🐳 Set up Docker": {
        "uses": "docker/setup-buildx-action@f87e5991a6d7451dcb8d9637bfbc97413f497069",
    },
    "🔐 Login to GHCR": {
        "uses": "docker/login-action@dbcb813823bdd20940b903addbd779551569679f",
        "with": {
            "registry": "ghcr.io",
            "username": "${{ github.actor }}",
            "password": "${{ secrets.GITHUB_TOKEN }}",
        },
    },
    "✅ Validate overlays and image availability": {
        "run": "bash dev-tools/deploy/validate-kustomize-overlays.sh",
    },
}

steps_by_name = {}
for step in job.get("steps", []):
    name = step.get("name")
    if name in expected_steps:
        steps_by_name.setdefault(name, []).append(step)

for name, expected in expected_steps.items():
    matching_steps = steps_by_name.get(name, [])
    if len(matching_steps) != 1:
        raise SystemExit(
            f"Overlay validation workflow must contain exactly one {name!r} step"
        )
    actual = matching_steps[0]
    for field, expected_value in expected.items():
        if actual.get(field) != expected_value:
            raise SystemExit(
                f"Overlay validation {name!r} step changed its {field} contract: "
                f"expected {expected_value!r}, got {actual.get(field)!r}"
            )
PY

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
  done < <(printf '%s\n' "$rendered_overlay" | sed -E -n 's/^[[:space:]]*(-[[:space:]]*)?image:[[:space:]]*//p' | awk '{print $1}')
done

if bash -s "$VALIDATOR" <<'BASH'
  # shellcheck disable=SC1091
  # shellcheck disable=SC1090
  set -e
  source "$1"
  render_overlay() {
    printf '%s\n' \
      'images:' \
      '  - image: ghcr.io/benhook1013/example-service@sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa' \
      'container:' \
      '  image: ghcr.io/benhook1013/example-service@sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa'
  }
  docker() {
    if [[ "${1:-}" = image && "${2:-}" = inspect ]]; then
      return 1
    fi
    if [[ "${1:-}" = buildx && "${2:-}" = imagetools && "${3:-}" = inspect ]]; then
      if [[ "${4:-}" = ghcr.io/benhook1013/example-service@sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa ]]; then
        return 0
      fi
      echo "unexpected docker image: ${4:-}" >&2
      return 1
    fi
    echo "unexpected docker invocation: $*" >&2
    return 1
  }
  check_images_exist "contract" "$ROOT_DIR/k8s/overlays/stage"
BASH
then
  :
else
  echo "Digest-pinned stage overlay image validation failed unexpectedly" >&2
  exit 1
fi

if bash -s "$VALIDATOR" >"$OUTPUT_FILE" 2>&1 <<'BASH'
  set -e
  # shellcheck disable=SC1091
  # shellcheck disable=SC1090
  source "$1"
  render_overlay() {
    printf '%s\n' \
      'images:' \
      '  - image: ghcr.io/benhook1013/example-service@sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa' \
      'container:' \
      '  image: ghcr.io/benhook1013/example-service@sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa'
  }
  docker() {
    if [[ "${1:-}" = image && "${2:-}" = inspect ]]; then
      return 1
    fi
    if [[ "${1:-}" = buildx && "${2:-}" = imagetools && "${3:-}" = inspect ]]; then
      echo "registry inspection failed" >&2
      return 1
    fi
    return 1
  }
  check_images_exist "contract" "$ROOT_DIR/k8s/overlays/stage"
BASH
then
  registry_validation_status=0
else
  registry_validation_status=$?
fi
if [[ "$registry_validation_status" -eq 0 ]]; then
  echo "Overlay image validation did not fail closed when registry inspection failed" >&2
  exit 1
fi

grep -q "registry inspection failed" "$OUTPUT_FILE" || {
  echo "Registry validation failure did not reach the registry inspection boundary" >&2
  cat "$OUTPUT_FILE" >&2
  exit 1
}

assert_production_change_requires_attestation() {
  local test_changed_file="$1"
  if (
    # shellcheck disable=SC1091
    source "$REPO_ROOT/dev-tools/deploy/validate-kustomize-overlays.sh"
    changed_files_between_base_and_head() {
      printf '%s\n' "$test_changed_file"
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
  'k8s/overlays/prod' \
  'k8s/overlays/prod/kustomization.yaml'; do
  assert_production_change_requires_attestation "$changed_file"
done

assert_nonproduction_change_skips_attestation() {
  local test_changed_file="$1"
  (
    # shellcheck disable=SC1091
    source "$REPO_ROOT/dev-tools/deploy/validate-kustomize-overlays.sh"
    changed_files_between_base_and_head() {
      printf '%s\n' "$test_changed_file"
    }
    GITHUB_EVENT_NAME=pull_request GITHUB_BASE_REF=develop run_preflight_policy_checks
  ) >"$OUTPUT_FILE" 2>&1

  grep -q "Skipping static preflight policy enforcement" "$OUTPUT_FILE" || {
    echo "Non-production change unexpectedly required production attestation: $1" >&2
    cat "$OUTPUT_FILE" >&2
    exit 1
  }
}

for changed_file in \
  'k8s/base/account-service.yaml' \
  'k8s/postgres/pg-dump-cronjob.yaml' \
  'k8s/velero/schedule.yaml'; do
  assert_nonproduction_change_skips_attestation "$changed_file"
done

if ! (
  # shellcheck disable=SC1091
  source "$REPO_ROOT/dev-tools/deploy/validate-kustomize-overlays.sh"
  changed_files_between_base_and_head() {
    printf '%s\n' 'k8s/velero/verify-backups-cronjob.yaml'
  }
  GITHUB_EVENT_NAME=pull_request GITHUB_BASE_REF=develop run_preflight_policy_checks
) >"$OUTPUT_FILE" 2>&1; then
  echo "Standalone Velero preflight validation failed; captured output follows:" >&2
  cat "$OUTPUT_FILE" >&2
  exit 1
fi

grep -q "Skipping static preflight policy enforcement" "$OUTPUT_FILE" || {
  echo "Standalone Velero pre-release assets incorrectly required production attestation" >&2
  cat "$OUTPUT_FILE" >&2
  exit 1
}

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

(
  # shellcheck disable=SC1091
  source "$REPO_ROOT/dev-tools/deploy/validate-kustomize-overlays.sh"
  require_cmd() {
    :
  }
  check_stage_has_no_backup_schedules_unless_enabled() {
    :
  }
  check_images_exist() {
    :
  }
  changed_files_between_base_and_head() {
    printf '%s\n' \
      'k8s/overlays/prod/kustomization.yaml' \
      'design/operations/deployments/production/attestations/deploy-123.json'
  }
  production_preflight_invoked="false"
  python3() {
    if [[ "${1:-}" == "-" ]]; then
      printf 'rollback-compatible\n'
      return 0
    fi
    if [[ "${1:-}" == "$REPO_ROOT/dev-tools/deploy/preflight.py" && "${2:-}" == "production" ]]; then
      if [[ "${FIREMUD_PREFLIGHT_CONTEXT:-}" != "ci-static" ]]; then
        echo "unexpected preflight context: ${FIREMUD_PREFLIGHT_CONTEXT:-}" >&2
        return 1
      fi
      production_preflight_invoked="true"
      return 0
    fi
    echo "unexpected python3 invocation: $*" >&2
    return 1
  }
  GITHUB_EVENT_NAME=pull_request GITHUB_BASE_REF=develop main
  if [[ "$production_preflight_invoked" != "true" ]]; then
    echo "Validator main did not invoke canonical production preflight" >&2
    exit 1
  fi
)

if ! (
  # shellcheck disable=SC1091
  source "$REPO_ROOT/dev-tools/deploy/validate-kustomize-overlays.sh"
  changed_files_between_base_and_head() {
    printf '%s\n' 'k8s/base/account-service.yaml'
  }
  kubectl_render_trace="$(mktemp)"
  docker_image_inspect_trace="$(mktemp)"
  trap 'rm -f "$kubectl_render_trace" "$docker_image_inspect_trace"' EXIT
  python3_invoked="false"
  kubectl() {
    if [[ "${1:-}" != "kustomize" ]]; then
      echo "unexpected kubectl invocation: $*" >&2
      return 1
    fi
    printf '%s\n' "${2:-}" >>"$kubectl_render_trace"
    printf '%s\n' \
      'apiVersion: v1' \
      'kind: ConfigMap' \
      'metadata:' \
      '  name: contract' \
      'data:' \
      '  image: ghcr.io/benhook1013/example-service@sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa'
  }
  docker() {
    if [[ "${1:-}" = image && "${2:-}" = inspect ]]; then
      printf '%s\n' "${3:-}" >>"$docker_image_inspect_trace"
      return 0
    fi
    echo "unexpected docker invocation: $*" >&2
    return 1
  }
  python3() {
    python3_invoked="true"
    echo "unexpected production preflight invocation: $*" >&2
    return 1
  }
  GITHUB_EVENT_NAME=pull_request GITHUB_BASE_REF=develop main
  if [[ "$python3_invoked" != "false" ]]; then
    echo "Shared-base validation unexpectedly invoked production preflight" >&2
    exit 1
  fi
  mapfile -t kubectl_render_calls <"$kubectl_render_trace"
  mapfile -t docker_image_inspect_calls <"$docker_image_inspect_trace"
  if [[ "${#kubectl_render_calls[@]}" -ne 3 ]]; then
    echo "Expected stage backup-marker plus stage/prod image renders, got ${#kubectl_render_calls[@]}" >&2
    exit 1
  fi
  if [[ "${kubectl_render_calls[0]}" != "$ROOT_DIR/k8s/overlays/stage" || \
    "${kubectl_render_calls[1]}" != "$ROOT_DIR/k8s/overlays/stage" || \
    "${kubectl_render_calls[2]}" != "$ROOT_DIR/k8s/overlays/prod" ]]; then
    printf 'Unexpected overlay render sequence: %s\n' "${kubectl_render_calls[*]}" >&2
    exit 1
  fi
  if [[ "${#docker_image_inspect_calls[@]}" -ne 2 ]]; then
    echo "Expected both stage/prod image validations, got ${#docker_image_inspect_calls[@]}" >&2
    exit 1
  fi
) >"$OUTPUT_FILE" 2>&1; then
  echo "Shared-base main-path validation contract failed" >&2
  cat "$OUTPUT_FILE" >&2
  exit 1
fi

grep -q "Skipping static preflight policy enforcement" "$OUTPUT_FILE" || {
  echo "Shared-base main path did not skip production attestation enforcement" >&2
  cat "$OUTPUT_FILE" >&2
  exit 1
}

echo "overlay preflight contract checks passed"
