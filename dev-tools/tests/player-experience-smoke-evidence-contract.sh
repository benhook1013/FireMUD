#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
VALIDATOR="$ROOT_DIR/dev-tools/observability/validate-player-experience-smoke-evidence.py"
TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

VALID_EVIDENCE="$TMP_DIR/valid-evidence.json"
INVALID_EVIDENCE="$TMP_DIR/invalid-evidence.json"

cat >"$VALID_EVIDENCE" <<'JSON'
{
  "deploymentRef": "staging-2026-03-19-a",
  "verifiedAt": "2026-03-19T10:55:00Z",
  "verifiedBy": "operator@example",
  "preflightEvidenceRef": "ci://observability-smoke/2026-03-19T10:40:00Z",
  "executionMode": "live",
  "externalAuthorityProvenance": "retained-external",
  "capabilities": {
    "prometheusMirrors": "published",
    "playerFlowCanary": "advertised"
  },
  "externalAuthority": {
    "profile": "independent-required",
    "exposedPublicPlayerPaths": ["websocket", "telnet"],
    "detectionBudgetSeconds": 195,
    "evidenceObservedAt": "2026-03-19T10:55:00Z",
    "lastSuccessfulHeartbeatObservedAt": "2026-03-19T10:54:00Z",
    "deadmanAuthority": {
      "status": "green",
      "evidenceRef": "pager://staging/player-experience/2026-03-19T10:50:00Z",
      "target": "staging-deadman-authority",
      "checkRef": "check://staging/deadman"
    },
    "publicPathChecks": {
      "websocket": {"status": "green", "evidenceRef": "probe://staging/websocket/2026-03-19T10:51:00Z", "target": "staging-websocket", "lastSuccessfulProbeObservedAt": "2026-03-19T10:51:00Z"},
      "telnet": {"status": "green", "evidenceRef": "probe://staging/telnet/2026-03-19T10:51:00Z", "target": "staging-telnet", "lastSuccessfulProbeObservedAt": "2026-03-19T10:51:00Z"}
    }
  },
  "mirroredSignals": {
    "entrypath_blackbox_probe_success": [
      {"path": "websocket", "target": "gateway", "value": 1},
      {"path": "telnet", "target": "tcp_proxy", "value": 1}
    ],
    "observability_deadman_heartbeat_timestamp_seconds": {
      "source": "staging",
      "value": 1773917600
    },
    "playerflow_canary_success": [
      {"flow": "login", "path": "websocket", "target": "gateway", "profile": "independent-required", "value": 1},
      {"flow": "command", "path": "websocket", "target": "gateway", "profile": "independent-required", "value": 1},
      {"flow": "login", "path": "telnet", "target": "tcp_proxy", "profile": "independent-required", "value": 1},
      {"flow": "command", "path": "telnet", "target": "tcp_proxy", "profile": "independent-required", "value": 1}
    ],
    "playerflow_canary_latency_ms": [
      {"flow": "command", "path": "websocket", "target": "gateway", "profile": "independent-required", "value": 184},
      {"flow": "command", "path": "telnet", "target": "tcp_proxy", "profile": "independent-required", "value": 201}
    ],
    "playerflow_canary_last_run_timestamp_seconds": [
      {"flow": "login", "path": "websocket", "target": "gateway", "profile": "independent-required", "value": 1773917690},
      {"flow": "command", "path": "websocket", "target": "gateway", "profile": "independent-required", "value": 1773917690},
      {"flow": "login", "path": "telnet", "target": "tcp_proxy", "profile": "independent-required", "value": 1773917690},
      {"flow": "command", "path": "telnet", "target": "tcp_proxy", "profile": "independent-required", "value": 1773917690}
    ],
    "playerflow_canary_freshness_budget_seconds": {
      "profile": "independent-required",
      "value": 195
    }
  },
  "canaryAlerts": [
    {"alert": "PlayerFlowCanaryLoginFailed", "severity": "P0", "exerciseResult": "passed"},
    {"alert": "PlayerFlowCanaryCommandFailed", "severity": "P1", "exerciseResult": "passed"},
    {"alert": "PlayerFlowCanaryLatencyHigh", "severity": "P1", "exerciseResult": "passed"},
    {"alert": "PlayerFlowCanaryEvidenceStale", "severity": "P1", "exerciseResult": "passed"}
  ]
}
JSON

python3 "$VALIDATOR" "$VALID_EVIDENCE" >"$TMP_DIR/valid.out"

python3 - "$VALIDATOR" <<'PY'
import importlib.util
import sys
from pathlib import Path

validator_path = Path(sys.argv[1])
spec = importlib.util.spec_from_file_location("player_experience_evidence_validator", validator_path)
assert spec is not None and spec.loader is not None
validator = importlib.util.module_from_spec(spec)
spec.loader.exec_module(validator)

_, findings = validator._validate_capabilities(
    {"prometheusMirrors": "invalid", "playerFlowCanary": "invalid"}
)
assert findings == [
    "capabilities.playerFlowCanary must be one of advertised, omitted",
    "capabilities.prometheusMirrors must be one of omitted, published",
]
PY

STALE_CANARY_EVIDENCE="$TMP_DIR/stale-canary-evidence.json"
python3 - "$VALID_EVIDENCE" "$STALE_CANARY_EVIDENCE" <<'PY'
import json
import sys
from pathlib import Path

source = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
source["mirroredSignals"]["playerflow_canary_last_run_timestamp_seconds"][0]["value"] = 1773917000
Path(sys.argv[2]).write_text(json.dumps(source), encoding="utf-8")
PY

if python3 "$VALIDATOR" "$STALE_CANARY_EVIDENCE" >"$TMP_DIR/stale-canary.out" 2>&1; then
  echo "stale canary timestamp unexpectedly passed" >&2
  exit 1
fi
grep -q "value is older than the configured detection budget" "$TMP_DIR/stale-canary.out"

MISSING_EXTERNAL_EVIDENCE_TIMESTAMP="$TMP_DIR/missing-external-evidence-timestamp.json"
python3 - "$VALID_EVIDENCE" "$MISSING_EXTERNAL_EVIDENCE_TIMESTAMP" <<'PY'
import json
import sys
from pathlib import Path

source = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
del source["externalAuthority"]["evidenceObservedAt"]
Path(sys.argv[2]).write_text(json.dumps(source), encoding="utf-8")
PY

if python3 "$VALIDATOR" "$MISSING_EXTERNAL_EVIDENCE_TIMESTAMP" >"$TMP_DIR/missing-external-evidence-timestamp.out" 2>&1; then
  echo "missing external evidence timestamp unexpectedly passed" >&2
  exit 1
fi
grep -q "externalAuthority.evidenceObservedAt must be an RFC3339 UTC timestamp ending in Z" "$TMP_DIR/missing-external-evidence-timestamp.out"

STALE_EXTERNAL_EVIDENCE_TIMESTAMP="$TMP_DIR/stale-external-evidence-timestamp.json"
python3 - "$VALID_EVIDENCE" "$STALE_EXTERNAL_EVIDENCE_TIMESTAMP" <<'PY'
import json
import sys
from pathlib import Path

source = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
source["externalAuthority"]["evidenceObservedAt"] = "2026-03-19T10:50:00Z"
source["externalAuthority"]["lastSuccessfulHeartbeatObservedAt"] = "2026-03-19T10:49:00Z"
for record in source["externalAuthority"]["publicPathChecks"].values():
    record["lastSuccessfulProbeObservedAt"] = "2026-03-19T10:49:00Z"
Path(sys.argv[2]).write_text(json.dumps(source), encoding="utf-8")
PY

if python3 "$VALIDATOR" "$STALE_EXTERNAL_EVIDENCE_TIMESTAMP" >"$TMP_DIR/stale-external-evidence-timestamp.out" 2>&1; then
  echo "stale external evidence timestamp unexpectedly passed" >&2
  exit 1
fi
grep -q "externalAuthority.evidenceObservedAt is older than externalAuthority.detectionBudgetSeconds" "$TMP_DIR/stale-external-evidence-timestamp.out"

FUTURE_EXTERNAL_EVIDENCE_TIMESTAMP="$TMP_DIR/future-external-evidence-timestamp.json"
python3 - "$VALID_EVIDENCE" "$FUTURE_EXTERNAL_EVIDENCE_TIMESTAMP" <<'PY'
import json
import sys
from pathlib import Path

source = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
source["externalAuthority"]["evidenceObservedAt"] = "2026-03-19T11:00:00Z"
Path(sys.argv[2]).write_text(json.dumps(source), encoding="utf-8")
PY

if python3 "$VALIDATOR" "$FUTURE_EXTERNAL_EVIDENCE_TIMESTAMP" >"$TMP_DIR/future-external-evidence-timestamp.out" 2>&1; then
  echo "future external evidence timestamp unexpectedly passed" >&2
  exit 1
fi
grep -q "externalAuthority.evidenceObservedAt cannot be in the future relative to verifiedAt" "$TMP_DIR/future-external-evidence-timestamp.out"

FUTURE_HEARTBEAT_TIMESTAMP="$TMP_DIR/future-heartbeat-timestamp.json"
python3 - "$VALID_EVIDENCE" "$FUTURE_HEARTBEAT_TIMESTAMP" <<'PY'
import json
import sys
from pathlib import Path

source = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
source["externalAuthority"]["lastSuccessfulHeartbeatObservedAt"] = "2026-03-19T11:00:00Z"
Path(sys.argv[2]).write_text(json.dumps(source), encoding="utf-8")
PY

if python3 "$VALIDATOR" "$FUTURE_HEARTBEAT_TIMESTAMP" >"$TMP_DIR/future-heartbeat-timestamp.out" 2>&1; then
  echo "future heartbeat observation timestamp unexpectedly passed" >&2
  exit 1
fi
grep -q "externalAuthority.lastSuccessfulHeartbeatObservedAt cannot be later than externalAuthority.evidenceObservedAt" \
  "$TMP_DIR/future-heartbeat-timestamp.out"

FUTURE_PROBE_TIMESTAMP="$TMP_DIR/future-probe-timestamp.json"
python3 - "$VALID_EVIDENCE" "$FUTURE_PROBE_TIMESTAMP" <<'PY'
import json
import sys
from pathlib import Path

source = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
source["externalAuthority"]["publicPathChecks"]["websocket"]["lastSuccessfulProbeObservedAt"] = "2026-03-19T11:00:00Z"
Path(sys.argv[2]).write_text(json.dumps(source), encoding="utf-8")
PY

if python3 "$VALIDATOR" "$FUTURE_PROBE_TIMESTAMP" >"$TMP_DIR/future-probe-timestamp.out" 2>&1; then
  echo "future public-probe observation timestamp unexpectedly passed" >&2
  exit 1
fi
grep -q "externalAuthority.publicPathChecks.websocket.lastSuccessfulProbeObservedAt cannot be later than externalAuthority.evidenceObservedAt" \
  "$TMP_DIR/future-probe-timestamp.out"

MISSING_HEARTBEAT_TIMESTAMP="$TMP_DIR/missing-heartbeat-timestamp.json"
python3 - "$VALID_EVIDENCE" "$MISSING_HEARTBEAT_TIMESTAMP" <<'PY'
import json
import sys
from pathlib import Path

source = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
del source["externalAuthority"]["lastSuccessfulHeartbeatObservedAt"]
Path(sys.argv[2]).write_text(json.dumps(source), encoding="utf-8")
PY

if python3 "$VALIDATOR" "$MISSING_HEARTBEAT_TIMESTAMP" >"$TMP_DIR/missing-heartbeat-timestamp.out" 2>&1; then
  echo "missing heartbeat observation timestamp unexpectedly passed" >&2
  exit 1
fi
grep -q "externalAuthority.lastSuccessfulHeartbeatObservedAt is required for independent-required" \
  "$TMP_DIR/missing-heartbeat-timestamp.out"

MISSING_PROBE_TIMESTAMP="$TMP_DIR/missing-probe-timestamp.json"
python3 - "$VALID_EVIDENCE" "$MISSING_PROBE_TIMESTAMP" <<'PY'
import json
import sys
from pathlib import Path

source = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
del source["externalAuthority"]["publicPathChecks"]["websocket"]["lastSuccessfulProbeObservedAt"]
Path(sys.argv[2]).write_text(json.dumps(source), encoding="utf-8")
PY

if python3 "$VALIDATOR" "$MISSING_PROBE_TIMESTAMP" >"$TMP_DIR/missing-probe-timestamp.out" 2>&1; then
  echo "missing public-probe observation timestamp unexpectedly passed" >&2
  exit 1
fi
grep -q "externalAuthority.publicPathChecks.websocket.lastSuccessfulProbeObservedAt is required for independent-required" \
  "$TMP_DIR/missing-probe-timestamp.out"

MALFORMED_HEARTBEAT_TIMESTAMP="$TMP_DIR/malformed-heartbeat-timestamp.json"
python3 - "$VALID_EVIDENCE" "$MALFORMED_HEARTBEAT_TIMESTAMP" <<'PY'
import json
import sys
from pathlib import Path

source = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
source["externalAuthority"]["lastSuccessfulHeartbeatObservedAt"] = "not-a-timestamp"
Path(sys.argv[2]).write_text(json.dumps(source), encoding="utf-8")
PY

if python3 "$VALIDATOR" "$MALFORMED_HEARTBEAT_TIMESTAMP" >"$TMP_DIR/malformed-heartbeat-timestamp.out" 2>&1; then
  echo "malformed heartbeat observation timestamp unexpectedly passed" >&2
  exit 1
fi
grep -q "externalAuthority.lastSuccessfulHeartbeatObservedAt must be an RFC3339 UTC timestamp ending in Z" \
  "$TMP_DIR/malformed-heartbeat-timestamp.out"

MALFORMED_PROBE_TIMESTAMP="$TMP_DIR/malformed-probe-timestamp.json"
python3 - "$VALID_EVIDENCE" "$MALFORMED_PROBE_TIMESTAMP" <<'PY'
import json
import sys
from pathlib import Path

source = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
source["externalAuthority"]["publicPathChecks"]["websocket"]["lastSuccessfulProbeObservedAt"] = "not-a-timestamp"
Path(sys.argv[2]).write_text(json.dumps(source), encoding="utf-8")
PY

if python3 "$VALIDATOR" "$MALFORMED_PROBE_TIMESTAMP" >"$TMP_DIR/malformed-probe-timestamp.out" 2>&1; then
  echo "malformed public-probe observation timestamp unexpectedly passed" >&2
  exit 1
fi
grep -q "externalAuthority.publicPathChecks.websocket.lastSuccessfulProbeObservedAt must be an RFC3339 UTC timestamp ending in Z" \
  "$TMP_DIR/malformed-probe-timestamp.out"

MISMATCHED_CANARY_BUDGET_EVIDENCE="$TMP_DIR/mismatched-canary-budget-evidence.json"
python3 - "$VALID_EVIDENCE" "$MISMATCHED_CANARY_BUDGET_EVIDENCE" <<'PY'
import json
import sys
from pathlib import Path

source = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
source["mirroredSignals"]["playerflow_canary_freshness_budget_seconds"]["value"] = 196
Path(sys.argv[2]).write_text(json.dumps(source), encoding="utf-8")
PY

if python3 "$VALIDATOR" "$MISMATCHED_CANARY_BUDGET_EVIDENCE" >"$TMP_DIR/mismatched-canary-budget.out" 2>&1; then
  echo "mismatched canary freshness budget unexpectedly passed" >&2
  exit 1
