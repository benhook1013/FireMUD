from __future__ import annotations

import json
import sqlite3
import tempfile
import unittest
from pathlib import Path

from fire_controller.jobs import JobsNotBootstrapped, JobsSchemaIncompatible, RevisionConflict
from fire_controller.map import (
    MAP_TABLES,
    MapImportConflict,
    MapNotImported,
    WorkstreamStore,
    editorial_mapping,
)


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

    def test_bootstrap_initializes_controller_and_independent_map_schema(self):
        self.store.bootstrap()
        with sqlite3.connect(self.database) as connection:
            tables = {row[0] for row in connection.execute("SELECT name FROM sqlite_master WHERE type='table'")}
            self.assertTrue(set(MAP_TABLES).issubset(tables))
            self.assertFalse(any(name.startswith("job_") for name in tables))
            WorkstreamStore.validate(connection)

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


if __name__ == "__main__":
    unittest.main()
