import contextlib
import fcntl
import io
import json
import os
import sys
import tempfile
import unittest
from datetime import datetime, timezone
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).parents[1]))

from pr_review import cli, cli_attempts, sqlite_review_records
from pr_review.controller import ReviewController
from pr_review.sqlite_review_records import FindingObservation, ReviewRecordsError, SqliteReviewRecords
from pr_review.sqlite_store import SqliteStateStore
from pr_review.state import FindingRoute, ReviewState, StateStore, SummaryFindingDisposition


class ReviewRecordsCliTest(unittest.TestCase):
    def test_records_source_resolve_requires_a_full_commit_sha(self) -> None:
        prefix = [
            "records",
            "source",
            "resolve",
            "--source-pr",
            "2885",
            "--run-id",
            "run.source-resolution-cli",
            "--finding-key",
            "accepted-key",
            "--resolution-id",
            "source-resolution-cli-proof",
            "--fix-sha",
        ]
        parsed = cli._parser().parse_args([*prefix, "a" * 40, "--actor", "owner", "--proof-note", "verified"])
        self.assertEqual(parsed.fix_sha, "a" * 40)
        for invalid_sha in ("a" * 12, "g" * 40, "a" * 39):
            with self.subTest(invalid_sha=invalid_sha), self.assertRaises(SystemExit):
                cli._parser().parse_args(
                    [*prefix, invalid_sha, "--actor", "owner", "--proof-note", "verified"]
                )

    def test_subagent_start_rejects_oversized_coverage_before_recording(self) -> None:
        self.invoke("bootstrap", "--database", str(self.database))
        for length, expected in ((200, 0), (201, 2)):
            code, _ = self.invoke(
                "subagent",
                "start",
                "--pr",
                "2893",
                "--run-id",
                f"coverage-{length}",
                "--reviewer",
                "Sol medium",
                "--scope",
                "narrow",
                "--coverage-limit",
                "n" * length,
                "--database",
                str(self.database),
            )
            self.assertEqual(code, expected)
        records = SqliteReviewRecords(self.database)
        self.assertEqual([item["attempt_id"] for item in records.attempt_history(2893)], ["coverage-200"])

    def test_existing_long_subagent_coverage_completes_without_changing_note_or_counts(self) -> None:
        self.invoke("bootstrap", "--database", str(self.database))
        note = "Original controller audit coverage retained. " * 7
        with patch.object(sqlite_review_records, "_coverage_limits", return_value=(note,)):
            code, _ = self.invoke(
                "subagent",
                "start",
                "--pr",
                "2893",
                "--run-id",
                "legacy-controller-round",
                "--reviewer",
                "Sol medium",
                "--scope",
                "narrow",
                "--coverage-limit",
                note,
                "--head",
                "a" * 40,
                "--database",
                str(self.database),
            )
        self.assertEqual(code, 0)
        records = SqliteReviewRecords(self.database)
        metadata = records.attempt("legacy-controller-round")["metadata"]
        arguments = ["subagent", "complete", "--run-id", "legacy-controller-round", "--actor", "root verified"]
        for title in ("Admission selection race", "Stopped historical evidence"):
            arguments += ["--finding-json", json.dumps({"title": title, "decision": "accepted", "reason": "verified"})]
        arguments += ["--database", str(self.database)]
        code, result = self.invoke(*arguments)
        self.assertEqual(code, 0, result)
        self.assertEqual(result["result"]["run"]["counts"], {"found": 2, "accepted": 2, "routed": 0})
        self.assertEqual(records.attempt("legacy-controller-round")["metadata"], metadata)
        self.assertEqual(records.history(2893)["runs"][0]["coverage_limits"], [note])
        code, replay = self.invoke(*arguments)
        self.assertEqual(code, 0, replay)
        self.assertTrue(replay["result"]["run"]["idempotent_replay"])

    def setUp(self) -> None:
        self.temporary_directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary_directory.cleanup)
        root = Path(self.temporary_directory.name)
        self.database = root / "controller.sqlite3"
        SqliteStateStore(self.database).update(lambda state: state)
        self.import_file = root / "manual-run.json"

    def invoke(self, *arguments: str) -> tuple[int, dict[str, object]]:
        output = io.StringIO()
        errors = io.StringIO()
        with (
            patch.object(cli, "default_controller", side_effect=AssertionError("records must not load controller")),
            contextlib.redirect_stdout(output),
            contextlib.redirect_stderr(errors),
        ):
            result = cli.main(["records", *arguments])
        if output.getvalue():
            return result, json.loads(output.getvalue())
        return result, {"error": errors.getvalue()}

    def test_cli_attempt_reconciliation_refuses_while_runner_lock_is_held(self) -> None:
        self.invoke("bootstrap", "--database", str(self.database))
        records = SqliteReviewRecords(self.database)
        capture_root = self.database.parent / "pr-review" / "runs"
        run_id = "run." + "e" * 32
        started_metadata = {
            "run_id": run_id,
            "kind": "cli",
            "capture_completion_marker": "capture-complete",
            "pull_request": 2885,
            "candidate_sha": "d" * 40,
        }
        capture = capture_root / run_id
        capture.mkdir(parents=True)
        (capture / "metadata.json").write_text(json.dumps(started_metadata), encoding="utf-8")
        (capture / "error").write_text("setup failed\n", encoding="utf-8")
        records.start_attempt(
            attempt_id=run_id,
            source_pr=2885,
            channel="cli",
            candidate_sha="d" * 40,
            metadata=started_metadata,
        )
        lock_path = self.database.parent / "pr-review" / "cli.lock"
        lock_path.touch(mode=0o600)

        with lock_path.open("r+") as lock_handle:
            fcntl.flock(lock_handle.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
            try:
                with patch.object(records, "attempt", side_effect=AssertionError("SQL reconciliation ran")):
                    result = cli_attempts.reconcile_legacy_failed_attempts(records, self.database)
            finally:
                fcntl.flock(lock_handle.fileno(), fcntl.LOCK_UN)

        self.assertFalse(result["available"])
        self.assertIn("CLI review is active", result["reason"])
        self.assertEqual(records.attempt(run_id)["state"], "started")

    def test_cli_attempt_reconciliation_does_not_match_missing_attempt_by_error_text(self) -> None:
        self.invoke("bootstrap", "--database", str(self.database))
        run_id = "run." + "f" * 32
        capture = self.database.parent / "pr-review" / "runs" / run_id
        capture.mkdir(parents=True)
        (capture / "metadata.json").write_text(
            json.dumps(
                {
                    "run_id": run_id,
                    "kind": "cli",
                    "capture_completion_marker": "capture-complete",
                    "pull_request": 2885,
                    "candidate_sha": "d" * 40,
                }
            ),
            encoding="utf-8",
        )
        (capture / "exit-status").write_text("0\n", encoding="utf-8")
        records = SqliteReviewRecords(self.database)

        with patch.object(records, "attempt", side_effect=ReviewRecordsError("review attempt does not exist")):
            result = cli_attempts.reconcile_legacy_failed_attempts(records, self.database)

        self.assertEqual(
            result["conflicts"],
            [
                {
                    "run_id": run_id,
                    "reason": "existing attempt could not be read",
                }
            ],
        )

    def test_subagent_pass_records_attempt_findings_decisions_and_route_without_taper(self) -> None:
        self.invoke("bootstrap", "--database", str(self.database))
        code, started = self.invoke(
            "subagent",
            "start",
            "--pr",
            "2893",
            "--run-id",
            "subagent.review-1",
            "--reviewer",
            "Luna independent pass",
            "--scope",
            "broad",
            "--head",
            "a" * 40,
            "--database",
            str(self.database),
        )
        self.assertEqual(code, 0)
        self.assertEqual(started["result"]["state"], "started")
        items = (
            {
                "title": "Bound the retry",
                "detail": "Exact candidate is retained",
                "decision": "accepted",
                "reason": "Owned by this PR",
            },
            {
                "title": "Another owner must repair proof",
                "decision": "routed",
                "reason": "Belongs to target",
                "target_pr": 2895,
            },
        )
        arguments = ["subagent", "complete", "--run-id", "subagent.review-1", "--actor", "Overseer"]
        for item in items:
            arguments.extend(("--finding-json", json.dumps(item)))
        arguments.extend(("--database", str(self.database)))
        code, completed = self.invoke(*arguments)
        self.assertEqual(code, 0)
        self.assertEqual(completed["result"]["run"]["counts"], {"found": 2, "accepted": 1, "routed": 1})
        code, replay = self.invoke(*arguments)
        self.assertEqual(code, 0)
        self.assertTrue(replay["result"]["run"]["idempotent_replay"])
        history = SqliteReviewRecords(self.database).history(2893)
        self.assertEqual(history["attempts"][0]["state"], "completed")
        self.assertEqual(history["runs"][0]["channel"], "subagent")
        self.assertEqual(len(history["findings"]), 2)
        self.assertEqual(history["routes"][0]["target_pr"], 2895)

    def test_subagent_completion_replay_preserves_attempt_finish_time_without_prior_run(self) -> None:
        self.invoke("bootstrap", "--database", str(self.database))
        run_id = "subagent.replay-finished-at"
        self.invoke(
            "subagent",
            "start",
            "--pr",
            "2893",
            "--run-id",
            run_id,
            "--reviewer",
            "Luna",
            "--scope",
            "narrow",
            "--database",
            str(self.database),
        )
        expected_finished_at = "2026-09-29T01:02:03Z"
        SqliteReviewRecords(self.database).finish_attempt(
            run_id,
            state="completed",
            finished_at=expected_finished_at,
        )
        code, completed = self.invoke(
            "subagent",
            "complete",
            "--run-id",
            run_id,
            "--actor",
            "Overseer",
            "--database",
            str(self.database),
        )
        self.assertEqual(code, 0)
        self.assertFalse(completed["result"]["run"]["idempotent_replay"])
        self.assertEqual(
            SqliteReviewRecords(self.database).history(2893)["runs"][0]["finished_at"],
            expected_finished_at,
        )

    def test_records_source_resolve_records_exact_accepted_fix_without_changing_counts(self) -> None:
        self.invoke("bootstrap", "--database", str(self.database))
        records = SqliteReviewRecords(self.database)
        records.import_completed_run(
            run_id="run.source-resolution-cli",
            source_pr=2885,
            channel="cli",
            source_head="a" * 40,
            reviewer="CodeRabbit",
            findings=(FindingObservation(source_finding_key="accepted-key", title="Accepted finding"),),
            source_decisions=(
                {
                    "source_finding_key": "accepted-key",
                    "decision_id": "source-resolution-cli-decision",
                    "decision": "accepted",
                    "actor": "reviewer",
                    "reason": "owned by this PR",
                },
            ),
        )

        code, result = self.invoke(
            "source",
            "resolve",
            "--source-pr",
            "2885",
            "--run-id",
            "run.source-resolution-cli",
            "--finding-key",
            "accepted-key",
            "--resolution-id",
            "source-resolution-cli-proof",
            "--fix-sha",
            "b" * 40,
            "--actor",
            "owner",
            "--proof-note",
            "Verified fixed in the exact source commit",
            "--resolved-at",
            "2026-09-30T12:00:00Z",
            "--database",
            str(self.database),
        )

        self.assertEqual(code, 0, result)
        self.assertEqual(result["result"]["outcome"], "accepted_fixed")
        self.assertFalse(result["result"]["idempotent_replay"])
        self.assertEqual(
            records.source_resolution_status(
                "run.source-resolution-cli",
                source_pr=2885,
                source_channel="cli",
                source_head="a" * 40,
                accepted_count=1,
            ),
            "resolved",
        )
        self.assertEqual(records.history(2885)["runs"][0]["counts"], {"found": 1, "accepted": 1, "routed": 0})

        wrong_pr_code, _ = self.invoke(
            "source",
            "resolve",
            "--source-pr",
            "2886",
            "--run-id",
            "run.source-resolution-cli",
            "--finding-key",
            "accepted-key",
            "--resolution-id",
            "wrong-pr-proof",
            "--fix-sha",
            "b" * 40,
            "--actor",
            "owner",
            "--proof-note",
            "Must bind to the exact source PR",
            "--database",
            str(self.database),
        )
        self.assertEqual(wrong_pr_code, 2)
        self.assertEqual(len(records.history(2885)["source_resolutions"]), 1)

    def test_failed_subagent_pass_has_no_completed_run(self) -> None:
        self.invoke("bootstrap", "--database", str(self.database))
        self.invoke(
            "subagent",
            "start",
            "--pr",
            "2893",
            "--run-id",
            "subagent.failed-1",
            "--reviewer",
            "Luna",
            "--scope",
            "narrow",
            "--database",
            str(self.database),
        )
        code, result = self.invoke(
            "subagent",
            "fail",
            "--run-id",
            "subagent.failed-1",
            "--reason",
            "model unavailable",
            "--database",
            str(self.database),
        )
        self.assertEqual(code, 0)
        self.assertEqual(result["result"]["state"], "failed")
        history = SqliteReviewRecords(self.database).history(2893)
        self.assertEqual(history["runs"], [])
        self.assertEqual(history["attempts"][0]["state"], "failed")

    def test_batch_history_keeps_each_pr_history_and_attempts_separate(self) -> None:
        self.invoke("bootstrap", "--database", str(self.database))
        records = SqliteReviewRecords(self.database)
        records.start_attempt(attempt_id="run.failed-1", source_pr=2890, channel="cli")
        records.finish_attempt("run.failed-1", state="failed", diagnostic="provider closed")
        code, response = self.invoke("history-batch", "--pr", "2890", "--pr", "2893", "--database", str(self.database))
        self.assertEqual(code, 0)
        histories = response["result"]["prs"]
        self.assertEqual(list(histories), ["2890", "2893"])
        self.assertEqual(histories["2890"]["attempts"][0]["attempt_id"], "run.failed-1")
        self.assertEqual(histories["2893"]["attempts"], [])
        code, _ = self.invoke("history-batch", "--pr", "2890", "--pr", "2890", "--database", str(self.database))
        self.assertNotEqual(code, 0)

    def test_route_cli_lists_open_by_default_and_filters_source_and_status(self) -> None:
        self.invoke("bootstrap", "--database", str(self.database))
        records = SqliteReviewRecords(self.database)
        for source_pr, key, target_pr in (
            (2700, "cli-open-target", 2879),
            (2701, "cli-open-unassigned", None),
            (2702, "cli-resolved", 2879),
        ):
            records.record_run(
                run_id=f"cli-route-{source_pr}",
                source_pr=source_pr,
                channel="manual",
                findings=(FindingObservation(key, key, "routed", target_pr=target_pr),),
            )
        resolved = records.list_routes(source_pr=2702)[0]
        records.record_resolution(
            resolved["route_id"],
            resolution_id="cli-route-resolution",
            resolution_pr=2879,
            outcome="rejected",
            actor="owner",
            proof_or_reason="not an actionable finding",
        )

        code, default_listing = self.invoke("routes", "--database", str(self.database))
        self.assertEqual(code, 0)
        self.assertEqual(
            {route["source_pr"] for route in default_listing["result"]["routes"]},
            {2700, 2701},
        )
        self.assertEqual(
            {route["title"] for route in default_listing["result"]["routes"]},
            {"cli-open-target", "cli-open-unassigned"},
        )
        code, resolved_listing = self.invoke("routes", "--status", "resolved", "--database", str(self.database))
        self.assertEqual(code, 0)
        self.assertEqual(
            [(route["source_pr"], route["status"]) for route in resolved_listing["result"]["routes"]],
            [(2702, "rejected")],
        )
        code, source_listing = self.invoke(
            "routes", "--status", "all", "--source-pr", "2702", "--database", str(self.database)
        )
        self.assertEqual(code, 0)
        self.assertEqual([route["source_pr"] for route in source_listing["result"]["routes"]], [2702])

    def test_history_exposes_only_sqlite_failed_cli_attempt_without_counting_result(self) -> None:
        self.invoke("bootstrap", "--database", str(self.database))
        root = self.database.parent / "pr-review" / "runs"
        failed = root / ("run." + "a" * 32)
        successful = root / ("run." + "b" * 32)
        for directory in (failed, successful):
            directory.mkdir(parents=True)
            (directory / "metadata.json").write_text(json.dumps({"pull_request": 2879}))
        (failed / "exit-status").write_text("1\n")
        (failed / "stderr").write_text("Error: Rate limit exceeded; private provider details")
        (successful / "exit-status").write_text("0\n")

        records = SqliteReviewRecords(self.database)
        records.start_attempt(attempt_id=failed.name, source_pr=2879, channel="cli")
        records.finish_attempt(failed.name, state="rate_limited", diagnostic="provider rate limit")

        code, response = self.invoke("history", "--pr", "2879", "--database", str(self.database))

        self.assertEqual(code, 0)
        attempts = response["result"]["cli_attempts"]
        self.assertTrue(attempts["available"])
        self.assertEqual(1, len(attempts["attempts"]))
        self.assertEqual("rate_limited", attempts["attempts"][0]["outcome"])
        self.assertEqual(failed.name, attempts["attempts"][0]["run_id"])
        self.assertNotIn("private provider details", str(response))

        # Old capture directories are migration input, never a live history source.
        (failed / "exit-status").unlink()
        code, after = self.invoke("history", "--pr", "2879", "--database", str(self.database))
        self.assertEqual(code, 0)
        self.assertEqual(after["result"]["cli_attempts"], attempts)

    def test_records_migrate_durably_imports_legacy_failed_cli_attempts(self) -> None:
        self.invoke("bootstrap", "--database", str(self.database))
        run_id = "run." + "7" * 32
        capture = self.database.parent / "pr-review" / "runs" / run_id
        capture.mkdir(parents=True)
        (capture / "metadata.json").write_text(
            json.dumps({"run_id": run_id, "pull_request": 2881, "candidate_sha": "a" * 40}),
            encoding="utf-8",
        )
        (capture / "exit-status").write_text("1\n", encoding="utf-8")
        (capture / "stderr").write_text("Rate limit exceeded; private provider details", encoding="utf-8")

        code, migrated = self.invoke("migrate", "--database", str(self.database))
        self.assertEqual(code, 0)
        reconciliation = migrated["result"]["legacy_cli_attempts"]
        self.assertEqual(reconciliation["imported"], [{"run_id": run_id, "pr": "2881"}])
        self.assertEqual(reconciliation["already_imported"], [])
        self.assertEqual(reconciliation["conflicts"], [])
        self.assertNotIn("private provider details", str(migrated))

        records = SqliteReviewRecords(self.database)
        history = records.history(2881)
        self.assertEqual(history["runs"], [])
        self.assertEqual(len(history["attempts"]), 1)
        self.assertEqual(history["attempts"][0]["state"], "rate_limited")
        self.assertEqual(history["attempts"][0]["attempt_id"], run_id)
        imported = records.attempt(run_id)
        self.assertEqual(imported["metadata"]["origin"], "legacy_private_capture")
        self.assertEqual(imported["metadata"]["legacy_outcome"], "rate_limited")
        self.assertEqual(len(imported["metadata"]["source_fingerprint"]), 64)

        code, replayed = self.invoke("migrate", "--database", str(self.database))
        self.assertEqual(code, 0)
        self.assertEqual(replayed["result"]["legacy_cli_attempts"]["imported"], [])
        self.assertEqual(
            replayed["result"]["legacy_cli_attempts"]["already_imported"],
            [{"run_id": run_id, "pr": "2881"}],
        )

        code, single = self.invoke("history", "--pr", "2881", "--database", str(self.database))
        self.assertEqual(code, 0)
        attempt = single["result"]["cli_attempts"]["attempts"][0]
        self.assertEqual(attempt["run_id"], run_id)
        self.assertEqual(attempt["outcome"], "rate_limited")
        self.assertEqual(attempt["origin"], "legacy_private_capture")
        self.assertNotIn("private provider details", str(single))

        code, batch = self.invoke("history-batch", "--pr", "2881", "--pr", "2882", "--database", str(self.database))
        self.assertEqual(code, 0)
        batch_attempts = batch["result"]["prs"]["2881"]["cli_attempts"]["attempts"]
        self.assertEqual(batch_attempts[0]["origin"], "legacy_private_capture")
        self.assertEqual(batch["result"]["prs"]["2882"]["cli_attempts"]["attempts"], [])

    def test_legacy_failed_cli_reimport_reports_changed_source_fingerprint(self) -> None:
        self.invoke("bootstrap", "--database", str(self.database))
        run_id = "run." + "8" * 32
        capture = self.database.parent / "pr-review" / "runs" / run_id
        capture.mkdir(parents=True)
        (capture / "metadata.json").write_text(json.dumps({"pull_request": 2883}), encoding="utf-8")
        (capture / "exit-status").write_text("1\n", encoding="utf-8")
        (capture / "stderr").write_text("Rate limit exceeded", encoding="utf-8")

        _, first = self.invoke("migrate", "--database", str(self.database))
        self.assertEqual(first["result"]["legacy_cli_attempts"]["imported"][0]["run_id"], run_id)
        (capture / "stderr").write_text("Provider rejected the request", encoding="utf-8")

        code, replay = self.invoke("migrate", "--database", str(self.database))
        self.assertEqual(code, 2)
        self.assertEqual(replay["result"]["status"], "migrated_partial")
        report = replay["result"]["legacy_cli_attempts"]
        self.assertEqual(report["imported"], [])
        self.assertEqual(report["already_imported"], [])
        self.assertEqual(
            report["conflicts"],
            [
                {
                    "run_id": run_id,
                    "reason": "attempt identity or source fingerprint conflicts",
                }
            ],
        )
        history = SqliteReviewRecords(self.database).history(2883)
        self.assertEqual(len(history["attempts"]), 1)
        self.assertEqual(history["attempts"][0]["state"], "rate_limited")

    def test_records_migrate_reports_partial_when_cli_capture_reconciliation_is_unavailable(self) -> None:
        self.invoke("bootstrap", "--database", str(self.database))
        unavailable = {
            "available": False,
            "reason": "a CLI review is active; legacy captures were not reconciled",
            "imported": [],
            "already_imported": [],
            "recovered": [],
            "terminally_classified": [],
            "conflicts": [],
            "skipped": 0,
        }

        with patch.object(cli_attempts, "reconcile_legacy_failed_attempts", return_value=unavailable):
            code, migrated = self.invoke("migrate", "--database", str(self.database))

        self.assertEqual(code, 2)
        self.assertEqual(migrated["result"]["status"], "migrated_partial")
        self.assertEqual(migrated["result"]["legacy_cli_attempts"], unavailable)

    def test_migrate_recognizes_matching_terminal_native_cli_attempt(self) -> None:
        self.invoke("bootstrap", "--database", str(self.database))
        run_id = "run." + "9" * 32
        capture = self.database.parent / "pr-review" / "runs" / run_id
        capture.mkdir(parents=True)
        (capture / "metadata.json").write_text(json.dumps({"pull_request": 2884}), encoding="utf-8")
        (capture / "exit-status").write_text("1\n", encoding="utf-8")
        (capture / "stderr").write_text("Provider rejected the request", encoding="utf-8")
        records = SqliteReviewRecords(self.database)
        records.start_attempt(
            attempt_id=run_id,
            source_pr=2884,
            channel="cli",
            metadata={"source": "native-cli"},
        )
        records.finish_attempt(run_id, state="failed", diagnostic="native CLI failure")

        code, response = self.invoke("migrate", "--database", str(self.database))

        self.assertEqual(code, 0)
        reconciliation = response["result"]["legacy_cli_attempts"]
        self.assertEqual(reconciliation["imported"], [])
        self.assertEqual(reconciliation["already_imported"], [{"run_id": run_id, "pr": "2884"}])
        self.assertEqual(reconciliation["conflicts"], [])
        attempt = records.attempt(run_id)
        self.assertEqual(attempt["metadata"], {"source": "native-cli"})
        self.assertEqual(attempt["state"], "failed")

    def test_migrate_does_not_classify_native_attempt_with_mismatched_owner_or_channel(self) -> None:
        self.invoke("bootstrap", "--database", str(self.database))
        root = self.database.parent / "pr-review" / "runs"
        records = SqliteReviewRecords(self.database)
        mismatches = (("a", 2885, "cli"), ("b", 2884, "hosted"))
        for marker, source_pr, channel in mismatches:
            run_id = "run." + marker * 32
            capture = root / run_id
            capture.mkdir(parents=True)
            (capture / "metadata.json").write_text(json.dumps({"pull_request": 2884}), encoding="utf-8")
            (capture / "exit-status").write_text("1\n", encoding="utf-8")
            (capture / "stderr").write_text("Provider rejected the request", encoding="utf-8")
            records.start_attempt(
                attempt_id=run_id,
                source_pr=source_pr,
                channel=channel,
                metadata={"source": "native-cli"},
            )
            records.finish_attempt(run_id, state="failed", diagnostic="native failure")

        code, response = self.invoke("migrate", "--database", str(self.database))

        self.assertEqual(code, 2)
        self.assertEqual(response["result"]["status"], "migrated_partial")
        reconciliation = response["result"]["legacy_cli_attempts"]
        self.assertEqual(reconciliation["already_imported"], [])
        self.assertEqual(reconciliation["imported"], [])
        self.assertEqual(
            {item["run_id"] for item in reconciliation["conflicts"]},
            {"run." + "a" * 32, "run." + "b" * 32},
        )

    def test_migrate_finishes_native_started_attempt_from_exact_failed_capture(self) -> None:
        self.invoke("bootstrap", "--database", str(self.database))
        run_id = "run." + "2" * 32
        started_metadata = {
            "run_id": run_id,
            "kind": "cli",
            "capture_completion_marker": "capture-complete",
            "pull_request": 2885,
            "candidate_sha": "d" * 40,
            "candidate_files": 3,
        }
        records = SqliteReviewRecords(self.database)
        records.start_attempt(
            attempt_id=run_id,
            source_pr=2885,
            channel="cli",
            candidate_sha="d" * 40,
            started_at="2026-09-29T01:00:00Z",
            metadata=started_metadata,
        )
        capture = self.database.parent / "pr-review" / "runs" / run_id
        capture.mkdir(parents=True)
        final_metadata = {**started_metadata, "duration_seconds": 7, "exit_status": 23}
        (capture / "metadata.json").write_text(json.dumps(final_metadata), encoding="utf-8")
        (capture / "exit-status").write_text("23\n", encoding="utf-8")
        (capture / "stderr").write_text(
            "Rate limit exceeded; " + "diagnostic " * 1000,
            encoding="utf-8",
        )
        (capture / "review-duration-seconds").write_text("7\n", encoding="utf-8")
        (capture / "capture-complete").write_text(f"{run_id}\n", encoding="utf-8")
        completed_epoch = 1_790_700_000
        os.utime(capture / "exit-status", (completed_epoch, completed_epoch))

        result = cli_attempts.reconcile_legacy_failed_attempts(records, self.database)

        self.assertEqual(result["terminally_classified"], [{"run_id": run_id, "pr": "2885"}])
        attempt = records.attempt(run_id)
        attempt_summary = records.attempt_history(2885)[0]
        expected_finished = (
            datetime.fromtimestamp(completed_epoch, timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z")
        )
        self.assertEqual(attempt["finished_at"], expected_finished)
        self.assertEqual(attempt["state"], "rate_limited")
        self.assertEqual(attempt_summary["exit_status"], 23)
        self.assertLessEqual(len(attempt_summary["diagnostic"]), 1000)
        self.assertTrue(attempt_summary["diagnostic"].startswith("CodeRabbit CLI was rate limited:"))

        beyond_prefix_id = "run." + "4" * 32
        beyond_prefix_metadata = {**started_metadata, "run_id": beyond_prefix_id}
        records.start_attempt(
            attempt_id=beyond_prefix_id,
            source_pr=2885,
            channel="cli",
            candidate_sha="d" * 40,
            started_at="2026-09-29T01:00:00Z",
            metadata=beyond_prefix_metadata,
        )
        beyond_prefix = self.database.parent / "pr-review" / "runs" / beyond_prefix_id
        beyond_prefix.mkdir(parents=True)
        (beyond_prefix / "metadata.json").write_text(
            json.dumps({**beyond_prefix_metadata, "duration_seconds": 3, "exit_status": 1}),
            encoding="utf-8",
        )
        (beyond_prefix / "exit-status").write_text("1\n", encoding="utf-8")
        (beyond_prefix / "stderr").write_text(
            "x" * cli_attempts.MAX_STDERR_BYTES + "Rate limit exceeded",
            encoding="utf-8",
        )
        (beyond_prefix / "review-duration-seconds").write_text("3\n", encoding="utf-8")
        (beyond_prefix / "capture-complete").write_text(f"{beyond_prefix_id}\n", encoding="utf-8")

        cli_attempts.reconcile_legacy_failed_attempts(records, self.database)

        self.assertEqual(records.attempt(beyond_prefix_id)["state"], "failed")

    def test_reconciles_native_setup_error_when_sqlite_finish_failed(self) -> None:
        self.invoke("bootstrap", "--database", str(self.database))
        run_id = "run." + "5" * 32
        started_metadata = {
            "run_id": run_id,
            "kind": "cli",
            "capture_completion_marker": "capture-complete",
            "pull_request": 2885,
            "candidate_sha": "f" * 40,
            "candidate_files": 2,
        }
        records = SqliteReviewRecords(self.database)
        records.start_attempt(
            attempt_id=run_id,
            source_pr=2885,
            channel="cli",
            candidate_sha="f" * 40,
            started_at="2026-09-29T01:00:00Z",
            metadata=started_metadata,
        )
        capture = self.database.parent / "pr-review" / "runs" / run_id
        capture.mkdir(parents=True)
        (capture / "metadata.json").write_text(json.dumps(started_metadata), encoding="utf-8")
        (capture / "error").write_text("preflight failed; " + "detail " * 1000, encoding="utf-8")
        completed_epoch = 1_790_700_000
        os.utime(capture / "error", (completed_epoch, completed_epoch))

        result = cli_attempts.reconcile_legacy_failed_attempts(records, self.database)

        self.assertEqual(result["terminally_classified"], [{"run_id": run_id, "pr": "2885"}])
        attempt = records.attempt(run_id)
        self.assertEqual(attempt["state"], "failed")
        self.assertEqual(
            attempt["finished_at"],
            datetime.fromtimestamp(completed_epoch, timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z"),
        )
        attempt_summary = next(item for item in records.attempt_history(2885) if item["attempt_id"] == run_id)
        self.assertLessEqual(len(attempt_summary["diagnostic"]), 1000)
        self.assertTrue(attempt_summary["diagnostic"].startswith("CLI setup or capture failed:"))
        self.assertEqual(records.history(2885)["runs"], [])

    def test_native_capture_diagnostics_normalize_controls_before_terminal_archive(self) -> None:
        self.invoke("bootstrap", "--database", str(self.database))
        records = SqliteReviewRecords(self.database)
        root = self.database.parent / "pr-review" / "runs"
        captures = (
            ("a", "failed", "\x1b[31mprovider failed\nnext line\x1b[0m"),
            ("b", "incomplete", "\x1b[33msetup failed\nwith detail\x1b[0m"),
        )
        for marker, kind, detail in captures:
            run_id = "run." + marker * 32
            metadata = {
                "run_id": run_id,
                "kind": "cli",
                "capture_completion_marker": "capture-complete",
                "pull_request": 2885,
                "candidate_sha": marker * 40,
            }
            records.start_attempt(
                attempt_id=run_id,
                source_pr=2885,
                channel="cli",
                candidate_sha=marker * 40,
                metadata=metadata,
            )
            capture = root / run_id
            capture.mkdir(parents=True)
            (capture / "metadata.json").write_text(json.dumps(metadata), encoding="utf-8")
            if kind == "failed":
                final_metadata = {**metadata, "duration_seconds": 2, "exit_status": 1}
                (capture / "metadata.json").write_text(json.dumps(final_metadata), encoding="utf-8")
                (capture / "exit-status").write_text("1\n", encoding="utf-8")
                (capture / "stderr").write_text(detail, encoding="utf-8")
                (capture / "review-duration-seconds").write_text("2\n", encoding="utf-8")
                (capture / "capture-complete").write_text(f"{run_id}\n", encoding="utf-8")
            else:
                (capture / "error").write_text(detail, encoding="utf-8")

        result = cli_attempts.reconcile_legacy_failed_attempts(records, self.database)

        self.assertEqual(
            result["terminally_classified"],
            [{"run_id": "run." + marker * 32, "pr": "2885"} for marker, _, _ in captures],
        )
        for marker, _, detail in captures:
            attempt = records.attempt("run." + marker * 32)
            self.assertEqual(attempt["state"], "failed")
            attempt_summary = next(
                item for item in records.attempt_history(2885) if item["attempt_id"] == "run." + marker * 32
            )
            diagnostic = attempt_summary["diagnostic"]
            self.assertNotIn("\x1b", diagnostic)
            self.assertNotIn("\n", diagnostic)
            self.assertIn(detail.split("\x1b", 1)[1].split("\n", 1)[0], diagnostic)
            self.assertIn("next line" if marker == "a" else "with detail", diagnostic)

    def test_native_successful_capture_recovery_preserves_unicode_line_separators(self) -> None:
        self.invoke("bootstrap", "--database", str(self.database))
        run_id = "run." + "f" * 32
        metadata = {
            "run_id": run_id,
            "kind": "cli",
            "capture_completion_marker": "capture-complete",
            "pull_request": 2885,
            "candidate_sha": "e" * 40,
            "candidate_files": 1,
        }
        records = SqliteReviewRecords(self.database)
        records.start_attempt(
            attempt_id=run_id,
            source_pr=2885,
            channel="cli",
            candidate_sha="e" * 40,
            metadata=metadata,
        )
        capture = self.database.parent / "pr-review" / "runs" / run_id
        capture.mkdir(parents=True)
        (capture / "metadata.json").write_text(
            json.dumps({**metadata, "duration_seconds": 2, "exit_status": 0}), encoding="utf-8"
        )
        (capture / "exit-status").write_text("0\n", encoding="utf-8")
        finding_text = "Review comment at @src/Representative.java:1\nKeep\u2028the\u2029line."
        stdout = (
            json.dumps({"type": "finding", "codegenInstructions": finding_text}, ensure_ascii=False)
            + "\n"
            + json.dumps(
                {
                    "type": "complete",
                    "status": "review_completed",
                    "findings": 1,
                    "reviewedFiles": ["src/Representative.java"],
                },
                ensure_ascii=False,
            )
            + "\n"
        )
        (capture / "stdout").write_text(stdout, encoding="utf-8")
        (capture / "stderr").write_text("", encoding="utf-8")
        (capture / "review-duration-seconds").write_text("2\n", encoding="utf-8")
        (capture / "capture-complete").write_text(f"{run_id}\n", encoding="utf-8")

        result = cli_attempts.reconcile_legacy_failed_attempts(records, self.database)

        self.assertEqual(result["recovered"], [{"run_id": run_id, "pr": "2885"}])
        self.assertEqual(records.attempt(run_id)["state"], "completed")
        self.assertEqual(records.history(2885)["findings"][0]["title"], "Keep\u2028the\u2029line.")
        self.assertEqual(records.history(2885)["findings"][0]["detail"], "Keep the line.")

    def test_reconciles_old_native_attempt_killed_without_terminal_file_as_failed(self) -> None:
        self.invoke("bootstrap", "--database", str(self.database))
        run_id = "run." + "6" * 32
        started_metadata = {
            "run_id": run_id,
            "kind": "cli",
            "capture_completion_marker": "capture-complete",
            "pull_request": 2885,
            "candidate_sha": "a" * 40,
        }
        records = SqliteReviewRecords(self.database)
        records.start_attempt(
            attempt_id=run_id,
            source_pr=2885,
            channel="cli",
            candidate_sha="a" * 40,
            started_at="2026-09-28T01:00:00Z",
            metadata=started_metadata,
        )
        capture = self.database.parent / "pr-review" / "runs" / run_id
        capture.mkdir(parents=True)
        metadata_path = capture / "metadata.json"
        metadata_path.write_text(json.dumps(started_metadata), encoding="utf-8")
        started_epoch = 1_790_600_000
        os.utime(metadata_path, (started_epoch, started_epoch))

        result = cli_attempts.reconcile_legacy_failed_attempts(records, self.database)

        self.assertEqual(result["terminally_classified"], [{"run_id": run_id, "pr": "2885"}])
        attempt = records.attempt(run_id)
        self.assertEqual(attempt["state"], "failed")
        self.assertEqual(
            attempt["finished_at"],
            datetime.fromtimestamp(started_epoch, timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z"),
        )
        attempt_summary = next(item for item in records.attempt_history(2885) if item["attempt_id"] == run_id)
        self.assertIn("without a terminal capture record", attempt_summary["diagnostic"])
        self.assertEqual(records.history(2885)["runs"], [])

    def test_stale_native_failure_mtime_cannot_predate_started_attempt(self) -> None:
        self.invoke("bootstrap", "--database", str(self.database))
        run_id = "run." + "8" * 32
        started_at = "2026-09-29T01:00:00Z"
        started_metadata = {
            "run_id": run_id,
            "kind": "cli",
            "capture_completion_marker": "capture-complete",
            "pull_request": 2885,
            "candidate_sha": "c" * 40,
            "candidate_files": 1,
        }
        records = SqliteReviewRecords(self.database)
        records.start_attempt(
            attempt_id=run_id,
            source_pr=2885,
            channel="cli",
            candidate_sha="c" * 40,
            started_at=started_at,
            metadata=started_metadata,
        )
        capture = self.database.parent / "pr-review" / "runs" / run_id
        capture.mkdir(parents=True)
        final_metadata = {**started_metadata, "duration_seconds": 2, "exit_status": 1}
        (capture / "metadata.json").write_text(json.dumps(final_metadata), encoding="utf-8")
        exit_path = capture / "exit-status"
        exit_path.write_text("1\n", encoding="utf-8")
        (capture / "stderr").write_text("provider failed", encoding="utf-8")
        (capture / "review-duration-seconds").write_text("2\n", encoding="utf-8")
        (capture / "capture-complete").write_text(f"{run_id}\n", encoding="utf-8")
        os.utime(exit_path, (1_700_000_000, 1_700_000_000))

        result = cli_attempts.reconcile_legacy_failed_attempts(records, self.database)

        self.assertEqual(result["terminally_classified"], [{"run_id": run_id, "pr": "2885"}])
        attempt = records.attempt(run_id)
        self.assertEqual(attempt["state"], "failed")
        self.assertEqual(attempt["finished_at"], started_at)
        self.assertEqual(records.history(2885)["runs"], [])

    def test_stale_successful_capture_mtime_stays_non_attributable(self) -> None:
        self.invoke("bootstrap", "--database", str(self.database))
        run_id = "run." + "9" * 32
        started_at = "2026-09-29T01:00:00Z"
        started_metadata = {
            "run_id": run_id,
            "kind": "cli",
            "capture_completion_marker": "capture-complete",
            "pull_request": 2885,
            "candidate_sha": "e" * 40,
            "candidate_files": 1,
        }
        records = SqliteReviewRecords(self.database)
        records.start_attempt(
            attempt_id=run_id,
            source_pr=2885,
            channel="cli",
            candidate_sha="e" * 40,
            started_at=started_at,
            metadata=started_metadata,
        )
        capture = self.database.parent / "pr-review" / "runs" / run_id
        capture.mkdir(parents=True)
        final_metadata = {**started_metadata, "duration_seconds": 2, "exit_status": 0}
        (capture / "metadata.json").write_text(json.dumps(final_metadata), encoding="utf-8")
        exit_path = capture / "exit-status"
        exit_path.write_text("0\n", encoding="utf-8")
        (capture / "stdout").write_text(
            json.dumps({"type": "complete", "status": "review_completed", "findings": 0, "reviewedFiles": []}) + "\n",
            encoding="utf-8",
        )
        (capture / "stderr").write_text("", encoding="utf-8")
        (capture / "review-duration-seconds").write_text("2\n", encoding="utf-8")
        (capture / "capture-complete").write_text(f"{run_id}\n", encoding="utf-8")
        os.utime(exit_path, (1_700_000_000, 1_700_000_000))

        result = cli_attempts.reconcile_legacy_failed_attempts(records, self.database)

        self.assertEqual(result["terminally_classified"], [{"run_id": run_id, "pr": "2885"}])
        attempt = records.attempt(run_id)
        self.assertEqual(attempt["state"], "failed")
        self.assertEqual(attempt["finished_at"], started_at)
        self.assertEqual(records.history(2885)["runs"], [])

    def test_incomplete_native_capture_metadata_mismatch_stays_started(self) -> None:
        self.invoke("bootstrap", "--database", str(self.database))
        run_id = "run." + "7" * 32
        started_metadata = {
            "run_id": run_id,
            "kind": "cli",
            "capture_completion_marker": "capture-complete",
            "pull_request": 2885,
            "candidate_sha": "b" * 40,
        }
        records = SqliteReviewRecords(self.database)
        records.start_attempt(
            attempt_id=run_id,
            source_pr=2885,
            channel="cli",
            candidate_sha="b" * 40,
            metadata=started_metadata,
        )
        capture = self.database.parent / "pr-review" / "runs" / run_id
        capture.mkdir(parents=True)
        (capture / "metadata.json").write_text(json.dumps({**started_metadata, "pull_request": 2886}), encoding="utf-8")
        (capture / "error").write_text("setup failed\n", encoding="utf-8")

        cli_attempts.reconcile_legacy_failed_attempts(records, self.database)

        self.assertEqual(records.attempt(run_id)["state"], "started")

    def test_migrate_keeps_native_attempt_started_when_failure_capture_is_not_exact(self) -> None:
        self.invoke("bootstrap", "--database", str(self.database))
        run_id = "run." + "3" * 32
        metadata = {
            "run_id": run_id,
            "kind": "cli",
            "capture_completion_marker": "capture-complete",
            "pull_request": 2885,
            "candidate_sha": "d" * 40,
        }
        records = SqliteReviewRecords(self.database)
        records.start_attempt(
            attempt_id=run_id,
            source_pr=2885,
            channel="cli",
            candidate_sha="d" * 40,
            started_at="2026-09-29T01:00:00Z",
            metadata=metadata,
        )
        capture = self.database.parent / "pr-review" / "runs" / run_id
        capture.mkdir(parents=True)
        (capture / "metadata.json").write_text(
            json.dumps({**metadata, "pull_request": 2886, "exit_status": 1, "duration_seconds": 2}),
            encoding="utf-8",
        )
        (capture / "exit-status").write_text("1\n", encoding="utf-8")
        (capture / "stderr").write_text("provider failed", encoding="utf-8")
        (capture / "review-duration-seconds").write_text("2\n", encoding="utf-8")
        (capture / "capture-complete").write_text(f"{run_id}\n", encoding="utf-8")

        cli_attempts.reconcile_legacy_failed_attempts(records, self.database)

        self.assertEqual(records.attempt(run_id)["state"], "started")

    def test_failed_cli_attempt_reads_only_bounded_stderr_prefix_for_rate_limit_classification(self) -> None:
        root = self.database.parent / "pr-review" / "runs"
        failed = root / ("run." + "c" * 32)
        failed.mkdir(parents=True)
        (failed / "metadata.json").write_text(json.dumps({"pull_request": 2890}), encoding="utf-8")
        (failed / "exit-status").write_text("1\n", encoding="utf-8")
        stderr_path = failed / "stderr"

        class GuardedStream(io.BytesIO):
            requested_bytes: int | None = None

            def read(self, size: int = -1) -> bytes:
                self.requested_bytes = size
                if size < 0 or size > cli_attempts.MAX_STDERR_BYTES:
                    raise AssertionError("stderr read was not bounded")
                return super().read(size)

        stream = GuardedStream(b"Error: Rate limit exceeded\n" + b"x" * 1_000_000)
        stderr_path.touch()
        original_open = Path.open

        def open_stderr(path: Path, *args: object, **kwargs: object) -> object:
            if path == stderr_path:
                return stream
            return original_open(path, *args, **kwargs)

        with patch.object(Path, "open", autospec=True, side_effect=open_stderr):
            attempts = cli_attempts.failed_attempts(self.database, 2890)

        self.assertEqual(stream.requested_bytes, 4096)
        self.assertEqual(attempts["attempts"][0]["outcome"], "rate_limited")

    def test_failed_cli_attempt_reads_only_bounded_exit_status_prefix(self) -> None:
        root = self.database.parent / "pr-review" / "runs"
        failed = root / ("run." + "9" * 32)
        failed.mkdir(parents=True)
        (failed / "metadata.json").write_text(json.dumps({"pull_request": 2890}), encoding="utf-8")
        exit_path = failed / "exit-status"
        exit_path.touch()

        class GuardedStream(io.BytesIO):
            requested_bytes: int | None = None

            def read(self, size: int = -1) -> bytes:
                self.requested_bytes = size
                if size < 0 or size > 33:
                    raise AssertionError("exit-status read was not bounded")
                return super().read(size)

        stream = GuardedStream(b"1\n" + b"x" * 1_000_000)
        original_open = Path.open

        def open_exit_status(path: Path, *args: object, **kwargs: object) -> object:
            if path == exit_path:
                return stream
            return original_open(path, *args, **kwargs)

        with patch.object(Path, "open", autospec=True, side_effect=open_exit_status):
            attempts = cli_attempts.failed_attempts(self.database, 2890)

        self.assertEqual(stream.requested_bytes, 33)
        self.assertEqual(attempts["attempts"][0]["outcome"], "provider_failed")

    def test_failed_cli_attempt_ignores_symlinked_capture_files(self) -> None:
        root = self.database.parent / "pr-review" / "runs"
        target = self.database.parent / "capture-target"
        target.write_text("1\n", encoding="utf-8")
        symlinked = {
            "exit-status": "run." + "a" * 32,
            "stderr": "run." + "b" * 32,
            "error": "run." + "c" * 32,
        }
        for capture_name, run_name in symlinked.items():
            directory = root / run_name
            directory.mkdir(parents=True)
            (directory / "metadata.json").write_text(json.dumps({"pull_request": 2890}), encoding="utf-8")
            if capture_name == "stderr":
                (directory / "exit-status").write_text("1\n", encoding="utf-8")
            (directory / capture_name).symlink_to(target)

        attempts = cli_attempts.failed_attempts(self.database, 2890)

        self.assertEqual(attempts["attempts"], [])

    def test_failed_cli_attempt_metadata_is_bounded_at_the_size_limit(self) -> None:
        root = self.database.parent / "pr-review" / "runs"
        exact = root / ("run." + "d" * 32)
        oversized = root / ("run." + "e" * 32)
        exact.mkdir(parents=True)
        oversized.mkdir()
        metadata = b'{"pull_request":2890}'
        exact_bytes = metadata + b" " * (cli_attempts.MAX_METADATA_BYTES - len(metadata))
        (exact / "metadata.json").write_bytes(exact_bytes)
        (exact / "exit-status").write_text("1\n", encoding="utf-8")
        oversized_path = oversized / "metadata.json"
        oversized_path.touch()
        (oversized / "exit-status").write_text("1\n", encoding="utf-8")

        class GuardedStream(io.BytesIO):
            requested_bytes: int | None = None

            def read(self, size: int = -1) -> bytes:
                self.requested_bytes = size
                if size < 0 or size > cli_attempts.MAX_METADATA_BYTES + 1:
                    raise AssertionError("metadata read was not bounded")
                return super().read(size)

        stream = GuardedStream(exact_bytes + b" ")
        original_open = Path.open

        def open_metadata(path: Path, *args: object, **kwargs: object) -> object:
            if path == oversized_path:
                return stream
            return original_open(path, *args, **kwargs)

        with patch.object(Path, "open", autospec=True, side_effect=open_metadata):
            attempts = cli_attempts.failed_attempts(self.database, 2890)

        self.assertEqual(stream.requested_bytes, cli_attempts.MAX_METADATA_BYTES + 1)
        self.assertEqual([exact.name], [attempt["run_id"] for attempt in attempts["attempts"]])

    def test_failed_cli_attempt_ignores_symlinked_metadata(self) -> None:
        root = self.database.parent / "pr-review" / "runs"
        failed = root / ("run." + "f" * 32)
        failed.mkdir(parents=True)
        metadata_target = self.database.parent / "metadata-target.json"
        metadata_target.write_text(json.dumps({"pull_request": 2890}), encoding="utf-8")
        (failed / "metadata.json").symlink_to(metadata_target)
        (failed / "exit-status").write_text("1\n", encoding="utf-8")

        attempts = cli_attempts.failed_attempts(self.database, 2890)

        self.assertEqual(attempts["attempts"], [])

    def test_default_records_require_cutover_and_status_ignores_orphan_sibling_database(self) -> None:
        legacy_path = Path(self.temporary_directory.name) / "controller.json"
        StateStore(legacy_path).save(ReviewState())
        orphan_database = legacy_path.with_suffix(".sqlite3")
        SqliteStateStore(orphan_database).update(lambda state: state)
        SqliteReviewRecords(orphan_database).bootstrap()

        with patch.object(cli, "state_path", return_value=legacy_path):
            status, result = self.invoke("history", "--pr", "2828")
            self.assertEqual(status, 2)
            self.assertIn("has not been migrated to SQLite", result["error"])
            routes, route_state = cli._read_record_incoming_routes(2828)

        self.assertEqual(routes, [])
        self.assertEqual(route_state["status"], "not_bootstrapped")
        self.assertIn("has not been migrated to SQLite", route_state["reason"])

    def test_versioned_manual_run_readback_and_route_disposition(self) -> None:
        _, bootstrapped = self.invoke("bootstrap", "--database", str(self.database))
        self.assertEqual(bootstrapped["api_version"], 1)

        self.import_file.write_text(
            json.dumps(
                {
                    "api_version": 1,
                    "run": {
                        "run_id": "subagent-run-1",
                        "source_pr": 2700,
                        "channel": "subagent",
                        "reviewer": "security-reviewer",
                        "scope": "broad",
                        "coverage_limits": ["static inspection only"],
                        "outcome": "completed",
                    },
                    "findings": [
                        {"source_finding_key": "accept-me", "title": "Validate owner input"},
                        {"source_finding_key": "route-me", "title": "Move check to shared PR", "detail": "Wrong layer"},
                    ],
                }
            ),
            encoding="utf-8",
        )
        _, imported = self.invoke("import-run", "--input", str(self.import_file), "--database", str(self.database))
        self.assertEqual(imported["api_version"], 1)
        self.assertFalse(imported["result"]["finalized"])

        _, accepted = self.invoke(
            "source",
            "decide",
            "--run-id",
            "subagent-run-1",
            "--finding-key",
            "accept-me",
            "--decision-id",
            "source-accept",
            "--decision",
            "accepted",
            "--actor",
            "owner",
            "--reason",
            "valid finding",
            "--database",
            str(self.database),
        )
        self.assertEqual(accepted["result"]["decision"], "accepted")
        _, routed = self.invoke(
            "source",
            "decide",
            "--run-id",
            "subagent-run-1",
            "--finding-key",
            "route-me",
            "--decision-id",
            "source-route",
            "--decision",
            "routed",
            "--target-pr",
            "2879",
            "--actor",
            "owner",
            "--reason",
            "belongs to target",
            "--database",
            str(self.database),
        )
        route_id = routed["result"]["route_id"]
        _, finalized = self.invoke("source", "finalize", "--run-id", "subagent-run-1", "--database", str(self.database))
        self.assertEqual(finalized["result"]["counts"], {"found": 2, "accepted": 1, "routed": 1})

        _, target_decision = self.invoke(
            "route",
            "decide",
            "--route-id",
            route_id,
            "--decision-id",
            "target-decision",
            "--target-pr",
            "2879",
            "--decision",
            "accepted",
            "--actor",
            "owner",
            "--reason",
            "accepted by target owner",
            "--database",
            str(self.database),
        )
        self.assertEqual(target_decision["result"]["decision"], "accepted")
        _, resolution = self.invoke(
            "route",
            "resolve",
            "--route-id",
            route_id,
            "--resolution-id",
            "target-resolution",
            "--target-pr",
            "2879",
            "--outcome",
            "accepted_fixed",
            "--actor",
            "owner",
            "--proof-or-reason",
            "verified fix",
            "--database",
            str(self.database),
        )
        self.assertEqual(resolution["result"]["outcome"], "accepted_fixed")

        _, history = self.invoke("history", "--pr", "2700", "--database", str(self.database))
        self.assertEqual(history["api_version"], 1)
        self.assertEqual(history["result"]["runs"][0]["counts"], {"found": 2, "accepted": 1, "routed": 1})
        self.assertTrue(history["result"]["runs"][0]["finalized"])
        self.assertEqual(history["result"]["routes"][0]["resolutions"][0]["outcome"], "accepted_fixed")
        self.assertNotIn("payload", history["result"]["findings"][0])

    def test_batch_import_rejects_arbitrary_payload_and_provider_channels(self) -> None:
        _, _ = self.invoke("bootstrap", "--database", str(self.database))
        document = {
            "api_version": 1,
            "run": {
                "run_id": "manual-run",
                "source_pr": 2828,
                "channel": "manual",
                "reviewer": "reviewer",
                "scope": "narrow",
                "coverage_limits": [],
                "outcome": "completed",
            },
            "findings": [{"source_finding_key": "f-1", "title": "Finding", "payload": {"stdout": "raw"}}],
        }
        self.import_file.write_text(json.dumps(document), encoding="utf-8")
        status, result = self.invoke("import-run", "--input", str(self.import_file), "--database", str(self.database))
        self.assertEqual(status, 2)
        self.assertIn("arbitrary payload is not accepted", result["error"])

        document["run"]["channel"] = "hosted"
        document["findings"] = []
        self.import_file.write_text(json.dumps(document), encoding="utf-8")
        status, result = self.invoke("import-run", "--input", str(self.import_file), "--database", str(self.database))
        self.assertEqual(status, 2)
        self.assertIn("provider imports are not enabled", result["error"])

    def test_batch_import_bounds_bytes_read_even_if_file_grows_after_validation(self) -> None:
        class OversizedStream(io.BytesIO):
            requested_bytes: int | None = None

            def read(self, size: int = -1) -> bytes:
                self.requested_bytes = size
                return super().read(size)

        stream = OversizedStream(b"{" + b" " * 512_000)
        self.import_file.touch()
        with (
            patch.object(Path, "open", return_value=stream) as open_file,
            self.assertRaisesRegex(cli.CliError, "exceeds the 512 KB limit"),
        ):
            cli._load_records_import(str(self.import_file))

        open_file.assert_called_once_with("rb")
        self.assertEqual(stream.requested_bytes, 512_001)

    def test_complete_batch_import_decides_and_finalizes_atomically(self) -> None:
        self.invoke("bootstrap", "--database", str(self.database))
        self.import_file.write_text(
            json.dumps(
                {
                    "api_version": 1,
                    "run": {
                        "run_id": "manual-complete",
                        "source_pr": 2828,
                        "channel": "manual",
                        "reviewer": "reviewer",
                        "scope": "broad",
                        "coverage_limits": ["static inspection only"],
                        "outcome": "completed",
                    },
                    "findings": [
                        {
                            "source_finding_key": "accept-me",
                            "title": "Validate owner input",
                            "decision": "accepted",
                            "actor": "owner",
                            "reason": "valid source finding",
                        },
                        {
                            "source_finding_key": "route-me",
                            "title": "Move check to shared PR",
                            "decision": "routed",
                            "target_pr": 2879,
                            "actor": "owner",
                            "reason": "belongs to target PR",
                        },
                    ],
                }
            ),
            encoding="utf-8",
        )

        _, imported = self.invoke("import-run", "--input", str(self.import_file), "--database", str(self.database))
        self.assertTrue(imported["result"]["finalized"])
        self.assertFalse(imported["result"]["idempotent_replay"])
        self.assertEqual(imported["result"]["counts"], {"found": 2, "accepted": 1, "routed": 1})

        _, replay = self.invoke("import-run", "--input", str(self.import_file), "--database", str(self.database))
        self.assertTrue(replay["result"]["idempotent_replay"])
        _, history = self.invoke("history", "--pr", "2828", "--database", str(self.database))
        self.assertEqual(len(history["result"]["runs"]), 1)
        self.assertEqual(len(history["result"]["decisions"]), 2)

    def test_empty_incomplete_batch_is_recorded_as_unattributable(self) -> None:
        self.invoke("bootstrap", "--database", str(self.database))
        self.import_file.write_text(
            json.dumps(
                {
                    "api_version": 1,
                    "run": {
                        "run_id": "manual-empty-incomplete",
                        "source_pr": 2828,
                        "channel": "manual",
                        "reviewer": "reviewer",
                        "scope": "narrow",
                        "coverage_limits": ["review did not complete"],
                        "outcome": "incomplete",
                    },
                    "findings": [],
                }
            ),
            encoding="utf-8",
        )

        status, imported = self.invoke("import-run", "--input", str(self.import_file), "--database", str(self.database))
        self.assertEqual(status, 0)
        self.assertFalse(imported["result"]["finalized"])
        _, history = self.invoke("history", "--pr", "2828", "--database", str(self.database))
        run = history["result"]["runs"][0]
        self.assertEqual(run["outcome"], "incomplete")
        self.assertFalse(run["attributable"])

    def test_provider_import_uses_exact_parsed_checkpoint_and_keeps_capture_import_explicit(self) -> None:
        checkpoint = {
            "id": "comment-123",
            "databaseId": 123,
            "body": "Hosted: 1 found / 1 accepted / 0 routed · abcdef0\n<!-- firemud-hosted-review: 998 -->",
            "createdAt": "2026-09-28T01:02:03Z",
            "updatedAt": "2026-09-28T01:02:03Z",
            "author": {"login": "coderabbitai[bot]"},
        }
        payload = {"data": {"repository": {"pullRequest": {"number": 2828, "comments": {"nodes": [checkpoint]}}}}}
        records = type("FakeRecords", (), {"history": lambda self, pr: {"pr": pr}})()
        with (
            patch.object(cli, "_records_store", return_value=records),
            patch.object(cli.github, "infer_repo", return_value="owner/repo"),
            patch.object(cli.github, "fetch_pull_request", return_value=payload),
            patch.object(
                cli.sqlite_records_repair,
                "repair_provider_checkpoints",
                return_value={"status": "complete", "items": []},
            ) as importer,
        ):
            output = io.StringIO()
            with contextlib.redirect_stdout(output):
                status = cli.main(
                    [
                        "records",
                        "import-provider",
                        "--pr",
                        "2828",
                        "--channel",
                        "hosted",
                        "--checkpoint-id",
                        "123",
                        "--actor",
                        "operator",
                        "--scope",
                        "broad",
                        "--repo",
                        "owner/repo",
                        "--database",
                        str(self.database),
                    ]
                )
        self.assertEqual(status, 0)
        self.assertEqual(json.loads(output.getvalue())["api_version"], 1)
        args, kwargs = importer.call_args
        self.assertEqual(args, (records,))
        self.assertEqual(kwargs["repo"], "owner/repo")
        self.assertEqual(kwargs["pr_number"], 2828)
        self.assertEqual(kwargs["checkpoints"][0].comment_id, 123)
        self.assertEqual(kwargs["checkpoints"][0].hosted_review_id, 998)
        self.assertEqual(kwargs["actor"], "operator")
        self.assertEqual(kwargs["hosted_payload"], payload)
        self.assertFalse(kwargs["dry_run"])

    def test_provider_repair_previews_exact_checkpoints_and_applies_only_when_requested(self) -> None:
        first = {
            "id": "comment-123",
            "databaseId": 123,
            "body": "Hosted: 0 found / 0 accepted / 0 routed · abcdef0",
            "createdAt": "2026-09-28T01:02:03Z",
            "updatedAt": "2026-09-28T01:02:03Z",
            "author": {"login": "coderabbitai[bot]"},
        }
        second = {
            "id": "comment-124",
            "databaseId": 124,
            "body": "CLI: 1 found / 0 accepted / 1 routed · abcdef0",
            "createdAt": "2026-09-28T02:02:03Z",
            "updatedAt": "2026-09-28T02:02:03Z",
            "author": {"login": "coderabbitai[bot]"},
        }
        payload = {
            "data": {
                "repository": {
                    "pullRequest": {
                        "number": 2828,
                        "comments": {"nodes": [first, second]},
                    }
                }
            }
        }
        records = type("FakeRecords", (), {"history": lambda self, pr: {"pr": pr}})()
        with (
            patch.object(cli, "_records_store", return_value=records),
            patch.object(cli.github, "infer_repo", return_value="owner/repo"),
            patch.object(cli.github, "fetch_pull_request", return_value=payload) as fetch,
            patch.object(
                cli.sqlite_records_repair,
                "repair_provider_checkpoints",
                return_value={"status": "preview", "items": []},
            ) as repair,
        ):
            code, _ = self.invoke(
                "repair-provider",
                "--pr",
                "2828",
                "--checkpoint-id",
                "123",
                "--actor",
                "operator",
                "--scope",
                "broad",
                "--database",
                str(self.database),
            )
        self.assertEqual(code, 0)
        fetch.assert_called_once()
        self.assertEqual([item.comment_id for item in repair.call_args.kwargs["checkpoints"]], [123])
        self.assertEqual(repair.call_args.kwargs["hosted_payload"], payload)
        self.assertTrue(repair.call_args.kwargs["dry_run"])

        with (
            patch.object(cli, "_records_store", return_value=records),
            patch.object(cli.github, "infer_repo", return_value="owner/repo"),
            patch.object(cli.github, "fetch_pull_request", return_value=payload),
            patch.object(
                cli.sqlite_records_repair,
                "repair_provider_checkpoints",
                return_value={"status": "complete", "items": []},
            ) as repair,
        ):
            code, _ = self.invoke(
                "repair-provider",
                "--pr",
                "2828",
                "--all",
                "--apply",
                "--actor",
                "operator",
                "--scope",
                "broad",
                "--database",
                str(self.database),
            )
        self.assertEqual(code, 0)
        self.assertEqual([item.comment_id for item in repair.call_args.kwargs["checkpoints"]], [123, 124])
        self.assertFalse(repair.call_args.kwargs["dry_run"])

        with (
            patch.object(cli, "_records_store", return_value=records),
            patch.object(cli.github, "infer_repo", return_value="owner/repo"),
            patch.object(cli.github, "fetch_pull_request", return_value=payload),
            patch.object(
                cli.sqlite_records_repair,
                "repair_provider_checkpoints",
                return_value={"status": "preview", "items": []},
            ) as repair,
        ):
            code, result = self.invoke(
                "repair-provider",
                "--pr",
                "2828",
                "--all",
                "--continue-on-error",
                "--actor",
                "operator",
                "--scope",
                "broad",
                "--database",
                str(self.database),
            )
        self.assertEqual(code, 0)
        self.assertEqual(result["result"]["status"], "preview")
        self.assertEqual(repair.call_count, 2)
        self.assertEqual([call.kwargs["checkpoints"][0].comment_id for call in repair.call_args_list], [123, 124])

    def test_hosted_sync_returns_failure_status_for_pending_ambiguous_or_errors(self) -> None:
        self.invoke("bootstrap", "--database", str(self.database))
        reports = (
            ({"synced": [{"pr": 2893}], "pending": [{"pr": 2893}], "ambiguous": [], "errors": []}, 2),
            ({"synced": [{"pr": 2893}], "pending": [], "ambiguous": [{"pr": 2893}], "errors": []}, 2),
            ({"synced": [{"pr": 2893}], "pending": [], "ambiguous": [], "errors": [{"pr": 2893}]}, 2),
            ({"synced": [{"pr": 2893}], "pending": [], "ambiguous": [], "errors": []}, 0),
        )
        for report, expected_status in reports:
            with (
                patch.object(cli.github, "infer_repo", return_value="owner/repo"),
                patch.object(cli.sqlite_hosted_capture, "sync_hosted_pending", return_value=report) as sync,
                patch.object(cli.github, "fetch_pull_request", side_effect=AssertionError("sync helper owns evidence")),
            ):
                code, result = self.invoke("sync-hosted", "--pr", "2893", "--database", str(self.database))
            self.assertEqual(code, expected_status)
            self.assertEqual(result["result"], report)
            self.assertEqual(sync.call_args.kwargs["repo"], "owner/repo")
            self.assertEqual(sync.call_args.kwargs["pr_number"], 2893)

    def test_repair_all_archives_incomplete_old_checkpoint_without_claiming_run(self) -> None:
        checkpoint = {
            "databaseId": 123,
            "body": "Hosted: 1 found / 1 accepted / 0 routed · abcdef0",
            "createdAt": "2026-09-28T01:02:03Z",
            "updatedAt": "2026-09-28T01:02:03Z",
            "author": {"login": "ben"},
        }
        payload = {
            "data": {
                "repository": {
                    "pullRequest": {
                        "number": 2828,
                        "comments": {"nodes": [checkpoint]},
                    }
                }
            }
        }
        records = type("FakeRecords", (), {"history": lambda self, pr: {"pr": pr}})()
        output = io.StringIO()
        with (
            patch.object(cli, "_records_store", return_value=records),
            patch.object(cli.github, "infer_repo", return_value="owner/repo"),
            patch.object(cli.github, "fetch_pull_request", return_value=payload),
            patch.object(
                cli.sqlite_records_repair,
                "repair_provider_checkpoints",
                side_effect=cli.sqlite_records_repair.MissingHistoricalEvidenceError("old decisions missing"),
            ),
            patch.object(
                cli.sqlite_records_repair,
                "archive_incomplete_checkpoint",
                return_value={"status": "incomplete_preview", "checkpoint_id": 123},
            ) as gap,
            contextlib.redirect_stdout(output),
        ):
            status = cli.main(
                [
                    "records",
                    "repair-provider",
                    "--pr",
                    "2828",
                    "--all",
                    "--continue-on-error",
                    "--actor",
                    "operator",
                    "--scope",
                    "broad",
                    "--database",
                    str(self.database),
                ]
            )
        self.assertEqual(status, 2)
        result = json.loads(output.getvalue())["result"]
        self.assertEqual(result["status"], "partial")
        self.assertEqual(result["items"][0]["status"], "incomplete")
        self.assertEqual(result["items"][0]["missing_evidence"], "old decisions missing")
        self.assertTrue(gap.call_args.kwargs["dry_run"])

    def test_repair_all_reports_transient_or_conflicting_error_without_archiving_gap(self) -> None:
        checkpoint = {
            "databaseId": 123,
            "body": "Hosted: 1 found / 1 accepted / 0 routed · abcdef0",
            "createdAt": "2026-09-28T01:02:03Z",
            "updatedAt": "2026-09-28T01:02:03Z",
            "author": {"login": "ben"},
        }
        payload = {
            "data": {
                "repository": {
                    "pullRequest": {
                        "number": 2828,
                        "comments": {"nodes": [checkpoint]},
                    }
                }
            }
        }
        records = type("FakeRecords", (), {"history": lambda self, pr: {"pr": pr}})()
        for error in (
            "provider origin-link API is unavailable",
            "matching provider attempt conflicts with captured finding or decision evidence",
        ):
            for apply in (False, True):
                with self.subTest(error=error, apply=apply):
                    output = io.StringIO()
                    arguments = [
                        "records",
                        "repair-provider",
                        "--pr",
                        "2828",
                        "--all",
                        "--continue-on-error",
                        "--actor",
                        "operator",
                        "--scope",
                        "broad",
                        "--database",
                        str(self.database),
                    ]
                    if apply:
                        arguments.append("--apply")
                    with (
                        patch.object(cli, "_records_store", return_value=records),
                        patch.object(cli.github, "infer_repo", return_value="owner/repo"),
                        patch.object(cli.github, "fetch_pull_request", return_value=payload),
                        patch.object(
                            cli.sqlite_records_repair,
                            "repair_provider_checkpoints",
                            side_effect=cli.sqlite_records_repair.SqliteRecordsRepairError(error),
                        ),
                        patch.object(cli.sqlite_records_repair, "archive_incomplete_checkpoint") as gap,
                        contextlib.redirect_stdout(output),
                    ):
                        status = cli.main(arguments)
                    self.assertEqual(status, 2)
                    result = json.loads(output.getvalue())["result"]
                    self.assertEqual(result["status"], "partial")
                    self.assertEqual(result["items"][0]["status"], "unavailable")
                    self.assertEqual(result["items"][0]["error"], error)
                    self.assertNotIn("gap", result["items"][0])
                    gap.assert_not_called()

    def test_provider_import_requires_explicit_schema_bootstrap_before_reading_provider_state(self) -> None:
        with patch.object(cli.github, "fetch_pull_request") as fetch:
            status, result = self.invoke(
                "import-provider",
                "--pr",
                "2828",
                "--channel",
                "hosted",
                "--checkpoint-id",
                "123",
                "--actor",
                "operator",
                "--scope",
                "broad",
                "--repo",
                "owner/repo",
                "--database",
                str(self.database),
            )
        self.assertEqual(status, 2)
        self.assertIn("schema is not bootstrapped", result["error"])
        fetch.assert_not_called()

    def test_migrated_legacy_routes_are_read_through_and_keep_controller_dispositions_authoritative(self) -> None:
        legacy_path = Path(self.temporary_directory.name) / "legacy-review-state.json"
        database = legacy_path.with_suffix(".sqlite3")
        incoming = FindingRoute(
            source_pr=2600,
            source_channel="hosted",
            source_review="summary:review:901",
            source_finding="duplicate:ref:shared-check",
            observations=("belongs to the shared owner",),
            target_pr=2879,
        )
        unassigned = FindingRoute(
            source_pr=2601,
            source_channel="cli",
            source_review="legacy-cli-review",
            source_finding="legacy-unassigned-check",
            observations=("owner is not known yet",),
        )
        disposition = SummaryFindingDisposition(
            pr=2600,
            head="a" * 40,
            source="review",
            summary_id=901,
            kind="duplicate",
            count=1,
            decision="routed",
            reason="owned by the shared change",
            route_ids=(incoming.route_id,),
        )
        original_state = ReviewState(
            summary_dispositions=(disposition,),
            routes=(incoming, unassigned),
        )
        StateStore(legacy_path).save(original_state)
        migrated = SqliteStateStore.migrate_legacy_json(legacy_path, database)
        records = SqliteReviewRecords(database)
        records.bootstrap()
        records.record_run(
            run_id="new-structured-route",
            source_pr=2700,
            channel="manual",
            findings=(FindingObservation("structured-key", "A separate structured route", "routed", target_pr=2879),),
        )

        _, target_routes = self.invoke("routes", "--target-pr", "2879", "--database", str(database))
        target_items = target_routes["result"]["routes"]
        structured_target = next(item for item in target_items if item["origin"] == "review_records")
        self.assertEqual(
            {item["route_id"] for item in target_items},
            {incoming.route_id, structured_target["route_id"]},
        )
        legacy_target = next(item for item in target_items if item["origin"] == "legacy_controller")
        self.assertEqual(legacy_target["route_id"], incoming.route_id)
        self.assertEqual(legacy_target["source_review"], incoming.source_review)
        self.assertEqual(legacy_target["source_finding"], incoming.source_finding)

        _, unassigned_routes = self.invoke("routes", "--unassigned", "--database", str(database))
        unassigned_items = unassigned_routes["result"]["routes"]
        self.assertEqual([item["route_id"] for item in unassigned_items], [unassigned.route_id])
        self.assertEqual(unassigned_items[0]["origin"], "legacy_controller")

        _, history = self.invoke("history", "--pr", "2600", "--database", str(database))
        history_result = history["result"]
        self.assertEqual(history_result["runs"], [])
        self.assertEqual(history_result["findings"], [])
        self.assertNotIn("taper", history_result)
        self.assertEqual(history_result["routes"][0]["route_id"], incoming.route_id)
        self.assertEqual(history_result["routes"][0]["origin"], "legacy_controller")

        controller = ReviewController(store=migrated)
        controller.decide_route(
            route_id=incoming.route_id,
            decision="accepted-fixed",
            proof="verified in commit " + "b" * 40,
        )
        _, after_decision = self.invoke("routes", "--target-pr", "2879", "--database", str(database))
        remaining = after_decision["result"]["routes"]
        self.assertEqual(len(remaining), 1)
        self.assertEqual(remaining[0]["origin"], "review_records")
        _, resolved_routes = self.invoke(
            "routes", "--status", "resolved", "--source-pr", "2600", "--database", str(database)
        )
        resolved_items = resolved_routes["result"]["routes"]
        self.assertEqual([item["route_id"] for item in resolved_items], [incoming.route_id])
        self.assertEqual(resolved_items[0]["origin"], "legacy_controller")
        self.assertEqual(resolved_items[0]["status"], "accepted_fixed")
        _, all_target_routes = self.invoke(
            "routes", "--status", "all", "--target-pr", "2879", "--database", str(database)
        )
        all_target_items = all_target_routes["result"]["routes"]
        self.assertEqual(
            {item["route_id"] for item in all_target_items},
            {incoming.route_id, structured_target["route_id"]},
        )
        _, closed_history = self.invoke("history", "--pr", "2600", "--database", str(database))
        closed_route = closed_history["result"]["routes"][0]
        self.assertEqual(closed_route["route_id"], incoming.route_id)
        self.assertEqual(closed_route["status"], "accepted_fixed")
        self.assertEqual(closed_route["proof"], "verified in commit " + "b" * 40)
        current_state = migrated.load()
        self.assertEqual(current_state.summary_dispositions, original_state.summary_dispositions)
        self.assertEqual(current_state.summary_dispositions[0].route_ids, (incoming.route_id,))

    def test_selected_status_adds_sqlite_routes_without_replacing_legacy_route_checks(self) -> None:
        records = SqliteReviewRecords(self.database)
        records.bootstrap()
        records.record_run(
            run_id="subagent-route",
            source_pr=2700,
            channel="subagent",
            findings=(FindingObservation("sqlite-finding", "Independent route"),),
        )
        records.record_source_decision(
            "subagent-route",
            "sqlite-finding",
            decision_id="sqlite-source-route",
            decision="routed",
            target_pr=2879,
            actor="reviewer",
            reason="owned by selected PR",
        )

        legacy_route = {
            "route_id": "legacy-json-route",
            "source_pr": 2600,
            "source_channel": "hosted",
            "source_review": "old-review",
            "source_finding": "legacy-finding",
            "observations": ["kept in the legacy state store"],
        }
        controller = type(
            "FakeController",
            (),
            {
                "store": SqliteStateStore(self.database),
                "status_for_pr": lambda self, pr: {
                    "prs": [
                        {
                            "pr": pr,
                            "incoming_routes": [legacy_route],
                            "routes_out": [],
                            "reconciliation": "COHERENT",
                            "channels": {"hosted": "COMPLETE", "cli": "COMPLETE"},
                            "allocations": {},
                            "head": "a" * 40,
                            "base": "main",
                            "parent_head": "b" * 40,
                        }
                    ]
                },
            },
        )()
        base_report = {
            "pr_number": 2879,
            "pull_request": {"headRefOid": "a" * 40, "baseRefName": "main", "baseRefOid": "b" * 40},
            "reasons": [],
            "ready": True,
            "verdict": "READY",
            "mergeability": {"clean": True, "diagnosis": "READY"},
        }
        cutover_state_path = Path(self.temporary_directory.name) / "cutover-state"
        cutover_state_path.mkdir()
        output = io.StringIO()
        with (
            patch.object(cli, "default_controller", return_value=controller),
            patch.object(cli.status_module, "status", return_value=base_report.copy()),
            patch.object(cli, "state_path", return_value=cutover_state_path),
            patch.object(cli, "_records_database_path", return_value=self.database),
            contextlib.redirect_stdout(output),
        ):
            status = cli.main(["status", "--pr", "2879", "--json"])
        self.assertEqual(status, 0)
        report = json.loads(output.getvalue())
        self.assertEqual(report["incoming_routes"], [legacy_route])
        self.assertEqual(len(report["incoming_record_routes"]), 1)
        self.assertEqual(report["incoming_record_routes"][0]["source_pr"], 2700)
        self.assertEqual(report["record_route_store"]["status"], "available")
        self.assertFalse(report["ready"])
        self.assertTrue(any("SQLite-record incoming route" in reason for reason in report["reasons"]))

    def test_selected_status_deduplicates_legacy_route_shadows_before_filtering(self) -> None:
        retargeted = FindingRoute(
            source_pr=2600,
            source_channel="hosted",
            source_review="summary:review:901",
            source_finding="duplicate:ref:retargeted-shadow",
            observations=("originally owned by this PR",),
            target_pr=2879,
        )
        resolved = FindingRoute(
            source_pr=2600,
            source_channel="hosted",
            source_review="summary:review:902",
            source_finding="duplicate:ref:resolved-shadow",
            observations=("originally owned by this PR",),
            target_pr=2879,
        )
        legacy_path = Path(self.temporary_directory.name) / "legacy-shadow-state.json"
        database = legacy_path.with_suffix(".sqlite3")
        StateStore(legacy_path).save(ReviewState(routes=(retargeted, resolved)))
        migrated = SqliteStateStore.migrate_legacy_json(legacy_path, database)
        records = SqliteReviewRecords(database)
        records.bootstrap()
        shadow_findings = (
            FindingObservation("retargeted-shadow-key", "Retargeted route shadow"),
            FindingObservation("resolved-shadow-key", "Resolved route shadow"),
        )
        records.import_completed_run(
            run_id="legacy-route-shadows",
            source_pr=2600,
            channel="hosted",
            findings=shadow_findings,
            source_decisions=(
                {
                    "source_finding_key": "retargeted-shadow-key",
                    "decision_id": "retargeted-shadow-decision",
                    "decision": "routed",
                    "actor": "reviewer",
                    "reason": "preserve the imported legacy route identity",
                    "target_pr": 2879,
                    "route_id": retargeted.route_id,
                    "route_status": "open",
                },
                {
                    "source_finding_key": "resolved-shadow-key",
                    "decision_id": "resolved-shadow-decision",
                    "decision": "routed",
                    "actor": "reviewer",
                    "reason": "preserve the imported legacy route identity",
                    "target_pr": 2879,
                    "route_id": resolved.route_id,
                    "route_status": "open",
                },
            ),
        )
        self.assertEqual(
            {route["route_id"] for route in records.open_routes(target_pr=2879)},
            {retargeted.route_id, resolved.route_id},
        )

        controller = ReviewController(store=migrated)
        controller.decide_route(
            route_id=retargeted.route_id,
            decision="retargeted",
            target_pr=2880,
            reason="the receiving owner changed",
        )
        controller.decide_route(
            route_id=resolved.route_id,
            decision="accepted-fixed",
            proof="verified fix in the receiving owner",
        )

        _, open_old_target = self.invoke("routes", "--target-pr", "2879", "--database", str(database))
        self.assertEqual(open_old_target["result"]["routes"], [])
        _, resolved_old_target = self.invoke(
            "routes", "--status", "resolved", "--target-pr", "2879", "--database", str(database)
        )
        self.assertEqual([route["route_id"] for route in resolved_old_target["result"]["routes"]], [resolved.route_id])
        self.assertEqual(resolved_old_target["result"]["routes"][0]["origin"], "legacy_controller")
        _, retargeted_routes = self.invoke(
            "routes",
            "--status",
            "all",
            "--target-pr",
            "2880",
            "--source-pr",
            "2600",
            "--database",
            str(database),
        )
        self.assertEqual([route["route_id"] for route in retargeted_routes["result"]["routes"]], [retargeted.route_id])
        self.assertEqual(retargeted_routes["result"]["routes"][0]["origin"], "legacy_controller")

        cutover_state_path = Path(self.temporary_directory.name) / "cutover-shadow-state"
        cutover_state_path.mkdir()
        with (
            patch.object(cli, "state_path", return_value=cutover_state_path),
            patch.object(cli, "_records_database_path", return_value=database),
        ):
            incoming, state = cli._read_record_incoming_routes(2879)
        self.assertEqual(state["status"], "available")
        self.assertEqual(incoming, [])


if __name__ == "__main__":
    unittest.main()
