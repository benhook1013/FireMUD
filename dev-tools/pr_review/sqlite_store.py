"""Offline SQLite persistence for validated PR-review controller state.

This module is deliberately not wired into the live controller. It provides a
transactional store and an explicit JSON import primitive for a later,
operator-controlled migration.
"""

from __future__ import annotations

import fcntl
import json
import os
import sqlite3
import tempfile
from collections.abc import Callable, Iterator, Mapping
from contextlib import closing, contextmanager
from pathlib import Path
from typing import Any

from .state import ReviewState, StateError

SQLITE_SCHEMA_VERSION = 1
WRITER_BUILD = 1
_METADATA_TABLE = "controller_metadata"
_STATE_TABLE = "review_state"


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
