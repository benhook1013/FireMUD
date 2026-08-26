#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
VALIDATOR="$ROOT_DIR/dev-tools/observability/validate-player-experience-smoke-evidence.py"
TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

HELP_OUTPUT="$TMP_DIR/help.out"
COLUMNS=80 python3 "$VALIDATOR" --help >"$HELP_OUTPUT"
NORMALIZED_HELP_OUTPUT="$(tr '\n' ' ' <"$HELP_OUTPUT" | tr -s '[:space:]' ' ')"
grep -q "explicitly unexercised alert paths, for incident evidence." <<<"$NORMALIZED_HELP_OUTPUT"
grep -q "External-authority/deadman structure, provenance, chronology, completeness, and freshness" <<<"$NORMALIZED_HELP_OUTPUT"
grep -q "current red outcomes are permitted only as" <<<"$NORMALIZED_HELP_OUTPUT"

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
  "logPipelineQueryability": {
    "selectedProfile": "staging",
    "capability": "indexed-log-observability",
    "backend": "elasticsearch",
    "storageTarget": "firemud-logs-*",
    "recordId": "log-smoke-11111111-2222-4333-8444-555555555555",
    "service": "game-session-service",
    "traceId": "9c8d7e6f5a4b3210",
    "queryPath": "kibana-saved-search:player-incident-drilldown",
    "configuredDelayTargetSeconds": 120,
    "emittedAt": "2026-03-19T10:53:20Z",
    "retrievedAt": "2026-03-19T10:54:34Z",
    "observedDelaySeconds": 74,
    "result": "passed",
    "evidenceObservedAt": "2026-03-19T10:55:00Z",
    "evidenceFreshnessBudgetSeconds": 7200,
    "evidenceExpiresAt": "2026-03-19T12:55:00Z",
    "evidenceRef": "query-proof://staging/log-smoke-11111111-2222-4333-8444-555555555555",
    "verifiedFields": ["recordId", "service", "traceId"]
  },
  "capabilities": {
    "prometheusMirrors": "published",
    "playerFlowCanary": "advertised"
  },
  "externalAuthority": {
    "profile": "independent-required",
    "exposedPublicPlayerPaths": ["websocket", "telnet"],
    "detectionBudgetSeconds": 195,
    "staleThresholdSeconds": 180,
    "evidenceObservedAt": "2026-03-19T10:55:00Z",
    "lastSuccessfulHeartbeatObservedAt": "2026-03-19T10:54:00Z",
    "observedStalenessSeconds": 60,
    "deadmanAuthority": {
      "status": "green",
      "evidenceRef": "pager://staging/player-experience/2026-03-19T10:50:00Z",
      "pageEvidenceRef": "pager://staging/player-experience/2026-03-19T10:50:00Z/delivery",
      "target": "staging-deadman-authority",
      "checkRef": "check://staging/deadman"
    },
    "publicPathChecks": {
      "websocket": {"status": "green", "evidenceRef": "probe://staging/websocket/2026-03-19T10:53:00Z", "pageEvidenceRef": "pager://staging/websocket/2026-03-19T10:50:00Z/delivery", "target": "staging-websocket", "lastSuccessfulProbeObservedAt": "2026-03-19T10:53:00Z", "observedProbeAgeSeconds": 120},
      "telnet": {"status": "green", "evidenceRef": "probe://staging/telnet/2026-03-19T10:53:00Z", "pageEvidenceRef": "pager://staging/telnet/2026-03-19T10:50:00Z/delivery", "target": "staging-telnet", "lastSuccessfulProbeObservedAt": "2026-03-19T10:53:00Z", "observedProbeAgeSeconds": 120}
    }
  },
  "mirroredSignals": {
    "entrypath_blackbox_probe_success": [
      {"path": "websocket", "target": "gateway", "value": 1},
      {"path": "telnet", "target": "tcp_proxy", "value": 1}
    ],
    "observability_deadman_heartbeat_timestamp_seconds": {
      "source": "staging",
      "value": 1773917640
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
    {"alert": "PlayerFlowCanaryLoginFailed", "severity": "P1", "exerciseResult": "passed"},
    {"alert": "PlayerFlowCanaryCommandFailed", "severity": "P1", "exerciseResult": "passed"},
    {"alert": "PlayerFlowCanaryLatencyHigh", "severity": "P1", "exerciseResult": "passed"},
    {"alert": "PlayerFlowCanaryEvidenceStale", "severity": "P1", "exerciseResult": "passed"}
  ]
}
JSON

python3 "$VALIDATOR" "$VALID_EVIDENCE" >"$TMP_DIR/valid.out"

MISSING_QUERYABILITY_EVIDENCE="$TMP_DIR/missing-queryability-evidence.json"
python3 - "$VALID_EVIDENCE" "$MISSING_QUERYABILITY_EVIDENCE" <<'PY'
import json
import sys
from pathlib import Path

source = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
source.pop("logPipelineQueryability")
Path(sys.argv[2]).write_text(json.dumps(source), encoding="utf-8")
PY
if python3 "$VALIDATOR" "$MISSING_QUERYABILITY_EVIDENCE" \
  >"$TMP_DIR/missing-queryability.out" 2>&1; then
  echo "missing queryability posture unexpectedly passed" >&2
  exit 1
fi
grep -q "logPipelineQueryability is required" "$TMP_DIR/missing-queryability.out"

REDUCED_QUERYABILITY_EVIDENCE="$TMP_DIR/reduced-queryability-evidence.json"
OMITTED_QUERYABILITY_EVIDENCE="$TMP_DIR/omitted-queryability-evidence.json"
python3 - "$VALID_EVIDENCE" "$REDUCED_QUERYABILITY_EVIDENCE" "$OMITTED_QUERYABILITY_EVIDENCE" <<'PY'
import copy
import json
import sys
from pathlib import Path

source = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
reduced = copy.deepcopy(source)
reduced["logPipelineQueryability"].update(
    {
        "capability": "console-journal-log-observability",
        "backend": "journald",
        "storageTarget": "firemud-journal",
        "queryPath": "journalctl:firemud-logs",
    }
)
Path(sys.argv[2]).write_text(json.dumps(reduced), encoding="utf-8")

omitted = copy.deepcopy(source)
omitted["logPipelineQueryability"] = {
    "selectedProfile": "hobby-self-hosted",
    "capability": "log-queryability-omitted",
    "result": "not_applicable",
    "omissionReason": "indexed search is not deployed",
    "evidenceObservedAt": "2026-03-19T10:55:00Z",
    "evidenceFreshnessBudgetSeconds": 7200,
    "evidenceExpiresAt": "2026-03-19T12:55:00Z",
    "evidenceRef": "operator://hobby/queryability-omission/2026-03-19T10:55:00Z",
}
Path(sys.argv[3]).write_text(json.dumps(omitted), encoding="utf-8")
PY

