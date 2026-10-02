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
[[ "$(stat -c '%a' "$fixture_root/authority")" == 700 ]] || {
  echo "first Compose mTLS certificate setup did not retain owner-only authority permissions." >&2
  exit 1
}
services=(
  account-service gateway automation-scripting-service entity-management-service
  game-design-service game-logic-service game-session-service logging-admin-service
  social-groups-service tcp-proxy-service world-management-service
)
workloads_dir="$fixture_root/workloads"

snapshot_workloads() {
  find "$workloads_dir" -type f -print0 | sort -z \
    | while IFS= read -r -d '' file; do
        printf '%s ' "$(stat -c '%a %h' "$file")"
        sha256sum -- "$file"
      done
}

workloads_before_idempotent_readback="$(snapshot_workloads)"
FIREMUD_SMOKE_TEST_MODE=1 \
FIREMUD_SMOKE_OWNERSHIP_DIR="$ownership_dir" \
FIREMUD_SMOKE_RUN_ID="$run_id" \
COMPOSE_PROJECT_NAME="$project_name" \
  bash "$ROOT_DIR/dev-tools/certs/ensure-dev-certs.sh" --compose-mtls "$fixture_root"
[[ "$workloads_before_idempotent_readback" == "$(snapshot_workloads)" ]] || {
  echo "Compose mTLS certificate verification changed an existing valid fixture." >&2
  exit 1
}

[[ "$(stat -c '%a' "$fixture_root")" == 700 ]]
[[ "$(stat -c '%a' "$fixture_root/authority")" == 700 ]]
[[ "$(stat -c '%a' "$fixture_root/authority/ca.key")" == 600 ]]
[[ ! -e "$workloads_dir/ca.key" ]]
[[ -z "$(find "$workloads_dir" -type f -name ca.key -print -quit)" ]]

snapshot_tree() {
  local tree_root="$1"
  find "$tree_root" -type f -print0 | sort -z \
    | while IFS= read -r -d '' file; do
        printf '%s %s ' "$(stat -c '%a %h' "$file")" "${file#"$tree_root"/}"
        sha256sum -- "$file"
      done
}

# A CA-valid dedicated workload leaf in the shared authority slot is an
# accidental misprojection. Reject it before chmod or workload projection.
misplaced_authority_run_id="${run_id}-misplaced-authority"
misplaced_authority_project_name="firemud-smoke-$misplaced_authority_run_id"
misplaced_authority_project_key="$(printf '%s' "$misplaced_authority_project_name" | sha256sum | awk '{print $1}')"
misplaced_authority_root="$ownership_dir/$misplaced_authority_project_key.grpc-mtls"
misplaced_authority_dir="$misplaced_authority_root/authority"
mkdir -m 700 -- "$misplaced_authority_root"
mkdir -m 755 -- "$misplaced_authority_dir"
cp -- "$fixture_root/authority/ca.crt" "$misplaced_authority_dir/ca.crt"
cp -- "$fixture_root/authority/ca.key" "$misplaced_authority_dir/ca.key"
cp -- "$workloads_dir/game-session-service/client.crt" "$misplaced_authority_dir/client.crt"
cp -- "$workloads_dir/game-session-service/client.key" "$misplaced_authority_dir/client.key"
chmod 644 "$misplaced_authority_dir/ca.crt" "$misplaced_authority_dir/client.crt"
chmod 600 "$misplaced_authority_dir/ca.key"
# Noncanonical client-key mode proves refusal precedes authority-file chmod.
chmod 444 "$misplaced_authority_dir/client.key"
misplaced_authority_before="$(snapshot_tree "$misplaced_authority_root")"
if FIREMUD_SMOKE_TEST_MODE=1 \
  FIREMUD_SMOKE_OWNERSHIP_DIR="$ownership_dir" \
  FIREMUD_SMOKE_RUN_ID="$misplaced_authority_run_id" \
  COMPOSE_PROJECT_NAME="$misplaced_authority_project_name" \
  bash "$ROOT_DIR/dev-tools/certs/ensure-dev-certs.sh" --compose-mtls "$misplaced_authority_root" \
  >"$TEST_ROOT/misplaced-authority-output" 2>&1; then
  echo "Compose mTLS certificate setup accepted a dedicated leaf in the shared authority slot." >&2
  exit 1
