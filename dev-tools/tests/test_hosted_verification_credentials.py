"""Offline role-selection proof; never loads credentials or contacts Kubernetes."""

import os
import subprocess
import tempfile
import unittest
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parents[2]
WAITER = ROOT / "dev-tools/hosted/preview/wait-for-hosted-identity.sh"


class HostedVerificationCredentialsTest(unittest.TestCase):
    def setUp(self):
        workflow = yaml.safe_load((ROOT / ".github/workflows/hosted-identity-request.yml").read_text())
        self.job = workflow["jobs"]["verify-runtime"]
        self.steps = {step.get("name"): step for step in self.job["steps"]}

    def test_existing_protected_credentials_are_private_and_not_ambient(self):
        self.assertEqual("trusted-hosted-cluster", self.job["environment"])
        expected = {
            "Write runtime kubeconfig": "TRUSTED_HOSTED_PREVIEW_RUNTIME_KUBECONFIG",
            "Write verification namespace-manager kubeconfig": "TRUSTED_HOSTED_PREVIEW_NAMESPACE_MANAGER_KUBECONFIG",
            "Write verification identity-requester kubeconfig": "TRUSTED_HOSTED_IDENTITY_REQUESTER_KUBECONFIG",
        }
        for name, secret in expected.items():
            with self.subTest(name=name):
                step = self.steps[name]
                self.assertEqual("./.github/actions/write-kubeconfig", step["uses"])
                self.assertEqual("${{ secrets." + secret + " }}", step["with"]["content"])
                self.assertEqual("false", step["with"]["export-to-github-env"])
        self.assertEqual(
            "${{ needs.validate-target.outputs.certificate_identity_mode == 'hosted-controller' }}",
            self.steps["Write verification identity-requester kubeconfig"]["if"],
        )

    def test_each_existing_resource_read_selects_its_own_role(self):
        for name in (
            "Capture exact runtime Namespace UID before verification",
            "Publish trusted preview verification failure",
        ):
            self.assertEqual(
                "${{ runner.temp }}/preview-namespace-manager.kubeconfig", self.steps[name]["env"]["KUBECONFIG"]
            )
        for name in ("Wait for runtime rollouts", "Read allocated TCP port"):
            self.assertEqual("${{ runner.temp }}/preview-runtime.kubeconfig", self.steps[name]["env"]["KUBECONFIG"])
        env = self.steps["Wait for exact controller identity projection"]["env"]
        self.assertEqual("${{ runner.temp }}/preview-runtime.kubeconfig", env["KUBECONFIG"])
        self.assertEqual("${{ runner.temp }}/preview-namespace-manager.kubeconfig", env["NAMESPACE_KUBECONFIG"])
        self.assertEqual("${{ runner.temp }}/hosted-identity-requester.kubeconfig", env["IDENTITY_KUBECONFIG"])
        cleanup = self.steps["Remove runtime kubeconfig"]
        self.assertEqual("${{ always() }}", cleanup["if"])
        for name in ("preview-runtime", "preview-namespace-manager", "hosted-identity-requester"):
            self.assertIn(f"$RUNNER_TEMP/{name}.kubeconfig", cleanup["run"])

    def test_waiter_call_sites_select_control_plane_but_not_projection_reads(self):
        source = WAITER.read_text()
        self.assertIn('"${NAMESPACE_KUBECONFIG:-}" \\\n    get namespace', source)
        self.assertEqual(2, source.count('"${IDENTITY_KUBECONFIG:-}"'))
        self.assertIn('"" \\\n        -n "$runtime_namespace" get secret', source)

    def test_explicit_config_is_one_argument_and_default_preserves_runtime(self):
        source = WAITER.read_text()
        function = source.split("read_kubectl_json() {", 1)[1].split("\n}\n", 1)[0]
        function = "read_kubectl_json() {" + function + "\n}\n"
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            log = root / "argv"
            stub = root / "kubectl"
            stub.write_text('#!/bin/bash\nprintf "%s\\n" "$@" > "$ARG_LOG"\nprintf "{}"\n')
            stub.chmod(0o700)
            for config in ("", "/private/namespace role.config", "/private/identity.config"):
                with self.subTest(config=config):
                    env = {**os.environ, "PATH": str(root) + ":" + os.environ["PATH"], "ARG_LOG": str(log)}
                    script = (
                        "set -euo pipefail\n"
                        + function
                        + '\nkubectl_error_file="$1"\nvalue=\nretries=0\nread_kubectl_json value retries resource failed "$2" get namespace pr-1\n[[ "$value" == "{}" ]]'
                    )
                    result = subprocess.run(
                        ["bash", "-c", script, "credential-test", str(root / "err"), config],
                        env=env,
                        capture_output=True,
                        text=True,
                        timeout=5,
                        check=False,
                    )
                    self.assertEqual(0, result.returncode, result.stderr)
                    expected = (["--kubeconfig", config] if config else []) + ["get", "namespace", "pr-1"]
                    self.assertEqual(expected, log.read_text().splitlines())


if __name__ == "__main__":
    unittest.main()
