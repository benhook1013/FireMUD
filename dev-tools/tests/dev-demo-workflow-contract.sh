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
    raise AssertionError("dev-demo workflow is not valid YAML") from exc

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
python3 - <<'PY' "$ROOT_DIR"
import re
import sys
from pathlib import Path

import yaml

root = Path(sys.argv[1]).resolve()
workflow_path = root / ".github" / "workflows" / "dev-demo.yml"
if not workflow_path.is_file():
    raise AssertionError(f"dev-demo workflow is missing: expected {workflow_path}")
try:
    workflow = yaml.safe_load(workflow_path.read_text(encoding="utf-8"))
except yaml.YAMLError as exc:
    raise AssertionError("dev-demo workflow is not valid YAML") from exc
jobs = workflow.get("jobs") if isinstance(workflow, dict) else None
if not isinstance(jobs, dict):
    raise AssertionError("dev-demo workflow must define jobs as a mapping")
deploy_job = jobs.get("dev-demo-deploy")
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
try:
    bootstrap_manifest = bootstrap_step["run"]
except KeyError as exc:
    raise AssertionError(
        "dev-demo bootstrap step must expose its shell script as run"
    ) from exc
if not isinstance(bootstrap_manifest, str):
    raise AssertionError("dev-demo bootstrap step run must be a string")
bootstrap_lines = [line.strip() for line in bootstrap_manifest.splitlines()]


def assert_ordered_lines(lines, expected_lines, message):
    next_index = 0
    for expected_line in expected_lines:
        for index in range(next_index, len(lines)):
            if expected_line in lines[index]:
                next_index = index + 1
                break
        else:
            raise AssertionError(message)


cleanup_function_starts = [
    index
    for index, line in enumerate(bootstrap_lines)
    if line == "cleanup_bootstrap_temp_dir() {"
]
if len(cleanup_function_starts) != 1:
    raise AssertionError(
        "dev-demo bootstrap must contain exactly one cleanup_bootstrap_temp_dir function"
    )


def cleanup_function_end_index(lines, function_start):
    brace_depth = 0
    for index in range(function_start, len(lines)):
        line = lines[index]
        if line.endswith("() {") or line == "{":
            brace_depth += 1
        elif line == "}":
            brace_depth -= 1
            if brace_depth == 0:
                return index + 1
    return None


cleanup_function_start = cleanup_function_starts[0]
cleanup_function_end = cleanup_function_end_index(
    bootstrap_lines,
    cleanup_function_start,
)
if cleanup_function_end is None:
    raise AssertionError(
        "dev-demo bootstrap cleanup function has no same-nesting closing brace"
    )
cleanup_function_lines = bootstrap_lines[cleanup_function_start:cleanup_function_end]
cleanup_success_start = next(
    (
        index
        for index, line in enumerate(cleanup_function_lines)
        if 'if rm -rf "${BOOTSTRAP_SECRET_DIR}"; then' in line
    ),
    None,
)
if cleanup_success_start is None:
    raise AssertionError(
        "dev-demo bootstrap temp directory cleanup success branch is missing"
    )
shell_if_start_re = re.compile(r"^if\b.*;[ \t]*then$")


def assert_supported_shell_if(line):
    if re.match(r"^if(?:\s|$)", line) and not shell_if_start_re.fullmatch(line):
        raise AssertionError(
            "unsupported shell if form; expected a single-line 'if ...; then' opener: "
            f"{line}"
        )


def closing_fi_index(lines, if_index):
    assert_supported_shell_if(lines[if_index])
    nested_if_depth = 0
    for index in range(if_index + 1, len(lines)):
        line = lines[index]
        if re.match(r"^if(?:\s|$)", line):
            assert_supported_shell_if(line)
            nested_if_depth += 1
        elif line == "fi":
            if nested_if_depth == 0:
                return index
            nested_if_depth -= 1
    return None


cleanup_success_end = closing_fi_index(
    cleanup_function_lines,
    cleanup_success_start,
)
if cleanup_success_end is None:
    raise AssertionError(
        "dev-demo bootstrap temp directory cleanup success branch has no closing fi"
    )
