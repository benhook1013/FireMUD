"""Independent, bounded job state stored beside the FireMUD review state.

The job schema is explicitly bootstrapped and owns only ``job_*`` tables. It
never edits review state, review records, the controller writer fence, or the
review lock. List reads select public summaries only; full Markdown briefs
and revision snapshots are loaded by explicit job/history reads.
"""

from __future__ import annotations

import difflib
import hashlib
import json
import math
import os
import re
import sqlite3
import uuid
from collections.abc import Iterator, Mapping, Sequence
from contextlib import closing, contextmanager
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from pr_review.sqlite_store import SQLITE_SCHEMA_VERSION, WRITER_BUILD, SqliteStateStore
from pr_review.state import ReviewState, StateError

from .context import worker_alias

JOBS_SCHEMA_VERSION = 2
JOB_WORKERS = ("Gameplay", "General", "Document", "Overseer")
JOB_STATUSES = ("active", "parked", "blocked", "completed")
NOTE_STATUSES = ("pending", "consumed", "dismissed")
NOTE_KINDS = ("reminder", "source", "instruction")
CHECKLIST_ACTIONS = ("add", "change", "done", "reopen")

_JOB_METADATA = "job_metadata"
_JOB_TABLES = (
    _JOB_METADATA,
    "jobs",
    "job_revisions",
    "job_briefs",
    "job_updates",
    "job_checkpoints",
    "job_notes",
    "job_note_revisions",
    "job_worker_state",
    "job_worker_state_history",
    "job_imports",
)

# Plain schema inventories are imported by the backup validator. Keep them
# data-only so backup code never has to infer job columns from live objects.
JOB_COLUMNS: dict[str, tuple[str, ...]] = {
    "job_metadata": ("singleton", "jobs_schema_version", "controller_schema_version", "controller_data_model_version"),
    "jobs": (
        "id", "name", "worker", "workstream_id", "title", "status", "is_primary", "revision", "brief_revision",
        "summary", "progress", "blocker", "checklist_json", "chat_id", "created_at", "updated_at",
    ),
    "job_revisions": ("job_id", "revision", "brief_revision", "created_at", "state_json"),
    "job_briefs": ("job_id", "brief_revision", "created_at", "brief"),
    "job_updates": ("sequence", "job_id", "created_at", "kind", "body"),
    "job_checkpoints": (
        "sequence", "job_id", "created_at", "done", "next_steps", "blocker", "pointers_json",
    ),
    "job_notes": (
        "id", "body", "worker", "job", "phase", "kind", "status", "dismissal_reason", "revision", "created_at", "updated_at",
    ),
    "job_note_revisions": ("note_id", "revision", "created_at", "state_json"),
    "job_worker_state": ("worker", "paused", "reason", "updated_at"),
    "job_worker_state_history": ("sequence", "worker", "created_at", "paused", "reason"),
    "job_imports": ("manifest_fingerprint", "created_at", "manifest_json", "source_contents_json", "provenance_json"),
}

JOB_INDEXES: dict[str, str] = {
    "jobs_worker_primary_idx": "CREATE UNIQUE INDEX jobs_worker_primary_idx ON jobs(worker) WHERE is_primary = 1",
    "jobs_worker_status_name_idx": "CREATE INDEX jobs_worker_status_name_idx ON jobs(worker, status, name)",
    "jobs_workstream_status_idx": "CREATE INDEX jobs_workstream_status_idx ON jobs(workstream_id, status, updated_at DESC)",
    "jobs_status_updated_idx": "CREATE INDEX jobs_status_updated_idx ON jobs(status, updated_at DESC)",
    "job_revisions_created_idx": "CREATE INDEX job_revisions_created_idx ON job_revisions(job_id, created_at DESC, revision DESC)",
    "job_updates_sequence_idx": "CREATE INDEX job_updates_sequence_idx ON job_updates(job_id, sequence DESC)",
    "job_checkpoints_sequence_idx": "CREATE INDEX job_checkpoints_sequence_idx ON job_checkpoints(job_id, sequence DESC)",
    "job_notes_worker_status_idx": "CREATE INDEX job_notes_worker_status_idx ON job_notes(worker, status, created_at DESC)",
    "job_notes_job_status_idx": "CREATE INDEX job_notes_job_status_idx ON job_notes(job, status, created_at DESC)",
    "job_notes_phase_status_idx": "CREATE INDEX job_notes_phase_status_idx ON job_notes(phase, status, created_at DESC)",
    "job_note_revisions_created_idx": "CREATE INDEX job_note_revisions_created_idx ON job_note_revisions(note_id, revision DESC)",
    "job_note_revisions_job_created_idx": "CREATE INDEX job_note_revisions_job_created_idx ON job_note_revisions(json_extract(state_json, '$.job'), created_at DESC)",
    "job_worker_state_history_idx": "CREATE INDEX job_worker_state_history_idx ON job_worker_state_history(worker, sequence DESC)",
}

JOB_TEXT_COLUMNS: dict[str, tuple[str, ...]] = {
    "job_metadata": (),
    "jobs": ("id", "name", "worker", "workstream_id", "title", "status", "summary", "progress", "blocker", "checklist_json", "chat_id", "created_at", "updated_at"),
    "job_revisions": ("job_id", "created_at", "state_json"),
    "job_briefs": ("job_id", "created_at", "brief"),
    "job_updates": ("job_id", "created_at", "kind", "body"),
    "job_checkpoints": ("job_id", "created_at", "done", "next_steps", "blocker", "pointers_json"),
    "job_notes": ("id", "body", "worker", "job", "phase", "kind", "status", "dismissal_reason", "created_at", "updated_at"),
    "job_note_revisions": ("note_id", "created_at", "state_json"),
    "job_worker_state": ("worker", "reason", "updated_at"),
    "job_worker_state_history": ("worker", "created_at", "reason"),
    "job_imports": ("manifest_fingerprint", "created_at", "manifest_json", "source_contents_json", "provenance_json"),
}

_SECRET_PATTERNS = (
    re.compile(r"-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----", re.IGNORECASE),
    re.compile(r"\b(?:gh[pousr]_[A-Za-z0-9_]{20,}|github_pat_[A-Za-z0-9_]{20,})\b"),
    re.compile(r"\bAKIA[0-9A-Z]{16}\b"),
    re.compile(r"\bBearer\s+\S+", re.IGNORECASE),
    re.compile(r"\b(?:password|passwd|secret|api[_-]?key|access[_-]?token)\s*[:=]\s*\S+", re.IGNORECASE),
    re.compile(r"\beyJ[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\b"),
)
_NAME_PATTERN = re.compile(r"^[a-z0-9](?:[a-z0-9-]{0,78}[a-z0-9])?$")
_MAX_BRIEF = 200_000
_MAX_SOURCE_TOTAL = 10_000_000
_MAX_PAGE = 10_000
_MAX_LANE_JOBS = 50
_LIST_COLUMNS = (
    "id, name, worker, workstream_id, title, status, is_primary, revision, summary, progress, blocker, "
    "checklist_json, chat_id, created_at, updated_at"
)
_LIST_COLUMN_NAMES = (
    "id", "name", "worker", "workstream_id", "title", "status", "is_primary", "revision", "summary", "progress", "blocker",
    "checklist_json", "chat_id", "created_at", "updated_at",
)
_REVISION_SNAPSHOT_FIELDS = (
    "id", "name", "worker", "workstream_id", "title", "status", "primary", "summary", "progress", "blocker",
    "checklist", "chat_id",
)
_REVISION_FIELDS = frozenset(_REVISION_SNAPSHOT_FIELDS)
_REVISION_EDIT_FIELDS = frozenset(
    {"title", "worker", "workstream_id", "chat_id", "status", "primary", "brief", "summary", "progress", "blocker", "checklist"}
)
_NOTE_SNAPSHOT_FIELDS = ("id", "body", "worker", "job", "phase", "kind", "status", "dismissal_reason")
_POINTER_FIELDS = frozenset({"branch", "worktree", "pr", "source", "proof"})
_MANIFEST_FIELDS = frozenset({"version", "provenance", "jobs", "notes"})
_MANIFEST_JOB_FIELDS = frozenset(
    {"name", "worker", "workstream_id", "title", "brief_file", "status", "primary", "chat_id", "summary", "progress", "blocker", "checklist", "source_files"}
)
_MANIFEST_NOTE_FIELDS = frozenset({"body", "worker", "job", "phase", "kind"})


class JobError(ValueError):
    """Raised when job input, state, or SQLite compatibility is invalid."""


class JobNotFound(JobError):
    """Raised when an exact job or note identifier cannot be found."""


class RevisionConflict(JobError):
    """Raised when a current-state edit uses a stale expected revision."""


class JobsNotBootstrapped(JobError):
    """Raised until the explicit job-schema bootstrap has completed."""


class JobsSchemaIncompatible(JobError):
    """Raised when the controller or job schema cannot safely be used."""


def _now() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z")


def _reject_json_constant(value: str) -> None:
    raise ValueError(f"non-finite JSON number {value} is not allowed")


def _json(value: Any) -> str:
    try:
        return json.dumps(value, ensure_ascii=True, sort_keys=True, separators=(",", ":"), allow_nan=False)
    except (TypeError, ValueError) as exc:
        raise JobError("value is not valid JSON data") from exc


def _loads(value: str, label: str) -> Any:
    try:
        return json.loads(value, parse_constant=_reject_json_constant)
    except (json.JSONDecodeError, ValueError) as exc:
        raise JobsSchemaIncompatible(f"stored {label} is malformed JSON") from exc


def _text(value: Any, label: str, *, maximum: int, allow_empty: bool = False) -> str:
    if not isinstance(value, str) or len(value) > maximum:
        raise JobError(f"{label} must be text of at most {maximum} characters")
    if not allow_empty and not value.strip():
        raise JobError(f"{label} must not be empty")
    if any(ord(char) < 0x20 and char not in "\n\r\t" for char in value):
        raise JobError(f"{label} must not contain control characters")
    if any(pattern.search(value) for pattern in _SECRET_PATTERNS):
        raise JobError(f"{label} resembles credential or raw secret material")
    return value


def _slug(value: Any) -> str:
    selected = _text(value, "name", maximum=80)
    if not _NAME_PATTERN.fullmatch(selected):
        raise JobError("name must be a lowercase friendly slug containing letters, digits, and hyphens")
    return selected


def _worker(value: Any) -> str:
    selected = _text(value, "worker", maximum=100)
    if not worker_alias(selected):
        raise JobError("worker alias must not have surrounding whitespace or control characters")
    return selected


def _status(value: Any) -> str:
    if not isinstance(value, str) or value not in JOB_STATUSES:
        raise JobError(f"status must be one of {', '.join(JOB_STATUSES)}")
    return value


def _positive_revision(value: Any, label: str = "expected_revision") -> int:
    if isinstance(value, bool) or not isinstance(value, int) or value <= 0:
        raise JobError(f"{label} must be a positive integer")
    return value


def _optional_chat_id(value: Any) -> str | None:
    if value is None:
        return None
    return _text(value, "chat_id", maximum=500)


def _optional_workstream_id(value: Any) -> str | None:
    if value is None:
        return None
    return _text(value, "workstream_id", maximum=100)


def _checklist(value: Any, *, allow_missing_ids: bool = False) -> list[dict[str, Any]]:
    if isinstance(value, (str, bytes)) or not isinstance(value, Sequence):
        raise JobError("checklist must be a list of {id, text, done} objects")
    if len(value) > 200:
        raise JobError("checklist may contain at most 200 items")
    result: list[dict[str, Any]] = []
    seen: set[str] = set()
    for item in value:
        if not isinstance(item, Mapping):
            raise JobError("each checklist item must be an object")
        unknown = set(item) - {"id", "text", "done"}
        if unknown:
            raise JobError(f"unknown checklist fields: {', '.join(sorted(unknown))}")
        item_id = item.get("id")
        if item_id is None and allow_missing_ids:
            item_id = str(uuid.uuid4())
        item_id = _text(item_id, "checklist item id", maximum=100)
        if item_id in seen:
            raise JobError("checklist item ids must be unique")
        seen.add(item_id)
        text = _text(item.get("text"), "checklist item text", maximum=1000)
        done = item.get("done", False)
        if not isinstance(done, bool):
            raise JobError("checklist item done must be a boolean")
        result.append({"id": item_id, "text": text, "done": done})
    return result


def _name_filter(value: Any) -> str:
    return _text(value, "search", maximum=200)


def _snapshot(row: Mapping[str, Any]) -> dict[str, Any]:
    checklist = _loads(row["checklist_json"], "job checklist")
    if not isinstance(checklist, list):
        raise JobsSchemaIncompatible("stored job checklist is not a list")
    return {
        "id": row["id"],
        "name": row["name"],
        "worker": row["worker"],
        "workstream_id": row["workstream_id"],
        "title": row["title"],
        "status": row["status"],
        "primary": bool(row["is_primary"]),
        "summary": row["summary"],
        "progress": row["progress"],
        "blocker": row["blocker"],
        "checklist": checklist,
        "chat_id": row["chat_id"],
    }


