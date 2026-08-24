#!/usr/bin/env bash
# Contract checks for dev-tools/validation/validate-secret-compliance.py.
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
VALIDATOR="$ROOT_DIR/dev-tools/validation/validate-secret-compliance.py"
TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT
VALID_OUTPUT="$TMP_DIR/valid.out"
INVALID_OUTPUT="$TMP_DIR/invalid.out"
NOT_PROVISIONED_OUTPUT="$TMP_DIR/not-provisioned.out"
NOT_PROVISIONED_INVALID_OUTPUT="$TMP_DIR/not-provisioned-invalid.out"
NOT_PROVISIONED_OPERATION_INVALID_OUTPUT="$TMP_DIR/not-provisioned-operation-invalid.out"
HOBBY_SCHEMA_INVALID_OUTPUT="$TMP_DIR/hobby-schema-invalid.out"
MISSING_STATE_OUTPUT="$TMP_DIR/missing-state.out"
MISSING_OPERATION_STATUS_OUTPUT="$TMP_DIR/missing-operation-status.out"
WRONG_OPERATION_STATUS_OUTPUT="$TMP_DIR/wrong-operation-status.out"
NONCOMPLIANT_OUTPUT="$TMP_DIR/noncompliant.out"
HOBBY_NONCOMPLIANT_OUTPUT="$TMP_DIR/hobby-noncompliant.out"
MISSING_CREDENTIAL_CLASSES_OUTPUT="$TMP_DIR/missing-credential-classes.out"
BOOTSTRAP_BINDING_OUTPUT="$TMP_DIR/bootstrap-binding.out"

mkdir -p "$TMP_DIR/design/operations/secret-compliance/evidence"

