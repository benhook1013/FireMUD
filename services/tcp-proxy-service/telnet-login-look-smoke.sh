#!/usr/bin/env bash
# Telnet -> Gateway -> Game Session smoke test: WORLDS + LOGIN + PLAY + LOOK over TCP Proxy.
set -euo pipefail

FIREMUD_REPO_ROOT=${FIREMUD_REPO_ROOT:-$(cd "$(dirname "$0")/../.." && pwd)}
# shellcheck disable=SC1091 # The repository root is resolved at runtime.
source "$FIREMUD_REPO_ROOT/dev-tools/smoke/demo-smoke-defaults.sh"

TCP_PORT=${TCP_PROXY_PORT:-2323}
SMOKE_HOST=${SMOKE_TELNET_HOST:-localhost}
SMOKE_LOGIN_EMAIL=${SMOKE_LOGIN_EMAIL:-$DEMO_SMOKE_EMAIL}
SMOKE_PASSWORD=${SMOKE_PASSWORD:-$DEMO_SMOKE_PASSWORD}
SMOKE_SESSION_ID=${SMOKE_SESSION_ID:-1}
SMOKE_TENANT_ID=${SMOKE_TENANT_ID:-1}
SMOKE_ACCOUNT_API_BASE=${SMOKE_ACCOUNT_API_BASE:-http://localhost:8081}
SMOKE_GAME_LOGIC_API_BASE=${SMOKE_GAME_LOGIC_API_BASE:-http://localhost:8085}
SMOKE_GAME_SESSION_API_BASE=${SMOKE_GAME_SESSION_API_BASE:-http://localhost:8086}
SMOKE_GATEWAY_API_BASE=${SMOKE_GATEWAY_API_BASE:-http://localhost:8080}
SMOKE_TCP_PROXY_API_BASE=${SMOKE_TCP_PROXY_API_BASE:-http://localhost:8089}
SMOKE_WORLDS_EXPECT=${SMOKE_WORLDS_EXPECT:-"OK WORLDS"}
SMOKE_LOGIN_EXPECT=${SMOKE_LOGIN_EXPECT:-"OK LOGIN"}
SMOKE_PLAY_EXPECT=${SMOKE_PLAY_EXPECT:-"OK PLAY"}
SMOKE_LOOK_EXPECT=${SMOKE_LOOK_EXPECT:-"OK LOOK"}
SMOKE_STARTUP_EXPECT=${SMOKE_STARTUP_EXPECT:-"DISCONNECT startup_unavailable"}
SMOKE_TIMEOUT_SECONDS=${SMOKE_TIMEOUT_SECONDS:-10}
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
  echo "Running Telnet baseline plus explicit item/container/equipment mutation extension against ${SMOKE_HOST}:${TCP_PORT}"
else
  echo "Running Telnet WORLDS + LOGIN + PLAY + LOOK baseline against ${SMOKE_HOST}:${TCP_PORT}"
fi
echo "Using login credentials (email and password redacted)"
echo "Using session='${SMOKE_SESSION_ID}' tenant='${SMOKE_TENANT_ID}'"
echo "Using account API base '${SMOKE_ACCOUNT_API_BASE}' for smoke validation"

"$PYTHON" - <<'PYTHON'
import os
import socket
import sys
import time
from pathlib import Path

repo_root = Path(os.environ["FIREMUD_REPO_ROOT"])
sys.path.insert(0, str(repo_root / "dev-tools" / "smoke"))

from smoke_common import (
    gameplay_item_container_equipment_steps,
    http_readiness_up,
    login_play_look_steps,
    recv_until_socket,
    run_telnet_smoke_session,
    verify_smoke_account,
    wait_for_account_schema,
    wait_for_http_readiness,
)

host = os.environ.get("SMOKE_TELNET_HOST", "localhost")
port = int(os.environ.get("TCP_PORT", "2323"))
login_email = os.environ.get("SMOKE_LOGIN_EMAIL", os.environ["DEMO_SMOKE_EMAIL"])
password = os.environ.get("SMOKE_PASSWORD", os.environ["DEMO_SMOKE_PASSWORD"])
session_id = os.environ.get("SMOKE_SESSION_ID", "1")
tenant_id = os.environ.get("SMOKE_TENANT_ID", "1")
account_api_base = os.environ.get("SMOKE_ACCOUNT_API_BASE", "http://localhost:8081")
game_logic_api_base = os.environ.get("SMOKE_GAME_LOGIC_API_BASE", "http://localhost:8085")
game_session_api_base = os.environ.get("SMOKE_GAME_SESSION_API_BASE", "http://localhost:8086")
gateway_api_base = os.environ.get("SMOKE_GATEWAY_API_BASE", "http://localhost:8080")
tcp_proxy_api_base = os.environ.get("SMOKE_TCP_PROXY_API_BASE", "http://localhost:8089")
worlds_expect = os.environ.get("SMOKE_WORLDS_EXPECT", "OK WORLDS")
login_expect = os.environ.get("SMOKE_LOGIN_EXPECT", "OK LOGIN")
play_expect = os.environ.get("SMOKE_PLAY_EXPECT", "OK PLAY")
look_expect = os.environ.get("SMOKE_LOOK_EXPECT", "OK LOOK")
startup_expect = os.environ.get("SMOKE_STARTUP_EXPECT", "DISCONNECT startup_unavailable")
timeout_seconds = int(os.environ.get("SMOKE_TIMEOUT_SECONDS", "10"))
startup_wait_seconds = int(os.environ.get("SMOKE_STARTUP_WAIT_SECONDS", "90"))

def verify_pre_readiness_telnet_admission():
    readiness_url = f"{tcp_proxy_api_base}/actuator/health/readiness"
    deadline = time.time() + startup_wait_seconds
    observed_unready_window = False
    while time.time() < deadline:
        if http_readiness_up(readiness_url, timeout_seconds):
            if observed_unready_window:
                print("Confirmed pre-readiness Telnet refusal before tcp-proxy readiness converged.")
            else:
                print("tcp-proxy reported ready before a pre-readiness admission window was observable; skipping startup refusal assertion.")
            return
        observed_unready_window = True
        try:
            with socket.create_connection((host, port), timeout=timeout_seconds) as sock:
                response = recv_until_socket(sock, "\n", timeout_seconds).strip()
                print("=== Pre-readiness Telnet response ===")
                print(response or "<no data>")
                # A pre-readiness admission block may surface as either an explicit
                # startup refusal or an immediate close before any payload is sent.
                if not response:
                    print("Observed empty pre-readiness response; treating as acceptable blocked admission.")
                    time.sleep(1)
                    continue
                if startup_expect not in response:
                    raise RuntimeError(
                        "Expected pre-readiness Telnet refusal containing "
                        f"'{startup_expect}', got '{response or '<no data>'}'"
                    )
        except ConnectionRefusedError:
            print("Pre-readiness Telnet connect refused before listener bind; acceptable while traffic is still blocked.")
        except OSError as exc:
            raise RuntimeError(f"Pre-readiness Telnet verification failed: {exc}") from exc
        time.sleep(1)
    raise RuntimeError("tcp-proxy readiness did not converge after verifying pre-readiness admission behavior")


verify_pre_readiness_telnet_admission()
wait_for_account_schema(startup_wait_seconds, timeout_seconds)
wait_for_http_readiness(
    "account-service", account_api_base, startup_wait_seconds, timeout_seconds
)
wait_for_http_readiness(
    "game-logic-service", game_logic_api_base, startup_wait_seconds, timeout_seconds
)
wait_for_http_readiness(
    "game-session-service", game_session_api_base, startup_wait_seconds, timeout_seconds
)
wait_for_http_readiness(
    "spring-cloud-gateway", gateway_api_base, startup_wait_seconds, timeout_seconds
)
wait_for_http_readiness(
    "tcp-proxy-service", tcp_proxy_api_base, startup_wait_seconds, timeout_seconds
)
verify_smoke_account(account_api_base, login_email, password, timeout_seconds)
world = os.environ.get("SMOKE_WORLD") or os.environ["DEMO_SMOKE_WORLD"]
if os.environ["SMOKE_MUTATION_EXTENSION"] == "true":
    steps = gameplay_item_container_equipment_steps(
        login_email,
        password,
        worlds_expect,
        login_expect,
        play_expect,
        look_expect,
        world,
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
    )
run_telnet_smoke_session(host, port, steps, timeout_seconds)

if os.environ["SMOKE_MUTATION_EXTENSION"] == "true":
    print("Telnet WORLDS + LOGIN + PLAY + item/container/equipment mutation extension passed.")
else:
    print("Telnet WORLDS + LOGIN + PLAY + LOOK baseline smoke test passed.")
PYTHON
