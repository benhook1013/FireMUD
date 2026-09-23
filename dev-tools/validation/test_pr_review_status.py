#!/usr/bin/env python3
"""Hermetic tests for the unified PR-review status evidence adapter."""

from __future__ import annotations

import json
import sys
import unittest
from pathlib import Path
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "dev-tools"))

from pr_review import status

BASE = "a" * 40
HEAD = "b" * 40
MERGE_BASE = "c" * 40


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
                    "statusCheckRollup": checks or [{"name": "build", "status": "COMPLETED", "conclusion": "SUCCESS"}],
                    "reviewThreads": {
                        "nodes": [{"isResolved": False, "isOutdated": False}, {"isResolved": True, "isOutdated": False}]
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
