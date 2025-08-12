#!/usr/bin/env bash
# Restores a Redis AOF file for local development.
# Usage: restore-redis-aof.sh <aof-file>
# The Redis container must be managed via the docker compose stack.
# This script stops the Redis service, copies the provided AOF
# into the persistent volume, and restarts the container.

set -euo pipefail

FILE=${1:?"Usage: restore-redis-aof.sh <aof-file>"}
DATA_DIR=${REDIS_DATA_DIR:-redis-data}
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
COMPOSE_FILES=(-f "$ROOT_DIR/docker/docker-compose.yml" -f "$ROOT_DIR/docker/docker-compose.override.yml")

# Stop the Redis container if running
if docker compose "${COMPOSE_FILES[@]}" ps --status running | grep -q "_redis_"; then
  docker compose "${COMPOSE_FILES[@]}" stop redis
fi

mkdir -p "$DATA_DIR"
cp "$FILE" "$DATA_DIR/appendonly.aof"

# Start Redis again
docker compose "${COMPOSE_FILES[@]}" up -d redis

echo "Redis AOF restored from $FILE"
