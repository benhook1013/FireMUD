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
INVALID_TODAY_OUTPUT="$TMP_DIR/invalid-today.out"
BOOTSTRAP_BINDING_OUTPUT="$TMP_DIR/bootstrap-binding.out"
ASSET_MISSING_CLASS_OUTPUT="$TMP_DIR/asset-missing-class.out"
ASSET_INVALID_EVIDENCE_OUTPUT="$TMP_DIR/asset-invalid-evidence.out"
BACKUP_MISSING_CLASS_OUTPUT="$TMP_DIR/backup-missing-class.out"
INVALID_MAX_AGE_OUTPUT="$TMP_DIR/invalid-max-age.out"

python3 - "$VALIDATOR" <<'PY'
import importlib.util
import pathlib
import sys

module_path = pathlib.Path(sys.argv[1])
spec = importlib.util.spec_from_file_location("validate_secret_compliance", module_path)
if spec is None or spec.loader is None:
    raise SystemExit("could not load secret-compliance validator")
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)
if module.schema_issue_environment("staging: malformed record") != "staging":
    raise SystemExit("known schema environment prefix was not recognized")
for message in ("malformed record", "unknown: malformed record"):
    if module.schema_issue_environment(message) is not None:
        raise SystemExit(f"unknown schema prefix was treated as authoritative: {message}")

failures = []
warnings = []
non_authorizing = []
module.record_schema_issue_outcome(
    "staging: malformed record", "advisory", failures, warnings, non_authorizing
)
if failures or warnings != ["staging: malformed record"] or non_authorizing != ["staging"]:
    raise SystemExit("known-prefix advisory schema issue did not fail closed correctly")

failures = []
warnings = []
non_authorizing = []
module.record_schema_issue_outcome(
    "unknown: malformed record", "advisory", failures, warnings, non_authorizing
)
if (
    len(failures) != 1
    or not failures[0].startswith("Internal validator error: schema issue lacks a known environment prefix:")
    or warnings
    or non_authorizing
):
    raise SystemExit("unknown-prefix advisory schema issue did not fail closed")

failures = []
warnings = []
non_authorizing = []
module.record_schema_issue_outcome(
    "staging: malformed record", "strict", failures, warnings, non_authorizing
)
if failures != ["staging: malformed record"] or warnings or non_authorizing:
    raise SystemExit("strict schema issue collection changed unexpectedly")
PY

mkdir -p "$TMP_DIR/design/operations/secret-compliance/evidence"

write_expected_binding_files() {
  local asset_enabled="$1"
  for env in production staging hobby-self-hosted; do
    local directory="$TMP_DIR/design/operations/environments/$env"
    mkdir -p "$directory"
    cat >"$directory/expected-bindings.yaml" <<YAML
environment: $env
backupStorage:
  enabled: true
assetStorage:
  enabled: $asset_enabled
YAML
  done
}

write_expected_binding_files true

write_evidence_fixture() {
  python3 - "$TMP_DIR" "$VALIDATOR" <<'PY'
import importlib.util
import json
import pathlib
import sys

root = pathlib.Path(sys.argv[1])
validator_path = pathlib.Path(sys.argv[2])
spec = importlib.util.spec_from_file_location(
    "secret_compliance_fixture", validator_path
)
if spec is None or spec.loader is None:
    raise SystemExit("could not load secret-compliance validator")
module = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = module
spec.loader.exec_module(module)
evidence_dir = root / "design/operations/secret-compliance/evidence"
classes = (
    "jwt-signing-keys-jwks",
    "postgres-application-credentials",
    "backup-object-store-credentials",
    "asset-store-credentials",
    "operator-credentials",
)
for env in ("production", "staging", "hobby-self-hosted"):
    evidence = {
        "environment": env,
        "bootstrapOperationId": "bootstrap-contract-test-01",
        "provisioningGeneration": 1,
        "records": {},
    }
    for class_name in classes:
        record = {
            "targetEnvironment": env,
            "credentialClass": class_name,
            "evidenceOperationId": f"rotation-{env}-{class_name}",
        }
        if class_name == "jwt-signing-keys-jwks":
            record.update(
                {
                    "bootstrapOperationId": "bootstrap-contract-test-01",
                    "provisioningGeneration": 1,
                }
            )
        record["immutableArtifactId"] = module.canonical_evidence_digest(record)
        evidence["records"][class_name] = record
    (evidence_dir / f"{env}.json").write_text(
        json.dumps(evidence) + "\n", encoding="utf-8"
    )
PY
}

