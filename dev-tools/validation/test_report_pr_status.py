#!/usr/bin/env python3
"""Deterministic tests for the composed pull-request status reporter."""

from __future__ import annotations

import importlib.util
import io
import json
import subprocess
import sys
import tempfile
import unittest
from contextlib import redirect_stderr, redirect_stdout
from pathlib import Path
from unittest.mock import patch

SCRIPT = Path(__file__).resolve().parent / "report-pr-status.py"


def load_reporter():
    spec = importlib.util.spec_from_file_location("pr_status_reporter", SCRIPT)
    if spec is None or spec.loader is None:
        raise AssertionError("could not load PR status reporter")
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


class PrStatusReporterTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.reporter = load_reporter()

    @staticmethod
    def checkpoint_payload() -> dict:
        payload = {
            "total_comments": 2,
            "matched_checkpoints": 2,
            "returned_checkpoints": 2,
            "omitted_checkpoints": 0,
            "unparsed_candidates": 0,
            "matched_scope_changes": 0,
            "warnings": [],
            "checkpoints": [
                {
                    "comment_id": 102,
                    "created_at": "2026-09-14T01:00:00Z",
                    "type": "CLI",
                    "raw_found": 2,
                    "accepted": 1,
                    "reviewed_sha": "abcdef123456",
                    "file_count": 2,
                    "correction": False,
                },
                {
                    "comment_id": 101,
                    "created_at": "2026-09-13T01:00:00Z",
                    "type": "Hosted",
                    "raw_found": 3,
                    "accepted": 2,
                    "reviewed_sha": "abcdef123456",
                    "file_count": 2,
                    "correction": False,
                },
            ],
            "timeline": [],
        }
        payload["timeline"] = [
            {
                "kind": "scope_change",
                "comment_id": 103,
                "created_at": "2026-09-13T00:30:00Z",
                "description": "The report includes the final validation scope.",
            },
            *[
                {"kind": "checkpoint", **checkpoint}
                for checkpoint in sorted(payload["checkpoints"], key=lambda item: item["created_at"])
            ],
        ]
        return payload

    @staticmethod
    def checker_payload(*, ok: bool, reasons: list[str] | None = None) -> dict:
        return {
            "repo": "owner/repo",
            "pr_number": 42,
            "head_sha": "0123456789abcdef0123456789abcdef01234567",
            "latest_commit_at": "2026-09-14T00:00:00Z",
            "unresolved_non_outdated": 0 if ok else 1,
            "unresolved_outdated": 0 if ok else 1,
            "unresolved_total": 0 if ok else 2,
            "latest_explicit_review_request_at": "2026-09-14T00:05:00Z",
            "latest_explicit_review_request_type": "full",
            "latest_coderabbit_review_finished_at": "2026-09-14T00:10:00Z",
            "explicit_review_after_latest_commit": True,
            "review_finished_after_latest_request": True,
            "substantive_review_after_latest_commit": True,
            "latest_review_request_rate_limited": False,
            "review_rate_limit_until": None,
            "latest_review_request_noop": False,
            "latest_review_request_failed": False,
            "retrigger_review_allowed": ok,
            "manual_thread_resolution_required": not ok,
            "must_resolve_outdated_threads": not ok,
            "outside_diff_actionable_comments": 0 if ok else 1,
            "duplicate_actionable_comments": 0,
            "latest_actionable_comment_url": None,
            "prior_substantive_review_checkpoint": True,
            "plan_ceiling_rejection_evidence": False,
            "reviewed_commit_range_after_latest_commit": True,
            "incremental_review_exception_allowed": False,
            "ok": ok,
            "reasons": (
                reasons
                if reasons is not None
                else (
                    []
                    if ok
                    else [
                        "1 unresolved non-outdated CodeRabbit thread remains",
                        "1 unresolved outdated CodeRabbit thread remains",
                        "1 top-level outside-diff CodeRabbit comment remains",
                    ]
                )
            ),
        }

    @staticmethod
    def github_payload(*, merge_state: str = "CLEAN", checks: list[dict] | None = None) -> dict:
        return {
            "number": 42,
            "title": "Compose the PR status report",
            "headRefName": "codex/pr-status-report",
            "headRefOid": "0123456789abcdef0123456789abcdef01234567",
            "baseRefName": "main",
            "baseRefOid": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            "changedFiles": 2,
            "statusCheckRollup": checks
            if checks is not None
            else [
                {"__typename": "CheckRun", "name": "build", "status": "COMPLETED", "conclusion": "SUCCESS"},
                {"__typename": "CheckRun", "name": "docs", "status": "COMPLETED", "conclusion": "SKIPPED"},
            ],
            "mergeable": "MERGEABLE",
            "mergeStateStatus": merge_state,
            "isDraft": False,
            "url": "https://github.com/owner/repo/pull/42",
            "body": (
                "<!-- firemud:cloc-report:start -->\n"
                '<!-- firemud:cloc-report:metadata {"base_oid":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",'
                '"classifier_sha256":null,"head_oid":"0123456789abcdef0123456789abcdef01234567",'
                '"merge_base":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"} -->\n'
                "### FireMUD LOC impact\n"
                "<!-- firemud:cloc-report:end -->"
            ),
        }

    def provider_responses(
        self,
        *,
        checker_ok: bool,
        checker_exit: int | None = None,
        checks=None,
        merge_state="CLEAN",
        checkpoint_payload=None,
        checker_payload=None,
        github_payload=None,
    ):
        checkpoint = checkpoint_payload or self.checkpoint_payload()
        checker = checker_payload or self.checker_payload(ok=checker_ok)
        github = github_payload or self.github_payload(merge_state=merge_state, checks=checks)
        if checker_exit is None:
            checker_exit = 0 if checker_ok else 1

        def run(command, **kwargs):
            if "report-pr-review-checkpoints.py" in " ".join(command):
                return subprocess.CompletedProcess(command, 0, json.dumps(checkpoint), "")
            if "check-coderabbit-review.py" in " ".join(command):
                return subprocess.CompletedProcess(command, checker_exit, json.dumps(checker), "checker blocked")
            return subprocess.CompletedProcess(command, 0, json.dumps(github), "")

        return run

    def test_normal_blocked_report_has_threads_ci_and_conservative_verdict(self) -> None:
        checks = [
            {"name": "build", "status": "IN_PROGRESS"},
            {"name": "lint", "status": "COMPLETED", "conclusion": "FAILURE"},
            {"name": "unit", "status": "COMPLETED", "conclusion": "SUCCESS"},
        ]
        with patch.object(self.reporter.subprocess, "run", side_effect=self.provider_responses(checker_ok=False, checks=checks, merge_state="DIRTY")):
            report = self.reporter.build_report("owner/repo", 42)
        self.assertFalse(report["ready"])
        self.assertEqual(report["threads"], {"current": 1, "outdated": 1, "total": 2})
        self.assertEqual([item["name"] for item in report["ci"]["pending"]], ["build"])
        self.assertEqual([item["name"] for item in report["ci"]["failed"]], ["lint"])
        self.assertIn("GitHub mergeStateStatus is not CLEAN", report["reasons"])

    def test_ready_report_orders_hosted_and_cli_sequence_and_formats_mobile_text(self) -> None:
        checkpoint = self.checkpoint_payload()
        checkpoint["unparsed_candidates"] = 2
        with patch.object(
            self.reporter.subprocess,
            "run",
            side_effect=self.provider_responses(checker_ok=True, checkpoint_payload=checkpoint),
        ):
            report = self.reporter.build_report("owner/repo", 42)
        self.assertTrue(report["ready"])
        self.assertEqual([item["type"] for item in report["review_sequence"]], ["Hosted", "CLI"])
        output = io.StringIO()
        with redirect_stdout(output):
            self.reporter.emit_text(report)
        rendered = output.getvalue()
        self.assertIn("reviews: chronological", rendered)
        self.assertLess(rendered.index("Hosted R/A 3/2"), rendered.index("CLI R/A 2/1"))
        self.assertIn("CI: no pending or failed checks", rendered)
        self.assertIn("verdict: READY", rendered)
        self.assertNotIn("build", rendered)
        self.assertIn("scope-change · comment 103: The report includes the final validation scope.", rendered)
        self.assertIn("mergeStateStatus=CLEAN · mergeable=MERGEABLE", rendered)
        self.assertIn("LOC metadata: fresh", rendered)
        self.assertIn("2 checkpoint candidate(s) were not parsed", rendered)
        self.assertEqual(report["review_timeline"][0]["kind"], "scope_change")

    def test_expected_checker_nonzero_with_valid_json_is_report_data(self) -> None:
        with patch.object(self.reporter.subprocess, "run", side_effect=self.provider_responses(checker_ok=False, checker_exit=1)):
            report = self.reporter.build_report("owner/repo", 42)
        self.assertEqual(report["providers"]["coderabbit_checker"]["exit_code"], 1)
        self.assertFalse(report["ready"])

    def test_expired_cooldown_follows_checker_flags(self) -> None:
        checker = self.checker_payload(ok=True)
        checker["review_rate_limit_until"] = "2020-01-01T00:00:00Z"
        with patch.object(
            self.reporter.subprocess,
            "run",
            side_effect=self.provider_responses(checker_ok=True, checker_payload=checker),
        ):
            report = self.reporter.build_report("owner/repo", 42)
        self.assertTrue(report["ready"])
        self.assertTrue(report["review_coverage"]["retrigger_review_allowed"])

    def test_checker_semantic_inconsistency_fails_closed(self) -> None:
        cases = []
        inconsistent_total = self.checker_payload(ok=True)
        inconsistent_total["unresolved_total"] = 1
        cases.append(inconsistent_total)
        inconsistent_flag = self.checker_payload(ok=False)
        inconsistent_flag["manual_thread_resolution_required"] = False
        cases.append(inconsistent_flag)
        inconsistent_ok = self.checker_payload(ok=False)
        inconsistent_ok["reasons"] = []
        cases.append(inconsistent_ok)
        inconsistent_summary = self.checker_payload(ok=False)
        inconsistent_summary["outside_diff_actionable_comments"] = 0
        cases.append(inconsistent_summary)
        inconsistent_retrigger = self.checker_payload(ok=True)
        inconsistent_retrigger["retrigger_review_allowed"] = False
        cases.append(inconsistent_retrigger)
        inconsistent_reason = self.checker_payload(ok=False)
        inconsistent_reason["reasons"] = ["wrong reason 1", "wrong reason 2", "wrong reason 3"]
        cases.append(inconsistent_reason)
        for checker in cases:
            with self.subTest(checker=checker), patch.object(
                self.reporter.subprocess,
                "run",
                side_effect=self.provider_responses(
                    checker_ok=checker["ok"],
                    checker_payload=checker,
                    checker_exit=0 if checker["ok"] else 1,
                ),
            ), self.assertRaises(self.reporter.ReportError):
                self.reporter.build_report("owner/repo", 42)

    def test_active_status_wins_over_stale_success_conclusion(self) -> None:
        checks = [
            {"name": "active-stale", "status": "IN_PROGRESS", "conclusion": "SUCCESS"},
            {"name": "unknown-success", "status": "MYSTERY", "conclusion": "SUCCESS"},
            {"name": "unknown-failure", "status": "MYSTERY", "conclusion": "FAILURE"},
            {"name": "terminal-failure", "status": "COMPLETED", "conclusion": "FAILURE"},
        ]
        with patch.object(
            self.reporter.subprocess,
            "run",
            side_effect=self.provider_responses(checker_ok=True, checks=checks),
        ):
            report = self.reporter.build_report("owner/repo", 42)
        self.assertEqual(
            [item["name"] for item in report["ci"]["pending"]],
            ["active-stale", "unknown-success"],
        )
        self.assertEqual(
            [item["name"] for item in report["ci"]["failed"]],
            ["unknown-failure", "terminal-failure"],
        )
        self.assertFalse(report["ready"])
        output = io.StringIO()
        with redirect_stdout(output):
            self.reporter.emit_text(report)
        rendered = output.getvalue()
        self.assertIn("pending active-stale (IN_PROGRESS)", rendered)
        self.assertNotIn("pending active-stale (SUCCESS)", rendered)

    def test_checker_exit_must_match_ok_value_and_be_zero_or_one(self) -> None:
        for checker_ok, checker_exit in ((True, 1), (False, 0), (True, 2)):
            with self.subTest(checker_ok=checker_ok, checker_exit=checker_exit), patch.object(
                self.reporter.subprocess,
                "run",
                side_effect=self.provider_responses(
                    checker_ok=checker_ok, checker_exit=checker_exit
                ),
            ), self.assertRaisesRegex(self.reporter.ReportError, "exit status"):
                self.reporter.build_report("owner/repo", 42)

    def test_provider_identity_and_github_number_must_match_request(self) -> None:
        checker = self.checker_payload(ok=True)
        checker["repo"] = "other/repo"
        with patch.object(
            self.reporter.subprocess,
            "run",
            side_effect=self.provider_responses(checker_ok=True, checker_payload=checker),
        ), self.assertRaisesRegex(self.reporter.ReportError, "repository"):
            self.reporter.build_report("owner/repo", 42)

        github = self.github_payload()
        github["number"] = 43
        with patch.object(
            self.reporter.subprocess,
            "run",
            side_effect=self.provider_responses(checker_ok=True, github_payload=github),
        ), self.assertRaisesRegex(self.reporter.ReportError, "number"):
            self.reporter.build_report("owner/repo", 42)

        checker = self.checker_payload(ok=True)
        checker["head_sha"] = "ffffffffffffffffffffffffffffffffffffffff"
        with patch.object(
            self.reporter.subprocess,
            "run",
            side_effect=self.provider_responses(checker_ok=True, checker_payload=checker),
        ), self.assertRaisesRegex(self.reporter.ReportError, "head"):
            self.reporter.build_report("owner/repo", 42)

        for field, value in (("head_sha", "short"), ("head_sha", "g" * 40)):
            checker = self.checker_payload(ok=True)
            checker[field] = value
            with self.subTest(field=field, value=value), patch.object(
                self.reporter.subprocess,
                "run",
                side_effect=self.provider_responses(checker_ok=True, checker_payload=checker),
            ), self.assertRaisesRegex(self.reporter.ReportError, "40 hexadecimal"):
                self.reporter.build_report("owner/repo", 42)

        for value in ("short", "g" * 40):
            github = self.github_payload()
            github["headRefOid"] = value
            with self.subTest(value=value), patch.object(
                self.reporter.subprocess,
                "run",
                side_effect=self.provider_responses(checker_ok=True, github_payload=github),
            ), self.assertRaisesRegex(self.reporter.ReportError, "40 hexadecimal"):
                self.reporter.build_report("owner/repo", 42)

    def test_github_merge_fields_and_changed_files_are_required(self) -> None:
        for field in ("mergeStateStatus", "mergeable", "isDraft", "changedFiles"):
            github = self.github_payload()
            del github[field]
            with self.subTest(field=field), patch.object(
                self.reporter.subprocess,
                "run",
                side_effect=self.provider_responses(checker_ok=True, github_payload=github),
            ), self.assertRaises(self.reporter.ReportError):
                self.reporter.build_report("owner/repo", 42)

        github = self.github_payload(merge_state="CLEAN")
        github["mergeable"] = "CONFLICTING"
        with patch.object(
            self.reporter.subprocess,
            "run",
            side_effect=self.provider_responses(checker_ok=True, github_payload=github),
        ):
            report = self.reporter.build_report("owner/repo", 42)
        self.assertFalse(report["ready"])
        self.assertIn("GitHub mergeable is not MERGEABLE", report["reasons"])

        for field, value in (("mergeStateStatus", "BOGUS"), ("mergeable", "BOGUS")):
            github = self.github_payload()
            github[field] = value
            with self.subTest(field=field), patch.object(
                self.reporter.subprocess,
                "run",
                side_effect=self.provider_responses(checker_ok=True, github_payload=github),
            ), self.assertRaisesRegex(self.reporter.ReportError, "invalid"):
                self.reporter.build_report("owner/repo", 42)

        github = self.github_payload()
        github["isDraft"] = True
        with patch.object(
            self.reporter.subprocess,
            "run",
            side_effect=self.provider_responses(checker_ok=True, github_payload=github),
        ):
            report = self.reporter.build_report("owner/repo", 42)
        self.assertFalse(report["ready"])

    def test_every_checkpoint_timeline_record_is_validated(self) -> None:
        checkpoint = self.checkpoint_payload()
        checkpoint["timeline"][1]["accepted"] = "one"
        with patch.object(
            self.reporter.subprocess,
            "run",
            side_effect=self.provider_responses(checker_ok=True, checkpoint_payload=checkpoint),
        ), self.assertRaisesRegex(self.reporter.ReportError, "accepted"):
            self.reporter.build_report("owner/repo", 42)

        checkpoint = self.checkpoint_payload()
        checkpoint["timeline"][1]["reviewed_sha"] = "not-hex"
        with patch.object(
            self.reporter.subprocess,
            "run",
            side_effect=self.provider_responses(checker_ok=True, checkpoint_payload=checkpoint),
        ), self.assertRaisesRegex(self.reporter.ReportError, "reviewed_sha"):
            self.reporter.build_report("owner/repo", 42)

    def test_timestamps_are_ordered_by_instant_and_scope_ids_are_retained(self) -> None:
        checkpoint = self.checkpoint_payload()
        checkpoint["checkpoints"][0]["created_at"] = "2026-09-14T00:00:00+02:00"
        checkpoint["checkpoints"][1]["created_at"] = "2026-09-13T23:30:00+00:00"
        checkpoint["timeline"] = [
            {"kind": "checkpoint", **checkpoint["checkpoints"][0]},
            {
                "kind": "scope_change",
                "comment_id": 103,
                "created_at": "2026-09-13T00:30:00Z",
                "description": "The report includes the final validation scope.",
            },
            {"kind": "checkpoint", **checkpoint["checkpoints"][1]},
        ]
        with patch.object(
            self.reporter.subprocess,
            "run",
            side_effect=self.provider_responses(checker_ok=True, checkpoint_payload=checkpoint),
        ):
            report = self.reporter.build_report("owner/repo", 42)
        self.assertEqual([item["type"] for item in report["review_sequence"]], ["CLI", "Hosted"])
        self.assertEqual(report["review_timeline"][0]["comment_id"], 103)

    def test_command_does_not_request_files_fallback(self) -> None:
        commands = []

        def capture(command, **kwargs):
            commands.append(command)
            return self.provider_responses(checker_ok=True)(command, **kwargs)

        with patch.object(self.reporter.subprocess, "run", side_effect=capture):
            self.reporter.build_report("owner/repo", 42)
        github_command = next(command for command in commands if command[0] == "gh")
        self.assertNotIn("files", github_command[-1].split(","))

    def test_malformed_or_missing_provider_evidence_fails_visibly(self) -> None:
        def malformed(command, **kwargs):
            if "report-pr-review-checkpoints.py" in " ".join(command):
                return subprocess.CompletedProcess(command, 0, "not-json", "")
            return subprocess.CompletedProcess(command, 0, "{}", "")

        with patch.object(self.reporter.subprocess, "run", side_effect=malformed), self.assertRaisesRegex(
            self.reporter.ReportError, "malformed JSON"
        ):
            self.reporter.build_report("owner/repo", 42)

        def missing(command, **kwargs):
            if "report-pr-review-checkpoints.py" in " ".join(command):
                return subprocess.CompletedProcess(command, 0, "", "")
            return subprocess.CompletedProcess(command, 0, "{}", "")

        stderr = io.StringIO()
        with (
            patch.object(self.reporter.subprocess, "run", side_effect=missing),
            patch.object(self.reporter, "parse_args", return_value=unittest.mock.Mock(repo="owner/repo", pr=42, json=False)),
            redirect_stderr(stderr),
            redirect_stdout(io.StringIO()),
        ):
            result = self.reporter.main()
        self.assertEqual(result, 1)
        self.assertIn("returned no JSON evidence", stderr.getvalue())

    def test_json_exposes_only_pending_and_failed_ci_details(self) -> None:
        checks = [
            {"name": "success-build", "status": "COMPLETED", "conclusion": "SUCCESS"},
            {"name": "skipped-docs", "status": "COMPLETED", "conclusion": "SKIPPED"},
            {"name": "pending-test", "status": "QUEUED"},
            {"name": "failed-lint", "status": "COMPLETED", "conclusion": "FAILURE"},
        ]
        with patch.object(self.reporter.subprocess, "run", side_effect=self.provider_responses(checker_ok=False, checks=checks)):
            report = self.reporter.build_report("owner/repo", 42)
        serialized = json.dumps(report)
        self.assertIn("pending-test", serialized)
        self.assertIn("failed-lint", serialized)
        self.assertNotIn("success-build", serialized)
        self.assertNotIn("skipped-docs", serialized)
        self.assertEqual(report["ci"]["observed"], 4)

    def test_checkpoint_counts_streak_taper_and_loc_are_separate_evidence(self) -> None:
        checkpoint = self.checkpoint_payload()
        checkpoint["checkpoints"] = [
            {
                **checkpoint["checkpoints"][1],
                "created_at": "2026-09-13T01:00:00Z",
                "accepted": 1,
            },
            {
                **checkpoint["checkpoints"][0],
                "created_at": "2026-09-14T01:00:00Z",
                "raw_found": 0,
                "accepted": 0,
            },
            {
                **checkpoint["checkpoints"][0],
                "created_at": "2026-09-15T01:00:00Z",
                "raw_found": 0,
                "accepted": 0,
            },
        ]
        checkpoint["timeline"] = [
            {"kind": "checkpoint", **item} for item in checkpoint["checkpoints"]
        ]
        with patch.object(
            self.reporter.subprocess,
            "run",
            side_effect=self.provider_responses(checker_ok=True, checkpoint_payload=checkpoint),
        ):
            report = self.reporter.build_report("owner/repo", 42)
        self.assertEqual(report["checkpoint_counts"]["by_type"]["CLI"]["count"], 2)
        self.assertEqual(report["checkpoint_counts"]["cli_zero_streak"]["count"], 2)
        self.assertEqual(report["checkpoint_counts"]["taper_evidence"]["hosted_zero_zero_streak"], 0)
        self.assertEqual(report["loc_metadata"]["status"], "fresh")
        self.assertEqual(report["verdict"], "READY")

    def test_hosted_taper_labels_raw_positive_and_correction_evidence(self) -> None:
        checkpoint = self.checkpoint_payload()
        checkpoint["checkpoints"] = [
            {
                **checkpoint["checkpoints"][1],
                "created_at": "2026-09-13T01:00:00Z",
                "raw_found": 1,
                "accepted": 0,
            },
            {
                **checkpoint["checkpoints"][1],
                "created_at": "2026-09-14T01:00:00Z",
                "raw_found": 0,
                "accepted": 0,
                "correction": True,
            },
            {
                **checkpoint["checkpoints"][1],
                "created_at": "2026-09-15T01:00:00Z",
                "raw_found": 0,
                "accepted": 0,
            },
        ]
        checkpoint["timeline"] = [
            {"kind": "checkpoint", **item} for item in checkpoint["checkpoints"]
        ]
        with patch.object(
            self.reporter.subprocess,
            "run",
            side_effect=self.provider_responses(checker_ok=True, checkpoint_payload=checkpoint),
        ):
            report = self.reporter.build_report("owner/repo", 42)
        taper = report["checkpoint_counts"]["taper_evidence"]
        self.assertEqual(taper["hosted_zero_zero_streak"], 1)
        self.assertEqual(taper["hosted_raw_positive_accepted_zero_streak"], 0)
        self.assertEqual(taper["hosted_completed_zero_zero_observed"], 1)
        self.assertEqual(taper["hosted_raw_found"], 1)
        self.assertEqual(taper["hosted_correction_exclusions"], 1)

    def test_loc_metadata_fails_closed_as_ambiguous_when_marker_is_malformed(self) -> None:
        github = self.github_payload()
        github["body"] = (
            "<!-- firemud:cloc-report:start -->\n"
            "<!-- firemud:cloc-report:metadata {bad} -->\n"
            "<!-- firemud:cloc-report:end -->"
        )
        with patch.object(
            self.reporter.subprocess,
            "run",
            side_effect=self.provider_responses(checker_ok=True, github_payload=github),
        ):
            report = self.reporter.build_report("owner/repo", 42)
        self.assertEqual(report["loc_metadata"]["status"], "ambiguous")

    def test_loc_metadata_checks_current_base_and_merge_base_when_available(self) -> None:
        github = self.github_payload()
        github["baseRefOid"] = "d" * 40
        checker = self.checker_payload(ok=True)

        def run(command, **kwargs):
            if command[:3] == ["git", "merge-base", "d" * 40]:
                return subprocess.CompletedProcess(command, 0, "c" * 40, "")
            return self.provider_responses(checker_ok=True, checker_payload=checker, github_payload=github)(
                command, **kwargs
            )

        with patch.object(self.reporter.subprocess, "run", side_effect=run):
            report = self.reporter.build_report("owner/repo", 42)
        self.assertEqual(report["loc_metadata"]["status"], "stale")
        self.assertIn("current PR base", report["loc_metadata"]["reason"])
        self.assertIn("current merge-base", report["loc_metadata"]["reason"])

    def test_null_loc_body_is_reported_as_missing(self) -> None:
        github = self.github_payload()
        github["body"] = None
        with patch.object(
            self.reporter.subprocess,
            "run",
            side_effect=self.provider_responses(checker_ok=True, github_payload=github),
        ):
            report = self.reporter.build_report("owner/repo", 42)
        self.assertEqual(report["loc_metadata"]["status"], "missing")

    def test_durable_hosted_trigger_is_reported_as_separate_transition_evidence(self) -> None:
        trigger = {
            "trigger_state": {
                "state": "completed",
                "terminal": True,
                "attributed": True,
                "repository": "OWNER/REPO",
                "pr_number": 42,
                "head_sha": "0123456789abcdef0123456789abcdef01234567",
                "current_head_sha": "0123456789abcdef0123456789abcdef01234567",
                "trigger_comment_id": 101,
                "trigger_created_at": "2026-09-14T00:05:00Z",
                "trigger_url": "https://example.test/comments/101",
                "trigger_type": "full",
                "response_id": 102,
                "response_created_at": "2026-09-14T00:10:00Z",
                "response_url": "https://example.test/comments/102",
                "cooldown_until": None,
                "reason": "the captured Hosted review completed",
            }
        }
        checker = self.checker_payload(ok=True)
        checker.update(trigger)
        with tempfile.TemporaryDirectory() as directory:
            record = Path(directory) / "trigger.json"
            record.write_text("{}", encoding="utf-8")
            commands = []

            def run(command, **kwargs):
                commands.append(command)
                if "--trigger-record" in command:
                    return subprocess.CompletedProcess(command, 0, json.dumps(checker), "")
                return self.provider_responses(checker_ok=True)(command, **kwargs)

            with (
                patch.object(self.reporter, "hosted_trigger_record_path", return_value=record),
                patch.object(self.reporter.subprocess, "run", side_effect=run),
            ):
                report = self.reporter.build_report("owner/repo", 42)
        self.assertTrue(report["hosted_trigger"]["available"])
        self.assertEqual(report["hosted_trigger"]["state"], "completed")
        self.assertEqual(report["hosted_trigger"]["repository"], "OWNER/REPO")
        self.assertEqual(report["hosted_trigger"]["trigger_comment_id"], 101)
        self.assertEqual(
            sum("check-coderabbit-review.py" in " ".join(command) for command in commands),
            1,
        )
        self.assertNotIn(str(record), json.dumps(report))
        self.assertEqual(report["verdict"], "READY")

    def test_ambiguous_hosted_trigger_reason_is_visible_in_human_output(self) -> None:
        trigger = {
            "trigger_state": {
                "state": "ambiguous",
                "terminal": True,
                "attributed": False,
                "repository": "owner/repo",
                "pr_number": 42,
                "head_sha": "0123456789abcdef0123456789abcdef01234567",
                "current_head_sha": "fedcba9876543210fedcba9876543210fedcba98",
                "trigger_comment_id": 101,
                "trigger_created_at": "2026-09-14T00:05:00Z",
                "trigger_url": "https://example.test/comments/101",
                "trigger_type": "full",
                "response_id": None,
                "response_created_at": None,
                "response_url": None,
                "cooldown_until": None,
                "reason": "the durable trigger record disagrees with the current PR head",
            }
        }
        checker = self.checker_payload(ok=True)
        checker.update(trigger)
        with tempfile.TemporaryDirectory() as directory:
            record = Path(directory) / "trigger.json"
            record.write_text("{}", encoding="utf-8")
            with (
                patch.object(self.reporter, "hosted_trigger_record_path", return_value=record),
                patch.object(
                    self.reporter.subprocess,
                    "run",
                    side_effect=self.provider_responses(
                        checker_ok=True,
                        checker_exit=1,
                        checker_payload=checker,
                    ),
                ),
            ):
                report = self.reporter.build_report("owner/repo", 42)
        output = io.StringIO()
        with redirect_stdout(output):
            self.reporter.emit_text(report)
        rendered = output.getvalue()
        self.assertIn("trigger: state=ambiguous", rendered)
        self.assertIn(
            "reason=Hosted trigger evidence does not match the current GitHub PR head",
            rendered,
        )

    def test_nonterminal_hosted_trigger_without_response_url_is_preserved(self) -> None:
        trigger = {
            "trigger_state": {
                "state": "awaiting_response",
                "terminal": False,
                "attributed": True,
                "repository": "owner/repo",
                "pr_number": 42,
                "head_sha": "0123456789abcdef0123456789abcdef01234567",
                "current_head_sha": "0123456789abcdef0123456789abcdef01234567",
                "trigger_comment_id": 101,
                "trigger_created_at": "2026-09-14T00:05:00Z",
                "trigger_url": "https://example.test/comments/101",
                "trigger_type": "full",
                "response_id": None,
                "response_created_at": None,
                "response_url": None,
                "cooldown_until": None,
                "reason": "no qualifying response has arrived yet",
            }
        }
        checker = self.checker_payload(ok=True)
        checker.update(trigger)
        with tempfile.TemporaryDirectory() as directory:
            record = Path(directory) / "trigger.json"
            record.write_text("{}", encoding="utf-8")

            def run(command, **kwargs):
                if "--trigger-record" in command:
                    return subprocess.CompletedProcess(command, 2, json.dumps(checker), "")
                return self.provider_responses(checker_ok=True)(command, **kwargs)

            with (
                patch.object(self.reporter, "hosted_trigger_record_path", return_value=record),
                patch.object(self.reporter.subprocess, "run", side_effect=run),
            ):
                report = self.reporter.build_report("owner/repo", 42)
        self.assertEqual(report["hosted_trigger"]["state"], "awaiting_response")
        self.assertIsNone(report["hosted_trigger"]["response_url"])

    def test_unattributed_hosted_trigger_without_trigger_identity_is_preserved(self) -> None:
        trigger = {
            "trigger_state": {
                "state": "unattributed",
                "terminal": True,
                "attributed": False,
                "repository": "owner/repo",
                "pr_number": 42,
                "head_sha": "0123456789abcdef0123456789abcdef01234567",
                "current_head_sha": "0123456789abcdef0123456789abcdef01234567",
                "trigger_comment_id": None,
                "trigger_created_at": None,
                "trigger_url": None,
                "trigger_type": None,
                "response_id": None,
                "response_created_at": None,
                "response_url": None,
                "cooldown_until": None,
                "reason": "the durable posting reservation has no captured response",
            }
        }
        checker = self.checker_payload(ok=True)
        checker.update(trigger)
        with tempfile.TemporaryDirectory() as directory:
            record = Path(directory) / "trigger.json"
            record.write_text("{}", encoding="utf-8")

            def run(command, **kwargs):
                if "--trigger-record" in command:
                    return subprocess.CompletedProcess(command, 1, json.dumps(checker), "")
                return self.provider_responses(checker_ok=True)(command, **kwargs)

            with (
                patch.object(self.reporter, "hosted_trigger_record_path", return_value=record),
                patch.object(self.reporter.subprocess, "run", side_effect=run),
            ):
                report = self.reporter.build_report("owner/repo", 42)
        self.assertEqual(report["hosted_trigger"]["state"], "unattributed")
        self.assertIsNone(report["hosted_trigger"]["trigger_url"])


if __name__ == "__main__":
    unittest.main()
