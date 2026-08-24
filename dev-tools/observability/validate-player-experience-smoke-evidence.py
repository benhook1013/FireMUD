#!/usr/bin/env python3

from __future__ import annotations

import argparse
import datetime as dt
import json
import math
import sys
from pathlib import Path
from typing import Any

REQUIRED_PLAYERFLOW_FLOWS = {"login", "command"}
REQUIRED_EXTERNAL_PROFILES = {"independent-required", "independent-omitted"}
REQUIRED_PUBLIC_PATHS = {"websocket", "telnet"}
METRIC_TARGET_BY_PATH = {"websocket": "gateway", "telnet": "tcp_proxy"}
PLAYERFLOW_CANARY_LAST_RUN_TIMESTAMP_METRIC = (
    "playerflow_canary_last_run_timestamp_seconds"
)
PLAYERFLOW_CANARY_FRESHNESS_BUDGET_METRIC = (
    "playerflow_canary_freshness_budget_seconds"
)
REQUIRED_CANARY_ALERTS = {
    "PlayerFlowCanaryLoginFailed": "P0",
    "PlayerFlowCanaryCommandFailed": "P1",
    "PlayerFlowCanaryLatencyHigh": "P1",
    "PlayerFlowCanaryEvidenceStale": "P1",
}
CANARY_ALERT_MAX_HOLD_SECONDS = 2 * 60
CANARY_ALERT_EVALUATION_MARGIN_SECONDS = 60
MIN_CANARY_DETECTION_BUDGET_SECONDS = (
    CANARY_ALERT_MAX_HOLD_SECONDS + CANARY_ALERT_EVALUATION_MARGIN_SECONDS
)
REQUIRED_CAPABILITIES = {"prometheusMirrors", "playerFlowCanary"}
CAPABILITY_VALUES = {
    "prometheusMirrors": {"published", "omitted"},
    "playerFlowCanary": {"advertised", "omitted"},
}
EXECUTION_MODES = {"live", "simulated"}
AUTHORITY_PROVENANCES = {"retained-external", "synthetic"}


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
    except (OSError, UnicodeError, json.JSONDecodeError) as exc:
        return [f"evidence JSON is unreadable: {exc}"]
    if not isinstance(data, dict):
        return ["evidence root must be a JSON object"]

    findings: list[str] = []
    if not data.get("deploymentRef") and not data.get("recoveryRef"):
        findings.append("deploymentRef or recoveryRef is required")
    findings.extend(_require_non_empty_string(data, "verifiedBy"))
    findings.extend(_require_non_empty_string(data, "preflightEvidenceRef"))
    findings.extend(_validate_timestamp(data.get("verifiedAt"), "verifiedAt"))
    execution_mode = data.get("executionMode")
    authority_provenance = data.get("externalAuthorityProvenance")
    findings.extend(_validate_execution_provenance(execution_mode, authority_provenance))
    capabilities, capability_findings = _validate_capabilities(data.get("capabilities"))
    findings.extend(capability_findings)
    external_authority = data.get("externalAuthority")
    findings.extend(
        _validate_external_authority(
            external_authority,
            execution_mode,
            authority_provenance,
            data.get("verifiedAt"),
            capabilities.get("playerFlowCanary") == "advertised",
        )
    )
    findings.extend(
        _validate_mirrored_signals(
            data.get("mirroredSignals"),
            external_authority,
            capabilities,
            data.get("verifiedAt"),
        )
    )
    findings.extend(
        _validate_canary_alerts(
            data.get("canaryAlerts"),
            capabilities.get("playerFlowCanary") == "advertised",
        )
    )
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


def _validate_execution_provenance(
    execution_mode: Any, authority_provenance: Any
) -> list[str]:
    findings: list[str] = []
    if execution_mode not in EXECUTION_MODES:
        findings.append("executionMode must be live or simulated")
    if authority_provenance not in AUTHORITY_PROVENANCES:
        findings.append(
            "externalAuthorityProvenance must be retained-external or synthetic"
        )
    if execution_mode == "live" and authority_provenance != "retained-external":
        findings.append("live evidence must use retained-external authority provenance")
    if authority_provenance == "synthetic" and execution_mode != "simulated":
        findings.append("synthetic authority provenance is allowed only for simulated evidence")
    return findings


