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


@dataclass(frozen=True)
class Finding:
    path: Path
    message: str


def _read_text(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def _extract_fenced_blocks(markdown: str, language: str) -> list[str]:
    pattern = re.compile(rf"```{re.escape(language)}\n(.*?)\n```", re.DOTALL)
    return [match.group(1) for match in pattern.finditer(markdown)]


def _split_alert_rules(yaml_text: str) -> list[list[str]]:
    lines = yaml_text.splitlines()
    rule_starts: list[int] = []
    for index, line in enumerate(lines):
        if re.match(r"^\s*-\s*alert:\s*\S+", line):
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
        expr_lines = [match.group("rest").rstrip()]
        for next_line in rule_lines[index + 1 :]:
            next_indent = len(next_line) - len(next_line.lstrip(" "))
            if next_line.strip() == "":
                expr_lines.append("")
                continue
            if next_indent <= expr_indent and re.match(r"^\s*(for|labels|annotations):\s*", next_line):
                break
            if next_indent <= expr_indent and re.match(r"^\s*-\s*alert:\s*", next_line):
                break
            expr_lines.append(next_line.rstrip())
        return "\n".join(expr_lines).strip()
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
    matcher = re.search(r"grpc_app_error(\{[^}]*\})", expr)
    if not matcher:
        return "expression references grpc_app_error without a `{...}` matcher; shared dashboards/snippets must scope by `service`"
    if "service=" not in matcher.group(1):
        return "expression references grpc_app_error without a `service=...` matcher; shared dashboards/snippets must scope by `service`"
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

            runbook = labels.get("runbook", "")
            if not runbook.startswith("design/") or ".md" not in runbook or "#" not in runbook:
                findings.append(Finding(path=path, message=f"alert rule runbook label must be a design doc anchor (design/...md#section); got {runbook!r}"))

            alert_class = labels.get("alert_class")
            if alert_class == "test" and severity != "P2":
                findings.append(Finding(path=path, message="test alerts must use severity=P2 and alert_class=test (never severity=test)"))

            expr = _parse_expr(rule_lines)
            if expr is None:
                findings.append(Finding(path=path, message="alert rule is missing expr"))
                continue

            ms_issue = _check_ms_thresholds(expr)
            if ms_issue:
                findings.append(Finding(path=path, message=ms_issue))

            grpc_scope_issue = _check_grpc_app_error_scoping(expr)
            if grpc_scope_issue:
                findings.append(Finding(path=path, message=grpc_scope_issue))
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
    return findings


def main() -> int:
    findings: list[Finding] = []

    grafana_dir = REPO_ROOT / "design" / "observability" / "grafana"
    findings.extend(_validate_alert_snippet(grafana_dir / "core-alerts-snippets.md"))
    findings.extend(_validate_alert_snippet(grafana_dir / "tcp-proxy-alerts-snippets.md"))
    findings.extend(_validate_grafana_dashboards(grafana_dir))

    if not findings:
        print("Observability contract validation: OK")
        return 0

    print("Observability contract validation: FAILED")
    for finding in findings:
        print(f"- {finding.path}: {finding.message}")
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
