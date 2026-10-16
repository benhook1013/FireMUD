#!/usr/bin/env python3
"""Prove current held-admission behavior against a run-owned Compose stack."""

from __future__ import annotations

import base64
import http.client
import json
import os
import socket
import subprocess
import sys
import time
from urllib.parse import urlsplit

from smoke_common import is_localhost_equivalent

REQUIRED_SERVICES = (
    "account-service",
    "automation-scripting-service",
    "entity-management-service",
    "game-design-service",
    "game-logic-service",
    "game-session-service",
    "gateway",
    "logging-admin-service",
    "minio",
    "postgres",
    "redis-cache",
    "redis-coord",
    "social-groups-service",
    "tcp-proxy-service",
    "world-management-service",
)
HELD_ADMISSION_SERVICES = frozenset({"gateway", "tcp-proxy-service"})
GATEWAY_ERROR_CLASS_HEADER = "X-Firemud-Handshake-Error-Class"
GATEWAY_HELD_ERROR_CLASS = "CONNECT_REPLAY_PROTECTION_UNAVAILABLE"
TELNET_HELD_DENIAL = (
    "DISCONNECT startup_unavailable Gameplay path starting; please reconnect\n"
)
MAX_HTTP_RESPONSE_BYTES = 64 * 1024


class QualificationFailure(RuntimeError):
    """A required held-admission condition was missing or contradictory."""


def parse_compose_status(output: str) -> dict[str, dict[str, object]]:
    """Parse Compose's JSON array or newline-delimited JSON status output."""
    stripped = output.strip()
    if not stripped:
        raise QualificationFailure("Compose status output was empty")
    try:
        if stripped.startswith("["):
            entries = json.loads(stripped)
        else:
            entries = [json.loads(line) for line in stripped.splitlines() if line.strip()]
    except json.JSONDecodeError as exc:
        raise QualificationFailure(f"Compose status output was malformed JSON: {exc}") from exc
    if not isinstance(entries, list) or not entries:
        raise QualificationFailure("Compose status output did not contain service records")

    services: dict[str, dict[str, object]] = {}
    for entry in entries:
        if not isinstance(entry, dict):
            raise QualificationFailure("Compose status contained a non-object service record")
        service = entry.get("Service")
        state = entry.get("State")
        health = entry.get("Health", "")
        if not isinstance(service, str) or not service or not isinstance(state, str):
            raise QualificationFailure("Compose status omitted a service name or state")
        if not isinstance(health, str):
            raise QualificationFailure(f"Compose status for {service} had a malformed health state")
        if service in services:
            raise QualificationFailure(f"Compose status duplicated service {service}")
        services[service] = entry
    return services


def classify_compose_status(
    services: dict[str, dict[str, object]],
) -> tuple[str, str]:
    """Return ready/pending or fail on an unexpected held-edge health state."""
    missing = [service for service in REQUIRED_SERVICES if service not in services]
    if missing:
        return "pending", f"missing required Compose services: {', '.join(missing)}"

    pending: list[str] = []
    for service in REQUIRED_SERVICES:
        entry = services[service]
        state = entry.get("State")
        health = entry.get("Health", "")
        if state != "running":
            if state in {"created", "restarting"}:
                pending.append(f"{service}: state={state}")
                continue
            raise QualificationFailure(f"Required Compose service {service} is not running: {state}")

        if service in HELD_ADMISSION_SERVICES:
            if health == "healthy":
                raise QualificationFailure(
                    f"{service} unexpectedly reported healthy during held-admission qualification"
                )
            if health == "unhealthy":
                continue
            if health == "starting":
                pending.append(f"{service}: health=starting")
                continue
            raise QualificationFailure(
                f"{service} did not expose an expected health state: {health or 'missing'}"
            )

        if health == "healthy":
            continue
        if health in {"starting", "unhealthy"}:
            pending.append(f"{service}: health={health}")
            continue
        raise QualificationFailure(
            f"{service} did not expose a required health state: {health or 'missing'}"
        )

    if pending:
        return "pending", "; ".join(pending)
    return "ready", "all required services are running; only Gateway and TCP Proxy are unhealthy"


