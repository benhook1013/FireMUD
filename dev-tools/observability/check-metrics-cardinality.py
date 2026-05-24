#!/usr/bin/env python3
from __future__ import annotations

import re
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
SOURCE_ROOT = REPO_ROOT / "services"
DOC_POLICY_FILES = [
    REPO_ROOT / "design/architecture/system-architecture-logging-monitoring.md",
    REPO_ROOT / "design/architecture/system-architecture-redis-metrics-catalog.md",
    REPO_ROOT / "design/architecture/system-architecture-scripting-observability-contract.md",
    REPO_ROOT / "design/architecture/system-architecture-scripting-normative-contract-tables.md",
    REPO_ROOT / "design/architecture/system-architecture-scripting-quotas-and-operations.md",
    REPO_ROOT / "design/architecture/microservices/game-session-service/runtime-and-data.md",
    REPO_ROOT / "k8s/monitoring/prometheus-rules-firemud.yaml",
]

FORBIDDEN_EXACT_LABELS = {
    "class",
    "characterId",
    "componentId",
    "entityId",
    "gameInstanceId",
    "pluginId",
    "pluginVersionId",
    "regionId",
    "scriptId",
    "script_patch_version",
    "scriptPatchVersion",
    "serviceInstanceId",
    "sessionId",
    "spanId",
    "patchVersion",
    "tenantId",
    "traceId",
}

METRIC_ANCHORS = (
    "meterRegistry.",
    "Counter.builder(",
    "Gauge.builder(",
    "Timer.builder(",
    "DistributionSummary.builder(",
    ".tag(",
    ".tags(",
)

IGNORE_MARKER = "metrics-cardinality: allow"

FORBIDDEN_EXACT_LITERAL_PATTERN = re.compile(
    r'"(?P<label>'
    + "|".join(re.escape(label) for label in sorted(FORBIDDEN_EXACT_LABELS))
    + r')"'
)
LITERAL_LABEL_PATTERN = re.compile(r'"(?P<label>[A-Za-z_][A-Za-z0-9_]*)"')
DOC_METRIC_LABEL_PATTERN = re.compile(
    r"\b[a-zA-Z_:][a-zA-Z0-9_:]*\{[^}\n]*\b(?P<label>"
    + "|".join(re.escape(label) for label in sorted(FORBIDDEN_EXACT_LABELS))
    + r")\b[^}\n]*\}"
)
PROMQL_GROUPING_PATTERN = re.compile(
    r"\b(?:sum|avg|min|max|count|stddev|stdvar|group|count_values|quantile|topk|bottomk)\s+by\s*\((?P<group_labels>[^)\n]+)\)"
    r"|\bon\s*\((?P<join_labels>[^)\n]+)\)"
)


def is_forbidden_label(label: str) -> bool:
    if label in FORBIDDEN_EXACT_LABELS:
        return True
    if label.endswith("Id"):
        return True
    if label.lower().endswith("_id"):
        return True
    return False


def iter_source_files() -> list[Path]:
    return sorted(
        path
        for path in SOURCE_ROOT.rglob("*")
        if path.suffix in {".java", ".kt"} and "build" not in path.parts
    )


def iter_doc_policy_files() -> list[Path]:
    return [path for path in DOC_POLICY_FILES if path.exists()]


def forbidden_grouping_labels(labels: str) -> list[str]:
    findings: list[str] = []
    for raw_label in labels.split(","):
        label = raw_label.strip()
        if label and label in FORBIDDEN_EXACT_LABELS:
            findings.append(label)
    return findings


def main() -> int:
    findings: list[str] = []
    for path in iter_source_files():
        lines = path.read_text(encoding="utf-8").splitlines()
        for index, line in enumerate(lines):
            if IGNORE_MARKER in line:
                continue
            start = max(0, index - 3)
            end = min(len(lines), index + 4)
            window = "\n".join(lines[start:end])
            if not any(anchor in window for anchor in METRIC_ANCHORS):
                continue
            exact_match = FORBIDDEN_EXACT_LITERAL_PATTERN.search(line)
            if exact_match:
                findings.append(
                    f"{path.relative_to(REPO_ROOT)}:{index + 1}: forbidden high-cardinality metric label "
                    f"{exact_match.group('label')!r}"
                )
                continue
            for literal_match in LITERAL_LABEL_PATTERN.finditer(line):
                label = literal_match.group("label")
                if not is_forbidden_label(label):
                    continue
                findings.append(
                    f"{path.relative_to(REPO_ROOT)}:{index + 1}: forbidden high-cardinality metric label "
                    f"{label!r}"
                )
                break
    for path in iter_doc_policy_files():
        lines = path.read_text(encoding="utf-8").splitlines()
        for index, line in enumerate(lines):
            match = DOC_METRIC_LABEL_PATTERN.search(line)
            if match is not None:
                findings.append(
                    f"{path.relative_to(REPO_ROOT)}:{index + 1}: canonical observability docs still teach forbidden raw metric label "
                    f"{match.group('label')!r}"
                )
            for grouping_match in PROMQL_GROUPING_PATTERN.finditer(line):
                labels = grouping_match.group("group_labels") or grouping_match.group(
                    "join_labels"
                )
                if labels is None:
                    continue
                forbidden = forbidden_grouping_labels(labels)
                if not forbidden:
                    continue
                findings.append(
                    f"{path.relative_to(REPO_ROOT)}:{index + 1}: canonical observability rules still group or join on forbidden raw metric labels "
                    + ", ".join(repr(label) for label in forbidden)
                )
    if findings:
        print("Metrics cardinality policy violations found:", file=sys.stderr)
        for finding in findings:
            print(f"  - {finding}", file=sys.stderr)
        return 1
    print("Metrics cardinality policy check passed.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