fi
grep -q "value must equal externalAuthority.detectionBudgetSeconds" "$TMP_DIR/mismatched-canary-budget.out"

MISMATCHED_CANARY_PROFILE_EVIDENCE="$TMP_DIR/mismatched-canary-profile-evidence.json"
python3 - "$VALID_EVIDENCE" "$MISMATCHED_CANARY_PROFILE_EVIDENCE" <<'PY'
import json
import sys
from pathlib import Path

source = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
source["mirroredSignals"]["playerflow_canary_success"][0]["profile"] = (
    "independent-omitted"
)
Path(sys.argv[2]).write_text(json.dumps(source), encoding="utf-8")
PY

if python3 "$VALIDATOR" "$MISMATCHED_CANARY_PROFILE_EVIDENCE" >"$TMP_DIR/mismatched-canary-profile.out" 2>&1; then
  echo "mismatched canary profile unexpectedly passed" >&2
  exit 1
fi
grep -q "playerflow_canary_success profile must be 'independent-required'" \
  "$TMP_DIR/mismatched-canary-profile.out"

MISSING_CANARY_PROFILE_EVIDENCE="$TMP_DIR/missing-canary-profile-evidence.json"
python3 - "$VALID_EVIDENCE" "$MISSING_CANARY_PROFILE_EVIDENCE" <<'PY'
import json
import sys
from pathlib import Path

source = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
del source["mirroredSignals"]["playerflow_canary_last_run_timestamp_seconds"][0][
    "profile"
]
Path(sys.argv[2]).write_text(json.dumps(source), encoding="utf-8")
PY

if python3 "$VALIDATOR" "$MISSING_CANARY_PROFILE_EVIDENCE" >"$TMP_DIR/missing-canary-profile.out" 2>&1; then
  echo "missing canary profile unexpectedly passed" >&2
  exit 1
fi
grep -q "playerflow_canary_last_run_timestamp_seconds profile must be 'independent-required'" \
  "$TMP_DIR/missing-canary-profile.out"

UNSUPPORTED_CANARY_SUCCESS_EVIDENCE="$TMP_DIR/unsupported-canary-success-evidence.json"
python3 - "$VALID_EVIDENCE" "$UNSUPPORTED_CANARY_SUCCESS_EVIDENCE" <<'PY'
import json
import sys
from pathlib import Path

source = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
source["mirroredSignals"]["playerflow_canary_success"].append(
    {"flow": "unexpected", "path": "websocket", "target": "gateway", "value": 1}
)
Path(sys.argv[2]).write_text(json.dumps(source), encoding="utf-8")
PY

if python3 "$VALIDATOR" "$UNSUPPORTED_CANARY_SUCCESS_EVIDENCE" >"$TMP_DIR/unsupported-canary-success.out" 2>&1; then
  echo "unsupported canary success flow unexpectedly passed" >&2
  exit 1
fi
grep -q "playerflow_canary_success contains unsupported flow: unexpected" \
  "$TMP_DIR/unsupported-canary-success.out"

UNSUPPORTED_CANARY_LATENCY_EVIDENCE="$TMP_DIR/unsupported-canary-latency-evidence.json"
python3 - "$VALID_EVIDENCE" "$UNSUPPORTED_CANARY_LATENCY_EVIDENCE" <<'PY'
import json
import sys
from pathlib import Path

source = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
source["mirroredSignals"]["playerflow_canary_latency_ms"].append(
    {"flow": "login", "path": "websocket", "target": "gateway", "value": 1}
)
Path(sys.argv[2]).write_text(json.dumps(source), encoding="utf-8")
PY

if python3 "$VALIDATOR" "$UNSUPPORTED_CANARY_LATENCY_EVIDENCE" >"$TMP_DIR/unsupported-canary-latency.out" 2>&1; then
  echo "unsupported canary latency flow unexpectedly passed" >&2
  exit 1
fi
grep -q "playerflow_canary_latency_ms contains unsupported flow: login" \
  "$TMP_DIR/unsupported-canary-latency.out"

DUPLICATE_CANARY_LATENCY_EVIDENCE="$TMP_DIR/duplicate-canary-latency-evidence.json"
python3 - "$VALID_EVIDENCE" "$DUPLICATE_CANARY_LATENCY_EVIDENCE" <<'PY'
import json
import sys
from pathlib import Path

source = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
source["mirroredSignals"]["playerflow_canary_latency_ms"].append(
    dict(source["mirroredSignals"]["playerflow_canary_latency_ms"][0])
)
Path(sys.argv[2]).write_text(json.dumps(source), encoding="utf-8")
PY

if python3 "$VALIDATOR" "$DUPLICATE_CANARY_LATENCY_EVIDENCE" >"$TMP_DIR/duplicate-canary-latency.out" 2>&1; then
  echo "duplicate canary latency unexpectedly passed" >&2
  exit 1
fi
grep -q "playerflow_canary_latency_ms must not duplicate flow/path: command/websocket" \
  "$TMP_DIR/duplicate-canary-latency.out"

