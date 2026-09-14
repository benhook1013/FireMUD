#!/usr/bin/env python3
"""Validate runner labels used by preview workflow jobs."""

from __future__ import annotations

import sys
from collections.abc import Mapping, Sequence
from pathlib import Path

import yaml

_PREVIEW_LABELS = frozenset({"self-hosted", "preview"})
_PLATFORM_LABELS = frozenset({"linux", "x64"})


def normalize_runs_on(runs_on: object) -> set[str]:
    """Return labels from the supported list and mapping runs-on forms."""
    if isinstance(runs_on, Mapping):
        runs_on = runs_on.get("labels")
    if isinstance(runs_on, str):
        return {runs_on.lower()}
    if isinstance(runs_on, list):
        return {label.lower() for label in runs_on if isinstance(label, str)}
    return set()


def validate_preview_runner_labels(data: object, path: Path) -> None:
    """Require Linux x64 labels for self-hosted preview jobs."""
    if not isinstance(data, Mapping):
        return
    jobs = data.get("jobs")
    if not isinstance(jobs, Mapping):
        return
    for job_name, job in jobs.items():
        if not isinstance(job, Mapping):
            continue
        labels = normalize_runs_on(job.get("runs-on"))
        if _PREVIEW_LABELS.issubset(labels) and not _PLATFORM_LABELS.issubset(labels):
            raise ValueError(f"{path.name}:{job_name} preview runner must require linux and x64 labels")


def validate_workflow(path: Path) -> None:
    """Load and validate one workflow file."""
    data = yaml.safe_load(path.read_text(encoding="utf-8"))
    validate_preview_runner_labels(data, path)


def _run_fixtures() -> None:
    """Exercise positive and negative list/mapping runner fixtures."""
    complete_labels = ["self-hosted", "preview", "linux", "x64"]
    mixed_case_labels = ["SELF-HOSTED", "Preview", "LiNuX", "X64"]
    mixed_case_missing_platform = ["SeLf-HoStEd", "pReViEw", "LiNuX"]
    fixtures = (
        (
            "list-valid",
            {"jobs": {"preview": {"runs-on": complete_labels}}},
            True,
        ),
        (
            "list-invalid",
            {"jobs": {"preview": {"runs-on": ["self-hosted", "preview", "linux"]}}},
            False,
        ),
        (
            "list-mixed-case-valid",
            {"jobs": {"preview": {"runs-on": mixed_case_labels}}},
            True,
        ),
        (
            "list-mixed-case-invalid",
            {"jobs": {"preview": {"runs-on": mixed_case_missing_platform}}},
            False,
        ),
        (
            "mapping-list-valid",
            {"jobs": {"preview": {"runs-on": {"labels": complete_labels}}}},
            True,
        ),
        (
            "mapping-list-invalid",
            {"jobs": {"preview": {"runs-on": {"labels": ["self-hosted", "preview", "x64"]}}}},
            False,
        ),
        (
            "mapping-list-mixed-case-valid",
            {"jobs": {"preview": {"runs-on": {"labels": mixed_case_labels}}}},
            True,
        ),
        (
            "mapping-list-mixed-case-invalid",
            {"jobs": {"preview": {"runs-on": {"labels": mixed_case_missing_platform}}}},
            False,
        ),
        (
            "mapping-scalar-non-preview",
            {"jobs": {"other": {"runs-on": {"labels": "SeLf-HoStEd"}}}},
            True,
        ),
    )
    if normalize_runs_on({"labels": "PrEvIeW"}) != {"preview"}:
        raise AssertionError("mapping scalar labels were not lowercased")
    if normalize_runs_on(["SELF-HOSTED", 17, None]) != {"self-hosted"}:
        raise AssertionError("non-string list labels were not ignored")
    for name, fixture, expected_valid in fixtures:
        try:
            validate_preview_runner_labels(fixture, Path(f"{name}.yml"))
        except ValueError:
            if expected_valid:
                raise
        else:
            if not expected_valid:
                raise AssertionError(f"{name} fixture was accepted")


def main(arguments: Sequence[str] | None = None) -> int:
    args = list(sys.argv[1:] if arguments is None else arguments)
    if args == ["--self-test"]:
        _run_fixtures()
        return 0
    if not args:
        raise SystemExit("usage: preview_runner_labels.py [--self-test] WORKFLOW...")
    for path_text in args:
        validate_workflow(Path(path_text))
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except ValueError as error:
        raise SystemExit(str(error)) from error
