#!/usr/bin/env python3
"""Focused proof for the unified GitHub, evidence, and Hosted mechanics."""

from __future__ import annotations

import importlib.util
import json
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
PR_REVIEW = ROOT / "dev-tools" / "pr_review"
sys.path.insert(0, str(PR_REVIEW))


def load(name: str):
    spec = importlib.util.spec_from_file_location(name, PR_REVIEW / f"{name}.py")
    assert spec and spec.loader
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
    spec.loader.exec_module(module)
    return module


github = load("github")
evidence = load("evidence")
hosted = load("hosted")

REPO = "owner/repo"
PR = 42
HEAD = "a" * 40
BASE = "b" * 40


def comment(item_id: int, author: str, body: str, created: str, *, url: str | None = None):
    return {
        "databaseId": item_id,
        "author": {"login": author},
        "body": body,
        "createdAt": created,
        "updatedAt": created,
        "url": url or f"https://example.test/comments/{item_id}",
    }


def review_payload(comments: list[dict] | None = None, reviews: list[dict] | None = None, *, head: str = HEAD):
    return {
        "data": {
            "repository": {
                "pullRequest": {
                    "headRefOid": head,
                    "commits": {"nodes": [{"commit": {"oid": head, "committedDate": "2026-09-23T00:00:00Z"}}]},
                    "reviewThreads": {"nodes": []},
                    "comments": {"nodes": comments or []},
                    "reviews": {"nodes": reviews or []},
                }
            }
        }
    }


def trigger_record(head: str = HEAD):
    return {
        "schema_version": 1,
        "status": "posted",
        "repository": REPO,
        "pr_number": PR,
        "head_sha": head,
        "trigger": {
            "id": 10,
            "created_at": "2026-09-23T00:01:00Z",
            "url": "https://example.test/comments/10",
            "type": "full",
            "command": hosted.FULL_COMMAND,
        },
    }