def _validate_capabilities(value: Any) -> tuple[dict[str, str], list[str]]:
    if not isinstance(value, dict):
        return {}, ["capabilities is required"]
    findings: list[str] = []
    missing = sorted(REQUIRED_CAPABILITIES - set(value))
    if missing:
        findings.append("capabilities missing: " + ", ".join(missing))
    unexpected = sorted(set(value) - REQUIRED_CAPABILITIES)
    if unexpected:
        findings.append("capabilities has unsupported entries: " + ", ".join(unexpected))
    capabilities: dict[str, str] = {}
    for key in sorted(REQUIRED_CAPABILITIES & set(value)):
        capability = value[key]
        if capability not in CAPABILITY_VALUES[key]:
            findings.append(
                f"capabilities.{key} must be one of "
                + ", ".join(sorted(CAPABILITY_VALUES[key]))
            )
        else:
            capabilities[key] = capability
    return capabilities, findings


def _validate_external_authority(
    value: Any,
    execution_mode: Any,
    authority_provenance: Any,
    verified_at: Any,
    canary_advertised: bool,
) -> list[str]:
    if not isinstance(value, dict):
        return ["externalAuthority is required"]
    profile = value.get("profile")
    allow_synthetic_refs = (
        execution_mode == "simulated" and authority_provenance == "synthetic"
    )
    if profile not in REQUIRED_EXTERNAL_PROFILES:
        return [
            "externalAuthority.profile must be independent-required or independent-omitted"
        ]
    findings, exposed_paths = _validate_exposed_paths(value)
    if profile == "independent-omitted":
        findings.extend(_validate_omitted_authority(value))
        allowed_fields = {"profile", "reason", "exposedPublicPlayerPaths"}
        if canary_advertised:
            allowed_fields.add("detectionBudgetSeconds")
            findings.extend(
                _validate_positive_finite_number(
                    value.get("detectionBudgetSeconds"),
                    "externalAuthority.detectionBudgetSeconds",
                )
            )
        unexpected = sorted(set(value) - allowed_fields)
        if unexpected:
            findings.append(
                "externalAuthority.independent-omitted must not include external authority fields: "
                + ", ".join(unexpected)
            )
        return findings

    findings.extend(
        _validate_positive_finite_number(
            value.get("detectionBudgetSeconds"),
            "externalAuthority.detectionBudgetSeconds",
        )
    )
    findings.extend(_validate_external_authority_freshness(value, verified_at))

    deadman = value.get("deadmanAuthority")
    if not isinstance(deadman, dict):
        findings.append("externalAuthority.deadmanAuthority is required")
    else:
        findings.extend(
            _validate_authority_record(
                deadman,
                "externalAuthority.deadmanAuthority",
                require_green=True,
                allow_synthetic_refs=allow_synthetic_refs,
            )
        )

    checks = value.get("publicPathChecks")
    if not isinstance(checks, dict):
        findings.append("externalAuthority.publicPathChecks is required")
        return findings
    missing = sorted(REQUIRED_PUBLIC_PATHS - checks.keys())
    if missing:
        findings.append("externalAuthority.publicPathChecks missing: " + ", ".join(missing))
    extra = sorted(set(checks) - REQUIRED_PUBLIC_PATHS)
    if extra:
        findings.append("externalAuthority.publicPathChecks has unsupported paths: " + ", ".join(extra))
    for path in sorted(REQUIRED_PUBLIC_PATHS & checks.keys()):
        record = checks.get(path)
        if not isinstance(record, dict):
            findings.append(f"externalAuthority.publicPathChecks.{path} must be an object")
            continue
        if path in exposed_paths:
            findings.extend(
                _validate_public_path_record(
                    record,
                    f"externalAuthority.publicPathChecks.{path}",
                    allow_synthetic_refs,
                )
            )
        else:
            findings.extend(
                _validate_not_applicable_path_record(
                    record, f"externalAuthority.publicPathChecks.{path}"
                )
            )
    findings.extend(_validate_required_source_timestamps(value, exposed_paths))
    return findings