python3 "$VALIDATOR" "$REDUCED_QUERYABILITY_EVIDENCE" >"$TMP_DIR/reduced-queryability.out"
python3 "$VALIDATOR" "$OMITTED_QUERYABILITY_EVIDENCE" >"$TMP_DIR/omitted-queryability.out"

NON_OMITTED_NOT_APPLICABLE="$TMP_DIR/non-omitted-not-applicable.json"
FAILED_QUERYABILITY="$TMP_DIR/failed-queryability.json"
UNSUPPORTED_QUERYABILITY="$TMP_DIR/unsupported-queryability.json"
FABRICATED_OMISSION="$TMP_DIR/fabricated-omission.json"
python3 - "$VALID_EVIDENCE" "$OMITTED_QUERYABILITY_EVIDENCE" "$NON_OMITTED_NOT_APPLICABLE" "$FAILED_QUERYABILITY" "$UNSUPPORTED_QUERYABILITY" "$FABRICATED_OMISSION" <<'PY'
import copy
import json
import sys
from pathlib import Path

source = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
not_applicable = copy.deepcopy(source)
not_applicable["logPipelineQueryability"]["result"] = "not_applicable"
Path(sys.argv[3]).write_text(json.dumps(not_applicable), encoding="utf-8")

failed = copy.deepcopy(source)
failed["logPipelineQueryability"]["result"] = "failed"
Path(sys.argv[4]).write_text(json.dumps(failed), encoding="utf-8")

unsupported = copy.deepcopy(source)
unsupported["logPipelineQueryability"]["capability"] = "elasticsearch-log-search"
Path(sys.argv[5]).write_text(json.dumps(unsupported), encoding="utf-8")

fabricated = json.loads(Path(sys.argv[2]).read_text(encoding="utf-8"))
fabricated["logPipelineQueryability"].update(
    {
        "backend": "elasticsearch",
        "storageTarget": "firemud-logs-*",
        "queryPath": "kibana:saved-search",
        "emittedAt": "2026-03-19T10:53:20Z",
        "retrievedAt": "2026-03-19T10:54:34Z",
        "configuredDelayTargetSeconds": 120,
        "observedDelaySeconds": 74,
        "recordId": "log-smoke-11111111-2222-4333-8444-555555555555",
        "service": "game-session-service",
        "traceId": "9c8d7e6f5a4b3210",
        "verifiedFields": ["recordId", "service", "traceId"],
        "retrievalProof": "query-proof://fabricated",
    }
)
Path(sys.argv[6]).write_text(json.dumps(fabricated), encoding="utf-8")
PY

if python3 "$VALIDATOR" "$NON_OMITTED_NOT_APPLICABLE" >"$TMP_DIR/non-omitted-not-applicable.out" 2>&1; then
  echo "non-omitted not_applicable queryability unexpectedly passed" >&2
  exit 1
fi
grep -q "logPipelineQueryability.result must be 'passed' or 'failed' for a non-omitted capability" \
  "$TMP_DIR/non-omitted-not-applicable.out"

if python3 "$VALIDATOR" "$FAILED_QUERYABILITY" >"$TMP_DIR/failed-queryability.out" 2>&1; then
  echo "failed queryability unexpectedly authorized readiness" >&2
  exit 1
fi
grep -q "logPipelineQueryability.result must be 'passed' for readiness validation" \
  "$TMP_DIR/failed-queryability.out"
python3 "$VALIDATOR" --allow-failure-evidence "$FAILED_QUERYABILITY" >"$TMP_DIR/failed-queryability-incident.out"

if python3 "$VALIDATOR" "$UNSUPPORTED_QUERYABILITY" >"$TMP_DIR/unsupported-queryability.out" 2>&1; then
  echo "unsupported queryability capability unexpectedly passed" >&2
  exit 1
fi
grep -q "logPipelineQueryability.capability must be one of" "$TMP_DIR/unsupported-queryability.out"

if python3 "$VALIDATOR" "$FABRICATED_OMISSION" >"$TMP_DIR/fabricated-omission.out" 2>&1; then
  echo "fabricated omitted queryability fields unexpectedly passed" >&2
  exit 1
fi
grep -q "logPipelineQueryability.backend is not allowed when queryability is omitted" \
  "$TMP_DIR/fabricated-omission.out"
grep -q "logPipelineQueryability.recordId is not allowed when queryability is omitted" \
  "$TMP_DIR/fabricated-omission.out"
grep -q "logPipelineQueryability.retrievalProof is not allowed when queryability is omitted" \
  "$TMP_DIR/fabricated-omission.out"

python3 - "$VALID_EVIDENCE" "$TMP_DIR" <<'PY'
import copy
import json
import sys
from pathlib import Path

source = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
output = Path(sys.argv[2])
mutations = {
    "expired": lambda evidence: evidence.update({"verifiedAt": "2026-03-19T13:00:00Z"}),
    "future-dated": lambda evidence: evidence["logPipelineQueryability"].update(
        {"evidenceObservedAt": "2026-03-19T11:00:00Z"}
    ),
    "non-positive": lambda evidence: evidence["logPipelineQueryability"].update(
        {"evidenceFreshnessBudgetSeconds": 0}
    ),
    "non-finite": lambda evidence: evidence["logPipelineQueryability"].update(
        {"evidenceFreshnessBudgetSeconds": float("nan")}
    ),
    "inconsistent-expiry": lambda evidence: evidence["logPipelineQueryability"].update(
        {"evidenceExpiresAt": "2026-03-19T13:00:00Z"}
    ),
    "retrieved-before-emitted": lambda evidence: evidence["logPipelineQueryability"].update(
        {"retrievedAt": "2026-03-19T10:53:19Z"}
    ),
    "observed-before-retrieved": lambda evidence: evidence["logPipelineQueryability"].update(
        {
            "evidenceObservedAt": "2026-03-19T10:54:00Z",
            "evidenceExpiresAt": "2026-03-19T12:54:00Z",
        }
    ),
    "emitted-after-verified": lambda evidence: evidence["logPipelineQueryability"].update(
        {"emittedAt": "2026-03-19T10:56:00Z"}
    ),
    "retrieved-after-verified": lambda evidence: evidence["logPipelineQueryability"].update(
        {"retrievedAt": "2026-03-19T10:56:00Z"}
    ),
    "delay-mismatch": lambda evidence: evidence["logPipelineQueryability"].update(
        {"observedDelaySeconds": 73}
    ),
    "passed-delay-over-target": lambda evidence: evidence["logPipelineQueryability"].update(
        {"configuredDelayTargetSeconds": 73}
    ),
    "missing-selected-profile": lambda evidence: evidence["logPipelineQueryability"].pop(
        "selectedProfile"
    ),
    "blank-selected-profile": lambda evidence: evidence["logPipelineQueryability"].update(
        {"selectedProfile": "   "}
    ),
    "huge-budget": lambda evidence: evidence["logPipelineQueryability"].update(
        {"evidenceFreshnessBudgetSeconds": 10**1000}
    ),
    "huge-delay-target": lambda evidence: evidence["logPipelineQueryability"].update(
        {"configuredDelayTargetSeconds": 10**1000}
    ),
    "huge-observed-delay": lambda evidence: evidence["logPipelineQueryability"].update(
        {"observedDelaySeconds": 10**1000}
    ),
}
for name, mutate in mutations.items():
    evidence = copy.deepcopy(source)
    mutate(evidence)
    (output / f"queryability-{name}.json").write_text(
        json.dumps(evidence), encoding="utf-8"
    )
