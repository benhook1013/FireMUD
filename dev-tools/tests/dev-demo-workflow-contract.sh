#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

for smoke_script in \
  "$ROOT_DIR/services/game-session-service/websocket-login-look-smoke.sh" \
  "$ROOT_DIR/services/tcp-proxy-service/telnet-login-look-smoke.sh"; do
  if ! grep -Fq \
    "verify_smoke_account(account_api_base, username, password, timeout_seconds)" \
    "$smoke_script"; then
    printf '%s\n' \
      "Smoke contract missing required account verification:" \
      "  script: $smoke_script" \
      "  pattern: verify_smoke_account(account_api_base, username, password, timeout_seconds)" \
      >&2
    exit 1
  fi
done

python3 - <<'PY' "$ROOT_DIR"
import json
import sys
import urllib.error
from contextlib import redirect_stderr, redirect_stdout
from io import BytesIO, StringIO
from pathlib import Path
from unittest.mock import patch

import yaml

root = Path(sys.argv[1])
sys.path.insert(0, str(root / "dev-tools" / "smoke"))

import smoke_common
from smoke_common import verify_smoke_account

dev_demo_workflow_path = root / ".github" / "workflows" / "dev-demo.yml"
if not dev_demo_workflow_path.is_file():
    raise AssertionError(
        f"dev-demo workflow is missing: expected {dev_demo_workflow_path}"
    )
dev_demo_workflow = dev_demo_workflow_path.read_text(encoding="utf-8")
try:
    workflow = yaml.safe_load(dev_demo_workflow)
except yaml.YAMLError as exc:
    raise AssertionError(f"dev-demo workflow is not valid YAML: {exc}") from exc

jobs = workflow.get("jobs") if isinstance(workflow, dict) else None
if not isinstance(jobs, dict) or "dev-demo-deploy" not in jobs:
    raise AssertionError(
        "dev-demo workflow missing required 'dev-demo-deploy' job"
    )
deploy_job = jobs["dev-demo-deploy"]
if not isinstance(deploy_job, dict) or not isinstance(deploy_job.get("steps"), list):
    raise AssertionError(
        "dev-demo-deploy job missing its required steps list"
    )
bootstrap_step = next(
    (
        step
        for step in deploy_job["steps"]
        if isinstance(step, dict)
        and step.get("name") == "Create dev-demo smoke account"
    ),
    None,
)
if bootstrap_step is None:
    raise AssertionError(
        "dev-demo-deploy job missing required bootstrap step "
        "'Create dev-demo smoke account'"
    )
smoke_step = next(
    (
        step
        for step in deploy_job["steps"]
        if isinstance(step, dict)
        and step.get("name") == "Smoke dev-demo over TCP"
    ),
    None,
)
if smoke_step is None:
    raise AssertionError(
        "dev-demo-deploy job missing required step 'Smoke dev-demo over TCP'"
    )
smoke_condition = smoke_step.get("if")
if not isinstance(smoke_condition, str) or "!cancelled()" not in smoke_condition:
    raise AssertionError(
        "dev-demo TCP smoke must still run after a non-cancellation bootstrap failure"
    )

try:
    bootstrap_manifest = bootstrap_step["run"]
except KeyError as exc:
    raise AssertionError(
        "dev-demo bootstrap step must expose its shell script as run"
    ) from exc
if not isinstance(bootstrap_manifest, str):
    raise AssertionError("dev-demo bootstrap step run must be a string")


def normalize_script(script):
    return " ".join(script.split())


def normalize_nonempty_lines(script):
    return "\n".join(
        " ".join(line.split()) for line in script.splitlines() if line.strip()
    )


