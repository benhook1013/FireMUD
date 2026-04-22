#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
COMPOSE_FILES=(-f "$ROOT_DIR/docker/docker-compose.yml" -f "$ROOT_DIR/docker/docker-compose.override.yml")
TCP_SMOKE_SCRIPT="$ROOT_DIR/services/tcp-proxy-service/telnet-login-look-smoke.sh"
WS_SMOKE_SCRIPT="$ROOT_DIR/services/game-session-service/websocket-login-look-smoke.sh"
HEALTH_SCRIPT="$ROOT_DIR/dev-tools/verify-compose-health.sh"
BUILD_JARS_SCRIPT="$ROOT_DIR/dev-tools/build-compose-service-jars.sh"

echo "Restart-state proof: preserve local compose volumes, restart the stack, then run WebSocket/Telnet LOGIN -> PLAY -> item/container/equipment proofs."
echo "Local volumes are left intact."

"$BUILD_JARS_SCRIPT"
docker compose "${COMPOSE_FILES[@]}" up -d --remove-orphans
docker compose "${COMPOSE_FILES[@]}" restart

"$HEALTH_SCRIPT"
bash "$WS_SMOKE_SCRIPT"
bash "$TCP_SMOKE_SCRIPT"
