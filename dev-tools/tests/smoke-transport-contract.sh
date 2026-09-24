#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
RUN_OWNED_COMPOSE_HELPER="$ROOT_DIR/dev-tools/smoke/run-owned-compose.sh"

command -v openssl >/dev/null 2>&1 || {
    echo "openssl is required to generate TLS certificates for this smoke contract" >&2
    exit 1
}

python3 - <<'PY' "$ROOT_DIR"
import contextlib
import io
import json
import socket
import ssl
import subprocess
import sys
import tempfile
import threading
import time
from pathlib import Path
from unittest.mock import patch

root = Path(sys.argv[1])
sys.path.insert(0, str(root / "dev-tools" / "smoke"))

import smoke_common
from smoke_common import (
    open_telnet_socket,
    run_telnet_smoke_session,
    run_transport_session,
    run_websocket_smoke_session,
)


for local_host in (
    "localhost",
    "LOCALHOST",
    "localhost.",
    "127.0.0.1",
    "127.255.255.254",
    "::1",
    "0:0:0:0:0:0:0:1",
    "::ffff:127.0.0.1",
):
    assert smoke_common.is_localhost_equivalent(local_host), local_host

for remote_host in (
    "",
    "remotehost",
    "example.test",
    "localhost.example.test",
    "0.0.0.0",
    "192.168.1.10",
    "203.0.113.10",
    "::",
    "::ffff:192.168.1.10",
):
    assert not smoke_common.is_localhost_equivalent(remote_host), remote_host


class FakeReadinessResponse:
    def __init__(self, status, body):
        self.status = status
        self.body = body

    def __enter__(self):
        return self

    def __exit__(self, *_args):
        return False

    def read(self):
        return self.body


for status, body, expected in (
    (200, b'{"status":"UP"}', True),
    (204, b'{"status":"UP"}', True),
    (302, b'{"status":"UP"}', False),
    (404, b'{"status":"UP"}', False),
    (200, b'{"detail":"\\"status\\":\\"UP\\"","status":"DOWN"}', False),
    (200, b'{"components":{"status":"UP"}}', False),
    (200, b'{"status":"up"}', False),
    (200, b'not-json', False),
    (200, b'\xff', False),
):
    with patch.object(
        smoke_common.urllib.request,
        "urlopen",
        return_value=FakeReadinessResponse(status, body),
    ):
        assert smoke_common.http_readiness_up("https://example.test/readiness", 1) is expected

assert smoke_common.telnet_look_room_id(
    "OK LOOK\n\x1b[32mRoom: \x1b[0mStart (ID: room-123)\nShort: A room\n"
) == "room-123"
assert smoke_common.telnet_look_room_id(
    "OK LOOK\r\nRoom: Start (ID: room-123)\r\nShort: A room\r\n"
) == "room-123"
for malformed_look in ("OK LOOK", "Room: Start (ID: )", "Room: Start (ID: x y)"):
    try:
        smoke_common.telnet_look_room_id(malformed_look)
    except smoke_common.ProbeOperationalFailure:
        pass
    else:
        raise AssertionError("malformed Telnet LOOK view accepted")


class FakeSession:
    def __init__(self, chunks=None):
        self.chunks = list(chunks or [])
        self.sent = []
        self.wire_sent = []
        self.closed = False

    def sendall(self, payload):
        self.wire_sent.append(payload)
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


class FakeTlsContext:
    def __init__(self, wrapped=None, failure=None):
        self.wrapped = wrapped or FakeSession()
        self.failure = failure
        self.check_hostname = False
        self.verify_mode = ssl.CERT_NONE
        self.wrap_calls = []

    def wrap_socket(self, raw_socket, *, server_hostname):
        self.wrap_calls.append((raw_socket, server_hostname))
        if self.failure is not None:
            raise self.failure
        return self.wrapped


raw_plaintext_socket = FakeSession()
with patch(
    "smoke_common.socket.create_connection", return_value=raw_plaintext_socket
):
    assert (
        open_telnet_socket(
            "127.0.0.1",
            2323,
            1,
            tls_enabled=False,
        )
        is raw_plaintext_socket
    )


with patch("smoke_common.socket.create_connection") as connect:
    try:
        open_telnet_socket(
            "203.0.113.10",
            2323,
            1,
            tls_enabled=False,
        )
    except ValueError as exc:
        assert str(exc) == smoke_common.PLAINTEXT_TELNET_HOST_ERROR
    else:
        raise AssertionError("plaintext Telnet accepted a non-loopback host")
connect.assert_not_called()


raw_tls_socket = FakeSession()
wrapped_tls_socket = FakeSession()
tls_context = FakeTlsContext(wrapped=wrapped_tls_socket)
with patch(
    "smoke_common.socket.create_connection", return_value=raw_tls_socket
) as connect, patch(
    "smoke_common.ssl.create_default_context", return_value=tls_context
) as create_context:
    result = open_telnet_socket(
        "203.0.113.10",
        2323,
        1,
        tls_enabled=True,
        tls_server_hostname="preview.example.test",
        tls_ca_file="ca.pem",
    )
assert result is wrapped_tls_socket
assert connect.call_count == 1
create_context.assert_called_once_with(cafile="ca.pem")
assert tls_context.wrap_calls == [(raw_tls_socket, "preview.example.test")]
assert tls_context.check_hostname is True
assert tls_context.verify_mode == ssl.CERT_REQUIRED
assert raw_tls_socket.closed is False


failed_raw_tls_socket = FakeSession()
failed_tls_context = FakeTlsContext(failure=ssl.SSLError("certificate mismatch"))
with patch(
    "smoke_common.socket.create_connection", return_value=failed_raw_tls_socket
) as connect, patch(
    "smoke_common.ssl.create_default_context", return_value=failed_tls_context
) as create_context:
    try:
        open_telnet_socket(
            "203.0.113.10",
            2323,
            1,
            tls_enabled=True,
            tls_server_hostname="preview.example.test",
        )
    except ssl.SSLError:
        pass
    else:
        raise AssertionError("TLS failure unexpectedly succeeded")
assert failed_raw_tls_socket.closed is True
assert connect.call_count == 1
create_context.assert_called_once_with()


try:
    open_telnet_socket("example.test", 2323, 1)
except TypeError as exc:
    assert "tls_enabled" in str(exc)
else:
    raise AssertionError("Telnet socket helper accepted an omitted TLS mode")

for invalid_tls_enabled in (None, "true", 0, 1, [], {}):
    try:
        open_telnet_socket(
            "example.test", 2323, 1, tls_enabled=invalid_tls_enabled
        )
    except TypeError as exc:
        assert "tls_enabled" in str(exc)
    else:
        raise AssertionError(
            f"Telnet socket helper accepted non-boolean TLS mode: {invalid_tls_enabled!r}"
        )

for invalid_options in (
    {"tls_enabled": True},
    {"tls_enabled": False, "tls_server_hostname": "example.test"},
    {"tls_enabled": False, "tls_ca_file": "ca.pem"},
):
    try:
        open_telnet_socket("example.test", 2323, 1, **invalid_options)
    except ValueError:
        pass
    else:
        raise AssertionError(f"invalid Telnet TLS options were accepted: {invalid_options}")


mixed_response_chunks = iter(
    ["OK LOGIN account=demo\nERROR INVALID_CREDENTIALS Login failed.\n"]
)
try:
    smoke_common.wait_for_incremental_response(
        lambda: next(mixed_response_chunks, ""),
        [],
        0,
        ["OK LOGIN"],
        1,
        "".join,
    )
