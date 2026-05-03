#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
COMPOSE_FILES=(-f "$ROOT_DIR/docker/docker-compose.yml" -f "$ROOT_DIR/docker/docker-compose.override.yml")
TCP_SMOKE_SCRIPT="$ROOT_DIR/services/tcp-proxy-service/telnet-login-look-smoke.sh"
WS_SMOKE_SCRIPT="$ROOT_DIR/services/game-session-service/websocket-login-look-smoke.sh"
HEALTH_SCRIPT="$ROOT_DIR/dev-tools/verify-compose-health.sh"
BUILD_JARS_SCRIPT="$ROOT_DIR/dev-tools/build-compose-service-jars.sh"

# Prefer noninteractive compose output for AI/automation callers. PTY-backed runs
# under Docker Desktop/WSL can hang in compose teardown even when the same command
# completes immediately in plain mode.
export TERM="${TERM:-dumb}"
export COMPOSE_PROGRESS="${COMPOSE_PROGRESS:-plain}"
FIREMUD_SMOKE_SERIAL_BUILD="${FIREMUD_SMOKE_SERIAL_BUILD:-0}"
export COMPOSE_PARALLEL_LIMIT="${COMPOSE_PARALLEL_LIMIT:-4}"

echo "Fresh bootstrap proof: destroy local compose containers, networks, and named volumes, then rebuild and run WebSocket/Telnet LOGIN -> PLAY -> item/container/equipment proofs."
echo "Destroyed named volumes: postgres-data, redis-coord-data, minio-data"

docker compose "${COMPOSE_FILES[@]}" down -v --remove-orphans
"$BUILD_JARS_SCRIPT"
if [[ "$FIREMUD_SMOKE_SERIAL_BUILD" == "1" ]]; then
  COMPOSE_PARALLEL_LIMIT=1 docker compose "${COMPOSE_FILES[@]}" build
else
  docker compose "${COMPOSE_FILES[@]}" build
fi
docker compose "${COMPOSE_FILES[@]}" up -d --remove-orphans

"$HEALTH_SCRIPT"
# These smoke clients intentionally reuse the same seeded demo account/runtime
# state and must stay sequential unless the caller isolates accounts/session ids.
bash "$WS_SMOKE_SCRIPT"
bash "$TCP_SMOKE_SCRIPT"
