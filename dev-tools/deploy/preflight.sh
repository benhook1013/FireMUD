#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat >&2 <<'USAGE'
Usage: preflight.sh <staging|production|hobby-self-hosted>

Environment variables:
  FIREMUD_PREFLIGHT_CONTEXT          Context for applicability (default: operator)
                                     allowed: operator, ci-static
  FIREMUD_PREFLIGHT_OUTPUT           Optional output report path
  FIREMUD_DEPLOYMENT_REF             Optional deployment ref token for report naming
  FIREMUD_PREFLIGHT_RENDER_PATH      Required for hobby-self-hosted; explicit manifest/render path
  FIREMUD_PREFLIGHT_WAIVER           Optional waiver JSON path with fields:
                                     approver, ticket, waivedPolicyIds[]
  FIREMUD_PROMOTION_ATTESTATION      Required in operator production context; path to attestation JSON
  FIREMUD_BACKUP_READINESS_EVIDENCE  Required for production roll-forward-only promotions; path to backup-readiness JSON
USAGE
  exit 1
}

[ $# -eq 1 ] || usage
ENV_CLASS="$1"
case "$ENV_CLASS" in
  staging|production|hobby-self-hosted) ;;
  *) usage ;;
esac

ROOT_DIR="$(git rev-parse --show-toplevel)"
CONTEXT="${FIREMUD_PREFLIGHT_CONTEXT:-operator}"
case "$CONTEXT" in
  operator|ci-static) ;;
  *) echo "Invalid FIREMUD_PREFLIGHT_CONTEXT: $CONTEXT" >&2; exit 1 ;;
esac
DEPLOYMENT_REF="${FIREMUD_DEPLOYMENT_REF:-$(git rev-parse --short=12 HEAD)}"

