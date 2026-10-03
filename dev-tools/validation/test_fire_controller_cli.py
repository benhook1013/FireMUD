"""Real command-line proof against explicitly selected disposable databases."""
from __future__ import annotations

import contextlib
import io
import json
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).parents[1]))
from fire_controller.context import ProjectContext
from pr_review.sqlite_backup import create_snapshot, restore_snapshot
from pr_review.sqlite_review_records import SqliteReviewRecords
from pr_review.sqlite_store import SqliteStateStore

TOOLS = Path(__file__).parents[1]


class ControllerCliTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.contexts = {}
        for alias in ("alpha", "beta"):
            root = self.root / alias
            root.mkdir()
            context = root / "context.json"
            context.write_text(json.dumps({"root": str(root), "database": str(root / "state.sqlite3"),
                                           "repository": f"example/{alias}"}))
            self.contexts[alias] = context
            self.run_cli(alias, "bootstrap")

    def run_cli(self, alias, *args, success=True):
        env = os.environ.copy()
        # Any attempted external provider call fails rather than consuming quota.
        env["GH_HOST"] = "provider.invalid"
        result = subprocess.run([sys.executable, str(TOOLS / "fire-controller"), "--context",
                                 str(self.contexts[alias]), "jobs", "--json", *args],
                                capture_output=True, text=True, env=env, timeout=10, check=False)
        self.assertEqual(result.returncode, 0 if success else 2, result.stderr)
        return json.loads(result.stdout) if success and result.stdout.strip() else result

    def test_two_wrapper_contexts_and_revision_guard(self):
        job = self.run_cli("alpha", "create", "current", "--worker", "General", "--title", "Alpha work",
                           "--body", "Private current instructions", "--summary", "Public alpha")
        self.assertEqual(self.run_cli("beta", "list"), [])
        other = self.run_cli("beta", "create", "current", "--worker", "General", "--title", "Beta work")
        self.run_cli("alpha", "revise", job["id"], "--expect-revision", str(job["revision"]),
                     "--summary", "Revised alpha", "--body", "Revised private instructions")
        self.run_cli("alpha", "revise", job["id"], "--expect-revision", str(job["revision"]),
                     "--summary", "Stale overwrite", success=False)
        self.assertEqual(self.run_cli("beta", "read", other["id"])["title"], "Beta work")
        self.run_cli("beta", "read", job["id"], success=False)
        export = self.run_cli("alpha", "public-export")
        self.assertNotIn("private", json.dumps(export).lower())
        self.assertNotIn("brief", export[0])
        self.assertEqual(export[0]["summary"], "Revised alpha")

    def test_note_job_selectors_and_failed_correction_preserve_state(self):
        job = self.run_cli("alpha", "create", "note-target", "--worker", "General", "--title", "Target")
        notes = [self.run_cli("alpha", "note", "--job", selector, "--body", "Original text")
                 for selector in (job["id"], job["name"])]
        for selector in (job["id"], job["name"]):
            self.assertEqual({note["id"] for note in self.run_cli("alpha", "notes", "--job", selector)},
                             {note["id"] for note in notes})
        before = self.run_cli("alpha", "note-history", notes[0]["id"])
        self.run_cli("alpha", "note-revise", notes[0]["id"], "--expect-revision", "1",
                     "--job", "unknown-target", "--body", "Must not overwrite", success=False)
        self.assertEqual(self.run_cli("alpha", "note-history", notes[0]["id"]), before)
        self.run_cli("alpha", "note", "--job", "unknown-target", "--body", "Must not insert", success=False)
        self.run_cli("alpha", "notes", "--job", "unknown-target", success=False)

    def test_append_checkpoint_stdin_and_checklist(self):
        body = self.root / "body.md"
        body.write_text("# Working instructions\nMeaningful handoff")
        job = self.run_cli("alpha", "create", "check", "--worker", "Document", "--title", "Document work",
                           "--body-file", str(body))
        self.run_cli("alpha", "update", job["id"], "--body", "Proof finished")
        self.run_cli("alpha", "checkpoint", job["id"], "--done", "Checked", "--next", "Publish",
                     "--pointers", json.dumps({"branch": "codex/test", "proof": "focused test"}))
        read = self.run_cli("alpha", "read", job["id"])
        self.assertEqual(read["brief"], body.read_text())
        revised = self.run_cli("alpha", "checklist", job["id"], "add", "--expect-revision",
                               str(read["revision"]), "--text", "Public proof")
        self.run_cli("alpha", "checklist", job["id"], "done", "--expect-revision", str(revised["revision"]),
                     "--item-id", revised["checklist"][0]["id"])

    def test_backup_preserves_jobs_and_old_review_writes(self):
        context = ProjectContext.load(self.contexts["alpha"])
        SqliteReviewRecords(context.database).bootstrap()
        job = self.run_cli("alpha", "create", "retained", "--worker", "Gameplay", "--title", "Retained")
        # Existing review writer updates only its own state document, retaining
        # independent tables even without understanding jobs.
        SqliteStateStore(context.database).update(lambda current: current)
        snapshot = self.root / "snapshot.sqlite3"
        restored = self.root / "restored.sqlite3"
        create_snapshot(context.database, snapshot)
        restore_snapshot(snapshot, restored)
        from fire_controller.jobs import JobStore
        self.assertEqual(JobStore(restored).get(job["id"])["title"], "Retained")
        self.assertEqual(self.run_cli("beta", "list"), [])

    def test_jobs_never_needs_git_or_provider(self):
        from fire_controller.cli import main
        with patch("subprocess.run", side_effect=AssertionError("jobs must be SQLite-only")):
            result = main(["--context", str(self.contexts["alpha"]), "jobs", "--json", "list"])
        self.assertEqual(result, 0)

    def test_review_arguments_cannot_cross_project_context(self):
        context = ProjectContext.load(self.contexts["alpha"])
        for arguments in (["records", "history", "--database", str(self.root / "beta" / "state.sqlite3")],
                          ["records", "history", "--repo=example/beta"],
                          ["state", "migrate-sqlite", "--path", str(self.root / "beta" / "state.json")]):
            with self.subTest(arguments=arguments), self.assertRaises(ValueError):
                context.validate_review_arguments(arguments)

    def test_review_parsed_options_enforce_context_for_abbreviations(self):
        from fire_controller.cli import main
        context = ProjectContext.load(self.contexts["alpha"])
        other = self.root / "beta" / "state.sqlite3"
        cases = [
            ["records", "bootstrap", "--databas", str(other)],
            ["state", "migrate-sqlite", "--pat=" + str(other.with_suffix(".json"))],
            ["records", "sync-hosted", "--rep=example/beta"],
            ["--state-pat", str(other.with_suffix(".json")), "status"],
            ["status", "--root", str(self.root / "beta")],
        ]
        with patch("pr_review.cli.main") as engine, patch("subprocess.run") as external:
            for arguments in cases:
                with self.subTest(arguments=arguments):
                    self.assertEqual(main(["--context", str(self.contexts["alpha"]), "reviews", *arguments]), 2)
            engine.assert_not_called()
            external.assert_not_called()
        context.validate_review_arguments(["records", "bootstrap", "--databas", str(context.database)])
        context.validate_review_arguments(["state", "status", "--pat", str(context.database.with_suffix(".json"))])
        context.validate_review_arguments(["records", "sync-hosted", "--rep", context.repository])

    def test_review_engine_receives_selected_root_and_repository(self):
        from fire_controller.cli import main
        selected = ProjectContext.load(self.contexts["alpha"])
        # A disposable canonical path is sufficient for delegation; the fake
        # engine records context without invoking providers or inspecting PRs.
        context_file = self.root / "review-context.json"
        context_file.write_text(json.dumps({"root": str(selected.root),
            "database": str(selected.root / ".git" / "firemud" / "pr-review-stack.sqlite3"),
            "repository": "example/alpha"}))
        original = Path.cwd()
        seen = {}
        def fake_engine(arguments):
            seen.update(root=Path.cwd(), repository=os.environ["GH_REPO"], arguments=arguments)
            return 0
        fake_git = subprocess.CompletedProcess([], 0, stdout=".git\n", stderr="")
        with patch("subprocess.run", return_value=fake_git), patch("pr_review.cli.main", side_effect=fake_engine):
            self.assertEqual(main(["--context", str(context_file), "reviews", "stack", "show"]), 0)
        self.assertEqual(seen, {"root": selected.root, "repository": "example/alpha",
                                "arguments": ["stack", "show"]})
        self.assertEqual(Path.cwd(), original)

    def test_context_rejects_database_mismatch(self):
        self.run_cli("alpha", "list", success=True)
        from fire_controller.cli import main
        result = main(["--context", str(self.contexts["alpha"]), "jobs", "--database",
                       str(self.root / "beta" / "state.sqlite3"), "list"])
        self.assertEqual(result, 2)

    def test_review_unread_notice_preserves_stdout_and_is_not_a_gate(self):
        from fire_controller.cli import main
        from fire_controller.inbox import InboxStore
        context_file = self.root / "notice-context.json"
        database = self.root / "alpha" / ".git" / "firemud" / "pr-review-stack.sqlite3"
        database.parent.mkdir(parents=True)
        context_file.write_text(json.dumps({"root": str(self.root / "alpha"),
            "database": str(database), "repository": "example/alpha"}))
        inbox = InboxStore(database)
        inbox.bootstrap()
        inbox.send("General", "Private unseen message", author="Overseer")
        fake_git = subprocess.CompletedProcess([], 0, stdout=".git\n", stderr="")
        def engine(arguments):
            print('{"existing_contract":true}')
            return 0
        output, errors = io.StringIO(), io.StringIO()
        with patch("subprocess.run", return_value=fake_git), patch("pr_review.cli.main", side_effect=engine), \
                contextlib.redirect_stdout(output), contextlib.redirect_stderr(errors):
            result = main(["--context", str(context_file), "--worker-identity", "General",
                           "reviews", "status", "--json"])
        self.assertEqual(result, 0)
        self.assertEqual(output.getvalue(), '{"existing_contract":true}\n')
        self.assertIn("1 unread", errors.getvalue())
        self.assertNotIn("Private unseen", output.getvalue() + errors.getvalue())
        with patch("fire_controller.inbox.InboxStore.unread_count", side_effect=ValueError("bad inbox")), \
                patch("subprocess.run", return_value=fake_git), patch("pr_review.cli.main", side_effect=engine), \
                contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(main(["--context", str(context_file), "--worker-identity", "General",
                                   "reviews", "status", "--json"]), 0)

    def test_note_correction_and_explicit_pause_survive_routine_writes(self):
        job = self.run_cli("alpha", "create", "unfinished", "--worker", "General", "--title", "Work")
        paused = self.run_cli("alpha", "lane-pause", "General", "--job", job["id"],
                              "--body", "Private pause checkpoint", "--reason", "Human paused work")
        self.assertEqual(paused["status"], "paused")
        note = self.run_cli("alpha", "note", "--worker", "General", "--body", "Deferred source")
        dismissed = self.run_cli("alpha", "note-status", note["id"], "dismissed", "--reason", "Mistaken duplicate",
                                  "--expect-revision", str(note["revision"]))
        dismissed = self.run_cli("alpha", "note-revise", note["id"], "--reason", "Corrected dismissal reason",
                                  "--expect-revision", str(dismissed["revision"]))
        corrected = self.run_cli("alpha", "note-revise", note["id"], "--status", "pending",
                                  "--body", "Corrected source", "--expect-revision", str(dismissed["revision"]))
        self.assertEqual(corrected["body"], "Corrected source")
        self.run_cli("alpha", "note-revise", note["id"], "--body", "Stale overwrite",
                     "--expect-revision", str(note["revision"]), success=False)
        history = self.run_cli("alpha", "note-history", note["id"])
        self.assertIn("Mistaken duplicate", json.dumps(history))
        self.run_cli("alpha", "update", job["id"], "--body", "Progress while paused")
        self.assertEqual(self.run_cli("alpha", "lanes", "--worker", "General")[0]["status"], "paused")
        self.run_cli("alpha", "lane-resume", "General")
        self.run_cli("alpha", "complete", job["id"], "--expect-revision", str(job["revision"]))
        self.assertEqual(self.run_cli("alpha", "lanes", "--worker", "General")[0]["status"], "idle")

    def test_submission_advisories_preserve_message_and_checker_errors_precede_write(self):
        from fire_controller.cli import main
        from fire_controller.inbox import InboxStore
        database = ProjectContext.load(self.contexts["alpha"]).database
        inbox = InboxStore(database)
        inbox.bootstrap()
        body = "Plain message [unsafe](javascript:alert) <b>original HTML</b>"
        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            self.assertEqual(main(["--context", str(self.contexts["alpha"]), "inbox", "--json",
                                   "send", "Overseer", "--body", body]), 0)
        result = json.loads(output.getvalue())
        self.assertTrue(result["_fire_controller"]["markdown_diagnostics"])
        self.assertEqual(inbox.list("Overseer")[0]["body"], body)
        with patch("fire_controller.markdown.diagnostics", side_effect=ValueError("checker failed")), \
                contextlib.redirect_stderr(io.StringIO()):
            self.assertEqual(main(["--context", str(self.contexts["alpha"]), "inbox", "send",
                                   "Overseer", "--body", "Must not write"]), 2)
        self.assertEqual(len(inbox.list("Overseer")), 1)

    def test_public_export_never_contains_caller_inbox_metadata(self):
        from fire_controller.cli import main
        from fire_controller.inbox import InboxStore
        database = ProjectContext.load(self.contexts["alpha"]).database
        InboxStore(database).bootstrap()
        InboxStore(database).send("General", "Private caller canary")
        self.run_cli("alpha", "create", "public", "--worker", "General", "--title", "Public title")
        output, errors = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(output), contextlib.redirect_stderr(errors):
            self.assertEqual(main(["--context", str(self.contexts["alpha"]), "--worker-identity", "General",
                                   "jobs", "--json", "public-export"]), 0)
        export = json.loads(output.getvalue())
        self.assertIsInstance(export, list)
        self.assertNotIn("_fire_controller", output.getvalue())
        self.assertNotIn("unread", output.getvalue())
        self.assertNotIn("Private caller canary", output.getvalue())
        self.assertIn("1 unread", errors.getvalue())


if __name__ == "__main__":
    unittest.main()
