#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
TEST_ROOT="$(mktemp -d "${TMPDIR:-/tmp}/firemud-compose-grpc-mtls-contract.XXXXXX")"
trap 'rm -rf -- "$TEST_ROOT"' EXIT
chmod 700 "$TEST_ROOT"

ownership_dir="$TEST_ROOT/ownership"
mkdir -m 700 -- "$ownership_dir"
run_id="mtls-contract-${BASHPID}"
project_name="firemud-smoke-$run_id"
project_key="$(printf '%s' "$project_name" | sha256sum | awk '{print $1}')"
fixture_root="$ownership_dir/$project_key.grpc-mtls"

ensure_compose_mtls_fixture() {
  FIREMUD_SMOKE_TEST_MODE=1 \
  FIREMUD_SMOKE_OWNERSHIP_DIR="$ownership_dir" \
  FIREMUD_SMOKE_RUN_ID="$run_id" \
  COMPOSE_PROJECT_NAME="$project_name" \
    bash "$ROOT_DIR/dev-tools/certs/ensure-dev-certs.sh" --compose-mtls "$fixture_root"
}

ensure_compose_mtls_fixture
ensure_compose_mtls_fixture

services=(
  account-service gateway automation-scripting-service entity-management-service
  game-design-service game-logic-service game-session-service logging-admin-service
  social-groups-service tcp-proxy-service world-management-service
)
workloads_dir="$fixture_root/workloads"
[[ "$(stat -c '%a' "$fixture_root")" == 700 ]]
[[ "$(stat -c '%a' "$fixture_root/authority")" == 700 ]]
[[ "$(stat -c '%a' "$fixture_root/authority/ca.key")" == 600 ]]
[[ ! -e "$workloads_dir/ca.key" ]]
[[ -z "$(find "$workloads_dir" -type f -name ca.key -print -quit)" ]]

symlink_run_id="${run_id}-symlink"
symlink_project_name="firemud-smoke-$symlink_run_id"
symlink_project_key="$(printf '%s' "$symlink_project_name" | sha256sum | awk '{print $1}')"
symlink_fixture_root="$ownership_dir/$symlink_project_key.grpc-mtls"
outside_workloads="$TEST_ROOT/outside-workloads"
mkdir -m 700 -- "$symlink_fixture_root"
mkdir -m 755 -- "$outside_workloads"
ln -s "$outside_workloads" "$symlink_fixture_root/workloads"
if FIREMUD_SMOKE_TEST_MODE=1 \
  FIREMUD_SMOKE_OWNERSHIP_DIR="$ownership_dir" \
  FIREMUD_SMOKE_RUN_ID="$symlink_run_id" \
  COMPOSE_PROJECT_NAME="$symlink_project_name" \
  bash "$ROOT_DIR/dev-tools/certs/ensure-dev-certs.sh" --compose-mtls "$symlink_fixture_root" \
  >"$TEST_ROOT/symlink-output" 2>&1; then
  echo "Compose mTLS certificate generation accepted a symlinked workloads path." >&2
  exit 1
fi
rg -Fq 'Compose mTLS workloads path must be a real directory.' "$TEST_ROOT/symlink-output"

public_key_digest() {
  local cert_or_key="$1" kind="$2"
  if [[ "$kind" == certificate ]]; then
    openssl x509 -in "$cert_or_key" -pubkey -noout
  else
    openssl pkey -in "$cert_or_key" -pubout
  fi | openssl pkey -pubin -outform DER 2>/dev/null | openssl dgst -sha256 | awk '{print $NF}'
}

file_digest() {
  sha256sum "$1" | awk '{print $1}'
}

# Recreate the retained fixture's exact legacy shape: only the WMS leaf is the
# generic authority/client pair. The owner claim and all other fixture material
# remain in place while ensure-dev-certs performs the narrow migration.
declare -A authority_digests=()
for file in ca.crt ca.key client.crt client.key; do
  authority_digests["$file"]="$(file_digest "$fixture_root/authority/$file")"
