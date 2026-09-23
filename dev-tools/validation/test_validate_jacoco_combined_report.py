#!/usr/bin/env python3
from __future__ import annotations

import importlib.util
import sys
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).with_name("validate-jacoco-combined-report.py")
SPEC = importlib.util.spec_from_file_location("validate_jacoco_combined_report", SCRIPT)
assert SPEC is not None and SPEC.loader is not None
VALIDATOR = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = VALIDATOR
SPEC.loader.exec_module(VALIDATOR)


def report_xml(unit_covered: int = 4, integration_covered: int = 7) -> str:
    return f"""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<report name="fixture">
  <package name="net/firedevops/firemud/common/saga">
    <class name="net/firedevops/firemud/common/saga/SagaRunner" sourcefilename="SagaRunner.java">
      <method name="run" desc="()V" line="1"><counter type="LINE" missed="1" covered="{unit_covered}"/></method>
      <counter type="LINE" missed="1" covered="{unit_covered}"/>
    </class>
  </package>
  <package name="net/firedevops/firemud/common/saga/persistence">
    <class name="net/firedevops/firemud/common/saga/persistence/SagaInstanceRepository" sourcefilename="SagaInstanceRepository.java">
      <method name="findAll" desc="()V" line="1"><counter type="LINE" missed="2" covered="{integration_covered}"/></method>
      <counter type="LINE" missed="2" covered="{integration_covered}"/>
    </class>
  </package>
</report>
"""


class CombinedCoverageTests(unittest.TestCase):
    def write_report(self, contents: str) -> Path:
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        path = Path(directory.name) / "jacocoTestReport.xml"
        path.write_text(contents, encoding="utf-8")
        return path

    def test_accepts_representative_unit_and_integration_covered_classes(self) -> None:
        report = self.write_report(report_xml())

        VALIDATOR.verify_required_coverage(
            report,
            [
                "net/firedevops/firemud/common/saga/SagaRunner",
                "net/firedevops/firemud/common/saga/persistence/SagaInstanceRepository",
            ],
            ["net/firedevops/firemud/common/saga/persistence/SagaInstanceRepository#findAll"],
        )

    def test_rejects_missing_or_uncovered_required_class(self) -> None:
        report = self.write_report(report_xml(integration_covered=0))

        with self.assertRaisesRegex(VALIDATOR.CoverageError, "SagaInstanceRepository"):
            VALIDATOR.verify_required_coverage(
                report,
                ["net/firedevops/firemud/common/saga/persistence/SagaInstanceRepository"],
                ["net/firedevops/firemud/common/saga/persistence/SagaInstanceRepository#findAll"],
            )
        with self.assertRaisesRegex(VALIDATOR.CoverageError, "MissingRepository"):
            VALIDATOR.verify_required_coverage(
                report,
                ["net/firedevops/firemud/common/saga/persistence/MissingRepository"],
                [],
            )

    def test_rejects_malformed_xml_and_duplicate_classes(self) -> None:
        malformed = self.write_report("<report>")
        with self.assertRaisesRegex(VALIDATOR.CoverageError, "could not read"):
            VALIDATOR.covered_lines_by_class(malformed)

        duplicate = self.write_report(
            report_xml().replace(
                "  </package>\n</report>",
                "    <class name=\"net/firedevops/firemud/common/saga/persistence/SagaInstanceRepository\">"
                "<counter type=\"LINE\" missed=\"0\" covered=\"1\"/></class>\n  </package>\n</report>",
            )
        )
        with self.assertRaisesRegex(VALIDATOR.CoverageError, "appears more than once"):
            VALIDATOR.covered_lines_by_class(duplicate)


if __name__ == "__main__":
    unittest.main()
