import json
import sys
import tempfile
import threading
import time
import unittest
from pathlib import Path
from subprocess import CompletedProcess
from unittest.mock import Mock

DEV_TOOLS = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(DEV_TOOLS))

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
    def __init__(self, root, delay=0, candidate=HEAD):
        self.root = root
        self.delay = delay
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
    )


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
