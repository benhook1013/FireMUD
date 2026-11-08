#!/usr/bin/env python3
"""Failure-only, bounded Gradle evidence; never reruns a build or compiler."""

import argparse
import hashlib
import json
import os
import re
import selectors
import signal
import subprocess
import sys
import tempfile
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


# Account CI-only observation. No build process is signalled or restarted.
PROC_ROOT = Path('/proc')
STALL_IDLE_SECONDS = 300
STALL_BATCH_SECONDS = 90
STALL_COMMAND_SECONDS = 5
STALL_MAX_PROCESSES = 8
STALL_MAX_CANDIDATES = 32
STALL_DUMP_BYTES = 128 * 1024
DAEMON_PID = re.compile(rb'The client will now receive all logging from the daemon \(pid: ([0-9]+)\)')


def stall_marker(status, fd=2):
    """Fixed, best-effort status only; never block capture or change shared descriptor flags."""
    if status not in {'enabled', 'started', 'completed', 'launch-failed', 'setup-failed',
                      'observer-failed', 'session-unowned', 'deadline', 'cancelled'}:
        return
    descriptor = None
    try:
        # Reopening proc fd creates a separate description; dup()+F_SETFL would also
        # make the caller's pipe nonblocking. O_APPEND protects regular-file sinks.
        descriptor = os.open(f'/proc/self/fd/{fd}', os.O_WRONLY | os.O_NONBLOCK | os.O_APPEND)
        os.write(descriptor, f'FIREMUD_STALL_OBSERVER status={status}\n'.encode('ascii'))
    except OSError:
        pass
    finally:
        if descriptor is not None:
            os.close(descriptor)


def owns_session(pid):
    try:
        return os.getpgid(pid) == pid and os.getsid(pid) == pid
    except ProcessLookupError:
        return False


def stop_stall_session(signum, frame):
    stall_marker('deadline', fd=1)
    if owns_session(os.getpid()):
        os.killpg(os.getpid(), signal.SIGKILL)
    raise SystemExit(128 + signum)


def java_identity(pid, rejection=None):
    """Classify without retaining argv, environment, or arbitrary process names."""
    def reject(reason):
        if rejection is not None:
            rejection['reason'] = reason

    try:
        path = PROC_ROOT / str(pid)
        if path.stat().st_uid != os.getuid():
            return reject('different-uid')
        fields = (path / 'stat').read_bytes().rsplit(b')', 1)[1].split()
        with (path / 'cmdline').open('rb') as stream:
            arguments = stream.read(8193)
        if len(arguments) > 8192:
            return reject('command-line-limit')
        arguments = arguments.split(b'\0')
        if b'org.gradle.launcher.daemon.bootstrap.GradleDaemon' in arguments:
            role = 'daemon'
        elif (b'worker.org.gradle.process.internal.worker.GradleWorkerMain' in arguments
              and any(arg.startswith(b'Gradle Test Executor ') for arg in arguments)):
            role = 'test-worker'
        else:
            return reject('unrecognized-java-role')
        executable = (path / 'exe').resolve(strict=True)
        if executable.name != 'java':
            return reject('not-java-executable')
        return {'pid': pid, 'ppid': int(fields[1]), 'start': int(fields[19]),
                'state': fields[0].decode('ascii'), 'role': role,
                'cpu_ticks': int(fields[11]) + int(fields[12]), 'rss_pages': int(fields[21]),
                'jcmd': str(executable.with_name('jcmd'))}
    except (FileNotFoundError, ProcessLookupError):
        return reject('exited')
    except PermissionError:
        return reject('access-denied')
    except (OSError, ValueError, IndexError, UnicodeError):
        return reject('process-unavailable')


