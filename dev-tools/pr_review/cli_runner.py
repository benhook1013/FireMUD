"""The isolated CodeRabbit CLI execution boundary.

This module deliberately knows nothing about stack policy.  The stack layer supplies a
fully resolved :class:`ReviewTarget`; this runner verifies the live GitHub facts that
must still be true immediately before spending CLI quota and then runs exactly one
committed review in an isolated worktree.

The public integration seam is ``ReviewTargetResolver.resolve_cli_target``.  Keeping
target selection outside this module makes a wrong ``--expect-pr`` assertion possible
before a lock is acquired or the review process is started.
"""

from __future__ import annotations

import dataclasses
import fcntl
import json
import os
import shutil
import subprocess
import tempfile
import time
import unicodedata
import uuid
from collections.abc import Callable, Mapping, Sequence
from pathlib import Path
from typing import Any, Protocol

from . import github as github_api
from . import hosted
from .git_merge import TestMergeError, test_merge_tree
from .patch_identity import patch_identity


class ReviewRunnerError(RuntimeError):
    """A preflight or execution failure that is safe to show to an operator."""


class WrongTargetError(ReviewRunnerError):
    """The requested target does not equal the target selected by stack policy."""


class StaleReviewTargetError(ReviewRunnerError):
    """A direct-to-default target became stale before review work began."""


class UnreconciledReviewError(ReviewRunnerError):
    """A normal review was requested while stack reconciliation is incomplete."""


GIT_TIMEOUT_SECONDS = 30
CODERABBIT_TIMEOUT_SECONDS = 30 * 60


def _name_only_diff_args(base: str, head: str) -> tuple[str, ...]:
    """Return deterministic path-list arguments matching GitHub rename reporting."""

    return (
        "diff",
        "--name-only",
        "-z",
        "--find-renames=50%",
        "-l0",
        "--diff-algorithm=myers",
        "--no-ext-diff",
        "--no-textconv",
        f"{base}...{head}",
    )


@dataclasses.dataclass(frozen=True)
class EffectiveParent:
    """The exact parent identity against which a child review is anchored."""

    ref_name: str
    head_sha: str
    pr_number: int | None = None


@dataclasses.dataclass(frozen=True)
class PullRequestSnapshot:
    """The immutable GitHub fields needed for candidate validation."""

    number: int
    state: str
    base_ref_name: str
    base_sha: str
    head_sha: str
    head_ref_name: str = ""
    changed_files: int = 0
    mergeable: str = "MERGEABLE"
    merged: bool = False
    base_exists: bool = True
    head_repository: str | None = None


@dataclasses.dataclass(frozen=True)
class ReviewTarget:
    """A stack-selected child and its effective parent.

    ``snapshot`` and ``parent`` are obtained from the integration layer.  The runner
    refreshes the corresponding live GitHub values through ``github`` before invoking
    CodeRabbit, so stale state cannot silently spend quota.
    """

    snapshot: PullRequestSnapshot
    parent: EffectiveParent
    reconciled: bool = True
    ancestor_links_valid: bool = True
    patch_identity: str = ""
    merge_base: str = ""
    repository: str = ""
    default_base_front: bool = False
    default_test_merge_base_sha: str = ""
    default_test_merge_head_sha: str = ""
    default_test_merge_tree_sha: str = ""

    def has_current_default_test_merge_proof(self) -> bool:
        """Whether the controller supplied a tree proof for this exact PR tuple."""

        def is_sha(value: str) -> bool:
            return len(value) == 40 and all(character in "0123456789abcdefABCDEF" for character in value)

        return (
            self.default_base_front
            and self.parent.pr_number is None
            and self.parent.ref_name == self.snapshot.base_ref_name
            and self.parent.head_sha.casefold() == self.snapshot.base_sha.casefold()
            and self.default_test_merge_base_sha.casefold() == self.snapshot.base_sha.casefold()
            and self.default_test_merge_head_sha.casefold() == self.snapshot.head_sha.casefold()
            and is_sha(self.default_test_merge_base_sha)
            and is_sha(self.default_test_merge_head_sha)
            and is_sha(self.default_test_merge_tree_sha)
        )


class ReviewTargetResolver(Protocol):
    """Integration interface implemented by the unified stack controller."""

    def resolve_cli_target(self) -> ReviewTarget:
        """Return the one PR currently permitted to run a CLI review."""


