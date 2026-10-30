"""Focused failure-capture contracts; all Gradle commands are inert fixtures."""

import importlib.util
import json
import shutil
import subprocess
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
        # Collection failure must not turn a failure into success or another exit.
        self.write('dev-tools/validation/collect-gradle-compile-failure.py',
                   'raise SystemExit(97)\n')
        return subprocess.run(['bash', '.github/scripts/run-gradle-module-checks.sh',
                               'world-management-service'], cwd=self.root,
                              capture_output=True, text=True, check=False)

    def test_success_does_not_collect(self):
        result = self.fixture_wrapper(0)
        self.assertEqual(0, result.returncode)
        self.assertNotIn('evidence collection unavailable', result.stderr)
        self.assertFalse((self.root / 'build/reports/compile-failure').exists())
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
