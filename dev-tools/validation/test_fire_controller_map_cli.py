"""Integrated source migration preserves private work and uses SQL afterwards."""
from __future__ import annotations

import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parents[1]))
from fire_controller.inbox import InboxStore
from fire_controller.jobs import JobStore
from fire_controller.map import WorkstreamStore
from pr_review.sqlite_backup import create_snapshot, restore_snapshot
from pr_review.sqlite_review_records import SqliteReviewRecords

TOOLS = Path(__file__).parents[1]


class MapCliTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.database = self.root / "state.sqlite3"
        self.jobs = JobStore(self.database)
        self.jobs.bootstrap()
        WorkstreamStore(self.database).bootstrap()
        self.status = self.root / "status.json"
        self.progress = self.root / "progress.json"
        self.status.write_text(json.dumps({"lanes": [{"name": "Worker Alpha", "status": "PAUSED",
            "task": ["<b>Original public task</b>"], "brief": "private-source.md"}],
            "review_front": 123, "stack": [], "review_tool": "configured-review-wrapper"}))
        self.progress.write_text(json.dumps({"headline": "Editorial heading", "tracks": [{
            "id": "delivery", "name": "Delivery", "state": "active", "now": "Current paragraph",
            "milestone": "Next milestone", "purpose": "Editorial purpose", "links": [],
            "phases": [{"name": "Proof", "state": "active"}]}], "domains": [],
            "return_points": [{"name": "Deferred work", "state": "parked", "trigger": "Owner boundary"}]}))

    def cli(self, *args, success=True):
        command = [sys.executable, str(TOOLS / "fire-controller"), "map", "--database", str(self.database),
                   "--editorial", str(self.progress), "--json", *args]
        result = subprocess.run(command, capture_output=True, text=True, timeout=10, check=False)
        self.assertEqual(result.returncode, 0 if success else 2, result.stderr)
        return json.loads(result.stdout) if success else result

    def test_preview_apply_guarded_updates_and_retry_do_not_replace_private_brief(self):
        job = self.jobs.create("current", "Worker Alpha", "Current title", brief="Exact private **instructions**")
        preview = self.cli("import", str(self.status), str(self.progress))
        self.assertFalse(preview["applied"])
        self.assertEqual(preview["lane_targets"][0]["job_id"], job["id"])
        self.assertTrue(preview["_fire_controller"]["markdown_diagnostics"])
        self.assertEqual(self.jobs.get(job["id"])["summary"], "")
        applied = self.cli("import", str(self.status), str(self.progress), "--apply")
        self.assertTrue(applied["applied"])
        current = self.jobs.get(job["id"])
        self.assertEqual(current["brief"], "Exact private **instructions**")
        self.assertEqual(self.jobs.lane_state("Worker Alpha")["status"], "paused")
        self.jobs.revise(job["id"], current["revision"], summary="Later public progress")
        repeated = self.cli("import", str(self.status), str(self.progress), "--apply")
        self.assertTrue(repeated["already_applied"])
        self.assertEqual(self.jobs.get(job["id"])["summary"], "Later public progress")
        updated = self.cli("update", "delivery", "--expect-revision", "1", "--now", "[explicit][missing]")
        self.assertTrue(updated["_fire_controller"]["markdown_diagnostics"])
        self.cli("update", "delivery", "--expect-revision", "1", "--now", "Stale", success=False)
        self.cli("update", "delivery", "--expect-revision", str(updated["revision"]),
                 "--phase-states", '{"Unknown phase":"active"}', success=False)
        self.assertEqual(self.cli("read", "delivery")["now"], "[explicit][missing]")

    def test_long_alias_source_apply_and_repeat_keep_distinct_jobs(self):
        status = json.loads(self.status.read_text())
        workers = {"a" * 70 + "x", "a" * 70 + "y"}
        status["lanes"] = [{**status["lanes"][0], "name": worker} for worker in sorted(workers)]
        self.status.write_text(json.dumps(status))
        preview = self.cli("import", str(self.status), str(self.progress))
        names = {job["name"] for job in preview["lane_jobs"]}
        self.assertEqual(len(names), 2)
        self.cli("import", str(self.status), str(self.progress), "--apply")
        before = self.jobs.list()
        self.assertEqual({job["worker"] for job in before}, workers)
        self.assertEqual({job["name"] for job in before}, names)
        repeated = self.cli("import", str(self.status), str(self.progress), "--apply")
        self.assertTrue(repeated["already_applied"])
        self.assertEqual(self.jobs.list(), before)

    def test_combined_review_jobs_notes_lanes_inbox_and_map_restore(self):
        self.cli("import", str(self.status), str(self.progress), "--apply")
        inbox = InboxStore(self.database)
        inbox.bootstrap()
        message = inbox.send("Worker Alpha", "Private **handoff**")
        note = self.jobs.note("Retained deferred source", worker="Worker Alpha")
        self.jobs.note_status(note["id"], "dismissed", reason="Earlier reason", expected_revision=note["revision"])
        SqliteReviewRecords(self.database).bootstrap()
        snapshot, restored = self.root / "snapshot.sqlite3", self.root / "restored.sqlite3"
        create_snapshot(self.database, snapshot)
        restore_snapshot(snapshot, restored)
        self.assertEqual(InboxStore(restored).list("Worker Alpha")[0]["id"], message["id"])
        self.assertEqual(JobStore(restored).lane_state("Worker Alpha")["status"], "paused")
        self.assertIn("Earlier reason", json.dumps(JobStore(restored).note_history(note["id"])))
        self.assertEqual(WorkstreamStore(restored).get("delivery", json.loads(self.progress.read_text()))["now"], "Current paragraph")


if __name__ == "__main__":
    unittest.main()
