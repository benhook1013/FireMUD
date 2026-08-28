#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
COMPOSE_FILES=(-f "$ROOT_DIR/docker/docker-compose.yml" -f "$ROOT_DIR/docker/docker-compose.override.yml")
TCP_SMOKE_SCRIPT="$ROOT_DIR/services/tcp-proxy-service/telnet-login-look-smoke.sh"
WS_SMOKE_SCRIPT="$ROOT_DIR/services/game-session-service/websocket-login-look-smoke.sh"
HEALTH_SCRIPT="$ROOT_DIR/dev-tools/verify-compose-health.sh"
BUILD_JARS_SCRIPT="$ROOT_DIR/dev-tools/build-compose-service-jars.sh"
ENSURE_CERTS_SCRIPT="$ROOT_DIR/dev-tools/certs/ensure-dev-certs.sh"
ENSURE_ENV_SCRIPT="$ROOT_DIR/dev-tools/ensure-local-compose-env.sh"

# Prefer noninteractive compose output for AI/automation callers. PTY-backed runs
# under Docker Desktop/WSL can hang in compose teardown even when the same command
# completes immediately in plain mode.
export TERM="${TERM:-dumb}"
export COMPOSE_PROGRESS="${COMPOSE_PROGRESS:-plain}"
FIREMUD_SMOKE_SERIAL_BUILD="${FIREMUD_SMOKE_SERIAL_BUILD:-1}"
FIREMUD_SMOKE_NO_CACHE_SERVICES="${FIREMUD_SMOKE_NO_CACHE_SERVICES:-}"
FIREMUD_SMOKE_COMPOSE_SERVICES="${FIREMUD_SMOKE_COMPOSE_SERVICES:-}"
FIREMUD_SMOKE_VALIDATE_ONLY="${FIREMUD_SMOKE_VALIDATE_ONLY:-0}"
SMOKE_MUTATION_EXTENSION="${SMOKE_MUTATION_EXTENSION:-false}"
case "$SMOKE_MUTATION_EXTENSION" in
  false|0)
    export SMOKE_MUTATION_EXTENSION=false
    ;;
  true|1)
    echo "SMOKE_MUTATION_EXTENSION is not supported by the two-transport wrapper: independent transport identities/state are not proven; run each transport smoke separately." >&2
    exit 1
    ;;
  *)
    echo "SMOKE_MUTATION_EXTENSION must be boolean true/false (or 1/0); refusing to run." >&2
    exit 1
    ;;
esac

require_run_owned_compose_project() {
  local project_name="${COMPOSE_PROJECT_NAME:-}"
  if [[ ! "$project_name" =~ ^(firemud-smoke-[a-z0-9][a-z0-9-]*|smoke-full-[0-9]+-[0-9]+)$ ]]; then
    echo "Refusing destructive smoke teardown: set COMPOSE_PROJECT_NAME to an explicit run-owned name matching firemud-smoke-<unique-run-id> or smoke-full-<run-id>-<attempt> (not blank, default, or shared)." >&2
    exit 1
  fi
}

bash "$ENSURE_ENV_SCRIPT"
COMPOSE_SERVICES=()
if [[ -n "$FIREMUD_SMOKE_COMPOSE_SERVICES" ]]; then
  while IFS= read -r service; do
    if [[ -n "$service" ]]; then
      COMPOSE_SERVICES+=("$service")
    fi
  done <<<"$FIREMUD_SMOKE_COMPOSE_SERVICES"
else
  if ! compose_services_output="$(docker compose "${COMPOSE_FILES[@]}" config --services)"; then
    echo "Docker Compose service discovery failed." >&2
    exit 1
  fi
  while IFS= read -r service; do
    if [[ -n "$service" ]]; then
      COMPOSE_SERVICES+=("$service")
    fi
  done <<<"$compose_services_output"
fi
if ((${#COMPOSE_SERVICES[@]} == 0)); then
  echo "No Docker Compose services were discovered." >&2
  exit 1
fi

declare -A ALL_COMPOSE_SERVICES=()
for service in "${COMPOSE_SERVICES[@]}"; do
  ALL_COMPOSE_SERVICES["$service"]=1
done

declare -A NO_CACHE_SERVICES=()
if [[ -n "$FIREMUD_SMOKE_NO_CACHE_SERVICES" ]]; then
  for service in ${FIREMUD_SMOKE_NO_CACHE_SERVICES//,/ }; do
    if [[ -z "${ALL_COMPOSE_SERVICES[$service]:-}" ]]; then
      echo "Unknown service in FIREMUD_SMOKE_NO_CACHE_SERVICES: $service" >&2
      echo "Use Docker Compose service ids here, not Gradle module names. Example: 'gateway', not 'spring-cloud-gateway'." >&2
      echo "Known compose services: ${COMPOSE_SERVICES[*]}" >&2
      exit 1
    fi
    NO_CACHE_SERVICES["$service"]=1
  done
fi

echo "Fresh bootstrap proof: destroy this run-owned compose project's containers, networks, and named volumes, then rebuild and run WebSocket/Telnet LOGIN -> PLAY -> LOOK baseline proofs."
echo "Destroyed named volumes: postgres-data, redis-coord-data, minio-data"
if [[ -n "$FIREMUD_SMOKE_NO_CACHE_SERVICES" ]]; then
  echo "Forcing no-cache compose rebuild for: ${FIREMUD_SMOKE_NO_CACHE_SERVICES//,/ }"
fi

if [[ "$FIREMUD_SMOKE_VALIDATE_ONLY" == "1" ]]; then
  echo "Validation-only mode: compose service selector parsing succeeded."
  exit 0
fi

require_run_owned_compose_project
docker compose "${COMPOSE_FILES[@]}" down -v --remove-orphans
bash "$ENSURE_CERTS_SCRIPT"
bash "$BUILD_JARS_SCRIPT"
if [[ "$FIREMUD_SMOKE_SERIAL_BUILD" == "1" ]]; then
  for service in "${COMPOSE_SERVICES[@]}"; do
    if [[ -n "${NO_CACHE_SERVICES[$service]:-}" ]]; then
      docker compose "${COMPOSE_FILES[@]}" build --no-cache "$service"
    else
      docker compose "${COMPOSE_FILES[@]}" build "$service"
    fi
  done
else
  remaining_services=()
  for service in "${COMPOSE_SERVICES[@]}"; do
    if [[ -n "${NO_CACHE_SERVICES[$service]:-}" ]]; then
      docker compose "${COMPOSE_FILES[@]}" build --no-cache "$service"
    else
      remaining_services+=("$service")
    fi
  done
  export COMPOSE_PARALLEL_LIMIT="${COMPOSE_PARALLEL_LIMIT:-4}"
  if ((${#remaining_services[@]} > 0)); then
    docker compose "${COMPOSE_FILES[@]}" build "${remaining_services[@]}"
  fi
fi
docker compose "${COMPOSE_FILES[@]}" up -d --remove-orphans

bash "$HEALTH_SCRIPT"
# Both transport legs are baseline-only; mutation parity requires independent
# transport identities/state and is rejected by this wrapper above.
bash "$WS_SMOKE_SCRIPT"
bash "$TCP_SMOKE_SCRIPT"
