"""Focused proofs for the explicit SQLite state CLI and live-store selection."""

from __future__ import annotations

import contextlib
import dataclasses
import io
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "dev-tools"))

from pr_review import cli as review_cli
from pr_review import runtime
from pr_review.sqlite_review_records import SqliteReviewRecords
from pr_review.sqlite_store import SQLITE_SCHEMA_VERSION, WRITER_BUILD, SqliteStateStore
from pr_review.state import ControllerStateStore, ReviewState, StateStore


class SqliteControllerEntrypointTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary_directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary_directory.cleanup)
        self.root = Path(self.temporary_directory.name)
        self.state_path = self.root / "pr-review-stack.json"

    def _run_cli(self, arguments: list[str]) -> tuple[int, str, str]:
        stdout = io.StringIO()
        stderr = io.StringIO()
        with contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(stderr):
            result = review_cli.main(arguments)
        return result, stdout.getvalue(), stderr.getvalue()

    def test_canonical_entrypoint_reports_the_schema_writer_build(self) -> None:
        result = subprocess.run(
            [sys.executable, str(ROOT / "dev-tools" / "pr-review"), "--version"],
            cwd=self.root,
            check=False,
            capture_output=True,
            text=True,
            timeout=10,
        )

        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(
            result.stdout.strip(),
            f"pr-review sqlite_schema_version={SQLITE_SCHEMA_VERSION} writer_build={WRITER_BUILD}",
        )
        self.assertEqual(result.stderr, "")

    def test_live_store_keeps_json_until_explicit_migration_and_status_is_read_only(self) -> None:
        live_store = ControllerStateStore(self.state_path)
        expected = ReviewState(ordered_prs=(2828,))
        live_store.update(lambda _: expected)
        before = self.state_path.read_bytes()

        result, stdout, stderr = self._run_cli(
            ["state", "status", "--path", str(self.state_path), "--json"]
        )

        self.assertEqual(result, 0, stderr)
        status = json.loads(stdout)
        self.assertEqual(status["status_version"], 1)
        self.assertEqual(status["format"], "json")
        self.assertTrue(status["compatible"])
        self.assertTrue(status["read_only"])
        self.assertEqual(self.state_path.read_bytes(), before)
        self.assertFalse(self.state_path.with_suffix(".sqlite3").exists())

    def test_explicit_migration_switches_new_live_writes_and_keeps_old_writer_fenced(self) -> None:
        expected = ReviewState(ordered_prs=(2828, 2879))
        legacy_store = StateStore(self.state_path)
        legacy_store.save(expected)
        retained_bytes = self.state_path.read_bytes()

        result, stdout, stderr = self._run_cli(
            ["state", "migrate-sqlite", "--path", str(self.state_path), "--json"]
        )

        self.assertEqual(result, 0, stderr)
        status = json.loads(stdout)
        self.assertEqual(status["status_version"], 1)
        self.assertEqual(status["format"], "sqlite")
        self.assertEqual(status["min_writer_build"], WRITER_BUILD)
        self.assertTrue(status["compatible"])
        self.assertEqual(self.state_path.with_name(f"{self.state_path.name}.migrated").read_bytes(), retained_bytes)

        live_store = ControllerStateStore(self.state_path)
        updated = live_store.update(lambda current: dataclasses.replace(current, ordered_prs=(2879,)))
        self.assertEqual(updated.ordered_prs, (2879,))
        self.assertEqual(live_store.load(), updated)
        with self.assertRaisesRegex(ValueError, "migrated to SQLite"):
            legacy_store.update(lambda current: dataclasses.replace(current, ordered_prs=(9999,)))

    def test_default_live_controller_uses_versioned_dynamic_store(self) -> None:
        with (
            patch.object(runtime.github, "infer_repo", return_value="owner/repository"),
            patch.object(runtime.github, "repository_metadata", return_value={"ref_name": "main"}),
        ):
            controller = runtime.default_controller()

        self.assertIsInstance(controller.store, ControllerStateStore)
        self.assertEqual(controller.store.path.name, "pr-review-stack.json")

    def test_actual_period_reset_dispatch_uses_local_sqlite_without_external_calls(self) -> None:
        common = self.root / ".git"
        common.mkdir()
        selected = common / "firemud" / "pr-review-stack.json"
        StateStore(selected).save(ReviewState(ordered_prs=(42,)))
        SqliteStateStore.migrate_legacy_json(selected, selected.with_suffix(".sqlite3"))
        store = ControllerStateStore(selected)._active_store()
        SqliteReviewRecords(store.path).bootstrap()
        arguments = ["decide", "period", "reset", "--pr", "42", "--channel", "cli", "--head", "a" * 40,
                     "--current-history-baseline", "--baseline-id", "actual-dispatch", "--actor", "operator",
                     "--reason", "authorized renewal", "--json"]
        with (patch.object(Path, "cwd", return_value=self.root),
              patch.object(review_cli, "default_controller", side_effect=AssertionError("network controller")),
              patch.object(review_cli.github, "infer_repo", side_effect=AssertionError("GitHub lookup")),
              patch.object(review_cli.github, "repository_metadata", side_effect=AssertionError("GitHub metadata")),
              patch.object(subprocess, "run", side_effect=AssertionError("external process"))):
            first_status, first_stdout, first_stderr = self._run_cli(arguments)
            replay_status, replay_stdout, replay_stderr = self._run_cli(arguments)
            explicit_status, explicit_stdout, explicit_stderr = self._run_cli(
                [*arguments, "--database", str(store.path)])
        self.assertEqual(first_status, 0, first_stderr)
        self.assertEqual(replay_status, 0, replay_stderr)
        self.assertEqual(explicit_status, 0, explicit_stderr)
        first, replay, explicit = map(json.loads, (first_stdout, replay_stdout, explicit_stdout))
        self.assertEqual(first["status"], "recorded")
        self.assertEqual(replay["status"], "already_recorded")
        self.assertEqual(first["period"], replay["period"])
        self.assertEqual(first["period"], explicit["period"])
        self.assertEqual(len(store.load().review_period_decisions), 1)

    def test_stack_show_reads_the_migrated_store_without_github(self) -> None:
        StateStore(self.state_path).save(ReviewState(ordered_prs=(2828, 2879)))
        result, _, stderr = self._run_cli(["state", "migrate-sqlite", "--path", str(self.state_path), "--json"])
        self.assertEqual(result, 0, stderr)
        with (
            patch.object(review_cli, "ControllerStateStore", return_value=ControllerStateStore(self.state_path)),
            patch.object(
                review_cli.github,
                "infer_repo",
                side_effect=AssertionError("stack show must not resolve a GitHub repository"),
            ),
            patch.object(
                review_cli.github,
                "repository_metadata",
                side_effect=AssertionError("stack show must not read GitHub repository metadata"),
            ),
        ):
            result, stdout, stderr = self._run_cli(["stack", "show"])
        self.assertEqual(result, 0, stderr)
        self.assertIn("ordered_prs=[2828, 2879]", stdout)


if __name__ == "__main__":
    unittest.main()
