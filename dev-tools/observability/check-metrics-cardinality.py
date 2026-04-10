#!/usr/bin/env python3
from __future__ import annotations

import re
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
SOURCE_ROOT = REPO_ROOT / "services"

FORBIDDEN_EXACT_LABELS = {
    "class",
    "script_patch_version",
    "scriptPatchVersion",
    "patchVersion",
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
    if findings:
        print("Metrics cardinality policy violations found:", file=sys.stderr)
        for finding in findings:
            print(f"  - {finding}", file=sys.stderr)
        return 1
    print("Metrics cardinality policy check passed.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