def _validate_exposed_paths(value: dict[str, Any]) -> tuple[list[str], set[str]]:
    raw_paths = value.get("exposedPublicPlayerPaths")
    findings: list[str] = []
    if not isinstance(raw_paths, list) or not raw_paths:
        return ["externalAuthority.exposedPublicPlayerPaths is required"], set()
    if any(not isinstance(path, str) for path in raw_paths):
        findings.append(
            "externalAuthority.exposedPublicPlayerPaths must contain only strings"
        )
    paths = [path for path in raw_paths if isinstance(path, str)]
    if len(paths) != len(set(paths)):
        findings.append("externalAuthority.exposedPublicPlayerPaths must not contain duplicates")
    invalid_paths = sorted(set(paths) - REQUIRED_PUBLIC_PATHS)
    if invalid_paths:
        findings.append(
            "externalAuthority.exposedPublicPlayerPaths contains unsupported paths: "
            + ", ".join(invalid_paths)
        )
    return findings, set(paths)


def _validate_omitted_authority(value: dict[str, Any]) -> list[str]:
    reason = value.get("reason")
    if not isinstance(reason, str) or not reason.strip():
        return ["externalAuthority.reason is required for independent-omitted"]
    return []


def _validate_authority_record(
    record: dict[str, Any],
    key: str,
    require_green: bool,
    allow_synthetic_refs: bool,
) -> list[str]:
    findings: list[str] = []
    status = record.get("status")
    if status not in {"green", "red"}:
        findings.append(f"{key}.status must be green or red")
    elif require_green and status != "green":
        findings.append(f"{key}.status must be green")
    for field in ("evidenceRef", "target", "checkRef"):
        value = record.get(field)
        if not isinstance(value, str) or not value.strip():
            findings.append(f"{key}.{field} is required")
        else:
            findings.extend(
                _validate_reference_provenance(
                    value, f"{key}.{field}", allow_synthetic_refs
                )
            )
    return findings


def _validate_public_path_record(
    record: dict[str, Any], key: str, allow_synthetic_refs: bool
) -> list[str]:
    findings: list[str] = []
    status = record.get("status")
    if status != "green":
        findings.append(f"{key}.status must be green")
    for field in ("evidenceRef", "target"):
        value = record.get(field)
        if not isinstance(value, str) or not value.strip():
            findings.append(f"{key}.{field} is required")
        else:
            findings.extend(
                _validate_reference_provenance(
                    value, f"{key}.{field}", allow_synthetic_refs
                )
            )
    return findings


def _validate_reference_provenance(
    value: str, key: str, allow_synthetic_refs: bool
) -> list[str]:
    synthetic = value.startswith(("synthetic://", "synthetic-"))
    if synthetic and not allow_synthetic_refs:
        return [f"{key} must not use a synthetic reference for retained evidence"]
    if not synthetic and allow_synthetic_refs:
        return [f"{key} must use a synthetic reference for synthetic evidence"]
    return []


def _validate_not_applicable_path_record(
    record: dict[str, Any], key: str
) -> list[str]:
    if record != {"status": "not_applicable"}:
        return [f"{key} must be exactly {{'status': 'not_applicable'}}"]
    return []


def _validate_required_source_timestamps(
    value: dict[str, Any], exposed_paths: set[str]
) -> list[str]:
    findings: list[str] = []
    if value.get("lastSuccessfulHeartbeatObservedAt") is None:
        findings.append(
            "externalAuthority.lastSuccessfulHeartbeatObservedAt is required for independent-required"
        )
    checks = value.get("publicPathChecks")
    if not isinstance(checks, dict):
        return findings
    for path in sorted(exposed_paths):
        record = checks.get(path)
        if isinstance(record, dict) and record.get("lastSuccessfulProbeObservedAt") is None:
            findings.append(
                f"externalAuthority.publicPathChecks.{path}.lastSuccessfulProbeObservedAt is required for independent-required"
            )
    return findings


