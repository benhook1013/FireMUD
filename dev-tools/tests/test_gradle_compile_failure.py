"""Focused failure-capture contracts; all Gradle commands are inert fixtures."""

import importlib.util
import io
import json
import os
import shutil
import signal
import subprocess
import sys
import tempfile
import time
import unittest
from pathlib import Path
from unittest.mock import Mock, patch

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location(
    'capture', ROOT / 'dev-tools/validation/collect-gradle-compile-failure.py')
CAPTURE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(CAPTURE)


class CaptureTests(unittest.TestCase):
    def setUp(self):
        (ROOT / 'build').mkdir(exist_ok=True)
        self.temp = tempfile.TemporaryDirectory(dir=ROOT / 'build', prefix='compile-failure-test-')
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.log = self.root / 'check.log'
        self.log.write_text('> Task :common-saga:compileJava FAILED\n'
                            'Compiling with toolchain /jdk21\n'
                            'SECRET=do-not-retain\nBad.java:1: error: bad\n')
        self.output = self.root / 'evidence'
        self.write('dev-tools/validation/run-locked-gradle.sh', '#!/bin/bash\n'
                   'printf "%s\\n" "$@" > locked-args\n'
                   'exec "$(dirname "$0")/../../gradlew" "$@"\n')

    def write(self, relative, content='source'):
        path = self.root / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content)
        return path

    def stall_identity(self, pid=123, parent=100, role='daemon'):
        return {'pid': pid, 'ppid': parent, 'start': 42, 'role': role,
                'state': 'S', 'cpu_ticks': 1, 'rss_pages': 10, 'jcmd': '/unused/jcmd'}

    def test_stall_timer_activity_attribution_and_one_batch(self):
        identity = self.stall_identity()
        with patch.object(CAPTURE.time, 'monotonic', return_value=0) as clock, patch.object(
                CAPTURE, 'java_identity', return_value=identity), patch.object(
                CAPTURE.subprocess, 'Popen') as launch, patch.object(CAPTURE.os, 'killpg') as kill, patch.object(
                CAPTURE, 'owns_session', return_value=True), patch.object(CAPTURE.os, 'waitid', return_value=None):
            observer = CAPTURE.StallObserver(self.root, self.output)
            observer.observe(b'The client will now receive all logging from the daemon (pi')
            observer.observe(b'd: 123). The daemon log file: secret')
            self.assertEqual(identity, observer.daemon)
            clock.return_value = 299
            observer.tick()
            launch.assert_not_called()
            observer.observe(b'progress')
            clock.return_value = 598
            observer.tick()
            launch.assert_not_called()
            clock.return_value = 599
            observer.tick()
            launch.assert_called_once()
            self.assertTrue(launch.call_args.kwargs['start_new_session'])
            self.assertEqual(subprocess.DEVNULL, launch.call_args.kwargs['stdin'])
            clock.return_value = 690
            observer.tick()
            kill.assert_called_once()
            observer.observe(b'new progress')
            clock.return_value = 1000
            observer.tick()
            launch.assert_called_once()

    def test_stall_without_daemon_and_launch_failure_are_nonfatal(self):
        with patch.object(CAPTURE.time, 'monotonic', side_effect=[0, 301]), patch.object(
                CAPTURE.subprocess, 'Popen') as launch:
            observer = CAPTURE.StallObserver(self.root, self.output)
            observer.tick()
            launch.assert_called_once()
            self.assertIn('null', launch.call_args.args[0])
        with patch.object(CAPTURE.time, 'monotonic', side_effect=[0, 301]), patch.object(
                CAPTURE.subprocess, 'Popen', side_effect=OSError('unavailable')):
            observer = CAPTURE.StallObserver(self.root, self.output)
            observer.daemon = self.stall_identity()
            observer.tick()
            self.assertTrue(observer.attempted)
            self.assertIsNone(observer.child)

    def test_stall_processes_include_both_workers_exclude_unrelated_and_reused(self):
        proc = self.root / 'proc'
        java = self.write('jdk/bin/java')
        def process(pid, parent, main, display=b'', executable=None):
            base = proc / str(pid)
            base.mkdir(parents=True)
            fields = ['0'] * 22
            fields[0], fields[1], fields[19] = 'S', str(parent), '42'
            (base / 'stat').write_text(f'{pid} (java) ' + ' '.join(fields))
            (base / 'cmdline').write_bytes(b'java\0-Dfixture=SECRET\0' + main + b'\0' + display + b'\0')
            (base / 'exe').symlink_to(executable or java)
        process(100, 1, b'org.gradle.launcher.daemon.bootstrap.GradleDaemon')
        for pid in (101, 102):
            process(pid, 100, b'worker.org.gradle.process.internal.worker.GradleWorkerMain',
                    b"'Gradle Test Executor 4'")
        process(103, 999, b'worker.org.gradle.process.internal.worker.GradleWorkerMain',
                b"'Gradle Test Executor 5'")
        process(104, 100, b'other.JavaMain')
        process(105, 100, b'SECRET' * 2000)
        process(106, 101, b'worker.org.gradle.process.internal.worker.GradleWorkerMain',
                b"'Gradle Test Executor 6'")
        process(107, 100, b'worker.org.gradle.process.internal.worker.GradleWorkerMain',
                b"'Gradle Test Executor 7'", self.write('jdk/bin/not-java'))
        with patch.object(CAPTURE, 'PROC_ROOT', proc):
            daemon = CAPTURE.java_identity(100)
            coverage = {}
            selected = CAPTURE.stall_processes(daemon, coverage)
            self.assertEqual(2, coverage['workers_selected'])
            self.assertEqual({'selected-test-worker': 2, 'unrecognized-java-role': 1,
                              'command-line-limit': 1, 'not-java-executable': 1}, coverage['selection_counts'])
            self.assertEqual({101, 102, 104, 105, 107}, {c['pid'] for c in coverage['candidates']})
            self.assertNotIn('SECRET', json.dumps(coverage))
            self.assertEqual({100, 101, 102}, {p['pid'] for p in selected})
            self.assertNotIn('SECRET', json.dumps(selected))
            self.assertEqual([], CAPTURE.stall_processes({**daemon, 'start': 43}))
            with patch.object(CAPTURE, 'STALL_MAX_PROCESSES', 2), patch.object(
                    CAPTURE, 'STALL_MAX_CANDIDATES', 1):
                limited = {}
                self.assertEqual(2, len(CAPTURE.stall_processes(daemon, limited)))
                self.assertEqual(1, limited['selection_counts']['attachment-limit'])
                self.assertEqual(1, len(limited['candidates']))
                self.assertEqual(4, limited['candidates_omitted'])
            with patch.object(CAPTURE, 'MAX_VISITED_ENTRIES', 1):
                limited = {}
                CAPTURE.stall_processes(daemon, limited)
                self.assertTrue(limited['scan_limit_reached'])
            reason = {}
            with patch.object(Path, 'open', side_effect=PermissionError('SECRET')):
                self.assertIsNone(CAPTURE.java_identity(101, reason))
            self.assertEqual({'reason': 'access-denied'}, reason)

    def test_stall_worker_launch_shape_is_exact_and_bounded(self):
        proc = self.root / 'proc'
        base = proc / '123'
        base.mkdir(parents=True)
        java = self.write('jdk/bin/java')
        (base / 'exe').symlink_to(java)
        fields = ['0'] * 22
        fields[0], fields[1], fields[19] = 'S', '100', '42'
        (base / 'stat').write_text('123 (java) ' + ' '.join(fields))
        main = b'worker.org.gradle.process.internal.worker.GradleWorkerMain'
        label = b"'Gradle Test Executor 3'"
        # The classpath @file precedes the explicit main and is never opened.
        valid = [b'java', b'-Xmx512m', b'@/unreadable/classpath', main, label]
        cases = [
            (valid, True),
            ([b'java', main, b"'Gradle Worker Daemon 3'"], False),
            ([b'java', main, b'Gradle Test Executor 3'], False),
            ([b'java', main, b'"Gradle Test Executor 3"'], False),
            ([b'java', main, b"'Gradle Test Executor 3' suffix"], False),
            ([b'java', main, b"'Gradle Test Executor 0'"], False),
            ([b'java', main, b"'Gradle Test Executor -3'"], False),
            ([b'java', main, b"'Gradle Test Executor 3\n'"], False),
            ([b'java', main + b'Extra', label], False),
            ([b'java', b'-Dmain=' + main, label], False),
            ([b'java', main, b'unrelated', label], False),
            ([*valid, b'extra'], False),
            ([b'java', b'@/unreadable/launch'], False),
            ([b'java', b'-Dsecret=' + b'x' * 8192, main, label], False),
        ]
        with patch.object(CAPTURE, 'PROC_ROOT', proc):
            for index, (argv, accepted) in enumerate(cases):
                with self.subTest(case=index):
                    (base / 'cmdline').write_bytes(b'\0'.join(argv) + b'\0')
                    result = CAPTURE.java_identity(123)
                    self.assertEqual(accepted, result is not None)
                    if result:
                        self.assertEqual('test-worker', result['role'])
                        self.assertNotIn('argv', result)
                        self.assertNotIn('classpath', json.dumps(result))

    def test_stall_coverage_distinguishes_no_children_from_no_attribution(self):
        daemon = self.stall_identity()
        coverage = {}
        with patch.object(CAPTURE, 'java_identity', return_value=daemon), patch.object(
                CAPTURE.os, 'scandir') as entries:
            entries.return_value.__enter__.return_value = iter([])
            self.assertEqual([daemon], CAPTURE.stall_processes(daemon, coverage))
        self.assertEqual('verified', coverage['daemon_identity'])
        self.assertEqual(0, coverage['workers_selected'])
        self.assertEqual([], coverage['candidates'])
        self.assertFalse(coverage['scan_limit_reached'])
        with patch.object(CAPTURE, 'java_identity', return_value=None):
            self.assertEqual([], CAPTURE.stall_processes(daemon, coverage))
        self.assertEqual('exited-or-changed', coverage['daemon_identity'])

    def test_stall_daemon_retains_late_frames_with_fixed_larger_ceiling(self):
        fake = self.write('jcmd', '#!/usr/bin/env python3\n'
                          'print("ignored" * 24000)\n'
                          'print("at org.example.Late.run(Late.java:42)")\n')
        fake.chmod(0o755)
        for role in ('daemon', 'test-worker'):
            identity = {**self.stall_identity(role=role), 'jcmd': str(fake)}
            with patch.object(CAPTURE, 'java_identity', return_value=identity):
                result = CAPTURE.thread_snapshot(identity)
            if role == 'daemon':
                self.assertEqual('complete', result['status'])
                self.assertIn('Late.run', result['threads'])
                self.assertEqual(512 * 1024, result['dump_byte_limit'])
            else:
                self.assertEqual('limited', result['status'])
                self.assertNotIn('Late.run', result['threads'])
                self.assertEqual(128 * 1024, result['dump_byte_limit'])

    def test_stall_sanitizer_rejects_names_secrets_and_diagnostic_prose(self):
        raw = (b'"SECRET-password" #17 tid=0xabc nid=0xdef waiting\n'
               b'   java.lang.Thread.State: WAITING (parking)\n'
               b'        at java.lang.Object.wait0(java.base@25/Native Method)\n'
               b'        at jdk.internal.misc.Unsafe.park(java.base@25.0.5+8/Native Method)\n'
               b'\"SECRET-decimal-thread\" #18 tid=0x123 nid=345 waiting\n'
               b'        at org.example.Work.run(Work.java:42)\n'
               b'        - locked <0xabc> (a java.lang.Object)\n'
               b'JAVA_TOOL_OPTIONS=SECRET\njdbc:postgresql://SECRET\n'
               b'SELECT SECRET FROM private\nVM.command_line SECRET\n')
        output = CAPTURE.sanitized_threads(raw)
        self.assertNotIn('SECRET', output)
        self.assertIn('#17 tid=0xabc nid=0xdef', output)
        self.assertIn('#18 tid=0x123 nid=345', output)
        self.assertIn('Object.wait0(java.base@25/Native Method)', output)
        self.assertIn('Unsafe.park(java.base@25.0.5+8/Native Method)', output)
        self.assertIn('WAITING', output)
        self.assertIn('Work.run(Work.java:42)', output)
        self.assertIn('locked <0xabc>', output)

    def test_stall_identity_change_never_attaches(self):
        original = self.stall_identity()
        with patch.object(CAPTURE, 'java_identity', return_value={**original, 'start': 43}), patch.object(
                CAPTURE.subprocess, 'Popen') as launch:
            result = CAPTURE.thread_snapshot(original)
        self.assertEqual('exited-or-changed', result['status'])
        launch.assert_not_called()

    def test_stall_fake_jcmd_output_limit_and_timeout(self):
        fake = self.write('jcmd', '#!/usr/bin/env python3\n'
                          'print("at org.example.Work.run(Work.java:42)", flush=True)\n'
                          'print("SECRET" * 1000, flush=True)\n'
                          'import time; time.sleep(10)\n')
        fake.chmod(0o755)
        identity = {**self.stall_identity(), 'jcmd': str(fake)}
        with patch.object(CAPTURE, 'java_identity', return_value=identity), patch.object(
                CAPTURE, 'STALL_DUMP_BYTES', 100):
            result = CAPTURE.thread_snapshot(identity)
        self.assertEqual('limited', result['status'])
        self.assertNotIn('SECRET', json.dumps(result))
        self.assertNotIn('jcmd', result)
        fake.write_text('#!/usr/bin/env python3\nimport time; time.sleep(10)\n')
        with patch.object(CAPTURE, 'java_identity', return_value=identity), patch.object(
                CAPTURE, 'STALL_COMMAND_SECONDS', 0.1):
            result = CAPTURE.thread_snapshot(identity)
        self.assertEqual('timeout', result['status'])

    def test_stall_missing_jcmd_is_nonfatal(self):
        identity = self.stall_identity()
        with patch.object(CAPTURE, 'java_identity', return_value=identity):
            self.assertEqual('unavailable', CAPTURE.thread_snapshot(identity)['status'])

    def test_stall_snapshots_are_fresh_bounded_and_reject_symlinks(self):
        identity = self.stall_identity()
        with patch.object(CAPTURE, 'stall_processes', return_value=[identity]), patch.object(
                CAPTURE, 'thread_snapshot', return_value={'status': 'complete'}), patch.object(
                CAPTURE.time, 'sleep'), patch('sys.stdout', io.StringIO()):
            CAPTURE.collect_stall(self.root, self.output, identity)
            CAPTURE.collect_stall(self.root, self.output, identity)
        self.assertEqual(4, len(list(self.output.glob('observation-*/snapshot-*.json'))))
        link = self.root / 'escape'
        link.symlink_to(self.output, target_is_directory=True)
        with self.assertRaises(ValueError):
            CAPTURE.collect_stall(self.root, link, identity)

    def test_stall_session_guard_never_signals_parent_or_unowned_group(self):
        with patch.object(CAPTURE.os, 'getpgid', return_value=50), patch.object(
                CAPTURE.os, 'getsid', return_value=50):
            self.assertFalse(CAPTURE.owns_session(123))
        with patch.object(CAPTURE, 'owns_session', return_value=False), patch.object(
                CAPTURE.os, 'killpg') as kill:
            with self.assertRaises(SystemExit):
                CAPTURE.stop_stall_session(signal.SIGALRM, None)
            observer = CAPTURE.StallObserver(self.root, self.output)
            child = observer.child = Mock()
            observer.close()
            kill.assert_not_called()
            child.wait.assert_not_called()
        with patch.object(CAPTURE, 'owns_session', return_value=True), patch.object(
                CAPTURE.os, 'killpg') as kill, self.assertRaises(SystemExit):
            CAPTURE.stop_stall_session(signal.SIGALRM, None)
        kill.assert_called_once_with(os.getpid(), signal.SIGKILL)

    def test_stall_capture_cancellation_closes_observer(self):
        observer = Mock()
        observer.tick.side_effect = SystemExit(143)
        read_fd, write_fd = os.pipe()
        os.close(write_fd)
        with os.fdopen(read_fd, 'rb') as source, self.assertRaises(SystemExit):
            list(CAPTURE.capture_chunks(source, observer))
        observer.close.assert_called_once()

    def test_stall_missing_attribution_is_explicit_and_never_enumerates_jvms(self):
        with patch.object(CAPTURE, 'stall_processes') as enumerate_jvms, patch.object(
                CAPTURE.time, 'sleep'), patch('sys.stdout', io.StringIO()) as output:
            CAPTURE.collect_stall(self.root, self.output, None)
        enumerate_jvms.assert_not_called()
        self.assertIn('"daemon_attribution": "unavailable"', output.getvalue())

    def test_stall_blocked_diagnostic_sink_is_reaped_without_touching_other_processes(self):
        child = subprocess.Popen([sys.executable, '-c',
                                  'import os;\nwhile True: os.write(1, b"x" * 65536)'],
                                 stdout=subprocess.PIPE, start_new_session=True)
        unrelated = subprocess.Popen([sys.executable, '-c', 'import time; time.sleep(30)'])
        self.addCleanup(unrelated.wait)
        self.addCleanup(unrelated.kill)
        self.addCleanup(child.stdout.close)
        self.addCleanup(child.wait)
        self.addCleanup(lambda: child.kill() if child.poll() is None else None)
        observer = CAPTURE.StallObserver(self.root, self.output)
        observer.child, observer.started, observer.attempted = child, time.monotonic(), True
        read_fd, write_fd = os.pipe()
        os.write(write_fd, b'build output')
        os.close(write_fd)
        with os.fdopen(read_fd, 'rb') as source:
            self.assertEqual([b'build output'], list(CAPTURE.capture_chunks(source, observer)))
        self.assertIsNotNone(child.poll())
        self.assertIsNone(unrelated.poll())

    def test_stall_exited_or_reused_daemon_is_not_labelled_verified(self):
        with patch.object(CAPTURE, 'stall_processes', return_value=[]), patch.object(
                CAPTURE.time, 'sleep'), patch('sys.stdout', io.StringIO()) as output:
            CAPTURE.collect_stall(self.root, self.output, self.stall_identity())
        self.assertIn('"daemon_attribution": "unavailable"', output.getvalue())
        for path in self.output.glob('observation-*/snapshot-*.json'):
            self.assertEqual('unavailable', json.loads(path.read_text())['daemon_attribution'])

    def test_stall_symlinked_checkout_preserves_output_containment(self):
        alias = self.root / 'checkout'
        actual = self.root / 'actual'
        actual.mkdir()
        alias.symlink_to(actual, target_is_directory=True)
        with patch.object(CAPTURE.time, 'sleep'), patch('sys.stdout', io.StringIO()):
            CAPTURE.collect_stall(alias, alias / 'build/evidence', None)
        self.assertEqual(2, len(list(actual.glob('build/evidence/observation-*/snapshot-*.json'))))
        (actual / 'escape').symlink_to(self.root, target_is_directory=True)
        with self.assertRaises(ValueError):
            CAPTURE.collect_stall(alias, alias / 'escape/evidence', None)
        with self.assertRaises(ValueError):
            CAPTURE.collect_stall(alias, alias / '../outside', None)

    def test_stall_marker_is_fixed_nonblocking_and_does_not_expose_failure_text(self):
        with patch.object(CAPTURE.os, 'open', return_value=99) as opening, patch.object(
                CAPTURE.os, 'write', side_effect=BlockingIOError('SECRET')) as writing, patch.object(
                CAPTURE.os, 'close') as closing:
            CAPTURE.stall_marker('launch-failed')
        self.assertTrue(opening.call_args.args[1] & os.O_NONBLOCK)
        self.assertTrue(opening.call_args.args[1] & os.O_APPEND)
        self.assertEqual(b'FIREMUD_STALL_OBSERVER status=launch-failed\n', writing.call_args.args[1])
        closing.assert_called_once_with(99)
        with patch.object(CAPTURE.os, 'open') as opening:
            CAPTURE.stall_marker('SECRET exception path')
        opening.assert_not_called()

    def test_stall_private_mode_reports_safe_setup_failure_in_subprocess(self):
        output = self.root / 'escape'
        output.symlink_to(self.root, target_is_directory=True)
        result = subprocess.run(
            [sys.executable, str(ROOT / 'dev-tools/validation/collect-gradle-compile-failure.py'),
             '--stall-daemon', 'null', '--root', str(self.root), '--output', str(output)],
            start_new_session=True, capture_output=True, text=True, timeout=5, check=False)
        self.assertEqual(1, result.returncode)
        self.assertIn('status=started', result.stdout)
        self.assertIn('status=setup-failed', result.stdout)
        self.assertNotIn(str(self.root), result.stdout + result.stderr)
        self.assertNotIn('Traceback', result.stdout + result.stderr)

    def test_stall_enabled_capture_subprocess_preserves_bytes_through_idle_observation(self):
        # Copy only the executable under test; shorten constants without attaching to a JVM.
        script = self.root / 'collector.py'
        script.write_text((ROOT / 'dev-tools/validation/collect-gradle-compile-failure.py').read_text()
                          .replace('STALL_IDLE_SECONDS = 300', 'STALL_IDLE_SECONDS = 0.01')
                          .replace('STALL_BATCH_SECONDS = 90', 'STALL_BATCH_SECONDS = 2'))
        process = subprocess.Popen(
            [sys.executable, str(script), '--capture-log', str(self.log), '--root', str(self.root),
             '--stall-output', str(self.output)], stdin=subprocess.PIPE,
            stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        self.addCleanup(lambda: process.kill() if process.poll() is None else None)
        deadline = time.monotonic() + 4
        while not list(self.output.glob('observation-*/snapshot-1.json')):
            if time.monotonic() > deadline:
                self.fail('bounded fake observer did not create its first snapshot')
            time.sleep(0.01)
        output, error = process.communicate(b'original bytes\n', timeout=5)
        self.assertEqual(0, process.returncode)
        self.assertEqual(b'original bytes\n', output)
        self.assertIn(b'status=enabled', error)
        self.assertIn(b'status=started', error)
        self.assertNotIn(b'Traceback', error)

    def test_stall_capture_keeps_exact_stdout_and_closes_on_eof(self):
        read_fd, write_fd = os.pipe()
        os.write(write_fd, b'original output\n')
        os.close(write_fd)
        observer = Mock()
        destination = io.BytesIO()
        with os.fdopen(read_fd, 'rb') as source:
            status = CAPTURE.capture_log(source, destination, self.log, observer)
        self.assertEqual(0, status)
        self.assertEqual(b'original output\n', destination.getvalue())
        self.assertEqual(destination.getvalue(), self.log.read_bytes())
        observer.observe.assert_called_once_with(b'original output\n')
        observer.close.assert_called_once()

    def test_stall_capture_falls_back_for_regular_file_input(self):
        input_path = self.root / 'build-output.log'
        input_path.write_bytes(b'original build output\n')
        observer = Mock()
        destination = io.BytesIO()
        with patch.object(CAPTURE, 'MAX_LOG_BYTES', 8), patch.object(
                CAPTURE, 'stall_marker') as marker, input_path.open('rb') as source:
            status = CAPTURE.capture_log(source, destination, self.log, observer)
        self.assertEqual(0, status)
        self.assertEqual(b'original build output\n', destination.getvalue())
        self.assertEqual(b' output\n', self.log.read_bytes())
        marker.assert_called_once_with('observer-failed')
        observer.observe.assert_not_called()
        observer.close.assert_called_once()

    def test_stall_capture_registration_failure_closes_observer_without_hiding_read_error(self):
        selector = Mock()
        selector.register.side_effect = PermissionError('regular files cannot be epoll-registered')
        observer = Mock()

        class BrokenSource:
            def read(self, size):
                raise OSError('direct read failed')

        with patch.object(CAPTURE.selectors, 'DefaultSelector', return_value=selector), patch.object(
                CAPTURE, 'stall_marker') as marker, self.assertRaisesRegex(OSError, 'direct read failed'):
            list(CAPTURE.capture_chunks(BrokenSource(), observer))
        selector.close.assert_called_once()
        observer.close.assert_called_once()
        marker.assert_called_once_with('observer-failed')

    def test_module_wrapper_uses_two_workers_including_fallback(self):
        for module, partition, expected in [('account-service', '4', [2, 2]),
                                            ('account-service', 'full', [2]),
                                            ('game-session-service', '4', [2, 2]),
                                            ('world-management-service', 'full', [2])]:
            for fallback in (False, True):
                with self.subTest(module=module, partition=partition, fallback=fallback):
                    root = self.root / f'{module}-{partition}-{fallback}'
                    script = root / '.github/scripts/run-gradle-module-checks.sh'
                    script.parent.mkdir(parents=True)
                    shutil.copyfile(ROOT / '.github/scripts/run-gradle-module-checks.sh', script)
                    validation = root / 'dev-tools/validation'
                    validation.mkdir(parents=True)
                    shutil.copyfile(ROOT / 'dev-tools/validation/collect-gradle-compile-failure.py',
                                    validation / 'collect-gradle-compile-failure.py')
                    (validation / 'run-gradle-with-dependency-recovery.sh').write_text(
                        '#!/bin/bash\nrunner="$2"; shift 3; exec "$runner" "$@"\n')
                    (validation / 'slow_service_ci_test_partition.py').write_text('# Inert planner fixture.\n')
                    runner = root / 'gradlew'
                    runner.write_text('#!/bin/bash\nprintf "%s\\n" "$*" >> calls\n'
                                      'printf "original-check-output\\n"\n')
                    runner.chmod(0o755)
                    env = {**os.environ, 'GITHUB_ACTIONS': 'true', 'RUNNER_ENVIRONMENT': 'github-hosted',
                           'RUNNER_OS': 'Linux'}
                    if fallback:
                        binary = root / 'bin'
                        binary.mkdir()
                        mktemp = binary / 'mktemp'
                        mktemp.write_text('#!/bin/bash\nexit 1\n')
                        mktemp.chmod(0o755)
                        env['PATH'] = str(binary) + os.pathsep + env['PATH']
                    result = subprocess.run(['bash', str(script), module, partition], cwd=root, env=env,
                                            capture_output=True, text=True, timeout=5, check=False)
                    self.assertEqual(0, result.returncode, result.stderr)
                    calls = (root / 'calls').read_text().splitlines()
                    self.assertEqual(len(expected), len(calls))
                    for call, workers in zip(calls, expected, strict=True):
                        self.assertIn(f'--max-workers={workers}', call)
                        self.assertIn('-PfullCheck', call)
                        self.assertNotIn('--tests', call)
                    self.assertEqual(len(expected), result.stdout.count('original-check-output'))
                    if partition != 'full':
                        self.assertIn(f'-PslowServiceCiPartition={partition}', calls[-1])
                        self.assertIn(f':{module}:testClasses', calls[0])
                    self.assertIn(f':{module}:check', calls[-1])
                    marker = ('disabled-capture-unavailable' if fallback else
                              'enabled' if module == 'account-service' and partition != 'full' else
                              'disabled-scope')
                    self.assertIn(f'FIREMUD_STALL_ACTIVATION status={marker}', result.stderr)

    def test_compile_failure_bounds_and_symlink_rejection(self):
        self.write('services/common-saga/build/generated/source/Bad.java', 'bad')
        self.write('services/common-saga/build/generated/source/Huge.java', 'x' * 100)
        self.write('protos/owner/service.proto', 'proto')
        external = self.write('private/outside.java', 'secret')
        generated = self.root / 'services/common-saga/build/generated'
        (generated / 'Escape.java').symlink_to(external)
        (generated / 'linked').symlink_to(external.parent, target_is_directory=True)
        with patch.object(CAPTURE, 'MAX_FILE_BYTES', 20), patch.object(
                CAPTURE, 'diagnostic', return_value={'status': 'unavailable'}):
            CAPTURE.collect(self.root, self.log, self.output, 17)
        manifest = json.loads((self.output / 'manifest.json').read_text())
        paths = [entry['path'] for entry in manifest['files']]
        self.assertIn('services/common-saga/build/generated/source/Bad.java', paths)
        self.assertIn('protos/owner/service.proto', paths)
        self.assertEqual(1, manifest['omitted'])
        self.assertNotIn('secret', json.dumps(manifest))
        self.assertNotIn('SECRET', json.dumps(manifest))
        self.assertEqual(17, manifest['check_exit'])
        self.assertTrue(all(not p.is_symlink() for p in self.output.rglob('*')))

    def test_total_and_file_count_budgets(self):
        for n in range(4):
            self.write(f'protos/{n}.proto', '1234')
        with patch.object(CAPTURE, 'MAX_FILES', 2), patch.object(CAPTURE, 'MAX_BYTES', 7), patch.object(
                CAPTURE, 'diagnostic', return_value={'status': 'unavailable'}):
            CAPTURE.collect(self.root, self.log, self.output, 1)
        manifest = json.loads((self.output / 'manifest.json').read_text())
        self.assertEqual(4, manifest['captured_bytes'])
        self.assertEqual(3, manifest['omitted'])

    def test_noncompile_failure_emits_nothing(self):
        self.log.write_text('> Task :common-saga:test FAILED\n')
        CAPTURE.collect(self.root, self.log, self.output, 1)
        self.assertFalse(self.output.exists())

    def test_diagnostic_failure_is_explicit(self):
        with patch.object(CAPTURE, 'diagnostic', side_effect=OSError('private details')):
            CAPTURE.collect(self.root, self.log, self.output, 19)
        manifest = json.loads((self.output / 'manifest.json').read_text())
        self.assertEqual(19, manifest['check_exit'])
        self.assertEqual('OSError', manifest['diagnostics'][0]['reason'])
        self.assertNotIn('private details', json.dumps(manifest))

    def test_same_module_compile_tasks_keep_distinct_diagnostics(self):
        tasks = [':common-saga:compileJava', ':common-saga:compileTestJava']
        self.log.write_text(''.join(f'> Task {task} FAILED\n' for task in tasks))
        results = {tasks[0]: ('complete', 0), tasks[1]: ('failed', 1)}

        def diagnostic(root, task, target):
            status, exit_code = results[task]
            target.write_text(json.dumps({'task': task, 'facts': f'facts for {task}'}) + '\n')
            return {'status': status, 'exit': exit_code}

        with patch.object(CAPTURE, 'diagnostic', side_effect=diagnostic):
            CAPTURE.collect(self.root, self.log, self.output, 23)

        expected_names = {CAPTURE.diagnostic_basename(task) for task in tasks}
        actual_names = {path.name for path in self.output.glob('*-diagnostic.json')}
        self.assertEqual(expected_names, actual_names)
        for task in tasks:
            contents = json.loads((self.output / CAPTURE.diagnostic_basename(task)).read_text())
            self.assertEqual({'task': task, 'facts': f'facts for {task}'}, contents)

        manifest = json.loads((self.output / 'manifest.json').read_text())
        self.assertEqual(tasks, manifest['failed_tasks'])
        self.assertEqual(23, manifest['check_exit'])
        self.assertEqual({task: results[task][0] for task in tasks},
                         {entry['task']: entry['status'] for entry in manifest['diagnostics']})

    def test_diagnostic_retains_only_structured_facts_and_no_check_command(self):
        runner = self.write('gradlew', '#!/bin/bash\n'
                            'printf "%s\\n" "$@" > diagnostic-args\n'
                            'echo "SECRET=must-not-retain"\n'
                            "echo 'FIREMUD_COMPILE_FACTS={\"attribution\":\"post-failure\"}'\n")
        runner.chmod(0o755)
        target = self.root / 'facts.json'
        result = CAPTURE.diagnostic(self.root, ':common-saga:compileJava', target)
        self.assertEqual('complete', result['status'])
        self.assertEqual({'attribution': 'post-failure'}, json.loads(target.read_text()))
        arguments = (self.root / 'diagnostic-args').read_text().splitlines()
        self.assertEqual(arguments, (self.root / 'locked-args').read_text().splitlines())
        self.assertIn('--offline', arguments)
        self.assertIn('--no-configuration-cache', arguments)
        self.assertEqual(':common-saga:firemudCompileFailureFacts', arguments[-1])
        self.assertFalse(any(arg.endswith(':check') for arg in arguments))
        self.assertNotIn('--refresh-dependencies', arguments)

    def test_diagnostic_preserves_only_the_inherited_resource_descriptor(self):
        runner = self.write('gradlew', f'#!{sys.executable}\n'
                            'import json, os\n'
                            'fd = int(os.environ["FIREMUD_LOCAL_RESOURCE_SESSION_FD"])\n'
                            'os.fstat(fd)\n'
                            'other = int(os.environ["FIXTURE_UNRELATED_FD"])\n'
                            'try:\n'
                            '    os.fstat(other)\n'
                            'except OSError:\n'
                            '    pass\n'
                            'else:\n'
                            '    raise SystemExit("unrelated descriptor inherited")\n'
                            'print("FIREMUD_COMPILE_FACTS=" + json.dumps({"lease_descriptor_open": True}))\n')
        runner.chmod(0o755)
        with (self.root / 'lease').open('w') as lease, (self.root / 'unrelated').open('w') as other:
            os.set_inheritable(other.fileno(), True)
            with patch.dict(os.environ, {
                    'FIREMUD_LOCAL_RESOURCE_SESSION_FD': str(lease.fileno()),
                    'FIXTURE_UNRELATED_FD': str(other.fileno())}):
                target = self.root / 'facts.json'
                result = CAPTURE.diagnostic(self.root, ':common-saga:compileJava', target)
        self.assertEqual('complete', result['status'])
        self.assertEqual({'lease_descriptor_open': True}, json.loads(target.read_text()))

    def test_output_symlink_escape_rejected(self):
        external = self.root / 'outside'
        external.mkdir()
        (self.root / 'linked-output').symlink_to(external, target_is_directory=True)
        with self.assertRaises(ValueError):
            CAPTURE.collect(self.root, self.log, self.root / 'linked-output/capture', 1)
        self.assertEqual([], list(external.iterdir()))

    def test_symlink_workspace_root_keeps_lexical_output_relation(self):
        actual_root = self.root / 'actual-workspace'
        actual_root.mkdir()
        (actual_root / 'check.log').write_text('> Task :common-saga:compileJava FAILED\n')
        linked_root = self.root / 'workspace-link'
        linked_root.symlink_to(actual_root, target_is_directory=True)
        with patch.object(CAPTURE, 'diagnostic', return_value={'status': 'unavailable'}):
            CAPTURE.collect(linked_root, linked_root / 'check.log', linked_root / 'evidence', 1)
        manifest = actual_root / 'evidence/manifest.json'
        self.assertTrue(manifest.is_file())
        self.assertEqual(1, json.loads(manifest.read_text())['check_exit'])

    def test_exact_diagnostic_cap_never_claims_complete(self):
        data = b'FIREMUD_COMPILE_FACTS={"attribution":"post-failure"}\n'
        data += b'x' * (128 - len(data))
        runner = self.write('gradlew', f'#!{sys.executable}\nimport os\nos.write(1, {data!r})\n')
        runner.chmod(0o755)
        with patch.object(CAPTURE, 'MAX_FILE_BYTES', 128):
            result = CAPTURE.diagnostic(self.root, ':common-saga:compileJava', self.root / 'facts.json')
        self.assertEqual('limited', result['status'])
        self.assertTrue(result['output_limit_reached'])
        self.assertTrue(result['output_possibly_truncated'])

    def test_diagnostic_cache_write_is_not_limited_by_stdout_cap(self):
        runner = self.write(
            'gradlew', f'#!{sys.executable}\n'
            'from pathlib import Path\n'
            f'Path("unrelated-cache.bin").write_bytes(b"x" * {CAPTURE.MAX_FILE_BYTES + 4096})\n'
            'print(\'FIREMUD_COMPILE_FACTS={"attribution":"post-failure"}\')\n')
        runner.chmod(0o755)
        result = CAPTURE.diagnostic(self.root, ':common-saga:compileJava', self.root / 'facts.json')
        self.assertEqual('complete', result['status'])
        self.assertEqual(CAPTURE.MAX_FILE_BYTES + 4096, (self.root / 'unrelated-cache.bin').stat().st_size)
        self.assertEqual({'attribution': 'post-failure'}, json.loads((self.root / 'facts.json').read_text()))

    def assert_process_group_child_exited(self, pid):
        deadline = time.monotonic() + 3
        while time.monotonic() < deadline:
            try:
                with open(f'/proc/{pid}/stat', encoding='ascii') as process_stat:
                    if process_stat.read().split()[2] == 'Z':
                        return
            except (FileNotFoundError, ProcessLookupError):
                return
            time.sleep(0.02)
        self.fail(f'diagnostic child {pid} survived process-group cleanup')

    def test_process_group_child_disappearance_during_stat_read_is_exited(self):
        class DisappearingProcess:
            def __enter__(self):
                return self

            def __exit__(self, *_):
                return False

            def read(self):
                raise ProcessLookupError('process disappeared')

        with patch('builtins.open', return_value=DisappearingProcess()):
            self.assert_process_group_child_exited(12345)

    def test_process_group_child_stat_read_errors_other_than_disappearance_propagate(self):
        class UnreadableProcess:
            def __enter__(self):
                return self

            def __exit__(self, *_):
                return False

            def read(self):
                raise PermissionError('stat unavailable')

        with patch('builtins.open', return_value=UnreadableProcess()), self.assertRaises(PermissionError):
            self.assert_process_group_child_exited(12345)

    def test_diagnostic_output_limit_kills_only_its_process_group(self):
        runner = self.write(
            'gradlew', f'#!{sys.executable}\n'
            'import os, subprocess, sys\n'
            'from pathlib import Path\n'
            'child = subprocess.Popen([sys.executable, "-c", "import time; time.sleep(30)"])\n'
            'Path("child.pid").write_text(str(child.pid))\n'
            'while True: os.write(1, b"x" * 4096)\n')
        runner.chmod(0o755)
        unrelated = subprocess.Popen([sys.executable, '-c', 'import time; time.sleep(30)'])
        self.addCleanup(unrelated.wait)
        self.addCleanup(unrelated.kill)
        with patch.object(CAPTURE, 'MAX_FILE_BYTES', 128):
            result = CAPTURE.diagnostic(self.root, ':common-saga:compileJava', self.root / 'facts.json')
        self.assertIn(result['status'], ['limited', 'unavailable'])
        self.assertTrue(result['output_limit_reached'])
        self.assert_process_group_child_exited(int((self.root / 'child.pid').read_text()))
        self.assertIsNone(unrelated.poll())

    def test_diagnostic_deadline_kills_its_process_group(self):
        runner = self.write(
            'gradlew', f'#!{sys.executable}\n'
            'import subprocess, sys, time\n'
            'from pathlib import Path\n'
            'child = subprocess.Popen([sys.executable, "-c", "import time; time.sleep(30)"])\n'
            'Path("child.pid").write_text(str(child.pid))\n'
            'time.sleep(30)\n')
        runner.chmod(0o755)
        unrelated = subprocess.Popen([sys.executable, '-c', 'import time; time.sleep(30)'])
        self.addCleanup(unrelated.wait)
        self.addCleanup(unrelated.kill)
        with patch.object(CAPTURE, 'DIAGNOSTIC_TIMEOUT_SECONDS', 0.2):
            result = CAPTURE.diagnostic(self.root, ':common-saga:compileJava', self.root / 'facts.json')
        self.assertEqual('timeout', result['status'])
        self.assert_process_group_child_exited(int((self.root / 'child.pid').read_text()))
        self.assertIsNone(unrelated.poll())

    def test_streamed_tail_is_bounded_and_output_is_live(self):
        destination = io.BytesIO()
        chunks = [b'abc', b'def', b'ghijkl']

        class LiveSource:
            def read(self, size):
                if not chunks:
                    return b''
                if len(chunks) < 3:
                    self_test.assertTrue(destination.getvalue())
                return chunks.pop(0)

        self_test = self
        with patch.object(CAPTURE, 'MAX_LOG_BYTES', 5):
            status = CAPTURE.capture_log(LiveSource(), destination, self.log)
        self.assertEqual(0, status)
        self.assertEqual(b'abcdefghijkl', destination.getvalue())
        self.assertEqual(b'hijkl', self.log.read_bytes())
        self.assertEqual(5, self.log.stat().st_size)

    def test_capture_write_failure_still_forwards_and_drains(self):
        source = io.BytesIO(b'abcdefghijkl')
        destination = io.BytesIO()
        with patch.object(CAPTURE, 'MAX_LOG_BYTES', 5), patch.object(
                Path, 'open', side_effect=OSError('disk full')), patch('sys.stderr', io.StringIO()):
            status = CAPTURE.capture_log(source, destination, self.log)
        self.assertEqual(1, status)
        self.assertEqual(b'abcdefghijkl', destination.getvalue())
        self.assertEqual(b'', source.read())

    def test_forwarding_failure_still_drains_and_keeps_bounded_tail(self):
        class BrokenOutput:
            def write(self, chunk):
                raise BrokenPipeError('closed')

        source = io.BytesIO(b'abcdefghijkl')
        with patch.object(CAPTURE, 'MAX_LOG_BYTES', 5), patch('sys.stderr', io.StringIO()):
            status = CAPTURE.capture_log(source, BrokenOutput(), self.log)
        self.assertEqual(1, status)
        self.assertEqual(b'', source.read())
        self.assertEqual(b'hijkl', self.log.read_bytes())

    def test_enumeration_budget_is_reported_and_candidates_are_bounded(self):
        for number in range(100):
            self.write(f'services/common-saga/build/generated/{number}.java')
        with patch.object(CAPTURE, 'MAX_VISITED_ENTRIES', 8), patch.object(
                CAPTURE, 'diagnostic', return_value={'status': 'unavailable'}):
            CAPTURE.collect(self.root, self.log, self.output, 1)
        manifest = json.loads((self.output / 'manifest.json').read_text())
        self.assertEqual(8, manifest['enumeration']['visited'])
        self.assertTrue(manifest['enumeration']['truncated'])
        self.assertLessEqual(len(manifest['files']), 8)

    def test_implicated_generated_filename_wins_file_budget(self):
        self.write('services/common-saga/build/generated/Other.java')
        self.write('services/common-saga/build/generated/Bad.java')
        with patch.object(CAPTURE, 'MAX_FILES', 1), patch.object(
                CAPTURE, 'diagnostic', return_value={'status': 'unavailable'}):
            CAPTURE.collect(self.root, self.log, self.output, 1)
        manifest = json.loads((self.output / 'manifest.json').read_text())
        self.assertTrue(manifest['files'][0]['path'].endswith('/Bad.java'))

    def test_implicated_java_requires_exact_basename_under_file_budget(self):
        self.write('services/common-saga/build/generated/ABad.java')
        self.write('services/common-saga/build/generated/Bad.java')
        with patch.object(CAPTURE, 'MAX_FILES', 1), patch.object(
                CAPTURE, 'diagnostic', return_value={'status': 'unavailable'}):
            CAPTURE.collect(self.root, self.log, self.output, 1)
        manifest = json.loads((self.output / 'manifest.json').read_text())
        self.assertTrue(manifest['files'][0]['path'].endswith('/Bad.java'))

    def test_proto_inputs_are_enumerated_before_generated_budget_is_exhausted(self):
        for number in range(100):
            self.write(f'services/common-saga/build/generated/{number}.java')
        self.write('protos/owner/service.proto')
        with patch.object(CAPTURE, 'MAX_VISITED_ENTRIES', 4), patch.object(
                CAPTURE, 'diagnostic', return_value={'status': 'unavailable'}):
            CAPTURE.collect(self.root, self.log, self.output, 1)
        manifest = json.loads((self.output / 'manifest.json').read_text())
        paths = [entry['path'] for entry in manifest['files']]
        self.assertIn('protos/owner/service.proto', paths)
        self.assertTrue(manifest['enumeration']['truncated'])

    def test_cancellation_skips_direct_collection_and_cli_diagnostics(self):
        for status in [130, 143]:
            with self.subTest(status=status), patch.object(CAPTURE, 'diagnostic') as diagnostic:
                CAPTURE.collect(self.root, self.log, self.output, status)
                diagnostic.assert_not_called()
                self.assertFalse(self.output.exists())
            result = subprocess.run([sys.executable, str(ROOT / 'dev-tools/validation/collect-gradle-compile-failure.py'),
                                     '--root', str(self.root), '--log', str(self.root / 'missing.log'),
                                     '--output', str(self.output), '--check-exit', str(status)],
                                    capture_output=True, text=True, check=False)
            self.assertEqual(0, result.returncode)
            self.assertFalse(self.output.exists())

    def test_cancellation_wrapper_preserves_exit_and_runs_no_diagnostic(self):
        for status in [130, 143]:
            with self.subTest(status=status):
                result = self.fixture_wrapper(status)
                self.assertEqual(status, result.returncode)
                self.assertNotIn('preserving check exit', result.stderr)
        calls = (self.root / 'calls').read_text().splitlines()
        self.assertEqual(2, len(calls))
        self.assertTrue(all(':world-management-service:check' in call for call in calls))

    def fixture_wrapper(self, exit_code):
        for relative in ['.github/scripts/run-gradle-module-checks.sh',
                         'dev-tools/validation/run-gradle-with-dependency-recovery.sh',
                         'dev-tools/validation/collect-gradle-compile-failure.py']:
            destination = self.root / relative
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(ROOT / relative, destination)
        gradlew = self.write('gradlew', '#!/bin/bash\n'
                             'echo "$*" >> calls\n'
                             'echo "> Task :common-saga:compileJava FAILED"\n'
                             'echo "Compilation failed"\n'
                             f'exit {exit_code}\n')
        gradlew.chmod(0o755)
        # Force collection failure without breaking the streaming capture mode.
        (self.root / 'build/reports/compile-failure/world-management-service').mkdir(parents=True, exist_ok=True)
        return subprocess.run(['bash', '.github/scripts/run-gradle-module-checks.sh',
                               'world-management-service'], cwd=self.root,
                              capture_output=True, text=True, check=False)

    def test_success_does_not_collect(self):
        result = self.fixture_wrapper(0)
        self.assertEqual(0, result.returncode)
        self.assertNotIn('evidence collection unavailable', result.stderr)
        self.assertFalse((self.root / 'build/reports/compile-failure/world-management-service/manifest.json').exists())
        self.assertEqual(1, len((self.root / 'calls').read_text().splitlines()))

    def test_collector_failure_preserves_exit_and_no_extra_retry(self):
        result = self.fixture_wrapper(1)
        self.assertEqual(1, result.returncode)
        self.assertIn('preserving check exit 1', result.stderr)
        calls = (self.root / 'calls').read_text().splitlines()
        self.assertEqual(1, len(calls))
        self.assertIn(':world-management-service:check', calls[0])
        self.assertNotIn('--refresh-dependencies', calls[0])

    def test_early_capture_exit_drains_output_and_preserves_check_status(self):
        for check_exit in [0, 1]:
            for lastpipe in [False, True]:
                with self.subTest(check_exit=check_exit, lastpipe=lastpipe):
                    scenario_root = self.root / f'capture-exit-{check_exit}-{lastpipe}'
                    scenario_root.mkdir()
                    result = self.fixture_wrapper_with_early_capture_failure(check_exit, scenario_root, lastpipe)
                    self.assertEqual(check_exit, result.returncode)
                    self.assertEqual('producer-end-marker\n', (scenario_root / 'producer-ended').read_text())
                    calls = (scenario_root / 'calls').read_text().splitlines()
                    self.assertEqual(1, len(calls))
                    self.assertIn(':world-management-service:check', calls[0])
                    self.assertNotIn('--refresh-dependencies', calls[0])

    def fixture_wrapper_with_early_capture_failure(self, exit_code, root, lastpipe=False):
        for relative in ['.github/scripts/run-gradle-module-checks.sh',
                         'dev-tools/validation/run-gradle-with-dependency-recovery.sh',
                         'dev-tools/validation/collect-gradle-compile-failure.py']:
            destination = root / relative
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(ROOT / relative, destination)
        collector = root / 'dev-tools/validation/collect-gradle-compile-failure.py'
        collector.write_text(
            'import sys\n'
            "if '--capture-log' in sys.argv:\n"
            '    sys.stdin.buffer.read(1)\n'
            '    raise SystemExit(23)\n'
            'raise SystemExit(24)\n')
        gradlew = root / 'gradlew'
        gradlew.write_text(
            '#!/bin/bash\n'
            'echo "$*" >> calls\n'
            'head -c 1048576 /dev/zero | tr "\\0" x\n'
            "printf 'producer-end-marker\\n' > producer-ended\n"
            f'exit {exit_code}\n')
        gradlew.chmod(0o755)
        return subprocess.run(['bash', *(['-O', 'lastpipe'] if lastpipe else []),
                               '.github/scripts/run-gradle-module-checks.sh',
                               'world-management-service'], cwd=root,
                              capture_output=True, text=True, check=False)


class RuntimeBootJarTests(unittest.TestCase):
    """Run the real entrypoint/recovery/collector against inert Gradle launchers."""

    write = CaptureTests.write

    def setUp(self):
        (ROOT / 'build').mkdir(exist_ok=True)
        self.temp = tempfile.TemporaryDirectory(dir=ROOT / 'build', prefix='runtime-compile-test-')
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        for relative in ['dev-tools/build-compose-service-jars.sh',
                         'dev-tools/validation/run-gradle-with-dependency-recovery.sh',
                         'dev-tools/validation/collect-gradle-compile-failure.py']:
            destination = self.root / relative
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(ROOT / relative, destination)
        launcher = self.write('dev-tools/validation/run-locked-gradle.sh', '#!/bin/bash\n'
                              'printf "%s\\n" "$@" >> locked-args\n'
                              'exec "$(dirname "$0")/../../gradlew" "$@"\n')
        launcher.chmod(0o755)
        self.env = {**os.environ, 'TMPDIR': str(self.root), 'RUNNER_TEMP': str(self.root),
                    'GITHUB_OUTPUT': str(self.root / 'step-output')}
        self.env.pop('FIREMUD_LOCAL_RESOURCE_SESSION_FD', None)

    def run_build(self, status=0, output='', producer='', lastpipe=False, signal_number=None):
        runner = self.write('gradlew', f'#!{sys.executable}\n'
                            'import json, os, sys\nfrom pathlib import Path\n'
                            'with open("calls", "a") as calls:\n'
                            '    calls.write(json.dumps(sys.argv[1:]) + "\\n")\n'
                            'if any(arg.endswith(":firemudCompileFailureFacts") for arg in sys.argv):\n'
                            '    print(\'SECRET=not-retained\')\n'
                            '    print(\'FIREMUD_COMPILE_FACTS={"attribution":"post-failure",'
                            '"gradleVersion":"fixture", "jvm":"fixture",'
                            '"compiler":"fixture", "classpath":[]}\')\n'
                            '    raise SystemExit(0)\n'
                            + producer + '\n'
                            f'print({output!r}, flush=True)\n'
                            'Path("producer-ended").write_text("drained")\n'
                            + (f'os.kill(os.getpid(), {signal_number})\n' if signal_number else '')
                            + f'raise SystemExit({status})\n')
        runner.chmod(0o755)
        return subprocess.run(['bash', *(['-O', 'lastpipe'] if lastpipe else []),
                               'dev-tools/build-compose-service-jars.sh'], cwd=self.root,
                              env=self.env, capture_output=True, check=False, start_new_session=True)

    def calls(self):
        return [json.loads(line) for line in (self.root / 'calls').read_text().splitlines()]

    def evidence(self):
        return list((self.root / 'build/reports/compile-failure').rglob('manifest.json'))

    def assert_original_command(self, arguments):
        services = ['account-service', 'automation-scripting-service', 'entity-management-service',
                    'game-design-service', 'game-logic-service', 'game-session-service',
                    'logging-admin-service', 'social-groups-service', 'spring-cloud-gateway',
                    'tcp-proxy-service', 'world-management-service']
        self.assertEqual(['--no-configuration-cache', '--max-workers=1', '--no-parallel',
                          '-Dorg.gradle.jvmargs=-Xmx2560m -Dfile.encoding=UTF-8',
                          '-PincludeLoadTesting=false',
                          *[f':{service}:bootJar' for service in services],
                          '--info', '--console=plain'], arguments)

    def test_success_preserves_all_eleven_tasks_and_flags_once(self):
        result = self.run_build(output='> Task :common-saga:compileJava FAILED')
        self.assertEqual(0, result.returncode)
        self.assertEqual(1, len(self.calls()))
        self.assert_original_command(self.calls()[0])
        self.assertEqual([], self.evidence())

    def test_noncompiler_and_signal_exits_never_collect(self):
        for status, signal_number, output in [(19, None, '> Task :account-service:bootJar FAILED'),
                                               (130, None, '> Task :common-saga:compileJava FAILED'),
                                               (143, 15, '> Task :common-saga:compileJava FAILED'),
                                               (129, 1, '> Task :common-saga:compileJava FAILED')]:
            with self.subTest(status=status):
                result = self.run_build(status, output, signal_number=signal_number)
                self.assertEqual(status, result.returncode)
                self.assertEqual([], self.evidence())
        self.assertEqual(4, len(self.calls()))
        for arguments in self.calls():
            self.assert_original_command(arguments)

    def test_bounded_useful_compiler_capture_without_compiler_retry(self):
        self.write('services/common-saga/build/generated/Bad.java', 'bad source')
        self.write('services/common-saga/build/generated/Huge.java', 'x' * (CAPTURE.MAX_FILE_BYTES + 1))
        self.write('protos/owner/service.proto', 'schema')
        self.write('build.gradle.kts', 'build inputs')
        result = self.run_build(17, '> Task :common-saga:compileJava FAILED\n'
                                'Compiling with toolchain /jdk25\nBad.java:1: error: bad\n'
                                'SECRET=not-retained',
                                'os.write(1, b"x" * (17 * 1024 * 1024) + b"\\n")')
        self.assertEqual(17, result.returncode)
        calls = self.calls()
        self.assertEqual(2, len(calls))
        self.assert_original_command(calls[0])
        self.assertEqual(':common-saga:firemudCompileFailureFacts', calls[1][-1])
        self.assertIn('--offline', calls[1])
        self.assertIn('--no-daemon', calls[1])
        self.assertNotIn('--refresh-dependencies', calls[1])
        self.assertFalse(any(arg.startswith(':') and arg.endswith((':check', ':test', ':compileJava'))
                             for arg in calls[1]))
        manifest_path, = self.evidence()
        manifest = json.loads(manifest_path.read_text())
        self.assertEqual(17, manifest['check_exit'])
        self.assertEqual(['Compiling with toolchain /jdk25'], manifest['original_compiler_info'])
        self.assertIn('post-failure', manifest['attribution'])
        self.assertTrue(manifest['log_tail_possibly_truncated'])
        self.assertEqual(1, manifest['omitted'])
        self.assertLessEqual(manifest['captured_bytes'], CAPTURE.MAX_BYTES)
        paths = [entry['path'] for entry in manifest['files']]
        self.assertIn('services/common-saga/build/generated/Bad.java', paths)
        self.assertIn('protos/owner/service.proto', paths)
        self.assertIn('build.gradle.kts', paths)
        diagnostic, = manifest_path.parent.glob('*-diagnostic.json')
        self.assertEqual('fixture', json.loads(diagnostic.read_text())['gradleVersion'])
        self.assertNotIn('SECRET', manifest_path.read_text() + diagnostic.read_text())
        self.assertFalse(list((self.root / 'build').glob('runtime-bootjar.*')))

    def test_wrapper_group_interrupts_preserve_signal_exit_without_diagnostics(self):
        for signal_number, expected in [(2, 130), (15, 143), (1, 129)]:
            with self.subTest(signal=signal_number):
                result = self.run_build(producer='print("> Task :common-saga:compileJava FAILED", flush=True)\n'
                                        f'os.killpg(os.getpgrp(), {signal_number})')
                self.assertEqual(expected, result.returncode)
                self.assertEqual([], self.evidence())
        self.assertEqual(3, len(self.calls()))

    def test_capture_early_exit_drains_for_all_original_statuses_and_lastpipe(self):
        collector = self.root / 'dev-tools/validation/collect-gradle-compile-failure.py'
        collector.write_text('import sys\n'
                             'if "--capture-log" in sys.argv:\n'
                             '    sys.stdin.buffer.read(1)\n'
                             'raise SystemExit(23)\n')
        for status in [0, 17, 130, 143]:
            for lastpipe in [False, True]:
                with self.subTest(status=status, lastpipe=lastpipe):
                    result = self.run_build(status, producer='os.write(1, b"x" * 1048576)', lastpipe=lastpipe)
                    self.assertEqual(status, result.returncode)
                    self.assertEqual('drained', (self.root / 'producer-ended').read_text())
                    self.assertEqual([], self.evidence())
        self.assertEqual(8, len(self.calls()))

    def test_capture_write_failure_drains_and_preserves_original_exit(self):
        collector = self.root / 'dev-tools/validation/collect-gradle-compile-failure.py'
        collector.write_text(collector.read_text().replace(
            'return capture_log(sys.stdin.buffer',
            'args.capture_log.mkdir()\n        return capture_log(sys.stdin.buffer'))
        for status in [0, 17]:
            with self.subTest(status=status):
                result = self.run_build(status, '> Task :common-saga:compileJava FAILED',
                                        'os.write(1, b"x" * 1048576)')
                self.assertEqual(status, result.returncode)
                self.assertIn(b'original output drained', result.stderr)
                self.assertIn(b'> Task :common-saga:compileJava FAILED', result.stdout)
                self.assertEqual('drained', (self.root / 'producer-ended').read_text())
        self.assertEqual(2, len(self.calls()))
        self.assertEqual([], self.evidence())

    def test_missing_scratch_runs_original_build_once_without_evidence(self):
        mktemp = self.write('bin/mktemp', '#!/bin/bash\n'
                            'case "$*" in *runtime-bootjar*) exit 1;; esac\n'
                            f'exec "{shutil.which("mktemp")}" "$@"\n')
        mktemp.chmod(0o755)
        self.env['PATH'] = str(mktemp.parent) + os.pathsep + os.environ['PATH']
        for status in [0, 17, 143]:
            with self.subTest(status=status):
                result = self.run_build(status, '> Task :common-saga:compileJava FAILED')
                self.assertEqual(status, result.returncode)
                self.assertIn(b'without capture', result.stderr)
        self.assertEqual(3, len(self.calls()))
        self.assertEqual([], self.evidence())
        self.assertFalse((self.root / 'step-output').exists())

    def test_collection_failure_preserves_original_exit_without_retry(self):
        collector = self.root / 'dev-tools/validation/collect-gradle-compile-failure.py'
        collector.write_text(collector.read_text().replace(
            'collect(args.root, args.log, args.output, args.check_exit)',
            'raise OSError("fixture collection unavailable")'))
        result = self.run_build(23, '> Task :common-saga:compileJava FAILED')
        self.assertEqual(23, result.returncode)
        self.assertIn(b'preserving build exit 23', result.stderr)
        self.assertEqual(1, len(self.calls()))
        self.assertEqual([], self.evidence())

    def test_repeated_invocations_never_select_stale_evidence(self):
        self.env.update(GITHUB_RUN_ID='123', GITHUB_RUN_ATTEMPT='2')
        paths = []
        for status in [17, 0, 19, 17]:
            step_output = self.root / 'step-output'
            step_output.write_text('')
            result = self.run_build(status, '> Task :common-saga:compileJava FAILED' if status == 17 else '')
            self.assertEqual(status, result.returncode)
            output = step_output.read_text()
            self.assertEqual(status == 17, bool(output))
            if output:
                paths.append(Path(output.strip().split('=', 1)[1]))
        self.assertEqual(2, len(set(paths)))
        self.assertTrue(all((path / 'manifest.json').exists() for path in paths))
        self.assertEqual(6, len(self.calls()))

    def test_reused_scratch_name_never_publishes_existing_evidence(self):
        stale = self.write('build/reports/compile-failure/runtime-bootjar/runtime-bootjar.reused/manifest.json',
                           'stale evidence')
        mktemp = self.write('bin/mktemp', '#!/bin/bash\n'
                            'case "$*" in *runtime-bootjar*)\n'
                            f'  mkdir -p "{self.root}/build/runtime-bootjar.reused"\n'
                            f'  printf "%s\\n" "{self.root}/build/runtime-bootjar.reused"; exit 0;; esac\n'
                            f'exec "{shutil.which("mktemp")}" "$@"\n')
        mktemp.chmod(0o755)
        self.env['PATH'] = str(mktemp.parent) + os.pathsep + os.environ['PATH']
        result = self.run_build(17, '> Task :common-saga:compileJava FAILED')
        self.assertEqual(17, result.returncode)
        self.assertIn(b'preserving build exit 17', result.stderr)
        self.assertEqual('stale evidence', stale.read_text())
        self.assertFalse((self.root / 'step-output').exists())
        self.assertEqual(1, len(self.calls()))


if __name__ == '__main__':
    unittest.main()
