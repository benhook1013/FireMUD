#!/usr/bin/env bash
# Direct WebSocket -> Game Session smoke test: WORLDS + LOGIN + PLAY + item/container/equipment loop after readiness.
set -euo pipefail

FIREMUD_REPO_ROOT=${FIREMUD_REPO_ROOT:-$(cd "$(dirname "$0")/../.." && pwd)}
source "$FIREMUD_REPO_ROOT/dev-tools/smoke/demo-smoke-defaults.sh"

SMOKE_GAME_SESSION_WS_URL=${SMOKE_GAME_SESSION_WS_URL:-ws://localhost:8086/ws/game}
SMOKE_USERNAME=${SMOKE_USERNAME:-$DEMO_SMOKE_USERNAME}
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
export FIREMUD_REPO_ROOT

if command -v python3 >/dev/null 2>&1; then
  PYTHON=python3
elif command -v python >/dev/null 2>&1; then
  PYTHON=python
else
  echo "Python 3 or python is required to run this smoke test" >&2
  exit 1
fi

echo "Running direct WebSocket WORLDS + LOGIN + PLAY + item/container/equipment smoke test against ${SMOKE_GAME_SESSION_WS_URL}"
echo "Using username='${SMOKE_USERNAME}' (password redacted)"
echo "Using session='${SMOKE_SESSION_ID}' tenant='${SMOKE_TENANT_ID}'"

"$PYTHON" - <<'PYTHON'
import os
import sys
from pathlib import Path

repo_root = Path(os.environ["FIREMUD_REPO_ROOT"])
sys.path.insert(0, str(repo_root / "dev-tools" / "smoke"))

from smoke_common import (
    gameplay_item_container_equipment_steps,
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
username = os.environ.get("SMOKE_USERNAME", os.environ["DEMO_SMOKE_USERNAME"])
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
verify_smoke_account(account_api_base, username, password, timeout_seconds)
steps = gameplay_item_container_equipment_steps(
    username,
    password,
    worlds_expect,
    login_expect,
    play_expect,
    look_expect,
    os.environ.get("SMOKE_WORLD", os.environ["DEMO_SMOKE_WORLD"]),
    look_timeout_seconds,
) + [("LOGOUT", ["OK LOGOUT", "Logged out."], "LOGOUT")]
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