def _validate_mirrored_signals(
    value: Any,
    external_authority: Any,
    capabilities: dict[str, str],
    verified_at: Any,
) -> list[str]:
    if not isinstance(value, dict):
        return ["mirroredSignals is required"]
    exposed_paths = _declared_exposed_paths(external_authority)
    if exposed_paths is None:
        exposed_paths = set()
    profile = external_authority.get("profile") if isinstance(external_authority, dict) else None
    findings: list[str] = []
    mirrors_published = capabilities.get("prometheusMirrors") == "published"
    entrypath_key = "entrypath_blackbox_probe_success"
    if mirrors_published:
        findings.extend(
            _validate_entrypath_signals(
                value.get(entrypath_key), exposed_paths
            )
        )
    elif entrypath_key in value:
        findings.append(
            f"mirroredSignals.{entrypath_key} requires capabilities.prometheusMirrors=published"
        )
    deadman_key = "observability_deadman_heartbeat_timestamp_seconds"
    if profile == "independent-omitted":
        if deadman_key in value:
            findings.append(f"mirroredSignals.{deadman_key} must be absent for independent-omitted")
    elif mirrors_published:
        findings.extend(_validate_deadman_signal(value.get(deadman_key)))
    elif deadman_key in value:
        findings.append(
            f"mirroredSignals.{deadman_key} requires capabilities.prometheusMirrors=published"
        )

    canary_advertised = capabilities.get("playerFlowCanary") == "advertised"
    canary_keys = {
        "playerflow_canary_success",
        "playerflow_canary_latency_ms",
        PLAYERFLOW_CANARY_LAST_RUN_TIMESTAMP_METRIC,
        PLAYERFLOW_CANARY_FRESHNESS_BUDGET_METRIC,
    }
    canary_present = canary_keys & set(value)
    if canary_advertised:
        findings.extend(
            _validate_playerflow_success(
                value.get("playerflow_canary_success"), exposed_paths, profile
            )
        )
        findings.extend(
            _validate_playerflow_latency(
                value.get("playerflow_canary_latency_ms"), exposed_paths, profile
            )
        )
        freshness_budget = (
            external_authority.get("detectionBudgetSeconds")
            if isinstance(external_authority, dict)
            else None
        )
        findings.extend(
            _validate_playerflow_last_run(
                value.get(PLAYERFLOW_CANARY_LAST_RUN_TIMESTAMP_METRIC),
                exposed_paths,
                verified_at,
                freshness_budget,
                profile,
            )
        )
        findings.extend(
            _validate_canary_freshness_budget(
                value.get(PLAYERFLOW_CANARY_FRESHNESS_BUDGET_METRIC),
                profile,
                freshness_budget,
            )
        )
        findings.extend(_validate_canary_detection_budget_minimum(freshness_budget))
    elif canary_present:
        findings.append(
            "mirroredSignals player-flow canary records require capabilities.playerFlowCanary=advertised"
        )
    return findings


def _declared_exposed_paths(value: Any) -> set[str] | None:
    if not isinstance(value, dict):
        return None
    paths = value.get("exposedPublicPlayerPaths")
    if not isinstance(paths, list) or any(not isinstance(path, str) for path in paths):
        return None
    return set(paths)


def _validate_entrypath_signals(value: Any, exposed_paths: set[str]) -> list[str]:
    records = _records(value)
    if records is None:
        return ["mirroredSignals.entrypath_blackbox_probe_success must be a list"]
    record_paths = [record.get("path") for record in records]
    if any(not isinstance(path, str) for path in record_paths):
        return ["entrypath_blackbox_probe_success records must declare string paths"]
    if len(record_paths) != len(set(record_paths)):
        return ["entrypath_blackbox_probe_success must contain one record per exposed path"]
    extra = sorted(set(record_paths) - exposed_paths)
    if extra:
        return ["entrypath_blackbox_probe_success contains non-exposed paths: " + ", ".join(extra)]
    target_findings = []
    for record in records:
        target_findings.extend(
            _validate_metric_target(
                record, record["path"], "entrypath_blackbox_probe_success"
            )
        )
    passing_paths = {
        record["path"] for record in records if record.get("value") == 1
    }
    missing = sorted(exposed_paths - passing_paths)
    if missing:
        target_findings.append(
            "entrypath_blackbox_probe_success missing passing paths: "
            + ", ".join(missing)
        )
    return target_findings


