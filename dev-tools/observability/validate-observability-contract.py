#!/usr/bin/env python3

from __future__ import annotations

import json
import re
from dataclasses import dataclass
from pathlib import Path


def _compact_promql(expr: str) -> str:
    return re.sub(r"\s+", "", expr)


REPO_ROOT = Path(__file__).resolve().parents[2]

KIBANA_DEFAULT_LOG_INDEX = "firemud-logs-*"
KIBANA_ENVIRONMENT_INDEX_SENTINEL = "firemud-logs-env-__REQUIRED_ENVIRONMENT__-*"
KIBANA_SAFE_LOG_INDEX_PATTERN = re.compile(
    r"^firemud-logs-(?:\*|[A-Za-z0-9][A-Za-z0-9._-]*\*?)$"
)

ALLOWED_SEVERITIES = {"P0", "P1", "P2"}
REQUIRED_ALERT_LABELS = {"service", "severity", "owner", "runbook"}
SUPPORTED_RULE_ROOT_FIELDS = {
    "alert",
    "record",
    "expr",
    "for",
    "keep_firing_for",
    "query_offset",
    "labels",
    "annotations",
}
SUPPORTED_RULE_INLINE_FIELDS = SUPPORTED_RULE_ROOT_FIELDS - {"alert", "record"}
SERVICE_OPTIONAL_ALERTS = {
    "PlayerFlowCanaryLoginFailed",
    "PlayerFlowCanaryCommandFailed",
    "PlayerFlowCanaryLatencyHigh",
}
SERVICE_DERIVED_ALERTS = {"ChatDeliveryLatencyP99High"}
TARGET_ONLY_INSTALLED_ALERTS = {
    "TickEffectsReplaySloBreached",
    "TickEffectsReplayStarved",
    "RedisLuaScriptLoadFailures",
    "RedisLuaScriptMissingForRegion",
    "RedisLuaScriptRuntimeHigh",
    "RedisCoordinationOomErrors",
    "RedisCoordinationTailLossSLOBreached",
    "RedisTickPendingStuck",
    "TickExecutionUnsafeRatio",
    "TickCleanupLagHigh",
    "TickReplayScanLagHigh",
    "LoginSuccessRatioLowGateway",
    "LoginSuccessRatioLowTcpProxy",
    "CommandLatencyP99HighGateway",
    "CommandLatencyP99HighTcpProxy",
    "ChatDeliveryLatencyP99High",
    "EntryPathAvailabilityLowGateway",
    "EntryPathAvailabilityLowGatewayCompliance",
    "EntryPathAvailabilityLowTcpProxy",
    "EntryPathAvailabilityLowTcpProxyCompliance",
}
TARGET_ONLY_INSTALLED_RECORDINGS = {
    "tick_effects_replay_convergence_budget_seconds",
    "tick_effects_replay_starved",
    "command_latency_stage_ms_p99_5m",
    "tick_execution_time_ms_p95",
    "tick_execution_time_ms_p99",
    "tick_execution_safety_ratio_p99",
    "redis_coordination_tail_loss_budget_ms",
    "redis_coordination_tail_loss_slo_breached",
    "tick_effects_pending_oldest_age_seconds",
    "tick_effects_replay_slo_breached",
    "login_success_ratio_gateway_15m",
    "login_success_ratio_tcpproxy_15m",
    "command_latency_ms_p99_gateway_5m",
    "command_latency_ms_p99_tcpproxy_5m",
    "chat_delivery_latency_ms_p99_5m",
    "entrypath_availability_gateway_1d",
    "entrypath_availability_gateway_5m",
    "entrypath_availability_tcpproxy_1d",
    "entrypath_availability_tcpproxy_5m",
}
PLAYER_SLO_CALIBRATION_ALERTS = {
    "LoginSuccessRatioLowGateway",
    "LoginSuccessRatioLowTcpProxy",
    "CommandLatencyP99HighGateway",
    "CommandLatencyP99HighTcpProxy",
    "ChatDeliveryLatencyP99High",
    "EntryPathAvailabilityLowGateway",
    "EntryPathAvailabilityLowGatewayCompliance",
    "EntryPathAvailabilityLowTcpProxy",
    "EntryPathAvailabilityLowTcpProxyCompliance",
}
PLAYERFLOW_CANARY_ALERTS = SERVICE_OPTIONAL_ALERTS | {
    "PlayerFlowCanaryEvidenceStale",
}
PLAYERFLOW_CANARY_REQUIRED_LABELS = {
    "component": "playerflow-canary",
    "path": "{{ $labels.path }}",
    "target": "{{ $labels.target }}",
}
PROFILE_DEPENDENT_ALERTS = {
    "ObservabilityDeadmanHeartbeatMissing",
    "ObservabilityDeadmanHeartbeatStale",
    "WebSocketEntryPathBlackboxMetricsAbsent",
    "WebSocketEntryPathBlackboxUnavailable",
    "TelnetEntryPathBlackboxMetricsAbsent",
    "TelnetEntryPathBlackboxUnavailable",
}
ENTRY_PATH_BLACKBOX_ALERT_CONTRACTS = {
    "WebSocketEntryPathBlackboxMetricsAbsent": {
        "path": "websocket",
        "target": "gateway",
        "service": "spring-cloud-gateway",
        "expression": "absent",
    },
    "WebSocketEntryPathBlackboxUnavailable": {
        "path": "websocket",
        "target": "gateway",
        "service": "spring-cloud-gateway",
        "expression": "zero",
    },
    "TelnetEntryPathBlackboxMetricsAbsent": {
        "path": "telnet",
        "target": "tcp_proxy",
        "service": "tcp-proxy-service",
        "expression": "absent",
    },
    "TelnetEntryPathBlackboxUnavailable": {
        "path": "telnet",
        "target": "tcp_proxy",
        "service": "tcp-proxy-service",
        "expression": "zero",
    },
}
SERVICE_SCOPED_ALERT_CONTRACTS = {
    "LoginSuccessRatioLowGateway": (
        "login_requests_total",
        "spring-cloud-gateway",
    ),
    "LoginSuccessRatioLowTcpProxy": (
        "login_requests_total",
        "tcp-proxy-service",
    ),
    "CommandLatencyP99HighGateway": (
        "command_end_to_end_latency_ms_bucket",
        "spring-cloud-gateway",
    ),
    "CommandLatencyP99HighTcpProxy": (
        "command_end_to_end_latency_ms_bucket",
        "tcp-proxy-service",
    ),
}
TICK_SCOPE_CLASS_MATCHER = 'scope_class=~"region|game_instance|tenant|cluster"'
_TICK_SCOPE_LABEL, _TICK_SCOPE_CLASS_REGEX = TICK_SCOPE_CLASS_MATCHER.split("=~", 1)
TICK_SCOPE_CLASS_MATCHER_RE = re.compile(
    rf'(?:^|,)\s*{re.escape(_TICK_SCOPE_LABEL)}\s*=\s*~\s*'
    rf'{re.escape(_TICK_SCOPE_CLASS_REGEX)}\s*(?:,|$)',
)
TICK_REPLAY_ALERT_METRICS = {
    "TickEffectsReplaySloBreached": "tick_effects_replay_slo_breached",
    "TickEffectsReplayStarved": "tick_effects_replay_starved",
    "TickReplayScanLagHigh": "tick_effects_replay_scan_lag_ms",
}
TICK_REPLAY_ALERT_THRESHOLDS = {
    "TickEffectsReplaySloBreached": "0",
    "TickEffectsReplayStarved": "0",
    "TickReplayScanLagHigh": "300000",
}
# These label sets mirror the canonical metric families in
# system-architecture-redis-metrics-catalog.md.  A selector may omit a
# documented dimension when it intentionally aggregates it (for example the
# dashboard's abandoned-effects sum omits `reason`, and the Redis compatibility
# alert selects `tick_interval_ms` by bounded deployment `scope`), but it may
# not introduce an unbounded runtime identity or an otherwise unknown label.
TICK_SCOPED_METRIC_LABELS = {
    "tick_interval_ms": {"scope", "scope_class"},
    "tick_execution_time_ms_bucket": {"scope_class", "tick_mode", "le"},
    "tick_execution_time_ms_p95": {"scope_class", "tick_mode"},
    "tick_execution_time_ms_p99": {"scope_class", "tick_mode"},
    "tick_execution_safety_ratio_p99": {"scope_class", "tick_mode"},
    "tick_lock_ttl_ms": {"scope_class"},
    "solo_lock_ttl_ms": {"scope_class"},
    "tick_status": {"scope_class", "status"},
    "current_tick_state": {"scope_class", "state"},
    "current_tick_terminal_at_ms": {"scope_class"},
    "tick_current_id": {"scope_class"},
    "tick_pending_oldest_id": {"scope_class"},
    "tick_retry_queue_depth": {"scope_class"},
    "tick_command_queue_depth": {"scope_class"},
    "tick_effects_pending_total": {"scope_class"},
    "tick_effects_applied_total": {"scope_class"},
    "tick_effects_abandoned_total": {"scope_class", "reason"},
    "tick_effects_pending_oldest_scheduled_timestamp_seconds": {"scope_class"},
    "tick_effects_pending_oldest_age_seconds": {"scope_class"},
    "tick_effects_replay_convergence_budget_seconds": {"scope_class"},
    "tick_effects_replay_slo_breached": {"scope_class"},
    "tick_effects_replay_scan_lag_ms": {"scope_class"},
    "tick_effects_replay_batches_total": {"scope_class"},
    "tick_effects_replay_starved": {"scope_class"},
    "tick_durable_commit_total": {"scope_class"},
    "tick_coordination_cleared_total": {"scope_class"},
    "tick_cleanup_lag_ms": {"scope_class"},
    "tick_effects_replayed_total": {"scope_class"},
    "gamesession_tick_replayed_total": {"scope_class"},
    "gamesession_tick_executed_total": {"scope_class"},
}
TICK_SCOPED_METRICS = set(TICK_SCOPED_METRIC_LABELS)
DISALLOWED_ALERT_SERVICE_LABELS = {"gateway", "game-session"}
GRAFANA_DIR = REPO_ROOT / "design" / "observability" / "grafana"
CORE_ALERT_SNIPPET_PATHS = [
    GRAFANA_DIR / "redis-alerts-snippets.md",
    GRAFANA_DIR / "tick-alerts-snippets.md",
    GRAFANA_DIR / "backup-alerts-snippets.md",
    GRAFANA_DIR / "player-experience-alerts-snippets.md",
    GRAFANA_DIR / "observability-stack-alerts-snippets.md",
    GRAFANA_DIR / "scripting-execution-policy-alerts-snippets.md",
]
ALERT_SNIPPET_PATHS = [
    GRAFANA_DIR / "core-alerts-snippets.md",
    *CORE_ALERT_SNIPPET_PATHS,
    GRAFANA_DIR / "tcp-proxy-alerts-snippets.md",
]
REQUIRED_BACKUP_RECORDINGS = {
    "backup_pipeline_recent_backup_slo_breached",
    "backup_pipeline_recent_verification_slo_breached",
    "backup_pipeline_recent_restore_drill_slo_breached",
    "backup_artifact_lineage_invalid",
    "backup_artifact_restore_unreadable",
    "recovery_participant_convergence_blocked",
    "recovery_environment_convergence_blocked",
    "recovery_participant_convergence_coverage_missing",
    "recovery_participant_convergence_source_missing",
}
CURRENT_BLOCKED_CONVERGENCE_EXPR = _compact_promql(
    """
    (
      recovery_participant_convergence_state{state="blocked"} == 1
      and on (environment)
      recovery_required_participant_inventory_complete == 1
    )
    or on (environment, participant)
    (
      recovery_participant_convergence_coverage_missing > 0
    )
    """
)
REQUIRED_ABSENT_ALERT_METRICS = {
    "BackupLastSuccessMetricsAbsent": "backup_last_success_timestamp_seconds",
    "BackupVerificationLastSuccessMetricsAbsent": "backup_verify_last_success_timestamp_seconds",
    "BackupRestoreDrillLastSuccessMetricsAbsent": "backup_restore_drill_last_success_timestamp_seconds",
    "BackupArtifactLineageMetricsAbsent": "backup_artifact_lineage_valid",
    "BackupArtifactRestoreReadabilityMetricsAbsent": "backup_artifact_restore_readable",
}
PARTICIPANT_COVERAGE_EXPR = _compact_promql(
    """
    (
      (
        recovery_required_participant_inventory == 1
        and on (environment)
        recovery_required_participant_inventory_complete == 1
      )
      unless on (environment, participant)
      (
        count by (environment, participant) (
          recovery_participant_convergence_coverage
        ) > 0
      )
    )
    or
    label_replace(
      recovery_required_participant_inventory_complete != bool 1,
      "participant", "__environment__", "", ""
    )
    or
    label_replace(
      (
        count by (environment) (
          recovery_required_participant_inventory
        )
        unless on (environment)
        (
          recovery_required_participant_inventory_complete == 1
        )
      ),
      "participant", "__environment__", "", ""
    )
    or
    label_replace(
      (
        recovery_required_participant_inventory_complete == 1
        unless on (environment)
        (
          count by (environment) (
            recovery_required_participant_inventory
          ) > 0
        )
      ),
      "participant", "__environment__", "", ""
    )
    """
)
PARTICIPANT_COVERAGE_ALERT_EXPR = _compact_promql(
    "recovery_participant_convergence_coverage_missing > 0"
)
PARTICIPANT_SOURCE_MISSING_EXPR = _compact_promql(
    """
    label_replace(
      absent(recovery_required_participant_inventory_complete),
      "source_family", "inventory_complete", "", ""
    )
    or
    label_replace(
      absent(recovery_required_participant_inventory),
      "source_family", "participant_inventory", "", ""
    )
    or
    label_replace(
      absent(recovery_participant_convergence_coverage),
      "source_family", "participant_coverage", "", ""
    )
    """
)
PARTICIPANT_SOURCE_MISSING_ALERT_EXPR = _compact_promql(
    "recovery_participant_convergence_source_missing > 0"
)
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
    r"time\s*\(\s*\)\s*-\s*"
    r"backup_restore_drill_last_success_timestamp_seconds\s*>\s*30\s*\*\s*24\s*\*\s*60\s*\*\s*60"
)


