#!/usr/bin/env python3
"""Contract tests for architecture-document validation modes."""

from __future__ import annotations

import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "dev-tools/tests/architecture-doc-contracts.sh"
ARCHITECTURE_ALWAYS_RUN_COMMANDS = (
    "dev-tools/validation/check-design-capability-allocation.py",
    "dev-tools/validation/check-adr-review-status.py",
    "dev-tools/validation/check-implementation-capability-tracking.py",
    "dev-tools/validation/test_implementation_capability_tracking.py",
    "dev-tools/observability/check-metrics-cardinality.py",
    "dev-tools/validation/check-authz-route-matrix.py",
)
DISCOVERABLE_ARCHITECTURE_UNIT_TESTS = (
    "dev-tools/validation/test_design_capability_allocation.py",
    "dev-tools/validation/test_adr_review_status.py",
    "dev-tools/validation/test_check_metrics_cardinality.py",
    "dev-tools/validation/test_check_authz_route_matrix.py",
)

class ArchitectureDocContractsExecutionTest(unittest.TestCase):
    def run_contract(
        self, *arguments: str
    ) -> tuple[subprocess.CompletedProcess[str], list[list[str]]]:
        with tempfile.TemporaryDirectory() as temporary_directory:
            temporary_root = Path(temporary_directory)
            fake_bin = temporary_root / "bin"
            fake_bin.mkdir()
            call_log = temporary_root / "python-calls.jsonl"
            fake_python = fake_bin / "python3"
            fake_python.write_text(
                f"#!{sys.executable}\n"
                "import json\n"
                "import os\n"
                "import sys\n"
                "if sys.argv[1:] == ['-']:\n"
                "    sys.stdin.read()\n"
                "with open(\n"
                "    os.environ['ARCHITECTURE_DOC_CONTRACT_CALL_LOG'], 'a',\n"
                "    encoding='utf-8',\n"
                ") as output:\n"
                "    output.write(json.dumps(sys.argv[1:]) + chr(10))\n",
                encoding="utf-8",
            )
            fake_python.chmod(0o755)

            environment = os.environ.copy()
            environment["PATH"] = os.pathsep.join(
                (str(fake_bin), environment.get("PATH", ""))
            )
            environment["ARCHITECTURE_DOC_CONTRACT_CALL_LOG"] = str(call_log)
            completed = subprocess.run(
                ["bash", str(SCRIPT), *arguments],
                cwd=ROOT,
                env=environment,
                capture_output=True,
                check=False,
                text=True,
                timeout=15,
            )
            calls = []
            if call_log.exists():
                calls = [
                    json.loads(line)
                    for line in call_log.read_text(encoding="utf-8").splitlines()
                ]
            return completed, calls

    def test_default_mode_runs_discoverable_modules_and_procedural_regression(
        self,
    ) -> None:
        completed, calls = self.run_contract()

        self.assertEqual(0, completed.returncode, completed.stderr)
        expected_python_calls = [
            [path]
            for path in ARCHITECTURE_ALWAYS_RUN_COMMANDS
            + DISCOVERABLE_ARCHITECTURE_UNIT_TESTS
        ]
        self.assertEqual(
            [["-"], *expected_python_calls],
            calls,
        )

    def test_deferred_mode_keeps_procedural_regression_and_defers_discoverable_modules(
        self,
    ) -> None:
        completed, calls = self.run_contract("--defer-unit-tests")

        self.assertEqual(0, completed.returncode, completed.stderr)
        expected_python_calls = [
            [path] for path in ARCHITECTURE_ALWAYS_RUN_COMMANDS
        ]
        self.assertEqual([["-"], *expected_python_calls], calls)

    def test_invalid_modes_fail_before_running_contracts(self) -> None:
        for arguments in (("--skip-unit-tests",), ("--defer-unit-tests", "extra")):
            with self.subTest(arguments=arguments):
                completed, calls = self.run_contract(*arguments)

                self.assertEqual(2, completed.returncode)
                self.assertIn("Usage:", completed.stderr)
                self.assertEqual([], calls)


if __name__ == "__main__":
    unittest.main()