PY

for queryability_case in \
  expired future-dated non-positive non-finite inconsistent-expiry \
  retrieved-before-emitted observed-before-retrieved emitted-after-verified \
  retrieved-after-verified delay-mismatch passed-delay-over-target \
  missing-selected-profile blank-selected-profile huge-budget huge-delay-target \
  huge-observed-delay; do
  queryability_path="$TMP_DIR/queryability-$queryability_case.json"
  if python3 "$VALIDATOR" "$queryability_path" >"$TMP_DIR/queryability-$queryability_case.out" 2>&1; then
    echo "$queryability_case queryability mutation unexpectedly passed" >&2
    exit 1
  fi
done
grep -q "logPipelineQueryability.evidenceExpiresAt cannot be in the past relative to verifiedAt" \
  "$TMP_DIR/queryability-expired.out"
grep -q "logPipelineQueryability.evidenceObservedAt cannot be in the future relative to verifiedAt" \
  "$TMP_DIR/queryability-future-dated.out"
grep -q "logPipelineQueryability.evidenceFreshnessBudgetSeconds must be a positive finite number" \
  "$TMP_DIR/queryability-non-positive.out"
grep -q "logPipelineQueryability.evidenceFreshnessBudgetSeconds must be a positive finite number" \
  "$TMP_DIR/queryability-non-finite.out"
grep -q "logPipelineQueryability.evidenceExpiresAt must equal evidenceObservedAt plus evidenceFreshnessBudgetSeconds within numeric tolerance" \
  "$TMP_DIR/queryability-inconsistent-expiry.out"
grep -q "logPipelineQueryability.retrievedAt must be at or after logPipelineQueryability.emittedAt" \
  "$TMP_DIR/queryability-retrieved-before-emitted.out"
grep -q "logPipelineQueryability.evidenceObservedAt must be at or after logPipelineQueryability.retrievedAt" \
  "$TMP_DIR/queryability-observed-before-retrieved.out"
grep -q "logPipelineQueryability.emittedAt cannot be in the future relative to verifiedAt" \
  "$TMP_DIR/queryability-emitted-after-verified.out"
grep -q "logPipelineQueryability.retrievedAt cannot be in the future relative to verifiedAt" \
  "$TMP_DIR/queryability-retrieved-after-verified.out"
grep -q "logPipelineQueryability.observedDelaySeconds must equal retrievedAt minus emittedAt within numeric tolerance" \
  "$TMP_DIR/queryability-delay-mismatch.out"
grep -q "logPipelineQueryability.observedDelaySeconds must be no greater than configuredDelayTargetSeconds for a passed result" \
  "$TMP_DIR/queryability-passed-delay-over-target.out"
grep -q "logPipelineQueryability.selectedProfile is required" \
  "$TMP_DIR/queryability-missing-selected-profile.out"
grep -q "logPipelineQueryability.selectedProfile is required" \
  "$TMP_DIR/queryability-blank-selected-profile.out"
grep -q "logPipelineQueryability.evidenceFreshnessBudgetSeconds must be a positive finite number" \
  "$TMP_DIR/queryability-huge-budget.out"
grep -q "logPipelineQueryability.configuredDelayTargetSeconds must be a nonnegative finite number" \
  "$TMP_DIR/queryability-huge-delay-target.out"
grep -q "logPipelineQueryability.observedDelaySeconds must be a nonnegative finite number" \
  "$TMP_DIR/queryability-huge-observed-delay.out"

HUGE_NUMERIC_EVIDENCE_DIR="$TMP_DIR/huge-numeric-evidence"
mkdir -p "$HUGE_NUMERIC_EVIDENCE_DIR"
python3 - "$VALID_EVIDENCE" "$HUGE_NUMERIC_EVIDENCE_DIR" <<'PY'
import copy
import json
import sys
from pathlib import Path

source = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
output = Path(sys.argv[2])
mutations = {
    "external-detection-budget": lambda evidence: evidence["externalAuthority"].update(
        {"detectionBudgetSeconds": 10**1000}
    ),
    "external-stale-threshold": lambda evidence: evidence["externalAuthority"].update(
        {"staleThresholdSeconds": 10**1000}
    ),
    "external-observed-staleness": lambda evidence: evidence["externalAuthority"].update(
        {"observedStalenessSeconds": 10**1000}
    ),
    "mirrored-probe-age": lambda evidence: evidence["externalAuthority"][
        "publicPathChecks"
    ]["websocket"].update({"observedProbeAgeSeconds": 10**1000}),
    "mirrored-deadman": lambda evidence: evidence["mirroredSignals"][
        "observability_deadman_heartbeat_timestamp_seconds"
    ].update({"value": 10**1000}),
    "mirrored-canary-success": lambda evidence: evidence["mirroredSignals"][
        "playerflow_canary_success"
    ][0].update({"value": 10**1000}),
    "mirrored-canary-latency": lambda evidence: evidence["mirroredSignals"][
        "playerflow_canary_latency_ms"
    ][0].update({"value": 10**1000}),
    "mirrored-canary-last-run": lambda evidence: evidence["mirroredSignals"][
        "playerflow_canary_last_run_timestamp_seconds"
    ][0].update({"value": 10**1000}),
    "mirrored-canary-budget": lambda evidence: evidence["mirroredSignals"][
        "playerflow_canary_freshness_budget_seconds"
    ].update({"value": 10**1000}),
}
for name, mutate in mutations.items():
    evidence = copy.deepcopy(source)
    mutate(evidence)
    (output / f"{name}.json").write_text(json.dumps(evidence), encoding="utf-8")
PY

