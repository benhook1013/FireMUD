#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
RUNNER="$ROOT_DIR/dev-tools/observability/run-player-experience-smoke.py"
VALIDATOR="$ROOT_DIR/dev-tools/observability/validate-player-experience-smoke-evidence.py"
TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

python3 - "$ROOT_DIR" <<'PY'
import importlib.util
import os
import sys
from pathlib import Path

root = Path(sys.argv[1]) / "dev-tools" / "observability"
sys.path.insert(0, str(root))
shared = __import__("numeric_validation")
observability_contract = __import__("observability_contract")
for name in ("run-player-experience-smoke.py", "validate-player-experience-smoke-evidence.py"):
    spec = importlib.util.spec_from_file_location(name, root / name)
    module = importlib.util.module_from_spec(spec)
    assert spec.loader is not None
    spec.loader.exec_module(module)
    assert module.is_finite_number is shared.is_finite_number
    assert module.is_bounded_positive_seconds is shared.is_bounded_positive_seconds
    if name == "run-player-experience-smoke.py":
        assert module.parse_bounded_positive_seconds is shared.parse_bounded_positive_seconds
        runner_module = module
    else:
        evidence_validator_module = module

assert runner_module.CANARY_IDENTITY_REQUIRED_FIELDS is observability_contract.CANARY_IDENTITY_REQUIRED_FIELDS
assert evidence_validator_module.CANARY_IDENTITY_REQUIRED_FIELDS is observability_contract.CANARY_IDENTITY_REQUIRED_FIELDS
assert runner_module.AUTHORITATIVE_CANARY_IDENTITY_VERIFIER_AVAILABLE is observability_contract.AUTHORITATIVE_CANARY_IDENTITY_VERIFIER_AVAILABLE
assert evidence_validator_module.AUTHORITATIVE_CANARY_IDENTITY_VERIFIER_AVAILABLE is observability_contract.AUTHORITATIVE_CANARY_IDENTITY_VERIFIER_AVAILABLE

os.environ["PLAYER_EXPERIENCE_QUERYABILITY_PROFILE"] = "staging"
os.environ["PLAYER_EXPERIENCE_QUERYABILITY_FRESHNESS_BUDGET_SECONDS"] = "7200"
env_config = runner_module.SmokeConfig.from_env("contract-test", "websocket", None)
assert env_config.queryability_profile == "staging"
assert env_config.queryability_freshness_budget_seconds == 7200

for invalid_budget in ("1e308", "1e-100"):
    try:
        runner_module.parse_optional_positive_finite_number(
            invalid_budget, "queryabilityFreshnessBudgetSeconds"
        )
    except ValueError as exc:
        assert "positive finite number" in str(exc)
    else:
        raise AssertionError(f"unsafe freshness budget was accepted: {invalid_budget}")

for key, malformed in (
    (runner_module.PROMETHEUS_MIRRORS_CAPABILITY, ["published"]),
    (runner_module.PLAYER_FLOW_CANARY_CAPABILITY, {"value": "advertised"}),
    (runner_module.PROMETHEUS_MIRRORS_CAPABILITY, False),
):
    try:
        runner_module.validate_capability(malformed, key)
    except ValueError as exc:
        assert "must be one of" in str(exc)
    except TypeError as exc:
        raise AssertionError(f"runner capability leaked TypeError: {key}={malformed!r}") from exc
    else:
        raise AssertionError(f"runner accepted malformed capability: {key}={malformed!r}")

for malformed_profile in (["independent-required"], {"profile": "independent-required"}, False):
    try:
        runner_module.validate_external_authority_shape(
            {
                "profile": malformed_profile,
                "exposedPublicPlayerPaths": ["websocket"],
            },
            runner_module.Path("malformed-profile-authority.json"),
        )
    except RuntimeError as exc:
        assert "must declare profile independent-required or independent-omitted" in str(exc)
    except TypeError as exc:
        raise AssertionError(f"runner profile leaked TypeError: {malformed_profile!r}") from exc
    else:
        raise AssertionError(f"runner accepted malformed profile: {malformed_profile!r}")

subsecond_config = runner_module.SmokeConfig.from_env(
    "contract-test",
    "websocket",
    None,
    queryability_profile="staging",
    queryability_freshness_budget_seconds="1e-6",
)
subsecond_record = runner_module.queryability_omission_record(
    subsecond_config, "2026-03-19T10:54:00Z"
)
observed_at = runner_module.dt.datetime.fromisoformat(
    subsecond_record["evidenceObservedAt"].replace("Z", "+00:00")
)
expires_at = runner_module.dt.datetime.fromisoformat(
    subsecond_record["evidenceExpiresAt"].replace("Z", "+00:00")
)
assert expires_at > observed_at

offset_record = runner_module.queryability_omission_record(
    subsecond_config, "2026-03-19T11:54:00+01:00"
)
assert offset_record["evidenceObservedAt"] == "2026-03-19T10:54:00Z"
assert offset_record["evidenceExpiresAt"] == "2026-03-19T10:54:00.000001Z"

overflow_config = runner_module.SmokeConfig.from_env(
    "contract-test",
    "websocket",
    None,
    queryability_profile="staging",
    queryability_freshness_budget_seconds="1e12",
)
try:
    runner_module.queryability_omission_record(
        overflow_config, "2026-03-19T10:54:00Z"
    )
except ValueError as exc:
    assert "representable datetime range" in str(exc)
except OverflowError as exc:
    raise AssertionError("near-1e12 queryability budget leaked OverflowError") from exc
else:
    raise AssertionError("near-1e12 queryability budget unexpectedly passed")

for invalid_budget in (-1, "195", float("nan"), float("inf")):
    try:
        runner_module.validate_external_authority_shape(
            {
                "profile": "independent-omitted",
                "reason": "contract test",
                "exposedPublicPlayerPaths": ["websocket"],
                "detectionBudgetSeconds": invalid_budget,
            },
            runner_module.Path("contract-authority.json"),
            canary_advertised=False,
            allow_unadvertised_canary_budget=True,
        )
    except RuntimeError as exc:
        assert (
            "positive finite number of seconds in the inclusive range "
            "[1e-06, 1000000000000]"
        ) in str(exc)
    else:
        raise AssertionError(
            f"invalid independent-omitted detection budget was accepted: {invalid_budget!r}"
        )

config = runner_module.SmokeConfig.from_env(
    "contract-test",
    "websocket",
    None,
    queryability_profile="staging",
    queryability_freshness_budget_seconds="7200",
)
config.player_flow_canary = "advertised"
config.player_flow_canary_identity = {"selfAttested": True}
signals = {
    "playerflow_canary_success": [{"flow": "login", "path": "websocket", "target": "gateway", "profile": "independent-required", "value": 1}],
    "playerflow_canary_latency_ms": [{"flow": "command", "path": "websocket", "target": "gateway", "profile": "independent-required", "value": 1}],
    "playerflow_canary_last_run_timestamp_seconds": [{"flow": "login", "path": "websocket", "target": "gateway", "profile": "independent-required", "value": 1}],
    "playerflow_canary_freshness_budget_seconds": {"profile": "independent-required", "value": 195},
}
evidence = runner_module.build_evidence(config, signals, {}, set())
assert evidence["capabilities"]["playerFlowCanary"] == "omitted"
assert "playerFlowCanaryIdentity" not in evidence
assert not any(key.startswith("playerflow_canary_") for key in evidence["mirroredSignals"])
assert "playerflow_canary_" not in runner_module.render_metrics(config, signals)
PY

CANARY_IDENTITY_EVIDENCE="$TMP_DIR/canary-identity-evidence.json"
python3 - "$CANARY_IDENTITY_EVIDENCE" <<'PY'
import json
import sys
from pathlib import Path

