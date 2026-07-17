#!/usr/bin/env python3
"""Regression checks for structural design-allocation validation."""

from __future__ import annotations

import importlib.util
import shutil
import sys
import tempfile
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "dev-tools/validation/check-design-capability-allocation.py"


def load_validator():
    spec = importlib.util.spec_from_file_location("design_capability_allocation_validator", SCRIPT)
    if spec is None or spec.loader is None:
        raise AssertionError("could not load design allocation validator")
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


def fixture_root() -> tempfile.TemporaryDirectory[str]:
    fixture = tempfile.TemporaryDirectory()
    shutil.copytree(ROOT / "design", Path(fixture.name) / "design")
    return fixture


def expect_failure(label: str, validator, root: Path, expected: str) -> None:
    try:
        validator(root)
    except SystemExit as error:
        if expected not in str(error):
            raise AssertionError(f"{label}: unexpected failure: {error}") from error
    else:
        raise AssertionError(f"{label}: mutated fixture unexpectedly passed")


def main() -> None:
    validator = load_validator()
    with fixture_root() as directory:
        validator.validate(Path(directory))

    with fixture_root() as directory:
        path = Path(directory) / validator.SYSTEM_ALLOCATION
        text = path.read_text(encoding="utf-8")
        path.write_text(
            "\n".join(line for line in text.splitlines() if "[design/architecture/README.md]" not in line) + "\n",
            encoding="utf-8",
        )
        expect_failure("missing architecture ledger row", validator.validate, Path(directory), "source manifest mismatch")

    with fixture_root() as directory:
        path = Path(directory) / validator.TOP_ALLOCATION
        text = path.read_text(encoding="utf-8")
        path.write_text(text.replace("| **Total** | **184** |", "| **Total** | **183** |", 1), encoding="utf-8")
        expect_failure("architecture summary drift", validator.validate, Path(directory), "total discovered summary drift")

    print("design capability allocation regression tests passed")


if __name__ == "__main__":
    main()
