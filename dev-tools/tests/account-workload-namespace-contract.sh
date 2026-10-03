#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
CHART_DIR="$ROOT_DIR/k8s/helm/account-service"
TEMP_DIR="$(mktemp -d)"
trap 'rm -rf -- "$TEMP_DIR"' EXIT

if ! command -v helm >/dev/null 2>&1; then
  echo "helm is required to render the Account workload namespace contract" >&2
  exit 1
fi

DEFAULT_RENDER="$TEMP_DIR/default.yaml"
CUSTOM_RENDER="$TEMP_DIR/custom.yaml"
COLLISION_RENDER="$TEMP_DIR/collision.yaml"

helm template account-default "$CHART_DIR" --namespace default >"$DEFAULT_RENDER"
helm template account-custom "$CHART_DIR" \
  --namespace pr-2848 \
  --set-string extraEnv.ORDINARY_SETTING=preserved \
  >"$CUSTOM_RENDER"

python3 - "$DEFAULT_RENDER" "$CUSTOM_RENDER" <<'PY'
import pathlib
import sys

import yaml


def account_environment(path):
    documents = [
        document
        for document in yaml.safe_load_all(pathlib.Path(path).read_text(encoding="utf-8"))
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
        raise SystemExit(f"{path}: rendered Account Deployment is missing")
    containers = deployment.get("spec", {}).get("template", {}).get("spec", {}).get("containers", [])
    account = next(
        (container for container in containers if container.get("name") == "account-service"),
        None,
    )
    if account is None:
        raise SystemExit(f"{path}: rendered Account container is missing")
    return account.get("env", [])


for path in sys.argv[1:]:
    env = account_environment(path)
    matches = [entry for entry in env if entry.get("name") == "FIREMUD_GRPC_WORKLOAD_NAMESPACE"]
    if len(matches) != 1:
        raise SystemExit(
            f"{path}: expected exactly one FIREMUD_GRPC_WORKLOAD_NAMESPACE entry, found {len(matches)}"
        )
    expected = {
        "name": "FIREMUD_GRPC_WORKLOAD_NAMESPACE",
        "valueFrom": {"fieldRef": {"fieldPath": "metadata.namespace"}},
    }
    if matches[0] != expected:
        raise SystemExit(f"{path}: workload namespace is not sourced from metadata.namespace: {matches[0]}")

custom_env = account_environment(sys.argv[2])
ordinary = [entry for entry in custom_env if entry.get("name") == "ORDINARY_SETTING"]
if ordinary != [{"name": "ORDINARY_SETTING", "value": "preserved"}]:
    raise SystemExit(f"custom render did not preserve extraEnv: {ordinary}")
PY

if helm template account-collision "$CHART_DIR" \
  --namespace pr-2848 \
  --set-string extraEnv.FIREMUD_GRPC_WORKLOAD_NAMESPACE=forged \
  >"$COLLISION_RENDER" 2>"$TEMP_DIR/collision.err"; then
  echo "Account chart accepted a controller-owned workload namespace override" >&2
  exit 1
fi
if ! grep -Fq 'extraEnv must not set controller-owned FIREMUD_GRPC_WORKLOAD_NAMESPACE' "$TEMP_DIR/collision.err"; then
  echo "Account chart collision failed for an unexpected reason" >&2
  cat "$TEMP_DIR/collision.err" >&2
  exit 1
fi
