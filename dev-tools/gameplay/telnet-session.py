#!/usr/bin/env python3
"""Maintain an interactive FireMUD TCP/Telnet player session.

The receiver is independent from stdin command handling.  Every observed event
is appended to a JSONL transcript so asynchronous and unsolicited output is not
lost when a command has no immediately corresponding response.
"""

from __future__ import annotations

import argparse
import fcntl
import json
import os
import re
import socket
import ssl
import sys
import threading
from collections.abc import Callable, Iterable
from datetime import datetime, timezone
from pathlib import Path

IAC = 255
WILL = 251
WONT = 252
DO = 253
DONT = 254
SB = 250
SE = 240

COMMAND_NAMES = {WILL: "WILL", WONT: "WONT", DO: "DO", DONT: "DONT"}


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="milliseconds").replace(
        "+00:00", "Z"
    )


class EvidenceStore:
    """Append-only JSONL evidence with an unambiguous integer cursor."""

    def __init__(self, path: os.PathLike[str] | str):
        self.path = Path(path)
        self.path.parent.mkdir(parents=True, exist_ok=True)
        self._lock = threading.Lock()
        self._next_seq = self._find_next_seq()

    def _find_next_seq(self) -> int:
        if not self.path.exists():
            return 1
        last = 0
        with self.path.open(encoding="utf-8") as stream:
            for line in stream:
                try:
                    last = max(last, int(json.loads(line)["seq"]))
                except (TypeError, ValueError, KeyError, json.JSONDecodeError):
                    continue
        return last + 1

    def append(self, direction: str, event: str, **fields) -> dict:
        with self._lock:
            # The process-local lock protects threads; flock protects writers
            # sharing this transcript across processes.
            fd = os.open(self.path, os.O_WRONLY | os.O_APPEND | os.O_CREAT, 0o600)
            try:
                os.fchmod(fd, 0o600)
                fcntl.flock(fd, fcntl.LOCK_EX)
                try:
                    self._next_seq = self._find_next_seq()
                    seq = self._next_seq
                    self._next_seq += 1
                    record = {
                        "timestamp": utc_now(),
                        "seq": seq,
                        "cursor": seq,
                        "direction": direction,
                        "event": event,
                        **fields,
                    }
                    line = json.dumps(record, ensure_ascii=False, separators=(",", ":")) + "\n"
                    # O_APPEND makes each complete JSONL write append at the
                    # filesystem boundary; flush/fsync makes live evidence
                    # durable before display.
                    with os.fdopen(os.dup(fd), "a", encoding="utf-8") as stream:
                        stream.write(line)
                        stream.flush()
                        os.fsync(stream.fileno())
                    return record
                finally:
                    fcntl.flock(fd, fcntl.LOCK_UN)
            finally:
                os.close(fd)

    def read(self, after: int | None = None) -> list[dict]:
        if not self.path.exists():
            return []
        records = []
        with self.path.open(encoding="utf-8") as stream:
            for line in stream:
                try:
                    record = json.loads(line)
                    cursor = int(record["cursor"])
                except (TypeError, ValueError, KeyError, json.JSONDecodeError):
                    continue
                if after is None or cursor > after:
                    records.append(record)
        return records

    def latest_cursor(self) -> int:
        with self._lock:
            # Do not report the process-local cursor when another process has
            # appended evidence. A shared flock makes this rescan consistent
            # with append's exclusive writer lock.
            try:
                fd = os.open(self.path, os.O_RDONLY)
            except FileNotFoundError:
                # A missing transcript has no durable evidence. In particular,
                # do not create it merely to answer a cursor query.
                self._next_seq = 1
                return 0
            try:
                fcntl.flock(fd, fcntl.LOCK_SH)
                try:
                    self._next_seq = self._find_next_seq()
                    return self._next_seq - 1
                finally:
                    fcntl.flock(fd, fcntl.LOCK_UN)
            finally:
                os.close(fd)