nested_if_fixture = [
    'if rm -rf "${BOOTSTRAP_SECRET_DIR}"; then',
    'if [[ -n "${BOOTSTRAP_SECRET_DIR}" ]]; then',
    "true",
    "fi",
    "return 0",
    "fi",
]
if closing_fi_index(nested_if_fixture, 0) != 5:
    raise AssertionError("cleanup success branch must match its outer closing fi")
nested_group_fixture = [
    "cleanup_bootstrap_temp_dir() {",
    "{",
    'if [[ -n "${BOOTSTRAP_SECRET_DIR}" ]]; then',
    "true",
    "fi",
    "}",
    "}",
]
if cleanup_function_end_index(nested_group_fixture, 0) != len(nested_group_fixture):
    raise AssertionError(
        "cleanup function brace depth must include standalone grouped-command braces"
    )
for unsupported_if_fixture in (
    ["if true", "then", "fi"],
    ["if true; then echo inline; fi"],
):
    try:
        closing_fi_index(unsupported_if_fixture, 0)
    except AssertionError as exc:
        if "unsupported shell if form" not in str(exc):
            raise
    else:
        raise AssertionError(
            "unsupported shell if fixture unexpectedly passed contract parsing"
        )
cleanup_success_return = next(
    (
        index
        for index in range(cleanup_success_start + 1, cleanup_success_end)
        if "return 0" in cleanup_function_lines[index]
    ),
    None,
)
if cleanup_success_return is None:
    raise AssertionError(
        "dev-demo bootstrap temp directory cleanup success branch must return 0"
    )
clear_directory_lines = [
    index
    for index in range(len(cleanup_function_lines))
    if cleanup_function_lines[index] == "BOOTSTRAP_SECRET_DIR="
]
if (
    len(clear_directory_lines) != 1
    or not cleanup_success_start
    < clear_directory_lines[0]
    < cleanup_success_return
    < cleanup_success_end
):
    raise AssertionError(
        "dev-demo bootstrap temp directory must clear its variable only in the "
        "successful rm branch before return 0"
    )
assert_ordered_lines(
    cleanup_function_lines,
    (
        'echo "::error::Failed to remove dev-demo bootstrap credential files" >&2',
        "return 1",
    ),
    "dev-demo bootstrap temp directory removal failure must return failure",
)

manifest_opener = "cat <<'EOF' | kubectl -n \"${PREVIEW_NAMESPACE}\" apply -f -\n"
if bootstrap_manifest.count(manifest_opener) != 1:
    raise AssertionError(
        "dev-demo bootstrap step must contain exactly one expected pod manifest heredoc opener"
    )
try:
    manifest_start = bootstrap_manifest.index(manifest_opener)
    manifest_start = bootstrap_manifest.index("\n", manifest_start) + 1
    try:
        manifest_end = bootstrap_manifest.index("\nEOF\n", manifest_start)
    except ValueError:
        if not bootstrap_manifest.endswith("\nEOF"):
            raise
        manifest_end = len(bootstrap_manifest) - len("\nEOF")
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
if not isinstance(bootstrap_pod, dict) or not isinstance(
    bootstrap_pod.get("spec"), dict
):
    raise AssertionError("dev-demo bootstrap pod must define spec as a mapping")
containers = bootstrap_pod["spec"].get("containers")
if not isinstance(containers, list) or not containers:
    raise AssertionError(
        "dev-demo bootstrap pod spec.containers must be a non-empty list"
    )
if not isinstance(containers[0], dict):
    raise AssertionError(
        "dev-demo bootstrap pod spec.containers[0] must be a mapping"
    )
if containers[0].get("envFrom", []) != [
    {"secretRef": {"name": "dev-demo-bootstrap-env"}}
]:
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
    r"(?<![A-Za-z0-9_./$-])(?:bash[ \t]+)?"
    r"(?P<invocation>(?:"
    r"dev-tools/[A-Za-z0-9_./-]+[.]sh|"
    r"[.]/dev-tools/[A-Za-z0-9_./-]+[.]sh|"
    r"/[^\s;&|\"'`]+/dev-tools/[A-Za-z0-9_./-]+[.]sh|"
    r"\$(?:\{[A-Za-z_][A-Za-z0-9_]*\}|[A-Za-z_][A-Za-z0-9_]*)/"
    r"dev-tools/[A-Za-z0-9_./-]+[.]sh"
    r"))(?![A-Za-z0-9_./-])"
)
root_dir = root.resolve()


