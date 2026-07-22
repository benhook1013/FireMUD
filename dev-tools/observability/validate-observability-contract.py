#!/usr/bin/env python3

from __future__ import annotations

import json
import re
import sys
from dataclasses import dataclass
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[2]

ALLOWED_SEVERITIES = {"P0", "P1", "P2"}
REQUIRED_ALERT_LABELS = {"service", "severity", "owner", "runbook"}
DISALLOWED_ALERT_SERVICE_LABELS = {"gateway", "game-session"}
GRAFANA_DIR = REPO_ROOT / "design" / "observability" / "grafana"
CORE_ALERT_SNIPPET_PATHS = [
    GRAFANA_DIR / "redis-alerts-snippets.md",
    GRAFANA_DIR / "tick-alerts-snippets.md",
    GRAFANA_DIR / "backup-alerts-snippets.md",
    GRAFANA_DIR / "player-experience-alerts-snippets.md",
    GRAFANA_DIR / "observability-stack-alerts-snippets.md",
]
REQUIRED_BACKUP_RECORDINGS = {
    "backup_pipeline_recent_backup_slo_breached",
    "backup_pipeline_recent_verification_slo_breached",
    "backup_pipeline_recent_restore_drill_slo_breached",
    "backup_artifact_lineage_invalid",
    "backup_artifact_restore_unreadable",
    "recovery_participant_convergence_blocked",
    "recovery_environment_convergence_blocked",
}
CURRENT_BLOCKED_CONVERGENCE_EXPR = re.compile(
    r'recovery_participant_convergence_state\s*\{\s*state\s*=\s*["\']blocked["\']\s*\}'
)
REQUIRED_ABSENT_ALERT_METRICS = {
    "BackupLastSuccessMetricsAbsent": "backup_last_success_timestamp_seconds",
    "BackupVerificationLastSuccessMetricsAbsent": "backup_verify_last_success_timestamp_seconds",
    "BackupRestoreDrillLastSuccessMetricsAbsent": "backup_restore_drill_last_success_timestamp_seconds",
    "BackupArtifactLineageMetricsAbsent": "backup_artifact_lineage_valid",
    "BackupArtifactRestoreReadabilityMetricsAbsent": "backup_artifact_restore_readable",
    "RecoveryParticipantConvergenceMetricsAbsent": "recovery_participant_convergence_state",
}
BLOCKED_REOPEN_ATTEMPT_EXPR = re.compile(
    r'increase\s*\(\s*recovery_reopen_attempt_total\s*\{'
    r'(?=[^}]*result\s*=\s*["\']blocked["\'])'
    r'(?=[^}]*reason\s*=\s*["\']incomplete_convergence["\'])[^}]*\}'
    r'\s*\[\s*[0-9]+(?:\.[0-9]+)?(?:ms|s|m|h|d|w|y)'
    r'(?:[0-9]+(?:\.[0-9]+)?(?:ms|s|m|h|d|w|y))*\s*\]\s*\)\s*>\s*0'
)
ENVIRONMENT_BLOCKED_CONVERGENCE_EXPR = re.compile(
    r"max\s+by\s*\(\s*environment\s*\)\s*\(\s*recovery_participant_convergence_blocked\s*\)"
)
STALE_BLOCKED_CONVERGENCE_EXPR = re.compile(
    r'recovery_participant_convergence_total\s*\{[^}]*result\s*=\s*["\']blocked["\']'
)
RESTORE_DRILL_30_DAY_EXPR = re.compile(
    r"backup_restore_drill_last_success_timestamp_seconds\s*>\s*30\s*\*\s*24\s*\*\s*60\s*\*\s*60"
)


@dataclass(frozen=True)
class Finding:
    path: Path
    message: str