@dataclass(frozen=True)
class Finding:
    path: Path
    message: str


def _recovery_coverage_alert_finding(
    path: Path, alert_name: str | None, expr: str
) -> Finding | None:
    expected = {
        "RecoveryParticipantConvergenceCoverageMissing": (
            PARTICIPANT_COVERAGE_ALERT_EXPR,
            "recovery_participant_convergence_coverage_missing > 0",
        ),
        "RecoveryParticipantConvergenceMetricsAbsent": (
            PARTICIPANT_SOURCE_MISSING_ALERT_EXPR,
            "recovery_participant_convergence_source_missing > 0",
        ),
    }.get(alert_name)
    if expected is not None and _compact_promql(expr) != expected[0]:
        return Finding(
            path=path,
            message=f"{alert_name} must use {expected[1]}",
        )
    return None


@dataclass(frozen=True)
class _RuleEntry:
    lines: list[str]
    key: str | None
    name: str | None
    issue: str | None = None


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
    base = re.sub(r"\s", "-", base)
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


def _alert_runbook_findings(path: Path, runbook: str) -> list[Finding]:
    if not runbook.startswith("design/") or ".md" not in runbook or "#" not in runbook:
        return [
            Finding(
                path=path,
                message=(
                    "alert rule runbook label must be a design doc anchor "
                    f"(design/...md#section); got {runbook!r}"
                ),
            )
        ]

    runbook_path_s, anchor = runbook.split("#", 1)
    runbook_path = REPO_ROOT / runbook_path_s
    if not runbook_path.exists():
        return [
            Finding(
                path=path,
                message=f"alert rule runbook file does not exist: {runbook!r}",
            )
        ]

    if anchor not in _github_anchors_for_markdown(runbook_path):
        return [
            Finding(
                path=path,
                message=f"alert rule runbook anchor does not exist: {runbook!r}",
            )
        ]
    return []


def _split_alert_rules(yaml_text: str) -> list[_RuleEntry]:
    return _split_rule_entries(yaml_text, "alert")


def _split_recording_rules(yaml_text: str) -> list[_RuleEntry]:
    return _split_rule_entries(yaml_text, "record")


def _strip_yaml_comment(value: str) -> str:
    in_single_quote = False
    in_double_quote = False
    escaped = False
    for index, character in enumerate(value):
        if in_double_quote:
            if escaped:
                escaped = False
            elif character == "\\":
                escaped = True
            elif character == '"':
                in_double_quote = False
            continue
        if in_single_quote:
            if character == "'":
                in_single_quote = False
            continue
        if character == '"':
            in_double_quote = True
        elif character == "'":
            in_single_quote = True
        elif character == "#" and (index == 0 or value[index - 1].isspace()):
            return value[:index]
    return value


def _leading_space_count(line: str) -> int:
    return len(line) - len(line.lstrip(" "))


def _meaningful_yaml_line(line: str) -> bool:
    return bool(_strip_yaml_comment(line).strip())


def _is_sequence_item(line: str, indent: int | None = None) -> bool:
    if indent is not None and _leading_space_count(line) != indent:
        return False
    return re.match(r"^\s*-(?:\s|$)", _strip_yaml_comment(line)) is not None


def _parse_mapping_header(text: str) -> tuple[str, str] | None:
    match = re.match(
        # A plain mapping value must be separated from ``:`` by a space. A
        # bare ``key:`` remains valid for an empty/null value, while
        # ``key:value`` and ``key:\tvalue`` are scalar/malformed YAML rather
        # than mappings. Keep this aligned with the dependency-free parser's
        # safe subset so it cannot silently reinterpret malformed input.
        r"^(?P<key>[A-Za-z_][A-Za-z0-9_-]*|\"[^\"]+\"|'[^']+')[ \t]*:(?:(?P<spaces> +)(?P<value>.*)|(?P<empty>))$",
        _strip_yaml_comment(text).strip(),
    )
    if not match:
        return None
    key = match.group("key").strip("\"'")
    return key, (match.group("value") or "").strip()


def _looks_like_mapping_header(text: str) -> bool:
    """Recognize YAML mapping-key syntax the dependency-free parser cannot parse."""
    content = _strip_yaml_comment(text).strip()
    if not content or _parse_mapping_header(content) is not None:
        return False
    # Keep malformed spellings of the supported rule fields visible (for
    # example ``expr:value``) without treating arbitrary scalar text such as
    # URLs or PromQL string values as mapping headers.
    if re.match(
        r"^(?:alert|record|expr|for|keep_firing_for|query_offset|labels|annotations):(?:[^ \t]|[ \t]+\S)",
        content,
    ):
        return True
    # Keep this deliberately broad: YAML permits numeric, flow, tagged, and
    # otherwise non-identifier mapping keys. In a rule entry, an over-indented
    # header outside labels/annotations is not a valid scalar continuation and
    # must fail closed instead of being folded into the PromQL expression.
    return re.match(
        r"^(?:[^\s#][^:]*|\[[^\]]*\]|\{.*\}|\?\s+.+):(?:[ \t]+|$)",
        content,
    ) is not None


def _parse_rule_name(value: str) -> tuple[str | None, bool]:
    value = _strip_yaml_comment(value).strip()
    if not value:
        return None, False
    if value.startswith(("\"", "'")):
        quote = value[0]
        if len(value) < 2 or value[-1] != quote:
            return None, True
        if quote == '"':
            try:
                name = json.loads(value)
            except json.JSONDecodeError:
                return None, True
            if not isinstance(name, str):
                return None, True
        else:
            name = value[1:-1].replace("''", "'")
    else:
        if value.startswith(("&", "*", "!", "{", "[", "|", ">")):
            return None, True
        if re.match(r"^(?:\?|-(?:\s|$))", value) or re.search(r":(?:\s|$)", value):
            return None, True
        if value.lower() in {"null", "~"}:
            return None, False
        name = value

    if not name.strip():
        return None, False
    return name, False


def _parse_rule_entry_header(line: str) -> tuple[str | None, str | None]:
    content = _strip_yaml_comment(line).lstrip()
    if not content.startswith("-"):
        return None, None
    header = content[1:].strip()
    parsed = _parse_mapping_header(header)
    if not parsed:
        return None, None
    key, name = parsed
    if key not in {"alert", "record"}:
        return None, None
    normalized_name, unsupported_name = _parse_rule_name(name)
    if unsupported_name:
        return None, None
    return key, normalized_name


def _flow_collection_delta(value: str) -> int:
    delta = 0
    in_single_quote = False
    in_double_quote = False
    escaped = False
    for character in value:
        if in_double_quote:
            if escaped:
                escaped = False
            elif character == "\\":
                escaped = True
            elif character == '"':
                in_double_quote = False
            continue
        if in_single_quote:
            if character == "'":
                in_single_quote = False
            continue
        if character == '"':
            in_double_quote = True
        elif character == "'":
            in_single_quote = True
        elif character in "[{":
            delta += 1
        elif character in "]}":
            delta -= 1
    return delta


def _unsupported_rules_key_shapes(lines: list[str]) -> list[_RuleEntry]:
    findings: list[_RuleEntry] = []
    flow_depth = 0
    block_scalar_indent: int | None = None
    flow_rules_key = re.compile(r"(?:^|[,{])\s*(?:rules|\"rules\"|'rules')\s*:")
    explicit_rules_key = re.compile(r"^(?:-\s+)?\?\s*(?:rules|\"rules\"|'rules')(?:\s|$)")
    for line in lines:
        content = _strip_yaml_comment(line).strip()
        if not content:
            continue
        indent = _leading_space_count(line)
        if block_scalar_indent is not None:
            if indent <= block_scalar_indent:
                block_scalar_indent = None
            else:
                continue
        if explicit_rules_key.match(content):
            findings.append(
                _RuleEntry(
                    lines=[line],
                    key=None,
                    name=None,
                    issue="unsupported explicit rules key shape",
                )
            )
        rules_header = re.match(r"^(?:rules|\"rules\"|'rules')\s*:", content)
        starts_flow_collection = (
            rules_header is None
            and re.search(r"(?:^|-\s+|:\s*)[\[{]", content) is not None
        )
        if (flow_depth > 0 or starts_flow_collection) and flow_rules_key.search(content):
            findings.append(
                _RuleEntry(
                    lines=[line],
                    key=None,
                    name=None,
                    issue="unsupported flow rules key shape",
                )
            )
        if flow_depth > 0 or starts_flow_collection:
            flow_depth = max(0, flow_depth + _flow_collection_delta(content))
        if re.search(r":\s*[|>](?:[+-][1-9]?|[1-9][+-]?)?(?:\s+#.*)?$", content):
            block_scalar_indent = indent
    return findings


def _rule_sequence_ranges(lines: list[str]) -> list[tuple[int, int, int]]:
    ranges: list[tuple[int, int, int]] = []
    for index, line in enumerate(lines):
        if not _meaningful_yaml_line(line):
            continue
        parsed = _parse_mapping_header(line)
        if not parsed or parsed[0] != "rules" or parsed[1]:
            continue

        rules_indent = _leading_space_count(line)
        first_item = None
        for candidate in range(index + 1, len(lines)):
            if not _meaningful_yaml_line(lines[candidate]):
                continue
            candidate_indent = _leading_space_count(lines[candidate])
            if candidate_indent < rules_indent:
                break
            if _is_sequence_item(lines[candidate]) and candidate_indent >= rules_indent:
                first_item = candidate
            break
        if first_item is None:
            continue

        sequence_indent = _leading_space_count(lines[first_item])
        end = first_item
        while end < len(lines):
            if not _meaningful_yaml_line(lines[end]):
                end += 1
                continue
            current_indent = _leading_space_count(lines[end])
            if current_indent < rules_indent:
                break
            if current_indent == sequence_indent and not _is_sequence_item(lines[end]):
                break
            if current_indent < sequence_indent and current_indent >= rules_indent:
                break
            end += 1
        ranges.append((first_item, end, sequence_indent))
    return ranges


def _standalone_rule_sequence_range(lines: list[str]) -> tuple[int, int, int] | None:
    first_item = next(
        (index for index, line in enumerate(lines) if _meaningful_yaml_line(line)),
        None,
    )
    if first_item is None or not _is_sequence_item(lines[first_item]):
        return None
    sequence_indent = _leading_space_count(lines[first_item])
    end = first_item
    while end < len(lines):
        if not _meaningful_yaml_line(lines[end]):
            end += 1
            continue
        if _leading_space_count(lines[end]) < sequence_indent:
            break
        end += 1
    return first_item, end, sequence_indent


