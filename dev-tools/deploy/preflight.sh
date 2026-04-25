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
  FIREMUD_TRAFFIC_OPEN_EVENT         Optional traffic-open gate: first-live or reopen
  FIREMUD_TRAFFIC_OPEN_EVIDENCE      Optional explicit traffic-open evidence path
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
EXPECTED_BINDINGS_REF="design/operations/environments/$ENV_CLASS/expected-bindings.yaml"
EXPECTED_BINDINGS_PATH="$ROOT_DIR/$EXPECTED_BINDINGS_REF"
if [ ! -f "$EXPECTED_BINDINGS_PATH" ]; then
  echo "Expected-bindings manifest not found: $EXPECTED_BINDINGS_PATH" >&2
  exit 1
fi

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
RENDERED_PATH="$(mktemp)"
trap 'rm -f "$RENDERED_PATH"' EXIT
printf '%s\n' "$RENDERED" >"$RENDERED_PATH"

rendered_has_resource() {
  local kind="$1"
  local name="$2"
  python3 - <<'PY' "$RENDERED_PATH" "$kind" "$name"
import pathlib
import sys
import yaml

path = pathlib.Path(sys.argv[1])
kind = sys.argv[2]
name = sys.argv[3]
for document in yaml.safe_load_all(path.read_text(encoding="utf-8")):
    if not isinstance(document, dict):
        continue
    metadata = document.get("metadata") or {}
    if document.get("kind") == kind and metadata.get("name") == name:
        raise SystemExit(0)
raise SystemExit(1)
PY
}

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
TRAFFIC_OPEN_EVENT="${FIREMUD_TRAFFIC_OPEN_EVENT:-}"
case "$TRAFFIC_OPEN_EVENT" in
  ""|first-live|reopen) ;;
  *) echo "Invalid FIREMUD_TRAFFIC_OPEN_EVENT: $TRAFFIC_OPEN_EVENT" >&2; exit 1 ;;
esac

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

EXPECTED_BINDING_RESULTS="$(python3 - <<'PY' "$EXPECTED_BINDINGS_PATH" "$EXPECTED_BINDINGS_REF" "$ENV_CLASS" "$RENDERED_PATH" "$CONTEXT"
import pathlib
import re
import sys

try:
    import yaml
except Exception as exc:
    print(f"PREFLIGHT-SECRETS-002\ttrue\tfail\tPyYAML is required to validate expected bindings: {exc}")
    raise SystemExit(0)

path = pathlib.Path(sys.argv[1])
ref = sys.argv[2]
env_class = sys.argv[3]
rendered = pathlib.Path(sys.argv[4]).read_text(encoding="utf-8")
context = sys.argv[5]

def result(policy_id, required, status, message):
    print(f"{policy_id}\t{str(required).lower()}\t{status}\t{message}")

def get(data, dotted):
    cur = data
    for part in dotted.split("."):
        if not isinstance(cur, dict) or part not in cur:
            return None
        cur = cur[part]
    return cur

try:
    data = yaml.safe_load(path.read_text(encoding="utf-8")) or {}
except Exception as exc:
    result("PREFLIGHT-SECRETS-002", True, "fail", f"Expected-bindings manifest is unreadable: {exc}")
    result("PREFLIGHT-BOOTSTRAP-001", True, "fail", "Expected-bindings manifest is unreadable")
    result("PREFLIGHT-EXTERNAL-001", True, "fail", "Expected-bindings manifest is unreadable")
    result("PREFLIGHT-SERVICES-001", True, "fail", "Expected-bindings manifest is unreadable")
    raise SystemExit(0)

if data.get("environment") != env_class:
    result("PREFLIGHT-SECRETS-002", True, "fail", f"Expected-bindings environment mismatch in {ref}")