normalized_bootstrap_manifest = normalize_script(bootstrap_manifest)
for expected in (
    'create secret generic dev-demo-bootstrap-env',
    '--from-file=DEMO_SMOKE_EMAIL="${BOOTSTRAP_SECRET_DIR}/email"',
    '--from-file=DEMO_SMOKE_PASSWORD="${BOOTSTRAP_SECRET_DIR}/password"',
    '--from-file=DEMO_SMOKE_USERNAME="${BOOTSTRAP_SECRET_DIR}/username"',
    'cleanup_bootstrap_temp_dir() {',
    'if rm -rf "${BOOTSTRAP_SECRET_DIR}"; then',
    'echo "::error::Failed to remove dev-demo bootstrap credential files"',
    'if ! cleanup_bootstrap_temp_dir; then',
    'cleanup_bootstrap_secret',
):
    if normalize_script(expected) not in normalized_bootstrap_manifest:
        raise AssertionError(
            f"dev-demo bootstrap step contract missing: {expected}"
        )

normalized_bootstrap_lines = normalize_nonempty_lines(bootstrap_manifest)
cleanup_success_lines = normalize_nonempty_lines(
    """if rm -rf "${BOOTSTRAP_SECRET_DIR}"; then
    BOOTSTRAP_SECRET_DIR=
    return 0"""
)
if cleanup_success_lines not in normalized_bootstrap_lines:
    raise AssertionError(
        "dev-demo bootstrap temp directory must clear its variable only after rm succeeds"
    )
cleanup_failure_lines = normalize_nonempty_lines(
    """echo "::error::Failed to remove dev-demo bootstrap credential files" >&2
  return 1"""
)
if cleanup_failure_lines not in normalized_bootstrap_lines:
    raise AssertionError(
        "dev-demo bootstrap temp directory removal failure must return failure"
    )

try:
    manifest_start = bootstrap_manifest.index(
        "cat <<'EOF' | kubectl -n \"${PREVIEW_NAMESPACE}\" apply -f -\n"
    )
    manifest_start = bootstrap_manifest.index("\n", manifest_start) + 1
    manifest_end = bootstrap_manifest.index("\nEOF\n", manifest_start)
except ValueError as exc:
    raise AssertionError(
        "dev-demo bootstrap step must contain the expected pod manifest heredoc"
    ) from exc
try:
    bootstrap_pod = yaml.safe_load(bootstrap_manifest[manifest_start:manifest_end])
except yaml.YAMLError as exc:
    raise AssertionError(
        "dev-demo bootstrap pod manifest heredoc is not valid YAML"
    ) from exc
if not isinstance(bootstrap_pod, dict):
    raise AssertionError("dev-demo bootstrap pod manifest must be a mapping")
pod_spec = bootstrap_pod.get("spec")
if not isinstance(pod_spec, dict):
    raise AssertionError("dev-demo bootstrap pod manifest must contain a spec mapping")
containers = pod_spec.get("containers")
if not isinstance(containers, list) or not containers:
    raise AssertionError(
        "dev-demo bootstrap pod manifest must contain a non-empty containers list"
    )
bootstrap_container = containers[0]
if not isinstance(bootstrap_container, dict):
    raise AssertionError("dev-demo bootstrap pod manifest first container must be a mapping")
env_from = bootstrap_container.get("envFrom", [])
if not isinstance(env_from, list):
    raise AssertionError(
        "dev-demo bootstrap pod first container envFrom must be a list"
    )
if env_from != [{"secretRef": {"name": "dev-demo-bootstrap-env"}}]:
    raise AssertionError(
        "dev-demo bootstrap pod must import dev-demo-bootstrap-env"
    )

summary_runs = []
for job_name, job in jobs.items():
    if not isinstance(job, dict):
        raise AssertionError(f"dev-demo workflow job {job_name!r} must be a mapping")
    steps = job.get("steps")
    if steps is None:
        continue
    if not isinstance(steps, list):
        raise AssertionError(f"dev-demo workflow job {job_name!r} steps must be a list")
    for step_index, step in enumerate(steps):
        if not isinstance(step, dict):
            raise AssertionError(
                f"dev-demo workflow job {job_name!r} step {step_index} must be a mapping"
            )
        run = step.get("run")
        if isinstance(run, str) and "GITHUB_STEP_SUMMARY" in run:
            summary_runs.append((job_name, step.get("name", step_index), run))
