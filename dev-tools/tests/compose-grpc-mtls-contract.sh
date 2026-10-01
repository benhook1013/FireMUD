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

FIREMUD_SMOKE_TEST_MODE=1 \
FIREMUD_SMOKE_OWNERSHIP_DIR="$ownership_dir" \
FIREMUD_SMOKE_RUN_ID="$run_id" \
COMPOSE_PROJECT_NAME="$project_name" \
  bash "$ROOT_DIR/dev-tools/certs/ensure-dev-certs.sh" --compose-mtls "$fixture_root"
FIREMUD_SMOKE_TEST_MODE=1 \
FIREMUD_SMOKE_OWNERSHIP_DIR="$ownership_dir" \
FIREMUD_SMOKE_RUN_ID="$run_id" \
COMPOSE_PROJECT_NAME="$project_name" \
  bash "$ROOT_DIR/dev-tools/certs/ensure-dev-certs.sh" --compose-mtls "$fixture_root"

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

public_key_digest() {
  local cert_or_key="$1" kind="$2"
  if [[ "$kind" == certificate ]]; then
    openssl x509 -in "$cert_or_key" -pubkey -noout
  else
    openssl pkey -in "$cert_or_key" -pubout
  fi | openssl pkey -pubin -outform DER 2>/dev/null | openssl dgst -sha256 | awk '{print $NF}'
}

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

declare -A identity_fingerprints=()
for service in account-service game-session-service entity-management-service; do
  echo "Checking exact workload identity: $service"
  san_output="$(openssl x509 -in "$workloads_dir/$service/client.crt" -noout -ext subjectAltName)"
  [[ "$san_output" == *"URI:spiffe://firemud/ns/dev/sa/$service"* ]]
  identity_fingerprints["$service"]="$(openssl x509 -in "$workloads_dir/$service/client.crt" -noout -fingerprint -sha256)"
done
[[ "${identity_fingerprints[account-service]}" != "${identity_fingerprints[game-session-service]}" ]]
[[ "${identity_fingerprints[account-service]}" != "${identity_fingerprints[entity-management-service]}" ]]
[[ "${identity_fingerprints[game-session-service]}" != "${identity_fingerprints[entity-management-service]}" ]]
echo "Checking canonical Compose entrypoints."
mtls_compose="$ROOT_DIR/docker/docker-compose.grpc-mtls.override.yml"
rg -Fq 'FIREMUD_GRPC_PLAINTEXT: "false"' "$mtls_compose"
rg -Fq 'GRPC_SERVER_TLS_ENABLED: "true"' "$mtls_compose"
rg -Fq 'FIREMUD_GRPC_WORKLOAD_NAMESPACE: dev' "$mtls_compose"
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
    docker compose --env-file "$compose_fixture/.env" \
      -f "$compose_fixture/docker/docker-compose.yml" \
      -f "$compose_fixture/docker/docker-compose.override.yml" \
      -f "$compose_fixture/docker/docker-compose.smoke-images.override.yml" \
      -f "$compose_fixture/docker/docker-compose.grpc-mtls.override.yml" \
      config --format json >"$rendered_config"
  python3 - "$rendered_config" "$fixture_root" <<'PY'
import json
import pathlib
import sys

config_path, cert_root = sys.argv[1:]
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
assert services["account-service"]["image"].endswith(":contract")
print("Verified Compose mTLS wiring and image-only service configuration.")
PY
else
  echo "Docker Compose unavailable; skipped rendered-configuration assertion."
fi

echo "Compose gRPC mTLS certificate and wiring contract passed."
