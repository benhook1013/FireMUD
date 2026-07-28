#!/usr/bin/env python3
"""Regression checks for ADR human-review provenance validation."""

from __future__ import annotations

import importlib.util
import sys
import tempfile
import textwrap
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "dev-tools/validation/check-adr-review-status.py"


def load_validator():
    spec = importlib.util.spec_from_file_location("adr_review_status_validator", SCRIPT)
    if spec is None or spec.loader is None:
        raise AssertionError("could not load ADR review status validator")
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


def write(path: Path, text: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(textwrap.dedent(text).lstrip(), encoding="utf-8")


def fixture_root() -> tempfile.TemporaryDirectory[str]:
    fixture = tempfile.TemporaryDirectory()
    root = Path(fixture.name)
    write(
        root
        / "design/project-management/design-alignment/consequential-decision-inventory.md",
        """
        - [x] `TEST-01` — `revised` on 2026-07-27; [ADR 0012](../../architecture/decisions/adr-0012-reviewed.md)
        """,
    )
    write(
        root / "design/architecture/decisions/adr-0001-legacy.md",
        """
        # ADR 0001

        ## Status

        Accepted
        """,
    )
    write(
        root / "design/architecture/decisions/adr-0012-reviewed.md",
        """
        # ADR 0012

        ## Status

        Accepted

        ## Decision Record

        - Human review status: Completed
        - Human review date: 2026-07-27
        - Human review disposition: Revised
        - Review source: `TEST-01`
        """,
    )
    write(
        root / "design/architecture/decisions/adr-0013-pending.md",
        """
        # ADR 0013

        ## Status

        Proposed - Pending Human Review

        ## Decision Record

        - Human review status: Pending
        """,
    )
    return fixture


def queue_path(root: Path) -> Path:
    return (
        root
        / "design/project-management/design-alignment/consequential-decision-inventory.md"
    )


def append_queue_row(root: Path, row: str) -> None:
    path = queue_path(root)
    path.write_text(
        path.read_text(encoding="utf-8") + f"\n{row}\n",
        encoding="utf-8",
    )


def expect_failure(call, expected: str) -> None:
    with unittest.TestCase().assertRaisesRegex(SystemExit, expected):
        call()


class AdrReviewStatusTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.validator = load_validator()

    def test_valid_reviewed_pending_and_legacy_records(self) -> None:
        with fixture_root() as fixture:
            self.validator.validate(Path(fixture))

    def test_accepted_record_requires_checked_review(self) -> None:
        with fixture_root() as fixture:
            root = Path(fixture)
            path = root / "design/architecture/decisions/adr-0013-pending.md"
            path.write_text(
                path.read_text(encoding="utf-8").replace(
                    "Proposed - Pending Human Review", "Accepted"
                ),
                encoding="utf-8",
            )
            expect_failure(
                lambda: self.validator.validate(root),
                "terminal ADR status lacks a checked human-review queue entry",
            )

    def test_all_terminal_statuses_require_checked_review(self) -> None:
        for status in ("Accepted", "Superseded by ADR 0099", "Withdrawn"):
            with self.subTest(status=status), fixture_root() as fixture:
                root = Path(fixture)
                path = root / "design/architecture/decisions/adr-0013-pending.md"
                path.write_text(
                    path.read_text(encoding="utf-8").replace(
                        "Proposed - Pending Human Review", status
                    ),
                    encoding="utf-8",
                )
                expect_failure(
                    lambda: self.validator.validate(root),
                    "terminal ADR status lacks a checked human-review queue entry",
                )

    def test_checked_review_provenance_accepts_all_terminal_statuses(self) -> None:
        for status in ("Accepted", "Superseded by ADR 0099", "Withdrawn"):
            with self.subTest(status=status), fixture_root() as fixture:
                root = Path(fixture)
                path = root / "design/architecture/decisions/adr-0012-reviewed.md"
                path.write_text(
                    path.read_text(encoding="utf-8").replace("Accepted", status),
                    encoding="utf-8",
                )
                self.validator.validate(root)

    def test_pre_formal_terminal_statuses_are_exempt(self) -> None:
        for status in ("Accepted", "Superseded by ADR 0099", "Withdrawn"):
            with self.subTest(status=status), fixture_root() as fixture:
                root = Path(fixture)
                path = root / "design/architecture/decisions/adr-0001-legacy.md"
                path.write_text(
                    path.read_text(encoding="utf-8").replace("Accepted", status),
                    encoding="utf-8",
                )
                self.validator.validate(root)

    def test_unrecognized_and_nonterminal_statuses_are_rejected(self) -> None:
        for status in (
            "Proposed",
            "Deferred",
            "Proposed - Pending Human review",
            "Accepted with caveat",
            "Superseded",
        ):
            with self.subTest(status=status), fixture_root() as fixture:
                root = Path(fixture)
                path = root / "design/architecture/decisions/adr-0013-pending.md"
                path.write_text(
                    path.read_text(encoding="utf-8").replace(
                        "Proposed - Pending Human Review", status
                    ),
                    encoding="utf-8",
                )
                expect_failure(
                    lambda: self.validator.validate(root),
                    "status must be exactly",
                )

    def test_checked_review_requires_completed_metadata(self) -> None:
        with fixture_root() as fixture:
            root = Path(fixture)
            path = root / "design/architecture/decisions/adr-0012-reviewed.md"
            path.write_text(
                path.read_text(encoding="utf-8").replace(
                    "Human review status: Completed", "Human review status: Pending"
                ),
                encoding="utf-8",
            )
            expect_failure(
                lambda: self.validator.validate(root),
                "checked human review requires",
            )

    def test_checked_review_fields_must_match_queue(self) -> None:
        with fixture_root() as fixture:
            root = Path(fixture)
            path = root / "design/architecture/decisions/adr-0012-reviewed.md"
            path.write_text(
                path.read_text(encoding="utf-8")
                .replace("2026-07-27", "2026-07-26")
                .replace("`TEST-01`", "`TEST-02`"),
                encoding="utf-8",
            )
            expect_failure(
                lambda: self.validator.validate(root),
                "human review date must match",
            )

    def test_completed_metadata_requires_checked_queue_entry(self) -> None:
        with fixture_root() as fixture:
            root = Path(fixture)
            path = root / "design/architecture/decisions/adr-0013-pending.md"
            path.write_text(
                path.read_text(encoding="utf-8").replace(
                    "Human review status: Pending", "Human review status: Completed"
                ),
                encoding="utf-8",
            )
            expect_failure(
                lambda: self.validator.validate(root),
                "completed human review is not backed",
            )

    def test_duplicate_adr_number_is_rejected(self) -> None:
        with fixture_root() as fixture:
            root = Path(fixture)
            reviewed = root / "design/architecture/decisions/adr-0012-reviewed.md"
            duplicate = root / "design/architecture/decisions/adr-0012-duplicate.md"
            duplicate.write_text(reviewed.read_text(encoding="utf-8"), encoding="utf-8")
            expect_failure(
                lambda: self.validator.validate(root),
                "duplicate ADR number 0012",
            )

    def test_duplicate_human_review_field_is_rejected(self) -> None:
        with fixture_root() as fixture:
            root = Path(fixture)
            path = root / "design/architecture/decisions/adr-0012-reviewed.md"
            path.write_text(
                path.read_text(encoding="utf-8")
                + "\n- Human review status: Completed\n",
                encoding="utf-8",
            )
            expect_failure(
                lambda: self.validator.validate(root),
                "duplicate ADR review field 'Human review status'",
            )

    def test_malformed_status_section_is_rejected(self) -> None:
        with fixture_root() as fixture:
            root = Path(fixture)
            path = root / "design/architecture/decisions/adr-0013-pending.md"
            path.write_text(
                path.read_text(encoding="utf-8").replace(
                    "## Status\n\nProposed - Pending Human Review",
                    "## Status\nProposed - Pending Human Review",
                ),
                encoding="utf-8",
            )
            expect_failure(
                lambda: self.validator.validate(root),
                "missing or malformed 'Status' section",
            )

    def test_checked_queue_entry_requires_matching_adr(self) -> None:
        with fixture_root() as fixture:
            root = Path(fixture)
            append_queue_row(
                root,
                "- [x] `TEST-99` — `accepted` on 2026-07-27; "
                "[ADR 0099](../../architecture/decisions/adr-0099-missing.md)",
            )
            expect_failure(
                lambda: self.validator.validate(root),
                r"checked review queue references missing ADRs: \[99\]",
            )

    def test_checked_queue_row_collects_every_adr_link(self) -> None:
        with fixture_root() as fixture:
            root = Path(fixture)
            append_queue_row(
                root,
                "- [x] `TEST-COUPLED` — `revised` on 2026-07-27; "
                "[ADR 0012](../../architecture/decisions/adr-0012-reviewed.md); "
                "[ADR 0014](../../architecture/decisions/adr-0014-other.md)",
            )
            reviews = self.validator.checked_reviews(queue_path(root))
            self.assertEqual(set(reviews), {12, 14})
            self.assertEqual(reviews[14][0].key, "TEST-COUPLED")

    def test_malformed_checked_queue_rows_fail_closed(self) -> None:
        rows = (
            "- [x] `TEST-99`",
            "- [x] `TEST-99` — `revised`",
            "- [x] `TEST-99` — `revised` on 2026-07-27",
        )
        for row in rows:
            with self.subTest(row=row), fixture_root() as fixture:
                append_queue_row(Path(fixture), row)
                expect_failure(
                    lambda: self.validator.checked_reviews(
                        queue_path(Path(fixture))
                    ),
                    "malformed checked review queue row",
                )

        with fixture_root() as fixture:
            append_queue_row(
                Path(fixture),
                "- [x] `TEST-99` — `revised` on 2026-07-27; outcome without link",
            )
            expect_failure(
                lambda: self.validator.checked_reviews(queue_path(Path(fixture))),
                "must contain at least one Markdown outcome link",
            )

    def test_duplicate_checked_review_source_is_rejected(self) -> None:
        with fixture_root() as fixture:
            root = Path(fixture)
            append_queue_row(
                root,
                "- [x] `TEST-01` — `revised` on 2026-07-27; "
                "[duplicate](../../architecture/decisions/adr-0012-reviewed.md)",
            )
            expect_failure(
                lambda: self.validator.checked_reviews(queue_path(root)),
                "ambiguous duplicate checked review source",
            )

    def test_conflicting_duplicate_adr_rows_are_rejected(self) -> None:
        with fixture_root() as fixture:
            root = Path(fixture)
            append_queue_row(
                root,
                "- [x] `TEST-02` — `accepted` on 2026-07-27; "
                "[ADR 0012](../../architecture/decisions/adr-0012-reviewed.md)",
            )
            expect_failure(
                lambda: self.validator.checked_reviews(queue_path(root)),
                "ambiguous duplicate checked review rows for ADR 0012",
            )


if __name__ == "__main__":
    unittest.main()
