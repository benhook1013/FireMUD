#!/usr/bin/env python3
"""Serve the local snapshot and refresh its read-only review data periodically."""

from __future__ import annotations

import argparse
import html
import json
import logging
import re
import subprocess
import sys
import threading
import time
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Callable
from urllib.parse import urlsplit

ROOT = Path(__file__).resolve().parent
OUTPUT = ROOT / "output"
DEFAULT_EXTERNAL_ORIGIN = "http://192.168.50.100:8877"
RENDER_SCRIPT = ROOT / "render.py"
PUBLISH_SCRIPT = ROOT / "publish-hetzner.py"
STATUS_CONFIG = ROOT / "status.json"
REFRESH_COOLDOWN_SECONDS = 15
AUTO_REFRESH_INTERVAL_SECONDS = 30 * 60
CONTROLLER_HELP_TIMEOUT_SECONDS = 10
HOSTED_SYNC_TIMEOUT_SECONDS = 90
LOG = logging.getLogger(__name__)
TIMING_LINE = re.compile(
    r"^STATUS_PAGE_TIMING stage=([a-z_]+) elapsed_seconds=([0-9]{1,3}(?:\.[0-9]{1,3})?) outcome=(ok|failed)$"
)
RENDER_TIMING_STAGES = frozenset({
    "controller_status", "github_listing", "routed_enrichment", "queue_history",
    "records_history", "html_render",
})


def _log_timing(stage: str, elapsed: float, outcome: str) -> None:
    LOG.info("status-page timing stage=%s elapsed_seconds=%.3f outcome=%s", stage, elapsed, outcome)


def _log_render_stage_timings(stderr: str) -> None:
    """Forward only fixed stage names and numeric durations from the renderer."""
    for line in stderr.splitlines():
        match = TIMING_LINE.fullmatch(line)
        if match is None or match.group(1) not in RENDER_TIMING_STAGES:
            continue
        elapsed = float(match.group(2))
        if elapsed <= 180:
            _log_timing(f"render.{match.group(1)}", elapsed, match.group(3))


def run_local_script(script: Path, timeout: int) -> None:
    started_at = time.monotonic()
    stage = (
        "render_process" if script == RENDER_SCRIPT
        else "publish_process" if script == PUBLISH_SCRIPT
        else "local_script"
    )
    try:
        result = subprocess.run(
            [sys.executable, str(script)],
            cwd=ROOT,
            capture_output=True,
            text=True,
            timeout=timeout,
            check=False,
        )
    except (OSError, subprocess.SubprocessError):
        _log_timing(stage, max(0.0, time.monotonic() - started_at), "failed")
        raise
    if script == RENDER_SCRIPT:
        _log_render_stage_timings(result.stderr)
    outcome = "ok" if result.returncode == 0 else "failed"
    _log_timing(stage, max(0.0, time.monotonic() - started_at), outcome)
    if result.returncode:
        raise RuntimeError(f"{Path(script).name} exited {result.returncode}: {result.stderr[-1200:]}")


def render_snapshot() -> None:
    run_local_script(RENDER_SCRIPT, 180)


def publish_snapshot() -> None:
    run_local_script(PUBLISH_SCRIPT, 300)


def sync_hosted_reviews() -> str:
    """Sync completed Hosted reviews only through the configured capable controller."""
    config = json.loads(STATUS_CONFIG.read_text(encoding="utf-8"))
    if not isinstance(config, dict):
        raise ValueError("status config must be an object")
    configured = config.get("review_tool")
    if configured is None:
        return "unconfigured"
    if not isinstance(configured, str) or not Path(configured).is_absolute():
        raise ValueError("review_tool must be an absolute path")
    tool = Path(configured)
    command = [sys.executable, str(tool), "records"]
    help_result = subprocess.run(
        [*command, "--help"],
        cwd=tool.parent.parent,
        capture_output=True,
        text=True,
        timeout=CONTROLLER_HELP_TIMEOUT_SECONDS,
        check=False,
    )
    if help_result.returncode:
        raise RuntimeError(f"controller records help exited {help_result.returncode}: {help_result.stderr[-1200:]}")
    if not re.search(r"(?m)^\s*sync-hosted\s", help_result.stdout):
        return "unsupported"
    started_at = time.monotonic()
    try:
        result = subprocess.run(
            [*command, "sync-hosted"],
            cwd=tool.parent.parent,
            capture_output=True,
            text=True,
            timeout=HOSTED_SYNC_TIMEOUT_SECONDS,
            check=False,
        )
    except (OSError, subprocess.SubprocessError):
        _log_timing("hosted_sync", max(0.0, time.monotonic() - started_at), "failed")
        raise
    _log_timing("hosted_sync", max(0.0, time.monotonic() - started_at),
                "ok" if result.returncode == 0 else "failed")
    if result.returncode:
        raise RuntimeError(f"records sync-hosted exited {result.returncode}: {result.stderr[-1200:]}")
    return "complete"