except smoke_common.ProbeOperationalFailure as exc:
    assert "ERROR INVALID_CREDENTIALS" in str(exc)
else:
    raise AssertionError("mixed success and explicit failure response passed smoke")


trailing_response_chunks = iter(["OK LOOK room=R-1021\n"])
try:
    smoke_common.wait_for_incremental_response(
        lambda: next(trailing_response_chunks, ""),
        [],
        0,
        ["OK LOOK"],
        1,
        "".join,
        lambda: "DISCONNECT backend_unavailable Gateway bridge closed.\n",
    )
except smoke_common.ProbeOperationalFailure as exc:
    assert "DISCONNECT backend_unavailable" in str(exc)
else:
    raise AssertionError("explicit failure in trailing drain passed smoke")


expected_error_response = (
    "ERROR SLOT_INCOMPATIBLE Iron Boots cannot be worn by this body layout\n"
)
expected_error_substrings = [
    "ERROR SLOT_INCOMPATIBLE",
    "Iron Boots cannot be worn by this body layout",
]
expected_error_chunks = iter([expected_error_response])
assert smoke_common.wait_for_incremental_response(
    lambda: next(expected_error_chunks, ""),
    [],
    0,
    expected_error_substrings,
    1,
    "".join,
) == expected_error_response


for additional_failure in (
    "ERROR UPSTREAM_FAILURE Gameplay unavailable.\n",
    "DISCONNECT backend_unavailable Gateway bridge closed.\n",
):
    expected_error_with_additional_failure = iter(
        [expected_error_response + additional_failure]
    )
    try:
        smoke_common.wait_for_incremental_response(
            lambda: next(expected_error_with_additional_failure, ""),
            [],
            0,
            expected_error_substrings,
            1,
            "".join,
        )
    except smoke_common.ProbeOperationalFailure as exc:
        assert additional_failure.strip() in str(exc)
    else:
        raise AssertionError(
            f"{additional_failure.strip()} following an expected explicit error "
            "passed smoke"
        )


secret = "contract-password"
login_command = f"LOGIN demo@example.test {secret}"
login_response = (
    f"login   demo@example.test   {secret}\n"
    "OK LOGIN account=demo\n"
    f"Diagnostic credential={secret}; proof remains visible.\n"
)
login_session = FakeSession([login_response])
login_step_results = []
login_output = io.StringIO()
with contextlib.redirect_stdout(login_output):
    raw_login_response = smoke_common.send_telnet_command_and_expect(
        login_session,
        [],
        login_command,
        ["OK LOGIN"],
        "LOGIN",
        1,
        drain_timeout=0,
        step_results=login_step_results,
    )
assert secret in raw_login_response, "protocol response must remain unchanged"
assert login_session.sent == [f"{login_command}\r\n"]
assert secret not in login_output.getvalue()
assert secret not in json.dumps(login_step_results)
assert login_step_results[0]["command"] == "LOGIN demo@example.test [REDACTED]"
assert "OK LOGIN account=demo" in login_output.getvalue()
assert "Diagnostic credential=[REDACTED]; proof remains visible." in login_output.getvalue()


iac_secret = "p\u00ffss"
iac_login_command = f"LOGIN demo@example.test {iac_secret}"
iac_login_response = f"{iac_login_command}\nOK LOGIN account=demo\n"
iac_session = CommandResponseSession(["OK SAY\n", iac_login_response])
iac_step_results = []
with contextlib.redirect_stdout(io.StringIO()):
    smoke_common.send_telnet_command_and_expect(
        iac_session,
        [],
        "SAY \u00ff",
        ["OK SAY"],
        "SAY",
        1,
        drain_timeout=0,
    )
    iac_response = smoke_common.send_telnet_command_and_expect(
        iac_session,
        [],
        iac_login_command,
        ["OK LOGIN"],
        "LOGIN",
        1,
        drain_timeout=0,
        step_results=iac_step_results,
    )
assert iac_session.wire_sent == [
    b"SAY \xff\xff\r\n",
    b"LOGIN demo@example.test p\xff\xffss\r\n",
]
assert iac_secret in iac_response
assert iac_secret not in json.dumps(iac_step_results)


logon_command = f"LOGON demo@example.test {secret}"
logon_session = FakeSession([login_response])
logon_step_results = []
logon_output = io.StringIO()
with contextlib.redirect_stdout(logon_output):
    raw_logon_response = smoke_common.send_telnet_command_and_expect(
        logon_session,
        [],
        logon_command,
        ["OK LOGIN"],
        "LOGON",
        1,
        drain_timeout=0,
        step_results=logon_step_results,
    )
assert secret in raw_logon_response, "protocol response must remain unchanged"
assert secret not in logon_output.getvalue()
assert secret not in json.dumps(logon_step_results)
assert logon_step_results[0]["command"] == "LOGON demo@example.test [REDACTED]"
assert "Diagnostic credential=[REDACTED]; proof remains visible." in logon_output.getvalue()


mixed_case_secret = "MiXeD  Credential"
mixed_case_command = f"LoGiN demo@example.test {mixed_case_secret}"
mixed_case_response = (
    "lOgIn demo@example.test mIxEd credential\n"
    "OK LOGIN account=demo\n"
    "Diagnostic credential=mIXeD credential; proof remains visible.\n"
)
mixed_case_telnet_steps = []
mixed_case_telnet_output = io.StringIO()
with contextlib.redirect_stdout(mixed_case_telnet_output):
    mixed_case_telnet_raw = smoke_common.send_telnet_command_and_expect(
        FakeSession([mixed_case_response]),
        [],
        mixed_case_command,
        ["OK LOGIN"],
        "LOGIN",
        1,
        drain_timeout=0,
        step_results=mixed_case_telnet_steps,
    )
assert "mIxEd credential" in mixed_case_telnet_raw
for credential_form in (mixed_case_secret, "MiXeD Credential", "mIxEd credential"):
    assert credential_form.casefold() not in mixed_case_telnet_output.getvalue().casefold()
    assert credential_form.casefold() not in json.dumps(mixed_case_telnet_steps).casefold()
assert "Diagnostic credential=[REDACTED]; proof remains visible." in (
    mixed_case_telnet_output.getvalue()
)
assert mixed_case_telnet_steps[0]["response"].startswith(
    "lOgIn demo@example.test [REDACTED]"
)


class DeadlineBoundSession(FakeSession):
    def __init__(self, chunks=None):
        super().__init__(chunks)
        self.timeouts = []

    def settimeout(self, timeout):
        self.timeouts.append(timeout)

    def recv(self, _size=None):
        if self.chunks:
            chunk = self.chunks.pop(0)
            return chunk if _size is None else chunk.encode("iso-8859-1")
        time.sleep(self.timeouts[-1])
        raise TimeoutError


deadline_session = DeadlineBoundSession(["OK LOOK room=demo\n"])
deadline_response = smoke_common.send_telnet_command_and_expect(
    deadline_session,
    [],
    "LOOK",
    ["OK LOOK"],
    "LOOK",
    0.08,
    drain_timeout=1.0,
)
assert deadline_response == "OK LOOK room=demo\n"
assert deadline_session.timeouts
assert max(deadline_session.timeouts) <= 0.09


class BlockingSendSession(FakeSession):
    def __init__(self):
        super().__init__()
        self.timeouts = []

    def settimeout(self, timeout):
        self.timeouts.append(timeout)

    def sendall(self, payload):
        self.wire_sent.append(payload)
        time.sleep(self.timeouts[-1])
        raise TimeoutError("send blocked")


