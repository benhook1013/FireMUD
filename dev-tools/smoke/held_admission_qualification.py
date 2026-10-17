#!/usr/bin/env python3
"""Prove current held-admission behavior against a run-owned Compose stack."""

from __future__ import annotations

import http.client
import ipaddress
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
MAX_GATEWAY_PROBE_OUTPUT_BYTES = 4096

GATEWAY_CONTAINER_PROBE_SCRIPT = r"""set -eu
timeout_seconds="${1:?bounded probe timeout is required}"
allow_value="${FIREMUD_GATEWAY_HEADER_TRUST_TCP_PROXY_ALLOW_INSECURE_HEADERS_FROM_TRUSTED_CIDRS:-false}"
tls_value="${FIREMUD_GATEWAY_TCP_PROXY_TLS_ENABLED:-false}"
cidr_zero="${FIREMUD_GATEWAY_HEADER_TRUST_TCP_PROXY_INSECURE_TRUSTED_CIDRS_0:-}"
if [ "$allow_value" = true ]; then allow_state=true; else allow_state=unexpected; fi
if [ "$tls_value" = false ]; then tls_state=false; else tls_state=unexpected; fi
if [ "$cidr_zero" = 172.16.0.0/12 ]; then cidr_state=172.16.0.0/12; else cidr_state=unexpected; fi
extra_indexes="$(env | awk -F= '$1 ~ /^FIREMUD_GATEWAY_HEADER_TRUST_TCP_PROXY_INSECURE_TRUSTED_CIDRS_[0-9]+$/ && $1 != "FIREMUD_GATEWAY_HEADER_TRUST_TCP_PROXY_INSECURE_TRUSTED_CIDRS_0" { found=1 } END { print found ? "present" : "none" }')"
printf 'SMOKE_GATEWAY_TRUST allow=%s tls=%s cidr0=%s extra_indexes=%s\n' "$allow_state" "$tls_state" "$cidr_state" "$extra_indexes"
if [ "$allow_state" != true ] || [ "$tls_state" != false ] || [ "$cidr_state" != 172.16.0.0/12 ] || [ "$extra_indexes" != none ]; then
  exit 21
fi
probe_tmp="$(mktemp -d /tmp/firemud-held-admission.XXXXXX)"
trap 'rm -rf "$probe_tmp"' EXIT
trap 'exit 130' HUP INT TERM
if curl --silent --show-error --noproxy '*' --http1.1 --connect-timeout "$timeout_seconds" --max-time "$timeout_seconds" --max-filesize 65536 \
  --dump-header "$probe_tmp/headers" --output "$probe_tmp/body" \
  --write-out '%{local_ip}\t%{remote_ip}\t%{http_code}\t%{size_download}\n' \
  -H 'Connection: Upgrade' -H 'Upgrade: websocket' \
  -H 'Sec-WebSocket-Key: MDEyMzQ1Njc4OWFiY2RlZg==' -H 'Sec-WebSocket-Version: 13' \
  -H 'Cookie: Firemud-Connect-Token=held-admission-probe.invalid' \
  http://127.0.0.1:8080/ws/game >"$probe_tmp/metrics"; then
  curl_state=ok
else
  curl_state=failed
fi
header_bytes="$(wc -c < "$probe_tmp/headers" | tr -d '[:space:]')"
body_bytes="$(wc -c < "$probe_tmp/body" | tr -d '[:space:]')"
if [ "$header_bytes" -le 32768 ]; then
  class_pair="$(awk -F: 'tolower($1) == "x-firemud-handshake-error-class" { count++; value=$2; sub(/^[[:space:]]+/, "", value); sub(/\r$/, "", value) } END { printf "%d\t%s", count, value }' "$probe_tmp/headers")"
  class_count="${class_pair%%$(printf '\t')*}"
  class_value="${class_pair#*$(printf '\t')}"
  headers_state=ok
else
  class_count=oversized
  class_value=
  headers_state=oversized
fi
case "$class_count:$class_value" in
  1:CONNECT_REPLAY_PROTECTION_UNAVAILABLE) class_state=expected ;;
  0:*) class_state=missing ;;
  *) class_state=unexpected ;;
esac
IFS="$(printf '\t')" read -r local_ip remote_ip http_status download_bytes < "$probe_tmp/metrics" || true
printf 'SMOKE_GATEWAY_RESULT local_ip=%s remote_ip=%s status=%s class=%s class_count=%s body_bytes=%s curl=%s headers=%s downloaded=%s\n' \
  "${local_ip:-missing}" "${remote_ip:-missing}" "${http_status:-missing}" "$class_state" \
  "$class_count" "$body_bytes" "$curl_state" "$headers_state" "${download_bytes:-missing}"
"""


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


def _parse_probe_record(line: str, prefix: str, keys: set[str]) -> dict[str, str]:
    if not line.startswith(f"{prefix} "):
        raise QualificationFailure(f"Gateway container probe omitted its {prefix} record")
    fields: dict[str, str] = {}
    for item in line[len(prefix) + 1 :].split(" "):
        if "=" not in item:
            raise QualificationFailure(f"Gateway container probe had a malformed {prefix} record")
        name, value = item.split("=", 1)
        if name not in keys or name in fields or not value:
            raise QualificationFailure(f"Gateway container probe had an unexpected {prefix} field")
        fields[name] = value
    if fields.keys() != keys:
        raise QualificationFailure(f"Gateway container probe had an incomplete {prefix} record")
    return fields