def _validate_snapshot(snapshot: Mapping[str, Any]) -> None:
    if set(snapshot) != _REVISION_FIELDS:
        raise JobsSchemaIncompatible("stored job snapshot has an invalid shape")
    _text(snapshot["id"], "job id", maximum=100)
    _slug(snapshot["name"])
    _worker(snapshot["worker"])
    _optional_workstream_id(snapshot["workstream_id"])
    _text(snapshot["title"], "title", maximum=300)
    status = _status(snapshot["status"])
    if not isinstance(snapshot["primary"], bool):
        raise JobsSchemaIncompatible("stored job primary value is invalid")
    if snapshot["primary"] and status in {"parked", "completed"}:
        raise JobsSchemaIncompatible("parked or completed job is marked primary")
    _text(snapshot["summary"], "summary", maximum=2000, allow_empty=True)
    _text(snapshot["progress"], "progress", maximum=2000, allow_empty=True)
    _text(snapshot["blocker"], "blocker", maximum=2000, allow_empty=True)
    _checklist(snapshot["checklist"])
    _optional_chat_id(snapshot["chat_id"])


def _public_row(row: Mapping[str, Any]) -> dict[str, Any]:
    return {
        "id": row["id"],
        "name": row["name"],
        "worker": row["worker"],
        "workstream_id": row["workstream_id"],
        "title": row["title"],
        "status": row["status"],
        "primary": bool(row["is_primary"]),
        "revision": row["revision"],
        "summary": row["summary"],
        "progress": row["progress"],
        "blocker": row["blocker"],
        "checklist": _loads(row["checklist_json"], "job checklist"),
        "chat_id": row["chat_id"],
        "created_at": row["created_at"],
        "updated_at": row["updated_at"],
    }


def _note_snapshot(row: Mapping[str, Any]) -> dict[str, Any]:
    return {field: row[field] for field in _NOTE_SNAPSHOT_FIELDS}


def _validate_note_snapshot(snapshot: Mapping[str, Any]) -> None:
    if set(snapshot) != set(_NOTE_SNAPSHOT_FIELDS):
        raise JobsSchemaIncompatible("stored note revision has an invalid shape")
    _text(snapshot["id"], "note id", maximum=100)
    _text(snapshot["body"], "note body", maximum=20_000)
    if snapshot["worker"] is not None:
        _worker(snapshot["worker"])
    if snapshot["job"] is not None:
        _text(snapshot["job"], "note job", maximum=100)
    if snapshot["phase"] is not None:
        _text(snapshot["phase"], "note phase", maximum=200)
    if snapshot["worker"] is None and snapshot["job"] is None and snapshot["phase"] is None:
        raise JobsSchemaIncompatible("stored note revision has no scope")
    if snapshot["kind"] not in NOTE_KINDS or snapshot["status"] not in NOTE_STATUSES:
        raise JobsSchemaIncompatible("stored note revision has an invalid kind or status")
    if snapshot["status"] == "dismissed":
        _text(snapshot["dismissal_reason"], "dismissal reason", maximum=2000)
    elif snapshot["dismissal_reason"] != "":
        raise JobsSchemaIncompatible("non-dismissed note revision has a dismissal reason")


