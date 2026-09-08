#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
WORKFLOW="$ROOT_DIR/.github/workflows/dev-demo.yml"

check_port_forward_guard() {
  local workflow="$1"
  python3 - "$workflow" <<'PY'
import sys
from pathlib import Path

workflow_path = Path(sys.argv[1])
source = workflow_path.read_text(encoding="utf-8")

function_start = source.find("          wait_for_bootstrap_port_forward() {\n")
function_end = source.find("          cleanup_bootstrap_account_id_file() {\n", function_start)
if function_start < 0 or function_end < 0:
    raise SystemExit("dev-demo workflow must define the port-forward readiness guard")

guard = source[function_start:function_end]
required_guard_fragments = (
    "BOOTSTRAP_PORT_FORWARD_READY_ATTEMPTS",
    'kill -0 "${BOOTSTRAP_PORT_FORWARD_PID}"',
    "while IFS= read -r forwarding_line; do",
    'if [[ "${forwarding_line}" =~ ^Forwarding\\ from\\ (127\\.0\\.0\\.1|localhost):([0-9]+)\\ -\\>\\ 8080$ ]]; then',
    'BOOTSTRAP_GATEWAY_PORT="${BASH_REMATCH[2]}"',
    '[[ -z "${BOOTSTRAP_GATEWAY_PORT}" || ! "${BOOTSTRAP_GATEWAY_PORT}" =~ ^[0-9]+$ ]]',
    '"${BOOTSTRAP_PORT_FORWARD_LOG}"',
    "sleep 1",
)
for fragment in required_guard_fragments:
    if fragment not in guard:
        raise SystemExit(f"dev-demo port-forward readiness guard must contain: {fragment}")

if guard.count('kill -0 "${BOOTSTRAP_PORT_FORWARD_PID}"') < 2:
    raise SystemExit("dev-demo port-forward readiness guard must recheck its spawned PID after log confirmation")
if "$((" in guard or "$(" in guard:
    raise SystemExit("dev-demo port-forward readiness guard must parse its listener without executable substitutions")

port_assignment = source.find("          BOOTSTRAP_GATEWAY_PORT=\n", function_end)
dynamic_port_forward = source.find('            ":80"', port_assignment)
fixed_port_forward = source.find("            \"${BOOTSTRAP_GATEWAY_PORT}:80\"\n", port_assignment)
spawn_pid = source.find("          BOOTSTRAP_PORT_FORWARD_PID=$!\n", port_assignment)
guard_call = source.find("          if ! wait_for_bootstrap_port_forward; then\n", spawn_pid)
credential_bootstrap = source.find("          if ! BOOTSTRAP_MODE=account \\\n", spawn_pid)
base_url = source.find('BOOTSTRAP_GATEWAY_BASE_URL="http://127.0.0.1:${BOOTSTRAP_GATEWAY_PORT}"', credential_bootstrap)
parsed_port = guard.find('BOOTSTRAP_GATEWAY_PORT="${BASH_REMATCH[2]}"')
if min(port_assignment, dynamic_port_forward, spawn_pid, guard_call, credential_bootstrap, base_url) < 0:
    raise SystemExit("dev-demo workflow must retain dynamic port allocation, spawned PID, readiness call, and account bootstrap")
if fixed_port_forward >= 0:
    raise SystemExit("dev-demo workflow must not bind a fixed local port before readiness parsing")
if not port_assignment < dynamic_port_forward < spawn_pid < guard_call < credential_bootstrap:
    raise SystemExit("dev-demo workflow must prove its spawned port-forward before account credentials can be sent")
if parsed_port < 0:
    raise SystemExit("dev-demo workflow must source the bootstrap port from the confirmed forwarding line")
parsed_port_absolute = source.find('BOOTSTRAP_GATEWAY_PORT="${BASH_REMATCH[2]}"', function_start)
if not function_start < parsed_port_absolute < function_end:
    raise SystemExit("dev-demo workflow must assign the selected port inside the readiness guard")
if 'cat "${BOOTSTRAP_PORT_FORWARD_LOG}"' in source:
    raise SystemExit("dev-demo workflow must not print the raw port-forward log on credential-bootstrap failure")
PY
}