done
declare -A workload_ca_digests=() other_leaf_digests=()
for service in "${services[@]}"; do
  service_dir="$workloads_dir/$service"
  workload_ca_digests["$service"]="$(file_digest "$service_dir/ca.crt")"
  if [[ "$service" != world-management-service ]]; then
    other_leaf_digests["$service.crt"]="$(file_digest "$service_dir/client.crt")"
    other_leaf_digests["$service.key"]="$(file_digest "$service_dir/client.key")"
  fi
done
wms_dir="$workloads_dir/world-management-service"
chmod 644 "$wms_dir/client.crt" "$wms_dir/client.key"
cp -- "$fixture_root/authority/client.crt" "$wms_dir/client.crt"
cp -- "$fixture_root/authority/client.key" "$wms_dir/client.key"
chmod 444 "$wms_dir/client.crt" "$wms_dir/client.key"
legacy_wms_fingerprint="$(openssl x509 -in "$wms_dir/client.crt" -noout -fingerprint -sha256)"

ensure_compose_mtls_fixture
canonical_wms_san="$(openssl x509 -in "$wms_dir/client.crt" -noout -ext subjectAltName)"
[[ "$canonical_wms_san" == *"URI:spiffe://firemud/ns/dev/sa/world-management-service"* ]]
[[ "$(openssl x509 -in "$wms_dir/client.crt" -noout -fingerprint -sha256)" != "$legacy_wms_fingerprint" ]]
[[ "$(public_key_digest "$wms_dir/client.crt" certificate)" == "$(public_key_digest "$wms_dir/client.key" key)" ]]
for file in ca.crt ca.key client.crt client.key; do
  [[ "$(file_digest "$fixture_root/authority/$file")" == "${authority_digests[$file]}" ]]
done
for service in "${services[@]}"; do
  service_dir="$workloads_dir/$service"
  [[ "$(file_digest "$service_dir/ca.crt")" == "${workload_ca_digests[$service]}" ]]
  if [[ "$service" != world-management-service ]]; then
    [[ "$(file_digest "$service_dir/client.crt")" == "${other_leaf_digests[$service.crt]}" ]]
    [[ "$(file_digest "$service_dir/client.key")" == "${other_leaf_digests[$service.key]}" ]]
  fi
done
migrated_wms_certificate_digest="$(file_digest "$wms_dir/client.crt")"
migrated_wms_key_digest="$(file_digest "$wms_dir/client.key")"
ensure_compose_mtls_fixture
[[ "$(file_digest "$wms_dir/client.crt")" == "$migrated_wms_certificate_digest" ]]
[[ "$(file_digest "$wms_dir/client.key")" == "$migrated_wms_key_digest" ]]

canonical_wms_dir="$TEST_ROOT/canonical-wms"
mkdir -m 700 -- "$canonical_wms_dir"
cp -- "$wms_dir/client.crt" "$canonical_wms_dir/client.crt"
cp -- "$wms_dir/client.key" "$canonical_wms_dir/client.key"

# A canonical-SAN certificate paired with the legacy generic key is neither a
# valid retry nor the recognized legacy copy, so validation must fail without
# repairing either file.
chmod 644 "$wms_dir/client.key"
cp -- "$fixture_root/authority/client.key" "$wms_dir/client.key"
chmod 444 "$wms_dir/client.key"
stale_key_wms_certificate_digest="$(file_digest "$wms_dir/client.crt")"
stale_key_wms_key_digest="$(file_digest "$wms_dir/client.key")"
if ensure_compose_mtls_fixture >"$TEST_ROOT/stale-key-output" 2>&1; then
  echo "Compose mTLS certificate validation accepted a mismatched canonical WMS key." >&2
  exit 1