MINIMUM_CANARY_BUDGET_EVIDENCE="$TMP_DIR/minimum-canary-budget-evidence.json"
python3 - "$VALID_EVIDENCE" "$MINIMUM_CANARY_BUDGET_EVIDENCE" <<'PY'
import json
import sys
from pathlib import Path

source = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
source["externalAuthority"]["detectionBudgetSeconds"] = 180
source["mirroredSignals"]["playerflow_canary_freshness_budget_seconds"]["value"] = 180
Path(sys.argv[2]).write_text(json.dumps(source), encoding="utf-8")
PY

python3 "$VALIDATOR" "$MINIMUM_CANARY_BUDGET_EVIDENCE" >"$TMP_DIR/minimum-canary-budget.out"

SMALL_CANARY_BUDGET_EVIDENCE="$TMP_DIR/small-canary-budget-evidence.json"
python3 - "$MINIMUM_CANARY_BUDGET_EVIDENCE" "$SMALL_CANARY_BUDGET_EVIDENCE" <<'PY'
import json
import sys
from pathlib import Path

source = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
source["externalAuthority"]["detectionBudgetSeconds"] = 179
source["mirroredSignals"]["playerflow_canary_freshness_budget_seconds"]["value"] = 179
Path(sys.argv[2]).write_text(json.dumps(source), encoding="utf-8")
PY

if python3 "$VALIDATOR" "$SMALL_CANARY_BUDGET_EVIDENCE" >"$TMP_DIR/small-canary-budget.out" 2>&1; then
  echo "canary budget below alert hold margin unexpectedly passed" >&2
  exit 1
fi
grep -q "externalAuthority.detectionBudgetSeconds must be at least 180 seconds" \
  "$TMP_DIR/small-canary-budget.out"

SYNTHETIC_REFERENCE_EVIDENCE="$TMP_DIR/synthetic-reference-evidence.json"
python3 - "$VALID_EVIDENCE" "$SYNTHETIC_REFERENCE_EVIDENCE" <<'PY'
import json
import sys
from pathlib import Path

source = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
source["externalAuthority"]["deadmanAuthority"]["evidenceRef"] = (
    "synthetic://external-authority/deadman"
)
Path(sys.argv[2]).write_text(json.dumps(source), encoding="utf-8")
PY

if python3 "$VALIDATOR" "$SYNTHETIC_REFERENCE_EVIDENCE" >"$TMP_DIR/synthetic-reference.out" 2>&1; then
  echo "synthetic retained authority reference unexpectedly accepted" >&2
  exit 1
fi
grep -q "must not use a synthetic reference for retained evidence" "$TMP_DIR/synthetic-reference.out"

WRONG_TARGET_EVIDENCE="$TMP_DIR/wrong-target-evidence.json"
python3 - "$VALID_EVIDENCE" "$WRONG_TARGET_EVIDENCE" <<'PY'
import json
import sys
from pathlib import Path

source = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
for record in source["mirroredSignals"]["entrypath_blackbox_probe_success"]:
    record["target"] = "staging-web-gateway"
for record in source["mirroredSignals"]["playerflow_canary_success"]:
    record["target"] = "staging-web-gateway"
for record in source["mirroredSignals"]["playerflow_canary_latency_ms"]:
    record["target"] = "staging-web-gateway"
Path(sys.argv[2]).write_text(json.dumps(source), encoding="utf-8")
PY

if python3 "$VALIDATOR" "$WRONG_TARGET_EVIDENCE" >"$TMP_DIR/wrong-target.out" 2>&1; then
  echo "wrong metric targets unexpectedly accepted" >&2
  exit 1
fi
grep -q "entrypath_blackbox_probe_success target for path 'websocket' must be 'gateway'" "$TMP_DIR/wrong-target.out"
grep -q "entrypath_blackbox_probe_success target for path 'telnet' must be 'tcp_proxy'" "$TMP_DIR/wrong-target.out"
grep -q "playerflow_canary_success target for path 'websocket' must be 'gateway'" "$TMP_DIR/wrong-target.out"
grep -q "playerflow_canary_success target for path 'telnet' must be 'tcp_proxy'" "$TMP_DIR/wrong-target.out"
grep -q "playerflow_canary_latency_ms target for path 'websocket' must be 'gateway'" "$TMP_DIR/wrong-target.out"
grep -q "playerflow_canary_latency_ms target for path 'telnet' must be 'tcp_proxy'" "$TMP_DIR/wrong-target.out"

DUPLICATE_EXPOSED_PATHS_EVIDENCE="$TMP_DIR/duplicate-exposed-paths-evidence.json"
python3 - "$VALID_EVIDENCE" "$DUPLICATE_EXPOSED_PATHS_EVIDENCE" <<'PY'
import json
import sys
from pathlib import Path

source = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
source["externalAuthority"]["exposedPublicPlayerPaths"] = ["websocket", "websocket"]
Path(sys.argv[2]).write_text(json.dumps(source), encoding="utf-8")
PY

if python3 "$VALIDATOR" "$DUPLICATE_EXPOSED_PATHS_EVIDENCE" >"$TMP_DIR/duplicate-paths.out" 2>&1; then
  echo "duplicate exposed paths unexpectedly accepted" >&2
  exit 1
fi
grep -q "externalAuthority.exposedPublicPlayerPaths must not contain duplicates" "$TMP_DIR/duplicate-paths.out"

