from __future__ import annotations

import json
import os
import sqlite3
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parents[1]))

from fire_controller.inbox import InboxStore
from fire_controller.jobs import JobsNotBootstrapped, JobsSchemaIncompatible, JobStore, RevisionConflict
from fire_controller.map import (
    MAP_TABLES,
    MapImportConflict,
    MapNotImported,
    WorkstreamStore,
    editorial_mapping,
)
from fire_controller.web import public_jobs
from pr_review.sqlite_backup import BackupError, _validate_database, create_snapshot, restore_snapshot
from pr_review.sqlite_review_records import SqliteReviewRecords
from pr_review.sqlite_store import SQLITE_SCHEMA_VERSION, WRITER_BUILD, SqliteStateStore
from pr_review.state import ControllerStateStore, ReviewState, StateError


def _site_sources(directory: Path, *, worker: str = "Build & Tools") -> tuple[Path, Path]:
    status = {
        "review_tool": "/private/local/controller",
        "review_front": 123,
        "stack": [{"private_review_payload": "PRIVATE_REVIEW_SENTINEL"}],
        "lanes": [
            {
                "name": worker,
                "status": "PAUSED",
                "task": ["Build renderer", "Review output"],
                "up_next": "Run isolated proof",
                "blocker": "Waiting for fixture",
                "brief": "../task-briefs/build-tools.md",
                "verified_at": "2026-10-03T00:00:00Z",
                "private_note": "PRIVATE_NOTE_SENTINEL",
            }
        ],
    }
    progress = {
        "headline": "Curated map",
        "tracks": [
            {
                "id": "shared-foundations",
                "name": "Shared foundations",
                "state": "ACTIVE",
                "now": "Current work",
                "milestone": "Next milestone",
                "purpose": "Static purpose",
                "phases": [
                    {"name": "Phase one", "state": "NOW"},
                    {"name": "Phase two", "state": "LATER"},
                ],
            }
        ],
        "domains": [{"name": "Runtime", "file": "runtime.md", "note": "Static domain summary"}],
        "return_points": [
            {"name": "Resume Phase One", "trigger": "When the prerequisite lands", "state": "WAITING"}
        ],
    }
    status_path = directory / "status.json"
    progress_path = directory / "progress.json"
    status_path.write_text(json.dumps(status), encoding="utf-8")
    progress_path.write_text(json.dumps(progress), encoding="utf-8")
    return status_path, progress_path


class WorkstreamStoreTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.database = self.root / "controller.sqlite3"
        self.status_source, self.progress_source = _site_sources(self.root)
        self.editorial = editorial_mapping(json.loads(self.progress_source.read_text(encoding="utf-8")))
        self.store = WorkstreamStore(self.database)

    def tearDown(self):
        self.temporary.cleanup()

    def test_long_alias_names_are_distinct_and_import_reapply_is_idempotent(self):
        status = json.loads(self.status_source.read_text())
        first = "a" * 70 + "x"
        second = "a" * 70 + "y"
        status["lanes"] = [{**status["lanes"][0], "name": worker} for worker in (first, second)]
        self.status_source.write_text(json.dumps(status))
        self.store.bootstrap()
        preview = self.store.import_site(self.status_source, self.progress_source)
        names = [row["name"] for row in preview["lane_jobs"]]
        self.assertEqual(len(set(names)), 2)
        self.assertTrue(all(len(name) <= 80 for name in names))
        applied = self.store.import_site(self.status_source, self.progress_source, apply=True)
        self.assertTrue(applied["applied"])
        repeated = self.store.import_site(self.status_source, self.progress_source, apply=True)
        self.assertTrue(repeated["already_applied"])
        self.assertEqual({job["worker"] for job in repeated["lane_jobs"]}, {first, second})
        self.assertEqual({job["name"] for job in repeated["lane_jobs"]}, set(names))
        for control in ("Build\nTeam", "Build\tTeam"):
            status["lanes"][0]["name"] = control
            self.status_source.write_text(json.dumps(status))
            with self.assertRaisesRegex(ValueError, "worker alias"):
                self.store.import_site(self.status_source, self.progress_source)

    def test_bootstrap_initializes_controller_and_independent_map_schema(self):
        self.store.bootstrap()
        with sqlite3.connect(self.database) as connection:
            tables = {row[0] for row in connection.execute("SELECT name FROM sqlite_master WHERE type='table'")}
            self.assertTrue(set(MAP_TABLES).issubset(tables))
            self.assertFalse(any(name.startswith("job_") for name in tables))
            WorkstreamStore.validate(connection)

    def test_v1_bootstrap_and_normal_map_operations_preserve_controller_provenance(self):
        controller = SqliteStateStore(self.database)
        controller.update(lambda state: state)
        with sqlite3.connect(self.database) as connection:
            state = json.loads(
                connection.execute("SELECT state_json FROM review_state WHERE singleton = 1").fetchone()[0]
            )
            state["schema_version"] = 1
            state.pop("review_period_decisions", None)
            connection.execute(
                "UPDATE controller_metadata SET data_model_version = 1, min_writer_build = 9 WHERE singleton = 1"
            )
            connection.execute(
                "UPDATE review_state SET state_json = ? WHERE singleton = 1",
                (json.dumps(state, separators=(",", ":")),),
            )

        self.store.bootstrap()
        with sqlite3.connect(self.database) as connection:
            self.assertEqual(
                connection.execute(
                    "SELECT controller_data_model_version FROM map_metadata WHERE singleton = 1"
                ).fetchone(),
                (1,),
            )
            state = json.loads(connection.execute(
                "SELECT state_json FROM review_state WHERE singleton = 1"
            ).fetchone()[0])
            self.assertEqual(state["schema_version"], 1)

        self.assertFalse(self.store.has_workstream("shared-foundations"))
        preview = self.store.import_site(self.status_source, self.progress_source)
        applied = self.store.import_site(
            self.status_source,
            self.progress_source,
            apply=True,
            expected_fingerprint=preview["source_fingerprint"],
        )
        self.assertTrue(applied["applied"])
        first = self.store.get("shared-foundations", self.editorial)
        self.store.update("shared-foundations", first["revision"], self.editorial, state="PAUSED")

        controller.update(lambda state: state)

        listed = self.store.list(self.editorial)
        self.assertEqual(listed["workstreams"][0]["state"], "PAUSED")
        current = self.store.get("shared-foundations", self.editorial)
        changed = self.store.update(
            "shared-foundations", current["revision"], self.editorial, now="Mutation after controller upgrade"
        )
        self.assertEqual(changed["now"], "Mutation after controller upgrade")
        with sqlite3.connect(self.database) as connection:
            self.assertEqual(
                connection.execute(
                    "SELECT data_model_version FROM controller_metadata WHERE singleton = 1"
                ).fetchone(),
            (3,),
            )
            self.assertEqual(
                connection.execute(
                    "SELECT controller_data_model_version FROM map_metadata WHERE singleton = 1"
                ).fetchone(),
                (1,),
            )

    def test_refuses_unrelated_database_without_changing_it(self):
        with sqlite3.connect(self.database) as connection:
            connection.execute("CREATE TABLE unrelated (value TEXT)")
            connection.execute("INSERT INTO unrelated VALUES ('preserve')")
        before = self.database.read_bytes()
        with self.assertRaises(JobsSchemaIncompatible):
            self.store.bootstrap()
        self.assertEqual(self.database.read_bytes(), before)

    def test_import_preview_apply_revision_history_and_idempotence(self):
        self.store.bootstrap()
        status_before = self.status_source.read_bytes()
        progress_before = self.progress_source.read_bytes()
        preview = self.store.import_site(self.status_source, self.progress_source)
        self.assertFalse(preview["applied"])
        self.assertEqual(len(preview["lane_jobs"]), 1)
        lane = preview["lane_jobs"][0]
        self.assertEqual(lane["worker"], "Build & Tools")
        self.assertEqual(lane["name"], "site-build-tools")
        self.assertEqual(lane["status"], "active")
        self.assertTrue(lane["worker_paused"])
        self.assertEqual([item["text"] for item in lane["checklist"]], ["Build renderer", "Review output"])
        self.assertEqual(self.status_source.read_bytes(), status_before)
        self.assertEqual(self.progress_source.read_bytes(), progress_before)

        result = self.store.import_site(
            self.status_source,
            self.progress_source,
            apply=True,
            expected_fingerprint=preview["source_fingerprint"],
        )
        self.assertTrue(result["applied"])
        self.assertNotIn("PRIVATE_REVIEW_SENTINEL", self.database.read_bytes().decode("latin1"))
        self.assertNotIn("PRIVATE_NOTE_SENTINEL", self.database.read_bytes().decode("latin1"))

        current = self.store.list(self.editorial)
        self.assertTrue(self.store.has_workstream("shared-foundations"))
        self.assertFalse(self.store.has_workstream("not-imported"))
        self.assertEqual(len(current["workstreams"]), 1)
        self.assertEqual(len(current["return_points"]), 1)
        workstream = current["workstreams"][0]
        self.assertEqual(workstream["phase_states"], {"Phase one": "NOW", "Phase two": "LATER"})
        updated = self.store.update(
            "shared-foundations",
            workstream["revision"],
            self.editorial,
            state="PAUSED",
            now="Resume after review",
            phase_states={"Phase two": "NEXT"},
        )
        self.assertEqual(updated["revision"], 2)
        self.assertEqual(updated["phase_states"]["Phase two"], "NEXT")
        with self.assertRaises(RevisionConflict):
            self.store.update("shared-foundations", workstream["revision"], self.editorial, state="STALE")
        point = current["return_points"][0]
        self.assertEqual(point["id"], "resume-phase-one")
        changed_point = self.store.update_return_point(
            point["id"], point["revision"], self.editorial, state="READY"
        )
        self.assertEqual(changed_point["revision"], 2)
        history = self.store.history("workstream", "shared-foundations", limit=50, offset=0)
        self.assertEqual([item["revision"] for item in history], [2, 1])
        repeated = self.store.import_site(self.status_source, self.progress_source, apply=True)
        self.assertTrue(repeated["already_applied"])
        self.assertEqual(repeated["lane_jobs"], preview["lane_jobs"])

    def test_changed_sources_do_not_apply_against_a_preview(self):
        self.store.bootstrap()
        preview = self.store.import_site(self.status_source, self.progress_source)
        self.progress_source.write_text(
            self.progress_source.read_text(encoding="utf-8").replace("Current work", "Changed source"),
            encoding="utf-8",
        )
        with self.assertRaises(MapImportConflict):
            self.store.import_site(
                self.status_source,
                self.progress_source,
                apply=True,
                expected_fingerprint=preview["source_fingerprint"],
            )
        with sqlite3.connect(self.database) as connection:
            self.assertEqual(connection.execute("SELECT COUNT(*) FROM map_imports").fetchone()[0], 0)
            self.assertEqual(connection.execute("SELECT COUNT(*) FROM map_workstreams").fetchone()[0], 0)

    def test_slug_collisions_are_deterministic_and_unique(self):
        status = {
            "lanes": [
                {"name": "Build Team", "task": "One"},
                {"name": "Build-Team", "task": "Two"},
            ]
        }
        from fire_controller.map import _lane_job_plan

        first = _lane_job_plan(status)
        second = _lane_job_plan(status)
        names = [item["name"] for item in first]
        self.assertEqual(names, [item["name"] for item in second])
        self.assertEqual(len(set(names)), 2)

    def test_list_requires_an_exact_editorial_pairing(self):
        self.store.bootstrap()
        self.store.import_site(self.status_source, self.progress_source, apply=True)
        with self.assertRaises(MapNotImported):
            self.store.list({"workstreams": {}, "return_points": {}})

    def test_read_before_bootstrap_does_not_create_a_database(self):
        self.assertFalse(self.database.exists())
        with self.assertRaises(JobsNotBootstrapped):
            self.store.list(self.editorial)
        self.assertFalse(self.database.exists())

    def test_backup_validator_refuses_damaged_map_history_without_writing(self):
        def state_rows(database: Path):
            with sqlite3.connect(database) as connection:
                return {
                    table: connection.execute(f'SELECT * FROM "{table}" ORDER BY rowid').fetchall()
                    for table in ("map_workstreams", "map_return_points", "map_revisions", "map_imports")
                }

        def delete_history(connection: sqlite3.Connection) -> None:
            connection.execute("DELETE FROM map_revisions")

        def mismatch_current(connection: sqlite3.Connection) -> None:
            connection.execute("UPDATE map_workstreams SET state = 'CONTRADICTORY'")

        def damage_shape(connection: sqlite3.Connection) -> None:
            connection.execute("UPDATE map_revisions SET snapshot_json = '{}' WHERE record_type = 'workstream'")

        def damage_identity(connection: sqlite3.Connection) -> None:
            row = connection.execute(
                "SELECT snapshot_json FROM map_revisions WHERE record_type = 'workstream'"
            ).fetchone()
            snapshot = json.loads(row[0])
            snapshot["id"] = "another-workstream"
            connection.execute(
                "UPDATE map_revisions SET snapshot_json = ? WHERE record_type = 'workstream'",
                (json.dumps(snapshot),),
            )

        def damage_revision(connection: sqlite3.Connection) -> None:
            row = connection.execute(
                "SELECT snapshot_json FROM map_revisions WHERE record_type = 'workstream'"
            ).fetchone()
            snapshot = json.loads(row[0])
            snapshot["revision"] += 1
            connection.execute(
                "UPDATE map_revisions SET snapshot_json = ? WHERE record_type = 'workstream'",
                (json.dumps(snapshot),),
            )

        def add_orphan(connection: sqlite3.Connection) -> None:
            row = connection.execute(
                "SELECT created_at, snapshot_json FROM map_revisions WHERE record_type = 'workstream'"
            ).fetchone()
            snapshot = json.loads(row[1])
            snapshot["id"] = "orphan-workstream"
            connection.execute(
                "INSERT INTO map_revisions(record_type, record_id, revision, created_at, snapshot_json) "
                "VALUES ('workstream', 'orphan-workstream', 1, ?, ?)",
                (row[0], json.dumps(snapshot)),
            )

        def add_gap(connection: sqlite3.Connection) -> None:
            row = connection.execute(
                "SELECT created_at, snapshot_json FROM map_revisions WHERE record_type = 'workstream'"
            ).fetchone()
            snapshot = json.loads(row[1])
            snapshot["revision"] = 3
            connection.execute(
                "INSERT INTO map_revisions(record_type, record_id, revision, created_at, snapshot_json) "
                "VALUES ('workstream', 'shared-foundations', 3, ?, ?)",
                (row[0], json.dumps(snapshot)),
            )

        corruptions = (
            ("missing history", delete_history),
            ("current snapshot mismatch", mismatch_current),
            ("snapshot shape", damage_shape),
            ("snapshot identity", damage_identity),
            ("snapshot revision", damage_revision),
            ("orphan history", add_orphan),
            ("revision gap", add_gap),
        )
        for label, corrupt in corruptions:
            with self.subTest(corruption=label), tempfile.TemporaryDirectory() as directory:
                database = Path(directory) / "controller.sqlite3"
                store = WorkstreamStore(database)
                store.bootstrap()
                store.import_site(self.status_source, self.progress_source, apply=True)
                with sqlite3.connect(database) as connection:
                    corrupt(connection)
                damaged_state = state_rows(database)

                with self.assertRaises(BackupError):
                    _validate_database(database, "damaged map fixture")
                self.assertEqual(state_rows(database), damaged_state)

                with self.assertRaises(JobsSchemaIncompatible):
                    store.bootstrap()
                self.assertEqual(state_rows(database), damaged_state)

    def test_credentials_editorial_labels_survive_combined_snapshot_and_restore(self):
        progress = json.loads(self.progress_source.read_text(encoding="utf-8"))
        progress["tracks"][0].update({"id": "credentials", "name": "Credentials and access"})
        progress["tracks"][0]["phases"][0].update({"name": "Credentials", "state": "NOW"})
        self.progress_source.write_text(json.dumps(progress), encoding="utf-8")
        self.editorial = editorial_mapping(progress)
        status_before = self.status_source.read_bytes()
        progress_before = self.progress_source.read_bytes()

        self.store.bootstrap()
        JobStore(self.database).bootstrap()
        InboxStore(self.database).bootstrap()
        SqliteReviewRecords(self.database).bootstrap()
        self.store.import_site(self.status_source, self.progress_source, apply=True)
        current = self.store.get("credentials", self.editorial)
        updated = self.store.update(
            "credentials",
            current["revision"],
            self.editorial,
            now="Continue the credentials phase",
            phase_states={"Credentials": "NOW"},
        )
        history = self.store.history("workstream", "credentials", limit=50, offset=0)
        self.assertEqual(updated["phase_states"], {"Credentials": "NOW", "Phase two": "LATER"})
        self.assertEqual([item["revision"] for item in history], [2, 1])
        self.assertEqual(self.status_source.read_bytes(), status_before)
        self.assertEqual(self.progress_source.read_bytes(), progress_before)

        with sqlite3.connect(self.database) as connection:
            original_values = json.loads(
                connection.execute("SELECT original_values_json FROM map_imports").fetchone()[0]
            )
            manifest = json.loads(connection.execute("SELECT manifest_json FROM map_imports").fetchone()[0])
            tables = {row[0] for row in connection.execute("SELECT name FROM sqlite_master WHERE type='table'")}
        self.assertEqual(original_values["workstreams"][0]["phase_states"]["Credentials"], "NOW")
        self.assertEqual(manifest["workstreams"][0]["phase_states"]["Credentials"], "NOW")
        self.assertIn("credentials", manifest["editorial"]["workstreams"])
        self.assertTrue({"jobs", "inbox_messages", "review_runs", "map_revisions"}.issubset(tables))

        source_before_snapshot = self.database.read_bytes()
        snapshot = self.root / "combined.snapshot.sqlite3"
        restored_path = self.root / "combined-restored.sqlite3"
        create_snapshot(self.database, snapshot)
        restore_snapshot(snapshot, restored_path)
        self.assertEqual(self.database.read_bytes(), source_before_snapshot)
        self.assertEqual(self.status_source.read_bytes(), status_before)
        self.assertEqual(self.progress_source.read_bytes(), progress_before)

        restored_store = WorkstreamStore(restored_path)
        restored = restored_store.get("credentials", self.editorial)
        restored_history = restored_store.history("workstream", "credentials", limit=50, offset=0)
        self.assertEqual(restored["phase_states"], {"Credentials": "NOW", "Phase two": "LATER"})
        self.assertEqual([item["revision"] for item in restored_history], [2, 1])
        with sqlite3.connect(restored_path) as connection:
            restored_original = json.loads(
                connection.execute("SELECT original_values_json FROM map_imports").fetchone()[0]
            )
            restored_manifest = json.loads(
                connection.execute("SELECT manifest_json FROM map_imports").fetchone()[0]
            )
        self.assertEqual(restored_original, original_values)
        self.assertEqual(restored_manifest, manifest)

    def test_backup_restore_refuses_secret_shaped_map_import_text_without_changes(self):
        self.store.bootstrap()
        self.store.import_site(self.status_source, self.progress_source, apply=True)
        cases = (
            ("semantic secret field", {"credentials": "short"}, "unredacted semantic secret field"),
            ("credential pattern", {"source": "Bearer synthetic-secret-value"}, "credential- or raw-secret"),
        )
        for label, provenance, message in cases:
            with self.subTest(kind=label), sqlite3.connect(self.database) as connection:
                connection.execute(
                    "UPDATE map_imports SET provenance_json = ?",
                    (json.dumps(provenance),),
                )
            source_before = self.database.read_bytes()
            destination = self.root / f"{label.replace(' ', '-')}.restored.sqlite3"
            with self.assertRaisesRegex(BackupError, message):
                restore_snapshot(self.database, destination)
            self.assertEqual(self.database.read_bytes(), source_before)
            self.assertFalse(destination.exists())

    def test_controller_provenance_survives_read_and_upgrades_only_on_state_write(self):
        for model_version in (1, 2):
            with self.subTest(model_version=model_version):
                self._assert_controller_provenance_survives_read_and_upgrades_only_on_state_write(model_version)

    def _assert_controller_provenance_survives_read_and_upgrades_only_on_state_write(self, model_version):
        state_path = self.root / f"legacy-review-state-model-{model_version}.json"
        database = state_path.with_suffix(".sqlite3")
        SqliteStateStore(database).update(lambda _: ReviewState())
        jobs = JobStore(database)
        jobs.bootstrap()
        jobs.create("retained-job", "General", "Retained job", brief="Private brief remains available to its owner")
        InboxStore(database).bootstrap()
        workstreams = WorkstreamStore(database)
        workstreams.bootstrap()
        workstreams.import_site(self.status_source, self.progress_source, apply=True)
        records = SqliteReviewRecords(database)
        records.bootstrap()

        legacy_state = ReviewState().to_dict()
        legacy_state["schema_version"] = model_version
        if model_version == 1:
            legacy_state.pop("review_period_decisions")
        with sqlite3.connect(database) as connection:
            connection.execute(
                "UPDATE controller_metadata SET data_model_version = ?, min_writer_build = 9 WHERE singleton = 1",
                (model_version,),
            )
            connection.execute(
                "UPDATE review_state SET state_json = ? WHERE singleton = 1",
                (json.dumps(legacy_state, separators=(",", ":")),),
            )
            connection.execute(
                "UPDATE job_metadata SET controller_data_model_version = ? WHERE singleton = 1",
                (model_version,),
            )
            connection.execute(
                "UPDATE map_metadata SET controller_data_model_version = ? WHERE singleton = 1",
                (model_version,),
            )
            connection.execute(
                "UPDATE review_records_metadata SET controller_data_model_version = ?, min_writer_build = 9 "
                "WHERE singleton = 1",
                (model_version,),
            )

        state_path.mkdir(mode=0o700)
        marker = state_path / "sqlite-cutover.json"
        marker_document = {
            "format": "firemud-pr-review-sqlite-cutover",
            "cutover_version": 1,
            "database": str(database.resolve()),
            "sqlite_schema_version": SQLITE_SCHEMA_VERSION,
            "state_schema_version": model_version,
            "min_writer_build": 9,
        }
        marker.write_text(json.dumps(marker_document, sort_keys=True, separators=(",", ":")) + "\n")
        os.chmod(marker, 0o600)
        marker_before = marker.read_bytes()
        database_before = database.read_bytes()
        controller = ControllerStateStore(state_path)

        self.assertEqual(controller.load(), ReviewState())
        with self.assertRaisesRegex(StateError, "requires writer build 9"):
            SqliteStateStore(database, writer_build=8).load()
        with sqlite3.connect(database) as connection:
            self.assertEqual(connection.execute("PRAGMA user_version").fetchone(), (1,))
            JobStore.validate(connection)
            InboxStore.validate(connection)
            WorkstreamStore.validate(connection)
        self.assertEqual(records.history(42)["runs"], [])
        self.assertEqual([item["name"] for item in jobs.list()], ["retained-job"])
        self.assertEqual(public_jobs(jobs.list())[0]["title"], "Retained job")
        self.assertNotIn("brief", public_jobs(jobs.list())[0])
        self.assertEqual(workstreams.list(self.editorial)["workstreams"][0]["id"], "shared-foundations")
        _validate_database(database, f"model-{model_version} shared-controller fixture")

        snapshot = self.root / f"model-{model_version}.snapshot.sqlite3"
        restored = self.root / f"model-{model_version}.restored.sqlite3"
        create_snapshot(database, snapshot)
        restore_snapshot(snapshot, restored)
        _validate_database(restored, f"model-{model_version} restored fixture")
        self.assertEqual(SqliteStateStore(restored).load(), ReviewState())
        self.assertEqual([item["name"] for item in JobStore(restored).list()], ["retained-job"])
        self.assertEqual(
            WorkstreamStore(restored).list(self.editorial)["workstreams"][0]["id"], "shared-foundations"
        )
        self.assertEqual(SqliteReviewRecords(restored).history(42)["runs"], [])

        self.assertEqual(database.read_bytes(), database_before)
        self.assertEqual(marker.read_bytes(), marker_before)

        upgraded = controller.update(lambda state: ReviewState(ordered_prs=state.ordered_prs + (42,)))

        self.assertEqual(upgraded.ordered_prs, (42,))
        self.assertEqual(marker.read_bytes(), marker_before)
        with sqlite3.connect(database) as connection:
            self.assertEqual(
                connection.execute(
                    "SELECT data_model_version, min_writer_build FROM controller_metadata WHERE singleton = 1"
                ).fetchone(),
                (3, WRITER_BUILD),
            )
            self.assertEqual(
                connection.execute(
                    "SELECT controller_data_model_version FROM job_metadata WHERE singleton = 1"
                ).fetchone(),
                (model_version,),
            )
            self.assertEqual(
                connection.execute(
                    "SELECT controller_data_model_version FROM map_metadata WHERE singleton = 1"
                ).fetchone(),
                (model_version,),
            )
            self.assertEqual(
                connection.execute(
                    "SELECT controller_data_model_version, min_writer_build "
                    "FROM review_records_metadata WHERE singleton = 1"
                ).fetchone(),
                (model_version, 9),
            )
            JobStore.validate(connection)
            InboxStore.validate(connection)
            WorkstreamStore.validate(connection)
        self.assertEqual(records.history(42)["runs"], [])
        self.assertEqual(ControllerStateStore(state_path).load().ordered_prs, (42,))
        _validate_database(database, f"model-3 state with retained model-{model_version} provenance")
        with self.assertRaisesRegex(StateError, f"requires writer build {WRITER_BUILD}"):
            SqliteStateStore(database, writer_build=8).load()


if __name__ == "__main__":
    unittest.main()
