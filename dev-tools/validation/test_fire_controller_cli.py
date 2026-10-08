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


class ControllerHelpTest(unittest.TestCase):
    def help(self, *arguments):
        from fire_controller.cli import main

        output = io.StringIO()
        # Help must work before a context, database, provider or Markdown checker is consulted.
        environment = {key: value for key, value in os.environ.items() if key != "FIRE_CONTROLLER_CONTEXT"}
        with (
            patch.dict(os.environ, environment, clear=True),
            patch("fire_controller.context.ProjectContext.load", side_effect=AssertionError("context consulted")),
            patch("fire_controller.cli._precheck", side_effect=AssertionError("submission checked")),
            patch("pr_review.cli._dispatch", side_effect=AssertionError("review dispatched")),
            contextlib.redirect_stdout(output),
            self.assertRaises(SystemExit) as exit_result,
        ):
            main([*arguments, "--help"])
        self.assertEqual(exit_result.exception.code, 0)
        return " ".join(output.getvalue().split())

    def test_job_help_distinguishes_lookup_writes_and_guarded_private_changes(self):
        read = self.help("jobs", "read")
        self.assertIn("latest checkpoint", read)
        self.assertIn("without writing", read)
        self.assertIn("maximum newest updates", read)
        checkpoint = self.help("jobs", "checkpoint")
        self.assertIn("Write a private checkpoint", checkpoint)
        self.assertIn("Use jobs read to retrieve", checkpoint)
        revise = self.help("jobs", "revise")
        self.assertIn("revision returned by jobs read", revise)
        self.assertIn("stale revisions are refused", revise)
        self.assertIn("public assignment and outcome", revise)
        self.assertIn("Private working brief", revise)
        create = self.help("jobs", "create")
        self.assertIn("mission-first", create)
        self.assertIn("#2898 Smoke", create)
        self.assertIn("Prerequisite PRs", create)
        self.assertIn("unpublished PRs (estimate)", create)
        self.assertIn("--checklist-item TEXT", create)
        self.assertIn("JSON array of objects", create)
        self.assertIn("item IDs are generated", create)
        self.assertIn("plain checklist item", self.help("jobs", "create"))
        self.assertIn("one concise human-readable line per job", self.help("jobs", "list"))
        self.assertIn("incompatible with --json", self.help("jobs", "list"))
        read = self.help("jobs", "read")
        self.assertIn("--checkpoint-only", read)
        self.assertIn("omit the brief and other context", read)
        self.assertIn("latest checkpoint", read)
        self.assertIn("required when status is dismissed", self.help("jobs", "note-status"))
        for command in ("checkpoint", "lane-pause", "lane-resume"):
            with self.subTest(command=command):
                detail = self.help("jobs", command)
                self.assertIn("allowed keys: branch, worktree, pr, source, proof", detail)
                self.assertIn("pr also accepts a positive integer", detail)
                self.assertIn("extra head/CI evidence in checkpoint prose", detail)

    def test_inbox_help_discloses_seen_acknowledgment_and_no_wake_semantics(self):
        list_help = self.help("inbox", "list")
        self.assertIn("without changing seen or acknowledged state", list_help)
        self.assertIn("--unacknowledged", list_help)
        self.assertIn("including messages already read", list_help)
        self.assertIn("--compact", list_help)
        self.assertIn("--json", list_help)
        self.assertIn("does not wake the recipient or complete work", self.help("inbox", "send"))
        read = self.help("inbox", "read")
        self.assertIn("mark it seen", read)
        self.assertIn("does not acknowledge handling", read)
        ack = self.help("inbox", "ack")
        self.assertIn("acknowledged and seen", ack)
        self.assertIn("not job completion", ack)
        thread = self.help("inbox", "thread")
        self.assertIn("across recipients", thread)
        self.assertIn("chronological order", thread)
        self.assertIn("does not mark messages seen or acknowledged", thread)
        self.assertIn("--limit", thread)
        self.assertIn("--offset", thread)

    def test_review_help_points_to_native_resolution_and_exact_round_semantics(self):
        self.assertIn("records route resolve", self.help("reviews", "routes"))
        resolve = self.help("reviews", "records", "route", "resolve")
        self.assertIn("native SQLite route outcome", resolve)
        self.assertIn("does not complete a review run", resolve)
        allocation = self.help("reviews", "decide", "allocation")
        self.assertIn("renew to replace an existing allocation or human stop", allocation)
        self.assertIn("does not request a review", allocation)
        self.assertIn("Exact rounds always preserve taper", allocation)
        self.assertIn("cannot be combined with --fresh-taper", allocation)


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
            context.write_text(
                json.dumps(
                    {"root": str(root), "database": str(root / "state.sqlite3"), "repository": f"example/{alias}"}
                )
            )
            self.contexts[alias] = context
            self.run_cli(alias, "bootstrap")

    def run_cli(self, alias, *args, success=True):
        env = os.environ.copy()
        # Keep this convenience path identity-free; identity metadata is tested explicitly below.
        env.pop("FIRE_CONTROLLER_WORKER", None)
        # Any attempted external provider call fails rather than consuming quota.
        env["GH_HOST"] = "provider.invalid"
        result = subprocess.run(
            [
                sys.executable,
                str(TOOLS / "fire-controller"),
                "--context",
                str(self.contexts[alias]),
                "jobs",
                "--json",
                *args,
            ],
            capture_output=True,
            text=True,
            env=env,
            timeout=10,
            check=False,
        )
        self.assertEqual(result.returncode, 0 if success else 2, result.stderr)
        self.last_output_bytes = len(result.stdout.encode("utf-8"))
        return json.loads(result.stdout) if success and result.stdout.strip() else result

    def test_file_and_real_piped_stdin_preserve_utf8_and_exact_line_endings(self):
        original = "# Instructions\r\n\r\nKeep ü text.\nMixed return\rLast line\r\n"
        body_file = self.root / "windows.md"
        body_file.write_bytes(original.encode("utf-8"))
        job = self.run_cli(
            "alpha", "create", "file-lines", "--worker", "General", "--title", "Exact", "--body-file", str(body_file)
        )
        self.assertNotIn("brief", job)
        self.assertIn("id", job)
        self.assertNotIn("history", job)
        revised = self.run_cli(
            "alpha", "revise", job["id"], "--expect-revision", str(job["revision"]), "--body-file", str(body_file)
        )
        self.assertNotIn("brief", revised)
        self.assertEqual(self.run_cli("alpha", "read", job["id"])["brief"], original)
        note = self.run_cli("alpha", "note", "--worker", "General", "--body-file", str(body_file))
        self.assertEqual(note["body"], original)
        updated = self.run_cli("alpha", "update", job["id"], "--body-file", str(body_file))
        self.assertEqual(updated["body"], original)
        command = [
            sys.executable,
            str(TOOLS / "fire-controller"),
            "--context",
            str(self.contexts["alpha"]),
            "jobs",
            "--json",
            "create",
            "stdin-lines",
            "--worker",
            "Document",
            "--title",
            "Piped",
            "--body-file",
            "-",
        ]
        piped = subprocess.run(command, input=original.encode("utf-8"), capture_output=True, timeout=10, check=False)
        self.assertEqual(piped.returncode, 0, piped.stderr.decode())
        piped_job = json.loads(piped.stdout)
        self.assertEqual(self.run_cli("alpha", "read", piped_job["id"])["brief"], original)
        from fire_controller.inbox import InboxStore

        selected = ProjectContext.load(self.contexts["alpha"])
        InboxStore(selected.database).bootstrap()
        inbox_command = [
            sys.executable,
            str(TOOLS / "fire-controller"),
            "--context",
            str(self.contexts["alpha"]),
            "inbox",
            "--json",
            "send",
            "General",
            "--body-file",
            "-",
        ]
        message = subprocess.run(
            inbox_command, input=original.encode("utf-8"), capture_output=True, timeout=10, check=False
        )
        self.assertEqual(message.returncode, 0, message.stderr.decode())
        self.assertEqual(json.loads(message.stdout)["body"], original)
        import argparse

        from fire_controller.cli import _body

        self.assertEqual(_body(argparse.Namespace(body_file=str(body_file))), original)

    def test_inbox_thread_command_is_read_only_and_crosses_recipients(self):
        from fire_controller.cli import main
        from fire_controller.inbox import InboxStore

        database = ProjectContext.load(self.contexts["alpha"]).database
        inbox = InboxStore(database)
        inbox.bootstrap()
        root = inbox.send("General", "Original", author="Overseer")
        reply = inbox.send("Overseer", "Reply", author="General", reply_to=root["id"])
        output = io.StringIO()
        env = os.environ.copy()
        env.pop("FIRE_CONTROLLER_WORKER", None)
        with patch.dict(os.environ, env, clear=True), contextlib.redirect_stdout(output):
            self.assertEqual(
                main(
                    [
                        "--context",
                        str(self.contexts["alpha"]),
                        "inbox",
                        "--json",
                        "thread",
                        reply["id"],
                        "--limit",
                        "2",
                        "--offset",
                        "0",
                    ]
                ),
                0,
            )
        page = json.loads(output.getvalue())
        self.assertEqual({message["body"] for message in page}, {"Original", "Reply"})
        self.assertEqual({message["recipient"] for message in inbox.thread(root["id"])}, {"General", "Overseer"})
        self.assertTrue(
            all(
                message["seen_at"] is None and message["acknowledged_at"] is None
                for message in inbox.thread(reply["id"])
            )
        )

    def test_inbox_unacknowledged_compact_and_json_list_outputs(self):
        from fire_controller.cli import main
        from fire_controller.inbox import InboxStore

        database = ProjectContext.load(self.contexts["alpha"]).database
        inbox = InboxStore(database)
        inbox.bootstrap()
        body = "First line of a long message\n" + ("Useful follow-up context. " * 8)
        timestamps = iter(f"2026-10-01T00:0{minute}:00Z" for minute in range(3))
        with patch("fire_controller.inbox._now", side_effect=lambda: next(timestamps)):
            unseen = inbox.send("General", body, author="Overseer", job="controller-work", pr=3092)
            read_pending = inbox.send("General", "Already read, still awaiting handling", author="Gameplay")
            handled = inbox.send("General", "Handled message", author="Document")
        inbox.read(read_pending["id"], recipient="General")
        inbox.ack(handled["id"], recipient="General")

        output, errors = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(output), contextlib.redirect_stderr(errors):
            result = main(
                [
                    "--context",
                    str(self.contexts["alpha"]),
                    "inbox",
                    "list",
                    "--worker",
                    "General",
                    "--unacknowledged",
                    "--compact",
                ]
            )
        self.assertEqual(result, 0, errors.getvalue())
        compact_lines = output.getvalue().splitlines()
        self.assertEqual(compact_lines[0], "ID  STATE  SENDER  CREATED  LINKS  BODY")
        self.assertEqual(len(compact_lines), 3)
        rows_by_id = {line.split("  ", 1)[0]: line for line in compact_lines[1:]}
        self.assertIn(f'{unseen["id"]}  unread  "Overseer"', rows_by_id[unseen["id"]])
        self.assertIn('job="controller-work" PR=#3092', rows_by_id[unseen["id"]])
        self.assertIn('"First line of a long message Useful follow-up context.', rows_by_id[unseen["id"]])
        self.assertNotIn("Already read, still awaiting handling", rows_by_id[unseen["id"]])
        self.assertIn(f'{read_pending["id"]}  read/unacknowledged  "Gameplay"', rows_by_id[read_pending["id"]])
        self.assertNotIn(handled["id"], output.getvalue())

        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            result = main(
                [
                    "--context",
                    str(self.contexts["alpha"]),
                    "inbox",
                    "list",
                    "--worker",
                    "General",
                    "--unacknowledged",
                    "--json",
                ]
            )
        self.assertEqual(result, 0)
        structured = json.loads(output.getvalue())
        self.assertEqual({message["id"] for message in structured}, {unseen["id"], read_pending["id"]})
        self.assertEqual(next(message for message in structured if message["id"] == unseen["id"])["body"], body)

        errors = io.StringIO()
        with contextlib.redirect_stderr(errors):
            result = main(
                [
                    "--context",
                    str(self.contexts["alpha"]),
                    "inbox",
                    "list",
                    "--worker",
                    "General",
                    "--unacknowledged",
                    "--compact",
                    "--json",
                ]
            )
        self.assertEqual(result, 2)
        self.assertIn("human-readable", errors.getvalue())

    def test_invalid_utf8_file_and_piped_input_fail_before_writes(self):
        import sqlite3

        selected = ProjectContext.load(self.contexts["alpha"])
        with sqlite3.connect(selected.database) as connection:
            before = list(connection.iterdump())
        source = self.root / "invalid.md"
        source.write_bytes(b"Invalid UTF8: \xff")
        self.run_cli(
            "alpha",
            "create",
            "bad-file",
            "--worker",
            "General",
            "--title",
            "Bad",
            "--body-file",
            str(source),
            success=False,
        )
        command = [
            sys.executable,
            str(TOOLS / "fire-controller"),
            "--context",
            str(self.contexts["alpha"]),
            "jobs",
            "--json",
            "create",
            "bad-stdin",
            "--worker",
            "General",
            "--title",
            "Bad",
            "--body-file",
            "-",
        ]
        failed = subprocess.run(command, input=source.read_bytes(), capture_output=True, timeout=10, check=False)
        self.assertEqual(failed.returncode, 2)
        self.assertIn(b"utf-8", failed.stderr)
        with sqlite3.connect(selected.database) as connection:
            self.assertEqual(list(connection.iterdump()), before)

    def test_two_wrapper_contexts_and_revision_guard(self):
        job = self.run_cli(
            "alpha",
            "create",
            "current",
            "--worker",
            "General",
            "--title",
            "Alpha work",
            "--body",
            "Private current instructions",
            "--summary",
            "Public alpha",
        )
        self.assertEqual(self.run_cli("beta", "list"), [])
        other = self.run_cli("beta", "create", "current", "--worker", "General", "--title", "Beta work")
        self.run_cli(
            "alpha",
            "revise",
            job["id"],
            "--expect-revision",
            str(job["revision"]),
            "--summary",
            "Revised alpha",
            "--body",
            "Revised private instructions",
        )
        self.run_cli(
            "alpha",
            "revise",
            job["id"],
            "--expect-revision",
            str(job["revision"]),
            "--summary",
            "Stale overwrite",
            success=False,
        )
        self.assertEqual(self.run_cli("beta", "read", other["id"])["title"], "Beta work")
        self.run_cli("beta", "read", job["id"], success=False)
        export = self.run_cli("alpha", "public-export")
        self.assertNotIn("private", json.dumps(export).lower())
        self.assertNotIn("brief", export[0])
        self.assertEqual(export[0]["summary"], "Revised alpha")

    def test_job_cli_compact_read_and_mutation_receipts_preserve_full_store_api(self):
        large_brief = "# Current instructions\n\n" + ("Keep the current proof bounded. " * 100)
        job = self.run_cli(
            "alpha", "create", "compact-read", "--worker", "General", "--title", "Compact read", "--body", large_brief
        )
        create_bytes = self.last_output_bytes
        self.assertEqual(
            set(job),
            {
                "id",
                "name",
                "worker",
                "title",
                "status",
                "revision",
                "brief_revision",
                "created_at",
                "updated_at",
                "last_activity_at",
            },
        )
        self.assertLess(create_bytes, 1024)

        revised = self.run_cli(
            "alpha",
            "revise",
            job["id"],
            "--expect-revision",
            str(job["revision"]),
            "--body",
            "# Revised instructions\n\n" + ("Keep the next step clear. " * 100),
        )
        revise_bytes = self.last_output_bytes
        self.assertEqual(revised["revision"], 2)
        self.assertEqual(revised["brief_revision"], 2)
        self.assertNotIn("brief", revised)
        self.assertNotIn("history", revised)
        self.assertLess(revise_bytes, 1024)

        # Repeating the same guarded state is idempotent: it returns the same revision.
        no_op = self.run_cli(
            "alpha",
            "revise",
            job["id"],
            "--expect-revision",
            str(revised["revision"]),
            "--body",
            "# Revised instructions\n\n" + ("Keep the next step clear. " * 100),
        )
        self.assertEqual(no_op["revision"], revised["revision"])
        self.assertEqual(no_op["updated_at"], revised["updated_at"])
        self.run_cli(
            "alpha",
            "revise",
            job["id"],
            "--expect-revision",
            str(job["revision"]),
            "--summary",
            "Stale write",
            success=False,
        )

        first_update = self.run_cli(
            "alpha", "update", job["id"], "--body", "A compact receipt must retain this update."
        )
        update_receipt = self.run_cli("alpha", "update", job["id"], "--body", "Second useful update")
        update_bytes = self.last_output_bytes
        self.assertIn("sequence", first_update)
        self.assertIn("sequence", update_receipt)
        self.assertEqual(update_receipt["body"], "Second useful update")
        self.assertLess(update_bytes, 1024)
        checkpoint = self.run_cli(
            "alpha", "checkpoint", job["id"], "--done", "Read compactly", "--next", "Continue the proof"
        )
        checkpoint_bytes = self.last_output_bytes
        self.assertIn("sequence", checkpoint)
        self.assertEqual(checkpoint["next_steps"], "Continue the proof")
        self.assertLess(checkpoint_bytes, 1024)

        compact = self.run_cli("alpha", "read", job["id"])
        compact_bytes = self.last_output_bytes
        self.assertEqual(compact["brief"], "# Revised instructions\n\n" + ("Keep the next step clear. " * 100))
        self.assertEqual(compact["history"], [])
        self.assertEqual(len(compact["updates"]), 2)
        self.assertEqual(compact["latest_checkpoint"]["next_steps"], "Continue the proof")
        full = self.run_cli("alpha", "read", job["id"], "--full-history")
        full_bytes = self.last_output_bytes
        self.assertEqual({entry["revision"] for entry in full["history"]}, {1, 2})
        self.assertIn(large_brief, {entry["brief"] for entry in full["history"]})
        self.assertGreater(full_bytes, compact_bytes)
        self.assertEqual(len(self.run_cli("alpha", "history", job["id"])), 2)

        from fire_controller.jobs import JobStore

        store = JobStore(ProjectContext.load(self.contexts["alpha"]).database)
        api_result = store.get(job["id"])
        self.assertEqual(len(api_result["history"]), 2)
        self.assertEqual(api_result["brief"], compact["brief"])

    def test_job_checklist_items_compact_list_and_checkpoint_only_are_narrow_views(self):
        from fire_controller.cli import main
        from fire_controller.jobs import JobStore

        created = self.run_cli(
            "alpha",
            "create",
            "checklist-simple",
            "--worker",
            "General",
            "--title",
            "Simple checklist",
            "--checklist-item",
            "Audit common tasks",
            "--checklist-item",
            "Collect worker feedback",
        )
        store = JobStore(ProjectContext.load(self.contexts["alpha"]).database)
        stored = store.get(created["id"])
        self.assertEqual(
            [item["text"] for item in stored["checklist"]], ["Audit common tasks", "Collect worker feedback"]
        )
        self.assertTrue(all(item["done"] is False and item["id"] for item in stored["checklist"]))

        self.run_cli(
            "alpha",
            "create",
            "checklist-json",
            "--worker",
            "General",
            "--title",
            "JSON checklist",
            "--status",
            "parked",
            "--secondary",
            "--checklist",
            '[{"text":"Focused proof"}]',
        )
        invalid = self.run_cli(
            "alpha",
            "create",
            "checklist-invalid",
            "--worker",
            "General",
            "--title",
            "Invalid checklist",
            "--checklist",
            "not-json",
            success=False,
        )
        self.assertIn("invalid JSON at column", invalid.stderr)
        self.assertIn("--checklist-item TEXT", invalid.stderr)
        non_array = self.run_cli(
            "alpha",
            "create",
            "checklist-object",
            "--worker",
            "General",
            "--title",
            "Invalid checklist shape",
            "--checklist",
            '{"text":"not an array"}',
            success=False,
        )
        self.assertIn("expected a JSON array", non_array.stderr)
        self.assertIn("--checklist-item TEXT", non_array.stderr)

        output, errors = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(output), contextlib.redirect_stderr(errors):
            result = main(
                [
                    "--context",
                    str(self.contexts["alpha"]),
                    "jobs",
                    "list",
                    "--worker",
                    "General",
                    "--status",
                    "active",
                    "--compact",
                ]
            )
        self.assertEqual(result, 0, errors.getvalue())
        self.assertIn("ID  STATUS  WORKER  NAME / TITLE", output.getvalue())
        self.assertIn("checklist-simple", output.getvalue())
        self.assertNotIn("checklist-json", output.getvalue())
        self.assertNotIn("brief", output.getvalue())

        full_list = self.run_cli("alpha", "list", "--worker", "General")
        self.assertIsInstance(full_list, list)
        self.assertEqual({job["name"] for job in full_list}, {"checklist-simple", "checklist-json"})
        output, errors = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(output), contextlib.redirect_stderr(errors):
            result = main(
                [
                    "--context",
                    str(self.contexts["alpha"]),
                    "jobs",
                    "--json",
                    "list",
                    "--worker",
                    "General",
                    "--compact",
                ]
            )
        self.assertEqual(result, 2)
        self.assertIn("omit --json", errors.getvalue())

        checkpoint = self.run_cli(
            "alpha",
            "checkpoint",
            created["id"],
            "--done",
            "Audit complete",
            "--next",
            "Send findings",
        )
        self.assertEqual(checkpoint["next_steps"], "Send findings")
        full_read = self.run_cli("alpha", "read", created["id"])
        checkpoint_only = self.run_cli("alpha", "read", created["id"], "--checkpoint-only")
        self.assertEqual(set(checkpoint_only), {"id", "name", "status", "revision", "latest_checkpoint"})
        self.assertEqual(checkpoint_only["latest_checkpoint"]["next_steps"], "Send findings")
        self.assertNotIn("brief", checkpoint_only)
        self.assertNotIn("updates", checkpoint_only)
        self.assertIn("brief", full_read)
        self.assertIn("history", full_read)

    def test_note_job_selectors_and_failed_correction_preserve_state(self):
        job = self.run_cli("alpha", "create", "note-target", "--worker", "General", "--title", "Target")
        notes = [
            self.run_cli("alpha", "note", "--job", selector, "--body", "Original text")
            for selector in (job["id"], job["name"])
        ]
        for selector in (job["id"], job["name"]):
            self.assertEqual(
                {note["id"] for note in self.run_cli("alpha", "notes", "--job", selector)},
                {note["id"] for note in notes},
            )
        before = self.run_cli("alpha", "note-history", notes[0]["id"])
        self.run_cli(
            "alpha",
            "note-revise",
            notes[0]["id"],
            "--expect-revision",
            "1",
            "--job",
            "unknown-target",
            "--body",
            "Must not overwrite",
            success=False,
        )
        self.assertEqual(self.run_cli("alpha", "note-history", notes[0]["id"]), before)
        self.run_cli("alpha", "note", "--job", "unknown-target", "--body", "Must not insert", success=False)
        self.run_cli("alpha", "notes", "--job", "unknown-target", success=False)

    def test_append_checkpoint_stdin_and_checklist(self):
        body = self.root / "body.md"
        body.write_text("# Working instructions\nMeaningful handoff")
        job = self.run_cli(
            "alpha", "create", "check", "--worker", "Document", "--title", "Document work", "--body-file", str(body)
        )
        self.run_cli("alpha", "update", job["id"], "--body", "Proof finished")
        self.run_cli(
            "alpha",
            "checkpoint",
            job["id"],
            "--done",
            "Checked",
            "--next",
            "Publish",
            "--pointers",
            json.dumps({"branch": "codex/test", "proof": "focused test"}),
        )
        read = self.run_cli("alpha", "read", job["id"])
        self.assertEqual(read["brief"], body.read_text())
        revised = self.run_cli(
            "alpha", "checklist", job["id"], "add", "--expect-revision", str(read["revision"]), "--text", "Public proof"
        )
        self.assertTrue(revised["item_id"])
        self.assertLess(self.last_output_bytes, 1024)
        completed = self.run_cli(
            "alpha",
            "checklist",
            job["id"],
            "done",
            "--expect-revision",
            str(revised["revision"]),
            "--item-id",
            revised["item_id"],
        )
        self.assertEqual(completed["item_id"], revised["item_id"])

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
        for arguments in (
            ["records", "history", "--database", str(self.root / "beta" / "state.sqlite3")],
            ["records", "history", "--repo=example/beta"],
            ["state", "migrate-sqlite", "--path", str(self.root / "beta" / "state.json")],
        ):
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
        context_file.write_text(
            json.dumps(
                {
                    "root": str(selected.root),
                    "database": str(selected.root / ".git" / "firemud" / "pr-review-stack.sqlite3"),
                    "repository": "example/alpha",
                }
            )
        )
        original = Path.cwd()
        seen = {}

        def fake_engine(arguments):
            seen.update(root=Path.cwd(), repository=os.environ["GH_REPO"], arguments=arguments)
            return 0

        fake_git = subprocess.CompletedProcess([], 0, stdout=".git\n", stderr="")
        with patch("subprocess.run", return_value=fake_git), patch("pr_review.cli.main", side_effect=fake_engine):
            self.assertEqual(main(["--context", str(context_file), "reviews", "stack", "show"]), 0)
        self.assertEqual(seen, {"root": selected.root, "repository": "example/alpha", "arguments": ["stack", "show"]})
        self.assertEqual(Path.cwd(), original)

    def test_context_rejects_database_mismatch(self):
        self.run_cli("alpha", "list", success=True)
        from fire_controller.cli import main

        result = main(
            [
                "--context",
                str(self.contexts["alpha"]),
                "jobs",
                "--database",
                str(self.root / "beta" / "state.sqlite3"),
                "list",
            ]
        )
        self.assertEqual(result, 2)

    def test_review_unread_notice_preserves_stdout_and_is_not_a_gate(self):
        from fire_controller.cli import main
        from fire_controller.inbox import InboxStore

        context_file = self.root / "notice-context.json"
        database = self.root / "alpha" / ".git" / "firemud" / "pr-review-stack.sqlite3"
        database.parent.mkdir(parents=True)
        context_file.write_text(
            json.dumps({"root": str(self.root / "alpha"), "database": str(database), "repository": "example/alpha"})
        )
        inbox = InboxStore(database)
        inbox.bootstrap()
        inbox.send("General", "Private unseen message", author="Overseer")
        fake_git = subprocess.CompletedProcess([], 0, stdout=".git\n", stderr="")

        def engine(arguments):
            print('{"existing_contract":true}')
            return 0

        output, errors = io.StringIO(), io.StringIO()
        with (
            patch("subprocess.run", return_value=fake_git),
            patch("pr_review.cli.main", side_effect=engine),
            contextlib.redirect_stdout(output),
            contextlib.redirect_stderr(errors),
        ):
            result = main(
                ["--context", str(context_file), "--worker-identity", "General", "reviews", "status", "--json"]
            )
        self.assertEqual(result, 0)
        self.assertEqual(output.getvalue(), '{"existing_contract":true}\n')
        self.assertIn("1 unread", errors.getvalue())
        self.assertNotIn("Private unseen", output.getvalue() + errors.getvalue())
        with (
            patch("fire_controller.inbox.InboxStore.unread_count", side_effect=ValueError("bad inbox")),
            patch("subprocess.run", return_value=fake_git),
            patch("pr_review.cli.main", side_effect=engine),
            contextlib.redirect_stdout(io.StringIO()),
        ):
            self.assertEqual(
                main(["--context", str(context_file), "--worker-identity", "General", "reviews", "status", "--json"]), 0
            )

    def test_note_correction_and_explicit_pause_survive_routine_writes(self):
        job = self.run_cli("alpha", "create", "unfinished", "--worker", "General", "--title", "Work")
        paused = self.run_cli(
            "alpha",
            "lane-pause",
            "General",
            "--job",
            job["id"],
            "--body",
            "Private pause checkpoint",
            "--reason",
            "Human paused work",
        )
        self.assertEqual(paused["status"], "paused")
        note = self.run_cli("alpha", "note", "--worker", "General", "--body", "Deferred source")
        dismissed = self.run_cli(
            "alpha",
            "note-status",
            note["id"],
            "dismissed",
            "--reason",
            "Mistaken duplicate",
            "--expect-revision",
            str(note["revision"]),
        )
        dismissed = self.run_cli(
            "alpha",
            "note-revise",
            note["id"],
            "--reason",
            "Corrected dismissal reason",
            "--expect-revision",
            str(dismissed["revision"]),
        )
        corrected = self.run_cli(
            "alpha",
            "note-revise",
            note["id"],
            "--status",
            "pending",
            "--body",
            "Corrected source",
            "--expect-revision",
            str(dismissed["revision"]),
        )
        self.assertEqual(corrected["body"], "Corrected source")
        self.run_cli(
            "alpha",
            "note-revise",
            note["id"],
            "--body",
            "Stale overwrite",
            "--expect-revision",
            str(note["revision"]),
            success=False,
        )
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
            self.assertEqual(
                main(["--context", str(self.contexts["alpha"]), "inbox", "--json", "send", "Overseer", "--body", body]),
                0,
            )
        result = json.loads(output.getvalue())
        self.assertTrue(result["_fire_controller"]["markdown_diagnostics"])
        self.assertEqual(inbox.list("Overseer")[0]["body"], body)
        with (
            patch("fire_controller.markdown.diagnostics", side_effect=ValueError("checker failed")),
            contextlib.redirect_stderr(io.StringIO()),
        ):
            self.assertEqual(
                main(
                    ["--context", str(self.contexts["alpha"]), "inbox", "send", "Overseer", "--body", "Must not write"]
                ),
                2,
            )
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
            self.assertEqual(
                main(
                    [
                        "--context",
                        str(self.contexts["alpha"]),
                        "--worker-identity",
                        "General",
                        "jobs",
                        "--json",
                        "public-export",
                    ]
                ),
                0,
            )
        export = json.loads(output.getvalue())
        self.assertIsInstance(export, list)
        self.assertNotIn("_fire_controller", output.getvalue())
        self.assertNotIn("unread", output.getvalue())
        self.assertNotIn("Private caller canary", output.getvalue())
        self.assertIn("1 unread", errors.getvalue())


if __name__ == "__main__":
    unittest.main()