def parse_gateway_container_probe_output(
    output: str,
) -> tuple[dict[str, str], dict[str, str]]:
    if len(output.encode("utf-8")) > MAX_GATEWAY_PROBE_OUTPUT_BYTES:
        raise QualificationFailure("Gateway container probe output exceeded its 4 KiB bound")
    lines = output.splitlines()
    if len(lines) != 2:
        raise QualificationFailure("Gateway container probe output was missing or malformed")
    trust = _parse_probe_record(
        lines[0],
        "SMOKE_GATEWAY_TRUST",
        {"allow", "tls", "cidr0", "extra_indexes"},
    )
    result = _parse_probe_record(
        lines[1],
        "SMOKE_GATEWAY_RESULT",
        {
            "local_ip",
            "remote_ip",
            "status",
            "class",
            "class_count",
            "body_bytes",
            "curl",
            "headers",
            "downloaded",
        },
    )
    return trust, result


def validate_gateway_container_first_party_result(
    trust: dict[str, str], result: dict[str, str]
) -> None:
    expected_trust = {
        "allow": "true",
        "tls": "false",
        "cidr0": "172.16.0.0/12",
        "extra_indexes": "none",
    }
    if trust != expected_trust:
        raise QualificationFailure(
            "Gateway runtime trust configuration did not match the local Compose contract: "
            f"allow={trust.get('allow', 'missing')}, tls={trust.get('tls', 'missing')}, "
            f"cidr0={trust.get('cidr0', 'missing')}, "
            f"extra_indexes={trust.get('extra_indexes', 'missing')}"
        )

    observed_addresses = {}
    for field in ("local_ip", "remote_ip"):
        try:
            observed_ip = ipaddress.ip_address(result[field])
        except (KeyError, ValueError) as exc:
            raise QualificationFailure(
                f"Gateway container probe had an invalid {field} socket address"
            ) from exc
        if observed_ip != ipaddress.IPv4Address("127.0.0.1"):
            raise QualificationFailure(
                f"Gateway container probe {field} was {observed_ip}; expected loopback 127.0.0.1"
            )
        observed_addresses[field] = observed_ip
    try:
        configured_trust_cidr = ipaddress.ip_network(trust["cidr0"], strict=True)
    except (KeyError, ValueError) as exc:
        raise QualificationFailure("Gateway runtime trust CIDR was malformed") from exc
    if observed_addresses["remote_ip"] in configured_trust_cidr:
        raise QualificationFailure(
            "Gateway loopback peer falls inside the configured insecure TCP trust CIDR"
        )

    expected_result = {
        "status": "403",
        "class": "expected",
        "class_count": "1",
        "body_bytes": "0",
        "curl": "ok",
        "headers": "ok",
        "downloaded": "0",
    }
    for field, expected in expected_result.items():
        if result.get(field) != expected:
            raise QualificationFailure(
                "Gateway container loopback handshake had an unexpected result: "
                f"{field}={result.get(field, 'missing')}, expected {expected}"
            )
    # HeaderTrustFilter(-4) short-circuits trusted session traffic without a
    # proxy client IP; this later handshake class proves the observed loopback
    # request continued as an untrusted peer.


def _assert_gateway_first_party_denial(
    compose_args: list[str], timeout_seconds: int
) -> None:
    command = [
        "docker",
        "compose",
        *compose_args,
        "exec",
        "-T",
        "gateway",
        "sh",
        "-c",
        GATEWAY_CONTAINER_PROBE_SCRIPT,
        "held-admission-gateway-probe",
        str(timeout_seconds),
    ]
    try:
        result = subprocess.run(
            command,
            check=False,
            capture_output=True,
            text=True,
            encoding="ascii",
            errors="replace",
            timeout=timeout_seconds,
        )
    except subprocess.TimeoutExpired as exc:
        raise QualificationFailure(
            "Gateway container loopback handshake timed out within its bounded request time"
        ) from exc
    except OSError as exc:
        raise QualificationFailure("Could not execute the run-owned Gateway container probe") from exc

    if len(result.stdout.encode("utf-8")) > MAX_GATEWAY_PROBE_OUTPUT_BYTES:
        raise QualificationFailure("Gateway container probe output exceeded its 4 KiB bound")
    lines = result.stdout.splitlines()
    if result.returncode != 0:
        if lines and lines[0].startswith("SMOKE_GATEWAY_TRUST "):
            trust = _parse_probe_record(
                lines[0],
                "SMOKE_GATEWAY_TRUST",
                {"allow", "tls", "cidr0", "extra_indexes"},
            )
            expected_trust = {
                "allow": "true",
                "tls": "false",
                "cidr0": "172.16.0.0/12",
                "extra_indexes": "none",
            }
            if trust != expected_trust:
                raise QualificationFailure(
                    "Gateway runtime trust configuration did not match the local Compose contract: "
                    f"allow={trust['allow']}, tls={trust['tls']}, cidr0={trust['cidr0']}, "
                    f"extra_indexes={trust['extra_indexes']}"
                )
        raise QualificationFailure(
            f"Run-owned Gateway container probe exited with status {result.returncode}"
        )

    trust, probe = parse_gateway_container_probe_output(result.stdout)
    validate_gateway_container_first_party_result(trust, probe)
    print(
        "Observed run-owned Gateway TCP trust inputs: insecure=true, TLS=false, "
        "environment CIDR[0]=172.16.0.0/12, higher environment indexes=none."
    )
    print(
        "Confirmed the run-owned Gateway container's public listener received the first-party "
        f"loopback handshake at {probe['remote_ip']} and denied it with "
        f"{GATEWAY_HELD_ERROR_CLASS}; this does not prove host-published ingress."
    )


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
    command = ["docker", "compose", *compose_args, "ps", "--all", "--format", "json"]
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
        _assert_gateway_first_party_denial(compose_args, request_timeout)
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
