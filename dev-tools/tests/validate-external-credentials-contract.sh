#!/usr/bin/env bash
# Contract checks for the canonical external-credential recovery record fields.
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SCRIPT="$ROOT_DIR/dev-tools/restores/validate-external-credentials.sh"
TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

EVIDENCE_DIR="$TMP_DIR/design/operations/deployments/production/recovery"
FAKE_BIN="$TMP_DIR/bin"
VALID_EVIDENCE="$EVIDENCE_DIR/valid.json"
mkdir -p "$EVIDENCE_DIR" "$FAKE_BIN"

cat >"$VALID_EVIDENCE" <<'JSON'
{
  "environment": "production",
  "recoveryRef": "contract-recovery",
  "certificateReissuance": {"status": "pass"},
  "jwtHardening": {"status": "pass"},
  "databaseCredentialRotation": {"status": "pass"},
  "externalCredentialValidation": {
    "records": {
      "backup-storage": {
        "status": "pass",
        "evidenceRef": "contract-backup-evidence",
        "isolationAssertion": "production-only",
        "validationMethod": "aws-s3-head-bucket",
        "validatedAt": "2026-07-23T00:00:00Z",
        "validatedBy": "contract-test",
        "observedValue": "production-backups"
      },
      "asset-storage": {
        "status": "pass",
        "evidenceRef": "contract-asset-evidence",
        "isolationAssertion": "production-only",
        "validationMethod": "aws-s3-head-bucket",
        "validatedAt": "2026-07-23T00:00:00Z",
        "validatedBy": "contract-test",
        "observedValue": "production-assets"
      },
      "outbound-comms": {
        "status": "pass",
        "evidenceRef": "contract-outbound-evidence",
        "isolationAssertion": "production-only",
        "validationMethod": "smtp-connectivity-check",
        "validatedAt": "2026-07-23T00:00:00Z",
        "validatedBy": "contract-test",
        "observedValue": "smtp.production.example"
      },
      "operator-credentials": {
        "status": "pass",
        "evidenceRef": "contract-operator-evidence",
        "isolationAssertion": "production-only",
        "validationMethod": "operator-certificate-fingerprint",
        "validatedAt": "2026-07-23T00:00:00Z",
        "validatedBy": "contract-test",
        "observedValue": "sha256:operator"
      }
    }
  }
}
JSON

# Keep the contract independent of any configured cloud CLI or external target.
cat >"$FAKE_BIN/aws" <<'SH'
#!/usr/bin/env bash
set -euo pipefail

case "$#:$*" in
  "4:s3api head-bucket --bucket production-backups"|\
  "4:s3api head-bucket --bucket production-assets")
    exit 0
    ;;
  *)
    printf 'unexpected aws invocation: %q ' "$@" >&2
    printf '\n' >&2
    exit 1
    ;;
esac
SH
chmod +x "$FAKE_BIN/aws"

run_validator() {
  local evidence_ref="$1"
  (
    cd "$TMP_DIR"
    PATH="$FAKE_BIN:/usr/bin:/bin" \
      PG_DUMP_BUCKET="production-backups" \
      PG_DUMP_ENDPOINT="" \
      ASSET_STORE_BUCKET="production-assets" \
      ASSET_STORE_ENDPOINT="" \
      EXPECTED_PG_DUMP_BUCKET="production-backups" \
      EXPECTED_PG_DUMP_ENDPOINT="" \
      EXPECTED_ASSET_STORE_BUCKET="production-assets" \
      EXPECTED_ASSET_STORE_ENDPOINT="" \
      EXTERNAL_CREDENTIAL_EVIDENCE_REF="$evidence_ref" \
      "$SCRIPT" production
  )
}

valid_output="$(run_validator "design/operations/deployments/production/recovery/valid.json" 2>&1)"
grep -q "External credential validation passed for production." <<<"$valid_output"

for field_pair in \
  "certificateReissuance:certificateReissuanceEvidence" \
  "jwtHardening:jwtRestoreHardeningEvidence" \
  "databaseCredentialRotation:databaseCredentialRotationEvidence"; do
  IFS=: read -r canonical_field legacy_field <<<"$field_pair"
  invalid_evidence="$EVIDENCE_DIR/${canonical_field}-legacy.json"
  python3 - "$VALID_EVIDENCE" "$invalid_evidence" "$canonical_field" "$legacy_field" <<'PY'
import json
import pathlib
import sys

source = pathlib.Path(sys.argv[1])
destination = pathlib.Path(sys.argv[2])
canonical_field = sys.argv[3]
legacy_field = sys.argv[4]
data = json.loads(source.read_text(encoding="utf-8"))
data[legacy_field] = data.pop(canonical_field)
destination.write_text(json.dumps(data), encoding="utf-8")
PY

  if output="$(run_validator "design/operations/deployments/production/recovery/${canonical_field}-legacy.json" 2>&1)"; then
    echo "validator accepted legacy-only recovery field: $legacy_field" >&2
    exit 1
  fi
  grep -q "External credential evidence missing $canonical_field." <<<"$output"
done

echo "validate-external-credentials contract checks passed"
