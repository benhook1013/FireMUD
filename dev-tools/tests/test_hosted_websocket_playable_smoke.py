import importlib.util
import json
import pathlib
import sys
import unittest
from collections import deque


HELPER = pathlib.Path(__file__).parents[1] / "hosted/shared/hosted-websocket-playable-smoke.py"
SPEC = importlib.util.spec_from_file_location("hosted_websocket_playable_smoke", HELPER)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
sys.modules[SPEC.name] = MODULE
SPEC.loader.exec_module(MODULE)


class FakeHttp:
    def __init__(self, *, cookie="Firemud-Connect-Token=token-1", readiness=None):
        self.calls = []
        self.cookie = cookie
        self.readiness = readiness
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
            return MODULE.HttpResponse(
                200, {}, {"data": [{"characterName": "Ada"}]}
            )
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
            response["outputs"] = [
                {"payloadType": "look_view", "payload": self.look_payload}
            ]
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

    def test_first_party_cookie_bootstrap_and_login_play_look_logout(self):
        http = FakeHttp()
        sockets = []

        def socket_factory(url, timeout, headers):
            sockets.append((url, timeout, list(headers)))
            ws = FakeWebSocket()
            sockets.append(ws)
            return ws

        result = MODULE.run_smoke(
            self.config(), http_request=http, websocket_factory=socket_factory
        )

        self.assertEqual(result["steps"], ["LOGIN", "PLAY", "LOOK"])
        self.assertTrue(result["logout"])
        self.assertEqual(result["lookRoomId"], "R-1021")
        self.assertEqual(result["lookRoomName"], "Candle-lit Antechamber")
        self.assertNotIn("outputs", result)
        ws = sockets[1]
        self.assertEqual(ws.commands, ["LOGIN", "PLAY demo production Ada", "LOOK", "LOGOUT"])
        self.assertTrue(ws.close_observed)
        self.assertEqual(
            sockets[0][2],
            [
                "Cookie: Firemud-Connect-Token=token-1",
                "Origin: https://frontend.preview.example",
            ],
        )
        self.assertEqual(http.calls[0][2], {
            "accountIdentifier": "operator@example.com",
            "secret": "do-not-print-this",
        })

    def test_reconnect_uses_a_fresh_connect_cookie(self):
        http = FakeHttp()
        attempts = []
        sockets = []

        def socket_factory(url, timeout, headers):
            attempts.append(list(headers))
            if len(attempts) == 2:
                self.assertTrue(sockets[0].closed)
                raise FakeHandshakeRejected()
            sockets.append(FakeWebSocket())
            return sockets[-1]

        result = MODULE.run_smoke(
            self.config(exercise_reconnect=True),
            http_request=http,
            websocket_factory=socket_factory,
        )

        self.assertEqual(result["sessions"], 2)
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
            ["LOGIN", "PLAY demo production Ada", "LOOK", "LOGOUT"],
        )
        self.assertTrue(result["replayRejected"])

    def test_reconnect_replay_check_fails_closed_for_non_replay_handshakes(self):
        for failure in (
            FakeHandshakeRejected(status_code=500),
            FakeHandshakeRejected(error_class="CONNECT_TOKEN_MISSING"),
            OSError("network failure"),
        ):
            with self.subTest(failure=type(failure).__name__):
                http = FakeHttp()
                accepted = []

                def socket_factory(url, timeout, headers):
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
            with self.subTest(origin=origin):
                with self.assertRaisesRegex(
                    MODULE.HostedWebSocketPlayableSmokeError, "first-party Origin"
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

        with self.assertRaisesRegex(
            MODULE.HostedWebSocketPlayableSmokeError, "WSS connection failed"
        ):
            MODULE.run_smoke(
                self.config(origin="https://wrong.preview.example"),
                http_request=http,
                websocket_factory=gateway_requiring_expected_origin,
            )

    def test_missing_cookie_fails_closed(self):
        http = FakeHttp(cookie="")
        with self.assertRaisesRegex(
            MODULE.HostedWebSocketPlayableSmokeError, "valid Firemud-Connect-Token"
        ):
            MODULE.run_smoke(self.config(), http_request=http, websocket_factory=FakeWebSocket)

    def test_wrong_cookie_fails_closed(self):
        http = FakeHttp(cookie="Other=wrong")
        with self.assertRaisesRegex(
            MODULE.HostedWebSocketPlayableSmokeError, "valid Firemud-Connect-Token"
        ):
            MODULE.run_smoke(self.config(), http_request=http, websocket_factory=FakeWebSocket)

    def test_readiness_rejects_non_2xx_and_malformed_payload(self):
        for readiness in (
            MODULE.HttpResponse(503, {}, {"status": "DOWN"}),
            MODULE.HttpResponse(200, {}, {"ready": True}),
        ):
            http = FakeHttp(readiness=readiness)
            with self.subTest(readiness=readiness):
                with self.assertRaises(MODULE.HostedWebSocketPlayableSmokeError):
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

        with self.assertRaisesRegex(
            MODULE.HostedWebSocketPlayableSmokeError, "close observation"
        ):
            MODULE.run_smoke(
                self.config(),
                http_request=http,
                websocket_factory=socket_factory,
            )
        self.assertFalse(sockets[0].close_observed)
        self.assertTrue(sockets[0].assert_control_frame)


if __name__ == "__main__":
    unittest.main()