fi
rg -Fq 'shared client certificate must not contain a workload URI SAN' "$TEST_ROOT/misplaced-authority-output"
[[ "$misplaced_authority_before" == "$(snapshot_tree "$misplaced_authority_root")" \
  && "$(stat -c '%a' "$misplaced_authority_dir")" == 755 \
  && ! -e "$misplaced_authority_root/workloads" ]] || {
  echo "Compose mTLS source-identity refusal changed authority material or created workload projections." >&2
  exit 1
}

# Existing generic projections receive the same no-URI-SAN check, with all
# files left untouched when a dedicated certificate was placed in one.
misplaced_projection_run_id="${run_id}-misplaced-projection"
misplaced_projection_project_name="firemud-smoke-$misplaced_projection_run_id"
misplaced_projection_project_key="$(printf '%s' "$misplaced_projection_project_name" | sha256sum | awk '{print $1}')"
misplaced_projection_root="$ownership_dir/$misplaced_projection_project_key.grpc-mtls"
mkdir -m 700 -- "$misplaced_projection_root"
cp -a -- "$fixture_root/authority" "$misplaced_projection_root/authority"
cp -a -- "$workloads_dir" "$misplaced_projection_root/workloads"
chmod 644 "$misplaced_projection_root/workloads/gateway/client.crt" \
  "$misplaced_projection_root/workloads/gateway/client.key"
cp -- "$workloads_dir/game-session-service/client.crt" \
  "$misplaced_projection_root/workloads/gateway/client.crt"
cp -- "$workloads_dir/game-session-service/client.key" \
  "$misplaced_projection_root/workloads/gateway/client.key"
chmod 444 "$misplaced_projection_root/workloads/gateway/client.crt" \
  "$misplaced_projection_root/workloads/gateway/client.key"
misplaced_projection_before="$(snapshot_tree "$misplaced_projection_root")"
if FIREMUD_SMOKE_TEST_MODE=1 \
  FIREMUD_SMOKE_OWNERSHIP_DIR="$ownership_dir" \
  FIREMUD_SMOKE_RUN_ID="$misplaced_projection_run_id" \
  COMPOSE_PROJECT_NAME="$misplaced_projection_project_name" \
  bash "$ROOT_DIR/dev-tools/certs/ensure-dev-certs.sh" --compose-mtls "$misplaced_projection_root" \
  >"$TEST_ROOT/misplaced-projection-output" 2>&1; then
  echo "Compose mTLS certificate setup accepted a dedicated leaf in a generic workload projection." >&2
  exit 1
fi
rg -Fq 'shared generic workload must not contain a URI SAN: gateway' "$TEST_ROOT/misplaced-projection-output"
[[ "$misplaced_projection_before" == "$(snapshot_tree "$misplaced_projection_root")" ]] || {
  echo "Compose mTLS generic-identity refusal partially changed an existing workload projection." >&2
  exit 1
}

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

authority_hardlink_run_id="${run_id}-authority-hardlink"
authority_hardlink_project_name="firemud-smoke-$authority_hardlink_run_id"
authority_hardlink_project_key="$(printf '%s' "$authority_hardlink_project_name" | sha256sum | awk '{print $1}')"
authority_hardlink_fixture="$ownership_dir/$authority_hardlink_project_key.grpc-mtls"
authority_hardlink_dir="$authority_hardlink_fixture/authority"
authority_hardlink_key="$authority_hardlink_dir/ca.key"
authority_hardlink_sentinel="$authority_hardlink_dir/ca-key-alias-sentinel"
mkdir -m 700 -- "$authority_hardlink_fixture"
mkdir -m 711 -- "$authority_hardlink_dir"
for file in ca.crt ca.key client.crt client.key; do
  cp -- "$fixture_root/authority/$file" "$authority_hardlink_dir/$file"
