"""Deterministic patch identity shared by controller and CLI preflight."""

from __future__ import annotations

import hashlib
from collections.abc import Callable, Sequence

_PATCH_DIFF_OPTIONS = (
    "--binary",
    "--full-index",
    "--no-ext-diff",
    "--no-textconv",
    "--no-color",
    "--no-renames",
    "--diff-algorithm=myers",
    "--no-indent-heuristic",
    "--unified=3",
    "--inter-hunk-context=0",
    "--src-prefix=a/",
    "--dst-prefix=b/",
    "--no-relative",
    "--ignore-submodules=none",
    "-O/dev/null",
)


def patch_diff_args(merge_base: str, head: str) -> tuple[str, ...]:
    """Return the fixed Git command arguments for the anchored patch."""

    return (
        "-c",
        "core.quotePath=true",
        "-c",
        "diff.compactionHeuristic=false",
        "-c",
        "diff.suppressBlankEmpty=false",
        "diff",
        *_PATCH_DIFF_OPTIONS,
        f"{merge_base}...{head}",
    )


def patch_identity(
    run_git: Callable[[Sequence[str]], bytes | str],
    merge_base: str,
    head: str,
) -> str:
    """Run the canonical binary diff and hash its exact output bytes."""

    output = run_git(patch_diff_args(merge_base, head))
    if isinstance(output, str):
        output = output.encode("utf-8")
    return hashlib.sha256(output).hexdigest()
