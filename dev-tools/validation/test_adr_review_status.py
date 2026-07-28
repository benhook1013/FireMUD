#!/usr/bin/env python3
"""Regression checks for ADR human-review provenance validation."""

from __future__ import annotations

import importlib.util
import re
import shutil
import sys
import tempfile
import textwrap
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "dev-tools/validation/check-adr-review-status.py"


class ValidatorLoadError(AssertionError):
    """Raised when the ADR review status validator cannot be loaded."""

    def __init__(self) -> None:
        super().__init__("could not load ADR review status validator")


def load_validator():
    spec = importlib.util.spec_from_file_location("adr_review_status_validator", SCRIPT)
    if spec is None or spec.loader is None:
        raise ValidatorLoadError()
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
        - Human review date: Not yet reviewed
        - Human review disposition: Pending
        - Review source: `AI-AUTHORED-PENDING`
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


def set_review_status(root: Path, status: str, disposition: str) -> None:
    queue = queue_path(root)
    queue.write_text(
        queue.read_text(encoding="utf-8").replace(
            "`revised`", f"`{disposition.lower()}`", 1
        ),
        encoding="utf-8",
    )
    path = root / "design/architecture/decisions/adr-0012-reviewed.md"
    text = path.read_text(encoding="utf-8")
    text = text.replace("Accepted\n\n## Decision Record", f"{status}\n\n## Decision Record")
    text = text.replace(
        "Human review disposition: Revised",
        f"Human review disposition: {disposition}",
    )
    path.write_text(text, encoding="utf-8")


def expect_failure(test_case: unittest.TestCase, call, expected: str) -> None:
    with test_case.assertRaisesRegex(
        test_case.validator.ValidationError,
        re.escape(expected),
    ):
        call()


class AdrReviewStatusTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.validator = load_validator()

    def test_valid_reviewed_pending_and_legacy_records(self) -> None:
        with fixture_root() as fixture:
            self.validator.validate(Path(fixture))

    def test_section_value_accepts_lf_and_crlf_without_carriage_return(self) -> None:
        for newline in ("\n", "\r\n"):
            with self.subTest(newline=repr(newline)):
                text = f"## Status{newline}{newline}Accepted{newline}"
                self.assertEqual("Accepted", self.validator.section_value(text, "Status"))

    def test_pre_formal_record_requires_completed_metadata_when_checked(self) -> None:
        with fixture_root() as fixture:
            root = Path(fixture)
            append_queue_row(
                root,
                "- [x] `TEST-LEGACY` — `revised` on 2026-07-27; "
                "[ADR 0001](../../architecture/decisions/adr-0001-legacy.md)",
            )
            expect_failure(
                self,
                lambda: self.validator.validate(root),
                "checked human review requires",
            )

    def test_missing_adr_directory_fails_clearly(self) -> None:
        with fixture_root() as fixture:
            root = Path(fixture)
            shutil.rmtree(root / "design/architecture/decisions")
            expect_failure(
                self,
                lambda: self.validator.validate(root),
                "ADR directory missing",
            )

    def test_missing_review_queue_fails_clearly(self) -> None:
        with fixture_root() as fixture:
            root = Path(fixture)
            queue_path(root).unlink()
            expect_failure(
                self,
                lambda: self.validator.validate(root),
                "ADR review queue missing",
            )

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
            expect_failure(self,
                lambda: self.validator.validate(root),
                "terminal ADR status lacks a checked human-review queue entry",
            )

    def test_all_terminal_statuses_require_checked_review(self) -> None:
        for status in (
            "Accepted",
            "Superseded",
            "Withdrawn",
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
                expect_failure(self,
                    lambda root=root: self.validator.validate(root),
                    "terminal ADR status lacks a checked human-review queue entry",
                )

    def test_checked_review_provenance_accepts_all_terminal_statuses(self) -> None:
        for status, disposition in (
            ("Accepted", "Accepted"),
            ("Accepted", "Revised"),
            ("Superseded", "Superseded"),
            ("Withdrawn", "Withdrawn"),
        ):
            with self.subTest(status=status, disposition=disposition), fixture_root() as fixture:
                root = Path(fixture)
                set_review_status(root, status, disposition)
                self.validator.validate(root)

    def test_pre_formal_terminal_statuses_are_exempt_with_unrelated_checked_row(self) -> None:
        for status in (
            "Accepted",
            "Superseded by ADR 0099",
            "Withdrawn",
            "Withdrawn; superseded by ADR 0099",
            "Withdrawn (superseded by ADR 0099)",
        ):
            with self.subTest(status=status), fixture_root() as fixture:
                root = Path(fixture)
                path = root / "design/architecture/decisions/adr-0001-legacy.md"
                path.write_text(
                    path.read_text(encoding="utf-8").replace("Accepted", status),
                    encoding="utf-8",
                )
                checked = self.validator.checked_reviews(queue_path(root))
                self.assertIn(12, checked)
                self.assertNotIn(1, checked)
                self.validator.validate(root)

    def test_unrecognized_and_nonterminal_statuses_are_rejected(self) -> None:
        for status in (
            "Proposed",
            "Deferred",
            "Proposed - Pending Human review",
            "Accepted with caveat",
            "Superseded by ADR 0099",
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
                expect_failure(self,
                    lambda root=root: self.validator.validate(root),
                    "status must be exactly",
                )

    def test_status_and_review_disposition_mapping_is_enforced(self) -> None:
        invalid_pairs = (
            ("Accepted", "Superseded"),
            ("Accepted", "Withdrawn"),
            ("Accepted", "Deferred"),
            ("Superseded", "Revised"),
            ("Withdrawn", "Revised"),
        )
        for status, disposition in invalid_pairs:
            with self.subTest(status=status, disposition=disposition), fixture_root() as fixture:
                root = Path(fixture)
                set_review_status(root, status, disposition)
                expect_failure(
                    self,
                    lambda root=root: self.validator.validate(root),
                    "does not allow human review disposition",
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
            expect_failure(self,
                lambda: self.validator.validate(root),
                "checked human review requires",
            )

    def test_checked_terminal_record_requires_each_review_field(self) -> None:
        with fixture_root() as fixture:
            root = Path(fixture)
            path = root / "design/architecture/decisions/adr-0012-reviewed.md"
            path.write_text(
                path.read_text(encoding="utf-8").replace(
                    "- Human review date: 2026-07-27\n", ""
                ),
                encoding="utf-8",
            )
            expect_failure(self,
                lambda: self.validator.validate(root),
                "human review date must match",
            )

    def test_pending_record_requires_exact_metadata_shape(self) -> None:
        pending_fields = (
            ("Human review status: Pending", "Human review status: Awaiting"),
            ("Human review date: Not yet reviewed", "Human review date: 2026-07-27"),
            (
                "Human review disposition: Pending",
                "Human review disposition: Deferred",
            ),
            (
                "Review source: `AI-AUTHORED-PENDING`",
                "Review source: `AI-REVIEWED`",
            ),
        )
        for current, replacement in pending_fields:
            with self.subTest(current=current), fixture_root() as fixture:
                root = Path(fixture)
                path = root / "design/architecture/decisions/adr-0013-pending.md"
                path.write_text(
                    path.read_text(encoding="utf-8").replace(current, replacement),
                    encoding="utf-8",
                )
                expect_failure(self,
                    lambda root=root: self.validator.validate(root),
                    "pending proposal requires exact",
                )

    def test_pending_record_cannot_be_backed_by_checked_queue(self) -> None:
        with fixture_root() as fixture:
            root = Path(fixture)
            append_queue_row(
                root,
                "- [x] `TEST-PENDING` — `revised` on 2026-07-27; "
                "[ADR 0013](../../architecture/decisions/adr-0013-pending.md)",
            )
            expect_failure(self,
                lambda: self.validator.validate(root),
                "checked human review requires",
            )

    def test_pending_record_rejects_completed_revised_queue_mismatch(self) -> None:
        with fixture_root() as fixture:
            root = Path(fixture)
            append_queue_row(
                root,
                "- [x] `TEST-PENDING` — `revised` on 2026-07-27; "
                "[ADR 0013](../../architecture/decisions/adr-0013-pending.md)",
            )
            path = root / "design/architecture/decisions/adr-0013-pending.md"
            text = path.read_text(encoding="utf-8")
            text = text.replace(
                "Human review status: Pending", "Human review status: Completed"
            )
            text = text.replace(
                "Human review date: Not yet reviewed",
                "Human review date: 2026-07-27",
            )
            text = text.replace(
                "Human review disposition: Pending",
                "Human review disposition: Revised",
            )
            text = text.replace(
                "Review source: `AI-AUTHORED-PENDING`",
                "Review source: `TEST-PENDING`",
            )
            path.write_text(text, encoding="utf-8")
            expect_failure(
                self,
                lambda: self.validator.validate(root),
                "does not allow human review disposition 'Revised'",
            )

    def test_checked_deferred_row_with_exact_adr_provenance_is_rejected(self) -> None:
        with fixture_root() as fixture:
            root = Path(fixture)
            append_queue_row(
                root,
                "- [x] `TEST-DEFERRED` — `deferred` on 2026-07-27; "
                "[ADR 0013](../../architecture/decisions/adr-0013-pending.md)",
            )
            expect_failure(
                self,
                lambda: self.validator.validate(root),
                "checked human review requires",
            )

    def test_checked_review_date_must_match_queue(self) -> None:
        with fixture_root() as fixture:
            root = Path(fixture)
            path = root / "design/architecture/decisions/adr-0012-reviewed.md"
            path.write_text(
                path.read_text(encoding="utf-8").replace(
                    "2026-07-27", "2026-07-26"
                ),
                encoding="utf-8",
            )
            expect_failure(self,
                lambda: self.validator.validate(root),
                "human review date must match",
            )

    def test_checked_review_disposition_must_match_queue(self) -> None:
        with fixture_root() as fixture:
            root = Path(fixture)
            path = root / "design/architecture/decisions/adr-0012-reviewed.md"
            path.write_text(
                path.read_text(encoding="utf-8").replace(
                    "Human review disposition: Revised",
                    "Human review disposition: Accepted",
                ),
                encoding="utf-8",
            )
            expect_failure(self,
                lambda: self.validator.validate(root),
                "human review disposition must match",
            )

    def test_checked_review_source_must_match_queue(self) -> None:
        with fixture_root() as fixture:
            root = Path(fixture)
            path = root / "design/architecture/decisions/adr-0012-reviewed.md"
            path.write_text(
                path.read_text(encoding="utf-8").replace(
                    "`TEST-01`", "`TEST-02`"
                ),
                encoding="utf-8",
            )
            expect_failure(self,
                lambda: self.validator.validate(root),
                "review source keys",
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
            expect_failure(self,
                lambda: self.validator.validate(root),
                "completed human review is not backed",
            )

    def test_unchecked_queue_row_does_not_back_terminal_record(self) -> None:
        with fixture_root() as fixture:
            root = Path(fixture)
            append_queue_row(
                root,
                "- [ ] `TEST-UNCHECKED` — `revised` on 2026-07-27; "
                "[ADR 0013](../../architecture/decisions/adr-0013-pending.md)",
            )
            path = root / "design/architecture/decisions/adr-0013-pending.md"
            path.write_text(
                path.read_text(encoding="utf-8").replace(
                    "Proposed - Pending Human Review", "Accepted"
                ),
                encoding="utf-8",
            )
            expect_failure(
                self,
                lambda: self.validator.validate(root),
                "terminal ADR status lacks a checked human-review queue entry",
            )

    def test_duplicate_adr_number_is_rejected(self) -> None:
        with fixture_root() as fixture:
            root = Path(fixture)
            reviewed = root / "design/architecture/decisions/adr-0012-reviewed.md"
            duplicate = root / "design/architecture/decisions/adr-0012-duplicate.md"
            duplicate.write_text(reviewed.read_text(encoding="utf-8"), encoding="utf-8")
            expect_failure(self,
                lambda: self.validator.validate(root),
                "duplicate ADR number 0012",
            )

    def test_malformed_adr_filename_is_rejected(self) -> None:
        with fixture_root() as fixture:
            root = Path(fixture)
            write(
                root / "design/architecture/decisions/adr-not-number.md",
                """
                # Invalid ADR filename

                ## Status

                Proposed - Pending Human Review

                ## Decision Record

                - Human review status: Pending
                - Human review date: Not yet reviewed
                - Human review disposition: Pending
                - Review source: `AI-AUTHORED-PENDING`
                """,
            )
            expect_failure(
                self,
                lambda: self.validator.validate(root),
                "invalid ADR filename",
            )

    def test_duplicate_human_review_field_is_rejected(self) -> None:
        with fixture_root() as fixture:
            root = Path(fixture)
            path = root / "design/architecture/decisions/adr-0012-reviewed.md"
            text = path.read_text(encoding="utf-8").replace(
                "## Decision Record\n",
                "## Decision Record\n- Human review status: Completed\n",
                1,
            )
            path.write_text(text, encoding="utf-8")
            expect_failure(self,
                lambda: self.validator.validate(root),
                "duplicate ADR review field 'Human review status'",
            )

    def test_review_metadata_is_bounded_to_decision_record(self) -> None:
        with fixture_root() as fixture:
            root = Path(fixture)
            path = root / "design/architecture/decisions/adr-0012-reviewed.md"
            path.write_text(
                path.read_text(encoding="utf-8")
                + "\n## Notes\n\n"
                "- Human review status: Pending\n"
                "- Human review date: not metadata\n",
                encoding="utf-8",
            )
            self.validator.validate(root)

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
            expect_failure(self,
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
            expect_failure(self,
                lambda: self.validator.validate(root),
                "checked review queue references missing ADRs: ['0099']",
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

    def test_checked_queue_accepts_uppercase_x(self) -> None:
        with fixture_root() as fixture:
            root = Path(fixture)
            append_queue_row(
                root,
                "- [X] `TEST-UPPERCASE` — `revised` on 2026-07-27; "
                "[ADR 0012](../../architecture/decisions/adr-0012-reviewed.md)",
            )
            reviews = self.validator.checked_reviews(queue_path(root))
            self.assertEqual(reviews[12][-1].key, "TEST-UPPERCASE")

    def test_checked_queue_rejects_duplicate_adr_links_in_one_row(self) -> None:
        with fixture_root() as fixture:
            root = Path(fixture)
            append_queue_row(
                root,
                "- [x] `TEST-DUP-LINK` — `revised` on 2026-07-27; "
                "[ADR 0012](../../architecture/decisions/adr-0012-reviewed.md); "
                "[ADR 0012](../../architecture/decisions/adr-0012-reviewed.md)",
            )
            expect_failure(
                self,
                lambda: self.validator.checked_reviews(queue_path(root)),
                "contains duplicate ADR outcome links",
            )

    def test_checked_queue_rejects_mismatched_adr_link_provenance(self) -> None:
        with fixture_root() as fixture:
            root = Path(fixture)
            append_queue_row(
                root,
                "- [x] `TEST-MISMATCH` — `revised` on 2026-07-27; "
                "[ADR 0013](../../architecture/decisions/adr-0012-reviewed.md)",
            )
            expect_failure(
                self,
                lambda: self.validator.checked_reviews(queue_path(root)),
                "malformed ADR provenance",
            )

    def test_superseded_rows_keep_reviewed_adr_provenance(self) -> None:
        with fixture_root() as fixture:
            root = Path(fixture)
            append_queue_row(
                root,
                "- [x] `TEST-SUPERSEDED` — `superseded` on 2026-07-27 by "
                "[ADR 0013](../../architecture/decisions/adr-0013-pending.md); "
                "replacement: [replacement ADR 0014](../../architecture/decisions/"
                "adr-0014-replacement.md)",
            )
            reviews = self.validator.checked_reviews(queue_path(root))
            self.assertEqual(reviews[13][0].key, "TEST-SUPERSEDED")
            self.assertNotIn(14, reviews)

    def test_malformed_checked_queue_rows_fail_closed(self) -> None:
        rows = (
            "- [x] `TEST-99`",
            "- [x] `TEST-99` — `revised`",
            "- [x] `TEST-99` — `revised` on 2026-07-27",
        )
        for row in rows:
            with self.subTest(row=row), fixture_root() as fixture:
                root = Path(fixture)
                append_queue_row(root, row)
                expect_failure(self,
                    lambda root=root: self.validator.checked_reviews(queue_path(root)),
                    "malformed checked review queue row",
                )

        with fixture_root() as fixture:
            append_queue_row(
                Path(fixture),
                "- [x] `TEST-99` — `revised` on 2026-07-27; outcome without link",
            )
            expect_failure(self,
                lambda: self.validator.checked_reviews(queue_path(Path(fixture))),
                "must contain at least one Markdown outcome link",
            )

    def test_indented_checked_queue_rows_are_rejected(self) -> None:
        with fixture_root() as fixture:
            root = Path(fixture)
            path = queue_path(root)
            path.write_text(
                path.read_text(encoding="utf-8")
                + "\n  - [x] `TEST-INDENTED` — `revised` on 2026-07-27; "
                "[ADR 0012](../../architecture/decisions/adr-0012-reviewed.md)\n",
                encoding="utf-8",
            )
            expect_failure(self,
                lambda: self.validator.checked_reviews(path),
                "indented checked review queue row",
            )

    def test_duplicate_checked_review_source_is_rejected(self) -> None:
        with fixture_root() as fixture:
            root = Path(fixture)
            append_queue_row(
                root,
                "- [x] `TEST-01` — `revised` on 2026-07-27; "
                "[duplicate](../../architecture/decisions/adr-0012-reviewed.md)",
            )
            expect_failure(self,
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
            expect_failure(self,
                lambda: self.validator.checked_reviews(queue_path(root)),
                "ambiguous duplicate checked review rows for ADR 0012",
            )

    def test_matching_duplicate_adr_rows_require_all_review_sources(self) -> None:
        with fixture_root() as fixture:
            root = Path(fixture)
            append_queue_row(
                root,
                "- [x] `TEST-02` — `revised` on 2026-07-27; "
                "[ADR 0012](../../architecture/decisions/adr-0012-reviewed.md)",
            )
            expect_failure(
                self,
                lambda: self.validator.validate(root),
                "review source keys",
            )

            path = root / "design/architecture/decisions/adr-0012-reviewed.md"
            path.write_text(
                path.read_text(encoding="utf-8").replace(
                    "Review source: `TEST-01`",
                    "Review source: `TEST-01`, `TEST-02`",
                ),
                encoding="utf-8",
            )
            self.validator.validate(root)


if __name__ == "__main__":
    unittest.main()