def stall_processes(daemon, coverage=None):
    if coverage is None:
        coverage = {}
    coverage.update({'scope': 'same-uid-direct-daemon-children', 'scan_limit': MAX_VISITED_ENTRIES,
                     'scan_limit_reached': False, 'entries_examined': 0, 'inspection_errors': 0,
                     'candidate_limit': STALL_MAX_CANDIDATES, 'candidates': [],
                     'candidates_omitted': 0, 'selection_counts': {}, 'workers_selected': 0})
    current = java_identity(daemon['pid'])
    if not current or (current['start'], current['role']) != (daemon['start'], 'daemon'):
        coverage['daemon_identity'] = 'exited-or-changed'
        return []
    coverage['daemon_identity'] = 'verified'
    result = [current]
    # Scope intentionally remains direct children. Rejected candidates are metadata,
    # not permission to attach to an unknown JVM or scan its descendants/arguments.
    with os.scandir(PROC_ROOT) as entries:
        for _, entry in zip(range(MAX_VISITED_ENTRIES), entries):
            coverage['entries_examined'] += 1
            if not entry.name.isdigit():
                continue
            path = Path(entry.path)
            try:
                fields = (path / 'stat').read_bytes().rsplit(b')', 1)[1].split()
                if int(fields[1]) != daemon['pid'] or path.stat().st_uid != os.getuid():
                    continue
                candidate = {'pid': int(entry.name), 'ppid': int(fields[1]),
                             'start': int(fields[19]), 'state': fields[0].decode('ascii')}
            except (OSError, ValueError, IndexError, UnicodeError):
                coverage['inspection_errors'] += 1
                continue
            rejection = {}
            worker = java_identity(candidate['pid'], rejection)
            reason = rejection.get('reason', 'unrecognized-java-role')
            if worker and worker['role'] == 'test-worker':
                if any(worker[key] != candidate[key] for key in ('pid', 'ppid', 'start')):
                    reason = 'identity-changed'
                elif len(result) >= STALL_MAX_PROCESSES:
                    reason = 'attachment-limit'
                else:
                    result.append(worker)
                    coverage['workers_selected'] += 1
                    reason = 'selected-test-worker'
            candidate['selection'] = reason
            counts = coverage['selection_counts']
            counts[reason] = counts.get(reason, 0) + 1
            if len(coverage['candidates']) < STALL_MAX_CANDIDATES:
                coverage['candidates'].append(candidate)
            else:
                coverage['candidates_omitted'] += 1
    coverage['scan_limit_reached'] = coverage['entries_examined'] >= MAX_VISITED_ENTRIES
    return result


def sanitized_threads(raw):
    """Allow only stack/state/monitor syntax; thread names and diagnostic prose are private."""
    lines = []
    for line in raw.decode('utf-8', errors='replace').splitlines():
        if line.startswith('"'):
            identifiers = re.findall(r'(?:#\d+|(?:tid|nid)=0x[0-9a-f]+)(?=\s|$)', line[line.rfind(chr(34)) + 1:])
            lines.append('thread (name omitted) ' + ' '.join(identifiers))
        elif (re.fullmatch(r'\s*java.lang.Thread.State: [A-Z_]+(?: \([a-z ]+\))?', line)
              or re.fullmatch(r'\s*at [A-Za-z0-9_.$/@+\-]+\([A-Za-z0-9_.$: /\-]+\)', line)
              or re.fullmatch(r'\s*- (?:locked|waiting on|waiting to lock|parking to wait for)\s+<0x[0-9a-f]+> \(a [A-Za-z0-9_.$]+\)', line)):
            lines.append(line.strip())
    return '\n'.join(lines)


