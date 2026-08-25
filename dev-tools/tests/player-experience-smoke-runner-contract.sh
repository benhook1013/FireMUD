#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
RUNNER="$ROOT_DIR/dev-tools/observability/run-player-experience-smoke.py"
VALIDATOR="$ROOT_DIR/dev-tools/observability/validate-player-experience-smoke-evidence.py"
TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

SMOKE_CONFIG_ENV_UNSETS=(
  -u PLAYER_EXPERIENCE_EXTERNAL_AUTHORITY_EVIDENCE
  -u PLAYER_EXPERIENCE_FAILURE_INJECTION
  -u PLAYER_EXPERIENCE_SOURCE
  -u PLAYER_EXPERIENCE_DEPLOYMENT_EVENT_ID
  -u PLAYER_EXPERIENCE_CANARY_PATH
  -u PLAYER_EXPERIENCE_PROMETHEUS_MIRRORS
  -u PLAYER_EXPERIENCE_PLAYER_FLOW_CANARY
  -u PLAYER_EXPERIENCE_WEBSOCKET_URL
  -u SMOKE_GATEWAY_API_BASE
  -u SMOKE_TELNET_HOST
  -u TCP_PROXY_PORT
  -u SMOKE_ACCOUNT_API_BASE
  -u SMOKE_GAME_LOGIC_API_BASE
  -u SMOKE_GAME_SESSION_API_BASE
  -u SMOKE_TCP_PROXY_API_BASE
  -u SMOKE_TENANT_ID
  -u PLAYER_EXPERIENCE_REALM
  -u PLAYER_EXPERIENCE_CHARACTER
  -u SMOKE_USERNAME
  -u SMOKE_PASSWORD
  -u PLAYER_EXPERIENCE_WORLD
  -u SMOKE_TIMEOUT_SECONDS
  -u SMOKE_STARTUP_WAIT_SECONDS
  -u PLAYER_EXPERIENCE_AUTH_API_BASE
  -u PLAYER_EXPERIENCE_AUTH_API_PREFIX
  -u PLAYER_EXPERIENCE_VERIFIED_BY
  -u PLAYER_EXPERIENCE_PREFLIGHT_REF
  -u PLAYER_EXPERIENCE_DEPLOYMENT_REF
)
SMOKE_CONFIG_ENV_OVERRIDES=()

run_smoke_runner() {
  env "${SMOKE_CONFIG_ENV_UNSETS[@]}" python3 "$RUNNER" "$@"
}

run_smoke_runner_with_deployment_ref() {
  local deployment_ref="$1"
  shift
  env "${SMOKE_CONFIG_ENV_UNSETS[@]}" \
    PLAYER_EXPERIENCE_DEPLOYMENT_REF="$deployment_ref" \
    python3 "$RUNNER" "$@"
}

run_clean_python() {
  if [ "${#SMOKE_CONFIG_ENV_OVERRIDES[@]}" -gt 0 ]; then
    env "${SMOKE_CONFIG_ENV_UNSETS[@]}" \
      "${SMOKE_CONFIG_ENV_OVERRIDES[@]}" \
      python3 "$@"
  else
    env "${SMOKE_CONFIG_ENV_UNSETS[@]}" \
      python3 "$@"
  fi
}

refresh_external_authority_fixture() {
  local authority_path="$1"
  run_clean_python - "$authority_path" <<'PY'
import json
import sys
from datetime import datetime, timezone
from pathlib import Path

path = Path(sys.argv[1])
source = json.loads(path.read_text(encoding="utf-8"))
observed_at = datetime.now(timezone.utc).replace(microsecond=0).isoformat().replace(
    "+00:00", "Z"
)
source["evidenceObservedAt"] = observed_at
source["lastSuccessfulHeartbeatObservedAt"] = observed_at
source["observedStalenessSeconds"] = 0
for record in source.get("publicPathChecks", {}).values():
    if record.get("status") == "green":
        record["lastSuccessfulProbeObservedAt"] = observed_at
        record["observedProbeAgeSeconds"] = 0
path.write_text(json.dumps(source), encoding="utf-8")
PY
}

SMOKE_CONFIG_ENV_OVERRIDES=(
  PLAYER_EXPERIENCE_DEPLOYMENT_EVENT_ID=not-a-uuid
  SMOKE_TIMEOUT_SECONDS=not-an-integer
)
run_clean_python - "$RUNNER" <<'PY'
import importlib.util
import os
import sys
from pathlib import Path
from urllib.parse import urlencode

runner_path = Path(sys.argv[1])
spec = importlib.util.spec_from_file_location("player_experience_smoke", runner_path)
assert spec is not None and spec.loader is not None
runner = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = runner
spec.loader.exec_module(runner)

def expect_invalid_config(error_fragment):
    try:
        runner.SmokeConfig.from_env("contract-test", "websocket", None)
    except ValueError as exc:
        assert error_fragment in str(exc)
    else:
        raise AssertionError(f"expected invalid configuration: {error_fragment}")


expect_invalid_config("not-an-integer")
os.environ["SMOKE_TIMEOUT_SECONDS"] = "10"
expect_invalid_config("deploymentEventId must be a UUID")
os.environ.pop("PLAYER_EXPERIENCE_DEPLOYMENT_EVENT_ID")
os.environ.pop("SMOKE_TIMEOUT_SECONDS")
config = runner.SmokeConfig.from_env("contract-test", "websocket", None)
assert config.websocket_url == "ws://localhost:8080/ws/game"
requests = []


def stub_http_request_json(url, timeout_seconds, method="GET", payload=None, headers=None):
    requests.append(
        {
            "url": url,
            "timeout_seconds": timeout_seconds,
            "method": method,
            "payload": payload,
            "headers": headers,
        }
    )
    if url.endswith("/auth/player-bootstrap"):
        return {"data": {"bootstrapToken": "bootstrap-token"}}
    if "/characters?" in url:
        return {"data": []}
    raise AssertionError(f"unexpected stubbed request: {method} {url}")


runner.http_request_json = stub_http_request_json
bootstrap = runner.issue_player_bootstrap(config)
bootstrap_request = requests.pop(0)
assert bootstrap == {"bootstrapToken": "bootstrap-token"}
assert bootstrap_request["method"] == "POST"
assert bootstrap_request["payload"] == {
    "accountIdentifier": config.username,
    "secret": config.password,
}
assert not ({"tenantId", "username", "password", "otp"} & bootstrap_request["payload"].keys())

connect_scope_id = "scope/with spaces?&"
runner.resolve_character_name(config, bootstrap["bootstrapToken"], connect_scope_id)
characters_request = requests.pop(0)
expected_query = urlencode({"connectScopeId": connect_scope_id})
assert characters_request["url"].endswith(f"/characters?{expected_query}")
assert connect_scope_id not in characters_request["url"]
assert characters_request["headers"] == {
    "Authorization": "Bearer bootstrap-token",
}
PY
SMOKE_CONFIG_ENV_OVERRIDES=()

SMOKE_CONFIG_ENV_OVERRIDES=(
  PLAYER_EXPERIENCE_DEPLOYMENT_EVENT_ID=not-a-uuid
  SMOKE_TIMEOUT_SECONDS=not-an-integer
)
run_clean_python - "$RUNNER" <<'PY'
import importlib.util
import os
import sys
from pathlib import Path

runner_path = Path(sys.argv[1])
spec = importlib.util.spec_from_file_location("player_experience_smoke_readiness", runner_path)
assert spec is not None and spec.loader is not None
runner = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = runner
spec.loader.exec_module(runner)