Path(sys.argv[1]).write_text(
    json.dumps(
        {
            "authority": "account-service",
            "classification": "synthetic",
            "analyticsSloExclusion": True,
            "credentials": {"nonDefault": True, "productionSafe": True},
            "transportCharacters": {
                "websocket": {"restricted": True, "isolated": True},
                "telnet": {"restricted": True, "isolated": True},
            },
            "evidenceRef": "account://contract-test/synthetic-canary",
        }
    ),
    encoding="utf-8",
)
PY

OMITTED_AUTHORITY_WITHOUT_CANARY_BUDGET="$TMP_DIR/omitted-authority-without-canary-budget.json"
cat >"$OMITTED_AUTHORITY_WITHOUT_CANARY_BUDGET" <<'JSON'
{
  "profile": "independent-omitted",
  "reason": "single-node deployment uses operator-dependent outage detection",
  "exposedPublicPlayerPaths": ["websocket"]
}
JSON

SMOKE_CONFIG_ENV_UNSETS=(
  -u PLAYER_EXPERIENCE_EXTERNAL_AUTHORITY_EVIDENCE
  -u PLAYER_EXPERIENCE_FAILURE_INJECTION
  -u PLAYER_EXPERIENCE_SOURCE
  -u PLAYER_EXPERIENCE_DEPLOYMENT_EVENT_ID
  -u PLAYER_EXPERIENCE_CANARY_PATH
  -u PLAYER_EXPERIENCE_PROMETHEUS_MIRRORS
  -u PLAYER_EXPERIENCE_PLAYER_FLOW_CANARY
  -u PLAYER_EXPERIENCE_SYNTHETIC_IDENTITY_EVIDENCE
  -u PLAYER_EXPERIENCE_QUERYABILITY_PROFILE
  -u PLAYER_EXPERIENCE_QUERYABILITY_FRESHNESS_BUDGET_SECONDS
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
SMOKE_CONFIG_ACCOUNT_ENV=(
  SMOKE_USERNAME=canary-contract@example.com
  SMOKE_PASSWORD=contract-canary-secret
)
SMOKE_CONFIG_CANARY_ENV=(
  "${SMOKE_CONFIG_ACCOUNT_ENV[@]}"
  PLAYER_EXPERIENCE_SYNTHETIC_IDENTITY_EVIDENCE="$CANARY_IDENTITY_EVIDENCE"
)
SMOKE_CONFIG_QUERYABILITY_ENV=(
  PLAYER_EXPERIENCE_QUERYABILITY_PROFILE=staging
  PLAYER_EXPERIENCE_QUERYABILITY_FRESHNESS_BUDGET_SECONDS=7200
)
SMOKE_CONFIG_DEFAULT_ENV=(
  "${SMOKE_CONFIG_CANARY_ENV[@]}"
  "${SMOKE_CONFIG_QUERYABILITY_ENV[@]}"
)

run_smoke_runner() {
  env "${SMOKE_CONFIG_ENV_UNSETS[@]}" \
    "${SMOKE_CONFIG_DEFAULT_ENV[@]}" \
    python3 "$RUNNER" "$@"
}

run_smoke_runner_with_deployment_ref() {
  local deployment_ref="$1"
  shift
  env "${SMOKE_CONFIG_ENV_UNSETS[@]}" \
    PLAYER_EXPERIENCE_DEPLOYMENT_REF="$deployment_ref" \
    "${SMOKE_CONFIG_DEFAULT_ENV[@]}" \
    python3 "$RUNNER" "$@"
}

run_smoke_runner_without_canary_identity() {
  env "${SMOKE_CONFIG_ENV_UNSETS[@]}" \
    SMOKE_USERNAME=demo@example.com \
    SMOKE_PASSWORD=swordfish \
    "${SMOKE_CONFIG_QUERYABILITY_ENV[@]}" \
    python3 "$RUNNER" "$@"
}

run_smoke_runner_without_queryability_config() {
  env "${SMOKE_CONFIG_ENV_UNSETS[@]}" \
    "${SMOKE_CONFIG_CANARY_ENV[@]}" \
    python3 "$RUNNER" "$@"
}

run_clean_python() {
  env "${SMOKE_CONFIG_ENV_UNSETS[@]}" \
    "${SMOKE_CONFIG_ENV_OVERRIDES[@]}" \
    "${SMOKE_CONFIG_DEFAULT_ENV[@]}" \
    python3 "$@"
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
assert runner.metric_target_for_path("websocket") == "gateway"
assert runner.metric_target_for_path("telnet") == "tcp_proxy"

def expect_invalid_path(path):
    try:
        runner.metric_target_for_path(path)
    except ValueError as exc:
        assert "Unsupported public player path" in str(exc), str(exc)
    else:
        raise AssertionError(f"expected invalid public player path: {path!r}")


for invalid_path in ([], {}, True, None, "unsupported"):
    expect_invalid_path(invalid_path)
retained_authority = {
    "profile": "independent-required",
    "exposedPublicPlayerPaths": ["websocket"],
    "detectionBudgetSeconds": 195,
    "staleThresholdSeconds": 180,
    "lastSuccessfulHeartbeatObservedAt": "2026-03-19T10:54:00Z",
}
expected_heartbeat = runner.dt.datetime.fromisoformat(
    "2026-03-19T10:54:00+00:00"
).timestamp()
assert runner.deadman_record(config, set(), retained_authority)["value"] == expected_heartbeat


def expect_runtime_error(action, error_fragment):
    try:
        action()
    except RuntimeError as exc:
        assert error_fragment in str(exc), str(exc)
    else:
        raise AssertionError(f"expected RuntimeError containing: {error_fragment}")


for field, error_fragment in (
    (
        "detectionBudgetSeconds",
        "detectionBudgetSeconds must be a positive finite number of seconds in the inclusive range [1e-06, 1000000000000]",
    ),
    (
        "staleThresholdSeconds",
        "staleThresholdSeconds must be a positive finite number of seconds in the inclusive range [1e-06, 1000000000000]",
    ),
):
    huge_authority = runner.simulated_external_authority("2026-03-19T10:55:00Z")
    for invalid_value in (10**1000, 1e12 + 1, 1e308):
        huge_authority[field] = invalid_value
        expect_runtime_error(
            lambda authority=huge_authority: runner.validate_external_authority_shape(
                authority,
                Path("huge-numeric-authority.json"),
                evaluation_epoch=runner.dt.datetime.fromisoformat(
                    "2026-03-19T10:55:00+00:00"
                ).timestamp(),
                canary_advertised=True,
            ),
            error_fragment,
        )

huge_authority = runner.simulated_external_authority("2026-03-19T10:55:00Z")
huge_authority["observedStalenessSeconds"] = 10**1000
expect_runtime_error(
    lambda authority=huge_authority: runner.validate_external_authority_shape(
        authority,
        Path("huge-numeric-authority.json"),
        evaluation_epoch=runner.dt.datetime.fromisoformat(
            "2026-03-19T10:55:00+00:00"
        ).timestamp(),
        canary_advertised=True,
    ),
    "must define a nonnegative finite observedStalenessSeconds",
)

expect_runtime_error(
    lambda: runner.stale_deadman_heartbeat_timestamp(
        {"staleThresholdSeconds": 10**1000}, 1773917700
    ),
    "staleThresholdSeconds must be a positive finite number of seconds in the inclusive range [1e-06, 1000000000000]",
)
expect_runtime_error(
    lambda: runner.stale_deadman_heartbeat_timestamp(
        {"staleThresholdSeconds": 1e308}, 1773917700
    ),
    "staleThresholdSeconds must be a positive finite number of seconds in the inclusive range [1e-06, 1000000000000]",
)
expect_runtime_error(
    lambda: runner.validate_public_path_freshness(
        {
            "lastSuccessfulProbeObservedAt": "2026-03-19T10:55:00Z",
            "observedProbeAgeSeconds": 10**1000,
            "status": "green",
        },
        "publicPathChecks.websocket",
        runner.dt.datetime.fromisoformat("2026-03-19T10:55:00+00:00").timestamp(),
        195,
        Path("huge-numeric-authority.json"),
    ),
    "must define a nonnegative finite publicPathChecks.websocket.observedProbeAgeSeconds",
)
mirror_only_config = runner.SmokeConfig.from_env(
    "contract-test",
    "websocket",
    None,
    prometheus_mirrors="published",
    player_flow_canary="omitted",
)
simulated_mirror = runner.simulated_signals(
    mirror_only_config, set(), retained_authority
)["observability_deadman_heartbeat_timestamp_seconds"]
assert simulated_mirror["value"] == expected_heartbeat
final_authority = dict(retained_authority)
final_authority["lastSuccessfulHeartbeatObservedAt"] = "2026-03-19T10:55:00Z"
mirrored_signals = {
    "observability_deadman_heartbeat_timestamp_seconds": dict(simulated_mirror)
}
runner.synchronize_deadman_heartbeat_mirror(
    mirrored_signals, final_authority, set()
)
assert mirrored_signals[
    "observability_deadman_heartbeat_timestamp_seconds"
]["value"] == runner.dt.datetime.fromisoformat(
    "2026-03-19T10:55:00+00:00"
).timestamp()
injected_mirror = {
    "observability_deadman_heartbeat_timestamp_seconds": {
        "source": "contract-test",
        "value": 123.0,
    }
}
runner.synchronize_deadman_heartbeat_mirror(
    injected_mirror, final_authority, {"deadman"}
)
assert injected_mirror[
    "observability_deadman_heartbeat_timestamp_seconds"
]["value"] == 123.0
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

if run_smoke_runner \
  --simulate \
  --failure-injection "unsupported-token" \
  --evidence-out "$TMP_DIR/unsupported-injection-evidence.json" \
  --source "contract-test" \
  --canary-path websocket >"$TMP_DIR/unsupported-injection.out" 2>&1; then
  echo "unsupported failure-injection token unexpectedly passed" >&2
  exit 1
fi
grep -q "unsupported failure-injection token(s): unsupported-token" \
  "$TMP_DIR/unsupported-injection.out"

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
    "lastSuccessfulHeartbeatObservedAt": "2026-03-19T10:54:00Z",
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
    "contract-test",
    "telnet",
    None,
    prometheus_mirrors="published",
    player_flow_canary="omitted",
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
    "contract-test",
    "telnet",
    None,
    prometheus_mirrors="omitted",
    player_flow_canary="omitted",
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
python3 - "$SUCCESS_EVIDENCE" <<'PY'
import json
import sys
from pathlib import Path

data = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
if data.get("capabilities") != {"prometheusMirrors": "published", "playerFlowCanary": "omitted"}:
    raise SystemExit(repr(data))
if any(key.startswith("playerflow_canary_") for key in data.get("mirroredSignals", {})):
    raise SystemExit(repr(data["mirroredSignals"]))
if "playerFlowCanaryIdentity" in data or "canaryAlerts" in data:
    raise SystemExit(repr(data))
PY

NO_IDENTITY_EVIDENCE="$TMP_DIR/no-identity-evidence.json"
run_smoke_runner_without_canary_identity \
  --simulate \
  --external-authority-evidence "$AUTHORITY_EVIDENCE" \
  --evidence-out "$NO_IDENTITY_EVIDENCE" \
  --source "contract-test" \
  --canary-path websocket >/dev/null 2>"$TMP_DIR/no-identity.out"
python3 - "$NO_IDENTITY_EVIDENCE" <<'PY'
import json
import sys
from pathlib import Path

data = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
if data.get("capabilities", {}).get("playerFlowCanary") != "omitted":
    raise SystemExit(repr(data))
if "playerFlowCanaryIdentity" in data or "canaryAlerts" in data:
    raise SystemExit(repr(data))
if any(key.startswith("playerflow_canary_") for key in data.get("mirroredSignals", {})):
    raise SystemExit(repr(data))
PY

NO_IDENTITY_OMITTED_BUDGET_EVIDENCE="$TMP_DIR/no-identity-omitted-budget-evidence.json"
run_smoke_runner_without_canary_identity \
  --simulate \
  --external-authority-evidence "$OMITTED_AUTHORITY_WITHOUT_CANARY_BUDGET" \
  --evidence-out "$NO_IDENTITY_OMITTED_BUDGET_EVIDENCE" \
  --source "contract-test" \
  --canary-path websocket >/dev/null 2>"$TMP_DIR/no-identity-omitted-budget.out"
python3 - "$NO_IDENTITY_OMITTED_BUDGET_EVIDENCE" <<'PY'
import json
import sys
from pathlib import Path

data = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
assert data["capabilities"]["playerFlowCanary"] == "omitted"
assert data["externalAuthority"] == {
    "profile": "independent-omitted",
    "reason": "single-node deployment uses operator-dependent outage detection",
    "exposedPublicPlayerPaths": ["websocket"],
}
assert not any(key.startswith("playerflow_canary_") for key in data["mirroredSignals"])
PY

OMITTED_AUTHORITY_UNDER_MINIMUM_BUDGET="$TMP_DIR/omitted-authority-under-minimum-budget.json"
python3 - "$OMITTED_AUTHORITY_WITHOUT_CANARY_BUDGET" "$OMITTED_AUTHORITY_UNDER_MINIMUM_BUDGET" <<'PY'
import json
import sys
from pathlib import Path

source = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
source["detectionBudgetSeconds"] = 179
Path(sys.argv[2]).write_text(json.dumps(source), encoding="utf-8")
PY

NO_IDENTITY_UNDER_MINIMUM_EVIDENCE="$TMP_DIR/no-identity-under-minimum-evidence.json"
run_smoke_runner_without_canary_identity \
  --simulate \
  --external-authority-evidence "$OMITTED_AUTHORITY_UNDER_MINIMUM_BUDGET" \
  --evidence-out "$NO_IDENTITY_UNDER_MINIMUM_EVIDENCE" \
  --source "contract-test" \
  --canary-path websocket >/dev/null 2>"$TMP_DIR/no-identity-under-minimum.out"
python3 - "$NO_IDENTITY_UNDER_MINIMUM_EVIDENCE" <<'PY'
import json
import sys
from pathlib import Path

data = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
assert data["capabilities"]["playerFlowCanary"] == "omitted"
assert "detectionBudgetSeconds" not in data["externalAuthority"]
assert not any(key.startswith("playerflow_canary_") for key in data["mirroredSignals"])
PY

FORGED_IDENTITY_EVIDENCE="$TMP_DIR/forged-identity-evidence.json"
python3 - "$SUCCESS_EVIDENCE" "$FORGED_IDENTITY_EVIDENCE" <<'PY'
import json
import sys
from pathlib import Path

data = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
data["capabilities"]["playerFlowCanary"] = "advertised"
data.pop("playerFlowCanaryIdentity", None)
Path(sys.argv[2]).write_text(json.dumps(data), encoding="utf-8")
PY
if python3 "$VALIDATOR" "$FORGED_IDENTITY_EVIDENCE" >"$TMP_DIR/forged-identity.out" 2>&1; then
  echo "advertised canary evidence without identity attestation unexpectedly passed" >&2
  exit 1
fi
grep -q "playerFlowCanaryIdentity is required" "$TMP_DIR/forged-identity.out"

if run_smoke_runner_without_queryability_config \
  --simulate \
  --external-authority-evidence "$AUTHORITY_EVIDENCE" \
  --evidence-out "$TMP_DIR/missing-queryability-config.json" \
  --source "contract-test" \
  --canary-path websocket >"$TMP_DIR/missing-queryability-config.out" 2>&1; then
  echo "runner unexpectedly emitted evidence without queryability profile/budget" >&2
  exit 1
fi
grep -q "queryability profile is required" "$TMP_DIR/missing-queryability-config.out"

ALERT_ONLY_EVIDENCE="$TMP_DIR/alert-only-evidence.json"
run_smoke_runner_with_deployment_ref "$DEPLOYMENT_REF" \
  --simulate \
  --external-authority-evidence "$AUTHORITY_EVIDENCE" \
  --failure-injection "PlayerFlowCanaryCommandFailed" \
  --evidence-out "$ALERT_ONLY_EVIDENCE" \
  --source "contract-test" \
  --canary-path websocket \
  --deployment-event-id "$DEPLOYMENT_EVENT_ID"

python3 - "$SUCCESS_EVIDENCE" "$ALERT_ONLY_EVIDENCE" <<'PY'
import copy
import json
import sys
from pathlib import Path


def normalized(data):
    data = copy.deepcopy(data)
    data.pop("verifiedAt", None)
    queryability = data.get("logPipelineQueryability")
    if isinstance(queryability, dict):
        queryability.pop("evidenceObservedAt", None)
        queryability.pop("evidenceExpiresAt", None)
    authority = data["externalAuthority"]
    for key in (
        "evidenceObservedAt",
        "lastSuccessfulHeartbeatObservedAt",
        "observedStalenessSeconds",
    ):
        authority.pop(key, None)
    for record in authority["publicPathChecks"].values():
        if isinstance(record, dict):
            record.pop("lastSuccessfulProbeObservedAt", None)
            record.pop("observedProbeAgeSeconds", None)
    signals = data["mirroredSignals"]
    signals["observability_deadman_heartbeat_timestamp_seconds"].pop("value", None)
    for key in (
        "playerflow_canary_success",
        "playerflow_canary_latency_ms",
        "playerflow_canary_last_run_timestamp_seconds",
        "playerflow_canary_freshness_budget_seconds",
    ):
        signals.pop(key, None)
    data.pop("canaryAlerts", None)
    return data


baseline = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
alert_only = json.loads(Path(sys.argv[2]).read_text(encoding="utf-8"))
assert normalized(alert_only) == normalized(baseline)
assert all(
    record["status"] == "green"
    for record in alert_only["externalAuthority"]["publicPathChecks"].values()
)
assert alert_only["externalAuthority"]["deadmanAuthority"]["status"] == "green"
assert all(
    record["value"] == 1
    for record in alert_only["mirroredSignals"]["entrypath_blackbox_probe_success"]
)
assert not any(key.startswith("playerflow_canary_") for key in alert_only["mirroredSignals"])
assert "canaryAlerts" not in alert_only
PY

python3 "$VALIDATOR" "$ALERT_ONLY_EVIDENCE" >"$TMP_DIR/alert-only-readiness.out"

SYNTHETIC_EVIDENCE="$TMP_DIR/synthetic-evidence.json"
run_smoke_runner \
  --simulate \
  --evidence-out "$SYNTHETIC_EVIDENCE" \
  --source "contract-test" \
  --canary-path websocket
python3 "$VALIDATOR" --allow-failure-evidence "$SYNTHETIC_EVIDENCE" \
  >"$TMP_DIR/synthetic.out"
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
assert data["capabilities"]["playerFlowCanary"] == "omitted"
assert "canaryAlerts" not in data
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
assert data["capabilities"]["playerFlowCanary"] == "omitted"
assert not any(key.startswith("playerflow_canary_") for key in data["mirroredSignals"])
PY

POST_EXECUTION_STALE_AUTHORITY="$TMP_DIR/post-execution-stale-authority.json"
refresh_external_authority_fixture "$AUTHORITY_EVIDENCE"
python3 - "$AUTHORITY_EVIDENCE" "$POST_EXECUTION_STALE_AUTHORITY" <<'PY'
import json
import sys
from pathlib import Path

source = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
source["detectionBudgetSeconds"] = 20
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
    clock["now"] += 30.0
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
    source["staleThresholdSeconds"] += 1
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
        assert "staleThresholdSeconds changed from 180 to 181" in str(exc)
    else:
        raise AssertionError("runner accepted authority snapshot changed during execution")
finally:
    sys.argv = original_argv
assert not evidence_path.exists()
PY

if grep -q 'playerflow_canary_' "$SUCCESS_METRICS"; then
  echo "runner emitted player-flow canary metrics without authoritative identity proof" >&2
  exit 1
fi
grep -q 'entrypath_blackbox_probe_success{path="websocket",target="gateway"} 1' "$SUCCESS_METRICS"
grep -q 'entrypath_blackbox_probe_success{path="telnet",target="tcp_proxy"} 1' "$SUCCESS_METRICS"
grep -q 'observability_deadman_heartbeat_timestamp_seconds{source="contract-test"}' "$SUCCESS_METRICS"

REQUIRED_180_MIRRORS_OMITTED_AUTHORITY="$TMP_DIR/required-180-mirrors-omitted-authority.json"
REQUIRED_180_MIRRORS_OMITTED_EVIDENCE="$TMP_DIR/required-180-mirrors-omitted-evidence.json"
refresh_external_authority_fixture "$AUTHORITY_EVIDENCE"
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
python3 "$VALIDATOR" "$REQUIRED_180_MIRRORS_OMITTED_EVIDENCE" \
  >"$TMP_DIR/required-180-mirrors-omitted.out"
python3 - "$REQUIRED_180_MIRRORS_OMITTED_EVIDENCE" <<'PY'
import json
import sys
from pathlib import Path

data = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
assert data["capabilities"]["playerFlowCanary"] == "omitted"
assert data["externalAuthority"]["detectionBudgetSeconds"] == 180
assert not any(key.startswith("playerflow_canary_") for key in data["mirroredSignals"])
assert "canaryAlerts" not in data
assert "entrypath_blackbox_probe_success" not in data["mirroredSignals"]
assert "observability_deadman_heartbeat_timestamp_seconds" not in data["mirroredSignals"]
PY

REQUIRED_179_CANARY_AUTHORITY="$TMP_DIR/required-179-canary-authority.json"
refresh_external_authority_fixture "$AUTHORITY_EVIDENCE"
python3 - "$AUTHORITY_EVIDENCE" "$REQUIRED_179_CANARY_AUTHORITY" <<'PY'
import json
import sys
from pathlib import Path

source = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
source["detectionBudgetSeconds"] = 179
Path(sys.argv[2]).write_text(json.dumps(source), encoding="utf-8")
PY
refresh_external_authority_fixture "$REQUIRED_179_CANARY_AUTHORITY"

if ! run_smoke_runner \
  --simulate \
  --external-authority-evidence "$REQUIRED_179_CANARY_AUTHORITY" \
  --player-flow-canary advertised \
  --evidence-out "$TMP_DIR/required-179-canary-evidence.json" \
  --source "contract-test" \
  --canary-path websocket >"$TMP_DIR/required-179-canary.out" 2>&1; then
  echo "runner failed to downgrade the unverified canary budget to omitted" >&2
  exit 1
fi
python3 - "$TMP_DIR/required-179-canary-evidence.json" <<'PY'
import json
import sys
from pathlib import Path

data = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
assert data["capabilities"]["playerFlowCanary"] == "omitted"
assert "playerFlowCanaryIdentity" not in data
assert not any(key.startswith("playerflow_canary_") for key in data["mirroredSignals"])
assert "canaryAlerts" not in data
PY

MISSING_TIMESTAMP_AUTHORITY="$TMP_DIR/missing-timestamp-authority.json"
refresh_external_authority_fixture "$AUTHORITY_EVIDENCE"
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
refresh_external_authority_fixture "$AUTHORITY_EVIDENCE"
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
grep -q "staleThresholdSeconds must be a positive finite number of seconds in the inclusive range \[1e-06, 1000000000000\]" "$TMP_DIR/missing-stale-threshold.out"

INVALID_STALE_THRESHOLD_AUTHORITY="$TMP_DIR/invalid-stale-threshold-authority.json"
refresh_external_authority_fixture "$AUTHORITY_EVIDENCE"
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
grep -q "staleThresholdSeconds must be a positive finite number of seconds in the inclusive range \[1e-06, 1000000000000\]" "$TMP_DIR/invalid-stale-threshold.out"

NEGATIVE_STALE_THRESHOLD_AUTHORITY="$TMP_DIR/negative-stale-threshold-authority.json"
refresh_external_authority_fixture "$AUTHORITY_EVIDENCE"
python3 - "$AUTHORITY_EVIDENCE" "$NEGATIVE_STALE_THRESHOLD_AUTHORITY" <<'PY'
import json
import sys
from pathlib import Path

source = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
source["staleThresholdSeconds"] = -1
Path(sys.argv[2]).write_text(json.dumps(source), encoding="utf-8")
PY

if run_smoke_runner \
  --simulate \
  --external-authority-evidence "$NEGATIVE_STALE_THRESHOLD_AUTHORITY" \
  --evidence-out "$TMP_DIR/negative-stale-threshold-evidence.json" \
  --source "contract-test" \
  --canary-path websocket >"$TMP_DIR/negative-stale-threshold.out" 2>&1; then
  echo "runner unexpectedly accepted negative stale threshold" >&2
  exit 1
fi
grep -q "staleThresholdSeconds must be a positive finite number of seconds in the inclusive range \[1e-06, 1000000000000\]" "$TMP_DIR/negative-stale-threshold.out"

OVERLARGE_DETECTION_BUDGET_AUTHORITY="$TMP_DIR/overlarge-detection-budget-authority.json"
refresh_external_authority_fixture "$AUTHORITY_EVIDENCE"
python3 - "$AUTHORITY_EVIDENCE" "$OVERLARGE_DETECTION_BUDGET_AUTHORITY" <<'PY'
import json
import sys
from pathlib import Path

source = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
source["detectionBudgetSeconds"] = 1e308
Path(sys.argv[2]).write_text(json.dumps(source), encoding="utf-8")
PY

OVERLARGE_DETECTION_BUDGET_EVIDENCE="$TMP_DIR/overlarge-detection-budget-evidence.json"
if run_smoke_runner \
  --simulate \
  --external-authority-evidence "$OVERLARGE_DETECTION_BUDGET_AUTHORITY" \
  --evidence-out "$OVERLARGE_DETECTION_BUDGET_EVIDENCE" \
  --source "contract-test" \
  --canary-path websocket >"$TMP_DIR/overlarge-detection-budget.out" 2>&1; then
  echo "runner unexpectedly accepted overlarge detection budget" >&2
  exit 1
fi
grep -q "detectionBudgetSeconds must be a positive finite number of seconds in the inclusive range \[1e-06, 1000000000000\]" "$TMP_DIR/overlarge-detection-budget.out"
test ! -e "$OVERLARGE_DETECTION_BUDGET_EVIDENCE"

OVERLARGE_STALE_THRESHOLD_AUTHORITY="$TMP_DIR/overlarge-stale-threshold-authority.json"
refresh_external_authority_fixture "$AUTHORITY_EVIDENCE"
python3 - "$AUTHORITY_EVIDENCE" "$OVERLARGE_STALE_THRESHOLD_AUTHORITY" <<'PY'
import json
import sys
from pathlib import Path

source = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
source["staleThresholdSeconds"] = 1e308
Path(sys.argv[2]).write_text(json.dumps(source), encoding="utf-8")
PY

OVERLARGE_STALE_THRESHOLD_EVIDENCE="$TMP_DIR/overlarge-stale-threshold-evidence.json"
if run_smoke_runner \
  --simulate \
  --external-authority-evidence "$OVERLARGE_STALE_THRESHOLD_AUTHORITY" \
  --evidence-out "$OVERLARGE_STALE_THRESHOLD_EVIDENCE" \
  --source "contract-test" \
  --canary-path websocket >"$TMP_DIR/overlarge-stale-threshold.out" 2>&1; then
  echo "runner unexpectedly accepted overlarge stale threshold" >&2
  exit 1
fi
grep -q "staleThresholdSeconds must be a positive finite number of seconds in the inclusive range \[1e-06, 1000000000000\]" "$TMP_DIR/overlarge-stale-threshold.out"
test ! -e "$OVERLARGE_STALE_THRESHOLD_EVIDENCE"

run_clean_python - "$RUNNER" "$AUTHORITY_EVIDENCE" <<'PY'
import copy
import importlib.util
import json
import sys
from datetime import datetime
from pathlib import Path

runner_path = Path(sys.argv[1])
authority_path = Path(sys.argv[2])
spec = importlib.util.spec_from_file_location("player_experience_smoke", runner_path)
assert spec is not None and spec.loader is not None
runner = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = runner
spec.loader.exec_module(runner)

source = json.loads(authority_path.read_text(encoding="utf-8"))
evaluation_epoch = datetime.fromisoformat(
    source["evidenceObservedAt"].replace("Z", "+00:00")
).timestamp()
cases = {
    "missing": (lambda data: data.pop("lastSuccessfulHeartbeatObservedAt"),
                 "lastSuccessfulHeartbeatObservedAt is required"),
    "non-string": (lambda data: data.__setitem__("lastSuccessfulHeartbeatObservedAt", 0),
                   "lastSuccessfulHeartbeatObservedAt must be an RFC3339 UTC timestamp ending in Z"),
    "non-Z": (lambda data: data.__setitem__("lastSuccessfulHeartbeatObservedAt", "2026-03-19T10:54:00+00:00"),
              "lastSuccessfulHeartbeatObservedAt must be an RFC3339 UTC timestamp ending in Z"),
    "unparseable": (lambda data: data.__setitem__("lastSuccessfulHeartbeatObservedAt", "not-a-timestampZ"),
                    "lastSuccessfulHeartbeatObservedAt is invalid"),
    "non-positive": (lambda data: data.__setitem__("lastSuccessfulHeartbeatObservedAt", "1970-01-01T00:00:00Z"),
                     "requires a positive finite lastSuccessfulHeartbeatObservedAt timestamp"),
}
for name, (mutate, expected) in cases.items():
    candidate = copy.deepcopy(source)
    mutate(candidate)
    try:
        if name == "non-positive":
            runner.external_authority_heartbeat_timestamp(candidate)
        else:
            runner.validate_external_authority_shape(
                candidate,
                authority_path,
                evaluation_epoch=evaluation_epoch,
            )
    except (RuntimeError, TypeError) as exc:
        assert expected in str(exc), (name, str(exc))
    else:
        raise AssertionError(f"runner accepted invalid heartbeat case: {name}")
PY

OVER_THRESHOLD_DEADMAN_AUTHORITY="$TMP_DIR/over-threshold-deadman-authority.json"
refresh_external_authority_fixture "$AUTHORITY_EVIDENCE"
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
refresh_external_authority_fixture "$AUTHORITY_EVIDENCE"
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
refresh_external_authority_fixture "$AUTHORITY_EVIDENCE"
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
refresh_external_authority_fixture "$AUTHORITY_EVIDENCE"
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
refresh_external_authority_fixture "$AUTHORITY_EVIDENCE"
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
refresh_external_authority_fixture "$AUTHORITY_EVIDENCE"
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
refresh_external_authority_fixture "$AUTHORITY_EVIDENCE"
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
refresh_external_authority_fixture "$AUTHORITY_EVIDENCE"
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
refresh_external_authority_fixture "$AUTHORITY_EVIDENCE"
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
refresh_external_authority_fixture "$AUTHORITY_EVIDENCE"
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
refresh_external_authority_fixture "$AUTHORITY_EVIDENCE"
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
refresh_external_authority_fixture "$AUTHORITY_EVIDENCE"
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
refresh_external_authority_fixture "$AUTHORITY_EVIDENCE"
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
refresh_external_authority_fixture "$AUTHORITY_EVIDENCE"
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
refresh_external_authority_fixture "$AUTHORITY_EVIDENCE"
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
refresh_external_authority_fixture "$AUTHORITY_EVIDENCE"
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
RED_SOURCE_AUTHORITY_EVIDENCE="$TMP_DIR/red-source-authority.json"
RED_SOURCE_EVIDENCE="$TMP_DIR/red-source-evidence.json"
python3 - "$AUTHORITY_EVIDENCE" "$RED_SOURCE_AUTHORITY_EVIDENCE" <<'PY'
import json
import sys
from pathlib import Path

source = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
source["deadmanAuthority"]["status"] = "red"
source["deadmanAuthority"].pop("pageEvidenceRef")
source["publicPathChecks"]["websocket"]["status"] = "red"
source["publicPathChecks"]["websocket"].pop("pageEvidenceRef")
Path(sys.argv[2]).write_text(json.dumps(source), encoding="utf-8")
PY

NULL_DEADMAN_PAGE_AUTHORITY_EVIDENCE="$TMP_DIR/null-deadman-page-authority.json"
NULL_PUBLIC_PATH_PAGE_AUTHORITY_EVIDENCE="$TMP_DIR/null-public-path-page-authority.json"
python3 - \
  "$RED_SOURCE_AUTHORITY_EVIDENCE" \
  "$NULL_DEADMAN_PAGE_AUTHORITY_EVIDENCE" \
  "$NULL_PUBLIC_PATH_PAGE_AUTHORITY_EVIDENCE" <<'PY'
import copy
import json
import sys
from pathlib import Path

source = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
null_deadman = copy.deepcopy(source)
null_deadman["deadmanAuthority"]["pageEvidenceRef"] = None
Path(sys.argv[2]).write_text(json.dumps(null_deadman), encoding="utf-8")
null_public_path = copy.deepcopy(source)
null_public_path["publicPathChecks"]["websocket"]["pageEvidenceRef"] = None
Path(sys.argv[3]).write_text(json.dumps(null_public_path), encoding="utf-8")
PY

if run_smoke_runner \
  --simulate \
  --allow-failure-evidence \
  --external-authority-evidence "$NULL_DEADMAN_PAGE_AUTHORITY_EVIDENCE" \
  --evidence-out "$TMP_DIR/rejected-null-deadman-page-evidence.json" \
  --source "contract-test" \
  --canary-path websocket >"$TMP_DIR/null-deadman-page.out" 2>&1; then
  echo "runner unexpectedly accepted a present null red deadman page reference" >&2
  exit 1
fi
grep -q "must define deadmanAuthority.pageEvidenceRef when present" \
  "$TMP_DIR/null-deadman-page.out"

if run_smoke_runner \
  --simulate \
  --allow-failure-evidence \
  --external-authority-evidence "$NULL_PUBLIC_PATH_PAGE_AUTHORITY_EVIDENCE" \
  --evidence-out "$TMP_DIR/rejected-null-public-path-page-evidence.json" \
  --source "contract-test" \
  --canary-path websocket >"$TMP_DIR/null-public-path-page.out" 2>&1; then
  echo "runner unexpectedly accepted a present null red public-path page reference" >&2
  exit 1
fi
grep -q "must define publicPathChecks.websocket.pageEvidenceRef when present" \
  "$TMP_DIR/null-public-path-page.out"

RED_SOURCE_WITH_PAGE_AUTHORITY_EVIDENCE="$TMP_DIR/red-source-with-page-authority.json"
RED_SOURCE_WITH_PAGE_EVIDENCE="$TMP_DIR/red-source-with-page-evidence.json"
python3 - "$AUTHORITY_EVIDENCE" "$RED_SOURCE_WITH_PAGE_AUTHORITY_EVIDENCE" <<'PY'
import json
import sys
from pathlib import Path

source = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
source["deadmanAuthority"]["status"] = "red"
source["publicPathChecks"]["websocket"]["status"] = "red"
Path(sys.argv[2]).write_text(json.dumps(source), encoding="utf-8")
PY
run_smoke_runner \
  --simulate \
  --allow-failure-evidence \
  --external-authority-evidence "$RED_SOURCE_WITH_PAGE_AUTHORITY_EVIDENCE" \
  --evidence-out "$RED_SOURCE_WITH_PAGE_EVIDENCE" \
  --source "contract-test" \
  --canary-path websocket
python3 "$VALIDATOR" --allow-failure-evidence "$RED_SOURCE_WITH_PAGE_EVIDENCE" \
  >"$TMP_DIR/red-source-with-page-incident.out"
python3 - "$RED_SOURCE_WITH_PAGE_EVIDENCE" <<'PY'
import json
import sys
from pathlib import Path

data = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
assert data["externalAuthority"]["deadmanAuthority"]["pageEvidenceRef"]
assert data["externalAuthority"]["publicPathChecks"]["websocket"]["pageEvidenceRef"]
PY

if run_smoke_runner \
  --simulate \
  --external-authority-evidence "$RED_SOURCE_AUTHORITY_EVIDENCE" \
  --evidence-out "$TMP_DIR/rejected-red-source-evidence.json" \
  --source "contract-test" \
  --canary-path websocket >"$TMP_DIR/rejected-red-source.out" 2>&1; then
  echo "runner unexpectedly accepted red retained authority as readiness input" >&2
  exit 1
fi
grep -q "requires deadmanAuthority.status=green" "$TMP_DIR/rejected-red-source.out"

run_smoke_runner \
  --simulate \
  --allow-failure-evidence \
  --external-authority-evidence "$RED_SOURCE_AUTHORITY_EVIDENCE" \
  --evidence-out "$RED_SOURCE_EVIDENCE" \
  --source "contract-test" \
  --canary-path websocket
if python3 "$VALIDATOR" "$RED_SOURCE_EVIDENCE" \
  >"$TMP_DIR/red-source-readiness.out" 2>&1; then
  echo "red retained source evidence unexpectedly authorized readiness" >&2
  exit 1
fi
grep -q "deadmanAuthority.status must be green" "$TMP_DIR/red-source-readiness.out"
python3 "$VALIDATOR" --allow-failure-evidence "$RED_SOURCE_EVIDENCE" \
  >"$TMP_DIR/red-source-incident.out"
python3 - "$RED_SOURCE_EVIDENCE" <<'PY'
import json
import sys
from datetime import datetime
from pathlib import Path

data = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
assert data["externalAuthorityProvenance"] == "retained-external"
assert data["externalAuthority"]["deadmanAuthority"]["status"] == "red"
assert data["externalAuthority"]["publicPathChecks"]["websocket"]["status"] == "red"
assert "pageEvidenceRef" not in data["externalAuthority"]["deadmanAuthority"]
assert "pageEvidenceRef" not in data["externalAuthority"]["publicPathChecks"]["websocket"]
assert data["mirroredSignals"][
    "observability_deadman_heartbeat_timestamp_seconds"
]["value"] == datetime.fromisoformat(
    data["externalAuthority"]["lastSuccessfulHeartbeatObservedAt"].replace(
        "Z", "+00:00"
    )
).timestamp()
PY

LOGIN_FAILURE_EVIDENCE="$TMP_DIR/login-failure-evidence.json"
refresh_external_authority_fixture "$AUTHORITY_EVIDENCE"
run_smoke_runner \
  --simulate \
  --external-authority-evidence "$AUTHORITY_EVIDENCE" \
  --failure-injection "login" \
  --evidence-out "$LOGIN_FAILURE_EVIDENCE" \
  --source "contract-test" \
  --canary-path websocket
python3 "$VALIDATOR" --allow-failure-evidence "$LOGIN_FAILURE_EVIDENCE" \
  >"$TMP_DIR/login-failure-incident.out"
python3 - "$LOGIN_FAILURE_EVIDENCE" <<'PY'
import json
import sys
from pathlib import Path

data = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
assert data["capabilities"]["playerFlowCanary"] == "omitted"
assert not any(key.startswith("playerflow_canary_") for key in data["mirroredSignals"])
assert "canaryAlerts" not in data
PY

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
from datetime import datetime
from pathlib import Path

data = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
assert data["externalAuthorityProvenance"] == "retained-external"
assert data["externalAuthority"]["deadmanAuthority"]["status"] == "green"
assert data["externalAuthority"]["publicPathChecks"]["websocket"]["status"] == "green"
assert data["externalAuthority"]["publicPathChecks"]["telnet"]["status"] == "green"
deadman_timestamp = data["mirroredSignals"]["observability_deadman_heartbeat_timestamp_seconds"]["value"]
assert deadman_timestamp > 0
verified_at = datetime.fromisoformat(data["verifiedAt"].replace("Z", "+00:00"))
assert deadman_timestamp <= verified_at.timestamp()
assert any(
    record["path"] == "websocket" and record["value"] == 0
    for record in data["mirroredSignals"]["entrypath_blackbox_probe_success"]
)
assert data["capabilities"]["playerFlowCanary"] == "omitted"
assert not any(key.startswith("playerflow_canary_") for key in data["mirroredSignals"])
assert "canaryAlerts" not in data
PY

python3 "$VALIDATOR" --allow-failure-evidence "$FAIL_EVIDENCE" \
  >"$TMP_DIR/failure-incident.out"

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

python3 "$VALIDATOR" "$OMITTED_CANARY_EVIDENCE" \
  >"$TMP_DIR/omitted-canary.out"
python3 - "$OMITTED_CANARY_EVIDENCE" <<'PY'
import json
import sys
from pathlib import Path

data = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
assert data["externalAuthority"]["profile"] == "independent-omitted"
assert "detectionBudgetSeconds" not in data["externalAuthority"]
assert data["capabilities"]["playerFlowCanary"] == "omitted"
assert not any(key.startswith("playerflow_canary_") for key in data["mirroredSignals"])
assert "canaryAlerts" not in data
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

if ! run_smoke_runner \
  --simulate \
  --external-authority-evidence "$OMITTED_179_CANARY_AUTHORITY" \
  --prometheus-mirrors omitted \
  --player-flow-canary advertised \
  --evidence-out "$TMP_DIR/omitted-179-canary-evidence.json" \
  --source "contract-test" \
  --canary-path websocket >"$TMP_DIR/omitted-179-canary.out" 2>&1; then
  echo "runner failed to downgrade independent-omitted canary to omitted" >&2
  exit 1
fi
python3 - "$TMP_DIR/omitted-179-canary-evidence.json" <<'PY'
import json
import sys
from pathlib import Path

data = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
assert data["capabilities"]["playerFlowCanary"] == "omitted"
assert "playerFlowCanaryIdentity" not in data
assert not any(key.startswith("playerflow_canary_") for key in data["mirroredSignals"])
assert "canaryAlerts" not in data
PY

MISSING_OMITTED_CANARY_BUDGET="$TMP_DIR/missing-omitted-canary-budget.json"
python3 - "$OMITTED_CANARY_AUTHORITY_EVIDENCE" "$MISSING_OMITTED_CANARY_BUDGET" <<'PY'
import json
import sys
from pathlib import Path

source = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
del source["detectionBudgetSeconds"]
Path(sys.argv[2]).write_text(json.dumps(source), encoding="utf-8")
PY

if ! run_smoke_runner \
  --simulate \
  --external-authority-evidence "$MISSING_OMITTED_CANARY_BUDGET" \
  --prometheus-mirrors omitted \
  --player-flow-canary advertised \
  --evidence-out "$TMP_DIR/missing-omitted-canary-budget-evidence.json" \
  --source "contract-test" \
  --canary-path websocket >"$TMP_DIR/missing-omitted-canary-budget.out" 2>&1; then
  echo "runner failed to downgrade canary without authority budget" >&2
  exit 1
fi
python3 - "$TMP_DIR/missing-omitted-canary-budget-evidence.json" <<'PY'
import json
import sys
from pathlib import Path

data = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
assert data["capabilities"]["playerFlowCanary"] == "omitted"
assert "playerFlowCanaryIdentity" not in data
assert not any(key.startswith("playerflow_canary_") for key in data["mirroredSignals"])
assert "canaryAlerts" not in data
PY

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

if ! grep -q "observability_deadman_heartbeat_timestamp_seconds" "$SUCCESS_METRICS"; then
  echo "required profile metrics unexpectedly omitted the deadman mirror" >&2
  exit 1
fi
if grep -q "observability_deadman_stale" "$SUCCESS_METRICS"; then
  echo "required profile metrics unexpectedly included the monitor-owned stale decision" >&2
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
python3 "$VALIDATOR" "$REQUIRED_SINGLE_PATH_EVIDENCE" \
  >"$TMP_DIR/required-single-path.out"
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
assert data["capabilities"]["playerFlowCanary"] == "omitted"
assert not any(key.startswith("playerflow_canary_") for key in data["mirroredSignals"])
assert "canaryAlerts" not in data
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

run_clean_python - "$RUNNER" <<'PY'
import importlib.util
import sys
from pathlib import Path

runner_path = Path(sys.argv[1])
spec = importlib.util.spec_from_file_location("player_experience_smoke_failure_capture", runner_path)
assert spec is not None and spec.loader is not None
runner = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = runner
spec.loader.exec_module(runner)

config = runner.SmokeConfig.from_env("contract-test", "websocket", None)
config.username = "non-default@example.com"
config.password = "non-default-password"
config.player_flow_canary_identity = {
    "authority": "account-service",
    "classification": "synthetic",
    "analyticsSloExclusion": True,
    "credentials": {"nonDefault": True, "productionSafe": True},
    "transportCharacters": {
        "websocket": {"restricted": True, "isolated": True}
    },
    "evidenceRef": "account://contract-test/synthetic-canary",
}
config._validated_player_flow_canary_paths = frozenset({"websocket"})

direct_steps = [
    {"label": "LOGIN", "latencyMs": 10},
    {"label": "LOOK", "latencyMs": 20},
]

def producer_results(player_flow_canary):
    def websocket_config():
        config = runner.SmokeConfig.from_env("contract-test", "websocket", None)
        config.player_flow_canary = player_flow_canary
        if player_flow_canary == "advertised":
            config.username = "non-default@example.com"
            config.password = "non-default-password"
            config.player_flow_canary_identity = {
                "authority": "account-service",
                "classification": "synthetic",
                "analyticsSloExclusion": True,
                "credentials": {"nonDefault": True, "productionSafe": True},
                "transportCharacters": {
                    "websocket": {"restricted": True, "isolated": True}
                },
                "evidenceRef": "account://contract-test/synthetic-canary",
            }
            config._validated_player_flow_canary_paths = frozenset({"websocket"})
        return config

    def telnet_config():
        config = runner.SmokeConfig.from_env("contract-test", "telnet", None)
        config.player_flow_canary = player_flow_canary
        if player_flow_canary == "advertised":
            config.username = "non-default@example.com"
            config.password = "non-default-password"
            config.player_flow_canary_identity = {
                "authority": "account-service",
                "classification": "synthetic",
                "analyticsSloExclusion": True,
                "credentials": {"nonDefault": True, "productionSafe": True},
                "transportCharacters": {
                    "telnet": {"restricted": True, "isolated": True}
                },
                "evidenceRef": "account://contract-test/synthetic-canary",
            }
            config._validated_player_flow_canary_paths = frozenset({"telnet"})
        return config

    return [
        runner.run_playerflow_canary(websocket_config(), {"login"}),
        runner.run_playerflow_canaries(
            websocket_config(), {"login"}, {"websocket"}, "independent-required"
        ),
        runner.failed_canary_records(websocket_config()),
        runner.simulated_playerflow_canaries(
            websocket_config(), {"login"}, {"websocket"}, "independent-required"
        ),
        runner.run_websocket_canary(websocket_config(), {"login"}),
        runner.run_telnet_canary(telnet_config(), {"login"}),
        runner.simulated_canary_records(websocket_config(), set()),
        runner.canary_records_from_steps(websocket_config(), direct_steps),
    ]


def assert_producers_empty(player_flow_canary, verifier_available):
    runner.AUTHORITATIVE_CANARY_IDENTITY_VERIFIER_AVAILABLE = verifier_available
    for result in producer_results(player_flow_canary):
        assert all(not records for records in result), (player_flow_canary, result)


def assert_producers_emit(player_flow_canary, verifier_available):
    runner.AUTHORITATIVE_CANARY_IDENTITY_VERIFIER_AVAILABLE = verifier_available
    for result in producer_results(player_flow_canary):
        assert result and all(records for records in result), (player_flow_canary, result)


assert_producers_empty("advertised", False)
assert_producers_empty("omitted", False)
assert_producers_empty("omitted", True)
assert_producers_emit("advertised", True)

runner.AUTHORITATIVE_CANARY_IDENTITY_VERIFIER_AVAILABLE = True
for identity in (
    None,
    [],
    True,
    {"authority": "account-service"},
    {
        "authority": "account-service",
        "classification": "synthetic",
        "analyticsSloExclusion": True,
        "credentials": {"nonDefault": True, "productionSafe": True},
        "transportCharacters": {
            "websocket": {"restricted": False, "isolated": True}
        },
        "evidenceRef": "account://contract-test/synthetic-canary",
    },
):
    malformed = runner.SmokeConfig.from_env("contract-test", "websocket", None)
    malformed.player_flow_canary = "advertised"
    malformed.username = "non-default@example.com"
    malformed.password = "non-default-password"
    malformed._validated_player_flow_canary_paths = frozenset({"websocket"})
    malformed.player_flow_canary_identity = identity
    assert not runner.canary_producer_is_authorized(malformed)
    assert not any(runner.simulated_canary_records(malformed, set()))

valid = runner.SmokeConfig.from_env("contract-test", "websocket", None)
valid.player_flow_canary = "advertised"
valid.username = "non-default@example.com"
valid.password = "non-default-password"
valid.player_flow_canary_identity = {
    "authority": "account-service",
    "classification": "synthetic",
    "analyticsSloExclusion": True,
    "credentials": {"nonDefault": True, "productionSafe": True},
    "transportCharacters": {
        "websocket": {"restricted": True, "isolated": True}
    },
    "evidenceRef": "account://contract-test/synthetic-canary",
}
valid._validated_player_flow_canary_paths = frozenset({"websocket"})
assert runner.canary_producer_is_authorized(valid)
assert all(runner.simulated_canary_records(valid, set()))

def assert_build_downgrades(config):
    signals = {
        "playerflow_canary_success": [{"value": 1}],
        "playerflow_canary_latency_ms": [{"value": 1}],
        "playerflow_canary_last_run_timestamp_seconds": [{"value": 1}],
        "playerflow_canary_freshness_budget_seconds": {"value": 195},
    }
    evidence = runner.build_evidence(config, signals, {}, set())
    assert evidence["capabilities"]["playerFlowCanary"] == "omitted"
    assert "playerFlowCanaryIdentity" not in evidence
    assert "canaryAlerts" not in evidence
    assert not any(key.startswith("playerflow_canary_") for key in signals)

for invalid_identity in (
    None,
    [],
    True,
    {"authority": "account-service"},
    {
        "authority": "account-service",
        "classification": "synthetic",
        "analyticsSloExclusion": True,
        "credentials": {"nonDefault": True, "productionSafe": True},
        "transportCharacters": {
            "websocket": {"restricted": False, "isolated": True}
        },
        "evidenceRef": "account://contract-test/synthetic-canary",
    },
):
    invalid = runner.copy.copy(valid)
    invalid.player_flow_canary_identity = invalid_identity
    assert_build_downgrades(invalid)

producer_first_invalid = runner.copy.copy(valid)
producer_first_invalid.player_flow_canary_identity = []
assert not runner.canary_producer_is_authorized(producer_first_invalid)
stale_signals = {
    "playerflow_canary_success": [{"value": 1}],
    "playerflow_canary_latency_ms": [{"value": 1}],
    "playerflow_canary_last_run_timestamp_seconds": [{"value": 1}],
    "playerflow_canary_freshness_budget_seconds": {"value": 195},
}
producer_first_evidence = runner.build_evidence(
    producer_first_invalid, stale_signals, {}, set()
)
assert producer_first_invalid.player_flow_canary == "omitted"
assert producer_first_evidence["capabilities"]["playerFlowCanary"] == "omitted"
assert "playerFlowCanaryIdentity" not in producer_first_evidence
assert "canaryAlerts" not in producer_first_evidence
assert not stale_signals

for invalid_path_state in (["websocket"], frozenset({"unknown"}), frozenset()):
    invalid_paths = runner.copy.copy(valid)
    invalid_paths._validated_player_flow_canary_paths = invalid_path_state
    assert_build_downgrades(invalid_paths)

selected_path_mismatch = runner.copy.copy(valid)
selected_path_mismatch.canary_path = "telnet"
assert_build_downgrades(selected_path_mismatch)

valid_evidence = runner.build_evidence(
    valid,
    {"playerflow_canary_success": [{"value": 1}]},
    {},
    set(),
)
assert valid_evidence["capabilities"]["playerFlowCanary"] == "advertised"
assert "playerFlowCanaryIdentity" in valid_evidence
assert len(valid_evidence["canaryAlerts"]) == 4
runner.AUTHORITATIVE_CANARY_IDENTITY_VERIFIER_AVAILABLE = True

def fail_canary(*args):
    raise runner.PlayerFlowProbeFailure("simulated live canary transport failure")


runner.run_playerflow_canary = fail_canary
success, latency, last_run = runner.run_playerflow_canaries(
    config, set(), {"websocket"}, "independent-required"
)
assert {record["flow"] for record in success} == {"login", "command"}
assert all(record["value"] == 0 for record in success)
assert latency[0]["value"] == 0
assert {record["flow"] for record in last_run} == {"login", "command"}
runner.AUTHORITATIVE_CANARY_IDENTITY_VERIFIER_AVAILABLE = False


def fail_entrypath(*args):
    raise runner.PlayerFlowProbeFailure("simulated live blackbox failure")


signals = runner.entrypath_signals(
    config, set(), {"websocket"}, fail_entrypath
)
assert signals["entrypath_blackbox_probe_success"] == [
    {"path": "websocket", "target": "gateway", "value": 0}
]

def programmer_fault(*args):
    raise ValueError("malformed probe configuration")


try:
    runner.entrypath_signals(config, set(), {"websocket"}, programmer_fault)
except ValueError as exc:
    assert str(exc) == "malformed probe configuration"
else:
    raise AssertionError("programmer/configuration fault was incorrectly converted to zero evidence")


original_create_connection = runner.socket.create_connection


def arbitrary_runtime_fault(*args, **kwargs):
    raise RuntimeError("unexpected programmer failure")


runner.socket.create_connection = arbitrary_runtime_fault
try:
    try:
        runner.blackbox_telnet_record(config, set())
    except RuntimeError as exc:
        assert str(exc) == "unexpected programmer failure"
    else:
        raise AssertionError("arbitrary RuntimeError was incorrectly converted")
finally:
    runner.socket.create_connection = original_create_connection


def classified_operational_failure(*args, **kwargs):
    raise runner.ProbeOperationalFailure("expected telnet transport failure")


runner.socket.create_connection = classified_operational_failure
try:
    signals = runner.entrypath_signals(
        config,
        set(),
        {"telnet"},
        lambda current_config, injected, _path: runner.blackbox_telnet_record(
            current_config, injected
        ),
    )
finally:
    runner.socket.create_connection = original_create_connection
assert signals["entrypath_blackbox_probe_success"] == [
    {"path": "telnet", "target": "tcp_proxy", "value": 0}
]
PY

echo "player-experience smoke runner contract checks passed"
