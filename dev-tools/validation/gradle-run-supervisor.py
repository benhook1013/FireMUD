#!/usr/bin/env python3
"""Private lifecycle implementation for run-locked-gradle.sh (Linux local proof)."""

import argparse
import datetime
import fcntl
import json
import math
import os
import queue
import re
import secrets
import select
import shlex
import signal
import stat
import subprocess
import sys
import tempfile
import threading
import time
from pathlib import Path

MARKER = "FIREMUD_GRADLE_RUN_OWNER"
SESSION_PROCESS_MARKER = "FIREMUD_LOCAL_RESOURCE_PROCESS_OWNER"


# A bounded daemon writer may block on the caller's pipe; supervision never does.
# Do not change O_NONBLOCK on duplicated descriptors: that would affect the caller
# and Gradle, which share the underlying open file description.
DIAGNOSTICS = queue.Queue(maxsize=32)


def write_diagnostics():
    while True:
        fd, message, completed = DIAGNOSTICS.get()
        try:
            os.write(fd, message)
        except OSError:
            pass
        finally:
            completed.set()
            DIAGNOSTICS.task_done()


threading.Thread(target=write_diagnostics, daemon=True).start()
LAST_DIAGNOSTIC = None


def report(message, *, error=False):
    global LAST_DIAGNOSTIC
    completed = threading.Event()
    try:
        DIAGNOSTICS.put_nowait((2 if error else 1, (message + "\n").encode()[:4096], completed))
        LAST_DIAGNOSTIC = completed
    except queue.Full:
        pass


def identity(pid):
    """Return start time and state without confusing spaces in process names."""
    try:
        # Linux comm is arbitrary bytes, including invalid UTF-8. Only the
        # ASCII start-time/state fields after its final ')' are interpreted.
        fields = Path(f"/proc/{pid}/stat").read_bytes().rsplit(b")", 1)[1].split()
        return fields[19].decode("ascii"), fields[0].decode("ascii")
    except (FileNotFoundError, ProcessLookupError):
        return None


def alive(pid, started):
    current = identity(pid)
    return current is not None and current[0] == started and current[1] != "Z"


class OwnedProcess:
    """A pidfd binds cancellation to one process, even after its PID is reused."""

    def __init__(self, pid, started, descriptor):
        self.pid = pid
        self.started = started
        self.descriptor = descriptor

    def running(self):
        poller = select.poll()
        poller.register(self.descriptor, select.POLLIN)
        return not poller.poll(0)

    def send(self, sig):
        signal.pidfd_send_signal(self.descriptor, sig)

    def close(self):
        os.close(self.descriptor)


def admit_process(pid, token=None, marker=MARKER):
    descriptor = os.pidfd_open(pid)
    owned = None
    try:
        # Every proc observation happens while this exact handle is still live.
        # If the PID is recycled during the reads, its old pidfd becomes ready
        # and the replacement's observations cannot admit it as owned.
        owned = OwnedProcess(pid, None, descriptor)
        if not owned.running():
            return None
        path = Path(f"/proc/{pid}")
        if path.stat().st_uid != os.getuid():
            return None
        current = identity(pid)
        if current is None or current[1] == "Z":
            return None
        if token is not None and f"{marker}={token}".encode() not in (path / "environ").read_bytes().split(b"\0"):
            return None
        latest = identity(pid)
        if latest is None or latest[0] != current[0] or latest[1] == "Z":
            return None
        if path.stat().st_uid != os.getuid() or not owned.running():
            return None
        owned.started = current[0]
        result, owned = owned, None
        return result
    finally:
        if owned is not None:
            owned.close()


def owned_processes(token, remembered, marker=MARKER):
    found = {}
    try:
        for path in Path("/proc").iterdir():
            if not path.name.isdigit() or int(path.name) in remembered:
                continue
            try:
                if path.stat().st_uid != os.getuid():
                    continue
                process = admit_process(int(path.name), token, marker)
                if process is not None:
                    found[process.pid] = process
            except (FileNotFoundError, ProcessLookupError, PermissionError):
                continue
        return found
    except OSError:
        for process in found.values():
            process.close()
        raise


