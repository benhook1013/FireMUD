"""Private, explicitly bootstrapped inter-worker messages in SQLite.

Inbox read and acknowledgement state is message handling metadata. It does not
complete work, wake workers, impose permissions, or participate in review
locking or allocation.
"""

from __future__ import annotations

import math
import os
import re
import sqlite3
import uuid
from collections.abc import Iterator
from contextlib import closing, contextmanager
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from pr_review.sqlite_review_records import _SECRET_PATTERNS
from pr_review.sqlite_store import SqliteStateStore

from .context import worker_alias

INBOX_SCHEMA_VERSION = 1
_MAX_TEXT = 20_000
_MAX_PAGE = 10_000
_TABLES = ("inbox_metadata", "inbox_messages")

INBOX_COLUMNS: dict[str, tuple[str, ...]] = {
    "inbox_metadata": ("singleton", "inbox_schema_version"),
    "inbox_messages": (
        "id", "recipient", "body", "author", "job", "pr", "reply_to", "created_at", "seen_at", "acknowledged_at",
    ),
}

INBOX_INDEXES: dict[str, str] = {
    "inbox_recipient_created_idx": (
        "CREATE INDEX inbox_recipient_created_idx ON inbox_messages(recipient, created_at DESC, id DESC)"
    ),
    "inbox_recipient_unread_idx": (
        "CREATE INDEX inbox_recipient_unread_idx ON inbox_messages(recipient, seen_at, created_at DESC)"
    ),
    "inbox_reply_to_idx": "CREATE INDEX inbox_reply_to_idx ON inbox_messages(reply_to)",
}

INBOX_TEXT_COLUMNS: dict[str, tuple[str, ...]] = {
    "inbox_metadata": (),
    "inbox_messages": (
        "id", "recipient", "body", "author", "job", "reply_to", "created_at", "seen_at", "acknowledged_at",
    ),
}


class InboxError(ValueError):
    """Raised when inbox input, state, or schema compatibility is invalid."""


class InboxNotBootstrapped(InboxError):
    """Raised until the explicit inbox schema bootstrap has completed."""


class InboxSchemaIncompatible(InboxError):
    """Raised when the inbox tables do not match their declared schema."""


class MessageNotFound(InboxError):
    """Raised when an exact message ID or recipient does not match."""


def _now() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z")


def _text(value: Any, label: str, *, maximum: int = 200, allow_empty: bool = False) -> str:
    if not isinstance(value, str) or len(value) > maximum:
        raise InboxError(f"{label} must be text of at most {maximum} characters")
    if not allow_empty and not value.strip():
        raise InboxError(f"{label} must not be empty")
    if any(ord(char) < 0x20 and char not in "\n\r\t" for char in value):
        raise InboxError(f"{label} must not contain control characters")
    if any(pattern.search(value) for pattern in _SECRET_PATTERNS):
        raise InboxError(f"{label} resembles credential or raw secret material")
    return value



def _worker(value, label) -> str:
    selected = _text(value, label, maximum=100)
    if not worker_alias(selected):
        raise InboxError(f"{label} must be a worker alias without surrounding whitespace or control characters")
    return selected

def _limit(value: Any) -> int:
    if isinstance(value, bool) or not isinstance(value, int) or not 1 <= value <= _MAX_PAGE:
        raise InboxError(f"limit must be an integer from 1 through {_MAX_PAGE}")
    return value


def _offset(value: Any) -> int:
    if isinstance(value, bool) or not isinstance(value, int) or not 0 <= value <= 2_147_483_647:
        raise InboxError("offset must be a non-negative integer")
    return value


def _normalize_sql(value: str) -> str:
    return re.sub(r"\s+", " ", value.strip().rstrip(";")).casefold()