RENDERED=""
if [ "$ENV_CLASS" = "hobby-self-hosted" ]; then
  if [ -z "${FIREMUD_PREFLIGHT_RENDER_PATH:-}" ]; then
    echo "FIREMUD_PREFLIGHT_RENDER_PATH is required for hobby-self-hosted preflight." >&2
    exit 1
  fi
  RENDER_PATH="${FIREMUD_PREFLIGHT_RENDER_PATH}"
  case "$RENDER_PATH" in
    /*) ;;
    *) RENDER_PATH="$ROOT_DIR/$RENDER_PATH" ;;
  esac
  if [ -d "$RENDER_PATH" ]; then
    RENDERED="$(kubectl kustomize "$RENDER_PATH")"
  elif [ -f "$RENDER_PATH" ]; then
    RENDERED="$(cat "$RENDER_PATH")"
  else
    echo "FIREMUD_PREFLIGHT_RENDER_PATH does not exist: $RENDER_PATH" >&2
    exit 1
  fi
else
  OVERLAY_PATH="$ROOT_DIR/k8s/overlays/${ENV_CLASS/staging/stage}"
  RENDERED="$(kubectl kustomize "$OVERLAY_PATH")"
fi

WAIVER_PATH="${FIREMUD_PREFLIGHT_WAIVER:-}"
WAIVED_IDS=""
WAIVER_APPROVER=""
WAIVER_TICKET=""
if [ -n "$WAIVER_PATH" ]; then
  if [ ! -f "$WAIVER_PATH" ]; then
    echo "Waiver path does not exist: $WAIVER_PATH" >&2
    exit 1
  fi
  WAIVED_IDS="$(python3 - <<'PY' "$WAIVER_PATH"
import json,sys
p=sys.argv[1]
obj=json.load(open(p,'r',encoding='utf-8'))
print(','.join(obj.get('waivedPolicyIds',[])))
print(obj.get('approver',''))
print(obj.get('ticket',''))
PY
)"
  WAIVER_APPROVER="$(printf '%s\n' "$WAIVED_IDS" | sed -n '2p')"
  WAIVER_TICKET="$(printf '%s\n' "$WAIVED_IDS" | sed -n '3p')"
  WAIVED_IDS="$(printf '%s\n' "$WAIVED_IDS" | sed -n '1p')"
fi

is_waived() {
  local policy_id="$1"
  [ -n "$WAIVED_IDS" ] && printf '%s' "$WAIVED_IDS" | tr ',' '\n' | grep -Fxq "$policy_id"
}

json_escape() {
  python3 - <<'PY' "$1"
import json,sys
print(json.dumps(sys.argv[1]))
PY
}

CHECK_RESULTS=""
HAS_REQUIRED_FAILURE=0
STARTED_AT="$(date -u +%Y-%m-%dT%H:%M:%SZ)"

append_result() {
  local policy_id="$1"
  local required="$2"
  local status="$3"
  local message="$4"
  local effective_status="$status"

  if [ "$status" = "fail" ] && is_waived "$policy_id"; then
    effective_status="pass"
    message="waived by ${WAIVER_APPROVER:-unknown} (${WAIVER_TICKET:-no-ticket}): $message"
  fi

  if [ "$required" = "true" ] && [ "$effective_status" = "fail" ]; then
    HAS_REQUIRED_FAILURE=1
  fi

  [ -n "$CHECK_RESULTS" ] && CHECK_RESULTS+=$'\n'
  CHECK_RESULTS+="$(printf '{\"policyId\":%s,\"status\":%s,\"message\":%s}' \
    "$(json_escape "$policy_id")" \
    "$(json_escape "$effective_status")" \
    "$(json_escape "$message")")"
}

# PREFLIGHT-DIGEST-001 / PREFLIGHT-DIGEST-002
SERVICE_IMAGES="$(printf '%s\n' "$RENDERED" | grep -E '^[[:space:]]*image:[[:space:]]*ghcr\.io/benhook1013/.+-service' | sed -E 's/^[[:space:]]*image:[[:space:]]*//' | awk '{print $1}' | sort -u || true)"
if [ "$ENV_CLASS" = "hobby-self-hosted" ]; then
  if [ -z "$SERVICE_IMAGES" ]; then
    append_result "PREFLIGHT-DIGEST-002" "false" "not_applicable" "No workload images found for hobby manifest rendering"
  elif printf '%s\n' "$SERVICE_IMAGES" | grep -vq '@sha256:'; then
    append_result "PREFLIGHT-DIGEST-002" "false" "fail" "One or more hobby workload images are not digest-pinned"
  else
    append_result "PREFLIGHT-DIGEST-002" "false" "pass" "All hobby workload images are digest-pinned"
  fi
  append_result "PREFLIGHT-DIGEST-001" "false" "not_applicable" "Overlay digest policy does not apply to hobby deployments"
else
  if [ -z "$SERVICE_IMAGES" ]; then
    append_result "PREFLIGHT-DIGEST-001" "true" "fail" "No workload images found in rendered overlay"
  elif printf '%s\n' "$SERVICE_IMAGES" | grep -vq '@sha256:'; then
    append_result "PREFLIGHT-DIGEST-001" "true" "fail" "Staging/production overlay contains non-digest service image references"
  else
    append_result "PREFLIGHT-DIGEST-001" "true" "pass" "All rendered workload images are digest-pinned"
  fi
  append_result "PREFLIGHT-DIGEST-002" "false" "not_applicable" "Hobby digest advisory does not apply to overlay deployment"
fi

# PREFLIGHT-SECRETS-001
secret_check_failed=0
for secret_name in postgres-credentials jwt-signing-keys jwt-jwks; do
  if ! printf '%s\n' "$RENDERED" | grep -Eq "kind:[[:space:]]*Secret" || ! printf '%s\n' "$RENDERED" | grep -Eq "name:[[:space:]]*$secret_name([[:space:]]|$)"; then
    append_result "PREFLIGHT-SECRETS-001" "true" "fail" "Missing required Secret in rendered manifests: $secret_name"
    secret_check_failed=1
    break
  fi
done
if [ "${secret_check_failed:-0}" -eq 0 ]; then
  append_result "PREFLIGHT-SECRETS-001" "true" "pass" "Required player-facing Secrets are present"
fi

