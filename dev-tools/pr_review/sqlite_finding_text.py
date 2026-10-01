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
        r"^_[^\n]*?_(?:\s*\|\s*_[^\n]*?_){1,3}",
        r"^(?:\*\*[^\n]*?\*\*|__[^\n]*?__)",
    ):
        match = re.match(pattern, line)
        if match and _is_badge_line(match.group()):
            return line[match.end():].strip()
    return line


def _hosted_issue_markdown(
    value: str, *, keep_badges: bool = False, classification_titles: set[str] | None = None
) -> str:
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
    value = re.sub(r"<!--.*?(?:-->|\Z)", "", value, flags=re.DOTALL)
    value = re.sub(r"<(script|style)\b[^>]*>.*?</\1>", "", value, flags=re.DOTALL | re.IGNORECASE)
    value = re.sub(
        r"<details\b[^>]*>\s*<summary\b[^>]*>(.*?)</summary>(.*?)</details>",
        lambda match: "" if (_is_evidence_label(_headline_text(match.group(1)))
                              or re.fullmatch(r"[^a-zA-Z0-9]*Tools\s*", _headline_text(match.group(1))))
        else match.group(1) + "\n" + match.group(2),
        value,
        flags=re.DOTALL | re.IGNORECASE,
    )
    # Security taxonomy is metadata only when the provider header and an
    # adjacent exploitability/CWE block prove that a later authored remediation exists.
    value = "\n".join(
        line for line in value.splitlines()
        if not _is_evidence_label(_headline_text(html.unescape(line)))
        and line.strip() not in {"---", "***", "___"}
    )
    pattern = re.compile(
        r"^([^\n]+)\n[ \t\n]*\*\*([^\n*]+)\*\*[ \t]*\n[ \t\n]*"
        r"((?:\*\*(?:Reachability|Exploitability|CWE):\*\*[^\n]*\n[ \t\n]*)+)"
        r"(?=\*\*[^\n*]+\*\*(?:[ \t]|$))", re.MULTILINE
    )
    def strip_classification(match: re.Match[str]) -> str:
        header, classification, metadata = match.groups()
        if (
            not _is_badge_line(header) or len(header.split("|")) not in {3, 4}
            or _badge_label_text(header.split("|")[0]).casefold() != "security & privacy"
            or "**Exploitability:**" not in metadata
            or not re.search(r"\*\*CWE:\*\*.*CWE-[0-9]+", metadata)
        ):
            return match.group()
        if classification_titles is not None:
            classification_titles.add(classification)
        return header + "\n"
    value = pattern.sub(strip_classification, value)
    lines = []
    for raw in value.splitlines():
        line = _headline_text(html.unescape(raw.rstrip()))
        if not keep_badges:
            line = _strip_badge_prefix(line)
        if line and ((_is_badge_line(line) and not keep_badges) or _is_evidence_label(line)):
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
    return _hosted_title_choice(value)[0]


def _hosted_title_choice(value: Any) -> tuple[str | None, bool]:
    """Select canonical title text and whether it is unheaded body prose."""

    if not isinstance(value, str):
        return None, False
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
            return content, False
    for line in lines:
        match = re.match(r"^(?:\*\*(.+?)\*\*|__(.+?)__)(?:\s|$)", line)
        if match:
            content = match.group(1) or match.group(2)
            if not _unusable_hosted_title(content):
                return content, False
    if not lines:
        return None, False
    if re.match(r"^#{1,6}\s", lines[0]):
        return re.sub(r"^#{1,6}\s*", "", lines[0]), False
    return lines[0], True


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
        if content == title and (_bold_line_content(candidate) is not None or re.match(r"^#{1,6}\s", candidate)):
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


def _badge_label_text(value: str) -> str:
    return re.sub(r"^[^a-zA-Z0-9]+", "", value.strip().strip("_*")).strip()


