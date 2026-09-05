#!/usr/bin/env python3
"""Focused tests for the maintained gameplay Telnet session helper."""

import importlib.util
import json
import socket
import stat
import subprocess
import sys
import tempfile
import threading
import time
import unittest
from pathlib import Path

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
        self.thread = threading.Thread(target=self._run, daemon=True)
        self.thread.start()
        self.ready.wait(1)

    def _run(self):
        try:
            connection, _ = self.listener.accept()
            with connection:
                self.ready.set()
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


class TelnetSessionDriverTest(unittest.TestCase):
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
                "127.0.0.1", server.port, transcript, read_timeout=0.03, output=output.append
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
                "127.0.0.1", server.port, Path(directory) / "session.jsonl", output=output.append
            )
            session.connect()
            records = wait_for(session.store, lambda rows: any("READY" in r.get("text", "") for r in rows))
            session.close("negotiation_complete")
            server.close_and_check()
            self.assertTrue(any(r["event"] == "telnet_negotiation" and r["direction"] == "inbound" for r in records))
            self.assertTrue(any(r["event"] == "telnet_negotiation" and r["command"] == "DONT" for r in records))
            self.assertTrue(all("READY" in line or "TELNET" in line or line.startswith("[") for line in output))
            self.assertFalse(any("\xff" in r.get("text", "") for r in records))

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
                "127.0.0.1", server.port, transcript, output=output.append
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


if __name__ == "__main__":
    unittest.main(verbosity=2)
