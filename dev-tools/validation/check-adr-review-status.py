#!/usr/bin/env python3
"""Validate ADR human-review provenance against the checked decision queue."""

from __future__ import annotations

import argparse
import re
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import NoReturn

ROOT = Path(__file__).resolve().parents[2]
ADR_DIR = Path("design/architecture/decisions")
REVIEW_QUEUE = Path(
    "design/project-management/design-alignment/consequential-decision-inventory.md"
)
# These records predate the formal human-review queue. Later reviewed parcels
# either affirm them directly or supersede them with queue-backed ADRs.
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
DECISION_KEY_LABEL_RE = re.compile(r"^[A-Z0-9][A-Z0-9-]*$")
REVIEW_FIELD_RE = re.compile(
    r"^- (?P<name>Human review status|Human review date|"
    r"Human review disposition|Review source): (?P<value>.+)$"
)
CHECKED_ROW_PREFIX_RE = re.compile(r"^[-*+] \[[xX]\]")
FENCE_RE = re.compile(r"^(?P<fence>`{3,}|~{3,})")
FENCE_CLOSER_RE = re.compile(r"^(?P<fence>`{3,}|~{3,})[ \t]*$")
LEVEL_TWO_HEADING_RE = re.compile(r"^## [^\r\n]*$")
REVIEW_QUEUE_HEADING_RE = re.compile(r"^## Adversarial Review Queue[ \t]*$")
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


def section_value(text: str, heading: str) -> str:
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
        or lines[heading_line.number] != ""
        or value_line_number > len(lines)
        or value_line_number not in {line.number for line in visible_lines}
        or lines[value_line_number - 1] == ""
    ):
        fail(f"missing or malformed {heading!r} section")
    return lines[value_line_number - 1].strip()


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
            and LEVEL_TWO_HEADING_RE.fullmatch(line.text)
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
        fields[name] = match.group("value").strip()
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
) -> None:
    visible_lines = visible_markdown_lines(text)
    heading_re = re.compile(r"^## Supersession[ \t]*$")
    headings = [line for line in visible_lines if heading_re.fullmatch(line.text)]
    formal_superseded = status == "Superseded"
    if not headings:
        if formal_superseded:
            fail(
                f"{context}: formal Superseded ADR requires exactly one "
                "'Replacement ADR' entry in a 'Supersession' section"
            )
        return
    if not formal_superseded:
        fail(
            f"{context}: 'Supersession' section is only valid for an ADR with "
            "formal status 'Superseded'"
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
            and LEVEL_TWO_HEADING_RE.fullmatch(line.text)
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


def scan_review_queue(lines: list[str], queue_start: int) -> MarkdownSection:
    open_fence: MarkdownFence | None = None
    visible_lines: list[MarkdownLine] = []
    for index in range(queue_start + 1, len(lines)):
        line = lines[index]
        open_fence, is_fence = advance_markdown_fence(
            open_fence,
            line,
            index + 1,
        )
        if is_fence or open_fence is not None:
            continue
        if LEVEL_TWO_HEADING_RE.fullmatch(line):
            return MarkdownSection(index, tuple(visible_lines), None)
        visible_lines.append(MarkdownLine(index + 1, line))
    return MarkdownSection(len(lines), tuple(visible_lines), open_fence)


def review_queue_end(lines: list[str], queue_start: int) -> int:
    return scan_review_queue(lines, queue_start).end


def checked_reviews(
    path: Path,
    repository_root: Path,
    adr_dir: Path,
) -> dict[int, list[Review]]:
    reviews: dict[int, list[Review]] = {}
    seen_keys: set[str] = set()
    text = path.read_text(encoding="utf-8")
    lines = text.splitlines()
    queue_starts = [
        markdown_line.number - 1
        for markdown_line in visible_markdown_lines(text, path)
        if REVIEW_QUEUE_HEADING_RE.fullmatch(markdown_line.text)
    ]
    if not queue_starts:
        fail(f"{path}: missing 'Adversarial Review Queue' section")
    if len(queue_starts) != 1:
        fail(
            f"{path}: expected exactly one 'Adversarial Review Queue' section, "
            f"found {len(queue_starts)}"
        )
    queue_start = queue_starts[0]
    section = scan_review_queue(lines, queue_start)
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
                f"{path}: indented checked review queue row at line {line_number}; "
                "checked review rows must be top-level"
            )
        match = REVIEW_ROW_RE.fullmatch(stripped)
        if not match:
            fail(f"{path}: malformed checked review queue row at line {line_number}")

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
                f"{path}: checked review queue row at line {line_number} "
                "must contain at least one Markdown outcome link"
            )
        if len(outcome_adr_numbers) != len(set(outcome_adr_numbers)):
            fail(
                f"{path}: checked review queue row at line {line_number} "
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
                f"{path}: checked review queue row at line {line_number} "
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
    actual_keys = set(re.findall(r"`([^`]+)`", fields.get("Review source", "")))

    if fields.get("Human review status") != "Completed":
        fail(
            f"{context}: checked human review requires 'Human review status: Completed'"
        )
    if (
        fields.get("Human review date") not in expected_dates
        or len(expected_dates) != 1
    ):
        fail(
            f"{context}: human review date must match checked queue date "
            f"{sorted(expected_dates)}"
        )
    if (
        fields.get("Human review disposition") not in expected_dispositions
        or len(expected_dispositions) != 1
    ):
        fail(
            f"{context}: human review disposition must match checked queue "
            f"{sorted(expected_dispositions)}"
        )
    if actual_keys != expected_keys:
        fail(
            f"{context}: review source keys {sorted(actual_keys)} do not match "
            f"checked queue keys {sorted(expected_keys)}"
        )


def validate_pending_review(context: Path, fields: dict[str, str]) -> None:
    for name, expected in PENDING_REVIEW_FIELDS.items():
        if fields.get(name) != expected:
            fail(f"{context}: pending proposal requires exact '{name}: {expected}'")


def validate(root: Path = ROOT) -> None:
    root = root.resolve()
    adr_dir = root / ADR_DIR
    queue = root / REVIEW_QUEUE
    if not adr_dir.is_dir():
        fail(f"ADR directory missing: {adr_dir.relative_to(root)}")
    if not queue.is_file():
        fail(f"ADR review queue missing: {queue.relative_to(root)}")
    reviews = checked_reviews(queue, root, adr_dir)
    seen_numbers: set[int] = set()

    for path in sorted(adr_dir.glob("adr-*.md")):
        number = adr_number(path)
        if number in seen_numbers:
            fail(f"duplicate ADR number {number:04d}")
        seen_numbers.add(number)

        text = path.read_text(encoding="utf-8")
        visible_markdown_lines(text, path)
        linked_reviews = reviews.get(number, [])
        context = path.relative_to(root)
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
                "by a checked review-queue entry"
            )

        if normalized_status == PENDING_ADR_STATUS:
            validate_pending_review(context, fields)
        elif number not in PRE_FORMAL_REVIEW_RECORDS and not linked_reviews:
            fail(
                f"{context}: terminal ADR status lacks a checked "
                "human-review queue entry"
            )

        validate_supersession(
            context,
            path,
            adr_dir,
            status,
            number,
            text,
        )

    missing_adrs = sorted(set(reviews) - seen_numbers)
    if missing_adrs:
        formatted_missing_adrs = [f"{number:04d}" for number in missing_adrs]
        fail(f"checked review queue references missing ADRs: {formatted_missing_adrs}")

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
