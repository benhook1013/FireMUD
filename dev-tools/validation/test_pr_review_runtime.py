#!/usr/bin/env python3
"""Focused live-adapter contracts for the unified review controller."""

from __future__ import annotations

import json
import sys
import tempfile
import unittest
from datetime import datetime, timedelta, timezone
from pathlib import Path
from subprocess import CompletedProcess
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "dev-tools"))

from pr_review import evidence, github, hosted
from pr_review.cli_runner import EffectiveParent, PullRequestSnapshot, ReviewTarget
from pr_review.controller import ControllerError
from pr_review.runtime import HostedRunner, LiveEvidence, LiveGitHub, default_controller

BASE = "a" * 40
HEAD = "b" * 40
PATCH = "c" * 64


class RuntimeTest(unittest.TestCase):
    def test_default_controller_derives_the_repository_default_base(self) -> None:
        with patch.object(
            github,
            "repository_metadata",
            return_value={"ref_name": "main", "head_sha": BASE},
        ):
            controller = default_controller("owner/repo")
        self.assertEqual(controller.default_base_ref, "main")

    def test_live_github_projects_exact_metadata_and_paginated_files(self) -> None:
        metadata = {
            "number": 42,
            "state": "OPEN",
            "baseRefName": "develop",
            "baseRefOid": BASE,
            "headRefName": "feature",
            "headRefOid": HEAD,
            "changedFiles": 2,
            "mergeable": "MERGEABLE",
            "mergedAt": None,
        }
        with (
            patch.object(github, "fetch_pr_metadata", return_value=metadata),
            patch.object(
                github,
                "fetch_api_endpoint",
                return_value=[{"filename": "a.txt"}, {"filename": "b.txt"}],
            ),
        ):
            live = LiveGitHub("owner/repo")
            snapshot = live.pull_request(42)
            self.assertEqual(snapshot.head_sha, HEAD)
            self.assertEqual(snapshot.base_sha, BASE)
            self.assertEqual(live.pull_request_files(42), ["a.txt", "b.txt"])

    def test_historical_cli_capture_remains_attributable(self) -> None:
        body = (
            f"CLI: 1 found / 0 accepted · `{HEAD[:12]}` · 1 files · 4s\n"
            "<!-- firemud-cli-run: run.Legacy -->\n"
            "<!-- firemud-review-duration-seconds: 4 -->"
        )
        payload = {
            "data": {
                "repository": {
                    "pullRequest": {
                        "comments": {
                            "nodes": [
                                {
                                    "databaseId": 7,
                                    "body": body,
                                    "createdAt": "2026-09-23T00:00:00Z",
                                    "updatedAt": "2026-09-23T00:00:00Z",
                                }
                            ]
                        },
                        "reviews": {"nodes": []},
                        "reviewThreads": {"nodes": []},
                    }
                }
            }
        }
        snapshot = PullRequestSnapshot(42, "OPEN", "develop", BASE, HEAD, "feature", 1)
        with tempfile.TemporaryDirectory() as directory:
            common = Path(directory)
            run = common / "coderabbit-review-logs" / "run.Legacy"
            run.mkdir(parents=True)
            (run / "metadata").write_text(
                "\n".join(
                    (
                        "run_id=run.Legacy",
                        "repository=owner/repo",
                        "pull_request=42",
                        f"candidate_sha={HEAD}",
                        "candidate_files=1",
                        "",
                    )
                ),
                encoding="utf-8",
            )
            (run / "stdout").write_text(
                json.dumps({"type": "finding", "message": "one"})
                + "\n"
                + json.dumps(
                    {"type": "complete", "status": "review_completed", "findings": 1, "reviewedFiles": ["a.txt"]}
                )
                + "\n",
                encoding="utf-8",
            )
            (run / "exit-status").write_text("0\n", encoding="utf-8")
            live = LiveGitHub("owner/repo")
            with (
                patch.object(github, "fetch_pull_request", return_value=payload),
                patch.object(live, "pull_request", return_value=snapshot),
                patch.object(evidence, "git_common_dir", return_value=common),
            ):
                history = LiveEvidence("owner/repo", live).history(42, "cli")
            self.assertEqual(len(history), 1)
            self.assertTrue(history[0]["completed"])
            self.assertTrue(history[0]["attributable"])
            self.assertFalse(history[0]["anchored"])
            self.assertEqual(history[0]["accepted"], 0)

    def test_hosted_request_persists_exact_anchor_and_verified_comment(self) -> None:
        snapshot = PullRequestSnapshot(42, "OPEN", "develop", BASE, HEAD, "feature", 1)
        target = ReviewTarget(
            snapshot,
            EffectiveParent("develop", BASE),
            patch_identity=PATCH,
            merge_base=BASE,
            repository="owner/repo",
        )
        live = LiveGitHub("owner/repo")
        comment = {
            "id": 123,
            "created_at": "2026-09-23T00:01:00Z",
            "html_url": "https://example.test/123",
            "body": hosted.FULL_COMMAND,
            "user": {"login": "maintainer"},
        }
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "trigger.json"
            with (
                patch.object(live, "pull_request", return_value=snapshot),
                patch.object(live, "branch_head", return_value=BASE),
                patch.object(hosted, "default_trigger_record_path", return_value=path),
                patch(
                    "pr_review.runtime.subprocess.run",
                    return_value=CompletedProcess([], 0, json.dumps(comment), ""),
                ),
            ):
                result = HostedRunner("owner/repo", live)(target, expect_pr=42)
            record = json.loads(path.read_text(encoding="utf-8"))
            self.assertEqual(result["trigger_comment_id"], 123)
            self.assertEqual(record["anchor"]["child_head"], HEAD)
            self.assertEqual(record["anchor"]["parent_head"], BASE)
            self.assertEqual(record["anchor"]["patch_id"], PATCH)

    @staticmethod
    def _payload(comments=None, reviews=None, threads=None):
        return {
            "data": {
                "repository": {
                    "pullRequest": {
                        "headRefOid": HEAD,
                        "comments": {"nodes": comments or []},
                        "reviews": {"nodes": reviews or []},
                        "reviewThreads": {"nodes": threads or []},
                    }
                }
            }
        }

    @staticmethod
    def _trigger_record(head=HEAD, *, created="2026-09-23T00:00:00Z", status="posted"):
        return {
            "schema_version": 2,
            "status": status,
            "repository": "owner/repo",
            "pr_number": 42,
            "head_sha": head,
            "anchor": {
                "pr": 42,
                "child_head": head,
                "parent_identity": "develop",
                "parent_head": BASE,
                "merge_base": BASE,
                "patch_id": PATCH,
            },
            "trigger": {
                "id": 10,
                "created_at": created,
                "url": "https://example.test/comments/10",
                "type": "full",
                "command": hosted.FULL_COMMAND,
            },
        }

    def _history(self, common: Path, payload, channel="hosted"):
        snapshot = PullRequestSnapshot(42, "OPEN", "develop", BASE, HEAD, "feature", 1)
        live = LiveGitHub("owner/repo")
        with (
            patch.object(github, "fetch_pull_request", return_value=payload),
            patch.object(live, "pull_request", return_value=snapshot),
            patch.object(evidence, "git_common_dir", return_value=common),
        ):
            return list(LiveEvidence("owner/repo", live).history(42, channel))

    def test_rate_limit_cooldown_holds_until_deadline_and_unknown_fails_closed(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            common = Path(directory)
            path = hosted.default_trigger_record_path("owner/repo", 42, common)
            path.parent.mkdir(parents=True)
            now = datetime.now(timezone.utc).replace(microsecond=0)
            trigger_time = (now - timedelta(minutes=2)).isoformat().replace("+00:00", "Z")
            record = self._trigger_record(created=trigger_time)
            path.write_text(json.dumps(record), encoding="utf-8")
            trigger = {
                "databaseId": 10,
                "author": {"login": "maintainer"},
                "body": hosted.FULL_COMMAND,
                "createdAt": trigger_time,
                "updatedAt": trigger_time,
                "url": record["trigger"]["url"],
            }
            recent = (now - timedelta(seconds=5)).isoformat().replace("+00:00", "Z")
            future_reply = {
                "databaseId": 11,
                "author": {"login": "coderabbitai"},
                "body": "Review rate limited; next reviews available in 30 minutes",
                "createdAt": recent,
                "updatedAt": recent,
            }
            current = self._history(common, self._payload([trigger, future_reply]))
            self.assertTrue(any(item.get("rate_limited") and item.get("cooldown_until") for item in current))

            old_reply = {**future_reply, "body": "Review rate limited; next reviews available in 1 second"}
            old_reply["createdAt"] = (now - timedelta(minutes=1)).isoformat().replace("+00:00", "Z")
            old_reply["updatedAt"] = old_reply["createdAt"]
            released = self._history(common, self._payload([trigger, old_reply]))
            self.assertFalse(any(item.get("rate_limited") for item in released))

            unknown_reply = {**future_reply, "body": hosted.REVIEW_LIMIT_MARKER}
            unknown = self._history(common, self._payload([trigger, unknown_reply]))
            self.assertTrue(any(item.get("rate_limited") and item.get("unstable") for item in unknown))

    def test_hosted_checkpoint_requires_matching_completed_durable_trigger_and_anchor(self) -> None:
        body = (
            f"Hosted: 1 found / 0 accepted · `{HEAD[:12]}` · 1 files · 5s\n"
            "<!-- firemud-hosted-review: 55 -->\n<!-- firemud-review-duration-seconds: 5 -->"
        )
        now = datetime.now(timezone.utc).replace(microsecond=0)
        created = (now - timedelta(minutes=3)).isoformat().replace("+00:00", "Z")
        reviewed = (now - timedelta(minutes=2)).isoformat().replace("+00:00", "Z")
        trigger = {
            "databaseId": 10,
            "author": {"login": "maintainer"},
            "body": hosted.FULL_COMMAND,
            "createdAt": created,
            "updatedAt": created,
            "url": "https://example.test/comments/10",
        }
        checkpoint = {
            "databaseId": 12,
            "author": {"login": "maintainer"},
            "body": body,
            "createdAt": reviewed,
            "updatedAt": reviewed,
        }
        review = {
            "databaseId": 55,
            "author": {"login": "coderabbitai[bot]"},
            "body": f"<!-- walkthrough_start -->\nReviewed {HEAD}",
            "state": "COMMENTED",
            "submittedAt": reviewed,
            "commit": {"oid": HEAD},
        }
        payload = self._payload([trigger, checkpoint], [review])
        with tempfile.TemporaryDirectory() as directory:
            common = Path(directory)
            record_path = hosted.default_trigger_record_path("owner/repo", 42, common)
            record_path.parent.mkdir(parents=True)
            record = self._trigger_record(created=created)
            record_path.write_text(json.dumps(record), encoding="utf-8")
            history = self._history(common, payload)
            self.assertTrue(any(item.get("completed") and item.get("anchored") for item in history))

            mismatched = {**review, "databaseId": 56}
            history = self._history(common, self._payload([trigger, checkpoint], [mismatched]))
            self.assertFalse(any(item.get("checkpoint") == "12" and item.get("completed") for item in history))

            record["anchor"] = {
                **record["anchor"],
                "child_head": "c" * 40,
            }
            record_path.write_text(json.dumps(record), encoding="utf-8")
            history = self._history(common, payload)
            self.assertFalse(any(item.get("checkpoint") == "12" and item.get("completed") for item in history))

            record["anchor"] = {"child_head": HEAD}
            record_path.write_text(json.dumps(record), encoding="utf-8")
            history = self._history(common, payload)
            self.assertFalse(any(item.get("checkpoint") == "12" and item.get("completed") for item in history))

    def test_legacy_posting_record_in_any_current_location_holds_runner(self) -> None:
        snapshot = PullRequestSnapshot(42, "OPEN", "develop", BASE, HEAD, "feature", 1)
        target = ReviewTarget(
            snapshot,
            EffectiveParent("develop", BASE),
            patch_identity=PATCH,
            merge_base=BASE,
            repository="owner/repo",
        )
        live = LiveGitHub("owner/repo")
        with tempfile.TemporaryDirectory() as directory:
            common = Path(directory)
            legacy_path = hosted.current_trigger_record_paths("owner/repo", 42, common)
            old = common / "coderabbit-review-logs" / "hosted" / "owner_repo" / "pr-42" / "trigger.json"
            old.parent.mkdir(parents=True)
            old.write_text(
                json.dumps(
                    {
                        "status": "posting",
                        "repository": "owner/repo",
                        "pr_number": 42,
                        "head_sha": HEAD,
                    }
                ),
                encoding="utf-8",
            )
            with (
                patch.object(live, "pull_request", return_value=snapshot),
                patch.object(live, "branch_head", return_value=BASE),
                patch.object(github, "fetch_pull_request", return_value=self._payload()),
                patch.object(hosted, "default_trigger_record_path", return_value=common / "firemud" / "new.json"),
                patch.object(evidence, "git_common_dir", return_value=common),
                self.assertRaisesRegex(ControllerError, "ambiguous"),
            ):
                HostedRunner("owner/repo", live)(target, expect_pr=42)
            self.assertEqual(hosted.current_trigger_record_paths("owner/repo", 42, common), [old])
            self.assertEqual(legacy_path, [])

    def test_duplicate_public_checkpoints_for_one_cli_capture_count_once(self) -> None:
        run_id = "run.Duplicate"
        body = (
            f"CLI: 0 found / 0 accepted · `{HEAD[:12]}` · 1 files · 3s\n"
            f"<!-- firemud-cli-run: {run_id} -->\n<!-- firemud-review-duration-seconds: 3 -->"
        )
        comments = [
            {
                "databaseId": comment_id,
                "body": body,
                "createdAt": f"2026-09-23T00:0{minute}:00Z",
                "updatedAt": f"2026-09-23T00:0{minute}:00Z",
            }
            for comment_id, minute in ((1, 1), (2, 2))
        ]
        payload = self._payload(comments)
        with tempfile.TemporaryDirectory() as directory:
            common = Path(directory)
            run = common / "coderabbit-review-logs" / run_id
            run.mkdir(parents=True)
            (run / "metadata").write_text(
                f"run_id={run_id}\nrepository=owner/repo\npull_request=42\ncandidate_sha={HEAD}\ncandidate_files=1\n",
                encoding="utf-8",
            )
            (run / "stdout").write_text(
                json.dumps({"type": "complete", "status": "review_completed", "findings": 0, "reviewedFiles": ["a"]})
                + "\n",
                encoding="utf-8",
            )
            (run / "exit-status").write_text("0\n", encoding="utf-8")
            history = self._history(common, payload, "cli")
            self.assertEqual(sum(item.get("completed", False) for item in history), 1)

    def test_successful_cli_capture_without_checkpoint_is_held(self) -> None:
        run_id = "run.Orphan"
        with tempfile.TemporaryDirectory() as directory:
            common = Path(directory)
            run = common / "coderabbit-review-logs" / run_id
            run.mkdir(parents=True)
            (run / "metadata").write_text(
                f"run_id={run_id}\nrepository=owner/repo\npull_request=42\ncandidate_sha={HEAD}\ncandidate_files=1\n",
                encoding="utf-8",
            )
            (run / "stdout").write_text(
                json.dumps({"type": "complete", "status": "review_completed", "findings": 0, "reviewedFiles": ["a"]})
                + "\n",
                encoding="utf-8",
            )
            (run / "exit-status").write_text("0\n", encoding="utf-8")
            history = self._history(common, self._payload(), "cli")
            pending = [item for item in history if item.get("checkpoint") == f"pending-capture:{run_id}"]
            self.assertEqual(len(pending), 1)
            self.assertTrue(pending[0]["held"])
            self.assertFalse(pending[0]["completed"])

    def test_unresolved_threads_actionable_summary_and_file_ceiling_block_targeting(self) -> None:
        comments = [
            {
                "databaseId": 20,
                "author": {"login": "coderabbitai[bot]"},
                "body": (
                    "<!-- This is an auto-generated comment: skip review by coderabbit.ai -->\n"
                    "Your plan exceeds the file limit for this review."
                ),
                "createdAt": "2026-09-23T00:04:00Z",
                "url": "https://example.test/20",
            },
            {
                "databaseId": 21,
                "author": {"login": "coderabbitai[bot]"},
                "body": (
                    f"Reviewing files that changed from the base of the PR and between `{BASE[:12]}` and `{HEAD[:12]}`.\n"
                    "**Actionable comments posted:** 0\nOutside diff range comments (2)\nDuplicate comments (1)"
                ),
                "createdAt": "2026-09-23T00:03:00Z",
            },
        ]
        threads = [
            {"isResolved": False, "isOutdated": False, "path": "a.py", "line": 1},
            {"isResolved": False, "isOutdated": True, "path": "b.py", "line": 2},
        ]
        with tempfile.TemporaryDirectory() as directory:
            history = self._history(Path(directory), self._payload(comments, threads=threads), "cli")
        self.assertTrue(
            any(item.get("held") and item.get("checkpoint", "").startswith("review-threads:") for item in history)
        )
        self.assertTrue(
            any(item.get("held") and item.get("checkpoint", "").startswith("summary-actions:") for item in history)
        )
        self.assertTrue(any(item.get("over_ceiling") for item in history))


if __name__ == "__main__":
    unittest.main()
