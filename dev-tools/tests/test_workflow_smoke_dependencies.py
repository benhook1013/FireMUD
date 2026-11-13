"""Keep offline wire-test dependencies installed before their CI discovery."""

import unittest
from pathlib import Path

import yaml


class WorkflowSmokeDependenciesTest(unittest.TestCase):
    def test_full_contract_discovery_has_hash_locked_wire_dependencies(self):
        root = Path(__file__).resolve().parents[2]
        workflow = yaml.safe_load((root / ".github/workflows/ci.yml").read_text())
        steps = workflow["jobs"]["dev-tool-contract-checks"]["steps"]
        installs = [
            (i, step) for i, step in enumerate(steps) if step.get("name") == "Install bounded wire test dependencies"
        ]
        self.assertEqual(1, len(installs))
        index, install = installs[0]
        self.assertEqual("${{ needs.changes.outputs.lightweight_only != 'true' }}", install["if"])
        self.assertEqual(
            "python3 -m pip install --disable-pip-version-check --require-hashes -r config/python/smoke-requirements.txt",
            install["run"],
        )
        discoveries = [
            i
            for i, step in enumerate(steps)
            if "python3 -m unittest discover -s dev-tools/tests -p 'test_*.py'" in step.get("run", "")
        ]
        self.assertTrue(discoveries)
        self.assertTrue(all(index < discovery for discovery in discoveries))
        self.assertIn("websocket-client", (root / "config/python/smoke-requirements.txt").read_text())
        self.assertTrue(
            any(
                step.get("uses") == "./.github/actions/setup-python"
                and "'ci'" in step.get("with", {}).get("requirements", "")
                for step in steps[:index]
            )
        )


if __name__ == "__main__":
    unittest.main()
