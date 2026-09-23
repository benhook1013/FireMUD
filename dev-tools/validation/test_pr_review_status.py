#!/usr/bin/env python3
"""Hermetic tests for the unified PR-review status evidence adapter."""

from __future__ import annotations

import json
import sys
import unittest
from pathlib import Path
from unittest.mock import Mock, patch

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "dev-tools"))

from pr_review import cli, github, status

BASE = "a" * 40
HEAD = "b" * 40
MERGE_BASE = "c" * 40
REQUIRED_CONTEXTS = ["Validation Gate", "Security Gate", "License Gate", "Smoke Gate", "CodeQL Gate"]


def checkpoint_payload() -> dict:
    hosted = {
        "comment_id": 101,
        "created_at": "2026-09-21T01:00:00Z",
        "type": "Hosted",
        "raw_found": 2,
        "accepted": 0,
        "reviewed_sha": HEAD[:12],
        "file_count": 4,
        "correction": False,
        "duration_seconds": 14,
    }
    cli = {
        "comment_id": 102,
        "created_at": "2026-09-22T01:00:00Z",
        "type": "CLI",
        "raw_found": 1,
        "accepted": 1,
        "reviewed_sha": HEAD[:12],
        "file_count": 4,
        "correction": False,
    }
    return {
        "checkpoints": [hosted, cli],
        "timeline": [{"kind": "checkpoint", **hosted}, {"kind": "checkpoint", **cli}],
        "warnings": [],
        "unparsed_candidates": 0,
    }


def github_payload(checks: list[dict] | None = None) -> dict:
    default_checks = [
        {
            "__typename": "CheckRun",
            "workflowName": context,
            "name": context,
            "startedAt": "2026-09-23T00:00:00Z",
            "status": "COMPLETED",
            "conclusion": "SUCCESS",
            "head_sha": HEAD,
            "app": {"id": 42, "slug": "github-actions"},
        }
        for context in REQUIRED_CONTEXTS
    ]
    selected_checks = checks or default_checks
    lifecycle = {
        str(value.get(key)).upper()
        for value in selected_checks
        if isinstance(value, dict)
        for key in ("status", "state", "conclusion")
        if value.get(key) is not None
    }
    aggregate_state = "FAILURE" if lifecycle & {"FAILURE", "ERROR", "CANCELLED"} else (
        "PENDING" if lifecycle & {"EXPECTED", "IN_PROGRESS", "PENDING", "QUEUED", "RUNNING"} else "SUCCESS"
    )
    return {
        "data": {
            "repository": {
                "pullRequest": {
                    "number": 2838,
                    "title": "Review controller",
                    "headRefName": "codex/review-controller",
                    "headRefOid": HEAD,
                    "baseRefName": "develop",
                    "baseRefOid": BASE,
                    "changedFiles": 4,
                    "mergeable": "MERGEABLE",
                    "mergeStateStatus": "CLEAN",
                    "isDraft": False,
                    "url": "https://github.test/pull/2838",
                    "commits": {
                        "nodes": [{"commit": {"oid": HEAD, "statusCheckRollup": {"state": aggregate_state}}}]
                    },
                    "body": (
                        "<!-- firemud:cloc-report:start -->\n"
                        "<!-- firemud:cloc-report:metadata "
                        + json.dumps(
                            {
                                "base_oid": BASE,
                                "head_oid": HEAD,
                                "merge_base": MERGE_BASE,
                                "classifier_sha256": "d" * 64,
                            }
                        )
                        + " -->\n"
                        "<!-- firemud:cloc-report:end -->"
                    ),
                    "statusCheckRollup": selected_checks,
                    "required_status_checks": {
                        "available": True,
                        "contexts": REQUIRED_CONTEXTS,
                        "checks": [{"context": context, "app_id": 42} for context in REQUIRED_CONTEXTS],
                    },
                    "reviewThreads": {
                        "nodes": [
                            {"id": "PRRT_1", "isResolved": False, "isOutdated": False},
                            {"id": "PRRT_2", "isResolved": True, "isOutdated": False},
                        ]
                    },
                    "comments": {"nodes": []},
                    "reviews": {"nodes": []},
                }
            }
        }
    }


