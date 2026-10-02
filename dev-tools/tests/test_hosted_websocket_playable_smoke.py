import contextlib
import http.server
import importlib.util
import io
import json
import pathlib
import sys
import threading
import unittest
from collections import deque
from unittest.mock import patch

HELPER = pathlib.Path(__file__).parents[1] / "hosted/shared/hosted-websocket-playable-smoke.py"
SPEC = importlib.util.spec_from_file_location("hosted_websocket_playable_smoke", HELPER)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
sys.modules[SPEC.name] = MODULE
SPEC.loader.exec_module(MODULE)


@contextlib.contextmanager
def local_http_server():
    requests = []
    small_json = b'{"ok":true}'
    exact_json = small_json + b" " * (MODULE.MAX_HTTP_RESPONSE_BYTES - len(small_json))

    class Handler(http.server.BaseHTTPRequestHandler):
        def _respond(self):
            request_body = self.rfile.read(int(self.headers.get("Content-Length", "0")))
            requests.append((self.command, self.path, dict(self.headers.items()), request_body))
            parsed = MODULE.urllib.parse.urlsplit(self.path)
            query = MODULE.urllib.parse.parse_qs(parsed.query)
            response_headers = {}
            response_body = b"ok"
            status = 200

            if parsed.path in {"/auth/bootstrap/worlds", "/auth/connect-token"}:
                status = int(query["redirect"][0])
                response_headers["Location"] = f"http://localhost:{self.server.server_port}/redirect-target"
                response_body = b"redirect response"
            elif parsed.path == "/success-small":
                response_body = small_json
            elif parsed.path == "/success-exact":
                response_body = exact_json
            elif parsed.path == "/success-over":
                response_body = b"do-not-print-this" + b"x" * MODULE.MAX_HTTP_RESPONSE_BYTES
            elif parsed.path == "/error-exact":
                status = 401
                response_body = b"e" * MODULE.MAX_HTTP_RESPONSE_BYTES
            elif parsed.path == "/error-over":
                status = 401
                response_body = b"do-not-print-this" + b"x" * MODULE.MAX_HTTP_RESPONSE_BYTES

            self.send_response(status)
            for name, value in response_headers.items():
                self.send_header(name, value)
            self.send_header("Content-Length", str(len(response_body)))
            self.end_headers()
            self.wfile.write(response_body)

        def do_GET(self):
            self._respond()

        def do_POST(self):
            self._respond()

        def log_message(self, format, *args):
            pass

    server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    try:
        yield f"http://127.0.0.1:{server.server_port}", requests
    finally:
        server.shutdown()
        thread.join()
        server.server_close()


class FakeHttp:
    def __init__(self, *, cookie="Firemud-Connect-Token=token-1", readiness=None, characters=None):
        self.calls = []
        self.cookie = cookie
        self.readiness = readiness
        self.characters = characters if characters is not None else [{"characterName": "Ada"}]
        self.connect_count = 0

    def __call__(self, method, url, payload, headers, timeout):
        self.calls.append((method, url, payload, dict(headers), timeout))
        if url.endswith("/ready"):
            return self.readiness
        if url.endswith("/auth/player-bootstrap"):
            return MODULE.HttpResponse(200, {}, {"data": {"bootstrapToken": "bootstrap"}})
        if url.endswith("/auth/bootstrap/worlds"):
            return MODULE.HttpResponse(200, {}, {"data": [{"worldSlug": "demo"}]})
        if url.endswith("/realms"):
            return MODULE.HttpResponse(
                200,
                {},
                {"data": [{"realmSlug": "production", "connectScopeId": "scope-1"}]},
            )
        if "/characters?" in url:
            return MODULE.HttpResponse(200, {}, {"data": self.characters})
        if url.endswith("/auth/connect-token"):
            self.connect_count += 1
            cookie = self.cookie
            if self.connect_count > 1 and cookie == "Firemud-Connect-Token=token-1":
                cookie = f"Firemud-Connect-Token=token-{self.connect_count}"
            return MODULE.HttpResponse(200, {"Set-Cookie": cookie + "; Path=/ws/game"}, {"data": {}})
        if url.endswith("/revoke"):
            return MODULE.HttpResponse(200, {}, {"status": "success", "data": {}})
        raise AssertionError(f"unexpected fake HTTP request: {method} {url}")


