#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
COMPOSE_FILES=(-f "$ROOT_DIR/docker/docker-compose.yml" -f "$ROOT_DIR/docker/docker-compose.override.yml")
TCP_SMOKE_SCRIPT="$ROOT_DIR/services/tcp-proxy-service/telnet-login-look-smoke.sh"
WS_SMOKE_SCRIPT="$ROOT_DIR/services/game-session-service/websocket-login-look-smoke.sh"
HEALTH_SCRIPT="$ROOT_DIR/dev-tools/verify-compose-health.sh"
BUILD_JARS_SCRIPT="$ROOT_DIR/dev-tools/build-compose-service-jars.sh"
ENSURE_CERTS_SCRIPT="$ROOT_DIR/dev-tools/certs/ensure-dev-certs.sh"
ENSURE_ENV_SCRIPT="$ROOT_DIR/dev-tools/ensure-local-compose-env.sh"

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

echo "Restart-state proof: preserve local compose volumes, restart the stack, then run WebSocket/Telnet LOGIN -> PLAY -> LOOK baseline proofs."
echo "Local volumes are left intact."

bash "$ENSURE_ENV_SCRIPT"
bash "$ENSURE_CERTS_SCRIPT"
"$BUILD_JARS_SCRIPT"
docker compose "${COMPOSE_FILES[@]}" up -d --build --remove-orphans
docker compose "${COMPOSE_FILES[@]}" restart

"$HEALTH_SCRIPT"
# Both transport legs are baseline-only; mutation parity requires independent
# transport identities/state and is rejected by this wrapper above.
bash "$WS_SMOKE_SCRIPT"
bash "$TCP_SMOKE_SCRIPT"
