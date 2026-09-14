#!/usr/bin/env python3
"""Validate runner labels used by preview workflow jobs."""

from __future__ import annotations

import sys
from collections.abc import Mapping, Sequence
from pathlib import Path
from tempfile import TemporaryDirectory

import yaml

_PREVIEW_LABELS = frozenset({"self-hosted", "preview"})
_PLATFORM_LABELS = frozenset({"linux", "x64"})
_USAGE = "usage: preview_runner_labels.py [--self-test] WORKFLOW..."


def normalize_runs_on(runs_on: object) -> set[str]:
    """Return labels from the supported list and mapping runs-on forms."""
    if isinstance(runs_on, Mapping):
        runs_on = runs_on.get("labels")
    if isinstance(runs_on, str):
        return {runs_on.lower()}
    if isinstance(runs_on, list):
        return {label.lower() for label in runs_on if isinstance(label, str)}
    return set()


def validate_preview_runner_labels(data: object, path: Path) -> int:
    """Require Linux x64 labels and return matching preview jobs inspected."""
    if not isinstance(data, Mapping):
        return 0
    jobs = data.get("jobs")
    if not isinstance(jobs, Mapping):
        return 0
    inspected = 0
    for job_name, job in jobs.items():
        if not isinstance(job, Mapping):
            continue
        runs_on = job.get("runs-on")
        labels = normalize_runs_on(runs_on)
        if isinstance(runs_on, Mapping) and "group" in runs_on:
            if "labels" not in runs_on or not labels:
                raise ValueError(f"{path.name}:{job_name} self-hosted runner group must define labels")
            if "self-hosted" not in labels:
                raise ValueError(f"{path.name}:{job_name} self-hosted runner group must include the self-hosted label")
        if "self-hosted" not in labels:
            continue
        inspected += 1
        if not _PREVIEW_LABELS.issubset(labels):
            raise ValueError(f"{path.name}:{job_name} self-hosted preview runner must require the preview label")
        if not _PLATFORM_LABELS.issubset(labels):
            raise ValueError(f"{path.name}:{job_name} preview runner must require linux and x64 labels")
    return inspected


def validate_workflow(path: Path) -> int:
    """Load and validate one workflow file."""
    data = yaml.safe_load(path.read_text(encoding="utf-8"))
    return validate_preview_runner_labels(data, path)


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
            False,
        ),
        (
            "mapping-group-without-labels",
            {"jobs": {"preview": {"runs-on": {"group": "preview-runners"}}}},
            False,
        ),
        (
            "mapping-group-valid",
            {"jobs": {"preview": {"runs-on": {"group": "preview-runners", "labels": complete_labels}}}},
            True,
        ),
        (
            "list-self-hosted-wrong-label",
            {"jobs": {"preview": {"runs-on": ["self-hosted", "other", "linux", "x64"]}}},
            False,
        ),
        (
            "no-preview-jobs",
            {"jobs": {"hosted": {"runs-on": "ubuntu-latest"}}},
            True,
        ),
    )
    if normalize_runs_on({"labels": "PrEvIeW"}) != {"preview"}:
        raise AssertionError("mapping scalar labels were not lowercased")
    if normalize_runs_on(["SELF-HOSTED", 17, None]) != {"self-hosted"}:
        raise AssertionError("non-string list labels were not ignored")
    for name, fixture, expected_valid in fixtures:
        try:
            inspected = validate_preview_runner_labels(fixture, Path(f"{name}.yml"))
            if name == "no-preview-jobs" and inspected != 0:
                raise AssertionError("no-preview-jobs fixture inspected an unexpected job")
        except ValueError:
            if expected_valid:
                raise
        else:
            if not expected_valid:
                raise AssertionError(f"{name} fixture was accepted")
    with TemporaryDirectory() as temporary:
        no_preview = Path(temporary) / "no-preview.yml"
        no_preview.write_text(
            "jobs:\n  hosted:\n    runs-on: ubuntu-latest\n",
            encoding="utf-8",
        )
        try:
            main((str(no_preview),))
        except ValueError as error:
            if str(error) != "no-preview.yml: no preview runner jobs found":
                raise AssertionError(f"no-preview aggregate had unexpected diagnostic: {error}") from error
        else:
            raise AssertionError("no-preview aggregate was accepted")
        valid_preview = Path(temporary) / "valid-preview.yml"
        valid_preview.write_text(
            "jobs:\n  preview:\n    runs-on: [self-hosted, preview, linux, x64]\n",
            encoding="utf-8",
        )
        try:
            main((str(valid_preview), str(no_preview)))
        except ValueError as error:
            if str(error) != "no-preview.yml: no preview runner jobs found":
                raise AssertionError(f"mixed workflow arguments had unexpected diagnostic: {error}") from error
        else:
            raise AssertionError("workflow with no preview jobs was accepted alongside a valid workflow")
    for invalid_arguments in (("--unknown",), ("workflow.yml", "--self-test")):
        try:
            main(invalid_arguments)
        except SystemExit as error:
            if str(error) != _USAGE:
                raise AssertionError(f"invalid arguments returned unexpected usage: {error}") from error
        else:
            raise AssertionError(f"invalid arguments were accepted: {invalid_arguments}")


def main(arguments: Sequence[str] | None = None) -> int:
    args = list(sys.argv[1:] if arguments is None else arguments)
    if args == ["--self-test"]:
        _run_fixtures()
        return 0
    if not args or any(argument.startswith("--") for argument in args):
        raise SystemExit(_USAGE)
    for path_text in args:
        path = Path(path_text)
        if validate_workflow(path) == 0:
            raise ValueError(f"{path.name}: no preview runner jobs found")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except ValueError as error:
        raise SystemExit(str(error)) from error
