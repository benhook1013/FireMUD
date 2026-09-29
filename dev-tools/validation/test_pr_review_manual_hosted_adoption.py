#!/usr/bin/env python3
"""A completed manual Hosted request can be audited without reposting it."""

from __future__ import annotations

import sys
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "dev-tools"))

from pr_review import cli, evidence, github, hosted, stack
from pr_review.cli_runner import PullRequestSnapshot
from pr_review.runtime import LiveEvidence, LiveGitHub

REPO = "owner/repo"
HEAD = "b" * 40
BASE = "a" * 40
ANCHOR = {
    "pr": 42,
    "child_head": HEAD,
    "parent_identity": "develop",
    "parent_head": BASE,
    "merge_base": BASE,
    "patch_id": "c" * 64,
}


def public_payload(
    *,
    head: str = HEAD,
    base_name: str = "develop",
    base_oid: str = BASE,
    extra_trigger: bool = False,
    review_head: str = HEAD,
):
    trigger = {
        "databaseId": 10,
        "author": {"login": "maintainer"},
        "body": hosted.FULL_COMMAND,
        "createdAt": "2026-09-28T10:08:38Z",
        "url": "https://example.test/comments/10",
    }
    comments = [trigger]
    if extra_trigger:
        comments.append({**trigger, "databaseId": 11, "url": "https://example.test/comments/11"})
    comments.append(
        {
            "databaseId": 12,
            "author": {"login": "maintainer"},
            "body": (
                f"Hosted: 2 found / 2 accepted / 0 routed · `{HEAD[:12]}` · 73 files · 10m 41s\n"
                "<!-- firemud-hosted-review: 55 -->\n"
                "<!-- firemud-review-duration-seconds: 641 -->"
            ),
            "createdAt": "2026-09-28T10:21:00Z",
        }
    )
    return {
        "data": {
            "repository": {
                "pullRequest": {
                    "headRefOid": head,
                    "baseRefName": base_name,
                    "baseRefOid": base_oid,
                    "comments": {"nodes": comments},
                    "reviews": {
                        "nodes": [
                            {
                                "databaseId": 55,
                                "author": {"login": "coderabbitai[bot]"},
                                "body": "<!-- walkthrough_start -->\n**Actionable comments posted:** 2",
                                "state": "COMMENTED",
                                "submittedAt": "2026-09-28T10:19:19Z",
                                "commit": {"oid": review_head},
                            }
                        ]
                    },
                    "reviewThreads": {"nodes": []},
                }
            }
        }
    }


def payload_fetcher(payload):
    return lambda: payload


def finished_only_payload(*, exact_head_proof: bool):
    payload = public_payload()
    pull = payload["data"]["repository"]["pullRequest"]
    pull["reviews"]["nodes"] = []
    pull["comments"]["nodes"] = [pull["comments"]["nodes"][0]]
    pull["comments"]["nodes"].append(
        {
            "databaseId": 13,
            "author": {"login": "coderabbitai[bot]"},
            "body": "Full review finished.",
            "createdAt": "2026-09-28T10:21:00Z",
            "updatedAt": "2026-09-28T10:21:00Z",
        }
    )
    if exact_head_proof:
        pull["comments"]["nodes"].append(
            {
                "databaseId": 14,
                "author": {"login": "coderabbitai[bot]"},
                "body": (
                    "No actionable comments were generated in the recent review.\n"
                    "Reviewing files that changed from the base of the PR and between "
                    f"{BASE} and {HEAD}."
                ),
                "createdAt": "2026-09-28T10:20:00Z",
                "updatedAt": "2026-09-28T10:20:00Z",
            }
        )
    return payload


