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
OPERATOR_REPORT_PATH="$TMP_DIR/operator-preflight-report.json"
TRAFFIC_EVIDENCE="$TMP_DIR/traffic-open.json"
PRODUCTION_REPORT="$TMP_DIR/preflight-production.json"
LEGACY_PRODUCTION_TRAFFIC_EVIDENCE="$TMP_DIR/production-traffic-open.json"
PRODUCTION_WAIVER="$TMP_DIR/contract-production.waiver.json"

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

FIREMUD_PREFLIGHT_CONTEXT=ci-static \
  FIREMUD_DEPLOYMENT_REF=contract-hobby \
  FIREMUD_PREFLIGHT_RENDER_PATH="$RENDERED_MANIFEST" \
  FIREMUD_PREFLIGHT_OUTPUT="$REPORT_PATH" \
  python3 "$SCRIPT" hobby-self-hosted >/tmp/firemud-preflight-contract.out

python3 - <<'PY' "$REPORT_PATH"
import json
import pathlib
import sys
import uuid

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
if actual_ids != expected_ids or len(report["checkResults"]) != len(expected_ids):
    raise SystemExit("preflight report did not emit exactly the complete expected policy set")
if report.get("expectedBindingsRef") != "design/operations/environments/hobby-self-hosted/expected-bindings.yaml":
    raise SystemExit("preflight report missing expectedBindingsRef")
try:
    uuid.UUID(report["deploymentEventId"])
except (KeyError, ValueError) as exc:
    raise SystemExit(f"preflight report missing canonical deploymentEventId: {exc}") from exc
if report.get("trafficOpenEvent") is not None:
    raise SystemExit("general preflight report unexpectedly recorded a traffic-open event")
if any(not isinstance(check.get("required"), bool) for check in report["checkResults"]):
    raise SystemExit("preflight report did not emit required applicability for every policy")
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
  --preflight-report "$OPERATOR_REPORT_PATH" \
  --evidence-ref contract-test \
  --output "$TRAFFIC_EVIDENCE" >/tmp/firemud-preflight-write-traffic-hobby.out

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
  python3 "$SCRIPT" hobby-self-hosted >/tmp/firemud-preflight-contract-traffic.out 2>&1 && {
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

FIREMUD_PREFLIGHT_CONTEXT=ci-static \
  FIREMUD_DEPLOYMENT_REF="contract-production" \
  FIREMUD_PREFLIGHT_OUTPUT="$PRODUCTION_REPORT" \
  python3 "$SCRIPT" production >/tmp/firemud-preflight-contract-production-traffic.out

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
  --output "$LEGACY_PRODUCTION_TRAFFIC_EVIDENCE" >/tmp/firemud-preflight-write-traffic-production.out 2>&1; then
  echo "legacy production traffic-open writer unexpectedly succeeded" >&2
  exit 1
fi
grep -Fq "invalid choice: 'production'" /tmp/firemud-preflight-write-traffic-production.out

if FIREMUD_PREFLIGHT_CONTEXT=ci-static \
  FIREMUD_DEPLOYMENT_REF="contract-production" \
  FIREMUD_PREFLIGHT_OUTPUT="$PRODUCTION_REPORT" \
  FIREMUD_TRAFFIC_OPEN_EVENT=reopen \
  python3 "$SCRIPT" production >/tmp/firemud-preflight-contract-production-traffic-gated.out 2>&1; then
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
  python3 "$SCRIPT" production >/tmp/firemud-preflight-contract-production-traffic-waiver.out 2>&1; then
  echo "production traffic-open gate unexpectedly accepted a waiver" >&2
  exit 1
fi

if ! grep -q "waiver execution remains blocked" /tmp/firemud-preflight-contract-production-traffic-waiver.out; then
  echo "production waiver failed for the wrong reason" >&2
  cat /tmp/firemud-preflight-contract-production-traffic-waiver.out >&2
  exit 1
fi
if [[ -e "$PRODUCTION_REPORT" ]]; then
  echo "blocked waiver unexpectedly produced an authoritative report" >&2
  exit 1
fi

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
        "backupArtifactLineage": {
            "databaseIdentity": "production",
            "snapshotAt": credential_validated_at,
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
}
for case_name, replacement in invalid_baseline_cases.items():
    invalid_baseline = {**valid_baseline, **replacement}
    invalid_path = recovery_dir / f"invalid-{case_name}-baseline.json"
    invalid_path.write_text(json.dumps(invalid_baseline), encoding="utf-8")
    invalid_status, invalid_message = module.validate_recovery_baseline(
        tmp,
        str(invalid_path.relative_to(tmp)),
        "sha256:recovery-contract",
        now,
        now,
    )
    if invalid_status != "fail":
        raise SystemExit(f"invalid {case_name} recovery baseline was accepted: {invalid_message}")

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

future_evaluation = compatibility_result("compatible")
future_evaluation["evaluatedAt"] = timestamp(now - module.dt.timedelta(minutes=1))
future_evaluation_attestation = promotion_attestation(future_evaluation)
future_evaluation_attestation["generatedAt"] = timestamp(now - module.dt.timedelta(minutes=2))
future_evaluation_path = write_json(
    "future-recovery-evaluation-attestation.json",
    future_evaluation_attestation,
)
future_status, _, future_message, _, _ = module.promotion_check(future_evaluation_path, [], tmp)
if future_status != "fail" or "must not be after attestation generatedAt" not in future_message:
    raise SystemExit(
        f"future-dated recovery compatibility evaluation did not fail closed: {future_message}"
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
            "startedAt": past_timestamp,
            "completedAt": past_timestamp,
            "toolVersion": "preflight.py-v1",
            "context": "operator",
            "checkResults": [
                {
                    "policyId": policy_id,
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
