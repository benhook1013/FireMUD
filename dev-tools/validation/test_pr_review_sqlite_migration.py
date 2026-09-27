"""Focused proofs for version-matched JSON-to-SQLite controller migration."""

from __future__ import annotations

import dataclasses
import json
import sqlite3
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "dev-tools"))

from pr_review.sqlite_store import CUTOVER_VERSION, SQLITE_SCHEMA_VERSION, WRITER_BUILD, SqliteStateStore
from pr_review.state import ControllerStateStore, ReviewState, StateError, StateStore, controller_state_status


class SqliteMigrationEntrypointTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary_directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary_directory.cleanup)
        self.root = Path(self.temporary_directory.name)
        self.legacy_path = self.root / "pr-review-stack.json"
        self.database_path = self.root / "pr-review-stack.sqlite3"

    def test_migration_records_current_writer_build_and_fences_older_writers(self) -> None:
        expected = dataclasses.replace(ReviewState(), ordered_prs=(2828, 2879))
        StateStore(self.legacy_path).save(expected)

        current = SqliteStateStore.migrate_legacy_json(self.legacy_path, self.database_path)

        self.assertEqual(current.load(), expected)
        self.assertEqual(current.status()["min_writer_build"], WRITER_BUILD)
        marker = json.loads((self.legacy_path / "sqlite-cutover.json").read_text(encoding="utf-8"))
        self.assertEqual(marker["cutover_version"], CUTOVER_VERSION)
        self.assertEqual(marker["sqlite_schema_version"], SQLITE_SCHEMA_VERSION)
        self.assertEqual(marker["min_writer_build"], WRITER_BUILD)
        before = self.database_path.read_bytes()
        old_writer = SqliteStateStore(self.database_path, writer_build=WRITER_BUILD - 1)
        with self.assertRaisesRegex(StateError, f"requires writer build {WRITER_BUILD}"):
            old_writer.update(lambda state: dataclasses.replace(state, ordered_prs=(9999,)))
        self.assertEqual(self.database_path.read_bytes(), before)
        with self.assertRaises(StateError):
            StateStore(self.legacy_path).save(ReviewState(ordered_prs=(9999,)))

    def test_incompatible_schema_and_writer_metadata_fail_closed(self) -> None:
        StateStore(self.legacy_path).save(ReviewState(ordered_prs=(2828,)))
        SqliteStateStore.migrate_legacy_json(self.legacy_path, self.database_path)
        live_store = ControllerStateStore(self.legacy_path)

        with sqlite3.connect(self.database_path) as connection:
            connection.execute("PRAGMA user_version = 999")
        status = controller_state_status(self.legacy_path)
        self.assertFalse(status["compatible"])
        with self.assertRaises(StateError):
            live_store.update(lambda state: dataclasses.replace(state, ordered_prs=(2879,)))

        with sqlite3.connect(self.database_path) as connection:
            connection.execute(f"PRAGMA user_version = {SQLITE_SCHEMA_VERSION}")
            connection.execute(
                "UPDATE controller_metadata SET min_writer_build = ? WHERE singleton = 1",
                (WRITER_BUILD + 1,),
            )
        marker_path = self.legacy_path / "sqlite-cutover.json"
        marker = json.loads(marker_path.read_text(encoding="utf-8"))
        marker["min_writer_build"] = WRITER_BUILD + 1
        marker_path.write_text(json.dumps(marker, sort_keys=True) + "\n", encoding="utf-8")
        status = controller_state_status(self.legacy_path)
        self.assertEqual(status["format"], "sqlite")
        self.assertFalse(status["compatible"])
        with self.assertRaisesRegex(StateError, "requires writer build"):
            live_store.load()

        marker["cutover_version"] = True
        marker_path.write_text(json.dumps(marker, sort_keys=True) + "\n", encoding="utf-8")
        with self.assertRaisesRegex(StateError, "cutover marker version"):
            live_store.load()


if __name__ == "__main__":
    unittest.main()