# PREFLIGHT-JWT-001
if printf '%s\n' "$RENDERED" | grep -q 'FIREMUD_AUTH_JWT_SECRET:'; then
  append_result "PREFLIGHT-JWT-001" "true" "fail" "Inline JWT secret material detected in rendered manifests"
elif ! printf '%s\n' "$RENDERED" | grep -q 'FIREMUD_AUTH_JWT_SECRET_PATH'; then
  append_result "PREFLIGHT-JWT-001" "true" "fail" "FIREMUD_AUTH_JWT_SECRET_PATH is not configured in rendered manifests"
else
  append_result "PREFLIGHT-JWT-001" "true" "pass" "JWT file-path contract is satisfied"
fi

# PREFLIGHT-JWKS-001
if printf '%s\n' "$RENDERED" | grep -Eq 'kind:[[:space:]]*ConfigMap' && printf '%s\n' "$RENDERED" | grep -Eq 'name:[[:space:]]*jwt-jwks([[:space:]]|$)'; then
  append_result "PREFLIGHT-JWKS-001" "true" "fail" "jwt-jwks is configured as a ConfigMap in player-facing context"
elif printf '%s\n' "$RENDERED" | grep -Eq 'kind:[[:space:]]*Secret' && printf '%s\n' "$RENDERED" | grep -Eq 'name:[[:space:]]*jwt-jwks([[:space:]]|$)'; then
  append_result "PREFLIGHT-JWKS-001" "true" "pass" "jwt-jwks Secret contract is satisfied"
else
  append_result "PREFLIGHT-JWKS-001" "true" "fail" "No jwt-jwks Secret found in rendered manifests"
fi

# PREFLIGHT-BRIDGE-001
GW_LINE="$(printf '%s\n' "$RENDERED" | grep -E 'name:[[:space:]]*GATEWAY_WS_URL' -A 1 | grep -E 'value:[[:space:]]*' | head -n1 || true)"
GW_VALUE="$(printf '%s' "$GW_LINE" | sed -E 's/^[[:space:]]*value:[[:space:]]*//')"
if [ -z "$GW_VALUE" ]; then
  append_result "PREFLIGHT-BRIDGE-001" "true" "fail" "GATEWAY_WS_URL is not explicitly configured"
