#!/usr/bin/env python3
from __future__ import annotations

import importlib.util
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).with_name("check-coderabbit-review.py")
SPEC = importlib.util.spec_from_file_location("check_coderabbit_review", SCRIPT)
assert SPEC is not None and SPEC.loader is not None
CHECKER = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = CHECKER
SPEC.loader.exec_module(CHECKER)

REPO = "owner/repo"
PR = 42
HEAD = "a" * 40
TRIGGER_AT = "2026-09-14T01:00:00Z"


def comment(
    item_id: int,
    author: str,
    body: str,
    created_at: str,
    *,
    url: str | None = None,
    updated_at: str | None = None,
) -> dict[str, object]:
    return {
        "databaseId": item_id,
        "author": {"login": author},
        "body": body,
        "createdAt": created_at,
        "updatedAt": updated_at or created_at,
        "url": url or f"https://example.test/comments/{item_id}",
    }


def trigger_comment() -> dict[str, object]:
    return comment(10, "owner", "@coderabbitai full review", TRIGGER_AT)


def finished_reply() -> dict[str, object]:
    return comment(
        11,
        "coderabbitai",
        """<!-- This is an auto-generated reply by CodeRabbit -->
<!-- CodeRabbit review command invocation: v2:example -->
<details>
<summary>✅ Action performed</summary>

Full review finished.

</details>""",
        "2026-09-14T01:00:06Z",
        updated_at="2026-09-14T01:06:00Z",
    )


def zero_finding_summary(head: str = HEAD) -> dict[str, object]:
    return comment(
        12,
        "coderabbitai",
        f"""<!-- recent_review_start -->
No actionable comments were generated in the recent review. 🎉
Reviewing files that changed from the base of the PR and between {'b' * 40} and {head}.
Files selected for processing (24)
<!-- recent_review_end -->
<!-- walkthrough_start -->""",
        "2026-09-13T00:00:00Z",
        updated_at="2026-09-14T01:05:00Z",
    )


def payload(
    comments: list[dict[str, object]] | None = None,
    reviews: list[dict[str, object]] | None = None,
) -> dict[str, object]:
    return {
        "data": {
            "repository": {
                "pullRequest": {
                    "headRefOid": HEAD,
                    "commits": {
                        "nodes": [
                            {
                                "commit": {
                                    "oid": HEAD,
                                    "committedDate": "2026-09-14T00:00:00Z",
                                }
                            }
                        ]
                    },
                    "reviewThreads": {"nodes": []},
                    "comments": {
                        "nodes": comments
                        if comments is not None
                        else [trigger_comment()]
                    },
                    "reviews": {"nodes": reviews or []},
                }
            }
        }
    }


def record() -> dict[str, object]:
    return {
        "schema_version": 1,
        "status": "posted",
        "repository": REPO,
        "pr_number": PR,
        "head_sha": HEAD,
        "trigger": {
            "id": 10,
            "created_at": TRIGGER_AT,
            "url": "https://example.test/comments/10",
            "type": "full",
            "command": "@coderabbitai full review",
        },
    }


