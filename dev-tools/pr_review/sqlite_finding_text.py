"""Shared bounded text projections for provider finding observations."""

from __future__ import annotations

import html
import re
from typing import Any

from .sqlite_review_records import _redact_archive_text

_BADGE_LABELS = {
    "bug",
    "bugs",
    "category",
    "resolved",
    "fixed",
    "status",
    "open",
    "accepted",
    "rejected",
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
    "trivial",
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


_HTML_TAG = re.compile(r"</?[a-zA-Z][a-zA-Z0-9]*(?:\s+[^<>]*)?\s*/?>")
_EVIDENCE_LABEL = re.compile(
    r"^(?:(?:supported by static analysis|script executed|analysis results?|"
    r"committable suggestion|script output|analysis chain|suggested fix|prompt for ai agents|ai (?:agent )?prompt)\s*:?|"
    r"repository\s*:\s*`?[a-zA-Z0-9_.-]+/[a-zA-Z0-9_.-]+`?|"
    r"length of output\s*:\s*[0-9]+)\s*$", re.IGNORECASE
)


def _is_evidence_label(value: str) -> bool:
    candidate = _bold_line_content(value.strip()) or value.strip().strip("_*")
    candidate = re.sub(r"^[^a-zA-Z0-9]+", "", candidate)
    return bool(_EVIDENCE_LABEL.fullmatch(candidate))


def _headline_text(line: str) -> str:
    # Inline code remains literal, including generic types and comparisons.
    parts = re.split(r"(`+[^`]*`+)", line)
    return "".join(part if part.startswith("`") else _HTML_TAG.sub("", part) for part in parts).strip()


def _unusable_hosted_title(value: Any) -> bool:
    """Recognize wrapper/metadata titles eligible for an optional display projection."""

    if not isinstance(value, str) or not value.strip():
        return True
    text = _headline_text(html.unescape(value)).strip()
    return not text or _is_badge_line(text) or _is_evidence_label(text) or bool(re.fullmatch(r"[\s#>*_~`-]+", text))


def _strip_badge_prefix(line: str) -> str:
    for pattern in (
        r"^_[^\n]*?_(?:\s*\|\s*_[^\n]*?_){2,3}",
        r"^(?:\*\*[^\n]*?\*\*|__[^\n]*?__)",
    ):
        match = re.match(pattern, line)
        if match and _is_badge_line(match.group()):
            return line[match.end():].strip()
    return line


def _hosted_issue_markdown(value: str, *, keep_badges: bool = False) -> str:
    """Remove provider presentation noise while preserving issue Markdown."""

    from .sqlite_hosted_capture import _markdown_fenced_ranges

    original_fences = _markdown_fenced_ranges(value)
    # An orphan/truncated provider prompt summary still owns the remaining tail.
    for match in re.finditer(r"<summary\b[^>]*>(.*?)</summary>", value, re.DOTALL | re.IGNORECASE):
        if any(start <= match.start() < end for start, end in original_fences):
            continue
        summary = _headline_text(match.group(1))
        if re.search(r"prompt for ai agents|ai (?:agent )?prompt", summary, re.IGNORECASE):
            value = value[:match.start()]
            break
    # Protect ordinary samples before any HTML/metadata cleanup. Diagnostic
    # details still discard their entire block, including protected samples.
    protected = {}
    marker = "\x00firemud-fence:"
    while marker in value:
        marker += ":"
    for index, (start, end) in reversed(list(enumerate(_markdown_fenced_ranges(value)))):
        preceding = value[:start].rstrip().splitlines()
        if preceding and _is_evidence_label(_headline_text(preceding[-1])):
            replacement = "\n"
        else:
            token = f"{marker}{index}\x00"
            protected[token] = value[start:end].rstrip("\r\n")
            replacement = token + "\n"
        value = value[:start] + replacement + value[end:]
    security_metadata = all(label in value for label in ("**Reachability:**", "**Exploitability:**", "**CWE:**"))
    value = re.sub(r"<!--.*?(?:-->|\Z)", "", value, flags=re.DOTALL)
    value = re.sub(r"<(script|style)\b[^>]*>.*?</\1>", "", value, flags=re.DOTALL | re.IGNORECASE)
    value = re.sub(
        r"<details\b[^>]*>\s*<summary\b[^>]*>(.*?)</summary>(.*?)</details>",
        lambda match: "" if _is_evidence_label(_headline_text(match.group(1)))
        else match.group(1) + "\n" + match.group(2),
        value,
        flags=re.DOTALL | re.IGNORECASE,
    )
    lines = []
    for raw in value.splitlines():
        line = _headline_text(html.unescape(raw.rstrip()))
        if not keep_badges:
            line = _strip_badge_prefix(line)
        if line and ((_is_badge_line(line) and not keep_badges) or _is_evidence_label(line)):
            continue
        if security_metadata and (
            line.strip() == "**Broken Authentication**"
            or re.match(r"^\*\*(?:Reachability|Exploitability|CWE):\*\*", line.strip())
        ):
            continue
        if line.strip() in {"---", "***", "___"}:
            continue
        if line or not lines or lines[-1]:
            lines.append(line)
    value = "\n".join(lines).strip()
    for token, sample in protected.items():
        value = value.replace(token, sample)
    return value


def _first_line(value: Any) -> str | None:
    """Choose a Hosted issue headline outside provider wrappers and executed scripts."""

    if not isinstance(value, str):
        return None
    from .sqlite_hosted_capture import _markdown_fenced_ranges

    value = _hosted_issue_markdown(value)
    for start, end in reversed(_markdown_fenced_ranges(value)):
        value = value[:start] + "\n" + value[end:]
    lines = []
    for raw in value.splitlines():
        line = re.sub(r"^(?:>\s*)+", "", raw.strip()).strip()
        if line and not _unusable_hosted_title(line):
            lines.append(line)
    for line in lines:
        content = _bold_line_content(line)
        if content is not None and not _unusable_hosted_title(content):
            return content
    for line in lines:
        match = re.match(r"^(?:\*\*(.+?)\*\*|__(.+?)__)(?:\s|$)", line)
        if match:
            content = match.group(1) or match.group(2)
            if not _unusable_hosted_title(content):
                return content
    return lines[0] if lines else None


def _hosted_display_detail(value: str, title: str) -> str:
    """Expose readable Markdown from archived Hosted prose, never overwrite detail."""

    from .sqlite_hosted_capture import _markdown_fenced_ranges

    value = _hosted_issue_markdown(value)
    lines = value.splitlines()
    fenced_ranges = _markdown_fenced_ranges(value)
    offset = 0
    for index, line in enumerate(value.splitlines(keepends=True)):
        in_fence = any(start <= offset < end for start, end in fenced_ranges)
        offset += len(line)
        if in_fence:
            continue
        candidate = re.sub(r"^(?:>\s*)+", "", line.strip()).strip()
        content = _bold_line_content(candidate) or re.sub(r"^#{1,6}\s*", "", candidate)
        if content == title:
            lines.pop(index)
            break
        match = re.match(r"^(?:\*\*(.+?)\*\*|__(.+?)__)(?:\s|$)", candidate)
        if match and (match.group(1) or match.group(2)) == title:
            lines[index] = candidate[match.end():].lstrip()
            break
    value = "\n".join(lines).strip()
    value, _ = _redact_archive_text(value)
    return value[:8000].rstrip()


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
    if len(sections) in {3, 4} and all(
        section.startswith("_") and section.endswith("_") for section in sections
    ):
        severity_words = re.findall(r"[a-z0-9]+", sections[-2].casefold())
        effort_words = set(re.findall(r"[a-z0-9]+", sections[-1].casefold()))
        severity_labels = {"critical", "high", "major", "medium", "minor", "low", "trivial", "p0", "p1", "p2", "p3"}
        effort_labels = ({"quick", "win"}, {"heavy", "lift"})
        known_tier = len(sections) == 3 or set(re.findall(r"[a-z]+", sections[1].casefold())) == {
            "detected", "with", "advanced", "tier"
        }
        if known_tier and any(word in severity_labels for word in severity_words) and any(
            label == effort_words for label in effort_labels
        ):
            return True
    for delimiter in ("**", "__"):
        if candidate.startswith(delimiter) and candidate.endswith(delimiter) and len(candidate) > 4:
            candidate = candidate[2:-2]
            break
    words = re.findall(r"[a-z0-9]+", candidate.casefold())
    return bool(words) and all(word in _BADGE_LABELS for word in words)


def _hosted_display_severity(value: str) -> str | None:
    """Read explicit provider badge labels, never narrative or sample severity."""

    from .sqlite_hosted_capture import _markdown_fenced_ranges

    value = _hosted_issue_markdown(value, keep_badges=True)
    for start, end in reversed(_markdown_fenced_ranges(value)):
        value = value[:start] + "\n" + value[end:]
    labels = {label.casefold(): label for label in
              ("Critical", "Major", "Minor", "Trivial", "High", "Medium", "Low", "P0", "P1", "P2", "P3")}
    found = set()
    for raw in value.splitlines():
        line = re.sub(r"^(?:>\s*)+", "", raw.strip()).strip()
        # Provider category | severity | effort badge, including inline prose.
        match = re.match(r"^_[^\n]*?_(?:\s*\|\s*_[^\n]*?_){2,3}", line)
        badge = match.group().split("|")[-2] if match and _is_badge_line(match.group()) else None
        if badge is None:
            match = re.match(r"^(?:\*\*([^\n]*?)\*\*|__([^\n]*?)__)", line)
            if match and _is_badge_line(match.group()):
                badge = match.group(1) or match.group(2)
        if badge is not None:
            words = re.findall(r"[a-z0-9]+", badge.casefold())
            found.update(labels[word] for word in words if word in labels)
    return next(iter(found)) if len(found) == 1 else None
