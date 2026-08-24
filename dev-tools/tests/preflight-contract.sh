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
    "assetStorage.bucket",
    "assetStorage.bindingRef",
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
            - name: GATEWAY_WS_URL
              value: wss://spring-cloud-gateway-mtls.firemud.svc.cluster.local/ws/game
          envFrom:
            - secretRef:
                name: postgres-credentials
            - configMapRef:
                name: firemud-config
          volumeMounts:
            - name: jwt-signing-keys
              mountPath: /var/run/secrets/firemud/jwt
              readOnly: true
      volumes:
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

python3 - <<'PY' "$RENDERED_MANIFEST" "$MIGRATED_RENDERED_MANIFEST"
import pathlib
import sys

import yaml

source = pathlib.Path(sys.argv[1])
destination = pathlib.Path(sys.argv[2])
documents = list(yaml.safe_load_all(source.read_text(encoding="utf-8")))
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
        volume for volume in pod_spec.get("volumes", []) if volume.get("name") == "jwt-jwks"
    )
    jwks_volume.pop("secret", None)
    jwks_volume["configMap"] = {"name": "jwt-jwks"}
    account_container = next(
        container
        for container in pod_spec.get("containers", [])
        if container.get("name") == "account-service"
    )
    jwks_mount = next(
        mount for mount in account_container.get("volumeMounts", []) if mount.get("name") == "jwt-jwks"
    )
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

legacy_documents = list(yaml.safe_load_all(rendered_path.read_text(encoding="utf-8")))


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
    jwks["data"] = jwks.pop("stringData")
    account = account_deployment(documents)
    pod_spec = account["spec"]["template"]["spec"]
    jwks_volume = next(volume for volume in pod_spec["volumes"] if volume.get("name") == "jwt-jwks")
    jwks_volume.pop("secret", None)
    jwks_volume["configMap"] = {"name": "jwt-jwks"}
    return documents


def jwks_result(documents, expected_ref=module.CANONICAL_JWKS_REF):
    results = {
        result.policy_id: result
        for result in module.jwt_jwks_checks(documents, expected_ref)
    }
    return results["PREFLIGHT-JWKS-001"]


public_documents = public_config_map_documents()
public_result = jwks_result(public_documents)
if public_result.status != "pass":
    raise SystemExit(f"public jwt-jwks ConfigMap with Account mount did not pass: {public_result.message}")

secret_binding_result = jwks_result(public_documents, "secret://firemud/jwt-jwks")
if secret_binding_result.status != "fail" or "configmap://firemud/jwt-jwks" not in secret_binding_result.message:
    raise SystemExit(
        "Secret-backed jwksRef did not fail the canonical binding contract: "
        f"{secret_binding_result.message}"
    )

noncanonical_binding_result = jwks_result(public_documents, "configmap://other/jwt-jwks")
if noncanonical_binding_result.status != "fail" or "configmap://firemud/jwt-jwks" not in noncanonical_binding_result.message:
    raise SystemExit(
        "noncanonical public jwksRef did not fail the fixed-name binding contract: "
        f"{noncanonical_binding_result.message}"
    )

for case_name, mutate in (
    ("missing-data", lambda jwks: jwks.pop("data")),
    ("empty-data", lambda jwks: jwks.__setitem__("data", {})),
    ("wrong-data-type", lambda jwks: jwks.__setitem__("data", {"jwks.json": 7})),
):
    malformed_documents = copy.deepcopy(public_documents)
    malformed_jwks = next(
        document
        for document in malformed_documents
        if document.get("kind") == "ConfigMap"
        and document.get("metadata", {}).get("name") == "jwt-jwks"
    )
    mutate(malformed_jwks)
    malformed_result = jwks_result(malformed_documents)
    if malformed_result.status != "fail" or "data.jwks.json" not in malformed_result.message:
        raise SystemExit(
            f"{case_name} jwt-jwks ConfigMap data did not fail closed: {malformed_result.message}"
        )

missing_mount_documents = copy.deepcopy(public_documents)
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

wrong_mount_documents = copy.deepcopy(public_documents)
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
if secret_result.status != "fail" or "ConfigMap" not in secret_result.message:
    raise SystemExit(f"Secret jwt-jwks incorrectly satisfied the public-resource contract: {secret_result.message}")
PY