write_evidence_fixture

if SECRET_COMPLIANCE_ROOT="$TMP_DIR" \
  SECRET_COMPLIANCE_TODAY=2026-04-24T00:00:00 \
  SECRET_COMPLIANCE_ENFORCEMENT_MODE=strict \
  python3 "$VALIDATOR" >"$INVALID_TODAY_OUTPUT" 2>&1; then
  echo "secret compliance validator accepted a naive SECRET_COMPLIANCE_TODAY override" >&2
  exit 1
fi
grep -q \
  "SECRET_COMPLIANCE_TODAY override must be an ISO-8601 timestamp with an explicit timezone" \
  "$INVALID_TODAY_OUTPUT"

python3 - "$ROOT_DIR" <<'PY'
import importlib.util
import pathlib
import sys

root = pathlib.Path(sys.argv[1])
spec = importlib.util.spec_from_file_location(
    "secret_compliance_contract",
    root / "dev-tools/validation/validate-secret-compliance.py",
)
module = importlib.util.module_from_spec(spec)
assert spec.loader is not None
sys.modules[spec.name] = module
spec.loader.exec_module(module)

expected = "sha256:fd8b688bfa8b71822975ab3519e20b09e43b67d382a9f32831bfa384df21a82d"
actual = module.canonical_evidence_digest({"\ufffd": 2, "\U0001f600": 1})
if actual != expected:
    raise SystemExit(f"RFC 8785 UTF-16 key ordering drifted: {actual}")
for invalid_record in ({"value": 9_007_199_254_740_992}, {"value": 1.5}):
    try:
        module.canonical_evidence_digest(invalid_record)
    except TypeError:
        pass
    else:
        raise SystemExit(f"non-interoperable evidence number was accepted: {invalid_record}")
PY

write_compliance_file() {
  local env="$1"
  local timestamp_field="$2"
  local extra_timestamp_field=""
  local operation_status="completed"
  if [ "$#" -ge 3 ]; then
    extra_timestamp_field="$3"
  fi
  if [ "$#" -ge 4 ]; then
    operation_status="$4"
  fi
  python3 - "$TMP_DIR" "$env" "$timestamp_field" "$extra_timestamp_field" "$operation_status" <<'PY'
import json
import pathlib
import sys

root = pathlib.Path(sys.argv[1])
env, timestamp_field, extra_timestamp_field, operation_status = sys.argv[2:]
classes = (
    "jwt-signing-keys-jwks",
    "postgres-application-credentials",
    "backup-object-store-credentials",
    "asset-store-credentials",
    "operator-credentials",
)
record = {
    "environment": env,
    "provisioningState": "provisioned",
    "bootstrapOperationStatus": operation_status,
    "bootstrapOperationId": "bootstrap-contract-test-01",
    "provisioningGeneration": 1,
    "credentialClasses": {},
}
for class_name in classes:
    credential_timestamp_field = (
        timestamp_field if class_name == "jwt-signing-keys-jwks" else "lastRotationAt"
    )
    credential = {
        "maxAgeDays": 30,
        credential_timestamp_field: "2026-04-20T00:00:00Z",
        "evidenceRef": f"design/operations/secret-compliance/evidence/{env}.json",
        "evidenceKey": class_name,
    }
    if extra_timestamp_field and class_name == "jwt-signing-keys-jwks":
        credential[extra_timestamp_field] = "2026-04-20T00:00:00Z"
    if timestamp_field == "lastProvisionedAt" and class_name == "jwt-signing-keys-jwks":
        credential.update(
            {
                "bootstrapOperationId": "bootstrap-contract-test-01",
                "provisioningGeneration": 1,
            }
        )
    else:
        credential["evidenceOperationId"] = f"rotation-{env}-{class_name}"
    record["credentialClasses"][class_name] = credential
(root / "design/operations/secret-compliance" / f"{env}.yaml").write_text(
    json.dumps(record) + "\n", encoding="utf-8"
)
PY
}

