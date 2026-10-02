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
import sqlite3
import subprocess
import tempfile
import time
import unicodedata
import uuid
from collections.abc import Callable, Mapping, Sequence
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Protocol

from . import evidence, hosted
from . import github as github_api
from .git_merge import TestMergeError, test_merge_tree
from .patch_identity import patch_identity
from .sqlite_finding_text import _safe_finding_detail
from .sqlite_provider_imports import _cli_detail, _cli_finding_title
from .sqlite_review_records import (
    FindingObservation,
    ReviewRecordsError,
    SqliteReviewRecords,
)


class ReviewRunnerError(RuntimeError):
    """A preflight or execution failure that is safe to show to an operator."""


HOSTED_ACTIVE_RESPONSE_REASON = "CodeRabbit acknowledged that the full review is active"
HOSTED_CLI_OVERLAP_HOLD_REASON = (
    "CLI can overlap Hosted only when the active Hosted request is attributable to the exact published head"
)


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
    candidate_warnings: tuple[str, ...] = ()

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
    warning: str | None = None
    force_acknowledged: bool = False
    candidate_warnings: tuple[str, ...] = ()
    force_reason: str | None = None

    def as_dict(self) -> dict[str, Any]:
        result = {
            "run_id": self.run_id,
            "pull_request": self.pull_request,
            "candidate_sha": self.candidate_sha,
            "parent_sha": self.parent_sha,
            "merge_base": self.merge_base,
            "published_files": self.published_files,
            "candidate_files": self.candidate_files,
            "published_status": self.published_status,
            "provisional": self.provisional,
            "force_acknowledged": self.force_acknowledged,
            "candidate_warnings": list(self.candidate_warnings),
            "force_reason": self.force_reason,
            "duration_seconds": self.duration_seconds,
            "duration_display": evidence.format_duration_seconds(self.duration_seconds),
            "exit_status": self.exit_status,
            "capture_dir": str(self.capture_dir),
            "checkpoint_marker": f"<!-- firemud-cli-run: {self.run_id} -->",
            "duration_marker": f"<!-- firemud-review-duration-seconds: {self.duration_seconds} -->",
        }
        if self.warning:
            result["warning"] = self.warning
        return result


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


def _verify_target_still_current(target: ReviewTarget, github: GitHubReader, *, force: bool = False) -> None:
    """Recheck the selected tuple at the provider boundary, after context setup."""

    current = github.pull_request(target.snapshot.number)
    selected_head = _sha(target.snapshot.head_sha, "selected head")
    selected_base = _sha(target.snapshot.base_sha, "selected base")
    if force:
        actual_base_tip = _sha(github.branch_head(target.snapshot.base_ref_name), "pull request base branch tip")
        if (
            current.number == target.snapshot.number
            and current.state.upper() == "OPEN"
            and current.base_exists
            and _sha(current.head_sha, "pull request head") == selected_head
            and current.base_ref_name == target.snapshot.base_ref_name
            and _sha(current.base_sha, "pull request base") == selected_base
            and actual_base_tip == selected_base
        ):
            return
        raise ReviewRunnerError("pull request identity changed during forced CLI preflight")
    parent_tip = _sha(github.branch_head(target.parent.ref_name), "effective parent tip")
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
        and current.state.upper() == "OPEN"
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


def _write_cli_lock_owner(lock_handle: Any, run_id: str) -> None:
    lock_handle.seek(0)
    lock_handle.truncate()
    lock_handle.write(f"run_id={run_id}\n")
    lock_handle.flush()
    os.fsync(lock_handle.fileno())


def _clear_cli_lock_owner(lock_handle: Any) -> None:
    lock_handle.seek(0)
    lock_handle.truncate()
    lock_handle.flush()
    os.fsync(lock_handle.fileno())