SCOPED_REQUIRED_EVIDENCE="$TMP_DIR/scoped-required-evidence.json"
python3 - "$VALID_EVIDENCE" "$SCOPED_REQUIRED_EVIDENCE" <<'PY'
import json
import sys
from pathlib import Path

source = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
source["externalAuthority"]["exposedPublicPlayerPaths"] = ["websocket"]
source["externalAuthority"]["publicPathChecks"]["telnet"] = {
    "status": "not_applicable"
}
source["mirroredSignals"]["entrypath_blackbox_probe_success"] = [
    record
    for record in source["mirroredSignals"]["entrypath_blackbox_probe_success"]
    if record["path"] == "websocket"
]
source["mirroredSignals"]["playerflow_canary_success"] = [
    record
    for record in source["mirroredSignals"]["playerflow_canary_success"]
    if record["path"] == "websocket"
]
source["mirroredSignals"]["playerflow_canary_latency_ms"] = [
    record
    for record in source["mirroredSignals"]["playerflow_canary_latency_ms"]
    if record["path"] == "websocket"
]
source["mirroredSignals"]["playerflow_canary_last_run_timestamp_seconds"] = [
    record
    for record in source["mirroredSignals"]["playerflow_canary_last_run_timestamp_seconds"]
    if record["path"] == "websocket"
]
Path(sys.argv[2]).write_text(json.dumps(source), encoding="utf-8")
PY

python3 "$VALIDATOR" "$SCOPED_REQUIRED_EVIDENCE" >"$TMP_DIR/scoped.out"

cat >"$INVALID_EVIDENCE" <<'JSON'
{
  "deploymentRef": "staging-2026-03-19-a",
  "verifiedAt": "2026-03-19T10:55:00Z",
  "verifiedBy": "operator@example",
  "preflightEvidenceRef": "ci://observability-smoke/2026-03-19T10:40:00Z",
  "executionMode": "live",
  "externalAuthorityProvenance": "retained-external",
  "capabilities": {
    "prometheusMirrors": "published",
    "playerFlowCanary": "advertised"
  },
  "externalAuthority": {
    "profile": "independent-required",
    "exposedPublicPlayerPaths": ["websocket", "telnet"],
    "evidenceObservedAt": "2026-03-19T10:55:00Z",
    "deadmanAuthority": {
      "status": "red",
      "evidenceRef": "pager://staging/deadman/failure",
      "target": "staging-deadman-authority",
      "checkRef": "check://staging/deadman"
    },
    "publicPathChecks": {
      "websocket": {"status": "green", "evidenceRef": "probe://staging/websocket/2026-03-19T10:51:00Z", "target": "staging-websocket"}
    }
  },
  "mirroredSignals": {
    "entrypath_blackbox_probe_success": [
      {"path": "websocket", "target": "gateway", "value": 1}
    ],
    "observability_deadman_heartbeat_timestamp_seconds": {
      "source": "staging",
      "value": 1773917600
    },
    "playerflow_canary_success": [
      {"flow": "login", "path": "websocket", "target": "gateway", "value": 1}
    ],
    "playerflow_canary_latency_ms": []
  },
  "canaryAlerts": [
    {"alert": "PlayerFlowCanaryLoginFailed", "severity": "P0", "exerciseResult": "passed"}
  ]
}
JSON

if python3 "$VALIDATOR" "$INVALID_EVIDENCE" >"$TMP_DIR/invalid.out" 2>&1; then
  echo "invalid evidence unexpectedly passed" >&2
  exit 1
fi

grep -q "deadmanAuthority.status must be green" "$TMP_DIR/invalid.out"
grep -q "externalAuthority.publicPathChecks missing: telnet" "$TMP_DIR/invalid.out"
grep -q "canaryAlerts missing: PlayerFlowCanaryCommandFailed, PlayerFlowCanaryEvidenceStale, PlayerFlowCanaryLatencyHigh" "$TMP_DIR/invalid.out"
grep -q "playerflow_canary_success missing passing flows for path 'telnet': command, login" "$TMP_DIR/invalid.out"
grep -q "playerflow_canary_success missing passing flows for path 'websocket': command" "$TMP_DIR/invalid.out"
grep -q "playerflow_canary_latency_ms must include a numeric command latency record" "$TMP_DIR/invalid.out"

OMITTED_EVIDENCE="$TMP_DIR/omitted-evidence.json"
python3 - "$VALID_EVIDENCE" "$OMITTED_EVIDENCE" <<'PY'
import json
import sys
from pathlib import Path

source = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
source["externalAuthority"] = {
    "profile": "independent-omitted",
    "reason": "single-node deployment uses operator-dependent outage detection",
    "exposedPublicPlayerPaths": ["websocket"],
}
source["capabilities"]["playerFlowCanary"] = "omitted"
source["mirroredSignals"]["entrypath_blackbox_probe_success"] = [
    record
    for record in source["mirroredSignals"]["entrypath_blackbox_probe_success"]
    if record["path"] == "websocket"
]
source["mirroredSignals"].pop(
    "observability_deadman_heartbeat_timestamp_seconds", None
)
source["mirroredSignals"].pop("playerflow_canary_success", None)
source["mirroredSignals"].pop("playerflow_canary_latency_ms", None)
source["mirroredSignals"].pop("playerflow_canary_last_run_timestamp_seconds", None)
source["mirroredSignals"].pop("playerflow_canary_freshness_budget_seconds", None)
source.pop("canaryAlerts", None)
Path(sys.argv[2]).write_text(json.dumps(source), encoding="utf-8")
PY

