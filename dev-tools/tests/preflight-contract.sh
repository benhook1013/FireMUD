#!/usr/bin/env bash
# Contract checks for dev-tools/deploy/preflight.py report shape and policy IDs.
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SCRIPT="$ROOT_DIR/dev-tools/deploy/preflight.py"
WRITER="$ROOT_DIR/dev-tools/deploy/write-traffic-open-evidence.py"
TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

RENDERED_MANIFEST="$TMP_DIR/hobby-rendered.yaml"
MIGRATED_RENDERED_MANIFEST="$TMP_DIR/hobby-rendered-configmap.yaml"
REPORT_PATH="$TMP_DIR/preflight-report.json"
MIGRATED_REPORT_PATH="$TMP_DIR/preflight-migrated-report.json"
OPERATOR_REPORT_PATH="$TMP_DIR/operator-preflight-report.json"
TRAFFIC_EVIDENCE="$TMP_DIR/traffic-open.json"
PRODUCTION_REPORT="$TMP_DIR/preflight-production.json"
LEGACY_PRODUCTION_TRAFFIC_EVIDENCE="$TMP_DIR/production-traffic-open.json"
PRODUCTION_WAIVER="$TMP_DIR/contract-production.waiver.json"
LEGACY_HOBBY_PREFLIGHT_OUTPUT="$TMP_DIR/firemud-preflight-contract.out"
MIGRATED_HOBBY_PREFLIGHT_OUTPUT="$TMP_DIR/firemud-preflight-contract-migrated.out"
TRAFFIC_HOBBY_PREFLIGHT_OUTPUT="$TMP_DIR/firemud-preflight-contract-traffic.out"
LEGACY_PRODUCTION_PREFLIGHT_OUTPUT="$TMP_DIR/firemud-preflight-contract-production-traffic.out"
GATED_PRODUCTION_PREFLIGHT_OUTPUT="$TMP_DIR/firemud-preflight-contract-production-traffic-gated.out"
WAIVER_PRODUCTION_PREFLIGHT_OUTPUT="$TMP_DIR/firemud-preflight-contract-production-traffic-waiver.out"
HOBBY_TRAFFIC_WRITER_OUTPUT="$TMP_DIR/firemud-preflight-write-traffic-hobby.out"
PRODUCTION_TRAFFIC_WRITER_OUTPUT="$TMP_DIR/firemud-preflight-write-traffic-production.out"

python3 - <<'PY' "$ROOT_DIR"
import pathlib
import sys
import yaml

root = pathlib.Path(sys.argv[1])
required_paths = [
    "internalBindings.postgres.endpoint",
    "internalBindings.postgres.credentialsRef",
    "internalBindings.redis.coordination.endpoint",
    "internalBindings.redis.cache.endpoint",
    "internalBindings.jwt.custodyMode",
    "internalBindings.jwt.signingKeysRef",
    "internalBindings.jwt.jwksRef",
    "internalBindings.certificates.issuerRef",
    "internalBindings.certificates.workloadMtlsRef",
    "internalBindings.certificates.gatewayInternalWsListenerRef",
    "internalBindings.certificates.tcpProxyBridgeClientRef",
    "internalBindings.registry.imagePullSecretRef",
    "assetStorage.enabled",
    "assetStorage.bucket",
    "assetStorage.bindingRef",
    "outboundComms.enabled",
    "outboundComms.smtpHost",
    "operatorCredentials.bindingRef",
    "serviceDiscovery.mode",
]

def get(data, dotted):
    cur = data
    for part in dotted.split("."):
        if not isinstance(cur, dict) or part not in cur:
            return None
        cur = cur[part]
    return cur

for env in ("production", "staging", "hobby-self-hosted"):
    ref = pathlib.Path(f"design/operations/environments/{env}/expected-bindings.yaml")
    data = yaml.safe_load((root / ref).read_text(encoding="utf-8"))
    if data.get("environment") != env:
        raise SystemExit(f"{ref}: environment mismatch")
    missing = [path for path in required_paths if not get(data, path)]
    if missing:
        raise SystemExit(f"{ref}: missing required binding paths: {missing}")
    backup_storage = data.get("backupStorage")
    if not isinstance(backup_storage, dict):
        raise SystemExit(f"{ref}: backupStorage must be a mapping")
    backup_enabled = backup_storage.get("enabled")
    if not isinstance(backup_enabled, bool):
        raise SystemExit(f"{ref}: backupStorage.enabled must be a boolean")
    if backup_enabled:
        backup_missing = []
        if not backup_storage.get("bucket"):
            backup_missing.append("backupStorage.bucket")
        if not backup_storage.get("bindingRef") and not backup_storage.get("fingerprint"):
            backup_missing.append("backupStorage.bindingRef or backupStorage.fingerprint")
        if backup_missing:
            raise SystemExit(f"{ref}: missing enabled backup storage paths: {backup_missing}")
    for section in ("assetStorage", "outboundComms"):
        optional = data.get(section)
        if not isinstance(optional, dict):
            raise SystemExit(f"{ref}: {section} must be an object")
        if not isinstance(optional.get("enabled"), bool):
            raise SystemExit(f"{ref}: {section}.enabled must be a boolean")
        if not optional["enabled"]:
            continue
        if section == "assetStorage":
            if not optional.get("bucket") or not optional.get("endpoint"):
                raise SystemExit(f"{ref}: enabled assetStorage needs bucket and endpoint")
            if not optional.get("bindingRef") and not optional.get("fingerprint"):
                raise SystemExit(f"{ref}: enabled assetStorage needs bindingRef or fingerprint")
        elif not optional.get("smtpHost") and not optional.get("webhookTargets"):
            raise SystemExit(f"{ref}: enabled outboundComms needs smtpHost or webhookTargets")
PY

python3 - <<'PY' "$ROOT_DIR" "$OPERATOR_REPORT_PATH"
import importlib.util
import pathlib
import sys

root = pathlib.Path(sys.argv[1])
output = pathlib.Path(sys.argv[2])
spec = importlib.util.spec_from_file_location("preflight_operator_report_contract", root / "dev-tools/deploy/preflight.py")
module = importlib.util.module_from_spec(spec)
assert spec.loader is not None
sys.modules[spec.name] = module
spec.loader.exec_module(module)

expected_rfc8785_digest = "sha256:fd8b688bfa8b71822975ab3519e20b09e43b67d382a9f32831bfa384df21a82d"
actual_rfc8785_digest = module.canonical_evidence_digest({"\ufffd": 2, "\U0001f600": 1})
if actual_rfc8785_digest != expected_rfc8785_digest:
    raise SystemExit(f"RFC 8785 UTF-16 key ordering drifted: {actual_rfc8785_digest}")
for invalid_record in ({"value": 9_007_199_254_740_992}, {"value": 1.5}):
    try:
        module.canonical_evidence_digest(invalid_record)
    except TypeError:
        pass
    else:
        raise SystemExit(f"non-interoperable evidence number was accepted: {invalid_record}")

requirements = module.expected_preflight_policy_requirements("hobby-self-hosted", None)
checks = [
    module.CheckResult(
        policy_id,
        required,
        "pass" if required or policy_id == "PREFLIGHT-DIGEST-002" else "not_applicable",
        "contract evidence",
    )
    for policy_id, required in requirements.items()
]
report_timestamp = module.utc_now()
module.write_report(
    output,
    "hobby-self-hosted",
    "contract-hobby",
    report_timestamp,
    report_timestamp,
    checks,
    "operator",
    "design/operations/environments/hobby-self-hosted/expected-bindings.yaml",
    "66666666-6666-4666-8666-666666666666",
    "",
)
try:
    module.write_report(
        output,
        "hobby-self-hosted",
        "contract-hobby",
        report_timestamp,
        report_timestamp,
        checks,
        "operator",
        "design/operations/environments/hobby-self-hosted/expected-bindings.yaml",
        "66666666-6666-4666-8666-666666666666",
        "",
    )
except SystemExit as exc:
    if exc.code != 1:
        raise SystemExit(f"existing report was rejected for the wrong reason: {exc}") from exc
else:
    raise SystemExit("write_report overwrote an existing output")
for invalid_ref in ("../escape", "UpperCase", "contains/slash", "contains_underscore"):
    if module.DEPLOYMENT_REF_RE.fullmatch(invalid_ref):
        raise SystemExit(f"invalid deployment ref was accepted: {invalid_ref}")
PY

cat >"$RENDERED_MANIFEST" <<'YAML'
apiVersion: v1
kind: ConfigMap
metadata:
  name: firemud-config
data:
  FIREMUD_AUTH_JWT_SECRET_PATH: /var/run/secrets/firemud/jwt/current.key
  FIREMUD_AUTH_JWKS_PATH: /var/run/secrets/firemud/jwks/jwks.json
  FIREMUD_REDIS_COORD_HOST: redis-coord
  FIREMUD_REDIS_COORD_PORT: "6379"
  FIREMUD_REDIS_CACHE_HOST: redis-cache
  FIREMUD_REDIS_CACHE_PORT: "6379"
---
apiVersion: v1
kind: Service
metadata:
  namespace: firemud
  name: spring-cloud-gateway-mtls
spec:
  type: ClusterIP
  ports:
    - port: 443
---
apiVersion: v1
kind: Secret
metadata:
  name: postgres-credentials
type: Opaque
stringData:
  FIREMUD_POSTGRES_USER: firemud
  FIREMUD_POSTGRES_PASSWORD: firemud
---
apiVersion: v1
kind: Secret
metadata:
  name: jwt-signing-keys
type: Opaque
stringData:
  current.key: changeit-changeit-changeit-changeit
---
apiVersion: v1
kind: Secret
metadata:
  name: jwt-jwks
type: Opaque
stringData:
  jwks.json: '{"keys":[]}'
---
apiVersion: v1
kind: Secret
metadata:
  name: hobby-tcp-proxy-bridge
type: Opaque
stringData:
  client.crt: bridge-client
  client.key: bridge-key
  ca.crt: bridge-ca
---
apiVersion: v1
kind: Secret
metadata:
  name: grpc-tls
type: Opaque
stringData:
  client.crt: grpc-client
  client.key: grpc-key
  ca.crt: grpc-ca
---
apiVersion: v1
kind: ServiceAccount
metadata:
  name: firemud-app
imagePullSecrets:
  - name: ghcr-pull-hobby
---
apiVersion: apps/v1
kind: Deployment
metadata:
  name: tcp-proxy-service
spec:
  template:
    spec:
      serviceAccountName: firemud-app
      containers:
        - name: tcp-proxy-service
          image: ghcr.io/benhook1013/tcp-proxy-service:latest
          env:
            - name: FIREMUD_GRPC_CERT_CHAIN_PATH
              value: /grpc-tls/client.crt
            - name: FIREMUD_GRPC_PRIVATE_KEY_PATH
              value: /grpc-tls/client.key
            - name: FIREMUD_GRPC_CA_CERT_PATH
              value: /grpc-tls/ca.crt
            - name: GATEWAY_WS_URL
              value: wss://spring-cloud-gateway-mtls.firemud.svc.cluster.local/ws/game
            - name: FIREMUD_GATEWAY_WS_CLIENT_CERT_CHAIN_PATH
              value: /tls/client.crt
            - name: FIREMUD_GATEWAY_WS_CLIENT_PRIVATE_KEY_PATH
              value: /tls/client.key
            - name: FIREMUD_GATEWAY_WS_CA_CERT_PATH
              value: /tls/ca.crt
          envFrom:
            - secretRef:
                name: postgres-credentials
            - configMapRef:
                name: firemud-config
          volumeMounts:
            - name: hobby-tcp-proxy-bridge
              mountPath: /tls
              readOnly: true
            - name: grpc-tls
              mountPath: /grpc-tls
              readOnly: true
            - name: jwt-signing-keys
              mountPath: /var/run/secrets/firemud/jwt
              readOnly: true
      volumes:
        - name: hobby-tcp-proxy-bridge
          secret:
            secretName: hobby-tcp-proxy-bridge
        - name: grpc-tls
          secret:
            secretName: grpc-tls
        - name: jwt-signing-keys
          secret:
            secretName: jwt-signing-keys
---
apiVersion: apps/v1
kind: Deployment
metadata:
  name: account-service
spec:
  template:
    spec:
      serviceAccountName: firemud-app
      containers:
        - name: account-service
          image: ghcr.io/benhook1013/account-service:latest
          envFrom:
            - secretRef:
                name: postgres-credentials
            - configMapRef:
                name: firemud-config
          volumeMounts:
            - name: jwt-signing-keys
              mountPath: /var/run/secrets/firemud/jwt
              readOnly: true
            - name: jwt-jwks
              mountPath: /var/run/secrets/firemud/jwks
              readOnly: true
      volumes:
        - name: jwt-signing-keys
          secret:
            secretName: jwt-signing-keys
        - name: jwt-jwks
          secret:
            secretName: jwt-jwks
YAML

python3 - <<'PY' "$RENDERED_MANIFEST"
import pathlib
import sys

import yaml

path = pathlib.Path(sys.argv[1])
documents = [
    document
    for document in yaml.safe_load_all(path.read_text(encoding="utf-8"))
    if isinstance(document, dict)
]
for document in documents:
    document.setdefault("metadata", {})["namespace"] = "firemud"
path.write_text(yaml.safe_dump_all(documents, sort_keys=False), encoding="utf-8")
PY

python3 - <<'PY' "$RENDERED_MANIFEST" "$MIGRATED_RENDERED_MANIFEST"
import pathlib
import sys

import yaml

source = pathlib.Path(sys.argv[1])
destination = pathlib.Path(sys.argv[2])
documents = [
    document
    for document in yaml.safe_load_all(source.read_text(encoding="utf-8"))
    if isinstance(document, dict)
]
found_jwks_resource = False
found_account_wiring = False
for document in documents:
    metadata = document.get("metadata") or {}
    if document.get("kind") == "Secret" and metadata.get("name") == "jwt-jwks":
        document["kind"] = "ConfigMap"
        document.pop("type", None)
        document.pop("stringData", None)
        document["data"] = {
            "jwks.json": '{"keys":[{"kty":"RSA","kid":"contract-jwks"}]}'
        }
        found_jwks_resource = True
    if document.get("kind") != "Deployment" or metadata.get("name") != "account-service":
        continue
    pod_spec = document["spec"]["template"]["spec"]
    jwks_volume = next(
        (
            volume
            for volume in pod_spec.get("volumes", [])
            if volume.get("name") == "jwt-jwks"
        ),
        None,
    )
    if jwks_volume is None:
        raise SystemExit("migrated ConfigMap fixture is missing the Account jwt-jwks volume")
    jwks_volume.pop("secret", None)
    jwks_volume["configMap"] = {"name": "jwt-jwks"}
    account_container = next(
        container
        for container in pod_spec.get("containers", [])
        if container.get("name") == "account-service"
    )
    jwks_mount = next(
        (
            mount
            for mount in account_container.get("volumeMounts", [])
            if mount.get("name") == "jwt-jwks"
        ),
        None,
    )
    if jwks_mount is None:
        raise SystemExit("migrated ConfigMap fixture is missing the Account jwt-jwks mount")
    if jwks_mount.get("mountPath") != "/var/run/secrets/firemud/jwks":
        raise SystemExit("migrated ConfigMap fixture has an unexpected Account JWKS mount path")
    found_account_wiring = True

if not found_jwks_resource or not found_account_wiring:
    raise SystemExit("migrated ConfigMap fixture did not contain the expected JWKS resource and Account wiring")

destination.write_text(yaml.safe_dump_all(documents, sort_keys=False), encoding="utf-8")
PY

python3 - <<'PY' "$RENDERED_MANIFEST" "$SCRIPT"
import copy
import importlib.util
import pathlib
import sys

import yaml

rendered_path = pathlib.Path(sys.argv[1])
preflight_path = pathlib.Path(sys.argv[2])
spec = importlib.util.spec_from_file_location("preflight_jwt_jwks_contract", preflight_path)
module = importlib.util.module_from_spec(spec)
assert spec.loader is not None
sys.modules[spec.name] = module
spec.loader.exec_module(module)

legacy_documents = [
    document
    for document in yaml.safe_load_all(rendered_path.read_text(encoding="utf-8"))
    if isinstance(document, dict)
]


def account_deployment(documents):
    for document in documents:
        if document.get("kind") != "Deployment" or document.get("metadata", {}).get("name") != "account-service":
            continue
        return document
    raise SystemExit("JWT/JWKS fixture is missing account-service deployment")


def account_container(document):
    containers = document["spec"]["template"]["spec"].get("containers") or []
    for container in containers:
        if container.get("name") == "account-service":
            return container
    raise SystemExit("JWT/JWKS fixture is missing account-service container")


def public_config_map_documents():
    documents = copy.deepcopy(legacy_documents)
    jwks = next(
        document
        for document in documents
        if document.get("metadata", {}).get("name") == "jwt-jwks"
    )
    jwks["kind"] = "ConfigMap"
    jwks.pop("type", None)
    string_data = jwks.pop("stringData", None)
    secret_data = jwks.pop("data", None)
    config_map_data = string_data if string_data else secret_data
    if not isinstance(config_map_data, dict) or not config_map_data:
        raise SystemExit("JWT/JWKS Secret fixture is missing usable source data")
    jwks["data"] = config_map_data
    account = account_deployment(documents)
    pod_spec = account["spec"]["template"]["spec"]
    jwks_volume = next(volume for volume in pod_spec["volumes"] if volume.get("name") == "jwt-jwks")
    jwks_volume.pop("secret", None)
    jwks_volume["configMap"] = {"name": "jwt-jwks"}
    return documents


def jwks_result(documents):
    results = {
        result.policy_id: result
        for result in module.jwt_jwks_checks(documents, "firemud")
    }
    return results["PREFLIGHT-JWKS-001"]


public_documents = public_config_map_documents()
public_result = jwks_result(public_documents)
if public_result.status != "fail" or "configured as a ConfigMap" not in public_result.message:
    raise SystemExit(f"public ConfigMap unexpectedly satisfied the legacy Secret contract: {public_result.message}")

missing_mount_documents = copy.deepcopy(legacy_documents)
missing_account = account_deployment(missing_mount_documents)
missing_account_container = account_container(missing_account)
missing_account_container["volumeMounts"] = [
    mount
    for mount in missing_account_container["volumeMounts"]
    if mount.get("name") != "jwt-jwks"
]
missing_mount_result = jwks_result(missing_mount_documents)
if missing_mount_result.status != "fail" or "does not mount" not in missing_mount_result.message:
    raise SystemExit(f"missing Account jwt-jwks mount did not fail closed: {missing_mount_result.message}")

wrong_mount_documents = copy.deepcopy(legacy_documents)
wrong_account = account_deployment(wrong_mount_documents)
wrong_account_container = account_container(wrong_account)
wrong_mount = next(
    mount
    for mount in wrong_account_container["volumeMounts"]
    if mount.get("name") == "jwt-jwks"
)
wrong_mount["mountPath"] = "/var/run/secrets/firemud/wrong-jwks"
wrong_mount_result = jwks_result(wrong_mount_documents)
if wrong_mount_result.status != "fail" or "does not mount" not in wrong_mount_result.message:
    raise SystemExit(f"wrong Account jwt-jwks mount did not fail closed: {wrong_mount_result.message}")

secret_documents = copy.deepcopy(legacy_documents)
secret_result = jwks_result(secret_documents)
if secret_result.status != "pass":
    raise SystemExit(f"legacy Secret jwt-jwks did not satisfy the current contract: {secret_result.message}")

expected_bindings_path = pathlib.Path(
    preflight_path.parents[2],
    "design/operations/environments/hobby-self-hosted/expected-bindings.yaml",
)
expected_bindings = yaml.safe_load(expected_bindings_path.read_text(encoding="utf-8"))
mutated_expected_bindings = copy.deepcopy(expected_bindings)
mutated_expected_bindings["internalBindings"]["jwt"]["jwksRef"] = (
    "secret://firemud/renamed-jwt-jwks"
)
mutated_documents = copy.deepcopy(legacy_documents)


def rename_jwks_references(node):
    if isinstance(node, dict):
        for key, value in node.items():
            if isinstance(value, str) and value == "jwt-jwks":
                node[key] = "renamed-jwt-jwks"
            else:
                rename_jwks_references(value)
    elif isinstance(node, list):
        for value in node:
            rename_jwks_references(value)


rename_jwks_references(mutated_documents)
mutated_binding_results = module.expected_binding_checks(
    expected_bindings_path,
    "design/operations/environments/hobby-self-hosted/expected-bindings.yaml",
    "hobby-self-hosted",
    mutated_documents,
    context="ci-static",
    expected_bindings=mutated_expected_bindings,
)
mutated_binding_result = next(
    result
    for result in mutated_binding_results
    if result.policy_id == "PREFLIGHT-SECRETS-002"
)
if mutated_binding_result.status != "fail" or "must be exactly" not in mutated_binding_result.message:
    raise SystemExit(
        "noncanonical legacy jwt-jwks binding was accepted: "
        f"{mutated_binding_result.message}"
    )

namespace_less_documents = copy.deepcopy(legacy_documents)
for document in namespace_less_documents:
    document.get("metadata", {}).pop("namespace", None)
namespace_less_result = jwks_result(namespace_less_documents)
if namespace_less_result.status != "pass":
    raise SystemExit(
        "namespace-less jwt-jwks wiring did not inherit the configured namespace: "
        f"{namespace_less_result.message}"
    )

wrong_namespace_documents = copy.deepcopy(legacy_documents)
wrong_namespace_secret = next(
    document
    for document in wrong_namespace_documents
    if document.get("kind") == "Secret"
    and document.get("metadata", {}).get("name") == "jwt-jwks"
)
wrong_namespace_secret["metadata"]["namespace"] = "other"
wrong_namespace_result = jwks_result(wrong_namespace_documents)
if wrong_namespace_result.status != "fail" or "expected namespace" not in wrong_namespace_result.message:
    raise SystemExit(
        f"same-name jwt-jwks Secret in the wrong namespace was accepted: {wrong_namespace_result.message}"
    )

namespace_reference_document = {
    "kind": "Deployment",
    "metadata": {"name": "namespace-reference-contract"},
    "spec": {
        "template": {
            "spec": {
                "containers": [
                    {
                        "name": "contract",
                        "envFrom": [{"secretRef": {"name": "postgres-credentials"}}],
                    }
                ]
            }
        }
    },
}
if not module.rendered_references_secret(
    [namespace_reference_document],
    "postgres-credentials",
    "firemud",
    default_namespace="firemud",
):
    raise SystemExit("namespace-less Secret reference did not inherit the expected namespace")
namespace_reference_document["metadata"]["namespace"] = "other"
if module.rendered_references_secret(
    [namespace_reference_document],
    "postgres-credentials",
    "firemud",
    default_namespace="firemud",
):
    raise SystemExit("Secret reference with an explicit wrong namespace was accepted")

image_pull_namespace_reference_document = {
    "kind": "ServiceAccount",
    "metadata": {"name": "image-pull-namespace-reference"},
    "imagePullSecrets": [{"name": "ghcr-pull-hobby"}],
}
if not module.rendered_references_image_pull_secret(
    [image_pull_namespace_reference_document],
    "ghcr-pull-hobby",
    "firemud",
):
    raise SystemExit("namespace-less image pull Secret reference did not inherit the expected namespace")
image_pull_namespace_reference_document["metadata"]["namespace"] = "other"
if module.rendered_references_image_pull_secret(
    [image_pull_namespace_reference_document],
    "ghcr-pull-hobby",
    "firemud",
):
    raise SystemExit("image pull Secret reference with an explicit wrong namespace was accepted")
PY

# Legacy Secret-backed hobby fixture is the current player-facing contract.
set +e
rm -f "$REPORT_PATH"
FIREMUD_PREFLIGHT_CONTEXT=ci-static \
  FIREMUD_DEPLOYMENT_REF=contract-hobby \
  FIREMUD_PREFLIGHT_RENDER_PATH="$RENDERED_MANIFEST" \
  FIREMUD_PREFLIGHT_OUTPUT="$REPORT_PATH" \
  python3 "$SCRIPT" hobby-self-hosted >"$LEGACY_HOBBY_PREFLIGHT_OUTPUT"
preflight_status=$?
set -e
if [ "$preflight_status" -ne 0 ]; then
  echo "static CI must not block on the legacy Secret jwt-jwks diagnostic" >&2
  exit 1
fi

python3 - <<'PY' "$ROOT_DIR" "$REPORT_PATH"
import importlib.util
import json
import pathlib
import sys
import uuid