def normalize_summary_helper_path(invocation):
    if invocation.startswith("/"):
        return Path(invocation).resolve()
    if invocation.startswith("$"):
        return (root_dir / invocation.split("/", 1)[1]).resolve()
    return (root_dir / invocation.removeprefix("./")).resolve()


for fixture, expected in (
    (
        "dev-tools/tests/smoke-transport-contract.sh",
        "dev-tools/tests/smoke-transport-contract.sh",
    ),
    (
        "./dev-tools/tests/smoke-transport-contract.sh",
        "dev-tools/tests/smoke-transport-contract.sh",
    ),
    (
        "bash dev-tools/tests/smoke-transport-contract.sh",
        "dev-tools/tests/smoke-transport-contract.sh",
    ),
    (
        "bash ./dev-tools/tests/smoke-transport-contract.sh",
        "dev-tools/tests/smoke-transport-contract.sh",
    ),
    (
        f"bash {root_dir}/dev-tools/tests/smoke-transport-contract.sh",
        "dev-tools/tests/smoke-transport-contract.sh",
    ),
    (
        "$GITHUB_WORKSPACE/dev-tools/tests/smoke-transport-contract.sh",
        "dev-tools/tests/smoke-transport-contract.sh",
    ),
    (
        "${ROOT_DIR}/dev-tools/tests/smoke-transport-contract.sh",
        "dev-tools/tests/smoke-transport-contract.sh",
    ),
):
    matches = summary_helper_pattern.findall(fixture)
    if (
        len(matches) != 1
        or normalize_summary_helper_path(matches[0]) != root_dir / expected
    ):
        raise AssertionError(
            f"summary helper fixture was not normalized: {fixture}"
        )

outside_fixture = f"{root_dir.parent}/dev-tools/tests/smoke-transport-contract.sh"
outside_matches = summary_helper_pattern.findall(outside_fixture)
if len(outside_matches) != 1:
    raise AssertionError("absolute summary helper containment fixture was not detected")
try:
    normalize_summary_helper_path(outside_matches[0]).relative_to(root_dir)
except ValueError:
    pass
else:
    raise AssertionError("summary helper discovery must keep helpers within repo root")


summary_writers = []
for root_entry in workflow_run_sources:
    source_closure = [root_entry]
    pending_helpers = [root_entry]
    seen_helpers = set()
    while pending_helpers:
        job_name, step_name, source = pending_helpers.pop()
        for helper_invocation in summary_helper_pattern.findall(source):
            helper_path = normalize_summary_helper_path(helper_invocation)
            try:
                helper = helper_path.relative_to(root_dir).as_posix()
            except ValueError:
                continue
            if helper in seen_helpers:
                continue
            seen_helpers.add(helper)
            if not helper_path.is_file():
                continue
            helper_source = helper_path.read_text(encoding="utf-8")
            helper_entry = (job_name, f"{step_name}:{helper}", helper_source)
            source_closure.append(helper_entry)
            pending_helpers.append(helper_entry)
    summary_writers.extend(
        entry for entry in source_closure if "GITHUB_STEP_SUMMARY" in entry[2]
    )
if not summary_writers:
    raise AssertionError("dev-demo workflow must define summary-writing steps")

forbidden_summary_reference = re.compile(
    r"DEMO_SMOKE_PASSWORD|"
    r"\$\{?BOOTSTRAP_SECRET_DIR\}?/password|"
    r"\$\{\{\s*secrets[.]|"
    r"steps[.][A-Za-z0-9_-]+[.]outputs[.]password|"
    r"(?<![;&|\n])[^;&|\n]*(?<![A-Za-z])(?:secrets?|secs?|credentials?|creds?)(?![A-Za-z])"
    r"[^;&|\n]*(?:\|[^;&|\n]*)*\|\s*base64\s+(?:-[A-Za-z]*d[A-Za-z]*|--decode)\b",
    re.IGNORECASE,
)


def has_forbidden_summary_reference(text):
    normalized_text = re.sub(r"[ 	]+", " ", text)
    return forbidden_summary_reference.search(normalized_text) is not None


