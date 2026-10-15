#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
TEST_ROOT="$(mktemp -d "${TMPDIR:-/tmp}/firemud-run-owned-admission-contract.XXXXXX")"
trap 'rm -rf -- "$TEST_ROOT"' EXIT
chmod 700 "$TEST_ROOT"

docker() {
  case "$1 $2" in
    "ps -a"|"network ls"|"volume ls") return 0 ;;
    *)
      echo "Unexpected Docker command in capability contract: $*" >&2
      return 1
      ;;
  esac
}
export -f docker

# shellcheck disable=SC1091 # The repository root is resolved at runtime.
source "$ROOT_DIR/dev-tools/smoke/run-owned-compose.sh"

ownership_dir="$TEST_ROOT/ownership"
mkdir -m 700 -- "$ownership_dir"
export FIREMUD_SMOKE_TEST_MODE=1
export FIREMUD_SMOKE_OWNERSHIP_DIR="$ownership_dir"
unset GITHUB_ACTIONS GITHUB_RUN_ID GITHUB_RUN_ATTEMPT GITHUB_JOB

expect_failure() {
  local label="$1"
  shift
  local output="$TEST_ROOT/failure-output"
  if "$@" >"$output" 2>&1; then
    echo "Expected refusal for $label, but the capability helper accepted it." >&2
    exit 1
  fi
}

load_operation_id() {
  python3 - "$1" "$FIREMUD_SMOKE_INITIAL_ADMISSION_CLAIM_DIGEST" <<'PY'
import json
import pathlib
import sys

value = json.loads(pathlib.Path(sys.argv[1]).read_text(encoding="utf-8"))
expected_claim_digest = sys.argv[2]
expected_fields = {
    "schema", "runId", "composeProjectName", "operationId", "tenantId",
    "gameTemplateId", "ownerAccountId", "worldSlug", "worldDisplayName",
    "realmSlug", "realmDisplayName", "visible", "publicProductionRealm",
    "requiresCharacterSelection", "stateScope", "characterCreationPolicy",
    "gameSessionLeafSha256", "gameSessionUriSan", "worldManagementLeafSha256",
    "worldManagementUriSan", "caCertificateSha256", "claimDigest",
}
assert set(value) == expected_fields, set(value)
assert value["claimDigest"] == expected_claim_digest
assert value["schema"] == "firemud.run-owned-initial-admission-fixture.v1"
assert value["tenantId"] == 1
assert value["gameTemplateId"] == 1
assert value["ownerAccountId"] == 1
assert value["worldSlug"] == "demo"
assert value["worldDisplayName"] == "Demo World"
assert value["realmSlug"] == "production"
assert value["realmDisplayName"] == "Live Realm"
assert value["visible"] is True
assert value["publicProductionRealm"] is True
assert value["requiresCharacterSelection"] is False
assert value["stateScope"] == "SHARED"
assert value["characterCreationPolicy"] == "ALLOW_NEW"
assert value["gameSessionUriSan"] == "spiffe://firemud/ns/dev/sa/game-session-service"
assert value["worldManagementUriSan"] == "spiffe://firemud/ns/dev/sa/world-management-service"
assert len(value["worldManagementLeafSha256"]) == 64
print(value["operationId"])
PY
}

reject_wrong_claim() {
  FIREMUD_SMOKE_OWNERSHIP_TOKEN="$(printf 'f%.0s' {1..64})" \
    ensure_run_owned_initial_admission_capability "$base_certificate_root" create-or-verify
}

reject_wrong_run() {
  FIREMUD_SMOKE_RUN_ID=wrong-run \
    ensure_run_owned_initial_admission_capability "$base_certificate_root" create-or-verify
}

reject_wrong_project() {
  COMPOSE_PROJECT_NAME=wrong-project \
    ensure_run_owned_initial_admission_capability "$base_certificate_root" create-or-verify
}

reject_symlinked_capability() {
  local saved="$TEST_ROOT/capability-symlink-target.json"
  mv -- "$base_capability" "$saved"
  ln -s -- "$saved" "$base_capability"
  if ensure_run_owned_initial_admission_capability "$base_certificate_root" create-or-verify; then
    rm -f -- "$base_capability"
    mv -- "$saved" "$base_capability"
    return 0
  fi
  rm -f -- "$base_capability"
  mv -- "$saved" "$base_capability"
  return 1
}