root = pathlib.Path(sys.argv[1])
report = json.loads(pathlib.Path(sys.argv[2]).read_text(encoding="utf-8"))
spec = importlib.util.spec_from_file_location("preflight_report_contract", root / "dev-tools/deploy/preflight.py")
module = importlib.util.module_from_spec(spec)
assert spec.loader is not None
sys.modules[spec.name] = module
spec.loader.exec_module(module)
expected_ids = {
    "PREFLIGHT-DIGEST-001",
    "PREFLIGHT-DIGEST-002",
    "PREFLIGHT-SECRETS-001",
    "PREFLIGHT-SECRETS-002",
    "PREFLIGHT-JWT-001",
    "PREFLIGHT-JWKS-001",
    "PREFLIGHT-BRIDGE-001",
    "PREFLIGHT-REDIS-001",
    "PREFLIGHT-BOOTSTRAP-001",
    "PREFLIGHT-EXTERNAL-001",
    "PREFLIGHT-SERVICES-001",
    "PREFLIGHT-PROMOTION-001",
    "PREFLIGHT-BACKUP-001",
    "PREFLIGHT-BACKUP-002",
    "PREFLIGHT-BACKUP-003",
}
actual_ids = {check["policyId"] for check in report["checkResults"]}
missing = sorted(expected_ids - actual_ids)
if missing:
    raise SystemExit(f"preflight report missing policy IDs: {missing}")
if actual_ids != expected_ids or len(report["checkResults"]) != len(expected_ids):
    raise SystemExit("preflight report did not emit exactly the complete expected policy set")
if report.get("expectedBindingsRef") != "design/operations/environments/hobby-self-hosted/expected-bindings.yaml":
    raise SystemExit("preflight report missing expectedBindingsRef")
if report.get("policyCatalogVersion") != module.PREFLIGHT_POLICY_CATALOG_VERSION:
    raise SystemExit("preflight report missing or mismatched policyCatalogVersion")
try:
    uuid.UUID(report["deploymentEventId"])
except (KeyError, ValueError) as exc:
    raise SystemExit(f"preflight report missing canonical deploymentEventId: {exc}") from exc
if report.get("trafficOpenEvent") is not None:
    raise SystemExit("general preflight report unexpectedly recorded a traffic-open event")
if any(not isinstance(check.get("required"), bool) for check in report["checkResults"]):
    raise SystemExit("preflight report did not emit required applicability for every policy")
if any(
    check.get("category") != module.PREFLIGHT_POLICY_CATALOG.get(check.get("policyId"))
    for check in report["checkResults"]
):
    raise SystemExit("preflight report did not emit the catalogue category for every policy")
failures = [
    check
    for check in report["checkResults"]
    if check["status"] == "fail"
    and check["policyId"] not in {"PREFLIGHT-DIGEST-002", "PREFLIGHT-JWT-001", "PREFLIGHT-JWKS-001"}
]
if failures:
    raise SystemExit(f"unexpected required preflight failures: {failures}")
legacy_jwks = [
    check
    for check in report["checkResults"]
    if check["policyId"] == "PREFLIGHT-JWKS-001"
]
if len(legacy_jwks) != 1 or legacy_jwks[0]["status"] != "pass":
    raise SystemExit(f"legacy Secret jwt-jwks fixture did not pass the current diagnostic: {legacy_jwks}")
for policy_id in ("PREFLIGHT-JWT-001", "PREFLIGHT-JWKS-001"):
    diagnostic = [check for check in report["checkResults"] if check["policyId"] == policy_id]
    if len(diagnostic) != 1 or diagnostic[0]["required"]:
        raise SystemExit(f"{policy_id} diagnostic was incorrectly apply-blocking: {diagnostic}")
PY

# A ConfigMap-backed player-facing fixture remains deferred and must fail required binding checks.
set +e
FIREMUD_PREFLIGHT_CONTEXT=ci-static \
  FIREMUD_DEPLOYMENT_REF=contract-hobby-migrated \
  FIREMUD_PREFLIGHT_RENDER_PATH="$MIGRATED_RENDERED_MANIFEST" \
  FIREMUD_PREFLIGHT_OUTPUT="$MIGRATED_REPORT_PATH" \
  python3 "$SCRIPT" hobby-self-hosted >"$MIGRATED_HOBBY_PREFLIGHT_OUTPUT"
migrated_preflight_status=$?
set -e
if [ "$migrated_preflight_status" -eq 0 ]; then
  echo "deferred ConfigMap-backed hobby fixture unexpectedly passed preflight" >&2
  exit 1
fi

python3 - <<'PY' "$MIGRATED_REPORT_PATH"
import json
import pathlib
import sys

report = json.loads(pathlib.Path(sys.argv[1]).read_text(encoding="utf-8"))
required_failures = [
    check
    for check in report["checkResults"]
    if check["required"] and check["status"] == "fail"
]
if not any(check["policyId"] == "PREFLIGHT-SECRETS-001" for check in required_failures):
    raise SystemExit(f"deferred ConfigMap fixture did not fail the required Secret check: {required_failures}")
migrated_jwks = [
    check
    for check in report["checkResults"]
    if check["policyId"] == "PREFLIGHT-JWKS-001"
]
if len(migrated_jwks) != 1 or migrated_jwks[0]["status"] != "fail":
    raise SystemExit(f"deferred ConfigMap fixture did not retain the advisory diagnostic: {migrated_jwks}")
PY

python3 "$WRITER" hobby-self-hosted contract-hobby first-live \
  --assessed-by preflight-contract \
  --preflight-report "$OPERATOR_REPORT_PATH" \
  --evidence-ref contract-test \
  --output "$TRAFFIC_EVIDENCE" >"$HOBBY_TRAFFIC_WRITER_OUTPUT"

python3 - <<'PY' "$OPERATOR_REPORT_PATH" "$TRAFFIC_EVIDENCE"
import json
import pathlib
import sys
import uuid

preflight = json.loads(pathlib.Path(sys.argv[1]).read_text(encoding="utf-8"))
traffic = json.loads(pathlib.Path(sys.argv[2]).read_text(encoding="utf-8"))
preflight_event_id = preflight.get("deploymentEventId")
traffic_event_id = traffic.get("deploymentEventId")
for label, event_id in (("preflight", preflight_event_id), ("traffic-open", traffic_event_id)):
    if not isinstance(event_id, str):
        raise SystemExit(f"{label} deploymentEventId is missing")
    try:
        parsed_event_id = uuid.UUID(event_id)
    except ValueError as exc:
        raise SystemExit(f"{label} deploymentEventId is not a UUID: {exc}") from exc
    if str(parsed_event_id) != event_id:
        raise SystemExit(f"{label} deploymentEventId is not canonical")
if traffic_event_id != preflight_event_id:
    raise SystemExit("traffic-open writer did not preserve the preflight deploymentEventId")
PY

rm -f "$REPORT_PATH"
FIREMUD_PREFLIGHT_CONTEXT=ci-static \
  FIREMUD_DEPLOYMENT_REF=contract-hobby \
  FIREMUD_PREFLIGHT_RENDER_PATH="$RENDERED_MANIFEST" \
  FIREMUD_PREFLIGHT_OUTPUT="$REPORT_PATH" \
  FIREMUD_TRAFFIC_OPEN_EVENT=first-live \
  python3 "$SCRIPT" hobby-self-hosted >"$TRAFFIC_HOBBY_PREFLIGHT_OUTPUT" 2>&1 && {
    echo "expected hobby first-live preflight without controller authority to fail" >&2
    exit 1
  }

python3 - <<'PY' "$REPORT_PATH"
import json
import pathlib
import sys

report = json.loads(pathlib.Path(sys.argv[1]).read_text(encoding="utf-8"))
backup_003 = [
    check
    for check in report["checkResults"]
    if check["policyId"] == "PREFLIGHT-BACKUP-003"
]
if len(backup_003) != 1 or backup_003[0]["status"] != "fail":
    raise SystemExit(f"PREFLIGHT-BACKUP-003 did not fail closed: {backup_003}")
if "durable environment-wide recovery-controller authority is not implemented" not in backup_003[0]["message"]:
    raise SystemExit(f"PREFLIGHT-BACKUP-003 failed for the wrong reason: {backup_003}")
PY

python3 - <<'PY' "$ROOT_DIR" "$TMP_DIR"
import ast
import importlib.util
import json
import pathlib
import sys

root = pathlib.Path(sys.argv[1])
tmp = pathlib.Path(sys.argv[2])
preflight_path = root / "dev-tools/deploy/preflight.py"
preflight_source = preflight_path.read_text(encoding="utf-8")
try:
    preflight_tree = ast.parse(preflight_source, filename=str(preflight_path))
except SyntaxError as exc:
    raise SystemExit(f"could not parse preflight.py for main contract: {exc}") from exc

main_nodes = [
    node
    for node in preflight_tree.body
    if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef)) and node.name == "main"
]
if len(main_nodes) != 1:
    raise SystemExit(f"expected exactly one top-level main function in preflight.py, found {len(main_nodes)}")


def called_name(node):
    if isinstance(node, ast.Name):
        return node.id
    if isinstance(node, ast.Attribute):
        return node.attr
    return None


class MainContractVisitor(ast.NodeVisitor):
    promotion_attestation_reload = False
    recovery_compatibility_evaluations = 0

    def visit_Call(self, node):
        if called_name(node.func) == "load_json" and any(
            isinstance(child, ast.Name) and child.id == "promotion_attestation"
            for argument in node.args
            for child in ast.walk(argument)
        ):
            self.promotion_attestation_reload = True
        if called_name(node.func) == "recovery_compatibility_check":
            self.recovery_compatibility_evaluations += 1
        self.generic_visit(node)


main_contract = MainContractVisitor()
main_contract.visit(main_nodes[0])
if main_contract.promotion_attestation_reload:
    raise SystemExit("production preflight reloaded the promotion attestation inside main")
if main_contract.recovery_compatibility_evaluations:
    raise SystemExit("production preflight duplicated recovery compatibility evaluation inside main")

spec = importlib.util.spec_from_file_location("preflight_hobby_contract", root / "dev-tools/deploy/preflight.py")
module = importlib.util.module_from_spec(spec)
assert spec.loader is not None
sys.modules[spec.name] = module
spec.loader.exec_module(module)

deployment_event_id = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
validation_now = module.dt.datetime(2026, 1, 1, 0, 5, tzinfo=module.dt.timezone.utc)


def validate_report(report, environment, deployment_ref):
    return module.validate_preflight_report(
        report,
        environment,
        f"design/operations/environments/{environment}/expected-bindings.yaml",
        deployment_ref,
        now_dt=validation_now,
    )

def report_results(environment, traffic_open_event=None):
    requirements = module.expected_preflight_policy_requirements(environment, traffic_open_event)
    return [
        {
            "policyId": policy_id,
            "category": module.PREFLIGHT_POLICY_CATALOG[policy_id],
            "required": requirements[policy_id],
            "status": (
                "pass"
                if requirements[policy_id] or (environment == "hobby-self-hosted" and policy_id == "PREFLIGHT-DIGEST-002")
                else "not_applicable"
            ),
            "message": "contract evidence",
        }
        for policy_id in module.EXPECTED_PREFLIGHT_POLICY_IDS
    ]

complete_report = {
    "environment": "hobby-self-hosted",
    "expectedBindingsRef": "design/operations/environments/hobby-self-hosted/expected-bindings.yaml",
    "deploymentRef": {"manifestRef": "contract-hobby"},
    "deploymentEventId": deployment_event_id,
    "trafficOpenEvent": None,
    "policyCatalogVersion": module.PREFLIGHT_POLICY_CATALOG_VERSION,
    "startedAt": "2026-01-01T00:00:00Z",
    "completedAt": "2026-01-01T00:00:01Z",
    "toolVersion": "preflight.py-v1",
    "context": "operator",
    "checkResults": report_results("hobby-self-hosted"),
}
complete_status, complete_message = validate_report(
    complete_report, "hobby-self-hosted", "contract-hobby"
)
if complete_status != "pass":
    raise SystemExit(f"complete preflight policy set did not pass: {complete_message}")
ordinary_supplemental_report = {
    **complete_report,
    "checkResults": complete_report["checkResults"]
    + [
        {
            "policyId": "PREFLIGHT-JWT-ROTATION-001",
            "category": module.PREFLIGHT_POLICY_CATALOG["PREFLIGHT-JWT-ROTATION-001"],
            "required": True,
            "status": "pass",
            "message": "ordinary report must not authorize rotation evidence",
        }
    ],
}
ordinary_supplemental_status, ordinary_supplemental_message = validate_report(
    ordinary_supplemental_report, "hobby-self-hosted", "contract-hobby"
)
if ordinary_supplemental_status != "fail" or "unknown policy IDs" not in ordinary_supplemental_message:
    raise SystemExit(
        "ordinary preflight validation accepted supplemental JWT rotation evidence: "
        + ordinary_supplemental_message
    )
for advisory_status in ("pass", "fail"):
    advisory_report = {
        **complete_report,
        "checkResults": [
            (
                {**check, "status": advisory_status}
                if check["policyId"] == "PREFLIGHT-JWT-001"
                else check
            )
            for check in complete_report["checkResults"]
        ],
    }
    advisory_result, advisory_message = validate_report(
        advisory_report, "hobby-self-hosted", "contract-hobby"
    )
    if advisory_result != "pass":
        raise SystemExit(
            f"advisory executable status {advisory_status} was rejected: {advisory_message}"
        )
fixture_policy_id = "PREFLIGHT-BACKUP-003"
if fixture_policy_id not in module.PREFLIGHT_POLICY_CATALOG:
    raise SystemExit(f"invalid preflight policy catalogue fixture ID is missing: {fixture_policy_id}")
for invalid_catalog, expected_fragment in (
    (
        {
            policy_id: category
            for policy_id, category in module.PREFLIGHT_POLICY_CATALOG.items()
            if policy_id != fixture_policy_id
        },
        f"missing policy IDs: {fixture_policy_id}",
    ),
    (
        {**module.PREFLIGHT_POLICY_CATALOG, "PREFLIGHT-UNKNOWN-001": "apply-blocking"},
        "unknown policy IDs: PREFLIGHT-UNKNOWN-001",
    ),
    (
        {**module.PREFLIGHT_POLICY_CATALOG, fixture_policy_id: "invalid"},
        f"invalid preflight policy catalogue categories for policy IDs: {fixture_policy_id}",
    ),
):
    catalog_message = module.validate_preflight_policy_catalog(invalid_catalog)
    if catalog_message is None or expected_fragment not in catalog_message:
        raise SystemExit(
            "invalid preflight policy catalogue failed for the wrong reason: "
            f"expected '{expected_fragment}', got {catalog_message!r}"
        )
for invalid_version in (None, "preflight-policy-v0"):
    invalid_version_report = {**complete_report}
    if invalid_version is None:
        invalid_version_report.pop("policyCatalogVersion")
    else:
        invalid_version_report["policyCatalogVersion"] = invalid_version
    version_status, version_message = validate_report(
        invalid_version_report, "hobby-self-hosted", "contract-hobby"
    )
    if version_status != "fail" or "policyCatalogVersion" not in version_message:
        raise SystemExit(f"invalid policy catalogue version was accepted: {version_message}")
missing_category_report = {
    **complete_report,
    "checkResults": [
        ({key: value for key, value in check.items() if key != "category"} if index == 0 else check)
        for index, check in enumerate(complete_report["checkResults"])
    ],
}
missing_category_status, missing_category_message = validate_report(
    missing_category_report, "hobby-self-hosted", "contract-hobby"
)
if missing_category_status != "fail" or "malformed checkResults" not in missing_category_message:
    raise SystemExit(f"missing policy result category was accepted: {missing_category_message}")
invalid_category_report = {
    **complete_report,
    "checkResults": [
        ({**check, "category": "invalid"} if index == 0 else check)
        for index, check in enumerate(complete_report["checkResults"])
    ],
}
invalid_category_status, invalid_category_message = validate_report(
    invalid_category_report, "hobby-self-hosted", "contract-hobby"
)
if invalid_category_status != "fail" or "malformed checkResults" not in invalid_category_message:
    raise SystemExit(f"invalid policy result category was accepted: {invalid_category_message}")
mismatched_category_report = {
    **complete_report,
    "checkResults": [
        (
            {
                **check,
                "category": (
                    "advisory"
                    if module.PREFLIGHT_POLICY_CATALOG[check["policyId"]] != "advisory"
                    else "apply-blocking"
                ),
            }
            if index == 0
            else check
        )
        for index, check in enumerate(complete_report["checkResults"])
    ],
}
mismatched_category_status, mismatched_category_message = validate_report(
    mismatched_category_report, "hobby-self-hosted", "contract-hobby"
)
if mismatched_category_status != "fail" or "mismatched policy categories" not in mismatched_category_message:
    raise SystemExit(f"mismatched policy result category was accepted: {mismatched_category_message}")
for invalid_event_id in (None, "not-a-uuid", "AAAAAAAA-AAAA-4AAA-8AAA-AAAAAAAAAAAA"):
    invalid_report = {**complete_report, "deploymentEventId": invalid_event_id}
    invalid_status, invalid_message = validate_report(
        invalid_report, "hobby-self-hosted", "contract-hobby"
    )
    if invalid_status != "fail" or "deploymentEventId" not in invalid_message:
        raise SystemExit(
            f"invalid preflight deployment event ID was accepted: {invalid_event_id!r}, {invalid_message}"
        )
forged_all_pass_report = {
    **complete_report,
    "checkResults": [
        {**check, "status": "pass"}
        for check in complete_report["checkResults"]
    ],
}
forged_status, forged_message = validate_report(
    forged_all_pass_report, "hobby-self-hosted", "contract-hobby"
)
if forged_status != "fail" or "non-applicable policy IDs" not in forged_message:
    raise SystemExit(f"synthetic all-pass report was accepted: {forged_message}")
wrong_requirement_report = {
    **complete_report,
    "checkResults": [
        ({**check, "required": not check["required"]} if check["policyId"] == "PREFLIGHT-DIGEST-001" else check)
        for check in complete_report["checkResults"]
    ],
}
wrong_requirement_status, wrong_requirement_message = validate_report(
    wrong_requirement_report, "hobby-self-hosted", "contract-hobby"
)
if wrong_requirement_status != "fail" or "incorrect required applicability" not in wrong_requirement_message:
    raise SystemExit(f"incorrect report applicability was accepted: {wrong_requirement_message}")

staging_report = {
    **complete_report,
    "environment": "staging",
    "expectedBindingsRef": "design/operations/environments/staging/expected-bindings.yaml",
    "deploymentRef": {"overlayCommitSha": "contract-staging"},
    "checkResults": report_results("staging"),
}
for executable_status in ("pass", "fail"):
    staging_digest_report = {
        **staging_report,
        "checkResults": [
            (
                {**check, "status": executable_status}
                if check["policyId"] == "PREFLIGHT-DIGEST-002"
                else check
            )
            for check in staging_report["checkResults"]
        ],
    }
    digest_status, digest_message = validate_report(
        staging_digest_report, "staging", "contract-staging"
    )
    if digest_status != "fail" or "non-applicable policy IDs" not in digest_message:
        raise SystemExit(
            f"staging hobby digest status {executable_status} was accepted: {digest_message}"
        )

stale_report = {
    **complete_report,
    "startedAt": "2025-12-31T23:20:00Z",
    "completedAt": "2025-12-31T23:30:00Z",
}
stale_status, stale_message = validate_report(
    stale_report, "hobby-self-hosted", "contract-hobby"
)
if stale_status != "fail" or "older than the 30-minute consumption window" not in stale_message:
    raise SystemExit(f"stale generally consumed preflight report was accepted: {stale_message}")

static_report = {**complete_report, "context": "ci-static"}
static_status, static_message = validate_report(
    static_report, "hobby-self-hosted", "contract-hobby"
)
if static_status != "fail" or "operator context" not in static_message:
    raise SystemExit(f"static preflight report was accepted as operator evidence: {static_message}")
incomplete_report = {**complete_report, "checkResults": complete_report["checkResults"][1:]}
incomplete_status, incomplete_message = validate_report(
    incomplete_report, "hobby-self-hosted", "contract-hobby"
)
if incomplete_status != "fail" or "missing expected policy IDs" not in incomplete_message:
    raise SystemExit(f"incomplete preflight policy set did not fail closed: {incomplete_message}")

not_applicable_report = {
    **complete_report,
    "checkResults": [
        {**check, "status": "not_applicable", "message": "synthetic evidence"}
        for check in complete_report["checkResults"]
    ],
}
not_applicable_status, not_applicable_message = validate_report(
    not_applicable_report, "hobby-self-hosted", "contract-hobby"
)
if not_applicable_status != "fail" or "non-passing required policy IDs" not in not_applicable_message:
    raise SystemExit(f"all-not-applicable preflight report did not fail closed: {not_applicable_message}")

PY

# The checked-in production overlay lacks the dedicated bridge client wiring,
# so static preflight must fail closed rather than authorize URL-only wiring.
set +e
FIREMUD_PREFLIGHT_CONTEXT=ci-static \
  FIREMUD_DEPLOYMENT_REF="contract-production" \
  FIREMUD_PREFLIGHT_OUTPUT="$PRODUCTION_REPORT" \
  python3 "$SCRIPT" production >"$LEGACY_PRODUCTION_PREFLIGHT_OUTPUT"
production_preflight_status=$?
set -e
if [ "$production_preflight_status" -eq 0 ]; then
  echo "production preflight unexpectedly authorized URL-only bridge wiring" >&2
  exit 1
fi
python3 - "$PRODUCTION_REPORT" <<'PY'
import json
import pathlib
import sys

report = json.loads(pathlib.Path(sys.argv[1]).read_text(encoding="utf-8"))
bridge = next(check for check in report["checkResults"] if check["policyId"] == "PREFLIGHT-BRIDGE-001")
if bridge["status"] != "fail" or bridge["required"] is not True:
    raise SystemExit(f"production bridge gap was not an explicit required failure: {bridge}")
PY

cat >"$LEGACY_PRODUCTION_TRAFFIC_EVIDENCE" <<'JSON'
{
  "schemaVersion": "traffic-open-record/v1",
  "environment": "production",
  "eventType": "reopen",
  "deploymentRef": "contract-production",
  "assessedAt": "2026-07-22T00:00:00Z",
  "assessedBy": "preflight-contract",
  "preflightReportPath": "design/operations/deployments/production/preflight/contract-production.json",
  "backupLastSuccessAt": "2026-07-22T00:00:00Z",
  "backupVerifyLastSuccessAt": "2026-07-22T00:00:00Z",
  "restoreDrillLastSuccessAt": "2026-07-22T00:00:00Z",
  "coordinatedBackupScope": {
    "type": "tenant_region",
    "tenantId": "tenant-1",
    "regionId": "region-1"
  },
  "evidenceRefs": ["caller-supplied-legacy-evidence"]
}
JSON

if python3 "$WRITER" production contract-production reopen \
  --assessed-by preflight-contract \
  --preflight-report "$PRODUCTION_REPORT" \
  --evidence-ref contract-test \
  --output "$LEGACY_PRODUCTION_TRAFFIC_EVIDENCE" >"$PRODUCTION_TRAFFIC_WRITER_OUTPUT" 2>&1; then
  echo "legacy production traffic-open writer unexpectedly succeeded" >&2
  exit 1
fi
grep -Fq "hobby-self-hosted" "$PRODUCTION_TRAFFIC_WRITER_OUTPUT"

rm -f "$PRODUCTION_REPORT"
if FIREMUD_PREFLIGHT_CONTEXT=ci-static \
  FIREMUD_DEPLOYMENT_REF="contract-production" \
  FIREMUD_PREFLIGHT_OUTPUT="$PRODUCTION_REPORT" \
  FIREMUD_TRAFFIC_OPEN_EVENT=reopen \
  python3 "$SCRIPT" production >"$GATED_PRODUCTION_PREFLIGHT_OUTPUT" 2>&1; then
  echo "production traffic-open preflight unexpectedly passed without controller authority" >&2
  exit 1
fi

python3 - <<'PY' "$PRODUCTION_REPORT"
import json
import pathlib
import sys

report = json.loads(pathlib.Path(sys.argv[1]).read_text(encoding="utf-8"))
backup_002 = [
    check
    for check in report["checkResults"]
    if check["policyId"] == "PREFLIGHT-BACKUP-002"
]
if len(backup_002) != 1 or backup_002[0]["status"] != "fail":
    raise SystemExit(f"PREFLIGHT-BACKUP-002 did not fail closed: {backup_002}")
