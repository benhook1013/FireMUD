"""Focused failure-capture contracts; all Gradle commands are inert fixtures."""

import importlib.util
import io
import json
import os
import shutil
import subprocess
import sys
import tempfile
import time
import unittest
from pathlib import Path
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location(
    'capture', ROOT / 'dev-tools/validation/collect-gradle-compile-failure.py')
CAPTURE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(CAPTURE)


class CaptureTests(unittest.TestCase):
    def setUp(self):
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
