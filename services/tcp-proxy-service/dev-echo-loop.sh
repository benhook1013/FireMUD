#!/usr/bin/env bash
# Manual helper that connects to the dev Telnet port, sends one line, and asserts the DevEchoWebSocketHandler replies.
set -euo pipefail

TCP_PORT=${TCP_PROXY_PORT:-2323}
DEV_MESSAGE=${DEV_ECHO_MESSAGE:-firemud-dev-echo-test}
export TCP_PORT DEV_MESSAGE

if command -v python3 >/dev/null 2>&1; then
  PYTHON=python3
elif command -v python >/dev/null 2>&1; then
  PYTHON=python
else
  echo "Python 3 or python is required to run this helper" >&2
  exit 1
fi

echo "Sending one line over Telnet to localhost:${TCP_PORT} and waiting for the DevEcho response."

"$PYTHON" - <<'PYTHON'
import os
import socket

port = int(os.environ["TCP_PORT"])
message = os.environ["DEV_MESSAGE"]
with socket.create_connection(("localhost", port), timeout=5) as sock:
    sock.sendall((message + "\r\n").encode("iso-8859-1"))
    response = sock.recv(1024).decode("iso-8859-1", errors="ignore")

stripped = response.strip("\r\n")
print(f"dev echo sent: {message}")
print(f"dev echo recv: {stripped}")
if stripped != message:
    raise SystemExit("Dev echo handler did not return the expected text.")
PYTHON

echo "Telnet → WebSocket dev echo flow succeeded."