class ManualHostedAdoptionTest(unittest.TestCase):
    def test_later_manual_request_cannot_claim_first_requests_late_reply(self):
        payload = public_payload()
        pull = payload["data"]["repository"]["pullRequest"]
        pull["comments"]["nodes"].insert(
            1,
            {
                "databaseId": 20,
                "author": {"login": "maintainer"},
                "body": hosted.FULL_COMMAND,
                "createdAt": "2026-09-28T10:10:00Z",
                "url": "https://example.test/comments/20",
            },
        )
        with tempfile.TemporaryDirectory() as directory:
            path = hosted.default_trigger_record_path(REPO, 42, Path(directory))
            with self.assertRaisesRegex(ValueError, "earlier full-review command is unresolved"):
                hosted.adopt_manual_completed_trigger(
                    REPO, 42, 20, HEAD, ANCHOR, payload_fetcher(payload), path=path
                )
            self.assertFalse(path.exists())

    def test_later_manual_request_can_be_adopted_after_untracked_terminal_review(self):
        payload = public_payload()
        pull = payload["data"]["repository"]["pullRequest"]
        first = pull["comments"]["nodes"][0]
        second = {**first, "databaseId": 20, "createdAt": "2026-09-28T10:10:00Z"}
        second["url"] = "https://example.test/comments/20"
        pull["comments"]["nodes"] = [first, second]
        pull["reviews"]["nodes"] = [
            {
                "databaseId": 55,
                "author": {"login": "coderabbitai[bot]"},
                "body": "<!-- walkthrough_start -->\n**Actionable comments posted:** 1",
                "state": "COMMENTED",
                "submittedAt": "2026-09-28T10:09:00Z",
                "commit": {"oid": HEAD},
            },
            {
                "databaseId": 56,
                "author": {"login": "coderabbitai[bot]"},
                "body": "<!-- walkthrough_start -->\n**Actionable comments posted:** 1",
                "state": "COMMENTED",
                "submittedAt": "2026-09-28T10:19:00Z",
                "commit": {"oid": HEAD},
            },
        ]

        with tempfile.TemporaryDirectory() as directory:
            common = Path(directory)
            adopted = hosted.adopt_manual_completed_trigger(
                REPO,
                42,
                20,
                HEAD,
                ANCHOR,
                payload_fetcher(payload),
                path=hosted.default_trigger_record_path(REPO, 42, common),
                common=common,
            )

        self.assertEqual(adopted["status"], "adopted")
        self.assertEqual(adopted["response_id"], 56)

    def test_later_manual_request_stays_blocked_when_older_summary_is_edited_in_window(self):
        payload = public_payload()
        pull = payload["data"]["repository"]["pullRequest"]
        first = pull["comments"]["nodes"][0]
        second = {**first, "databaseId": 20, "createdAt": "2026-09-28T10:10:00Z"}
        second["url"] = "https://example.test/comments/20"
        edited_summary = {
            "databaseId": 19,
            "author": {"login": "coderabbitai[bot]"},
            "body": (
                "<!-- This is an auto-generated comment: summarize by coderabbit.ai -->\n"
                f"Reviewing files that changed from the base of the PR and between {BASE} and {HEAD}."
            ),
            "createdAt": "2026-09-28T10:00:00Z",
            "updatedAt": "2026-09-28T10:09:30Z",
        }
        pull["comments"]["nodes"] = [first, edited_summary, second]
        pull["reviews"]["nodes"] = [
            {
                "databaseId": 55,
                "author": {"login": "coderabbitai[bot]"},
                "body": "<!-- walkthrough_start -->\n**Actionable comments posted:** 1",
                "state": "COMMENTED",
                "submittedAt": "2026-09-28T10:09:00Z",
                "commit": {"oid": HEAD},
            },
            {
                "databaseId": 56,
                "author": {"login": "coderabbitai[bot]"},
                "body": "<!-- walkthrough_start -->\n**Actionable comments posted:** 1",
                "state": "COMMENTED",
                "submittedAt": "2026-09-28T10:19:00Z",
                "commit": {"oid": HEAD},
            },
        ]

        with tempfile.TemporaryDirectory() as directory:
            common = Path(directory)
            path = hosted.default_trigger_record_path(REPO, 42, common)
            with self.assertRaisesRegex(ValueError, "earlier full-review command is unresolved"):
                hosted.adopt_manual_completed_trigger(
                    REPO, 42, 20, HEAD, ANCHOR, payload_fetcher(payload), path=path, common=common
                )
            self.assertFalse(path.exists())

    def test_later_manual_request_stays_blocked_when_new_summary_shares_terminal_window(self):
        payload = public_payload()
        pull = payload["data"]["repository"]["pullRequest"]
        first = pull["comments"]["nodes"][0]
        second = {**first, "databaseId": 20, "createdAt": "2026-09-28T10:10:00Z"}
        second["url"] = "https://example.test/comments/20"
        new_summary = {
            "databaseId": 19,
            "author": {"login": "coderabbitai[bot]"},
            "body": (
                "<!-- This is an auto-generated comment: summarize by coderabbit.ai -->\n"
                f"Reviewing files that changed from the base of the PR and between {BASE} and {HEAD}."
            ),
            "createdAt": "2026-09-28T10:09:00Z",
            "updatedAt": "2026-09-28T10:09:00Z",
        }
        terminal_reply = {
            "databaseId": 21,
            "author": {"login": "coderabbitai[bot]"},
            "body": "Review rate limited; next reviews available in 30 minutes.",
            "createdAt": "2026-09-28T10:09:30Z",
            "updatedAt": "2026-09-28T10:09:30Z",
        }
        pull["comments"]["nodes"] = [first, new_summary, terminal_reply, second]
        pull["reviews"]["nodes"] = [
            {
                "databaseId": 56,
                "author": {"login": "coderabbitai[bot]"},
                "body": "<!-- walkthrough_start -->\n**Actionable comments posted:** 1",
                "state": "COMMENTED",
                "submittedAt": "2026-09-28T10:19:00Z",
                "commit": {"oid": HEAD},
            }
        ]

        with tempfile.TemporaryDirectory() as directory:
            common = Path(directory)
            path = hosted.default_trigger_record_path(REPO, 42, common)
            with self.assertRaisesRegex(ValueError, "earlier full-review command is unresolved"):
                hosted.adopt_manual_completed_trigger(
                    REPO, 42, 20, HEAD, ANCHOR, payload_fetcher(payload), path=path, common=common
                )
            self.assertFalse(path.exists())

    def test_later_manual_request_stays_blocked_by_ambiguous_untracked_command(self):
        payload = public_payload()
        pull = payload["data"]["repository"]["pullRequest"]
        first = pull["comments"]["nodes"][0]
        second = {**first, "databaseId": 20, "createdAt": "2026-09-28T10:10:00Z"}
        second["url"] = "https://example.test/comments/20"
        ambiguous_finish = {
            "databaseId": 21,
            "author": {"login": "coderabbitai[bot]"},
            "body": "Full review finished.",
            "createdAt": "2026-09-28T10:09:00Z",
            "updatedAt": "2026-09-28T10:09:00Z",
        }
        pull["comments"]["nodes"] = [first, ambiguous_finish, second]
        pull["reviews"]["nodes"] = [
            {
                "databaseId": 56,
                "author": {"login": "coderabbitai[bot]"},
                "body": "<!-- walkthrough_start -->\n**Actionable comments posted:** 1",
                "state": "COMMENTED",
                "submittedAt": "2026-09-28T10:19:00Z",
                "commit": {"oid": HEAD},
            }
        ]

        with tempfile.TemporaryDirectory() as directory:
            common = Path(directory)
            path = hosted.default_trigger_record_path(REPO, 42, common)
            with self.assertRaisesRegex(ValueError, "earlier full-review command is unresolved"):
                hosted.adopt_manual_completed_trigger(
                    REPO, 42, 20, HEAD, ANCHOR, payload_fetcher(payload), path=path, common=common
                )
            self.assertFalse(path.exists())

    def test_cli_exposes_exact_manual_identity(self):
        args = cli._parser().parse_args(
            ["decide", "trigger-adopt-manual", "--pr", "42", "--trigger-id", "10", "--head", HEAD]
        )
        self.assertEqual(
            (args.decide_command, args.pr, args.trigger_id, args.head), ("trigger-adopt-manual", 42, 10, HEAD)
        )

    def test_cli_rejects_manual_adoption_in_acceptance_fixture_mode(self):
        args = cli._parser().parse_args(
            [
                "--acceptance-fixture", "fixture.json", "--state-path", "fixture-state.json",
                "decide", "trigger-adopt-manual", "--pr", "42", "--trigger-id", "10", "--head", HEAD,
            ]
        )
        with (
            patch.object(cli, "_controller", return_value=(object(), object())),
            self.assertRaisesRegex(cli.CliError, "live-state decisions are unavailable in acceptance fixture mode"),
        ):
            cli._dispatch(args)

    def test_completed_public_request_counts_after_audited_adoption(self):
        payload = public_payload()
        with tempfile.TemporaryDirectory() as directory:
            common = Path(directory)
            record_path = hosted.default_trigger_record_path(REPO, 42, common)
            with patch.object(evidence, "git_common_dir", return_value=common):
                adopted = hosted.adopt_manual_completed_trigger(
                    REPO, 42, 10, HEAD, ANCHOR, payload_fetcher(payload), path=record_path
                )
                self.assertEqual(adopted["response_id"], 55)
                with self.assertRaisesRegex(ValueError, "already has a durable record"):
                    hosted.adopt_manual_completed_trigger(
                        REPO, 42, 10, HEAD, ANCHOR, payload_fetcher(payload), path=record_path
                    )
                live = LiveGitHub(REPO)
                snapshot = PullRequestSnapshot(42, "OPEN", "develop", BASE, HEAD, "feature", 73)
                with (
                    patch.object(github, "fetch_pull_request", return_value=payload),
                    patch.object(live, "pull_request", return_value=snapshot),
                ):
                    history = LiveEvidence(REPO, live).history(42, "hosted")
            matched = [item for item in history if item.get("checkpoint") == "12"]
            self.assertEqual(len(matched), 1)
            self.assertTrue(matched[0]["completed"])
            self.assertTrue(matched[0]["anchored"])
            self.assertEqual(matched[0]["accepted"], 2)

    def test_numeric_parent_identity_does_not_have_to_match_live_base_branch_name(self):
        payload = public_payload(base_name="parent-feature")
        anchor = {**ANCHOR, "parent_identity": "41"}
        with tempfile.TemporaryDirectory() as directory:
            adopted = hosted.adopt_manual_completed_trigger(
                REPO,
                42,
                10,
                HEAD,
                anchor,
                payload_fetcher(payload),
                path=Path(directory) / "trigger.json",
            )

        self.assertEqual(adopted["status"], "adopted")

    def test_finished_reply_only_zero_after_trigger_requires_exact_public_reviewed_head_proof(self):
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaisesRegex(ValueError, "lacks exact public proof of the reviewed head"):
                hosted.adopt_manual_completed_trigger(
                    REPO,
                    42,
                    10,
                    HEAD,
                    ANCHOR,
                    payload_fetcher(finished_only_payload(exact_head_proof=False)),
                    path=Path(directory) / "without-proof.json",
                )

            adopted = hosted.adopt_manual_completed_trigger(
                REPO,
                42,
                10,
                HEAD,
                ANCHOR,
                payload_fetcher(finished_only_payload(exact_head_proof=True)),
                path=Path(directory) / "with-proof.json",
            )

        self.assertEqual(adopted["status"], "adopted")

    def test_malformed_existing_candidate_reports_path_and_refuses_adoption(self):
        with tempfile.TemporaryDirectory() as directory:
            common = Path(directory)
            candidate = (
                common
                / "coderabbit-review-logs"
                / "hosted"
                / "owner_repo"
                / "pr-42"
                / "trigger.json"
            )
            candidate.parent.mkdir(parents=True)
            candidate.write_text("{", encoding="utf-8")
            record_path = common / "adopted-trigger.json"

            with self.assertRaises(ValueError) as raised:
                hosted.adopt_manual_completed_trigger(
                    REPO, 42, 10, HEAD, ANCHOR, payload_fetcher(public_payload()), path=record_path, common=common
                )

            self.assertIn(str(candidate), str(raised.exception))
            self.assertFalse(record_path.exists())

    def test_custom_common_root_duplicate_is_refused_with_path_override(self):
        with tempfile.TemporaryDirectory() as directory:
            common = Path(directory)
            first_path = hosted.default_trigger_record_path(REPO, 42, common)
            second_path = common / "second-adoption.json"

            hosted.adopt_manual_completed_trigger(
                REPO, 42, 10, HEAD, ANCHOR, payload_fetcher(public_payload()), path=first_path, common=common
            )

            with self.assertRaisesRegex(ValueError, "already has a durable record"):
                hosted.adopt_manual_completed_trigger(
                    REPO, 42, 10, HEAD, ANCHOR, payload_fetcher(public_payload()), path=second_path, common=common
                )

            self.assertTrue(first_path.exists())
            self.assertFalse(second_path.exists())

    def test_canonical_path_override_derives_custom_common_root(self):
        with tempfile.TemporaryDirectory() as directory:
            common = Path(directory)
            first_path = hosted.default_trigger_record_path(REPO, 42, common)
            legacy_path = (
                common
                / "coderabbit-review-logs"
                / "hosted"
                / "owner_repo"
                / "pr-42"
                / "trigger.json"
            )

            hosted.adopt_manual_completed_trigger(
                REPO, 42, 10, HEAD, ANCHOR, payload_fetcher(public_payload()), path=first_path, common=common
            )

            with self.assertRaisesRegex(ValueError, "already has a durable record"):
                hosted.adopt_manual_completed_trigger(
                    REPO, 42, 10, HEAD, ANCHOR, payload_fetcher(public_payload()), path=legacy_path
                )

            self.assertTrue(first_path.exists())
            self.assertFalse(legacy_path.exists())

    def test_ambiguous_or_mismatched_public_identity_is_not_adopted(self):
        duplicated_review = public_payload()
        duplicated_review["data"]["repository"]["pullRequest"]["reviews"]["nodes"].append(
            {
                **duplicated_review["data"]["repository"]["pullRequest"]["reviews"]["nodes"][0],
                "databaseId": 56,
            }
        )
        rate_limited = public_payload()
        rate_limited_pr = rate_limited["data"]["repository"]["pullRequest"]
        rate_limited_pr["reviews"]["nodes"] = []
        rate_limited_pr["comments"]["nodes"].append(
            {
                "databaseId": 57,
                "author": {"login": "coderabbitai[bot]"},
                "body": "Review rate limited. Next reviews available in 30 minutes.",
                "createdAt": "2026-09-28T10:09:00Z",
            }
        )
        later_trigger = public_payload()
        later_trigger["data"]["repository"]["pullRequest"]["comments"]["nodes"].append(
            {
                "databaseId": 58,
                "author": {"login": "maintainer"},
                "body": hosted.FULL_COMMAND,
                "createdAt": "2026-09-28T10:30:00Z",
                "url": "https://example.test/comments/58",
            }
        )
        cases = (
            (public_payload(extra_trigger=True), "ambiguous"),
            (public_payload(review_head="d" * 40), "completed review"),
            (public_payload(head="e" * 40), "remain current"),
            (duplicated_review, "ambiguous"),
            (rate_limited, "rate_limited"),
            (later_trigger, "latest public"),
        )
        for payload, error in cases:
            with self.subTest(error=error), tempfile.TemporaryDirectory() as directory:
                path = Path(directory) / "trigger.json"
                with self.assertRaisesRegex(ValueError, error):
                    hosted.adopt_manual_completed_trigger(
                        REPO, 42, 10, HEAD, ANCHOR, payload_fetcher(payload), path=path
                    )
                self.assertFalse(path.exists())

    def test_cli_rejects_base_advanced_before_locked_manual_adoption_fetch(self):
        candidate = SimpleNamespace(head=HEAD, head_ref="feature", base_ref="develop", base_tip=BASE)
        anchor = SimpleNamespace(as_dict=lambda: ANCHOR)
        reconciliation = SimpleNamespace(
            status_for=lambda _pr: stack.ReconciliationStatus.COHERENT,
        )
        controller = SimpleNamespace(
            repository=REPO,
            store=SimpleNamespace(load=lambda: SimpleNamespace(ordered_prs=[42])),
            _reconciliation=lambda _state, evidence_prs: ({42: candidate}, reconciliation),
            _reconciled_anchor=lambda _pr, _candidate, _reconciliation: anchor,
        )
        stale_payloads = (
            public_payload(base_name="release"),
            public_payload(base_oid="d" * 40),
            public_payload(base_name=None),
            public_payload(base_oid=None),
        )

        for payload in stale_payloads:
            with (
                self.subTest(
                    base_name=payload["data"]["repository"]["pullRequest"].get("baseRefName"),
                    base_oid=payload["data"]["repository"]["pullRequest"].get("baseRefOid"),
                ),
                tempfile.TemporaryDirectory() as directory,
            ):
                common = Path(directory)
                record_path = hosted.default_trigger_record_path(REPO, 42, common)

                def fetch_after_lock(_repo, _pr, payload=payload, record_path=record_path):
                    self.assertTrue((record_path.parent / "request.lock").exists())
                    return payload

                args = cli._parser().parse_args(
                    ["decide", "trigger-adopt-manual", "--pr", "42", "--trigger-id", "10", "--head", HEAD]
                )
                with (
                    patch.object(cli, "_controller", return_value=(controller, None)),
                    patch.object(evidence, "git_common_dir", return_value=common),
                    patch.object(github, "fetch_pull_request", side_effect=fetch_after_lock),
                    self.assertRaisesRegex(ValueError, "live PR base to match its verified parent"),
                ):
                    cli._dispatch(args)

                self.assertFalse(record_path.exists())

    def test_cli_requires_coherent_reconciliation_and_live_branch_identity_before_anchor(self):
        args = cli._parser().parse_args(
            ["decide", "trigger-adopt-manual", "--pr", "42", "--trigger-id", "10", "--head", HEAD]
        )
        for candidate, reconciliation, message in (
            (
                SimpleNamespace(head=HEAD, head_ref="feature"),
                SimpleNamespace(status_for=lambda _pr: stack.ReconciliationStatus.PARENT_MOVED),
                "coherent live stack reconciliation",
            ),
            (
                SimpleNamespace(head=HEAD, head_ref=""),
                SimpleNamespace(status_for=lambda _pr: stack.ReconciliationStatus.COHERENT),
                "live PR branch identity",
            ),
        ):
            anchor_called = False

            def reconciled_anchor(_pr, _candidate, _reconciliation):
                nonlocal anchor_called
                anchor_called = True
                return SimpleNamespace(as_dict=lambda: ANCHOR)

            controller = SimpleNamespace(
                repository=REPO,
                store=SimpleNamespace(load=lambda: SimpleNamespace(ordered_prs=[42])),
                _reconciliation=lambda _state, evidence_prs, candidate=candidate, reconciliation=reconciliation: (
                    {42: candidate},
                    reconciliation,
                ),
                _reconciled_anchor=reconciled_anchor,
            )
            with (
                self.subTest(message=message),
                patch.object(cli, "_controller", return_value=(controller, None)),
                self.assertRaisesRegex(cli.CliError, message),
            ):
                cli._dispatch(args)
            self.assertFalse(anchor_called)


if __name__ == "__main__":
    unittest.main()