if "durable environment-wide recovery-controller authority is not implemented" not in backup_002[0]["message"]:
    raise SystemExit(f"PREFLIGHT-BACKUP-002 did not report controller unavailability: {backup_002}")
PY

cat >"$PRODUCTION_WAIVER" <<JSON
{
  "environment": "production",
  "deploymentRef": "contract-production",
  "deploymentEventId": "77777777-7777-4777-8777-777777777777",
  "expiration": "deployment-event",
  "recordedAt": "$(date -u +%Y-%m-%dT%H:%M:%SZ)",
  "approver": "preflight-contract",
  "ticket": "contract-ticket",
  "waivedPolicyIds": ["PREFLIGHT-BACKUP-002"]
}
JSON

rm -f "$PRODUCTION_REPORT"
if FIREMUD_PREFLIGHT_CONTEXT=ci-static \
  FIREMUD_DEPLOYMENT_REF="contract-production" \
  FIREMUD_PREFLIGHT_OUTPUT="$PRODUCTION_REPORT" \
  FIREMUD_PREFLIGHT_WAIVER="$PRODUCTION_WAIVER" \
  FIREMUD_TRAFFIC_OPEN_EVENT=reopen \
  python3 "$SCRIPT" production >"$WAIVER_PRODUCTION_PREFLIGHT_OUTPUT" 2>&1; then
  echo "production traffic-open gate unexpectedly accepted a waiver" >&2
  exit 1
fi

if ! grep -q "waiver execution remains blocked" "$WAIVER_PRODUCTION_PREFLIGHT_OUTPUT"; then
  echo "production waiver failed for the wrong reason" >&2
  cat "$WAIVER_PRODUCTION_PREFLIGHT_OUTPUT" >&2
  exit 1
fi
if [[ -e "$PRODUCTION_REPORT" ]]; then
  echo "blocked waiver unexpectedly produced an authoritative report" >&2
  exit 1
fi

for env in staging production; do
  REPORT="$TMP_DIR/preflight-$env.json"
  # These checked-in overlays lack the dedicated bridge client wiring; static
  # CI must record that required gap rather than authorize URL-only wiring.
  set +e
  FIREMUD_PREFLIGHT_CONTEXT=ci-static \
    FIREMUD_DEPLOYMENT_REF="contract-$env" \
    FIREMUD_PREFLIGHT_OUTPUT="$REPORT" \
    python3 "$SCRIPT" "$env" >"$TMP_DIR/firemud-preflight-contract-$env.out"
  preflight_status=$?
  set -e
  if [ "$preflight_status" -eq 0 ]; then
    echo "$env: URL-only bridge wiring unexpectedly passed static CI" >&2
    exit 1
  fi

  python3 - <<'PY' "$REPORT" "$env"
import json
import pathlib
import sys

report = json.loads(pathlib.Path(sys.argv[1]).read_text(encoding="utf-8"))
env = sys.argv[2]
expected_ref = f"design/operations/environments/{env}/expected-bindings.yaml"
if report.get("expectedBindingsRef") != expected_ref:
    raise SystemExit(f"{env}: expectedBindingsRef mismatch: {report.get('expectedBindingsRef')}")
failures = [
    check
    for check in report["checkResults"]
    if check["status"] == "fail" and check["policyId"] not in {"PREFLIGHT-JWT-001", "PREFLIGHT-JWKS-001", "PREFLIGHT-BRIDGE-001", "PREFLIGHT-SERVICES-001", "PREFLIGHT-REDIS-001"}
]
if failures:
    raise SystemExit(f"{env}: unexpected preflight failures: {failures}")
bridge = [check for check in report["checkResults"] if check["policyId"] == "PREFLIGHT-BRIDGE-001"]
if len(bridge) != 1 or bridge[0]["status"] != "fail" or bridge[0]["required"] is not True:
    raise SystemExit(f"{env}: missing explicit required bridge-client failure: {bridge}")
for policy_id in ("PREFLIGHT-SERVICES-001", "PREFLIGHT-REDIS-001"):
    effective_check = [check for check in report["checkResults"] if check["policyId"] == policy_id]
    if len(effective_check) != 1 or effective_check[0]["status"] != "pass" or effective_check[0]["required"] is not True:
        raise SystemExit(f"{env}: unrelated external Secret absence contaminated {policy_id}: {effective_check}")
jwks = [check for check in report["checkResults"] if check["policyId"] == "PREFLIGHT-JWKS-001"]
if len(jwks) != 1 or jwks[0]["status"] != "pass":
    raise SystemExit(f"{env}: legacy Secret jwt-jwks fixture did not pass the current diagnostic: {jwks}")
for policy_id in ("PREFLIGHT-JWT-001", "PREFLIGHT-JWKS-001"):
    diagnostic = [check for check in report["checkResults"] if check["policyId"] == policy_id]
    if len(diagnostic) != 1 or diagnostic[0]["required"]:
        raise SystemExit(f"{env}: {policy_id} diagnostic was incorrectly apply-blocking: {diagnostic}")
PY
done

python3 - <<'PY' "$ROOT_DIR" "$TMP_DIR"
import copy
import json
import importlib.util
import pathlib
import sys
import yaml

root = pathlib.Path(sys.argv[1])
tmp = pathlib.Path(sys.argv[2])
env_root = tmp / "envs"
for env in ("staging", "production", "hobby-self-hosted"):
    (env_root / env).mkdir(parents=True, exist_ok=True)

base = {
    "internalBindings": {},
    "backupStorage": {
        "enabled": True,
        "bucket": "unique-backups",
        "bindingRef": "secret://firemud/unique-backup",
    },
    "assetStorage": {
        "enabled": True,
        "bucket": "unique-assets",
        "endpoint": "https://assets.unique.internal",
        "bindingRef": "secret://firemud/unique-assets",
    },
    "outboundComms": {
        "enabled": True,
        "smtpHost": "smtp.unique.internal",
        "webhookTargets": {"accountNotifications": "unique-only"},
    },
    "operatorCredentials": {"bindingRef": "cert-manager://firemud/unique-operator"},
    "serviceDiscovery": {"mode": "kubernetes-dns-default"},
}

staging = {"environment": "staging", **base}
production = {"environment": "production", **base}
hobby = {"environment": "hobby-self-hosted", **base}

staging["backupStorage"] = {
    "enabled": True,
    "bucket": "dup-backups",
    "bindingRef": "secret://firemud/staging-backup",
}
production["backupStorage"] = {
    "enabled": True,
    "bucket": "dup-backups",
    "bindingRef": "secret://firemud/production-backup",
}
hobby["backupStorage"] = {
    "enabled": True,
    "bucket": "hobby-backups",
    "bindingRef": "secret://firemud/hobby-backup",
}

for env, data in (("staging", staging), ("production", production), ("hobby-self-hosted", hobby)):
    path = env_root / env / "expected-bindings.yaml"
    path.write_text(yaml.safe_dump(data, sort_keys=False), encoding="utf-8")

spec = importlib.util.spec_from_file_location("preflight", root / "dev-tools/deploy/preflight.py")
module = importlib.util.module_from_spec(spec)
assert spec.loader is not None
sys.modules[spec.name] = module
spec.loader.exec_module(module)

overflow_timestamp = "9999-12-31T23:59:59-14:00"
try:
    module.parse_timestamp(overflow_timestamp, "overflow timestamp")
except module.TIMESTAMP_ERRORS as exc:
    if not isinstance(exc, OverflowError):
        raise SystemExit(f"unexpected overflow fixture exception: {exc!r}")
else:
    raise SystemExit("offset-aware overflow timestamp unexpectedly normalized")
try:
    module.parse_timestamp("2026-01-01T00:00:00", "naive timestamp")
except module.TIMESTAMP_ERRORS as exc:
    if "timezone" not in str(exc):
        raise SystemExit(f"naive timestamp failed for the wrong reason: {exc}")
else:
    raise SystemExit("naive timestamp unexpectedly accepted")

original_subprocess_run = module.subprocess.run
try:
    def not_found_lookup(*args, **kwargs):
        if kwargs.get("timeout") != module.SECRET_LOOKUP_TIMEOUT_SECONDS:
            raise SystemExit("Secret lookup did not receive its deployment timeout")
        return module.subprocess.CompletedProcess(
            args, 1, "", 'Error from server (NotFound): secrets "missing" not found'
        )

    module.subprocess.run = not_found_lookup
    not_found_message = module.secret_lookup_failure("missing")
    if not_found_message != "Missing required Secret in cluster: firemud/missing":
        raise SystemExit(f"NotFound Secret lookup reported incorrectly: {not_found_message}")
    namespaced_message = module.secret_lookup_failure("missing", "other")
    if namespaced_message != "Missing required Secret in cluster: other/missing":
        raise SystemExit(f"namespaced Secret lookup reported incorrectly: {namespaced_message}")

    forbidden_stderr = 'Error from server (Forbidden): secrets is forbidden'
    module.subprocess.run = lambda *args, **kwargs: module.subprocess.CompletedProcess(
        args, 1, "", forbidden_stderr
    )
    forbidden_message = module.secret_lookup_failure("forbidden")
    expected_forbidden = (
        "Secret lookup could not be verified for firemud/forbidden: "
        + forbidden_stderr
    )
    if forbidden_message != expected_forbidden:
        raise SystemExit(f"non-NotFound Secret lookup reported incorrectly: {forbidden_message}")

    def raise_lookup_timeout(*args, **kwargs):
        raise module.subprocess.TimeoutExpired(
            args, module.SECRET_LOOKUP_TIMEOUT_SECONDS
        )

    module.subprocess.run = raise_lookup_timeout
    timeout_message = module.secret_lookup_failure("timed-out")
    if (
        timeout_message is None
        or not timeout_message.startswith(
            "Secret lookup could not be verified for firemud/timed-out:"
        )
        or "timed out" not in timeout_message
    ):
        raise SystemExit(f"Timeout Secret lookup reported incorrectly: {timeout_message}")

    def raise_lookup_unicode_error(*args, **kwargs):
        raise UnicodeDecodeError("utf-8", b"\xff", 0, 1, "invalid start byte")

    module.subprocess.run = raise_lookup_unicode_error
    unicode_error_message = module.secret_lookup_failure("undecodable")
    if (
        unicode_error_message is None
        or not unicode_error_message.startswith(
            "Secret lookup could not be verified for firemud/undecodable:"
        )
        or "codec can't decode" not in unicode_error_message
    ):
        raise SystemExit(
            f"Unicode decoding Secret lookup reported incorrectly: {unicode_error_message}"
        )

    def raise_lookup_os_error(*args, **kwargs):
        raise OSError("kubectl unavailable")

    module.subprocess.run = raise_lookup_os_error
    os_error_message = module.secret_lookup_failure("unavailable")
    expected_os_error = (
        "Secret lookup could not be verified for firemud/unavailable: "
        "kubectl unavailable"
    )
    if os_error_message != expected_os_error:
        raise SystemExit(f"OSError Secret lookup reported incorrectly: {os_error_message}")

finally:
    module.subprocess.run = original_subprocess_run

issues = module.external_binding_uniqueness_issues(env_root, "staging", staging)
if not any("backupStorage.bucket matches production" in issue for issue in issues):
    raise SystemExit(f"expected duplicate backupStorage.bucket issue, got: {issues}")

staging["observability"] = {"otelCollectorEndpoint": "https://otel.shared.internal:4317"}
production["observability"] = {"otelCollectorEndpoint": "https://otel.shared.internal:4317"}
for env, data in (("staging", staging), ("production", production)):
    path = env_root / env / "expected-bindings.yaml"
    path.write_text(yaml.safe_dump(data, sort_keys=False), encoding="utf-8")
issues = module.external_binding_uniqueness_issues(env_root, "staging", staging)
if not any("observability.otelCollectorEndpoint matches production" in issue for issue in issues):
    raise SystemExit(f"undeclared shared OTEL endpoint was accepted: {issues}")
shared_otel = {
    "value": "https://otel.shared.internal:4317",
    "shared": True,
    "sharedRationale": "shared collector endpoint",
}
staging["observability"]["otelCollectorEndpoint"] = shared_otel
production["observability"]["otelCollectorEndpoint"] = shared_otel
for env, data in (("staging", staging), ("production", production)):
    path = env_root / env / "expected-bindings.yaml"
    path.write_text(yaml.safe_dump(data, sort_keys=False), encoding="utf-8")
issues = module.external_binding_uniqueness_issues(env_root, "staging", staging)
if any("observability.otelCollectorEndpoint" in issue for issue in issues):
    raise SystemExit(f"declared shared OTEL endpoint was rejected: {issues}")

disabled_staging = copy.deepcopy(staging)
disabled_production = copy.deepcopy(production)
disabled_staging["backupStorage"] = {"enabled": False}
disabled_staging["assetStorage"] = {"enabled": False}
for env, data in (("staging", disabled_staging), ("production", disabled_production)):
    path = env_root / env / "expected-bindings.yaml"
    path.write_text(yaml.safe_dump(data, sort_keys=False), encoding="utf-8")

issues = module.external_binding_uniqueness_issues(
    env_root, "staging", disabled_staging
)
if any(issue.startswith("backupStorage.") for issue in issues):
    raise SystemExit(f"disabled backup storage must be excluded from uniqueness checks: {issues}")
if any(issue.startswith("assetStorage.") for issue in issues):
    raise SystemExit(f"disabled asset storage must be excluded from uniqueness checks: {issues}")

active_staging = copy.deepcopy(staging)
active_production = copy.deepcopy(production)
active_staging["assetStorage"]["bucket"] = "dup-assets"
active_production["assetStorage"]["bucket"] = "dup-assets"
for env, data in (("staging", active_staging), ("production", active_production)):
    path = env_root / env / "expected-bindings.yaml"
    path.write_text(yaml.safe_dump(data, sort_keys=False), encoding="utf-8")
issues = module.external_binding_uniqueness_issues(env_root, "staging", active_staging)
if not any("assetStorage.bucket matches production" in issue for issue in issues):
    raise SystemExit(f"enabled asset storage must participate in uniqueness checks: {issues}")

shared_value = {"value": "smtp.shared.internal", "shared": True, "sharedRationale": "shared relay"}
staging["outboundComms"]["smtpHost"] = shared_value
production["outboundComms"]["smtpHost"] = shared_value
for env, data in (("staging", staging), ("production", production)):
    path = env_root / env / "expected-bindings.yaml"
    path.write_text(yaml.safe_dump(data, sort_keys=False), encoding="utf-8")

issues = module.external_binding_uniqueness_issues(env_root, "staging", staging)
if any("outboundComms.smtpHost" in issue for issue in issues):
    raise SystemExit(f"shared smtpHost should be allowed, got: {issues}")

exclusive_shared = copy.deepcopy(staging)
exclusive_shared["operatorCredentials"]["bindingRef"] = {
    "value": "cert-manager://firemud/shared-operator",
    "shared": True,
    "sharedRationale": "incorrectly shared operator identity",
}
if not any(
    "operatorCredentials.bindingRef is environment-exclusive" in issue
    for issue in module.external_binding_uniqueness_issues(env_root, "staging", exclusive_shared)
):
    raise SystemExit("environment-exclusive operator identity sharing was accepted")

missing_shared_rationale = copy.deepcopy(staging)
missing_shared_rationale["assetStorage"]["bucket"] = {
    "value": "assets.shared.internal",
    "shared": True,
}
if not any(
    "assetStorage.bucket is marked shared but missing sharedRationale" in issue
    for issue in module.external_binding_uniqueness_issues(
        env_root, "staging", missing_shared_rationale
    )
):
    raise SystemExit("conditional shared asset target without rationale was accepted")

credential_shaped_target = copy.deepcopy(staging)
credential_shaped_target["assetStorage"]["bucket"] = {
    "bindingRef": "secret://firemud/shared-asset-credentials",
    "shared": True,
    "sharedRationale": "invalid credential-shaped target",
}
if not any(
    "assetStorage.bucket is a non-sensitive target and cannot use credential fields: bindingRef"
    in issue
    for issue in module.external_binding_uniqueness_issues(
        env_root, "staging", credential_shaped_target
    )
):
    raise SystemExit("credential-shaped shared asset target was accepted")


def verify_service_override_contract(case_name, rendered_overrides, allowed_overrides, expected_status, expected_fragment):
    base_expected = yaml.safe_load((root / "design/operations/environments/staging/expected-bindings.yaml").read_text(encoding="utf-8"))
    base_expected["environment"] = "staging"
    base_expected["serviceDiscovery"] = {
        "mode": "explicit-overrides",
        "allowedOverrides": allowed_overrides,
    }
    expected_path = tmp / f"{case_name}-expected-bindings.yaml"
    expected_path.write_text(yaml.safe_dump(base_expected, sort_keys=False), encoding="utf-8")

    rendered_payload = pathlib.Path(f"{tmp}/hobby-rendered.yaml").read_text(encoding="utf-8")
    documents = module.parse_documents(rendered_payload)
    config_maps = [
        document
        for document in documents
        if document.get("kind") == "ConfigMap"
        and document.get("metadata", {}).get("name") == "firemud-config"
        and document.get("metadata", {}).get("namespace") == "firemud"
    ]
    if len(config_maps) != 1:
        raise SystemExit(f"{case_name}: expected exactly one referenced firemud-config ConfigMap")
    config_maps[0]["data"] = rendered_overrides
    results = module.expected_binding_checks(
        expected_path, f"design/operations/environments/{case_name}-explicit-overrides.yaml", "staging", documents
    )
    service_check = next(
        (result for result in results if result.policy_id == "PREFLIGHT-SERVICES-001"),
        None,
    )
    if service_check is None:
        raise SystemExit(f"{case_name}: missing PREFLIGHT-SERVICES-001 result")
    if service_check.status != expected_status:
        raise SystemExit(
            f"{case_name}: expected PREFLIGHT-SERVICES-001 {expected_status}, got {service_check.status}: {service_check.message}"
        )
    if expected_fragment and expected_fragment not in service_check.message:
        raise SystemExit(
            f"{case_name}: expected services message fragment '{expected_fragment}', got '{service_check.message}'"
        )


verify_service_override_contract(
    "pass",
    {"FIREMUD_SERVICES_ACCOUNT_SERVICE": "account-service.firemud.svc.cluster.local"},
    {"FIREMUD_SERVICES_ACCOUNT_SERVICE": "account-service.firemud.svc.cluster.local"},
    "pass",
    "match expected explicit contract",
)
verify_service_override_contract(
    "undeclared",
    {"FIREMUD_SERVICES_UNKNOWN_SERVICE": "mystery.firemud.svc.cluster.local"},
    {"FIREMUD_SERVICES_ACCOUNT_SERVICE": "account-service.firemud.svc.cluster.local"},
    "fail",
    "not declared",
)
verify_service_override_contract(
    "mismatch",
    {"FIREMUD_SERVICES_ACCOUNT_SERVICE": "mismatch.firemud.svc.cluster.local"},
    {"FIREMUD_SERVICES_ACCOUNT_SERVICE": "account-service.firemud.svc.cluster.local"},
    "fail",
    "does not match allowed value",
)
verify_service_override_contract(
    "external-host",
    {"FIREMUD_SERVICES_ACCOUNT_SERVICE": "account-service.evil.example:6565"},
    {"FIREMUD_SERVICES_ACCOUNT_SERVICE": "account-service.evil.example:6565"},
    "fail",
    "Kubernetes environment",
)
verify_service_override_contract(
    "missing",
    {},
    {"FIREMUD_SERVICES_ACCOUNT_SERVICE": "account-service.firemud.svc.cluster.local"},
    "fail",
    "missing from effective workloads",
)
verify_service_override_contract(
    "partial-missing",
    {"FIREMUD_SERVICES_ACCOUNT_SERVICE": "account-service.firemud.svc.cluster.local"},
    {
        "FIREMUD_SERVICES_ACCOUNT_SERVICE": "account-service.firemud.svc.cluster.local",
        "FIREMUD_SERVICES_GAME_SESSION_SERVICE": "game-session-service.firemud.svc.cluster.local",
    },
    "fail",
    "FIREMUD_SERVICES_GAME_SESSION_SERVICE",
)
verify_service_override_contract(
    "malformed-rendered-key",
    {"FIREMUD_SERVICES_account_SERVICE": "account-service.firemud.svc.cluster.local"},
    {"FIREMUD_SERVICES_ACCOUNT_SERVICE": "account-service.firemud.svc.cluster.local"},
    "fail",
    "not declared",
)
verify_service_override_contract(
    "invalid-unused-entry",
    {"FIREMUD_SERVICES_ACCOUNT_SERVICE": "account-service.firemud.svc.cluster.local"},
    {
        "FIREMUD_SERVICES_ACCOUNT_SERVICE": "account-service.firemud.svc.cluster.local",
        "FIREMUD_SERVICES_UNUSED": 7,
    },
    "fail",
    "must be a string",
)

rendered_documents = module.parse_documents(pathlib.Path(tmp / "hobby-rendered.yaml").read_text(encoding="utf-8"))

for env in ("production", "staging", "hobby-self-hosted"):
    expected = yaml.safe_load(
        (root / f"design/operations/environments/{env}/expected-bindings.yaml").read_text(encoding="utf-8")
    )
    custody_mode = expected.get("internalBindings", {}).get("jwt", {}).get("custodyMode")
    if custody_mode != module.IMPLEMENTED_JWT_CUSTODY_MODE:
        raise SystemExit(f"{env}: checked-in manifest selected unexpected JWT custody mode: {custody_mode}")

current_expected_path = root / "design/operations/environments/hobby-self-hosted/expected-bindings.yaml"
unreferenced_decoy_documents = copy.deepcopy(rendered_documents)
unreferenced_decoy_documents.append(
    {
        "apiVersion": "v1",
        "kind": "ConfigMap",
        "metadata": {"name": "unreferenced-decoy", "namespace": "firemud"},
        "data": {
            "FIREMUD_SERVICES_DECOY": "evil.firemud.svc.cluster.local",
        },
    }
)
unreferenced_decoy_results = module.expected_binding_checks(
    current_expected_path,
    "design/operations/environments/hobby-self-hosted/expected-bindings.yaml",
    "hobby-self-hosted",
    unreferenced_decoy_documents,
)
unreferenced_decoy_service = next(
    result
    for result in unreferenced_decoy_results
    if result.policy_id == "PREFLIGHT-SERVICES-001"
)
if unreferenced_decoy_service.status != "pass":
    raise SystemExit(
        "unreferenced service-discovery ConfigMap decoy affected preflight: "
        + unreferenced_decoy_service.message
    )

current_results = module.expected_binding_checks(
    current_expected_path,
    "design/operations/environments/hobby-self-hosted/expected-bindings.yaml",
    "hobby-self-hosted",
    rendered_documents,
)
current_secrets = next(result for result in current_results if result.policy_id == "PREFLIGHT-SECRETS-002")
if current_secrets.status != "pass":
    raise SystemExit(f"checked-in legacy JWT custody selector did not pass current wiring validation: {current_secrets.message}")


def effective_env_documents(*, config_map=None, config_map_namespace="firemud", secret=None, secret_namespace="firemud", workload_namespace="firemud", env=None, env_from=None):
    workload = {
        "kind": "Deployment",
        "metadata": {"name": "account-service", "namespace": workload_namespace},
        "spec": {
            "template": {
                "spec": {
                    "containers": [
                        {
                            "name": "account-service",
                            "env": env or [],
                            "envFrom": env_from or [],
                        }
                    ]
                }
            }
        },
    }
    documents = [workload]
    if config_map is not None:
        documents.insert(
            0,
            {
                "kind": "ConfigMap",
                "metadata": {"name": "cfg", "namespace": config_map_namespace},
                "data": config_map,
            },
        )
    if secret is not None:
        documents.insert(
            0,
            {
                "kind": "Secret",
                "metadata": {"name": "cfg", "namespace": secret_namespace},
                "stringData": secret,
            },
        )
    return documents, workload, workload["spec"]["template"]["spec"]["containers"][0]


precedence_documents, precedence_workload, precedence_container = effective_env_documents(
    config_map={"FIREMUD_SERVICES_ACCOUNT_SERVICE": "from-config-map"},
    env=[{"name": "FIREMUD_SERVICES_ACCOUNT_SERVICE", "value": "direct-env"}],
    env_from=[{"configMapRef": {"name": "cfg"}}],
)
precedence_values, precedence_issues = module.effective_container_env(
    precedence_documents, precedence_workload, precedence_container
)
if precedence_issues or precedence_values.get("FIREMUD_SERVICES_ACCOUNT_SERVICE") != "direct-env":
    raise SystemExit(f"direct env did not override envFrom: {precedence_values}, {precedence_issues}")