class JobStore:
    """Persist jobs, private briefs, updates, checkpoints, and deferred notes.

    Construction is side-effect free. Call :meth:`bootstrap` explicitly on a
    compatible controller database before using read or write operations.
    """

    def __init__(
        self,
        path: str | os.PathLike[str],
        *,
        writer_build: int = WRITER_BUILD,
        timeout: float = 10.0,
    ) -> None:
        if isinstance(writer_build, bool) or not isinstance(writer_build, int) or writer_build <= 0:
            raise JobError("writer_build must be a positive integer")
        if isinstance(timeout, bool) or not isinstance(timeout, (int, float)) or not math.isfinite(timeout) or timeout <= 0:
            raise JobError("timeout must be a positive finite number")
        self.path = Path(path).expanduser().absolute()
        self.writer_build = writer_build
        self.timeout = float(timeout)

    def bootstrap(self) -> None:
        """Create only the job tables after validating controller state.

        A missing database is initialized through ``SqliteStateStore`` only
        from this explicit call. Existing database review state is read and
        compatibility-checked, then left byte-for-byte untouched at row level.
        """

        if self.path.is_symlink():
            raise JobsSchemaIncompatible("SQLite controller database path must not be a symlink")
        if self.path.exists() and not self.path.is_file():
            raise JobsSchemaIncompatible("SQLite controller database path must be a regular file")
        controller = SqliteStateStore(self.path, writer_build=self.writer_build, timeout=self.timeout)
        if not self.path.exists():
            try:
                controller.update(lambda state: state)
            except StateError as exc:
                raise JobsSchemaIncompatible("cannot initialize a new compatible controller database") from exc
        else:
            status = controller.status()
            if status.get("compatible") is not True:
                raise JobsSchemaIncompatible(f"controller SQLite database is incompatible: {status.get('reason')}")
            try:
                controller.load()
            except StateError as exc:
                raise JobsSchemaIncompatible("controller SQLite review state cannot be validated") from exc

        try:
            with closing(self._connect(read_only=False)) as connection:
                connection.execute("BEGIN IMMEDIATE")
                self._require_controller_compatible(connection)
                tables = self._table_names(connection)
                present = tables & set(_JOB_TABLES)
                if present:
                    if present != set(_JOB_TABLES):
                        raise JobsSchemaIncompatible("job schema is partial and cannot be bootstrapped")
                    index_sql = JOB_INDEXES["job_note_revisions_job_created_idx"]
                    connection.execute(index_sql.replace("CREATE INDEX ", "CREATE INDEX IF NOT EXISTS ", 1))
                    self.validate(connection)
                    connection.commit()
                    return
                self._create_schema(connection)
                self.validate(connection)
                connection.commit()
        except JobError:
            raise
        except sqlite3.DatabaseError as exc:
            raise JobsSchemaIncompatible("cannot bootstrap SQLite job store") from exc

    @classmethod
    def validate(cls, connection: sqlite3.Connection) -> None:
        """Validate controller and job schema on an already-open connection.

        This method is read-only and is intended for backup/restore validation.
        """

        if not isinstance(connection, sqlite3.Connection):
            raise TypeError("connection must be an sqlite3.Connection")
        cls._require_controller_compatible_connection(connection)
        tables = cls._table_names(connection)
        missing = set(_JOB_TABLES) - tables
        if missing:
            raise JobsNotBootstrapped("job schema is incomplete; call bootstrap() explicitly")
        for table, expected in JOB_COLUMNS.items():
            try:
                actual = tuple(row[1] for row in connection.execute(f'PRAGMA table_info("{table}")'))
            except sqlite3.DatabaseError as exc:
                raise JobsSchemaIncompatible(f"cannot inspect {table} schema") from exc
            if actual != expected:
                raise JobsSchemaIncompatible(f"job table {table} has incompatible columns")
        index_rows = {
            name: sql
            for name, sql in connection.execute(
                "SELECT name, sql FROM sqlite_master WHERE type = 'index' AND name IS NOT NULL"
            )
        }
        for name, expected_sql in JOB_INDEXES.items():
            actual_sql = index_rows.get(name)
            if actual_sql is None or _normalize_sql(actual_sql) != _normalize_sql(expected_sql):
                raise JobsSchemaIncompatible(f"required job index {name} is missing or incompatible")
        row = connection.execute(
            "SELECT jobs_schema_version, controller_schema_version, controller_data_model_version "
            "FROM job_metadata WHERE singleton = 1"
        ).fetchone()
        if row is None:
            raise JobsSchemaIncompatible("job metadata row is missing")
        if tuple(row) != (JOBS_SCHEMA_VERSION, SQLITE_SCHEMA_VERSION, ReviewState().schema_version):
            raise JobsSchemaIncompatible("job metadata records incompatible schema versions")
        try:
            state_row = connection.execute("SELECT state_json FROM review_state WHERE singleton = 1").fetchone()
            if state_row is None:
                raise JobsSchemaIncompatible("controller review state row is missing")
            state_document = _loads(state_row[0], "controller review state")
            if not isinstance(state_document, Mapping):
                raise JobsSchemaIncompatible("controller review state is not an object")
            ReviewState.from_dict(state_document)
        except StateError as exc:
            raise JobsSchemaIncompatible("controller review state is invalid") from exc
        invalid_primary = connection.execute(
            "SELECT id FROM jobs WHERE (is_primary NOT IN (0, 1)) "
            "OR (is_primary = 1 AND status IN ('parked', 'completed')) LIMIT 1"
        ).fetchone()
        if invalid_primary is not None:
            raise JobsSchemaIncompatible("job primary designation is inconsistent with job status")
        invalid_jobs = connection.execute(
            "SELECT j.id FROM jobs AS j LEFT JOIN job_briefs AS b "
            "ON b.job_id = j.id AND b.brief_revision = j.brief_revision "
            "LEFT JOIN job_revisions AS r ON r.job_id = j.id AND r.revision = j.revision "
            "WHERE b.job_id IS NULL OR r.job_id IS NULL LIMIT 1"
        ).fetchone()
        if invalid_jobs is not None:
            raise JobsSchemaIncompatible("job current revision or brief link is missing")
        orphan_revision = connection.execute(
            "SELECT r.job_id FROM job_revisions AS r LEFT JOIN job_briefs AS b "
            "ON b.job_id = r.job_id AND b.brief_revision = r.brief_revision "
            "WHERE b.job_id IS NULL LIMIT 1"
        ).fetchone()
        if orphan_revision is not None:
            raise JobsSchemaIncompatible("job history contains a missing brief version")
        incomplete_revisions = connection.execute(
            "SELECT job_id, COUNT(*), MIN(revision), MAX(revision), COUNT(DISTINCT revision) "
            "FROM job_revisions GROUP BY job_id HAVING MIN(revision) <> 1 OR COUNT(*) <> MAX(revision) "
            "OR COUNT(DISTINCT revision) <> COUNT(*) LIMIT 1"
        ).fetchone()
        if incomplete_revisions is not None:
            raise JobsSchemaIncompatible("job revision history contains a gap")
        incomplete_briefs = connection.execute(
            "SELECT job_id, COUNT(*), MIN(brief_revision), MAX(brief_revision), COUNT(DISTINCT brief_revision) "
            "FROM job_briefs GROUP BY job_id HAVING MIN(brief_revision) <> 1 "
            "OR COUNT(*) <> MAX(brief_revision) OR COUNT(DISTINCT brief_revision) <> COUNT(*) LIMIT 1"
        ).fetchone()
        if incomplete_briefs is not None:
            raise JobsSchemaIncompatible("job brief history contains a gap")
        orphan_brief = connection.execute(
            "SELECT b.job_id FROM job_briefs AS b LEFT JOIN job_revisions AS r "
            "ON r.job_id = b.job_id AND r.brief_revision = b.brief_revision "
            "WHERE r.job_id IS NULL LIMIT 1"
        ).fetchone()
        if orphan_brief is not None:
            raise JobsSchemaIncompatible("job brief history contains an unreferenced version")
        previous_brief_revision: dict[str, int] = {}
        for revision_row in connection.execute(
            "SELECT job_id, revision, brief_revision FROM job_revisions ORDER BY job_id, revision"
        ):
            job_id, _, brief_revision = revision_row
            previous = previous_brief_revision.get(job_id)
            if (previous is None and brief_revision != 1) or (
                previous is not None and brief_revision not in {previous, previous + 1}
            ):
                raise JobsSchemaIncompatible("job revision history has an inconsistent brief version")
            previous_brief_revision[job_id] = brief_revision
        for table in _JOB_TABLES:
            foreign_key_failure = connection.execute(f"PRAGMA foreign_key_check(\"{table}\")").fetchone()
            if foreign_key_failure is not None:
                raise JobsSchemaIncompatible("job records contain a broken foreign-key reference")
        try:
            job_ids = {row[0] for row in connection.execute("SELECT id FROM jobs")}
            for row in connection.execute("SELECT * FROM jobs"):
                job = _row_dict(row, JOB_COLUMNS["jobs"])
                current_snapshot = _snapshot(job)
                _validate_snapshot(current_snapshot)
                _text(job["created_at"], "job created_at", maximum=100)
                _text(job["updated_at"], "job updated_at", maximum=100)
                _text(JobStore._current_brief_for_validation(connection, job["id"], job["brief_revision"]), "job brief", maximum=_MAX_BRIEF, allow_empty=True)
                latest_revision = connection.execute(
                    "SELECT revision, brief_revision, state_json FROM job_revisions "
                    "WHERE job_id = ? ORDER BY revision DESC LIMIT 1",
                    (job["id"],),
                ).fetchone()
                if latest_revision is None or latest_revision[0] != job["revision"]:
                    raise JobsSchemaIncompatible("current job revision is not the latest history revision")
                if latest_revision[1] != job["brief_revision"] or _loads(
                    latest_revision[2], "job revision"
                ) != current_snapshot:
                    raise JobsSchemaIncompatible("current job snapshot is inconsistent with its latest history")
            for row in connection.execute("SELECT * FROM job_revisions"):
                revision = _row_dict(row, JOB_COLUMNS["job_revisions"])
                snapshot = _loads(revision["state_json"], "job revision")
                if not isinstance(snapshot, Mapping) or set(snapshot) != _REVISION_FIELDS:
                    raise JobsSchemaIncompatible("stored job revision has an invalid shape")
                if snapshot["id"] != revision["job_id"]:
                    raise JobsSchemaIncompatible("stored job revision identifies another job")
                _validate_snapshot(snapshot)
            for row in connection.execute("SELECT * FROM job_briefs"):
                brief = _row_dict(row, JOB_COLUMNS["job_briefs"])
                _text(brief["brief"], "job brief", maximum=_MAX_BRIEF, allow_empty=True)
            for row in connection.execute("SELECT * FROM job_updates"):
                update = _row_dict(row, JOB_COLUMNS["job_updates"])
                _text(update["kind"], "update kind", maximum=40)
                _text(update["body"], "update body", maximum=20_000)
            for row in connection.execute("SELECT * FROM job_checkpoints"):
                checkpoint = _row_dict(row, JOB_COLUMNS["job_checkpoints"])
                _text(checkpoint["done"], "checkpoint done", maximum=10_000)
                _text(checkpoint["next_steps"], "checkpoint next_steps", maximum=10_000)
                _text(checkpoint["blocker"], "checkpoint blocker", maximum=2000, allow_empty=True)
                _pointers(_loads(checkpoint["pointers_json"], "checkpoint pointers"))
            for row in connection.execute("SELECT * FROM job_notes"):
                note = _row_dict(row, JOB_COLUMNS["job_notes"])
                if isinstance(note["revision"], bool) or not isinstance(note["revision"], int) or note["revision"] <= 0:
                    raise JobsSchemaIncompatible("stored note revision number is invalid")
                _validate_note_snapshot(_note_snapshot(note))
                if note["job"] is not None and note["job"] not in job_ids:
                    raise JobsSchemaIncompatible("stored note job link is not an existing stable job ID")
                current_revision = connection.execute(
                    "SELECT revision, state_json FROM job_note_revisions WHERE note_id = ? ORDER BY revision DESC LIMIT 1",
                    (note["id"],),
                ).fetchone()
                if current_revision is None or current_revision[0] != note["revision"]:
                    raise JobsSchemaIncompatible("current note revision does not match history")
                if _loads(current_revision[1], "note revision") != _note_snapshot(note):
                    raise JobsSchemaIncompatible("current note snapshot is inconsistent")
            for row in connection.execute("SELECT * FROM job_note_revisions"):
                revision = _row_dict(row, JOB_COLUMNS["job_note_revisions"])
                snapshot = _loads(revision["state_json"], "note revision")
                if not isinstance(snapshot, Mapping):
                    raise JobsSchemaIncompatible("stored note revision is not an object")
                _validate_note_snapshot(snapshot)
                if snapshot["job"] is not None and snapshot["job"] not in job_ids:
                    raise JobsSchemaIncompatible("historical note job link is not an existing stable job ID")
                if snapshot["id"] != revision["note_id"]:
                    raise JobsSchemaIncompatible("stored note revision identifies another note")
            incomplete_note_revisions = connection.execute(
                "SELECT note_id FROM job_note_revisions GROUP BY note_id "
                "HAVING MIN(revision) <> 1 OR COUNT(*) <> MAX(revision) "
                "OR COUNT(DISTINCT revision) <> COUNT(*) LIMIT 1"
            ).fetchone()
            if incomplete_note_revisions is not None:
                raise JobsSchemaIncompatible("note revision history contains a gap")
            missing_current_note_revision = connection.execute(
                "SELECT n.id FROM job_notes AS n LEFT JOIN job_note_revisions AS r "
                "ON r.note_id = n.id AND r.revision = n.revision WHERE r.note_id IS NULL LIMIT 1"
            ).fetchone()
            if missing_current_note_revision is not None:
                raise JobsSchemaIncompatible("current note revision is missing")
            invalid_worker_state = connection.execute(
                "SELECT worker FROM job_worker_state WHERE paused NOT IN (0, 1) "
                "OR (paused = 0 AND reason <> '') LIMIT 1"
            ).fetchone()
            if invalid_worker_state is not None:
                raise JobsSchemaIncompatible("stored worker pause state is invalid")
            for row in connection.execute("SELECT * FROM job_worker_state"):
                worker_state = _row_dict(row, JOB_COLUMNS["job_worker_state"])
                _worker(worker_state["worker"])
                _text(worker_state["reason"], "worker pause reason", maximum=2000, allow_empty=True)
                _text(worker_state["updated_at"], "worker state updated_at", maximum=100)
            for row in connection.execute("SELECT * FROM job_worker_state_history"):
                worker_state = _row_dict(row, JOB_COLUMNS["job_worker_state_history"])
                _worker(worker_state["worker"])
                if worker_state["paused"] not in (0, 1):
                    raise JobsSchemaIncompatible("stored worker state history is invalid")
                _text(worker_state["reason"], "worker state history reason", maximum=2000, allow_empty=True)
                _text(worker_state["created_at"], "worker state history created_at", maximum=100)
            stale_worker_state = connection.execute(
                "SELECT s.worker FROM job_worker_state AS s LEFT JOIN job_worker_state_history AS h "
                "ON h.worker = s.worker AND h.sequence = (SELECT MAX(latest.sequence) "
                "FROM job_worker_state_history AS latest WHERE latest.worker = s.worker) "
                "WHERE h.worker IS NULL OR h.paused <> s.paused OR h.reason <> s.reason LIMIT 1"
            ).fetchone()
            if stale_worker_state is not None:
                raise JobsSchemaIncompatible("current worker state does not match its history")
            orphan_worker_history = connection.execute(
                "SELECT h.worker FROM job_worker_state_history AS h LEFT JOIN job_worker_state AS s "
                "ON s.worker = h.worker WHERE s.worker IS NULL LIMIT 1"
            ).fetchone()
            if orphan_worker_history is not None:
                raise JobsSchemaIncompatible("worker state history has no current state row")
            for row in connection.execute("SELECT * FROM job_imports"):
                imported = _row_dict(row, JOB_COLUMNS["job_imports"])
                if not re.fullmatch(r"[0-9a-f]{64}", imported["manifest_fingerprint"]):
                    raise JobsSchemaIncompatible("stored import fingerprint is invalid")
                manifest_document = _loads(imported["manifest_json"], "import manifest")
                source_document = _loads(imported["source_contents_json"], "import source contents")
                provenance_document = _loads(imported["provenance_json"], "import provenance")
                if not isinstance(manifest_document, Mapping) or not isinstance(source_document, list) or not isinstance(provenance_document, Mapping):
                    raise JobsSchemaIncompatible("stored import provenance has an invalid shape")
        except JobError as exc:
            if isinstance(exc, JobsSchemaIncompatible):
                raise
            raise JobsSchemaIncompatible("stored job data failed content validation") from exc

    def create(
        self,
        name: str,
        worker: str,
        title: str,
        brief: str = "",
        summary: str = "",
        status: str = "active",
        chat_id: str | None = None,
        checklist: Sequence[Mapping[str, Any]] | None = None,
        progress: str = "",
        blocker: str = "",
        primary: bool | None = None,
        workstream_id: str | None = None,
    ) -> dict[str, Any]:
        """Create a job at revision one; an occupied primary slot is rejected."""

        selected_status = _status(status)
        selected_primary = selected_status not in {"parked", "completed"} if primary is None else _boolean(primary, "primary")
        if selected_status in {"parked", "completed"} and selected_primary:
            raise JobError("parked and completed jobs cannot be primary")
        prepared = {
            "name": _slug(name),
            "worker": _worker(worker),
            "workstream_id": _optional_workstream_id(workstream_id),
            "title": _text(title, "title", maximum=300),
            "brief": _text(brief, "brief", maximum=_MAX_BRIEF, allow_empty=True),
            "summary": _text(summary, "summary", maximum=2000, allow_empty=True),
            "status": selected_status,
            "primary": selected_primary,
            "chat_id": _optional_chat_id(chat_id),
            "checklist": _checklist(() if checklist is None else checklist, allow_missing_ids=True),
            "progress": _text(progress, "progress", maximum=2000, allow_empty=True),
            "blocker": _text(blocker, "blocker", maximum=2000, allow_empty=True),
        }
        with self._write() as connection:
            created = self._insert_job(connection, prepared)
            return self._get(connection, created["id"], latest=10, full_history=False)

    def get(self, identifier: str, latest: int = 10, full_history: bool = False) -> dict[str, Any]:
        """Read one job, its private brief, pending notes, and bounded recent activity."""

        selected_latest = _bounded_integer(latest, "latest", minimum=0, maximum=1000)
        if not isinstance(full_history, bool):
            raise JobError("full_history must be a boolean")
        with self._read() as connection:
            return self._get(connection, identifier, latest=selected_latest, full_history=full_history)

    def list(
        self,
        worker: str | None = None,
        status: str | None = None,
        search: str | None = None,
        primary: bool | None = None,
        workstream_id: str | None = None,
    ) -> list[dict[str, Any]]:
        """List public job fields without loading briefs or revision history."""

        selected_worker = None if worker is None else _worker(worker)
        selected_status = None if status is None else _status(status)
        selected_search = None if search is None else _name_filter(search)
        selected_primary = None if primary is None else _boolean(primary, "primary")
        selected_workstream_id = _optional_workstream_id(workstream_id)
        clauses: list[str] = []
        parameters: list[Any] = []
        if selected_worker is not None:
            clauses.append("worker = ?")
            parameters.append(selected_worker)
        if selected_status is not None:
            clauses.append("status = ?")
            parameters.append(selected_status)
        if selected_primary is not None:
            clauses.append("is_primary = ?")
            parameters.append(int(selected_primary))
        if selected_workstream_id is not None:
            clauses.append("workstream_id = ?")
            parameters.append(selected_workstream_id)
        if selected_search is not None:
            folded = selected_search.casefold()
            clauses.append(
                "(name = ? COLLATE NOCASE OR lower(name) LIKE ? OR lower(title) LIKE ? "
                "OR lower(worker) LIKE ? OR lower(summary) LIKE ? OR lower(progress) LIKE ? OR lower(blocker) LIKE ?)"
            )
            parameters.extend([selected_search, *(f"%{folded}%" for _ in range(6))])
        where = " WHERE " + " AND ".join(clauses) if clauses else ""
        with self._read() as connection:
            rows = connection.execute(
                f"SELECT {_LIST_COLUMNS} FROM jobs{where} "
                "ORDER BY is_primary DESC, updated_at DESC, name COLLATE NOCASE ASC",
                parameters,
            ).fetchall()
            result = [_public_row(_row_dict(row, _LIST_COLUMN_NAMES)) for row in rows]
            activity = self._last_activity(connection, [job["id"] for job in result])
            for job in result:
                job["last_activity_at"] = activity[job["id"]]
            return result

    @staticmethod
    def _last_activity(connection: sqlite3.Connection, identifiers: list[str]) -> dict[str, str]:
        """Aggregate durable job activity in one read, including historical note scopes."""

        if not identifiers:
            return {}
        rows = connection.execute(
            "WITH selected AS (SELECT id, updated_at FROM jobs WHERE id IN (SELECT value FROM json_each(?))), "
            "activity(job_id, touched_at) AS (SELECT id, updated_at FROM selected "
            "UNION ALL SELECT job_id, created_at FROM job_revisions JOIN selected ON selected.id = job_id "
            "UNION ALL SELECT job_id, created_at FROM job_updates JOIN selected ON selected.id = job_id "
            "UNION ALL SELECT job_id, created_at FROM job_checkpoints JOIN selected ON selected.id = job_id "
            "UNION ALL SELECT job, job_notes.updated_at FROM job_notes JOIN selected ON selected.id = job "
            "UNION ALL SELECT selected.id, (SELECT MAX(job_note_revisions.created_at) "
            "FROM job_note_revisions INDEXED BY job_note_revisions_job_created_idx "
            "WHERE json_extract(state_json, '$.job') = selected.id) FROM selected) "
            "SELECT job_id, MAX(touched_at) FROM activity GROUP BY job_id",
            (_json(identifiers),),
        ).fetchall()
        return {row[0]: row[1] for row in rows}

    def revise(self, identifier: str, expected_revision: int, **changes: Any) -> dict[str, Any]:
        """Revise current fields atomically after an exact expected-revision check."""

        expected = _positive_revision(expected_revision)
        unknown = set(changes) - _REVISION_EDIT_FIELDS
        if unknown:
            raise JobError(f"unknown job fields: {', '.join(sorted(unknown))}")
        prepared = _prepare_changes(changes)
        with self._write() as connection:
            row = self._find_job(connection, identifier)
            self._expect_revision(row, expected)
            current = _row_dict(row, JOB_COLUMNS["jobs"])
            state_changes = _changes_for_row(current, prepared)
            current_brief = self._current_brief(connection, current["id"], current["brief_revision"])
            brief_changed = "brief" in prepared and prepared["brief"] != current_brief
            if not state_changes and not brief_changed:
                return self._get(connection, current["id"], latest=10, full_history=False)
            timestamp = _now()
            new_worker = state_changes.get("worker", current["worker"])
            new_status = state_changes.get("status", current["status"])
            explicit_primary = "primary" in prepared and prepared["primary"] is True
            if new_status in {"parked", "completed"}:
                if prepared.get("primary") is True:
                    raise JobError("parked and completed jobs cannot be primary")
                state_changes["is_primary"] = 0
            elif "primary" in prepared:
                state_changes["is_primary"] = int(prepared["primary"])
            else:
                state_changes["is_primary"] = current["is_primary"]
            becoming_primary = bool(state_changes["is_primary"])
            displaced: dict[str, Any] | None = None
            if becoming_primary:
                existing = connection.execute(
                    "SELECT * FROM jobs WHERE worker = ? AND is_primary = 1 AND id <> ?",
                    (new_worker, current["id"]),
                ).fetchone()
                if existing is not None:
                    if not explicit_primary:
                        raise JobError(f"worker {new_worker} already has a primary job")
                    displaced = _row_dict(existing, JOB_COLUMNS["jobs"])
                    displaced["revision"] += 1
                    displaced["is_primary"] = 0
                    displaced["updated_at"] = timestamp
                    connection.execute(
                        "UPDATE jobs SET is_primary = 0, revision = ?, updated_at = ? WHERE id = ?",
                        (displaced["revision"], timestamp, displaced["id"]),
                    )
                    refreshed = connection.execute("SELECT * FROM jobs WHERE id = ?", (displaced["id"],)).fetchone()
                    self._record_revision(connection, _row_dict(refreshed, JOB_COLUMNS["jobs"]), timestamp)

            setters: list[str] = []
            values: list[Any] = []
            for key, value in state_changes.items():
                setters.append(f"{key} = ?")
                values.append(value)
            new_revision = current["revision"] + 1
            new_brief_revision = current["brief_revision"]
            if "brief" in prepared and prepared["brief"] != self._current_brief(connection, current["id"], current["brief_revision"]):
                new_brief_revision += 1
                connection.execute(
                    "INSERT INTO job_briefs(job_id, brief_revision, created_at, brief) VALUES (?, ?, ?, ?)",
                    (current["id"], new_brief_revision, timestamp, prepared["brief"]),
                )
                state_changes["brief_revision"] = new_brief_revision
                setters.append("brief_revision = ?")
                values.append(new_brief_revision)
            state_changes["revision"] = new_revision
            state_changes["updated_at"] = timestamp
            setters.extend(("revision = ?", "updated_at = ?"))
            values.extend((new_revision, timestamp, current["id"]))
            connection.execute(f"UPDATE jobs SET {', '.join(setters)} WHERE id = ?", values)
            row_after = connection.execute("SELECT * FROM jobs WHERE id = ?", (current["id"],)).fetchone()
            self._record_revision(connection, _row_dict(row_after, JOB_COLUMNS["jobs"]), timestamp)
            return self._get(connection, current["id"], latest=10, full_history=False)

    def append_update(self, identifier: str, body: str, kind: str = "progress") -> dict[str, Any]:
        """Append one chronological update without changing the job revision."""

        selected_body = _text(body, "update body", maximum=20_000)
        selected_kind = _text(kind, "update kind", maximum=40)
        with self._write() as connection:
            job = self._find_job(connection, identifier)
            timestamp = _now()
            cursor = connection.execute(
                "INSERT INTO job_updates(job_id, created_at, kind, body) VALUES (?, ?, ?, ?)",
                (job[0], timestamp, selected_kind, selected_body),
            )
            return {
                "sequence": int(cursor.lastrowid),
                "job_id": job[0],
                "created_at": timestamp,
                "kind": selected_kind,
                "body": selected_body,
            }

    def checkpoint(
        self,
        identifier: str,
        done: str,
        next_steps: str,
        blocker: str = "",
        pointers: Mapping[str, Any] | None = None,
    ) -> dict[str, Any]:
        """Append a park/resume checkpoint with explicit source and proof pointers."""

        selected_done = _text(done, "checkpoint done", maximum=10_000)
        selected_next = _text(next_steps, "checkpoint next_steps", maximum=10_000)
        selected_blocker = _text(blocker, "checkpoint blocker", maximum=2000, allow_empty=True)
        selected_pointers = _pointers(pointers)
        with self._write() as connection:
            job = self._find_job(connection, identifier)
            timestamp = _now()
            cursor = connection.execute(
                "INSERT INTO job_checkpoints(job_id, created_at, done, next_steps, blocker, pointers_json) "
                "VALUES (?, ?, ?, ?, ?, ?)",
                (job[0], timestamp, selected_done, selected_next, selected_blocker, _json(selected_pointers)),
            )
            return {
                "sequence": int(cursor.lastrowid),
                "job_id": job[0],
                "created_at": timestamp,
                "done": selected_done,
                "next_steps": selected_next,
                "blocker": selected_blocker,
                "pointers": selected_pointers,
            }

    def lane_state(self, worker: str) -> dict[str, Any]:
        """Read one worker lane's explicit pause and derived public job state."""

        selected_worker = _worker(worker)
        with self._read() as connection:
            return self._lane_states(connection, [selected_worker])[0]

    def lanes(self, workers: Sequence[str] | None = None) -> list[dict[str, Any]]:
        """Read all requested lane states in one bounded, batched query pass."""

        if workers is not None and (isinstance(workers, (str, bytes)) or not isinstance(workers, Sequence)):
            raise JobError("workers must be a list of worker aliases")
        selected_workers = None if workers is None else list(dict.fromkeys(_worker(worker) for worker in workers))
        with self._read() as connection:
            if selected_workers is None:
                found = [row[0] for row in connection.execute(
                    "SELECT worker FROM jobs UNION SELECT worker FROM job_worker_state ORDER BY worker COLLATE NOCASE"
                )]
                selected_workers = list(dict.fromkeys(found))
            return self._lane_states(connection, selected_workers)

    def pause(
        self,
        worker: str,
        *,
        reason: str = "",
        job: str | None = None,
        checkpoint: Mapping[str, Any] | None = None,
        update: str | None = None,
    ) -> dict[str, Any]:
        """Persist a lane pause until resume is explicitly called."""

        return self._set_lane_state(
            worker, paused=True, reason=reason, job=job, checkpoint=checkpoint, update=update
        )

    def resume(
        self,
        worker: str,
        *,
        job: str | None = None,
        checkpoint: Mapping[str, Any] | None = None,
        update: str | None = None,
    ) -> dict[str, Any]:
        """Explicitly clear a persisted lane pause, optionally recording a checkpoint."""

        return self._set_lane_state(worker, paused=False, reason="", job=job, checkpoint=checkpoint, update=update)

    def _set_lane_state(
        self,
        worker: str,
        *,
        paused: bool,
        reason: str,
        job: str | None,
        checkpoint: Mapping[str, Any] | None,
        update: str | None,
    ) -> dict[str, Any]:
        selected_worker = _worker(worker)
        selected_reason = _text(reason, "pause reason", maximum=2000, allow_empty=True)
        if not paused and selected_reason:
            raise JobError("a resume cannot include a pause reason")
        selected_job = None if job is None else _text(job, "lane job", maximum=100)
        prepared_checkpoint = None if checkpoint is None else _prepare_checkpoint(checkpoint)
        selected_update = None if update is None else _text(update, "update body", maximum=20_000)
        if (prepared_checkpoint is not None or selected_update is not None) and selected_job is None:
            raise JobError("a lane checkpoint or update requires a job")
        with self._write() as connection:
            job_row = None if selected_job is None else self._find_job(connection, selected_job)
            if job_row is not None and job_row["worker"] != selected_worker:
                raise JobError(f"job {job_row['name']} is assigned to {job_row['worker']}, not {selected_worker}")
            timestamp = _now()
            connection.execute(
                "INSERT INTO job_worker_state(worker, paused, reason, updated_at) VALUES (?, ?, ?, ?) "
                "ON CONFLICT(worker) DO UPDATE SET paused = excluded.paused, reason = excluded.reason, updated_at = excluded.updated_at",
                (selected_worker, int(paused), selected_reason if paused else "", timestamp),
            )
            connection.execute(
                "INSERT INTO job_worker_state_history(worker, created_at, paused, reason) VALUES (?, ?, ?, ?)",
                (selected_worker, timestamp, int(paused), selected_reason if paused else ""),
            )
            if job_row is not None and prepared_checkpoint is not None:
                self._insert_checkpoint(connection, job_row["id"], prepared_checkpoint, timestamp)
            if job_row is not None and selected_update is not None:
                connection.execute(
                    "INSERT INTO job_updates(job_id, created_at, kind, body) VALUES (?, ?, 'progress', ?)",
                    (job_row["id"], timestamp, selected_update),
                )
            return self._lane_states(connection, [selected_worker])[0]

    @staticmethod
    def _insert_checkpoint(
        connection: sqlite3.Connection,
        job_id: str,
        checkpoint: Mapping[str, Any],
        timestamp: str,
    ) -> dict[str, Any]:
        cursor = connection.execute(
            "INSERT INTO job_checkpoints(job_id, created_at, done, next_steps, blocker, pointers_json) "
            "VALUES (?, ?, ?, ?, ?, ?)",
            (
                job_id, timestamp, checkpoint["done"], checkpoint["next_steps"], checkpoint["blocker"],
                _json(checkpoint["pointers"]),
            ),
        )
        return {
            "sequence": int(cursor.lastrowid), "job_id": job_id, "created_at": timestamp,
            **checkpoint,
        }

    @staticmethod
    def _lane_states(connection: sqlite3.Connection, workers: Sequence[str]) -> list[dict[str, Any]]:
        if not workers:
            return []
        placeholders = ",".join("?" for _ in workers)
        state_rows = {
            row["worker"]: _row_dict(row, JOB_COLUMNS["job_worker_state"])
            for row in connection.execute(
                f"SELECT worker, paused, reason, updated_at FROM job_worker_state WHERE worker IN ({placeholders})",
                workers,
            )
        }
        counts: dict[str, dict[str, int]] = {worker: {} for worker in workers}
        for row in connection.execute(
            f"SELECT worker, status, COUNT(*) AS total FROM jobs WHERE worker IN ({placeholders}) GROUP BY worker, status",
            workers,
        ):
            counts[row["worker"]][row["status"]] = row["total"]
        ranked_rows = connection.execute(
            "SELECT id, name, worker, workstream_id, title, status, is_primary, revision, summary, progress, "
            "blocker, checklist_json, created_at, updated_at, lane_rank FROM ("
            "SELECT id, name, worker, workstream_id, title, status, is_primary, revision, summary, progress, "
            "blocker, checklist_json, created_at, updated_at, "
            "ROW_NUMBER() OVER (PARTITION BY worker, status ORDER BY is_primary DESC, updated_at DESC, name COLLATE NOCASE) AS lane_rank "
            f"FROM jobs WHERE worker IN ({placeholders}) AND status IN ('active', 'blocked', 'parked')"
            ") WHERE lane_rank <= ? ORDER BY worker COLLATE NOCASE, status, lane_rank",
            (*workers, _MAX_LANE_JOBS),
        ).fetchall()
        activity = JobStore._last_activity(connection, [row["id"] for row in ranked_rows])
        grouped: dict[str, dict[str, list[dict[str, Any]]]] = {
            worker: {status: [] for status in ("active", "blocked", "parked")} for worker in workers
        }
        primary_by_worker: dict[str, dict[str, Any]] = {}
        for row in ranked_rows:
            selected = {
                "id": row["id"], "name": row["name"], "worker": row["worker"],
                "workstream_id": row["workstream_id"], "title": row["title"], "status": row["status"],
                "primary": bool(row["is_primary"]), "revision": row["revision"], "summary": row["summary"],
                "progress": row["progress"], "blocker": row["blocker"],
                "checklist": _loads(row["checklist_json"], "job checklist"), "created_at": row["created_at"],
                "updated_at": row["updated_at"], "last_activity_at": activity[row["id"]],
            }
            grouped[row["worker"]][row["status"]].append(selected)
            if selected["primary"]:
                primary_by_worker[row["worker"]] = selected
        result: list[dict[str, Any]] = []
        for worker in workers:
            state_row = state_rows.get(worker)
            paused = bool(state_row and state_row["paused"])
            status_counts = counts[worker]
            if paused:
                lane_status = "paused"
            elif status_counts.get("active", 0):
                lane_status = "active"
            elif status_counts.get("blocked", 0):
                lane_status = "blocked"
            else:
                lane_status = "idle"
            lane_jobs = [
                *grouped[worker]["active"],
                *grouped[worker]["blocked"],
                *grouped[worker]["parked"],
            ]
            lane: dict[str, Any] = {
                "worker": worker,
                "status": lane_status,
                "paused": paused,
                "pause_reason": "" if state_row is None else state_row["reason"],
                "active_count": status_counts.get("active", 0),
                "blocked_count": status_counts.get("blocked", 0),
                "parked_count": status_counts.get("parked", 0),
                "completed_count": status_counts.get("completed", 0),
                "primary": primary_by_worker.get(worker),
                "jobs": lane_jobs,
                "jobs_truncated": any(
                    status_counts.get(job_status, 0) > len(grouped[worker][job_status])
                    for job_status in ("active", "blocked", "parked")
                ),
            }
            result.append(lane)
        return result

    def checklist(
        self,
        identifier: str,
        expected_revision: int,
        action: str,
        item_id: str | None = None,
        text: str | None = None,
    ) -> dict[str, Any]:
        """Add, rename, complete, or reopen one checklist entry with CAS."""

        expected = _positive_revision(expected_revision)
        if not isinstance(action, str) or action not in CHECKLIST_ACTIONS:
            raise JobError(f"action must be one of {', '.join(CHECKLIST_ACTIONS)}")
        selected_item = None if item_id is None else _text(item_id, "item_id", maximum=100)
        selected_text = None if text is None else _text(text, "checklist item text", maximum=1000)
        if action == "add" and selected_text is None:
            raise JobError("checklist add requires text")
        if action != "add" and selected_item is None:
            raise JobError(f"checklist {action} requires item_id")
        if action == "change" and selected_text is None:
            raise JobError("checklist change requires text")
        if action in {"done", "reopen"} and selected_text is not None:
            raise JobError(f"checklist {action} does not accept text")
        with self._write() as connection:
            row = self._find_job(connection, identifier)
            self._expect_revision(row, expected)
            current = _loads(row["checklist_json"], "job checklist")
            items = _checklist(current)
            if action == "add":
                new_id = selected_item or str(uuid.uuid4())
                if any(item["id"] == new_id for item in items):
                    raise JobError(f"checklist item {new_id} already exists")
                items.append({"id": new_id, "text": selected_text, "done": False})
                items = _checklist(items)
            else:
                matched = next((item for item in items if item["id"] == selected_item), None)
                if matched is None:
                    raise JobNotFound(f"checklist item {selected_item} was not found")
                if action == "change":
                    matched["text"] = selected_text
                elif action == "done":
                    matched["done"] = True
                elif action == "reopen":
                    matched["done"] = False
            timestamp = _now()
            next_revision = row["revision"] + 1
            connection.execute(
                "UPDATE jobs SET checklist_json = ?, revision = ?, updated_at = ? WHERE id = ?",
                (_json(items), next_revision, timestamp, row["id"]),
            )
            refreshed = connection.execute("SELECT * FROM jobs WHERE id = ?", (row["id"],)).fetchone()
            self._record_revision(connection, _row_dict(refreshed, JOB_COLUMNS["jobs"]), timestamp)
            return self._get(connection, row["id"], latest=10, full_history=False)

    def history(
        self,
        identifier: str,
        revision: int | None = None,
        search: str | None = None,
        limit: int | None = 50,
        offset: int = 0,
    ) -> list[dict[str, Any]] | dict[str, Any]:
        """List bounded revision snapshots or load one exact historical revision."""

        if revision is not None:
            selected_revision = _positive_revision(revision, "revision")
            if search is not None:
                raise JobError("search cannot be combined with an exact revision")
        else:
            selected_revision = None
        selected_search = None if search is None else _name_filter(search)
        selected_limit = _page_limit(limit)
        selected_offset = _bounded_integer(offset, "offset", minimum=0, maximum=2_147_483_647)
        with self._read() as connection:
            job = self._find_job(connection, identifier)
            if selected_revision is not None:
                result = self._revision_detail(connection, job["id"], selected_revision)
                if result is None:
                    raise JobNotFound(f"revision {selected_revision} was not found for job {job['name']}")
                return result
            return self._history_rows(
                connection,
                job["id"],
                search=selected_search,
                limit=selected_limit,
                offset=selected_offset,
            )

    def diff(self, identifier: str, old: int, new: int) -> str:
        """Return a readable unified diff between two exact job revisions."""

        old_revision = _positive_revision(old, "old revision")
        new_revision = _positive_revision(new, "new revision")
        with self._read() as connection:
            job = self._find_job(connection, identifier)
            before = self._revision_detail(connection, job["id"], old_revision)
            after = self._revision_detail(connection, job["id"], new_revision)
            if before is None:
                raise JobNotFound(f"revision {old_revision} was not found for job {job['name']}")
            if after is None:
                raise JobNotFound(f"revision {new_revision} was not found for job {job['name']}")
            old_text = json.dumps(before, ensure_ascii=False, sort_keys=True, indent=2).splitlines(keepends=True)
            new_text = json.dumps(after, ensure_ascii=False, sort_keys=True, indent=2).splitlines(keepends=True)
            return "".join(
                difflib.unified_diff(old_text, new_text, fromfile=f"{job['name']}@{old_revision}", tofile=f"{job['name']}@{new_revision}")
            )

    def note(
        self,
        body: str,
        worker: str | None = None,
        job: str | None = None,
        phase: str = "",
        kind: str = "reminder",
    ) -> dict[str, Any]:
        """Add one deferred reminder or source pointer without revising a job."""

        selected_body = _text(body, "note body", maximum=20_000)
        selected_worker = None if worker is None else _worker(worker)
        selected_job = None if job is None else _text(job, "note job", maximum=100)
        selected_phase = _text(phase, "note phase", maximum=200, allow_empty=True) or None
        if selected_worker is None and selected_job is None and selected_phase is None:
            raise JobError("a note must be scoped to a worker, job, or future phase")
        if not isinstance(kind, str) or kind not in NOTE_KINDS:
            raise JobError(f"kind must be one of {', '.join(NOTE_KINDS)}")
        with self._write() as connection:
            return self._insert_note(
                connection,
                {"body": selected_body, "worker": selected_worker, "job": selected_job, "phase": selected_phase, "kind": kind},
            )

    def notes(
        self,
        worker: str | None = None,
        job: str | None = None,
        phase: str | None = None,
        status: str | None = "pending",
        limit: int | None = 50,
        offset: int = 0,
    ) -> list[dict[str, Any]]:
        """Read notes by exact indexed scope; status=None returns every state."""

        selected_worker = None if worker is None else _worker(worker)
        selected_job = None if job is None else _text(job, "note job", maximum=100)
        selected_phase = None if phase is None else _text(phase, "note phase", maximum=200)
        if status is not None and (not isinstance(status, str) or status not in NOTE_STATUSES):
            raise JobError(f"status must be one of {', '.join(NOTE_STATUSES)}")
        selected_limit = _page_limit(limit)
        selected_offset = _bounded_integer(offset, "offset", minimum=0, maximum=2_147_483_647)
        with self._read() as connection:
            if selected_job is not None:
                selected_job = self._find_job(connection, selected_job)["id"]
            clauses: list[str] = []
            parameters: list[Any] = []
            for column, value in (("worker", selected_worker), ("job", selected_job), ("phase", selected_phase), ("status", status)):
                if value is not None:
                    clauses.append(f"{column} = ?")
                    parameters.append(value)
            where = " WHERE " + " AND ".join(clauses) if clauses else ""
            sql = (
                "SELECT id, body, worker, job, phase, kind, status, dismissal_reason, revision, created_at, updated_at "
                f"FROM job_notes{where} ORDER BY created_at DESC, id DESC"
            )
            page_parameters = list(parameters)
            if selected_limit is not None:
                sql += " LIMIT ? OFFSET ?"
                page_parameters.extend((selected_limit, selected_offset))
            elif selected_offset:
                sql += " LIMIT -1 OFFSET ?"
                page_parameters.append(selected_offset)
            rows = connection.execute(
                sql,
                page_parameters,
            ).fetchall()
            return [_row_dict(row, JOB_COLUMNS["job_notes"]) for row in rows]

    def revise_note(self, note_id: str, expected_revision: int, **changes: Any) -> dict[str, Any]:
        """Correct note text, scope, kind, or lifecycle state with a revision CAS."""

        selected_id = _text(note_id, "note id", maximum=100)
        expected = _positive_revision(expected_revision)
        allowed = {"body", "worker", "job", "phase", "kind", "status", "reason"}
        unknown = set(changes) - allowed
        if unknown:
            raise JobError(f"unknown note fields: {', '.join(sorted(unknown))}")
        prepared: dict[str, Any] = {}
        for key, value in changes.items():
            if key == "body":
                prepared[key] = _text(value, "note body", maximum=20_000)
            elif key == "worker":
                prepared[key] = None if value is None else _worker(value)
            elif key == "job":
                prepared[key] = None if value is None else _text(value, "note job", maximum=100)
            elif key == "phase":
                prepared[key] = None if value is None else (_text(value, "note phase", maximum=200, allow_empty=True) or None)
            elif key == "kind":
                if not isinstance(value, str) or value not in NOTE_KINDS:
                    raise JobError(f"kind must be one of {', '.join(NOTE_KINDS)}")
                prepared[key] = value
            elif key == "status":
                if not isinstance(value, str) or value not in NOTE_STATUSES:
                    raise JobError(f"status must be one of {', '.join(NOTE_STATUSES)}")
                prepared[key] = value
            elif key == "reason":
                prepared["dismissal_reason"] = _text(value, "dismissal reason", maximum=2000, allow_empty=True)
        with self._write() as connection:
            row = connection.execute("SELECT * FROM job_notes WHERE id = ?", (selected_id,)).fetchone()
            if row is None:
                raise JobNotFound(f"note {selected_id} was not found")
            if row["revision"] != expected:
                raise RevisionConflict(
                    f"note {selected_id} is at revision {row['revision']}; expected revision {expected}"
                )
            current = _row_dict(row, JOB_COLUMNS["job_notes"])
            if prepared.get("job") is not None:
                prepared["job"] = self._find_job(connection, prepared["job"])["id"]
            updated_state = _note_snapshot(current)
            updated_state.update({key: value for key, value in prepared.items() if key != "reason"})
            status_changed = updated_state["status"] != current["status"]
            if "dismissal_reason" in prepared:
                updated_state["dismissal_reason"] = prepared["dismissal_reason"]
            elif status_changed and updated_state["status"] != "dismissed":
                updated_state["dismissal_reason"] = ""
            if updated_state["status"] == "dismissed" and not updated_state["dismissal_reason"]:
                raise JobError("dismissed notes require a dismissal reason")
            if updated_state["worker"] is None and updated_state["job"] is None and updated_state["phase"] is None:
                raise JobError("a note must retain a worker, job, or future phase scope")
            if updated_state["status"] == "dismissed":
                _text(updated_state["dismissal_reason"], "dismissal reason", maximum=2000)
            elif updated_state["dismissal_reason"]:
                raise JobError("a dismissal reason is only valid when status is dismissed")
            _validate_note_snapshot(updated_state)
            if updated_state == _note_snapshot(current):
                return current
            timestamp = _now()
            new_revision = current["revision"] + 1
            connection.execute(
                "UPDATE job_notes SET body = ?, worker = ?, job = ?, phase = ?, kind = ?, status = ?, "
                "dismissal_reason = ?, revision = ?, updated_at = ? WHERE id = ?",
                (
                    updated_state["body"], updated_state["worker"], updated_state["job"], updated_state["phase"],
                    updated_state["kind"], updated_state["status"], updated_state["dismissal_reason"],
                    new_revision, timestamp, selected_id,
                ),
            )
            updated = _row_dict(connection.execute("SELECT * FROM job_notes WHERE id = ?", (selected_id,)).fetchone(), JOB_COLUMNS["job_notes"])
            self._record_note_revision(connection, updated, timestamp)
            return updated

    def note_status(
        self,
        note_id: str,
        status: str,
        reason: str = "",
        *,
        expected_revision: int,
    ) -> dict[str, Any]:
        """Change note lifecycle state with the same compare-and-swap as edits."""

        changes: dict[str, Any] = {"status": status}
        if reason or status != "dismissed":
            changes["reason"] = reason
        return self.revise_note(note_id, expected_revision, **changes)

    def note_history(
        self,
        note_id: str,
        revision: int | None = None,
        limit: int | None = 50,
        offset: int = 0,
    ) -> list[dict[str, Any]] | dict[str, Any]:
        """Read bounded note revisions, or one exact full historical snapshot."""

        selected_id = _text(note_id, "note id", maximum=100)
        selected_revision = None if revision is None else _positive_revision(revision, "revision")
        selected_limit = _page_limit(limit)
        selected_offset = _bounded_integer(offset, "offset", minimum=0, maximum=2_147_483_647)
        with self._read() as connection:
            if connection.execute("SELECT 1 FROM job_notes WHERE id = ?", (selected_id,)).fetchone() is None:
                raise JobNotFound(f"note {selected_id} was not found")
            if selected_revision is not None:
                row = connection.execute(
                    "SELECT revision, created_at, state_json FROM job_note_revisions "
                    "WHERE note_id = ? AND revision = ?",
                    (selected_id, selected_revision),
                ).fetchone()
                if row is None:
                    raise JobNotFound(f"revision {selected_revision} was not found for note {selected_id}")
                snapshot = _loads(row["state_json"], "note revision")
                _validate_note_snapshot(snapshot)
                return {"revision": row["revision"], "created_at": row["created_at"], **snapshot}
            sql = "SELECT revision, created_at, state_json FROM job_note_revisions WHERE note_id = ? ORDER BY revision DESC"
            parameters: tuple[Any, ...] = (selected_id,)
            if selected_limit is not None:
                sql += " LIMIT ? OFFSET ?"
                parameters = (*parameters, selected_limit, selected_offset)
            elif selected_offset:
                sql += " LIMIT -1 OFFSET ?"
                parameters = (*parameters, selected_offset)
            rows = connection.execute(sql, parameters).fetchall()
            result: list[dict[str, Any]] = []
            for row in rows:
                snapshot = _loads(row["state_json"], "note revision")
                _validate_note_snapshot(snapshot)
                result.append({"revision": row["revision"], "created_at": row["created_at"], **snapshot})
            return result

    def import_briefs(self, manifest: Mapping[str, Any], apply: bool = False) -> dict[str, Any]:
        """Preview or idempotently import existing briefs and provenance.

        Manifest version one uses ``jobs`` entries with a ``brief_file`` path
        and optional ``source_files`` paths. Callers resolve relative paths
        against the manifest location before calling this method.
        """

        prepared_manifest, job_inputs, note_inputs, source_contents, fingerprint = _prepare_manifest(manifest)
        if not isinstance(apply, bool):
            raise JobError("apply must be a boolean")
        with self._read() as connection:
            existing_import = connection.execute(
                "SELECT created_at FROM job_imports WHERE manifest_fingerprint = ?", (fingerprint,)
            ).fetchone()
            if existing_import is not None:
                return {
                    "applied": False,
                    "already_applied": True,
                    "manifest_fingerprint": fingerprint,
                    "jobs": len(job_inputs),
                    "notes": len(note_inputs),
                    "job_ids": [],
                }
            conflicts = _import_conflicts(connection, job_inputs)
            planned_names = {item["name"] for item in job_inputs}
            for item in note_inputs:
                if item["job"] is not None and item["job"] not in planned_names:
                    self._find_job(connection, item["job"])
        result = {
            "applied": False,
            "already_applied": False,
            "manifest_fingerprint": fingerprint,
            "jobs": len(job_inputs),
            "notes": len(note_inputs),
            "conflicts": conflicts,
            "job_ids": [],
        }
        if not apply:
            return result
        if conflicts:
            raise JobError("manifest conflicts with existing job names or primary assignments: " + "; ".join(conflicts))
        job_ids: list[str] = []
        with self._write() as connection:
            # Recheck the fingerprint and constraints inside the write lock.
            if connection.execute(
                "SELECT 1 FROM job_imports WHERE manifest_fingerprint = ?", (fingerprint,)
            ).fetchone() is not None:
                return {
                    "applied": False,
                    "already_applied": True,
                    "manifest_fingerprint": fingerprint,
                    "jobs": len(job_inputs),
                    "notes": len(note_inputs),
                    "job_ids": [],
                }
            conflicts = _import_conflicts(connection, job_inputs)
            if conflicts:
                raise JobError("manifest conflicts with existing job names or primary assignments: " + "; ".join(conflicts))
            for item in job_inputs:
                row = self._insert_job(connection, item)
                job_ids.append(row["id"])
            for item in note_inputs:
                self._insert_note(connection, item)
            provenance = prepared_manifest.get("provenance", {})
            connection.execute(
                "INSERT INTO job_imports(manifest_fingerprint, created_at, manifest_json, source_contents_json, provenance_json) "
                "VALUES (?, ?, ?, ?, ?)",
                (fingerprint, _now(), _json(prepared_manifest), _json(source_contents), _json(provenance)),
            )
        return {**result, "applied": True, "job_ids": job_ids}

    @staticmethod
    def _table_names(connection: sqlite3.Connection) -> set[str]:
        return {row[0] for row in connection.execute("SELECT name FROM sqlite_master WHERE type = 'table'")}

    @staticmethod
    def _require_controller_compatible_connection(
        connection: sqlite3.Connection,
        writer_build: int = WRITER_BUILD,
    ) -> None:
        try:
            schema_version = int(connection.execute("PRAGMA user_version").fetchone()[0])
            if schema_version != SQLITE_SCHEMA_VERSION:
                raise StateError(f"unsupported SQLite review-state schema version: {schema_version}")
            row = connection.execute(
                "SELECT data_model_version, min_writer_build FROM controller_metadata WHERE singleton = 1"
            ).fetchone()
            if row is None:
                raise StateError("SQLite review-state metadata is missing")
            data_model_version, min_writer_build = row
            if data_model_version != ReviewState().schema_version:
                raise StateError(f"unsupported review-state data model version: {data_model_version}")
            if isinstance(min_writer_build, bool) or not isinstance(min_writer_build, int) or min_writer_build <= 0:
                raise StateError("SQLite review-state minimum writer build is invalid")
            if writer_build < min_writer_build:
                raise StateError(f"SQLite review state requires writer build {min_writer_build}")
            state_table = connection.execute(
                "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = 'review_state'"
            ).fetchone()
            if state_table is None:
                raise StateError("SQLite review state table is missing")
        except sqlite3.DatabaseError as exc:
            raise JobsSchemaIncompatible("controller SQLite metadata is incomplete or malformed") from exc
        except StateError as exc:
            raise JobsSchemaIncompatible(f"controller SQLite database is incompatible: {exc}") from exc

    @staticmethod
    def _create_schema(connection: sqlite3.Connection) -> None:
        connection.execute(
            "CREATE TABLE job_metadata ("
            "singleton INTEGER PRIMARY KEY CHECK (singleton = 1), "
            "jobs_schema_version INTEGER NOT NULL, controller_schema_version INTEGER NOT NULL, "
            "controller_data_model_version INTEGER NOT NULL)"
        )
        connection.execute(
            "CREATE TABLE jobs ("
            "id TEXT PRIMARY KEY, name TEXT NOT NULL UNIQUE, worker TEXT NOT NULL, workstream_id TEXT, title TEXT NOT NULL, "
            "status TEXT NOT NULL CHECK (status IN ('active', 'parked', 'blocked', 'completed')), "
            "is_primary INTEGER NOT NULL CHECK (is_primary IN (0, 1)), "
            "revision INTEGER NOT NULL CHECK (revision > 0), brief_revision INTEGER NOT NULL CHECK (brief_revision > 0), "
            "summary TEXT NOT NULL, progress TEXT NOT NULL, blocker TEXT NOT NULL, checklist_json TEXT NOT NULL, "
            "chat_id TEXT, created_at TEXT NOT NULL, updated_at TEXT NOT NULL, "
            "CHECK (is_primary = 0 OR status NOT IN ('parked', 'completed')))"
        )
        connection.execute(
            "CREATE TABLE job_revisions ("
            "job_id TEXT NOT NULL REFERENCES jobs(id), revision INTEGER NOT NULL CHECK (revision > 0), "
            "brief_revision INTEGER NOT NULL CHECK (brief_revision > 0), created_at TEXT NOT NULL, state_json TEXT NOT NULL, "
            "PRIMARY KEY (job_id, revision))"
        )
        connection.execute(
            "CREATE TABLE job_briefs ("
            "job_id TEXT NOT NULL REFERENCES jobs(id), brief_revision INTEGER NOT NULL CHECK (brief_revision > 0), "
            "created_at TEXT NOT NULL, brief TEXT NOT NULL, PRIMARY KEY (job_id, brief_revision))"
        )
        connection.execute(
            "CREATE TABLE job_updates ("
            "sequence INTEGER PRIMARY KEY AUTOINCREMENT, job_id TEXT NOT NULL REFERENCES jobs(id), "
            "created_at TEXT NOT NULL, kind TEXT NOT NULL, body TEXT NOT NULL)"
        )
        connection.execute(
            "CREATE TABLE job_checkpoints ("
            "sequence INTEGER PRIMARY KEY AUTOINCREMENT, job_id TEXT NOT NULL REFERENCES jobs(id), "
            "created_at TEXT NOT NULL, done TEXT NOT NULL, next_steps TEXT NOT NULL, blocker TEXT NOT NULL, pointers_json TEXT NOT NULL)"
        )
        connection.execute(
            "CREATE TABLE job_notes ("
            "id TEXT PRIMARY KEY, body TEXT NOT NULL, worker TEXT, job TEXT, phase TEXT, kind TEXT NOT NULL, "
            "status TEXT NOT NULL CHECK (status IN ('pending', 'consumed', 'dismissed')), dismissal_reason TEXT NOT NULL, "
            "revision INTEGER NOT NULL CHECK (revision > 0), created_at TEXT NOT NULL, updated_at TEXT NOT NULL, "
            "CHECK (worker IS NOT NULL OR job IS NOT NULL OR phase IS NOT NULL), "
            "CHECK ((status = 'dismissed' AND length(dismissal_reason) > 0) OR (status <> 'dismissed' AND dismissal_reason = '')))"
        )
        connection.execute(
            "CREATE TABLE job_note_revisions ("
            "note_id TEXT NOT NULL REFERENCES job_notes(id), revision INTEGER NOT NULL CHECK (revision > 0), "
            "created_at TEXT NOT NULL, state_json TEXT NOT NULL, PRIMARY KEY (note_id, revision))"
        )
        connection.execute(
            "CREATE TABLE job_worker_state ("
            "worker TEXT PRIMARY KEY, paused INTEGER NOT NULL CHECK (paused IN (0, 1)), reason TEXT NOT NULL, "
            "updated_at TEXT NOT NULL, CHECK (paused = 1 OR reason = ''))"
        )
        connection.execute(
            "CREATE TABLE job_worker_state_history ("
            "sequence INTEGER PRIMARY KEY AUTOINCREMENT, worker TEXT NOT NULL, created_at TEXT NOT NULL, "
            "paused INTEGER NOT NULL CHECK (paused IN (0, 1)), reason TEXT NOT NULL, "
            "CHECK (paused = 1 OR reason = ''))"
        )
        connection.execute(
            "CREATE TABLE job_imports ("
            "manifest_fingerprint TEXT PRIMARY KEY, created_at TEXT NOT NULL, manifest_json TEXT NOT NULL, "
            "source_contents_json TEXT NOT NULL, provenance_json TEXT NOT NULL)"
        )
        for statement in JOB_INDEXES.values():
            connection.execute(statement)
        connection.execute(
            "INSERT INTO job_metadata(singleton, jobs_schema_version, controller_schema_version, controller_data_model_version) "
            "VALUES (1, ?, ?, ?)",
            (JOBS_SCHEMA_VERSION, SQLITE_SCHEMA_VERSION, ReviewState().schema_version),
        )

    @contextmanager
    def _read(self) -> Iterator[sqlite3.Connection]:
        connection = self._connect(read_only=True)
        try:
            connection.execute("BEGIN")
            self._require_compatible(connection)
            yield connection
        except JobError:
            raise
        except sqlite3.DatabaseError as exc:
            raise JobsSchemaIncompatible("SQLite job read failed") from exc
        finally:
            connection.close()

    @contextmanager
    def _write(self) -> Iterator[sqlite3.Connection]:
        connection = self._connect(read_only=False)
        try:
            connection.execute("BEGIN IMMEDIATE")
            self._require_compatible(connection)
            yield connection
            connection.commit()
        except JobError:
            if connection.in_transaction:
                connection.rollback()
            raise
        except sqlite3.IntegrityError as exc:
            if connection.in_transaction:
                connection.rollback()
            raise JobError("job update conflicts with an existing unique or schema constraint") from exc
        except sqlite3.DatabaseError as exc:
            if connection.in_transaction:
                connection.rollback()
            raise JobsSchemaIncompatible("SQLite job write failed") from exc
        except BaseException:
            if connection.in_transaction:
                connection.rollback()
            raise
        finally:
            connection.close()

    def _connect(self, *, read_only: bool) -> sqlite3.Connection:
        if self.path.is_symlink():
            raise JobsSchemaIncompatible("SQLite controller database path must not be a symlink")
        if not self.path.exists() or not self.path.is_file():
            if read_only:
                raise JobsNotBootstrapped("job store is not bootstrapped; call bootstrap() explicitly")
            raise JobsNotBootstrapped("job store is not bootstrapped; call bootstrap() explicitly")
        mode = "ro" if read_only else "rw"
        uri = f"{self.path.resolve().as_uri()}?mode={mode}"
        try:
            connection = sqlite3.connect(uri, uri=True, timeout=self.timeout, isolation_level=None)
            connection.row_factory = sqlite3.Row
            connection.execute(f"PRAGMA busy_timeout = {int(self.timeout * 1000)}")
            if read_only:
                connection.execute("PRAGMA query_only = ON")
            return connection
        except sqlite3.DatabaseError as exc:
            raise JobsSchemaIncompatible("cannot open SQLite controller database") from exc

    def _require_compatible(self, connection: sqlite3.Connection) -> None:
        self._require_controller_compatible_connection(connection, self.writer_build)
        tables = self._table_names(connection)
        present = tables & set(_JOB_TABLES)
        if not present:
            raise JobsNotBootstrapped("job schema is not bootstrapped; call bootstrap() explicitly")
        if present != set(_JOB_TABLES):
            raise JobsSchemaIncompatible("job schema is partial")
        row = connection.execute(
            "SELECT jobs_schema_version, controller_schema_version, controller_data_model_version "
            "FROM job_metadata WHERE singleton = 1"
        ).fetchone()
        if row is None:
            raise JobsSchemaIncompatible("job metadata row is missing")
        if tuple(row) != (JOBS_SCHEMA_VERSION, SQLITE_SCHEMA_VERSION, ReviewState().schema_version):
            raise JobsSchemaIncompatible("job schema requires a different controller or data-model version")

    def _require_controller_compatible(self, connection: sqlite3.Connection) -> None:
        self._require_controller_compatible_connection(connection, self.writer_build)

    def _insert_job(self, connection: sqlite3.Connection, prepared: Mapping[str, Any]) -> dict[str, Any]:
        if prepared["primary"]:
            occupied = connection.execute(
                "SELECT name FROM jobs WHERE worker = ? AND is_primary = 1", (prepared["worker"],)
            ).fetchone()
            if occupied is not None:
                raise JobError(f"worker {prepared['worker']} already has primary job {occupied[0]}")
        timestamp = _now()
        identifier = str(uuid.uuid4())
        brief_revision = 1
        connection.execute(
            "INSERT INTO jobs(id, name, worker, workstream_id, title, status, is_primary, revision, brief_revision, summary, progress, "
            "blocker, checklist_json, chat_id, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, 1, ?, ?, ?, ?, ?, ?, ?, ?)",
            (
                identifier, prepared["name"], prepared["worker"], prepared["workstream_id"], prepared["title"], prepared["status"],
                int(prepared["primary"]), brief_revision, prepared["summary"], prepared["progress"], prepared["blocker"],
                _json(prepared["checklist"]), prepared["chat_id"], timestamp, timestamp,
            ),
        )
        connection.execute(
            "INSERT INTO job_briefs(job_id, brief_revision, created_at, brief) VALUES (?, ?, ?, ?)",
            (identifier, brief_revision, timestamp, prepared["brief"]),
        )
        row = connection.execute("SELECT * FROM jobs WHERE id = ?", (identifier,)).fetchone()
        job = _row_dict(row, JOB_COLUMNS["jobs"])
        self._record_revision(connection, job, timestamp)
        return job

    @staticmethod
    def _record_revision(connection: sqlite3.Connection, job: Mapping[str, Any], timestamp: str) -> None:
        snapshot = _snapshot(job)
        connection.execute(
            "INSERT INTO job_revisions(job_id, revision, brief_revision, created_at, state_json) VALUES (?, ?, ?, ?, ?)",
            (job["id"], job["revision"], job["brief_revision"], timestamp, _json(snapshot)),
        )

    @staticmethod
    def _insert_note(connection: sqlite3.Connection, prepared: Mapping[str, Any]) -> dict[str, Any]:
        prepared = dict(prepared)
        if prepared["job"] is not None:
            prepared["job"] = JobStore._find_job(connection, prepared["job"])["id"]
        note_id = str(uuid.uuid4())
        timestamp = _now()
        connection.execute(
            "INSERT INTO job_notes(id, body, worker, job, phase, kind, status, dismissal_reason, revision, created_at, updated_at) "
            "VALUES (?, ?, ?, ?, ?, ?, 'pending', '', 1, ?, ?)",
            (note_id, prepared["body"], prepared["worker"], prepared["job"], prepared["phase"], prepared["kind"], timestamp, timestamp),
        )
        result = {
            "id": note_id,
            "body": prepared["body"],
            "worker": prepared["worker"],
            "job": prepared["job"],
            "phase": prepared["phase"],
            "kind": prepared["kind"],
            "status": "pending",
            "dismissal_reason": "",
            "revision": 1,
            "created_at": timestamp,
            "updated_at": timestamp,
        }
        JobStore._record_note_revision(connection, result, timestamp)
        return result

    @staticmethod
    def _record_note_revision(connection: sqlite3.Connection, note: Mapping[str, Any], timestamp: str) -> None:
        connection.execute(
            "INSERT INTO job_note_revisions(note_id, revision, created_at, state_json) VALUES (?, ?, ?, ?)",
            (note["id"], note["revision"], timestamp, _json(_note_snapshot(note))),
        )

    def _get(self, connection: sqlite3.Connection, identifier: str, *, latest: int, full_history: bool) -> dict[str, Any]:
        row = self._find_job(connection, identifier)
        public = _public_row(_row_dict(row, JOB_COLUMNS["jobs"]))
        public["last_activity_at"] = self._last_activity(connection, [row["id"]])[row["id"]]
        public["brief"] = self._current_brief(connection, row["id"], row["brief_revision"])
        if latest or full_history:
            update_sql = ("SELECT sequence, job_id, created_at, kind, body FROM job_updates WHERE job_id = ? "
                          "ORDER BY sequence DESC")
            update_parameters = (row["id"],) if full_history else (row["id"], latest)
            if not full_history:
                update_sql += " LIMIT ?"
            update_rows = connection.execute(update_sql, update_parameters).fetchall()
            public["updates"] = [_row_dict(update, JOB_COLUMNS["job_updates"]) for update in reversed(update_rows)]
        else:
            public["updates"] = []
        checkpoint = connection.execute(
            "SELECT sequence, job_id, created_at, done, next_steps, blocker, pointers_json "
            "FROM job_checkpoints WHERE job_id = ? ORDER BY sequence DESC LIMIT 1",
            (row["id"],),
        ).fetchone()
        public["latest_checkpoint"] = None if checkpoint is None else _checkpoint_row(checkpoint)
        if full_history:
            public["checkpoints"] = [_checkpoint_row(item) for item in connection.execute(
                "SELECT sequence, job_id, created_at, done, next_steps, blocker, pointers_json "
                "FROM job_checkpoints WHERE job_id = ? ORDER BY sequence", (row["id"],)
            )]
        notes_sql = ("SELECT id, body, worker, job, phase, kind, status, dismissal_reason, revision, created_at, updated_at "
                     "FROM job_notes WHERE (job = ? OR worker = ?) ")
        if not full_history:
            notes_sql += "AND status = 'pending' "
        notes_sql += "ORDER BY created_at DESC, id DESC"
        if not full_history:
            notes_sql += " LIMIT 100"
        notes = connection.execute(notes_sql, (row["id"], row["worker"])).fetchall()
        public["notes"] = [_row_dict(note, JOB_COLUMNS["job_notes"]) for note in notes]
        history_limit = None if full_history else 10
        public["history"] = self._history_rows(connection, row["id"], search=None, limit=history_limit, offset=0, include_brief=full_history)
        return public

    @staticmethod
    def _find_job(connection: sqlite3.Connection, identifier: str) -> sqlite3.Row:
        selected = _text(identifier, "job identifier", maximum=100)
        rows = connection.execute("SELECT * FROM jobs WHERE id = ? OR name = ?", (selected, selected)).fetchall()
        if len(rows) > 1:
            raise JobError("job selector matches more than one job; use an unambiguous ID or name")
        row = rows[0] if rows else None
        if row is None:
            raise JobNotFound(f"job {selected} was not found")
        return row

    @staticmethod
    def _expect_revision(row: sqlite3.Row, expected: int) -> None:
        if row["revision"] != expected:
            raise RevisionConflict(
                f"job {row['name']} is at revision {row['revision']}; expected revision {expected}"
            )

    @staticmethod
    def _current_brief(connection: sqlite3.Connection, job_id: str, brief_revision: int) -> str:
        row = connection.execute(
            "SELECT brief FROM job_briefs WHERE job_id = ? AND brief_revision = ?",
            (job_id, brief_revision),
        ).fetchone()
        if row is None:
            raise JobsSchemaIncompatible("current job brief version is missing")
        return row[0]

    @staticmethod
    def _current_brief_for_validation(connection: sqlite3.Connection, job_id: str, brief_revision: int) -> str:
        row = connection.execute(
            "SELECT brief FROM job_briefs WHERE job_id = ? AND brief_revision = ?",
            (job_id, brief_revision),
        ).fetchone()
        if row is None:
            raise JobsSchemaIncompatible("current job brief version is missing")
        return row[0]

    def _history_rows(
        self,
        connection: sqlite3.Connection,
        job_id: str,
        *,
        search: str | None,
        limit: int | None,
        offset: int,
        include_brief: bool = False,
    ) -> list[dict[str, Any]]:
        where = "r.job_id = ?"
        parameters: list[Any] = [job_id]
        if search is not None:
            folded = f"%{search.casefold()}%"
            where += " AND (lower(r.state_json) LIKE ? OR lower(b.brief) LIKE ?)"
            parameters.extend((folded, folded))
        needs_brief_table = include_brief or search is not None
        selected_columns = "r.revision, r.brief_revision, r.created_at, r.state_json"
        if include_brief:
            selected_columns += ", b.brief"
        source = "job_revisions AS r"
        if needs_brief_table:
            source += " JOIN job_briefs AS b ON b.job_id = r.job_id AND b.brief_revision = r.brief_revision"
        sql = f"SELECT {selected_columns} FROM {source} WHERE {where} ORDER BY r.revision DESC"
        if limit is not None:
            sql += " LIMIT ? OFFSET ?"
            parameters.extend((limit, offset))
        elif offset:
            sql += " LIMIT -1 OFFSET ?"
            parameters.append(offset)
        rows = connection.execute(sql, parameters).fetchall()
        result: list[dict[str, Any]] = []
        for row in rows:
            snapshot = _loads(row["state_json"], "job revision")
            if not isinstance(snapshot, Mapping) or set(snapshot) != _REVISION_FIELDS:
                raise JobsSchemaIncompatible("stored job revision has an invalid shape")
            item = {"revision": row["revision"], "brief_revision": row["brief_revision"], "created_at": row["created_at"], **snapshot}
            if include_brief:
                item["brief"] = row["brief"]
            result.append(item)
        return result

    def _revision_detail(self, connection: sqlite3.Connection, job_id: str, revision: int) -> dict[str, Any] | None:
        row = connection.execute(
            "SELECT revision, brief_revision, created_at, state_json "
            "FROM job_revisions WHERE job_id = ? AND revision = ?",
            (job_id, revision),
        ).fetchone()
        if row is None:
            return None
        snapshot = _loads(row["state_json"], "job revision")
        if not isinstance(snapshot, Mapping) or set(snapshot) != _REVISION_FIELDS:
            raise JobsSchemaIncompatible("stored job revision has an invalid shape")
        brief = self._current_brief(connection, job_id, row["brief_revision"])
        return {"revision": row["revision"], "brief_revision": row["brief_revision"], "created_at": row["created_at"], **snapshot, "brief": brief}