class GitHubReader(Protocol):
    """Read-only GitHub operations required by runner preflight."""

    def pull_request(self, number: int) -> PullRequestSnapshot: ...

    def pull_request_files(self, number: int) -> Sequence[str]: ...

    def branch_head(self, ref_name: str) -> str: ...


class CommandRunner(Protocol):
    """Small subprocess seam used by focused tests and the real runner."""

    def run(
        self,
        args: Sequence[str],
        *,
        cwd: Path | None = None,
        capture_output: bool = False,
        check: bool = True,
        text: bool = True,
        timeout: float = GIT_TIMEOUT_SECONDS,
    ) -> subprocess.CompletedProcess[str] | subprocess.CompletedProcess[bytes]: ...


class SubprocessRunner:
    def run(
        self,
        args: Sequence[str],
        *,
        cwd: Path | None = None,
        capture_output: bool = False,
        check: bool = True,
        text: bool = True,
        timeout: float = GIT_TIMEOUT_SECONDS,
    ) -> subprocess.CompletedProcess[str] | subprocess.CompletedProcess[bytes]:
        return subprocess.run(
            list(args),
            cwd=cwd,
            text=text,
            capture_output=capture_output,
            check=check,
            timeout=timeout,
        )


@dataclasses.dataclass(frozen=True)
class ReviewResult:
    """Completed (or failed) CLI process and its durable private capture identity."""

    run_id: str
    pull_request: int
    candidate_sha: str
    parent_sha: str
    merge_base: str
    published_files: int
    candidate_files: int
    published_status: str
    provisional: bool
    duration_seconds: int
    exit_status: int
    capture_dir: Path

    def as_dict(self) -> dict[str, Any]:
        return {
            "run_id": self.run_id,
            "pull_request": self.pull_request,
            "candidate_sha": self.candidate_sha,
            "parent_sha": self.parent_sha,
            "merge_base": self.merge_base,
            "published_files": self.published_files,
            "candidate_files": self.candidate_files,
            "published_status": self.published_status,
            "provisional": self.provisional,
            "duration_seconds": self.duration_seconds,
            "exit_status": self.exit_status,
            "capture_dir": str(self.capture_dir),
            "checkpoint_marker": f"<!-- firemud-cli-run: {self.run_id} -->",
            "duration_marker": f"<!-- firemud-review-duration-seconds: {self.duration_seconds} -->",
        }


def _git(
    runner: CommandRunner,
    source_root: Path,
    *args: str,
    capture_output: bool = True,
    check: bool = True,
    text: bool = True,
    timeout: float = GIT_TIMEOUT_SECONDS,
) -> subprocess.CompletedProcess[str] | subprocess.CompletedProcess[bytes]:
    try:
        return runner.run(
            ["git", "-C", str(source_root), *args],
            capture_output=capture_output,
            check=check,
            text=text,
            timeout=timeout,
        )
    except subprocess.TimeoutExpired as error:
        raise ReviewRunnerError(f"git command timed out after {timeout} seconds") from error


def _git_output(
    runner: CommandRunner,
    source_root: Path,
    *args: str,
    timeout: float = GIT_TIMEOUT_SECONDS,
) -> str:
    result = _git(runner, source_root, *args, timeout=timeout)
    return result.stdout.strip()


def _sha(value: str, label: str) -> str:
    if len(value) != 40 or any(character not in "0123456789abcdefABCDEF" for character in value):
        raise ReviewRunnerError(f"{label} must be a full 40-character commit SHA")
    return value.lower()


def _unique_merge_base(
    runner: CommandRunner,
    source_root: Path,
    left: str,
    right: str,
    *,
    timeout: float = GIT_TIMEOUT_SECONDS,
) -> str:
    result = _git(runner, source_root, "merge-base", "--all", left, right, timeout=timeout)
    bases = [line.strip() for line in result.stdout.splitlines() if line.strip()]
    if len(bases) != 1:
        raise ReviewRunnerError("candidate and effective parent have ambiguous merge bases")
    return _sha(bases[0], "merge base")


def _ancestor(
    runner: CommandRunner,
    source_root: Path,
    ancestor: str,
    descendant: str,
    message: str,
    *,
    timeout: float = GIT_TIMEOUT_SECONDS,
) -> None:
    result = _git(
        runner,
        source_root,
        "merge-base",
        "--is-ancestor",
        ancestor,
        descendant,
        check=False,
        timeout=timeout,
    )
    if result.returncode != 0:
        raise ReviewRunnerError(message)


