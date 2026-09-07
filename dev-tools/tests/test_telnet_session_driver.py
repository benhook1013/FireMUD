#!/usr/bin/env python3
"""Focused tests for the maintained gameplay Telnet session helper."""

import argparse
import contextlib
import importlib.util
import io
import json
import multiprocessing
import socket
import stat
import subprocess
import sys
import tempfile
import threading
import time
import unittest
from pathlib import Path
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
TOOL_PATH = ROOT / "dev-tools/gameplay/telnet-session.py"
SPEC = importlib.util.spec_from_file_location("telnet_session", TOOL_PATH)
telnet_session = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(telnet_session)


class FakeServer:
    def __init__(self, handler):
        self.handler = handler
        self.ready = threading.Event()
        self.error = None
        self.listener = socket.socket()
        self.listener.bind(("127.0.0.1", 0))
        self.listener.listen(1)
        self.port = self.listener.getsockname()[1]
        self.ready.set()
        self.thread = threading.Thread(target=self._run, daemon=True)
        self.thread.start()
        self.ready.wait(1)

    def _run(self):
        try:
            connection, _ = self.listener.accept()
            with connection:
                self.handler(connection)
        except Exception as exc:  # noqa: BLE001 - surfaced by close_and_check
            self.error = exc
        finally:
            self.listener.close()

    def close_and_check(self):
        self.thread.join(2)
        if self.thread.is_alive():
            self.listener.close()
            self.thread.join(1)
        if self.error:
            raise self.error


def wait_for(store, predicate, timeout=2):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        records = store.read()
        if predicate(records):
            return records
        time.sleep(0.02)
    raise AssertionError(f"Timed out waiting for transcript: {store.read()}")


def append_preconstructed_store(path, ready, start, results, index):
    store = telnet_session.EvidenceStore(path)
    ready.set()
    if not start.wait(5):
        raise RuntimeError("timed out waiting to start append")
    results.put((index, store.append("inbound", "received", text=f"worker-{index}")))


def read_latest_cursor_from_store(path, ready, start, results):
    store = telnet_session.EvidenceStore(path)
    ready.set()
    if not start.wait(5):
        raise RuntimeError("timed out waiting to read latest cursor")
    results.put(store.latest_cursor())