def _normalize_sql(value: str) -> str:
    return re.sub(r"\s+", " ", value.strip().rstrip(";")).casefold()


def _boolean(value: Any, label: str) -> bool:
    if not isinstance(value, bool):
        raise JobError(f"{label} must be a boolean")
    return value


def _bounded_integer(value: Any, label: str, *, minimum: int, maximum: int) -> int:
    if isinstance(value, bool) or not isinstance(value, int) or value < minimum or value > maximum:
        raise JobError(f"{label} must be an integer from {minimum} through {maximum}")
    return value


def _page_limit(value: Any) -> int | None:
    if value is None:
        return None
    return _bounded_integer(value, "limit", minimum=1, maximum=_MAX_PAGE)


def _row_dict(row: sqlite3.Row | Sequence[Any], columns: Sequence[str]) -> dict[str, Any]:
    if isinstance(row, sqlite3.Row):
        return {column: row[column] for column in columns}
    return dict(zip(columns, row, strict=True))


def _checkpoint_row(row: sqlite3.Row | Sequence[Any]) -> dict[str, Any]:
    result = _row_dict(row, JOB_COLUMNS["job_checkpoints"])
    result["pointers"] = _loads(result.pop("pointers_json"), "checkpoint pointers")
    return result


def _pointers(value: Mapping[str, Any] | None) -> dict[str, Any]:
    if value is None:
        return {}
    if not isinstance(value, Mapping):
        raise JobError("pointers must be an object")
    unknown = set(value) - _POINTER_FIELDS
    if unknown:
        raise JobError(f"unknown checkpoint pointers: {', '.join(sorted(unknown))}")
    result: dict[str, Any] = {}
    for key, raw in value.items():
        if raw is None or raw == "":
            continue
        if key == "pr" and isinstance(raw, int) and not isinstance(raw, bool):
            if raw <= 0:
                raise JobError("checkpoint PR pointer must be positive")
            result[key] = raw
        else:
            result[key] = _text(raw, f"checkpoint {key} pointer", maximum=1000)
    return result