class InboxStore:
    """Store private messages in a standalone schema beside other controller data."""

    def __init__(self, path: str | os.PathLike[str], *, timeout: float = 10.0) -> None:
        if isinstance(timeout, bool) or not isinstance(timeout, (int, float)) or not math.isfinite(timeout) or timeout <= 0:
            raise InboxError("timeout must be a positive finite number")
        self.path = Path(path).expanduser().absolute()
        self.timeout = float(timeout)

    def bootstrap(self) -> None:
        """Create the inbox tables explicitly, refusing partial or unknown schemas."""

        if self.path.is_symlink():
            raise InboxSchemaIncompatible("SQLite inbox database path must not be a symlink")
        if self.path.exists() and not self.path.is_file():
            raise InboxSchemaIncompatible("SQLite inbox database path must be a regular file")
        controller = SqliteStateStore(self.path, timeout=self.timeout)
        if not self.path.exists():
            controller.update(lambda state: state)
        elif controller.status().get("compatible") is not True:
            raise InboxSchemaIncompatible("inbox database is not a compatible controller database")
        controller.load()
        try:
            with closing(self._connect(read_only=False)) as connection:
                connection.execute("BEGIN IMMEDIATE")
                existing = self._table_names(connection) & set(_TABLES)
                if existing:
                    if existing != set(_TABLES):
                        raise InboxSchemaIncompatible("inbox schema is partial and cannot be bootstrapped")
                    self.validate(connection)
                    connection.commit()
                    return
                self._create_schema(connection)
                self.validate(connection)
                connection.commit()
        except InboxError:
            raise
        except sqlite3.DatabaseError as exc:
            raise InboxSchemaIncompatible("cannot bootstrap SQLite inbox store") from exc

    @classmethod
    def validate(cls, connection: sqlite3.Connection) -> None:
        """Validate the inbox schema and stored private messages without writing."""

        if not isinstance(connection, sqlite3.Connection):
            raise TypeError("connection must be an sqlite3.Connection")
        tables = cls._table_names(connection)
        missing = set(_TABLES) - tables
        if missing:
            raise InboxNotBootstrapped("inbox schema is incomplete; call bootstrap() explicitly")
        for table, expected in INBOX_COLUMNS.items():
            try:
                actual = tuple(row[1] for row in connection.execute(f'PRAGMA table_info("{table}")'))
            except sqlite3.DatabaseError as exc:
                raise InboxSchemaIncompatible(f"cannot inspect {table} schema") from exc
            if actual != expected:
                raise InboxSchemaIncompatible(f"inbox table {table} has incompatible columns")
        index_rows = {
            name: sql
            for name, sql in connection.execute(
                "SELECT name, sql FROM sqlite_master WHERE type = 'index' AND name IS NOT NULL"
            )
        }
        for name, expected in INBOX_INDEXES.items():
            actual = index_rows.get(name)
            if actual is None or _normalize_sql(actual) != _normalize_sql(expected):
                raise InboxSchemaIncompatible(f"required inbox index {name} is missing or incompatible")
        metadata_rows = connection.execute(
            "SELECT singleton, inbox_schema_version FROM inbox_metadata ORDER BY singleton"
        ).fetchall()
        if len(metadata_rows) != 1 or tuple(metadata_rows[0]) != (1, INBOX_SCHEMA_VERSION):
            raise InboxSchemaIncompatible("inbox schema version is unknown or incompatible")
        for table in _TABLES:
            if connection.execute(f'PRAGMA foreign_key_check("{table}")').fetchone() is not None:
                raise InboxSchemaIncompatible("inbox messages contain a broken reply reference")
        try:
            for row in connection.execute("SELECT * FROM inbox_messages"):
                message = cls._message_dict(row)
                _text(message["id"], "message id", maximum=100)
                _worker(message["recipient"], "recipient")
                _text(message["body"], "message body", maximum=_MAX_TEXT)
                if message["author"] is not None:
                    _worker(message["author"], "author")
                if message["job"] is not None:
                    _text(message["job"], "job", maximum=100)
                if message["pr"] is not None and (
                    isinstance(message["pr"], bool) or not isinstance(message["pr"], int) or message["pr"] <= 0
                ):
                    raise InboxSchemaIncompatible("stored message PR reference is invalid")
                if message["reply_to"] is not None:
                    _text(message["reply_to"], "reply_to", maximum=100)
                    if message["reply_to"] == message["id"]:
                        raise InboxSchemaIncompatible("message cannot reply to itself")
                _text(message["created_at"], "message created_at", maximum=100)
                for field in ("seen_at", "acknowledged_at"):
                    if message[field] is not None:
                        _text(message[field], f"message {field}", maximum=100)
                if message["acknowledged_at"] is not None and message["seen_at"] is None:
                    raise InboxSchemaIncompatible("acknowledged message is not marked seen")
        except InboxError as exc:
            if isinstance(exc, InboxSchemaIncompatible):
                raise
            raise InboxSchemaIncompatible("stored inbox data failed content validation") from exc

    def send(
        self,
        recipient: str,
        body: str,
        *,
        author: str | None = None,
        job: str | None = None,
        pr: int | None = None,
        reply_to: str | None = None,
    ) -> dict[str, Any]:
        """Send one private message; author is descriptive metadata only."""

        selected_recipient = _worker(recipient, "recipient")
        selected_body = _text(body, "message body", maximum=_MAX_TEXT)
        selected_author = None if author is None else _worker(author, "author")
        selected_job = None if job is None else _text(job, "job", maximum=100)
        if pr is not None and (isinstance(pr, bool) or not isinstance(pr, int) or pr <= 0):
            raise InboxError("pr must be a positive integer")
        selected_reply = None if reply_to is None else _text(reply_to, "reply_to", maximum=100)
        message_id = str(uuid.uuid4())
        timestamp = _now()
        with self._write() as connection:
            if selected_reply is not None and connection.execute(
                "SELECT 1 FROM inbox_messages WHERE id = ?", (selected_reply,)
            ).fetchone() is None:
                raise MessageNotFound(f"reply target {selected_reply} was not found")
            connection.execute(
                "INSERT INTO inbox_messages(id, recipient, body, author, job, pr, reply_to, created_at, seen_at, acknowledged_at) "
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, NULL, NULL)",
                (message_id, selected_recipient, selected_body, selected_author, selected_job, pr, selected_reply, timestamp),
            )
            return {
                "id": message_id, "recipient": selected_recipient, "body": selected_body, "author": selected_author,
                "job": selected_job, "pr": pr, "reply_to": selected_reply, "created_at": timestamp,
                "seen_at": None, "acknowledged_at": None,
            }

    def list(
        self,
        recipient: str,
        unread: bool = False,
        limit: int = 50,
        offset: int = 0,
    ) -> list[dict[str, Any]]:
        """List a recipient's private messages newest first with a bounded page."""

        selected_recipient = _worker(recipient, "recipient")
        if not isinstance(unread, bool):
            raise InboxError("unread must be a boolean")
        selected_limit = _limit(limit)
        selected_offset = _offset(offset)
        where = "recipient = ?"
        parameters: list[Any] = [selected_recipient]
        if unread:
            where += " AND seen_at IS NULL"
        with self._read() as connection:
            rows = connection.execute(
                f"SELECT {', '.join(INBOX_COLUMNS['inbox_messages'])} FROM inbox_messages WHERE {where} "
                "ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?",
                (*parameters, selected_limit, selected_offset),
            ).fetchall()
            return [self._message_dict(row) for row in rows]

    def thread(
        self,
        message_id: str,
        limit: int = 50,
        offset: int = 0,
    ) -> list[dict[str, Any]]:
        """Return one reply thread across recipients in chronological pages."""

        return self.thread_page(message_id, limit=limit, offset=offset)["messages"]

    def thread_page(
        self,
        message_id: str,
        limit: int = 50,
        offset: int = 0,
        *,
        focus_id: str | None = None,
    ) -> dict[str, Any]:
        """Return one bounded chronological page, optionally focused on a message.

        This is a read-only projection. It never changes a message's seen or
        acknowledged state.
        """

        selected_id = _text(message_id, "message id", maximum=100)
        selected_limit = _limit(limit)
        selected_offset = _offset(offset)
        selected_focus = None if focus_id is None else _text(focus_id, "focus message id", maximum=100)
        with self._read() as connection:
            root_id = self._thread_root(connection, selected_id)
            if selected_focus is not None:
                focus = connection.execute(
                    "WITH RECURSIVE thread(id) AS ("
                    "SELECT id FROM inbox_messages WHERE id = ? "
                    "UNION "
                    "SELECT message.id FROM inbox_messages AS message "
                    "JOIN thread AS parent ON message.reply_to = parent.id) "
                    "SELECT created_at, rowid FROM inbox_messages "
                    "WHERE id = ? AND id IN (SELECT id FROM thread)",
                    (root_id, selected_focus),
                ).fetchone()
                if focus is None:
                    raise MessageNotFound(
                        f"focus message {selected_focus} is not in the thread containing {selected_id}"
                    )
                preceding = connection.execute(
                    "WITH RECURSIVE thread(id) AS ("
                    "SELECT id FROM inbox_messages WHERE id = ? "
                    "UNION "
                    "SELECT message.id FROM inbox_messages AS message "
                    "JOIN thread AS parent ON message.reply_to = parent.id) "
                    "SELECT COUNT(*) FROM inbox_messages WHERE id IN (SELECT id FROM thread) "
                    "AND (created_at < ? OR (created_at = ? AND rowid < ?))",
                    (root_id, focus[0], focus[0], focus[1]),
                ).fetchone()
                selected_offset = (int(preceding[0]) // selected_limit) * selected_limit
            rows = connection.execute(
                "WITH RECURSIVE thread(id) AS ("
                "SELECT id FROM inbox_messages WHERE id = ? "
                "UNION "
                "SELECT message.id FROM inbox_messages AS message "
                "JOIN thread AS parent ON message.reply_to = parent.id) "
                f"SELECT {', '.join(INBOX_COLUMNS['inbox_messages'])} FROM inbox_messages "
                "WHERE id IN (SELECT id FROM thread) ORDER BY created_at ASC, rowid ASC LIMIT ? OFFSET ?",
                (root_id, selected_limit, selected_offset),
            ).fetchall()
            return {"messages": [self._message_dict(row) for row in rows], "offset": selected_offset}

    def read(self, message_id: str, recipient: str | None = None) -> dict[str, Any]:
        """Mark one message seen and return it; recipient is an optional selector."""

        return self._mark(message_id, recipient=recipient, acknowledge=False)

    def ack(self, message_id: str, recipient: str | None = None) -> dict[str, Any]:
        """Mark one message acknowledged and seen, idempotently."""

        return self._mark(message_id, recipient=recipient, acknowledge=True)

    def _mark(self, message_id: str, *, recipient: str | None, acknowledge: bool) -> dict[str, Any]:
        selected_id = _text(message_id, "message id", maximum=100)
        selected_recipient = None if recipient is None else _worker(recipient, "recipient")
        timestamp = _now()
        with self._write() as connection:
            row = self._message(connection, selected_id, selected_recipient)
            if row is None:
                raise MessageNotFound(f"message {selected_id} was not found")
            if acknowledge:
                connection.execute(
                    "UPDATE inbox_messages SET seen_at = COALESCE(seen_at, ?), "
                    "acknowledged_at = COALESCE(acknowledged_at, ?) WHERE id = ?",
                    (timestamp, timestamp, selected_id),
                )
            else:
                connection.execute(
                    "UPDATE inbox_messages SET seen_at = COALESCE(seen_at, ?) WHERE id = ?",
                    (timestamp, selected_id),
                )
            return self._message_dict(connection.execute(
                f"SELECT {', '.join(INBOX_COLUMNS['inbox_messages'])} FROM inbox_messages WHERE id = ?",
                (selected_id,),
            ).fetchone())

    def unread_count(self, worker: str) -> int:
        """Return an unread count without creating missing inbox tables."""

        selected_worker = _worker(worker, "worker")
        if not self.path.exists() or self.path.is_symlink() or not self.path.is_file():
            return 0
        try:
            with closing(self._connect(read_only=True)) as connection:
                present = self._table_names(connection) & set(_TABLES)
                if not present:
                    return 0
                if present != set(_TABLES):
                    raise InboxSchemaIncompatible("inbox schema is partial")
                self._require_compatible(connection)
                row = connection.execute(
                    "SELECT COUNT(*) FROM inbox_messages WHERE recipient = ? AND seen_at IS NULL",
                    (selected_worker,),
                ).fetchone()
                return int(row[0])
        except InboxError:
            raise
        except sqlite3.DatabaseError as exc:
            raise InboxSchemaIncompatible("cannot read inbox unread count") from exc

    @staticmethod
    def _message_dict(row: sqlite3.Row | tuple[Any, ...]) -> dict[str, Any]:
        if isinstance(row, sqlite3.Row):
            return {column: row[column] for column in INBOX_COLUMNS["inbox_messages"]}
        return dict(zip(INBOX_COLUMNS["inbox_messages"], row, strict=True))

    @staticmethod
    def _message(connection: sqlite3.Connection, message_id: str, recipient: str | None = None) -> sqlite3.Row | None:
        sql = f"SELECT {', '.join(INBOX_COLUMNS['inbox_messages'])} FROM inbox_messages WHERE id = ?"
        parameters: tuple[Any, ...] = (message_id,)
        if recipient is not None:
            sql += " AND recipient = ?"
            parameters = (*parameters, recipient)
        return connection.execute(sql, parameters).fetchone()

    @staticmethod
    def _thread_root(connection: sqlite3.Connection, message_id: str) -> str:
        if connection.execute("SELECT 1 FROM inbox_messages WHERE id = ?", (message_id,)).fetchone() is None:
            raise MessageNotFound(f"message {message_id} was not found")
        root = connection.execute(
            "WITH RECURSIVE ancestors(id, reply_to) AS ("
            "SELECT id, reply_to FROM inbox_messages WHERE id = ? "
            "UNION "
            "SELECT parent.id, parent.reply_to FROM inbox_messages AS parent "
            "JOIN ancestors ON parent.id = ancestors.reply_to) "
            "SELECT id FROM ancestors WHERE reply_to IS NULL",
            (message_id,),
        ).fetchone()
        if root is None:
            raise InboxSchemaIncompatible("message reply ancestry has no root")
        return str(root[0])

    @staticmethod
    def _table_names(connection: sqlite3.Connection) -> set[str]:
        return {row[0] for row in connection.execute("SELECT name FROM sqlite_master WHERE type = 'table'")}

    @staticmethod
    def _create_schema(connection: sqlite3.Connection) -> None:
        connection.execute(
            "CREATE TABLE inbox_metadata (singleton INTEGER PRIMARY KEY CHECK (singleton = 1), "
            "inbox_schema_version INTEGER NOT NULL)"
        )
        connection.execute(
            "CREATE TABLE inbox_messages ("
            "id TEXT PRIMARY KEY, recipient TEXT NOT NULL, body TEXT NOT NULL, author TEXT, job TEXT, pr INTEGER, "
            "reply_to TEXT REFERENCES inbox_messages(id), created_at TEXT NOT NULL, seen_at TEXT, acknowledged_at TEXT, "
            "CHECK (pr IS NULL OR pr > 0), CHECK (reply_to IS NULL OR reply_to <> id), "
            "CHECK (acknowledged_at IS NULL OR seen_at IS NOT NULL))"
        )
        for statement in INBOX_INDEXES.values():
            connection.execute(statement)
        connection.execute(
            "INSERT INTO inbox_metadata(singleton, inbox_schema_version) VALUES (1, ?)",
            (INBOX_SCHEMA_VERSION,),
        )

    def _require_compatible(self, connection: sqlite3.Connection) -> None:
        # Reuse only the base controller metadata guard: no job schema, status,
        # review record, or allocation is consulted by the inbox.
        from .jobs import JobStore
        JobStore._require_controller_compatible_connection(connection)
        present = self._table_names(connection) & set(_TABLES)
        if not present:
            raise InboxNotBootstrapped("inbox schema is not bootstrapped; call bootstrap() explicitly")
        if present != set(_TABLES):
            raise InboxSchemaIncompatible("inbox schema is partial")
        row = connection.execute(
            "SELECT inbox_schema_version FROM inbox_metadata WHERE singleton = 1"
        ).fetchone()
        if row is None or row[0] != INBOX_SCHEMA_VERSION:
            raise InboxSchemaIncompatible("inbox schema requires an unsupported version")

    @contextmanager
    def _read(self) -> Iterator[sqlite3.Connection]:
        if not self.path.exists():
            raise InboxNotBootstrapped("inbox schema is not bootstrapped; call bootstrap() explicitly")
        connection = self._connect(read_only=True)
        try:
            connection.execute("BEGIN")
            self._require_compatible(connection)
            yield connection
        except InboxError:
            raise
        except sqlite3.DatabaseError as exc:
            raise InboxSchemaIncompatible("SQLite inbox read failed") from exc
        finally:
            connection.close()

    @contextmanager
    def _write(self) -> Iterator[sqlite3.Connection]:
        if not self.path.exists():
            raise InboxNotBootstrapped("inbox schema is not bootstrapped; call bootstrap() explicitly")
        connection = self._connect(read_only=False)
        try:
            connection.execute("BEGIN IMMEDIATE")
            self._require_compatible(connection)
            yield connection
            connection.commit()
        except InboxError:
            if connection.in_transaction:
                connection.rollback()
            raise
        except sqlite3.DatabaseError as exc:
            if connection.in_transaction:
                connection.rollback()
            raise InboxSchemaIncompatible("SQLite inbox write failed") from exc
        finally:
            connection.close()

    def _connect(self, *, read_only: bool) -> sqlite3.Connection:
        if self.path.is_symlink():
            raise InboxSchemaIncompatible("SQLite inbox database path must not be a symlink")
        if read_only:
            uri = f"{self.path.as_uri()}?mode=ro"
            connection = sqlite3.connect(uri, uri=True, timeout=self.timeout, isolation_level=None)
        else:
            connection = sqlite3.connect(self.path, timeout=self.timeout, isolation_level=None)
        connection.row_factory = sqlite3.Row
        connection.execute("PRAGMA foreign_keys = ON")
        connection.execute(f"PRAGMA busy_timeout = {int(self.timeout * 1000)}")
        return connection
