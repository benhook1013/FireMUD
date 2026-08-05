#!/usr/bin/env python3
"""Validate ADR human-review provenance against applied decision provenance."""

from __future__ import annotations

import argparse
import re
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import NoReturn

ROOT = Path(__file__).resolve().parents[2]
ADR_DIR = Path("design/architecture/decisions")
REVIEW_PROVENANCE = Path(
    "design/project-management/design-alignment/consequential-decision-inventory.md"
)
DECISION_README = Path("design/architecture/decisions/README.md")
# These records predate the applied-review provenance ledger. Later reviewed
# parcels either affirm them directly or supersede them with provenance-backed ADRs.
PRE_FORMAL_REVIEW_RECORDS = frozenset({1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11})
PENDING_ADR_STATUS = "Proposed - Pending Human Review"
PENDING_REVIEW_FIELDS = {
    "Human review status": "Pending",
    "Human review date": "Not yet reviewed",
    "Human review disposition": "Pending",
    "Review source": "`AI-AUTHORED-PENDING`",
}
STATUS_TO_HUMAN_REVIEW_DISPOSITIONS = {
    PENDING_ADR_STATUS: frozenset({"Pending"}),
    "Accepted": frozenset({"Accepted", "Revised"}),
    "Superseded": frozenset({"Superseded"}),
    "Withdrawn": frozenset({"Withdrawn"}),
}
ADR_REFERENCE = r"(?:ADR \d{4}|\[ADR \d{4}\]\([^)]+\))"
LEGACY_PRE_FORMAL_STATUS_RE = re.compile(
    rf"^(?:Superseded by {ADR_REFERENCE}|"
    rf"Withdrawn(?:; superseded by {ADR_REFERENCE}| \(superseded by {ADR_REFERENCE}\))?)$"
)
ADR_PATH_RE = re.compile(r"adr-(\d{4})-.*\.md$")
REVIEW_ROW_RE = re.compile(
    r"^[-*+] \[[xX]\] `(?P<key>[A-Z0-9][A-Z0-9-]*)` — "
    r"`(?P<disposition>accepted|revised|deferred|superseded|withdrawn)` "
    r"on (?P<date>\d{4}-\d{2}-\d{2})(?P<outcome>(?:;| by) .+)$"
)
OUTCOME_LINK_RE = re.compile(r"\[[^\]\r\n]+\]\([^)\r\n]+\)")
ADR_LINK_RE = re.compile(r"\[ADR (?P<number>\d{4})\]\((?P<target>[^)\r\n]+)\)")
MARKDOWN_LINK_RE = re.compile(r"\[(?P<label>[^\]\r\n]+)\]\((?P<target>[^)\r\n]+)\)")
ADR_LABEL_RE = re.compile(r"^ADR (?P<number>\d{4})$")
REPLACEMENT_ADR_LABEL_RE = re.compile(r"^replacement ADR (?P<number>\d{4})$")
REPLACEMENT_ADR_ENTRY_RE = re.compile(
    r"^- Replacement ADR: \[ADR (?P<number>\d{4})\]\((?P<target>[^)\r\n]+)\)$"
)
SUPERSESSION_INDEX_HEADING_RE = re.compile(r"^### Supersession Index[ \t]*$")
TABLE_SEPARATOR_CELL_RE = re.compile(r"^:?-{3,}:?$")
DECISION_KEY_LABEL_RE = re.compile(r"^[A-Z0-9][A-Z0-9-]*$")
REVIEW_SOURCE_RE = re.compile(
    r"^`[A-Z0-9][A-Z0-9-]*`(?:, `[A-Z0-9][A-Z0-9-]*`)*$"
)
REVIEW_FIELD_RE = re.compile(
    r"^- (?P<name>Human review status|Human review date|"
    r"Human review disposition|Review source|Withdrawal rationale): "
    r"(?P<value>.+)$"
)
CHECKED_ROW_PREFIX_RE = re.compile(r"^[-*+] \[[xX]\]")
FENCE_RE = re.compile(r"^(?P<fence>`{3,}|~{3,})")
FENCE_CLOSER_RE = re.compile(r"^(?P<fence>`{3,}|~{3,})[ \t]*$")
LEVEL_TWO_HEADING_RE = re.compile(r"^## [^\r\n]*$")
SECTION_BOUNDARY_HEADING_RE = re.compile(r"^#{1,2} [^\r\n]*$")
REVIEW_PROVENANCE_HEADING_RE = re.compile(r"^## Applied Review Provenance[ \t]*$")
RETIRED_REVIEW_QUEUE_HEADING_RE = re.compile(r"^## Adversarial Review Queue[ \t]*$")
SUPERSEDED_SCAN_ALIAS_KEY_RE = re.compile(r"^MS-[A-Z0-9]+(?:-[A-Z0-9]+)+$")
SUPERSEDED_SCAN_ALIAS_SUFFIX = "; retained as a historical service-scan alias."


