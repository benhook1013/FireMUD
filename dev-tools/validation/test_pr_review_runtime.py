#!/usr/bin/env python3
"""Focused live-adapter contracts for the unified review controller."""

from __future__ import annotations

import json
import os
import sys
import tempfile
import threading
import unittest
from collections.abc import Mapping
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timedelta, timezone
from pathlib import Path
from subprocess import CompletedProcess
from types import SimpleNamespace
from typing import Any
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "dev-tools"))

from pr_review import cli as review_cli
from pr_review import evidence, github, hosted, sqlite_hosted_capture, sqlite_review_records, sqlite_store
from pr_review.cli_runner import EffectiveParent, PullRequestSnapshot, ReviewRunnerError, ReviewTarget
from pr_review.controller import ControllerError, StaleReviewTarget, _review_activity
from pr_review.runtime import HostedRunner, LiveEvidence, LiveGitHub, default_controller
from pr_review.state import ReviewState, StateStore, SummaryFindingDisposition, observation_fingerprint

BASE = "a" * 40
HEAD = "b" * 40
PATCH = "c" * 64


class RuntimeTest(unittest.TestCase):
    def test_closed_reservations_only_hold_positive_finite_repository_cooldown(self) -> None:
        runner = HostedRunner("owner/repo", LiveGitHub("owner/repo"))
        future = (datetime.now(timezone.utc) + timedelta(minutes=10)).isoformat()
        past = (datetime.now(timezone.utc) - timedelta(minutes=1)).isoformat()
        for name, reset, attributed in (
            ("rate_limited", future, True),
            ("rate_limited", past, True),
            ("rate_limited", None, True),
            ("rate_limited", future, False),
            ("active", None, True),
            ("unattributed", None, False),
            ("completed", None, True),
            ("error", None, False),
        ):
            with (
                self.subTest(name=name, reset=reset, attributed=attributed),
                tempfile.TemporaryDirectory() as directory,
            ):
                path = Path(directory) / "trigger.json"
                state = SimpleNamespace(state=name, cooldown_until=reset, attributed=attributed, terminal=True)
                with (
                    patch.object(runner, "_repository_current_trigger_paths", return_value={99: [path]}),
                    patch.object(hosted, "load_trigger_reservation", return_value={"status": "posted"}),
                    patch.object(github, "fetch_pull_request", return_value=self._payload()),
                    patch.object(
                        hosted,
                        "trigger_state",
                        side_effect=RuntimeError("unreadable") if name == "error" else None,
                        return_value=state,
                    ),
                ):
                    if name == "rate_limited" and reset == future and attributed:
                        with self.assertRaisesRegex(ControllerError, "cooldown.*closed PR"):
                            runner._assert_no_other_active_reservations(42, Path(directory))
                    elif name == "error":
                        with self.assertRaisesRegex(ControllerError, "closed PR #99 cannot be verified"):
                            runner._assert_no_other_active_reservations(42, Path(directory))
                    else:
                        runner._assert_no_other_active_reservations(42, Path(directory))
                self.assertFalse((path.parent / "request.lock").exists())

    def test_closed_reservation_unavailable_or_malformed_live_read_fails_closed(self) -> None:
        runner = HostedRunner("owner/repo", LiveGitHub("owner/repo"))
        for failure, reservation_side_effect, pull_request_side_effect in (
            ("reservation", OSError("unreadable reservation"), None),
            ("live fetch", None, RuntimeError("pull request unavailable")),
            ("malformed live payload", None, lambda *_args: {}),
        ):
            with self.subTest(failure=failure), tempfile.TemporaryDirectory() as directory:
                path = Path(directory) / "trigger.json"
                with (
                    patch.object(runner, "_repository_current_trigger_paths", return_value={99: [path]}),
                    patch.object(
                        hosted,
                        "load_trigger_reservation",
                        side_effect=reservation_side_effect,
                        return_value={"status": "posted"},
                    ),
                    patch.object(
                        github,
                        "fetch_pull_request",
                        side_effect=pull_request_side_effect,
                        return_value=self._payload(),
                    ),
                    self.assertRaisesRegex(ControllerError, "closed PR #99 cannot be verified"),
                ):
                    runner._assert_no_other_active_reservations(42, Path(directory))

    def test_stopped_request_projection_never_reads_idle_historical_evidence(self) -> None:
        provider = LiveEvidence("owner/repo", LiveGitHub("owner/repo"))
        with (
            tempfile.TemporaryDirectory() as directory,
            patch.object(evidence, "resolve_cli_capture_context", return_value=(Path(directory), None)),
            patch.object(hosted, "current_trigger_record_paths", return_value=[]),
            patch.object(provider, "_request_lock_is_held", return_value=False),
            patch.object(provider, "_payload", side_effect=RuntimeError("broken archived history")) as full,
            patch.object(evidence, "discover_cli_captures", side_effect=AssertionError("archive read")),
        ):
            self.assertEqual(provider.request_history(42, "cli"), [])
            self.assertEqual(provider.request_history(42, "hosted"), [])
            full.assert_not_called()

    def test_stopped_terminal_noncounting_reply_needs_no_historical_attribution(self) -> None:
        record = self._trigger_record()
        trigger = {
            "databaseId": 10,
            "author": {"login": "reviewer"},
            "body": hosted.FULL_COMMAND,
            "createdAt": record["trigger"]["created_at"],
            "url": record["trigger"]["url"],
        }
        reply = {
            "databaseId": 11,
            "author": {"login": "coderabbitai"},
            "body": "Full review finished.",
            "createdAt": "2026-09-23T00:02:00Z",
            "url": "https://example.test/comments/11",
        }
        identity = self._payload([trigger, reply])["data"]["repository"]["pullRequest"]
        identity.pop("reviewThreads")  # Activity proof does not provide complete finding evidence.
        provider = LiveEvidence("owner/repo", LiveGitHub("owner/repo"))
        with (
            patch.object(hosted, "current_trigger_record_paths", return_value=[Path("/unused/trigger.json")]),
            patch.object(hosted, "load_trigger_reservation", return_value=record),
            patch.object(provider, "_request_lock_is_held", return_value=False),
            patch.object(provider.live, "batch_pull_requests", return_value={42: identity}),
            patch.object(provider, "_payload", side_effect=RuntimeError("broken old checkpoint")) as full,
        ):
            rows = provider.request_history(42, "hosted")
            self.assertFalse(any(row.get("active_reservation") for row in rows))
            self.assertFalse(any(row.get("completed") for row in rows))
            full.assert_not_called()

    def test_stopped_current_unknown_activity_remains_fail_closed(self) -> None:
        provider = LiveEvidence("owner/repo", LiveGitHub("owner/repo"))
        with (
            tempfile.TemporaryDirectory() as directory,
            patch.object(evidence, "resolve_cli_capture_context", return_value=(Path(directory), None)),
            patch.object(provider, "_request_lock_is_held", return_value=True),
        ):
            self.assertTrue(provider.request_history(42, "cli")[0]["active_review"])
            self.assertTrue(provider.request_history(42, "hosted")[0]["active_reservation"])
        identity = self._payload()["data"]["repository"]["pullRequest"]
        with (
            patch.object(hosted, "current_trigger_record_paths", return_value=[Path("/unused/trigger.json")]),
            patch.object(hosted, "load_trigger_reservation", return_value=self._trigger_record()),
            patch.object(provider, "_request_lock_is_held", return_value=False),
            patch.object(provider.live, "batch_pull_requests", return_value={42: identity}),
            patch.object(provider, "_payload", side_effect=ControllerError("current request unverified")),
            self.assertRaisesRegex(ControllerError, "current request unverified"),
        ):
            provider.request_history(42, "hosted")

    def test_cli_terminal_error_cleanup_is_not_reported_as_an_active_review(self) -> None:
        provider = LiveEvidence("owner/repo", LiveGitHub("owner/repo"))
        with tempfile.TemporaryDirectory() as directory:
            common = Path(directory)
            run_id = f"run.{'a' * 32}"
            cli_root = common / "firemud" / "pr-review"
            failed_capture = cli_root / "runs" / run_id
            failed_capture.mkdir(parents=True)
            (failed_capture / "metadata.json").write_text(
                json.dumps({"run_id": run_id, "pull_request": 42}), encoding="utf-8"
            )
            (failed_capture / "error").write_text("preflight failed\n", encoding="utf-8")
            (cli_root / "cli.lock").write_text(f"run_id={run_id}\n", encoding="utf-8")
            with (
                patch.object(evidence, "resolve_cli_capture_context", return_value=(common, None)),
                patch.object(provider, "_request_lock_is_held", return_value=True),
            ):
                self.assertEqual(provider.request_history(42, "cli"), [])

    def test_recognized_cli_terminal_error_is_scoped_to_lock_owner(self) -> None:
        provider = LiveEvidence("owner/repo", LiveGitHub("owner/repo"))
        with tempfile.TemporaryDirectory() as directory:
            common = Path(directory)
            runs = common / "firemud" / "pr-review" / "runs"
            run_id = f"run.{'b' * 32}"
            failed_capture = runs / run_id
            failed_capture.mkdir(parents=True)
            (failed_capture / "metadata.json").write_text(
                json.dumps({"run_id": run_id, "pull_request": 42}), encoding="utf-8"
            )
            (failed_capture / "error").write_text("preflight failed\n", encoding="utf-8")
            (common / "firemud" / "pr-review" / "cli.lock").write_text(f"run_id={run_id}\n", encoding="utf-8")
            active_capture = runs / "run.other"
            active_capture.mkdir(parents=True)
            (active_capture / "metadata.json").write_text(
                json.dumps({"run_id": "run.other", "pull_request": 43, "candidate_sha": HEAD}),
                encoding="utf-8",
            )
            with (
                patch.object(evidence, "resolve_cli_capture_context", return_value=(common, None)),
                patch.object(provider, "_request_lock_is_held", return_value=True),
            ):
                target_observation = provider.request_history(42, "cli")
                other_pr_observation = provider.request_history(43, "cli")

            self.assertEqual(target_observation, [])
            self.assertEqual([item["checkpoint"] for item in other_pr_observation], ["active-cli:unidentified"])

    def test_historical_terminal_error_does_not_identify_a_later_lock_owner(self) -> None:
        provider = LiveEvidence("owner/repo", LiveGitHub("owner/repo"))
        with tempfile.TemporaryDirectory() as directory:
            common = Path(directory)
            cli_root = common / "firemud" / "pr-review"
            old_run_id = f"run.{'c' * 32}"
            old_capture = cli_root / "runs" / old_run_id
            old_capture.mkdir(parents=True)
            (old_capture / "metadata.json").write_text(
                json.dumps({"run_id": old_run_id, "pull_request": 42}), encoding="utf-8"
            )
            (old_capture / "error").write_text("preflight failed\n", encoding="utf-8")
            (cli_root / "cli.lock").write_text(f"run_id=run.{'d' * 32}\n", encoding="utf-8")
            with (
                patch.object(evidence, "resolve_cli_capture_context", return_value=(common, None)),
                patch.object(provider, "_request_lock_is_held", return_value=True),
            ):
                history = provider.request_history(42, "cli")

            self.assertEqual([item["checkpoint"] for item in history], ["active-cli:unidentified"])

    def test_missing_or_malformed_cli_lock_owner_retains_active_fallback(self) -> None:
        provider = LiveEvidence("owner/repo", LiveGitHub("owner/repo"))
        with tempfile.TemporaryDirectory() as directory:
            common = Path(directory)
            cli_root = common / "firemud" / "pr-review"
            run_id = f"run.{'e' * 32}"
            failed_capture = cli_root / "runs" / run_id
            failed_capture.mkdir(parents=True)
            (failed_capture / "metadata.json").write_text(
                json.dumps({"run_id": run_id, "pull_request": 42}), encoding="utf-8"
            )
            (failed_capture / "error").write_text("preflight failed\n", encoding="utf-8")
            lock_path = cli_root / "cli.lock"
            for marker in ("", "not-an-owner\n", f"run_id={run_id}"):
                with self.subTest(marker=marker):
                    lock_path.write_text(marker, encoding="utf-8")
                    with (
                        patch.object(evidence, "resolve_cli_capture_context", return_value=(common, None)),
                        patch.object(provider, "_request_lock_is_held", return_value=True),
                    ):
                        history = provider.request_history(42, "cli")
                    self.assertEqual([item["checkpoint"] for item in history], ["active-cli:unidentified"])

    def test_recognized_cli_lock_owner_reads_only_its_capture_metadata(self) -> None:
        provider = LiveEvidence("owner/repo", LiveGitHub("owner/repo"))
        with tempfile.TemporaryDirectory() as directory:
            common = Path(directory)
            cli_root = common / "firemud" / "pr-review"
            run_id = f"run.{'f' * 32}"
            capture = cli_root / "runs" / run_id
            capture.mkdir(parents=True)
            (capture / "metadata.json").write_text(
                json.dumps({"run_id": run_id, "pull_request": 42, "candidate_sha": HEAD}),
                encoding="utf-8",
            )
            unrelated = cli_root / "runs" / "run.unrelated"
            unrelated.mkdir()
            (unrelated / "metadata.json").write_text(
                json.dumps({"run_id": "run.unrelated", "pull_request": 43, "candidate_sha": HEAD}),
                encoding="utf-8",
            )
            (cli_root / "cli.lock").write_text(f"run_id={run_id}\n", encoding="utf-8")

            with (
                patch.object(provider, "_request_lock_is_held", return_value=True),
                patch.object(Path, "glob", side_effect=AssertionError("recognized owner must not scan run history")),
            ):
                owner_history = provider._active_cli_history(42, common)
                other_pr_history = provider._active_cli_history(43, common, operational_only=True)

            self.assertEqual([item["checkpoint"] for item in owner_history], [f"active-cli:{run_id}"])
            self.assertEqual([item["checkpoint"] for item in other_pr_history], ["active-cli:unidentified"])

    def test_recognized_cli_lock_owner_with_missing_metadata_fails_closed(self) -> None:
        provider = LiveEvidence("owner/repo", LiveGitHub("owner/repo"))
        with tempfile.TemporaryDirectory() as directory:
            common = Path(directory)
            cli_root = common / "firemud" / "pr-review"
            runs = cli_root / "runs"
            runs.mkdir(parents=True)
            (runs / "run.other").mkdir()
            (runs / "run.other" / "metadata.json").write_text(
                json.dumps({"run_id": "run.other", "pull_request": 42, "candidate_sha": HEAD}),
                encoding="utf-8",
            )
            owner_run_id = f"run.{'a' * 32}"
            (cli_root / "cli.lock").write_text(f"run_id={owner_run_id}\n", encoding="utf-8")

            with (
                patch.object(provider, "_request_lock_is_held", return_value=True),
                patch.object(Path, "glob", side_effect=AssertionError("recognized owner must not scan run history")),
            ):
                history = provider._active_cli_history(42, common, operational_only=True)

            self.assertEqual([item["checkpoint"] for item in history], ["active-cli:unidentified"])

    def test_unknown_cli_lock_owner_still_scans_active_captures(self) -> None:
        provider = LiveEvidence("owner/repo", LiveGitHub("owner/repo"))
        with tempfile.TemporaryDirectory() as directory:
            common = Path(directory)
            cli_root = common / "firemud" / "pr-review"
            run_id = "run.active"
            capture = cli_root / "runs" / run_id
            capture.mkdir(parents=True)
            (capture / "metadata.json").write_text(
                json.dumps({"run_id": run_id, "pull_request": 42, "candidate_sha": HEAD}),
                encoding="utf-8",
            )
            (cli_root / "cli.lock").write_text("owner unavailable\n", encoding="utf-8")

            with (
                patch.object(provider, "_request_lock_is_held", return_value=True),
                patch.object(evidence, "resolve_cli_capture_context", return_value=(common, None)),
            ):
                history = provider.request_history(42, "cli")

            self.assertEqual([item["checkpoint"] for item in history], [f"active-cli:{run_id}"])

    def test_stopped_hosted_projection_preserves_current_cooldown(self) -> None:
        now = datetime.now(timezone.utc)
        record = self._trigger_record(created=(now - timedelta(minutes=2)).isoformat())
        trigger = {
            "databaseId": 10,
            "author": {"login": "reviewer"},
            "body": hosted.FULL_COMMAND,
            "createdAt": record["trigger"]["created_at"],
            "url": record["trigger"]["url"],
        }
        reply = {
            "databaseId": 11,
            "author": {"login": "coderabbitai"},
            "body": "Review rate limited; next reviews available in 30 minutes",
            "createdAt": (now - timedelta(minutes=1)).isoformat(),
        }
        identity = self._payload([trigger, reply])["data"]["repository"]["pullRequest"]
        provider = LiveEvidence("owner/repo", LiveGitHub("owner/repo"))
        with (
            patch.object(hosted, "current_trigger_record_paths", return_value=[Path("/unused/trigger.json")]),
            patch.object(hosted, "load_trigger_reservation", return_value=record),
            patch.object(provider, "_request_lock_is_held", return_value=False),
            patch.object(provider.live, "batch_pull_requests", return_value={42: identity}),
        ):
            self.assertTrue(provider.request_history(42, "hosted")[0]["rate_limited"])

    def test_hosted_admission_keeps_other_pr_cooldown_and_releases_expired_quota(self) -> None:
        runner = HostedRunner("owner/repo", LiveGitHub("owner/repo"))
        for reset in (
            None,
            datetime.now(timezone.utc) + timedelta(minutes=10),
            datetime.now(timezone.utc) - timedelta(minutes=1),
        ):
            with self.subTest(reset=reset), tempfile.TemporaryDirectory() as directory:
                path = Path(directory) / "trigger.json"
                state = SimpleNamespace(state="rate_limited", cooldown_until=reset.isoformat() if reset else None)
                with (
                    patch.object(runner, "_repository_current_trigger_paths", return_value={43: [path]}),
                    patch.object(hosted, "load_trigger_reservation", return_value={"status": "posted"}),
                    patch.object(github, "fetch_pull_request", return_value=self._payload()),
                    patch.object(hosted, "trigger_state", return_value=state),
                ):
                    if reset is None or reset > datetime.now(timezone.utc):
                        with self.assertRaisesRegex(ControllerError, "repository cooldown"):
                            runner._assert_no_other_active_reservations(42, Path(directory))
                    else:
                        runner._assert_no_other_active_reservations(42, Path(directory))

    def setUp(self) -> None:
        def quiet_repository(endpoint: str) -> list[dict[str, Any]]:
            if endpoint == "repos/owner/repo/pulls?state=open&per_page=100":
                return [{"number": 43, "state": "open"}]
            return []

        endpoint_patcher = patch.object(github, "fetch_api_endpoint", side_effect=quiet_repository)
        endpoint_patcher.start()
        self.addCleanup(endpoint_patcher.stop)

    @staticmethod
    def _rest_issue_comment(comment: Mapping[str, Any]) -> dict[str, Any]:
        author = comment.get("author")
        return {
            "id": comment.get("databaseId"),
            "user": {"login": author.get("login")} if isinstance(author, Mapping) else None,
            "body": comment.get("body"),
            "created_at": comment.get("createdAt"),
            "updated_at": comment.get("updatedAt"),
            "html_url": comment.get("url"),
        }

    def test_prepost_abandoned_trigger_audit_requires_the_existing_closure_proof(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "prepost-abandoned-0123456789abcdefabcd.json"
            now = "2026-09-24T00:00:00Z"
            record = {
                "repository": "owner/repo",
                "pr_number": 42,
                "status": "abandoned_prepost",
                "head_sha": HEAD,
                "trigger": None,
                "posting_comment_id_floor": 10,
                "posting_started_at": now,
                "posting_actor_login": "reviewer",
                "recovery": {
                    "action": "operator_confirmed_prepost_abandon",
                    "confirmed_not_posted": True,
                    "live_comment_history": "complete_paginated_no_candidate",
                    "expected_head_sha": HEAD,
                    "captured_head_sha": HEAD,
                    "at": now,
                    "reason": "the POST was never issued",
                },
            }
            path.write_text(json.dumps(record), encoding="utf-8")
            LiveEvidence._validate_prepost_abandoned(path, "owner/repo", 42)

            record["recovery"]["confirmed_not_posted"] = False
            path.write_text(json.dumps(record), encoding="utf-8")
            with self.assertRaisesRegex(ControllerError, "lacks complete closure proof"):
                LiveEvidence._validate_prepost_abandoned(path, "owner/repo", 42)

    def test_unrecorded_public_hosted_responses_must_be_terminal_and_head_bound(self) -> None:
        review = {
            "databaseId": 71,
            "state": "COMMENTED",
            "body": "**Actionable comments posted:** 1",
            "commit": {"oid": HEAD},
        }
        active_comment = {
            "databaseId": 72,
            "body": "Full review triggered. I am reviewing the pull request now.",
            "createdAt": "2026-09-24T00:00:00Z",
        }
        provider_skip = {
            "databaseId": 75,
            "author": {"login": "coderabbitai[bot]"},
            "body": "Review skipped: 121 files exceed the limit of 100.",
            "createdAt": "2026-09-24T00:00:00Z",
        }
        ambiguous_comment = {
            "databaseId": 73,
            "body": "**Actionable comments posted:** 1",
            "createdAt": "2026-09-24T00:00:00Z",
        }
        self.assertEqual(LiveEvidence._public_response_state(review, "submittedAt", {}), "completed")
        self.assertEqual(LiveEvidence._public_response_state(active_comment, "createdAt", {}), "active")
        self.assertEqual(LiveEvidence._public_response_state(provider_skip, "createdAt", {}), "failed")
        self.assertEqual(LiveEvidence._public_response_state(ambiguous_comment, "createdAt", {}), "ambiguous")
        self.assertIsNone(LiveEvidence._public_response_state({"databaseId": 74, "body": ""}, "createdAt", {}))

    def test_auto_generated_summary_is_not_a_response_but_unmatched_review_still_blocks(self) -> None:
        auto_summary = {
            "databaseId": 5748509184,
            "author": {"login": "coderabbitai[bot]"},
            "body": "<!-- This is an auto-generated comment: summarize by coderabbit.ai -->\nPR summary",
            "createdAt": "2026-09-24T00:00:00Z",
        }
        unmatched_review = {
            "databaseId": 91,
            "author": {"login": "coderabbitai[bot]"},
            "body": f"<!-- walkthrough_start -->\nReviewed {HEAD}",
            "state": "COMMENTED",
            "submittedAt": "2026-09-24T00:01:00Z",
            "commit": {"oid": HEAD},
        }
        self.assertEqual(
            LiveEvidence._bot_response_ids([auto_summary], [unmatched_review]),
            [(91, datetime(2026, 9, 24, 0, 1, tzinfo=timezone.utc))],
        )

        payload = self._payload([auto_summary], [unmatched_review])
        pull = payload["data"]["repository"]["pullRequest"]
        pull.update(
            {
                "number": 42,
                "baseRefName": "develop",
                "baseRefOid": BASE,
            }
        )
        snapshot = PullRequestSnapshot(42, "OPEN", "develop", BASE, HEAD, "feature", 1)
        live = LiveGitHub("owner/repo")
        observer = LiveEvidence("owner/repo", live)
        with (
            patch.object(github, "fetch_pull_request", return_value=payload),
            patch.object(live, "pull_request", return_value=snapshot),
            patch.object(observer, "_complete_trigger_paths", return_value=[]),
            patch.object(observer, "history", return_value=[]),
        ):
            audit = observer.legacy_transition_reauthorization_audit(
                42,
                (),
                {"child_head": HEAD, "live_base_ref": "develop", "live_base_tip": BASE},
            )
        self.assertIn("response has no preceding full-review trigger", audit["unmatched_responses"])

    def test_stop_audit_keeps_old_unanchored_checkpoint_historical_but_holds_current_one(self) -> None:
        old_head = "7" * 40

        def run_audit(reviewed_head: str):
            checkpoint = {
                "databaseId": 12,
                "author": {"login": "maintainer"},
                "body": (
                    f"Hosted: 5 found / 5 accepted · `{reviewed_head[:12]}` · 1 files\n"
                    "<!-- firemud-hosted-review: 55 -->"
                ),
                "createdAt": "2026-09-24T00:02:00Z",
                "updatedAt": "2026-09-24T00:02:00Z",
            }
            payload = self._payload([checkpoint], head=HEAD)
            pull = payload["data"]["repository"]["pullRequest"]
            pull.update({"number": 42, "baseRefName": "develop", "baseRefOid": BASE})
            snapshot = PullRequestSnapshot(42, "OPEN", "develop", BASE, HEAD, "feature", 1)
            live = LiveGitHub("owner/repo")
            observer = LiveEvidence("owner/repo", live)
            with (
                patch.object(github, "fetch_pull_request", return_value=payload),
                patch.object(live, "pull_request", return_value=snapshot),
                patch.object(observer, "_complete_trigger_paths", return_value=[]),
                patch.object(observer, "history", return_value=[]),
            ):
                return observer.legacy_transition_reauthorization_audit(
                    42,
                    (),
                    {"child_head": HEAD, "live_base_ref": "develop", "live_base_tip": BASE},
                    allow_historical_unmatched=True,
                )

        old_audit = run_audit(old_head)
        self.assertIn(
            "a public Hosted checkpoint has no unique attributable trigger",
            old_audit["historical_unmatched_responses"],
        )
        self.assertEqual(old_audit["unmatched_responses"], [])
        self.assertEqual(old_audit["ambiguous_responses"], [])

        current_audit = run_audit(HEAD)
        self.assertIn(
            "a public Hosted checkpoint has no unique attributable trigger",
            current_audit["ambiguous_responses"],
        )
        self.assertEqual(current_audit["historical_unmatched_responses"], [])

    def test_stop_audit_keeps_unmatched_old_response_historical_but_holds_current_response(self) -> None:
        old_head = "7" * 40

        def run_audit(reviewed_head: str):
            review = {
                "databaseId": 55,
                "author": {"login": "coderabbitai[bot]"},
                "body": "<!-- walkthrough_start -->\nActionable comments posted: 5",
                "state": "COMMENTED",
                "submittedAt": "2026-09-24T00:02:00Z",
                "commit": {"oid": reviewed_head},
            }
            payload = self._payload(reviews=[review], head=HEAD)
            pull = payload["data"]["repository"]["pullRequest"]
            pull.update({"number": 42, "baseRefName": "develop", "baseRefOid": BASE})
            snapshot = PullRequestSnapshot(42, "OPEN", "develop", BASE, HEAD, "feature", 1)
            live = LiveGitHub("owner/repo")
            observer = LiveEvidence("owner/repo", live)
            with (
                patch.object(github, "fetch_pull_request", return_value=payload),
                patch.object(live, "pull_request", return_value=snapshot),
                patch.object(observer, "_complete_trigger_paths", return_value=[]),
                patch.object(observer, "history", return_value=[]),
            ):
                return observer.legacy_transition_reauthorization_audit(
                    42,
                    (),
                    {"child_head": HEAD, "live_base_ref": "develop", "live_base_tip": BASE},
                    allow_historical_unmatched=True,
                )

        old_audit = run_audit(old_head)
        self.assertIn("response has no preceding full-review trigger", old_audit["historical_unmatched_responses"])
        self.assertEqual(old_audit["ambiguous_responses"], [])

        current_audit = run_audit(HEAD)
        self.assertIn("response has no preceding full-review trigger", current_audit["ambiguous_responses"])

    def test_retirement_audit_refreshes_cached_public_and_channel_history(self) -> None:
        stale_payload = self._payload()
        current_trigger = {
            "databaseId": 101,
            "author": {"login": "maintainer"},
            "body": hosted.FULL_COMMAND,
            "createdAt": "2026-09-24T00:02:00Z",
            "updatedAt": "2026-09-24T00:02:00Z",
        }
        current_review = {
            "databaseId": 102,
            "author": {"login": "coderabbitai[bot]"},
            "body": f"<!-- walkthrough_start -->\nReviewed {HEAD}",
            "state": "COMMENTED",
            "submittedAt": "2026-09-24T00:01:00Z",
            "commit": {"oid": HEAD},
        }
        current_payload = self._payload(
            [current_trigger],
            [current_review],
            [
                {
                    "isResolved": False,
                    "isOutdated": True,
                    "comments": {"nodes": [{"databaseId": 103}]},
                }
            ],
        )
        pull = current_payload["data"]["repository"]["pullRequest"]
        pull.update({"number": 42, "baseRefName": "develop", "baseRefOid": BASE})
        snapshot = PullRequestSnapshot(42, "OPEN", "develop", BASE, HEAD, "feature", 1)
        live = LiveGitHub("owner/repo")
        observer = LiveEvidence("owner/repo", live)
        observer._payloads[42] = stale_payload
        observer._histories[(42, "hosted")] = [{"checkpoint": "stale-hosted"}]
        observer._histories[(42, "cli")] = [{"checkpoint": "stale-cli"}]

        with (
            tempfile.TemporaryDirectory() as directory,
            patch.object(evidence, "git_common_dir", return_value=Path(directory)),
            patch.object(github, "fetch_pull_request", return_value=current_payload) as fetch,
            patch.object(live, "pull_request", return_value=snapshot),
            patch.object(observer, "_complete_trigger_paths", return_value=[]),
            patch.object(observer, "history", wraps=observer.history) as history,
        ):
            audit = observer.legacy_transition_reauthorization_audit(
                42,
                (),
                {"child_head": HEAD, "live_base_ref": "develop", "live_base_tip": BASE},
            )

        fetch.assert_called_once_with("owner/repo", 42)
        self.assertCountEqual(
            [call.args for call in history.call_args_list[:2]],
            [(42, "hosted"), (42, "cli")],
        )
        self.assertNotEqual(observer._histories[(42, "hosted")], [{"checkpoint": "stale-hosted"}])
        self.assertNotEqual(observer._histories[(42, "cli")], [{"checkpoint": "stale-cli"}])
        self.assertIn("response has no preceding full-review trigger", audit["unmatched_responses"])
        self.assertIn(
            "a public full-review trigger has no attributable terminal response",
            audit["active_reservations"],
        )

    def test_legacy_checkpoint_is_attributed_only_to_unique_exact_terminal_response(self) -> None:
        trigger_at = "2026-09-24T00:00:00Z"
        response_at = "2026-09-24T00:01:00Z"
        checkpoint_at = "2026-09-24T00:02:00Z"
        trigger = {
            "databaseId": 10,
            "author": {"login": "maintainer"},
            "body": hosted.FULL_COMMAND,
            "createdAt": trigger_at,
            "updatedAt": trigger_at,
            "url": "https://example.test/comments/10",
        }
        unrelated_trigger = {
            **trigger,
            "databaseId": 20,
            "createdAt": "2026-09-24T00:03:00Z",
            "updatedAt": "2026-09-24T00:03:00Z",
            "url": "https://example.test/comments/20",
        }
        checkpoint = {
            "databaseId": 12,
            "author": {"login": "maintainer"},
            "body": (f"Hosted: 0 found / 0 accepted · `{HEAD[:12]}` · 1 files\n<!-- firemud-hosted-review: 55 -->"),
            "createdAt": checkpoint_at,
            "updatedAt": checkpoint_at,
        }
        review = {
            "databaseId": 55,
            "author": {"login": "coderabbitai[bot]"},
            "body": f"<!-- walkthrough_start -->\nReviewed {HEAD}",
            "state": "COMMENTED",
            "submittedAt": response_at,
            "commit": {"oid": HEAD},
        }
        unrelated_response = {
            "databaseId": 56,
            "author": {"login": "coderabbitai[bot]"},
            "body": "Full review finished without an attributable head.",
            "createdAt": "2026-09-24T00:04:00Z",
            "updatedAt": "2026-09-24T00:04:00Z",
        }
        payload = self._payload([trigger, checkpoint, unrelated_trigger, unrelated_response], [review])
        pull = payload["data"]["repository"]["pullRequest"]
        pull.update({"number": 42, "baseRefName": "develop", "baseRefOid": BASE})
        snapshot = PullRequestSnapshot(42, "OPEN", "develop", BASE, HEAD, "feature", 1)
        live = LiveGitHub("owner/repo")
        observer = LiveEvidence("owner/repo", live)
        record = self._trigger_record(created=trigger_at)
        state = SimpleNamespace(
            trigger_comment_id=10,
            trigger_created_at=trigger_at,
            state="completed",
            terminal=True,
            attributed=True,
            response_id=55,
            head_sha=HEAD,
        )
        unrelated_record = {**self._trigger_record(created="2026-09-24T00:03:00Z")}
        unrelated_record["trigger"]["id"] = 20
        unrelated_state = SimpleNamespace(
            trigger_comment_id=20,
            trigger_created_at="2026-09-24T00:03:00Z",
            state="ambiguous",
            terminal=True,
            attributed=False,
            response_id=56,
            head_sha=HEAD,
        )
        records_by_path = {"trigger-10.json": record, "trigger-20.json": unrelated_record}
        states_by_trigger = {10: state, 20: unrelated_state}

        def run_audit(selected_record=record, selected_state=state, selected_checkpoint=checkpoint):
            observer._payloads.clear()
            selected_payload = self._payload(
                [trigger, selected_checkpoint, unrelated_trigger, unrelated_response], [review]
            )
            selected_pull = selected_payload["data"]["repository"]["pullRequest"]
            selected_pull.update({"number": 42, "baseRefName": "develop", "baseRefOid": BASE})
            with (
                patch.object(github, "fetch_pull_request", return_value=selected_payload),
                patch.object(live, "pull_request", return_value=snapshot),
                patch.object(
                    observer,
                    "_complete_trigger_paths",
                    return_value=["trigger-10.json", "trigger-20.json"],
                ),
                patch.object(
                    hosted,
                    "load_trigger_record",
                    side_effect=lambda path, *_: (
                        selected_record if Path(path).name == "trigger-10.json" else records_by_path[Path(path).name]
                    ),
                ),
                patch.object(
                    hosted,
                    "trigger_state",
                    side_effect=lambda _repo, _pr, _payload, selected, _path: (
                        selected_state
                        if selected["trigger"]["id"] == 10
                        else states_by_trigger[selected["trigger"]["id"]]
                    ),
                ),
                patch.object(observer, "history", return_value=[]),
            ):
                return observer.legacy_transition_reauthorization_audit(
                    42,
                    (),
                    {"child_head": HEAD, "live_base_ref": "develop", "live_base_tip": BASE},
                )

        accepted = run_audit()
        self.assertNotIn(
            "a public Hosted checkpoint has no unique attributable trigger",
            accepted["unmatched_responses"],
        )
        self.assertIn("unattributed trigger response", accepted["ambiguous_responses"])
        self.assertIn("ambiguous", accepted["active_reservations"])

        invalid_cases = (
            ({**record, "head_sha": "d" * 40}, state, checkpoint),
            (record, SimpleNamespace(**{**state.__dict__, "terminal": False}), checkpoint),
            (record, state, {**checkpoint, "author": {"login": "different-user"}}),
        )
        for selected_record, selected_state, selected_checkpoint in invalid_cases:
            with self.subTest(record=selected_record, state=selected_state, checkpoint=selected_checkpoint):
                rejected = run_audit(selected_record, selected_state, selected_checkpoint)
                self.assertIn(
                    "a public Hosted checkpoint has no unique attributable trigger",
                    rejected["unmatched_responses"],
                    msg=str(rejected),
                )

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

    def test_trigger_retirement_ignores_unreadable_unrelated_history(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            malformed = root / "trigger-malformed.json"
            malformed.write_text("{not-json", encoding="utf-8")
            valid = root / "trigger-20.json"
            valid.write_text(
                json.dumps(
                    {
                        "status": "posted",
                        "repository": "owner/repo",
                        "pr_number": 42,
                        "head_sha": HEAD,
                        "trigger": {
                            "id": 20,
                            "created_at": "2026-09-23T00:00:00Z",
                            "url": "https://example.test/comments/20",
                            "type": "full",
                            "command": hosted.FULL_COMMAND,
                        },
                    }
                ),
                encoding="utf-8",
            )
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
                patch.object(hosted, "trigger_record_paths", return_value=[malformed, valid]),
                patch.object(github, "fetch_pull_request", return_value={}),
                patch.object(hosted, "retire_trigger_record", return_value={"status": "retired"}) as retire,
            ):
                result, exit_status = review_cli._dispatch(args)

            self.assertEqual(exit_status, 0)
            self.assertEqual(result["status"], "retired")
            self.assertEqual(retire.call_args.args[0], valid)

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
            "headRepository": {"nameWithOwner": "owner/repo"},
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
            self.assertEqual(snapshot.head_repository, "owner/repo")
            self.assertEqual(live.pull_request_files(42), ["a.txt", "b.txt"])

    def test_live_github_derives_head_repository_identity_when_name_with_owner_is_absent(self) -> None:
        metadata = {
            "number": 42,
            "state": "OPEN",
            "baseRefName": "develop",
            "baseRefOid": BASE,
            "headRefName": "feature",
            "headRefOid": HEAD,
            "headRepository": {"name": "repo"},
            "headRepositoryOwner": {"login": "owner"},
            "changedFiles": 0,
            "mergeable": "MERGEABLE",
            "mergedAt": None,
        }
        with patch.object(github, "fetch_pr_metadata", return_value=metadata):
            self.assertEqual(LiveGitHub("owner/repo").pull_request(42).head_repository, "owner/repo")

    def test_live_github_rejects_malformed_present_head_repository_identity(self) -> None:
        metadata = {
            "number": 42,
            "state": "OPEN",
            "baseRefName": "develop",
            "baseRefOid": BASE,
            "headRefName": "feature",
            "headRefOid": HEAD,
            "headRepository": {"nameWithOwner": "repo", "name": "repo"},
            "headRepositoryOwner": {"login": "owner"},
            "changedFiles": 0,
            "mergeable": "MERGEABLE",
            "mergedAt": None,
        }
        with (
            patch.object(github, "fetch_pr_metadata", return_value=metadata),
            self.assertRaisesRegex(ReviewRunnerError, "head repository identity is malformed"),
        ):
            LiveGitHub("owner/repo").pull_request(42)

    def test_live_github_rejects_missing_head_repository_owner_for_fallback(self) -> None:
        metadata = {
            "number": 42,
            "state": "OPEN",
            "baseRefName": "develop",
            "baseRefOid": BASE,
            "headRefName": "feature",
            "headRefOid": HEAD,
            "headRepository": {"name": "repo"},
            "changedFiles": 0,
            "mergeable": "MERGEABLE",
            "mergedAt": None,
        }
        with (
            patch.object(github, "fetch_pr_metadata", return_value=metadata),
            self.assertRaisesRegex(ReviewRunnerError, "head repository identity is malformed"),
        ):
            LiveGitHub("owner/repo").pull_request(42)

    def test_fetch_pr_metadata_requests_head_repository_identity(self) -> None:
        metadata = {"number": 42, "headRepository": {"nameWithOwner": "owner/repo"}}
        with patch(
            "pr_review.github.subprocess.run",
            return_value=CompletedProcess(["gh"], 0, json.dumps(metadata), ""),
        ) as run:
            value = github.fetch_pr_metadata("owner/repo", 42)

        self.assertEqual(value["headRepository"]["nameWithOwner"], "owner/repo")
        self.assertIn("headRepository", run.call_args.args[0][-1])

    def test_historical_cli_capture_remains_attributable(self) -> None:
        body = f"CLI: 1 found / 0 accepted · `{HEAD[:12]}` · 1 files\n<!-- firemud-cli-run: run.Legacy -->"
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
            completed = [item for item in history if item.get("completed") is True]
            self.assertEqual(len(completed), 1)
            self.assertTrue(completed[0]["attributable"])
            self.assertFalse(completed[0]["anchored"])
            self.assertEqual(completed[0]["accepted"], 0)
            self.assertIsNone(completed[0]["routed"])
            self.assertIsNone(_review_activity(history, HEAD)["recent"][0]["routed"])

            payload["data"]["repository"]["pullRequest"]["comments"]["nodes"][0]["body"] = (
                f"CLI: 1 found / 0 accepted / 1 routed · `{HEAD[:12]}` · 1 files\n<!-- firemud-cli-run: run.Legacy -->"
            )
            (run / "decisions.tsv").write_text("1\trouted\tbelongs to another PR\n", encoding="utf-8")
            with (
                patch.object(github, "fetch_pull_request", return_value=payload),
                patch.object(live, "pull_request", return_value=snapshot),
                patch.object(evidence, "git_common_dir", return_value=common),
            ):
                routed_history = LiveEvidence("owner/repo", live).history(42, "cli")
            self.assertEqual(_review_activity(routed_history, HEAD)["recent"][0]["routed"], 1)

    def test_hosted_request_persists_exact_anchor_and_verified_comment(self) -> None:
        snapshot = PullRequestSnapshot(42, "OPEN", "develop", BASE, HEAD, "feature", 100)
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
        post_timeout: list[float] = []
        post_saw_started_attempt: list[bool] = []
        event_order: list[str] = []

        start_attempt = sqlite_hosted_capture.start_hosted_attempt
        write_trigger_record = hosted.atomic_write_json

        def start_with_observation(*args, **kwargs):
            result = start_attempt(*args, **kwargs)
            event_order.append("sqlite-start")
            return result

        def write_with_observation(write_path, payload):
            if payload.get("status") == "posting":
                event_order.append("reservation")
            return write_trigger_record(write_path, payload)

        def gh_call(args, **kwargs):
            if args == ["gh", "api", "user"]:
                output = {"login": "maintainer"}
            else:
                event_order.append("post")
                post_timeout.append(kwargs["timeout"])
                expected = [
                    "gh",
                    "api",
                    "repos/owner/repo/issues/42/comments",
                    "--method",
                    "POST",
                    "-f",
                    f"body={hosted.FULL_COMMAND}",
                ]
                if args != expected:
                    raise AssertionError(f"unexpected GitHub command: {args!r}")
                post_saw_started_attempt.append(
                    any(
                        item["channel"] == "hosted" and item["state"] == "started"
                        for item in records.attempt_history(42)
                    )
                )
                output = comment
            return CompletedProcess(args, 0, json.dumps(output), "")

        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "trigger.json"
            records = self._new_review_records(Path(directory) / "controller.sqlite3")
            with (
                patch.object(live, "pull_request", return_value=snapshot),
                patch.object(live, "branch_head", return_value=BASE),
                patch.object(github, "fetch_pull_request", return_value=self._payload()),
                patch.object(hosted, "default_trigger_record_path", return_value=path),
                patch.object(evidence, "git_common_dir", return_value=Path(directory)),
                patch.object(
                    sqlite_hosted_capture,
                    "start_hosted_attempt",
                    side_effect=start_with_observation,
                ),
                patch.object(hosted, "atomic_write_json", side_effect=write_with_observation),
                patch(
                    "pr_review.runtime.subprocess.run",
                    side_effect=gh_call,
                ),
            ):
                result = HostedRunner("owner/repo", live, records=records)(target, expect_pr=42)
            record = json.loads(path.read_text(encoding="utf-8"))
            self.assertEqual(result["trigger_comment_id"], 123)
            self.assertEqual(record["anchor"]["child_head"], HEAD)
            self.assertEqual(record["anchor"]["parent_head"], BASE)
            self.assertEqual(record["anchor"]["patch_id"], PATCH)
            self.assertEqual(record["posting_comment_id_floor"], 0)
            self.assertEqual(len(record["sqlite_attempt_id"]), 32)
            self.assertEqual(records.attempt_history(42)[0]["state"], "started")
            self.assertEqual(post_saw_started_attempt, [True])
            self.assertEqual(event_order, ["sqlite-start", "reservation", "post"])
            self.assertEqual(post_timeout, [github.GH_API_TIMEOUT_SECONDS])

    def test_hosted_attempt_start_failure_does_not_block_the_single_post(self) -> None:
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
            "id": 124,
            "created_at": "2026-09-23T00:01:00Z",
            "html_url": "https://example.test/124",
            "body": hosted.FULL_COMMAND,
            "user": {"login": "maintainer"},
        }
        post_calls: list[list[str]] = []

        def gh_call(args, **kwargs):
            if args == ["gh", "api", "user"]:
                output = {"login": "maintainer"}
            else:
                post_calls.append(args)
                output = comment
            return CompletedProcess(args, 0, json.dumps(output), "")

        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "trigger.json"
            records = self._new_review_records(Path(directory) / "controller.sqlite3")
            with (
                patch.object(live, "pull_request", return_value=snapshot),
                patch.object(live, "branch_head", return_value=BASE),
                patch.object(github, "fetch_pull_request", return_value=self._payload()),
                patch.object(hosted, "default_trigger_record_path", return_value=path),
                patch.object(evidence, "git_common_dir", return_value=Path(directory)),
                patch(
                    "pr_review.runtime.sqlite_hosted_capture.start_hosted_attempt",
                    side_effect=RuntimeError("injected archive failure"),
                ),
                patch("pr_review.runtime.subprocess.run", side_effect=gh_call),
            ):
                result = HostedRunner("owner/repo", live, records=records)(target, expect_pr=42)

            self.assertEqual(result["status"], "posted")
            self.assertEqual(len(post_calls), 1)
            record = json.loads(path.read_text(encoding="utf-8"))
            self.assertEqual(record["status"], "posted")
            self.assertEqual(len(record["sqlite_attempt_id"]), 32)
            self.assertTrue(result["sqlite_capture_warnings"])
            self.assertEqual(records.attempt_history(42), [])

    def test_hosted_reservation_write_failure_marks_attempt_failed_and_never_posts(self) -> None:
        snapshot = PullRequestSnapshot(42, "OPEN", "develop", BASE, HEAD, "feature", 1)
        target = ReviewTarget(
            snapshot,
            EffectiveParent("develop", BASE),
            patch_identity=PATCH,
            merge_base=BASE,
            repository="owner/repo",
        )
        live = LiveGitHub("owner/repo")
        records: sqlite_review_records.SqliteReviewRecords
        post_calls: list[list[str]] = []

        def gh_call(args, **kwargs):
            if args != ["gh", "api", "user"]:
                post_calls.append(args)
            return CompletedProcess(args, 0, json.dumps({"login": "maintainer"}), "")

        def fail_reservation(_path, payload):
            if payload.get("status") == "posting":
                raise OSError("injected reservation write failure")

        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "trigger.json"
            records = self._new_review_records(Path(directory) / "controller.sqlite3")
            with (
                patch.object(live, "pull_request", return_value=snapshot),
                patch.object(live, "branch_head", return_value=BASE),
                patch.object(github, "fetch_pull_request", return_value=self._payload()),
                patch.object(hosted, "default_trigger_record_path", return_value=path),
                patch.object(evidence, "git_common_dir", return_value=Path(directory)),
                patch.object(hosted, "atomic_write_json", side_effect=fail_reservation),
                patch("pr_review.runtime.subprocess.run", side_effect=gh_call),
                self.assertRaisesRegex(ControllerError, "durable Hosted posting reservation"),
            ):
                HostedRunner("owner/repo", live, records=records)(target, expect_pr=42)

            self.assertEqual(post_calls, [])
            self.assertFalse(path.exists())
            attempts = records.attempt_history(42)
            self.assertEqual(len(attempts), 1)
            self.assertEqual(attempts[0]["state"], "failed")
            self.assertIsNone(attempts[0]["run_id"])

    def test_next_hosted_request_captures_prior_terminal_attempt_before_archiving(self) -> None:
        snapshot = PullRequestSnapshot(42, "OPEN", "develop", BASE, HEAD, "feature", 1)
        target = ReviewTarget(
            snapshot,
            EffectiveParent("develop", BASE),
            patch_identity=PATCH,
            merge_base=BASE,
            repository="owner/repo",
        )
        live = LiveGitHub("owner/repo")
        prior_record = self._trigger_record(created="2026-09-23T00:00:00Z")
        prior_record["sqlite_attempt_id"] = "prior-hosted-attempt"
        payload = self._payload(
            comments=[],
            reviews=[
                {
                    "databaseId": 901,
                    "author": {"login": "coderabbitai[bot]"},
                    "body": f"<!-- walkthrough_start -->\nReviewed {HEAD}",
                    "state": "COMMENTED",
                    "submittedAt": "2026-09-23T00:02:00Z",
                    "commit": {"oid": HEAD},
                }
            ],
        )
        post_comment = {
            "id": 999,
            "created_at": "2026-09-29T01:00:00Z",
            "html_url": "https://example.test/999",
            "body": hosted.FULL_COMMAND,
            "user": {"login": "maintainer"},
        }
        post_saw_prior_completed: list[bool] = []

        with tempfile.TemporaryDirectory() as directory:
            common = Path(directory)
            path = hosted.default_trigger_record_path("owner/repo", 42, common)
            self._bind_trigger(common, payload, prior_record)
            records = self._new_review_records(common / "controller.sqlite3")
            sqlite_hosted_capture.start_hosted_attempt(
                records,
                attempt_id="prior-hosted-attempt",
                source_pr=42,
                candidate_sha=HEAD,
                started_at="2026-09-23T00:00:00Z",
                metadata={"repository": "owner/repo"},
            )

            def gh_call(args, **kwargs):
                if args == ["gh", "api", "user"]:
                    output = {"login": "maintainer"}
                else:
                    prior = records.attempt_history(42)[0]
                    post_saw_prior_completed.append(prior["state"] == "completed")
                    output = post_comment
                return CompletedProcess(args, 0, json.dumps(output), "")

            with (
                patch.object(live, "pull_request", return_value=snapshot),
                patch.object(live, "branch_head", return_value=BASE),
                patch.object(github, "fetch_pull_request", return_value=payload),
                patch.object(hosted, "default_trigger_record_path", return_value=path),
                patch.object(evidence, "git_common_dir", return_value=common),
                patch("pr_review.runtime.subprocess.run", side_effect=gh_call),
            ):
                result = HostedRunner("owner/repo", live, records=records)(target, expect_pr=42)

            self.assertEqual(result["status"], "posted")
            self.assertEqual(post_saw_prior_completed, [True])
            prior = records.attempt_history(42)[0]
            self.assertEqual(prior["state"], "completed")
            self.assertEqual(prior["run_id"], "prior-hosted-attempt")
            self.assertTrue(records.history(42)["runs"][0]["finalized"])
            self.assertTrue(path.with_name("trigger-10.json").exists())

    def test_status_history_does_not_mutate_hosted_attempt_records(self) -> None:
        scenarios = (
            (
                "rate-limited",
                [
                    {
                        "databaseId": 11,
                        "author": {"login": "coderabbitai[bot]"},
                        "body": "Review rate limited. Next reviews available in: 20 minutes.",
                        "createdAt": "2026-09-23T00:01:00Z",
                        "updatedAt": "2026-09-23T00:01:00Z",
                    }
                ],
                "rate_limited",
            ),
            (
                "ambiguous",
                [
                    {
                        "databaseId": 12,
                        "author": {"login": "coderabbitai[bot]"},
                        "body": "Full review finished.",
                        "createdAt": "2026-09-23T00:01:00Z",
                        "updatedAt": "2026-09-23T00:01:00Z",
                    },
                    {
                        "databaseId": 13,
                        "author": {"login": "coderabbitai[bot]"},
                        "body": "Additional provider output without a head-bound result.",
                        "createdAt": "2026-09-23T00:02:00Z",
                        "updatedAt": "2026-09-23T00:02:00Z",
                    },
                ],
                "ambiguous",
            ),
        )

        for suffix, responses, expected_state in scenarios:
            with self.subTest(state=expected_state), tempfile.TemporaryDirectory() as directory:
                common = Path(directory)
                records = self._new_review_records(common / "controller.sqlite3")
                attempt_id = f"hosted-{suffix}"
                trigger_record = self._trigger_record(created="2026-09-23T00:00:00Z")
                trigger_record["sqlite_attempt_id"] = attempt_id
                sqlite_hosted_capture.start_hosted_attempt(
                    records,
                    attempt_id=attempt_id,
                    source_pr=42,
                    candidate_sha=HEAD,
                    started_at="2026-09-23T00:00:00Z",
                )
                comments = [
                    *responses,
                ]
                payload = self._payload(comments=comments)
                payload["data"]["repository"]["pullRequest"]["changedFiles"] = 1
                self._bind_trigger(common, payload, trigger_record)
                live = LiveGitHub("owner/repo")
                snapshot = PullRequestSnapshot(42, "OPEN", "develop", BASE, HEAD, "feature", 1)
                with (
                    patch.object(github, "fetch_pull_request", return_value=payload),
                    patch.object(live, "pull_request", return_value=snapshot),
                    patch.object(evidence, "git_common_dir", return_value=common),
                ):
                    history = list(LiveEvidence("owner/repo", live).history(42, "hosted"))

                attempt = records.attempt_history(42)[0]
                self.assertEqual(attempt["state"], "started")
                self.assertIsNone(attempt["run_id"])
                self.assertEqual(records.history(42)["runs"], [])
                self.assertIsInstance(history, list)

    @staticmethod
    def _new_review_records(path: Path) -> sqlite_review_records.SqliteReviewRecords:
        sqlite_store.SqliteStateStore(path).update(lambda state: state)
        records = sqlite_review_records.SqliteReviewRecords(path)
        records.bootstrap()
        return records

    def test_hosted_request_refuses_latest_untracked_manual_command_before_reserving(self) -> None:
        snapshot = PullRequestSnapshot(42, "OPEN", "develop", BASE, HEAD, "feature", 1)
        target = ReviewTarget(
            snapshot,
            EffectiveParent("develop", BASE),
            patch_identity=PATCH,
            merge_base=BASE,
            repository="owner/repo",
        )
        live = LiveGitHub("owner/repo")
        manual = {
            "databaseId": 321,
            "author": {"login": "maintainer"},
            "body": hosted.FULL_COMMAND,
            "createdAt": "2026-09-24T00:00:00Z",
        }
        payload = self._payload([manual])
        calls = []

        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "trigger.json"
            with (
                patch.object(live, "pull_request", return_value=snapshot),
                patch.object(live, "branch_head", return_value=BASE),
                patch.object(github, "fetch_pull_request", return_value=payload),
                patch.object(hosted, "default_trigger_record_path", return_value=path),
                patch.object(evidence, "git_common_dir", return_value=Path(directory)),
                patch("pr_review.runtime.subprocess.run", side_effect=lambda *args, **kwargs: calls.append(args)),
                self.assertRaisesRegex(ControllerError, "latest public full-review trigger is not tracked privately"),
            ):
                HostedRunner("owner/repo", live)(target, expect_pr=42)

            self.assertFalse(path.exists())
            self.assertEqual(calls, [])

    def test_hosted_request_rechecks_target_after_repository_sweep(self) -> None:
        snapshot = PullRequestSnapshot(42, "OPEN", "develop", BASE, HEAD, "feature", 1)
        target = ReviewTarget(
            snapshot,
            EffectiveParent("develop", BASE),
            patch_identity=PATCH,
            merge_base=BASE,
            repository="owner/repo",
        )
        manual = {
            "databaseId": 321,
            "author": {"login": "maintainer"},
            "body": hosted.FULL_COMMAND,
            "createdAt": "2026-09-24T00:00:00Z",
        }
        sweep_finished = False
        calls = []

        def repository_sweep(*_args):
            nonlocal sweep_finished
            sweep_finished = True

        def gh_call(args, **_kwargs):
            calls.append(args)
            if args == ["gh", "api", "user"]:
                return CompletedProcess(args, 0, json.dumps({"login": "maintainer"}), "")
            raise AssertionError("manual trigger must prevent POST")

        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "trigger.json"
            with (
                patch.object(LiveGitHub, "pull_request", return_value=snapshot),
                patch.object(LiveGitHub, "branch_head", return_value=BASE),
                patch.object(
                    github,
                    "fetch_pull_request",
                    side_effect=lambda *_: self._payload([manual] if sweep_finished else []),
                ),
                patch.object(HostedRunner, "_assert_no_other_active_reservations", side_effect=repository_sweep),
                patch.object(hosted, "default_trigger_record_path", return_value=path),
                patch.object(evidence, "git_common_dir", return_value=Path(directory)),
                patch("pr_review.runtime.subprocess.run", side_effect=gh_call),
                self.assertRaisesRegex(ControllerError, "latest public full-review trigger is not tracked privately"),
            ):
                HostedRunner("owner/repo", LiveGitHub("owner/repo"))(target, expect_pr=42)
            self.assertTrue(sweep_finished)
            self.assertFalse(path.exists())
            self.assertEqual(calls, [["gh", "api", "user"]])

    def test_hosted_request_allows_tracked_command_and_other_terminal_ambiguous_reply(self) -> None:
        snapshot = PullRequestSnapshot(42, "OPEN", "develop", BASE, HEAD, "feature", 1)
        target = ReviewTarget(
            snapshot,
            EffectiveParent("develop", BASE),
            patch_identity=PATCH,
            merge_base=BASE,
            repository="owner/repo",
        )
        live = LiveGitHub("owner/repo")
        command = {
            "databaseId": 201,
            "author": {"login": "maintainer"},
            "body": hosted.FULL_COMMAND,
            "createdAt": "2026-09-24T00:00:00Z",
            "url": "https://example.test/comments/201",
        }
        other_command = {
            "databaseId": 456,
            "author": {"login": "maintainer"},
            "body": hosted.FULL_COMMAND,
            "createdAt": "2026-09-23T00:00:00Z",
            "url": "https://example.test/comments/456",
        }
        record = self._trigger_record(status="retired")
        record["trigger"].update(
            {
                "id": command["databaseId"],
                "created_at": command["createdAt"],
                "url": command["url"],
                "author_login": "maintainer",
            }
        )
        other_record = self._trigger_record(status="posted")
        other_record.update({"pr_number": 43})
        other_record["anchor"]["pr"] = 43
        other_record["trigger"].update(
            {
                "id": other_command["databaseId"],
                "created_at": other_command["createdAt"],
                "url": other_command["url"],
                "author_login": "maintainer",
            }
        )
        other_ambiguous_response = {
            "databaseId": 457,
            "author": {"login": "coderabbitai[bot]"},
            "body": "Full review finished.",
            "createdAt": "2026-09-23T00:01:00Z",
        }
        other_unclassified_output = {
            "databaseId": 458,
            "author": {"login": "coderabbitai[bot]"},
            "body": "Additional review detail is unavailable.",
            "createdAt": "2026-09-23T00:00:30Z",
        }
        payload42 = self._payload([command])
        payload43 = self._payload([other_command, other_unclassified_output, other_ambiguous_response])
        payload43["data"]["repository"]["pullRequest"]["number"] = 43
        post_comment = {
            "id": 123,
            "created_at": "2026-09-24T00:01:00Z",
            "html_url": "https://example.test/123",
            "body": hosted.FULL_COMMAND,
            "user": {"login": "maintainer"},
        }

        def gh_call(args, **kwargs):
            result = {"login": "maintainer"} if args == ["gh", "api", "user"] else post_comment
            return CompletedProcess(args, 0, json.dumps(result), "")

        with tempfile.TemporaryDirectory() as directory:
            common = Path(directory)
            target_dir = common / "firemud" / "hosted" / "owner_repo" / "pr-42"
            target_dir.mkdir(parents=True)
            path = target_dir / "trigger.json"
            archived = target_dir / "trigger-201.json"
            archived.write_text(json.dumps(record), encoding="utf-8")
            other_dir = common / "firemud" / "hosted" / "owner_repo" / "pr-43"
            other_dir.mkdir(parents=True)
            (other_dir / "trigger.json").write_text(json.dumps(other_record), encoding="utf-8")
            with (
                patch.object(live, "pull_request", return_value=snapshot),
                patch.object(live, "branch_head", return_value=BASE),
                patch.object(
                    github,
                    "fetch_pull_request",
                    side_effect=lambda _repo, pr: {42: payload42, 43: payload43}[pr],
                ),
                patch.object(hosted, "default_trigger_record_path", return_value=path),
                patch.object(evidence, "git_common_dir", return_value=common),
                patch("pr_review.runtime.subprocess.run", side_effect=gh_call),
            ):
                result = HostedRunner("owner/repo", live)(target, expect_pr=42)
            self.assertEqual(result["status"], "posted")
            self.assertEqual(json.loads(path.read_text(encoding="utf-8"))["status"], "posted")

    def test_hosted_request_refuses_another_pr_active_reservation(self) -> None:
        snapshot = PullRequestSnapshot(42, "OPEN", "develop", BASE, HEAD, "feature", 1)
        target = ReviewTarget(
            snapshot,
            EffectiveParent("develop", BASE),
            patch_identity=PATCH,
            merge_base=BASE,
            repository="owner/repo",
        )
        live = LiveGitHub("owner/repo")
        other_command = {
            "databaseId": 456,
            "author": {"login": "maintainer"},
            "body": hosted.FULL_COMMAND,
            "createdAt": "2026-09-23T00:00:00Z",
            "url": "https://example.test/comments/456",
        }
        active_response = {
            "databaseId": 457,
            "author": {"login": "coderabbitai[bot]"},
            "body": "Full review triggered. I am reviewing the pull request now.",
            "createdAt": "2026-09-23T00:01:00Z",
        }
        other_record = self._trigger_record(status="posted")
        other_record.update({"pr_number": 43})
        other_record["anchor"]["pr"] = 43
        other_record["trigger"].update(
            {
                "id": other_command["databaseId"],
                "created_at": other_command["createdAt"],
                "url": other_command["url"],
                "author_login": "maintainer",
            }
        )
        payload42 = self._payload()
        payload43 = self._payload([other_command, active_response])
        payload43["data"]["repository"]["pullRequest"]["number"] = 43
        calls = []

        def gh_call(args, **kwargs):
            calls.append(args)
            if args == ["gh", "api", "user"]:
                return CompletedProcess(args, 0, json.dumps({"login": "maintainer"}), "")
            raise AssertionError("another PR has an active Hosted request")

        with tempfile.TemporaryDirectory() as directory:
            common = Path(directory)
            path = common / "firemud" / "hosted" / "owner_repo" / "pr-42" / "trigger.json"
            other_path = common / "firemud" / "hosted" / "owner_repo" / "pr-43" / "trigger.json"
            other_path.parent.mkdir(parents=True)
            other_path.write_text(json.dumps(other_record), encoding="utf-8")
            with (
                patch.object(live, "pull_request", return_value=snapshot),
                patch.object(live, "branch_head", return_value=BASE),
                patch.object(
                    github,
                    "fetch_pull_request",
                    side_effect=lambda _repo, pr: {42: payload42, 43: payload43}[pr],
                ),
                patch.object(hosted, "default_trigger_record_path", return_value=path),
                patch.object(evidence, "git_common_dir", return_value=common),
                patch("pr_review.runtime.subprocess.run", side_effect=gh_call),
                self.assertRaisesRegex(ControllerError, "another Hosted request is unresolved for PR #43: active"),
            ):
                HostedRunner("owner/repo", live)(target, expect_pr=42)

            self.assertFalse(path.exists())
            self.assertEqual(calls, [["gh", "api", "user"]])

    def test_hosted_request_refuses_untracked_manual_request_on_another_open_pr(self) -> None:
        snapshot = PullRequestSnapshot(42, "OPEN", "develop", BASE, HEAD, "feature", 1)
        target = ReviewTarget(
            snapshot,
            EffectiveParent("develop", BASE),
            patch_identity=PATCH,
            merge_base=BASE,
            repository="owner/repo",
        )
        live = LiveGitHub("owner/repo")
        manual = {
            "databaseId": 456,
            "author": {"login": "maintainer"},
            "body": hosted.FULL_COMMAND,
            "createdAt": "2026-09-23T00:00:00Z",
            "url": "https://example.test/comments/456",
        }
        payload42 = self._payload()
        payload43 = self._payload([manual])
        payload43["data"]["repository"]["pullRequest"]["number"] = 43
        calls = []

        def gh_call(args, **kwargs):
            calls.append(args)
            if args == ["gh", "api", "user"]:
                return CompletedProcess(args, 0, json.dumps({"login": "maintainer"}), "")
            raise AssertionError("an unresolved manual Hosted request must prevent POST")

        def api_endpoint(endpoint: str) -> list[dict[str, Any]]:
            if endpoint == "repos/owner/repo/pulls?state=open&per_page=100":
                return [{"number": 43, "state": "open"}]
            if endpoint == "repos/owner/repo/issues/43/comments?per_page=100":
                return [self._rest_issue_comment(manual)]
            raise AssertionError(f"unexpected REST endpoint: {endpoint}")

        with tempfile.TemporaryDirectory() as directory:
            common = Path(directory)
            path = common / "firemud" / "hosted" / "owner_repo" / "pr-42" / "trigger.json"
            with (
                patch.object(live, "pull_request", return_value=snapshot),
                patch.object(live, "branch_head", return_value=BASE),
                patch.object(
                    github,
                    "fetch_pull_request",
                    side_effect=lambda _repo, pr: {42: payload42, 43: payload43}[pr],
                ),
                patch.object(github, "fetch_api_endpoint", side_effect=api_endpoint),
                patch.object(hosted, "unresolved_preceding_full_trigger", return_value=False),
                patch.object(hosted, "default_trigger_record_path", return_value=path),
                patch.object(evidence, "git_common_dir", return_value=common),
                patch("pr_review.runtime.subprocess.run", side_effect=gh_call),
                self.assertRaisesRegex(ControllerError, "another manual Hosted request is unresolved for PR #43"),
            ):
                HostedRunner("owner/repo", live)(target, expect_pr=42)
            self.assertFalse(path.exists())
            self.assertEqual(calls, [["gh", "api", "user"]])

    def test_hosted_request_checks_new_manual_command_after_other_prs_terminal_record(self) -> None:
        snapshot = PullRequestSnapshot(42, "OPEN", "develop", BASE, HEAD, "feature", 1)
        target = ReviewTarget(
            snapshot,
            EffectiveParent("develop", BASE),
            patch_identity=PATCH,
            merge_base=BASE,
            repository="owner/repo",
        )
        live = LiveGitHub("owner/repo")
        old_command = {
            "databaseId": 10,
            "author": {"login": "maintainer"},
            "body": hosted.FULL_COMMAND,
            "createdAt": "2026-09-23T00:00:00Z",
            "url": "https://example.test/comments/10",
        }
        old_finish = {
            "databaseId": 11,
            "author": {"login": "coderabbitai[bot]"},
            "body": "Full review finished.",
            "createdAt": "2026-09-23T00:01:00Z",
            "updatedAt": "2026-09-23T00:01:00Z",
        }
        new_manual = {
            "databaseId": 12,
            "author": {"login": "maintainer"},
            "body": hosted.FULL_COMMAND,
            "createdAt": "2026-09-23T00:02:00Z",
            "url": "https://example.test/comments/12",
        }
        payload42 = self._payload()
        payload43 = self._payload([old_command, old_finish, new_manual])
        payload43["data"]["repository"]["pullRequest"]["number"] = 43
        calls = []

        def gh_call(args, **kwargs):
            calls.append(args)
            if args == ["gh", "api", "user"]:
                return CompletedProcess(args, 0, json.dumps({"login": "maintainer"}), "")
            raise AssertionError("a new manual Hosted request must prevent POST")

        def api_endpoint(endpoint: str) -> list[dict[str, Any]]:
            if endpoint == "repos/owner/repo/pulls?state=open&per_page=100":
                return [{"number": 43, "state": "open"}]
            if endpoint == "repos/owner/repo/issues/43/comments?per_page=100":
                return [self._rest_issue_comment(comment) for comment in (old_command, old_finish, new_manual)]
            raise AssertionError(f"unexpected REST endpoint: {endpoint}")

        with tempfile.TemporaryDirectory() as directory:
            common = Path(directory)
            path = common / "firemud" / "hosted" / "owner_repo" / "pr-42" / "trigger.json"
            other = common / "firemud" / "hosted" / "owner_repo" / "pr-43" / "trigger.json"
            other.parent.mkdir(parents=True)
            record = self._trigger_record()
            record["pr_number"] = 43
            record["anchor"]["pr"] = 43
            other.write_text(json.dumps(record), encoding="utf-8")
            store = StateStore(common / "state.json")
            store.save(ReviewState(ordered_prs=(42, 43)))
            with (
                patch.object(live, "pull_request", return_value=snapshot),
                patch.object(live, "branch_head", return_value=BASE),
                patch.object(
                    github, "fetch_pull_request", side_effect=lambda _repo, pr: {42: payload42, 43: payload43}[pr]
                ),
                patch.object(github, "fetch_api_endpoint", side_effect=api_endpoint),
                patch.object(hosted, "unresolved_preceding_full_trigger", return_value=False),
                patch.object(hosted, "default_trigger_record_path", return_value=path),
                patch.object(evidence, "git_common_dir", return_value=common),
                patch("pr_review.runtime.subprocess.run", side_effect=gh_call),
                self.assertRaisesRegex(ControllerError, "another manual Hosted request is unresolved for PR #43"),
            ):
                HostedRunner("owner/repo", live, store)(target, expect_pr=42)
            self.assertFalse(path.exists())
            self.assertEqual(calls, [["gh", "api", "user"]])

    def test_hosted_global_scan_skips_full_history_for_quiet_open_pr(self) -> None:
        def api_endpoint(endpoint: str) -> list[dict[str, Any]]:
            if endpoint == "repos/owner/repo/pulls?state=open&per_page=100":
                return [{"number": 42, "state": "open"}, {"number": 99, "state": "open"}]
            if endpoint == "repos/owner/repo/issues/99/comments?per_page=100":
                return []
            raise AssertionError(f"unexpected REST endpoint: {endpoint}")

        live = LiveGitHub("owner/repo")
        runner = HostedRunner("owner/repo", live)
        with (
            tempfile.TemporaryDirectory() as directory,
            patch.object(github, "fetch_api_endpoint", side_effect=api_endpoint),
            patch.object(
                github,
                "fetch_pull_request",
                side_effect=AssertionError("quiet PRs must not load review threads"),
            ) as fetch_full,
        ):
            runner._assert_no_other_active_reservations(42, Path(directory))
        fetch_full.assert_not_called()

    def test_hosted_global_scan_blocks_off_queue_active_manual_request(self) -> None:
        manual = {
            "databaseId": 901,
            "author": {"login": "maintainer"},
            "body": hosted.FULL_COMMAND,
            "createdAt": "2026-09-23T00:00:00Z",
            "url": "https://example.test/comments/901",
        }
        payload = self._payload([manual])
        payload["data"]["repository"]["pullRequest"]["number"] = 99

        def api_endpoint(endpoint: str) -> list[dict[str, Any]]:
            if endpoint == "repos/owner/repo/pulls?state=open&per_page=100":
                return [{"number": 42, "state": "open"}, {"number": 99, "state": "open"}]
            if endpoint == "repos/owner/repo/issues/99/comments?per_page=100":
                return [self._rest_issue_comment(manual)]
            raise AssertionError(f"unexpected REST endpoint: {endpoint}")

        with (
            tempfile.TemporaryDirectory() as directory,
            patch.object(github, "fetch_api_endpoint", side_effect=api_endpoint),
            patch.object(github, "fetch_pull_request", return_value=payload) as fetch_full,
            patch.object(hosted, "unresolved_preceding_full_trigger", return_value=False) as preceding,
            self.assertRaisesRegex(ControllerError, "another manual Hosted request is unresolved for PR #99"),
        ):
            HostedRunner("owner/repo", LiveGitHub("owner/repo"))._assert_no_other_active_reservations(
                42, Path(directory)
            )
        fetch_full.assert_called_once_with("owner/repo", 99)
        preceding.assert_called_once_with("owner/repo", 99, payload, 901, Path(directory))

    def test_hosted_global_scan_releases_slot_after_off_queue_manual_adoption(self) -> None:
        manual = {
            "databaseId": 905,
            "author": {"login": "maintainer"},
            "body": hosted.FULL_COMMAND,
            "createdAt": "2026-09-28T10:08:38Z",
            "url": "https://example.test/comments/905",
        }
        completed_review = {
            "databaseId": 906,
            "author": {"login": "coderabbitai[bot]"},
            "body": "<!-- walkthrough_start -->\n**Actionable comments posted:** 1",
            "state": "COMMENTED",
            "submittedAt": "2026-09-28T10:19:19Z",
            "commit": {"oid": HEAD},
        }
        payload = self._payload([manual], [completed_review])
        payload["data"]["repository"]["pullRequest"]["number"] = 99
        anchor = {
            "pr": 99,
            "child_head": HEAD,
            "parent_identity": "develop",
            "parent_head": BASE,
            "merge_base": BASE,
            "patch_id": PATCH,
        }

        def api_endpoint(endpoint: str) -> list[dict[str, Any]]:
            if endpoint == "repos/owner/repo/pulls?state=open&per_page=100":
                return [{"number": 42, "state": "open"}, {"number": 99, "state": "open"}]
            if endpoint == "repos/owner/repo/issues/99/comments?per_page=100":
                return [self._rest_issue_comment(manual)]
            raise AssertionError(f"unexpected REST endpoint: {endpoint}")

        with tempfile.TemporaryDirectory() as directory:
            common = Path(directory)
            record_path = hosted.default_trigger_record_path("owner/repo", 99, common)
            adopted = hosted.adopt_manual_completed_trigger(
                "owner/repo",
                99,
                manual["databaseId"],
                HEAD,
                anchor,
                lambda: payload,
                path=record_path,
                common=common,
            )
            self.assertEqual(adopted["status"], "adopted")

            with (
                patch.object(github, "fetch_api_endpoint", side_effect=api_endpoint),
                patch.object(github, "fetch_pull_request", return_value=payload) as fetch_full,
            ):
                HostedRunner("owner/repo", LiveGitHub("owner/repo"))._assert_no_other_active_reservations(42, common)

            self.assertTrue(record_path.exists())
            fetch_full.assert_called_once_with("owner/repo", 99)

    def test_hosted_global_scan_blocks_new_manual_request_after_tracked_current_record(self) -> None:
        old_command = {
            "databaseId": 910,
            "author": {"login": "maintainer"},
            "body": hosted.FULL_COMMAND,
            "createdAt": "2026-09-23T00:00:00Z",
            "url": "https://example.test/comments/910",
        }
        new_command = {
            "databaseId": 912,
            "author": {"login": "maintainer"},
            "body": hosted.FULL_COMMAND,
            "createdAt": "2026-09-23T00:02:00Z",
            "url": "https://example.test/comments/912",
        }
        completed_review = {
            "databaseId": 911,
            "author": {"login": "coderabbitai[bot]"},
            "body": f"<!-- walkthrough_start -->\nActionable comments posted: 1\nReviewed {HEAD}",
            "state": "COMMENTED",
            "submittedAt": "2026-09-23T00:01:00Z",
            "commit": {"oid": HEAD},
        }
        payload = self._payload([old_command, new_command], [completed_review])
        payload["data"]["repository"]["pullRequest"]["number"] = 99

        def api_endpoint(endpoint: str) -> list[dict[str, Any]]:
            if endpoint == "repos/owner/repo/pulls?state=open&per_page=100":
                return [{"number": 42, "state": "open"}, {"number": 99, "state": "open"}]
            if endpoint == "repos/owner/repo/issues/99/comments?per_page=100":
                return [self._rest_issue_comment(old_command), self._rest_issue_comment(new_command)]
            raise AssertionError(f"unexpected REST endpoint: {endpoint}")

        record = self._trigger_record(created=old_command["createdAt"])
        record.update({"pr_number": 99})
        record["anchor"]["pr"] = 99
        record["trigger"].update(
            {
                "id": old_command["databaseId"],
                "url": old_command["url"],
                "author_login": "maintainer",
            }
        )
        with tempfile.TemporaryDirectory() as directory:
            common = Path(directory)
            record_path = common / "firemud" / "hosted" / "owner_repo" / "pr-99" / "trigger.json"
            record_path.parent.mkdir(parents=True)
            record_path.write_text(json.dumps(record), encoding="utf-8")
            with (
                patch.object(github, "fetch_api_endpoint", side_effect=api_endpoint),
                patch.object(github, "fetch_pull_request", return_value=payload) as fetch_full,
                patch.object(hosted, "unresolved_preceding_full_trigger", return_value=False) as preceding,
                self.assertRaisesRegex(ControllerError, "another manual Hosted request is unresolved for PR #99"),
            ):
                HostedRunner("owner/repo", LiveGitHub("owner/repo"))._assert_no_other_active_reservations(42, common)
        fetch_full.assert_called_once_with("owner/repo", 99)
        preceding.assert_called_once_with("owner/repo", 99, payload, 912, common)

    def test_hosted_global_scan_uses_reviewed_head_for_historical_manual_request(self) -> None:
        manual = {
            "databaseId": 920,
            "author": {"login": "maintainer"},
            "body": hosted.FULL_COMMAND,
            "createdAt": "2026-09-23T00:00:00Z",
            "url": "https://example.test/comments/920",
        }
        completed_review = {
            "databaseId": 921,
            "author": {"login": "coderabbitai[bot]"},
            "body": f"<!-- walkthrough_start -->\nActionable comments posted: 1\nReviewed {HEAD}",
            "state": "COMMENTED",
            "submittedAt": "2026-09-23T00:01:00Z",
            "commit": {"oid": HEAD},
        }
        payload = self._payload([manual], [completed_review], head="d" * 40)
        payload["data"]["repository"]["pullRequest"]["number"] = 99

        def api_endpoint(endpoint: str) -> list[dict[str, Any]]:
            if endpoint == "repos/owner/repo/pulls?state=open&per_page=100":
                return [{"number": 42, "state": "open"}, {"number": 99, "state": "open"}]
            if endpoint == "repos/owner/repo/issues/99/comments?per_page=100":
                return [self._rest_issue_comment(manual)]
            raise AssertionError(f"unexpected REST endpoint: {endpoint}")

        with (
            tempfile.TemporaryDirectory() as directory,
            patch.object(github, "fetch_api_endpoint", side_effect=api_endpoint),
            patch.object(github, "fetch_pull_request", return_value=payload),
            patch.object(hosted, "unresolved_preceding_full_trigger", return_value=False) as preceding,
        ):
            HostedRunner("owner/repo", LiveGitHub("owner/repo"))._assert_no_other_active_reservations(
                42, Path(directory)
            )
        preceding.assert_called_once_with("owner/repo", 99, payload, 920, Path(directory))

    def test_hosted_global_scan_keeps_active_manual_request_unresolved_without_head_proof(self) -> None:
        manual = {
            "databaseId": 922,
            "author": {"login": "maintainer"},
            "body": hosted.FULL_COMMAND,
            "createdAt": "2026-09-23T00:00:00Z",
            "url": "https://example.test/comments/922",
        }
        active_reply = {
            "databaseId": 923,
            "author": {"login": "coderabbitai[bot]"},
            "body": "Full review triggered. I am reviewing the pull request now.",
            "createdAt": "2026-09-23T00:01:00Z",
            "updatedAt": "2026-09-23T00:01:00Z",
        }
        payload = self._payload([manual, active_reply], head="d" * 40)
        payload["data"]["repository"]["pullRequest"]["number"] = 99

        def api_endpoint(endpoint: str) -> list[dict[str, Any]]:
            if endpoint == "repos/owner/repo/pulls?state=open&per_page=100":
                return [{"number": 42, "state": "open"}, {"number": 99, "state": "open"}]
            if endpoint == "repos/owner/repo/issues/99/comments?per_page=100":
                return [self._rest_issue_comment(manual)]
            raise AssertionError(f"unexpected REST endpoint: {endpoint}")

        with (
            tempfile.TemporaryDirectory() as directory,
            patch.object(github, "fetch_api_endpoint", side_effect=api_endpoint),
            patch.object(github, "fetch_pull_request", return_value=payload),
            patch.object(hosted, "unresolved_preceding_full_trigger", return_value=False),
            self.assertRaisesRegex(
                ControllerError,
                r"another manual Hosted request is unresolved for PR #99: its command-time head cannot be verified",
            ),
        ):
            HostedRunner("owner/repo", LiveGitHub("owner/repo"))._assert_no_other_active_reservations(
                42, Path(directory)
            )

    def test_hosted_global_scan_releases_slot_for_headless_terminal_manual_status(self) -> None:
        terminal_responses = (
            "Review rate limited; next reviews available in 30 minutes",
            f"This request {hosted.NOOP_MARKER}",
            "The review failed due to an internal error.",
        )
        for index, response_body in enumerate(terminal_responses):
            with self.subTest(response=response_body):
                manual = {
                    "databaseId": 930 + index * 2,
                    "author": {"login": "maintainer"},
                    "body": hosted.FULL_COMMAND,
                    "createdAt": "2026-09-23T00:00:00Z",
                    "url": f"https://example.test/comments/{930 + index * 2}",
                }
                response = {
                    "databaseId": 931 + index * 2,
                    "author": {"login": "coderabbitai[bot]"},
                    "body": response_body,
                    "createdAt": "2026-09-23T00:01:00Z",
                    "updatedAt": "2026-09-23T00:01:00Z",
                }
                payload = self._payload([manual, response], head="d" * 40)
                payload["data"]["repository"]["pullRequest"]["number"] = 99

                def api_endpoint(endpoint: str, manual: Mapping[str, Any] = manual) -> list[dict[str, Any]]:
                    if endpoint == "repos/owner/repo/pulls?state=open&per_page=100":
                        return [{"number": 42, "state": "open"}, {"number": 99, "state": "open"}]
                    if endpoint == "repos/owner/repo/issues/99/comments?per_page=100":
                        return [self._rest_issue_comment(manual)]
                    raise AssertionError(f"unexpected REST endpoint: {endpoint}")

                with (
                    tempfile.TemporaryDirectory() as directory,
                    patch.object(github, "fetch_api_endpoint", side_effect=api_endpoint),
                    patch.object(github, "fetch_pull_request", return_value=payload),
                ):
                    HostedRunner("owner/repo", LiveGitHub("owner/repo"))._assert_no_other_active_reservations(
                        42, Path(directory)
                    )

    def test_hosted_global_scan_releases_slot_for_headless_finished_reply_only(self) -> None:
        manual = {
            "databaseId": 940,
            "author": {"login": "maintainer"},
            "body": hosted.FULL_COMMAND,
            "createdAt": "2026-09-23T00:00:00Z",
            "url": "https://example.test/comments/940",
        }
        finished_reply = {
            "databaseId": 941,
            "author": {"login": "coderabbitai[bot]"},
            "body": "Full review finished.",
            "createdAt": "2026-09-23T00:01:00Z",
            "updatedAt": "2026-09-23T00:01:00Z",
        }
        payload = self._payload([manual, finished_reply], head="d" * 40)
        payload["data"]["repository"]["pullRequest"]["number"] = 99

        def api_endpoint(endpoint: str) -> list[dict[str, Any]]:
            if endpoint == "repos/owner/repo/pulls?state=open&per_page=100":
                return [{"number": 42, "state": "open"}, {"number": 99, "state": "open"}]
            if endpoint == "repos/owner/repo/issues/99/comments?per_page=100":
                return [self._rest_issue_comment(manual)]
            raise AssertionError(f"unexpected REST endpoint: {endpoint}")

        with (
            tempfile.TemporaryDirectory() as directory,
            patch.object(github, "fetch_api_endpoint", side_effect=api_endpoint),
            patch.object(github, "fetch_pull_request", return_value=payload),
        ):
            HostedRunner("owner/repo", LiveGitHub("owner/repo"))._assert_no_other_active_reservations(
                42, Path(directory)
            )

    def test_headless_terminal_manual_status_keeps_incomplete_coverage_blocked(self) -> None:
        manual = {
            "databaseId": 950,
            "author": {"login": "maintainer"},
            "body": hosted.FULL_COMMAND,
            "createdAt": "2026-09-23T00:00:00Z",
            "url": "https://example.test/comments/950",
        }
        failed_incomplete = {
            "databaseId": 951,
            "author": {"login": "coderabbitai[bot]"},
            "body": "The review failed with incomplete coverage: 4 of 10 files were reviewed.",
            "createdAt": "2026-09-23T00:01:00Z",
            "updatedAt": "2026-09-23T00:01:00Z",
        }
        payload = self._payload([manual, failed_incomplete], head="d" * 40)
        with tempfile.TemporaryDirectory() as directory:
            self.assertFalse(
                HostedRunner._manual_terminal_without_head("owner/repo", 99, payload, manual, Path(directory))
            )

    def test_hosted_repository_admission_lock_serializes_different_pr_posts(self) -> None:
        snapshots = {
            number: PullRequestSnapshot(number, "OPEN", "develop", BASE, HEAD, "feature", 1) for number in (42, 43)
        }
        targets = {
            number: ReviewTarget(
                snapshot,
                EffectiveParent("develop", BASE),
                patch_identity=PATCH,
                merge_base=BASE,
                repository="owner/repo",
            )
            for number, snapshot in snapshots.items()
        }
        lives = {number: LiveGitHub("owner/repo") for number in snapshots}
        payloads = {number: self._payload() for number in snapshots}
        for number, payload in payloads.items():
            payload["data"]["repository"]["pullRequest"]["number"] = number
        comment = {
            "id": 123,
            "created_at": "2026-09-24T00:01:00Z",
            "html_url": "https://example.test/123",
            "body": hosted.FULL_COMMAND,
            "user": {"login": "maintainer"},
        }
        post_started = threading.Event()
        release_post = threading.Event()
        posts = []

        def gh_call(args, **kwargs):
            if args == ["gh", "api", "user"]:
                return CompletedProcess(args, 0, json.dumps({"login": "maintainer"}), "")
            posts.append(args)
            post_started.set()
            if not release_post.wait(timeout=10):
                raise AssertionError("test did not release the first Hosted POST")
            return CompletedProcess(args, 0, json.dumps(comment), "")

        with tempfile.TemporaryDirectory() as directory:
            common = Path(directory)
            paths = {
                number: common / "firemud" / "hosted" / "owner_repo" / f"pr-{number}" / "trigger.json"
                for number in snapshots
            }
            with (
                patch.object(lives[42], "pull_request", return_value=snapshots[42]),
                patch.object(lives[43], "pull_request", return_value=snapshots[43]),
                patch.object(lives[42], "branch_head", return_value=BASE),
                patch.object(lives[43], "branch_head", return_value=BASE),
                patch.object(github, "fetch_pull_request", side_effect=lambda _repo, pr: payloads[pr]),
                patch.object(hosted, "default_trigger_record_path", side_effect=lambda _repo, pr: paths[pr]),
                patch.object(evidence, "git_common_dir", return_value=common),
                patch("pr_review.runtime.subprocess.run", side_effect=gh_call),
                ThreadPoolExecutor(max_workers=2) as pool,
            ):
                first = pool.submit(HostedRunner("owner/repo", lives[42]), targets[42], expect_pr=42)
                try:
                    self.assertTrue(post_started.wait(timeout=5), "first Hosted request did not reach POST")
                    second = pool.submit(HostedRunner("owner/repo", lives[43]), targets[43], expect_pr=43)
                    with self.assertRaisesRegex(
                        ControllerError,
                        "another Hosted request is active for repository owner/repo",
                    ):
                        second.result(timeout=5)
                finally:
                    release_post.set()
                self.assertEqual(first.result(timeout=5)["status"], "posted")

            self.assertEqual(len(posts), 1)
            self.assertTrue(paths[42].exists())
            self.assertFalse(paths[43].exists())

    def test_hosted_stop_at_admission_leaves_no_post_or_started_sqlite_attempt(self) -> None:
        snapshot = PullRequestSnapshot(42, "OPEN", "develop", BASE, HEAD, "feature", 1)
        target = ReviewTarget(
            snapshot, EffectiveParent("develop", BASE), patch_identity=PATCH, merge_base=BASE, repository="owner/repo"
        )
        live = LiveGitHub("owner/repo")
        with tempfile.TemporaryDirectory() as directory:
            common = Path(directory)
            path = common / "firemud" / "hosted" / "owner_repo" / "pr-42" / "trigger.json"
            database = common / "records.sqlite3"
            sqlite_store.SqliteStateStore(database).update(lambda _: ReviewState())
            records = sqlite_review_records.SqliteReviewRecords(database)
            records.bootstrap()
            runner = HostedRunner("owner/repo", live, records=records)

            def stopped(_reserve):
                raise ControllerError("hosted discovery is stopped")

            with (
                patch.object(live, "pull_request", return_value=snapshot),
                patch.object(live, "branch_head", return_value=BASE),
                patch.object(github, "fetch_pull_request", return_value=self._payload()),
                patch.object(hosted, "default_trigger_record_path", return_value=path),
                patch.object(evidence, "git_common_dir", return_value=common),
                patch.object(runner, "_authenticated_login", return_value="maintainer"),
                patch("pr_review.runtime.subprocess.run", side_effect=AssertionError("stopped admission posted")),
                self.assertRaisesRegex(ControllerError, "discovery is stopped"),
            ):
                runner(target, expect_pr=42, admit=stopped)
            self.assertFalse(path.exists())
            self.assertEqual(len(records.attempt_history(42)), 1)
            self.assertEqual(records.attempt_history(42)[0]["state"], "failed")

    def test_cli_activity_scans_all_captures_and_excludes_terminal_files(self) -> None:
        import fcntl

        with tempfile.TemporaryDirectory() as directory:
            common = Path(directory)
            root = common / "firemud" / "pr-review"
            captures = root / "runs"

            def create_capture(run_id: str, metadata: dict[str, Any], modified_ns: int) -> Path:
                capture = captures / run_id
                capture.mkdir(parents=True)
                metadata_path = capture / "metadata.json"
                metadata_path.write_text(json.dumps(metadata), encoding="utf-8")
                os.utime(metadata_path, ns=(modified_ns, modified_ns))
                return capture

            create_capture(
                "run.active",
                {
                    "run_id": "run.active",
                    "pull_request": 42,
                    "candidate_sha": HEAD,
                    "parent_ref": "develop",
                    "parent_sha": BASE,
                    "merge_base": BASE,
                    "patch_identity": PATCH,
                },
                1_000_000_000,
            )
            completed = create_capture(
                "run.completed",
                {"run_id": "run.completed", "pull_request": 42, "candidate_sha": HEAD},
                2_000_000_000,
            )
            create_capture(
                "run.other-pr",
                {"run_id": "run.other-pr", "pull_request": 43, "candidate_sha": HEAD},
                3_000_000_000,
            )
            malformed = captures / "run.malformed"
            malformed.mkdir(parents=True)
            malformed_metadata = malformed / "metadata.json"
            malformed_metadata.write_text("{not-json", encoding="utf-8")
            os.utime(malformed_metadata, ns=(4_000_000_000, 4_000_000_000))
            with (root / "cli.lock").open("a+") as handle:
                fcntl.flock(handle.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
                for terminal_file in ("error", "exit-status", "capture-complete"):
                    with self.subTest(terminal_file=terminal_file):
                        path = completed / terminal_file
                        path.write_text("0")
                        history = self._history(common, self._payload(), "cli")
                        self.assertEqual(
                            [item["checkpoint"] for item in history if item.get("active_review")],
                            ["active-cli:run.active"],
                        )
                        path.unlink()

    def test_hosted_request_rejects_more_than_100_files_before_reserving_or_posting(self) -> None:
        snapshot = PullRequestSnapshot(42, "OPEN", "develop", BASE, HEAD, "feature", 101)
        target = ReviewTarget(
            snapshot,
            EffectiveParent("develop", BASE),
            patch_identity=PATCH,
            merge_base=BASE,
            repository="owner/repo",
        )
        live = LiveGitHub("owner/repo")
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "trigger.json"
            with (
                patch.object(live, "pull_request", return_value=snapshot),
                patch.object(live, "branch_head", return_value=BASE),
                patch.object(hosted, "default_trigger_record_path", return_value=path),
                self.assertRaisesRegex(ControllerError, "at most 100 changed files"),
            ):
                HostedRunner("owner/repo", live)(target, expect_pr=42)
            self.assertFalse(path.exists())

    def test_hosted_post_boundary_uses_only_immutable_review_identity(self) -> None:
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
            if args == ["gh", "api", "user"]:
                output = {"login": "maintainer"}
            else:
                output = comment
            return CompletedProcess(args, 0, json.dumps(output), "")

        changed_metadata = PullRequestSnapshot(
            42,
            "OPEN",
            "develop",
            BASE,
            HEAD,
            "renamed-feature",
            7,
            "UNKNOWN",
            False,
            True,
        )
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "trigger.json"
            with (
                patch.object(live, "pull_request", side_effect=[snapshot, changed_metadata]),
                patch.object(live, "branch_head", return_value=BASE),
                patch.object(github, "fetch_pull_request", return_value=self._payload()),
                patch.object(hosted, "default_trigger_record_path", return_value=path),
                patch.object(hosted, "current_trigger_record_paths", return_value=[]),
                patch.object(evidence, "git_common_dir", return_value=Path(directory)),
                patch("pr_review.runtime.subprocess.run", side_effect=gh_call),
            ):
                result = HostedRunner("owner/repo", live)(target, expect_pr=42)
            record = json.loads(path.read_text(encoding="utf-8"))
        self.assertEqual(result["status"], "posted")
        self.assertEqual(record["status"], "posted")

    def test_hosted_post_boundary_fails_closed_on_review_identity_change(self) -> None:
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

        changed_identities = (
            PullRequestSnapshot(42, "OPEN", "develop", BASE, "d" * 40, "feature", 1),
            PullRequestSnapshot(42, "OPEN", "release", BASE, HEAD, "feature", 1),
            PullRequestSnapshot(42, "OPEN", "develop", "e" * 40, HEAD, "feature", 1),
        )
        for after in changed_identities:
            with self.subTest(after=after), tempfile.TemporaryDirectory() as directory:
                path = Path(directory) / "trigger.json"
                with (
                    patch.object(live, "pull_request", side_effect=[snapshot, after]),
                    patch.object(live, "branch_head", return_value=BASE),
                    patch.object(github, "fetch_pull_request", return_value=self._payload()),
                    patch.object(hosted, "default_trigger_record_path", return_value=path),
                    patch.object(hosted, "current_trigger_record_paths", return_value=[]),
                    patch.object(evidence, "git_common_dir", return_value=Path(directory)),
                    patch("pr_review.runtime.subprocess.run", side_effect=gh_call),
                    self.assertRaisesRegex(ControllerError, "changed across the Hosted posting boundary"),
                ):
                    HostedRunner("owner/repo", live)(target, expect_pr=42)
                self.assertEqual(json.loads(path.read_text(encoding="utf-8"))["status"], "posted_boundary_changed")

    def test_hosted_post_boundary_detects_parent_advance_when_pr_snapshot_is_stale(self) -> None:
        advanced_base = "9" * 40
        snapshot = PullRequestSnapshot(42, "OPEN", "develop", BASE, HEAD, "feature", 1)
        selected = ReviewTarget(
            snapshot,
            EffectiveParent("develop", BASE),
            patch_identity=PATCH,
            merge_base=BASE,
            repository="owner/repo",
            default_base_front=True,
            default_test_merge_base_sha=BASE,
            default_test_merge_head_sha=HEAD,
            default_test_merge_tree_sha="d" * 40,
        )
        live = LiveGitHub("owner/repo")
        comment = {
            "id": 123,
            "created_at": "2026-09-23T00:01:00Z",
            "html_url": "https://example.test/123",
            "body": hosted.FULL_COMMAND,
            "user": {"login": "maintainer"},
        }
        post_calls = []

        def gh_call(args, **kwargs):
            if args == ["gh", "api", "user"]:
                output = {"login": "maintainer"}
            else:
                post_calls.append(args)
                output = comment
            return CompletedProcess(args, 0, json.dumps(output), "")

        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "trigger.json"
            with (
                patch.object(live, "pull_request", return_value=snapshot),
                patch.object(live, "branch_head", side_effect=(BASE, BASE, advanced_base)),
                patch.object(github, "fetch_pull_request", return_value=self._payload()),
                patch.object(hosted, "default_trigger_record_path", return_value=path),
                patch.object(hosted, "current_trigger_record_paths", return_value=[]),
                patch.object(evidence, "git_common_dir", return_value=Path(directory)),
                patch("pr_review.runtime.subprocess.run", side_effect=gh_call),
                self.assertRaisesRegex(ControllerError, "effective parent changed across the Hosted posting boundary"),
            ):
                HostedRunner("owner/repo", live)(selected, expect_pr=42)
            record = json.loads(path.read_text(encoding="utf-8"))

        self.assertEqual(record["status"], "posted_boundary_changed")
        self.assertEqual(record["anchor"]["parent_head"], BASE)
        self.assertEqual(record["trigger"]["id"], 123)
        self.assertEqual(len(post_calls), 1)

    def test_hosted_request_floor_failure_leaves_no_reservation_or_post(self) -> None:
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
            path = Path(directory) / "trigger.json"
            post_calls = []

            def gh_call(args, **kwargs):
                if args == ["gh", "api", "user"]:
                    return CompletedProcess(args, 0, json.dumps({"login": "maintainer"}), "")
                post_calls.append(args)
                return CompletedProcess(args, 0, "{}", "")

            with (
                patch.object(live, "pull_request", return_value=snapshot),
                patch.object(live, "branch_head", return_value=BASE),
                patch.object(github, "fetch_pull_request", return_value=self._payload()),
                patch.object(hosted, "_comment_id_floor", side_effect=TypeError("incomplete comments")),
                patch.object(hosted, "default_trigger_record_path", return_value=path),
                patch.object(evidence, "git_common_dir", return_value=Path(directory)),
                patch("pr_review.runtime.subprocess.run", side_effect=gh_call),
                self.assertRaisesRegex(ControllerError, "pre-POST comment identity floor"),
            ):
                HostedRunner("owner/repo", live)(target, expect_pr=42)
            self.assertFalse(path.exists())
            self.assertEqual(post_calls, [])

    def test_hosted_runner_rejects_a_default_base_move_before_writing_or_posting(self) -> None:
        advanced_base = "9" * 40
        snapshot = PullRequestSnapshot(42, "OPEN", "develop", BASE, HEAD, "feature", 1)
        selected = ReviewTarget(
            snapshot,
            EffectiveParent("develop", BASE),
            patch_identity=PATCH,
            merge_base=BASE,
            repository="owner/repo",
            default_base_front=True,
            default_test_merge_base_sha=BASE,
            default_test_merge_head_sha=HEAD,
            default_test_merge_tree_sha="d" * 40,
        )
        live = LiveGitHub("owner/repo")
        payload = self._payload()
        pull = payload["data"]["repository"]["pullRequest"]
        pull.update(
            {
                "number": 42,
                "baseRefName": "develop",
                "baseRefOid": advanced_base,
            }
        )

        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "trigger.json"
            calls = []

            def subprocess_call(args, **kwargs):
                calls.append(args)
                if args == ["gh", "api", "user"]:
                    return CompletedProcess(args, 0, json.dumps({"login": "maintainer"}), "")
                raise AssertionError("stale target must not issue a Hosted POST")

            with (
                patch.object(live, "pull_request", return_value=snapshot),
                patch.object(live, "branch_head", return_value=BASE),
                patch.object(github, "fetch_pull_request", return_value=payload),
                patch.object(hosted, "default_trigger_record_path", return_value=path),
                patch.object(hosted, "current_trigger_record_paths", return_value=[]),
                patch.object(evidence, "git_common_dir", return_value=Path(directory)),
                patch("pr_review.runtime.subprocess.run", side_effect=subprocess_call),
                self.assertRaisesRegex(StaleReviewTarget, "base advanced"),
            ):
                HostedRunner("owner/repo", live)(selected, expect_pr=42)

            self.assertFalse(path.exists())
            self.assertEqual(len(calls), 1)
            self.assertEqual(calls[0], ["gh", "api", "user"])

    def test_hosted_runner_reselects_when_base_moves_while_mergeability_is_unknown(self) -> None:
        advanced_base = "9" * 40
        selected_snapshot = PullRequestSnapshot(42, "OPEN", "develop", BASE, HEAD, "feature", 1)
        selected = ReviewTarget(
            selected_snapshot,
            EffectiveParent("develop", BASE),
            patch_identity=PATCH,
            merge_base=BASE,
            repository="owner/repo",
            default_base_front=True,
            default_test_merge_base_sha=BASE,
            default_test_merge_head_sha=HEAD,
            default_test_merge_tree_sha="d" * 40,
        )
        current = PullRequestSnapshot(
            42,
            "OPEN",
            "develop",
            advanced_base,
            HEAD,
            "feature",
            1,
            mergeable="UNKNOWN",
        )
        live = LiveGitHub("owner/repo")
        with (
            patch.object(live, "pull_request", return_value=current),
            patch("pr_review.runtime.subprocess.run") as run,
            self.assertRaisesRegex(StaleReviewTarget, "base advanced"),
        ):
            HostedRunner("owner/repo", live)(selected, expect_pr=42)
        run.assert_not_called()

    @staticmethod
    def _payload(comments=None, reviews=None, threads=None, *, head=HEAD):
        return {
            "data": {
                "repository": {
                    "pullRequest": {
                        "number": 42,
                        "baseRefName": "develop",
                        "baseRefOid": BASE,
                        "headRefOid": head,
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

    @staticmethod
    def _bind_trigger(common: Path, payload: dict[str, Any], record: dict[str, Any]) -> None:
        trigger = record["trigger"]
        payload["data"]["repository"]["pullRequest"]["comments"]["nodes"].insert(
            0,
            {
                "databaseId": trigger["id"],
                "author": {"login": "reviewer"},
                "body": hosted.FULL_COMMAND,
                "createdAt": trigger["created_at"],
                "url": trigger["url"],
            },
        )
        path = hosted.default_trigger_record_path("owner/repo", 42, common)
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(record), encoding="utf-8")

    def _history(self, common: Path, payload, channel="hosted", *, changed_files=1, current_head=HEAD):
        snapshot = PullRequestSnapshot(42, "OPEN", "develop", BASE, current_head, "feature", changed_files)
        live = LiveGitHub("owner/repo")
        with (
            patch.object(github, "fetch_pull_request", return_value=payload),
            patch.object(live, "pull_request", return_value=snapshot),
            patch.object(evidence, "git_common_dir", return_value=common),
        ):
            return list(LiveEvidence("owner/repo", live).history(42, channel))

    def test_history_uses_complete_review_snapshot_head_without_extra_metadata_read(self):
        live = LiveGitHub("owner/repo")
        payload = self._payload()
        payload["data"]["repository"]["pullRequest"]["changedFiles"] = 1
        with (
            patch.object(github, "fetch_pull_request", return_value=payload),
            patch.object(live, "pull_request", side_effect=AssertionError("redundant metadata read")),
            patch.object(evidence, "discover_cli_captures", return_value=[]),
        ):
            history = list(LiveEvidence("owner/repo", live).history(42, "cli"))
            self.assertEqual(HEAD, history[0]["head"])

    def test_cli_history_resolves_common_dir_and_records_store_once_with_attribution(self):
        class EmptyRecords:
            @staticmethod
            def cli_source_decisions(_run_id):
                return None

        with tempfile.TemporaryDirectory() as directory:
            common = Path(directory)
            (common / "firemud" / "pr-review-stack.json").mkdir(parents=True)
            captures_root = common / "coderabbit-review-logs"
            for run_id in ("run.HistoryOne", "run.HistoryTwo"):
                run = captures_root / run_id
                run.mkdir(parents=True)
                (run / "metadata").write_text(
                    f"run_id={run_id}\nrepository=owner/repo\npull_request=42\n"
                    f"candidate_sha={HEAD}\ncandidate_files=1\n",
                    encoding="utf-8",
                )
                (run / "stdout").write_text(
                    json.dumps(
                        {"type": "complete", "status": "review_completed", "findings": 0, "reviewedFiles": ["a"]}
                    )
                    + "\n",
                    encoding="utf-8",
                )
                (run / "exit-status").write_text("0\n", encoding="utf-8")

            payload = self._payload()
            payload["data"]["repository"]["pullRequest"]["changedFiles"] = 1
            live = LiveGitHub("owner/repo")
            with (
                patch.object(github, "fetch_pull_request", return_value=payload),
                patch.object(evidence, "git_common_dir", return_value=common) as resolve_common,
                patch.object(sqlite_review_records, "SqliteReviewRecords", return_value=EmptyRecords()) as make_records,
            ):
                history = list(LiveEvidence("owner/repo", live).history(42, "cli"))

            self.assertEqual(resolve_common.call_count, 1)
            self.assertEqual(make_records.call_count, 1)
            captures = [item for item in history if item["checkpoint"].startswith("pending-capture:")]
            self.assertEqual(
                {item["checkpoint"] for item in captures},
                {"pending-capture:run.HistoryOne", "pending-capture:run.HistoryTwo"},
            )
            self.assertTrue(all(not item["completed"] and not item["attributable"] for item in captures))
            self.assertTrue(all(item["held"] for item in captures))
            self.assertEqual({item["head"] for item in captures}, {HEAD})

    def test_cli_history_holds_checkpoint_when_capture_context_is_unavailable(self):
        run_id = "run.ContextUnavailable"
        checkpoint = {
            "databaseId": 44,
            "body": (f"CLI: 0 found / 0 accepted · `{HEAD[:12]}` · 1 files\n<!-- firemud-cli-run: {run_id} -->"),
            "createdAt": "2026-09-23T00:01:00Z",
            "updatedAt": "2026-09-23T00:01:00Z",
        }
        payload = self._payload([checkpoint])
        live = LiveGitHub("owner/repo")
        snapshot = PullRequestSnapshot(42, "OPEN", "develop", BASE, HEAD, "feature", 1)
        with (
            patch.object(github, "fetch_pull_request", return_value=payload),
            patch.object(live, "pull_request", return_value=snapshot),
            patch.object(
                evidence,
                "resolve_cli_capture_context",
                side_effect=evidence.EvidenceError("capture context cannot be read"),
            ),
            patch.object(evidence, "load_cli_capture") as load_capture,
            patch.object(evidence, "discover_cli_captures") as discover_captures,
        ):
            history = list(LiveEvidence("owner/repo", live).history(42, "cli"))

        checkpoint_rows = [item for item in history if item.get("comment_id") == 44]
        self.assertEqual(len(checkpoint_rows), 1)
        row = checkpoint_rows[0]
        self.assertFalse(row["completed"])
        self.assertFalse(row["attributable"])
        self.assertTrue(row["held"])
        self.assertFalse(row["capture_context_available"])
        self.assertIn("context is unavailable", row["reason"])
        load_capture.assert_not_called()
        discover_captures.assert_not_called()

    def test_history_fallback_normalizes_live_snapshot_head(self):
        live = LiveGitHub("owner/repo")
        payload = self._payload()
        payload["data"]["repository"]["pullRequest"]["changedFiles"] = "unknown"
        snapshot = PullRequestSnapshot(42, "OPEN", "develop", BASE, HEAD.upper(), "feature", 1)
        with (
            patch.object(github, "fetch_pull_request", return_value=payload),
            patch.object(live, "pull_request", return_value=snapshot),
            patch.object(evidence, "discover_cli_captures", return_value=[]),
        ):
            history = list(LiveEvidence("owner/repo", live).history(42, "cli"))
        self.assertEqual(HEAD, history[0]["head"])

    def test_history_exposes_valid_and_malformed_scope_markers_as_non_counting_events(self):
        valid = {
            "databaseId": 90,
            "body": ("**Review scope changed:** reused PR scope was reset\n<!-- firemud-review-scope-change -->"),
            "createdAt": "2026-09-23T00:00:00Z",
            "updatedAt": "2026-09-24T00:00:00Z",
            "author": {"login": "maintainer"},
        }
        malformed = {
            "databaseId": 91,
            "body": "**Review scope changed:** missing the required hidden marker",
            "createdAt": "2026-09-25T00:00:00Z",
            "updatedAt": "2026-09-25T00:00:00Z",
            "author": {"login": "maintainer"},
        }
        malformed_marker = {
            "databaseId": 92,
            "body": "<!-- firemud-review-scope-change -- >",
            "createdAt": "2026-09-26T00:00:00Z",
            "updatedAt": "2026-09-26T00:00:00Z",
            "author": {"login": "maintainer"},
        }
        mid_body_marker = {
            "databaseId": 94,
            "body": "The marker is only mentioned here <!-- firemud-review-scope-change -->",
            "createdAt": "2026-09-27T00:00:00Z",
            "updatedAt": "2026-09-27T00:00:00Z",
            "author": {"login": "maintainer"},
        }
        indented_marker = {
            "databaseId": 95,
            "body": "**Review scope changed:** indentation is not accepted\n <!-- firemud-review-scope-change -->",
            "createdAt": "2026-09-28T00:00:00Z",
            "updatedAt": "2026-09-28T00:00:00Z",
            "author": {"login": "maintainer"},
        }
        indented_marker_only = {
            "databaseId": 97,
            "body": " <!-- firemud-review-scope-change -->",
            "createdAt": "2026-09-28T12:00:00Z",
            "updatedAt": "2026-09-28T12:00:00Z",
            "author": {"login": "maintainer"},
        }
        coderabbit_marker = {
            "databaseId": 96,
            "body": ("**Review scope changed:** bot-authored marker is ignored\n<!-- firemud-review-scope-change -->"),
            "createdAt": "2026-09-29T00:00:00Z",
            "updatedAt": "2026-09-29T00:00:00Z",
            "author": {"login": "coderabbitai[bot]"},
        }
        with tempfile.TemporaryDirectory() as directory:
            history = self._history(
                Path(directory),
                self._payload(
                    [
                        valid,
                        malformed,
                        malformed_marker,
                        mid_body_marker,
                        indented_marker,
                        coderabbit_marker,
                        indented_marker_only,
                    ]
                ),
            )

        markers = [item for item in history if item.get("scope_changed") is True]
        self.assertEqual(len(markers), 3)
        self.assertEqual([marker["comment_id"] for marker in markers], [90, 91, 95])
        self.assertEqual(markers[0]["kind"], "scope_change")
        self.assertFalse(markers[0]["scope_change_malformed"])
        self.assertEqual(markers[0]["observed_at"], "2026-09-24T00:00:00Z")
        self.assertEqual([marker["kind"] for marker in markers[1:]], ["scope_change_malformed"] * 2)
        self.assertTrue(all(marker["scope_change_malformed"] for marker in markers[1:]))
        for marker in markers:
            self.assertFalse(marker["completed"])
            self.assertFalse(marker["attributable"])
            self.assertTrue(marker["non_counting"])
            self.assertEqual((marker["accepted"], marker["raw"]), (0, 0))
        self.assertTrue(any(item.get("scope_timeline_complete") is True for item in history))

    def test_empty_history_includes_complete_scope_timeline(self):
        with tempfile.TemporaryDirectory() as directory:
            history = self._history(Path(directory), self._payload([]))

        self.assertEqual(len(history), 1)
        self.assertTrue(history[0]["scope_timeline"])
        self.assertTrue(history[0]["scope_timeline_complete"])
        self.assertTrue(history[0]["non_counting"])

    def test_history_skips_non_string_comment_bodies_during_scope_change_scan(self):
        comment = {
            "databaseId": 93,
            "body": None,
            "createdAt": "2026-09-26T00:00:00Z",
            "updatedAt": "2026-09-26T00:00:00Z",
            "author": {"login": "maintainer"},
        }
        with tempfile.TemporaryDirectory() as directory:
            history = self._history(Path(directory), self._payload([comment]))

        self.assertFalse(any(item.get("scope_changed") is True for item in history))
        self.assertTrue(any(item.get("scope_timeline_complete") is True for item in history))

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

    def test_review_stop_audit_excludes_only_proven_terminal_rate_limit_reservations(self) -> None:
        now = datetime.now(timezone.utc).replace(microsecond=0)
        trigger_at = (now - timedelta(minutes=2)).isoformat().replace("+00:00", "Z")
        response_at = (now - timedelta(seconds=5)).isoformat().replace("+00:00", "Z")
        cooldown_until = (now + timedelta(minutes=30)).isoformat().replace("+00:00", "Z")
        trigger = {
            "databaseId": 10,
            "author": {"login": "maintainer"},
            "body": hosted.FULL_COMMAND,
            "createdAt": trigger_at,
            "updatedAt": trigger_at,
            "url": "https://example.test/comments/10",
        }
        response = {
            "databaseId": 11,
            "author": {"login": "coderabbitai"},
            "body": "Review rate limited; next reviews available in 30 minutes",
            "createdAt": response_at,
            "updatedAt": response_at,
        }
        payload = self._payload([trigger, response])
        pull = payload["data"]["repository"]["pullRequest"]
        pull.update({"number": 42, "baseRefName": "develop", "baseRefOid": BASE})
        snapshot = PullRequestSnapshot(42, "OPEN", "develop", BASE, HEAD, "feature", 1)
        live = LiveGitHub("owner/repo")
        observer = LiveEvidence("owner/repo", live)
        record = self._trigger_record(created=trigger_at)
        old_head = "7" * 40
        record["head_sha"] = old_head
        state = SimpleNamespace(
            trigger_comment_id=10,
            trigger_created_at=trigger_at,
            state="rate_limited",
            terminal=True,
            attributed=True,
            response_id=11,
            response_created_at=response_at,
            head_sha=old_head,
            cooldown_until=cooldown_until,
            reason="CodeRabbit explicitly rate limited the request",
        )
        anchor = {
            "pr": 42,
            "child_head": HEAD,
            "parent_identity": "develop",
            "parent_head": BASE,
            "merge_base": BASE,
            "patch_id": PATCH,
        }
        generic_audit = {
            "complete": True,
            "active_reservations": ["rate_limited", "review active"],
            "unmatched_responses": [],
            "historical_unmatched_responses": [],
            "ambiguous_responses": [],
            "unresolved_findings": [],
        }

        with (
            patch.object(github, "fetch_pull_request", return_value=payload),
            patch.object(live, "pull_request", return_value=snapshot),
            patch.object(observer, "legacy_transition_reauthorization_audit", return_value=generic_audit),
            patch.object(observer, "_complete_trigger_paths", return_value=[Path("trigger.json")]),
            patch.object(hosted, "load_trigger_record", return_value=record),
            patch.object(hosted, "trigger_state", return_value=state),
            patch.object(observer, "history", return_value=[]),
        ):
            audit = observer.review_stop_audit(42, anchor)

        self.assertEqual(audit["active_reservations"], ["review active"])
        self.assertEqual(
            audit["terminal_rate_limits"],
            [
                {
                    "trigger_id": 10,
                    "response_id": 11,
                    "captured_head": old_head,
                    "cooldown_until": cooldown_until,
                    "terminal": True,
                    "attributable": True,
                }
            ],
        )
        self.assertIn("active Hosted reservation: review active", audit["blockers"])

        expired_cooldown = (now - timedelta(minutes=1)).isoformat().replace("+00:00", "Z")
        expired_state = SimpleNamespace(**{**vars(state), "cooldown_until": expired_cooldown})
        expired_audit = {**generic_audit, "active_reservations": ["review active"]}
        with (
            patch.object(github, "fetch_pull_request", return_value=payload),
            patch.object(live, "pull_request", return_value=snapshot),
            patch.object(observer, "legacy_transition_reauthorization_audit", return_value=expired_audit),
            patch.object(observer, "_complete_trigger_paths", return_value=[Path("trigger.json")]),
            patch.object(hosted, "load_trigger_record", return_value=record),
            patch.object(hosted, "trigger_state", return_value=expired_state),
            patch.object(observer, "history", return_value=[]),
        ):
            expired_result = observer.review_stop_audit(42, anchor)

        self.assertEqual(expired_result["active_reservations"], ["review active"])
        self.assertEqual(
            expired_result["terminal_rate_limits"][0]["cooldown_until"],
            expired_cooldown,
        )

        mismatched_audit = {**generic_audit, "active_reservations": ["review active"]}
        with (
            patch.object(github, "fetch_pull_request", return_value=payload),
            patch.object(live, "pull_request", return_value=snapshot),
            patch.object(observer, "legacy_transition_reauthorization_audit", return_value=mismatched_audit),
            patch.object(observer, "_complete_trigger_paths", return_value=[Path("trigger.json")]),
            patch.object(hosted, "load_trigger_record", return_value=record),
            patch.object(hosted, "trigger_state", return_value=state),
            patch.object(observer, "history", return_value=[]),
            self.assertRaisesRegex(ControllerError, "cannot be isolated from other reservations"),
        ):
            observer.review_stop_audit(42, anchor)

    def test_hosted_checkpoint_requires_matching_completed_durable_trigger_and_anchor(self) -> None:
        body = (
            f"Hosted: 1 found / 0 accepted / 1 routed · `{HEAD[:12]}` · 1 files · 2m 00s\n"
            "<!-- firemud-hosted-review: 55 -->\n<!-- firemud-review-duration-seconds: 120 -->"
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
            self.assertEqual(_review_activity(history, HEAD)["recent"][0]["routed"], 1)

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

    def test_zero_hosted_checkpoint_can_link_to_attributable_finished_reply(self) -> None:
        trigger_at = "2026-09-23T00:01:00Z"
        summary_at = "2026-09-23T00:02:00Z"
        reply_at = "2026-09-23T00:03:00Z"
        checkpoint_at = "2026-09-23T00:04:00Z"
        trigger = {
            "databaseId": 10,
            "author": {"login": "maintainer"},
            "body": hosted.FULL_COMMAND,
            "createdAt": trigger_at,
            "updatedAt": trigger_at,
            "url": "https://example.test/comments/10",
        }
        summary = {
            "databaseId": 12,
            "author": {"login": "coderabbitai[bot]"},
            "body": (
                "No actionable comments were generated in the recent review.\n"
                f"Reviewing files that changed from the base of the PR and between {BASE} and {HEAD}."
            ),
            "createdAt": summary_at,
            "updatedAt": summary_at,
        }
        reply = {
            "databaseId": 11,
            "author": {"login": "coderabbitai"},
            "body": "Full review finished.",
            "createdAt": reply_at,
            "updatedAt": reply_at,
        }
        checkpoint = {
            "databaseId": 13,
            "author": {"login": "maintainer"},
            "body": (
                f"Hosted: 0 found / 0 accepted · `{HEAD[:12]}` · 1 files · 120s\n"
                "<!-- firemud-hosted-review: 11 -->\n"
                "<!-- firemud-review-duration-seconds: 120 -->"
            ),
            "createdAt": checkpoint_at,
            "updatedAt": checkpoint_at,
        }

        def history_for(comments, reviews=None, threads=None):
            with tempfile.TemporaryDirectory() as directory:
                common = Path(directory)
                record_path = hosted.default_trigger_record_path("owner/repo", 42, common)
                record_path.parent.mkdir(parents=True)
                record_path.write_text(json.dumps(self._trigger_record(created=trigger_at)), encoding="utf-8")
                return self._history(common, self._payload(comments, reviews, threads))

        valid = history_for([trigger, summary, reply, checkpoint])
        self.assertTrue(any(item.get("checkpoint") == "13" and item.get("completed") for item in valid))

        legacy_inline = {
            "databaseId": 54,
            "author": {"login": "coderabbitai[bot]"},
            "body": "A late finding",
            "createdAt": "2026-09-23T00:03:30Z",
            "updatedAt": "2026-09-23T00:03:30Z",
        }
        invalidated = history_for(
            [trigger, summary, reply, checkpoint],
            threads=[{"comments": {"nodes": [legacy_inline]}}],
        )
        self.assertFalse(any(item.get("checkpoint") == "13" and item.get("completed") for item in invalidated))

        provider_summary = {
            **summary,
            "body": (
                "0 actionable comments found.\n"
                "Files selected: 89. Files reviewed: 89.\n"
                "Files not reviewed due to moderation or processing errors: 0.\n"
                f"Reviewing files that changed from the base of the PR and between {BASE} and {HEAD}."
            ),
            "updatedAt": "2026-09-23T00:03:20Z",
        }
        edited_reply = {**reply, "updatedAt": "2026-09-23T00:03:30Z"}
        edited_duration_checkpoint = {
            **checkpoint,
            "body": checkpoint["body"].replace("120s", "150s").replace(": 120 -->", ": 150 -->"),
        }
        provider_valid = history_for([trigger, provider_summary, edited_reply, edited_duration_checkpoint])
        self.assertTrue(any(item.get("checkpoint") == "13" and item.get("completed") for item in provider_valid))
        provider_old_duration = history_for([trigger, provider_summary, edited_reply, checkpoint])
        self.assertFalse(
            any(item.get("checkpoint") == "13" and item.get("completed") for item in provider_old_duration)
        )

        incomplete_provider_summary = {
            **provider_summary,
            "body": (
                "0 actionable comments found.\n"
                "Files selected: 89. Files reviewed: 61.\n"
                "Files not reviewed due to moderation or processing errors: 28.\n"
                "3 reported issues remain open.\n"
                f"Reviewing files that changed from the base of the PR and between {BASE} and {HEAD}."
            ),
        }
        stale_provider_summary = {**provider_summary, "updatedAt": trigger_at}
        mismatched_provider_summary = {
            **provider_summary,
            "body": provider_summary["body"].replace(HEAD, "d" * 40),
        }
        for invalid_summary in (
            incomplete_provider_summary,
            stale_provider_summary,
            mismatched_provider_summary,
        ):
            with self.subTest(provider_summary=invalid_summary["body"]):
                rejected = history_for([trigger, invalid_summary, edited_reply, edited_duration_checkpoint])
                self.assertFalse(any(item.get("checkpoint") == "13" and item.get("completed") for item in rejected))

        inline_comment = {
            "databaseId": 54,
            "author": {"login": "coderabbitai[bot]"},
            "body": "A post-trigger inline finding.",
            "createdAt": "2026-09-23T00:02:30Z",
            "updatedAt": "2026-09-23T00:02:30Z",
        }
        with_inline_output = history_for(
            [trigger, provider_summary, edited_reply, edited_duration_checkpoint],
            threads=[{"comments": {"nodes": [inline_comment]}}],
        )
        self.assertFalse(any(item.get("checkpoint") == "13" and item.get("completed") for item in with_inline_output))

        post_trigger_comment = {
            "databaseId": 53,
            "author": {"login": "coderabbitai"},
            "body": "A post-trigger bot comment.",
            "createdAt": "2026-09-23T00:02:30Z",
            "updatedAt": "2026-09-23T00:02:30Z",
        }
        with_issue_output = history_for(
            [trigger, provider_summary, post_trigger_comment, edited_reply, edited_duration_checkpoint]
        )
        self.assertFalse(any(item.get("checkpoint") == "13" and item.get("completed") for item in with_issue_output))

        mismatched_summary = {**summary, "body": summary["body"].replace(HEAD, "d" * 40)}
        missing_summary = [trigger, reply, checkpoint]
        self.assertTrue(
            any(item.get("checkpoint") == "13" and item.get("completed") for item in history_for(missing_summary))
        )
        for invalid_comments in (
            [trigger, mismatched_summary, reply, checkpoint],
            [trigger, summary, {**reply, "author": {"login": "other-user"}}, checkpoint],
            [
                trigger,
                summary,
                {**reply, "body": "Review rate limited; next reviews available in 30 minutes"},
                checkpoint,
            ],
        ):
            with self.subTest(comments=invalid_comments):
                rejected = history_for(invalid_comments)
                self.assertFalse(any(item.get("checkpoint") == "13" and item.get("completed") for item in rejected))

        later_substantive_summary = {
            "databaseId": 15,
            "author": {"login": "coderabbitai[bot]"},
            "body": (
                "<!-- walkthrough_start -->\n"
                f"Reviewing files that changed from the base of the PR and between {BASE} and {HEAD}."
            ),
            "createdAt": "2026-09-23T00:02:30Z",
            "updatedAt": "2026-09-23T00:02:30Z",
        }
        later_summary_history = history_for([trigger, summary, later_substantive_summary, reply, checkpoint])
        self.assertFalse(
            any(item.get("checkpoint") == "13" and item.get("completed") for item in later_summary_history)
        )

        wrong_duration = {
            **checkpoint,
            "body": checkpoint["body"].replace("2m 00s", "2m 01s").replace("seconds: 120", "seconds: 121"),
        }
        wrong_duration_history = history_for([trigger, summary, reply, wrong_duration])
        self.assertFalse(
            any(item.get("checkpoint") == "13" and item.get("completed") for item in wrong_duration_history)
        )

        conflicting_review = {
            "databaseId": 55,
            "author": {"login": "coderabbitai[bot]"},
            "body": f"<!-- walkthrough_start -->\nReviewed {HEAD}",
            "state": "COMMENTED",
            "submittedAt": summary_at,
            "commit": {"oid": "d" * 40},
        }
        conflicted = history_for([trigger, summary, reply, checkpoint], [conflicting_review])
        self.assertFalse(any(item.get("checkpoint") == "13" and item.get("completed") for item in conflicted))

    def test_later_explicit_terminal_comment_overrides_immutable_review(self) -> None:
        trigger_at = "2026-09-27T17:27:32Z"
        trigger = {
            "databaseId": 10,
            "author": {"login": "maintainer"},
            "body": hosted.FULL_COMMAND,
            "createdAt": trigger_at,
            "updatedAt": trigger_at,
            "url": "https://example.test/comments/10",
        }
        review = {
            "databaseId": 55,
            "author": {"login": "coderabbitai[bot]"},
            "body": f"<!-- walkthrough_start -->\nReviewed {HEAD}",
            "state": "COMMENTED",
            "submittedAt": "2026-09-27T17:30:00Z",
            "commit": {"oid": HEAD},
        }
        terminal_comment = {
            "databaseId": 56,
            "author": {"login": "coderabbitai[bot]"},
            "body": "Review rate limited; next reviews available in 30 minutes",
            "createdAt": "2026-09-27T17:31:00Z",
            "updatedAt": "2026-09-27T17:31:00Z",
        }

        state = hosted.trigger_state(
            "owner/repo",
            42,
            self._payload([trigger, terminal_comment], [review]),
            self._trigger_record(created=trigger_at),
        )

        self.assertEqual((state.state, state.response_id), ("rate_limited", 56))

    def test_edited_finished_acknowledgment_cannot_supersede_immutable_review(self) -> None:
        trigger_at = "2026-09-27T17:27:32Z"
        trigger = {
            "databaseId": 10,
            "author": {"login": "maintainer"},
            "body": hosted.FULL_COMMAND,
            "createdAt": trigger_at,
            "updatedAt": trigger_at,
            "url": "https://example.test/comments/10",
        }
        summary = {
            "databaseId": 12,
            "author": {"login": "coderabbitai[bot]"},
            "body": (
                "No actionable comments were generated in the recent review.\n"
                f"Reviewing files that changed from the base of the PR and between {BASE} and {HEAD}."
            ),
            "createdAt": "2026-09-27T17:31:00Z",
            "updatedAt": "2026-09-27T17:31:00Z",
        }
        acknowledgment = {
            "databaseId": 11,
            "author": {"login": "coderabbitai"},
            "body": "Full review finished.",
            "createdAt": "2026-09-27T17:28:00Z",
            "updatedAt": "2026-09-27T17:34:00Z",
        }
        review = {
            "databaseId": 55,
            "author": {"login": "coderabbitai[bot]"},
            "body": f"<!-- walkthrough_start -->\nReviewed {HEAD}",
            "state": "COMMENTED",
            "submittedAt": "2026-09-27T17:30:00Z",
            "commit": {"oid": HEAD},
        }

        state = hosted.trigger_state(
            "owner/repo",
            42,
            self._payload([trigger, summary, acknowledgment], [review]),
            self._trigger_record(created=trigger_at),
        )

        self.assertEqual((state.state, state.response_id), ("completed", 55))

    def test_archived_zero_reply_checkpoint_survives_a_later_hosted_review(self) -> None:
        first_head = "932ff6e0027214d7e5303941de11e52501230220"
        second_head = "52a" + "8" * 37
        first_at = "2026-09-27T17:27:32Z"
        first_trigger = {
            "databaseId": 5858100193,
            "author": {"login": "maintainer"},
            "body": hosted.FULL_COMMAND,
            "createdAt": first_at,
            "updatedAt": first_at,
            "url": "https://example.test/comments/5858100193",
        }
        first_summary = {
            "databaseId": 5858101150,
            "author": {"login": "coderabbitai[bot]"},
            "body": (
                "No actionable comments were generated in the recent review.\n"
                "**Actionable comments posted:** 0\n"
                f"Reviewing files that changed from the base of the PR and between {BASE} and {first_head}."
            ),
            "createdAt": "2026-09-27T17:30:00Z",
            "updatedAt": "2026-09-27T17:30:00Z",
        }
        first_reply = {
            "databaseId": 5858101160,
            "author": {"login": "coderabbitai"},
            "body": "Full review finished.",
            "createdAt": "2026-09-27T17:27:40Z",
            # A later edit must not erase this already-attributed review.
            "updatedAt": "2026-09-27T21:38:20Z",
        }
        first_checkpoint = {
            "databaseId": 5858176568,
            "author": {"login": "maintainer"},
            "body": (
                f"Hosted: 0 found / 0 accepted / 0 routed · `{first_head[:9]}` · 6 files\n"
                "<!-- firemud-hosted-review: 5858101160 -->"
            ),
            "createdAt": "2026-09-27T17:38:05Z",
            "updatedAt": "2026-09-27T17:40:22Z",
        }
        second_at = "2026-09-27T21:36:08Z"
        second_trigger = {
            **first_trigger,
            "databaseId": 5860038456,
            "createdAt": second_at,
            "updatedAt": second_at,
            "url": "https://example.test/comments/5860038456",
        }
        second_checkpoint = {
            **first_checkpoint,
            "databaseId": 5860127083,
            "body": (
                f"Hosted: 1 found / 1 accepted / 0 routed · `{second_head[:9]}` · 6 files\n"
                "<!-- firemud-hosted-review: 5332195978 -->"
            ),
            "createdAt": "2026-09-27T21:40:00Z",
            "updatedAt": "2026-09-27T21:40:00Z",
        }
        second_review = {
            "databaseId": 5332195978,
            "author": {"login": "coderabbitai[bot]"},
            "body": f"<!-- walkthrough_start -->\nReviewed {second_head}",
            "state": "COMMENTED",
            "submittedAt": "2026-09-27T21:39:18Z",
            "commit": {"oid": second_head},
        }
        payload = self._payload(
            [first_trigger, first_summary, first_reply, first_checkpoint, second_trigger, second_checkpoint],
            [second_review],
            head=second_head,
        )
        with tempfile.TemporaryDirectory() as directory:
            common = Path(directory)
            trigger_dir = hosted.default_trigger_record_path("owner/repo", 42, common).parent
            trigger_dir.mkdir(parents=True)
            first_record = self._trigger_record(head=first_head, created=first_at)
            first_record["status"] = "completed"
            first_record["trigger"]["id"] = 5858100193
            first_record["trigger"]["url"] = first_trigger["url"]
            archived = trigger_dir / "trigger-5858100193.json"
            archived.write_text(json.dumps(first_record), encoding="utf-8")
            second_record = self._trigger_record(head=second_head, created=second_at)
            second_record["trigger"].update({"id": 5860038456, "url": "https://example.test/comments/5860038456"})
            hosted.default_trigger_record_path("owner/repo", 42, common).write_text(
                json.dumps(second_record), encoding="utf-8"
            )

            self.assertIsNotNone(
                hosted._zero_finding_summary(
                    payload,
                    first_head,
                    hosted.parse_timestamp(first_at),
                    5858101160,
                    hosted.parse_timestamp(second_at),
                )
            )
            old_state = hosted.trigger_state("owner/repo", 42, payload, first_record, archived)
            self.assertEqual((old_state.state, old_state.response_id), ("completed", 5858101160))

            history = self._history(common, payload, current_head=second_head)

        checkpoints = {item["checkpoint"]: item for item in history if item.get("completed") is True}
        self.assertIn("5858176568", checkpoints)
        self.assertIn("5860127083", checkpoints)
        self.assertEqual(checkpoints["5858176568"]["accepted"], 0)
        self.assertEqual(checkpoints["5860127083"]["accepted"], 1)

    def test_completed_hosted_trigger_without_checkpoint_is_held(self) -> None:
        trigger_at = "2026-09-23T00:01:00Z"
        summary_at = "2026-09-23T00:02:00Z"
        reply_at = "2026-09-23T00:03:00Z"
        trigger = {
            "databaseId": 10,
            "author": {"login": "maintainer"},
            "body": hosted.FULL_COMMAND,
            "createdAt": trigger_at,
            "updatedAt": trigger_at,
            "url": "https://example.test/comments/10",
        }
        summary = {
            "databaseId": 12,
            "author": {"login": "coderabbitai[bot]"},
            "body": (
                "No actionable comments were generated in the recent review.\n"
                f"Reviewing files that changed from the base of the PR and between {BASE} and {HEAD}."
            ),
            "createdAt": summary_at,
            "updatedAt": summary_at,
        }
        reply = {
            "databaseId": 11,
            "author": {"login": "coderabbitai"},
            "body": "Full review finished.",
            "createdAt": reply_at,
            "updatedAt": reply_at,
        }
        with tempfile.TemporaryDirectory() as directory:
            common = Path(directory)
            record_path = hosted.default_trigger_record_path("owner/repo", 42, common)
            record_path.parent.mkdir(parents=True)
            record_path.write_text(json.dumps(self._trigger_record(created=trigger_at)), encoding="utf-8")
            history = self._history(common, self._payload([trigger, summary, reply]))

        held = [item for item in history if item.get("checkpoint") == "trigger-uncheckpointed:11"]
        self.assertEqual(len(held), 1)
        self.assertTrue(held[0]["held"])
        self.assertFalse(held[0].get("completed", False))

    def test_terminal_ambiguous_hosted_history_exposes_only_verified_immutable_identity(self) -> None:
        trigger_at = "2026-09-23T00:01:00Z"
        response_at = "2026-09-23T00:03:00Z"
        trigger = {
            "databaseId": 10,
            "author": {"login": "maintainer"},
            "body": hosted.FULL_COMMAND,
            "createdAt": trigger_at,
            "updatedAt": trigger_at,
            "url": "https://example.test/comments/10",
        }
        response = {
            "databaseId": 11,
            "author": {"login": "coderabbitai"},
            "body": "Full review finished.",
            "createdAt": response_at,
            "updatedAt": response_at,
        }
        state = SimpleNamespace(
            trigger_comment_id=10,
            trigger_created_at=trigger_at,
            state="ambiguous",
            terminal=True,
            attributed=False,
            response_id=11,
            response_created_at=response_at,
            head_sha=HEAD,
            reason="finished response has no head-attributed summary",
        )
        with tempfile.TemporaryDirectory() as directory:
            common = Path(directory)
            record_path = hosted.default_trigger_record_path("owner/repo", 42, common)
            record_path.parent.mkdir(parents=True)
            record_path.write_text(json.dumps(self._trigger_record(created=trigger_at)), encoding="utf-8")
            payload = self._payload([trigger, response])
            pull = payload["data"]["repository"]["pullRequest"]
            pull.update({"number": 42, "baseRefName": "develop", "baseRefOid": BASE})
            snapshot = PullRequestSnapshot(42, "OPEN", "develop", BASE, HEAD, "feature", 1)
            live = LiveGitHub("owner/repo")
            with (
                patch.object(github, "fetch_pull_request", return_value=payload),
                patch.object(live, "pull_request", return_value=snapshot),
                patch.object(evidence, "git_common_dir", return_value=common),
                patch.object(hosted, "trigger_state", return_value=state),
            ):
                history = list(LiveEvidence("owner/repo", live).history(42, "hosted"))

        held = [item for item in history if item.get("terminal_ambiguous") is True]
        self.assertEqual(len(held), 1)
        self.assertTrue(held[0]["held"])
        self.assertEqual(held[0]["trigger_id"], 10)
        self.assertEqual(held[0]["response_id"], 11)
        self.assertEqual(held[0]["captured_head"], HEAD)
        self.assertTrue(held[0]["terminal"])
        self.assertFalse(held[0]["attributable"])
        self.assertRegex(held[0]["fingerprint"], r"^[0-9a-f]{64}$")

        state.response_id = None
        state.response_created_at = None
        with tempfile.TemporaryDirectory() as directory:
            common = Path(directory)
            record_path = hosted.default_trigger_record_path("owner/repo", 42, common)
            record_path.parent.mkdir(parents=True)
            record_path.write_text(json.dumps(self._trigger_record(created=trigger_at)), encoding="utf-8")
            payload = self._payload([trigger, response])
            pull = payload["data"]["repository"]["pullRequest"]
            pull.update({"number": 42, "baseRefName": "develop", "baseRefOid": BASE})
            snapshot = PullRequestSnapshot(42, "OPEN", "develop", BASE, HEAD, "feature", 1)
            live = LiveGitHub("owner/repo")
            with (
                patch.object(github, "fetch_pull_request", return_value=payload),
                patch.object(live, "pull_request", return_value=snapshot),
                patch.object(evidence, "git_common_dir", return_value=common),
                patch.object(hosted, "trigger_state", return_value=state),
            ):
                unverified = list(LiveEvidence("owner/repo", live).history(42, "hosted"))
        self.assertFalse(any(item.get("terminal_ambiguous") is True for item in unverified))

    def test_active_hosted_observation_exposes_its_durable_anchor(self) -> None:
        now = datetime.now(timezone.utc).replace(microsecond=0)
        trigger_at = (now - timedelta(minutes=1)).isoformat().replace("+00:00", "Z")
        record = self._trigger_record(created=trigger_at)
        state = SimpleNamespace(
            trigger_comment_id=10,
            response_id=11,
            state="active",
            terminal=False,
            attributed=True,
            head_sha=HEAD,
            reason="CodeRabbit acknowledged that the full review is active",
        )
        payload = self._payload()
        pull = payload["data"]["repository"]["pullRequest"]
        pull.update({"number": 42, "baseRefName": "develop", "baseRefOid": BASE})
        snapshot = PullRequestSnapshot(42, "OPEN", "develop", BASE, HEAD, "feature", 1)
        live = LiveGitHub("owner/repo")
        with tempfile.TemporaryDirectory() as directory:
            common = Path(directory)
            record_path = hosted.default_trigger_record_path("owner/repo", 42, common)
            record_path.parent.mkdir(parents=True)
            record_path.write_text(json.dumps(record), encoding="utf-8")
            with (
                patch.object(github, "fetch_pull_request", return_value=payload),
                patch.object(live, "pull_request", return_value=snapshot),
                patch.object(evidence, "git_common_dir", return_value=common),
                patch.object(hosted, "trigger_state", return_value=state),
            ):
                history = list(LiveEvidence("owner/repo", live).history(42, "hosted"))

        observation = next(item for item in history if item.get("checkpoint") == "trigger:10")
        self.assertEqual(observation["anchor"], record["anchor"])
        self.assertEqual(observation["trigger_id"], 10)
        self.assertEqual(observation["response_id"], 11)

    def test_complete_audit_exposes_exact_active_hosted_response_identity(self) -> None:
        now = datetime.now(timezone.utc).replace(microsecond=0)
        trigger_at = (now - timedelta(minutes=2)).isoformat().replace("+00:00", "Z")
        response_at = (now - timedelta(minutes=1)).isoformat().replace("+00:00", "Z")
        trigger = {
            "databaseId": 10,
            "author": {"login": "maintainer"},
            "body": hosted.FULL_COMMAND,
            "createdAt": trigger_at,
            "updatedAt": trigger_at,
        }
        response = {
            "databaseId": 11,
            "author": {"login": "coderabbitai[bot]"},
            "body": "Full review triggered.",
            "createdAt": response_at,
            "updatedAt": response_at,
        }
        record = self._trigger_record(created=trigger_at)
        state = SimpleNamespace(
            trigger_comment_id=10,
            response_id=11,
            state="active",
            terminal=False,
            attributed=True,
            head_sha=HEAD,
            reason="CodeRabbit acknowledged that the full review is active",
        )
        payload = self._payload([trigger, response])
        pull = payload["data"]["repository"]["pullRequest"]
        pull.update({"number": 42, "baseRefName": "develop", "baseRefOid": BASE})
        snapshot = PullRequestSnapshot(42, "OPEN", "develop", BASE, HEAD, "feature", 1)
        live = LiveGitHub("owner/repo")
        observer = LiveEvidence("owner/repo", live)

        with tempfile.TemporaryDirectory() as directory:
            common = Path(directory)
            record_path = hosted.default_trigger_record_path("owner/repo", 42, common)
            record_path.parent.mkdir(parents=True)
            record_path.write_text(json.dumps(record), encoding="utf-8")
            with (
                patch.object(github, "fetch_pull_request", return_value=payload),
                patch.object(live, "pull_request", return_value=snapshot),
                patch.object(observer, "_complete_trigger_paths", return_value=[record_path]),
                patch.object(hosted, "current_trigger_record_paths", return_value=[record_path]),
                patch.object(hosted, "load_trigger_record", return_value=record),
                patch.object(hosted, "load_trigger_reservation", return_value=record),
                patch.object(hosted, "trigger_state", return_value=state),
            ):
                audit = observer.legacy_transition_reauthorization_audit(
                    42,
                    (),
                    {"child_head": HEAD, "live_base_ref": "develop", "live_base_tip": BASE},
                )

        self.assertEqual(audit["active_reservations"], ["active"])
        self.assertEqual(len(audit["active_hosted_reservations"]), 1)
        active = audit["active_hosted_reservations"][0]
        self.assertEqual(active["trigger_id"], 10)
        self.assertEqual(active["response_id"], 11)
        self.assertEqual(active["anchor"], record["anchor"])

    def test_review_stop_audit_pins_exact_terminal_ambiguity_without_legacy_reauthorization(self) -> None:
        trigger_at = "2026-09-23T00:01:00Z"
        response_at = "2026-09-23T00:03:00Z"
        second_trigger_at = "2026-09-23T00:04:00Z"
        second_response_at = "2026-09-23T00:05:00Z"
        trigger = {
            "databaseId": 10,
            "author": {"login": "maintainer"},
            "body": hosted.FULL_COMMAND,
            "createdAt": trigger_at,
            "updatedAt": trigger_at,
            "url": "https://example.test/comments/10",
        }
        response = {
            "databaseId": 11,
            "author": {"login": "coderabbitai"},
            "body": "Full review finished.",
            "createdAt": response_at,
            "updatedAt": response_at,
        }
        second_trigger = {
            **trigger,
            "databaseId": 20,
            "createdAt": second_trigger_at,
            "updatedAt": second_trigger_at,
            "url": "https://example.test/comments/20",
        }
        second_response = {
            **response,
            "databaseId": 21,
            "createdAt": second_response_at,
            "updatedAt": second_response_at,
        }
        payload = self._payload([trigger, response, second_trigger, second_response])
        pull = payload["data"]["repository"]["pullRequest"]
        pull.update({"number": 42, "baseRefName": "develop", "baseRefOid": BASE})
        snapshot = PullRequestSnapshot(42, "OPEN", "develop", BASE, HEAD, "feature", 1)
        live = LiveGitHub("owner/repo")
        observer = LiveEvidence("owner/repo", live)
        record = self._trigger_record(created=trigger_at)
        state = SimpleNamespace(
            trigger_comment_id=10,
            trigger_created_at=trigger_at,
            state="ambiguous",
            terminal=True,
            attributed=False,
            response_id=11,
            response_created_at=response_at,
            head_sha=HEAD,
            reason="finished response has no head-attributed summary",
        )
        second_record = self._trigger_record(created=second_trigger_at)
        second_record["trigger"]["id"] = 20
        second_state = SimpleNamespace(
            trigger_comment_id=20,
            trigger_created_at=second_trigger_at,
            state="ambiguous",
            terminal=True,
            attributed=False,
            response_id=21,
            response_created_at=second_response_at,
            head_sha=HEAD,
            reason="second finished response has no head-attributed summary",
        )
        anchor = {
            "pr": 42,
            "child_head": HEAD,
            "parent_identity": "develop",
            "parent_head": BASE,
            "merge_base": BASE,
            "patch_id": PATCH,
        }
        expected_audit = {
            "complete": True,
            "active_reservations": ["ambiguous", "ambiguous"],
            "unmatched_responses": [],
            "ambiguous_responses": ["unattributed trigger response", "unattributed trigger response"],
            "unresolved_findings": [],
        }

        record_by_path = {
            Path("trigger-10.json"): record,
            Path("trigger-20.json"): second_record,
        }
        state_by_trigger = {10: state, 20: second_state}

        def run_review_stop(selected_audit=expected_audit, pins=()):
            observer._payloads.clear()
            with (
                patch.object(github, "fetch_pull_request", return_value=payload),
                patch.object(live, "pull_request", return_value=snapshot),
                patch.object(observer, "legacy_transition_reauthorization_audit", return_value=selected_audit) as audit,
                patch.object(
                    observer,
                    "_complete_trigger_paths",
                    return_value=[Path("trigger-10.json"), Path("trigger-20.json")],
                ),
                patch.object(hosted, "load_trigger_record", side_effect=lambda path, *_: record_by_path[Path(path)]),
                patch.object(
                    hosted,
                    "trigger_state",
                    side_effect=lambda _repo, _pr, _payload, selected_record, _path: state_by_trigger[
                        selected_record["trigger"]["id"]
                    ],
                ),
                patch.object(observer, "history", return_value=[]),
            ):
                result = observer.review_stop_audit(
                    42,
                    anchor,
                    pins,
                )
                expected_anchor = {
                    "child_head": HEAD,
                    "live_base_ref": "develop",
                    "live_base_tip": BASE,
                }
                audit.assert_called_once_with(
                    42,
                    (),
                    expected_anchor,
                    allow_historical_unmatched=True,
                )
                return result

        observed = run_review_stop()
        ambiguous = observed["ambiguous_terminal_responses"]
        self.assertEqual(len(ambiguous), 2)
        self.assertEqual([(item["trigger_id"], item["response_id"]) for item in ambiguous], [(10, 11), (20, 21)])
        self.assertTrue(observed["blockers"])

        retained = run_review_stop(
            pins=tuple(item["fingerprint"] for item in ambiguous),
        )
        self.assertEqual(retained["retained_ambiguous"], ambiguous)
        self.assertEqual(retained["active_reservations"], [])
        self.assertEqual(retained["ambiguous_responses"], [])
        self.assertEqual(retained["blockers"], [])

        with self.assertRaisesRegex(ControllerError, "does not identify one current immutable response"):
            run_review_stop(pins=("0" * 64,))

        additional_ambiguity = {
            **expected_audit,
            "active_reservations": ["ambiguous", "ambiguous", "ambiguous"],
            "ambiguous_responses": [
                "unattributed trigger response",
                "unattributed trigger response",
                "unattributed trigger response",
            ],
        }
        with self.assertRaisesRegex(ControllerError, "cannot be isolated"):
            run_review_stop(
                additional_ambiguity,
                pins=tuple(item["fingerprint"] for item in ambiguous),
            )

    def test_archived_completed_hosted_response_uses_exact_uncheckpointed_observation_fingerprint(self) -> None:
        trigger_at = "2026-09-23T00:01:00Z"
        response_at = "2026-09-23T00:03:00Z"
        trigger = {
            "databaseId": 10,
            "author": {"login": "maintainer"},
            "body": hosted.FULL_COMMAND,
            "createdAt": trigger_at,
            "updatedAt": trigger_at,
            "url": "https://example.test/comments/10",
        }
        response = {
            "databaseId": 11,
            "author": {"login": "coderabbitai"},
            "body": "Full review finished.",
            "createdAt": response_at,
            "updatedAt": response_at,
        }
        payload = self._payload([trigger, response])
        pull = payload["data"]["repository"]["pullRequest"]
        pull.update({"number": 42, "baseRefName": "develop", "baseRefOid": BASE})
        snapshot = PullRequestSnapshot(42, "OPEN", "develop", BASE, HEAD, "feature", 1)
        live = LiveGitHub("owner/repo")
        observer = LiveEvidence("owner/repo", live)
        record = self._trigger_record(created=trigger_at)
        state = SimpleNamespace(
            trigger_comment_id=10,
            state="completed",
            terminal=True,
            attributed=True,
            response_id=11,
            head_sha=HEAD,
        )

        def run_audit(
            expected_fingerprints: tuple[str, ...],
            historical_observations: list[dict[str, Any]] | None = None,
        ) -> dict[str, object]:
            with (
                patch.object(github, "fetch_pull_request", return_value=payload),
                patch.object(live, "pull_request", return_value=snapshot),
                patch.object(observer, "_complete_trigger_paths", return_value=["trigger-10.json"]),
                patch.object(hosted, "load_trigger_record", return_value=record),
                patch.object(hosted, "trigger_state", return_value=state),
                patch.object(observer, "history", return_value=historical_observations or []),
            ):
                return observer.legacy_transition_reauthorization_audit(
                    42,
                    expected_fingerprints,
                    {"child_head": HEAD, "live_base_ref": "develop", "live_base_tip": BASE},
                )

        expected = observer._uncheckpointed_hosted_observation(42, state)
        expected_fingerprint = observation_fingerprint(expected)
        accepted = run_audit((expected_fingerprint,))
        self.assertNotIn("completed Hosted response has no checkpoint or prior audit", accepted["unmatched_responses"])

        rejected = run_audit(("d" * 64,))
        self.assertIn("completed Hosted response has no checkpoint or prior audit", rejected["unmatched_responses"])

        history_alone = run_audit((), [expected])
        self.assertIn(
            "completed Hosted response has no checkpoint or prior audit", history_alone["unmatched_responses"]
        )

    def test_unrecorded_completed_hosted_response_requires_one_checkpoint_by_review_identity(self) -> None:
        trigger = {
            "databaseId": 10,
            "author": {"login": "maintainer"},
            "body": hosted.FULL_COMMAND,
            "createdAt": "2026-09-24T00:00:00Z",
            "updatedAt": "2026-09-24T00:00:00Z",
            "url": "https://example.test/comments/10",
        }
        review = {
            "databaseId": 71,
            "author": {"login": "coderabbitai[bot]"},
            "body": f"<!-- walkthrough_start -->\nReviewed {HEAD}",
            "state": "COMMENTED",
            "submittedAt": "2026-09-24T00:01:00Z",
            "commit": {"oid": HEAD},
        }
        checkpoint = {
            "databaseId": 72,
            "author": {"login": "maintainer"},
            "body": (f"Hosted: 1 found / 1 accepted · `{HEAD}` · 1 files\n<!-- firemud-hosted-review: 71 -->"),
            "createdAt": "2026-09-24T00:02:00Z",
            "updatedAt": "2026-09-24T00:02:00Z",
        }
        payload = self._payload([trigger], [review])
        pull = payload["data"]["repository"]["pullRequest"]
        pull.update({"number": 42, "baseRefName": "develop", "baseRefOid": BASE})
        snapshot = PullRequestSnapshot(42, "OPEN", "develop", BASE, HEAD, "feature", 1)
        live = LiveGitHub("owner/repo")
        observer = LiveEvidence("owner/repo", live)

        def run_audit(checkpoints: list[dict[str, Any]]) -> dict[str, Any]:
            selected_payload = self._payload([trigger, *checkpoints], [review])
            selected_pull = selected_payload["data"]["repository"]["pullRequest"]
            selected_pull.update({"number": 42, "baseRefName": "develop", "baseRefOid": BASE})
            with (
                patch.object(github, "fetch_pull_request", return_value=selected_payload),
                patch.object(live, "pull_request", return_value=snapshot),
                patch.object(observer, "_complete_trigger_paths", return_value=[]),
                patch.object(observer, "history", return_value=[]),
            ):
                return observer.legacy_transition_reauthorization_audit(
                    42,
                    (),
                    {"child_head": HEAD, "live_base_ref": "develop", "live_base_tip": BASE},
                )

        missing = run_audit([])
        self.assertIn(
            "an unrecorded completed Hosted response has no matching public checkpoint",
            missing["unmatched_responses"],
        )

        matched = run_audit([checkpoint])
        self.assertNotIn(
            "an unrecorded completed Hosted response has no matching public checkpoint",
            matched["unmatched_responses"],
        )
        self.assertNotIn(
            "an unrecorded completed Hosted response has ambiguous public checkpoints",
            matched["ambiguous_responses"],
        )

        wrong_identity = {**checkpoint, "body": checkpoint["body"].replace("review: 71", "review: 99")}
        unmatched = run_audit([wrong_identity])
        self.assertIn(
            "an unrecorded completed Hosted response has no matching public checkpoint",
            unmatched["unmatched_responses"],
        )

        duplicate = {**checkpoint, "databaseId": 73}
        ambiguous = run_audit([checkpoint, duplicate])
        self.assertIn(
            "an unrecorded completed Hosted response has ambiguous public checkpoints",
            ambiguous["ambiguous_responses"],
        )

    def test_historical_hosted_checkpoint_uses_captured_head_after_live_head_moves(self) -> None:
        current_head = "d" * 40
        created = "2026-09-23T00:01:00Z"
        reviewed = "2026-09-23T00:02:00Z"
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
            "body": (
                f"Hosted: 1 found / 0 accepted · `{HEAD[:12]}` · 1 files · 5s\n"
                "<!-- firemud-hosted-review: 55 -->\n<!-- firemud-review-duration-seconds: 5 -->"
            ),
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
        payload = self._payload([trigger, checkpoint], [review], head=current_head)

        with tempfile.TemporaryDirectory() as directory:
            common = Path(directory)
            record_path = hosted.default_trigger_record_path("owner/repo", 42, common)
            record_path.parent.mkdir(parents=True)
            record = self._trigger_record(created=created)
            record_path.write_text(json.dumps(record), encoding="utf-8")

            history = self._history(common, payload, current_head=current_head)
            matched = [item for item in history if item.get("checkpoint") == "12"]
            self.assertEqual(len(matched), 1)
            self.assertTrue(matched[0]["completed"])
            self.assertTrue(matched[0]["attributable"])
            self.assertTrue(matched[0]["anchored"])
            self.assertEqual(matched[0]["head"], HEAD)
            self.assertFalse(matched[0]["corrected_state"])

            mismatched = {
                **record,
                "head_sha": current_head,
                "anchor": {**record["anchor"], "child_head": current_head},
            }
            record_path.write_text(json.dumps(mismatched), encoding="utf-8")
            history = self._history(common, payload, current_head=current_head)
            self.assertFalse(any(item.get("checkpoint") == "12" and item.get("completed") for item in history))

            malformed = {**record, "head_sha": "not-a-commit"}
            record_path.write_text(json.dumps(malformed), encoding="utf-8")
            history = self._history(common, payload, current_head=current_head)
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
        completed = [item for item in history if item.get("completed") is True]
        self.assertEqual([item["accepted"] for item in completed], [1])
        self.assertEqual([item["checkpoint"] for item in completed], ["12"])

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
                patch("pr_review.runtime.subprocess.run", side_effect=AssertionError("unexpected subprocess call")),
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
                "posting_comment_id_floor": 0,
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
                patch("pr_review.runtime.subprocess.run", side_effect=AssertionError("unexpected subprocess call")),
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
                        "posting_comment_id_floor": 0,
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

    def test_confirmed_prepost_recovery_finishes_the_linked_sqlite_attempt(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            common = Path(directory)
            state_directory = common / "firemud" / "pr-review-stack.json"
            state_directory.mkdir(parents=True)
            records = self._new_review_records(common / "firemud" / "pr-review-stack.sqlite3")
            attempt_id = "prepost-attempt"
            sqlite_hosted_capture.start_hosted_attempt(
                records,
                attempt_id=attempt_id,
                source_pr=42,
                candidate_sha=HEAD,
                started_at="2026-09-23T00:00:00Z",
            )
            path = common / "firemud" / "hosted" / "owner_repo" / "pr-42" / "trigger.json"
            path.parent.mkdir(parents=True)
            path.write_text(
                json.dumps(
                    {
                        "schema_version": 2,
                        "status": "posting",
                        "repository": "owner/repo",
                        "pr_number": 42,
                        "head_sha": HEAD,
                        "sqlite_attempt_id": attempt_id,
                        "posting_started_at": "2026-09-23T00:00:00Z",
                        "posting_actor_login": "maintainer",
                        "posting_comment_id_floor": 0,
                    }
                ),
                encoding="utf-8",
            )
            result, exit_status = self._dispatch_prepost_recovery(path, self._prepost_recovery_args())

            self.assertEqual(exit_status, 0)
            self.assertEqual(result["status"], "abandoned_no_post")
            attempt = records.attempt_history(42)[0]
            self.assertEqual(attempt["attempt_id"], attempt_id)
            self.assertEqual(attempt["state"], "failed")
            self.assertIsNone(attempt["run_id"])
            self.assertIn("POST was confirmed not issued", attempt["diagnostic"])

    def test_prepost_recovery_retries_after_sql_failure_without_replacing_audit(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            common = Path(directory)
            (common / "firemud" / "pr-review-stack.json").mkdir(parents=True)
            records = self._new_review_records(common / "firemud" / "pr-review-stack.sqlite3")
            attempt_id = "prepost-retry-attempt"
            sqlite_hosted_capture.start_hosted_attempt(
                records,
                attempt_id=attempt_id,
                source_pr=42,
                candidate_sha=HEAD,
                started_at="2026-09-23T00:00:00Z",
            )
            path = hosted.default_trigger_record_path("owner/repo", 42, common)
            self._posting_record(path)
            posting = json.loads(path.read_text(encoding="utf-8"))
            posting["sqlite_attempt_id"] = attempt_id
            path.write_text(json.dumps(posting), encoding="utf-8")
            args = self._prepost_recovery_args()
            first_live_head = "d" * 40
            first_payload = self._payload()
            first_payload["data"]["repository"]["pullRequest"]["headRefOid"] = first_live_head
            with (
                patch.object(hosted, "_finish_recovered_attempt", side_effect=OSError("temporary SQL failure")),
                self.assertRaisesRegex(OSError, "temporary SQL failure"),
            ):
                self._dispatch_prepost_recovery(path, args, first_payload)
            audit_path = next(path.parent.glob("prepost-abandoned-*.json"))
            original_audit = audit_path.read_bytes()
            self.assertTrue(path.exists())
            self.assertEqual(records.attempt(attempt_id)["state"], "started")

            retry_payload = self._payload()
            retry_payload["data"]["repository"]["pullRequest"]["headRefOid"] = "e" * 40
            result, status = self._dispatch_prepost_recovery(path, args, retry_payload)
            self.assertEqual(status, 0)
            self.assertEqual(result["status"], "abandoned_no_post")
            self.assertEqual(result["current_head_sha"], "e" * 40)
            self.assertFalse(path.exists())
            self.assertEqual(audit_path.read_bytes(), original_audit)
            self.assertEqual(json.loads(original_audit)["recovery"]["live_head_sha"], first_live_head)
            self.assertEqual(records.attempt(attempt_id)["state"], "failed")

    def test_prepost_recovery_rejects_a_malformed_archived_live_head(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            common = Path(directory)
            (common / "firemud" / "pr-review-stack.json").mkdir(parents=True)
            records = self._new_review_records(common / "firemud" / "pr-review-stack.sqlite3")
            attempt_id = "prepost-invalid-audit-head"
            sqlite_hosted_capture.start_hosted_attempt(
                records,
                attempt_id=attempt_id,
                source_pr=42,
                candidate_sha=HEAD,
                started_at="2026-09-23T00:00:00Z",
            )
            path = hosted.default_trigger_record_path("owner/repo", 42, common)
            self._posting_record(path)
            posting = json.loads(path.read_text(encoding="utf-8"))
            posting["sqlite_attempt_id"] = attempt_id
            path.write_text(json.dumps(posting), encoding="utf-8")
            with (
                patch.object(hosted, "_finish_recovered_attempt", side_effect=OSError("temporary SQL failure")),
                self.assertRaisesRegex(OSError, "temporary SQL failure"),
            ):
                self._dispatch_prepost_recovery(path, self._prepost_recovery_args())
            audit_path = next(path.parent.glob("prepost-abandoned-*.json"))
            audit = json.loads(audit_path.read_text(encoding="utf-8"))
            audit["recovery"]["live_head_sha"] = "not-a-sha"
            audit_path.write_text(json.dumps(audit), encoding="utf-8")

            with self.assertRaisesRegex(ValueError, "existing pre-POST recovery audit conflicts with the reservation"):
                self._dispatch_prepost_recovery(path, self._prepost_recovery_args())
            self.assertTrue(path.exists())
            self.assertEqual(records.attempt(attempt_id)["state"], "started")

    def test_confirmed_prepost_recovery_allows_optional_sql_start_to_be_absent(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            common = Path(directory)
            (common / "firemud" / "pr-review-stack.json").mkdir(parents=True)
            records = self._new_review_records(common / "firemud" / "pr-review-stack.sqlite3")
            path = hosted.default_trigger_record_path("owner/repo", 42, common)
            self._posting_record(path)
            posting = json.loads(path.read_text(encoding="utf-8"))
            posting["sqlite_attempt_id"] = "attempt-that-never-started"
            path.write_text(json.dumps(posting), encoding="utf-8")

            result, status = self._dispatch_prepost_recovery(path, self._prepost_recovery_args())
            self.assertEqual(status, 0)
            self.assertEqual(result["status"], "abandoned_no_post")
            self.assertFalse(path.exists())
            self.assertEqual(records.attempt_history(42), [])

    def test_unconfirmed_prepost_recovery_keeps_the_linked_sqlite_attempt_started(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            common = Path(directory)
            state_directory = common / "firemud" / "pr-review-stack.json"
            state_directory.mkdir(parents=True)
            records = self._new_review_records(common / "firemud" / "pr-review-stack.sqlite3")
            attempt_id = "prepost-uncertain"
            sqlite_hosted_capture.start_hosted_attempt(
                records,
                attempt_id=attempt_id,
                source_pr=42,
                candidate_sha=HEAD,
                started_at="2026-09-23T00:00:00Z",
            )
            path = common / "firemud" / "hosted" / "owner_repo" / "pr-42" / "trigger.json"
            path.parent.mkdir(parents=True)
            path.write_text(
                json.dumps(
                    {
                        "schema_version": 2,
                        "status": "posting",
                        "repository": "owner/repo",
                        "pr_number": 42,
                        "head_sha": HEAD,
                        "sqlite_attempt_id": attempt_id,
                        "posting_started_at": "2026-09-23T00:00:00Z",
                        "posting_actor_login": "maintainer",
                        "posting_comment_id_floor": 0,
                    }
                ),
                encoding="utf-8",
            )
            args = self._prepost_recovery_args(confirmed=False)
            with (
                patch.object(
                    review_cli,
                    "default_controller",
                    return_value=SimpleNamespace(repository="owner/repo"),
                ),
                patch.object(review_cli, "github") as github_module,
                self.assertRaisesRegex(review_cli.CliError, "--confirmed-not-posted"),
            ):
                review_cli._dispatch(args)
            github_module.fetch_pull_request.assert_not_called()
            self.assertEqual(records.attempt_history(42)[0]["state"], "started")
            self.assertTrue(path.exists())

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
            original_unlink = hosted.os.unlink

            def fail_reservation_unlink(unlink_path, *args, **kwargs):
                if Path(unlink_path) == path:
                    raise PermissionError("injected reservation unlink failure")
                return original_unlink(unlink_path, *args, **kwargs)

            with (
                patch.object(hosted.os, "unlink", side_effect=fail_reservation_unlink),
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
            original_audit = audit_paths[0].read_bytes()
            conflicting = json.loads(original_audit)
            conflicting["head_sha"] = "f" * 40
            audit_paths[0].write_text(json.dumps(conflicting), encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "audit conflicts"):
                self._dispatch_prepost_recovery(path, self._prepost_recovery_args())
            self.assertTrue(path.exists())
            audit_paths[0].write_bytes(original_audit)
            result, status = self._dispatch_prepost_recovery(path, self._prepost_recovery_args())
            self.assertEqual(status, 0)
            self.assertEqual(result["status"], "abandoned_no_post")
            self.assertFalse(path.exists())
            self.assertEqual(audit_paths[0].read_bytes(), original_audit)

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
            record["posting_comment_id_floor"] = 0
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

    def test_prepost_recovery_requires_captured_head_match_and_records_valid_live_head(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "pr-42" / "trigger.json"
            self._posting_record(path)
            advanced_head = "d" * 40
            payload = self._payload()
            payload["data"]["repository"]["pullRequest"]["headRefOid"] = advanced_head

            result, exit_status = self._dispatch_prepost_recovery(path, self._prepost_recovery_args(), payload)

            self.assertEqual(exit_status, 0)
            self.assertEqual(result["captured_head_sha"], HEAD)
            self.assertEqual(result["current_head_sha"], advanced_head)
            self.assertFalse(path.exists())
            audit = json.loads(Path(result["audit_path"]).read_text(encoding="utf-8"))
            self.assertEqual(audit["head_sha"], HEAD)
            self.assertEqual(audit["recovery"]["captured_head_sha"], HEAD)
            self.assertEqual(audit["recovery"]["live_head_sha"], advanced_head)

        for live_head in (None, "not-a-sha"):
            with self.subTest(live_head=live_head), tempfile.TemporaryDirectory() as directory:
                path = Path(directory) / "pr-42" / "trigger.json"
                self._posting_record(path)
                payload = self._payload()
                payload["data"]["repository"]["pullRequest"]["headRefOid"] = live_head

                with self.assertRaisesRegex(ValueError, "live pull-request head is not an exact SHA"):
                    self._dispatch_prepost_recovery(path, self._prepost_recovery_args(), payload)

                self.assertEqual(json.loads(path.read_text(encoding="utf-8"))["status"], "posting")

        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "pr-42" / "trigger.json"
            self._posting_record(path)
            with self.assertRaisesRegex(ValueError, "posting reservation head does not match recovery request"):
                self._dispatch_prepost_recovery(path, self._prepost_recovery_args(head="c" * 40))
            self.assertEqual(json.loads(path.read_text(encoding="utf-8"))["status"], "posting")

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
        body = f"CLI: 0 found / 0 accepted · `{HEAD[:12]}` · 1 files\n<!-- firemud-cli-run: {run_id} -->"
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
                + json.dumps({"type": "complete", "status": "review_completed", "findings": 1, "reviewedFiles": ["a"]})
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

    def test_pending_cli_capture_with_published_head_matching_live_head_is_held(self) -> None:
        run_id = "run.UnpublishedCandidate"
        candidate = "c" * 40
        with tempfile.TemporaryDirectory() as directory:
            common = Path(directory)
            run = common / "coderabbit-review-logs" / run_id
            run.mkdir(parents=True)
            (run / "metadata").write_text(
                "\n".join(
                    (
                        f"run_id={run_id}",
                        "repository=owner/repo",
                        "pull_request=42",
                        f"candidate_sha={candidate}",
                        f"published_head_sha={HEAD}",
                        "candidate_files=1",
                        "",
                    )
                ),
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
            self.assertIn("requires adjudication", pending[0]["reason"])

    def test_pending_cli_capture_with_old_published_head_is_not_held(self) -> None:
        run_id = "run.OldPublishedHead"
        candidate = "c" * 40
        old_head = "d" * 40
        with tempfile.TemporaryDirectory() as directory:
            common = Path(directory)
            run = common / "coderabbit-review-logs" / run_id
            run.mkdir(parents=True)
            (run / "metadata").write_text(
                "\n".join(
                    (
                        f"run_id={run_id}",
                        "repository=owner/repo",
                        "pull_request=42",
                        f"candidate_sha={candidate}",
                        f"published_head_sha={old_head}",
                        "candidate_files=1",
                        "",
                    )
                ),
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
            self.assertFalse(pending[0]["held"])
            self.assertIn("older head", pending[0]["reason"])

    def test_hosted_findings_and_provider_skip_hold_hosted_but_not_cli(self) -> None:
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
            self._bind_trigger(Path(directory), payload, self._trigger_record())
            hosted_history = self._history(Path(directory), payload, "hosted", changed_files=100)
            cli_history = self._history(Path(directory), payload, "cli", changed_files=100)
        self.assertTrue(
            any(
                item.get("held") and item.get("checkpoint", "").startswith("review-threads:") for item in hosted_history
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
        self.assertFalse(any(item.get("over_ceiling") for item in hosted_history))
        self.assertFalse(any(item.get("over_ceiling") for item in cli_history))

    def test_file_ceiling_skip_uses_current_changed_file_count_and_later_completion_clears_it(self) -> None:
        old_head = "d" * 40
        old_summary = {
            "databaseId": 19,
            "author": {"login": "coderabbitai[bot]"},
            "body": (
                f"<!-- walkthrough_start -->\nReviewing files that changed from the base of the PR and between "
                f"`{BASE}` and `{old_head}`."
            ),
            "createdAt": "2026-09-23T00:03:00Z",
        }
        skip = {
            "databaseId": 20,
            "author": {"login": "coderabbitai[bot]"},
            "body": (
                "<!-- This is an auto-generated comment: skip review by coderabbit.ai -->\n"
                "Your plan exceeds the file limit for this review."
            ),
            "createdAt": "2026-09-23T00:04:00Z",
        }
        current_head_commit = {"nodes": [{"commit": {"oid": HEAD, "committedDate": "2026-09-23T00:01:00Z"}}]}
        current_completion = {
            "databaseId": 21,
            "author": {"login": "coderabbitai[bot]"},
            "body": (
                f"<!-- walkthrough_start -->\nReviewing files that changed from the base of the PR and between "
                f"`{BASE}` and `{HEAD}`."
            ),
            "createdAt": "2026-09-23T00:02:00Z",
        }
        with tempfile.TemporaryDirectory() as directory:
            stale_skip = {**skip, "createdAt": "2026-09-23T00:00:00Z"}
            stale_payload = self._payload([old_summary, stale_skip, current_completion])
            stale_payload["data"]["repository"]["pullRequest"]["commits"] = current_head_commit
            self._bind_trigger(Path(directory), stale_payload, self._trigger_record(old_head))
            stale_history = self._history(Path(directory), stale_payload, "hosted", changed_files=100)
            self.assertFalse(any(item.get("over_ceiling") for item in stale_history))

            stale_over_ceiling_payload = self._payload([old_summary, stale_skip, current_completion])
            stale_over_ceiling_payload["data"]["repository"]["pullRequest"]["commits"] = current_head_commit
            self._bind_trigger(Path(directory), stale_over_ceiling_payload, self._trigger_record(old_head))
            stale_over_ceiling_history = self._history(
                Path(directory), stale_over_ceiling_payload, "hosted", changed_files=121
            )
            self.assertFalse(any(item.get("over_ceiling") for item in stale_over_ceiling_history))

            current_skip = {**skip, "createdAt": "2026-09-23T00:06:00Z"}
            current_payload = self._payload([old_summary, current_skip])
            current_payload["data"]["repository"]["pullRequest"]["commits"] = current_head_commit
            current_record = self._trigger_record(HEAD)
            self._bind_trigger(Path(directory), current_payload, current_record)
            for changed_files in (100, 101):
                cli_history = self._history(Path(directory), current_payload, "cli", changed_files=changed_files)
                self.assertFalse(any(item.get("over_ceiling") for item in cli_history))
            within_ceiling_hosted = self._history(Path(directory), current_payload, "hosted", changed_files=100)
            self.assertFalse(any(item.get("over_ceiling") for item in within_ceiling_hosted))

            current_history = self._history(Path(directory), current_payload, "hosted", changed_files=121)
            self.assertTrue(any(item.get("over_ceiling") for item in current_history))

            later_completion = {
                "databaseId": 21,
                "author": {"login": "coderabbitai[bot]"},
                "body": (
                    f"<!-- walkthrough_start -->\nReviewing files that changed from the base of the PR and between "
                    f"`{BASE}` and `{HEAD}`."
                ),
                "createdAt": "2026-09-23T00:07:00Z",
            }
            completed_payload = self._payload([old_summary, current_skip, later_completion])
            completed_payload["data"]["repository"]["pullRequest"]["commits"] = current_head_commit
            self._bind_trigger(Path(directory), completed_payload, current_record)
            completed_history = self._history(Path(directory), completed_payload, "hosted", changed_files=121)
            self.assertFalse(any(item.get("over_ceiling") for item in completed_history))

    def test_provider_ceiling_skip_is_hosted_only_and_ignores_docstring_skips(self) -> None:
        provider_skip = {
            "databaseId": 40,
            "author": {"login": "coderabbitai[bot]"},
            "body": (
                "<!-- This is an auto-generated reply by CodeRabbit -->\n"
                "<!-- CodeRabbit review command invocation: v2:provider-id -->\n"
                "<details><summary>⚠️ Action not completed</summary>\n\n"
                "Review skipped: 121 files exceed the limit of 100.\n\n</details>"
            ),
            "createdAt": "2026-09-23T00:04:00Z",
        }
        docstring_skip = {
            **provider_skip,
            "databaseId": 41,
            "body": "Docstring Coverage: 31 skipped files over the file limit.",
        }
        with tempfile.TemporaryDirectory() as directory:
            provider_payload = self._payload([provider_skip])
            self._bind_trigger(Path(directory), provider_payload, self._trigger_record())
            hosted_history = self._history(Path(directory), provider_payload, "hosted", changed_files=121)
            cli_history = self._history(Path(directory), provider_payload, "cli", changed_files=121)
            docstring_payload = self._payload([docstring_skip])
            self._bind_trigger(Path(directory), docstring_payload, self._trigger_record())
            docstring_history = self._history(Path(directory), docstring_payload, "hosted", changed_files=121)

        self.assertTrue(any(item.get("over_ceiling") for item in hosted_history))
        self.assertFalse(any(item.get("over_ceiling") for item in cli_history))
        self.assertFalse(any(item.get("over_ceiling") for item in docstring_history))

    def test_summary_selector_uses_created_at_canonical_sections_and_rejects_ties(self) -> None:
        first = {
            "databaseId": 31,
            "author": {"login": "coderabbitai[bot]"},
            "body": (
                f"Reviewing files that changed from the base of the PR and between `{BASE}` and `{HEAD}`.\n"
                "### Outside diff range comments (1)"
            ),
            "createdAt": "2026-09-23T00:01:00Z",
            "updatedAt": "2026-09-23T00:03:00Z",
            "url": "https://example.test/31",
        }
        latest = {
            **first,
            "databaseId": 32,
            "body": (
                f"Reviewing files that changed from the base of the PR and between `{BASE}` and `{HEAD}`.\n"
                "## Duplicate comments (2)"
            ),
            "createdAt": "2026-09-23T00:02:00Z",
            "updatedAt": "2026-09-23T00:03:00Z",
            "url": "https://example.test/32",
        }
        edited_walkthrough = {
            "databaseId": 35,
            "author": {"login": "coderabbitai[bot]"},
            "body": (
                f"<!-- walkthrough_start -->\nReviewing files that changed from the base of the PR and between "
                f"`{BASE}` and `{HEAD}`.\nReviewing the changed files now."
            ),
            "createdAt": "2026-09-23T00:01:30Z",
            "updatedAt": "2026-09-23T00:05:00Z",
            "url": "https://example.test/35",
        }
        stale_head = {
            "databaseId": 33,
            "author": {"login": "coderabbitai[bot]"},
            "body": "<!-- walkthrough_start -->\nOutside diff range comments (9)",
            "state": "COMMENTED",
            "submittedAt": "2026-09-23T00:04:00Z",
            "commit": {"oid": BASE},
        }
        selected = LiveEvidence._summary_action_counts(
            self._payload([first, edited_walkthrough, latest], [stale_head]), HEAD
        )
        self.assertEqual(selected, (0, 2, "https://example.test/32"))

        tied = {**latest, "databaseId": 34, "url": "https://example.test/34"}
        ambiguous = LiveEvidence._summary_action_counts(self._payload([latest, tied]), HEAD)
        self.assertEqual(ambiguous, (1, 1, None))

    def test_persisted_summary_disposition_matches_status_and_preserves_thread_gate(self) -> None:
        review = {
            "databaseId": 77,
            "author": {"login": "coderabbitai[bot]"},
            "body": (
                f"Reviewing files that changed from the base of the PR and between `{BASE}` and `{HEAD}`.\n"
                "Outside diff range comments (1)"
            ),
            "state": "COMMENTED",
            "submittedAt": "2026-09-23T00:01:00Z",
            "commit": {"oid": HEAD},
        }
        payload = self._payload(reviews=[review], threads=[{"isResolved": False, "isOutdated": False}])
        rejected = SummaryFindingDisposition(
            42, HEAD, "review", 77, "outside_diff", 1, "rejected", "finding is outside this PR's candidate"
        )
        with tempfile.TemporaryDirectory() as directory:
            store = StateStore(Path(directory) / "state.json")
            store.save(ReviewState(summary_dispositions=(rejected,)))
            observer = LiveEvidence("owner/repo", LiveGitHub("owner/repo"), store)

            blockers = observer._global_blockers(42, HEAD, payload)

        self.assertFalse(any(item.get("checkpoint", "").startswith("summary-actions:") for item in blockers))
        self.assertTrue(any(item.get("checkpoint") == "review-threads:1:0" for item in blockers))

    def test_summary_disposition_command_requires_and_records_live_exact_identity(self) -> None:
        review = {
            "databaseId": 77,
            "author": {"login": "coderabbitai[bot]"},
            "body": (
                f"Reviewing files that changed from the base of the PR and between `{BASE}` and `{HEAD}`.\n"
                "Duplicate comments (1)"
            ),
            "state": "COMMENTED",
            "submittedAt": "2026-09-23T00:01:00Z",
            "commit": {"oid": HEAD},
        }
        payload = self._payload(reviews=[review])
        with tempfile.TemporaryDirectory() as directory:
            store = StateStore(Path(directory) / "state.json")
            controller = SimpleNamespace(repository="owner/repo", store=store)
            args = review_cli._parser().parse_args(
                [
                    "decide",
                    "summary-disposition",
                    "rejected",
                    "--pr",
                    "42",
                    "--head",
                    HEAD,
                    "--source",
                    "review",
                    "--summary-id",
                    "77",
                    "--kind",
                    "duplicate",
                    "--count",
                    "1",
                    "--reason",
                    "the duplicate annotation does not apply to this candidate",
                ]
            )
            with (
                patch.object(review_cli, "default_controller", return_value=controller),
                patch.object(github, "fetch_pull_request", return_value=payload),
            ):
                result, exit_status = review_cli._dispatch(args)

            self.assertEqual(exit_status, 0)
            self.assertEqual(result["status"], "recorded")
            self.assertEqual(len(store.load().summary_dispositions), 1)
            self.assertEqual(
                LiveEvidence._summary_action_counts(
                    payload,
                    HEAD,
                    pr=42,
                    dispositions=store.load().summary_dispositions,
                ),
                (0, 0, None),
            )

            args.summary_id = 78
            with (
                patch.object(review_cli, "default_controller", return_value=controller),
                patch.object(github, "fetch_pull_request", return_value=payload),
                self.assertRaisesRegex(review_cli.CliError, "exact summary identity is not attributable"),
            ):
                review_cli._dispatch(args)
            self.assertEqual(len(store.load().summary_dispositions), 1)

    def test_routed_summary_disposition_accepts_exact_prior_head_after_live_head_advances(self) -> None:
        live_head = "e" * 40
        review = {
            "databaseId": 77,
            "author": {"login": "coderabbitai[bot]"},
            "body": (
                f"Reviewing files that changed from the base of the PR and between `{BASE}` and `{HEAD}`.\n"
                "Duplicate comments (1)"
            ),
            "state": "COMMENTED",
            "submittedAt": "2026-09-23T00:01:00Z",
            "commit": {"oid": HEAD},
        }
        payload = self._payload(reviews=[review], head=live_head)
        with tempfile.TemporaryDirectory() as directory:
            store = StateStore(Path(directory) / "state.json")
            controller = SimpleNamespace(repository="owner/repo", store=store)
            args = review_cli._parser().parse_args(
                [
                    "decide",
                    "summary-disposition",
                    "routed",
                    "--pr",
                    "42",
                    "--head",
                    HEAD,
                    "--source",
                    "review",
                    "--summary-id",
                    "77",
                    "--kind",
                    "duplicate",
                    "--count",
                    "1",
                    "--reason",
                    "observation belongs to the Automation child PR",
                    "--target-pr",
                    "2879",
                    "--route-finding",
                    "automation-base-observation",
                    "--route-observation",
                    "WorkItem base observation belongs to Automation",
                ]
            )
            with (
                patch.object(review_cli, "default_controller", return_value=controller),
                patch.object(github, "fetch_pull_request", return_value=payload),
            ):
                result, exit_status = review_cli._dispatch(args)

            self.assertEqual(exit_status, 0)
            self.assertEqual(result["status"], "recorded")
            state = store.load()
            self.assertEqual(state.summary_dispositions[0].head, HEAD)
            self.assertEqual(state.summary_dispositions[0].decision, "routed")
            self.assertEqual(state.routes[0].target_pr, 2879)
            self.assertEqual(state.routes[0].source_review, "summary:review:77")
            first_route_id = state.routes[0].route_id

            review["body"] = review["body"].replace("Duplicate comments (1)", "Duplicate comments (2)")
            args.count = 2
            args.route_finding = ["automation-base-observation", "new-follow-up-finding"]
            args.route_observation = [
                "same source finding, with a later observation",
                "separate finding in the updated bucket",
            ]
            with (
                patch.object(review_cli, "default_controller", return_value=controller),
                patch.object(github, "fetch_pull_request", return_value=payload),
            ):
                repeated, exit_status = review_cli._dispatch(args)

            self.assertEqual(exit_status, 0)
            self.assertEqual(repeated["status"], "recorded")
            state = store.load()
            self.assertEqual([item.count for item in state.summary_dispositions], [1, 2])
            self.assertEqual(state.routes[0].route_id, first_route_id)
            self.assertEqual(
                state.routes[0].source_finding,
                "duplicate:ref:automation-base-observation",
            )
            self.assertEqual(len(state.routes[0].observations), 2)
            state_before_idempotent_repeat = store.load()
            with (
                patch.object(review_cli, "default_controller", return_value=controller),
                patch.object(github, "fetch_pull_request", return_value=payload),
            ):
                review_cli._dispatch(args)
            self.assertEqual(store.load(), state_before_idempotent_repeat)

            state_before_conflict = store.load()
            args.route_finding = ["automation-base-observation", "replacement-finding"]
            args.route_observation = [
                "would replace the source observation",
                "replacement route must not orphan the original",
            ]
            with (
                patch.object(review_cli, "default_controller", return_value=controller),
                patch.object(github, "fetch_pull_request", return_value=payload),
                self.assertRaisesRegex(review_cli.CliError, "retain its exact route IDs"),
            ):
                review_cli._dispatch(args)
            self.assertEqual(store.load(), state_before_conflict)

            args.target_pr = 2880
            args.route_finding = ["automation-base-observation", "new-follow-up-finding"]
            args.route_observation = [
                "same source finding, with a later observation",
                "separate finding in the updated bucket",
            ]
            with (
                patch.object(review_cli, "default_controller", return_value=controller),
                patch.object(github, "fetch_pull_request", return_value=payload),
                self.assertRaisesRegex(review_cli.CliError, "explicitly retargeted"),
            ):
                review_cli._dispatch(args)
            self.assertEqual(store.load(), state_before_conflict)
            args.target_pr = 2879

            # A wrong summary identity or head remains non-attributable.
            args.summary_id = 78
            with (
                patch.object(review_cli, "default_controller", return_value=controller),
                patch.object(github, "fetch_pull_request", return_value=payload),
                self.assertRaisesRegex(review_cli.CliError, "exact summary identity is not attributable"),
            ):
                review_cli._dispatch(args)
            args.summary_id = 77
            args.head = "f" * 40
            with (
                patch.object(review_cli, "default_controller", return_value=controller),
                patch.object(github, "fetch_pull_request", return_value=payload),
                self.assertRaisesRegex(review_cli.CliError, "exact summary identity is not attributable"),
            ):
                review_cli._dispatch(args)

            # The old-head exception applies only to routing, not adjudication.
            rejected_args = review_cli._parser().parse_args(
                [
                    "decide",
                    "summary-disposition",
                    "rejected",
                    "--pr",
                    "42",
                    "--head",
                    HEAD,
                    "--source",
                    "review",
                    "--summary-id",
                    "77",
                    "--kind",
                    "duplicate",
                    "--count",
                    "1",
                    "--reason",
                    "not a source-lane finding",
                ]
            )
            with (
                patch.object(review_cli, "default_controller", return_value=controller),
                patch.object(github, "fetch_pull_request", return_value=payload),
                self.assertRaisesRegex(review_cli.CliError, "require the live PR head"),
            ):
                review_cli._dispatch(rejected_args)
            self.assertEqual(len(store.load().summary_dispositions), 2)

    def test_accepted_fixed_summary_disposition_uses_hyphenated_cli_choice(self) -> None:
        corrected_head = "d" * 40
        review = {
            "databaseId": 78,
            "author": {"login": "coderabbitai[bot]"},
            "body": (
                f"Reviewing files that changed from the base of the PR and between `{BASE}` and `{HEAD}`.\n"
                "Outside diff range comments (1)"
            ),
            "state": "COMMENTED",
            "submittedAt": "2026-09-23T00:01:00Z",
            "commit": {"oid": HEAD},
        }
        payload = self._payload(reviews=[review], head=corrected_head)
        with tempfile.TemporaryDirectory() as directory:
            store = StateStore(Path(directory) / "state.json")
            controller = SimpleNamespace(repository="owner/repo", store=store)
            args = review_cli._parser().parse_args(
                [
                    "decide",
                    "summary-disposition",
                    "accepted-fixed",
                    "--pr",
                    "42",
                    "--head",
                    HEAD,
                    "--source",
                    "review",
                    "--summary-id",
                    "78",
                    "--kind",
                    "outside_diff",
                    "--count",
                    "1",
                    "--reason",
                    "fix was published on the corrected head",
                    "--corrected-head",
                    corrected_head,
                ]
            )
            with (
                patch.object(review_cli, "default_controller", return_value=controller),
                patch.object(github, "fetch_pull_request", return_value=payload),
            ):
                result, exit_status = review_cli._dispatch(args)

            self.assertEqual(exit_status, 0)
            self.assertEqual(result["disposition"]["decision"], "accepted_fixed")
            self.assertEqual(store.load().summary_dispositions[0].corrected_head, corrected_head)

            args.corrected_head = "e" * 40
            with (
                patch.object(review_cli, "default_controller", return_value=controller),
                patch.object(github, "fetch_pull_request", return_value=payload),
                self.assertRaisesRegex(review_cli.CliError, "--corrected-head must equal the live PR head"),
            ):
                review_cli._dispatch(args)
            self.assertEqual(store.load().summary_dispositions[0].corrected_head, corrected_head)


if __name__ == "__main__":
    unittest.main()