def _login_redaction(command: str) -> tuple[str, bytes, bytes] | None:
    match = re.match(r"(?i)^[ \t]*LOGIN[ \t]+(\S+)(?:[ \t]+(\S.*))?$", command)
    if not match or match.group(2) is None:
        return None
    safe = f"LOGIN {match.group(1)} [REDACTED]"
    raw = command.encode("iso-8859-1", errors="replace")
    replacement = safe.encode("iso-8859-1")
    return safe, raw, replacement


def _display_text(text: str) -> str:
    """Make received control characters visible without changing evidence."""
    return "".join(char if char in "\r\n" or char.isprintable() else f"\\x{ord(char):02x}" for char in text)


class TelnetParser:
    """Strip Telnet controls while refusing every unsupported option."""

    def __init__(self):
        self.state = "data"
        self.command = None

    def feed(self, data: bytes) -> tuple[bytes, list[tuple[str, int, bytes | None]]]:
        output = bytearray()
        negotiations = []
        for value in data:
            if self.state == "data":
                if value == IAC:
                    self.state = "iac"
                else:
                    output.append(value)
            elif self.state == "iac":
                if value == IAC:
                    output.append(IAC)
                    self.state = "data"
                elif value in COMMAND_NAMES:
                    self.command = value
                    self.state = "option"
                elif value == SB:
                    self.state = "subnegotiation"
                else:
                    negotiations.append(("IAC", value, None))
                    self.state = "data"
            elif self.state == "option":
                command = self.command
                name = COMMAND_NAMES[command]
                response = None
                if command == WILL:
                    response = bytes((IAC, DONT, value))
                elif command == DO:
                    response = bytes((IAC, WONT, value))
                negotiations.append((name, value, response))
                self.command = None
                self.state = "data"
            elif self.state == "subnegotiation":
                if value == IAC:
                    self.state = "subnegotiation_iac"
            else:  # subnegotiation_iac
                self.state = "data" if value == SE else "subnegotiation"
        return bytes(output), negotiations