done
cp -- "$authority_hardlink_key" "$authority_hardlink_sentinel"
chmod 640 "$authority_hardlink_sentinel"
rm -- "$authority_hardlink_key"
ln -- "$authority_hardlink_sentinel" "$authority_hardlink_key"

snapshot_authority_sources() {
  find "$authority_hardlink_dir" -maxdepth 1 -type f -print0 | sort -z \
    | while IFS= read -r -d '' file; do
        printf '%s ' "$(stat -c '%a %h' "$file")"
        sha256sum -- "$file"
      done
}

authority_before_hardlink_refusal="$(snapshot_authority_sources)"
if FIREMUD_SMOKE_TEST_MODE=1 \
  FIREMUD_SMOKE_OWNERSHIP_DIR="$ownership_dir" \
  FIREMUD_SMOKE_RUN_ID="$authority_hardlink_run_id" \
  COMPOSE_PROJECT_NAME="$authority_hardlink_project_name" \
  bash "$ROOT_DIR/dev-tools/certs/ensure-dev-certs.sh" --compose-mtls "$authority_hardlink_fixture" \
  >"$TEST_ROOT/authority-hardlink-output" 2>&1; then
  echo "Compose mTLS certificate setup accepted a hard-linked authority key." >&2
  exit 1
fi
rg -Fq "refusing hard-linked Compose mTLS authority material: $authority_hardlink_key" \
  "$TEST_ROOT/authority-hardlink-output"
[[ "$authority_before_hardlink_refusal" == "$(snapshot_authority_sources)" \
  && "$(stat -c '%a' "$authority_hardlink_dir")" == 711 \
  && ! -e "$authority_hardlink_fixture/workloads" ]] || {
  echo "Compose mTLS authority preflight partially changed hard-linked source material." >&2
  exit 1
}
[[ "$(<"$authority_hardlink_sentinel")" == "$(<"$fixture_root/authority/ca.key")" ]] || {
  echo "Compose mTLS authority preflight changed the hard-link sentinel contents." >&2
  exit 1
}
[[ "$(stat -c '%a' "$authority_hardlink_sentinel")" == 640 ]] || {
  echo "Compose mTLS authority preflight changed the hard-link sentinel mode." >&2
  exit 1
}

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

identity_services=(
  account-service game-session-service entity-management-service social-groups-service world-management-service
)
declare -A identity_fingerprints=()
for service in "${identity_services[@]}"; do
  echo "Checking exact workload identity: $service"
  san_output="$(openssl x509 -in "$workloads_dir/$service/client.crt" -noout -ext subjectAltName)"
  uri_sans="$(printf '%s\n' "$san_output" | grep -oE 'URI:[^,[:space:]]+' || true)"
  [[ "$uri_sans" == "URI:spiffe://firemud/ns/dev/sa/$service" \
    && "$san_output" == *"DNS:$service"* ]]
  identity_fingerprints["$service"]="$(openssl x509 -in "$workloads_dir/$service/client.crt" -noout -fingerprint -sha256)"
