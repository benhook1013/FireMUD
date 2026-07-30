#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

python3 - <<'PY' "$ROOT_DIR"
import re
import sys
from pathlib import Path
from unittest.mock import patch

import yaml

root = Path(sys.argv[1])
sys.path.insert(0, str(root / "dev-tools" / "smoke"))

import smoke_common
from smoke_common import run_telnet_smoke_session, run_transport_session, run_websocket_smoke_session

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

try:
    bootstrap_manifest = bootstrap_step["run"]
except KeyError as exc:
    raise AssertionError(
        "dev-demo bootstrap step must expose its shell script as run"
    ) from exc
if not isinstance(bootstrap_manifest, str):
    raise AssertionError("dev-demo bootstrap step run must be a string")

bootstrap_lines = [line.strip() for line in bootstrap_manifest.splitlines()]


def assert_ordered_bootstrap_lines(expected_lines, message):
    next_index = 0
    for expected_line in expected_lines:
        for index in range(next_index, len(bootstrap_lines)):
            if expected_line in bootstrap_lines[index]:
                next_index = index + 1
                break
        else:
            raise AssertionError(message)


expected_secret_command_lines = [
    'kubectl -n "${PREVIEW_NAMESPACE}" create secret generic dev-demo-bootstrap-env \\',
    '--from-file=DEMO_SMOKE_EMAIL="${BOOTSTRAP_SECRET_DIR}/email" \\',
    '--from-file=DEMO_SMOKE_PASSWORD="${BOOTSTRAP_SECRET_DIR}/password" \\',
    '--from-file=DEMO_SMOKE_USERNAME="${BOOTSTRAP_SECRET_DIR}/username"',
]
secret_command_starts = [
    index
    for index, line in enumerate(bootstrap_lines)
    if line == expected_secret_command_lines[0]
]
if len(secret_command_starts) != 1:
    raise AssertionError(
        "dev-demo bootstrap must contain exactly one direct credential secret command"
    )
secret_command_lines = []
secret_command_index = secret_command_starts[0]
while True:
    secret_command_line = bootstrap_lines[secret_command_index]
    secret_command_lines.append(secret_command_line)
    if not secret_command_line.endswith("\\"):
        break
    secret_command_index += 1
    if secret_command_index >= len(bootstrap_lines):
        raise AssertionError("dev-demo bootstrap secret command is unterminated")
if secret_command_lines != expected_secret_command_lines:
    raise AssertionError(
        "dev-demo bootstrap credential secret must use the complete direct create argument block"
    )


for expected in (
    'cleanup_bootstrap_temp_dir() {',
    'if rm -rf "${BOOTSTRAP_SECRET_DIR}"; then',
    'echo "::error::Failed to remove dev-demo bootstrap credential files"',
    'if ! cleanup_bootstrap_temp_dir; then',
    'kubectl -n "${PREVIEW_NAMESPACE}" delete secret dev-demo-bootstrap-env --ignore-not-found',
    'cleanup_bootstrap_resources() {',
    'trap cleanup_bootstrap_resources EXIT',
    'if ! cleanup_bootstrap_secret; then',
    'cleanup_bootstrap_secret',
):
    if not any(expected in line for line in bootstrap_lines):
        raise AssertionError(
            f"dev-demo bootstrap step contract missing: {expected}"
        )
cleanup_success_start = bootstrap_lines.index(
    'if rm -rf "${BOOTSTRAP_SECRET_DIR}"; then'
)
cleanup_success_return = bootstrap_lines.index("return 0", cleanup_success_start)
clear_directory_lines = [
    index
    for index in range(cleanup_success_start + 1, cleanup_success_return)
    if bootstrap_lines[index] == "BOOTSTRAP_SECRET_DIR="
]
if len(clear_directory_lines) != 1:
    raise AssertionError(
        "dev-demo bootstrap temp directory must clear its variable only in the "
        "successful rm branch before return 0"
    )
assert_ordered_bootstrap_lines(
    (
        'echo "::error::Failed to remove dev-demo bootstrap credential files" >&2',
        "return 1",
    ),
    "dev-demo bootstrap temp directory removal failure must return failure",
)

try:
    manifest_start = bootstrap_manifest.index(
        "cat <<'EOF' | kubectl -n \"${PREVIEW_NAMESPACE}\" apply -f -\n"
    )
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
bootstrap_pod = yaml.safe_load(bootstrap_manifest[manifest_start:manifest_end])
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
env_from = containers[0].get("envFrom", [])
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
    r"(?<![A-Za-z0-9_./$-])(?:bash\s+)?(?:[.]/)?"
    r"(dev-tools/[A-Za-z0-9_./-]+[.]sh)(?![A-Za-z0-9_./-])"
)
summary_writers = []
root_dir = root.resolve()
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
            helper_path = (root / helper).resolve()
            try:
                helper_path.relative_to(root_dir)
            except ValueError:
                continue
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
    r"kubectl\b[^;&|]*\bget\s+secret\b[^;&|]*\|\s*base64\s+(?:-d|--decode)\b",
    re.IGNORECASE,
)
offending_writers = [
    (job_name, step_name)
    for job_name, step_name, source in summary_writers
    if forbidden_summary_reference.search(" ".join(source.split()))
]
if offending_writers:
    writers = ", ".join(
        f"{job_name}/{step_name}" for job_name, step_name in offending_writers
    )
    raise AssertionError(
        "dev-demo summaries must not reference bootstrap credential material; "
        f"offending summary writers: {writers}"
    )