def _explicit_severity_label(value: str, *, priority_badge: bool = False) -> str | None:
    labels = {label.casefold(): label for label in
              ("Critical", "Major", "Minor", "Trivial", "High", "Medium", "Low", "P0", "P1", "P2", "P3")}
    candidate = _badge_label_text(value)
    candidate = re.sub(r"^severity\s*:\s*", "", candidate, flags=re.IGNORECASE)
    priority = re.fullmatch(r"(P[0-3])\]?(?:\s+(?:Bug|Issue|Nit|Suggestion))?", candidate, re.IGNORECASE)
    if priority and (priority_badge or re.fullmatch(r"P[0-3]\]?", candidate, re.IGNORECASE)):
        candidate = priority.group(1)
    return labels.get(candidate.casefold())


def _is_badge_line(line: str) -> bool:
    """Identify severity/category-only lines so they cannot become a title."""

    candidate = re.sub(r"^#{1,6}\s*", "", line).strip()
    sections = [section.strip() for section in candidate.split("|")]
    if len(sections) == 2 and all(
        section.startswith("_") and section.endswith("_") for section in sections
    ):
        known_categories = {"bug", "data integrity & integration", "maintainability & code quality",
                            "security & privacy", "stability & availability"}
        return (_badge_label_text(sections[0]).casefold() in known_categories
                and _explicit_severity_label(sections[1]) is not None)
    if len(sections) in {3, 4} and all(
        section.startswith("_") and section.endswith("_") for section in sections
    ):
        category = _badge_label_text(sections[0]).casefold()
        if category.startswith("security") and category != "security & privacy":
            return False
        severity = _explicit_severity_label(sections[-2])
        effort = _badge_label_text(sections[-1]).casefold()
        effort_labels = {"quick win", "heavy lift", "low value"}
        known_tier = len(sections) == 3 or _badge_label_text(sections[1]).casefold() == "detected with advanced tier"
        if known_tier and severity is not None and effort in effort_labels:
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
    found = set()
    for raw in value.splitlines():
        line = re.sub(r"^(?:>\s*)+", "", raw.strip()).strip()
        # Provider category | severity badge, optionally with tier/effort, including inline prose.
        match = re.match(r"^_[^\n]*?_(?:\s*\|\s*_[^\n]*?_){1,3}", line)
        fields = match.group().split("|") if match and _is_badge_line(match.group()) else []
        badge = fields[-1] if len(fields) == 2 else fields[-2] if fields else None
        if badge is None:
            match = re.match(r"^(?:\*\*([^\n]*?)\*\*|__([^\n]*?)__)", line)
            if match and _is_badge_line(match.group()):
                badge = match.group(1) or match.group(2)
        if badge is not None:
            label = _explicit_severity_label(badge, priority_badge=match is not None and "|" not in match.group())
            if label is not None:
                found.add(label)
    return next(iter(found)) if len(found) == 1 else None


def _hosted_aggregate_display_detail(findings: list[dict[str, Any]], stored_title: str) -> str:
    """Keep each retained aggregate section represented within the shared display limit."""

    total = len(findings)
    note = f"Historical aggregate: {total} provider findings were recorded as one item"
    excerpt = "[Section excerpt truncated; see original source comment.]"
    entries = []
    minimum = len(note) + 100  # Reserve a bounded explicit omitted-section count.
    for index, finding in enumerate(findings, 1):
        title = finding["title"]
        heading = f"**Provider section {index}**"
        if index > 1 or (stored_title != title and not _unusable_hosted_title(stored_title)):
            heading = f"**Provider section {index}: {title}**"
        body = finding["display_detail"]
        required = len(heading) + len(excerpt) + min(len(body), 32) + 6
        if minimum + required > 8000:
            break
        entries.append((heading, body))
        minimum += required
    omitted = total - len(entries)
    if omitted:
        note += f"\n\nOmitted {omitted} provider sections; see original source comment."
    fixed = len(note) + sum(len(heading) + len(excerpt) + 6 for heading, _ in entries)
    budget = max(0, (8000 - fixed) // len(entries)) if entries else 0
    parts = [note]
    for heading, body in entries:
        section = heading
        if body:
            section += "\n\n" + body[:budget].rstrip()
            if len(body) > budget:
                section += "\n\n" + excerpt
        parts.append(section)
    result = "\n\n".join(parts)
    # Keep the aggregate display within the shared character ceiling.
    return result[:8000].rstrip()
