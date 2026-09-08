#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="$(git rev-parse --show-toplevel)"
OUTPUT_FILE="$(mktemp)"
trap 'rm -f "$OUTPUT_FILE"' EXIT

assert_balanced_preflight_group() {
  local context="$1"
  local group_starts
  local group_ends
  group_starts="$(grep -c '^::group::Run canonical preflight policy checks (ci-static)$' "$OUTPUT_FILE" || true)"
  group_ends="$(grep -c '^::endgroup::$' "$OUTPUT_FILE" || true)"
  if [[ "$group_starts" -ne 1 || "$group_ends" -ne 1 ]]; then
    echo "$context did not emit one balanced preflight log group" >&2
    cat "$OUTPUT_FILE" >&2
    exit 1
  fi
}

assert_production_change_requires_attestation() {
  local fixture_path="$1"
  if (
    # shellcheck disable=SC1091
    source "$REPO_ROOT/dev-tools/deploy/validate-kustomize-overlays.sh"
    changed_files_between_base_and_head() {
      printf '%s\n' "$fixture_path"
    }
    GITHUB_EVENT_NAME=pull_request GITHUB_BASE_REF=develop run_preflight_policy_checks
  ) >"$OUTPUT_FILE" 2>&1; then
    echo "Production promotion validation accepted $fixture_path without an attestation" >&2
    exit 1
  fi

  grep -q "must include exactly one attestation file" "$OUTPUT_FILE" || {
    echo "Missing-attestation failure did not explain the production promotion contract for $fixture_path" >&2
    cat "$OUTPUT_FILE" >&2
    exit 1
  }
  assert_balanced_preflight_group "Missing-attestation failure for $fixture_path"
}

assert_shared_change_skips_promotion_preflight() {
  local fixture_path="$1"
  (
    # shellcheck disable=SC1091
    source "$REPO_ROOT/dev-tools/deploy/validate-kustomize-overlays.sh"
    changed_files_between_base_and_head() {
      printf '%s\n' "$fixture_path"
    }
    GITHUB_EVENT_NAME=pull_request GITHUB_BASE_REF=develop run_preflight_policy_checks
  ) >"$OUTPUT_FILE" 2>&1

  grep -q "Skipping production promotion preflight" "$OUTPUT_FILE" || {
    echo "Shared/static Kubernetes change incorrectly entered production promotion preflight: $fixture_path" >&2
    cat "$OUTPUT_FILE" >&2
    exit 1
  }
  assert_balanced_preflight_group "Policy skip for $fixture_path"
}

assert_shared_change_runs_ordinary_overlay_checks() {
  local fixture_path="$1"
  (
    # shellcheck disable=SC1091
    source "$REPO_ROOT/dev-tools/deploy/validate-kustomize-overlays.sh"
    require_cmd() { :; }
    check_stage_has_no_backup_schedules_unless_enabled() {
      echo "checked-stage-backup-policy"
    }
    changed_files_between_base_and_head() {
      printf '%s\n' "$fixture_path"
    }
    check_images_exist() {
      printf 'checked-images:%s:%s\n' "$1" "$2"
    }
    GITHUB_EVENT_NAME=pull_request GITHUB_BASE_REF=develop main
  ) >"$OUTPUT_FILE" 2>&1

  for expected_call in \
    "checked-images:stage:$REPO_ROOT/k8s/overlays/stage" \
    "checked-images:prod:$REPO_ROOT/k8s/overlays/prod"; do
    grep -Fxq "$expected_call" "$OUTPUT_FILE" || {
      echo "Ordinary overlay validation omitted $expected_call for $fixture_path" >&2
      cat "$OUTPUT_FILE" >&2
      exit 1
    }
  done
  grep -Fqx 'Kustomize overlay validation passed.' "$OUTPUT_FILE" || {
    echo "Ordinary overlay validation did not complete for $fixture_path" >&2
    cat "$OUTPUT_FILE" >&2
    exit 1
  }
  assert_balanced_preflight_group "Ordinary overlay validation for $fixture_path"
}

