#!/usr/bin/env bash
# Contract checks for dev-tools/validation/validate-secret-compliance.py.
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
VALIDATOR="$ROOT_DIR/dev-tools/validation/validate-secret-compliance.py"
TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

mkdir -p "$TMP_DIR/design/operations/secret-compliance/evidence"

cat >"$TMP_DIR/design/operations/secret-compliance/evidence/evidence.json" <<'JSON'
{
  "records": {
    "jwt-signing-keys-jwks": {
      "immutableArtifactId": "test:jwt:sha256:1111"
    },
    "postgres-application-credentials": {
      "immutableArtifactId": "test:postgres:sha256:2222"
    },
    "backup-object-store-credentials": {
      "immutableArtifactId": "test:backup:sha256:3333"
    },
    "operator-credentials": {
      "immutableArtifactId": "test:operator:sha256:4444"
    }
  }
}
JSON

write_compliance_file() {
  local env="$1"
  local timestamp_field="$2"
  local extra_timestamp_field="${3:-}"
  local path="$TMP_DIR/design/operations/secret-compliance/$env.yaml"
  cat >"$path" <<YAML
{
  "environment": "$env",
  "credentialClasses": {
    "jwt-signing-keys-jwks": {
      "maxAgeDays": 30,
      "$timestamp_field": "2026-04-20T00:00:00Z",
      ${extra_timestamp_field:+"\"$extra_timestamp_field\": \"2026-04-20T00:00:00Z\","}
      "evidenceRef": "design/operations/secret-compliance/evidence/evidence.json",
      "evidenceKey": "jwt-signing-keys-jwks"
    },
    "postgres-application-credentials": {
      "maxAgeDays": 30,
      "lastRotationAt": "2026-04-20T00:00:00Z",
      "evidenceRef": "design/operations/secret-compliance/evidence/evidence.json",
      "evidenceKey": "postgres-application-credentials"
    },
    "backup-object-store-credentials": {
      "maxAgeDays": 30,
      "lastRotationAt": "2026-04-20T00:00:00Z",
      "evidenceRef": "design/operations/secret-compliance/evidence/evidence.json",
      "evidenceKey": "backup-object-store-credentials"
    },
    "operator-credentials": {
      "maxAgeDays": 30,
      "lastRotationAt": "2026-04-20T00:00:00Z",
      "evidenceRef": "design/operations/secret-compliance/evidence/evidence.json",
      "evidenceKey": "operator-credentials"
    }
  }
}
YAML
}

write_compliance_file production lastProvisionedAt
write_compliance_file staging lastRotationAt
write_compliance_file hobby-self-hosted lastRotationAt

SECRET_COMPLIANCE_ROOT="$TMP_DIR" \
  SECRET_COMPLIANCE_TODAY=2026-04-24T00:00:00Z \
  SECRET_COMPLIANCE_ENFORCEMENT_MODE=strict \
  python3 "$VALIDATOR" >/tmp/firemud-secret-compliance-valid.out

write_compliance_file production lastProvisionedAt lastRotationAt
if SECRET_COMPLIANCE_ROOT="$TMP_DIR" \
  SECRET_COMPLIANCE_TODAY=2026-04-24T00:00:00Z \
  SECRET_COMPLIANCE_ENFORCEMENT_MODE=strict \
  python3 "$VALIDATOR" >/tmp/firemud-secret-compliance-invalid.out 2>&1; then
  echo "secret compliance validator accepted both lastProvisionedAt and lastRotationAt" >&2
  exit 1
fi
grep -q "exactly one of lastRotationAt/lastProvisionedAt" /tmp/firemud-secret-compliance-invalid.out

echo "secret compliance contract checks passed"