for case_name, config_map, config_map_namespace, env, env_from, expects_issue in (
    ("required-configmap", None, "firemud", [], [{"configMapRef": {"name": "cfg"}}], True),
    ("optional-configmap", None, "firemud", [], [{"configMapRef": {"name": "cfg", "optional": True}}], False),
    ("malformed-configmap-optional", None, "firemud", [], [{"configMapRef": {"name": "cfg", "optional": "true"}}], True),
    (
        "required-configmap-key",
        {},
        "firemud",
        [{"name": "FIREMUD_SERVICES_ACCOUNT_SERVICE", "valueFrom": {"configMapKeyRef": {"name": "cfg", "key": "missing"}}}],
        [],
        True,
    ),
    (
        "optional-configmap-key",
        {},
        "firemud",
        [{"name": "FIREMUD_SERVICES_ACCOUNT_SERVICE", "valueFrom": {"configMapKeyRef": {"name": "cfg", "key": "missing", "optional": True}}}],
        [],
        False,
    ),
    (
        "malformed-configmap-key-optional",
        {},
        "firemud",
        [{"name": "FIREMUD_SERVICES_ACCOUNT_SERVICE", "valueFrom": {"configMapKeyRef": {"name": "cfg", "key": "missing", "optional": "false"}}}],
        [],
        True,
    ),
    ("namespace-decoy-configmap", {"FIREMUD_SERVICES_ACCOUNT_SERVICE": "decoy"}, "other", [], [{"configMapRef": {"name": "cfg"}}], True),
):
    documents, workload, container = effective_env_documents(
        config_map=config_map,
        config_map_namespace=config_map_namespace,
        env=env,
        env_from=env_from,
    )
    _, issues = module.effective_container_env(documents, workload, container)
    if bool(issues) != expects_issue:
        raise SystemExit(f"{case_name}: unexpected ConfigMap optional/namespace result: {issues}")

for case_name, secret, secret_namespace, env, env_from, expects_issue in (
    ("required-secret", None, "firemud", [], [{"secretRef": {"name": "cfg"}}], True),
    ("optional-secret", None, "firemud", [], [{"secretRef": {"name": "cfg", "optional": True}}], False),
    ("malformed-secret-optional", None, "firemud", [], [{"secretRef": {"name": "cfg", "optional": 1}}], True),
    (
        "required-secret-key",
        {},
        "firemud",
        [{"name": "FIREMUD_SERVICES_ACCOUNT_SERVICE", "valueFrom": {"secretKeyRef": {"name": "cfg", "key": "missing"}}}],
        [],
        True,
    ),
    (
        "optional-secret-key",
        {},
        "firemud",
        [{"name": "FIREMUD_SERVICES_ACCOUNT_SERVICE", "valueFrom": {"secretKeyRef": {"name": "cfg", "key": "missing", "optional": True}}}],
        [],
        False,
    ),
    (
        "malformed-secret-key-optional",
        {},
        "firemud",
        [{"name": "FIREMUD_SERVICES_ACCOUNT_SERVICE", "valueFrom": {"secretKeyRef": {"name": "cfg", "key": "missing", "optional": 0}}}],
        [],
        True,
    ),
    ("namespace-decoy-secret", {"FIREMUD_SERVICES_ACCOUNT_SERVICE": "decoy"}, "other", [], [{"secretRef": {"name": "cfg"}}], True),
):
    documents, workload, container = effective_env_documents(
        secret=secret,
        secret_namespace=secret_namespace,
        env=env,
        env_from=env_from,
    )
    _, issues = module.effective_container_env(documents, workload, container)
    if bool(issues) != expects_issue:
        raise SystemExit(f"{case_name}: unexpected Secret optional/namespace result: {issues}")

secret_documents, secret_workload, secret_container = effective_env_documents(
    env_from=[{"secretRef": {"name": "bridge-config"}}],
)
secret_documents.append(
    {
        "kind": "Secret",
        "metadata": {"name": "bridge-config", "namespace": "firemud"},
        "stringData": {"FIREMUD_SERVICES_ACCOUNT_SERVICE": "secret-backed"},
    }
)
secret_values, secret_issues = module.effective_container_env(
    secret_documents, secret_workload, secret_container
)
if not secret_issues or secret_values.get("FIREMUD_SERVICES_ACCOUNT_SERVICE") is not None:
    raise SystemExit(f"Secret-backed service override was not rejected safely: {secret_values}, {secret_issues}")
secret_overrides, secret_override_issues = module.extract_service_discovery_overrides(secret_documents)
if not secret_override_issues or secret_overrides:
    raise SystemExit(f"Secret-backed service override was not rejected: {secret_overrides}, {secret_override_issues}")

secret_key_documents, secret_key_workload, secret_key_container = effective_env_documents(
    env=[
        {
            "name": "FIREMUD_SERVICES_ACCOUNT_SERVICE",
            "valueFrom": {"secretKeyRef": {"name": "bridge-config", "key": "FIREMUD_SERVICES_ACCOUNT_SERVICE"}},
        }
    ],
)
secret_key_documents.append(
    {
        "kind": "Secret",
        "metadata": {"name": "bridge-config", "namespace": "firemud"},
        "stringData": {"FIREMUD_SERVICES_ACCOUNT_SERVICE": "secret-key-backed"},
    }
)
secret_key_values, secret_key_issues = module.effective_container_env(
    secret_key_documents, secret_key_workload, secret_key_container
)
if not secret_key_issues or secret_key_values:
    raise SystemExit(f"Secret-backed service valueFrom was not rejected: {secret_key_values}, {secret_key_issues}")

uninspectable_secret_documents, uninspectable_secret_workload, uninspectable_secret_container = effective_env_documents(
    env_from=[{"secretRef": {"name": "external-bridge-config"}}],
)
_, uninspectable_secret_issues = module.effective_container_env(
    uninspectable_secret_documents,
    uninspectable_secret_workload,
    uninspectable_secret_container,
    relevant_prefixes=("FIREMUD_SERVICES_",),
)
if uninspectable_secret_issues:
    raise SystemExit(f"unrelated missing external Secret contaminated service checks: {uninspectable_secret_issues}")

external_secret_documents, external_secret_workload, external_secret_container = effective_env_documents(
    config_map={"FIREMUD_SERVICES_ACCOUNT_SERVICE": "account-service.firemud.svc.cluster.local"},
    env_from=[
        {"configMapRef": {"name": "cfg"}},
        {"secretRef": {"name": "postgres-credentials"}},
    ],
)
external_secret_documents.append(
    {
        "kind": "Secret",
        "metadata": {"name": "postgres-credentials", "namespace": "firemud"},
        "data": "externally-managed-and-not-rendered",
    }
)
external_values, external_issues = module.effective_container_env(
    external_secret_documents,
    external_secret_workload,
    external_secret_container,
    relevant_prefixes=("FIREMUD_SERVICES_",),
)
if external_issues or external_values.get("FIREMUD_SERVICES_ACCOUNT_SERVICE") != "account-service.firemud.svc.cluster.local":
    raise SystemExit(f"unrelated external Secret contaminated effective service config: {external_values}, {external_issues}")

malformed_configmap_documents, malformed_configmap_workload, malformed_configmap_container = effective_env_documents(
    config_map="not-a-mapping",
    env_from=[{"configMapRef": {"name": "cfg"}}],
)
_, malformed_configmap_issues = module.effective_container_env(
    malformed_configmap_documents,
    malformed_configmap_workload,
    malformed_configmap_container,
    relevant_prefixes=("FIREMUD_SERVICES_",),
)
if not any("data must be a mapping" in issue for issue in malformed_configmap_issues):
    raise SystemExit(f"malformed ConfigMap data did not fail closed: {malformed_configmap_issues}")

malformed_secret_documents, malformed_secret_workload, malformed_secret_container = effective_env_documents(
    env_from=[{"secretRef": {"name": "malformed-secret"}}],
)
malformed_secret_documents.append(
    {
        "kind": "Secret",
        "metadata": {"name": "malformed-secret", "namespace": "firemud"},
        "data": ["not-a-mapping"],
    }
)
_, malformed_secret_issues = module.effective_container_env(
    malformed_secret_documents,
    malformed_secret_workload,
    malformed_secret_container,
)
if not any("data and stringData must be mappings" in issue for issue in malformed_secret_issues):
    raise SystemExit(f"malformed Secret data did not fail closed: {malformed_secret_issues}")

bridge_values, bridge_issues = module.validate_gateway_ws_values(
    rendered_documents, yaml.safe_load(current_expected_path.read_text(encoding="utf-8"))
)
if bridge_issues or not bridge_values:
    raise SystemExit(f"canonical bridge fixture did not pass: {bridge_issues}")
invalid_listener_expected = yaml.safe_load(current_expected_path.read_text(encoding="utf-8"))
invalid_listener_expected["internalBindings"]["certificates"]["gatewayInternalWsListenerRef"] = "secret://firemud/not-a-cert-manager-binding"
_, invalid_listener_issues = module.validate_gateway_ws_values(rendered_documents, invalid_listener_expected)
if not any("gatewayInternalWsListenerRef must be a cert-manager binding" in issue for issue in invalid_listener_issues):
    raise SystemExit(f"malformed Gateway listener binding was not rejected explicitly: {invalid_listener_issues}")
bridge_mutation = copy.deepcopy(rendered_documents)
for document in bridge_mutation:
    if document.get("kind") == "Deployment" and document.get("metadata", {}).get("name") == "tcp-proxy-service":
        for container in document["spec"]["template"]["spec"]["containers"]:
            for entry in container.get("env", []):
                if entry.get("name") == "GATEWAY_WS_URL":
                    entry["value"] = "wss://evil-spring-cloud-gateway-mtls.firemud.svc.cluster.local/ws/game"
_, bridge_mutation_issues = module.validate_gateway_ws_values(
    bridge_mutation, yaml.safe_load(current_expected_path.read_text(encoding="utf-8"))
)
if not any("does not match canonical" in issue for issue in bridge_mutation_issues):
    raise SystemExit("bridge host decoy was accepted")


def set_bridge_url(documents, value):
    for document in documents:
        if document.get("kind") != "Deployment" or document.get("metadata", {}).get("name") != "tcp-proxy-service":
            continue
        for container in document.get("spec", {}).get("template", {}).get("spec", {}).get("containers", []):
            for entry in container.get("env", []):
                if entry.get("name") == "GATEWAY_WS_URL":
                    entry["value"] = value


canonical_bridge_url = "wss://spring-cloud-gateway-mtls.firemud.svc.cluster.local/ws/game"
for case_name, value, expected_fragment in (
    ("scheme", "ws://spring-cloud-gateway-mtls.firemud.svc.cluster.local/ws/game", "does not match canonical"),
    ("port", "wss://spring-cloud-gateway-mtls.firemud.svc.cluster.local:444/ws/game", "does not match canonical"),
    ("port-zero", "wss://spring-cloud-gateway-mtls.firemud.svc.cluster.local:0/ws/game", "has an invalid port"),
    ("path", "wss://spring-cloud-gateway-mtls.firemud.svc.cluster.local/wrong", "does not match canonical"),
):
    documents = copy.deepcopy(rendered_documents)
    set_bridge_url(documents, value)
    _, issues = module.validate_gateway_ws_values(
        documents, yaml.safe_load(current_expected_path.read_text(encoding="utf-8"))
    )
    if not any(expected_fragment in issue for issue in issues):
        raise SystemExit(f"bridge {case_name} decoy was accepted: {issues}")

duplicate_service_documents = copy.deepcopy(rendered_documents)
duplicate_service_documents.append(
    copy.deepcopy(
        next(
            document
            for document in duplicate_service_documents
            if document.get("kind") == "Service"
            and document.get("metadata", {}).get("name") == "spring-cloud-gateway-mtls"
        )
    )
)
_, duplicate_service_issues = module.validate_gateway_ws_values(
    duplicate_service_documents, yaml.safe_load(current_expected_path.read_text(encoding="utf-8"))
)
if not any("exactly one rendered internal Gateway mTLS Service" in issue for issue in duplicate_service_issues):
    raise SystemExit(f"duplicate internal Gateway Service was accepted: {duplicate_service_issues}")

conflicting_bridge_documents = copy.deepcopy(rendered_documents)
account_deployment = next(
    document
    for document in conflicting_bridge_documents
    if document.get("kind") == "Deployment" and document.get("metadata", {}).get("name") == "account-service"
)
account_container = next(
    container
    for container in account_deployment["spec"]["template"]["spec"]["containers"]
    if container.get("name") == "account-service"
)
account_container.setdefault("env", []).extend(
    [
        {"name": "GATEWAY_WS_URL", "value": "wss://spring-cloud-gateway-mtls.firemud.svc.cluster.local:443/ws/game"},
        {"name": "FIREMUD_GATEWAY_WS_CLIENT_CERT_CHAIN_PATH", "value": "/tls/client.crt"},
        {"name": "FIREMUD_GATEWAY_WS_CLIENT_PRIVATE_KEY_PATH", "value": "/tls/client.key"},
        {"name": "FIREMUD_GATEWAY_WS_CA_CERT_PATH", "value": "/tls/ca.crt"},
    ]
)
account_container.setdefault("volumeMounts", []).append(
    {"name": "hobby-tcp-proxy-bridge", "mountPath": "/tls", "readOnly": True}
)
account_deployment["spec"]["template"]["spec"].setdefault("volumes", []).append(
    {"name": "hobby-tcp-proxy-bridge", "secret": {"secretName": "hobby-tcp-proxy-bridge"}}
)
_, conflicting_bridge_issues = module.validate_gateway_ws_values(
    conflicting_bridge_documents, yaml.safe_load(current_expected_path.read_text(encoding="utf-8"))
)
if not any("effective GATEWAY_WS_URL values conflict across workloads" in issue for issue in conflicting_bridge_issues):
    raise SystemExit(f"conflicting applicable bridge value was accepted: {conflicting_bridge_issues}")

def set_bridge_env(documents, name, value):
    for document in documents:
        if document.get("kind") != "Deployment" or document.get("metadata", {}).get("name") != "tcp-proxy-service":
            continue
        for container in document.get("spec", {}).get("template", {}).get("spec", {}).get("containers", []):
            if container.get("name") != "tcp-proxy-service":
                continue
            for entry in container.get("env", []):
                if entry.get("name") == name:
                    entry["value"] = value


for path_name, expected_path in (
    ("FIREMUD_GATEWAY_WS_CLIENT_CERT_CHAIN_PATH", "/tls/client.crt"),
    ("FIREMUD_GATEWAY_WS_CLIENT_PRIVATE_KEY_PATH", "/tls/client.key"),
    ("FIREMUD_GATEWAY_WS_CA_CERT_PATH", "/tls/ca.crt"),
):
    bridge_path_documents = copy.deepcopy(rendered_documents)
    set_bridge_url(bridge_path_documents, canonical_bridge_url)
    set_bridge_env(bridge_path_documents, path_name, "/wrong/bridge-file")
    _, bridge_path_issues = module.validate_gateway_ws_values(
        bridge_path_documents, yaml.safe_load(current_expected_path.read_text(encoding="utf-8"))
    )
    if not any(f"must be exactly {expected_path!r}" in issue for issue in bridge_path_issues):
        raise SystemExit(f"noncanonical bridge path {path_name} was accepted: {bridge_path_issues}")

bridge_mount_documents = copy.deepcopy(rendered_documents)
bridge_mount_container = next(
    container
    for document in bridge_mount_documents
    if document.get("kind") == "Deployment" and document.get("metadata", {}).get("name") == "tcp-proxy-service"
    for container in document["spec"]["template"]["spec"]["containers"]
    if container.get("name") == "tcp-proxy-service"
)
bridge_mount_container["volumeMounts"] = [
    mount for mount in bridge_mount_container["volumeMounts"] if mount.get("name") != "hobby-tcp-proxy-bridge"
]
_, bridge_mount_issues = module.validate_gateway_ws_values(
    bridge_mount_documents, yaml.safe_load(current_expected_path.read_text(encoding="utf-8"))
)
if not any("dedicated read-only Secret-backed /tls mount" in issue for issue in bridge_mount_issues):
    raise SystemExit(f"missing bridge client mount was accepted: {bridge_mount_issues}")

bridge_readonly_documents = copy.deepcopy(rendered_documents)
bridge_readonly_container = next(
    container
    for document in bridge_readonly_documents
    if document.get("kind") == "Deployment" and document.get("metadata", {}).get("name") == "tcp-proxy-service"
    for container in document["spec"]["template"]["spec"]["containers"]
    if container.get("name") == "tcp-proxy-service"
)
next(
    mount for mount in bridge_readonly_container["volumeMounts"] if mount.get("name") == "hobby-tcp-proxy-bridge"
)["readOnly"] = False
_, bridge_readonly_issues = module.validate_gateway_ws_values(
    bridge_readonly_documents, yaml.safe_load(current_expected_path.read_text(encoding="utf-8"))
)
if not any("dedicated read-only Secret-backed /tls mount" in issue for issue in bridge_readonly_issues):
    raise SystemExit(f"writable bridge client mount was accepted: {bridge_readonly_issues}")

bridge_secret_documents = copy.deepcopy(rendered_documents)
bridge_secret_deployment = next(
    document
    for document in bridge_secret_documents
    if document.get("kind") == "Deployment"
    and document.get("metadata", {}).get("name") == "tcp-proxy-service"
)
next(
    volume
    for volume in bridge_secret_deployment["spec"]["template"]["spec"]["volumes"]
    if volume.get("name") == "hobby-tcp-proxy-bridge"
)["secret"]["secretName"] = "wrong-bridge-secret"
_, bridge_secret_issues = module.validate_gateway_ws_values(
    bridge_secret_documents, yaml.safe_load(current_expected_path.read_text(encoding="utf-8"))
)
if not any("must reference Secret firemud/hobby-tcp-proxy-bridge" in issue for issue in bridge_secret_issues):
    raise SystemExit(f"wrong bridge Secret identity was accepted: {bridge_secret_issues}")

bridge_subpath_documents = copy.deepcopy(rendered_documents)
bridge_subpath_container = next(
    container
    for document in bridge_subpath_documents
    if document.get("kind") == "Deployment" and document.get("metadata", {}).get("name") == "tcp-proxy-service"
    for container in document["spec"]["template"]["spec"]["containers"]
    if container.get("name") == "tcp-proxy-service"
)
next(
    mount for mount in bridge_subpath_container["volumeMounts"] if mount.get("name") == "hobby-tcp-proxy-bridge"
)["subPath"] = "client.crt"
_, bridge_subpath_issues = module.validate_gateway_ws_values(
    bridge_subpath_documents, yaml.safe_load(current_expected_path.read_text(encoding="utf-8"))
)
if not any("must not use subPath" in issue for issue in bridge_subpath_issues):
    raise SystemExit(f"bridge Secret subPath was accepted: {bridge_subpath_issues}")

bridge_items_documents = copy.deepcopy(rendered_documents)
bridge_items_volume = next(
    volume
    for document in bridge_items_documents
    if document.get("kind") == "Deployment" and document.get("metadata", {}).get("name") == "tcp-proxy-service"
    for volume in document["spec"]["template"]["spec"]["volumes"]
    if volume.get("name") == "hobby-tcp-proxy-bridge"
)
bridge_items_volume["secret"]["items"] = [{"key": "client.crt"}]
_, bridge_items_issues = module.validate_gateway_ws_values(
    bridge_items_documents, yaml.safe_load(current_expected_path.read_text(encoding="utf-8"))
)
if not any("items must include" in issue for issue in bridge_items_issues):
    raise SystemExit(f"restrictive bridge Secret items were accepted: {bridge_items_issues}")

bridge_identity_documents = copy.deepcopy(rendered_documents)
next(
    volume for volume in next(
        document for document in bridge_identity_documents
        if document.get("kind") == "Deployment" and document.get("metadata", {}).get("name") == "tcp-proxy-service"
    )["spec"]["template"]["spec"]["volumes"]
    if volume.get("name") == "hobby-tcp-proxy-bridge"
)["secret"]["secretName"] = "grpc-tls"
_, bridge_identity_issues = module.validate_gateway_ws_values(
    bridge_identity_documents, yaml.safe_load(current_expected_path.read_text(encoding="utf-8"))
)
if not any("dedicated read-only Secret-backed /tls mount" in issue for issue in bridge_identity_issues):
    raise SystemExit(f"bridge client reused the gRPC Secret identity without failing: {bridge_identity_issues}")

redis_endpoints, redis_issues = module.effective_redis_endpoints(
    rendered_documents, yaml.safe_load(current_expected_path.read_text(encoding="utf-8"))
)
if redis_issues or redis_endpoints != {
    "redis-coord.firemud.svc.cluster.local:6379",
    "redis-cache.firemud.svc.cluster.local:6379",
}:
    raise SystemExit(f"canonical Redis fixture did not pass: {redis_issues}, {redis_endpoints}")
redis_mutation = copy.deepcopy(rendered_documents)
for document in redis_mutation:
    if document.get("kind") == "ConfigMap" and document.get("metadata", {}).get("name") == "firemud-config":
        document["data"]["FIREMUD_REDIS_COORD_HOST"] = "redis-cache"
_, redis_mutation_issues = module.effective_redis_endpoints(
    redis_mutation, yaml.safe_load(current_expected_path.read_text(encoding="utf-8"))
)
if not any("does not match expected" in issue for issue in redis_mutation_issues):
    raise SystemExit("referenced Redis ConfigMap mismatch was accepted")

for case_name, field, value in (
    ("cache-host", "FIREMUD_REDIS_CACHE_HOST", "redis-coord"),
    ("cache-port", "FIREMUD_REDIS_CACHE_PORT", "6380"),
):
    documents = copy.deepcopy(rendered_documents)
    config_map = next(
        document
        for document in documents
        if document.get("kind") == "ConfigMap" and document.get("metadata", {}).get("name") == "firemud-config"
    )
    config_map["data"][field] = value
    _, issues = module.effective_redis_endpoints(
        documents, yaml.safe_load(current_expected_path.read_text(encoding="utf-8"))
    )
    if not any("does not match expected" in issue for issue in issues):
        raise SystemExit(f"Redis {case_name} mismatch was accepted: {issues}")

collision_expected = yaml.safe_load(current_expected_path.read_text(encoding="utf-8"))
collision_expected["internalBindings"]["redis"]["cache"]["endpoint"] = collision_expected["internalBindings"]["redis"]["coordination"]["endpoint"]
collision_documents = copy.deepcopy(rendered_documents)
collision_config_map = next(
    document
    for document in collision_documents
    if document.get("kind") == "ConfigMap" and document.get("metadata", {}).get("name") == "firemud-config"
)
collision_config_map["data"]["FIREMUD_REDIS_CACHE_HOST"] = "redis-coord"
_, collision_issues = module.effective_redis_endpoints(collision_documents, collision_expected)
if not any("same host:port" in issue for issue in collision_issues):
    raise SystemExit(f"Redis coordination/cache endpoint collision was accepted: {collision_issues}")

for case_name, mutate, expected_fragment in (
    (
        "unknown-top-level",
        lambda data: data.__setitem__("typoSection", {"enabled": True}),
        "unknown top-level key",
    ),
    (
        "unknown-nested-field",
        lambda data: data["internalBindings"]["jwt"].__setitem__("jwksReff", "secret://firemud/jwt-jwks"),
        "internalBindings.jwt.jwksReff",
    ),
):
    malformed = yaml.safe_load(current_expected_path.read_text(encoding="utf-8"))
    mutate(malformed)
    malformed_path = tmp / f"{case_name}-expected-bindings.yaml"
    malformed_path.write_text(yaml.safe_dump(malformed, sort_keys=False), encoding="utf-8")
    malformed_results = module.expected_binding_checks(
        malformed_path,
        f"synthetic-{case_name}-expected-bindings.yaml",
        "hobby-self-hosted",
        rendered_documents,
    )
    malformed_secrets = next(
        result for result in malformed_results if result.policy_id == "PREFLIGHT-SECRETS-002"
    )
    if malformed_secrets.status != "fail" or expected_fragment not in malformed_secrets.message:
        raise SystemExit(
            f"{case_name}: unknown expected-bindings key was accepted: {malformed_secrets.message}"
        )

requirements = module.expected_preflight_policy_requirements("hobby-self-hosted", None)
if requirements["PREFLIGHT-JWT-001"] or requirements["PREFLIGHT-JWKS-001"]:
    raise SystemExit("JWT/JWKS diagnostics must be catalogued as advisory applicability")