blocked_send_session = BlockingSendSession()
try:
    smoke_common.send_telnet_command_and_expect(
        blocked_send_session,
        [],
        "LOOK",
        ["OK LOOK"],
        "LOOK",
        0.08,
    )
except TimeoutError as exc:
    assert str(exc) == "send blocked"
else:
    raise AssertionError("blocked Telnet send unexpectedly completed")
assert blocked_send_session.timeouts
assert max(blocked_send_session.timeouts) <= 0.09


blocked_receive_session = DeadlineBoundSession()
try:
    smoke_common.send_telnet_command_and_expect(
        blocked_receive_session,
        [],
        "LOOK",
        ["OK LOOK"],
        "LOOK",
        0.08,
    )
except smoke_common.ProbeOperationalFailure:
    pass
else:
    raise AssertionError("blocked Telnet receive unexpectedly completed")
assert blocked_receive_session.timeouts
assert max(blocked_receive_session.timeouts) <= 0.09


class TimedWebSocket(FakeSession):
    def __init__(self, chunks=None, *, block_send=False):
        super().__init__(chunks)
        self.timeouts = []
        self.block_send = block_send

    def settimeout(self, timeout):
        self.timeouts.append(timeout)

    def send(self, payload):
        self.sent.append(payload)
        if self.block_send:
            time.sleep(self.timeouts[-1])
            raise TimeoutError("send blocked")

    def recv(self, _size=None):
        if self.chunks:
            return self.chunks.pop(0)
        time.sleep(self.timeouts[-1])
        raise TimeoutError("receive blocked")


blocked_websocket_send = TimedWebSocket(block_send=True)
try:
    smoke_common.send_websocket_command_and_expect(
        blocked_websocket_send,
        [],
        "LOOK",
        ["OK LOOK"],
        "LOOK",
        0.08,
    )
except TimeoutError as exc:
    assert str(exc) == "send blocked"
else:
    raise AssertionError("blocked WebSocket send unexpectedly completed")
assert blocked_websocket_send.timeouts
assert max(blocked_websocket_send.timeouts) <= 0.09


blocked_websocket_receive = TimedWebSocket()
try:
    smoke_common.send_websocket_command_and_expect(
        blocked_websocket_receive,
        [],
        "LOOK",
        ["OK LOOK"],
        "LOOK",
        0.08,
    )
except smoke_common.ProbeOperationalFailure:
    pass
else:
    raise AssertionError("blocked WebSocket receive unexpectedly completed")
assert blocked_websocket_receive.timeouts
assert max(blocked_websocket_receive.timeouts) <= 0.09


blocked_websocket_drain = TimedWebSocket(["OK LOOK room=demo"])
drained_response = smoke_common.send_websocket_command_and_expect(
    blocked_websocket_drain,
    [],
    "LOOK",
    ["OK LOOK"],
    "LOOK",
    0.08,
)
assert drained_response == "OK LOOK room=demo"
assert blocked_websocket_drain.timeouts
assert max(blocked_websocket_drain.timeouts) <= 0.09


for invalid_command in (
    "LOOK\nNORTH",
    "LOGIN demo@example.test secret-with-newline\nINJECT",
    "LOOK \u2603",
):
    invalid_session = FakeSession(["OK SHOULD NOT ARRIVE\n"])
    try:
        smoke_common.send_telnet_command_and_expect(
            invalid_session,
            [],
            invalid_command,
            ["OK SHOULD NOT ARRIVE"],
            "INVALID",
            1,
        )
    except ValueError as exc:
        assert str(exc) == smoke_common.INVALID_COMMAND_LINE_ERROR
    else:
        raise AssertionError(f"embedded line break was accepted: {invalid_command!r}")
    assert invalid_session.sent == []


trailing_newline_session = FakeSession(["OK LOOK room=demo\n"])
smoke_common.send_telnet_command_and_expect(
    trailing_newline_session,
    [],
    "LOOK\n",
    ["OK LOOK"],
    "LOOK",
    1,
    drain_timeout=0,
)
assert trailing_newline_session.sent == ["LOOK\r\n"]


websocket_login_session = FakeSession([login_response])
websocket_login_step_results = []
websocket_login_output = io.StringIO()
with contextlib.redirect_stdout(websocket_login_output):
    raw_websocket_login_response = smoke_common.send_websocket_command_and_expect(
        websocket_login_session,
        [],
        login_command,
        ["OK LOGIN"],
        "LOGIN",
        1,
        step_results=websocket_login_step_results,
    )
assert secret in raw_websocket_login_response, "protocol response must remain unchanged"
assert websocket_login_session.sent == [login_command]
assert secret not in websocket_login_output.getvalue()
assert secret not in json.dumps(websocket_login_step_results)
assert (
    websocket_login_step_results[0]["command"]
    == "LOGIN demo@example.test [REDACTED]"
)
assert "OK LOGIN account=demo" in websocket_login_output.getvalue()


mixed_case_websocket_steps = []
mixed_case_websocket_output = io.StringIO()
with contextlib.redirect_stdout(mixed_case_websocket_output):
    mixed_case_websocket_raw = smoke_common.send_websocket_command_and_expect(
        FakeSession([mixed_case_response]),
        [],
        mixed_case_command,
        ["OK LOGIN"],
        "LOGIN",
        1,
        step_results=mixed_case_websocket_steps,
    )
assert "mIxEd credential" in mixed_case_websocket_raw
for credential_form in (mixed_case_secret, "MiXeD Credential", "mIxEd credential"):
    assert (
        credential_form.casefold()
        not in mixed_case_websocket_output.getvalue().casefold()
    )
    assert (
        credential_form.casefold()
        not in json.dumps(mixed_case_websocket_steps).casefold()
    )
assert "Diagnostic credential=[REDACTED]; proof remains visible." in (
    mixed_case_websocket_output.getvalue()
)
assert mixed_case_websocket_steps[0]["response"].startswith(
    "lOgIn demo@example.test [REDACTED]"
)


failing_login_chunks = iter(
    [f"OK LOGIN account=demo\nERROR AUTH_FAILURE credential={secret}\n"]
)
try:
    smoke_common.wait_for_incremental_response(
        lambda: next(failing_login_chunks, ""),
        [],
        0,
        ["OK LOGIN"],
        1,
        "".join,
        sanitize_response=lambda response: smoke_common.redact_login_credential(
            response, login_command
        ),
    )
except smoke_common.ProbeOperationalFailure as exc:
    assert secret not in str(exc)
    assert "ERROR AUTH_FAILURE credential=[REDACTED]" in str(exc)
else:
    raise AssertionError("credential-bearing mixed failure unexpectedly passed")


mixed_failure_secret = "FaIlUrE  Token"
mixed_failure_response = (
    "OK LOGIN account=demo\n"
    "ERROR AUTH_FAILURE credential=fAiLuRe token\n"
)
for transport in ("telnet", "websocket"):
    try:
        if transport == "telnet":
            smoke_common.send_telnet_command_and_expect(
                FakeSession([mixed_failure_response]),
                [],
                f"LOGIN demo@example.test {mixed_failure_secret}",
                ["OK LOGIN"],
                "LOGIN",
                1,
                drain_timeout=0,
            )
        else:
            smoke_common.send_websocket_command_and_expect(
                FakeSession([mixed_failure_response]),
                [],
                f"LOGIN demo@example.test {mixed_failure_secret}",
                ["OK LOGIN"],
                "LOGIN",
                1,
            )
    except smoke_common.ProbeOperationalFailure as exc:
        diagnostic = str(exc)
        assert mixed_failure_secret.casefold() not in diagnostic.casefold()
        assert "fAiLuRe token".casefold() not in diagnostic.casefold()
        assert "ERROR AUTH_FAILURE credential=[REDACTED]" in diagnostic
    else:
        raise AssertionError(
            f"{transport} mixed-case credential failure unexpectedly passed"
        )


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
    tls_enabled=False,
)
assert telnet_responses == ["OK WORLDS\n"]
assert opened[0].sent == ["WORLDS\r\n"]
assert opened[0].closed is True


