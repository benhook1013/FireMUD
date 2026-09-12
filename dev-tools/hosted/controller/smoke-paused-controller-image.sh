#!/usr/bin/env bash
set -euo pipefail

if (($# != 1)) || [[ -z "$1" ]]; then
  echo "usage: $0 <controller-image>" >&2
  exit 2
fi

for required_command in docker jq; do
  if ! command -v "$required_command" >/dev/null 2>&1; then
    echo "Missing required command: $required_command" >&2
    exit 1
  fi
done

readonly controller_image="$1"
readonly timeout_seconds="${FIREMUD_CONTROLLER_SMOKE_TIMEOUT_SECONDS:-300}"
if [[ ! "$timeout_seconds" =~ ^[1-9][0-9]{0,2}$ ]] || ((timeout_seconds > 300)); then
  echo "FIREMUD_CONTROLLER_SMOKE_TIMEOUT_SECONDS must be an integer from 1 to 300." >&2
  exit 2
fi

readonly container_name="hosted-identity-controller-smoke-${GITHUB_RUN_ID:-local}-${GITHUB_RUN_ATTEMPT:-0}-$$-${RANDOM}"

cleanup() {
  docker rm --force "$container_name" >/dev/null 2>&1 || true
}

show_container_logs() {
  docker logs "$container_name" >&2 || true
}

trap cleanup EXIT

docker run --detach \
  --name "$container_name" \
  --env FIREMUD_HOSTED_IDENTITY_ACTIVATION_MODE=paused \
  "$controller_image" \
  >/dev/null

healthy=false
deadline=$((SECONDS + timeout_seconds))
while ((SECONDS < deadline)); do
  if [[ "$(docker inspect --format '{{.State.Running}}' "$container_name")" != true ]]; then
    show_container_logs
    echo "Hosted identity controller exited before its paused-mode health endpoint became available." >&2
    exit 1
  fi

  request_timeout=$((deadline - SECONDS))
  ((request_timeout > 0)) || break
  ((request_timeout <= 5)) || request_timeout=5
  if health="$(docker exec "$container_name" \
    curl --fail --silent --show-error --connect-timeout 2 --max-time "$request_timeout" \
      http://127.0.0.1:8081/actuator/health/liveness 2>/dev/null)" \
    && jq -e '.status == "UP"' <<<"$health" >/dev/null 2>&1; then
    healthy=true
    break
  fi

  ((SECONDS < deadline)) && sleep 1
done

if [[ "$healthy" != true ]]; then
  show_container_logs
  echo "Hosted identity controller paused-mode health check timed out." >&2
  exit 1
fi

docker inspect --format '{{.Image}}' "$container_name"
