#!/usr/bin/env bash
# Telnet → Gateway → Game Session smoke test: WORLDS + LOGIN + PLAY + LOOK over TCP Proxy.
set -euo pipefail

TCP_PORT=${TCP_PROXY_PORT:-2323}
SMOKE_HOST=${SMOKE_TELNET_HOST:-localhost}
SMOKE_USERNAME=${SMOKE_USERNAME:-demo@example.com}
SMOKE_PASSWORD=${SMOKE_PASSWORD:-swordfish}
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

if command -v python3 >/dev/null 2>&1; then
  PYTHON=python3
elif command -v python >/dev/null 2>&1; then
  PYTHON=python
else
  echo "Python 3 or python is required to run this smoke test" >&2
  exit 1
fi

echo "Running Telnet WORLDS + LOGIN + PLAY + LOOK smoke test against ${SMOKE_HOST}:${TCP_PORT}"
echo "Using username='${SMOKE_USERNAME}' (password redacted)"
echo "Using session='${SMOKE_SESSION_ID}' tenant='${SMOKE_TENANT_ID}'"
echo "Using account API base '${SMOKE_ACCOUNT_API_BASE}' for smoke bootstrap"

"$PYTHON" - <<'PYTHON'
import json
import os
import socket
import subprocess
import sys
import time
import urllib.error
import urllib.request

host = os.environ.get("SMOKE_TELNET_HOST", "localhost")
port = int(os.environ.get("TCP_PORT", "2323"))
username = os.environ.get("SMOKE_USERNAME", "demo@example.com")
password = os.environ.get("SMOKE_PASSWORD", "swordfish")
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
compose_project_name = os.environ.get("COMPOSE_PROJECT_NAME", "docker")
postgres_container = f"{compose_project_name}-postgres-1"

def recv_until(sock, expected_substring, timeout):
    deadline = time.time() + timeout
    chunks = []
    while time.time() < deadline:
        try:
            sock.settimeout(deadline - time.time())
            data = sock.recv(4096)
        except (socket.timeout, BlockingIOError):
            break
        if not data:
            break
        chunks.append(data.decode("iso-8859-1", errors="ignore"))
        joined = "".join(chunks)
        if expected_substring in joined:
            return joined
    return "".join(chunks)


