"""One-shot SFTP-only backup and restore for a SQLite database.

This module is not imported by the review controller. A separate operator job
may call :func:`backup_database` for the compatible FireMUD controller and
bootstrapped review-record database. It checks an allowlisted schema and
screens persisted text before transfer, then uses SQLite's online backup API,
publishes a content-addressed versioned file into an operator-provisioned
private directory, and reads the published bytes back before reporting success.

The remote account must be a dedicated unprivileged SFTP-only identity, jailed
to a pre-provisioned directory with mode 0700. The job checks that directory's
visible owner and mode, rejects symlink or writable path components, and checks
artifact ownership and mode through SFTP listings. The forced-SFTP jail remains
a server provisioning invariant that the client cannot independently attest.
The module does not create accounts, keys, directories, or secret material.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import sqlite3
import stat
import subprocess
import sys
import tempfile
import uuid
from contextlib import closing
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path, PurePosixPath

from .sqlite_review_records import (
    _FULL_COMMIT_SHA,
    _LOW_ENTROPY_IDENTIFIER,
    _SECRET_PATTERNS,
    SqliteReviewRecords,
)
from .sqlite_store import SqliteStateStore

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
_DEFAULT_RETENTION_COUNT = 30
_GENERIC_SECRET_PATTERN = _SECRET_PATTERNS[-1]
_SPECIFIC_SECRET_PATTERNS = _SECRET_PATTERNS[:-1]
# Legacy route notes can name long Java test classes; keep this exception to
# alphabetic PascalCase test names, after the explicit credential patterns run.
_JAVA_TEST_IDENTIFIER = re.compile(r"(?:[A-Z][a-z]{2,}){4,}Test")
# Record IDs are validated on write. Their long, hyphenated, human-readable
# names can mention words such as "bearer" or "key" without containing a key.
# Keep this exception limited to identifier fields; free text remains screened.
_REVIEW_IDENTIFIER = re.compile(r"[a-z][a-z0-9]{0,23}(?:[-_][a-z0-9]{1,24}){3,}")
_IDENTIFIER_TEXT_COLUMNS = {
    "review_runs": {"run_id"},
    "findings": {"finding_id", "source_finding_key"},
    "finding_observations": {"run_id", "finding_id", "route_id"},
    "routes": {"route_id", "finding_id"},
    "route_target_history": {"route_id"},
    "decisions": {"decision_id", "run_id", "finding_id", "route_id"},
    "resolutions": {"resolution_id", "route_id"},
}
_EXPECTED_COLUMNS = {
    "controller_metadata": ("singleton", "data_model_version", "min_writer_build"),
    "review_state": ("singleton", "state_json"),
    "review_records_metadata": (
        "singleton", "records_schema_version", "controller_schema_version",
        "controller_data_model_version", "min_writer_build",
    ),
    "review_runs": (
        "run_id", "source_pr", "channel", "source_head", "reviewer", "scope",
        "coverage_limits_json", "import_payload_json", "outcome", "attributable",
        "started_at", "finished_at", "found_count", "accepted_count", "routed_count",
        "finalized", "finalized_at",
    ),
    "findings": ("finding_id", "source_pr", "source_channel", "source_finding_key", "first_seen_at"),
    "finding_observations": (
        "run_id", "finding_id", "source_pr", "source_channel", "title", "detail", "disposition", "route_id",
    ),
    "routes": (
        "route_id", "finding_id", "source_pr", "source_channel", "target_pr", "status", "created_at", "updated_at",
    ),
    "route_target_history": ("sequence", "route_id", "target_pr", "changed_at", "actor", "reason"),
    "decisions": (
        "decision_id", "decision_scope", "run_id", "finding_id", "route_id", "decision_pr", "target_pr",
        "decision", "actor", "reason", "decided_at",
    ),
    "resolutions": (
        "resolution_id", "route_id", "resolution_pr", "outcome", "actor", "proof_or_reason", "resolved_at",
    ),
}
_EXPECTED_INDEXES = {"review_runs_source_pr_idx", "routes_target_status_idx"}
_TEXT_COLUMNS = {
    "controller_metadata": (),
    "review_state": ("state_json",),
    "review_records_metadata": (),
    "review_runs": ("run_id", "source_head", "reviewer", "scope", "coverage_limits_json", "import_payload_json", "outcome", "started_at", "finished_at", "finalized_at"),
    "findings": ("finding_id", "source_channel", "source_finding_key", "first_seen_at"),
    "finding_observations": ("run_id", "finding_id", "source_channel", "title", "detail", "disposition", "route_id"),
    "routes": ("route_id", "finding_id", "source_channel", "status", "created_at", "updated_at"),
    "route_target_history": ("route_id", "changed_at", "actor", "reason"),
    "decisions": ("decision_id", "decision_scope", "run_id", "finding_id", "route_id", "decision", "actor", "reason", "decided_at"),
    "resolutions": ("resolution_id", "route_id", "outcome", "actor", "proof_or_reason", "resolved_at"),
}


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
        _validate_database(target, "restored database")
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
    remote_uid: int,
    sftp_binary: str = "sftp",
    retention_count: int = _DEFAULT_RETENTION_COUNT,
) -> BackupReceipt:
    """Snapshot and publish one versioned backup, then verify remote readback.

    Network failures raise :class:`BackupError`. This function must be invoked
    by a separate one-shot job; the interactive review command path must not
    call it.
    """

    _validate_database(Path(database_path).expanduser().absolute(), "source database")
    remote = _remote_config(host, identity_file, known_hosts_file, remote_directory, remote_uid)
    if not isinstance(retention_count, int) or retention_count < 1:
        raise BackupError("retention count must be a positive integer")
    with tempfile.TemporaryDirectory(prefix="firemud-pr-review-backup-") as temporary_name:
        temporary_directory = Path(temporary_name)
        snapshot_path = temporary_directory / "snapshot.sqlite3"
        snapshot = create_snapshot(database_path, snapshot_path)
        _validate_database(snapshot.path, "local snapshot")
        filename = _versioned_name(snapshot.sha256)
        final_remote_path = _remote_join(remote.directory, filename)
        partial_remote_path = _remote_join(remote.directory, f".{filename}.{uuid.uuid4().hex}.partial")
        readback_path = temporary_directory / "readback.sqlite3"

        _verify_remote_directory(remote, sftp_binary)
        try:
            _run_sftp(
                remote,
                sftp_binary,
                f"put {_sftp_quote(str(snapshot.path))} {_sftp_quote(partial_remote_path)}\n",
            )
            _run_sftp(remote, sftp_binary, f"chmod 600 {_sftp_quote(partial_remote_path)}\n")
            _verify_remote_file(remote, sftp_binary, partial_remote_path)
            _run_sftp(
                remote,
                sftp_binary,
                f"get {_sftp_quote(partial_remote_path)} {_sftp_quote(str(readback_path))}\n",
            )
            os.chmod(readback_path, 0o600, follow_symlinks=False)
            readback = _snapshot_info(readback_path)
            if readback.sha256 != snapshot.sha256 or readback.size_bytes != snapshot.size_bytes:
                raise BackupError("uploaded SFTP bytes did not match the local snapshot")
            _validate_database(readback_path, "remote backup readback")
            _run_sftp(remote, sftp_binary, f"rename {_sftp_quote(partial_remote_path)} {_sftp_quote(final_remote_path)}\n")
        except (BackupError, OSError) as exc:
            _remove_remote_partial(remote, sftp_binary, partial_remote_path)
            if isinstance(exc, OSError):
                raise BackupError("could not verify uploaded SFTP bytes") from exc
            raise

        try:
            _verify_remote_file(remote, sftp_binary, final_remote_path)
        except BackupError:
            _remove_remote_partial(remote, sftp_binary, final_remote_path)
            raise
        _prune_remote_backups(remote, sftp_binary, retention_count, keep=filename)
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
    remote_uid: int,
    sftp_binary: str = "sftp",
) -> Snapshot:
    """Read back a named backup, validate its digest, and restore a new DB."""

    match = _BACKUP_NAME.fullmatch(filename)
    if match is None:
        raise BackupError("backup filename is not a versioned FireMUD SQLite backup")
    destination = _new_destination_path(destination_path)
    remote = _remote_config(host, identity_file, known_hosts_file, remote_directory, remote_uid)
    remote_path = _remote_join(remote.directory, filename)
    _verify_remote_directory(remote, sftp_binary)
    _verify_remote_file(remote, sftp_binary, remote_path)

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
        return restore_snapshot(local_snapshot, destination)


def _new_destination_path(destination_path: str | os.PathLike[str]) -> Path:
    target = Path(destination_path).expanduser().absolute()
    if not target.parent.is_dir():
        raise BackupError(f"restore parent directory does not exist: {target.parent}")
    try:
        target.stat(follow_symlinks=False)
    except FileNotFoundError:
        return target
    except OSError as exc:
        raise BackupError("restore destination cannot be checked safely") from exc
    raise BackupError(f"restore destination already exists: {target}")


@dataclass(frozen=True)
class _RemoteConfig:
    host: str
    remote_uid: int
    identity_file: Path
    known_hosts_file: Path
    directory: str


def _remote_config(
    host: str,
    identity_file: str | os.PathLike[str],
    known_hosts_file: str | os.PathLike[str],
    remote_directory: str,
    remote_uid: int,
) -> _RemoteConfig:
    if isinstance(remote_uid, bool) or not isinstance(remote_uid, int) or remote_uid < 1:
        raise BackupError("remote UID must be a pinned positive integer")
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
    return _RemoteConfig(host, remote_uid, identity, known_hosts, directory)


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
            connection.execute("PRAGMA query_only = ON")
            _require_integrity(connection, label)
            _require_allowlisted_schema(connection)
            _screen_persisted_text(connection)

        # These public read paths validate the controller document, the full
        # record schema, and indexed history/worklist reads without changing it.
        state = SqliteStateStore(path).load()
        if not isinstance(state.to_dict(), dict):
            raise BackupError(f"{label} contains invalid logical controller state")
        records = SqliteReviewRecords(path)
        with closing(sqlite3.connect(uri, uri=True, timeout=10)) as connection:
            connection.execute("PRAGMA query_only = ON")
            prs = {
                int(row[0])
                for row in connection.execute(
                    "SELECT source_pr FROM review_runs UNION SELECT decision_pr FROM decisions "
                    "UNION SELECT source_pr FROM routes UNION SELECT target_pr FROM routes WHERE target_pr IS NOT NULL "
                    "UNION SELECT resolution_pr FROM resolutions"
                )
            }
        for pr in sorted(prs):
            history = records.history(pr)
            with closing(sqlite3.connect(uri, uri=True, timeout=10)) as connection:
                connection.execute("PRAGMA query_only = ON")
                expected = (
                    connection.execute("SELECT COUNT(*) FROM review_runs WHERE source_pr = ?", (pr,)).fetchone()[0],
                    connection.execute(
                        "SELECT COUNT(*) FROM finding_observations WHERE source_pr = ?", (pr,)
                    ).fetchone()[0],
                    connection.execute("SELECT COUNT(*) FROM decisions WHERE decision_pr = ?", (pr,)).fetchone()[0],
                    connection.execute(
                        "SELECT COUNT(*) FROM routes WHERE source_pr = ? OR target_pr = ?", (pr, pr)
                    ).fetchone()[0],
                )
            actual = (
                len(history["runs"]), len(history["findings"]),
                len(history["decisions"]), len(history["routes"]),
            )
            if actual != expected:
                raise BackupError("indexed review-history readback does not match persisted record counts")
        open_routes = records.open_routes()
        with closing(sqlite3.connect(uri, uri=True, timeout=10)) as connection:
            connection.execute("PRAGMA query_only = ON")
            expected_open_routes = connection.execute(
                "SELECT COUNT(*) FROM routes WHERE status = 'open'"
            ).fetchone()[0]
        if len(open_routes) != expected_open_routes:
            raise BackupError("indexed route-worklist readback does not match persisted records")
    except BackupError:
        raise
    except (OSError, sqlite3.Error, ValueError, TypeError) as exc:
        raise BackupError(f"{label} failed FireMUD SQLite schema or logical readback validation") from exc


def _require_allowlisted_schema(connection: sqlite3.Connection) -> None:
    objects = connection.execute(
        "SELECT type, name FROM sqlite_master WHERE name NOT LIKE 'sqlite_%'"
    ).fetchall()
    tables = {name for object_type, name in objects if object_type == "table"}
    indexes = {name for object_type, name in objects if object_type == "index"}
    if tables != set(_EXPECTED_COLUMNS):
        raise BackupError("database tables do not match the allowlisted FireMUD controller and review-record schema")
    if indexes != _EXPECTED_INDEXES or any(object_type not in {"table", "index"} for object_type, _name in objects):
        raise BackupError("database contains non-allowlisted SQLite schema objects")
    for table, expected_columns in _EXPECTED_COLUMNS.items():
        actual_columns = tuple(
            row[1] for row in connection.execute(f'PRAGMA table_info("{table}")')
        )
        if actual_columns != expected_columns:
            raise BackupError(f"database columns do not match the allowlist for {table}")


def _screen_persisted_text(connection: sqlite3.Connection) -> None:
    for table, columns in _TEXT_COLUMNS.items():
        for column in columns:
            for (value,) in connection.execute(f'SELECT "{column}" FROM "{table}"'):
                if not isinstance(value, str):
                    continue
                if column in {"state_json", "import_payload_json"}:
                    try:
                        document = json.loads(value)
                    except json.JSONDecodeError as exc:
                        raise BackupError("persisted JSON cannot be screened") from exc
                    if column == "import_payload_json":
                        _screen_import_payload(document)
                    else:
                        _screen_json_values(document)
                elif _looks_secret(value, identifier=column in _IDENTIFIER_TEXT_COLUMNS.get(table, set())):
                    raise BackupError("database contains credential- or raw-secret-looking text")


def _screen_import_payload(document: object) -> None:
    if not isinstance(document, dict):
        raise BackupError("review import payload is not an object")
    for key, value in document.items():
        if key != "findings" or not isinstance(value, list):
            _screen_json_values(value)
            continue
        for finding in value:
            if not isinstance(finding, dict):
                raise BackupError("review import finding is not an object")
            for finding_key, finding_value in finding.items():
                if finding_key == "source_finding_key" and isinstance(finding_value, str):
                    if _looks_secret(finding_value, identifier=True):
                        raise BackupError("database contains credential- or raw-secret-looking text")
                else:
                    _screen_json_values(finding_value)


def _screen_json_values(value: object) -> None:
    if isinstance(value, str):
        if _looks_secret(value):
            raise BackupError("database contains credential- or raw-secret-looking text")
    elif isinstance(value, dict):
        for nested_value in value.values():
            _screen_json_values(nested_value)
    elif isinstance(value, list):
        for nested_value in value:
            _screen_json_values(nested_value)


def _looks_secret(value: str, *, identifier: bool = False) -> bool:
    if any(pattern.search(value) for pattern in _SPECIFIC_SECRET_PATTERNS):
        return True
    if identifier and _REVIEW_IDENTIFIER.fullmatch(value):
        return False
    return any(
        not _is_known_identifier(match.group())
        for match in _GENERIC_SECRET_PATTERN.finditer(value)
    )


def _is_known_identifier(token: str) -> bool:
    if _FULL_COMMIT_SHA.fullmatch(token):
        return True
    if _JAVA_TEST_IDENTIFIER.fullmatch(token):
        return True
    if not _LOW_ENTROPY_IDENTIFIER.fullmatch(token):
        return False
    words = re.split(r"[_-]", token)
    return not set(words) & {
        "access", "aws", "bearer", "credential", "github", "key", "password", "private", "secret", "token"
    }


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


def _sftp_transport_options(remote: _RemoteConfig) -> list[str]:
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


def _run_sftp(remote: _RemoteConfig, sftp_binary: str, batch: str) -> str:
    return _run([sftp_binary, *_sftp_transport_options(remote), "-b", "-", remote.host], batch, "SFTP operation")


def _run(arguments: list[str], input_text: str | None, operation: str) -> str:
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
        # Do not copy server diagnostics into reports or job logs: they are
        # external text and can contain sensitive path or account details.
        raise BackupError(f"{operation} failed with exit status {result.returncode}")
    return result.stdout


def _verify_remote_directory(remote: _RemoteConfig, sftp_binary: str) -> None:
    components = PurePosixPath(remote.directory).parts[1:]
    parent = PurePosixPath("/")
    for index, component in enumerate(components):
        output = _run_sftp(remote, sftp_binary, f"ls -ln {_sftp_quote(str(parent))}\n")
        entry = _listing_entry(output, component, expected_path=str(parent / component))
        if entry is None or not entry[0].startswith("d"):
            raise BackupError("remote backup path contains a missing or non-directory component")
        is_destination = index == len(components) - 1
        if is_destination:
            if entry[0] != "drwx------" or not _owner_matches(entry[2], remote.remote_uid):
                raise BackupError("remote backup directory must be mode 0700 and owned by the pinned remote UID")
        elif entry[0][5] == "w" or entry[0][8] == "w":
            raise BackupError("remote backup path contains a group- or world-writable directory")
        parent = parent / component
    _run_sftp(remote, sftp_binary, f"cd {_sftp_quote(remote.directory)}\npwd\n")


def _parse_listing_line(line: str) -> tuple[str, str, int, str] | None:
    match = re.match(
        r"^(?P<mode>[bcdlps-][rwxStTs-]{9})\s+(?:\d+|\?)\s+(?P<owner>\S+)\s+\S+\s+"
        r"(?P<size>\d+)\s+\S+\s+\S+\s+\S+\s+(?P<name>.+?)\s*$",
        line,
    )
    if match is None:
        return None
    return match.group("mode"), match.group("owner"), int(match.group("size")), match.group("name")


def _listing_entry(
    output: str, expected_name: str, *, expected_path: str | None = None
) -> tuple[str, int, str] | None:
    """Parse an OpenSSH SFTP long-listing row with basename or full-path names."""
    for line in output.splitlines():
        parsed = _parse_listing_line(line)
        if parsed is None:
            continue
        mode, owner, size, listed_name = parsed
        if listed_name == expected_name or (expected_path is not None and listed_name == expected_path):
            return mode, size, owner
    return None


def _verify_remote_file(remote: _RemoteConfig, sftp_binary: str, path: str) -> None:
    output = _run_sftp(remote, sftp_binary, f"ls -ln {_sftp_quote(path)}\n")
    expected_name = PurePosixPath(path).name
    entry = _listing_entry(output, expected_name, expected_path=path)
    if entry is None or entry[0] != "-rw-------" or not _owner_matches(entry[2], remote.remote_uid):
        raise BackupError(f"remote backup file metadata is not private and owned by the pinned remote UID: {path}")


def _owner_matches(owner_text: str, expected_uid: int) -> bool:
    return owner_text.isascii() and owner_text.isdecimal() and int(owner_text) == expected_uid


def _remove_remote_partial(remote: _RemoteConfig, sftp_binary: str, path: str) -> None:
    try:
        _run_sftp(remote, sftp_binary, f"-rm {_sftp_quote(path)}\n")
    except BackupError:
        # The primary transfer failure remains authoritative; an inaccessible
        # unique partial path is confined by the already-private directory.
        return


def _prune_remote_backups(
    remote: _RemoteConfig, sftp_binary: str, retention_count: int, *, keep: str
) -> None:
    output = _run_sftp(remote, sftp_binary, f"ls -ln {_sftp_quote(remote.directory)}\n")
    entries: list[str] = []
    for line in output.splitlines():
        parsed = _parse_listing_line(line)
        if parsed is None:
            continue
        mode, owner, _size, listed_name = parsed
        listed_path = PurePosixPath(listed_name)
        if listed_path.is_absolute():
            if listed_path.parent != PurePosixPath(remote.directory):
                continue
            filename = listed_path.name
        else:
            filename = listed_name
        if _BACKUP_NAME.fullmatch(filename):
            if mode != "-rw-------" or not _owner_matches(owner, remote.remote_uid):
                raise BackupError("remote backup retention found an artifact with unsafe metadata")
            entries.append(filename)
    if keep not in entries:
        raise BackupError("new remote backup is missing from the SFTP retention listing")
    retained = {keep, *sorted((name for name in entries if name != keep), reverse=True)[: retention_count - 1]}
    for filename in entries:
        if filename not in retained:
            _run_sftp(remote, sftp_binary, f"rm {_sftp_quote(_remote_join(remote.directory, filename))}\n")


def _write_report(report_path: str | os.PathLike[str], payload: dict[str, object]) -> None:
    target = Path(report_path).expanduser().absolute()
    if not target.parent.is_dir():
        raise BackupError("report parent directory does not exist")
    temporary = target.parent / f".{target.name}.{uuid.uuid4().hex}.tmp"
    encoded = (json.dumps(payload, sort_keys=True, separators=(",", ":")) + "\n").encode("utf-8")
    descriptor = -1
    try:
        descriptor = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        remaining = memoryview(encoded)
        while remaining:
            remaining = remaining[os.write(descriptor, remaining):]
        os.fsync(descriptor)
        os.close(descriptor)
        descriptor = -1
        os.replace(temporary, target)
        os.chmod(target, 0o600, follow_symlinks=False)
        directory_fd = os.open(target.parent, os.O_RDONLY | getattr(os, "O_DIRECTORY", 0))
        try:
            os.fsync(directory_fd)
        finally:
            os.close(directory_fd)
    except OSError as exc:
        if descriptor >= 0:
            os.close(descriptor)
        try:
            temporary.unlink()
        except OSError:
            pass
        raise BackupError("could not persist local backup status report") from exc


def _read_report(report_path: str | os.PathLike[str]) -> dict[str, object]:
    target = Path(report_path).expanduser().absolute()
    if not target.parent.is_dir():
        raise BackupError("report parent directory does not exist")
    try:
        info = target.stat(follow_symlinks=False)
    except FileNotFoundError:
        return {"lastSuccess": None, "lastFailureAt": None}
    except OSError as exc:
        raise BackupError("could not read local backup status report") from exc
    if (
        not stat.S_ISREG(info.st_mode)
        or stat.S_IMODE(info.st_mode) & 0o077
        or (hasattr(os, "geteuid") and info.st_uid != os.geteuid())
    ):
        raise BackupError("existing backup status report must be a private regular file")
    try:
        value = json.loads(target.read_text(encoding="utf-8"))
    except (OSError, UnicodeError, json.JSONDecodeError) as exc:
        raise BackupError("existing backup status report is unreadable") from exc
    if not isinstance(value, dict):
        raise BackupError("existing backup status report is malformed")
    last_success = value.get("lastSuccess")
    last_failure_at = value.get("lastFailureAt")
    if last_success is not None and not isinstance(last_success, dict):
        raise BackupError("existing backup status report is malformed")
    if last_failure_at is not None and not isinstance(last_failure_at, str):
        raise BackupError("existing backup status report is malformed")
    try:
        if last_failure_at is not None:
            last_failure_at = datetime.fromisoformat(last_failure_at).astimezone(timezone.utc).isoformat()
        if last_success is not None:
            completed_at = last_success.get("completedAt")
            filename = last_success.get("filename")
            digest = last_success.get("sha256")
            size_bytes = last_success.get("sizeBytes")
            match = _BACKUP_NAME.fullmatch(filename) if isinstance(filename, str) else None
            if (
                not isinstance(completed_at, str)
                or match is None
                or not isinstance(digest, str)
                or digest != match.group("digest")
                or not isinstance(size_bytes, int)
                or size_bytes < 1
            ):
                raise ValueError("invalid last-success record")
            last_success = {
                "completedAt": datetime.fromisoformat(completed_at).astimezone(timezone.utc).isoformat(),
                "filename": filename,
                "sha256": digest,
                "sizeBytes": size_bytes,
            }
    except (ValueError, TypeError) as exc:
        raise BackupError("existing backup status report is malformed") from exc
    return {"lastSuccess": last_success, "lastFailureAt": last_failure_at}


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Create or restore one SFTP-only SQLite backup")
    parser.add_argument(
        "database",
        type=Path,
        nargs="?",
        help="source database for backup, or a new destination database with --restore",
    )
    parser.add_argument("--restore", metavar="FILENAME", help="restore this versioned remote backup")
    parser.add_argument("--host", required=True, help="dedicated non-root account@host")
    parser.add_argument("--identity-file", required=True, type=Path)
    parser.add_argument("--known-hosts-file", required=True, type=Path)
    parser.add_argument("--remote-directory", required=True)
    parser.add_argument("--remote-uid", required=True, type=int)
    parser.add_argument("--report-file", type=Path)
    parser.add_argument("--retention-count", type=int, default=_DEFAULT_RETENTION_COUNT)
    parser.add_argument("--sftp-binary", default="sftp")
    args = parser.parse_args(argv)
    if args.database is None:
        parser.error("a source or destination database path is required")
    if args.restore is not None:
        try:
            restored = restore_remote_backup(
                args.restore,
                args.database,
                host=args.host,
                identity_file=args.identity_file,
                known_hosts_file=args.known_hosts_file,
                remote_directory=args.remote_directory,
                remote_uid=args.remote_uid,
                sftp_binary=args.sftp_binary,
            )
            print(f"restore verified: sha256={restored.sha256} size={restored.size_bytes}")
            return 0
        except (BackupError, OSError) as exc:
            print(f"restore failed: {type(exc).__name__}", file=sys.stderr)
            return 1
    if args.report_file is None:
        parser.error("--report-file is required for backup mode")
    attempted_at = datetime.now(timezone.utc).isoformat()
    try:
        previous = _read_report(args.report_file)
    except BackupError as exc:
        print(f"backup failed: {type(exc).__name__}", file=sys.stderr)
        return 1
    try:
        receipt = backup_database(
            args.database,
            host=args.host,
            identity_file=args.identity_file,
            known_hosts_file=args.known_hosts_file,
            remote_directory=args.remote_directory,
            remote_uid=args.remote_uid,
            sftp_binary=args.sftp_binary,
            retention_count=args.retention_count,
        )
        _write_report(
            args.report_file,
            {
                "lastAttempt": {"startedAt": attempted_at, "completedAt": datetime.now(timezone.utc).isoformat(), "status": "success"},
                "lastSuccess": {
                    "completedAt": datetime.now(timezone.utc).isoformat(),
                    "filename": receipt.filename,
                    "sha256": receipt.sha256,
                    "sizeBytes": receipt.size_bytes,
                },
                "lastFailureAt": previous["lastFailureAt"],
            },
        )
        print(f"backup verified: {receipt.filename} sha256={receipt.sha256} size={receipt.size_bytes}")
        return 0
    except (BackupError, OSError) as exc:
        try:
            _write_report(
                args.report_file,
                {
                    "lastAttempt": {"startedAt": attempted_at, "completedAt": datetime.now(timezone.utc).isoformat(), "status": "failure"},
                    "lastSuccess": previous["lastSuccess"],
                    "lastFailureAt": datetime.now(timezone.utc).isoformat(),
                },
            )
        except BackupError:
            pass
        # Deliberately omit exception text because it can contain external diagnostics.
        print(f"backup failed: {type(exc).__name__}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
