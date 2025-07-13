#!/usr/bin/env bash
# Restores a Redis AOF file for local development.
# Usage: restore-redis-aof.sh <aof-file>
# The Redis container must be managed via docker-compose.
# This script stops the Redis service, copies the provided AOF
# into the persistent volume, and restarts the container.

set -euo pipefail

FILE=${1:?"Usage: restore-redis-aof.sh <aof-file>"}
DATA_DIR=${REDIS_DATA_DIR:-redis-data}

# Stop the Redis container if running
if docker compose ps --status running | grep -q "_redis_"; then
  docker compose stop redis
fi

mkdir -p "$DATA_DIR"
cp "$FILE" "$DATA_DIR/appendonly.aof"

# Start Redis again
docker compose up -d redis

echo "Redis AOF restored from $FILE"
