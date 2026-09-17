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
    script_parent = str(SCRIPT.parent)
    if script_parent not in sys.path:
        sys.path.insert(0, script_parent)
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

    def setUp(self) -> None:
        self.original_hosted_trigger_record_path = self.reporter.hosted_trigger_record_path
        self.hosted_trigger_record_path_patch = patch.object(
            self.reporter, "hosted_trigger_record_path", return_value=None
        )
        self.hosted_trigger_record_path_patch.start()
        self.addCleanup(self.hosted_trigger_record_path_patch.stop)

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
    def trigger_state(**overrides) -> dict:
        state = {
            "state": "completed",
            "terminal": True,
            "attributed": True,
            "repository": "owner/repo",
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
        state.update(overrides)
        return state

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
                '"classifier_sha256":"cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc",'
                '"head_oid":"0123456789abcdef0123456789abcdef01234567",'
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
            if command[:2] == ["git", "merge-base"]:
                return subprocess.CompletedProcess(command, 0, "b" * 40 + "\n", "")
            if command and command[0] == "gh":
                return subprocess.CompletedProcess(command, 0, json.dumps(github), "")
            raise AssertionError(f"unexpected provider command: {command!r}")

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

    def test_display_sanitizes_invisible_directional_formatting(self) -> None:
        untrusted = "safe\u200b\u061c\u202e\ufff9\ufffa\ufffbvalue\ufeff"
        self.assertEqual(self.reporter._display(untrusted), "safe value")

    def test_display_sanitizes_invisible_compatibility_formatting(self) -> None:
        untrusted = "safe\u00ad\u034f\u180e\ufe00\ufe0fvalue"
        self.assertEqual(self.reporter._display(untrusted), "safe value")

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

    def test_superseded_cancelled_check_run_is_not_actionable(self) -> None:
        checks = [
            {
                "__typename": "CheckRun",
                "workflowName": "CI",
                "name": "build",
                "startedAt": "2026-09-14T00:00:00Z",
                "status": "COMPLETED",
                "conclusion": "CANCELLED",
            },
            {
                "__typename": "CheckRun",
                "workflowName": "CI",
                "name": "build",
                "startedAt": "2026-09-14T01:00:00Z",
                "status": "COMPLETED",
                "conclusion": "SUCCESS",
            },
        ]
        with patch.object(self.reporter.subprocess, "run", side_effect=self.provider_responses(checker_ok=True, checks=checks)):
            report = self.reporter.build_report("owner/repo", 42)
        self.assertEqual(report["ci"]["pending"], [])
        self.assertEqual(report["ci"]["failed"], [])
        self.assertEqual(report["ci"]["observed"], 2)

    def test_latest_check_run_failure_or_pending_remains_actionable(self) -> None:
        cases = (("FAILURE", "failed"), (None, "pending"))
        for conclusion, category in cases:
            with self.subTest(category=category):
                checks = [
                    {
                        "__typename": "CheckRun",
                        "workflowName": "CI",
                        "name": "build",
                        "startedAt": "2026-09-14T00:00:00Z",
                        "status": "COMPLETED",
                        "conclusion": "CANCELLED",
                    },
                    {
                        "__typename": "CheckRun",
                        "workflowName": "CI",
                        "name": "build",
                        "startedAt": "2026-09-14T01:00:00Z",
                        "status": "IN_PROGRESS" if conclusion is None else "COMPLETED",
                        "conclusion": conclusion,
                    },
                ]
                with patch.object(self.reporter.subprocess, "run", side_effect=self.provider_responses(checker_ok=True, checks=checks)):
                    report = self.reporter.build_report("owner/repo", 42)
                self.assertEqual(len(report["ci"][category]), 1)

    def test_check_run_coalescing_fails_closed_for_ties_and_invalid_timestamps(self) -> None:
        checks = [
            {
                "__typename": "CheckRun",
                "workflowName": "CI",
                "name": "tie",
                "startedAt": "2026-09-14T00:00:00Z",
                "status": "COMPLETED",
                "conclusion": "CANCELLED",
            },
            {
                "__typename": "CheckRun",
                "workflowName": "CI",
                "name": "tie",
                "startedAt": "2026-09-14T00:00:00Z",
                "status": "COMPLETED",
                "conclusion": "SUCCESS",
            },
            {
                "__typename": "CheckRun",
                "workflowName": "CI",
                "name": "invalid",
                "startedAt": "not-a-timestamp",
                "status": "COMPLETED",
                "conclusion": "CANCELLED",
            },
            {
                "__typename": "CheckRun",
                "workflowName": "CI",
                "name": "invalid",
                "startedAt": "also-not-a-timestamp",
                "status": "COMPLETED",
                "conclusion": "SUCCESS",
            },
        ]
        with patch.object(self.reporter.subprocess, "run", side_effect=self.provider_responses(checker_ok=True, checks=checks)):
            report = self.reporter.build_report("owner/repo", 42)
        self.assertEqual([item["name"] for item in report["ci"]["failed"]], ["tie", "invalid"])
        self.assertEqual(report["ci"]["observed"], 4)

    def test_malformed_timestamp_blocks_coalescing_for_that_check_identity(self) -> None:
        checks = [
            {
                "__typename": "CheckRun",
                "workflowName": "CI",
                "name": "build",
                "startedAt": "2026-09-14T00:00:00Z",
                "status": "COMPLETED",
                "conclusion": "CANCELLED",
            },
            {
                "__typename": "CheckRun",
                "workflowName": "CI",
                "name": "build",
                "startedAt": "2026-09-14T01:00:00Z",
                "status": "COMPLETED",
                "conclusion": "SUCCESS",
            },
            {
                "__typename": "CheckRun",
                "workflowName": "CI",
                "name": "build",
                "startedAt": "not-a-timestamp",
                "status": "COMPLETED",
                "conclusion": "SUCCESS",
            },
        ]
        with patch.object(self.reporter.subprocess, "run", side_effect=self.provider_responses(checker_ok=True, checks=checks)):
            report = self.reporter.build_report("owner/repo", 42)
        self.assertEqual([item["name"] for item in report["ci"]["failed"]], ["build"])
        self.assertEqual(report["ci"]["observed"], 3)

    def test_malformed_older_check_run_is_validated_before_coalescing(self) -> None:
        for malformed_field in ("conclusion", "detailsUrl"):
            with self.subTest(field=malformed_field):
                older = {
                    "__typename": "CheckRun",
                    "workflowName": "CI",
                    "name": "build",
                    "startedAt": "2026-09-14T00:00:00Z",
                    "status": "COMPLETED",
                    "conclusion": "CANCELLED",
                }
                older[malformed_field] = 42
                checks = [
                    older,
                    {
                        "__typename": "CheckRun",
                        "workflowName": "CI",
                        "name": "build",
                        "startedAt": "2026-09-14T01:00:00Z",
                        "status": "COMPLETED",
                        "conclusion": "SUCCESS",
                    },
                ]
                with patch.object(
                    self.reporter.subprocess,
                    "run",
                    side_effect=self.provider_responses(checker_ok=True, checks=checks),
                ), self.assertRaises(self.reporter.ReportError):
                    self.reporter.build_report("owner/repo", 42)

    def test_status_contexts_are_not_coalesced(self) -> None:
        checks = [
            {"__typename": "StatusContext", "context": "build", "state": "FAILURE"},
            {"__typename": "StatusContext", "context": "build", "state": "SUCCESS"},
        ]
        with patch.object(self.reporter.subprocess, "run", side_effect=self.provider_responses(checker_ok=True, checks=checks)):
            report = self.reporter.build_report("owner/repo", 42)
        self.assertEqual([item["name"] for item in report["ci"]["failed"]], ["build"])
        self.assertEqual(report["ci"]["observed"], 2)

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

    def test_cli_correction_does_not_extend_zero_streak(self) -> None:
        checkpoint = self.checkpoint_payload()
        checkpoint["checkpoints"] = [
            {
                **checkpoint["checkpoints"][0],
                "comment_id": 201,
                "created_at": "2026-09-14T01:00:00Z",
                "type": "CLI",
                "raw_found": 0,
                "accepted": 0,
                "correction": False,
            },
            {
                **checkpoint["checkpoints"][0],
                "comment_id": 202,
                "created_at": "2026-09-15T01:00:00Z",
                "type": "CLI",
                "raw_found": 0,
                "accepted": 0,
                "correction": True,
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
        counts = report["checkpoint_counts"]
        self.assertEqual(counts["by_type"]["CLI"]["count"], 2)
        self.assertEqual(counts["by_type"]["CLI"]["raw_found"], 0)
        self.assertEqual(counts["cli_zero_streak"]["count"], 1)

    def test_cli_correction_does_not_replace_last_accepted_finding(self) -> None:
        checkpoint = self.checkpoint_payload()
        checkpoint["checkpoints"] = [
            {
                **checkpoint["checkpoints"][0],
                "comment_id": 201,
                "created_at": "2026-09-14T01:00:00Z",
                "type": "CLI",
                "raw_found": 1,
                "accepted": 1,
                "correction": False,
            },
            {
                **checkpoint["checkpoints"][0],
                "comment_id": 202,
                "created_at": "2026-09-15T01:00:00Z",
                "type": "CLI",
                "raw_found": 1,
                "accepted": 1,
                "correction": True,
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
        last_accepted = report["checkpoint_counts"]["cli_zero_streak"]["last_accepted_finding"]
        self.assertEqual(last_accepted["comment_id"], 201)
        self.assertEqual(last_accepted["created_at"], "2026-09-14T01:00:00Z")
        self.assertEqual(last_accepted["accepted"], 1)

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
            {
                **checkpoint["checkpoints"][1],
                "created_at": "2026-09-16T01:00:00Z",
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
        self.assertEqual(taper["hosted_zero_zero_streak"], 2)
        self.assertEqual(taper["hosted_raw_positive_accepted_zero_streak"], 0)
        self.assertEqual(taper["hosted_completed_zero_zero_observed"], 2)
        self.assertEqual(taper["hosted_raw_found"], 1)
        self.assertEqual(taper["hosted_correction_exclusions"], 1)

    def test_hosted_taper_skips_correction_in_raw_positive_streak(self) -> None:
        checkpoint = self.checkpoint_payload()
        checkpoint["checkpoints"] = [
            {
                **checkpoint["checkpoints"][1],
                "created_at": "2026-09-13T01:00:00Z",
                "raw_found": 0,
                "accepted": 0,
            },
            {
                **checkpoint["checkpoints"][1],
                "created_at": "2026-09-14T01:00:00Z",
                "raw_found": 2,
                "accepted": 0,
            },
            {
                **checkpoint["checkpoints"][1],
                "created_at": "2026-09-15T01:00:00Z",
                "raw_found": 9,
                "accepted": 0,
                "correction": True,
            },
            {
                **checkpoint["checkpoints"][1],
                "created_at": "2026-09-16T01:00:00Z",
                "raw_found": 1,
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
        self.assertEqual(taper["hosted_raw_positive_accepted_zero_streak"], 2)
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

    def test_loc_metadata_requires_classifier_digest(self) -> None:
        for classifier in ("absent", None, "not-a-digest", "c" * 63, "g" * 64):
            github = self.github_payload()
            metadata_line = github["body"].splitlines()[1]
            metadata = json.loads(
                metadata_line.removeprefix("<!-- firemud:cloc-report:metadata ").removesuffix(" -->")
            )
            if classifier == "absent":
                del metadata["classifier_sha256"]
            else:
                metadata["classifier_sha256"] = classifier
            github["body"] = (
                "<!-- firemud:cloc-report:start -->\n"
                "<!-- firemud:cloc-report:metadata "
                + json.dumps(metadata, separators=(",", ":"))
                + " -->\n"
                "### FireMUD LOC impact\n"
                "<!-- firemud:cloc-report:end -->"
            )
            with self.subTest(classifier=classifier), patch.object(
                self.reporter.subprocess,
                "run",
                side_effect=self.provider_responses(checker_ok=True, github_payload=github),
            ):
                report = self.reporter.build_report("owner/repo", 42)
            self.assertEqual(report["loc_metadata"]["status"], "ambiguous")
            self.assertIn("invalid classifier digest", report["loc_metadata"]["reason"])

    def test_loc_metadata_fails_closed_when_valid_and_malformed_markers_coexist(self) -> None:
        github = self.github_payload()
        valid_marker = github["body"].splitlines()[1]
        github["body"] = (
            "<!-- firemud:cloc-report:start -->\n"
            f"{valid_marker}\n"
            "<!-- firemud:cloc-report:metadata missing-closing-marker\n"
            "<!-- firemud:cloc-report:end -->"
        )
        with patch.object(
            self.reporter.subprocess,
            "run",
            side_effect=self.provider_responses(checker_ok=True, github_payload=github),
        ):
            report = self.reporter.build_report("owner/repo", 42)
        self.assertEqual(report["loc_metadata"]["status"], "ambiguous")
        self.assertIn("multiple LOC metadata markers", report["loc_metadata"]["reason"])

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

    def test_loc_metadata_is_fresh_when_current_merge_base_matches(self) -> None:
        with (
            patch.object(self.reporter, "_current_merge_base", return_value="b" * 40),
            patch.object(
                self.reporter.subprocess,
                "run",
                side_effect=self.provider_responses(checker_ok=True),
            ),
        ):
            report = self.reporter.build_report("owner/repo", 42)

        self.assertEqual(report["loc_metadata"]["status"], "fresh")
        self.assertTrue(report["loc_metadata"]["merge_base_checked"])

    def test_loc_metadata_rejects_inline_copy_when_exact_marker_is_outside_block(self) -> None:
        github = self.github_payload()
        valid_marker = github["body"].splitlines()[1]
        github["body"] = (
            f"{valid_marker}\n"
            "<!-- firemud:cloc-report:start -->\n"
            f"copy: {valid_marker}\n"
            "<!-- firemud:cloc-report:end -->"
        )

        result = self.reporter._loc_metadata_freshness(
            github["body"],
            github["baseRefOid"],
            github["headRefOid"],
            "b" * 40,
        )

        self.assertEqual(result["status"], "ambiguous")
        self.assertIn("outside the marked report block", result["reason"])

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
        trigger = {"trigger_state": self.trigger_state(repository="OWNER/REPO")}
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

    def test_report_owned_trigger_availability_cannot_be_overridden_by_provider(self) -> None:
        checker = self.checker_payload(ok=True)
        checker["trigger_state"] = self.trigger_state(available=False)

        evidence = self.reporter._hosted_trigger_evidence(
            "owner/repo",
            42,
            True,
            checker,
            "0123456789abcdef0123456789abcdef01234567",
        )

        self.assertTrue(evidence["available"])
        self.assertEqual(evidence["state"], "completed")

    def test_malformed_trigger_state_type_fails_with_report_error(self) -> None:
        for invalid in ([], {}):
            checker = self.checker_payload(ok=True)
            checker["trigger_state"] = self.trigger_state(state=invalid)
            with self.subTest(invalid=invalid), self.assertRaisesRegex(
                self.reporter.ReportError, "invalid state"
            ):
                self.reporter._validate_trigger_state(checker, "owner/repo", 42)

    def test_malformed_trigger_state_type_is_reported_ambiguous_during_build_report(self) -> None:
        for invalid in ([], {}):
            checker = self.checker_payload(ok=True)
            checker["trigger_state"] = self.trigger_state(state=invalid)
            with tempfile.TemporaryDirectory() as directory:
                record = Path(directory) / "trigger.json"
                record.write_text("{}", encoding="utf-8")
                with (
                    self.subTest(invalid=invalid),
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
            self.assertEqual(report["hosted_trigger"]["state"], "ambiguous")
            self.assertIn("invalid state", report["hosted_trigger"]["reason"])
            self.assertEqual(report["hosted_trigger"]["validation_outcome"], "invalid")
            self.assertEqual(
                report["hosted_trigger"]["provider_reason"], "the captured Hosted review completed"
            )

    def test_malformed_trigger_state_is_distinct_from_provider_ambiguous_state(self) -> None:
        malformed_checker = self.checker_payload(ok=True)
        malformed_checker["trigger_state"] = self.trigger_state(
            repository="", reason="the provider supplied a malformed trigger state"
        )
        malformed = self.reporter._hosted_trigger_evidence(
            "owner/repo",
            42,
            True,
            malformed_checker,
            "0123456789abcdef0123456789abcdef01234567",
        )

        ambiguous_checker = self.checker_payload(ok=True)
        ambiguous_checker["trigger_state"] = self.trigger_state(
            state="ambiguous", reason="the provider could not attribute the response"
        )
        provider_ambiguous = self.reporter._hosted_trigger_evidence(
            "owner/repo",
            42,
            True,
            ambiguous_checker,
            "0123456789abcdef0123456789abcdef01234567",
        )

        self.assertEqual(malformed["state"], "ambiguous")
        self.assertIn("invalid repository", malformed["reason"])
        self.assertEqual(malformed["validation_outcome"], "invalid")
        self.assertEqual(malformed["provider_state"], "completed")
        self.assertEqual(malformed["provider_reason"], "the provider supplied a malformed trigger state")
        self.assertEqual(provider_ambiguous["state"], "ambiguous")
        self.assertEqual(provider_ambiguous["reason"], "the provider could not attribute the response")
        self.assertEqual(provider_ambiguous["validation_outcome"], "valid")

    def test_trigger_state_pr_number_requires_positive_non_bool_int(self) -> None:
        for invalid in (42.0, True):
            checker = self.checker_payload(ok=True)
            checker["trigger_state"] = self.trigger_state(pr_number=invalid)
            with self.subTest(invalid=invalid), self.assertRaisesRegex(
                self.reporter.ReportError, "invalid pr_number"
            ):
                self.reporter._validate_trigger_state(checker, "owner/repo", 42)

        checker = self.checker_payload(ok=True)
        checker["trigger_state"] = self.trigger_state()
        self.assertEqual(
            self.reporter._validate_trigger_state(checker, "owner/repo", 42)["pr_number"],
            42,
        )

    def test_durable_hosted_trigger_record_is_discovered_from_main_and_linked_worktrees(self) -> None:
        with patch.object(
            self.reporter,
            "hosted_trigger_record_path",
            self.original_hosted_trigger_record_path,
        ), tempfile.TemporaryDirectory() as directory:
            fixture_root = Path(directory).resolve()
            main_root = fixture_root / "main"
            linked_root = fixture_root / "linked"
            worktree_git_dir = main_root / ".git" / "worktrees" / "linked"
            worktree_git_dir.mkdir(parents=True)
            linked_root.mkdir()
            (linked_root / ".git").write_text(
                f"gitdir: {worktree_git_dir}\n", encoding="utf-8"
            )
            (worktree_git_dir / "commondir").write_text("../..\n", encoding="utf-8")
            record = (
                main_root
                / ".git"
                / "coderabbit-review-logs"
                / "hosted"
                / "owner_repo"
                / "pr-42"
                / "trigger.json"
            )
            record.parent.mkdir(parents=True)
            record.write_text("{}", encoding="utf-8")

            trigger = {"trigger_state": self.trigger_state()}
            trigger_payload = self.checker_payload(ok=True)
            trigger_payload.update(trigger)
            record.write_text(json.dumps(trigger_payload), encoding="utf-8")

            with patch.object(self.reporter, "ROOT", main_root):
                self.assertEqual(self.reporter.hosted_trigger_record_path("owner/repo", 42), record)
            with patch.object(self.reporter, "ROOT", linked_root):
                self.assertEqual(self.reporter.hosted_trigger_record_path("owner/repo", 42), record)

            commands = []

            def run(command, **kwargs):
                commands.append(command)
                if "--trigger-record" in command:
                    return subprocess.CompletedProcess(command, 0, record.read_text(encoding="utf-8"), "")
                return self.provider_responses(checker_ok=True)(command, **kwargs)

            with (
                patch.object(self.reporter.subprocess, "run", side_effect=run),
                patch.object(self.reporter, "ROOT", linked_root),
            ):
                report = self.reporter.build_report("owner/repo", 42)
            self.assertTrue(report["hosted_trigger"]["available"])
            self.assertEqual(sum("--trigger-record" in command for command in commands), 1)

            record.unlink()
            commands.clear()
            with (
                patch.object(self.reporter.subprocess, "run", side_effect=run),
                patch.object(self.reporter, "ROOT", linked_root),
            ):
                report = self.reporter.build_report("owner/repo", 42)
            self.assertFalse(report["hosted_trigger"]["available"])
            self.assertEqual(sum("--trigger-record" in command for command in commands), 0)
            with patch.object(self.reporter, "ROOT", main_root):
                self.assertIsNone(self.reporter.hosted_trigger_record_path("owner/repo", 42))
            with patch.object(self.reporter, "ROOT", linked_root):
                self.assertIsNone(self.reporter.hosted_trigger_record_path("owner/repo", 42))

    def test_ambiguous_hosted_trigger_reason_is_visible_in_human_output(self) -> None:
        trigger = {
            "trigger_state": self.trigger_state(
                state="ambiguous",
                attributed=False,
                current_head_sha="fedcba9876543210fedcba9876543210fedcba98",
                response_id=None,
                response_created_at=None,
                response_url=None,
                reason="the durable trigger record disagrees with the current PR head",
            )
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
        self.assertEqual(report["hosted_trigger"]["validation_outcome"], "invalid")

    def test_non_completed_hosted_trigger_reason_is_visible_but_completed_reason_is_omitted(self) -> None:
        reason = "the Hosted review request is currently rate limited"
        for state, checker_exit, expect_reason in (
            ("rate_limited", 1, True),
            ("completed", 0, False),
        ):
            trigger = {
                "trigger_state": self.trigger_state(
                    state=state,
                    terminal=state == "completed",
                    attributed=state == "completed",
                    reason=reason,
                )
            }
            checker = self.checker_payload(ok=True)
            checker.update(trigger)
            with tempfile.TemporaryDirectory() as directory:
                record = Path(directory) / "trigger.json"
                record.write_text("{}", encoding="utf-8")
                with (
                    self.subTest(state=state),
                    patch.object(self.reporter, "hosted_trigger_record_path", return_value=record),
                    patch.object(
                        self.reporter.subprocess,
                        "run",
                        side_effect=self.provider_responses(
                            checker_ok=True,
                            checker_exit=checker_exit,
                            checker_payload=checker,
                        ),
                    ),
                ):
                    report = self.reporter.build_report("owner/repo", 42)
            output = io.StringIO()
            with redirect_stdout(output):
                self.reporter.emit_text(report)
            rendered = output.getvalue()
            expected = f"reason={reason}"
            if expect_reason:
                self.assertIn(expected, rendered)
            else:
                self.assertNotIn(expected, rendered)

    def test_nonterminal_hosted_trigger_without_response_url_is_preserved(self) -> None:
        trigger = {
            "trigger_state": self.trigger_state(
                state="awaiting_response",
                terminal=False,
                response_id=None,
                response_created_at=None,
                response_url=None,
                reason="no qualifying response has arrived yet",
            )
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

    def test_retired_hosted_trigger_state_is_reported(self) -> None:
        trigger = {
            "trigger_state": self.trigger_state(
                state="retired",
                attributed=False,
                response_id=None,
                response_created_at=None,
                response_url=None,
                reason="the stale captured trigger was retired",
            )
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
        self.assertEqual(report["hosted_trigger"]["state"], "retired")
        self.assertEqual(report["hosted_trigger"]["reason"], "the stale captured trigger was retired")

    def test_unattributed_hosted_trigger_without_trigger_identity_is_preserved(self) -> None:
        trigger = {
            "trigger_state": self.trigger_state(
                state="unattributed",
                attributed=False,
                trigger_comment_id=None,
                trigger_created_at=None,
                trigger_url=None,
                trigger_type=None,
                response_id=None,
                response_created_at=None,
                response_url=None,
                reason="the durable posting reservation has no captured response",
            )
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
        report["hosted_trigger"]["head_sha"] = None
        output = io.StringIO()
        with redirect_stdout(output):
            self.reporter.emit_text(report)
        rendered = output.getvalue()
        self.assertIn("trigger: state=unattributed · id=- · head=-", rendered)

    def test_human_loc_output_notes_when_merge_base_is_unchecked(self) -> None:
        with (
            patch.object(self.reporter, "_current_merge_base", return_value=None),
            patch.object(
                self.reporter.subprocess,
                "run",
                side_effect=self.provider_responses(checker_ok=True),
            ),
        ):
            report = self.reporter.build_report("owner/repo", 42)
        output = io.StringIO()
        with redirect_stdout(output):
            self.reporter.emit_text(report)
        self.assertIn("LOC metadata: fresh (merge-base not checked)", output.getvalue())


if __name__ == "__main__":
    unittest.main()