def _write_capture_complete_marker(capture_dir: Path) -> None:
    """Durably mark the capture after all provider output and metadata are written."""
    for name in (
        "stdout",
        "stderr",
        "exit-status",
        "review-duration-seconds",
        "metadata",
        "metadata.json",
    ):
        descriptor = os.open(capture_dir / name, os.O_RDONLY)
        try:
            os.fsync(descriptor)
        finally:
            os.close(descriptor)

    directory_descriptor = os.open(capture_dir, os.O_RDONLY | os.O_DIRECTORY)
    temporary = capture_dir / f".capture-complete.{os.getpid()}.{uuid.uuid4().hex}.tmp"
    marker = capture_dir / "capture-complete"
    try:
        os.fsync(directory_descriptor)
        descriptor = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(descriptor, "wb") as marker_file:
            marker_file.write(f"{capture_dir.name}\n".encode("ascii"))
            marker_file.flush()
            os.fsync(marker_file.fileno())
        os.replace(temporary, marker)
        os.fsync(directory_descriptor)
    finally:
        os.close(directory_descriptor)


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


def _select_candidate_for_hosted_overlap(
    repo: str,
    pr_number: int,
    common_dir: Path,
    *,
    published_head_sha: str,
    candidate_sha: str,
    expected_anchor: Mapping[str, Any] | None,
) -> str:
    """Select the published head for a proven Hosted overlap, otherwise keep the local candidate."""

    def display_sha(value: Any) -> str:
        if (
            isinstance(value, str)
            and len(value) == 40
            and all(character in "0123456789abcdefABCDEF" for character in value)
        ):
            return value.lower()
        return "unknown"

    def display_reservation_sha(value: Any) -> str:
        if isinstance(value, str) and "," in value:
            return ",".join(display_sha(part) for part in value.split(","))
        return display_sha(value)

    def hold(state: str, count: int = 1, reservation_sha: Any = None) -> ReviewRunnerError:
        return ReviewRunnerError(
            f"{HOSTED_CLI_OVERLAP_HOLD_REASON}; reservation_state={state}; reservation_count={count}; "
            f"candidate_sha={display_sha(candidate_sha)}; reservation_sha={display_reservation_sha(reservation_sha)}"
        )

    def same_sha(value: Any, expected: str) -> bool:
        return (
            isinstance(value, str)
            and len(value) == 40
            and all(character in "0123456789abcdefABCDEF" for character in value)
            and value.casefold() == expected.casefold()
        )

    def same_anchor(value: Any) -> bool:
        if not isinstance(value, Mapping) or expected_anchor is None:
            return False
        if type(value.get("pr")) is not int or value["pr"] != pr_number:
            return False
        for name in ("child_head", "parent_head", "merge_base"):
            expected = expected_anchor.get(name)
            if not isinstance(expected, str) or not same_sha(value.get(name), expected):
                return False
        parent_identity = expected_anchor.get("parent_identity")
        patch_id = expected_anchor.get("patch_id")
        return (
            isinstance(parent_identity, str)
            and bool(parent_identity)
            and value.get("parent_identity") == parent_identity
            and isinstance(patch_id, str)
            and bool(patch_id)
            and value.get("patch_id") == patch_id
        )

    records = hosted.current_trigger_record_paths(repo, pr_number, common=common_dir)
    if not records:
        return candidate_sha
    if len(records) > 1:
        reservation_shas: list[str] = []
        for record_path in records:
            try:
                reservation = hosted.load_trigger_reservation(record_path, repo, pr_number)
            except (OSError, ValueError, KeyError, TypeError, RuntimeError):
                reservation_shas.append("unknown")
            else:
                reservation_shas.append(display_sha(reservation.get("head_sha")))
        raise hold("multiple_current_reservations", len(records), ",".join(reservation_shas))

    try:
        payload = github_api.fetch_pull_request(repo, pr_number)
        for record_path in records:
            record = hosted.load_trigger_reservation(record_path, repo, pr_number)
            state = hosted.trigger_state(repo, pr_number, payload, record, record_path)
            # A terminal ambiguous response with exact immutable identities is
            # historical non-counting context, even after the PR advances. An
            # active response overlaps only when both immutable identities and
            # the exact published head are proven.
            terminal_attribution_ambiguity = (
                state.state == "ambiguous"
                and state.terminal is True
                and state.attributed is False
                and isinstance(state.trigger_comment_id, int)
                and not isinstance(state.trigger_comment_id, bool)
                and state.trigger_comment_id > 0
                and isinstance(state.response_id, int)
                and not isinstance(state.response_id, bool)
                and state.response_id > 0
            )
            if state.state == "active":
                immutable_active_identity = (
                    state.terminal is False
                    and state.attributed is True
                    and isinstance(state.repository, str)
                    and state.repository.casefold() == repo.casefold()
                    and state.pr_number == pr_number
                    and isinstance(state.trigger_comment_id, int)
                    and not isinstance(state.trigger_comment_id, bool)
                    and state.trigger_comment_id > 0
                    and isinstance(state.response_id, int)
                    and not isinstance(state.response_id, bool)
                    and state.response_id > 0
                    and same_sha(state.head_sha, published_head_sha)
                    and same_sha(state.current_head_sha, published_head_sha)
                    and same_anchor(record.get("anchor"))
                )
                if immutable_active_identity:
                    return published_head_sha
                raise hold("active_unverified", reservation_sha=state.head_sha)
            if state.state == "awaiting_response":
                raise hold("awaiting_response", reservation_sha=state.head_sha)
            if state.state in {"ambiguous", "unattributed", "timed_out"} and not terminal_attribution_ambiguity:
                raise hold(state.state, reservation_sha=state.head_sha)
            if terminal_attribution_ambiguity:
                continue
            if state.state not in {
                "completed",
                "noop",
                "failed",
                "retired",
                "rate_limited",
            }:
                raise hold(state.state, reservation_sha=state.head_sha)
    except ReviewRunnerError:
        raise
    except (OSError, ValueError, KeyError, TypeError, RuntimeError) as error:
        raise ReviewRunnerError(
            "current Hosted reservation cannot be safely classified; "
            f"candidate_sha={display_sha(candidate_sha)}; reservation_sha=unknown"
        ) from error
    return candidate_sha


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
    force: bool,
    git_timeout_seconds: float = GIT_TIMEOUT_SECONDS,
) -> tuple[str, int, int, str, str]:
    expected = target.snapshot
    if target.default_base_front and not force and not target.has_current_default_test_merge_proof():
        raise ReviewRunnerError("direct default-base target has no verified current base/head test merge")
    if live.number != expected.number:
        raise ReviewRunnerError("live pull request identity differs from selected target")
    if live.state.upper() != "OPEN":
        raise ReviewRunnerError(f"pull request is not OPEN (state: {live.state})")
    if live.mergeable.upper() != "MERGEABLE" and not force:
        raise ReviewRunnerError(f"pull request is not mergeable (mergeable: {live.mergeable})")
    if not live.base_exists:
        raise ReviewRunnerError("pull request base branch no longer exists")
    if live.base_ref_name != (expected.base_ref_name if force else target.parent.ref_name):
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
    if not force and target.default_base_front and live_base != expected_base and live_base == live_parent_tip:
        raise StaleReviewTargetError("default base advanced after CLI target selection")
    if live_base != expected_base:
        raise ReviewRunnerError("pull request base moved since target selection")
    if not force and live_parent_tip != selected_parent_tip:
        if target.default_base_front:
            raise StaleReviewTargetError("default base advanced after CLI target selection")
        raise ReviewRunnerError("effective parent moved since target selection")
    if live_base != live_parent_tip:
        raise ReviewRunnerError("pull request's actual base branch tip does not match its selected base SHA")
    if not force and (not target.reconciled or not target.ancestor_links_valid):
        raise UnreconciledReviewError("stack identity warnings require --force for a CLI review")
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
    diff_base = live_base if force else target.parent.head_sha
    merge_base = _unique_merge_base(
        runner,
        source_root,
        diff_base,
        child_head if target.default_base_front else candidate_sha,
        timeout=git_timeout_seconds,
    )
    if not force and not target.default_base_front:
        _ancestor(
            runner,
            source_root,
            target.parent.head_sha,
            candidate_sha,
            "committed HEAD does not contain the exact effective parent tip",
            timeout=git_timeout_seconds,
        )
    if target.default_base_front and not force:
        published_context = _test_merge_commit(
            runner,
            source_root,
            live_base,
            child_head,
            timeout=git_timeout_seconds,
        )
        published_tree = _sha(
            _git_output(
                runner,
                source_root,
                "rev-parse",
                f"{published_context}^{{tree}}",
                timeout=git_timeout_seconds,
            ),
            "published test-merge tree",
        )
        if published_tree != _sha(target.default_test_merge_tree_sha, "selected test-merge tree"):
            raise ReviewRunnerError("current test-merge tree differs from the selected base/head proof")
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
        if candidate_sha == child_head and target.patch_identity and candidate_patch != target.patch_identity:
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
    force: bool = False,
    reason: str | None = None,
    review_executable: str = "coderabbit",
    git_timeout_seconds: float = GIT_TIMEOUT_SECONDS,
    review_timeout_seconds: float = CODERABBIT_TIMEOUT_SECONDS,
    monotonic_ns: Callable[[], int] = time.monotonic_ns,
    records: SqliteReviewRecords | None = None,
    admit: Callable[[Callable[[], None]], None] | None = None,
) -> ReviewResult:
    """Run one isolated committed CLI review for an already-selected target.

    ``target`` must come from the unified stack integration.  In particular, callers
    must compare ``--expect-pr`` to this target before calling this function. ``force``
    records acknowledgment of the selected candidate's known reconciliation warnings;
    it does not weaken live PR, candidate, quota, or active-request validation.
    """

    if reason and not force:
        raise ReviewRunnerError("--reason is only valid with --force")
    if force and reason is not None and len(reason) > 240:
        raise ReviewRunnerError("--reason must be 240 characters or fewer")
    if force and reason is not None and any(unicodedata.category(character) == "Cc" for character in reason):
        raise ReviewRunnerError("--reason must not contain control characters")
    if not force and (not target.reconciled or not target.ancestor_links_valid):
        raise UnreconciledReviewError("stack identity warnings require --force for a CLI review")
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
    reservation_saved = False
    attempt_started = False
    attempt_finished = False
    provider_result_saved = False
    attempt_started_at: str | None = None
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
            try:
                _write_cli_lock_owner(lock_handle, run_id)
            except OSError as error:
                raise ReviewRunnerError("could not persist active CLI run owner marker") from error
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
            capture_dir.mkdir(mode=0o700)
            live = github.pull_request(target.snapshot.number)
            live_files = github.pull_request_files(target.snapshot.number)
            review_base_ref = live.base_ref_name if force else target.parent.ref_name
            review_base_tip = github.branch_head(review_base_ref)
            _ensure_commit(runner, source_root, live.base_sha, "pull request base", timeout=git_timeout_seconds)
            _ensure_commit(runner, source_root, review_base_tip, "review base branch tip", timeout=git_timeout_seconds)
            _ensure_commit(runner, source_root, live.head_sha, "pull request head", timeout=git_timeout_seconds)
            candidate_sha = _sha(
                _git_output(runner, source_root, "rev-parse", "HEAD^{commit}", timeout=git_timeout_seconds),
                "candidate HEAD",
            )
            merge_base, published_files, candidate_files, child_head, review_context_sha = _validate_target(
                target,
                live,
                live_files,
                review_base_tip,
                candidate_sha,
                runner,
                source_root,
                force=force,
                git_timeout_seconds=git_timeout_seconds,
            )
            candidate_patch_identity = _patch_identity(
                runner,
                source_root,
                _unique_merge_base(
                    runner,
                    source_root,
                    live.base_sha if force else target.parent.head_sha,
                    candidate_sha,
                    timeout=git_timeout_seconds,
                )
                if target.default_base_front and candidate_sha != child_head
                else merge_base,
                candidate_sha,
                timeout=git_timeout_seconds,
            )
            if not force and target.merge_base and _sha(target.merge_base, "selected merge base") != merge_base:
                raise ReviewRunnerError("candidate merge base changed since target selection")
            published_merge_base = (
                merge_base
                if candidate_sha == child_head
                else _unique_merge_base(
                    runner,
                    source_root,
                    live.base_sha if force else target.parent.head_sha,
                    child_head,
                    timeout=git_timeout_seconds,
                )
            )
            published_patch_identity = (
                candidate_patch_identity
                if candidate_sha == child_head
                else _patch_identity(
                    runner,
                    source_root,
                    published_merge_base,
                    child_head,
                    timeout=git_timeout_seconds,
                )
            )
            selected_patch_matches = bool(target.patch_identity) and target.patch_identity == published_patch_identity
            selected_merge_base_matches = bool(target.merge_base) and (
                _sha(target.merge_base, "selected merge base") == published_merge_base
            )
            expected_anchor = None
            if selected_patch_matches and selected_merge_base_matches:
                expected_anchor = {
                    "pr": target.snapshot.number,
                    "child_head": child_head,
                    "parent_identity": str(target.parent.pr_number or target.parent.ref_name),
                    "parent_head": target.parent.head_sha,
                    "merge_base": published_merge_base,
                    "patch_id": published_patch_identity,
                }
            selected_candidate_sha = _select_candidate_for_hosted_overlap(
                repository,
                target.snapshot.number,
                common_dir,
                published_head_sha=child_head,
                candidate_sha=candidate_sha,
                expected_anchor=expected_anchor,
            )
            if selected_candidate_sha != candidate_sha:
                candidate_sha = selected_candidate_sha
                merge_base, published_files, candidate_files, child_head, review_context_sha = _validate_target(
                    target,
                    live,
                    live_files,
                    review_base_tip,
                    candidate_sha,
                    runner,
                    source_root,
                    force=force,
                    git_timeout_seconds=git_timeout_seconds,
                )
                candidate_patch_identity = published_patch_identity
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
                review_base_sha = (
                    target.parent.head_sha if target.default_base_front and not force else merge_base
                )
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
                    "repository": target.repository or str(getattr(github, "repo", "unknown/unknown")),
                    "kind": "cli",
                    "capture_completion_marker": "capture-complete",
                    "pull_request": target.snapshot.number,
                    "candidate_sha": candidate_sha,
                    "child_head_sha": candidate_sha,
                    "published_head_sha": child_head,
                    "review_context_sha": review_context_sha,
                    "review_base_sha": review_base_sha,
                    "parent_pr": target.parent.pr_number,
                    "parent_ref": review_base_ref if force else target.parent.ref_name,
                    "parent_sha": live.base_sha if force else target.parent.head_sha,
                    "configured_parent_ref": target.parent.ref_name,
                    "configured_parent_sha": target.parent.head_sha,
                    "actual_base_ref": review_base_ref,
                    "actual_base_sha": live.base_sha,
                    "merge_base": merge_base,
                    "published_files": published_files,
                    "candidate_files": candidate_files,
                    "published_status": published_status,
                    "provisional": False,
                    "force_acknowledged": force,
                    "force_reason": reason,
                    "candidate_warnings": list(target.candidate_warnings),
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
                    "parent_ref": review_base_ref if force else target.parent.ref_name,
                    "parent_sha": live.base_sha if force else target.parent.head_sha,
                    "configured_parent_ref": target.parent.ref_name,
                    "configured_parent_sha": target.parent.head_sha,
                    "actual_base_ref": review_base_ref,
                    "actual_base_sha": live.base_sha,
                    "merge_base": merge_base,
                    "patch_identity": candidate_patch_identity,
                    "candidate_files": str(candidate_files),
                    "published_files": str(published_files),
                    "published_status": published_status,
                    "provisional": "false",
                    "force_acknowledged": str(force).lower(),
                    "force_reason": reason or "",
                    "candidate_warnings": json.dumps(list(target.candidate_warnings), sort_keys=True),
                }

                def reserve() -> None:
                    nonlocal reservation_saved
                    (capture_dir / "metadata").write_text(
                        "".join(f"{key}={value}\n" for key, value in legacy_metadata.items()),
                        encoding="utf-8",
                    )
                    os.chmod(capture_dir / "metadata", 0o600)
                    _atomic_json(capture_dir / "metadata.json", metadata)
                    reservation_saved = True

                if admit is None:
                    reserve()
                else:
                    admit(reserve)
                if not reservation_saved:
                    raise ReviewRunnerError("CLI admission callback returned without reserving the candidate")
                records_warning = None
                if records is not None:
                    attempt_started_at = datetime.now(timezone.utc).isoformat(timespec="seconds")
                    try:
                        records.start_attempt(
                            attempt_id=run_id,
                            source_pr=target.snapshot.number,
                            channel="cli",
                            candidate_sha=candidate_sha,
                            started_at=attempt_started_at,
                            metadata=metadata,
                        )
                    except (ReviewRecordsError, OSError, sqlite3.DatabaseError):
                        records = None
                        records_warning = (
                            "SQLite attempt and source decisions are missing; the durable CLI capture remains. "
                            "Record decisions.tsv for this run or use the exact repair path before adjudication"
                        )
                    else:
                        attempt_started = True
                # Hosted posting and CLI preflight share request.lock. Release it
                # only after the candidate and durable capture are pinned; the
                # repository-wide CLI lock remains held through provider execution.
                if hosted_lock_handle is not None:
                    if hosted_lock_acquired:
                        fcntl.flock(hosted_lock_handle.fileno(), fcntl.LOCK_UN)
                        hosted_lock_acquired = False
                    hosted_lock_handle.close()
                    hosted_lock_handle = None
                _verify_target_still_current(target, github, force=force)
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
                    _write_capture_complete_marker(capture_dir)
                    provider_result_saved = True
                    if records is not None:
                        try:
                            records.finish_attempt(
                                run_id,
                                state="timed_out",
                                duration_seconds=duration,
                                diagnostic="CodeRabbit CLI timed out before a complete result",
                                artifacts={
                                    "cli_raw_output": stdout,
                                    "cli_diagnostic": stderr,
                                    "metadata": json.dumps(metadata, sort_keys=True),
                                },
                            )
                        except (ReviewRecordsError, OSError, sqlite3.DatabaseError):
                            pass
                        else:
                            attempt_finished = True
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
                _write_capture_complete_marker(capture_dir)
                provider_result_saved = True
                stdout = process.stdout or ""
                stderr = process.stderr or ""
                artifacts = {
                    "cli_diagnostic": stderr,
                    "metadata": json.dumps(metadata, sort_keys=True),
                }
                try:
                    parsed_findings, _ = evidence._parse_capture_stdout(capture_dir / "stdout")
                except evidence.EvidenceError:
                    result_state = "rate_limited" if "rate limit exceeded" in stderr.casefold() else "failed"
                    artifacts["cli_raw_output"] = stdout
                    diagnostic = (
                        "CodeRabbit CLI was rate limited before a complete result"
                        if result_state == "rate_limited"
                        else "CodeRabbit CLI did not return a complete JSON review"
                    )
                else:
                    result_state = (
                        "completed"
                        if process.returncode == 0
                        else "rate_limited"
                        if "rate limit exceeded" in stderr.casefold()
                        else "failed"
                    )
                    artifacts["cli_events"] = stdout
                    diagnostic = (
                        ""
                        if result_state == "completed"
                        else "CodeRabbit CLI was rate limited"
                        if result_state == "rate_limited"
                        else "CodeRabbit CLI exited nonzero"
                    )
                command_exit_status = process.returncode
                if result_state != "completed" and command_exit_status == 0:
                    command_exit_status = 1
                if records is not None:
                    try:
                        if result_state == "completed":
                            completed_at = hosted.utc_now()
                            observations = []
                            for index, finding in enumerate(parsed_findings, 1):
                                instructions = finding.get("codegenInstructions")
                                title = _cli_finding_title(instructions, f"CodeRabbit CLI finding {index}")
                                observations.append(
                                    FindingObservation(
                                        source_finding_key=f"cli-run:{run_id}:finding:{index}",
                                        title=title,
                                        detail=_safe_finding_detail(_cli_detail(instructions)),
                                    )
                                )
                            records.complete_attempt_run(
                                run_id,
                                finish={
                                    "state": result_state,
                                    "finished_at": completed_at,
                                    "duration_seconds": duration,
                                    "exit_status": process.returncode,
                                    "diagnostic": diagnostic,
                                    "artifacts": artifacts,
                                },
                                run={
                                    "run_id": run_id,
                                    "source_pr": target.snapshot.number,
                                    "channel": "cli",
                                    "findings": observations,
                                    "source_head": candidate_sha,
                                    "reviewer": "CodeRabbit CLI",
                                    "scope": "broad",
                                    "started_at": attempt_started_at,
                                    "finished_at": completed_at,
                                },
                                finalize_empty=not observations,
                            )
                        else:
                            records.finish_attempt(
                                run_id,
                                state=result_state,
                                duration_seconds=duration,
                                exit_status=process.returncode,
                                diagnostic=diagnostic,
                                artifacts=artifacts,
                            )
                        attempt_finished = True
                    except (ReviewRecordsError, OSError, sqlite3.DatabaseError):
                        records_warning = (
                            "SQLite review record was not saved; the durable CLI capture remains available for recovery"
                        )
                return ReviewResult(
                    run_id=run_id,
                    pull_request=target.snapshot.number,
                    candidate_sha=candidate_sha,
                    parent_sha=live.base_sha if force else target.parent.head_sha,
                    merge_base=merge_base,
                    published_files=published_files,
                    candidate_files=candidate_files,
                    published_status=published_status,
                    provisional=False,
                    duration_seconds=duration,
                    exit_status=command_exit_status,
                    capture_dir=capture_dir,
                    warning=records_warning,
                    force_acknowledged=force,
                    candidate_warnings=target.candidate_warnings,
                    force_reason=reason,
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
            if records is not None and attempt_started and not attempt_finished and not provider_result_saved:
                try:
                    records.finish_attempt(
                        run_id,
                        state="failed",
                        diagnostic="CLI setup or capture failed",
                        artifacts={"cli_diagnostic": str(error)},
                    )
                except (ReviewRecordsError, OSError, sqlite3.DatabaseError) as archive_error:
                    # Keep the original provider failure while surfacing the
                    # separate archive failure to the caller.
                    add_note = getattr(error, "add_note", None)
                    if callable(add_note):
                        add_note(f"SQLite review-attempt archival also failed: {archive_error}")
            raise
        finally:
            try:
                if hosted_lock_handle is not None:
                    if hosted_lock_acquired:
                        fcntl.flock(hosted_lock_handle.fileno(), fcntl.LOCK_UN)
                    hosted_lock_handle.close()
            finally:
                try:
                    _clear_cli_lock_owner(lock_handle)
                except OSError:
                    # The owner marker is advisory cleanup. Preserve the
                    # completed provider result or primary failure, while
                    # still releasing the repository-wide execution lock.
                    pass
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