class StatusServer(ThreadingHTTPServer):
    daemon_threads = True

    def __init__(self, address: tuple[str, int], directory: Path,
                 render: Callable[[], None] = render_snapshot,
                 publish: Callable[[], None] = publish_snapshot,
                 auto_refresh_interval: float = AUTO_REFRESH_INTERVAL_SECONDS,
                 external_origin: str = DEFAULT_EXTERNAL_ORIGIN,
                 sync_hosted: Callable[[], str] = sync_hosted_reviews):
        parsed_origin = urlsplit(external_origin)
        if (parsed_origin.scheme != "http" or not parsed_origin.netloc or parsed_origin.path
                or parsed_origin.query or parsed_origin.fragment or parsed_origin.username
                or parsed_origin.password):
            raise ValueError("external origin must be an http origin without a path or credentials")
        self.render = render
        self.publish = publish
        self.sync_hosted = sync_hosted
        self.hosted_sync_status = "not_run"
        self.external_origin = external_origin.rstrip("/")
        self.external_host = parsed_origin.netloc
        self.phase = "idle"
        self.refresh_lock = threading.Lock()
        self.next_refresh_at = 0.0
        self.auto_refresh_interval = auto_refresh_interval
        self.next_auto_refresh_at = time.monotonic() + auto_refresh_interval
        self.scheduler_wakeup = threading.Event()
        self.scheduler_stop = threading.Event()
        self.scheduler_thread: threading.Thread | None = None
        super().__init__(address, lambda *args, **kwargs: StatusHandler(*args, directory=str(directory), **kwargs))

    def refresh_once(self) -> str:
        """Run one non-overlapping refresh, returning its result for the caller."""
        if not self.refresh_lock.acquire(blocking=False):
            return "busy"
        started = False
        try:
            if time.monotonic() < self.next_refresh_at:
                if self.phase in {"render_failed", "publish_failed"}:
                    return "failure_backoff"
                return "cooldown"
            started = True
            try:
                self.phase = "rendering"
                self.render()
            except (OSError, subprocess.SubprocessError, RuntimeError) as error:
                self.phase = "render_failed"
                LOG.error("status render failed: %s", error)
                return self.phase
            try:
                self.phase = "publishing"
                self.publish()
            except (OSError, subprocess.SubprocessError, RuntimeError) as error:
                self.phase = "publish_failed"
                LOG.error("status publish failed: %s", error)
                return self.phase
            self.phase = "complete"
            return self.phase
        finally:
            if started:
                finished_at = time.monotonic()
                self.next_refresh_at = finished_at + REFRESH_COOLDOWN_SECONDS
                self.next_auto_refresh_at = finished_at + self.auto_refresh_interval
                self.scheduler_wakeup.set()
            self.refresh_lock.release()

    def _auto_refresh_loop(self) -> None:
        while not self.scheduler_stop.is_set():
            remaining = max(0.0, self.next_auto_refresh_at - time.monotonic())
            if self.scheduler_wakeup.wait(remaining):
                self.scheduler_wakeup.clear()
                continue
            if self.scheduler_stop.is_set():
                break
            self.hosted_sync_status = "running"
            try:
                self.hosted_sync_status = self.sync_hosted()
            except Exception as error:
                self.hosted_sync_status = "failed"
                LOG.error("hosted review sync failed: %s", error)
            result = self.refresh_once()
            if result in {"cooldown", "failure_backoff"}:
                self.next_auto_refresh_at = max(self.next_auto_refresh_at, self.next_refresh_at)
            elif result == "busy":
                # The manual job will wake the scheduler when it finishes.
                self.scheduler_wakeup.wait()
                self.scheduler_wakeup.clear()

    def serve_forever(self, poll_interval: float = 0.5) -> None:
        self.scheduler_thread = threading.Thread(target=self._auto_refresh_loop, name="status-auto-refresh", daemon=True)
        self.scheduler_thread.start()
        try:
            super().serve_forever(poll_interval=poll_interval)
        finally:
            self.scheduler_stop.set()
            self.scheduler_wakeup.set()
            self.scheduler_thread.join(timeout=1)

    def shutdown(self) -> None:
        self.scheduler_stop.set()
        self.scheduler_wakeup.set()
        super().shutdown()


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
        if (self.headers.get("Host") != self.server.external_host
                or self.headers.get("Origin") != self.server.external_origin):
            self._message(403, "Refresh refused", "Open the local status page and use its refresh button.")
            return
        if self.headers.get("Transfer-Encoding") or self.headers.get("Content-Length", "0") != "0":
            self._message(400, "Refresh refused", "The refresh request must have no body.")
            return
        result = self.server.refresh_once()
        if result == "busy":
            self._message(409, "Refresh in progress", "Another refresh is already running.")
            return
        if result == "cooldown":
            self._message(429, "Refresh recently completed", "Wait a few seconds before trying again.")
            return
        if result == "failure_backoff":
            self._message(503, "Previous refresh failed", "The last refresh failed. Wait briefly before retrying, and check the server log for details.")
            return
        if result == "render_failed":
            self._message(502, "Local refresh failed", "The previous local and public snapshots are still available. Check the server log.")
            return
        if result == "publish_failed":
            self._message(503, "Public publish failed", "The local page was updated, but the public page was not confirmed. Check the server log.")
            return
        self.send_response(303)
        self.send_header("Location", "/")
        self.send_header("Content-Length", "0")
        self.end_headers()

    def do_GET(self) -> None:
        if self.path == "/refresh-status":
            body = json.dumps({"phase": self.server.phase,
                               "hosted_sync": self.server.hosted_sync_status}).encode()
            self.send_response(200)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return
        if self.path == "/refresh":
            self._message(405, "Use the refresh button", "The refresh endpoint accepts only the page's form.")
            return
        super().do_GET()


def main() -> None:
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--bind", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8877)
    parser.add_argument("--directory", type=Path, default=OUTPUT)
    parser.add_argument("--external-origin", default=DEFAULT_EXTERNAL_ORIGIN)
    args = parser.parse_args()
    if not (args.directory / "index.html").is_file():
        parser.error("rendered index.html is missing")
    with StatusServer((args.bind, args.port), args.directory, external_origin=args.external_origin) as server:
        print(f"FireMUD local status page at http://{args.bind}:{args.port}/", flush=True)
        server.serve_forever(poll_interval=0.25)


if __name__ == "__main__":
    main()