def _standalone_rule_mapping_entry(lines: list[str]) -> _RuleEntry | None:
    first_line = next(
        (
            index
            for index, line in enumerate(lines)
            if _meaningful_yaml_line(line)
            and _strip_yaml_comment(line).strip() not in {"---", "..."}
        ),
        None,
    )
    if first_line is None:
        return None

    first_content = _strip_yaml_comment(lines[first_line]).strip()
    if _is_sequence_item(lines[first_line]):
        return None
    malformed_or_unsupported_root = re.match(
        r"^(?:alert|record|\"(?:alert|record)\"|'(?:alert|record)')(?:\s|$)|^[?&*!{\[]",
        first_content,
    )
    if malformed_or_unsupported_root:
        return _RuleEntry(lines=[lines[first_line]], key=None, name=None)

    root_indent = _leading_space_count(lines[first_line])
    root_rule_fields = SUPPORTED_RULE_ROOT_FIELDS | {"name"}
    root_rule_mappings: list[tuple[int, str, str]] = []
    root_rule_like = False
    malformed_root_shape = False
    unsupported_root_mapping = False
    extra_document = False
    document_started = False
    document_closed = False
    for index, line in enumerate(lines):
        if not _meaningful_yaml_line(line):
            continue
        content = _strip_yaml_comment(line).strip()
        indent = _leading_space_count(line)
        if indent <= root_indent and content == "---":
            if document_started:
                extra_document = True
            document_started = True
            document_closed = False
            continue
        if indent <= root_indent and content == "...":
            document_closed = True
            continue

        if document_closed:
            extra_document = True
        document_started = True
        if indent != root_indent:
            continue

        if _is_sequence_item(line, root_indent):
            malformed_root_shape = True
            continue
        parsed = _parse_mapping_header(line)
        if not parsed:
            malformed_root_shape = True
            continue
        if parsed and parsed[0] in {"alert", "record"}:
            root_rule_mappings.append((index, parsed[0], parsed[1]))
        if parsed and parsed[0] not in SUPPORTED_RULE_ROOT_FIELDS:
            unsupported_root_mapping = True
        if parsed and parsed[0] in root_rule_fields:
            root_rule_like = True

    if not root_rule_mappings:
        if root_rule_like:
            return _RuleEntry(lines=[lines[first_line]], key=None, name=None)
        return None

    if (
        extra_document
        or malformed_root_shape
        or unsupported_root_mapping
        or len(root_rule_mappings) != 1
    ):
        return _RuleEntry(
            lines=[lines[first_line]],
            key=None,
            name=None,
            issue="standalone YAML rule form must contain exactly one supported document/root rule",
        )

    rule_index, key, raw_name = root_rule_mappings[0]
    name, unsupported_name = _parse_rule_name(raw_name)
    if unsupported_name:
        return _RuleEntry(lines=[lines[rule_index]], key=None, name=None)
    return _RuleEntry(
        lines=lines[first_line:],
        key=key,
        name=name,
    )


def _rule_sequence_structure_findings(
    lines: list[str],
    rules_index: int,
    rules_indent: int,
    sequence_indent: int,
) -> list[_RuleEntry]:
    """Reject shapes the dependency-free scanner cannot safely interpret."""
    findings: list[_RuleEntry] = []
    section_end = rules_index + 1
    while section_end < len(lines):
        if _meaningful_yaml_line(lines[section_end]):
            current_indent = _leading_space_count(lines[section_end])
            if current_indent < rules_indent:
                break
            # YAML permits an indentationless sequence directly under a
            # mapping key (for example ``rules:\n- alert: ...``). Keep those
            # same-indent sequence items in the section so malformed nested
            # entries remain visible while the enclosing mapping boundary
            # remains outside the section.
            if current_indent == rules_indent and not (
                current_indent == sequence_indent
                and _is_sequence_item(lines[section_end], sequence_indent)
            ):
                break
        section_end += 1

    block_scalar_indent: int | None = None
    for line in lines[rules_index + 1 : section_end]:
        if not _meaningful_yaml_line(line):
            continue
        indent = _leading_space_count(line)
        content = _strip_yaml_comment(line).strip()
        if block_scalar_indent is not None:
            if indent <= block_scalar_indent:
                block_scalar_indent = None
            else:
                continue

        if _is_sequence_item(line) and indent != sequence_indent:
            findings.append(
                _RuleEntry(
                    lines=[line],
                    key=None,
                    name=None,
                    issue="inconsistent or nested rule sequence indentation",
                )
            )
        elif indent == sequence_indent and not _is_sequence_item(line):
            findings.append(
                _RuleEntry(
                    lines=[line],
                    key=None,
                    name=None,
                    issue="unsupported rule sequence entry boundary",
                )
            )

        if re.search(r":\s*[|>](?:(?:[+-][1-9]?)|(?:[1-9][+-]?)?)?(?:\s+#.*)?$", content):
            block_scalar_indent = indent

    starts = [
        index
        for index in range(rules_index + 1, section_end)
        if _is_sequence_item(lines[index], sequence_indent)
    ]
    if not starts:
        return findings
    for entry_start, entry_end in zip(
        starts, [*starts[1:], section_end], strict=True
    ):
        entry_lines = lines[entry_start:entry_end]
        sequence_header = _strip_yaml_comment(lines[entry_start]).lstrip()
        inline_mapping_match = re.match(r"^-([ \t]+)(?P<key>[^\s].*)$", sequence_header)
        inline_mapping_indent = (
            sequence_indent + 1 + len(inline_mapping_match.group(1))
            if inline_mapping_match is not None
            else None
        )
        root_field_indents = [
            _leading_space_count(line)
            for line in entry_lines
            if _meaningful_yaml_line(line)
            and _parse_mapping_header(line) is not None
            and _parse_mapping_header(line)[0]
            in SUPPORTED_RULE_ROOT_FIELDS
            and _leading_space_count(line) > sequence_indent
        ]
        direct_indent = min(root_field_indents, default=None)
        nested_mapping_indent: int | None = None
        entry_block_scalar_indent: int | None = None
        for line in entry_lines:
            if not _meaningful_yaml_line(line):
                continue
            indent = _leading_space_count(line)
            content = _strip_yaml_comment(line).strip()
            if entry_block_scalar_indent is not None:
                if indent <= entry_block_scalar_indent:
                    entry_block_scalar_indent = None
                else:
                    continue
            parsed = _parse_mapping_header(line)
            if parsed is not None and indent > sequence_indent:
                if nested_mapping_indent is not None and indent <= nested_mapping_indent:
                    nested_mapping_indent = None
                if (
                    (
                        direct_indent is not None
                        and nested_mapping_indent is None
                        and parsed[0]
                        in SUPPORTED_RULE_ROOT_FIELDS
                        and indent != direct_indent
                    )
                    or (
                        inline_mapping_indent is not None
                        and nested_mapping_indent is None
                        and parsed[0]
                        in SUPPORTED_RULE_INLINE_FIELDS
                        and indent != inline_mapping_indent
                    )
                ):
                    findings.append(
                        _RuleEntry(
                            lines=[line],
                            key=None,
                            name=None,
                            issue="inconsistent rule root-field indentation",
                        )
                    )
                elif (
                    direct_indent is not None
                    and indent == direct_indent
                    and parsed[0] in {"labels", "annotations"}
                ):
                    nested_mapping_indent = indent
                elif (
                    nested_mapping_indent is None
                    and parsed[0] not in SUPPORTED_RULE_ROOT_FIELDS
                ):
                    findings.append(
                        _RuleEntry(
                            lines=[line],
                            key=None,
                            name=None,
                            issue=(
                                "unsupported rule mapping header outside labels/annotations"
                            ),
                        )
                    )
            elif (
                parsed is None
                and not _is_sequence_item(line)
                and indent > sequence_indent
                and nested_mapping_indent is None
                and _looks_like_mapping_header(content)
            ):
                findings.append(
                    _RuleEntry(
                        lines=[line],
                        key=None,
                        name=None,
                        issue=(
                            "unsupported rule mapping header outside labels/annotations"
                        ),
                    )
                )
            if re.search(
                r":\s*[|>](?:(?:[+-][1-9]?)|(?:[1-9][+-]?)?)?(?:\s+#.*)?$",
                content,
            ):
                entry_block_scalar_indent = indent
    return findings


def _scan_rule_entries(yaml_text: str) -> list[_RuleEntry]:
    lines = yaml_text.splitlines()
    entries: list[_RuleEntry] = _unsupported_rules_key_shapes(lines)
    entries.extend(_duplicate_group_rules_keys(lines))
    standalone_mapping = _standalone_rule_mapping_entry(lines)
    if standalone_mapping is not None:
        entries.append(standalone_mapping)
    for index, line in enumerate(lines):
        parsed = _parse_mapping_header(line)
        if not parsed or parsed[0] != "rules":
            continue
        if parsed[1]:
            entries.append(_RuleEntry(lines=[line], key=None, name=None))
            continue

        rules_indent = _leading_space_count(line)
        child = next(
            (
                candidate
                for candidate in range(index + 1, len(lines))
                if _meaningful_yaml_line(lines[candidate])
            ),
            None,
        )
        if (
            child is None
            or _leading_space_count(lines[child]) < rules_indent
            or not _is_sequence_item(lines[child])
        ):
            entries.append(_RuleEntry(lines=[line], key=None, name=None))

        if child is not None and _is_sequence_item(lines[child]):
            entries.extend(
                _rule_sequence_structure_findings(
                    lines,
                    index,
                    rules_indent,
                    _leading_space_count(lines[child]),
                )
            )

    ranges = _rule_sequence_ranges(lines)
    if not ranges:
        standalone_range = _standalone_rule_sequence_range(lines)
        if standalone_range is not None:
            ranges.append(standalone_range)

    for start, end, sequence_indent in ranges:
        starts = [
            index
            for index in range(start, end)
            if _is_sequence_item(lines[index], sequence_indent)
        ]
        for entry_start, entry_end in zip(starts, [*starts[1:], end], strict=True):
            key, name = _parse_rule_entry_header(lines[entry_start])
            entries.append(
                _RuleEntry(
                    lines=lines[entry_start:entry_end],
                    key=key,
                    name=name,
                )
            )
    duplicate_entries: list[_RuleEntry] = []
    for entry in entries:
        if entry.key is None:
            continue
        duplicate_keys = _duplicate_rule_mapping_keys(entry.lines)
        if duplicate_keys:
            duplicate_entries.append(
                _RuleEntry(
                    lines=entry.lines,
                    key=None,
                    name=None,
                    issue=(
                        "duplicate rule mapping keys are unsupported: "
                        + ", ".join(duplicate_keys)
                    ),
                )
            )
    entries.extend(duplicate_entries)
    return entries


def _split_rule_entries(yaml_text: str, entry_key: str) -> list[_RuleEntry]:
    return [
        entry
        for entry in _scan_rule_entries(yaml_text)
        if entry.key in {entry_key, None}
    ]


def _unrecognized_rule_entry_finding(path: Path, entry_key: str, entry: _RuleEntry) -> Finding:
    if entry.issue:
        return Finding(
            path=path,
            message=(
                f"{entry.issue}; the dependency-free validator cannot safely inspect this YAML shape"
            ),
        )
    return Finding(
        path=path,
        message=(
            f"unrecognized {entry_key} rule sequence entry; "
            "the dependency-free validator cannot safely inspect this YAML shape"
        ),
    )


def _parse_labels(rule_lines: list[str]) -> dict[str, str]:
    labels_indent = _rule_mapping_indent(rule_lines)
    if labels_indent is None:
        return {}
    for index, line in enumerate(rule_lines):
        if _leading_space_count(line) != labels_indent:
            continue
        parsed_section = _parse_mapping_header(line)
        if parsed_section is None or parsed_section[0] != "labels" or parsed_section[1]:
            continue
        labels: dict[str, str] = {}
        for next_line in rule_lines[index + 1 :]:
            if next_line.strip() == "":
                continue
            next_indent = _leading_space_count(next_line)
            if next_indent <= labels_indent:
                break
            parsed = _parse_mapping_header(next_line)
            if parsed is None or not parsed[1]:
                continue
            key, raw_value = parsed
            value, unsupported = _parse_rule_name(raw_value)
            labels[key] = "" if unsupported or value is None else value
        return labels
    return {}


def _rule_mapping_indent(rule_lines: list[str]) -> int | None:
    if not rule_lines:
        return None

    first_line = rule_lines[0]
    first_indent = _leading_space_count(first_line)
    if not _is_sequence_item(first_line, first_indent):
        return first_indent
    return next(
        (
            _leading_space_count(line)
            for line in rule_lines[1:]
            if _meaningful_yaml_line(line)
            and _leading_space_count(line) > first_indent
            and _parse_mapping_header(line) is not None
        ),
        None,
    )


