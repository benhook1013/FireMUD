#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DOCKER_DIR="$ROOT_DIR/docker"
ROOT_ENV_FILE="$ROOT_DIR/.env"
ROOT_ENV_SAMPLE="$ROOT_DIR/.env.sample"
DOCKER_ENV_FILE="$DOCKER_DIR/.env"
ROOT_ENV_BACKUP=""
COMPOSE_FILES=(
  -f "$DOCKER_DIR/docker-compose.yml"
  -f "$DOCKER_DIR/docker-compose.override.yml"
  -f "$DOCKER_DIR/docker-compose.smoke-images.override.yml"
)
COMPOSE_CONFIG_FILE=""
TCP_SMOKE_SCRIPT="$ROOT_DIR/services/tcp-proxy-service/telnet-login-look-smoke.sh"
WS_SMOKE_SCRIPT="$ROOT_DIR/services/game-session-service/websocket-login-look-smoke.sh"
HEALTH_CHECK_SCRIPT="$ROOT_DIR/dev-tools/verify-compose-health.sh"
ENSURE_CERTS_SCRIPT="$ROOT_DIR/dev-tools/certs/ensure-dev-certs.sh"
COMPOSE_UP_ARGS=(up -d --remove-orphans)
SMOKE_COMPOSE_UP_ATTEMPTS="${SMOKE_COMPOSE_UP_ATTEMPTS:-3}"
SMOKE_COMPOSE_UP_RETRY_DELAY_SECONDS="${SMOKE_COMPOSE_UP_RETRY_DELAY_SECONDS:-5}"
# shellcheck disable=SC1091 # The repository root is resolved at runtime.
source "$ROOT_DIR/dev-tools/smoke/run-owned-compose.sh"

export TERM="${TERM:-dumb}"
export COMPOSE_PROGRESS="${COMPOSE_PROGRESS:-plain}"

SMOKE_MUTATION_EXTENSION="${SMOKE_MUTATION_EXTENSION:-false}"
case "$SMOKE_MUTATION_EXTENSION" in
  false|0)
    export SMOKE_MUTATION_EXTENSION=false
    ;;
  true|1)
    echo "SMOKE_MUTATION_EXTENSION is not supported by the two-transport wrapper: independent transport identities/state are not proven; run each transport smoke separately." >&2
    exit 1
    ;;
  *)
    echo "SMOKE_MUTATION_EXTENSION must be boolean true/false (or 1/0); refusing to run." >&2
    exit 1
    ;;
esac

if [[ -z "${SMOKE_IMAGE_TAG:-}" ]]; then
  echo "SMOKE_IMAGE_TAG is required" >&2
  exit 1
fi

if [[ "${SMOKE_IMAGE_LOCAL_ONLY:-false}" == "true" ]]; then
  export SMOKE_IMAGE_PULL_POLICY=never
fi

SMOKE_MINIO_LOCAL_ONLY="${SMOKE_MINIO_LOCAL_ONLY:-false}"
case "$SMOKE_MINIO_LOCAL_ONLY" in
  true)
    [[ "${SMOKE_MINIO_SERVER_IMAGE:-}" =~ ^firemud-minio-server-smoke:[A-Za-z0-9_.-]+$ ]] || {
      echo "SMOKE_MINIO_LOCAL_ONLY=true requires a unique local server image tag." >&2
      exit 1
    }
    [[ "${SMOKE_MINIO_CLIENT_IMAGE:-}" =~ ^firemud-minio-client-smoke:[A-Za-z0-9_.-]+$ ]] || {
      echo "SMOKE_MINIO_LOCAL_ONLY=true requires a unique local client image tag." >&2
      exit 1
    }
    for image_id in "${SMOKE_MINIO_SERVER_IMAGE_ID:-}" "${SMOKE_MINIO_CLIENT_IMAGE_ID:-}"; do
      if [[ ! "$image_id" =~ ^sha256:[a-f0-9]{64}$ ]]; then
        echo "SMOKE_MINIO_LOCAL_ONLY=true requires the built server and client sha256 image IDs." >&2
        exit 1
      fi
    done
    COMPOSE_FILES+=( -f "$DOCKER_DIR/docker-compose.pr-local-minio.override.yml" )
    ;;
  false)
    ;;
  *)
    echo "SMOKE_MINIO_LOCAL_ONLY must be boolean true/false; refusing to run." >&2
    exit 1
    ;;
