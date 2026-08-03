#!/usr/bin/env python3
"""Regression checks for ADR human-review provenance validation."""

from __future__ import annotations

import importlib.util
import re
import shutil
import subprocess
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


class ReplacementCountError(AssertionError):
    """Raised when a fixture replacement does not match exactly once."""

    def __init__(self, old: str, occurrences: int) -> None:
        super().__init__(
            f"expected exactly one occurrence of {old!r}, found {occurrences}"
        )


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
        root / "design/architecture/decisions/README.md",
        """
        # Architecture Decision Records

        ### Supersession Index

        | ADR | Status | Replacement ADR |
        | --- | --- | --- |
        """,
    )
    write(
        root
        / "design/project-management/design-alignment/consequential-decision-inventory.md",
        """
        ## Adversarial Review Queue

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


def checked_reviews(validator, root: Path):
    return validator.checked_reviews(
        queue_path(root),
        root.resolve(),
        root / "design/architecture/decisions",
    )


def append_queue_row(root: Path, row: str) -> None:
    path = queue_path(root)
    path.write_text(
        path.read_text(encoding="utf-8") + f"\n{row}\n",
        encoding="utf-8",
    )


def replace_once(text: str, old: str, new: str) -> str:
    occurrences = text.count(old)
    if occurrences != 1:
        raise ReplacementCountError(old, occurrences)
    return text.replace(old, new, 1)


def append_supersession_index_row(
    root: Path,
    number: int,
    status: str,
    replacement_number: int,
) -> None:
    filenames = {
        1: "adr-0001-legacy.md",
        12: "adr-0012-reviewed.md",
        13: "adr-0013-pending.md",
        14: "adr-0014-superseded.md",
    }
    path = root / "design/architecture/decisions/README.md"
    with path.open("a", encoding="utf-8") as stream:
        stream.write(
            f"| [ADR {number:04d}](./{filenames[number]}) | {status} | "
            f"[ADR {replacement_number:04d}](./{filenames[replacement_number]}) |\n"
        )


def set_review_status(
    root: Path,
    status: str,
    disposition: str,
    supersession: str | None = None,
) -> None:
    queue = queue_path(root)
    queue.write_text(
        replace_once(
            queue.read_text(encoding="utf-8"),
            "`revised`",
            f"`{disposition.lower()}`",
        ),
        encoding="utf-8",
    )
    path = root / "design/architecture/decisions/adr-0012-reviewed.md"
    text = path.read_text(encoding="utf-8")
    text = replace_once(
        text,
        "Accepted\n\n## Decision Record",
        f"{status}\n\n## Decision Record",
    )
    text = replace_once(
        text,
        "Human review disposition: Revised",
        f"Human review disposition: {disposition}",
    )
    if status == "Superseded" and supersession is None:
        supersession = "- Replacement ADR: [ADR 0013](./adr-0013-pending.md)"
    if supersession is not None:
        text += "\n## Supersession\n\n"
        text += f"{supersession}\n"
        replacements = re.findall(r"\[ADR (\d{4})\]", supersession)
        if replacements:
            append_supersession_index_row(
                root,
                12,
                status,
                int(replacements[-1]),
            )
    elif disposition == "Withdrawn":
        text = replace_once(
            text,
            "- Review source: `TEST-01`",
            "- Review source: `TEST-01`\n"
            "- Withdrawal rationale: Withdrawn because the target was not "
            "accepted.",
        )
    path.write_text(text, encoding="utf-8")


def add_formal_superseded_adr(
    root: Path,
    supersession: str | None = "- Replacement ADR: [ADR 0012](./adr-0012-reviewed.md)",
) -> Path:
    append_queue_row(
        root,
        "- [x] `TEST-SUPERSEDED` — `superseded` on 2026-07-27; "
        "[ADR 0014](../../architecture/decisions/adr-0014-superseded.md)",
    )
    text = """
        # ADR 0014

        ## Status

        Superseded

        ## Decision Record

        - Human review status: Completed
        - Human review date: 2026-07-27
        - Human review disposition: Superseded
        - Review source: `TEST-SUPERSEDED`
    """
    text = textwrap.dedent(text).lstrip()
    if supersession is not None:
        text += f"\n## Supersession\n\n{supersession}\n"
        replacements = re.findall(r"\[ADR (\d{4})\]", supersession)
        if replacements:
            append_supersession_index_row(
                root,
                14,
                "Superseded",
                int(replacements[-1]),
            )
    path = root / "design/architecture/decisions/adr-0014-superseded.md"
    write(path, text)
    return path


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

    def test_cli_accepts_valid_fixture_root(self) -> None:
        with fixture_root() as fixture:
            result = subprocess.run(
                [sys.executable, str(SCRIPT), str(fixture)],
                check=False,
                capture_output=True,
                text=True,
            )
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertIn("ADR review status validation passed", result.stdout)

    def test_repository_corpus_passes(self) -> None:
        self.validator.validate(ROOT)

    def test_script_entrypoint_accepts_fixture_root_and_reports_failure(self) -> None:
        with fixture_root() as fixture:
            root = Path(fixture)
            queue_path(root).unlink()
            result = subprocess.run(
                [sys.executable, str(SCRIPT), str(root)],
                check=False,
                capture_output=True,
                text=True,
            )
            self.assertNotEqual(0, result.returncode)
            self.assertIn("ADR review queue missing", result.stderr)

    def test_section_value_accepts_lf_and_crlf_without_carriage_return(self) -> None:
        for newline in ("\n", "\r\n"):
            with self.subTest(newline=repr(newline)):
                text = f"## Status{newline}{newline}Accepted{newline}"
                self.assertEqual(
                    "Accepted", self.validator.section_value(text, "Status")
                )

    def test_section_value_treats_whitespace_separator_as_blank(self) -> None:
        text = "## Status\n \t \nAccepted\n"
        self.assertEqual("Accepted", self.validator.section_value(text, "Status"))

    def test_section_value_rejects_whitespace_only_value(self) -> None:
        text = "## Status\n\n \t \n"
        expect_failure(
            self,
            lambda: self.validator.section_value(text, "Status"),
            "missing or malformed 'Status' section",
        )

    def test_section_value_ignores_fenced_status_examples(self) -> None:
        text = (
            "## Status\n\nAccepted\n\n"
            "```text\n## Status\n\nWithdrawn\n```\n"
        )
        self.assertEqual("Accepted", self.validator.section_value(text, "Status"))

    def test_section_value_requires_exactly_one_status_heading(self) -> None:
        text = "## Status\n\nAccepted\n\n## Status\n\nAccepted\n"
        expect_failure(
            self,
            lambda: self.validator.section_value(text, "Status"),
            "missing or malformed 'Status' section",
        )

    def test_markdown_section_stops_at_visible_level_one_and_two_headings(self) -> None:
        for terminator in ("# Next Section", "## Next Section"):
            with self.subTest(terminator=terminator):
                text = (
                    "## Decision Record\n\n"
                    "- Human review status: Completed\n\n"
                    "```text\n"
                    "# Fenced heading\n"
                    "## Fenced heading\n"
                    "not visible\n"
                    "```\n\n"
                    f"{terminator}\n\n"
                    "not part of the section\n"
                )
                section = self.validator.markdown_section(text, "Decision Record")
                self.assertIn("- Human review status: Completed", section)
                self.assertNotIn("Fenced heading", section)
                self.assertNotIn("not part of the section", section)

    def test_review_queue_requires_exactly_one_queue_heading(self) -> None:
        with fixture_root() as fixture:
            root = Path(fixture)
            path = queue_path(root)
            path.write_text(
                path.read_text(encoding="utf-8") + "\n## Adversarial Review Queue\n",
                encoding="utf-8",
            )
            expect_failure(
                self,
                lambda: checked_reviews(self.validator, root),
                "expected exactly one 'Adversarial Review Queue' section, found 2",
            )

    def test_review_queue_requires_a_visible_queue_heading(self) -> None:
        with fixture_root() as fixture:
            root = Path(fixture)
            path = queue_path(root)
            path.write_text(
                path.read_text(encoding="utf-8").replace(
                    "## Adversarial Review Queue\n", "", 1
                ),
                encoding="utf-8",
            )
            expect_failure(
                self,
                lambda: checked_reviews(self.validator, root),
                "missing 'Adversarial Review Queue' section",
            )

    def test_fenced_queue_heading_does_not_count_as_duplicate(self) -> None:
        with fixture_root() as fixture:
            root = Path(fixture)
            path = queue_path(root)
            path.write_text(
                path.read_text(encoding="utf-8")
                + "\n```text\n## Adversarial Review Queue\n```\n",
                encoding="utf-8",
            )
            self.validator.validate(root)

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

    def test_pre_formal_legacy_status_with_checked_row_is_rejected(self) -> None:
        with fixture_root() as fixture:
            root = Path(fixture)
            append_queue_row(
                root,
                "- [x] `TEST-LEGACY` — `superseded` on 2026-07-27; "
                "[ADR 0001](../../architecture/decisions/adr-0001-legacy.md)",
            )
            path = root / "design/architecture/decisions/adr-0001-legacy.md"
            path.write_text(
                path.read_text(encoding="utf-8").replace(
                    "Accepted", "Superseded by ADR 0012"
                ),
                encoding="utf-8",
            )
            expect_failure(
                self,
                lambda: self.validator.validate(root),
                "status must be exactly one of",
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
            expect_failure(
                self,
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
                expect_failure(
                    self,
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
            with (
                self.subTest(status=status, disposition=disposition),
                fixture_root() as fixture,
            ):
                root = Path(fixture)
                set_review_status(root, status, disposition)
                self.validator.validate(root)

    def test_pre_formal_terminal_statuses_are_exempt_with_unrelated_checked_row(
        self,
    ) -> None:
        for status in (
            "Accepted",
            "Superseded by ADR 0099",
            "Withdrawn",
            "Withdrawn; superseded by ADR 0099",
            "Withdrawn (superseded by ADR 0099)",
            "Superseded by [ADR 0012](./adr-0012-reviewed.md)",
            "Withdrawn; superseded by [ADR 0012](./adr-0012-reviewed.md)",
            "Withdrawn (superseded by [ADR 0012](./adr-0012-reviewed.md))",
        ):
            with self.subTest(status=status), fixture_root() as fixture:
                root = Path(fixture)
                path = root / "design/architecture/decisions/adr-0001-legacy.md"
                path.write_text(
                    path.read_text(encoding="utf-8").replace("Accepted", status),
                    encoding="utf-8",
                )
                legacy_replacement = re.search(r"\[ADR (\d{4})\]", status)
                if legacy_replacement is not None:
                    append_supersession_index_row(
                        root,
                        1,
                        "Withdrawn" if status.startswith("Withdrawn") else "Superseded",
                        int(legacy_replacement.group(1)),
                    )
                checked = checked_reviews(self.validator, root)
                self.assertIn(12, checked)
                self.assertNotIn(1, checked)
                self.validator.validate(root)

    def test_legacy_composite_terminal_statuses_use_normalized_supersession_kind(
        self,
    ) -> None:
        for status in (
            "Superseded by [ADR 0012](./adr-0012-reviewed.md)",
            "Withdrawn (superseded by [ADR 0012](./adr-0012-reviewed.md))",
        ):
            with self.subTest(status=status), fixture_root() as fixture:
                root = Path(fixture)
                path = root / "design/architecture/decisions/adr-0001-legacy.md"
                path.write_text(
                    path.read_text(encoding="utf-8").replace("Accepted", status)
                    + "\n## Supersession\n\n"
                    "- Replacement ADR: [ADR 0012](./adr-0012-reviewed.md)\n",
                    encoding="utf-8",
                )
                append_supersession_index_row(
                    root,
                    1,
                    "Withdrawn" if status.startswith("Withdrawn") else "Superseded",
                    12,
                )
                self.validator.validate(root)

    def test_formal_superseded_record_requires_supersession_entry(self) -> None:
        with fixture_root() as fixture:
            root = Path(fixture)
            add_formal_superseded_adr(root, supersession=None)
            expect_failure(
                self,
                lambda: self.validator.validate(root),
                "formal Superseded ADR requires exactly one 'Replacement ADR' entry",
            )

    def test_supersession_section_requires_formal_terminal_status(self) -> None:
        with fixture_root() as fixture:
            root = Path(fixture)
            path = root / "design/architecture/decisions/adr-0012-reviewed.md"
            path.write_text(
                path.read_text(encoding="utf-8")
                + "\n## Supersession\n\n"
                "- Replacement ADR: [ADR 0013](./adr-0013-pending.md)\n",
                encoding="utf-8",
            )
            expect_failure(
                self,
                lambda: self.validator.validate(root),
                "'Supersession' section is only valid for an ADR with formal status "
                "'Superseded' or 'Withdrawn'",
            )

    def test_supersession_section_accepts_only_one_visible_valid_entry(self) -> None:
        with fixture_root() as fixture:
            root = Path(fixture)
            entry = "- Replacement ADR: [ADR 0012](./adr-0012-reviewed.md)"
            path = add_formal_superseded_adr(
                root,
                "```text\n"
                "## Supersession\n\n"
                "- Replacement ADR: [ADR 0013](./adr-0013-pending.md)\n"
                "```\n"
                f"{entry}",
            )
            self.validator.validate(root)

            path.write_text(
                path.read_text(encoding="utf-8").replace(
                    entry,
                    f"{entry}\n{entry}",
                ),
                encoding="utf-8",
            )
            expect_failure(
                self,
                lambda: self.validator.validate(root),
                "'Supersession' section must contain exactly one valid 'Replacement ADR' entry",
            )

    def test_supersession_section_requires_exact_label(self) -> None:
        with fixture_root() as fixture:
            root = Path(fixture)
            add_formal_superseded_adr(
                root,
                "- Replacement ADR: [replacement ADR 0012](./adr-0012-reviewed.md)",
            )
            expect_failure(
                self,
                lambda: self.validator.validate(root),
                "'Supersession' section must contain exactly one valid 'Replacement ADR' entry",
            )

    def test_supersession_section_requires_matching_target_number(self) -> None:
        with fixture_root() as fixture:
            root = Path(fixture)
            add_formal_superseded_adr(
                root,
                "- Replacement ADR: [ADR 0013](./adr-0012-reviewed.md)",
            )
            expect_failure(
                self,
                lambda: self.validator.validate(root),
                "replacement ADR 0013 does not match target",
            )

    def test_supersession_section_rejects_self_reference_before_target_validation(
        self,
    ) -> None:
        with fixture_root() as fixture:
            root = Path(fixture)
            add_formal_superseded_adr(
                root,
                "- Replacement ADR: [ADR 0014](./missing-replacement.md)",
            )
            expect_failure(
                self,
                lambda: self.validator.validate(root),
                "Supersession replacement ADR must not self-reference ADR 0014",
            )

    def test_supersession_section_requires_canonical_target_directory(self) -> None:
        with fixture_root() as fixture:
            root = Path(fixture)
            write(
                root / "design/architecture/adr-0012-outside.md",
                "# Outside ADR\n",
            )
            add_formal_superseded_adr(
                root,
                "- Replacement ADR: [ADR 0012](../adr-0012-outside.md)",
            )
            expect_failure(
                self,
                lambda: self.validator.validate(root),
                "must target the canonical ADR directory",
            )

    def test_supersession_index_matches_adr_status_and_replacement(self) -> None:
        with fixture_root() as fixture:
            root = Path(fixture)
            add_formal_superseded_adr(root)
            self.validator.validate(root)

    def test_supersession_index_rows_requires_one_heading(self) -> None:
        for mutation, expected in (
            (
                lambda text: text.replace("### Supersession Index\n", "", 1),
                "expected exactly one 'Supersession Index' section, found 0",
            ),
            (
                lambda text: text + "\n### Supersession Index\n",
                "expected exactly one 'Supersession Index' section, found 2",
            ),
        ):
            with self.subTest(expected=expected), fixture_root() as fixture:
                root = Path(fixture)
                path = root / "design/architecture/decisions/README.md"
                path.write_text(
                    mutation(path.read_text(encoding="utf-8")),
                    encoding="utf-8",
                )
                expect_failure(
                    self,
                    lambda path=path: self.validator.supersession_index_rows(
                        path, path.parent
                    ),
                    expected,
                )

    def test_supersession_index_rows_requires_canonical_header(self) -> None:
        with fixture_root() as fixture:
            root = Path(fixture)
            path = root / "design/architecture/decisions/README.md"
            path.write_text(
                replace_once(
                    path.read_text(encoding="utf-8"),
                    "| ADR | Status | Replacement ADR |",
                    "| ADR | Status | Replacement |",
                ),
                encoding="utf-8",
            )
            expect_failure(
                self,
                lambda: self.validator.supersession_index_rows(path, path.parent),
                "Supersession Index header must be exactly "
                "'| ADR | Status | Replacement ADR |'",
            )

    def test_supersession_index_rows_requires_canonical_separator(self) -> None:
        with fixture_root() as fixture:
            root = Path(fixture)
            path = root / "design/architecture/decisions/README.md"
            path.write_text(
                replace_once(
                    path.read_text(encoding="utf-8"),
                    "| --- | --- | --- |",
                    "| -- | --- | --- |",
                ),
                encoding="utf-8",
            )
            expect_failure(
                self,
                lambda: self.validator.supersession_index_rows(path, path.parent),
                "malformed Supersession Index separator row",
            )

    def test_supersession_index_rows_requires_three_cell_rows(self) -> None:
        with fixture_root() as fixture:
            root = Path(fixture)
            path = root / "design/architecture/decisions/README.md"
            path.write_text(
                replace_once(
                    path.read_text(encoding="utf-8"),
                    "| --- | --- | --- |",
                    "| --- | --- | --- |\n"
                    "| [ADR 0012](./adr-0012-reviewed.md) | Superseded |",
                ),
                encoding="utf-8",
            )
            expect_failure(
                self,
                lambda: self.validator.supersession_index_rows(path, path.parent),
                "Supersession Index row at line 7 must have exactly three cells",
            )

    def test_supersession_index_rows_requires_exact_source_link(self) -> None:
        with fixture_root() as fixture:
            root = Path(fixture)
            path = root / "design/architecture/decisions/README.md"
            path.write_text(
                replace_once(
                    path.read_text(encoding="utf-8"),
                    "| --- | --- | --- |",
                    "| --- | --- | --- |\n"
                    "| Not an ADR link | Superseded | "
                    "[ADR 0013](./adr-0013-pending.md) |",
                ),
                encoding="utf-8",
            )
            expect_failure(
                self,
                lambda: self.validator.supersession_index_rows(path, path.parent),
                "Supersession Index row at line 7 must use an exact "
                "[ADR NNNN] source link",
            )

    def test_supersession_index_rows_rejects_duplicate_source_rows(self) -> None:
        with fixture_root() as fixture:
            root = Path(fixture)
            path = root / "design/architecture/decisions/README.md"
            row = (
                "| [ADR 0012](./adr-0012-reviewed.md) | Superseded | "
                "[ADR 0013](./adr-0013-pending.md) |"
            )
            path.write_text(
                replace_once(
                    path.read_text(encoding="utf-8"),
                    "| --- | --- | --- |",
                    f"| --- | --- | --- |\n{row}\n{row}",
                ),
                encoding="utf-8",
            )
            expect_failure(
                self,
                lambda: self.validator.supersession_index_rows(path, path.parent),
                "duplicate Supersession Index entry for ADR 0012",
            )

    def test_supersession_index_rejects_missing_readme_row(self) -> None:
        with fixture_root() as fixture:
            root = Path(fixture)
            add_formal_superseded_adr(root)
            path = root / "design/architecture/decisions/README.md"
            row = "| [ADR 0014](./adr-0014-superseded.md) | Superseded | [ADR 0012](./adr-0012-reviewed.md) |\n"
            path.write_text(
                path.read_text(encoding="utf-8").replace(row, "", 1),
                encoding="utf-8",
            )
            expect_failure(
                self,
                lambda: self.validator.validate(root),
                "Supersession Index is missing ADR entries with replacement sections: ['0014']",
            )

    def test_supersession_index_rejects_unexpected_readme_row(self) -> None:
        with fixture_root() as fixture:
            root = Path(fixture)
            append_supersession_index_row(root, 12, "Superseded", 13)
            expect_failure(
                self,
                lambda: self.validator.validate(root),
                "Supersession Index contains ADRs without a validated replacement section: ['0012']",
            )

    def test_supersession_index_rejects_status_drift(self) -> None:
        with fixture_root() as fixture:
            root = Path(fixture)
            add_formal_superseded_adr(root)
            path = root / "design/architecture/decisions/README.md"
            path.write_text(
                path.read_text(encoding="utf-8").replace(
                    "| [ADR 0014](./adr-0014-superseded.md) | Superseded |",
                    "| [ADR 0014](./adr-0014-superseded.md) | Withdrawn |",
                ),
                encoding="utf-8",
            )
            expect_failure(
                self,
                lambda: self.validator.validate(root),
                "Supersession Index status for ADR 0014 is 'Withdrawn', "
                "but the ADR status is 'Superseded'",
            )

    def test_supersession_index_rejects_replacement_drift(self) -> None:
        with fixture_root() as fixture:
            root = Path(fixture)
            add_formal_superseded_adr(root)
            path = root / "design/architecture/decisions/README.md"
            path.write_text(
                path.read_text(encoding="utf-8").replace(
                    "[ADR 0012](./adr-0012-reviewed.md)",
                    "[ADR 0013](./adr-0013-pending.md)",
                ),
                encoding="utf-8",
            )
            expect_failure(
                self,
                lambda: self.validator.validate(root),
                "Supersession Index replacement for ADR 0014 is ADR 0013, "
                "but the ADR Supersession section names ADR 0012",
            )

    def test_formal_withdrawn_record_may_omit_supersession_entry(self) -> None:
        with fixture_root() as fixture:
            root = Path(fixture)
            set_review_status(root, "Withdrawn", "Withdrawn")
            self.validator.validate(root)

    def test_formal_withdrawn_record_requires_non_empty_rationale_without_supersession(
        self,
    ) -> None:
        with fixture_root() as fixture:
            root = Path(fixture)
            set_review_status(root, "Withdrawn", "Withdrawn")
            path = root / "design/architecture/decisions/adr-0012-reviewed.md"
            path.write_text(
                path.read_text(encoding="utf-8").replace(
                    "- Withdrawal rationale: Withdrawn because the target was not accepted.",
                    "- Withdrawal rationale:   \t",
                ),
                encoding="utf-8",
            )
            expect_failure(
                self,
                lambda: self.validator.validate(root),
                "requires a non-empty normalized 'Withdrawal rationale'",
            )

    def test_formal_withdrawn_record_normalizes_rationale_whitespace(self) -> None:
        with fixture_root() as fixture:
            root = Path(fixture)
            set_review_status(root, "Withdrawn", "Withdrawn")
            path = root / "design/architecture/decisions/adr-0012-reviewed.md"
            text = path.read_text(encoding="utf-8").replace(
                "- Withdrawal rationale: Withdrawn because the target was not accepted.",
                "- Withdrawal rationale:  Withdrawn   because the target was not accepted.  ",
            )
            path.write_text(text, encoding="utf-8")
            self.validator.validate(root)
            self.assertEqual(
                "Withdrawn because the target was not accepted.",
                self.validator.review_fields(text)["Withdrawal rationale"],
            )

    def test_formal_withdrawn_record_accepts_valid_supersession_entry(self) -> None:
        with fixture_root() as fixture:
            root = Path(fixture)
            set_review_status(
                root,
                "Withdrawn",
                "Withdrawn",
                "- Replacement ADR: [ADR 0013](./adr-0013-pending.md)",
            )
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
                expect_failure(
                    self,
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
            with (
                self.subTest(status=status, disposition=disposition),
                fixture_root() as fixture,
            ):
                root = Path(fixture)
                set_review_status(root, status, disposition)
                expect_failure(
                    self,
                    lambda root=root: self.validator.validate(root),
                    (
                        "checked deferred review row"
                        if disposition == "Deferred"
                        else "does not allow human review disposition"
                    ),
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
                self,
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
            expect_failure(
                self,
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
                expect_failure(
                    self,
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
            expect_failure(
                self,
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
                "checked deferred review row",
            )

    def test_checked_non_deferred_row_requires_exact_adr_provenance(self) -> None:
        with fixture_root() as fixture:
            root = Path(fixture)
            append_queue_row(
                root,
                "- [x] `TEST-NOTES` — `accepted` on 2026-07-27; "
                "[notes](https://example.com)",
            )
            expect_failure(
                self,
                lambda: self.validator.validate(root),
                "must contain at least one exact [ADR NNNN] outcome link",
            )

    def test_checked_superseded_row_requires_exact_adr_provenance(self) -> None:
        with fixture_root() as fixture:
            root = Path(fixture)
            append_queue_row(
                root,
                "- [x] `TEST-SUPERSEDED` — `superseded` on 2026-07-27 by "
                "[notes](https://example.com)",
            )
            expect_failure(
                self,
                lambda: checked_reviews(self.validator, root),
                "must contain at least one exact [ADR NNNN] outcome link",
            )

    def test_superseded_scan_alias_links_replacements_without_provenance(self) -> None:
        with fixture_root() as fixture:
            root = Path(fixture)
            write(
                root / "design/architecture/decisions/adr-0014-replacement.md",
                "# ADR 0014\n",
            )
            append_queue_row(
                root,
                "- [x] `MS-AA-TOKEN-REVOCATION` — `superseded` on 2026-07-27 by "
                "[replacement ADR 0014](../../architecture/decisions/"
                "adr-0014-replacement.md); retained as a historical "
                "service-scan alias.",
            )
            reviews = checked_reviews(self.validator, root)
            self.assertEqual({"TEST-01"}, {review.key for review in reviews[12]})
            self.assertNotIn(14, reviews)

    def test_superseded_scan_alias_requires_replacement_links(self) -> None:
        with fixture_root() as fixture:
            root = Path(fixture)
            append_queue_row(
                root,
                "- [x] `MS-AA-TOKEN-REVOCATION` — `superseded` on 2026-07-27; "
                "retained as a historical service-scan alias.",
            )
            expect_failure(
                self,
                lambda: checked_reviews(self.validator, root),
                "must contain replacement-decision Markdown links",
            )

    def test_superseded_scan_alias_rejects_replacement_adr_number_mismatch(
        self,
    ) -> None:
        with fixture_root() as fixture:
            root = Path(fixture)
            write(
                root / "design/architecture/decisions/adr-0014-replacement.md",
                "# ADR 0014\n",
            )
            append_queue_row(
                root,
                "- [x] `MS-AA-TOKEN-REVOCATION` — `superseded` on 2026-07-27 by "
                "[replacement ADR 0015](../../architecture/decisions/"
                "adr-0014-replacement.md); retained as a historical "
                "service-scan alias.",
            )
            expect_failure(
                self,
                lambda: checked_reviews(self.validator, root),
                "replacement ADR 0015 does not match target",
            )

    def test_superseded_scan_alias_rejects_replacement_outside_adr_directory(
        self,
    ) -> None:
        with fixture_root() as fixture:
            root = Path(fixture)
            write(
                root / "design/architecture/adr-0014-outside.md",
                "# ADR 0014\n",
            )
            append_queue_row(
                root,
                "- [x] `MS-AA-TOKEN-REVOCATION` — `superseded` on 2026-07-27 by "
                "[replacement ADR 0014](../../architecture/"
                "adr-0014-outside.md); retained as a historical "
                "service-scan alias.",
            )
            expect_failure(
                self,
                lambda: checked_reviews(self.validator, root),
                "must target the canonical ADR directory",
            )

    def test_superseded_scan_alias_accepts_decision_key_target(self) -> None:
        with fixture_root() as fixture:
            root = Path(fixture)
            write(
                root / "design/architecture/decision-notes.md",
                "# Decision notes\n",
            )
            append_queue_row(
                root,
                "- [x] `MS-AA-TOKEN-REVOCATION` — `superseded` on 2026-07-27 by "
                "[MS-AA-LOGIN-FACTORS](../../architecture/decision-notes.md); "
                "retained as a historical service-scan alias.",
            )
            reviews = checked_reviews(self.validator, root)
            self.assertEqual({"TEST-01"}, {review.key for review in reviews[12]})

    def test_superseded_scan_alias_rejects_missing_decision_key_target(self) -> None:
        with fixture_root() as fixture:
            root = Path(fixture)
            append_queue_row(
                root,
                "- [x] `MS-AA-TOKEN-REVOCATION` — `superseded` on 2026-07-27 by "
                "[MS-AA-LOGIN-FACTORS](../../architecture/missing-decision.md); "
                "retained as a historical service-scan alias.",
            )
            expect_failure(
                self,
                lambda: checked_reviews(self.validator, root),
                "must target an existing Markdown decision document",
            )

    def test_superseded_scan_alias_rejects_non_markdown_decision_key_target(
        self,
    ) -> None:
        with fixture_root() as fixture:
            root = Path(fixture)
            write(root / "design/architecture/decision-notes.txt", "notes\n")
            append_queue_row(
                root,
                "- [x] `MS-AA-TOKEN-REVOCATION` — `superseded` on 2026-07-27 by "
                "[MS-AA-LOGIN-FACTORS](../../architecture/decision-notes.txt); "
                "retained as a historical service-scan alias.",
            )
            expect_failure(
                self,
                lambda: checked_reviews(self.validator, root),
                "must target an existing Markdown decision document",
            )

    def test_superseded_scan_alias_rejects_decision_key_target_outside_root(
        self,
    ) -> None:
        with fixture_root() as fixture:
            root = Path(fixture)
            outside = root.parent / f"{root.name}-outside.md"
            outside.write_text("# Outside\n", encoding="utf-8")
            try:
                append_queue_row(
                    root,
                    "- [x] `MS-AA-TOKEN-REVOCATION` — `superseded` on 2026-07-27 by "
                    "[MS-AA-LOGIN-FACTORS](../../../../"
                    f"{outside.name}); retained as a historical service-scan alias.",
                )
                expect_failure(
                    self,
                    lambda: checked_reviews(self.validator, root),
                    "must target an existing Markdown decision document",
                )
            finally:
                outside.unlink(missing_ok=True)

    def test_superseded_scan_alias_rejects_arbitrary_replacement_label(self) -> None:
        with fixture_root() as fixture:
            root = Path(fixture)
            append_queue_row(
                root,
                "- [x] `MS-AA-TOKEN-REVOCATION` — `superseded` on 2026-07-27 by "
                "[notes](https://example.com); retained as a historical "
                "service-scan alias.",
            )
            expect_failure(
                self,
                lambda: checked_reviews(self.validator, root),
                "has non-replacement link label 'notes'",
            )

    def test_superseded_scan_alias_rejects_exact_adr_provenance_label(self) -> None:
        with fixture_root() as fixture:
            root = Path(fixture)
            write(
                root / "design/architecture/decisions/adr-0014-replacement.md",
                "# ADR 0014\n",
            )
            append_queue_row(
                root,
                "- [x] `MS-AA-TOKEN-REVOCATION` — `superseded` on 2026-07-27 by "
                "[ADR 0014](../../architecture/decisions/"
                "adr-0014-replacement.md); retained as a historical "
                "service-scan alias.",
            )
            expect_failure(
                self,
                lambda: checked_reviews(self.validator, root),
                "must not use exact [ADR NNNN] provenance labels",
            )

    def test_superseded_alias_marker_does_not_exempt_non_scan_key(self) -> None:
        with fixture_root() as fixture:
            root = Path(fixture)
            append_queue_row(
                root,
                "- [x] `TEST-ALIAS` — `superseded` on 2026-07-27 by "
                "[replacement](https://example.com); retained as a historical "
                "service-scan alias.",
            )
            expect_failure(
                self,
                lambda: checked_reviews(self.validator, root),
                "must contain at least one exact [ADR NNNN] outcome link",
            )

    def test_checked_row_inside_fenced_example_is_ignored(self) -> None:
        with fixture_root() as fixture:
            root = Path(fixture)
            append_queue_row(
                root,
                "```text\n"
                "- [x] `FAKE-ROW` — `accepted` on 2026-07-27; "
                "[ADR 0013](../../architecture/decisions/adr-0013-pending.md)\n"
                "```",
            )
            self.validator.validate(root)

    def test_decision_record_parser_ignores_fenced_headings_and_fields(self) -> None:
        with fixture_root() as fixture:
            root = Path(fixture)
            path = root / "design/architecture/decisions/adr-0012-reviewed.md"
            text = path.read_text(encoding="utf-8").replace(
                "## Decision Record\n",
                "## Decision Record\n\n"
                "```text\n"
                "## Notes\n"
                "- Human review status: Pending\n"
                "```\n",
                1,
            )
            path.write_text(text, encoding="utf-8")
            self.assertEqual(
                "Completed",
                self.validator.review_fields(text)["Human review status"],
            )
            self.validator.validate(root)

    def test_fenced_decision_record_heading_does_not_select_review_fields(self) -> None:
        with fixture_root() as fixture:
            root = Path(fixture)
            path = root / "design/architecture/decisions/adr-0001-legacy.md"
            path.write_text(
                "# ADR 0001\n\n"
                "## Status\n\n"
                "Accepted\n\n"
                "```text\n"
                "## Decision Record\n\n"
                "- Human review status: Completed\n"
                "```\n",
                encoding="utf-8",
            )
            self.validator.validate(root)

    def test_review_fields_ignores_fenced_metadata_examples(self) -> None:
        text = (
            "## Decision Record\n\n"
            "```text\n"
            "- Human review status: Pending\n"
            "```\n\n"
            "- Human review status: Completed\n"
        )
        self.assertEqual(
            {"Human review status": "Completed"},
            self.validator.review_fields(text),
        )

    def test_nested_fence_with_info_string_does_not_expose_checked_row(self) -> None:
        with fixture_root() as fixture:
            root = Path(fixture)
            append_queue_row(
                root,
                "````text\n"
                "```text\n"
                "- [x] `FAKE-NESTED` — `accepted` on 2026-07-27; "
                "[ADR 0013](../../architecture/decisions/adr-0013-pending.md)\n"
                "````text\n"
                "- [x] `FAKE-SAME-LENGTH` — `accepted` on 2026-07-27; "
                "[ADR 0013](../../architecture/decisions/adr-0013-pending.md)\n"
                "````",
            )
            self.validator.validate(root)

    def test_indented_fence_like_line_opens_a_fence(self) -> None:
        with fixture_root() as fixture:
            root = Path(fixture)
            append_queue_row(
                root,
                "    ```text\n"
                "- [x] `FAKE-INDENTED-FENCE` — `accepted` on 2026-07-27; "
                "[ADR 0013](../../architecture/decisions/adr-0013-pending.md)",
            )
            expect_failure(
                self,
                lambda: checked_reviews(self.validator, root),
                "unterminated code fence opened at line",
            )

    def test_fenced_level_two_heading_does_not_end_review_queue(self) -> None:
        with fixture_root() as fixture:
            root = Path(fixture)
            append_queue_row(
                root,
                "```text\n"
                "## Example heading inside the fenced block\n"
                "```\n"
                "- [x] `TEST-AFTER-FENCE` — `revised` on 2026-07-27; "
                "[ADR 0012](../../architecture/decisions/adr-0012-reviewed.md)",
            )
            reviews = checked_reviews(self.validator, root)
            self.assertIn(
                "TEST-AFTER-FENCE",
                {review.key for review in reviews[12]},
            )

    def test_scan_review_queue_ignores_headings_inside_fences(self) -> None:
        with fixture_root() as fixture:
            root = Path(fixture)
            append_queue_row(
                root,
                "```text\n## Example heading inside the fenced block\n```\n## Notes\n",
            )
            queue = queue_path(root)
            lines = queue.read_text(encoding="utf-8").splitlines()
            queue_start = lines.index("## Adversarial Review Queue")
            notes_heading = lines.index("## Notes")
            self.assertEqual(
                notes_heading,
                self.validator.scan_review_queue(lines, queue_start).end,
            )

    def test_unterminated_code_fence_fails_closed_with_path_and_opening_line(
        self,
    ) -> None:
        with fixture_root() as fixture:
            root = Path(fixture)
            queue = queue_path(root)
            expected_queue_path = queue.resolve()
            queue.write_text(
                queue.read_text(encoding="utf-8") + "\n```text\n",
                encoding="utf-8",
            )
            with self.assertRaises(self.validator.ValidationError) as raised:
                self.validator.validate(root)
            message = str(raised.exception)
            self.assertIn(str(expected_queue_path), message)
            self.assertRegex(message, r"unterminated code fence opened at line [0-9]+")

    def test_unterminated_adr_fence_is_rejected_before_decision_record_detection(
        self,
    ) -> None:
        with fixture_root() as fixture:
            root = Path(fixture)
            path = root / "design/architecture/decisions/adr-0013-pending.md"
            path.write_text(
                path.read_text(encoding="utf-8").replace(
                    "## Decision Record\n",
                    "```text\n## Decision Record\n",
                    1,
                ),
                encoding="utf-8",
            )
            expect_failure(
                self,
                lambda: self.validator.validate(root),
                "unterminated code fence opened at line",
            )

    def test_checked_review_target_must_be_in_canonical_adr_directory(self) -> None:
        with fixture_root() as fixture:
            root = Path(fixture)
            outside = root / "design/architecture/adr-0013-outside.md"
            outside.write_text("# Outside\n", encoding="utf-8")
            append_queue_row(
                root,
                "- [x] `TEST-OUTSIDE` — `accepted` on 2026-07-27; "
                "[ADR 0013](../../architecture/adr-0013-outside.md)",
            )
            expect_failure(
                self,
                lambda: self.validator.validate(root),
                "canonical ADR directory",
            )

    def test_checked_review_rejects_absolute_target(self) -> None:
        with fixture_root() as fixture:
            root = Path(fixture)
            append_queue_row(
                root,
                "- [x] `TEST-ABSOLUTE` — `accepted` on 2026-07-27; "
                "[ADR 0013](/design/architecture/decisions/adr-0013-pending.md)",
            )
            expect_failure(
                self,
                lambda: checked_reviews(
                    self.validator,
                    root,
                ),
                "canonical ADR directory",
            )

    def test_checked_review_normalizes_query_and_anchor_suffixes(self) -> None:
        with fixture_root() as fixture:
            root = Path(fixture)
            for index, suffix in enumerate(("?view=full#status", "#status?view=full")):
                append_queue_row(
                    root,
                    f"- [x] `TEST-SUFFIX-{index}` — `accepted` on 2026-07-27; "
                    "[ADR 0013](../../architecture/decisions/"
                    f"adr-0013-pending.md{suffix})",
                )
            reviews = checked_reviews(
                self.validator,
                root,
            )
            self.assertEqual(
                {"TEST-SUFFIX-0", "TEST-SUFFIX-1"},
                {review.key for review in reviews[13]},
            )

    def test_checked_review_date_must_match_queue(self) -> None:
        with fixture_root() as fixture:
            root = Path(fixture)
            path = root / "design/architecture/decisions/adr-0012-reviewed.md"
            path.write_text(
                path.read_text(encoding="utf-8").replace("2026-07-27", "2026-07-26"),
                encoding="utf-8",
            )
            expect_failure(
                self,
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
            expect_failure(
                self,
                lambda: self.validator.validate(root),
                "human review disposition must match",
            )

    def test_checked_review_source_must_match_queue(self) -> None:
        with fixture_root() as fixture:
            root = Path(fixture)
            path = root / "design/architecture/decisions/adr-0012-reviewed.md"
            path.write_text(
                path.read_text(encoding="utf-8").replace("`TEST-01`", "`TEST-02`"),
                encoding="utf-8",
            )
            expect_failure(
                self,
                lambda: self.validator.validate(root),
                "review source keys",
            )

    def test_review_source_parser_accepts_exact_comma_space_separators(self) -> None:
        self.assertEqual(
            ("TEST-01", "TEST-02"),
            self.validator.parse_review_source("`TEST-01`, `TEST-02`"),
        )

    def test_review_source_parser_rejects_non_exact_separators(self) -> None:
        invalid_sources = (
            "`TEST-01`,`TEST-02`",
            "`TEST-01`,  `TEST-02`",
            "`TEST-01` , `TEST-02`",
            "`TEST-01` `TEST-02`",
            "`TEST-01`,",
        )
        for source in invalid_sources:
            with self.subTest(source=source):
                expect_failure(
                    self,
                    lambda source=source: self.validator.parse_review_source(source),
                    "review source must contain",
                )

    def test_review_source_parser_rejects_duplicate_keys(self) -> None:
        expect_failure(
            self,
            lambda: self.validator.parse_review_source("`TEST-01`, `TEST-01`"),
            "review source must not contain duplicate queue keys",
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
                self,
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
            expect_failure(
                self,
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
            expect_failure(
                self,
                lambda: self.validator.validate(root),
                "duplicate ADR review field 'Human review status'",
            )

    def test_decision_record_section_is_required(self) -> None:
        with fixture_root() as fixture:
            root = Path(fixture)
            path = root / "design/architecture/decisions/adr-0012-reviewed.md"
            path.write_text(
                path.read_text(encoding="utf-8").replace("## Decision Record\n", "", 1),
                encoding="utf-8",
            )
            expect_failure(
                self,
                lambda: self.validator.validate(root),
                "expected exactly one section 'Decision Record', found 0",
            )

    def test_duplicate_decision_record_section_is_rejected(self) -> None:
        with fixture_root() as fixture:
            root = Path(fixture)
            path = root / "design/architecture/decisions/adr-0012-reviewed.md"
            path.write_text(
                path.read_text(encoding="utf-8") + "\n## Decision Record\n",
                encoding="utf-8",
            )
            expect_failure(
                self,
                lambda: self.validator.validate(root),
                "expected exactly one section 'Decision Record', found 2",
            )

    def test_review_metadata_is_bounded_to_decision_record(self) -> None:
        with fixture_root() as fixture:
            root = Path(fixture)
            path = root / "design/architecture/decisions/adr-0012-reviewed.md"
            path.write_text(
                path.read_text(encoding="utf-8") + "\n## Notes\n\n"
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
            expect_failure(
                self,
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
                self,
                lambda: self.validator.validate(root),
                "ADR 0099 target does not exist",
            )

    def test_checked_queue_row_collects_every_adr_link(self) -> None:
        with fixture_root() as fixture:
            root = Path(fixture)
            write(
                root / "design/architecture/decisions/adr-0014-other.md",
                "# ADR 0014\n",
            )
            append_queue_row(
                root,
                "- [x] `TEST-COUPLED` — `revised` on 2026-07-27; "
                "[ADR 0012](../../architecture/decisions/adr-0012-reviewed.md); "
                "[ADR 0014](../../architecture/decisions/adr-0014-other.md)",
            )
            reviews = checked_reviews(self.validator, root)
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
            reviews = checked_reviews(self.validator, root)
            self.assertEqual(reviews[12][-1].key, "TEST-UPPERCASE")

    def test_checked_queue_accepts_all_top_level_markers(self) -> None:
        with fixture_root() as fixture:
            root = Path(fixture)
            for index, marker in enumerate(("-", "*", "+")):
                append_queue_row(
                    root,
                    f"{marker} [x] `TEST-MARKER-{index}` — "
                    "`accepted` on 2026-07-27; "
                    "[ADR 0013](../../architecture/decisions/adr-0013-pending.md)",
                )
            reviews = checked_reviews(self.validator, root)
            self.assertEqual(
                {"TEST-MARKER-0", "TEST-MARKER-1", "TEST-MARKER-2"},
                {review.key for review in reviews[13]},
            )

    def test_checked_items_outside_review_queue_are_ignored(self) -> None:
        with fixture_root() as fixture:
            root = Path(fixture)
            path = queue_path(root)
            path.write_text(
                "- [x] `OUTSIDE` — malformed checked item\n\n"
                + path.read_text(encoding="utf-8")
                + "\n## Notes\n\n- [x] `OUTSIDE-2` — also unrelated\n",
                encoding="utf-8",
            )
            self.validator.validate(root)

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
                lambda: checked_reviews(self.validator, root),
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
                lambda: checked_reviews(self.validator, root),
                "malformed ADR provenance",
            )

    def test_superseded_rows_keep_reviewed_adr_provenance(self) -> None:
        with fixture_root() as fixture:
            root = Path(fixture)
            write(
                root / "design/architecture/decisions/adr-0014-replacement.md",
                "# ADR 0014\n",
            )
            append_queue_row(
                root,
                "- [x] `TEST-SUPERSEDED` — `superseded` on 2026-07-27 by "
                "[ADR 0013](../../architecture/decisions/adr-0013-pending.md); "
                "replacement: [replacement ADR 0014](../../architecture/decisions/"
                "adr-0014-replacement.md)",
            )
            reviews = checked_reviews(self.validator, root)
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
                expect_failure(
                    self,
                    lambda root=root: checked_reviews(self.validator, root),
                    "malformed checked review queue row",
                )

        with fixture_root() as fixture:
            append_queue_row(
                Path(fixture),
                "- [x] `TEST-99` — `revised` on 2026-07-27; outcome without link",
            )
            expect_failure(
                self,
                lambda: checked_reviews(self.validator, Path(fixture)),
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
            expect_failure(
                self,
                lambda: self.validator.checked_reviews(
                    path,
                    root.resolve(),
                    root / "design/architecture/decisions",
                ),
                "indented checked review queue row",
            )

    def test_duplicate_checked_review_source_is_rejected(self) -> None:
        with fixture_root() as fixture:
            root = Path(fixture)
            append_queue_row(
                root,
                "- [x] `TEST-01` — `revised` on 2026-07-27; "
                "[ADR 0012](../../architecture/decisions/adr-0012-reviewed.md)",
            )
            expect_failure(
                self,
                lambda: checked_reviews(self.validator, root),
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
                self,
                lambda: checked_reviews(self.validator, root),
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
