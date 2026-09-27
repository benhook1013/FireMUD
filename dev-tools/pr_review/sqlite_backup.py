"""One-shot, explicit SSH/SFTP backup and restore for a SQLite database.

This module is not imported by the review controller. A separate operator job
may call :func:`backup_database` for a database whose contents have already
been constrained to contain no credentials or raw secrets. It uses SQLite's
online backup API, publishes a content-addressed versioned file into an
operator-provisioned private directory, and reads the published bytes back
before reporting success.

The remote account, SSH key, known-hosts file, and destination directory are
provided by the caller. The destination must already exist, be owned by that
remote account, and have mode 0700. The module does not create accounts, keys,
directories, or secret material.
"""

from __future__ import annotations

import hashlib
import os
import re
import shlex
import sqlite3
import stat
import subprocess
import tempfile
import uuid
from contextlib import closing
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path, PurePosixPath

_BACKUP_NAME = re.compile(
    r"^pr-review-state-(?P<timestamp>\d{8}T\d{12}Z)-(?P<digest>[0-9a-f]{64})-"
    r"(?P<nonce>[0-9a-f]{32})\.sqlite3$"
)
_HOST = re.compile(
    r"^(?:[A-Za-z0-9_][A-Za-z0-9_.-]*@)?"
    r"(?:[A-Za-z0-9][A-Za-z0-9.-]*|\[[0-9A-Fa-f:]+\])$"
)
_REMOTE_PATH = re.compile(r"^/[A-Za-z0-9._/-]+$")
_COMMAND_TIMEOUT_SECONDS = 120


class BackupError(RuntimeError):
    """An expected backup or restore operation failed closed."""


@dataclass(frozen=True)
class Snapshot:
    """A consistent local SQLite snapshot and its content identity."""

    path: Path
    sha256: str
    size_bytes: int


@dataclass(frozen=True)
class BackupReceipt:
    """The remote versioned path verified by an SFTP readback."""

    filename: str
    remote_path: str
    sha256: str
    size_bytes: int


def create_snapshot(database_path: str | os.PathLike[str], snapshot_path: str | os.PathLike[str]) -> Snapshot:
    """Create a new, mode-0600 consistent snapshot using SQLite's backup API.

    ``snapshot_path`` must not already exist. The source is opened read-only,
    so a missing path cannot accidentally become a new empty database.
    """

    source = _require_regular_file(database_path, "source database", reject_symlink=True)
    target = Path(snapshot_path).expanduser().absolute()
    if not target.parent.is_dir():
        raise BackupError(f"snapshot parent directory does not exist: {target.parent}")

    created_identity: tuple[int, int] | None = None
    try:
        descriptor = os.open(target, os.O_CREAT | os.O_EXCL | os.O_RDWR, 0o600)
        os.fchmod(descriptor, 0o600)
        target_stat = os.fstat(descriptor)
        created_identity = (target_stat.st_dev, target_stat.st_ino)
        os.close(descriptor)

        source_uri = f"{source.as_uri()}?mode=ro"
        with closing(sqlite3.connect(source_uri, uri=True, timeout=10)) as source_connection:
            source_connection.execute("PRAGMA query_only = ON")
            with closing(sqlite3.connect(target, timeout=10)) as target_connection:
                target_connection.execute("PRAGMA journal_mode = DELETE")
                source_connection.backup(target_connection)
                _require_integrity(target_connection, "created snapshot")
                target_connection.commit()

        os.chmod(target, 0o600, follow_symlinks=False)
        _fsync_file(target)
        return _snapshot_info(target)
    except FileExistsError as exc:
        raise BackupError(f"snapshot path already exists: {target}") from exc
    except BackupError:
        _remove_created_file(target, created_identity)
        raise
    except (OSError, sqlite3.Error) as exc:
        _remove_created_file(target, created_identity)
        raise BackupError(f"could not create SQLite snapshot: {exc}") from exc


