#!/usr/bin/env python3
"""Regression checks for structural design-allocation validation."""

from __future__ import annotations

import importlib.util
import shutil
import sys
import tempfile
import unittest
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


def expect_call_failure(label: str, call, expected: str) -> None:
    try:
        call()
    except SystemExit as error:
        if expected not in str(error):
            raise AssertionError(f"{label}: unexpected failure: {error}") from error
    else:
        raise AssertionError(f"{label}: invalid input unexpectedly passed")


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


def summary_row_counts(path: Path, marker: str) -> tuple[int, int]:
    lines = [line for line in path.read_text(encoding="utf-8").splitlines() if marker in line]
    if len(lines) != 1:
        raise AssertionError(f"expected exactly one summary row containing {marker!r} in {path}")
    cells = [cell.strip().strip("*") for cell in lines[0].strip().strip("|").split("|")]
    return int(cells[1]), int(cells[2])


class DesignCapabilityAllocationRegressionTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.validator = load_validator()

    def test_valid_fixture(self) -> None:
        with fixture_root() as directory:
            self.validator.validate(Path(directory))

    def test_missing_architecture_ledger_row(self) -> None:
        with fixture_root() as directory:
            root = Path(directory)
            path = root / self.validator.SYSTEM_ALLOCATION
            text = path.read_text(encoding="utf-8")
            path.write_text(
                "\n".join(
                    line
                    for line in text.splitlines()
                    if "[design/architecture/README.md]" not in line
                )
                + "\n",
                encoding="utf-8",
            )
            expect_call_failure(
                "missing architecture ledger row",
                lambda: self.validator.validate(root),
                "source manifest mismatch",
            )

    def test_system_primary_allocation_drift_with_adjusted_counts(self) -> None:
        with fixture_root() as directory:
            root = Path(directory)
            path = root / self.validator.SYSTEM_ALLOCATION
            replace_in_line(path, "system-architecture-authentication.md", "| `AA-2` |", "| `SF-1` |")
            replace_once(path, "AA-2 4", "AA-2 3")
            replace_once(path, "SF-1 19", "SF-1 20")
            expect_call_failure(
                "system primary allocation drift with adjusted counts",
                lambda: self.validator.validate(root),
                "unexpected primary capability",
            )

    def test_system_classification_drift_with_adjusted_counts(self) -> None:
        with fixture_root() as directory:
            root = Path(directory)
            path = root / self.validator.SYSTEM_ALLOCATION
            replace_in_line(path, "system-architecture-authentication.md", "| normative design |", "| reference |")
            replace_once(path, "`56` normative design", "`55` normative design")
            replace_once(path, "`14` reference", "`15` reference")
            expect_call_failure(
                "system classification drift with adjusted counts",
                lambda: self.validator.validate(root),
                "unexpected source classification",
            )

    def test_architecture_summary_drift(self) -> None:
        with fixture_root() as directory:
            root = Path(directory)
            path = root / self.validator.TOP_ALLOCATION
            discovered, _ = summary_row_counts(path, "| **Total** |")
            replace_once(
                path,
                f"| **Total** | **{discovered}** |",
                f"| **Total** | **{discovered - 1}** |",
            )
            expect_call_failure(
                "architecture summary drift",
                lambda: self.validator.validate(root),
                "total discovered summary drift",
            )

    def test_duplicate_primary_allocation_count_claim(self) -> None:
        with fixture_root() as directory:
            root = Path(directory)
            path = root / self.validator.SYSTEM_ALLOCATION
            replace_once(
                path,
                "Primary allocation counts are: `AA-1 0`",
                "Primary allocation counts are: `AA-1 1`, `AA-1 0`",
            )
            expect_call_failure(
                "duplicate primary allocation count claim",
                lambda: self.validator.validate(root),
                "duplicate primary allocation count claim entries",
            )

    def test_duplicate_classification_count_claim(self) -> None:
        with fixture_root() as directory:
            root = Path(directory)
            path = root / self.validator.SYSTEM_ALLOCATION
            replace_once(
                path,
                "Classification counts are: `56` normative design",
                "Classification counts are: `55` normative design, `56` normative design",
            )
            expect_call_failure(
                "duplicate classification count claim",
                lambda: self.validator.validate(root),
                "duplicate classification count claim entries",
            )

    def test_microservice_classification_drift(self) -> None:
        with fixture_root() as directory:
            root = Path(directory)
            path = root / self.validator.MICROSERVICE_ALLOCATION
            replace_once(
                path,
                "| Runtime-policy/configuration contract | Substantive settings authority",
                "| Invalid classification | Substantive settings authority",
            )
            expect_call_failure(
                "microservice classification drift",
                lambda: self.validator.validate(root),
                "unexpected source classification",
            )

    def test_microservice_primary_drift(self) -> None:
        with fixture_root() as directory:
            root = Path(directory)
            path = root / self.validator.MICROSERVICE_ALLOCATION
            replace_once(
                path,
                "`design/architecture/microservices/account-service/README.md` | `AA-1`",
                "`design/architecture/microservices/account-service/README.md` | `AA-2`",
            )
            expect_call_failure(
                "microservice primary drift",
                lambda: self.validator.validate(root),
                "unexpected primary capability",
            )

    def test_adr_primary_allocation_drift_with_adjusted_counts(self) -> None:
        with fixture_root() as directory:
            root = Path(directory)
            path = root / self.validator.TOP_ALLOCATION
            adr_discovered, adr_allocated = summary_row_counts(path, "| Architecture decisions |")
            total_discovered, total_allocated = summary_row_counts(path, "| **Total** |")
            replace_in_line(path, "`design/architecture/decisions/README.md`", "| Exempt |", "| `AS-1` |")
            replace_once(
                path,
                f"| Architecture decisions | {adr_discovered} | {adr_allocated} |",
                f"| Architecture decisions | {adr_discovered} | {adr_allocated + 1} |",
            )
            replace_once(
                path,
                f"| Architecture decisions | {adr_discovered} | {adr_allocated + 1} | 0; 1 registry exemption",
                f"| Architecture decisions | {adr_discovered} | {adr_allocated + 1} | 0",
            )
            replace_once(
                path,
                f"| **Total** | **{total_discovered}** | **{total_allocated}** |",
                f"| **Total** | **{total_discovered}** | **{total_allocated + 1}** |",
            )
            replace_once(
                path,
                f"| **Total** | **{total_discovered}** | **{total_allocated + 1}** | **0; 3 explicit exemptions**",
                f"| **Total** | **{total_discovered}** | **{total_allocated + 1}** | **0; 2 explicit exemptions**",
            )
            expect_call_failure(
                "ADR primary allocation drift with adjusted counts",
                lambda: self.validator.validate(root),
                "unexpected primary capability",
            )

    def test_adr_classification_drift(self) -> None:
        with fixture_root() as directory:
            root = Path(directory)
            path = root / self.validator.TOP_ALLOCATION
            replace_in_line(path, "adr-0004-gameplay-reroute-vs-backend-unavailable.md", "| Superseded by ADR 0007 |", "| Accepted |")
            expect_call_failure(
                "ADR classification drift",
                lambda: self.validator.validate(root),
                "unexpected source classification",
            )

    def test_duplicate_top_level_allocation_row(self) -> None:
        with fixture_root() as directory:
            root = Path(directory)
            path = root / self.validator.TOP_ALLOCATION
            text = path.read_text(encoding="utf-8")
            row = next(
                line
                for line in text.splitlines()
                if "design-capability-allocation-microservices.md" in line
            )
            replace_once(path, row, f"{row}\n{row}")
            expect_call_failure(
                "duplicate top-level allocation row",
                lambda: self.validator.validate(root),
                "duplicate allocation ledger row",
            )

    def test_stale_microservice_umbrella_count(self) -> None:
        self.assert_stale_umbrella_count(
            "stale microservice umbrella count",
            "Microservice architecture",
            "design/architecture/microservices/example/new-source.md",
        )

    def test_stale_system_umbrella_count(self) -> None:
        self.assert_stale_umbrella_count(
            "stale system umbrella count",
            "Top-level architecture",
            "design/architecture/new-source.md",
        )

    def test_stale_adr_umbrella_count(self) -> None:
        self.assert_stale_umbrella_count(
            "stale ADR umbrella count",
            "Architecture decisions",
            "design/architecture/decisions/adr-9999-example.md",
        )

    def assert_stale_umbrella_count(self, label: str, source_class: str, added_path: str) -> None:
        with fixture_root() as directory:
            root = Path(directory)
            source_sets = self.validator.repository_files(root)
            source_sets[source_class].add(added_path)
            expect_call_failure(
                label,
                lambda: self.validator.validate_top_allocation_ledger(
                    root,
                    self.validator.group_ids(root),
                    source_sets["Architecture decisions"],
                    source_sets,
                ),
                "allocation row drift",
            )

    def test_trailing_contradictory_exemption_claim(self) -> None:
        expect_call_failure(
            "trailing contradictory exemption claim",
            lambda: self.validator.parse_explicit_exemptions(
                "0; 3 explicit exemptions; 99 gaps",
                "fixture",
            ),
            "malformed gap/exemption count",
        )

    def test_duplicate_exact_allocation_section(self) -> None:
        expect_call_failure(
            "duplicate exact allocation section",
            lambda: self.validator.section(
                "## Allocation Ledger\n\n## Allocation Ledger\n",
                "Allocation Ledger",
            ),
            "expected exactly one section",
        )

    def test_non_exact_allocation_section(self) -> None:
        expect_call_failure(
            "non-exact allocation section",
            lambda: self.validator.section("## Allocation Ledger Notes\n", "Allocation Ledger"),
            "expected exactly one section",
        )

    def test_duplicate_matching_allocation_table(self) -> None:
        duplicate_table = """
## Allocation Ledger

| Design source | Heading or scope | Primary capability |
| --- | --- | --- |
| one | scope | primary |

| Design source | Heading or scope | Primary capability |
| --- | --- | --- |
| two | scope | primary |
"""
        expect_call_failure(
            "duplicate matching allocation table",
            lambda: self.validator.table_in_section(
                duplicate_table,
                "Allocation Ledger",
                {"Design source", "Heading or scope", "Primary capability"},
            ),
            "expected exactly one table",
        )


if __name__ == "__main__":
    unittest.main(verbosity=2)