diagnostic_results = []
if module.append_result(
    diagnostic_results,
    "PREFLIGHT-JWT-001",
    True,
    "fail",
    "synthetic JWT diagnostic",
):
    raise SystemExit("advisory JWT diagnostic incorrectly blocked apply")
if diagnostic_results[0].required:
    raise SystemExit("append_result did not normalize advisory required applicability")

operator_results = module.expected_binding_checks(
    current_expected_path,
    "design/operations/environments/hobby-self-hosted/expected-bindings.yaml",
    "hobby-self-hosted",
    rendered_documents,
    context="operator",
)
operator_bootstrap = next(
    result for result in operator_results if result.policy_id == "PREFLIGHT-BOOTSTRAP-001"
)
if (
    operator_bootstrap.status != "fail"
    or not operator_bootstrap.required
    or "accepted player-facing JWT custody proof" not in operator_bootstrap.message
):
    raise SystemExit(
        "operator custody gate did not fail closed without accepted proof: "
        + operator_bootstrap.message
    )
static_bootstrap = next(
    result for result in current_results if result.policy_id == "PREFLIGHT-BOOTSTRAP-001"
)
if static_bootstrap.status != "pass":
    raise SystemExit("ci-static bootstrap diagnostics unexpectedly became an operator custody gate")


def verify_jwt_custody_selector(case_name, mutate, expected_fragment):
    expected = yaml.safe_load(current_expected_path.read_text(encoding="utf-8"))
    mutate(expected)
    case_path = tmp / f"{case_name}-jwt-custody-bindings.yaml"
    case_path.write_text(yaml.safe_dump(expected, sort_keys=False), encoding="utf-8")
    results = module.expected_binding_checks(
        case_path,
        f"synthetic-{case_name}-jwt-custody-bindings.yaml",
        "hobby-self-hosted",
        rendered_documents,
    )
    secrets = next(result for result in results if result.policy_id == "PREFLIGHT-SECRETS-002")
    if secrets.status != "fail" or expected_fragment not in secrets.message:
        raise SystemExit(
            f"{case_name}: JWT custody selector did not fail as expected: {secrets.message}"
        )


verify_jwt_custody_selector(
    "missing",
    lambda data: data["internalBindings"]["jwt"].pop("custodyMode"),
    "internalBindings.jwt.custodyMode",
)
verify_jwt_custody_selector(
    "unknown",
    lambda data: data["internalBindings"]["jwt"].__setitem__("custodyMode", "UNKNOWN_MODE"),
    "must be one of:",
)
for target_mode in (
    "INTERIM_ACCOUNT_ONLY_MOUNTED_FALLBACK",
    "TARGET_NON_EXPORTABLE_SIGNER",
):
    verify_jwt_custody_selector(
        target_mode.lower(),
        lambda data, mode=target_mode: data["internalBindings"]["jwt"].__setitem__("custodyMode", mode),
        "not currently implemented",
    )

def verify_binding_ref_contract(case_name, mutate, policy_id, expected_fragment):
    expected_path = root / "design/operations/environments/hobby-self-hosted/expected-bindings.yaml"
    expected = yaml.safe_load(expected_path.read_text(encoding="utf-8"))
    mutate(expected)
    case_path = tmp / f"{case_name}-expected-bindings.yaml"
    case_path.write_text(yaml.safe_dump(expected, sort_keys=False), encoding="utf-8")
    results = module.expected_binding_checks(
        case_path,
        f"design/operations/environments/{case_name}-expected-bindings.yaml",
        "hobby-self-hosted",
        rendered_documents,
    )
    policy = next((result for result in results if result.policy_id == policy_id), None)
    if policy is None:
        raise SystemExit(f"{case_name}: missing {policy_id} result")
    if policy.status != "fail":
        raise SystemExit(f"{case_name}: expected {policy_id} fail, got {policy.status}: {policy.message}")
    if expected_fragment not in policy.message:
        raise SystemExit(
            f"{case_name}: expected {policy_id} message to include '{expected_fragment}', got '{policy.message}'"
        )

def verify_operator_credentials_fingerprint_pass():
    expected_path = root / "design/operations/environments/hobby-self-hosted/expected-bindings.yaml"
    expected = yaml.safe_load(expected_path.read_text(encoding="utf-8"))
    expected["operatorCredentials"].pop("bindingRef")
    expected["operatorCredentials"]["fingerprint"] = "sha256:operator-identity"
    case_path = env_root / "hobby-self-hosted" / "fingerprint-only-operator-credentials.yaml"
    case_path.write_text(yaml.safe_dump(expected, sort_keys=False), encoding="utf-8")
    results = module.expected_binding_checks(
        case_path,
        "synthetic-fingerprint-only-operator-credentials.yaml",
        "hobby-self-hosted",
        rendered_documents,
    )
    policy = next(result for result in results if result.policy_id == "PREFLIGHT-EXTERNAL-001")
    if policy.status != "pass":
        raise SystemExit(
            "fingerprint-only operator credentials should pass external preflight: "
            + policy.message
        )

verify_operator_credentials_fingerprint_pass()

def verify_backup_storage_failure(case_name, mutate, expected_fragment, env_class="hobby-self-hosted"):
    expected_path = root / f"design/operations/environments/{env_class}/expected-bindings.yaml"
    expected = yaml.safe_load(expected_path.read_text(encoding="utf-8"))
    mutate(expected)
    case_path = env_root / env_class / f"{case_name}-expected-bindings.yaml"
    case_path.write_text(yaml.safe_dump(expected, sort_keys=False), encoding="utf-8")
    results = module.expected_binding_checks(
        case_path,
        f"synthetic-{case_name}-expected-bindings.yaml",
        env_class,
        rendered_documents,
    )
    policy = next(result for result in results if result.policy_id == "PREFLIGHT-EXTERNAL-001")
    if policy.status != "fail" or expected_fragment not in policy.message:
        raise SystemExit(
            f"{case_name}: expected backup-storage failure containing '{expected_fragment}', got {policy.status}: {policy.message}"
        )

def verify_backup_storage_pass(case_name, mutate):
    expected_path = root / "design/operations/environments/hobby-self-hosted/expected-bindings.yaml"
    expected = yaml.safe_load(expected_path.read_text(encoding="utf-8"))
    mutate(expected)
    case_path = env_root / "hobby-self-hosted" / f"{case_name}-expected-bindings.yaml"
    case_path.write_text(yaml.safe_dump(expected, sort_keys=False), encoding="utf-8")
    results = module.expected_binding_checks(
        case_path,
        f"synthetic-{case_name}-expected-bindings.yaml",
        "hobby-self-hosted",
        rendered_documents,
    )
    policy = next(result for result in results if result.policy_id == "PREFLIGHT-EXTERNAL-001")
    if policy.status != "pass":
        raise SystemExit(f"{case_name}: expected backup-storage validation to pass: {policy.message}")

def verify_optional_integration_failure(case_name, mutate, expected_fragment):
    expected_path = root / "design/operations/environments/hobby-self-hosted/expected-bindings.yaml"
    expected = yaml.safe_load(expected_path.read_text(encoding="utf-8"))
    mutate(expected)
    case_path = env_root / "hobby-self-hosted" / f"{case_name}-expected-bindings.yaml"
    case_path.write_text(yaml.safe_dump(expected, sort_keys=False), encoding="utf-8")
    results = module.expected_binding_checks(
        case_path,
        f"synthetic-{case_name}-expected-bindings.yaml",
        "hobby-self-hosted",
        rendered_documents,
    )
    policy = next(result for result in results if result.policy_id == "PREFLIGHT-EXTERNAL-001")
    if policy.status != "fail" or expected_fragment not in policy.message:
        raise SystemExit(
            f"{case_name}: expected optional integration failure containing '{expected_fragment}', "
            f"got {policy.status}: {policy.message}"
        )

def verify_optional_integration_pass(case_name, mutate):
    expected_path = root / "design/operations/environments/hobby-self-hosted/expected-bindings.yaml"
    expected = yaml.safe_load(expected_path.read_text(encoding="utf-8"))
    mutate(expected)
    case_path = env_root / "hobby-self-hosted" / f"{case_name}-expected-bindings.yaml"
    case_path.write_text(yaml.safe_dump(expected, sort_keys=False), encoding="utf-8")
    results = module.expected_binding_checks(
        case_path,
        f"synthetic-{case_name}-expected-bindings.yaml",
        "hobby-self-hosted",
        rendered_documents,
    )
    policy = next(result for result in results if result.policy_id == "PREFLIGHT-EXTERNAL-001")
    if policy.status != "pass":
        raise SystemExit(f"{case_name}: expected optional integration validation to pass: {policy.message}")

verify_backup_storage_failure(
    "missing-backup-enabled",
    lambda data: data["backupStorage"].pop("enabled"),
    "backupStorage.enabled must be a boolean",
)
verify_backup_storage_failure(
    "wrong-type-backup-enabled",
    lambda data: data["backupStorage"].__setitem__("enabled", "true"),
    "backupStorage.enabled must be a boolean",
)
verify_backup_storage_failure(
    "enabled-missing-backup-bucket",
    lambda data: data["backupStorage"].pop("bucket"),
    "backupStorage.bucket",
)
verify_backup_storage_failure(
    "enabled-missing-backup-binding",
    lambda data: data["backupStorage"].pop("bindingRef"),
    "backupStorage.bindingRef or backupStorage.fingerprint",
)
verify_backup_storage_failure(
    "disabled-populated-backup",
    lambda data: (
        data["backupStorage"].__setitem__("enabled", False),
        data["backupStorage"].__setitem__("fingerprint", "sha256:stale-backup-identity"),
    ),
    "backupStorage fields must be omitted when disabled",
)
verify_backup_storage_failure(
    "production-disabled-backup",
    lambda data: data.__setitem__("backupStorage", {"enabled": False}),
    "backupStorage.enabled must be true for production",
    "production",
)
verify_backup_storage_pass(
    "enabled-fingerprint-backup",
    lambda data: (
        data["backupStorage"].pop("bindingRef"),
        data["backupStorage"].__setitem__("fingerprint", "sha256:backup-identity"),
    ),
)
verify_backup_storage_pass(
    "disabled-omitted-backup",
    lambda data: (
        data.__setitem__("backupStorage", {"enabled": False}),
    ),
)

verify_optional_integration_failure(
    "missing-asset-enabled",
    lambda data: data["assetStorage"].pop("enabled"),
    "assetStorage.enabled must be a boolean",
)
verify_optional_integration_failure(
    "wrong-asset-enabled",
    lambda data: data["assetStorage"].__setitem__("enabled", "true"),
    "assetStorage.enabled must be a boolean",
)
verify_optional_integration_failure(
    "null-asset-section",
    lambda data: data.__setitem__("assetStorage", None),
    "assetStorage must be an object",
)
verify_optional_integration_failure(
    "malformed-asset-bucket-binding",
    lambda data: data["assetStorage"].__setitem__("bucket", {"shared": True}),
    "assetStorage.bucket",
)
verify_optional_integration_failure(
    "missing-asset-bucket",
    lambda data: data["assetStorage"].pop("bucket"),
    "assetStorage.bucket",
)
verify_optional_integration_failure(
    "missing-asset-binding",
    lambda data: data["assetStorage"].pop("bindingRef"),
    "assetStorage.bindingRef or assetStorage.fingerprint",
)
verify_optional_integration_failure(
    "disabled-populated-asset",
    lambda data: (
        data["assetStorage"].__setitem__("enabled", False),
        data["assetStorage"].__setitem__("bucket", "stale-assets"),
    ),
    "assetStorage fields must be omitted when disabled",
)
verify_optional_integration_failure(
    "missing-outbound-enabled",
    lambda data: data["outboundComms"].pop("enabled"),
    "outboundComms.enabled must be a boolean",
)
verify_optional_integration_failure(
    "null-outbound-section",
    lambda data: data.__setitem__("outboundComms", None),
    "outboundComms must be an object",
)
verify_optional_integration_failure(
    "enabled-empty-outbound",
    lambda data: data.__setitem__("outboundComms", {"enabled": True}),
    "Enabled outbound communications require smtpHost or webhookTargets",
)
verify_optional_integration_failure(
    "wrong-type-outbound-targets",
    lambda data: data["outboundComms"].__setitem__("webhookTargets", ["invalid"]),
    "outboundComms.webhookTargets must be a non-empty mapping when present",
)
verify_optional_integration_failure(
    "malformed-outbound-target-binding",
    lambda data: data["outboundComms"].__setitem__(
        "webhookTargets", {"accountNotifications": {"shared": True}}
    ),
    "outboundComms.webhookTargets entries must be non-empty binding values",
)
verify_optional_integration_failure(
    "disabled-populated-outbound",
    lambda data: (
        data["outboundComms"].__setitem__("enabled", False),
        data["outboundComms"].__setitem__("smtpHost", "stale-smtp"),
    ),
    "outboundComms fields must be omitted when disabled",
)
verify_optional_integration_pass(
    "omitted-optional-integrations",
    lambda data: (data.pop("assetStorage"), data.pop("outboundComms")),
)

verify_binding_ref_contract(
    "invalid-internal-binding-ref",
    lambda data: data["internalBindings"]["certificates"].__setitem__("issuerRef", "cert-manager://firemud/not-a-kind/firemud-hobby"),
    "PREFLIGHT-SECRETS-002",
    "internalBindings.certificates.issuerRef must use one of the allowed binding kinds",
)
verify_binding_ref_contract(
    "configmap-jwks-binding-ref",
    lambda data: data["internalBindings"]["jwt"].__setitem__("jwksRef", "configmap://firemud/jwt-jwks"),
    "PREFLIGHT-SECRETS-002",
    "internalBindings.jwt.jwksRef must use one of the allowed schemes: secret",
)
verify_binding_ref_contract(
    "invalid-external-binding-ref",
    lambda data: data["backupStorage"].__setitem__("bindingRef", "not-a-binding-ref"),
    "PREFLIGHT-EXTERNAL-001",
    "backupStorage.bindingRef must use <scheme>://<namespace>/<binding> format",
)
verify_binding_ref_contract(
    "invalid-operator-credentials-binding-ref",
    lambda data: data["operatorCredentials"].__setitem__("bindingRef", "not-a-binding-ref"),
    "PREFLIGHT-EXTERNAL-001",
    "operatorCredentials.bindingRef must use <scheme>://<namespace>/<binding> format",
)
verify_binding_ref_contract(
    "wrong-secret-namespace",
    lambda data: data["internalBindings"]["postgres"].__setitem__(
        "credentialsRef", "secret://other/postgres-credentials"
    ),
    "PREFLIGHT-SECRETS-002",
    "Rendered workloads do not reference expected Secret bindings",
)

routine_expected = yaml.safe_load(
    (root / "design/operations/environments/hobby-self-hosted/expected-bindings.yaml").read_text(encoding="utf-8")
)
routine_path = tmp / "routine-online-backup-bindings.yaml"
routine_path.write_text(yaml.safe_dump(routine_expected, sort_keys=False), encoding="utf-8")
routine_results = module.expected_binding_checks(
    routine_path,
    "synthetic-routine-online-backup-bindings",
    "hobby-self-hosted",
    rendered_documents,
)
routine_secrets = next(result for result in routine_results if result.policy_id == "PREFLIGHT-SECRETS-002")
if routine_secrets.status != "pass":
    raise SystemExit(f"routine online backup must not require backup control-plane identity: {routine_secrets.message}")

exceptional_expected = yaml.safe_load(
    (root / "design/operations/environments/hobby-self-hosted/expected-bindings.yaml").read_text(encoding="utf-8")
)
exceptional_expected["backupMaintenancePause"] = {"enabled": True}
exceptional_expected["internalBindings"]["certificates"]["backupControlPlaneClientRef"] = "not-a-binding-ref"
exceptional_path = tmp / "exceptional-backup-pause-bindings.yaml"
exceptional_path.write_text(yaml.safe_dump(exceptional_expected, sort_keys=False), encoding="utf-8")
exceptional_results = module.expected_binding_checks(
    exceptional_path,
    "synthetic-exceptional-backup-pause-bindings",
    "hobby-self-hosted",
    rendered_documents,
)
exceptional_secrets = next(result for result in exceptional_results if result.policy_id == "PREFLIGHT-SECRETS-002")
if exceptional_secrets.status != "fail" or "backupControlPlaneClientRef" not in exceptional_secrets.message:
    raise SystemExit(f"explicit backup maintenance pause opt-in did not validate its client binding: {exceptional_secrets.message}")

valid_exceptional_expected = yaml.safe_load(
    (root / "design/operations/environments/hobby-self-hosted/expected-bindings.yaml").read_text(encoding="utf-8")
)
valid_exceptional_expected["backupMaintenancePause"] = {"enabled": True}
valid_exceptional_expected["internalBindings"]["certificates"]["backupControlPlaneClientRef"] = (
    "cert-manager://firemud/hobby-backup-control-plane"
)
valid_exceptional_path = tmp / "valid-exceptional-backup-pause-bindings.yaml"
valid_exceptional_path.write_text(yaml.safe_dump(valid_exceptional_expected, sort_keys=False), encoding="utf-8")
valid_exceptional_results = module.expected_binding_checks(
    valid_exceptional_path,
    "synthetic-valid-exceptional-backup-pause-bindings",
    "hobby-self-hosted",
    rendered_documents,
)
valid_exceptional_secrets = next(
    result for result in valid_exceptional_results if result.policy_id == "PREFLIGHT-SECRETS-002"
)
if valid_exceptional_secrets.status != "pass":
    raise SystemExit(f"valid explicit backup maintenance pause configuration did not pass: {valid_exceptional_secrets.message}")

malformed_pause_expected = yaml.safe_load(
    (root / "design/operations/environments/hobby-self-hosted/expected-bindings.yaml").read_text(encoding="utf-8")
)
malformed_pause_expected["backupMaintenancePause"] = {"enabled": "true"}
malformed_pause_path = tmp / "malformed-backup-pause-bindings.yaml"
malformed_pause_path.write_text(yaml.safe_dump(malformed_pause_expected, sort_keys=False), encoding="utf-8")
malformed_pause_results = module.expected_binding_checks(
    malformed_pause_path,
    "synthetic-malformed-backup-pause-bindings",
    "hobby-self-hosted",
    rendered_documents,
)
malformed_pause_secrets = next(
    result for result in malformed_pause_results if result.policy_id == "PREFLIGHT-SECRETS-002"
)
if malformed_pause_secrets.status != "fail" or "must be a boolean" not in malformed_pause_secrets.message:
    raise SystemExit(f"malformed backup maintenance pause opt-in did not fail closed: {malformed_pause_secrets.message}")

undeclared_pause_expected = yaml.safe_load(
    (root / "design/operations/environments/hobby-self-hosted/expected-bindings.yaml").read_text(encoding="utf-8")
)
undeclared_pause_expected["internalBindings"]["certificates"]["backupControlPlaneClientRef"] = (
    "cert-manager://firemud/hobby-backup-control-plane"
)
undeclared_pause_path = tmp / "undeclared-backup-pause-bindings.yaml"
undeclared_pause_path.write_text(yaml.safe_dump(undeclared_pause_expected, sort_keys=False), encoding="utf-8")
undeclared_pause_results = module.expected_binding_checks(
    undeclared_pause_path,
    "synthetic-undeclared-backup-pause-bindings",
    "hobby-self-hosted",
    rendered_documents,
)
undeclared_pause_secrets = next(
    result for result in undeclared_pause_results if result.policy_id == "PREFLIGHT-SECRETS-002"
)
if undeclared_pause_secrets.status != "fail" or "must be omitted" not in undeclared_pause_secrets.message:
    raise SystemExit(f"undeclared backup maintenance identity did not fail closed: {undeclared_pause_secrets.message}")

now = module.dt.datetime.now(module.dt.timezone.utc).replace(microsecond=0)
past_timestamp = (now - module.dt.timedelta(minutes=5)).isoformat().replace("+00:00", "Z")
future_timestamp = (now + module.dt.timedelta(hours=1)).isoformat().replace("+00:00", "Z")
recovery_dir = tmp / "design/operations/deployments/production/recovery"
recovery_dir.mkdir(parents=True)

first_event_id = "11111111-1111-4111-8111-111111111111"
second_event_id = "22222222-2222-4222-8222-222222222222"
first_output_path = module.default_preflight_output_path(
    tmp,
    "staging",
    "contract-overlay",
    first_event_id,
)
second_output_path = module.default_preflight_output_path(
    tmp,
    "staging",
    "contract-overlay",
    second_event_id,
)
expected_first_path = (
    tmp
    / "design/operations/deployments/staging/preflight"
    / "contract-overlay"
    / f"{first_event_id}.json"
)
if first_output_path != expected_first_path or first_output_path == second_output_path:
    raise SystemExit("preflight default output paths are not immutable per deployment event")

def write_json(name, data):
    path = tmp / name
    path.write_text(json.dumps(data), encoding="utf-8")
    return path

def timestamp(value):
    return value.isoformat().replace("+00:00", "Z")

def canonical_recovery_record(finalized_at):
    quarantine_started_at = finalized_at - module.dt.timedelta(minutes=25)
    restored_at = finalized_at - module.dt.timedelta(minutes=20)
    ready_to_reopen_at = finalized_at - module.dt.timedelta(minutes=15)
    quarantine_released_at = finalized_at - module.dt.timedelta(minutes=5)
    credential_validated_at = timestamp(restored_at)
    credential_records = {}
    for class_name in ("backup-storage", "asset-storage", "outbound-comms", "operator-credentials"):
        credential_records[class_name] = {
            "status": "pass",
            "evidenceRef": f"evidence/{class_name}.json",
            "isolationAssertion": "production-equivalent drill boundary",
            "validationMethod": "contract-test",
            "validatedAt": credential_validated_at,
            "validatedBy": "preflight-contract",
            "observedValue": "redacted",
        }
    return {
        "schemaVersion": "recovery-record/v1",
        "environment": "production",
        "recoveryRef": "baseline",
        "operationId": "recovery-operation",
        "recoveryStatus": "finalized",
        "recoveryPurpose": "production-equivalent-drill",
        "sourceEnvironmentBinding": {"environment": "production", "bindingRef": "production"},
        "targetBoundary": {"environment": "production", "boundary": "isolated-drill"},
        "trafficExposure": "isolated-drill",
        "restoreSource": {"type": "current-production-lineage", "artifactRef": "backup-artifact"},
        "restoreSafeMode": {"status": "pass", "playerIngress": "disabled"},
        "coordinationRecoveryMode": "cold_start_restore",
        "backupArtifactRef": "backups/artifact",
        "artifactErasureHighWater": {"stream": "erasures", "sequence": 10},
        "initialCatchupHighWater": {"stream": "erasures", "sequence": 11},
        "restoreHighWater": {"stream": "erasures", "sequence": 12},
        "erasureReplay": {
            "ledgerRef": "erasures",
            "exclusiveStart": 10,
            "initialCatchupThrough": 11,
            "inclusiveEnd": 12,
            "replayedThrough": 12,
            "gapFree": True,
        },
        "erasureOverlayReconciliation": {
            "stream": "erasures",
            "artifactErasureHighWater": {"stream": "erasures", "sequence": 10},
            "initialCatchupHighWater": {"stream": "erasures", "sequence": 11},
            "restoreHighWater": {"stream": "erasures", "sequence": 12},
            "sequenceVerification": {
                "status": "pass",
                "exclusiveStart": 10,
                "inclusiveEnd": 11,
                "ordered": True,
                "contiguous": True,
                "complete": True,
                "gapFree": True,
                "duplicateFree": True,
            },
            "integrityVerification": {"status": "pass", "verified": True},
            "sequenceDispositions": [
                {
                    "stream": "erasures",
                    "sequence": 12,
                    "owner": "account-service",
                    "disposition": "invalidated",
                    "integrityVerified": True,
                },
            ],
        },
        "backupArtifactLineage": {
            "databaseIdentity": "production",
            "snapshotIdentity": "snapshot-production-1",
            "snapshotAt": credential_validated_at,
            "preSnapshotJournalHighWater": {
                "stream": "erasures",
                "sequence": 10,
                "observationId": "observation-1",
                "observedAt": timestamp(restored_at - module.dt.timedelta(minutes=1)),
                "observationDigest": "sha256:observation-1",
            },
            "preSnapshotJournalBoundaryWitness": {
                "observationId": "observation-1",
                "observationDigest": "sha256:observation-1",
                "snapshotIdentity": "snapshot-production-1",
                "snapshotOpenedAt": credential_validated_at,
                "evidenceRef": "evidence/pre-snapshot-journal-boundary.json",
            },
            "artifactErasureHighWater": {"stream": "erasures", "sequence": 10},
            "erasureHighWaterSnapshotBound": True,
        },
        "backupToolDigest": "sha256:backup-tool",
        "recoveryToolDigest": "sha256:recovery-tool",
        "recoveryContractFingerprint": "sha256:recovery-contract",
        "recoveryParticipantInventoryRef": "inventories/participants.json",
        "validatorInventoryRef": "inventories/validators.json",
        "externalEffectInventoryRef": "inventories/external-effects.json",
        "quarantineStartedAt": timestamp(quarantine_started_at),
        "readyToReopenAt": timestamp(ready_to_reopen_at),
        "quarantineReleasedAt": timestamp(quarantine_released_at),
        "finalizedAt": timestamp(finalized_at),
        "restoredAt": timestamp(restored_at),
        "restoredBy": "preflight-contract",
        "recoveryControllerLineage": {
            "recoveryStatus": "finalized",
            "scope": "environment-wide",
            "finalizedReleaseIdentity": "release-1",
        },
        "expectedBindingsRef": "design/operations/environments/production/expected-bindings.yaml",
        "coordinationRecoveryEvidence": {
            "mode": "cold_start_restore",
            "coordinationRedis": "empty-before-rebuild",
            "credentialBinding": "rotated-or-rebound",
            "targetEnvironmentBound": True,
            "snapshotCredentialsRejected": True,
            "regionEpochFences": "advanced-or-recreated",
            "accountAuthorityProjections": "rebuilt-and-verified",
            "accountAuthorityProjectionEvidenceRef": "evidence/account-authority-projections.json",
            "replayAdmissionFence": "advanced",
            "replayQuarantine": "lifetime-plus-skew-observed",
            "replayConsumeEvidenceRef": "evidence/replay-consume.json",
        },
        "backupConfidentialityEvidence": {"status": "pass", "transport": "encrypted", "storage": "encrypted"},
        "durableParticipantConvergence": {"gameplay": {"disposition": "converged"}},
        "externalEffectReconciliation": {"mail": {"disposition": "invalidated"}},
        "sessionRecovery": {"gameSessionHandling": "invalidated", "authSessionHandling": "invalidated"},
        "jwtHardening": {
            "rotationJobRef": "jobs/jwt-rotation",
            "resultingKeyIds": ["kid-1"],
            "revocationWatermarkEvidence": "evidence/jwt-revocation",
            "validatorConvergenceEvidence": "evidence/jwt-validators",
        },
        "databaseCredentialRotation": {
            "rotationJobRef": "jobs/postgres-rotation",
            "affectedSecretRefs": ["secret://firemud/postgres"],
            "rolloutRestartEvidence": "evidence/postgres-rollout",
        },
        "certificateReissuance": {
            "workloadLeafEvidence": "evidence/workload-certificates",
            "bridgeLeafEvidence": "evidence/bridge-certificates",
            "operatorLeafEvidence": "evidence/operator-certificates",
            "peerConvergenceEvidence": "evidence/certificate-peers",
        },
        "externalCredentialValidation": {"records": credential_records},
        "secretComplianceRefresh": {
            "recordRef": "design/operations/secret-compliance/production.yaml",
            "evidenceRef": "evidence/secret-compliance",
            "credentialClasses": ["jwt-signing-keys-jwks", "postgres-application-credentials"],
            "freshness": "lastRotationAt",
        },
        "smokeStatus": "pass",
        "smokeEvidence": ["evidence/smoke"],
        "reopenApprovedBy": "preflight-contract",
    }