def restore_snapshot(snapshot_path: str | os.PathLike[str], destination_path: str | os.PathLike[str]) -> Snapshot:
    """Restore a validated snapshot into a new mode-0600 SQLite database.

    Existing destinations are never replaced. The destination is removed only
    if this call created it and the restore then failed.
    """

    source = _require_regular_file(snapshot_path, "snapshot", reject_symlink=True)
    target = Path(destination_path).expanduser().absolute()
    if not target.parent.is_dir():
        raise BackupError(f"restore parent directory does not exist: {target.parent}")

    _validate_database(source, "restore source")
    created_identity: tuple[int, int] | None = None
    try:
        descriptor = os.open(target, os.O_CREAT | os.O_EXCL | os.O_RDWR, 0o600)
        os.fchmod(descriptor, 0o600)
        target_stat = os.fstat(descriptor)
        created_identity = (target_stat.st_dev, target_stat.st_ino)
        os.close(descriptor)

        source_uri = f"{source.as_uri()}?mode=ro"
        with closing(sqlite3.connect(source_uri, uri=True, timeout=10)) as source_connection:
            source_connection.execute("PRAGMA query_only = ON")
            with closing(sqlite3.connect(target, timeout=10)) as target_connection:
                target_connection.execute("PRAGMA journal_mode = DELETE")
                source_connection.backup(target_connection)
                _require_integrity(target_connection, "restored database")
                target_connection.commit()

        os.chmod(target, 0o600, follow_symlinks=False)
        _fsync_file(target)
        return _snapshot_info(target)
    except FileExistsError as exc:
        raise BackupError(f"restore destination already exists: {target}") from exc
    except BackupError:
        _remove_created_file(target, created_identity)
        raise
    except (OSError, sqlite3.Error) as exc:
        _remove_created_file(target, created_identity)
        raise BackupError(f"could not restore SQLite snapshot: {exc}") from exc


def backup_database(
    database_path: str | os.PathLike[str],
    *,
    host: str,
    identity_file: str | os.PathLike[str],
    known_hosts_file: str | os.PathLike[str],
    remote_directory: str,
    ssh_binary: str = "ssh",
    sftp_binary: str = "sftp",
) -> BackupReceipt:
    """Snapshot and publish one versioned backup, then verify remote readback.

    Network failures raise :class:`BackupError`. This function must be invoked
    by a separate one-shot job; the interactive review command path must not
    call it.
    """

    remote = _remote_config(host, identity_file, known_hosts_file, remote_directory)
    with tempfile.TemporaryDirectory(prefix="firemud-pr-review-backup-") as temporary_name:
        temporary_directory = Path(temporary_name)
        snapshot_path = temporary_directory / "snapshot.sqlite3"
        snapshot = create_snapshot(database_path, snapshot_path)
        filename = _versioned_name(snapshot.sha256)
        final_remote_path = _remote_join(remote.directory, filename)
        partial_remote_path = _remote_join(remote.directory, f".{filename}.{uuid.uuid4().hex}.partial")
        readback_path = temporary_directory / "readback.sqlite3"

        _verify_remote_directory(remote, ssh_binary, expected_absent=final_remote_path)
        try:
            _run_sftp(
                remote,
                sftp_binary,
                f"put {_sftp_quote(str(snapshot.path))} {_sftp_quote(partial_remote_path)}\n",
            )
            _run_ssh(remote, ssh_binary, _publish_command(partial_remote_path, final_remote_path))
        except BackupError:
            _remove_remote_partial(remote, sftp_binary, partial_remote_path)
            raise

        _verify_remote_file(remote, ssh_binary, final_remote_path, set_private_mode=True)
        _run_sftp(
            remote,
            sftp_binary,
            f"get {_sftp_quote(final_remote_path)} {_sftp_quote(str(readback_path))}\n",
        )
        os.chmod(readback_path, 0o600, follow_symlinks=False)
        readback = _snapshot_info(readback_path)
        if readback.sha256 != snapshot.sha256 or readback.size_bytes != snapshot.size_bytes:
            raise BackupError(f"remote backup readback did not match uploaded snapshot: {final_remote_path}")
        _validate_database(readback_path, "remote backup readback")
        return BackupReceipt(
            filename=filename,
            remote_path=final_remote_path,
            sha256=snapshot.sha256,
            size_bytes=snapshot.size_bytes,
        )


def restore_remote_backup(
    filename: str,
    destination_path: str | os.PathLike[str],
    *,
    host: str,
    identity_file: str | os.PathLike[str],
    known_hosts_file: str | os.PathLike[str],
    remote_directory: str,
    ssh_binary: str = "ssh",
    sftp_binary: str = "sftp",
) -> Snapshot:
    """Read back a named backup, validate its digest, and restore a new DB."""

    match = _BACKUP_NAME.fullmatch(filename)
    if match is None:
        raise BackupError("backup filename is not a versioned FireMUD SQLite backup")
    remote = _remote_config(host, identity_file, known_hosts_file, remote_directory)
    remote_path = _remote_join(remote.directory, filename)
    _verify_remote_directory(remote, ssh_binary)
    _verify_remote_file(remote, ssh_binary, remote_path, set_private_mode=False)

    with tempfile.TemporaryDirectory(prefix="firemud-pr-review-restore-") as temporary_name:
        local_snapshot = Path(temporary_name) / "download.sqlite3"
        _run_sftp(
            remote,
            sftp_binary,
            f"get {_sftp_quote(remote_path)} {_sftp_quote(str(local_snapshot))}\n",
        )
        os.chmod(local_snapshot, 0o600, follow_symlinks=False)
        downloaded = _snapshot_info(local_snapshot)
        if downloaded.sha256 != match.group("digest"):
            raise BackupError(f"remote backup digest does not match its versioned filename: {remote_path}")
        _validate_database(local_snapshot, "downloaded remote backup")
        return restore_snapshot(local_snapshot, destination_path)