def _duplicate_rule_mapping_keys(rule_lines: list[str]) -> list[str]:
    mapping_indent = _rule_mapping_indent(rule_lines)
    if mapping_indent is None:
        return []

    keys: list[str] = []
    first_line = rule_lines[0]
    if _is_sequence_item(first_line, _leading_space_count(first_line)):
        key, _ = _parse_rule_entry_header(first_line)
        if key is not None:
            keys.append(key)
    for line in rule_lines[1:] if keys else rule_lines:
        if _leading_space_count(line) != mapping_indent:
            continue
        parsed = _parse_mapping_header(line)
        if parsed is not None:
            keys.append(parsed[0])
    duplicates = {key for key in keys if keys.count(key) > 1}

    # Labels and annotations are nested mappings. Detect duplicate keys within
    # each mapping independently; the same key in two separate mappings is
    # valid YAML and must not be conflated.
    for index, line in enumerate(rule_lines):
        if _leading_space_count(line) != mapping_indent:
            continue
        parsed = _parse_mapping_header(line)
        if parsed is None or parsed[1]:
            continue
        child_indent = next(
            (
                _leading_space_count(candidate)
                for candidate in rule_lines[index + 1 :]
                if _meaningful_yaml_line(candidate)
                and _leading_space_count(candidate) > mapping_indent
            ),
            None,
        )
        if child_indent is None:
            continue
        nested_keys: list[str] = []
        for candidate in rule_lines[index + 1 :]:
            if not _meaningful_yaml_line(candidate):
                continue
            candidate_indent = _leading_space_count(candidate)
            if candidate_indent <= mapping_indent:
                break
            if candidate_indent != child_indent:
                continue
            nested = _parse_mapping_header(candidate)
            if nested is not None:
                nested_keys.append(nested[0])
        duplicates.update(
            f"{parsed[0]}.{key}"
            for key in set(nested_keys)
            if nested_keys.count(key) > 1
        )

    return sorted(duplicates)


def _duplicate_group_rules_keys(lines: list[str]) -> list[_RuleEntry]:
    """Reject duplicate ``rules`` keys within one Prometheus group mapping."""
    findings: list[_RuleEntry] = []
    for index, line in enumerate(lines):
        parsed = _parse_mapping_header(line)
        if not parsed or parsed[0] != "groups" or parsed[1]:
            continue
        groups_indent = _leading_space_count(line)
        first_item = next(
            (
                candidate
                for candidate in range(index + 1, len(lines))
                if _meaningful_yaml_line(lines[candidate])
            ),
            None,
        )
        if first_item is None or _leading_space_count(lines[first_item]) < groups_indent:
            continue
        if not _is_sequence_item(lines[first_item]):
            continue
        sequence_indent = _leading_space_count(lines[first_item])
        section_end = first_item
        while section_end < len(lines):
            if _meaningful_yaml_line(lines[section_end]):
                current_indent = _leading_space_count(lines[section_end])
                if current_indent < groups_indent:
                    break
                # YAML permits an indentationless sequence directly under a
                # mapping key. Same-indent sequence items remain part of this
                # groups section; any other same-indent node closes it.
                if current_indent == groups_indent and not (
                    current_indent == sequence_indent
                    and _is_sequence_item(lines[section_end], sequence_indent)
                ):
                    break
            section_end += 1
        starts = [
            candidate
            for candidate in range(first_item, section_end)
            if _meaningful_yaml_line(lines[candidate])
            and _leading_space_count(lines[candidate]) == sequence_indent
            and _is_sequence_item(lines[candidate])
        ]
        for start, end in zip(starts, [*starts[1:], section_end], strict=True):
            group_lines = lines[start:end]
            direct_indents = [
                _leading_space_count(candidate)
                for candidate in group_lines[1:]
                if _meaningful_yaml_line(candidate)
                and _parse_mapping_header(candidate) is not None
                and _leading_space_count(candidate) > sequence_indent
            ]
            if not direct_indents:
                continue
            mapping_indent = min(direct_indents)
            rules_lines = [
                candidate
                for candidate in group_lines[1:]
                if _leading_space_count(candidate) == mapping_indent
                and _parse_mapping_header(candidate) is not None
                and _parse_mapping_header(candidate)[0] == "rules"
            ]
            if len(rules_lines) > 1:
                findings.append(
                    _RuleEntry(
                        lines=rules_lines,
                        key=None,
                        name=None,
                        issue="duplicate group mapping keys are unsupported: rules",
                    )
                )
    return findings


def _parse_rule_scalar(rule_lines: list[str], field: str) -> str | None:
    if not rule_lines:
        return None

    mapping_indent = _rule_mapping_indent(rule_lines)
    if mapping_indent is None:
        return None

    for line in rule_lines:
        if _leading_space_count(line) != mapping_indent:
            continue
        parsed = _parse_mapping_header(line)
        if not parsed or parsed[0] != field:
            continue
        value, unsupported = _parse_rule_name(parsed[1])
        return None if unsupported else value
    return None


def _rule_scalar_is_present(rule_lines: list[str], field: str) -> bool:
    if not rule_lines:
        return False

    mapping_indent = _rule_mapping_indent(rule_lines)
    if mapping_indent is None:
        return False

    return any(
        _leading_space_count(line) == mapping_indent
        and (parsed := _parse_mapping_header(line)) is not None
        and parsed[0] == field
        for line in rule_lines
    )


def _entry_path_blackbox_findings(
    path: Path,
    alert_name: str,
    labels: dict[str, str],
    expr: str,
    hold: str | None,
) -> list[Finding]:
    contract = ENTRY_PATH_BLACKBOX_ALERT_CONTRACTS.get(alert_name)
    if contract is None:
        return []

    metric_selector = (
        "entrypath_blackbox_probe_success"
        f'{{path="{contract["path"]}",target="{contract["target"]}"}}'
    )
    expected_expr = _compact_promql(
        f"absent({metric_selector})"
        if contract["expression"] == "absent"
        else f"{metric_selector} == 0"
    )
    findings: list[Finding] = []
    expected_labels = {
        "severity": "P1" if contract["expression"] == "absent" else "P0",
        "component": "entrypath",
        "service": contract["service"],
    }
    for label, expected in expected_labels.items():
        if labels.get(label) != expected:
            findings.append(
                Finding(
                    path=path,
                    message=f"{alert_name} must use labels.{label}={expected}",
                )
            )
    if hold != "2m":
        findings.append(
            Finding(
                path=path,
                message=f"{alert_name} must use a rule-level for: 2m hold",
            )
        )
    if _compact_promql(expr) != expected_expr:
        expression_description = (
            "entrypath_blackbox_probe_success selector with absent()"
            if contract["expression"] == "absent"
            else "entrypath_blackbox_probe_success selector and compare it to zero"
        )
        findings.append(
            Finding(
                path=path,
                message=(
                    f'{alert_name} must use only the exact path="{contract["path"]}", '
                    f'target="{contract["target"]}" '
                    f"{expression_description}"
                ),
            )
        )
    return findings


def _service_scoped_alert_findings(
    path: Path,
    alert_name: str,
    labels: dict[str, str],
    expr: str,
) -> list[Finding]:
    contract = SERVICE_SCOPED_ALERT_CONTRACTS.get(alert_name)
    if contract is None:
        return []
    metric_name, service = contract
    findings: list[Finding] = []
    if labels.get("service") != service:
        findings.append(
            Finding(
                path=path,
                message=f"{alert_name} must use labels.service={service}",
            )
        )
    if not _all_metric_selectors_have_exact_label(
        expr, metric_name, "service", service
    ):
        findings.append(
            Finding(
                path=path,
                message=f'{alert_name} must scope expr to service="{service}"',
            )
        )
    return findings


def _playerflow_canary_label_findings(
    path: Path, alert_name: str, labels: dict[str, str]
) -> list[Finding]:
    if alert_name not in PLAYERFLOW_CANARY_ALERTS:
        return []
    return [
        Finding(
            path=path,
            message=f"{alert_name} must use labels.{label}={expected}",
        )
        for label, expected in PLAYERFLOW_CANARY_REQUIRED_LABELS.items()
        if labels.get(label) != expected
    ]


def _playerflow_canary_service_findings(
    path: Path, alert_name: str, labels: dict[str, str]
) -> list[Finding]:
    if alert_name in SERVICE_DERIVED_ALERTS and "service" in labels:
        return [
            Finding(
                path=path,
                message=(
                    f"{alert_name} must not set labels.service; use the "
                    "recording-rule service label"
                ),
            )
        ]
    if alert_name in SERVICE_OPTIONAL_ALERTS and "service" in labels:
        return [
            Finding(
                path=path,
                message=(
                    f"{alert_name} must not set labels.service on a cross-path canary alert"
                ),
            )
        ]
    if (
        alert_name == "PlayerFlowCanaryEvidenceStale"
        and "service" in labels
        and labels["service"] != "prometheus"
    ):
        return [
            Finding(
                path=path,
                message=(
                    "PlayerFlowCanaryEvidenceStale must use labels.service=prometheus"
                ),
            )
        ]
    return []


def _parse_expr(rule_lines: list[str]) -> str | None:
    expr_mapping_indent = _rule_mapping_indent(rule_lines)
    if expr_mapping_indent is None:
        return None
    for index, line in enumerate(rule_lines):
        if _leading_space_count(line) != expr_mapping_indent:
            continue
        parsed = _parse_mapping_header(line)
        if parsed is None or parsed[0] != "expr":
            continue
        expr_indent = _leading_space_count(line)
        raw_scalar = parsed[1]
        scalar = _strip_yaml_comment(raw_scalar).strip()
        is_block_scalar = (
            re.fullmatch(
                r"[|>](?:(?:[+-][1-9]?)|(?:[1-9][+-]?))?(?:\s+#.*)?",
                scalar,
            )
            is not None
        )
        block_scalar_indent_indicator = None
        if is_block_scalar:
            block_scalar_header = _strip_yaml_comment(scalar).strip()
            indicator = re.search(r"[1-9]", block_scalar_header)
            if indicator is not None:
                block_scalar_indent_indicator = int(indicator.group(0))
        elif scalar.startswith(("|", ">")):
            return ""
        normalized_scalar, unsupported_scalar = _parse_rule_name(scalar)
        if not is_block_scalar and not unsupported_scalar and normalized_scalar is not None:
            scalar = normalized_scalar
        expr_lines = [] if is_block_scalar or not scalar else [scalar]
        for next_line in rule_lines[index + 1 :]:
            next_indent = len(next_line) - len(next_line.lstrip(" "))
            if next_line.strip() == "":
                expr_lines.append("")
                continue
            if next_indent <= expr_indent:
                break
            expr_lines.append(next_line.rstrip())
        if block_scalar_indent_indicator is not None:
            first_content_line = next(
                (line for line in rule_lines[index + 1 :] if line.strip()),
                None,
            )
            if (
                first_content_line is not None
                and _leading_space_count(first_content_line)
                < expr_indent + block_scalar_indent_indicator
            ):
                return ""
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
            malformed_block_scalar = first_value_line.startswith(("|", ">"))
            unsupported_indirection = first_value_line.startswith(("#", "!", "&", "*"))
            collection_node = first_value_line.startswith(("{", "[")) or (
                not scalar
                and (
                    re.match(r"^-(?:\s|$)", first_value_line) is not None
                    or re.match(r"^\?(?:\s|$)", first_value_line) is not None
                    or re.match(r"^(?:[^'\"]+|'[^']+'|\"[^\"]+\"):\s", first_value_line)
                    is not None
                )
            )
            if (
                empty_scalar
                or malformed_block_scalar
                or unsupported_indirection
                or collection_node
            ):
                return ""
        return expression
    return None


def _mask_promql_non_code(expr: str) -> str:
    """Mask strings/comments while preserving offsets for lexical checks."""
    characters = list(expr)
    quote: str | None = None
    escaped = False
    index = 0
    while index < len(expr):
        character = expr[index]
        if quote is not None:
            characters[index] = " " if character != "\n" else "\n"
            if quote != "`" and escaped:
                escaped = False
            elif quote != "`" and character == "\\":
                escaped = True
            elif character == quote:
                quote = None
            index += 1
            continue
        if character in {'"', "'", "`"}:
            characters[index] = " "
            quote = character
            index += 1
            continue
        if character == "#":
            characters[index] = " "
            index += 1
            while index < len(expr) and expr[index] != "\n":
                characters[index] = " "
                index += 1
            continue
        index += 1
    return "".join(characters)