# Transport helper proof only: command sequencing is not live Game Session proof.
command_plan_session = CommandResponseSession(
    ["OK LOGIN\n", "OK MOVE NORTH\n", "OK SAY hello\n"]
)
command_plan_results = []
command_plan_responses = run_telnet_smoke_session(
    "example.test",
    2323,
    [
        ("LOGIN demo swordfish", ["OK LOGIN"], "LOGIN"),
        ("NORTH", ["OK MOVE NORTH"], "NORTH"),
        ("SAY hello", ["OK SAY hello"], "SAY"),
    ],
    1,
    open_session=lambda: command_plan_session,
    step_results=command_plan_results,
    tls_enabled=False,
)
assert command_plan_session.sent == [
    "LOGIN demo swordfish\r\n",
    "NORTH\r\n",
    "SAY hello\r\n",
]
assert command_plan_responses == [
    "OK LOGIN\n",
    "OK MOVE NORTH\n",
    "OK SAY hello\n",
]
assert [result["label"] for result in command_plan_results] == [
    "LOGIN",
    "NORTH",
    "SAY",
]
assert [result["response"] for result in command_plan_results] == [
    "OK LOGIN",
    "OK MOVE NORTH",
    "OK SAY hello",
]
assert command_plan_session.closed is True
class SessionFakeTlsContext:
    def __init__(self, wrapped_session):
        self.wrapped_session = wrapped_session
        self.server_hostname = None

    def wrap_socket(self, raw_socket, server_hostname):
        assert raw_socket is session_raw_tls_socket
        self.server_hostname = server_hostname
        return self.wrapped_session


session_raw_tls_socket = FakeSession()
session_wrapped_tls_socket = FakeSession(["OK WORLDS\n"])
session_tls_context = SessionFakeTlsContext(session_wrapped_tls_socket)
with patch(
    "smoke_common.socket.create_connection", return_value=session_raw_tls_socket
) as create_connection, patch(
    "smoke_common.ssl.create_default_context", return_value=session_tls_context
) as create_default_context:
    tls_responses = run_telnet_smoke_session(
        "preview.example.test",
        32042,
        [("WORLDS", ["OK WORLDS"], "WORLDS")],
        1,
        tls_enabled=True,
        tls_ca_file="/etc/ssl/certs/preview-ca.pem",
        tls_server_hostname="preview.example.test",
    )
assert tls_responses == ["OK WORLDS\n"]
create_connection.assert_called_once_with(
    ("preview.example.test", 32042), timeout=1
)
create_default_context.assert_called_once_with(
    cafile="/etc/ssl/certs/preview-ca.pem"
)
assert session_tls_context.server_hostname == "preview.example.test"
assert session_wrapped_tls_socket.sent == ["WORLDS\r\n"]
assert session_wrapped_tls_socket.closed is True
assert session_raw_tls_socket.closed is False


plaintext_socket = FakeSession(["OK WORLDS\n"])
with patch(
    "smoke_common.socket.create_connection", return_value=plaintext_socket
) as create_plaintext_connection, patch(
    "smoke_common.ssl.create_default_context"
) as create_plaintext_context:
    assert open_telnet_socket(
        "127.0.0.1", 2323, 1, tls_enabled=False
    ) is plaintext_socket
create_plaintext_connection.assert_called_once_with(("127.0.0.1", 2323), timeout=1)
create_plaintext_context.assert_not_called()


def generate_tls_certificates(certificate_dir):
    ca_key = certificate_dir / "ca-key.pem"
    ca_certificate = certificate_dir / "ca-cert.pem"
    server_key = certificate_dir / "server-key.pem"
    server_csr = certificate_dir / "server.csr.pem"
    server_certificate = certificate_dir / "server-cert.pem"
    server_extensions = certificate_dir / "server-extensions.cnf"

    subprocess.run(
        ["openssl", "genpkey", "-algorithm", "RSA", "-pkeyopt", "rsa_keygen_bits:2048", "-out", str(ca_key)],
        check=True,
        capture_output=True,
        text=True,
    )
    subprocess.run(
        [
            "openssl",
            "req",
            "-x509",
            "-new",
            "-key",
            str(ca_key),
            "-sha256",
            "-days",
            "1",
            "-out",
            str(ca_certificate),
            "-subj",
            "/CN=FireMUD smoke test CA",
            "-addext",
            "basicConstraints=critical,CA:TRUE,pathlen:1",
            "-addext",
            "keyUsage=critical,keyCertSign,cRLSign",
        ],
        check=True,
        capture_output=True,
        text=True,
    )
    subprocess.run(
        ["openssl", "genpkey", "-algorithm", "RSA", "-pkeyopt", "rsa_keygen_bits:2048", "-out", str(server_key)],
        check=True,
        capture_output=True,
        text=True,
    )
    subprocess.run(
        [
            "openssl",
            "req",
            "-new",
            "-key",
            str(server_key),
            "-out",
            str(server_csr),
            "-subj",
            "/CN=localhost",
        ],
        check=True,
        capture_output=True,
        text=True,
    )
    server_extensions.write_text(
        "basicConstraints=critical,CA:FALSE\n"
        "keyUsage=critical,digitalSignature,keyEncipherment\n"
        "extendedKeyUsage=serverAuth\n"
        "subjectAltName=DNS:localhost,IP:127.0.0.1\n"
    )
    subprocess.run(
        [
            "openssl",
            "x509",
            "-req",
            "-in",
            str(server_csr),
            "-CA",
            str(ca_certificate),
            "-CAkey",
            str(ca_key),
            "-CAcreateserial",
            "-out",
            str(server_certificate),
            "-days",
            "1",
            "-sha256",
            "-extfile",
            str(server_extensions),
        ],
        check=True,
        capture_output=True,
        text=True,
    )
    return ca_certificate, server_certificate, server_key


with tempfile.TemporaryDirectory(prefix="firemud-smoke-tls-") as certificate_directory:
    ca_certificate, server_certificate, server_key = generate_tls_certificates(
        Path(certificate_directory)
    )
    server_context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    server_context.load_cert_chain(
        certfile=str(server_certificate),
        keyfile=str(server_key),
    )
    server_socket = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    server_socket.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    server_socket.bind(("127.0.0.1", 0))
    server_socket.listen(1)
    server_socket.settimeout(5)
    server_port = server_socket.getsockname()[1]
    server_commands = []
    server_errors = []


    def serve_tls_telnet():
        try:
            raw_connection, _address = server_socket.accept()
            with raw_connection:
                with server_context.wrap_socket(
                    raw_connection,
                    server_side=True,
                ) as tls_connection:
                    command = tls_connection.recv(4096)
                    server_commands.append(command)
                    tls_connection.sendall(b"OK WORLDS\n")
        except Exception as exc:
            server_errors.append(exc)


    server_thread = threading.Thread(target=serve_tls_telnet, daemon=True)
    server_thread.start()
    try:
        local_tls_responses = run_telnet_smoke_session(
            "127.0.0.1",
            server_port,
            [("WORLDS", ["OK WORLDS"], "WORLDS")],
            5,
            tls_enabled=True,
            tls_ca_file=str(ca_certificate),
            tls_server_hostname="localhost",
        )
    finally:
        server_socket.close()
        server_thread.join(timeout=5)
    assert not server_thread.is_alive(), "local TLS server thread did not finish"
    assert not server_errors, f"local TLS server failed: {server_errors!r}"
    assert server_commands == [b"WORLDS\r\n"]
    assert local_tls_responses == ["OK WORLDS\n"]


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
    tls_enabled=False,
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
                tls_enabled=False,
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


