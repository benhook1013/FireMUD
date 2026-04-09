#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DOCKER_DIR="$ROOT_DIR/docker"
DOCKER_ENV_FILE="$DOCKER_DIR/.env"
COMPOSE_FILES=(
  -f "$DOCKER_DIR/docker-compose.yml"
  -f "$DOCKER_DIR/docker-compose.smoke-images.override.yml"
)
TCP_SMOKE_SCRIPT="$ROOT_DIR/services/tcp-proxy-service/telnet-login-look-smoke.sh"
WS_SMOKE_SCRIPT="$ROOT_DIR/services/game-session-service/websocket-login-look-smoke.sh"

if [[ -z "${SMOKE_IMAGE_TAG:-}" ]]; then
  echo "SMOKE_IMAGE_TAG is required" >&2
  exit 1
fi

cleanup() {
  rm -f "$DOCKER_ENV_FILE"
}
trap cleanup EXIT

echo "Smoke image proof: destroy local compose state, resolve smoke-image tags via docker/.env, start the stack, then run WebSocket and Telnet LOGIN -> PLAY -> LOOK proofs."
printf 'SMOKE_IMAGE_TAG=%s\n' "$SMOKE_IMAGE_TAG" >"$DOCKER_ENV_FILE"

docker compose "${COMPOSE_FILES[@]}" config >/dev/null
docker compose "${COMPOSE_FILES[@]}" down -v --remove-orphans
docker compose "${COMPOSE_FILES[@]}" up -d --remove-orphans

bash "$WS_SMOKE_SCRIPT"
bash "$TCP_SMOKE_SCRIPT"