python3 "$VALIDATOR" "$OMITTED_EVIDENCE" >"$TMP_DIR/omitted.out"

OMITTED_CANARY_BUDGET_EVIDENCE="$TMP_DIR/omitted-canary-budget-evidence.json"
python3 - "$OMITTED_EVIDENCE" "$OMITTED_CANARY_BUDGET_EVIDENCE" <<'PY'
import json
import sys
from pathlib import Path

source = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
source["mirroredSignals"]["playerflow_canary_freshness_budget_seconds"] = {
    "profile": "independent-omitted",
    "value": 195,
}
Path(sys.argv[2]).write_text(json.dumps(source), encoding="utf-8")
PY

if python3 "$VALIDATOR" "$OMITTED_CANARY_BUDGET_EVIDENCE" >"$TMP_DIR/omitted-canary-budget.out" 2>&1; then
  echo "omitted-canary freshness budget unexpectedly passed" >&2
  exit 1
fi
grep -q "mirroredSignals player-flow canary records require capabilities.playerFlowCanary=advertised" \
  "$TMP_DIR/omitted-canary-budget.out"

OMITTED_CANARY_EVIDENCE="$TMP_DIR/omitted-canary-evidence.json"
python3 - "$VALID_EVIDENCE" "$OMITTED_CANARY_EVIDENCE" <<'PY'
import json
import sys
from pathlib import Path

source = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
source["externalAuthority"] = {
    "profile": "independent-omitted",
    "reason": "single-node deployment uses operator-dependent outage detection",
    "exposedPublicPlayerPaths": ["websocket"],
    "detectionBudgetSeconds": 195,
}
source["capabilities"]["prometheusMirrors"] = "omitted"
source["mirroredSignals"].pop("entrypath_blackbox_probe_success", None)
source["mirroredSignals"].pop(
    "observability_deadman_heartbeat_timestamp_seconds", None
)
for key in (
    "playerflow_canary_success",
    "playerflow_canary_latency_ms",
    "playerflow_canary_last_run_timestamp_seconds",
):
    source["mirroredSignals"][key] = [
        {**record, "profile": "independent-omitted"}
        for record in source["mirroredSignals"][key]
        if record["path"] == "websocket"
    ]
source["mirroredSignals"]["playerflow_canary_freshness_budget_seconds"] = {
    "profile": "independent-omitted",
    "value": 195,
}
Path(sys.argv[2]).write_text(json.dumps(source), encoding="utf-8")
PY

python3 "$VALIDATOR" "$OMITTED_CANARY_EVIDENCE" >"$TMP_DIR/omitted-canary.out"

OMITTED_CANARY_NO_BUDGET_EVIDENCE="$TMP_DIR/omitted-canary-no-budget-evidence.json"
python3 - "$OMITTED_CANARY_EVIDENCE" "$OMITTED_CANARY_NO_BUDGET_EVIDENCE" <<'PY'
import json
import sys
from pathlib import Path

source = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
del source["mirroredSignals"]["playerflow_canary_freshness_budget_seconds"]
Path(sys.argv[2]).write_text(json.dumps(source), encoding="utf-8")
PY

if python3 "$VALIDATOR" "$OMITTED_CANARY_NO_BUDGET_EVIDENCE" >"$TMP_DIR/omitted-canary-no-budget.out" 2>&1; then
  echo "advertised canary without a freshness budget unexpectedly passed" >&2
  exit 1
fi
grep -q "mirroredSignals.playerflow_canary_freshness_budget_seconds is required" \
  "$TMP_DIR/omitted-canary-no-budget.out"

REQUIRED_CANARY_MIRRORS_OMITTED="$TMP_DIR/required-canary-mirrors-omitted.json"
python3 - "$VALID_EVIDENCE" "$REQUIRED_CANARY_MIRRORS_OMITTED" <<'PY'
import json
import sys
from pathlib import Path

source = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
source["capabilities"]["prometheusMirrors"] = "omitted"
source["mirroredSignals"].pop("entrypath_blackbox_probe_success", None)
source["mirroredSignals"].pop(
    "observability_deadman_heartbeat_timestamp_seconds", None
)
source["externalAuthority"]["detectionBudgetSeconds"] = 179
source["mirroredSignals"]["playerflow_canary_freshness_budget_seconds"]["value"] = 179
Path(sys.argv[2]).write_text(json.dumps(source), encoding="utf-8")
PY

if python3 "$VALIDATOR" "$REQUIRED_CANARY_MIRRORS_OMITTED" >"$TMP_DIR/required-canary-mirrors-omitted.out" 2>&1; then
  echo "advertised canary below the minimum budget unexpectedly passed with mirrors omitted" >&2
  exit 1
fi
grep -q "externalAuthority.detectionBudgetSeconds must be at least 180 seconds" \
  "$TMP_DIR/required-canary-mirrors-omitted.out"

