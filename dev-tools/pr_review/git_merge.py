"""Isolated Git merge proofs compatible with the repository's supported Git versions."""

from __future__ import annotations

import re
import subprocess
import tempfile
from collections.abc import Callable
from pathlib import Path
from typing import Any


class TestMergeError(RuntimeError):
    """A clean exact-base/head merge could not be proven safely."""


GitCommand = Callable[..., subprocess.CompletedProcess[Any]]


def test_merge_tree(
    root: str | Path,
    base: str,
    head: str,
    *,
    run: GitCommand,
    timeout_seconds: float,
) -> str:
    """Return the merged tree using a temporary detached worktree.

    Git 2.34 lacks ``merge-tree --write-tree``. A unique temporary worktree
    exercises the installed Git merge engine without touching the caller's
    checkout or any branch ref; cleanup is attempted even after timeout or
    conflict. Only the detached worktree's index and files are changed.
    """

    for value, label in ((base, "test-merge base"), (head, "test-merge head")):
        if not isinstance(value, str) or re.fullmatch(r"[0-9a-fA-F]{40}", value) is None:
            raise TestMergeError(f"{label} must be a full 40-character commit SHA")
    if timeout_seconds <= 0:
        raise TestMergeError("test-merge timeout must be positive")

    repository = Path(root).resolve()
    with tempfile.TemporaryDirectory(prefix="firemud-test-merge-") as temporary:
        temporary_root = Path(temporary)
        worktree = temporary_root / "worktree"
        hooks = temporary_root / "empty-hooks"
        hooks.mkdir()

        def invoke(directory: Path, *arguments: str, timeout: float = timeout_seconds):
            try:
                return run(
                    [
                        "git",
                        "-c",
                        f"core.hooksPath={hooks}",
                        "-C",
                        str(directory),
                        *arguments,
                    ],
                    check=False,
                    text=True,
                    timeout=timeout,
                )
            except subprocess.TimeoutExpired as error:
                raise TestMergeError(f"git test-merge command timed out after {timeout} seconds") from error

        add_attempted = False
        worktree_created = False
        primary_error: BaseException | None = None
        tree: str | None = None
        try:
            add_attempted = True
            added = invoke(repository, "worktree", "add", "--detach", "--quiet", str(worktree), base)
            if added.returncode != 0:
                raise TestMergeError("could not create an isolated detached test-merge worktree")
            worktree_created = True

            merged = invoke(
                worktree,
                "-c",
                "rerere.enabled=false",
                "merge",
                "--no-commit",
                "--no-ff",
                "--no-edit",
                "--strategy=ort",
                head,
            )
            if merged.returncode != 0:
                raise TestMergeError("current default base and PR head do not produce a clean test merge")
            written = invoke(worktree, "write-tree")
            if written.returncode != 0:
                raise TestMergeError("current default base and PR head test merge has an unresolved index")
            lines = [line.strip() for line in written.stdout.splitlines() if line.strip()]
            if not lines or re.fullmatch(r"[0-9a-fA-F]{40}", lines[0]) is None:
                raise TestMergeError("current default base and PR head test merge returned no tree")
            tree = lines[0].lower()
            tree_type = invoke(repository, "cat-file", "-t", tree)
            if tree_type.returncode != 0 or tree_type.stdout.strip() != "tree":
                raise TestMergeError("current default base and PR head test merge returned a non-tree object")
        except (OSError, subprocess.SubprocessError, TestMergeError, ValueError) as error:
            primary_error = error
        finally:
            if add_attempted:
                cleanup_timeout = min(max(timeout_seconds, 1.0), 10.0)
                removed = invoke(
                    repository,
                    "worktree",
                    "remove",
                    "--force",
                    str(worktree),
                    timeout=cleanup_timeout,
                )
                if worktree_created and removed.returncode != 0:
                    cleanup_error = TestMergeError("could not clean up the isolated test-merge worktree")
                    if primary_error is not None:
                        raise cleanup_error from primary_error
                    raise cleanup_error
        if primary_error is not None:
            raise primary_error
        if tree is None:
            raise TestMergeError("current default base and PR head test merge returned no tree")
        return tree