class GithubAndEvidenceTests(unittest.TestCase):
    def test_graphql_paginates_threads_comments_and_reviews(self):
        initial = {
            "data": {
                "repository": {
                    "pullRequest": {
                        "headRefOid": HEAD,
                        "commits": {"nodes": []},
                        "reviewThreads": {"nodes": ["thread-1"], "pageInfo": {"hasNextPage": True, "endCursor": "t1"}},
                        "comments": {"nodes": ["comment-1"], "pageInfo": {"hasNextPage": True, "endCursor": "c1"}},
                        "reviews": {"nodes": ["review-1"], "pageInfo": {"hasNextPage": True, "endCursor": "r1"}},
                    }
                }
            }
        }
        pages = {
            "reviewThreads": {"nodes": ["thread-2"], "pageInfo": {"hasNextPage": False}},
            "comments": {"nodes": ["comment-2"], "pageInfo": {"hasNextPage": False}},
            "reviews": {"nodes": ["review-2"], "pageInfo": {"hasNextPage": False}},
        }

        def query(_query, variables):
            if variables["after"] == "t1":
                connection = "reviewThreads"
            elif variables["after"] == "c1":
                connection = "comments"
            else:
                connection = "reviews"
            return {"data": {"repository": {"pullRequest": {connection: pages[connection]}}}}

        with patch.object(
            github,
            "run_gh_query",
            side_effect=[
                initial,
                query(None, {"after": "t1"}),
                query(None, {"after": "c1"}),
                query(None, {"after": "r1"}),
            ],
        ):
            payload = github.fetch_pull_request(REPO, PR)
        pr = payload["data"]["repository"]["pullRequest"]
        self.assertEqual(pr["reviewThreads"]["nodes"], ["thread-1", "thread-2"])
        self.assertEqual(pr["comments"]["nodes"], ["comment-1", "comment-2"])
        self.assertEqual(pr["reviews"]["nodes"], ["review-1", "review-2"])

    def test_historical_durationless_and_markered_checkpoint_forms(self):
        comments = [
            {
                "id": 1,
                "body": "Hosted: 2 found / 1 accepted · `abcdef1` · 3 files",
                "created_at": "2026-09-23T00:00:00Z",
            },
            {
                "id": 2,
                "body": "CLI: 1 found / 1 accepted · `abcdef1` · 3 files · 9s\n<!-- firemud-cli-run: run.A1 -->\n<!-- firemud-review-duration-seconds: 9 -->",
                "created_at": "2026-09-23T00:01:00Z",
            },
        ]
        parsed, unparsed = evidence.parse_checkpoint_comments(comments)
        self.assertEqual(unparsed, 0)
        self.assertIsNone(parsed[0].duration_seconds)
        self.assertEqual(parsed[1].duration_seconds, 9)

    def test_malformed_duration_is_explicit_and_not_inferred(self):
        comments = [
            {
                "body": "Hosted: 1 found / 0 accepted · `abcdef1` · 3 files · 12s\n<!-- firemud-review-duration-seconds: nope -->",
                "created_at": "2026-09-23T00:00:00Z",
            }
        ]
        report = evidence.collect_evidence(comments)
        self.assertIsNone(report["checkpoints"][0].get("duration_seconds"))
        self.assertTrue(report["warnings"])

    def test_duration_evidence_requires_matching_visible_and_hidden_values(self):
        comments = [
            {
                "id": 1,
                "body": "Hosted: 0 found / 0 accepted · `abcdef1` · 1 files",
                "created_at": "2026-09-23T00:00:00Z",
            },
            {
                "id": 2,
                "body": "Hosted: 0 found / 0 accepted · `abcdef1` · 1 files · 4s\n"
                "<!-- firemud-hosted-review: 10 -->\n"
                "<!-- firemud-review-duration-seconds: 4 -->",
                "created_at": "2026-09-23T00:01:00Z",
            },
            {
                "id": 3,
                "body": "Hosted: 0 found / 0 accepted · `abcdef1` · 1 files · 4s\n"
                "<!-- firemud-review-duration-seconds: 4 -->",
                "created_at": "2026-09-23T00:02:00Z",
            },
            {
                "id": 4,
                "body": "Hosted: 0 found / 0 accepted · `abcdef1` · 1 files\n"
                "<!-- firemud-review-duration-seconds: 4 -->",
                "created_at": "2026-09-23T00:03:00Z",
            },
            {
                "id": 5,
                "body": "Hosted: 0 found / 0 accepted · `abcdef1` · 1 files · 4s\n"
                "<!-- firemud-review-duration-seconds: nope -->",
                "created_at": "2026-09-23T00:04:00Z",
            },
            {
                "id": 6,
                "body": "Hosted: 0 found / 0 accepted · `abcdef1` · 1 files · 4s\n"
                "<!-- firemud-review-duration-seconds: 5 -->",
                "created_at": "2026-09-23T00:05:00Z",
            },
            {
                "id": 7,
                "body": "Hosted: 0 found / 0 accepted · `abcdef1` · 1 files · 4s\n"
                "<!-- firemud-review-duration-seconds: 4 -->\n"
                "<!-- firemud-review-duration-seconds: 4 -->",
                "created_at": "2026-09-23T00:06:00Z",
            },
        ]
        parsed, unparsed = evidence.parse_checkpoint_comments(comments)

        self.assertEqual(unparsed, 0)
        self.assertIsNone(parsed[0].duration_seconds)
        self.assertFalse(parsed[0].duration_invalid)
        self.assertEqual(parsed[1].duration_seconds, 4)
        self.assertEqual(parsed[2].duration_seconds, 4)
        self.assertFalse(parsed[2].duration_invalid)
        self.assertTrue(all(item.duration_invalid for item in parsed[3:]))
        self.assertTrue(evidence.hosted_checkpoint_evidence(parsed[5], [], HEAD)["status"] == "missing")
        report = evidence.collect_evidence(comments)
        self.assertEqual(report["duration_audit"]["malformed_count"], 1)
        self.assertEqual(report["duration_audit"]["duplicate_count"], 1)
        self.assertEqual(report["duration_audit"]["missing_count"], 2)
        self.assertEqual(report["duration_audit"]["mismatch_count"], 1)

    def test_malformed_private_capture_fails_closed(self):
        checkpoint = evidence.Checkpoint(
            1, "2026-09-23T00:00:00Z", "CLI", 1, 1, HEAD[:7], 1, False, None, "run.A1", None
        )
        with tempfile.TemporaryDirectory() as directory:
            run = Path(directory) / "coderabbit-review-logs" / checkpoint.run_id
            run.mkdir(parents=True)
            (run / "metadata").write_text(
                "run_id=run.A1\nrepository=owner/repo\npull_request=42\ncandidate_sha=" + HEAD + "\ncandidate_files=1\n"
            )
            (run / "stdout").write_text("not-json\n")
            (run / "exit-status").write_text("0\n")
            with self.assertRaises(evidence.CaptureInvalid):
                evidence.load_cli_capture(checkpoint, REPO, PR, Path(directory))

    def _cli_capture(
        self,
        common: Path,
        *,
        decision_text: str | None,
        rejection_text: str | None = None,
        accepted: int = 0,
    ):
        run_id = "run.Decision"
        run = common / "coderabbit-review-logs" / run_id
        run.mkdir(parents=True)
        (run / "metadata").write_text(
            f"run_id={run_id}\nrepository={REPO}\npull_request={PR}\ncandidate_sha={HEAD}\ncandidate_files=1\n",
            encoding="utf-8",
        )
        (run / "stdout").write_text(
            json.dumps({"type": "finding", "message": "one"})
            + "\n"
            + json.dumps({"type": "complete", "status": "review_completed", "findings": 1, "reviewedFiles": ["a"]})
            + "\n",
            encoding="utf-8",
        )
        (run / "exit-status").write_text("0\n", encoding="utf-8")
        if decision_text is not None:
            (run / "decisions.tsv").write_text(decision_text, encoding="utf-8")
        if rejection_text is not None:
            (run / "rejections.tsv").write_text(rejection_text, encoding="utf-8")
        checkpoint = evidence.Checkpoint(
            1, "2026-09-23T00:00:00Z", "CLI", 1, accepted, HEAD[:12], 1, False, None, run_id, None
        )
        return evidence.load_cli_capture(checkpoint, REPO, PR, common)

    def test_raw_positive_cli_capture_requires_complete_decisions_and_matching_accepted_count(self):
        with tempfile.TemporaryDirectory() as directory:
            common = Path(directory)
            with self.assertRaisesRegex(evidence.CaptureInvalid, "complete linked"):
                self._cli_capture(common, decision_text=None)

        with tempfile.TemporaryDirectory() as directory:
            common = Path(directory)
            with self.assertRaisesRegex(evidence.CaptureInvalid, "accepted count"):
                self._cli_capture(common, decision_text="1\taccepted\tuseful fix\n", accepted=0)

        with tempfile.TemporaryDirectory() as directory:
            common = Path(directory)
            capture = self._cli_capture(common, decision_text="1\trejected\tduplicate finding\n")
            self.assertEqual(capture.decisions, {1: ("rejected", "duplicate finding")})

    def test_cli_duration_evidence_fails_closed_and_valid_duration_checks_capture(self):
        with tempfile.TemporaryDirectory() as directory:
            common = Path(directory)
            run = common / "coderabbit-review-logs" / "run.Decision"
            run.mkdir(parents=True)
            (run / "metadata").write_text(
                f"run_id=run.Decision\nrepository={REPO}\npull_request={PR}\n"
                f"candidate_sha={HEAD}\ncandidate_files=1\nreview_duration_seconds=9\n",
                encoding="utf-8",
            )
            (run / "stdout").write_text(
                json.dumps({"type": "complete", "status": "review_completed", "findings": 0, "reviewedFiles": ["a"]})
                + "\n",
                encoding="utf-8",
            )
            (run / "exit-status").write_text("0\n", encoding="utf-8")
            checkpoint = evidence.Checkpoint(
                1,
                "2026-09-23T00:00:00Z",
                "CLI",
                0,
                0,
                HEAD[:12],
                1,
                False,
                None,
                "run.Decision",
                None,
                9,
            )
            with self.assertRaisesRegex(evidence.CaptureUnavailable, "duration"):
                evidence.load_cli_capture(checkpoint, REPO, PR, common)
            (run / "review-duration-seconds").write_text("8\n", encoding="utf-8")
            with self.assertRaisesRegex(evidence.CaptureInvalid, "duration"):
                evidence.load_cli_capture(checkpoint, REPO, PR, common)
            (run / "review-duration-seconds").write_text("9\n", encoding="utf-8")
            capture = evidence.load_cli_capture(checkpoint, REPO, PR, common)
            self.assertEqual(capture.metadata["review_duration_seconds"], "9")

    def test_historical_rejections_capture_is_attributable_only_for_zero_accepted(self):
        with tempfile.TemporaryDirectory() as directory:
            capture = self._cli_capture(
                Path(directory), decision_text=None, rejection_text="1\tlegacy-ref\tduplicate finding\n"
            )
            self.assertEqual(capture.decisions, {1: ("rejected", "duplicate finding")})

        with tempfile.TemporaryDirectory() as directory, self.assertRaisesRegex(
            evidence.CaptureInvalid, "complete linked|cannot explain"
        ):
            self._cli_capture(Path(directory), decision_text=None, rejection_text="2\tlegacy-ref\tduplicate finding\n")

        with tempfile.TemporaryDirectory() as directory, self.assertRaisesRegex(evidence.CaptureInvalid, "cannot explain"):
            self._cli_capture(
                Path(directory),
                decision_text=None,
                rejection_text="1\tlegacy-ref\tduplicate finding\n",
                accepted=1,
            )

    def test_duplicate_hidden_linkage_markers_are_ambiguous(self):
        comments = [
            {
                "body": (
                    "CLI: 1 found / 0 accepted · `abcdef1` · 1 files\n"
                    "<!-- firemud-cli-run: run.A1 -->\n"
                    "<!-- firemud-cli-run: run.A2 -->"
                ),
                "created_at": "2026-09-23T00:00:00Z",
            },
            {
                "body": (
                    "Hosted: 1 found / 0 accepted · `abcdef1` · 1 files\n"
                    "<!-- firemud-hosted-review: 10 -->\n"
                    "<!-- firemud-hosted-review: 11 -->"
                ),
                "created_at": "2026-09-23T00:01:00Z",
            },
        ]
        parsed, unparsed = evidence.parse_checkpoint_comments(comments)
        self.assertEqual(parsed, [])
        self.assertEqual(unparsed, 2)

    def test_single_hidden_linkage_marker_and_missing_duration_remain_valid(self):
        comments = [
            {
                "body": "CLI: 1 found / 0 accepted · `abcdef1` · 1 files\n<!-- firemud-cli-run: run.A1 -->",
                "created_at": "2026-09-23T00:00:00Z",
            },
            {
                "body": "Hosted: 0 found / 0 accepted · `abcdef1` · 1 files\n<!-- firemud-hosted-review: 10 -->",
                "created_at": "2026-09-23T00:01:00Z",
            },
        ]
        parsed, unparsed = evidence.parse_checkpoint_comments(comments)
        self.assertEqual(unparsed, 0)
        self.assertEqual(parsed[0].run_id, "run.A1")
        self.assertEqual(parsed[1].hosted_review_id, 10)
        self.assertIsNone(parsed[0].duration_seconds)