class FakeSession:
    def __init__(self, chunks=None):
        self.chunks = list(chunks or [])
        self.sent = []
        self.closed = False

    def sendall(self, payload):
        self.sent.append(payload.decode("iso-8859-1"))

    def recv(self, _size=None):
        if self.chunks:
            chunk = self.chunks.pop(0)
            return chunk if _size is None else chunk.encode("iso-8859-1")
        return "" if _size is None else b""

    def settimeout(self, _timeout):
        return None

    def send(self, payload):
        self.sent.append(payload)


    def close(self):
        self.closed = True


class CommandResponseSession(FakeSession):
    def __init__(self, responses):
        super().__init__()
        self.responses = list(responses)

    def sendall(self, payload):
        super().sendall(payload)
        self.chunks = [self.responses.pop(0)]

    def send(self, payload):
        super().send(payload)
        self.chunks = [self.responses.pop(0)]


opened = []


def open_telnet():
    session = FakeSession(["OK WORLDS\n"])
    opened.append(session)
    return session


telnet_responses = run_telnet_smoke_session(
    "example.test",
    2323,
    [("WORLDS", ["OK WORLDS"], "WORLDS")],
    1,
    open_session=open_telnet,
)
assert telnet_responses == ["OK WORLDS\n"]
assert opened[0].sent == ["WORLDS\r\n"]
assert opened[0].closed is True


opened_ws = []


def open_ws():
    session = FakeSession(["OK LOGIN"])
    opened_ws.append(session)
    return session


ws_responses = run_websocket_smoke_session(
    open_ws,
    [("LOGIN demo swordfish", ["OK LOGIN"], "LOGIN")],
    1,
    session_label="contract websocket",
)
assert ws_responses == ["OK LOGIN"]
assert opened_ws[0].sent == ["LOGIN demo swordfish"]
assert opened_ws[0].closed is True


attempts = {"count": 0}


def open_after_retry():
    attempts["count"] += 1
    if attempts["count"] == 1:
        raise OSError("temporary failure")
    return FakeSession()


result = run_transport_session(
    open_after_retry,
    lambda session: session,
    "retrying session",
    retry_window_seconds=1,
    retry_interval_seconds=0,
)
assert isinstance(result, FakeSession)
assert attempts["count"] == 2


for transient_failure in (True, False):
    deadline_attempts = {"count": 0}

    def open_until_deadline():
        deadline_attempts["count"] += 1
        if transient_failure:
            return FakeSession()
        raise OSError("temporary failure")

    def fail_until_deadline(_session):
        raise smoke_common.TransientUpstreamSmokeFailure("temporary upstream failure")

    with patch("smoke_common.time.time", side_effect=[0, 0, 1]), patch(
        "smoke_common.time.sleep"
    ) as sleep:
        try:
            run_transport_session(
                open_until_deadline,
                fail_until_deadline,
                "deadline-bound session",
                retry_window_seconds=1,
                retry_interval_seconds=2,
            )
            raise AssertionError("deadline-bound retry unexpectedly opened another session")
        except (smoke_common.TransientUpstreamSmokeFailure, RuntimeError):
            pass
    sleep.assert_called_once_with(1)
    assert deadline_attempts["count"] == 1


upstream_attempts = []


def open_after_transient_upstream_failure():
    responses = (
        ["ERROR UNAVAILABLE Login is temporarily unavailable."]
        if not upstream_attempts
        else ["OK LOGIN"]
    )
    session = FakeSession(responses)
    upstream_attempts.append(session)
    return session


upstream_responses = run_telnet_smoke_session(
    "example.test",
    2323,
    [("LOGIN demo swordfish", ["OK LOGIN"], "LOGIN")],
    1,
    open_session=open_after_transient_upstream_failure,
    retry_window_seconds=1,
    retry_interval_seconds=0,
)
assert upstream_responses == ["OK LOGIN"]
assert len(upstream_attempts) == 2
assert all(session.closed for session in upstream_attempts)


for transport in ("telnet", "websocket"):
    later_failure_attempts = []

    def open_later_failure():
        session = CommandResponseSession(
            ["OK LOGIN", "ERROR UPSTREAM_FAILURE Gameplay unavailable."]
        )
        later_failure_attempts.append(session)
        return session

    try:
        steps = [
            ("LOGIN demo swordfish", ["OK LOGIN"], "LOGIN"),
            ("LOOK", ["A room"], "LOOK"),
        ]
        if transport == "telnet":
            run_telnet_smoke_session(
                "example.test",
                2323,
                steps,
                1,
                open_session=open_later_failure,
                retry_window_seconds=1,
                retry_interval_seconds=0,
            )
        else:
            run_websocket_smoke_session(
                open_later_failure,
                steps,
                1,
                retry_window_seconds=1,
                retry_interval_seconds=0,
            )
        raise AssertionError(f"{transport} later-step failure unexpectedly retried")
    except RuntimeError as exc:
        assert not isinstance(exc, smoke_common.TransientUpstreamSmokeFailure)
        assert "ERROR UPSTREAM_FAILURE" in str(exc)
    assert len(later_failure_attempts) == 1
    assert later_failure_attempts[0].closed is True

print("smoke transport contract checks passed")
PY