def _mask_promql_comments(expr: str) -> str:
    """Mask PromQL comments while preserving quoted matcher values."""
    characters = list(expr)
    quote: str | None = None
    escaped = False
    index = 0
    while index < len(expr):
        character = expr[index]
        if quote is not None:
            if escaped:
                escaped = False
            elif character == "\\":
                escaped = True
            elif character == quote:
                quote = None
            index += 1
            continue
        if character in {'"', "'", "`"}:
            quote = character
            index += 1
            continue
        if character == "#":
            characters[index] = " "
            index += 1
            while index < len(expr) and expr[index] != "\n":
                characters[index] = " "
                index += 1
            continue
        index += 1
    return "".join(characters)


def _promql_metric_occurrences(expr: str, metric_name: str) -> list[re.Match[str]]:
    metric_pattern = re.compile(
        rf"(?<![A-Za-z0-9_:]){re.escape(metric_name)}(?![A-Za-z0-9_:])"
    )
    return list(metric_pattern.finditer(_mask_promql_non_code(expr)))


def _promql_selector_after(expr: str, end: int) -> str | None:
    index = end
    while index < len(expr):
        if expr[index].isspace():
            index += 1
            continue
        if expr[index] == "#":
            newline = expr.find("\n", index + 1)
            index = len(expr) if newline == -1 else newline + 1
            continue
        break
    if index >= len(expr) or expr[index] != "{":
        return None

    start = index
    depth = 0
    quote: str | None = None
    escaped = False
    while index < len(expr):
        character = expr[index]
        if quote is not None:
            if quote != "`" and escaped:
                escaped = False
            elif quote != "`" and character == "\\":
                escaped = True
            elif character == quote:
                quote = None
        elif character in {'"', "'", "`"}:
            quote = character
        elif character == "#":
            newline = expr.find("\n", index + 1)
            index = len(expr) if newline == -1 else newline
            continue
        elif character == "{":
            depth += 1
        elif character == "}":
            depth -= 1
            if depth == 0:
                return expr[start : index + 1]
        index += 1
    return None


def _check_ms_thresholds(expr: str) -> str | None:
    code_expr = _mask_promql_non_code(expr)
    if not re.search(r"_ms(?:_|\b)", code_expr):
        return None

    # A direct division of two `_ms` operands produces a dimensionless ratio.
    # PromQL may place `on`/`ignoring` and `group_left`/`group_right`
    # vector-matching modifiers between the operands. Only mask those direct
    # ratio operands; an unrelated raw `_ms` comparison in the same arithmetic
    # expression must still be checked.
    ms_operand = (
        r"(?:[A-Za-z_:][A-Za-z0-9_:]*_ms(?:_[A-Za-z0-9_:]+)*"
        r"(?:\{[^{}]*\})?|label_replace\([^()]*_ms(?:_[A-Za-z0-9_:]+)*"
        r"(?:\{[^{}]*\})?[^()]*\))"
    )
    dimensionless_ms_ratio = re.compile(
        rf"(?<![A-Za-z0-9_:]){ms_operand}/"
        r"(?:(?:on|ignoring)\([^()]*\))?"
        r"(?:(?:group_left|group_right)(?:\([^()]*\))?)?"
        rf"{ms_operand}"
    )

    # A compound PromQL expression can contain unrelated numeric comparisons.
    # Keep each logical clause with the metric it constrains so a later
    # `queue_depth > 0` cannot be mistaken for the threshold of an earlier
    # `latency_ms > 1000` comparison.
    clause_starts = [0]
    clause_ends: list[int] = []
    for logical_operator in re.finditer(
        r"\b(?:and|or|unless)\b", code_expr, re.IGNORECASE
    ):
        clause_ends.append(logical_operator.start())
        clause_starts.append(logical_operator.end())
    clause_ends.append(len(expr))

    for clause_start, clause_end in zip(clause_starts, clause_ends, strict=True):
        clause_code = code_expr[clause_start:clause_end]
        if not re.search(r"_ms(?:_|\b)", clause_code):
            continue
        normalized = re.sub(r"\s+", "", clause_code)
        ratio_spans = [
            match.span() for match in dimensionless_ms_ratio.finditer(normalized)
        ]
        residual = normalized
        for ratio_start, ratio_end in reversed(ratio_spans):
            residual = residual[:ratio_start] + residual[ratio_end:]
        if ratio_spans and not re.search(r"_ms(?:_|\b)", residual):
            continue
        # PromQL supports decimal and exponent-form float literals. Match the
        # complete literal after any scalar comparison so `1e2` is not parsed
        # as `1`, and cover both directions used by alert expressions.
        promql_number = r"(?:[0-9]+(?:\.[0-9]*)?|\.[0-9]+)(?:[eE][+-]?[0-9]+)?"
        numeric_comparisons = re.findall(
            rf"(?:<=|>=|<|>)\s*({promql_number})(?![A-Za-z0-9_.])",
            clause_code,
        )
        if not numeric_comparisons:
            continue
        threshold = float(numeric_comparisons[-1])
        if threshold < 10:
            return f"expression compares an `_ms` metric against {threshold}; this looks like seconds, but `_ms` metrics are milliseconds"
    return None


def _tick_replay_scope_matching_finding(
    path: Path, alert_name: str, expr: str
) -> Finding | None:
    metric_name = TICK_REPLAY_ALERT_METRICS.get(alert_name)
    if metric_name is None:
        return None
    code_expr = _mask_promql_non_code(expr)
    occurrences = _promql_metric_occurrences(code_expr, metric_name)
    scoped = bool(occurrences) and all(
        (selector := _promql_selector_after(expr, occurrence.end())) is not None
        and TICK_SCOPE_CLASS_MATCHER_RE.search(_mask_promql_comments(selector[1:-1]))
        for occurrence in occurrences
    )
    expected = (
        f"{metric_name}{{{TICK_SCOPE_CLASS_MATCHER}}}>"
        f"{TICK_REPLAY_ALERT_THRESHOLDS[alert_name]}"
    )
    if scoped and _compact_promql(_mask_promql_comments(expr)) == expected:
        return None
    return Finding(
        path=path,
        message=(
            f"{alert_name} must use {metric_name} with the exact bounded "
            "scope_class matcher"
        ),
    )


def _promql_logical_operator_depths(expr: str) -> list[tuple[str, int]]:
    """Return logical operators and their parenthesis depth outside literals."""
    code_expr = _mask_promql_non_code(expr)
    operators = re.compile(r"(?<![A-Za-z0-9_:])(?:and|or|unless)(?![A-Za-z0-9_:])", re.IGNORECASE)
    depths: list[tuple[str, int]] = []
    depth = 0
    index = 0
    while index < len(code_expr):
        character = code_expr[index]
        if character == "(":
            depth += 1
            index += 1
            continue
        if character == ")":
            depth = max(0, depth - 1)
            index += 1
            continue
        match = operators.match(code_expr, index)
        if match:
            depths.append((match.group(0).lower(), depth))
            index = match.end()
            continue
        index += 1
    return depths


def _tick_execution_ratio_finding(
    path: Path, alert_name: str, expr: str, *, require_threshold: bool = False
) -> Finding | None:
    if alert_name != "TickExecutionUnsafeRatio":
        return None
    normalized = _compact_promql(_mask_promql_comments(expr))
    if require_threshold:
        if not normalized.endswith(">0.75"):
            return Finding(
                path=path,
                message="TickExecutionUnsafeRatio must use the exact >0.75 threshold",
            )
        core = normalized[: -len(">0.75")]
    else:
        if re.search(r"(?:<=|>=|<|>)", normalized):
            return Finding(
                path=path,
                message="TickExecutionUnsafeRatio dashboard expressions must not add a threshold",
            )
        core = normalized

    # Alert expressions wrap the two branches in one outer pair so the
    # comparison applies to the complete ratio. Dashboard expressions expose
    # the same branches without that outer wrapper. In either form there must
    # be exactly one OR at the branch level and no logical operator outside it.
    expected_depth = 1 if require_threshold else 0
    logical_depths = _promql_logical_operator_depths(core)
    if any(depth < expected_depth for _, depth in logical_depths):
        return Finding(
            path=path,
            message="TickExecutionUnsafeRatio must not contain extra top-level logical branches",
        )
    branch_ors = [
        (operator, depth) for operator, depth in logical_depths if depth == expected_depth
    ]
    if branch_ors != [("or", expected_depth)]:
        return Finding(
            path=path,
            message="TickExecutionUnsafeRatio must contain exactly one normal/solo OR branch",
        )

    if require_threshold:
        if not (core.startswith("(") and core.endswith(")")):
            return Finding(
                path=path,
                message="TickExecutionUnsafeRatio alert must wrap both branches before applying >0.75",
            )
        branch_core = core[1:-1]
    else:
        branch_core = core

    branch_operator = re.search(r"(?<![A-Za-z0-9_:])or(?![A-Za-z0-9_:])", _mask_promql_non_code(branch_core))
    if branch_operator is None:
        return Finding(
            path=path,
            message="TickExecutionUnsafeRatio must retain separate normal and solo branches",
        )
    branches = [
        branch_core[: branch_operator.start()],
        branch_core[branch_operator.end() :],
    ]
    bounded = TICK_SCOPE_CLASS_MATCHER
    for mode, denominator in (("normal", "tick_lock_ttl_ms"), ("solo", "solo_lock_ttl_ms")):
        expected_branch = (
            f"(tick_execution_time_ms_p99{{{bounded},tick_mode=\"{mode}\"}}"
            f"/on(scope_class,tick_mode)label_replace({denominator}{{{bounded}}},"
            f"\"tick_mode\",\"{mode}\",\"scope_class\",\".*\"))"
        )
        branch_index = 0 if mode == "normal" else 1
        if branches[branch_index] != expected_branch:
            return Finding(
                path=path,
                message=(
                    "TickExecutionUnsafeRatio must select normal and solo "
                    "p99 series with the exact bounded scope_class matcher"
                ),
            )
    return None


def _tick_scope_selector_finding(
    path: Path, expr: str, *, allow_unbounded_scope: bool = False
) -> Finding | None:
    """Require bounded scope and canonical labels on every shared tick selector."""
    bounded_scope_matcher = re.compile(
        r'(?:^|,)\s*scope\s*(?P<operator>=~|=)\s*'
        r'"(?P<value>(?:\\.|[^"\\])*)"\s*(?:,|$)',
        re.IGNORECASE,
    )
    # A deployment scope is bounded only when it is an exact matcher or a
    # regex that enumerates bounded values.  In particular, PromQL wildcard
    # constructs such as `.+` and `.*` must not satisfy the tick_interval_ms
    # scope exemption.  Escaped metacharacters remain literal values.
    unbounded_scope_regex = re.compile(
        r'(?<!\\)(?:[.*+?\[\]{}]|\\[dDsSwW])'
    )
    label_matcher = re.compile(
        r'(?:^|,)\s*([A-Za-z_][A-Za-z0-9_]*)\s*(?:!=|=~|!~|=)'
    )
    code_expr = _mask_promql_non_code(expr)
    for metric_name in sorted(TICK_SCOPED_METRICS):
        for occurrence in _promql_metric_occurrences(code_expr, metric_name):
            selector = _promql_selector_after(expr, occurrence.end())
            if selector is None:
                return Finding(
                    path=path,
                    message=(
                        f"tick metric {metric_name} must use the exact bounded "
                        "scope_class matcher region|game_instance|tenant|cluster"
                    ),
                )
            selector_body = _mask_promql_non_code(selector[1:-1])
            selector_comments = _mask_promql_comments(selector[1:-1])
            has_bounded_scope_class = TICK_SCOPE_CLASS_MATCHER_RE.search(selector_comments)
            scope_matchers = list(bounded_scope_matcher.finditer(selector_comments))
            has_bounded_scope = any(
                match.group("operator") == "="
                or unbounded_scope_regex.search(match.group("value")) is None
                for match in scope_matchers
            )
            has_unbounded_scope = any(
                match.group("operator") == "=~"
                and unbounded_scope_regex.search(match.group("value")) is not None
                for match in scope_matchers
            )
            if not has_bounded_scope_class and not (
                metric_name == "tick_interval_ms"
                and (
                    allow_unbounded_scope
                    or (has_bounded_scope and not has_unbounded_scope)
                )
            ):
                return Finding(
                    path=path,
                    message=(
                        f"tick metric {metric_name} must use the exact bounded "
                        "scope_class matcher region|game_instance|tenant|cluster"
                    ),
                )
            labels = set(label_matcher.findall(selector_body))
            allowed_labels = TICK_SCOPED_METRIC_LABELS[metric_name]
            unexpected_labels = sorted(labels - allowed_labels)
            if unexpected_labels:
                return Finding(
                    path=path,
                    message=(
                        f"tick metric {metric_name} uses unsupported labels: "
                        + ", ".join(unexpected_labels)
                    ),
                )
    return None