def thread_snapshot(identity):
    facts = {key: value for key, value in identity.items() if key != 'jcmd'}
    # The observed daemon exceeded128KiB; retain later actor frames with a fixed ceiling.
    dump_limit = STALL_DUMP_BYTES * (4 if identity['role'] == 'daemon' else 1)
    facts['dump_byte_limit'] = dump_limit
    current = java_identity(identity['pid'])
    # Counters may change; only identity and lineage must remain fixed.
    keys = ('pid', 'ppid', 'start', 'role', 'jcmd')
    if not current or any(current[key] != identity[key] for key in keys):
        return {**facts, 'status': 'exited-or-changed'}
    process = None
    selector = selectors.DefaultSelector()
    raw = bytearray()
    status = 'complete'
    try:
        process = subprocess.Popen([identity['jcmd'], str(identity['pid']), 'Thread.print', '-l'],
                                   stdin=subprocess.DEVNULL, stdout=subprocess.PIPE,
                                   stderr=subprocess.DEVNULL)
        selector.register(process.stdout, selectors.EVENT_READ)
        deadline = time.monotonic() + STALL_COMMAND_SECONDS
        while True:
            remaining = deadline - time.monotonic()
            if remaining <= 0 or not selector.select(remaining):
                status = 'timeout'
                break
            chunk = os.read(process.stdout.fileno(), min(8192, dump_limit - len(raw)))
            if not chunk:
                break
            raw.extend(chunk)
            if len(raw) >= dump_limit:
                status = 'limited'
                break
        if status == 'complete':
            process.wait(timeout=max(0.01, deadline - time.monotonic()))
            if process.returncode:
                status = 'failed'
    except subprocess.TimeoutExpired:
        status = 'timeout'
    except OSError:
        status = 'unavailable'
    finally:
        selector.close()
        if process is not None:
            if process.poll() is None:
                process.kill()  # Only our jcmd helper, never the target JVM.
            process.wait()
            process.stdout.close()
    current = java_identity(identity['pid'])
    if not current or any(current[key] != identity[key] for key in keys):
        return {**facts, 'status': 'exited-or-changed'}
    return {**facts, 'status': status, 'threads': sanitized_threads(raw)}


def collect_stall(root, output, daemon):
    lexical_root = Path(os.path.abspath(root))
    relative = Path(os.path.abspath(output)).relative_to(lexical_root)
    root = lexical_root.resolve()
    output = root / relative
    cursor = root
    for part in relative.parts:
        cursor /= part
        if cursor.is_symlink():
            raise ValueError('Stall evidence must not traverse symlinks')
    output.mkdir(parents=True, exist_ok=True)
    # Each invocation owns a fresh directory; never overwrite prior evidence.
    target = Path(tempfile.mkdtemp(prefix='observation-', dir=output))
    started = time.monotonic()
    for number in range(2):
        if number:
            time.sleep(max(0, 30 - (time.monotonic() - started)))
        coverage = {'daemon_identity': 'unavailable'}
        processes = stall_processes(daemon, coverage) if daemon else []
        report = {'snapshot': number + 1, 'reason': '300 seconds without Gradle output',
                  'daemon_attribution': 'verified' if processes else 'unavailable',
                  'worker_coverage': coverage,
                  'process_limit': STALL_MAX_PROCESSES, 'process_limit_reached': len(processes) >= STALL_MAX_PROCESSES,
                  'processes': [thread_snapshot(process) for process in processes]}
        payload = json.dumps(report, indent=2) + '\n'
        (target / f'snapshot-{number + 1}.json').write_text(payload)
        # Independent child output: a blocked log sink cannot stall Gradle capture.
        print('FIREMUD_STALL_SNAPSHOT ' + json.dumps(
            {key: value for key, value in report.items() if key != 'processes'}), flush=True)
        for process in report['processes']:
            print(json.dumps({key: value for key, value in process.items() if key != 'threads'}), flush=True)
            print(process.get('threads', ''), flush=True)


class StallObserver:
    """One independent, bounded diagnostic batch; capture remains the sole build-output reader."""
    def __init__(self, root, output):
        self.root, self.output = root, output
        self.last_activity = time.monotonic()
        self.prefix = b''
        self.daemon = None
        self.child = None
        self.started = None
        self.attempted = False

    def observe(self, chunk):
        self.last_activity = time.monotonic()
        data = self.prefix + chunk
        for match in DAEMON_PID.finditer(data):
            candidate = java_identity(int(match[1]))
            if candidate and candidate['role'] == 'daemon':
                self.daemon = candidate
        self.prefix = data[-256:]

    def tick(self):
        now = time.monotonic()
        if self.child:
            # Observe without reaping: the reserved PID still owns the cleanup group.
            try:
                exited = os.waitid(os.P_PID, self.child.pid, os.WEXITED | os.WNOHANG | os.WNOWAIT)
            except OSError:
                stall_marker('observer-failed')
                self.close()
                return
            if exited is not None:
                if exited.si_status:
                    stall_marker('observer-failed')
                self.close()
            elif now - self.started >= STALL_BATCH_SECONDS:
                stall_marker('deadline')
                self.close()
        if self.attempted or now - self.last_activity < STALL_IDLE_SECONDS:
            return
        self.attempted = True
        try:
            self.child = subprocess.Popen(
                [sys.executable, str(Path(__file__).resolve()), '--stall-daemon',
                 json.dumps(self.daemon), '--root', str(self.root), '--output', str(self.output)],
                stdin=subprocess.DEVNULL, stdout=sys.stderr, stderr=subprocess.DEVNULL,
                start_new_session=True)
            self.started = now
        except OSError:
            stall_marker('launch-failed')  # Never include exception text or paths.

    def close(self):
        if self.child:
            # The session contains only this observer and its jcmd helpers.
            # Always reap its group, including a helper surviving an observer error.
            try:
                if owns_session(self.child.pid):
                    os.killpg(self.child.pid, signal.SIGKILL)
                    self.child.wait()
                else:
                    self.child.poll()  # Reap if exited; never wait on an unowned process.
            except ProcessLookupError:
                self.child.wait()
            self.child = None


