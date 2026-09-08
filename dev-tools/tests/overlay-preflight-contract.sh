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
    "spring-cloud-gateway-mtls.firemud.svc.cluster.local:8443",
    [],
)
module.validate_gateway_ws_listener = lambda documents, expected: (set(), [])
module.validate_gateway_ws_network_policy = lambda documents, secret_name: []
strategy_issue = (
    "TCP Proxy bridge Deployment strategy must be Recreate so identity withdrawal "
    "cannot retain stale pods"
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
