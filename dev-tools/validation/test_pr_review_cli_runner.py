import fcntl
import hashlib
import json
import subprocess
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

from pr_review import evidence, hosted
from pr_review.cli import _render
from pr_review.cli_runner import (
    EffectiveParent,
    PullRequestSnapshot,
    ReviewRunnerError,
    ReviewTarget,
    SubprocessRunner,
    UnreconciledReviewError,
    WrongTargetError,
    _name_only_diff_args,
    _validate_target,
    run_cli_review,
    target_from_resolver,
)
from pr_review.patch_identity import patch_diff_args

BASE = "a" * 40
PARENT = BASE
HEAD = "c" * 40
CANDIDATE = "d" * 40
OLDER_BASE = "e" * 40


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
        parent_is_ancestor=True,
        merge_base=None,
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
        self.parent_is_ancestor = parent_is_ancestor
        self.merge_base = merge_base or PARENT
        self.timeout_calls = []

    def run(self, args, *, cwd=None, capture_output=False, check=True, text=True, timeout=None):
        self.calls.append((tuple(args), cwd))
        self.timeout_calls.append((tuple(args), timeout, text))
        if args[0] == "coderabbit":
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
            return CompletedProcess(args, 0, self.review_output, "")
        if args[:3] == ["git", "-C", str(self.root)]:
            if self.timeout_git:
                raise subprocess.TimeoutExpired(args, timeout, output=b"partial git\n", stderr=b"timed out\n")
            git_args = args[3:]
            if git_args == ["rev-parse", "--git-common-dir"]:
                return CompletedProcess(args, 0, ".git\n", "")
            if git_args == ["rev-parse", "HEAD^{commit}"]:
                return CompletedProcess(args, 0, f"{self.candidate}\n", "")
            if git_args[:3] == ["diff", "--name-only", "-z"]:
                return CompletedProcess(args, 0, "\0".join(self.files) + "\0", "")
            if git_args == list(patch_diff_args(PARENT, self.candidate)):
                output = self.patch_bytes if not text else self.patch_bytes.decode("utf-8")
                return CompletedProcess(args, 0, output, b"" if not text else "")
            if git_args == ["rev-list", "--count", f"{HEAD}..{self.candidate}"]:
                return CompletedProcess(args, 0, "1\n", "")
            if git_args[:3] == ["merge-base", "--all", PARENT]:
                return CompletedProcess(args, 0, f"{self.merge_base}\n", "")
            if git_args[:3] == ["merge-base", "--is-ancestor", HEAD]:
                return CompletedProcess(args, 0, "", "")
            if git_args[:3] == ["merge-base", "--is-ancestor", PARENT]:
                return CompletedProcess(args, 0 if self.parent_is_ancestor else 1, "", "")
            return CompletedProcess(args, 0, "", "")
        raise AssertionError(f"unexpected command: {args}")


def target(*, reconciled=True, ancestor_links_valid=True, merge_base=""):
    snapshot = PullRequestSnapshot(42, "OPEN", "develop", BASE, HEAD, changed_files=1)
    return ReviewTarget(
        snapshot,
        EffectiveParent("develop", PARENT),
        reconciled=reconciled,
        ancestor_links_valid=ancestor_links_valid,
        merge_base=merge_base,
        repository="owner/repo",
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
                allow_unreconciled=False,
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

    def test_unreconciled_reason_rejects_long_and_control_text_before_capture(self):
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
                            allow_unreconciled=True,
                            reason=reason,
                        )
                    self.assertEqual(commands.calls, [])
                    self.assertFalse((root / ".git" / "firemud" / "pr-review" / "runs").exists())

    def test_provisional_run_allows_parent_tip_outside_candidate_history(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / ".git").mkdir()
            commands = FakeCommands(root, parent_is_ancestor=False, merge_base=OLDER_BASE)
            result = run_cli_review(
                target(reconciled=False, merge_base=OLDER_BASE),
                github=FakeGitHub(),
                source_root=root,
                runner=commands,
                allow_unreconciled=True,
                reason="one provisional discovery after the parent advanced",
            )

            self.assertEqual(result.merge_base, OLDER_BASE)
            self.assertEqual(result.parent_sha, PARENT)
            self.assertTrue(result.provisional)
            self.assertIn(
                ("git", "-C", str(root), "update-ref", f"refs/heads/codex-review-base/{result.run_id}", OLDER_BASE),
                [call[0] for call in commands.calls],
            )
            self.assertIn(
                ("git", "-C", str(root), "merge-base", "--all", PARENT, HEAD),
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

    def test_posted_active_or_awaiting_hosted_reservation_allows_cli_and_preserves_record(self):
        for include_response in (True, False):
            with self.subTest(include_response=include_response), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                common_dir = root / ".git"
                common_dir.mkdir()
                record_path = write_hosted_trigger(common_dir)
                original_record = json.loads(record_path.read_text())
                review_started = threading.Event()
                allow_review_finish = threading.Event()
                commands = FakeCommands(
                    root,
                    review_started=review_started,
                    allow_review_finish=allow_review_finish,
                )
                errors = []

                def run(run_root=root, run_commands=commands, run_errors=errors):
                    try:
                        run_cli_review(
                            target(),
                            github=FakeGitHub(),
                            source_root=run_root,
                            runner=run_commands,
                        )
                    except ReviewRunnerError as error:
                        run_errors.append(error)

                with patch(
                    "pr_review.cli_runner.github_api.fetch_pull_request",
                    return_value=hosted_payload(include_response=include_response),
                ):
                    thread = threading.Thread(target=run)
                    thread.start()
                    self.assertTrue(review_started.wait(timeout=3))
                    request_lock = record_path.parent / "request.lock"
                    with request_lock.open("a+") as lock_handle:
                        fcntl.flock(lock_handle.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
                        fcntl.flock(lock_handle.fileno(), fcntl.LOCK_UN)
                    allow_review_finish.set()
                    thread.join(timeout=3)

                self.assertFalse(thread.is_alive())
                self.assertEqual(errors, [])
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
                        f"Hosted review requires resolution before CLI review: {state}",
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
                self.assertRaisesRegex(ReviewRunnerError, "multiple current Hosted reservations"),
            ):
                run_cli_review(target(), github=FakeGitHub(), source_root=root, runner=commands)
            fetch.assert_not_called()
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
