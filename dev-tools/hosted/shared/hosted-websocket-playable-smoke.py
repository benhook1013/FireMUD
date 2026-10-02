#!/usr/bin/env python3
"""Diagnostic first-party hosted WSS LOGIN -> PLAY -> LOOK smoke.

This is an operator probe for the deployed public path.  It is deliberately
not a synthetic canary and does not emit promotion or release evidence.  The
Account bootstrap token is used only to discover a target and mint the
Gateway-owned, first-party ``Firemud-Connect-Token`` cookie; credentials are
never sent over the WebSocket or included in errors.
"""

from __future__ import annotations

import argparse
import json
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from collections.abc import Callable, Mapping, Sequence
from dataclasses import dataclass
from typing import Any

DEFAULT_USERNAME = "demo@example.com"
DEFAULT_PASSWORD = "swordfish"
DEFAULT_WORLD = "demo"
DEFAULT_REALM = "production"
DEFAULT_AUTH_PREFIX = "/api/account"
DEFAULT_GATEWAY_BASE = "http://localhost:8080"
MAX_HTTP_RESPONSE_BYTES = 1_048_576
MAX_WEBSOCKET_FRAME_BYTES = 1_048_576
MAX_WEBSOCKET_MESSAGE_BYTES = 1_048_576
MAX_WEBSOCKET_RECEIVE_FRAMES = 256
MAX_WEBSOCKET_RECEIVE_BYTES = 2 * 1_048_576
MAX_WEBSOCKET_WAIT_FRAMES = 1_024
MAX_WEBSOCKET_WAIT_MESSAGES = 256
MAX_WEBSOCKET_WAIT_BYTES = 2 * 1_048_576
MAX_WEBSOCKET_CLOSE_SECONDS = 3.0


class HostedWebSocketPlayableSmokeError(RuntimeError):
    """A bounded, operator-actionable diagnostic failure."""


@dataclass(frozen=True)
class SmokeConfig:
    auth_api_base: str
    websocket_url: str
    origin: str = ""
    auth_api_prefix: str = DEFAULT_AUTH_PREFIX
    readiness_url: str | None = None
    revoke_url: str | None = None
    username: str = DEFAULT_USERNAME
    password: str = DEFAULT_PASSWORD
    world: str = DEFAULT_WORLD
    realm: str = DEFAULT_REALM
    character: str | None = None
    expected_room_id: str | None = None
    timeout_seconds: float = 10.0
    exercise_logout: bool = False
    exercise_reconnect: bool = False


@dataclass(frozen=True)
class HttpResponse:
    status: int
    headers: Mapping[str, str]
    body: Any


HttpRequest = Callable[[str, str, Any, Mapping[str, str], float], HttpResponse]
WebSocketFactory = Callable[[str, float, Sequence[str]], Any]
WEBSOCKET_CLOSE_OPCODE = 0x8


class _WebSocketReceiveLimitExceeded(ValueError):
    """An inbound WebSocket frame or receive budget exceeded its local bound."""


def _websocket_payload_size(payload: Any) -> int:
    if isinstance(payload, bytes):
        return len(payload)
    if isinstance(payload, str):
        return len(payload.encode("utf-8"))
    return 0


