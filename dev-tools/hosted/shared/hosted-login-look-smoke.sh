#!/usr/bin/env bash
set -euo pipefail

FIREMUD_REPO_ROOT=${FIREMUD_REPO_ROOT:-$(cd "$(dirname "$0")/../../.." && pwd)}
# shellcheck disable=SC1091 # The repository root is resolved at runtime.
source "$FIREMUD_REPO_ROOT/dev-tools/smoke/demo-smoke-defaults.sh"

SMOKE_HOST=${SMOKE_TELNET_HOST:?SMOKE_TELNET_HOST is required}
TCP_PORT=${TCP_PORT:?TCP_PORT is required}
SMOKE_LOGIN_EMAIL=${SMOKE_LOGIN_EMAIL:-$DEMO_SMOKE_EMAIL}
SMOKE_PASSWORD=${SMOKE_PASSWORD:-$DEMO_SMOKE_PASSWORD}
SMOKE_WORLD=${SMOKE_WORLD:-$DEMO_SMOKE_WORLD}
SMOKE_TARGET_LABEL=${SMOKE_TARGET_LABEL:-hosted environment}
SMOKE_TIMEOUT_SECONDS=${SMOKE_TIMEOUT_SECONDS:-20}
SMOKE_WORLDS_EXPECT=${SMOKE_WORLDS_EXPECT:-OK WORLDS}
SMOKE_LOGIN_EXPECT=${SMOKE_LOGIN_EXPECT:-OK LOGIN}
SMOKE_PLAY_EXPECT=${SMOKE_PLAY_EXPECT:-OK PLAY}
SMOKE_LOOK_EXPECT=${SMOKE_LOOK_EXPECT:-OK LOOK}
SMOKE_TLS_CA_FILE=${SMOKE_TLS_CA_FILE:-}
export SMOKE_TELNET_CA_FILE=${SMOKE_TELNET_CA_FILE:-$SMOKE_TLS_CA_FILE}
export FIREMUD_REPO_ROOT
export SMOKE_TLS_CA_FILE

if command -v python3 >/dev/null 2>&1; then
  PYTHON=python3
else
  echo "python3 is required" >&2
  exit 1
fi

echo "Running ${SMOKE_TARGET_LABEL} TCP smoke against ${SMOKE_HOST}:${TCP_PORT}"
echo "Using verified Telnet TLS with hostname '${SMOKE_HOST}'${SMOKE_TELNET_CA_FILE:+ and explicit CA override}"
echo "Using login credentials (email and password redacted)"

"$PYTHON" - <<'PY'
import os
import sys
from pathlib import Path

repo_root = Path(os.environ["FIREMUD_REPO_ROOT"])
sys.path.insert(0, str(repo_root / "dev-tools" / "smoke"))

from smoke_common import login_play_look_steps, run_telnet_smoke_session, telnet_look_room_id

host = os.environ["SMOKE_TELNET_HOST"]
port = int(os.environ["TCP_PORT"])
login_email = os.environ.get("SMOKE_LOGIN_EMAIL", os.environ["DEMO_SMOKE_EMAIL"])
password = os.environ.get("SMOKE_PASSWORD", os.environ["DEMO_SMOKE_PASSWORD"])
world = os.environ.get("SMOKE_WORLD", os.environ["DEMO_SMOKE_WORLD"])
timeout_seconds = int(os.environ.get("SMOKE_TIMEOUT_SECONDS", "20"))
worlds_expect = os.environ.get("SMOKE_WORLDS_EXPECT", "OK WORLDS")
login_expect = os.environ.get("SMOKE_LOGIN_EXPECT", "OK LOGIN")
play_expect = os.environ.get("SMOKE_PLAY_EXPECT", "OK PLAY")
look_expect = os.environ.get("SMOKE_LOOK_EXPECT", "OK LOOK")
ca_file = os.environ.get("SMOKE_TELNET_CA_FILE") or None
responses = run_telnet_smoke_session(
    host,
    port,
    login_play_look_steps(
        login_email,
        password,
        world,
        worlds_expect,
        login_expect,
        play_expect,
        look_expect,
    ),
    timeout_seconds,
    retry_window_seconds=timeout_seconds,
    retry_interval_seconds=2,
    tls_enabled=True,
    tls_server_hostname=host,
    tls_ca_file=ca_file,
)

semantic_out = os.environ.get("SMOKE_SEMANTIC_OUT")
if semantic_out:
    import json
    import re

    room_id = telnet_look_room_id(responses[-1])
    if re.fullmatch(r"[A-Za-z0-9._:-]{1,128}", room_id) is None:
        raise RuntimeError("Telnet LOOK room ID is malformed")
    path = Path(semantic_out)
    if not path.is_absolute():
        raise RuntimeError("SMOKE_SEMANTIC_OUT must be an absolute path")
    with path.open("x", encoding="utf-8") as output:
        json.dump({"transport": "telnet", "lookRoomId": room_id}, output)
        output.write("\n")

label = os.environ.get("SMOKE_TARGET_LABEL", "hosted environment")
print(f"{label} TCP LOGIN -> PLAY -> LOOK smoke test passed.")
PY