class StatusTest(unittest.TestCase):
    def _ready_report(self, payload: dict) -> dict:
        with patch.object(status, "_loc_status", return_value={"status": "fresh", "merge_base_checked": True}):
            return status.build_report(
                "owner/repo", 2838, pull_request_payload=payload, checkpoint_payload=checkpoint_payload()
            )

    @staticmethod
    def _coderabbit_review(body: str, commit: str = HEAD, submitted_at: str = "2026-09-23T01:00:00Z") -> dict:
        return {
            "databaseId": 501,
            "author": {"login": "coderabbitai[bot]"},
            "body": body,
            "state": "COMMENTED",
            "submittedAt": submitted_at,
            "url": "https://github.test/reviews/501",
            "commit": {"oid": commit},
        }

    def test_historical_checkpoint_duration_and_counts_are_retained(self) -> None:
        report = status.build_report(
            "owner/repo",
            2838,
            pull_request_payload=github_payload(),
            checkpoint_payload=checkpoint_payload(),
        )
        self.assertEqual(report["review_sequence"][0]["duration_seconds"], 14)
        self.assertEqual(report["checkpoint_counts"]["by_type"]["Hosted"]["count"], 1)
        self.assertEqual(report["checkpoint_counts"]["by_type"]["CLI"]["accepted"], 1)
        self.assertEqual(report["threads"], {"current": 1, "outdated": 0, "total": 1})
        self.assertFalse(report["ready"])

    def test_review_thread_queries_use_opaque_ids_without_database_id(self) -> None:
        self.assertNotIn("databaseId isResolved", github._BASE_QUERY)
        self.assertNotIn("databaseId isResolved", github._connection_query("reviewThreads"))
        report = self._ready_report(github_payload())
        self.assertEqual(report["unresolved_threads"][0]["id"], "PRRT_1")

    def test_live_comment_shape_is_converted_to_historical_checkpoint_evidence(self) -> None:
        payload = github_payload()
        payload["data"]["repository"]["pullRequest"]["comments"] = {
            "nodes": [
                {
                    "id": "101",
                    "databaseId": 101,
                    "body": (
                        f"Hosted: 1 found / 0 accepted · `{HEAD[:12]}` · 4 files · 17s\n"
                        "<!-- firemud-hosted-review: 500 -->\n"
                        "<!-- firemud-review-duration-seconds: 17 -->"
                    ),
                    "createdAt": "2026-09-21T01:00:00Z",
                    "updatedAt": "2026-09-21T01:00:00Z",
                    "author": {"login": "ben"},
                    "url": "https://github.test/comments/101",
                }
            ]
        }
        report = status.build_report("owner/repo", 2838, pull_request_payload=payload)
        self.assertEqual(report["review_sequence"][0]["hosted_review_id"], 500)
        self.assertEqual(report["review_sequence"][0]["duration_seconds"], 17)
        self.assertEqual(report["checkpoint_counts"]["by_type"]["Hosted"]["count"], 1)

    def test_compact_and_json_views_use_the_same_report(self) -> None:
        report = status.build_report(
            "owner/repo", 2838, pull_request_payload=github_payload(), checkpoint_payload=checkpoint_payload()
        )
        compact = status.emit_text(report)
        encoded = json.dumps(report, sort_keys=True)
        self.assertIn("PR #2838", compact)
        self.assertIn(report["verdict"], compact)
        self.assertIn("Review controller", encoded)
        self.assertIn(str(report["threads"]["total"]), encoded)

    def test_ci_coalescing_keeps_latest_failed_check_and_contexts(self) -> None:
        checks = [
            {
                "__typename": "CheckRun",
                "workflowName": "CI",
                "name": "build",
                "startedAt": "2026-09-20T00:00:00Z",
                "status": "COMPLETED",
                "conclusion": "FAILURE",
            },
            {
                "__typename": "CheckRun",
                "workflowName": "CI",
                "name": "build",
                "startedAt": "2026-09-20T01:00:00Z",
                "status": "COMPLETED",
                "conclusion": "SUCCESS",
            },
            {"__typename": "StatusContext", "context": "deploy", "state": "FAILURE"},
        ]
        pending, failed, observed = status.normalize_checks(checks)
        self.assertEqual(observed, 3)
        self.assertEqual([item["name"] for item in failed], ["deploy"])
        self.assertEqual(pending, [])

    def test_malformed_check_fails_closed(self) -> None:
        with self.assertRaises(status.StatusError):
            status.build_report(
                "owner/repo",
                2838,
                pull_request_payload=github_payload([{"name": "build", "status": 3}]),
                checkpoint_payload=checkpoint_payload(),
            )

    def test_status_returns_one_structure_for_compact_and_json_renderers(self) -> None:
        with patch.object(status, "build_report", return_value={"verdict": "READY", "pr_number": 2838}):
            report = status.status(2838, as_json=True, repo="owner/repo")
        self.assertEqual(report["verdict"], "READY")

    def test_2838_required_gates_and_aggregate_success_can_still_be_blocked(self) -> None:
        payload = github_payload()
        pr = payload["data"]["repository"]["pullRequest"]
        pr["mergeStateStatus"] = "BLOCKED"
        pr["reviewThreads"] = {"nodes": []}

        report = self._ready_report(payload)

        self.assertEqual(report["ci"]["required"]["status"], "passed")
        self.assertEqual(
            [item["status"] for item in report["ci"]["required"]["contexts"]],
            ["success"] * 5,
        )
        self.assertEqual(report["ci"]["aggregate"], {"state": "SUCCESS", "source": "github"})
        self.assertEqual(report["threads"]["total"], 0)
        self.assertFalse(report["ready"])
        self.assertEqual(report["verdict"], "BLOCKED — cause not exposed by available API")
        self.assertEqual(report["reasons"], ["BLOCKED — cause not exposed by available API"])
        json.dumps(report)

    def test_2838_close_reopen_fixture_shows_newly_pending_required_gates(self) -> None:
        pending_checks = [
            {
                "__typename": "CheckRun",
                "workflowName": context,
                "name": context,
                "startedAt": "2026-09-23T03:00:00Z",
                "status": "IN_PROGRESS",
                "conclusion": None,
                "head_sha": HEAD,
                "app": {"id": 42, "slug": "github-actions"},
            }
            for context in REQUIRED_CONTEXTS
        ]
        payload = github_payload(pending_checks)
        pr = payload["data"]["repository"]["pullRequest"]
        pr["mergeStateStatus"] = "BLOCKED"
        pr["reviewThreads"] = {"nodes": []}

        report = self._ready_report(payload)

        self.assertEqual(report["ci"]["aggregate"]["state"], "PENDING")
        self.assertEqual(report["ci"]["required"]["status"], "pending")
        self.assertEqual(
            {item["status"] for item in report["ci"]["required"]["contexts"]},
            {"pending"},
        )
        self.assertFalse(report["ready"])
        self.assertIn("required status checks are pending", report["reasons"][-1])

    def test_required_context_prefers_expected_app_over_later_wrong_app(self) -> None:
        payload = github_payload()
        pr = payload["data"]["repository"]["pullRequest"]
        pr["reviewThreads"] = {"nodes": []}
        wrong_app = dict(pr["statusCheckRollup"][0])
        wrong_app.update(
            {
                "startedAt": "2026-09-23T02:00:00Z",
                "conclusion": "FAILURE",
                "app": {"id": 99, "slug": "untrusted-app"},
            }
        )
        pr["statusCheckRollup"].append(wrong_app)

        report = self._ready_report(payload)

        validation = report["ci"]["required"]["contexts"][0]
        self.assertEqual(validation["status"], "success")
        self.assertEqual(validation["result"]["app"]["id"], 42)
        self.assertEqual(report["ci"]["aggregate"]["state"], "SUCCESS")

    def test_required_context_with_only_wrong_app_is_visible_failure(self) -> None:
        payload = github_payload()
        pr = payload["data"]["repository"]["pullRequest"]
        pr["reviewThreads"] = {"nodes": []}
        pr["statusCheckRollup"][0]["app"] = {"id": 99, "slug": "untrusted-app"}
        pr["statusCheckRollup"][0]["conclusion"] = "SUCCESS"

        report = self._ready_report(payload)

        validation = report["ci"]["required"]["contexts"][0]
        self.assertEqual(validation["status"], "wrong_app")
        self.assertEqual(report["ci"]["required"]["status"], "failed")
        self.assertIn("wrong app", " ".join(report["reasons"]))

    def test_missing_or_mismatched_aggregate_head_fails_visibly(self) -> None:
        for mutate in (lambda pr: pr.pop("commits"), lambda pr: pr["commits"]["nodes"][0]["commit"].update({"oid": "c" * 40})):
            with self.subTest(mutate=mutate):
                payload = github_payload()
                pr = payload["data"]["repository"]["pullRequest"]
                pr["reviewThreads"] = {"nodes": []}
                mutate(pr)

                report = self._ready_report(payload)

                self.assertEqual(report["ci"]["aggregate"]["state"], "UNKNOWN")
                self.assertFalse(report["ready"])
                self.assertTrue(any("aggregate rollup" in reason for reason in report["reasons"]))

    def test_optional_cancelled_check_is_separate_from_required_gates(self) -> None:
        payload = github_payload()
        pr = payload["data"]["repository"]["pullRequest"]
        pr["reviewThreads"] = {"nodes": []}
        pr["statusCheckRollup"].append(
            {
                "__typename": "CheckRun",
                "workflowName": "Optional Workflow",
                "name": "Optional Summary",
                "startedAt": "2026-09-23T02:00:00Z",
                "status": "COMPLETED",
                "conclusion": "CANCELLED",
                "head_sha": HEAD,
                "app": {"id": 42, "slug": "github-actions"},
            }
        )
        pr["commits"]["nodes"][0]["commit"]["statusCheckRollup"]["state"] = "FAILURE"

        report = self._ready_report(payload)

        self.assertEqual(report["ci"]["required"]["status"], "passed")
        self.assertEqual([item["name"] for item in report["ci"]["optional"]["failed"]], ["Optional Summary"])
        self.assertEqual(report["ci"]["aggregate"]["state"], "FAILURE")
        self.assertFalse(report["ready"])
        self.assertIn("GitHub aggregate rollup is FAILURE", report["reasons"])

    def test_missing_branch_protection_authority_is_visible_and_blocks_readiness(self) -> None:
        payload = github_payload()
        del payload["data"]["repository"]["pullRequest"]["required_status_checks"]

        report = self._ready_report(payload)

        self.assertFalse(report["ci"]["required"]["available"])
        self.assertIn("authoritative branch protection is unavailable", report["ci"]["required"]["reason"])
        self.assertFalse(report["ready"])

    def test_cli_status_fails_closed_when_snapshots_have_different_base_or_head(self) -> None:
        report = {
            "pull_request": {
                "headRefOid": HEAD,
                "baseRefName": "develop",
                "baseRefOid": BASE,
            },
            "reasons": [],
            "ready": True,
            "verdict": "READY",
        }
        for stack_head, stack_parent_head in (("e" * 40, BASE), (HEAD, "f" * 40)):
            with self.subTest(head=stack_head, parent_head=stack_parent_head):
                controller = Mock()
                controller.status.return_value = {
                    "prs": [
                        {
                            "pr": 2838,
                            "head": stack_head,
                            "base": "develop",
                            "parent_head": stack_parent_head,
                            "reconciliation": "COHERENT",
                            "channels": {"hosted": "COMPLETE", "cli": "COMPLETE"},
                        }
                    ]
                }

                with (
                    patch.object(cli, "default_controller", return_value=controller),
                    patch.object(status, "status", return_value=report),
                ):
                    value, exit_status = cli._dispatch(cli._parser().parse_args(["status", "--pr", "2838", "--json"]))

                self.assertEqual(exit_status, 0)
                self.assertFalse(value["ready"])
                self.assertEqual(value["verdict"], "NOT READY")
                self.assertIn("PR base/head changed between status snapshots", value["reasons"])

    def test_cli_status_remains_ready_when_base_and_head_snapshots_match(self) -> None:
        report = {
            "pull_request": {
                "headRefOid": HEAD,
                "baseRefName": "develop",
                "baseRefOid": BASE,
            },
            "reasons": [],
            "ready": True,
            "verdict": "READY",
        }
        controller = Mock()
        controller.status.return_value = {
            "prs": [
                {
                    "pr": 2838,
                    "head": HEAD,
                    "base": "develop",
                    "parent_head": BASE,
                    "reconciliation": "COHERENT",
                    "channels": {"hosted": "COMPLETE", "cli": "COMPLETE"},
                }
            ]
        }

        with (
            patch.object(cli, "default_controller", return_value=controller),
            patch.object(status, "status", return_value=report),
        ):
            value, exit_status = cli._dispatch(cli._parser().parse_args(["status", "--pr", "2838", "--json"]))

        self.assertEqual(exit_status, 0)
        self.assertTrue(value["ready"], value["reasons"])
        self.assertEqual(value["verdict"], "READY")

    def test_latest_exact_head_summary_only_outside_diff_finding_blocks_readiness(self) -> None:
        payload = github_payload()
        pr = payload["data"]["repository"]["pullRequest"]
        pr["reviewThreads"] = {"nodes": []}
        pr["reviews"] = {
            "nodes": [
                self._coderabbit_review("Duplicate comments (1)", commit="c" * 40),
                self._coderabbit_review("Outside diff range comments (1)"),
            ]
        }

        report = self._ready_report(payload)

        self.assertFalse(report["ready"])
        self.assertEqual(
            report["coderabbit_summary"]["findings"],
            [{"kind": "outside_diff", "count": 1}],
        )
        self.assertTrue(any("actionable duplicate/outside-diff" in reason for reason in report["reasons"]))

    def test_latest_exact_head_summary_replaces_older_actionable_summary(self) -> None:
        payload = github_payload()
        pr = payload["data"]["repository"]["pullRequest"]
        pr["reviewThreads"] = {"nodes": []}
        pr["reviews"] = {
            "nodes": [
                self._coderabbit_review("Duplicate comments (1)", submitted_at="2026-09-23T00:00:00Z"),
                self._coderabbit_review("Duplicate comments (0)", submitted_at="2026-09-23T02:00:00Z"),
            ]
        }

        report = self._ready_report(payload)

        self.assertEqual(report["coderabbit_summary"]["findings"], [])
        self.assertTrue(report["ready"], report["reasons"])

    def test_latest_exact_head_summary_comment_replaces_older_actionable_comment(self) -> None:
        payload = github_payload()
        pr = payload["data"]["repository"]["pullRequest"]
        pr["reviewThreads"] = {"nodes": []}
        pr["comments"] = {
            "nodes": [
                {
                    "databaseId": 601,
                    "author": {"login": "coderabbitai"},
                    "body": (
                        f"Reviewing files that changed from the base of the PR and between `{BASE[:12]}` and `{HEAD[:12]}`.\n\n"
                        "Duplicate comments (1)"
                    ),
                    "createdAt": "2026-09-23T01:30:00Z",
                    "updatedAt": "2026-09-23T01:30:00Z",
                    "url": "https://github.test/comments/601",
                },
                {
                    "databaseId": 602,
                    "author": {"login": "coderabbitai"},
                    "body": f"Reviewing files that changed from the base of the PR and between `{BASE[:12]}` and `{HEAD[:12]}`.\n\nDuplicate comments (0)",
                    "createdAt": "2026-09-23T02:00:00Z",
                    "updatedAt": "2026-09-23T02:00:00Z",
                    "url": "https://github.test/comments/602",
                },
            ]
        }

        report = self._ready_report(payload)

        self.assertTrue(report["ready"], report["reasons"])
        self.assertEqual(report["coderabbit_summary"]["source"], "comment")
        self.assertEqual(report["coderabbit_summary"]["identity"], 602)
        self.assertEqual(report["coderabbit_summary"]["findings"], [])

    def test_malformed_exact_head_summary_fails_closed(self) -> None:
        payload = github_payload()
        payload["data"]["repository"]["pullRequest"]["reviews"] = {
            "nodes": [self._coderabbit_review("Duplicate comments\n\n## Other section\ncontent")]
        }

        with self.assertRaisesRegex(status.StatusError, "summary section has no canonical count"):
            self._ready_report(payload)

    def test_missing_paginated_review_evidence_fails_closed(self) -> None:
        payload = github_payload()
        del payload["data"]["repository"]["pullRequest"]["reviews"]

        with self.assertRaisesRegex(status.StatusError, "summary evidence is malformed or missing"):
            self._ready_report(payload)


if __name__ == "__main__":
    unittest.main()