@dataclass(frozen=True)
class Review:
    key: str
    date: str
    disposition: str


@dataclass(frozen=True)
class MarkdownFence:
    character: str
    length: int
    opening_line: int

    def closes(self, fence: str) -> bool:
        return fence[0] == self.character and len(fence) >= self.length

    @property
    def marker(self) -> str:
        return self.character * self.length


@dataclass(frozen=True)
class MarkdownLine:
    number: int
    text: str


@dataclass(frozen=True)
class MarkdownSection:
    end: int
    visible_lines: tuple[MarkdownLine, ...]
    open_fence: MarkdownFence | None


class ValidationError(Exception):
    """Raised when ADR review status validation fails."""


def fail(message: str) -> NoReturn:
    raise ValidationError(message)


def adr_number(path: Path) -> int:
    match = ADR_PATH_RE.fullmatch(path.name)
    if not match:
        fail(f"invalid ADR filename: {path}")
    return int(match.group(1))


def section_value_line(text: str, heading: str) -> MarkdownLine:
    lines = text.splitlines()
    visible_lines = visible_markdown_lines(text)
    heading_re = re.compile(rf"^## {re.escape(heading)}[ \t]*$")
    heading_matches = [
        line for line in visible_lines if heading_re.fullmatch(line.text)
    ]
    if len(heading_matches) != 1:
        fail(f"missing or malformed {heading!r} section")
    heading_line = heading_matches[0]
    value_line_number = heading_line.number + 2
    if (
        heading_line.number >= len(lines)
        or lines[heading_line.number].strip() != ""
        or value_line_number > len(lines)
        or value_line_number not in {line.number for line in visible_lines}
        or lines[value_line_number - 1].strip() == ""
    ):
        fail(f"missing or malformed {heading!r} section")
    return MarkdownLine(value_line_number, lines[value_line_number - 1].strip())


def section_value(text: str, heading: str) -> str:
    return section_value_line(text, heading).text


def markdown_section(text: str, heading: str) -> str:
    visible_lines = visible_markdown_lines(text)
    heading_re = re.compile(rf"^## {re.escape(heading)}[ \t]*$")
    matches = [
        line for line in visible_lines if heading_re.fullmatch(line.text)
    ]
    if len(matches) != 1:
        fail(f"expected exactly one section {heading!r}, found {len(matches)}")
    heading_line = matches[0]
    following = next(
        (
            line
            for line in visible_lines
            if line.number > heading_line.number
            and SECTION_BOUNDARY_HEADING_RE.fullmatch(line.text)
        ),
        None,
    )
    end = following.number if following is not None else None
    return "\n".join(
        line.text
        for line in visible_lines
        if line.number > heading_line.number
        and (end is None or line.number < end)
    )


def review_fields(text: str) -> dict[str, str]:
    fields: dict[str, str] = {}
    section = markdown_section(text, "Decision Record")
    for line in section.splitlines():
        match = REVIEW_FIELD_RE.fullmatch(line)
        if not match:
            continue
        name = match.group("name")
        if name in fields:
            fail(f"duplicate ADR review field {name!r}")
        value = match.group("value").strip()
        fields[name] = (
            " ".join(value.split())
            if name == "Withdrawal rationale"
            else value
        )
    return fields


def status_kind(
    context: Path,
    status: str,
    number: int,
    has_checked_review: bool,
) -> str:
    if status in STATUS_TO_HUMAN_REVIEW_DISPOSITIONS:
        return status
    if (
        number in PRE_FORMAL_REVIEW_RECORDS
        and not has_checked_review
        and LEGACY_PRE_FORMAL_STATUS_RE.fullmatch(status)
    ):
        return "Superseded" if status.startswith("Superseded") else "Withdrawn"
    fail(
        f"{context}: status must be exactly one of "
        f"{sorted(STATUS_TO_HUMAN_REVIEW_DISPOSITIONS)}"
    )


