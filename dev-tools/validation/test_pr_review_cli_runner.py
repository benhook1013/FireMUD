import contextlib
import dataclasses
import fcntl
import hashlib
import json
import sqlite3
import subprocess
import sys
import tempfile
import threading
import time
import unittest
from pathlib import Path
from subprocess import CompletedProcess
from types import SimpleNamespace
from unittest.mock import Mock, patch

DEV_TOOLS = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(DEV_TOOLS))

from pr_review import cli as cli_module
from pr_review import cli_attempts, cli_runner, evidence, github, hosted
from pr_review.cli import _parser, _render
from pr_review.cli_runner import (
    HOSTED_CLI_OVERLAP_HOLD_REASON,
    EffectiveParent,
    PullRequestSnapshot,
    ReviewRunnerError,
    ReviewTarget,
    StaleReviewTargetError,
    SubprocessRunner,
    UnreconciledReviewError,
    WrongTargetError,
    _name_only_diff_args,
    _test_merge_commit,
    _validate_target,
    _verify_target_still_current,
    run_cli_review,
    target_from_resolver,
)
from pr_review.patch_identity import patch_diff_args
from pr_review.policy import Channel, taper_satisfied
from pr_review.runtime import LiveEvidence, LiveGitHub
from pr_review.sqlite_review_records import ReviewRecordsError, SqliteReviewRecords
from pr_review.sqlite_store import SqliteStateStore

BASE = "a" * 40
PARENT = BASE
HEAD = "c" * 40
CANDIDATE = "d" * 40
CONTEXT = "f" * 40
ADVANCED = "9" * 40
OLDER_BASE = "e" * 40


class BoundedAllocationCliParserTests(unittest.TestCase):
    def test_bounded_allocation_grant_accepts_exact_checkpoint_and_positive_cap(self):
        args = _parser().parse_args(
            [
                "decide",
                "allocation",
                "grant",
                "--pr",
                "2827",
                "--channel",
                "hosted",
                "--head",
                HEAD,
                "--checkpoint",
                "5846432587",
                "--max-additional-completed",
                "2",
                "--reason",
                "bounded additional reviews",
            ]
        )

        self.assertEqual(args.action, "grant")
        self.assertEqual(args.checkpoint, "5846432587")
        self.assertEqual(args.max_additional_completed, 2)

    def test_minimum_and_maximum_are_optional_without_a_pinned_checkpoint(self):
        common = [
            "decide",
            "allocation",
            "grant",
            "--pr",
            "2827",
            "--channel",
            "cli",
            "--head",
            HEAD,
            "--reason",
            "additional review judgment",
        ]
        minimum_only = _parser().parse_args([*common, "--min-additional-completed", "2"])
        self.assertIsNone(minimum_only.checkpoint)
        self.assertEqual(minimum_only.min_additional_completed, 2)
        self.assertIsNone(minimum_only.max_additional_completed)

        maximum_only = _parser().parse_args([*common, "--max-additional-completed", "3"])
        self.assertIsNone(maximum_only.checkpoint)
        self.assertIsNone(maximum_only.min_additional_completed)
        self.assertEqual(maximum_only.max_additional_completed, 3)

    def test_exact_additional_completed_accepts_only_a_positive_integer(self):
        common = [
            "decide",
            "allocation",
            "grant",
            "--pr",
            "2827",
            "--channel",
            "cli",
            "--head",
            HEAD,
            "--reason",
            "three more completed CLI rounds",
            "--exact-additional-completed",
        ]

        args = _parser().parse_args([*common, "3"])

        self.assertEqual(args.exact_additional_completed, 3)
        for invalid in ("0", "-1", "not-an-integer"):
            with self.subTest(invalid=invalid), self.assertRaises(SystemExit):
                _parser().parse_args([*common, invalid])

    def test_exact_additional_completed_rejects_min_or_max_before_dispatch(self):
        common = [
            "decide",
            "allocation",
            "grant",
            "--pr",
            "2827",
            "--channel",
            "cli",
            "--head",
            HEAD,
            "--reason",
            "exact additional review count",
        ]
        self.assertEqual(
            _parser().parse_args(
                [*common, "--min-additional-completed", "1", "--max-additional-completed", "4"]
            ).min_additional_completed,
            1,
        )
        for bound in ("--min-additional-completed", "--max-additional-completed"):
            with self.subTest(bound=bound), patch.object(cli_module, "_dispatch") as dispatch:
                with self.assertRaises(SystemExit):
                    cli_module.main([*common, "--exact-additional-completed", "3", bound, "2"])
                dispatch.assert_not_called()

    def test_legacy_cancel_shape_needs_no_cap_replacement(self):
        args = _parser().parse_args(
            [
                "decide",
                "allocation",
                "cancel",
                "--pr",
                "2827",
                "--channel",
                "hosted",
                "--head",
                HEAD,
                "--reason",
                "cancel the explicit cap",
            ]
        )

        self.assertIsNone(args.checkpoint)
        self.assertIsNone(args.max_additional_completed)


class HostedCliPreflightBudgetTests(unittest.TestCase):
    def test_main_starts_one_budget_before_dispatch_and_leaves_other_commands_unchanged(self):
        observed = []

        def hosted_controller(_args):
            budget = github.active_hosted_preflight_budget()
            self.assertIsNotNone(budget)
            observed.append(budget)

            def run_hosted(**_kwargs):
                with github.hosted_preflight_budget() as nested:
                    self.assertIs(nested, budget)
                budget.complete()
                return {"status": "posted"}

            return SimpleNamespace(run_hosted=run_hosted), None

        def status_controller(_args):
            self.assertIsNone(github.active_hosted_preflight_budget())
            return SimpleNamespace(status_overview=lambda: {"status": "ok"}), None

        with patch.object(cli_module, "_controller", side_effect=hosted_controller), patch("builtins.print"):
            self.assertEqual(cli_module.main(["run", "hosted", "--expect-pr", "42"]), 0)
        self.assertEqual(len(observed), 1)
        self.assertIsNone(github.active_hosted_preflight_budget())

        with patch.object(cli_module, "_controller", side_effect=status_controller), patch("builtins.print"):
            self.assertEqual(cli_module.main(["status"]), 0)


def _git(root, *args, input_text=None):
    return subprocess.run(
        ["git", "-C", str(root), *args],
        input=input_text,
        check=True,
        capture_output=True,
        text=True,
    ).stdout.strip()


def _commit_tree_case(root, *, conflict=False):
    subprocess.run(["git", "init", "--quiet", str(root)], check=True, capture_output=True)

    def make_tree(files):
        entries = []
        for name, content in sorted(files.items()):
            blob = _git(root, "hash-object", "-w", "--stdin", input_text=content)
            entries.append(f"100644 blob {blob}\t{name}\n")
        return _git(root, "mktree", input_text="".join(entries))

    def make_commit(tree, message, parent=None):
        args = ["-c", "user.name=FireMUD test", "-c", "user.email=test@example.invalid", "commit-tree", tree]
        if parent is not None:
            args.extend(("-p", parent))
        args.extend(("-m", message))
        return _git(root, *args)

    common = make_commit(make_tree({"common.txt": "common\n"}), "common")
    base_files = {"base.txt": "base\n", "common.txt": "base version\n" if conflict else "common\n"}
    head_files = {"head.txt": "head\n", "common.txt": "head version\n" if conflict else "common\n"}
    base = make_commit(make_tree(base_files), "base", common)
    head = make_commit(make_tree(head_files), "head", common)
    expected = None if conflict else make_tree({**base_files, **head_files})
    return base, head, expected


class FakeGitHub:
    mergeable = "MERGEABLE"
    base_exists = True

    def __init__(self, files=None):
        self.files = files or ["src/Representative.java"]

    def pull_request(self, number):
        return PullRequestSnapshot(
            number,
            "OPEN",
            "develop",
            BASE,
            HEAD,
            changed_files=len(self.files),
            mergeable=self.mergeable,
            base_exists=self.base_exists,
        )

    def pull_request_files(self, number):
        return self.files

    def branch_head(self, ref_name):
        return PARENT


class FakeCommands:
    def __init__(
        self,
        root,
        delay=0,
        candidate=HEAD,
        review_started=None,
        allow_review_finish=None,
        files=None,
        patch_bytes=None,
        timeout_review=False,
        timeout_git=False,
        review_output="review output\n",
        review_stderr="",
        review_returncode=0,
        parent_is_ancestor=True,
        merge_base=None,
        merge_conflict=False,
        merge_tree=CONTEXT,
        merge_bases=None,
    ):
        self.root = root
        self.delay = delay
        self.review_started = review_started
        self.allow_review_finish = allow_review_finish
        self.active = 0
        self.overlap = False
        self.calls = []
        self.guard = threading.Lock()
        self.candidate = candidate
        self.files = files or ["src/Representative.java"]
        self.patch_bytes = patch_bytes or f"candidate patch {self.candidate}\n".encode()
        self.timeout_review = timeout_review
        self.timeout_git = timeout_git
        self.review_output = review_output
        self.review_stderr = review_stderr
        self.review_returncode = review_returncode
        self.parent_is_ancestor = parent_is_ancestor
        self.merge_base = merge_base or PARENT
        self.merge_conflict = merge_conflict
        self.merge_tree = merge_tree
        self.merge_bases = merge_bases or {}
        self.timeout_calls = []
        self.test_worktrees = set()
        self.records = None
        self.attempt_states_at_review_invocation = []

    def run(self, args, *, cwd=None, capture_output=False, check=True, text=True, timeout=None):
        self.calls.append((tuple(args), cwd))
        self.timeout_calls.append((tuple(args), timeout, text))
        if args[0] == "coderabbit":
            if self.records is not None:
                self.attempt_states_at_review_invocation = [
                    attempt["state"] for attempt in self.records.attempt_history(42)
                ]
            if self.timeout_review:
                raise subprocess.TimeoutExpired(args, timeout, output=b"partial \xff\n", stderr=b"timed out\n")
            with self.guard:
                self.active += 1
                if self.active > 1:
                    self.overlap = True
            if self.review_started is not None:
                self.review_started.set()
            if self.allow_review_finish is not None:
                self.allow_review_finish.wait(timeout=3)
            time.sleep(self.delay)
            with self.guard:
                self.active -= 1
            return CompletedProcess(args, self.review_returncode, self.review_output, self.review_stderr)
        if args and args[0] == "git" and "-C" in args:
            if self.timeout_git:
                raise subprocess.TimeoutExpired(args, timeout, output=b"partial git\n", stderr=b"timed out\n")
            directory_index = args.index("-C")
            command_root = Path(args[directory_index + 1])
            git_args = args[directory_index + 2 :]
            if git_args[:2] == ["merge-tree", "--write-tree"]:
                return CompletedProcess(args, 0, f"{self.merge_tree}\n", "")
            if command_root == self.root and git_args[:2] == ["worktree", "add"]:
                self.test_worktrees.add(Path(git_args[-2]))
                return CompletedProcess(args, 0, "", "")
            if command_root == self.root and git_args[:2] == ["worktree", "remove"]:
                self.test_worktrees.discard(Path(git_args[-1]))
                return CompletedProcess(args, 0, "", "")
            if command_root in self.test_worktrees:
                if "merge" in git_args:
                    if self.merge_conflict:
                        return CompletedProcess(args, 1, "", "conflict\n")
                    return CompletedProcess(args, 0, "", "")
                if git_args == ["write-tree"]:
                    return CompletedProcess(args, 0, f"{self.merge_tree}\n", "")
            if git_args == ["rev-parse", "--git-common-dir"]:
                return CompletedProcess(args, 0, ".git\n", "")
            if git_args == ["rev-parse", "HEAD^{commit}"]:
                return CompletedProcess(args, 0, f"{self.candidate}\n", "")
            if git_args[:3] == ["diff", "--name-only", "-z"]:
                return CompletedProcess(args, 0, "\0".join(self.files) + "\0", "")
            patch_args = tuple(git_args)
            candidate_patch_args = tuple(patch_diff_args(PARENT, self.candidate))
            published_patch_args = tuple(patch_diff_args(PARENT, HEAD))
            if patch_args in {candidate_patch_args, published_patch_args}:
                patch_bytes = (
                    self.patch_bytes if patch_args == candidate_patch_args else f"candidate patch {HEAD}\n".encode()
                )
                output = patch_bytes if not text else patch_bytes.decode("utf-8")
                return CompletedProcess(args, 0, output, b"" if not text else "")
            if git_args == ["rev-list", "--count", f"{HEAD}..{self.candidate}"]:
                return CompletedProcess(args, 0, "1\n", "")
            if git_args[:2] == ["merge-base", "--all"]:
                selected_base = self.merge_bases.get((git_args[2], git_args[3]), self.merge_base)
                return CompletedProcess(args, 0, f"{selected_base}\n", "")
            if git_args[:3] == ["merge-base", "--is-ancestor", HEAD]:
                return CompletedProcess(args, 0, "", "")
            if git_args[:3] == ["merge-base", "--is-ancestor", PARENT]:
                return CompletedProcess(args, 0 if self.parent_is_ancestor else 1, "", "")
            if git_args[:2] == ["cat-file", "-t"]:
                return CompletedProcess(args, 0, "tree\n", "")
            if len(git_args) == 2 and git_args[0] == "rev-parse" and git_args[1].endswith("^{tree}"):
                return CompletedProcess(args, 0, f"{self.merge_tree}\n", "")
            if "commit-tree" in git_args:
                return CompletedProcess(args, 0, f"{CONTEXT}\n", "")
            if git_args[:3] == ["show", "-s", "--format=%P"]:
                return CompletedProcess(args, 0, f"{BASE} {HEAD}\n", "")
            return CompletedProcess(args, 0, "", "")
        raise AssertionError(f"unexpected command: {args}")