for changed_file in \
  'k8s/overlays/prod/kustomization.yaml' \
  'design/operations/deployments/production/backup-readiness/deploy-123.json'; do
  assert_production_change_requires_attestation "$changed_file"
done

for changed_file in \
  'k8s/base/account-service.yaml' \
  'k8s/overlays/stage/kustomization.yaml'; do
  assert_shared_change_runs_ordinary_overlay_checks "$changed_file"
done

for changed_file in \
  'k8s/base/account-service.yaml' \
  'k8s/postgres/pg-dump-cronjob.yaml' \
  'k8s/velero/schedule.yaml'; do
  assert_shared_change_skips_promotion_preflight "$changed_file"
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
assert_balanced_preflight_group "Multiple-attestation failure"

if ! (
  # shellcheck disable=SC1091
  source "$REPO_ROOT/dev-tools/deploy/validate-kustomize-overlays.sh"
  changed_files_between_base_and_head() {
    printf '%s\n' 'design/operations/deployments/production/attestations/deploy-123.json'
  }
  python3() {
    if [[ "${1:-}" == "-" ]]; then
      printf 'rollback-compatible\n'
      return 0
    fi

    if [[ "$#" -ne 2 \
      || "$1" != "$REPO_ROOT/dev-tools/deploy/preflight.py" \
      || "$2" != "production" \
      || "${FIREMUD_PREFLIGHT_CONTEXT:-}" != "ci-static" \
      || "${FIREMUD_DEPLOYMENT_REF:-}" != "deploy-123" \
      || "${FIREMUD_PREFLIGHT_OUTPUT:-}" != "/tmp/firemud-preflight-production.json" \
      || "${FIREMUD_PROMOTION_ATTESTATION:-}" != "design/operations/deployments/production/attestations/deploy-123.json" \
      || -n "${FIREMUD_BACKUP_READINESS_EVIDENCE:-}" ]]; then
      echo "Production preflight received incorrect arguments or environment" >&2
      return 1
    fi
    printf 'validated production attestation\n'
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
assert_balanced_preflight_group "Attestation-only production validation"

assert_roll_forward_backup_count_rejected() {
  local context="$1"
  shift
  local -ar fixture_backup_files=("$@")
  if (
    # shellcheck disable=SC1091
    source "$REPO_ROOT/dev-tools/deploy/validate-kustomize-overlays.sh"
    changed_files_between_base_and_head() {
      printf '%s\n' 'design/operations/deployments/production/attestations/deploy-123.json'
      if [[ "${#fixture_backup_files[@]}" -gt 0 ]]; then
        printf '%s\n' "${fixture_backup_files[@]}"
      fi
    }
    python3() {
      if [[ "${1:-}" == "-" ]]; then
        printf 'roll-forward-only\n'
        return 0
      fi
      echo "Production preflight must not run with an invalid backup evidence count" >&2
      return 0
    }
    GITHUB_EVENT_NAME=pull_request GITHUB_BASE_REF=develop run_preflight_policy_checks
  ) >"$OUTPUT_FILE" 2>&1; then
    echo "$context was accepted" >&2
    cat "$OUTPUT_FILE" >&2
    exit 1
  fi

  grep -q "must include exactly one backup-readiness file" "$OUTPUT_FILE" || {
    echo "$context did not explain the backup-readiness contract" >&2
    cat "$OUTPUT_FILE" >&2
    exit 1
  }
  assert_balanced_preflight_group "$context"
}

assert_roll_forward_backup_count_rejected "Roll-forward-only promotion without backup evidence"
assert_roll_forward_backup_count_rejected \
  "Roll-forward-only promotion with multiple backup evidence files" \
  'design/operations/deployments/production/backup-readiness/one.json' \
  'design/operations/deployments/production/backup-readiness/two.json'

if ! (
  # shellcheck disable=SC1091
  source "$REPO_ROOT/dev-tools/deploy/validate-kustomize-overlays.sh"
  changed_files_between_base_and_head() {
    printf '%s\n' \
      'design/operations/deployments/production/attestations/deploy-123.json' \
      'design/operations/deployments/production/backup-readiness/deploy-123.json'
  }
  python3() {
    if [[ "${1:-}" == "-" ]]; then
      printf 'roll-forward-only\n'
      return 0
    fi

    if [[ "$#" -ne 2 \
      || "$1" != "$REPO_ROOT/dev-tools/deploy/preflight.py" \
      || "$2" != "production" \
      || "${FIREMUD_PREFLIGHT_CONTEXT:-}" != "ci-static" \
      || "${FIREMUD_DEPLOYMENT_REF:-}" != "deploy-123" \
      || "${FIREMUD_PREFLIGHT_OUTPUT:-}" != "/tmp/firemud-preflight-production.json" \
      || "${FIREMUD_PROMOTION_ATTESTATION:-}" != "design/operations/deployments/production/attestations/deploy-123.json" \
      || "${FIREMUD_BACKUP_READINESS_EVIDENCE:-}" != "design/operations/deployments/production/backup-readiness/deploy-123.json" ]]; then
      echo "Roll-forward-only preflight received incorrect arguments or environment" >&2
      return 1
    fi
    printf 'validated roll-forward-only production attestation\n'
  }
  GITHUB_EVENT_NAME=pull_request GITHUB_BASE_REF=develop run_preflight_policy_checks
) >"$OUTPUT_FILE" 2>&1; then
  echo "Roll-forward-only production validation rejected exactly one backup evidence file" >&2
  cat "$OUTPUT_FILE" >&2
  exit 1
fi

grep -q "validated roll-forward-only production attestation" "$OUTPUT_FILE" || {
  echo "Roll-forward-only production validation did not invoke exact production preflight" >&2
  cat "$OUTPUT_FILE" >&2
  exit 1
}
assert_balanced_preflight_group "Roll-forward-only production validation"

if (
  # shellcheck disable=SC1091
  source "$REPO_ROOT/dev-tools/deploy/validate-kustomize-overlays.sh"
  changed_files_between_base_and_head() {
    printf '%s\n' \
      'k8s/overlays/prod/kustomization.yaml' \
      'design/operations/deployments/production/attestations/invalid.json'
  }
  python3() {
    if [[ "${1:-}" == "-" ]]; then
      echo "invalid production attestation JSON" >&2
      return 1
    fi
    echo "Production preflight must not run after invalid attestation parsing" >&2
    return 1
  }
  GITHUB_EVENT_NAME=pull_request GITHUB_BASE_REF=develop run_preflight_policy_checks
) >"$OUTPUT_FILE" 2>&1; then
  echo "Production promotion validation accepted an invalid attestation" >&2
  exit 1
fi

grep -q "invalid production attestation JSON" "$OUTPUT_FILE" || {
  echo "Invalid-attestation failure did not reach attestation parsing" >&2
  cat "$OUTPUT_FILE" >&2
  exit 1
}
assert_balanced_preflight_group "Malformed-attestation failure"

if (
  # shellcheck disable=SC1091
  source "$REPO_ROOT/dev-tools/deploy/validate-kustomize-overlays.sh"
  changed_files_between_base_and_head() {
    printf '%s\n' \
      'k8s/overlays/prod/kustomization.yaml' \
      'design/operations/deployments/production/attestations/invalid-schema.json'
  }
  python3() {
    if [[ "${1:-}" == "-" ]]; then
      printf 'rollback-compatible\n'
      return 0
    fi
    echo "invalid production attestation schema" >&2
    return 1
  }
  GITHUB_EVENT_NAME=pull_request GITHUB_BASE_REF=develop run_preflight_policy_checks
) >"$OUTPUT_FILE" 2>&1; then
  echo "Production promotion validation masked an invalid attestation schema" >&2
  exit 1
fi

grep -q "invalid production attestation schema" "$OUTPUT_FILE" || {
  echo "Schema-invalid attestation did not reach production preflight" >&2
  cat "$OUTPUT_FILE" >&2
  exit 1
}
assert_balanced_preflight_group "Schema-invalid preflight failure"

if (
  # shellcheck disable=SC1091
  source "$REPO_ROOT/dev-tools/deploy/validate-kustomize-overlays.sh"
  changed_files_between_base_and_head() {
    printf '%s\n' 'design/operations/deployments/production/attestations/preflight-failure.json'
  }
  python3() {
    if [[ "${1:-}" == "-" ]]; then
      printf 'rollback-compatible\n'
      return 0
    fi
    echo "production preflight failed" >&2
    return 9
  }
  GITHUB_EVENT_NAME=pull_request GITHUB_BASE_REF=develop run_preflight_policy_checks
) >"$OUTPUT_FILE" 2>&1; then
  echo "Production promotion validation masked a production preflight failure" >&2
  exit 1
fi

grep -q "production preflight failed" "$OUTPUT_FILE" || {
  echo "Production preflight failure diagnostics were lost" >&2
  cat "$OUTPUT_FILE" >&2
  exit 1
}
assert_balanced_preflight_group "Production preflight failure"

(
  # shellcheck disable=SC1091
  source "$REPO_ROOT/dev-tools/deploy/validate-kustomize-overlays.sh"
  changed_files_between_base_and_head() {
    printf '%s\n' 'k8s/overlays/stage/kustomization.yaml'
  }
  GITHUB_EVENT_NAME=pull_request GITHUB_BASE_REF=develop run_preflight_policy_checks
) >"$OUTPUT_FILE" 2>&1

grep -q "Skipping production promotion preflight" "$OUTPUT_FILE" || {
  echo "Non-production overlay validation did not take the policy-skip path" >&2
  cat "$OUTPUT_FILE" >&2
  exit 1
}

for environment_and_overlay in 'staging stage' 'production prod'; do
  read -r environment overlay <<<"$environment_and_overlay"
  rendered_overlay="$REPO_ROOT/k8s/overlays/$overlay"
  kubectl kustomize "$rendered_overlay" >"$OUTPUT_FILE"
  python3 - "$REPO_ROOT" "$environment" "$OUTPUT_FILE" <<'PY'
import copy
import importlib.util
import pathlib
import sys

import yaml

root = pathlib.Path(sys.argv[1])
environment = sys.argv[2]
rendered_path = pathlib.Path(sys.argv[3])
spec = importlib.util.spec_from_file_location(
    "overlay_preflight_contract", root / "dev-tools/deploy/preflight.py"
)
module = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = module
spec.loader.exec_module(module)

documents = module.parse_documents(rendered_path.read_text(encoding="utf-8"))
proxy = next(
    document
    for document in documents
    if document.get("kind") == "Deployment"
    and document.get("metadata", {}).get("name") == "tcp-proxy-service"
)
if proxy.get("spec", {}).get("strategy") != {"type": "Recreate"}:
    raise SystemExit(
        f"{environment} TCP Proxy render must use Recreate for exclusive bridge identity"
    )

expected_path = (
    root / f"design/operations/environments/{environment}/expected-bindings.yaml"
)
expected = yaml.safe_load(expected_path.read_text(encoding="utf-8"))
# Isolate the rollout invariant from listener and network-policy prerequisites: this
# contract's input is the real rendered overlay, while those prerequisites have
# their own focused preflight coverage.
module.canonical_gateway_ws_endpoint = lambda documents, expected: (
    "spring-cloud-gateway-mtls.firemud.svc.cluster.local:443",
    [],
)
module.validate_gateway_ws_listener = lambda documents, expected: (set(), [])
module.validate_gateway_ws_network_policy = lambda documents, secret_name: []
strategy_issue = (
    "TCP Proxy bridge Deployment strategy must be Recreate for planned identity replacement"
)
_, current_issues = module.validate_gateway_ws_values(documents, expected)
if strategy_issue in current_issues:
    raise SystemExit(f"{environment} canonical render failed bridge rollout validation")

mutation = copy.deepcopy(documents)
mutated_proxy = next(
    document
    for document in mutation
    if document.get("kind") == "Deployment"
    and document.get("metadata", {}).get("name") == "tcp-proxy-service"
)
mutated_proxy["spec"].pop("strategy")
_, mutation_issues = module.validate_gateway_ws_values(mutation, expected)
if strategy_issue not in mutation_issues:
    raise SystemExit(
        f"{environment} preflight accepted a TCP Proxy render without Recreate"
    )
PY
done

echo "overlay preflight contract checks passed"
