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
import hashlib
import json
import os
import shutil
import subprocess
import tempfile
import time
import uuid
from collections.abc import Callable, Mapping, Sequence
from pathlib import Path
from typing import Any, Protocol


class ReviewRunnerError(RuntimeError):
    """A preflight or execution failure that is safe to show to an operator."""


class WrongTargetError(ReviewRunnerError):
    """The requested target does not equal the target selected by stack policy."""


class UnreconciledReviewError(ReviewRunnerError):
    """A normal review was requested while stack reconciliation is incomplete."""


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
    ) -> subprocess.CompletedProcess[str]: ...


class SubprocessRunner:
    def run(
        self,
        args: Sequence[str],
        *,
        cwd: Path | None = None,
        capture_output: bool = False,
        check: bool = True,
    ) -> subprocess.CompletedProcess[str]:
        return subprocess.run(
            list(args),
            cwd=cwd,
            text=True,
            capture_output=capture_output,
            check=check,
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
) -> subprocess.CompletedProcess[str]:
    return runner.run(
        ["git", "-C", str(source_root), *args],
        capture_output=capture_output,
        check=check,
    )


def _git_output(runner: CommandRunner, source_root: Path, *args: str) -> str:
    result = _git(runner, source_root, *args)
    return result.stdout.strip()


def _sha(value: str, label: str) -> str:
    if len(value) != 40 or any(character not in "0123456789abcdefABCDEF" for character in value):
        raise ReviewRunnerError(f"{label} must be a full 40-character commit SHA")
    return value.lower()


def _unique_merge_base(runner: CommandRunner, source_root: Path, left: str, right: str) -> str:
    result = _git(runner, source_root, "merge-base", "--all", left, right)
    bases = [line.strip() for line in result.stdout.splitlines() if line.strip()]
    if len(bases) != 1:
        raise ReviewRunnerError("candidate and effective parent have ambiguous merge bases")
    return _sha(bases[0], "merge base")


def _ancestor(runner: CommandRunner, source_root: Path, ancestor: str, descendant: str, message: str) -> None:
    result = _git(
        runner,
        source_root,
        "merge-base",
        "--is-ancestor",
        ancestor,
        descendant,
        check=False,
    )
    if result.returncode != 0:
        raise ReviewRunnerError(message)


def _nul_paths(runner: CommandRunner, source_root: Path, *args: str) -> list[str]:
    result = _git(runner, source_root, *args)
    return sorted(path for path in result.stdout.split("\0") if path)


def _patch_identity(runner: CommandRunner, source_root: Path, merge_base: str, head: str) -> str:
    """Hash the complete binary-capable candidate patch used by the review."""

    result = _git(runner, source_root, "diff", "--binary", "--full-index", f"{merge_base}...{head}")
    return hashlib.sha256(result.stdout.encode("utf-8")).hexdigest()


