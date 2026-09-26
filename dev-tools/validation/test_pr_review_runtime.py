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
from typing import Any
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "dev-tools"))

from pr_review import cli as review_cli
from pr_review import evidence, github, hosted
from pr_review.cli_runner import EffectiveParent, PullRequestSnapshot, ReviewRunnerError, ReviewTarget
from pr_review.controller import ControllerError, StaleReviewTarget
from pr_review.runtime import HostedRunner, LiveEvidence, LiveGitHub, default_controller
from pr_review.state import ReviewState, StateStore, SummaryFindingDisposition, observation_fingerprint

BASE = "a" * 40
HEAD = "b" * 40
PATCH = "c" * 64


class RuntimeTest(unittest.TestCase):
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
        ambiguous_comment = {
            "databaseId": 73,
            "body": "**Actionable comments posted:** 1",
            "createdAt": "2026-09-24T00:00:00Z",
        }
        self.assertEqual(LiveEvidence._public_response_state(review, "submittedAt", {}), "completed")
        self.assertEqual(LiveEvidence._public_response_state(active_comment, "createdAt", {}), "active")
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
            "body": (
                f"Hosted: 0 found / 0 accepted · `{HEAD[:12]}` · 1 files\n"
                "<!-- firemud-hosted-review: 55 -->"
            ),
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
                    side_effect=lambda path, *_: selected_record
                    if Path(path).name == "trigger-10.json"
                    else records_by_path[Path(path).name],
                ),
                patch.object(
                    hosted,
                    "trigger_state",
                    side_effect=lambda _repo, _pr, _payload, selected, _path: selected_state
                    if selected["trigger"]["id"] == 10
                    else states_by_trigger[selected["trigger"]["id"]],
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
        post_timeout: list[float] = []

        def gh_call(args, **kwargs):
            if args == ["gh", "api", "user"]:
                output = {"login": "maintainer"}
            else:
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
                output = comment
            return CompletedProcess(args, 0, json.dumps(output), "")

        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "trigger.json"
            with (
                patch.object(live, "pull_request", return_value=snapshot),
                patch.object(live, "branch_head", return_value=BASE),
                patch.object(github, "fetch_pull_request", return_value=self._payload()),
                patch.object(hosted, "default_trigger_record_path", return_value=path),
                patch.object(evidence, "git_common_dir", return_value=Path(directory)),
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
            self.assertEqual(record["posting_comment_id_floor"], 0)
            self.assertEqual(post_timeout, [github.GH_API_TIMEOUT_SECONDS])

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

    def _history(self, common: Path, payload, channel="hosted", *, changed_files=1, current_head=HEAD):
        snapshot = PullRequestSnapshot(42, "OPEN", "develop", BASE, current_head, "feature", changed_files)
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
        self.assertEqual(audit["terminal_rate_limits"], [
            {
                "trigger_id": 10,
                "response_id": 11,
                "captured_head": old_head,
                "cooldown_until": cooldown_until,
                "terminal": True,
                "attributable": True,
            }
        ])
        self.assertIn("active Hosted reservation: review active", audit["blockers"])

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
                record_path.write_text(
                    json.dumps(self._trigger_record(created=trigger_at)), encoding="utf-8"
                )
                return self._history(common, self._payload(comments, reviews, threads))

        valid = history_for([trigger, summary, reply, checkpoint])
        self.assertTrue(any(item.get("checkpoint") == "13" and item.get("completed") for item in valid))

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
        provider_valid = history_for([trigger, provider_summary, edited_reply, checkpoint])
        self.assertTrue(any(item.get("checkpoint") == "13" and item.get("completed") for item in provider_valid))

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
                rejected = history_for([trigger, invalid_summary, edited_reply, checkpoint])
                self.assertFalse(any(item.get("checkpoint") == "13" and item.get("completed") for item in rejected))

        inline_comment = {
            "databaseId": 54,
            "author": {"login": "coderabbitai[bot]"},
            "body": "A post-trigger inline finding.",
            "createdAt": "2026-09-23T00:02:30Z",
            "updatedAt": "2026-09-23T00:02:30Z",
        }
        with_inline_output = history_for(
            [trigger, provider_summary, edited_reply, checkpoint],
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
        with_issue_output = history_for([trigger, provider_summary, post_trigger_comment, edited_reply, checkpoint])
        self.assertFalse(any(item.get("checkpoint") == "13" and item.get("completed") for item in with_issue_output))

        mismatched_summary = {**summary, "body": summary["body"].replace(HEAD, "d" * 40)}
        missing_summary = [trigger, reply, checkpoint]
        for invalid_comments in (
            [trigger, mismatched_summary, reply, checkpoint],
            missing_summary,
            [trigger, summary, {**reply, "author": {"login": "other-user"}}, checkpoint],
            [trigger, summary, {**reply, "body": "Review rate limited; next reviews available in 30 minutes"}, checkpoint],
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
            "body": checkpoint["body"].replace("120s", "121s").replace("seconds: 120", "seconds: 121"),
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
            record_path.write_text(
                json.dumps(self._trigger_record(created=trigger_at)), encoding="utf-8"
            )
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
        self.assertIn("completed Hosted response has no checkpoint or prior audit", history_alone["unmatched_responses"])

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
            "body": (
                f"Hosted: 1 found / 1 accepted · `{HEAD}` · 1 files\n"
                "<!-- firemud-hosted-review: 71 -->"
            ),
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
            hosted_history = self._history(Path(directory), payload, "hosted", changed_files=101)
            cli_history = self._history(Path(directory), payload, "cli", changed_files=101)
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
            stale_history = self._history(Path(directory), stale_payload, "cli", changed_files=100)
            self.assertFalse(any(item.get("over_ceiling") for item in stale_history))

            stale_over_ceiling_payload = self._payload([old_summary, stale_skip, current_completion])
            stale_over_ceiling_payload["data"]["repository"]["pullRequest"]["commits"] = current_head_commit
            stale_over_ceiling_history = self._history(
                Path(directory), stale_over_ceiling_payload, "cli", changed_files=101
            )
            self.assertFalse(any(item.get("over_ceiling") for item in stale_over_ceiling_history))

            current_skip = {**skip, "createdAt": "2026-09-23T00:06:00Z"}
            current_payload = self._payload([old_summary, current_skip])
            current_payload["data"]["repository"]["pullRequest"]["commits"] = current_head_commit
            current_history = self._history(Path(directory), current_payload, "cli", changed_files=101)
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
            completed_history = self._history(Path(directory), completed_payload, "cli", changed_files=101)
            self.assertFalse(any(item.get("over_ceiling") for item in completed_history))

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
