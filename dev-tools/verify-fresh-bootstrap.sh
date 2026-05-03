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

echo "Fresh bootstrap proof: destroy local compose containers, networks, and named volumes, then rebuild and run WebSocket/Telnet LOGIN -> PLAY -> item/container/equipment proofs."
echo "Destroyed named volumes: postgres-data, redis-coord-data, minio-data"

docker compose "${COMPOSE_FILES[@]}" down -v --remove-orphans
"$BUILD_JARS_SCRIPT"
while IFS= read -r service; do
  docker compose "${COMPOSE_FILES[@]}" build "$service"
done < <(docker compose "${COMPOSE_FILES[@]}" config --services)
docker compose "${COMPOSE_FILES[@]}" up -d --remove-orphans

"$HEALTH_SCRIPT"
# These smoke clients intentionally reuse the same seeded demo account/runtime
# state and must stay sequential unless the caller isolates accounts/session ids.
bash "$WS_SMOKE_SCRIPT"
bash "$TCP_SMOKE_SCRIPT"
