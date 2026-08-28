#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

python3 - <<'PY' "$ROOT_DIR"
import sys
from pathlib import Path
from unittest.mock import patch

root = Path(sys.argv[1])
sys.path.insert(0, str(root / "dev-tools" / "smoke"))

import smoke_common
from smoke_common import run_telnet_smoke_session, run_transport_session, run_websocket_smoke_session


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


class FakeHttpResponse:
    def __init__(self, body):
        self.body = body
        self.headers = {}

    def __enter__(self):
        return self

    def __exit__(self, *_args):
        return False

    def read(self):
        return self.body


for body in (b"\xff", b"{ malformed"):
    with patch(
        "smoke_common.urllib.request.urlopen", return_value=FakeHttpResponse(body)
    ):
        try:
            smoke_common.http_request_json_with_headers("http://example.test", 1)
        except smoke_common.ProbeOperationalFailure as exc:
            assert "returned invalid JSON" in str(exc)
        else:
            raise AssertionError("invalid upstream HTTP JSON was not classified")


def unrelated_http_failure(*_args, **_kwargs):
    raise ValueError("unexpected HTTP client programming failure")


with patch(
    "smoke_common.urllib.request.urlopen", side_effect=unrelated_http_failure
):
    try:
        smoke_common.http_request_json_with_headers("http://example.test", 1)
    except ValueError as exc:
        assert str(exc) == "unexpected HTTP client programming failure"
    else:
        raise AssertionError("unrelated HTTP exception was incorrectly classified")

print("smoke transport contract checks passed")
PY

for script in \
  "$ROOT_DIR/services/game-session-service/websocket-login-look-smoke.sh" \
  "$ROOT_DIR/services/tcp-proxy-service/telnet-login-look-smoke.sh"; do
  if SMOKE_MUTATION_EXTENSION=invalid bash "$script" >/dev/null 2>&1; then
    echo "invalid mutation-extension value was accepted by $script" >&2
    exit 1
  fi
  if SMOKE_MUTATION_EXTENSION=true bash "$script" >/dev/null 2>&1; then
    echo "mutation extension ran without an isolation boundary: $script" >&2
    exit 1
  fi
  for project_name in docker smoke-full firemud-smoke- smoke-full--1 smoke-full-1- smoke-full-1-1-extra; do
    if SMOKE_MUTATION_EXTENSION=true SMOKE_MUTATION_BOUNDARY=run-owned-compose COMPOSE_PROJECT_NAME="$project_name" bash "$script" >/dev/null 2>&1; then
      echo "mutation extension accepted an invalid Compose project ($project_name): $script" >&2
      exit 1
    fi
  done
  if SMOKE_MUTATION_EXTENSION=true SMOKE_MUTATION_BOUNDARY=restricted-synthetic COMPOSE_PROJECT_NAME=firemud-smoke-contract bash "$script" >/dev/null 2>&1; then
    echo "mutation extension accepted unavailable synthetic isolation: $script" >&2
    exit 1
  fi
  grep -q 'SMOKE_MUTATION_EXTENSION=.*false' "$script"
  grep -q 'SMOKE_MUTATION_BOUNDARY=.*' "$script"
  grep -q 'run-owned-compose' "$script"
  grep -q 'smoke-full-' "$script"
  grep -q 'login_play_look_steps' "$script"
  grep -q 'gameplay_item_container_equipment_steps' "$script"
  boundary_line="$(grep -n 'Mutation extension requires SMOKE_MUTATION_BOUNDARY=run-owned-compose' "$script" | head -1 | cut -d: -f1)"
  python_line="$(grep -n '\$PYTHON.*<<' "$script" | head -1 | cut -d: -f1)"
  if [[ -z "$boundary_line" || -z "$python_line" || "$boundary_line" -ge "$python_line" ]]; then
    echo "mutation isolation preflight is not before service access in $script" >&2
    exit 1
  fi
done

for script in \
  "$ROOT_DIR/dev-tools/verify-fresh-bootstrap.sh" \
  "$ROOT_DIR/dev-tools/verify-restart-state.sh" \
  "$ROOT_DIR/dev-tools/verify-smoke-images.sh"; do
  if SMOKE_MUTATION_EXTENSION=true bash "$script" >/dev/null 2>&1; then
    echo "two-transport wrapper accepted unsupported mutation parity: $script" >&2
    exit 1
  fi
  grep -q 'independent transport identities/state are not proven' "$script"
  grep -q 'LOOK baseline proofs' "$script"
done

for script in "$ROOT_DIR/dev-tools/verify-fresh-bootstrap.sh" "$ROOT_DIR/dev-tools/verify-smoke-images.sh"; do
  grep -q 'require_run_owned_compose_project' "$script"
  grep -q 'firemud-smoke-' "$script"
  down_line="$(grep -n '^docker compose .*down -v --remove-orphans' "$script" | head -1 | cut -d: -f1)"
  guard_line="$(grep -n 'require_run_owned_compose_project$' "$script" | tail -1 | cut -d: -f1)"
  if [[ -z "$down_line" || -z "$guard_line" || "$guard_line" -ge "$down_line" ]]; then
    echo "destructive compose teardown is not guarded in $script" >&2
    exit 1
  fi
  if grep -q 'compose_up_with_retry' "$script"; then
    retry_call_line="$(grep -n '^compose_up_with_retry$' "$script" | tail -1 | cut -d: -f1)"
    if [[ -z "$retry_call_line" || "$guard_line" -ge "$retry_call_line" ]]; then
      echo "retry teardown is not guarded in $script" >&2
      exit 1
    fi
  fi
done

echo "smoke script boundary contract checks passed"
