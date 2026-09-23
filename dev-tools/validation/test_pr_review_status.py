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


if __name__ == "__main__":
    unittest.main()
