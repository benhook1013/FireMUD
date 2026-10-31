#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
CERT_HELPER="$REPO_ROOT/dev-tools/certs/ensure-local-compose-mtls.sh"
tmp_root="$(mktemp -d)"
trap 'if [[ -n "$tmp_root" && -d "$tmp_root" && ! -L "$tmp_root" ]]; then rm -rf -- "$tmp_root"; fi' EXIT
umask 077

cert_root="$tmp_root/local-dev-certs"
bash "$CERT_HELPER" "$cert_root" >/dev/null

owner_id="$(id -u)"
[[ "$(stat -c '%u %a %F' "$cert_root")" == "$owner_id 700 directory" ]]
[[ "$(stat -c '%u %a %F' "$cert_root/authority")" == "$owner_id 700 directory" ]]
[[ "$(stat -c '%u %a %F' "$cert_root/authority/ca.key")" == "$owner_id 600 regular file" ]]
[[ "$(stat -c '%u %a %F' "$cert_root/authority/ca.crt")" == "$owner_id 644 regular file" ]]

services=(
  account-service spring-cloud-gateway automation-scripting-service entity-management-service
  game-design-service game-logic-service game-session-service logging-admin-service
  social-groups-service tcp-proxy-service world-management-service
)
for service in "${services[@]}"; do
  service_dir="$cert_root/workloads/$service"
  [[ -d "$service_dir" && ! -L "$service_dir" ]]
  [[ "$(stat -c '%u %a %F' "$service_dir")" == "$owner_id 755 directory" ]]
  [[ "$(find "$service_dir" -mindepth 1 -maxdepth 1 -type f | wc -l)" -eq 3 ]]
  [[ ! -e "$service_dir/ca.key" && ! -L "$service_dir/ca.key" ]]
  [[ "$(stat -c '%u %a %F' "$service_dir/client.crt")" == "$owner_id 444 regular file" ]]
  [[ "$(stat -c '%u %a %F' "$service_dir/client.key")" == "$owner_id 444 regular file" ]]
  [[ "$(stat -c '%u %a %F' "$service_dir/ca.crt")" == "$owner_id 444 regular file" ]]
  cmp -s "$cert_root/authority/ca.crt" "$service_dir/ca.crt"
  openssl verify -CAfile "$cert_root/authority/ca.crt" "$service_dir/client.crt" >/dev/null
  san_output="$(openssl x509 -in "$service_dir/client.crt" -noout -ext subjectAltName)"
  mapfile -t uri_sans < <(printf '%s\n' "$san_output" \
    | tr ',' '\n' | sed -n 's/^[[:space:]]*\(URI:.*\)$/\1/p')
  [[ "${#uri_sans[@]}" -eq 1 ]]
  [[ "${uri_sans[0]}" == "URI:spiffe://firemud/ns/dev/sa/$service" ]]
done

before="$(find "$cert_root" -type f -print0 | sort -z | xargs -0 sha256sum)"
bash "$CERT_HELPER" "$cert_root" >/dev/null
after="$(find "$cert_root" -type f -print0 | sort -z | xargs -0 sha256sum)"
[[ "$before" == "$after" ]]

corrupt_root="$tmp_root/corrupt"
mkdir -m 700 -- "$corrupt_root"
mkdir -m 700 -- "$corrupt_root/authority"
printf 'retained-corrupt-certificate\n' >"$corrupt_root/authority/ca.crt"
chmod 644 "$corrupt_root/authority/ca.crt"
if bash "$CERT_HELPER" "$corrupt_root" >"$tmp_root/corrupt-output" 2>&1; then
  echo "local Compose mTLS helper accepted incomplete retained authority material." >&2
  exit 1
fi
grep -Fq "new dedicated FIREMUD_LOCAL_COMPOSE_MTLS_CERT_ROOT" "$tmp_root/corrupt-output"
[[ "$(cat "$corrupt_root/authority/ca.crt")" == "retained-corrupt-certificate" ]]
[[ ! -e "$corrupt_root/authority/ca.key" && ! -L "$corrupt_root/authority/ca.key" ]]

symlink_root="$tmp_root/symlink"
symlink_target="$tmp_root/retained-ca-key"
mkdir -m 700 -- "$symlink_root"
mkdir -m 700 -- "$symlink_root/authority"
printf 'retained-target\n' >"$symlink_target"
chmod 600 "$symlink_target"
printf 'retained-certificate\n' >"$symlink_root/authority/ca.crt"
chmod 644 "$symlink_root/authority/ca.crt"
ln -s "$symlink_target" "$symlink_root/authority/ca.key"
if bash "$CERT_HELPER" "$symlink_root" >/dev/null 2>&1; then
  echo "local Compose mTLS helper accepted a symlinked retained output." >&2
  exit 1
