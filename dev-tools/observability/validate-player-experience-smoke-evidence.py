#!/usr/bin/env python3

from __future__ import annotations

import argparse
import datetime as dt
import json
import sys
from pathlib import Path
from typing import Any


REQUIRED_ENTRYPATHS = {"websocket", "telnet"}
REQUIRED_PLAYERFLOW_FLOWS = {"login", "command"}
REQUIRED_CANARY_ALERTS = {
    "PlayerFlowCanaryLoginFailed": "P0",
    "PlayerFlowCanaryCommandFailed": "P1",
    "PlayerFlowCanaryLatencyHigh": "P1",
}
REQUIRED_ENTRYPOINT_CHECKS = {
    "prometheus",
    "alertmanager",
    "grafana",
    "kibana_log_query",
    "jaeger_query",
}


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Validate retained prod-like player-experience smoke evidence."
    )
    parser.add_argument("evidence", type=Path, help="Path to the smoke evidence JSON file")
    args = parser.parse_args()

    findings = validate_evidence(args.evidence)
    if findings:
        for finding in findings:
            print(f"ERROR: {finding}", file=sys.stderr)
        return 1
    print("player-experience smoke evidence validation: OK")
    return 0


def validate_evidence(path: Path) -> list[str]:
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except Exception as exc:
        return [f"evidence JSON is unreadable: {exc}"]
    if not isinstance(data, dict):
        return ["evidence root must be a JSON object"]

    findings: list[str] = []
    if not data.get("deploymentRef") and not data.get("recoveryRef"):
        findings.append("deploymentRef or recoveryRef is required")
    findings.extend(_require_non_empty_string(data, "verifiedBy"))
    findings.extend(_require_non_empty_string(data, "preflightEvidenceRef"))
    findings.extend(_validate_timestamp(data.get("verifiedAt"), "verifiedAt"))
    findings.extend(_validate_external_authority(data.get("externalAuthority")))
    findings.extend(_validate_mirrored_signals(data.get("mirroredSignals")))
    findings.extend(_validate_canary_alerts(data.get("canaryAlerts")))
    return findings


def _require_non_empty_string(data: dict[str, Any], key: str) -> list[str]:
    value = data.get(key)
    if not isinstance(value, str) or not value.strip():
        return [f"{key} is required"]
    return []


def _validate_timestamp(value: Any, key: str) -> list[str]:
    if not isinstance(value, str) or not value.endswith("Z"):
        return [f"{key} must be an RFC3339 UTC timestamp ending in Z"]
    try:
        dt.datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError as exc:
        return [f"{key} is not parseable: {exc}"]
    return []


def _validate_external_authority(value: Any) -> list[str]:
    if not isinstance(value, dict):
        return ["externalAuthority is required"]
    findings: list[str] = []
    deadman = value.get("deadmanAuthority")
    if not isinstance(deadman, dict):
        findings.append("externalAuthority.deadmanAuthority is required")
    else:
        if deadman.get("status") != "green":
            findings.append("externalAuthority.deadmanAuthority.status must be green")
        if not isinstance(deadman.get("evidenceRef"), str) or not deadman.get("evidenceRef").strip():
            findings.append("externalAuthority.deadmanAuthority.evidenceRef is required")
    checks = value.get("entrypointChecks")
    if not isinstance(checks, dict):
        findings.append("externalAuthority.entrypointChecks is required")
        return findings
    missing = sorted(REQUIRED_ENTRYPOINT_CHECKS - checks.keys())
    if missing:
        findings.append("externalAuthority.entrypointChecks missing: " + ", ".join(missing))
    not_green = sorted(key for key in REQUIRED_ENTRYPOINT_CHECKS if checks.get(key) != "green")
    if not_green:
        findings.append("externalAuthority.entrypointChecks not green: " + ", ".join(not_green))
    return findings


def _validate_mirrored_signals(value: Any) -> list[str]:
    if not isinstance(value, dict):
        return ["mirroredSignals is required"]
    findings: list[str] = []
    findings.extend(_validate_entrypath_signals(value.get("entrypath_blackbox_probe_success")))
    findings.extend(_validate_deadman_signal(value.get("observability_deadman_heartbeat_timestamp_seconds")))
    findings.extend(_validate_playerflow_success(value.get("playerflow_canary_success")))
    findings.extend(_validate_playerflow_latency(value.get("playerflow_canary_latency_ms")))
    return findings


def _validate_entrypath_signals(value: Any) -> list[str]:
    records = _records(value)
    if records is None:
        return ["mirroredSignals.entrypath_blackbox_probe_success must be a list"]
    passing_paths = {record.get("path") for record in records if record.get("value") == 1}
    missing = sorted(REQUIRED_ENTRYPATHS - passing_paths)
    if missing:
        return ["entrypath_blackbox_probe_success missing passing paths: " + ", ".join(missing)]
    return []


def _validate_deadman_signal(value: Any) -> list[str]:
    if not isinstance(value, dict):
        return ["mirroredSignals.observability_deadman_heartbeat_timestamp_seconds is required"]
    findings: list[str] = []
    if not isinstance(value.get("source"), str) or not value.get("source").strip():
        findings.append("observability_deadman_heartbeat_timestamp_seconds.source is required")
    if not isinstance(value.get("value"), (int, float)) or value.get("value") <= 0:
        findings.append("observability_deadman_heartbeat_timestamp_seconds.value must be positive")
    return findings


def _validate_playerflow_success(value: Any) -> list[str]:
    records = _records(value)
    if records is None:
        return ["mirroredSignals.playerflow_canary_success must be a list"]
    passing_flows = {record.get("flow") for record in records if record.get("value") == 1}
    missing = sorted(REQUIRED_PLAYERFLOW_FLOWS - passing_flows)
    if missing:
        return ["playerflow_canary_success missing passing flows: " + ", ".join(missing)]
    return []


def _validate_playerflow_latency(value: Any) -> list[str]:
    records = _records(value)
    if records is None:
        return ["mirroredSignals.playerflow_canary_latency_ms must be a list"]
    command_records = [
        record
        for record in records
        if record.get("flow") == "command" and isinstance(record.get("value"), (int, float))
    ]
    if not command_records:
        return ["playerflow_canary_latency_ms must include a numeric command latency record"]
    if any(record["value"] < 0 for record in command_records):
        return ["playerflow_canary_latency_ms values must be non-negative"]
    return []


def _validate_canary_alerts(value: Any) -> list[str]:
    records = _records(value)
    if records is None:
        return ["canaryAlerts must be a list"]
    by_name = {record.get("alert"): record for record in records}
    findings: list[str] = []
    missing = sorted(REQUIRED_CANARY_ALERTS - by_name.keys())
    if missing:
        findings.append("canaryAlerts missing: " + ", ".join(missing))
    for alert, severity in REQUIRED_CANARY_ALERTS.items():
        record = by_name.get(alert)
        if not isinstance(record, dict):
            continue
        if record.get("severity") != severity:
            findings.append(f"{alert} severity must be {severity}")
        if record.get("exerciseResult") != "passed":
            findings.append(f"{alert} exerciseResult must be passed")
    return findings


def _records(value: Any) -> list[dict[str, Any]] | None:
    if not isinstance(value, list):
        return None
    if not all(isinstance(item, dict) for item in value):
        return None
    return value


if __name__ == "__main__":
    raise SystemExit(main())