for huge_numeric_case in \
  external-detection-budget external-stale-threshold external-observed-staleness \
  mirrored-probe-age mirrored-deadman mirrored-canary-success mirrored-canary-latency \
  mirrored-canary-last-run mirrored-canary-budget; do
  huge_numeric_path="$HUGE_NUMERIC_EVIDENCE_DIR/$huge_numeric_case.json"
  huge_numeric_output="$TMP_DIR/$huge_numeric_case.out"
  if python3 "$VALIDATOR" "$huge_numeric_path" >"$huge_numeric_output" 2>&1; then
    echo "$huge_numeric_case unexpectedly passed" >&2
    exit 1
  fi
  if grep -q "Traceback" "$huge_numeric_output"; then
    echo "$huge_numeric_case raised an unexpected traceback" >&2
    cat "$huge_numeric_output" >&2
    exit 1
  fi
done
grep -q "externalAuthority.detectionBudgetSeconds must be a positive finite number" \
  "$TMP_DIR/external-detection-budget.out"
grep -q "externalAuthority.staleThresholdSeconds must be a positive finite number" \
  "$TMP_DIR/external-stale-threshold.out"
grep -q "externalAuthority.observedStalenessSeconds must be a nonnegative finite number" \
  "$TMP_DIR/external-observed-staleness.out"
grep -q "publicPathChecks.websocket.observedProbeAgeSeconds must be a nonnegative finite number" \
  "$TMP_DIR/mirrored-probe-age.out"
grep -q "observability_deadman_heartbeat_timestamp_seconds.value must be a positive finite number" \
  "$TMP_DIR/mirrored-deadman.out"
grep -q "playerflow_canary_success values must be finite numeric 0 or 1" \
  "$TMP_DIR/mirrored-canary-success.out"
grep -q "playerflow_canary_latency_ms values must be a nonnegative finite number" \
  "$TMP_DIR/mirrored-canary-latency.out"
grep -q "playerflow_canary_last_run_timestamp_seconds values must be positive finite timestamps" \
  "$TMP_DIR/mirrored-canary-last-run.out"
grep -q "mirroredSignals.playerflow_canary_freshness_budget_seconds.value must be a positive finite number" \
  "$TMP_DIR/mirrored-canary-budget.out"

QUERY_PATH_STORAGE_TARGET="$TMP_DIR/queryability-storage-target-path.json"
QUERY_PATH_BARE_INDEX="$TMP_DIR/queryability-bare-index-path.json"
python3 - "$VALID_EVIDENCE" "$QUERY_PATH_STORAGE_TARGET" "$QUERY_PATH_BARE_INDEX" <<'PY'
import copy
import json
import sys
from pathlib import Path

source = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
storage_target = copy.deepcopy(source)
storage_target["logPipelineQueryability"]["queryPath"] = (
    storage_target["logPipelineQueryability"]["storageTarget"]
)
Path(sys.argv[2]).write_text(json.dumps(storage_target), encoding="utf-8")

bare_index = copy.deepcopy(source)
bare_index["logPipelineQueryability"].update(
    {"storageTarget": "other-log-storage", "queryPath": "firemud-logs-*"}
)
Path(sys.argv[3]).write_text(json.dumps(bare_index), encoding="utf-8")
PY

for query_path_case in storage-target-path bare-index-path; do
  query_path_file="$TMP_DIR/queryability-$query_path_case.json"
  if python3 "$VALIDATOR" "$query_path_file" >"$TMP_DIR/queryability-$query_path_case.out" 2>&1; then
    echo "$query_path_case query path unexpectedly passed" >&2
    exit 1
  fi
  grep -q "logPipelineQueryability.queryPath must identify a final supported operator surface" \
    "$TMP_DIR/queryability-$query_path_case.out"
done

OMITTED_WRONG_RESULT="$TMP_DIR/omitted-queryability-wrong-result.json"
OMITTED_MISSING_REASON="$TMP_DIR/omitted-queryability-missing-reason.json"
OMITTED_BLANK_REASON="$TMP_DIR/omitted-queryability-blank-reason.json"
python3 - \
  "$OMITTED_QUERYABILITY_EVIDENCE" \
  "$OMITTED_WRONG_RESULT" \
  "$OMITTED_MISSING_REASON" \
  "$OMITTED_BLANK_REASON" <<'PY'
import copy
import json
import sys
from pathlib import Path

source = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))

wrong_result = copy.deepcopy(source)
wrong_result["logPipelineQueryability"]["result"] = "failed"
Path(sys.argv[2]).write_text(json.dumps(wrong_result), encoding="utf-8")

missing_reason = copy.deepcopy(source)
del missing_reason["logPipelineQueryability"]["omissionReason"]
Path(sys.argv[3]).write_text(json.dumps(missing_reason), encoding="utf-8")

blank_reason = copy.deepcopy(source)
blank_reason["logPipelineQueryability"]["omissionReason"] = "  "
Path(sys.argv[4]).write_text(json.dumps(blank_reason), encoding="utf-8")
PY

if python3 "$VALIDATOR" "$OMITTED_WRONG_RESULT" >"$TMP_DIR/omitted-queryability-wrong-result.out" 2>&1; then
  echo "omitted queryability wrong result unexpectedly passed" >&2
  exit 1
fi
grep -q "logPipelineQueryability.result must be 'not_applicable' when capability is log-queryability-omitted" \
  "$TMP_DIR/omitted-queryability-wrong-result.out"

for omission_reason_case in missing-reason blank-reason; do
  omission_reason_file="$TMP_DIR/omitted-queryability-$omission_reason_case.json"
  if python3 "$VALIDATOR" "$omission_reason_file" >"$TMP_DIR/omitted-queryability-$omission_reason_case.out" 2>&1; then
    echo "$omission_reason_case omitted queryability reason unexpectedly passed" >&2
    exit 1
  fi
  grep -q "logPipelineQueryability.omissionReason is required when queryability is omitted" \
    "$TMP_DIR/omitted-queryability-$omission_reason_case.out"
done

FALSE_CURRENT_DEADMAN_MIRROR="$TMP_DIR/false-current-deadman-mirror.json"
STALE_INJECTED_DEADMAN_MIRROR="$TMP_DIR/stale-injected-deadman-mirror.json"
python3 - \
  "$VALID_EVIDENCE" \
  "$FALSE_CURRENT_DEADMAN_MIRROR" \
  "$STALE_INJECTED_DEADMAN_MIRROR" <<'PY'
import copy
import json
import sys
from datetime import datetime
from pathlib import Path

source = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
verified_epoch = datetime.fromisoformat(
    source["verifiedAt"].replace("Z", "+00:00")
).timestamp()

false_current = copy.deepcopy(source)
false_current["mirroredSignals"][
    "observability_deadman_heartbeat_timestamp_seconds"
]["value"] = verified_epoch
Path(sys.argv[2]).write_text(json.dumps(false_current), encoding="utf-8")

stale_injected = copy.deepcopy(source)
stale_injected["mirroredSignals"][
    "observability_deadman_heartbeat_timestamp_seconds"
]["value"] = verified_epoch - source["externalAuthority"]["staleThresholdSeconds"] - 1
Path(sys.argv[3]).write_text(json.dumps(stale_injected), encoding="utf-8")
PY