def capture_chunks(source, observer):
    if observer is None:
        reader = getattr(source, 'read1', source.read)
        while chunk := reader(64 * 1024):
            yield chunk
        return
    selector = selectors.DefaultSelector()
    selector.register(source, selectors.EVENT_READ)
    try:
        while True:
            observer.tick()
            if not selector.select(1):
                continue
            chunk = os.read(source.fileno(), 64 * 1024)
            if not chunk:
                break
            observer.observe(chunk)
            yield chunk
    finally:
        selector.close()
        observer.close()


def capture_log(source, destination, path, observer=None):
    """Forward immediately; retain only a bounded tail, even if capture fails."""
    tail = deque()
    length = 0
    forwarding = True
    failed = False
    for chunk in capture_chunks(source, observer):
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
    # Preserve the existing lease descriptor; the canonical runner verifies its authority.
    session_fd = os.environ.get('FIREMUD_LOCAL_RESOURCE_SESSION_FD')
    session_fds = (int(session_fd),) if session_fd is not None else ()
    process = subprocess.Popen(
        ['bash', str(root / 'dev-tools/validation/run-locked-gradle.sh'),
         '--offline', '--no-daemon', '--no-configuration-cache',
         '--console=plain', '--max-workers=2',
         '-Dorg.gradle.java.installations.auto-download=false',
         '-I', str(root / 'dev-tools/validation/compile-failure-facts.init.gradle'),
         '-PfiremudFailedCompile=' + task, facts_task],
        cwd=root, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
        start_new_session=True, bufsize=0, pass_fds=session_fds,
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
    parser.add_argument('--stall-output', type=Path)
    parser.add_argument('--stall-daemon')
    args = parser.parse_args()
    if args.stall_daemon is not None:
        # Independent ceiling also applies if the capture parent is abruptly killed.
        if not owns_session(os.getpid()):
            stall_marker('session-unowned', fd=1)
            return 2  # Private mode must be started by our observer, in its own session.
        signal.signal(signal.SIGALRM, stop_stall_session)
        signal.alarm(STALL_BATCH_SECONDS)
        stall_marker('started', fd=1)
        try:
            collect_stall(args.root, args.output, json.loads(args.stall_daemon))
        except (OSError, ValueError):
            stall_marker('setup-failed', fd=1)
            return 1
        stall_marker('completed', fd=1)
        return 0
    if args.capture_log is not None:
        observer = StallObserver(args.root, args.stall_output) if args.stall_output else None
        if observer:
            stall_marker('enabled')
            def stop(signum, frame):
                stall_marker('cancelled')
                observer.close()
                raise SystemExit(128 + signum)
            for signum in (signal.SIGTERM, signal.SIGINT, signal.SIGHUP):
                signal.signal(signum, stop)
        return capture_log(sys.stdin.buffer, sys.stdout.buffer, args.capture_log, observer)
    if any(value is None for value in (args.root, args.log, args.output, args.check_exit)):
        parser.error('collection requires --root, --log, --output and --check-exit')
    if 0 < args.check_exit < 128:
        collect(args.root, args.log, args.output, args.check_exit)
    return 0



if __name__ == '__main__':
    raise SystemExit(main())