def _bounded_websocket_client(websocket: Any) -> type:
    """Build the smoke-only bounded adapter for the pinned websocket-client API."""
    # websocket-client 1.9.2 exposes create_connection(class_) but no public
    # max-frame/message setting. These two private _abnf hooks are intentionally
    # isolated here; the requirements pin must be reviewed with this adapter.
    from websocket import _abnf

    class BoundedFrameBuffer(_abnf.frame_buffer):
        def __init__(
            self,
            recv_fn: Callable[[int], bytes],
            skip_utf8_validation: bool,
            owner: Any,
        ):
            super().__init__(recv_fn, skip_utf8_validation)
            self.owner = owner

        def recv_length(self) -> None:
            super().recv_length()
            if self.header is None or self.length is None:
                raise _WebSocketReceiveLimitExceeded("incomplete WebSocket frame header")
            opcode = self.header[4]
            maximum = 125 if opcode & 0x8 else MAX_WEBSOCKET_FRAME_BYTES
            try:
                if self.length > maximum:
                    raise _WebSocketReceiveLimitExceeded("WebSocket frame exceeds its size limit")
                self.owner._reserve_inbound_frame(self.length)
            except _WebSocketReceiveLimitExceeded:
                self.clear()
                self.owner._receive_limit_failed = True
                raise

    class BoundedContinuousFrame(_abnf.continuous_frame):
        def add(self, frame: Any) -> None:
            if frame.opcode in (_abnf.ABNF.OPCODE_TEXT, _abnf.ABNF.OPCODE_BINARY):
                previous_size = len(self.cont_data[1]) if self.cont_data else 0
                if previous_size + len(frame.data) > MAX_WEBSOCKET_MESSAGE_BYTES:
                    raise _WebSocketReceiveLimitExceeded("WebSocket message exceeds its size limit")
            elif frame.opcode == _abnf.ABNF.OPCODE_CONT and self.cont_data:
                if len(self.cont_data[1]) + len(frame.data) > MAX_WEBSOCKET_MESSAGE_BYTES:
                    raise _WebSocketReceiveLimitExceeded("WebSocket message exceeds its size limit")
            super().add(frame)

    class BoundedWebSocket(websocket.WebSocket):
        def __init__(self, *args: Any, **kwargs: Any):
            super().__init__(*args, **kwargs)
            self._receive_deadline: float | None = None
            self._receive_operation_active = False
            self._receive_operation_frames = 0
            self._receive_operation_bytes = 0
            self._last_receive_usage = (0, 0)
            self._receive_limit_failed = False
            self.frame_buffer = BoundedFrameBuffer(
                self._recv,
                self.frame_buffer.skip_utf8_validation,
                self,
            )
            self.cont_frame = BoundedContinuousFrame(
                self.cont_frame.fire_cont_frame,
                self.cont_frame.skip_utf8_validation,
            )

        def set_receive_deadline(self, deadline: float | None) -> None:
            self._receive_deadline = deadline

        def _reserve_inbound_frame(self, payload_bytes: int) -> None:
            if self._receive_operation_frames + 1 > MAX_WEBSOCKET_RECEIVE_FRAMES:
                raise _WebSocketReceiveLimitExceeded("WebSocket receive frame budget exceeded")
            if self._receive_operation_bytes + payload_bytes > MAX_WEBSOCKET_RECEIVE_BYTES:
                raise _WebSocketReceiveLimitExceeded("WebSocket receive byte budget exceeded")
            self._receive_operation_frames += 1
            self._receive_operation_bytes += payload_bytes

        def _begin_receive_operation(self) -> bool:
            if self._receive_operation_active:
                return False
            self._receive_operation_active = True
            self._receive_operation_frames = 0
            self._receive_operation_bytes = 0
            return True

        def _end_receive_operation(self, started: bool) -> None:
            if started:
                self._last_receive_usage = (
                    self._receive_operation_frames,
                    self._receive_operation_bytes,
                )
                self._receive_operation_active = False

        def last_receive_usage(self) -> tuple[int, int]:
            return self._last_receive_usage

        def _recv(self, bufsize: int) -> bytes:
            deadline = self._receive_deadline
            if deadline is not None:
                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    raise TimeoutError("WebSocket receive deadline expired")
                if self.sock is not None:
                    self.sock.settimeout(remaining)
            result = super()._recv(bufsize)
            if deadline is not None and time.monotonic() >= deadline:
                raise TimeoutError("WebSocket receive deadline expired")
            return result

        def recv_frame(self) -> Any:
            started = self._begin_receive_operation()
            try:
                return super().recv_frame()
            except _WebSocketReceiveLimitExceeded:
                self._receive_limit_failed = True
                raise
            finally:
                self._end_receive_operation(started)

        def recv_data_frame(self, control_frame: bool = False) -> tuple:
            started = self._begin_receive_operation()
            try:
                return super().recv_data_frame(control_frame)
            except _WebSocketReceiveLimitExceeded:
                self._receive_limit_failed = True
                raise
            finally:
                self._end_receive_operation(started)

        def close(
            self,
            status: int = 1000,
            reason: str | bytes = b"",
            timeout: float | None = 3,
        ) -> None:
            if self._receive_limit_failed or not self.connected:
                self.shutdown()
                return
            close_timeout = (
                MAX_WEBSOCKET_CLOSE_SECONDS
                if timeout is None
                else min(max(0.0, float(timeout)), MAX_WEBSOCKET_CLOSE_SECONDS)
            )
            previous_deadline = self._receive_deadline
            self._receive_deadline = time.monotonic() + close_timeout
            started = self._begin_receive_operation()
            try:
                if self.sock is not None:
                    self.sock.settimeout(close_timeout)
                super().close(status=status, reason=reason, timeout=close_timeout)
            finally:
                self.shutdown()
                self._receive_deadline = previous_deadline
                self._end_receive_operation(started)

    return BoundedWebSocket


