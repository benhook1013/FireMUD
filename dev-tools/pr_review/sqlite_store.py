"""Transactional SQLite persistence for versioned PR-review controller state.

The controller selects this store only after an explicit JSON-to-SQLite
cutover; until then it uses the currently selected JSON state. Cutover and
migration are operator-controlled and version-checked. Structured review
records require separate explicit bootstrap. This module does not migrate
historical provider captures or old private review ledgers.
"""

from __future__ import annotations

import ctypes
import errno
import fcntl
import json
import os
import sqlite3
import sys
import tempfile
from collections.abc import Callable, Iterator, Mapping
from contextlib import closing, contextmanager
from pathlib import Path
from typing import Any

from .state import ReviewState, StateError, _locked, sqlite_state_path

SQLITE_SCHEMA_VERSION = 1
WRITER_BUILD = 2
STATUS_VERSION = 1
CUTOVER_VERSION = 1
_METADATA_TABLE = "controller_metadata"
_STATE_TABLE = "review_state"


def _atomic_exchange(first: Path, second: Path) -> None:
    """Atomically exchange two sibling directory entries on Linux."""

    if sys.platform != "linux":
        raise StateError("atomic SQLite cutover requires Linux renameat2 support")
    try:
        renameat2 = ctypes.CDLL(None, use_errno=True).renameat2
    except AttributeError as exc:
        raise StateError("atomic SQLite cutover requires Linux renameat2 support") from exc
    renameat2.argtypes = (ctypes.c_int, ctypes.c_char_p, ctypes.c_int, ctypes.c_char_p, ctypes.c_uint)
    renameat2.restype = ctypes.c_int
    result = renameat2(-100, os.fsencode(first), -100, os.fsencode(second), 2)
    if result == 0:
        return
    error_number = ctypes.get_errno()
    if error_number in {errno.ENOSYS, errno.EINVAL, getattr(errno, "EOPNOTSUPP", errno.EINVAL)}:
        raise StateError("filesystem does not support atomic SQLite cutover")
    raise OSError(error_number, os.strerror(error_number), str(first), str(second))


def _fsync_directory(path: Path) -> None:
    descriptor = os.open(path, os.O_RDONLY)
    try:
        os.fsync(descriptor)
    finally:
        os.close(descriptor)


def _remove_created_database(path: Path, identity: tuple[int, int] | None) -> None:
    if identity is None:
        return
    try:
        current = path.stat(follow_symlinks=False)
        if (current.st_dev, current.st_ino) == identity and path.is_file() and not path.is_symlink():
            path.unlink()
    except OSError:
        # Preserve uncertain state rather than risk removing a replacement.
        return


def _remove_staged_cutover(
    directory: Path,
    identity: tuple[int, int] | None,
    marker: Path | None,
    marker_bytes: bytes | None,
) -> None:
    if identity is None or marker is None or marker_bytes is None:
        return
    try:
        current = directory.stat(follow_symlinks=False)
        if (current.st_dev, current.st_ino) != identity or not directory.is_dir() or directory.is_symlink():
            return
        if marker.read_bytes() != marker_bytes:
            return
        marker.unlink()
        directory.rmdir()
    except OSError:
        # Keep unexpected files or replacements for inspection.
        return