class TelnetSession:
    """A maintained, asynchronously receiving TCP/Telnet session."""

    def __init__(
        self,
        host: str,
        port: int,
        transcript: os.PathLike[str] | str,
        read_timeout: float = 0.25,
        output: Callable[[str], None] | None = None,
        tls_enabled: bool = True,
        ca_file: os.PathLike[str] | str | None = None,
        server_hostname: str | None = None,
    ):
        self.host = host
        self.port = port
        self.store = EvidenceStore(transcript)
        self.read_timeout = read_timeout
        self.output = output or print
        self.tls_enabled = tls_enabled
        self.ca_file = Path(ca_file) if ca_file is not None else None
        self.server_hostname = server_hostname
        self.socket: socket.socket | None = None
        self.receiver: threading.Thread | None = None
        self.send_lock = threading.Lock()
        self.state_lock = threading.Lock()
        self.closed = False
        self.disconnect_recorded = False
        self.idle_timeout_recorded = False
        self.parser = TelnetParser()
        self.redaction_patterns: list[tuple[bytes, bytes]] = []
        self.redaction_tail = b""

    def _show(self, record: dict) -> None:
        if record["event"] == "received":
            text = record.get("text", "")
            self.output(f"[{record['seq']}] IN: {_display_text(text)}")
        elif record["event"] == "command":
            self.output(f"[{record['seq']}] OUT: {record.get('text', '')}")
        elif record["event"] in {"connect", "timeout", "disconnect", "error", "close"}:
            detail = record.get("reason", record["event"])
            self.output(f"[{record['seq']}] {record['event'].upper()}: {detail}")
        elif record["event"] == "telnet_negotiation":
            self.output(
                f"[{record['seq']}] {record['direction'].upper()} TELNET: "
                f"{record.get('command')} option={record.get('option')}"
            )

    def _append(self, direction: str, event: str, **fields) -> dict:
        record = self.store.append(direction, event, **fields)
        self._show(record)
        return record

    def connect(self) -> None:
        try:
            raw_socket = socket.create_connection((self.host, self.port))
            if self.tls_enabled:
                try:
                    context = ssl.create_default_context()
                    if self.ca_file is not None:
                        context.load_verify_locations(cafile=str(self.ca_file))
                    self.socket = context.wrap_socket(
                        raw_socket,
                        server_hostname=self.server_hostname or self.host,
                    )
                except Exception:
                    raw_socket.close()
                    raise
            else:
                self.socket = raw_socket
            self.socket.settimeout(self.read_timeout)
        except OSError as exc:
            self._append("system", "error", reason="connect", detail=str(exc))
            raise
        self._append("system", "connect", host=self.host, port=self.port)
        self.receiver = threading.Thread(target=self._receive_loop, name="firemud-telnet-receiver", daemon=True)
        self.receiver.start()

    def _redact_inbound(self, data: bytes, final: bool = False) -> bytes:
        combined = self.redaction_tail + data
        keep = 0
        if not final:
            for pattern, _ in self.redaction_patterns:
                for size in range(min(len(pattern) - 1, len(combined)), 0, -1):
                    if combined.endswith(pattern[:size]):
                        keep = max(keep, size)
                        break
        safe, self.redaction_tail = combined[: len(combined) - keep], combined[len(combined) - keep :]
        for pattern, replacement in self.redaction_patterns:
            safe = safe.replace(pattern, replacement)
        if final:
            self.redaction_tail = b""
        return safe

    def _receive_loop(self) -> None:
        assert self.socket is not None
        while True:
            try:
                chunk = self.socket.recv(4096)
            except TimeoutError:
                if not self.closed and not self.idle_timeout_recorded:
                    self._append("inbound", "timeout", reason="read_idle")
                    self.idle_timeout_recorded = True
                continue
            except OSError as exc:
                self._record_disconnect(
                    "receive_error",
                    error=("inbound", "error", {"reason": "receive", "detail": str(exc)}),
                )
                return
            if not chunk:
                if not self._claim_disconnect():
                    return
                trailing = self._redact_inbound(b"", final=True)
                if trailing:
                    self._append(
                        "inbound",
                        "received",
                        text=trailing.decode("iso-8859-1", errors="replace"),
                    )
                self._append("system", "disconnect", reason="remote_eof")
                return
            self.idle_timeout_recorded = False
            payload, negotiations = self.parser.feed(chunk)
            for command, option, response in negotiations:
                self._append("inbound", "telnet_negotiation", command=command, option=option)
                if response is not None:
                    with self.send_lock:
                        try:
                            self.socket.sendall(response)
                        except OSError as exc:
                            self._record_disconnect(
                                "send_error",
                                error=(
                                    "outbound",
                                    "error",
                                    {"reason": "telnet_refusal", "detail": str(exc)},
                                ),
                            )
                            return
                    response_name = "DONT" if command == "WILL" else "WONT"
                    self._append(
                        "outbound",
                        "telnet_negotiation",
                        command=response_name,
                        option=option,
                    )
            safe_payload = self._redact_inbound(payload)
            if safe_payload:
                self._append(
                    "inbound",
                    "received",
                    text=safe_payload.decode("iso-8859-1", errors="replace"),
                )

    def send_command(self, command: str) -> None:
        if self.socket is None or self.closed:
            raise RuntimeError("Telnet session is not connected")
        command = command.rstrip("\r\n")
        redaction = _login_redaction(command)
        if redaction:
            safe, raw, replacement = redaction
            self.redaction_patterns.append((raw, replacement))
            display = safe
        else:
            display = command
        # Record before send so an immediate asynchronous server response cannot
        # acquire a lower cursor than the command that caused it.
        self.idle_timeout_recorded = False
        self._append("outbound", "command", text=display)
        try:
            with self.send_lock:
                self.socket.sendall(command.encode("iso-8859-1") + b"\r\n")
        except OSError as exc:
            self._append("outbound", "error", reason="send", detail=str(exc))
            self._record_disconnect("send_error")
            raise

    def _claim_disconnect(self, *, local: bool = False) -> bool:
        with self.state_lock:
            if self.disconnect_recorded or (self.closed and not local):
                return False
            self.disconnect_recorded = True
            return True

    def _record_disconnect(
        self,
        reason: str,
        *,
        local: bool = False,
        error: tuple[str, str, dict] | None = None,
    ) -> bool:
        if not self._claim_disconnect(local=local):
            return False
        if error:
            direction, event, fields = error
            self._append(direction, event, **fields)
        self._append("system", "disconnect", reason=reason)
        return True

    def close(self, reason: str = "local_close") -> None:
        with self.state_lock:
            if self.closed:
                return
            self.closed = True
        self._append("local", "close", reason=reason)
        if self.socket is not None:
            try:
                self.socket.shutdown(socket.SHUT_RDWR)
            except OSError:
                pass
            try:
                self.socket.close()
            except OSError:
                pass
        self._record_disconnect(reason, local=True)
        if self.receiver and self.receiver is not threading.current_thread():
            self.receiver.join(timeout=max(1.0, self.read_timeout * 4))


