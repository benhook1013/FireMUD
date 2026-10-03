from __future__ import annotations

import contextlib
import dataclasses
import hashlib
import io
import json
import sqlite3
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "dev-tools"))

from pr_review import (
    cli,
    evidence,
    hosted,
    sqlite_hosted_capture,
    sqlite_provider_imports,
    sqlite_records_repair,
    sqlite_store,
)
from pr_review.sqlite_records_repair import (
    MissingHistoricalEvidenceError,
    SqliteRecordsRepairError,
    archive_incomplete_checkpoint,
    repair_provider_checkpoints,
)
from pr_review.sqlite_review_records import FindingObservation, ReviewRecordsError, SqliteReviewRecords

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

    def cli_capture(
        self,
        run_id: str = "run.Repair1",
        *,
        decision_text: str = "1\trouted\towner PR #2879\n",
    ) -> dict[str, bytes]:
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
            "decisions.tsv": decision_text.encode("utf-8"),
        }
        for name, contents in files.items():
            (capture_dir / name).write_bytes(contents)
        return files

    def late_reply_checkpoint(self, *, state="ambiguous", candidate=None, repository="owner/repo", trigger_id="10"):
        import test_pr_review_evidence_hosted as fixtures

        payload, record, record_path = fixtures.archived_addressed_reply_fixture(self.common)
        payload["data"]["repository"]["pullRequest"]["number"] = fixtures.PR
        payload["data"]["repository"]["pullRequest"]["reviewThreads"]["nodes"] = []
        record["anchor"] = {
            "child_head": fixtures.HEAD, "parent_identity": "develop",
            "parent_head": fixtures.BASE, "merge_base": fixtures.BASE, "patch_id": "archived-patch",
        }
        record["sqlite_attempt_id"] = "late-reply"
        record_path.write_text(json.dumps(record), encoding="utf-8")
        self.records.start_attempt(
            attempt_id="late-reply", source_pr=fixtures.PR, channel="hosted",
            candidate_sha=candidate or fixtures.HEAD, started_at=record["trigger"]["created_at"],
            metadata={"repository": repository},
        )
        if state != "started":
            self.records.finish_attempt(
                "late-reply", state=state, finished_at="2026-09-23T00:02:10Z",
                trigger_id=trigger_id, provider_review_id="11",
                diagnostic="initial reply has no completion proof",
                artifacts={"metadata": '{"initial": "immutable observation"}'},
            )
        body = (f"Hosted: 0 found / 0 accepted / 0 routed · {fixtures.HEAD} · 1 files\n"
                "<!-- firemud-hosted-review: 11 -->")
        at = "2026-09-23T00:03:00Z"
        payload["data"]["repository"]["pullRequest"]["comments"]["nodes"].append(
            fixtures.comment(40, "owner", body, at)
        )
        checkpoint = evidence.parse_checkpoint_comments([{
            "id": 40, "body": body, "created_at": at, "updated_at": at, "author_login": "owner",
        }])[0][0]
        return payload, checkpoint

    def test_late_verified_reply_preserves_ambiguous_attempt_and_appends_exact_source(self):
        payload, checkpoint = self.late_reply_checkpoint()
        before = self.records.history(PR)
        artifacts = self.records.attempt_artifacts("late-reply")
        with patch.object(hosted, "_git_common_dir", return_value=self.common):
            preview = repair_provider_checkpoints(
                self.records, repo=REPO, pr_number=PR, checkpoints=[checkpoint], actor="operator",
                common=self.common, hosted_payload=payload,
            )
            self.assertEqual(preview["status"], "preview")
            self.assertEqual(self.records.history(PR), before)
            result = repair_provider_checkpoints(
                self.records, repo=REPO, pr_number=PR, checkpoints=[checkpoint], actor="operator",
                common=self.common, hosted_payload=payload, dry_run=False,
            )
            after = self.records.history(PR)
            replay = repair_provider_checkpoints(
                self.records, repo=REPO, pr_number=PR, checkpoints=[checkpoint], actor="operator",
                common=self.common, hosted_payload=payload, dry_run=False,
            )
        self.assertEqual(result["status"], "complete")
        self.assertEqual(replay["status"], "complete")
        self.assertEqual(after["attempts"], before["attempts"])
        self.assertEqual(self.records.attempt_artifacts("late-reply"), artifacts)
        self.assertEqual(self.records.history(PR), after)
        self.assertEqual(len(after["runs"]), 1)
        self.assertEqual(after["runs"][0]["counts"], {"found": 0, "accepted": 0, "routed": 0})
        self.assertEqual(after["provider_origins"][0]["provider_id"], "trigger:10")

    def test_late_reply_retains_hold_when_provider_reply_is_still_active(self):
        payload, checkpoint = self.late_reply_checkpoint()
        response = next(
            item for item in payload["data"]["repository"]["pullRequest"]["comments"]["nodes"]
            if item.get("databaseId") == 11
        )
        response["body"] = "Reviewing your changes, please wait."
        before = self.records.history(PR)
        with (
            patch.object(hosted, "_git_common_dir", return_value=self.common),
            self.assertRaises(SqliteRecordsRepairError),
        ):
            repair_provider_checkpoints(
                self.records, repo=REPO, pr_number=PR, checkpoints=[checkpoint], actor="operator",
                common=self.common, hosted_payload=payload, dry_run=False,
            )
        self.assertEqual(self.records.history(PR), before)

    def test_late_reply_rejects_wrong_immutable_attempt_identity(self):
        for fields in ({"candidate": "c" * 40}, {"repository": "other/repo"}, {"trigger_id": "99"}):
            with self.subTest(fields=fields), tempfile.TemporaryDirectory() as directory:
                self.common = Path(directory)
                self.database = self.common / "controller.sqlite3"
                sqlite_store.SqliteStateStore(self.database).update(lambda state: state)
                self.records = SqliteReviewRecords(self.database)
                self.records.bootstrap()
                payload, checkpoint = self.late_reply_checkpoint(**fields)
                before = self.records.history(PR)
                with (
                    patch.object(hosted, "_git_common_dir", return_value=self.common),
                    self.assertRaisesRegex(SqliteRecordsRepairError, "immutable ambiguous attempt"),
                ):
                    repair_provider_checkpoints(
                        self.records, repo=REPO, pr_number=PR, checkpoints=[checkpoint],
                        actor="operator", common=self.common, hosted_payload=payload, dry_run=False,
                    )
                self.assertEqual(self.records.history(PR), before)

    def record_cli_attempt(
        self,
        *,
        run_id: str = "run.Repair1",
        disposition: str = "routed",
        target_pr: int | None = None,
    ) -> None:
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
            target_pr=target_pr,
            actor="backfill-reviewer",
            reason="owner PR #2879",
            decided_at="2026-09-27T12:00:00Z",
        )

    def imported_resolution_fixture(self, channel: str) -> tuple[evidence.Checkpoint, str]:
        if channel == "cli":
            self.cli_capture(decision_text="1\taccepted\tvalidated source finding\n")
            checkpoint = self.checkpoint("CLI", "<!-- firemud-cli-run: run.Repair1 -->")
        else:
            self.hosted_capture()
            checkpoint = self.checkpoint("Hosted", "<!-- firemud-hosted-review: 700 -->")
        # A real immutable comment author was omitted by the older as_json archive.
        checkpoint = dataclasses.replace(checkpoint, author_login="checkpoint-owner")
        report = repair_provider_checkpoints(
            self.records, repo=REPO, pr_number=PR, checkpoints=[checkpoint],
            actor="backfill-reviewer", common=self.common, dry_run=False,
        )
        return checkpoint, report["items"][0]["run_id"]

    def resolution_status(self, checkpoint: evidence.Checkpoint, run_id: str, channel: str) -> str | None:
        return self.records.source_resolution_status(
            run_id, source_pr=PR, source_channel=channel, source_head=HEAD,
            accepted_count=1, source_checkpoint=checkpoint, source_repository=REPO,
        )

    def resolve_imported_finding(self, run_id: str) -> None:
        key = self.records.history(PR)["findings"][0]["source_finding_key"]
        self.records.record_source_resolution(
            run_id, key, source_pr=PR, resolution_id="resolution.imported",
            fix_sha="d" * 40, actor="fix-owner", proof_note="focused regression passed",
        )

    def test_imported_cli_exact_origin_honors_unresolved_and_resolved_source(self) -> None:
        checkpoint, imported_id = self.imported_resolution_fixture("cli")
        self.assertNotEqual(imported_id, checkpoint.run_id)
        self.assertEqual(self.records.history(PR)["attempts"], [])
        capture = evidence.load_cli_capture(
            checkpoint, repo=REPO, pr_number=PR, common=self.common, records=self.records
        )
        self.assertEqual(capture.decisions[1][0], "accepted")
        self.assertEqual(self.resolution_status(checkpoint, checkpoint.run_id, "cli"), "pending")
        self.resolve_imported_finding(imported_id)
        self.assertEqual(self.resolution_status(checkpoint, checkpoint.run_id, "cli"), "resolved")
        from pr_review.runtime import LiveEvidence, LiveGitHub
        live = LiveEvidence(REPO, LiveGitHub(REPO), records=self.records)
        self.assertEqual(live._source_resolution_status(PR, "cli", checkpoint, HEAD), "resolved")
        self.assertEqual(self.resolution_status(dataclasses.replace(checkpoint, comment_id=11), checkpoint.run_id, "cli"), "pending")

    def test_older_import_archive_replays_without_changing_immutable_bytes(self) -> None:
        checkpoint, imported_id = self.imported_resolution_fixture("cli")
        with sqlite3.connect(self.database) as connection:
            metadata_row = connection.execute(
                "SELECT content FROM imported_artifacts WHERE run_id = ? AND kind = 'metadata'", (imported_id,)
            ).fetchone()
            metadata = json.loads(metadata_row[0])
            metadata.pop("checkpoint_fields")
            old_content = json.dumps(metadata, ensure_ascii=True, sort_keys=True, separators=(",", ":"))
            connection.execute(
                "UPDATE imported_artifacts SET content = ?, source_sha256 = ? WHERE run_id = ? AND kind = 'metadata'",
                (old_content, hashlib.sha256(old_content.encode()).hexdigest(), imported_id),
            )
            before = connection.execute("SELECT * FROM imported_artifacts WHERE run_id = ? ORDER BY kind", (imported_id,)).fetchall()
        replay = repair_provider_checkpoints(
            self.records, repo=REPO, pr_number=PR, checkpoints=[checkpoint],
            actor="backfill-reviewer", common=self.common, dry_run=False,
        )
        self.assertEqual(replay["items"][0]["action"], "already_imported")
        with sqlite3.connect(self.database) as connection:
            self.assertEqual(connection.execute("SELECT * FROM imported_artifacts WHERE run_id = ? ORDER BY kind", (imported_id,)).fetchall(), before)
        supplied = {row[1]: row[2] for row in before}
        metadata["checkpoint_fields"] = dataclasses.asdict(dataclasses.replace(checkpoint, author_login="wrong-owner"))
        supplied["metadata"] = json.dumps(metadata)
        with self.assertRaisesRegex(ReviewRecordsError, "artifacts conflict"):
            self.records.archive_imported_artifacts(imported_id, supplied)

    def test_imported_source_checkpoint_binding_allows_only_semantically_neutral_edits(self) -> None:
        checkpoint, imported_id = self.imported_resolution_fixture("hosted")
        self.resolve_imported_finding(imported_id)
        self.assertEqual(self.resolution_status(checkpoint, imported_id, "hosted"), "resolved")
        edited = dataclasses.replace(checkpoint, updated_at="2026-09-28T12:00:00Z")
        parsed, unparsed = evidence.parse_checkpoint_comments([{
            "id": checkpoint.comment_id,
            "created_at": checkpoint.created_at,
            "updated_at": edited.updated_at,
            "author": {"login": checkpoint.author_login},
            "body": f"Hosted: 1 found / 1 accepted / 0 routed · {HEAD[:12]} · 1 files\n\n<!-- firemud-hosted-review: 700 -->\n",
        }])
        self.assertEqual(unparsed, 0)
        self.assertEqual(self.resolution_status(parsed[0], imported_id, "hosted"), "resolved")
        for change in ({"raw_found": 2}, {"hosted_review_id": 701}, {"file_count": 2}, {"duration_seconds": 20}):
            with self.subTest(change=change):
                self.assertEqual(self.resolution_status(dataclasses.replace(edited, **change), imported_id, "hosted"), "pending")
        with sqlite3.connect(self.database) as connection:
            row = connection.execute("SELECT content FROM imported_artifacts WHERE run_id = ? AND kind = 'metadata'", (imported_id,)).fetchone()
            metadata = json.loads(row[0])
            metadata.pop("checkpoint_fields")
            metadata["checkpoint"].pop("author_login", None)
            old_content = json.dumps(metadata)
            connection.execute("UPDATE imported_artifacts SET content = ? WHERE run_id = ? AND kind = 'metadata'", (old_content, imported_id))
        self.assertEqual(self.resolution_status(edited, imported_id, "hosted"), "resolved")
        self.assertEqual(self.resolution_status(dataclasses.replace(edited, author_login="different-owner"), imported_id, "hosted"), "pending")
        metadata["checkpoint"]["raw_found"] = 2
        with sqlite3.connect(self.database) as connection:
            connection.execute("UPDATE imported_artifacts SET content = ? WHERE run_id = ? AND kind = 'metadata'", (json.dumps(metadata), imported_id))
        self.assertEqual(self.resolution_status(edited, imported_id, "hosted"), "pending")
        with sqlite3.connect(self.database) as connection:
            connection.execute("UPDATE imported_artifacts SET content = ? WHERE run_id = ? AND kind = 'metadata'", (old_content, imported_id))
            connection.execute("UPDATE provider_origins SET checkpoint_fingerprint = ? WHERE run_id = ?", ("0" * 64, imported_id))
        self.assertEqual(self.resolution_status(edited, imported_id, "hosted"), "pending")

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

    def test_missing_capture_uses_explicit_missing_evidence_repair_error(self) -> None:
        checkpoint = self.checkpoint("CLI", "<!-- firemud-cli-run: run.Lost -->")

        with self.assertRaises(MissingHistoricalEvidenceError) as raised:
            repair_provider_checkpoints(
                self.records,
                repo=REPO,
                pr_number=PR,
                checkpoints=[checkpoint],
                actor="backfill-reviewer",
                common=self.common,
                dry_run=True,
            )

        self.assertEqual(raised.exception.code, "missing_historical_evidence")
        self.assertEqual(self.records.history(PR)["historical_gaps"], [])

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

    def test_apply_reports_partial_after_raw_database_failure_and_keeps_prior_item(self) -> None:
        self.cli_capture("run.Partial1")
        self.cli_capture("run.Partial2")
        first = self.checkpoint(
            "CLI", "<!-- firemud-cli-run: run.Partial1 -->", comment_id=14, accepted=0, routed=1
        )
        second = self.checkpoint(
            "CLI", "<!-- firemud-cli-run: run.Partial2 -->", comment_id=15, accepted=0, routed=1
        )
        original_import_one = sqlite_records_repair._import_one
        calls = 0

        def fail_on_second_apply(*args: object, **kwargs: object) -> tuple[dict[str, object], str]:
            nonlocal calls
            calls += 1
            if calls == 4:  # two preflight imports, then the first live item
                raise sqlite3.DatabaseError("injected database failure" + "!" * 5000)
            return original_import_one(*args, **kwargs)

        with patch.object(sqlite_records_repair, "_import_one", side_effect=fail_on_second_apply):
            report = repair_provider_checkpoints(
                self.records,
                repo=REPO,
                pr_number=PR,
                checkpoints=[first, second],
                actor="backfill-reviewer",
                common=self.common,
                dry_run=False,
            )

        self.assertEqual(report["status"], "partial")
        self.assertEqual(len(report["items"]), 1)
        self.assertEqual(report["items"][0]["checkpoint_id"], first.comment_id)
        self.assertTrue(report["items"][0]["origin_linked"])
        self.assertTrue(report["items"][0]["artifacts_archived"])
        self.assertEqual(report["error"], "DatabaseError: injected database failure" + "!" * 960)
        self.assertEqual(
            [run["run_id"] for run in self.records.history(PR)["runs"]],
            [report["items"][0]["run_id"]],
        )
        with sqlite3.connect(self.database) as connection:
            self.assertEqual(connection.execute("SELECT COUNT(*) FROM provider_origins").fetchone()[0], 1)
            self.assertEqual(connection.execute("SELECT COUNT(*) FROM imported_artifacts").fetchone()[0], 3)

    def test_apply_reports_actual_drifted_item_without_false_origin_or_archive(self) -> None:
        self.cli_capture("run.Drift1")
        checkpoint = self.checkpoint(
            "CLI", "<!-- firemud-cli-run: run.Drift1 -->", comment_id=16, accepted=0, routed=1
        )
        original_import_one = sqlite_records_repair._import_one
        calls = 0

        def mutate_after_preflight(*args: object, **kwargs: object) -> tuple[dict[str, object], str]:
            nonlocal calls
            result = original_import_one(*args, **kwargs)
            calls += 1
            if calls == 1:
                (self.common / "coderabbit-review-logs" / "run.Drift1" / "stderr").unlink()
            return result

        with patch.object(sqlite_records_repair, "_import_one", side_effect=mutate_after_preflight):
            report = repair_provider_checkpoints(
                self.records,
                repo=REPO,
                pr_number=PR,
                checkpoints=[checkpoint],
                actor="backfill-reviewer",
                common=self.common,
                dry_run=False,
            )

        self.assertEqual(report["status"], "partial")
        self.assertEqual(report["error"], "source evidence changed after preflight; exact imported run is retained for audit")
        self.assertEqual(len(report["items"]), 1)
        item = report["items"][0]
        self.assertEqual(item["action"], "drifted")
        self.assertEqual(item["provider_id"], "run:run.Drift1")
        self.assertIsInstance(item["run_id"], str)
        self.assertEqual(item["counts"], {"found": 1, "accepted": 0, "routed": 1})
        self.assertEqual(
            item["checkpoint_fingerprint"],
            sqlite_provider_imports._checkpoint_fingerprint(checkpoint),
        )
        self.assertEqual(item["artifact_kinds"], ["cli_events", "metadata"])
        self.assertEqual(set(item["artifact_sha256"]), {"cli_events", "metadata"})
        self.assertFalse(item["origin_linked"])
        self.assertFalse(item["artifacts_archived"])
        with sqlite3.connect(self.database) as connection:
            self.assertEqual(connection.execute("SELECT COUNT(*) FROM provider_origins").fetchone()[0], 0)
            self.assertEqual(connection.execute("SELECT COUNT(*) FROM imported_artifacts").fetchone()[0], 0)
            self.assertEqual(connection.execute("SELECT COUNT(*) FROM review_runs").fetchone()[0], 1)

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

        # The partial native archive cannot be used by ordinary SQL-first
        # reads, but explicit repair can recover it from the exact raw capture.
        with self.assertRaisesRegex(evidence.CaptureInvalid, "records are incomplete or unavailable"):
            evidence.load_cli_capture(checkpoint, REPO, PR, self.common, records=self.records)

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

    def test_repair_links_cli_attempt_when_stored_route_target_is_not_in_capture(self) -> None:
        self.cli_capture("run.Target1")
        self.record_cli_attempt(run_id="run.Target1", target_pr=2879)
        checkpoint = self.checkpoint(
            "CLI", "<!-- firemud-cli-run: run.Target1 -->", comment_id=17, accepted=0, routed=1
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
        self.assertTrue(report["items"][0]["origin_linked"])
        self.assertEqual(self.records.history(PR)["routes"][0]["target_pr"], 2879)
        with sqlite3.connect(self.database) as connection:
            origin = connection.execute("SELECT provider_id, run_id FROM provider_origins").fetchone()
        self.assertEqual(origin, ("run:run.Target1", "run.Target1"))

    def test_repair_rejects_tampered_stored_route_target(self) -> None:
        self.cli_capture("run.TargetTamper")
        self.record_cli_attempt(run_id="run.TargetTamper", target_pr=2879)
        route_id = self.records.history(PR)["routes"][0]["route_id"]
        with sqlite3.connect(self.database) as connection:
            connection.execute(
                "UPDATE routes SET target_pr = ? WHERE route_id = ?",
                (2880, route_id),
            )
        checkpoint = self.checkpoint(
            "CLI", "<!-- firemud-cli-run: run.TargetTamper -->", comment_id=18, accepted=0, routed=1
        )

        with self.assertRaisesRegex(
            SqliteRecordsRepairError,
            "^matching provider attempt conflicts with captured finding or decision evidence$",
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

        with sqlite3.connect(self.database) as connection:
            self.assertEqual(connection.execute("SELECT COUNT(*) FROM provider_origins").fetchone()[0], 0)
            self.assertEqual(connection.execute("SELECT COUNT(*) FROM imported_artifacts").fetchone()[0], 0)

    def test_repair_links_corrected_automatic_cli_attempt_before_repair(self) -> None:
        self.cli_capture(decision_text="1\taccepted\towner PR #2879\n")
        self.record_cli_attempt(disposition="accepted")
        original = self.records.history(PR)["decisions"][0]
        self.records.correct_source_decision(
            "run.Repair1",
            "cli-run:run.Repair1:finding:1",
            supersedes_id=original["decision_id"],
            correction_id="correction.repair-accepted-to-rejected-before-import",
            decision="rejected",
            actor="adjudicator",
            reason="correction retained the audited source evidence",
            decided_at="2026-09-27T12:01:00Z",
        )
        checkpoint = self.checkpoint("CLI", "<!-- firemud-cli-run: run.Repair1 -->")

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
        self.assertEqual(
            report["items"][0]["counts"],
            {"found": 1, "accepted": 0, "routed": 0},
        )
        with sqlite3.connect(self.database) as connection:
            origin = connection.execute("SELECT provider_id, run_id FROM provider_origins").fetchone()
        self.assertEqual(origin, ("run:run.Repair1", "run.Repair1"))

    def test_repair_rejects_tampered_corrected_automatic_cli_counts(self) -> None:
        self.cli_capture(decision_text="1\taccepted\towner PR #2879\n")
        self.record_cli_attempt(disposition="accepted")
        original = self.records.history(PR)["decisions"][0]
        self.records.correct_source_decision(
            "run.Repair1",
            "cli-run:run.Repair1:finding:1",
            supersedes_id=original["decision_id"],
            correction_id="correction.repair-accepted-to-rejected-tamper",
            decision="rejected",
            actor="adjudicator",
            reason="correction retained the audited source evidence",
            decided_at="2026-09-27T12:01:00Z",
        )
        with sqlite3.connect(self.database) as connection:
            connection.execute(
                "UPDATE review_runs SET accepted_count = 1 WHERE run_id = ?",
                ("run.Repair1",),
            )
        checkpoint = self.checkpoint("CLI", "<!-- firemud-cli-run: run.Repair1 -->")

        with self.assertRaisesRegex(
            SqliteRecordsRepairError,
            "^matching provider attempt conflicts with captured run identity or counts$",
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

        with sqlite3.connect(self.database) as connection:
            self.assertEqual(connection.execute("SELECT COUNT(*) FROM provider_origins").fetchone()[0], 0)
            self.assertEqual(connection.execute("SELECT COUNT(*) FROM imported_artifacts").fetchone()[0], 0)

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

    def test_provider_import_rejects_empty_or_ambiguous_checkpoint_selection(self) -> None:
        records = type("FakeRecords", (), {"history": lambda self, pr: {"pr": pr}})()
        arguments = [
            "records", "import-provider", "--pr", str(PR), "--channel", "hosted",
            "--checkpoint-id", "10", "--actor", "backfill-reviewer", "--scope", "broad",
            "--repo", REPO, "--database", str(self.database),
        ]
        with (
            patch.object(cli, "_records_store", return_value=records),
            patch.object(cli.github, "infer_repo", return_value=REPO),
            patch.object(cli.sqlite_records_repair, "repair_provider_checkpoints") as importer,
            patch.object(cli, "_provider_checkpoints", side_effect=[([], {"empty": True}), ([object(), object()], {"ambiguous": True})]),
        ):
            for _ in range(2):
                errors = io.StringIO()
                with contextlib.redirect_stderr(errors):
                    status = cli.main(arguments)
                self.assertEqual(status, 2)
                self.assertIn("checkpoint ID must identify exactly one parsed checkpoint comment", errors.getvalue())
        importer.assert_not_called()


if __name__ == "__main__":
    unittest.main()
