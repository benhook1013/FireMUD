import dataclasses
import hashlib
import json
import sqlite3
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).parents[1]))

from pr_review.sqlite_review_records import (
    FindingObservation,
    ReviewRecordsError,
    SqliteReviewRecords,
    _archive_artifact,
)
from pr_review.sqlite_store import SQLITE_SCHEMA_VERSION, WRITER_BUILD, SqliteStateStore
from pr_review.state import FindingRoute


class SqliteReviewRecordsTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary_directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary_directory.cleanup)
        self.database = Path(self.temporary_directory.name) / "controller.sqlite3"
        SqliteStateStore(self.database).update(lambda state: state)
        self.records = SqliteReviewRecords(self.database)

    def bootstrap(self) -> None:
        self.records.bootstrap()

    @staticmethod
    def observation(
        key: str,
        disposition: str = "unresolved",
        *,
        target_pr: int | None = None,
    ) -> FindingObservation:
        return FindingObservation(
            source_finding_key=key,
            title=f"Finding {key}",
            disposition=disposition,
            target_pr=target_pr,
        )

    def test_bootstrap_is_explicit_and_preserves_controller_schema_version(self) -> None:
        with sqlite3.connect(self.database) as connection:
            before = connection.execute("PRAGMA user_version").fetchone()[0]
            self.assertEqual(
                connection.execute(
                    "SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'review_runs'"
                ).fetchone(),
                None,
            )

        self.bootstrap()

        with sqlite3.connect(self.database) as connection:
            self.assertEqual(connection.execute("PRAGMA user_version").fetchone()[0], before)
            self.assertEqual(before, SQLITE_SCHEMA_VERSION)
            self.assertIsNotNone(
                connection.execute(
                    "SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'review_runs'"
                ).fetchone()
            )

    def test_bootstrap_raises_controller_writer_fence(self) -> None:
        with sqlite3.connect(self.database) as connection:
            connection.execute("UPDATE controller_metadata SET min_writer_build = 2")

        old_writer = SqliteStateStore(self.database, writer_build=2)
        self.assertEqual(old_writer.status()["min_writer_build"], 2)
        self.bootstrap()

        self.assertEqual(SqliteStateStore(self.database).status()["min_writer_build"], WRITER_BUILD)
        with self.assertRaisesRegex(Exception, f"requires writer build {WRITER_BUILD}"):
            old_writer.update(lambda state: state)

    def test_archived_json_rejects_non_finite_numbers(self) -> None:
        for kind, content in (
            ("cli_events", '{"type":"finding","score":NaN}\n'),
            ("hosted_review", '{"score":Infinity}'),
        ):
            with self.subTest(kind=kind), self.assertRaisesRegex(ReviewRecordsError, "JSON"):
                _archive_artifact(kind, content)

    def test_attempt_write_and_read_database_errors_are_wrapped(self) -> None:
        self.bootstrap()
        with patch.object(
            self.records, "_write_connection", side_effect=sqlite3.OperationalError("write failed")
        ), self.assertRaisesRegex(ReviewRecordsError, "start_attempt") as raised:
            self.records.start_attempt(
                attempt_id="attempt-write-error", source_pr=2890, channel="cli"
            )
        self.assertIsInstance(raised.exception.__cause__, sqlite3.OperationalError)

        with patch.object(
            self.records, "_connect", side_effect=sqlite3.DatabaseError("read failed")
        ), self.assertRaisesRegex(ReviewRecordsError, "attempt_history") as raised:
            self.records.attempt_history(2890)
        self.assertIsInstance(raised.exception.__cause__, sqlite3.DatabaseError)

    def test_attempt_archives_complete_json_events_and_redacts_credentials(self) -> None:
        self.bootstrap()
        start = self.records.start_attempt(
            attempt_id="run.archive-1", source_pr=2890, channel="cli", candidate_sha="a" * 40,
            started_at="2026-09-29T01:00:00Z", metadata={"published_head": "a" * 40},
        )
        self.assertFalse(start["idempotent_replay"])
        self.assertTrue(self.records.start_attempt(
            attempt_id="run.archive-1", source_pr=2890, channel="cli", candidate_sha="a" * 40,
            started_at="2026-09-29T01:00:00Z", metadata={"published_head": "a" * 40},
        )["idempotent_replay"])
        code_identifier = "ReviewCandidateImmutablePublicationBindingForExactHead" * 2
        events = json.dumps({
            "type": "finding",
            "body": "token=ghp_" + "A" * 30,
            "symbol": code_identifier,
            "summary": "The complete non-secret review explanation remains available.",
            "password": "p",
            "api_key": "k1",
            "nested": {"access_token": "t"},
        }) + "\n"
        finish_args = {
            "state": "completed", "finished_at": "2026-09-29T01:01:00Z",
            "duration_seconds": 60, "exit_status": 0,
            "artifacts": {"cli_events": events, "cli_diagnostic": "provider connected"},
        }
        self.assertFalse(self.records.finish_attempt("run.archive-1", **finish_args)["idempotent_replay"])
        self.assertTrue(self.records.finish_attempt("run.archive-1", **finish_args)["idempotent_replay"])
        with sqlite3.connect(self.database) as connection:
            archived = connection.execute(
                "SELECT content, source_sha256, redactions FROM review_artifacts "
                "WHERE attempt_id = 'run.archive-1' AND kind = 'cli_events'"
            ).fetchone()
        archived_event = json.loads(archived[0])
        self.assertEqual(archived_event["body"], "token=[redacted credential]")
        self.assertEqual(archived_event["password"], "[redacted credential]")
        self.assertEqual(archived_event["api_key"], "[redacted credential]")
        self.assertEqual(archived_event["nested"]["access_token"], "[redacted credential]")
        self.assertEqual(archived_event["symbol"], code_identifier)
        self.assertEqual(archived_event["summary"], "The complete non-secret review explanation remains available.")
        self.assertEqual(archived[2], 4)
        self.assertEqual(archived[1], hashlib.sha256(events.encode("utf-8")).hexdigest())
        self.assertEqual(self.records.attempt_history(2890)[0]["state"], "completed")
        with self.assertRaisesRegex(ReviewRecordsError, "different content"):
            self.records.finish_attempt("run.archive-1", **{**finish_args, "duration_seconds": 61})

    def test_omitted_attempt_timestamps_are_ignored_only_for_exact_replays(self) -> None:
        self.bootstrap()
        attempt_id = "run.omitted-timestamps"
        first_start = self.records.start_attempt(
            attempt_id=attempt_id,
            source_pr=2890,
            channel="cli",
            candidate_sha="a" * 40,
            metadata={"published_head": "a" * 40},
        )
        self.assertFalse(first_start["idempotent_replay"])
        stored_start = self.records.attempt(attempt_id)["started_at"]
        self.assertTrue(
            self.records.start_attempt(
                attempt_id=attempt_id,
                source_pr=2890,
                channel="cli",
                candidate_sha="a" * 40,
                metadata={"published_head": "a" * 40},
            )["idempotent_replay"]
        )
        self.assertEqual(self.records.attempt(attempt_id)["started_at"], stored_start)
        with self.assertRaisesRegex(ReviewRecordsError, "different content or is terminal"):
            self.records.start_attempt(
                attempt_id=attempt_id,
                source_pr=2890,
                channel="cli",
                candidate_sha="a" * 40,
                started_at="2026-09-29T01:00:00Z",
                metadata={"published_head": "a" * 40},
            )

        first_finish = self.records.finish_attempt(
            attempt_id, state="failed", diagnostic="provider closed"
        )
        self.assertFalse(first_finish["idempotent_replay"])
        stored_finish = self.records.attempt(attempt_id)["finished_at"]
        self.assertTrue(
            self.records.finish_attempt(
                attempt_id, state="failed", diagnostic="provider closed"
            )["idempotent_replay"]
        )
        with self.assertRaisesRegex(ReviewRecordsError, "terminal attempt replay has different content"):
            self.records.finish_attempt(
                attempt_id,
                state="failed",
                finished_at="2026-09-29T02:00:00Z",
                diagnostic="provider closed",
            )
        self.assertEqual(self.records.attempt(attempt_id)["finished_at"], stored_finish)

    def test_correct_source_decision_wraps_sqlite_write_failures(self) -> None:
        self.bootstrap()
        for error, message in (
            (
                sqlite3.IntegrityError("injected constraint failure"),
                "source decision conflicts with existing immutable records",
            ),
            (sqlite3.OperationalError("injected write failure"), "cannot record SQLite source decision"),
        ):
            with self.subTest(error=type(error).__name__), patch.object(
                self.records, "_write_connection", side_effect=error
            ), self.assertRaisesRegex(ReviewRecordsError, message):
                self.records.correct_source_decision(
                    "run.correction-sql-error",
                    "cli-run:run.correction-sql-error:finding:1",
                    supersedes_id="original",
                    correction_id="correction",
                    decision="accepted",
                    actor="reviewer",
                    reason="corrected",
                )

    def test_v4_upgrade_is_atomic_and_fences_older_writers(self) -> None:
        self.bootstrap()
        with sqlite3.connect(self.database) as connection:
            connection.execute("DROP TABLE review_artifacts")
            connection.execute("DROP TABLE review_attempts")
            connection.execute("DROP TABLE source_decision_corrections")
            connection.execute("DROP TABLE provider_origins")
            connection.execute("DROP TABLE imported_artifacts")
            connection.execute("DROP TABLE historical_gap_artifacts")
            connection.execute("DROP TABLE historical_provider_gaps")
            connection.execute("UPDATE review_records_metadata SET records_schema_version = 4, min_writer_build = 2")
            connection.execute("UPDATE controller_metadata SET min_writer_build = 2")
        old_writer = SqliteStateStore(self.database, writer_build=2)
        self.assertEqual(old_writer.status()["min_writer_build"], 2)
        self.records.migrate()
        self.records.migrate()
        self.assertEqual(SqliteStateStore(self.database).status()["min_writer_build"], WRITER_BUILD)
        with self.assertRaisesRegex(Exception, f"requires writer build {WRITER_BUILD}"):
            old_writer.update(lambda state: state)
        with sqlite3.connect(self.database) as connection:
            self.assertEqual(connection.execute(
                "SELECT records_schema_version FROM review_records_metadata"
            ).fetchone()[0], 6)

    def test_v5_upgrade_preserves_attempts_and_fences_previous_writer(self) -> None:
        self.bootstrap()
        self.records.start_attempt(attempt_id="run.previous-v5", source_pr=2893, channel="cli")
        self.records.finish_attempt("run.previous-v5", state="rate_limited")
        with sqlite3.connect(self.database) as connection:
            connection.execute("DROP TABLE imported_artifacts")
            connection.execute("DROP TABLE provider_origins")
            connection.execute("DROP TABLE historical_gap_artifacts")
            connection.execute("DROP TABLE historical_provider_gaps")
            connection.execute(
                "UPDATE review_records_metadata SET records_schema_version = 5, min_writer_build = 3"
            )
            connection.execute("UPDATE controller_metadata SET min_writer_build = 3")
        self.records.migrate()
        self.records.migrate()
        self.assertEqual(self.records.attempt_history(2893)[0]["state"], "rate_limited")
        with sqlite3.connect(self.database) as connection:
            self.assertEqual(connection.execute(
                "SELECT records_schema_version FROM review_records_metadata"
            ).fetchone()[0], 6)
        with self.assertRaisesRegex(Exception, rf"requires writer build {WRITER_BUILD}\b"):
            SqliteStateStore(self.database, writer_build=3).update(lambda state: state)

    def test_current_version_migration_repairs_controller_writer_fence(self) -> None:
        self.bootstrap()
        with sqlite3.connect(self.database) as connection:
            connection.execute("UPDATE controller_metadata SET min_writer_build = 2")

        old_writer = SqliteStateStore(self.database, writer_build=2)
        self.assertEqual(old_writer.status()["min_writer_build"], 2)
        self.records.migrate()

        self.assertEqual(SqliteStateStore(self.database).status()["min_writer_build"], WRITER_BUILD)
        with self.assertRaisesRegex(Exception, f"requires writer build {WRITER_BUILD}"):
            old_writer.update(lambda state: state)

    def test_history_batch_uses_one_read_snapshot_across_prs(self) -> None:
        self.bootstrap()
        with sqlite3.connect(self.database) as connection:
            self.assertEqual(connection.execute("PRAGMA journal_mode=WAL").fetchone()[0], "wal")
        original_history = self.records.history

        def read_then_write(pr, **kwargs):
            selected = original_history(pr, **kwargs)
            if pr == 2890:
                self.records.record_run(
                    run_id="later-run", source_pr=2893, channel="subagent",
                    findings=(), started_at="2026-09-29T01:00:00Z",
                    finished_at="2026-09-29T01:01:00Z",
                )
            return selected

        with patch.object(self.records, "history", side_effect=read_then_write):
            batch = self.records.history_batch((2890, 2893))
        self.assertEqual(batch[2893]["runs"], [])
        self.assertEqual(len(self.records.history(2893)["runs"]), 1)

    def test_provider_origin_is_durable_idempotent_and_conflict_checked(self) -> None:
        self.bootstrap()
        self.records.import_completed_run(
            run_id="provider-clean-1", source_pr=2893, channel="hosted",
            findings=(), source_decisions=(), reviewer="CodeRabbit Hosted",
            started_at="2026-09-29T01:00:00Z", finished_at="2026-09-29T01:01:00Z",
        )
        origin = {
            "repository": "BenHook1013/FireMUD", "source_pr": 2893,
            "channel": "hosted", "provider_id": "trigger:987654321",
            "checkpoint_id": 123456789, "checkpoint_fingerprint": "a" * 64,
            "run_id": "provider-clean-1",
        }
        self.assertFalse(self.records.link_provider_origin(**origin)["idempotent_replay"])
        self.assertTrue(self.records.link_provider_origin(**origin)["idempotent_replay"])
        with self.assertRaisesRegex(ReviewRecordsError, "conflicts"):
            self.records.link_provider_origin(**{**origin, "checkpoint_id": 123456790})
        with self.assertRaisesRegex(ReviewRecordsError, "conflicts"):
            self.records.link_provider_origin(**{**origin, "checkpoint_fingerprint": "b" * 64})
        with self.assertRaisesRegex(ReviewRecordsError, "conflicts"):
            self.records.link_provider_origin(**{
                **origin, "provider_id": "trigger:987654322", "checkpoint_id": 123456791,
                "checkpoint_fingerprint": "c" * 64,
            })
        artifact = {"hosted_comments": json.dumps({"reply": "Full review finished", "symbol": "X" * 60})}
        self.assertFalse(self.records.archive_imported_artifacts("provider-clean-1", artifact)["idempotent_replay"])
        self.assertTrue(self.records.archive_imported_artifacts("provider-clean-1", artifact)["idempotent_replay"])
        with self.assertRaisesRegex(ReviewRecordsError, "conflict"):
            self.records.archive_imported_artifacts(
                "provider-clean-1", {"hosted_comments": json.dumps({"reply": "different"})}
            )
        self.assertEqual(self.records.history(2893)["provider_origins"][0]["repository"],
                         "benhook1013/firemud")
        self.assertEqual(self.records.history(2893)["imported_artifacts"][0]["kind"], "hosted_comments")

    def test_provider_origin_sqlite_failures_are_wrapped(self) -> None:
        self.bootstrap()
        self.records.import_completed_run(
            run_id="provider-sql-error", source_pr=2893, channel="hosted",
            findings=(), source_decisions=(), reviewer="CodeRabbit Hosted",
            started_at="2026-09-29T01:00:00Z", finished_at="2026-09-29T01:01:00Z",
        )
        with (
            patch.object(
                self.records,
                "_write_connection",
                side_effect=sqlite3.OperationalError("synthetic SQLite failure"),
            ),
            self.assertRaisesRegex(ReviewRecordsError, "cannot link provider origin"),
        ):
            self.records.link_provider_origin(
                repository="BenHook1013/FireMUD", source_pr=2893, channel="hosted",
                provider_id="trigger:987654323", checkpoint_id=123456792,
                checkpoint_fingerprint="d" * 64, run_id="provider-sql-error",
            )

    def test_historical_gap_preserves_evidence_without_run_and_can_be_superseded(self) -> None:
        self.bootstrap()
        self.records.record_historical_gap(
            repository="benhook1013/firemud", source_pr=2894, channel="cli",
            checkpoint_id=123456790, checkpoint_fingerprint="d" * 64,
            checkpoint={"comment_id": 123456790, "body": "Only surviving source"},
            artifacts={}, missing_reason="Original CLI event capture unavailable",
        )
        self.assertEqual(self.records.history(2894)["historical_gap_artifacts"], [])
        gap = {
            "repository": "BenHook1013/FireMUD", "source_pr": 2893, "channel": "hosted",
            "checkpoint_id": 123456789, "checkpoint_fingerprint": "a" * 64,
            "checkpoint": {"body": "Review checkpoint", "comment_id": 123456789},
            "artifacts": {"hosted_comments": json.dumps({
                "body": "finding visible", "credential": "Bearer synthetic-secret-value",
            })},
            "missing_reason": "Private decision capture never existed",
        }
        self.assertFalse(self.records.record_historical_gap(**gap)["idempotent_replay"])
        self.assertTrue(self.records.record_historical_gap(**gap)["idempotent_replay"])
        history = self.records.history(2893)
        self.assertEqual(history["runs"], [])
        self.assertEqual(history["attempts"], [])
        self.assertEqual(history["historical_gaps"][0]["checkpoint"], gap["checkpoint"])
        self.assertEqual(history["historical_gaps"][0]["missing_reason"], gap["missing_reason"])
        self.assertIsNone(history["historical_gaps"][0]["superseded_by_run_id"])
        self.assertNotIn("content", history["historical_gap_artifacts"][0])
        self.assertEqual(history["historical_gap_artifacts"][0]["kind"], "hosted_comments")
        self.assertEqual(history["historical_gap_artifacts"][0]["redactions"], 1)
        with self.assertRaisesRegex(ReviewRecordsError, "conflicts"):
            self.records.record_historical_gap(**{**gap, "missing_reason": "Different reason"})
        with self.assertRaisesRegex(ReviewRecordsError, "conflicts"):
            self.records.record_historical_gap(**{**gap, "artifacts": {
                "hosted_comments": '{"body":"different"}',
            }})
        with self.assertRaisesRegex(ReviewRecordsError, "size limit"):
            self.records.record_historical_gap(**{**gap, "artifacts": {
                "hosted_review": "x" * (4 * 1024 * 1024 + 1),
            }})
        self.records.import_completed_run(
            run_id="recovered-run", source_pr=2893, channel="hosted",
            findings=(), source_decisions=(), reviewer="CodeRabbit Hosted",
            started_at="2026-09-29T01:00:00Z", finished_at="2026-09-29T01:01:00Z",
        )
        with self.assertRaisesRegex(ReviewRecordsError, "historical checkpoint evidence"):
            self.records.link_provider_origin(
                repository=gap["repository"], source_pr=2893, channel="hosted",
                provider_id="trigger:987654321", checkpoint_id=gap["checkpoint_id"],
                checkpoint_fingerprint="c" * 64, run_id="recovered-run",
            )
        self.records.link_provider_origin(
            repository=gap["repository"], source_pr=2893, channel="hosted",
            provider_id="trigger:987654321", checkpoint_id=gap["checkpoint_id"],
            checkpoint_fingerprint=gap["checkpoint_fingerprint"], run_id="recovered-run",
        )
        recovered = self.records.history(2893)
        self.assertEqual(recovered["historical_gaps"][0]["superseded_by_run_id"], "recovered-run")
        self.assertEqual(len(recovered["historical_gap_artifacts"]), 1)
        with self.assertRaisesRegex(ReviewRecordsError, "attributed provider origin"):
            self.records.record_historical_gap(**gap)

    def test_cli_decision_correction_is_append_only_and_exact_prior(self) -> None:
        self.bootstrap()
        self.records.start_attempt(
            attempt_id="run.correction", source_pr=2890, channel="cli",
            started_at="2026-09-29T01:00:00Z",
        )
        self.records.finish_attempt(
            "run.correction", state="completed", finished_at="2026-09-29T01:01:00Z",
        )
        key = "cli-run:run.correction:finding:1"
        self.records.record_run(
            run_id="run.correction", source_pr=2890, channel="cli",
            findings=(self.observation(key),),
            started_at="2026-09-29T01:00:00Z", finished_at="2026-09-29T01:01:00Z",
        )
        self.records.link_attempt_run("run.correction", "run.correction")
        self.records.record_source_decision(
            "run.correction", key, decision_id="original-decision", decision="rejected",
            actor="reviewer", reason="initial reading",
        )
        with self.assertRaisesRegex(ReviewRecordsError, "latest exact decision"):
            self.records.correct_source_decision(
                "run.correction", key, supersedes_id="wrong", correction_id="fix-1",
                decision="accepted", actor="reviewer", reason="corrected reading",
            )
        corrected = self.records.correct_source_decision(
            "run.correction", key, supersedes_id="original-decision", correction_id="fix-1",
            decision="accepted", actor="reviewer", reason="corrected reading",
        )
        self.assertEqual(corrected["counts"], {"found": 1, "accepted": 1, "routed": 0})
        self.assertEqual(self.records.cli_source_decisions("run.correction"), {1: ("accepted", "corrected reading")})
        with sqlite3.connect(self.database) as connection:
            self.assertEqual(connection.execute(
                "SELECT COUNT(*) FROM decisions WHERE run_id = 'run.correction'"
            ).fetchone()[0], 1)
            self.assertEqual(connection.execute(
                "SELECT supersedes_id FROM source_decision_corrections"
            ).fetchone()[0], "original-decision")

    def test_duplicate_source_identity_in_one_run_is_rejected_atomically(self) -> None:
        self.bootstrap()
        with self.assertRaisesRegex(ReviewRecordsError, "same stable finding"):
            self.records.record_run(
                run_id="hosted-1",
                source_pr=2828,
                channel="hosted",
                findings=(self.observation("bug-1"), self.observation("bug-1", "accepted")),
            )
        self.assertEqual(self.records.history(2828)["runs"], [])

    def test_record_run_is_exactly_idempotent_and_conflicts_do_not_create_routes(self) -> None:
        self.bootstrap()
        findings = (self.observation("stable-1"),)
        original = {
            "run_id": "manual-run-1",
            "source_pr": 2828,
            "channel": "manual",
            "findings": findings,
            "reviewer": "reviewer",
            "scope": "narrow",
            "coverage_limits": ("no runtime execution",),
            "started_at": "2026-09-28T01:02:03Z",
            "finished_at": "2026-09-28T01:03:03Z",
        }
        first = self.records.record_run(**original)
        replay = self.records.record_run(**original)
        self.assertFalse(first["idempotent_replay"])
        self.assertTrue(replay["idempotent_replay"])
        self.assertEqual(first["counts"], replay["counts"])
        with self.assertRaisesRegex(ReviewRecordsError, "different immutable content"):
            self.records.record_run(
                **{**original, "findings": (self.observation("stable-1", "routed", target_pr=2879),)}
            )
        self.assertEqual(self.records.open_routes(), [])

        with self.assertRaisesRegex(ReviewRecordsError, "credential or raw secret"):
            FindingObservation("safe-key", "title", detail="token=ghp_" + "A" * 30)

    def test_review_text_preserves_code_identifiers_and_redacts_recognizable_credentials(self) -> None:
        sha1 = "a" * 40
        sha256 = "b" * 64
        observation = FindingObservation(
            "sha-context",
            f"fixed in commit {sha1}",
            detail=f"verified against {sha256}.",
        )
        self.assertEqual(observation.title, f"fixed in commit {sha1}")
        self.assertEqual(observation.detail, f"verified against {sha256}.")

        identifiers = FindingObservation(
            "identifier-context",
            "test_v2_long_snake_case_identifier_for_review_context",
            detail="hyphenated-review-context-identifier-with-many-parts",
        )
        self.assertEqual(identifiers.title, "test_v2_long_snake_case_identifier_for_review_context")
        self.assertEqual(identifiers.detail, "hyphenated-review-context-identifier-with-many-parts")

        self.assertEqual(FindingObservation("token-context", "Z" * 40).title, "Z" * 40)
        self.assertEqual(FindingObservation("mixed-case-token", "AbCdEf0123456789" * 3).title,
                         "AbCdEf0123456789" * 3)
        for key, value in (("provider-token", f"ghp_{sha1}"),
                           ("jwt-token", "eyJabcdefgh.eyJabcdefgh.eyJabcdefgh")):
            with self.subTest(value=value), self.assertRaisesRegex(ReviewRecordsError, "credential or raw secret"):
                FindingObservation(key, value)

    def test_routed_source_finding_cannot_be_changed_into_an_orphan_route(self) -> None:
        self.bootstrap()
        self.records.record_run(
            run_id="manual-route-run",
            source_pr=2828,
            channel="manual",
            findings=(self.observation("route-key"),),
        )
        route = self.records.record_source_decision(
            "manual-route-run",
            "route-key",
            decision_id="source-route",
            decision="routed",
            target_pr=2879,
            actor="reviewer",
            reason="owned by another change",
        )
        with self.assertRaisesRegex(ReviewRecordsError, "already decided"):
            self.records.record_source_decision(
                "manual-route-run",
                "route-key",
                decision_id="source-reject-after-route",
                decision="rejected",
                actor="reviewer",
                reason="changed mind",
            )
        self.assertEqual([item["route_id"] for item in self.records.open_routes()], [route["route_id"]])

    def test_repeated_source_observations_reuse_one_route_and_keep_target_history(self) -> None:
        self.bootstrap()
        first = self.records.record_run(
            run_id="hosted-1",
            source_pr=2828,
            channel="hosted",
            findings=(self.observation("bug-7", "routed", target_pr=2879),),
        )
        second = self.records.record_run(
            run_id="hosted-2",
            source_pr=2828,
            channel="hosted",
            findings=(self.observation("bug-7", "routed", target_pr=2879),),
        )
        source_history = self.records.history(2828)
        target_history = self.records.history(2879)

        self.assertEqual(first["counts"], {"found": 1, "accepted": 0, "routed": 1})
        self.assertEqual(second["counts"], first["counts"])
        self.assertEqual(len(source_history["routes"]), 1)
        self.assertEqual(len(target_history["routes"]), 1)
        self.assertEqual(source_history["routes"][0]["route_id"], target_history["routes"][0]["route_id"])
        self.assertEqual(len(source_history["findings"]), 2)
        self.assertEqual(len(source_history["routes"][0]["target_history"]), 1)

    def test_source_decisions_finalize_counts_and_target_resolution_cannot_rewrite_them(self) -> None:
        self.bootstrap()
        run = self.records.record_run(
            run_id="imported-review",
            source_pr=2828,
            channel="hosted",
            findings=(
                self.observation("accept-me"),
                self.observation("route-me"),
                self.observation("reject-me"),
            ),
        )
        self.assertEqual(run["counts"], {"found": 3, "accepted": 0, "routed": 0})
        self.assertFalse(run["finalized"])

        self.records.record_source_decision(
            "imported-review",
            "accept-me",
            decision_id="source-accept-1",
            decision="accepted",
            actor="reviewer",
            reason="valid source finding",
        )
        routed = self.records.record_source_decision(
            "imported-review",
            "route-me",
            decision_id="source-route-1",
            decision="routed",
            target_pr=2879,
            actor="reviewer",
            reason="owned by the target change",
        )
        rejected = self.records.record_source_decision(
            "imported-review",
            "reject-me",
            decision_id="source-reject-1",
            decision="rejected",
            actor="reviewer",
            reason="not actionable",
        )
        self.assertEqual(routed["counts"], {"found": 3, "accepted": 1, "routed": 1})
        self.assertEqual(rejected["counts"], {"found": 3, "accepted": 1, "routed": 1})
        finalized = self.records.finalize_run("imported-review")
        self.assertEqual(finalized["counts"], {"found": 3, "accepted": 1, "routed": 1})
        self.assertTrue(finalized["finalized"])
        replay = self.records.record_run(
            run_id="imported-review",
            source_pr=2828,
            channel="hosted",
            findings=(
                self.observation("accept-me"),
                self.observation("route-me"),
                self.observation("reject-me"),
            ),
        )
        self.assertTrue(replay["idempotent_replay"])
        self.assertTrue(replay["finalized"])
        self.assertEqual(replay["counts"], {"found": 3, "accepted": 1, "routed": 1})

        route_id = routed["route_id"]
        source_counts_before = self.records.history(2828)["runs"][0]["counts"]

        self.records.record_decision(
            route_id,
            decision_id="target-decision-1",
            decision_pr=2879,
            decision="accepted",
            actor="owner",
            reason="belongs to the target change",
        )
        self.records.record_resolution(
            route_id,
            resolution_id="resolution-1",
            resolution_pr=2879,
            outcome="accepted_fixed",
            actor="owner",
            proof_or_reason="fixed by the target PR",
        )

        history = self.records.history(2828)
        self.assertEqual(history["runs"][0]["counts"], {"found": 3, "accepted": 1, "routed": 1})
        self.assertEqual(history["runs"][0]["counts"], source_counts_before)
        self.assertTrue(history["runs"][0]["finalized"])
        self.assertEqual(len(history["decisions"]), 3)
        self.assertEqual(history["routes"][0]["decisions"][0]["decision"], "accepted")
        self.assertEqual(history["routes"][0]["resolutions"][0]["outcome"], "accepted_fixed")
        self.assertEqual(self.records.open_routes(target_pr=2879), [])
        with self.assertRaisesRegex(ReviewRecordsError, "finalized source-run counts"):
            self.records.record_source_decision(
                "imported-review",
                "accept-me",
                decision_id="late-source-decision",
                decision="rejected",
                actor="reviewer",
                reason="too late",
            )

    def test_completed_import_is_atomic_when_a_later_route_conflicts(self) -> None:
        self.bootstrap()
        self.records.record_run(
            run_id="seed-route",
            source_pr=2828,
            channel="cli",
            findings=(self.observation("later-route", "routed", target_pr=2879),),
        )

        with self.assertRaisesRegex(ReviewRecordsError, "explicitly retargeted"):
            self.records.import_completed_run(
                run_id="atomic-import",
                source_pr=2828,
                channel="cli",
                findings=(self.observation("first-accepted"), self.observation("later-route")),
                source_decisions=(
                    {
                        "source_finding_key": "first-accepted",
                        "decision_id": "atomic-accept",
                        "decision": "accepted",
                        "actor": "reviewer",
                        "reason": "valid source finding",
                    },
                    {
                        "source_finding_key": "later-route",
                        "decision_id": "atomic-route",
                        "decision": "routed",
                        "target_pr": 2999,
                        "actor": "reviewer",
                        "reason": "belongs to another change",
                    },
                ),
            )

        history = self.records.history(2828)
        self.assertEqual([run["run_id"] for run in history["runs"]], ["seed-route"])
        self.assertEqual([finding["source_finding_key"] for finding in history["findings"]], ["later-route"])
        self.assertEqual(self.records.open_routes()[0]["target_pr"], 2879)

    def test_completed_import_replays_exactly_and_refuses_decision_conflict(self) -> None:
        self.bootstrap()
        import_args = {
            "run_id": "completed-import",
            "source_pr": 2828,
            "channel": "manual",
            "findings": (self.observation("accept-me"),),
            "source_decisions": (
                {
                    "source_finding_key": "accept-me",
                    "decision_id": "completed-accept",
                    "decision": "accepted",
                    "actor": "reviewer",
                    "reason": "valid source finding",
                },
            ),
            "reviewer": "reviewer",
            "scope": "narrow",
        }
        first = self.records.import_completed_run(**import_args)
        replay = self.records.import_completed_run(**import_args)
        self.assertFalse(first["idempotent_replay"])
        self.assertTrue(replay["idempotent_replay"])
        self.assertEqual(first["counts"], {"found": 1, "accepted": 1, "routed": 0})
        with self.assertRaisesRegex(ReviewRecordsError, "conflicts with this completed import"):
            self.records.import_completed_run(
                **{
                    **import_args,
                    "source_decisions": (
                        {
                            "source_finding_key": "accept-me",
                            "decision_id": "completed-accept",
                            "decision": "accepted",
                            "actor": "different-reviewer",
                            "reason": "changed reason",
                        },
                    ),
                }
            )
        self.assertEqual(len(self.records.history(2828)["runs"]), 1)

    def test_completed_import_replay_survives_later_route_retargeting(self) -> None:
        self.bootstrap()
        import_args = {
            "run_id": "completed-routed-import",
            "source_pr": 2828,
            "channel": "cli",
            "findings": (self.observation("route-me"),),
            "source_decisions": (
                {
                    "source_finding_key": "route-me",
                    "decision_id": "completed-route",
                    "decision": "routed",
                    "actor": "reviewer",
                    "reason": "owned by another change",
                },
            ),
        }
        first = self.records.import_completed_run(**import_args)
        route_id = self.records.open_routes()[0]["route_id"]
        self.records.retarget_route(
            route_id,
            target_pr=2879,
            actor="target-owner",
            reason="target owner identified",
        )
        replay = self.records.import_completed_run(**import_args)
        self.assertFalse(first["idempotent_replay"])
        self.assertTrue(replay["idempotent_replay"])
        self.assertEqual(self.records.open_routes(target_pr=2879)[0]["route_id"], route_id)

    def test_replayed_decision_uses_its_run_target_after_route_retargeting(self) -> None:
        self.bootstrap()
        first_import = {
            "run_id": "route-owner-first-run",
            "source_pr": 2828,
            "channel": "hosted",
            "findings": (self.observation("shared-route"),),
            "source_decisions": (
                {
                    "source_finding_key": "shared-route",
                    "decision_id": "route-owner-first-decision",
                    "decision": "routed",
                    "target_pr": 2879,
                    "actor": "reviewer",
                    "reason": "initial owner",
                },
            ),
        }
        self.records.import_completed_run(**first_import)
        route_id = self.records.open_routes()[0]["route_id"]
        self.records.retarget_route(
            route_id,
            target_pr=2999,
            actor="reviewer",
            reason="ownership moved",
        )
        second_import = {
            **first_import,
            "run_id": "route-owner-second-run",
            "source_decisions": (
                {
                    "source_finding_key": "shared-route",
                    "decision_id": "route-owner-second-decision",
                    "decision": "routed",
                    "target_pr": 2999,
                    "actor": "reviewer",
                    "reason": "current owner",
                },
            ),
        }

        imported = self.records.import_completed_run(**second_import)
        replay = self.records.import_completed_run(**second_import)
        self.assertFalse(imported["idempotent_replay"])
        self.assertTrue(replay["idempotent_replay"])
        source_decisions = self.records.history(2828)["decisions"]
        self.assertEqual(
            {decision["decision_id"]: decision["target_pr"] for decision in source_decisions},
            {"route-owner-first-decision": 2879, "route-owner-second-decision": 2999},
        )

        conflicting_replay = {
            **second_import,
            "source_decisions": (
                {
                    **second_import["source_decisions"][0],
                    "target_pr": 2879,
                },
            ),
        }
        with self.assertRaisesRegex(ReviewRecordsError, "conflicts with this completed import"):
            self.records.import_completed_run(**conflicting_replay)

    def test_previous_records_schema_version_fails_closed_without_migration(self) -> None:
        self.bootstrap()
        with sqlite3.connect(self.database) as connection:
            connection.execute(
                "UPDATE review_records_metadata SET records_schema_version = 3 WHERE singleton = 1"
            )

        with self.assertRaisesRegex(ReviewRecordsError, "schema version 3"):
            self.records.bootstrap()
        with sqlite3.connect(self.database) as connection:
            self.assertEqual(
                connection.execute(
                    "SELECT records_schema_version FROM review_records_metadata WHERE singleton = 1"
                ).fetchone()[0],
                3,
            )

    def test_route_targeting_is_validated_and_unassigned_routes_are_readable(self) -> None:
        self.bootstrap()
        self.records.record_run(
            run_id="old-source-review",
            source_pr=2700,
            channel="cli",
            findings=(self.observation("incoming", "routed", target_pr=2879),),
        )
        self.records.record_run(
            run_id="manual-unassigned",
            source_pr=2999,
            channel="manual",
            findings=(self.observation("unassigned", "routed"),),
        )
        incoming = self.records.open_routes(target_pr=2879)
        all_open = self.records.open_routes()
        self.assertEqual([route["source_pr"] for route in incoming], [2700])
        self.assertEqual(incoming[0]["assignment"], "incoming")
        self.assertIn({"source_pr": 2999, "assignment": "unassigned"}, [
            {"source_pr": route["source_pr"], "assignment": route["assignment"]} for route in all_open
        ])
        self.assertEqual(self.records.history(2879)["routes"][0]["source_pr"], 2700)

        with self.assertRaisesRegex(ReviewRecordsError, "current target"):
            self.records.record_resolution(
                incoming[0]["route_id"],
                resolution_id="wrong-owner-resolution",
                resolution_pr=2800,
                outcome="rejected",
                actor="owner",
                proof_or_reason="wrong target",
            )

        self.records.retarget_route(
            all_open[0]["route_id"],
            target_pr=2879,
            actor="operator",
            reason="assign to the owning PR",
        )
        assigned_history = self.records.history(2879)
        self.assertEqual(len(assigned_history["routes"]), 2)
        self.assertEqual(len(assigned_history["routes"][1]["target_history"]), 2)

    def test_route_listing_filters_status_source_target_and_assignment(self) -> None:
        self.bootstrap()
        routes = (
            (2700, "active-target", 2879),
            (2701, "resolved-target", 2879),
            (2702, "unassigned", None),
            (2703, "other-target", 2880),
        )
        for source_pr, key, target_pr in routes:
            self.records.record_run(
                run_id=f"route-list-{source_pr}",
                source_pr=source_pr,
                channel="manual",
                findings=(self.observation(key, "routed", target_pr=target_pr),),
            )

        resolved_route = self.records.list_routes(source_pr=2701)[0]
        self.records.record_resolution(
            resolved_route["route_id"],
            resolution_id="route-list-resolution",
            resolution_pr=2879,
            outcome="accepted_fixed",
            actor="owner",
            proof_or_reason="verified fix",
        )

        self.assertEqual(len(self.records.list_routes()), 3)
        self.assertEqual(
            [route["status"] for route in self.records.list_routes(status="resolved")],
            ["accepted_fixed"],
        )
        self.assertEqual(len(self.records.list_routes(status="all")), 4)
        self.assertEqual(
            {route["source_pr"] for route in self.records.list_routes(target_pr=2879, status="all")},
            {2700, 2701},
        )
        self.assertEqual(
            [route["source_pr"] for route in self.records.list_routes(source_pr=2701, status="resolved")],
            [2701],
        )
        self.assertEqual(
            [route["source_pr"] for route in self.records.list_routes(source_pr=2703, target_pr=2880)],
            [2703],
        )
        self.assertEqual(
            [route["source_pr"] for route in self.records.list_routes(unassigned=True)],
            [2702],
        )
        with self.assertRaisesRegex(ReviewRecordsError, "route status"):
            self.records.list_routes(status="pending")
        with self.assertRaisesRegex(ReviewRecordsError, "cannot be combined"):
            self.records.list_routes(target_pr=2879, unassigned=True)

    def test_legacy_controller_route_rejects_sqlite_target_writes(self) -> None:
        self.bootstrap()
        legacy_route = FindingRoute(
            source_pr=2828,
            source_channel="hosted",
            source_review="summary:review:700",
            source_finding="duplicate:ref:legacy-summary",
            observations=("owned by another PR",),
            target_pr=2879,
            target_history=(2879,),
        )
        state_store = SqliteStateStore(self.database)
        state_store.update(lambda state: dataclasses.replace(state, routes=(legacy_route,)))
        self.records.import_completed_run(
            run_id="legacy-shadow-run",
            source_pr=2828,
            channel="manual",
            findings=(self.observation("legacy-shadow"),),
            source_decisions=(
                {
                    "source_finding_key": "legacy-shadow",
                    "decision_id": "legacy-shadow-source-route",
                    "decision": "routed",
                    "route_id": legacy_route.route_id,
                    "route_status": "open",
                    "target_pr": 2879,
                    "actor": "reviewer",
                    "reason": "mirrors the controller-owned route",
                },
            ),
        )

        visible_routes_before = self.records.open_routes(target_pr=2879, include_legacy_routes=True)
        visible_history_before = self.records.history(2879, include_legacy_routes=True)["routes"]
        shadow_history_before = self.records.history(2828)["routes"]
        self.assertEqual([route["origin"] for route in visible_routes_before], ["legacy_controller"])
        self.assertEqual(visible_routes_before[0]["route_id"], legacy_route.route_id)

        with self.assertRaisesRegex(ReviewRecordsError, "legacy controller owns this route.*decide route"):
            self.records.retarget_route(
                legacy_route.route_id,
                target_pr=2999,
                actor="owner",
                reason="retarget legacy route",
            )
        with self.assertRaisesRegex(ReviewRecordsError, "legacy controller owns this route.*decide route"):
            self.records.record_decision(
                legacy_route.route_id,
                decision_id="legacy-target-decision",
                decision_pr=2879,
                decision="accepted",
                actor="owner",
                reason="decide legacy route",
            )
        with self.assertRaisesRegex(ReviewRecordsError, "legacy controller owns this route.*decide route"):
            self.records.record_resolution(
                legacy_route.route_id,
                resolution_id="legacy-target-resolution",
                resolution_pr=2879,
                outcome="accepted_fixed",
                actor="owner",
                proof_or_reason="resolve legacy route",
            )

        self.assertEqual(state_store.load().routes, (legacy_route,))
        self.assertEqual(self.records.open_routes(target_pr=2879, include_legacy_routes=True), visible_routes_before)
        self.assertEqual(self.records.history(2879, include_legacy_routes=True)["routes"], visible_history_before)
        self.assertEqual(self.records.history(2828)["routes"], shadow_history_before)

    def test_manual_and_subagent_runs_do_not_change_controller_policy_state(self) -> None:
        self.bootstrap()
        policy_store = SqliteStateStore(self.database)
        policy_before = policy_store.load().to_dict()
        self.records.record_run(
            run_id="manual-review",
            source_pr=2828,
            channel="manual",
            findings=(self.observation("manual-accepted", "accepted"),),
        )
        self.records.finalize_run("manual-review")
        self.records.record_run(
            run_id="subagent-review",
            source_pr=2828,
            channel="subagent",
            findings=(self.observation("subagent-unresolved"),),
        )
        self.records.record_source_decision(
            "subagent-review",
            "subagent-unresolved",
            decision_id="subagent-source-decision",
            decision="accepted",
            actor="reviewer",
            reason="recordable assistant observation",
        )
        self.records.finalize_run("subagent-review")

        with sqlite3.connect(self.database) as connection:
            taper_table = connection.execute(
                "SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'channel_taper'"
            ).fetchone()
        history = self.records.history(2828)
        self.assertEqual(policy_store.load().to_dict(), policy_before)
        self.assertIsNone(taper_table)
        self.assertNotIn("taper", history)
        self.assertEqual([run["channel"] for run in history["runs"]], ["manual", "subagent"])
        self.assertEqual([run["counts"]["accepted"] for run in history["runs"]], [1, 1])

    def test_incompatible_records_metadata_fails_closed_and_readback_is_machine_readable(self) -> None:
        self.bootstrap()
        self.records.record_run(
            run_id="manual-1",
            source_pr=2828,
            channel="manual",
            attributable=False,
            findings=(self.observation("readback", "accepted"),),
        )
        self.records.finalize_run("manual-1")
        reopened = SqliteReviewRecords(self.database)
        result = reopened.history(2828)
        self.assertEqual(result["runs"][0]["counts"], {"found": 1, "accepted": 1, "routed": 0})
        self.assertTrue(result["runs"][0]["finalized"])
        self.assertEqual(result["runs"][0]["reviewer"], "manual")
        self.assertEqual(result["runs"][0]["scope"], "narrow")
        self.assertEqual(result["findings"][0]["source_finding_key"], "readback")
        self.assertNotIn("payload", result["findings"][0])
        self.assertNotIn("taper", result)

        with sqlite3.connect(self.database) as connection:
            connection.execute(
                "UPDATE review_records_metadata SET records_schema_version = 999 WHERE singleton = 1"
            )
        with self.assertRaisesRegex(ReviewRecordsError, "schema version 999"):
            reopened.history(2828)


if __name__ == "__main__":
    unittest.main()