DEFAULT_LOOK_PAYLOAD = object()


class FakeWebSocket:
    def __init__(self, *, look_payload=DEFAULT_LOOK_PAYLOAD):
        self.commands = []
        self.responses = deque()
        self.closed = False
        self.close_observed = False
        self.look_payload = (
            {
                "roomId": "R-1021",
                "roomName": "Candle-lit Antechamber",
            }
            if look_payload is DEFAULT_LOOK_PAYLOAD
            else look_payload
        )

    def settimeout(self, timeout):
        self.timeout = timeout

    def send(self, command):
        self.commands.append(command)
        command_type = command.split(" ", 1)[0]
        response = {
            "eventType": "command_result",
            "commandType": command_type,
            "accepted": True,
        }
        if command_type == "LOOK" and self.look_payload is not None:
            response["outputs"] = [{"payloadType": "look_view", "payload": self.look_payload}]
        self.responses.append(json.dumps(response))

    def recv(self):
        if self.responses:
            return self.responses.popleft()
        raise RuntimeError("fake server closed")

    def recv_data(self, *, control_frame=False):
        if not control_frame:
            raise AssertionError("close observation must request control frames")
        if self.commands and self.commands[-1] == "LOGOUT":
            self.close_observed = True
            self.closed = True
            return MODULE.WEBSOCKET_CLOSE_OPCODE, b"\x03\xe8"
        raise RuntimeError("fake server did not close")

    def close(self):
        self.closed = True


class LogoutUnavailableWebSocket(FakeWebSocket):
    def send(self, command):
        if command == "LOGOUT":
            self.commands.append(command)
            self.responses.append(
                json.dumps(
                    {
                        "eventType": "command_result",
                        "commandType": "LOGOUT",
                        "accepted": False,
                        "errorCode": "LOGOUT_UNAVAILABLE",
                    }
                )
            )
            return
        super().send(command)


class FakeHandshakeRejected(Exception):
    def __init__(self, status_code=403, error_class="CONNECT_TOKEN_REPLAYED"):
        super().__init__("replay response contains a token that must not be logged")
        self.status_code = status_code
        self.resp_headers = {"X-Firemud-Handshake-Error-Class": error_class}