if python3 "$VALIDATOR" --allow-failure-evidence "$FALSE_CURRENT_DEADMAN_MIRROR" \
  >"$TMP_DIR/false-current-deadman-mirror.out" 2>&1; then
  echo "current-looking deadman mirror unexpectedly diverged from retained authority" >&2
  exit 1
fi
grep -q "must equal externalAuthority.lastSuccessfulHeartbeatObservedAt" \
  "$TMP_DIR/false-current-deadman-mirror.out"

if python3 "$VALIDATOR" "$STALE_INJECTED_DEADMAN_MIRROR" \
  >"$TMP_DIR/stale-injected-deadman-readiness.out" 2>&1; then
  echo "injected stale deadman mirror unexpectedly authorized readiness" >&2
  exit 1
fi
grep -q "must equal externalAuthority.lastSuccessfulHeartbeatObservedAt" \
  "$TMP_DIR/stale-injected-deadman-readiness.out"
python3 "$VALIDATOR" --allow-failure-evidence "$STALE_INJECTED_DEADMAN_MIRROR" \
  >"$TMP_DIR/stale-injected-deadman-incident.out"

FAILED_CANARY_ALERT_EVIDENCE="$TMP_DIR/failed-canary-alert-evidence.json"
python3 - "$VALID_EVIDENCE" "$FAILED_CANARY_ALERT_EVIDENCE" <<'PY'
import json
import sys
from pathlib import Path

source = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
source["canaryAlerts"][0]["exerciseResult"] = "failed"
Path(sys.argv[2]).write_text(json.dumps(source), encoding="utf-8")
PY

if python3 "$VALIDATOR" "$FAILED_CANARY_ALERT_EVIDENCE" >"$TMP_DIR/failed-canary-alert-readiness.out" 2>&1; then
  echo "readiness validator unexpectedly accepted failed canary alert evidence" >&2
  exit 1
fi
grep -q "PlayerFlowCanaryLoginFailed exerciseResult must be passed" \
  "$TMP_DIR/failed-canary-alert-readiness.out"
python3 "$VALIDATOR" --allow-failure-evidence "$FAILED_CANARY_ALERT_EVIDENCE" \
  >"$TMP_DIR/failed-canary-alert-incident.out"

INVALID_CANARY_ALERT_EXERCISE_RESULT="$TMP_DIR/invalid-canary-alert-exercise-result.json"
python3 - "$VALID_EVIDENCE" "$INVALID_CANARY_ALERT_EXERCISE_RESULT" <<'PY'
import json
import sys
from pathlib import Path

source = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
source["canaryAlerts"][0]["exerciseResult"] = "unknown"
Path(sys.argv[2]).write_text(json.dumps(source), encoding="utf-8")
PY

if python3 "$VALIDATOR" --allow-failure-evidence "$INVALID_CANARY_ALERT_EXERCISE_RESULT" \
  >"$TMP_DIR/invalid-canary-alert-exercise-result.out" 2>&1; then
  echo "invalid canary alert exercise result unexpectedly passed incident validation" >&2
  exit 1
fi
grep -q "PlayerFlowCanaryLoginFailed exerciseResult must be one of failed, not_exercised, passed" \
  "$TMP_DIR/invalid-canary-alert-exercise-result.out"

UNEXERCISED_CANARY_ALERT_EVIDENCE="$TMP_DIR/unexercised-canary-alert-evidence.json"
python3 - "$VALID_EVIDENCE" "$UNEXERCISED_CANARY_ALERT_EVIDENCE" <<'PY'
import json
import sys
from pathlib import Path

source = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
for record in source["canaryAlerts"]:
    record["exerciseResult"] = "not_exercised"
Path(sys.argv[2]).write_text(json.dumps(source), encoding="utf-8")
PY

if python3 "$VALIDATOR" "$UNEXERCISED_CANARY_ALERT_EVIDENCE" \
  >"$TMP_DIR/unexercised-canary-alert-readiness.out" 2>&1; then
  echo "unexercised canary alert declarations unexpectedly authorized readiness" >&2
  exit 1
fi
grep -q "PlayerFlowCanaryLoginFailed exerciseResult must be passed" \
  "$TMP_DIR/unexercised-canary-alert-readiness.out"
python3 "$VALIDATOR" --allow-failure-evidence "$UNEXERCISED_CANARY_ALERT_EVIDENCE" \
  >"$TMP_DIR/unexercised-canary-alert-incident.out"

MALFORMED_CANARY_ALERT_SHAPE="$TMP_DIR/malformed-canary-alert-shape.json"
python3 - "$VALID_EVIDENCE" "$MALFORMED_CANARY_ALERT_SHAPE" <<'PY'
import json
import sys
from pathlib import Path

source = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
source["canaryAlerts"].append(dict(source["canaryAlerts"][0]))
source["canaryAlerts"].append(
    {"alert": "UnsupportedCanaryAlert", "severity": "P1", "exerciseResult": "passed"}
)
Path(sys.argv[2]).write_text(json.dumps(source), encoding="utf-8")
PY

if python3 "$VALIDATOR" "$MALFORMED_CANARY_ALERT_SHAPE" >"$TMP_DIR/malformed-canary-alert-shape.out" 2>&1; then
  echo "duplicate/unsupported canary alert families unexpectedly passed readiness validation" >&2
  exit 1
fi
grep -q "canaryAlerts must contain exactly one record per alert family" \
  "$TMP_DIR/malformed-canary-alert-shape.out"
grep -q "canaryAlerts has unsupported alert families: UnsupportedCanaryAlert" \
  "$TMP_DIR/malformed-canary-alert-shape.out"
if python3 "$VALIDATOR" --allow-failure-evidence "$MALFORMED_CANARY_ALERT_SHAPE" \
  >"$TMP_DIR/malformed-canary-alert-shape-incident.out" 2>&1; then
  echo "duplicate/unsupported canary alert families unexpectedly passed incident validation" >&2
  exit 1
fi
grep -q "canaryAlerts must contain exactly one record per alert family" \
  "$TMP_DIR/malformed-canary-alert-shape-incident.out"
grep -q "canaryAlerts has unsupported alert families: UnsupportedCanaryAlert" \
  "$TMP_DIR/malformed-canary-alert-shape-incident.out"

INVALID_CAPABILITIES_EVIDENCE="$TMP_DIR/invalid-capabilities-evidence.json"
python3 - "$VALID_EVIDENCE" "$INVALID_CAPABILITIES_EVIDENCE" <<'PY'
import json
import sys
from pathlib import Path

source = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
source["capabilities"] = {
    "prometheusMirrors": "invalid",
    "playerFlowCanary": "invalid",
}
Path(sys.argv[2]).write_text(json.dumps(source), encoding="utf-8")
PY

if python3 "$VALIDATOR" "$INVALID_CAPABILITIES_EVIDENCE" >"$TMP_DIR/invalid-capabilities.out" 2>&1; then
  echo "invalid capabilities unexpectedly passed" >&2
  exit 1
fi
grep -q "capabilities.playerFlowCanary must be one of advertised, omitted" \
  "$TMP_DIR/invalid-capabilities.out"
grep -q "capabilities.prometheusMirrors must be one of omitted, published" \
  "$TMP_DIR/invalid-capabilities.out"

INVALID_LIVE_PROVENANCE_EVIDENCE="$TMP_DIR/invalid-live-provenance-evidence.json"
python3 - "$VALID_EVIDENCE" "$INVALID_LIVE_PROVENANCE_EVIDENCE" <<'PY'
import json
import sys
from pathlib import Path

source = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
source["externalAuthorityProvenance"] = "synthetic"
Path(sys.argv[2]).write_text(json.dumps(source), encoding="utf-8")
PY

if python3 "$VALIDATOR" "$INVALID_LIVE_PROVENANCE_EVIDENCE" >"$TMP_DIR/invalid-live-provenance.out" 2>&1; then
  echo "live evidence with synthetic authority provenance unexpectedly passed" >&2
  exit 1
fi
grep -q "live evidence must use retained-external authority provenance" \
  "$TMP_DIR/invalid-live-provenance.out"

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

MISSING_STALE_THRESHOLD_EVIDENCE="$TMP_DIR/missing-stale-threshold-evidence.json"
python3 - "$VALID_EVIDENCE" "$MISSING_STALE_THRESHOLD_EVIDENCE" <<'PY'
import json
import sys
from pathlib import Path

source = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
del source["externalAuthority"]["staleThresholdSeconds"]
Path(sys.argv[2]).write_text(json.dumps(source), encoding="utf-8")
PY

if python3 "$VALIDATOR" "$MISSING_STALE_THRESHOLD_EVIDENCE" >"$TMP_DIR/missing-stale-threshold.out" 2>&1; then
  echo "missing stale threshold unexpectedly passed" >&2
  exit 1
fi
grep -q "externalAuthority.staleThresholdSeconds must be a positive finite number" \
  "$TMP_DIR/missing-stale-threshold.out"

INVALID_STALE_THRESHOLD_EVIDENCE="$TMP_DIR/invalid-stale-threshold-evidence.json"
python3 - "$VALID_EVIDENCE" "$INVALID_STALE_THRESHOLD_EVIDENCE" <<'PY'
import json
import sys
from pathlib import Path

source = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
source["externalAuthority"]["staleThresholdSeconds"] = 0
Path(sys.argv[2]).write_text(json.dumps(source), encoding="utf-8")
PY

if python3 "$VALIDATOR" "$INVALID_STALE_THRESHOLD_EVIDENCE" >"$TMP_DIR/invalid-stale-threshold.out" 2>&1; then
  echo "invalid stale threshold unexpectedly passed" >&2
  exit 1
fi
grep -q "externalAuthority.staleThresholdSeconds must be a positive finite number" \
  "$TMP_DIR/invalid-stale-threshold.out"

OVER_THRESHOLD_DEADMAN_EVIDENCE="$TMP_DIR/over-threshold-deadman-evidence.json"
python3 - "$VALID_EVIDENCE" "$OVER_THRESHOLD_DEADMAN_EVIDENCE" <<'PY'
import json
import sys
from pathlib import Path

source = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
source["externalAuthority"]["lastSuccessfulHeartbeatObservedAt"] = "2026-03-19T10:51:59Z"
source["externalAuthority"]["observedStalenessSeconds"] = 181
Path(sys.argv[2]).write_text(json.dumps(source), encoding="utf-8")
PY

if python3 "$VALIDATOR" "$OVER_THRESHOLD_DEADMAN_EVIDENCE" >"$TMP_DIR/over-threshold-deadman.out" 2>&1; then
  echo "green deadman over stale threshold unexpectedly passed" >&2
  exit 1
fi
grep -q "deadmanAuthority green observedStalenessSeconds must be no greater than externalAuthority.staleThresholdSeconds" \
  "$TMP_DIR/over-threshold-deadman.out"

MISMATCHED_STALENESS_EVIDENCE="$TMP_DIR/mismatched-staleness-evidence.json"
python3 - "$VALID_EVIDENCE" "$MISMATCHED_STALENESS_EVIDENCE" <<'PY'
import json
import sys
from pathlib import Path

source = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
source["externalAuthority"]["observedStalenessSeconds"] = 61
Path(sys.argv[2]).write_text(json.dumps(source), encoding="utf-8")
PY

if python3 "$VALIDATOR" "$MISMATCHED_STALENESS_EVIDENCE" >"$TMP_DIR/mismatched-staleness.out" 2>&1; then
  echo "mismatched observed staleness unexpectedly passed" >&2
  exit 1
fi
grep -q "externalAuthority.observedStalenessSeconds must equal" \
  "$TMP_DIR/mismatched-staleness.out"

MISSING_PAGE_EVIDENCE="$TMP_DIR/missing-page-evidence.json"
python3 - "$VALID_EVIDENCE" "$MISSING_PAGE_EVIDENCE" <<'PY'
import json
import sys
from pathlib import Path

source = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
del source["externalAuthority"]["publicPathChecks"]["websocket"]["pageEvidenceRef"]
Path(sys.argv[2]).write_text(json.dumps(source), encoding="utf-8")
PY

if python3 "$VALIDATOR" "$MISSING_PAGE_EVIDENCE" >"$TMP_DIR/missing-page.out" 2>&1; then
  echo "missing per-path page evidence unexpectedly passed" >&2
  exit 1
fi
grep -q "externalAuthority.publicPathChecks.websocket.pageEvidenceRef is required" \
  "$TMP_DIR/missing-page.out"

MISSING_DEADMAN_PAGE_EVIDENCE="$TMP_DIR/missing-deadman-page-evidence.json"
python3 - "$VALID_EVIDENCE" "$MISSING_DEADMAN_PAGE_EVIDENCE" <<'PY'
import json
import sys
from pathlib import Path

source = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
del source["externalAuthority"]["deadmanAuthority"]["pageEvidenceRef"]
Path(sys.argv[2]).write_text(json.dumps(source), encoding="utf-8")
PY

if python3 "$VALIDATOR" "$MISSING_DEADMAN_PAGE_EVIDENCE" >"$TMP_DIR/missing-deadman-page.out" 2>&1; then
  echo "missing deadman page evidence unexpectedly passed" >&2
  exit 1