def _read_text(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def _extract_fenced_blocks(markdown: str, language: str) -> list[str]:
    lines = markdown.splitlines()
    blocks: list[str] = []
    index = 0
    while index < len(lines):
        opening = re.match(
            r"^ {0,3}(?P<fence>`{3,}|~{3,})[ \t]*(?P<info>.*)$",
            lines[index],
        )
        if not opening:
            index += 1
            continue
        info = opening.group("info").strip()
        if not info or info.split(maxsplit=1)[0].lower() != language.lower():
            index += 1
            continue
        fence = opening.group("fence")
        closing = re.compile(
            rf"^ {{0,3}}{re.escape(fence[0])}{{{len(fence)},}}[ \t]*$"
        )
        body_start = index + 1
        index = body_start
        while index < len(lines) and not closing.match(lines[index]):
            index += 1
        if index < len(lines):
            blocks.append("\n".join(lines[body_start:index]))
        index += 1
    return blocks

def _github_anchor_from_heading(heading: str) -> str:
    base = heading.strip().lower()
    base = re.sub(r"[`*_~]", "", base)
    base = re.sub(r"[^a-z0-9\s-]", "", base)
    base = re.sub(r"\s+", "-", base)
    base = re.sub(r"-+", "-", base)
    base = base.strip("-")
    return base


def _github_anchors_for_markdown(path: Path) -> set[str]:
    used: dict[str, int] = {}
    anchors: set[str] = set()
    for line in _read_text(path).splitlines():
        match = re.match(r"^(#{1,6})\s+(.*)$", line)
        if not match:
            continue
        heading = match.group(2).strip()
        base = _github_anchor_from_heading(heading)
        if base in used:
            suffix = used[base]
            used[base] = suffix + 1
            anchor = f"{base}-{suffix}"
        else:
            used[base] = 1
            anchor = base
        anchors.add(anchor)
    return anchors


def _split_alert_rules(yaml_text: str) -> list[list[str]]:
    return _split_rule_entries(yaml_text, "alert")


def _split_recording_rules(yaml_text: str) -> list[list[str]]:
    return _split_rule_entries(yaml_text, "record")


def _split_rule_entries(yaml_text: str, entry_key: str) -> list[list[str]]:
    lines = yaml_text.splitlines()
    rule_starts: list[int] = []
    for index, line in enumerate(lines):
        if re.match(rf"^\s*-\s*{re.escape(entry_key)}:\s*\S+", line):
            rule_starts.append(index)
    rule_starts.append(len(lines))
    rules: list[list[str]] = []
    for start_index, end_index in zip(rule_starts, rule_starts[1:]):
        rules.append(lines[start_index:end_index])
    return rules


def _parse_labels(rule_lines: list[str]) -> dict[str, str]:
    for index, line in enumerate(rule_lines):
        match = re.match(r"^(?P<indent>\s*)labels:\s*$", line)
        if not match:
            continue
        labels_indent = len(match.group("indent"))
        labels: dict[str, str] = {}
        for next_line in rule_lines[index + 1 :]:
            if next_line.strip() == "":
                continue
            next_indent = len(next_line) - len(next_line.lstrip(" "))
            if next_indent <= labels_indent:
                break
            kv_match = re.match(r"^\s*(?P<key>[A-Za-z0-9_]+):\s*(?P<value>.+?)\s*$", next_line)
            if not kv_match:
                continue
            key = kv_match.group("key")
            value = kv_match.group("value").strip().strip('"').strip("'")
            labels[key] = value
        return labels
    return {}


def _parse_expr(rule_lines: list[str]) -> str | None:
    for index, line in enumerate(rule_lines):
        match = re.match(r"^(?P<indent>\s*)expr:\s*(?P<rest>.*)$", line)
        if not match:
            continue
        expr_indent = len(match.group("indent"))
        scalar = match.group("rest").strip()
        is_block_scalar = (
            re.fullmatch(
                r"[|>](?:(?:[+-][1-9]?)|(?:[1-9][+-]?))?(?:\s+#.*)?",
                scalar,
            )
            is not None
        )
        expr_lines = [] if is_block_scalar or not scalar else [scalar]
        for next_line in rule_lines[index + 1 :]:
            next_indent = len(next_line) - len(next_line.lstrip(" "))
            if next_line.strip() == "":
                expr_lines.append("")
                continue
            if next_indent <= expr_indent:
                break
            expr_lines.append(next_line.rstrip())
        expression = "\n".join(expr_lines).strip()
        if not is_block_scalar:
            first_value_line = next(
                (value.strip() for value in expr_lines if value.strip()),
                "",
            )
            empty_scalar = re.fullmatch(
                r"(?:null|~|''|\"\")(?:\s+#.*)?",
                first_value_line,
                re.IGNORECASE,
            )
            unsupported_indirection = first_value_line.startswith(("#", "!", "&", "*"))
            collection_node = first_value_line.startswith(("{", "[")) or (
                not scalar
                and (
                    re.match(r"^-\s", first_value_line) is not None
                    or re.match(r"^(?:[^'\"]+|'[^']+'|\"[^\"]+\"):\s", first_value_line)
                    is not None
                )
            )
            if empty_scalar or unsupported_indirection or collection_node:
                return ""
        return expression
    return None


def _check_ms_thresholds(expr: str) -> str | None:
    if "_ms_" not in expr:
        return None
    normalized = re.sub(r"\s+", "", expr)
    if re.search(r"_ms[^)]*/[^)]*_ms", normalized):
        return None
    numeric_comparisons = re.findall(r">\s*([0-9]+(?:\.[0-9]+)?)", expr)
    if not numeric_comparisons:
        return None
    threshold = float(numeric_comparisons[-1])
    if threshold < 10:
        return f"expression compares an `_ms` metric against {threshold}; this looks like seconds, but `_ms` metrics are milliseconds"
    return None


def _check_grpc_app_error_scoping(expr: str) -> str | None:
    if "grpc_app_error" not in expr:
        return None
    matcher = re.search(r"grpc_app_error(?:_total)?(\{[^}]*\})", expr)
    if not matcher:
        return "expression references grpc_app_error_total without a `{...}` matcher; shared dashboards/snippets must scope by `service`"
    if "service=" not in matcher.group(1):
        return "expression references grpc_app_error_total without a `service=...` matcher; shared dashboards/snippets must scope by `service`"
    return None


def _check_dotted_metric_tokens(expr: str) -> str | None:
    for token in re.findall(r"\b[A-Za-z_][A-Za-z0-9_\.]*\b", expr):
        if "." not in token:
            continue
        if re.fullmatch(r"\d+\.\d+", token):
            continue
        return f"expression references dotted token {token!r}; Prometheus metric names in shared assets must use snake_case"
    return None


def _validate_alert_snippet(path: Path) -> list[Finding]:
    findings: list[Finding] = []
    markdown = _read_text(path)
    yaml_blocks = _extract_fenced_blocks(markdown, "yaml")
    for yaml_block in yaml_blocks:
        for rule_lines in _split_alert_rules(yaml_block):
            labels = _parse_labels(rule_lines)
            missing = sorted(REQUIRED_ALERT_LABELS - labels.keys())
            if missing:
                findings.append(Finding(path=path, message=f"alert rule is missing required labels: {', '.join(missing)}"))
                continue

            severity = labels.get("severity", "")
            if severity not in ALLOWED_SEVERITIES:
                findings.append(Finding(path=path, message=f"alert rule has invalid severity={severity!r}; expected one of {sorted(ALLOWED_SEVERITIES)}"))

            service_label = labels.get("service", "")
            if service_label in DISALLOWED_ALERT_SERVICE_LABELS:
                findings.append(
                    Finding(
                        path=path,
                        message=(
                            f"alert rule uses ad-hoc service label {service_label!r}; use runtime identity labels "
                            "(for example spring-cloud-gateway, game-session-service, tcp-proxy-service)"
                        ),
                    )
                )

            runbook = labels.get("runbook", "")
            if not runbook.startswith("design/") or ".md" not in runbook or "#" not in runbook:
                findings.append(Finding(path=path, message=f"alert rule runbook label must be a design doc anchor (design/...md#section); got {runbook!r}"))
            else:
                runbook_path_s, anchor = runbook.split("#", 1)
                runbook_path = REPO_ROOT / runbook_path_s
                if not runbook_path.exists():
                    findings.append(Finding(path=path, message=f"alert rule runbook file does not exist: {runbook!r}"))
                else:
                    anchors = _github_anchors_for_markdown(runbook_path)
                    if anchor not in anchors:
                        findings.append(Finding(path=path, message=f"alert rule runbook anchor does not exist: {runbook!r}"))

            alert_class = labels.get("alert_class")
            if alert_class == "test" and severity != "P2":
                findings.append(Finding(path=path, message="test alerts must use severity=P2 and alert_class=test (never severity=test)"))

            expr = _parse_expr(rule_lines)
            if not expr:
                findings.append(Finding(path=path, message="alert rule is missing expr"))
                continue

            ms_issue = _check_ms_thresholds(expr)
            if ms_issue:
                findings.append(Finding(path=path, message=ms_issue))

            grpc_scope_issue = _check_grpc_app_error_scoping(expr)
            if grpc_scope_issue:
                findings.append(Finding(path=path, message=grpc_scope_issue))

            dotted_metric_issue = _check_dotted_metric_tokens(expr)
            if dotted_metric_issue:
                findings.append(Finding(path=path, message=dotted_metric_issue))

    return findings


def _validate_grafana_dashboards(grafana_dir: Path) -> list[Finding]:
    findings: list[Finding] = []
    for json_path in sorted(grafana_dir.glob("*.json")):
        try:
            dashboard = json.loads(_read_text(json_path))
        except json.JSONDecodeError as exc:
            findings.append(Finding(path=json_path, message=f"invalid JSON: {exc}"))
            continue

        panels = dashboard.get("panels", [])
        for panel in panels:
            for target in panel.get("targets", []):
                expr = target.get("expr")
                if not expr or not isinstance(expr, str):
                    continue
                grpc_scope_issue = _check_grpc_app_error_scoping(expr)
                if grpc_scope_issue:
                    findings.append(Finding(path=json_path, message=grpc_scope_issue))

                if "redis_coordination_used_memory_bytes" in expr and "role=" not in expr:
                    findings.append(
                        Finding(
                            path=json_path,
                            message="expression references redis_coordination_used_memory_bytes without a role matcher; shared dashboards must scope coordination role explicitly",
                        )
                    )
                if "redis_coordination_keys_total" in expr and "role=" not in expr:
                    findings.append(
                        Finding(
                            path=json_path,
                            message="expression references redis_coordination_keys_total without a role matcher; shared dashboards must scope coordination role explicitly",
                        )
                    )
                dotted_metric_issue = _check_dotted_metric_tokens(expr)
                if dotted_metric_issue:
                    findings.append(Finding(path=json_path, message=dotted_metric_issue))
    return findings


def _validate_kibana_saved_objects(kibana_dir: Path) -> list[Finding]:
    findings: list[Finding] = []
    required_columns: dict[str, set[str]] = {
        "log-volume.json": {"service", "tenantId", "regionId", "message"},
        "player-incident-drilldown.json": {"service", "tenantId", "characterId", "traceId", "correlationId", "message"},
        "tick-region-logs.json": {"service", "tenantId", "regionId", "tickId", "traceId", "correlationId", "message"},
    }

    for json_path in sorted(kibana_dir.glob("*.json")):
        try:
            payload = json.loads(_read_text(json_path))
        except json.JSONDecodeError as exc:
            findings.append(Finding(path=json_path, message=f"invalid JSON: {exc}"))
            continue

        expected = required_columns.get(json_path.name)
        if expected is None:
            continue

        serialized = json.dumps(payload)
        missing = sorted(column for column in expected if column not in serialized)
        if missing:
            findings.append(
                Finding(
                    path=json_path,
                    message=f"Kibana saved object is missing required structured log fields for runbooks: {', '.join(missing)}",
                )
            )
    return findings


def _validate_doc_semantics() -> list[Finding]:
    findings: list[Finding] = []

    backup_doc = REPO_ROOT / "design" / "architecture" / "system-architecture-backup-recovery.md"
    backup_text = _read_text(backup_doc)
    if 'service="postgres-backup"' in backup_text and 'owner="platform"' in backup_text:
        findings.append(
            Finding(
                path=backup_doc,
                message=(
                    "backup alert examples still include owner=\"platform\" with service=\"postgres-backup\"; "
                    "owner must be \"infra\" per logging/monitoring owner mapping"
                ),
            )
        )

    for core_alerts in CORE_ALERT_SNIPPET_PATHS:
        core_text = _read_text(core_alerts)
        for yaml_block in _extract_fenced_blocks(core_text, "yaml"):
            for rule_lines in _split_alert_rules(yaml_block):
                alert_name = None
                first_line = rule_lines[0] if rule_lines else ""
                match = re.match(r"^\s*-\s*alert:\s*(\S+)", first_line)
                if match:
                    alert_name = match.group(1).strip()
                if not alert_name:
                    continue
                labels = _parse_labels(rule_lines)
                expr = _parse_expr(rule_lines) or ""
                compact_expr = re.sub(r"\s+", "", expr)

                if alert_name == "LoginSuccessRatioLowGateway":
                    if labels.get("service") != "spring-cloud-gateway":
                        findings.append(Finding(path=core_alerts, message="LoginSuccessRatioLowGateway must use labels.service=spring-cloud-gateway"))
                    if 'login_requests_total{service="spring-cloud-gateway"' not in compact_expr:
                        findings.append(Finding(path=core_alerts, message="LoginSuccessRatioLowGateway must scope expr to service=\"spring-cloud-gateway\""))
                if alert_name == "LoginSuccessRatioLowTcpProxy":
                    if labels.get("service") != "tcp-proxy-service":
                        findings.append(Finding(path=core_alerts, message="LoginSuccessRatioLowTcpProxy must use labels.service=tcp-proxy-service"))
                    if 'login_requests_total{service="tcp-proxy-service"' not in compact_expr:
                        findings.append(Finding(path=core_alerts, message="LoginSuccessRatioLowTcpProxy must scope expr to service=\"tcp-proxy-service\""))
                if alert_name == "CommandLatencyP99HighGateway":
                    if labels.get("service") != "spring-cloud-gateway":
                        findings.append(Finding(path=core_alerts, message="CommandLatencyP99HighGateway must use labels.service=spring-cloud-gateway"))
                    if 'command_end_to_end_latency_ms_bucket{service="spring-cloud-gateway"' not in compact_expr:
                        findings.append(Finding(path=core_alerts, message="CommandLatencyP99HighGateway must scope expr to service=\"spring-cloud-gateway\""))
                if alert_name == "CommandLatencyP99HighTcpProxy":
                    if labels.get("service") != "tcp-proxy-service":
                        findings.append(Finding(path=core_alerts, message="CommandLatencyP99HighTcpProxy must use labels.service=tcp-proxy-service"))
                    if 'command_end_to_end_latency_ms_bucket{service="tcp-proxy-service"' not in compact_expr:
                        findings.append(Finding(path=core_alerts, message="CommandLatencyP99HighTcpProxy must scope expr to service=\"tcp-proxy-service\""))

    player_runbook = REPO_ROOT / "design" / "architecture" / "system-architecture-player-experience-incident-runbook.md"
    player_runbook_text = _read_text(player_runbook)
    if re.search(r"`LoginSuccessRatioLow`(?![A-Za-z])", player_runbook_text):
        findings.append(
            Finding(
                path=player_runbook,
                message=(
                    "player experience runbook still references legacy `LoginSuccessRatioLow`; "
                    "use split alert names `LoginSuccessRatioLowGateway`/`LoginSuccessRatioLowTcpProxy`"
                ),
            )
        )
    if re.search(r"`CommandLatencyP99High`(?![A-Za-z])", player_runbook_text):
        findings.append(
            Finding(
                path=player_runbook,
                message=(
                    "player experience runbook still references legacy `CommandLatencyP99High`; "
                    "use split alert names `CommandLatencyP99HighGateway`/`CommandLatencyP99HighTcpProxy`"
                ),
            )
        )
    if "Telnet and WebSocket path availability below SLO" not in player_runbook_text:
        findings.append(
            Finding(
                path=player_runbook,
                message=(
                    "player experience runbook incident types must explicitly include "
                    "Telnet/WebSocket path availability below SLO"
                ),
            )
        )

    runbook_index = REPO_ROOT / "design" / "architecture" / "system-architecture-runbooks.md"
    runbook_index_text = _read_text(runbook_index)
    if not re.search(r"Player Experience Incidents[\s\S]*Telnet/WebSocket path availability", runbook_index_text):
        findings.append(
            Finding(
                path=runbook_index,
                message=(
                    "runbook index Player Experience section must explicitly mention "
                    "Telnet/WebSocket path availability incidents"
                ),
            )
        )

    logging_doc = REPO_ROOT / "design" / "architecture" / "system-architecture-logging-monitoring.md"
    logging_text = _read_text(logging_doc)
    if "mirroring the `LoginSuccessRatioLow` alert condition" in logging_text:
        findings.append(
            Finding(
                path=logging_doc,
                message=(
                    "fallback recording-rule section references legacy LoginSuccessRatioLow; "
                    "it must mirror split ingress alerts (Gateway/TCP Proxy)"
                ),
            )
        )
    if "mirroring the `CommandLatencyP99High` alert condition" in logging_text:
        findings.append(
            Finding(
                path=logging_doc,
                message=(
                    "fallback recording-rule section references legacy CommandLatencyP99High; "
                    "it must mirror split ingress alerts (Gateway/TCP Proxy)"
                ),
            )
        )

    player_dashboard = REPO_ROOT / "design" / "observability" / "grafana" / "player-experience.json"
    player_dashboard_text = _read_text(player_dashboard)
    if "Telnet/WebSocket Path Availability (1d SLO)" not in player_dashboard_text:
        findings.append(
            Finding(
                path=player_dashboard,
                message="player SLO dashboard must include a dedicated 1d entry-path availability panel",
            )
        )

    return findings


def _validate_reference_prometheus_rules(path: Path) -> list[Finding]:
    findings: list[Finding] = []
    text = _read_text(path)
    alerts_seen: set[str] = set()

    for rule_lines in _split_alert_rules(text):
        first_line = rule_lines[0] if rule_lines else ""
        match = re.match(r"^\s*-\s*alert:\s*(\S+)", first_line)
        if not match:
            continue
        alert_name = match.group(1).strip()
        expr = _parse_expr(rule_lines)
        if not expr:
            findings.append(Finding(path=path, message=f"{alert_name} is missing expr"))
            continue
        alerts_seen.add(alert_name)

        labels = _parse_labels(rule_lines)
        missing = sorted(REQUIRED_ALERT_LABELS - labels.keys())
        if missing:
            findings.append(Finding(path=path, message=f"{alert_name} is missing required labels: {', '.join(missing)}"))
            continue

        severity = labels.get("severity", "")
        if severity not in ALLOWED_SEVERITIES:
            findings.append(Finding(path=path, message=f"{alert_name} has invalid severity={severity!r}; expected one of {sorted(ALLOWED_SEVERITIES)}"))

        service_label = labels.get("service", "")
        if service_label in DISALLOWED_ALERT_SERVICE_LABELS:
            findings.append(
                Finding(
                    path=path,
                    message=(
                        f"{alert_name} uses ad-hoc service label {service_label!r}; use runtime identity labels "
                        "(for example spring-cloud-gateway, game-session-service, tcp-proxy-service)"
                    ),
                )
            )

        ms_issue = _check_ms_thresholds(expr)
        if ms_issue:
            findings.append(Finding(path=path, message=f"{alert_name}: {ms_issue}"))

        grpc_scope_issue = _check_grpc_app_error_scoping(expr)
        if grpc_scope_issue:
            findings.append(Finding(path=path, message=f"{alert_name}: {grpc_scope_issue}"))

        dotted_metric_issue = _check_dotted_metric_tokens(expr)
        if dotted_metric_issue:
            findings.append(Finding(path=path, message=f"{alert_name}: {dotted_metric_issue}"))

        if alert_name.startswith("Redis") and labels.get("owner") != "infra":
            findings.append(Finding(path=path, message=f"{alert_name} must use owner=infra for Redis/coordination incidents"))
        if alert_name.startswith("Backup") and labels.get("owner") != "infra":
            findings.append(Finding(path=path, message=f"{alert_name} must use owner=infra for backup incidents"))
        if alert_name.startswith("Recovery") and labels.get("owner") != "infra":
            findings.append(Finding(path=path, message=f"{alert_name} must use owner=infra for recovery incidents"))
        absent_metric = REQUIRED_ABSENT_ALERT_METRICS.get(alert_name)
        if absent_metric:
            absent_expr = re.compile(rf"absent\s*\(\s*{re.escape(absent_metric)}\s*\)")
            if not absent_expr.search(expr or ""):
                findings.append(Finding(path=path, message=f"{alert_name} must use absent({absent_metric})"))
        if alert_name == "RecoveryReopenAttemptBlocked":
            if not BLOCKED_REOPEN_ATTEMPT_EXPR.search(expr or ""):
                findings.append(
                    Finding(
                        path=path,
                        message=(
                            "RecoveryReopenAttemptBlocked must query blocked recovery reopen attempts "
                            "with reason=incomplete_convergence"
                        ),
                    )
                )
            if severity != "P0":
                findings.append(Finding(path=path, message="RecoveryReopenAttemptBlocked must use severity=P0"))
        if alert_name.startswith("Tick") and alert_name not in {
            "TickExecutionUnsafeRatio",
            "TickEffectLedgerBacklog",
            "TickCleanupLagHigh",
            "TickReplayFairnessStarved",
            "TickReplayScanLagHigh",
        }:
            findings.append(Finding(path=path, message=f"unexpected tick alert name {alert_name!r} in reference rules; update validator contract if intentional"))

    required_alerts = {
        "RedisCoordinationTailLossSLOBreached",
        "TickExecutionUnsafeRatio",
        "BackupPipelineNoRecentBackup",
        "BackupLastSuccessMetricsAbsent",
        "BackupPipelineNoRecentVerification",
        "BackupVerificationLastSuccessMetricsAbsent",
        "BackupPipelineNoRecentRestoreDrill",
        "BackupRestoreDrillLastSuccessMetricsAbsent",
        "BackupArtifactLineageInvalid",
        "BackupArtifactLineageMetricsAbsent",
        "BackupArtifactRestoreUnreadable",
        "BackupArtifactRestoreReadabilityMetricsAbsent",
        "RecoveryParticipantConvergenceBlocked",
        "RecoveryParticipantConvergenceMetricsAbsent",
        "RecoveryReopenAttemptBlocked",
        "LoginSuccessRatioLowGateway",
        "LoginSuccessRatioLowTcpProxy",
        "CommandLatencyP99HighGateway",
        "CommandLatencyP99HighTcpProxy",
        "EntryPathAvailabilityLowGateway",
        "EntryPathAvailabilityLowTcpProxy",
        "EntryPathAvailabilityLowGatewayCompliance",
        "EntryPathAvailabilityLowTcpProxyCompliance",
        "PlayerFlowCanaryLoginFailed",
        "PlayerFlowCanaryCommandFailed",
        "PlayerFlowCanaryLatencyHigh",
        "WebSocketEntryPathBlackboxUnavailable",
        "TelnetEntryPathBlackboxUnavailable",
        "ChatDeliveryLatencyP99High",
        "TickReplayFairnessStarved",
        "TickReplayScanLagHigh",
        "ObservabilityDeadmanHeartbeatStale",
        "AlertmanagerNotificationsFailing",
        "AlertmanagerConfigReloadFailed",
        "PrometheusRuleEvaluationsFailing",
        "PrometheusServiceDiscoveryFailures",
        "ElasticsearchClusterHealthRed",
        "ElasticsearchIndexingFailuresHigh",
        "OTelCollectorExportFailures",
        "JaegerQueryUnavailable",
        "JaegerStorageFailuresHigh",
        "FluentBitOutputErrorsHigh",
        "GrafanaDatasourceUnavailable",
        "GrafanaServiceUnavailable",
    }
    missing_required = sorted(required_alerts - alerts_seen)
    if missing_required:
        findings.append(
            Finding(
                path=path,
                message=f"reference rules are missing required alerts: {', '.join(missing_required)}",
            )
        )

    if "LoginSuccessRatioLow" in alerts_seen:
        findings.append(Finding(path=path, message="reference rules must not include legacy LoginSuccessRatioLow; use split ingress alerts"))
    if "CommandLatencyP99High" in alerts_seen:
        findings.append(Finding(path=path, message="reference rules must not include legacy CommandLatencyP99High; use split ingress alerts"))

    return findings


def _validate_reference_prometheus_recordings(path: Path) -> list[Finding]:
    text = _read_text(path)
    findings: list[Finding] = []
    recording_occurrences: dict[str, list[str | None]] = {}
    for rule_lines in _split_recording_rules(text):
        first_line = rule_lines[0] if rule_lines else ""
        match = re.match(r"^\s*-\s*record:\s*(\S+)", first_line)
        if not match:
            continue
        recording = match.group(1).strip()
        recording_occurrences.setdefault(recording, []).append(_parse_expr(rule_lines))

    missing_required = sorted(REQUIRED_BACKUP_RECORDINGS - recording_occurrences.keys())
    if missing_required:
        findings.append(
            Finding(
                path=path,
                message=f"reference rules are missing required backup recordings: {', '.join(missing_required)}",
            )
        )

    duplicate_required = sorted(
        recording
        for recording in REQUIRED_BACKUP_RECORDINGS & recording_occurrences.keys()
        if len(recording_occurrences[recording]) != 1
    )
    if duplicate_required:
        findings.append(
            Finding(
                path=path,
                message=(
                    "required backup recordings must be declared exactly once: "
                    + ", ".join(duplicate_required)
                ),
            )
        )

    missing_expressions = sorted(
        recording
        for recording in REQUIRED_BACKUP_RECORDINGS & recording_occurrences.keys()
        if any(not expression for expression in recording_occurrences[recording])
    )
    if missing_expressions:
        findings.append(
            Finding(
                path=path,
                message=(
                    "required backup recordings are missing expr: "
                    + ", ".join(missing_expressions)
                ),
            )
        )

    recordings = {
        recording: expressions[0]
        for recording, expressions in recording_occurrences.items()
        if len(expressions) == 1
    }
    blocked_convergence_expr = recordings.get("recovery_participant_convergence_blocked") or ""
    if not CURRENT_BLOCKED_CONVERGENCE_EXPR.search(blocked_convergence_expr):
        findings.append(
            Finding(
                path=path,
                message="blocked convergence recording must use the current participant state gauge",
            )
        )

    environment_blocked_convergence_expr = recordings.get("recovery_environment_convergence_blocked") or ""
    if not ENVIRONMENT_BLOCKED_CONVERGENCE_EXPR.search(environment_blocked_convergence_expr):
        findings.append(
            Finding(
                path=path,
                message=(
                    "environment blocked-convergence recording must aggregate "
                    "recovery_participant_convergence_blocked with max by (environment)"
                ),
            )
        )
    if STALE_BLOCKED_CONVERGENCE_EXPR.search(blocked_convergence_expr):
        findings.append(
            Finding(
                path=path,
                message="blocked convergence recording must not use the cumulative convergence counter",
            )
        )

    restore_drill_expr = recordings.get("backup_pipeline_recent_restore_drill_slo_breached") or ""
    if not RESTORE_DRILL_30_DAY_EXPR.search(restore_drill_expr):
        findings.append(
            Finding(
                path=path,
                message="restore-drill freshness must use the accepted 30-day baseline",
            )
        )
    return findings


def main() -> int:
    findings: list[Finding] = []

    grafana_dir = GRAFANA_DIR
    for alert_snippet in [
        grafana_dir / "core-alerts-snippets.md",
        *CORE_ALERT_SNIPPET_PATHS,
        grafana_dir / "tcp-proxy-alerts-snippets.md",
    ]:
        findings.extend(_validate_alert_snippet(alert_snippet))
    findings.extend(_validate_grafana_dashboards(grafana_dir))
    findings.extend(_validate_kibana_saved_objects(REPO_ROOT / "design" / "observability" / "kibana"))
    findings.extend(_validate_doc_semantics())
    prometheus_rules = REPO_ROOT / "k8s" / "monitoring" / "prometheus-rules-firemud.yaml"
    findings.extend(_validate_reference_prometheus_recordings(prometheus_rules))
    findings.extend(_validate_reference_prometheus_rules(prometheus_rules))

    if not findings:
        print("Observability contract validation: OK")
        return 0

    print("Observability contract validation: FAILED")
    for finding in findings:
        print(f"- {finding.path}: {finding.message}")
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