reject_replaced_capability() {
  ensure_run_owned_initial_admission_capability "$base_certificate_root" create-or-verify
}

reject_claim_digest_variant() {
  local variant="$1"
  local backup="$TEST_ROOT/capability-before-claim-digest-$variant.json"
  local status=0
  cp -p -- "$base_capability" "$backup"
  python3 - "$TEST_ROOT/claim-digest-$variant.json" "$base_capability" "$variant" <<'PY'
import json
import pathlib
import sys

target, source, variant = sys.argv[1:]
value = json.loads(pathlib.Path(source).read_text(encoding="utf-8"))
if variant == "missing":
    del value["claimDigest"]
elif variant == "substituted":
    value["claimDigest"] = "0" * 64
else:
    raise SystemExit("unknown claim digest test variant")
pathlib.Path(target).write_text(json.dumps(value, indent=2) + "\n", encoding="utf-8")
PY
  chmod 444 "$TEST_ROOT/claim-digest-$variant.json"
  mv -- "$TEST_ROOT/claim-digest-$variant.json" "$base_capability"
  if ensure_run_owned_initial_admission_capability "$base_certificate_root" create-or-verify; then
    status=0
  else
    status=$?
  fi
  mv -- "$backup" "$base_capability"
  return "$status"
}

reject_missing_restart_capability() {
  ensure_run_owned_initial_admission_capability "$base_certificate_root" reuse-only
}

reject_missing_restart_receipt_directory() {
  ensure_run_owned_initial_admission_capability "$base_certificate_root" reuse-only
}

reject_wrong_github_job() {
  GITHUB_JOB=other-job ensure_run_owned_initial_admission_capability \
    "$base_certificate_root" create-or-verify
}

prepare_local_claim() {
  local run_id="$1"
  export FIREMUD_SMOKE_RUN_ID="$run_id"
  export COMPOSE_PROJECT_NAME="firemud-smoke-$run_id"
  FIREMUD_SMOKE_OWNERSHIP_TOKEN="$(openssl rand -hex 32)"
  export FIREMUD_SMOKE_OWNERSHIP_TOKEN
  claim_run_owned_compose_project
}

generate_local_certificates() {
  local certificate_root="$1"
  _firemud_smoke_unlock
  FIREMUD_COMPOSE_GRPC_MTLS_CERT_ROOT="$certificate_root" \
    bash "$ROOT_DIR/dev-tools/certs/ensure-dev-certs.sh" --compose-mtls "$certificate_root"
  claim_run_owned_compose_project
}

prepare_local_claim "cap-contract-local"
base_run_id="$FIREMUD_SMOKE_RUN_ID"
base_project="$COMPOSE_PROJECT_NAME"
base_token="$FIREMUD_SMOKE_OWNERSHIP_TOKEN"
base_certificate_root="$FIREMUD_SMOKE_OWNERSHIP_DIR_RESOLVED/$FIREMUD_SMOKE_PROJECT_KEY.grpc-mtls"
base_capability="$FIREMUD_SMOKE_OWNERSHIP_DIR_RESOLVED/$FIREMUD_SMOKE_PROJECT_KEY.initial-admission-capability.json"
base_receipt_directory="$FIREMUD_SMOKE_OWNERSHIP_DIR_RESOLVED/$FIREMUD_SMOKE_PROJECT_KEY.authored-world-source"
world_management_dir="$base_certificate_root/workloads/world-management-service"

expect_failure missing_tls ensure_run_owned_initial_admission_capability \
  "$base_certificate_root" create-or-verify
[[ ! -e "$base_capability" && "$FIREMUD_SMOKE_INITIAL_ADMISSION_FIXTURE_ENABLED" == false ]]

