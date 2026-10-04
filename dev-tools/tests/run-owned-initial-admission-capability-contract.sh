#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
TEST_ROOT="$(mktemp -d "${TMPDIR:-/tmp}/firemud-run-owned-admission-contract.XXXXXX")"
trap 'rm -rf -- "$TEST_ROOT"' EXIT
chmod 700 "$TEST_ROOT"
# This owner value proves only the synthetic run-owned fixture shape; it does not
# claim Account authentication or derive provenance from a retained numeric ID.
SYNTHETIC_FIXTURE_OWNER_ACCOUNT_UUID="123e4567-e89b-12d3-a456-426614174000"

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
  python3 - "$1" "$SYNTHETIC_FIXTURE_OWNER_ACCOUNT_UUID" <<'PY'
import json
import pathlib
import sys

value = json.loads(pathlib.Path(sys.argv[1]).read_text(encoding="utf-8"))
expected_fields = {
    "schema", "runId", "composeProjectName", "operationId", "tenantId",
    "gameTemplateId", "ownerAccountId", "worldSlug", "worldDisplayName",
    "realmSlug", "realmDisplayName", "visible", "publicProductionRealm",
    "requiresCharacterSelection", "stateScope", "characterCreationPolicy",
    "gameSessionLeafSha256", "gameSessionUriSan", "caCertificateSha256",
}
assert set(value) == expected_fields, set(value)
assert value["schema"] == "firemud.run-owned-initial-admission-fixture.v1"
assert value["tenantId"] == 1
assert value["gameTemplateId"] == 1
assert type(value["ownerAccountId"]) is str
assert value["ownerAccountId"] == sys.argv[2]
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

reject_missing_restart_capability() {
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
[[ "$(stat -c '%a' "$(dirname "$base_capability")")" == 700 ]]
[[ "$(stat -c '%u %a %F' "$base_capability")" == "$(id -u) 444 regular file" ]]
base_operation_id="$(load_operation_id "$base_capability")"
ensure_run_owned_initial_admission_capability "$base_certificate_root" reuse-only
ensure_run_owned_initial_admission_capability "$base_certificate_root" create-or-verify
[[ "$(load_operation_id "$base_capability")" == "$base_operation_id" ]]

expect_failure wrong_claim reject_wrong_claim
expect_failure wrong_run reject_wrong_run
expect_failure wrong_project reject_wrong_project
expect_failure symlinked_capability reject_symlinked_capability
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

capability_backup="$TEST_ROOT/capability-before-numeric-owner.json"
cp -p -- "$base_capability" "$capability_backup"
python3 - "$TEST_ROOT/numeric-owner-capability.json" "$base_capability" <<'PY'
import json
import pathlib
import sys

source = pathlib.Path(sys.argv[2])
value = json.loads(source.read_text(encoding="utf-8"))
value["ownerAccountId"] = 1
pathlib.Path(sys.argv[1]).write_text(json.dumps(value, indent=2) + "\n", encoding="utf-8")
PY
chmod 444 "$TEST_ROOT/numeric-owner-capability.json"
mv -- "$TEST_ROOT/numeric-owner-capability.json" "$base_capability"
expect_failure numeric_owner_account_id reject_replaced_capability
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
    if [[ "$wrapper" != verify-restart-state.sh ]]; then
      ((config_line < capability_line))
    fi
  fi
done

echo "Run-owned initial-admission capability contract passed."
