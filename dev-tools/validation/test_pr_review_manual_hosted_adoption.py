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

from pr_review import cli, evidence, github, hosted
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


class ManualHostedAdoptionTest(unittest.TestCase):
    def test_cli_exposes_exact_manual_identity(self):
        args = cli._parser().parse_args(
            ["decide", "trigger-adopt-manual", "--pr", "42", "--trigger-id", "10", "--head", HEAD]
        )
        self.assertEqual(
            (args.decide_command, args.pr, args.trigger_id, args.head), ("trigger-adopt-manual", 42, 10, HEAD)
        )

    def test_completed_public_request_counts_after_audited_adoption(self):
        payload = public_payload()
        with tempfile.TemporaryDirectory() as directory:
            common = Path(directory)
            record_path = hosted.default_trigger_record_path(REPO, 42, common)
            with patch.object(evidence, "git_common_dir", return_value=common):
                adopted = hosted.adopt_manual_completed_trigger(REPO, 42, 10, HEAD, ANCHOR, payload, path=record_path)
                self.assertEqual(adopted["response_id"], 55)
                with self.assertRaisesRegex(ValueError, "already has a durable record"):
                    hosted.adopt_manual_completed_trigger(REPO, 42, 10, HEAD, ANCHOR, payload, path=record_path)
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
                    REPO, 42, 10, HEAD, ANCHOR, public_payload(), path=record_path, common=common
                )

            self.assertIn(str(candidate), str(raised.exception))
            self.assertFalse(record_path.exists())

    def test_custom_common_root_duplicate_is_refused_with_path_override(self):
        with tempfile.TemporaryDirectory() as directory:
            common = Path(directory)
            first_path = hosted.default_trigger_record_path(REPO, 42, common)
            second_path = common / "second-adoption.json"

            hosted.adopt_manual_completed_trigger(
                REPO, 42, 10, HEAD, ANCHOR, public_payload(), path=first_path, common=common
            )

            with self.assertRaisesRegex(ValueError, "already has a durable record"):
                hosted.adopt_manual_completed_trigger(
                    REPO, 42, 10, HEAD, ANCHOR, public_payload(), path=second_path, common=common
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
                REPO, 42, 10, HEAD, ANCHOR, public_payload(), path=first_path, common=common
            )

            with self.assertRaisesRegex(ValueError, "already has a durable record"):
                hosted.adopt_manual_completed_trigger(
                    REPO, 42, 10, HEAD, ANCHOR, public_payload(), path=legacy_path
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
                    hosted.adopt_manual_completed_trigger(REPO, 42, 10, HEAD, ANCHOR, payload, path=path)
                self.assertFalse(path.exists())

    def test_cli_refuses_live_base_that_differs_from_reconciled_parent(self):
        candidate = SimpleNamespace(head=HEAD)
        anchor = SimpleNamespace(as_dict=lambda: ANCHOR)
        controller = SimpleNamespace(
            repository=REPO,
            store=SimpleNamespace(load=lambda: SimpleNamespace(ordered_prs=[42])),
            _reconciliation=lambda _state, evidence_prs: ({42: candidate}, object()),
            _reconciled_anchor=lambda _pr, _candidate, _reconciliation: anchor,
        )
        stale_payloads = (
            public_payload(base_name="release"),
            public_payload(base_oid="d" * 40),
            public_payload(base_name=None),
            public_payload(base_oid=None),
        )

        for payload in stale_payloads:
            with self.subTest(base_name=payload["data"]["repository"]["pullRequest"].get("baseRefName"),
                              base_oid=payload["data"]["repository"]["pullRequest"].get("baseRefOid")):
                args = cli._parser().parse_args(
                    ["decide", "trigger-adopt-manual", "--pr", "42", "--trigger-id", "10", "--head", HEAD]
                )
                with (
                    patch.object(cli, "_controller", return_value=(controller, None)),
                    patch.object(github, "fetch_pull_request", return_value=payload),
                    patch.object(hosted, "adopt_manual_completed_trigger") as adopt,
                    self.assertRaisesRegex(cli.CliError, "live PR base to match its verified parent"),
                ):
                    cli._dispatch(args)

                adopt.assert_not_called()


if __name__ == "__main__":
    unittest.main()
