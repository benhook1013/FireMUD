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
  FIREMUD_PREFLIGHT_WAIVER           Optional waiver JSON path with fields:
                                     approver, ticket, waivedPolicyIds[]
  FIREMUD_PROMOTION_ATTESTATION      Required in operator production context; path to attestation JSON
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

RENDERED=""
if [ "$ENV_CLASS" = "hobby-self-hosted" ]; then
  # Hobby deployments may use charts/manifests outside the kustomize overlays.
  # For consistency, default to stage overlay rendering when no explicit manifests are provided.
  RENDERED="$(kubectl kustomize "$ROOT_DIR/k8s/overlays/stage")"
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
if [ "$ENV_CLASS" != "production" ]; then
  append_result "PREFLIGHT-PROMOTION-001" "false" "not_applicable" "Promotion attestation applies only to production"
elif [ "$CONTEXT" = "ci-static" ] && [ -z "${FIREMUD_PROMOTION_ATTESTATION:-}" ]; then
  append_result "PREFLIGHT-PROMOTION-001" "false" "not_applicable" "Static CI validation without production attestation context"
else
  if [ -z "${FIREMUD_PROMOTION_ATTESTATION:-}" ]; then
    append_result "PREFLIGHT-PROMOTION-001" "true" "fail" "FIREMUD_PROMOTION_ATTESTATION is required for production operator preflight"
  elif [ ! -f "$FIREMUD_PROMOTION_ATTESTATION" ]; then
    append_result "PREFLIGHT-PROMOTION-001" "true" "fail" "Attestation file not found: $FIREMUD_PROMOTION_ATTESTATION"
  else
    if python3 - <<'PY' "$FIREMUD_PROMOTION_ATTESTATION" "$SERVICE_IMAGES"; then
import json,sys
att_path=sys.argv[1]
images=[i for i in sys.argv[2].splitlines() if i.strip()]
att=json.load(open(att_path,'r',encoding='utf-8'))
if att.get('environment')!='staging':
    raise SystemExit(2)
service_digests=att.get('serviceDigests',{})
for image in images:
    name=image.split('/')[-1].split('@')[0].split(':')[0]
    expected=service_digests.get(name)
    if expected and expected!=image:
        raise SystemExit(3)
raise SystemExit(0)
PY
      append_result "PREFLIGHT-PROMOTION-001" "true" "pass" "Production promotion attestation is present and structurally valid"
    else
      append_result "PREFLIGHT-PROMOTION-001" "true" "fail" "Attestation content is invalid or digest-mismatched"
    fi
  fi
fi

COMPLETED_AT="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
DEPLOYMENT_REF="${FIREMUD_DEPLOYMENT_REF:-$(git rev-parse --short=12 HEAD)}"
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