exercise_port_forward_guard() {
  local workflow="$1"
  local forwarding_line="$2"
  local expected_port="$3"
  local extracted_guard="$FIXTURE_DIR/extracted-port-forward-guard.sh"
  local port_forward_log="$FIXTURE_DIR/port-forward.log"

  python3 - "$workflow" "$extracted_guard" <<'PY'
import sys
from pathlib import Path

source = Path(sys.argv[1]).read_text(encoding="utf-8")
function_start = source.find("          wait_for_bootstrap_port_forward() {\n")
function_end = source.find("          cleanup_bootstrap_account_id_file() {\n", function_start)
if function_start < 0 or function_end < 0:
    raise SystemExit("dev-demo workflow must define the port-forward readiness guard")

indent = "          "
lines = source[function_start:function_end].splitlines(keepends=True)
if any(line.strip() and not line.startswith(indent) for line in lines):
    raise SystemExit("dev-demo port-forward readiness guard has unexpected indentation")
Path(sys.argv[2]).write_text(
    "".join(line[len(indent):] if line.strip() else line for line in lines),
    encoding="utf-8",
)
PY

  printf '%s\n' "$forwarding_line" > "$port_forward_log"
  (
    # shellcheck disable=SC1090 # Generated from the checked workflow function above.
    source "$extracted_guard"
    export BOOTSTRAP_PORT_FORWARD_READY_ATTEMPTS=1
    export BOOTSTRAP_PORT_FORWARD_LOG="$port_forward_log"
    export BOOTSTRAP_PORT_FORWARD_PID="$BASHPID"
    BOOTSTRAP_GATEWAY_PORT=
    if ! wait_for_bootstrap_port_forward; then
      echo "dev-demo workflow guard rejected a representative kubectl forwarding line" >&2
      return 1
    fi
    if [[ "$BOOTSTRAP_GATEWAY_PORT" != "$expected_port" ]]; then
      echo "dev-demo workflow guard parsed the wrong dynamic listener port" >&2
      return 1
    fi
  )
}

check_port_forward_guard "$WORKFLOW"

# Keep the workflow source's bootstrap path binding stable; this is the shell
# declaration used by the following heredoc and subsequent bootstrap commands.
if ! grep -Fq '          readonly BOOTSTRAP_SCRIPT=/tmp/dev-demo-bootstrap.py' "$WORKFLOW"; then
  echo "dev-demo workflow must declare its readonly bootstrap script path" >&2
  exit 1
fi

FIXTURE_DIR="$(mktemp -d)"
trap 'rm -rf "${FIXTURE_DIR}"' EXIT

exercise_port_forward_guard \
  "$WORKFLOW" \
  "Forwarding from 127.0.0.1:54321 -> 8080" \
  "54321"

MISSING_GUARD_FIXTURE="$FIXTURE_DIR/missing-port-forward-guard.yml"
sed '/^          if ! wait_for_bootstrap_port_forward; then$/,/^          fi$/d' \
  "$WORKFLOW" > "$MISSING_GUARD_FIXTURE"
if check_port_forward_guard "$MISSING_GUARD_FIXTURE" >/dev/null 2>&1; then
  echo "dev-demo workflow contract accepted credential bootstrap without the port-forward guard" >&2
  exit 1
fi

WRONG_TARGET_FIXTURE="$FIXTURE_DIR/wrong-port-forward-target.yml"
python3 - "$WORKFLOW" "$WRONG_TARGET_FIXTURE" <<'PY'
import sys
from pathlib import Path

source = Path(sys.argv[1]).read_text(encoding="utf-8")
old = r"\ -\>\ 8080$"
new = r"\ -\>\ 8081$"
if source.count(old) != 1:
    raise SystemExit("expected exactly one bootstrap forwarding target assertion")
