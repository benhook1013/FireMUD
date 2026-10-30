#!/usr/bin/env python3
"""Failure-only, bounded module-CI evidence; never reruns a check or compiler."""

import argparse
import hashlib
import json
import os
import re
import signal
import subprocess
import sys
import tempfile
from collections import deque
from pathlib import Path

MAX_FILES = 512
MAX_BYTES = 16 * 1024 * 1024
MAX_FILE_BYTES = 2 * 1024 * 1024
MAX_LOG_BYTES = 16 * 1024 * 1024
MAX_VISITED_ENTRIES = 4096
TASK = re.compile(r"^> Task (:([a-z0-9]+(?:-[a-z0-9]+)*):compile[A-Za-z0-9]*Java) FAILED$", re.MULTILINE)


def safe_file(root, path):
    """Reject symlinks in every component, including links inside the root."""
    try:
        relative = path.relative_to(root)
        cursor = root
        for part in relative.parts:
            cursor /= part
            if cursor.is_symlink():
                return False
        return path.is_file() and path.resolve().is_relative_to(root)
    except (OSError, ValueError):
        return False


def capture_log(source, destination, path):
    """Forward immediately; retain only a bounded tail, even if capture fails."""
    tail = deque()
    length = 0
    forwarding = True
    failed = False
    reader = getattr(source, 'read1', source.read)
    while chunk := reader(64 * 1024):
        if forwarding:
            try:
                destination.write(chunk)
                destination.flush()
            except OSError:
                forwarding = False
                failed = True
        tail.append(chunk)
        length += len(chunk)
        while length > MAX_LOG_BYTES:
            excess = length - MAX_LOG_BYTES
            first = tail.popleft()
            removed = min(excess, len(first))
            length -= removed
            if removed < len(first):
                tail.appendleft(first[removed:])
    try:
        # The file stays empty during streaming, then receives at most one tail.
        with path.open('wb') as stream:
            for chunk in tail:
                stream.write(chunk)
    except OSError:
        failed = True
    if failed:
        print('Compile-failure log capture/forwarding unavailable; original output drained', file=sys.stderr)
    return int(failed)


def walk_files(root, start, suffix, budget):
    pending = [start]
    while pending:
        directory = pending.pop()
        if directory.is_symlink() or not directory.is_dir():
            continue
        try:
            with os.scandir(directory) as entries:
                while True:
                    if budget['visited'] >= MAX_VISITED_ENTRIES:
                        budget['truncated'] = True
                        return
                    entry = next(entries, None)
                    if entry is None:
                        break
                    budget['visited'] += 1
                    path = Path(entry.path)
                    if entry.is_symlink():
                        continue
                    if entry.is_dir(follow_symlinks=False):
                        pending.append(path)
                    elif path.suffix == suffix and safe_file(root, path):
                        yield path
        except OSError:
            budget['unavailable_directories'] += 1


def diagnostic(root, task, output):
    """Only retain our structured marker; Gradle stdout can include unrelated data."""
    with tempfile.TemporaryFile() as stream:
        process = subprocess.Popen(
            ['prlimit', f'--fsize={MAX_FILE_BYTES}:{MAX_FILE_BYTES}', '--',
             str(root / 'gradlew'), '--offline', '--no-daemon', '--no-configuration-cache',
             '--console=plain', '--max-workers=2',
             '-Dorg.gradle.java.installations.auto-download=false',
             '-I', str(root / 'dev-tools/validation/compile-failure-facts.init.gradle'),
             '-PfiremudFailedCompile=' + task, 'firemudCompileFailureFacts'],
            cwd=root, stdout=stream, stderr=subprocess.STDOUT, start_new_session=True,
        )
        try:
            process.wait(timeout=60)
        except subprocess.TimeoutExpired:
            os.killpg(process.pid, signal.SIGKILL)
            process.wait()
            return {'status': 'timeout', 'facts': None}
        stream.seek(0)
        data = stream.read(MAX_FILE_BYTES + 1)
    limited = len(data) >= MAX_FILE_BYTES
    for line in data[:MAX_FILE_BYTES].decode('utf-8', errors='replace').splitlines():
        if line.startswith('FIREMUD_COMPILE_FACTS='):
            facts = json.loads(line.split('=', 1)[1])
            output.write_text(json.dumps(facts, indent=2) + '\n')
            return {'status': 'limited' if limited else ('complete' if process.returncode == 0 else 'failed'),
                    'exit': process.returncode, 'output_limit_reached': limited, 'output_possibly_truncated': limited}
    return {'status': 'unavailable', 'exit': process.returncode,
            'output_limit_reached': limited, 'output_possibly_truncated': limited}


