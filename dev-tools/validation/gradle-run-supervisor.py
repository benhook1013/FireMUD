#!/usr/bin/env python3
"""Private lifecycle implementation for run-locked-gradle.sh (Linux local proof)."""

import argparse
import datetime
import fcntl
import math
import os
import secrets
import shlex
import signal
import subprocess
import sys
import time
from pathlib import Path

MARKER = "FIREMUD_GRADLE_RUN_OWNER"


def report(message, *, error=False):
    """A disappeared caller/output pipe must not interrupt owned cleanup."""
    try:
        os.write(2 if error else 1, (message + "\n").encode())
    except OSError:
        pass


def identity(pid):
    """Return start time and state without confusing spaces in process names."""
    try:
        fields = Path(f"/proc/{pid}/stat").read_text().rsplit(")", 1)[1].split()
        return fields[19], fields[0]
    except (FileNotFoundError, ProcessLookupError):
        return None


def alive(pid, started):
    current = identity(pid)
    return current is not None and current[0] == started and current[1] != "Z"


def owned_processes(token):
    marker = f"{MARKER}={token}".encode()
    found = {}
    for path in Path("/proc").iterdir():
        if not path.name.isdigit():
            continue
        try:
            if path.stat().st_uid != os.getuid():
                continue
            if marker not in (path / "environ").read_bytes().split(b"\0"):
                continue
            current = identity(int(path.name))
            if current and current[1] != "Z":
                found[int(path.name)] = current[0]
        except (FileNotFoundError, ProcessLookupError, PermissionError):
            continue
    return found


def positive_env(name, default):
    value = os.environ.get(name, str(default))
    error = f"{name} must be a positive integer number of seconds representable as a finite deadline"
    if not value.isdecimal():
        raise ValueError(error)
    try:
        seconds = int(value)
        finite = math.isfinite(time.monotonic() + seconds)
    except (ValueError, OverflowError):
        raise ValueError(error) from None
    if seconds < 1 or not finite:
        raise ValueError(error)
    return seconds