def _prepare_checkpoint(value: Mapping[str, Any]) -> dict[str, Any]:
    if not isinstance(value, Mapping):
        raise JobError("checkpoint must be an object")
    unknown = set(value) - {"done", "next_steps", "blocker", "pointers"}
    if unknown:
        raise JobError(f"unknown checkpoint fields: {', '.join(sorted(unknown))}")
    if "done" not in value or "next_steps" not in value:
        raise JobError("a lane checkpoint requires done and next_steps")
    return {
        "done": _text(value["done"], "checkpoint done", maximum=10_000),
        "next_steps": _text(value["next_steps"], "checkpoint next_steps", maximum=10_000),
        "blocker": _text(value.get("blocker", ""), "checkpoint blocker", maximum=2000, allow_empty=True),
        "pointers": _pointers(value.get("pointers")),
    }


def _prepare_changes(changes: Mapping[str, Any]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in changes.items():
        if key == "title":
            result[key] = _text(value, key, maximum=300)
        elif key == "worker":
            result[key] = _worker(value)
        elif key == "workstream_id":
            result[key] = _optional_workstream_id(value)
        elif key == "chat_id":
            result[key] = _optional_chat_id(value)
        elif key == "status":
            result[key] = _status(value)
        elif key == "primary":
            result[key] = _boolean(value, key)
        elif key == "brief":
            result[key] = _text(value, key, maximum=_MAX_BRIEF, allow_empty=True)
        elif key in {"summary", "progress", "blocker"}:
            result[key] = _text(value, key, maximum=2000, allow_empty=True)
        elif key == "checklist":
            result[key] = _checklist(value, allow_missing_ids=True)
    return result


def _changes_for_row(current: Mapping[str, Any], prepared: Mapping[str, Any]) -> dict[str, Any]:
    database_names = {"worker": "worker", "workstream_id": "workstream_id", "title": "title", "status": "status", "chat_id": "chat_id", "summary": "summary", "progress": "progress", "blocker": "blocker"}
    result: dict[str, Any] = {}
    for key, value in prepared.items():
        if key == "brief":
            continue
        if key == "primary":
            if bool(current["is_primary"]) != value:
                result["is_primary"] = int(value)
        elif key == "checklist":
            serialized = _json(value)
            if serialized != current["checklist_json"]:
                result["checklist_json"] = serialized
        elif key in database_names and current[database_names[key]] != value:
            result[database_names[key]] = value
    return result


def _prepare_manifest(manifest: Mapping[str, Any]) -> tuple[dict[str, Any], list[dict[str, Any]], list[dict[str, Any]], list[dict[str, str]], str]:
    if not isinstance(manifest, Mapping):
        raise JobError("manifest must be an object")
    unknown = set(manifest) - _MANIFEST_FIELDS
    if unknown:
        raise JobError(f"unknown manifest fields: {', '.join(sorted(unknown))}")
    if manifest.get("version") != 1 or isinstance(manifest.get("version"), bool):
        raise JobError("manifest version must be 1")
    raw_jobs = manifest.get("jobs")
    if isinstance(raw_jobs, (str, bytes)) or not isinstance(raw_jobs, Sequence) or not raw_jobs:
        raise JobError("manifest jobs must be a non-empty list")
    provenance = manifest.get("provenance", {})
    if not isinstance(provenance, Mapping):
        raise JobError("manifest provenance must be an object")
    if len(provenance) > 40:
        raise JobError("manifest provenance may contain at most 40 fields")
    safe_provenance: dict[str, Any] = {}
    for key, value in provenance.items():
        selected_key = _text(key, "provenance key", maximum=100)
        if _is_secret_field(selected_key):
            raise JobError(f"manifest provenance field {selected_key} may contain credential material")
        if isinstance(value, str):
            safe_provenance[selected_key] = _text(value, f"provenance {selected_key}", maximum=2000, allow_empty=True)
        elif value is None or isinstance(value, (bool, int)):
            safe_provenance[selected_key] = value
        else:
            raise JobError("manifest provenance values must be text, numbers, booleans, or null")
    normalized_jobs: list[dict[str, Any]] = []
    seen_names: set[str] = set()
    source_contents: list[dict[str, str]] = []
    total_source_bytes = 0
    for index, raw_job in enumerate(raw_jobs):
        if not isinstance(raw_job, Mapping):
            raise JobError(f"manifest job {index} must be an object")
        extra = set(raw_job) - _MANIFEST_JOB_FIELDS
        if extra:
            raise JobError(f"unknown manifest job fields: {', '.join(sorted(extra))}")
        name = _slug(raw_job.get("name"))
        if name in seen_names:
            raise JobError(f"manifest contains duplicate job name {name}")
        seen_names.add(name)
        brief_path = _text(raw_job.get("brief_file"), f"manifest job {name} brief_file", maximum=1000)
        brief, source_bytes = _read_import_file(brief_path, f"brief for {name}", _MAX_BRIEF)
        total_source_bytes += len(source_bytes)
        source_contents.append({"path": brief_path, "content": brief, "role": "brief"})
        raw_sources = raw_job.get("source_files", ())
        if isinstance(raw_sources, (str, bytes)) or not isinstance(raw_sources, Sequence):
            raise JobError(f"manifest job {name} source_files must be a list of paths")
        if len(raw_sources) > 100:
            raise JobError(f"manifest job {name} may have at most 100 source_files")
        for source_index, source_path_value in enumerate(raw_sources):
            source_path = _text(source_path_value, f"manifest job {name} source_files[{source_index}]", maximum=1000)
            source_text, source_bytes = _read_import_file(source_path, f"source input for {name}", 2_000_000)
            total_source_bytes += len(source_bytes)
            source_contents.append({"path": source_path, "content": source_text, "role": "source"})
        if total_source_bytes > _MAX_SOURCE_TOTAL:
            raise JobError("manifest source inputs exceed 10 MB")
        status = _status(raw_job.get("status", "active"))
        primary = raw_job.get("primary", status not in {"parked", "completed"})
        primary = _boolean(primary, f"manifest job {name} primary")
        if status in {"parked", "completed"} and primary:
            raise JobError(f"manifest job {name} cannot be primary while {status}")
        prepared = {
            "name": name,
            "worker": _worker(raw_job.get("worker")),
            "workstream_id": _optional_workstream_id(raw_job.get("workstream_id")),
            "title": _text(raw_job.get("title"), f"manifest job {name} title", maximum=300),
            "brief": brief,
            "summary": _text(raw_job.get("summary", ""), f"manifest job {name} summary", maximum=2000, allow_empty=True),
            "status": status,
            "primary": primary,
            "chat_id": _optional_chat_id(raw_job.get("chat_id")),
            "checklist": _checklist(raw_job.get("checklist", ()), allow_missing_ids=True),
            "progress": _text(raw_job.get("progress", ""), f"manifest job {name} progress", maximum=2000, allow_empty=True),
            "blocker": _text(raw_job.get("blocker", ""), f"manifest job {name} blocker", maximum=2000, allow_empty=True),
        }
        normalized_jobs.append(prepared)
    raw_notes = manifest.get("notes", ())
    if isinstance(raw_notes, (str, bytes)) or not isinstance(raw_notes, Sequence):
        raise JobError("manifest notes must be a list")
    normalized_notes: list[dict[str, Any]] = []
    for index, raw_note in enumerate(raw_notes):
        if not isinstance(raw_note, Mapping):
            raise JobError(f"manifest note {index} must be an object")
        extra = set(raw_note) - _MANIFEST_NOTE_FIELDS
        if extra:
            raise JobError(f"unknown manifest note fields: {', '.join(sorted(extra))}")
        body = _text(raw_note.get("body"), f"manifest note {index} body", maximum=20_000)
        note_worker = None if raw_note.get("worker") is None else _worker(raw_note["worker"])
        note_job = None if raw_note.get("job") is None else _text(raw_note["job"], f"manifest note {index} job", maximum=100)
        note_phase = _text(raw_note.get("phase", ""), f"manifest note {index} phase", maximum=200, allow_empty=True) or None
        if note_worker is None and note_job is None and note_phase is None:
            raise JobError(f"manifest note {index} must have a worker, job, or phase scope")
        note_kind = raw_note.get("kind", "reminder")
        if not isinstance(note_kind, str) or note_kind not in NOTE_KINDS:
            raise JobError(f"manifest note {index} kind is invalid")
        normalized_notes.append({"body": body, "worker": note_worker, "job": note_job, "phase": note_phase, "kind": note_kind})
    normalized = {"version": 1, "provenance": safe_provenance, "jobs": normalized_jobs, "notes": normalized_notes}
    fingerprint_data = {"manifest": dict(manifest), "sources": source_contents}
    fingerprint = hashlib.sha256(_json(fingerprint_data).encode("utf-8")).hexdigest()
    return normalized, normalized_jobs, normalized_notes, source_contents, fingerprint


def _read_import_file(path_value: str, label: str, maximum: int) -> tuple[str, bytes]:
    path = Path(path_value).expanduser()
    try:
        file_stat = path.stat(follow_symlinks=False)
        if not path.is_file() or path.is_symlink() or file_stat.st_size > maximum:
            raise JobError(f"{label} must be a regular non-symlink file of at most {maximum} bytes")
        content = path.read_bytes()
        decoded = content.decode("utf-8")
    except JobError:
        raise
    except (OSError, UnicodeDecodeError) as exc:
        raise JobError(f"cannot read UTF-8 {label}") from exc
    _text(decoded, label, maximum=maximum, allow_empty=True)
    return decoded, content


def _is_secret_field(key: str) -> bool:
    normalized = re.sub(r"[^a-z0-9]", "", key.casefold())
    qualifiers = ("value", "material", "hash", "raw", "plaintext", "encoded", "encrypted")
    while normalized:
        qualifier = next((candidate for candidate in qualifiers if normalized.endswith(candidate)), None)
        if qualifier is None:
            break
        normalized = normalized[: -len(qualifier)]
    return any(
        normalized.endswith(suffix)
        for suffix in (
            "password", "passwd", "secret", "token", "credential", "credentials", "apikey",
            "accesskey", "secretkey", "privatekey", "signingkey", "encryptionkey", "accesstoken",
            "refreshtoken", "authtoken", "oauthtoken", "clientsecret", "clienttoken", "githubtoken",
            "bearertoken", "sessiontoken", "idtoken",
        )
    )


def _import_conflicts(connection: sqlite3.Connection, jobs: Sequence[Mapping[str, Any]]) -> list[str]:
    conflicts: list[str] = []
    names: set[str] = set()
    primaries: set[str] = set()
    for item in jobs:
        name = item["name"]
        if name in names:
            conflicts.append(f"duplicate name {name}")
        names.add(name)
        if connection.execute("SELECT 1 FROM jobs WHERE name = ?", (name,)).fetchone() is not None:
            conflicts.append(f"job name {name} already exists")
        if item["primary"]:
            worker = item["worker"]
            if worker in primaries:
                conflicts.append(f"multiple imported primary jobs for {worker}")
            primaries.add(worker)
            existing = connection.execute(
                "SELECT name FROM jobs WHERE worker = ? AND is_primary = 1", (worker,)
            ).fetchone()
            if existing is not None:
                conflicts.append(f"worker {worker} already has primary job {existing[0]}")
    return conflicts