def require_pidfds():
    if not hasattr(os, "pidfd_open") or not hasattr(signal, "pidfd_send_signal"):
        raise ValueError("Local Gradle supervision requires Linux pidfd support in Python and the kernel")
    try:
        descriptor = os.pidfd_open(os.getpid())
        try:
            signal.pidfd_send_signal(descriptor, 0)
        finally:
            os.close(descriptor)
    except OSError as error:
        raise ValueError(f"Local Gradle supervision requires working Linux pidfds: {error}") from error


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


SESSION_FD = "FIREMUD_LOCAL_RESOURCE_SESSION_FD"
SESSION_PID = "FIREMUD_LOCAL_RESOURCE_SESSION_PID"
SESSION_START = "FIREMUD_LOCAL_RESOURCE_SESSION_START"
SESSION_TOKEN = "FIREMUD_LOCAL_RESOURCE_SESSION_OWNER"


def resource_directory():
    return Path(os.environ.get(
        "FIREMUD_LOCK_GRADLE_RESOURCE_DIR",
        str(Path(os.environ.get("XDG_CACHE_HOME") or str(Path.home() / ".cache"))
            / "firemud" / "gradle-validation"),
    ))


def read_resource_metadata(path):
    lines = path.read_text().splitlines()
    if len(lines) not in {5, 6}:
        raise ValueError("local resource metadata is malformed; scoped recovery is required")
    projects = []
    if len(lines) == 6:
        state = json.loads(lines[5])
        if not isinstance(state, dict) or set(state) != {"runtimeProjects"}:
            raise ValueError("local runtime resource state is malformed")
        projects = state["runtimeProjects"]
        if not isinstance(projects, list) or any(not isinstance(project, str) for project in projects):
            raise ValueError("local runtime project identities are malformed")
        if len(projects) != len(set(projects)):
            raise ValueError("local runtime project identities are malformed")
        for project in projects:
            if not isinstance(project, str) or not re.fullmatch(r"(?:firemud-smoke-|smoke-full-)[a-z0-9][a-z0-9-]{0,127}", project):
                raise ValueError("local runtime project identity is malformed")
    return lines[:5], projects


def check_runtime_projects(projects):
    """Inspect only recorded projects, never enumerate other Docker deployments."""
    for project in projects:
        try:
            result = subprocess.run(
                ["docker", "ps", "-a", "--filter", f"label=com.docker.compose.project={project}",
                 "--format", "{{.State}}"], stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
                text=True, timeout=5, check=True,
            )
        except (OSError, subprocess.SubprocessError) as error:
            raise ValueError(
                f"Unresolved local runtime project {project}: container state is unavailable; "
                "retry after restoring Docker inspection or perform original-capability scoped teardown"
            ) from error
        states = result.stdout.splitlines()
        if any(state not in {"created", "exited", "dead"} for state in states):
            raise ValueError(
                f"Unresolved local runtime project {project}: running or unknown container state; "
                "perform original-capability scoped teardown, then retry"
            )


def register_runtime_project(project):
    fd = verify_resource_session()
    if not re.fullmatch(r"(?:firemud-smoke-|smoke-full-)[a-z0-9][a-z0-9-]{0,127}", project):
        raise ValueError("invalid exact runtime project identity")
    # POSIX byte-range locking on the existing canonical resource inode serializes
    # metadata updates among children sharing its inherited flock description.
    fcntl.lockf(fd, fcntl.LOCK_EX)
    try:
        metadata = resource_directory() / "local-resource.meta"
        lines, projects = read_resource_metadata(metadata)
        if lines[0] != os.environ[SESSION_PID] or lines[4] != os.environ[SESSION_TOKEN]:
            raise ValueError("runtime registration no longer matches the owning session")
        if project not in projects:
            projects.append(project)
        payload = "\n".join(lines) + "\n" + json.dumps({"runtimeProjects": projects}) + "\n"
        # Atomic durable replacement prevents owner death from tearing identities.
        temporary_fd, temporary_name = tempfile.mkstemp(dir=metadata.parent, prefix=".resource-meta-")
        try:
            with os.fdopen(temporary_fd, "w") as target:
                target.write(payload)
                target.flush()
                os.fsync(target.fileno())
            os.replace(temporary_name, metadata)
            directory_fd = os.open(metadata.parent, os.O_RDONLY | os.O_DIRECTORY)
            try:
                os.fsync(directory_fd)
            finally:
                os.close(directory_fd)
        finally:
            try:
                os.unlink(temporary_name)
            except FileNotFoundError:
                pass
    finally:
        fcntl.lockf(fd, fcntl.LOCK_UN)