def _nul_paths(
    runner: CommandRunner,
    source_root: Path,
    *args: str,
    timeout: float = GIT_TIMEOUT_SECONDS,
) -> list[str]:
    result = _git(runner, source_root, *args, timeout=timeout)
    return sorted(path for path in result.stdout.split("\0") if path)


def _patch_identity(
    runner: CommandRunner,
    source_root: Path,
    merge_base: str,
    head: str,
    *,
    timeout: float = GIT_TIMEOUT_SECONDS,
) -> str:
    """Hash the complete binary-capable candidate patch used by the review."""

    def run_diff(args: Sequence[str]) -> bytes | str:
        result = _git(
            runner,
            source_root,
            *args,
            text=False,
            timeout=timeout,
        )
        return result.stdout

    return patch_identity(run_diff, merge_base, head)


def _test_merge_commit(
    runner: CommandRunner,
    source_root: Path,
    base: str,
    head: str,
    *,
    timeout: float = GIT_TIMEOUT_SECONDS,
) -> str:
    """Create an isolated local merge commit for one exact base/head pair."""

    def run_git(args, *, check, text, timeout):
        return runner.run(
            args,
            capture_output=True,
            check=check,
            text=text,
            timeout=timeout,
        )

    try:
        tree = test_merge_tree(
            source_root,
            base,
            head,
            run=run_git,
            timeout_seconds=timeout,
        )
    except TestMergeError as error:
        raise ReviewRunnerError(str(error)) from error
    merge = _sha(
        _git_output(
            runner,
            source_root,
            "-c",
            "user.name=FireMUD review runner",
            "-c",
            "user.email=firemud-review-runner@invalid",
            "commit-tree",
            tree,
            "-p",
            base,
            "-p",
            head,
            "-m",
            "FireMUD current-base review context",
            timeout=timeout,
        ),
        "test-merge commit",
    )
    parents = _git_output(runner, source_root, "show", "-s", "--format=%P", merge, timeout=timeout).split()
    if parents != [base, head]:
        raise ReviewRunnerError("current test-merge commit does not preserve the exact base/head parents")
    return merge


def _verify_target_still_current(target: ReviewTarget, github: GitHubReader) -> None:
    """Recheck the selected tuple at the provider boundary, after context setup."""

    current = github.pull_request(target.snapshot.number)
    parent_tip = _sha(github.branch_head(target.parent.ref_name), "effective parent tip")
    selected_head = _sha(target.snapshot.head_sha, "selected head")
    selected_base = _sha(target.snapshot.base_sha, "selected base")
    if (
        current.number == target.snapshot.number
        and current.state.upper() == "OPEN"
        and current.mergeable.upper() == "MERGEABLE"
        and current.base_exists
        and _sha(current.head_sha, "pull request head") == selected_head
        and current.base_ref_name == target.parent.ref_name
        and _sha(current.base_sha, "pull request base") == selected_base
        and parent_tip == _sha(target.parent.head_sha, "selected parent")
    ):
        return
    if (
        target.default_base_front
        and current.number == target.snapshot.number
        and current.head_sha.casefold() == selected_head
        and current.base_ref_name == target.parent.ref_name
        and current.mergeable.upper() == "MERGEABLE"
        and current.base_exists
        and (
            current.base_sha.casefold() != selected_base
            or parent_tip != _sha(target.parent.head_sha, "selected parent")
        )
    ):
        raise StaleReviewTargetError("default base advanced during CLI preflight")
    raise ReviewRunnerError("pull request changed during CLI preflight")