def expect_invalid_config(error_fragment):
    try:
        runner.SmokeConfig.from_env("contract-test", "telnet", None)
    except ValueError as exc:
        assert error_fragment in str(exc)
    else:
        raise AssertionError(f"expected invalid configuration: {error_fragment}")


expect_invalid_config("not-an-integer")
os.environ["SMOKE_TIMEOUT_SECONDS"] = "10"
expect_invalid_config("deploymentEventId must be a UUID")
os.environ.pop("PLAYER_EXPERIENCE_DEPLOYMENT_EVENT_ID")
os.environ.pop("SMOKE_TIMEOUT_SECONDS")
authority = {
    "profile": "independent-required",
    "exposedPublicPlayerPaths": ["telnet"],
}
readiness_calls = []

runner.wait_for_account_schema = lambda *args: None
runner.wait_for_http_readiness = lambda name, *args: readiness_calls.append(name)
runner.verify_smoke_account = lambda *args: None


def stub_entrypath_signals(config, *args):
    if config.prometheus_mirrors != "published":
        return {}
    return {"entrypath_blackbox_probe_success": []}


runner.entrypath_signals = stub_entrypath_signals

published_mirrors_config = runner.SmokeConfig.from_env(
    "contract-test", "telnet", None, "published", "omitted"
)
assert published_mirrors_config.deployment_event_id is None
signals = runner.execute_smoke(
    published_mirrors_config, False, set(), authority
)
assert readiness_calls == [
    "account-service",
    "game-logic-service",
    "game-session-service",
    "spring-cloud-gateway",
    "tcp-proxy-service",
]
assert "observability_deadman_heartbeat_timestamp_seconds" in signals

readiness_calls.clear()
omitted_mirrors_config = runner.SmokeConfig.from_env(
    "contract-test", "telnet", None, "omitted", "omitted"
)
signals = runner.execute_smoke(omitted_mirrors_config, False, set(), authority)
assert "tcp-proxy-service" not in readiness_calls
assert signals == {}
PY
SMOKE_CONFIG_ENV_OVERRIDES=()

SUCCESS_EVIDENCE="$TMP_DIR/success-evidence.json"
SUCCESS_METRICS="$TMP_DIR/success-metrics.prom"
FAIL_EVIDENCE="$TMP_DIR/failure-evidence.json"
AUTHORITY_EVIDENCE="$TMP_DIR/external-authority.json"
DEPLOYMENT_REF="0123456789abcdef0123456789abcdef01234567"
DEPLOYMENT_EVENT_ID="11111111-2222-4333-8444-555555555555"

cat >"$AUTHORITY_EVIDENCE" <<'JSON'
{
  "profile": "independent-required",
  "exposedPublicPlayerPaths": ["websocket", "telnet"],
  "detectionBudgetSeconds": 195,
  "staleThresholdSeconds": 180,
  "observedStalenessSeconds": 60,
  "lastSuccessfulHeartbeatObservedAt": "2026-03-19T10:54:00Z",
  "deadmanAuthority": {
    "status": "green",
    "evidenceRef": "pager://contract/deadman/2026-03-19T10:50:00Z",
    "pageEvidenceRef": "pager://contract/deadman/2026-03-19T10:50:00Z/delivery",
    "target": "contract-deadman-authority",
    "checkRef": "check://contract/deadman"
  },
  "publicPathChecks": {
    "websocket": {"status": "green", "evidenceRef": "probe://contract/websocket/2026-03-19T10:53:00Z", "pageEvidenceRef": "pager://contract/websocket/2026-03-19T10:50:00Z/delivery", "target": "contract-websocket", "lastSuccessfulProbeObservedAt": "2026-03-19T10:53:00Z", "observedProbeAgeSeconds": 120},
    "telnet": {"status": "green", "evidenceRef": "probe://contract/telnet/2026-03-19T10:53:00Z", "pageEvidenceRef": "pager://contract/telnet/2026-03-19T10:50:00Z/delivery", "target": "contract-telnet", "lastSuccessfulProbeObservedAt": "2026-03-19T10:53:00Z", "observedProbeAgeSeconds": 120}
  }
}
JSON

refresh_external_authority_fixture "$AUTHORITY_EVIDENCE"

run_smoke_runner_with_deployment_ref "$DEPLOYMENT_REF" \
  --simulate \
  --external-authority-evidence "$AUTHORITY_EVIDENCE" \
  --evidence-out "$SUCCESS_EVIDENCE" \
  --metrics-out "$SUCCESS_METRICS" \
  --source "contract-test" \
  --canary-path websocket \
  --deployment-event-id "$DEPLOYMENT_EVENT_ID"

python3 "$VALIDATOR" "$SUCCESS_EVIDENCE" >"$TMP_DIR/valid.out"

python3 - "$SUCCESS_EVIDENCE" "$DEPLOYMENT_REF" "$DEPLOYMENT_EVENT_ID" <<'PY'
import json
import sys
from pathlib import Path

data = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
assert data["executionMode"] == "simulated"
assert data["externalAuthorityProvenance"] == "retained-external"
assert data["deploymentRef"] == sys.argv[2]
assert data["deploymentEventId"] == sys.argv[3]
assert data["capabilities"] == {
    "prometheusMirrors": "published",
    "playerFlowCanary": "advertised",
}
assert {
    (record["flow"], record["path"])
    for record in data["mirroredSignals"]["playerflow_canary_success"]
} == {
    ("login", "websocket"),
    ("command", "websocket"),
    ("login", "telnet"),
    ("command", "telnet"),
}
assert {
    record["profile"]
    for record in data["mirroredSignals"]["playerflow_canary_success"]
} == {"independent-required"}
assert {record["path"] for record in data["mirroredSignals"]["playerflow_canary_latency_ms"]} == {
    "websocket",
    "telnet",
}
assert {
    record["profile"]
    for record in data["mirroredSignals"]["playerflow_canary_latency_ms"]
} == {"independent-required"}
assert {
    (record["flow"], record["path"])
    for record in data["mirroredSignals"]["playerflow_canary_last_run_timestamp_seconds"]
} == {
    ("login", "websocket"),
    ("command", "websocket"),
    ("login", "telnet"),
    ("command", "telnet"),
}
assert {
    record["profile"]
    for record in data["mirroredSignals"]["playerflow_canary_last_run_timestamp_seconds"]
} == {"independent-required"}
assert data["mirroredSignals"]["playerflow_canary_freshness_budget_seconds"] == {
    "profile": "independent-required",
    "value": 195,
}
PY

SYNTHETIC_EVIDENCE="$TMP_DIR/synthetic-evidence.json"
run_smoke_runner \
  --simulate \
  --evidence-out "$SYNTHETIC_EVIDENCE" \
  --source "contract-test" \
  --canary-path websocket
python3 "$VALIDATOR" "$SYNTHETIC_EVIDENCE" >"$TMP_DIR/synthetic.out"
python3 - "$SYNTHETIC_EVIDENCE" <<'PY'
import json
import sys
from pathlib import Path

data = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
assert data["executionMode"] == "simulated"
assert data["externalAuthorityProvenance"] == "synthetic"
assert "deploymentEventId" not in data
assert data["externalAuthority"]["deadmanAuthority"]["evidenceRef"].startswith(
    "synthetic://"
)
PY

run_clean_python - "$RUNNER" "$TMP_DIR/delayed-evidence.json" <<'PY'
import importlib.util
import json
import sys
import time
from pathlib import Path

runner_path = Path(sys.argv[1])
evidence_path = Path(sys.argv[2])
spec = importlib.util.spec_from_file_location("player_experience_smoke_delayed", runner_path)
assert spec is not None and spec.loader is not None
runner = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = runner
spec.loader.exec_module(runner)

