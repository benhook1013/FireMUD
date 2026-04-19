#!/usr/bin/env bash
# Direct WebSocket -> Game Session smoke test: WORLDS + LOGIN + PLAY + LOOK after readiness.
set -euo pipefail

SMOKE_GAME_SESSION_WS_URL=${SMOKE_GAME_SESSION_WS_URL:-ws://localhost:8086/ws/game}
SMOKE_USERNAME=${SMOKE_USERNAME:-demo@example.com}
SMOKE_PASSWORD=${SMOKE_PASSWORD:-swordfish}
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

if command -v python3 >/dev/null 2>&1; then
  PYTHON=python3
elif command -v python >/dev/null 2>&1; then
  PYTHON=python
else
  echo "Python 3 or python is required to run this smoke test" >&2
  exit 1
fi

echo "Running direct WebSocket WORLDS + LOGIN + PLAY + LOOK smoke test against ${SMOKE_GAME_SESSION_WS_URL}"
echo "Using username='${SMOKE_USERNAME}' (password redacted)"
echo "Using session='${SMOKE_SESSION_ID}' tenant='${SMOKE_TENANT_ID}'"

"$PYTHON" - <<'PYTHON'
import json
import os
import subprocess
import sys
import time
import urllib.error
import urllib.request

try:
    import websocket
except ImportError as exc:
    raise SystemExit(
        "The python 'websocket-client' package is required. "
        "Install it with 'python3 -m pip install websocket-client'."
    ) from exc

websocket_url = os.environ.get("SMOKE_GAME_SESSION_WS_URL", "ws://localhost:8086/ws/game")
username = os.environ.get("SMOKE_USERNAME", "demo@example.com")
password = os.environ.get("SMOKE_PASSWORD", "swordfish")
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
compose_project_name = os.environ.get("COMPOSE_PROJECT_NAME", "docker")
postgres_container = f"{compose_project_name}-postgres-1"


def verify_smoke_account():
    payload = json.dumps(
        {
            "tenantId": int(tenant_id),
            "username": username,
            "password": password,
            "otp": "",
        }
    ).encode("utf-8")
    request = urllib.request.Request(
        f"{account_api_base}/auth/login",
        data=payload,
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    for attempt in range(1, 4):
        try:
            with urllib.request.urlopen(request, timeout=timeout_seconds) as response:
                body = response.read().decode("utf-8", errors="ignore").strip()
                print("=== Account validation response ===")
                print(body or "<empty>")
                if response.status >= 500:
                    raise RuntimeError(
                        f"Smoke account validation returned unexpected status {response.status}"
                    )
                return body
        except urllib.error.HTTPError as exc:
            body = exc.read().decode("utf-8", errors="ignore").strip()
            print("=== Account validation response ===")
            print(body or "<empty>")
            raise RuntimeError(
                f"Smoke account validation failed with status {exc.code}: {body or '<empty>'}"
            ) from exc
        except OSError as exc:
            if attempt < 3:
                time.sleep(1)
                continue
            raise RuntimeError(f"Smoke account validation failed: {exc}") from exc


def wait_for_account_schema():
    deadline = time.time() + startup_wait_seconds
    query = "select to_regclass('account_service.accounts');"
    while time.time() < deadline:
        try:
            table_name = subprocess.check_output(
                [
                    "docker",
                    "exec",
                    postgres_container,
                    "psql",
                    "-U",
                    "firemud",
                    "-d",
                    "firemud",
                    "-tAc",
                    query,
                ],
                text=True,
                timeout=timeout_seconds,
            ).strip()
            if table_name == "account_service.accounts":
                print("Confirmed account schema is ready.")
                return
        except (OSError, subprocess.CalledProcessError, subprocess.TimeoutExpired):
            pass
        time.sleep(2)
    raise RuntimeError("Account schema readiness did not converge before smoke execution")


def http_readiness_up(readiness_url):
    try:
        with urllib.request.urlopen(readiness_url, timeout=timeout_seconds) as response:
            body = response.read().decode("utf-8", errors="ignore")
            return response.status < 500 and "\"status\":\"UP\"" in body.replace(" ", "")
    except (urllib.error.URLError, OSError):
        return False


def wait_for_http_readiness(name, base_url):
    deadline = time.time() + startup_wait_seconds
    readiness_url = f"{base_url}/actuator/health/readiness"
    while time.time() < deadline:
        if http_readiness_up(readiness_url):
            print(f"Confirmed {name} readiness via {readiness_url}.")
            return
        time.sleep(2)
    raise RuntimeError(f"{name} readiness did not report UP at {readiness_url}")


def recv_text(ws, label, timeout):
    deadline = time.time() + timeout
    last_error = None
    while time.time() < deadline:
        remaining = deadline - time.time()
        ws.settimeout(min(1.0, max(0.1, remaining)))
        try:
            return ws.recv()
        except Exception as exc:
            if exc.__class__.__name__ != "WebSocketTimeoutException" and not isinstance(
                exc, TimeoutError
            ):
                raise
            last_error = exc
    raise RuntimeError(f"Timed out waiting for {label} after {timeout}s") from last_error



def websocket_smoke():
    ws = websocket.create_connection(
        websocket_url,
        timeout=timeout_seconds,
        header=[
            f"X-Game-Instance-Id: {session_id}",
            f"X-Tenant-Id: {tenant_id}",
        ],
    )
    try:
        ws.send("WORLDS")
        worlds_response = recv_text(ws, "WORLDS response", timeout_seconds)
        print("=== WORLDS response ===")
        print(worlds_response.strip() or "<empty>")
        if worlds_expect not in worlds_response:
            raise RuntimeError(
                f"Expected WORLDS response containing '{worlds_expect}', got '{worlds_response}'"
            )

        ws.send(f"LOGIN {username} {password}")
        login_response = recv_text(ws, "LOGIN response", timeout_seconds)
        print("=== LOGIN response ===")
        print(login_response.strip() or "<empty>")
        if login_expect not in login_response:
            raise RuntimeError(
                f"Expected LOGIN response containing '{login_expect}', got '{login_response}'"
            )

        ws.send("PLAY demo")
        play_response = recv_text(ws, "PLAY response", timeout_seconds)
        print("=== PLAY response ===")
        print(play_response.strip() or "<empty>")
        if play_expect not in play_response:
            raise RuntimeError(
                f"Expected PLAY response containing '{play_expect}', got '{play_response}'"
            )

        ws.send("LOOK")
        look_chunks = []
        deadline = time.time() + look_timeout_seconds
        while time.time() < deadline:
            remaining = max(0.1, deadline - time.time())
            look_chunks.append(recv_text(ws, "LOOK response chunk", min(remaining, timeout_seconds)))
            combined = "\n".join(chunk.strip() for chunk in look_chunks if chunk.strip())
            if look_expect in combined:
                look_response = combined
                break
        else:
            look_response = "\n".join(chunk.strip() for chunk in look_chunks if chunk.strip())
        print("=== LOOK response ===")
        print(look_response.strip() or "<empty>")
        if look_expect not in look_response:
            raise RuntimeError(
                f"Expected LOOK response containing '{look_expect}', got '{look_response}'"
            )
    finally:
        ws.close()


wait_for_account_schema()
wait_for_http_readiness("account-service", account_api_base)
wait_for_http_readiness("game-logic-service", game_logic_api_base)
wait_for_http_readiness("game-session-service", game_session_api_base)
verify_smoke_account()
websocket_smoke()
PYTHON
