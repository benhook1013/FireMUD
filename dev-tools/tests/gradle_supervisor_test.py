"""Behavioral proof for the canonical wrapper without starting repository Gradle."""

import importlib.util
import json
import os
import re
import shlex
import shutil
import signal
import subprocess
import tempfile
import time
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest import mock

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location("gradle_supervisor", ROOT / "dev-tools/validation/gradle-run-supervisor.py")
SUPERVISOR = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(SUPERVISOR)


class GradleSupervisorTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="firemud-gradle-supervisor-test-")
        self.root = Path(self.temp.name)
        self.resource = self.root / "resource"
        self.bin = self.root / "mock-bin"
        self.bin.mkdir()
        self.docker_state = self.root / "docker-state.json"
        self.docker_state.write_text("{}")
        docker = self.bin / "docker"
        docker.write_text("""#!/usr/bin/env python3
import json, os, pathlib, sys
arguments = sys.argv[1:]
state_path = pathlib.Path(os.environ['MOCK_DOCKER_STATE'])
all_state = json.loads(state_path.read_text())
if arguments and arguments[0] == 'compose':
    project = os.environ['COMPOSE_PROJECT_NAME']
    if 'down' in arguments: all_state.pop(project, None)
    elif 'up' in arguments or 'restart' in arguments: all_state[project] = ['running']
    else: raise SystemExit('unexpected mock Compose command')
    state_path.write_text(json.dumps(all_state))
    raise SystemExit(0)
if arguments[:2] in (['network', 'ls'], ['volume', 'ls']):
    if len(arguments) != 6 or arguments[2] != '--filter' or not arguments[3].startswith('label=com.docker.compose.project='):
        raise SystemExit('unscoped mock resource command')
    raise SystemExit(0)
if len(arguments) != 6 or arguments[:3] != ['ps', '-a', '--filter'] or arguments[4] != '--format' or arguments[5] not in ('{{.State}}', '{{.ID}}'):
    raise SystemExit('unexpected mock Docker command')
prefix = 'label=com.docker.compose.project='
if not arguments[3].startswith(prefix): raise SystemExit('missing exact project')
project = arguments[3][len(prefix):]
state = all_state.get(project, [])
if state == 'unavailable': raise SystemExit(93)
for value in state: print(value if arguments[5] == '{{.State}}' else 'mock-container')
""")
        docker.chmod(0o755)
        self.processes = []
        self.logs = []
        self.fixture = self.root / "fake-gradle"
        self.fixture.write_text("""#!/usr/bin/env python3
import os, pathlib, signal, subprocess, sys, time
root = pathlib.Path(os.environ['FIXTURE_DIR'])
(root / 'args').write_text(' '.join(sys.argv[1:]))
(root / 'ready').write_text(str(os.getpid()))
fds = []
for fd in pathlib.Path('/proc/self/fd').iterdir():
    try: fds.append(os.readlink(fd))
    except FileNotFoundError: pass
(root / 'fds').write_text(str(fds))
mode = os.environ.get('FIXTURE_MODE', 'hold')
if mode == 'exit': sys.exit(23)
if mode == 'stdin':
    (root / 'stdin').write_text(sys.stdin.readline())
    sys.exit(0)
if mode == 'worker':
    worker = subprocess.Popen([sys.executable, '-c', 'import os,pathlib,signal,time; pathlib.Path(os.environ["FIXTURE_DIR"],"worker").write_text(str(os.getpid())); signal.signal(signal.SIGTERM, signal.SIG_IGN); time.sleep(60)'], start_new_session=True)
    while not (root / 'worker').exists(): time.sleep(.01)
    sys.exit(0)
if mode == 'nonutf8':
    import ctypes
    ctypes.CDLL(None).prctl(15, ctypes.c_char_p(bytes([110, 97, 116, 105, 118, 101, 45, 255])), 0, 0, 0)
    (root / 'renamed').write_text(str(os.getpid()))
    signal.signal(signal.SIGTERM, signal.SIG_IGN)
if mode == 'cleanenv':
    os.execve(sys.executable, [sys.executable, '-c', 'import os,pathlib,signal,time; pathlib.Path(os.environ["FIXTURE_CLEAN_DIR"],"clean").write_text(str(os.getpid())); signal.signal(signal.SIGTERM, signal.SIG_IGN); time.sleep(60)'], {'FIXTURE_CLEAN_DIR': str(root)})
time.sleep(float(os.environ.get('FIXTURE_SLEEP', '60')))
""")
        self.fixture.chmod(0o755)

    def tearDown(self):
        for process in self.processes:
            if process.poll() is None:
                process.terminate()
                try:
                    process.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait(timeout=5)
            if process.stdin is not None:
                process.stdin.close()
            if process.stderr is not None:
                process.stderr.close()
        for log in self.logs:
            log.close()
        self.temp.cleanup()

    def checkout(self, name):
        root = self.root / name
        destination = root / "dev-tools" / "validation"
        if destination.exists():
            return destination / "run-locked-gradle.sh"
        destination.mkdir(parents=True)
        for filename in ("run-locked-gradle.sh", "gradle-run-supervisor.py"):
            shutil.copy(ROOT / "dev-tools" / "validation" / filename, destination)
        for service in ("account-service", "game-session-service"):
            (root / "services" / service).mkdir(parents=True)
        return destination / "run-locked-gradle.sh"

    def launch(self, name, task=":account-service:test", *, checkout_name=None, diagnostic_pipe=False, diagnostic_fd=None, **settings):
        fixture_dir = self.root / f"fixture-{name}"
        fixture_dir.mkdir()
        env = {**os.environ, "CI": "false", "FIXTURE_DIR": str(fixture_dir),
               "PATH": f"{self.bin}:{os.environ['PATH']}", "MOCK_DOCKER_STATE": str(self.docker_state),
               "FIREMUD_LOCK_GRADLE_EXEC": str(self.fixture),
               "FIREMUD_LOCK_GRADLE_RESOURCE_DIR": str(self.resource),
               "FIREMUD_LOCK_GRADLE_RUN_SECONDS": "10",
               "FIREMUD_LOCK_GRADLE_CANCEL_SECONDS": "1", **settings}
        log = (self.root / f"{name}.log").open("w+")
        self.logs.append(log)
        process = subprocess.Popen(["bash", str(self.checkout(checkout_name or name)), task], env=env,
                                   stdout=log, stderr=diagnostic_fd if diagnostic_fd is not None else (subprocess.PIPE if diagnostic_pipe else log),
                                   stdin=subprocess.PIPE)
        self.processes.append(process)
        return process, fixture_dir, log

    def ready(self, fixture, name="ready"):
        deadline = time.monotonic() + 5
        while not (fixture / name).exists() and time.monotonic() < deadline:
            time.sleep(.02)
        self.assertTrue((fixture / name).exists(), f"fixture did not reach {name}")
        return int((fixture / name).read_text())

    def output(self, log):
        log.flush()
        log.seek(0)
        return log.read()

    def test_cross_worktree_and_service_exclusion(self):
        holder, fixture, _ = self.launch("holder")
        self.ready(fixture)
        contender, _, log = self.launch("contender", ":game-session-service:test")
        self.assertEqual(contender.wait(timeout=5), 1)
        self.assertIn("Verification lock unavailable for local-resource.", self.output(log))
        holder.terminate()
        self.assertEqual(holder.wait(timeout=5), 143)

    def test_wait_has_bounded_total_budget(self):
        holder, fixture, _ = self.launch("holder")
        self.ready(fixture)
        started = time.monotonic()
        contender, _, _ = self.launch("contender", FIREMUD_LOCK_GRADLE_WAIT="1",
                                      FIREMUD_LOCK_GRADLE_WAIT_SECONDS="1")
        self.assertEqual(contender.wait(timeout=5), 1)
        self.assertLess(time.monotonic() - started, 3)
        holder.terminate()
        holder.wait(timeout=5)

    def test_exit_status_and_stdin_are_preserved(self):
        process, _, _ = self.launch("exit", FIXTURE_MODE="exit")
        self.assertEqual(process.wait(timeout=5), 23)
        process, fixture, _ = self.launch("stdin", FIXTURE_MODE="stdin")
        process.stdin.write(b"input retained\n")
        process.stdin.flush()
        self.assertEqual(process.wait(timeout=5), 0)
        self.assertEqual((fixture / "stdin").read_text(), "input retained\n")
        self.assertIn("--no-daemon", (fixture / "args").read_text())
        self.assertNotIn("firemud-gradle-cancel", (fixture / "fds").read_text())

    def test_ci_retains_daemon_policy(self):
        process, fixture, _ = self.launch("ci", FIXTURE_MODE="exit", CI="true",
                                         FIREMUD_LOCK_GRADLE_RUN_SECONDS="invalid",
                                         FIREMUD_LOCK_GRADLE_CANCEL_SECONDS="invalid")
        self.assertEqual(process.wait(timeout=5), 23)
        self.assertNotIn("--no-daemon", (fixture / "args").read_text())
        self.assertFalse(self.resource.exists())

    def test_empty_xdg_uses_home_cache(self):
        (self.root / "fixture-xdg").mkdir()
        env = {**os.environ, "CI": "false", "XDG_CACHE_HOME": "", "HOME": str(self.root),
               "FIREMUD_LOCK_GRADLE_EXEC": str(self.fixture), "FIXTURE_MODE": "exit",
               "FIXTURE_DIR": str(self.root / "fixture-xdg")}
        env.pop("FIREMUD_LOCK_GRADLE_RESOURCE_DIR", None)
        result = subprocess.run(["bash", str(self.checkout("xdg")), ":account-service:test"],
                                env=env, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=5, check=False)
        self.assertEqual(result.returncode, 23)
        self.assertTrue((self.root / ".cache/firemud/gradle-validation/local-resource.lock").exists())

    def test_clean_environment_exec_retains_exact_owned_cleanup(self):
        process, fixture, _ = self.launch("clean", FIXTURE_MODE="cleanenv",
                                         FIREMUD_LOCK_GRADLE_RUN_SECONDS="1")
        pid = self.ready(fixture, "clean")
        self.assertEqual(process.wait(timeout=6), 124)
        self.assertFalse(self.running(pid))

    def test_closed_diagnostic_pipe_cannot_release_over_owned_worker(self):
        process, fixture, _ = self.launch("closed-pipe", FIXTURE_MODE="worker", diagnostic_pipe=True)
        pid = self.ready(fixture, "worker")
        process.stderr.close()
        process.kill()
        process.wait(timeout=5)
        contender, _, _ = self.launch("pipe-contender", FIXTURE_MODE="exit")
        self.assertEqual(contender.wait(timeout=5), 1)
        replacement, _, _ = self.launch("pipe-replacement", FIXTURE_MODE="exit",
                                        FIREMUD_LOCK_GRADLE_WAIT="1", FIREMUD_LOCK_GRADLE_WAIT_SECONDS="4")
        self.assertEqual(replacement.wait(timeout=6), 23)
        self.assertFalse(self.running(pid))

    def test_full_unread_diagnostic_pipe_cannot_delay_cleanup(self):
        reader, writer = os.pipe()
        try:
            os.set_blocking(writer, False)
            with self.assertRaises(BlockingIOError):
                while True:
                    os.write(writer, b"x" * 4096)
            os.set_blocking(writer, True)
            process, fixture, _ = self.launch("full-pipe", FIXTURE_MODE="worker", diagnostic_fd=writer)
            pid = self.ready(fixture, "worker")
            process.kill()
            process.wait(timeout=5)
            replacement, _, _ = self.launch("full-pipe-replacement", FIXTURE_MODE="exit",
                                            FIREMUD_LOCK_GRADLE_WAIT="1", FIREMUD_LOCK_GRADLE_WAIT_SECONDS="4")
            self.assertEqual(replacement.wait(timeout=6), 23)
            self.assertFalse(self.running(pid))
            # The supervisor must not change the shared pipe's blocking policy.
            self.assertTrue(os.get_blocking(writer))
        finally:
            os.close(writer)
            os.close(reader)

    def test_non_utf8_process_name_preserves_exact_identity(self):
        stat = b"123 (name with ) and \xff) " + b" ".join([b"S"] + [b"0"] * 18 + [b"123456"])
        with mock.patch.object(SUPERVISOR.Path, "read_bytes", return_value=stat):
            self.assertEqual(SUPERVISOR.identity(123), ("123456", "S"))
            self.assertTrue(SUPERVISOR.alive(123, "123456"))

    def test_non_utf8_native_name_cannot_bypass_cleanup(self):
        process, fixture, _ = self.launch("nonutf8", FIXTURE_MODE="nonutf8",
                                         FIREMUD_LOCK_GRADLE_RUN_SECONDS="1")
        pid = self.ready(fixture, "renamed")
        self.assertIn(b"\xff", Path(f"/proc/{pid}/stat").read_bytes())
        self.assertEqual(process.wait(timeout=6), 124)
        self.assertFalse(self.running(pid))
        replacement, _, _ = self.launch("nonutf8-replacement", FIXTURE_MODE="exit")
        self.assertEqual(replacement.wait(timeout=5), 23)

    def test_pidfd_readiness_supports_descriptor_above_select_limit(self):
        reader, writer = os.pipe()
        descriptor = SUPERVISOR.fcntl.fcntl(reader, SUPERVISOR.fcntl.F_DUPFD_CLOEXEC, 1024)
        try:
            process = SUPERVISOR.OwnedProcess(123, "start", descriptor)
            self.assertTrue(process.running())
            os.write(writer, b"ready")
            self.assertFalse(process.running())
        finally:
            os.close(descriptor)
            os.close(reader)
            os.close(writer)

    def test_pid_reuse_during_admission_rejects_replacement(self):
        # The numerical PID now exposes a replacement with the same marker,
        # but the original stable handle became ready while procfs was read.
        with mock.patch.object(SUPERVISOR.os, "pidfd_open", return_value=700), \
             mock.patch.object(SUPERVISOR.os, "close") as close, \
             mock.patch.object(SUPERVISOR.OwnedProcess, "running", side_effect=[True, False]), \
             mock.patch.object(SUPERVISOR, "identity", return_value=("replacement-start", "S")), \
             mock.patch.object(SUPERVISOR.Path, "stat", return_value=SimpleNamespace(st_uid=os.getuid())), \
             mock.patch.object(SUPERVISOR.Path, "read_bytes", return_value=b"FIREMUD_GRADLE_RUN_OWNER=token\0"):
            self.assertIsNone(SUPERVISOR.admit_process(900000003, "token"))
        close.assert_called_once_with(700)

    def test_admission_requires_same_start_identity_but_allows_state_changes(self):
        for latest, admitted in ((("original-start", "R"), True), (("replacement-start", "S"), False)):
            with self.subTest(latest=latest), \
                 mock.patch.object(SUPERVISOR.os, "pidfd_open", return_value=700), \
                 mock.patch.object(SUPERVISOR.os, "close") as close, \
                 mock.patch.object(SUPERVISOR.OwnedProcess, "running", return_value=True), \
                 mock.patch.object(SUPERVISOR, "identity", side_effect=[("original-start", "S"), latest]), \
                 mock.patch.object(SUPERVISOR.Path, "stat", return_value=SimpleNamespace(st_uid=os.getuid())), \
                 mock.patch.object(SUPERVISOR.Path, "read_bytes", return_value=b"FIREMUD_GRADLE_RUN_OWNER=token\0"):
                process = SUPERVISOR.admit_process(900000003, "token")
                self.assertEqual(process is not None, admitted)
                if process is not None:
                    process.close()
            close.assert_called_once_with(700)

    def test_signalling_retained_handle_never_uses_recycled_pid(self):
        process = SUPERVISOR.OwnedProcess(900000003, "old-start", 700)
        args = SimpleNamespace(root=str(self.root), wrapper_pid=os.getpid(), wrapper_start=SUPERVISOR.identity(os.getpid())[0], command=["fixture"], lock=[])
        with mock.patch.dict(os.environ, {"CI": "false"}):
            supervisor = SUPERVISOR.Supervisor(args)
        supervisor.owned[process.pid] = process
        with mock.patch.object(SUPERVISOR.signal, "pidfd_send_signal") as send, \
             mock.patch.object(SUPERVISOR.os, "kill") as unsafe_kill, \
             mock.patch.object(SUPERVISOR, "identity", return_value=("replacement-start", "S")):
            supervisor.signal_owned(signal.SIGKILL)
        send.assert_called_once_with(700, signal.SIGKILL)
        unsafe_kill.assert_not_called()

    def test_missing_pidfd_support_fails_before_launch(self):
        with mock.patch.object(SUPERVISOR.os, "pidfd_open", side_effect=OSError("unsupported kernel")), \
             self.assertRaisesRegex(ValueError, "requires working Linux pidfds"):
            SUPERVISOR.require_pidfds()

    def test_interrupt_and_hangup_exit_status(self):
        for sig in (signal.SIGINT, signal.SIGHUP, signal.SIGTERM):
            process, fixture, _ = self.launch(f"signal-{sig}", FIXTURE_MODE="worker")
            pid = self.ready(fixture, "worker")
            process.send_signal(sig)
            self.assertEqual(process.wait(timeout=5), 128 + sig)
            self.assertFalse(self.running(pid))

    def test_replaced_wrapper_before_initialization_cannot_launch(self):
        args = SimpleNamespace(root=str(self.root), wrapper_pid=900000004, wrapper_start="123",
                               command=["fixture"], lock=[])
        with mock.patch.dict(os.environ, {"CI": "false"}), \
             mock.patch.object(SUPERVISOR, "identity", return_value=("456", "S")), \
             mock.patch.object(SUPERVISOR.fcntl, "flock") as flock, \
             mock.patch.object(SUPERVISOR.subprocess, "Popen") as launch, \
             self.assertRaisesRegex(ValueError, "wrapper process is no longer alive"):
            SUPERVISOR.Supervisor(args)
        flock.assert_not_called()
        launch.assert_not_called()

    def test_bound_channel_cancels_during_acquisition(self):
        reader, writer = os.pipe()
        args = SimpleNamespace(root=str(self.root), wrapper_pid=os.getpid(), wrapper_start=SUPERVISOR.identity(os.getpid())[0], command=["fixture"],
                               lock=[], cancel_fd=reader)
        try:
            with mock.patch.dict(os.environ, {"CI": "false"}):
                supervisor = SUPERVISOR.Supervisor(args)
            os.write(writer, b"130\n")
            try:
                with mock.patch.object(SUPERVISOR.fcntl, "flock") as flock:
                    self.assertFalse(supervisor.acquire(self.resource, "local-resource"))
                    flock.assert_not_called()
                self.assertEqual(supervisor.cancel_status, 130)
            finally:
                supervisor.release()
        finally:
            os.close(writer)

    def test_total_wait_budget_spans_resource_and_output_locks(self):
        output_holder, fixture, _ = self.launch("output-holder", "check", CI="true")
        self.ready(fixture)
        holder, fixture, _ = self.launch("resource-holder", FIXTURE_SLEEP=".6")
        self.ready(fixture)
        started = time.monotonic()
        contender, _, _ = self.launch("multi-stage", checkout_name="output-holder",
                                      FIREMUD_LOCK_GRADLE_WAIT="1", FIREMUD_LOCK_GRADLE_WAIT_SECONDS="1")
        self.assertEqual(contender.wait(timeout=5), 1)
        self.assertLess(time.monotonic() - started, 1.7)
        holder.wait(timeout=5)
        output_holder.terminate()
        output_holder.wait(timeout=5)

    def test_unrelated_process_is_not_cancelled(self):
        unrelated = subprocess.Popen(["python3", "-c", "import time; time.sleep(60)"])
        self.processes.append(unrelated)
        process, fixture, _ = self.launch("owned")
        self.ready(fixture)
        process.terminate()
        self.assertEqual(process.wait(timeout=5), 143)
        self.assertIsNone(unrelated.poll())

    def simulated_boundary(self, transient_error=False):
        """Model a worker appearing after the first scan and final client exit."""
        client_pid, worker_pid = 900000001, 900000002
        child = SimpleNamespace(pid=client_pid, poll=lambda: 0)
        state = {"scans": 0, "worker_alive": True, "cancelled": False}
        env = {"CI": "false", "FIREMUD_LOCK_GRADLE_RESOURCE_DIR": str(self.resource)}
        args = SimpleNamespace(root=str(self.root), wrapper_pid=os.getpid(), wrapper_start=SUPERVISOR.identity(os.getpid())[0], command=["fixture"], lock=[])
        with mock.patch.dict(os.environ, env):
            supervisor = SUPERVISOR.Supervisor(args)

        worker_handle = SimpleNamespace(running=lambda: state["worker_alive"], close=lambda: None)

        def scan(_token, _remembered):
            state["scans"] += 1
            if transient_error and state["scans"] == 1:
                raise OSError("temporary procfs read failure")
            if state["scans"] == 1 or not state["worker_alive"]:
                return {}
            return {worker_pid: worker_handle}

        def cancel(_sig):
            # Cleanup must still own the resource guard when the late worker is found.
            with (self.resource / "local-resource.lock").open("a") as contender, \
                 self.assertRaises(BlockingIOError):
                SUPERVISOR.fcntl.flock(contender, SUPERVISOR.fcntl.LOCK_EX | SUPERVISOR.fcntl.LOCK_NB)
            if worker_pid in supervisor.owned:
                state["cancelled"] = True
                state["worker_alive"] = False

        with mock.patch.dict(os.environ, env), \
             mock.patch.object(SUPERVISOR.os, "setsid"), \
             mock.patch.object(SUPERVISOR.signal, "signal"), \
             mock.patch.object(SUPERVISOR.subprocess, "Popen", return_value=child), \
             mock.patch.object(SUPERVISOR, "admit_process", return_value=None), \
             mock.patch.object(SUPERVISOR, "owned_processes", side_effect=scan), \
             mock.patch.object(supervisor, "wrapper_gone", side_effect=[False, True]), \
             mock.patch.object(supervisor, "signal_owned", side_effect=cancel), \
             mock.patch.object(SUPERVISOR.time, "sleep"):
            try:
                status = supervisor.run()
            finally:
                supervisor.release()
        self.assertTrue(state["cancelled"])
        self.assertFalse(state["worker_alive"])
        return status

    def test_rescan_after_client_exit_discovers_late_worker(self):
        self.assertEqual(self.simulated_boundary(), 143)

    def test_transient_proc_error_retries_cleanup_and_returns_failure(self):
        self.assertEqual(self.simulated_boundary(transient_error=True), 1)

    def test_timeout_cleans_separate_session_worker_before_unlock(self):
        process, fixture, _ = self.launch("worker", FIXTURE_MODE="worker",
                                         FIREMUD_LOCK_GRADLE_RUN_SECONDS="1")
        worker = self.ready(fixture, "worker")
        contender, _, _ = self.launch("contender", FIXTURE_MODE="exit")
        self.assertEqual(contender.wait(timeout=5), 1)
        self.assertEqual(process.wait(timeout=6), 124)
        self.assertFalse(self.running(worker))
        replacement, _, _ = self.launch("replacement", FIXTURE_MODE="exit")
        self.assertEqual(replacement.wait(timeout=5), 23)

    def session(self, name, script, *, inline=False, cwd=None, **settings):
        command = self.root / f"{name}.sh"
        command.write_text(script)
        fixture = self.root / f"fixture-{name}"
        fixture.mkdir()
        env = {**os.environ, "CI": "false", "FIXTURE_DIR": str(fixture),
               "PATH": f"{self.bin}:{os.environ['PATH']}", "MOCK_DOCKER_STATE": str(self.docker_state),
               "FIREMUD_LOCK_GRADLE_EXEC": str(self.fixture), "FIXTURE_MODE": "exit",
               "FIREMUD_LOCK_GRADLE_RESOURCE_DIR": str(self.resource),
               "FIREMUD_LOCK_GRADLE_RUN_SECONDS": "10", "FIREMUD_LOCK_GRADLE_CANCEL_SECONDS": "2", **settings}
        log = (self.root / f"{name}.log").open("w+")
        self.logs.append(log)
        shell_command = ["bash", "-e", "-c", script] if inline else ["bash", str(command)]
        process = subprocess.Popen(["bash", str(self.checkout(name)), "--resource-session", *shell_command],
                                   env=env, stdout=log, stderr=log, cwd=cwd)
        self.processes.append(process)
        return process, fixture, log

    def test_full_session_excludes_other_worktrees_until_teardown(self):
        nested = self.checkout("nested")
        process, fixture, log = self.session("session", f"""set -eu
set +e
CI=true bash '{nested}' :account-service:test
status=$?
set -e
[ "$status" = 23 ]
printf '%s' "$status" > "$FIXTURE_DIR/nested-status"
printf '%s' "$$" > "$FIXTURE_DIR/session-ready"
while [ ! -e "$FIXTURE_DIR/finish" ]; do sleep .03; done
printf done > "$FIXTURE_DIR/teardown"
""")
        self.ready(fixture, "session-ready")
        self.assertEqual((fixture / "nested-status").read_text(), "23")
        self.assertIn("--no-daemon", (fixture / "args").read_text())
        contender, contender_fixture, _ = self.launch("session-contender", FIXTURE_MODE="exit")
        self.assertEqual(contender.wait(timeout=5), 1)
        self.assertFalse((contender_fixture / "ready").exists())
        (fixture / "finish").touch()
        self.assertEqual(process.wait(timeout=5), 0, self.output(log))
        self.assertTrue((fixture / "teardown").exists())
        replacement, _, _ = self.launch("session-replacement", FIXTURE_MODE="exit")
        self.assertEqual(replacement.wait(timeout=5), 23)

    def test_session_signal_runs_teardown_before_releasing_guard(self):
        process, fixture, log = self.session("cancel-session", """set -eu
trap 'trap "" TERM INT HUP; sleep .3; printf done > "$FIXTURE_DIR/teardown"; exit 143' TERM
printf '%s' "$$" > "$FIXTURE_DIR/session-ready"
while true; do sleep .03; done
""")
        self.ready(fixture, "session-ready")
        process.terminate()
        contender, _, _ = self.launch("cancel-contender", FIXTURE_MODE="exit")
        self.assertEqual(contender.wait(timeout=5), 1)
        self.assertEqual(process.wait(timeout=5), 143, self.output(log))
        self.assertTrue((fixture / "teardown").exists())

    def test_dead_session_owner_rejects_later_nested_admission(self):
        nested = self.checkout("dead-nested")
        process, fixture, log = self.session("dead-session", f"""set -eu
printf '%s' "$FIREMUD_LOCAL_RESOURCE_SESSION_PID" > "$FIXTURE_DIR/owner"
printf '%s' "$$" > "$FIXTURE_DIR/session-ready"
for attempt in {{1..100}}; do
  [ ! -e "$FIXTURE_DIR/continue" ] || break
  sleep .03
done
set +e
CI=true bash '{nested}' :account-service:test
printf '%s' "$?" > "$FIXTURE_DIR/nested-status"
printf '%s' "$$" > "$FIXTURE_DIR/teardown"
""")
        self.ready(fixture, "session-ready")
        owner = int((fixture / "owner").read_text())
        os.kill(owner, signal.SIGKILL)
        self.assertNotEqual(process.wait(timeout=5), 0)
        (fixture / "continue").touch()
        self.ready(fixture, "teardown")
        self.assertEqual((fixture / "nested-status").read_text(), "1", self.output(log))
        self.assertFalse((fixture / "ready").exists())

    def test_multiline_command_newline_argument_and_cwd_preserve_admission(self):
        nested = self.checkout("newline-nested")
        cwd = self.root / "directory\nwith-newline"
        cwd.mkdir()
        project = "firemud-smoke-multiline"
        script = f"""set -eu
{self.register_command(project)}
set +e
bash '{nested}' :account-service:test $'argument\nwith-newline'
status=$?
set -e
[ "$status" = 23 ]
"""
        process, fixture, log = self.session("multiline", script, inline=True, cwd=cwd)
        self.assertEqual(process.wait(timeout=5), 0, self.output(log))
        lines = (self.resource / "local-resource.meta").read_text().splitlines()
        self.assertEqual(len(lines), 6)
        self.assertEqual(json.loads(lines[2]), str(cwd))
        self.assertEqual(shlex.split(json.loads(lines[3]))[-1], script)
        self.assertEqual(json.loads(lines[5])["runtimeProjects"], [project])
        self.assertIn("argument\nwith-newline", (fixture / "args").read_text())
        admitted, _fixture, log = self.launch("after-multiline", FIXTURE_MODE="exit")
        self.assertEqual(admitted.wait(timeout=5), 23, self.output(log))

    def test_documented_multiline_fresh_restart_chain_and_clean_readmission(self):
        wrapper = self.checkout("documented-chain")
        checkout = wrapper.parents[2]
        helper = checkout / "dev-tools/smoke/run-owned-compose.sh"
        helper.parent.mkdir()
        shutil.copy(ROOT / "dev-tools/smoke/run-owned-compose.sh", helper)
        for name, claim in (("verify-fresh-bootstrap.sh", "claim_run_owned_compose_project"),
                            ("verify-restart-state.sh", "require_run_owned_compose_project")):
            (checkout / "dev-tools" / name).write_text(f"""#!/usr/bin/env bash
set -euo pipefail
ROOT_DIR='{checkout}'
source "$ROOT_DIR/dev-tools/smoke/run-owned-compose.sh"
initialize_run_owned_compose_proof "$@"
COMPOSE_FILES=(-f docker/docker-compose.yml -f docker/docker-compose.override.yml -f docker/docker-compose.grpc-mtls.override.yml)
install_run_owned_compose_proof_traps
{claim}
FIREMUD_SMOKE_PROOF_CLAIMED=true
docker compose "${{COMPOSE_FILES[@]}}" up -d
set +e
bash "$ROOT_DIR/dev-tools/validation/run-locked-gradle.sh" :account-service:test
status=$?
set -e
[ "$status" = 23 ]
""")
        documented = (ROOT / "docker/README.md").read_text()
        match = re.search(r"bash dev-tools/validation/run-locked-gradle.sh --resource-session bash -e -c '(.*?)\n'", documented, re.DOTALL)
        self.assertIsNotNone(match)
        process, _fixture, log = self.session("documented-inline", match.group(1), inline=True, cwd=checkout,
                                             FIREMUD_SMOKE_RUN_ID="doc-chain",
                                             COMPOSE_PROJECT_NAME="firemud-smoke-doc-chain",
                                             FIREMUD_SMOKE_OWNERSHIP_TOKEN="a" * 64,
                                             FIREMUD_SMOKE_TEST_MODE="1",
                                             FIREMUD_SMOKE_OWNERSHIP_DIR=str(self.root / "ownership"))
        self.assertEqual(process.wait(timeout=5), 0, self.output(log))
        self.assertEqual(json.loads(self.docker_state.read_text()), {})
        self.assertFalse(list((self.root / "ownership").glob("*.marker")))
        admitted, _fixture, log = self.launch("after-documented-chain", FIXTURE_MODE="exit")
        self.assertEqual(admitted.wait(timeout=5), 23, self.output(log))

    def test_prior_well_formed_metadata_preserves_exact_project_hold(self):
        self.resource.mkdir(mode=0o700)
        project = "firemud-smoke-prior-format"
        prior = "111\nprior-time\nprior-cwd\nprior-command\nprior-owner\n" + json.dumps({"runtimeProjects": [project]}) + "\n"
        metadata = self.resource / "local-resource.meta"
        metadata.write_text(prior)
        self.docker_state.write_text(json.dumps({project: ["running"]}))
        blocked, _fixture, log = self.launch("prior-blocked", FIXTURE_MODE="exit")
        self.assertEqual(blocked.wait(timeout=5), 1)
        self.assertIn(project, self.output(log))
        self.assertEqual(metadata.read_text(), prior)
        self.docker_state.write_text("{}")
        admitted, _fixture, log = self.launch("prior-recovered", FIXTURE_MODE="exit")
        self.assertEqual(admitted.wait(timeout=5), 23, self.output(log))
        metadata.write_text("111\nprior-time\nprior-cwd\nprior-command\nprior-owner\n")
        admitted, _fixture, log = self.launch("prior-five-lines", FIXTURE_MODE="exit")
        self.assertEqual(admitted.wait(timeout=5), 23, self.output(log))

    def test_outer_deadline_drains_nested_closed_fd_term_ignoring_worker(self):
        nested = self.checkout("outer-deadline-nested")
        process, fixture, log = self.session("outer-deadline", f"""set -eu
FIREMUD_LOCK_GRADLE_RUN_SECONDS=10 FIREMUD_LOCK_GRADLE_CANCEL_SECONDS=4 FIXTURE_MODE=worker \
  bash '{nested}' :account-service:test
""", FIREMUD_LOCK_GRADLE_RUN_SECONDS="1", FIREMUD_LOCK_GRADLE_CANCEL_SECONDS="1")
        worker = self.ready(fixture, "worker")
        contender, _fixture, _log = self.launch("outer-deadline-contender", FIXTURE_MODE="exit")
        self.assertEqual(contender.wait(timeout=5), 1)
        self.assertEqual(process.wait(timeout=6), 124, self.output(log))
        self.assertFalse(self.running(worker))
        admitted, _fixture, log = self.launch("outer-deadline-after", FIXTURE_MODE="exit")
        self.assertEqual(admitted.wait(timeout=5), 23, self.output(log))

    def test_nested_deadline_cancels_only_its_workers_not_parent_or_sibling(self):
        nested = self.checkout("nested-deadline")
        sibling = self.root / "sibling-fixture"
        sibling.mkdir()
        process, fixture, log = self.session("nested-isolation", f"""set -eu
FIXTURE_DIR='{sibling}' FIXTURE_MODE=hold bash '{nested}' :game-session-service:test &
sibling_wrapper=$!
FIREMUD_LOCK_GRADLE_RUN_SECONDS=1 FIREMUD_LOCK_GRADLE_CANCEL_SECONDS=1 FIXTURE_MODE=worker \
  bash '{nested}' :account-service:test &
worker_wrapper=$!
set +e
wait "$worker_wrapper"
status=$?
set -e
[ "$status" = 124 ]
kill -0 "$(cat '{sibling}/ready')"
printf '%s' "$$" > "$FIXTURE_DIR/session-ready"
while [ ! -e "$FIXTURE_DIR/finish" ]; do sleep .03; done
kill -TERM "$sibling_wrapper"
set +e
wait "$sibling_wrapper"
status=$?
set -e
[ "$status" = 143 ]
""")
        worker = self.ready(fixture, "worker")
        self.ready(fixture, "session-ready")
        self.assertFalse(self.running(worker))
        self.assertTrue(self.running(int((sibling / "ready").read_text())))
        self.assertIsNone(process.poll())
        (fixture / "finish").touch()
        self.assertEqual(process.wait(timeout=5), 0, self.output(log))

    def register_command(self, project):
        return f"python3 '{ROOT / 'dev-tools/validation/gradle-run-supervisor.py'}' --register-runtime-project '{project}'"

    def failed_runtime(self, name, project, state):
        self.docker_state.write_text(json.dumps({project: state}))
        process, _fixture, log = self.session(name, f"set -eu\n{self.register_command(project)}\nexit 37\n")
        self.assertEqual(process.wait(timeout=5), 37, self.output(log))
        return (self.resource / "local-resource.meta").read_text()

    def test_failed_cleanup_live_or_unknown_blocks_gradle_and_runtime(self):
        project = "firemud-smoke-failed-cleanup"
        metadata = self.failed_runtime("failed-runtime", project, ["running"])
        for index, state in enumerate((["running"], "unavailable", ["restarting"], ["paused"])):
            self.docker_state.write_text(json.dumps({project: state}))
            contender, fixture, log = self.launch(f"blocked-gradle-{index}", FIXTURE_MODE="exit")
            self.assertEqual(contender.wait(timeout=5), 1)
            self.assertFalse((fixture / "ready").exists())
            self.assertIn(project, self.output(log))
            runtime, fixture, log = self.session(f"blocked-runtime-{index}", 'touch "$FIXTURE_DIR/started"')
            self.assertEqual(runtime.wait(timeout=5), 1)
            self.assertFalse((fixture / "started").exists())
            self.assertEqual((self.resource / "local-resource.meta").read_text(), metadata)

    def test_stopped_or_absent_releases_hold_despite_volume_cleanup_failure(self):
        project = "firemud-smoke-stopped"
        for index, state in enumerate((["exited"], [])):
            self.failed_runtime(f"stopped-runtime-{index}", project, state)
            retained_volume = self.root / f"retained-volume-{index}"
            retained_volume.touch()
            contender, _fixture, log = self.launch(f"stopped-admission-{index}", FIXTURE_MODE="exit")
            self.assertEqual(contender.wait(timeout=5), 23, self.output(log))
            self.assertTrue(retained_volume.exists())
            self.assertFalse((self.resource / "local-resource.meta").exists())

    def test_abrupt_owner_death_retains_project_until_verified_absence(self):
        project = "firemud-smoke-dead-runtime"
        self.docker_state.write_text(json.dumps({project: ["running"]}))
        process, fixture, log = self.session("abrupt-runtime", f"""set -eu
{self.register_command(project)}
printf '%s' "$FIREMUD_LOCAL_RESOURCE_SESSION_PID" > "$FIXTURE_DIR/owner"
printf '%s' "$$" > "$FIXTURE_DIR/session-ready"
for attempt in {{1..100}}; do
  [ ! -e "$FIXTURE_DIR/finish" ] || break
  sleep .03
done
printf '%s' "$$" > "$FIXTURE_DIR/finished"
""")
        self.ready(fixture, "session-ready")
        os.kill(int((fixture / "owner").read_text()), signal.SIGKILL)
        self.assertNotEqual(process.wait(timeout=5), 0)
        (fixture / "finish").touch()
        self.ready(fixture, "finished")
        blocked, _fixture, log = self.launch("dead-runtime-contender", FIXTURE_MODE="exit",
                                             FIREMUD_LOCK_GRADLE_WAIT="1")
        self.assertEqual(blocked.wait(timeout=5), 1, self.output(log))
        self.docker_state.write_text("{}")
        admitted, _fixture, log = self.launch("dead-runtime-recovery", FIXTURE_MODE="exit")
        self.assertEqual(admitted.wait(timeout=5), 23, self.output(log))

    def test_concurrent_contenders_preserve_all_unresolved_identities(self):
        projects = ["firemud-smoke-first", "firemud-smoke-second"]
        self.docker_state.write_text(json.dumps({project: ["running"] for project in projects}))
        script = "set -eu\n" + "\n".join(f"{self.register_command(project)} &" for project in projects) + "\nwait\nexit 37\n"
        owner, _fixture, log = self.session("multiple-projects", script)
        self.assertEqual(owner.wait(timeout=5), 37, self.output(log))
        before = (self.resource / "local-resource.meta").read_text()
        recorded = json.loads(before.splitlines()[5])["runtimeProjects"]
        self.assertEqual(set(recorded), set(projects))
        one, _fixture, _log = self.launch("contender-one", FIXTURE_MODE="exit")
        two, _fixture, _log = self.launch("contender-two", FIXTURE_MODE="exit")
        self.assertEqual(one.wait(timeout=5), 1)
        self.assertEqual(two.wait(timeout=5), 1)
        self.assertEqual((self.resource / "local-resource.meta").read_text(), before)
        self.docker_state.write_text(json.dumps({projects[1]: ["running"]}))
        remaining, _fixture, log = self.launch("contender-remaining", FIXTURE_MODE="exit")
        self.assertEqual(remaining.wait(timeout=5), 1)
        self.assertIn(projects[1], self.output(log))
        self.assertEqual((self.resource / "local-resource.meta").read_text(), before)

    def test_invalid_inherited_session_cannot_bypass_with_ci(self):
        process, fixture, log = self.launch("invalid-session", CI="true",
                                           FIREMUD_LOCAL_RESOURCE_SESSION_FD="99999",
                                           FIREMUD_LOCAL_RESOURCE_SESSION_PID=str(os.getpid()),
                                           FIREMUD_LOCAL_RESOURCE_SESSION_START=SUPERVISOR.identity(os.getpid())[0],
                                           FIREMUD_LOCAL_RESOURCE_SESSION_OWNER="forged")
        self.assertEqual(process.wait(timeout=5), 1)
        self.assertFalse((fixture / "ready").exists())
        self.assertIn("Invalid inherited local resource session", self.output(log))

    def test_session_rejects_foreign_unheld_descriptor_and_dead_owner(self):
        self.resource.mkdir(mode=0o700)
        lock = self.resource / "local-resource.lock"
        with lock.open("a") as descriptor:
            (self.resource / "local-resource.meta").write_text(f"{os.getpid()}\nnow\nroot\ncommand\ntoken\n")
            env = {"FIREMUD_LOCK_GRADLE_RESOURCE_DIR": str(self.resource),
                   SUPERVISOR.SESSION_FD: str(descriptor.fileno()), SUPERVISOR.SESSION_PID: str(os.getpid()),
                   SUPERVISOR.SESSION_START: SUPERVISOR.identity(os.getpid())[0], SUPERVISOR.SESSION_TOKEN: "token"}
            with mock.patch.dict(os.environ, env):
                with self.assertRaisesRegex(ValueError, "no exclusive session ownership"):
                    SUPERVISOR.verify_resource_session()
                with (self.root / "foreign").open("a") as foreign, \
                     mock.patch.dict(os.environ, {SUPERVISOR.SESSION_FD: str(foreign.fileno())}), \
                     self.assertRaisesRegex(ValueError, "descriptor does not match"):
                    SUPERVISOR.verify_resource_session()
                with mock.patch.object(SUPERVISOR, "admit_process", return_value=None), \
                     self.assertRaisesRegex(ValueError, "owner is not live"):
                    SUPERVISOR.verify_resource_session()

    @staticmethod
    def running(pid):
        try:
            return Path(f"/proc/{pid}/stat").read_bytes().rsplit(b")", 1)[1].split()[0] != b"Z"
        except (FileNotFoundError, ProcessLookupError):
            return False

    def test_running_treats_disappearing_process_as_not_running(self):
        for error in (FileNotFoundError, ProcessLookupError):
            with self.subTest(error=error), mock.patch.object(Path, "read_bytes", side_effect=error):
                self.assertFalse(self.running(12345))

    def test_wrapper_sigkill_supervisor_retains_guard_until_cleanup(self):
        process, fixture, _ = self.launch("worker", FIXTURE_MODE="worker")
        worker = self.ready(fixture, "worker")
        process.kill()
        self.assertEqual(process.wait(timeout=5), -signal.SIGKILL)
        contender, _, _ = self.launch("contender", FIXTURE_MODE="exit")
        self.assertEqual(contender.wait(timeout=5), 1)
        deadline = time.monotonic() + 5
        while self.running(worker) and time.monotonic() < deadline:
            time.sleep(.05)
        self.assertFalse(self.running(worker))
        replacement, _, _ = self.launch("replacement", FIXTURE_MODE="exit",
                                        FIREMUD_LOCK_GRADLE_WAIT="1",
                                        FIREMUD_LOCK_GRADLE_WAIT_SECONDS="3")
        self.assertEqual(replacement.wait(timeout=5), 23)

    def test_invalid_budget_and_explicit_daemon_fail_before_launch(self):
        process, fixture, _ = self.launch("invalid", FIREMUD_LOCK_GRADLE_RUN_SECONDS="0")
        self.assertEqual(process.wait(timeout=5), 1)
        self.assertFalse((fixture / "ready").exists())
        process, fixture, _ = self.launch("daemon", "--daemon")
        self.assertEqual(process.wait(timeout=5), 1)
        self.assertFalse((fixture / "ready").exists())

    def test_unrepresentable_budgets_fail_before_launch(self):
        for index, setting in enumerate(("FIREMUD_LOCK_GRADLE_WAIT_SECONDS", "FIREMUD_LOCK_GRADLE_RUN_SECONDS",
                                         "FIREMUD_LOCK_GRADLE_CANCEL_SECONDS")):
            with self.subTest(setting=setting):
                process, fixture, log = self.launch(f"huge-budget-{index}", **{setting: "9" * 400})
                self.assertEqual(process.wait(timeout=5), 1)
                self.assertFalse((fixture / "ready").exists())
                self.assertIn(f"{setting} must be a positive integer", self.output(log))
                self.assertIn("finite deadline", self.output(log))


if __name__ == "__main__":
    unittest.main()
