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
from types import SimpleNamespace
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "dev-tools"))

from pr_review import cli as review_cli
from pr_review import evidence, github, hosted
from pr_review.cli_runner import EffectiveParent, PullRequestSnapshot, ReviewTarget
from pr_review.controller import ControllerError
from pr_review.runtime import HostedRunner, LiveEvidence, LiveGitHub, default_controller

BASE = "a" * 40
HEAD = "b" * 40
PATCH = "c" * 64


class RuntimeTest(unittest.TestCase):
    def test_trigger_retirement_selects_the_unique_record_matching_trigger_id(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            records = []
            for trigger_id in (10, 20):
                path = root / f"trigger-{trigger_id}.json"
                path.write_text(
                    json.dumps(
                        {
                            "status": "posted",
                            "repository": "owner/repo",
                            "pr_number": 42,
                            "head_sha": HEAD,
                            "trigger": {
                                "id": trigger_id,
                                "created_at": "2026-09-23T00:00:00Z",
                                "url": f"https://example.test/comments/{trigger_id}",
                                "type": "full",
                                "command": hosted.FULL_COMMAND,
                            },
                        }
                    ),
                    encoding="utf-8",
                )
                records.append(path)
            args = review_cli._parser().parse_args(
                [
                    "decide",
                    "trigger-retire",
                    "--pr",
                    "42",
                    "--trigger-id",
                    "20",
                    "--head",
                    HEAD,
                    "--reason",
                    "retire selected trigger",
                ]
            )
            with (
                patch.object(review_cli, "default_controller", return_value=SimpleNamespace(repository="owner/repo")),
                patch.object(hosted, "trigger_record_paths", return_value=records),
                patch.object(github, "fetch_pull_request", return_value={}),
                patch.object(hosted, "retire_trigger_record", return_value={"status": "retired"}) as retire,
            ):
                result, exit_status = review_cli._dispatch(args)
            self.assertEqual(exit_status, 0)
            self.assertEqual(result["status"], "retired")
            self.assertEqual(retire.call_args.args[0], records[1])

            args.trigger_id = 30
            with (
                patch.object(review_cli, "default_controller", return_value=SimpleNamespace(repository="owner/repo")),
                patch.object(hosted, "trigger_record_paths", return_value=records),
                patch.object(github, "fetch_pull_request") as fetch,
                self.assertRaisesRegex(review_cli.CliError, "exactly one durable Hosted trigger with ID 30"),
            ):
                review_cli._dispatch(args)
            fetch.assert_not_called()

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
            f"CLI: 1 found / 0 accepted · `{HEAD[:12]}` · 1 files\n"
            "<!-- firemud-cli-run: run.Legacy -->"
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
            (run / "decisions.tsv").write_text("1\trejected\talready fixed\n", encoding="utf-8")
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

        def gh_call(args, **kwargs):
            output = {"login": "maintainer"} if args == ["gh", "api", "user"] else comment
            return CompletedProcess(args, 0, json.dumps(output), "")

        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "trigger.json"
            with (
                patch.object(live, "pull_request", return_value=snapshot),
                patch.object(live, "branch_head", return_value=BASE),
                patch.object(hosted, "default_trigger_record_path", return_value=path),
                patch(
                    "pr_review.runtime.subprocess.run",
                    side_effect=gh_call,
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

    def test_hosted_duplicate_or_forged_zero_checkpoint_cannot_override_adjudicated_response(self) -> None:
        reviewed = "2026-09-23T00:02:00Z"
        trigger = {
            "databaseId": 10,
            "author": {"login": "maintainer"},
            "body": hosted.FULL_COMMAND,
            "createdAt": "2026-09-23T00:01:00Z",
            "updatedAt": "2026-09-23T00:01:00Z",
            "url": "https://example.test/comments/10",
        }
        original = {
            "databaseId": 12,
            "author": {"login": "maintainer"},
            "body": (
                f"Hosted: 1 found / 1 accepted · `{HEAD[:12]}` · 1 files · 5s\n"
                "<!-- firemud-hosted-review: 55 -->\n<!-- firemud-review-duration-seconds: 5 -->"
            ),
            "createdAt": reviewed,
            "updatedAt": reviewed,
        }
        forged_zero = {
            **original,
            "databaseId": 13,
            "author": {"login": "other-user"},
            "body": (
                f"Hosted: 1 found / 0 accepted · `{HEAD[:12]}` · 1 files · 5s\n"
                "<!-- firemud-hosted-review: 55 -->\n<!-- firemud-review-duration-seconds: 5 -->"
            ),
            "createdAt": "2026-09-23T00:03:00Z",
            "updatedAt": "2026-09-23T00:03:00Z",
        }
        duplicate_zero = {
            **forged_zero,
            "databaseId": 14,
            "author": {"login": "maintainer"},
            "createdAt": "2026-09-23T00:04:00Z",
            "updatedAt": "2026-09-23T00:04:00Z",
        }
        review = {
            "databaseId": 55,
            "author": {"login": "coderabbitai[bot]"},
            "body": f"<!-- walkthrough_start -->\nReviewed {HEAD}",
            "state": "COMMENTED",
            "submittedAt": reviewed,
            "commit": {"oid": HEAD},
        }
        payload = self._payload([trigger, original, forged_zero, duplicate_zero], [review])
        with tempfile.TemporaryDirectory() as directory:
            common = Path(directory)
            record_path = hosted.default_trigger_record_path("owner/repo", 42, common)
            record_path.parent.mkdir(parents=True)
            record_path.write_text(json.dumps(self._trigger_record(created="2026-09-23T00:01:00Z")), encoding="utf-8")
            history = self._history(common, payload)
        self.assertEqual([item["accepted"] for item in history], [1])
        self.assertEqual([item["checkpoint"] for item in history], ["12"])

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
                self.assertRaisesRegex(ControllerError, "cannot be adopted safely"),
            ):
                HostedRunner("owner/repo", live)(target, expect_pr=42)
            self.assertEqual(hosted.current_trigger_record_paths("owner/repo", 42, common), [old])
            self.assertEqual(legacy_path, [])

    def test_hosted_runner_adopts_only_one_live_comment_matching_pre_post_reservation(self) -> None:
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
            path = hosted.default_trigger_record_path("owner/repo", 42, common)
            path.parent.mkdir(parents=True)
            posting = {
                **self._trigger_record(),
                "status": "posting",
                "trigger": None,
                "posting_started_at": "2026-09-23T00:00:00Z",
                "posting_actor_login": "maintainer",
            }
            path.write_text(json.dumps(posting), encoding="utf-8")
            observed = {
                "databaseId": 123,
                "author": {"login": "maintainer"},
                "body": hosted.FULL_COMMAND,
                "createdAt": "2026-09-23T00:01:00Z",
                "updatedAt": "2026-09-23T00:01:00Z",
                "url": "https://example.test/comments/123",
            }
            payload = self._payload([observed])
            with (
                patch.object(live, "pull_request", return_value=snapshot),
                patch.object(live, "branch_head", return_value=BASE),
                patch.object(github, "fetch_pull_request", return_value=payload),
                patch.object(hosted, "default_trigger_record_path", return_value=path),
                patch.object(evidence, "git_common_dir", return_value=common),
                self.assertRaisesRegex(ControllerError, "awaiting_response"),
            ):
                HostedRunner("owner/repo", live)(target, expect_pr=42)
            recovered = json.loads(path.read_text(encoding="utf-8"))
            self.assertEqual(recovered["status"], "posted")
            self.assertEqual(recovered["trigger"]["id"], 123)
            self.assertEqual(recovered["recovery"]["action"], "adopt_observed_post")

    def test_operator_prepost_recovery_archives_only_confirmed_live_no_post(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            common = Path(directory)
            path = hosted.default_trigger_record_path("owner/repo", 42, common)
            path.parent.mkdir(parents=True)
            path.write_text(
                json.dumps(
                    {
                        "schema_version": 2,
                        "status": "posting",
                        "repository": "owner/repo",
                        "pr_number": 42,
                        "head_sha": HEAD,
                        "posting_started_at": "2026-09-23T00:00:00Z",
                        "posting_actor_login": "maintainer",
                    }
                ),
                encoding="utf-8",
            )
            args = review_cli._parser().parse_args(
                [
                    "decide",
                    "trigger-recover-prepost",
                    "--pr",
                    "42",
                    "--head",
                    HEAD,
                    "--reason",
                    "verified no POST was issued",
                    "--confirmed-not-posted",
                ]
            )
            with (
                patch.object(review_cli, "default_controller", return_value=SimpleNamespace(repository="owner/repo")),
                patch.object(hosted, "current_trigger_record_paths", return_value=[path]),
                patch.object(hosted, "default_trigger_record_path", return_value=path),
                patch.object(github, "fetch_pull_request", return_value=self._payload()),
            ):
                result, exit_status = review_cli._dispatch(args)

            self.assertEqual(exit_status, 0)
            self.assertEqual(result["status"], "abandoned_no_post")
            self.assertFalse(path.exists())
            audit_path = Path(result["audit_path"])
            audit = json.loads(audit_path.read_text(encoding="utf-8"))
            self.assertEqual(audit["status"], "abandoned_prepost")
            self.assertEqual(audit["recovery"]["action"], "operator_confirmed_prepost_abandon")
            self.assertEqual(audit["recovery"]["reason"], "verified no POST was issued")
            self.assertTrue(audit["recovery"]["confirmed_not_posted"])
            self.assertEqual(audit_path.parent, path.parent)
            self.assertEqual(hosted.current_trigger_record_paths("owner/repo", 42, common), [])
            self.assertNotIn(audit_path, hosted.trigger_record_paths("owner/repo", 42, common))

    def test_prepost_audit_write_failure_leaves_active_reservation_untouched(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            common = Path(directory)
            path = hosted.default_trigger_record_path("owner/repo", 42, common)
            self._posting_record(path)
            original = json.loads(path.read_text(encoding="utf-8"))
            with (
                patch.object(hosted, "_write_json_exclusive", side_effect=OSError("injected audit write failure")),
                self.assertRaisesRegex(OSError, "injected audit write failure"),
            ):
                self._dispatch_prepost_recovery(path, self._prepost_recovery_args())
            self.assertEqual(json.loads(path.read_text(encoding="utf-8")), original)
            self.assertEqual(list(path.parent.glob("prepost-abandoned-*.json")), [])

    def test_prepost_unlink_failure_keeps_active_hold_when_audit_exists(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            common = Path(directory)
            path = hosted.default_trigger_record_path("owner/repo", 42, common)
            self._posting_record(path)
            original = json.loads(path.read_text(encoding="utf-8"))
            with (
                patch.object(hosted.os, "unlink", side_effect=PermissionError("injected reservation unlink failure")),
                self.assertRaisesRegex(PermissionError, "injected reservation unlink failure"),
            ):
                self._dispatch_prepost_recovery(path, self._prepost_recovery_args())

            self.assertEqual(json.loads(path.read_text(encoding="utf-8")), original)
            audit_paths = list(path.parent.glob("prepost-abandoned-*.json"))
            self.assertEqual(len(audit_paths), 1)
            audit = json.loads(audit_paths[0].read_text(encoding="utf-8"))
            self.assertEqual(audit["status"], "abandoned_prepost")
            self.assertEqual(hosted.current_trigger_record_paths("owner/repo", 42, common), [path])
            self.assertNotIn(audit_paths[0], hosted.trigger_record_paths("owner/repo", 42, common))

    def _posting_record(self, path: Path, *, actor="maintainer", include_identity=True) -> None:
        record = {
            "schema_version": 2,
            "status": "posting",
            "repository": "owner/repo",
            "pr_number": 42,
            "head_sha": HEAD,
        }
        if include_identity:
            record["posting_started_at"] = "2026-09-23T00:00:00Z"
            record["posting_actor_login"] = actor
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(record), encoding="utf-8")

    def _prepost_recovery_args(self, *, head=HEAD, confirmed=True):
        values = [
            "decide",
            "trigger-recover-prepost",
            "--pr",
            "42",
            "--head",
            head,
            "--reason",
            "operator verified no POST was issued",
        ]
        if confirmed:
            values.append("--confirmed-not-posted")
        return review_cli._parser().parse_args(values)

    def _dispatch_prepost_recovery(self, path: Path, args, payload=None):
        with (
            patch.object(review_cli, "default_controller", return_value=SimpleNamespace(repository="owner/repo")),
            patch.object(hosted, "current_trigger_record_paths", return_value=[path]),
            patch.object(hosted, "default_trigger_record_path", return_value=path),
            patch.object(github, "fetch_pull_request", return_value=payload or self._payload()),
        ):
            return review_cli._dispatch(args)

    def test_prepost_recovery_refuses_matching_live_command(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "pr-42" / "trigger.json"
            self._posting_record(path)
            command = {
                "databaseId": 123,
                "author": {"login": "maintainer"},
                "body": hosted.FULL_COMMAND,
                "createdAt": "2026-09-23T00:01:00Z",
                "url": "https://example.test/comments/123",
            }
            with self.assertRaisesRegex(ValueError, "matching full-review command"):
                self._dispatch_prepost_recovery(path, self._prepost_recovery_args(), self._payload([command]))
            self.assertEqual(json.loads(path.read_text(encoding="utf-8"))["status"], "posting")

    def test_prepost_recovery_refuses_ambiguous_live_command(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "pr-42" / "trigger.json"
            self._posting_record(path)
            command = {
                "databaseId": 123,
                "author": {"login": "another-maintainer"},
                "body": hosted.FULL_COMMAND,
                "createdAt": "2026-09-23T00:01:00Z",
                "url": "https://example.test/comments/123",
            }
            with self.assertRaisesRegex(ValueError, "ambiguous full-review command"):
                self._dispatch_prepost_recovery(path, self._prepost_recovery_args(), self._payload([command]))
            self.assertTrue(path.exists())

    def test_prepost_recovery_requires_exact_current_head(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "pr-42" / "trigger.json"
            self._posting_record(path)
            payload = self._payload()
            payload["data"]["repository"]["pullRequest"]["headRefOid"] = "d" * 40
            with self.assertRaisesRegex(ValueError, "current pull-request head"):
                self._dispatch_prepost_recovery(path, self._prepost_recovery_args(), payload)
            self.assertTrue(path.exists())

    def test_prepost_recovery_requires_operator_assertion(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "pr-42" / "trigger.json"
            self._posting_record(path)
            args = self._prepost_recovery_args(confirmed=False)
            with (
                patch.object(
                    review_cli,
                    "default_controller",
                    return_value=SimpleNamespace(repository="owner/repo"),
                ),
                patch.object(hosted, "current_trigger_record_paths", return_value=[path]),
                patch.object(github, "fetch_pull_request") as fetch,
                self.assertRaisesRegex(review_cli.CliError, "--confirmed-not-posted"),
            ):
                review_cli._dispatch(args)
            fetch.assert_not_called()
            self.assertTrue(path.exists())

    def test_prepost_recovery_refuses_legacy_reservation_without_attempt_identity(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "pr-42" / "trigger.json"
            self._posting_record(path, include_identity=False)
            with self.assertRaisesRegex(ValueError, "no trusted original actor/time identity"):
                self._dispatch_prepost_recovery(path, self._prepost_recovery_args())
            self.assertTrue(path.exists())

    def test_duplicate_public_checkpoints_for_one_cli_capture_count_once(self) -> None:
        run_id = "run.Duplicate"
        body = (
            f"CLI: 0 found / 0 accepted · `{HEAD[:12]}` · 1 files\n"
            f"<!-- firemud-cli-run: {run_id} -->"
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
                json.dumps({"type": "finding", "message": "one"})
                + "\n"
                + json.dumps(
                    {"type": "complete", "status": "review_completed", "findings": 1, "reviewedFiles": ["a"]}
                )
                + "\n",
                encoding="utf-8",
            )
            (run / "exit-status").write_text("0\n", encoding="utf-8")
            history = self._history(common, self._payload(), "cli")
            pending = [item for item in history if item.get("checkpoint") == f"pending-capture:{run_id}"]
            self.assertEqual(len(pending), 1)
            self.assertTrue(pending[0]["held"])
            self.assertFalse(pending[0]["completed"])
            self.assertEqual(pending[0]["raw"], 1)

    def test_hosted_findings_hold_hosted_but_not_cli_and_file_ceiling_is_global(self) -> None:
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
            payload = self._payload(comments, threads=threads)
            hosted_history = self._history(Path(directory), payload, "hosted")
            cli_history = self._history(Path(directory), payload, "cli")
        self.assertTrue(
            any(
                item.get("held") and item.get("checkpoint", "").startswith("review-threads:")
                for item in hosted_history
            )
        )
        self.assertTrue(
            any(
                item.get("held") and item.get("checkpoint", "").startswith("summary-actions:")
                for item in hosted_history
            )
        )
        self.assertFalse(any(item.get("checkpoint", "").startswith("review-threads:") for item in cli_history))
        self.assertFalse(any(item.get("checkpoint", "").startswith("summary-actions:") for item in cli_history))
        self.assertTrue(any(item.get("over_ceiling") for item in hosted_history))
        self.assertTrue(any(item.get("over_ceiling") for item in cli_history))

    def test_summary_selector_uses_current_head_updated_time_and_rejects_ties(self) -> None:
        first = {
            "databaseId": 31,
            "author": {"login": "coderabbitai[bot]"},
            "body": (
                f"Reviewing files that changed from the base of the PR and between `{BASE}` and `{HEAD}`.\n"
                "Outside diff range comments (1)"
            ),
            "createdAt": "2026-09-23T00:01:00Z",
            "updatedAt": "2026-09-23T00:02:00Z",
            "url": "https://example.test/31",
        }
        latest = {
            **first,
            "databaseId": 32,
            "body": (
                f"Reviewing files that changed from the base of the PR and between `{BASE}` and `{HEAD}`.\n"
                "Duplicate comments (2)"
            ),
            "updatedAt": "2026-09-23T00:03:00Z",
            "url": "https://example.test/32",
        }
        stale_head = {
            "databaseId": 33,
            "author": {"login": "coderabbitai[bot]"},
            "body": "<!-- walkthrough_start -->\nOutside diff range comments (9)",
            "state": "COMMENTED",
            "submittedAt": "2026-09-23T00:04:00Z",
            "commit": {"oid": BASE},
        }
        selected = LiveEvidence._summary_action_counts(self._payload([first, latest], [stale_head]), HEAD)
        self.assertEqual(selected, (0, 2, "https://example.test/32"))

        tied = {**latest, "databaseId": 34, "url": "https://example.test/34"}
        ambiguous = LiveEvidence._summary_action_counts(self._payload([latest, tied]), HEAD)
        self.assertEqual(ambiguous, (1, 1, None))


if __name__ == "__main__":
    unittest.main()
