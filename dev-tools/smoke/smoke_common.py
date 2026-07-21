import json
import os
import socket
import subprocess
import time
import urllib.error
import urllib.request
from contextlib import closing
from urllib.parse import quote


class TransientUpstreamSmokeFailure(RuntimeError):
    """A startup-time upstream error that a bounded smoke retry may retry."""


RETRYABLE_STARTUP_COMMAND_LABELS = frozenset({"WORLDS", "LOGIN"})


def compose_postgres_container_name():
    compose_project_name = os.environ.get("COMPOSE_PROJECT_NAME", "docker")
    return f"{compose_project_name}-postgres-1"


def verify_smoke_account(account_api_base, tenant_id, username, password, timeout_seconds):
    payload = json.dumps(
        {
            "tenantId": int(tenant_id),
            "username": username,
            "password": password,
            "otp": "",
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
                body = response.read().decode("utf-8", errors="ignore").strip()
                print("=== Account validation response ===")
                print(body or "<empty>")
                if response.status >= 500:
                    raise RuntimeError(
                        f"Smoke account validation returned unexpected status {response.status}"
                    )
                return body
        except urllib.error.HTTPError as exc:
            body = exc.read().decode("utf-8", errors="ignore").strip()
            print("=== Account validation response ===")
            print(body or "<empty>")
            raise RuntimeError(
                f"Smoke account validation failed with status {exc.code}: {body or '<empty>'}"
            ) from exc
        except OSError as exc:
            if attempt < 3:
                time.sleep(1)
                continue
            raise RuntimeError(f"Smoke account validation failed: {exc}") from exc


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
    except urllib.error.HTTPError as exc:
        body = exc.read().decode("utf-8", errors="ignore").strip()
        raise RuntimeError(
            f"Request {method} {url} failed with status {exc.code}: {body or '<empty>'}"
        ) from exc
    except urllib.error.URLError as exc:
        raise RuntimeError(f"Request {method} {url} failed: {exc}") from exc


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
            with closing(open_session()) as session:
                return execute_session(session)
        except TransientUpstreamSmokeFailure:
            if deadline is None:
                raise
            remaining = deadline - time.time()
            if remaining <= 0:
                raise
            time.sleep(min(retry_interval_seconds, remaining))
            if time.time() >= deadline:
                raise
        except retriable_exceptions as exc:
            if deadline is None:
                raise RuntimeError(f"Failed to open {session_label}: {exc}") from exc
            remaining = deadline - time.time()
            if remaining <= 0:
                raise RuntimeError(f"Failed to open {session_label}: {exc}") from exc
            time.sleep(min(retry_interval_seconds, remaining))
            if time.time() >= deadline:
                raise RuntimeError(f"Failed to open {session_label}: {exc}") from exc


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
):
    deadline = time.time() + timeout
    expects_explicit_failure = any(
        any(substring.startswith(prefix) for prefix in explicit_failure_prefixes)
        for substring in expected_substrings
    )
    response = combine_responses(responses[start_index:])
    while time.time() < deadline:
        chunk = next_chunk()
        if chunk:
            responses.append(chunk)
            response = combine_responses(responses[start_index:])
            stripped = response.strip()
            if not expects_explicit_failure and any(
                stripped.startswith(prefix) for prefix in explicit_failure_prefixes
            ):
                if retry_upstream_failure and stripped.startswith(
                    ("ERROR UPSTREAM_FAILURE", "ERROR UNAVAILABLE")
                ):
                    raise TransientUpstreamSmokeFailure(
                        f"Command failed explicitly: {stripped}"
                    )
                raise RuntimeError(f"Command failed explicitly: {stripped}")
            if all(substring in response for substring in expected_substrings):
                if drain_remaining is not None:
                    trailing = drain_remaining()
                    if trailing:
                        responses.append(trailing)
                        response = combine_responses(responses[start_index:])
                return response
        else:
            time.sleep(idle_sleep_seconds)
    raise RuntimeError(
        f"Expected response containing {expected_substrings}, got '{response}'"
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


def recv_until_socket(sock, expected_substring, timeout):
    deadline = time.time() + timeout
    chunks = []
    while time.time() < deadline:
        try:
            sock.settimeout(deadline - time.time())
            data = sock.recv(4096)
        except (socket.timeout, BlockingIOError):
            break
        if not data:
            break
        chunks.append(data.decode("iso-8859-1", errors="ignore"))
        joined = "".join(chunks)
        if expected_substring in joined:
            return joined
    return "".join(chunks)


def drain_available_socket(sock, quiet_timeout=0.25):
    deadline = time.time() + quiet_timeout
    chunks = []
    while time.time() < deadline:
        try:
            sock.settimeout(max(0.05, deadline - time.time()))
            data = sock.recv(4096)
        except (socket.timeout, BlockingIOError):
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
    start_index = len(responses)
    started_at = time.time()
    sock.sendall(f"{line}\r\n".encode("iso-8859-1"))
    response = wait_for_incremental_response(
        lambda: recv_until_socket(sock, "", 0.5),
        responses,
        start_index,
        expected_substrings,
        timeout_seconds,
        "".join,
        lambda: drain_available_socket(sock, drain_timeout),
        retry_upstream_failure=label in RETRYABLE_STARTUP_COMMAND_LABELS,
    )
    print(f"=== {label} response ===")
    print(response.strip() or "<no data>")
    if step_results is not None:
        step_results.append(
            {
                "label": label,
                "command": line,
                "latencyMs": round((time.time() - started_at) * 1000, 3),
                "response": response.strip(),
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
):
    return run_transport_session(
        open_session
        or (lambda: socket.create_connection((host, port), timeout=timeout_seconds)),
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


def recv_text_websocket(ws, label, timeout):
    deadline = time.time() + timeout
    last_error = None
    while time.time() < deadline:
        remaining = deadline - time.time()
        ws.settimeout(min(1.0, max(0.1, remaining)))
        try:
            return ws.recv()
        except Exception as exc:
            if exc.__class__.__name__ != "WebSocketTimeoutException" and not isinstance(
                exc, TimeoutError
            ):
                raise
            last_error = exc
    raise RuntimeError(f"Timed out waiting for {label} after {timeout}s") from last_error


def recv_optional_websocket_chunk(ws, label, timeout):
    try:
        return recv_text_websocket(ws, label, timeout).strip()
    except RuntimeError:
        return ""


def drain_available_websocket(ws, responses, quiet_timeout=0.25):
    deadline = time.time() + quiet_timeout
    while time.time() < deadline:
        remaining = max(0.05, deadline - time.time())
        chunk = recv_optional_websocket_chunk(ws, "drain chunk", remaining)
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
    start_index = len(responses)
    started_at = time.time()
    ws.send(line)
    response = wait_for_incremental_response(
        lambda: recv_optional_websocket_chunk(
            ws, f"{label} response chunk", min(0.5, timeout_seconds)
        ),
        responses,
        start_index,
        expected_substrings,
        timeout_seconds,
        lambda parts: "\n".join(chunk for chunk in parts if chunk),
        lambda: drain_available_websocket(ws, responses),
        retry_upstream_failure=label in RETRYABLE_STARTUP_COMMAND_LABELS,
    )
    print(f"=== {label} response ===")
    print(response.strip() or "<empty>")
    if step_results is not None:
        step_results.append(
            {
                "label": label,
                "command": line,
                "latencyMs": round((time.time() - started_at) * 1000, 3),
                "response": response.strip(),
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