@dataclass(frozen=True)
class _RemoteConfig:
    host: str
    identity_file: Path
    known_hosts_file: Path
    directory: str


def _remote_config(
    host: str,
    identity_file: str | os.PathLike[str],
    known_hosts_file: str | os.PathLike[str],
    remote_directory: str,
) -> _RemoteConfig:
    if (
        not isinstance(host, str)
        or not _HOST.fullmatch(host)
        or host.startswith("-")
        or "@" not in host
        or host.partition("@")[0].lower() == "root"
    ):
        raise BackupError("host must include an explicit non-root SSH account and host")
    directory = _validate_remote_directory(remote_directory)
    identity = _require_regular_file(identity_file, "SSH identity file", reject_symlink=True)
    identity_mode = stat.S_IMODE(identity.stat().st_mode)
    if identity_mode & 0o077:
        raise BackupError("SSH identity file permissions must exclude group and other access")
    if hasattr(os, "geteuid") and identity.stat().st_uid != os.geteuid():
        raise BackupError("SSH identity file must be owned by the calling user")
    known_hosts = _require_regular_file(known_hosts_file, "known-hosts file", reject_symlink=True)
    if stat.S_IMODE(known_hosts.stat().st_mode) & 0o022:
        raise BackupError("known-hosts file must not be group or world writable")
    return _RemoteConfig(host, identity, known_hosts, directory)


def _validate_remote_directory(directory: str) -> str:
    if (
        not isinstance(directory, str)
        or not _REMOTE_PATH.fullmatch(directory)
        or directory == "/"
        or directory.endswith("/")
        or any(component in {"", ".", ".."} for component in directory.split("/")[1:])
    ):
        raise BackupError("remote directory must be an explicit absolute path without traversal or shell characters")
    return directory


def _require_regular_file(
    path_value: str | os.PathLike[str], label: str, *, reject_symlink: bool
) -> Path:
    path = Path(path_value).expanduser().absolute()
    try:
        file_stat = path.stat(follow_symlinks=False)
    except OSError as exc:
        raise BackupError(f"{label} is unavailable: {path}") from exc
    if not stat.S_ISREG(file_stat.st_mode) or (reject_symlink and path.is_symlink()):
        raise BackupError(f"{label} must be a regular non-symlink file: {path}")
    return path


def _snapshot_info(path: Path) -> Snapshot:
    _require_regular_file(path, "SQLite snapshot", reject_symlink=True)
    hasher = hashlib.sha256()
    size = 0
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            hasher.update(chunk)
            size += len(chunk)
    return Snapshot(path=path, sha256=hasher.hexdigest(), size_bytes=size)


def _validate_database(path: Path, label: str) -> None:
    uri = f"{path.as_uri()}?mode=ro"
    try:
        with closing(sqlite3.connect(uri, uri=True, timeout=10)) as connection:
            _require_integrity(connection, label)
    except sqlite3.Error as exc:
        raise BackupError(f"{label} is not a readable SQLite database: {exc}") from exc


def _require_integrity(connection: sqlite3.Connection, label: str) -> None:
    rows = connection.execute("PRAGMA integrity_check").fetchall()
    if rows != [("ok",)]:
        raise BackupError(f"{label} failed SQLite integrity_check")


def _fsync_file(path: Path) -> None:
    descriptor = os.open(path, os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0))
    try:
        os.fsync(descriptor)
    finally:
        os.close(descriptor)


def _remove_created_file(path: Path, identity: tuple[int, int] | None) -> None:
    if identity is None:
        return
    try:
        current = path.stat(follow_symlinks=False)
        if (current.st_dev, current.st_ino) == identity and stat.S_ISREG(current.st_mode):
            path.unlink()
    except OSError:
        return


def _versioned_name(digest: str) -> str:
    timestamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S%fZ")
    return f"pr-review-state-{timestamp}-{digest}-{uuid.uuid4().hex}.sqlite3"


def _remote_join(directory: str, filename: str) -> str:
    return str(PurePosixPath(directory) / filename)