fi
rg -Fq 'Compose mTLS workload certificate and private key do not match: world-management-service' "$TEST_ROOT/stale-key-output"
[[ "$(file_digest "$wms_dir/client.crt")" == "$stale_key_wms_certificate_digest" ]]
[[ "$(file_digest "$wms_dir/client.key")" == "$stale_key_wms_key_digest" ]]
chmod 644 "$wms_dir/client.crt" "$wms_dir/client.key"
cp -- "$canonical_wms_dir/client.crt" "$wms_dir/client.crt"
cp -- "$canonical_wms_dir/client.key" "$wms_dir/client.key"
chmod 444 "$wms_dir/client.crt" "$wms_dir/client.key"
[[ "$(file_digest "$wms_dir/client.crt")" == "$migrated_wms_certificate_digest" ]]
[[ "$(file_digest "$wms_dir/client.key")" == "$migrated_wms_key_digest" ]]

unknown_wms_dir="$TEST_ROOT/unknown-wms"
mkdir -m 700 -- "$unknown_wms_dir"
"$ROOT_DIR/dev-tools/certs/generate-dev-certs.sh" --workload \
  "$fixture_root/authority/ca.crt" "$fixture_root/authority/ca.key" \
  "$unknown_wms_dir/client.crt" "$unknown_wms_dir/client.key" test world-management-service
chmod 444 "$unknown_wms_dir/client.crt" "$unknown_wms_dir/client.key"
openssl verify -CAfile "$fixture_root/authority/ca.crt" "$unknown_wms_dir/client.crt" >/dev/null
chmod 644 "$wms_dir/client.crt" "$wms_dir/client.key"
cp -- "$unknown_wms_dir/client.crt" "$wms_dir/client.crt"
cp -- "$unknown_wms_dir/client.key" "$wms_dir/client.key"
chmod 444 "$wms_dir/client.crt" "$wms_dir/client.key"
unknown_wms_certificate_digest="$(file_digest "$wms_dir/client.crt")"
unknown_wms_key_digest="$(file_digest "$wms_dir/client.key")"
if ensure_compose_mtls_fixture >"$TEST_ROOT/unknown-identity-output" 2>&1; then
  echo "Compose mTLS certificate validation accepted an unknown WMS identity." >&2
  exit 1
fi
rg -Fq 'Compose mTLS workload has the wrong SPIFFE identity: world-management-service' "$TEST_ROOT/unknown-identity-output"
[[ "$(file_digest "$wms_dir/client.crt")" == "$unknown_wms_certificate_digest" ]]
[[ "$(file_digest "$wms_dir/client.key")" == "$unknown_wms_key_digest" ]]
chmod 644 "$wms_dir/client.crt" "$wms_dir/client.key"
cp -- "$canonical_wms_dir/client.crt" "$wms_dir/client.crt"
cp -- "$canonical_wms_dir/client.key" "$wms_dir/client.key"
chmod 444 "$wms_dir/client.crt" "$wms_dir/client.key"
[[ "$(file_digest "$wms_dir/client.crt")" == "$migrated_wms_certificate_digest" ]]
[[ "$(file_digest "$wms_dir/client.key")" == "$migrated_wms_key_digest" ]]

for service in "${services[@]}"; do
  echo "Checking Compose mTLS leaf: $service"
  service_dir="$workloads_dir/$service"
  [[ "$(stat -c '%a' "$service_dir")" == 755 ]]
  [[ "$(find "$service_dir" -mindepth 1 -maxdepth 1 -type f | wc -l)" -eq 3 ]]
  for file in ca.crt client.crt client.key; do
    [[ -f "$service_dir/$file" && ! -L "$service_dir/$file" ]]
    [[ "$(stat -c '%a' "$service_dir/$file")" == 444 ]]
  done
  openssl verify -CAfile "$service_dir/ca.crt" "$service_dir/client.crt" >/dev/null
  cert_public_key="$(public_key_digest "$service_dir/client.crt" certificate)"
  leaf_public_key="$(public_key_digest "$service_dir/client.key" key)"
  [[ "$cert_public_key" == "$leaf_public_key" ]]
done