def validate_status_review_mapping(
    context: Path,
    status: str,
    fields: dict[str, str],
) -> None:
    disposition = fields.get("Human review disposition")
    allowed = STATUS_TO_HUMAN_REVIEW_DISPOSITIONS[status]
    if disposition not in allowed:
        fail(
            f"{context}: ADR status {status!r} does not allow human review "
            f"disposition {disposition!r}; allowed dispositions are "
            f"{sorted(allowed)}"
        )


def parse_adr_target(
    path: Path, target: str
) -> tuple[str, Path, Path, re.Match[str] | None]:
    target_ref = re.split(r"[?#]", target, maxsplit=1)[0]
    target_path = Path(target_ref)
    resolved_target = (path.parent / target_path).resolve()
    target_filename = target_ref.rsplit("/", 1)[-1]
    target_match = ADR_PATH_RE.fullmatch(target_filename)
    return target_ref, target_path, resolved_target, target_match


def provenance_adr_number(
    path: Path,
    adr_dir: Path,
    line_number: int,
    adr_match: re.Match[str],
) -> int:
    displayed_number = int(adr_match.group("number"))
    target_ref, target_path, resolved_target, target_match = parse_adr_target(
        path,
        adr_match.group("target"),
    )
    if target_match is None or int(target_match.group(1)) != displayed_number:
        fail(
            f"{path}: malformed ADR provenance at line {line_number}; "
            f"displayed ADR {displayed_number:04d} does not match target "
            f"{adr_match.group('target')!r}"
        )

    if (
        target_path.is_absolute()
        or resolved_target.parent != adr_dir.resolve()
        or not resolved_target.is_file()
    ):
        fail(
            f"{path}: ADR {displayed_number:04d} target does not exist in the "
            f"canonical ADR directory: {target_ref!r}"
        )
    return displayed_number


def validate_replacement_adr_target(
    path: Path,
    adr_dir: Path,
    line_number: int,
    displayed_number: int,
    target: str,
    relationship: str = "superseded scan-alias replacement",
) -> None:
    _target_ref, target_path, resolved_target, target_match = parse_adr_target(
        path,
        target,
    )
    if target_match is None or int(target_match.group(1)) != displayed_number:
        fail(
            f"{path}: malformed {relationship} at line "
            f"{line_number}; replacement ADR {displayed_number:04d} does not "
            f"match target {target!r}"
        )

    if (
        target_path.is_absolute()
        or resolved_target.parent != adr_dir.resolve()
        or not resolved_target.is_file()
    ):
        fail(
            f"{path}: {relationship} at line {line_number} "
            f"must target the canonical ADR directory: {target!r}"
        )


def validate_decision_key_target(
    path: Path,
    repository_root: Path,
    line_number: int,
    target: str,
) -> None:
    _target_ref, target_path, resolved_target, _ = parse_adr_target(path, target)
    try:
        resolved_target.relative_to(repository_root.resolve())
    except ValueError:
        resolved_inside_repository = False
    else:
        resolved_inside_repository = True
    if (
        target_path.is_absolute()
        or target_path.suffix.lower() != ".md"
        or not resolved_inside_repository
        or not resolved_target.is_file()
    ):
        fail(
            f"{path}: superseded scan-alias replacement at line {line_number} "
            f"must target an existing Markdown decision document: {target!r}"
        )


def validate_superseded_scan_alias_outcome(
    path: Path,
    repository_root: Path,
    adr_dir: Path,
    line_number: int,
    outcome: str,
) -> None:
    links = list(MARKDOWN_LINK_RE.finditer(outcome))
    if not links:
        fail(
            f"{path}: superseded scan-alias row at line {line_number} must "
            "contain replacement-decision Markdown links"
        )

    for link in links:
        label = link.group("label")
        target = link.group("target")
        if ADR_LABEL_RE.fullmatch(label):
            fail(
                f"{path}: superseded scan-alias row at line {line_number} "
                "must not use exact [ADR NNNN] provenance labels"
            )
        replacement_adr = REPLACEMENT_ADR_LABEL_RE.fullmatch(label)
        if replacement_adr is not None:
            validate_replacement_adr_target(
                path,
                adr_dir,
                line_number,
                int(replacement_adr.group("number")),
                target,
            )
            continue
        if DECISION_KEY_LABEL_RE.fullmatch(label) is None:
            fail(
                f"{path}: superseded scan-alias row at line {line_number} "
                f"has non-replacement link label {label!r}"
            )
        validate_decision_key_target(
            path,
            repository_root,
            line_number,
            target,
        )


