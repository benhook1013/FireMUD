import ipaddress
import json
import os
import socket
import ssl
import subprocess
import time
import urllib.error
import urllib.request
from urllib.parse import quote


class ProbeOperationalFailure(RuntimeError):
    """An expected transport, upstream, or protocol failure from a live probe."""


class TransientUpstreamSmokeFailure(ProbeOperationalFailure):
    """A startup-time upstream error that a bounded smoke retry may retry."""


RETRYABLE_STARTUP_COMMAND_LABELS = frozenset({"WORLDS", "LOGIN"})
INVALID_COMMAND_LINE_ERROR = "commands must not contain embedded CR or LF"
PLAINTEXT_TELNET_HOST_ERROR = (
    "tls_enabled=False requires a localhost-equivalent Telnet host"
)


def is_localhost_equivalent(host):
    if not isinstance(host, str):
        return False
    normalized = host.strip().casefold()
    if normalized in {"localhost", "localhost."}:
        return True
    if normalized.startswith("[") and normalized.endswith("]"):
        normalized = normalized[1:-1]
    try:
        address = ipaddress.ip_address(normalized)
    except ValueError:
        return False
    if address.is_loopback:
        return True
    mapped = getattr(address, "ipv4_mapped", None)
    return mapped is not None and mapped.is_loopback


def compose_postgres_container_name():
    compose_project_name = os.environ.get("COMPOSE_PROJECT_NAME", "docker")
    return f"{compose_project_name}-postgres-1"