summary_target = re.compile(
    r"(?P<operator>>{1,2}|tee(?:[ \t]+(?:-a|--append))?)[ \t]*"
    r"['\"]?\$\{?GITHUB_STEP_SUMMARY\}?['\"]?"
)
heredoc_open = re.compile(
    r"<<(?P<strip_tabs>-)?[ \t]*(?P<quote>['\"]?)"
    r"(?P<delimiter>[A-Za-z_][A-Za-z0-9_]*)"
    r"(?P=quote)"
)


def shell_group_tokens(line):
    tokens = []
    quote = None
    escaped = False
    word_started = False
    index = 0
    while index < len(line):
        character = line[index]
        if escaped:
            escaped = False
            word_started = True
            index += 1
            continue
        if character == "\\":
            escaped = True
            word_started = True
            index += 1
            continue
        if quote is not None:
            if character == quote:
                quote = None
            index += 1
            continue
        if character in "'\"":
            quote = character
            word_started = True
            index += 1
            continue
        if character == "$" and index + 1 < len(line) and line[index + 1] in "{(":
            opener = line[index + 1]
            closer = "}" if opener == "{" else ")"
            depth = 1
            word_started = True
            index += 2
            while index < len(line) and depth:
                if line[index] == opener:
                    depth += 1
                elif line[index] == closer:
                    depth -= 1
                index += 1
            continue
        if character == "#" and not word_started:
            break
        if character in "{}()":
            tokens.append(character)
        if character.isspace() or character in ";|&<>()":
            word_started = False
        else:
            word_started = True
        index += 1
    return tokens


for fixture, expected in (
    ('{ echo safe # comment with } and )', ["{"]),
    ('{ echo safe#not-a-comment }', ["{", "}"]),
    ('{ echo "# not a comment }" }', ["{", "}"]),
    (r"{ echo \#not-a-comment }", ["{", "}"]),
    ('{ echo ${value#pattern} }', ["{", "}"]),
    ('{ echo $(printf "# not a comment }") }', ["{", "}"]),
):
    if shell_group_tokens(fixture) != expected:
        raise AssertionError(
            f"shell group token comment fixture parsed incorrectly: {fixture}"
        )


def grouped_command_start(lines, index, target_match):
    attached_tokens = shell_group_tokens(lines[index][: target_match.start()])
    if not attached_tokens or attached_tokens[-1] not in "})":
        return None
    depth = 0
    saw_closing_group = False
    for candidate in range(index, -1, -1):
        candidate_text = (
            lines[candidate][: target_match.start()]
            if candidate == index
            else lines[candidate]
        )
        for token in reversed(shell_group_tokens(candidate_text)):
            if token in "})":
                depth += 1
                saw_closing_group = True
            elif saw_closing_group and token in "{(":
                depth -= 1
                if depth == 0:
                    return candidate
    return None


def summary_heredoc(lines, start, index, target_match):
    for opener_index in range(start, index + 1):
        if opener_index < index and any(
            not lines[candidate].rstrip().endswith("\\")
            for candidate in range(opener_index, index)
        ):
            continue
        command_lines = lines[opener_index:index]
        command_lines.append(lines[index][: target_match.start()])
        command_text = "\n".join(command_lines)
        match = heredoc_open.search(command_text)
        if match is None:
            continue
        if re.search(r"[;&|]", command_text[match.end() :]):
            continue
        return match
    return None


def summary_write_regions(source):
    lines = source.splitlines()
    regions = []
    for index, line in enumerate(lines):
        target_match = summary_target.search(line)
        if target_match is None:
            continue
        start = grouped_command_start(lines, index, target_match)
        if start is None:
            start = index
            while start > 0 and lines[start - 1].rstrip().endswith("\\"):
                start -= 1
        end = index
        heredoc_match = summary_heredoc(lines, start, index, target_match)
        if heredoc_match is not None:
            delimiter = heredoc_match.group("delimiter")
            strip_tabs = heredoc_match.group("strip_tabs") is not None
            for candidate in range(index + 1, len(lines)):
                candidate_line = lines[candidate]
                if strip_tabs:
                    candidate_line = candidate_line.lstrip("\t")
                if candidate_line == delimiter:
                    end = candidate
                    break
            else:
                end = len(lines) - 1
        regions.append("\n".join(lines[start : end + 1]))
    return regions


if not any(
    summary_write_regions(source)
    for _job_name, _step_name, source in summary_writers
):
    raise AssertionError(
        "dev-demo workflow must write summaries through a recognized shell target"
    )


