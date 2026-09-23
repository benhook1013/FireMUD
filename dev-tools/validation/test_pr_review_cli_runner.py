import fcntl
import json
import sys
import tempfile
import threading
import time
import unittest
from pathlib import Path
from subprocess import CompletedProcess
from unittest.mock import Mock, patch

DEV_TOOLS = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(DEV_TOOLS))

from pr_review import hosted
from pr_review.cli import _render
from pr_review.cli_runner import (
    EffectiveParent,
    PullRequestSnapshot,
    ReviewRunnerError,
    ReviewTarget,
    UnreconciledReviewError,
    WrongTargetError,
    run_cli_review,
    target_from_resolver,
)

BASE = "a" * 40
PARENT = BASE
HEAD = "c" * 40
CANDIDATE = "d" * 40


class FakeGitHub:
    mergeable = "MERGEABLE"
    base_exists = True

    def pull_request(self, number):
        return PullRequestSnapshot(
            number,
            "OPEN",
            "develop",
            BASE,
            HEAD,
            changed_files=1,
            mergeable=self.mergeable,
            base_exists=self.base_exists,
        )

    def pull_request_files(self, number):
        return ["src/Representative.java"]

    def branch_head(self, ref_name):
        return PARENT


class FakeCommands:
    def __init__(self, root, delay=0, candidate=HEAD, review_started=None, allow_review_finish=None):
        self.root = root
        self.delay = delay
        self.review_started = review_started
        self.allow_review_finish = allow_review_finish
        self.active = 0
        self.overlap = False
        self.calls = []
        self.guard = threading.Lock()
        self.candidate = candidate

    def run(self, args, *, cwd=None, capture_output=False, check=True):
        self.calls.append((tuple(args), cwd))
        if args[0] == "coderabbit":
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
            return CompletedProcess(args, 0, "review output\n", "")
        if args[:3] == ["git", "-C", str(self.root)]:
            git_args = args[3:]
            if git_args == ["rev-parse", "--git-common-dir"]:
                return CompletedProcess(args, 0, ".git\n", "")
            if git_args == ["rev-parse", "HEAD^{commit}"]:
                return CompletedProcess(args, 0, f"{self.candidate}\n", "")
            if git_args == ["diff", "--name-only", f"{BASE}...{HEAD}"]:
                return CompletedProcess(args, 0, "src/Representative.java\0", "")
            if git_args == ["diff", "--name-only", f"{PARENT}...{HEAD}"]:
                return CompletedProcess(args, 0, "src/Representative.java\0", "")
            if git_args == ["diff", "--name-only", f"{PARENT}...{self.candidate}"]:
                return CompletedProcess(args, 0, "src/Representative.java\0", "")
            if git_args == ["diff", "--binary", "--full-index", f"{PARENT}...{self.candidate}"]:
                return CompletedProcess(args, 0, f"candidate patch {self.candidate}\n", "")
            if git_args == ["rev-list", "--count", f"{HEAD}..{self.candidate}"]:
                return CompletedProcess(args, 0, "1\n", "")
            if git_args[:3] == ["merge-base", "--all", PARENT]:
                return CompletedProcess(args, 0, f"{PARENT}\n", "")
            if git_args[:3] == ["merge-base", "--is-ancestor", HEAD]:
                return CompletedProcess(args, 0, "", "")
            if git_args[:3] == ["merge-base", "--is-ancestor", PARENT]:
                return CompletedProcess(args, 0, "", "")
            return CompletedProcess(args, 0, "", "")
        raise AssertionError(f"unexpected command: {args}")


def target(*, reconciled=True, ancestor_links_valid=True):
    snapshot = PullRequestSnapshot(42, "OPEN", "develop", BASE, HEAD, changed_files=1)
    return ReviewTarget(
        snapshot,
        EffectiveParent("develop", PARENT),
        reconciled=reconciled,
        ancestor_links_valid=ancestor_links_valid,
        repository="owner/repo",
    )