class HostedEvidenceTests(unittest.TestCase):
    def test_wrong_target_assertion_happens_before_request_preparation(self):
        with self.assertRaises(ValueError):
            hosted.prepare_full_trigger(PR, PR + 1)
        self.assertEqual(hosted.prepare_full_trigger(PR)["command"], hosted.FULL_COMMAND)

    def test_rate_limit_cannot_count_as_completed_review(self):
        trigger = comment(10, "owner", hosted.FULL_COMMAND, "2026-09-23T00:01:00Z")
        reply = comment(
            11, "coderabbitai", "Review rate limited; next reviews available in 30 minutes", "2026-09-23T00:02:00Z"
        )
        state = hosted.trigger_state(REPO, PR, review_payload([trigger, reply]), trigger_record())
        self.assertEqual(state.state, "rate_limited")
        self.assertNotEqual(state.state, "completed")

    def test_missing_hosted_evidence_cannot_count_as_completion(self):
        trigger = comment(10, "owner", hosted.FULL_COMMAND, "2026-09-23T00:01:00Z")
        finished = comment(11, "coderabbitai", "Full review finished.", "2026-09-23T00:02:00Z")
        state = hosted.trigger_state(REPO, PR, review_payload([trigger, finished]), trigger_record())
        self.assertEqual(state.state, "awaiting_response")
        checkpoint = evidence.Checkpoint(1, "2026-09-23T00:03:00Z", "Hosted", 0, 0, HEAD[:7], 1, False, None, None, 99)
        self.assertEqual(evidence.hosted_checkpoint_evidence(checkpoint, [], HEAD)["status"], "missing")

    def test_matching_completed_review_is_attributable_and_has_duration(self):
        trigger = comment(10, "owner", hosted.FULL_COMMAND, "2026-09-23T00:01:00Z")
        summary = comment(
            11,
            "coderabbitai",
            f"<!-- walkthrough_start -->\nReviewing files that changed from the base of the PR and between {BASE} and {HEAD}.",
            "2026-09-23T00:02:00Z",
        )
        state = hosted.trigger_state(REPO, PR, review_payload([trigger, summary]), trigger_record())
        self.assertEqual(state.state, "completed")
        self.assertEqual(state.duration_seconds, 60)

    def test_recorded_trigger_author_login_comparison_is_case_insensitive(self):
        trigger = comment(10, "Owner", hosted.FULL_COMMAND, "2026-09-23T00:01:00Z")
        record = trigger_record()
        record["trigger"]["author_login"] = "owner"
        state = hosted.trigger_state(REPO, PR, review_payload([trigger]), record)
        self.assertEqual(state.state, "awaiting_response")
        self.assertTrue(state.attributed)

    def test_suffixed_coderabbit_bot_identity_is_attributable(self):
        trigger = comment(10, "owner", hosted.FULL_COMMAND, "2026-09-23T00:01:00Z")
        summary = comment(
            11,
            "coderabbitai[bot]",
            f"<!-- walkthrough_start -->\nReviewing files that changed from the base of the PR and between {BASE} and {HEAD}.",
            "2026-09-23T00:02:00Z",
        )
        state = hosted.trigger_state(REPO, PR, review_payload([trigger, summary]), trigger_record())
        self.assertEqual(state.state, "completed")

    def test_new_writes_use_firemud_and_legacy_paths_remain_discoverable(self):
        with tempfile.TemporaryDirectory() as directory:
            common = Path(directory)
            new = hosted.default_trigger_record_path(REPO, PR, common)
            old = common / "coderabbit-review-logs" / "hosted" / "owner_repo" / "pr-42" / "trigger.json"
            old.parent.mkdir(parents=True)
            old.write_text("{}")
            self.assertTrue(str(new).startswith(str(common / "firemud")))
            self.assertIn(old, hosted.trigger_record_paths(REPO, PR, common))

    def test_legacy_posting_reservation_without_trigger_identity_is_readable_as_ambiguous(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "legacy.json"
            path.write_text(
                json.dumps(
                    {
                        "status": "posting",
                        "repository": REPO,
                        "pr_number": PR,
                        "head_sha": HEAD,
                    }
                ),
                encoding="utf-8",
            )
            record = hosted.load_trigger_reservation(path, REPO, PR)
            state = hosted.trigger_state(REPO, PR, review_payload(), record, path)
            self.assertEqual(state.state, "ambiguous")
            self.assertFalse(state.attributed)

    def test_posting_recovery_fails_closed_when_multiple_live_commands_match(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "trigger.json"
            path.write_text(
                json.dumps(
                    {
                        "schema_version": 2,
                        "status": "posting",
                        "repository": REPO,
                        "pr_number": PR,
                        "head_sha": HEAD,
                        "posting_started_at": "2026-09-23T00:00:00Z",
                        "posting_actor_login": "maintainer",
                    }
                ),
                encoding="utf-8",
            )
            comments = [
                comment(31, "maintainer", hosted.FULL_COMMAND, "2026-09-23T00:01:00Z"),
                comment(32, "maintainer", hosted.FULL_COMMAND, "2026-09-23T00:02:00Z"),
            ]
            with (
                patch.object(hosted, "default_trigger_record_path", return_value=path),
                self.assertRaisesRegex(ValueError, "exactly one live command"),
            ):
                hosted.adopt_posting_reservation(path, REPO, PR, HEAD, review_payload(comments))
            self.assertEqual(json.loads(path.read_text(encoding="utf-8"))["status"], "posting")

    def test_retirement_refuses_an_active_review(self):
        trigger = comment(10, "owner", hosted.FULL_COMMAND, "2026-09-23T00:01:00Z")
        active = comment(11, "coderabbitai", "Full review triggered", "2026-09-23T00:02:00Z")
        payload = review_payload([trigger, active])
        record = trigger_record()
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "trigger.json"
            path.write_text(json.dumps(record), encoding="utf-8")
            lock_path = Path(directory) / "lock-trigger.json"
            with (
                patch.object(hosted, "default_trigger_record_path", return_value=lock_path),
                self.assertRaisesRegex(ValueError, "active"),
            ):
                hosted.retire_trigger_record(path, REPO, PR, 10, HEAD, "stale", payload)

    def test_retirement_accepts_only_durable_later_exact_head_completion(self):
        earlier = trigger_record("c" * 40)
        later = {
            **trigger_record(HEAD),
            "anchor": {
                "child_head": HEAD,
                "parent_identity": "develop",
                "parent_head": BASE,
                "merge_base": BASE,
                "patch_id": "d" * 64,
            },
            "trigger": {
                "id": 20,
                "created_at": "2026-09-23T00:03:00Z",
                "url": "https://example.test/comments/20",
                "type": "full",
                "command": hosted.FULL_COMMAND,
            },
        }
        earlier["status"] = "posted"
        later_trigger = comment(20, "owner", hosted.FULL_COMMAND, "2026-09-23T00:03:00Z")
        completed = {
            **comment(
                21,
                "coderabbitai[bot]",
                f"<!-- walkthrough_start -->\nReviewing files that changed from the base of the PR and between {BASE} and {HEAD}.",
                "2026-09-23T00:04:00Z",
            ),
            "state": "COMMENTED",
            "submittedAt": "2026-09-23T00:04:00Z",
            "commit": {"oid": HEAD},
        }
        payload = review_payload([later_trigger], [completed])
        with tempfile.TemporaryDirectory() as directory:
            common = Path(directory)
            lock_path = hosted.default_trigger_record_path(REPO, PR, common)
            old_path = common / "old.json"
            old_path.write_text(json.dumps(earlier), encoding="utf-8")
            later_path = lock_path
            later_path.parent.mkdir(parents=True)
            later_path.write_text(json.dumps(later), encoding="utf-8")
            with (
                patch.object(hosted, "default_trigger_record_path", return_value=lock_path),
                patch.object(hosted, "trigger_record_paths", return_value=[later_path]),
            ):
                result = hosted.retire_trigger_record(old_path, REPO, PR, 10, HEAD, "superseded", payload)
            self.assertEqual(result["status"], "retired")

            unverified_path = common / "unverified.json"
            unverified_path.write_text(json.dumps(earlier), encoding="utf-8")
            with (
                patch.object(hosted, "default_trigger_record_path", return_value=lock_path),
                patch.object(hosted, "trigger_record_paths", return_value=[]),
                self.assertRaisesRegex(ValueError, "later completed exact-head"),
            ):
                hosted.retire_trigger_record(unverified_path, REPO, PR, 10, HEAD, "superseded", payload)


if __name__ == "__main__":
    unittest.main()