def _sftp_quote(value: str) -> str:
    if "\n" in value or "\r" in value:
        raise BackupError("SFTP paths cannot contain line breaks")
    return '"' + value.replace("\\", "\\\\").replace('"', '\\"') + '"'


def _ssh_base(remote: _RemoteConfig) -> list[str]:
    return [
        "-F", "/dev/null",
        "-o", "BatchMode=yes",
        "-o", "StrictHostKeyChecking=yes",
        "-o", "UpdateHostKeys=no",
        "-o", "IdentitiesOnly=yes",
        "-o", "PreferredAuthentications=publickey",
        "-o", "PasswordAuthentication=no",
        "-o", "KbdInteractiveAuthentication=no",
        "-o", "GlobalKnownHostsFile=/dev/null",
        "-o", f"UserKnownHostsFile={remote.known_hosts_file}",
        "-o", "ConnectTimeout=15",
        "-o", "ServerAliveInterval=15",
        "-o", "ServerAliveCountMax=2",
        "-i", str(remote.identity_file),
    ]


def _run_ssh(remote: _RemoteConfig, ssh_binary: str, command: str) -> None:
    _run([ssh_binary, *_ssh_base(remote), remote.host, command], None, "SSH command")


def _run_sftp(remote: _RemoteConfig, sftp_binary: str, batch: str) -> None:
    _run([sftp_binary, *_ssh_base(remote), "-b", "-", remote.host], batch, "SFTP transfer")


def _run(arguments: list[str], input_text: str | None, operation: str) -> None:
    try:
        result = subprocess.run(
            arguments,
            input=input_text,
            capture_output=True,
            text=True,
            timeout=_COMMAND_TIMEOUT_SECONDS,
            check=False,
        )
    except subprocess.TimeoutExpired as exc:
        raise BackupError(f"{operation} timed out after {_COMMAND_TIMEOUT_SECONDS} seconds") from exc
    except OSError as exc:
        raise BackupError(f"could not start {operation}: {exc}") from exc
    if result.returncode != 0:
        detail = (result.stderr or result.stdout).strip()
        suffix = f": {detail}" if detail else ""
        raise BackupError(f"{operation} failed with exit status {result.returncode}{suffix}")


def _directory_guard_command(directory: str, expected_absent: str | None = None) -> str:
    quoted_directory = shlex.quote(directory)
    checks = [
        "set -eu",
        f"d={quoted_directory}",
        '[ -d "$d" ]',
        '[ ! -L "$d" ]',
        '[ "$(id -u)" -ne 0 ]',
        '[ "$(stat -c %a -- "$d")" = 700 ]',
        '[ "$(stat -c %u -- "$d")" = "$(id -u)" ]',
    ]
    if expected_absent is not None:
        quoted_target = shlex.quote(expected_absent)
        checks.extend((f"p={quoted_target}", '[ ! -e "$p" ]', '[ ! -L "$p" ]'))
    return "; ".join(checks)


def _file_guard_command(path: str, *, set_private_mode: bool) -> str:
    quoted_path = shlex.quote(path)
    checks = ["set -eu", f"p={quoted_path}"]
    if set_private_mode:
        checks.append('chmod 600 -- "$p"')
    checks.extend(
        (
            '[ -f "$p" ]',
            '[ ! -L "$p" ]',
            '[ "$(stat -c %a -- "$p")" = 600 ]',
            '[ "$(stat -c %u -- "$p")" = "$(id -u)" ]',
        )
    )
    return "; ".join(checks)


def _publish_command(partial_path: str, final_path: str) -> str:
    source = shlex.quote(partial_path)
    target = shlex.quote(final_path)
    return "; ".join(
        (
            "set -eu",
            f"s={source}",
            f"d={target}",
            '[ -f "$s" ]',
            '[ ! -L "$s" ]',
            'ln -- "$s" "$d"',
            'rm -- "$s"',
        )
    )


def _verify_remote_directory(
    remote: _RemoteConfig, ssh_binary: str, expected_absent: str | None = None
) -> None:
    _run_ssh(remote, ssh_binary, _directory_guard_command(remote.directory, expected_absent))


def _verify_remote_file(
    remote: _RemoteConfig, ssh_binary: str, path: str, *, set_private_mode: bool
) -> None:
    _run_ssh(remote, ssh_binary, _file_guard_command(path, set_private_mode=set_private_mode))


def _remove_remote_partial(remote: _RemoteConfig, sftp_binary: str, path: str) -> None:
    try:
        _run_sftp(remote, sftp_binary, f"-rm {_sftp_quote(path)}\n")
    except BackupError:
        # The primary transfer failure remains authoritative; an inaccessible
        # unique partial path is confined by the already-private directory.
        return
