#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
CHART_DIR="$ROOT_DIR/k8s/helm/account-service"
TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

if ! command -v helm >/dev/null 2>&1; then
  echo "helm is required to render the Account workload namespace contract" >&2
  exit 1
fi

render_and_check() {
  local namespace="$1"
  local rendered="$2"
  local check_extra_env="$3"
  shift 3

  helm template "account-contract-$namespace" "$CHART_DIR" \
    --namespace "$namespace" \
    "$@" \
    >"$rendered"

  python3 - "$rendered" "$namespace" "$check_extra_env" <<'PY'
import pathlib
import sys

import yaml

rendered_path = pathlib.Path(sys.argv[1])
namespace = sys.argv[2]
check_extra_env = sys.argv[3] == "true"
documents = [
    document
    for document in yaml.safe_load_all(rendered_path.read_text(encoding="utf-8"))
    if isinstance(document, dict)
]
deployment = next(
    (
        document
        for document in documents
        if document.get("kind") == "Deployment"
        and document.get("metadata", {}).get("name") == "account-service"
    ),
    None,
)
if deployment is None:
    raise SystemExit(f"render in namespace {namespace!r} omitted Account Deployment")

containers = deployment.get("spec", {}).get("template", {}).get("spec", {}).get("containers", [])
account_container = next(
    (container for container in containers if container.get("name") == "account-service"),
    None,
)
if account_container is None:
    raise SystemExit(f"render in namespace {namespace!r} omitted Account container")

env = account_container.get("env", [])
namespace_entries = [
    entry
    for entry in env
    if isinstance(entry, dict)
    and entry.get("name") == "FIREMUD_GRPC_WORKLOAD_NAMESPACE"
]
expected = {
    "name": "FIREMUD_GRPC_WORKLOAD_NAMESPACE",
    "valueFrom": {"fieldRef": {"fieldPath": "metadata.namespace"}},
}
if namespace_entries != [expected]:
    raise SystemExit(
        f"render in namespace {namespace!r} must derive exactly one workload namespace "
        f"from metadata.namespace, found {namespace_entries!r}"
    )

if check_extra_env:
    sentinel = [entry for entry in env if entry.get("name") == "ACCOUNT_CONTRACT_SENTINEL"]
    if sentinel != [{"name": "ACCOUNT_CONTRACT_SENTINEL", "value": "preserved"}]:
        raise SystemExit(f"ordinary extraEnv value was not preserved: {sentinel!r}")
PY
}

render_and_check default "$TMP_DIR/default.yaml" false
render_and_check account-contract-custom "$TMP_DIR/custom.yaml" true \
  --set-string extraEnv.ACCOUNT_CONTRACT_SENTINEL=preserved

if helm template account-contract-collision "$CHART_DIR" \
  --namespace account-contract-collision \
  --set-string extraEnv.FIREMUD_GRPC_WORKLOAD_NAMESPACE=override \
  >"$TMP_DIR/collision.out" 2>"$TMP_DIR/collision.err"; then
  echo "Account chart accepted extraEnv collision with FIREMUD_GRPC_WORKLOAD_NAMESPACE" >&2
  exit 1
fi
if ! grep -Fq \
  "extraEnv must not set FIREMUD_GRPC_WORKLOAD_NAMESPACE; Account derives it from the pod namespace" \
  "$TMP_DIR/collision.err"; then
  echo "Account chart collision failure omitted its explicit reason" >&2
  cat "$TMP_DIR/collision.err" >&2
  exit 1
fi

echo "Account workload namespace Helm contract passed"
