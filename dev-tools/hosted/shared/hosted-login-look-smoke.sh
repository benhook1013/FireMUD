#!/usr/bin/env bash
set -euo pipefail

SMOKE_HOST=${SMOKE_TELNET_HOST:?SMOKE_TELNET_HOST is required}
TCP_PORT=${TCP_PORT:?TCP_PORT is required}
SMOKE_USERNAME=${SMOKE_USERNAME:-demo@example.com}
SMOKE_PASSWORD=${SMOKE_PASSWORD:-swordfish}
SMOKE_WORLD=${SMOKE_WORLD:-demo}
SMOKE_TARGET_LABEL=${SMOKE_TARGET_LABEL:-hosted environment}
SMOKE_TIMEOUT_SECONDS=${SMOKE_TIMEOUT_SECONDS:-20}
SMOKE_WORLDS_EXPECT=${SMOKE_WORLDS_EXPECT:-OK WORLDS}
SMOKE_LOGIN_EXPECT=${SMOKE_LOGIN_EXPECT:-OK LOGIN}
SMOKE_PLAY_EXPECT=${SMOKE_PLAY_EXPECT:-OK PLAY}
SMOKE_LOOK_EXPECT=${SMOKE_LOOK_EXPECT:-OK LOOK}
FIREMUD_REPO_ROOT=${FIREMUD_REPO_ROOT:-$(cd "$(dirname "$0")/../../.." && pwd)}

if command -v python3 >/dev/null 2>&1; then
  PYTHON=python3
else
  echo "python3 is required" >&2
  exit 1
fi

echo "Running ${SMOKE_TARGET_LABEL} TCP smoke against ${SMOKE_HOST}:${TCP_PORT}"
echo "Using username='${SMOKE_USERNAME}' (password redacted)"

"$PYTHON" - <<'PY'
import os
import socket
import sys
import time
from pathlib import Path

repo_root = Path(os.environ["FIREMUD_REPO_ROOT"])
sys.path.insert(0, str(repo_root / "dev-tools" / "smoke"))

from smoke_common import run_command_plan, wait_for_incremental_response

host = os.environ["SMOKE_TELNET_HOST"]
port = int(os.environ["TCP_PORT"])
username = os.environ.get("SMOKE_USERNAME", "demo@example.com")
password = os.environ.get("SMOKE_PASSWORD", "swordfish")
world = os.environ.get("SMOKE_WORLD", "demo")
timeout_seconds = int(os.environ.get("SMOKE_TIMEOUT_SECONDS", "20"))
worlds_expect = os.environ.get("SMOKE_WORLDS_EXPECT", "OK WORLDS")
login_expect = os.environ.get("SMOKE_LOGIN_EXPECT", "OK LOGIN")
play_expect = os.environ.get("SMOKE_PLAY_EXPECT", "OK PLAY")
look_expect = os.environ.get("SMOKE_LOOK_EXPECT", "OK LOOK")
session_retry_deadline = time.time() + timeout_seconds


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


last_error = None
while time.time() < session_retry_deadline:
    try:
        with socket.create_connection((host, port), timeout=timeout_seconds) as sock:
            responses = []
            run_command_plan(
                [
                    ("WORLDS", [worlds_expect], "WORLDS"),
                    (f"LOGIN {username} {password}", [login_expect], "LOGIN"),
                    (f"PLAY {world}", [play_expect], "PLAY"),
                    ("LOOK", [look_expect], "LOOK"),
                ],
                lambda line, expected_substrings, label, timeout: send_and_expect(
                    sock, responses, line, expected_substrings, label
                ),
            )
            last_error = None
            break
    except OSError as exc:
        last_error = exc
        time.sleep(2)

if last_error is not None:
    raise SystemExit(f"Failed to connect to {host}:{port}: {last_error}") from last_error

label = os.environ.get("SMOKE_TARGET_LABEL", "hosted environment")
print(f"{label} TCP LOGIN -> PLAY -> LOOK smoke test passed.")
PY