esac

if [[ "$SMOKE_MINIO_LOCAL_ONLY" == "true" && "${SMOKE_COMPOSE_CONFIG_ONLY:-false}" != "true" ]]; then
  for image_ref_and_id in "$SMOKE_MINIO_SERVER_IMAGE|$SMOKE_MINIO_SERVER_IMAGE_ID" \
    "$SMOKE_MINIO_CLIENT_IMAGE|$SMOKE_MINIO_CLIENT_IMAGE_ID"; do
    image_ref="${image_ref_and_id%%|*}"
    image_id="${image_ref_and_id#*|}"
    local_image_id="$(docker image inspect --format '{{.Id}}' "$image_ref")" || {
      echo "Required source-built MinIO image $image_ref is not available locally." >&2
      exit 1
    }
    [[ "$local_image_id" == "$image_id" ]] || {
      echo "Local MinIO image $image_ref ID did not match the pinned-source build output." >&2
      exit 1
    }
    printf 'Verified source-built MinIO image ID: %s -> %s\n' "$image_ref" "$image_id"
  done
fi

cleanup() {
  if [[ -n "$COMPOSE_CONFIG_FILE" ]]; then
    rm -f "$COMPOSE_CONFIG_FILE"
  fi
  rm -f "$DOCKER_ENV_FILE"
  if [[ -n "$ROOT_ENV_BACKUP" && -f "$ROOT_ENV_BACKUP" ]]; then
    mv "$ROOT_ENV_BACKUP" "$ROOT_ENV_FILE"
  else
    rm -f "$ROOT_ENV_FILE"
  fi
}
trap cleanup EXIT

merge_env_vars() {
  local source_file="$1"
  local target_file="$2"
  [[ -f "$source_file" ]] || return 0
  while IFS= read -r line || [[ -n "$line" ]]; do
    [[ "$line" =~ ^[A-Za-z_][A-Za-z0-9_]*= ]] || continue
    local key="${line%%=*}"
    local value="${line#*=}"
    upsert_env_var "$target_file" "$key" "$value"
  done <"$source_file"
}
upsert_env_var() {
  local file="$1"
  local key="$2"
  local value="$3"
  local tmp
  tmp="$(mktemp)"
  if [[ -f "$file" ]]; then
    grep -v "^${key}=" "$file" >"$tmp" || true
  fi
  printf '%s=%s\n' "$key" "$value" >>"$tmp"
  mv "$tmp" "$file"
}

compose_up_with_retry() {
  local attempt=1
  local max_attempts="$SMOKE_COMPOSE_UP_ATTEMPTS"
  while true; do
    if docker compose "${COMPOSE_FILES[@]}" "${COMPOSE_UP_ARGS[@]}"; then
      return 0
    fi
    if (( attempt >= max_attempts )); then
      return 1
    fi
    echo "docker compose up failed on attempt ${attempt}/${max_attempts}; retrying after ${SMOKE_COMPOSE_UP_RETRY_DELAY_SECONDS}s" >&2
    claim_run_owned_compose_project
    docker compose "${COMPOSE_FILES[@]}" down -v --remove-orphans || true
    sleep "$SMOKE_COMPOSE_UP_RETRY_DELAY_SECONDS"
    attempt=$((attempt + 1))
  done
}

