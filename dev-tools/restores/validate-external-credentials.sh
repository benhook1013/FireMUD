#!/usr/bin/env bash
# Validate enabled external bindings against the canonical expected-bindings
# manifest after an isolated restore drill. This is one hardening control group,
# not complete recovery or traffic-open proof.
set -euo pipefail

SCRIPT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

usage() {
  echo "Usage: validate-external-credentials.sh <hobby-self-hosted|staging|production>" >&2
  exit 1
}

[ $# -eq 1 ] || usage
ENVIRONMENT="$1"
case "$ENVIRONMENT" in
  hobby-self-hosted|staging|production) ;;
  *) usage ;;
esac

EXPECTED_BINDINGS_REF="${EXPECTED_BINDINGS_REF:-design/operations/environments/$ENVIRONMENT/expected-bindings.yaml}"
EXPECTED_BINDINGS_PATTERN="design/operations/environments/$ENVIRONMENT/expected-bindings.yaml"
if [[ "$EXPECTED_BINDINGS_REF" != "$EXPECTED_BINDINGS_PATTERN" ]]; then
  echo "EXPECTED_BINDINGS_REF must be the canonical manifest: $EXPECTED_BINDINGS_PATTERN" >&2
  exit 1
fi
if [[ -f "$EXPECTED_BINDINGS_REF" ]]; then
  EXPECTED_BINDINGS_PATH="$(realpath "$EXPECTED_BINDINGS_REF")"
else
  EXPECTED_BINDINGS_PATH="$SCRIPT_ROOT/$EXPECTED_BINDINGS_REF"
fi
if [[ ! -f "$EXPECTED_BINDINGS_PATH" ]]; then
  echo "Canonical expected-bindings manifest not found: $EXPECTED_BINDINGS_REF" >&2
  exit 1
fi

