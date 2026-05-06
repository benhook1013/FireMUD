#!/usr/bin/env python3
"""Validate time-related proto field names use explicit domains or units."""

from __future__ import annotations

import re
import sys
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[2]
PROTO_ROOT = REPO_ROOT / "protos"

FIELD_RE = re.compile(
    r"^\s*(?:optional\s+|repeated\s+)?(?:[A-Za-z_][\w.]*)\s+([a-z][a-z0-9_]*)\s*=\s*\d+\s*(?:\[.*\])?\s*;"
)

AMBIGUOUS_EXACT = {
    "time",
    "timeout",
    "expires",
    "expiry",
    "duration",
    "cooldown",
}

AMBIGUOUS_SUFFIXES = (
    "_time",
    "_timeout",
    "_expires",
    "_expiry",
    "_duration",
    "_cooldown",
)

ALLOWED_EXPLICIT_SUFFIXES = (
    "_at",
    "_at_ms",
    "_ms",
    "_seconds",
    "_ticks",
    "_tick",
)

ALLOWED_NON_TEMPORAL_SUBSTRINGS = (
    "runtime",
)


def strip_line_comment(line: str) -> str:
    return line.split("//", 1)[0]


def is_time_related(field_name: str) -> bool:
    if any(token in field_name for token in ALLOWED_NON_TEMPORAL_SUBSTRINGS):
        return False
    return (
        field_name in AMBIGUOUS_EXACT
        or field_name.endswith(AMBIGUOUS_SUFFIXES)
        or any(token in field_name for token in ("expires", "expiry", "timeout", "duration", "cooldown"))
    )


def is_explicit(field_name: str) -> bool:
    return field_name.endswith(ALLOWED_EXPLICIT_SUFFIXES)


def validate_file(path: Path) -> list[str]:
    findings: list[str] = []
    for line_number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        match = FIELD_RE.match(strip_line_comment(line))
        if not match:
            continue
        field_name = match.group(1)
        if is_time_related(field_name) and not is_explicit(field_name):
            findings.append(
                f"{path.relative_to(REPO_ROOT)}:{line_number}: "
                f"time-related proto field '{field_name}' must declare its domain/unit "
                "with a suffix such as '_at', '_ms', '_seconds', '_tick', or '_ticks'"
            )
    return findings


def main() -> int:
    findings: list[str] = []
    for path in sorted(PROTO_ROOT.rglob("*.proto")):
        findings.extend(validate_file(path))
    if findings:
        print("Proto time-field contract violations:", file=sys.stderr)
        for finding in findings:
            print(f"  - {finding}", file=sys.stderr)
        return 1
    print("Proto time-field contract passed.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
