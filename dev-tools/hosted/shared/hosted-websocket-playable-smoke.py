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
from dataclasses import dataclass
from typing import Any, Callable, Mapping, Sequence


DEFAULT_USERNAME = "demo@example.com"
DEFAULT_PASSWORD = "swordfish"
DEFAULT_WORLD = "demo"
DEFAULT_REALM = "production"
DEFAULT_AUTH_PREFIX = "/api/account"
DEFAULT_GATEWAY_BASE = "http://localhost:8080"


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
    exercise_logout: bool = True
    exercise_reconnect: bool = False


@dataclass(frozen=True)
class HttpResponse:
    status: int
    headers: Mapping[str, str]
    body: Any


HttpRequest = Callable[[str, str, Any, Mapping[str, str], float], HttpResponse]
WebSocketFactory = Callable[[str, float, Sequence[str]], Any]


def redact_credentials(value: Any, username: str, password: str) -> str:
    text = str(value)
    for secret in (password, username):
        if secret:
            text = text.replace(secret, "<redacted>")
    return text


def _fail(message: str, config: SmokeConfig) -> HostedWebSocketPlayableSmokeError:
    return HostedWebSocketPlayableSmokeError(
        redact_credentials(message, config.username, config.password)
    )


def _join_url(base: str, path: str) -> str:
    return base.rstrip("/") + "/" + path.lstrip("/")


def _quote(value: str) -> str:
    return urllib.parse.quote(value, safe="")


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


def _default_http_request(
    method: str,
    url: str,
    payload: Any,
    headers: Mapping[str, str],
    timeout: float,
) -> HttpResponse:
    body = None if payload is None else json.dumps(payload).encode("utf-8")
    request = urllib.request.Request(url, data=body, method=method, headers=dict(headers))
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            return HttpResponse(
                response.status,
                dict(response.headers.items()),
                response.read(),
            )
    except urllib.error.HTTPError as exc:
        return HttpResponse(exc.code, dict(exc.headers.items()), exc.read())
    except (OSError, urllib.error.URLError) as exc:
        raise HostedWebSocketPlayableSmokeError(
            f"{method} {url} failed: {exc.__class__.__name__}"
        ) from exc


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

    realms_url = _auth_url(
        config, f"/auth/bootstrap/worlds/{_quote(config.world)}/realms"
    )
    realms = _require_envelope(
        _request(http_request, config, "GET", realms_url, headers=headers),
        "bootstrap realms",
        config,
    )
    realm = next(
        (
            candidate
            for candidate in realms
            if isinstance(candidate, dict) and candidate.get("realmSlug") == config.realm
        ),
        None,
    ) if isinstance(realms, list) else None
    if not isinstance(realm, dict) or not isinstance(realm.get("connectScopeId"), str):
        raise _fail(f"realm {config.realm!r} was not visible during bootstrap discovery", config)
    connect_scope_id = realm["connectScopeId"]
    if not connect_scope_id:
        raise _fail("bootstrap realm returned an empty connect scope", config)

    query = urllib.parse.urlencode({"connectScopeId": connect_scope_id})
    characters_url = _auth_url(
        config,
        f"/auth/bootstrap/worlds/{_quote(config.world)}/realms/"
        f"{_quote(config.realm)}/characters?{query}",
    )
    characters = _require_envelope(
        _request(http_request, config, "GET", characters_url, headers=headers),
        "bootstrap characters",
        config,
    )
    if not isinstance(characters, list):
        raise _fail("bootstrap characters returned a malformed list", config)
    if config.character:
        if not any(
            isinstance(candidate, dict) and candidate.get("characterName") == config.character
            for candidate in characters
        ):
            raise _fail(f"character {config.character!r} was not visible during bootstrap discovery", config)
        return connect_scope_id, config.character
    if not characters:
        return connect_scope_id, None
    first = characters[0]
    if not isinstance(first, dict):
        raise _fail("bootstrap characters returned a malformed entry", config)
    character = first.get("characterName")
    if character is not None and not isinstance(character, str):
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


def _await_command_result(ws: Any, command_type: str, config: SmokeConfig) -> dict[str, Any]:
    deadline = time.monotonic() + config.timeout_seconds
    while time.monotonic() < deadline:
        ws.settimeout(max(0.01, deadline - time.monotonic()))
        try:
            payload = ws.recv()
        except Exception as exc:
            raise _fail(f"WebSocket closed while waiting for {command_type}", config) from exc
        try:
            parsed = json.loads(payload)
        except (TypeError, json.JSONDecodeError):
            continue
        if not isinstance(parsed, dict):
            continue
        if parsed.get("eventType") != "command_result" or parsed.get("commandType") != command_type:
            continue
        if not parsed.get("accepted", False):
            raise _fail(f"{command_type} was rejected by the first-party gameplay session", config)
        return parsed
    raise _fail(f"timed out waiting for structured {command_type} result", config)


def _require_look_view(
    response: dict[str, Any], config: SmokeConfig
) -> tuple[str, str]:
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
        if not (
            isinstance(room_id, str)
            and room_id.strip()
            and isinstance(room_name, str)
            and room_name.strip()
        ):
            continue
        if config.expected_room_id is not None and room_id != config.expected_room_id:
            raise _fail("LOOK room ID did not match the expected Telnet parity room ID", config)
        return room_id, room_name
    raise _fail("LOOK accepted without an authoritative LOOK view", config)


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
            # LOGOUT result.  A recv exception is the normal close observation.
            deadline = time.monotonic() + config.timeout_seconds
            while time.monotonic() < deadline:
                ws.settimeout(max(0.01, deadline - time.monotonic()))
                try:
                    ws.recv()
                except Exception:
                    break
            else:
                raise _fail("first-party WSS session did not close after LOGOUT", config)
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

        def websocket_factory(url: str, timeout: float, headers: Sequence[str]) -> Any:
            # websocket-client emits its own Origin unless the dedicated
            # option is set. Keep Origin out of the raw header list so the
            # handshake contains exactly one operator-supplied Origin.
            return websocket.create_connection(
                url,
                timeout=timeout,
                header=[
                    header
                    for header in headers
                    if not header.lower().startswith("origin:")
                ],
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
        gateway.replace("https://", "wss://", 1).replace("http://", "ws://", 1)
        + "/ws/game"
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
        exercise_logout=not args.no_logout,
        exercise_reconnect=args.reconnect,
    )


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--gateway-base", default=os.environ.get("SMOKE_GATEWAY_API_BASE", DEFAULT_GATEWAY_BASE))
    parser.add_argument("--auth-base", default=os.environ.get("PLAYER_EXPERIENCE_AUTH_API_BASE"))
    parser.add_argument("--auth-prefix", default=os.environ.get("PLAYER_EXPERIENCE_AUTH_API_PREFIX", DEFAULT_AUTH_PREFIX))
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
    parser.add_argument("--no-logout", action="store_true")
    parser.add_argument("--reconnect", action="store_true")
    args = parser.parse_args(argv)
    config = _config_from_args(args)
    try:
        result = run_smoke(config)
    except HostedWebSocketPlayableSmokeError as exc:
        print(f"Hosted first-party WSS smoke failed: {redact_credentials(exc, config.username, config.password)}", file=sys.stderr)
        return 1
    print(json.dumps(result, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