post_command_failure_attempts = []


def open_after_command_failure():
    session = FakeSession()
    post_command_failure_attempts.append(session)
    return session


def fail_after_login_was_sent(session):
    session.sent.append("LOGIN demo swordfish")
    raise OSError("connection lost after LOGIN")


try:
    run_transport_session(
        open_after_command_failure,
        fail_after_login_was_sent,
        "post-command failure session",
        retry_window_seconds=1,
        retry_interval_seconds=0,
    )
except smoke_common.ProbeOperationalFailure as exc:
    assert "Failed during post-command failure session" in str(exc)
else:
    raise AssertionError("post-command OSError unexpectedly retried")
assert len(post_command_failure_attempts) == 1
assert post_command_failure_attempts[0].closed is True


class CloseFailureSession(FakeSession):
    def close(self):
        self.closed = True
        raise OSError("close failed")


close_failure_attempts = []


def open_close_failure_session():
    session = CloseFailureSession()
    close_failure_attempts.append(session)
    return session


try:
    run_transport_session(
        open_close_failure_session,
        lambda _session: "completed",
        "close failure session",
        retry_window_seconds=1,
        retry_interval_seconds=0,
    )
except smoke_common.ProbeOperationalFailure as exc:
    assert "Failed to close close failure session" in str(exc)
else:
    raise AssertionError("close OSError unexpectedly retried")
assert len(close_failure_attempts) == 1
assert close_failure_attempts[0].closed is True


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


item_steps = smoke_common.gameplay_item_container_equipment_steps(
    "demo@example.com",
    "swordfish",
    "OK WORLDS",
    "OK LOGIN",
    "OK PLAY",
    "OK LOOK",
)
get_index = next(index for index, step in enumerate(item_steps) if step[0] == "GET Torch")
assert item_steps[get_index + 1] == (
    "INVENTORY",
    ["Inventory:", "- Torch"],
    "INVENTORY after GET",
)

print("smoke transport contract checks passed")
PY

assert_command_rejects() {
  local expected_substring="$1"
  shift
  local output status
  set +e
  output="$("$@" 2>&1)"
  status=$?
  set -e
  if [[ "$status" -eq 0 ]]; then
    echo "expected command to fail, but it succeeded: $*" >&2
    exit 1
  fi
  if [[ "$output" != *"$expected_substring"* ]]; then
    echo "expected failed command output to contain '$expected_substring': $*" >&2
    echo "$output" >&2
    exit 1
  fi
}

for script in \
  "$ROOT_DIR/services/game-session-service/websocket-login-look-smoke.sh" \
  "$ROOT_DIR/services/tcp-proxy-service/telnet-login-look-smoke.sh"; do
  assert_command_rejects \
    "SMOKE_MUTATION_EXTENSION must be boolean" \
    env SMOKE_MUTATION_EXTENSION=invalid bash "$script"
  assert_command_rejects \
    "Mutation extension requires SMOKE_MUTATION_BOUNDARY=run-owned-compose" \
    env SMOKE_MUTATION_EXTENSION=true bash "$script"
  for project_name in docker smoke-full firemud-smoke- smoke-full--1 smoke-full-1- smoke-full-1-1-extra; do
    assert_command_rejects \
      "COMPOSE_PROJECT_NAME must exactly match firemud-smoke-contract-local" \
      env SMOKE_MUTATION_EXTENSION=true \
      SMOKE_MUTATION_BOUNDARY=run-owned-compose \
      GITHUB_ACTIONS=false \
      FIREMUD_SMOKE_RUN_ID=contract-local \
      COMPOSE_PROJECT_NAME="$project_name" bash "$script"
  done
  assert_command_rejects \
    "SMOKE_MUTATION_BOUNDARY=restricted-synthetic is unavailable" \
    env SMOKE_MUTATION_EXTENSION=true \
    SMOKE_MUTATION_BOUNDARY=restricted-synthetic \
    GITHUB_ACTIONS=false \
    COMPOSE_PROJECT_NAME=firemud-smoke-contract bash "$script"
  if [[ "$script" == *"tcp-proxy-service/telnet-login-look-smoke.sh" ]]; then
    assert_command_rejects \
      "Plaintext Telnet smoke requires a localhost-equivalent target" \
      env SMOKE_TELNET_HOST=remotehost bash "$script"
  fi
  grep -q 'SMOKE_MUTATION_EXTENSION=.*false' "$script"
  grep -q 'SMOKE_MUTATION_BOUNDARY=.*' "$script"
  grep -q 'require_smoke_mutation_boundary' "$script"
  grep -q 'run-owned-compose' "$script"
  grep -q 'login_play_look_steps' "$script"
  grep -q 'gameplay_item_container_equipment_steps' "$script"
  if [[ "$script" == *"game-session-service/websocket-login-look-smoke.sh" ]]; then
    grep -Fq -- 'venv_directory = repo_root / ".venv-smoke"' "$script" ||
      { echo "$script must use the canonical repository virtual environment" >&2; exit 1; }
    grep -Fq -- 'venv_python = venv_directory / "bin" / "python"' "$script" ||
      { echo "$script must install through the virtual environment interpreter" >&2; exit 1; }
    grep -Fq -- 'requirements_file = repo_root / "config" / "python" / "smoke-requirements.txt"' "$script" ||
      { echo "$script must resolve the canonical smoke requirements file" >&2; exit 1; }
    grep -Fq -- 'python3 -m venv --clear {shlex.quote(str(venv_directory))}' "$script" ||
      { echo "$script must create the canonical virtual environment in its install hint" >&2; exit 1; }
    grep -Fq -- 'shlex.quote(str(venv_python))' "$script" ||
      { echo "$script must quote the canonical virtual environment interpreter" >&2; exit 1; }
    grep -Fq -- '--disable-pip-version-check --require-hashes' "$script" ||
      { echo "$script must use the pinned hash-locked smoke requirements profile" >&2; exit 1; }
    grep -Fq -- 'shlex.quote(str(requirements_file))' "$script" ||
      { echo "$script must quote the absolute requirements file" >&2; exit 1; }
    grep -Fq 'f"-r {shlex.quote(str(requirements_file))}\n"' "$script" ||
      { echo "$script must keep prose punctuation outside the copyable requirements command" >&2; exit 1; }
    if grep -q 'shlex.quote(sys.executable)' "$script"; then
      echo "$script must not recommend installing into the ambient interpreter" >&2
      exit 1
    fi
  fi
  if grep -q 'COMPOSE_PROJECT_NAME:-.*=~' "$script"; then
    echo "inline COMPOSE_PROJECT_NAME validator remains in $script" >&2
    exit 1
  fi
  helper_source_line="$(grep -n 'run-owned-compose.sh' "$script" | head -1 | cut -d: -f1)"
  mutation_gate_line="$(grep -n '^[[:space:]]*require_smoke_mutation_boundary$' "$script" | head -1 | cut -d: -f1)"
  endpoint_guard_line="$(grep -n 'Mutation mode requires' "$script" | head -1 | cut -d: -f1)"
  service_guard_line="$(grep -n '^[[:space:]]*require_run_owned_compose_service' "$script" | head -1 | cut -d: -f1)"
  # shellcheck disable=SC2016 # Match the literal shell variable in the target script.
  python_line="$(grep -n '\$PYTHON.*<<' "$script" | head -1 | cut -d: -f1)"
  if [[ -z "$helper_source_line" || -z "$mutation_gate_line" || -z "$endpoint_guard_line" \
    || -z "$service_guard_line" || -z "$python_line" \
    || "$helper_source_line" -ge "$mutation_gate_line" \
    || "$mutation_gate_line" -ge "$endpoint_guard_line" \
    || "$endpoint_guard_line" -ge "$service_guard_line" \
    || "$service_guard_line" -ge "$python_line" ]]; then
    echo "executable shared mutation gate and transport consequences are not ordered before Python in $script" >&2
    exit 1
  fi
  if grep -q '^[[:space:]]*require_run_owned_compose_project$' "$script"; then
    echo "transport-local mutation guard still calls the shared project gate directly in $script" >&2
    exit 1
  fi