def is_superseded_scan_alias(key: str, outcome: str, disposition: str) -> bool:
    return (
        disposition == "Superseded"
        and SUPERSEDED_SCAN_ALIAS_KEY_RE.fullmatch(key) is not None
        and outcome.endswith(SUPERSEDED_SCAN_ALIAS_SUFFIX)
    )


def advance_markdown_fence(
    open_fence: MarkdownFence | None,
    line: str,
    line_number: int,
) -> tuple[MarkdownFence | None, bool]:
    stripped = line.lstrip()
    fence_match = FENCE_RE.match(stripped)
    if fence_match is None:
        return open_fence, False

    fence = fence_match.group("fence")
    if open_fence is None:
        return MarkdownFence(fence[0], len(fence), line_number), True
    if open_fence.closes(fence) and FENCE_CLOSER_RE.fullmatch(stripped):
        return None, True
    return open_fence, True


def visible_markdown_lines(
    text: str, context: Path | None = None
) -> list[MarkdownLine]:
    open_fence: MarkdownFence | None = None
    visible_lines: list[MarkdownLine] = []
    for index, line in enumerate(text.splitlines()):
        open_fence, is_fence = advance_markdown_fence(
            open_fence,
            line,
            index + 1,
        )
        if is_fence or open_fence is not None:
            continue
        visible_lines.append(MarkdownLine(index + 1, line))
    if open_fence is not None:
        prefix = f"{context}: " if context is not None else ""
        fail(
            f"{prefix}unterminated code fence opened at line "
            f"{open_fence.opening_line} with {open_fence.marker}"
        )
    return visible_lines


def validate_supersession(
    context: Path,
    path: Path,
    adr_dir: Path,
    status: str,
    number: int,
    text: str,
    legacy_status: str | None = None,
) -> int | None:
    visible_lines = visible_markdown_lines(text)
    heading_re = re.compile(r"^## Supersession[ \t]*$")
    headings = [line for line in visible_lines if heading_re.fullmatch(line.text)]
    allows_supersession = status in {"Superseded", "Withdrawn"}
    if not headings:
        legacy_source = legacy_status if legacy_status is not None else status
        if number in PRE_FORMAL_REVIEW_RECORDS and LEGACY_PRE_FORMAL_STATUS_RE.fullmatch(
            legacy_source
        ):
            legacy_replacement = ADR_LINK_RE.search(legacy_source)
            if legacy_replacement is None:
                return None
            replacement_number = int(legacy_replacement.group("number"))
            if replacement_number == number:
                fail(
                    f"{context}: legacy replacement ADR must not self-reference "
                    f"ADR {number:04d}"
                )
            status_line = next(
                (
                    line.number
                    for line in visible_lines
                    if line.text.strip() == legacy_source
                ),
                section_value_line(text, "Status").number,
            )
            validate_replacement_adr_target(
                path,
                adr_dir,
                status_line,
                replacement_number,
                legacy_replacement.group("target"),
                relationship="legacy replacement ADR",
            )
            return replacement_number
        if status == "Superseded" and number not in PRE_FORMAL_REVIEW_RECORDS:
            fail(
                f"{context}: formal Superseded ADR requires exactly one "
                "'Replacement ADR' entry in a 'Supersession' section"
            )
        return None
    if not allows_supersession:
        fail(
            f"{context}: 'Supersession' section is only valid for an ADR with "
            "formal status 'Superseded' or 'Withdrawn'"
        )
    if len(headings) != 1:
        fail(
            f"{context}: expected exactly one section 'Supersession', "
            f"found {len(headings)}"
        )

    heading = headings[0]
    following = next(
        (
            line
            for line in visible_lines
            if line.number > heading.number
            and SECTION_BOUNDARY_HEADING_RE.fullmatch(line.text)
        ),
        None,
    )
    end = following.number if following is not None else None
    section_lines = [
        line
        for line in visible_lines
        if line.number > heading.number
        and (end is None or line.number < end)
        and line.text.strip()
    ]
    if len(section_lines) != 1:
        fail(
            f"{context}: 'Supersession' section must contain exactly one "
            "valid 'Replacement ADR' entry"
        )

    entry = REPLACEMENT_ADR_ENTRY_RE.fullmatch(section_lines[0].text)
    if entry is None:
        fail(
            f"{context}: 'Supersession' section must contain exactly one "
            "valid 'Replacement ADR' entry"
        )
    replacement_number = int(entry.group("number"))
    if replacement_number == number:
        fail(
            f"{context}: Supersession replacement ADR must not self-reference "
            f"ADR {number:04d}"
        )
    validate_replacement_adr_target(
        path,
        adr_dir,
        section_lines[0].number,
        replacement_number,
        entry.group("target"),
        relationship="Supersession replacement ADR",
    )
    return replacement_number


