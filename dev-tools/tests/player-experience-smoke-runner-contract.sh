#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
RUNNER="$ROOT_DIR/dev-tools/observability/run-player-experience-smoke.py"
VALIDATOR="$ROOT_DIR/dev-tools/observability/validate-player-experience-smoke-evidence.py"
TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

SUCCESS_EVIDENCE="$TMP_DIR/success-evidence.json"
SUCCESS_METRICS="$TMP_DIR/success-metrics.prom"
FAIL_EVIDENCE="$TMP_DIR/failure-evidence.json"

python3 "$RUNNER" \
  --simulate \
  --evidence-out "$SUCCESS_EVIDENCE" \
  --metrics-out "$SUCCESS_METRICS" \
  --source "contract-test" \
  --canary-path websocket

python3 "$VALIDATOR" "$SUCCESS_EVIDENCE" >/tmp/firemud-player-experience-runner-valid.out

grep -q 'playerflow_canary_success{flow="login",path="websocket",target="local-websocket-edge"} 1' "$SUCCESS_METRICS"
grep -q 'playerflow_canary_success{flow="command",path="websocket",target="local-websocket-edge"} 1' "$SUCCESS_METRICS"
grep -q 'entrypath_blackbox_probe_success{path="websocket",target="local-websocket-edge"} 1' "$SUCCESS_METRICS"
grep -q 'entrypath_blackbox_probe_success{path="telnet",target="local-telnet-edge"} 1' "$SUCCESS_METRICS"
grep -q 'observability_deadman_heartbeat_timestamp_seconds{source="contract-test"}' "$SUCCESS_METRICS"

python3 "$RUNNER" \
  --simulate \
  --failure-injection "websocket,telnet,login,command,deadman,prometheus,PlayerFlowCanaryCommandFailed" \
  --evidence-out "$FAIL_EVIDENCE" \
  --source "contract-test" \
  --canary-path websocket

python3 - "$FAIL_EVIDENCE" <<'PY'
import json
import sys
from pathlib import Path

data = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
assert data["externalAuthority"]["deadmanIncidentOpened"] is False
assert data["externalAuthority"]["entrypointChecks"]["prometheus"] == "red"
assert any(
    record["path"] == "websocket" and record["value"] == 0
    for record in data["mirroredSignals"]["entrypath_blackbox_probe_success"]
)
assert any(
    record["flow"] == "login" and record["value"] == 0
    for record in data["mirroredSignals"]["playerflow_canary_success"]
)
assert any(
    record["alert"] == "PlayerFlowCanaryCommandFailed"
    and record["exerciseResult"] == "failed"
    for record in data["canaryAlerts"]
)
PY

echo "player-experience smoke runner contract checks passed"