for safe_source, unsafe in (
    (
        'printf "%s" "$DEMO_SMOKE_PASSWORD" >/tmp/password\n'
        'echo "safe summary" >> "$GITHUB_STEP_SUMMARY"',
        False,
    ),
    (
        '{\n'
        '  echo "unsafe: $DEMO_SMOKE_PASSWORD"\n'
        '} >> "$GITHUB_STEP_SUMMARY"',
        True,
    ),
    (
        "cat <<'SUMMARY_EOF' >> \"$GITHUB_STEP_SUMMARY\"\n"
        "unsafe: $DEMO_SMOKE_PASSWORD\n"
        "SUMMARY_EOF",
        True,
    ),
):
    fixture_regions = summary_write_regions(safe_source)
    fixture_is_unsafe = any(has_forbidden_summary_reference(region) for region in fixture_regions)
    if fixture_is_unsafe != unsafe:
        raise AssertionError(
            "summary secret detector fixture produced the wrong result"
        )

for secret_pipeline in (
    "kubectl get secret demo -o json | base64 -d",
    "kubectl get SECRETS demo -o json | jq -r .data.password | base64 --decode",
    "kubectl get sec demo -o json | tr -d '\\n' | base64 -d",
    "echo API_SECRET_TOKEN | base64 -d",
    "echo DB_CREDS | base64 -d",
    "echo DB_CREDS | base64 -di",
):
    if not has_forbidden_summary_reference(secret_pipeline):
        raise AssertionError(
            "forbidden summary regex must detect secret material through intermediate "
            f"pipelines: {secret_pipeline}"
        )
for safe_summary in (
    "echo secret summary",
    "kubectl get secret demo -o json; cat encoded.txt | base64 -d",
    "kubectl get secret demo -o json\ncat encoded.txt | base64 -d",
    "kubectl get secret demo -o json | jq -r .metadata.name",
    "kubectl get secret demo -o json; echo base64 -d",
    "kubectl get secret demo -o json\nbase64 -d",
):
    if has_forbidden_summary_reference(safe_summary):
        raise AssertionError(
            f"forbidden summary regex must not cross command separators: {safe_summary}"
        )
offending_writers = [
    (job_name, step_name)
    for job_name, step_name, source in summary_writers
    if any(
        has_forbidden_summary_reference(region)
        for region in summary_write_regions(source)
    )
]
if offending_writers:
    writers = ", ".join(
        f"{job_name}/{step_name}" for job_name, step_name in offending_writers
    )
    raise AssertionError(
        "dev-demo summaries must not reference bootstrap credential material; "
        f"offending summary writers: {writers}"
    )


for fixture, expected in (
    (
        '{ echo "unsafe: $DEMO_SMOKE_PASSWORD"; } >> "$GITHUB_STEP_SUMMARY"',
        True,
    ),
    (
        "{\n"
        '  echo "unsafe: $DEMO_SMOKE_PASSWORD"\n'
        '} >> "$GITHUB_STEP_SUMMARY"',
        True,
    ),
    (
        '( echo "unsafe: $DEMO_SMOKE_PASSWORD" ) >> "$GITHUB_STEP_SUMMARY"',
        True,
    ),
    (
        "(\n"
        '  echo "unsafe: $DEMO_SMOKE_PASSWORD"\n'
        ') >> "$GITHUB_STEP_SUMMARY"',
        True,
    ),
    (
        "cat <<'UNRELATED'\n"
        "unsafe: $DEMO_SMOKE_PASSWORD\n"
        "UNRELATED\n"
        'echo "safe summary" >> "$GITHUB_STEP_SUMMARY"',
        False,
    ),
    (
        "unrelated() {\n"
        '  echo "unsafe: $DEMO_SMOKE_PASSWORD"\n'
        "}\n"
        'echo "safe summary" >> "$GITHUB_STEP_SUMMARY"',
        False,
    ),
):
    fixture_is_unsafe = any(
        has_forbidden_summary_reference(region)
        for region in summary_write_regions(fixture)
    )
    if fixture_is_unsafe != expected:
        raise AssertionError(
            "summary write-region fixture produced the wrong result"
        )

print("dev-demo summary contract checks passed")
PY
