import json
import sqlite3
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).parents[1]))

from fire_controller.jobs import (
    JOB_WORKERS,
    JobError,
    JobNotFound,
    JobsNotBootstrapped,
    JobStore,
    RevisionConflict,
)
from pr_review.sqlite_backup import BackupError, _validate_database
from pr_review.sqlite_store import SqliteStateStore


class JobStoreTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.database = Path(self.temp.name) / "controller.sqlite3"
        self.store = JobStore(self.database)

    def bootstrap(self) -> None:
        self.store.bootstrap()

    def test_construction_is_side_effect_free_and_bootstrap_is_explicit(self) -> None:
        self.assertFalse(self.database.exists())
        with self.assertRaises(JobsNotBootstrapped):
            self.store.list()
        self.assertFalse(self.database.exists())
        self.bootstrap()
        self.assertTrue(self.database.exists())
        self.assertEqual(SqliteStateStore(self.database).load().to_dict(), SqliteStateStore(self.database).load().to_dict())

    def test_bootstrap_preserves_review_state_and_writer_metadata(self) -> None:
        controller = SqliteStateStore(self.database)
        controller.update(lambda state: state)
        with sqlite3.connect(self.database) as connection:
            original_state = connection.execute("SELECT state_json FROM review_state WHERE singleton = 1").fetchone()[0]
            original_metadata = connection.execute(
                "SELECT data_model_version, min_writer_build FROM controller_metadata WHERE singleton = 1"
            ).fetchone()
        self.bootstrap()
        with sqlite3.connect(self.database) as connection:
            self.assertEqual(
                connection.execute("SELECT state_json FROM review_state WHERE singleton = 1").fetchone()[0],
                original_state,
            )
            self.assertEqual(
                connection.execute(
                    "SELECT data_model_version, min_writer_build FROM controller_metadata WHERE singleton = 1"
                ).fetchone(),
                original_metadata,
            )

    def test_primary_is_independent_from_status_and_switch_is_revisioned(self) -> None:
        self.bootstrap()
        first = self.store.create("first", "Gameplay", "First task")
        second = self.store.create("second", "Gameplay", "Second task", primary=False)
        with self.assertRaisesRegex(JobError, "already has primary"):
            self.store.create("third", "Gameplay", "Third task")

        switched = self.store.revise(second["id"], 1, primary=True)
        old_job = self.store.get(first["id"])
        self.assertTrue(switched["primary"])
        self.assertFalse(old_job["primary"])
        self.assertEqual(old_job["revision"], 2)
        self.assertFalse(self.store.history(first["id"], revision=2)["primary"])

        blocked = self.store.revise(second["id"], switched["revision"], status="blocked")
        self.assertEqual(blocked["status"], "blocked")
        self.assertTrue(blocked["primary"])
        parked = self.store.revise(second["id"], blocked["revision"], status="parked")
        self.assertFalse(parked["primary"])
        self.assertEqual(parked["status"], "parked")

    def test_full_history_retains_updates_checkpoints_and_consumed_notes(self) -> None:
        self.store.bootstrap()
        job = self.store.create("history-full", "General", "History", brief="Current brief")
        for index in range(12):
            self.store.append_update(job["id"], f"Update {index}")
        self.store.checkpoint(job["id"], "First done", "First next")
        self.store.checkpoint(job["id"], "Second done", "Second next")
        note = self.store.note("Consumed source", job=job["id"])
        self.store.note_status(note["id"], "consumed", expected_revision=1)
        current = self.store.get(job["id"])
        self.assertEqual(len(current["updates"]), 10)
        self.assertEqual(current["notes"], [])
        full = self.store.get(job["id"], full_history=True)
        self.assertEqual([item["body"] for item in full["updates"]], [f"Update {index}" for index in range(12)])
        self.assertEqual(len(full["checkpoints"]), 2)
        self.assertEqual(full["notes"][0]["status"], "consumed")

    def test_worker_aliases_are_exact_bounded_project_values(self) -> None:
        self.bootstrap()
        job = self.store.create("custom-worker-job", "Worker Alpha", "Project-specific assignment")
        self.assertEqual(job["worker"], "Worker Alpha")
        self.assertEqual(self.store.list(worker="Worker Alpha")[0]["id"], job["id"])
        with self.assertRaisesRegex(JobError, "whitespace"):
            self.store.create("bad-worker-job", " Worker Alpha", "Invalid alias")

    def test_compare_and_swap_brief_versions_and_activity_history(self) -> None:
        self.bootstrap()
        job = self.store.create("brief-job", "General", "A task", brief="# First brief")
        revised = self.store.revise(job["id"], 1, progress="Started")
        self.assertEqual(revised["revision"], 2)
        self.assertEqual(self.store.history(job["id"], revision=2)["brief_revision"], 1)
        with self.assertRaises(RevisionConflict):
            self.store.revise(job["id"], 1, title="Stale title")

        changed_brief = self.store.revise(job["id"], 2, brief="# Updated brief")
        self.assertEqual(changed_brief["revision"], 3)
        self.assertEqual(changed_brief["brief"], "# Updated brief")
        self.assertEqual(self.store.history(job["id"], revision=1)["brief"], "# First brief")
        self.assertEqual(self.store.history(job["id"], revision=3)["brief_revision"], 2)
        self.assertIn("Updated brief", self.store.diff(job["id"], 1, 3))

        self.store.append_update(job["id"], "One")
        self.store.append_update(job["id"], "Two", kind="handoff")
        self.store.append_update(job["id"], "Three")
        current = self.store.get(job["id"], latest=2)
        self.assertEqual([update["body"] for update in current["updates"]], ["Two", "Three"])
        self.assertEqual(current["revision"], 3)
        self.assertEqual([row["revision"] for row in self.store.history(job["id"], limit=2)], [3, 2])
        self.assertEqual(len(self.store.history(job["id"], limit=None)), 3)

    def test_checklist_checkpoint_notes_and_dismissal_reason(self) -> None:
        self.bootstrap()
        job = self.store.create("check-job", "Document", "Write docs", checklist=[])
        added = self.store.checklist(job["id"], 1, "add", text="Review source")
        item = added["checklist"][0]
        done = self.store.checklist(job["id"], 2, "done", item_id=item["id"])
        self.assertTrue(done["checklist"][0]["done"])
        reopened = self.store.checklist(job["id"], 3, "reopen", item_id=item["id"])
        self.assertFalse(reopened["checklist"][0]["done"])

        checkpoint = self.store.checkpoint(
            job["id"],
            done="Docs drafted",
            next_steps="Run link check",
            blocker="",
            pointers={"branch": "codex/docs", "worktree": "/tmp/docs", "pr": 42, "proof": "focused tests"},
        )
        self.assertEqual(checkpoint["pointers"]["pr"], 42)
        self.assertEqual(self.store.get(job["id"])["latest_checkpoint"]["sequence"], checkpoint["sequence"])

        worker_note = self.store.note("Check the canonical wording", worker="Document", kind="instruction")
        job_note = self.store.note("Resume after link check", job="check-job", phase="docs")
        current = self.store.get(job["id"])
        self.assertEqual({note["id"] for note in current["notes"]}, {worker_note["id"], job_note["id"]})
        with self.assertRaisesRegex(JobError, "require a dismissal reason"):
            self.store.note_status(job_note["id"], "dismissed", expected_revision=1)
        dismissed = self.store.note_status(
            job_note["id"], "dismissed", "The phase was cancelled", expected_revision=1
        )
        self.assertEqual(dismissed["dismissal_reason"], "The phase was cancelled")
        self.assertEqual(dismissed["revision"], 2)
        self.assertEqual(self.store.note_history(job_note["id"], revision=1)["status"], "pending")
        self.assertEqual(self.store.note_history(job_note["id"], revision=2)["dismissal_reason"], "The phase was cancelled")
        self.assertEqual(self.store.notes(job="check-job"), [])
        self.assertEqual(self.store.notes(job="check-job", status=None)[0]["status"], "dismissed")

    def test_checklist_add_over_limit_leaves_job_revision_and_history_unchanged(self) -> None:
        self.bootstrap()
        checklist = [
            {"id": f"item-{index}", "text": f"Checklist item {index}", "done": False}
            for index in range(200)
        ]
        job = self.store.create("full-checklist", "Document", "Review all items", checklist=checklist)
        history_before = self.store.history(job["id"], limit=None)

        with self.assertRaisesRegex(JobError, "at most 200"):
            self.store.checklist(job["id"], job["revision"], "add", text="One item too many")

        current = self.store.get(job["id"])
        self.assertEqual(current["revision"], job["revision"])
        self.assertEqual(current["checklist"], checklist)
        self.assertEqual(self.store.history(job["id"], limit=None), history_before)

    def test_backup_validator_refuses_current_job_that_disagrees_with_latest_history(self) -> None:
        self.bootstrap()
        job = self.store.create("damaged-current", "General", "Recorded title")
        with sqlite3.connect(self.database) as connection:
            connection.execute("UPDATE jobs SET title = ? WHERE id = ?", ("Contradictory title", job["id"]))
        damaged_database = self.database.read_bytes()

        with self.assertRaises(BackupError):
            _validate_database(self.database, "damaged job fixture")

        self.assertEqual(self.database.read_bytes(), damaged_database)
        with sqlite3.connect(self.database) as connection:
            self.assertEqual(
                connection.execute("SELECT title, revision FROM jobs WHERE id = ?", (job["id"],)).fetchone(),
                ("Contradictory title", 1),
            )
            self.assertEqual(
                connection.execute("SELECT COUNT(*) FROM job_revisions WHERE job_id = ?", (job["id"],)).fetchone()[0],
                1,
            )

    def test_note_cas_correction_reopen_and_bounded_history(self) -> None:
        self.bootstrap()
        target = self.store.create("future-job", "General", "Future phase assignment")
        note = self.store.note("Old instruction", worker="General", phase="first", kind="instruction")
        edited = self.store.revise_note(
            note["id"], 1, body="Corrected instruction", job="future-job", phase=None
        )
        self.assertEqual(edited["revision"], 2)
        self.assertEqual(edited["body"], "Corrected instruction")
        self.assertEqual(edited["phase"], None)
        self.assertEqual(edited["job"], target["id"])
        dismissed = self.store.note_status(
            note["id"], "dismissed", "No longer needed", expected_revision=2
        )
        reopened = self.store.note_status(note["id"], "pending", expected_revision=dismissed["revision"])
        self.assertEqual(reopened["status"], "pending")
        self.assertEqual(reopened["dismissal_reason"], "")
        self.assertEqual(self.store.note_history(note["id"], revision=3)["dismissal_reason"], "No longer needed")
        self.assertEqual([item["revision"] for item in self.store.note_history(note["id"], limit=2)], [4, 3])
        with self.assertRaises(RevisionConflict):
            self.store.revise_note(note["id"], 1, body="Stale update")
        with sqlite3.connect(self.database) as connection:
            JobStore.validate(connection)

    def test_note_job_aliases_normalize_and_unknown_correction_is_atomic(self) -> None:
        self.bootstrap()
        first = self.store.create("first-note-job", "General", "First")
        second = self.store.create("second-note-job", "Document", "Second")
        by_id = self.store.note("Original ID note", job=first["id"])
        by_name = self.store.note("Original name note", job=first["name"])
        self.assertEqual(by_name["job"], first["id"])
        expected_ids = {by_id["id"], by_name["id"]}
        for selector in (first["id"], first["name"]):
            self.assertEqual({row["id"] for row in self.store.notes(job=selector)}, expected_ids)
        corrected = self.store.revise_note(by_name["id"], 1, job=second["name"], body="Corrected text")
        self.assertEqual(corrected["job"], second["id"])
        self.assertEqual(self.store.note_history(by_name["id"], revision=1)["body"], "Original name note")
        before_history = self.store.note_history(by_name["id"])
        before_notes = self.store.notes(status=None)
        with self.assertRaises(JobNotFound):
            self.store.revise_note(by_name["id"], 2, job="missing-job", body="Must not overwrite")
        self.assertEqual(self.store.notes(status=None), before_notes)
        self.assertEqual(self.store.note_history(by_name["id"]), before_history)
        with self.assertRaises(JobNotFound):
            self.store.note("Must not insert", job="missing-job")
        with self.assertRaises(JobNotFound):
            self.store.notes(job="missing-job")
        future = self.store.note("Future source", worker="Overseer", phase="Unit 3A")
        self.assertIsNone(future["job"])
        self.assertEqual(self.store.notes(phase="Unit 3A"), [future])

    def test_note_import_resolves_planned_job_and_rejects_unknown_without_writes(self) -> None:
        self.bootstrap()
        brief = Path(self.temp.name) / "brief.md"
        brief.write_text("Current original instructions")
        manifest = {"version": 1, "jobs": [{"name": "imported-note-job", "worker": "General",
                    "title": "Imported", "brief_file": str(brief)}],
                    "notes": [{"body": "Imported original note", "job": "imported-note-job"}]}
        self.assertFalse(self.store.import_briefs(manifest)["applied"])
        self.store.import_briefs(manifest, apply=True)
        job = self.store.get("imported-note-job")
        self.assertEqual(job["notes"][0]["job"], job["id"])
        manifest["jobs"][0]["name"] = "must-not-create"
        manifest["jobs"][0]["worker"] = "Other Worker"
        manifest["notes"][0]["job"] = "unknown-import-target"
        for apply in (False, True):
            with self.assertRaises(JobNotFound):
                self.store.import_briefs(manifest, apply=apply)
        self.assertEqual(len(self.store.list()), 1)

    def test_backup_refuses_broken_current_and_historical_note_links(self) -> None:
        from pr_review.sqlite_backup import BackupError, create_snapshot, restore_snapshot

        self.bootstrap()
        job = self.store.create("linked-note", "General", "Target")
        note = self.store.note("Original text", job=job["name"])
        self.store.revise_note(note["id"], 1, body="Current text")
        snapshot = Path(self.temp.name) / "valid.sqlite3"
        create_snapshot(self.database, snapshot)
        restored = Path(self.temp.name) / "valid-restored.sqlite3"
        restore_snapshot(snapshot, restored)
        self.assertEqual(JobStore(restored).get(job["id"])["notes"][0]["body"], "Current text")
        for historical in (False, True):
            broken = Path(self.temp.name) / f"broken-{historical}.sqlite3"
            create_snapshot(self.database, broken)
            with sqlite3.connect(broken) as connection:
                if historical:
                    state = json.loads(connection.execute(
                        "SELECT state_json FROM job_note_revisions WHERE note_id = ? AND revision = 1",
                        (note["id"],),
                    ).fetchone()[0])
                    state["job"] = "missing-stable-job"
                    connection.execute(
                        "UPDATE job_note_revisions SET state_json = ? WHERE note_id = ? AND revision = 1",
                        (json.dumps(state), note["id"]),
                    )
                else:
                    connection.execute("UPDATE job_notes SET job = ? WHERE id = ?", ("missing-stable-job", note["id"]))
            destination = Path(self.temp.name) / f"refused-{historical}.sqlite3"
            with self.assertRaises(BackupError) as refused:
                restore_snapshot(broken, destination)
            self.assertIn("note job link", str(refused.exception.__cause__))
            self.assertFalse(destination.exists())

    def test_workstream_is_optional_and_preserved_in_revisions_and_imports(self) -> None:
        self.bootstrap()
        job = self.store.create(
            "workstream-job", "General", "Map task", workstream_id="future-map-track"
        )
        self.assertEqual(self.store.list(workstream_id="future-map-track")[0]["workstream_id"], "future-map-track")
        self.assertEqual(self.store.history(job["id"], revision=1)["workstream_id"], "future-map-track")
        revised = self.store.revise(job["id"], 1, workstream_id=None)
        self.assertIsNone(revised["workstream_id"])
        self.assertEqual(self.store.history(job["id"], revision=1)["workstream_id"], "future-map-track")

    def test_list_reads_only_lightweight_public_rows(self) -> None:
        self.bootstrap()
        self.store.create("public-job", "Overseer", "Public summary", brief="private instructions", summary="visible")
        with patch.object(JobStore, "_current_brief", side_effect=AssertionError("list loaded a private brief")):
            rows = self.store.list(search="visible")
        self.assertEqual(len(rows), 1)
        self.assertNotIn("brief", rows[0])
        self.assertTrue(rows[0]["primary"])

    def test_import_preview_apply_provenance_and_idempotence(self) -> None:
        self.bootstrap()
        brief_path = Path(self.temp.name) / "brief.md"
        source_path = Path(self.temp.name) / "source.md"
        brief_path.write_text("# Imported full instructions\n", encoding="utf-8")
        source_path.write_text("Historical source text\n", encoding="utf-8")
        manifest = {
            "version": 1,
            "provenance": {"source": "worker-briefs", "version": "2026-10"},
            "jobs": [
                {
                    "name": "imported-primary",
                    "worker": "Gameplay",
                    "title": "Imported work",
                    "workstream_id": "imported-track",
                    "brief_file": str(brief_path),
                    "source_files": [str(source_path)],
                    "summary": "Public summary",
                },
                {
                    "name": "imported-parked",
                    "worker": "Gameplay",
                    "title": "Future work",
                    "brief_file": str(brief_path),
                    "status": "parked",
                    "primary": False,
                },
            ],
            "notes": [{"body": "Revisit after the active slice", "worker": "Gameplay", "phase": "next"}],
        }
        preview = self.store.import_briefs(manifest)
        self.assertFalse(preview["applied"])
        self.assertEqual(len(self.store.list()), 0)
        applied = self.store.import_briefs(manifest, apply=True)
        self.assertTrue(applied["applied"])
        self.assertEqual(len(applied["job_ids"]), 2)
        self.assertEqual(self.store.get("imported-primary")["brief"], "# Imported full instructions\n")
        self.assertEqual(self.store.get("imported-primary")["workstream_id"], "imported-track")
        self.assertTrue(self.store.get("imported-primary")["primary"])
        repeated = self.store.import_briefs(manifest, apply=True)
        self.assertTrue(repeated["already_applied"])
        self.assertEqual(len(self.store.list()), 2)
        with sqlite3.connect(self.database) as connection:
            source_record = connection.execute("SELECT source_contents_json, provenance_json FROM job_imports").fetchone()
        archived_sources = json.loads(source_record[0])
        self.assertEqual({entry["content"] for entry in archived_sources}, {"# Imported full instructions\n", "Historical source text\n"})
        self.assertEqual(json.loads(source_record[1])["version"], "2026-10")

    def test_rejects_unknown_fields_invalid_status_and_missing_identifiers(self) -> None:
        self.bootstrap()
        with self.assertRaises(JobError):
            self.store.create("bad-status", "Gameplay", "Bad", status="primary")
        job = self.store.create("valid-job", "Gameplay", "Valid")
        with self.assertRaisesRegex(JobError, "unknown job fields"):
            self.store.revise(job["id"], 1, private=True)
        with self.assertRaises(JobNotFound):
            self.store.get("missing-job")

    def test_read_only_backup_validation_checks_the_job_schema(self) -> None:
        self.bootstrap()
        self.store.create("backup-job", "Overseer", "Backup validation", brief="stored brief")
        uri = f"{self.database.resolve().as_uri()}?mode=ro"
        with sqlite3.connect(uri, uri=True) as connection:
            JobStore.validate(connection)

    def test_lane_state_pause_persists_until_explicit_resume_and_tracks_job_visibility(self) -> None:
        self.bootstrap()
        active = self.store.create("active-lane", "General", "Active", primary=True)
        blocked = self.store.create("blocked-lane", "General", "Blocked", status="blocked", primary=False)
        self.store.create("parked-lane", "General", "Parked", status="parked", primary=False)
        lane = self.store.lane_state("General")
        self.assertEqual(lane["status"], "active")
        self.assertEqual(lane["primary"]["id"], active["id"])
        self.assertEqual({item["status"] for item in lane["jobs"]}, {"active", "blocked", "parked"})
        self.assertEqual(lane["parked_count"], 1)

        self.store.revise(active["id"], active["revision"], status="completed")
        self.assertEqual(self.store.lane_state("General")["status"], "blocked")
        paused = self.store.pause(
            "General",
            reason="Waiting for direction",
            job=blocked["id"],
            checkpoint={"done": "Evidence gathered", "next_steps": "Wait", "blocker": "Direction"},
            update="Pausing pending direction",
        )
        self.assertEqual(paused["status"], "paused")
        self.assertEqual(paused["pause_reason"], "Waiting for direction")
        new_job = self.store.create("while-paused", "Worker Alpha", "New assignment")
        self.assertEqual(self.store.lane_state("General")["status"], "paused")
        self.store.revise(new_job["id"], new_job["revision"], worker="General")
        self.assertEqual(self.store.lane_state("General")["status"], "paused")
        self.assertEqual(self.store.get(blocked["id"])["latest_checkpoint"]["done"], "Evidence gathered")
        self.assertEqual(self.store.get(blocked["id"])["updates"][-1]["body"], "Pausing pending direction")
        resumed = self.store.resume("General", job=blocked["id"], checkpoint={"done": "Resumed", "next_steps": "Continue"})
        self.assertEqual(resumed["status"], "active")
        self.assertFalse(resumed["paused"])
        self.assertEqual(self.store.lane_state("General")["status"], "active")
        self.assertEqual({row["worker"] for row in self.store.lanes()}, {"General"})
        self.assertEqual(len(self.store.lanes(JOB_WORKERS)), len(JOB_WORKERS))
        with sqlite3.connect(self.database) as connection:
            JobStore.validate(connection)

    def test_lane_idle_ignores_parked_jobs_and_resume_checkpoint_requires_job(self) -> None:
        self.bootstrap()
        self.store.create("only-parked", "Gameplay", "Parked future", status="parked", primary=False)
        self.assertEqual(self.store.lane_state("Gameplay")["status"], "idle")
        with self.assertRaisesRegex(JobError, "requires a job"):
            self.store.resume("Gameplay", checkpoint={"done": "Done", "next_steps": "Next"})

    def test_current_operations_honour_selected_writer_build_without_changing_floor(self) -> None:
        from pr_review.sqlite_store import WRITER_BUILD
        self.bootstrap()
        job = self.store.create("fenced", "Worker Alpha", "Retained")
        with sqlite3.connect(self.database) as connection:
            connection.execute("UPDATE controller_metadata SET min_writer_build = ?", (WRITER_BUILD + 1,))
        with self.assertRaisesRegex(JobError, "writer build"):
            self.store.get(job["id"])
        future = JobStore(self.database, writer_build=WRITER_BUILD + 1)
        self.assertEqual(future.get(job["id"])["title"], "Retained")
        with sqlite3.connect(self.database) as connection:
            self.assertEqual(connection.execute("SELECT min_writer_build FROM controller_metadata").fetchone()[0], WRITER_BUILD + 1)


if __name__ == "__main__":
    unittest.main()