# Legacy Secret-backed hobby fixture: the migration gap must remain explicit.
set +e
FIREMUD_PREFLIGHT_CONTEXT=ci-static \
  FIREMUD_DEPLOYMENT_REF=contract-hobby \
  FIREMUD_PREFLIGHT_RENDER_PATH="$RENDERED_MANIFEST" \
  FIREMUD_PREFLIGHT_OUTPUT="$REPORT_PATH" \
  python3 "$SCRIPT" hobby-self-hosted >"$LEGACY_HOBBY_PREFLIGHT_OUTPUT"
preflight_status=$?
set -e
if [ "$preflight_status" -ne 1 ]; then
  echo "expected legacy Secret jwt-jwks fixture to fail canonical public-resource preflight" >&2
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
    and check["policyId"] not in {"PREFLIGHT-DIGEST-002", "PREFLIGHT-JWKS-001"}
]
if failures:
    raise SystemExit(f"unexpected required preflight failures: {failures}")
legacy_jwks = [
    check
    for check in report["checkResults"]
    if check["policyId"] == "PREFLIGHT-JWKS-001"
]
if len(legacy_jwks) != 1 or legacy_jwks[0]["status"] != "fail" or "ConfigMap" not in legacy_jwks[0]["message"]:
    raise SystemExit(f"legacy Secret jwt-jwks fixture did not fail the canonical public-resource check: {legacy_jwks}")
PY

# Migrated ConfigMap-backed hobby fixture: the canonical JWKS check must pass.
set +e
FIREMUD_PREFLIGHT_CONTEXT=ci-static \
  FIREMUD_DEPLOYMENT_REF=contract-hobby-migrated \
  FIREMUD_PREFLIGHT_RENDER_PATH="$MIGRATED_RENDERED_MANIFEST" \
  FIREMUD_PREFLIGHT_OUTPUT="$MIGRATED_REPORT_PATH" \
  python3 "$SCRIPT" hobby-self-hosted >"$MIGRATED_HOBBY_PREFLIGHT_OUTPUT"
migrated_preflight_status=$?
set -e
if [ "$migrated_preflight_status" -ne 0 ]; then
  echo "migrated ConfigMap-backed hobby fixture unexpectedly failed preflight" >&2
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
if required_failures:
    raise SystemExit(f"migrated ConfigMap fixture had unexpected required preflight failures: {required_failures}")
migrated_jwks = [
    check
    for check in report["checkResults"]
    if check["policyId"] == "PREFLIGHT-JWKS-001"
]
if len(migrated_jwks) != 1 or migrated_jwks[0]["status"] != "pass":
    raise SystemExit(f"migrated ConfigMap fixture did not pass the canonical public-resource check: {migrated_jwks}")
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
for invalid_catalog in (
    {policy_id: category for policy_id, category in module.PREFLIGHT_POLICY_CATALOG.items() if policy_id != "PREFLIGHT-BACKUP-003"},
    {**module.PREFLIGHT_POLICY_CATALOG, "PREFLIGHT-UNKNOWN-001": "apply-blocking"},
    {**module.PREFLIGHT_POLICY_CATALOG, "PREFLIGHT-BACKUP-003": "invalid"},
):
    catalog_message = module.validate_preflight_policy_catalog(invalid_catalog)
    if catalog_message is None:
        raise SystemExit(f"invalid preflight policy catalogue was accepted: {invalid_catalog}")
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

# The checked-in production overlay remains a separately named legacy-gap case.
set +e
FIREMUD_PREFLIGHT_CONTEXT=ci-static \
  FIREMUD_DEPLOYMENT_REF="contract-production" \
  FIREMUD_PREFLIGHT_OUTPUT="$PRODUCTION_REPORT" \
  python3 "$SCRIPT" production >"$LEGACY_PRODUCTION_PREFLIGHT_OUTPUT"
production_preflight_status=$?
set -e
if [ "$production_preflight_status" -ne 1 ]; then
  echo "expected checked-in production legacy-gap fixture to fail canonical public-resource preflight" >&2
  exit 1