def hosted_payload(body="Full review triggered"):
    trigger_at = "2026-01-01T00:00:00Z"
    response_at = "2026-01-01T00:01:00Z"
    return {
        "data": {
            "repository": {
                "pullRequest": {
                    "headRefOid": HEAD,
                    "comments": {
                        "nodes": [
                            {
                                "databaseId": 100,
                                "author": {"login": "ben"},
                                "body": hosted.FULL_COMMAND,
                                "createdAt": trigger_at,
                                "url": "https://example.test/trigger",
                            },
                            {
                                "databaseId": 101,
                                "author": {"login": "coderabbitai[bot]"},
                                "body": body,
                                "createdAt": response_at,
                                "url": "https://example.test/response",
                            },
                        ]
                    },
                    "reviews": {"nodes": []},
                }
            }
        }
    }


def write_hosted_trigger(common_dir, *, legacy=False, status="posted"):
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
                "head_sha": HEAD,
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


class CliReviewRunnerTests(unittest.TestCase):
    def test_wrong_expectation_fails_before_runner(self):
        resolver = Mock()
        resolver.resolve_cli_target.return_value = target()
        with self.assertRaises(WrongTargetError):
            target_from_resolver(resolver, expected_pr=99)
        resolver.resolve_cli_target.assert_called_once_with()

    def test_unreconciled_mode_requires_reason_and_is_provisional(self):
        with self.assertRaises(ReviewRunnerError):
            run_cli_review(target(reconciled=False), github=FakeGitHub(), allow_unreconciled=True)
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
                allow_unreconciled=True,
                reason="cost is disproportionate for a one-pass discovery",
            )
            self.assertTrue(result.provisional)
            metadata = json.loads((result.capture_dir / "metadata.json").read_text())
            self.assertTrue(metadata["provisional"])
            self.assertIn("one-pass", metadata["reason"])

    def test_repository_lock_rejects_a_concurrent_cli_process(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / ".git").mkdir()
            commands = FakeCommands(root, delay=0.06)
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
            second.start()
            first.join()
            second.join()
            self.assertEqual(len(results), 1)
            self.assertEqual(len(errors), 1)
            self.assertIn("already running", errors[0])
            self.assertFalse(commands.overlap)

    def test_cli_holds_the_hosted_per_pr_lock_through_review_execution(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            common_dir = root / ".git"
            common_dir.mkdir()
            review_started = threading.Event()
            allow_review_finish = threading.Event()
            commands = FakeCommands(
                root,
                review_started=review_started,
                allow_review_finish=allow_review_finish,
            )
            errors = []

            def run():
                try:
                    run_cli_review(target(), github=FakeGitHub(), source_root=root, runner=commands)
                except ReviewRunnerError as error:
                    errors.append(error)

            thread = threading.Thread(target=run)
            thread.start()
            self.assertTrue(review_started.wait(timeout=3))
            request_lock = common_dir / "firemud" / "hosted" / "owner_repo" / "pr-42" / "request.lock"
            with request_lock.open("a+") as lock_handle, self.assertRaises(BlockingIOError):
                fcntl.flock(lock_handle.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
            allow_review_finish.set()
            thread.join(timeout=3)
            self.assertFalse(thread.is_alive())
            self.assertEqual(errors, [])

    def test_current_or_legacy_active_hosted_reservation_blocks_cli(self):
        for legacy in (False, True):
            with self.subTest(legacy=legacy), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                common_dir = root / ".git"
                common_dir.mkdir()
                write_hosted_trigger(common_dir, legacy=legacy)
                commands = FakeCommands(root)
                with (
                    patch("pr_review.cli_runner.github_api.fetch_pull_request", return_value=hosted_payload()),
                    self.assertRaisesRegex(
                        ReviewRunnerError,
                        "Hosted review requires resolution before CLI review: active",
                    ),
                ):
                    run_cli_review(target(), github=FakeGitHub(), source_root=root, runner=commands)
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
                self.assertRaisesRegex(ReviewRunnerError, "Hosted review requires resolution.*ambiguous"),
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
