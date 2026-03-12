#!/usr/bin/env bash
# Telnet → Gateway → Game Session smoke test: LOGIN + LOOK over TCP Proxy.
set -euo pipefail

TCP_PORT=${TCP_PROXY_PORT:-2323}
SMOKE_HOST=${SMOKE_TELNET_HOST:-localhost}
SMOKE_USERNAME=${SMOKE_USERNAME:-demo@example.com}
SMOKE_PASSWORD=${SMOKE_PASSWORD:-swordfish}
SMOKE_SESSION_ID=${SMOKE_SESSION_ID:-1}
SMOKE_TENANT_ID=${SMOKE_TENANT_ID:-1}
SMOKE_LOGIN_EXPECT=${SMOKE_LOGIN_EXPECT:-"OK LOGIN"}
SMOKE_LOOK_EXPECT=${SMOKE_LOOK_EXPECT:-"OK LOOK"}
SMOKE_TIMEOUT_SECONDS=${SMOKE_TIMEOUT_SECONDS:-10}

if command -v python3 >/dev/null 2>&1; then
  PYTHON=python3
elif command -v python >/dev/null 2>&1; then
  PYTHON=python
else
  echo "Python 3 or python is required to run this smoke test" >&2
  exit 1
fi

echo "Running Telnet LOGIN + LOOK smoke test against ${SMOKE_HOST}:${TCP_PORT}"
echo "Using username='${SMOKE_USERNAME}' (password redacted)"
echo "Using session='${SMOKE_SESSION_ID}' tenant='${SMOKE_TENANT_ID}'"

"$PYTHON" - <<'PYTHON'
import os
import socket
import sys
import time

host = os.environ.get("SMOKE_TELNET_HOST", "localhost")
port = int(os.environ.get("TCP_PORT", "2323"))
username = os.environ.get("SMOKE_USERNAME", "demo@example.com")
password = os.environ.get("SMOKE_PASSWORD", "swordfish")
session_id = os.environ.get("SMOKE_SESSION_ID", "1")
tenant_id = os.environ.get("SMOKE_TENANT_ID", "1")
login_expect = os.environ.get("SMOKE_LOGIN_EXPECT", "OK LOGIN")
look_expect = os.environ.get("SMOKE_LOOK_EXPECT", "OK LOOK")
timeout_seconds = int(os.environ.get("SMOKE_TIMEOUT_SECONDS", "10"))

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

try:
    with socket.create_connection((host, port), timeout=timeout_seconds) as sock:
        session_envelope = f"SESSION {session_id} {tenant_id}\r\n"
        sock.sendall(session_envelope.encode("iso-8859-1"))

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

print("Telnet LOGIN + LOOK smoke test passed.")
PYTHON