def scan_review_provenance(lines: list[str], provenance_start: int) -> MarkdownSection:
    open_fence: MarkdownFence | None = None
    visible_lines: list[MarkdownLine] = []
    for index in range(provenance_start + 1, len(lines)):
        line = lines[index]
        open_fence, is_fence = advance_markdown_fence(
            open_fence,
            line,
            index + 1,
        )
        if is_fence or open_fence is not None:
            continue
        if SECTION_BOUNDARY_HEADING_RE.fullmatch(line):
            return MarkdownSection(index, tuple(visible_lines), None)
        visible_lines.append(MarkdownLine(index + 1, line))
    return MarkdownSection(len(lines), tuple(visible_lines), open_fence)


def markdown_table_cells(
    context: Path,
    line: MarkdownLine,
) -> list[str]:
    stripped = line.text.strip()
    if not (stripped.startswith("|") and stripped.endswith("|")):
        fail(f"{context}: malformed table row at line {line.number}")
    return [cell.strip() for cell in stripped[1:-1].split("|")]


def supersession_index_rows(
    path: Path,
    adr_dir: Path,
) -> dict[int, tuple[str, int]]:
    text = path.read_text(encoding="utf-8")
    visible_lines = visible_markdown_lines(text, path)
    headings = [
        line
        for line in visible_lines
        if SUPERSESSION_INDEX_HEADING_RE.fullmatch(line.text)
    ]
    if len(headings) != 1:
        fail(
            f"{path}: expected exactly one 'Supersession Index' section, "
            f"found {len(headings)}"
        )

    heading = headings[0]
    following = next(
        (
            line
            for line in visible_lines
            if line.number > heading.number
            and (
                SECTION_BOUNDARY_HEADING_RE.fullmatch(line.text)
                or re.fullmatch(r"^### [^\r\n]*$", line.text)
            )
        ),
        None,
    )
    end = following.number if following is not None else None
    section_lines = [
        line
        for line in visible_lines
        if line.number > heading.number
        and (end is None or line.number < end)
    ]
    table_lines = [line for line in section_lines if line.text.strip().startswith("|")]
    if len(table_lines) < 2:
        fail(
            f"{path}: Supersession Index must contain a header and separator row"
        )

    header = markdown_table_cells(path, table_lines[0])
    if header != ["ADR", "Status", "Replacement ADR"]:
        fail(
            f"{path}: Supersession Index header must be exactly "
            "'| ADR | Status | Replacement ADR |'"
        )
    separator = markdown_table_cells(path, table_lines[1])
    if len(separator) != 3 or not all(
        TABLE_SEPARATOR_CELL_RE.fullmatch(cell) for cell in separator
    ):
        fail(f"{path}: malformed Supersession Index separator row")

    rows: dict[int, tuple[str, int]] = {}
    for line in table_lines[2:]:
        cells = markdown_table_cells(path, line)
        if len(cells) != 3:
            fail(
                f"{path}: Supersession Index row at line {line.number} must have "
                "exactly three cells"
            )
        source_match = ADR_LINK_RE.fullmatch(cells[0])
        if source_match is None:
            fail(
                f"{path}: Supersession Index row at line {line.number} must use "
                "an exact [ADR NNNN] source link"
            )
        source_number = provenance_adr_number(
            path,
            adr_dir,
            line.number,
            source_match,
        )
        replacement_match = ADR_LINK_RE.fullmatch(cells[2])
        if replacement_match is None:
            fail(
                f"{path}: Supersession Index row at line {line.number} must use "
                "an exact [ADR NNNN] replacement link"
            )
        replacement_number = int(replacement_match.group("number"))
        validate_replacement_adr_target(
            path,
            adr_dir,
            line.number,
            replacement_number,
            replacement_match.group("target"),
            relationship="Supersession Index replacement ADR",
        )
        if cells[1] not in {"Superseded", "Withdrawn"}:
            fail(
                f"{path}: Supersession Index row at line {line.number} has invalid "
                f"status {cells[1]!r}"
            )
        if source_number in rows:
            fail(
                f"{path}: duplicate Supersession Index entry for ADR "
                f"{source_number:04d}"
            )
        rows[source_number] = (cells[1], replacement_number)
    return rows