def target(
    *,
    reconciled=True,
    ancestor_links_valid=True,
    merge_base="",
    patch_identity="",
    default_base_front=False,
    changed_files=1,
    parent_ref="develop",
    parent_head=PARENT,
    parent_pr=None,
    candidate_warnings=(),
):
    snapshot = PullRequestSnapshot(42, "OPEN", "develop", BASE, HEAD, changed_files=changed_files)
    return ReviewTarget(
        snapshot,
        EffectiveParent(parent_ref, parent_head, parent_pr),
        reconciled=reconciled,
        ancestor_links_valid=ancestor_links_valid,
        merge_base=merge_base,
        patch_identity=patch_identity,
        repository="owner/repo",
        default_base_front=default_base_front,
        default_test_merge_base_sha=BASE if default_base_front else "",
        default_test_merge_head_sha=HEAD if default_base_front else "",
        default_test_merge_tree_sha=CONTEXT if default_base_front else "",
        candidate_warnings=tuple(candidate_warnings),
    )


def hosted_payload(body="Full review triggered", *, include_response=True):
    trigger_at = "2026-01-01T00:00:00Z"
    response_at = "2026-01-01T00:01:00Z"
    comments = [
        {
            "databaseId": 100,
            "author": {"login": "ben"},
            "body": hosted.FULL_COMMAND,
            "createdAt": trigger_at,
            "url": "https://example.test/trigger",
        }
    ]
    if include_response:
        comments.append(
            {
                "databaseId": 101,
                "author": {"login": "coderabbitai[bot]"},
                "body": body,
                "createdAt": response_at,
                "url": "https://example.test/response",
            }
        )
    return {
        "data": {
            "repository": {
                "pullRequest": {
                    "headRefOid": HEAD,
                    "comments": {"nodes": comments},
                    "reviews": {"nodes": []},
                }
            }
        }
    }


def write_hosted_trigger(common_dir, *, legacy=False, status="posted", head_sha=HEAD, anchor=None):
    namespace = "coderabbit-review-logs" if legacy else "firemud"
    directory = common_dir / namespace / "hosted" / "owner_repo" / "pr-42"
    directory.mkdir(parents=True)
    record_path = directory / "trigger.json"
    record_path.write_text(
        json.dumps(
            {
                "schema_version": 1,
                "status": status,
                "repository": "owner/repo",
                "pr_number": 42,
                "head_sha": head_sha,
                **({"anchor": anchor} if anchor is not None else {}),
                **(
                    {
                        "trigger": {
                            "id": 100,
                            "created_at": "2026-01-01T00:00:00Z",
                            "url": "https://example.test/trigger",
                            "type": "full",
                            "command": hosted.FULL_COMMAND,
                        }
                    }
                    if status == "posted"
                    else {}
                ),
            }
        )
    )
    return record_path


def cli_anchor(*, parent_identity="develop", parent_head=PARENT, merge_base=PARENT, patch_id=None):
    return {
        "pr": 42,
        "child_head": HEAD,
        "parent_identity": parent_identity,
        "parent_head": parent_head,
        "merge_base": merge_base,
        "patch_id": patch_id or hashlib.sha256(f"candidate patch {HEAD}\n".encode()).hexdigest(),
    }