def _atomic_json(path: Path, value: Mapping[str, Any]) -> None:
    temporary = path.with_name(f".{path.name}.{os.getpid()}.{uuid.uuid4().hex}.tmp")
    temporary.write_text(json.dumps(value, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    os.chmod(temporary, 0o600)
    os.replace(temporary, path)


def _common_dir(
    runner: CommandRunner,
    source_root: Path,
    *,
    timeout: float = GIT_TIMEOUT_SECONDS,
) -> Path:
    value = _git_output(runner, source_root, "rev-parse", "--git-common-dir", timeout=timeout)
    path = Path(value)
    if not path.is_absolute():
        path = (source_root / path).resolve()
    return path


def _assert_no_active_hosted_review(repo: str, pr_number: int, common_dir: Path) -> None:
    """Refuse CLI work only when Hosted attribution is unsafe or unresolved."""

    records = hosted.current_trigger_record_paths(repo, pr_number, common=common_dir)
    if not records:
        return
    if len(records) > 1:
        raise ReviewRunnerError("multiple current Hosted reservations require operator resolution")

    try:
        payload = github_api.fetch_pull_request(repo, pr_number)
        for record_path in records:
            record = hosted.load_trigger_reservation(record_path, repo, pr_number)
            state = hosted.trigger_state(repo, pr_number, payload, record, record_path)
            # An already-posted Hosted request can run alongside the independent
            # CLI process. The caller holds request.lock through CLI preflight and
            # capture initialization, so trigger classification and candidate
            # snapshotting remain serialized with Hosted posting. An ambiguous,
            # unattributed, or timed-out response still fails closed.
            if state.state in {"ambiguous", "unattributed", "timed_out"}:
                raise ReviewRunnerError(f"Hosted review requires resolution before CLI review: {state.state}")
            if state.state not in {
                "active",
                "awaiting_response",
                "completed",
                "noop",
                "failed",
                "retired",
                "rate_limited",
            }:
                raise ReviewRunnerError(f"Hosted review has an unsupported state before CLI review: {state.state}")
    except ReviewRunnerError:
        raise
    except (OSError, ValueError, KeyError, TypeError, RuntimeError) as error:
        raise ReviewRunnerError("current Hosted reservation cannot be safely classified") from error


def _ensure_commit(
    runner: CommandRunner,
    source_root: Path,
    commit: str,
    label: str,
    *,
    timeout: float = GIT_TIMEOUT_SECONDS,
) -> None:
    """Make an exact GitHub SHA available without accepting FETCH_HEAD ambiguity."""

    commit = _sha(commit, label)
    present = _git(runner, source_root, "cat-file", "-e", f"{commit}^{{commit}}", check=False, timeout=timeout)
    if present.returncode == 0:
        return
    _git(runner, source_root, "remote", "get-url", "origin", timeout=timeout)
    _git(runner, source_root, "fetch", "--no-tags", "origin", commit, timeout=timeout)
    fetched = _sha(
        _git_output(runner, source_root, "rev-parse", "FETCH_HEAD^{commit}", timeout=timeout),
        "fetched commit",
    )
    if fetched != commit:
        raise ReviewRunnerError(f"fetched {label} does not match the live GitHub SHA")


def _validate_target(
    target: ReviewTarget,
    live: PullRequestSnapshot,
    live_files: Sequence[str],
    parent_tip: str,
    candidate_sha: str,
    runner: CommandRunner,
    source_root: Path,
    *,
    allow_unreconciled: bool,
    git_timeout_seconds: float = GIT_TIMEOUT_SECONDS,
) -> tuple[str, int, int, str, str]:
    expected = target.snapshot
    if target.default_base_front and not target.has_current_default_test_merge_proof():
        raise ReviewRunnerError("direct default-base target has no verified current base/head test merge")
    if live.number != expected.number:
        raise ReviewRunnerError("live pull request identity differs from selected target")
    if live.state.upper() != "OPEN":
        raise ReviewRunnerError(f"pull request is not OPEN (state: {live.state})")
    if live.mergeable.upper() != "MERGEABLE":
        raise ReviewRunnerError(f"pull request is not mergeable (mergeable: {live.mergeable})")
    if not live.base_exists:
        raise ReviewRunnerError("pull request base branch no longer exists")
    if live.base_ref_name != target.parent.ref_name:
        raise ReviewRunnerError(
            f"pull request base {live.base_ref_name!r} does not match effective parent {target.parent.ref_name!r}"
        )
    child_head = _sha(live.head_sha, "pull request head")
    if child_head != _sha(expected.head_sha, "selected head"):
        raise ReviewRunnerError("pull request head moved since target selection")
    live_base = _sha(live.base_sha, "pull request base")
    expected_base = _sha(expected.base_sha, "selected base")
    live_parent_tip = _sha(parent_tip, "effective parent tip")
    selected_parent_tip = _sha(target.parent.head_sha, "selected parent")
    if target.default_base_front and live_base != expected_base and live_base == live_parent_tip:
        raise StaleReviewTargetError("default base advanced after CLI target selection")
    if live_base != expected_base:
        raise ReviewRunnerError("pull request base moved since target selection")
    if live_parent_tip != selected_parent_tip:
        if target.default_base_front:
            raise StaleReviewTargetError("default base advanced after CLI target selection")
        raise ReviewRunnerError("effective parent moved since target selection")
    if live_base != live_parent_tip:
        raise ReviewRunnerError("pull request base SHA does not equal the effective parent tip")
    if not allow_unreconciled and (not target.reconciled or not target.ancestor_links_valid):
        raise UnreconciledReviewError("stack is unreconciled; reconcile it before running a normal CLI review")
    if len(live_files) != live.changed_files:
        raise ReviewRunnerError(
            f"pull request file list/count mismatch (files: {len(live_files)}, changedFiles: {live.changed_files})"
        )
    if not live_files:
        raise ReviewRunnerError("pull request has zero changed files; refusing to spend review quota")
    _ancestor(
        runner,
        source_root,
        child_head,
        candidate_sha,
        "committed HEAD is neither the pull request head nor a descendant containing its fixes",
        timeout=git_timeout_seconds,
    )
    merge_base = _unique_merge_base(
        runner,
        source_root,
        target.parent.head_sha,
        child_head if target.default_base_front else candidate_sha,
        timeout=git_timeout_seconds,
    )
    # Provisional discovery can use the unique merge base above even when the exact
    # parent tip is outside candidate history; normal reviews still require ancestry.
    if not allow_unreconciled and not target.default_base_front:
        _ancestor(
            runner,
            source_root,
            target.parent.head_sha,
            candidate_sha,
            "committed HEAD does not contain the exact effective parent tip",
            timeout=git_timeout_seconds,
        )
    if target.default_base_front:
        published_context = _test_merge_commit(
            runner,
            source_root,
            live_base,
            child_head,
            timeout=git_timeout_seconds,
        )
        review_context = (
            published_context
            if candidate_sha == child_head
            else _test_merge_commit(
                runner,
                source_root,
                live_base,
                candidate_sha,
                timeout=git_timeout_seconds,
            )
        )
        published = _nul_paths(
            runner,
            source_root,
            *_name_only_diff_args(live_base, published_context),
            timeout=git_timeout_seconds,
        )
        if published != sorted(live_files):
            raise ReviewRunnerError("current test-merge file paths do not match GitHub's live file list")
        candidate_merge_base = _unique_merge_base(
            runner,
            source_root,
            target.parent.head_sha,
            candidate_sha,
            timeout=git_timeout_seconds,
        )
        candidate_patch = _patch_identity(
            runner,
            source_root,
            candidate_merge_base,
            candidate_sha,
            timeout=git_timeout_seconds,
        )
        if (
            candidate_sha == child_head
            and target.patch_identity
            and candidate_patch != target.patch_identity
        ):
            raise ReviewRunnerError("published owned-patch identity changed since target selection")
        candidate_count = len(
            _nul_paths(
                runner,
                source_root,
                *_name_only_diff_args(live_base, review_context),
                timeout=git_timeout_seconds,
            )
        )
    else:
        published = _nul_paths(
            runner,
            source_root,
            *_name_only_diff_args(live.base_sha, child_head),
            timeout=git_timeout_seconds,
        )
        if published != sorted(live_files):
            raise ReviewRunnerError("published pull request file paths do not match GitHub's file list")
        review_context = candidate_sha
        candidate_count = len(
            _nul_paths(
                runner,
                source_root,
                *_name_only_diff_args(merge_base, candidate_sha),
                timeout=git_timeout_seconds,
            )
        )
    if candidate_count == 0:
        raise ReviewRunnerError("candidate has zero changed files; refusing to spend review quota")
    return merge_base, len(published), candidate_count, child_head, review_context


def run_cli_review(
    target: ReviewTarget,
    *,
    github: GitHubReader,
    source_root: Path | None = None,
    runner: CommandRunner | None = None,
    allow_unreconciled: bool = False,
    reason: str | None = None,
    review_executable: str = "coderabbit",
    git_timeout_seconds: float = GIT_TIMEOUT_SECONDS,
    review_timeout_seconds: float = CODERABBIT_TIMEOUT_SECONDS,
    monotonic_ns: Callable[[], int] = time.monotonic_ns,
) -> ReviewResult:
    """Run one isolated committed CLI review for an already-selected target.

    ``target`` must come from the unified stack integration.  In particular, callers
    must compare ``--expect-pr`` to this target before calling this function.  The
    exceptional unreconciled mode is deliberately a one-pass provisional run and is
    rejected unless a non-empty reason is recorded in its private capture.
    """

    if allow_unreconciled and not reason:
        raise ReviewRunnerError("--allow-unreconciled requires a non-empty --reason")
    if allow_unreconciled and len(reason) > 240:
        raise ReviewRunnerError("--reason must be 240 characters or fewer")
    if allow_unreconciled and any(unicodedata.category(character) == "Cc" for character in reason):
        raise ReviewRunnerError("--reason must not contain control characters")
    if not allow_unreconciled and reason:
        raise ReviewRunnerError("--reason is only valid with --allow-unreconciled")
    if allow_unreconciled and target.reconciled and target.ancestor_links_valid:
        raise ReviewRunnerError("--allow-unreconciled is only valid for an unreconciled target")
    if not allow_unreconciled and (not target.reconciled or not target.ancestor_links_valid):
        raise UnreconciledReviewError("stack is unreconciled; reconcile it before running a normal CLI review")
    runner = runner or SubprocessRunner()
    source_root = (
        source_root
        or Path(_git_output(runner, Path.cwd(), "rev-parse", "--show-toplevel", timeout=git_timeout_seconds))
    ).resolve()
    common_dir = _common_dir(runner, source_root, timeout=git_timeout_seconds)
    private_root = common_dir / "firemud" / "pr-review"
    capture_root = private_root / "runs"
    private_root.mkdir(parents=True, exist_ok=True)
    capture_root.mkdir(parents=True, exist_ok=True)
    os.chmod(private_root, 0o700)
    os.chmod(capture_root, 0o700)
    lock_path = private_root / "cli.lock"
    # Keep the marker shape understood by historical checkpoint evidence while making
    # every invocation unique.  The complete child/parent/head identity lives in the
    # private metadata, not in the human-facing marker.
    run_id = f"run.{uuid.uuid4().hex}"
    capture_dir = capture_root / run_id
    candidate_worktree: Path | None = None
    # Keep review anchors out of branch listings: this temporary ref is an
    # implementation detail of the review run, not a user-visible branch.
    pinned_ref = f"refs/firemud/pr-review-base/{run_id}"
    lock_path.touch(mode=0o600, exist_ok=True)
    with lock_path.open("r+") as lock_handle:
        try:
            fcntl.flock(lock_handle.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError as error:
            raise ReviewRunnerError(f"another CLI review is already running (lock: {lock_path})") from error
        hosted_lock_handle = None
        hosted_lock_acquired = False
        temp_root: Path | None = None
        try:
            repository = target.repository or str(getattr(github, "repo", "unknown/unknown"))
            hosted_record_path = hosted.default_trigger_record_path(
                repository, target.snapshot.number, common=common_dir
            )
            hosted_record_path.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
            hosted_record_path.parent.chmod(0o700)
            hosted_lock_path = hosted_record_path.parent / "request.lock"
            hosted_lock_path.touch(mode=0o600, exist_ok=True)
            hosted_lock_handle = hosted_lock_path.open("r+")
            try:
                fcntl.flock(hosted_lock_handle.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
            except BlockingIOError as error:
                raise ReviewRunnerError(
                    f"another review request is active for PR #{target.snapshot.number} (lock: {hosted_lock_path})"
                ) from error
            hosted_lock_acquired = True
            _assert_no_active_hosted_review(repository, target.snapshot.number, common_dir)
            capture_dir.mkdir(mode=0o700)
            live = github.pull_request(target.snapshot.number)
            live_files = github.pull_request_files(target.snapshot.number)
            parent_tip = github.branch_head(target.parent.ref_name)
            _ensure_commit(runner, source_root, live.base_sha, "pull request base", timeout=git_timeout_seconds)
            _ensure_commit(runner, source_root, parent_tip, "effective parent tip", timeout=git_timeout_seconds)
            _ensure_commit(runner, source_root, live.head_sha, "pull request head", timeout=git_timeout_seconds)
            candidate_sha = _sha(
                _git_output(runner, source_root, "rev-parse", "HEAD^{commit}", timeout=git_timeout_seconds),
                "candidate HEAD",
            )
            merge_base, published_files, candidate_files, child_head, review_context_sha = _validate_target(
                target,
                live,
                live_files,
                parent_tip,
                candidate_sha,
                runner,
                source_root,
                allow_unreconciled=allow_unreconciled,
                git_timeout_seconds=git_timeout_seconds,
            )
            candidate_patch_identity = _patch_identity(
                runner,
                source_root,
                _unique_merge_base(
                    runner,
                    source_root,
                    target.parent.head_sha,
                    candidate_sha,
                    timeout=git_timeout_seconds,
                )
                if target.default_base_front and candidate_sha != child_head
                else merge_base,
                candidate_sha,
                timeout=git_timeout_seconds,
            )
            if target.merge_base and _sha(target.merge_base, "selected merge base") != merge_base:
                raise ReviewRunnerError("candidate merge base changed since target selection")
            if candidate_sha == child_head:
                published_status = "published-head"
            else:
                ahead = _git_output(
                    runner,
                    source_root,
                    "rev-list",
                    "--count",
                    f"{child_head}..{candidate_sha}",
                    timeout=git_timeout_seconds,
                )
                if not ahead.isdigit():
                    raise ReviewRunnerError("could not count candidate commits ahead of the published head")
                published_status = f"unpublished-commits-ahead:{ahead}"
            # Pinning the merge base and allocating the temporary root are one
            # cleanup boundary: either setup step can fail, but a successful pin
            # must never outlive a failed temporary-root allocation.
            try:
                review_base_sha = target.parent.head_sha if target.default_base_front else merge_base
                _git(runner, source_root, "update-ref", pinned_ref, review_base_sha, timeout=git_timeout_seconds)
                temp_root = Path(tempfile.mkdtemp(prefix="firemud-pr-review-"))
            except Exception:
                _git(
                    runner,
                    source_root,
                    "update-ref",
                    "-d",
                    pinned_ref,
                    check=False,
                    timeout=git_timeout_seconds,
                )
                if temp_root is not None:
                    shutil.rmtree(temp_root, ignore_errors=True)
                raise
            candidate_worktree = temp_root / "candidate"
            try:
                _git(
                    runner,
                    source_root,
                    "worktree",
                    "add",
                    "--detach",
                    str(candidate_worktree),
                    review_context_sha,
                    timeout=git_timeout_seconds,
                )
                metadata: dict[str, Any] = {
                    "run_id": run_id,
                    "kind": "cli",
                    "pull_request": target.snapshot.number,
                    "candidate_sha": candidate_sha,
                    "child_head_sha": candidate_sha,
                    "published_head_sha": child_head,
                    "review_context_sha": review_context_sha,
                    "review_base_sha": review_base_sha,
                    "parent_pr": target.parent.pr_number,
                    "parent_ref": target.parent.ref_name,
                    "parent_sha": target.parent.head_sha,
                    "merge_base": merge_base,
                    "published_files": published_files,
                    "candidate_files": candidate_files,
                    "published_status": published_status,
                    "provisional": allow_unreconciled,
                    "reason": reason,
                    "patch_identity": candidate_patch_identity,
                    "argv": [review_executable, "review", "--agent", "--committed", "--base", pinned_ref],
                }
                # The line-oriented metadata file is retained as a read-compatible
                # projection for historical checkpoint comments.  metadata.json is
                # the canonical structured capture for the new controller.
                legacy_metadata = {
                    "run_id": run_id,
                    "repository": target.repository or str(getattr(github, "repo", "unknown/unknown")),
                    "pull_request": str(target.snapshot.number),
                    "candidate_sha": candidate_sha,
                    "child_head_sha": candidate_sha,
                    "published_head_sha": child_head,
                    "parent_pr": str(target.parent.pr_number) if target.parent.pr_number is not None else "",
                    "parent_ref": target.parent.ref_name,
                    "parent_sha": target.parent.head_sha,
                    "merge_base": merge_base,
                    "patch_identity": candidate_patch_identity,
                    "candidate_files": str(candidate_files),
                    "published_files": str(published_files),
                    "published_status": published_status,
                    "provisional": str(allow_unreconciled).lower(),
                    "reason": reason or "",
                }
                (capture_dir / "metadata").write_text(
                    "".join(f"{key}={value}\n" for key, value in legacy_metadata.items()),
                    encoding="utf-8",
                )
                os.chmod(capture_dir / "metadata", 0o600)
                _atomic_json(capture_dir / "metadata.json", metadata)
                # Hosted posting and CLI preflight share request.lock. Release it
                # only after the candidate and durable capture are pinned; the
                # repository-wide CLI lock remains held through provider execution.
                if hosted_lock_handle is not None:
                    if hosted_lock_acquired:
                        fcntl.flock(hosted_lock_handle.fileno(), fcntl.LOCK_UN)
                        hosted_lock_acquired = False
                    hosted_lock_handle.close()
                    hosted_lock_handle = None
                _verify_target_still_current(target, github)
                started = monotonic_ns()
                try:
                    process = runner.run(
                        [review_executable, "review", "--agent", "--committed", "--base", pinned_ref],
                        cwd=candidate_worktree,
                        capture_output=True,
                        check=False,
                        text=True,
                        timeout=review_timeout_seconds,
                    )
                except subprocess.TimeoutExpired as error:
                    finished = monotonic_ns()
                    if finished < started:
                        raise ReviewRunnerError("process clock moved backwards while measuring review duration")
                    duration = max(0, (finished - started + 999_999_999) // 1_000_000_000)
                    timeout_stdout = error.stdout if error.stdout is not None else error.output
                    timeout_stderr = error.stderr
                    stdout = (
                        timeout_stdout.decode("utf-8", errors="replace")
                        if isinstance(timeout_stdout, bytes)
                        else timeout_stdout or ""
                    )
                    stderr = (
                        timeout_stderr.decode("utf-8", errors="replace")
                        if isinstance(timeout_stderr, bytes)
                        else timeout_stderr or ""
                    )
                    (capture_dir / "stdout").write_text(stdout, encoding="utf-8")
                    (capture_dir / "stderr").write_text(stderr, encoding="utf-8")
                    (capture_dir / "exit-status").write_text("timeout\n", encoding="utf-8")
                    (capture_dir / "review-duration-seconds").write_text(f"{duration}\n", encoding="utf-8")
                    metadata.update({"duration_seconds": duration, "exit_status": None, "timed_out": True})
                    _atomic_json(capture_dir / "metadata.json", metadata)
                    with (capture_dir / "metadata").open("a", encoding="utf-8") as legacy_file:
                        legacy_file.write(f"review_duration_seconds={duration}\n")
                    raise ReviewRunnerError(
                        f"CodeRabbit review timed out after {review_timeout_seconds} seconds"
                    ) from error
                finished = monotonic_ns()
                if finished < started:
                    raise ReviewRunnerError("process clock moved backwards while measuring review duration")
                duration = max(0, (finished - started + 999_999_999) // 1_000_000_000)
                (capture_dir / "stdout").write_text(process.stdout or "", encoding="utf-8")
                (capture_dir / "stderr").write_text(process.stderr or "", encoding="utf-8")
                (capture_dir / "exit-status").write_text(f"{process.returncode}\n", encoding="utf-8")
                (capture_dir / "review-duration-seconds").write_text(f"{duration}\n", encoding="utf-8")
                metadata.update({"duration_seconds": duration, "exit_status": process.returncode})
                _atomic_json(capture_dir / "metadata.json", metadata)
                with (capture_dir / "metadata").open("a", encoding="utf-8") as legacy_file:
                    legacy_file.write(f"review_duration_seconds={duration}\n")
                return ReviewResult(
                    run_id=run_id,
                    pull_request=target.snapshot.number,
                    candidate_sha=candidate_sha,
                    parent_sha=target.parent.head_sha,
                    merge_base=merge_base,
                    published_files=published_files,
                    candidate_files=candidate_files,
                    published_status=published_status,
                    provisional=allow_unreconciled,
                    duration_seconds=duration,
                    exit_status=process.returncode,
                    capture_dir=capture_dir,
                )
            finally:
                if candidate_worktree is not None:
                    _git(
                        runner,
                        source_root,
                        "worktree",
                        "remove",
                        "--force",
                        str(candidate_worktree),
                        check=False,
                        timeout=git_timeout_seconds,
                    )
                _git(runner, source_root, "update-ref", "-d", pinned_ref, check=False, timeout=git_timeout_seconds)
                if temp_root is not None:
                    shutil.rmtree(temp_root, ignore_errors=True)
        except Exception as error:
            if capture_dir.exists():
                (capture_dir / "error").write_text(f"{error}\n", encoding="utf-8")
            raise
        finally:
            if hosted_lock_handle is not None:
                if hosted_lock_acquired:
                    fcntl.flock(hosted_lock_handle.fileno(), fcntl.LOCK_UN)
                hosted_lock_handle.close()
            fcntl.flock(lock_handle.fileno(), fcntl.LOCK_UN)


def target_from_resolver(resolver: ReviewTargetResolver, expected_pr: int | None = None) -> ReviewTarget:
    """Resolve and assert a target before any quota-consuming runner work."""

    target = resolver.resolve_cli_target()
    if expected_pr is not None and target.snapshot.number != expected_pr:
        raise WrongTargetError(
            f"--expect-pr {expected_pr} does not match the selected CLI target #{target.snapshot.number}"
        )
    return target