original_execute_smoke = runner.execute_smoke


def delayed_execute_smoke(*args):
    time.sleep(1.1)
    return original_execute_smoke(*args)


runner.execute_smoke = delayed_execute_smoke
original_argv = sys.argv
sys.argv = [
    str(runner_path),
    "--simulate",
    "--evidence-out",
    str(evidence_path),
    "--source",
    "contract-test",
    "--canary-path",
    "websocket",
]
try:
    assert runner.main() == 0
finally:
    sys.argv = original_argv

data = json.loads(evidence_path.read_text(encoding="utf-8"))
verified_epoch = runner.dt.datetime.fromisoformat(
    data["verifiedAt"].replace("Z", "+00:00")
).timestamp()
for record in data["mirroredSignals"][runner.PLAYERFLOW_CANARY_LAST_RUN_TIMESTAMP_METRIC]:
    assert record["value"] <= verified_epoch
PY

POST_EXECUTION_STALE_AUTHORITY="$TMP_DIR/post-execution-stale-authority.json"
refresh_external_authority_fixture "$AUTHORITY_EVIDENCE"
python3 - "$AUTHORITY_EVIDENCE" "$POST_EXECUTION_STALE_AUTHORITY" <<'PY'
import json
import sys
from pathlib import Path

source = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
source["detectionBudgetSeconds"] = 1.5
Path(sys.argv[2]).write_text(json.dumps(source), encoding="utf-8")
PY
refresh_external_authority_fixture "$POST_EXECUTION_STALE_AUTHORITY"

run_clean_python - "$RUNNER" "$POST_EXECUTION_STALE_AUTHORITY" "$TMP_DIR/post-execution-stale-evidence.json" <<'PY'
import importlib.util
import sys
import time
from pathlib import Path

runner_path = Path(sys.argv[1])
authority_path = Path(sys.argv[2])
evidence_path = Path(sys.argv[3])
spec = importlib.util.spec_from_file_location("player_experience_smoke_post_execution_stale", runner_path)
assert spec is not None and spec.loader is not None
runner = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = runner
spec.loader.exec_module(runner)

original_execute_smoke = runner.execute_smoke
original_time = runner.time.time
original_gmtime = runner.time.gmtime
clock = {"now": original_time()}
runner.time.time = lambda: clock["now"]
runner.time.gmtime = lambda seconds=None: original_gmtime(
    clock["now"] if seconds is None else seconds
)


def delayed_execute_smoke(*args):
    clock["now"] += 2.0
    return original_execute_smoke(*args)


runner.execute_smoke = delayed_execute_smoke
original_argv = sys.argv
sys.argv = [
    str(runner_path),
    "--simulate",
    "--external-authority-evidence",
    str(authority_path),
    "--prometheus-mirrors",
    "omitted",
    "--player-flow-canary",
    "omitted",
    "--evidence-out",
    str(evidence_path),
    "--source",
    "contract-test",
    "--canary-path",
    "websocket",
]
try:
    try:
        runner.main()
    except RuntimeError as exc:
        assert "evidenceObservedAt is older than detectionBudgetSeconds" in str(exc)
    else:
        raise AssertionError("runner accepted authority that went stale during execution")
finally:
    sys.argv = original_argv
    runner.time.time = original_time
    runner.time.gmtime = original_gmtime
assert not evidence_path.exists()
PY

POST_EXECUTION_CHANGED_AUTHORITY="$TMP_DIR/post-execution-changed-authority.json"
refresh_external_authority_fixture "$AUTHORITY_EVIDENCE"
python3 - "$AUTHORITY_EVIDENCE" "$POST_EXECUTION_CHANGED_AUTHORITY" <<'PY'
import json
import sys
from pathlib import Path

source = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
Path(sys.argv[2]).write_text(json.dumps(source), encoding="utf-8")
PY
refresh_external_authority_fixture "$POST_EXECUTION_CHANGED_AUTHORITY"

run_clean_python - "$RUNNER" "$POST_EXECUTION_CHANGED_AUTHORITY" "$TMP_DIR/post-execution-changed-evidence.json" <<'PY'
import importlib.util
import json
import sys
from pathlib import Path

runner_path = Path(sys.argv[1])
authority_path = Path(sys.argv[2])
evidence_path = Path(sys.argv[3])
spec = importlib.util.spec_from_file_location("player_experience_smoke_changed_authority", runner_path)
assert spec is not None and spec.loader is not None
runner = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = runner
spec.loader.exec_module(runner)

original_execute_smoke = runner.execute_smoke


def changed_execute_smoke(*args):
    source = json.loads(authority_path.read_text(encoding="utf-8"))
    source["detectionBudgetSeconds"] += 1
    authority_path.write_text(json.dumps(source), encoding="utf-8")
    return original_execute_smoke(*args)


runner.execute_smoke = changed_execute_smoke
original_argv = sys.argv
sys.argv = [
    str(runner_path),
    "--simulate",
    "--external-authority-evidence",
    str(authority_path),
    "--prometheus-mirrors",
    "omitted",
    "--player-flow-canary",
    "omitted",
    "--evidence-out",
    str(evidence_path),
    "--source",
    "contract-test",
    "--canary-path",
    "websocket",
]
try:
    try:
        runner.main()
    except RuntimeError as exc:
        assert "detectionBudgetSeconds changed from 195 to 196" in str(exc)
    else:
        raise AssertionError("runner accepted authority snapshot changed during execution")
finally:
    sys.argv = original_argv
assert not evidence_path.exists()
PY

grep -q 'playerflow_canary_success{flow="login",path="websocket",target="gateway",profile="independent-required"} 1' "$SUCCESS_METRICS"
grep -q 'playerflow_canary_success{flow="command",path="websocket",target="gateway",profile="independent-required"} 1' "$SUCCESS_METRICS"
grep -q 'playerflow_canary_success{flow="login",path="telnet",target="tcp_proxy",profile="independent-required"} 1' "$SUCCESS_METRICS"
grep -q 'playerflow_canary_success{flow="command",path="telnet",target="tcp_proxy",profile="independent-required"} 1' "$SUCCESS_METRICS"
grep -q 'playerflow_canary_last_run_timestamp_seconds{flow="login",path="websocket",target="gateway",profile="independent-required"} ' "$SUCCESS_METRICS"
grep -q 'playerflow_canary_last_run_timestamp_seconds{flow="command",path="telnet",target="tcp_proxy",profile="independent-required"} ' "$SUCCESS_METRICS"
grep -q 'playerflow_canary_freshness_budget_seconds{profile="independent-required"} 195' "$SUCCESS_METRICS"
grep -q 'entrypath_blackbox_probe_success{path="websocket",target="gateway"} 1' "$SUCCESS_METRICS"
grep -q 'entrypath_blackbox_probe_success{path="telnet",target="tcp_proxy"} 1' "$SUCCESS_METRICS"
grep -q 'observability_deadman_heartbeat_timestamp_seconds{source="contract-test"}' "$SUCCESS_METRICS"

REQUIRED_180_MIRRORS_OMITTED_AUTHORITY="$TMP_DIR/required-180-mirrors-omitted-authority.json"
REQUIRED_180_MIRRORS_OMITTED_EVIDENCE="$TMP_DIR/required-180-mirrors-omitted-evidence.json"
python3 - "$AUTHORITY_EVIDENCE" "$REQUIRED_180_MIRRORS_OMITTED_AUTHORITY" <<'PY'
import json
import sys
from pathlib import Path

source = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
source["detectionBudgetSeconds"] = 180
Path(sys.argv[2]).write_text(json.dumps(source), encoding="utf-8")
PY
refresh_external_authority_fixture "$REQUIRED_180_MIRRORS_OMITTED_AUTHORITY"
run_smoke_runner \
  --simulate \
  --external-authority-evidence "$REQUIRED_180_MIRRORS_OMITTED_AUTHORITY" \
  --prometheus-mirrors omitted \
  --player-flow-canary advertised \
  --evidence-out "$REQUIRED_180_MIRRORS_OMITTED_EVIDENCE" \
  --source "contract-test" \
  --canary-path websocket >/dev/null
python3 "$VALIDATOR" "$REQUIRED_180_MIRRORS_OMITTED_EVIDENCE" >"$TMP_DIR/required-180-mirrors-omitted.out"
python3 - "$REQUIRED_180_MIRRORS_OMITTED_EVIDENCE" <<'PY'
import json
import sys
from pathlib import Path

data = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
assert data["externalAuthority"]["detectionBudgetSeconds"] == 180
assert {
    "playerflow_canary_success",
    "playerflow_canary_latency_ms",
    "playerflow_canary_last_run_timestamp_seconds",
    "playerflow_canary_freshness_budget_seconds",
}.issubset(data["mirroredSignals"])
assert "entrypath_blackbox_probe_success" not in data["mirroredSignals"]
assert "observability_deadman_heartbeat_timestamp_seconds" not in data["mirroredSignals"]
PY

REQUIRED_179_CANARY_AUTHORITY="$TMP_DIR/required-179-canary-authority.json"
python3 - "$AUTHORITY_EVIDENCE" "$REQUIRED_179_CANARY_AUTHORITY" <<'PY'
import json
import sys
from pathlib import Path

source = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
source["detectionBudgetSeconds"] = 179
Path(sys.argv[2]).write_text(json.dumps(source), encoding="utf-8")
PY
refresh_external_authority_fixture "$REQUIRED_179_CANARY_AUTHORITY"

if run_smoke_runner \
  --simulate \
  --external-authority-evidence "$REQUIRED_179_CANARY_AUTHORITY" \
  --player-flow-canary advertised \
  --evidence-out "$TMP_DIR/required-179-canary-evidence.json" \
  --source "contract-test" \
  --canary-path websocket >"$TMP_DIR/required-179-canary.out" 2>&1; then
  echo "runner unexpectedly accepted independent-required 179-second canary budget" >&2
  exit 1
fi
grep -q "detectionBudgetSeconds must be at least 180 seconds" "$TMP_DIR/required-179-canary.out"

MISSING_TIMESTAMP_AUTHORITY="$TMP_DIR/missing-timestamp-authority.json"
python3 - "$AUTHORITY_EVIDENCE" "$MISSING_TIMESTAMP_AUTHORITY" <<'PY'
import json
import sys
from pathlib import Path

source = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
del source["evidenceObservedAt"]
Path(sys.argv[2]).write_text(json.dumps(source), encoding="utf-8")
PY

if run_smoke_runner \
  --simulate \
  --external-authority-evidence "$MISSING_TIMESTAMP_AUTHORITY" \
  --evidence-out "$TMP_DIR/missing-timestamp-evidence.json" \
  --source "contract-test" \
  --canary-path websocket >"$TMP_DIR/missing-timestamp.out" 2>&1; then
  echo "runner unexpectedly accepted missing external evidence timestamp" >&2
  exit 1
fi
grep -q "must define evidenceObservedAt" "$TMP_DIR/missing-timestamp.out"

MISSING_STALE_THRESHOLD_AUTHORITY="$TMP_DIR/missing-stale-threshold-authority.json"
python3 - "$AUTHORITY_EVIDENCE" "$MISSING_STALE_THRESHOLD_AUTHORITY" <<'PY'
import json
import sys
from pathlib import Path

source = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
del source["staleThresholdSeconds"]
Path(sys.argv[2]).write_text(json.dumps(source), encoding="utf-8")
PY

if run_smoke_runner \
  --simulate \
  --external-authority-evidence "$MISSING_STALE_THRESHOLD_AUTHORITY" \
  --evidence-out "$TMP_DIR/missing-stale-threshold-evidence.json" \
  --source "contract-test" \
  --canary-path websocket >"$TMP_DIR/missing-stale-threshold.out" 2>&1; then
  echo "runner unexpectedly accepted missing stale threshold" >&2
  exit 1
fi
grep -q "must define a positive finite staleThresholdSeconds" "$TMP_DIR/missing-stale-threshold.out"

INVALID_STALE_THRESHOLD_AUTHORITY="$TMP_DIR/invalid-stale-threshold-authority.json"
python3 - "$AUTHORITY_EVIDENCE" "$INVALID_STALE_THRESHOLD_AUTHORITY" <<'PY'
import json
import sys
from pathlib import Path

source = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
source["staleThresholdSeconds"] = 0
Path(sys.argv[2]).write_text(json.dumps(source), encoding="utf-8")
PY

if run_smoke_runner \
  --simulate \
  --external-authority-evidence "$INVALID_STALE_THRESHOLD_AUTHORITY" \
  --evidence-out "$TMP_DIR/invalid-stale-threshold-evidence.json" \
  --source "contract-test" \
  --canary-path websocket >"$TMP_DIR/invalid-stale-threshold.out" 2>&1; then
  echo "runner unexpectedly accepted invalid stale threshold" >&2
  exit 1
fi
grep -q "must define a positive finite staleThresholdSeconds" "$TMP_DIR/invalid-stale-threshold.out"

OVER_THRESHOLD_DEADMAN_AUTHORITY="$TMP_DIR/over-threshold-deadman-authority.json"
python3 - "$AUTHORITY_EVIDENCE" "$OVER_THRESHOLD_DEADMAN_AUTHORITY" <<'PY'
import json
import sys
from datetime import datetime, timedelta, timezone
from pathlib import Path

source = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
observed_at = datetime.fromisoformat(
    source["evidenceObservedAt"].replace("Z", "+00:00")
)
source["lastSuccessfulHeartbeatObservedAt"] = (
    observed_at - timedelta(seconds=181)
).astimezone(timezone.utc).isoformat().replace("+00:00", "Z")
source["observedStalenessSeconds"] = 181
Path(sys.argv[2]).write_text(json.dumps(source), encoding="utf-8")
PY

if run_smoke_runner \
  --simulate \
  --external-authority-evidence "$OVER_THRESHOLD_DEADMAN_AUTHORITY" \
  --evidence-out "$TMP_DIR/over-threshold-deadman-evidence.json" \
  --source "contract-test" \
  --canary-path websocket >"$TMP_DIR/over-threshold-deadman.out" 2>&1; then
  echo "runner unexpectedly accepted green deadman over stale threshold" >&2
  exit 1
fi
grep -q "green deadman observedStalenessSeconds must be no greater than staleThresholdSeconds" \
  "$TMP_DIR/over-threshold-deadman.out"

MISMATCHED_STALENESS_AUTHORITY="$TMP_DIR/mismatched-staleness-authority.json"
python3 - "$AUTHORITY_EVIDENCE" "$MISMATCHED_STALENESS_AUTHORITY" <<'PY'
import json
import sys
from pathlib import Path

source = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
source["observedStalenessSeconds"] = 1
Path(sys.argv[2]).write_text(json.dumps(source), encoding="utf-8")
PY