OMITTED_CANARY_SMALL_BUDGET_EVIDENCE="$TMP_DIR/omitted-canary-small-budget-evidence.json"
python3 - "$OMITTED_CANARY_EVIDENCE" "$OMITTED_CANARY_SMALL_BUDGET_EVIDENCE" <<'PY'
import json
import sys
from pathlib import Path

source = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
source["externalAuthority"]["detectionBudgetSeconds"] = 179
source["mirroredSignals"]["playerflow_canary_freshness_budget_seconds"]["value"] = 179
Path(sys.argv[2]).write_text(json.dumps(source), encoding="utf-8")
PY

if python3 "$VALIDATOR" "$OMITTED_CANARY_SMALL_BUDGET_EVIDENCE" >"$TMP_DIR/omitted-canary-small-budget.out" 2>&1; then
  echo "independent-omitted advertised canary below the minimum budget unexpectedly passed" >&2
  exit 1
fi
grep -q "externalAuthority.detectionBudgetSeconds must be at least 180 seconds" \
  "$TMP_DIR/omitted-canary-small-budget.out"

MISSING_OMITTED_CANARY_BUDGET="$TMP_DIR/missing-omitted-canary-budget.json"
python3 - "$OMITTED_CANARY_EVIDENCE" "$MISSING_OMITTED_CANARY_BUDGET" <<'PY'
import json
import sys
from pathlib import Path

source = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
del source["externalAuthority"]["detectionBudgetSeconds"]
Path(sys.argv[2]).write_text(json.dumps(source), encoding="utf-8")
PY

if python3 "$VALIDATOR" "$MISSING_OMITTED_CANARY_BUDGET" >"$TMP_DIR/missing-omitted-canary-budget.out" 2>&1; then
  echo "missing advertised-canary freshness budget unexpectedly passed" >&2
  exit 1
fi
grep -q "externalAuthority.detectionBudgetSeconds must be a positive finite number" \
  "$TMP_DIR/missing-omitted-canary-budget.out"

DUPLICATE_PLAYERFLOW_SUCCESS="$TMP_DIR/duplicate-playerflow-success.json"
python3 - "$VALID_EVIDENCE" "$DUPLICATE_PLAYERFLOW_SUCCESS" <<'PY'
import json
import sys
from pathlib import Path

source = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
source["mirroredSignals"]["playerflow_canary_success"].append(
    dict(source["mirroredSignals"]["playerflow_canary_success"][0])
)
Path(sys.argv[2]).write_text(json.dumps(source), encoding="utf-8")
PY

if python3 "$VALIDATOR" "$DUPLICATE_PLAYERFLOW_SUCCESS" >"$TMP_DIR/duplicate-playerflow-success.out" 2>&1; then
  echo "duplicate player-flow success unexpectedly passed" >&2
  exit 1
fi
grep -q "playerflow_canary_success must not duplicate flow/path: login/websocket" \
  "$TMP_DIR/duplicate-playerflow-success.out"

SYNTHESIZED_OMITTED_EVIDENCE="$TMP_DIR/synthesized-omitted-evidence.json"
python3 - "$OMITTED_EVIDENCE" "$SYNTHESIZED_OMITTED_EVIDENCE" <<'PY'
import json
import sys
from pathlib import Path

source = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
source["externalAuthority"]["deadmanAuthority"] = {
    "status": "green",
    "evidenceRef": "pager://synthesized/deadman",
    "target": "synthesized-deadman",
    "checkRef": "check://synthesized/deadman",
}
Path(sys.argv[2]).write_text(json.dumps(source), encoding="utf-8")
PY

if python3 "$VALIDATOR" "$SYNTHESIZED_OMITTED_EVIDENCE" >"$TMP_DIR/synthesized.out" 2>&1; then
  echo "omitted evidence unexpectedly accepted synthesized deadman authority" >&2
  exit 1
fi
grep -q "independent-omitted must not include external authority fields" "$TMP_DIR/synthesized.out"

NON_APPLICABLE_MISREPRESENTED="$TMP_DIR/non-applicable-misrepresented.json"
python3 - "$VALID_EVIDENCE" "$NON_APPLICABLE_MISREPRESENTED" <<'PY'
import json
import sys
from pathlib import Path

source = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
source["externalAuthority"]["exposedPublicPlayerPaths"] = ["websocket"]
source["externalAuthority"]["publicPathChecks"]["telnet"] = {
    "status": "green",
    "evidenceRef": "probe://staging/telnet/not-applicable",
    "target": "staging-telnet",
}
source["mirroredSignals"]["entrypath_blackbox_probe_success"] = [
    record
    for record in source["mirroredSignals"]["entrypath_blackbox_probe_success"]
    if record["path"] == "websocket"
]
Path(sys.argv[2]).write_text(json.dumps(source), encoding="utf-8")
PY

if python3 "$VALIDATOR" "$NON_APPLICABLE_MISREPRESENTED" >"$TMP_DIR/not-applicable.out" 2>&1; then
  echo "non-exposed path unexpectedly accepted green external evidence" >&2
  exit 1
fi
grep -q "publicPathChecks.telnet must be exactly" "$TMP_DIR/not-applicable.out"

echo "player-experience smoke evidence contract checks passed"
