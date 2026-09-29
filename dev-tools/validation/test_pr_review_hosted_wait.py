"""Focused checks for bounded exact-trigger Hosted waiting."""

from __future__ import annotations

import io
import json
import sys
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from pr_review import cli, github, hosted, hosted_wait


def observation(state: str, *, terminal: bool = False, attributed: bool = True) -> SimpleNamespace:
    return SimpleNamespace(
        state=state,
        terminal=terminal,
        attributed=attributed,
        as_dict=lambda: {"state": state, "terminal": terminal, "attributed": attributed},
    )


class HostedWaitTests(unittest.TestCase):
    def test_exact_saved_trigger_uses_existing_full_history_classifier(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "trigger-42.json"
            path.write_text(json.dumps({
                "repository": "owner/repo", "pr_number": 19, "head_sha": "a" * 40,
                "trigger": {"id": 42},
            }))
            payload = {"complete": "snapshot"}
            expected = observation("completed", terminal=True)
            with (
                patch.object(hosted, "trigger_record_paths", return_value=[path]),
                patch.object(github, "fetch_pull_request", return_value=payload) as fetch,
                patch.object(hosted, "trigger_state", return_value=expected) as classify,
            ):
                self.assertIs(hosted_wait.observe_trigger("owner/repo", 19, 42), expected)
            fetch.assert_called_once_with("owner/repo", 19)
            self.assertEqual(classify.call_args.args[:3], ("owner/repo", 19, payload))
            self.assertEqual(classify.call_args.args[4], path)

    def test_exact_wait_skips_unposted_current_reservation(self) -> None:
        current = Path("trigger.json")
        archived = Path("trigger-42.json")
        reservation = {
            "repository": "owner/repo",
            "pr_number": 19,
            "status": "posting",
            "head_sha": "a" * 40,
        }
        record = {
            "repository": "owner/repo",
            "pr_number": 19,
            "head_sha": "a" * 40,
            "trigger": {"id": 42},
        }
        expected = observation("completed", terminal=True)
        with (
            patch.object(hosted, "trigger_record_paths", return_value=[current, archived]),
            patch.object(hosted, "load_trigger_reservation", return_value=reservation),
            patch.object(hosted, "load_trigger_record", return_value=record) as load_record,
            patch.object(github, "fetch_pull_request", return_value={"complete": "snapshot"}),
            patch.object(hosted, "trigger_state", return_value=expected),
        ):
            self.assertIs(hosted_wait.observe_trigger("owner/repo", 19, 42), expected)
        load_record.assert_called_once_with(archived, "owner/repo", 19)

    def test_unknown_trigger_does_not_fetch_or_post(self) -> None:
        with (
            patch.object(hosted, "trigger_record_paths", return_value=[]),
            patch.object(github, "fetch_pull_request") as fetch,
        ):
            with self.assertRaisesRegex(hosted_wait.HostedWaitError, "exactly one saved Hosted trigger"):
                hosted_wait.observe_trigger("owner/repo", 19, 42)
            fetch.assert_not_called()

    def test_wait_reports_completed_exact_result_and_state_changes(self) -> None:
        states = iter((observation("awaiting_response"), observation("active"),
                       observation("completed", terminal=True)))
        clock = [0.0]
        changes: list[str] = []
        result, code = hosted_wait.wait_for_hosted(
            "owner/repo", 19, 42, poll_seconds=10, max_wait_seconds=40,
            observe=lambda _repo, _pr, _id: next(states),
            monotonic=lambda: clock[0], sleep=lambda seconds: clock.__setitem__(0, clock[0] + seconds),
            on_change=changes.append,
        )
        self.assertEqual(code, 0)
        self.assertEqual(result["status"], "completed")
        self.assertEqual(clock[0], 20)
        self.assertEqual(changes, ["awaiting_response", "active"])

    def test_rate_limit_is_terminal_but_not_a_completed_review(self) -> None:
        result, code = hosted_wait.wait_for_hosted(
            "owner/repo", 19, 42,
            observe=lambda _repo, _pr, _id: observation("rate_limited", terminal=True),
        )
        self.assertEqual((result["status"], code), ("rate_limited", 3))

    def test_deadline_does_not_retire_or_reclassify_trigger(self) -> None:
        clock = [0.0]
        reads = [0]

        def observe(_repo: str, _pr: int, _id: int) -> SimpleNamespace:
            reads[0] += 1
            return observation("awaiting_response")

        result, code = hosted_wait.wait_for_hosted(
            "owner/repo", 19, 42, poll_seconds=10, max_wait_seconds=25,
            observe=observe, monotonic=lambda: clock[0],
            sleep=lambda seconds: clock.__setitem__(0, clock[0] + seconds),
        )
        self.assertEqual((result["status"], code), ("wait_expired", 4))
        self.assertEqual(result["observation"]["state"], "awaiting_response")
        self.assertEqual(reads[0], 4)

    def test_transient_read_failure_retries_but_repeated_failure_surfaces(self) -> None:
        clock = [0.0]
        attempts = [0]

        def recover(_repo: str, _pr: int, _id: int) -> SimpleNamespace:
            attempts[0] += 1
            if attempts[0] == 1:
                raise RuntimeError("temporary GitHub failure")
            return observation("completed", terminal=True)

        result, code = hosted_wait.wait_for_hosted(
            "owner/repo", 19, 42, poll_seconds=1, max_wait_seconds=5,
            observe=recover, monotonic=lambda: clock[0],
            sleep=lambda seconds: clock.__setitem__(0, clock[0] + seconds),
        )
        self.assertEqual((result["status"], code), ("completed", 0))
        with self.assertRaisesRegex(hosted_wait.HostedWaitError, "failed 3 times"):
            hosted_wait.wait_for_hosted(
                "owner/repo", 19, 42, poll_seconds=1, max_wait_seconds=5,
                observe=lambda _repo, _pr, _id: (_ for _ in ()).throw(RuntimeError("offline")),
                monotonic=lambda: clock[0],
                sleep=lambda seconds: clock.__setitem__(0, clock[0] + seconds),
            )

    def test_cli_wait_does_not_construct_controller_or_request_review(self) -> None:
        output = io.StringIO()
        expected = {"pr": 19, "trigger_id": 42, "status": "completed"}
        with (
            patch.object(cli, "default_controller", side_effect=AssertionError("controller loaded")),
            patch.object(hosted_wait, "wait_for_hosted", return_value=(expected, 0)) as wait,
            patch("sys.stdout", output),
        ):
            code = cli.main(["wait", "hosted", "--pr", "19", "--trigger-id", "42",
                             "--repo", "owner/repo"])
        self.assertEqual(code, 0)
        self.assertEqual(json.loads(output.getvalue()), expected)
        self.assertEqual(wait.call_args.args[:3], ("owner/repo", 19, 42))
        self.assertEqual(wait.call_args.kwargs["poll_seconds"], 30)
        self.assertEqual(wait.call_args.kwargs["max_wait_seconds"], 5400)


if __name__ == "__main__":
    unittest.main()