Path(sys.argv[2]).write_text(source.replace(old, new), encoding="utf-8")
PY
if check_port_forward_guard "$WRONG_TARGET_FIXTURE" >/dev/null 2>&1; then
  echo "dev-demo workflow contract accepted confirmation for an unrelated pod target" >&2
  exit 1
fi

FIXED_PORT_FIXTURE="$FIXTURE_DIR/fixed-port-forward.yml"
python3 - "$WORKFLOW" "$FIXED_PORT_FIXTURE" <<'PY'
import sys
from pathlib import Path

source = Path(sys.argv[1]).read_text(encoding="utf-8")
old = '            ":80" \\\n'
new = '            "18080:80" \\\n'
if source.count(old) != 1:
    raise SystemExit("expected exactly one dynamic bootstrap port-forward spec")
Path(sys.argv[2]).write_text(source.replace(old, new), encoding="utf-8")
PY
if check_port_forward_guard "$FIXED_PORT_FIXTURE" >/dev/null 2>&1; then
  echo "dev-demo workflow contract accepted a fixed local port-forward binding" >&2
  exit 1
fi

FIXED_BASE_URL_FIXTURE="$FIXTURE_DIR/fixed-base-url-port.yml"
# shellcheck disable=SC2016 # The fixture mutation must match the literal workflow placeholder.
sed 's/127\.0\.0\.1:${BOOTSTRAP_GATEWAY_PORT}/127.0.0.1:18080/' \
  "$WORKFLOW" > "$FIXED_BASE_URL_FIXTURE"
if check_port_forward_guard "$FIXED_BASE_URL_FIXTURE" >/dev/null 2>&1; then
  echo "dev-demo workflow contract accepted a bootstrap URL detached from the selected port" >&2
  exit 1
fi

LATE_GUARD_FIXTURE="$FIXTURE_DIR/late-port-forward-guard.yml"
python3 - "$WORKFLOW" "$LATE_GUARD_FIXTURE" <<'PY'
import sys
from pathlib import Path

source = Path(sys.argv[1]).read_text(encoding="utf-8")
guard_start = source.index("          if ! wait_for_bootstrap_port_forward; then\n")
account_bootstrap = source.index("          if ! BOOTSTRAP_MODE=account \\\n", guard_start)
guard = source[guard_start:account_bootstrap]
source = source[:guard_start] + source[account_bootstrap:]
account_bootstrap = source.index("          if ! BOOTSTRAP_MODE=account \\\n", guard_start)
cleanup = source.index("          cleanup_bootstrap_port_forward\n", account_bootstrap)
Path(sys.argv[2]).write_text(source[:cleanup] + guard + source[cleanup:], encoding="utf-8")
PY
if check_port_forward_guard "$LATE_GUARD_FIXTURE" >/dev/null 2>&1; then
  echo "dev-demo workflow contract accepted account bootstrap before the port-forward guard" >&2
  exit 1
fi

PRINTED_LOG_FIXTURE="$FIXTURE_DIR/printed-port-forward-log.yml"
# shellcheck disable=SC2016 # Insert the literal workflow variable reference.
sed '/^          BOOTSTRAP_PORT_FORWARD_PID=\$!$/a\          cat "${BOOTSTRAP_PORT_FORWARD_LOG}"' \
  "$WORKFLOW" > "$PRINTED_LOG_FIXTURE"
if check_port_forward_guard "$PRINTED_LOG_FIXTURE" >/dev/null 2>&1; then
  echo "dev-demo workflow contract accepted printing the raw port-forward log after PID assignment" >&2
  exit 1
fi

python3 "$ROOT_DIR/dev-tools/validation/check_dev_demo_summary.py" "$ROOT_DIR"
python3 "$ROOT_DIR/dev-tools/validation/test_check_dev_demo_summary.py"
