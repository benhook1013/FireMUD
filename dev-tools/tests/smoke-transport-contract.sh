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
from smoke_common import run_telnet_smoke_session, run_transport_session, run_websocket_smoke_session

dev_demo_workflow = (root / ".github" / "workflows" / "dev-demo.yml").read_text()
for expected in (
    'create secret generic dev-demo-bootstrap-env',
    '--from-file=DEMO_SMOKE_EMAIL="${BOOTSTRAP_SECRET_DIR}/email"',
    '--from-file=DEMO_SMOKE_PASSWORD="${BOOTSTRAP_SECRET_DIR}/password"',
    '--from-file=DEMO_SMOKE_USERNAME="${BOOTSTRAP_SECRET_DIR}/username"',
    'cleanup_bootstrap_secret',
):
    if expected not in dev_demo_workflow:
        raise AssertionError(
            f"dev-demo bootstrap environment contract missing: {expected}"
        )

workflow = yaml.safe_load(dev_demo_workflow)
bootstrap_step = next(
    step
    for step in workflow["jobs"]["dev-demo-deploy"]["steps"]
    if step.get("name") == "Create dev-demo smoke account"
)
bootstrap_manifest = bootstrap_step["run"]
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
bootstrap_pod = yaml.safe_load(bootstrap_manifest[manifest_start:manifest_end])
env_from = bootstrap_pod["spec"]["containers"][0].get("envFrom", [])
if env_from != [{"secretRef": {"name": "dev-demo-bootstrap-env"}}]:
    raise AssertionError(
        "dev-demo bootstrap pod must import dev-demo-bootstrap-env"
    )
if "Demo login password: ${DEMO_SMOKE_PASSWORD}" in dev_demo_workflow:
    raise AssertionError("dev-demo summary must not print the smoke password")


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
        smoke_common.verify_smoke_account(
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
                smoke_common.verify_smoke_account(
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
            smoke_common.verify_smoke_account(
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
        smoke_common.verify_smoke_account(
            "http://account.test", "demo@example.com", "swordfish", 5
        )
    except RuntimeError as exc:
        assert str(exc) == "Smoke account validation failed with status 400"
        assert "sensitive non-retryable detail" not in str(exc)
    else:
        raise AssertionError("Expected non-retryable account validation failure")
assert non_retryable_urlopen.call_count == 1


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