else:
    required_internal = [
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
    ]
    missing = [key for key in required_internal if not get(data, key)]
    secret_refs = [
        get(data, "internalBindings.postgres.credentialsRef"),
        get(data, "internalBindings.jwt.signingKeysRef"),
        get(data, "internalBindings.jwt.jwksRef"),
    ]
    missing_rendered = []
    for ref_value in secret_refs:
        if isinstance(ref_value, str) and ref_value.startswith("secret://"):
            name = ref_value.rstrip("/").split("/")[-1]
            if not re.search(rf"name:\s*{re.escape(name)}(\s|$)", rendered):
                missing_rendered.append(name)
    if missing:
        result("PREFLIGHT-SECRETS-002", True, "fail", "Expected-bindings missing internal keys: " + ", ".join(missing))
    elif missing_rendered:
        result("PREFLIGHT-SECRETS-002", True, "fail", "Rendered manifests missing expected Secret bindings: " + ", ".join(missing_rendered))
    else:
        result("PREFLIGHT-SECRETS-002", True, "pass", f"Internal state/trust bindings match {ref}")

bootstrap_names = ["postgres-credentials", "jwt-signing-keys", "jwt-jwks"]
missing_bootstrap = [name for name in bootstrap_names if not re.search(rf"name:\s*{re.escape(name)}(\s|$)", rendered)]
if missing_bootstrap:
    result("PREFLIGHT-BOOTSTRAP-001", True, "fail", "Rendered manifests missing bootstrap resources: " + ", ".join(missing_bootstrap))
else:
    result("PREFLIGHT-BOOTSTRAP-001", True, "pass", "Minimum bootstrap secret/trust resources are present")

external_requirements = [
    ("backupStorage.bucket", None),
    ("backupStorage.bindingRef", "backupStorage.fingerprint"),
    ("assetStorage.bucket", None),
    ("assetStorage.endpoint", None),
    ("assetStorage.bindingRef", "assetStorage.fingerprint"),
    ("operatorCredentials.bindingRef", "operatorCredentials.fingerprint"),
]
missing_external = []
for primary, alternate in external_requirements:
    if not get(data, primary) and (alternate is None or not get(data, alternate)):
        missing_external.append(primary if alternate is None else f"{primary} or {alternate}")
if missing_external:
    result("PREFLIGHT-EXTERNAL-001", True, "fail", "Expected-bindings missing external binding keys: " + ", ".join(missing_external))
else:
    values = [
        str(get(data, "backupStorage.bucket")),
        str(get(data, "assetStorage.bucket")),
        str(get(data, "operatorCredentials.bindingRef") or get(data, "operatorCredentials.fingerprint")),
    ]
    env_tokens = [env_class]
    if env_class == "hobby-self-hosted":
        env_tokens.append("hobby")
    if any(not any(token in value for token in env_tokens) for value in values):
        result("PREFLIGHT-EXTERNAL-001", True, "fail", "External binding values do not consistently identify the target environment")
    else:
        result("PREFLIGHT-EXTERNAL-001", True, "pass", f"External bindings are environment-scoped in {ref}")

mode = get(data, "serviceDiscovery.mode")
override_lines = re.findall(r"FIREMUD_SERVICES_[A-Z0-9_]+:\s*([^\n]+)", rendered)
if mode == "kubernetes-dns-default" and override_lines:
    result("PREFLIGHT-SERVICES-001", True, "fail", "Rendered manifests contain FIREMUD_SERVICES_* overrides while expected bindings require Kubernetes DNS defaults")
elif mode == "explicit-overrides":
    allowed = get(data, "serviceDiscovery.allowedOverrides")
    if not isinstance(allowed, dict) or not allowed:
        result("PREFLIGHT-SERVICES-001", True, "fail", "serviceDiscovery.allowedOverrides is required for explicit-overrides mode")
    else:
        result("PREFLIGHT-SERVICES-001", True, "pass", "Explicit service-discovery overrides are declared in expected bindings")
elif mode == "kubernetes-dns-default":
    result("PREFLIGHT-SERVICES-001", True, "pass", "Rendered manifests use default in-environment service discovery")
else:
    result("PREFLIGHT-SERVICES-001", True, "fail", "serviceDiscovery.mode must be kubernetes-dns-default or explicit-overrides")
PY
)"
while IFS=$'\t' read -r policy_id required status message; do
  [ -z "$policy_id" ] && continue
  append_result "$policy_id" "$required" "$status" "$message"
