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
                "accepted ADR lacks a checked human-review queue entry",
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


if __name__ == "__main__":
    unittest.main()