def redact_credentials(value: Any, username: str, password: str) -> str:
    text = str(value)
    for secret in (password, username):
        if secret:
            text = text.replace(secret, "<redacted>")
    return text


def _fail(message: str, config: SmokeConfig) -> HostedWebSocketPlayableSmokeError:
    return HostedWebSocketPlayableSmokeError(redact_credentials(message, config.username, config.password))


def _join_url(base: str, path: str) -> str:
    return base.rstrip("/") + "/" + path.lstrip("/")


def _quote(value: str) -> str:
    return urllib.parse.quote(value, safe="")


def _valid_character_name(value: object) -> bool:
    return (
        isinstance(value, str)
        and bool(value)
        and value == value.strip()
        and not any(ord(character) < 0x20 or ord(character) == 0x7F for character in value)
    )


def _header(headers: Mapping[str, str], name: str) -> str:
    wanted = name.lower()
    for key, value in headers.items():
        if key.lower() == wanted:
            return value
    return ""


def _decode_json(body: Any, description: str, config: SmokeConfig) -> Any:
    if isinstance(body, (bytes, bytearray)):
        body = body.decode("utf-8", errors="replace")
    if isinstance(body, (dict, list)):
        return body
    try:
        return json.loads(str(body))
    except (TypeError, json.JSONDecodeError) as exc:
        raise _fail(f"{description} returned malformed JSON", config) from exc


def _require_success(response: HttpResponse, description: str, config: SmokeConfig) -> Any:
    if not 200 <= response.status < 300:
        raise _fail(f"{description} returned HTTP {response.status}", config)
    return _decode_json(response.body, description, config)


def _require_envelope(response: HttpResponse, description: str, config: SmokeConfig) -> Any:
    payload = _require_success(response, description, config)
    if not isinstance(payload, dict) or "data" not in payload:
        raise _fail(f"{description} returned a malformed response envelope", config)
    return payload["data"]


