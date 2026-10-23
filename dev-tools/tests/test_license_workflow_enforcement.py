#!/usr/bin/env python3
"""Contract tests for fail-closed ORT analyzer completion in the license gate."""

from __future__ import annotations

import shlex
import subprocess
import unittest
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parents[2]
WORKFLOW = ROOT / ".github/workflows/license-scan.yml"
ANALYZER_EXIT_ENV = "ORT_CLI_ANALYZE_EXIT_CODE"


def workflow_data() -> dict:
    return yaml.safe_load(WORKFLOW.read_text(encoding="utf-8"))


def step_by_id(job: dict, step_id: str) -> dict:
    return next(step for step in job["steps"] if step.get("id") == step_id)


def step_by_name(job: dict, name: str) -> dict:
    return next(step for step in job["steps"] if step.get("name") == name)


class LicenseWorkflowEnforcementTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.jobs = workflow_data()["jobs"]

    def test_both_scans_use_the_actions_supported_violation_gate(self) -> None:
        for job_name in ("ort-gradle", "ort-npm"):
            with self.subTest(job=job_name):
                ort_step = step_by_id(self.jobs[job_name], "ort")
                self.assertEqual("violations", ort_step["with"]["fail-on"])

    def test_analyzer_guards_execute_fail_closed_for_both_scans(self) -> None:
        cases = (("0", 0), ("2", 1), (None, 1), ("malformed", 1))
        for job_name, guard_id in (
            ("ort-gradle", "gradle_analyzer_guard"),
            ("ort-npm", "npm_analyzer_guard"),
        ):
            guard = step_by_id(self.jobs[job_name], guard_id)
            self.assertEqual("bash", guard["shell"])
            script = guard["run"]
            self.assertIn(ANALYZER_EXIT_ENV, script)

            for value, expected_exit in cases:
                with self.subTest(job=job_name, analyzer_exit=value):
                    environment = (
                        {}
                        if value is None
                        else {ANALYZER_EXIT_ENV: value}
                    )
                    result = subprocess.run(
                        [
                            "/bin/bash",
                            "--noprofile",
                            "--norc",
                            "-e",
                            "-o",
                            "pipefail",
                            "-c",
                            script,
                        ],
                        cwd=ROOT,
                        env=environment,
                        capture_output=True,
                        check=False,
                        text=True,
                        timeout=5,
                    )
                    self.assertEqual(expected_exit, result.returncode, result.stderr)

    def test_repository_metadata_is_selected_and_applied_in_ci(self) -> None:
        gradle_inputs = step_by_id(self.jobs["ort-gradle"], "ort")["with"]
        docker_args = shlex.split(gradle_inputs["docker-cli-args"])
        self.assertIn(["-e", "CI=true"], [docker_args[i:i + 2] for i in range(len(docker_args))])
        for job_name in ("ort-gradle", "ort-npm"):
            with self.subTest(job=job_name):
                inputs = step_by_id(self.jobs[job_name], "ort")["with"]
                args = shlex.split(inputs["docker-cli-args"].replace("${{ github.workspace }}", "/workspace/FireMUD"))
                mounts = [args[i + 1] for i, arg in enumerate(args[:-1]) if arg == "--mount"]
                self.assertIn(
                    "type=bind,source=/workspace/FireMUD/config/ort/curations,"
                    "target=/home/ort/.ort/config/curations/firemud,readonly",
                    mounts,
                )
        npm_inputs = step_by_id(self.jobs["ort-npm"], "ort")["with"]
        self.assertEqual("${{ github.workspace }}/config/ort/npm.ort.yml", npm_inputs["ort-yml-path"])
        self.assertIn(
            "--mount type=bind,source=${{ github.workspace }}/config/ort/npm.ort.yml,"
            "target=${{ github.workspace }}/config/ort/npm.ort.yml,readonly",
            npm_inputs["docker-cli-args"],
        )
        self.assertNotIn("ort-yml-path", gradle_inputs)
        self.assertFalse((ROOT / ".ort.yml").exists())
        self.assertNotIn("curations", yaml.safe_load((ROOT / "config/ort/npm.ort.yml").read_text()))
        filters = yaml.safe_load(step_by_id(self.jobs["changes"], "filter")["with"]["filters"])
        for ecosystem in ("gradle", "npm"):
            with self.subTest(ecosystem=ecosystem):
                self.assertIn("config/ort/curations/**", filters[ecosystem])
        self.assertIn("config/ort/npm.ort.yml", filters["npm"])
        self.assertNotIn("config/ort/npm.ort.yml", filters["gradle"])

    def test_guard_failures_retain_results_and_gradle_diagnostics(self) -> None:
        gradle = self.jobs["ort-gradle"]
        npm = self.jobs["ort-npm"]

        result_step_name = "Upload ORT results, including partial analysis"
        gradle_results = step_by_name(gradle, result_step_name)
        npm_results = step_by_name(npm, result_step_name)
        expected_results_condition = "${{ always() && steps.ort.outcome != 'skipped' }}"
        for upload in (gradle_results, npm_results):
            with self.subTest(upload=upload["name"]):
                self.assertEqual(expected_results_condition, upload["if"])
                self.assertEqual("error", upload["with"]["if-no-files-found"])

        diagnostic = step_by_id(gradle, "gradle_ort_diagnostic")
        diagnostic_upload = step_by_name(
            gradle, "Upload Gradle ORT connectivity diagnostics"
        )
        for step in (diagnostic, diagnostic_upload):
            condition = step["if"]
            self.assertIn("steps.ort.outcome == 'failure'", condition)
            self.assertIn("steps.gradle_analyzer_guard.outcome == 'failure'", condition)


if __name__ == "__main__":
    unittest.main()
