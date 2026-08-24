#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
CHART_DIR="$ROOT_DIR/k8s/helm/firemud"
TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

RENDERED="$TMP_DIR/rendered.yaml"
if ! command -v helm >/dev/null 2>&1; then
  echo "helm is required to render the JWKS contract" >&2
  exit 1
fi

helm template contract "$CHART_DIR" \
  -f "$CHART_DIR/values-hosted-shared.example.yaml" \
  >"$RENDERED"

python3 - <<'PY' "$RENDERED"
import copy
import pathlib
import sys

import yaml

documents = [
    document
    for document in yaml.safe_load_all(
        pathlib.Path(sys.argv[1]).read_text(encoding="utf-8")
    )
    if isinstance(document, dict)
]
jwks_matches = [
    document
    for document in documents
    if document.get("kind") == "ConfigMap"
    and document.get("metadata", {}).get("name") == "jwt-jwks"
]
if len(jwks_matches) != 1:
    raise SystemExit(
        "rendered YAML must contain exactly one jwt-jwks ConfigMap, "
        f"found {len(jwks_matches)}"
    )
jwks = jwks_matches[0]
data = jwks.get("data")
jwks_json = data.get("jwks.json") if isinstance(data, dict) else None
if not isinstance(jwks_json, str) or not jwks_json.strip():
    raise SystemExit("jwt-jwks ConfigMap did not render non-empty data.jwks.json")
projected_jwks_files = {
    key for key in data if isinstance(key, str) and key
}

account = next(
    (
        document
        for document in documents
        if document.get("kind") == "Deployment"
        and document.get("metadata", {}).get("name") == "account-service"
    ),
    None,
)
if account is None:
    raise SystemExit("rendered YAML did not contain the account-service Deployment")
pod_spec = account.get("spec", {}).get("template", {}).get("spec", {})
jwks_volume = next(
    (
        volume
        for volume in pod_spec.get("volumes", [])
        if isinstance(volume, dict) and volume.get("name") == "jwt-jwks"
    ),
    None,
)
if jwks_volume is None:
    raise SystemExit(
        "account-service Deployment did not declare the jwt-jwks volume"
    )
if jwks_volume.get("configMap", {}).get("name") != "jwt-jwks":
    raise SystemExit(f"Account jwt-jwks volume is not ConfigMap-backed: {jwks_volume}")

account_container = next(
    (
        container
        for container in pod_spec.get("containers", [])
        if isinstance(container, dict) and container.get("name") == "account-service"
    ),
    None,
)
if account_container is None:
    raise SystemExit(
        "account-service Deployment did not declare its account-service container"
    )

jwks_path_prefix = "/var/run/secrets/firemud/jwks/"
jwks_mount_path = jwks_path_prefix.rstrip("/")


def validate_account_jwks_path_overrides(container):
    for env_entry in container.get("env", []):
        if not isinstance(env_entry, dict) or env_entry.get("name") != "FIREMUD_AUTH_JWKS_PATH":
            continue
        value = env_entry.get("value")
        if (
            not isinstance(value, str)
            or not value.startswith(jwks_path_prefix)
            or value.endswith("/")
        ):
            raise SystemExit(
                "Account FIREMUD_AUTH_JWKS_PATH override must be a file path under "
                f"{jwks_path_prefix}"
            )
        if pathlib.PurePosixPath(value).name not in projected_jwks_files:
            raise SystemExit(
                "Account FIREMUD_AUTH_JWKS_PATH override must name a file projected by "
                f"the jwt-jwks ConfigMap: {value}"
            )


validate_account_jwks_path_overrides(account_container)

positive_override_container = copy.deepcopy(account_container)
positive_override_container.setdefault("env", []).append(
    {
        "name": "FIREMUD_AUTH_JWKS_PATH",
        "value": f"{jwks_path_prefix}jwks.json",
    }
)
validate_account_jwks_path_overrides(positive_override_container)

for invalid_value in (
    jwks_path_prefix,
    f"{jwks_path_prefix}override.json",
    "/tmp/override.json",
    7,
):
    negative_override_container = copy.deepcopy(account_container)
    negative_override_container.setdefault("env", []).append(
        {"name": "FIREMUD_AUTH_JWKS_PATH", "value": invalid_value}
    )
    try:
        validate_account_jwks_path_overrides(negative_override_container)
    except SystemExit:
        pass
    else:
        raise SystemExit(
            "invalid Account FIREMUD_AUTH_JWKS_PATH override unexpectedly passed: "
            f"{invalid_value!r}"
        )

jwks_mounts = [
    mount
    for mount in account_container.get("volumeMounts", [])
    if isinstance(mount, dict) and mount.get("name") == "jwt-jwks"
]
if len(jwks_mounts) != 1 or jwks_mounts[0].get("mountPath") != jwks_mount_path:
    raise SystemExit(
        "Account jwt-jwks volume must be mounted exactly once at "
        f"{jwks_mount_path}"
    )

config_map_refs = [
    env_from.get("configMapRef", {})
    for env_from in account_container.get("envFrom", [])
    if isinstance(env_from, dict)
]
if not any(ref.get("name") == "firemud-config" for ref in config_map_refs):
    raise SystemExit(
        "Account container must load FIREMUD_AUTH_JWKS_PATH from firemud-config"
    )

firemud_config = next(
    (
        document
        for document in documents
        if document.get("kind") == "ConfigMap"
        and document.get("metadata", {}).get("name") == "firemud-config"
    ),
    None,
)
if firemud_config is None:
    raise SystemExit("rendered YAML did not contain the firemud-config ConfigMap")
config_data = firemud_config.get("data")
jwks_path = config_data.get("FIREMUD_AUTH_JWKS_PATH") if isinstance(config_data, dict) else None
if (
    not isinstance(jwks_path, str)
    or not jwks_path.startswith(jwks_path_prefix)
    or jwks_path.endswith("/")
):
    raise SystemExit(
        "FIREMUD_AUTH_JWKS_PATH must be a file path under "
        f"{jwks_mount_path}"
    )
if pathlib.PurePosixPath(jwks_path).name not in projected_jwks_files:
    raise SystemExit(
        "FIREMUD_AUTH_JWKS_PATH must name a file projected by the jwt-jwks ConfigMap: "
        f"{jwks_path}"
    )
PY

if helm template contract "$CHART_DIR" \
  -f "$CHART_DIR/values-hosted-shared.example.yaml" \
  --set previewStack.jwt.jwksResourceKind=Secret \
  >"$TMP_DIR/secret-override.out" 2>&1; then
  echo "Secret-backed previewStack.jwt.jwksResourceKind override unexpectedly rendered" >&2
  exit 1
fi
if ! grep -q "jwksResourceKind is unsupported" "$TMP_DIR/secret-override.out"; then
  echo "Secret-backed JWKS override failed for the wrong reason" >&2
  cat "$TMP_DIR/secret-override.out" >&2
  exit 1
fi

echo "Helm public JWKS contract passed"
