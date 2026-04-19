#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
COMPOSE_FILES=(-f "$ROOT_DIR/docker/docker-compose.yml" -f "$ROOT_DIR/docker/docker-compose.override.yml")
SMOKE_SCRIPT="$ROOT_DIR/services/tcp-proxy-service/telnet-login-look-smoke.sh"
HEALTH_SCRIPT="$ROOT_DIR/dev-tools/verify-compose-health.sh"

echo "Fresh bootstrap proof: destroy local compose containers, networks, and named volumes, then rebuild and run LOGIN -> PLAY -> LOOK."
echo "Destroyed named volumes: postgres-data, redis-coord-data, minio-data"

docker compose "${COMPOSE_FILES[@]}" down -v --remove-orphans
docker compose "${COMPOSE_FILES[@]}" up -d --build --remove-orphans

"$HEALTH_SCRIPT"
bash "$SMOKE_SCRIPT"