done <<<"$EXPECTED_BINDING_RESULTS"

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
  if ! rendered_has_resource Secret "$secret_name"; then
    append_result "PREFLIGHT-SECRETS-001" "true" "fail" "Missing required Secret in rendered manifests: $secret_name"
    secret_check_failed=1
    break
  fi
done
if [ "${secret_check_failed:-0}" -eq 0 ]; then
  append_result "PREFLIGHT-SECRETS-001" "true" "pass" "Required player-facing Secrets are present"
fi

JWT_JWKS_RESULTS="$(python3 - <<'PY' "$RENDERED_PATH"
import pathlib
import sys
import yaml

path = pathlib.Path(sys.argv[1])
documents = [
    document
    for document in yaml.safe_load_all(path.read_text(encoding="utf-8"))
    if isinstance(document, dict)
]

def result(policy_id, status, message):
    print(f"{policy_id}\ttrue\t{status}\t{message}")

def metadata_name(document):
    metadata = document.get("metadata") or {}
    return metadata.get("name")

def has_resource(kind, name):
    return any(document.get("kind") == kind and metadata_name(document) == name for document in documents)

def config_value(name):
    for document in documents:
        if document.get("kind") != "ConfigMap":
            continue
        data = document.get("data") or {}
        if name in data:
            return str(data[name])
    return None

def env_value(container, name):
    for entry in container.get("env") or []:
        if entry.get("name") == name:
            return entry.get("value")
    return None

def primary_containers(document):
    if document.get("kind") not in {"Deployment", "StatefulSet", "DaemonSet"}:
        return []
    spec = (((document.get("spec") or {}).get("template") or {}).get("spec") or {})
    volumes = {
        volume.get("name"): ((volume.get("secret") or {}).get("secretName"))
        for volume in spec.get("volumes") or []
        if isinstance(volume, dict)
    }
    containers = []
    for container in spec.get("containers") or []:
        name = container.get("name") or ""
        if name.endswith("-service") or name == "spring-cloud-gateway":
            containers.append((metadata_name(document), container, volumes))
    return containers

def has_secret_mount(container, volumes, secret_name, required_mount):
    for mount in container.get("volumeMounts") or []:
        mounted_secret = volumes.get(mount.get("name"))
        mount_path = str(mount.get("mountPath") or "")
        if mounted_secret == secret_name and mount_path == required_mount:
            return True
    return False

inline_secret = False
missing_secret_path = []
missing_signing_mount = []
missing_jwks_mount = []
global_secret_path = config_value("FIREMUD_AUTH_JWT_SECRET_PATH")
global_jwks_path = config_value("FIREMUD_AUTH_JWKS_PATH")
for workload_name, container, volumes in [
    item for document in documents for item in primary_containers(document)
]:
    container_name = container.get("name") or "<unknown>"
    if env_value(container, "FIREMUD_AUTH_JWT_SECRET") is not None:
        inline_secret = True
    secret_path = env_value(container, "FIREMUD_AUTH_JWT_SECRET_PATH") or global_secret_path
    if not secret_path:
        missing_secret_path.append(f"{workload_name}/{container_name}")
    elif str(secret_path).startswith("/var/run/secrets/firemud/jwt/") and not has_secret_mount(
        container, volumes, "jwt-signing-keys", "/var/run/secrets/firemud/jwt"
    ):
        missing_signing_mount.append(f"{workload_name}/{container_name}")
    jwks_path = env_value(container, "FIREMUD_AUTH_JWKS_PATH") or global_jwks_path
    if (
        container_name == "account-service"
        and jwks_path
        and str(jwks_path).startswith("/var/run/secrets/firemud/jwks/")
        and not has_secret_mount(container, volumes, "jwt-jwks", "/var/run/secrets/firemud/jwks")
    ):
        missing_jwks_mount.append(f"{workload_name}/{container_name}")

if inline_secret:
    result("PREFLIGHT-JWT-001", "fail", "Inline JWT secret material detected in rendered workloads")
elif missing_secret_path:
    result("PREFLIGHT-JWT-001", "fail", "FIREMUD_AUTH_JWT_SECRET_PATH is missing for workloads: " + ", ".join(missing_secret_path))
