"""Native subagent model provenance does not alter review accounting."""
from __future__ import annotations

import contextlib
import io
import json
import sqlite3
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parents[1]))
from pr_review import cli
from pr_review.sqlite_review_records import ReviewRecordsError, SqliteReviewRecords
from pr_review.sqlite_store import SqliteStateStore


class ModelProvenanceTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.database = Path(self.temp.name) / "state.sqlite3"
        SqliteStateStore(self.database).update(lambda current: current)
        self.records = SqliteReviewRecords(self.database)
        self.records.bootstrap()

    def test_api_requires_model_before_writing(self):
        for metadata in (None, {}, {"model": ""}, {"model": "gpt name"}, {"model": "x" * 201}):
            with self.subTest(metadata=metadata), self.assertRaises(ReviewRecordsError):
                self.records.start_attempt(attempt_id="missing", source_pr=7, channel="subagent", metadata=metadata)
        self.assertEqual(self.records.attempt_history(7), [])

    def test_native_start_complete_roundtrip_no_provider_credit(self):
        with contextlib.redirect_stdout(io.StringIO()) as output:
            result = cli.main(["records", "subagent", "start", "--pr", "7", "--run-id", "model-proof",
                               "--reviewer", "independent checker", "--scope", "broad", "--model", "gpt-6.1-sol",
                               "--reasoning-effort", "medium", "--database", str(self.database)])
        self.assertEqual(result, 0, output.getvalue())
        before = SqliteStateStore(self.database).load().to_dict()
        with contextlib.redirect_stdout(io.StringIO()):
            result = cli.main(["records", "subagent", "complete", "--run-id", "model-proof", "--actor", "checker",
                               "--database", str(self.database)])
        self.assertEqual(result, 0)
        history = self.records.history(7)
        self.assertEqual(history["attempts"][0]["model"], "gpt-6.1-sol")
        self.assertEqual(history["attempts"][0]["reasoning_effort"], "medium")
        self.assertEqual(history["runs"][0]["counts"]["accepted"], 0)
        self.assertEqual(SqliteStateStore(self.database).load().to_dict(), before)

    def test_cli_missing_model_rejected(self):
        with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit) as failure:
            cli._parser().parse_args(["records", "subagent", "start", "--pr", "7", "--reviewer", "checker",
                                      "--scope", "narrow"])
        self.assertEqual(failure.exception.code, 2)

    def test_historical_unknown_remains_readable(self):
        self.records.start_attempt(attempt_id="historical", source_pr=7, channel="subagent",
                                   metadata={"model": "fixture-old-writer"})
        # An exact old-writer storage fixture has no declared model. Never
        # infer one from the reviewer text on its read path.
        legacy = {"reviewer": "Sol medium", "scope": "narrow", "coverage_limits": []}
        with sqlite3.connect(self.database) as connection:
            connection.execute("UPDATE review_attempts SET metadata_json = ? WHERE attempt_id = 'historical'",
                               (json.dumps(legacy),))
        self.assertEqual(self.records.attempt("historical")["metadata"], legacy)
        self.assertNotIn("model", self.records.history(7)["attempts"][0])


if __name__ == "__main__":
    unittest.main()