class SqliteStateStore:
    """Persist one validated :class:`ReviewState` document transactionally."""

    def __init__(
        self,
        path: str | os.PathLike[str],
        *,
        writer_build: int = WRITER_BUILD,
        timeout: float = 10.0,
    ) -> None:
        if isinstance(writer_build, bool) or not isinstance(writer_build, int) or writer_build <= 0:
            raise ValueError("writer_build must be a positive integer")
        if timeout <= 0:
            raise ValueError("timeout must be positive")
        self.path = Path(path).expanduser().absolute()
        self.writer_build = writer_build
        self.timeout = timeout

    def load(self) -> ReviewState:
        """Load state without creating a missing database."""

        if not self.path.exists():
            return ReviewState()
        try:
            with closing(self._connect_read_only()) as connection:
                self._require_compatible(connection)
                row = connection.execute(f"SELECT state_json FROM {_STATE_TABLE} WHERE singleton = 1").fetchone()
        except sqlite3.DatabaseError as exc:
            raise StateError("cannot read SQLite review-state database") from exc
        if row is None or not isinstance(row[0], str):
            raise StateError("SQLite review state is missing its validated state document")
        try:
            document = json.loads(row[0])
        except json.JSONDecodeError as exc:
            raise StateError("SQLite review state contains malformed JSON") from exc
        if not isinstance(document, Mapping):
            raise StateError("SQLite review state document must be an object")
        return ReviewState.from_dict(document)

    def update(self, mutate: Callable[[ReviewState], ReviewState]) -> ReviewState:
        """Apply ``mutate`` and persist its validated result in one transaction."""

        if not callable(mutate):
            raise TypeError("mutate must be callable")
        self.path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
        with self._exclusive_update_lock():
            return self._update_locked(mutate)

    def _update_locked(self, mutate: Callable[[ReviewState], ReviewState]) -> ReviewState:
        created_identity = self._create_empty_file_exclusively()
        connection: sqlite3.Connection | None = None
        committed = False
        try:
            connection = sqlite3.connect(self.path, timeout=self.timeout, isolation_level=None)
            connection.execute(f"PRAGMA busy_timeout = {int(self.timeout * 1000)}")
            connection.execute("BEGIN IMMEDIATE")
            if created_identity is not None:
                self._initialize(connection, self.writer_build)
            else:
                self._require_compatible(connection)
            current = self._load_from_connection(connection)
            updated = mutate(current)
            if not isinstance(updated, ReviewState):
                raise TypeError("mutate must return ReviewState")
            payload = json.dumps(
                updated.to_dict(),
                ensure_ascii=True,
                sort_keys=True,
                separators=(",", ":"),
                allow_nan=False,
            )
            connection.execute(
                f"UPDATE {_STATE_TABLE} SET state_json = ? WHERE singleton = 1",
                (payload,),
            )
            connection.commit()
            committed = True
            return updated
        except BaseException:
            if connection is not None and connection.in_transaction:
                connection.rollback()
            if connection is not None:
                connection.close()
                connection = None
            if not committed and created_identity is not None:
                self._unlink_created_database(created_identity)
            raise
        finally:
            if connection is not None:
                connection.close()

    def status(self) -> dict[str, Any]:
        """Return local schema/build compatibility without creating or changing files."""

        base: dict[str, Any] = {
            "status_version": STATUS_VERSION,
            "format": "missing",
            "schema_version": None,
            "data_model_version": None,
            "min_writer_build": None,
            "running_writer_build": self.writer_build,
            "compatible": False,
            "read_only": True,
            "reason": "database does not exist",
        }
        if not self.path.exists():
            return base
        try:
            with closing(self._connect_read_only()) as connection:
                base["format"] = "sqlite"
                base["schema_version"] = int(connection.execute("PRAGMA user_version").fetchone()[0])
                tables = {
                    row[0]
                    for row in connection.execute(
                        "SELECT name FROM sqlite_master WHERE type = 'table'"
                    )
                }
                if _METADATA_TABLE not in tables:
                    base["reason"] = "controller metadata table is missing"
                    return base
                row = connection.execute(
                    f"SELECT data_model_version, min_writer_build FROM {_METADATA_TABLE} WHERE singleton = 1"
                ).fetchone()
                if row is None:
                    base["reason"] = "controller metadata row is missing"
                    return base
                data_model_version, min_writer_build = row
                base["data_model_version"] = data_model_version
                base["min_writer_build"] = min_writer_build
                if base["schema_version"] != SQLITE_SCHEMA_VERSION:
                    base["reason"] = f"unsupported SQLite schema version {base['schema_version']}"
                elif data_model_version != ReviewState().schema_version:
                    base["reason"] = f"unsupported review-state data model version {data_model_version}"
                elif (
                    isinstance(min_writer_build, bool)
                    or not isinstance(min_writer_build, int)
                    or min_writer_build <= 0
                ):
                    base["reason"] = "minimum writer build metadata is invalid"
                elif self.writer_build < min_writer_build:
                    base["reason"] = f"database requires writer build {min_writer_build}"
                else:
                    base["compatible"] = True
                    base["reason"] = None
                return base
        except (OSError, sqlite3.DatabaseError) as exc:
            base["format"] = "unknown"
            base["reason"] = f"database cannot be inspected read-only: {exc.__class__.__name__}"
            return base

    @classmethod
    def import_legacy_json(
        cls,
        legacy_path: str | os.PathLike[str],
        database_path: str | os.PathLike[str],
        *,
        writer_build: int = WRITER_BUILD,
    ) -> SqliteStateStore:
        """Import validated legacy JSON into a new caller-selected DB path.

        The source is never modified. An existing target (including a symlink)
        is rejected. The imported state is semantically read back before an
        atomic no-clobber link installs the completed database.
        """

        source = Path(legacy_path).expanduser().absolute()
        target = Path(database_path).expanduser().absolute()
        if os.path.lexists(target):
            raise StateError(f"SQLite import target already exists: {target}")
        try:
            with source.open("r", encoding="utf-8") as handle:
                document = json.load(handle)
        except (OSError, json.JSONDecodeError) as exc:
            raise StateError("cannot read legacy review-state JSON") from exc
        if not isinstance(document, Mapping):
            raise StateError("legacy review-state JSON must contain an object")
        imported = ReviewState.from_dict(document)

        target.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
        descriptor, temporary_name = tempfile.mkstemp(
            prefix=f".{target.name}.import-", suffix=".sqlite3", dir=target.parent
        )
        os.close(descriptor)
        temporary = Path(temporary_name)
        temporary.unlink()
        try:
            store = cls(temporary, writer_build=writer_build)
            store.update(lambda _: imported)
            readback = store.load()
            if readback.to_dict() != imported.to_dict():
                raise StateError("SQLite import readback differs from validated legacy state")
            with closing(store._connect_read_only()) as connection:
                integrity = connection.execute("PRAGMA integrity_check").fetchone()
            if integrity is None or integrity[0] != "ok":
                raise StateError("SQLite import failed integrity_check")
            try:
                os.link(temporary, target)
            except FileExistsError as exc:
                raise StateError(f"SQLite import target already exists: {target}") from exc
            return cls(target, writer_build=writer_build)
        finally:
            temporary.unlink(missing_ok=True)
            temporary.with_name(f".{temporary.name}.lock").unlink(missing_ok=True)

    @classmethod
    def migrate_legacy_json(
        cls,
        legacy_path: str | os.PathLike[str],
        database_path: str | os.PathLike[str],
        *,
        writer_build: int = WRITER_BUILD,
    ) -> SqliteStateStore:
        """Validate, import, and atomically fence one legacy JSON state file.

        The source is retained at ``<legacy_path>.migrated``. Its former path
        becomes a directory with a cutover marker. That path shape makes the
        old JSON loader fail closed and makes its atomic ``os.replace`` writer
        unable to replace the directory. The exchange is Linux-only and
        requires ``renameat2(RENAME_EXCHANGE)``; unsupported filesystems fail
        before cutover rather than using a non-atomic two-rename fallback.
        """

        source_input = Path(legacy_path).expanduser().absolute()
        target_input = Path(database_path).expanduser().absolute()
        source = source_input.parent.resolve() / source_input.name
        target = target_input.parent.resolve() / target_input.name
        canonical_target = sqlite_state_path(source)
        if target != canonical_target:
            raise StateError(f"SQLite migration target must be the canonical sibling path: {canonical_target}")
        retained_source = source.with_name(f"{source.name}.migrated")
        if (
            target == source
            or target == retained_source
            or source in target.parents
            or retained_source in target.parents
        ):
            raise StateError("SQLite migration target must be separate from the legacy state path")
        if not source.parent.is_dir():
            raise StateError("legacy review-state directory does not exist")

        database_identity: tuple[int, int] | None = None
        staging_identity: tuple[int, int] | None = None
        marker_bytes: bytes | None = None
        exchanged = False
        marker_path: Path | None = None

        # StateStore.save/update use this same fixed per-directory lock. Keep
        # cooperating legacy writers out until the JSON path has been fenced.
        with _locked(source.with_name(".pr-review-stack.lock")):
            if source.is_symlink() or not source.is_file():
                raise StateError("legacy review-state path must be an existing regular JSON file")
            if os.path.lexists(target):
                raise StateError(f"SQLite migration target already exists: {target}")
            if os.path.lexists(retained_source):
                raise StateError(f"legacy state retention path already exists: {retained_source}")

            original_stat = source.stat(follow_symlinks=False)
            original_identity = (original_stat.st_dev, original_stat.st_ino)
            try:
                original_bytes = source.read_bytes()
                document = json.loads(original_bytes.decode("utf-8"))
            except (OSError, UnicodeDecodeError, json.JSONDecodeError) as exc:
                raise StateError("cannot read legacy review-state JSON for migration") from exc
            if not isinstance(document, Mapping):
                raise StateError("legacy review-state JSON must contain an object")
            imported = ReviewState.from_dict(document)

            store = cls.import_legacy_json(source, target, writer_build=writer_build)
            try:
                database_stat = target.stat(follow_symlinks=False)
                database_identity = (database_stat.st_dev, database_stat.st_ino)

                readback = store.load()
                if readback.to_dict() != imported.to_dict():
                    raise StateError("SQLite migration preflight readback differs from legacy state")
                status = store.status()
                minimum_build = status.get("min_writer_build") if isinstance(status, Mapping) else None
                running_build = status.get("running_writer_build") if isinstance(status, Mapping) else None
                sqlite_schema = status.get("schema_version") if isinstance(status, Mapping) else None
                state_schema = status.get("data_model_version") if isinstance(status, Mapping) else None
                if (
                    not isinstance(status, Mapping)
                    or status.get("compatible") is not True
                    or isinstance(sqlite_schema, bool)
                    or not isinstance(sqlite_schema, int)
                    or sqlite_schema != SQLITE_SCHEMA_VERSION
                    or isinstance(state_schema, bool)
                    or not isinstance(state_schema, int)
                    or state_schema != imported.schema_version
                    or isinstance(minimum_build, bool)
                    or not isinstance(minimum_build, int)
                    or minimum_build <= 0
                    or isinstance(running_build, bool)
                    or not isinstance(running_build, int)
                    or running_build <= 0
                    or running_build < minimum_build
                ):
                    reason = status.get("reason") if isinstance(status, Mapping) else "invalid compatibility status"
                    raise StateError(f"SQLite migration compatibility preflight failed: {reason}")

                current_stat = source.stat(follow_symlinks=False)
                if (
                    (current_stat.st_dev, current_stat.st_ino) != original_identity
                    or source.read_bytes() != original_bytes
                ):
                    raise StateError("legacy review-state JSON changed during migration preflight")

                retained_source.mkdir(mode=0o700)
                staging_stat = retained_source.stat(follow_symlinks=False)
                staging_identity = (staging_stat.st_dev, staging_stat.st_ino)
                marker_path = retained_source / "sqlite-cutover.json"
                marker = {
                    "format": "firemud-pr-review-sqlite-cutover",
                    "cutover_version": CUTOVER_VERSION,
                    "database": str(target),
                    "sqlite_schema_version": SQLITE_SCHEMA_VERSION,
                    "state_schema_version": imported.schema_version,
                    "min_writer_build": minimum_build,
                }
                marker_bytes = (
                    json.dumps(marker, sort_keys=True, separators=(",", ":")) + "\n"
                ).encode("utf-8")
                with marker_path.open("xb") as handle:
                    os.fchmod(handle.fileno(), 0o600)
                    handle.write(marker_bytes)
                    handle.flush()
                    os.fsync(handle.fileno())
                _fsync_directory(retained_source)
                _fsync_directory(source.parent)
                current_stat = source.stat(follow_symlinks=False)
                if (
                    (current_stat.st_dev, current_stat.st_ino) != original_identity
                    or source.read_bytes() != original_bytes
                ):
                    raise StateError("legacy review-state JSON changed before cutover")
                _atomic_exchange(source, retained_source)
                exchanged = True
                cutover_stat = source.stat(follow_symlinks=False)
                retained_stat = retained_source.stat(follow_symlinks=False)
                if (
                    (cutover_stat.st_dev, cutover_stat.st_ino) != staging_identity
                    or (retained_stat.st_dev, retained_stat.st_ino) != original_identity
                    or not source.is_dir()
                    or not retained_source.is_file()
                    or (source / "sqlite-cutover.json").read_bytes() != marker_bytes
                    or retained_source.read_bytes() != original_bytes
                ):
                    raise StateError("SQLite migration cutover postflight did not match its preflight")
                try:
                    _fsync_directory(source.parent)
                except OSError as exc:
                    raise StateError(
                        "SQLite migration cutover is installed but directory durability could not be confirmed"
                    ) from exc
            except BaseException:
                if not exchanged:
                    _remove_staged_cutover(retained_source, staging_identity, marker_path, marker_bytes)
                    _remove_created_database(target, database_identity)
                raise

            return cls(target, writer_build=writer_build)

    @property
    def _lock_path(self) -> Path:
        return self.path.with_name(f".{self.path.name}.lock")

    @contextmanager
    def _exclusive_update_lock(self) -> Iterator[None]:
        descriptor = os.open(self._lock_path, os.O_CREAT | os.O_RDWR, 0o600)
        try:
            os.fchmod(descriptor, 0o600)
            fcntl.flock(descriptor, fcntl.LOCK_EX)
            try:
                yield
            finally:
                fcntl.flock(descriptor, fcntl.LOCK_UN)
        finally:
            os.close(descriptor)

    def _create_empty_file_exclusively(self) -> tuple[int, int] | None:
        if self.path.is_symlink():
            raise StateError("SQLite review-state path must not be a symlink")
        try:
            descriptor = os.open(self.path, os.O_CREAT | os.O_EXCL | os.O_RDWR, 0o600)
        except FileExistsError:
            return None
        identity = os.fstat(descriptor)
        os.close(descriptor)
        return (identity.st_dev, identity.st_ino)

    def _unlink_created_database(self, identity: tuple[int, int]) -> None:
        try:
            current = self.path.stat(follow_symlinks=False)
        except FileNotFoundError:
            return
        if (current.st_dev, current.st_ino) == identity and not self.path.is_symlink():
            self.path.unlink()

    def _connect_read_only(self) -> sqlite3.Connection:
        if self.path.is_symlink():
            raise StateError("SQLite review-state path must not be a symlink")
        uri = f"{self.path.resolve().as_uri()}?mode=ro"
        connection = sqlite3.connect(uri, uri=True, timeout=self.timeout, isolation_level=None)
        connection.execute("PRAGMA query_only = ON")
        return connection

    def _require_compatible(self, connection: sqlite3.Connection) -> None:
        schema_version = int(connection.execute("PRAGMA user_version").fetchone()[0])
        if schema_version != SQLITE_SCHEMA_VERSION:
            raise StateError(f"unsupported SQLite review-state schema version: {schema_version}")
        row = connection.execute(
            f"SELECT data_model_version, min_writer_build FROM {_METADATA_TABLE} WHERE singleton = 1"
        ).fetchone()
        if row is None:
            raise StateError("SQLite review-state metadata is missing")
        data_model_version, min_writer_build = row
        if data_model_version != ReviewState().schema_version:
            raise StateError(f"unsupported review-state data model version: {data_model_version}")
        if (
            isinstance(min_writer_build, bool)
            or not isinstance(min_writer_build, int)
            or min_writer_build <= 0
        ):
            raise StateError("SQLite review-state minimum writer build is invalid")
        if self.writer_build < min_writer_build:
            raise StateError(f"SQLite review state requires writer build {min_writer_build}")

    @staticmethod
    def _initialize(connection: sqlite3.Connection, writer_build: int) -> None:
        connection.execute(
            f"CREATE TABLE {_METADATA_TABLE} ("
            "singleton INTEGER PRIMARY KEY CHECK (singleton = 1), "
            "data_model_version INTEGER NOT NULL, "
            "min_writer_build INTEGER NOT NULL CHECK (min_writer_build > 0)"
            ")"
        )
        connection.execute(
            f"CREATE TABLE {_STATE_TABLE} ("
            "singleton INTEGER PRIMARY KEY CHECK (singleton = 1), "
            "state_json TEXT NOT NULL"
            ")"
        )
        connection.execute(f"PRAGMA user_version = {SQLITE_SCHEMA_VERSION}")
        connection.execute(
            f"INSERT INTO {_METADATA_TABLE} VALUES (1, ?, ?)",
            (ReviewState().schema_version, writer_build),
        )
        connection.execute(
            f"INSERT INTO {_STATE_TABLE} VALUES (1, ?)",
            (
                json.dumps(
                    ReviewState().to_dict(),
                    ensure_ascii=True,
                    sort_keys=True,
                    separators=(",", ":"),
                    allow_nan=False,
                ),
            ),
        )

    @staticmethod
    def _load_from_connection(connection: sqlite3.Connection) -> ReviewState:
        row = connection.execute(f"SELECT state_json FROM {_STATE_TABLE} WHERE singleton = 1").fetchone()
        if row is None or not isinstance(row[0], str):
            raise StateError("SQLite review state is missing its validated state document")
        try:
            document = json.loads(row[0])
        except json.JSONDecodeError as exc:
            raise StateError("SQLite review state contains malformed JSON") from exc
        if not isinstance(document, Mapping):
            raise StateError("SQLite review state document must be an object")
        return ReviewState.from_dict(document)