elif missing_signing_mount:
    result("PREFLIGHT-JWT-001", "fail", "JWT signing Secret is not mounted at the configured path for workloads: " + ", ".join(missing_signing_mount))
else:
    result("PREFLIGHT-JWT-001", "pass", "JWT file-path contract and signing Secret mounts are satisfied")

if has_resource("ConfigMap", "jwt-jwks"):
    result("PREFLIGHT-JWKS-001", "fail", "jwt-jwks is configured as a ConfigMap in player-facing context")
elif not has_resource("Secret", "jwt-jwks"):
    result("PREFLIGHT-JWKS-001", "fail", "No jwt-jwks Secret found in rendered manifests")
elif missing_jwks_mount:
    result("PREFLIGHT-JWKS-001", "fail", "Account Service does not mount jwt-jwks at the configured JWKS path: " + ", ".join(missing_jwks_mount))
else:
    result("PREFLIGHT-JWKS-001", "pass", "jwt-jwks Secret contract and Account Service mount are satisfied")
PY
)"
while IFS=$'\t' read -r policy_id required status message; do
  [ -z "$policy_id" ] && continue
  append_result "$policy_id" "$required" "$status" "$message"
done <<<"$JWT_JWKS_RESULTS"

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
preflight_ref = record.get("preflightReportPath")
if not preflight_ref:
    print(f"fail\t{rollback_mode}\tStaging deployment record missing preflightReportPath")
    raise SystemExit(0)
preflight_path = root_dir / str(preflight_ref)
if not preflight_path.exists():
    print(f"fail\t{rollback_mode}\tStaging preflight report not found: {preflight_ref}")
    raise SystemExit(0)
try:
    preflight_report = json.loads(preflight_path.read_text(encoding="utf-8"))
except Exception as exc:
    print(f"fail\t{rollback_mode}\tStaging preflight report unreadable: {exc}")
    raise SystemExit(0)
if preflight_report.get("environment") != "staging":
    print(f"fail\t{rollback_mode}\tStaging preflight report has wrong environment")
    raise SystemExit(0)
if preflight_report.get("expectedBindingsRef") != "design/operations/environments/staging/expected-bindings.yaml":
    print(f"fail\t{rollback_mode}\tStaging preflight report expectedBindingsRef mismatch")
    raise SystemExit(0)
preflight_results = preflight_report.get("checkResults")
if not isinstance(preflight_results, list) or not preflight_results:
    print(f"fail\t{rollback_mode}\tStaging preflight report missing checkResults")
    raise SystemExit(0)
required_failures = [
    check.get("policyId")
    for check in preflight_results
    if isinstance(check, dict)
    and check.get("status") == "fail"
    and check.get("policyId") != "PREFLIGHT-DIGEST-002"
]
if required_failures:
    print(
        f"fail\t{rollback_mode}\tStaging preflight report contains failing required checks: {', '.join(required_failures)}"
    )
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

# PREFLIGHT-BACKUP-002 / PREFLIGHT-BACKUP-003
if [ "$ENV_CLASS" = "production" ]; then
  if [ -z "$TRAFFIC_OPEN_EVENT" ]; then
    append_result "PREFLIGHT-BACKUP-002" "false" "not_applicable" "Production traffic-open backup gate applies only to first-live or reopen events"
  else
    TRAFFIC_EVIDENCE="${FIREMUD_TRAFFIC_OPEN_EVIDENCE:-$ROOT_DIR/design/operations/deployments/production/backup-readiness/${TRAFFIC_OPEN_EVENT}-${DEPLOYMENT_REF}.json}"
    TRAFFIC_RESULT="$(python3 - <<'PY' "$TRAFFIC_EVIDENCE" "$TRAFFIC_OPEN_EVENT" "$DEPLOYMENT_REF"
import datetime as dt
import json
import pathlib
import sys

path = pathlib.Path(sys.argv[1])
event = sys.argv[2]
deployment_ref = sys.argv[3]
if not path.exists():
    print(f"fail\tProduction traffic-open evidence not found: {path}")
    raise SystemExit(0)
try:
    data = json.loads(path.read_text(encoding="utf-8"))
