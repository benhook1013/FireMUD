#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DOCKER_DIR="$ROOT_DIR/docker"
ROOT_ENV_FILE="$ROOT_DIR/.env"
ROOT_ENV_SAMPLE="$ROOT_DIR/.env.sample"
DOCKER_ENV_FILE="$DOCKER_DIR/.env"
ROOT_ENV_BACKUP=""
COMPOSE_FILES=(
  -f "$DOCKER_DIR/docker-compose.yml"
  -f "$DOCKER_DIR/docker-compose.override.yml"
  -f "$DOCKER_DIR/docker-compose.smoke-images.override.yml"
)
TCP_SMOKE_SCRIPT="$ROOT_DIR/services/tcp-proxy-service/telnet-login-look-smoke.sh"
WS_SMOKE_SCRIPT="$ROOT_DIR/services/game-session-service/websocket-login-look-smoke.sh"
HEALTH_CHECK_SCRIPT="$ROOT_DIR/dev-tools/verify-compose-health.sh"
ENSURE_CERTS_SCRIPT="$ROOT_DIR/dev-tools/certs/ensure-dev-certs.sh"
COMPOSE_UP_ARGS=(up -d --remove-orphans)
SMOKE_COMPOSE_UP_ATTEMPTS="${SMOKE_COMPOSE_UP_ATTEMPTS:-3}"
SMOKE_COMPOSE_UP_RETRY_DELAY_SECONDS="${SMOKE_COMPOSE_UP_RETRY_DELAY_SECONDS:-5}"

export TERM="${TERM:-dumb}"
export COMPOSE_PROGRESS="${COMPOSE_PROGRESS:-plain}"

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

if [[ -z "${SMOKE_IMAGE_TAG:-}" ]]; then
  echo "SMOKE_IMAGE_TAG is required" >&2
  exit 1
fi

if [[ "${SMOKE_IMAGE_LOCAL_ONLY:-false}" == "true" ]]; then
  export SMOKE_IMAGE_PULL_POLICY=never
fi

cleanup() {
  rm -f "$DOCKER_ENV_FILE"
  if [[ -n "$ROOT_ENV_BACKUP" && -f "$ROOT_ENV_BACKUP" ]]; then
    mv "$ROOT_ENV_BACKUP" "$ROOT_ENV_FILE"
  else
    rm -f "$ROOT_ENV_FILE"
  fi
}
trap cleanup EXIT

merge_env_vars() {
  local source_file="$1"
  local target_file="$2"
  [[ -f "$source_file" ]] || return 0
  while IFS= read -r line || [[ -n "$line" ]]; do
    [[ "$line" =~ ^[A-Za-z_][A-Za-z0-9_]*= ]] || continue
    local key="${line%%=*}"
    local value="${line#*=}"
    upsert_env_var "$target_file" "$key" "$value"
  done <"$source_file"
}
upsert_env_var() {
  local file="$1"
  local key="$2"
  local value="$3"
  local tmp
  tmp="$(mktemp)"
  if [[ -f "$file" ]]; then
    grep -v "^${key}=" "$file" >"$tmp" || true
  fi
  printf '%s=%s\n' "$key" "$value" >>"$tmp"
  mv "$tmp" "$file"
}

compose_up_with_retry() {
  local attempt=1
  local max_attempts="$SMOKE_COMPOSE_UP_ATTEMPTS"
  while true; do
    if docker compose "${COMPOSE_FILES[@]}" "${COMPOSE_UP_ARGS[@]}"; then
      return 0
    fi
    if (( attempt >= max_attempts )); then
      return 1
    fi
    echo "docker compose up failed on attempt ${attempt}/${max_attempts}; retrying after ${SMOKE_COMPOSE_UP_RETRY_DELAY_SECONDS}s" >&2
    require_run_owned_compose_project
    docker compose "${COMPOSE_FILES[@]}" down -v --remove-orphans || true
    sleep "$SMOKE_COMPOSE_UP_RETRY_DELAY_SECONDS"
    attempt=$((attempt + 1))
  done
}

echo "Smoke image proof: destroy this run-owned compose project's state, resolve smoke-image tags via docker/.env, start the stack, then run WebSocket and Telnet LOGIN -> PLAY -> LOOK baseline proofs."
if [[ "${SMOKE_IMAGE_LOCAL_ONLY:-false}" == "true" ]]; then
  echo "Local-only mode enabled: compose will reuse matching local FireMUD images while pulling missing dependencies."
fi

if [[ -f "$ROOT_ENV_FILE" ]]; then
  ROOT_ENV_BACKUP="$(mktemp)"
  cp "$ROOT_ENV_FILE" "$ROOT_ENV_BACKUP"
elif [[ -f "$ROOT_ENV_SAMPLE" ]]; then
  cp "$ROOT_ENV_SAMPLE" "$ROOT_ENV_FILE"
else
  echo ".env.sample is missing; cannot seed local compose defaults for smoke images." >&2
  exit 1
fi

if [[ -f "$ROOT_ENV_SAMPLE" ]]; then
  cp "$ROOT_ENV_SAMPLE" "$ROOT_ENV_FILE"
elif [[ -n "$ROOT_ENV_BACKUP" && -f "$ROOT_ENV_BACKUP" ]]; then
  cp "$ROOT_ENV_BACKUP" "$ROOT_ENV_FILE"
else
  echo ".env.sample is missing; cannot seed local compose defaults for smoke images." >&2
  exit 1
fi

if [[ -n "$ROOT_ENV_BACKUP" && -f "$ROOT_ENV_BACKUP" ]]; then
  merge_env_vars "$ROOT_ENV_BACKUP" "$ROOT_ENV_FILE"
fi

cp "$ROOT_ENV_FILE" "$DOCKER_ENV_FILE"
upsert_env_var "$ROOT_ENV_FILE" "SMOKE_IMAGE_TAG" "$SMOKE_IMAGE_TAG"
upsert_env_var "$DOCKER_ENV_FILE" "SMOKE_IMAGE_TAG" "$SMOKE_IMAGE_TAG"

docker compose "${COMPOSE_FILES[@]}" config >/dev/null
if [[ "${SMOKE_COMPOSE_CONFIG_ONLY:-false}" == "true" ]]; then
  exit 0
fi
require_run_owned_compose_project
docker compose "${COMPOSE_FILES[@]}" down -v --remove-orphans
bash "$ENSURE_CERTS_SCRIPT"
compose_up_with_retry
bash "$HEALTH_CHECK_SCRIPT" "${COMPOSE_FILES[@]}"

# Both transport legs are baseline-only; mutation parity requires independent
# transport identities/state and is rejected by this wrapper above.
bash "$WS_SMOKE_SCRIPT"
bash "$TCP_SMOKE_SCRIPT"
