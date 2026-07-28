#!/usr/bin/env python3
"""Validate ADR human-review provenance against the checked decision queue."""

from __future__ import annotations

import re
import sys
from collections import defaultdict
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
TERMINAL_ADR_STATUS_RE = re.compile(r"^(?:Accepted|Superseded|Withdrawn)$")
LEGACY_PRE_FORMAL_STATUS_RE = re.compile(
    rf"^(?:Superseded by {ADR_REFERENCE}|"
    rf"Withdrawn(?:; superseded by {ADR_REFERENCE}| \(superseded by {ADR_REFERENCE}\))?)$"
)
ADR_PATH_RE = re.compile(r"adr-(\d{4})-.*\.md$")
REVIEW_ROW_RE = re.compile(
    r"^- \[[xX]\] `(?P<key>[A-Z0-9][A-Z0-9-]*)` — "
    r"`(?P<disposition>accepted|revised|deferred|superseded|withdrawn)` "
    r"on (?P<date>\d{4}-\d{2}-\d{2})(?P<outcome>(?:;| by) .+)$"
)
OUTCOME_LINK_RE = re.compile(r"\[[^\]\r\n]+\]\([^)\r\n]+\)")
ADR_LINK_RE = re.compile(
    r"\[ADR (?P<number>\d{4})\]\((?P<target>[^)\r\n]+)\)"
)
REVIEW_FIELD_RE = re.compile(
    r"^- (?P<name>Human review status|Human review date|"
    r"Human review disposition|Review source): (?P<value>.+)$"
)
CHECKED_ROW_PREFIX_RE = re.compile(r"^- \[[xX]\]")


@dataclass(frozen=True)
class Review:
    key: str
    date: str
    disposition: str


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
    match = re.search(
        rf"^## {re.escape(heading)}\n\n(?P<value>[^\n]+)$",
        text,
        flags=re.MULTILINE,
    )
    if not match:
        fail(f"missing or malformed {heading!r} section")
    return match.group("value").strip()


def review_fields(text: str) -> dict[str, str]:
    fields: dict[str, str] = {}
    for line in text.splitlines():
        match = REVIEW_FIELD_RE.fullmatch(line)
        if not match:
            continue
        name = match.group("name")
        if name in fields:
            fail(f"duplicate ADR review field {name!r}")
        fields[name] = match.group("value").strip()
    return fields


def is_terminal_status(status: str) -> bool:
    return TERMINAL_ADR_STATUS_RE.fullmatch(status) is not None


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


def checked_reviews(path: Path) -> dict[int, list[Review]]:
    reviews: dict[int, list[Review]] = defaultdict(list)
    seen_keys: set[str] = set()
    for line_number, line in enumerate(
        path.read_text(encoding="utf-8").splitlines(), start=1
    ):
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

        outcome_adr_numbers: list[int] = []
        for adr_match in ADR_LINK_RE.finditer(match.group("outcome")):
            displayed_number = int(adr_match.group("number"))
            target_filename = (
                adr_match.group("target")
                .split("#", 1)[0]
                .split("?", 1)[0]
                .rsplit("/", 1)[-1]
            )
            target_match = ADR_PATH_RE.fullmatch(target_filename)
            if target_match is None or int(target_match.group(1)) != displayed_number:
                fail(
                    f"{path}: malformed ADR provenance at line {line_number}; "
                    f"displayed ADR {displayed_number:04d} does not match target "
                    f"{adr_match.group('target')!r}"
                )
            outcome_adr_numbers.append(displayed_number)
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

        review = Review(
            key=match.group("key"),
            date=match.group("date"),
            disposition=match.group("disposition").capitalize(),
        )
        if review.key in seen_keys:
            fail(
                f"{path}: ambiguous duplicate checked review source "
                f"{review.key!r} at line {line_number}"
            )
        seen_keys.add(review.key)

        # Exact [ADR NNNN] labels carry provenance for every disposition,
        # including superseded rows. Replacement links use other labels.
        for number in outcome_adr_numbers:
            existing = reviews[number]
            if existing and any(
                (prior.date, prior.disposition)
                != (review.date, review.disposition)
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
        fail(f"{context}: checked human review requires 'Human review status: Completed'")
    if fields.get("Human review date") not in expected_dates or len(expected_dates) != 1:
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
            fail(
                f"{context}: pending proposal requires exact "
                f"'{name}: {expected}'"
            )


def validate(root: Path = ROOT) -> None:
    root = root.resolve()
    adr_dir = root / ADR_DIR
    queue = root / REVIEW_QUEUE
    if not adr_dir.is_dir():
        fail(f"ADR directory missing: {adr_dir.relative_to(root)}")
    if not queue.is_file():
        fail(f"ADR review queue missing: {queue.relative_to(root)}")
    reviews = checked_reviews(queue)
    seen_numbers: set[int] = set()

    for path in sorted(adr_dir.glob("adr-*.md")):
        number = adr_number(path)
        if number in seen_numbers:
            fail(f"duplicate ADR number {number:04d}")
        seen_numbers.add(number)

        text = path.read_text(encoding="utf-8")
        try:
            status = section_value(text, "Status")
            fields = review_fields(text)
        except ValidationError as error:
            fail(f"{path.relative_to(root)}: {error}")

        linked_reviews = reviews.get(number, [])
        context = path.relative_to(root)
        normalized_status = status_kind(
            context,
            status,
            number,
            bool(linked_reviews),
        )
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
        elif not is_terminal_status(normalized_status):
            fail(f"{context}: status is not a recognized terminal status")
        elif number not in PRE_FORMAL_REVIEW_RECORDS and not linked_reviews:
            fail(
                f"{context}: terminal ADR status lacks a checked "
                "human-review queue entry"
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


if __name__ == "__main__":
    try:
        validate()
    except ValidationError as error:
        print(error, file=sys.stderr)
        raise SystemExit(1)
