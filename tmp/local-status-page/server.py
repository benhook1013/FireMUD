#!/usr/bin/env python3
"""Serve the local snapshot and refresh its read-only review data on demand."""

from __future__ import annotations

import argparse
import html
import subprocess
import threading
import time
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Callable

ROOT = Path(__file__).resolve().parent
OUTPUT = ROOT / "output"
WSL_RENDER = "/home/ben/src/FireMUD-project-direction/tmp/local-status-page/render.py"
REFRESH_COOLDOWN_SECONDS = 15


def refresh_snapshot() -> None:
    result = subprocess.run(
        ["wsl.exe", "-d", "Ubuntu-22.04", "--exec", "python3", WSL_RENDER],
        capture_output=True,
        text=True,
        timeout=180,
        check=False,
    )
    if result.returncode:
        raise RuntimeError(f"render exited {result.returncode}: {result.stderr[-1200:]}")


class StatusServer(ThreadingHTTPServer):
    daemon_threads = True

    def __init__(self, address: tuple[str, int], directory: Path, refresh: Callable[[], None] = refresh_snapshot):
        self.refresh = refresh
        self.refresh_lock = threading.Lock()
        self.next_refresh_at = 0.0
        super().__init__(address, lambda *args, **kwargs: StatusHandler(*args, directory=str(directory), **kwargs))


class StatusHandler(SimpleHTTPRequestHandler):
    server: StatusServer

    def end_headers(self) -> None:
        self.send_header("Cache-Control", "no-store")
        super().end_headers()

    def _message(self, status: int, title: str, detail: str) -> None:
        body = (
            "<!doctype html><meta charset='utf-8'><title>FireMUD status refresh</title>"
            f"<h1>{html.escape(title)}</h1><p>{html.escape(detail)}</p><p><a href='/'>Back to status</a></p>"
        ).encode()
        self.send_response(status)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_POST(self) -> None:
        if self.path != "/refresh":
            self._message(404, "Not found", "This server only refreshes the status page.")
            return
        bound_host = f"{self.server.server_address[0]}:{self.server.server_address[1]}"
        if self.headers.get("Host") != bound_host or self.headers.get("Origin") != f"http://{bound_host}":
            self._message(403, "Refresh refused", "Open the local status page and use its refresh button.")
            return
        if self.headers.get("Transfer-Encoding") or self.headers.get("Content-Length", "0") != "0":
            self._message(400, "Refresh refused", "The refresh request must have no body.")
            return
        if not self.server.refresh_lock.acquire(blocking=False):
            self._message(409, "Refresh in progress", "Another refresh is already running.")
            return
        try:
            if time.monotonic() < self.server.next_refresh_at:
                self._message(429, "Refresh recently completed", "Wait a few seconds before trying again.")
                return
            try:
                self.server.refresh()
            except (OSError, subprocess.SubprocessError, RuntimeError) as error:
                self.log_error("status refresh failed: %s", error)
                self._message(502, "Refresh failed", "The old snapshot is still available. Check the server log.")
                return
            self.send_response(303)
            self.send_header("Location", "/")
            self.send_header("Content-Length", "0")
            self.end_headers()
        finally:
            self.server.next_refresh_at = time.monotonic() + REFRESH_COOLDOWN_SECONDS
            self.server.refresh_lock.release()

    def do_GET(self) -> None:
        if self.path == "/refresh":
            self._message(405, "Use the refresh button", "The refresh endpoint accepts only the page's form.")
            return
        super().do_GET()


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--bind", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8877)
    parser.add_argument("--directory", type=Path, default=OUTPUT)
    args = parser.parse_args()
    if not (args.directory / "index.html").is_file():
        parser.error("rendered index.html is missing")
    with StatusServer((args.bind, args.port), args.directory) as server:
        print(f"FireMUD local status page at http://{args.bind}:{args.port}/", flush=True)
        server.serve_forever(poll_interval=0.25)


if __name__ == "__main__":
    main()
