"""Shared bounded text projections for provider finding observations."""

from __future__ import annotations

import re
from typing import Any

from .sqlite_review_records import _redact_archive_text

_BADGE_LABELS = {
    "bug",
    "bugs",
    "category",
    "correctness",
    "critical",
    "high",
    "info",
    "informational",
    "issue",
    "issues",
    "low",
    "major",
    "medium",
    "minor",
    "nit",
    "nits",
    "other",
    "p0",
    "p1",
    "p2",
    "p3",
    "performance",
    "potential",
    "question",
    "reliability",
    "security",
    "severity",
    "style",
    "suggestion",
    "suggestions",
    "test",
    "testing",
    "tests",
}

_CSI_COLOR_SEQUENCE = re.compile(r"(?:\x1b\[|\x9b)[0-?]*[ -/]*m")


def _safe_finding_detail(value: Any) -> str:
    """Keep a readable, secret-scrubbed detail excerpt within the public limit."""

    if not isinstance(value, str):
        return ""
    without_color = _CSI_COLOR_SEQUENCE.sub("", value)
    normalized = " ".join(re.sub(r"[\x00-\x1f\x7f-\x9f]", " ", without_color).split())
    redacted, _ = _redact_archive_text(normalized)
    return redacted[:1000].rstrip()


def _first_line(value: Any) -> str | None:
    """Choose a provider finding headline while skipping severity badges."""

    if not isinstance(value, str):
        return None
    lines = [line.strip() for line in value.splitlines() if line.strip()]
    non_badge_lines = [line for line in lines if not _is_badge_line(line)]
    for line in non_badge_lines:
        content = _bold_line_content(line)
        if content is not None and not _is_badge_line(content):
            return content
    return non_badge_lines[0] if non_badge_lines else None


def _bold_line_content(line: str) -> str | None:
    """Return a whole-line Markdown bold headline without its delimiters."""

    candidate = re.sub(r"^#{1,6}\s*", "", line).strip()
    for delimiter in ("**", "__"):
        if candidate.startswith(delimiter) and candidate.endswith(delimiter) and len(candidate) > 4:
            return candidate[2:-2].strip()
    return None


def _is_badge_line(line: str) -> bool:
    """Identify severity/category-only lines so they cannot become a title."""

    candidate = re.sub(r"^#{1,6}\s*", "", line).strip()
    sections = [section.strip() for section in candidate.split("|")]
    if len(sections) == 3 and all(
        section.startswith("_") and section.endswith("_") for section in sections
    ):
        severity_words = re.findall(r"[a-z0-9]+", sections[1].casefold())
        effort_words = set(re.findall(r"[a-z0-9]+", sections[2].casefold()))
        severity_labels = {"critical", "high", "major", "medium", "minor", "p0", "p1", "p2", "p3"}
        effort_labels = ({"quick", "win"}, {"heavy", "lift"})
        if any(word in severity_labels for word in severity_words) and any(
            label <= effort_words for label in effort_labels
        ):
            return True
    for delimiter in ("**", "__"):
        if candidate.startswith(delimiter) and candidate.endswith(delimiter) and len(candidate) > 4:
            candidate = candidate[2:-2]
            break
    words = re.findall(r"[a-z0-9]+", candidate.casefold())
    return bool(words) and all(word in _BADGE_LABELS for word in words)