if run_smoke_runner \
  --simulate \
  --external-authority-evidence "$MISMATCHED_STALENESS_AUTHORITY" \
  --evidence-out "$TMP_DIR/mismatched-staleness-evidence.json" \
  --source "contract-test" \
  --canary-path websocket >"$TMP_DIR/mismatched-staleness.out" 2>&1; then
  echo "runner unexpectedly accepted mismatched observed staleness" >&2
  exit 1
fi
grep -q "observedStalenessSeconds must equal evidenceObservedAt minus lastSuccessfulHeartbeatObservedAt" \
  "$TMP_DIR/mismatched-staleness.out"

MISSING_PAGE_AUTHORITY="$TMP_DIR/missing-page-authority.json"
python3 - "$AUTHORITY_EVIDENCE" "$MISSING_PAGE_AUTHORITY" <<'PY'
import json
import sys
from pathlib import Path

source = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
del source["publicPathChecks"]["websocket"]["pageEvidenceRef"]
Path(sys.argv[2]).write_text(json.dumps(source), encoding="utf-8")
PY

if run_smoke_runner \
  --simulate \
  --external-authority-evidence "$MISSING_PAGE_AUTHORITY" \
  --evidence-out "$TMP_DIR/missing-page-evidence.json" \
  --source "contract-test" \
  --canary-path websocket >"$TMP_DIR/missing-page.out" 2>&1; then
  echo "runner unexpectedly accepted missing per-path page evidence" >&2
  exit 1
fi
grep -q "publicPathChecks.websocket.pageEvidenceRef" "$TMP_DIR/missing-page.out"

MISSING_DEADMAN_PAGE_AUTHORITY="$TMP_DIR/missing-deadman-page-authority.json"
python3 - "$AUTHORITY_EVIDENCE" "$MISSING_DEADMAN_PAGE_AUTHORITY" <<'PY'
import json
import sys
from pathlib import Path

source = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
del source["deadmanAuthority"]["pageEvidenceRef"]
Path(sys.argv[2]).write_text(json.dumps(source), encoding="utf-8")
PY

if run_smoke_runner \
  --simulate \
  --external-authority-evidence "$MISSING_DEADMAN_PAGE_AUTHORITY" \
  --evidence-out "$TMP_DIR/missing-deadman-page-evidence.json" \
  --source "contract-test" \
  --canary-path websocket >"$TMP_DIR/missing-deadman-page.out" 2>&1; then
  echo "runner unexpectedly accepted missing deadman page evidence" >&2
  exit 1
fi
grep -q "deadmanAuthority.pageEvidenceRef" "$TMP_DIR/missing-deadman-page.out"

MISSING_PROBE_AGE_AUTHORITY="$TMP_DIR/missing-probe-age-authority.json"
python3 - "$AUTHORITY_EVIDENCE" "$MISSING_PROBE_AGE_AUTHORITY" <<'PY'
import json
import sys
from pathlib import Path

source = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
del source["publicPathChecks"]["websocket"]["observedProbeAgeSeconds"]
Path(sys.argv[2]).write_text(json.dumps(source), encoding="utf-8")
PY

if run_smoke_runner \
  --simulate \
  --external-authority-evidence "$MISSING_PROBE_AGE_AUTHORITY" \
  --evidence-out "$TMP_DIR/missing-probe-age-evidence.json" \
  --source "contract-test" \
  --canary-path websocket >"$TMP_DIR/missing-probe-age.out" 2>&1; then
  echo "runner unexpectedly accepted missing observed probe age" >&2
  exit 1
fi
grep -q "must define a nonnegative finite publicPathChecks.websocket.observedProbeAgeSeconds" \
  "$TMP_DIR/missing-probe-age.out"

INVALID_PROBE_AGE_AUTHORITY="$TMP_DIR/invalid-probe-age-authority.json"
python3 - "$AUTHORITY_EVIDENCE" "$INVALID_PROBE_AGE_AUTHORITY" <<'PY'
import json
import sys
from pathlib import Path

source = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
source["publicPathChecks"]["websocket"]["observedProbeAgeSeconds"] = -1
Path(sys.argv[2]).write_text(json.dumps(source), encoding="utf-8")
PY

if run_smoke_runner \
  --simulate \
  --external-authority-evidence "$INVALID_PROBE_AGE_AUTHORITY" \
  --evidence-out "$TMP_DIR/invalid-probe-age-evidence.json" \
  --source "contract-test" \
  --canary-path websocket >"$TMP_DIR/invalid-probe-age.out" 2>&1; then
  echo "runner unexpectedly accepted invalid observed probe age" >&2
  exit 1
fi
grep -q "must define a nonnegative finite publicPathChecks.websocket.observedProbeAgeSeconds" \
  "$TMP_DIR/invalid-probe-age.out"

MISMATCHED_PROBE_AGE_AUTHORITY="$TMP_DIR/mismatched-probe-age-authority.json"
python3 - "$AUTHORITY_EVIDENCE" "$MISMATCHED_PROBE_AGE_AUTHORITY" <<'PY'
import json
import sys
from pathlib import Path

source = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
source["publicPathChecks"]["websocket"]["observedProbeAgeSeconds"] = 1
Path(sys.argv[2]).write_text(json.dumps(source), encoding="utf-8")
PY

if run_smoke_runner \
  --simulate \
  --external-authority-evidence "$MISMATCHED_PROBE_AGE_AUTHORITY" \
  --evidence-out "$TMP_DIR/mismatched-probe-age-evidence.json" \
  --source "contract-test" \
  --canary-path websocket >"$TMP_DIR/mismatched-probe-age.out" 2>&1; then
  echo "runner unexpectedly accepted mismatched observed probe age" >&2
  exit 1
fi
grep -q "publicPathChecks.websocket.observedProbeAgeSeconds must equal evidenceObservedAt minus lastSuccessfulProbeObservedAt" \
  "$TMP_DIR/mismatched-probe-age.out"

OVER_BUDGET_PROBE_AGE_AUTHORITY="$TMP_DIR/over-budget-probe-age-authority.json"
python3 - "$AUTHORITY_EVIDENCE" "$OVER_BUDGET_PROBE_AGE_AUTHORITY" <<'PY'
import json
import sys
from datetime import datetime, timedelta, timezone
from pathlib import Path

source = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
observed_at = datetime.fromisoformat(
    source["evidenceObservedAt"].replace("Z", "+00:00")
)
record = source["publicPathChecks"]["websocket"]
record["lastSuccessfulProbeObservedAt"] = (
    observed_at - timedelta(seconds=196)
).astimezone(timezone.utc).isoformat().replace("+00:00", "Z")
record["observedProbeAgeSeconds"] = 196
Path(sys.argv[2]).write_text(json.dumps(source), encoding="utf-8")
PY

if run_smoke_runner \
  --simulate \
  --external-authority-evidence "$OVER_BUDGET_PROBE_AGE_AUTHORITY" \
  --evidence-out "$TMP_DIR/over-budget-probe-age-evidence.json" \
  --source "contract-test" \
  --canary-path websocket >"$TMP_DIR/over-budget-probe-age.out" 2>&1; then
  echo "runner unexpectedly accepted green public path over detection budget" >&2
  exit 1
fi
grep -q "green publicPathChecks.websocket.observedProbeAgeSeconds must be no greater than detectionBudgetSeconds" \
  "$TMP_DIR/over-budget-probe-age.out"

STALE_TIMESTAMP_AUTHORITY="$TMP_DIR/stale-timestamp-authority.json"
python3 - "$AUTHORITY_EVIDENCE" "$STALE_TIMESTAMP_AUTHORITY" <<'PY'
import json
import sys
from datetime import datetime, timedelta, timezone
from pathlib import Path

source = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
source["evidenceObservedAt"] = (
    datetime.now(timezone.utc)
    - timedelta(seconds=source["detectionBudgetSeconds"] + 60)
).replace(microsecond=0).isoformat().replace("+00:00", "Z")
source["lastSuccessfulHeartbeatObservedAt"] = source["evidenceObservedAt"]
for record in source["publicPathChecks"].values():
    if record["status"] == "green":
        record["lastSuccessfulProbeObservedAt"] = source["evidenceObservedAt"]
Path(sys.argv[2]).write_text(json.dumps(source), encoding="utf-8")
PY

if run_smoke_runner \
  --simulate \
  --external-authority-evidence "$STALE_TIMESTAMP_AUTHORITY" \
  --evidence-out "$TMP_DIR/stale-timestamp-evidence.json" \
  --source "contract-test" \
  --canary-path websocket >"$TMP_DIR/stale-timestamp.out" 2>&1; then
  echo "runner unexpectedly accepted stale external evidence timestamp" >&2
  exit 1
fi
grep -q "evidenceObservedAt is older than detectionBudgetSeconds" "$TMP_DIR/stale-timestamp.out"

FUTURE_TIMESTAMP_AUTHORITY="$TMP_DIR/future-timestamp-authority.json"
python3 - "$AUTHORITY_EVIDENCE" "$FUTURE_TIMESTAMP_AUTHORITY" <<'PY'
import json
import sys
from pathlib import Path

source = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
source["evidenceObservedAt"] = "2099-03-19T10:55:00Z"
Path(sys.argv[2]).write_text(json.dumps(source), encoding="utf-8")
PY

if run_smoke_runner \
  --simulate \
  --external-authority-evidence "$FUTURE_TIMESTAMP_AUTHORITY" \
  --evidence-out "$TMP_DIR/future-timestamp-evidence.json" \
  --source "contract-test" \
  --canary-path websocket >"$TMP_DIR/future-timestamp.out" 2>&1; then
  echo "runner unexpectedly accepted future external evidence timestamp" >&2
  exit 1
fi
grep -q "evidenceObservedAt cannot be in the future" "$TMP_DIR/future-timestamp.out"

FUTURE_HEARTBEAT_AUTHORITY="$TMP_DIR/future-heartbeat-authority.json"
python3 - "$AUTHORITY_EVIDENCE" "$FUTURE_HEARTBEAT_AUTHORITY" <<'PY'
import json
import sys
from pathlib import Path

source = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
source["lastSuccessfulHeartbeatObservedAt"] = "2099-03-19T10:55:00Z"
Path(sys.argv[2]).write_text(json.dumps(source), encoding="utf-8")
PY

if run_smoke_runner \
  --simulate \
  --external-authority-evidence "$FUTURE_HEARTBEAT_AUTHORITY" \
  --evidence-out "$TMP_DIR/future-heartbeat-evidence.json" \
  --source "contract-test" \
  --canary-path websocket >"$TMP_DIR/future-heartbeat.out" 2>&1; then
  echo "runner unexpectedly accepted future heartbeat observation timestamp" >&2
  exit 1
fi
grep -q "lastSuccessfulHeartbeatObservedAt cannot be later than evidenceObservedAt" \
  "$TMP_DIR/future-heartbeat.out"

FUTURE_PROBE_AUTHORITY="$TMP_DIR/future-probe-authority.json"
python3 - "$AUTHORITY_EVIDENCE" "$FUTURE_PROBE_AUTHORITY" <<'PY'
import json
import sys
from pathlib import Path

source = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
source["publicPathChecks"]["websocket"]["lastSuccessfulProbeObservedAt"] = "2099-03-19T10:55:00Z"
Path(sys.argv[2]).write_text(json.dumps(source), encoding="utf-8")
PY

if run_smoke_runner \
  --simulate \
  --external-authority-evidence "$FUTURE_PROBE_AUTHORITY" \
  --evidence-out "$TMP_DIR/future-probe-evidence.json" \
  --source "contract-test" \
  --canary-path websocket >"$TMP_DIR/future-probe.out" 2>&1; then
  echo "runner unexpectedly accepted future public-probe observation timestamp" >&2
  exit 1
fi
grep -q "publicPathChecks.websocket.lastSuccessfulProbeObservedAt cannot be later than evidenceObservedAt" \
  "$TMP_DIR/future-probe.out"

MISSING_HEARTBEAT_AUTHORITY="$TMP_DIR/missing-heartbeat-authority.json"
python3 - "$AUTHORITY_EVIDENCE" "$MISSING_HEARTBEAT_AUTHORITY" <<'PY'
import json
import sys
from pathlib import Path

source = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
del source["lastSuccessfulHeartbeatObservedAt"]
Path(sys.argv[2]).write_text(json.dumps(source), encoding="utf-8")
PY

if run_smoke_runner \
  --simulate \
  --external-authority-evidence "$MISSING_HEARTBEAT_AUTHORITY" \
  --evidence-out "$TMP_DIR/missing-heartbeat-evidence.json" \
  --source "contract-test" \
  --canary-path websocket >"$TMP_DIR/missing-heartbeat.out" 2>&1; then
  echo "runner unexpectedly accepted missing heartbeat observation timestamp" >&2
  exit 1
fi
grep -q "lastSuccessfulHeartbeatObservedAt is required for independent-required" \
  "$TMP_DIR/missing-heartbeat.out"

MISSING_PROBE_AUTHORITY="$TMP_DIR/missing-probe-authority.json"
python3 - "$AUTHORITY_EVIDENCE" "$MISSING_PROBE_AUTHORITY" <<'PY'
import json
import sys
from pathlib import Path

source = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
del source["publicPathChecks"]["websocket"]["lastSuccessfulProbeObservedAt"]
Path(sys.argv[2]).write_text(json.dumps(source), encoding="utf-8")
PY

if run_smoke_runner \
  --simulate \
  --external-authority-evidence "$MISSING_PROBE_AUTHORITY" \
  --evidence-out "$TMP_DIR/missing-probe-evidence.json" \
  --source "contract-test" \
  --canary-path websocket >"$TMP_DIR/missing-probe.out" 2>&1; then
  echo "runner unexpectedly accepted missing public-probe observation timestamp" >&2
  exit 1
fi
grep -q "publicPathChecks.websocket.lastSuccessfulProbeObservedAt is required for independent-required" \
  "$TMP_DIR/missing-probe.out"

MALFORMED_HEARTBEAT_AUTHORITY="$TMP_DIR/malformed-heartbeat-authority.json"
python3 - "$AUTHORITY_EVIDENCE" "$MALFORMED_HEARTBEAT_AUTHORITY" <<'PY'
import json
import sys
from pathlib import Path

source = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
source["lastSuccessfulHeartbeatObservedAt"] = "not-a-timestamp"
Path(sys.argv[2]).write_text(json.dumps(source), encoding="utf-8")
PY

if run_smoke_runner \
  --simulate \
  --external-authority-evidence "$MALFORMED_HEARTBEAT_AUTHORITY" \
  --evidence-out "$TMP_DIR/malformed-heartbeat-evidence.json" \
  --source "contract-test" \
  --canary-path websocket >"$TMP_DIR/malformed-heartbeat.out" 2>&1; then
  echo "runner unexpectedly accepted malformed heartbeat observation timestamp" >&2
  exit 1
fi
grep -q "lastSuccessfulHeartbeatObservedAt must be an RFC3339 UTC timestamp ending in Z" \
  "$TMP_DIR/malformed-heartbeat.out"