def _check_grpc_app_error_scoping(expr: str) -> str | None:
    if not _all_metric_selectors_have_label(expr, "grpc_app_error_total", "service"):
        return "expression references grpc_app_error_total without a `service=...` matcher; shared dashboards/snippets must scope by `service`"
    return None


def _all_metric_selectors_have_label(
    expr: str, metric_name: str, label_name: str
) -> bool:
    label_matcher = re.compile(
        rf'(?:^|,)\s*{re.escape(label_name)}\s*=\s*'
        r'(?:~\s*)?"(?:\\.|[^"\\])*"\s*(?:,|$)'
    )
    occurrences = _promql_metric_occurrences(expr, metric_name)
    if not occurrences:
        return True
    for occurrence in occurrences:
        selector = _promql_selector_after(expr, occurrence.end())
        if selector is None or not label_matcher.search(
            _mask_promql_comments(selector[1:-1])
        ):
            return False
    return True


def _all_metric_selectors_have_exact_label(
    expr: str, metric_name: str, label_name: str, label_value: str
) -> bool:
    """Require exact labels on every occurrence.

    Unlike the looser helper, absence fails closed.
    """
    exact_matcher = re.compile(
        rf'(?:^|,)\s*{re.escape(label_name)}\s*=\s*"{re.escape(label_value)}"\s*(?:,|$)',
    )
    occurrences = _promql_metric_occurrences(expr, metric_name)
    if not occurrences:
        return False
    return all(
        (selector := _promql_selector_after(expr, occurrence.end())) is not None
        and exact_matcher.search(_mask_promql_comments(selector[1:-1]))
        for occurrence in occurrences
    )


def _check_dotted_metric_tokens(expr: str) -> str | None:
    for token in re.findall(
        r"\b[A-Za-z_][A-Za-z0-9_\.]*\b", _mask_promql_non_code(expr)
    ):
        if "." not in token:
            continue
        if re.fullmatch(r"\d+\.\d+", token):
            continue
        return f"expression references dotted token {token!r}; Prometheus metric names in shared assets must use snake_case"
    return None


def _exact_metric_label_selector_finding(
    path: Path,
    expr: str,
    metric_name: str,
    label_name: str,
    label_value: str,
    message: str,
) -> Finding | None:
    exact_matcher = re.compile(
        rf'(?:\{{|,)\s*{re.escape(label_name)}\s*=\s*'
        rf'"{re.escape(label_value)}"\s*(?:,|\}})'
    )
    occurrences = _promql_metric_occurrences(expr, metric_name)
    if occurrences and all(
        (selector := _promql_selector_after(expr, occurrence.end())) is not None
        and exact_matcher.search(_mask_promql_comments(selector))
        for occurrence in occurrences
    ):
        return None
    return Finding(path=path, message=message)


def _recipient_dispatch_selector_finding(
    path: Path, expr: str, metric_name: str, context: str
) -> Finding | None:
    return _exact_metric_label_selector_finding(
        path,
        expr,
        metric_name,
        "completion_boundary",
        "recipient_dispatch",
        (
            f"{context} must select "
            'completion_boundary="recipient_dispatch" on every '
            f"{metric_name} selector"
        ),
    )


def _chat_recording_grouping_finding(path: Path, expr: str) -> Finding | None:
    required_grouping = {
        "service",
        "scope",
        "completion_boundary",
        "channel_type",
        "le",
    }
    groupings = [
        {label.strip() for label in match.group(1).split(",") if label.strip()}
        for match in re.finditer(
            r"sum\s+by\s*\(([^)]*)\)",
            _mask_promql_non_code(expr),
            re.IGNORECASE,
        )
    ]
    if any(required_grouping.issubset(grouping) for grouping in groupings):
        return None
    return Finding(
        path=path,
        message=(
            "canonical chat delivery recording rule must group by service, "
            "scope, completion_boundary, channel_type, and le"
        ),
    )


def _player_slo_calibration_findings(
    path: Path, alert_name: str, labels: dict[str, str]
) -> list[Finding]:
    if alert_name not in PLAYER_SLO_CALIBRATION_ALERTS:
        return []
    findings: list[Finding] = []
    if labels.get("severity") != "P2":
        findings.append(
            Finding(
                path=path,
                message=f"{alert_name} calibration alert must use severity=P2",
            )
        )
    if labels.get("slo_state") != "calibration":
        findings.append(
            Finding(
                path=path,
                message=f"{alert_name} calibration alert must use slo_state=calibration",
            )
        )
    return findings


def _command_scope_query_findings(path: Path, expr: str) -> list[Finding]:
    # Restrict command-scope checks to PromQL code. Metric names in
    # label_replace values, comments, and other string literals are data, not
    # command-latency expressions to be grouped.
    code_expr = _mask_promql_non_code(expr)
    if "command_end_to_end_latency_ms_bucket" not in code_expr:
        return []

    findings: list[Finding] = []
    groupings = [
        {
            label.strip().lower()
            for label in grouping.group(1).split(",")
            if label.strip()
        }
        for grouping in re.finditer(
            r"sum\s+by\s*\(([^)]*)\)", code_expr, re.IGNORECASE
        )
    ]
    if any("region" in grouping for grouping in groupings):
        findings.append(
            Finding(
                path=path,
                message=(
                    "canonical command latency by-scope panels must not group "
                    "command latency expressions by raw region"
                ),
            )
        )
    if not groupings or any(
        not {"scope", "command"}.issubset(grouping) for grouping in groupings
    ):
        findings.append(
            Finding(
                path=path,
                message=(
                    "canonical command latency by-scope panels must group each "
                    "command latency expression by bounded scope and command"
                ),
            )
        )
    return findings


def _mask_kibana_query_strings(query: str) -> str:
    """Mask quoted KQL values while preserving operators outside strings."""
    characters = list(query)
    quote: str | None = None
    escaped = False
    for index, character in enumerate(query):
        if quote is not None:
            characters[index] = "\n" if character == "\n" else " "
            if escaped:
                escaped = False
            elif character == "\\":
                escaped = True
            elif character == quote:
                quote = None
            continue
        if character in {'"', "'"}:
            characters[index] = " "
            quote = character
    return "".join(characters)


def _split_kibana_query_clauses(query: str) -> list[str]:
    """Split conjunctions at operators outside quoted KQL values."""
    masked_query = _mask_kibana_query_strings(query)
    clauses: list[str] = []
    start = 0
    for separator in re.finditer(r"\band\b", masked_query, re.IGNORECASE):
        clauses.append(query[start : separator.start()].strip())
        start = separator.end()
    clauses.append(query[start:].strip())
    return clauses


def _validate_alert_snippet(path: Path) -> list[Finding]:
    findings: list[Finding] = []
    markdown = _read_text(path)
    yaml_blocks = _extract_fenced_blocks(markdown, "yaml")
    for yaml_block in yaml_blocks:
        for entry in _split_alert_rules(yaml_block):
            if entry.key is None:
                findings.append(_unrecognized_rule_entry_finding(path, "alert", entry))
                continue
            if not entry.name:
                findings.append(Finding(path=path, message="alert rule is missing name"))
                continue
            rule_lines = entry.lines
            labels = _parse_labels(rule_lines)
            findings.extend(
                _player_slo_calibration_findings(path, entry.name, labels)
            )
            findings.extend(
                _playerflow_canary_label_findings(path, entry.name, labels)
            )
            findings.extend(
                _playerflow_canary_service_findings(path, entry.name, labels)
            )
            required_labels = REQUIRED_ALERT_LABELS - (
                {"service"}
                if entry.name in SERVICE_OPTIONAL_ALERTS | SERVICE_DERIVED_ALERTS
                else set()
            )
            missing = sorted(required_labels - labels.keys())
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

            findings.extend(_alert_runbook_findings(path, labels.get("runbook", "")))

            alert_class = labels.get("alert_class")
            if alert_class == "test" and severity != "P2":
                findings.append(Finding(path=path, message="test alerts must use severity=P2 and alert_class=test (never severity=test)"))

            expr = _parse_expr(rule_lines)
            if not expr:
                findings.append(Finding(path=path, message="alert rule is missing expr"))
                continue

            findings.extend(
                _service_scoped_alert_findings(path, entry.name, labels, expr)
            )

            findings.extend(
                _entry_path_blackbox_findings(
                    path,
                    entry.name,
                    labels,
                    expr,
                    _parse_rule_scalar(rule_lines, "for"),
                )
            )
            if entry.name == "ChatDeliveryLatencyP99High":
                chat_selector_issue = _recipient_dispatch_selector_finding(
                    path,
                    expr,
                    "chat_delivery_latency_ms_bucket",
                    "ChatDeliveryLatencyP99High alert snippet",
                )
                if chat_selector_issue:
                    findings.append(chat_selector_issue)

            ms_issue = _check_ms_thresholds(expr)
            if ms_issue:
                findings.append(Finding(path=path, message=ms_issue))

            tick_scope_issue = _tick_replay_scope_matching_finding(
                path, entry.name, expr
            )
            if tick_scope_issue:
                findings.append(tick_scope_issue)
            tick_ratio_issue = _tick_execution_ratio_finding(
                path, entry.name, expr, require_threshold=True
            )
            if tick_ratio_issue:
                findings.append(tick_ratio_issue)
            tick_selector_issue = _tick_scope_selector_finding(
                path,
                expr,
                # Target-only snippets intentionally use deployment-scope
                # placeholders until their producer and bounded-label
                # contract are implemented.
                allow_unbounded_scope=entry.name in TARGET_ONLY_INSTALLED_ALERTS,
            )
            if tick_selector_issue:
                findings.append(tick_selector_issue)

            grpc_scope_issue = _check_grpc_app_error_scoping(expr)
            if grpc_scope_issue:
                findings.append(Finding(path=path, message=grpc_scope_issue))

            dotted_metric_issue = _check_dotted_metric_tokens(expr)
            if dotted_metric_issue:
                findings.append(Finding(path=path, message=dotted_metric_issue))

            recovery_coverage_issue = _recovery_coverage_alert_finding(path, entry.name, expr)
            if recovery_coverage_issue:
                findings.append(recovery_coverage_issue)

    return findings


def _validate_grafana_dashboards(grafana_dir: Path) -> list[Finding]:
    findings: list[Finding] = []
    for json_path in sorted(grafana_dir.glob("*.json")):
        try:
            dashboard = json.loads(_read_text(json_path))
        except json.JSONDecodeError as exc:
            findings.append(Finding(path=json_path, message=f"invalid JSON: {exc}"))
            continue

        if not isinstance(dashboard, dict):
            findings.append(
                Finding(path=json_path, message="Grafana dashboard root must be a JSON object")
            )
            continue
        panels = dashboard.get("panels", [])
        if not isinstance(panels, list):
            findings.append(
                Finding(path=json_path, message="Grafana dashboard panels must be a JSON array")
            )
            continue
        for panel in panels:
            if not isinstance(panel, dict):
                findings.append(
                    Finding(path=json_path, message="Grafana dashboard panels must contain JSON objects")
                )
                continue
            targets = panel.get("targets", [])
            if not isinstance(targets, list):
                findings.append(
                    Finding(path=json_path, message="Grafana dashboard panel targets must be a JSON array")
                )
                continue
            for target in targets:
                if not isinstance(target, dict):
                    findings.append(
                        Finding(path=json_path, message="Grafana dashboard targets must contain JSON objects")
                    )
                    continue
                expr = target.get("expr")
                if not expr or not isinstance(expr, str):
                    continue
                if "tick_execution_time_ms_p99" in _mask_promql_non_code(expr):
                    tick_ratio_issue = _tick_execution_ratio_finding(
                        json_path, "TickExecutionUnsafeRatio", expr
                    )
                    if tick_ratio_issue:
                        findings.append(tick_ratio_issue)
                tick_selector_issue = _tick_scope_selector_finding(json_path, expr)
                if tick_selector_issue:
                    findings.append(tick_selector_issue)
                grpc_scope_issue = _check_grpc_app_error_scoping(expr)
                if grpc_scope_issue:
                    findings.append(Finding(path=json_path, message=grpc_scope_issue))

                if not _all_metric_selectors_have_label(
                    expr, "redis_coordination_used_memory_bytes", "role"
                ):
                    findings.append(
                        Finding(
                            path=json_path,
                            message="expression references redis_coordination_used_memory_bytes without a role matcher; shared dashboards must scope coordination role explicitly",
                        )
                    )
                if not _all_metric_selectors_have_label(
                    expr, "redis_coordination_keys_total", "role"
                ):
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