def _validate_deadman_signal(value: Any) -> list[str]:
    if not isinstance(value, dict):
        return ["mirroredSignals.observability_deadman_heartbeat_timestamp_seconds is required"]
    findings: list[str] = []
    if not isinstance(value.get("source"), str) or not value.get("source").strip():
        findings.append("observability_deadman_heartbeat_timestamp_seconds.source is required")
    if not isinstance(value.get("value"), (int, float)) or value.get("value") <= 0:
        findings.append("observability_deadman_heartbeat_timestamp_seconds.value must be positive")
    return findings


def _validate_playerflow_success(
    value: Any, exposed_paths: set[str], profile: Any
) -> list[str]:
    records = _records(value)
    if records is None:
        return ["mirroredSignals.playerflow_canary_success must be a list"]
    findings: list[str] = []
    passing_flows = {path: set() for path in exposed_paths}
    seen: set[tuple[str, str]] = set()
    for record in records:
        path = record.get("path")
        if not isinstance(path, str):
            findings.append("playerflow_canary_success must declare a string canary path")
            continue
        if path not in exposed_paths:
            findings.append(
                f"playerflow_canary_success contains non-exposed path: {path}"
            )
            continue
        if path not in METRIC_TARGET_BY_PATH:
            findings.append(
                f"playerflow_canary_success contains unsupported path: {path}"
            )
            continue
        flow = record.get("flow")
        if flow not in REQUIRED_PLAYERFLOW_FLOWS:
            findings.append(
                f"playerflow_canary_success contains unsupported flow: {flow}"
            )
            continue
        key = (flow, path)
        if key in seen:
            findings.append(
                "playerflow_canary_success must not duplicate flow/path: "
                f"{flow}/{path}"
            )
            continue
        seen.add(key)
        findings.extend(
            _validate_metric_target(
                record, path, "playerflow_canary_success", profile
            )
        )
        if record.get("value") == 1:
            passing_flows[path].add(flow)
    for path in sorted(exposed_paths):
        missing = sorted(REQUIRED_PLAYERFLOW_FLOWS - passing_flows[path])
        if missing:
            findings.append(
                f"playerflow_canary_success missing passing flows for path {path!r}: "
                + ", ".join(missing)
            )
    return findings


def _validate_playerflow_latency(
    value: Any, exposed_paths: set[str], profile: Any
) -> list[str]:
    records = _records(value)
    if records is None:
        return ["mirroredSignals.playerflow_canary_latency_ms must be a list"]
    findings: list[str] = []
    latency_paths: set[str] = set()
    seen: set[tuple[str, str]] = set()
    command_record_seen = False
    for record in records:
        if record.get("flow") != "command":
            findings.append(
                "playerflow_canary_latency_ms contains unsupported flow: "
                f"{record.get('flow')}"
            )
            continue
        command_record_seen = True
        path = record.get("path")
        if not isinstance(path, str):
            findings.append("playerflow_canary_latency_ms must declare a string canary path")
            continue
        latency_paths.add(path)
        if path not in exposed_paths:
            findings.append(
                f"playerflow_canary_latency_ms contains non-exposed path: {path}"
            )
            continue
        if path not in METRIC_TARGET_BY_PATH:
            findings.append(
                f"playerflow_canary_latency_ms contains unsupported path: {path}"
            )
            continue
        key = ("command", path)
        if key in seen:
            findings.append(
                "playerflow_canary_latency_ms must not duplicate flow/path: "
                f"command/{path}"
            )
            continue
        seen.add(key)
        if not isinstance(record.get("value"), (int, float)):
            findings.append(
                "playerflow_canary_latency_ms command values must be numeric"
            )
            continue
        if record["value"] < 0:
            findings.append(
                "playerflow_canary_latency_ms values must be non-negative"
            )
        findings.extend(
            _validate_metric_target(
                record, path, "playerflow_canary_latency_ms", profile
            )
        )
    if not command_record_seen:
        findings.append(
            "playerflow_canary_latency_ms must include a numeric command latency record"
        )
    missing = sorted(exposed_paths - latency_paths)
    if missing:
        findings.append(
            "playerflow_canary_latency_ms missing exposed paths: "
            + ", ".join(missing)
        )
    return findings