MALFORMED_PROBE_AUTHORITY="$TMP_DIR/malformed-probe-authority.json"
python3 - "$AUTHORITY_EVIDENCE" "$MALFORMED_PROBE_AUTHORITY" <<'PY'
import json
import sys
from pathlib import Path

source = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
source["publicPathChecks"]["websocket"]["lastSuccessfulProbeObservedAt"] = "not-a-timestamp"
Path(sys.argv[2]).write_text(json.dumps(source), encoding="utf-8")
PY

if run_smoke_runner \
  --simulate \
  --external-authority-evidence "$MALFORMED_PROBE_AUTHORITY" \
  --evidence-out "$TMP_DIR/malformed-probe-evidence.json" \
  --source "contract-test" \
  --canary-path websocket >"$TMP_DIR/malformed-probe.out" 2>&1; then
  echo "runner unexpectedly accepted malformed public-probe observation timestamp" >&2
  exit 1
fi
grep -q "publicPathChecks.websocket.lastSuccessfulProbeObservedAt must be an RFC3339 UTC timestamp ending in Z" \
  "$TMP_DIR/malformed-probe.out"

if run_smoke_runner \
  --evidence-out "$TMP_DIR/missing-authority.json" \
  --source "contract-test" \
  --canary-path websocket >"$TMP_DIR/missing-authority.out" 2>&1; then
  echo "non-simulated runner unexpectedly accepted missing external authority evidence" >&2
  exit 1
fi

grep -q "requires --external-authority-evidence" "$TMP_DIR/missing-authority.out"

refresh_external_authority_fixture "$AUTHORITY_EVIDENCE"
run_smoke_runner \
  --simulate \
  --external-authority-evidence "$AUTHORITY_EVIDENCE" \
  --failure-injection "websocket,telnet,login,command,deadman,PlayerFlowCanaryCommandFailed" \
  --evidence-out "$FAIL_EVIDENCE" \
  --source "contract-test" \
  --canary-path websocket

python3 - "$FAIL_EVIDENCE" <<'PY'
import json
import sys
from pathlib import Path

data = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
assert data["externalAuthority"]["deadmanAuthority"]["status"] == "red"
assert data["externalAuthority"]["publicPathChecks"]["websocket"]["status"] == "red"
assert data["externalAuthority"]["publicPathChecks"]["telnet"]["status"] == "red"
assert any(
    record["path"] == "websocket" and record["value"] == 0
    for record in data["mirroredSignals"]["entrypath_blackbox_probe_success"]
)
assert any(
    record["flow"] == "login" and record["value"] == 0
    for record in data["mirroredSignals"]["playerflow_canary_success"]
)
assert all(
    record["value"] > 0
    for record in data["mirroredSignals"]["playerflow_canary_last_run_timestamp_seconds"]
)
assert any(
    record["alert"] == "PlayerFlowCanaryCommandFailed"
    and record["exerciseResult"] == "failed"
    for record in data["canaryAlerts"]
)
assert {
    (record["alert"], record["severity"])
    for record in data["canaryAlerts"]
} == {
    ("PlayerFlowCanaryLoginFailed", "P0"),
    ("PlayerFlowCanaryCommandFailed", "P1"),
    ("PlayerFlowCanaryLatencyHigh", "P1"),
    ("PlayerFlowCanaryEvidenceStale", "P1"),
}
PY

OMITTED_AUTHORITY_EVIDENCE="$TMP_DIR/omitted-authority.json"
OMITTED_EVIDENCE="$TMP_DIR/omitted-evidence.json"
cat >"$OMITTED_AUTHORITY_EVIDENCE" <<'JSON'
{
  "profile": "independent-omitted",
  "reason": "single-node deployment uses operator-dependent outage detection",
  "exposedPublicPlayerPaths": ["websocket"]
}
JSON

run_smoke_runner \
  --simulate \
  --external-authority-evidence "$OMITTED_AUTHORITY_EVIDENCE" \
  --player-flow-canary omitted \
  --evidence-out "$OMITTED_EVIDENCE" \
  --source "contract-test" \
  --canary-path websocket

python3 "$VALIDATOR" "$OMITTED_EVIDENCE" >"$TMP_DIR/omitted.out"

python3 - "$OMITTED_EVIDENCE" <<'PY'
import json
import sys
from pathlib import Path

data = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
assert data["externalAuthority"] == {
    "profile": "independent-omitted",
    "reason": "single-node deployment uses operator-dependent outage detection",
    "exposedPublicPlayerPaths": ["websocket"],
}
assert "observability_deadman_heartbeat_timestamp_seconds" not in data["mirroredSignals"]
assert [
    record["path"]
    for record in data["mirroredSignals"]["entrypath_blackbox_probe_success"]
] == ["websocket"]
assert "playerflow_canary_freshness_budget_seconds" not in data["mirroredSignals"]
PY

OMITTED_CANARY_AUTHORITY_EVIDENCE="$TMP_DIR/omitted-canary-authority.json"
OMITTED_CANARY_EVIDENCE="$TMP_DIR/omitted-canary-evidence.json"
cat >"$OMITTED_CANARY_AUTHORITY_EVIDENCE" <<'JSON'
{
  "profile": "independent-omitted",
  "reason": "single-node deployment uses operator-dependent outage detection",
  "exposedPublicPlayerPaths": ["websocket"],
  "detectionBudgetSeconds": 195
}
JSON

run_smoke_runner \
  --simulate \
  --external-authority-evidence "$OMITTED_CANARY_AUTHORITY_EVIDENCE" \
  --prometheus-mirrors omitted \
  --player-flow-canary advertised \
  --evidence-out "$OMITTED_CANARY_EVIDENCE" \
  --source "contract-test" \
  --canary-path websocket

python3 "$VALIDATOR" "$OMITTED_CANARY_EVIDENCE" >"$TMP_DIR/omitted-canary.out"
python3 - "$OMITTED_CANARY_EVIDENCE" <<'PY'
import json
import sys
from pathlib import Path

data = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
assert data["externalAuthority"]["profile"] == "independent-omitted"
assert data["externalAuthority"]["detectionBudgetSeconds"] == 195
assert data["mirroredSignals"]["playerflow_canary_freshness_budget_seconds"] == {
    "profile": "independent-omitted",
    "value": 195,
}
assert {
    record["profile"]
    for record in data["mirroredSignals"]["playerflow_canary_success"]
} == {"independent-omitted"}
assert {
    record["profile"]
    for record in data["mirroredSignals"]["playerflow_canary_latency_ms"]
} == {"independent-omitted"}
assert {
    record["profile"]
    for record in data["mirroredSignals"]["playerflow_canary_last_run_timestamp_seconds"]
} == {"independent-omitted"}
assert "entrypath_blackbox_probe_success" not in data["mirroredSignals"]
assert "observability_deadman_heartbeat_timestamp_seconds" not in data["mirroredSignals"]
PY

OMITTED_179_CANARY_AUTHORITY="$TMP_DIR/omitted-179-canary-authority.json"
python3 - "$OMITTED_CANARY_AUTHORITY_EVIDENCE" "$OMITTED_179_CANARY_AUTHORITY" <<'PY'
import json
import sys
from pathlib import Path

source = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
source["detectionBudgetSeconds"] = 179
Path(sys.argv[2]).write_text(json.dumps(source), encoding="utf-8")
PY

if run_smoke_runner \
  --simulate \
  --external-authority-evidence "$OMITTED_179_CANARY_AUTHORITY" \
  --prometheus-mirrors omitted \
  --player-flow-canary advertised \
  --evidence-out "$TMP_DIR/omitted-179-canary-evidence.json" \
  --source "contract-test" \
  --canary-path websocket >"$TMP_DIR/omitted-179-canary.out" 2>&1; then
  echo "runner unexpectedly accepted independent-omitted 179-second canary budget" >&2
  exit 1
