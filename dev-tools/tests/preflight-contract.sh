#!/usr/bin/env bash
# Contract checks for dev-tools/deploy/preflight.py report shape and policy IDs.
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SCRIPT="$ROOT_DIR/dev-tools/deploy/preflight.py"
WRITER="$ROOT_DIR/dev-tools/deploy/write-traffic-open-evidence.py"
TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

RENDERED_MANIFEST="$TMP_DIR/hobby-rendered.yaml"
REPORT_PATH="$TMP_DIR/preflight-report.json"
TRAFFIC_EVIDENCE="$TMP_DIR/traffic-open.json"
PRODUCTION_REPORT="$TMP_DIR/preflight-production.json"
PRODUCTION_TRAFFIC_EVIDENCE="$TMP_DIR/production-traffic-open.json"
PRODUCTION_BASELINE_RECOVERY="$TMP_DIR/production-baseline-recovery.json"
PRODUCTION_BACKUP_READINESS="$TMP_DIR/production-backup-readiness.json"

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
    "internalBindings.jwt.signingKeysRef",
    "internalBindings.jwt.jwksRef",
    "internalBindings.certificates.issuerRef",
    "internalBindings.certificates.workloadMtlsRef",
    "internalBindings.certificates.gatewayInternalWsListenerRef",
    "internalBindings.certificates.tcpProxyBridgeClientRef",
    "internalBindings.registry.imagePullSecretRef",
    "backupStorage.bucket",
    "backupStorage.bindingRef",
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

FIREMUD_PREFLIGHT_CONTEXT=ci-static \
  FIREMUD_DEPLOYMENT_REF=contract-hobby \
  FIREMUD_PREFLIGHT_RENDER_PATH="$RENDERED_MANIFEST" \
  FIREMUD_PREFLIGHT_OUTPUT="$REPORT_PATH" \
  python3 "$SCRIPT" hobby-self-hosted >/tmp/firemud-preflight-contract.out

python3 - <<'PY' "$REPORT_PATH"
import json
import pathlib
import sys

report = json.loads(pathlib.Path(sys.argv[1]).read_text(encoding="utf-8"))
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
if report.get("expectedBindingsRef") != "design/operations/environments/hobby-self-hosted/expected-bindings.yaml":
    raise SystemExit("preflight report missing expectedBindingsRef")
failures = [
    check
    for check in report["checkResults"]
    if check["status"] == "fail" and check["policyId"] != "PREFLIGHT-DIGEST-002"
]
if failures:
    raise SystemExit(f"unexpected required preflight failures: {failures}")
PY

python3 "$WRITER" hobby-self-hosted contract-hobby first-live \
  --assessed-by preflight-contract \
  --preflight-report "$REPORT_PATH" \
  --evidence-ref contract-test \
  --output "$TRAFFIC_EVIDENCE" >/tmp/firemud-preflight-write-traffic-hobby.out