def _validate_playerflow_last_run(
    value: Any,
    exposed_paths: set[str],
    verified_at: Any,
    freshness_budget: Any,
    profile: Any,
) -> list[str]:
    records = _records(value)
    if records is None:
        return [
            f"mirroredSignals.{PLAYERFLOW_CANARY_LAST_RUN_TIMESTAMP_METRIC} must be a list"
        ]
    findings: list[str] = []
    seen: set[tuple[str, str]] = set()
    verified_epoch = _timestamp_epoch(verified_at)
    budget = (
        freshness_budget
        if isinstance(freshness_budget, (int, float))
        and not isinstance(freshness_budget, bool)
        and math.isfinite(freshness_budget)
        and freshness_budget > 0
        else None
    )
    for record in records:
        path = record.get("path")
        flow = record.get("flow")
        if not isinstance(path, str):
            findings.append(
                f"{PLAYERFLOW_CANARY_LAST_RUN_TIMESTAMP_METRIC} must declare a string canary path"
            )
            continue
        if path not in exposed_paths:
            findings.append(
                f"{PLAYERFLOW_CANARY_LAST_RUN_TIMESTAMP_METRIC} contains non-exposed path: {path}"
            )
            continue
        if path not in METRIC_TARGET_BY_PATH:
            findings.append(
                f"{PLAYERFLOW_CANARY_LAST_RUN_TIMESTAMP_METRIC} contains unsupported path: {path}"
            )
            continue
        if flow not in REQUIRED_PLAYERFLOW_FLOWS:
            findings.append(
                f"{PLAYERFLOW_CANARY_LAST_RUN_TIMESTAMP_METRIC} contains unsupported flow: {flow}"
            )
            continue
        findings.extend(
            _validate_metric_target(
                record,
                path,
                PLAYERFLOW_CANARY_LAST_RUN_TIMESTAMP_METRIC,
                profile,
            )
        )
        key = (flow, path)
        if key in seen:
            findings.append(
                f"{PLAYERFLOW_CANARY_LAST_RUN_TIMESTAMP_METRIC} must not duplicate flow/path: {flow}/{path}"
            )
        seen.add(key)
        timestamp = record.get("value")
        if (
            isinstance(timestamp, bool)
            or not isinstance(timestamp, (int, float))
            or not math.isfinite(timestamp)
            or timestamp <= 0
        ):
            findings.append(
                f"{PLAYERFLOW_CANARY_LAST_RUN_TIMESTAMP_METRIC} values must be positive finite timestamps"
            )
            continue
        if verified_epoch is not None:
            if timestamp > verified_epoch + 1:
                findings.append(
                    f"{PLAYERFLOW_CANARY_LAST_RUN_TIMESTAMP_METRIC} value cannot be in the future"
                )
            if budget is not None and verified_epoch - timestamp > budget:
                findings.append(
                    f"{PLAYERFLOW_CANARY_LAST_RUN_TIMESTAMP_METRIC} value is older than the configured detection budget"
                )
    required = {(flow, path) for flow in REQUIRED_PLAYERFLOW_FLOWS for path in exposed_paths}
    missing = sorted(required - seen)
    if missing:
        findings.append(
            f"{PLAYERFLOW_CANARY_LAST_RUN_TIMESTAMP_METRIC} missing exposed flow/path records: "
            + ", ".join(f"{flow}/{path}" for flow, path in missing)
        )
    return findings


def _validate_canary_freshness_budget(
    value: Any, profile: str, authoritative_budget: Any
) -> list[str]:
    key = f"mirroredSignals.{PLAYERFLOW_CANARY_FRESHNESS_BUDGET_METRIC}"
    if not isinstance(value, dict):
        return [f"{key} is required"]
    budget_findings = _validate_positive_finite_number(value.get("value"), f"{key}.value")
    findings = list(budget_findings)
    if value.get("profile") != profile:
        findings.append(f"{key}.profile must be {profile!r}")
    if (
        not budget_findings
        and value.get("value") != authoritative_budget
    ):
        findings.append(
            f"{key}.value must equal externalAuthority.detectionBudgetSeconds"
        )
    return findings