fi
grep -q "detectionBudgetSeconds must be at least 180 seconds" "$TMP_DIR/omitted-179-canary.out"

MISSING_OMITTED_CANARY_BUDGET="$TMP_DIR/missing-omitted-canary-budget.json"
python3 - "$OMITTED_CANARY_AUTHORITY_EVIDENCE" "$MISSING_OMITTED_CANARY_BUDGET" <<'PY'
import json
import sys
from pathlib import Path

source = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
del source["detectionBudgetSeconds"]
Path(sys.argv[2]).write_text(json.dumps(source), encoding="utf-8")
PY

if run_smoke_runner \
  --simulate \
  --external-authority-evidence "$MISSING_OMITTED_CANARY_BUDGET" \
  --prometheus-mirrors omitted \
  --player-flow-canary advertised \
  --evidence-out "$TMP_DIR/missing-omitted-canary-budget-evidence.json" \
  --source "contract-test" \
  --canary-path websocket >"$TMP_DIR/missing-omitted-canary-budget.out" 2>&1; then
  echo "runner unexpectedly accepted advertised canary without a freshness budget" >&2
  exit 1
fi
grep -q "must define a positive finite detectionBudgetSeconds for an advertised player-flow canary" \
  "$TMP_DIR/missing-omitted-canary-budget.out"

NO_OPTIONAL_EVIDENCE="$TMP_DIR/no-optional-evidence.json"
run_smoke_runner \
  --simulate \
  --external-authority-evidence "$OMITTED_AUTHORITY_EVIDENCE" \
  --prometheus-mirrors omitted \
  --player-flow-canary omitted \
  --evidence-out "$NO_OPTIONAL_EVIDENCE" \
  --source "contract-test" \
  --canary-path websocket

python3 "$VALIDATOR" "$NO_OPTIONAL_EVIDENCE" >"$TMP_DIR/no-optional.out"

python3 - "$NO_OPTIONAL_EVIDENCE" <<'PY'
import json
import sys
from pathlib import Path

data = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
assert data["capabilities"] == {
    "prometheusMirrors": "omitted",
    "playerFlowCanary": "omitted",
}
assert data["mirroredSignals"] == {}
assert "canaryAlerts" not in data
PY

if grep -q "observability_deadman_heartbeat_timestamp_seconds" "$SUCCESS_METRICS"; then
  :
else
  echo "required profile metrics unexpectedly omitted the deadman mirror" >&2
  exit 1
fi

run_smoke_runner \
  --simulate \
  --external-authority-evidence "$OMITTED_AUTHORITY_EVIDENCE" \
  --player-flow-canary omitted \
  --evidence-out "$TMP_DIR/omitted-metrics-evidence.json" \
  --metrics-out "$TMP_DIR/omitted-metrics.prom" \
  --source "contract-test" \
  --canary-path websocket >/dev/null
if grep -q "observability_deadman_heartbeat_timestamp_seconds" "$TMP_DIR/omitted-metrics.prom"; then
  echo "omitted profile metrics unexpectedly included the deadman mirror" >&2
  exit 1
fi

REQUIRED_SINGLE_PATH_AUTHORITY_EVIDENCE="$TMP_DIR/required-single-path-authority.json"
REQUIRED_SINGLE_PATH_EVIDENCE="$TMP_DIR/required-single-path-evidence.json"
cat >"$REQUIRED_SINGLE_PATH_AUTHORITY_EVIDENCE" <<'JSON'
{
  "profile": "independent-required",
  "exposedPublicPlayerPaths": ["websocket"],
  "detectionBudgetSeconds": 195,
  "staleThresholdSeconds": 180,
  "observedStalenessSeconds": 60,
  "deadmanAuthority": {
    "status": "green",
    "evidenceRef": "pager://contract/single-path/deadman/2026-03-19T10:50:00Z",
    "pageEvidenceRef": "pager://contract/single-path/deadman/2026-03-19T10:50:00Z/delivery",
    "target": "contract-single-path-deadman-authority",
    "checkRef": "check://contract/single-path/deadman"
  },
  "publicPathChecks": {
    "websocket": {"status": "green", "evidenceRef": "probe://contract/single-path/websocket/2026-03-19T10:53:00Z", "pageEvidenceRef": "pager://contract/single-path/websocket/2026-03-19T10:50:00Z/delivery", "target": "contract-single-path-websocket", "lastSuccessfulProbeObservedAt": "2026-03-19T10:53:00Z", "observedProbeAgeSeconds": 120},
    "telnet": {"status": "not_applicable"}
  }
}
JSON

refresh_external_authority_fixture "$REQUIRED_SINGLE_PATH_AUTHORITY_EVIDENCE"

run_smoke_runner \
  --simulate \
  --external-authority-evidence "$REQUIRED_SINGLE_PATH_AUTHORITY_EVIDENCE" \
  --evidence-out "$REQUIRED_SINGLE_PATH_EVIDENCE" \
  --source "contract-test" \
  --canary-path telnet
python3 "$VALIDATOR" "$REQUIRED_SINGLE_PATH_EVIDENCE" >"$TMP_DIR/required-single-path.out"
python3 - "$REQUIRED_SINGLE_PATH_EVIDENCE" <<'PY'
import json
import sys
from pathlib import Path

data = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
assert data["externalAuthority"]["profile"] == "independent-required"
assert data["externalAuthority"]["exposedPublicPlayerPaths"] == ["websocket"]
assert data["externalAuthority"]["publicPathChecks"]["telnet"] == {
    "status": "not_applicable"
}
assert {
    (record["flow"], record["path"])
    for record in data["mirroredSignals"]["playerflow_canary_success"]
} == {("login", "websocket"), ("command", "websocket")}
assert {
    record["path"]
    for record in data["mirroredSignals"]["playerflow_canary_latency_ms"]
} == {"websocket"}
assert {
    record["path"]
    for record in data["mirroredSignals"]["playerflow_canary_last_run_timestamp_seconds"]
} == {"websocket"}
assert data["mirroredSignals"]["playerflow_canary_freshness_budget_seconds"] == {
    "profile": "independent-required",
    "value": 195,
}
PY

SYNTHESIZED_OMITTED_AUTHORITY_EVIDENCE="$TMP_DIR/synthesized-omitted-authority.json"
cat >"$SYNTHESIZED_OMITTED_AUTHORITY_EVIDENCE" <<'JSON'
{
  "profile": "independent-omitted",
  "reason": "single-node deployment uses operator-dependent outage detection",
  "exposedPublicPlayerPaths": ["websocket"],
  "deadmanAuthority": {
    "status": "green",
    "evidenceRef": "pager://synthesized/deadman",
    "target": "synthesized-deadman",
    "checkRef": "check://synthesized/deadman"
  }
}
JSON

if run_smoke_runner \
  --simulate \
  --external-authority-evidence "$SYNTHESIZED_OMITTED_AUTHORITY_EVIDENCE" \
  --player-flow-canary omitted \
  --evidence-out "$TMP_DIR/synthesized-omitted-evidence.json" \
  --source "contract-test" \
  --canary-path websocket >"$TMP_DIR/synthesized.out" 2>&1; then
  echo "runner unexpectedly accepted synthesized omitted authority" >&2
  exit 1
fi
grep -q "independent-omitted must not include external authority fields" "$TMP_DIR/synthesized.out"

echo "player-experience smoke runner contract checks passed"
