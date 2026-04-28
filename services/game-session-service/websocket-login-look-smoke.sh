#!/usr/bin/env bash
# Direct WebSocket -> Game Session smoke test: WORLDS + LOGIN + PLAY + item/container/equipment loop after readiness.
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

echo "Running direct WebSocket WORLDS + LOGIN + PLAY + item/container/equipment smoke test against ${SMOKE_GAME_SESSION_WS_URL}"
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


def drain_available(ws, responses, quiet_timeout=0.25):
    deadline = time.time() + quiet_timeout
    while time.time() < deadline:
        remaining = max(0.05, deadline - time.time())
        try:
            responses.append(recv_text(ws, "drain chunk", remaining).strip())
        except RuntimeError:
            return


def wait_for_incremental_text(ws, responses, start_index, label, expected_substrings, timeout):
    deadline = time.time() + timeout
    expects_explicit_failure = any(
        substring.startswith("ERROR ") or substring.startswith("DISCONNECT ")
        for substring in expected_substrings
    )
    while time.time() < deadline:
        remaining = max(0.1, deadline - time.time())
        responses.append(
            recv_text(ws, f"{label} response chunk", min(remaining, timeout_seconds)).strip()
        )
        response = "\n".join(chunk for chunk in responses[start_index:] if chunk)
        if not expects_explicit_failure and (
            response.startswith("ERROR ") or response.startswith("DISCONNECT ")
        ):
            raise RuntimeError(f"{label} failed explicitly: {response}")
        if all(substring in response for substring in expected_substrings):
            drain_available(ws, responses)
            return response
    raise RuntimeError(
        f"Expected {label} response containing {expected_substrings}, got '{response}'"
    )


def send_and_expect(ws, line, expected_substrings, label, timeout=timeout_seconds):
    if not hasattr(ws, "_smoke_responses"):
        ws._smoke_responses = []
    start_index = len(ws._smoke_responses)
    ws.send(line)
    response = wait_for_incremental_text(
        ws, ws._smoke_responses, start_index, label, expected_substrings, timeout
    )
    print(f"=== {label} response ===")
    print(response.strip() or "<empty>")
    return response



def websocket_smoke():
    ws = websocket.create_connection(
        websocket_url,
        timeout=timeout_seconds,
        header=[
            f"X-Game-Instance-Id: {session_id}",
            f"X-Tenant-Id: {tenant_id}",
        ],
    )
    ws._smoke_responses = []
    try:
        send_and_expect(ws, "WORLDS", [worlds_expect], "WORLDS")
        send_and_expect(ws, f"LOGIN {username} {password}", [login_expect], "LOGIN")
        send_and_expect(ws, "PLAY demo", [play_expect], "PLAY")
        send_and_expect(ws, "LOOK", [look_expect], "LOOK", timeout=look_timeout_seconds)
        send_and_expect(ws, "INV HERE", ["Room Inventory:", "Torch", "Backpack"], "INV HERE")
        send_and_expect(ws, "GET Torch", ["You pick up Torch.", "Inventory:", "Torch"], "GET")
        send_and_expect(
            ws,
            "CONTAINER Backpack",
            ["Container: Backpack [backpack#1]", "Ration"],
            "CONTAINER",
        )
        send_and_expect(
            ws,
            "PUT Torch INTO Backpack",
            ["You put Torch into Backpack.", "Container: Backpack [backpack#1]", "Torch"],
            "PUT",
        )
        send_and_expect(
            ws,
            "TAKE Torch FROM Backpack",
            ["You take Torch from Backpack.", "Container: Backpack [backpack#1]", "Ration"],
            "TAKE",
        )
        send_and_expect(ws, "DROP Torch", ["You drop Torch."], "DROP")
        send_and_expect(ws, "INV HERE", ["Room Inventory:", "Torch", "Backpack"], "INV HERE after DROP")
        send_and_expect(ws, "EQUIPMENT", ["You have nothing equipped."], "EQUIPMENT empty")
        send_and_expect(ws, "WEAR Leather Cap", ["You wear Leather Cap."], "WEAR")
        send_and_expect(ws, "EQUIPMENT", ["Equipment:", "HEAD", "Leather Cap"], "EQUIPMENT worn")
        send_and_expect(ws, "REMOVE HEAD", ["You remove Leather Cap."], "REMOVE", timeout=look_timeout_seconds)
        send_and_expect(ws, "EQUIPMENT", ["You have nothing equipped."], "EQUIPMENT empty again")
        send_and_expect(
            ws,
            "WEAR Iron Boots",
            ["ERROR SLOT_INCOMPATIBLE", "Iron Boots cannot be worn by this body layout"],
            "WEAR incompatible",
            timeout=look_timeout_seconds,
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
