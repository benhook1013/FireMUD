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
  "externalAuthority": {
    "deadmanIncidentOpened": true,
    "entrypointChecks": {
      "prometheus": "green",
      "alertmanager": "green",
      "grafana": "green",
      "kibana_log_query": "green",
      "jaeger_query": "green"
    }
  },
  "mirroredSignals": {
    "entrypath_blackbox_probe_success": [
      {"path": "websocket", "target": "staging-web-gateway", "value": 1},
      {"path": "telnet", "target": "staging-telnet-edge", "value": 1}
    ],
    "observability_deadman_heartbeat_timestamp_seconds": {
      "source": "staging",
      "value": 1773917600
    },
    "playerflow_canary_success": [
      {"flow": "login", "path": "websocket", "target": "staging-web-gateway", "value": 1},
      {"flow": "command", "path": "websocket", "target": "staging-web-gateway", "value": 1}
    ],
    "playerflow_canary_latency_ms": [
      {"flow": "command", "path": "websocket", "target": "staging-web-gateway", "value": 184}
    ]
  },
  "canaryAlerts": [
    {"alert": "PlayerFlowCanaryLoginFailed", "severity": "P0", "exerciseResult": "passed"},
    {"alert": "PlayerFlowCanaryCommandFailed", "severity": "P1", "exerciseResult": "passed"},
    {"alert": "PlayerFlowCanaryLatencyHigh", "severity": "P1", "exerciseResult": "passed"}
  ]
}
JSON

python3 "$VALIDATOR" "$VALID_EVIDENCE" >/tmp/firemud-player-experience-smoke-valid.out

cat >"$INVALID_EVIDENCE" <<'JSON'
{
  "deploymentRef": "staging-2026-03-19-a",
  "verifiedAt": "2026-03-19T10:55:00Z",
  "verifiedBy": "operator@example",
  "preflightEvidenceRef": "ci://observability-smoke/2026-03-19T10:40:00Z",
  "externalAuthority": {
    "deadmanIncidentOpened": false,
    "entrypointChecks": {
      "prometheus": "green"
    }
  },
  "mirroredSignals": {
    "entrypath_blackbox_probe_success": [
      {"path": "websocket", "target": "staging-web-gateway", "value": 1}
    ],
    "observability_deadman_heartbeat_timestamp_seconds": {
      "source": "staging",
      "value": 1773917600
    },
    "playerflow_canary_success": [
      {"flow": "login", "path": "websocket", "target": "staging-web-gateway", "value": 1}
    ],
    "playerflow_canary_latency_ms": []
  },
  "canaryAlerts": [
    {"alert": "PlayerFlowCanaryLoginFailed", "severity": "P0", "exerciseResult": "passed"}
  ]
}
JSON

if python3 "$VALIDATOR" "$INVALID_EVIDENCE" >/tmp/firemud-player-experience-smoke-invalid.out 2>&1; then
  echo "invalid evidence unexpectedly passed" >&2
  exit 1
fi

grep -q "deadmanIncidentOpened must be true" /tmp/firemud-player-experience-smoke-invalid.out
grep -q "entrypath_blackbox_probe_success missing passing paths: telnet" /tmp/firemud-player-experience-smoke-invalid.out
grep -q "canaryAlerts missing: PlayerFlowCanaryCommandFailed, PlayerFlowCanaryLatencyHigh" /tmp/firemud-player-experience-smoke-invalid.out

echo "player-experience smoke evidence contract checks passed"