write_evidence_fixture() {
  cat >"$TMP_DIR/design/operations/secret-compliance/evidence/evidence.json" <<'JSON'
  {
    "bootstrapOperationId": "bootstrap-contract-test-01",
    "provisioningGeneration": 1,
    "records": {
      "jwt-signing-keys-jwks": {
        "bootstrapOperationId": "bootstrap-contract-test-01",
        "provisioningGeneration": 1,
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
}

write_evidence_fixture

write_compliance_file() {
  local env="$1"
  local timestamp_field="$2"
  local extra_timestamp_field="${3:-}"
  local operation_status="${4:-completed}"
  local bootstrap_fields=""
  if [[ "$timestamp_field" == "lastProvisionedAt" ]]; then
    bootstrap_fields='      "bootstrapOperationId": "bootstrap-contract-test-01",
      "provisioningGeneration": 1,'
  fi
  local path="$TMP_DIR/design/operations/secret-compliance/$env.yaml"
  cat >"$path" <<YAML
{
  "environment": "$env",
  "provisioningState": "provisioned",
  "bootstrapOperationStatus": "$operation_status",
  "bootstrapOperationId": "bootstrap-contract-test-01",
  "provisioningGeneration": 1,
  "credentialClasses": {
    "jwt-signing-keys-jwks": {
      "maxAgeDays": 30,
      "$timestamp_field": "2026-04-20T00:00:00Z",
      ${extra_timestamp_field:+"\"$extra_timestamp_field\": \"2026-04-20T00:00:00Z\","}
      ${bootstrap_fields}
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

mutate_bootstrap_binding() {
  local mode="$1"
  python3 - \
    "$TMP_DIR/design/operations/secret-compliance/production.yaml" \
    "$TMP_DIR/design/operations/secret-compliance/evidence/evidence.json" \
    "$mode" <<'PY'
import json
import pathlib
import sys

compliance_path = pathlib.Path(sys.argv[1])
evidence_path = pathlib.Path(sys.argv[2])
mode = sys.argv[3]
compliance = json.loads(compliance_path.read_text(encoding="utf-8"))
evidence = json.loads(evidence_path.read_text(encoding="utf-8"))
credential = compliance["credentialClasses"]["jwt-signing-keys-jwks"]
evidence_record = evidence["records"]["jwt-signing-keys-jwks"]

if mode == "missing-credential-operation-id":
    credential.pop("bootstrapOperationId", None)
elif mode == "mismatched-credential-generation":
    credential["provisioningGeneration"] = 2
elif mode == "missing-evidence-operation-id":
    evidence_record.pop("bootstrapOperationId", None)
elif mode == "mismatched-evidence-generation":
    evidence_record["provisioningGeneration"] = 2
elif mode == "missing-evidence-payload-operation-id":
    evidence.pop("bootstrapOperationId", None)
elif mode == "mismatched-evidence-payload-generation":
    evidence["provisioningGeneration"] = 2
else:
    raise SystemExit(f"unknown bootstrap binding mutation: {mode}")

compliance_path.write_text(json.dumps(compliance) + "\n", encoding="utf-8")
evidence_path.write_text(json.dumps(evidence) + "\n", encoding="utf-8")
PY
}

expect_bootstrap_binding_failure() {
  local mode="$1"
  local expected_message="$2"
  write_evidence_fixture
  write_compliance_file production lastProvisionedAt
  mutate_bootstrap_binding "$mode"
  if SECRET_COMPLIANCE_ROOT="$TMP_DIR" \
    SECRET_COMPLIANCE_TODAY=2026-04-24T00:00:00Z \
    SECRET_COMPLIANCE_ENFORCEMENT_MODE=strict \
    python3 "$VALIDATOR" >"$BOOTSTRAP_BINDING_OUTPUT" 2>&1; then
    echo "secret compliance validator accepted bootstrap binding mutation: $mode" >&2
    exit 1
  fi
  grep -q "$expected_message" "$BOOTSTRAP_BINDING_OUTPUT"
}

write_not_provisioned_file() {
  local env="$1"
  local credential_classes="${2:-}"
  if [[ -z "$credential_classes" ]]; then
    credential_classes="{}"
  fi
  local path="$TMP_DIR/design/operations/secret-compliance/$env.yaml"
  cat >"$path" <<YAML
{
  "environment": "$env",
  "provisioningState": "not-provisioned",
  "credentialClasses": $credential_classes
}
YAML
}

write_compliance_file production lastProvisionedAt
write_compliance_file staging lastRotationAt
write_compliance_file hobby-self-hosted lastRotationAt

SECRET_COMPLIANCE_ROOT="$TMP_DIR" \
  SECRET_COMPLIANCE_TODAY=2026-04-24T00:00:00Z \
  SECRET_COMPLIANCE_ENFORCEMENT_MODE=strict \
  python3 "$VALIDATOR" >"$VALID_OUTPUT"

expect_bootstrap_binding_failure \
  missing-credential-operation-id \
  "bootstrap credential record bootstrapOperationId must exactly match top-level bootstrapOperationId"
expect_bootstrap_binding_failure \
  mismatched-credential-generation \
  "bootstrap credential record provisioningGeneration must exactly match top-level provisioningGeneration"
expect_bootstrap_binding_failure \
  missing-evidence-operation-id \
  "bootstrap evidence record bootstrapOperationId must exactly match top-level bootstrapOperationId"
expect_bootstrap_binding_failure \
  mismatched-evidence-generation \
  "bootstrap evidence record provisioningGeneration must exactly match top-level provisioningGeneration"
expect_bootstrap_binding_failure \
  missing-evidence-payload-operation-id \
  "bootstrap evidence payload bootstrapOperationId must exactly match top-level bootstrapOperationId"
expect_bootstrap_binding_failure \
  mismatched-evidence-payload-generation \
  "bootstrap evidence payload provisioningGeneration must exactly match top-level provisioningGeneration"

write_compliance_file production lastRotationAt
python3 - "$TMP_DIR/design/operations/secret-compliance/production.yaml" <<'PY'
import json
import pathlib
import sys

path = pathlib.Path(sys.argv[1])
record = json.loads(path.read_text(encoding="utf-8"))
record.pop("bootstrapOperationStatus", None)
path.write_text(json.dumps(record) + "\n", encoding="utf-8")
PY
if SECRET_COMPLIANCE_ROOT="$TMP_DIR" \
  SECRET_COMPLIANCE_TODAY=2026-04-24T00:00:00Z \
  SECRET_COMPLIANCE_ENFORCEMENT_MODE=strict \
  python3 "$VALIDATOR" >"$MISSING_OPERATION_STATUS_OUTPUT" 2>&1; then
  echo "secret compliance validator accepted a provisioned record without bootstrapOperationStatus" >&2
  exit 1
fi
grep -q "bootstrapOperationStatus must be one of" "$MISSING_OPERATION_STATUS_OUTPUT"

write_compliance_file production lastRotationAt "" pending
if SECRET_COMPLIANCE_ROOT="$TMP_DIR" \
  SECRET_COMPLIANCE_TODAY=2026-04-24T00:00:00Z \
  SECRET_COMPLIANCE_ENFORCEMENT_MODE=strict \
  python3 "$VALIDATOR" >"$WRONG_OPERATION_STATUS_OUTPUT" 2>&1; then
  echo "secret compliance validator accepted a provisioned record with pending bootstrapOperationStatus" >&2
  exit 1
fi
grep -q "provisioningState=provisioned requires bootstrapOperationStatus=completed" \
  "$WRONG_OPERATION_STATUS_OUTPUT"

write_compliance_file production lastProvisionedAt lastRotationAt
if SECRET_COMPLIANCE_ROOT="$TMP_DIR" \
  SECRET_COMPLIANCE_TODAY=2026-04-24T00:00:00Z \
  SECRET_COMPLIANCE_ENFORCEMENT_MODE=strict \
  python3 "$VALIDATOR" >"$INVALID_OUTPUT" 2>&1; then
  echo "secret compliance validator accepted both lastProvisionedAt and lastRotationAt" >&2
  exit 1
fi
grep -q "exactly one of lastRotationAt/lastProvisionedAt" "$INVALID_OUTPUT"

write_not_provisioned_file production
write_not_provisioned_file staging
write_not_provisioned_file hobby-self-hosted
SECRET_COMPLIANCE_ROOT="$TMP_DIR" \
  SECRET_COMPLIANCE_TODAY=2026-12-20T00:00:00Z \
  SECRET_COMPLIANCE_ENFORCEMENT_MODE=strict \
  python3 "$VALIDATOR" >"$NOT_PROVISIONED_OUTPUT"

write_not_provisioned_file production '{"unexpected": {}}'
if SECRET_COMPLIANCE_ROOT="$TMP_DIR" \
  SECRET_COMPLIANCE_TODAY=2026-12-20T00:00:00Z \
  SECRET_COMPLIANCE_ENFORCEMENT_MODE=strict \
  python3 "$VALIDATOR" >"$NOT_PROVISIONED_INVALID_OUTPUT" 2>&1; then
  echo "secret compliance validator accepted credential evidence for an unprovisioned environment" >&2
  exit 1
fi
grep -q "not-provisioned compliance records must not list credential classes" "$NOT_PROVISIONED_INVALID_OUTPUT"

cat >"$TMP_DIR/design/operations/secret-compliance/production.yaml" <<'YAML'
{
  "environment": "production",
  "provisioningState": "not-provisioned",
  "bootstrapOperationStatus": "completed",
  "bootstrapOperationId": "bootstrap-contract-test-01",
  "provisioningGeneration": 1,
  "credentialClasses": {}
}
YAML
if SECRET_COMPLIANCE_ROOT="$TMP_DIR" \
  SECRET_COMPLIANCE_TODAY=2026-12-20T00:00:00Z \
  SECRET_COMPLIANCE_ENFORCEMENT_MODE=strict \
  python3 "$VALIDATOR" >"$NOT_PROVISIONED_OPERATION_INVALID_OUTPUT" 2>&1; then
  echo "secret compliance validator accepted bootstrap operation fields for an unprovisioned environment" >&2
  exit 1
fi
grep -q "not-provisioned compliance records must not contain bootstrap operation fields" \
  "$NOT_PROVISIONED_OPERATION_INVALID_OUTPUT"

cat >"$TMP_DIR/design/operations/secret-compliance/production.yaml" <<'YAML'
{
  "environment": "production",
  "provisioningState": "noncompliant",
  "bootstrapOperationStatus": "completed",
  "bootstrapOperationId": "bootstrap-contract-test-01",
  "provisioningGeneration": 1,
  "credentialClasses": {}
}
YAML
if SECRET_COMPLIANCE_ROOT="$TMP_DIR" \
  SECRET_COMPLIANCE_TODAY=2026-12-20T00:00:00Z \
  SECRET_COMPLIANCE_ENFORCEMENT_MODE=strict \
  python3 "$VALIDATOR" >"$NONCOMPLIANT_OUTPUT" 2>&1; then
  echo "secret compliance validator treated a noncompliant projection as provisioned" >&2
  exit 1
fi
grep -q "provisioningState=noncompliant cannot satisfy a provisioning compliance gate" \
  "$NONCOMPLIANT_OUTPUT"

write_not_provisioned_file production
write_not_provisioned_file staging
cat >"$TMP_DIR/design/operations/secret-compliance/hobby-self-hosted.yaml" <<'YAML'
{
  "environment": "hobby-self-hosted",
  "provisioningState": "noncompliant",
  "bootstrapOperationStatus": "completed",
  "bootstrapOperationId": "bootstrap-contract-test-01",
  "provisioningGeneration": 1,
  "credentialClasses": {}
}
YAML
if SECRET_COMPLIANCE_ROOT="$TMP_DIR" \
  SECRET_COMPLIANCE_TODAY=2026-04-24T00:00:00Z \
  SECRET_COMPLIANCE_ENFORCEMENT_MODE=strict \
  python3 "$VALIDATOR" >"$HOBBY_NONCOMPLIANT_OUTPUT" 2>&1; then
  echo "secret compliance validator treated a noncompliant hobby projection as advisory" >&2
  exit 1
fi
grep -q "provisioningState=noncompliant cannot satisfy a provisioning compliance gate" \
  "$HOBBY_NONCOMPLIANT_OUTPUT"

write_not_provisioned_file staging
write_not_provisioned_file hobby-self-hosted
cat >"$TMP_DIR/design/operations/secret-compliance/production.yaml" <<'YAML'
{
  "environment": "production",
  "provisioningState": "not-provisioned"
}
YAML
if SECRET_COMPLIANCE_ROOT="$TMP_DIR" \
  SECRET_COMPLIANCE_TODAY=2026-12-20T00:00:00Z \
  SECRET_COMPLIANCE_ENFORCEMENT_MODE=strict \
  python3 "$VALIDATOR" >"$MISSING_CREDENTIAL_CLASSES_OUTPUT" 2>&1; then
  echo "secret compliance validator accepted missing credentialClasses" >&2
  exit 1
fi
grep -q "credentialClasses must be present" "$MISSING_CREDENTIAL_CLASSES_OUTPUT"

write_not_provisioned_file production
write_not_provisioned_file hobby-self-hosted '{"unexpected": {}}'
if SECRET_COMPLIANCE_ROOT="$TMP_DIR" \
  SECRET_COMPLIANCE_TODAY=2026-12-20T00:00:00Z \
  SECRET_COMPLIANCE_ENFORCEMENT_MODE=strict \
  python3 "$VALIDATOR" >"$HOBBY_SCHEMA_INVALID_OUTPUT" 2>&1; then
  echo "secret compliance validator treated an invalid hobby record as advisory" >&2
  exit 1
fi
grep -q "not-provisioned compliance records must not list credential classes" "$HOBBY_SCHEMA_INVALID_OUTPUT"

cat >"$TMP_DIR/design/operations/secret-compliance/production.yaml" <<'YAML'
{
  "environment": "production",
  "credentialClasses": {}
}
YAML
if SECRET_COMPLIANCE_ROOT="$TMP_DIR" \
  SECRET_COMPLIANCE_TODAY=2026-12-20T00:00:00Z \
  SECRET_COMPLIANCE_ENFORCEMENT_MODE=strict \
  python3 "$VALIDATOR" >"$MISSING_STATE_OUTPUT" 2>&1; then
  echo "secret compliance validator accepted a record without provisioningState" >&2
  exit 1
fi
grep -q "provisioningState must be one of" "$MISSING_STATE_OUTPUT"

echo "secret compliance contract checks passed"
