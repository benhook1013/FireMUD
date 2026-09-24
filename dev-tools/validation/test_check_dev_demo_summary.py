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

    def _write_workflow_fixture(
        self,
        root: Path,
        bootstrap_manifest: str,
        summary_run: str | None = None,
        smoke_condition: str = "${{ success() }}",
    ) -> None:
        helper_path = root / "dev-tools/hosted/dev-demo/write-dev-demo-summary.sh"
        helper_path.parent.mkdir(parents=True)
        helper_path.write_text(
            (
                ROOT / "dev-tools/hosted/dev-demo/write-dev-demo-summary.sh"
            ).read_text(encoding="utf-8"),
            encoding="utf-8",
        )
        if summary_run is None:
            summary_run = (
                'bash ./dev-tools/hosted/dev-demo/write-dev-demo-summary.sh \\\n'
                "  success \\\n"
                '  "${{ needs.dev-demo-plan.outputs.head_sha }}" \\\n'
                '  "${{ needs.dev-demo-plan.outputs.image_tag }}" \\\n'
                '  "${{ needs.dev-demo-plan.outputs.hostname }}" \\\n'
                '  "${{ needs.dev-demo-plan.outputs.telnet_port }}" >> "$GITHUB_STEP_SUMMARY"'
            )
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
                "unsupported or indirect summary syntax",
            ):
                self.validator.validate_workflow(root)

    def test_validate_workflow_accepts_reformatted_bootstrap_secret_command(self):
        bootstrap_manifest = self._bootstrap_manifest_fixture()
        canonical_command = (
            'kubectl -n "${PREVIEW_NAMESPACE}" create secret generic '
            "dev-demo-bootstrap-env \\\n"
            '  --from-file=DEMO_SMOKE_EMAIL="${BOOTSTRAP_SECRET_DIR}/email" \\\n'
            '  --from-file=DEMO_SMOKE_PASSWORD="${BOOTSTRAP_SECRET_DIR}/password" \\\n'
            '  --from-file=DEMO_SMOKE_USERNAME="${BOOTSTRAP_SECRET_DIR}/username"'
        )
        reformatted_command = (
            'kubectl  -n "${PREVIEW_NAMESPACE}" create secret generic '
            "dev-demo-bootstrap-env \\\n"
            '  --from-file=DEMO_SMOKE_USERNAME="${BOOTSTRAP_SECRET_DIR}/username" \\\n'
            '  --from-file=DEMO_SMOKE_EMAIL="${BOOTSTRAP_SECRET_DIR}/email" \\\n'
            '  --from-file=DEMO_SMOKE_PASSWORD="${BOOTSTRAP_SECRET_DIR}/password"'
        )
        self.assertIn(canonical_command, bootstrap_manifest)
        reformatted_manifest = bootstrap_manifest.replace(
            canonical_command, reformatted_command, 1
        )
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self._write_workflow_fixture(root, reformatted_manifest)
            self.validator.validate_workflow(root)

    def test_validate_workflow_reports_extra_bootstrap_secret_command_options(self):
        bootstrap_manifest = self._bootstrap_manifest_fixture()
        command_end = (
            '--from-file=DEMO_SMOKE_USERNAME="${BOOTSTRAP_SECRET_DIR}/username"'
        )
        self.assertIn(command_end, bootstrap_manifest)
        invalid_manifest = bootstrap_manifest.replace(
            command_end, f"{command_end} \\\n--dry-run=client", 1
        )
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self._write_workflow_fixture(root, invalid_manifest)
            with self.assertRaisesRegex(
                AssertionError, "only --from-file arguments are allowed"
            ):
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

    def test_validate_workflow_rejects_non_text_account_id_write(self):
        bootstrap_manifest = self._bootstrap_manifest_fixture()
        self.assertIn("account_file.write(str(account_id))", bootstrap_manifest)
        invalid_manifest = bootstrap_manifest.replace(
            "account_file.write(str(account_id))",
            "account_file.write(account_id)",
            1,
        )
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self._write_workflow_fixture(root, invalid_manifest)
            with self.assertRaisesRegex(
                AssertionError,
                "dev-demo bootstrap must write the account id as text",
            ):
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
        canonical_request = (
            "status, bootstrap_body = post_json(\n"
            "      "
            + canonical_payload
            + "\n    )"
        )
        self.assertIn(canonical_request, bootstrap_manifest)
        duplicate_request = canonical_request.replace(
            "status, bootstrap_body", "duplicate_status, duplicate_body", 1
        )
        invalid_manifest = bootstrap_manifest.replace(
            canonical_request,
            f"{canonical_request}\n    {duplicate_request}",
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

    def test_validate_workflow_does_not_count_fake_bootstrap_call_comment_or_string(self):
        bootstrap_manifest = self._bootstrap_manifest_fixture()
        canonical_payload = (
            'public_account_url("/auth/player-bootstrap"),\n'
            "      {\n"
            '        "accountIdentifier": email,\n'
            '        "secret": password,\n'
            "      },"
        )
        fake_source = (
            '# public_account_url("/auth/player-bootstrap")\n'
            'fake = "public_account_url(\\"/auth/player-bootstrap\\")"'
        )
        self.assertIn(canonical_payload, bootstrap_manifest)
        invalid_manifest = bootstrap_manifest.replace(canonical_payload, fake_source, 1)
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self._write_workflow_fixture(root, invalid_manifest)
            with self.assertRaisesRegex(
                AssertionError,
                r"dev-demo bootstrap must contain exactly one /auth/player-bootstrap request \(found 0\)",
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

    def test_validate_workflow_rejects_fixed_player_bootstrap_port(self):
        bootstrap_manifest = self._bootstrap_manifest_fixture()
        dynamic_forward = "service/spring-cloud-gateway \\\n  :80 \\\n"
        self.assertIn(dynamic_forward, bootstrap_manifest)
        invalid_manifest = bootstrap_manifest.replace(
            dynamic_forward,
            'service/spring-cloud-gateway \\\n  "${BOOTSTRAP_GATEWAY_PORT}:80" \\\n',
            1,
        )
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self._write_workflow_fixture(root, invalid_manifest)
            with self.assertRaisesRegex(
                AssertionError, "dynamic :80 local-port syntax"
            ):
                self.validator.validate_workflow(root)

    def test_validate_workflow_rejects_literal_bootstrap_port_assignment(self):
        bootstrap_manifest = self._bootstrap_manifest_fixture()
        dynamic_assignment = "BOOTSTRAP_GATEWAY_PORT="
        dynamic_forward = "service/spring-cloud-gateway \\\n  :80 \\\n"
        self.assertIn(dynamic_assignment, bootstrap_manifest)
        self.assertIn(dynamic_forward, bootstrap_manifest)
        invalid_manifest = bootstrap_manifest.replace(
            dynamic_assignment, "BOOTSTRAP_GATEWAY_PORT=12345", 1
        )
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self._write_workflow_fixture(root, invalid_manifest)
            with self.assertRaisesRegex(
                AssertionError, "dynamically selected local port"
            ):
                self.validator.validate_workflow(root)

    def test_validate_workflow_rejects_missing_pre_python_liveness_check(self):
        bootstrap_manifest = self._bootstrap_manifest_fixture()
        liveness_check = (
            'if ! kill -0 "${BOOTSTRAP_PORT_FORWARD_PID}" >/dev/null 2>&1; then'
        )
        self.assertEqual(2, bootstrap_manifest.count(liveness_check))
        invalid_manifest = bootstrap_manifest.replace(liveness_check, "", 1)
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self._write_workflow_fixture(root, invalid_manifest)
            with self.assertRaisesRegex(
                AssertionError, "recheck port-forward liveness before invoking Python"
            ):
                self.validator.validate_workflow(root)

    def test_validate_workflow_rejects_unbounded_port_forward_readiness(self):
        bootstrap_manifest = self._bootstrap_manifest_fixture()
        self.assertIn("for attempt in {1..30}; do", bootstrap_manifest)
        invalid_manifest = bootstrap_manifest.replace(
            "for attempt in {1..30}; do", "while true; do", 1
        )
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self._write_workflow_fixture(root, invalid_manifest)
            with self.assertRaisesRegex(
                AssertionError, "short bounded port-forward readiness loop"
            ):
                self.validator.validate_workflow(root)

    def test_validate_workflow_rejects_bootstrap_manifest_without_env_from(self):
        bootstrap_manifest = self._bootstrap_manifest_fixture()
        self.assertIn("envFrom:", bootstrap_manifest)
        invalid_manifest = bootstrap_manifest.replace(
            "envFrom:", "missingEnvFrom:", 1
        )
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self._write_workflow_fixture(root, invalid_manifest)
            with self.assertRaisesRegex(
                AssertionError,
                "dev-demo bootstrap pod must import dev-demo-bootstrap-env",
            ):
                self.validator.validate_workflow(root)

    def test_validate_workflow_rejects_optional_smoke_success_or_guard(self):
        for smoke_condition in (
            "${{ success() || steps.cluster-access.outputs.available == 'true' }}",
            "${{ steps.cluster-access.outputs.available == 'true' && success() }}",
        ):
            with self.subTest(
                smoke_condition=smoke_condition
            ), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                self._write_workflow_fixture(
                    root,
                    self._bootstrap_manifest_fixture(),
                    smoke_condition=smoke_condition,
                )
                with self.assertRaisesRegex(
                    AssertionError,
                    r"dev-demo TCP smoke must use .*leading .* guard",
                ):
                    self.validator.validate_workflow(root)

    def test_validate_workflow_accepts_not_equal_smoke_condition(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self._write_workflow_fixture(
                root,
                self._bootstrap_manifest_fixture(),
                smoke_condition=(
                    "${{ success() && "
                    "steps.cluster-access.outputs.available != 'true' }}"
                ),
            )
            self.validator.validate_workflow(root)

    def test_validate_workflow_rejects_negated_smoke_success_guard(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self._write_workflow_fixture(
                root,
                self._bootstrap_manifest_fixture(),
                smoke_condition="${{ !success() && steps.cluster-access.outputs.available == 'true' }}",
            )
            with self.assertRaisesRegex(
                AssertionError,
                r"dev-demo TCP smoke must use .*leading .* guard",
            ):
                self.validator.validate_workflow(root)

    def test_validate_workflow_rejects_redundant_smoke_cancellation_guard(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self._write_workflow_fixture(
                root,
                self._bootstrap_manifest_fixture(),
                smoke_condition="${{ success() && !cancelled() }}",
            )
            with self.assertRaisesRegex(
                AssertionError,
                r"dev-demo TCP smoke must use .*leading .* guard",
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

    def test_cleanup_parsers_handle_nested_groups_and_reject_unsupported_if_forms(self):
        validator = self.validator
        nested_if = [
            'if rm -rf "${BOOTSTRAP_SECRET_DIR}"; then',
            'if [[ -n "${BOOTSTRAP_SECRET_DIR}" ]]; then',
            "true",
            "fi",
            "return 0",
            "fi",
        ]
        self.assertEqual(validator.closing_fi_index(nested_if, 0), 5)
        nested_group = [
            "cleanup_bootstrap_temp_dir() {",
            "{",
            'if [[ -n "${BOOTSTRAP_SECRET_DIR}" ]]; then',
            "true",
            "fi",
            "}",
            "}",
        ]
        self.assertEqual(
            validator._cleanup_function_end_index(nested_group, 0), len(nested_group)
        )
        for fixture in (["if true", "then", "fi"], ["if true; then echo inline; fi"]):
            with (
                self.subTest(fixture=fixture),
                self.assertRaisesRegex(AssertionError, "unsupported shell if form"),
            ):
                validator.closing_fi_index(fixture, 0)
        self.assertIsNone(
            validator.closing_fi_index(
                ['if rm -rf "${BOOTSTRAP_SECRET_DIR}"; then', "true"], 0
            )
        )
        self.assertIsNone(
            validator._cleanup_function_end_index(
                ["cleanup_bootstrap_temp_dir() {", "true"], 0
            )
        )

    def test_summary_write_regions_accept_only_canonical_direct_forms(self):
        validator = self.validator
        direct = (
            'bash ./dev-tools/hosted/dev-demo/write-dev-demo-summary.sh \\\n'
            "  success \\\n"
            '  "${{ needs.dev-demo-plan.outputs.head_sha }}" \\\n'
            '  "${{ needs.dev-demo-plan.outputs.image_tag }}" \\\n'
            '  "${{ needs.dev-demo-plan.outputs.hostname }}" \\\n'
            '  "${{ needs.dev-demo-plan.outputs.telnet_port }}" >> "$GITHUB_STEP_SUMMARY"'
        )
        metadata = "\n".join(validator.SUMMARY_METADATA_LINES)
        self.assertEqual(len(validator.summary_write_regions(direct)), 1)
        self.assertEqual(len(validator.summary_write_regions(metadata)), 1)
        self.assertEqual(
            validator.summary_write_regions('echo "safe summary" >> "$GITHUB_STEP_SUMMARY"'),
            [],
        )

    def test_discovery_rejects_indirect_summary_sink(self):
        validator = self.validator
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = [
                validator.WorkflowRunSource(
                    "job",
                    "step",
                    'summary_file="$GITHUB_STEP_SUMMARY"\necho safe >> "$summary_file"',
                )
            ]
            with self.assertRaisesRegex(AssertionError, "unsupported or indirect summary syntax"):
                validator.discover_summary_writers(source, root)

    def test_discovery_rejects_unsupported_helper_indirection(self):
        validator = self.validator
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = [
                validator.WorkflowRunSource(
                    "job",
                    "step",
                    'summary_helper="./dev-tools/hosted/dev-demo/write-dev-demo-summary.sh"\n'
                    'bash "$summary_helper" success >> "$GITHUB_STEP_SUMMARY"',
                )
            ]
            with self.assertRaisesRegex(AssertionError, "unsupported or indirect summary syntax"):
                validator.discover_summary_writers(source, root)

    def test_discovery_rejects_workflow_helper_summary_credential_sink(self):
        validator = self.validator
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self._write_workflow_fixture(
                root,
                self._bootstrap_manifest_fixture(),
                'bash ./dev-tools/other-summary.sh',
            )
            helper = root / "dev-tools/other-summary.sh"
            helper.write_text(
                'echo "${DEMO_SMOKE_PASSWORD}" >> "$GITHUB_STEP_SUMMARY"\n',
                encoding="utf-8",
            )
            with self.assertRaisesRegex(
                AssertionError, "repository shell helper must not write"
            ):
                validator.validate_workflow(root)

    def test_discovery_rejects_nested_workflow_helper_summary_credential_sink(self):
        validator = self.validator
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self._write_workflow_fixture(
                root,
                self._bootstrap_manifest_fixture(),
                'bash ./dev-tools/outer-summary.sh',
            )
            (root / "dev-tools/outer-summary.sh").write_text(
                "bash ./dev-tools/inner-summary.sh\n", encoding="utf-8"
            )
            (root / "dev-tools/inner-summary.sh").write_text(
                'echo "${DEMO_SMOKE_PASSWORD}" >> "$GITHUB_STEP_SUMMARY"\n',
                encoding="utf-8",
            )
            with self.assertRaisesRegex(
                AssertionError, "repository shell helper must not write"
            ):
                validator.validate_workflow(root)

    def test_discovery_checks_variable_rooted_nested_helper(self):
        validator = self.validator
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self._write_workflow_fixture(
                root,
                self._bootstrap_manifest_fixture(),
                'bash ./dev-tools/outer-summary.sh',
            )
            (root / "dev-tools/outer-summary.sh").write_text(
                'source "$FIREMUD_REPO_ROOT/dev-tools/inner-summary.sh"\n',
                encoding="utf-8",
            )
            (root / "dev-tools/inner-summary.sh").write_text(
                'echo "${DEMO_SMOKE_PASSWORD}" >> "$GITHUB_STEP_SUMMARY"\n',
                encoding="utf-8",
            )
            with self.assertRaisesRegex(
                AssertionError, "repository shell helper must not write"
            ):
                validator.validate_workflow(root)

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

    def test_canonical_helper_is_checked_for_forbidden_references(self):
        validator = self.validator
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            helper_path = root / "dev-tools/hosted/dev-demo/write-dev-demo-summary.sh"
            helper_path.parent.mkdir(parents=True)
            helper_path.write_text(
                'echo "${DEMO_SMOKE_PASSWORD}"\n', encoding="utf-8"
            )
            source = [
                validator.WorkflowRunSource(
                    "job",
                    "step",
                    'bash ./dev-tools/hosted/dev-demo/write-dev-demo-summary.sh \\\n'
                    '  success "${{ needs.dev-demo-plan.outputs.head_sha }}" \\\n'
                    '  "${{ needs.dev-demo-plan.outputs.image_tag }}" \\\n'
                    '  "${{ needs.dev-demo-plan.outputs.hostname }}" \\\n'
                    '  "${{ needs.dev-demo-plan.outputs.telnet_port }}" >> "$GITHUB_STEP_SUMMARY"',
                )
            ]
            with self.assertRaisesRegex(
                AssertionError, "canonical dev-demo summary helper must not reference"
            ):
                validator.discover_summary_writers(source, root)


if __name__ == "__main__":
    unittest.main()
