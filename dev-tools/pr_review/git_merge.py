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
    """Return the exact merge tree, preferring Git's checkout-free merge path.

    Git 2.34 lacks ``merge-tree --write-tree``. For that version, a unique
    temporary detached worktree exercises the merge engine without touching
    the caller's checkout or any branch ref. LFS smudging is disabled during
    that fallback checkout. Cleanup is attempted even after timeout or conflict.
    """

    for value, label in ((base, "test-merge base"), (head, "test-merge head")):
        if not isinstance(value, str) or re.fullmatch(r"[0-9a-fA-F]{40}", value) is None:
            raise TestMergeError(f"{label} must be a full 40-character commit SHA")
    if timeout_seconds <= 0:
        raise TestMergeError("test-merge timeout must be positive")

    repository = Path(root).resolve()
    def invoke(directory: Path, *arguments: str, timeout: float = timeout_seconds):
        try:
            return run(
                ["git", "-C", str(directory), *arguments],
                check=False,
                text=True,
                timeout=timeout,
            )
        except subprocess.TimeoutExpired as error:
            raise TestMergeError(f"git test-merge command timed out after {timeout} seconds") from error

    # Probe the capability with a trivial self-merge so an unsupported option
    # is distinguished from a real conflict in the requested base/head merge.
    probe = invoke(repository, "merge-tree", "--write-tree", base, base)
    if probe.returncode == 0:
        probe_lines = [line.strip() for line in probe.stdout.splitlines() if line.strip()]
        if not probe_lines or re.fullmatch(r"[0-9a-fA-F]{40}", probe_lines[0]) is None:
            raise TestMergeError("git merge-tree capability probe returned no tree")
        probe_tree = probe_lines[0].lower()
        probe_type = invoke(repository, "cat-file", "-t", probe_tree)
        if probe_type.returncode != 0 or probe_type.stdout.strip() != "tree":
            raise TestMergeError("git merge-tree capability probe returned a non-tree object")
        merged = invoke(repository, "merge-tree", "--write-tree", base, head)
        if merged.returncode != 0:
            raise TestMergeError("current default base and PR head do not produce a clean test merge")
        lines = [line.strip() for line in merged.stdout.splitlines() if line.strip()]
        if not lines or re.fullmatch(r"[0-9a-fA-F]{40}", lines[0]) is None:
            raise TestMergeError("current default base and PR head test merge returned no tree")
        tree = lines[0].lower()
        tree_type = invoke(repository, "cat-file", "-t", tree)
        if tree_type.returncode != 0 or tree_type.stdout.strip() != "tree":
            raise TestMergeError("current default base and PR head test merge returned a non-tree object")
        return tree

    with tempfile.TemporaryDirectory(prefix="firemud-test-merge-") as temporary:
        temporary_root = Path(temporary)
        worktree = temporary_root / "worktree"
        hooks = temporary_root / "empty-hooks"
        hooks.mkdir()

        def invoke_fallback(directory: Path, *arguments: str, timeout: float = timeout_seconds):
            try:
                return run(
                    [
                        "git",
                        "-c",
                        f"core.hooksPath={hooks}",
                        "-c",
                        "filter.lfs.process=",
                        "-c",
                        "filter.lfs.smudge=cat",
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
            added = invoke_fallback(repository, "worktree", "add", "--detach", "--quiet", str(worktree), base)
            if added.returncode != 0:
                raise TestMergeError("could not create an isolated detached test-merge worktree")
            worktree_created = True

            merged = invoke_fallback(
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
            written = invoke_fallback(worktree, "write-tree")
            if written.returncode != 0:
                raise TestMergeError("current default base and PR head test merge has an unresolved index")
            lines = [line.strip() for line in written.stdout.splitlines() if line.strip()]
            if not lines or re.fullmatch(r"[0-9a-fA-F]{40}", lines[0]) is None:
                raise TestMergeError("current default base and PR head test merge returned no tree")
            tree = lines[0].lower()
            tree_type = invoke_fallback(repository, "cat-file", "-t", tree)
            if tree_type.returncode != 0 or tree_type.stdout.strip() != "tree":
                raise TestMergeError("current default base and PR head test merge returned a non-tree object")
        except (OSError, subprocess.SubprocessError, TestMergeError, ValueError) as error:
            primary_error = error
        finally:
            if add_attempted:
                cleanup_timeout = min(max(timeout_seconds, 1.0), 10.0)
                removed = invoke_fallback(
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
