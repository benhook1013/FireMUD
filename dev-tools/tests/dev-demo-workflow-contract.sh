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
    "'^Forwarding from (127\\.0\\.0\\.1|localhost):18080 -> [0-9]+$'",
    '"${BOOTSTRAP_PORT_FORWARD_LOG}"',
    "sleep 1",
)
for fragment in required_guard_fragments:
    if fragment not in guard:
        raise SystemExit(f"dev-demo port-forward readiness guard must contain: {fragment}")

if guard.count('kill -0 "${BOOTSTRAP_PORT_FORWARD_PID}"') < 2:
    raise SystemExit("dev-demo port-forward readiness guard must recheck its spawned PID after log confirmation")

port_assignment = source.find("          BOOTSTRAP_GATEWAY_PORT=18080\n", function_end)
spawn_pid = source.find("          BOOTSTRAP_PORT_FORWARD_PID=$!\n", port_assignment)
guard_call = source.find("          if ! wait_for_bootstrap_port_forward; then\n", spawn_pid)
credential_bootstrap = source.find("          if ! BOOTSTRAP_MODE=account \\\n", spawn_pid)
if min(port_assignment, spawn_pid, guard_call, credential_bootstrap) < 0:
    raise SystemExit("dev-demo workflow must retain the fixed port, spawned PID, readiness call, and account bootstrap")
if not port_assignment < spawn_pid < guard_call < credential_bootstrap:
    raise SystemExit("dev-demo workflow must prove its spawned port-forward before account credentials can be sent")
if 'cat "${BOOTSTRAP_PORT_FORWARD_LOG}"' in source:
    raise SystemExit("dev-demo workflow must not print the raw port-forward log on credential-bootstrap failure")
PY
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

MISSING_GUARD_FIXTURE="$FIXTURE_DIR/missing-port-forward-guard.yml"
sed '/^          if ! wait_for_bootstrap_port_forward; then$/,/^          fi$/d' \
  "$WORKFLOW" > "$MISSING_GUARD_FIXTURE"
if check_port_forward_guard "$MISSING_GUARD_FIXTURE" >/dev/null 2>&1; then
  echo "dev-demo workflow contract accepted credential bootstrap without the port-forward guard" >&2
  exit 1
fi

WRONG_LISTENER_FIXTURE="$FIXTURE_DIR/wrong-port-forward-listener.yml"
sed 's/localhost):18080/localhost):18081/' "$WORKFLOW" > "$WRONG_LISTENER_FIXTURE"
if check_port_forward_guard "$WRONG_LISTENER_FIXTURE" >/dev/null 2>&1; then
  echo "dev-demo workflow contract accepted confirmation for an unrelated local listener" >&2
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