elif [[ "$GW_VALUE" != wss://* ]]; then
  append_result "PREFLIGHT-BRIDGE-001" "true" "fail" "GATEWAY_WS_URL must use wss:// in player-facing environments"
elif [[ "$GW_VALUE" != *"spring-cloud-gateway-mtls"* ]]; then
  append_result "PREFLIGHT-BRIDGE-001" "true" "fail" "GATEWAY_WS_URL does not target the internal gateway mTLS listener"
else
  append_result "PREFLIGHT-BRIDGE-001" "true" "pass" "Gateway bridge alignment is valid"
fi

# PREFLIGHT-REDIS-001
COORD_HOST="$(printf '%s\n' "$RENDERED" | grep -E 'FIREMUD_REDIS_COORD_HOST:' | head -n1 | sed -E 's/.*FIREMUD_REDIS_COORD_HOST:[[:space:]]*//' | tr -d '"' || true)"
COORD_PORT="$(printf '%s\n' "$RENDERED" | grep -E 'FIREMUD_REDIS_COORD_PORT:' | head -n1 | sed -E 's/.*FIREMUD_REDIS_COORD_PORT:[[:space:]]*//' | tr -d '"' || true)"
CACHE_HOST="$(printf '%s\n' "$RENDERED" | grep -E 'FIREMUD_REDIS_CACHE_HOST:' | head -n1 | sed -E 's/.*FIREMUD_REDIS_CACHE_HOST:[[:space:]]*//' | tr -d '"' || true)"
CACHE_PORT="$(printf '%s\n' "$RENDERED" | grep -E 'FIREMUD_REDIS_CACHE_PORT:' | head -n1 | sed -E 's/.*FIREMUD_REDIS_CACHE_PORT:[[:space:]]*//' | tr -d '"' || true)"
if [ -z "$COORD_HOST" ] || [ -z "$CACHE_HOST" ]; then
  append_result "PREFLIGHT-REDIS-001" "true" "fail" "Could not resolve both Coordination and Cache Redis endpoints"
elif [ "$COORD_HOST:$COORD_PORT" = "$CACHE_HOST:$CACHE_PORT" ]; then
  append_result "PREFLIGHT-REDIS-001" "true" "fail" "Coordination and Cache Redis endpoints resolve to the same host:port"
else
  append_result "PREFLIGHT-REDIS-001" "true" "pass" "Redis role split contract is satisfied"
fi

# PREFLIGHT-PROMOTION-001
ROLLBACK_MODE=""
if [ "$ENV_CLASS" != "production" ]; then
  append_result "PREFLIGHT-PROMOTION-001" "false" "not_applicable" "Promotion attestation applies only to production"
  append_result "PREFLIGHT-BACKUP-001" "false" "not_applicable" "Backup readiness applies only to production roll-forward-only promotions"
elif [ "$CONTEXT" = "ci-static" ] && [ -z "${FIREMUD_PROMOTION_ATTESTATION:-}" ]; then
  append_result "PREFLIGHT-PROMOTION-001" "false" "not_applicable" "Static CI validation without production attestation context"
  append_result "PREFLIGHT-BACKUP-001" "false" "not_applicable" "Static CI validation without production attestation context"
else
  if [ -z "${FIREMUD_PROMOTION_ATTESTATION:-}" ]; then
    append_result "PREFLIGHT-PROMOTION-001" "true" "fail" "FIREMUD_PROMOTION_ATTESTATION is required for production operator preflight"
    append_result "PREFLIGHT-BACKUP-001" "false" "not_applicable" "Promotion attestation missing"
  elif [ ! -f "$FIREMUD_PROMOTION_ATTESTATION" ]; then
    append_result "PREFLIGHT-PROMOTION-001" "true" "fail" "Attestation file not found: $FIREMUD_PROMOTION_ATTESTATION"
    append_result "PREFLIGHT-BACKUP-001" "false" "not_applicable" "Promotion attestation missing"
  else
    PROMOTION_RESULT="$(python3 - <<'PY' "$FIREMUD_PROMOTION_ATTESTATION" "$SERVICE_IMAGES" "$ROOT_DIR"
import json
import pathlib
import sys

att_path = pathlib.Path(sys.argv[1])
images = [i for i in sys.argv[2].splitlines() if i.strip()]
root_dir = pathlib.Path(sys.argv[3])

try:
    att = json.loads(att_path.read_text(encoding="utf-8"))
except Exception as exc:
    print(f"fail\tunknown\tAttestation unreadable: {exc}")
    raise SystemExit(0)

if att.get("environment") != "staging":
    print("fail\tunknown\tAttestation environment must be staging")
    raise SystemExit(0)

rollback_mode = str(att.get("rollbackMode", "unknown"))
if rollback_mode not in {"rollback-compatible", "roll-forward-only"}:
    print("fail\tunknown\tAttestation rollbackMode is missing or invalid")
    raise SystemExit(0)

service_digests = att.get("serviceDigests", {})
for image in images:
    name = image.split("/")[-1].split("@")[0].split(":")[0]
    expected = service_digests.get(name)
    if not expected:
        print(f"fail\t{rollback_mode}\tMissing digest for service {name} in attestation")
        raise SystemExit(0)
    if expected != image:
        print(f"fail\t{rollback_mode}\tDigest mismatch for service {name}")
        raise SystemExit(0)

staging_sha = att.get("stagingOverlayCommitSha", "")
if not staging_sha:
    print(f"fail\t{rollback_mode}\tAttestation missing stagingOverlayCommitSha")
    raise SystemExit(0)

record_path = root_dir / "design" / "operations" / "deployments" / "staging" / "deployments" / f"{staging_sha}.json"
if not record_path.exists():
    print(f"fail\t{rollback_mode}\tStaging deployment record not found: {record_path}")
    raise SystemExit(0)

try:
    record = json.loads(record_path.read_text(encoding="utf-8"))
except Exception as exc:
    print(f"fail\t{rollback_mode}\tStaging deployment record unreadable: {exc}")
    raise SystemExit(0)

if record.get("environment") != "staging":
    print(f"fail\t{rollback_mode}\tStaging deployment record has wrong environment")
    raise SystemExit(0)
if record.get("overlayCommitSha") != staging_sha:
    print(f"fail\t{rollback_mode}\tStaging deployment record overlayCommitSha mismatch")
    raise SystemExit(0)

record_digests = record.get("serviceDigests", {})
for name, expected in service_digests.items():
    if record_digests.get(name) != expected:
        print(f"fail\t{rollback_mode}\tStaging deployment record digest mismatch for {name}")
        raise SystemExit(0)

if record.get("deployStatus") != "pass":
    print(f"fail\t{rollback_mode}\tStaging deployment record deployStatus must be pass")
    raise SystemExit(0)
if record.get("smokeStatus") != "pass":
    print(f"fail\t{rollback_mode}\tStaging deployment record smokeStatus must be pass")
    raise SystemExit(0)
if not record.get("smokeEvidence"):
    print(f"fail\t{rollback_mode}\tStaging deployment record missing smokeEvidence")
    raise SystemExit(0)
if not record.get("secretComplianceSnapshotAt"):
    print(f"fail\t{rollback_mode}\tStaging deployment record missing secretComplianceSnapshotAt")
    raise SystemExit(0)

live_state = record.get("liveStateEvidence")
if not isinstance(live_state, dict):
    print(f"fail\t{rollback_mode}\tStaging deployment record missing liveStateEvidence")
    raise SystemExit(0)
if live_state.get("status") != "pass":
    print(f"fail\t{rollback_mode}\tStaging deployment record liveStateEvidence must be pass")
    raise SystemExit(0)
if not live_state.get("observedOverlaySha") or live_state.get("observedOverlaySha") != staging_sha:
    print(f"fail\t{rollback_mode}\tStaging deployment record liveStateEvidence overlay SHA mismatch")
    raise SystemExit(0)
if not live_state.get("observedDigests"):
    print(f"fail\t{rollback_mode}\tStaging deployment record missing observedDigests")
    raise SystemExit(0)
observed_digests = live_state.get("observedDigests", {})
for name, expected in service_digests.items():
    if observed_digests.get(name) != expected:
        print(f"fail\t{rollback_mode}\tStaging live-state evidence digest mismatch for {name}")
        raise SystemExit(0)

secret_status = record.get("secretComplianceStatus")
secret_ref = record.get("secretComplianceEvidenceRef")
if secret_status != "pass":
    print(f"fail\t{rollback_mode}\tStaging deployment record secretComplianceStatus must be pass")
    raise SystemExit(0)
if not secret_ref:
    print(f"fail\t{rollback_mode}\tStaging deployment record missing secretComplianceEvidenceRef")
    raise SystemExit(0)
secret_path = root_dir / secret_ref
if not secret_path.exists():
    print(f"fail\t{rollback_mode}\tStaging secret compliance evidence not found: {secret_ref}")
    raise SystemExit(0)
try:
    secret_evidence = json.loads(secret_path.read_text(encoding="utf-8"))
except Exception as exc:
    print(f"fail\t{rollback_mode}\tStaging secret compliance evidence unreadable: {exc}")
    raise SystemExit(0)
required_secret_classes = {
    "jwt-signing-keys-jwks",
    "postgres-application-credentials",
    "backup-object-store-credentials",
    "operator-credentials",
}
records = (secret_evidence or {}).get("records", {})
for key in required_secret_classes:
    rec = records.get(key)
    if not isinstance(rec, dict):
        print(f"fail\t{rollback_mode}\tStaging secret compliance evidence missing record: {key}")
        raise SystemExit(0)
    immutable_id = str(rec.get("immutableArtifactId", ""))
    if "sha256:" not in immutable_id:
        print(f"fail\t{rollback_mode}\tStaging secret compliance evidence record is not immutable: {key}")
        raise SystemExit(0)

print(f"pass\t{rollback_mode}\tProduction promotion attestation and staging deployment evidence are valid")
PY
)"
    PROMOTION_STATUS="$(printf '%s\n' "$PROMOTION_RESULT" | cut -f1)"
    ROLLBACK_MODE="$(printf '%s\n' "$PROMOTION_RESULT" | cut -f2)"
    PROMOTION_MESSAGE="$(printf '%s\n' "$PROMOTION_RESULT" | cut -f3-)"
    if [ "$PROMOTION_STATUS" = "pass" ]; then
      append_result "PREFLIGHT-PROMOTION-001" "true" "pass" "$PROMOTION_MESSAGE"
    else
      append_result "PREFLIGHT-PROMOTION-001" "true" "fail" "$PROMOTION_MESSAGE"
    fi

    if [ "$ROLLBACK_MODE" != "roll-forward-only" ]; then
      append_result "PREFLIGHT-BACKUP-001" "false" "not_applicable" "Backup readiness is required only for roll-forward-only promotions"
    elif [ -z "${FIREMUD_BACKUP_READINESS_EVIDENCE:-}" ]; then
      append_result "PREFLIGHT-BACKUP-001" "true" "fail" "FIREMUD_BACKUP_READINESS_EVIDENCE is required for roll-forward-only promotions"
    elif [ ! -f "$FIREMUD_BACKUP_READINESS_EVIDENCE" ]; then
      append_result "PREFLIGHT-BACKUP-001" "true" "fail" "Backup-readiness evidence file not found: $FIREMUD_BACKUP_READINESS_EVIDENCE"
    else
      BACKUP_RESULT="$(python3 - <<'PY' "$FIREMUD_BACKUP_READINESS_EVIDENCE" "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$DEPLOYMENT_REF" "$ROOT_DIR"
import datetime as dt
import json
import pathlib
import sys

path = pathlib.Path(sys.argv[1])
now = dt.datetime.fromisoformat(sys.argv[2].replace("Z", "+00:00"))
deployment_ref = sys.argv[3]
root_dir = pathlib.Path(sys.argv[4])

try:
    data = json.loads(path.read_text(encoding="utf-8"))
except Exception as exc:
    print(f"fail\tBackup-readiness evidence unreadable: {exc}")
    raise SystemExit(0)

if data.get("environment") != "production":
    print("fail\tBackup-readiness evidence must target production")
    raise SystemExit(0)
if data.get("rollbackMode") != "roll-forward-only":
    print("fail\tBackup-readiness evidence rollbackMode must be roll-forward-only")
    raise SystemExit(0)
if not data.get("deploymentRef"):
    print("fail\tBackup-readiness evidence missing deploymentRef")
    raise SystemExit(0)
if deployment_ref and str(data.get("deploymentRef")) != str(deployment_ref):
    print("fail\tBackup-readiness evidence deploymentRef does not match the current deployment")
    raise SystemExit(0)
attestation_ref = str(data.get("promotionAttestationRef", ""))
if not attestation_ref:
    print("fail\tBackup-readiness evidence missing promotionAttestationRef")
    raise SystemExit(0)
attestation_path = (root_dir / attestation_ref).resolve()
if not attestation_path.exists():
    print("fail\tBackup-readiness evidence references missing promotionAttestationRef")
    raise SystemExit(0)
if not data.get("restorePlanRef"):
    print("fail\tBackup-readiness evidence missing restorePlanRef")
    raise SystemExit(0)
if not data.get("evidenceRefs"):
    print("fail\tBackup-readiness evidence missing evidenceRefs")
    raise SystemExit(0)
service_digests = data.get("serviceDigests")
if not isinstance(service_digests, dict) or not service_digests:
    print("fail\tBackup-readiness evidence missing serviceDigests")
    raise SystemExit(0)

def parse_ts(name):
    value = data.get(name)
    if not value:
        raise ValueError(f"missing {name}")
    return dt.datetime.fromisoformat(str(value).replace("Z", "+00:00"))

try:
    backup_ts = parse_ts("backupLastSuccessAt")
    verify_ts = parse_ts("backupVerifyLastSuccessAt")
    drill_ts = parse_ts("restoreDrillLastSuccessAt")
except Exception as exc:
    print(f"fail\t{exc}")
    raise SystemExit(0)

if (now - backup_ts).total_seconds() > 90 * 60:
    print("fail\tBackup-readiness evidence is stale: backupLastSuccessAt older than 90 minutes")
    raise SystemExit(0)
if (now - verify_ts).total_seconds() > 36 * 60 * 60:
    print("fail\tBackup-readiness evidence is stale: backupVerifyLastSuccessAt older than 36 hours")
    raise SystemExit(0)
if (now - drill_ts).total_seconds() > 30 * 24 * 60 * 60:
    print("fail\tBackup-readiness evidence is stale: restoreDrillLastSuccessAt older than 30 days")
    raise SystemExit(0)

try:
    attestation = json.loads(attestation_path.read_text(encoding="utf-8"))
except Exception as exc:
    print(f"fail\tBackup-readiness attestation unreadable: {exc}")
    raise SystemExit(0)
if attestation.get("rollbackMode") != "roll-forward-only":
    print("fail\tBackup-readiness evidence does not match a roll-forward-only attestation")
    raise SystemExit(0)
if attestation.get("serviceDigests") != service_digests:
    print("fail\tBackup-readiness evidence serviceDigests do not match the attestation")
    raise SystemExit(0)

print("pass\tBackup-readiness evidence is valid for roll-forward-only promotion")
PY
)"
      BACKUP_STATUS="$(printf '%s\n' "$BACKUP_RESULT" | cut -f1)"
      BACKUP_MESSAGE="$(printf '%s\n' "$BACKUP_RESULT" | cut -f2-)"
      if [ "$BACKUP_STATUS" = "pass" ]; then
        append_result "PREFLIGHT-BACKUP-001" "true" "pass" "$BACKUP_MESSAGE"
      else
        append_result "PREFLIGHT-BACKUP-001" "true" "fail" "$BACKUP_MESSAGE"
      fi
    fi
  fi