def validate_out_of_service_response(
    status_code: int, body: bytes, service_name: str, component_name: str
) -> None:
    if status_code != 503:
        raise QualificationFailure(
            f"{service_name} readiness returned HTTP {status_code}; expected HTTP 503"
        )
    if not body:
        raise QualificationFailure(f"{service_name} readiness returned an empty body")
    try:
        payload = json.loads(body.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise QualificationFailure(
            f"{service_name} readiness returned malformed JSON: {exc}"
        ) from exc
    if not isinstance(payload, dict) or payload.get("status") != "OUT_OF_SERVICE":
        raise QualificationFailure(
            f"{service_name} readiness did not report OUT_OF_SERVICE"
        )
    components = payload.get("components")
    if components is not None:
        if not isinstance(components, dict):
            raise QualificationFailure(f"{service_name} readiness components were malformed")
        component = components.get(component_name)
        if not isinstance(component, dict) or component.get("status") != "OUT_OF_SERVICE":
            raise QualificationFailure(
                f"{service_name} readiness component {component_name} was missing or unexpected"
            )


def validate_gateway_first_party_denial(
    status_code: int, headers: dict[str, str], body: bytes
) -> None:
    if status_code != 403:
        raise QualificationFailure(
            f"Gateway first-party WebSocket handshake returned HTTP {status_code}; expected HTTP 403"
        )
    normalized = {name.casefold(): value for name, value in headers.items()}
    observed = normalized.get(GATEWAY_ERROR_CLASS_HEADER.casefold())
    if observed != GATEWAY_HELD_ERROR_CLASS:
        raise QualificationFailure(
            "Gateway first-party WebSocket handshake omitted the expected "
            f"{GATEWAY_ERROR_CLASS_HEADER}: {GATEWAY_HELD_ERROR_CLASS}"
        )
    if body:
        raise QualificationFailure("Gateway first-party denial unexpectedly returned a response body")


def validate_telnet_denial(response: bytes, closed: bool) -> None:
    if not closed:
        raise QualificationFailure("Telnet held-admission response did not close the connection")
    try:
        decoded = response.decode("ascii")
    except UnicodeDecodeError as exc:
        raise QualificationFailure("Telnet held-admission response was not ASCII") from exc
    if decoded != TELNET_HELD_DENIAL:
        raise QualificationFailure(
            "Telnet held-admission response was missing or unexpected: "
            f"{decoded.strip()!r}"
        )


def _local_http_endpoint(base_url: str, name: str) -> tuple[str, int]:
    parsed = urlsplit(base_url)
    if (
        parsed.scheme != "http"
        or not parsed.hostname
        or parsed.username is not None
        or parsed.password is not None
        or parsed.path not in {"", "/"}
        or parsed.query
        or parsed.fragment
        or not is_localhost_equivalent(parsed.hostname)
    ):
        raise QualificationFailure(
            f"{name} endpoint must be an unauthenticated localhost HTTP base URL"
        )
    try:
        port = parsed.port or 80
    except ValueError as exc:
        raise QualificationFailure(f"{name} endpoint had an invalid port") from exc
    return parsed.hostname, port


def _http_request(
    host: str, port: int, method: str, path: str, timeout_seconds: int, headers=None
) -> tuple[int, dict[str, str], bytes]:
    connection = http.client.HTTPConnection(host, port, timeout=timeout_seconds)
    try:
        connection.request(method, path, headers=headers or {})
        response = connection.getresponse()
        body = response.read(MAX_HTTP_RESPONSE_BYTES + 1)
        if len(body) > MAX_HTTP_RESPONSE_BYTES:
            raise QualificationFailure(f"HTTP {method} {host}:{port}{path} returned an oversized body")
        return response.status, dict(response.getheaders()), body
    except (OSError, http.client.HTTPException) as exc:
        raise QualificationFailure(f"HTTP {method} {host}:{port}{path} failed: {exc}") from exc
    finally:
        connection.close()


def _assert_readiness_out_of_service(
    base_url: str, timeout_seconds: int, service_name: str, component_name: str
) -> None:
    host, port = _local_http_endpoint(base_url, service_name)
    status, _, body = _http_request(
        host, port, "GET", "/actuator/health/readiness", timeout_seconds
    )
    validate_out_of_service_response(status, body, service_name, component_name)
    print(f"Confirmed {service_name} public readiness is OUT_OF_SERVICE.")


def _assert_gateway_first_party_denial(base_url: str, timeout_seconds: int) -> None:
    host, port = _local_http_endpoint(base_url, "Gateway")
    headers = {
        "Connection": "Upgrade",
        "Upgrade": "websocket",
        "Sec-WebSocket-Key": base64.b64encode(b"0123456789abcdef").decode("ascii"),
        "Sec-WebSocket-Version": "13",
        # This sentinel is deliberately not a credential and carries no authority.
        "Cookie": "Firemud-Connect-Token=held-admission-probe.invalid",
    }
    status, response_headers, body = _http_request(
        host, port, "GET", "/ws/game", timeout_seconds, headers
    )
    validate_gateway_first_party_denial(status, response_headers, body)
    print(f"Confirmed first-party Gateway denial: {GATEWAY_HELD_ERROR_CLASS}.")


def _assert_telnet_denial(host: str, port: int, timeout_seconds: int) -> None:
    if not is_localhost_equivalent(host):
        raise QualificationFailure("Held-admission plaintext Telnet probe requires localhost")
    if not 1 <= port <= 65535:
        raise QualificationFailure("Held-admission Telnet port is outside the valid range")
    deadline = time.monotonic() + timeout_seconds
    response = bytearray()
    try:
        with socket.create_connection((host, port), timeout=timeout_seconds) as connection:
            while True:
                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    raise QualificationFailure(
                        "Telnet held-admission response timed out before the connection closed"
                    )
                connection.settimeout(remaining)
                try:
                    chunk = connection.recv(4096)
                except TimeoutError as exc:
                    raise QualificationFailure(
                        "Telnet held-admission response timed out before the connection closed"
                    ) from exc
                if not chunk:
                    break
                response.extend(chunk)
                if len(response) > 1024:
                    raise QualificationFailure("Telnet held-admission response was oversized")
    except QualificationFailure:
        raise
    except OSError as exc:
        raise QualificationFailure(f"Telnet held-admission connection failed: {exc}") from exc
    validate_telnet_denial(bytes(response), closed=True)
    print("Confirmed Telnet startup_unavailable denial and closed connection.")


def _positive_env_int(name: str, default: int) -> int:
    raw = os.environ.get(name, str(default))
    try:
        value = int(raw)
    except ValueError as exc:
        raise QualificationFailure(f"{name} must be a positive integer") from exc
    if value <= 0:
        raise QualificationFailure(f"{name} must be a positive integer")
    return value


def wait_for_compose_state(compose_args: list[str], timeout_seconds: int, poll_seconds: int) -> None:
    deadline = time.monotonic() + timeout_seconds
    last_output = ""
    last_pending = "Compose status has not been read"
    command = ["docker", "compose", *compose_args, "ps", "--format", "json"]
    while True:
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            break
        try:
            result = subprocess.run(
                command,
                check=False,
                capture_output=True,
                text=True,
                timeout=remaining,
            )
        except subprocess.TimeoutExpired as exc:
            raise QualificationFailure(
                "Timed out inspecting run-owned Compose status before the health deadline"
            ) from exc
        except OSError as exc:
            raise QualificationFailure(f"Could not inspect run-owned Compose status: {exc}") from exc
        if result.returncode != 0:
            raise QualificationFailure(
                "Could not inspect run-owned Compose status: "
                f"{result.stderr.strip() or result.stdout.strip() or 'docker compose ps failed'}"
            )
        last_output = result.stdout
        state, detail = classify_compose_status(parse_compose_status(result.stdout))
        if state == "ready":
            print("Confirmed required Compose services are running in the held-admission state.")
            return
        last_pending = detail
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            break
        time.sleep(min(poll_seconds, remaining))
    raise QualificationFailure(
        f"Timed out waiting for the required held-admission Compose state: {last_pending}. "
        f"Last Compose status: {last_output.strip() or '<empty>'}"
    )


def _compose_file_args_are_valid(compose_args: list[str]) -> bool:
    if not compose_args or len(compose_args) % 2 != 0:
        return False
    for index in range(0, len(compose_args), 2):
        if compose_args[index] != "-f" or not compose_args[index + 1]:
            return False
    return True


def format_success_message() -> str:
    return (
        "Held-admission edge qualification passed. It proves Gateway/Telnet denial only; "
        "the direct backend positive proof is separate. Successful public Gateway/Telnet "
        "admission remains unproved."
    )


def run_qualification(compose_args: list[str]) -> int:
    try:
        if os.environ.get("FIREMUD_SMOKE_ADMISSION_QUALIFICATION") != "held":
            raise QualificationFailure(
                "Set FIREMUD_SMOKE_ADMISSION_QUALIFICATION=held to run this qualification"
            )
        if not _compose_file_args_are_valid(compose_args):
            raise QualificationFailure("Expected the exact run-owned Compose -f file arguments")

        health_timeout = _positive_env_int("SMOKE_HEALTH_TIMEOUT_SECONDS", 240)
        poll_interval = _positive_env_int("SMOKE_HEALTH_POLL_INTERVAL_SECONDS", 5)
        request_timeout = _positive_env_int("SMOKE_TIMEOUT_SECONDS", 10)
        wait_for_compose_state(compose_args, health_timeout, poll_interval)
        _assert_readiness_out_of_service(
            os.environ.get("SMOKE_GATEWAY_API_BASE", "http://localhost:8080"),
            request_timeout,
            "Gateway",
            "gameplayRouteReadiness",
        )
        _assert_gateway_first_party_denial(
            os.environ.get("SMOKE_GATEWAY_API_BASE", "http://localhost:8080"),
            request_timeout,
        )
        _assert_readiness_out_of_service(
            os.environ.get("SMOKE_TCP_PROXY_API_BASE", "http://localhost:8089"),
            request_timeout,
            "TCP Proxy",
            "trafficAdmissionReadiness",
        )
        telnet_host = os.environ.get("SMOKE_TELNET_HOST", "localhost")
        telnet_port = _positive_env_int("TCP_PROXY_PORT", 2323)
        _assert_telnet_denial(telnet_host, telnet_port, request_timeout)
    except QualificationFailure as exc:
        print(f"Held-admission qualification failed: {exc}", file=sys.stderr)
        return 1
    print(format_success_message())
    return 0


if __name__ == "__main__":
    print(
        "Held-admission checks must be invoked by the run-owned smoke wrapper.",
        file=sys.stderr,
    )
    raise SystemExit(2)