generate_local_certificates "$base_certificate_root"
ensure_run_owned_initial_admission_capability "$base_certificate_root" create-or-verify
[[ "$FIREMUD_SMOKE_INITIAL_ADMISSION_FIXTURE_ENABLED" == true ]]
[[ "$FIREMUD_SMOKE_COMPOSE_PROJECT_NAME" == "$base_project" ]]
[[ "$FIREMUD_SMOKE_INITIAL_ADMISSION_CAPABILITY_PATH" == /app/run-owned-initial-admission-capability.json ]]
[[ "$FIREMUD_SMOKE_INITIAL_ADMISSION_CAPABILITY_HOST_PATH" == "$base_capability" ]]
[[ "$FIREMUD_SMOKE_INITIAL_ADMISSION_CAPABILITY_HOST_PATH" != "$FIREMUD_SMOKE_INITIAL_ADMISSION_CAPABILITY_PATH" ]]
[[ "$FIREMUD_SMOKE_AUTHORED_WORLD_SOURCE_RECEIPT_DIR_HOST_PATH" == "$base_receipt_directory" ]]
[[ "$FIREMUD_SMOKE_AUTHORED_WORLD_SOURCE_RECEIPT_PATH" == /app/run-owned-authored-world-source/source-receipt.json ]]
[[ "$(stat -c '%a' "$(dirname "$base_capability")")" == 700 ]]
[[ "$(stat -c '%u %a %F' "$base_capability")" == "$(id -u) 444 regular file" ]]
[[ "$(stat -c '%u %a %F' "$base_receipt_directory")" == "$(id -u) 777 directory" ]]
[[ ! -e "$base_receipt_directory/source-receipt.json" ]]
base_operation_id="$(load_operation_id "$base_capability")"
ensure_run_owned_initial_admission_capability "$base_certificate_root" reuse-only
ensure_run_owned_initial_admission_capability "$base_certificate_root" create-or-verify
[[ "$(load_operation_id "$base_capability")" == "$base_operation_id" ]]

receipt_directory_backup="$TEST_ROOT/receipt-dir-before-missing-restart"
mv -- "$base_receipt_directory" "$receipt_directory_backup"
expect_failure missing_restart_receipt_directory reject_missing_restart_receipt_directory
[[ ! -e "$base_receipt_directory" ]]
mv -- "$receipt_directory_backup" "$base_receipt_directory"
ensure_run_owned_initial_admission_capability "$base_certificate_root" reuse-only
[[ "$(load_operation_id "$base_capability")" == "$base_operation_id" ]]

expect_failure wrong_claim reject_wrong_claim
expect_failure missing_claim_digest reject_claim_digest_variant missing
expect_failure substituted_claim_digest reject_claim_digest_variant substituted
expect_failure wrong_run reject_wrong_run
expect_failure wrong_project reject_wrong_project
expect_failure symlinked_capability reject_symlinked_capability
[[ "$(load_operation_id "$base_capability")" == "$base_operation_id" ]]

world_management_certificate_backup="$TEST_ROOT/world-management-original.crt"
cp -p -- "$world_management_dir/client.crt" "$world_management_certificate_backup"
mv -- "$world_management_dir/client.crt" "$TEST_ROOT/world-management-missing.crt"
expect_failure missing_world_management_certificate ensure_run_owned_initial_admission_capability \
  "$base_certificate_root" create-or-verify
[[ ! -e "$world_management_dir/client.crt" ]]
mv -- "$TEST_ROOT/world-management-missing.crt" "$world_management_dir/client.crt"
ensure_run_owned_initial_admission_capability "$base_certificate_root" reuse-only

mv -- "$world_management_dir/client.crt" "$TEST_ROOT/world-management-non-symlink.crt"
ln -s -- "$TEST_ROOT/world-management-non-symlink.crt" "$world_management_dir/client.crt"
expect_failure symlinked_world_management_certificate ensure_run_owned_initial_admission_capability \
  "$base_certificate_root" create-or-verify
rm -- "$world_management_dir/client.crt"
mv -- "$TEST_ROOT/world-management-non-symlink.crt" "$world_management_dir/client.crt"
ensure_run_owned_initial_admission_capability "$base_certificate_root" reuse-only

