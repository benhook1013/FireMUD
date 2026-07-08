#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ROOT_ENV_FILE="$ROOT_DIR/.env"
ROOT_ENV_SAMPLE="$ROOT_DIR/.env.sample"
DOCKER_ENV_FILE="$ROOT_DIR/docker/.env"

if [[ ! -f "$ROOT_ENV_FILE" ]]; then
  if [[ ! -f "$ROOT_ENV_SAMPLE" ]]; then
    echo ".env is missing and .env.sample was not found." >&2
    exit 1
  fi
  cp "$ROOT_ENV_SAMPLE" "$ROOT_ENV_FILE"
  echo "Seeded local .env from .env.sample" >&2
fi

cp "$ROOT_ENV_FILE" "$DOCKER_ENV_FILE"
echo "Synchronized docker/.env from repository-root .env" >&2
