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
import re
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
    'cleanup_bootstrap_resources() {',
    'trap cleanup_bootstrap_resources EXIT',
    "trap 'exit 130' INT",
    "trap 'exit 143' TERM",
):
    if normalize_script(expected) not in normalized_bootstrap_manifest:
        raise AssertionError(
            f"dev-demo bootstrap step contract missing: {expected}"
        )

normalized_bootstrap_lines = normalize_nonempty_lines(bootstrap_manifest)
credential_validation_lines = normalize_nonempty_lines(
    '''for credential in DEMO_SMOKE_EMAIL DEMO_SMOKE_PASSWORD DEMO_SMOKE_USERNAME; do
  if [[ -z "${!credential:-}" ]]; then
    echo "::error::${credential} is empty; refusing to create dev-demo bootstrap credentials" >&2
    exit 1
  fi
done
BOOTSTRAP_SECRET_DIR="$(mktemp -d)"'''
)
if credential_validation_lines not in normalized_bootstrap_lines:
    raise AssertionError(
        "dev-demo bootstrap must reject empty credentials before creating temporary files"
    )
if 'chmod 700 "${BOOTSTRAP_SECRET_DIR}"' in bootstrap_manifest:
    raise AssertionError("dev-demo bootstrap must rely on mktemp directory permissions")
secret_cleanup_and_create = normalize_nonempty_lines(
    '''if ! cleanup_bootstrap_secret; then
  exit 1
fi
kubectl -n "${PREVIEW_NAMESPACE}" create secret generic dev-demo-bootstrap-env'''
)
if secret_cleanup_and_create not in normalized_bootstrap_lines:
    raise AssertionError(
        "dev-demo bootstrap must delete stale credentials before direct secret creation"
    )
bootstrap_source_lines = bootstrap_manifest.splitlines()
try:
    secret_command_start = next(
        index
        for index, line in enumerate(bootstrap_source_lines)
        if "create secret generic dev-demo-bootstrap-env" in line
    )
except StopIteration as exc:
    raise AssertionError(
        "dev-demo bootstrap must create its credential secret directly"
    ) from exc
secret_command_lines = []
secret_command_index = secret_command_start
while True:
    secret_command_line = bootstrap_source_lines[secret_command_index].strip()
    secret_command_lines.append(secret_command_line)
    if not secret_command_line.endswith("\\"):
        break
    secret_command_index += 1
    if secret_command_index >= len(bootstrap_source_lines):
        raise AssertionError("dev-demo bootstrap secret command is unterminated")