done

for script in \
  "$ROOT_DIR/dev-tools/verify-fresh-bootstrap.sh" \
  "$ROOT_DIR/dev-tools/verify-restart-state.sh" \
  "$ROOT_DIR/dev-tools/verify-smoke-images.sh"; do
  assert_command_rejects \
    "independent transport identities/state are not proven" \
    env SMOKE_MUTATION_EXTENSION=true bash "$script"
  grep -q 'independent transport identities/state are not proven' "$script"
  grep -q 'LOOK baseline proofs' "$script"
done

for script in "$ROOT_DIR/dev-tools/verify-fresh-bootstrap.sh" "$ROOT_DIR/dev-tools/verify-smoke-images.sh"; do
  grep -qE '(claim_run_owned_compose_project|require_run_owned_compose_project)' "$script"
  grep -q 'run-owned-compose.sh' "$script"
  mapfile -t down_lines < <(grep -nE '^[[:space:]]*docker compose .*down -v --remove-orphans([[:space:]]|$)' "$script" || true)
  if ((${#down_lines[@]} == 0)); then
    echo "no destructive compose teardown found in $script" >&2
    exit 1
  fi
  for down_entry in "${down_lines[@]}"; do
    down_line="${down_entry%%:*}"
    guard_line="$(grep -nE '^[[:space:]]*(claim_run_owned_compose_project|require_run_owned_compose_project)$' "$script" | awk -F: -v down="$down_line" '$1 < down {line=$1} END {print line}')"
    if [[ -z "$guard_line" || "$guard_line" -ge "$down_line" ]]; then
      echo "destructive compose teardown at line $down_line is not guarded in $script" >&2
      exit 1
    fi
  done
done

restart_script="$ROOT_DIR/dev-tools/verify-restart-state.sh"
grep -q 'run-owned-compose.sh' "$restart_script"
restart_helper_source_line="$(grep -n 'run-owned-compose.sh' "$restart_script" | head -1 | cut -d: -f1)"
restart_guard_line="$(grep -n '^[[:space:]]*require_run_owned_compose_project$' "$restart_script" | head -1 | cut -d: -f1)"
restart_compose_line="$(grep -n '^[[:space:]]*docker compose' "$restart_script" | head -1 | cut -d: -f1)"
if [[ -z "$restart_helper_source_line" || -z "$restart_guard_line" || -z "$restart_compose_line" \
  || "$restart_helper_source_line" -ge "$restart_guard_line" \
  || "$restart_guard_line" -ge "$restart_compose_line" ]]; then
  echo "restart-state must source and call the run-owned helper before Compose" >&2
  exit 1
fi

assert_command_rejects \
  "FIREMUD_SMOKE_RUN_ID must match" \
  env GITHUB_ACTIONS=false COMPOSE_PROJECT_NAME=firemud-smoke-shared bash "$RUN_OWNED_COMPOSE_HELPER"
assert_command_rejects \
  "FIREMUD_SMOKE_RUN_ID must match" \
  env GITHUB_ACTIONS=false FIREMUD_SMOKE_RUN_ID=Invalid_ID \
  COMPOSE_PROJECT_NAME=firemud-smoke-Invalid_ID bash "$RUN_OWNED_COMPOSE_HELPER"
assert_command_rejects \
  "COMPOSE_PROJECT_NAME must exactly match firemud-smoke-contract-local" \
  env GITHUB_ACTIONS=false FIREMUD_SMOKE_RUN_ID=contract-local \
  COMPOSE_PROJECT_NAME=firemud-smoke-other bash "$RUN_OWNED_COMPOSE_HELPER"
assert_command_rejects \
  "GitHub Actions mode requires nonempty GITHUB_RUN_ID, GITHUB_RUN_ATTEMPT, and GITHUB_JOB" \
  env GITHUB_ACTIONS=true GITHUB_RUN_ID=123 GITHUB_RUN_ATTEMPT= GITHUB_JOB=smoke \
  COMPOSE_PROJECT_NAME=smoke-full-123-2 bash "$RUN_OWNED_COMPOSE_HELPER"
assert_command_rejects \
  "COMPOSE_PROJECT_NAME must exactly match smoke-full-123-2" \
  env GITHUB_ACTIONS=true GITHUB_RUN_ID=123 GITHUB_RUN_ATTEMPT=2 GITHUB_JOB=smoke \
  COMPOSE_PROJECT_NAME=smoke-full-123-1 bash "$RUN_OWNED_COMPOSE_HELPER"


TEST_ROOT="$(mktemp -d)"
trap 'rm -rf "$TEST_ROOT"' EXIT
FAKE_DOCKER_BIN="$TEST_ROOT/bin"
FAKE_DOCKER_STATE="$TEST_ROOT/state"
OWNERSHIP_TEST_DIR="$TEST_ROOT/ownership"
mkdir -p "$FAKE_DOCKER_BIN" "$FAKE_DOCKER_STATE" "$OWNERSHIP_TEST_DIR"
chmod 700 "$OWNERSHIP_TEST_DIR"
cat >"$FAKE_DOCKER_BIN/docker" <<'FAKE_DOCKER'
#!/usr/bin/env bash
set -euo pipefail

state_dir=$FAKE_DOCKER_STATE
command_name=$1
shift
field_matches() {
  local expected=$1
  local actual=$2
  [[ -z "$expected" || "$expected" == "$actual" ]]
}

case "$command_name" in
  ps)
    project=""
    service=""
    status=""
    while (($# > 0)); do
      case "$1" in
        -a) shift ;;
        --filter)
          filter=$2
          case "$filter" in
            label=com.docker.compose.project=*) project=${filter##*=} ;;
            label=com.docker.compose.service=*) service=${filter##*=} ;;
            status=*) status=${filter##*=} ;;
          esac
          shift 2
          ;;
        --format) shift 2 ;;
        *) shift ;;
      esac
    done
    [[ -f "$state_dir/containers" ]] || exit 0
    while IFS='|' read -r id row_project row_service row_status row_ports; do
      field_matches "$project" "$row_project" || continue
      field_matches "$service" "$row_service" || continue
      field_matches "$status" "$row_status" || continue
      printf '%s\n' "$id"
    done <"$state_dir/containers"
    ;;
  network)
    [[ "$1" == ls ]] || exit 99
    shift
    project=""
    while (($# > 0)); do
      if [[ "$1" == --filter ]]; then
        filter=$2
        [[ "$filter" == label=com.docker.compose.project=* ]] && project=${filter##*=}
        shift 2
      else
        shift
      fi
    done
    [[ -f "$state_dir/networks" ]] || exit 0
    while IFS='|' read -r id row_project; do
      field_matches "$project" "$row_project" || continue
      printf '%s\n' "$id"
    done <"$state_dir/networks"
    ;;
  volume)
    [[ "$1" == ls ]] || exit 99
    shift
    project=""
    while (($# > 0)); do
      if [[ "$1" == --filter ]]; then
        filter=$2
        [[ "$filter" == label=com.docker.compose.project=* ]] && project=${filter##*=}
        shift 2
      else
        shift
      fi
    done
    [[ -f "$state_dir/volumes" ]] || exit 0
    while IFS='|' read -r name row_project; do
      field_matches "$project" "$row_project" || continue
      printf '%s\n' "$name"
    done <"$state_dir/volumes"
    ;;
  port)
    id=$1
    requested=$2
    [[ -f "$state_dir/containers" ]] || exit 1
    while IFS='|' read -r row_id _project _service _status row_ports; do
      [[ "$row_id" == "$id" ]] || continue
      IFS=',' read -ra bindings <<<"$row_ports"
      for binding in "${bindings[@]}"; do
        binding_port=${binding%%=*}
        binding_value=${binding#*=}
        [[ "$binding_port" == "$requested" ]] && printf '%s\n' "$binding_value"
      done
      exit 0
    done <"$state_dir/containers"
    exit 1
    ;;
  *)
    echo "unsupported fake docker command: $command_name" >&2
    exit 99
    ;;
esac
FAKE_DOCKER
chmod 700 "$FAKE_DOCKER_BIN/docker"
export PATH="$FAKE_DOCKER_BIN:$PATH"
export FAKE_DOCKER_STATE

OWNERSHIP_TOKEN=0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef
export GITHUB_ACTIONS=false FIREMUD_SMOKE_RUN_ID=contract-local
export COMPOSE_PROJECT_NAME=firemud-smoke-contract-local
export FIREMUD_SMOKE_OWNERSHIP_TOKEN="$OWNERSHIP_TOKEN"
export FIREMUD_SMOKE_OWNERSHIP_DIR="$OWNERSHIP_TEST_DIR" FIREMUD_SMOKE_TEST_MODE=1

run_owned_helper() {
  local action="$1"
  shift
  bash -c 'source "$1"; shift; "$@"' _ "$RUN_OWNED_COMPOSE_HELPER" "$action" "$@"
}

normalized_mutation_false="$(
  # shellcheck disable=SC2016 # Match the literal shell variables in the child shell.
  env SMOKE_MUTATION_EXTENSION=0 SMOKE_MUTATION_BOUNDARY= \
    bash -c 'source "$1"; require_smoke_mutation_boundary; printf "%s|%s\n" "$SMOKE_MUTATION_EXTENSION" "$SMOKE_MUTATION_BOUNDARY"' \
    _ "$RUN_OWNED_COMPOSE_HELPER"
)"
[[ "$normalized_mutation_false" == "false|" ]]
normalized_mutation_true="$(
  # shellcheck disable=SC2016 # Match the literal shell variables in the child shell.
  env SMOKE_MUTATION_EXTENSION=1 SMOKE_MUTATION_BOUNDARY=restricted-synthetic \
    bash -c 'source "$1"; require_smoke_mutation_boundary || :; printf "%s|%s\n" "$SMOKE_MUTATION_EXTENSION" "$SMOKE_MUTATION_BOUNDARY"' \
    _ "$RUN_OWNED_COMPOSE_HELPER" 2>/dev/null
)"
[[ "$normalized_mutation_true" == "true|restricted-synthetic" ]]
# shellcheck disable=SC2016
RUN_OWNED_CHILD_BASH='source "$1"; shift; "$@"'
# shellcheck disable=SC2016
RUN_OWNED_NO_PIPEFAIL_CHILD_BASH='set +o pipefail; source "$1"; shift; "$@"'
# shellcheck disable=SC2016
RUN_OWNED_UNSET_TOKEN_CHILD_BASH='unset FIREMUD_SMOKE_OWNERSHIP_TOKEN; source "$1"; shift; "$@"'
# shellcheck disable=SC2016
RUN_OWNED_HOLD_LOCK_CHILD_BASH='source "$1"; claim_run_owned_compose_project; : >"$2"; sleep 10'
# shellcheck disable=SC2016
RUN_OWNED_UMASK_CHILD_BASH='source "$1"; shift; umask 027; "$@"; [[ "$(umask)" == 0027 ]]'

NO_FLOCK_BIN="$TEST_ROOT/no-flock-bin"
mkdir -p "$NO_FLOCK_BIN"
BASH_EXECUTABLE="$(command -v bash)"
for dependency in uname stat readlink id mktemp awk sha256sum; do
  ln -s "$(command -v "$dependency")" "$NO_FLOCK_BIN/$dependency"
done
assert_command_rejects \
  "flock dependency" \
  env PATH="$NO_FLOCK_BIN" \
  "$BASH_EXECUTABLE" -c "$RUN_OWNED_CHILD_BASH" _ \
  "$RUN_OWNED_COMPOSE_HELPER" claim_run_owned_compose_project

FAILING_SHA256SUM_BIN="$TEST_ROOT/failing-sha256sum-bin"
FAILING_OWNERSHIP_DIR="$TEST_ROOT/failing-ownership"
mkdir -p "$FAILING_SHA256SUM_BIN"
for dependency in uname stat readlink id mktemp awk flock; do
  ln -s "$(command -v "$dependency")" "$FAILING_SHA256SUM_BIN/$dependency"
done
cat >"$FAILING_SHA256SUM_BIN/sha256sum" <<'FAILING_SHA256SUM'
#!/usr/bin/env bash
exit 42
FAILING_SHA256SUM
chmod 700 "$FAILING_SHA256SUM_BIN/sha256sum"
assert_command_rejects \
  "sha256sum command failed" \
  env PATH="$FAILING_SHA256SUM_BIN:$PATH" \
  FIREMUD_SMOKE_OWNERSHIP_DIR="$FAILING_OWNERSHIP_DIR" \
  "$BASH_EXECUTABLE" -c "$RUN_OWNED_NO_PIPEFAIL_CHILD_BASH" _ \
  "$RUN_OWNED_COMPOSE_HELPER" claim_run_owned_compose_project
[[ -z "$(find "$FAILING_OWNERSHIP_DIR" -maxdepth 1 -type f -name '*.marker' -print -quit)" ]]

bash -c "$RUN_OWNED_UMASK_CHILD_BASH" _ \
  "$RUN_OWNED_COMPOSE_HELPER" claim_run_owned_compose_project

run_owned_helper claim_run_owned_compose_project
marker_path="$(find "$OWNERSHIP_TEST_DIR" -maxdepth 1 -type f -name '*.marker' -print -quit)"
[[ -n "$marker_path" ]]
expected_digest="$(printf '%s' "$OWNERSHIP_TOKEN" | sha256sum | awk '{print $1}')"
[[ "$(cat "$marker_path")" == "$expected_digest" ]]
[[ "$(stat -Lc '%a' "$marker_path")" == 600 ]]

lock_ready="$TEST_ROOT/lock-ready"
bash -c "$RUN_OWNED_HOLD_LOCK_CHILD_BASH" _ \
  "$RUN_OWNED_COMPOSE_HELPER" "$lock_ready" &
lock_holder_pid=$!
for _ in $(seq 1 200); do
  [[ -e "$lock_ready" ]] && break
  sleep 0.02
done
[[ -e "$lock_ready" ]]
assert_command_rejects \
  "another smoke invocation holds the project lock" \
  run_owned_helper claim_run_owned_compose_project
kill "$lock_holder_pid"
wait "$lock_holder_pid" 2>/dev/null || true

assert_command_rejects \
  "FIREMUD_SMOKE_OWNERSHIP_TOKEN" \
  bash -c "$RUN_OWNED_UNSET_TOKEN_CHILD_BASH" _ \
  "$RUN_OWNED_COMPOSE_HELPER" claim_run_owned_compose_project
for invalid_token in abc "${OWNERSHIP_TOKEN^^}"; do
  assert_command_rejects \
    "FIREMUD_SMOKE_OWNERSHIP_TOKEN" \
    env FIREMUD_SMOKE_OWNERSHIP_TOKEN="$invalid_token" \
    bash -c "$RUN_OWNED_CHILD_BASH" _ \
    "$RUN_OWNED_COMPOSE_HELPER" claim_run_owned_compose_project
done
assert_command_rejects \
  "ownership marker" \
  env FIREMUD_SMOKE_OWNERSHIP_TOKEN=ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff \
  bash -c "$RUN_OWNED_CHILD_BASH" _ \
  "$RUN_OWNED_COMPOSE_HELPER" claim_run_owned_compose_project

rm -f "$marker_path"
ln -s "$TEST_ROOT/missing-marker-target" "$marker_path"
assert_command_rejects \
  "ownership marker" \
  run_owned_helper require_run_owned_compose_project
rm -f "$marker_path"
run_owned_helper claim_run_owned_compose_project

rm -f "$marker_path"
assert_command_rejects \
  "ownership marker" \
  run_owned_helper require_run_owned_compose_project
run_owned_helper claim_run_owned_compose_project

rm -f "$FAKE_DOCKER_STATE/containers" "$FAKE_DOCKER_STATE/networks" \
  "$FAKE_DOCKER_STATE/volumes"
assert_command_rejects \
  "no standard Compose-labelled project resource" \
  run_owned_helper require_run_owned_compose_project

rm -f "$OWNERSHIP_TEST_DIR"/*.marker
export GITHUB_ACTIONS=true GITHUB_RUN_ID=123 GITHUB_RUN_ATTEMPT=2 GITHUB_JOB=smoke
export COMPOSE_PROJECT_NAME=smoke-full-123-2
run_owned_helper claim_run_owned_compose_project
assert_command_rejects \
  "ownership marker" \
  env GITHUB_JOB=other-smoke \
  bash -c "$RUN_OWNED_CHILD_BASH" _ \
  "$RUN_OWNED_COMPOSE_HELPER" claim_run_owned_compose_project
rm -f "$OWNERSHIP_TEST_DIR"/*.marker
export GITHUB_ACTIONS=false FIREMUD_SMOKE_RUN_ID=contract-local
export COMPOSE_PROJECT_NAME=firemud-smoke-contract-local
run_owned_helper claim_run_owned_compose_project

rm -f "$marker_path"
printf 'collision|%s|other|exited|\n' "$COMPOSE_PROJECT_NAME" >"$FAKE_DOCKER_STATE/containers"
assert_command_rejects \
  "standard Compose-labelled resources already exist without an ownership marker" \
  run_owned_helper claim_run_owned_compose_project
rm -f "$FAKE_DOCKER_STATE/containers"
run_owned_helper claim_run_owned_compose_project

printf 'game-session|%s|game-session-service|running|8080/tcp=0.0.0.0:8086\n' "$COMPOSE_PROJECT_NAME" >"$FAKE_DOCKER_STATE/containers"
printf 'network|%s\n' "$COMPOSE_PROJECT_NAME" >"$FAKE_DOCKER_STATE/networks"
printf 'volume|%s\n' "$COMPOSE_PROJECT_NAME" >"$FAKE_DOCKER_STATE/volumes"
run_owned_helper require_run_owned_compose_project
run_owned_helper require_run_owned_compose_service game-session-service 8080 8086

rm -f "$FAKE_DOCKER_STATE/containers"
assert_command_rejects \
  "expected exactly one running Compose service game-session-service" \
  run_owned_helper require_run_owned_compose_service game-session-service 8080 8086
printf 'game-session|%s|game-session-service|exited|8080/tcp=0.0.0.0:8086\n' "$COMPOSE_PROJECT_NAME" >"$FAKE_DOCKER_STATE/containers"
assert_command_rejects \
  "expected exactly one running Compose service game-session-service" \
  run_owned_helper require_run_owned_compose_service game-session-service 8080 8086
printf 'game-session|%s|game-session-service|running|8080/tcp=0.0.0.0:8085\n' "$COMPOSE_PROJECT_NAME" >"$FAKE_DOCKER_STATE/containers"
assert_command_rejects \
  "does not publish 8080/tcp on host port 8086" \
  run_owned_helper require_run_owned_compose_service game-session-service 8080 8086
printf 'game-session|%s|game-session-service|running|8080/tcp=0.0.0.0:8086\n' "$COMPOSE_PROJECT_NAME" >"$FAKE_DOCKER_STATE/containers"
run_owned_helper require_run_owned_compose_service game-session-service 8080 8086

printf 'tcp-proxy|%s|tcp-proxy-service|running|2323/tcp=0.0.0.0:2323\n' "$COMPOSE_PROJECT_NAME" >>"$FAKE_DOCKER_STATE/containers"
run_owned_helper require_run_owned_compose_service tcp-proxy-service 2323 2323

assert_command_rejects \
  "SMOKE_GAME_SESSION_WS_URL" \
  env SMOKE_MUTATION_EXTENSION=true \
  SMOKE_MUTATION_BOUNDARY=run-owned-compose \
  SMOKE_GAME_SESSION_WS_URL=ws://localhost:8085/ws \
  bash "$ROOT_DIR/services/game-session-service/websocket-login-look-smoke.sh"
assert_command_rejects \
  "canonical Telnet endpoint" \
  env SMOKE_MUTATION_EXTENSION=true \
  SMOKE_MUTATION_BOUNDARY=run-owned-compose \
  SMOKE_TELNET_HOST=remotehost \
  bash "$ROOT_DIR/services/tcp-proxy-service/telnet-login-look-smoke.sh"
assert_command_rejects \
  "canonical Telnet endpoint" \
  env SMOKE_MUTATION_EXTENSION=true \
  SMOKE_MUTATION_BOUNDARY=run-owned-compose \
  TCP_PROXY_PORT=2324 \
  bash "$ROOT_DIR/services/tcp-proxy-service/telnet-login-look-smoke.sh"

assert_command_rejects \
  "cannot release ownership while standard Compose-labelled project resources remain" \
  run_owned_helper release_run_owned_compose_project
[[ -e "$marker_path" ]]

printf 'game-session-2|%s|game-session-service|running|8080/tcp=0.0.0.0:8086\n' "$COMPOSE_PROJECT_NAME" >>"$FAKE_DOCKER_STATE/containers"
assert_command_rejects \
  "expected exactly one running Compose service game-session-service" \
  run_owned_helper require_run_owned_compose_service game-session-service 8080 8086
printf 'game-session|%s|game-session-service|running|8080/tcp=0.0.0.0:8086\n' "$COMPOSE_PROJECT_NAME" >"$FAKE_DOCKER_STATE/containers"

rm -f "$FAKE_DOCKER_STATE/networks" "$FAKE_DOCKER_STATE/volumes"
rm -f "$FAKE_DOCKER_STATE/containers"
run_owned_helper release_run_owned_compose_project
[[ ! -e "$marker_path" ]]

echo "smoke script boundary contract checks passed"
