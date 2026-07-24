#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
ROOT_ENV_FILE="$ROOT_DIR/.env"
DOCKER_ENV_FILE="$ROOT_DIR/docker/.env"
ROOT_ENV_BACKUP=""
DOCKER_ENV_BACKUP=""
ERR_FILE="$(mktemp)"
OUT_FILE="$(mktemp)"

cleanup() {
  rm -f "$ERR_FILE" "$OUT_FILE"
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
FIREMUD_AUTH_JWT_SECRET=contract-override-jwt-secret
EOF

SMOKE_IMAGE_TAG=contract-smoke-tag \
SMOKE_COMPOSE_CONFIG_ONLY=true \
bash "$ROOT_DIR/dev-tools/verify-smoke-images.sh" >"$OUT_FILE" 2>"$ERR_FILE"

if grep -q 'variable is not set' "$ERR_FILE"; then
  echo "verify-smoke-images left compose variables unresolved:" >&2
  cat "$ERR_FILE" >&2
  exit 1
fi

if grep -q 'Defaulting to a blank string' "$ERR_FILE"; then
  echo "verify-smoke-images allowed blank-string compose defaults:" >&2
  cat "$ERR_FILE" >&2
  exit 1
fi

SMOKE_IMAGE_TAG=contract-smoke-tag \
SMOKE_IMAGE_LOCAL_ONLY=true \
SMOKE_COMPOSE_CONFIG_ONLY=true \
bash "$ROOT_DIR/dev-tools/verify-smoke-images.sh" >"$OUT_FILE" 2>"$ERR_FILE"

if grep -q 'Defaulting to a blank string' "$ERR_FILE"; then
  echo "local-only smoke configuration allowed blank-string compose defaults:" >&2
  cat "$ERR_FILE" >&2
  exit 1
fi

if grep -q -- '--pull never' "$ROOT_DIR/dev-tools/verify-smoke-images.sh"; then
  echo "local-only smoke must not suppress pulls for external dependency images" >&2
  exit 1
fi

# shellcheck disable=SC2016 # Match the literal Compose interpolation contract.
if [[ "$(grep -Fc 'pull_policy: ${SMOKE_IMAGE_PULL_POLICY:-always}' "$ROOT_DIR/docker/docker-compose.smoke-images.override.yml")" -ne 11 ]]; then
  echo "every FireMUD smoke image must use the configurable per-service pull policy" >&2
  exit 1
fi

echo "smoke image env contract checks passed"