def _atomic_json(path: Path, value: Mapping[str, Any]) -> None:
    temporary = path.with_name(f".{path.name}.{os.getpid()}.{uuid.uuid4().hex}.tmp")
    temporary.write_text(json.dumps(value, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    os.chmod(temporary, 0o600)
    os.replace(temporary, path)


def _common_dir(runner: CommandRunner, source_root: Path) -> Path:
    value = _git_output(runner, source_root, "rev-parse", "--git-common-dir")
    path = Path(value)
    if not path.is_absolute():
        path = (source_root / path).resolve()
    return path


def _ensure_commit(runner: CommandRunner, source_root: Path, commit: str, label: str) -> None:
    """Make an exact GitHub SHA available without accepting FETCH_HEAD ambiguity."""

    commit = _sha(commit, label)
    present = _git(runner, source_root, "cat-file", "-e", f"{commit}^{{commit}}", check=False)
    if present.returncode == 0:
        return
    _git(runner, source_root, "remote", "get-url", "origin")
    _git(runner, source_root, "fetch", "--no-tags", "origin", commit)
    fetched = _sha(_git_output(runner, source_root, "rev-parse", "FETCH_HEAD^{commit}"), "fetched commit")
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
) -> tuple[str, int, int, str]:
    expected = target.snapshot
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
    if _sha(live.base_sha, "pull request base") != _sha(expected.base_sha, "selected base"):
        raise ReviewRunnerError("pull request base moved since target selection")
    child_head = _sha(live.head_sha, "pull request head")
    if child_head != _sha(expected.head_sha, "selected head"):
        raise ReviewRunnerError("pull request head moved since target selection")
    if _sha(parent_tip, "effective parent tip") != _sha(target.parent.head_sha, "selected parent"):
        raise ReviewRunnerError("effective parent moved since target selection")
    if _sha(live.base_sha, "pull request base") != _sha(parent_tip, "effective parent tip"):
        raise ReviewRunnerError("pull request base SHA does not equal the effective parent tip")
    if not allow_unreconciled and (not target.reconciled or not target.ancestor_links_valid):
        raise UnreconciledReviewError("stack is unreconciled; reconcile it before running a normal CLI review")
    if len(live_files) != live.changed_files:
        raise ReviewRunnerError(
            f"pull request file list/count mismatch (files: {len(live_files)}, changedFiles: {live.changed_files})"
        )
    if not live_files:
        raise ReviewRunnerError("pull request has zero changed files; refusing to spend review quota")
    published = _nul_paths(runner, source_root, "diff", "--name-only", f"{live.base_sha}...{child_head}")
    if published != sorted(live_files):
        raise ReviewRunnerError("published pull request file paths do not match GitHub's file list")
    _ancestor(
        runner,
        source_root,
        child_head,
        candidate_sha,
        "committed HEAD is neither the pull request head nor a descendant containing its fixes",
    )
    merge_base = _unique_merge_base(runner, source_root, target.parent.head_sha, candidate_sha)
    _ancestor(
        runner,
        source_root,
        target.parent.head_sha,
        candidate_sha,
        "committed HEAD does not contain the exact effective parent tip",
    )
    candidate_count = len(_nul_paths(runner, source_root, "diff", "--name-only", f"{merge_base}...{candidate_sha}"))
    if candidate_count == 0:
        raise ReviewRunnerError("candidate has zero changed files; refusing to spend review quota")
    return merge_base, len(published), candidate_count, child_head


def run_cli_review(
    target: ReviewTarget,
    *,
    github: GitHubReader,
    source_root: Path | None = None,
    runner: CommandRunner | None = None,
    allow_unreconciled: bool = False,
    reason: str | None = None,
    review_executable: str = "coderabbit",
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
    if not allow_unreconciled and reason:
        raise ReviewRunnerError("--reason is only valid with --allow-unreconciled")
    if allow_unreconciled and target.reconciled and target.ancestor_links_valid:
        raise ReviewRunnerError("--allow-unreconciled is only valid for an unreconciled target")
    if not allow_unreconciled and (not target.reconciled or not target.ancestor_links_valid):
        raise UnreconciledReviewError("stack is unreconciled; reconcile it before running a normal CLI review")
    runner = runner or SubprocessRunner()
    source_root = (source_root or Path(_git_output(runner, Path.cwd(), "rev-parse", "--show-toplevel"))).resolve()
    common_dir = _common_dir(runner, source_root)
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
    pinned_ref = f"refs/heads/codex-review-base/{run_id}"
    lock_path.touch(mode=0o600, exist_ok=True)
    with lock_path.open("r+") as lock_handle:
        try:
            fcntl.flock(lock_handle.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError as error:
            raise ReviewRunnerError(f"another CLI review is already running (lock: {lock_path})") from error
        try:
            capture_dir.mkdir(mode=0o700)
            live = github.pull_request(target.snapshot.number)
            live_files = github.pull_request_files(target.snapshot.number)
            parent_tip = github.branch_head(target.parent.ref_name)
            _ensure_commit(runner, source_root, live.base_sha, "pull request base")
            _ensure_commit(runner, source_root, parent_tip, "effective parent tip")
            _ensure_commit(runner, source_root, live.head_sha, "pull request head")
            candidate_sha = _sha(_git_output(runner, source_root, "rev-parse", "HEAD^{commit}"), "candidate HEAD")
            merge_base, published_files, candidate_files, child_head = _validate_target(
                target,
                live,
                live_files,
                parent_tip,
                candidate_sha,
                runner,
                source_root,
                allow_unreconciled=allow_unreconciled,
            )
            candidate_patch_identity = _patch_identity(runner, source_root, merge_base, candidate_sha)
            if target.merge_base and _sha(target.merge_base, "selected merge base") != merge_base:
                raise ReviewRunnerError("candidate merge base changed since target selection")
            if candidate_sha == child_head:
                published_status = "published-head"
            else:
                ahead = _git_output(runner, source_root, "rev-list", "--count", f"{child_head}..{candidate_sha}")
                if not ahead.isdigit():
                    raise ReviewRunnerError("could not count candidate commits ahead of the published head")
                published_status = f"unpublished-commits-ahead:{ahead}"
            _git(runner, source_root, "update-ref", pinned_ref, merge_base)
            temp_root = Path(tempfile.mkdtemp(prefix="firemud-pr-review-"))
            candidate_worktree = temp_root / "candidate"
            try:
                _git(runner, source_root, "worktree", "add", "--detach", str(candidate_worktree), candidate_sha)
                metadata: dict[str, Any] = {
                    "run_id": run_id,
                    "kind": "cli",
                    "pull_request": target.snapshot.number,
                    "candidate_sha": candidate_sha,
                    "child_head_sha": candidate_sha,
                    "published_head_sha": child_head,
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
                started = monotonic_ns()
                process = runner.run(
                    [review_executable, "review", "--agent", "--committed", "--base", pinned_ref],
                    cwd=candidate_worktree,
                    capture_output=True,
                    check=False,
                )
                finished = monotonic_ns()
                if finished < started:
                    raise ReviewRunnerError("process clock moved backwards while measuring review duration")
                duration = max(0, (finished - started + 999_999_999) // 1_000_000_000)
                (capture_dir / "stdout").write_text(process.stdout or "", encoding="utf-8")
                (capture_dir / "stderr").write_text(process.stderr or "", encoding="utf-8")
                (capture_dir / "exit-status").write_text(f"{process.returncode}\n", encoding="utf-8")
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
                    _git(runner, source_root, "worktree", "remove", "--force", str(candidate_worktree), check=False)
                _git(runner, source_root, "update-ref", "-d", pinned_ref, check=False)
                shutil.rmtree(temp_root, ignore_errors=True)
        except Exception as error:
            if capture_dir.exists():
                (capture_dir / "error").write_text(f"{error}\n", encoding="utf-8")
            raise
        finally:
            fcntl.flock(lock_handle.fileno(), fcntl.LOCK_UN)


def target_from_resolver(resolver: ReviewTargetResolver, expected_pr: int | None = None) -> ReviewTarget:
    """Resolve and assert a target before any quota-consuming runner work."""

    target = resolver.resolve_cli_target()
    if expected_pr is not None and target.snapshot.number != expected_pr:
        raise WrongTargetError(
            f"--expect-pr {expected_pr} does not match the selected CLI target #{target.snapshot.number}"
        )
    return target
