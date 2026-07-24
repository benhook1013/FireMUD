#!/usr/bin/env bash
# Validate external credential bindings after a restore before opening traffic.
set -euo pipefail

usage() {
  echo "Usage: validate-external-credentials.sh <hobby-self-hosted|staging|production>" >&2
  exit 1
}

[ $# -eq 1 ] || usage
ENVIRONMENT="$1"
if [[ "$ENVIRONMENT" != "hobby-self-hosted" && "$ENVIRONMENT" != "staging" && "$ENVIRONMENT" != "production" ]]; then
  usage
fi

check_required_var() {
  local key="$1"
  if [[ -z "${!key:-}" ]]; then
    echo "Missing required environment variable: $key" >&2
    exit 1
  fi
}

validate_expected_match() {
  local actual_key="$1"
  local expected_key="$2"
  if [[ -n "${!expected_key:-}" && "${!actual_key:-}" != "${!expected_key}" ]]; then
    echo "Mismatch: $actual_key='${!actual_key}' does not match $expected_key='${!expected_key}'" >&2
    exit 1
  fi
}

validate_required_record() {
  local record_key="$1"
  local expected_method="$2"
  python3 - <<'PY' "$EXTERNAL_CREDENTIAL_EVIDENCE_REF" "$record_key" "$expected_method"
import json
import pathlib
import sys

path = pathlib.Path(sys.argv[1])
record_key = sys.argv[2]
expected_method = sys.argv[3]
data = json.loads(path.read_text(encoding="utf-8"))
record = ((data.get("externalCredentialValidation") or {}).get("records") or {}).get(record_key)
if not isinstance(record, dict):
    print(f"Missing external credential validation record: {record_key}", file=sys.stderr)
    raise SystemExit(1)
if record.get("status") != "pass":
    print(f"External credential validation record must be pass: {record_key}", file=sys.stderr)
    raise SystemExit(1)
if expected_method and record.get("validationMethod") != expected_method:
    print(
        f"External credential validation record {record_key} must use validationMethod={expected_method}",
        file=sys.stderr,
    )
    raise SystemExit(1)
if not record.get("observedValue"):
    print(f"External credential validation record missing observedValue: {record_key}", file=sys.stderr)
    raise SystemExit(1)
print(str(record.get("observedValue")))
PY
}

echo "Validating external credential bindings for environment: $ENVIRONMENT"

check_required_var "PG_DUMP_BUCKET"
check_required_var "ASSET_STORE_BUCKET"
check_required_var "EXTERNAL_CREDENTIAL_EVIDENCE_REF"

validate_expected_match "PG_DUMP_BUCKET" "EXPECTED_PG_DUMP_BUCKET"
validate_expected_match "ASSET_STORE_BUCKET" "EXPECTED_ASSET_STORE_BUCKET"
validate_expected_match "ASSET_STORE_ENDPOINT" "EXPECTED_ASSET_STORE_ENDPOINT"
validate_expected_match "SMTP_HOST" "EXPECTED_SMTP_HOST"
validate_expected_match "OPERATOR_CERT_FINGERPRINT" "EXPECTED_OPERATOR_CERT_FINGERPRINT"

EXPECTED_EVIDENCE_PREFIX="design/operations/deployments/$ENVIRONMENT/recovery/"
if [[ ! "$EXTERNAL_CREDENTIAL_EVIDENCE_REF" =~ ^${EXPECTED_EVIDENCE_PREFIX} ]]; then
  echo "EXTERNAL_CREDENTIAL_EVIDENCE_REF must point under $EXPECTED_EVIDENCE_PREFIX" >&2
  exit 1
fi
if [[ ! -f "$EXTERNAL_CREDENTIAL_EVIDENCE_REF" ]]; then
  echo "External credential evidence file not found: $EXTERNAL_CREDENTIAL_EVIDENCE_REF" >&2
  exit 1
fi

python3 - <<'PY' "$EXTERNAL_CREDENTIAL_EVIDENCE_REF" "$ENVIRONMENT"
import json
import pathlib
import sys

path = pathlib.Path(sys.argv[1])
environment = sys.argv[2]

try:
    data = json.loads(path.read_text(encoding="utf-8"))
except Exception as exc:
    print(f"External credential evidence unreadable: {exc}", file=sys.stderr)
    raise SystemExit(1)

if data.get("environment") != environment:
    print("External credential evidence environment mismatch.", file=sys.stderr)
    raise SystemExit(1)
if not data.get("recoveryRef"):
    print("External credential evidence missing recoveryRef.", file=sys.stderr)
    raise SystemExit(1)
for key in ["certificateReissuance", "jwtHardening", "databaseCredentialRotation"]:
    if not data.get(key):
        print(f"External credential evidence missing {key}.", file=sys.stderr)
        raise SystemExit(1)

records = (data.get("externalCredentialValidation") or {}).get("records", {})
required = ["backup-storage", "asset-storage", "outbound-comms", "operator-credentials"]
for key in required:
    record = records.get(key)
    if not isinstance(record, dict):
        print(f"External credential evidence missing record: {key}", file=sys.stderr)
        raise SystemExit(1)
    if record.get("status") != "pass":
        print(f"External credential evidence record must be pass: {key}", file=sys.stderr)
        raise SystemExit(1)
    if not record.get("evidenceRef"):
        print(f"External credential evidence record missing evidenceRef: {key}", file=sys.stderr)
        raise SystemExit(1)
    if not record.get("isolationAssertion"):
        print(f"External credential evidence record missing isolationAssertion: {key}", file=sys.stderr)
        raise SystemExit(1)
    if not record.get("validationMethod"):
        print(f"External credential evidence record missing validationMethod: {key}", file=sys.stderr)
        raise SystemExit(1)
    if not record.get("validatedAt"):
        print(f"External credential evidence record missing validatedAt: {key}", file=sys.stderr)
        raise SystemExit(1)
    if not record.get("validatedBy"):
        print(f"External credential evidence record missing validatedBy: {key}", file=sys.stderr)
        raise SystemExit(1)
    if not record.get("observedValue"):
        print(f"External credential evidence record missing observedValue: {key}", file=sys.stderr)
        raise SystemExit(1)
PY

if command -v aws >/dev/null 2>&1; then
  BACKUP_OBSERVED_VALUE="$(validate_required_record "backup-storage" "aws-s3-head-bucket")"
  AWS_ENDPOINT_ARGS=()
  if [[ -n "${PG_DUMP_ENDPOINT:-}" ]]; then
    AWS_ENDPOINT_ARGS+=(--endpoint-url "$PG_DUMP_ENDPOINT")
  fi
  aws s3api head-bucket --bucket "$PG_DUMP_BUCKET" "${AWS_ENDPOINT_ARGS[@]}" >/dev/null
  if [[ "$BACKUP_OBSERVED_VALUE" != "$PG_DUMP_BUCKET" ]]; then
    echo "Backup storage observedValue must match PG_DUMP_BUCKET." >&2
    exit 1
  fi
  echo "Verified backup bucket access: $PG_DUMP_BUCKET"

  ASSET_OBSERVED_VALUE="$(validate_required_record "asset-storage" "aws-s3-head-bucket")"
  ASSET_ENDPOINT_ARGS=()
  if [[ -n "${ASSET_STORE_ENDPOINT:-}" ]]; then
    ASSET_ENDPOINT_ARGS+=(--endpoint-url "$ASSET_STORE_ENDPOINT")
  fi
  aws s3api head-bucket --bucket "$ASSET_STORE_BUCKET" "${ASSET_ENDPOINT_ARGS[@]}" >/dev/null
  if [[ "$ASSET_OBSERVED_VALUE" != "$ASSET_STORE_BUCKET" ]]; then
    echo "Asset storage observedValue must match ASSET_STORE_BUCKET." >&2
    exit 1
  fi
  echo "Verified asset bucket access: $ASSET_STORE_BUCKET"
else
  echo "Skipping bucket access checks because aws CLI is unavailable."
fi

if [[ -n "${SMTP_HOST:-}" ]]; then
  OUTBOUND_OBSERVED_VALUE="$(validate_required_record "outbound-comms" "smtp-connectivity-check")"
  SMTP_PORT_VALUE="${SMTP_PORT:-25}"
  if command -v timeout >/dev/null 2>&1; then
    timeout 5 bash -c "exec 3<>/dev/tcp/$SMTP_HOST/$SMTP_PORT_VALUE" >/dev/null 2>&1 || {
      echo "Unable to connect to SMTP target $SMTP_HOST:$SMTP_PORT_VALUE" >&2
      exit 1
    }
    if [[ "$OUTBOUND_OBSERVED_VALUE" != "$SMTP_HOST:$SMTP_PORT_VALUE" ]]; then
      echo "Outbound comms observedValue must match SMTP host:port." >&2
      exit 1
    fi
    echo "Verified SMTP connectivity: $SMTP_HOST:$SMTP_PORT_VALUE"
  fi
fi

if [[ -n "${OPERATOR_CERT_FINGERPRINT:-}" ]]; then
  OPERATOR_OBSERVED_VALUE="$(validate_required_record "operator-credentials" "operator-certificate-fingerprint")"
  if [[ "$OPERATOR_OBSERVED_VALUE" != "$OPERATOR_CERT_FINGERPRINT" ]]; then
    echo "Operator credential observedValue must match OPERATOR_CERT_FINGERPRINT." >&2
    exit 1
  fi
  echo "Verified operator certificate fingerprint: $OPERATOR_CERT_FINGERPRINT"
fi

if [[ "$ENVIRONMENT" == "staging" ]]; then
  if [[ -n "${PRODUCTION_PG_DUMP_BUCKET:-}" && "$PG_DUMP_BUCKET" == "$PRODUCTION_PG_DUMP_BUCKET" ]]; then
    echo "Staging must not use the production backup bucket." >&2
    exit 1
  fi
  if [[ -n "${PRODUCTION_ASSET_STORE_BUCKET:-}" && "$ASSET_STORE_BUCKET" == "$PRODUCTION_ASSET_STORE_BUCKET" ]]; then
    echo "Staging must not use the production asset bucket." >&2
    exit 1
  fi
fi

if [[ "$ENVIRONMENT" == "staging" ]]; then
  check_required_var "SANITIZATION_EVIDENCE_REF"
  if [[ ! "$SANITIZATION_EVIDENCE_REF" =~ ^design/operations/deployments/staging/recovery/[^/]+\.sanitization\.json$ ]]; then
    echo "SANITIZATION_EVIDENCE_REF must point to a pre-release *.sanitization.json artifact under design/operations/deployments/staging/recovery/." >&2
    exit 1
  fi
  if [[ ! -f "$SANITIZATION_EVIDENCE_REF" ]]; then
    echo "SANITIZATION_EVIDENCE_REF not found: $SANITIZATION_EVIDENCE_REF" >&2
    exit 1
  fi
  python3 - <<'PY' "$SANITIZATION_EVIDENCE_REF" "$EXTERNAL_CREDENTIAL_EVIDENCE_REF"
import json
import pathlib
import sys

path = pathlib.Path(sys.argv[1])
external_path = pathlib.Path(sys.argv[2])
data = json.loads(path.read_text(encoding="utf-8"))
external_data = json.loads(external_path.read_text(encoding="utf-8"))
if data.get("schemaVersion") != "recovery-sanitization-evidence/v1":
    print("Staging sanitization evidence schemaVersion must be recovery-sanitization-evidence/v1.", file=sys.stderr)
    raise SystemExit(1)
if data.get("environment") != "staging":
    print("Staging sanitization evidence environment mismatch.", file=sys.stderr)
    raise SystemExit(1)
required = [
    "recoveryRef",
    "operationId",
    "deploymentEventId",
    "backupArtifactDigest",
    "sanitizedAt",
    "sanitizedBy",
    "controlsApplied",
    "validationEvidence",
]
for key in required:
    if not data.get(key):
        print(f"Staging sanitization evidence missing {key}.", file=sys.stderr)
        raise SystemExit(1)
if path.name != f"{data['recoveryRef']}.sanitization.json":
    print("Staging sanitization evidence filename must match recoveryRef.", file=sys.stderr)
    raise SystemExit(1)
external_lineage = external_data.get("backupArtifactLineage") or {}
expected_values = {
    "recoveryRef": external_data.get("recoveryRef"),
    "operationId": external_data.get("operationId"),
    "deploymentEventId": external_data.get("deploymentEventId"),
    "backupArtifactDigest": external_lineage.get("artifactDigest"),
}
for key, expected in expected_values.items():
    if not expected:
        print(f"External credential evidence missing staging lineage field: {key}.", file=sys.stderr)
        raise SystemExit(1)
    if data.get(key) != expected:
        print(f"Staging sanitization evidence {key} mismatch.", file=sys.stderr)
        raise SystemExit(1)
PY
fi

echo "External credential validation passed for $ENVIRONMENT."