class CliReviewRunnerTests(unittest.TestCase):
    def test_admission_callback_must_reserve_before_attempt_or_provider(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / ".git").mkdir()
            database = root / "records.sqlite3"
            SqliteStateStore(database).update(lambda state: state)
            records = SqliteReviewRecords(database)
            records.bootstrap()
            commands = FakeCommands(root)
            commands.records = records
            callback_calls = []

            def admit_without_reserving(reserve):
                callback_calls.append(reserve)

            with self.assertRaisesRegex(ReviewRunnerError, "without reserving"):
                run_cli_review(
                    target(),
                    github=FakeGitHub(),
                    source_root=root,
                    runner=commands,
                    records=records,
                    admit=admit_without_reserving,
                )

            self.assertEqual(len(callback_calls), 1)
            self.assertEqual(records.attempt_history(42), [])
            self.assertEqual(
                [call for call in commands.calls if call[0][0] == "coderabbit"],
                [],
            )

    def test_sqlite_attempt_is_written_before_provider_and_completed_with_json_events(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / ".git").mkdir()
            database = root / "records.sqlite3"
            SqliteStateStore(database).update(lambda state: state)
            records = SqliteReviewRecords(database)
            records.bootstrap()
            output = (
                '{"type":"start","reviewType":"full"}\n'
                '{"type":"complete","status":"review_completed",'
                '"findings":0,"reviewedFiles":["src/Representative.java"]}\n'
            )
            commands = FakeCommands(root, review_output=output)
            commands.records = records
            result = run_cli_review(
                target(),
                github=FakeGitHub(),
                source_root=root,
                runner=commands,
                records=records,
            )
            attempts = records.attempt_history(result.pull_request)
            self.assertEqual(len(attempts), 1)
            self.assertEqual(attempts[0]["attempt_id"], result.run_id)
            self.assertEqual(commands.attempt_states_at_review_invocation, ["started"])
            self.assertEqual(attempts[0]["state"], "completed")
            attempt = records.attempt(result.run_id)
            run = records.history(result.pull_request)["runs"][0]
            self.assertTrue(attempt["finished_at"].endswith("Z"))
            self.assertEqual(attempt["finished_at"], run["finished_at"])
            self.assertEqual(attempts[0]["duration_seconds"], result.duration_seconds)
            self.assertEqual(attempt["metadata"]["candidate_sha"], result.candidate_sha)
            with sqlite3.connect(database) as connection:
                kinds = {
                    row[0]
                    for row in connection.execute(
                        "SELECT kind FROM review_artifacts WHERE attempt_id = ?", (result.run_id,)
                    )
                }
            self.assertEqual(kinds, {"cli_events", "cli_diagnostic", "metadata"})

    def test_runner_accepts_unicode_line_separators_inside_json_finding(self):
        output = (
            json.dumps(
                {
                    "type": "finding",
                    "codegenInstructions": "Review comment at @src/Representative.java:1\nKeep\u2028the\u2029line.",
                },
                ensure_ascii=False,
            )
            + "\n"
            + json.dumps(
                {
                    "type": "complete",
                    "status": "review_completed",
                    "findings": 1,
                    "reviewedFiles": ["src/Representative.java"],
                },
                ensure_ascii=False,
            )
            + "\n"
        )
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / ".git").mkdir()
            database = root / "records.sqlite3"
            SqliteStateStore(database).update(lambda state: state)
            records = SqliteReviewRecords(database)
            records.bootstrap()

            result = run_cli_review(
                target(),
                github=FakeGitHub(),
                source_root=root,
                runner=FakeCommands(root, review_output=output),
                records=records,
            )

            self.assertEqual(records.attempt(result.run_id)["state"], "completed")
            self.assertEqual(records.history(result.pull_request)["findings"][0]["title"], "Keep\u2028the\u2029line.")

    def test_successful_capture_recovers_the_original_started_attempt_after_sql_failure(self):
        output = (
            json.dumps(
                {
                    "type": "finding",
                    "codegenInstructions": "Review comment at @src/Representative.java:1\nUse the safer path.",
                }
            )
            + "\n"
            + json.dumps(
                {
                    "type": "complete",
                    "status": "review_completed",
                    "findings": 1,
                    "reviewedFiles": ["src/Representative.java"],
                }
            )
            + "\n"
        )
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / ".git").mkdir()
            database = root / ".git" / "firemud" / "records.sqlite3"
            database.parent.mkdir()
            SqliteStateStore(database).update(lambda state: state)
            records = SqliteReviewRecords(database)
            records.bootstrap()
            commands = FakeCommands(root, review_output=output)
            interleaved_reports = []
            atomic_json = cli_runner._atomic_json

            def migrate_before_final_metadata(path, value):
                if path.name == "metadata.json" and "duration_seconds" in value:
                    report = cli_attempts.reconcile_legacy_failed_attempts(records, database)
                    interleaved_reports.append(report)
                    self.assertEqual(records.attempt(value["run_id"])["state"], "started")
                atomic_json(path, value)

            with (
                patch.object(
                    records,
                    "complete_attempt_run",
                    side_effect=ReviewRecordsError("simulated archive failure"),
                ),
                patch("pr_review.cli_runner._atomic_json", side_effect=migrate_before_final_metadata),
            ):
                result = run_cli_review(
                    target(),
                    github=FakeGitHub(),
                    source_root=root,
                    runner=commands,
                    records=records,
                )

            self.assertIn("was not saved", result.warning)
            self.assertEqual(len(interleaved_reports), 1)
            self.assertFalse(interleaved_reports[0]["available"])
            self.assertIn("CLI review is active", interleaved_reports[0]["reason"])
            self.assertEqual(interleaved_reports[0]["recovered"], [])
            run_id = next((root / ".git" / "firemud" / "pr-review" / "runs").iterdir()).name
            self.assertEqual(
                (root / ".git" / "firemud" / "pr-review" / "runs" / run_id / "capture-complete").read_text(),
                f"{run_id}\n",
            )
            self.assertEqual(records.attempt(run_id)["state"], "started")
            report = cli_attempts.reconcile_legacy_failed_attempts(records, database)
            self.assertEqual(report["recovered"], [{"run_id": run_id, "pr": "42"}])
            self.assertEqual(report["terminally_classified"], [])
            attempt = records.attempt(run_id)
            self.assertEqual(attempt["state"], "completed")
            self.assertEqual(attempt["run_id"], run_id)
            self.assertEqual(records.history(42)["runs"][0]["counts"]["found"], 1)

    def test_provider_runs_when_optional_attempt_start_fails(self):
        for failure in (
            ReviewRecordsError("SQLite attempt start failed"),
            OSError("attempt database unavailable"),
            sqlite3.DatabaseError("SQLite connection failed"),
        ):
            with self.subTest(failure=type(failure).__name__), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                (root / ".git").mkdir()
                database = root / ".git" / "firemud" / "records.sqlite3"
                database.parent.mkdir()
                SqliteStateStore(database).update(lambda state: state)
                records = SqliteReviewRecords(database)
                records.bootstrap()
                commands = FakeCommands(
                    root,
                    review_output=(
                        '{"type":"complete","status":"review_completed",'
                        '"findings":0,"reviewedFiles":["src/Representative.java"]}\n'
                    ),
                )
                commands.records = records

                with patch.object(records, "start_attempt", side_effect=failure):
                    result = run_cli_review(
                        target(),
                        github=FakeGitHub(),
                        source_root=root,
                        runner=commands,
                        records=records,
                    )

                self.assertTrue(any(call[0][0] == "coderabbit" for call in commands.calls))
                self.assertTrue((result.capture_dir / "capture-complete").is_file())
                self.assertIn("SQLite attempt and source decisions are missing", result.warning)
                self.assertIn("durable CLI capture remains", result.warning)
                self.assertIn("Record decisions.tsv for this run", result.warning)
                self.assertEqual(result.exit_status, 0)
                self.assertEqual(records.attempt_history(42), [])

    def test_setup_error_survives_sqlite_archival_database_error(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / ".git").mkdir()
            database = root / ".git" / "firemud" / "records.sqlite3"
            database.parent.mkdir()
            SqliteStateStore(database).update(lambda state: state)
            records = SqliteReviewRecords(database)
            records.bootstrap()
            commands = FakeCommands(root)

            with (
                patch.object(
                    cli_runner,
                    "_verify_target_still_current",
                    side_effect=ReviewRunnerError("target changed during setup"),
                ),
                patch.object(
                    records,
                    "finish_attempt",
                    side_effect=sqlite3.DatabaseError("archive database unavailable"),
                ),
                self.assertRaisesRegex(ReviewRunnerError, "target changed during setup"),
            ):
                run_cli_review(
                    target(),
                    github=FakeGitHub(),
                    source_root=root,
                    runner=commands,
                    records=records,
                )

            self.assertFalse(any(call[0][0] == "coderabbit" for call in commands.calls))
            capture_dir = next((root / ".git" / "firemud" / "pr-review" / "runs").iterdir())
            self.assertIn("target changed during setup", (capture_dir / "error").read_text())

    def test_provider_result_survives_sqlite_completion_failures(self):
        cases = (
            ("finding validation", "finding"),
            ("completed attempt archival", "complete"),
            ("failed attempt archival", "finish"),
        )
        for _label, failure_kind in cases:
            with self.subTest(failure_kind=failure_kind), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                (root / ".git").mkdir()
                database = root / ".git" / "firemud" / "records.sqlite3"
                database.parent.mkdir()
                SqliteStateStore(database).update(lambda state: state)
                records = SqliteReviewRecords(database)
                records.bootstrap()
                if failure_kind == "finish":
                    output = "provider returned no complete result\n"
                    returncode = 1
                else:
                    output = (
                        '{"type":"finding","codegenInstructions":"Review comment at '
                        '@src/Representative.java:1\\nKeep the safer path."}\n'
                        '{"type":"complete","status":"review_completed",'
                        '"findings":1,"reviewedFiles":["src/Representative.java"]}\n'
                    )
                    returncode = 0
                commands = FakeCommands(root, review_output=output, review_returncode=returncode)
                failure = ReviewRecordsError("injected SQLite completion failure")
                with contextlib.ExitStack() as stack:
                    if failure_kind == "finding":
                        stack.enter_context(patch.object(cli_runner, "FindingObservation", side_effect=failure))
                    elif failure_kind == "complete":
                        stack.enter_context(patch.object(records, "complete_attempt_run", side_effect=failure))
                    else:
                        stack.enter_context(patch.object(records, "finish_attempt", side_effect=failure))
                    result = run_cli_review(
                        target(),
                        github=FakeGitHub(),
                        source_root=root,
                        runner=commands,
                        records=records,
                    )

                self.assertTrue(any(call[0][0] == "coderabbit" for call in commands.calls))
                self.assertEqual(result.exit_status, returncode)
                self.assertIn("was not saved", result.warning)
                self.assertTrue((result.capture_dir / "capture-complete").is_file())
                self.assertEqual(records.attempt(result.run_id)["state"], "started")

    def test_zero_exit_with_incomplete_json_is_a_failed_command_and_attempt(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / ".git").mkdir()
            database = root / ".git" / "firemud" / "records.sqlite3"
            database.parent.mkdir()
            SqliteStateStore(database).update(lambda state: state)
            records = SqliteReviewRecords(database)
            records.bootstrap()

            result = run_cli_review(
                target(),
                github=FakeGitHub(),
                source_root=root,
                runner=FakeCommands(root, review_output="not JSON\n", review_returncode=0),
                records=records,
            )

            self.assertEqual(result.exit_status, 1)
            self.assertEqual((result.capture_dir / "exit-status").read_text(), "0\n")
            self.assertEqual(
                json.loads((result.capture_dir / "metadata.json").read_text())["exit_status"],
                0,
            )
            attempt = next(
                item for item in records.attempt_history(result.pull_request) if item["attempt_id"] == result.run_id
            )
            self.assertEqual(attempt["state"], "failed")
            self.assertEqual(attempt["exit_status"], 0)
            self.assertEqual(
                attempt["diagnostic"],
                "CodeRabbit CLI did not return a complete JSON review",
            )
            self.assertEqual(records.history(result.pull_request)["runs"], [])

    def test_redacted_cli_headline_is_bounded_before_sql_completion(self):
        long_headline = " ".join(["Bearer x"] * 40)
        output = (
            json.dumps(
                {
                    "type": "finding",
                    "codegenInstructions": long_headline,
                }
            )
            + "\n"
            + json.dumps(
                {
                    "type": "complete",
                    "status": "review_completed",
                    "findings": 1,
                    "reviewedFiles": ["src/Representative.java"],
                }
            )
            + "\n"
        )
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / ".git").mkdir()
            database = root / ".git" / "firemud" / "records.sqlite3"
            database.parent.mkdir()
            SqliteStateStore(database).update(lambda state: state)
            records = SqliteReviewRecords(database)
            records.bootstrap()
            result = run_cli_review(
                target(),
                github=FakeGitHub(),
                source_root=root,
                runner=FakeCommands(root, review_output=output),
                records=records,
            )

            with sqlite3.connect(database) as connection:
                title, detail = connection.execute(
                    "SELECT title, detail FROM finding_observations WHERE run_id = ?", (result.run_id,)
                ).fetchone()
            self.assertEqual(len(title), 300)
            self.assertNotIn("Bearer ", title)
            self.assertLessEqual(len(detail), 1000)
            self.assertNotIn("Bearer ", detail)
            self.assertIn("[redacted credential]", detail)

    def test_control_characters_in_cli_headline_are_normalized_before_recording(self):
        output = (
            json.dumps(
                {
                    "type": "finding",
                    "codegenInstructions": (
                        "Review comment at @src/Representative.java:1\nKeep\u0000 the \u001b[31msafer\u001b[0m path."
                    ),
                }
            )
            + "\n"
            + json.dumps(
                {
                    "type": "complete",
                    "status": "review_completed",
                    "findings": 1,
                    "reviewedFiles": ["src/Representative.java"],
                }
            )
            + "\n"
        )
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / ".git").mkdir()
            database = root / ".git" / "firemud" / "records.sqlite3"
            database.parent.mkdir()
            SqliteStateStore(database).update(lambda state: state)
            records = SqliteReviewRecords(database)
            records.bootstrap()
            result = run_cli_review(
                target(),
                github=FakeGitHub(),
                source_root=root,
                runner=FakeCommands(root, review_output=output),
                records=records,
            )

            with sqlite3.connect(database) as connection:
                title, detail = connection.execute(
                    "SELECT title, detail FROM finding_observations WHERE run_id = ?", (result.run_id,)
                ).fetchone()
            self.assertEqual(title, "Keep the safer path.")
            self.assertEqual(detail, "Keep the safer path.")

    def test_unrecordable_success_capture_is_terminally_non_counting(self):
        output = (
            '{"type":"complete","status":"review_completed","findings":0,"reviewedFiles":["src/Representative.java"]}\n'
        )
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / ".git").mkdir()
            database = root / ".git" / "firemud" / "records.sqlite3"
            database.parent.mkdir()
            SqliteStateStore(database).update(lambda state: state)
            records = SqliteReviewRecords(database)
            records.bootstrap()
            commands = FakeCommands(root, review_output=output)
            with patch.object(
                records,
                "complete_attempt_run",
                side_effect=ReviewRecordsError("simulated archive failure"),
            ):
                result = run_cli_review(
                    target(),
                    github=FakeGitHub(),
                    source_root=root,
                    runner=commands,
                    records=records,
                )
            self.assertIn("was not saved", result.warning)
            capture_dir = next((root / ".git" / "firemud" / "pr-review" / "runs").iterdir())
            run_id = capture_dir.name
            capture_dir.joinpath("stdout").write_text("not JSON\n", encoding="utf-8")

            report = cli_attempts.reconcile_legacy_failed_attempts(records, database)
            self.assertEqual(report["recovered"], [])
            self.assertEqual(report["terminally_classified"], [{"run_id": run_id, "pr": "42"}])
            self.assertEqual(records.attempt(run_id)["state"], "failed")
            self.assertEqual(records.history(42)["runs"], [])

    def test_sqlite_records_rate_limit_without_taper_result(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / ".git").mkdir()
            database = root / "records.sqlite3"
            SqliteStateStore(database).update(lambda state: state)
            records = SqliteReviewRecords(database)
            records.bootstrap()
            result = run_cli_review(
                target(),
                github=FakeGitHub(),
                source_root=root,
                runner=FakeCommands(
                    root,
                    review_output="provider interrupted\n",
                    review_stderr="Rate limit exceeded; retry later\n",
                    review_returncode=1,
                ),
                records=records,
            )
            attempts = records.attempt_history(result.pull_request)
            self.assertEqual(len(attempts), 1)
            self.assertEqual(attempts[0]["state"], "rate_limited")
            self.assertEqual(records.history(result.pull_request)["runs"], [])

    def test_cli_review_accepts_111_changed_files(self):
        files = [f"src/File{index}.java" for index in range(111)]
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / ".git").mkdir()
            result = run_cli_review(
                target(changed_files=len(files)),
                github=FakeGitHub(files=files),
                source_root=root,
                runner=FakeCommands(root, files=files),
            )

        self.assertEqual(result.published_files, 111)
        self.assertEqual(result.candidate_files, 111)

    def test_git_timeout_is_translated_to_review_runner_error(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / ".git").mkdir()
            with self.assertRaisesRegex(ReviewRunnerError, "git command timed out after 7 seconds"):
                run_cli_review(
                    target(),
                    github=FakeGitHub(),
                    source_root=root,
                    runner=FakeCommands(root, timeout_git=True),
                    git_timeout_seconds=7,
                )

    def test_review_timeout_is_translated_and_partial_capture_is_retained(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / ".git").mkdir()
            commands = FakeCommands(root, timeout_review=True)
            with self.assertRaisesRegex(ReviewRunnerError, "CodeRabbit review timed out after 13 seconds"):
                run_cli_review(
                    target(),
                    github=FakeGitHub(),
                    source_root=root,
                    runner=commands,
                    review_timeout_seconds=13,
                )

            run_dirs = list((root / ".git" / "firemud" / "pr-review" / "runs").glob("run.*"))
            self.assertEqual(len(run_dirs), 1)
            capture_dir = run_dirs[0]
            self.assertEqual((capture_dir / "stdout").read_text(), "partial �\n")
            self.assertEqual((capture_dir / "stderr").read_text(), "timed out\n")
            self.assertEqual((capture_dir / "exit-status").read_text(), "timeout\n")
            metadata = json.loads((capture_dir / "metadata.json").read_text())
            self.assertTrue(metadata["timed_out"])
            self.assertIsNone(metadata["exit_status"])
            self.assertEqual(
                (capture_dir / "review-duration-seconds").read_text(),
                f"{metadata['duration_seconds']}\n",
            )

    def test_timeout_capture_is_complete_before_sqlite_finalization(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / ".git").mkdir()
            database = root / ".git" / "firemud" / "records.sqlite3"
            database.parent.mkdir()
            SqliteStateStore(database).update(lambda state: state)
            records = SqliteReviewRecords(database)
            records.bootstrap()

            with (
                patch.object(records, "finish_attempt", side_effect=ReviewRecordsError("SQLite finish failed")),
                self.assertRaisesRegex(ReviewRunnerError, "CodeRabbit review timed out after 13 seconds"),
            ):
                run_cli_review(
                    target(),
                    github=FakeGitHub(),
                    source_root=root,
                    runner=FakeCommands(root, timeout_review=True),
                    records=records,
                    review_timeout_seconds=13,
                )

            run_dirs = list((root / ".git" / "firemud" / "pr-review" / "runs").glob("run.*"))
            self.assertEqual(len(run_dirs), 1)
            capture_dir = run_dirs[0]
            self.assertEqual((capture_dir / "capture-complete").read_text(), f"{capture_dir.name}\n")
            self.assertTrue(json.loads((capture_dir / "metadata.json").read_text())["timed_out"])
            self.assertEqual(records.attempt(capture_dir.name)["state"], "started")

    def test_nul_path_output_preserves_multiple_paths_and_timeout_configuration(self):
        files = ["src/Representative.java", "src/path with spaces\n.txt"]
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / ".git").mkdir()
            commands = FakeCommands(root, files=files)
            result = run_cli_review(
                target(),
                github=FakeGitHub(files=files),
                source_root=root,
                runner=commands,
                git_timeout_seconds=11,
                review_timeout_seconds=101,
            )
            self.assertEqual(result.published_files, 2)
            self.assertEqual(result.candidate_files, 2)
            path_calls = [call for call in commands.calls if call[0][0] == "git" and "--name-only" in call[0]]
            self.assertEqual(len(path_calls), 2)
            self.assertTrue(all("-z" in call[0] for call in path_calls))
            self.assertTrue(all(timeout == 11 for args, timeout, _text in commands.timeout_calls if args[0] == "git"))
            review_timeouts = [timeout for args, timeout, _text in commands.timeout_calls if args[0] == "coderabbit"]
            self.assertEqual(review_timeouts, [101])

    def test_name_only_diffs_fix_rename_detection_and_disable_configured_drivers(self):
        files = ["src/renamed.txt"]
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / ".git").mkdir()
            commands = FakeCommands(root, files=files)
            result = run_cli_review(
                target(),
                github=FakeGitHub(files=files),
                source_root=root,
                runner=commands,
            )

            self.assertEqual((result.published_files, result.candidate_files), (1, 1))
            path_calls = [call[0][3:] for call in commands.calls if call[0][0] == "git" and "--name-only" in call[0]]
            self.assertEqual(len(path_calls), 2)
            for args in path_calls:
                self.assertEqual(args[:3], ("diff", "--name-only", "-z"))
                self.assertIn("--find-renames=50%", args)
                self.assertIn("-l0", args)
                self.assertIn("--diff-algorithm=myers", args)
                self.assertIn("--no-ext-diff", args)
                self.assertIn("--no-textconv", args)

        self.assertEqual(
            _name_only_diff_args(BASE, HEAD),
            (
                "diff",
                "--name-only",
                "-z",
                "--find-renames=50%",
                "-l0",
                "--diff-algorithm=myers",
                "--no-ext-diff",
                "--no-textconv",
                f"{BASE}...{HEAD}",
            ),
        )

    def test_name_only_preflight_uses_destination_path_without_textconv_driver(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            marker = root / "textconv-invoked"
            driver = root / "textconv-driver"
            driver.write_text(f"#!/bin/sh\nprintf 'normalized\\n'\nprintf 'used\\n' >> {marker}\n")
            driver.chmod(0o755)

            def git(*args):
                return subprocess.run(
                    ["git", "-C", str(root), *args],
                    check=True,
                    capture_output=True,
                    text=True,
                ).stdout.strip()

            git("init", "--quiet")
            git("config", "user.name", "CLI runner test")
            git("config", "user.email", "cli-runner-test@example.invalid")
            git("config", "diff.renames", "false")
            git("config", "diff.fixture.textconv", str(driver))
            (root / "src").mkdir()
            (root / ".gitattributes").write_text("*.txt diff=fixture\n")
            (root / "src" / "original.txt").write_text("unchanged file contents\n")
            git("add", ".")
            git("commit", "--quiet", "-m", "base")
            base_sha = git("rev-parse", "HEAD")

            (root / "src" / "original.txt").rename(root / "src" / "renamed.txt")
            git("add", "-A")
            git("commit", "--quiet", "-m", "rename")
            head_sha = git("rev-parse", "HEAD")

            snapshot = PullRequestSnapshot(
                42,
                "OPEN",
                "develop",
                base_sha,
                head_sha,
                changed_files=1,
            )
            selected = ReviewTarget(snapshot, EffectiveParent("develop", base_sha), repository="owner/repo")
            result = _validate_target(
                selected,
                snapshot,
                ["src/renamed.txt"],
                base_sha,
                head_sha,
                SubprocessRunner(),
                root,
                force=False,
            )

            self.assertEqual(result[1:3], (1, 1))
            self.assertFalse(marker.exists(), "configured textconv driver must not run during path preflight")

    def test_patch_identity_hashes_raw_diff_bytes(self):
        patch_bytes = b"diff --git a/file b/file\n\xff\x80\x00\n"
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / ".git").mkdir()
            result = run_cli_review(
                target(),
                github=FakeGitHub(),
                source_root=root,
                runner=FakeCommands(root, patch_bytes=patch_bytes),
            )
            metadata = json.loads((result.capture_dir / "metadata.json").read_text())
            self.assertEqual(metadata["patch_identity"], hashlib.sha256(patch_bytes).hexdigest())

    def test_wrong_expectation_fails_before_runner(self):
        resolver = Mock()
        resolver.resolve_cli_target.return_value = target()
        with self.assertRaises(WrongTargetError):
            target_from_resolver(resolver, expected_pr=99)
        resolver.resolve_cli_target.assert_called_once_with()

    def test_force_acknowledges_known_candidate_warning_without_provisional_credit(self):
        with self.assertRaises(UnreconciledReviewError):
            run_cli_review(target(reconciled=False), github=FakeGitHub())

        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / ".git").mkdir()
            result = run_cli_review(
                target(reconciled=False),
                github=FakeGitHub(),
                source_root=root,
                runner=FakeCommands(root),
                force=True,
            )
            self.assertFalse(result.provisional)
            self.assertTrue(result.force_acknowledged)
            metadata = json.loads((result.capture_dir / "metadata.json").read_text())
            self.assertFalse(metadata["provisional"])
            self.assertTrue(metadata["force_acknowledged"])

    def test_repeated_forced_cli_results_import_as_counting_runtime_history(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            common = root / ".git"
            common.mkdir()
            fake_github = FakeGitHub()
            selected = target(
                reconciled=False,
                ancestor_links_valid=False,
                parent_ref="current-parent",
                parent_head="d" * 40,
                parent_pr=41,
                candidate_warnings=("stack reconciliation is PARENT_MOVED",),
            )
            results = []
            for _ in range(3):
                commands = FakeCommands(
                    root,
                    parent_is_ancestor=False,
                    merge_base=BASE,
                    review_output=(
                        '{"type":"start","reviewType":"full"}\n'
                        '{"type":"complete","status":"review_completed",'
                        '"findings":0,"reviewedFiles":["src/Representative.java"]}\n'
                    ),
                )
                results.append(
                    run_cli_review(
                        selected,
                        github=fake_github,
                        source_root=root,
                        runner=commands,
                        force=True,
                        reason="acknowledged parent movement",
                    )
                )
            comments = [
                {
                    "databaseId": index + 1,
                    "body": (
                        f"CLI: 0 found / 0 accepted · `{HEAD[:12]}` · 1 files · 1s\n"
                        f"<!-- firemud-cli-run: {result.run_id} -->\n"
                        "<!-- firemud-review-duration-seconds: 1 -->"
                    ),
                    "createdAt": f"2026-09-23T00:0{index}:00Z",
                    "updatedAt": f"2026-09-23T00:0{index}:00Z",
                }
                for index, result in enumerate(results)
            ]
            payload = {
                "data": {
                    "repository": {
                        "pullRequest": {
                            "number": 42,
                            "baseRefName": "develop",
                            "baseRefOid": BASE,
                            "headRefOid": HEAD,
                            "changedFiles": 1,
                            "comments": {"nodes": comments},
                            "reviews": {"nodes": []},
                            "reviewThreads": {"nodes": []},
                        }
                    }
                }
            }
            observer_live = LiveGitHub("owner/repo")
            with (
                patch.object(github, "fetch_pull_request", return_value=payload),
                patch.object(observer_live, "pull_request", return_value=selected.snapshot),
                patch.object(evidence, "git_common_dir", return_value=common),
            ):
                history = list(LiveEvidence("owner/repo", observer_live).history(42, "cli"))

            counting = [row for row in history if row.get("completed") is True]
            self.assertEqual(len(counting), 3)
            self.assertTrue(all(row["attributable"] and row["anchored"] for row in counting))
            self.assertTrue(all(row["provisional"] is False for row in counting))
            self.assertTrue(all(row["parent_identity"] == "develop" for row in counting))
            self.assertTrue(all(row["parent_head"] == BASE for row in counting))
            self.assertTrue(taper_satisfied(Channel.CLI, counting, required=3))
            for result in results:
                metadata = json.loads((result.capture_dir / "metadata.json").read_text())
                self.assertTrue(metadata["force_acknowledged"])
                self.assertEqual(metadata["parent_ref"], "develop")
                self.assertEqual(metadata["parent_sha"], BASE)
                self.assertEqual(metadata["actual_base_ref"], "develop")
                self.assertEqual(metadata["actual_base_sha"], BASE)
                self.assertEqual(metadata["configured_parent_sha"], "d" * 40)
                self.assertEqual(metadata["configured_parent_pr"], 41)
                self.assertIsNone(metadata["parent_pr"])

    def test_force_reason_rejects_long_and_control_text_before_capture(self):
        invalid_reasons = (
            ("x" * 241, "240 characters or fewer"),
            ("line one\nline two", "control characters"),
            ("tab\tseparator", "control characters"),
            ("c1\x85separator", "control characters"),
        )
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for reason, error in invalid_reasons:
                with self.subTest(reason=repr(reason)):
                    commands = FakeCommands(root)
                    with self.assertRaisesRegex(ReviewRunnerError, error):
                        run_cli_review(
                            target(reconciled=False),
                            github=FakeGitHub(),
                            source_root=root,
                            runner=commands,
                            force=True,
                            reason=reason,
                        )
                    self.assertEqual(commands.calls, [])
                    self.assertFalse((root / ".git" / "firemud" / "pr-review" / "runs").exists())

    def test_force_run_allows_known_parent_tip_outside_candidate_history(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / ".git").mkdir()
            commands = FakeCommands(root, parent_is_ancestor=False, merge_base=OLDER_BASE)
            result = run_cli_review(
                target(reconciled=False, merge_base=OLDER_BASE),
                github=FakeGitHub(),
                source_root=root,
                runner=commands,
                force=True,
                reason="parent advanced after reconciliation",
            )

            self.assertEqual(result.merge_base, OLDER_BASE)
            self.assertEqual(result.parent_sha, BASE)
            self.assertFalse(result.provisional)
            self.assertTrue(result.force_acknowledged)
            pinned_ref = f"refs/firemud/pr-review-base/{result.run_id}"
            self.assertTrue(pinned_ref.startswith("refs/firemud/pr-review-base/"))
            self.assertNotIn("refs/heads/", pinned_ref)
            self.assertIn(
                ("git", "-C", str(root), "update-ref", pinned_ref, OLDER_BASE),
                [call[0] for call in commands.calls],
            )
            self.assertIn(
                ("git", "-C", str(root), "merge-base", "--all", PARENT, HEAD),
                [call[0] for call in commands.calls],
            )

    def test_force_reviews_actual_pr_base_when_configured_parent_and_mergeability_are_stale(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / ".git").mkdir()
            commands = FakeCommands(
                root,
                parent_is_ancestor=False,
                merge_base=BASE,
                review_output=(
                    '{"type":"start","reviewType":"full"}\n'
                    '{"type":"complete","status":"review_completed",'
                    '"findings":0,"reviewedFiles":["src/Representative.java"]}\n'
                ),
            )
            live = FakeGitHub()
            live.mergeable = "UNKNOWN"
            selected = target(
                reconciled=False,
                ancestor_links_valid=False,
                parent_ref="current-parent",
                parent_head="d" * 40,
                parent_pr=41,
                candidate_warnings=("stack reconciliation is PARENT_MOVED",),
            )

            result = run_cli_review(
                selected,
                github=live,
                source_root=root,
                runner=commands,
                force=True,
            )

            metadata = json.loads((result.capture_dir / "metadata.json").read_text())
            self.assertEqual(result.exit_status, 0)
            self.assertTrue(result.force_acknowledged)
            self.assertFalse(result.provisional)
            self.assertEqual(metadata["actual_base_ref"], "develop")
            self.assertEqual(metadata["actual_base_sha"], BASE)
            self.assertEqual(metadata["parent_ref"], "develop")
            self.assertEqual(metadata["parent_sha"], BASE)
            self.assertEqual(metadata["configured_parent_ref"], "current-parent")
            self.assertEqual(metadata["configured_parent_sha"], "d" * 40)
            self.assertIsNone(metadata["parent_pr"])
            self.assertEqual(metadata["configured_parent_pr"], 41)
            self.assertEqual(metadata["candidate_warnings"], ["stack reconciliation is PARENT_MOVED"])
            self.assertTrue(metadata["force_acknowledged"])
            self.assertNotIn(
                ("git", "-C", str(root), "merge-base", "--is-ancestor", "d" * 40, HEAD),
                [call[0] for call in commands.calls],
            )

        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / ".git").mkdir()
            with self.assertRaisesRegex(ReviewRunnerError, "does not contain the exact effective parent tip"):
                run_cli_review(
                    target(),
                    github=FakeGitHub(),
                    source_root=root,
                    runner=FakeCommands(root, parent_is_ancestor=False, merge_base=OLDER_BASE),
                )

    def test_direct_default_front_reviews_a_current_composed_base_head_context(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / ".git").mkdir()
            commands = FakeCommands(root, parent_is_ancestor=False, merge_base=OLDER_BASE)

            result = run_cli_review(
                target(default_base_front=True, merge_base=OLDER_BASE),
                github=FakeGitHub(),
                source_root=root,
                runner=commands,
            )

            metadata = json.loads((result.capture_dir / "metadata.json").read_text())
            pinned_ref = f"refs/firemud/pr-review-base/{result.run_id}"
            command_args = [call[0] for call in commands.calls]
            self.assertEqual(result.candidate_sha, HEAD)
            self.assertEqual(metadata["review_context_sha"], CONTEXT)
            self.assertEqual(metadata["review_base_sha"], BASE)
            self.assertIn(("git", "-C", str(root), "update-ref", pinned_ref, BASE), command_args)
            self.assertTrue(
                any(
                    args[:6] == ("git", "-C", str(root), "worktree", "add", "--detach") and args[-1] == CONTEXT
                    for args in command_args
                )
            )

    def test_force_reviews_default_base_diff_without_merge_readiness_proof(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / ".git").mkdir()
            commands = FakeCommands(
                root,
                parent_is_ancestor=False,
                merge_base=BASE,
                merge_conflict=True,
                review_output=(
                    '{"type":"start","reviewType":"full"}\n'
                    '{"type":"complete","status":"review_completed",'
                    '"findings":0,"reviewedFiles":["src/Representative.java"]}\n'
                ),
            )
            selected = dataclasses.replace(
                target(default_base_front=True),
                default_test_merge_tree_sha="",
                candidate_warnings=("current default-base merge proof is missing",),
            )

            result = run_cli_review(
                selected,
                github=FakeGitHub(),
                source_root=root,
                runner=commands,
                force=True,
            )

            metadata = json.loads((result.capture_dir / "metadata.json").read_text())
            command_args = [call[0] for call in commands.calls]
            self.assertEqual(result.exit_status, 0)
            self.assertTrue(result.force_acknowledged)
            self.assertEqual(metadata["actual_base_ref"], "develop")
            self.assertEqual(metadata["actual_base_sha"], BASE)
            self.assertEqual(metadata["parent_sha"], BASE)
            self.assertEqual(metadata["candidate_warnings"], ["current default-base merge proof is missing"])
            self.assertFalse(any("merge-tree" in args for args in command_args))

    def test_force_default_base_local_candidate_records_candidate_merge_base(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / ".git").mkdir()
            commands = FakeCommands(
                root,
                candidate=CANDIDATE,
                parent_is_ancestor=False,
                merge_base=OLDER_BASE,
                merge_bases={(BASE, CANDIDATE): OLDER_BASE, (BASE, HEAD): BASE},
                review_output=(
                    '{"type":"start","reviewType":"full"}\n'
                    '{"type":"complete","status":"review_completed",'
                    '"findings":0,"reviewedFiles":["src/Representative.java"]}\n'
                ),
            )

            result = run_cli_review(
                target(default_base_front=True),
                github=FakeGitHub(),
                source_root=root,
                runner=commands,
                force=True,
            )

            metadata = json.loads((result.capture_dir / "metadata.json").read_text())
            command_args = [call[0] for call in commands.calls]
            self.assertEqual(result.merge_base, OLDER_BASE)
            self.assertEqual(metadata["merge_base"], OLDER_BASE)
            self.assertEqual(metadata["review_base_sha"], OLDER_BASE)
            self.assertEqual(metadata["published_merge_base"], BASE)
            self.assertIn(
                ("git", "-C", str(root), "merge-base", "--all", BASE, CANDIDATE),
                command_args,
            )
            self.assertIn(
                ("git", "-C", str(root), "merge-base", "--all", BASE, HEAD),
                command_args,
            )

    def test_direct_default_front_rejects_a_published_tree_that_differs_from_selected_proof(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / ".git").mkdir()
            commands = FakeCommands(root, parent_is_ancestor=False, merge_base=OLDER_BASE, merge_tree=ADVANCED)

            with self.assertRaisesRegex(ReviewRunnerError, "differs from the selected base/head proof"):
                run_cli_review(
                    target(default_base_front=True, merge_base=OLDER_BASE),
                    github=FakeGitHub(),
                    source_root=root,
                    runner=commands,
                )

            self.assertFalse(any(args and args[0] == "coderabbit" for args, _ in commands.calls))

    def test_closed_default_front_target_is_not_classified_as_stale_base_advance(self):
        selected = target(default_base_front=True)
        closed_advanced = dataclasses.replace(
            selected.snapshot,
            state="CLOSED",
            base_sha=ADVANCED,
        )

        class ClosedPullRequest:
            def pull_request(self, _number):
                return closed_advanced

            def branch_head(self, _ref_name):
                return ADVANCED

        with self.assertRaisesRegex(ReviewRunnerError, "pull request changed during CLI preflight"):
            _verify_target_still_current(selected, ClosedPullRequest())

    def test_cli_test_merge_commit_works_on_installed_git_without_changing_refs_or_checkout(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory) / "repository"
            root.mkdir()
            base, head, expected_tree = _commit_tree_case(root)
            refs_before = _git(root, "for-each-ref", "--format=%(refname) %(objectname)")
            status_before = _git(root, "status", "--porcelain", "--untracked-files=all")
            worktrees_before = _git(root, "worktree", "list", "--porcelain")

            merge_commit = _test_merge_commit(SubprocessRunner(), root, base, head)

            self.assertEqual(_git(root, "show", "-s", "--format=%T", merge_commit), expected_tree)
            self.assertEqual(_git(root, "show", "-s", "--format=%P", merge_commit), f"{base} {head}")
            self.assertEqual(_git(root, "for-each-ref", "--format=%(refname) %(objectname)"), refs_before)
            self.assertEqual(_git(root, "status", "--porcelain", "--untracked-files=all"), status_before)
            self.assertEqual(_git(root, "worktree", "list", "--porcelain"), worktrees_before)

    def test_cli_test_merge_commit_rejects_real_conflict_and_cleans_worktree(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory) / "repository"
            root.mkdir()
            base, head, _ = _commit_tree_case(root, conflict=True)
            worktrees_before = _git(root, "worktree", "list", "--porcelain")

            with self.assertRaisesRegex(ReviewRunnerError, "do not produce a clean test merge"):
                _test_merge_commit(SubprocessRunner(), root, base, head)

            self.assertEqual(_git(root, "worktree", "list", "--porcelain"), worktrees_before)

    def test_direct_default_front_requires_the_selected_test_merge_proof(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / ".git").mkdir()
            commands = FakeCommands(root)
            selected = dataclasses.replace(
                target(default_base_front=True),
                default_test_merge_tree_sha="",
            )

            with self.assertRaisesRegex(ReviewRunnerError, "no verified current base/head test merge"):
                run_cli_review(
                    selected,
                    github=FakeGitHub(),
                    source_root=root,
                    runner=commands,
                )

            self.assertFalse(any(args and args[0] == "coderabbit" for args, _ in commands.calls))

    def test_direct_default_front_rejects_a_base_move_before_starting_the_cli_process(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / ".git").mkdir()
            github = FakeGitHub()
            advanced = PullRequestSnapshot(
                42,
                "OPEN",
                "develop",
                ADVANCED,
                HEAD,
                changed_files=1,
                mergeable="MERGEABLE",
            )
            original_pull = github.pull_request
            snapshots = iter((original_pull(42), advanced))
            github.pull_request = lambda _number: next(snapshots)
            parent_tips = iter((PARENT, ADVANCED))
            github.branch_head = lambda _ref: next(parent_tips)
            commands = FakeCommands(root, parent_is_ancestor=False)

            with self.assertRaisesRegex(StaleReviewTargetError, "base advanced during CLI preflight"):
                run_cli_review(
                    target(default_base_front=True),
                    github=github,
                    source_root=root,
                    runner=commands,
                )

            self.assertFalse(any(args and args[0] == "coderabbit" for args, _ in commands.calls))

    def test_successful_capture_contains_duration_artifact_and_loads(self):
        complete = {
            "type": "complete",
            "status": "review_completed",
            "findings": 0,
            "reviewedFiles": ["src/Representative.java"],
        }
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / ".git").mkdir()
            commands = FakeCommands(root, review_output=json.dumps(complete) + "\n")
            clock_values = iter((0, 1_250_000_000))
            result = run_cli_review(
                target(),
                github=FakeGitHub(),
                source_root=root,
                runner=commands,
                monotonic_ns=lambda: next(clock_values),
            )

            duration_artifact = result.capture_dir / "review-duration-seconds"
            self.assertEqual(duration_artifact.read_text(encoding="utf-8"), "2\n")
            checkpoint = evidence.Checkpoint(
                comment_id=None,
                created_at="",
                type="CLI",
                raw_found=0,
                accepted=0,
                reviewed_sha=result.candidate_sha[:12],
                file_count=result.candidate_files,
                correction=False,
                updated_at=None,
                run_id=result.run_id,
                hosted_review_id=None,
                duration_seconds=result.duration_seconds,
            )
            capture = evidence.load_cli_capture(checkpoint, "owner/repo", 42, root / ".git")
            self.assertEqual(capture.metadata["review_duration_seconds"], "2")

    def test_human_stop_preserves_running_cli_capture_and_rejects_next_admission(self):
        import test_pr_review_controller as controller_fixtures
        from pr_review.controller import ControllerError, ReviewController

        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            common = root / ".git"
            common.mkdir()
            controller = ReviewController(
                store=SqliteStateStore(common / "firemud" / "pr-review-stack.sqlite3"),
                repository="owner/repo",
                github=controller_fixtures.FakeGitHub({42: controller_fixtures.pr(42, HEAD)}),
                git=controller_fixtures.FakeGit({"feature-42": HEAD}),
                evidence={},
            )
            controller.set_stack([42])
            started, finish = threading.Event(), threading.Event()
            commands = FakeCommands(
                root,
                review_started=started,
                allow_review_finish=finish,
                review_output="review output retained after human stop\n",
            )
            results, errors = [], []

            def run():
                try:
                    results.append(
                        run_cli_review(
                            target(),
                            github=FakeGitHub(),
                            source_root=root,
                            runner=commands,
                            admit=lambda reserve: controller._admit_review(42, "cli", reserve),
                        )
                    )
                except (ControllerError, ReviewRunnerError, OSError) as error:
                    errors.append(error)

            thread = threading.Thread(target=run)
            thread.start()
            try:
                self.assertTrue(started.wait(timeout=3))
                stopped = controller.decide_stop(pr=42, channel="cli", reason="human overrides unfinished taper")
                self.assertIsNone(stopped["reviewed_head"])
                self.assertTrue(thread.is_alive())
                with self.assertRaisesRegex(ReviewRunnerError, "already running"):
                    run_cli_review(target(), github=FakeGitHub(), source_root=root, runner=commands)
            finally:
                finish.set()
                thread.join(timeout=3)
            self.assertFalse(thread.is_alive())
            self.assertEqual(errors, [])
            self.assertEqual(len(results), 1)
            self.assertEqual((results[0].capture_dir / "exit-status").read_text().strip(), "0")
            self.assertTrue((results[0].capture_dir / "capture-complete").exists())
            self.assertIn("retained after human stop", (results[0].capture_dir / "stdout").read_text())
            self.assertEqual(controller.store.load().allocations["42:cli"].stop_basis, "direct_human")
            with self.assertRaisesRegex(ControllerError, "discovery is stopped"):
                run_cli_review(
                    target(),
                    github=FakeGitHub(),
                    source_root=root,
                    runner=commands,
                    admit=lambda reserve: controller._admit_review(42, "cli", reserve),
                )
            self.assertEqual(sum(call[0][0] == "coderabbit" for call in commands.calls), 1)

    def test_repository_lock_rejects_a_concurrent_cli_process(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / ".git").mkdir()
            review_started = threading.Event()
            allow_review_finish = threading.Event()
            commands = FakeCommands(
                root,
                review_started=review_started,
                allow_review_finish=allow_review_finish,
            )
            results = []
            errors = []

            def run():
                try:
                    results.append(run_cli_review(target(), github=FakeGitHub(), source_root=root, runner=commands))
                except ReviewRunnerError as error:
                    errors.append(str(error))

            first = threading.Thread(target=run)
            second = threading.Thread(target=run)
            first.start()
            self.assertTrue(review_started.wait(timeout=3))
            second.start()
            second.join(timeout=3)
            self.assertFalse(second.is_alive())
            allow_review_finish.set()
            first.join(timeout=3)
            self.assertFalse(first.is_alive())
            self.assertEqual(len(results), 1)
            self.assertEqual(len(errors), 1)
            self.assertIn("already running", errors[0])
            self.assertFalse(commands.overlap)

    def test_cli_lock_owner_marker_matches_active_provider_and_replaces_stale_value(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / ".git").mkdir()
            lock_path = root / ".git" / "firemud" / "pr-review" / "cli.lock"
            lock_path.parent.mkdir(parents=True)
            lock_path.write_text(f"run_id=run.{'0' * 32}\n", encoding="utf-8")
            commands = FakeCommands(root)
            observed_markers = []
            original_run = commands.run

            def observe_provider(args, **kwargs):
                if args[0] == "coderabbit":
                    observed_markers.append(lock_path.read_text(encoding="utf-8"))
                return original_run(args, **kwargs)

            commands.run = observe_provider
            result = run_cli_review(
                target(),
                github=FakeGitHub(),
                source_root=root,
                runner=commands,
            )

            self.assertEqual(observed_markers, [f"run_id={result.run_id}\n"])
            self.assertEqual(lock_path.read_text(encoding="utf-8"), "")

    def test_cli_lock_owner_marker_clears_after_failed_preflight(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / ".git").mkdir()
            lock_path = root / ".git" / "firemud" / "pr-review" / "cli.lock"
            lock_path.parent.mkdir(parents=True)
            lock_path.write_text(f"run_id=run.{'1' * 32}\n", encoding="utf-8")
            github = FakeGitHub()
            github.mergeable = "CONFLICTING"
            observed_markers = []
            original_pull_request = github.pull_request

            def observe_preflight(number):
                observed_markers.append(lock_path.read_text(encoding="utf-8"))
                return original_pull_request(number)

            github.pull_request = observe_preflight
            commands = FakeCommands(root)
            with self.assertRaisesRegex(ReviewRunnerError, "not mergeable"):
                run_cli_review(target(), github=github, source_root=root, runner=commands)

            self.assertTrue(observed_markers)
            self.assertTrue(
                all(
                    marker.startswith("run_id=run.")
                    and len(marker) == len("run_id=run.") + 32 + 1
                    and marker.endswith("\n")
                    for marker in observed_markers
                )
            )
            self.assertEqual(len(set(observed_markers)), 1)
            self.assertEqual(lock_path.read_text(encoding="utf-8"), "")
            self.assertFalse(any(call[0][0] == "coderabbit" for call in commands.calls))

    def test_cli_owner_cleanup_failure_preserves_success_and_releases_execution_lock(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / ".git").mkdir()
            commands = FakeCommands(root)
            lock_path = root / ".git" / "firemud" / "pr-review" / "cli.lock"

            with patch("pr_review.cli_runner._clear_cli_lock_owner", side_effect=OSError("disk unavailable")):
                result = run_cli_review(target(), github=FakeGitHub(), source_root=root, runner=commands)

            self.assertTrue(any(call[0][0] == "coderabbit" for call in commands.calls))
            self.assertEqual(result.pull_request, 42)
            with lock_path.open("r+") as lock_handle:
                fcntl.flock(lock_handle.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
                fcntl.flock(lock_handle.fileno(), fcntl.LOCK_UN)

    def test_cli_owner_cleanup_failure_preserves_primary_error_and_releases_execution_lock(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / ".git").mkdir()
            github = FakeGitHub()
            github.mergeable = "CONFLICTING"
            commands = FakeCommands(root)
            lock_path = root / ".git" / "firemud" / "pr-review" / "cli.lock"

            with (
                patch("pr_review.cli_runner._clear_cli_lock_owner", side_effect=OSError("disk unavailable")),
                self.assertRaisesRegex(ReviewRunnerError, "not mergeable"),
            ):
                run_cli_review(target(), github=github, source_root=root, runner=commands)

            with lock_path.open("r+") as lock_handle:
                fcntl.flock(lock_handle.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
                fcntl.flock(lock_handle.fileno(), fcntl.LOCK_UN)
            self.assertFalse(any(call[0][0] == "coderabbit" for call in commands.calls))

    def test_cli_lock_owner_marker_write_failure_prevents_preflight_and_provider(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / ".git").mkdir()
            github = FakeGitHub()
            commands = FakeCommands(root)
            lock_path = root / ".git" / "firemud" / "pr-review" / "cli.lock"
            with (
                patch("pr_review.cli_runner._write_cli_lock_owner", side_effect=OSError("disk unavailable")),
                patch.object(github, "pull_request", wraps=github.pull_request) as pull_request,
                self.assertRaisesRegex(ReviewRunnerError, "could not persist active CLI run owner"),
            ):
                run_cli_review(target(), github=github, source_root=root, runner=commands)

            pull_request.assert_not_called()
            self.assertFalse(any(call[0][0] == "coderabbit" for call in commands.calls))
            self.assertEqual(lock_path.read_text(encoding="utf-8"), "")

    def test_cli_holds_hosted_lock_during_preflight_and_releases_it_for_provider(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            common_dir = root / ".git"
            common_dir.mkdir()
            preflight_started = threading.Event()
            allow_preflight_finish = threading.Event()
            review_started = threading.Event()
            allow_review_finish = threading.Event()
            commands = FakeCommands(
                root,
                review_started=review_started,
                allow_review_finish=allow_review_finish,
            )
            github = FakeGitHub()
            pull_request = github.pull_request

            def gated_pull_request(number):
                preflight_started.set()
                if not allow_preflight_finish.wait(timeout=3):
                    raise RuntimeError("test did not release CLI preflight")
                return pull_request(number)

            github.pull_request = gated_pull_request
            results = []
            errors = []

            def run():
                try:
                    results.append(run_cli_review(target(), github=github, source_root=root, runner=commands))
                except (ReviewRunnerError, RuntimeError) as error:
                    errors.append(error)

            thread = threading.Thread(target=run)
            thread.start()
            self.assertTrue(preflight_started.wait(timeout=3))
            request_lock = common_dir / "firemud" / "hosted" / "owner_repo" / "pr-42" / "request.lock"
            with request_lock.open("a+") as lock_handle, self.assertRaises(BlockingIOError):
                fcntl.flock(lock_handle.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)

            allow_preflight_finish.set()
            self.assertTrue(review_started.wait(timeout=3))
            capture_dirs = list((common_dir / "firemud" / "pr-review" / "runs").iterdir())
            self.assertEqual(len(capture_dirs), 1)
            capture_metadata = json.loads((capture_dirs[0] / "metadata.json").read_text(encoding="utf-8"))
            self.assertEqual(capture_metadata["candidate_sha"], HEAD)
            with request_lock.open("a+") as lock_handle:
                fcntl.flock(lock_handle.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
                # A Hosted request can take the per-PR lock while the CLI provider
                # is still running; the repository-wide CLI lock remains occupied.
                self.assertTrue(thread.is_alive())
                fcntl.flock(lock_handle.fileno(), fcntl.LOCK_UN)
            allow_review_finish.set()
            thread.join(timeout=3)
            self.assertFalse(thread.is_alive())
            self.assertEqual(errors, [])
            self.assertEqual(len(results), 1)

    def test_attributable_active_hosted_review_allows_cli_on_same_published_head(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            common_dir = root / ".git"
            common_dir.mkdir()
            selected = target(merge_base=PARENT, patch_identity=cli_anchor()["patch_id"])
            record_path = write_hosted_trigger(common_dir, anchor=cli_anchor())
            original_record = json.loads(record_path.read_text())
            commands = FakeCommands(root)
            with patch(
                "pr_review.cli_runner.github_api.fetch_pull_request",
                return_value=hosted_payload("Full review triggered"),
            ):
                run_cli_review(selected, github=FakeGitHub(), source_root=root, runner=commands)
            self.assertTrue(any(call[0][0] == "coderabbit" for call in commands.calls))
            self.assertEqual(json.loads(record_path.read_text()), original_record)

    def test_active_hosted_review_selects_published_head_for_local_ahead_candidate(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            common_dir = root / ".git"
            common_dir.mkdir()
            write_hosted_trigger(common_dir, anchor=cli_anchor())
            commands = FakeCommands(root, candidate=CANDIDATE)
            with (
                patch(
                    "pr_review.cli_runner.github_api.fetch_pull_request",
                    return_value=hosted_payload("Full review triggered"),
                ),
            ):
                result = run_cli_review(
                    target(merge_base=PARENT, patch_identity=cli_anchor()["patch_id"]),
                    github=FakeGitHub(),
                    source_root=root,
                    runner=commands,
                )
            metadata = json.loads((result.capture_dir / "metadata.json").read_text())
            self.assertEqual(metadata["candidate_sha"], HEAD)
            self.assertEqual(metadata["published_head_sha"], HEAD)
            self.assertEqual(metadata["published_status"], "published-head")
            self.assertTrue(any(call[0][0] == "coderabbit" for call in commands.calls))

    def test_active_hosted_review_same_head_with_changed_anchor_holds_cli(self):
        target_anchor = cli_anchor()
        selected = target(merge_base=PARENT, patch_identity=target_anchor["patch_id"])
        cases = {
            "missing anchor": None,
            "parent identity": cli_anchor(parent_identity="17"),
            "parent head": cli_anchor(parent_head=OLDER_BASE),
            "merge base": cli_anchor(merge_base=OLDER_BASE),
            "patch identity": cli_anchor(patch_id="0" * 64),
        }
        for label, hosted_anchor in cases.items():
            with self.subTest(anchor=label), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                common_dir = root / ".git"
                common_dir.mkdir()
                write_hosted_trigger(common_dir, anchor=hosted_anchor)
                commands = FakeCommands(root)
                with (
                    patch(
                        "pr_review.cli_runner.github_api.fetch_pull_request",
                        return_value=hosted_payload("Full review triggered"),
                    ),
                    self.assertRaisesRegex(
                        ReviewRunnerError,
                        rf"{HOSTED_CLI_OVERLAP_HOLD_REASON}; reservation_state=active_unverified; "
                        rf"reservation_count=1; candidate_sha={HEAD}; reservation_sha={HEAD}",
                    ),
                ):
                    run_cli_review(selected, github=FakeGitHub(), source_root=root, runner=commands)
                self.assertFalse(any(call[0][0] == "coderabbit" for call in commands.calls))

    def test_awaiting_hosted_reservation_blocks_cli_and_preserves_record(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            common_dir = root / ".git"
            common_dir.mkdir()
            record_path = write_hosted_trigger(common_dir)
            original_record = json.loads(record_path.read_text())
            commands = FakeCommands(root)
            with (
                patch(
                    "pr_review.cli_runner.github_api.fetch_pull_request",
                    return_value=hosted_payload(include_response=False),
                ),
                self.assertRaisesRegex(
                    ReviewRunnerError,
                    rf"{HOSTED_CLI_OVERLAP_HOLD_REASON}; reservation_state=awaiting_response; reservation_count=1",
                ),
            ):
                run_cli_review(target(), github=FakeGitHub(), source_root=root, runner=commands)
            self.assertFalse(any(call[0][0] == "coderabbit" for call in commands.calls))
            self.assertEqual(json.loads(record_path.read_text()), original_record)

    def test_terminal_ambiguous_hosted_response_allows_cli_and_preserves_record(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            common_dir = root / ".git"
            common_dir.mkdir()
            record_path = write_hosted_trigger(common_dir)
            original_record = json.loads(record_path.read_text())
            commands = FakeCommands(root)
            with patch(
                "pr_review.cli_runner.github_api.fetch_pull_request",
                return_value=hosted_payload("Full review finished."),
            ):
                run_cli_review(target(), github=FakeGitHub(), source_root=root, runner=commands)
            self.assertTrue(any(call[0][0] == "coderabbit" for call in commands.calls))
            self.assertEqual(json.loads(record_path.read_text()), original_record)

    def test_terminal_provider_file_ceiling_skip_allows_cli_on_same_published_head(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            common_dir = root / ".git"
            common_dir.mkdir()
            record_path = write_hosted_trigger(common_dir, anchor=cli_anchor())
            original_record = json.loads(record_path.read_text())
            files = [f"src/provider-ceiling-{index:03}.java" for index in range(121)]
            commands = FakeCommands(root, files=files)
            body = (
                "<!-- This is an auto-generated reply by CodeRabbit -->\n"
                "<!-- CodeRabbit review command invocation: v2:provider-id -->\n"
                "<details><summary>⚠️ Action not completed</summary>\n\n"
                "Review skipped: 121 files exceed the limit of 100.\n\n</details>"
            )
            with patch(
                "pr_review.cli_runner.github_api.fetch_pull_request",
                return_value=hosted_payload(body),
            ):
                run_cli_review(
                    target(
                        merge_base=PARENT,
                        patch_identity=cli_anchor()["patch_id"],
                        changed_files=len(files),
                    ),
                    github=FakeGitHub(files=files),
                    source_root=root,
                    runner=commands,
                )
            self.assertTrue(any(call[0][0] == "coderabbit" for call in commands.calls))
            self.assertEqual(json.loads(record_path.read_text()), original_record)

    def test_historical_terminal_ambiguity_allows_cli_on_a_later_published_head(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            common_dir = root / ".git"
            common_dir.mkdir()
            record_path = write_hosted_trigger(common_dir, head_sha=OLDER_BASE)
            original_record = json.loads(record_path.read_text())
            commands = FakeCommands(root)
            with patch(
                "pr_review.cli_runner.github_api.fetch_pull_request",
                return_value=hosted_payload("Full review finished."),
            ):
                run_cli_review(target(), github=FakeGitHub(), source_root=root, runner=commands)
            self.assertTrue(any(call[0][0] == "coderabbit" for call in commands.calls))
            self.assertEqual(json.loads(record_path.read_text()), original_record)

    def test_ambiguous_unattributed_and_timed_out_hosted_reservations_block_cli(self):
        cases = ("ambiguous", "unattributed", "timed_out")
        for state in cases:
            with self.subTest(state=state), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                common_dir = root / ".git"
                common_dir.mkdir()
                record_path = write_hosted_trigger(common_dir)
                if state == "timed_out":
                    timed_out_record = json.loads(record_path.read_text())
                    timed_out_record["status"] = "timed_out"
                    record_path.write_text(json.dumps(timed_out_record))
                original_record = json.loads(record_path.read_text())
                payload = hosted_payload(include_response=False)
                comments = payload["data"]["repository"]["pullRequest"]["comments"]["nodes"]
                if state == "unattributed":
                    comments.clear()
                elif state == "ambiguous":
                    comments.append(
                        {
                            "databaseId": 102,
                            "author": {"login": "another-maintainer"},
                            "body": hosted.FULL_COMMAND,
                            "createdAt": "2026-01-01T00:02:00Z",
                            "url": "https://example.test/later-trigger",
                        }
                    )
                commands = FakeCommands(root)
                with (
                    patch("pr_review.cli_runner.github_api.fetch_pull_request", return_value=payload),
                    self.assertRaisesRegex(
                        ReviewRunnerError,
                        rf"{HOSTED_CLI_OVERLAP_HOLD_REASON}; reservation_state={state}; reservation_count=1",
                    ),
                ):
                    run_cli_review(target(), github=FakeGitHub(), source_root=root, runner=commands)
                self.assertFalse(any(call[0][0] == "coderabbit" for call in commands.calls))
                self.assertEqual(json.loads(record_path.read_text()), original_record)

    def test_malformed_hosted_reservation_blocks_cli(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            common_dir = root / ".git"
            common_dir.mkdir()
            record_path = write_hosted_trigger(common_dir)
            original_record = json.loads(record_path.read_text())
            malformed_record = dict(original_record)
            malformed_record["trigger"] = {**original_record["trigger"], "type": "not-full"}
            record_path.write_text(json.dumps(malformed_record))
            commands = FakeCommands(root)
            with (
                patch("pr_review.cli_runner.github_api.fetch_pull_request", return_value=hosted_payload()),
                self.assertRaisesRegex(ReviewRunnerError, "current Hosted reservation cannot be safely classified"),
            ):
                run_cli_review(target(), github=FakeGitHub(), source_root=root, runner=commands)
            self.assertFalse(any(call[0][0] == "coderabbit" for call in commands.calls))
            self.assertEqual(json.loads(record_path.read_text()), malformed_record)

    def test_multiple_current_hosted_reservations_block_cli_before_lookup(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            common_dir = root / ".git"
            common_dir.mkdir()
            write_hosted_trigger(common_dir)
            write_hosted_trigger(common_dir, legacy=True)
            commands = FakeCommands(root)
            with (
                patch("pr_review.cli_runner.github_api.fetch_pull_request") as fetch,
                self.assertRaisesRegex(
                    ReviewRunnerError,
                    rf"{HOSTED_CLI_OVERLAP_HOLD_REASON}; reservation_state=multiple_current_reservations; "
                    rf"reservation_count=2; candidate_sha={HEAD}; reservation_sha={HEAD},{HEAD}",
                ),
            ):
                run_cli_review(target(), github=FakeGitHub(), source_root=root, runner=commands)
            fetch.assert_not_called()
            self.assertFalse(any(call[0][0] == "coderabbit" for call in commands.calls))

    def test_unknown_hosted_reservation_state_holds_cli_with_classified_details(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            common_dir = root / ".git"
            common_dir.mkdir()
            write_hosted_trigger(common_dir)
            commands = FakeCommands(root)
            with (
                patch(
                    "pr_review.cli_runner.github_api.fetch_pull_request",
                    return_value=hosted_payload(),
                ),
                patch(
                    "pr_review.cli_runner.hosted.trigger_state",
                    return_value=Mock(state="future_state", head_sha=HEAD),
                ),
                self.assertRaisesRegex(
                    ReviewRunnerError,
                    rf"{HOSTED_CLI_OVERLAP_HOLD_REASON}; reservation_state=future_state; reservation_count=1; "
                    rf"candidate_sha={HEAD}; reservation_sha={HEAD}",
                ),
            ):
                run_cli_review(target(), github=FakeGitHub(), source_root=root, runner=commands)
            self.assertFalse(any(call[0][0] == "coderabbit" for call in commands.calls))

    def test_active_hosted_review_with_mismatched_reservation_head_reports_both_shas(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            common_dir = root / ".git"
            common_dir.mkdir()
            write_hosted_trigger(common_dir, head_sha=ADVANCED, anchor=cli_anchor())
            commands = FakeCommands(root, candidate=CANDIDATE)
            with (
                patch(
                    "pr_review.cli_runner.github_api.fetch_pull_request",
                    return_value=hosted_payload("Full review triggered"),
                ),
                self.assertRaisesRegex(
                    ReviewRunnerError,
                    rf"{HOSTED_CLI_OVERLAP_HOLD_REASON}; reservation_state=active_unverified; reservation_count=1; "
                    rf"candidate_sha={CANDIDATE}; reservation_sha={ADVANCED}",
                ),
            ):
                run_cli_review(
                    target(merge_base=PARENT, patch_identity=cli_anchor()["patch_id"]),
                    github=FakeGitHub(),
                    source_root=root,
                    runner=commands,
                )
            self.assertFalse(any(call[0][0] == "coderabbit" for call in commands.calls))

    def test_hosted_posting_lock_blocks_cli_before_live_lookup(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            common_dir = root / ".git"
            common_dir.mkdir()
            request_lock = common_dir / "firemud" / "hosted" / "owner_repo" / "pr-42" / "request.lock"
            request_lock.parent.mkdir(parents=True)
            commands = FakeCommands(root)
            with request_lock.open("a+") as lock_handle:
                fcntl.flock(lock_handle.fileno(), fcntl.LOCK_EX)
                with (
                    patch("pr_review.cli_runner.github_api.fetch_pull_request") as fetch,
                    self.assertRaisesRegex(ReviewRunnerError, "another review request is active for PR #42"),
                ):
                    run_cli_review(target(), github=FakeGitHub(), source_root=root, runner=commands)
                fetch.assert_not_called()
                self.assertFalse(any(call[0][0] == "coderabbit" for call in commands.calls))
                fcntl.flock(lock_handle.fileno(), fcntl.LOCK_UN)

    def test_terminal_hosted_cooldown_with_or_without_reset_does_not_block_cli(self):
        for response in (
            hosted.REVIEW_LIMIT_MARKER,
            f"{hosted.REVIEW_LIMIT_MARKER}\nMore included reviews available in 30 minutes",
        ):
            with self.subTest(response=response), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                common_dir = root / ".git"
                common_dir.mkdir()
                write_hosted_trigger(common_dir)
                commands = FakeCommands(root)
                with patch(
                    "pr_review.cli_runner.github_api.fetch_pull_request",
                    return_value=hosted_payload(response),
                ):
                    run_cli_review(target(), github=FakeGitHub(), source_root=root, runner=commands)
                self.assertTrue(any(call[0][0] == "coderabbit" for call in commands.calls))

    def test_durable_posting_reservation_blocks_cli_without_trigger_identity(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            common_dir = root / ".git"
            common_dir.mkdir()
            write_hosted_trigger(common_dir, status="posting")
            commands = FakeCommands(root)
            with (
                patch("pr_review.cli_runner.github_api.fetch_pull_request", return_value=hosted_payload()),
                self.assertRaisesRegex(ReviewRunnerError, HOSTED_CLI_OVERLAP_HOLD_REASON),
            ):
                run_cli_review(target(), github=FakeGitHub(), source_root=root, runner=commands)
            self.assertFalse(any(call[0][0] == "coderabbit" for call in commands.calls))

    def test_compact_and_json_render_the_same_result(self):
        result = {"run_id": "cli-42", "provisional": False, "exit_status": 0}
        compact = _render(result)
        structured = json.loads(_render(result, as_json=True))
        self.assertEqual(structured, result)
        self.assertIn("run_id=cli-42", compact)
        self.assertIn("provisional=False", compact)
        self.assertIn("exit_status=0", compact)

    def test_unpublished_candidate_anchor_uses_reviewed_head_and_patch(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / ".git").mkdir()
            result = run_cli_review(
                target(),
                github=FakeGitHub(),
                source_root=root,
                runner=FakeCommands(root, candidate=CANDIDATE),
            )
            metadata = json.loads((result.capture_dir / "metadata.json").read_text())
            self.assertEqual(metadata["candidate_sha"], CANDIDATE)
            self.assertEqual(metadata["child_head_sha"], CANDIDATE)
            self.assertEqual(metadata["published_head_sha"], HEAD)
            self.assertEqual(len(metadata["patch_identity"]), 64)

    def test_temp_root_failure_removes_pinned_ref(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / ".git").mkdir()
            commands = FakeCommands(root)
            with (
                patch(
                    "pr_review.cli_runner.tempfile.mkdtemp",
                    side_effect=OSError("temporary root unavailable"),
                ),
                self.assertRaisesRegex(OSError, "temporary root unavailable"),
            ):
                run_cli_review(target(), github=FakeGitHub(), source_root=root, runner=commands)

            update_ref_calls = [
                call[0]
                for call in commands.calls
                if call[0][:3] == ("git", "-C", str(root)) and "update-ref" in call[0]
            ]
            self.assertEqual(len(update_ref_calls), 2)
            self.assertEqual(update_ref_calls[0][3], "update-ref")
            self.assertEqual(update_ref_calls[0][5], PARENT)
            self.assertEqual(update_ref_calls[1][3:5], ("update-ref", "-d"))
            self.assertEqual(update_ref_calls[1][5], update_ref_calls[0][4])

    def test_final_preflight_rejects_conflict_or_missing_base_before_review(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / ".git").mkdir()
            github = FakeGitHub()
            github.mergeable = "CONFLICTING"
            commands = FakeCommands(root)
            with self.assertRaisesRegex(ReviewRunnerError, "not mergeable"):
                run_cli_review(target(), github=github, source_root=root, runner=commands)
            self.assertFalse(any(call[0][0] == "coderabbit" for call in commands.calls))

            github.mergeable = "MERGEABLE"
            github.base_exists = False
            commands = FakeCommands(root)
            with self.assertRaisesRegex(ReviewRunnerError, "base branch"):
                run_cli_review(target(), github=github, source_root=root, runner=commands)
            self.assertFalse(any(call[0][0] == "coderabbit" for call in commands.calls))


if __name__ == "__main__":
    unittest.main()