class Supervisor:
    def __init__(self, args):
        if not sys.platform.startswith("linux") or not Path("/proc/self/environ").exists():
            raise ValueError("The Gradle proof supervisor requires Linux /proc (use WSL on Windows)")
        self.args = args
        self.token = secrets.token_hex(24)
        self.cancel_status = None
        self.locks = []
        self.child = None
        self.owned = {}
        self.failed = False
        self.run_deadline = None
        self.cancellation_started = None
        self.local = os.environ.get("CI", "").lower() not in {"true", "1"}
        self.wait = os.environ.get("FIREMUD_LOCK_GRADLE_WAIT", "0")
        if self.wait not in {"0", "1"}:
            raise ValueError("FIREMUD_LOCK_GRADLE_WAIT must be 0 or 1")
        self.wait_seconds = positive_env("FIREMUD_LOCK_GRADLE_WAIT_SECONDS", 300)
        self.run_seconds = positive_env("FIREMUD_LOCK_GRADLE_RUN_SECONDS", 7200) if self.local else None
        self.grace_seconds = positive_env("FIREMUD_LOCK_GRADLE_CANCEL_SECONDS", 30) if self.local else 30
        self.wrapper_start = identity(args.wrapper_pid)
        if self.wrapper_start is None:
            raise ValueError("Gradle wrapper process is no longer alive")
        self.deadline = time.monotonic() + self.wait_seconds

    def cancel(self, sig, _frame):
        if self.cancel_status is None:
            self.cancel_status = 128 + sig

    def wrapper_gone(self):
        return not alive(self.args.wrapper_pid, self.wrapper_start[0])

    def fail(self, error):
        if not self.failed:
            report(f"Gradle proof ownership verification failed: {error}; retaining locks and cancelling.", error=True)
        self.failed = True
        if self.cancel_status is None:
            self.cancel_status = 1

    def remaining_owned(self):
        try:
            if self.local:
                self.owned.update(owned_processes(self.token))
            return {pid: started for pid, started in self.owned.items() if alive(pid, started)}
        except OSError as error:
            self.fail(error)
            return None

    def acquire(self, directory, target, shared=False):
        directory.mkdir(parents=True, exist_ok=True)
        name = target.replace(":", "__")
        handle = (directory / f"{name}.lock").open("a")
        meta = directory / f"{name}.meta"
        self.locks.append((handle, meta, not shared))
        mode = fcntl.LOCK_SH if shared else fcntl.LOCK_EX
        while True:
            if self.cancel_status is not None or self.wrapper_gone():
                return False
            try:
                fcntl.flock(handle.fileno(), mode | fcntl.LOCK_NB)
                break
            except BlockingIOError:
                if self.wait == "0" or time.monotonic() >= self.deadline:
                    report(f"Verification lock unavailable for {target}.", error=True)
                    try:
                        lines = meta.read_text().splitlines()
                        for label, value in zip(
                            ("Active lock owner PID", "Started at", "Working directory", "Command"), lines
                        ):
                            report(f"  {label}: {value}", error=True)
                    except FileNotFoundError:
                        report("  Lock owner metadata is unavailable.", error=True)
                    report("Use FIREMUD_LOCK_GRADLE_WAIT=1 for a bounded wait.", error=True)
                    return False
                time.sleep(min(0.1, max(0, self.deadline - time.monotonic())))
        if not shared:
            meta.write_text(
                f"{os.getpid()}\n{datetime.datetime.now(datetime.timezone.utc).isoformat()}\n"
                f"{Path.cwd()}\n{shlex.join(self.args.command)}\n{self.token}\n"
            )
        return True

    def release(self):
        for handle, meta, exclusive in reversed(self.locks):
            if exclusive:
                try:
                    if meta.read_text().splitlines()[-1] == self.token:
                        meta.unlink()
                except FileNotFoundError:
                    pass
            handle.close()

    def signal_owned(self, sig):
        # The marker admits ownership; the remembered start identity survives exec
        # into a clean environment. Recheck it immediately before every signal.
        for pid, started in self.owned.items():
            try:
                if alive(pid, started):
                    os.kill(pid, sig)
            except ProcessLookupError:
                pass
            except OSError as error:
                self.fail(error)

    def run(self):
        os.setsid()
        for sig in (signal.SIGINT, signal.SIGTERM, signal.SIGHUP):
            signal.signal(sig, self.cancel)
        if self.local:
            if "--daemon" in self.args.command:
                raise ValueError("Local serialized proof uses --no-daemon; explicit --daemon is unsupported")
            resource = Path(os.environ.get(
                "FIREMUD_LOCK_GRADLE_RESOURCE_DIR",
                str(Path(os.environ.get("XDG_CACHE_HOME") or str(Path.home() / ".cache"))
                    / "firemud" / "gradle-validation"),
            ))
            resource.mkdir(mode=0o700, parents=True, exist_ok=True)
            if resource.is_symlink() or resource.stat().st_uid != os.getuid() or resource.stat().st_mode & 0o077:
                raise ValueError("Local Gradle resource directory must be private, caller-owned, and not a symlink")
            if not self.acquire(resource, "local-resource"):
                return self.cancel_status or (143 if self.wrapper_gone() else 1)
        directory = Path(self.args.root) / ".gradle" / "firemud-validation-locks"
        for item in self.args.lock:
            mode, target = item.split("=", 1)
            if not self.acquire(directory, target, mode == "shared"):
                return self.cancel_status or (143 if self.wrapper_gone() else 1)
        report("Acquired verification lock(s): " + " ".join(self.args.lock))
        command = list(self.args.command)
        env = dict(os.environ)
        if self.local:
            command.append("--no-daemon")
            env[MARKER] = self.token
        self.child = subprocess.Popen(command, env=env, start_new_session=True, close_fds=True)
        self.run_deadline = time.monotonic() + self.run_seconds if self.run_seconds is not None else None
        try:
            launched = identity(self.child.pid)
            if self.local and launched is not None:
                # Popen proves ownership even if the first exec discards its marker.
                self.owned[self.child.pid] = launched[0]
        except OSError as error:
            self.fail(error)
        return self.supervise()

    def supervise(self):
        while True:
            remaining = self.remaining_owned()
            try:
                if self.local and self.child.pid not in self.owned:
                    launched = identity(self.child.pid)
                    if launched is not None:
                        self.owned[self.child.pid] = launched[0]
                status = self.child.poll()
                # Resolve a late fork after the first scan and before client exit.
                if status is not None and remaining == {}:
                    remaining = self.remaining_owned()
                    if remaining == {}:
                        return self.cancel_status or (128 - status if status < 0 else status)
            except OSError as error:
                self.fail(error)
                status = None
            if self.cancel_status is None:
                try:
                    if self.wrapper_gone():
                        self.cancel_status = 143
                        report("Gradle wrapper exited; cancelling its owned run.", error=True)
                    elif self.run_deadline is not None and time.monotonic() >= self.run_deadline:
                        self.cancel_status = 124
                        report("Gradle run budget exhausted; cancelling its owned run.", error=True)
                except OSError as error:
                    self.fail(error)
            if self.cancel_status is not None:
                if self.cancellation_started is None:
                    self.cancellation_started = time.monotonic()
                sig = signal.SIGTERM if time.monotonic() - self.cancellation_started < self.grace_seconds else signal.SIGKILL
                if self.local:
                    self.signal_owned(sig)
                elif status is None:
                    try:
                        os.killpg(self.child.pid, sig)
                    except ProcessLookupError:
                        pass
                    except OSError as error:
                        self.fail(error)
                # Keep locks indefinitely if an owned task cannot exit (for example uninterruptible I/O).
                # A bounded wait by contenders reports that condition without admitting overlapping work.
            time.sleep(0.1)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", required=True)
    parser.add_argument("--wrapper-pid", required=True, type=int)
    parser.add_argument("--lock", action="append", default=[])
    parser.add_argument("command", nargs=argparse.REMAINDER)
    args = parser.parse_args()
    if args.command[:1] == ["--"]:
        args.command.pop(0)
    supervisor = None
    try:
        supervisor = Supervisor(args)
        return supervisor.run()
    except (ValueError, OSError) as error:
        report(f"Gradle proof supervisor: {error}", error=True)
        # Retry verification and cancellation without releasing a launched run's locks.
        if supervisor is not None and supervisor.child is not None:
            supervisor.fail(error)
            return supervisor.supervise()
        return 1
    finally:
        if supervisor is not None:
            supervisor.release()


if __name__ == "__main__":
    sys.exit(main())
