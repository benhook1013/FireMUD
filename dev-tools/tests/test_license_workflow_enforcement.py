#!/usr/bin/env python3
"""Contract tests for fail-closed ORT analyzer completion in the license gate."""

from __future__ import annotations

import hashlib
import re
import shlex
import subprocess
import unittest
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parents[2]
WORKFLOW = ROOT / ".github/workflows/license-scan.yml"
ADVISORY_WORKFLOW = ROOT / ".github/workflows/ort-advisory.yml"
ANALYZER_EXIT_ENV = "ORT_CLI_ANALYZE_EXIT_CODE"
GATLING_LICENSE_REF = "LicenseRef-Gatling-Highcharts-3.16.0"
GATLING_LICENSE_PATH = ROOT / "config/ort/custom-license-texts" / GATLING_LICENSE_REF
GATLING_LICENSE_SHA256 = "cadfc4af543cb6702bbf3aeedbf896c372b923f6ce7bf4d5e79cc40feb3c9681"


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
        self.assertEqual(
            "${{ github.workspace }}/config/ort/gatling.ort.yml",
            gradle_inputs["ort-yml-path"],
        )
        self.assertIn(
            "--mount type=bind,source=${{ github.workspace }}/config/ort/gatling.ort.yml,"
            "target=${{ github.workspace }}/config/ort/gatling.ort.yml,readonly",
            gradle_inputs["docker-cli-args"],
        )
        self.assertFalse((ROOT / ".ort.yml").exists())
        self.assertNotIn("curations", yaml.safe_load((ROOT / "config/ort/npm.ort.yml").read_text()))
        gradle_config = yaml.safe_load(
            (ROOT / "config/ort/gatling.ort.yml").read_text(encoding="utf-8")
        )
        npm_config = yaml.safe_load(
            (ROOT / "config/ort/npm.ort.yml").read_text(encoding="utf-8")
        )
        self.assertNotIn("license_choices", gradle_config)
        self.assertIn("license_choices", npm_config)
        filters = yaml.safe_load(step_by_id(self.jobs["changes"], "filter")["with"]["filters"])
        for ecosystem in ("gradle", "npm"):
            with self.subTest(ecosystem=ecosystem):
                self.assertIn("config/ort/curations/**", filters[ecosystem])
        self.assertIn("config/ort/npm.ort.yml", filters["npm"])
        self.assertNotIn("config/ort/npm.ort.yml", filters["gradle"])
        self.assertIn("config/ort/gatling.ort.yml", filters["gradle"])
        self.assertNotIn("config/ort/gatling.ort.yml", filters["npm"])

    def test_custom_license_text_is_routed_mounted_and_curated_exactly(self) -> None:
        custom_license_mount = (
            "type=bind,source=/workspace/FireMUD/config/ort/custom-license-texts/"
            f"{GATLING_LICENSE_REF},target=/home/ort/.ort/config/custom-license-texts/"
            f"{GATLING_LICENSE_REF},readonly"
        )
        for workflow_path in (WORKFLOW, ADVISORY_WORKFLOW):
            workflow = yaml.safe_load(workflow_path.read_text(encoding="utf-8"))
            if workflow_path == WORKFLOW:
                ort_steps = [step_by_id(workflow["jobs"]["ort-gradle"], "ort")]
            else:
                ort_steps = [
                    step
                    for job in workflow["jobs"].values()
                    for step in job.get("steps", [])
                    if step.get("id") == "ort"
                ]
            with self.subTest(workflow=workflow_path.name):
                self.assertTrue(ort_steps)
                for step in ort_steps:
                    args = shlex.split(
                        step["with"]["docker-cli-args"].replace(
                            "${{ github.workspace }}", "/workspace/FireMUD"
                        )
                    )
                    mounts = [
                        args[index + 1]
                        for index, arg in enumerate(args[:-1])
                        if arg == "--mount"
                    ]
                    self.assertIn(custom_license_mount, mounts)

        filters = yaml.safe_load(
            step_by_id(self.jobs["changes"], "filter")["with"]["filters"]
        )
        self.assertIn("config/ort/custom-license-texts/**", filters["gradle"])

        curations = yaml.safe_load(
            (ROOT / "config/ort/curations/firemud.yml").read_text(encoding="utf-8")
        )
        gatling = [
            curation
            for curation in curations
            if curation["id"]
            == "Maven:io.gatling.highcharts:gatling-charts-highcharts:3.16.0"
        ]
        self.assertEqual(1, len(gatling))
        self.assertEqual(
            {"Gatling Highcharts": GATLING_LICENSE_REF},
            gatling[0]["curations"]["declared_license_mapping"],
        )
        self.assertEqual(
            GATLING_LICENSE_SHA256,
            hashlib.sha256(GATLING_LICENSE_PATH.read_bytes()).hexdigest(),
        )

    def test_gatling_resolution_matches_only_the_observed_violation(self) -> None:
        observed_message = (
            "The license LicenseRef-Gatling-Highcharts-3.16.0 is currently not "
            "covered by policy rules. The license was declared in package "
            "'Maven:io.gatling.highcharts:gatling-charts-highcharts:3.16.0'."
        )
        rejected_messages = {
            "altered package": observed_message.replace(
                "Maven:io.gatling.highcharts", "Maven:io.example.highcharts"
            ),
            "altered version": observed_message.replace(
                "gatling-charts-highcharts:3.16.0",
                "gatling-charts-highcharts:3.16.1",
            ),
            "altered license": observed_message.replace(
                GATLING_LICENSE_REF, "LicenseRef-Gatling-Highcharts-3.16.1"
            ),
            "other error": observed_message.replace(
                "currently not covered by policy rules",
                "is prohibited by policy rules",
            ),
            "message suffix": observed_message + " Additional failure details.",
        }
        config_paths = (
            ROOT / "config/ort/gatling.ort.yml",
            ROOT / "config/ort/npm.ort.yml",
        )

        for config_path in config_paths:
            config = yaml.safe_load(config_path.read_text(encoding="utf-8"))
            resolutions = config["resolutions"]["rule_violations"]
            with self.subTest(config=config_path.name):
                self.assertEqual(1, len(resolutions))
                resolution = resolutions[0]
                self.assertEqual("NOT_MODIFIED_EXCEPTION", resolution["reason"])
                pattern = resolution["message"]
                self.assertIsNotNone(re.fullmatch(pattern, observed_message, re.DOTALL))
                for case, message in rejected_messages.items():
                    with self.subTest(rejected=case):
                        self.assertIsNone(re.fullmatch(pattern, message, re.DOTALL))

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