check_path() {
  local key="$1"
  local value="${!key:-}"
  if [[ -z "$value" ]]; then return 0; fi
  if [[ "$value" == /* || "$value" == *..* || "$value" != design/operations/* ]]; then
    echo "$key must be a repository-relative path under design/operations/." >&2
    exit 1
  fi
}

check_path "EXTERNAL_CREDENTIAL_EVIDENCE_REF"
check_path "SANITIZATION_EVIDENCE_REF"

if [[ -z "${EXTERNAL_CREDENTIAL_EVIDENCE_REF:-}" ]]; then
  echo "Missing required environment variable: EXTERNAL_CREDENTIAL_EVIDENCE_REF" >&2
  exit 1
fi
if [[ ! "$EXTERNAL_CREDENTIAL_EVIDENCE_REF" =~ ^design/operations/deployments/$ENVIRONMENT/recovery/[^/]+\.json$ ]]; then
  echo "EXTERNAL_CREDENTIAL_EVIDENCE_REF must point to the complete recovery record under the target environment." >&2
  exit 1
fi
if [[ ! -f "$EXTERNAL_CREDENTIAL_EVIDENCE_REF" ]]; then
  echo "External credential evidence file not found: $EXTERNAL_CREDENTIAL_EVIDENCE_REF" >&2
  exit 1
fi

# The immutable recovery record is authoritative for restore provenance. A
# caller-supplied source is only an optional expectation checked against it;
# it must never select the sanitization branch.
SOURCE_ENVIRONMENT="$(python3 - "$EXTERNAL_CREDENTIAL_EVIDENCE_REF" <<'PY'
import json
import pathlib
import sys

path = pathlib.Path(sys.argv[1])
try:
    data = json.loads(path.read_text(encoding="utf-8"))
except (OSError, UnicodeError, json.JSONDecodeError) as exc:
    print(f"External credential evidence unreadable: {exc}", file=sys.stderr)
    raise SystemExit(1)
if not isinstance(data, dict):
    print("External credential evidence must be a JSON object.", file=sys.stderr)
    raise SystemExit(1)
if data.get("schemaVersion") != "recovery-record/v1":
    print("External credential evidence schemaVersion must be recovery-record/v1.", file=sys.stderr)
    raise SystemExit(1)
binding = data.get("sourceEnvironmentBinding")
if not isinstance(binding, dict) or set(binding) != {"environment", "bindingRef"}:
    print("External credential evidence sourceEnvironmentBinding must contain exactly environment and bindingRef.", file=sys.stderr)
    raise SystemExit(1)
source = binding.get("environment")
binding_ref = binding.get("bindingRef")
if not isinstance(source, str) or source not in {"hobby-self-hosted", "staging", "production"}:
    print("External credential evidence sourceEnvironmentBinding.environment is unknown.", file=sys.stderr)
    raise SystemExit(1)
if not isinstance(binding_ref, str) or not binding_ref.strip():
    print("External credential evidence sourceEnvironmentBinding.bindingRef must be non-empty.", file=sys.stderr)
    raise SystemExit(1)
restore_source = data.get("restoreSource")
if not restore_source:
    print("External credential evidence restoreSource must be present and non-empty.", file=sys.stderr)
    raise SystemExit(1)
print(source)
PY
)"

if [[ -n "${RESTORE_SOURCE_ENVIRONMENT:-}" ]]; then
  case "$RESTORE_SOURCE_ENVIRONMENT" in
    hobby-self-hosted|staging|production) ;;
    *)
      echo "RESTORE_SOURCE_ENVIRONMENT must be hobby-self-hosted, staging, or production." >&2
      exit 1
      ;;
  esac
  if [[ "$RESTORE_SOURCE_ENVIRONMENT" != "$SOURCE_ENVIRONMENT" ]]; then
    echo "RESTORE_SOURCE_ENVIRONMENT contradicts sourceEnvironmentBinding in the recovery record." >&2
    exit 1
  fi
fi

if [[ "$ENVIRONMENT" == "staging" ]]; then
  if [[ "$SOURCE_ENVIRONMENT" != "staging" && "$SOURCE_ENVIRONMENT" != "production" ]]; then
    echo "Staging restore source must be staging or production." >&2
    exit 1
  fi
elif [[ "$SOURCE_ENVIRONMENT" != "$ENVIRONMENT" ]]; then
  echo "Restore source must match target environment for $ENVIRONMENT." >&2
  exit 1
fi

# The manifest is the authority; EXPECTED_* variables are intentionally not
# accepted as a second source of expected values.
python3 - "$EXPECTED_BINDINGS_PATH" "$ENVIRONMENT" \
  "${PG_DUMP_BUCKET:-}" "${PG_DUMP_ENDPOINT:-}" "${PG_DUMP_BINDING_REF:-}" \
  "${ASSET_STORE_BUCKET:-}" "${ASSET_STORE_ENDPOINT:-}" "${ASSET_STORE_BINDING_REF:-}" \
  "${SMTP_HOST:-}" "${SMTP_PORT:-}" "${OPERATOR_CREDENTIAL_BINDING_REF:-}" <<'PY'
import pathlib
import sys

import yaml

manifest_path = pathlib.Path(sys.argv[1])
environment = sys.argv[2]
values = dict(
    pg_bucket=sys.argv[3], pg_endpoint=sys.argv[4], pg_binding=sys.argv[5],
    asset_bucket=sys.argv[6], asset_endpoint=sys.argv[7], asset_binding=sys.argv[8],
    smtp_host=sys.argv[9], smtp_port=sys.argv[10], operator_binding=sys.argv[11],
)
try:
    manifest = yaml.safe_load(manifest_path.read_text(encoding="utf-8"))
except (OSError, UnicodeError, yaml.YAMLError) as exc:
    print(f"Canonical expected-bindings manifest unreadable: {exc}", file=sys.stderr)
    raise SystemExit(1)
if not isinstance(manifest, dict) or manifest.get("environment") != environment:
    print(f"Canonical expected-bindings manifest must target {environment}.", file=sys.stderr)
    raise SystemExit(1)

def section(name):
    value = manifest.get(name, {})
    if value is None: return {}
    if not isinstance(value, dict):
        print(f"Canonical expected-bindings section must be a mapping: {name}.", file=sys.stderr)
        raise SystemExit(1)
    return value

def compare(label, actual, expected, required=True):
    expected = "" if expected is None else str(expected)
    if required and not actual:
        print(f"Missing required binding value: {label}.", file=sys.stderr)
        raise SystemExit(1)
    if actual != expected:
        print(f"Binding mismatch for {label}: expected canonical value.", file=sys.stderr)
        raise SystemExit(1)

backup = section("backupStorage")
if backup.get("enabled") is True:
    compare("PG_DUMP_BUCKET", values["pg_bucket"], backup.get("bucket"))
    compare("PG_DUMP_ENDPOINT", values["pg_endpoint"], backup.get("endpoint", ""), required=False)
    compare("PG_DUMP_BINDING_REF", values["pg_binding"], backup.get("bindingRef"))
elif any(values[key] for key in ("pg_bucket", "pg_endpoint", "pg_binding")):
    print("Backup storage is disabled; its binding values must be omitted.", file=sys.stderr)
    raise SystemExit(1)

asset = section("assetStorage")
if asset.get("enabled") is True:
    compare("ASSET_STORE_BUCKET", values["asset_bucket"], asset.get("bucket"))
    compare("ASSET_STORE_ENDPOINT", values["asset_endpoint"], asset.get("endpoint", ""), required=False)
    compare("ASSET_STORE_BINDING_REF", values["asset_binding"], asset.get("bindingRef"))
elif any(values[key] for key in ("asset_bucket", "asset_endpoint", "asset_binding")):
    print("Asset storage is disabled; its binding values must be omitted.", file=sys.stderr)
    raise SystemExit(1)

outbound = section("outboundComms")
if outbound.get("enabled") is True:
    expected_host = outbound.get("smtpHost")
    if not expected_host:
        print("Enabled outbound communications has no supported SMTP target; refusing to skip its probe.", file=sys.stderr)
        raise SystemExit(1)
    compare("SMTP_HOST", values["smtp_host"], expected_host)
    if values["smtp_port"] and (not values["smtp_port"].isdigit() or not 1 <= int(values["smtp_port"]) <= 65535):
        print("SMTP_PORT must be an integer from 1 through 65535.", file=sys.stderr)
        raise SystemExit(1)
elif any(values[key] for key in ("smtp_host", "smtp_port")):
    print("Outbound communications is disabled; its binding values must be omitted.", file=sys.stderr)
    raise SystemExit(1)

operator = section("operatorCredentials")
compare("OPERATOR_CREDENTIAL_BINDING_REF", values["operator_binding"], operator.get("bindingRef"))
PY

python3 - "$EXTERNAL_CREDENTIAL_EVIDENCE_REF" "$ENVIRONMENT" \
  "${PG_DUMP_BUCKET:-}" "${ASSET_STORE_BUCKET:-}" "${SMTP_HOST:-}" "${SMTP_PORT:-}" \
  "${OPERATOR_CERT_FINGERPRINT:-}" "$EXPECTED_BINDINGS_PATH" <<'PY'
import json
import pathlib
import sys

import yaml

evidence_path = pathlib.Path(sys.argv[1])
environment = sys.argv[2]
pg_bucket, asset_bucket, smtp_host, smtp_port, operator_fingerprint = sys.argv[3:8]
manifest_path = pathlib.Path(sys.argv[8])
try:
    data = json.loads(evidence_path.read_text(encoding="utf-8"))
except (OSError, UnicodeError, json.JSONDecodeError) as exc:
    print(f"External credential evidence unreadable: {exc}", file=sys.stderr)
    raise SystemExit(1)
if not isinstance(data, dict) or data.get("environment") != environment:
    print("External credential evidence environment mismatch.", file=sys.stderr)
    raise SystemExit(1)
for key in ("recoveryRef", "certificateReissuance", "jwtHardening", "databaseCredentialRotation"):
    if not data.get(key):
        print(f"External credential evidence missing {key}.", file=sys.stderr)
        raise SystemExit(1)
try:
    manifest = yaml.safe_load(manifest_path.read_text(encoding="utf-8"))
except (OSError, UnicodeError, yaml.YAMLError) as exc:
    print(f"Canonical expected-bindings manifest unreadable: {exc}", file=sys.stderr)
    raise SystemExit(1)
if not isinstance(manifest, dict):
    print("Canonical expected-bindings manifest must be a mapping.", file=sys.stderr)
    raise SystemExit(1)
records = (data.get("externalCredentialValidation") or {}).get("records")
if not isinstance(records, dict):
    print("External credential evidence missing externalCredentialValidation.records.", file=sys.stderr)
    raise SystemExit(1)

def section(name):
    value = manifest.get(name)
    if not isinstance(value, dict):
        print(f"Canonical expected-bindings section must be a mapping: {name}.", file=sys.stderr)
        raise SystemExit(1)
    return value

def enabled(name):
    value = section(name).get("enabled")
    if type(value) is not bool:
        print(f"Canonical expected-bindings selector must be boolean: {name}.enabled", file=sys.stderr)
        raise SystemExit(1)
    return value

PASS_KEYS = {"status", "evidenceRef", "isolationAssertion", "validationMethod", "validatedAt", "validatedBy", "observedValue"}
NOT_APPLICABLE_KEYS = {"status", "reason", "evidenceRef"}
RECORD_KEYS = {"backup-storage", "asset-storage", "outbound-comms", "operator-credentials"}

if set(records) != RECORD_KEYS:
    missing = sorted(RECORD_KEYS - set(records))
    unknown = sorted(set(records) - RECORD_KEYS)
    details = []
    if missing: details.append(f"missing={','.join(missing)}")
    if unknown: details.append(f"unknown={','.join(unknown)}")
    print(f"External credential evidence records must be the closed four-record universe ({'; '.join(details)}).", file=sys.stderr)
    raise SystemExit(1)

def record(key, method, observed=None):
    item = records.get(key)
    if not isinstance(item, dict) or item.get("status") != "pass":
        print(f"External credential evidence record must be pass: {key}", file=sys.stderr)
        raise SystemExit(1)
    if set(item) != PASS_KEYS:
        print(f"External credential pass record has non-canonical keys: {key}", file=sys.stderr)
        raise SystemExit(1)
    for field in PASS_KEYS - {"status"}:
        if not isinstance(item.get(field), str) or not item[field].strip():
            print(f"External credential evidence record missing {field}: {key}", file=sys.stderr)
            raise SystemExit(1)
    if item.get("validationMethod") != method:
        print(f"External credential validation record {key} must use validationMethod={method}", file=sys.stderr)
        raise SystemExit(1)
    if observed is not None and item.get("observedValue") != observed:
        print(f"External credential record observedValue mismatch: {key}", file=sys.stderr)
        raise SystemExit(1)

def not_applicable(key):
    item = records.get(key)
    if not isinstance(item, dict) or set(item) != NOT_APPLICABLE_KEYS:
        print(f"Disabled integration record must contain exactly status, reason, and evidenceRef: {key}", file=sys.stderr)
        raise SystemExit(1)
    for field in ("reason", "evidenceRef"):
        if not isinstance(item.get(field), str) or not item[field].strip():
            print(f"Disabled integration record field must be non-empty: {key}.{field}", file=sys.stderr)
            raise SystemExit(1)
    if item.get("status") != "not_applicable":
        print(f"Disabled integration record must be not_applicable: {key}", file=sys.stderr)
        raise SystemExit(1)
    if item.get("reason") != "credential-class-not-present":
        print(f"Disabled integration record reason must be credential-class-not-present: {key}", file=sys.stderr)
        raise SystemExit(1)

if enabled("backupStorage"):
    record("backup-storage", "aws-s3-head-bucket", pg_bucket)
else:
    not_applicable("backup-storage")
if enabled("assetStorage"):
    record("asset-storage", "aws-s3-head-bucket", asset_bucket)
else:
    not_applicable("asset-storage")
if enabled("outboundComms"):
    record("outbound-comms", "smtp-connectivity-check", f"{smtp_host}:{smtp_port or '25'}")
else:
    not_applicable("outbound-comms")
record("operator-credentials", "operator-certificate-fingerprint", operator_fingerprint or None)
PY

if [[ "$ENVIRONMENT" == "staging" && "$SOURCE_ENVIRONMENT" == "production" ]]; then
  if [[ ! "${SANITIZATION_EVIDENCE_REF:-}" =~ ^design/operations/deployments/staging/recovery/[^/]+\.sanitization\.json$ ]]; then
    echo "SANITIZATION_EVIDENCE_REF is required for production-origin staging restores and must point to a pre-release *.sanitization.json artifact." >&2
    exit 1
  fi
  if [[ ! -f "$SANITIZATION_EVIDENCE_REF" ]]; then
    echo "SANITIZATION_EVIDENCE_REF not found: $SANITIZATION_EVIDENCE_REF" >&2
    exit 1
  fi
  python3 - "$SANITIZATION_EVIDENCE_REF" "$EXTERNAL_CREDENTIAL_EVIDENCE_REF" <<'PY'
import json
import pathlib
import sys
path = pathlib.Path(sys.argv[1])
external_path = pathlib.Path(sys.argv[2])
data = json.loads(path.read_text(encoding="utf-8"))
external = json.loads(external_path.read_text(encoding="utf-8"))
if data.get("schemaVersion") != "recovery-sanitization-evidence/v1":
    print("Staging sanitization evidence schemaVersion must be recovery-sanitization-evidence/v1.", file=sys.stderr)
    raise SystemExit(1)
if data.get("environment") != "staging":
    print("Staging sanitization evidence environment mismatch.", file=sys.stderr)
    raise SystemExit(1)
for key in ("recoveryRef", "operationId", "deploymentEventId", "backupArtifactDigest", "sanitizedAt", "sanitizedBy", "controlsApplied", "validationEvidence"):
    if not data.get(key):
        print(f"Staging sanitization evidence missing {key}.", file=sys.stderr)
        raise SystemExit(1)
if path.name != f"{data['recoveryRef']}.sanitization.json":
    print("Staging sanitization evidence filename must match recoveryRef.", file=sys.stderr)
    raise SystemExit(1)
lineage = external.get("backupArtifactLineage") or {}
for key, expected in {
    "recoveryRef": external.get("recoveryRef"),
    "operationId": external.get("operationId"),
    "deploymentEventId": external.get("deploymentEventId"),
    "backupArtifactDigest": lineage.get("artifactDigest"),
}.items():
    if not expected or data.get(key) != expected:
        print(f"Staging sanitization evidence {key} mismatch or missing external lineage.", file=sys.stderr)
        raise SystemExit(1)
PY
elif [[ -n "${SANITIZATION_EVIDENCE_REF:-}" ]]; then
  echo "SANITIZATION_EVIDENCE_REF is only valid for production-origin staging restores." >&2
  exit 1
fi

# Enabled object storage must have its real probe available; absence is an
# error rather than an advisory skip. The canonical manifest check above has
# already established whether each target is enabled.
if [[ -n "${PG_DUMP_BUCKET:-}" || -n "${ASSET_STORE_BUCKET:-}" ]]; then
  command -v aws >/dev/null 2>&1 || {
    echo "aws CLI is required for enabled object-storage validation; refusing to skip the probe." >&2
    exit 1
  }
  if [[ -n "${PG_DUMP_BUCKET:-}" ]]; then
    AWS_ENDPOINT_ARGS=()
    if [[ -n "${PG_DUMP_ENDPOINT:-}" ]]; then AWS_ENDPOINT_ARGS+=(--endpoint-url "$PG_DUMP_ENDPOINT"); fi
    aws s3api head-bucket --bucket "$PG_DUMP_BUCKET" "${AWS_ENDPOINT_ARGS[@]}" >/dev/null
  fi
  if [[ -n "${ASSET_STORE_BUCKET:-}" ]]; then
    ASSET_ENDPOINT_ARGS=()
    if [[ -n "${ASSET_STORE_ENDPOINT:-}" ]]; then ASSET_ENDPOINT_ARGS+=(--endpoint-url "$ASSET_STORE_ENDPOINT"); fi
    aws s3api head-bucket --bucket "$ASSET_STORE_BUCKET" "${ASSET_ENDPOINT_ARGS[@]}" >/dev/null
  fi
fi

if [[ -n "${SMTP_HOST:-}" ]]; then
  command -v timeout >/dev/null 2>&1 || {
    echo "timeout is required for enabled SMTP validation; refusing to skip the probe." >&2
    exit 1
  }
  SMTP_PORT_VALUE="${SMTP_PORT:-25}"
  # shellcheck disable=SC2016 # The probe shell expands its positional arguments.
  timeout 5 bash -c 'exec 3<>/dev/tcp/"$1"/"$2"' -- "$SMTP_HOST" "$SMTP_PORT_VALUE" >/dev/null 2>&1 || {
    echo "Unable to connect to SMTP target $SMTP_HOST:$SMTP_PORT_VALUE" >&2
    exit 1
  }
fi

echo "External credential validation passed for $ENVIRONMENT (source: $SOURCE_ENVIRONMENT)."
