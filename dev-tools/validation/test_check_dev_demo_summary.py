#!/usr/bin/env python3
"""Unit tests for the dev-demo workflow and summary validator."""

from __future__ import annotations

import importlib.util
import json
import sys
import tempfile
import unittest
import urllib.error
from contextlib import contextmanager, redirect_stderr, redirect_stdout
from io import BytesIO, StringIO
from pathlib import Path
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "dev-tools/validation/check_dev_demo_summary.py"


def load_validator():
    spec = importlib.util.spec_from_file_location("dev_demo_summary_validator", SCRIPT)
    if spec is None or spec.loader is None:
        raise AssertionError("could not load dev-demo summary validator")
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


class _FakeHttpResponse:
    status = 200

    def __enter__(self):
        return self

    def __exit__(self, *_args):
        return False

    def read(self):
        return b'{"status":"SUCCESS"}'


@contextmanager
def smoke_account_verifier():
    smoke_path = ROOT / "dev-tools/smoke"
    sys.path.insert(0, str(smoke_path))
    try:
        from smoke_common import verify_smoke_account

        yield verify_smoke_account
    finally:
        sys.path.remove(str(smoke_path))


class DevDemoSummaryValidatorTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.validator = load_validator()

    def _bootstrap_manifest_fixture(self) -> str:
        workflow = self.validator._load_workflow(ROOT)
        deploy_job = workflow["jobs"]["dev-demo-deploy"]
        return self.validator._find_step(
            deploy_job, "Create dev-demo smoke account"
        )["run"]

    def _bootstrap_manifest_with_pod_mutation(self, mutate) -> str:
        manifest = self._bootstrap_manifest_fixture()
        pod = self.validator._extract_bootstrap_pod(manifest)
        mutate(pod)
        rendered_pod = self.validator.yaml.safe_dump(pod, sort_keys=False).rstrip()
        manifest_start = manifest.index(
            self.validator.BOOTSTRAP_MANIFEST_HEREDOC_OPENER
        )
        content_start = manifest.index("\n", manifest_start) + 1
        content_end = manifest.index("\nEOF", content_start)
        return manifest[:content_start] + rendered_pod + manifest[content_end:]

    def _write_workflow_fixture(
        self,
        root: Path,
        bootstrap_manifest: str,
        summary_run: str = 'echo "safe summary" >> "$GITHUB_STEP_SUMMARY"',
        smoke_condition: str = "${{ !cancelled() }}",
    ) -> None:
        workflow = {
            "jobs": {
                "dev-demo-deploy": {
                    "steps": [
                        {
                            "name": "Create dev-demo smoke account",
                            "run": bootstrap_manifest,
                        },
                        {
                            "name": "Smoke dev-demo over TCP",
                            "if": smoke_condition,
                            "run": "echo smoke",
                        },
                        {
                            "name": "Summarize dev-demo access",
                            "run": summary_run,
                        },
                    ]
                }
            }
        }
        workflow_path = root / ".github/workflows/dev-demo.yml"
        workflow_path.parent.mkdir(parents=True)
        workflow_path.write_text(
            self.validator.yaml.safe_dump(workflow, sort_keys=False),
            encoding="utf-8",
        )

    def test_validate_workflow_accepts_valid_bootstrap_manifest_fixture(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self._write_workflow_fixture(root, self._bootstrap_manifest_fixture())
            self.validator.validate_workflow(root)

    def test_noop_bootstrap_manifest_mutation_preserves_valid_fixture(self):
        bootstrap_manifest = self._bootstrap_manifest_with_pod_mutation(lambda _pod: None)
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self._write_workflow_fixture(root, bootstrap_manifest)
            self.validator.validate_workflow(root)

    def test_validate_workflow_rejects_bootstrap_credentials_in_summary_writer(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self._write_workflow_fixture(
                root,
                self._bootstrap_manifest_fixture(),
                'echo "password=${DEMO_SMOKE_PASSWORD}" >> "$GITHUB_STEP_SUMMARY"',
            )
            with self.assertRaisesRegex(
                AssertionError,
                "dev-demo summaries must not reference bootstrap credential material; "
                "offending summary writers: dev-demo-deploy/Summarize dev-demo access",
            ):
                self.validator.validate_workflow(root)

    def test_validate_workflow_accepts_credential_free_session_pod(self):
        bootstrap_manifest = self._bootstrap_manifest_fixture()
        self.assertNotIn("envFrom:", bootstrap_manifest)
        self.assertNotIn("BOOTSTRAP_SECRET_DIR", bootstrap_manifest)
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self._write_workflow_fixture(root, bootstrap_manifest)
            self.validator.validate_workflow(root)

    def test_validate_workflow_rejects_bootstrap_credential_secret_creation(self):
        bootstrap_manifest = self._bootstrap_manifest_fixture()
        invalid_manifest = bootstrap_manifest + "\nkubectl -n \"${PREVIEW_NAMESPACE}\" create secret generic unrelated-resource"
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self._write_workflow_fixture(root, invalid_manifest)
            with self.assertRaisesRegex(AssertionError, "must not create or mount credential Secret"):
                self.validator.validate_workflow(root)

    def test_validate_workflow_rejects_any_bootstrap_secret_create_subcommand(self):
        bootstrap_manifest = self._bootstrap_manifest_fixture()
        invalid_manifest = bootstrap_manifest + (
            '\nkubectl -n "${PREVIEW_NAMESPACE}" create secret docker-registry unrelated-resource'
        )
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self._write_workflow_fixture(root, invalid_manifest)
            with self.assertRaisesRegex(
                AssertionError, "must not create or mount credential Secret"
            ):
                self.validator.validate_workflow(root)

    def test_validate_workflow_accepts_image_pull_secret_reference(self):
        bootstrap_manifest = self._bootstrap_manifest_with_pod_mutation(
            lambda pod: pod["spec"].update(
                imagePullSecrets=[{"name": "ghcr-preview-pull"}]
            )
        )
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self._write_workflow_fixture(root, bootstrap_manifest)
            self.validator.validate_workflow(root)

    def test_validate_workflow_rejects_legacy_player_bootstrap_payload(self):
        bootstrap_manifest = self._bootstrap_manifest_fixture()
        canonical_payload = (
            'public_account_url("/auth/player-bootstrap"),\n'
            "      {\n"
            '        "accountIdentifier": email,\n'
            '        "secret": password,\n'
            "      },"
        )
        legacy_payload = (
            'public_account_url("/auth/player-bootstrap"),\n'
            "      {\n"
            '        "tenantId": tenant_id,\n'
            '        "username": email,\n'
            '        "password": password,\n'
            "      },"
        )
        self.assertIn(canonical_payload, bootstrap_manifest)
        invalid_manifest = bootstrap_manifest.replace(
            canonical_payload, legacy_payload, 1
        )
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self._write_workflow_fixture(root, invalid_manifest)
            with self.assertRaisesRegex(
                AssertionError,
                "dev-demo bootstrap must send exactly accountIdentifier and secret",
            ):
                self.validator.validate_workflow(root)

    def test_validate_workflow_accepts_account_id_file_plumbing_for_session_handoff(self):
        bootstrap_manifest = self._bootstrap_manifest_fixture()
        account_id_markers = (
            '--from-file=account-id="${BOOTSTRAP_ACCOUNT_ID_FILE}"',
            "account_file.write(str(account_id))",
        )
        for marker in account_id_markers:
            self.assertIn(marker, self.validator.BOOTSTRAP_ACCOUNT_TRANSPORT_REQUIRED_MARKERS)
            self.assertIn(marker, bootstrap_manifest)
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self._write_workflow_fixture(root, bootstrap_manifest)
            self.validator.validate_workflow(root)

    def test_validate_workflow_requires_text_account_id_handoff(self):
        bootstrap_manifest = self._bootstrap_manifest_fixture()
        conversion = next(
            marker
            for marker in self.validator.BOOTSTRAP_ACCOUNT_TRANSPORT_REQUIRED_MARKERS
            if marker == "account_file.write(str(account_id))"
        )
        self.assertIn(conversion, bootstrap_manifest)
        invalid_manifest = bootstrap_manifest.replace(
            conversion, "account_file.write(account_id)", 1
        )
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self._write_workflow_fixture(root, invalid_manifest)
            with self.assertRaisesRegex(AssertionError, "port-forward transport.*missing"):
                self.validator.validate_workflow(root)

    def test_validate_workflow_rejects_multiple_player_bootstrap_requests(self):
        bootstrap_manifest = self._bootstrap_manifest_fixture()
        canonical_payload = (
            'public_account_url("/auth/player-bootstrap"),\n'
            "      {\n"
            '        "accountIdentifier": email,\n'
            '        "secret": password,\n'
            "      },"
        )
        self.assertIn(canonical_payload, bootstrap_manifest)
        invalid_manifest = bootstrap_manifest.replace(
            canonical_payload,
            f"{canonical_payload}\n{canonical_payload}",
            1,
        )
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self._write_workflow_fixture(root, invalid_manifest)
            with self.assertRaisesRegex(
                AssertionError,
                r"dev-demo bootstrap must contain exactly one /auth/player-bootstrap request \(found 2\)",
            ):
                self.validator.validate_workflow(root)

    def test_validate_workflow_accepts_reformatted_player_bootstrap_payload(self):
        bootstrap_manifest = self._bootstrap_manifest_fixture()
        canonical_payload = (
            'public_account_url("/auth/player-bootstrap"),\n'
            "      {\n"
            '        "accountIdentifier": email,\n'
            '        "secret": password,\n'
            "      },"
        )
        reformatted_payload = (
            "public_account_url ( '/auth/player-bootstrap' ),\n"
            "      {\n"
            "        'secret': password\n"
            "        , 'accountIdentifier': email\n"
            "      },"
        )
        self.assertIn(canonical_payload, bootstrap_manifest)
        reformatted_manifest = bootstrap_manifest.replace(
            canonical_payload, reformatted_payload, 1
        )
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self._write_workflow_fixture(root, reformatted_manifest)
            self.validator.validate_workflow(root)

    def test_validate_workflow_rejects_misbinding_player_bootstrap_fields(self):
        bootstrap_manifest = self._bootstrap_manifest_fixture()
        self.assertIn('"accountIdentifier": email,', bootstrap_manifest)
        invalid_manifest = bootstrap_manifest.replace(
            '"accountIdentifier": email,',
            '"accountIdentifier": password,',
            1,
        )
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self._write_workflow_fixture(root, invalid_manifest)
            with self.assertRaisesRegex(
                AssertionError,
                "dev-demo bootstrap must send exactly accountIdentifier and secret",
            ):
                self.validator.validate_workflow(root)

    def test_validate_workflow_rejects_unprotected_port_forward_transport(self):
        bootstrap_manifest = self._bootstrap_manifest_fixture()
        invalid_manifest = bootstrap_manifest.replace(
            "--address 127.0.0.1", "--address 0.0.0.0", 1
        )
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self._write_workflow_fixture(root, invalid_manifest)
            with self.assertRaisesRegex(
                AssertionError, "authenticated Kubernetes.*missing"
            ):
                self.validator.validate_workflow(root)

    def test_validate_workflow_rejects_unscoped_port_forward_authorization(self):
        bootstrap_manifest = self._bootstrap_manifest_fixture()
        scoped_check = 'kubectl auth can-i create pods/portforward -n "${PREVIEW_NAMESPACE}" >/dev/null'
        self.assertIn(scoped_check, bootstrap_manifest)
        invalid_manifest = bootstrap_manifest.replace(
            scoped_check, "kubectl auth can-i create pods/portforward >/dev/null", 1
        )
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self._write_workflow_fixture(root, invalid_manifest)
            with self.assertRaisesRegex(AssertionError, "port-forward transport.*missing"):
                self.validator.validate_workflow(root)

    def test_validate_workflow_rejects_wrong_port_forward_namespace_scope(self):
        bootstrap_manifest = self._bootstrap_manifest_fixture()
        scoped_check = 'kubectl auth can-i create pods/portforward -n "${PREVIEW_NAMESPACE}" >/dev/null'
        self.assertIn(scoped_check, bootstrap_manifest)
        invalid_manifest = bootstrap_manifest.replace(
            scoped_check, 'kubectl auth can-i create pods/portforward -n default >/dev/null', 1
        )
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self._write_workflow_fixture(root, invalid_manifest)
            with self.assertRaisesRegex(AssertionError, "port-forward transport.*missing"):
                self.validator.validate_workflow(root)

    def test_validate_workflow_rejects_non_gateway_endpoint(self):
        bootstrap_manifest = self._bootstrap_manifest_fixture()
        dynamic_assignment = 'BOOTSTRAP_GATEWAY_BASE_URL="http://127.0.0.1:${BOOTSTRAP_GATEWAY_PORT}"'
        self.assertIn(dynamic_assignment, bootstrap_manifest)
        invalid_manifest = bootstrap_manifest.replace(
            dynamic_assignment,
            'BOOTSTRAP_GATEWAY_BASE_URL="http://account-service:8080"',
            1,
        )
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self._write_workflow_fixture(root, invalid_manifest)
            with self.assertRaisesRegex(
                AssertionError, "authenticated Kubernetes.*missing"
            ):
                self.validator.validate_workflow(root)

    def test_validate_workflow_rejects_missing_gateway_endpoint(self):
        bootstrap_manifest = self._bootstrap_manifest_fixture()
        gateway_endpoint = 'BOOTSTRAP_GATEWAY_BASE_URL="http://127.0.0.1:${BOOTSTRAP_GATEWAY_PORT}"'
        self.assertIn(gateway_endpoint, bootstrap_manifest)
        invalid_manifest = bootstrap_manifest.replace(gateway_endpoint, "", 1)
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self._write_workflow_fixture(root, invalid_manifest)
            with self.assertRaisesRegex(
                AssertionError, "authenticated Kubernetes.*missing"
            ):
                self.validator.validate_workflow(root)

    def test_validate_workflow_rejects_split_bootstrap_mode(self):
        bootstrap_manifest = self._bootstrap_manifest_fixture()
        self.assertIn("value: session", bootstrap_manifest)
        invalid_manifest = bootstrap_manifest.replace("value: session", "value: account", 1)
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self._write_workflow_fixture(root, invalid_manifest)
            with self.assertRaisesRegex(
                AssertionError, "authenticated Kubernetes.*missing"
            ):
                self.validator.validate_workflow(root)

    def test_validate_workflow_rejects_account_mode_in_parsed_session_pod(self):
        invalid_manifest = self._bootstrap_manifest_with_pod_mutation(
            lambda pod: next(
                item
                for item in pod["spec"]["containers"][0]["env"]
                if item.get("name") == "BOOTSTRAP_MODE"
            ).update(value="account")
        )
        invalid_manifest += "\n# Preserve the raw transport marker: value: session"
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self._write_workflow_fixture(root, invalid_manifest)
            with self.assertRaisesRegex(
                AssertionError, "must run the noncredential session bootstrap"
            ):
                self.validator.validate_workflow(root)

    def test_validate_workflow_rejects_nonlist_bootstrap_container_env(self):
        invalid_manifest = self._bootstrap_manifest_with_pod_mutation(
            lambda pod: pod["spec"]["containers"][0].update(env={"name": "BOOTSTRAP_MODE"})
        )
        invalid_manifest += "\n# Preserve the raw transport marker: value: session"
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self._write_workflow_fixture(root, invalid_manifest)
            with self.assertRaisesRegex(
                AssertionError, r"spec\.containers\[0\]\.env must be a list"
            ):
                self.validator.validate_workflow(root)

    def test_validate_workflow_rejects_bootstrap_manifest_with_credential_env_from(self):
        invalid_manifest = self._bootstrap_manifest_with_pod_mutation(
            lambda pod: pod["spec"]["containers"][0].update(
                envFrom=[{"secretRef": {"name": "dev-demo-bootstrap-env"}}]
            )
        )
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self._write_workflow_fixture(root, invalid_manifest)
            with self.assertRaisesRegex(AssertionError, "must not import credential Secret env"):
                self.validator.validate_workflow(root)

    def test_validate_workflow_rejects_sidecar_credential_env_from(self):
        invalid_manifest = self._bootstrap_manifest_with_pod_mutation(
            lambda pod: pod["spec"]["containers"].append(
                {
                    "name": "sidecar",
                    "image": "python:3.12-alpine",
                    "envFrom": [{"secretRef": {"name": "bootstrap-secret"}}],
                }
            )
        )
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self._write_workflow_fixture(root, invalid_manifest)
            with self.assertRaisesRegex(AssertionError, "must not import credential Secret env"):
                self.validator.validate_workflow(root)

    def test_validate_workflow_rejects_init_container_credential_env_from(self):
        invalid_manifest = self._bootstrap_manifest_with_pod_mutation(
            lambda pod: pod["spec"].update(
                initContainers=[
                    {
                        "name": "init",
                        "image": "python:3.12-alpine",
                        "envFrom": [
                            {"secretRef": {"name": "bootstrap-secret"}}
                        ],
                    }
                ]
            )
        )
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self._write_workflow_fixture(root, invalid_manifest)
            with self.assertRaisesRegex(AssertionError, "must not import credential Secret env"):
                self.validator.validate_workflow(root)

    def test_validate_workflow_rejects_ephemeral_container_credential_env_from(self):
        invalid_manifest = self._bootstrap_manifest_with_pod_mutation(
            lambda pod: pod["spec"].update(
                ephemeralContainers=[
                    {
                        "name": "debugger",
                        "image": "python:3.12-alpine",
                        "envFrom": [
                            {"secretRef": {"name": "bootstrap-secret"}}
                        ],
                    }
                ]
            )
        )
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self._write_workflow_fixture(root, invalid_manifest)
            with self.assertRaisesRegex(AssertionError, "must not import credential Secret env"):
                self.validator.validate_workflow(root)

    def test_validate_workflow_rejects_secret_key_ref_in_container_env(self):
        invalid_manifest = self._bootstrap_manifest_with_pod_mutation(
            lambda pod: pod["spec"]["containers"][0]["env"].append(
                {
                    "name": "BOOTSTRAP_PASSWORD",
                    "valueFrom": {
                        "secretKeyRef": {
                            "name": "bootstrap-secret",
                            "key": "password",
                        }
                    },
                }
            )
        )
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self._write_workflow_fixture(root, invalid_manifest)
            with self.assertRaisesRegex(AssertionError, "must not import credential Secret env"):
                self.validator.validate_workflow(root)

    def test_validate_workflow_rejects_secret_volume(self):
        invalid_manifest = self._bootstrap_manifest_with_pod_mutation(
            lambda pod: pod["spec"]["volumes"].append(
                {
                    "name": "bootstrap-secret",
                    "secret": {"secretName": "bootstrap-secret"},
                }
            )
        )
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self._write_workflow_fixture(root, invalid_manifest)
            with self.assertRaisesRegex(
                AssertionError, "must not create or mount credential Secret"
            ):
                self.validator.validate_workflow(root)

    def test_validate_workflow_rejects_projected_secret_volume(self):
        invalid_manifest = self._bootstrap_manifest_with_pod_mutation(
            lambda pod: pod["spec"]["volumes"].append(
                {
                    "name": "projected-secret",
                    "projected": {
                        "sources": [
                            {"secret": {"name": "bootstrap-secret"}}
                        ]
                    },
                }
            )
        )
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self._write_workflow_fixture(root, invalid_manifest)
            with self.assertRaisesRegex(
                AssertionError, "must not contain Secret-bearing pod spec key"
            ):
                self.validator.validate_workflow(root)

    def test_validate_workflow_rejects_csi_secret_reference(self):
        invalid_manifest = self._bootstrap_manifest_with_pod_mutation(
            lambda pod: pod["spec"]["volumes"].append(
                {
                    "name": "csi-volume",
                    "csi": {
                        "driver": "example.csi.k8s.io",
                        "nodeStageSecretRef": {"name": "bootstrap-secret"},
                    },
                }
            )
        )
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self._write_workflow_fixture(root, invalid_manifest)
            with self.assertRaisesRegex(
                AssertionError, "must not contain Secret-bearing pod spec key"
            ):
                self.validator.validate_workflow(root)

    def test_validate_workflow_rejects_missing_automount_service_account_token(self):
        invalid_manifest = self._bootstrap_manifest_with_pod_mutation(
            lambda pod: pod["spec"].pop("automountServiceAccountToken")
        )
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self._write_workflow_fixture(root, invalid_manifest)
            with self.assertRaisesRegex(
                AssertionError, "must set automountServiceAccountToken: false"
            ):
                self.validator.validate_workflow(root)

    def test_validate_workflow_rejects_true_automount_service_account_token(self):
        invalid_manifest = self._bootstrap_manifest_with_pod_mutation(
            lambda pod: pod["spec"].update(automountServiceAccountToken=True)
        )
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self._write_workflow_fixture(root, invalid_manifest)
            with self.assertRaisesRegex(
                AssertionError, "must set automountServiceAccountToken: false"
            ):
                self.validator.validate_workflow(root)

    def test_validate_workflow_rejects_smoke_condition_without_cancellation_guard(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self._write_workflow_fixture(
                root,
                self._bootstrap_manifest_fixture(),
                smoke_condition="${{ success() }}",
            )
            with self.assertRaisesRegex(
                AssertionError,
                "dev-demo TCP smoke must still run after a non-cancellation bootstrap failure",
            ):
                self.validator.validate_workflow(root)

    def test_smoke_account_contract_reports_missing_script_path(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            missing = root / (
                "services/game-session-service/websocket-login-look-smoke.sh"
            )
            with self.assertRaises(AssertionError) as raised:
                self.validator._validate_smoke_account_contract(root)
        self.assertEqual(
            f"Smoke account contract script is missing: {missing}",
            str(raised.exception),
        )

    def test_smoke_account_contract_reports_missing_marker(self):
        missing_marker = (
            "verify_smoke_account(account_api_base, login_email, password, "
            "timeout_seconds)"
        )
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for relative_path in (
                "services/game-session-service/websocket-login-look-smoke.sh",
                "services/tcp-proxy-service/telnet-login-look-smoke.sh",
            ):
                script = root / relative_path
                script.parent.mkdir(parents=True, exist_ok=True)
                script.write_text(
                    'login_email = os.environ.get("SMOKE_LOGIN_EMAIL", '
                    'os.environ["DEMO_SMOKE_EMAIL"])\n',
                    encoding="utf-8",
                )
            with self.assertRaises(AssertionError) as raised:
                self.validator._validate_smoke_account_contract(root)
        self.assertIn(
            f"missing required account verification marker {missing_marker!r}",
            str(raised.exception),
        )

    def test_smoke_account_runtime_success_is_redacted_and_bounded(self):
        success_output = StringIO()
        with (
            smoke_account_verifier() as verify_smoke_account,
            patch(
                "smoke_common.urllib.request.urlopen",
                return_value=_FakeHttpResponse(),
            ) as urlopen,
            redirect_stdout(success_output),
        ):
            verify_smoke_account(
                "http://account.test", "demo@example.com", "swordfish", 5
            )
        login_request = urlopen.call_args.args[0]
        self.assertEqual(
            "http://account.test/auth/login",
            login_request.full_url,
            "smoke account verification must use the login endpoint",
        )
        self.assertEqual(
            {"username": "demo@example.com", "password": "swordfish"},
            json.loads(login_request.data),
            "smoke account verification must send the configured credentials",
        )
        self.assertNotIn(
            "SUCCESS",
            success_output.getvalue(),
            "response bodies must stay redacted",
        )
        self.assertIn(
            "status 200",
            success_output.getvalue(),
            "success status must be reported",
        )

    def test_smoke_account_runtime_redacts_http_failure(self):
        http_error = urllib.error.HTTPError(
            "http://account.test/auth/login",
            401,
            "Unauthorized",
            {},
            BytesIO(b'{"error":"sensitive upstream detail"}'),
        )
        failure_stdout = StringIO()
        failure_stderr = StringIO()
        with (
            smoke_account_verifier() as verify_smoke_account,
            patch(
                "smoke_common.urllib.request.urlopen",
                side_effect=http_error,
            ),
            redirect_stdout(failure_stdout),
            redirect_stderr(failure_stderr),
            self.assertRaises(RuntimeError) as raised,
        ):
            verify_smoke_account(
                "http://account.test", "demo@example.com", "swordfish", 5
            )
        self.assertEqual(
            "Smoke account validation failed with status 401",
            str(raised.exception),
            "HTTP failures must report only the response status",
        )
        self.assertNotIn(
            "sensitive upstream detail",
            str(raised.exception),
            "failure text leaked response body",
        )
        self.assertNotIn(
            "sensitive upstream detail",
            failure_stdout.getvalue(),
            "stdout leaked a failed response body",
        )
        self.assertNotIn(
            "sensitive upstream detail",
            failure_stderr.getvalue(),
            "stderr leaked a failed response body",
        )

    def test_smoke_account_runtime_retries_retryable_failures(self):
        retry_errors = [
            urllib.error.HTTPError(
                "http://account.test/auth/login",
                503,
                "Unavailable",
                {},
                BytesIO(b'{"error":"sensitive retry detail"}'),
            )
            for _ in range(2)
        ]
        retry_output = StringIO()
        with (
            smoke_account_verifier() as verify_smoke_account,
            patch(
                "smoke_common.urllib.request.urlopen",
                side_effect=retry_errors + [_FakeHttpResponse()],
            ) as retry_urlopen,
            patch("smoke_common.time.sleep") as sleep,
            redirect_stdout(retry_output),
        ):
            verify_smoke_account(
                "http://account.test", "demo@example.com", "swordfish", 5
            )
        self.assertEqual(
            3,
            retry_urlopen.call_count,
            "retryable failures must make three attempts",
        )
        self.assertEqual(
            [(1,), (1,)],
            [call.args for call in sleep.call_args_list],
            "smoke account retries must retain the bounded one-second delay",
        )
        self.assertNotIn(
            "sensitive retry detail",
            retry_output.getvalue(),
            "retry output leaked response body",
        )

    def test_smoke_account_runtime_exhausts_retryable_failures(self):
        exhausted_errors = [
            urllib.error.HTTPError(
                "http://account.test/auth/login",
                503,
                "Unavailable",
                {},
                BytesIO(b'{"error":"sensitive exhausted retry detail"}'),
            )
            for _ in range(3)
        ]
        exhausted_stdout = StringIO()
        exhausted_stderr = StringIO()
        with (
            smoke_account_verifier() as verify_smoke_account,
            patch(
                "smoke_common.urllib.request.urlopen",
                side_effect=exhausted_errors,
            ) as exhausted_urlopen,
            patch("smoke_common.time.sleep") as exhausted_sleep,
            redirect_stdout(exhausted_stdout),
            redirect_stderr(exhausted_stderr),
            self.assertRaises(RuntimeError) as raised,
        ):
            verify_smoke_account(
                "http://account.test", "demo@example.com", "swordfish", 5
            )
        self.assertEqual(
            "Smoke account validation failed with status 503",
            str(raised.exception),
            "exhausted HTTP retries must report only the response status",
        )
        self.assertNotIn(
            "sensitive exhausted retry detail",
            str(raised.exception),
            "exhausted HTTP retry failure leaked response body",
        )
        self.assertEqual(
            3,
            exhausted_urlopen.call_count,
            "exhausted retryable failures must stop after three attempts",
        )
        self.assertEqual(
            [(1,), (1,)],
            [call.args for call in exhausted_sleep.call_args_list],
            "exhausted retryable failures must sleep only between attempts",
        )
        self.assertNotIn(
            "sensitive exhausted retry detail",
            exhausted_stdout.getvalue(),
            "exhausted retry stdout leaked response body",
        )
        self.assertNotIn(
            "sensitive exhausted retry detail",
            exhausted_stderr.getvalue(),
            "exhausted retry stderr leaked response body",
        )

    def test_smoke_account_runtime_redacts_exhausted_transport_failures(self):
        transport_failure = "sensitive socket route detail"
        transport_errors = [OSError(transport_failure) for _ in range(3)]
        transport_stdout = StringIO()
        transport_stderr = StringIO()
        with (
            smoke_account_verifier() as verify_smoke_account,
            patch(
                "smoke_common.urllib.request.urlopen",
                side_effect=transport_errors,
            ) as transport_urlopen,
            patch("smoke_common.time.sleep") as transport_sleep,
            redirect_stdout(transport_stdout),
            redirect_stderr(transport_stderr),
            self.assertRaises(RuntimeError) as raised,
        ):
            verify_smoke_account(
                "http://account.test", "demo@example.com", "swordfish", 5
            )
        self.assertEqual(
            "Smoke account validation failed due to a transport error",
            str(raised.exception),
            "transport failure must use the redacted canonical message",
        )
        self.assertNotIn(
            transport_failure,
            str(raised.exception),
            "transport failure leaked raw exception details",
        )
        self.assertEqual(
            3,
            transport_urlopen.call_count,
            "transport failures must stop after three attempts",
        )
        self.assertEqual(
            [(1,), (1,)],
            [call.args for call in transport_sleep.call_args_list],
            "transport failures must sleep only between attempts",
        )
        self.assertNotIn(
            transport_failure,
            transport_stdout.getvalue(),
            "transport failure stdout leaked raw exception details",
        )
        self.assertNotIn(
            transport_failure,
            transport_stderr.getvalue(),
            "transport failure stderr leaked raw exception details",
        )

    def test_smoke_account_runtime_does_not_retry_non_retryable_http_failure(self):
        non_retryable = urllib.error.HTTPError(
            "http://account.test/auth/login",
            400,
            "Bad Request",
            {},
            BytesIO(b'{"error":"sensitive non-retryable detail"}'),
        )
        non_retryable_stdout = StringIO()
        non_retryable_stderr = StringIO()
        with (
            smoke_account_verifier() as verify_smoke_account,
            patch(
                "smoke_common.urllib.request.urlopen",
                side_effect=[non_retryable, _FakeHttpResponse()],
            ) as non_retryable_urlopen,
            redirect_stdout(non_retryable_stdout),
            redirect_stderr(non_retryable_stderr),
            self.assertRaises(RuntimeError) as raised,
        ):
            verify_smoke_account(
                "http://account.test", "demo@example.com", "swordfish", 5
            )
        self.assertEqual(
            "Smoke account validation failed with status 400",
            str(raised.exception),
            "non-retryable HTTP failures must report only the response status",
        )
        self.assertNotIn(
            "sensitive non-retryable detail",
            str(raised.exception),
            "non-retryable failure text leaked response body",
        )
        self.assertNotIn(
            "sensitive non-retryable detail",
            non_retryable_stdout.getvalue(),
            "non-retryable stdout leaked response body",
        )
        self.assertNotIn(
            "sensitive non-retryable detail",
            non_retryable_stderr.getvalue(),
            "non-retryable stderr leaked response body",
        )
        self.assertEqual(
            1,
            non_retryable_urlopen.call_count,
            "HTTP 400 must not be retried",
        )

    def test_shell_group_tokens_ignore_comments_quotes_and_expansions(self):
        fixtures = (
            ("{ echo safe # comment with } and )", ["{"]),
            ("{ echo safe#not-a-comment }", ["{", "}"]),
            ('{ echo "# not a comment }" }', ["{", "}"]),
            (r"{ echo \#not-a-comment }", ["{", "}"]),
            ("{ echo '\\' }", ["{", "}"]),
            ("{ echo ${value#pattern} }", ["{", "}"]),
            ('{ echo $(printf "# not a comment }") }', ["{", "}"]),
        )
        for fixture, expected in fixtures:
            with self.subTest(fixture=fixture):
                self.assertEqual(self.validator.shell_group_tokens(fixture), expected)


    def test_summary_helper_paths_allow_only_workspace_root_variables(self):
        validator = self.validator
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            expected = root / "dev-tools/tests/smoke-transport-contract.sh"
            fixtures = (
                "dev-tools/tests/smoke-transport-contract.sh",
                "./dev-tools/tests/smoke-transport-contract.sh",
                "bash dev-tools/tests/smoke-transport-contract.sh",
                "bash ./dev-tools/tests/smoke-transport-contract.sh",
                f"bash {root}/dev-tools/tests/smoke-transport-contract.sh",
                "$FIREMUD_REPO_ROOT/dev-tools/tests/smoke-transport-contract.sh",
                "$GITHUB_WORKSPACE/dev-tools/tests/smoke-transport-contract.sh",
                "${ROOT_DIR}/dev-tools/tests/smoke-transport-contract.sh",
            )
            for fixture in fixtures:
                with self.subTest(fixture=fixture):
                    matches = list(validator._helper_matches(fixture))
                    self.assertEqual(len(matches), 1)
                    self.assertEqual(
                        validator.normalize_summary_helper_path(
                            matches[0].group("invocation"), root
                        ),
                        expected.resolve(),
                    )
            unsupported = "$HOME/dev-tools/tests/smoke-transport-contract.sh"
            match = next(validator._helper_matches(unsupported))
            with self.assertRaisesRegex(ValueError, "unsupported variable-prefixed"):
                validator.normalize_summary_helper_path(match.group("invocation"), root)

            outside = root.parent / "dev-tools/tests/smoke-transport-contract.sh"
            with self.assertRaisesRegex(
                ValueError, "summary helper path escapes repository root"
            ):
                validator.discover_summary_writers(
                    [
                        validator.WorkflowRunSource(
                            "job",
                            "step",
                            f'bash {outside} >> "$GITHUB_STEP_SUMMARY"',
                        )
                    ],
                    root,
                )

    def test_summary_write_regions_preserve_group_and_heredoc_boundaries(self):
        validator = self.validator
        fixtures = (
            ('echo "safe summary" > "$GITHUB_STEP_SUMMARY"', False),
            ('echo "safe summary" | tee "$GITHUB_STEP_SUMMARY"', False),
            ('echo "safe summary" | tee -a "$GITHUB_STEP_SUMMARY"', False),
            (
                'echo "safe summary" | tee --append "$GITHUB_STEP_SUMMARY"',
                False,
            ),
            (
                (
                    'printf "%s" "$DEMO_SMOKE_PASSWORD" >/tmp/password\n'
                    'echo "safe summary" >> "$GITHUB_STEP_SUMMARY"'
                ),
                False,
            ),
            (
                '{\n  echo "unsafe: $DEMO_SMOKE_PASSWORD"\n} >> "$GITHUB_STEP_SUMMARY"',
                True,
            ),
            (
                (
                    "cat <<'SUMMARY_EOF' >> \"$GITHUB_STEP_SUMMARY\"\n"
                    "unsafe: $DEMO_SMOKE_PASSWORD\n"
                    "SUMMARY_EOF"
                ),
                True,
            ),
            (
                (
                    "cat <<-'SUMMARY_EOF' >> \"$GITHUB_STEP_SUMMARY\"\n"
                    "\tunsafe: $DEMO_SMOKE_PASSWORD\n"
                    "SUMMARY_EOF"
                ),
                True,
            ),
            (
                (
                    "cat >> \"$GITHUB_STEP_SUMMARY\" <<'SUMMARY_EOF'\n"
                    "unsafe: $DEMO_SMOKE_PASSWORD\n"
                    "SUMMARY_EOF"
                ),
                True,
            ),
            (
                (
                    "cat <<'SUMMARY_EOF' | tee \"$GITHUB_STEP_SUMMARY\"\n"
                    "unsafe: $DEMO_SMOKE_PASSWORD\n"
                    "SUMMARY_EOF"
                ),
                True,
            ),
            (
                '{ echo "unsafe: $DEMO_SMOKE_PASSWORD"; } >> "$GITHUB_STEP_SUMMARY"',
                True,
            ),
            (
                '( echo "unsafe: $DEMO_SMOKE_PASSWORD" ) >> "$GITHUB_STEP_SUMMARY"',
                True,
            ),
            (
                (
                    "cat <<'UNRELATED'\n"
                    "unsafe: $DEMO_SMOKE_PASSWORD\n"
                    "UNRELATED\n"
                    'echo "safe summary" >> "$GITHUB_STEP_SUMMARY"'
                ),
                False,
            ),
            (
                (
                    "unrelated() {\n"
                    '  echo "unsafe: $DEMO_SMOKE_PASSWORD"\n'
                    "}\n"
                    'echo "safe summary" >> "$GITHUB_STEP_SUMMARY"'
                ),
                False,
            ),
        )
        for fixture, unsafe in fixtures:
            with self.subTest(fixture=fixture):
                regions = validator.summary_write_regions(fixture)
                self.assertEqual(
                    any(
                        validator.has_forbidden_summary_reference(region)
                        for region in regions
                    ),
                    unsafe,
                )

    def test_forbidden_summary_reference_detects_secret_pipelines_without_crossing_commands(
        self,
    ):
        validator = self.validator
        secret_pipelines = (
            "kubectl get secret demo -o json | base64 -d",
            "kubectl get SECRETS demo -o json | jq -r .data.password | base64 --decode",
            "kubectl get sec demo -o json | tr -d '\\n' | base64 -d",
            "echo API_SECRET_TOKEN | base64 -d",
            "echo DB_CREDS | base64 -d",
            "echo DB_CREDS | base64 -di",
        )
        for fixture in secret_pipelines:
            with self.subTest(fixture=fixture):
                self.assertTrue(validator.has_forbidden_summary_reference(fixture))
        direct_references = (
            'echo "${BOOTSTRAP_SECRET_DIR}/password"',
            'echo "${{ secrets.DEMO_SMOKE_PASSWORD }}"',
            'echo "${{ steps.create-account.outputs.password }}"',
        )
        for fixture in direct_references:
            with self.subTest(fixture=fixture):
                self.assertTrue(validator.has_forbidden_summary_reference(fixture))
        safe = (
            "echo secret summary",
            "kubectl get secret demo -o json; cat encoded.txt | base64 -d",
            "kubectl get secret demo -o json\ncat encoded.txt | base64 -d",
            "kubectl get secret demo -o json | jq -r .metadata.name",
            "kubectl get secret demo -o json; echo base64 -d",
            "kubectl get secret demo -o json\nbase64 -d",
        )
        for fixture in safe:
            with self.subTest(fixture=fixture):
                self.assertFalse(validator.has_forbidden_summary_reference(fixture))

    def test_summary_reachability_propagates_through_redirected_helpers(self):
        validator = self.validator
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            helper_one = root / "dev-tools/summary-one.sh"
            helper_two = root / "dev-tools/summary-two.sh"
            helper_one.parent.mkdir(parents=True)
            helper_one.write_text(
                "#!/usr/bin/env bash\nbash dev-tools/summary-two.sh\n",
                encoding="utf-8",
            )
            helper_two.write_text(
                '#!/usr/bin/env bash\necho "unsafe: $DEMO_SMOKE_PASSWORD"\n',
                encoding="utf-8",
            )
            sources = [
                validator.WorkflowRunSource(
                    "job",
                    "step",
                    'bash dev-tools/summary-one.sh >> "$GITHUB_STEP_SUMMARY"',
                )
            ]
            writers = validator.discover_summary_writers(sources, root)
            self.assertEqual(len(writers), 3)
            reachable_helpers = [
                writer
                for writer in writers
                if writer.resolved_helper_path
                in {helper_one.resolve(), helper_two.resolve()}
            ]
            self.assertEqual(len(reachable_helpers), 2)
            self.assertTrue(all(writer.summary_reachable for writer in reachable_helpers))
            self.assertTrue(
                any(
                    validator.has_forbidden_summary_reference(writer.source)
                    for writer in reachable_helpers
                    if writer.summary_reachable
                )
            )

    def test_helper_cycles_terminate_without_repeating_resolved_paths(self):
        validator = self.validator
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            helper_one = root / "dev-tools/summary-one.sh"
            helper_two = root / "dev-tools/summary-two.sh"
            helper_one.parent.mkdir(parents=True)
            helper_one.write_text(
                "#!/usr/bin/env bash\nbash dev-tools/summary-two.sh\n",
                encoding="utf-8",
            )
            helper_two.write_text(
                "#!/usr/bin/env bash\nbash dev-tools/summary-one.sh\n",
                encoding="utf-8",
            )
            sources = [
                validator.WorkflowRunSource(
                    "job",
                    "step",
                    'bash dev-tools/summary-one.sh >> "$GITHUB_STEP_SUMMARY"',
                )
            ]
            writers = validator.discover_summary_writers(sources, root)
            self.assertEqual(len(writers), 3)
            resolved_helper_paths = [
                writer.resolved_helper_path
                for writer in writers
                if writer.resolved_helper_path is not None
            ]
            self.assertCountEqual(
                resolved_helper_paths, [helper_one.resolve(), helper_two.resolve()]
            )
            self.assertTrue(
                all(
                    writer.summary_reachable
                    for writer in writers
                    if writer.resolved_helper_path is not None
                )
            )

    def test_helper_reprocesses_when_reachability_is_upgraded(self):
        validator = self.validator
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            helper = root / "dev-tools/summary.sh"
            helper.parent.mkdir(parents=True)
            helper.write_text(
                '#!/usr/bin/env bash\necho "unsafe: $DEMO_SMOKE_PASSWORD"\n',
                encoding="utf-8",
            )
            sources = [
                validator.WorkflowRunSource(
                    "job",
                    "summary-step",
                    'bash dev-tools/summary.sh >> "$GITHUB_STEP_SUMMARY"',
                ),
                validator.WorkflowRunSource(
                    "job", "non-summary-step", "bash dev-tools/summary.sh"
                ),
            ]
            writers = validator.discover_summary_writers(sources, root)
            reachable_helpers = [
                writer
                for writer in writers
                if writer.resolved_helper_path == helper.resolve()
            ]
            self.assertEqual(len(reachable_helpers), 1)
            self.assertTrue(reachable_helpers[0].summary_reachable)

    def test_missing_helper_fails_closed_with_clear_assertion(self):
        validator = self.validator
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = [
                validator.WorkflowRunSource(
                    "job",
                    "step",
                    'bash dev-tools/missing-summary.sh >> "$GITHUB_STEP_SUMMARY"',
                )
            ]
            with self.assertRaisesRegex(
                AssertionError, "summary helper file is missing"
            ):
                validator.discover_summary_writers(source, root)

    def test_discovery_fails_closed_for_unsupported_variable_helper(self):
        validator = self.validator
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = [
                validator.WorkflowRunSource(
                    "job",
                    "step",
                    'bash "$UNTRUSTED_ROOT/dev-tools/summary.sh" >> "$GITHUB_STEP_SUMMARY"',
                )
            ]
            with self.assertRaisesRegex(ValueError, "unsupported variable-prefixed"):
                validator.discover_summary_writers(source, root)


if __name__ == "__main__":
    unittest.main()