echo "Smoke image proof: destroy this run-owned compose project's state, resolve smoke-image tags via docker/.env, start the stack, then run WebSocket and Telnet LOGIN -> PLAY -> LOOK baseline proofs."
if [[ "${SMOKE_IMAGE_LOCAL_ONLY:-false}" == "true" ]]; then
  echo "Local-only mode enabled: compose will reuse matching local FireMUD images while pulling missing dependencies."
fi
if [[ "$SMOKE_MINIO_LOCAL_ONLY" == "true" ]]; then
  echo "PR-local MinIO mode enabled: compose will use unique local tags whose image IDs match the pinned-source build outputs, with pull_policy never."
fi

if [[ -f "$ROOT_ENV_FILE" ]]; then
  ROOT_ENV_BACKUP="$(mktemp)"
  cp "$ROOT_ENV_FILE" "$ROOT_ENV_BACKUP"
elif [[ -f "$ROOT_ENV_SAMPLE" ]]; then
  cp "$ROOT_ENV_SAMPLE" "$ROOT_ENV_FILE"
else
  echo ".env.sample is missing; cannot seed local compose defaults for smoke images." >&2
  exit 1
fi

if [[ -f "$ROOT_ENV_SAMPLE" ]]; then
  cp "$ROOT_ENV_SAMPLE" "$ROOT_ENV_FILE"
elif [[ -n "$ROOT_ENV_BACKUP" && -f "$ROOT_ENV_BACKUP" ]]; then
  cp "$ROOT_ENV_BACKUP" "$ROOT_ENV_FILE"
else
  echo ".env.sample is missing; cannot seed local compose defaults for smoke images." >&2
  exit 1
fi

if [[ -n "$ROOT_ENV_BACKUP" && -f "$ROOT_ENV_BACKUP" ]]; then
  merge_env_vars "$ROOT_ENV_BACKUP" "$ROOT_ENV_FILE"
fi

cp "$ROOT_ENV_FILE" "$DOCKER_ENV_FILE"
upsert_env_var "$ROOT_ENV_FILE" "SMOKE_IMAGE_TAG" "$SMOKE_IMAGE_TAG"
upsert_env_var "$DOCKER_ENV_FILE" "SMOKE_IMAGE_TAG" "$SMOKE_IMAGE_TAG"

if [[ "$SMOKE_MINIO_LOCAL_ONLY" == "true" ]]; then
  COMPOSE_CONFIG_FILE="$(mktemp)"
  docker compose "${COMPOSE_FILES[@]}" config --format json >"$COMPOSE_CONFIG_FILE"
  python3 - "$COMPOSE_CONFIG_FILE" "$SMOKE_MINIO_SERVER_IMAGE" "$SMOKE_MINIO_CLIENT_IMAGE" <<'PY'
import json
import sys

config_path, server_image, client_image = sys.argv[1:]
with open(config_path, encoding="utf-8") as source:
    config = json.load(source)

services = config.get("services", {})
expected = {"minio": server_image, "minio-setup": client_image}
for service_name, image_ref in expected.items():
    service = services.get(service_name, {})
    if service.get("image") != image_ref:
        raise SystemExit(f"Compose {service_name} image did not match its unique local tag")
    if service.get("pull_policy") != "never":
        raise SystemExit(f"Compose {service_name} must use pull_policy: never")
print("Verified PR-local MinIO Compose image tags and pull policies.")
PY
else
  docker compose "${COMPOSE_FILES[@]}" config >/dev/null
fi
if [[ "${SMOKE_COMPOSE_CONFIG_ONLY:-false}" == "true" ]]; then
  exit 0
fi
claim_run_owned_compose_project
docker compose "${COMPOSE_FILES[@]}" down -v --remove-orphans
bash "$ENSURE_CERTS_SCRIPT"
compose_up_with_retry
bash "$HEALTH_CHECK_SCRIPT" "${COMPOSE_FILES[@]}"

# Both transport legs are baseline-only; mutation parity requires independent
# transport identities/state and is rejected by this wrapper above.
bash "$WS_SMOKE_SCRIPT"
bash "$TCP_SMOKE_SCRIPT"