def _validate_player_experience_dashboard(path: Path) -> list[Finding]:
    try:
        dashboard = json.loads(_read_text(path))
    except json.JSONDecodeError:
        return []

    findings: list[Finding] = []
    if not isinstance(dashboard, dict):
        return [Finding(path=path, message="Grafana dashboard root must be a JSON object")]
    description = dashboard.get("description")
    if (
        not isinstance(description, str)
        or "calibration" not in description.lower()
        or "non-enforcing" not in description.lower()
    ):
        findings.append(
            Finding(
                path=path,
                message=(
                    "canonical player-experience dashboard must identify its "
                    "views as calibration and non-enforcing until profile promotion"
                ),
            )
        )
    panels = dashboard.get("panels", [])
    if not isinstance(panels, list):
        return findings + [
            Finding(path=path, message="Grafana dashboard panels must be a JSON array")
        ]
    for panel in panels:
        if not isinstance(panel, dict):
            findings.append(
                Finding(path=path, message="Grafana dashboard panels must contain JSON objects")
            )
            continue
        title = panel.get("title") if isinstance(panel, dict) else None
        targets = panel.get("targets", [])
        if not isinstance(targets, list):
            findings.append(
                Finding(path=path, message="Grafana dashboard panel targets must be a JSON array")
            )
            continue
        if (
            isinstance(title, str)
            and re.search(r"\bslo\b", title, re.IGNORECASE)
            and not re.search(r"\bcalibration\b", title, re.IGNORECASE)
        ):
            findings.append(
                Finding(
                    path=path,
                    message=(
                        "canonical player-experience calibration panels must not "
                        "use enforceable SLO wording before profile promotion"
                    ),
                )
            )
        if (
            isinstance(title, str)
            and re.search(r"\bcommand\s+latency\b", title, re.IGNORECASE)
            and re.search(r"\bregion\b", title, re.IGNORECASE)
            and isinstance(panel, dict)
                and any(
                    isinstance(target.get("expr"), str)
                    and "command_end_to_end_latency_ms_bucket" in target["expr"]
                for target in targets
                if isinstance(target, dict)
            )
        ):
            findings.append(
                Finding(
                    path=path,
                    message=(
                        "canonical command latency panels grouped by bounded scope "
                        "must not claim a raw region grouping"
                    ),
                )
            )
        # Apply the bounded-scope contract to every command-latency query,
        # independent of presentation titles. A canonical query can retain a
        # concise panel title without escaping its scope and command checks.
        for target in targets:
            if isinstance(target, dict) and isinstance(
                expr := target.get("expr"), str
            ):
                findings.extend(_command_scope_query_findings(path, expr))

    chat_expressions: list[str] = []
    for panel in panels:
        if not isinstance(panel, dict):
            continue
        targets = panel.get("targets", [])
        if not isinstance(targets, list):
            continue
        for target in targets:
            if not isinstance(target, dict):
                continue
            expr = target.get("expr")
            if isinstance(expr, str) and "chat_delivery_latency_ms_bucket" in expr:
                chat_expressions.append(expr)
    if not chat_expressions:
        findings.append(
            Finding(
                path=path,
                message=(
                    "canonical player-experience dashboard must include a chat "
                    "delivery latency panel"
                ),
            )
        )
        return findings

    for expr in chat_expressions:
        chat_selector_issue = _recipient_dispatch_selector_finding(
            path,
            expr,
            "chat_delivery_latency_ms_bucket",
            "canonical player-experience chat latency panels",
        )
        if chat_selector_issue:
            findings.append(chat_selector_issue)
    return findings