def validate_supersession_index(
    path: Path,
    adr_dir: Path,
    adr_states: dict[int, tuple[str, int | None]],
) -> None:
    actual = supersession_index_rows(path, adr_dir)
    expected = {
        number: (status, replacement_number)
        for number, (status, replacement_number) in adr_states.items()
        if replacement_number is not None
    }
    missing = sorted(set(expected) - set(actual))
    if missing:
        fail(
            f"{path}: Supersession Index is missing ADR entries with replacement "
            f"sections: {[f'{number:04d}' for number in missing]}"
        )
    unexpected = sorted(set(actual) - set(expected))
    if unexpected:
        fail(
            f"{path}: Supersession Index contains ADRs without a validated "
            f"replacement section: {[f'{number:04d}' for number in unexpected]}"
        )
    for number, (actual_status, actual_replacement) in actual.items():
        expected_status, expected_replacement = expected[number]
        if actual_status != expected_status:
            fail(
                f"{path}: Supersession Index status for ADR {number:04d} is "
                f"{actual_status!r}, but the ADR status is {expected_status!r}"
            )
        if actual_replacement != expected_replacement:
            fail(
                f"{path}: Supersession Index replacement for ADR {number:04d} "
                f"is ADR {actual_replacement:04d}, but the ADR Supersession "
                f"section names ADR {expected_replacement:04d}"
            )


