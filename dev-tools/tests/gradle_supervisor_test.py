"""Behavioral proof for the canonical wrapper without starting repository Gradle."""

import importlib.util
import os
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
        self.processes = []
        self.logs = []
        self.fixture = self.root / "fake-gradle"
        self.fixture.write_text("""#!/usr/bin/env python3
import os, pathlib, signal, subprocess, sys, time
root = pathlib.Path(os.environ['FIXTURE_DIR'])
(root / 'args').write_text(' '.join(sys.argv[1:]))
(root / 'ready').write_text(str(os.getpid()))
mode = os.environ.get('FIXTURE_MODE', 'hold')
if mode == 'exit': sys.exit(23)
if mode == 'stdin':
    (root / 'stdin').write_text(sys.stdin.readline())
    sys.exit(0)
if mode == 'worker':
    worker = subprocess.Popen([sys.executable, '-c', 'import os,pathlib,signal,time; pathlib.Path(os.environ["FIXTURE_DIR"],"worker").write_text(str(os.getpid())); signal.signal(signal.SIGTERM, signal.SIG_IGN); time.sleep(60)'], start_new_session=True)
    while not (root / 'worker').exists(): time.sleep(.01)
    sys.exit(0)
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

    def launch(self, name, task=":account-service:test", *, checkout_name=None, diagnostic_pipe=False, **settings):
        fixture_dir = self.root / f"fixture-{name}"
        fixture_dir.mkdir()
        env = {**os.environ, "CI": "false", "FIXTURE_DIR": str(fixture_dir),
               "FIREMUD_LOCK_GRADLE_EXEC": str(self.fixture),
               "FIREMUD_LOCK_GRADLE_RESOURCE_DIR": str(self.resource),
               "FIREMUD_LOCK_GRADLE_RUN_SECONDS": "10",
               "FIREMUD_LOCK_GRADLE_CANCEL_SECONDS": "1", **settings}
        log = (self.root / f"{name}.log").open("w+")
        self.logs.append(log)
        process = subprocess.Popen(["bash", str(self.checkout(checkout_name or name)), task], env=env,
                                   stdout=log, stderr=subprocess.PIPE if diagnostic_pipe else log,
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

    def test_interrupt_and_hangup_exit_status(self):
        for sig in (signal.SIGINT, signal.SIGHUP):
            process, fixture, _ = self.launch(f"signal-{sig}")
            pid = self.ready(fixture)
            process.send_signal(sig)
            self.assertEqual(process.wait(timeout=5), 128 + sig)
            self.assertFalse(self.running(pid))

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
        args = SimpleNamespace(root=str(self.root), wrapper_pid=os.getpid(), command=["fixture"], lock=[])
        with mock.patch.dict(os.environ, env):
            supervisor = SUPERVISOR.Supervisor(args)

        def scan(_token):
            state["scans"] += 1
            if transient_error and state["scans"] == 1:
                raise OSError("temporary procfs read failure")
            if state["scans"] == 1 or not state["worker_alive"]:
                return {}
            return {worker_pid: "worker-start"}

        def is_alive(pid, _start):
            return pid == worker_pid and state["worker_alive"]

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
             mock.patch.object(SUPERVISOR, "identity", return_value=("client-start", "Z")), \
             mock.patch.object(SUPERVISOR, "alive", side_effect=is_alive), \
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

    @staticmethod
    def running(pid):
        try:
            return Path(f"/proc/{pid}/stat").read_text().rsplit(")", 1)[1].split()[0] != "Z"
        except FileNotFoundError:
            return False

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


if __name__ == "__main__":
    unittest.main()
