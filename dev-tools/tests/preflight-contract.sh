#!/usr/bin/env bash
# Contract checks for dev-tools/deploy/preflight.sh report shape and policy IDs.
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SCRIPT="$ROOT_DIR/dev-tools/deploy/preflight.sh"
TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

RENDERED_MANIFEST="$TMP_DIR/hobby-rendered.yaml"
REPORT_PATH="$TMP_DIR/preflight-report.json"
TRAFFIC_EVIDENCE="$TMP_DIR/traffic-open.json"

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
    "internalBindings.certificates.backupControlPlaneClientRef",
    "internalBindings.registry.imagePullSecretRef",
    "backupStorage.bucket",
    "backupStorage.bindingRef",
    "assetStorage.bucket",
    "assetStorage.bindingRef",
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
apiVersion: apps/v1
kind: Deployment
metadata:
  name: tcp-proxy-service
spec:
  template:
    spec:
      containers:
        - name: tcp-proxy-service
          image: ghcr.io/benhook1013/tcp-proxy-service:latest
          env:
            - name: GATEWAY_WS_URL
              value: wss://spring-cloud-gateway-mtls.firemud.svc.cluster.local/ws/game
          envFrom:
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
      containers:
        - name: account-service
          image: ghcr.io/benhook1013/account-service:latest
          envFrom:
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
  bash "$SCRIPT" hobby-self-hosted >/tmp/firemud-preflight-contract.out

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

cat >"$TRAFFIC_EVIDENCE" <<'JSON'
{
  "schemaVersion": "traffic-open-record/v1",
  "environment": "hobby-self-hosted",
  "eventType": "first-live",
  "deploymentRef": "contract-hobby",
  "assessedAt": "2026-04-22T00:00:00Z",
  "assessedBy": "preflight-contract",
  "backupComplianceRef": "design/operations/deployments/hobby-self-hosted/backup-compliance.yaml",
  "preflightReportPath": "design/operations/deployments/hobby-self-hosted/preflight/contract-hobby.json",
  "evidenceRefs": [
    "contract-test"
  ]
}
JSON

FIREMUD_PREFLIGHT_CONTEXT=ci-static \
  FIREMUD_DEPLOYMENT_REF=contract-hobby \
  FIREMUD_PREFLIGHT_RENDER_PATH="$RENDERED_MANIFEST" \
  FIREMUD_PREFLIGHT_OUTPUT="$REPORT_PATH" \
  FIREMUD_TRAFFIC_OPEN_EVENT=first-live \
  FIREMUD_TRAFFIC_OPEN_EVIDENCE="$TRAFFIC_EVIDENCE" \
  bash "$SCRIPT" hobby-self-hosted >/tmp/firemud-preflight-contract-traffic.out

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
if len(backup_003) != 1 or backup_003[0]["status"] != "pass":
    raise SystemExit(f"PREFLIGHT-BACKUP-003 did not pass: {backup_003}")
PY

echo "preflight contract checks passed"
