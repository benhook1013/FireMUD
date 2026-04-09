#!/usr/bin/env python3
from __future__ import annotations

import re
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
SOURCE_ROOT = REPO_ROOT / "services"

FORBIDDEN_LABELS = {
    "tenantId",
    "accountId",
    "characterId",
    "sessionId",
    "gameInstanceId",
    "roomInstanceId",
    "script_patch_version",
    "scriptPatchVersion",
    "patchVersion",
    "targetId",
    "queueTargetId",
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

FORBIDDEN_LITERAL_PATTERN = re.compile(
    r'"(?P<label>'
    + "|".join(re.escape(label) for label in sorted(FORBIDDEN_LABELS))
    + r')"'
)


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
            match = FORBIDDEN_LITERAL_PATTERN.search(line)
            if not match:
                continue
            start = max(0, index - 3)
            end = min(len(lines), index + 4)
            window = "\n".join(lines[start:end])
            if not any(anchor in window for anchor in METRIC_ANCHORS):
                continue
            findings.append(
                f"{path.relative_to(REPO_ROOT)}:{index + 1}: forbidden high-cardinality metric label "
                f"{match.group('label')!r}"
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