def _validate_canary_detection_budget_minimum(value: Any) -> list[str]:
    if (
        isinstance(value, bool)
        or not isinstance(value, (int, float))
        or not math.isfinite(value)
        or value <= 0
        or value >= MIN_CANARY_DETECTION_BUDGET_SECONDS
    ):
        return []
    message = (
        "externalAuthority.detectionBudgetSeconds must be at least "
        + f"{MIN_CANARY_DETECTION_BUDGET_SECONDS} seconds for the canonical canary alert hold"
    )
    return [message]


def _validate_external_authority_freshness(
    value: dict[str, Any], verified_at: Any
) -> list[str]:
    key = "externalAuthority.evidenceObservedAt"
    findings = _validate_timestamp(value.get("evidenceObservedAt"), key)
    if findings:
        return findings
    observed_epoch = _timestamp_epoch(value.get("evidenceObservedAt"))
    verified_epoch = _timestamp_epoch(verified_at)
    if observed_epoch is not None:
        findings.extend(
            _validate_source_timestamp_chronology(
                value.get("lastSuccessfulHeartbeatObservedAt"),
                "externalAuthority.lastSuccessfulHeartbeatObservedAt",
                observed_epoch,
            )
        )
        checks = value.get("publicPathChecks")
        if isinstance(checks, dict):
            for path, record in checks.items():
                if isinstance(record, dict):
                    findings.extend(
                        _validate_source_timestamp_chronology(
                            record.get("lastSuccessfulProbeObservedAt"),
                            f"externalAuthority.publicPathChecks.{path}.lastSuccessfulProbeObservedAt",
                            observed_epoch,
                        )
                    )
    budget = value.get("detectionBudgetSeconds")
    if observed_epoch is None or verified_epoch is None:
        return findings
    if (
        isinstance(budget, bool)
        or not isinstance(budget, (int, float))
        or not math.isfinite(budget)
        or budget <= 0
    ):
        return findings
    evidence_age = verified_epoch - observed_epoch
    if evidence_age < 0:
        findings.append(
            f"{key} cannot be in the future relative to verifiedAt"
        )
    elif evidence_age > budget:
        findings.append(
            f"{key} is older than externalAuthority.detectionBudgetSeconds"
        )
    return findings


def _validate_source_timestamp_chronology(
    value: Any, key: str, evidence_observed_epoch: float
) -> list[str]:
    if value is None:
        return []
    timestamp_findings = _validate_timestamp(value, key)
    if timestamp_findings:
        return timestamp_findings
    source_epoch = _timestamp_epoch(value)
    if source_epoch is not None and source_epoch > evidence_observed_epoch:
        return [f"{key} cannot be later than externalAuthority.evidenceObservedAt"]
    return []


def _validate_positive_finite_number(value: Any, key: str) -> list[str]:
    if (
        isinstance(value, bool)
        or not isinstance(value, (int, float))
        or not math.isfinite(value)
        or value <= 0
    ):
        return [f"{key} must be a positive finite number"]
    return []


def _timestamp_epoch(value: Any) -> float | None:
    if not isinstance(value, str) or not value.endswith("Z"):
        return None
    try:
        return dt.datetime.fromisoformat(value.replace("Z", "+00:00")).timestamp()
    except ValueError:
        return None


def _validate_metric_target(
    record: dict[str, Any],
    path: str,
    metric: str,
    expected_profile: Any = None,
) -> list[str]:
    expected_target = METRIC_TARGET_BY_PATH.get(path)
    findings: list[str] = []
    if record.get("target") != expected_target:
        findings.append(
            f"{metric} target for path {path!r} must be {expected_target!r}"
        )
    if expected_profile is not None and record.get("profile") != expected_profile:
        findings.append(f"{metric} profile must be {expected_profile!r}")
    return findings


def _validate_canary_alerts(value: Any, required: bool) -> list[str]:
    if not required:
        if value in (None, []):
            return []
        return ["canaryAlerts must be absent when player-flow canary capability is omitted"]
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