fi

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
grep -Fq "invalid choice: 'production'" "$PRODUCTION_TRAFFIC_WRITER_OUTPUT"

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
  # These checked-in overlays remain legacy Secret-backed; the canonical
  # public-resource check must report their migration gap explicitly.
  set +e
  FIREMUD_PREFLIGHT_CONTEXT=ci-static \
    FIREMUD_DEPLOYMENT_REF="contract-$env" \
    FIREMUD_PREFLIGHT_OUTPUT="$REPORT" \
    python3 "$SCRIPT" "$env" >"$TMP_DIR/firemud-preflight-contract-$env.out"
  preflight_status=$?
  set -e
  if [ "$preflight_status" -ne 1 ]; then
    echo "$env: expected checked-in legacy-gap fixture to fail canonical public-resource preflight" >&2
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
    if check["status"] == "fail" and check["policyId"] != "PREFLIGHT-JWKS-001"
]
if failures:
    raise SystemExit(f"{env}: unexpected preflight failures: {failures}")
jwks = [check for check in report["checkResults"] if check["policyId"] == "PREFLIGHT-JWKS-001"]
if len(jwks) != 1 or jwks[0]["status"] != "fail" or "ConfigMap" not in jwks[0]["message"]:
    raise SystemExit(f"{env}: legacy Secret jwt-jwks fixture did not fail the canonical public-resource check: {jwks}")
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
        "bucket": "unique-assets",
        "endpoint": "https://assets.unique.internal",
        "bindingRef": "secret://firemud/unique-assets",
    },
    "outboundComms": {
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

    def config_map_lookup(stdout, returncode=0, stderr=""):
        def lookup(args, **kwargs):
            if kwargs.get("timeout") != module.SECRET_LOOKUP_TIMEOUT_SECONDS:
                raise SystemExit("ConfigMap lookup did not receive its deployment timeout")
            if args[-2:] != ["-o", "json"]:
                raise SystemExit(f"ConfigMap lookup did not request JSON output: {args}")
            return module.subprocess.CompletedProcess(args, returncode, stdout, stderr)

        return lookup

    module.subprocess.run = config_map_lookup(
        "", 1, 'Error from server (NotFound): configmaps "missing" not found'
    )
    config_map_missing_message = module.jwks_config_map_lookup_failure("missing")
    if config_map_missing_message != "Missing required ConfigMap in cluster: firemud/missing":
        raise SystemExit(
            f"NotFound ConfigMap lookup reported incorrectly: {config_map_missing_message}"
        )

    for name, payload in (
        ("empty", {"data": {}}),
        ("non-string", {"data": {"jwks.json": 7}}),
    ):
        module.subprocess.run = config_map_lookup(json.dumps(payload))
        config_map_invalid_message = module.jwks_config_map_lookup_failure("jwt-jwks")
        if (
            config_map_invalid_message is None
            or "data.jwks.json" not in config_map_invalid_message
            or "non-empty" not in config_map_invalid_message
        ):
            raise SystemExit(
                f"{name} ConfigMap lookup did not fail closed: {config_map_invalid_message}"
            )

    module.subprocess.run = config_map_lookup(
        json.dumps({"data": {"jwks.json": '{"keys":[]}'}})
    )
    if module.jwks_config_map_lookup_failure("jwt-jwks") is not None:
        raise SystemExit("valid ConfigMap JSON lookup did not pass")
finally:
    module.subprocess.run = original_subprocess_run

issues = module.external_binding_uniqueness_issues(env_root, "staging", staging)
if not any("backupStorage.bucket matches production" in issue for issue in issues):
    raise SystemExit(f"expected duplicate backupStorage.bucket issue, got: {issues}")

disabled_staging = copy.deepcopy(staging)
disabled_production = copy.deepcopy(production)
disabled_staging["backupStorage"] = {"enabled": False}
disabled_staging["assetStorage"]["bucket"] = "dup-assets"
disabled_production["assetStorage"]["bucket"] = "dup-assets"
for env, data in (("staging", disabled_staging), ("production", disabled_production)):
    path = env_root / env / "expected-bindings.yaml"
    path.write_text(yaml.safe_dump(data, sort_keys=False), encoding="utf-8")

issues = module.external_binding_uniqueness_issues(
    env_root, "staging", disabled_staging
)
if any(issue.startswith("backupStorage.") for issue in issues):
    raise SystemExit(f"disabled backup storage must be excluded from uniqueness checks: {issues}")
if not any("assetStorage.bucket matches production" in issue for issue in issues):
    raise SystemExit(f"disabled backup storage must not disable other uniqueness checks: {issues}")

shared_value = {"value": "smtp.shared.internal", "shared": True, "sharedRationale": "shared relay"}
staging["outboundComms"]["smtpHost"] = shared_value
production["outboundComms"]["smtpHost"] = shared_value
for env, data in (("staging", staging), ("production", production)):
    path = env_root / env / "expected-bindings.yaml"
    path.write_text(yaml.safe_dump(data, sort_keys=False), encoding="utf-8")

issues = module.external_binding_uniqueness_issues(env_root, "staging", staging)
if any("outboundComms.smtpHost" in issue for issue in issues):
    raise SystemExit(f"shared smtpHost should be allowed, got: {issues}")


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
    rendered_payload = (
        rendered_payload
        + "\n---\n"
        + yaml.safe_dump(
            {
                "apiVersion": "v1",
                "kind": "ConfigMap",
                "metadata": {"name": f"service-discovery-{case_name}"},
                "data": rendered_overrides,
            },
            sort_keys=False,
        )
    )

    documents = module.parse_documents(rendered_payload)
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
    "missing",
    {},
    {"FIREMUD_SERVICES_ACCOUNT_SERVICE": "account-service.firemud.svc.cluster.local"},
    "fail",
    "No FIREMUD_SERVICES_* overrides were rendered",
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
current_results = module.expected_binding_checks(
    current_expected_path,
    "design/operations/environments/hobby-self-hosted/expected-bindings.yaml",
    "hobby-self-hosted",
    rendered_documents,
)
current_secrets = next(result for result in current_results if result.policy_id == "PREFLIGHT-SECRETS-002")
if current_secrets.status != "pass":
    raise SystemExit(f"checked-in legacy JWT custody selector did not pass current wiring validation: {current_secrets.message}")


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

verify_binding_ref_contract(
    "invalid-internal-binding-ref",
    lambda data: data["internalBindings"]["certificates"].__setitem__("issuerRef", "cert-manager://firemud/not-a-kind/firemud-hobby"),
    "PREFLIGHT-SECRETS-002",
    "internalBindings.certificates.issuerRef must use one of the allowed binding kinds",
)
verify_binding_ref_contract(
    "secret-jwks-binding-ref",
    lambda data: data["internalBindings"]["jwt"].__setitem__("jwksRef", "secret://firemud/jwt-jwks"),
    "PREFLIGHT-SECRETS-002",
    "internalBindings.jwt.jwksRef must use one of the allowed schemes: configmap",
)
verify_binding_ref_contract(
    "noncanonical-jwks-binding-ref",
    lambda data: data["internalBindings"]["jwt"].__setitem__("jwksRef", "configmap://other/jwt-jwks"),
    "PREFLIGHT-SECRETS-002",
    "internalBindings.jwt.jwksRef must be the canonical configmap://firemud/jwt-jwks reference",
)
verify_binding_ref_contract(
    "invalid-external-binding-ref",
    lambda data: data["backupStorage"].__setitem__("bindingRef", "not-a-binding-ref"),
    "PREFLIGHT-EXTERNAL-001",
    "backupStorage.bindingRef must use <scheme>://<namespace>/<binding> format",
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

secret_evidence_path = promotion_root / "secret-compliance.json"
secret_evidence_path.write_text(
    json.dumps(
        {
            "records": {
                class_name: {"immutableArtifactId": f"contract:{class_name}:sha256:{'a' * 64}"}
                for class_name in (
                    "jwt-signing-keys-jwks",
                    "postgres-application-credentials",
                    "backup-object-store-credentials",
                    "operator-credentials",
                )
            }
        }
    ),
    encoding="utf-8",
)
staging_event_id = "55555555-5555-4555-8555-555555555555"
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
            "checkResults": [
                {
                    "policyId": policy_id,
                    "category": module.PREFLIGHT_POLICY_CATALOG[policy_id],
                    "required": required,
                    "status": "pass" if required else "not_applicable",
                    "message": "contract evidence",
                }
                for policy_id, required in staging_requirements.items()
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
(staging_dir / f"{staging_event_id}.json").write_text(json.dumps(staging_record), encoding="utf-8")
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
    json.dumps(
        {
            "records": {
                class_name: {"immutableArtifactId": f"contract:{class_name}:sha256:{'a' * 64}"}
                for class_name in (
                    "jwt-signing-keys-jwks",
                    "postgres-application-credentials",
                    "backup-object-store-credentials",
                    "operator-credentials",
                )
            }
        }
    ),
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