def print_records(records: Iterable[dict], after: int | None = None) -> int:
    latest = after if after is not None else 0
    for record in records:
        print(json.dumps(record, ensure_ascii=False, separators=(",", ":")))
        latest = max(latest, int(record["cursor"]))
    print(f"next_cursor={latest}")
    return latest


def run_read(args: argparse.Namespace) -> int:
    print_records(EvidenceStore(args.transcript).read(args.after), args.after)
    return 0


def run_connect(args: argparse.Namespace) -> int:
    allow_insecure = getattr(args, "allow_insecure", False)
    if allow_insecure:
        print("WARNING: using insecure raw TCP; local/private/test use only.")
    session = TelnetSession(
        args.host,
        args.port,
        args.transcript,
        args.timeout,
        tls_enabled=not allow_insecure,
        ca_file=getattr(args, "ca_file", None),
        server_hostname=getattr(args, "server_hostname", None),
    )
    try:
        session.connect()
        print("Commands are sent as entered. Meta-commands: :read [cursor], :cursor, :close [reason].")
        for line in sys.stdin:
            line = line.rstrip("\r\n")
            if line == ":cursor":
                print(f"next_cursor={session.store.latest_cursor()}")
            elif line.startswith(":read") and (line == ":read" or line.startswith(":read ")):
                value = line[5:].strip()
                try:
                    after = int(value) if value else None
                except ValueError:
                    print(f"Invalid cursor: {value!r}")
                    continue
                print_records(session.store.read(after), after)
            elif line.startswith(":close") and (line == ":close" or line.startswith(":close ")):
                session.close(line[6:].strip() or "local_close")
                break
            elif line == ":quit":
                session.close("local_quit")
                break
            elif line:
                session.send_command(line)
    finally:
        if not session.closed:
            session.close("stdin_eof")
    return 0


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    subparsers = parser.add_subparsers(dest="mode", required=True)
    connect = subparsers.add_parser("connect", help="maintain an interactive Telnet session")
    connect.add_argument("--host", required=True)
    connect.add_argument("--port", required=True, type=int)
    connect.add_argument("--transcript", required=True, type=Path)
    connect.add_argument("--timeout", type=float, default=0.25)
    connect.add_argument(
        "--allow-insecure",
        action="store_true",
        help="use raw TCP; local/private/test only, never for public endpoints",
    )
    connect.add_argument(
        "--ca-file",
        type=Path,
        help="optional additional CA bundle for TLS certificate verification",
    )
    connect.add_argument(
        "--server-hostname",
        help="TLS SNI and hostname-verification name (defaults to --host)",
    )
    connect.set_defaults(function=run_connect)
    read = subparsers.add_parser("read", help="read durable transcript events")
    read.add_argument("--transcript", required=True, type=Path)
    read.add_argument("--after", type=int)
    read.set_defaults(function=run_read)
    return parser


if __name__ == "__main__":
    parsed_args = build_parser().parse_args()
    sys.exit(parsed_args.function(parsed_args))