def ensure_smoke_account():
    payload = json.dumps(
        {
            "tenantId": int(tenant_id),
            "username": username.split("@", 1)[0],
            "email": username,
            "password": password,
        }
    ).encode("utf-8")
    request = urllib.request.Request(
        f"{account_api_base}/accounts",
        data=payload,
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    last_error = None
    for attempt in range(1, 4):
        try:
            with urllib.request.urlopen(request, timeout=timeout_seconds) as response:
                body = response.read().decode("utf-8", errors="ignore").strip()
                print("=== Account bootstrap response ===")
                print(body or "<empty>")
                return
        except urllib.error.HTTPError as exc:
            body = exc.read().decode("utf-8", errors="ignore").strip()
            print("=== Account bootstrap response ===")
            print(body or "<empty>")
            return
        except OSError as exc:
            last_error = exc
            if attempt < 3:
                time.sleep(1)
    if last_error is not None:
        print(f"Account bootstrap skipped: {last_error}")


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


def wait_for_http_readiness(name, base_url):
    deadline = time.time() + startup_wait_seconds
    readiness_url = f"{base_url}/actuator/health/readiness"
    while time.time() < deadline:
        if http_readiness_up(readiness_url):
            print(f"Confirmed {name} readiness via {readiness_url}.")
            return
        time.sleep(2)
    raise RuntimeError(f"{name} readiness did not report UP at {readiness_url}")


def http_readiness_up(readiness_url):
    try:
        with urllib.request.urlopen(readiness_url, timeout=timeout_seconds) as response:
            body = response.read().decode("utf-8", errors="ignore")
            return response.status < 500 and "\"status\":\"UP\"" in body.replace(" ", "")
    except (urllib.error.URLError, OSError):
        return False


def verify_pre_readiness_telnet_admission():
    readiness_url = f"{tcp_proxy_api_base}/actuator/health/readiness"
    deadline = time.time() + startup_wait_seconds
    observed_unready_window = False
    while time.time() < deadline:
        if http_readiness_up(readiness_url):
            if observed_unready_window:
                print("Confirmed pre-readiness Telnet refusal before tcp-proxy readiness converged.")
            else:
                print("tcp-proxy reported ready before a pre-readiness admission window was observable; skipping startup refusal assertion.")
            return
        observed_unready_window = True
        try:
            with socket.create_connection((host, port), timeout=timeout_seconds) as sock:
                response = recv_until(sock, "\n", timeout_seconds).strip()
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


def sync_session_owner_account():
    query = (
        "select id from account_service.accounts "
        f"where email = '{username}' "
        "order by id desc limit 1;"
    )
    try:
        account_id = subprocess.check_output(
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
        if not account_id:
            print("Session-owner sync skipped: no smoke account found in postgres")
            return
        update = (
            "update game_session_service.game_instances "
            f"set owner_account_id = {account_id}, tenant_id = {tenant_id} "
            f"where id = {session_id};"
        )
        subprocess.check_call(
            [
                "docker",
                "exec",
                postgres_container,
                "psql",
                "-U",
                "firemud",
                "-d",
                "firemud",
                "-c",
                update,
            ],
            timeout=timeout_seconds,
        )
        print(f"Aligned game session {session_id} owner_account_id to {account_id}")
    except (OSError, subprocess.CalledProcessError, subprocess.TimeoutExpired) as exc:
        print(f"Session-owner sync skipped: {exc}")

try:
    wait_for_account_schema()
    verify_pre_readiness_telnet_admission()
    wait_for_http_readiness("account-service", account_api_base)
    wait_for_http_readiness("game-logic-service", game_logic_api_base)
    wait_for_http_readiness("game-session-service", game_session_api_base)
    wait_for_http_readiness("spring-cloud-gateway", gateway_api_base)
    wait_for_http_readiness("tcp-proxy-service", tcp_proxy_api_base)
    ensure_smoke_account()
    sync_session_owner_account()
    with socket.create_connection((host, port), timeout=timeout_seconds) as sock:
        # WORLDS
        sock.sendall("WORLDS\r\n".encode("iso-8859-1"))
        worlds_resp = recv_until(sock, worlds_expect, timeout_seconds)
        print("=== WORLDS response ===")
        print(worlds_resp.strip() or "<no data>")
        if worlds_expect not in worlds_resp:
            sys.stderr.write(
                f"Expected substring '{worlds_expect}' in WORLDS response but did not find it.\n"
            )
            sys.exit(1)

        # LOGIN
        login_line = f"LOGIN {username} {password}\r\n"
        sock.sendall(login_line.encode("iso-8859-1"))
        login_resp = recv_until(sock, login_expect, timeout_seconds)
        print("=== LOGIN response ===")
        print(login_resp.strip() or "<no data>")
        if login_expect not in login_resp:
            sys.stderr.write(
                f"Expected substring '{login_expect}' in LOGIN response but did not find it.\n"
            )
            sys.exit(1)

        # PLAY
        sock.sendall("PLAY demo\r\n".encode("iso-8859-1"))
        play_resp = recv_until(sock, play_expect, timeout_seconds)
        print("=== PLAY response ===")
        print(play_resp.strip() or "<no data>")
        if play_expect not in play_resp:
            sys.stderr.write(
                f"Expected substring '{play_expect}' in PLAY response but did not find it.\n"
            )
            sys.exit(1)

        # LOOK
        sock.sendall("LOOK\r\n".encode("iso-8859-1"))
        look_resp = recv_until(sock, look_expect, timeout_seconds)
        print("=== LOOK response ===")
        print(look_resp.strip() or "<no data>")
        if look_expect not in look_resp:
            sys.stderr.write(
                f"Expected substring '{look_expect}' in LOOK response but did not find it.\n"
            )
            sys.exit(1)

except OSError as exc:
    sys.stderr.write(f"Failed to connect to {host}:{port}: {exc}\n")
    sys.exit(1)

print("Telnet WORLDS + LOGIN + PLAY + LOOK smoke test passed.")
PYTHON
