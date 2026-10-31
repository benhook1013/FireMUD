#!/usr/bin/env python3
"""Failure-only, bounded module-CI evidence; never reruns a check or compiler."""

import argparse
import hashlib
import json
import os
import re
import selectors
import signal
import subprocess
import sys
import time
from collections import deque
from pathlib import Path

MAX_FILES = 512
MAX_BYTES = 16 * 1024 * 1024
MAX_FILE_BYTES = 2 * 1024 * 1024
MAX_LOG_BYTES = 16 * 1024 * 1024
MAX_VISITED_ENTRIES = 4096
DIAGNOSTIC_TIMEOUT_SECONDS = 60
TASK_PATH = re.compile(r'^(:[a-z0-9]+(?:-[a-z0-9]+)*):compile[A-Za-z0-9]*Java$')
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
    task_match = TASK_PATH.fullmatch(task)
    if task_match is None:
        raise ValueError('Diagnostic compile task path is invalid')
    facts_task = task_match.group(1) + ':firemudCompileFailureFacts'
    process = subprocess.Popen(
        [str(root / 'gradlew'), '--offline', '--no-daemon', '--no-configuration-cache',
         '--console=plain', '--max-workers=2',
         '-Dorg.gradle.java.installations.auto-download=false',
         '-I', str(root / 'dev-tools/validation/compile-failure-facts.init.gradle'),
         '-PfiremudFailedCompile=' + task, facts_task],
        cwd=root, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
        start_new_session=True, bufsize=0,
    )
    captured = bytearray()
    limited = False
    timed_out = False
    settled = False
    deadline = time.monotonic() + DIAGNOSTIC_TIMEOUT_SECONDS
    selector = selectors.DefaultSelector()
    selector.register(process.stdout, selectors.EVENT_READ)
    try:
        while True:
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                timed_out = True
                break
            if not selector.select(remaining):
                continue
            remaining_bytes = MAX_FILE_BYTES - len(captured)
            chunk = os.read(process.stdout.fileno(), min(64 * 1024, remaining_bytes))
            if not chunk:
                selector.unregister(process.stdout)
                break
            captured.extend(chunk)
            # An exactly full output is conservatively limited too, because
            # additional output could have been truncated at the boundary.
            if len(captured) >= MAX_FILE_BYTES:
                limited = True
                break

        if timed_out or limited:
            try:
                os.killpg(process.pid, signal.SIGKILL)
            except ProcessLookupError:
                pass
            process.wait()
            settled = True
        else:
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                timed_out = True
            else:
                try:
                    process.wait(timeout=remaining)
                    settled = True
                except subprocess.TimeoutExpired:
                    timed_out = True
            if timed_out:
                try:
                    os.killpg(process.pid, signal.SIGKILL)
                except ProcessLookupError:
                    pass
                process.wait()
                settled = True
    finally:
        selector.close()
        process.stdout.close()
        if not settled:
            # Even an unexpected capture error may not leave this diagnostic's
            # child processes behind. start_new_session scopes this group.
            try:
                os.killpg(process.pid, signal.SIGKILL)
            except ProcessLookupError:
                pass
            process.wait()
    if timed_out:
        return {'status': 'timeout', 'facts': None}
    data = bytes(captured)
    marker = b'FIREMUD_COMPILE_FACTS='
    start = 0
    while (start := data.find(marker, start)) >= 0:
        if start == 0 or data[start - 1] == ord('\n'):
            end = data.find(b'\n', start)
            if end < 0:
                end = len(data)
            facts = json.loads(data[start + len(marker):end].decode('utf-8', errors='replace'))
            serialized = (json.dumps(facts, separators=(',', ':'), ensure_ascii=False) + '\n').encode('utf-8')
            if len(serialized) > MAX_FILE_BYTES:
                return {'status': 'limited', 'exit': process.returncode,
                        'output_limit_reached': True, 'output_possibly_truncated': True,
                        'facts_output_limit_reached': True}
            output.write_bytes(serialized)
            return {'status': 'limited' if limited else ('complete' if process.returncode == 0 else 'failed'),
                    'exit': process.returncode, 'output_limit_reached': limited, 'output_possibly_truncated': limited}
        start += len(marker)
    return {'status': 'unavailable', 'exit': process.returncode,
            'output_limit_reached': limited, 'output_possibly_truncated': limited}


def diagnostic_basename(task):
    if TASK_PATH.fullmatch(task) is None:
        raise ValueError('Diagnostic compile task path is invalid')
    # The task-path grammar excludes "--" within components, so separators stay unique.
    return task[1:].replace(':', '--') + '-diagnostic.json'


def collect(root, log, output, check_exit):
    if not 0 < check_exit < 128:
        return
    # Compute the relation before resolving so a symlink used as the workspace
    # root remains a valid lexical prefix for the caller's output path.
    lexical_root = Path(os.path.abspath(root))
    lexical_output = Path(os.path.abspath(output))
    relative_output = lexical_output.relative_to(lexical_root)
    root = lexical_root.resolve()
    output = root / relative_output
    output.relative_to(root)
    with log.open('rb') as stream:
        size = stream.seek(0, 2)
        stream.seek(max(0, size - MAX_LOG_BYTES))
        text = stream.read(MAX_LOG_BYTES).decode('utf-8', errors='replace')
    implicated_java = set(re.findall(r'(?<![\w$])[\w$]+\.java(?![\w$])', text))
    tasks = list(dict.fromkeys(TASK.findall(text)))
    if not tasks:
        return
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
    for task, module in tasks[:4]:
        project = root / ('dev-tools/load-testing' if module == 'load-testing' else 'services/' + module)
        copy(project / 'build.gradle.kts')
    schema_inputs = list(walk_files(root, root / 'protos', '.proto', enumeration))
    generated_inputs = []
    for task, module in tasks[:4]:
        project = root / ('dev-tools/load-testing' if module == 'load-testing' else 'services/' + module)
        generated_inputs.extend(walk_files(root, project / 'build/generated', '.java', enumeration))
    # Preserve implicated generated sources first, then schemas, then other Java.
    generated_inputs.sort(key=lambda p: (p.name not in implicated_java, str(p)))
    for path in generated_inputs:
        if path.name in implicated_java:
            copy(path)
    for path in schema_inputs:
        copy(path)
    for path in generated_inputs:
        copy(path)
    for task, module in tasks[:4]:
        try:
            result = diagnostic(root, task, output / diagnostic_basename(task))
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
