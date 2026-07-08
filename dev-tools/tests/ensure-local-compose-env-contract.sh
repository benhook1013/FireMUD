#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
ROOT_ENV_FILE="$ROOT_DIR/.env"
DOCKER_ENV_FILE="$ROOT_DIR/docker/.env"
ROOT_ENV_BACKUP=""
DOCKER_ENV_BACKUP=""

cleanup() {
  if [[ -n "$ROOT_ENV_BACKUP" && -f "$ROOT_ENV_BACKUP" ]]; then
    mv "$ROOT_ENV_BACKUP" "$ROOT_ENV_FILE"
  else
    rm -f "$ROOT_ENV_FILE"
  fi
  if [[ -n "$DOCKER_ENV_BACKUP" && -f "$DOCKER_ENV_BACKUP" ]]; then
    mv "$DOCKER_ENV_BACKUP" "$DOCKER_ENV_FILE"
  else
    rm -f "$DOCKER_ENV_FILE"
  fi
}
trap cleanup EXIT

if [[ -f "$ROOT_ENV_FILE" ]]; then
  ROOT_ENV_BACKUP="$(mktemp)"
  cp "$ROOT_ENV_FILE" "$ROOT_ENV_BACKUP"
fi
if [[ -f "$DOCKER_ENV_FILE" ]]; then
  DOCKER_ENV_BACKUP="$(mktemp)"
  cp "$DOCKER_ENV_FILE" "$DOCKER_ENV_BACKUP"
fi

cat >"$ROOT_ENV_FILE" <<'EOF'
FIREMUD_AUTH_JWT_SECRET=existing-jwt-secret
EOF

bash "$ROOT_DIR/dev-tools/ensure-local-compose-env.sh" >/dev/null

grep -q '^FIREMUD_AUTH_JWT_SECRET=existing-jwt-secret$' "$ROOT_ENV_FILE"
grep -q '^FIREMUD_REDIS_HOST=redis-cache$' "$ROOT_ENV_FILE"
grep -q '^FIREMUD_REDIS_PORT=6379$' "$ROOT_ENV_FILE"
grep -q '^FIREMUD_AUTH_JWT_SECRET=existing-jwt-secret$' "$DOCKER_ENV_FILE"
grep -q '^FIREMUD_REDIS_HOST=redis-cache$' "$DOCKER_ENV_FILE"
grep -q '^FIREMUD_REDIS_PORT=6379$' "$DOCKER_ENV_FILE"

echo "ensure local compose env contract checks passed"
