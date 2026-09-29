from __future__ import annotations

import dataclasses
import json
import sqlite3
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "dev-tools"))

from pr_review import evidence, hosted, sqlite_hosted_capture, sqlite_provider_imports, sqlite_store
from pr_review.sqlite_records_repair import (
    SqliteRecordsRepairError,
    archive_incomplete_checkpoint,
    repair_provider_checkpoints,
)
from pr_review.sqlite_review_records import FindingObservation, SqliteReviewRecords

HEAD = "abcdef0123456789abcdef0123456789abcdef01"
REPO = "owner/repo"
PR = 42


class SqliteRecordsRepairTest(unittest.TestCase):
    def setUp(self) -> None:
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.common = Path(temporary.name)
        self.database = self.common / "controller.sqlite3"
        sqlite_store.SqliteStateStore(self.database).update(lambda state: state)
        self.records = SqliteReviewRecords(self.database)
        self.records.bootstrap()

    @staticmethod
    def checkpoint(
        channel: str,
        marker: str,
        *,
        comment_id: int = 10,
        raw: int = 1,
        accepted: int = 1,
        routed: int | None = 0,
    ) -> evidence.Checkpoint:
        counts = f"{raw} found / {accepted} accepted"
        if routed is not None:
            counts += f" / {routed} routed"
        heading = f"{channel}: {counts} · {HEAD[:12]} · 1 files"
        comments, unparsed = evidence.parse_checkpoint_comments(
            [{"id": comment_id, "body": heading + "\n" + marker, "created_at": "2026-09-27T12:00:00Z"}]
        )
        if unparsed or len(comments) != 1:
            raise AssertionError("fixture checkpoint did not parse canonically")
        return comments[0]

    def hosted_capture(
        self,
        review_id: int = 700,
        *,
        decision_text: str = "701\taccepted\tvalidated source finding\n",
    ) -> bytes:
        capture_dir = self.common / "firemud" / f"hosted-review.{review_id}"
        capture_dir.mkdir(parents=True)
        snapshot = {
            "source": "hosted",
            "repository": REPO,
            "pull_request": PR,
            "review": {
                "id": review_id,
                "user": {"login": "coderabbitai[bot]"},
                "state": "COMMENTED",
                "submitted_at": "2026-09-27T11:59:00Z",
                "commit_id": HEAD,
            },
            "comments": [
                {
                    "id": 701,
                    "pull_request_review_id": review_id,
                    "in_reply_to_id": None,
                    "user": {"login": "coderabbitai[bot]"},
                    "body": "Use the checked value before dereferencing it.",
                    "created_at": "2026-09-27T11:58:00Z",
                }
            ],
        }
        snapshot_path = capture_dir / "snapshot.json"
        snapshot_bytes = json.dumps(snapshot).encode("utf-8")
        snapshot_path.write_bytes(snapshot_bytes)
        (capture_dir / "decisions.tsv").write_text(decision_text, encoding="utf-8")
        return snapshot_bytes

    def cli_capture(self, run_id: str = "run.Repair1") -> dict[str, bytes]:
        capture_dir = self.common / "coderabbit-review-logs" / run_id
        capture_dir.mkdir(parents=True)
        legacy_metadata = (
            f"run_id={run_id}\nrepository={REPO}\npull_request={PR}\n"
            f"candidate_sha={HEAD}\ncandidate_files=1\n"
        )
        metadata = {
            "run_id": run_id,
            "kind": "cli",
            "pull_request": PR,
            "candidate_sha": HEAD,
            "candidate_files": 1,
        }
        events = [
            {"type": "finding", "codegenInstructions": "Validate the route before using it.\nThen update callers."},
            {"type": "complete", "status": "review_completed", "findings": 1, "reviewedFiles": ["a.py"]},
        ]
        files = {
            "metadata": legacy_metadata.encode("utf-8"),
            "metadata.json": json.dumps(metadata).encode("utf-8"),
            "stdout": ("\n".join(json.dumps(event) for event in events) + "\n").encode("utf-8"),
            "stderr": b"synthetic provider diagnostic\n",
            "exit-status": b"0\n",
            "review-duration-seconds": b"31\n",
            "decisions.tsv": b"1\trouted\towner PR #2879\n",
        }
        for name, contents in files.items():
            (capture_dir / name).write_bytes(contents)
        return files

    def record_cli_attempt(self, *, disposition: str = "routed") -> None:
        run_id = "run.Repair1"
        self.records.start_attempt(
            attempt_id=run_id,
            source_pr=PR,
            channel="cli",
            candidate_sha=HEAD,
            started_at="2026-09-27T11:00:00Z",
        )
        self.records.complete_attempt_run(
            run_id,
            finish={
                "state": "completed",
                "finished_at": "2026-09-27T11:01:00Z",
                "duration_seconds": 60,
                "exit_status": 0,
                "artifacts": {"cli_events": "{}\n"},
            },
            run={
                "run_id": run_id,
                "source_pr": PR,
                "channel": "cli",
                "findings": (
                    FindingObservation(
                        source_finding_key=f"cli-run:{run_id}:finding:1",
                        title="CLI title only",
                    ),
                ),
                "source_head": HEAD,
                "reviewer": "CodeRabbit CLI",
                "scope": "broad",
                "started_at": "2026-09-27T11:00:00Z",
                "finished_at": "2026-09-27T11:01:00Z",
            },
        )
        self.records.record_source_decision(
            run_id,
            f"cli-run:{run_id}:finding:1",
            decision_id="decision.repair-attempt",
            decision=disposition,  # type: ignore[arg-type]
            actor="backfill-reviewer",
            reason="owner PR #2879",
            decided_at="2026-09-27T12:00:00Z",
        )

    def test_missing_old_capture_is_archived_as_gap_without_review_credit(self) -> None:
        checkpoint = self.checkpoint("CLI", "<!-- firemud-cli-run: run.Lost -->")
        preview = archive_incomplete_checkpoint(
            self.records, repo=REPO, pr_number=PR, checkpoint=checkpoint,
            missing_reason="original capture is unavailable", common=self.common,
        )
        self.assertEqual(preview["status"], "incomplete_preview")
        self.assertEqual(self.records.history(PR)["historical_gaps"], [])
        applied = archive_incomplete_checkpoint(
            self.records, repo=REPO, pr_number=PR, checkpoint=checkpoint,
            missing_reason="original capture is unavailable", common=self.common,
            dry_run=False,
        )
        self.assertEqual(applied["status"], "incomplete_archived")
        self.assertFalse(applied["idempotent_replay"])
        history = self.records.history(PR)
        self.assertEqual(history["runs"], [])
        self.assertEqual(history["attempts"], [])
        self.assertEqual(history["historical_gaps"][0]["checkpoint_id"], checkpoint.comment_id)
        self.assertEqual(history["historical_gaps"][0]["missing_reason"], "original capture is unavailable")
        replay = archive_incomplete_checkpoint(
            self.records, repo=REPO, pr_number=PR, checkpoint=checkpoint,
            missing_reason="original capture is unavailable", common=self.common,
            dry_run=False,
        )
        self.assertTrue(replay["idempotent_replay"])

    def test_failed_capture_archives_loader_diagnostic_without_review_credit(self) -> None:
        run_id = "run.Malformed"
        self.cli_capture(run_id)
        (self.common / "coderabbit-review-logs" / run_id / "stdout").write_text(
            "not-json\n", encoding="utf-8"
        )
        checkpoint = self.checkpoint("CLI", f"<!-- firemud-cli-run: {run_id} -->")

        archived = archive_incomplete_checkpoint(
            self.records,
            repo=REPO,
            pr_number=PR,
            checkpoint=checkpoint,
            missing_reason="capture could not be validated",
            common=self.common,
            dry_run=False,
        )

        self.assertEqual(archived["status"], "incomplete_archived")
        history = self.records.history(PR)
        self.assertEqual(history["runs"], [])
        self.assertEqual(history["attempts"], [])
        with sqlite3.connect(self.database) as connection:
            metadata = connection.execute(
                "SELECT content FROM historical_gap_artifacts WHERE checkpoint_id = ? AND kind = 'metadata'",
                (checkpoint.comment_id,),
            ).fetchone()
        self.assertIsNotNone(metadata)
        self.assertEqual(
            json.loads(metadata[0])["capture_error"],
            "linked capture stdout has invalid JSON at line 1",
        )

    def test_historical_gap_rejects_a_changed_reason(self) -> None:
        checkpoint = self.checkpoint("CLI", "<!-- firemud-cli-run: run.Lost -->")
        archive_incomplete_checkpoint(
            self.records, repo=REPO, pr_number=PR, checkpoint=checkpoint,
            missing_reason="original capture is unavailable", common=self.common,
            dry_run=False,
        )
        with self.assertRaisesRegex(
            SqliteRecordsRepairError,
            "^historical gap conflicts with existing checkpoint evidence$",
        ):
            archive_incomplete_checkpoint(
                self.records, repo=REPO, pr_number=PR, checkpoint=checkpoint,
                missing_reason="different claim", common=self.common,
                dry_run=False,
            )

    def test_preview_validates_both_channels_without_writing_live_database_or_captures(self) -> None:
        hosted_bytes = self.hosted_capture()
        cli_files = self.cli_capture()
        hosted_checkpoint = self.checkpoint("Hosted", "<!-- firemud-hosted-review: 700 -->", comment_id=10)
        cli_checkpoint = self.checkpoint(
            "CLI", "<!-- firemud-cli-run: run.Repair1 -->", comment_id=11, accepted=0, routed=1
        )

        report = repair_provider_checkpoints(
            self.records,
            repo=REPO,
            pr_number=PR,
            checkpoints=[hosted_checkpoint, cli_checkpoint],
            actor="backfill-reviewer",
            common=self.common,
            dry_run=True,
        )

        self.assertEqual(report["status"], "preview")
        self.assertEqual([item["action"] for item in report["items"]], ["would_import", "would_import"])
        self.assertEqual(report["items"][0]["artifact_kinds"], ["hosted_comments", "hosted_review", "metadata"])
        self.assertEqual(
            report["items"][1]["artifact_kinds"], ["cli_diagnostic", "cli_events", "metadata"]
        )
        self.assertEqual(self.records.history(PR)["runs"], [])
        with sqlite3.connect(self.database) as connection:
            self.assertEqual(connection.execute("SELECT COUNT(*) FROM provider_origins").fetchone()[0], 0)
            self.assertEqual(connection.execute("SELECT COUNT(*) FROM imported_artifacts").fetchone()[0], 0)
        self.assertEqual((self.common / "firemud" / "hosted-review.700" / "snapshot.json").read_bytes(), hosted_bytes)
        for name, contents in cli_files.items():
            self.assertEqual(
                (self.common / "coderabbit-review-logs" / "run.Repair1" / name).read_bytes(), contents
            )

    def test_repair_requires_canonical_importer_archive_artifacts(self) -> None:
        checkpoint = self.checkpoint(
            "CLI", "<!-- firemud-cli-run: run.RepairNoArchive -->", comment_id=12
        )

        def import_without_archive(records: SqliteReviewRecords, **kwargs: object) -> dict[str, object]:
            checkpoint_arg = kwargs["checkpoint"]
            assert isinstance(checkpoint_arg, evidence.Checkpoint)
            result = records.import_completed_run(
                run_id=checkpoint_arg.run_id or "",
                source_pr=PR,
                channel="cli",
                findings=[],
                source_decisions=[],
                source_head=HEAD,
                reviewer="CodeRabbit CLI",
                scope="broad",
                started_at=checkpoint_arg.created_at,
                finished_at=checkpoint_arg.created_at,
            )
            return {**result, "provider_id": f"run:{checkpoint_arg.run_id}"}

        with (
            patch.object(sqlite_provider_imports, "import_cli_checkpoint", side_effect=import_without_archive),
            self.assertRaisesRegex(SqliteRecordsRepairError, "omitted archive artifacts"),
        ):
            repair_provider_checkpoints(
                self.records,
                repo=REPO,
                pr_number=PR,
                checkpoints=[checkpoint],
                actor="backfill-reviewer",
                common=self.common,
            )

    def test_apply_archives_capture_and_origin_then_replays_exactly(self) -> None:
        self.hosted_capture()
        checkpoint = self.checkpoint("Hosted", "<!-- firemud-hosted-review: 700 -->")
        first = repair_provider_checkpoints(
            self.records,
            repo=REPO,
            pr_number=PR,
            checkpoints=[checkpoint],
            actor="backfill-reviewer",
            common=self.common,
            dry_run=False,
        )
        replay = repair_provider_checkpoints(
            self.records,
            repo=REPO,
            pr_number=PR,
            checkpoints=[checkpoint],
            actor="backfill-reviewer",
            common=self.common,
            dry_run=False,
        )

        self.assertEqual(first["status"], "complete")
        self.assertTrue(first["items"][0]["origin_linked"])
        self.assertTrue(first["items"][0]["artifacts_archived"])
        self.assertEqual(replay["items"][0]["action"], "already_imported")
        self.assertEqual(self.records.history(PR)["runs"][0]["counts"], {"found": 1, "accepted": 1, "routed": 0})
        with sqlite3.connect(self.database) as connection:
            origin = connection.execute(
                "SELECT repository, source_pr, channel, provider_id, checkpoint_id, run_id "
                "FROM provider_origins"
            ).fetchone()
            artifacts = connection.execute(
                "SELECT kind, source_sha256 FROM imported_artifacts ORDER BY kind"
            ).fetchall()
        self.assertEqual(origin, ("owner/repo", PR, "hosted", "review:700", 10, first["items"][0]["run_id"]))
        self.assertEqual([row[0] for row in artifacts], ["hosted_comments", "hosted_review", "metadata"])
        self.assertTrue(all(len(row[1]) == 64 for row in artifacts))

    def test_replay_after_source_decision_correction_keeps_origin_and_current_counts(self) -> None:
        self.hosted_capture()
        checkpoint = self.checkpoint("Hosted", "<!-- firemud-hosted-review: 700 -->")
        first = repair_provider_checkpoints(
            self.records,
            repo=REPO,
            pr_number=PR,
            checkpoints=[checkpoint],
            actor="backfill-reviewer",
            common=self.common,
            dry_run=False,
        )
        history = self.records.history(PR)
        self.records.correct_source_decision(
            first["items"][0]["run_id"],
            "hosted-comment:701",
            supersedes_id=history["decisions"][0]["decision_id"],
            correction_id="correction.repair-accepted-to-rejected",
            decision="rejected",
            actor="adjudicator",
            reason="correction retained the audited source evidence",
            decided_at="2026-09-27T12:01:00Z",
        )

        replay = repair_provider_checkpoints(
            self.records,
            repo=REPO,
            pr_number=PR,
            checkpoints=[checkpoint],
            actor="backfill-reviewer",
            common=self.common,
            dry_run=False,
        )

        self.assertEqual(replay["items"][0]["action"], "already_imported")
        self.assertEqual(
            replay["items"][0]["counts"],
            {"found": 1, "accepted": 0, "routed": 0},
        )
        self.assertEqual(
            self.records.history(PR)["runs"][0]["counts"],
            {"found": 1, "accepted": 0, "routed": 0},
        )
        with sqlite3.connect(self.database) as connection:
            self.assertEqual(connection.execute("SELECT COUNT(*) FROM provider_origins").fetchone()[0], 1)
            self.assertEqual(connection.execute("SELECT COUNT(*) FROM imported_artifacts").fetchone()[0], 3)

    def test_repair_links_exact_automatically_recorded_cli_attempt_without_duplicate(self) -> None:
        self.cli_capture()
        self.record_cli_attempt()
        checkpoint = self.checkpoint(
            "CLI", "<!-- firemud-cli-run: run.Repair1 -->", accepted=0, routed=1
        )

        report = repair_provider_checkpoints(
            self.records,
            repo=REPO,
            pr_number=PR,
            checkpoints=[checkpoint],
            actor="backfill-reviewer",
            common=self.common,
            dry_run=False,
        )

        self.assertEqual(report["status"], "complete")
        self.assertEqual(report["items"][0]["action"], "already_imported")
        self.assertEqual(report["items"][0]["run_id"], "run.Repair1")
        self.assertTrue(report["items"][0]["origin_linked"])
        self.assertEqual([run["run_id"] for run in self.records.history(PR)["runs"]], ["run.Repair1"])
        with sqlite3.connect(self.database) as connection:
            origin = connection.execute("SELECT provider_id, run_id FROM provider_origins").fetchone()
            artifact_run_ids = connection.execute(
                "SELECT DISTINCT run_id FROM imported_artifacts"
            ).fetchall()
        self.assertEqual(origin, ("run:run.Repair1", "run.Repair1"))
        self.assertEqual(artifact_run_ids, [("run.Repair1",)])
        replay = repair_provider_checkpoints(
            self.records,
            repo=REPO,
            pr_number=PR,
            checkpoints=[checkpoint],
            actor="backfill-reviewer",
            common=self.common,
            dry_run=False,
        )
        self.assertEqual(replay["items"][0]["run_id"], "run.Repair1")
        self.assertEqual(len(self.records.history(PR)["runs"]), 1)

    def test_repair_links_exact_automatically_recorded_hosted_attempt_without_duplicate(self) -> None:
        self.hosted_capture()
        attempt_id = "hosted-attempt.700"
        trigger_id = 999
        started_at = "2026-09-27T11:57:30Z"
        trigger_record = {
            "schema_version": 1,
            "status": "posted",
            "repository": REPO,
            "pr_number": PR,
            "head_sha": HEAD,
            "posting_started_at": "2026-09-27T11:58:00Z",
            "trigger": {
                "id": trigger_id,
                "created_at": "2026-09-27T11:58:00Z",
                "url": f"https://github.example/{REPO}/pull/{PR}#issuecomment-{trigger_id}",
                "author_login": "maintainer",
                "type": "full",
                "command": hosted.FULL_COMMAND,
            },
            "sqlite_attempt_id": attempt_id,
        }
        sqlite_hosted_capture.start_hosted_attempt(
            self.records,
            attempt_id=attempt_id,
            source_pr=PR,
            candidate_sha=HEAD,
            started_at=started_at,
        )
        checkpoint_body = (
            f"Hosted: 1 found / 1 accepted / 0 routed · {HEAD[:12]} · 1 files\n"
            "<!-- firemud-hosted-review: 700 -->"
        )
        payload = {
            "data": {
                "repository": {
                    "pullRequest": {
                        "number": PR,
                        "headRefOid": HEAD,
                        "comments": {
                            "nodes": [
                                {
                                    "databaseId": trigger_id,
                                    "author": {"login": "maintainer"},
                                    "body": hosted.FULL_COMMAND,
                                    "createdAt": "2026-09-27T11:58:00Z",
                                    "updatedAt": "2026-09-27T11:58:00Z",
                                    "url": (
                                        f"https://github.example/{REPO}/pull/{PR}"
                                        f"#issuecomment-{trigger_id}"
                                    ),
                                },
                                {
                                    "databaseId": 10,
                                    "author": {"login": "maintainer"},
                                    "body": checkpoint_body,
                                    "createdAt": "2026-09-27T12:00:00Z",
                                    "updatedAt": "2026-09-27T12:00:00Z",
                                },
                            ]
                        },
                        "reviews": {
                            "nodes": [
                                {
                                    "databaseId": 700,
                                    "author": {"login": "coderabbitai[bot]"},
                                    "body": f"<!-- walkthrough_start -->\nReviewed {HEAD}",
                                    "state": "COMMENTED",
                                    "submittedAt": "2026-09-27T11:59:00Z",
                                    "commit": {"oid": HEAD},
                                }
                            ]
                        },
                        "reviewThreads": {
                            "nodes": [
                                {
                                    "id": "PRRT_thread_1",
                                    "isResolved": False,
                                    "isOutdated": False,
                                    "path": "src/example.py",
                                    "comments": {
                                        "nodes": [
                                            {
                                                "databaseId": 701,
                                                "author": {"login": "coderabbitai[bot]"},
                                                "body": "Use the checked value before dereferencing it.",
                                                "createdAt": "2026-09-27T11:58:30Z",
                                                "updatedAt": "2026-09-27T11:58:30Z",
                                                "url": "https://github.example/owner/repo/pull/42#discussion_r701",
                                            }
                                        ]
                                    },
                                }
                            ]
                        },
                    }
                }
            }
        }
        captured = sqlite_hosted_capture.record_hosted_terminal_result(
            self.records,
            attempt_id=attempt_id,
            repo=REPO,
            source_pr=PR,
            trigger_record=trigger_record,
            payload=payload,
        )
        self.assertEqual(captured["state"], "completed", captured)
        self.assertEqual(
            self.records.history(PR)["findings"][0]["source_finding_key"],
            "hosted-comment:701",
        )
        self.records.record_source_decision(
            attempt_id,
            "hosted-comment:701",
            decision_id="decision.hosted-attempt",
            decision="accepted",
            actor="backfill-reviewer",
            reason="validated source finding",
            decided_at="2026-09-27T11:59:00Z",
        )
        checkpoint = self.checkpoint("Hosted", "<!-- firemud-hosted-review: 700 -->")

        report = repair_provider_checkpoints(
            self.records,
            repo=REPO,
            pr_number=PR,
            checkpoints=[checkpoint],
            actor="backfill-reviewer",
            common=self.common,
            dry_run=False,
        )

        self.assertEqual(report["status"], "complete")
        self.assertEqual(report["items"][0]["action"], "already_imported")
        self.assertEqual(report["items"][0]["run_id"], attempt_id)
        self.assertEqual(
            [run["run_id"] for run in self.records.history(PR)["runs"]], [attempt_id]
        )
        with sqlite3.connect(self.database) as connection:
            origin = connection.execute("SELECT provider_id, run_id FROM provider_origins").fetchone()
        self.assertEqual(origin, ("review:700", attempt_id))
        replay = repair_provider_checkpoints(
            self.records,
            repo=REPO,
            pr_number=PR,
            checkpoints=[checkpoint],
            actor="backfill-reviewer",
            common=self.common,
            dry_run=False,
        )
        self.assertEqual(replay["items"][0]["run_id"], attempt_id)
        self.assertEqual(len(self.records.history(PR)["runs"]), 1)

    def test_repair_refuses_mismatched_automatic_attempt_without_duplicate_or_origin(self) -> None:
        self.cli_capture()
        self.record_cli_attempt(disposition="accepted")
        checkpoint = self.checkpoint(
            "CLI", "<!-- firemud-cli-run: run.Repair1 -->", accepted=0, routed=1
        )

        with self.assertRaisesRegex(
            SqliteRecordsRepairError,
            "^CLI checkpoint accepted count does not match linked findings decisions$",
        ):
            repair_provider_checkpoints(
                self.records,
                repo=REPO,
                pr_number=PR,
                checkpoints=[checkpoint],
                actor="backfill-reviewer",
                common=self.common,
                dry_run=False,
            )

        self.assertEqual([run["run_id"] for run in self.records.history(PR)["runs"]], ["run.Repair1"])
        with sqlite3.connect(self.database) as connection:
            self.assertEqual(connection.execute("SELECT COUNT(*) FROM provider_origins").fetchone()[0], 0)
            self.assertEqual(connection.execute("SELECT COUNT(*) FROM imported_artifacts").fetchone()[0], 0)

    def test_duplicate_provider_origin_with_different_checkpoint_ids_is_refused_preflight(self) -> None:
        self.hosted_capture()
        first = self.checkpoint("Hosted", "<!-- firemud-hosted-review: 700 -->", comment_id=10)
        second = self.checkpoint("Hosted", "<!-- firemud-hosted-review: 700 -->", comment_id=12)

        with self.assertRaisesRegex(
            SqliteRecordsRepairError,
            "^provider identity review:700 is attributed by multiple checkpoint comments \\(10, 12\\)$",
        ):
            repair_provider_checkpoints(
                self.records,
                repo=REPO,
                pr_number=PR,
                checkpoints=[first, second],
                actor="backfill-reviewer",
                common=self.common,
                dry_run=False,
            )

        self.assertEqual(self.records.history(PR)["runs"], [])
        with sqlite3.connect(self.database) as connection:
            self.assertEqual(connection.execute("SELECT COUNT(*) FROM provider_origins").fetchone()[0], 0)
            self.assertEqual(connection.execute("SELECT COUNT(*) FROM imported_artifacts").fetchone()[0], 0)

    def test_existing_run_decision_conflict_is_refused_without_live_mutation(self) -> None:
        self.hosted_capture()
        checkpoint = self.checkpoint("Hosted", "<!-- firemud-hosted-review: 700 -->")
        original = sqlite_provider_imports.import_hosted_checkpoint(
            self.records,
            repo=REPO,
            pr_number=PR,
            checkpoint=checkpoint,
            actor="original-reviewer",
            common=self.common,
            scope="broad",
        )

        with self.assertRaisesRegex(
            SqliteRecordsRepairError,
            "^existing source decision conflicts with this completed import$",
        ):
            repair_provider_checkpoints(
                self.records,
                repo=REPO,
                pr_number=PR,
                checkpoints=[checkpoint],
                actor="different-reviewer",
                common=self.common,
                dry_run=True,
            )

        history = self.records.history(PR)
        self.assertEqual([run["run_id"] for run in history["runs"]], [original["run_id"]])
        self.assertEqual(history["decisions"][0]["actor"], "original-reviewer")
        with sqlite3.connect(self.database) as connection:
            self.assertEqual(connection.execute("SELECT COUNT(*) FROM provider_origins").fetchone()[0], 0)

    def test_trigger_anchored_zero_result_uses_importer_proven_identity_without_review_object(self) -> None:
        checkpoint = self.checkpoint(
            "Hosted", "", comment_id=13, raw=0, accepted=0, routed=0
        )
        checkpoint = dataclasses.replace(checkpoint, reviewed_sha=HEAD)
        archive = {
            "hosted_comments": json.dumps([{"id": 999, "body": "Review complete: no findings"}]),
            "metadata": json.dumps({"trigger_id": 999, "zero_findings_proven": True}),
        }

        def import_trigger(records: SqliteReviewRecords, **kwargs: object) -> dict[str, object]:
            checkpoint_arg = kwargs["checkpoint"]
            assert isinstance(checkpoint_arg, evidence.Checkpoint)
            result = records.import_completed_run(
                run_id="trigger-empty-run",
                source_pr=PR,
                channel="hosted",
                findings=[],
                source_decisions=[],
                source_head=HEAD,
                reviewer="CodeRabbit Hosted",
                scope="broad",
                started_at=checkpoint_arg.created_at,
                finished_at=checkpoint_arg.created_at,
            )
            return {**result, "provider_id": "trigger:999", "archive_artifacts": archive}

        with patch.object(sqlite_provider_imports, "import_hosted_checkpoint", side_effect=import_trigger):
            report = repair_provider_checkpoints(
                self.records,
                repo=REPO,
                pr_number=PR,
                checkpoints=[checkpoint],
                actor="backfill-reviewer",
                common=self.common,
                dry_run=False,
            )

        self.assertEqual(report["status"], "complete")
        self.assertEqual(report["items"][0]["provider_id"], "trigger:999")
        self.assertEqual(self.records.history(PR)["runs"][0]["counts"], {"found": 0, "accepted": 0, "routed": 0})
        with sqlite3.connect(self.database) as connection:
            origin = connection.execute(
                "SELECT provider_id, checkpoint_id FROM provider_origins"
            ).fetchone()
            kinds = connection.execute("SELECT kind FROM imported_artifacts ORDER BY kind").fetchall()
        self.assertEqual(origin, ("trigger:999", 13))
        self.assertEqual([row[0] for row in kinds], ["hosted_comments", "metadata"])


if __name__ == "__main__":
    unittest.main()
