#!/usr/bin/env python3
"""Focused tests for the pull-request checkpoint extractor."""

from __future__ import annotations

import importlib.util
import io
import json
import subprocess
import sys
import tempfile
import unittest
from contextlib import redirect_stdout
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

    def test_parses_hidden_run_marker_and_warns_for_unlinked_cli_comments(self) -> None:
        comments = [
            {
                "id": 101,
                "body": "**CLI: 2 found / 1 accepted** · `abc1234` · 3 files\n<!-- firemud-cli-run: run.A1b2C3 -->",
                "created_at": "2026-09-10T00:00:00Z",
                "updated_at": "2026-09-10T00:00:00Z",
            },
            {
                "id": 102,
                "body": "**CLI: 0 found / 0 accepted**",
                "created_at": "2026-09-10T00:01:00Z",
                "updated_at": "2026-09-10T00:01:00Z",
            },
        ]

        report = self.reporter.collect_report(comments, 0)

        self.assertEqual(report["checkpoints"][0]["comment_id"], 101)
        self.assertEqual(report["checkpoints"][0]["run_id"], "run.A1b2C3")
        self.assertEqual(report["checkpoints"][1]["comment_id"], 102)
        self.assertEqual(len(report["warnings"]), 1)
        self.assertIn("1 CLI checkpoint", report["warnings"][0])
        output = io.StringIO()
        with redirect_stdout(output):
            self.reporter.emit_text(report)
        self.assertIn("unlinked", output.getvalue())

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

    def test_interleaves_scope_change_timeline_event_with_selected_checkpoints(self) -> None:
        comments = [
            {
                "id": 201,
                "body": "**CLI: 1 found / 0 accepted** · `abc1234` · 2 files",
                "created_at": "2026-09-10T01:00:00Z",
                "updated_at": "2026-09-10T01:00:00Z",
            },
            {
                "id": 202,
                "body": (
                    "**Review scope changed:** validator cleanup moved to #2731. "
                    "Earlier counts cover the previous scope.\n"
                    "<!-- firemud-review-scope-change -->"
                ),
                "created_at": "2026-09-10T02:00:00Z",
                "updated_at": "2026-09-10T02:00:00Z",
            },
            {
                "id": 203,
                "body": "**CLI: 2 found / 1 accepted** · `def5678` · 3 files",
                "created_at": "2026-09-10T03:00:00Z",
                "updated_at": "2026-09-10T03:00:00Z",
            },
            {
                "id": 204,
                "body": "**Review scope changed:** legacy text without the standard marker",
                "created_at": "2026-09-10T04:00:00Z",
                "updated_at": "2026-09-10T04:00:00Z",
            },
        ]

        report = self.reporter.collect_report(comments, 1)

        self.assertEqual(report["matched_scope_changes"], 1)
        self.assertEqual(report["unparsed_candidates"], 1)
        self.assertEqual(
            [(item["kind"], item["comment_id"]) for item in report["timeline"]],
            [("scope_change", 202), ("checkpoint", 203)],
        )
        self.assertIn("Earlier counts cover the previous scope.", report["timeline"][0]["description"])

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

    def test_details_preserves_legacy_reason_separately_from_raw_findings(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            log_root = Path(directory)
            run_dir = log_root / "run.A1b2C3"
            run_dir.mkdir()
            (run_dir / "metadata").write_text(
                "repository=owner/repo\n"
                "pull_request=42\n"
                "candidate_sha=abc1234567890123456789012345678901234567\n"
                "candidate_files=1\n",
                encoding="utf-8",
            )
            (run_dir / "exit-status").write_text("0\n", encoding="utf-8")
            (run_dir / "stdout").write_text(
                "\n".join(
                    [
                        json.dumps(
                            {
                                "type": "finding",
                                "severity": "minor",
                                "fileName": "a.txt",
                                "codegenInstructions": "In @a.txt around lines 5 - 7, fix it.",
                            }
                        ),
                        json.dumps(
                            {
                                "type": "complete",
                                "status": "review_completed",
                                "findings": 1,
                                "reviewedFiles": ["a.txt"],
                            }
                        ),
                    ]
                )
                + "\n",
                encoding="utf-8",
            )
            (run_dir / "rejections.tsv").write_text(
                "a.txt:5\tRejected because the rule is canonical.\n", encoding="utf-8"
            )
            comments = [
                {
                    "id": 777,
                    "body": "**CLI: 1 found / 0 accepted** · `abc1234` · 1 files\n<!-- firemud-cli-run: run.A1b2C3 -->",
                    "created_at": "2026-09-10T00:00:00Z",
                    "updated_at": "2026-09-10T00:00:00Z",
                }
            ]
            with patch.object(self.reporter, "_git_log_root", return_value=log_root):
                detail = self.reporter.collect_detail(comments, 777, "owner/repo", 42)
                mismatch = self.reporter.collect_detail(
                    [
                        {
                            **comments[0],
                            "body": "**CLI: 2 found / 0 accepted** · `abc1234` · 1 files\n"
                            "<!-- firemud-cli-run: run.A1b2C3 -->",
                        }
                    ],
                    777,
                    "owner/repo",
                    42,
                )
                (run_dir / "rejections.tsv").unlink()
                without_rejections = self.reporter.collect_detail(comments, 777, "owner/repo", 42)

        self.assertEqual(detail["linkage_status"], "linked")
        self.assertEqual(detail["findings"][0]["reason_status"], "not recorded")
        self.assertEqual(detail["findings"][0]["rejection_reason"], None)
        self.assertEqual(detail["unlinked_rejections"][0]["format"], "legacy")
        self.assertEqual(
            detail["unlinked_rejections"][0]["reason"],
            "Rejected because the rule is canonical.",
        )
        self.assertEqual(mismatch["linkage_status"], "invalid")
        self.assertIn("finding count does not match the checkpoint", mismatch["message"])
        self.assertEqual(without_rejections["linkage_status"], "linked")
        self.assertFalse(without_rejections["no_linked_data"])
        self.assertIn("rejection reasons are not recorded", without_rejections["message"])

    def test_details_rejects_missing_marker_or_invalid_capture_without_empty_success(self) -> None:
        comments = [
            {
                "id": 778,
                "body": "**CLI: 1 found / 0 accepted** · `abc1234` · 1 files",
                "created_at": "2026-09-10T00:00:00Z",
                "updated_at": "2026-09-10T00:00:00Z",
            }
        ]

        detail = self.reporter.collect_detail(comments, 778, "owner/repo", 42)

        self.assertEqual(detail["linkage_status"], "unavailable")
        self.assertTrue(detail["no_linked_data"])
        self.assertIn("no firemud-cli-run marker", detail["message"])

    def test_rejections_selects_latest_cli_rounds_and_keeps_missing_links(self) -> None:
        comments = [
            {
                "id": 801,
                "body": "**CLI: 1 found / 0 accepted** · `abc1234`",
                "created_at": "2026-09-10T01:00:00Z",
                "updated_at": "2026-09-10T01:00:00Z",
            },
            {
                "id": 802,
                "body": "**CLI: 2 found / 1 accepted** · `def5678`",
                "created_at": "2026-09-10T02:00:00Z",
                "updated_at": "2026-09-10T02:00:00Z",
            },
            {
                "id": 803,
                "body": "**Hosted: 3 found / 2 accepted**",
                "created_at": "2026-09-10T03:00:00Z",
                "updated_at": "2026-09-10T03:00:00Z",
            },
        ]

        report = self.reporter.collect_rejections(comments, 2, "owner/repo", 42)

        self.assertEqual([round_result["comment_id"] for round_result in report["rounds"]], [801, 802])
        self.assertTrue(all(round_result["status"] == "unavailable" for round_result in report["rounds"]))
        self.assertEqual(report["omitted_rounds"], 0)

    def test_rejections_render_only_recorded_findings_and_legacy_references(self) -> None:
        comment = {
            "id": 804,
            "body": "**CLI: 2 found / 1 accepted** · `abc1234`\n<!-- firemud-cli-run: run.A1b2C3 -->",
            "created_at": "2026-09-10T04:00:00Z",
            "updated_at": "2026-09-10T04:00:00Z",
        }
        capture = self.reporter.CaptureData(
            metadata={},
            findings=[
                {"type": "finding", "fileName": "a.txt"},
                {"type": "finding", "fileName": "b.txt"},
            ],
            reasons={1: "Recorded rejection."},
            unlinked_rejections=[
                {"format": "legacy", "reference": "b.txt:4", "reason": "Legacy rejection."},
                {"format": "ordinal", "ordinal": 2, "reference": "b.txt:4", "reason": "not recorded"},
            ],
            rejection_file_present=True,
        )
        with patch.object(self.reporter, "load_capture", return_value=capture):
            report = self.reporter.collect_rejections([comment], 1, "owner/repo", 42)

        round_result = report["rounds"][0]
        self.assertEqual(len(round_result["rejections"]), 1)
        self.assertEqual(round_result["rejections"][0]["reason"], "Recorded rejection.")
        self.assertEqual(round_result["references"][0]["format"], "legacy")
        self.assertEqual(round_result["references"][1]["reason"], "not recorded")

    def test_rejections_count_requires_positive_integer(self) -> None:
        with self.assertRaises(self.reporter.argparse.ArgumentTypeError):
            self.reporter.parse_positive_limit("0")

    def test_future_rejections_use_one_based_finding_ordinal(self) -> None:
        finding = {
            "fileName": "a.txt",
            "codegenInstructions": "In @a.txt around lines 5 - 7, fix it.",
        }
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "rejections.tsv"
            path.write_text("1\ta.txt:5\tRejected by owner.\n", encoding="utf-8")
            reasons, unlinked = self.reporter._read_rejections(path, [finding])

        self.assertEqual(reasons, {1: "Rejected by owner."})
        self.assertEqual(unlinked, [])

    def test_malformed_capture_stdout_is_invalid_evidence(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "stdout"
            path.write_text('{"type":"finding"}\nnot-json\n', encoding="utf-8")
            with self.assertRaisesRegex(self.reporter.CaptureInvalid, "invalid JSON"):
                self.reporter._parse_capture_stdout(path)

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