def compatibility_result(status):
    return {
        "baselineRecoveryRecordRef": "design/operations/deployments/production/recovery/baseline.json",
        "baselineRecoveryContractFingerprint": "sha256:recovery-contract",
        "candidateRecoveryContractFingerprint": "sha256:recovery-contract",
        "changedDimensions": [],
        "compatibilityStatus": status,
        "compatibilityRationale": "contract test",
        "evaluatedAt": past_timestamp,
        "evaluatorToolDigest": "sha256:evaluator",
        "newDrillRequired": False,
    }

stub_baseline = {
    "environment": "production",
    "recoveryStatus": "finalized",
    "recoveryPurpose": "production-equivalent-drill",
    "trafficExposure": "isolated-drill",
    "coordinationRecoveryMode": "cold_start_restore",
    "recoveryContractFingerprint": "sha256:recovery-contract",
    "finalizedAt": timestamp(now - module.dt.timedelta(minutes=10)),
}
stub_path = recovery_dir / "stub-baseline.json"
stub_path.write_text(json.dumps(stub_baseline), encoding="utf-8")
stub_status, stub_message = module.validate_recovery_baseline(
    tmp,
    str(stub_path.relative_to(tmp)),
    "sha256:recovery-contract",
    now,
    now,
)
if stub_status != "fail" or "canonical finalized projection fields" not in stub_message:
    raise SystemExit(f"seven-field recovery baseline was accepted: {stub_message}")

valid_baseline = canonical_recovery_record(now - module.dt.timedelta(minutes=10))
baseline_path = recovery_dir / "baseline.json"
baseline_path.write_text(json.dumps(valid_baseline), encoding="utf-8")
baseline_status, baseline_message = module.validate_recovery_baseline(
    tmp,
    str(baseline_path.relative_to(tmp)),
    "sha256:recovery-contract",
    now,
    now,
)
if baseline_status != "pass":
    raise SystemExit(f"valid recovery baseline did not pass: {baseline_message}")

wide_restore_high_water = {"stream": "erasures", "sequence": 40}
wide_overlay = copy.deepcopy(valid_baseline["erasureOverlayReconciliation"])
wide_overlay["restoreHighWater"] = wide_restore_high_water
wide_display_limit = module.MISSING_SEQUENCE_DISPLAY_LIMIT
wide_overlay_status, wide_overlay_message = module.validate_erasure_overlay_reconciliation(
    wide_overlay,
    valid_baseline["artifactErasureHighWater"],
    valid_baseline["initialCatchupHighWater"],
    wide_restore_high_water,
    "erasures",
)
if (
    wide_overlay_status != "fail"
    or "missingCount=28" not in wide_overlay_message
    or f"missing={list(range(13, 13 + wide_display_limit))}" not in wide_overlay_message
    or f"omittedCount={28 - wide_display_limit}" not in wide_overlay_message
):
    raise SystemExit(
        "wide missing sequence interval did not report the true count with a truncated display: "
        + wide_overlay_message
    )

unordered_overlay = copy.deepcopy(valid_baseline["erasureOverlayReconciliation"])
unordered_artifact_high_water = {"stream": "erasures", "sequence": 12}
unordered_initial_catchup_high_water = {"stream": "erasures", "sequence": 10}
unordered_restore_high_water = {"stream": "erasures", "sequence": 12}
unordered_overlay["artifactErasureHighWater"] = unordered_artifact_high_water
unordered_overlay["initialCatchupHighWater"] = unordered_initial_catchup_high_water
unordered_overlay["restoreHighWater"] = unordered_restore_high_water
unordered_status, unordered_message = module.validate_erasure_overlay_reconciliation(
    unordered_overlay,
    unordered_artifact_high_water,
    unordered_initial_catchup_high_water,
    unordered_restore_high_water,
    "erasures",
)
if (
    unordered_status != "fail"
    or unordered_message
    != (
        "Recovery compatibility baseline erasureOverlayReconciliation "
        "canonical erasure high-water sequences must be ordered"
    )
):
    raise SystemExit(f"unordered canonical overlay boundaries did not fail at the ordering guard: {unordered_message}")

for boundary_sequence in ("10", True):
    direct_boundary_overlay = copy.deepcopy(valid_baseline["erasureOverlayReconciliation"])
    direct_boundary = {"stream": "erasures", "sequence": boundary_sequence}
    direct_boundary_overlay["artifactErasureHighWater"] = direct_boundary
    direct_boundary_status, direct_boundary_message = module.validate_erasure_overlay_reconciliation(
        direct_boundary_overlay,
        direct_boundary,
        valid_baseline["initialCatchupHighWater"],
        valid_baseline["restoreHighWater"],
        "erasures",
    )
    if (
        direct_boundary_status != "fail"
        or "canonical artifactErasureHighWater.sequence must be an integer" not in direct_boundary_message
    ):
        raise SystemExit(
            "direct overlay canonical boundary type guard did not reject the invalid sequence: "
            + direct_boundary_message
        )

pre_snapshot_after_artifact = copy.deepcopy(valid_baseline)
pre_snapshot_after_artifact["backupArtifactLineage"]["preSnapshotJournalHighWater"] = {
    **valid_baseline["backupArtifactLineage"]["preSnapshotJournalHighWater"],
    "stream": "erasures",
    "sequence": 12,
}
pre_snapshot_after_artifact_path = recovery_dir / "pre-snapshot-after-artifact-baseline.json"
pre_snapshot_after_artifact_path.write_text(json.dumps(pre_snapshot_after_artifact), encoding="utf-8")
pre_snapshot_after_artifact_status, pre_snapshot_after_artifact_message = module.validate_recovery_baseline(
    tmp,
    str(pre_snapshot_after_artifact_path.relative_to(tmp)),
    "sha256:recovery-contract",
    now,
    now,
)
if pre_snapshot_after_artifact_status != "pass":
    raise SystemExit(
        "preSnapshotJournalHighWater above artifact high-water did not pass: "
        + pre_snapshot_after_artifact_message
    )

intervening_coverage_entry = {
    "sequence": 10,
    "snapshotVisibleLedger": {
        "identity": "erasure-10",
        "digest": "sha256:erasure-10",
    },
    "externalJournal": {
        "identity": "erasure-10",
        "digest": "sha256:erasure-10",
    },
}
pre_snapshot_before_artifact = copy.deepcopy(valid_baseline)
pre_snapshot_before_artifact["backupArtifactLineage"]["preSnapshotJournalHighWater"] = {
    **valid_baseline["backupArtifactLineage"]["preSnapshotJournalHighWater"],
    "stream": "erasures",
    "sequence": 9,
}
pre_snapshot_before_artifact["backupArtifactLineage"]["interveningErasureCoverageProof"] = {
    "stream": "erasures",
    "exclusiveStart": 9,
    "inclusiveEnd": 10,
    "snapshotLedgerEvidenceRef": "evidence/snapshot-ledger-9-10.json",
    "externalJournalEvidenceRef": "evidence/external-journal-9-10.json",
    "entries": [intervening_coverage_entry],
}
pre_snapshot_before_artifact_path = recovery_dir / "pre-snapshot-before-artifact-baseline.json"
pre_snapshot_before_artifact_path.write_text(json.dumps(pre_snapshot_before_artifact), encoding="utf-8")
pre_snapshot_before_artifact_status, pre_snapshot_before_artifact_message = module.validate_recovery_baseline(
    tmp,
    str(pre_snapshot_before_artifact_path.relative_to(tmp)),
    "sha256:recovery-contract",
    now,
    now,
)
if pre_snapshot_before_artifact_status != "pass":
    raise SystemExit(
        "complete intervening erasure coverage proof did not pass: "
        + pre_snapshot_before_artifact_message
    )

invalid_intervening_proof_shapes = [
    ("non-list-entries", "entries", "not-a-list", ".entries must be an ordered list"),
    ("empty-entries", "entries", [], ".entries must cover every sequence in order exactly once"),
    (
        "blank-snapshot-ledger-evidence-ref",
        "snapshotLedgerEvidenceRef",
        "   ",
        ".snapshotLedgerEvidenceRef must be a non-empty immutable evidence reference",
    ),
    (
        "blank-external-journal-evidence-ref",
        "externalJournalEvidenceRef",
        "   ",
        ".externalJournalEvidenceRef must be a non-empty immutable evidence reference",
    ),
]
for fixture_name, field, value, expected_message in invalid_intervening_proof_shapes:
    invalid_proof = copy.deepcopy(pre_snapshot_before_artifact)
    invalid_proof["backupArtifactLineage"]["interveningErasureCoverageProof"][field] = value
    invalid_proof_path = recovery_dir / f"{fixture_name}-baseline.json"
    invalid_proof_path.write_text(json.dumps(invalid_proof), encoding="utf-8")
    invalid_proof_status, invalid_proof_message = module.validate_recovery_baseline(
        tmp,
        str(invalid_proof_path.relative_to(tmp)),
        "sha256:recovery-contract",
        now,
        now,
    )
    if invalid_proof_status != "fail" or expected_message not in invalid_proof_message:
        raise SystemExit(
            f"invalid intervening erasure coverage proof {fixture_name} passed: "
            + invalid_proof_message
        )

missing_intervening_proof = copy.deepcopy(pre_snapshot_before_artifact)
del missing_intervening_proof["backupArtifactLineage"]["interveningErasureCoverageProof"]
missing_intervening_proof_path = recovery_dir / "missing-intervening-proof-baseline.json"
missing_intervening_proof_path.write_text(json.dumps(missing_intervening_proof), encoding="utf-8")
missing_intervening_proof_status, missing_intervening_proof_message = module.validate_recovery_baseline(
    tmp,
    str(missing_intervening_proof_path.relative_to(tmp)),
    "sha256:recovery-contract",
    now,
    now,
)
if missing_intervening_proof_status != "fail" or "interveningErasureCoverageProof" not in (
    missing_intervening_proof_message
):
    raise SystemExit(
        "lower pre-snapshot high-water passed without intervening coverage proof: "
        + missing_intervening_proof_message
    )

mismatched_intervening_proof = copy.deepcopy(pre_snapshot_before_artifact)
mismatched_intervening_proof["backupArtifactLineage"]["interveningErasureCoverageProof"]["entries"][0][
    "externalJournal"
]["digest"] = "sha256:mismatch"
mismatched_intervening_proof_path = recovery_dir / "mismatched-intervening-proof-baseline.json"
mismatched_intervening_proof_path.write_text(json.dumps(mismatched_intervening_proof), encoding="utf-8")
mismatched_intervening_proof_status, mismatched_intervening_proof_message = module.validate_recovery_baseline(
    tmp,
    str(mismatched_intervening_proof_path.relative_to(tmp)),
    "sha256:recovery-contract",
    now,
    now,
)
if mismatched_intervening_proof_status != "fail" or "matching identity and digest" not in (
    mismatched_intervening_proof_message
):
    raise SystemExit(
        "mismatched intervening erasure identity/digest proof passed: "
        + mismatched_intervening_proof_message
    )

unneeded_intervening_proof = copy.deepcopy(valid_baseline)
unneeded_intervening_proof["backupArtifactLineage"]["interveningErasureCoverageProof"] = (
    pre_snapshot_before_artifact["backupArtifactLineage"]["interveningErasureCoverageProof"]
)
unneeded_intervening_proof_path = recovery_dir / "unneeded-intervening-proof-baseline.json"
unneeded_intervening_proof_path.write_text(json.dumps(unneeded_intervening_proof), encoding="utf-8")
unneeded_intervening_proof_status, unneeded_intervening_proof_message = module.validate_recovery_baseline(
    tmp,
    str(unneeded_intervening_proof_path.relative_to(tmp)),
    "sha256:recovery-contract",
    now,
    now,
)
if unneeded_intervening_proof_status != "fail" or "must be absent" not in unneeded_intervening_proof_message:
    raise SystemExit(
        "unneeded intervening erasure coverage proof passed: "
        + unneeded_intervening_proof_message
    )

rollback_compatibility_status, rollback_compatibility_message = module.recovery_compatibility_check(
    {
        "generatedAt": past_timestamp,
        "recoveryCompatibility": compatibility_result("compatible"),
    },
    "rollback-compatible",
    tmp,
    now,
)
if rollback_compatibility_status != "pass":
    raise SystemExit(
        "rollback-compatible unchanged recovery compatibility did not pass: "
        + rollback_compatibility_message
    )