def verify_smoke_account(account_api_base, username, password, timeout_seconds):
    payload = json.dumps(
        {
            "username": username,
            "password": password,
        }
    ).encode("utf-8")
    request = urllib.request.Request(
        f"{account_api_base}/auth/login",
        data=payload,
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    for attempt in range(1, 4):
        try:
            with urllib.request.urlopen(request, timeout=timeout_seconds) as response:
                response.read()
                print(f"Smoke account validation returned status {response.status}")
                return
        except urllib.error.HTTPError as exc:
            exc.read()
            if exc.code >= 500:
                if attempt < 3:
                    time.sleep(1)
                    continue
                raise RuntimeError(
                    f"Smoke account validation failed with status {exc.code}"
                ) from exc
            raise RuntimeError(
                f"Smoke account validation failed with status {exc.code}"
            ) from exc
        except OSError as exc:
            if attempt < 3:
                time.sleep(1)
                continue
            raise RuntimeError(
                "Smoke account validation failed due to a transport error"
            ) from exc


def http_request_json(url, timeout_seconds, method="GET", payload=None, headers=None):
    return http_request_json_with_headers(
        url,
        timeout_seconds,
        method=method,
        payload=payload,
        headers=headers,
    )[0]


def http_request_json_with_headers(
    url, timeout_seconds, method="GET", payload=None, headers=None
):
    request_headers = {"Content-Type": "application/json"}
    if headers:
        request_headers.update(headers)
    body = None
    if payload is not None:
        body = json.dumps(payload).encode("utf-8")
    request = urllib.request.Request(
        url,
        data=body,
        headers=request_headers,
        method=method,
    )
    try:
        with urllib.request.urlopen(request, timeout=timeout_seconds) as response:
            return json.loads(response.read().decode("utf-8")), response.headers
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise ProbeOperationalFailure(
            f"Request {method} {url} returned invalid JSON: {exc}"
        ) from exc
    except urllib.error.HTTPError as exc:
        body = exc.read().decode("utf-8", errors="ignore").strip()
        raise ProbeOperationalFailure(
            f"Request {method} {url} failed with status {exc.code}: {body or '<empty>'}"
        ) from exc
    except urllib.error.URLError as exc:
        raise ProbeOperationalFailure(f"Request {method} {url} failed: {exc}") from exc


def quote_path(value):
    return quote(value, safe="")


def wait_for_account_schema(startup_wait_seconds, timeout_seconds):
    deadline = time.time() + startup_wait_seconds
    query = "select to_regclass('account_service.accounts');"
    postgres_container = compose_postgres_container_name()
    while time.time() < deadline:
        try:
            table_name = subprocess.check_output(
                [
                    "docker",
                    "exec",
                    postgres_container,
                    "psql",
                    "-U",
                    "firemud",
                    "-d",
                    "firemud",
                    "-tAc",
                    query,
                ],
                text=True,
                timeout=timeout_seconds,
            ).strip()
            if table_name == "account_service.accounts":
                print("Confirmed account schema is ready.")
                return
        except (OSError, subprocess.CalledProcessError, subprocess.TimeoutExpired):
            pass
        time.sleep(2)
    raise RuntimeError("Account schema readiness did not converge before smoke execution")


def http_readiness_up(readiness_url, timeout_seconds):
    try:
        with urllib.request.urlopen(readiness_url, timeout=timeout_seconds) as response:
            body = response.read().decode("utf-8", errors="ignore")
            return response.status < 500 and "\"status\":\"UP\"" in body.replace(" ", "")
    except (urllib.error.URLError, OSError):
        return False


def wait_for_http_readiness(name, base_url, startup_wait_seconds, timeout_seconds):
    deadline = time.time() + startup_wait_seconds
    readiness_url = f"{base_url}/actuator/health/readiness"
    while time.time() < deadline:
        if http_readiness_up(readiness_url, timeout_seconds):
            print(f"Confirmed {name} readiness via {readiness_url}.")
            return
        time.sleep(2)
    raise RuntimeError(f"{name} readiness did not report UP at {readiness_url}")


def run_command_plan(steps, executor):
    for step in steps:
        if len(step) == 3:
            line, expected_substrings, label = step
            timeout = None
        elif len(step) == 4:
            line, expected_substrings, label, timeout = step
        else:
            raise ValueError(f"Unsupported command-plan step: {step!r}")
        executor(line, expected_substrings, label, timeout)


def run_transport_session(
    open_session,
    execute_session,
    session_label,
    retry_window_seconds=0,
    retry_interval_seconds=2,
    retriable_exceptions=(OSError,),
):
    deadline = time.time() + retry_window_seconds if retry_window_seconds > 0 else None
    while True:
        try:
            session = open_session()
        except retriable_exceptions as exc:
            if deadline is None:
                raise ProbeOperationalFailure(
                    f"Failed to open {session_label}: {exc}"
                ) from exc
            remaining = deadline - time.time()
            if remaining <= 0:
                raise ProbeOperationalFailure(
                    f"Failed to open {session_label}: {exc}"
                ) from exc
            time.sleep(min(retry_interval_seconds, remaining))
            if time.time() >= deadline:
                raise ProbeOperationalFailure(
                    f"Failed to open {session_label}: {exc}"
                ) from exc
            continue

        try:
            result = execute_session(session)
        except TransientUpstreamSmokeFailure:
            try:
                session.close()
            except Exception as exc:
                raise ProbeOperationalFailure(
                    f"Failed to close {session_label}: {exc}"
                ) from exc
            if deadline is None:
                raise
            remaining = deadline - time.time()
            if remaining <= 0:
                raise
            time.sleep(min(retry_interval_seconds, remaining))
            if time.time() >= deadline:
                raise
            continue
        except retriable_exceptions as exc:
            try:
                session.close()
            except Exception as close_exc:
                raise ProbeOperationalFailure(
                    f"Failed during {session_label}: {exc}; "
                    f"close failed: {close_exc}"
                ) from exc
            raise ProbeOperationalFailure(
                f"Failed during {session_label}: {exc}"
            ) from exc
        except Exception:
            try:
                session.close()
            except Exception:
                pass
            raise
        else:
            try:
                session.close()
            except Exception as exc:
                raise ProbeOperationalFailure(
                    f"Failed to close {session_label}: {exc}"
                ) from exc
            return result


def login_play_look_steps(
    username,
    password,
    world,
    worlds_expect,
    login_expect,
    play_expect,
    look_expect,
    look_timeout=None,
    realm=None,
    character=None,
):
    play_parts = ["PLAY", world]
    if realm:
        play_parts.append(realm)
    if character:
        play_parts.append(character)
    steps = [
        ("WORLDS", [worlds_expect], "WORLDS"),
        (f"LOGIN {username} {password}", [login_expect], "LOGIN"),
        (" ".join(play_parts), [play_expect], "PLAY"),
        ("LOOK", [look_expect], "LOOK"),
    ]
    if look_timeout is None:
        return steps
    return [
        (line, expected, label, look_timeout) if label == "LOOK" else (line, expected, label)
        for (line, expected, label) in steps
    ]


def redact_login_credential(response, command):
    if not isinstance(response, str) or not isinstance(command, str):
        return response
    command_parts = command.strip().split(maxsplit=2)
    if len(command_parts) != 3 or command_parts[0].casefold() not in {"login", "logon"}:
        return response

    credential = command_parts[2]
    normalized_credential = " ".join(credential.split())
    credential_variants = sorted(
        {value for value in (credential, normalized_credential) if value},
        key=len,
        reverse=True,
    )
    redacted = response
    for value in credential_variants:
        redacted = redacted.replace(value, "[REDACTED]")
    return redacted


def _normalize_command_line(command):
    if not isinstance(command, str):
        raise TypeError("command must be a string")
    normalized = command.rstrip("\r\n")
    if "\r" in normalized or "\n" in normalized:
        raise ValueError(INVALID_COMMAND_LINE_ERROR)
    return normalized


def redact_login_command(command):
    if not isinstance(command, str):
        return command
    command_parts = command.strip().split(maxsplit=2)
    if len(command_parts) != 3 or command_parts[0].casefold() not in {"login", "logon"}:
        return command
    return f"{command_parts[0]} {command_parts[1]} [REDACTED]"


def unexpected_explicit_failure_line(
    response, explicit_failure_prefixes, expected_substrings
):
    expected_failures = [
        substring
        for substring in expected_substrings
        if any(
            substring.startswith(prefix) for prefix in explicit_failure_prefixes
        )
    ]
    accepted_expected_failures = set()
    response_ends_with_line_break = response.endswith(("\n", "\r"))
    response_lines = response.splitlines()
    for index, line in enumerate(response_lines):
        stripped = line.strip()
        if not any(
            stripped.startswith(prefix) for prefix in explicit_failure_prefixes
        ):
            continue
        matching_expected_failure = next(
            (
                expected_index
                for expected_index, expected in enumerate(expected_failures)
                if expected_index not in accepted_expected_failures
                and stripped.startswith(expected)
            ),
            None,
        )
        if matching_expected_failure is not None:
            accepted_expected_failures.add(matching_expected_failure)
            continue
        if (
            index == len(response_lines) - 1
            and not response_ends_with_line_break
            and any(expected.startswith(stripped) for expected in expected_failures)
        ):
            continue
        return stripped
    return None


def raise_for_explicit_failure(
    response,
    explicit_failure_prefixes,
    expected_substrings,
    retry_upstream_failure,
    sanitize_response,
):
    failure_line = unexpected_explicit_failure_line(
        response, explicit_failure_prefixes, expected_substrings
    )
    if failure_line is None:
        return
    diagnostic_response = (
        sanitize_response(response) if sanitize_response else response
    ).strip()
    if retry_upstream_failure and failure_line.startswith(
        ("ERROR UPSTREAM_FAILURE", "ERROR UNAVAILABLE")
    ):
        raise TransientUpstreamSmokeFailure(
            f"Command failed explicitly: {diagnostic_response}"
        )
    raise ProbeOperationalFailure(f"Command failed explicitly: {diagnostic_response}")


def wait_for_incremental_response(
    next_chunk,
    responses,
    start_index,
    expected_substrings,
    timeout,
    combine_responses,
    drain_remaining=None,
    explicit_failure_prefixes=("ERROR ", "DISCONNECT "),
    idle_sleep_seconds=0.05,
    retry_upstream_failure=False,
    sanitize_response=None,
    deadline=None,
):
    if deadline is None:
        deadline = time.time() + timeout
    response = combine_responses(responses[start_index:])
    while time.time() < deadline:
        chunk = next_chunk()
        if chunk:
            responses.append(chunk)
            response = combine_responses(responses[start_index:])
            raise_for_explicit_failure(
                response,
                explicit_failure_prefixes,
                expected_substrings,
                retry_upstream_failure,
                sanitize_response,
            )
            if all(substring in response for substring in expected_substrings):
                if drain_remaining is not None:
                    trailing = drain_remaining()
                    if trailing:
                        responses.append(trailing)
                    drained_response = combine_responses(responses[start_index:])
                    raise_for_explicit_failure(
                        drained_response,
                        explicit_failure_prefixes,
                        expected_substrings,
                        retry_upstream_failure,
                        sanitize_response,
                    )
                    if trailing:
                        response = drained_response
                return response
        else:
            time.sleep(min(idle_sleep_seconds, max(0, deadline - time.time())))
    diagnostic_response = sanitize_response(response) if sanitize_response else response
    raise ProbeOperationalFailure(
        f"Expected response containing {expected_substrings}, got '{diagnostic_response}'"
    )


def gameplay_item_container_equipment_steps(
    username,
    password,
    worlds_expect,
    login_expect,
    play_expect,
    look_expect,
    world="demo",
    look_timeout=None,
):
    steps = login_play_look_steps(
        username,
        password,
        world,
        worlds_expect,
        login_expect,
        play_expect,
        look_expect,
    ) + [
        ("INV HERE", ["Room Inventory:", "Torch", "Backpack"], "INV HERE"),
        ("GET Torch", ["You pick up Torch."], "GET"),
        ("INVENTORY", ["Inventory:", "- Torch"], "INVENTORY after GET"),
        (
            "CONTAINER Backpack",
            ["Container: Backpack [backpack#1]", "Ration"],
            "CONTAINER",
        ),
        (
            "PUT Torch INTO Backpack",
            ["You put Torch into Backpack.", "Container: Backpack [backpack#1]", "Torch"],
            "PUT",
        ),
        (
            "TAKE Torch FROM Backpack",
            ["You take Torch from Backpack.", "Container: Backpack [backpack#1]", "Ration"],
            "TAKE",
        ),
        ("DROP Torch", ["You drop Torch."], "DROP"),
        ("INV HERE", ["Room Inventory:", "Torch", "Backpack"], "INV HERE after DROP"),
        ("EQUIPMENT", ["You have nothing equipped."], "EQUIPMENT empty"),
        ("WEAR Leather Cap", ["You wear Leather Cap."], "WEAR"),
        ("EQUIPMENT", ["Equipment:", "HEAD", "Leather Cap"], "EQUIPMENT worn"),
        ("REMOVE HEAD", ["You remove Leather Cap."], "REMOVE"),
        ("EQUIPMENT", ["You have nothing equipped."], "EQUIPMENT empty again"),
        (
            "WEAR Iron Boots",
            ["ERROR SLOT_INCOMPATIBLE", "Iron Boots cannot be worn by this body layout"],
            "WEAR incompatible",
        ),
    ]
    if look_timeout is None:
        return steps
    return [
        (line, expected, label, look_timeout)
        if label in {"LOOK", "REMOVE", "WEAR incompatible"}
        else (line, expected, label)
        for (line, expected, label) in steps
    ]


def recv_until_socket(sock, expected_substring, timeout=None, *, deadline=None):
    if deadline is None:
        if timeout is None:
            raise TypeError("recv_until_socket requires timeout or deadline")
        deadline = time.time() + timeout
    chunks = []
    while time.time() < deadline:
        try:
            remaining = deadline - time.time()
            if remaining <= 0:
                break
            sock.settimeout(remaining)
            data = sock.recv(4096)
        except (TimeoutError, BlockingIOError):
            break
        if not data:
            break
        chunks.append(data.decode("iso-8859-1", errors="ignore"))
        joined = "".join(chunks)
        if expected_substring in joined:
            return joined
    return "".join(chunks)


def drain_available_socket(sock, quiet_timeout=0.25, deadline=None):
    quiet_deadline = time.time() + quiet_timeout
    if deadline is not None:
        quiet_deadline = min(quiet_deadline, deadline)
    chunks = []
    while time.time() < quiet_deadline:
        try:
            remaining = quiet_deadline - time.time()
            if remaining <= 0:
                break
            sock.settimeout(remaining)
            data = sock.recv(4096)
        except (TimeoutError, BlockingIOError):
            break
        if not data:
            break
        chunks.append(data.decode("iso-8859-1", errors="ignore"))
    return "".join(chunks)


def send_telnet_command_and_expect(
    sock,
    responses,
    line,
    expected_substrings,
    label,
    timeout_seconds,
    drain_timeout=0.25,
    step_results=None,
):
    line = _normalize_command_line(line)
    start_index = len(responses)
    started_at = time.time()
    command_deadline = started_at + timeout_seconds
    command_bytes = line.encode("iso-8859-1").replace(b"\xff", b"\xff\xff")
    remaining_timeout = command_deadline - time.time()
    if remaining_timeout <= 0:
        raise ProbeOperationalFailure(f"Timed out before sending {label}")
    sock.settimeout(remaining_timeout)
    sock.sendall(command_bytes + b"\r\n")
    remaining_timeout = max(0, command_deadline - time.time())
    response = wait_for_incremental_response(
        lambda: recv_until_socket(
            sock, "", deadline=command_deadline
        ),
        responses,
        start_index,
        expected_substrings,
        remaining_timeout,
        "".join,
        lambda: drain_available_socket(
            sock,
            min(drain_timeout, max(0, command_deadline - time.time())),
            deadline=command_deadline,
        ),
        retry_upstream_failure=label in RETRYABLE_STARTUP_COMMAND_LABELS,
        sanitize_response=lambda value: redact_login_credential(value, line),
        deadline=command_deadline,
    )
    diagnostic_response = redact_login_credential(response, line)
    print(f"=== {label} response ===")
    print(diagnostic_response.strip() or "<no data>")
    if step_results is not None:
        step_results.append(
            {
                "label": label,
                "command": redact_login_command(line),
                "latencyMs": round((time.time() - started_at) * 1000, 3),
                "response": diagnostic_response.strip(),
            }
        )
    return response


def run_telnet_command_plan(
    sock,
    steps,
    timeout_seconds,
    play_drain_timeout=1.0,
    default_drain_timeout=0.25,
    step_results=None,
):
    responses = []
    run_command_plan(
        steps,
        lambda line, expected_substrings, label, timeout: send_telnet_command_and_expect(
            sock,
            responses,
            line,
            expected_substrings,
            label,
            timeout_seconds if timeout is None else timeout,
            play_drain_timeout if label == "PLAY" else default_drain_timeout,
            step_results,
        ),
    )
    return responses


def open_telnet_socket(
    host,
    port,
    timeout_seconds,
    *,
    tls_enabled,
    tls_server_hostname=None,
    tls_ca_file=None,
):
    if not isinstance(tls_enabled, bool):
        raise TypeError("tls_enabled must be explicitly set to true or false")
    if tls_enabled:
        if not isinstance(tls_server_hostname, str) or not tls_server_hostname.strip():
            raise ValueError("TLS Telnet connections require an explicit server hostname")
    elif tls_server_hostname is not None or tls_ca_file is not None:
        raise ValueError(
            "TLS server hostname and CA file are only valid for TLS Telnet connections"
        )
    if not tls_enabled and not is_localhost_equivalent(host):
        raise ValueError(PLAINTEXT_TELNET_HOST_ERROR)

    raw_socket = socket.create_connection((host, port), timeout=timeout_seconds)
    if not tls_enabled:
        return raw_socket
    try:
        context = (
            ssl.create_default_context(cafile=str(tls_ca_file))
            if tls_ca_file is not None
            else ssl.create_default_context()
        )
        context.check_hostname = True
        context.verify_mode = ssl.CERT_REQUIRED
        return context.wrap_socket(
            raw_socket,
            server_hostname=tls_server_hostname,
        )
    except Exception:
        raw_socket.close()
        raise


def run_telnet_smoke_session(
    host,
    port,
    steps,
    timeout_seconds,
    open_session=None,
    retry_window_seconds=0,
    retry_interval_seconds=2,
    play_drain_timeout=1.0,
    default_drain_timeout=0.25,
    step_results=None,
    *,
    tls_enabled,
    tls_server_hostname=None,
    tls_ca_file=None,
):
    return run_transport_session(
        open_session
        or (
            lambda: open_telnet_socket(
                host,
                port,
                timeout_seconds,
                tls_enabled=tls_enabled,
                tls_server_hostname=tls_server_hostname,
                tls_ca_file=tls_ca_file,
            )
        ),
        lambda sock: run_telnet_command_plan(
            sock,
            steps,
            timeout_seconds,
            play_drain_timeout=play_drain_timeout,
            default_drain_timeout=default_drain_timeout,
            step_results=step_results,
        ),
        f"Telnet session {host}:{port}",
        retry_window_seconds=retry_window_seconds,
        retry_interval_seconds=retry_interval_seconds,
    )


def recv_text_websocket(ws, label, timeout=None, *, deadline=None):
    if deadline is None:
        if timeout is None:
            raise TypeError("recv_text_websocket requires timeout or deadline")
        deadline = time.time() + timeout
    last_error = None
    while time.time() < deadline:
        remaining = deadline - time.time()
        if remaining <= 0:
            break
        ws.settimeout(remaining)
        try:
            return ws.recv()
        except Exception as exc:
            if exc.__class__.__name__ != "WebSocketTimeoutException" and not isinstance(
                exc, TimeoutError
            ):
                raise
            last_error = exc
    timeout_description = (
        f"{timeout}s" if timeout is not None else "the command deadline"
    )
    raise ProbeOperationalFailure(
        f"Timed out waiting for {label} after {timeout_description}"
    ) from last_error


def recv_optional_websocket_chunk(ws, label, timeout=None, *, deadline=None):
    try:
        return recv_text_websocket(ws, label, timeout, deadline=deadline).strip()
    except ProbeOperationalFailure:
        return ""


def drain_available_websocket(ws, responses, quiet_timeout=0.25, *, deadline=None):
    quiet_deadline = time.time() + quiet_timeout
    if deadline is not None:
        quiet_deadline = min(quiet_deadline, deadline)
    while time.time() < quiet_deadline:
        chunk = recv_optional_websocket_chunk(
            ws, "drain chunk", deadline=quiet_deadline
        )
        if not chunk:
            return
        responses.append(chunk)


def send_websocket_command_and_expect(
    ws,
    responses,
    line,
    expected_substrings,
    label,
    timeout_seconds,
    step_results=None,
):
    line = _normalize_command_line(line)
    start_index = len(responses)
    started_at = time.time()
    command_deadline = started_at + timeout_seconds
    remaining_timeout = command_deadline - time.time()
    if remaining_timeout <= 0:
        raise ProbeOperationalFailure(f"Timed out before sending {label}")
    ws.settimeout(remaining_timeout)
    ws.send(line)
    remaining_timeout = max(0, command_deadline - time.time())
    response = wait_for_incremental_response(
        lambda: recv_optional_websocket_chunk(
            ws, f"{label} response chunk", deadline=command_deadline
        ),
        responses,
        start_index,
        expected_substrings,
        remaining_timeout,
        lambda parts: "\n".join(chunk for chunk in parts if chunk),
        lambda: drain_available_websocket(
            ws, responses, deadline=command_deadline
        ),
        retry_upstream_failure=label in RETRYABLE_STARTUP_COMMAND_LABELS,
        sanitize_response=lambda value: redact_login_credential(value, line),
        deadline=command_deadline,
    )
    diagnostic_response = redact_login_credential(response, line)
    print(f"=== {label} response ===")
    print(diagnostic_response.strip() or "<empty>")
    if step_results is not None:
        step_results.append(
            {
                "label": label,
                "command": redact_login_command(line),
                "latencyMs": round((time.time() - started_at) * 1000, 3),
                "response": diagnostic_response.strip(),
            }
        )
    return response


def run_websocket_command_plan(ws, steps, timeout_seconds, step_results=None):
    responses = []
    run_command_plan(
        steps,
        lambda line, expected_substrings, label, timeout: send_websocket_command_and_expect(
            ws,
            responses,
            line,
            expected_substrings,
            label,
            timeout_seconds if timeout is None else timeout,
            step_results,
        ),
    )
    return responses


def run_websocket_smoke_session(
    open_session,
    steps,
    timeout_seconds,
    retry_window_seconds=0,
    retry_interval_seconds=2,
    retriable_exceptions=(OSError,),
    session_label="WebSocket session",
    step_results=None,
):
    return run_transport_session(
        open_session,
        lambda ws: run_websocket_command_plan(
            ws,
            steps,
            timeout_seconds,
            step_results=step_results,
        ),
        session_label,
        retry_window_seconds=retry_window_seconds,
        retry_interval_seconds=retry_interval_seconds,
        retriable_exceptions=retriable_exceptions,
    )