capability_backup="$TEST_ROOT/capability-before-world-pin-change.json"
cp -p -- "$base_capability" "$capability_backup"
python3 - "$TEST_ROOT/changed-world-pin.json" "$base_capability" <<'PY'
import json
import pathlib
import sys

value = json.loads(pathlib.Path(sys.argv[2]).read_text(encoding="utf-8"))
value["worldManagementLeafSha256"] = "f" * 64
pathlib.Path(sys.argv[1]).write_text(json.dumps(value, indent=2) + "\n", encoding="utf-8")
PY
chmod 444 "$TEST_ROOT/changed-world-pin.json"
mv -- "$TEST_ROOT/changed-world-pin.json" "$base_capability"
expect_failure changed_world_capability_pin reject_replaced_capability
mv -- "$capability_backup" "$base_capability"
[[ "$(load_operation_id "$base_capability")" == "$base_operation_id" ]]

capability_backup="$TEST_ROOT/capability-original.json"
cp -p -- "$base_capability" "$capability_backup"
python3 - "$TEST_ROOT/replaced-capability.json" "$base_capability" <<'PY'
import json
import pathlib
import sys
import uuid

source = pathlib.Path(sys.argv[2])
value = json.loads(source.read_text(encoding="utf-8"))
replacement = uuid.UUID(value["operationId"]).int ^ 1
value["operationId"] = str(uuid.UUID(int=replacement))
pathlib.Path(sys.argv[1]).write_text(json.dumps(value, indent=2) + "\n", encoding="utf-8")
PY
chmod 444 "$TEST_ROOT/replaced-capability.json"
mv -- "$TEST_ROOT/replaced-capability.json" "$base_capability"
expect_failure replaced_capability reject_replaced_capability
mv -- "$capability_backup" "$base_capability"
[[ "$(load_operation_id "$base_capability")" == "$base_operation_id" ]]

capability_backup="$TEST_ROOT/capability-before-missing-restart.json"
mv -- "$base_capability" "$capability_backup"
expect_failure missing_restart_capability reject_missing_restart_capability
[[ ! -e "$base_capability" ]]
mv -- "$capability_backup" "$base_capability"

_firemud_smoke_unlock
prepare_local_claim "cap-contract-cert-source"
source_certificate_root="$FIREMUD_SMOKE_OWNERSHIP_DIR_RESOLVED/$FIREMUD_SMOKE_PROJECT_KEY.grpc-mtls"
generate_local_certificates "$source_certificate_root"
_firemud_smoke_unlock
export FIREMUD_SMOKE_RUN_ID="$base_run_id"
export COMPOSE_PROJECT_NAME="$base_project"
export FIREMUD_SMOKE_OWNERSHIP_TOKEN="$base_token"
claim_run_owned_compose_project
chmod 600 "$world_management_dir/client.crt" "$world_management_dir/ca.crt"
cp -- "$source_certificate_root/workloads/world-management-service/client.crt" \
  "$world_management_dir/client.crt"
cp -- "$source_certificate_root/workloads/world-management-service/ca.crt" \
  "$world_management_dir/ca.crt"
chmod 444 "$world_management_dir/client.crt" "$world_management_dir/ca.crt"
expect_failure changed_world_management_certificate ensure_run_owned_initial_admission_capability \
  "$base_certificate_root" create-or-verify
chmod 600 "$world_management_dir/client.crt" "$world_management_dir/ca.crt"
cp -- "$TEST_ROOT/world-management-original.crt" "$world_management_dir/client.crt"
cp -- "$base_certificate_root/workloads/game-session-service/ca.crt" "$world_management_dir/ca.crt"
chmod 444 "$world_management_dir/client.crt" "$world_management_dir/ca.crt"
ensure_run_owned_initial_admission_capability "$base_certificate_root" reuse-only

chmod 600 "$world_management_dir/ca.crt"
cp -- "$source_certificate_root/workloads/world-management-service/ca.crt" \
  "$world_management_dir/ca.crt"
chmod 444 "$world_management_dir/ca.crt"
expect_failure changed_world_management_ca ensure_run_owned_initial_admission_capability \
  "$base_certificate_root" create-or-verify