identity_services=(
  account-service game-session-service entity-management-service world-management-service
)
declare -A identity_fingerprints=()
for service in "${identity_services[@]}"; do
  echo "Checking exact workload identity: $service"
  san_output="$(openssl x509 -in "$workloads_dir/$service/client.crt" -noout -ext subjectAltName)"
  [[ "$san_output" == *"URI:spiffe://firemud/ns/dev/sa/$service"* ]]
  identity_fingerprints["$service"]="$(openssl x509 -in "$workloads_dir/$service/client.crt" -noout -fingerprint -sha256)"
done
for ((left = 0; left < ${#identity_services[@]}; left++)); do
  for ((right = left + 1; right < ${#identity_services[@]}; right++)); do
    left_service="${identity_services[$left]}"
    right_service="${identity_services[$right]}"
    [[ "${identity_fingerprints[$left_service]}" != "${identity_fingerprints[$right_service]}" ]]
  done
done
echo "Checking canonical Compose entrypoints."
mtls_compose="$ROOT_DIR/docker/docker-compose.grpc-mtls.override.yml"
rg -Fq 'FIREMUD_GRPC_PLAINTEXT: "false"' "$mtls_compose"
rg -Fq 'GRPC_SERVER_TLS_ENABLED: "true"' "$mtls_compose"
python3 - "$mtls_compose" <<'PY'
import pathlib
import re
import sys

lines = pathlib.Path(sys.argv[1]).read_text(encoding="utf-8").splitlines()
for service in (
    "entity-management-service",
    "world-management-service",
    "game-session-service",
    "game-design-service",
):
    headers = [index for index, line in enumerate(lines) if line == f"  {service}:"]
    assert len(headers) == 1, service
    start = headers[0]
    end = next(
        (
            index
            for index in range(start + 1, len(lines))
            if lines[index].startswith("  ") and not lines[index].startswith("    ")
        ),
        len(lines),
    )
    block = lines[start + 1 : end]
    environments = [index for index, line in enumerate(block) if line == "    environment:"]
    assert len(environments) == 1, service
    env_start = environments[0]
    env_end = next(
        (
            index
            for index in range(env_start + 1, len(block))
            if block[index].startswith("    ") and not block[index].startswith("      ")
        ),
        len(block),
    )
    environment = block[env_start + 1 : env_end]
    assert any(
        re.fullmatch(
            r"      FIREMUD_GRPC_WORKLOAD_NAMESPACE:\s*[\"']?dev[\"']?\s*(?:#.*)?",
            line,
        )
        for line in environment
    ), f"{service} must set FIREMUD_GRPC_WORKLOAD_NAMESPACE to dev in its mTLS overlay"
PY
python3 - "$mtls_compose" <<'PY'
import pathlib
import sys

lines = pathlib.Path(sys.argv[1]).read_text(encoding="utf-8").splitlines()
service_blocks = {}
for index, line in enumerate(lines):
    if not line.startswith("  ") or line.startswith("    ") or not line.endswith(":"):
        continue
    service = line[2:-1]
    end = next(
        (
            position
            for position in range(index + 1, len(lines))
            if lines[position].startswith("  ") and not lines[position].startswith("    ")
        ),
        len(lines),
    )
    service_blocks[service] = lines[index + 1 : end]

capability_path = "/app/run-owned-initial-admission-capability.json"
fixture_keys = (
    "FIREMUD_SMOKE_RUN_ID",
    "FIREMUD_SMOKE_COMPOSE_PROJECT_NAME",
    "FIREMUD_SMOKE_INITIAL_ADMISSION_FIXTURE_ENABLED",
    "FIREMUD_SMOKE_INITIAL_ADMISSION_CAPABILITY_PATH",
)
for service, block in service_blocks.items():
    if service == "game-session-service":
        for key in fixture_keys:
            assert any(line.startswith(f"      {key}:") for line in block), key
        assert any(
            line == f"      FIREMUD_SMOKE_INITIAL_ADMISSION_CAPABILITY_PATH: {capability_path}"
            for line in block
        )
        assert any(
            line == "        source: ${FIREMUD_SMOKE_INITIAL_ADMISSION_CAPABILITY_HOST_PATH:-/dev/null}"
            for line in block
        )
        assert any(line == f"        target: {capability_path}" for line in block)
        assert any(line == "        read_only: true" for line in block)
        assert any(line == "          create_host_path: false" for line in block)
    else:
        assert not any(any(key in line for key in fixture_keys) for line in block), service
        assert not any(capability_path in line for line in block), service
PY
rg -Fq 'client-auth: REQUIRE' "$ROOT_DIR/services/world-management-service/src/main/resources/application.yml"
world_guard="$ROOT_DIR/services/world-management-service/src/main/java/net/firedevops/firemud/worldmanagement/service/impl/InitialAdmissionBindWorkloadGuard.java"
world_grpc="$ROOT_DIR/services/world-management-service/src/main/java/net/firedevops/firemud/worldmanagement/service/impl/WorldManagementGrpcService.java"
rg -Fq 'world_management.v1.WorldManagementService/AcquireInitialAdmissionBindHold' "$world_guard"
rg -Fq 'peerIdentity.isService("game-session-service")' "$world_guard"
rg -Fq 'peerIdentity.isInNamespace(trustedNamespace)' "$world_guard"
rg -Fq 'firemud.grpc.workload-namespace' "$world_grpc"
for service in "${services[@]}"; do
  rg -Fq "\${FIREMUD_COMPOSE_GRPC_MTLS_CERT_ROOT:?canonical smoke must set a run-owned mTLS certificate root}/workloads/$service:/app/certs:ro" "$mtls_compose"
done

python3 - "$ROOT_DIR/dev-tools/verify-smoke-images.sh" <<'PY'
import pathlib
import sys

source = pathlib.Path(sys.argv[1]).read_text(encoding="utf-8")
profile = source.index('COMPOSE_FILES+=( -f "$DOCKER_DIR/docker-compose.grpc-mtls.override.yml" )')
for prior_overlay in (
    'docker-compose.smoke-images.override.yml',
    'docker-compose.pr-local-minio.override.yml',
):
    assert source.index(prior_overlay) < profile, prior_overlay
assert "COMPOSE_UP_ARGS=(up -d --remove-orphans)" in source
assert "--build" not in source
PY

for wrapper in verify-smoke-images.sh verify-fresh-bootstrap.sh verify-restart-state.sh; do
  wrapper_path="$ROOT_DIR/dev-tools/$wrapper"
  rg -Fq 'docker-compose.grpc-mtls.override.yml' "$wrapper_path"
  rg -q -- '--compose-mtls' "$wrapper_path"
done
rg -Fq 'docker-compose.pr-local-minio.override.yml' "$ROOT_DIR/dev-tools/verify-smoke-images.sh"
rg -Fq 'docker-compose.grpc-mtls.override.yml' "$ROOT_DIR/dev-tools/verify-smoke-images.sh"

if command -v docker >/dev/null 2>&1 && docker compose version >/dev/null 2>&1; then
  compose_fixture="$TEST_ROOT/compose"
  mkdir -p "$compose_fixture/docker"
  sed 's#\.\./\.env#./compose.env#g' "$ROOT_DIR/docker/docker-compose.yml" >"$compose_fixture/docker/docker-compose.yml"
  cp "$ROOT_DIR/docker/docker-compose.override.yml" "$compose_fixture/docker/docker-compose.override.yml"
  cp "$ROOT_DIR/docker/docker-compose.smoke-images.override.yml" "$compose_fixture/docker/docker-compose.smoke-images.override.yml"
  cp "$ROOT_DIR/docker/docker-compose.grpc-mtls.override.yml" "$compose_fixture/docker/docker-compose.grpc-mtls.override.yml"
  cp "$ROOT_DIR/.env.sample" "$compose_fixture/docker/compose.env"
  cp "$ROOT_DIR/.env.sample" "$compose_fixture/.env"

  rendered_config="$TEST_ROOT/compose-config.json"
  FIREMUD_COMPOSE_GRPC_MTLS_CERT_ROOT="$fixture_root" SMOKE_IMAGE_TAG=contract \
    FIREMUD_SMOKE_INITIAL_ADMISSION_CAPABILITY_HOST_PATH="$TEST_ROOT/config-probe-capability.json" \
    FIREMUD_SMOKE_INITIAL_ADMISSION_FIXTURE_ENABLED=false \
    FIREMUD_SMOKE_RUN_ID='' FIREMUD_SMOKE_COMPOSE_PROJECT_NAME='' \
    docker compose --env-file "$compose_fixture/.env" \
      -f "$compose_fixture/docker/docker-compose.yml" \
      -f "$compose_fixture/docker/docker-compose.override.yml" \
      -f "$compose_fixture/docker/docker-compose.smoke-images.override.yml" \
      -f "$compose_fixture/docker/docker-compose.grpc-mtls.override.yml" \
      config --format json >"$rendered_config"
  python3 - "$rendered_config" "$fixture_root" "$TEST_ROOT/config-probe-capability.json" <<'PY'
import json
import pathlib
import sys

config_path, cert_root, capability_source = sys.argv[1:]
config = json.loads(pathlib.Path(config_path).read_text(encoding="utf-8"))
services = config["services"]
app_names = {
    "account-service", "gateway", "automation-scripting-service",
    "entity-management-service", "game-design-service", "game-logic-service",
    "game-session-service", "logging-admin-service", "social-groups-service",
    "tcp-proxy-service", "world-management-service",
}
for name in app_names:
    service = services[name]
    environment = service.get("environment", {})
    assert environment.get("FIREMUD_GRPC_PLAINTEXT") == "false", name
    assert environment.get("GRPC_SERVER_TLS_ENABLED") == "true", name
    assert environment.get("FIREMUD_GRPC_CERT_CHAIN_PATH") == "/app/certs/client.crt", name
    assert environment.get("FIREMUD_GRPC_PRIVATE_KEY_PATH") == "/app/certs/client.key", name
    assert environment.get("FIREMUD_GRPC_CA_CERT_PATH") == "/app/certs/ca.crt", name
    mounts = [mount for mount in service.get("volumes", []) if mount.get("target") == "/app/certs"]
    assert len(mounts) == 1, name
    assert mounts[0]["source"] == f"{cert_root}/workloads/{name}", (name, mounts[0])
    assert mounts[0].get("read_only") is True, name
    assert service.get("build") is None, f"image-only proof must not build {name}"
entity_namespace = services["entity-management-service"]["environment"].get("FIREMUD_GRPC_WORKLOAD_NAMESPACE")
assert entity_namespace == "dev", entity_namespace
wms_namespace = services["world-management-service"]["environment"].get("FIREMUD_GRPC_WORKLOAD_NAMESPACE")
assert wms_namespace == "dev", wms_namespace
game_session = services["game-session-service"]
game_session_environment = game_session["environment"]
assert game_session_environment.get("FIREMUD_SMOKE_RUN_ID") == ""
assert game_session_environment.get("FIREMUD_SMOKE_COMPOSE_PROJECT_NAME") == ""
assert game_session_environment.get("FIREMUD_SMOKE_INITIAL_ADMISSION_FIXTURE_ENABLED") == "false"
capability_path = "/app/run-owned-initial-admission-capability.json"
assert game_session_environment.get("FIREMUD_SMOKE_INITIAL_ADMISSION_CAPABILITY_PATH") == capability_path
capability_mounts = [mount for mount in game_session.get("volumes", []) if mount.get("target") == capability_path]
assert len(capability_mounts) == 1, capability_mounts
assert capability_mounts[0]["source"] == capability_source, capability_mounts[0]
assert capability_mounts[0].get("read_only") is True
for name, service in services.items():
    if name != "game-session-service":
        assert not any(mount.get("target") == capability_path for mount in service.get("volumes", [])), name
assert services["account-service"]["image"].endswith(":contract")
print("Verified Compose mTLS wiring and image-only service configuration.")
PY
else
  echo "Docker Compose unavailable; skipped rendered-configuration assertion."
fi

echo "Compose gRPC mTLS certificate and wiring contract passed."