mutate_bootstrap_binding() {
  local mode="$1"
  python3 - \
    "$TMP_DIR/design/operations/secret-compliance/production.yaml" \
    "$TMP_DIR/design/operations/secret-compliance/evidence/production.json" \
    "$mode" \
    "$VALIDATOR" <<'PY'
import importlib.util
import json
import pathlib
import sys

compliance_path = pathlib.Path(sys.argv[1])
evidence_path = pathlib.Path(sys.argv[2])
mode = sys.argv[3]
validator_path = pathlib.Path(sys.argv[4])
spec = importlib.util.spec_from_file_location(
    "secret_compliance_mutation", validator_path
)
if spec is None or spec.loader is None:
    raise SystemExit("could not load secret-compliance validator")
module = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = module
spec.loader.exec_module(module)
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

evidence_record["immutableArtifactId"] = module.canonical_evidence_digest(evidence_record)

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

python3 - "$TMP_DIR/design/operations/environments/staging/expected-bindings.yaml" <<'PY'
import pathlib
import sys

path = pathlib.Path(sys.argv[1])
path.write_text("environment: production\nbackupStorage:\n  enabled: true\n", encoding="utf-8")
PY
if SECRET_COMPLIANCE_ROOT="$TMP_DIR" \
  SECRET_COMPLIANCE_TODAY=2026-04-24T00:00:00Z \
  SECRET_COMPLIANCE_ENFORCEMENT_MODE=strict \
  python3 "$VALIDATOR" >"$HOBBY_SCHEMA_INVALID_OUTPUT" 2>&1; then
  echo "secret compliance validator accepted a wrong-environment expected-bindings manifest" >&2
  exit 1
fi
grep -q "canonical expected-bindings manifest must target 'staging'" "$HOBBY_SCHEMA_INVALID_OUTPUT"
if grep -q "staging: missing required credential classes" "$HOBBY_SCHEMA_INVALID_OUTPUT"; then
  echo "invalid staging expected-bindings manifest produced a misleading missing-class cascade" >&2
  exit 1
fi
write_expected_binding_files true

python3 - "$TMP_DIR/design/operations/secret-compliance/production.yaml" <<'PY'
import json
import pathlib
import sys

path = pathlib.Path(sys.argv[1])
record = json.loads(path.read_text(encoding="utf-8"))
record["credentialClasses"]["postgres-application-credentials"].pop(
    "evidenceOperationId"
)
path.write_text(json.dumps(record) + "\n", encoding="utf-8")
PY
if SECRET_COMPLIANCE_ROOT="$TMP_DIR" \
  SECRET_COMPLIANCE_TODAY=2026-04-24T00:00:00Z \
  SECRET_COMPLIANCE_ENFORCEMENT_MODE=strict \
  python3 "$VALIDATOR" >"$INVALID_OUTPUT" 2>&1; then
  echo "secret compliance validator accepted a non-bootstrap record without evidenceOperationId" >&2
  exit 1
fi
grep -q "non-bootstrap credential records require a stable evidenceOperationId" "$INVALID_OUTPUT"

write_evidence_fixture
write_compliance_file production lastRotationAt
python3 - "$TMP_DIR/design/operations/secret-compliance/evidence/production.json" <<'PY'
import json
import pathlib
import sys

path = pathlib.Path(sys.argv[1])
evidence = json.loads(path.read_text(encoding="utf-8"))
evidence["environment"] = "staging"
path.write_text(json.dumps(evidence) + "\n", encoding="utf-8")
PY
if SECRET_COMPLIANCE_ROOT="$TMP_DIR" \
  SECRET_COMPLIANCE_TODAY=2026-04-24T00:00:00Z \
  SECRET_COMPLIANCE_ENFORCEMENT_MODE=strict \
  python3 "$VALIDATOR" >"$INVALID_OUTPUT" 2>&1; then
  echo "secret compliance validator accepted an evidence payload for the wrong environment" >&2
  exit 1
fi
grep -q "evidence payload environment must exactly match 'production'" "$INVALID_OUTPUT"

write_evidence_fixture
write_compliance_file production lastRotationAt
python3 - "$TMP_DIR/design/operations/secret-compliance/production.yaml" <<'PY'
import json
import pathlib
import sys

path = pathlib.Path(sys.argv[1])
record = json.loads(path.read_text(encoding="utf-8"))
record["credentialClasses"]["operator-credentials"]["lastRotationAt"] = (
    "2026-04-25T00:00:00Z"
)
path.write_text(json.dumps(record) + "\n", encoding="utf-8")
PY
if SECRET_COMPLIANCE_ROOT="$TMP_DIR" \
  SECRET_COMPLIANCE_TODAY=2026-04-24T00:00:00Z \
  SECRET_COMPLIANCE_ENFORCEMENT_MODE=strict \
  python3 "$VALIDATOR" >"$INVALID_OUTPUT" 2>&1; then
  echo "secret compliance validator accepted a future freshness timestamp" >&2
  exit 1
fi
grep -q "freshness timestamp must not be in the future" "$INVALID_OUTPUT"

write_evidence_fixture
write_compliance_file production lastRotationAt
python3 - "$TMP_DIR/design/operations/secret-compliance/production.yaml" <<'PY'
import json
import pathlib
import sys

path = pathlib.Path(sys.argv[1])
record = json.loads(path.read_text(encoding="utf-8"))
record["credentialClasses"]["operator-credentials"]["lastRotationAt"] = (
    "2026-04-20T00:00:00"
)
path.write_text(json.dumps(record) + "\n", encoding="utf-8")
PY
if SECRET_COMPLIANCE_ROOT="$TMP_DIR" \
  SECRET_COMPLIANCE_TODAY=2026-04-24T00:00:00Z \
  SECRET_COMPLIANCE_ENFORCEMENT_MODE=strict \
  python3 "$VALIDATOR" >"$INVALID_OUTPUT" 2>&1; then
  echo "secret compliance validator accepted a naive freshness timestamp" >&2
  exit 1
fi
grep -q "timestamp must include an explicit timezone" "$INVALID_OUTPUT"

for invalid_max_age in "not-a-number" 0 -1 1.5 true; do
  write_evidence_fixture
  write_compliance_file production lastRotationAt
  python3 - "$TMP_DIR/design/operations/secret-compliance/production.yaml" "$invalid_max_age" <<'PY'
import json
import pathlib
import sys

path = pathlib.Path(sys.argv[1])
raw_value = sys.argv[2]
if raw_value == "true":
    value = True
elif raw_value in {"0", "-1"}:
    value = int(raw_value)
elif raw_value == "1.5":
    value = 1.5
else:
    value = raw_value
record = json.loads(path.read_text(encoding="utf-8"))
record["credentialClasses"]["operator-credentials"]["maxAgeDays"] = value
path.write_text(json.dumps(record) + "\n", encoding="utf-8")
PY
  if SECRET_COMPLIANCE_ROOT="$TMP_DIR" \
    SECRET_COMPLIANCE_TODAY=2026-04-24T00:00:00Z \
    SECRET_COMPLIANCE_ENFORCEMENT_MODE=strict \
    python3 "$VALIDATOR" >"$INVALID_MAX_AGE_OUTPUT" 2>&1; then
    echo "secret compliance validator accepted invalid maxAgeDays: $invalid_max_age" >&2
    exit 1
  fi
  grep -q "operator-credentials: maxAgeDays must be a positive integer" \
    "$INVALID_MAX_AGE_OUTPUT"
done

write_evidence_fixture
write_compliance_file production lastRotationAt
python3 - "$TMP_DIR/design/operations/secret-compliance/production.yaml" <<'PY'
import json
import pathlib
import sys

path = pathlib.Path(sys.argv[1])
record = json.loads(path.read_text(encoding="utf-8"))
record["credentialClasses"].pop("asset-store-credentials")
path.write_text(json.dumps(record) + "\n", encoding="utf-8")
PY
if SECRET_COMPLIANCE_ROOT="$TMP_DIR" \
  SECRET_COMPLIANCE_TODAY=2026-04-24T00:00:00Z \
  SECRET_COMPLIANCE_ENFORCEMENT_MODE=strict \
  python3 "$VALIDATOR" >"$ASSET_MISSING_CLASS_OUTPUT" 2>&1; then
  echo "assetStorage-enabled compliance accepted a missing asset-store-credentials class" >&2
  exit 1
fi
grep -q "production: missing required credential classes: asset-store-credentials" "$ASSET_MISSING_CLASS_OUTPUT"

write_evidence_fixture
write_compliance_file production lastRotationAt
python3 - "$TMP_DIR/design/operations/secret-compliance/evidence/production.json" <<'PY'
import json
import pathlib
import sys

path = pathlib.Path(sys.argv[1])
evidence = json.loads(path.read_text(encoding="utf-8"))
evidence["records"]["asset-store-credentials"]["immutableArtifactId"] = "test:asset:missing-digest"
path.write_text(json.dumps(evidence) + "\n", encoding="utf-8")
PY
if SECRET_COMPLIANCE_ROOT="$TMP_DIR" \
  SECRET_COMPLIANCE_TODAY=2026-04-24T00:00:00Z \
  SECRET_COMPLIANCE_ENFORCEMENT_MODE=strict \
  python3 "$VALIDATOR" >"$ASSET_INVALID_EVIDENCE_OUTPUT" 2>&1; then
  echo "assetStorage-enabled compliance accepted invalid asset evidence" >&2
  exit 1
fi
grep -q "asset-store-credentials: immutableArtifactId" "$ASSET_INVALID_EVIDENCE_OUTPUT"

# Disabled asset storage does not add the conditional class requirement.
write_expected_binding_files false
write_evidence_fixture
write_compliance_file production lastRotationAt
python3 - "$TMP_DIR/design/operations/secret-compliance/production.yaml" <<'PY'
import json
import pathlib
import sys

path = pathlib.Path(sys.argv[1])
record = json.loads(path.read_text(encoding="utf-8"))
record["credentialClasses"].pop("asset-store-credentials")
path.write_text(json.dumps(record) + "\n", encoding="utf-8")
PY
SECRET_COMPLIANCE_ROOT="$TMP_DIR" \
  SECRET_COMPLIANCE_TODAY=2026-04-24T00:00:00Z \
  SECRET_COMPLIANCE_ENFORCEMENT_MODE=strict \
  python3 "$VALIDATOR" >"$VALID_OUTPUT"
write_expected_binding_files true
write_evidence_fixture
write_compliance_file production lastRotationAt

# Enabled backup storage requires the backup credential class.
python3 - "$TMP_DIR/design/operations/secret-compliance/staging.yaml" <<'PY'
import json
import pathlib
import sys

path = pathlib.Path(sys.argv[1])
record = json.loads(path.read_text(encoding="utf-8"))
record["credentialClasses"].pop("backup-object-store-credentials")
path.write_text(json.dumps(record) + "\n", encoding="utf-8")
PY
if SECRET_COMPLIANCE_ROOT="$TMP_DIR" \
  SECRET_COMPLIANCE_TODAY=2026-04-24T00:00:00Z \
  SECRET_COMPLIANCE_ENFORCEMENT_MODE=strict \
  python3 "$VALIDATOR" >"$BACKUP_MISSING_CLASS_OUTPUT" 2>&1; then
  echo "backupStorage-enabled compliance accepted a missing backup credential class" >&2
  exit 1
fi
grep -q "staging: missing required credential classes: backup-object-store-credentials" \
  "$BACKUP_MISSING_CLASS_OUTPUT"

# Disabled non-production backup storage does not require that class.
write_compliance_file staging lastRotationAt
python3 - \
  "$TMP_DIR/design/operations/environments/staging/expected-bindings.yaml" \
  "$TMP_DIR/design/operations/secret-compliance/staging.yaml" <<'PY'
import json
import pathlib
import sys

import yaml

bindings_path = pathlib.Path(sys.argv[1])
bindings = yaml.safe_load(bindings_path.read_text(encoding="utf-8"))
bindings["backupStorage"]["enabled"] = False
bindings_path.write_text(yaml.safe_dump(bindings, sort_keys=False), encoding="utf-8")

record_path = pathlib.Path(sys.argv[2])
record = json.loads(record_path.read_text(encoding="utf-8"))
record["credentialClasses"].pop("backup-object-store-credentials")
record_path.write_text(json.dumps(record) + "\n", encoding="utf-8")
PY
SECRET_COMPLIANCE_ROOT="$TMP_DIR" \
  SECRET_COMPLIANCE_TODAY=2026-04-24T00:00:00Z \
  SECRET_COMPLIANCE_ENFORCEMENT_MODE=strict \
  python3 "$VALIDATOR" >"$VALID_OUTPUT"
write_expected_binding_files true
write_compliance_file staging lastRotationAt

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

write_evidence_fixture
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
grep -q "authorization/readiness was not established" "$NOT_PROVISIONED_OUTPUT"

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
if ! SECRET_COMPLIANCE_ROOT="$TMP_DIR" \
  SECRET_COMPLIANCE_TODAY=2026-12-20T00:00:00Z \
  SECRET_COMPLIANCE_ENFORCEMENT_MODE=advisory \
  python3 "$VALIDATOR" >"$NONCOMPLIANT_OUTPUT" 2>&1; then
  echo "secret compliance advisory mode rejected a noncompliant projection" >&2
  exit 1
fi
grep -q "provisioningState=noncompliant cannot satisfy a provisioning compliance gate" \
  "$NONCOMPLIANT_OUTPUT"
grep -q "authorization/readiness was not established for: production" "$NONCOMPLIANT_OUTPUT"

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