class TriggerStateTests(unittest.TestCase):
    def state(
        self,
        comments: list[dict[str, object]] | None = None,
        reviews: list[dict[str, object]] | None = None,
        trigger_record: dict[str, object] | None = None,
    ) -> object:
        return CHECKER.trigger_state(
            REPO, PR, payload(comments, reviews), trigger_record or record()
        )

    def test_awaiting_response_ignores_old_same_time_pending_and_edited_timestamps(
        self,
    ) -> None:
        comments = [
            comment(
                1, "coderabbitai", "<!-- walkthrough_start -->", "2026-09-14T00:59:00Z"
            ),
            trigger_comment(),
            comment(11, "coderabbitai", "Full review triggered", TRIGGER_AT),
            comment(12, "coderabbitai", "Review pending", "2026-09-14T01:00:01Z"),
            comment(
                13,
                "coderabbitai",
                "Review rate limited",
                "2026-09-14T00:59:30Z",
                updated_at="2026-09-14T01:05:00Z",
            ),
        ]
        state = self.state(comments)
        self.assertEqual(state.state, "awaiting_response")
        self.assertFalse(state.terminal)
        self.assertTrue(state.attributed)

    def test_active_ack_is_nonterminal(self) -> None:
        state = self.state(
            [
                trigger_comment(),
                comment(
                    11, "coderabbitai", "Full review triggered", "2026-09-14T01:00:01Z"
                ),
            ]
        )
        self.assertEqual(
            (state.state, state.terminal, state.response_id), ("active", False, 11)
        )

    def test_terminal_comment_outcomes(self) -> None:
        cases = {
            "failed": "The full review failed because an internal error occurred.",
            "noop": "Review finished. CodeRabbit does not re-review already reviewed commits.",
            "rate_limited": "Review rate limited\nMore reviews will be available in 57 minutes.",
        }
        for expected, body in cases.items():
            with self.subTest(expected=expected):
                state = self.state(
                    [
                        trigger_comment(),
                        comment(11, "coderabbitai", body, "2026-09-14T01:00:01Z"),
                    ]
                )
                self.assertEqual(state.state, expected)
                self.assertTrue(state.terminal)
                self.assertTrue(state.attributed)
                if expected == "rate_limited":
                    self.assertEqual(state.cooldown_until, "2026-09-14T01:57:01+00:00")

    def test_rate_limit_after_generated_reply_marker_is_terminal(self) -> None:
        body = """<!-- This is an auto-generated reply by CodeRabbit -->
<!-- CodeRabbit review command invocation: v2:example -->
<details>
<summary>⚠️ Action not completed</summary>

Review rate limited.

Your next included review will be available in 39 minutes.
</details>"""
        state = self.state(
            [
                trigger_comment(),
                comment(11, "coderabbitai", body, "2026-09-14T01:00:07Z"),
            ]
        )
        self.assertEqual(state.state, "rate_limited")
        self.assertTrue(state.terminal)
        self.assertTrue(state.attributed)
        self.assertEqual(state.cooldown_until, "2026-09-14T01:39:07+00:00")

    def test_rate_limit_reply_with_seconds_window_is_terminal(self) -> None:
        body = """<!-- This is an auto-generated reply by CodeRabbit -->
<!-- CodeRabbit review command invocation: v2:c736cf9d8435ec67afe240228552056b7fa78ca9fbb13f4b52a32d90e181670e -->
<details>
<summary>⚠️ Action not completed</summary>

Review rate limited.

---

Your included review limit is currently reached under our [Fair Usage Limits Policy](https://docs.coderabbit.ai/management/plans#fair-usage-limits-policy). This review may still proceed through usage-based billing if eligible. Your next included review will be available in 19 seconds.

</details>"""
        state = self.state(
            [
                trigger_comment(),
                comment(11, "coderabbitai", body, "2026-09-14T01:00:07Z"),
            ]
        )
        self.assertEqual(state.state, "rate_limited")
        self.assertTrue(state.terminal)
        self.assertTrue(state.attributed)
        self.assertEqual(state.cooldown_until, "2026-09-14T01:00:26+00:00")

    def test_finished_reply_uses_exact_head_zero_finding_summary(self) -> None:
        comments = [trigger_comment(), finished_reply(), zero_finding_summary()]
        state = self.state(comments)
        self.assertEqual(state.state, "completed")
        self.assertTrue(state.terminal)
        self.assertTrue(state.attributed)
        self.assertEqual(state.response_id, 11)

        summary = CHECKER.summarize(REPO, PR, payload(comments))
        self.assertTrue(summary.ok)
        self.assertTrue(summary.review_finished_after_latest_request)
        self.assertTrue(summary.substantive_review_after_latest_commit)

    def test_finished_reply_without_matching_zero_finding_summary_keeps_waiting(
        self,
    ) -> None:
        for summary_comment in (None, zero_finding_summary("c" * 40)):
            with self.subTest(summary=summary_comment is not None):
                comments = [trigger_comment(), finished_reply()]
                if summary_comment is not None:
                    comments.append(summary_comment)
                state = self.state(comments)
                self.assertEqual(state.state, "awaiting_response")
                self.assertFalse(state.terminal)

    def test_empty_rate_limit_snapshot_does_not_qualify(self) -> None:
        state = self.state(
            [
                trigger_comment(),
                comment(
                    11, "coderabbitai", "Review rate limited", "2026-09-14T01:00:01Z"
                ),
            ]
        )
        self.assertEqual(state.state, "awaiting_response")

    def test_completed_comment_must_match_captured_head(self) -> None:
        template = "<!-- walkthrough_start -->\nReviewing files that changed from the base of the PR and between `{base}` and `{head}`\nFiles selected for processing (2)"
        matching = template.format(base="b" * 40, head=HEAD)
        mismatch = template.format(base="b" * 40, head="c" * 40)
        self.assertEqual(
            self.state(
                [
                    trigger_comment(),
                    comment(20, "coderabbitai", matching, "2026-09-14T01:02:00Z"),
                ]
            ).state,
            "completed",
        )
        state = self.state(
            [
                trigger_comment(),
                comment(21, "coderabbitai", mismatch, "2026-09-14T01:02:00Z"),
            ]
        )
        self.assertEqual((state.state, state.attributed), ("ambiguous", False))

    def test_submitted_review_id_and_commit_are_authoritative(self) -> None:
        review = {
            "databaseId": 55,
            "author": {"login": "coderabbitai"},
            "body": "<!-- walkthrough_start -->",
            "state": "COMMENTED",
            "submittedAt": "2026-09-14T01:03:00Z",
            "url": "https://example.test/reviews/55",
            "commit": {"oid": HEAD},
        }
        state = self.state(reviews=[review])
        self.assertEqual((state.state, state.response_id), ("completed", 55))

    def test_interleaved_and_same_time_triggers_are_ambiguous(self) -> None:
        for created_at in (TRIGGER_AT, "2026-09-14T01:00:01Z"):
            with self.subTest(created_at=created_at):
                state = self.state(
                    [
                        trigger_comment(),
                        comment(12, "other", "@coderabbitai full review", created_at),
                    ]
                )
                self.assertEqual((state.state, state.attributed), ("ambiguous", False))

    def test_later_trigger_does_not_invalidate_completed_bounded_response(self) -> None:
        body = f"<!-- walkthrough_start -->\nReviewing files that changed from the base of the PR and between {'b' * 40} and {HEAD}\nFiles selected for processing (2)"
        state = self.state(
            [
                trigger_comment(),
                comment(11, "coderabbitai", body, "2026-09-14T01:00:01Z"),
                comment(
                    12,
                    "other",
                    "@coderabbitai full review",
                    "2026-09-14T01:00:02Z",
                ),
                comment(
                    13,
                    "coderabbitai",
                    "The full review failed.",
                    "2026-09-14T01:00:03Z",
                ),
            ]
        )
        self.assertEqual((state.state, state.response_id), ("completed", 11))

    def test_late_response_to_unfinished_prior_trigger_is_ambiguous(self) -> None:
        comments = [
            comment(8, "owner", "@coderabbitai full review", "2026-09-14T00:58:00Z"),
            comment(9, "coderabbitai", "Full review triggered", "2026-09-14T00:58:01Z"),
            trigger_comment(),
            comment(
                11, "coderabbitai", "The full review failed.", "2026-09-14T01:00:01Z"
            ),
        ]
        self.assertEqual(self.state(comments).state, "ambiguous")

    def test_prior_completed_trigger_does_not_poison_attribution(self) -> None:
        prior_body = f"<!-- walkthrough_start -->\nReviewing files that changed from the base of the PR and between {'b' * 40} and {HEAD}\nFiles selected for processing (1)"
        comments = [
            comment(7, "owner", "@coderabbitai full review", "2026-09-14T00:57:00Z"),
            comment(8, "coderabbitai", prior_body, "2026-09-14T00:58:00Z"),
            trigger_comment(),
            comment(
                11, "coderabbitai", "Full review triggered", "2026-09-14T01:00:01Z"
            ),
        ]
        self.assertEqual(self.state(comments).state, "active")

    def test_missing_trigger_and_posting_reservation_are_unattributed(self) -> None:
        missing = self.state([])
        self.assertEqual((missing.state, missing.attributed), ("unattributed", False))
        posting = record()
        posting.pop("trigger")
        state = self.state(trigger_record=posting)
        self.assertEqual((state.state, state.terminal), ("unattributed", True))

    def test_changed_posting_boundary_is_ambiguous(self) -> None:
        changed = record()
        changed["status"] = "posted_boundary_changed"
        state = self.state(trigger_record=changed)
        self.assertEqual((state.state, state.attributed), ("ambiguous", False))

    def test_same_time_terminal_responses_are_ambiguous(self) -> None:
        state = self.state(
            [
                trigger_comment(),
                comment(
                    11,
                    "coderabbitai",
                    "The full review failed.",
                    "2026-09-14T01:00:01Z",
                ),
                comment(
                    12,
                    "coderabbitai",
                    "More reviews will be available in 5 minutes.",
                    "2026-09-14T01:00:01Z",
                ),
            ]
        )
        self.assertEqual(state.state, "ambiguous")

    def test_json_shape_and_meaningful_timeout_are_independent_from_merge_ok(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            payload_path = root / "payload.json"
            record_path = root / "record.json"
            payload_path.write_text(json.dumps(payload()), encoding="utf-8")
            record_path.write_text(json.dumps(record()), encoding="utf-8")
            completed = subprocess.run(
                [
                    sys.executable,
                    str(SCRIPT),
                    "--repo",
                    REPO,
                    "--pr",
                    str(PR),
                    "--input",
                    str(payload_path),
                    "--trigger-record",
                    str(record_path),
                    "--wait",
                    "--timeout",
                    "0",
                    "--json",
                ],
                check=False,
                capture_output=True,
                text=True,
            )
        output = json.loads(completed.stdout)
        self.assertEqual(completed.returncode, 1)
        self.assertFalse(output["ok"])
        self.assertEqual(output["trigger_state"]["state"], "timed_out")
        self.assertTrue(output["trigger_state"]["terminal"])
        self.assertTrue(output["trigger_state"]["attributed"])

    def test_direct_wait_rejects_nonfinite_and_unbounded_values_before_loading(self) -> None:
        for option, value in (
            ("--timeout", "inf"),
            ("--timeout", "86401"),
            ("--poll-interval", "nan"),
            ("--poll-interval", "301"),
        ):
            with self.subTest(option=option, value=value):
                completed = subprocess.run(
                    [
                        sys.executable,
                        str(SCRIPT),
                        "--repo",
                        REPO,
                        "--pr",
                        str(PR),
                        "--trigger-record",
                        "/does/not/exist",
                        "--wait",
                        option,
                        value,
                    ],
                    check=False,
                    capture_output=True,
                    text=True,
                )
                self.assertEqual(completed.returncode, 2)
                self.assertIn("invalid bounded wait values", completed.stderr)


if __name__ == "__main__":
    unittest.main()