except Exception as exc:
    print(f"fail\tProduction traffic-open evidence unreadable: {exc}")
    raise SystemExit(0)
if data.get("environment") != "production":
    print("fail\tProduction traffic-open evidence must target production")
    raise SystemExit(0)
if data.get("eventType") != event:
    print("fail\tProduction traffic-open evidence eventType mismatch")
    raise SystemExit(0)
if str(data.get("deploymentRef", "")) != str(deployment_ref):
    print("fail\tProduction traffic-open evidence deploymentRef mismatch")
    raise SystemExit(0)
for key in ("backupLastSuccessAt", "backupVerifyLastSuccessAt", "restoreDrillLastSuccessAt"):
    if not data.get(key):
        print(f"fail\tProduction traffic-open evidence missing {key}")
        raise SystemExit(0)
scope = data.get("coordinatedBackupScope", {})
if scope.get("type") != "tenant_region":
    print("fail\tProduction traffic-open evidence must use canonical tenant_id + region_id coordinated-backup scope")
    raise SystemExit(0)
try:
    drill = dt.datetime.fromisoformat(str(data["restoreDrillLastSuccessAt"]).replace("Z", "+00:00"))
except Exception as exc:
    print(f"fail\tProduction restore drill timestamp unreadable: {exc}")
    raise SystemExit(0)
now = dt.datetime.now(dt.timezone.utc)
if (now - drill).total_seconds() > 30 * 24 * 60 * 60:
    print("fail\tProduction restore drill evidence is older than 30 days")
    raise SystemExit(0)
print("pass\tProduction traffic-open backup evidence is valid")
PY
)"
    TRAFFIC_STATUS="$(printf '%s\n' "$TRAFFIC_RESULT" | cut -f1)"
    TRAFFIC_MESSAGE="$(printf '%s\n' "$TRAFFIC_RESULT" | cut -f2-)"
    append_result "PREFLIGHT-BACKUP-002" "true" "$TRAFFIC_STATUS" "$TRAFFIC_MESSAGE"
  fi
else
  append_result "PREFLIGHT-BACKUP-002" "false" "not_applicable" "Production traffic-open backup gate applies only to production"
fi

if [ "$ENV_CLASS" = "hobby-self-hosted" ]; then
  if [ -z "$TRAFFIC_OPEN_EVENT" ]; then
    append_result "PREFLIGHT-BACKUP-003" "false" "not_applicable" "Hobby traffic-open backup gate applies only to first-live or reopen events"
  else
    TRAFFIC_EVIDENCE="${FIREMUD_TRAFFIC_OPEN_EVIDENCE:-$ROOT_DIR/design/operations/deployments/hobby-self-hosted/traffic-open/${DEPLOYMENT_REF}.json}"
    HOBBY_RESULT="$(python3 - <<'PY' "$ROOT_DIR/design/operations/deployments/hobby-self-hosted/backup-compliance.yaml" "$TRAFFIC_EVIDENCE" "$TRAFFIC_OPEN_EVENT" "$DEPLOYMENT_REF" "$ROOT_DIR"
import datetime as dt
import json
import pathlib
import sys
import yaml

compliance_path = pathlib.Path(sys.argv[1])
traffic_path = pathlib.Path(sys.argv[2])
event = sys.argv[3]
deployment_ref = sys.argv[4]
root_dir = pathlib.Path(sys.argv[5])
if not compliance_path.exists():
    print(f"fail\tHobby backup-compliance record not found: {compliance_path}")
    raise SystemExit(0)
if not traffic_path.exists():
    print(f"fail\tHobby traffic-open evidence not found: {traffic_path}")
    raise SystemExit(0)
try:
    compliance = yaml.safe_load(compliance_path.read_text(encoding="utf-8")) or {}
    traffic = json.loads(traffic_path.read_text(encoding="utf-8"))
except Exception as exc:
    print(f"fail\tHobby traffic-open evidence unreadable: {exc}")
    raise SystemExit(0)
if compliance.get("environment") != "hobby-self-hosted" or traffic.get("environment") != "hobby-self-hosted":
    print("fail\tHobby traffic-open evidence must target hobby-self-hosted")
    raise SystemExit(0)
