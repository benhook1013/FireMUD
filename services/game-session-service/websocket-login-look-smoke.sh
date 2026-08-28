#!/usr/bin/env bash
# Direct WebSocket -> Game Session smoke test: WORLDS + LOGIN + PLAY + LOOK after readiness.
set -euo pipefail

FIREMUD_REPO_ROOT=${FIREMUD_REPO_ROOT:-$(cd "$(dirname "$0")/../.." && pwd)}
# shellcheck disable=SC1091 # The repository root is resolved at runtime.
source "$FIREMUD_REPO_ROOT/dev-tools/smoke/demo-smoke-defaults.sh"

SMOKE_GAME_SESSION_WS_URL=${SMOKE_GAME_SESSION_WS_URL:-ws://localhost:8086/ws/game}
SMOKE_LOGIN_EMAIL=${SMOKE_LOGIN_EMAIL:-$DEMO_SMOKE_EMAIL}
SMOKE_PASSWORD=${SMOKE_PASSWORD:-$DEMO_SMOKE_PASSWORD}
SMOKE_SESSION_ID=${SMOKE_SESSION_ID:-1}
SMOKE_TENANT_ID=${SMOKE_TENANT_ID:-1}
SMOKE_ACCOUNT_API_BASE=${SMOKE_ACCOUNT_API_BASE:-http://localhost:8081}
SMOKE_GAME_LOGIC_API_BASE=${SMOKE_GAME_LOGIC_API_BASE:-http://localhost:8085}
SMOKE_GAME_SESSION_API_BASE=${SMOKE_GAME_SESSION_API_BASE:-http://localhost:8086}
SMOKE_WORLDS_EXPECT=${SMOKE_WORLDS_EXPECT:-"OK WORLDS"}
SMOKE_LOGIN_EXPECT=${SMOKE_LOGIN_EXPECT:-"OK LOGIN"}
SMOKE_PLAY_EXPECT=${SMOKE_PLAY_EXPECT:-"OK PLAY"}
SMOKE_LOOK_EXPECT=${SMOKE_LOOK_EXPECT:-"OK LOOK"}
SMOKE_TIMEOUT_SECONDS=${SMOKE_TIMEOUT_SECONDS:-10}
SMOKE_LOOK_TIMEOUT_SECONDS=${SMOKE_LOOK_TIMEOUT_SECONDS:-60}
SMOKE_MUTATION_EXTENSION=${SMOKE_MUTATION_EXTENSION:-false}
SMOKE_MUTATION_BOUNDARY=${SMOKE_MUTATION_BOUNDARY:-}
case "$SMOKE_MUTATION_EXTENSION" in
  false|0)
    SMOKE_MUTATION_EXTENSION=false
    ;;
  true|1)
    SMOKE_MUTATION_EXTENSION=true
    case "$SMOKE_MUTATION_BOUNDARY" in
      run-owned-compose)
        if [[ ! "${COMPOSE_PROJECT_NAME:-}" =~ ^(firemud-smoke-[a-z0-9][a-z0-9-]*|smoke-full-[0-9]+-[0-9]+)$ ]]; then
          echo "Mutation extension requires COMPOSE_PROJECT_NAME matching firemud-smoke-<unique-run-id> or smoke-full-<run-id>-<attempt>; refusing shared/default state." >&2
          exit 1
        fi
        ;;
      restricted-synthetic|synthetic-identity)
        echo "SMOKE_MUTATION_BOUNDARY=$SMOKE_MUTATION_BOUNDARY is unavailable: no authoritative synthetic identity/isolation verifier exists." >&2
        exit 1
        ;;
      *)
        echo "Mutation extension requires SMOKE_MUTATION_BOUNDARY=run-owned-compose; refusing unverified state." >&2
        exit 1
        ;;
    esac
    ;;
  *)
    echo "SMOKE_MUTATION_EXTENSION must be boolean true/false (or 1/0); refusing to run." >&2
    exit 1
    ;;
esac
export SMOKE_MUTATION_EXTENSION
export SMOKE_MUTATION_BOUNDARY
export FIREMUD_REPO_ROOT

if command -v python3 >/dev/null 2>&1; then
  PYTHON=python3
elif command -v python >/dev/null 2>&1; then
  PYTHON=python
else
  echo "Python 3 or python is required to run this smoke test" >&2
  exit 1
fi

