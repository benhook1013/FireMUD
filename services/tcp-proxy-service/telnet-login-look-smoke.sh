#!/usr/bin/env bash
# Telnet -> Gateway -> Game Session smoke test: WORLDS + LOGIN + PLAY + item/container/equipment loop over TCP Proxy.
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
FIREMUD_REPO_ROOT=${FIREMUD_REPO_ROOT:-$(cd "$(dirname "$0")/../.." && pwd)}

if command -v python3 >/dev/null 2>&1; then
  PYTHON=python3
elif command -v python >/dev/null 2>&1; then
  PYTHON=python
else
  echo "Python 3 or python is required to run this smoke test" >&2
  exit 1
fi

echo "Running Telnet WORLDS + LOGIN + PLAY + item/container/equipment smoke test against ${SMOKE_HOST}:${TCP_PORT}"
echo "Using username='${SMOKE_USERNAME}' (password redacted)"
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
    run_command_plan,
    wait_for_incremental_response,
    verify_smoke_account,
    wait_for_account_schema,
    wait_for_http_readiness,
)

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


def drain_available(sock, quiet_timeout=0.25):
    deadline = time.time() + quiet_timeout
    chunks = []
    while time.time() < deadline:
        try:
            sock.settimeout(max(0.05, deadline - time.time()))
            data = sock.recv(4096)
        except (socket.timeout, BlockingIOError):
            break
        if not data:
            break
        chunks.append(data.decode("iso-8859-1", errors="ignore"))
    return "".join(chunks)

def send_and_expect(sock, responses, line, expected_substrings, label):
    start_index = len(responses)
    sock.sendall(f"{line}\r\n".encode("iso-8859-1"))
    response = wait_for_incremental_response(
        lambda: recv_until(sock, "", 0.5),
        responses,
        start_index,
        expected_substrings,
        timeout_seconds,
        "".join,
        lambda: drain_available(sock),
    )
    print(f"=== {label} response ===")
    print(response.strip() or "<no data>")
    return response


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


try:
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
    verify_smoke_account(account_api_base, tenant_id, username, password, timeout_seconds)
    with socket.create_connection((host, port), timeout=timeout_seconds) as sock:
        responses = []
        steps = gameplay_item_container_equipment_steps(
            username, password, worlds_expect, login_expect, play_expect, look_expect
        )
        run_command_plan(
            steps,
            lambda line, expected_substrings, label, timeout: send_and_expect(
                sock, responses, line, expected_substrings, label
            ),
        )

except OSError as exc:
    sys.stderr.write(f"Failed to connect to {host}:{port}: {exc}\n")
    sys.exit(1)

print("Telnet WORLDS + LOGIN + PLAY + item/container/equipment smoke test passed.")
PYTHON