chmod 600 "$world_management_dir/ca.crt"
cp -- "$base_certificate_root/workloads/game-session-service/ca.crt" \
  "$world_management_dir/ca.crt"
chmod 444 "$world_management_dir/ca.crt"
ensure_run_owned_initial_admission_capability "$base_certificate_root" reuse-only

wrong_identity_world_certificate="$TEST_ROOT/wrong-world-identity.crt"
cp -- "$base_certificate_root/workloads/game-design-service/client.crt" \
  "$wrong_identity_world_certificate"
chmod 600 "$world_management_dir/client.crt"
cp -- "$wrong_identity_world_certificate" "$world_management_dir/client.crt"
chmod 444 "$world_management_dir/client.crt"
expect_failure wrong_world_management_uri ensure_run_owned_initial_admission_capability \
  "$base_certificate_root" create-or-verify
chmod 600 "$world_management_dir/client.crt"
cp -- "$TEST_ROOT/world-management-original.crt" "$world_management_dir/client.crt"
chmod 444 "$world_management_dir/client.crt"

invalid_world_certificate="$TEST_ROOT/invalid-world-certificate.crt"
printf 'not-a-certificate\n' >"$invalid_world_certificate"
chmod 600 "$world_management_dir/client.crt"
cp -- "$invalid_world_certificate" "$world_management_dir/client.crt"
chmod 444 "$world_management_dir/client.crt"
expect_failure invalid_world_management_certificate ensure_run_owned_initial_admission_capability \
  "$base_certificate_root" create-or-verify
chmod 600 "$world_management_dir/client.crt"
cp -- "$TEST_ROOT/world-management-original.crt" "$world_management_dir/client.crt"
chmod 444 "$world_management_dir/client.crt"
ensure_run_owned_initial_admission_capability "$base_certificate_root" reuse-only

multiple_world_directory="$TEST_ROOT/multiple-world-uri"
mkdir -m 700 -- "$multiple_world_directory"
cp -- "$base_certificate_root/authority/ca.crt" "$multiple_world_directory/ca.crt"
cp -- "$base_certificate_root/authority/ca.key" "$multiple_world_directory/ca.key"
chmod 600 "$multiple_world_directory/ca.key"
openssl req -new -newkey rsa:2048 -nodes -sha256 \
  -keyout "$multiple_world_directory/client.key" \
  -out "$multiple_world_directory/client.csr" \
  -subj "/CN=world-management-service" >/dev/null 2>&1
cat >"$multiple_world_directory/extensions.cnf" <<'EOF'
basicConstraints=critical,CA:FALSE
keyUsage=critical,digitalSignature,keyEncipherment
extendedKeyUsage=clientAuth
subjectAltName=URI:spiffe://firemud/ns/dev/sa/world-management-service,URI:spiffe://firemud/ns/dev/sa/other
EOF
openssl x509 -req -in "$multiple_world_directory/client.csr" \
  -CA "$multiple_world_directory/ca.crt" -CAkey "$multiple_world_directory/ca.key" \
  -CAcreateserial -out "$multiple_world_directory/client.crt" -days 2 -sha256 \
  -extfile "$multiple_world_directory/extensions.cnf" >/dev/null 2>&1
chmod 444 "$multiple_world_directory/client.crt"
chmod 600 "$world_management_dir/client.crt"
cp -- "$multiple_world_directory/client.crt" "$world_management_dir/client.crt"
chmod 444 "$world_management_dir/client.crt"
expect_failure multiple_world_management_uris ensure_run_owned_initial_admission_capability \
  "$base_certificate_root" create-or-verify
chmod 600 "$world_management_dir/client.crt"
cp -- "$world_management_certificate_backup" "$world_management_dir/client.crt"
chmod 444 "$world_management_dir/client.crt"
ensure_run_owned_initial_admission_capability "$base_certificate_root" reuse-only