if [[ "$SMOKE_MUTATION_EXTENSION" == "true" ]]; then
  echo "Running direct WebSocket baseline plus explicit item/container/equipment mutation extension against ${SMOKE_GAME_SESSION_WS_URL}"
else
  echo "Running direct WebSocket WORLDS + LOGIN + PLAY + LOOK baseline against ${SMOKE_GAME_SESSION_WS_URL}"
fi
echo "Using login credentials (email and password redacted)"
echo "Using session='${SMOKE_SESSION_ID}' tenant='${SMOKE_TENANT_ID}'"

"$PYTHON" - <<'PYTHON'
import os
import sys
from pathlib import Path

repo_root = Path(os.environ["FIREMUD_REPO_ROOT"])
sys.path.insert(0, str(repo_root / "dev-tools" / "smoke"))

from smoke_common import (
    gameplay_item_container_equipment_steps,
    login_play_look_steps,
    run_websocket_smoke_session,
    verify_smoke_account,
    wait_for_account_schema,
    wait_for_http_readiness,
)

try:
    import websocket
except ImportError as exc:
    raise SystemExit(
        "The python 'websocket-client' package is required. "
        "Install it with 'python3 -m pip install websocket-client'."
    ) from exc

websocket_url = os.environ.get("SMOKE_GAME_SESSION_WS_URL", "ws://localhost:8086/ws/game")
login_email = os.environ.get("SMOKE_LOGIN_EMAIL", os.environ["DEMO_SMOKE_EMAIL"])
password = os.environ.get("SMOKE_PASSWORD", os.environ["DEMO_SMOKE_PASSWORD"])
session_id = os.environ.get("SMOKE_SESSION_ID", "1")
tenant_id = os.environ.get("SMOKE_TENANT_ID", "1")
account_api_base = os.environ.get("SMOKE_ACCOUNT_API_BASE", "http://localhost:8081")
game_logic_api_base = os.environ.get("SMOKE_GAME_LOGIC_API_BASE", "http://localhost:8085")
game_session_api_base = os.environ.get("SMOKE_GAME_SESSION_API_BASE", "http://localhost:8086")
worlds_expect = os.environ.get("SMOKE_WORLDS_EXPECT", "OK WORLDS")
login_expect = os.environ.get("SMOKE_LOGIN_EXPECT", "OK LOGIN")
play_expect = os.environ.get("SMOKE_PLAY_EXPECT", "OK PLAY")
look_expect = os.environ.get("SMOKE_LOOK_EXPECT", "OK LOOK")
timeout_seconds = int(os.environ.get("SMOKE_TIMEOUT_SECONDS", "10"))
look_timeout_seconds = int(os.environ.get("SMOKE_LOOK_TIMEOUT_SECONDS", "60"))
startup_wait_seconds = int(os.environ.get("SMOKE_STARTUP_WAIT_SECONDS", "90"))

wait_for_account_schema(startup_wait_seconds, timeout_seconds)
wait_for_http_readiness("account-service", account_api_base, startup_wait_seconds, timeout_seconds)
wait_for_http_readiness("game-logic-service", game_logic_api_base, startup_wait_seconds, timeout_seconds)
wait_for_http_readiness("game-session-service", game_session_api_base, startup_wait_seconds, timeout_seconds)
verify_smoke_account(account_api_base, login_email, password, timeout_seconds)
world = os.environ.get("SMOKE_WORLD", os.environ["DEMO_SMOKE_WORLD"])
if os.environ["SMOKE_MUTATION_EXTENSION"] == "true":
    steps = gameplay_item_container_equipment_steps(
        login_email,
        password,
        worlds_expect,
        login_expect,
        play_expect,
        look_expect,
        world,
        look_timeout_seconds,
    )
else:
    steps = login_play_look_steps(
        login_email,
        password,
        world,
        worlds_expect,
        login_expect,
        play_expect,
        look_expect,
        look_timeout=look_timeout_seconds,
    )
steps += [("LOGOUT", ["OK LOGOUT", "Logged out."], "LOGOUT")]
run_websocket_smoke_session(
    lambda: websocket.create_connection(
        websocket_url,
        timeout=timeout_seconds,
        header=[
            f"X-Game-Instance-Id: {session_id}",
            f"X-Tenant-Id: {tenant_id}",
        ],
    ),
    steps,
    timeout_seconds,
    retriable_exceptions=(OSError, websocket.WebSocketException),
    session_label=f"WebSocket session {websocket_url}",
)
PYTHON