expected_secret_command_lines = [
    'kubectl -n "${PREVIEW_NAMESPACE}" create secret generic dev-demo-bootstrap-env \\',
    '--from-file=DEMO_SMOKE_EMAIL="${BOOTSTRAP_SECRET_DIR}/email" \\',
    '--from-file=DEMO_SMOKE_PASSWORD="${BOOTSTRAP_SECRET_DIR}/password" \\',
    '--from-file=DEMO_SMOKE_USERNAME="${BOOTSTRAP_SECRET_DIR}/username"',
]
if secret_command_lines != expected_secret_command_lines:
    raise AssertionError(
        "dev-demo bootstrap credential secret must use direct create without apply annotations"
    )
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
post_log_cleanup_lines = normalize_nonempty_lines(
    '''kubectl -n "${PREVIEW_NAMESPACE}" logs dev-demo-bootstrap | tee "${BOOTSTRAP_POD_LOG}"
  kubectl -n "${PREVIEW_NAMESPACE}" delete pod dev-demo-bootstrap --ignore-not-found >/dev/null 2>&1 || true
  kubectl -n "${PREVIEW_NAMESPACE}" delete configmap dev-demo-bootstrap-script --ignore-not-found >/dev/null 2>&1 || true
  cleanup_bootstrap_secret'''
)
if post_log_cleanup_lines not in normalized_bootstrap_lines:
    raise AssertionError(
        "dev-demo bootstrap must remove its credential secret after successful pod logging"
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

workflow_run_sources = []
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
        if isinstance(run, str):
            workflow_run_sources.append((job_name, step.get("name", step_index), run))
summary_helper_pattern = re.compile(
    r"(?:bash\s+)?(?:[.]/)?(dev-tools/[A-Za-z0-9_./-]+[.]sh)"
)
summary_sources = []
for root_entry in workflow_run_sources:
    source_closure = [root_entry]
    pending_helpers = [root_entry]
    seen_helpers = set()
    while pending_helpers:
        job_name, step_name, source = pending_helpers.pop()
        for helper in summary_helper_pattern.findall(source):
            if helper in seen_helpers:
                continue
            seen_helpers.add(helper)
            helper_path = root / helper
            if not helper_path.is_file():
                raise AssertionError(f"workflow helper does not exist: {helper}")
            helper_source = helper_path.read_text(encoding="utf-8")
            helper_entry = (job_name, f"{step_name}:{helper}", helper_source)
            source_closure.append(helper_entry)
            pending_helpers.append(helper_entry)
    if any("GITHUB_STEP_SUMMARY" in source for _, _, source in source_closure):
        summary_sources.extend(source_closure)
if not summary_sources:
    raise AssertionError("dev-demo workflow must define summary-writing steps")
forbidden_summary_reference = re.compile(
    r"DEMO_SMOKE_PASSWORD|"
    r"\$\{BOOTSTRAP_SECRET_DIR\}/password|"
    r"\$\{\{\s*secrets[.]|"
    r"steps[.][A-Za-z0-9_-]+[.]outputs[.]password",
    re.IGNORECASE,
)
if any(forbidden_summary_reference.search(source) for _, _, source in summary_sources):
    writers = ", ".join(
        f"{job_name}/{step_name}" for job_name, step_name, _ in summary_sources
    )
    raise AssertionError(
        "dev-demo summaries must not reference bootstrap credential material; "
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


def require(condition, message):
    if not condition:
        raise AssertionError(message)


success_output = StringIO()
with patch(
    "smoke_common.urllib.request.urlopen", return_value=FakeHttpResponse()
) as urlopen:
    with redirect_stdout(success_output):
        verify_smoke_account(
            "http://account.test", "demo@example.com", "swordfish", 5
        )

login_request = urlopen.call_args.args[0]
require(
    login_request.full_url == "http://account.test/auth/login",
    "smoke account verification must use the login endpoint",
)
require(
    json.loads(login_request.data)
    == {"username": "demo@example.com", "password": "swordfish"},
    "smoke account verification must send the configured credentials",
)
require("SUCCESS" not in success_output.getvalue(), "response bodies must stay redacted")
require("status 200" in success_output.getvalue(), "success status must be reported")

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
    require(
        str(exc) == "Smoke account validation failed with status 401",
        "HTTP failures must report only the response status",
    )
    require("sensitive upstream detail" not in str(exc), "failure text leaked response body")
    require(
        "sensitive upstream detail" not in failure_stdout.getvalue(),
        "stdout leaked a failed response body",
    )
    require(
        "sensitive upstream detail" not in failure_stderr.getvalue(),
        "stderr leaked a failed response body",
    )
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
require(retry_urlopen.call_count == 3, "retryable failures must make three attempts")
require(
    [call.args for call in sleep.call_args_list] == [(1,), (1,)],
    "smoke account retries must retain the bounded one-second delay",
)
require("sensitive retry detail" not in retry_output.getvalue(), "retry output leaked response body")


exhausted_retry_errors = [
    urllib.error.HTTPError(
        "http://account.test/auth/login",
        503,
        "Unavailable",
        {},
        BytesIO(b'{"error":"sensitive exhausted retry detail"}'),
    )
    for _ in range(3)
]
exhausted_retry_stdout = StringIO()
exhausted_retry_stderr = StringIO()
with patch(
    "smoke_common.urllib.request.urlopen", side_effect=exhausted_retry_errors
) as exhausted_retry_urlopen:
    with patch("smoke_common.time.sleep") as exhausted_retry_sleep:
        try:
            with redirect_stdout(exhausted_retry_stdout):
                with redirect_stderr(exhausted_retry_stderr):
                    verify_smoke_account(
                        "http://account.test", "demo@example.com", "swordfish", 5
                    )
        except RuntimeError as exc:
            require(
                str(exc) == "Smoke account validation failed with status 503",
                "exhausted HTTP retries must report only the response status",
            )
            require(
                "sensitive exhausted retry detail" not in str(exc),
                "exhausted HTTP retry failure leaked response body",
            )
        else:
            raise AssertionError("Expected exhausted retryable account validation failure")
require(
    exhausted_retry_urlopen.call_count == 3,
    "exhausted retryable failures must stop after three attempts",
)
require(
    [call.args for call in exhausted_retry_sleep.call_args_list] == [(1,), (1,)],
    "exhausted retryable failures must sleep only between attempts",
)
require(
    "sensitive exhausted retry detail" not in exhausted_retry_stdout.getvalue(),
    "exhausted retry stdout leaked response body",
)
require(
    "sensitive exhausted retry detail" not in exhausted_retry_stderr.getvalue(),
    "exhausted retry stderr leaked response body",
)


transport_failure_text = "sensitive socket route detail"
transport_errors = [OSError(transport_failure_text) for _ in range(3)]
transport_stdout = StringIO()
transport_stderr = StringIO()
with patch(
    "smoke_common.urllib.request.urlopen", side_effect=transport_errors
) as transport_urlopen:
    with patch("smoke_common.time.sleep") as transport_sleep:
        try:
            with redirect_stdout(transport_stdout):
                with redirect_stderr(transport_stderr):
                    verify_smoke_account(
                        "http://account.test", "demo@example.com", "swordfish", 5
                    )
        except RuntimeError as exc:
            require(
                str(exc)
                == "Smoke account validation failed due to a transport error",
                "transport failure must use the redacted canonical message",
            )
            require(
                transport_failure_text not in str(exc),
                "transport failure leaked raw exception details",
            )
        else:
            raise AssertionError("Expected exhausted transport account validation failure")
require(
    transport_urlopen.call_count == 3,
    "transport failures must stop after three attempts",
)
require(
    [call.args for call in transport_sleep.call_args_list] == [(1,), (1,)],
    "transport failures must sleep only between attempts",
)
require(
    transport_failure_text not in transport_stdout.getvalue(),
    "transport failure stdout leaked raw exception details",
)
require(
    transport_failure_text not in transport_stderr.getvalue(),
    "transport failure stderr leaked raw exception details",
)


non_retryable_error = urllib.error.HTTPError(
    "http://account.test/auth/login",
    400,
    "Bad Request",
    {},
    BytesIO(b'{"error":"sensitive non-retryable detail"}'),
)
non_retryable_stdout = StringIO()
non_retryable_stderr = StringIO()
with patch(
    "smoke_common.urllib.request.urlopen",
    side_effect=[non_retryable_error, FakeHttpResponse()],
) as non_retryable_urlopen:
    try:
        with redirect_stdout(non_retryable_stdout):
            with redirect_stderr(non_retryable_stderr):
                verify_smoke_account(
                    "http://account.test", "demo@example.com", "swordfish", 5
                )
    except RuntimeError as exc:
        require(
            str(exc) == "Smoke account validation failed with status 400",
            "non-retryable HTTP failures must report only the response status",
        )
        require(
            "sensitive non-retryable detail" not in str(exc),
            "non-retryable failure text leaked response body",
        )
        require(
            "sensitive non-retryable detail" not in non_retryable_stdout.getvalue(),
            "non-retryable stdout leaked response body",
        )
        require(
            "sensitive non-retryable detail" not in non_retryable_stderr.getvalue(),
            "non-retryable stderr leaked response body",
        )
    else:
        raise AssertionError("Expected non-retryable account validation failure")
require(non_retryable_urlopen.call_count == 1, "HTTP 400 must not be retried")

print("dev-demo workflow contract checks passed")
PY