changed_world_directory="$TEST_ROOT/changed-world-same-ca"
mkdir -m 700 -- "$changed_world_directory"
cp -- "$base_certificate_root/authority/ca.crt" "$changed_world_directory/ca.crt"
cp -- "$base_certificate_root/authority/ca.key" "$changed_world_directory/ca.key"
chmod 600 "$changed_world_directory/ca.key"
openssl req -new -newkey rsa:2048 -nodes -sha256 \
  -keyout "$changed_world_directory/client.key" \
  -out "$changed_world_directory/client.csr" \
  -subj "/CN=world-management-service" >/dev/null 2>&1
cat >"$changed_world_directory/extensions.cnf" <<'EOF'
basicConstraints=critical,CA:FALSE
keyUsage=critical,digitalSignature,keyEncipherment
extendedKeyUsage=clientAuth
subjectAltName=URI:spiffe://firemud/ns/dev/sa/world-management-service,DNS:world-management-service
EOF
openssl x509 -req -in "$changed_world_directory/client.csr" \
  -CA "$changed_world_directory/ca.crt" -CAkey "$changed_world_directory/ca.key" \
  -CAcreateserial -out "$changed_world_directory/client.crt" -days 2 -sha256 \
  -extfile "$changed_world_directory/extensions.cnf" >/dev/null 2>&1
chmod 444 "$changed_world_directory/client.crt"
chmod 600 "$world_management_dir/client.crt"
cp -- "$changed_world_directory/client.crt" "$world_management_dir/client.crt"
chmod 444 "$world_management_dir/client.crt"
expect_failure changed_world_management_leaf ensure_run_owned_initial_admission_capability \
  "$base_certificate_root" create-or-verify
chmod 600 "$world_management_dir/client.crt"
cp -- "$world_management_certificate_backup" "$world_management_dir/client.crt"
chmod 444 "$world_management_dir/client.crt"
ensure_run_owned_initial_admission_capability "$base_certificate_root" reuse-only

cp -p -- "$base_certificate_root/workloads/game-session-service/client.crt" "$TEST_ROOT/original-client.crt"
cp -p -- "$base_certificate_root/workloads/game-session-service/ca.crt" "$TEST_ROOT/original-ca.crt"
chmod 600 "$base_certificate_root/workloads/game-session-service/client.crt" \
  "$base_certificate_root/workloads/game-session-service/ca.crt"
cp -- "$source_certificate_root/workloads/game-session-service/client.crt" \
  "$base_certificate_root/workloads/game-session-service/client.crt"
cp -- "$source_certificate_root/workloads/game-session-service/ca.crt" \
  "$base_certificate_root/workloads/game-session-service/ca.crt"
chmod 444 "$base_certificate_root/workloads/game-session-service/client.crt" \
  "$base_certificate_root/workloads/game-session-service/ca.crt"
expect_failure changed_certificate ensure_run_owned_initial_admission_capability \
  "$base_certificate_root" create-or-verify
chmod 600 "$base_certificate_root/workloads/game-session-service/client.crt" \
  "$base_certificate_root/workloads/game-session-service/ca.crt"
cp -- "$TEST_ROOT/original-client.crt" "$base_certificate_root/workloads/game-session-service/client.crt"
cp -- "$TEST_ROOT/original-ca.crt" "$base_certificate_root/workloads/game-session-service/ca.crt"
chmod 444 "$base_certificate_root/workloads/game-session-service/client.crt" \
  "$base_certificate_root/workloads/game-session-service/ca.crt"
ensure_run_owned_initial_admission_capability "$base_certificate_root" reuse-only
[[ "$(load_operation_id "$base_capability")" == "$base_operation_id" ]]

_firemud_smoke_unlock
export GITHUB_ACTIONS=true
export GITHUB_RUN_ID=801
export GITHUB_RUN_ATTEMPT=3
export GITHUB_JOB=smoke
export COMPOSE_PROJECT_NAME=smoke-full-801-3
FIREMUD_SMOKE_OWNERSHIP_TOKEN="$(openssl rand -hex 32)"
export FIREMUD_SMOKE_OWNERSHIP_TOKEN
claim_run_owned_compose_project
expected_github_run_id="$(python3 - <<'PY'
import hashlib
print("gha-" + hashlib.sha256(b"github:801:3:smoke").hexdigest())
PY
)"
_firemud_smoke_initial_admission_context
[[ "$FIREMUD_SMOKE_RUN_ID" == "$expected_github_run_id" ]]
[[ "$FIREMUD_SMOKE_COMPOSE_PROJECT_NAME" == smoke-full-801-3 ]]
github_certificate_root="$FIREMUD_SMOKE_OWNERSHIP_DIR_RESOLVED/$FIREMUD_SMOKE_PROJECT_KEY.grpc-mtls"
_firemud_smoke_unlock
FIREMUD_COMPOSE_GRPC_MTLS_CERT_ROOT="$github_certificate_root" \
  bash "$ROOT_DIR/dev-tools/certs/ensure-dev-certs.sh" --compose-mtls "$github_certificate_root"
