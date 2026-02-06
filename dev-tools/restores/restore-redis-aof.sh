#!/usr/bin/env bash
# Restores a Redis AOF file for local development.
# Usage: restore-redis-aof.sh <aof-file>
# The Coordination Redis container must be managed via the docker compose stack.
# This script stops the container, replaces the Coordination Redis data volume
# contents with the provided AOF file, and restarts the container.

set -euo pipefail

FILE=${1:?"Usage: restore-redis-aof.sh <aof-file>"}
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
COMPOSE_FILES=(-f "$ROOT_DIR/docker/docker-compose.yml" -f "$ROOT_DIR/docker/docker-compose.override.yml")
SERVICE=${REDIS_COORD_SERVICE:-redis-coord}

# Ensure the container exists (so we can locate its named volume)
docker compose "${COMPOSE_FILES[@]}" up -d --no-deps "$SERVICE"

# Stop Coordination Redis before replacing its persisted data
docker compose "${COMPOSE_FILES[@]}" stop "$SERVICE"

CONTAINER_ID="$(docker compose "${COMPOSE_FILES[@]}" ps -q "$SERVICE")"
if [[ -z "$CONTAINER_ID" ]]; then
  echo "Unable to locate docker container for service: $SERVICE" >&2
  exit 1
fi

VOLUME_NAME="$(docker inspect -f '{{range .Mounts}}{{if eq .Destination "/data"}}{{.Name}}{{end}}{{end}}' "$CONTAINER_ID")"
if [[ -z "$VOLUME_NAME" ]]; then
  echo "Unable to locate /data volume for container: $CONTAINER_ID" >&2
  exit 1
fi

SRC_DIR="$(cd "$(dirname "$FILE")" && pwd)"
SRC_FILE_NAME="$(basename "$FILE")"

# Redis 7 may use multi-part AOF files + a manifest. Replace the entire data dir
# so Redis starts from exactly the provided AOF.
docker run --rm \
  -v "$VOLUME_NAME:/mnt/redis" \
  -v "$SRC_DIR:/mnt/src:ro" \
  busybox \
  sh -c "rm -rf /mnt/redis/* && cp \"/mnt/src/$SRC_FILE_NAME\" /mnt/redis/appendonly.aof"

# Start Coordination Redis again
docker compose "${COMPOSE_FILES[@]}" up -d --no-deps "$SERVICE"

echo "Coordination Redis AOF restored from $FILE"
