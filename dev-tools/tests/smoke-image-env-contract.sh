#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
ROOT_ENV_FILE="$ROOT_DIR/.env"
DOCKER_ENV_FILE="$ROOT_DIR/docker/.env"
ROOT_ENV_BACKUP=""
DOCKER_ENV_BACKUP=""
ERR_FILE="$(mktemp)"
OUT_FILE="$(mktemp)"
TEST_OWNERSHIP_DIR="$(mktemp -d "${TMPDIR:-/tmp}/firemud-smoke-image-env-contract.XXXXXX")"
TEST_RUN_ID="smoke-image-env-contract-$$"
chmod 700 "$TEST_OWNERSHIP_DIR"
export TEST_OWNERSHIP_DIR

docker() {
  case "$1 $2" in
    "ps -a"|"network ls"|"volume ls") return 0 ;;
    compose\ *)
      local expected_receipt_directory
      expected_receipt_directory="$FIREMUD_SMOKE_OWNERSHIP_DIR_RESOLVED/$FIREMUD_SMOKE_PROJECT_KEY.authored-world-source"
      if [[ "${FIREMUD_SMOKE_AUTHORED_WORLD_SOURCE_RECEIPT_DIR_HOST_PATH:-}" != "$expected_receipt_directory" ||
        ! -d "$expected_receipt_directory" ||
        "${FIREMUD_SMOKE_INITIAL_ADMISSION_FIXTURE_ENABLED:-}" != true ||
        ! -f "${FIREMUD_SMOKE_INITIAL_ADMISSION_CAPABILITY_HOST_PATH:-}" ]]; then
        echo "Compose ran before the claimed initial-admission receipt directory was prepared." >&2
        return 1
      fi
      command docker "$@"
      ;;
    *)
      echo "Unexpected Docker command in smoke image env contract: $*" >&2
      return 1
      ;;
  esac
}
export -f docker

cleanup() {
  rm -f "$ERR_FILE" "$OUT_FILE"
  rm -rf -- "$TEST_OWNERSHIP_DIR"
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

GITHUB_ACTIONS=true \
GITHUB_RUN_ID="$TEST_RUN_ID" \
GITHUB_RUN_ATTEMPT=1 \
GITHUB_JOB=smoke-image-env-contract \
COMPOSE_PROJECT_NAME="smoke-full-$TEST_RUN_ID-1" \
FIREMUD_SMOKE_TEST_MODE=1 \
FIREMUD_SMOKE_OWNERSHIP_DIR="$TEST_OWNERSHIP_DIR" \
SMOKE_IMAGE_TAG=contract-smoke-tag \
SMOKE_COMPOSE_CONFIG_ONLY=true \
bash "$ROOT_DIR/dev-tools/verify-smoke-images.sh" >"$OUT_FILE" 2>"$ERR_FILE"

if grep -q 'variable is not set' "$ERR_FILE"; then
  echo "verify-smoke-images left compose variables unresolved:" >&2
  cat "$ERR_FILE" >&2
  exit 1
fi

if grep -q 'Defaulting to a blank string' "$ERR_FILE"; then
  echo "verify-smoke-images detected and rejected blank-string Compose defaults:" >&2
  cat "$ERR_FILE" >&2
  exit 1
fi

GITHUB_ACTIONS=true \
GITHUB_RUN_ID="$TEST_RUN_ID" \
GITHUB_RUN_ATTEMPT=1 \
GITHUB_JOB=smoke-image-env-contract \
COMPOSE_PROJECT_NAME="smoke-full-$TEST_RUN_ID-1" \
FIREMUD_SMOKE_TEST_MODE=1 \
FIREMUD_SMOKE_OWNERSHIP_DIR="$TEST_OWNERSHIP_DIR" \
SMOKE_IMAGE_TAG=contract-smoke-tag \
SMOKE_IMAGE_LOCAL_ONLY=true \
SMOKE_COMPOSE_CONFIG_ONLY=true \
bash "$ROOT_DIR/dev-tools/verify-smoke-images.sh" >"$OUT_FILE" 2>"$ERR_FILE"

if grep -q 'Defaulting to a blank string' "$ERR_FILE"; then
  echo "local-only smoke configuration detected and rejected blank-string Compose defaults:" >&2
  cat "$ERR_FILE" >&2
  exit 1
fi

if SMOKE_IMAGE_TAG=contract-smoke-tag \
  SMOKE_MINIO_LOCAL_ONLY=true \
  SMOKE_COMPOSE_CONFIG_ONLY=true \
  bash "$ROOT_DIR/dev-tools/verify-smoke-images.sh" >"$OUT_FILE" 2>"$ERR_FILE"; then
  echo "PR-local MinIO smoke accepted missing built image references and IDs" >&2
  exit 1
fi

if ! grep -Fq 'SMOKE_MINIO_LOCAL_ONLY=true requires a unique local server image tag.' "$ERR_FILE"; then
  echo "PR-local MinIO smoke did not reject the missing server image tag with the expected error" >&2
  cat "$ERR_FILE" >&2
  exit 1
fi

if ! GITHUB_ACTIONS=true \
  GITHUB_RUN_ID="$TEST_RUN_ID" \
  GITHUB_RUN_ATTEMPT=1 \
  GITHUB_JOB=smoke-image-env-contract \
  COMPOSE_PROJECT_NAME="smoke-full-$TEST_RUN_ID-1" \
  FIREMUD_SMOKE_TEST_MODE=1 \
  FIREMUD_SMOKE_OWNERSHIP_DIR="$TEST_OWNERSHIP_DIR" \
  SMOKE_IMAGE_TAG=contract-smoke-tag \
  SMOKE_MINIO_LOCAL_ONLY=true \
  SMOKE_MINIO_SERVER_IMAGE=firemud-minio-server-smoke:contract \
  SMOKE_MINIO_CLIENT_IMAGE=firemud-minio-client-smoke:contract \
  SMOKE_MINIO_SERVER_IMAGE_ID=sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa \
  SMOKE_MINIO_CLIENT_IMAGE_ID=sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb \
  SMOKE_COMPOSE_CONFIG_ONLY=true \
  bash "$ROOT_DIR/dev-tools/verify-smoke-images.sh" >"$OUT_FILE" 2>"$ERR_FILE"; then
  echo "PR-local MinIO smoke positive path failed:" >&2
  cat "$OUT_FILE" >&2
  cat "$ERR_FILE" >&2
  exit 1
fi

if ! grep -q 'Verified PR-local MinIO Compose image tags and pull policies.' "$OUT_FILE"; then
  echo "PR-local MinIO smoke did not prove the rendered image tags and no-pull policy" >&2
  cat "$OUT_FILE" >&2
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