if traffic.get("eventType") != event:
    print("fail\tHobby traffic-open evidence eventType mismatch")
    raise SystemExit(0)
if str(traffic.get("deploymentRef", "")) != str(deployment_ref):
    print("fail\tHobby traffic-open evidence deploymentRef mismatch")
    raise SystemExit(0)
if traffic.get("backupComplianceRef") != "design/operations/deployments/hobby-self-hosted/backup-compliance.yaml":
    print("fail\tHobby traffic-open evidence must reference the canonical backup-compliance record")
    raise SystemExit(0)
if compliance.get("status") != "pass":
    print("fail\tHobby backup-compliance status must be pass")
    raise SystemExit(0)
preflight_ref = traffic.get("preflightReportPath")
if not preflight_ref:
    print("fail\tHobby traffic-open evidence missing preflightReportPath")
    raise SystemExit(0)
preflight_path = pathlib.Path(preflight_ref)
if not preflight_path.is_absolute():
    preflight_path = root_dir / str(preflight_ref)
if not preflight_path.exists():
    print(f"fail\tHobby preflight report not found: {preflight_ref}")
    raise SystemExit(0)
try:
    preflight_report = json.loads(preflight_path.read_text(encoding="utf-8"))
except Exception as exc:
    print(f"fail\tHobby preflight report unreadable: {exc}")
    raise SystemExit(0)
if preflight_report.get("environment") != "hobby-self-hosted":
    print("fail\tHobby preflight report must target hobby-self-hosted")
    raise SystemExit(0)
if preflight_report.get("expectedBindingsRef") != "design/operations/environments/hobby-self-hosted/expected-bindings.yaml":
    print("fail\tHobby preflight report expectedBindingsRef mismatch")
    raise SystemExit(0)
deployment_ref_obj = preflight_report.get("deploymentRef", {})
if not isinstance(deployment_ref_obj, dict) or str(deployment_ref_obj.get("manifestRef", "")) != str(deployment_ref):
    print("fail\tHobby preflight report deploymentRef mismatch")
    raise SystemExit(0)
preflight_results = preflight_report.get("checkResults")
if not isinstance(preflight_results, list) or not preflight_results:
    print("fail\tHobby preflight report missing checkResults")
    raise SystemExit(0)
required_failures = [
    check.get("policyId")
    for check in preflight_results
    if isinstance(check, dict)
    and check.get("status") == "fail"
    and check.get("policyId") != "PREFLIGHT-DIGEST-002"
]
if required_failures:
    print(
        "fail\tHobby preflight report contains failing required checks: "
        + ", ".join(required_failures)
    )
    raise SystemExit(0)
print("pass\tHobby traffic-open backup compliance evidence is valid")
PY
)"
    HOBBY_STATUS="$(printf '%s\n' "$HOBBY_RESULT" | cut -f1)"
    HOBBY_MESSAGE="$(printf '%s\n' "$HOBBY_RESULT" | cut -f2-)"
    append_result "PREFLIGHT-BACKUP-003" "true" "$HOBBY_STATUS" "$HOBBY_MESSAGE"
  fi
else
  append_result "PREFLIGHT-BACKUP-003" "false" "not_applicable" "Hobby traffic-open backup gate applies only to hobby-self-hosted"
fi

COMPLETED_AT="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
DEFAULT_OUTPUT="$ROOT_DIR/design/operations/deployments/$ENV_CLASS/preflight/$DEPLOYMENT_REF.json"
OUTPUT_PATH="${FIREMUD_PREFLIGHT_OUTPUT:-$DEFAULT_OUTPUT}"
mkdir -p "$(dirname "$OUTPUT_PATH")"

CHECKS_JSON="[$(printf '%s\n' "$CHECK_RESULTS" | paste -sd ',' -)]"

python3 - <<'PY' "$OUTPUT_PATH" "$ENV_CLASS" "$DEPLOYMENT_REF" "$STARTED_AT" "$COMPLETED_AT" "$CHECKS_JSON" "$CONTEXT" "$WAIVER_PATH" "$EXPECTED_BINDINGS_REF"
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
  "expectedBindingsRef": sys.argv[9],
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