fi

COMPLETED_AT="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
DEFAULT_OUTPUT="$ROOT_DIR/design/operations/deployments/$ENV_CLASS/preflight/$DEPLOYMENT_REF.json"
OUTPUT_PATH="${FIREMUD_PREFLIGHT_OUTPUT:-$DEFAULT_OUTPUT}"
mkdir -p "$(dirname "$OUTPUT_PATH")"

CHECKS_JSON="[$(printf '%s\n' "$CHECK_RESULTS" | paste -sd ',' -)]"

python3 - <<'PY' "$OUTPUT_PATH" "$ENV_CLASS" "$DEPLOYMENT_REF" "$STARTED_AT" "$COMPLETED_AT" "$CHECKS_JSON" "$CONTEXT" "$WAIVER_PATH"
import json,sys,pathlib
out=pathlib.Path(sys.argv[1])
env_class = sys.argv[2]
deployment_ref = sys.argv[3]
if env_class == "hobby-self-hosted":
  deployment_ref_obj = {"manifestRef": deployment_ref}
else:
  deployment_ref_obj = {"overlayCommitSha": deployment_ref}
report={
  "environment": env_class,
  "deploymentRef": deployment_ref_obj,
  "startedAt": sys.argv[4],
  "completedAt": sys.argv[5],
  "checkResults": json.loads(sys.argv[6]),
  "toolVersion": "preflight.sh-v1",
  "context": sys.argv[7],
}
if sys.argv[8]:
  report["waiverPath"] = sys.argv[8]
out.write_text(json.dumps(report, indent=2) + "\n", encoding='utf-8')
print(str(out))
PY

if [ "$HAS_REQUIRED_FAILURE" -ne 0 ]; then
  echo "Preflight failed; report written to: $OUTPUT_PATH" >&2
  exit 1
fi

echo "Preflight passed; report written to: $OUTPUT_PATH"
