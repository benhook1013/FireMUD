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


def replace_once(path: Path, old: str, new: str) -> None:
    text = path.read_text(encoding="utf-8")
    if text.count(old) != 1:
        raise AssertionError(f"expected exactly one mutation target in {path}: {old!r}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


def replace_in_line(path: Path, marker: str, old: str, new: str) -> None:
    lines = path.read_text(encoding="utf-8").splitlines()
    matching_lines = [index for index, line in enumerate(lines) if marker in line]
    if len(matching_lines) != 1:
        raise AssertionError(f"expected exactly one line containing {marker!r} in {path}")
    index = matching_lines[0]
    if lines[index].count(old) != 1:
        raise AssertionError(f"expected exactly one mutation target in {path}: {old!r}")
    lines[index] = lines[index].replace(old, new, 1)
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")


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
        path = Path(directory) / validator.SYSTEM_ALLOCATION
        replace_in_line(path, "system-architecture-authentication.md", "| `AA-2` |", "| `SF-1` |")
        replace_once(path, "AA-2 4", "AA-2 3")
        replace_once(path, "SF-1 19", "SF-1 20")
        expect_failure(
            "system primary allocation drift with adjusted counts",
            validator.validate,
            Path(directory),
            "unexpected primary capability",
        )

    with fixture_root() as directory:
        path = Path(directory) / validator.SYSTEM_ALLOCATION
        replace_in_line(path, "system-architecture-authentication.md", "| normative design |", "| reference |")
        replace_once(path, "`56` normative design", "`55` normative design")
        replace_once(path, "`14` reference", "`15` reference")
        expect_failure(
            "system classification drift with adjusted counts",
            validator.validate,
            Path(directory),
            "unexpected source classification",
        )

    with fixture_root() as directory:
        path = Path(directory) / validator.TOP_ALLOCATION
        replace_once(path, "| **Total** | **184** |", "| **Total** | **183** |")
        expect_failure("architecture summary drift", validator.validate, Path(directory), "total discovered summary drift")

    with fixture_root() as directory:
        path = Path(directory) / validator.MICROSERVICE_ALLOCATION
        replace_once(
            path,
            "| Runtime-policy/configuration contract | Substantive settings authority",
            "| Invalid classification | Substantive settings authority",
        )
        expect_failure(
            "microservice classification drift",
            validator.validate,
            Path(directory),
            "unexpected source classification",
        )

    with fixture_root() as directory:
        path = Path(directory) / validator.MICROSERVICE_ALLOCATION
        replace_once(
            path,
            "`design/architecture/microservices/account-service/README.md` | `AA-1`",
            "`design/architecture/microservices/account-service/README.md` | `AA-2`",
        )
        expect_failure(
            "microservice primary drift",
            validator.validate,
            Path(directory),
            "unexpected primary capability",
        )

    with fixture_root() as directory:
        path = Path(directory) / validator.TOP_ALLOCATION
        replace_in_line(path, "`design/architecture/decisions/README.md`", "| Exempt |", "| `AS-1` |")
        replace_once(path, "| Architecture decisions | 12 | 11 |", "| Architecture decisions | 12 | 12 |")
        replace_once(path, "| Architecture decisions | 12 | 12 | 0; 1 registry exemption", "| Architecture decisions | 12 | 12 | 0")
        replace_once(path, "| **Total** | **184** | **181** |", "| **Total** | **184** | **182** |")
        replace_once(path, "| **Total** | **184** | **182** | **0; 3 explicit exemptions**", "| **Total** | **184** | **182** | **0; 2 explicit exemptions**")
        expect_failure(
            "ADR primary allocation drift with adjusted counts",
            validator.validate,
            Path(directory),
            "unexpected primary capability",
        )

    with fixture_root() as directory:
        path = Path(directory) / validator.TOP_ALLOCATION
        replace_in_line(path, "adr-0004-gameplay-reroute-vs-backend-unavailable.md", "| Superseded by ADR 0007 |", "| Accepted |")
        expect_failure(
            "ADR classification drift",
            validator.validate,
            Path(directory),
            "unexpected source classification",
        )

    with fixture_root() as directory:
        path = Path(directory) / validator.TOP_ALLOCATION
        text = path.read_text(encoding="utf-8")
        row = next(
            line
            for line in text.splitlines()
            if "design-capability-allocation-microservices.md" in line
        )
        replace_once(path, row, f"{row}\n{row}")
        expect_failure(
            "duplicate top-level allocation row",
            validator.validate,
            Path(directory),
            "duplicate allocation ledger row",
        )

    print("design capability allocation regression tests passed")


if __name__ == "__main__":
    main()