def verify_resource_session():
    """Verify inherited open-file-description ownership, never an env flag."""
    try:
        fd = int(os.environ[SESSION_FD])
        pid = int(os.environ[SESSION_PID])
        started = os.environ[SESSION_START]
        token = os.environ[SESSION_TOKEN]
        if fd < 3 or not token or not started.isdecimal():
            raise ValueError("invalid session identity")
        require_pidfds()
        owner = admit_process(pid)
        if owner is None:
            raise ValueError("resource session owner is not live")
        try:
            directory = resource_directory()
            if directory.is_symlink() or directory.stat().st_uid != os.getuid() or directory.stat().st_mode & 0o077:
                raise ValueError("invalid resource directory")
            expected = (directory / "local-resource.lock").stat()
            inherited = os.fstat(fd)
            original = Path(f"/proc/{pid}/fd/{fd}").stat()
            if not stat.S_ISREG(inherited.st_mode) or inherited.st_uid != os.getuid():
                raise ValueError("invalid inherited lock descriptor")
            if any((item.st_dev, item.st_ino) != (expected.st_dev, expected.st_ino)
                   for item in (inherited, original)):
                raise ValueError("resource descriptor does not match owning session")
            metadata, _projects = read_resource_metadata(directory / "local-resource.meta")
            if metadata[0] != str(pid) or metadata[4] != token:
                raise ValueError("resource session metadata does not match")
            if owner.started != started or not owner.running():
                raise ValueError("resource session owner changed")
            # fdinfo describes locks owned by this open file description;
            # checking never turns an unheld/foreign descriptor into admission.
            lock_lines = Path(f"/proc/self/fdinfo/{fd}").read_text().splitlines()
            held = [line.split() for line in lock_lines if line.startswith("lock:")]
            if not any(len(fields) >= 9 and fields[2:5] == ["FLOCK", "ADVISORY", "WRITE"]
                       and fields[5] == str(pid) for fields in held):
                raise ValueError("inherited descriptor has no exclusive session ownership")
            contender = (directory / "local-resource.lock").open("a")
            try:
                try:
                    fcntl.flock(contender.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
                except BlockingIOError:
                    pass
                else:
                    raise ValueError("resource session does not exclude contenders")
            finally:
                contender.close()
            if not owner.running():
                raise ValueError("resource session owner ended")
            return fd
        finally:
            owner.close()
    except (KeyError, ValueError, OSError) as error:
        raise ValueError(f"Invalid inherited local resource session: {error}") from error


class Supervisor:
    def __init__(self, args):
        if not sys.platform.startswith("linux") or not Path("/proc/self/environ").exists():
            raise ValueError("The Gradle proof supervisor requires Linux /proc (use WSL on Windows)")
        self.args = args
        self.token = secrets.token_hex(24)
        self.cancel_status = None
        self.cancel_fd = getattr(args, "cancel_fd", None)
        self.cancel_buffer = b""
        if self.cancel_fd is not None:
            os.set_blocking(self.cancel_fd, False)
        self.locks = []
        self.child = None
        self.owned = {}
        self.failed = False
        self.run_deadline = None
        self.cancellation_started = None
        self.session_fd = verify_resource_session() if SESSION_FD in os.environ else None
        self.resource_session = getattr(args, "resource_session", False)
        self.owns_resource_session = self.resource_session and self.session_fd is None
        self.local = self.session_fd is not None or os.environ.get("CI", "").lower() not in {"true", "1"}
        self.client_admitted = False
        if self.local:
            require_pidfds()
        self.wait = os.environ.get("FIREMUD_LOCK_GRADLE_WAIT", "0")
        if self.wait not in {"0", "1"}:
            raise ValueError("FIREMUD_LOCK_GRADLE_WAIT must be 0 or 1")
        self.wait_seconds = positive_env("FIREMUD_LOCK_GRADLE_WAIT_SECONDS", 300)
        self.run_seconds = positive_env("FIREMUD_LOCK_GRADLE_RUN_SECONDS", 7200) if self.local else None
        self.grace_seconds = positive_env("FIREMUD_LOCK_GRADLE_CANCEL_SECONDS", 30) if self.local else 30
        self.wrapper_start = args.wrapper_start
        if not self.wrapper_start.isdecimal() or not alive(args.wrapper_pid, self.wrapper_start):
            raise ValueError("Gradle wrapper process is no longer alive")
        self.deadline = time.monotonic() + self.wait_seconds

    def cancel(self, sig, _frame):
        if self.cancel_status is None:
            self.cancel_status = 128 + sig

    def check_cancel(self):
        if self.cancel_fd is None or self.cancel_status is not None:
            return
        try:
            self.cancel_buffer += os.read(self.cancel_fd, 16)
        except BlockingIOError:
            return
        if b"\n" in self.cancel_buffer or len(self.cancel_buffer) >= 16:
            request = self.cancel_buffer.split(b"\n", 1)[0]
            self.cancel_buffer = b""
            if request in {b"129", b"130", b"143"}:
                self.cancel_status = int(request)
            else:
                self.fail("invalid graceful cancellation request")

    def wrapper_gone(self):
        return not alive(self.args.wrapper_pid, self.wrapper_start)

    def fail(self, error):
        if not self.failed:
            report(f"Gradle proof ownership verification failed: {error}; retaining locks and cancelling.", error=True)
        self.failed = True
        if self.cancel_status is None:
            self.cancel_status = 1

    def remaining_owned(self):
        try:
            if self.local:
                for pid, process in list(self.owned.items()):
                    if not process.running():
                        process.close()
                        del self.owned[pid]
                if self.owns_resource_session:
                    self.owned.update(owned_processes(self.token, self.owned, SESSION_PROCESS_MARKER))
                else:
                    self.owned.update(owned_processes(self.token, self.owned))
            return dict(self.owned)
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
            self.check_cancel()
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
            if target == "local-resource" and meta.exists():
                try:
                    _old_owner, projects = read_resource_metadata(meta)
                    check_runtime_projects(projects)
                except (ValueError, OSError) as error:
                    report(str(error), error=True)
                    return False
            meta.write_text(
                f"{os.getpid()}\n{datetime.datetime.now(datetime.timezone.utc).isoformat()}\n"
                f"{json.dumps(str(Path.cwd()))}\n{json.dumps(shlex.join(self.args.command))}\n{self.token}\n"
            )
        return True

    def release(self):
        if self.cancel_fd is not None:
            os.close(self.cancel_fd)
            self.cancel_fd = None
        for process in self.owned.values():
            process.close()
        self.owned.clear()
        for handle, meta, exclusive in reversed(self.locks):
            if exclusive:
                try:
                    if meta.name == "local-resource.meta":
                        owner, projects = read_resource_metadata(meta)
                        if owner[4] == self.token and not projects:
                            meta.unlink()
                    elif meta.read_text().splitlines()[-1] == self.token:
                        meta.unlink()
                except FileNotFoundError:
                    pass
                except (ValueError, OSError) as error:
                    report(f"Retaining unresolved local resource metadata: {error}", error=True)
            handle.close()

    def signal_owned(self, sig):
        # Admission validates the marker/UID/start identity against a live
        # pidfd. The retained handle stays authoritative after environment loss.
        for process in self.owned.values():
            try:
                process.send(sig)
            except ProcessLookupError:
                pass
            except OSError as error:
                self.fail(error)

    def admit_client(self):
        if self.local and not self.client_admitted:
            # Capture before poll/wait can reap our child and free its PID.
            if self.child.pid in self.owned:
                self.client_admitted = True
                return
            process = admit_process(self.child.pid)
            if process is not None:
                self.owned[self.child.pid] = process
            self.client_admitted = True

    def run(self):
        os.setsid()
        for sig in (signal.SIGINT, signal.SIGTERM, signal.SIGHUP):
            signal.signal(sig, self.cancel)
        if self.local:
            if not self.resource_session and "--daemon" in self.args.command:
                raise ValueError("Local serialized proof uses --no-daemon; explicit --daemon is unsupported")
            resource = resource_directory()
            resource.mkdir(mode=0o700, parents=True, exist_ok=True)
            if resource.is_symlink() or resource.stat().st_uid != os.getuid() or resource.stat().st_mode & 0o077:
                raise ValueError("Local Gradle resource directory must be private, caller-owned, and not a symlink")
            if self.session_fd is None:
                if not self.acquire(resource, "local-resource"):
                    return self.cancel_status or (143 if self.wrapper_gone() else 1)
                if self.resource_session:
                    self.session_fd = self.locks[-1][0].fileno()
        directory = Path(self.args.root) / ".gradle" / "firemud-validation-locks"
        for item in self.args.lock:
            mode, target = item.split("=", 1)
            if not self.acquire(directory, target, mode == "shared"):
                return self.cancel_status or (143 if self.wrapper_gone() else 1)
        report("Acquired verification lock(s): " + " ".join(self.args.lock))
        command = list(self.args.command)
        env = dict(os.environ)
        if self.local:
            if not self.resource_session:
                command.append("--no-daemon")
            env[MARKER] = self.token
        if self.local:
            # An unreaped direct child cannot have its PID reused before admission.
            signal.signal(signal.SIGCHLD, signal.SIG_DFL)
        if self.local and self.resource_session and SESSION_FD not in env:
            env.update({SESSION_FD: str(self.session_fd), SESSION_PID: str(os.getpid()),
                        SESSION_START: identity(os.getpid())[0], SESSION_TOKEN: self.token,
                        SESSION_PROCESS_MARKER: self.token})
        pass_fds = (self.session_fd,) if self.session_fd is not None else ()
        self.child = subprocess.Popen(command, env=env, start_new_session=True, close_fds=True, pass_fds=pass_fds)
        self.run_deadline = time.monotonic() + self.run_seconds if self.run_seconds is not None else None
        try:
            self.admit_client()
        except (FileNotFoundError, ProcessLookupError):
            self.client_admitted = True
        except OSError as error:
            self.fail(error)
        return self.supervise()

    def supervise(self):
        term_sent = False
        while True:
            try:
                self.check_cancel()
            except OSError as error:
                self.fail(error)
            remaining = self.remaining_owned()
            try:
                self.admit_client()
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
                    if sig == signal.SIGKILL or not self.resource_session or not term_sent:
                        self.signal_owned(sig)
                        term_sent = True
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
    parser.add_argument("--verify-resource-session", action="store_true")
    if len(sys.argv) == 3 and sys.argv[1] == "--register-runtime-project":
        try:
            register_runtime_project(sys.argv[2])
            return 0
        except (ValueError, OSError) as error:
            report(str(error), error=True)
            LAST_DIAGNOSTIC.wait(0.05)
            return 1
    if sys.argv[1:] == ["--verify-resource-session"]:
        try:
            verify_resource_session()
            return 0
        except ValueError as error:
            report(str(error), error=True)
            LAST_DIAGNOSTIC.wait(0.05)
            return 1
    parser.add_argument("--resource-session", action="store_true")
    parser.add_argument("--root", required=True)
    parser.add_argument("--wrapper-pid", required=True, type=int)
    parser.add_argument("--wrapper-start", required=True)
    parser.add_argument("--cancel-fd", required=True, type=int)
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
        # Give normal diagnostics a short opportunity to finish only after
        # cleanup and lock release; an unread pipe must never delay ownership.
        if LAST_DIAGNOSTIC is not None:
            LAST_DIAGNOSTIC.wait(0.05)


if __name__ == "__main__":
    sys.exit(main())
