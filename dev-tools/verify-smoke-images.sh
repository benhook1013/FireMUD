#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DOCKER_DIR="$ROOT_DIR/docker"
ROOT_ENV_FILE="$ROOT_DIR/.env"
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
COMPOSE_UP_ARGS=(up -d --remove-orphans)
SMOKE_COMPOSE_UP_ATTEMPTS="${SMOKE_COMPOSE_UP_ATTEMPTS:-3}"
SMOKE_COMPOSE_UP_RETRY_DELAY_SECONDS="${SMOKE_COMPOSE_UP_RETRY_DELAY_SECONDS:-5}"

export TERM="${TERM:-dumb}"
export COMPOSE_PROGRESS="${COMPOSE_PROGRESS:-plain}"

if [[ -z "${SMOKE_IMAGE_TAG:-}" ]]; then
  echo "SMOKE_IMAGE_TAG is required" >&2
  exit 1
fi

if [[ "${SMOKE_IMAGE_LOCAL_ONLY:-false}" == "true" ]]; then
  COMPOSE_UP_ARGS+=(--pull never)
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
    docker compose "${COMPOSE_FILES[@]}" down -v --remove-orphans || true
    sleep "$SMOKE_COMPOSE_UP_RETRY_DELAY_SECONDS"
    attempt=$((attempt + 1))
  done
}

echo "Smoke image proof: destroy local compose state, resolve smoke-image tags via docker/.env, start the stack, then run WebSocket and Telnet LOGIN -> PLAY -> item/container/equipment proofs."
if [[ "${SMOKE_IMAGE_LOCAL_ONLY:-false}" == "true" ]]; then
  echo "Local-only mode enabled: compose will reuse matching local images and skip remote pulls."
fi
printf 'SMOKE_IMAGE_TAG=%s\n' "$SMOKE_IMAGE_TAG" >"$DOCKER_ENV_FILE"
if [[ -f "$ROOT_ENV_FILE" ]]; then
  ROOT_ENV_BACKUP="$(mktemp)"
  cp "$ROOT_ENV_FILE" "$ROOT_ENV_BACKUP"
fi
cat >"$ROOT_ENV_FILE" <<EOF
SMOKE_IMAGE_TAG=$SMOKE_IMAGE_TAG
EOF

docker compose "${COMPOSE_FILES[@]}" config >/dev/null
docker compose "${COMPOSE_FILES[@]}" down -v --remove-orphans
compose_up_with_retry
bash "$HEALTH_CHECK_SCRIPT" "${COMPOSE_FILES[@]}"

# These smoke clients intentionally reuse the same seeded demo account/runtime
# state and must stay sequential unless the caller isolates accounts/session ids.
bash "$WS_SMOKE_SCRIPT"
bash "$TCP_SMOKE_SCRIPT"