def collect(root, log, output, check_exit):
    if not 0 < check_exit < 128:
        return
    root = root.resolve()
    with log.open('rb') as stream:
        size = stream.seek(0, 2)
        stream.seek(max(0, size - MAX_LOG_BYTES))
        text = stream.read(MAX_LOG_BYTES).decode('utf-8', errors='replace')
    tasks = list(dict.fromkeys(TASK.findall(text)))
    if not tasks:
        return
    relative_output = output.relative_to(root)
    cursor = root
    for part in relative_output.parts:
        cursor /= part
        if cursor.is_symlink():
            raise ValueError('Evidence output must not traverse symlinks')
    output.mkdir(parents=True, exist_ok=False)
    manifest = {'check_exit': check_exit, 'failed_tasks': [t[0] for t in tasks],
                'log_tail_possibly_truncated': size >= MAX_LOG_BYTES,
                'limits': {'files': MAX_FILES, 'bytes': MAX_BYTES, 'file_bytes': MAX_FILE_BYTES,
                           'visited_entries': MAX_VISITED_ENTRIES},
                'files': [], 'omitted': 0, 'diagnostics': [],
                'attribution': 'Sources are a post-failure snapshot. Diagnostic compiler/classpath selection is post-failure, not proof of the compiler or classpath used by the failed execution.'}
    # Native --info compiler lines belong to the original invocation. Do not retain
    # arbitrary log lines, environment, compiler arguments, or daemon command lines.
    facts = [line[:4096] for line in text.splitlines()
             if re.fullmatch(r'(Compiling with toolchain .+|Compiling with JDK Java compiler API\.|Compiling with Java command line compiler .+)', line)]
    manifest['original_compiler_info'] = facts[:32]
    manifest['original_compiler_info_unavailable'] = not bool(facts)
    total = 0
    seen = set()

    def copy(path):
        nonlocal total
        if path in seen or not safe_file(root, path):
            return
        seen.add(path)
        relative = path.relative_to(root)
        length = path.stat().st_size
        if (len(manifest['files']) >= MAX_FILES or length > MAX_FILE_BYTES
                or total + length > MAX_BYTES):
            manifest['omitted'] += 1
            return
        with path.open('rb') as stream:
            data = stream.read(min(MAX_FILE_BYTES, MAX_BYTES - total) + 1)
        if len(data) != length:
            manifest['omitted'] += 1
            return
        target = output / 'inputs' / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(data)
        total += len(data)
        manifest['files'].append({'path': relative.as_posix(), 'bytes': len(data),
                                  'sha256': hashlib.sha256(data).hexdigest()})

    for name in ['build.gradle.kts', 'settings.gradle.kts', 'gradle/libs.versions.toml',
                 'gradle/proto-convention.gradle', 'gradle/wrapper/gradle-wrapper.properties',
                 'gradle/gradle-daemon-jvm.properties', 'config/protobuf/buf.gen.yaml']:
        copy(root / name)
    enumeration = {'visited': 0, 'truncated': False, 'unavailable_directories': 0}
    generated_inputs = []
    for task, module in tasks[:4]:
        project = root / ('dev-tools/load-testing' if module == 'load-testing' else 'services/' + module)
        copy(project / 'build.gradle.kts')
        generated_inputs.extend(walk_files(root, project / 'build/generated', '.java', enumeration))
    # Preserve implicated generated sources first, then schemas, then other Java.
    generated_inputs.sort(key=lambda p: (p.name not in text, str(p)))
    for path in generated_inputs:
        if path.name in text:
            copy(path)
    for path in walk_files(root, root / 'protos', '.proto', enumeration):
        copy(path)
    for path in generated_inputs:
        copy(path)
    for task, module in tasks[:4]:
        try:
            result = diagnostic(root, task, output / (module + '-diagnostic.json'))
        except (OSError, ValueError) as error:
            result = {'status': 'unavailable', 'reason': type(error).__name__}
        manifest['diagnostics'].append({'task': task, **result})
    manifest['task_limit_truncated'] = len(tasks) > 4
    manifest['enumeration'] = enumeration
    manifest['captured_bytes'] = total
    (output / 'manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--capture-log', type=Path)
    parser.add_argument('--root', type=Path)
    parser.add_argument('--log', type=Path)
    parser.add_argument('--output', type=Path)
    parser.add_argument('--check-exit', type=int)
    args = parser.parse_args()
    if args.capture_log is not None:
        return capture_log(sys.stdin.buffer, sys.stdout.buffer, args.capture_log)
    if any(value is None for value in (args.root, args.log, args.output, args.check_exit)):
        parser.error('collection requires --root, --log, --output and --check-exit')
    if 0 < args.check_exit < 128:
        collect(args.root, args.log, args.output, args.check_exit)
    return 0



if __name__ == '__main__':
    raise SystemExit(main())