FIREMUD_PREFLIGHT_CONTEXT=ci-static \
  FIREMUD_DEPLOYMENT_REF=contract-hobby \
  FIREMUD_PREFLIGHT_RENDER_PATH="$RENDERED_MANIFEST" \
  FIREMUD_PREFLIGHT_OUTPUT="$REPORT_PATH" \
  FIREMUD_TRAFFIC_OPEN_EVENT=first-live \
  FIREMUD_TRAFFIC_OPEN_EVIDENCE="$TRAFFIC_EVIDENCE" \
  python3 "$SCRIPT" hobby-self-hosted >/tmp/firemud-preflight-contract-traffic.out 2>&1 && {
    echo "expected incomplete checked-in hobby backup evidence to fail first-live preflight" >&2
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
if "status must be pass" not in backup_003[0]["message"]:
    raise SystemExit(f"PREFLIGHT-BACKUP-003 failed for the wrong reason: {backup_003}")
PY

python3 - <<'PY' "$ROOT_DIR" "$TMP_DIR"
import importlib.util
import json
import pathlib
import sys

root = pathlib.Path(sys.argv[1])
tmp = pathlib.Path(sys.argv[2])
spec = importlib.util.spec_from_file_location("preflight_hobby_contract", root / "dev-tools/deploy/preflight.py")
module = importlib.util.module_from_spec(spec)
assert spec.loader is not None
sys.modules[spec.name] = module
spec.loader.exec_module(module)

compliance_path = tmp / "passing-hobby-backup-compliance.yaml"
compliance_path.write_text("environment: hobby-self-hosted\nstatus: pass\n", encoding="utf-8")
preflight_path = tmp / "passing-hobby-preflight.json"
preflight_path.write_text(
    json.dumps(
        {
            "environment": "hobby-self-hosted",
            "expectedBindingsRef": "design/operations/environments/hobby-self-hosted/expected-bindings.yaml",
            "deploymentRef": {"manifestRef": "contract-hobby"},
            "checkResults": [{"policyId": "PREFLIGHT-DIGEST-002", "status": "fail"}],
        }
    ),
    encoding="utf-8",
)
traffic_path = tmp / "passing-hobby-traffic-open.json"
traffic_path.write_text(
    json.dumps(
        {
            "schemaVersion": "traffic-open-record/v1",
            "environment": "hobby-self-hosted",
            "eventType": "first-live",
            "deploymentRef": "contract-hobby",
            "assessedAt": "2026-01-01T00:00:00Z",
            "assessedBy": "preflight-contract",
            "backupComplianceRef": "design/operations/deployments/hobby-self-hosted/backup-compliance.yaml",
            "preflightReportPath": str(preflight_path),
            "evidenceRefs": ["contract-test"],
        }
    ),
    encoding="utf-8",
)
status, message = module.hobby_traffic_check(
    compliance_path,
    traffic_path,
    "first-live",
    "contract-hobby",
    root,
)
if status != "pass":
    raise SystemExit(f"synthetic compliant hobby evidence did not pass: {message}")
PY

FIREMUD_PREFLIGHT_CONTEXT=ci-static \
  FIREMUD_DEPLOYMENT_REF="contract-production" \
  FIREMUD_PREFLIGHT_OUTPUT="$PRODUCTION_REPORT" \
  python3 "$SCRIPT" production >/tmp/firemud-preflight-contract-production-traffic.out

python3 - <<'PY' "$PRODUCTION_REPORT" "$PRODUCTION_TRAFFIC_EVIDENCE" "$PRODUCTION_BASELINE_RECOVERY" "$PRODUCTION_BACKUP_READINESS"
import json
import pathlib
import sys

preflight_path, traffic_path, baseline_path, readiness_path = map(pathlib.Path, sys.argv[1:])
recovery = {
    "schemaVersion": "recovery-record/v1",
    "environment": "production",
    "recoveryRef": "contract-baseline-recovery",
    "recoveryStatus": "finalized",
    "recoveryPurpose": "production-equivalent-drill",
    "sourceEnvironmentBinding": "production-source",
    "targetBoundary": "production-equivalent-boundary",
    "trafficExposure": "isolated-drill",
    "coordinationRecoveryMode": "cold_start_restore",
    "backupArtifactRef": "s3://firemud-production/backups/contract",
    "backupArtifactLineage": {"coverage": "environment-wide-postgresql"},
    "coordinationRecoveryEvidence": {"status": "pass"},
    "durableParticipantConvergence": {"status": "pass"},
    "externalEffectReconciliation": {"status": "pass"},
    "sessionRecovery": {
        "gameSessionHandling": "invalidated",
        "authSessionHandling": "invalidated",
    },
}
baseline_path.write_text(json.dumps(recovery), encoding="utf-8")
readiness_path.write_text(
    json.dumps(
        {
            "environment": "production",
            "backupCoverage": "environment-wide-postgresql",
            "backupArtifactRef": recovery["backupArtifactRef"],
            "restoreRecoveryRecordRef": str(baseline_path),
        }
    ),
    encoding="utf-8",
)
traffic_path.write_text(
    json.dumps(
        {
            "schemaVersion": "traffic-open-record/v1",
            "environment": "production",
            "eventType": "reopen",
            "deploymentRef": "contract-production",
            "assessedAt": "2026-01-01T00:00:00Z",
            "assessedBy": "preflight-contract",
            "preflightReportPath": str(preflight_path),
            "backupStorageBinding": "secret://firemud/production-backup-object-store",
            "backupCoverage": "environment-wide-postgresql",
            "backupArtifactRef": recovery["backupArtifactRef"],
            "backupReadinessRef": str(readiness_path),
            "baselineRecoveryRecordRef": str(baseline_path),
            "sourceEnvironmentBinding": recovery["sourceEnvironmentBinding"],
            "drillTargetBoundary": recovery["targetBoundary"],
            "evidenceRefs": ["contract-test"],
        }
    ),
    encoding="utf-8",
)
PY

FIREMUD_PREFLIGHT_CONTEXT=ci-static \
  FIREMUD_DEPLOYMENT_REF="contract-production" \
  FIREMUD_PREFLIGHT_OUTPUT="$PRODUCTION_REPORT" \
  FIREMUD_TRAFFIC_OPEN_EVENT=reopen \
  FIREMUD_TRAFFIC_OPEN_EVIDENCE="$PRODUCTION_TRAFFIC_EVIDENCE" \
  python3 "$SCRIPT" production >/tmp/firemud-preflight-contract-production-traffic-gated.out

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
if len(backup_002) != 1 or backup_002[0]["status"] != "pass":
    raise SystemExit(f"PREFLIGHT-BACKUP-002 did not pass: {backup_002}")
PY

python3 - <<'PY' "$ROOT_DIR" "$PRODUCTION_TRAFFIC_EVIDENCE"
import importlib.util
import json
import pathlib
import sys

root = pathlib.Path(sys.argv[1])
traffic_path = pathlib.Path(sys.argv[2])
spec = importlib.util.spec_from_file_location("preflight_production_contract", root / "dev-tools/deploy/preflight.py")
module = importlib.util.module_from_spec(spec)
assert spec.loader is not None
sys.modules[spec.name] = module
spec.loader.exec_module(module)
traffic = json.loads(traffic_path.read_text(encoding="utf-8"))
traffic.pop("baselineRecoveryRecordRef")
traffic["coordinatedBackupScope"] = {"type": "tenant_region", "tenantId": "tenant-1", "regionId": "region-1"}
traffic["restoreDrillLastSuccessAt"] = "2026-01-01T00:00:00Z"
legacy_path = traffic_path.with_name("legacy-production-traffic-open.json")
legacy_path.write_text(json.dumps(traffic), encoding="utf-8")
status, message = module.production_traffic_check(
    legacy_path,
    "reopen",
    "contract-production",
    root,
)
if status == "pass":
    raise SystemExit("obsolete tenant/region traffic-open evidence unexpectedly passed")
PY

for env in staging production; do
  REPORT="$TMP_DIR/preflight-$env.json"
  FIREMUD_PREFLIGHT_CONTEXT=ci-static \
    FIREMUD_DEPLOYMENT_REF="contract-$env" \
    FIREMUD_PREFLIGHT_OUTPUT="$REPORT" \
    python3 "$SCRIPT" "$env" >/tmp/firemud-preflight-contract-"$env".out

  python3 - <<'PY' "$REPORT" "$env"
import json
import pathlib
import sys

report = json.loads(pathlib.Path(sys.argv[1]).read_text(encoding="utf-8"))
env = sys.argv[2]
expected_ref = f"design/operations/environments/{env}/expected-bindings.yaml"
if report.get("expectedBindingsRef") != expected_ref:
    raise SystemExit(f"{env}: expectedBindingsRef mismatch: {report.get('expectedBindingsRef')}")
failures = [check for check in report["checkResults"] if check["status"] == "fail"]
if failures:
    raise SystemExit(f"{env}: unexpected preflight failures: {failures}")
PY
done

python3 - <<'PY' "$ROOT_DIR" "$TMP_DIR"
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
    "backupStorage": {"bucket": "unique-backups", "bindingRef": "secret://firemud/unique-backup"},
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

staging["backupStorage"] = {"bucket": "dup-backups", "bindingRef": "secret://firemud/staging-backup"}
production["backupStorage"] = {"bucket": "dup-backups", "bindingRef": "secret://firemud/production-backup"}
hobby["backupStorage"] = {"bucket": "hobby-backups", "bindingRef": "secret://firemud/hobby-backup"}

for env, data in (("staging", staging), ("production", production), ("hobby-self-hosted", hobby)):
    path = env_root / env / "expected-bindings.yaml"
    path.write_text(yaml.safe_dump(data, sort_keys=False), encoding="utf-8")

spec = importlib.util.spec_from_file_location("preflight", root / "dev-tools/deploy/preflight.py")
module = importlib.util.module_from_spec(spec)
assert spec.loader is not None
sys.modules[spec.name] = module
spec.loader.exec_module(module)

issues = module.external_binding_uniqueness_issues(env_root, "staging", staging)
if not any("backupStorage.bucket matches production" in issue for issue in issues):
    raise SystemExit(f"expected duplicate backupStorage.bucket issue, got: {issues}")

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

verify_binding_ref_contract(
    "invalid-internal-binding-ref",
    lambda data: data["internalBindings"]["certificates"].__setitem__("issuerRef", "cert-manager://firemud/not-a-kind/firemud-hobby"),
    "PREFLIGHT-SECRETS-002",
    "internalBindings.certificates.issuerRef must use one of the allowed binding kinds",
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
routine_expected["internalBindings"]["certificates"].pop("backupControlPlaneClientRef", None)
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
PY

echo "preflight contract checks passed"