claim_run_owned_compose_project
ensure_run_owned_initial_admission_capability "$github_certificate_root" create-or-verify
github_capability="$FIREMUD_SMOKE_INITIAL_ADMISSION_CAPABILITY_HOST_PATH"
github_operation_id="$(load_operation_id "$github_capability")"
python3 - "$github_capability" "$expected_github_run_id" <<'PY'
import json
import pathlib
import sys

value = json.loads(pathlib.Path(sys.argv[1]).read_text(encoding="utf-8"))
assert value["runId"] == sys.argv[2]
assert value["composeProjectName"] == "smoke-full-801-3"
PY
ensure_run_owned_initial_admission_capability "$github_certificate_root" reuse-only
expect_failure wrong_github_job reject_wrong_github_job
[[ "$(load_operation_id "$github_capability")" == "$github_operation_id" ]]

_firemud_smoke_unlock
export GITHUB_RUN_ATTEMPT=4
export COMPOSE_PROJECT_NAME=smoke-full-801-4
claim_run_owned_compose_project
_firemud_smoke_initial_admission_context
expected_attempt_run_id="$(python3 - <<'PY'
import hashlib
print("gha-" + hashlib.sha256(b"github:801:4:smoke").hexdigest())
PY
)"
[[ "$FIREMUD_SMOKE_RUN_ID" == "$expected_attempt_run_id" ]]
[[ "$FIREMUD_SMOKE_COMPOSE_PROJECT_NAME" == smoke-full-801-4 ]]
attempt_run_id="$FIREMUD_SMOKE_RUN_ID"

_firemud_smoke_unlock
export GITHUB_RUN_ID=802
export COMPOSE_PROJECT_NAME=smoke-full-802-4
claim_run_owned_compose_project
_firemud_smoke_initial_admission_context
expected_other_run_id="$(python3 - <<'PY'
import hashlib
print("gha-" + hashlib.sha256(b"github:802:4:smoke").hexdigest())
PY
)"
[[ "$FIREMUD_SMOKE_RUN_ID" == "$expected_other_run_id" ]]
[[ "$FIREMUD_SMOKE_RUN_ID" != "$attempt_run_id" ]]
[[ "$FIREMUD_SMOKE_COMPOSE_PROJECT_NAME" == smoke-full-802-4 ]]

for wrapper in verify-fresh-bootstrap.sh verify-smoke-images.sh verify-restart-state.sh; do
  wrapper_path="$ROOT_DIR/dev-tools/$wrapper"
  disabled_line="$(rg -n '^export FIREMUD_SMOKE_INITIAL_ADMISSION_FIXTURE_ENABLED=false$' "$wrapper_path" | cut -d: -f1)"
  config_line="$(rg -n 'config(--format json| --services| >/dev/null)' "$wrapper_path" | head -n 1 | cut -d: -f1 || true)"
  capability_line="$(rg -n 'ensure_run_owned_initial_admission_capability' "$wrapper_path" | cut -d: -f1)"
  [[ -n "$disabled_line" && -n "$capability_line" ]]
  if [[ -n "$config_line" ]]; then
    ((disabled_line < config_line))
    if [[ "$wrapper" == verify-smoke-images.sh ]]; then
      ((capability_line < config_line))
    elif [[ "$wrapper" != verify-restart-state.sh ]]; then
      ((config_line < capability_line))
    fi
  fi
done

echo "Run-owned initial-admission capability contract passed."