def checked_reviews(
    path: Path,
    repository_root: Path,
    adr_dir: Path,
) -> dict[int, list[Review]]:
    reviews: dict[int, list[Review]] = {}
    seen_keys: set[str] = set()
    text = path.read_text(encoding="utf-8")
    lines = text.splitlines()
    visible_lines = visible_markdown_lines(text, path)
    if any(
        RETIRED_REVIEW_QUEUE_HEADING_RE.fullmatch(markdown_line.text)
        for markdown_line in visible_lines
    ):
        fail(
            f"{path}: retired 'Adversarial Review Queue' section is not allowed; "
            "use 'Applied Review Provenance'"
        )
    provenance_starts = [
        markdown_line.number - 1
        for markdown_line in visible_lines
        if REVIEW_PROVENANCE_HEADING_RE.fullmatch(markdown_line.text)
    ]
    if not provenance_starts:
        fail(f"{path}: missing 'Applied Review Provenance' section")
    if len(provenance_starts) != 1:
        fail(
            f"{path}: expected exactly one 'Applied Review Provenance' section, "
            f"found {len(provenance_starts)}"
        )
    provenance_start = provenance_starts[0]
    section = scan_review_provenance(lines, provenance_start)
    if section.open_fence is not None:
        fail(
            f"{path}: unterminated code fence opened at line "
            f"{section.open_fence.opening_line} with {section.open_fence.marker}"
        )

    for markdown_line in section.visible_lines:
        line_number = markdown_line.number
        line = markdown_line.text
        stripped = line.lstrip()
        if not CHECKED_ROW_PREFIX_RE.match(stripped):
            continue
        if stripped != line:
            fail(
                f"{path}: indented checked review provenance row at "
                f"line {line_number}; "
                "checked review rows must be top-level"
            )
        match = REVIEW_ROW_RE.fullmatch(stripped)
        if not match:
            fail(
                f"{path}: malformed checked review provenance row "
                f"at line {line_number}"
            )

        review = Review(
            key=match.group("key"),
            date=match.group("date"),
            disposition=match.group("disposition").capitalize(),
        )
        is_scan_alias = is_superseded_scan_alias(
            review.key,
            match.group("outcome"),
            review.disposition,
        )
        if is_scan_alias:
            validate_superseded_scan_alias_outcome(
                path,
                repository_root,
                adr_dir,
                line_number,
                match.group("outcome"),
            )
        outcome_adr_numbers: list[int] = []
        for adr_match in ADR_LINK_RE.finditer(match.group("outcome")):
            outcome_adr_numbers.append(
                provenance_adr_number(path, adr_dir, line_number, adr_match)
            )
        if not OUTCOME_LINK_RE.search(match.group("outcome")):
            fail(
                f"{path}: checked review provenance row at line {line_number} "
                "must contain at least one Markdown outcome link"
            )
        if len(outcome_adr_numbers) != len(set(outcome_adr_numbers)):
            fail(
                f"{path}: checked review provenance row at line {line_number} "
                "contains duplicate ADR outcome links"
            )

        if review.disposition == "Deferred" and outcome_adr_numbers:
            fail(
                f"{path}: checked deferred review row at line {line_number} "
                "must not use exact ADR provenance"
            )
        if (
            review.disposition in {"Accepted", "Revised", "Superseded", "Withdrawn"}
            and not outcome_adr_numbers
            and not is_scan_alias
        ):
            fail(
                f"{path}: checked review provenance row at line {line_number} "
                "must contain at least one exact [ADR NNNN] outcome link"
            )
        if review.key in seen_keys:
            fail(
                f"{path}: ambiguous duplicate checked review source "
                f"{review.key!r} at line {line_number}"
            )
        seen_keys.add(review.key)

        # A superseded scan alias points to replacement decisions; those ADRs
        # are not provenance for the historical alias row itself.
        review_adr_numbers = [] if is_scan_alias else outcome_adr_numbers
        for number in review_adr_numbers:
            existing = reviews.setdefault(number, [])
            if existing and any(
                (prior.date, prior.disposition) != (review.date, review.disposition)
                for prior in existing
            ):
                fail(
                    f"{path}: ambiguous duplicate checked review rows for "
                    f"ADR {number:04d}"
                )
            existing.append(review)
    return reviews


def validate_completed_review(
    context: Path,
    fields: dict[str, str],
    reviews: list[Review],
) -> None:
    expected_dates = {review.date for review in reviews}
    expected_dispositions = {review.disposition for review in reviews}
    expected_keys = {review.key for review in reviews}

    if fields.get("Human review status") != "Completed":
        fail(
            f"{context}: checked human review requires 'Human review status: Completed'"
        )
    if (
        fields.get("Human review date") not in expected_dates
        or len(expected_dates) != 1
    ):
        fail(
            f"{context}: human review date must match checked provenance date "
            f"{sorted(expected_dates)}"
        )
    if (
        fields.get("Human review disposition") not in expected_dispositions
        or len(expected_dispositions) != 1
    ):
        fail(
            f"{context}: human review disposition must match checked provenance "
            f"{sorted(expected_dispositions)}"
        )
    actual_keys = set(parse_review_source(fields.get("Review source", ""), context))
    if actual_keys != expected_keys:
        fail(
            f"{context}: review source keys {sorted(actual_keys)} do not match "
            f"checked provenance keys {sorted(expected_keys)}"
        )


def parse_review_source(
    value: str, context: Path | str = "review metadata"
) -> tuple[str, ...]:
    if REVIEW_SOURCE_RE.fullmatch(value) is None:
        fail(
            f"{context}: review source must contain one or more "
            "backtick-delimited provenance keys separated by ', '"
        )
    keys = tuple(re.findall(r"`([^`]+)`", value))
    if len(keys) != len(set(keys)):
        fail(f"{context}: review source must not contain duplicate provenance keys")
    return keys


def validate_pending_review(context: Path, fields: dict[str, str]) -> None:
    for name, expected in PENDING_REVIEW_FIELDS.items():
        if fields.get(name) != expected:
            fail(f"{context}: pending proposal requires exact '{name}: {expected}'")