class HostedWebSocketPlayableSmokeTests(unittest.TestCase):
    def config(self, **overrides):
        values = {
            "auth_api_base": "https://preview.example",
            "websocket_url": "wss://preview.example/ws/game",
            "origin": "https://frontend.preview.example",
            "username": "operator@example.com",
            "password": "do-not-print-this",
        }
        values.update(overrides)
        return MODULE.SmokeConfig(**values)

    def test_default_http_transport_rejects_cross_origin_redirects_for_get_and_post(self):
        with local_http_server() as (base_url, requests):
            for status in (301, 302, 303):
                get_response = MODULE._default_http_request(
                    "GET",
                    f"{base_url}/auth/bootstrap/worlds?redirect={status}",
                    None,
                    {"Authorization": "Bearer bootstrap-secret"},
                    2.0,
                )
                self.assertEqual(get_response.status, status)
                with self.assertRaisesRegex(
                    MODULE.HostedWebSocketPlayableSmokeError,
                    f"returned HTTP {status}",
                ):
                    MODULE._require_success(get_response, "bootstrap worlds", self.config())

                post_response = MODULE._default_http_request(
                    "POST",
                    f"{base_url}/auth/connect-token?redirect={status}",
                    {"connectScopeId": "scope-1", "requestId": "request-1"},
                    {
                        "Authorization": "Bearer bootstrap-secret",
                        "Content-Type": "application/json",
                    },
                    2.0,
                )
                self.assertEqual(post_response.status, status)
                with self.assertRaisesRegex(
                    MODULE.HostedWebSocketPlayableSmokeError,
                    f"returned HTTP {status}",
                ):
                    MODULE._require_success(post_response, "connect-token issuance", self.config())

        self.assertEqual(len(requests), 6)
        self.assertTrue(all("redirect-target" not in request[1] for request in requests))
        get_requests = [request for request in requests if request[0] == "GET"]
        post_requests = [request for request in requests if request[0] == "POST"]
        self.assertEqual(len(get_requests), 3)
        self.assertEqual(len(post_requests), 3)
        self.assertTrue(all(request[2].get("Authorization") == "Bearer bootstrap-secret" for request in requests))
        self.assertTrue(
            all(
                json.loads(request[3]) == {"connectScopeId": "scope-1", "requestId": "request-1"}
                for request in post_requests
            )
        )

    def test_default_http_redirect_handler_rejects_https_downgrade_without_post_conversion(self):
        handler = MODULE._RejectRedirectHandler()
        for status in (301, 302, 303):
            with self.subTest(status=status):
                request = MODULE.urllib.request.Request(
                    "https://preview.example/api/account/auth/connect-token",
                    data=b'{"connectScopeId":"scope-1"}',
                    method="POST",
                    headers={"Authorization": "Bearer bootstrap-secret"},
                )
                redirected = handler.redirect_request(
                    request,
                    None,
                    status,
                    "Found",
                    {},
                    "http://attacker.example/connect-token",
                )
                self.assertIsNone(redirected)
                self.assertEqual(request.full_url, "https://preview.example/api/account/auth/connect-token")
                self.assertEqual(request.get_method(), "POST")
                self.assertEqual(request.data, b'{"connectScopeId":"scope-1"}')

    def test_default_http_transport_accepts_in_limit_and_exact_limit_success_bodies(self):
        with local_http_server() as (base_url, _):
            for path in ("/success-small", "/success-exact"):
                with self.subTest(path=path):
                    response = MODULE._default_http_request("GET", base_url + path, None, {}, 2.0)
                    self.assertEqual(MODULE._require_success(response, "local probe", self.config()), {"ok": True})
                    if path == "/success-exact":
                        self.assertEqual(len(response.body), MODULE.MAX_HTTP_RESPONSE_BYTES)

    def test_default_http_transport_bounds_oversized_success_and_http_error_bodies(self):
        secret = "do-not-print-this"
        with local_http_server() as (base_url, _):
            with self.assertRaisesRegex(
                MODULE.HostedWebSocketPlayableSmokeError,
                rf"HTTP 200.*{MODULE.MAX_HTTP_RESPONSE_BYTES} bytes",
            ) as caught:
                MODULE._default_http_request(
                    "GET",
                    base_url + "/success-over?token=" + secret,
                    None,
                    {"Authorization": "Bearer bootstrap-secret"},
                    2.0,
                )
            self.assertNotIn(secret, str(caught.exception))
            self.assertNotIn("bootstrap-secret", str(caught.exception))

            error_response = MODULE._default_http_request("GET", base_url + "/error-over", None, {}, 2.0)
            self.assertEqual(error_response.status, 401)
            self.assertEqual(len(error_response.body), MODULE.MAX_HTTP_RESPONSE_BYTES)
            with self.assertRaisesRegex(
                MODULE.HostedWebSocketPlayableSmokeError,
                "returned HTTP 401",
            ) as caught:
                MODULE._require_success(error_response, "local probe", self.config())
            self.assertNotIn(secret, str(caught.exception))

            exact_error_response = MODULE._default_http_request("GET", base_url + "/error-exact", None, {}, 2.0)
            self.assertEqual(exact_error_response.status, 401)
            self.assertEqual(len(exact_error_response.body), MODULE.MAX_HTTP_RESPONSE_BYTES)

    def test_default_http_transport_closes_http_error_responses(self):
        url = "https://preview.example/auth/connect-token"
        response_body = io.BytesIO(b"error response")
        error = MODULE.urllib.error.HTTPError(url, 401, "Unauthorized", {}, response_body)

        class ErrorOpener:
            def open(self, request, timeout):
                raise error

        with patch.object(MODULE.urllib.request, "build_opener", return_value=ErrorOpener()):
            response = MODULE._default_http_request("POST", url, {}, {}, 2.0)

        self.assertEqual(response.status, 401)
        self.assertTrue(response_body.closed)

    def test_default_first_party_cookie_bootstrap_runs_read_only_baseline(self):
        http = FakeHttp()
        sockets = []

        def socket_factory(url, timeout, headers):
            sockets.append((url, timeout, list(headers)))
            ws = LogoutUnavailableWebSocket()
            sockets.append(ws)
            return ws

        result = MODULE.run_smoke(self.config(), http_request=http, websocket_factory=socket_factory)

        self.assertEqual(result["steps"], ["LOGIN", "PLAY", "LOOK"])
        self.assertFalse(result["logout"])
        self.assertEqual(result["lookRoomId"], "R-1021")
        self.assertEqual(result["lookRoomName"], "Candle-lit Antechamber")
        self.assertNotIn("outputs", result)
        ws = sockets[1]
        self.assertEqual(ws.commands, ["LOGIN", "PLAY demo production Ada", "LOOK"])
        self.assertFalse(ws.close_observed)
        self.assertEqual(
            sockets[0][2],
            [
                "Cookie: Firemud-Connect-Token=token-1",
                "Origin: https://frontend.preview.example",
            ],
        )
        self.assertEqual(
            http.calls[0][2],
            {
                "accountIdentifier": "operator@example.com",
                "secret": "do-not-print-this",
            },
        )

    def test_ambiguous_character_discovery_fails_before_connect_token(self):
        http = FakeHttp(characters=[{"characterName": "Ada"}, {"characterName": "Bea"}])

        with self.assertRaisesRegex(
            MODULE.HostedWebSocketPlayableSmokeError,
            "multiple characters.*explicit character",
        ):
            MODULE.run_smoke(
                self.config(),
                http_request=http,
                websocket_factory=FakeWebSocket,
            )

        self.assertEqual(http.connect_count, 0)

    def test_default_reconnect_uses_fresh_cookie_and_rejects_replay_without_logout(self):
        http = FakeHttp()
        attempts = []
        sockets = []

        def socket_factory(url, timeout, headers):
            attempts.append(list(headers))
            if len(attempts) == 2:
                self.assertTrue(sockets[0].closed)
                raise FakeHandshakeRejected()
            sockets.append(LogoutUnavailableWebSocket())
            return sockets[-1]

        result = MODULE.run_smoke(
            self.config(exercise_reconnect=True),
            http_request=http,
            websocket_factory=socket_factory,
        )

        self.assertEqual(result["sessions"], 2)
        self.assertFalse(result["logout"])
        self.assertTrue(result["replayRejected"])
        self.assertEqual(
            attempts,
            [
                [
                    "Cookie: Firemud-Connect-Token=token-1",
                    "Origin: https://frontend.preview.example",
                ],
                [
                    "Cookie: Firemud-Connect-Token=token-1",
                    "Origin: https://frontend.preview.example",
                ],
                [
                    "Cookie: Firemud-Connect-Token=token-2",
                    "Origin: https://frontend.preview.example",
                ],
            ],
        )
        self.assertEqual(
            sockets[0].commands,
            ["LOGIN", "PLAY demo production Ada", "LOOK"],
        )
        self.assertEqual(
            sockets[1].commands,
            ["LOGIN", "PLAY demo production Ada", "LOOK"],
        )
        self.assertTrue(result["replayRejected"])

    def test_cli_defaults_to_read_only_and_logout_flag_opts_in(self):
        result = {
            "transport": "first-party-wss",
            "classification": "diagnostic-operator-smoke",
            "steps": ["LOGIN", "PLAY", "LOOK"],
            "sessions": 1,
            "logout": False,
            "reconnect": False,
            "replayRejected": False,
            "lookRoomId": "R-1021",
            "lookRoomName": "Candle-lit Antechamber",
        }
        for extra_args, expected_logout in (([], False), (["--logout"], True)):
            with self.subTest(extra_args=extra_args):
                output = io.StringIO()
                with (
                    patch.object(MODULE, "run_smoke", return_value=result) as run_smoke,
                    contextlib.redirect_stdout(output),
                ):
                    self.assertEqual(
                        MODULE.main(["--origin", "https://frontend.preview.example", *extra_args]),
                        0,
                    )
                self.assertEqual(run_smoke.call_args.args[0].exercise_logout, expected_logout)

    def test_trusted_cli_pins_destinations_over_ambient_configuration(self):
        http = FakeHttp()
        socket_attempts = []

        def socket_factory(url, timeout, headers):
            socket_attempts.append((url, list(headers)))
            if len(socket_attempts) == 2:
                raise FakeHandshakeRejected()
            return FakeWebSocket()

        actual_run_smoke = MODULE.run_smoke
        observed_configs = []

        def run_with_fakes(config):
            observed_configs.append(config)
            return actual_run_smoke(
                config,
                http_request=http,
                websocket_factory=socket_factory,
            )

        hostile_environment = {
            "PLAYER_EXPERIENCE_AUTH_API_BASE": "https://attacker.invalid",
            "PLAYER_EXPERIENCE_AUTH_API_PREFIX": "/attacker-account",
            "SMOKE_GATEWAY_READINESS_URL": "https://attacker.invalid/ready",
            "SMOKE_GATEWAY_CONNECT_TOKEN_REVOKE_URL": "https://attacker.invalid/revoke",
        }
        output = io.StringIO()
        with (
            patch.dict(MODULE.os.environ, hostile_environment),
            patch.object(MODULE, "run_smoke", side_effect=run_with_fakes),
            contextlib.redirect_stdout(output),
        ):
            self.assertEqual(
                MODULE.main(
                    [
                        "--gateway-base",
                        "https://preview.example",
                        "--auth-base",
                        "https://preview.example",
                        "--auth-prefix",
                        "/api/account",
                        "--websocket-url",
                        "wss://preview.example/ws/game",
                        "--origin",
                        "https://preview.example",
                        "--readiness-url",
                        "",
                        "--revoke-url",
                        "",
                        "--username",
                        "operator@example.com",
                        "--password",
                        "do-not-print-this",
                        "--world",
                        "demo",
                        "--realm",
                        "production",
                        "--expected-room-id",
                        "R-1021",
                        "--reconnect",
                    ]
                ),
                0,
            )

        self.assertEqual(len(observed_configs), 1)
        config = observed_configs[0]
        self.assertEqual(config.auth_api_base, "https://preview.example")
        self.assertEqual(config.auth_api_prefix, "/api/account")
        self.assertEqual(config.readiness_url, "")
        self.assertEqual(config.revoke_url, "")
        self.assertEqual(config.websocket_url, "wss://preview.example/ws/game")
        self.assertEqual(config.origin, "https://preview.example")
        self.assertTrue(config.exercise_reconnect)
        self.assertEqual(len(socket_attempts), 3)
        self.assertTrue(all(url == "wss://preview.example/ws/game" for url, _ in socket_attempts))
        self.assertTrue(
            all(
                any(header.startswith("Cookie: Firemud-Connect-Token=") for header in headers)
                for _, headers in socket_attempts
            )
        )
        self.assertTrue(all(url.startswith("https://preview.example/") for _, url, _, _, _ in http.calls))
        credential_calls = [call for call in http.calls if "secret" in (call[2] or {}) or "Authorization" in call[3]]
        self.assertTrue(credential_calls)
        self.assertTrue(all(call[1].startswith("https://preview.example/") for call in credential_calls))
        self.assertFalse(any(url.endswith(("/ready", "/revoke")) for _, url, _, _, _ in http.calls))
        self.assertNotIn("do-not-print-this", output.getvalue())

    def test_explicit_logout_opt_in_fails_when_logout_is_unavailable(self):
        with self.assertRaisesRegex(
            MODULE.HostedWebSocketPlayableSmokeError,
            "LOGOUT was rejected",
        ):
            MODULE.run_smoke(
                self.config(exercise_logout=True),
                http_request=FakeHttp(),
                websocket_factory=lambda url, timeout, headers: LogoutUnavailableWebSocket(),
            )

    def test_explicit_logout_opt_in_requires_and_observes_close_frame(self):
        http = FakeHttp()
        sockets = []

        def socket_factory(url, timeout, headers):
            socket = FakeWebSocket()
            sockets.append(socket)
            return socket

        result = MODULE.run_smoke(
            self.config(exercise_logout=True),
            http_request=http,
            websocket_factory=socket_factory,
        )

        self.assertTrue(result["logout"])
        self.assertEqual(
            sockets[0].commands,
            ["LOGIN", "PLAY demo production Ada", "LOOK", "LOGOUT"],
        )
        self.assertTrue(sockets[0].close_observed)

    def test_reconnect_replay_check_fails_closed_for_non_replay_handshakes(self):
        for failure in (
            FakeHandshakeRejected(status_code=500),
            FakeHandshakeRejected(error_class="CONNECT_TOKEN_MISSING"),
            OSError("network failure"),
        ):
            with self.subTest(failure=type(failure).__name__):
                http = FakeHttp()
                accepted = []

                def socket_factory(url, timeout, headers, *, accepted=accepted, failure=failure):
                    if not accepted:
                        socket = FakeWebSocket()
                        accepted.append(socket)
                        return socket
                    raise failure

                with self.assertRaisesRegex(
                    MODULE.HostedWebSocketPlayableSmokeError,
                    "CONNECT_TOKEN_REPLAYED",
                ):
                    MODULE.run_smoke(
                        self.config(exercise_reconnect=True),
                        http_request=http,
                        websocket_factory=socket_factory,
                    )

    def test_reconnect_replay_check_rejects_an_accepted_upgrade(self):
        http = FakeHttp()
        sockets = []

        def socket_factory(url, timeout, headers):
            socket = FakeWebSocket()
            sockets.append(socket)
            return socket

        with self.assertRaisesRegex(
            MODULE.HostedWebSocketPlayableSmokeError,
            "replayed connect-token handshake was accepted",
        ):
            MODULE.run_smoke(
                self.config(exercise_reconnect=True),
                http_request=http,
                websocket_factory=socket_factory,
            )
        self.assertTrue(sockets[1].closed)

    def test_look_without_authoritative_view_fails_closed(self):
        http = FakeHttp()

        def socket_factory(url, timeout, headers):
            return FakeWebSocket(look_payload=None)

        with self.assertRaisesRegex(
            MODULE.HostedWebSocketPlayableSmokeError,
            "authoritative LOOK view",
        ):
            MODULE.run_smoke(
                self.config(),
                http_request=http,
                websocket_factory=socket_factory,
            )

    def test_look_room_id_must_match_optional_telnet_parity_id(self):
        http = FakeHttp()

        with self.assertRaisesRegex(
            MODULE.HostedWebSocketPlayableSmokeError,
            "expected Telnet parity room ID",
        ):
            MODULE.run_smoke(
                self.config(expected_room_id="R-other"),
                http_request=http,
                websocket_factory=lambda url, timeout, headers: FakeWebSocket(),
            )

    def test_missing_or_malformed_origin_fails_closed(self):
        for origin in (
            "",
            "frontend.preview.example",
            "https://frontend.preview.example/path",
        ):
            http = FakeHttp()
            with (
                self.subTest(origin=origin),
                self.assertRaisesRegex(MODULE.HostedWebSocketPlayableSmokeError, "first-party Origin"),
            ):
                MODULE.run_smoke(
                    self.config(origin=origin),
                    http_request=http,
                    websocket_factory=FakeWebSocket,
                )
            self.assertEqual(http.calls, [])

    def test_wrong_allowlisted_origin_is_not_treated_as_a_pass(self):
        http = FakeHttp()

        def gateway_requiring_expected_origin(url, timeout, headers):
            if "Origin: https://frontend.preview.example" not in headers:
                raise RuntimeError("origin rejected by gateway allowlist")
            return FakeWebSocket()

        with self.assertRaisesRegex(MODULE.HostedWebSocketPlayableSmokeError, "WSS connection failed"):
            MODULE.run_smoke(
                self.config(origin="https://wrong.preview.example"),
                http_request=http,
                websocket_factory=gateway_requiring_expected_origin,
            )

    def test_missing_cookie_fails_closed(self):
        http = FakeHttp(cookie="")
        with self.assertRaisesRegex(MODULE.HostedWebSocketPlayableSmokeError, "valid Firemud-Connect-Token"):
            MODULE.run_smoke(self.config(), http_request=http, websocket_factory=FakeWebSocket)

    def test_wrong_cookie_fails_closed(self):
        http = FakeHttp(cookie="Other=wrong")
        with self.assertRaisesRegex(MODULE.HostedWebSocketPlayableSmokeError, "valid Firemud-Connect-Token"):
            MODULE.run_smoke(self.config(), http_request=http, websocket_factory=FakeWebSocket)

    def test_readiness_rejects_non_2xx_and_malformed_payload(self):
        for readiness in (
            MODULE.HttpResponse(503, {}, {"status": "DOWN"}),
            MODULE.HttpResponse(200, {}, {"ready": True}),
        ):
            http = FakeHttp(readiness=readiness)
            with self.subTest(readiness=readiness), self.assertRaises(MODULE.HostedWebSocketPlayableSmokeError):
                MODULE.run_smoke(
                    self.config(readiness_url="https://preview.example/ready"),
                    http_request=http,
                    websocket_factory=FakeWebSocket,
                )
            self.assertEqual(len(http.calls), 1)

    def test_failure_does_not_echo_credentials(self):
        secret = "do-not-print-this"

        def failing_http(method, url, payload, headers, timeout):
            return MODULE.HttpResponse(401, {}, {"error": secret, "request": payload})

        with self.assertRaises(MODULE.HostedWebSocketPlayableSmokeError) as caught:
            MODULE.run_smoke(
                self.config(password=secret),
                http_request=failing_http,
                websocket_factory=FakeWebSocket,
            )
        self.assertNotIn(secret, str(caught.exception))

    def test_logout_silent_open_does_not_count_as_a_close(self):
        http = FakeHttp()
        sockets = []

        class SilentOpenWebSocket(FakeWebSocket):
            def recv_data(self, *, control_frame=False):
                self.assert_control_frame = control_frame
                raise TimeoutError("socket remains open")

        def socket_factory(url, timeout, headers):
            socket = SilentOpenWebSocket()
            sockets.append(socket)
            return socket

        with self.assertRaisesRegex(MODULE.HostedWebSocketPlayableSmokeError, "close observation"):
            MODULE.run_smoke(
                self.config(exercise_logout=True),
                http_request=http,
                websocket_factory=socket_factory,
            )
        self.assertFalse(sockets[0].close_observed)
        self.assertTrue(sockets[0].assert_control_frame)


if __name__ == "__main__":
    unittest.main()