if not summary_runs:
    raise AssertionError("dev-demo workflow must define summary-writing steps")
if any("DEMO_SMOKE_PASSWORD" in run for _, _, run in summary_runs):
    writers = ", ".join(f"{job_name}/{step_name}" for job_name, step_name, _ in summary_runs)
    raise AssertionError(
        "dev-demo summaries must not reference DEMO_SMOKE_PASSWORD; "
        f"summary writers: {writers}"
    )


class FakeHttpResponse:
    status = 200

    def __enter__(self):
        return self

    def __exit__(self, *_args):
        return False

    def read(self):
        return b'{"status":"SUCCESS"}'


success_output = StringIO()
with patch(
    "smoke_common.urllib.request.urlopen", return_value=FakeHttpResponse()
) as urlopen:
    with redirect_stdout(success_output):
        verify_smoke_account(
            "http://account.test", "demo@example.com", "swordfish", 5
        )

login_request = urlopen.call_args.args[0]
assert login_request.full_url == "http://account.test/auth/login"
assert json.loads(login_request.data) == {
    "username": "demo@example.com",
    "password": "swordfish",
}
assert "SUCCESS" not in success_output.getvalue()
assert "status 200" in success_output.getvalue()

failure_body = b'{"error":"sensitive upstream detail"}'
http_error = urllib.error.HTTPError(
    "http://account.test/auth/login",
    401,
    "Unauthorized",
    {},
    BytesIO(failure_body),
)
failure_stdout = StringIO()
failure_stderr = StringIO()
try:
    with patch("smoke_common.urllib.request.urlopen", side_effect=http_error):
        with redirect_stdout(failure_stdout):
            with redirect_stderr(failure_stderr):
                verify_smoke_account(
                    "http://account.test", "demo@example.com", "swordfish", 5
                )
except RuntimeError as exc:
    assert str(exc) == "Smoke account validation failed with status 401"
    assert "sensitive upstream detail" not in str(exc)
    assert "sensitive upstream detail" not in failure_stdout.getvalue()
    assert "sensitive upstream detail" not in failure_stderr.getvalue()
else:
    raise AssertionError("Expected account validation failure")


retry_errors = [
    urllib.error.HTTPError(
        "http://account.test/auth/login",
        503,
        "Unavailable",
        {},
        BytesIO(b'{"error":"sensitive retry detail"}'),
    ),
    urllib.error.HTTPError(
        "http://account.test/auth/login",
        503,
        "Unavailable",
        {},
        BytesIO(b'{"error":"sensitive retry detail"}'),
    ),
]
retry_output = StringIO()
with patch(
    "smoke_common.urllib.request.urlopen",
    side_effect=retry_errors + [FakeHttpResponse()],
) as retry_urlopen:
    with patch("smoke_common.time.sleep") as sleep:
        with redirect_stdout(retry_output):
            verify_smoke_account(
                "http://account.test", "demo@example.com", "swordfish", 5
            )
assert retry_urlopen.call_count == 3
assert sleep.call_count == 2
assert "sensitive retry detail" not in retry_output.getvalue()


non_retryable_error = urllib.error.HTTPError(
    "http://account.test/auth/login",
    400,
    "Bad Request",
    {},
    BytesIO(b'{"error":"sensitive non-retryable detail"}'),
)
with patch(
    "smoke_common.urllib.request.urlopen",
    side_effect=[non_retryable_error, FakeHttpResponse()],
) as non_retryable_urlopen:
    try:
        verify_smoke_account(
            "http://account.test", "demo@example.com", "swordfish", 5
        )
    except RuntimeError as exc:
        assert str(exc) == "Smoke account validation failed with status 400"
        assert "sensitive non-retryable detail" not in str(exc)
    else:
        raise AssertionError("Expected non-retryable account validation failure")
assert non_retryable_urlopen.call_count == 1

print("dev-demo workflow contract checks passed")
PY