def _validate_player_experience_drilldown(path: Path) -> list[Finding]:
    try:
        dashboard = json.loads(_read_text(path))
    except json.JSONDecodeError:
        return []

    if not isinstance(dashboard, dict):
        return [Finding(path=path, message="Grafana dashboard root must be a JSON object")]
    panels = dashboard.get("panels", [])
    if not isinstance(panels, list):
        return [Finding(path=path, message="Grafana dashboard panels must be a JSON array")]
    findings: list[Finding] = []
    chat_expressions: list[str] = []
    for panel in panels:
        if not isinstance(panel, dict):
            findings.append(
                Finding(path=path, message="Grafana dashboard panels must contain JSON objects")
            )
            continue
        targets = panel.get("targets", [])
        if not isinstance(targets, list):
            findings.append(
                Finding(path=path, message="Grafana dashboard panel targets must be a JSON array")
            )
            continue
        for target in targets:
            if not isinstance(target, dict):
                findings.append(
                    Finding(path=path, message="Grafana dashboard targets must contain JSON objects")
                )
                continue
            expr = target.get("expr")
            if isinstance(expr, str) and "chat_delivery_latency_ms_bucket" in expr:
                chat_expressions.append(expr)
    if not chat_expressions:
        findings.append(
            Finding(
                path=path,
                message=(
                    "player-experience drilldown must include a chat delivery "
                    "latency panel"
                ),
            )
        )
        return findings

    for expr in chat_expressions:
        chat_selector_issue = _recipient_dispatch_selector_finding(
            path,
            expr,
            "chat_delivery_latency_ms_bucket",
            "player-experience drilldown chat latency panels",
        )
        if chat_selector_issue:
            findings.append(chat_selector_issue)
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

        objects = payload if isinstance(payload, list) else [payload]
        candidate_pairs: list[tuple[dict[str, object], dict[str, object]]] = []
        invalid_object_shape = False
        for saved_object in objects:
            if not isinstance(saved_object, dict):
                invalid_object_shape = True
                continue
            if saved_object.get("type") == "index-pattern":
                continue
            if saved_object.get("type") == "dashboard":
                saved_search = saved_object.get("savedSearch")
                if isinstance(saved_search, dict):
                    candidate_pairs.append((saved_search, saved_object))
                else:
                    candidate_pairs.append((saved_object, saved_object))
                continue
            candidate_pairs.append((saved_object, saved_object))

        column_lists: list[object | None] = []
        for candidate, _ in candidate_pairs:
            attributes = candidate.get("attributes")
            column_lists.append(
                attributes.get("columns")
                if isinstance(attributes, dict)
                else None
            )
        if invalid_object_shape or not column_lists:
            column_lists.append(None)
        missing = sorted(
            column
            for columns in column_lists
            for column in expected
            if not isinstance(columns, list) or column not in columns
        )
        if missing:
            findings.append(
                Finding(
                    path=json_path,
                    message=f"Kibana saved object is missing required structured log fields for runbooks: {', '.join(missing)}",
                )
            )
        if json_path.name == "player-incident-drilldown.json":
            player_sources = [
                (candidate, owner)
                for candidate, owner in candidate_pairs
                if isinstance(candidate.get("attributes"), dict)
                and isinstance(
                    candidate["attributes"].get("kibanaSavedObjectMeta"), dict
                )
                and "searchSourceJSON"
                in candidate["attributes"]["kibanaSavedObjectMeta"]
            ]
            if len(player_sources) != 1:
                findings.append(
                    Finding(
                        path=json_path,
                        message=(
                            "player incident Kibana saved object must contain exactly "
                            "one relevant search object"
                        ),
                    )
                )
                continue
            player_payload, references_payload = player_sources[0]
            try:
                search_source = player_payload["attributes"]["kibanaSavedObjectMeta"]["searchSourceJSON"]
                search_source_payload = json.loads(search_source)
                query = search_source_payload["query"]["query"]
                index_ref_name = search_source_payload.get("indexRefName")
            except (KeyError, TypeError, json.JSONDecodeError):
                query = ""
                index_ref_name = None
            if not isinstance(query, str):
                findings.append(
                    Finding(
                        path=json_path,
                        message=(
                            "player incident Kibana saved object query must be a "
                            "string before index/access safety checks"
                        ),
                    )
                )
                query = ""
            if re.search(r"\benvironment\s*:", query, re.IGNORECASE):
                findings.append(
                    Finding(
                        path=json_path,
                        message=(
                            "player incident Kibana saved object must scope environment "
                            "through its index/access boundary rather than a log field predicate"
                        ),
                    )
                )
            query_clauses = _split_kibana_query_clauses(query)
            required_conjunctive_clauses = {
                "service": r"service\s*:\s*\*",
                "traceId": r"traceId\s*:\s*\*",
            }
            if any(
                not any(
                    re.fullmatch(pattern, clause, re.IGNORECASE)
                    for clause in query_clauses
                )
                for pattern in required_conjunctive_clauses.values()
            ):
                findings.append(
                    Finding(
                        path=json_path,
                        message=(
                            "player incident Kibana saved object query must contain "
                            "exact conjunctive service and traceId bounds"
                        ),
                    )
                )
            if re.search(
                r"\bor\b", _mask_kibana_query_strings(query), re.IGNORECASE
            ):
                findings.append(
                    Finding(
                        path=json_path,
                        message=(
                            "player incident Kibana saved object query must keep "
                            "service and traceId clauses conjunctive"
                        ),
                    )
                )

            references = references_payload.get("references")
            index_references = (
                references
                if isinstance(references, list)
                else []
            )
            if (
                len(index_references) != 1
                or not isinstance(index_references[0], dict)
                or index_references[0].get("name") != "searchSourceJSON.index"
                or index_references[0].get("type") != "index-pattern"
            ):
                findings.append(
                    Finding(
                        path=json_path,
                        message=(
                            "player incident Kibana saved object must have exactly "
                            "one searchSourceJSON.index index-pattern reference"
                        ),
                    )
                )
            else:
                index_id = index_references[0].get("id")
                if not isinstance(index_ref_name, str) or not index_ref_name:
                    findings.append(
                        Finding(
                            path=json_path,
                            message=(
                                "player incident Kibana saved object must have a "
                                "non-empty searchSourceJSON.indexRefName"
                            ),
                        )
                    )
                elif index_ref_name != index_references[0].get("name"):
                    findings.append(
                        Finding(
                            path=json_path,
                            message=(
                                "player incident Kibana saved object "
                                "searchSourceJSON.indexRefName must exactly match "
                                "the searchSourceJSON.index reference name"
                            ),
                        )
                    )
                if not isinstance(index_id, str) or not KIBANA_SAFE_LOG_INDEX_PATTERN.fullmatch(index_id):
                    findings.append(
                        Finding(
                            path=json_path,
                            message=(
                                "player incident Kibana saved object index reference "
                                f"must use {KIBANA_ENVIRONMENT_INDEX_SENTINEL} or an explicit "
                                "environment-scoped FireMUD log index"
                            ),
                        )
                    )
                elif index_id == KIBANA_DEFAULT_LOG_INDEX:
                    findings.append(
                        Finding(
                            path=json_path,
                            message=(
                                "player incident Kibana saved object index reference must "
                                "use the __REQUIRED_ENVIRONMENT__ fail-closed sentinel or "
                                "an explicit environment-scoped FireMUD log index"
                            ),
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
            for entry in _split_alert_rules(yaml_block):
                if entry.key is None:
                    findings.append(_unrecognized_rule_entry_finding(core_alerts, "alert", entry))
                    continue
                alert_name = entry.name
                if not alert_name:
                    findings.append(Finding(path=core_alerts, message="alert rule is missing name"))
                    continue
                rule_lines = entry.lines
                labels = _parse_labels(rule_lines)
                expr = _parse_expr(rule_lines) or ""
                findings.extend(
                    _service_scoped_alert_findings(
                        core_alerts, alert_name, labels, expr
                    )
                )
                recovery_coverage_issue = _recovery_coverage_alert_finding(
                    core_alerts, alert_name, expr
                )
                if recovery_coverage_issue:
                    findings.append(recovery_coverage_issue)

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

    runbook_index = REPO_ROOT / "design" / "operations" / "README.md"
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
    if "Telnet/WebSocket Path Availability (1d Calibration)" not in player_dashboard_text:
        findings.append(
            Finding(
                path=player_dashboard,
                message="player SLO dashboard must include a dedicated 1d entry-path availability panel",
            )
        )

    return findings


def _validate_reference_prometheus_rules(
    path: Path,
    required_alerts: set[str] | None = None,
    *,
    allow_profile_dependent_alerts: bool = False,
    installed_in_shared_prometheus_rule: bool = False,
) -> list[Finding]:
    findings: list[Finding] = []
    text = _read_text(path)
    alerts_seen: set[str] = set()
    alert_occurrences: dict[str, int] = {}

    for entry in _split_alert_rules(text):
        if entry.key is None:
            findings.append(_unrecognized_rule_entry_finding(path, "alert", entry))
            continue
        alert_name = entry.name
        if not alert_name:
            findings.append(Finding(path=path, message="alert rule is missing name"))
            continue
        if installed_in_shared_prometheus_rule and alert_name in TARGET_ONLY_INSTALLED_ALERTS:
            findings.append(
                Finding(
                    path=path,
                    message=f"target-only alert {alert_name} must not be installed in the shared PrometheusRule",
                )
            )
        alert_occurrences[alert_name] = alert_occurrences.get(alert_name, 0) + 1
        if (
            alert_name in PROFILE_DEPENDENT_ALERTS
            and not allow_profile_dependent_alerts
        ):
            findings.append(
                Finding(
                    path=path,
                    message=(
                        "base Prometheus rules must not include profile-dependent alert "
                        f"{alert_name}; install it only through the matching profile overlay"
                    ),
                )
            )
        rule_lines = entry.lines
        expr = _parse_expr(rule_lines)
        if not expr:
            findings.append(Finding(path=path, message=f"{alert_name} is missing expr"))
            continue
        alerts_seen.add(alert_name)

        labels = _parse_labels(rule_lines)
        findings.extend(
            _player_slo_calibration_findings(path, alert_name, labels)
        )
        findings.extend(
            _entry_path_blackbox_findings(
                path,
                alert_name,
                labels,
                expr,
                _parse_rule_scalar(rule_lines, "for"),
            )
        )
        if alert_name == "ChatDeliveryLatencyP99High":
            chat_selector_issue = _recipient_dispatch_selector_finding(
                path,
                expr,
                "chat_delivery_latency_ms_p99_5m",
                "shipped ChatDeliveryLatencyP99High alert",
            )
            if chat_selector_issue:
                findings.append(chat_selector_issue)
        findings.extend(
            _playerflow_canary_label_findings(path, alert_name, labels)
        )
        findings.extend(
            _playerflow_canary_service_findings(path, alert_name, labels)
        )
        required_labels = REQUIRED_ALERT_LABELS - (
            {"service"}
            if alert_name in SERVICE_OPTIONAL_ALERTS | SERVICE_DERIVED_ALERTS
            else set()
        )
        missing = sorted(required_labels - labels.keys())
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

        findings.extend(_alert_runbook_findings(path, labels.get("runbook", "")))

        ms_issue = _check_ms_thresholds(expr)
        if ms_issue:
            findings.append(Finding(path=path, message=f"{alert_name}: {ms_issue}"))

        tick_scope_issue = _tick_replay_scope_matching_finding(path, alert_name, expr)
        if tick_scope_issue:
            findings.append(tick_scope_issue)
        tick_ratio_issue = _tick_execution_ratio_finding(
            path, alert_name, expr, require_threshold=True
        )
        if tick_ratio_issue:
            findings.append(tick_ratio_issue)
        tick_selector_issue = _tick_scope_selector_finding(path, expr)
        if tick_selector_issue:
            findings.append(tick_selector_issue)

        grpc_scope_issue = _check_grpc_app_error_scoping(expr)
        if grpc_scope_issue:
            findings.append(Finding(path=path, message=f"{alert_name}: {grpc_scope_issue}"))

        dotted_metric_issue = _check_dotted_metric_tokens(expr)
        if dotted_metric_issue:
            findings.append(Finding(path=path, message=f"{alert_name}: {dotted_metric_issue}"))

        recovery_coverage_issue = _recovery_coverage_alert_finding(path, alert_name, expr)
        if recovery_coverage_issue:
            findings.append(recovery_coverage_issue)

        if alert_name.startswith("Redis") and labels.get("owner") != "infra":
            findings.append(Finding(path=path, message=f"{alert_name} must use owner=infra for Redis/coordination incidents"))
        if alert_name.startswith("Backup") and labels.get("owner") != "infra":
            findings.append(Finding(path=path, message=f"{alert_name} must use owner=infra for backup incidents"))
        if alert_name.startswith("Recovery") and labels.get("owner") != "infra":
            findings.append(Finding(path=path, message=f"{alert_name} must use owner=infra for recovery incidents"))
        absent_metric = REQUIRED_ABSENT_ALERT_METRICS.get(alert_name)
        if absent_metric:
            absent_expr = re.compile(rf"absent\s*\(\s*{re.escape(absent_metric)}\s*\)")
            if absent_expr.fullmatch(_mask_promql_non_code(expr or "").strip()) is None:
                findings.append(Finding(path=path, message=f"{alert_name} must use absent({absent_metric})"))
        if alert_name == "RecoveryReopenAttemptBlocked":
            if BLOCKED_REOPEN_ATTEMPT_EXPR.fullmatch(_mask_promql_comments(expr or "").strip()) is None:
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
            "TickEffectsReplaySloBreached",
            "TickCleanupLagHigh",
            "TickEffectsReplayStarved",
            "TickReplayScanLagHigh",
        }:
            findings.append(Finding(path=path, message=f"unexpected tick alert name {alert_name!r} in reference rules; update validator contract if intentional"))

    if required_alerts is None:
        required_alerts = {
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
            "RecoveryParticipantConvergenceCoverageMissing",
            "RecoveryParticipantConvergenceMetricsAbsent",
            "RecoveryReopenAttemptBlocked",
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

    if allow_profile_dependent_alerts:
        duplicate_profile_alerts = sorted(
            alert_name
            for alert_name in ENTRY_PATH_BLACKBOX_ALERT_CONTRACTS
            if alert_occurrences.get(alert_name, 0) > 1
        )
        if duplicate_profile_alerts:
            findings.append(
                Finding(
                    path=path,
                    message=(
                        "profile-dependent entry-path blackbox alerts must appear exactly once; "
                        "duplicate declarations: "
                        + ", ".join(duplicate_profile_alerts)
                    ),
                )
            )

    if "LoginSuccessRatioLow" in alerts_seen:
        findings.append(Finding(path=path, message="reference rules must not include legacy LoginSuccessRatioLow; use split ingress alerts"))
    if "CommandLatencyP99High" in alerts_seen:
        findings.append(Finding(path=path, message="reference rules must not include legacy CommandLatencyP99High; use split ingress alerts"))

    return findings


def _validate_reference_prometheus_recordings(
    path: Path, *, installed_in_shared_prometheus_rule: bool = False
) -> list[Finding]:
    text = _read_text(path)
    findings: list[Finding] = []
    recording_occurrences: dict[str, list[str | None]] = {}
    for entry in _split_recording_rules(text):
        if entry.key is None:
            findings.append(_unrecognized_rule_entry_finding(path, "record", entry))
            continue
        recording = entry.name
        if not recording:
            findings.append(Finding(path=path, message="recording rule is missing name"))
            continue
        if installed_in_shared_prometheus_rule and recording in TARGET_ONLY_INSTALLED_RECORDINGS:
            findings.append(
                Finding(
                    path=path,
                    message=(
                        f"target-only recording {recording} must not be installed in the shared PrometheusRule"
                    ),
                )
            )
        expression = _parse_expr(entry.lines)
        recording_occurrences.setdefault(recording, []).append(expression)
        if expression:
            tick_selector_issue = _tick_scope_selector_finding(path, expression)
            if tick_selector_issue:
                findings.append(tick_selector_issue)

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
    chat_recording_name = "chat_delivery_latency_ms_p99_5m"
    chat_recording_expressions = recording_occurrences.get(chat_recording_name, [])
    # The player-experience recording family is target-only until its
    # producers are implemented. If a deployment supplies the optional
    # recording, validate its canonical shape; absence is valid here.
    if len(chat_recording_expressions) > 1:
        findings.append(
            Finding(
                path=path,
                message=(
                    "canonical chat delivery recording rule must be declared "
                    f"at most once: {chat_recording_name}"
                ),
            )
        )
    elif chat_recording_expressions and not chat_recording_expressions[0]:
        findings.append(
            Finding(
                path=path,
                message=(
                    "canonical chat delivery recording rule is missing expr: "
                    f"{chat_recording_name}"
                ),
            )
        )
    elif chat_recording_expressions:
        chat_selector_issue = _recipient_dispatch_selector_finding(
            path,
            chat_recording_expressions[0],
            "chat_delivery_latency_ms_bucket",
            "canonical chat delivery recording rule",
        )
        if chat_selector_issue:
            findings.append(chat_selector_issue)
        chat_grouping_issue = _chat_recording_grouping_finding(
            path, chat_recording_expressions[0]
        )
        if chat_grouping_issue:
            findings.append(chat_grouping_issue)
    blocked_convergence_expr = recordings.get("recovery_participant_convergence_blocked") or ""
    if _compact_promql(blocked_convergence_expr) != CURRENT_BLOCKED_CONVERGENCE_EXPR:
        findings.append(
            Finding(
                path=path,
                message=(
                    "blocked convergence recording must combine current blocked participant state under a complete "
                    "inventory with fail-closed coverage-missing state"
                ),
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

    participant_coverage_expr = recordings.get("recovery_participant_convergence_coverage_missing") or ""
    if _compact_promql(participant_coverage_expr) != PARTICIPANT_COVERAGE_EXPR:
        findings.append(
            Finding(
                path=path,
                message=(
                    "participant coverage recording must compare authoritative required-participant inventory "
                    "with the current participant coverage projection while preserving environment scope"
                ),
            )
        )
    participant_source_missing_expr = recordings.get("recovery_participant_convergence_source_missing") or ""
    if _compact_promql(participant_source_missing_expr) != PARTICIPANT_SOURCE_MISSING_EXPR:
        findings.append(
            Finding(
                path=path,
                message=(
                    "participant source-missing recording must report globally absent inventory and coverage families "
                    "with a stable source_family label"
                ),
            )
        )

    restore_drill_expr = recordings.get("backup_pipeline_recent_restore_drill_slo_breached") or ""
    if RESTORE_DRILL_30_DAY_EXPR.fullmatch(_mask_promql_non_code(restore_drill_expr).strip()) is None:
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
    for alert_snippet in ALERT_SNIPPET_PATHS:
        findings.extend(_validate_alert_snippet(alert_snippet))
    findings.extend(_validate_grafana_dashboards(grafana_dir))
    findings.extend(
        _validate_player_experience_dashboard(
            grafana_dir / "player-experience.json"
        )
    )
    findings.extend(
        _validate_player_experience_drilldown(
            grafana_dir / "player-experience-drilldown.json"
        )
    )
    findings.extend(_validate_kibana_saved_objects(REPO_ROOT / "design" / "observability" / "kibana"))
    findings.extend(_validate_doc_semantics())
    prometheus_rules = REPO_ROOT / "k8s" / "monitoring" / "prometheus-rules-firemud.yaml"
    findings.extend(
        _validate_reference_prometheus_recordings(
            prometheus_rules, installed_in_shared_prometheus_rule=True
        )
    )
    findings.extend(
        _validate_reference_prometheus_rules(
            prometheus_rules, installed_in_shared_prometheus_rule=True
        )
    )
    independent_required_rules = (
        REPO_ROOT
        / "k8s"
        / "overlays"
        / "monitoring"
        / "independent-required-prometheus-published"
        / "prometheus-rules-firemud-independent-required.yaml"
    )
    if not independent_required_rules.exists():
        findings.append(
            Finding(
                path=independent_required_rules,
                message=(
                    "profile overlay rules file is missing; the independent-required "
                    "deadman alert cannot be installed"
                ),
            )
        )
    else:
        findings.extend(
            _validate_reference_prometheus_rules(
                independent_required_rules,
                {
                    "ObservabilityDeadmanHeartbeatMissing",
                    "ObservabilityDeadmanHeartbeatStale",
                },
                allow_profile_dependent_alerts=True,
                installed_in_shared_prometheus_rule=False,
            )
        )

    if not findings:
        print("Observability contract validation: OK")
        return 0

    print("Observability contract validation: FAILED")
    for finding in findings:
        print(f"- {finding.path}: {finding.message}")
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