fi
grep -q "externalAuthority.deadmanAuthority.pageEvidenceRef is required" \
  "$TMP_DIR/missing-deadman-page.out"

MISSING_PROBE_AGE_EVIDENCE="$TMP_DIR/missing-probe-age-evidence.json"
python3 - "$VALID_EVIDENCE" "$MISSING_PROBE_AGE_EVIDENCE" <<'PY'
import json
import sys
from pathlib import Path

source = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
del source["externalAuthority"]["publicPathChecks"]["websocket"]["observedProbeAgeSeconds"]
Path(sys.argv[2]).write_text(json.dumps(source), encoding="utf-8")
PY

if python3 "$VALIDATOR" "$MISSING_PROBE_AGE_EVIDENCE" >"$TMP_DIR/missing-probe-age.out" 2>&1; then
  echo "missing observed probe age unexpectedly passed" >&2
  exit 1
fi
grep -q "externalAuthority.publicPathChecks.websocket.observedProbeAgeSeconds must be a nonnegative finite number" \
  "$TMP_DIR/missing-probe-age.out"

INVALID_PROBE_AGE_EVIDENCE="$TMP_DIR/invalid-probe-age-evidence.json"
python3 - "$VALID_EVIDENCE" "$INVALID_PROBE_AGE_EVIDENCE" <<'PY'
import json
import sys
from pathlib import Path

source = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
source["externalAuthority"]["publicPathChecks"]["websocket"]["observedProbeAgeSeconds"] = -1
Path(sys.argv[2]).write_text(json.dumps(source), encoding="utf-8")
PY

if python3 "$VALIDATOR" "$INVALID_PROBE_AGE_EVIDENCE" >"$TMP_DIR/invalid-probe-age.out" 2>&1; then
  echo "invalid observed probe age unexpectedly passed" >&2
  exit 1
fi
grep -q "externalAuthority.publicPathChecks.websocket.observedProbeAgeSeconds must be a nonnegative finite number" \
  "$TMP_DIR/invalid-probe-age.out"

MISMATCHED_PROBE_AGE_EVIDENCE="$TMP_DIR/mismatched-probe-age-evidence.json"
python3 - "$VALID_EVIDENCE" "$MISMATCHED_PROBE_AGE_EVIDENCE" <<'PY'
import json
import sys
from pathlib import Path

source = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
source["externalAuthority"]["publicPathChecks"]["websocket"]["observedProbeAgeSeconds"] = 121
Path(sys.argv[2]).write_text(json.dumps(source), encoding="utf-8")
PY

if python3 "$VALIDATOR" "$MISMATCHED_PROBE_AGE_EVIDENCE" >"$TMP_DIR/mismatched-probe-age.out" 2>&1; then
  echo "mismatched observed probe age unexpectedly passed" >&2
  exit 1
fi
grep -q "externalAuthority.publicPathChecks.websocket.observedProbeAgeSeconds must equal" \
  "$TMP_DIR/mismatched-probe-age.out"

OVER_BUDGET_PROBE_AGE_EVIDENCE="$TMP_DIR/over-budget-probe-age-evidence.json"
python3 - "$VALID_EVIDENCE" "$OVER_BUDGET_PROBE_AGE_EVIDENCE" <<'PY'
import json
import sys
from pathlib import Path

source = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
record = source["externalAuthority"]["publicPathChecks"]["websocket"]
record["lastSuccessfulProbeObservedAt"] = "2026-03-19T10:51:44Z"
record["observedProbeAgeSeconds"] = 196
Path(sys.argv[2]).write_text(json.dumps(source), encoding="utf-8")
PY

if python3 "$VALIDATOR" "$OVER_BUDGET_PROBE_AGE_EVIDENCE" >"$TMP_DIR/over-budget-probe-age.out" 2>&1; then
  echo "green public path over detection budget unexpectedly passed" >&2
  exit 1
fi
grep -q "externalAuthority.publicPathChecks.websocket.green observedProbeAgeSeconds must be no greater than detectionBudgetSeconds" \
  "$TMP_DIR/over-budget-probe-age.out"

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

for invalid_latency_kind in bool nan infinity; do
  INVALID_CANARY_LATENCY_EVIDENCE="$TMP_DIR/invalid-canary-latency-$invalid_latency_kind.json"
  python3 - "$VALID_EVIDENCE" "$INVALID_CANARY_LATENCY_EVIDENCE" "$invalid_latency_kind" <<'PY'
import json
import sys
from pathlib import Path

source = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
invalid_latency = {
    "bool": True,
    "nan": float("nan"),
    "infinity": float("inf"),
}[sys.argv[3]]
source["mirroredSignals"]["playerflow_canary_latency_ms"][0]["value"] = invalid_latency
Path(sys.argv[2]).write_text(json.dumps(source), encoding="utf-8")
PY

  if python3 "$VALIDATOR" "$INVALID_CANARY_LATENCY_EVIDENCE" \
    >"$TMP_DIR/invalid-canary-latency-$invalid_latency_kind.out" 2>&1; then
    echo "$invalid_latency_kind canary latency unexpectedly passed" >&2
    exit 1
  fi
  grep -q "playerflow_canary_latency_ms values must be a nonnegative finite number" \
    "$TMP_DIR/invalid-canary-latency-$invalid_latency_kind.out"
done

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
    {"alert": "PlayerFlowCanaryLoginFailed", "severity": "P1", "exerciseResult": "passed"}
  ]
}
JSON

if python3 "$VALIDATOR" "$INVALID_EVIDENCE" >"$TMP_DIR/invalid.out" 2>&1; then
  echo "invalid evidence unexpectedly passed" >&2
  exit 1
fi

grep -q "deadmanAuthority.status must be green" "$TMP_DIR/invalid.out"
grep -q "externalAuthority.publicPathChecks missing: telnet" "$TMP_DIR/invalid.out"
grep -q "canaryAlerts missing:" "$TMP_DIR/invalid.out"
for alert in PlayerFlowCanaryCommandFailed PlayerFlowCanaryEvidenceStale PlayerFlowCanaryLatencyHigh; do
  grep -q "$alert" "$TMP_DIR/invalid.out"
done
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

FRESH_FAILURE_EVIDENCE="$TMP_DIR/fresh-failure-evidence.json"
python3 - "$VALID_EVIDENCE" "$FRESH_FAILURE_EVIDENCE" <<'PY'
import json
import sys
from pathlib import Path

source = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
for record in source["mirroredSignals"]["entrypath_blackbox_probe_success"]:
    record["value"] = 0
for record in source["mirroredSignals"]["playerflow_canary_success"]:
    record["value"] = 0
Path(sys.argv[2]).write_text(json.dumps(source), encoding="utf-8")
PY

