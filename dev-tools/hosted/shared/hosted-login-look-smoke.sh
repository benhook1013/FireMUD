#!/usr/bin/env bash
set -euo pipefail

FIREMUD_REPO_ROOT=${FIREMUD_REPO_ROOT:-$(cd "$(dirname "$0")/../../.." && pwd)}
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
export FIREMUD_REPO_ROOT

if command -v python3 >/dev/null 2>&1; then
  PYTHON=python3
else
  echo "python3 is required" >&2
  exit 1
fi

echo "Running ${SMOKE_TARGET_LABEL} TCP smoke against ${SMOKE_HOST}:${TCP_PORT}"
echo "Using login credentials (email and password redacted)"

"$PYTHON" - <<'PY'
import os
import sys
from pathlib import Path

repo_root = Path(os.environ["FIREMUD_REPO_ROOT"])
sys.path.insert(0, str(repo_root / "dev-tools" / "smoke"))

from smoke_common import login_play_look_steps, run_telnet_smoke_session

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
run_telnet_smoke_session(
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
)

label = os.environ.get("SMOKE_TARGET_LABEL", "hosted environment")
print(f"{label} TCP LOGIN -> PLAY -> LOOK smoke test passed.")
PY