class TelnetSessionDriverTest(unittest.TestCase):
    def test_repeated_local_appends_reuse_observed_cursor_state(self):
        with tempfile.TemporaryDirectory() as directory:
            transcript = Path(directory) / "session.jsonl"
            store = telnet_session.EvidenceStore(transcript)
            first = store.append("system", "connect")

            with patch.object(
                store, "_scan_next_seq", wraps=store._scan_next_seq
            ) as scan:
                second = store.append("inbound", "received", text="room")
                latest = store.latest_cursor()

            self.assertEqual(second["cursor"], first["cursor"] + 1)
            self.assertEqual(latest, second["cursor"])
            scan.assert_not_called()
            self.assertEqual(store._next_seq, second["cursor"] + 1)

    def test_reopened_store_resumes_from_durable_cursor(self):
        with tempfile.TemporaryDirectory() as directory:
            transcript = Path(directory) / "session.jsonl"
            writer = telnet_session.EvidenceStore(transcript)
            previous = writer.append("system", "connect")

            reopened = telnet_session.EvidenceStore(transcript)
            resumed = reopened.append("inbound", "received", text="resumed")

            self.assertEqual(resumed["cursor"], previous["cursor"] + 1)

    def test_transcript_append_allocates_unique_cursors_across_processes(self):
        context = multiprocessing.get_context("fork")
        with tempfile.TemporaryDirectory() as directory:
            transcript = Path(directory) / "session.jsonl"
            store = telnet_session.EvidenceStore(transcript)
            initial = store.append("system", "connect")
            start = context.Event()
            ready = [context.Event(), context.Event()]
            results = context.Queue()
            processes = [
                context.Process(
                    target=append_preconstructed_store,
                    args=(transcript, ready[index], start, results, index),
                )
                for index in range(2)
            ]
            try:
                for process in processes:
                    process.start()
                for event in ready:
                    self.assertTrue(event.wait(2))
                start.set()
                records = [results.get(timeout=5)[1] for _ in processes]
            finally:
                start.set()
                for process in processes:
                    process.join(5)
                    if process.is_alive():
                        process.terminate()
                        process.join(2)
            self.assertEqual([process.exitcode for process in processes], [0, 0])
            self.assertEqual(sorted(record["cursor"] for record in records), [2, 3])
            resumed = store.read(after=initial["cursor"])
            self.assertEqual(sorted(record["text"] for record in resumed), ["worker-0", "worker-1"])

    def test_latest_cursor_refreshes_independent_stores_across_processes(self):
        context = multiprocessing.get_context("fork")
        with tempfile.TemporaryDirectory() as directory:
            transcript = Path(directory) / "session.jsonl"
            writer = telnet_session.EvidenceStore(transcript)
            first = writer.append("system", "connect")
            independent = telnet_session.EvidenceStore(transcript)
            self.assertEqual(independent.latest_cursor(), first["cursor"])

            start = context.Event()
            ready = context.Event()
            results = context.Queue()
            process = context.Process(
                target=read_latest_cursor_from_store,
                args=(transcript, ready, start, results),
            )
            try:
                process.start()
                self.assertTrue(ready.wait(2))
                second = independent.append("inbound", "received", text="external")
                start.set()
                observed = results.get(timeout=5)
            finally:
                start.set()
                process.join(5)
                if process.is_alive():
                    process.terminate()
                    process.join(2)

            self.assertEqual(process.exitcode, 0)
            self.assertEqual(observed, second["cursor"])
            self.assertEqual(writer.latest_cursor(), second["cursor"])

    def test_latest_cursor_does_not_create_missing_transcript(self):
        with tempfile.TemporaryDirectory() as directory:
            transcript = Path(directory) / "session.jsonl"
            store = telnet_session.EvidenceStore(transcript)

            self.assertEqual(store.latest_cursor(), 0)
            self.assertFalse(transcript.exists())

    def test_read_skips_malformed_records_and_preserves_later_evidence(self):
        with tempfile.TemporaryDirectory() as directory:
            transcript = Path(directory) / "session.jsonl"
            transcript.write_text(
                '{"event":"malformed"\n'
                '{"event":"missing cursor"}\n'
                '[]\n'
                '{"cursor":7,"event":"received","text":"later"}\n',
                encoding="utf-8",
            )
            store = telnet_session.EvidenceStore(transcript)

            self.assertEqual(
                store.read(after=6),
                [{"cursor": 7, "event": "received", "text": "later"}],
            )

    def test_tls_default_wraps_socket_with_ca_and_hostname(self):
        class FakeSocket:
            def __init__(self):
                self.closed = False
                self.timeouts = []

            def settimeout(self, timeout):
                self.timeouts.append(timeout)

            def recv(self, _size):
                return b""

            def shutdown(self, _how):
                return None

            def close(self):
                self.closed = True

        raw_socket = FakeSocket()
        tls_socket = FakeSocket()
        context = unittest.mock.Mock()
        context.wrap_socket.return_value = tls_socket
        with tempfile.TemporaryDirectory() as directory:
            session = telnet_session.TelnetSession(
                "preview.example",
                32016,
                Path(directory) / "session.jsonl",
                read_timeout=0.125,
                output=lambda _line: None,
                ca_file=Path(directory) / "extra-ca.pem",
                server_hostname="public.example",
                connect_timeout=3.5,
            )
            with (
                patch.object(
                    telnet_session.socket,
                    "create_connection",
                    return_value=raw_socket,
                ) as create_connection,
                patch.object(telnet_session.ssl, "create_default_context", return_value=context),
            ):
                session.connect()
            create_connection.assert_called_once_with(
                ("preview.example", 32016), timeout=3.5
            )
            context.load_verify_locations.assert_called_once_with(
                cafile=str(Path(directory) / "extra-ca.pem")
            )
            context.wrap_socket.assert_called_once_with(
                raw_socket, server_hostname="public.example"
            )
            self.assertIs(session.socket, tls_socket)
            self.assertEqual(tls_socket.timeouts, [0.125])
            session.close("tls_test_complete")

    def test_tls_failure_closes_raw_socket_without_fallback(self):
        class FakeSocket:
            def __init__(self):
                self.closed = False

            def close(self):
                self.closed = True

        raw_socket = FakeSocket()
        context = unittest.mock.Mock()
        context.wrap_socket.side_effect = telnet_session.ssl.SSLError("certificate rejected")
        with tempfile.TemporaryDirectory() as directory:
            session = telnet_session.TelnetSession(
                "preview.example",
                32016,
                Path(directory) / "session.jsonl",
                output=lambda _line: None,
            )
            with (
                patch.object(telnet_session.socket, "create_connection", return_value=raw_socket),
                patch.object(telnet_session.ssl, "create_default_context", return_value=context),
                self.assertRaises(telnet_session.ssl.SSLError),
            ):
                session.connect()
            self.assertTrue(raw_socket.closed)
            context.wrap_socket.assert_called_once_with(
                raw_socket, server_hostname="preview.example"
            )

    def test_allow_insecure_is_explicit_parser_opt_in(self):
        args = telnet_session.build_parser().parse_args(
            [
                "connect",
                "--host",
                "localhost",
                "--port",
                "32000",
                "--transcript",
                "/tmp/session.jsonl",
                "--allow-insecure",
            ]
        )
        self.assertTrue(args.allow_insecure)

    def test_connect_and_receive_timeouts_are_distinct_positive_cli_values(self):
        parser = telnet_session.build_parser()
        base_args = [
            "connect",
            "--host",
            "localhost",
            "--port",
            "32000",
            "--transcript",
            "/tmp/session.jsonl",
        ]

        args = parser.parse_args(
            [*base_args, "--connect-timeout", "7.5", "--timeout", "0.125"]
        )
        self.assertEqual(args.connect_timeout, 7.5)
        self.assertEqual(args.timeout, 0.125)

        for option, timeout_name in (
            ("--connect-timeout", "connect timeout"),
            ("--timeout", "receive timeout"),
        ):
            for invalid in ("0", "-1", "nan", "inf", "not-a-number"):
                with self.subTest(option=option, invalid=invalid):
                    stderr = io.StringIO()
                    with (
                        contextlib.redirect_stderr(stderr),
                        self.assertRaises(SystemExit),
                    ):
                        parser.parse_args([*base_args, option, invalid])
                    self.assertIn(
                        f"argument {option}: {timeout_name} must be a positive finite number",
                        stderr.getvalue(),
                    )

    def test_direct_session_timeout_validation_raises_value_error(self):
        with tempfile.TemporaryDirectory() as directory:
            transcript = Path(directory) / "session.jsonl"
            for timeout_name, override in (
                ("read timeout", {"read_timeout": 0}),
                ("connect timeout", {"connect_timeout": float("nan")}),
            ):
                with (
                    self.subTest(timeout_name=timeout_name),
                    self.assertRaisesRegex(
                        ValueError,
                        f"^{timeout_name} must be a positive finite number$",
                    ),
                ):
                    telnet_session.TelnetSession(
                        "localhost", 32000, transcript, **override
                    )

    def test_run_connect_passes_cli_connect_timeout_to_session(self):
        observed = {}

        class StubSession:
            def __init__(self, _host, _port, _transcript, timeout, **kwargs):
                observed["read_timeout"] = timeout
                observed["connect_timeout"] = kwargs["connect_timeout"]
                self.closed = False

            def connect(self):
                return None

            def close(self, _reason):
                self.closed = True

        args = argparse.Namespace(
            host="localhost",
            port=32000,
            transcript=Path("/tmp/session.jsonl"),
            timeout=0.125,
            connect_timeout=7.5,
            allow_insecure=False,
            ca_file=None,
            server_hostname=None,
        )
        with (
            patch.object(telnet_session, "TelnetSession", StubSession),
            patch("sys.stdin", io.StringIO(":close done\n")),
            contextlib.redirect_stdout(io.StringIO()),
        ):
            self.assertEqual(telnet_session.run_connect(args), 0)

        self.assertEqual(observed, {"read_timeout": 0.125, "connect_timeout": 7.5})

    def test_raw_mode_does_not_create_tls_context(self):
        raw_socket = unittest.mock.Mock()
        raw_socket.recv.return_value = b""
        with tempfile.TemporaryDirectory() as directory:
            session = telnet_session.TelnetSession(
                "localhost",
                32000,
                Path(directory) / "session.jsonl",
                output=lambda _line: None,
                tls_enabled=False,
            )
            with (
                patch.object(telnet_session.socket, "create_connection", return_value=raw_socket),
                patch.object(telnet_session.ssl, "create_default_context") as create_context,
            ):
                session.connect()
            create_context.assert_not_called()
            session.close("raw_test_complete")

    def test_async_unsolicited_output_cursor_and_explicit_close(self):
        def handler(connection):
            connection.sendall(b"WELCOME\r\n")
            command = b""
            while not command.endswith(b"\r\n"):
                command += connection.recv(1)
            self.assertEqual(command, b"LOOK\r\n")
            connection.sendall(b"UNSOLICITED notice\r\n")
            time.sleep(0.12)
            connection.sendall(b"ROOM antechamber\r\n")
            connection.settimeout(1)
            try:
                while connection.recv(1):
                    pass
            except OSError:
                pass

        server = FakeServer(handler)
        with tempfile.TemporaryDirectory() as directory:
            transcript = Path(directory) / "session.jsonl"
            output = []
            session = telnet_session.TelnetSession(
                "127.0.0.1",
                server.port,
                transcript,
                read_timeout=0.03,
                output=output.append,
                tls_enabled=False,
            )
            session.connect()
            records = wait_for(session.store, lambda rows: any(r.get("text") == "WELCOME\r\n" for r in rows))
            cursor = max(r["cursor"] for r in records)
            session.send_command("LOOK")
            records = wait_for(
                session.store,
                lambda rows: any("ROOM antechamber" in r.get("text", "") for r in rows),
            )
            time.sleep(0.12)
            idle_timeout_count = sum(r["event"] == "timeout" for r in session.store.read())
            time.sleep(0.12)
            self.assertEqual(
                idle_timeout_count,
                sum(r["event"] == "timeout" for r in session.store.read()),
            )
            session.close("demo_complete")
            server.close_and_check()

            received = [r["text"] for r in records if r["event"] == "received"]
            self.assertIn("UNSOLICITED notice\r\n", received)
            self.assertIn("ROOM antechamber\r\n", received)
            command_seq = next(r["seq"] for r in records if r["event"] == "command")
            unsolicited_seq = next(
                r["seq"] for r in records if r.get("text") == "UNSOLICITED notice\r\n"
            )
            room_seq = next(r["seq"] for r in records if r.get("text") == "ROOM antechamber\r\n")
            self.assertLess(command_seq, unsolicited_seq)
            self.assertLess(unsolicited_seq, room_seq)
            after_cursor = session.store.read(after=cursor)
            self.assertTrue(any(r["event"] == "command" for r in after_cursor))
            self.assertTrue(any(r["event"] == "close" and r["reason"] == "demo_complete" for r in after_cursor))
            self.assertTrue(any(r["event"] == "disconnect" for r in after_cursor))
            self.assertNotIn("next_cursor=", "".join(output))
            self.assertEqual([r["seq"] for r in session.store.read()], sorted(r["seq"] for r in session.store.read()))
            self.assertEqual(stat.S_IMODE(transcript.stat().st_mode), 0o600)

    def test_telnet_negotiation_is_refused_and_not_rendered(self):
        def handler(connection):
            connection.sendall(bytes((255, 251, 42)) + b"READY\r\n")
            connection.settimeout(1)
            response = b""
            while len(response) < 3:
                response += connection.recv(3 - len(response))
            self.assertEqual(response, bytes((255, 254, 42)))
            try:
                while connection.recv(1):
                    pass
            except OSError:
                pass

        server = FakeServer(handler)
        with tempfile.TemporaryDirectory() as directory:
            output = []
            session = telnet_session.TelnetSession(
                "127.0.0.1",
                server.port,
                Path(directory) / "session.jsonl",
                output=output.append,
                tls_enabled=False,
            )
            session.connect()
            records = wait_for(session.store, lambda rows: any("READY" in r.get("text", "") for r in rows))
            session.close("negotiation_complete")
            server.close_and_check()
            self.assertTrue(any(r["event"] == "telnet_negotiation" and r["direction"] == "inbound" for r in records))
            self.assertTrue(any(r["event"] == "telnet_negotiation" and r["command"] == "DONT" for r in records))
            self.assertTrue(any("INBOUND TELNET" in line and "option=42" in line for line in output))
            self.assertFalse(any("\xff" in r.get("text", "") for r in records))

    def test_close_wins_receiver_disconnect_race(self):
        class ClosingSocket:
            def __init__(self):
                self.entered = threading.Event()
                self.release = threading.Event()

            def recv(self, _size):
                self.entered.set()
                self.release.wait(2)
                raise OSError("closed by test")

            def shutdown(self, _how):
                self.release.set()

            def close(self):
                self.release.set()

        with tempfile.TemporaryDirectory() as directory:
            output = []
            session = telnet_session.TelnetSession(
                "localhost",
                32000,
                Path(directory) / "session.jsonl",
                output=output.append,
                tls_enabled=False,
            )
            fake_socket = ClosingSocket()
            session.socket = fake_socket
            session.receiver = threading.Thread(target=session._receive_loop, daemon=True)
            session.receiver.start()
            self.assertTrue(fake_socket.entered.wait(2))
            session.close("demo_complete")

            session.receiver.join(2)
            records = session.store.read()
            disconnects = [record for record in records if record["event"] == "disconnect"]
            self.assertEqual([record["reason"] for record in disconnects], ["demo_complete"])
            self.assertFalse(any(record["event"] == "error" for record in records))

    def test_received_display_escapes_terminal_controls_without_mutating_evidence(self):
        raw = "room \x1b[31mred\x1b]8;;https://example.test\x07\x01\r\n"
        with tempfile.TemporaryDirectory() as directory:
            output = []
            session = telnet_session.TelnetSession(
                "localhost",
                32000,
                Path(directory) / "session.jsonl",
                output=output.append,
                tls_enabled=False,
            )
            session._append("inbound", "received", text=raw)

            rendered = output[-1]
            self.assertNotIn("\x1b", rendered)
            self.assertNotIn("\x07", rendered)
            self.assertNotIn("\x01", rendered)
            self.assertIn(r"\x1b[31m", rendered)
            self.assertIn(r"\x1b]8;;https://example.test\x07", rendered)
            self.assertTrue(rendered.endswith("\r\n"))
            self.assertEqual(session.store.read()[0]["text"], raw)

    def test_login_echo_redacts_password_in_output_and_transcript(self):
        secret = "secret-7"

        def handler(connection):
            command = b""
            while not command.endswith(b"\r\n"):
                command += connection.recv(1)
            split_at = command.index(secret.encode("iso-8859-1")) + 3
            connection.sendall(command[:split_at])
            time.sleep(0.02)
            connection.sendall(command[split_at:])
            time.sleep(0.08)

        server = FakeServer(handler)
        with tempfile.TemporaryDirectory() as directory:
            transcript = Path(directory) / "session.jsonl"
            output = []
            session = telnet_session.TelnetSession(
                "127.0.0.1",
                server.port,
                transcript,
                output=output.append,
                tls_enabled=False,
            )
            session.connect()
            session.send_command(f"LOGIN demo@example.com {secret}")
            wait_for(session.store, lambda rows: any(r["event"] == "received" for r in rows))
            session.close("redaction_complete")
            server.close_and_check()
            transcript_text = transcript.read_text(encoding="utf-8")
            rendered = "\n".join(output)
            self.assertNotIn(secret, transcript_text)
            self.assertNotIn(secret, rendered)
            self.assertIn("LOGIN demo@example.com [REDACTED]", transcript_text)
            self.assertIn("LOGIN demo@example.com [REDACTED]", rendered)

    def test_login_redaction_reassembles_room_text_across_socket_boundary(self):
        def handler(connection):
            command = b""
            while not command.endswith(b"\r\n"):
                command += connection.recv(1)
            connection.sendall(b"L")
            time.sleep(0.02)
            connection.sendall(b"antern light fills the room.\r\n")
            time.sleep(0.08)

        server = FakeServer(handler)
        with tempfile.TemporaryDirectory() as directory:
            transcript = Path(directory) / "session.jsonl"
            session = telnet_session.TelnetSession(
                "127.0.0.1",
                server.port,
                transcript,
                output=lambda _line: None,
                tls_enabled=False,
            )
            session.connect()
            session.send_command("LOGIN demo@example.com secret-7")
            records = wait_for(
                session.store,
                lambda rows: any(
                    row.get("text") == "Lantern light fills the room.\r\n"
                    for row in rows
                ),
            )
            session.close("boundary_complete")
            server.close_and_check()

            received = [
                row["text"] for row in records if row.get("event") == "received"
            ]
            self.assertEqual(received, ["Lantern light fills the room.\r\n"])

    def test_login_redaction_drops_cross_socket_credential_near_match(self):
        secret = "secret-7"

        def handler(connection):
            command = b""
            while not command.endswith(b"\r\n"):
                command += connection.recv(1)
            echoed_command = command.removesuffix(b"\r\n")
            connection.sendall(echoed_command[:-1])
            time.sleep(0.02)
            connection.sendall(b"X\r\nLantern light fills the room.\r\n")
            time.sleep(0.08)

        server = FakeServer(handler)
        with tempfile.TemporaryDirectory() as directory:
            transcript = Path(directory) / "session.jsonl"
            output = []
            session = telnet_session.TelnetSession(
                "127.0.0.1",
                server.port,
                transcript,
                output=output.append,
                tls_enabled=False,
            )
            session.connect()
            session.send_command(f"LOGIN demo@example.com {secret}")
            records = wait_for(
                session.store,
                lambda rows: any(
                    row.get("text") == "Lantern light fills the room.\r\n"
                    for row in rows
                ),
            )
            session.close("boundary_complete")
            server.close_and_check()

            transcript_text = transcript.read_text(encoding="utf-8")
            rendered = "\n".join(output)
            received = [
                row["text"] for row in records if row.get("event") == "received"
            ]
            self.assertEqual(received, ["Lantern light fills the room.\r\n"])
            self.assertNotIn("Xcret-7", transcript_text)
            self.assertNotIn("Xcret-7", rendered)
            self.assertNotIn(secret, transcript_text)
            self.assertNotIn(secret, rendered)

    def test_login_redaction_suppresses_early_mismatch_and_recovers_next_line(self):
        secret = "secret-7"

        def handler(connection):
            command = b""
            while not command.endswith(b"\r\n"):
                command += connection.recv(1)
            connection.sendall(b"LOGIN demo@example.com s")
            time.sleep(0.02)
            connection.sendall(b"Xcret-7\r\nA later room line.\r\n")
            time.sleep(0.08)

        server = FakeServer(handler)
        with tempfile.TemporaryDirectory() as directory:
            transcript = Path(directory) / "session.jsonl"
            output = []
            session = telnet_session.TelnetSession(
                "127.0.0.1",
                server.port,
                transcript,
                output=output.append,
                tls_enabled=False,
            )
            session.connect()
            session.send_command(f"LOGIN demo@example.com {secret}")
            records = wait_for(
                session.store,
                lambda rows: any(
                    row.get("text") == "A later room line.\r\n" for row in rows
                ),
            )
            session.close("boundary_complete")
            server.close_and_check()

            transcript_text = transcript.read_text(encoding="utf-8")
            rendered = "\n".join(output)
            received = [
                row["text"] for row in records if row.get("event") == "received"
            ]
            self.assertEqual(received, ["A later room line.\r\n"])
            for forbidden in (secret, "Xcret-7", "sXcret-7"):
                self.assertNotIn(forbidden, transcript_text)
                self.assertNotIn(forbidden, rendered)

    def test_login_redaction_drops_partial_prefix_on_unrelated_bytes(self):
        _, raw, replacement = telnet_session._login_redaction(
            "LOGIN demo@example.com secret-7"
        )
        with tempfile.TemporaryDirectory() as directory:
            session = telnet_session.TelnetSession(
                "localhost",
                32000,
                Path(directory) / "session.jsonl",
                output=lambda _line: None,
                tls_enabled=False,
            )
            session.redaction_patterns.append((raw, replacement))

            partial = session._redact_inbound(raw[:-2])
            unrelated = session._redact_inbound(b" room text\r\n")
            recovered = session._redact_inbound(b"Later room text\r\n")

            self.assertEqual(partial, b"")
            self.assertEqual(unrelated, b"")
            self.assertEqual(recovered, b"Later room text\r\n")
            self.assertNotIn(raw[:-2], partial + unrelated + recovered)
            self.assertNotIn(b"secret-7", partial + unrelated + recovered)
            self.assertEqual(session.redaction_tail, b"")

    def test_login_redaction_drops_partial_prefix_at_eof(self):
        _, raw, replacement = telnet_session._login_redaction(
            "LOGIN demo@example.com secret-7"
        )
        with tempfile.TemporaryDirectory() as directory:
            session = telnet_session.TelnetSession(
                "localhost",
                32000,
                Path(directory) / "session.jsonl",
                output=lambda _line: None,
                tls_enabled=False,
            )
            session.redaction_patterns.append((raw, replacement))

            partial = session._redact_inbound(raw[:-2])
            trailing = session._redact_inbound(b"", final=True)

            self.assertEqual(partial, b"")
            self.assertEqual(trailing, b"")
            self.assertEqual(session.redaction_tail, b"")

    def test_login_redaction_drops_disproved_in_chunk_credential_prefix(self):
        _, raw, replacement = telnet_session._login_redaction(
            "LOGIN demo@example.com secret-7"
        )
        with tempfile.TemporaryDirectory() as directory:
            session = telnet_session.TelnetSession(
                "localhost",
                32000,
                Path(directory) / "session.jsonl",
                output=lambda _line: None,
                tls_enabled=False,
            )
            session.redaction_patterns.append((raw, replacement))

            safe = session._redact_inbound(b"room: " + raw[:-1] + b"X\r\n")

            self.assertEqual(safe, b"")
            self.assertNotIn(b"secret", safe)
            self.assertNotIn(raw[:-1], safe)
            self.assertEqual(session.redaction_tail, b"")

    def test_login_redaction_preserves_unrelated_in_chunk_text(self):
        _, raw, replacement = telnet_session._login_redaction(
            "LOGIN demo@example.com secret-7"
        )
        with tempfile.TemporaryDirectory() as directory:
            session = telnet_session.TelnetSession(
                "localhost",
                32000,
                Path(directory) / "session.jsonl",
                output=lambda _line: None,
                tls_enabled=False,
            )
            session.redaction_patterns.append((raw, replacement))

            safe = session._redact_inbound(b"Lantern light fills the room.\r\n")

            self.assertEqual(safe, b"Lantern light fills the room.\r\n")

    def test_login_redaction_handles_leading_whitespace_and_extra_tail(self):
        command = "\t LOGIN demo@example.com secret extra-token"
        redaction = telnet_session._login_redaction(command)

        self.assertIsNotNone(redaction)
        safe, raw, replacement = redaction
        self.assertEqual(safe, "LOGIN demo@example.com [REDACTED]")
        self.assertEqual(raw, command.encode("iso-8859-1"))
        self.assertEqual(replacement, safe.encode("iso-8859-1"))
        self.assertNotIn(b"secret", replacement)
        self.assertIsNone(telnet_session._login_redaction("LOGIN demo@example.com"))

    def test_login_redaction_uses_transmitted_iso88591_replacement_bytes(self):
        command = "LOGIN snowman☃ secret☃"

        safe, raw, replacement = telnet_session._login_redaction(command)

        self.assertEqual(raw, command.encode("iso-8859-1", errors="replace"))
        self.assertEqual(replacement, safe.encode("iso-8859-1", errors="replace"))
        self.assertEqual(safe, "LOGIN snowman? [REDACTED]")

    def test_interactive_send_failure_stops_cleanly(self):
        with tempfile.TemporaryDirectory() as directory:
            transcript = Path(directory) / "session.jsonl"
            for failure_type in (OSError, RuntimeError):
                with self.subTest(failure_type=failure_type.__name__):

                    class StubSession:
                        _failure_type = failure_type

                        def __init__(self, host, port, transcript_path, timeout, **_kwargs):
                            self.store = telnet_session.EvidenceStore(transcript_path)
                            self.closed = False

                        def connect(self):
                            return None

                        def send_command(self, _line):
                            raise self._failure_type("session closed")

                        def close(self, reason):
                            self.closed = True

                    args = argparse.Namespace(
                        host="localhost", port=32000, transcript=transcript, timeout=0.25
                    )
                    output = io.StringIO()
                    with (
                        patch.object(telnet_session, "TelnetSession", StubSession),
                        patch("sys.stdin", io.StringIO("LOOK\n")),
                        contextlib.redirect_stdout(output),
                        contextlib.redirect_stderr(output),
                    ):
                        self.assertEqual(telnet_session.run_connect(args), 0)

                    self.assertIn("Unable to send command: session closed", output.getvalue())

    def test_read_subcommand_returns_events_after_cursor(self):
        with tempfile.TemporaryDirectory() as directory:
            transcript = Path(directory) / "session.jsonl"
            store = telnet_session.EvidenceStore(transcript)
            first = store.append("system", "connect", host="local", port=1)
            store.append("inbound", "received", text="hello")
            completed = subprocess.run(
                [sys.executable, str(TOOL_PATH), "read", "--transcript", str(transcript), "--after", str(first["cursor"])],
                check=True,
                capture_output=True,
                text=True,
            )
            lines = completed.stdout.strip().splitlines()
            self.assertEqual(json.loads(lines[0])["text"], "hello")
            self.assertEqual(lines[-1], "next_cursor=2")

            empty = subprocess.run(
                [sys.executable, str(TOOL_PATH), "read", "--transcript", str(transcript), "--after", "2"],
                check=True,
                capture_output=True,
                text=True,
            )
            self.assertEqual(empty.stdout.strip(), "next_cursor=2")

    def test_interactive_read_rejects_invalid_cursor_and_preserves_session(self):
        with tempfile.TemporaryDirectory() as directory:
            transcript = Path(directory) / "session.jsonl"
            transcript.write_text(
                json.dumps({"cursor": 1, "event": "connect"}) + "\n", encoding="utf-8"
            )

            class StubSession:
                def __init__(self, host, port, transcript_path, timeout, **_kwargs):
                    self.store = telnet_session.EvidenceStore(transcript_path)
                    self.closed = False

                def connect(self):
                    return None

                def close(self, reason):
                    self.closed = True

            args = argparse.Namespace(
                host="localhost", port=32000, transcript=transcript, timeout=0.25
            )
            output = io.StringIO()
            with (
                patch.object(telnet_session, "TelnetSession", StubSession),
                patch("sys.stdin", io.StringIO(":read nope\n:read 1\n:close done\n")),
                contextlib.redirect_stdout(output),
            ):
                self.assertEqual(telnet_session.run_connect(args), 0)

            self.assertIn("Invalid cursor: 'nope'", output.getvalue())
            self.assertIn("next_cursor=1", output.getvalue())


if __name__ == "__main__":
    unittest.main(verbosity=2)
