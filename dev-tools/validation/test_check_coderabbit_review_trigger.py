#!/usr/bin/env python3
from __future__ import annotations

import importlib.util
import json
import os
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
FRESH_HEAD = "c" * 40
FRESH_TRIGGER_AT = "2026-09-14T02:00:00Z"


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


def fresh_trigger_comment() -> dict[str, object]:
    return comment(20, "owner", "@coderabbitai full review", FRESH_TRIGGER_AT)


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
    *,
    head: str = HEAD,
) -> dict[str, object]:
    return {
        "data": {
            "repository": {
                "pullRequest": {
                    "headRefOid": head,
                    "commits": {
                        "nodes": [
                            {
                                "commit": {
                                    "oid": head,
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


def timed_out_record() -> dict[str, object]:
    timed_out = record()
    timed_out["status"] = "timed_out"
    timed_out["timeout"] = {
        "at": "2026-09-14T01:30:00Z",
        "observed_state": "awaiting_response",
        "observed_response_id": None,
        "reason": "bounded wait expired before a terminal CodeRabbit response",
    }
    return timed_out


def fresh_record() -> dict[str, object]:
    current = json.loads(json.dumps(record()))
    current["trigger"] = {
        "id": 20,
        "created_at": FRESH_TRIGGER_AT,
        "url": "https://example.test/comments/20",
        "type": "full",
        "command": "@coderabbitai full review",
    }
    return current


def retired_archive_record(
    trigger_record: dict[str, object] | None = None,
    *,
    evidence_state: str = "active",
    expected_head: str = HEAD,
) -> dict[str, object]:
    archived = json.loads(json.dumps(trigger_record or record()))
    trigger = archived["trigger"]
    archived["status"] = "retired"
    archived["retirement"] = {
        "action": "operator_retire",
        "retired_at": "2026-09-14T01:30:00Z",
        "reason": "bounded wait timed out; no current review evidence",
        "trigger_comment_id": trigger["id"],
        "expected_head_sha": expected_head,
        "evidence": {
            "state": evidence_state,
            "captured_head_sha": archived["head_sha"],
            "current_head_sha": expected_head,
        },
    }
    return archived


class TriggerStateTests(unittest.TestCase):
    def state(
        self,
        comments: list[dict[str, object]] | None = None,
        reviews: list[dict[str, object]] | None = None,
        trigger_record: dict[str, object] | None = None,
        trigger_record_path: str | Path | None = None,
    ) -> object:
        return CHECKER.trigger_state(
            REPO,
            PR,
            payload(comments, reviews),
            trigger_record or record(),
            trigger_record_path,
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

    def test_outside_the_diff_review_layout_is_terminal(self) -> None:
        review = {
            "databaseId": 55,
            "author": {"login": "coderabbitai"},
            "body": f"""**⚠️ Outside the diff (1)**

Reviewing files that changed from the base of the PR and between {'b' * 40} and {HEAD}.
Files selected for processing (20)""",
            "state": "COMMENTED",
            "submittedAt": "2026-09-14T01:03:00Z",
            "url": "https://example.test/reviews/55",
            "commit": {"oid": HEAD},
        }
        comments = [trigger_comment(), finished_reply()]
        state = self.state(comments, [review])
        self.assertEqual((state.state, state.response_id), ("completed", 55))

        summary = CHECKER.summarize(REPO, PR, payload(comments, [review]))
        self.assertTrue(summary.review_finished_after_latest_request)
        self.assertTrue(summary.substantive_review_after_latest_commit)
        self.assertEqual(summary.outside_diff_actionable_comments, 1)
        self.assertFalse(summary.ok)

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

    def test_prior_zero_finding_trigger_does_not_poison_rate_limit_attribution(
        self,
    ) -> None:
        prior_finished = finished_reply()
        prior_finished["databaseId"] = 8
        prior_finished["createdAt"] = "2026-09-14T00:58:00Z"
        prior_finished["updatedAt"] = "2026-09-14T00:58:00Z"
        comments = [
            comment(7, "owner", "@coderabbitai full review", "2026-09-14T00:57:00Z"),
            prior_finished,
            trigger_comment(),
            comment(
                11,
                "coderabbitai",
                "Review rate limited\nMore reviews will be available in 5 minutes.",
                "2026-09-14T01:00:01Z",
            ),
        ]
        state = self.state(comments)
        self.assertEqual((state.state, state.response_id), ("rate_limited", 11))
        self.assertTrue(state.attributed)

    def test_validated_retired_predecessor_allows_fresh_active_trigger_attribution_and_retirement(
        self,
    ) -> None:
        current_record = fresh_record()
        comments = [
            trigger_comment(),
            fresh_trigger_comment(),
            comment(
                21,
                "coderabbitai",
                "Full review triggered",
                "2026-09-14T02:00:01Z",
            ),
        ]
        current_payload = payload(comments, head=FRESH_HEAD)
        current_payload["data"]["repository"]["pullRequest"]["commits"]["nodes"][0][
            "commit"
        ]["committedDate"] = "2026-09-14T03:00:00Z"
        with tempfile.TemporaryDirectory() as directory:
            record_path = Path(directory) / "trigger.json"
            record_path.write_text(json.dumps(current_record), encoding="utf-8")
            (Path(directory) / "trigger-10.json").write_text(
                json.dumps(retired_archive_record()), encoding="utf-8"
            )
            state = CHECKER.trigger_state(
                REPO, PR, current_payload, current_record, record_path
            )
            result = CHECKER.retire_trigger_record(
                str(record_path),
                REPO,
                PR,
                20,
                FRESH_HEAD,
                "fresh active trigger is stale after the PR advanced",
                current_payload,
            )
            persisted = json.loads(record_path.read_text(encoding="utf-8"))

        self.assertEqual(
            (state.state, state.response_id, state.attributed), ("active", 21, True)
        )
        self.assertEqual(result["status"], "retired")
        self.assertEqual(persisted["status"], "retired")
        self.assertEqual(persisted["retirement"]["evidence"]["state"], "active")

    def test_mismatched_retired_predecessor_archive_remains_ambiguous_and_refused(
        self,
    ) -> None:
        current_record = fresh_record()
        comments = [trigger_comment(), fresh_trigger_comment()]
        current_payload = payload(comments, head=FRESH_HEAD)
        mismatched_archive = retired_archive_record()
        mismatched_archive["repository"] = "other/repo"
        with tempfile.TemporaryDirectory() as directory:
            record_path = Path(directory) / "trigger.json"
            record_path.write_text(json.dumps(current_record), encoding="utf-8")
            (Path(directory) / "trigger-10.json").write_text(
                json.dumps(mismatched_archive), encoding="utf-8"
            )
            state = CHECKER.trigger_state(
                REPO, PR, current_payload, current_record, record_path
            )
            with self.assertRaisesRegex(ValueError, "ambiguous response evidence"):
                CHECKER.retire_trigger_record(
                    str(record_path),
                    REPO,
                    PR,
                    20,
                    FRESH_HEAD,
                    "operator adjudication",
                    current_payload,
                )

        self.assertEqual((state.state, state.attributed), ("ambiguous", False))

    def test_timed_out_retired_predecessor_requires_original_timeout_evidence(self) -> None:
        current_record = fresh_record()
        comments = [
            trigger_comment(),
            fresh_trigger_comment(),
            comment(
                21,
                "coderabbitai",
                "Full review triggered",
                "2026-09-14T02:00:01Z",
            ),
        ]
        current_payload = payload(comments, head=FRESH_HEAD)
        archive = retired_archive_record(evidence_state="timed_out")
        archive["timeout"] = timed_out_record()["timeout"]
        with tempfile.TemporaryDirectory() as directory:
            record_path = Path(directory) / "trigger.json"
            record_path.write_text(json.dumps(current_record), encoding="utf-8")
            archive_path = Path(directory) / "trigger-10.json"
            archive_path.write_text(json.dumps(archive), encoding="utf-8")

            valid_state = CHECKER.trigger_state(
                REPO, PR, current_payload, current_record, record_path
            )
            archive["timeout"] = {"at": "2026-09-14T01:30:00Z"}
            archive_path.write_text(json.dumps(archive), encoding="utf-8")
            invalid_state = CHECKER.trigger_state(
                REPO, PR, current_payload, current_record, record_path
            )

        self.assertEqual(
            (valid_state.state, valid_state.response_id, valid_state.attributed),
            ("active", 21, True),
        )
        self.assertEqual((invalid_state.state, invalid_state.attributed), ("ambiguous", False))

    def test_retired_predecessor_requires_a_nonempty_live_url(self) -> None:
        current_record = fresh_record()
        archive = retired_archive_record()
        for live_url in (None, "", [], {}):
            with self.subTest(live_url=live_url):
                predecessor = trigger_comment()
                predecessor["url"] = live_url
                comments = [predecessor, fresh_trigger_comment()]
                with tempfile.TemporaryDirectory() as directory:
                    record_path = Path(directory) / "trigger.json"
                    record_path.write_text(
                        json.dumps(current_record), encoding="utf-8"
                    )
                    (Path(directory) / "trigger-10.json").write_text(
                        json.dumps(archive), encoding="utf-8"
                    )
                    state = CHECKER.trigger_state(
                        REPO,
                        PR,
                        payload(comments),
                        current_record,
                        record_path,
                    )
                self.assertEqual((state.state, state.attributed), ("ambiguous", False))

    def test_additional_unretired_predecessor_remains_ambiguous(self) -> None:
        current_record = fresh_record()
        comments = [
            comment(9, "owner", "@coderabbitai full review", "2026-09-14T00:55:00Z"),
            trigger_comment(),
            fresh_trigger_comment(),
        ]
        with tempfile.TemporaryDirectory() as directory:
            record_path = Path(directory) / "trigger.json"
            record_path.write_text(json.dumps(current_record), encoding="utf-8")
            (Path(directory) / "trigger-10.json").write_text(
                json.dumps(retired_archive_record()), encoding="utf-8"
            )
            state = CHECKER.trigger_state(
                REPO, PR, payload(comments), current_record, record_path
            )

        self.assertEqual((state.state, state.attributed), ("ambiguous", False))

    def test_malformed_noncanonical_and_symlink_archives_do_not_suppress_ambiguity(
        self,
    ) -> None:
        current_record = fresh_record()
        comments = [trigger_comment(), fresh_trigger_comment()]
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            record_path = root / "trigger.json"
            record_path.write_text(json.dumps(current_record), encoding="utf-8")
            outside = root.parent / f"{root.name}-retired.json"
            outside.write_text(json.dumps(retired_archive_record()), encoding="utf-8")
            malformed_path = root / "trigger-10.json"
            malformed_path.write_text("{malformed", encoding="utf-8")
            state = CHECKER.trigger_state(
                REPO, PR, payload(comments), current_record, record_path
            )
            malformed_path.unlink()
            self.assertEqual((state.state, state.attributed), ("ambiguous", False))
            deep_path = root / "trigger-10.json"
            deep_path.write_text("[" * 1200 + "]" * 1200, encoding="utf-8")
            state = CHECKER.trigger_state(
                REPO, PR, payload(comments), current_record, record_path
            )
            deep_path.unlink()
            self.assertEqual((state.state, state.attributed), ("ambiguous", False))
            for archive_name in ("trigger-10.json.bak", "trigger-010.json"):
                with self.subTest(archive_name=archive_name):
                    archive_path = root / archive_name
                    archive_path.write_text(
                        json.dumps(retired_archive_record()), encoding="utf-8"
                    )
                    state = CHECKER.trigger_state(
                        REPO, PR, payload(comments), current_record, record_path
                    )
                    archive_path.unlink()
                    self.assertEqual(
                        (state.state, state.attributed), ("ambiguous", False)
                    )
            symlink_path = root / "trigger-10.json"
            symlink_path.symlink_to(outside)
            state = CHECKER.trigger_state(
                REPO, PR, payload(comments), current_record, record_path
            )
            symlink_path.unlink()
            outside.unlink()
            fifo_path = root / "trigger-10.json"
            if hasattr(os, "mkfifo"):
                os.mkfifo(fifo_path)
                state = CHECKER.trigger_state(
                    REPO, PR, payload(comments), current_record, record_path
                )
                fifo_path.unlink()
                self.assertEqual((state.state, state.attributed), ("ambiguous", False))

        self.assertEqual((state.state, state.attributed), ("ambiguous", False))

    def test_missing_trigger_and_posting_reservation_are_unattributed(self) -> None:
        missing = self.state([])
        self.assertEqual((missing.state, missing.attributed), ("unattributed", False))
        posting = record()
        posting.pop("trigger")
        state = self.state(trigger_record=posting)
        self.assertEqual((state.state, state.terminal), ("unattributed", True))

    def test_persisted_timeout_remains_manual_terminal_state(self) -> None:
        state = self.state(trigger_record=timed_out_record())
        self.assertEqual((state.state, state.terminal), ("timed_out", True))
        self.assertTrue(state.manual_adjudication_required)

    def test_bounded_wait_persists_timeout_even_after_active_ack(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            payload_path = root / "payload.json"
            record_path = root / "record.json"
            active_payload = payload(
                [
                    trigger_comment(),
                    comment(
                        11,
                        "coderabbitai",
                        "Full review triggered",
                        "2026-09-14T01:00:01Z",
                    ),
                ]
            )
            payload_path.write_text(json.dumps(active_payload), encoding="utf-8")
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
            persisted = json.loads(record_path.read_text(encoding="utf-8"))

        output = json.loads(completed.stdout)
        self.assertEqual(completed.returncode, 1)
        self.assertEqual(output["trigger_state"]["state"], "timed_out")
        self.assertEqual(persisted["status"], "timed_out")
        self.assertEqual(persisted["timeout"]["observed_state"], "active")

    def test_timeout_does_not_overwrite_a_newer_trigger_record(self) -> None:
        old_record = record()
        newer_record = record()
        newer_record["head_sha"] = "d" * 40
        newer_record["trigger"] = {
            "id": 20,
            "created_at": "2026-09-14T02:00:00Z",
            "url": "https://example.test/comments/20",
            "type": "full",
            "command": "@coderabbitai full review",
        }
        with tempfile.TemporaryDirectory() as directory:
            record_path = Path(directory) / "trigger.json"
            record_path.write_text(json.dumps(old_record), encoding="utf-8")
            state = self.state()
            record_path.write_text(json.dumps(newer_record), encoding="utf-8")
            persisted = CHECKER.persist_timeout_if_current(
                str(record_path), old_record, state
            )
            current = json.loads(record_path.read_text(encoding="utf-8"))

        self.assertFalse(persisted)
        self.assertEqual(current, newer_record)

    def test_operator_retirement_requires_identity_and_preserves_evidence(self) -> None:
        current_payload = payload()
        current_payload["data"]["repository"]["pullRequest"]["commits"]["nodes"][0][
            "commit"
        ]["committedDate"] = "2026-09-14T02:00:00Z"
        with tempfile.TemporaryDirectory() as directory:
            record_path = Path(directory) / "trigger.json"
            record_path.write_text(json.dumps(timed_out_record()), encoding="utf-8")
            result = CHECKER.retire_trigger_record(
                str(record_path),
                REPO,
                PR,
                10,
                HEAD,
                "bounded wait timed out; no current review evidence",
                current_payload,
            )
            persisted = json.loads(record_path.read_text(encoding="utf-8"))

        self.assertEqual(result["status"], "retired")
        self.assertEqual(persisted["status"], "retired")
        self.assertEqual(persisted["trigger"]["id"], 10)
        self.assertEqual(persisted["head_sha"], HEAD)
        self.assertEqual(
            persisted["retirement"]["expected_head_sha"],
            HEAD,
        )
        self.assertEqual(
            persisted["retirement"]["reason"],
            "bounded wait timed out; no current review evidence",
        )
        self.assertEqual(persisted["retirement"]["evidence"]["state"], "timed_out")
        self.assertNotIn("trigger_record", result)

    def test_operator_retirement_repairs_posted_active_old_head(self) -> None:
        new_head = "c" * 40
        current_payload = payload(
            [
                trigger_comment(),
                comment(
                    11,
                    "coderabbitai",
                    "Full review triggered",
                    "2026-09-14T01:00:01Z",
                ),
            ]
        )
        pull_request = current_payload["data"]["repository"]["pullRequest"]
        assert isinstance(pull_request, dict)
        pull_request["headRefOid"] = new_head
        pull_request["commits"] = {
            "nodes": [
                {
                    "commit": {
                        "oid": new_head,
                        "committedDate": "2026-09-14T02:00:00Z",
                    }
                }
            ]
        }
        with tempfile.TemporaryDirectory() as directory:
            record_path = Path(directory) / "trigger.json"
            record_path.write_text(json.dumps(record()), encoding="utf-8")
            result = CHECKER.retire_trigger_record(
                str(record_path),
                REPO,
                PR,
                10,
                new_head,
                "old-head acknowledgement is stale after the PR advanced",
                current_payload,
            )
            persisted = json.loads(record_path.read_text(encoding="utf-8"))

        self.assertEqual(result["status"], "retired")
        self.assertEqual(persisted["status"], "retired")
        self.assertEqual(persisted["head_sha"], HEAD)
        self.assertEqual(persisted["retirement"]["evidence"]["state"], "active")

    def test_operator_retirement_refuses_active_ambiguous_and_head_mismatch(
        self,
    ) -> None:
        current_payload = payload()
        current_payload["data"]["repository"]["pullRequest"]["commits"]["nodes"][0][
            "commit"
        ]["committedDate"] = "2026-09-14T02:00:00Z"
        active_comments = [
            trigger_comment(),
            comment(11, "coderabbitai", "Full review triggered", "2026-09-14T01:00:01Z"),
        ]
        ambiguous_comments = [
            trigger_comment(),
            comment(12, "other", "@coderabbitai full review", TRIGGER_AT),
        ]
        with tempfile.TemporaryDirectory() as directory:
            record_path = Path(directory) / "trigger.json"
            record_path.write_text(json.dumps(timed_out_record()), encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "active review"):
                CHECKER.retire_trigger_record(
                    str(record_path),
                    REPO,
                    PR,
                    10,
                    HEAD,
                    "operator adjudication",
                    payload(active_comments),
                )
            with self.assertRaisesRegex(ValueError, "ambiguous"):
                CHECKER.retire_trigger_record(
                    str(record_path),
                    REPO,
                    PR,
                    10,
                    HEAD,
                    "operator adjudication",
                    payload(ambiguous_comments),
                )
            mismatch_payload = payload()
            mismatch_payload["data"]["repository"]["pullRequest"]["headRefOid"] = "b" * 40
            with self.assertRaisesRegex(ValueError, "expected head SHA"):
                CHECKER.retire_trigger_record(
                    str(record_path),
                    REPO,
                    PR,
                    10,
                    HEAD,
                    "operator adjudication",
                    mismatch_payload,
                )

    def test_operator_retirement_refuses_record_identity_mismatch(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            record_path = Path(directory) / "trigger.json"
            mismatched = timed_out_record()
            mismatched["repository"] = "other/repo"
            record_path.write_text(json.dumps(mismatched), encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "repository or pull request"):
                CHECKER.retire_trigger_record(
                    str(record_path),
                    REPO,
                    PR,
                    10,
                    HEAD,
                    "operator adjudication",
                    payload(),
                )

    def test_retirement_reason_rejects_unicode_line_separators(self) -> None:
        for separator in ("\x85", "\u2028", "\u2029"):
            with self.subTest(separator=hex(ord(separator))), self.assertRaisesRegex(
                ValueError, "one line"
            ):
                CHECKER.validate_retirement_reason(f"operator{separator}reason")

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
            text_completed = subprocess.run(
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
                ],
                check=False,
                capture_output=True,
                text=True,
            )
            persisted = json.loads(record_path.read_text(encoding="utf-8"))
        output = json.loads(completed.stdout)
        self.assertEqual(completed.returncode, 1)
        self.assertFalse(output["ok"])
        self.assertEqual(output["trigger_state"]["state"], "timed_out")
        self.assertTrue(output["trigger_state"]["terminal"])
        self.assertTrue(output["trigger_state"]["attributed"])
        self.assertEqual(output["trigger_state"]["trigger_comment_id"], 10)
        self.assertGreaterEqual(output["trigger_state"]["age_seconds"], 0)
        self.assertTrue(output["trigger_state"]["manual_adjudication_required"])
        self.assertEqual(persisted["status"], "timed_out")
        self.assertEqual(
            persisted["timeout"]["observed_state"],
            "awaiting_response",
        )
        self.assertEqual(
            persisted["timeout"]["reason"],
            "bounded wait expired before a terminal CodeRabbit response",
        )
        self.assertEqual(text_completed.returncode, 1)
        self.assertRegex(
            text_completed.stdout,
            r"warning=HOSTED CODERABBIT TRIGGER 10 HAS NO ATTRIBUTABLE TERMINAL RESPONSE; "
            r"AGE=\d+ SECONDS; MANUAL OVERSEER ADJUDICATION REQUIRED BEFORE RETRYING",
        )

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
