#!/usr/bin/env bash
# Direct WebSocket -> Game Session smoke test: WORLDS + LOGIN + PLAY + LOOK after readiness.
set -euo pipefail

FIREMUD_REPO_ROOT=${FIREMUD_REPO_ROOT:-$(cd "$(dirname "$0")/../.." && pwd)}
# shellcheck disable=SC1091 # The repository root is resolved at runtime.
source "$FIREMUD_REPO_ROOT/dev-tools/smoke/demo-smoke-defaults.sh"
# shellcheck disable=SC1091 # The repository root is resolved at runtime.
source "$FIREMUD_REPO_ROOT/dev-tools/smoke/run-owned-compose.sh"

requested_websocket_url="${SMOKE_GAME_SESSION_WS_URL:-}"
SMOKE_GAME_SESSION_WS_URL="${requested_websocket_url:-ws://localhost:8086/ws/game}"
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
require_smoke_mutation_boundary
if [[ "$SMOKE_MUTATION_EXTENSION" == "true" ]]; then
  if [[ "$SMOKE_GAME_SESSION_WS_URL" != "ws://localhost:8086/ws/game" ]]; then
    echo "Mutation mode requires SMOKE_GAME_SESSION_WS_URL=ws://localhost:8086/ws/game; refusing endpoint override." >&2
    exit 1
  fi
  require_run_owned_compose_service game-session-service 8080 8086
fi
SMOKE_WS_FIXTURE_MODE=false
if [[ "${FIREMUD_SMOKE_INITIAL_ADMISSION_FIXTURE_ENABLED:-false}" == true \
    && "${FIREMUD_SMOKE_ADMISSION_QUALIFICATION:-baseline}" == baseline \
    && -n "${FIREMUD_SMOKE_FIXTURE_WEBSOCKET_CERTIFICATE_SHA256:-}" \
    && "$SMOKE_MUTATION_EXTENSION" != true ]]; then
  fixture_url=wss://127.0.0.1:18444/ws/game/run-owned-fixture
  if [[ "${requested_websocket_url:-$fixture_url}" != "$fixture_url" ]]; then
    echo "Canonical WebSocket smoke requires the run-owned loopback listener." >&2
    exit 1
  fi
  require_run_owned_compose_service game-session-service 8444 18444 127.0.0.1
  SMOKE_WS_FIXTURE_MODE=true
  SMOKE_GAME_SESSION_WS_URL="$fixture_url"
fi
export SMOKE_WS_FIXTURE_MODE SMOKE_GAME_SESSION_WS_URL
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
elif [[ "$SMOKE_WS_FIXTURE_MODE" == true ]]; then
  echo "Running canonical LOGIN, WORLDS, REALMS, JOIN, PLAY and LOOK over the claimed WebSocket fixture"
else
  echo "Running direct WebSocket WORLDS + LOGIN + fail-closed PLAY baseline against ${SMOKE_GAME_SESSION_WS_URL}"
fi
echo "Using login credentials (email and password redacted)"
echo "Using session='${SMOKE_SESSION_ID}' tenant='${SMOKE_TENANT_ID}'"

"$PYTHON" - <<'PYTHON'
import os
import shlex
import ssl
import sys
from pathlib import Path

repo_root = Path(os.environ["FIREMUD_REPO_ROOT"])
sys.path.insert(0, str(repo_root / "dev-tools" / "smoke"))

from smoke_common import (
    PlayEntitlementUnavailable,
    canonical_fixture_play_steps,
    gameplay_item_container_equipment_steps,
    websocket_fail_closed_play_steps,
    run_websocket_smoke_session,
    verify_smoke_account,
    wait_for_account_schema,
    wait_for_http_readiness,
)

try:
    import websocket
except ImportError as exc:
    venv_directory = repo_root / ".venv-smoke"
    venv_python = venv_directory / "bin" / "python"
    requirements_file = repo_root / "config" / "python" / "smoke-requirements.txt"
    raise SystemExit(
        "The python 'websocket-client' package is required. "
        "Create the canonical virtual environment and install it with: "
        f"python3 -m venv --clear {shlex.quote(str(venv_directory))} && "
        f"{shlex.quote(str(venv_python))} -m pip install "
        "--disable-pip-version-check --require-hashes "
        f"-r {shlex.quote(str(requirements_file))}\n"
        "Activate that environment before rerunning this smoke test."
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
world = os.environ.get("SMOKE_WORLD") or os.environ["DEMO_SMOKE_WORLD"]
fixture_mode = os.environ["SMOKE_WS_FIXTURE_MODE"] == "true"
connection_options = {}
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
elif fixture_mode:
    steps = canonical_fixture_play_steps(
        login_email, password, world, worlds_expect, login_expect, play_expect, look_expect,
    )
    fixture_root = Path(os.environ["FIREMUD_COMPOSE_GRPC_MTLS_CERT_ROOT"])
    connection_options["sslopt"] = {
        "ca_certs": str(fixture_root / "authority" / "ca.crt"),
        "certfile": str(fixture_root / "websocket-client" / "client.crt"),
        "keyfile": str(fixture_root / "websocket-client" / "client.key"),
        "cert_reqs": ssl.CERT_REQUIRED,
        "server_hostname": "game-session-service",
        "check_hostname": True,
    }
else:
    steps = websocket_fail_closed_play_steps(
        login_email,
        password,
        world,
        worlds_expect,
        login_expect,
    )
if not fixture_mode:
    steps += [("LOGOUT", ["OK LOGOUT", "Logged out."], "LOGOUT")]
    connection_options["header"] = [
        f"X-Game-Instance-Id: {session_id}",
        f"X-Tenant-Id: {tenant_id}",
    ]
try:
    run_websocket_smoke_session(
        lambda: websocket.create_connection(
            websocket_url,
            timeout=timeout_seconds,
            **connection_options,
        ),
        steps,
        timeout_seconds,
        retriable_exceptions=(OSError, websocket.WebSocketException),
        session_label=f"WebSocket session {websocket_url}",
        discovery_selector=world,
        step_results=[],
    )
except PlayEntitlementUnavailable as exc:
    print(f"Known direct backend PLAY failure after LOGIN: {exc}", file=sys.stderr)
    raise SystemExit(86) from exc

PYTHON