fi
[[ -L "$symlink_root/authority/ca.key" ]]
[[ "$(cat "$symlink_target")" == "retained-target" ]]

compose_fixture="$tmp_root/compose"
mkdir -m 700 -- "$compose_fixture"
mkdir -m 700 -- "$compose_fixture/docker"
sed 's#\.\./\.env#./compose.env#g' "$REPO_ROOT/docker/docker-compose.yml" \
  >"$compose_fixture/docker/docker-compose.yml"
cp -- "$REPO_ROOT/docker/docker-compose.override.yml" "$compose_fixture/docker/docker-compose.override.yml"
cp -- "$REPO_ROOT/docker/docker-compose.local-images.override.yml" \
  "$compose_fixture/docker/docker-compose.local-images.override.yml"
cp -- "$REPO_ROOT/docker/docker-compose.local-mtls.override.yml" \
  "$compose_fixture/docker/docker-compose.local-mtls.override.yml"
cp -- "$REPO_ROOT/.env.sample" "$compose_fixture/docker/compose.env"
cp -- "$REPO_ROOT/.env.sample" "$compose_fixture/.env"

compose_rendered="false"
if command -v docker >/dev/null 2>&1 && docker compose version >/dev/null 2>&1; then
  compose_json="$tmp_root/compose.json"
  FIREMUD_LOCAL_COMPOSE_MTLS_CERT_ROOT="$cert_root" docker compose \
    --env-file "$compose_fixture/.env" \
    -f "$compose_fixture/docker/docker-compose.yml" \
    -f "$compose_fixture/docker/docker-compose.override.yml" \
    -f "$compose_fixture/docker/docker-compose.local-images.override.yml" \
    -f "$compose_fixture/docker/docker-compose.local-mtls.override.yml" \
    config --format json >"$compose_json"
  compose_rendered="true"
  python3 - "$compose_json" "$cert_root" <<'PYTHON'
import json
import pathlib
import sys

config_path, cert_root_arg = sys.argv[1:]
config = json.loads(pathlib.Path(config_path).read_text())
cert_root = pathlib.Path(cert_root_arg).resolve()
services = (
    "account-service",
    "gateway",
    "automation-scripting-service",
    "entity-management-service",
    "game-design-service",
    "game-logic-service",
    "game-session-service",
    "logging-admin-service",
    "social-groups-service",
    "tcp-proxy-service",
    "world-management-service",
)
expected_environment = {
    "FIREMUD_GRPC_PLAINTEXT": "false",
    "GRPC_SERVER_TLS_ENABLED": "true",
    "GRPC_SERVER_CLIENT_AUTH": "REQUIRE",
    "FIREMUD_GRPC_CERT_CHAIN_PATH": "/app/certs/client.crt",
    "FIREMUD_GRPC_PRIVATE_KEY_PATH": "/app/certs/client.key",
    "FIREMUD_GRPC_CA_CERT_PATH": "/app/certs/ca.crt",
    "FIREMUD_GRPC_WORKLOAD_NAMESPACE": "dev",
}

for name in services:
    service = config["services"][name]
    workload_name = "spring-cloud-gateway" if name == "gateway" else name
    environment = service["environment"]
    for key, expected in expected_environment.items():
        assert environment[key] == expected, (name, key, environment.get(key))
    probe = "liveness" if name in {"gateway", "tcp-proxy-service"} else "readiness"
    assert f"/actuator/health/{probe}" in service["healthcheck"]["test"][-1], (name, service["healthcheck"])
    cert_mounts = [volume for volume in service.get("volumes", []) if volume.get("target") == "/app/certs"]
    assert len(cert_mounts) == 1, (name, cert_mounts)
    mount = cert_mounts[0]
    assert mount["type"] == "bind" and mount["read_only"] is True, (name, mount)
    assert pathlib.Path(mount["source"]).resolve() == cert_root / "workloads" / workload_name, (name, mount)
    assert not any("ca.key" in str(volume.get("source", "")) for volume in service.get("volumes", []))
    assert not any("run-owned" in str(volume.get("target", "")) for volume in service.get("volumes", []))

entity_dependencies = config["services"]["entity-management-service"]["depends_on"]
assert entity_dependencies["account-service"]["condition"] == "service_healthy", entity_dependencies
for dependency in ("postgres", "redis-coord", "redis-cache"):
    assert dependency in entity_dependencies, entity_dependencies
PYTHON
else
  echo "UNRUN: local Compose profile rendering requires Docker Compose v2; certificate and retained-material checks ran." >&2
fi
if [[ "$compose_rendered" == "true" ]]; then
  echo "Local Compose mTLS certificate, profile rendering, and retained-material contract passed."
else
  echo "Local Compose mTLS certificate and retained-material checks passed; profile rendering was unrun."
fi