invalid_baseline_cases = {
    "controller": {"recoveryControllerLineage": {"recoveryStatus": "collecting", "scope": "environment-wide"}},
    "erasure": {"erasureReplay": {**valid_baseline["erasureReplay"], "gapFree": False}},
    "coordination": {
        "coordinationRecoveryEvidence": {
            **valid_baseline["coordinationRecoveryEvidence"],
            "coordinationRedis": "non-empty",
        }
    },
    "confidentiality": {
        "backupConfidentialityEvidence": {
            **valid_baseline["backupConfidentialityEvidence"],
            "status": "fail",
        }
    },
    "participant": {"durableParticipantConvergence": {"gameplay": {"disposition": "unknown"}}},
    "coordination-account-projections": {
        "coordinationRecoveryEvidence": {
            **valid_baseline["coordinationRecoveryEvidence"],
            "accountAuthorityProjections": "missing",
        }
    },
    "coordination-replay-evidence": {
        "coordinationRecoveryEvidence": {
            **valid_baseline["coordinationRecoveryEvidence"],
            "replayConsumeEvidenceRef": "",
        }
    },
    "canonical-high-water-non-int-sequence": {
        "artifactErasureHighWater": {"stream": "erasures", "sequence": "10"},
        "backupArtifactLineage": {
            **valid_baseline["backupArtifactLineage"],
            "artifactErasureHighWater": {"stream": "erasures", "sequence": "10"},
        },
    },
    "overlay-restore-boundary-equality-mismatch": {
        "erasureOverlayReconciliation": {
            **valid_baseline["erasureOverlayReconciliation"],
            "restoreHighWater": {"stream": "erasures", "sequence": 11},
        }
    },
    "overlay-artifact-boundary-equality-mismatch": {
        "erasureOverlayReconciliation": {
            **valid_baseline["erasureOverlayReconciliation"],
            "artifactErasureHighWater": {"stream": "erasures", "sequence": 9},
        }
    },
    "overlay-initial-catchup-boundary-equality-mismatch": {
        "erasureOverlayReconciliation": {
            **valid_baseline["erasureOverlayReconciliation"],
            "initialCatchupHighWater": {"stream": "erasures", "sequence": 10},
        }
    },
    "lineage-pre-snapshot": {
        "backupArtifactLineage": {
            **valid_baseline["backupArtifactLineage"],
            "preSnapshotJournalHighWater": None,
        }
    },
    "lineage-artifact-high-water-mismatch": {
        "backupArtifactLineage": {
            **valid_baseline["backupArtifactLineage"],
            "artifactErasureHighWater": {"stream": "erasures", "sequence": 9},
        }
    },
    "lineage-snapshot-bound": {
        "backupArtifactLineage": {
            **valid_baseline["backupArtifactLineage"],
            "erasureHighWaterSnapshotBound": False,
        }
    },
    "lineage-pre-snapshot-witness-missing": {
        "backupArtifactLineage": {
            key: value
            for key, value in valid_baseline["backupArtifactLineage"].items()
            if key != "preSnapshotJournalBoundaryWitness"
        }
    },
    "lineage-pre-snapshot-observation-id-mismatch": {
        "backupArtifactLineage": {
            **valid_baseline["backupArtifactLineage"],
            "preSnapshotJournalBoundaryWitness": {
                **valid_baseline["backupArtifactLineage"]["preSnapshotJournalBoundaryWitness"],
                "observationId": "observation-other",
            },
        }
    },
    "lineage-pre-snapshot-observation-digest-mismatch": {
        "backupArtifactLineage": {
            **valid_baseline["backupArtifactLineage"],
            "preSnapshotJournalBoundaryWitness": {
                **valid_baseline["backupArtifactLineage"]["preSnapshotJournalBoundaryWitness"],
                "observationDigest": "sha256:observation-other",
            },
        }
    },
    "lineage-pre-snapshot-identity-mismatch": {
        "backupArtifactLineage": {
            **valid_baseline["backupArtifactLineage"],
            "preSnapshotJournalBoundaryWitness": {
                **valid_baseline["backupArtifactLineage"]["preSnapshotJournalBoundaryWitness"],
                "snapshotIdentity": "snapshot-other",
            },
        }
    },
    "lineage-pre-snapshot-opening-mismatch": {
        "backupArtifactLineage": {
            **valid_baseline["backupArtifactLineage"],
            "preSnapshotJournalBoundaryWitness": {
                **valid_baseline["backupArtifactLineage"]["preSnapshotJournalBoundaryWitness"],
                "snapshotOpenedAt": timestamp(
                    now - module.dt.timedelta(minutes=29)
                ),
            },
        }
    },
    "lineage-pre-snapshot-observation-not-before-opening": {
        "backupArtifactLineage": {
            **valid_baseline["backupArtifactLineage"],
            "preSnapshotJournalHighWater": {
                **valid_baseline["backupArtifactLineage"]["preSnapshotJournalHighWater"],
                "observedAt": valid_baseline["backupArtifactLineage"]["snapshotAt"],
            },
        }
    },
    "lineage-pre-snapshot-stream": {
        "backupArtifactLineage": {
            **valid_baseline["backupArtifactLineage"],
            "preSnapshotJournalHighWater": {
                **valid_baseline["backupArtifactLineage"]["preSnapshotJournalHighWater"],
                "stream": "other-stream",
            },
        }
    },
    "lineage-pre-snapshot-sequence": {
        "backupArtifactLineage": {
            **valid_baseline["backupArtifactLineage"],
            "preSnapshotJournalHighWater": {
                **valid_baseline["backupArtifactLineage"]["preSnapshotJournalHighWater"],
                "sequence": "10",
            },
        }
    },
    "lineage-pre-snapshot-below-artifact-high-water": {
        "backupArtifactLineage": {
            **valid_baseline["backupArtifactLineage"],
            "preSnapshotJournalHighWater": {
                **valid_baseline["backupArtifactLineage"]["preSnapshotJournalHighWater"],
                "sequence": 9,
            },
        }
    },
    "lineage-pre-snapshot-above-restore-high-water": {
        "backupArtifactLineage": {
            **valid_baseline["backupArtifactLineage"],
            "preSnapshotJournalHighWater": {
                **valid_baseline["backupArtifactLineage"]["preSnapshotJournalHighWater"],
                "sequence": 13,
            },
        }
    },
    "overlay-stream": {
        "erasureOverlayReconciliation": {
            **valid_baseline["erasureOverlayReconciliation"],
            "stream": "other-stream",
        }
    },
    "overlay-entry-stream": {
        "erasureOverlayReconciliation": {
            **valid_baseline["erasureOverlayReconciliation"],
            "sequenceDispositions": [
                {
                    **valid_baseline["erasureOverlayReconciliation"]["sequenceDispositions"][0],
                    "stream": "other-stream",
                }
            ],
        }
    },
    "overlay-integrity": {
        "erasureOverlayReconciliation": {
            **valid_baseline["erasureOverlayReconciliation"],
            "integrityVerification": {"status": "pass", "verified": False},
        }
    },
    "overlay-entry-integrity": {
        "erasureOverlayReconciliation": {
            **valid_baseline["erasureOverlayReconciliation"],
            "sequenceDispositions": [
                {
                    **valid_baseline["erasureOverlayReconciliation"]["sequenceDispositions"][0],
                    "integrityVerified": False,
                }
            ],
        }
    },
    "overlay-duplicate": {
        "erasureOverlayReconciliation": {
            **valid_baseline["erasureOverlayReconciliation"],
            "sequenceDispositions": [
                valid_baseline["erasureOverlayReconciliation"]["sequenceDispositions"][0],
                valid_baseline["erasureOverlayReconciliation"]["sequenceDispositions"][0],
            ],
        }
    },
    "overlay-missing-entry": {
        "erasureOverlayReconciliation": {
            **valid_baseline["erasureOverlayReconciliation"],
            "sequenceDispositions": [],
        }
    },
    "overlay-dispositions-type": {
        "erasureOverlayReconciliation": {
            **valid_baseline["erasureOverlayReconciliation"],
            "sequenceDispositions": {},
        }
    },
    "overlay-gap": {
        "erasureOverlayReconciliation": {
            **valid_baseline["erasureOverlayReconciliation"],
            "sequenceVerification": {
                **valid_baseline["erasureOverlayReconciliation"]["sequenceVerification"],
                "gapFree": False,
            },
        }
    },
    "overlay-sequence-verification-absent": {
        "erasureOverlayReconciliation": {
            key: value
            for key, value in valid_baseline["erasureOverlayReconciliation"].items()
            if key != "sequenceVerification"
        }
    },
    "overlay-sequence-verification-type": {
        "erasureOverlayReconciliation": {
            **valid_baseline["erasureOverlayReconciliation"],
            "sequenceVerification": [],
        }
    },
    "overlay-sequence-verification-status": {
        "erasureOverlayReconciliation": {
            **valid_baseline["erasureOverlayReconciliation"],
            "sequenceVerification": {
                **valid_baseline["erasureOverlayReconciliation"]["sequenceVerification"],
                "status": "fail",
            },
        }
    },
    "overlay-sequence-verification-unordered": {
        "erasureOverlayReconciliation": {
            **valid_baseline["erasureOverlayReconciliation"],
            "sequenceVerification": {
                **valid_baseline["erasureOverlayReconciliation"]["sequenceVerification"],
                "ordered": False,
            },
        }
    },
    "overlay-out-of-range": {
        "erasureOverlayReconciliation": {
            **valid_baseline["erasureOverlayReconciliation"],
            "sequenceDispositions": [
                {
                    **valid_baseline["erasureOverlayReconciliation"]["sequenceDispositions"][0],
                    "sequence": 11,
                }
            ],
        }
    },
    "overlay-owner-missing": {
        "erasureOverlayReconciliation": {
            **valid_baseline["erasureOverlayReconciliation"],
            "sequenceDispositions": [
                {
                    key: value
                    for key, value in valid_baseline["erasureOverlayReconciliation"]["sequenceDispositions"][0].items()
                    if key != "owner"
                }
            ],
        }
    },
    "overlay-owner-blank": {
        "erasureOverlayReconciliation": {
            **valid_baseline["erasureOverlayReconciliation"],
            "sequenceDispositions": [
                {
                    **valid_baseline["erasureOverlayReconciliation"]["sequenceDispositions"][0],
                    "owner": "   ",
                }
            ],
        }
    },
    "overlay-disposition": {
        "erasureOverlayReconciliation": {
            **valid_baseline["erasureOverlayReconciliation"],
            "sequenceDispositions": [
                {
                    **valid_baseline["erasureOverlayReconciliation"]["sequenceDispositions"][0],
                    "disposition": "unknown",
                }
            ],
        }
    },
    "overlay-type": {
        "erasureOverlayReconciliation": ["not-an-object"],
    },
    "overlay-entry-type": {
        "erasureOverlayReconciliation": {
            **valid_baseline["erasureOverlayReconciliation"],
            "sequenceDispositions": ["not-an-object"],
        }
    },
    "overlay-entry-bool-sequence": {
        "erasureOverlayReconciliation": {
            **valid_baseline["erasureOverlayReconciliation"],
            "sequenceDispositions": [
                {
                    **valid_baseline["erasureOverlayReconciliation"]["sequenceDispositions"][0],
                    "sequence": True,
                }
            ],
        }
    },
    "overlay-entry-non-int-sequence": {
        "erasureOverlayReconciliation": {
            **valid_baseline["erasureOverlayReconciliation"],
            "sequenceDispositions": [
                {
                    **valid_baseline["erasureOverlayReconciliation"]["sequenceDispositions"][0],
                    "sequence": "12",
                }
            ],
        }
    },
    "overlay-object-artifact-boundary-bool-sequence": {
        "erasureOverlayReconciliation": {
            **valid_baseline["erasureOverlayReconciliation"],
            "artifactErasureHighWater": {"stream": "erasures", "sequence": True},
        }
    },
    "overlay-object-restore-boundary-non-int-sequence": {
        "erasureOverlayReconciliation": {
            **valid_baseline["erasureOverlayReconciliation"],
            "restoreHighWater": {"stream": "erasures", "sequence": "12"},
        }
    },
    "overlay-verification-bounds": {
        "erasureOverlayReconciliation": {
            **valid_baseline["erasureOverlayReconciliation"],
            "sequenceVerification": {
                **valid_baseline["erasureOverlayReconciliation"]["sequenceVerification"],
                "inclusiveEnd": 12,
            },
        }
    },
    "overlay-sequence-verification-bool-endpoint": {
        "artifactErasureHighWater": {"stream": "erasures", "sequence": 1},
        "initialCatchupHighWater": {"stream": "erasures", "sequence": 1},
        "erasureReplay": {
            **valid_baseline["erasureReplay"],
            "exclusiveStart": 1,
            "initialCatchupThrough": 1,
        },
        "erasureOverlayReconciliation": {
            **valid_baseline["erasureOverlayReconciliation"],
            "artifactErasureHighWater": {"stream": "erasures", "sequence": 1},
            "initialCatchupHighWater": {"stream": "erasures", "sequence": 1},
            "sequenceVerification": {
                **valid_baseline["erasureOverlayReconciliation"]["sequenceVerification"],
                "exclusiveStart": True,
                "inclusiveEnd": True,
            },
            "sequenceDispositions": [
                {
                    **valid_baseline["erasureOverlayReconciliation"]["sequenceDispositions"][0],
                    "sequence": sequence,
                }
                for sequence in range(2, 13)
            ],
        },
        "backupArtifactLineage": {
            **valid_baseline["backupArtifactLineage"],
            "preSnapshotJournalHighWater": {"stream": "erasures", "sequence": 1},
            "artifactErasureHighWater": {"stream": "erasures", "sequence": 1},
        },
    },
    "overlay-integrity-failed": {
        "erasureOverlayReconciliation": {
            **valid_baseline["erasureOverlayReconciliation"],
            "integrityVerification": {"status": "fail", "verified": False},
        }
    },
    "overlay-artifact-boundary-equality-mismatch-at-restore-boundary": {
        "erasureOverlayReconciliation": {
            **valid_baseline["erasureOverlayReconciliation"],
            "artifactErasureHighWater": {"stream": "erasures", "sequence": 12},
            "initialCatchupHighWater": {"stream": "erasures", "sequence": 11},
            "restoreHighWater": {"stream": "erasures", "sequence": 12},
        }
    },
    "retained-backlog": {
        "durableParticipantConvergence": {
            "gameplay": {"disposition": "fenced_disabled_backlog_retained"},
        }
    },
}
expected_invalid_baseline_messages = {
    "controller": "controller lineage must be finalized",
    "erasure": "erasure replay must be gap-free through restoreHighWater",
    "coordination": "coordination recovery must prove",
    "confidentiality": "backup confidentiality evidence must pass",
    "participant": "unsafe or missing disposition: gameplay",
    "coordination-account-projections": "coordination recovery must prove",
    "coordination-replay-evidence": "coordination recovery must prove",
    "canonical-high-water-non-int-sequence": "erasure high-water sequences must be ordered non-boolean integers",
    "overlay-restore-boundary-equality-mismatch": "restoreHighWater must match the canonical bound exactly",
    "overlay-artifact-boundary-equality-mismatch": "artifactErasureHighWater must match the canonical bound exactly",
    "overlay-initial-catchup-boundary-equality-mismatch": "initialCatchupHighWater must match the canonical bound exactly",
    "lineage-pre-snapshot": "artifact lineage must include a valid",
    "lineage-artifact-high-water-mismatch": "artifactErasureHighWater must match the snapshot-bound",
    "lineage-snapshot-bound": "erasureHighWaterSnapshotBound must be true",
    "lineage-pre-snapshot-stream": "preSnapshotJournalHighWater.stream must match",
    "lineage-pre-snapshot-sequence": "preSnapshotJournalHighWater.sequence must be an integer",
    "lineage-pre-snapshot-below-artifact-high-water": "interveningErasureCoverageProof must be an object",
    "lineage-pre-snapshot-above-restore-high-water": "preSnapshotJournalHighWater.sequence must be at or below",
    "lineage-pre-snapshot-witness-missing": "preSnapshotJournalBoundaryWitness must be an object",
    "lineage-pre-snapshot-observation-id-mismatch": "observationId must match preSnapshotJournalHighWater.observationId",
    "lineage-pre-snapshot-observation-digest-mismatch": "observationDigest must match preSnapshotJournalHighWater.observationDigest",
    "lineage-pre-snapshot-identity-mismatch": "snapshotIdentity must match snapshotIdentity",
    "lineage-pre-snapshot-opening-mismatch": "snapshotOpenedAt must exactly equal snapshotAt",
    "lineage-pre-snapshot-observation-not-before-opening": "observedAt must strictly precede snapshot opening",
    "overlay-stream": "stream must match the canonical erasure stream",
    "overlay-entry-stream": "sequenceDispositions[0] stream must match the canonical erasure stream",
    "overlay-integrity": "integrityVerification must be verified with status pass",
    "overlay-entry-integrity": "sequenceDispositions[0] integrity must be verified",
    "overlay-duplicate": "contains duplicate sequence 12",
    "overlay-missing-entry": "missingCount=1, missing=[12]",
    "overlay-dispositions-type": "sequenceDispositions must be a list",
    "overlay-gap": "sequenceVerification must prove the canonical bounds and ordered, contiguous, complete, gap-free, duplicate-free initial catch-up interval",
    "overlay-out-of-range": "sequenceDispositions[0] sequence is outside the final interval",
    "overlay-owner-missing": "sequenceDispositions[0] owner must be non-empty",
    "overlay-owner-blank": "sequenceDispositions[0] owner must be non-empty",
    "overlay-disposition": "has an invalid canonical disposition",
    "overlay-type": "erasureOverlayReconciliation must be an object",
    "overlay-entry-type": "sequenceDispositions[0] must be an object",
    "overlay-entry-bool-sequence": "sequenceDispositions[0] sequence must be an integer",
    "overlay-entry-non-int-sequence": "sequenceDispositions[0] sequence must be an integer",
    "overlay-object-artifact-boundary-bool-sequence": "artifactErasureHighWater must match the canonical bound exactly",
    "overlay-object-restore-boundary-non-int-sequence": "restoreHighWater must match the canonical bound exactly",
    "overlay-verification-bounds": "sequenceVerification must prove the canonical bounds",
    "overlay-sequence-verification-absent": "sequenceVerification must prove the canonical bounds",
    "overlay-sequence-verification-type": "sequenceVerification must prove the canonical bounds",
    "overlay-sequence-verification-status": "sequenceVerification must prove the canonical bounds",
    "overlay-sequence-verification-unordered": "sequenceVerification must prove the canonical bounds",
    "overlay-sequence-verification-bool-endpoint": "sequenceVerification must prove the canonical bounds",
    "overlay-integrity-failed": "integrityVerification must be verified with status pass",
    "overlay-artifact-boundary-equality-mismatch-at-restore-boundary": "artifactErasureHighWater must match the canonical bound exactly",
    "retained-backlog": "unsafe or missing disposition: gameplay",
}
if set(invalid_baseline_cases) != set(expected_invalid_baseline_messages):
    raise SystemExit(
        "invalid recovery baseline cases and expected messages must have the same exact key set: "
        f"cases={sorted(invalid_baseline_cases)}, messages={sorted(expected_invalid_baseline_messages)}"
    )
for case_name, replacement in invalid_baseline_cases.items():
    invalid_baseline = copy.deepcopy(valid_baseline)
    invalid_baseline.update(copy.deepcopy(replacement))
    invalid_path = recovery_dir / f"invalid-{case_name}-baseline.json"
    invalid_path.write_text(json.dumps(invalid_baseline), encoding="utf-8")
    invalid_status, invalid_message = module.validate_recovery_baseline(
        tmp,
        str(invalid_path.relative_to(tmp)),
        "sha256:recovery-contract",
        now,
        now,
    )
    expected_message = expected_invalid_baseline_messages[case_name]
    if invalid_status != "fail" or expected_message not in invalid_message:
        raise SystemExit(
            f"invalid {case_name} recovery baseline failed for the wrong reason: {invalid_message}"
        )

stale_baseline = {
    **canonical_recovery_record(now - module.dt.timedelta(days=31)),
}
stale_baseline_path = recovery_dir / "stale-baseline.json"
stale_baseline_path.write_text(json.dumps(stale_baseline), encoding="utf-8")
stale_status, stale_message = module.validate_recovery_baseline(
    tmp,
    str(stale_baseline_path.relative_to(tmp)),
    "sha256:recovery-contract",
    now,
    now,
)
if stale_status != "fail" or "older than 30 days" not in stale_message:
    raise SystemExit(f"stale recovery baseline did not fail closed: {stale_message}")

missing_baseline_status, missing_baseline_message = module.validate_recovery_baseline(
    tmp,
    "design/operations/deployments/production/recovery/missing-baseline.json",
    "sha256:recovery-contract",
    now,
    now,
)
if missing_baseline_status != "fail" or "not found" not in missing_baseline_message:
    raise SystemExit(f"missing recovery baseline did not fail closed: {missing_baseline_message}")

outside_baseline_path = write_json("outside-baseline.json", valid_baseline)
outside_status, outside_message = module.validate_recovery_baseline(
    tmp,
    outside_baseline_path.name,
    "sha256:recovery-contract",
    now,
    now,
)
if outside_status != "fail" or "production/recovery" not in outside_message:
    raise SystemExit(f"out-of-namespace recovery baseline did not fail closed: {outside_message}")

missing_compatibility_attestation = write_json(
    "missing-recovery-compatibility-attestation.json",
    {
        "attestationVersion": "v1",
        "environment": "staging",
        "generatedAt": past_timestamp,
        "stagingOverlayCommitSha": "deadbeef",
        "stagingDeploymentEventId": "55555555-5555-4555-8555-555555555555",
        "productionOverlayRef": "contract-production",
        "serviceDigests": {"account-service": "ghcr.io/firemud/account-service@sha256:candidate"},
        "smokeEvidence": ["contract-smoke"],
        "approvedBy": "preflight-contract",
        "rollbackMode": "rollback-compatible",
    },
)
missing_status, _, missing_message, missing_recovery_status, missing_recovery_message = module.promotion_check(
    missing_compatibility_attestation,
    [],
    tmp,
)
if (
    missing_status != "fail"
    or "canonical fields" not in missing_message
    or "recoveryCompatibility" not in missing_message
    or missing_recovery_status != "fail"
    or "recoveryCompatibility" not in missing_recovery_message
):
    raise SystemExit(f"missing recoveryCompatibility did not fail closed: {missing_message}")

def promotion_attestation(compatibility, rollback_mode="rollback-compatible"):
    return {
        "attestationVersion": "v1",
        "environment": "staging",
        "stagingOverlayCommitSha": "deadbeef",
        "stagingDeploymentEventId": "55555555-5555-4555-8555-555555555555",
        "productionOverlayRef": "contract-production",
        "serviceDigests": {"account-service": "ghcr.io/firemud/account-service@sha256:candidate"},
        "smokeEvidence": ["contract-smoke"],
        "generatedAt": past_timestamp,
        "approvedBy": "preflight-contract",
        "rollbackMode": rollback_mode,
        "recoveryCompatibility": compatibility,
    }

for compatibility_status in ("drill_required", "incompatible"):
    status_result = compatibility_result(compatibility_status)
    if compatibility_status == "drill_required":
        status_result.update(
            {"newDrillRequired": True, "backupReadinessRef": "drill-required-readiness.json"}
        )
    status_attestation = write_json(
        f"{compatibility_status}-attestation.json",
        promotion_attestation(status_result),
    )
    promotion_status, _, promotion_message, _, _ = module.promotion_check(status_attestation, [], tmp)
    if promotion_status != "fail" or "compatibilityStatus blocks promotion" not in promotion_message:
        raise SystemExit(
            f"{compatibility_status} recoveryCompatibility did not fail closed: {promotion_message}"
        )

evaluated_after_generated = compatibility_result("compatible")
evaluated_after_generated["evaluatedAt"] = timestamp(now - module.dt.timedelta(minutes=1))
evaluated_after_generated_attestation = promotion_attestation(evaluated_after_generated)
evaluated_after_generated_attestation["generatedAt"] = timestamp(now - module.dt.timedelta(minutes=2))
evaluated_after_generated_path = write_json(
    "evaluated-after-generated-attestation.json",
    evaluated_after_generated_attestation,
)
evaluated_after_generated_status, _, evaluated_after_generated_message, _, _ = module.promotion_check(
    evaluated_after_generated_path, [], tmp
)
if (
    evaluated_after_generated_status != "fail"
    or "must not be after attestation generatedAt" not in evaluated_after_generated_message
):
    raise SystemExit(
        "recovery compatibility evaluated after attestation generation did not fail closed: "
        + evaluated_after_generated_message
    )

rollback_status, rollback_message = module.production_recovery_check(
    "pass", "rollback-compatible", "promotion valid", "", "contract-production", tmp
)
if rollback_status != "pass":
    raise SystemExit(f"valid rollback-compatible recovery gate did not pass: {rollback_message}")

rollback_failure_status, rollback_failure_message = module.production_recovery_check(
    "fail", "rollback-compatible", "baseline invalid", "", "contract-production", tmp
)
if rollback_failure_status != "fail" or "baseline invalid" not in rollback_failure_message:
    raise SystemExit(
        "failed rollback-compatible recovery validation did not fail PREFLIGHT-BACKUP-001: "
        + rollback_failure_message
    )

unknown_mode_status, unknown_mode_message = module.production_recovery_check(
    "fail", "unknown", "invalid attestation", "", "contract-production", tmp
)
if unknown_mode_status != "fail" or "rollbackMode is invalid" not in unknown_mode_message:
    raise SystemExit(f"invalid rollback mode did not fail recovery compatibility: {unknown_mode_message}")

roll_forward_status, roll_forward_message = module.production_recovery_check(
    "pass", "roll-forward-only", "promotion valid", "", "contract-production", tmp
)
if roll_forward_status != "fail" or "FIREMUD_BACKUP_READINESS_EVIDENCE" not in roll_forward_message:
    raise SystemExit(f"roll-forward promotion without readiness evidence did not fail: {roll_forward_message}")

gateway_image = "ghcr.io/benhook1013/spring-cloud-gateway@sha256:gateway"
account_image = "ghcr.io/benhook1013/account-service@sha256:account"
extracted_images = module.extract_service_images(
    "image: " + gateway_image + "\nimage: " + account_image + "\n"
)
if set(extracted_images) != {gateway_image, account_image}:
    raise SystemExit(f"production digest extraction omitted the Gateway image: {extracted_images}")

import subprocess

promotion_root = tmp / "promotion-git-root"
promotion_root.mkdir()
subprocess.run(["git", "-C", str(promotion_root), "init", "-q"], check=True)
subprocess.run(["git", "-C", str(promotion_root), "config", "user.name", "preflight-contract"], check=True)
subprocess.run(["git", "-C", str(promotion_root), "config", "user.email", "preflight-contract@example.test"], check=True)
(promotion_root / "marker").write_text("staging overlay\n", encoding="utf-8")
subprocess.run(["git", "-C", str(promotion_root), "add", "marker"], check=True)
subprocess.run(["git", "-C", str(promotion_root), "commit", "-qm", "staging overlay"], check=True)
staging_sha = subprocess.check_output(
    ["git", "-C", str(promotion_root), "rev-parse", "HEAD"], text=True
).strip()
if not module.git_commit_exists(promotion_root, staging_sha):
    raise SystemExit("valid stagingOverlayCommitSha was not proven to exist in Git")
if module.git_commit_exists(promotion_root, "deadbeef"):
    raise SystemExit("unknown stagingOverlayCommitSha was incorrectly accepted by Git validation")

staging_expected_bindings = (
    promotion_root / "design/operations/environments/staging/expected-bindings.yaml"
)
staging_expected_bindings.parent.mkdir(parents=True)
staging_expected_data = yaml.safe_load(
    (root / "design/operations/environments/staging/expected-bindings.yaml").read_text(
        encoding="utf-8"
    )
)
staging_expected_data["backupStorage"] = {
    "enabled": True,
    "bucket": "firemud-staging-backups",
    "endpoint": "https://minio.staging.internal",
    "bindingRef": "secret://firemud/staging-backup-object-store",
}
staging_expected_data["assetStorage"] = {
    "enabled": True,
    "bucket": "contract-staging-assets",
    "endpoint": "https://assets.staging.internal",
    "bindingRef": "secret://firemud/staging-asset-object-store",
}
staging_expected_bindings.write_text(
    yaml.safe_dump(staging_expected_data, sort_keys=False), encoding="utf-8"
)

for env in ("production", "hobby-self-hosted"):
    expected_path = promotion_root / "design/operations/environments" / env / "expected-bindings.yaml"
    expected_path.parent.mkdir(parents=True, exist_ok=True)
    expected_path.write_text(
        (root / f"design/operations/environments/{env}/expected-bindings.yaml").read_text(
            encoding="utf-8"
        ),
        encoding="utf-8",
    )
staging_validation_documents = copy.deepcopy(rendered_documents)
for document in staging_validation_documents:
    if document.get("kind") != "ServiceAccount":
        continue
    for image_pull_secret in document.get("imagePullSecrets") or []:
        if image_pull_secret.get("name") == "ghcr-pull-hobby":
            image_pull_secret["name"] = "ghcr-pull-staging"
staging_binding_results = module.expected_binding_checks(
    staging_expected_bindings,
    "design/operations/environments/staging/expected-bindings.yaml",
    "staging",
    staging_validation_documents,
)
staging_binding_failures = [
    result
    for result in staging_binding_results
    if result.required and result.status == "fail"
]
if staging_binding_failures:
    raise SystemExit(
        "staging promotion expected-bindings fixture failed validation: "
        + "; ".join(result.message for result in staging_binding_failures)
    )

secret_evidence_path = promotion_root / "secret-compliance.json"
def evidence_digest(record):
    return module.canonical_evidence_digest(record)


def make_secret_evidence():
    records = {}
    for class_name in (
        "jwt-signing-keys-jwks",
        "postgres-application-credentials",
        "backup-object-store-credentials",
        "asset-store-credentials",
        "operator-credentials",
    ):
        record = {
            "targetEnvironment": "staging",
            "credentialClass": class_name,
            "evidenceOperationId": f"rotation-staging-{class_name}",
        }
        record["immutableArtifactId"] = evidence_digest(record)
        records[class_name] = record
    return {"environment": "staging", "records": records}


def make_bootstrap_secret_evidence():
    bootstrap_operation_id = "bootstrap-staging-20260825"
    provisioning_generation = 7
    records = {}
    for class_name in (
        "jwt-signing-keys-jwks",
        "postgres-application-credentials",
        "backup-object-store-credentials",
        "asset-store-credentials",
        "operator-credentials",
    ):
        record = {
            "targetEnvironment": "staging",
            "credentialClass": class_name,
            "bootstrapOperationId": bootstrap_operation_id,
            "provisioningGeneration": provisioning_generation,
        }
        record["immutableArtifactId"] = evidence_digest(record)
        records[class_name] = record
    return {
        "environment": "staging",
        "bootstrapOperationId": bootstrap_operation_id,
        "provisioningGeneration": provisioning_generation,
        "records": records,
    }