done
for ((i = 0; i < ${#identity_services[@]}; i++)); do
  for ((j = i + 1; j < ${#identity_services[@]}; j++)); do
    left="${identity_services[$i]}"
    right="${identity_services[$j]}"
    [[ "${identity_fingerprints[$left]}" != "${identity_fingerprints[$right]}" ]] || {
      echo "Compose mTLS workloads share a private identity: $left and $right" >&2
      exit 1
    }
  done
done
echo "Checking canonical Compose entrypoints."
mtls_compose="$ROOT_DIR/docker/docker-compose.grpc-mtls.override.yml"
rg -Fq 'FIREMUD_GRPC_PLAINTEXT: "false"' "$mtls_compose"
rg -Fq 'GRPC_SERVER_TLS_ENABLED: "true"' "$mtls_compose"
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

python3 - "$ROOT_DIR/dev-tools/verify-smoke-images.sh" "$mtls_compose" \
  "$ROOT_DIR/dev-tools/verify-fresh-bootstrap.sh" "$ROOT_DIR/dev-tools/verify-restart-state.sh" <<'PY'
import pathlib
import sys

smoke_source_path, mtls_compose_path, *source_wrapper_paths = sys.argv[1:]
smoke_source = pathlib.Path(smoke_source_path).read_text(encoding="utf-8")
profile = smoke_source.index('COMPOSE_FILES+=( -f "$DOCKER_DIR/docker-compose.grpc-mtls.override.yml" )')
for prior_overlay in (
    'docker-compose.smoke-images.override.yml',
    'docker-compose.pr-local-minio.override.yml',
):
    assert smoke_source.index(prior_overlay) < profile, prior_overlay
assert "COMPOSE_UP_ARGS=(up -d --remove-orphans)" in smoke_source
assert "--build" not in smoke_source

mtls_override = pathlib.Path(mtls_compose_path).read_text(encoding="utf-8")
logging_block = mtls_override.split("\n  logging-admin-service:\n", 1)[1].split(
    "\n  social-groups-service:\n", 1
)[0]
assert "FIREMUD_GRPC_WORKLOAD_NAMESPACE: dev" in logging_block

for wrapper_path in source_wrapper_paths:
    wrapper = pathlib.Path(wrapper_path).read_text(encoding="utf-8")
    base = wrapper.index('-f "$ROOT_DIR/docker/docker-compose.yml"')
    local = wrapper.index('-f "$ROOT_DIR/docker/docker-compose.override.yml"')
    mtls = wrapper.index('-f "$ROOT_DIR/docker/docker-compose.grpc-mtls.override.yml"')
    assert base < local < mtls, wrapper_path
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

  rendered_source_config="$TEST_ROOT/compose-source-config.json"
  FIREMUD_COMPOSE_GRPC_MTLS_CERT_ROOT="$fixture_root" \
    docker compose --env-file "$compose_fixture/.env" \
      -f "$compose_fixture/docker/docker-compose.yml" \
      -f "$compose_fixture/docker/docker-compose.override.yml" \
      -f "$compose_fixture/docker/docker-compose.grpc-mtls.override.yml" \
      config --format json >"$rendered_source_config"
  rendered_image_config="$TEST_ROOT/compose-image-config.json"
  FIREMUD_COMPOSE_GRPC_MTLS_CERT_ROOT="$fixture_root" SMOKE_IMAGE_TAG=contract \
    docker compose --env-file "$compose_fixture/.env" \
      -f "$compose_fixture/docker/docker-compose.yml" \
      -f "$compose_fixture/docker/docker-compose.override.yml" \
      -f "$compose_fixture/docker/docker-compose.smoke-images.override.yml" \
      -f "$compose_fixture/docker/docker-compose.grpc-mtls.override.yml" \
      config --format json >"$rendered_image_config"
  python3 - "$rendered_source_config" "$rendered_image_config" "$fixture_root" <<'PY'
import json
import pathlib
import sys

source_config_path, image_config_path, cert_root = sys.argv[1:]
app_names = {
    "account-service", "gateway", "automation-scripting-service",
    "entity-management-service", "game-design-service", "game-logic-service",
    "game-session-service", "logging-admin-service", "social-groups-service",
    "tcp-proxy-service", "world-management-service",
}
for profile, config_path, image_only in (
    ("source", source_config_path, False),
    ("images", image_config_path, True),
):
    services = json.loads(pathlib.Path(config_path).read_text(encoding="utf-8"))["services"]
    for name in app_names:
        service = services[name]
        environment = service.get("environment", {})
        assert environment.get("FIREMUD_GRPC_PLAINTEXT") == "false", (profile, name)
        assert environment.get("GRPC_SERVER_TLS_ENABLED") == "true", (profile, name)
        assert environment.get("FIREMUD_GRPC_CERT_CHAIN_PATH") == "/app/certs/client.crt", (profile, name)
        assert environment.get("FIREMUD_GRPC_PRIVATE_KEY_PATH") == "/app/certs/client.key", (profile, name)
        assert environment.get("FIREMUD_GRPC_CA_CERT_PATH") == "/app/certs/ca.crt", (profile, name)
        mounts = [mount for mount in service.get("volumes", []) if mount.get("target") == "/app/certs"]
        assert len(mounts) == 1, (profile, name)
        assert mounts[0]["source"] == f"{cert_root}/workloads/{name}", (profile, name, mounts[0])
        assert mounts[0].get("read_only") is True, (profile, name)
        assert "/authority" not in mounts[0]["source"], (profile, name, mounts[0])
        if image_only:
            assert service.get("build") is None, f"image-only proof must not build {name}"

    for name in (
        "account-service", "entity-management-service", "logging-admin-service",
        "social-groups-service", "world-management-service",
    ):
        namespace = services[name]["environment"].get("FIREMUD_GRPC_WORKLOAD_NAMESPACE")
        assert namespace == "dev", (profile, name, namespace)
    if image_only:
        assert services["account-service"]["image"].endswith(":contract")
print("Verified source and image Compose mTLS wiring and workload identities.")
PY
else
  echo "Docker Compose unavailable; source/layer invariant passed, skipped rendered-configuration assertion."
fi

social_groups_key="$workloads_dir/social-groups-service/client.key"
social_groups_key_backup="$TEST_ROOT/social-groups-client-key.backup"
cp -- "$social_groups_key" "$social_groups_key_backup"
chmod 644 "$social_groups_key"
cp -- "$workloads_dir/account-service/client.key" "$social_groups_key"
chmod 444 "$social_groups_key"
mismatched_key_snapshot="$(snapshot_workloads)"
if FIREMUD_SMOKE_TEST_MODE=1 \
  FIREMUD_SMOKE_OWNERSHIP_DIR="$ownership_dir" \
  FIREMUD_SMOKE_RUN_ID="$run_id" \
  COMPOSE_PROJECT_NAME="$project_name" \
  bash "$ROOT_DIR/dev-tools/certs/ensure-dev-certs.sh" --compose-mtls "$fixture_root" \
  >"$TEST_ROOT/mismatched-key-output" 2>&1; then
  echo "Compose mTLS certificate verification accepted a mismatched certificate/key pair." >&2
  exit 1
fi
rg -Fq 'Compose mTLS workload certificate and private key do not match: social-groups-service' \
  "$TEST_ROOT/mismatched-key-output"
[[ "$mismatched_key_snapshot" == "$(snapshot_workloads)" ]] || {
  echo "Compose mTLS verification changed an existing mismatched fixture." >&2
  exit 1
}
chmod 644 "$social_groups_key"
cp -- "$social_groups_key_backup" "$social_groups_key"
chmod 444 "$social_groups_key"

social_groups_leaf="$workloads_dir/social-groups-service/client.crt"
chmod 644 "$social_groups_leaf" "$social_groups_key"
cp -- "$workloads_dir/account-service/client.crt" "$social_groups_leaf"
cp -- "$workloads_dir/account-service/client.key" "$social_groups_key"
chmod 444 "$social_groups_leaf" "$social_groups_key"
wrong_leaf_snapshot="$(snapshot_workloads)"
if FIREMUD_SMOKE_TEST_MODE=1 \
  FIREMUD_SMOKE_OWNERSHIP_DIR="$ownership_dir" \
  FIREMUD_SMOKE_RUN_ID="$run_id" \
  COMPOSE_PROJECT_NAME="$project_name" \
  bash "$ROOT_DIR/dev-tools/certs/ensure-dev-certs.sh" --compose-mtls "$fixture_root" \
  >"$TEST_ROOT/wrong-leaf-output" 2>&1; then
  echo "Compose mTLS certificate verification accepted the Account leaf for Social Groups." >&2
  exit 1
fi
rg -Fq 'Compose mTLS workload has the wrong SPIFFE identity: social-groups-service' \
  "$TEST_ROOT/wrong-leaf-output"
[[ "$wrong_leaf_snapshot" == "$(snapshot_workloads)" ]] || {
  echo "Compose mTLS certificate verification changed an existing wrong-identity fixture." >&2
  exit 1
}

echo "Compose gRPC mTLS certificate and wiring contract passed."