class _RejectRedirectHandler(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        # Returning None leaves the original 3xx response for the normal status
        # check; no second request can carry bootstrap authority or cookies.
        return None


def _read_http_body(response: Any) -> tuple[bytes, bool]:
    # Socket timeouts bound stalls, not bytes; the sentinel independently caps
    # memory use and JSON input while allowing an exactly-at-limit response.
    body = response.read(MAX_HTTP_RESPONSE_BYTES + 1)
    return body[:MAX_HTTP_RESPONSE_BYTES], len(body) > MAX_HTTP_RESPONSE_BYTES


def _default_http_request(
    method: str,
    url: str,
    payload: Any,
    headers: Mapping[str, str],
    timeout: float,
) -> HttpResponse:
    body = None if payload is None else json.dumps(payload).encode("utf-8")
    request = urllib.request.Request(url, data=body, method=method, headers=dict(headers))
    opener = urllib.request.build_opener(_RejectRedirectHandler())
    try:
        with opener.open(request, timeout=timeout) as response:
            response_body, oversized = _read_http_body(response)
            if oversized:
                raise HostedWebSocketPlayableSmokeError(
                    f"{method} returned HTTP {response.status} with a response body exceeding "
                    f"{MAX_HTTP_RESPONSE_BYTES} bytes"
                )
            return HttpResponse(
                response.status,
                dict(response.headers.items()),
                response_body,
            )
    except urllib.error.HTTPError as exc:
        try:
            response_body, _ = _read_http_body(exc)
            return HttpResponse(exc.code, dict(exc.headers.items()), response_body)
        finally:
            exc.close()
    except (OSError, urllib.error.URLError) as exc:
        raise HostedWebSocketPlayableSmokeError(f"{method} {url} failed: {exc.__class__.__name__}") from exc


def _request(
    http_request: HttpRequest,
    config: SmokeConfig,
    method: str,
    url: str,
    *,
    payload: Any = None,
    headers: Mapping[str, str] | None = None,
) -> HttpResponse:
    try:
        return http_request(
            method,
            url,
            payload,
            headers or {},
            config.timeout_seconds,
        )
    except HostedWebSocketPlayableSmokeError:
        raise
    except Exception as exc:
        raise _fail(f"{method} {url} transport failed: {exc.__class__.__name__}", config) from exc


def _auth_url(config: SmokeConfig, path: str) -> str:
    return _join_url(_join_url(config.auth_api_base, config.auth_api_prefix), path)


def _validate_origin(config: SmokeConfig) -> None:
    parsed = urllib.parse.urlparse(config.origin)
    if (
        parsed.scheme not in {"http", "https"}
        or not parsed.hostname
        or parsed.path != ""
        or parsed.params
        or parsed.query
        or parsed.fragment
    ):
        raise _fail(
            "a valid first-party Origin (for example https://frontend.example) is required",
            config,
        )


def _bootstrap(config: SmokeConfig, http_request: HttpRequest) -> str:
    data = _require_envelope(
        _request(
            http_request,
            config,
            "POST",
            _auth_url(config, "/auth/player-bootstrap"),
            payload={"accountIdentifier": config.username, "secret": config.password},
            headers={"Content-Type": "application/json"},
        ),
        "player bootstrap",
        config,
    )
    if not isinstance(data, dict) or not isinstance(data.get("bootstrapToken"), str):
        raise _fail("player bootstrap returned no bootstrap token", config)
    if not data["bootstrapToken"]:
        raise _fail("player bootstrap returned an empty bootstrap token", config)
    return data["bootstrapToken"]


def _discover_target(
    config: SmokeConfig,
    http_request: HttpRequest,
    bootstrap_token: str,
) -> tuple[str, str | None]:
    headers = {"Authorization": f"Bearer {bootstrap_token}"}
    worlds = _require_envelope(
        _request(http_request, config, "GET", _auth_url(config, "/auth/bootstrap/worlds"), headers=headers),
        "bootstrap worlds",
        config,
    )
    if not isinstance(worlds, list) or not any(
        isinstance(world, dict) and world.get("worldSlug") == config.world for world in worlds
    ):
        raise _fail(f"world {config.world!r} was not visible during bootstrap discovery", config)

    realms_url = _auth_url(config, f"/auth/bootstrap/worlds/{_quote(config.world)}/realms")
    realms = _require_envelope(
        _request(http_request, config, "GET", realms_url, headers=headers),
        "bootstrap realms",
        config,
    )
    realm = (
        next(
            (
                candidate
                for candidate in realms
                if isinstance(candidate, dict) and candidate.get("realmSlug") == config.realm
            ),
            None,
        )
        if isinstance(realms, list)
        else None
    )
    if not isinstance(realm, dict) or not isinstance(realm.get("connectScopeId"), str):
        raise _fail(f"realm {config.realm!r} was not visible during bootstrap discovery", config)
    connect_scope_id = realm["connectScopeId"]
    if not connect_scope_id:
        raise _fail("bootstrap realm returned an empty connect scope", config)

    query = urllib.parse.urlencode({"connectScopeId": connect_scope_id})
    characters_url = _auth_url(
        config,
        f"/auth/bootstrap/worlds/{_quote(config.world)}/realms/{_quote(config.realm)}/characters?{query}",
    )
    characters = _require_envelope(
        _request(http_request, config, "GET", characters_url, headers=headers),
        "bootstrap characters",
        config,
    )
    if not isinstance(characters, list):
        raise _fail("bootstrap characters returned a malformed list", config)
    if config.character:
        if not _valid_character_name(config.character):
            raise _fail("configured character name is malformed", config)
        if not any(
            isinstance(candidate, dict)
            and _valid_character_name(candidate.get("characterName"))
            and candidate.get("characterName") == config.character
            for candidate in characters
        ):
            raise _fail("configured character was not visible during bootstrap discovery", config)
        return connect_scope_id, config.character
    if len(characters) > 1:
        raise _fail(
            "bootstrap character discovery returned multiple characters; configure an explicit character",
            config,
        )
    if not characters:
        raise _fail(
            "bootstrap character discovery returned no valid current character",
            config,
        )
    first = characters[0]
    if not isinstance(first, dict):
        raise _fail("bootstrap characters returned a malformed entry", config)
    character = first.get("characterName")
    if not _valid_character_name(character):
        raise _fail("bootstrap characters returned a malformed name", config)
    return connect_scope_id, character


def _connect_context(
    config: SmokeConfig,
    http_request: HttpRequest,
    bootstrap_token: str,
    request_id: str,
) -> tuple[str, str | None]:
    # Discovery and connect-token issuance intentionally happen together for
    # reconnect: each fresh WSS admission receives a fresh one-use cookie.
    scope, character = _discover_target(config, http_request, bootstrap_token)
    response = _request(
        http_request,
        config,
        "POST",
        _auth_url(config, "/auth/connect-token"),
        payload={"connectScopeId": scope, "requestId": request_id},
        headers={
            "Authorization": f"Bearer {bootstrap_token}",
            "Content-Type": "application/json",
        },
    )
    _require_envelope(response, "connect-token issuance", config)
    set_cookie = _header(response.headers, "Set-Cookie")
    pair = set_cookie.split(";", 1)[0].strip()
    name, separator, value = pair.partition("=")
    if separator != "=" or name != "Firemud-Connect-Token" or not value:
        raise _fail(
            "connect-token issuance did not return a valid Firemud-Connect-Token cookie",
            config,
        )
    return f"Firemud-Connect-Token={value}", character


def _set_receive_deadline(ws: Any, deadline: float) -> None:
    setter = getattr(ws, "set_receive_deadline", None)
    if callable(setter):
        setter(deadline)


def _receive_usage(ws: Any, payload: Any) -> tuple[int, int]:
    getter = getattr(ws, "last_receive_usage", None)
    usage = getter() if callable(getter) else None
    if (
        isinstance(usage, tuple)
        and len(usage) == 2
        and all(isinstance(value, int) and not isinstance(value, bool) and value >= 0 for value in usage)
    ):
        return usage
    return 1, _websocket_payload_size(payload)


def _await_command_result(ws: Any, command_type: str, config: SmokeConfig) -> dict[str, Any]:
    deadline = time.monotonic() + config.timeout_seconds
    received_messages = 0
    received_frames = 0
    received_bytes = 0
    while time.monotonic() < deadline:
        if received_messages >= MAX_WEBSOCKET_WAIT_MESSAGES:
            raise _fail(f"WebSocket receive budget exceeded while waiting for {command_type}", config)
        ws.settimeout(max(0.01, deadline - time.monotonic()))
        _set_receive_deadline(ws, deadline)
        try:
            payload = ws.recv()
        except _WebSocketReceiveLimitExceeded as exc:
            raise _fail(
                f"WebSocket receive limits exceeded while waiting for {command_type}",
                config,
            ) from exc
        except Exception as exc:
            raise _fail(f"WebSocket closed while waiting for {command_type}", config) from exc
        received_messages += 1
        frame_count, byte_count = _receive_usage(ws, payload)
        received_frames += frame_count
        received_bytes += byte_count
        if received_frames > MAX_WEBSOCKET_WAIT_FRAMES or received_bytes > MAX_WEBSOCKET_WAIT_BYTES:
            raise _fail(f"WebSocket receive budget exceeded while waiting for {command_type}", config)
        try:
            parsed = json.loads(payload)
        except (TypeError, json.JSONDecodeError):
            continue
        if not isinstance(parsed, dict):
            continue
        if parsed.get("eventType") != "command_result" or parsed.get("commandType") != command_type:
            continue
        if parsed.get("accepted") is not True:
            raise _fail(f"{command_type} was rejected by the first-party gameplay session", config)
        return parsed
    raise _fail(f"timed out waiting for structured {command_type} result", config)


def _require_look_view(response: dict[str, Any], config: SmokeConfig) -> tuple[str, str]:
    outputs = response.get("outputs")
    if not isinstance(outputs, list):
        raise _fail("LOOK accepted without an authoritative LOOK view", config)
    for output in outputs:
        if not isinstance(output, dict) or output.get("payloadType") != "look_view":
            continue
        payload = output.get("payload")
        if not isinstance(payload, dict):
            continue
        room_id = payload.get("roomId")
        room_name = payload.get("roomName")
        if not (isinstance(room_id, str) and room_id.strip() and isinstance(room_name, str) and room_name.strip()):
            continue
        if config.expected_room_id is not None and room_id != config.expected_room_id:
            raise _fail("LOOK room ID did not match the expected Telnet parity room ID", config)
        return room_id, room_name
    raise _fail("LOOK accepted without an authoritative LOOK view", config)


def _await_websocket_close(ws: Any, config: SmokeConfig) -> None:
    """Require websocket-client's documented close-control-frame observation."""
    recv_data = getattr(ws, "recv_data", None)
    if not callable(recv_data):
        raise _fail(
            "first-party WSS close observation is unavailable; expected a WebSocket close opcode",
            config,
        )

    deadline = time.monotonic() + config.timeout_seconds
    received_messages = 0
    received_frames = 0
    received_bytes = 0
    while time.monotonic() < deadline:
        if received_messages >= MAX_WEBSOCKET_WAIT_MESSAGES:
            raise _fail("WebSocket receive budget exceeded while waiting for close", config)
        ws.settimeout(max(0.01, deadline - time.monotonic()))
        _set_receive_deadline(ws, deadline)
        try:
            observation = recv_data(control_frame=True)
        except _WebSocketReceiveLimitExceeded as exc:
            raise _fail(
                "WebSocket receive limits exceeded while waiting for close",
                config,
            ) from exc
        except Exception as exc:
            raise _fail(
                "first-party WSS close observation failed after LOGOUT",
                config,
            ) from exc
        if not isinstance(observation, tuple) or len(observation) != 2 or not isinstance(observation[0], int):
            raise _fail(
                "first-party WSS close observation returned a malformed frame",
                config,
            )
        received_messages += 1
        frame_count, byte_count = _receive_usage(ws, observation[1])
        received_frames += frame_count
        received_bytes += byte_count
        if received_frames > MAX_WEBSOCKET_WAIT_FRAMES or received_bytes > MAX_WEBSOCKET_WAIT_BYTES:
            raise _fail("WebSocket receive budget exceeded while waiting for close", config)
        if observation[0] == WEBSOCKET_CLOSE_OPCODE:
            return

    raise _fail(
        "first-party WSS session did not receive a WebSocket close opcode after LOGOUT",
        config,
    )


def _run_gameplay_session(
    config: SmokeConfig,
    websocket_factory: WebSocketFactory,
    cookie: str,
    character: str | None,
    *,
    logout: bool | None = None,
) -> dict[str, str]:
    try:
        ws = websocket_factory(
            config.websocket_url,
            config.timeout_seconds,
            [f"Cookie: {cookie}", f"Origin: {config.origin}"],
        )
    except Exception as exc:
        raise _fail("first-party WSS connection failed", config) from exc
    perform_logout = config.exercise_logout if logout is None else logout
    look_room: tuple[str, str] | None = None
    try:
        for command in (
            "LOGIN",
            f"PLAY {config.world} {config.realm} {character}" if character else f"PLAY {config.world} {config.realm}",
            "LOOK",
        ):
            ws.send(command)
            response = _await_command_result(ws, command.split(" ", 1)[0], config)
            if command == "LOOK":
                look_room = _require_look_view(response, config)
        if perform_logout:
            ws.send("LOGOUT")
            _await_command_result(ws, "LOGOUT", config)
            # Game Session closes the first-party transport after the accepted
            # LOGOUT result.  A timeout or unrelated receive error is not proof.
            _await_websocket_close(ws, config)
    finally:
        close = getattr(ws, "close", None)
        if callable(close):
            close()
    if look_room is None:
        raise _fail("LOOK did not produce an authoritative room view", config)
    return {"lookRoomId": look_room[0], "lookRoomName": look_room[1]}


def _require_replayed_cookie_rejected(
    config: SmokeConfig,
    websocket_factory: WebSocketFactory,
    cookie: str,
) -> None:
    """Require Gateway to reject reuse of the consumed one-use cookie."""
    try:
        replay_socket = websocket_factory(
            config.websocket_url,
            config.timeout_seconds,
            [f"Cookie: {cookie}", f"Origin: {config.origin}"],
        )
    except Exception as exc:
        # websocket-client exposes the failed HTTP upgrade as
        # WebSocketBadStatusException.status_code/resp_headers. Never render
        # the exception itself: its message can contain request details.
        status_code = getattr(exc, "status_code", None)
        response_headers = getattr(exc, "resp_headers", None)
        error_class = (
            _header(response_headers, "X-Firemud-Handshake-Error-Class")
            if isinstance(response_headers, Mapping)
            else ""
        )
        if status_code == 403 and error_class == "CONNECT_TOKEN_REPLAYED":
            return
        raise _fail(
            "replayed connect-token handshake was not rejected as CONNECT_TOKEN_REPLAYED",
            config,
        ) from exc
    close = getattr(replay_socket, "close", None)
    if callable(close):
        try:
            close()
        except Exception as exc:
            raise _fail("replayed connect-token handshake was accepted", config) from exc
    raise _fail("replayed connect-token handshake was accepted", config)


def _check_readiness(config: SmokeConfig, http_request: HttpRequest) -> None:
    if not config.readiness_url:
        return
    response = _request(http_request, config, "GET", config.readiness_url)
    payload = _require_success(response, "gateway readiness", config)
    if not isinstance(payload, dict) or payload.get("status") != "UP":
        raise _fail("gateway readiness returned a malformed or non-UP response", config)


def run_smoke(
    config: SmokeConfig,
    *,
    http_request: HttpRequest = _default_http_request,
    websocket_factory: WebSocketFactory | None = None,
) -> dict[str, Any]:
    """Run the diagnostic flow; injected transports keep tests offline."""
    _validate_origin(config)
    _check_readiness(config, http_request)
    if websocket_factory is None:
        try:
            import websocket
        except ImportError as exc:
            raise _fail("the websocket-client package is required for WSS smoke", config) from exc
        bounded_websocket = _bounded_websocket_client(websocket)

        def websocket_factory(url: str, timeout: float, headers: Sequence[str]) -> Any:
            # websocket-client emits its own Origin unless the dedicated
            # option is set. Keep Origin out of the raw header list so the
            # handshake contains exactly one operator-supplied Origin.
            return websocket.create_connection(
                url,
                timeout=timeout,
                class_=bounded_websocket,
                header=[header for header in headers if not header.lower().startswith("origin:")],
                origin=config.origin,
            )

    bootstrap_token = _bootstrap(config, http_request)
    sessions: list[dict[str, str]] = []
    cookie, character = _connect_context(
        config,
        http_request,
        bootstrap_token,
        f"hosted-websocket-playable-smoke-{uuid.uuid4()}",
    )
    sessions.append(
        _run_gameplay_session(
            config,
            websocket_factory,
            cookie,
            character,
            logout=False if config.exercise_reconnect else None,
        )
    )
    if config.exercise_reconnect:
        _require_replayed_cookie_rejected(config, websocket_factory, cookie)
        second_bootstrap = _bootstrap(config, http_request)
        cookie, second_character = _connect_context(
            config,
            http_request,
            second_bootstrap,
            f"hosted-websocket-playable-reconnect-{uuid.uuid4()}",
        )
        sessions.append(
            _run_gameplay_session(
                config,
                websocket_factory,
                cookie,
                second_character,
                logout=config.exercise_logout,
            )
        )
    if config.revoke_url:
        response = _request(
            http_request,
            config,
            "POST",
            config.revoke_url,
            headers={
                "Cookie": cookie,
                "Origin": config.origin,
                "Content-Type": "application/json",
            },
        )
        _require_success(response, "connect-token revocation", config)
    return {
        "transport": "first-party-wss",
        "classification": "diagnostic-operator-smoke",
        "steps": ["LOGIN", "PLAY", "LOOK"],
        "sessions": len(sessions),
        "logout": config.exercise_logout,
        "reconnect": config.exercise_reconnect,
        "replayRejected": config.exercise_reconnect,
        "lookRoomId": sessions[-1]["lookRoomId"],
        "lookRoomName": sessions[-1]["lookRoomName"],
    }


def _config_from_args(args: argparse.Namespace) -> SmokeConfig:
    gateway = args.gateway_base.rstrip("/")
    auth_base = args.auth_base or gateway
    websocket_url = args.websocket_url or (
        gateway.replace("https://", "wss://", 1).replace("http://", "ws://", 1) + "/ws/game"
    )
    return SmokeConfig(
        auth_api_base=auth_base,
        websocket_url=websocket_url,
        origin=args.origin,
        auth_api_prefix=args.auth_prefix,
        readiness_url=args.readiness_url,
        revoke_url=args.revoke_url,
        username=args.username,
        password=args.password,
        world=args.world,
        realm=args.realm,
        character=args.character,
        expected_room_id=args.expected_room_id,
        timeout_seconds=args.timeout,
        exercise_logout=args.logout,
        exercise_reconnect=args.reconnect,
    )


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--gateway-base", default=os.environ.get("SMOKE_GATEWAY_API_BASE", DEFAULT_GATEWAY_BASE))
    parser.add_argument("--auth-base", default=os.environ.get("PLAYER_EXPERIENCE_AUTH_API_BASE"))
    parser.add_argument(
        "--auth-prefix", default=os.environ.get("PLAYER_EXPERIENCE_AUTH_API_PREFIX", DEFAULT_AUTH_PREFIX)
    )
    parser.add_argument("--websocket-url", default=os.environ.get("PLAYER_EXPERIENCE_WEBSOCKET_URL"))
    parser.add_argument(
        "--origin",
        default=os.environ.get(
            "SMOKE_FIRST_PARTY_ORIGIN",
            os.environ.get("PLAYER_EXPERIENCE_ORIGIN", ""),
        ),
        help="Exact allowlisted first-party Origin sent on the WSS upgrade",
    )
    parser.add_argument("--readiness-url", default=os.environ.get("SMOKE_GATEWAY_READINESS_URL"))
    parser.add_argument("--revoke-url", default=os.environ.get("SMOKE_GATEWAY_CONNECT_TOKEN_REVOKE_URL"))
    parser.add_argument("--username", default=os.environ.get("SMOKE_USERNAME", DEFAULT_USERNAME))
    parser.add_argument("--password", default=os.environ.get("SMOKE_PASSWORD", DEFAULT_PASSWORD))
    parser.add_argument("--world", default=os.environ.get("PLAYER_EXPERIENCE_WORLD", DEFAULT_WORLD))
    parser.add_argument("--realm", default=os.environ.get("PLAYER_EXPERIENCE_REALM", DEFAULT_REALM))
    parser.add_argument("--character", default=os.environ.get("PLAYER_EXPERIENCE_CHARACTER"))
    parser.add_argument(
        "--expected-room-id",
        default=os.environ.get("SMOKE_EXPECTED_ROOM_ID", os.environ.get("PLAYER_EXPERIENCE_EXPECTED_ROOM_ID")),
        help="Optional room ID from the trusted Telnet probe for live parity",
    )
    parser.add_argument("--timeout", type=float, default=float(os.environ.get("SMOKE_TIMEOUT_SECONDS", "10")))
    parser.add_argument(
        "--logout",
        action="store_true",
        help="Opt into LOGOUT diagnostics (currently expected to fail with LOGOUT_UNAVAILABLE)",
    )
    parser.add_argument("--reconnect", action="store_true")
    args = parser.parse_args(argv)
    config = _config_from_args(args)
    try:
        result = run_smoke(config)
    except HostedWebSocketPlayableSmokeError as exc:
        print(
            f"Hosted first-party WSS smoke failed: {redact_credentials(exc, config.username, config.password)}",
            file=sys.stderr,
        )
        return 1
    print(json.dumps(result, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