if python3 "$VALIDATOR" "$FRESH_FAILURE_EVIDENCE" >"$TMP_DIR/fresh-failure-readiness.out" 2>&1; then
  echo "readiness validator unexpectedly accepted fresh failure evidence" >&2
  exit 1
fi
grep -q "missing passing paths" "$TMP_DIR/fresh-failure-readiness.out"
python3 "$VALIDATOR" --allow-failure-evidence "$FRESH_FAILURE_EVIDENCE" \
  >"$TMP_DIR/fresh-failure-incident.out"

RED_EXTERNAL_INCIDENT_EVIDENCE="$TMP_DIR/red-external-incident-evidence.json"
python3 - "$VALID_EVIDENCE" "$RED_EXTERNAL_INCIDENT_EVIDENCE" <<'PY'
import json
import sys
from pathlib import Path

source = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
source["externalAuthority"]["deadmanAuthority"]["status"] = "red"
source["externalAuthority"]["deadmanAuthority"].pop("pageEvidenceRef")
for record in source["externalAuthority"]["publicPathChecks"].values():
    if record["status"] != "not_applicable":
        record["status"] = "red"
        record.pop("pageEvidenceRef", None)
Path(sys.argv[2]).write_text(json.dumps(source), encoding="utf-8")
PY

if python3 "$VALIDATOR" "$RED_EXTERNAL_INCIDENT_EVIDENCE" \
  >"$TMP_DIR/red-external-readiness.out" 2>&1; then
  echo "readiness validator unexpectedly accepted red external incident evidence" >&2
  exit 1
fi
grep -q "deadmanAuthority.status must be green" "$TMP_DIR/red-external-readiness.out"
python3 "$VALIDATOR" --allow-failure-evidence "$RED_EXTERNAL_INCIDENT_EVIDENCE" \
  >"$TMP_DIR/red-external-incident.out"

FALSE_CURRENT_RED_DEADMAN_MIRROR="$TMP_DIR/false-current-red-deadman-mirror.json"
python3 - "$RED_EXTERNAL_INCIDENT_EVIDENCE" "$FALSE_CURRENT_RED_DEADMAN_MIRROR" <<'PY'
import json
import sys
from datetime import datetime
from pathlib import Path

source = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
source["mirroredSignals"][
    "observability_deadman_heartbeat_timestamp_seconds"
]["value"] = datetime.fromisoformat(
    source["verifiedAt"].replace("Z", "+00:00")
).timestamp()
Path(sys.argv[2]).write_text(json.dumps(source), encoding="utf-8")
PY
if python3 "$VALIDATOR" --allow-failure-evidence \
  "$FALSE_CURRENT_RED_DEADMAN_MIRROR" \
  >"$TMP_DIR/false-current-red-deadman-mirror.out" 2>&1; then
  echo "red retained authority accepted a false current deadman mirror" >&2
  exit 1
fi
grep -q "must equal externalAuthority.lastSuccessfulHeartbeatObservedAt" \
  "$TMP_DIR/false-current-red-deadman-mirror.out"

MIXED_FAILURE_EVIDENCE="$TMP_DIR/mixed-failure-evidence.json"
python3 - "$VALID_EVIDENCE" "$MIXED_FAILURE_EVIDENCE" <<'PY'
import json
import sys
from pathlib import Path

source = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
entrypath = source["mirroredSignals"]["entrypath_blackbox_probe_success"]
canary = source["mirroredSignals"]["playerflow_canary_success"]
for record in entrypath:
    record["value"] = 0 if record["path"] == "websocket" else 1
for record in canary:
    record["value"] = 0 if record["path"] == "websocket" else 1
Path(sys.argv[2]).write_text(json.dumps(source), encoding="utf-8")
PY

python3 "$VALIDATOR" --allow-failure-evidence "$MIXED_FAILURE_EVIDENCE" \
  >"$TMP_DIR/mixed-failure-incident.out"

EMPTY_FAILURE_EVIDENCE="$TMP_DIR/empty-failure-evidence.json"
python3 - "$FRESH_FAILURE_EVIDENCE" "$EMPTY_FAILURE_EVIDENCE" <<'PY'
import json
import sys
from pathlib import Path

source = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
source["mirroredSignals"]["entrypath_blackbox_probe_success"] = []
source["mirroredSignals"]["playerflow_canary_success"] = []
Path(sys.argv[2]).write_text(json.dumps(source), encoding="utf-8")
PY

if python3 "$VALIDATOR" --allow-failure-evidence "$EMPTY_FAILURE_EVIDENCE" \
  >"$TMP_DIR/empty-failure.out" 2>&1; then
  echo "incident validator unexpectedly accepted empty failure arrays" >&2
  exit 1
fi
grep -q "must contain one record per exposed path" "$TMP_DIR/empty-failure.out"
grep -q "must contain one record per required flow/path" "$TMP_DIR/empty-failure.out"

BOOL_DEADMAN_EVIDENCE="$TMP_DIR/bool-deadman-evidence.json"
python3 - "$VALID_EVIDENCE" "$BOOL_DEADMAN_EVIDENCE" <<'PY'
import json
import sys
from pathlib import Path

source = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
source["mirroredSignals"]["observability_deadman_heartbeat_timestamp_seconds"]["value"] = True
Path(sys.argv[2]).write_text(json.dumps(source), encoding="utf-8")
PY

if python3 "$VALIDATOR" --allow-failure-evidence "$BOOL_DEADMAN_EVIDENCE" >"$TMP_DIR/bool-deadman.out" 2>&1; then
  echo "boolean deadman timestamp unexpectedly accepted" >&2
  exit 1
fi
grep -q "value must be a positive finite number" "$TMP_DIR/bool-deadman.out"

FUTURE_DEADMAN_EVIDENCE="$TMP_DIR/future-deadman-evidence.json"
python3 - "$VALID_EVIDENCE" "$FUTURE_DEADMAN_EVIDENCE" <<'PY'
import datetime as dt
import json
import sys
from pathlib import Path

path = Path(sys.argv[1])
source = json.loads(path.read_text(encoding="utf-8"))
verified = dt.datetime.fromisoformat(source["verifiedAt"].replace("Z", "+00:00"))
source["mirroredSignals"]["observability_deadman_heartbeat_timestamp_seconds"]["value"] = verified.timestamp() + 2
Path(sys.argv[2]).write_text(json.dumps(source), encoding="utf-8")
PY

if python3 "$VALIDATOR" --allow-failure-evidence "$FUTURE_DEADMAN_EVIDENCE" >"$TMP_DIR/future-deadman.out" 2>&1; then
  echo "future deadman timestamp unexpectedly accepted" >&2
  exit 1
fi
grep -q "cannot be in the future relative to verifiedAt" "$TMP_DIR/future-deadman.out"

echo "player-experience smoke evidence contract checks passed"
