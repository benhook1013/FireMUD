#!/usr/bin/env bash
set -euo pipefail

SMOKE_HOST=${SMOKE_TELNET_HOST:?SMOKE_TELNET_HOST is required}
TCP_PORT=${TCP_PORT:?TCP_PORT is required}
SMOKE_USERNAME=${SMOKE_USERNAME:-demo@example.com}
SMOKE_PASSWORD=${SMOKE_PASSWORD:-swordfish}
SMOKE_WORLD=${SMOKE_WORLD:-demo}
SMOKE_TIMEOUT_SECONDS=${SMOKE_TIMEOUT_SECONDS:-20}
SMOKE_WORLDS_EXPECT=${SMOKE_WORLDS_EXPECT:-OK WORLDS}
SMOKE_LOGIN_EXPECT=${SMOKE_LOGIN_EXPECT:-OK LOGIN}
SMOKE_PLAY_EXPECT=${SMOKE_PLAY_EXPECT:-OK PLAY}
SMOKE_LOOK_EXPECT=${SMOKE_LOOK_EXPECT:-OK LOOK}

if command -v python3 >/dev/null 2>&1; then
  PYTHON=python3
else
  echo "python3 is required" >&2
  exit 1
fi

echo "Running hosted preview TCP smoke against ${SMOKE_HOST}:${TCP_PORT}"
echo "Using username='${SMOKE_USERNAME}' (password redacted)"

"$PYTHON" - <<'PY'
import os
import socket
import sys
import time

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


def expect_contains(label, payload, expected):
    print(f"=== {label} response ===")
    print(payload.strip() or "<no data>")
    if expected not in payload:
        raise SystemExit(
            f"Expected substring {expected!r} in {label} response but did not find it."
        )


last_error = None
while time.time() < session_retry_deadline:
    try:
        with socket.create_connection((host, port), timeout=timeout_seconds) as sock:
            sock.sendall(b"WORLDS\r\n")
            expect_contains(
                "WORLDS", recv_until(sock, worlds_expect, timeout_seconds), worlds_expect
            )

            sock.sendall(f"LOGIN {username} {password}\r\n".encode("iso-8859-1"))
            expect_contains(
                "LOGIN", recv_until(sock, login_expect, timeout_seconds), login_expect
            )

            sock.sendall(f"PLAY {world}\r\n".encode("iso-8859-1"))
            expect_contains("PLAY", recv_until(sock, play_expect, timeout_seconds), play_expect)

            sock.sendall(b"LOOK\r\n")
            expect_contains("LOOK", recv_until(sock, look_expect, timeout_seconds), look_expect)
            last_error = None
            break
    except OSError as exc:
        last_error = exc
        time.sleep(2)

if last_error is not None:
    raise SystemExit(f"Failed to connect to {host}:{port}: {last_error}") from last_error

print("Hosted preview TCP LOGIN -> PLAY -> LOOK smoke test passed.")
PY