secret_evidence_path.write_text(json.dumps(make_secret_evidence()), encoding="utf-8")
staging_event_id = "55555555-5555-4555-8555-555555555555"
jwt_custody_proof = {
    "proofId": "PREFLIGHT-JWT-INTERIM-001",
    "custodyMode": "INTERIM_ACCOUNT_ONLY_MOUNTED_FALLBACK",
    "contractVersion": 1,
}
rotation_evidence = {
    "policyId": "PREFLIGHT-JWT-ROTATION-001",
    "status": "pass",
    "deploymentEventId": staging_event_id,
    "jwtCustodyProof": jwt_custody_proof,
}
rotation_evidence_path = (
    promotion_root
    / "design/operations/deployments/staging/jwt-rotation"
    / f"{staging_event_id}.json"
)
rotation_evidence_path.parent.mkdir(parents=True, exist_ok=True)
rotation_evidence_path.write_text(json.dumps(rotation_evidence), encoding="utf-8")
rotation_evidence_ref = (
    str(rotation_evidence_path.relative_to(promotion_root))
    + "#"
    + module.canonical_evidence_digest(rotation_evidence)
)
staging_dir = (
    promotion_root
    / "design/operations/deployments/staging/deployments"
    / staging_sha
)
staging_dir.mkdir(parents=True)
staging_preflight_path = (
    promotion_root
    / "design/operations/deployments/staging/preflight"
    / staging_sha
    / f"{staging_event_id}.json"
)
staging_preflight_path.parent.mkdir(parents=True)
staging_requirements = module.expected_preflight_policy_requirements("staging", None)
staging_preflight_path.write_text(
    json.dumps(
        {
            "environment": "staging",
            "expectedBindingsRef": "design/operations/environments/staging/expected-bindings.yaml",
            "deploymentRef": {"overlayCommitSha": staging_sha},
            "deploymentEventId": staging_event_id,
            "trafficOpenEvent": None,
            "policyCatalogVersion": module.PREFLIGHT_POLICY_CATALOG_VERSION,
            "startedAt": past_timestamp,
            "completedAt": past_timestamp,
            "toolVersion": "preflight.py-v1",
            "context": "operator",
            "jwtCustodyProof": jwt_custody_proof,
            "checkResults": [
                {
                    "policyId": policy_id,
                    "category": module.PREFLIGHT_POLICY_CATALOG[policy_id],
                    "required": required,
                    "status": "pass" if required else "not_applicable",
                    "message": "contract evidence",
                }
                for policy_id, required in staging_requirements.items()
            ]
            + [
                {
                    "policyId": "PREFLIGHT-JWT-INTERIM-001",
                    "category": module.PREFLIGHT_POLICY_CATALOG["PREFLIGHT-JWT-INTERIM-001"],
                    "required": True,
                    "status": "pass",
                    "message": "contract evidence",
                },
                {
                    "policyId": "PREFLIGHT-JWT-ROTATION-001",
                    "category": module.PREFLIGHT_POLICY_CATALOG["PREFLIGHT-JWT-ROTATION-001"],
                    "required": True,
                    "status": "pass",
                    "message": "contract evidence",
                }
            ],
        }
    ),
    encoding="utf-8",
)
staging_record = {
    "environment": "staging",
    "overlayCommitSha": staging_sha,
    "deploymentEventId": staging_event_id,
    "appliedAt": past_timestamp,
    "appliedBy": "preflight-contract",
    "deployStatus": "pass",
    "smokeStatus": "pass",
    "serviceDigests": {"spring-cloud-gateway": gateway_image, "account-service": account_image},
    "jwtCustodyProof": jwt_custody_proof,
    "jwtRotationEvidenceRef": rotation_evidence_ref,
    "preflightReportPath": str(staging_preflight_path.relative_to(promotion_root)),
    "liveStateEvidence": {
        "status": "pass",
        "observedOverlaySha": staging_sha,
        "observedDigests": {"spring-cloud-gateway": gateway_image, "account-service": account_image},
    },
    "secretComplianceSnapshotAt": past_timestamp,
    "secretComplianceStatus": "pass",
    "secretComplianceEvidenceRef": secret_evidence_path.name,
    "smokeEvidence": ["contract-smoke"],
}
staging_record_path = staging_dir / f"{staging_event_id}.json"
staging_record_path.write_text(json.dumps(staging_record), encoding="utf-8")
promotion_recovery_dir = promotion_root / "design/operations/deployments/production/recovery"
promotion_recovery_dir.mkdir(parents=True)
(promotion_recovery_dir / "baseline.json").write_text(json.dumps(valid_baseline), encoding="utf-8")
promotion_attestation_path = promotion_root / "promotion-attestation.json"
promotion_attestation_path.write_text(
    json.dumps(
        {
            "attestationVersion": "v1",
            "environment": "staging",
            "stagingOverlayCommitSha": staging_sha,
            "stagingDeploymentEventId": staging_event_id,
            "jwtCustodyProof": jwt_custody_proof,
            "jwtRotationEvidenceRef": rotation_evidence_ref,
            "productionOverlayRef": "contract-production",
            "serviceDigests": {"spring-cloud-gateway": gateway_image, "account-service": account_image},
            "smokeEvidence": ["contract-smoke"],
            "generatedAt": past_timestamp,
            "approvedBy": "preflight-contract",
            "rollbackMode": "rollback-compatible",
            "recoveryCompatibility": compatibility_result("compatible"),
        }
    ),
    encoding="utf-8",
)
promotion_status, promotion_mode, promotion_message, _, _ = module.promotion_check(
    promotion_attestation_path,
    [gateway_image, account_image],
    promotion_root,
    expected_production_overlay_ref="contract-production",
)
if (
    promotion_status != "pass"
    or promotion_mode != "rollback-compatible"
):
    raise SystemExit(f"valid rollback-compatible promotion did not pass: {promotion_message}")

# Bootstrap evidence is independently authorized by the matching operation ID
# and provisioning generation; it must not require a rotation evidence ID.
secret_evidence_path.write_text(
    json.dumps(make_bootstrap_secret_evidence()),
    encoding="utf-8",
)
bootstrap_promotion_status, _, bootstrap_promotion_message, _, _ = module.promotion_check(
    promotion_attestation_path,
    [gateway_image, account_image],
    promotion_root,
    expected_production_overlay_ref="contract-production",
)
if bootstrap_promotion_status != "pass":
    raise SystemExit(
        "valid bootstrap secret compliance evidence did not pass: "
        + bootstrap_promotion_message
    )
bootstrap_mismatch = make_bootstrap_secret_evidence()
bootstrap_mismatch["records"]["operator-credentials"]["provisioningGeneration"] = 8
bootstrap_mismatch["records"]["operator-credentials"]["immutableArtifactId"] = evidence_digest(
    bootstrap_mismatch["records"]["operator-credentials"]
)
secret_evidence_path.write_text(json.dumps(bootstrap_mismatch), encoding="utf-8")
bootstrap_mismatch_status, _, bootstrap_mismatch_message, _, _ = module.promotion_check(
    promotion_attestation_path,
    [gateway_image, account_image],
    promotion_root,
    expected_production_overlay_ref="contract-production",
)
if (
    bootstrap_mismatch_status != "fail"
    or "provisioningGeneration mismatch" not in bootstrap_mismatch_message
):
    raise SystemExit(
        "bootstrap provisioning generation mismatch was accepted: "
        + bootstrap_mismatch_message
    )
secret_evidence_path.write_text(json.dumps(make_secret_evidence()), encoding="utf-8")

base_attestation = json.loads(promotion_attestation_path.read_text(encoding="utf-8"))
base_preflight_report = json.loads(staging_preflight_path.read_text(encoding="utf-8"))


def verify_jwt_lineage_failure(
    case_name,
    mutate_attestation=None,
    mutate_record=None,
    mutate_rotation=None,
    mutate_preflight=None,
    expected_fragment="",
):
    attestation = copy.deepcopy(base_attestation)
    record = copy.deepcopy(staging_record)
    rotation = copy.deepcopy(rotation_evidence)
    preflight = copy.deepcopy(base_preflight_report)
    if mutate_attestation:
        mutate_attestation(attestation)
    if mutate_record:
        mutate_record(record)
    if mutate_rotation:
        mutate_rotation(rotation)
        rotation_evidence_path.write_text(json.dumps(rotation), encoding="utf-8")
        rotation_ref = (
            str(rotation_evidence_path.relative_to(promotion_root))
            + "#"
            + module.canonical_evidence_digest(rotation)
        )
        attestation["jwtRotationEvidenceRef"] = rotation_ref
        record["jwtRotationEvidenceRef"] = rotation_ref
    else:
        rotation_evidence_path.write_text(json.dumps(rotation), encoding="utf-8")
    if mutate_preflight:
        mutate_preflight(preflight)
    staging_preflight_path.write_text(json.dumps(preflight), encoding="utf-8")
    promotion_attestation_path.write_text(json.dumps(attestation), encoding="utf-8")
    staging_record_path.write_text(json.dumps(record), encoding="utf-8")
    status, _, message, _, _ = module.promotion_check(
        promotion_attestation_path,
        [gateway_image, account_image],
        promotion_root,
        expected_production_overlay_ref="contract-production",
    )
    if status != "fail" or expected_fragment not in message:
        raise SystemExit(f"{case_name}: JWT lineage failure was not enforced: {message}")


verify_jwt_lineage_failure(
    "missing-attestation-custody-proof",
    mutate_attestation=lambda attestation: attestation.pop("jwtCustodyProof"),
    expected_fragment="Attestation missing required canonical fields",
)
verify_jwt_lineage_failure(
    "missing-record-custody-proof",
    mutate_record=lambda record: record.pop("jwtCustodyProof"),
    expected_fragment="Staging deployment record missing required canonical fields",
)
verify_jwt_lineage_failure(
    "mismatched-custody-proof",
    mutate_attestation=lambda attestation: attestation.__setitem__(
        "jwtCustodyProof",
        {
            "proofId": "PREFLIGHT-JWT-002",
            "custodyMode": "TARGET_NON_EXPORTABLE_SIGNER",
            "contractVersion": 1,
        },
    ),
    expected_fragment="jwtCustodyProof does not match the attestation",
)
verify_jwt_lineage_failure(
    "extra-custody-proof-field",
    mutate_attestation=lambda attestation: attestation["jwtCustodyProof"].__setitem__(
        "unexpected", True
    ),
    expected_fragment="must contain exactly proofId, custodyMode, and contractVersion",
)
for invalid_contract_version in (True, 1.0):
    verify_jwt_lineage_failure(
        f"invalid-contract-version-{invalid_contract_version!r}",
        mutate_attestation=lambda attestation, version=invalid_contract_version: attestation[
            "jwtCustodyProof"
        ].__setitem__("contractVersion", version),
        expected_fragment="contractVersion must be an integer",
    )
verify_jwt_lineage_failure(
    "missing-selected-custody-policy",
    mutate_preflight=lambda preflight: preflight.__setitem__(
        "checkResults",
        [
            check
            for check in preflight["checkResults"]
            if check["policyId"] != "PREFLIGHT-JWT-INTERIM-001"
        ],
    ),
    expected_fragment="one passing required result for the selected JWT custody policy",
)
verify_jwt_lineage_failure(
    "failed-selected-custody-policy",
    mutate_preflight=lambda preflight: next(
        check
        for check in preflight["checkResults"]
        if check["policyId"] == "PREFLIGHT-JWT-INTERIM-001"
    ).__setitem__("status", "fail"),
    expected_fragment="one passing required result for the selected JWT custody policy",
)
verify_jwt_lineage_failure(
    "alternate-custody-policy",
    mutate_preflight=lambda preflight: preflight["checkResults"].append(
        {
            "policyId": "PREFLIGHT-JWT-002",
            "category": module.PREFLIGHT_POLICY_CATALOG["PREFLIGHT-JWT-002"],
            "required": True,
            "status": "pass",
            "message": "alternate custody must be rejected",
        }
    ),
    expected_fragment="unknown policy IDs",
)
verify_jwt_lineage_failure(
    "missing-attestation-rotation-ref",
    mutate_attestation=lambda attestation: attestation.pop("jwtRotationEvidenceRef"),
    expected_fragment="Attestation missing required canonical fields",
)
verify_jwt_lineage_failure(
    "mismatched-rotation-ref",
    mutate_attestation=lambda attestation: attestation.__setitem__(
        "jwtRotationEvidenceRef", rotation_evidence_ref + "-mismatch"
    ),
    expected_fragment="jwtRotationEvidenceRef does not match the attestation",
)
verify_jwt_lineage_failure(
    "wrong-rotation-event",
    mutate_rotation=lambda rotation: rotation.__setitem__(
        "deploymentEventId", "88888888-8888-4888-8888-888888888888"
    ),
    expected_fragment="deploymentEventId does not match the staging event",
)
verify_jwt_lineage_failure(
    "wrong-rotation-policy",
    mutate_rotation=lambda rotation: rotation.__setitem__("policyId", "PREFLIGHT-JWT-002"),
    expected_fragment="policyId must be PREFLIGHT-JWT-ROTATION-001",
)
verify_jwt_lineage_failure(
    "wrong-rotation-status",
    mutate_rotation=lambda rotation: rotation.__setitem__("status", "fail"),
    expected_fragment="evidence status must be pass",
)
verify_jwt_lineage_failure(
    "non-immutable-rotation-ref",
    mutate_attestation=lambda attestation: attestation.__setitem__(
        "jwtRotationEvidenceRef",
        "design/operations/deployments/staging/jwt-rotation/evidence.json#sha256:not-immutable",
    ),
    mutate_record=lambda record: record.__setitem__(
        "jwtRotationEvidenceRef",
        "design/operations/deployments/staging/jwt-rotation/evidence.json#sha256:not-immutable",
    ),
    expected_fragment="must use <repository-path>#sha256:<digest> format",
)

rotation_evidence_path.write_text(json.dumps(rotation_evidence), encoding="utf-8")
staging_record_path.write_text(json.dumps(staging_record), encoding="utf-8")
staging_preflight_path.write_text(json.dumps(base_preflight_report), encoding="utf-8")
promotion_attestation_path.write_text(json.dumps(base_attestation), encoding="utf-8")

# Exercise staging-lineage failures independently of the deliberately blocked
# recovery-inventory dereference boundary above.
module.validate_recovery_baseline = lambda *args, **kwargs: (
    "pass",
    "contract-only complete recovery evidence",
)

staging_record_path = staging_dir / f"{staging_event_id}.json"
staging_record_path.write_text(
    json.dumps({**staging_record, "preflightReportPath": "staging-preflight.json"}),
    encoding="utf-8",
)
noncanonical_status, _, noncanonical_message, _, _ = module.promotion_check(
    promotion_attestation_path,
    [gateway_image, account_image],
    promotion_root,
    expected_production_overlay_ref="contract-production",
)
if noncanonical_status != "fail" or "canonical preflight report path" not in noncanonical_message:
    raise SystemExit(f"noncanonical staging preflight path was accepted: {noncanonical_message}")

staging_record_path.write_text(
    json.dumps(
        {
            **staging_record,
            "deploymentEventId": "88888888-8888-4888-8888-888888888888",
        }
    ),
    encoding="utf-8",
)
mismatched_event_status, _, mismatched_event_message, _, _ = module.promotion_check(
    promotion_attestation_path,
    [gateway_image, account_image],
    promotion_root,
    expected_production_overlay_ref="contract-production",
)
if mismatched_event_status != "fail" or "deploymentEventId mismatch" not in mismatched_event_message:
    raise SystemExit(f"mismatched preflight deployment event was accepted: {mismatched_event_message}")

staging_record_path.write_text(
    json.dumps(
        {
            **staging_record,
            "appliedAt": timestamp(now - module.dt.timedelta(minutes=10)),
        }
    ),
    encoding="utf-8",
)
late_report_status, _, late_report_message, _, _ = module.promotion_check(
    promotion_attestation_path,
    [gateway_image, account_image],
    promotion_root,
    expected_production_overlay_ref="contract-production",
)
if late_report_status != "fail" or "later than the apply event" not in late_report_message:
    raise SystemExit(f"post-apply preflight report was accepted: {late_report_message}")

unwaived_preflight_report = json.loads(staging_preflight_path.read_text(encoding="utf-8"))
for waiver_value in (
    f"design/operations/deployments/staging/preflight/{staging_sha}/{staging_event_id}.waiver.json",
    None,
):
    waived_preflight_report = {**unwaived_preflight_report, "waiverPath": waiver_value}
    staging_preflight_path.write_text(json.dumps(waived_preflight_report), encoding="utf-8")
    staging_record_path.write_text(json.dumps(staging_record), encoding="utf-8")
    waived_report_status, _, waived_report_message, _, _ = module.promotion_check(
        promotion_attestation_path,
        [gateway_image, account_image],
        promotion_root,
        expected_production_overlay_ref="contract-production",
    )
    if waived_report_status != "fail" or "waivers are not consumable" not in waived_report_message:
        raise SystemExit(
            f"preflight report with waiverPath={waiver_value!r} was accepted: {waived_report_message}"
        )
staging_preflight_path.write_text(json.dumps(unwaived_preflight_report), encoding="utf-8")

stale_preflight_report = json.loads(staging_preflight_path.read_text(encoding="utf-8"))
stale_preflight_report["startedAt"] = timestamp(now - module.dt.timedelta(minutes=41))
stale_preflight_report["completedAt"] = timestamp(now - module.dt.timedelta(minutes=40))
staging_preflight_path.write_text(json.dumps(stale_preflight_report), encoding="utf-8")
staging_record_path.write_text(json.dumps(staging_record), encoding="utf-8")
stale_report_status, _, stale_report_message, _, _ = module.promotion_check(
    promotion_attestation_path,
    [gateway_image, account_image],
    promotion_root,
    expected_production_overlay_ref="contract-production",
)
if stale_report_status != "fail" or "older than the 30-minute apply window" not in stale_report_message:
    raise SystemExit(f"stale preflight report was accepted: {stale_report_message}")
staging_preflight_path.write_text(
    json.dumps(
        {
            **stale_preflight_report,
            "startedAt": past_timestamp,
            "completedAt": past_timestamp,
        }
    ),
    encoding="utf-8",
)

malformed_smoke_record = {**staging_record, "smokeEvidence": [{}]}
staging_record_path.write_text(json.dumps(malformed_smoke_record), encoding="utf-8")
malformed_smoke_status, _, malformed_smoke_message, _, _ = module.promotion_check(
    promotion_attestation_path,
    [gateway_image, account_image],
    promotion_root,
    expected_production_overlay_ref="contract-production",
)
if malformed_smoke_status != "fail" or "smokeEvidence entries" not in malformed_smoke_message:
    raise SystemExit(f"malformed staging smoke evidence was accepted: {malformed_smoke_message}")
staging_record_path.write_text(json.dumps({**staging_record, "smokeEvidence": ["different-smoke"]}), encoding="utf-8")
mismatched_smoke_status, _, mismatched_smoke_message, _, _ = module.promotion_check(
    promotion_attestation_path,
    [gateway_image, account_image],
    promotion_root,
    expected_production_overlay_ref="contract-production",
)
if mismatched_smoke_status != "fail" or "does not match" not in mismatched_smoke_message:
    raise SystemExit(f"mismatched staging smoke evidence was accepted: {mismatched_smoke_message}")
staging_record_path.write_text(json.dumps(staging_record), encoding="utf-8")

malformed_secret_evidence = json.loads(secret_evidence_path.read_text(encoding="utf-8"))
malformed_secret_evidence["records"]["operator-credentials"]["immutableArtifactId"] = {"note": "sha256:"}
secret_evidence_path.write_text(json.dumps(malformed_secret_evidence), encoding="utf-8")
malformed_immutable_status, _, malformed_immutable_message, _, _ = module.promotion_check(
    promotion_attestation_path,
    [gateway_image, account_image],
    promotion_root,
    expected_production_overlay_ref="contract-production",
)
if malformed_immutable_status != "fail" or "not immutable" not in malformed_immutable_message:
    raise SystemExit(f"malformed immutable evidence identifier was accepted: {malformed_immutable_message}")
secret_evidence_path.write_text(
    json.dumps(make_secret_evidence()),
    encoding="utf-8",
)

bad_git_attestation = json.loads(promotion_attestation_path.read_text(encoding="utf-8"))
bad_git_attestation["stagingOverlayCommitSha"] = "deadbeef"
bad_git_path = promotion_root / "bad-git-attestation.json"
bad_git_path.write_text(json.dumps(bad_git_attestation), encoding="utf-8")
bad_git_status, _, bad_git_message, _, _ = module.promotion_check(
    bad_git_path,
    [gateway_image, account_image],
    promotion_root,
)
if bad_git_status != "fail" or "does not exist in Git" not in bad_git_message:
    raise SystemExit(f"unbound stagingOverlayCommitSha did not fail closed: {bad_git_message}")

legacy_attestation = write_json(
    "legacy-roll-forward-attestation.json",
    {
        "environment": "staging",
        "generatedAt": past_timestamp,
        "rollbackMode": "roll-forward-only",
        "serviceDigests": {"account-service": "ghcr.io/firemud/account-service@sha256:candidate"},
        "recoveryCompatibility": {
            **compatibility_result("compatible"),
            "newDrillRequired": True,
            "backupReadinessRef": "legacy-roll-forward-readiness.json",
        },
    },
)
legacy_readiness = write_json(
    "legacy-roll-forward-readiness.json",
    {
        "environment": "production",
        "deploymentRef": "contract-roll-forward",
        "rollbackMode": "roll-forward-only",
        "promotionAttestationRef": legacy_attestation.name,
        "restorePlanRef": "legacy-restore-plan",
        "evidenceRefs": ["legacy-evidence"],
        "backupLastSuccessAt": past_timestamp,
        "backupVerifyLastSuccessAt": past_timestamp,
        "restoreDrillLastSuccessAt": past_timestamp,
        "serviceDigests": {"account-service": "ghcr.io/firemud/account-service@sha256:candidate"},
    },
)
legacy_status, legacy_message = module.backup_readiness_check(
    legacy_readiness,
    now.isoformat().replace("+00:00", "Z"),
    "contract-roll-forward",
    tmp,
)
if legacy_status != "fail" or "required target-state fields" not in legacy_message:
    raise SystemExit(f"incomplete legacy roll-forward evidence did not fail closed: {legacy_message}")

future_attestation = write_json(
    "future-roll-forward-attestation.json",
    {
        "environment": "staging",
        "generatedAt": past_timestamp,
        "rollbackMode": "roll-forward-only",
        "serviceDigests": {"account-service": "ghcr.io/firemud/account-service@sha256:candidate"},
        "recoveryCompatibility": {
            **compatibility_result("compatible"),
            "newDrillRequired": True,
            "backupReadinessRef": "future-roll-forward-readiness.json",
        },
    },
)
future_readiness = write_json(
    "future-roll-forward-readiness.json",
    {
        "environment": "production",
        "deploymentRef": "contract-roll-forward",
        "promotionAttestationRef": future_attestation.name,
        "assessedAt": past_timestamp,
        "assessedBy": "preflight-contract",
        "rollbackMode": "roll-forward-only",
        "backupLastSuccessAt": future_timestamp,
        "backupVerifyLastSuccessAt": past_timestamp,
        "restoreDrillLastSuccessAt": past_timestamp,
        "restorePlanRef": "restore-plan",
        "restoreRecoveryRecordRef": "recovery/restore.json",
        "baselineRecoveryRecordRef": "design/operations/deployments/production/recovery/baseline.json",
        "recoveryControllerLineage": {"recoveryStatus": "finalized"},
        "backupConfidentialityEvidence": {"status": "pass"},
        "backupCoverage": "environment-wide-postgresql",
        "backupArtifactRef": "backups/artifact",
        "artifactErasureHighWater": {"stream": "erasures", "sequence": 1},
        "initialCatchupHighWater": {"stream": "erasures", "sequence": 2},
        "restoreHighWater": {"stream": "erasures", "sequence": 3},
        "sourceServiceDigests": {"account-service": "ghcr.io/firemud/account-service@sha256:source"},
        "candidateServiceDigests": {"account-service": "ghcr.io/firemud/account-service@sha256:candidate"},
        "candidateMigrationPathRef": "migrations/candidate",
        "backupToolDigest": "sha256:backup-tool",
        "recoveryToolDigest": "sha256:recovery-tool",
        "recoveryContractFingerprint": "sha256:recovery-contract",
        "evidenceRefs": ["contract-evidence"],
    },
)
missing_restore_high_water_data = json.loads(future_readiness.read_text(encoding="utf-8"))
missing_restore_high_water_data.pop("restoreHighWater")
missing_restore_high_water = write_json(
    "missing-restore-high-water-readiness.json",
    missing_restore_high_water_data,
)
missing_restore_status, missing_restore_message = module.backup_readiness_check(
    missing_restore_high_water,
    now.isoformat().replace("+00:00", "Z"),
    "contract-roll-forward",
    tmp,
)
if (
    missing_restore_status != "fail"
    or "required target-state fields" not in missing_restore_message
    or "restoreHighWater" not in missing_restore_message
):
    raise SystemExit(
        f"backup readiness without restoreHighWater did not fail closed: {missing_restore_message}"
    )

future_status, future_message = module.backup_readiness_check(
    future_readiness,
    now.isoformat().replace("+00:00", "Z"),
    "contract-roll-forward",
    tmp,
)
if future_status != "fail" or "future-dated timestamps" not in future_message:
    raise SystemExit(f"future-dated backup readiness did not fail closed: {future_message}")

blocked_attestation = write_json(
    "blocked-roll-forward-attestation.json",
    {
        "environment": "staging",
        "generatedAt": past_timestamp,
        "rollbackMode": "roll-forward-only",
        "serviceDigests": {"account-service": "ghcr.io/firemud/account-service@sha256:candidate"},
        "recoveryCompatibility": {
            **compatibility_result("compatible"),
            "newDrillRequired": True,
            "backupReadinessRef": "blocked-roll-forward-readiness.json",
        },
    },
)
blocked_readiness = write_json(
    "blocked-roll-forward-readiness.json",
    {
        **json.loads(future_readiness.read_text(encoding="utf-8")),
        "promotionAttestationRef": blocked_attestation.name,
        "backupLastSuccessAt": past_timestamp,
    },
)
blocked_status, blocked_message = module.backup_readiness_check(
    blocked_readiness,
    now.isoformat().replace("+00:00", "Z"),
    "contract-roll-forward",
    tmp,
)
if blocked_status != "fail" or "remains blocked until canonical recovery-controller" not in blocked_message:
    raise SystemExit(f"incomplete nested roll-forward validation did not fail closed: {blocked_message}")
PY

echo "preflight contract checks passed"
