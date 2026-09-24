#!/usr/bin/env python3
"""Focused recovery contracts for durable Hosted posting reservations."""

from __future__ import annotations

import json
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "dev-tools"))

from pr_review import hosted

REPO = "owner/repo"
PR = 42
CAPTURED_HEAD = "b" * 40
ADVANCED_HEAD = "d" * 40


class HostedPostingRecoveryTests(unittest.TestCase):
    def _reservation(self, path: Path) -> None:
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(
            json.dumps(
                {
                    "schema_version": 2,
                    "status": "posting",
                    "repository": REPO,
                    "pr_number": PR,
                    "head_sha": CAPTURED_HEAD,
                    "posting_started_at": "2026-09-23T00:00:00Z",
                    "posting_actor_login": "maintainer",
                    "posting_comment_id_floor": 30,
                }
            ),
            encoding="utf-8",
        )

    @staticmethod
    def _comment(comment_id: int, actor: str = "maintainer") -> dict[str, object]:
        return {
            "databaseId": comment_id,
            "author": {"login": actor},
            "body": hosted.FULL_COMMAND,
            "createdAt": "2026-09-23T00:01:00Z",
            "url": f"https://example.test/comments/{comment_id}",
        }

    @staticmethod
    def _payload(head: object, comments: list[dict[str, object]] | None = None) -> dict[str, object]:
        return {
            "data": {
                "repository": {
                    "pullRequest": {
                        "headRefOid": head,
                        "comments": {"nodes": comments or []},
                        "reviews": {"nodes": []},
                    }
                }
            }
        }

    def _boundary_changed_trigger(self, path: Path) -> dict[str, object]:
        trigger = self._comment(31)
        trigger["createdAt"] = "2026-09-23T00:01:00Z"
        hosted.record_posted_trigger(REPO, PR, CAPTURED_HEAD, trigger, path=path)
        record = json.loads(path.read_text(encoding="utf-8"))
        record["status"] = "posted_boundary_changed"
        record["recovery"] = {
            "captured_head_sha": CAPTURED_HEAD,
            "live_head_sha": ADVANCED_HEAD,
        }
        path.write_text(json.dumps(record), encoding="utf-8")
        return record

    def _retire_stuck(self, path: Path, payload: dict[str, object]) -> dict[str, object]:
        with (
            patch.object(hosted, "default_trigger_record_path", return_value=path.with_name("request.lock")),
            patch.object(hosted, "current_trigger_record_paths", return_value=[path]),
        ):
            return hosted.retire_stuck_trigger_after_head_advance(
                path,
                REPO,
                PR,
                31,
                ADVANCED_HEAD,
                "captured head advanced and response is terminal",
                True,
                lambda: payload,
            )

    def _adopt(self, path: Path, expected_head: str, payload: dict[str, object]) -> dict[str, object]:
        with patch.object(hosted, "default_trigger_record_path", return_value=path.with_name("request.lock")):
            return hosted.adopt_posting_reservation(path, REPO, PR, expected_head, payload)

    def _recover_prepost(
        self, path: Path, expected_head: str, payload: dict[str, object]
    ) -> dict[str, object]:
        with (
            patch.object(hosted, "default_trigger_record_path", return_value=path.with_name("request.lock")),
            patch.object(hosted, "current_trigger_record_paths", return_value=[path]),
        ):
            return hosted.recover_prepost_reservation(
                path,
                REPO,
                PR,
                expected_head,
                "operator confirmed no POST was issued",
                True,
                lambda: payload,
            )

    def test_adoption_after_head_advance_is_boundary_changed_and_not_evidence(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "trigger.json"
            self._reservation(path)

            record = self._adopt(path, ADVANCED_HEAD, self._payload(ADVANCED_HEAD, [self._comment(31)]))

            self.assertEqual(record["status"], "posted_boundary_changed")
            self.assertEqual(record["head_sha"], CAPTURED_HEAD)
            self.assertEqual(record["recovery"]["captured_head_sha"], CAPTURED_HEAD)
            self.assertEqual(record["recovery"]["live_head_sha"], ADVANCED_HEAD)
            state = hosted.trigger_state(REPO, PR, self._payload(ADVANCED_HEAD), record)
            self.assertEqual(state.state, "ambiguous")
            self.assertFalse(state.attributed)

    def test_adoption_on_captured_head_remains_posted(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "trigger.json"
            self._reservation(path)

            record = self._adopt(path, CAPTURED_HEAD, self._payload(CAPTURED_HEAD, [self._comment(31)]))

            self.assertEqual(record["status"], "posted")
            self.assertEqual(record["head_sha"], CAPTURED_HEAD)
            self.assertEqual(record["recovery"]["live_head_sha"], CAPTURED_HEAD)

    def test_adoption_refuses_ambiguous_commands_after_comment_floor(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "trigger.json"
            self._reservation(path)

            with self.assertRaisesRegex(ValueError, "exactly one live command"):
                self._adopt(
                    path,
                    CAPTURED_HEAD,
                    self._payload(CAPTURED_HEAD, [self._comment(31), self._comment(32)]),
                )

            self.assertEqual(json.loads(path.read_text(encoding="utf-8"))["status"], "posting")

    def test_adoption_requires_a_well_formed_live_head(self) -> None:
        for live_head in (None, "not-a-sha"):
            with self.subTest(live_head=live_head), tempfile.TemporaryDirectory() as directory:
                path = Path(directory) / "trigger.json"
                self._reservation(path)

                with self.assertRaisesRegex(ValueError, "live pull-request head is not an exact SHA"):
                    self._adopt(path, CAPTURED_HEAD, self._payload(live_head, [self._comment(31)]))

                self.assertEqual(json.loads(path.read_text(encoding="utf-8"))["status"], "posting")

    def test_prepost_recovery_archives_captured_and_advanced_live_heads(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "trigger.json"
            self._reservation(path)

            result = self._recover_prepost(path, CAPTURED_HEAD, self._payload(ADVANCED_HEAD))

            self.assertEqual(result["status"], "abandoned_no_post")
            self.assertEqual(result["captured_head_sha"], CAPTURED_HEAD)
            self.assertEqual(result["current_head_sha"], ADVANCED_HEAD)
            self.assertFalse(path.exists())
            audit = json.loads(Path(result["audit_path"]).read_text(encoding="utf-8"))
            self.assertEqual(audit["head_sha"], CAPTURED_HEAD)
            self.assertEqual(audit["recovery"]["captured_head_sha"], CAPTURED_HEAD)
            self.assertEqual(audit["recovery"]["live_head_sha"], ADVANCED_HEAD)

    def test_prepost_recovery_archives_same_head_identity(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "trigger.json"
            self._reservation(path)

            result = self._recover_prepost(path, CAPTURED_HEAD, self._payload(CAPTURED_HEAD))

            self.assertEqual(result["status"], "abandoned_no_post")
            self.assertEqual(result["captured_head_sha"], CAPTURED_HEAD)
            self.assertEqual(result["current_head_sha"], CAPTURED_HEAD)

    def test_prepost_recovery_refuses_ambiguous_command_and_keeps_reservation(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "trigger.json"
            self._reservation(path)

            with self.assertRaisesRegex(ValueError, "ambiguous full-review command"):
                self._recover_prepost(
                    path,
                    CAPTURED_HEAD,
                    self._payload(CAPTURED_HEAD, [self._comment(31, actor="another-maintainer")]),
                )

            self.assertEqual(json.loads(path.read_text(encoding="utf-8"))["status"], "posting")

    def test_prepost_recovery_requires_a_well_formed_live_head(self) -> None:
        for live_head in (None, "not-a-sha"):
            with self.subTest(live_head=live_head), tempfile.TemporaryDirectory() as directory:
                path = Path(directory) / "trigger.json"
                self._reservation(path)

                with self.assertRaisesRegex(ValueError, "live pull-request head is not an exact SHA"):
                    self._recover_prepost(path, CAPTURED_HEAD, self._payload(live_head))

                self.assertTrue(path.exists())

    def test_boundary_changed_trigger_can_be_retired_after_terminal_response(self) -> None:
        responses = {
            "completed": "Reviewing files that changed from the base of the PR and between `aaaaaa` and `bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb`.\n\n**Actionable comments posted: 1**",
            "rate_limited": hosted.REVIEW_LIMIT_MARKER,
            "noop": hosted.NOOP_MARKER,
            "failed": "The review failed while processing this request.",
        }
        for state, body in responses.items():
            with self.subTest(state=state), tempfile.TemporaryDirectory() as directory:
                path = Path(directory) / "trigger.json"
                self._boundary_changed_trigger(path)
                response = self._comment(32, actor="coderabbitai[bot]")
                response["createdAt"] = "2026-09-23T00:02:00Z"
                response["body"] = body
                payload = self._payload(ADVANCED_HEAD, [self._comment(31), response])

                result = self._retire_stuck(path, payload)

                self.assertEqual(result["status"], "retired")
                self.assertEqual(result["observed_live_state"], state)
                retired = json.loads(path.read_text(encoding="utf-8"))
                self.assertEqual(retired["status"], "retired")
                self.assertEqual(retired["retirement"]["observed_response_id"], 32)

    def test_terminal_response_does_not_enable_retirement_for_other_records(self) -> None:
        response = self._comment(32, actor="coderabbitai[bot]")
        response["createdAt"] = "2026-09-23T00:02:00Z"
        response["body"] = hosted.REVIEW_LIMIT_MARKER
        payload = self._payload(ADVANCED_HEAD, [self._comment(31), response])

        for original_status in ("posted", "timed_out"):
            with self.subTest(status=original_status), tempfile.TemporaryDirectory() as directory:
                path = Path(directory) / "trigger.json"
                record = self._boundary_changed_trigger(path)
                record["status"] = original_status
                if original_status == "timed_out":
                    record["timeout"] = {
                        "observed_state": "awaiting_response",
                        "at": "2026-09-23T00:01:30Z",
                    }
                path.write_text(json.dumps(record), encoding="utf-8")

                with self.assertRaisesRegex(ValueError, "cannot retire stuck trigger in live state rate_limited"):
                    self._retire_stuck(path, payload)

                self.assertEqual(json.loads(path.read_text(encoding="utf-8"))["status"], original_status)


if __name__ == "__main__":
    unittest.main()