def validate_withdrawal_rationale(
    context: Path,
    number: int,
    status: str,
    fields: dict[str, str],
    text: str,
) -> None:
    if status != "Withdrawn" or number in PRE_FORMAL_REVIEW_RECORDS:
        return
    has_supersession = any(
        re.fullmatch(r"## Supersession[ \t]*", line.text)
        for line in visible_markdown_lines(text)
    )
    if has_supersession:
        return
    rationale = fields.get("Withdrawal rationale", "")
    if not rationale:
        fail(
            f"{context}: Withdrawn ADR without Supersession requires a "
            "non-empty normalized 'Withdrawal rationale'"
        )


def validate(root: Path = ROOT) -> None:
    root = root.resolve()
    adr_dir = root / ADR_DIR
    provenance = root / REVIEW_PROVENANCE
    decision_readme = root / DECISION_README
    if not adr_dir.is_dir():
        fail(f"ADR directory missing: {adr_dir.relative_to(root)}")
    if not provenance.is_file():
        fail(f"ADR review provenance missing: {provenance.relative_to(root)}")
    if not decision_readme.is_file():
        fail(f"ADR decision README missing: {decision_readme.relative_to(root)}")
    reviews = checked_reviews(provenance, root, adr_dir)
    seen_numbers: set[int] = set()
    adr_states: dict[int, tuple[str, int | None]] = {}

    for path in sorted(adr_dir.glob("adr-*.md")):
        number = adr_number(path)
        if number in seen_numbers:
            fail(f"duplicate ADR number {number:04d}")
        seen_numbers.add(number)

        context = path.relative_to(root)
        text = path.read_text(encoding="utf-8")
        visible_markdown_lines(text, context)
        linked_reviews = reviews.get(number, [])
        try:
            status = section_value(text, "Status")
        except ValidationError as error:
            fail(f"{path.relative_to(root)}: {error}")
        normalized_status = status_kind(
            context,
            status,
            number,
            bool(linked_reviews),
        )
        has_decision_record = (
            any(
                re.fullmatch(r"## Decision Record[ \t]*", line.text)
                for line in visible_markdown_lines(text)
            )
        )
        if number in PRE_FORMAL_REVIEW_RECORDS and not has_decision_record:
            fields = {}
        else:
            try:
                fields = review_fields(text)
            except ValidationError as error:
                fail(f"{path.relative_to(root)}: {error}")
        if linked_reviews:
            validate_completed_review(
                context,
                fields,
                linked_reviews,
            )
            validate_status_review_mapping(context, normalized_status, fields)
        elif fields.get("Human review status") == "Completed":
            fail(
                f"{context}: completed human review is not backed "
                "by a checked review-provenance entry"
            )

        if normalized_status == PENDING_ADR_STATUS:
            validate_pending_review(context, fields)
        elif number not in PRE_FORMAL_REVIEW_RECORDS and not linked_reviews:
            fail(
                f"{context}: terminal ADR status lacks a checked "
                "human-review provenance entry"
            )

        validate_withdrawal_rationale(
            context,
            number,
            normalized_status,
            fields,
            text,
        )

        replacement_number = validate_supersession(
            context,
            path,
            adr_dir,
            normalized_status,
            number,
            text,
            legacy_status=status,
        )
        adr_states[number] = (normalized_status, replacement_number)

    missing_adrs = sorted(set(reviews) - seen_numbers)
    if missing_adrs:
        formatted_missing_adrs = [f"{number:04d}" for number in missing_adrs]
        fail(f"checked review provenance references missing ADRs: {formatted_missing_adrs}")

    validate_supersession_index(decision_readme, adr_dir, adr_states)

    print(
        "ADR review status validation passed: "
        f"{len(reviews)} reviewed ADRs, "
        f"{len(PRE_FORMAL_REVIEW_RECORDS & seen_numbers)} pre-formal records"
    )


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="Validate ADR human-review provenance."
    )
    parser.add_argument(
        "root",
        nargs="?",
        type=Path,
        default=ROOT,
        help="repository root (defaults to the root containing this script)",
    )
    arguments = parser.parse_args(argv)
    try:
        validate(arguments.root)
    except ValidationError as error:
        print(error, file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
