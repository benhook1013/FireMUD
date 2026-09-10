#!/usr/bin/env python3
"""Focused tests for the pull-request checkpoint extractor."""

from __future__ import annotations

import importlib.util
import json
import subprocess
import sys
import unittest
from pathlib import Path
from unittest.mock import patch

SCRIPT = Path(__file__).resolve().parent / "report-pr-review-checkpoints.py"


def load_reporter():
    spec = importlib.util.spec_from_file_location("pr_checkpoint_reporter", SCRIPT)
    if spec is None or spec.loader is None:
        raise AssertionError("could not load checkpoint reporter")
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


class CheckpointReporterTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.reporter = load_reporter()

    def test_parses_standard_legacy_zero_and_correction_shapes(self) -> None:
        comments = [
            {
                "body": "**CLI: 0 found / 0 accepted**",
                "created_at": "2026-09-08T15:29:49Z",
                "updated_at": "2026-09-08T15:29:49Z",
            },
            {
                "body": "**Hosted: 9 found / 7 accepted** · abc1234",
                "created_at": "2026-09-08T15:42:05Z",
                "updated_at": "2026-09-08T15:42:05Z",
            },
            {
                "body": "**Hosted: 5 found / 3 accepted** · `abc1234` · 88 files",
                "created_at": "2026-09-09T03:40:30Z",
                "updated_at": "2026-09-09T04:19:20Z",
            },
            {
                "body": (
                    "**Correction — CLI: 12 found / 5 accepted** · `abc1234` · 98 files\n\n"
                    "The rejected suggestion was not applied."
                ),
                "created_at": "2026-09-10T04:24:10Z",
                "updated_at": "2026-09-10T04:24:10Z",
            },
        ]

        checkpoints, unparsed = self.reporter.parse_checkpoint_comments(comments)

        self.assertEqual(unparsed, 0)
        self.assertEqual(len(checkpoints), 4)
        self.assertEqual(checkpoints[0].raw_found, 0)
        self.assertIsNone(checkpoints[0].reviewed_sha)
        self.assertEqual(checkpoints[1].reviewed_sha, "abc1234")
        self.assertIsNone(checkpoints[1].file_count)
        self.assertEqual(checkpoints[2].updated_at, "2026-09-09T04:19:20Z")
        self.assertTrue(checkpoints[3].correction)

    def test_ignores_unrelated_and_quoted_text_and_counts_malformed_candidates(self) -> None:
        comments = [
            {
                "body": ("A narrative says CLI: 1 found / 1 accepted.\n> **Hosted: 2 found / 1 accepted**"),
                "created_at": "2026-09-10T00:00:00Z",
                "updated_at": "2026-09-10T00:00:00Z",
            },
            {
                "body": "**CLI: malformed**",
                "created_at": "2026-09-10T00:00:01Z",
                "updated_at": "2026-09-10T00:00:01Z",
            },
            {
                "body": "**Hosted: 3 found / 2 accepted** · unknown suffix",
                "created_at": "2026-09-10T00:00:02Z",
                "updated_at": "2026-09-10T00:00:02Z",
            },
        ]

        checkpoints, unparsed = self.reporter.parse_checkpoint_comments(comments)

        self.assertEqual(checkpoints, [])
        self.assertEqual(unparsed, 2)

    def test_uses_only_the_first_nonblank_nonindented_line(self) -> None:
        comments = [
            {
                "body": "```markdown\n**CLI: 2 found / 1 accepted**\n```",
                "created_at": "2026-09-10T00:00:00Z",
                "updated_at": "2026-09-10T00:00:00Z",
            },
            {
                "body": "Example: **Hosted: 2 found / 1 accepted**\n**CLI: 3 found / 2 accepted**",
                "created_at": "2026-09-10T00:01:00Z",
                "updated_at": "2026-09-10T00:01:00Z",
            },
            {
                "body": "\n**CLI: 4 found / 3 accepted**\n**Hosted: 5 found / 4 accepted**",
                "created_at": "2026-09-10T00:02:00Z",
                "updated_at": "2026-09-10T00:02:00Z",
            },
        ]

        checkpoints, unparsed = self.reporter.parse_checkpoint_comments(comments)

        self.assertEqual(unparsed, 0)
        self.assertEqual(len(checkpoints), 1)
        self.assertEqual(checkpoints[0].raw_found, 4)

    def test_accepts_observed_literal_escaped_correction_prose(self) -> None:
        comments = [
            {
                "body": (
                    "**Correction — CLI: 12 found / 5 accepted** · `abc1234` · 98 files"
                    r"\n\nThe rejected suggestion was not applied."
                ),
                "created_at": "2026-09-10T04:24:10Z",
                "updated_at": "2026-09-10T04:24:10Z",
            }
        ]

        checkpoints, unparsed = self.reporter.parse_checkpoint_comments(comments)

        self.assertEqual(unparsed, 0)
        self.assertEqual(len(checkpoints), 1)
        self.assertTrue(checkpoints[0].correction)

    def test_sorts_all_comments_before_limiting_and_preserves_same_sha(self) -> None:
        comments = [
            {
                "body": "**CLI: 2 found / 1 accepted** · `abc1234` · 2 files",
                "created_at": "2026-09-10T03:00:00Z",
                "updated_at": "2026-09-10T03:00:00Z",
            },
            {
                "body": "**Hosted: 1 found / 0 accepted** · `abc1234` · 2 files",
                "created_at": "2026-09-10T02:00:00Z",
                "updated_at": "2026-09-10T02:00:00Z",
            },
            {
                "body": "**CLI: 0 found / 0 accepted**",
                "created_at": "2026-09-10T04:00:00Z",
                "updated_at": "2026-09-10T04:00:00Z",
            },
        ]

        report = self.reporter.collect_report(comments, 2)

        self.assertEqual(report["matched_checkpoints"], 3)
        self.assertEqual(report["returned_checkpoints"], 2)
        self.assertEqual(report["omitted_checkpoints"], 1)
        self.assertEqual(
            [checkpoint["created_at"] for checkpoint in report["checkpoints"]],
            ["2026-09-10T03:00:00Z", "2026-09-10T04:00:00Z"],
        )

    def test_fetches_paginated_comments_with_explicit_get(self) -> None:
        response = subprocess.CompletedProcess(
            args=[],
            returncode=0,
            stdout=json.dumps(
                [
                    [
                        {
                            "body": "**CLI: 1 found / 0 accepted**",
                            "created_at": "2026-09-10T00:00:00Z",
                            "updated_at": "2026-09-10T00:00:00Z",
                        }
                    ],
                    [],
                ]
            ),
            stderr="",
        )
        with patch.object(self.reporter.subprocess, "run", return_value=response) as run:
            comments = self.reporter.fetch_comments("owner/repo", 42)

        self.assertEqual(len(comments), 1)
        run.assert_called_once_with(
            [
                "gh",
                "api",
                "--method",
                "GET",
                "--paginate",
                "--slurp",
                "repos/owner/repo/issues/42/comments?per_page=100",
            ],
            check=True,
            capture_output=True,
            text=True,
        )

    def test_api_errors_and_malformed_payload_are_not_zero_findings(self) -> None:
        with (
            patch.object(
                self.reporter.subprocess,
                "run",
                side_effect=subprocess.CalledProcessError(1, "gh", stderr="authentication failed"),
            ),
            self.assertRaisesRegex(RuntimeError, "authentication failed"),
        ):
            self.reporter.fetch_comments("owner/repo", 42)

        response = subprocess.CompletedProcess([], 0, "{}", "")
        with (
            patch.object(self.reporter.subprocess, "run", return_value=response),
            self.assertRaisesRegex(self.reporter.CheckpointError, "unexpected paginated response"),
        ):
            self.reporter.fetch_comments("owner/repo", 42)

    def test_negative_limit_is_rejected(self) -> None:
        with self.assertRaises(self.reporter.argparse.ArgumentTypeError):
            self.reporter.parse_limit("-1")


if __name__ == "__main__":
    unittest.main()
