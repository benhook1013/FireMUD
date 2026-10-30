"""Focused failure-capture contracts; all Gradle commands are inert fixtures."""

import importlib.util
import io
import json
import shutil
import subprocess
import sys
import tempfile
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
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.log = self.root / 'check.log'
        self.log.write_text('> Task :common-saga:compileJava FAILED\n'
                            'Compiling with toolchain /jdk21\n'
                            'SECRET=do-not-retain\nBad.java:1: error: bad\n')
        self.output = self.root / 'evidence'

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
        self.assertIn('--offline', arguments)
        self.assertIn('--no-configuration-cache', arguments)
        self.assertEqual('firemudCompileFailureFacts', arguments[-1])
        self.assertFalse(any(arg.endswith(':check') for arg in arguments))
        self.assertNotIn('--refresh-dependencies', arguments)

    def test_output_symlink_escape_rejected(self):
        external = self.root / 'outside'
        external.mkdir()
        (self.root / 'linked-output').symlink_to(external, target_is_directory=True)
        with self.assertRaises(ValueError):
            CAPTURE.collect(self.root, self.log, self.root / 'linked-output/capture', 1)
        self.assertEqual([], list(external.iterdir()))

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


if __name__ == '__main__':
    unittest.main()
