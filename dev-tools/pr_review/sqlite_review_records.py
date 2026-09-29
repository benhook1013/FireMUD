"""Explicit SQLite persistence for structured PR-review records.

This repository extends the controller's existing SQLite database. It does
not own legacy controller routes: callers must invoke :meth:`bootstrap` once,
after the controller database itself has been initialized. Route queries read
the current controller state alongside these records so migrated legacy
routes remain visible and retain their original authority. Review runs keep
source counts that can be updated while findings are adjudicated and become
immutable when explicitly finalized; target-side decisions and resolutions
are separate records.
"""

from __future__ import annotations

import contextlib
import dataclasses
import functools
import hashlib
import json
import os
import re
import sqlite3
from collections.abc import Mapping, Sequence
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Literal

from .sqlite_store import SQLITE_SCHEMA_VERSION, WRITER_BUILD
from .state import FindingRoute, ReviewState

ReviewChannel = Literal["hosted", "cli", "manual", "subagent"]
FindingDisposition = Literal["accepted", "routed", "rejected", "unresolved"]
_RECORDS_SCHEMA_VERSION = 6
_RECORDS_METADATA_TABLE = "review_records_metadata"
_RECORDS_TABLES = {
    _RECORDS_METADATA_TABLE,
    "review_runs",
    "findings",
    "finding_observations",
    "routes",
    "route_target_history",
    "decisions",
    "resolutions",
    "review_attempts",
    "review_artifacts",
    "source_decision_corrections",
    "provider_origins",
    "imported_artifacts",
    "historical_provider_gaps",
    "historical_gap_artifacts",
}


class ReviewRecordsError(ValueError):
    """Raised when review records are invalid or the database is incompatible."""


class AttemptNotFound(ReviewRecordsError):
    """Raised when an exact attempt ID is not present in the records store."""


class RecordsSchemaIncompatible(ReviewRecordsError):
    """Raised when the controller or review-records schema is incompatible."""


class RecordsNotBootstrapped(RecordsSchemaIncompatible):
    """Raised when the review-records schema has not been bootstrapped."""


def _translate_database_errors(method):
    """Keep SQLite failures at review-record boundaries in the public error type."""

    @functools.wraps(method)
    def wrapped(*args, **kwargs):
        try:
            return method(*args, **kwargs)
        except ReviewRecordsError:
            raise
        except sqlite3.DatabaseError as exc:
            raise ReviewRecordsError(f"SQLite failure in {method.__name__}") from exc

    return wrapped


def _reject_json_constant(value: str) -> None:
    raise ValueError(f"non-finite JSON number {value} is not allowed")


@dataclasses.dataclass(frozen=True)
class FindingObservation:
    """One finding observed in a source run.

    ``source_finding_key`` is stable for the same finding on the same source
    PR and channel, even when later runs observe it again. The observation is
    still stored per run, while a routed finding reuses its stable route.
    """

    source_finding_key: str
    title: str
    disposition: FindingDisposition = "unresolved"
    detail: str = ""
    target_pr: int | None = None

    def __post_init__(self) -> None:
        _safe_identifier(self.source_finding_key, "source_finding_key", maximum=200)
        _bounded_text(self.title, "title", maximum=300)
        _bounded_text(self.detail, "detail", maximum=1000, allow_empty=True)
        if not isinstance(self.disposition, str) or self.disposition not in {
            "accepted", "routed", "rejected", "unresolved"
        }:
            raise ReviewRecordsError("finding disposition is invalid")
        if self.target_pr is not None:
            _positive_pr(self.target_pr, "finding target PR")
        if self.disposition != "routed" and self.target_pr is not None:
            raise ReviewRecordsError("only routed findings may set a target PR")


_SECRET_PATTERNS = (
    re.compile(r"-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----", re.IGNORECASE),
    re.compile(r"\b(?:gh[pousr]_[A-Za-z0-9_]{20,}|github_pat_[A-Za-z0-9_]{20,})\b"),
    re.compile(r"\bAKIA[0-9A-Z]{16}\b"),
    re.compile(r"\bBearer\s+\S+", re.IGNORECASE),
    re.compile(r"\b(?:password|passwd|secret|api[_-]?key|access[_-]?token)\s*[:=]\s*\S+", re.IGNORECASE),
    re.compile(r"\beyJ[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\b"),
)
_SECRET_FIELD_SUFFIXES = (
    "password", "passwd", "secret", "token", "credential", "credentials",
    "apikey", "accesskey", "secretkey", "privatekey", "signingkey", "encryptionkey",
    "accesstoken", "refreshtoken", "authtoken", "oauthtoken", "clientsecret",
    "clienttoken", "githubtoken", "bearertoken", "sessiontoken", "idtoken",
)
_SECRET_FIELD_TRAILING_QUALIFIERS = ("value", "material", "hash", "raw", "plaintext", "encoded", "encrypted")
_REDACTED_CREDENTIAL = "[redacted credential]"


def _text(value: Any, label: str, *, maximum: int, allow_empty: bool = False) -> str:
    if not isinstance(value, str) or len(value) > maximum:
        raise ReviewRecordsError(f"{label} must be text of at most {maximum} characters")
    if (not allow_empty and not value.strip()) or any(ord(char) < 0x20 for char in value):
        raise ReviewRecordsError(f"{label} must be non-empty text without control characters")
    return value


def _bounded_text(value: Any, label: str, *, maximum: int, allow_empty: bool = False) -> str:
    value = _text(value, label, maximum=maximum, allow_empty=allow_empty)
    if any(pattern.search(value) for pattern in _SECRET_PATTERNS):
        raise ReviewRecordsError(f"{label} resembles credential or raw secret material")
    return value


def _is_secret_field(key: str) -> bool:
    """Return whether a JSON field name semantically identifies secret material."""

    normalized = re.sub(r"[^a-z0-9]", "", key.casefold())
    while normalized:
        qualifier = next(
            (candidate for candidate in _SECRET_FIELD_TRAILING_QUALIFIERS if normalized.endswith(candidate)),
            None,
        )
        if qualifier is None:
            break
        normalized = normalized[:-len(qualifier)]
    return any(normalized.endswith(suffix) for suffix in _SECRET_FIELD_SUFFIXES)


def _has_secret_field_value(value: Any) -> bool:
    if value is None or value == "" or value == _REDACTED_CREDENTIAL:
        return False
    return not isinstance(value, (dict, list, tuple)) or bool(value)


def _safe_identifier(value: Any, label: str, *, maximum: int) -> str:
    value = _text(value, label, maximum=maximum)
    if re.fullmatch(r"[0-9a-fA-F]{40}|[0-9a-fA-F]{64}|[0-9a-fA-F-]{36}", value):
        return value
    return _bounded_text(value, label, maximum=maximum)


def _timestamp(value: str | None, label: str, *, optional: bool = False) -> str | None:
    if value is None and optional:
        return None
    selected = _now() if value is None else _text(value, label, maximum=100)
    try:
        parsed = datetime.fromisoformat(selected.replace("Z", "+00:00"))
    except ValueError as exc:
        raise ReviewRecordsError(f"{label} must be an ISO-8601 timestamp") from exc
    if parsed.tzinfo is None or parsed.utcoffset() is None:
        raise ReviewRecordsError(f"{label} must include a timezone")
    return selected


def _positive_pr(value: Any, label: str = "PR") -> int:
    if isinstance(value, bool) or not isinstance(value, int) or value <= 0:
        raise ReviewRecordsError(f"{label} must be a positive integer")
    return value


def _json(value: Any) -> str:
    return json.dumps(value, ensure_ascii=True, sort_keys=True, separators=(",", ":"), allow_nan=False)


def _now() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z")


def _stable_id(kind: str, source_pr: int, channel: str, finding_key: str) -> str:
    value = "\0".join((kind, str(source_pr), channel, finding_key))
    return hashlib.sha256(value.encode("utf-8")).hexdigest()


def _redact_archive_text(value: str) -> tuple[str, int]:
    """Keep full review evidence while masking recognizable credentials."""

    redactions = 0

    def redact(match: re.Match[str]) -> str:
        nonlocal redactions
        redactions += 1
        return _REDACTED_CREDENTIAL

    for pattern in _SECRET_PATTERNS:
        value = pattern.sub(redact, value)
    return value, redactions


def _archive_artifact(kind: str, content: str) -> tuple[str, str, int]:
    limits = {
        "cli_events": 8 * 1024 * 1024,
        "cli_raw_output": 8 * 1024 * 1024,
        "cli_diagnostic": 128 * 1024,
        "hosted_review": 4 * 1024 * 1024,
        "hosted_comments": 8 * 1024 * 1024,
        "metadata": 128 * 1024,
    }
    if kind not in limits or not isinstance(content, str):
        raise ReviewRecordsError("review artifact kind or content is invalid")
    encoded = content.encode("utf-8")
    if len(encoded) > limits[kind]:
        raise ReviewRecordsError(f"{kind} exceeds its evidence size limit")
    source_digest = hashlib.sha256(encoded).hexdigest()
    if kind in {"cli_diagnostic", "cli_raw_output"}:
        stored, count = _redact_archive_text(content)
        return stored, source_digest, count
    if kind == "cli_events":
        events = []
        try:
            for line in content.split("\n"):
                line = line.removesuffix("\r")
                if line.strip():
                    event = json.loads(line, parse_constant=_reject_json_constant)
                    if not isinstance(event, dict):
                        raise ValueError("non-object event")
                    events.append(event)
        except RecursionError as exc:
            raise ReviewRecordsError("CLI events must be JSON objects, one per line") from exc
        except (ValueError, json.JSONDecodeError) as exc:
            raise ReviewRecordsError("CLI events must be JSON objects, one per line") from exc
        value: Any = events
    else:
        try:
            value = json.loads(content, parse_constant=_reject_json_constant)
        except RecursionError as exc:
            raise ReviewRecordsError(f"{kind} must be JSON") from exc
        except ValueError as exc:
            raise ReviewRecordsError(f"{kind} must be JSON") from exc

    def scrub(item: Any) -> tuple[Any, int]:
        if isinstance(item, str):
            return _redact_archive_text(item)
        if isinstance(item, list):
            output = []
            total = 0
            for child in item:
                result, count = scrub(child)
                output.append(result)
                total += count
            return output, total
        if isinstance(item, dict):
            output = {}
            total = 0
            for key, child in item.items():
                if not isinstance(key, str):
                    raise ReviewRecordsError("review artifact JSON keys must be strings")
                if _is_secret_field(key) and _has_secret_field_value(child):
                    output[key] = _REDACTED_CREDENTIAL
                    total += 1
                    continue
                result, count = scrub(child)
                output[key] = result
                total += count
            return output, total
        return item, 0

    try:
        scrubbed, redactions = scrub(value)
        if kind == "cli_events":
            stored = "".join(_json(event) + "\n" for event in scrubbed)
        else:
            stored = _json(scrubbed)
    except RecursionError as exc:
        raise ReviewRecordsError(f"{kind} JSON is too deeply nested") from exc
    return stored, source_digest, redactions


class SqliteReviewRecords:
    """Store review runs, finding observations, routes, decisions, and resolutions.

    The database must already be a compatible controller SQLite database.
    Construction is side-effect free. ``bootstrap`` is deliberately explicit
    and only creates this module's tables; all later reads are opened read-only.
    """

    def __init__(
        self,
        path: str | os.PathLike[str],
        *,
        writer_build: int = WRITER_BUILD,
        timeout: float = 10.0,
    ) -> None:
        if isinstance(writer_build, bool) or not isinstance(writer_build, int) or writer_build <= 0:
            raise ReviewRecordsError("writer_build must be a positive integer")
        if timeout <= 0:
            raise ReviewRecordsError("timeout must be positive")
        self.path = Path(path).expanduser().absolute()
        self.writer_build = writer_build
        self.timeout = timeout

    def bootstrap(self) -> None:
        """Create the records schema on a compatible controller DB, if absent."""

        self._require_regular_database()
        try:
            with contextlib.closing(self._connect(read_only=False)) as connection:
                connection.execute("BEGIN IMMEDIATE")
                self._require_controller_compatible(connection)
                tables = self._table_names(connection)
                if _RECORDS_METADATA_TABLE in tables:
                    self._require_compatible(connection)
                    self._raise_controller_writer_fence(connection)
                    connection.commit()
                    return
                partial = tables & _RECORDS_TABLES
                if partial:
                    raise ReviewRecordsError("review-records schema is partial and cannot be bootstrapped")
                self._create_schema(connection)
                self._raise_controller_writer_fence(connection)
                connection.commit()
        except ReviewRecordsError:
            raise
        except sqlite3.DatabaseError as exc:
            raise ReviewRecordsError("cannot bootstrap SQLite review records") from exc

    def migrate(self) -> None:
        """Upgrade existing v4/v5 records atomically and fence older state writers.

        This is an explicit offline cutover operation. It does not read or edit
        the controller's legacy capture directories and is safe to retry after
        the transaction commits.
        """

        self._require_regular_database()
        try:
            with contextlib.closing(self._connect(read_only=False)) as connection:
                connection.execute("BEGIN IMMEDIATE")
                self._require_controller_compatible(connection)
                row = connection.execute(
                    "SELECT records_schema_version FROM review_records_metadata WHERE singleton = 1"
                ).fetchone()
                if row is None:
                    raise ReviewRecordsError("review-records metadata row is missing")
                if row[0] == _RECORDS_SCHEMA_VERSION:
                    self._require_compatible(connection)
                    self._raise_controller_writer_fence(connection)
                    connection.commit()
                    return
                if row[0] not in {4, 5}:
                    raise ReviewRecordsError(f"unsupported review-records schema version {row[0]}")
                existing = self._table_names(connection)
                added = ({"review_attempts", "review_artifacts", "source_decision_corrections",
                          "provider_origins", "imported_artifacts", "historical_provider_gaps",
                          "historical_gap_artifacts"}
                         if row[0] == 4 else {"provider_origins", "imported_artifacts",
                                               "historical_provider_gaps", "historical_gap_artifacts"})
                required = _RECORDS_TABLES - added
                if not required <= existing or added & existing:
                    raise ReviewRecordsError("review-records schema is incomplete or partially upgraded")
                if row[0] == 4:
                    self._create_attempt_schema(connection)
                self._create_origin_schema(connection)
                self._create_historical_gap_schema(connection)
                self._raise_controller_writer_fence(connection)
                connection.execute(
                    "UPDATE review_records_metadata SET records_schema_version = ?, min_writer_build = ? "
                    "WHERE singleton = 1",
                    (_RECORDS_SCHEMA_VERSION, WRITER_BUILD),
                )
                self._require_compatible(connection)
                connection.commit()
        except ReviewRecordsError:
            raise
        except sqlite3.DatabaseError as exc:
            raise ReviewRecordsError("cannot migrate SQLite review records") from exc

    @_translate_database_errors
    def start_attempt(
        self,
        *,
        attempt_id: str,
        source_pr: int,
        channel: Literal["hosted", "cli", "subagent"],
        candidate_sha: str | None = None,
        started_at: str | None = None,
        metadata: Mapping[str, Any] | None = None,
    ) -> dict[str, Any]:
        """Record an attempt before a provider request or independent pass starts."""

        attempt_id = _safe_identifier(attempt_id, "attempt ID", maximum=100)
        source_pr = _positive_pr(source_pr)
        if channel not in {"hosted", "cli", "subagent"}:
            raise ReviewRecordsError("attempt channel is invalid")
        if candidate_sha is not None and not re.fullmatch(r"[0-9a-fA-F]{40}|[0-9a-fA-F]{64}", candidate_sha):
            raise ReviewRecordsError("attempt candidate SHA is invalid")
        started_at_was_supplied = started_at is not None
        started_at = _timestamp(started_at, "attempt start")
        try:
            serialized_metadata = _json(dict(metadata or {}))
        except (TypeError, ValueError) as exc:
            raise ReviewRecordsError("attempt metadata must be JSON") from exc
        metadata_json, _, redactions = _archive_artifact("metadata", serialized_metadata)
        if redactions:
            raise ReviewRecordsError("attempt metadata contains credential-shaped material")
        with self._write_connection() as connection:
            existing = connection.execute(
                "SELECT source_pr, channel, candidate_sha, state, started_at, metadata_json "
                "FROM review_attempts WHERE attempt_id = ?",
                (attempt_id,),
            ).fetchone()
            expected = (source_pr, channel, candidate_sha, "started", started_at, metadata_json)
            if existing is not None:
                existing_content = tuple(existing)
                expected_content = expected
                if not started_at_was_supplied:
                    existing_content = existing_content[:4] + existing_content[5:]
                    expected_content = expected_content[:4] + expected_content[5:]
                if existing_content != expected_content:
                    raise ReviewRecordsError("attempt ID already has different content or is terminal")
                return {"attempt_id": attempt_id, "state": "started", "idempotent_replay": True}
            connection.execute(
                "INSERT INTO review_attempts (attempt_id, source_pr, channel, candidate_sha, state, "
                "started_at, metadata_json) VALUES (?, ?, ?, ?, 'started', ?, ?)",
                (attempt_id, source_pr, channel, candidate_sha, started_at, metadata_json),
            )
        return {"attempt_id": attempt_id, "state": "started", "idempotent_replay": False}

    @_translate_database_errors
    def finish_attempt(
        self,
        attempt_id: str,
        *,
        state: Literal["completed", "failed", "rate_limited", "timed_out", "ambiguous"],
        finished_at: str | None = None,
        duration_seconds: int | None = None,
        exit_status: int | None = None,
        trigger_id: str | None = None,
        provider_review_id: str | None = None,
        checkpoint_id: str | None = None,
        diagnostic: str = "",
        artifacts: Mapping[str, str] | None = None,
        _connection: sqlite3.Connection | None = None,
    ) -> dict[str, Any]:
        """Finish an attempt and archive its evidence in one transaction."""

        attempt_id = _safe_identifier(attempt_id, "attempt ID", maximum=100)
        if state not in {"completed", "failed", "rate_limited", "timed_out", "ambiguous"}:
            raise ReviewRecordsError("terminal attempt state is invalid")
        finished_at_was_supplied = finished_at is not None
        finished_at = _timestamp(finished_at, "attempt finish")
        if duration_seconds is not None and (type(duration_seconds) is not int or duration_seconds < 0):
            raise ReviewRecordsError("attempt duration is invalid")
        if exit_status is not None and type(exit_status) is not int:
            raise ReviewRecordsError("attempt exit status is invalid")
        identifiers = []
        for label, value in (("trigger ID", trigger_id), ("provider review ID", provider_review_id),
                             ("checkpoint ID", checkpoint_id)):
            identifiers.append(None if value is None else _safe_identifier(value, label, maximum=100))
        trigger_id, provider_review_id, checkpoint_id = identifiers
        diagnostic = _bounded_text(diagnostic, "attempt diagnostic", maximum=1000, allow_empty=True)
        archived = {
            kind: _archive_artifact(kind, content)
            for kind, content in (artifacts or {}).items()
        }
        with (self._write_connection() if _connection is None
              else contextlib.nullcontext(_connection)) as connection:
            existing = connection.execute(
                "SELECT state, finished_at, duration_seconds, exit_status, trigger_id, provider_review_id, "
                "checkpoint_id, diagnostic FROM review_attempts WHERE attempt_id = ?",
                (attempt_id,),
            ).fetchone()
            if existing is None:
                raise ReviewRecordsError("attempt ID is not registered")
            expected = (state, finished_at, duration_seconds, exit_status, trigger_id,
                        provider_review_id, checkpoint_id, diagnostic)
            if existing[0] != "started":
                stored = {
                    row[0]: (row[1], row[2], row[3]) for row in connection.execute(
                        "SELECT kind, content, source_sha256, redactions FROM review_artifacts "
                        "WHERE attempt_id = ?", (attempt_id,)
                    )
                }
                existing_content = tuple(existing)
                expected_content = expected
                if not finished_at_was_supplied:
                    existing_content = existing_content[:1] + existing_content[2:]
                    expected_content = expected_content[:1] + expected_content[2:]
                if existing_content != expected_content or stored != archived:
                    raise ReviewRecordsError("terminal attempt replay has different content")
                return {"attempt_id": attempt_id, "state": state, "idempotent_replay": True}
            connection.execute(
                "UPDATE review_attempts SET state = ?, finished_at = ?, duration_seconds = ?, "
                "exit_status = ?, trigger_id = ?, provider_review_id = ?, checkpoint_id = ?, diagnostic = ? "
                "WHERE attempt_id = ?",
                (*expected, attempt_id),
            )
            for kind, (content, source_sha256, redactions) in archived.items():
                connection.execute(
                    "INSERT INTO review_artifacts VALUES (?, ?, ?, ?, ?)",
                    (attempt_id, kind, content, source_sha256, redactions),
                )
        return {"attempt_id": attempt_id, "state": state, "idempotent_replay": False}

    @_translate_database_errors
    def attempt_history(self, pr: int) -> list[dict[str, Any]]:
        """Return bounded metadata for all attempts on one PR, without private artifacts."""

        pr = _positive_pr(pr)
        self._require_regular_database()
        with contextlib.closing(self._connect(read_only=True)) as connection:
            self._require_compatible(connection)
            rows = connection.execute(
                "SELECT attempt_id, channel, candidate_sha, state, started_at, finished_at, "
                "duration_seconds, exit_status, trigger_id, provider_review_id, checkpoint_id, run_id, "
                "diagnostic FROM review_attempts WHERE source_pr = ? ORDER BY started_at, attempt_id",
                (pr,),
            ).fetchall()
        return [
            {"attempt_id": row[0], "channel": row[1], "candidate_sha": row[2], "state": row[3],
             "started_at": row[4], "finished_at": row[5], "duration_seconds": row[6],
             "exit_status": row[7], "trigger_id": row[8], "provider_review_id": row[9],
             "checkpoint_id": row[10], "run_id": row[11], "diagnostic": row[12]}
            for row in rows
        ]

    @_translate_database_errors
    def attempt(self, attempt_id: str) -> dict[str, Any]:
        """Read one attempt for exact retry or a direct subagent-pass command."""

        attempt_id = _safe_identifier(attempt_id, "attempt ID", maximum=100)
        self._require_regular_database()
        with contextlib.closing(self._connect(read_only=True)) as connection:
            self._require_compatible(connection)
            row = connection.execute(
                "SELECT source_pr, channel, candidate_sha, state, started_at, finished_at, "
                "run_id, metadata_json FROM review_attempts WHERE attempt_id = ?",
                (attempt_id,),
            ).fetchone()
        if row is None:
            raise AttemptNotFound("review attempt does not exist")
        try:
            metadata = json.loads(row[7])
        except json.JSONDecodeError as exc:
            raise ReviewRecordsError("review attempt metadata is malformed") from exc
        if not isinstance(metadata, dict):
            raise ReviewRecordsError("review attempt metadata is not an object")
        return {
            "attempt_id": attempt_id,
            "source_pr": row[0],
            "channel": row[1],
            "candidate_sha": row[2],
            "state": row[3],
            "started_at": row[4],
            "finished_at": row[5],
            "run_id": row[6],
            "metadata": metadata,
        }

    @_translate_database_errors
    def attempt_artifacts(self, attempt_id: str) -> dict[str, str]:
        """Read private archived evidence for exact recovery, outside ordinary history."""

        attempt_id = _safe_identifier(attempt_id, "attempt ID", maximum=100)
        self._require_regular_database()
        with contextlib.closing(self._connect(read_only=True)) as connection:
            self._require_compatible(connection)
            rows = connection.execute(
                "SELECT kind, content FROM review_artifacts WHERE attempt_id = ?",
                (attempt_id,),
            ).fetchall()
        return {kind: content for kind, content in rows}

    @_translate_database_errors
    def link_attempt_run(
        self, attempt_id: str, run_id: str, *, _connection: sqlite3.Connection | None = None
    ) -> None:
        """Bind one completed, attributable source run to its exact attempt."""

        attempt_id = _safe_identifier(attempt_id, "attempt ID", maximum=100)
        run_id = _safe_identifier(run_id, "run ID", maximum=100)
        with (self._write_connection() if _connection is None
              else contextlib.nullcontext(_connection)) as connection:
            attempt = connection.execute(
                "SELECT source_pr, channel, state, run_id FROM review_attempts WHERE attempt_id = ?",
                (attempt_id,),
            ).fetchone()
            run = connection.execute(
                "SELECT source_pr, channel FROM review_runs WHERE run_id = ?", (run_id,)
            ).fetchone()
            if attempt is None or run is None or attempt[0:2] != run or attempt[2] != "completed":
                raise ReviewRecordsError("attempt and completed source run do not match")
            if attempt[3] not in (None, run_id):
                raise ReviewRecordsError("attempt is already linked to a different run")
            connection.execute(
                "UPDATE review_attempts SET run_id = ? WHERE attempt_id = ?", (run_id, attempt_id)
            )

    @_translate_database_errors
    def complete_attempt_run(
        self,
        attempt_id: str,
        *,
        finish: Mapping[str, Any],
        run: Mapping[str, Any],
        finalize_empty: bool = False,
    ) -> dict[str, Any]:
        """Commit a completed attempt, its source run and their link together.

        A provider completion must never become terminal without its counted
        source run. Validation failures roll the entire transition back so a
        later exact replay can retry it.
        """

        attempt_id = _safe_identifier(attempt_id, "attempt ID", maximum=100)
        if run.get("run_id") != attempt_id:
            raise ReviewRecordsError("completed attempt must use its exact ID as the source run ID")
        if finish.get("state") != "completed" or run.get("outcome", "completed") != "completed":
            raise ReviewRecordsError("atomic completion requires a completed attempt and run")
        findings = run.get("findings")
        if finalize_empty and findings:
            raise ReviewRecordsError("only a zero-finding run may be finalized on completion")
        with self._write_connection() as connection:
            finished = self.finish_attempt(attempt_id, _connection=connection, **finish)
            recorded = self.record_run(_connection=connection, **run)
            self.link_attempt_run(attempt_id, attempt_id, _connection=connection)
            if finalize_empty:
                self.finalize_run(
                    attempt_id, finalized_at=run.get("finished_at"), _connection=connection
                )
        return {"attempt": finished, "run": recorded}

    @_translate_database_errors
    def recover_completed_attempt_run(
        self,
        attempt_id: str,
        *,
        run: Mapping[str, Any],
        finalize_empty: bool = False,
    ) -> dict[str, Any]:
        """Atomically link a pre-v6 terminal attempt to its exact recovered run."""

        attempt_id = _safe_identifier(attempt_id, "attempt ID", maximum=100)
        if run.get("run_id") != attempt_id:
            raise ReviewRecordsError("recovered source run must use the exact attempt ID")
        if run.get("channel") != "hosted" or run.get("outcome", "completed") != "completed":
            raise ReviewRecordsError("only a completed Hosted source run can be recovered")
        findings = run.get("findings")
        if finalize_empty and findings:
            raise ReviewRecordsError("only a zero-finding run may be finalized on recovery")
        with self._write_connection() as connection:
            attempt = connection.execute(
                "SELECT source_pr, channel, candidate_sha, state, run_id, trigger_id, "
                "provider_review_id FROM review_attempts WHERE attempt_id = ?", (attempt_id,)
            ).fetchone()
            if (
                attempt is None or attempt[1] != "hosted" or attempt[3] != "completed"
                or attempt[4] is not None or attempt[5] is None or attempt[6] is None
                or attempt[0] != run.get("source_pr") or attempt[2] != run.get("source_head")
            ):
                raise ReviewRecordsError("completed Hosted attempt is not recoverable by this run")
            archived = {
                row[0] for row in connection.execute(
                    "SELECT kind FROM review_artifacts WHERE attempt_id = ?", (attempt_id,)
                )
            }
            if not {"hosted_review", "hosted_comments", "metadata"} <= archived:
                raise ReviewRecordsError("completed Hosted attempt lacks its archived evidence")
            recorded = self.record_run(_connection=connection, **run)
            self.link_attempt_run(attempt_id, attempt_id, _connection=connection)
            if finalize_empty:
                self.finalize_run(attempt_id, finalized_at=run.get("finished_at"), _connection=connection)
        return recorded

    def link_provider_origin(
        self,
        *,
        repository: str,
        source_pr: int,
        channel: Literal["hosted", "cli"],
        provider_id: str,
        checkpoint_id: int,
        checkpoint_fingerprint: str,
        run_id: str,
    ) -> dict[str, Any]:
        """Bind imported source evidence to its immutable public origin."""

        if not isinstance(repository, str) or not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repository):
            raise ReviewRecordsError("provider repository is invalid")
        repository = repository.casefold()
        source_pr = _positive_pr(source_pr)
        if channel not in {"hosted", "cli"}:
            raise ReviewRecordsError("provider origin channel is invalid")
        provider_id = _safe_identifier(provider_id, "provider origin ID", maximum=120)
        checkpoint_id = _positive_pr(checkpoint_id, "checkpoint ID")
        if not isinstance(checkpoint_fingerprint, str) or not re.fullmatch(r"[0-9a-f]{64}", checkpoint_fingerprint):
            raise ReviewRecordsError("checkpoint fingerprint must be lowercase SHA-256")
        run_id = _safe_identifier(run_id, "run ID", maximum=100)
        try:
            with self._write_connection() as connection:
                run = connection.execute(
                    "SELECT source_pr, channel, outcome, attributable FROM review_runs WHERE run_id = ?",
                    (run_id,),
                ).fetchone()
                if run != (source_pr, channel, "completed", 1):
                    raise ReviewRecordsError("provider origin must link a completed attributable matching run")
                gap = connection.execute(
                    "SELECT channel, checkpoint_fingerprint FROM historical_provider_gaps "
                    "WHERE repository = ? AND source_pr = ? AND checkpoint_id = ?",
                    (repository, source_pr, checkpoint_id),
                ).fetchone()
                if gap is not None and gap != (channel, checkpoint_fingerprint):
                    raise ReviewRecordsError("provider origin conflicts with historical checkpoint evidence")
                columns = (
                    "repository, source_pr, channel, provider_id, checkpoint_id, "
                    "checkpoint_fingerprint, run_id"
                )
                by_checkpoint = connection.execute(
                    f"SELECT {columns} FROM provider_origins "
                    "WHERE repository = ? AND source_pr = ? AND checkpoint_id = ?",
                    (repository, source_pr, checkpoint_id),
                ).fetchone()
                by_provider = connection.execute(
                    f"SELECT {columns} FROM provider_origins "
                    "WHERE repository = ? AND source_pr = ? AND channel = ? AND provider_id = ?",
                    (repository, source_pr, channel, provider_id),
                ).fetchone()
                by_run = connection.execute(
                    f"SELECT {columns} FROM provider_origins WHERE run_id = ?", (run_id,)
                ).fetchone()
                expected = (repository, source_pr, channel, provider_id, checkpoint_id, checkpoint_fingerprint, run_id)
                existing = (by_checkpoint, by_provider, by_run)
                if any(row is not None for row in existing):
                    if any(row != expected for row in existing):
                        raise ReviewRecordsError(
                            "provider origin conflicts with existing checkpoint, provider, or run identity"
                        )
                    replay = True
                else:
                    connection.execute(
                        "INSERT INTO provider_origins VALUES (?, ?, ?, ?, ?, ?, ?)", expected
                    )
                    replay = False
        except ReviewRecordsError:
            raise
        except sqlite3.Error as exc:
            raise ReviewRecordsError("cannot link provider origin") from exc
        return {"run_id": run_id, "checkpoint_id": checkpoint_id, "provider_id": provider_id,
                "idempotent_replay": replay}

    @_translate_database_errors
    def archive_imported_artifacts(
        self, run_id: str, artifacts: Mapping[str, str]
    ) -> dict[str, Any]:
        """Retain historical provider evidence without fabricating a live attempt."""

        run_id = _safe_identifier(run_id, "run ID", maximum=100)
        if not isinstance(artifacts, Mapping) or not artifacts:
            raise ReviewRecordsError("imported provider artifacts are required")
        archived = {kind: _archive_artifact(kind, content) for kind, content in artifacts.items()}
        with self._write_connection() as connection:
            run = connection.execute(
                "SELECT channel, outcome, attributable FROM review_runs WHERE run_id = ?", (run_id,)
            ).fetchone()
            if run is None or run[0] not in {"hosted", "cli"} or run[1:] != ("completed", 1):
                raise ReviewRecordsError("artifacts require a completed attributable provider run")
            existing = {
                row[0]: (row[1], row[2], row[3]) for row in connection.execute(
                    "SELECT kind, content, source_sha256, redactions FROM imported_artifacts WHERE run_id = ?",
                    (run_id,),
                )
            }
            if existing:
                if existing != archived:
                    raise ReviewRecordsError("imported provider artifacts conflict with existing evidence")
                replay = True
            else:
                for kind, (content, digest, redactions) in archived.items():
                    connection.execute(
                        "INSERT INTO imported_artifacts VALUES (?, ?, ?, ?, ?)",
                        (run_id, kind, content, digest, redactions),
                    )
                replay = False
        return {"run_id": run_id, "kinds": sorted(archived), "idempotent_replay": replay}

    def record_historical_gap(
        self,
        *,
        repository: str,
        source_pr: int,
        channel: Literal["hosted", "cli"],
        checkpoint_id: int,
        checkpoint_fingerprint: str,
        checkpoint: Mapping[str, Any],
        artifacts: Mapping[str, str],
        missing_reason: str,
    ) -> dict[str, Any]:
        """Preserve a public checkpoint whose missing evidence prevents run attribution."""

        if not isinstance(repository, str) or not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repository):
            raise ReviewRecordsError("provider repository is invalid")
        repository = repository.casefold()
        source_pr = _positive_pr(source_pr)
        if channel not in {"hosted", "cli"}:
            raise ReviewRecordsError("historical gap channel is invalid")
        checkpoint_id = _positive_pr(checkpoint_id, "checkpoint ID")
        if not isinstance(checkpoint_fingerprint, str) or not re.fullmatch(r"[0-9a-f]{64}", checkpoint_fingerprint):
            raise ReviewRecordsError("checkpoint fingerprint must be lowercase SHA-256")
        if not isinstance(checkpoint, Mapping) or not checkpoint:
            raise ReviewRecordsError("historical checkpoint metadata is required")
        if not isinstance(artifacts, Mapping):
            raise ReviewRecordsError("historical source artifacts must be a mapping")
        missing_reason = _bounded_text(missing_reason, "missing evidence reason", maximum=1000)
        try:
            serialized_checkpoint = _json(dict(checkpoint))
        except (TypeError, ValueError) as exc:
            raise ReviewRecordsError("historical checkpoint metadata must be JSON") from exc
        checkpoint_json, checkpoint_digest, checkpoint_redactions = _archive_artifact(
            "metadata", serialized_checkpoint
        )
        archived = {kind: _archive_artifact(kind, content) for kind, content in artifacts.items()}
        expected = (
            repository, source_pr, channel, checkpoint_id, checkpoint_fingerprint,
            checkpoint_json, checkpoint_digest, checkpoint_redactions, missing_reason,
        )
        try:
            with self._write_connection() as connection:
                if connection.execute(
                    "SELECT 1 FROM provider_origins WHERE repository = ? AND source_pr = ? "
                    "AND checkpoint_id = ?", (repository, source_pr, checkpoint_id)
                ).fetchone():
                    raise ReviewRecordsError("historical gap conflicts with an attributed provider origin")
                existing = connection.execute(
                    "SELECT repository, source_pr, channel, checkpoint_id, checkpoint_fingerprint, "
                    "checkpoint_json, checkpoint_source_sha256, checkpoint_redactions, missing_reason "
                    "FROM historical_provider_gaps WHERE repository = ? AND source_pr = ? AND checkpoint_id = ?",
                    (repository, source_pr, checkpoint_id),
                ).fetchone()
                if existing is None:
                    connection.execute(
                        "INSERT INTO historical_provider_gaps VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)", expected
                    )
                    for kind, (content, digest, redactions) in archived.items():
                        connection.execute(
                            "INSERT INTO historical_gap_artifacts VALUES (?, ?, ?, ?, ?, ?, ?)",
                            (repository, source_pr, checkpoint_id, kind, content, digest, redactions),
                        )
                    replay = False
                else:
                    existing_artifacts = {
                        row[0]: (row[1], row[2], row[3]) for row in connection.execute(
                            "SELECT kind, content, source_sha256, redactions FROM historical_gap_artifacts "
                            "WHERE repository = ? AND source_pr = ? AND checkpoint_id = ?",
                            (repository, source_pr, checkpoint_id),
                        )
                    }
                    if existing != expected or existing_artifacts != archived:
                        raise ReviewRecordsError("historical gap conflicts with existing checkpoint evidence")
                    replay = True
        except ReviewRecordsError:
            raise
        except sqlite3.DatabaseError as exc:
            raise ReviewRecordsError("cannot record historical provider gap") from exc
        return {
            "repository": repository, "source_pr": source_pr, "channel": channel,
            "checkpoint_id": checkpoint_id, "kinds": sorted(archived), "idempotent_replay": replay,
        }

    @_translate_database_errors
    def cli_source_decisions(self, attempt_id: str) -> dict[int, tuple[str, str]] | None:
        """Read exact per-finding CLI decisions, or None for a legacy file-only run."""

        attempt_id = _safe_identifier(attempt_id, "attempt ID", maximum=100)
        self._require_regular_database()
        with contextlib.closing(self._connect(read_only=True)) as connection:
            self._require_compatible(connection)
            run = connection.execute(
                "SELECT run_id FROM review_attempts WHERE attempt_id = ? AND channel = 'cli'",
                (attempt_id,),
            ).fetchone()
            if run is None or run[0] is None:
                return None
            rows = connection.execute(
                "SELECT f.source_finding_key, COALESCE(c.decision, d.decision), "
                "COALESCE(c.reason, d.reason) "
                "FROM decisions d JOIN findings f ON f.finding_id = d.finding_id "
                "LEFT JOIN source_decision_corrections c ON c.sequence = ("
                "SELECT MAX(sequence) FROM source_decision_corrections "
                "WHERE run_id = d.run_id AND finding_id = d.finding_id) "
                "WHERE d.run_id = ? AND d.decision_scope = 'source'",
                (run[0],),
            ).fetchall()
        result: dict[int, tuple[str, str]] = {}
        prefix = f"cli-run:{attempt_id}:finding:"
        for key, decision, reason in rows:
            if not key.startswith(prefix) or not key[len(prefix):].isdigit():
                raise ReviewRecordsError("CLI source decision has an invalid finding key")
            index = int(key[len(prefix):])
            if index in result:
                raise ReviewRecordsError("CLI source decision index is duplicated")
            result[index] = (decision, reason)
        return result

    def correct_source_decision(
        self,
        run_id: str,
        source_finding_key: str,
        *,
        supersedes_id: str,
        correction_id: str,
        decision: Literal["accepted", "rejected"],
        actor: str,
        reason: str,
        decided_at: str | None = None,
    ) -> dict[str, Any]:
        """Append an exact-prior decision correction without deleting evidence.

        Route changes use the dedicated route commands. Changing a routed
        source finding requires separate owner adjudication and is refused here.
        """

        run_id = _safe_identifier(run_id, "run ID", maximum=100)
        source_finding_key = _safe_identifier(source_finding_key, "finding key", maximum=200)
        supersedes_id = _safe_identifier(supersedes_id, "prior decision ID", maximum=200)
        correction_id = _safe_identifier(correction_id, "correction ID", maximum=200)
        if decision not in {"accepted", "rejected"}:
            raise ReviewRecordsError("source correction must be accepted or rejected")
        actor = _bounded_text(actor, "actor", maximum=100)
        reason = _bounded_text(reason, "correction reason", maximum=300)
        decided_at = _timestamp(decided_at, "correction time")
        try:
            with self._write_connection() as connection:
                row = connection.execute(
                    "SELECT o.finding_id, o.disposition, r.finalized FROM finding_observations o "
                    "JOIN findings f USING (finding_id) JOIN review_runs r USING (run_id) "
                    "WHERE o.run_id = ? AND f.source_finding_key = ?",
                    (run_id, source_finding_key),
                ).fetchone()
                if row is None:
                    raise ReviewRecordsError("source finding was not observed in that run")
                finding_id, old_decision, finalized = row
                if old_decision not in {"accepted", "rejected"}:
                    raise ReviewRecordsError("routed and unresolved source findings need owner adjudication")
                initial = connection.execute(
                    "SELECT decision_id FROM decisions WHERE decision_scope = 'source' "
                    "AND run_id = ? AND finding_id = ?",
                    (run_id, finding_id),
                ).fetchone()
                latest = connection.execute(
                    "SELECT correction_id FROM source_decision_corrections WHERE run_id = ? AND finding_id = ? "
                    "ORDER BY sequence DESC LIMIT 1", (run_id, finding_id),
                ).fetchone()
                if initial is None or supersedes_id != (latest or initial)[0]:
                    raise ReviewRecordsError("correction does not name the latest exact decision")
                if connection.execute(
                    "SELECT 1 FROM source_decision_corrections WHERE correction_id = ?", (correction_id,)
                ).fetchone():
                    raise ReviewRecordsError("correction ID is already used")
                connection.execute(
                    "INSERT INTO source_decision_corrections "
                    "(correction_id, supersedes_id, run_id, finding_id, decision, target_pr, actor, reason, decided_at) "
                    "VALUES (?, ?, ?, ?, ?, NULL, ?, ?, ?)",
                    (correction_id, supersedes_id, run_id, finding_id, decision, actor, reason, decided_at),
                )
                connection.execute(
                    "UPDATE finding_observations SET disposition = ? WHERE run_id = ? AND finding_id = ?",
                    (decision, run_id, finding_id),
                )
                counts = self._current_run_counts(connection, run_id)
                connection.execute(
                    "UPDATE review_runs SET found_count = ?, accepted_count = ?, routed_count = ? WHERE run_id = ?",
                    (*counts, run_id),
                )
        except ReviewRecordsError:
            raise
        except sqlite3.IntegrityError as exc:
            raise ReviewRecordsError("source decision conflicts with existing immutable records") from exc
        except sqlite3.DatabaseError as exc:
            raise ReviewRecordsError("cannot record SQLite source decision") from exc
        return {
            "correction_id": correction_id, "run_id": run_id, "prior_decision_id": supersedes_id,
            "decision": decision, "counts": {"found": counts[0], "accepted": counts[1], "routed": counts[2]},
            "public_checkpoint_correction_required": bool(finalized),
        }

    def record_run(
        self,
        *,
        run_id: str,
        source_pr: int,
        channel: ReviewChannel,
        findings: Sequence[FindingObservation],
        outcome: Literal["completed", "incomplete", "failed"] = "completed",
        attributable: bool = True,
        source_head: str | None = None,
        reviewer: str = "manual",
        scope: Literal["broad", "narrow"] = "narrow",
        coverage_limits: Sequence[str] = (),
        started_at: str | None = None,
        finished_at: str | None = None,
        _connection: sqlite3.Connection | None = None,
    ) -> dict[str, Any]:
        """Persist one immutable source run and its findings atomically."""

        run_id = _safe_identifier(run_id, "run_id", maximum=100)
        source_pr = _positive_pr(source_pr, "source PR")
        if not isinstance(channel, str) or channel not in {"hosted", "cli", "manual", "subagent"}:
            raise ReviewRecordsError("run channel must be hosted, cli, manual, or subagent")
        if not isinstance(outcome, str) or outcome not in {"completed", "incomplete", "failed"}:
            raise ReviewRecordsError("run outcome is invalid")
        if not isinstance(attributable, bool):
            raise ReviewRecordsError("attributable must be boolean")
        if source_head is not None:
            source_head = _text(source_head, "source_head", maximum=64)
            if not re.fullmatch(r"[0-9a-fA-F]{40}|[0-9a-fA-F]{64}", source_head):
                raise ReviewRecordsError("source_head must be a full git commit identifier")
        reviewer = _bounded_text(reviewer, "reviewer", maximum=100)
        if not isinstance(scope, str) or scope not in {"broad", "narrow"}:
            raise ReviewRecordsError("scope must be broad or narrow")
        if isinstance(coverage_limits, (str, bytes)) or not isinstance(coverage_limits, Sequence):
            raise ReviewRecordsError("coverage_limits must be a sequence of bounded text values")
        coverage = tuple(
            _bounded_text(item, "coverage limit", maximum=200) for item in coverage_limits
        )
        if len(coverage) > 20:
            raise ReviewRecordsError("coverage_limits may contain at most 20 entries")
        supplied_started_at = started_at
        supplied_finished_at = finished_at
        started_at = _timestamp(started_at, "started_at")
        if outcome == "completed":
            finished_at = _timestamp(finished_at, "finished_at")
        else:
            finished_at = _timestamp(finished_at, "finished_at", optional=True)
        if isinstance(findings, (str, bytes)) or not isinstance(findings, Sequence):
            raise ReviewRecordsError("findings must be a sequence of FindingObservation values")
        observations = tuple(findings)
        if len(observations) > 200:
            raise ReviewRecordsError("a run may contain at most 200 findings")
        if any(not isinstance(item, FindingObservation) for item in observations):
            raise ReviewRecordsError("findings must contain only FindingObservation values")
        finding_keys = [item.source_finding_key for item in observations]
        if len(finding_keys) != len(set(finding_keys)):
            raise ReviewRecordsError("a run cannot contain the same stable finding more than once")

        immutable_payload = {
            "source_pr": source_pr,
            "channel": channel,
            "source_head": source_head,
            "reviewer": reviewer,
            "scope": scope,
            "coverage_limits": coverage,
            "outcome": outcome,
            "attributable": attributable,
            "started_at": supplied_started_at,
            "finished_at": supplied_finished_at,
            "findings": [
                {
                    "source_finding_key": item.source_finding_key,
                    "title": item.title,
                    "detail": item.detail,
                    "disposition": item.disposition,
                    "target_pr": item.target_pr,
                }
                for item in observations
            ],
        }
        payload_json = _json(immutable_payload)
        if len(payload_json.encode("utf-8")) > 512_000:
            raise ReviewRecordsError("review run metadata and findings exceed the 512 KB limit")

        counts = {
            "found_count": len(observations),
            "accepted_count": sum(item.disposition == "accepted" for item in observations),
            "routed_count": sum(item.disposition == "routed" for item in observations),
        }
        try:
            with (self._write_connection() if _connection is None
                  else contextlib.nullcontext(_connection)) as connection:
                existing = connection.execute(
                    "SELECT source_pr, channel, import_payload_json, found_count, accepted_count, "
                    "routed_count, finalized FROM review_runs WHERE run_id = ?",
                    (run_id,),
                ).fetchone()
                if existing is not None:
                    if existing[0] != source_pr or existing[1] != channel or existing[2] != payload_json:
                        raise ReviewRecordsError("run_id was already imported with different immutable content")
                    return {
                        "run_id": run_id,
                        "source_pr": source_pr,
                        "channel": channel,
                        "counts": {
                            "found": existing[3],
                            "accepted": existing[4],
                            "routed": existing[5],
                        },
                        "finalized": bool(existing[6]),
                        "idempotent_replay": True,
                    }
                connection.execute(
                    "INSERT INTO review_runs "
                    "(run_id, source_pr, channel, source_head, reviewer, scope, coverage_limits_json, outcome, "
                    "attributable, started_at, finished_at, found_count, accepted_count, routed_count, "
                    "finalized, finalized_at, import_payload_json) "
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, NULL, ?)",
                    (
                        run_id,
                        source_pr,
                        channel,
                        source_head,
                        reviewer,
                        scope,
                        _json(coverage),
                        outcome,
                        int(attributable),
                        started_at,
                        finished_at,
                        counts["found_count"],
                        counts["accepted_count"],
                        counts["routed_count"],
                        payload_json,
                    ),
                )
                for item in observations:
                    finding_id = _stable_id("finding", source_pr, channel, item.source_finding_key)
                    connection.execute(
                        "INSERT OR IGNORE INTO findings "
                        "(finding_id, source_pr, source_channel, source_finding_key, first_seen_at) "
                        "VALUES (?, ?, ?, ?, ?)",
                        (finding_id, source_pr, channel, item.source_finding_key, started_at),
                    )
                    route_id: str | None = None
                    if item.disposition == "routed":
                        route_id = _stable_id("route", source_pr, channel, item.source_finding_key)
                        self._record_route_observation(
                            connection,
                            route_id=route_id,
                            finding_id=finding_id,
                            source_pr=source_pr,
                            channel=channel,
                            target_pr=item.target_pr,
                            observed_at=started_at,
                        )
                    connection.execute(
                        "INSERT INTO finding_observations "
                        "(run_id, finding_id, source_pr, source_channel, title, detail, disposition, route_id) "
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                        (
                            run_id,
                            finding_id,
                            source_pr,
                            channel,
                            item.title,
                            item.detail,
                            item.disposition,
                            route_id,
                        ),
                    )
        except ReviewRecordsError:
            raise
        except sqlite3.IntegrityError as exc:
            raise ReviewRecordsError("review run conflicts with existing immutable records") from exc
        except sqlite3.DatabaseError as exc:
            raise ReviewRecordsError("cannot record SQLite review run") from exc
        return {
            "run_id": run_id,
            "source_pr": source_pr,
            "channel": channel,
            "counts": {
                "found": counts["found_count"],
                "accepted": counts["accepted_count"],
                "routed": counts["routed_count"],
            },
            "finalized": False,
            "idempotent_replay": False,
        }

    def record_source_decision(
        self,
        run_id: str,
        source_finding_key: str,
        *,
        decision_id: str,
        decision: Literal["accepted", "routed", "rejected"],
        actor: str,
        reason: str,
        target_pr: int | None = None,
        decided_at: str | None = None,
    ) -> dict[str, Any]:
        """Record a source finding decision before its run is finalized."""

        run_id = _safe_identifier(run_id, "run_id", maximum=100)
        source_finding_key = _safe_identifier(source_finding_key, "source_finding_key", maximum=200)
        decision_id = _safe_identifier(decision_id, "decision_id", maximum=200)
        if not isinstance(decision, str) or decision not in {"accepted", "routed", "rejected"}:
            raise ReviewRecordsError("source decision must be accepted, routed, or rejected")
        if target_pr is not None:
            target_pr = _positive_pr(target_pr, "target PR")
        if decision != "routed" and target_pr is not None:
            raise ReviewRecordsError("only routed source decisions may set a target PR")
        actor = _bounded_text(actor, "actor", maximum=100)
        reason = _bounded_text(reason, "reason", maximum=300)
        decided_at = _timestamp(decided_at, "decided_at")
        route_id: str | None = None
        try:
            with self._write_connection() as connection:
                finding = connection.execute(
                    "SELECT f.finding_id, o.source_pr, o.source_channel, r.finalized, o.disposition "
                    "FROM finding_observations o JOIN findings f USING (finding_id) "
                    "JOIN review_runs r USING (run_id) "
                    "WHERE o.run_id = ? AND f.source_finding_key = ?",
                    (run_id, source_finding_key),
                ).fetchone()
                if finding is None:
                    raise ReviewRecordsError("source finding was not observed in that run")
                finding_id, source_pr, channel, finalized, current_disposition = finding
                if finalized:
                    raise ReviewRecordsError("finalized source-run counts cannot be changed")
                prior = connection.execute(
                    "SELECT decision_id FROM decisions WHERE decision_scope = 'source' "
                    "AND run_id = ? AND finding_id = ?", (run_id, finding_id)
                ).fetchone()
                if prior is not None:
                    raise ReviewRecordsError("source finding is already decided; use an audited correction")
                if current_disposition == "routed" and decision != "routed":
                    raise ReviewRecordsError("a routed source finding cannot be withdrawn after creating its route")
                if decision == "routed":
                    route_id = _stable_id("route", source_pr, channel, source_finding_key)
                    self._record_route_observation(
                        connection,
                        route_id=route_id,
                        finding_id=finding_id,
                        source_pr=source_pr,
                        channel=channel,
                        target_pr=target_pr,
                        observed_at=decided_at,
                    )
                connection.execute(
                    "UPDATE finding_observations SET disposition = ?, route_id = ? "
                    "WHERE run_id = ? AND finding_id = ?",
                    (decision, route_id, run_id, finding_id),
                )
                connection.execute(
                    "INSERT INTO decisions (decision_id, decision_scope, run_id, finding_id, route_id, "
                    "decision_pr, target_pr, decision, actor, reason, decided_at) "
                    "VALUES (?, 'source', ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    (
                        decision_id,
                        run_id,
                        finding_id,
                        route_id,
                        source_pr,
                        target_pr,
                        decision,
                        actor,
                        reason,
                        decided_at,
                    ),
                )
                counts = self._current_run_counts(connection, run_id)
                connection.execute(
                    "UPDATE review_runs SET found_count = ?, accepted_count = ?, routed_count = ? "
                    "WHERE run_id = ? AND finalized = 0",
                    (*counts, run_id),
                )
        except ReviewRecordsError:
            raise
        except sqlite3.IntegrityError as exc:
            raise ReviewRecordsError("source decision conflicts with existing immutable records") from exc
        except sqlite3.DatabaseError as exc:
            raise ReviewRecordsError("cannot record SQLite source decision") from exc
        return {
            "decision_id": decision_id,
            "run_id": run_id,
            "source_finding_key": source_finding_key,
            "decision": decision,
            "route_id": route_id,
            "counts": {"found": counts[0], "accepted": counts[1], "routed": counts[2]},
        }

    def import_completed_run(
        self,
        *,
        run_id: str,
        source_pr: int,
        channel: ReviewChannel,
        findings: Sequence[FindingObservation],
        source_decisions: Sequence[Mapping[str, Any]],
        outcome: Literal["completed"] = "completed",
        attributable: bool = True,
        source_head: str | None = None,
        reviewer: str = "manual",
        scope: Literal["broad", "narrow"] = "narrow",
        coverage_limits: Sequence[str] = (),
        started_at: str | None = None,
        finished_at: str | None = None,
        finalized_at: str | None = None,
    ) -> dict[str, Any]:
        """Import and finalize one completed run in a single transaction.

        This is the only persistence entry point used by provider imports and
        complete curated batches. Every finding must have exactly one source
        decision; validation, finding/route creation, decision writes, and
        finalization share one transaction. Replaying the same immutable run
        and decisions is a no-op, while any conflicting replay is refused
        without changing the existing rows.
        """

        run_id = _safe_identifier(run_id, "run_id", maximum=100)
        source_pr = _positive_pr(source_pr, "source PR")
        if not isinstance(channel, str) or channel not in {"hosted", "cli", "manual", "subagent"}:
            raise ReviewRecordsError("run channel must be hosted, cli, manual, or subagent")
        if outcome != "completed":
            raise ReviewRecordsError("completed-run import requires a completed outcome")
        if not isinstance(attributable, bool) or not attributable:
            raise ReviewRecordsError("completed-run import requires an attributable run")
        if source_head is not None:
            source_head = _text(source_head, "source_head", maximum=64)
            if not re.fullmatch(r"[0-9a-fA-F]{40}|[0-9a-fA-F]{64}", source_head):
                raise ReviewRecordsError("source_head must be a full git commit identifier")
        reviewer = _bounded_text(reviewer, "reviewer", maximum=100)
        if not isinstance(scope, str) or scope not in {"broad", "narrow"}:
            raise ReviewRecordsError("scope must be broad or narrow")
        if isinstance(coverage_limits, (str, bytes)) or not isinstance(coverage_limits, Sequence):
            raise ReviewRecordsError("coverage_limits must be a sequence of bounded text values")
        coverage = tuple(_bounded_text(item, "coverage limit", maximum=200) for item in coverage_limits)
        if len(coverage) > 20:
            raise ReviewRecordsError("coverage_limits may contain at most 20 entries")
        supplied_started_at = started_at
        supplied_finished_at = finished_at
        started_at = _timestamp(started_at, "started_at")
        finished_at = _timestamp(finished_at, "finished_at")
        finalized_at = _timestamp(finalized_at, "finalized_at")
        if isinstance(findings, (str, bytes)) or not isinstance(findings, Sequence):
            raise ReviewRecordsError("findings must be a sequence of FindingObservation values")
        observations = tuple(findings)
        if len(observations) > 200:
            raise ReviewRecordsError("a run may contain at most 200 findings")
        if any(not isinstance(item, FindingObservation) for item in observations):
            raise ReviewRecordsError("findings must contain only FindingObservation values")
        finding_keys = [item.source_finding_key for item in observations]
        if len(finding_keys) != len(set(finding_keys)):
            raise ReviewRecordsError("a run cannot contain the same stable finding more than once")

        if isinstance(source_decisions, (str, bytes)) or not isinstance(source_decisions, Sequence):
            raise ReviewRecordsError("source_decisions must be a sequence of decision objects")
        if len(source_decisions) != len(observations):
            raise ReviewRecordsError("a completed run requires exactly one source decision per finding")
        observation_keys = set(finding_keys)
        normalized_decisions: list[dict[str, Any]] = []
        decision_keys: set[str] = set()
        decision_ids: set[str] = set()
        for item in source_decisions:
            if not isinstance(item, Mapping):
                raise ReviewRecordsError("source decisions must be objects")
            allowed = {
                "source_finding_key", "decision_id", "decision", "actor", "reason", "target_pr",
                "decided_at", "route_id", "route_status",
            }
            if set(item) - allowed or not {"source_finding_key", "decision_id", "decision", "actor", "reason"} <= set(item):
                raise ReviewRecordsError("source decision has missing or unsupported fields")
            source_finding_key = _safe_identifier(item["source_finding_key"], "source_finding_key", maximum=200)
            if source_finding_key not in observation_keys:
                raise ReviewRecordsError("source decision references an unobserved finding")
            if source_finding_key in decision_keys:
                raise ReviewRecordsError("a completed run cannot decide one finding more than once")
            decision_id = _safe_identifier(item["decision_id"], "decision_id", maximum=200)
            if decision_id in decision_ids:
                raise ReviewRecordsError("source decision IDs must be unique")
            decision = item["decision"]
            if not isinstance(decision, str) or decision not in {"accepted", "routed", "rejected"}:
                raise ReviewRecordsError("source decision must be accepted, routed, or rejected")
            target_pr = item.get("target_pr")
            if target_pr is not None:
                target_pr = _positive_pr(target_pr, "target PR")
            if decision != "routed" and target_pr is not None:
                raise ReviewRecordsError("only routed source decisions may set a target PR")
            route_id = item.get("route_id")
            route_status = item.get("route_status")
            if decision != "routed" and (route_id is not None or route_status is not None):
                raise ReviewRecordsError("only routed source decisions may reference an external route")
            if route_id is not None:
                route_id = _safe_identifier(route_id, "route_id", maximum=64)
            if route_status is not None and route_status not in {"open", "accepted_fixed", "rejected"}:
                raise ReviewRecordsError("source route status is invalid")
            if route_status is not None and route_id is None:
                raise ReviewRecordsError("source route status requires a route reference")
            actor = _bounded_text(item["actor"], "actor", maximum=100)
            reason = _bounded_text(item["reason"], "reason", maximum=300)
            supplied_decided_at = item.get("decided_at")
            decided_at = _timestamp(supplied_decided_at, "decided_at")
            decision_keys.add(source_finding_key)
            decision_ids.add(decision_id)
            normalized_decisions.append(
                {
                    "source_finding_key": source_finding_key,
                    "decision_id": decision_id,
                    "decision": decision,
                    "actor": actor,
                    "reason": reason,
                    "target_pr": target_pr,
                    "route_id": route_id,
                    "route_status": route_status,
                    "decided_at": decided_at,
                    "supplied_decided_at": supplied_decided_at is not None,
                }
            )
        if decision_keys != observation_keys:
            raise ReviewRecordsError("a completed run requires a decision for every finding")

        immutable_payload = {
            "source_pr": source_pr,
            "channel": channel,
            "source_head": source_head,
            "reviewer": reviewer,
            "scope": scope,
            "coverage_limits": coverage,
            "outcome": "completed",
            "attributable": attributable,
            "started_at": supplied_started_at,
            "finished_at": supplied_finished_at,
            "findings": [
                {
                    "source_finding_key": item.source_finding_key,
                    "title": item.title,
                    "detail": item.detail,
                    "disposition": item.disposition,
                    "target_pr": item.target_pr,
                }
                for item in observations
            ],
        }
        payload_json = _json(immutable_payload)
        if len(payload_json.encode("utf-8")) > 512_000:
            raise ReviewRecordsError("review run metadata and findings exceed the 512 KB limit")

        counts = {
            "found_count": len(observations),
            "accepted_count": sum(item.disposition == "accepted" for item in observations),
            "routed_count": sum(item.disposition == "routed" for item in observations),
        }
        try:
            with self._write_connection() as connection:
                existing = connection.execute(
                    "SELECT source_pr, channel, import_payload_json, found_count, accepted_count, "
                    "routed_count, finalized, finalized_at FROM review_runs WHERE run_id = ?",
                    (run_id,),
                ).fetchone()
                idempotent_replay = existing is not None
                if existing is not None:
                    if existing[0] != source_pr or existing[1] != channel or existing[2] != payload_json:
                        raise ReviewRecordsError("run_id was already imported with different immutable content")
                else:
                    connection.execute(
                        "INSERT INTO review_runs "
                        "(run_id, source_pr, channel, source_head, reviewer, scope, coverage_limits_json, outcome, "
                        "attributable, started_at, finished_at, found_count, accepted_count, routed_count, "
                        "finalized, finalized_at, import_payload_json) "
                        "VALUES (?, ?, ?, ?, ?, ?, ?, 'completed', 1, ?, ?, ?, ?, ?, 0, NULL, ?)",
                        (
                            run_id,
                            source_pr,
                            channel,
                            source_head,
                            reviewer,
                            scope,
                            _json(coverage),
                            started_at,
                            finished_at,
                            counts["found_count"],
                            counts["accepted_count"],
                            counts["routed_count"],
                            payload_json,
                        ),
                    )
                    for item in observations:
                        finding_id = _stable_id("finding", source_pr, channel, item.source_finding_key)
                        connection.execute(
                            "INSERT OR IGNORE INTO findings "
                            "(finding_id, source_pr, source_channel, source_finding_key, first_seen_at) "
                            "VALUES (?, ?, ?, ?, ?)",
                            (finding_id, source_pr, channel, item.source_finding_key, started_at),
                        )
                        route_id: str | None = None
                        if item.disposition == "routed":
                            route_id = _stable_id("route", source_pr, channel, item.source_finding_key)
                            self._record_route_observation(
                                connection,
                                route_id=route_id,
                                finding_id=finding_id,
                                source_pr=source_pr,
                                channel=channel,
                                target_pr=item.target_pr,
                                observed_at=started_at,
                            )
                        connection.execute(
                            "INSERT INTO finding_observations "
                            "(run_id, finding_id, source_pr, source_channel, title, detail, disposition, route_id) "
                            "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                            (
                                run_id,
                                finding_id,
                                source_pr,
                                channel,
                                item.title,
                                item.detail,
                                item.disposition,
                                route_id,
                            ),
                        )

                existing_decision_rows = list(
                    connection.execute(
                        "SELECT d.decision_id, f.source_finding_key, d.decision, d.actor, d.reason, "
                        "d.decided_at, d.route_id, d.target_pr "
                        "FROM decisions d JOIN findings f USING (finding_id) "
                        "WHERE d.decision_scope = 'source' AND d.run_id = ?",
                        (run_id,),
                    )
                )
                existing_decisions = {
                    row[1]: {
                        "decision_id": row[0],
                        "decision": row[2],
                        "actor": row[3],
                        "reason": row[4],
                        "decided_at": row[5],
                        "route_id": row[6],
                        "target_pr": row[7],
                    }
                    for row in existing_decision_rows
                }
                if len(existing_decisions) != len(existing_decision_rows):
                    raise ReviewRecordsError("existing source decisions conflict with this completed import")
                if set(existing_decisions) - decision_keys:
                    raise ReviewRecordsError("existing source decisions conflict with this completed import")

                for item in normalized_decisions:
                    prior = existing_decisions.get(item["source_finding_key"])
                    if prior is not None:
                        expected_route_id = None
                        if item["decision"] == "routed":
                            expected_route_id = item["route_id"] or _stable_id(
                                "route", source_pr, channel, item["source_finding_key"]
                            )
                        if (
                            prior["decision_id"] != item["decision_id"]
                            or prior["decision"] != item["decision"]
                            or prior["actor"] != item["actor"]
                            or prior["reason"] != item["reason"]
                            or prior["route_id"] != expected_route_id
                            # Externally identified legacy routes remain owned
                            # by the controller, so their shadow decision does
                            # not claim authority over later target changes.
                            or (item["route_id"] is None and prior["target_pr"] != item["target_pr"])
                            or (item["supplied_decided_at"] and prior["decided_at"] != item["decided_at"])
                        ):
                            raise ReviewRecordsError("existing source decision conflicts with this completed import")
                        continue
                    if existing is not None and existing[6]:
                        raise ReviewRecordsError("finalized provider run is missing one or more source decisions")
                    finding = connection.execute(
                        "SELECT f.finding_id, o.source_pr, o.source_channel, r.finalized, o.disposition "
                        "FROM finding_observations o JOIN findings f USING (finding_id) "
                        "JOIN review_runs r USING (run_id) "
                        "WHERE o.run_id = ? AND f.source_finding_key = ?",
                        (run_id, item["source_finding_key"]),
                    ).fetchone()
                    if finding is None:
                        raise ReviewRecordsError("source finding was not observed in that run")
                    finding_id, observed_pr, observed_channel, finalized, current_disposition = finding
                    if finalized:
                        raise ReviewRecordsError("finalized source-run counts cannot be changed")
                    if current_disposition == "routed" and item["decision"] != "routed":
                        raise ReviewRecordsError("a routed source finding cannot be withdrawn after creating its route")
                    route_id: str | None = None
                    if item["decision"] == "routed":
                        route_id = item["route_id"] or _stable_id(
                            "route", observed_pr, observed_channel, item["source_finding_key"]
                        )
                        self._record_route_observation(
                            connection,
                            route_id=route_id,
                            finding_id=finding_id,
                            source_pr=observed_pr,
                            channel=observed_channel,
                            target_pr=item["target_pr"],
                            observed_at=item["decided_at"],
                            route_status=item["route_status"],
                        )
                    connection.execute(
                        "UPDATE finding_observations SET disposition = ?, route_id = ? "
                        "WHERE run_id = ? AND finding_id = ?",
                        (item["decision"], route_id, run_id, finding_id),
                    )
                    connection.execute(
                        "INSERT INTO decisions (decision_id, decision_scope, run_id, finding_id, route_id, "
                        "decision_pr, target_pr, decision, actor, reason, decided_at) "
                        "VALUES (?, 'source', ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                        (
                            item["decision_id"],
                            run_id,
                            finding_id,
                            route_id,
                            observed_pr,
                            item["target_pr"],
                            item["decision"],
                            item["actor"],
                            item["reason"],
                            item["decided_at"],
                        ),
                    )

                final_counts = self._current_run_counts(connection, run_id)
                unresolved = connection.execute(
                    "SELECT COUNT(*) FROM finding_observations "
                    "WHERE run_id = ? AND disposition = 'unresolved'",
                    (run_id,),
                ).fetchone()[0]
                if unresolved:
                    raise ReviewRecordsError("all source findings must be decided before completed-run import")
                final_timestamp = existing[7] if existing is not None and existing[7] is not None else finalized_at
                connection.execute(
                    "UPDATE review_runs SET found_count = ?, accepted_count = ?, routed_count = ?, "
                    "finalized = 1, finalized_at = ? WHERE run_id = ? AND finalized = 0",
                    (*final_counts, final_timestamp, run_id),
                )
                return {
                    "run_id": run_id,
                    "source_pr": source_pr,
                    "channel": channel,
                    "finalized": True,
                    "finalized_at": final_timestamp,
                    "counts": {
                        "found": final_counts[0],
                        "accepted": final_counts[1],
                        "routed": final_counts[2],
                    },
                    "idempotent_replay": idempotent_replay,
                }
        except ReviewRecordsError:
            raise
        except sqlite3.IntegrityError as exc:
            raise ReviewRecordsError("completed run conflicts with existing immutable records") from exc
        except sqlite3.DatabaseError as exc:
            raise ReviewRecordsError("cannot import completed SQLite review run") from exc

    def finalize_run(
        self, run_id: str, *, finalized_at: str | None = None,
        _connection: sqlite3.Connection | None = None,
    ) -> dict[str, Any]:
        """Freeze the source counts after every finding has a source disposition."""

        run_id = _safe_identifier(run_id, "run_id", maximum=100)
        finalized_at = _timestamp(finalized_at, "finalized_at")
        try:
            with (self._write_connection() if _connection is None
                  else contextlib.nullcontext(_connection)) as connection:
                run = connection.execute(
                    "SELECT finalized, finalized_at FROM review_runs WHERE run_id = ?", (run_id,)
                ).fetchone()
                if run is None:
                    raise ReviewRecordsError("review run does not exist")
                counts = self._current_run_counts(connection, run_id)
                unresolved = connection.execute(
                    "SELECT COUNT(*) FROM finding_observations "
                    "WHERE run_id = ? AND disposition = 'unresolved'",
                    (run_id,),
                ).fetchone()[0]
                if unresolved:
                    raise ReviewRecordsError("all source findings must be decided before run finalization")
                if not run[0]:
                    connection.execute(
                        "UPDATE review_runs SET found_count = ?, accepted_count = ?, routed_count = ?, "
                        "finalized = 1, finalized_at = ? WHERE run_id = ? AND finalized = 0",
                        (*counts, finalized_at, run_id),
                    )
                else:
                    finalized_at = run[1]
        except ReviewRecordsError:
            raise
        except sqlite3.DatabaseError as exc:
            raise ReviewRecordsError("cannot finalize SQLite review run") from exc
        return {
            "run_id": run_id,
            "finalized": True,
            "finalized_at": finalized_at,
            "counts": {"found": counts[0], "accepted": counts[1], "routed": counts[2]},
        }

    @staticmethod
    def _current_run_counts(connection: sqlite3.Connection, run_id: str) -> tuple[int, int, int]:
        row = connection.execute(
            "SELECT COUNT(*), "
            "COALESCE(SUM(CASE WHEN disposition = 'accepted' THEN 1 ELSE 0 END), 0), "
            "COALESCE(SUM(CASE WHEN disposition = 'routed' THEN 1 ELSE 0 END), 0) "
            "FROM finding_observations WHERE run_id = ?",
            (run_id,),
        ).fetchone()
        return (row[0], row[1], row[2])

    def retarget_route(
        self,
        route_id: str,
        *,
        target_pr: int | None,
        actor: str,
        reason: str,
        changed_at: str | None = None,
    ) -> dict[str, Any]:
        """Change an open route's owner while retaining its target history."""

        route_id = _text(route_id, "route_id", maximum=64)
        if target_pr is not None:
            target_pr = _positive_pr(target_pr, "target PR")
        actor = _bounded_text(actor, "actor", maximum=100)
        reason = _bounded_text(reason, "reason", maximum=300)
        changed_at = _timestamp(changed_at, "changed_at")
        try:
            with self._write_connection() as connection:
                self._reject_legacy_route_write(connection, route_id)
                row = connection.execute(
                    "SELECT source_pr, source_channel, status FROM routes WHERE route_id = ?", (route_id,)
                ).fetchone()
                if row is None:
                    raise ReviewRecordsError("route does not exist")
                if row[2] != "open":
                    raise ReviewRecordsError("only open routes can be retargeted")
                connection.execute("UPDATE routes SET target_pr = ?, updated_at = ? WHERE route_id = ?", (target_pr, changed_at, route_id))
                connection.execute(
                    "INSERT INTO route_target_history (route_id, target_pr, changed_at, actor, reason) "
                    "VALUES (?, ?, ?, ?, ?)",
                    (route_id, target_pr, changed_at, actor, reason),
                )
        except ReviewRecordsError:
            raise
        except sqlite3.DatabaseError as exc:
            raise ReviewRecordsError("cannot retarget SQLite review route") from exc
        return {"route_id": route_id, "source_pr": row[0], "source_channel": row[1], "target_pr": target_pr}

    def record_decision(
        self,
        route_id: str,
        *,
        decision_id: str,
        decision_pr: int,
        decision: Literal["accepted", "rejected", "deferred"],
        actor: str,
        reason: str,
        decided_at: str | None = None,
    ) -> dict[str, Any]:
        """Append a target-side decision without changing source-run counts."""

        route_id = _text(route_id, "route_id", maximum=64)
        decision_id = _text(decision_id, "decision_id", maximum=200)
        decision_pr = _positive_pr(decision_pr, "decision PR")
        if not isinstance(decision, str) or decision not in {"accepted", "rejected", "deferred"}:
            raise ReviewRecordsError("target decision is invalid")
        actor = _bounded_text(actor, "actor", maximum=100)
        reason = _bounded_text(reason, "reason", maximum=300)
        decided_at = _timestamp(decided_at, "decided_at")
        try:
            with self._write_connection() as connection:
                self._reject_legacy_route_write(connection, route_id)
                route = connection.execute(
                    "SELECT finding_id, target_pr, status FROM routes WHERE route_id = ?",
                    (route_id,),
                ).fetchone()
                if route is None:
                    raise ReviewRecordsError("route does not exist")
                if route[2] != "open":
                    raise ReviewRecordsError("target decisions require an open route")
                if route[1] not in (None, decision_pr):
                    raise ReviewRecordsError("target decision PR does not own the route")
                if route[1] is None:
                    connection.execute(
                        "UPDATE routes SET target_pr = ?, updated_at = ? WHERE route_id = ?",
                        (decision_pr, decided_at, route_id),
                    )
                    connection.execute(
                        "INSERT INTO route_target_history (route_id, target_pr, changed_at, actor, reason) "
                        "VALUES (?, ?, ?, ?, ?)",
                        (route_id, decision_pr, decided_at, actor, "assigned when target decision was recorded"),
                    )
                connection.execute(
                    "INSERT INTO decisions (decision_id, decision_scope, run_id, finding_id, route_id, "
                    "decision_pr, target_pr, decision, actor, reason, decided_at) "
                    "VALUES (?, 'target', NULL, ?, ?, ?, ?, ?, ?, ?, ?)",
                    (decision_id, route[0], route_id, decision_pr, decision_pr, decision, actor, reason, decided_at),
                )
        except ReviewRecordsError:
            raise
        except sqlite3.IntegrityError as exc:
            raise ReviewRecordsError("target decision conflicts with existing immutable records") from exc
        except sqlite3.DatabaseError as exc:
            raise ReviewRecordsError("cannot record SQLite target decision") from exc
        return {"decision_id": decision_id, "route_id": route_id, "decision_pr": decision_pr, "decision": decision}

    def record_resolution(
        self,
        route_id: str,
        *,
        resolution_id: str,
        resolution_pr: int,
        outcome: Literal["accepted_fixed", "rejected"],
        actor: str,
        proof_or_reason: str,
        resolved_at: str | None = None,
    ) -> dict[str, Any]:
        """Resolve an assigned route while preserving its source-run snapshot."""

        route_id = _text(route_id, "route_id", maximum=64)
        resolution_id = _text(resolution_id, "resolution_id", maximum=200)
        resolution_pr = _positive_pr(resolution_pr, "resolution PR")
        if not isinstance(outcome, str) or outcome not in {"accepted_fixed", "rejected"}:
            raise ReviewRecordsError("resolution outcome is invalid")
        actor = _bounded_text(actor, "actor", maximum=100)
        proof_or_reason = _bounded_text(proof_or_reason, "proof_or_reason", maximum=300)
        resolved_at = _timestamp(resolved_at, "resolved_at")
        try:
            with self._write_connection() as connection:
                self._reject_legacy_route_write(connection, route_id)
                route = connection.execute(
                    "SELECT target_pr, status FROM routes WHERE route_id = ?", (route_id,)
                ).fetchone()
                if route is None:
                    raise ReviewRecordsError("route does not exist")
                if route[1] != "open":
                    raise ReviewRecordsError("route is already resolved")
                if route[0] is None or route[0] != resolution_pr:
                    raise ReviewRecordsError("resolution PR must be the route's current target")
                connection.execute(
                    "INSERT INTO resolutions (resolution_id, route_id, resolution_pr, outcome, actor, "
                    "proof_or_reason, resolved_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                    (resolution_id, route_id, resolution_pr, outcome, actor, proof_or_reason, resolved_at),
                )
                connection.execute(
                    "UPDATE routes SET status = ?, updated_at = ? WHERE route_id = ?",
                    (outcome, resolved_at, route_id),
                )
        except ReviewRecordsError:
            raise
        except sqlite3.IntegrityError as exc:
            raise ReviewRecordsError("resolution conflicts with existing immutable records") from exc
        except sqlite3.DatabaseError as exc:
            raise ReviewRecordsError("cannot resolve SQLite review route") from exc
        return {"resolution_id": resolution_id, "route_id": route_id, "resolution_pr": resolution_pr, "outcome": outcome}

    def history(
        self, pr: int, *, include_legacy_routes: bool = False,
        _connection: sqlite3.Connection | None = None,
    ) -> dict[str, Any]:
        """Return machine-readable source and incoming route history for one PR.

        The query includes routes whose source PR is no longer active or has
        merged. Merge state is intentionally not consulted or stored here.
        When requested, legacy controller routes are read through from the
        canonical state document; they are not copied into structured runs or
        findings.
        """

        pr = _positive_pr(pr)
        if not isinstance(include_legacy_routes, bool):
            raise ReviewRecordsError("include_legacy_routes must be boolean")
        try:
            with (contextlib.closing(self._connect(read_only=True)) if _connection is None
                  else contextlib.nullcontext(_connection)) as connection:
                if _connection is None:
                    connection.execute("BEGIN")
                self._require_compatible(connection)
                controller_state = self._controller_state(connection) if include_legacy_routes else None
                runs = [
                    self._run_record(row)
                    for row in connection.execute(
                        "SELECT run_id, source_pr, channel, source_head, reviewer, scope, coverage_limits_json, "
                        "outcome, attributable, started_at, finished_at, found_count, accepted_count, "
                        "routed_count, finalized, finalized_at "
                        "FROM review_runs WHERE source_pr = ? ORDER BY started_at, run_id",
                        (pr,),
                    )
                ]
                observations = [
                    self._observation_record(row)
                    for row in connection.execute(
                        "SELECT o.run_id, o.finding_id, o.source_pr, o.source_channel, f.source_finding_key, "
                        "o.title, o.detail, o.disposition, o.route_id "
                        "FROM finding_observations o JOIN findings f USING (finding_id) "
                        "WHERE o.source_pr = ? ORDER BY o.run_id, o.finding_id",
                        (pr,),
                    )
                ]
                routes = self._routes_for_pr(connection, pr)
                if controller_state is not None:
                    legacy_route_ids = {route.route_id for route in controller_state.routes}
                    # Hosted summary imports mirror legacy routes for SQLite
                    # foreign keys. The controller copy is authoritative and
                    # can be retargeted independently, so never expose a stale
                    # shadow row under its former target.
                    routes = [route for route in routes if route["route_id"] not in legacy_route_ids]
                    routes.extend(
                        self._legacy_route_record(route)
                        for route in controller_state.routes
                        if route.source_pr == pr or route.target_pr == pr
                    )
                routes = self._deduplicate_routes(routes)
                decisions = [
                    {
                        "decision_id": row[0],
                        "scope": row[1],
                        "run_id": row[2],
                        "finding_id": row[3],
                        "route_id": row[4],
                        "decision_pr": row[5],
                        "target_pr": row[6],
                        "decision": row[7],
                        "actor": row[8],
                        "reason": row[9],
                        "decided_at": row[10],
                    }
                    for row in connection.execute(
                        "SELECT decision_id, decision_scope, run_id, finding_id, route_id, decision_pr, target_pr, "
                        "decision, actor, reason, decided_at FROM decisions WHERE decision_pr = ? "
                        "ORDER BY decided_at, decision_id",
                        (pr,),
                    )
                ]
                attempts = []
                for row in connection.execute(
                    "SELECT attempt_id, channel, candidate_sha, state, started_at, finished_at, "
                    "duration_seconds, exit_status, trigger_id, provider_review_id, checkpoint_id, run_id, "
                    "diagnostic, metadata_json FROM review_attempts WHERE source_pr = ? "
                    "ORDER BY started_at, attempt_id",
                    (pr,),
                ):
                    try:
                        metadata = json.loads(row[13])
                    except json.JSONDecodeError as exc:
                        raise ReviewRecordsError("review attempt metadata is malformed") from exc
                    if not isinstance(metadata, dict):
                        raise ReviewRecordsError("review attempt metadata is not an object")
                    attempts.append(
                        {"attempt_id": row[0], "channel": row[1], "candidate_sha": row[2],
                         "state": row[3], "started_at": row[4], "finished_at": row[5],
                         "duration_seconds": row[6], "exit_status": row[7], "trigger_id": row[8],
                         "provider_review_id": row[9], "checkpoint_id": row[10], "run_id": row[11],
                         "diagnostic": row[12], "origin": metadata.get("origin"),
                         "legacy_outcome": metadata.get("legacy_outcome")}
                    )
                corrections = [
                    {"correction_id": row[0], "supersedes_id": row[1], "run_id": row[2],
                     "finding_id": row[3], "decision": row[4], "target_pr": row[5],
                     "actor": row[6], "reason": row[7], "decided_at": row[8]}
                    for row in connection.execute(
                        "SELECT c.correction_id, c.supersedes_id, c.run_id, c.finding_id, c.decision, "
                        "c.target_pr, c.actor, c.reason, c.decided_at "
                        "FROM source_decision_corrections c JOIN review_runs r USING (run_id) "
                        "WHERE r.source_pr = ? ORDER BY c.sequence", (pr,)
                    )
                ]
                provider_origins = [
                    {"repository": row[0], "source_pr": row[1], "channel": row[2],
                     "provider_id": row[3], "checkpoint_id": row[4],
                     "checkpoint_fingerprint": row[5], "run_id": row[6]}
                    for row in connection.execute(
                        "SELECT repository, source_pr, channel, provider_id, checkpoint_id, "
                        "checkpoint_fingerprint, run_id FROM provider_origins "
                        "WHERE source_pr = ? ORDER BY checkpoint_id", (pr,)
                    )
                ]
                imported_artifacts = [
                    {"run_id": row[0], "kind": row[1], "source_sha256": row[2],
                     "redactions": row[3]}
                    for row in connection.execute(
                        "SELECT a.run_id, a.kind, a.source_sha256, a.redactions "
                        "FROM imported_artifacts a JOIN review_runs r USING (run_id) "
                        "WHERE r.source_pr = ? ORDER BY a.run_id, a.kind", (pr,)
                    )
                ]
                historical_gaps = [
                    {"repository": row[0], "source_pr": row[1], "channel": row[2],
                     "checkpoint_id": row[3], "checkpoint_fingerprint": row[4],
                     "checkpoint": json.loads(row[5]), "checkpoint_source_sha256": row[6],
                     "checkpoint_redactions": row[7], "missing_reason": row[8],
                     "superseded_by_run_id": row[9]}
                    for row in connection.execute(
                        "SELECT g.repository, g.source_pr, g.channel, g.checkpoint_id, "
                        "g.checkpoint_fingerprint, g.checkpoint_json, g.checkpoint_source_sha256, "
                        "g.checkpoint_redactions, g.missing_reason, o.run_id "
                        "FROM historical_provider_gaps g LEFT JOIN provider_origins o "
                        "ON o.repository = g.repository AND o.source_pr = g.source_pr "
                        "AND o.checkpoint_id = g.checkpoint_id "
                        "WHERE g.source_pr = ? ORDER BY g.checkpoint_id", (pr,)
                    )
                ]
                historical_gap_artifacts = [
                    {"repository": row[0], "source_pr": row[1], "checkpoint_id": row[2],
                     "kind": row[3], "source_sha256": row[4], "redactions": row[5]}
                    for row in connection.execute(
                        "SELECT repository, source_pr, checkpoint_id, kind, "
                        "source_sha256, redactions FROM historical_gap_artifacts "
                        "WHERE source_pr = ? ORDER BY checkpoint_id, kind", (pr,)
                    )
                ]
                return {
                    "pr": pr,
                    "runs": runs,
                    "findings": observations,
                    "routes": routes,
                    "decisions": decisions,
                    "attempts": attempts,
                    "corrections": corrections,
                    "provider_origins": provider_origins,
                    "imported_artifacts": imported_artifacts,
                    "historical_gaps": historical_gaps,
                    "historical_gap_artifacts": historical_gap_artifacts,
                }
        except ReviewRecordsError:
            raise
        except (OSError, sqlite3.DatabaseError, json.JSONDecodeError) as exc:
            raise ReviewRecordsError("cannot read SQLite review history") from exc

    def history_batch(
        self, prs: Sequence[int], *, include_legacy_routes: bool = False
    ) -> dict[int, dict[str, Any]]:
        """Read several PR histories from one SQLite snapshot."""

        if isinstance(prs, (str, bytes)) or not isinstance(prs, Sequence) or not 1 <= len(prs) <= 200:
            raise ReviewRecordsError("history batch requires 1–200 PRs")
        selected = tuple(_positive_pr(pr) for pr in prs)
        if len(set(selected)) != len(selected):
            raise ReviewRecordsError("history batch PRs must be distinct")
        self._require_regular_database()
        try:
            with contextlib.closing(self._connect(read_only=True)) as connection:
                connection.execute("BEGIN")
                self._require_compatible(connection)
                return {
                    pr: self.history(pr, include_legacy_routes=include_legacy_routes,
                                     _connection=connection)
                    for pr in selected
                }
        except ReviewRecordsError:
            raise
        except (OSError, sqlite3.DatabaseError, json.JSONDecodeError) as exc:
            raise ReviewRecordsError("cannot read SQLite review history batch") from exc

    def list_routes(
        self,
        *,
        status: str = "open",
        target_pr: int | None = None,
        source_pr: int | None = None,
        unassigned: bool = False,
        include_legacy_routes: bool = False,
    ) -> list[dict[str, Any]]:
        """Read routed findings by status and source/target PR.

        ``status`` is ``open`` (the default), ``resolved`` (accepted-fixed or
        rejected), or ``all``. Target and source PR filters may be combined;
        ``unassigned`` selects routes without a target and may be combined
        with a source PR. Legacy controller routes join the result only when
        ``include_legacy_routes`` is true.
        """

        if not isinstance(status, str) or status not in {"open", "resolved", "all"}:
            raise ReviewRecordsError("route status must be open, resolved, or all")
        if target_pr is not None:
            target_pr = _positive_pr(target_pr, "target PR")
        if source_pr is not None:
            source_pr = _positive_pr(source_pr, "source PR")
        if not isinstance(unassigned, bool):
            raise ReviewRecordsError("unassigned must be boolean")
        if target_pr is not None and unassigned:
            raise ReviewRecordsError("target PR and unassigned filters cannot be combined")
        if not isinstance(include_legacy_routes, bool):
            raise ReviewRecordsError("include_legacy_routes must be boolean")
        try:
            with contextlib.closing(self._connect(read_only=True)) as connection:
                connection.execute("BEGIN")
                self._require_compatible(connection)
                controller_state = self._controller_state(connection) if include_legacy_routes else None
                route_select = (
                    "SELECT routes.route_id, routes.finding_id, routes.source_pr, routes.source_channel, "
                    "findings.source_finding_key, routes.target_pr, routes.status, routes.created_at, "
                    "routes.updated_at, "
                    "(SELECT o.title FROM finding_observations o JOIN review_runs r USING (run_id) "
                    "WHERE o.finding_id = routes.finding_id "
                    "ORDER BY r.started_at DESC, o.run_id DESC LIMIT 1) "
                    "FROM routes JOIN findings USING (finding_id)"
                )
                if include_legacy_routes:
                    # Read every shadow row before filtering. A legacy route
                    # with the same stable ID may have moved or reached a
                    # different status since the SQLite import; deduplication
                    # must see the authoritative legacy record before any
                    # status or assignment filters are applied.
                    rows = connection.execute(
                        route_select + " ORDER BY COALESCE(routes.target_pr, 0), "
                        "routes.source_pr, routes.route_id"
                    )
                else:
                    conditions = []
                    parameters: list[Any] = []
                    if status == "open":
                        conditions.append("routes.status = 'open'")
                    elif status == "resolved":
                        conditions.append("routes.status IN ('accepted_fixed', 'rejected')")
                    if target_pr is not None:
                        conditions.append("routes.target_pr = ?")
                        parameters.append(target_pr)
                    if source_pr is not None:
                        conditions.append("routes.source_pr = ?")
                        parameters.append(source_pr)
                    if unassigned:
                        conditions.append("routes.target_pr IS NULL")
                    where = " WHERE " + " AND ".join(conditions) if conditions else ""
                    rows = connection.execute(
                        route_select + where
                        + " ORDER BY COALESCE(routes.target_pr, 0), routes.source_pr, routes.route_id",
                        parameters,
                    )
                routes = [
                    {
                        "route_id": row[0],
                        "origin": "review_records",
                        "finding_id": row[1],
                        "source_pr": row[2],
                        "source_channel": row[3],
                        "source_finding_key": row[4],
                        "target_pr": row[5],
                        "status": row[6],
                        "assignment": "unassigned" if row[5] is None else "incoming",
                        "created_at": row[7],
                        "updated_at": row[8],
                        "title": row[9],
                    }
                    for row in rows
                ]
                if controller_state is not None:
                    routes.extend(
                        self._legacy_route_record(route)
                        for route in controller_state.routes
                        if route.status in {"open", "accepted_fixed", "rejected"}
                    )
                routes = self._deduplicate_routes(routes)
                if status == "open":
                    routes = [route for route in routes if route["status"] == "open"]
                elif status == "resolved":
                    routes = [route for route in routes if route["status"] in {"accepted_fixed", "rejected"}]
                if target_pr is not None:
                    routes = [route for route in routes if route["target_pr"] == target_pr]
                if source_pr is not None:
                    routes = [route for route in routes if route["source_pr"] == source_pr]
                if unassigned:
                    routes = [route for route in routes if route["target_pr"] is None]
                return sorted(
                    routes,
                    key=lambda route: (route["target_pr"] or 0, route["source_pr"], route["route_id"]),
                )
        except ReviewRecordsError:
            raise
        except (OSError, sqlite3.DatabaseError) as exc:
            raise ReviewRecordsError("cannot read SQLite review routes") from exc

    def open_routes(
        self,
        *,
        target_pr: int | None = None,
        include_legacy_routes: bool = False,
    ) -> list[dict[str, Any]]:
        """Read open incoming and unassigned routes for existing callers."""

        return self.list_routes(
            target_pr=target_pr,
            include_legacy_routes=include_legacy_routes,
        )

    def _routes_for_pr(self, connection: sqlite3.Connection, pr: int) -> list[dict[str, Any]]:
        rows = connection.execute(
            "SELECT routes.route_id, routes.finding_id, routes.source_pr, routes.source_channel, "
            "findings.source_finding_key, routes.target_pr, routes.status, routes.created_at, routes.updated_at "
            "FROM routes JOIN findings USING (finding_id) "
            "WHERE routes.source_pr = ? OR routes.target_pr = ? ORDER BY routes.source_pr, routes.route_id",
            (pr, pr),
        )
        records: list[dict[str, Any]] = []
        for row in rows:
            route_id = row[0]
            targets = [
                {"target_pr": target[0], "changed_at": target[1], "actor": target[2], "reason": target[3]}
                for target in connection.execute(
                    "SELECT target_pr, changed_at, actor, reason FROM route_target_history "
                    "WHERE route_id = ? ORDER BY sequence",
                    (route_id,),
                )
            ]
            decisions = [
                {
                    "decision_id": item[0], "decision_pr": item[1], "decision": item[2],
                    "actor": item[3], "reason": item[4], "decided_at": item[5],
                }
                for item in connection.execute(
                    "SELECT decision_id, decision_pr, decision, actor, reason, decided_at FROM decisions "
                    "WHERE decision_scope = 'target' AND route_id = ? ORDER BY decided_at, decision_id",
                    (route_id,),
                )
            ]
            resolutions = [
                {
                    "resolution_id": item[0], "resolution_pr": item[1], "outcome": item[2],
                    "actor": item[3], "proof_or_reason": item[4], "resolved_at": item[5],
                }
                for item in connection.execute(
                    "SELECT resolution_id, resolution_pr, outcome, actor, proof_or_reason, resolved_at "
                    "FROM resolutions WHERE route_id = ? ORDER BY resolved_at, resolution_id",
                    (route_id,),
                )
            ]
            records.append(
                {
                    "route_id": route_id,
                    "origin": "review_records",
                    "finding_id": row[1],
                    "source_pr": row[2],
                    "source_channel": row[3],
                    "source_finding_key": row[4],
                    "target_pr": row[5],
                    "status": row[6],
                    "created_at": row[7],
                    "updated_at": row[8],
                    "target_history": targets,
                    "decisions": decisions,
                    "resolutions": resolutions,
                }
            )
        return records

    @staticmethod
    def _controller_state(connection: sqlite3.Connection) -> ReviewState:
        """Read canonical controller state from the current SQLite snapshot."""

        try:
            row = connection.execute("SELECT state_json FROM review_state WHERE singleton = 1").fetchone()
        except sqlite3.DatabaseError as exc:
            raise ReviewRecordsError("controller SQLite review state has an incompatible shape") from exc
        if row is None or not isinstance(row[0], str):
            raise ReviewRecordsError("controller SQLite review state is missing its state document")
        try:
            document = json.loads(row[0])
        except json.JSONDecodeError as exc:
            raise ReviewRecordsError("controller SQLite review state contains malformed JSON") from exc
        if not isinstance(document, Mapping):
            raise ReviewRecordsError("controller SQLite review state document must be an object")
        try:
            return ReviewState.from_dict(document)
        except (TypeError, ValueError) as exc:
            raise ReviewRecordsError("controller SQLite review state is invalid") from exc

    @classmethod
    def _reject_legacy_route_write(cls, connection: sqlite3.Connection, route_id: str) -> None:
        """Keep controller-owned route IDs writable only through the controller."""

        if any(route.route_id == route_id for route in cls._controller_state(connection).routes):
            raise ReviewRecordsError(
                "legacy controller owns this route; use `dev-tools/pr-review decide route` to update it"
            )

    @staticmethod
    def _legacy_route_record(route: FindingRoute) -> dict[str, Any]:
        """Expose one legacy route without inventing structured run metadata."""

        return {
            "route_id": route.route_id,
            "origin": "legacy_controller",
            "source_pr": route.source_pr,
            "source_channel": route.source_channel,
            "source_review": route.source_review,
            "source_finding": route.source_finding,
            "source_finding_key": route.source_finding,
            "title": None,
            "observations": list(route.observations),
            "target_pr": route.target_pr,
            "status": route.status,
            "assignment": "unassigned" if route.target_pr is None else "incoming",
            "disposition": route.disposition,
            "proof": route.proof,
            "target_history": list(route.target_history),
        }

    @staticmethod
    def _deduplicate_routes(routes: Sequence[dict[str, Any]]) -> list[dict[str, Any]]:
        """Deduplicate exact stable route IDs without collapsing identity schemes."""

        unique: dict[str, dict[str, Any]] = {}
        for route in routes:
            route_id = route.get("route_id")
            if not isinstance(route_id, str):
                raise ReviewRecordsError("stored route is missing its stable route ID")
            existing = unique.get(route_id)
            if existing is None:
                unique[route_id] = route
            elif existing.get("origin") == "legacy_controller":
                # Hosted summary imports retain the exact legacy route ID in a
                # SQLite shadow row so foreign keys can preserve the source
                # observation. The canonical legacy record owns its current
                # target/status; never expose the shadow as a second route.
                continue
            elif route.get("origin") == "legacy_controller":
                unique[route_id] = route
            elif existing != route:
                raise ReviewRecordsError("legacy and structured routes conflict on a stable route ID")
            else:
                continue
        return list(unique.values())

    @staticmethod
    def _run_record(row: Sequence[Any]) -> dict[str, Any]:
        try:
            coverage_limits = json.loads(row[6])
        except (TypeError, json.JSONDecodeError) as exc:
            raise ReviewRecordsError("stored coverage metadata is malformed") from exc
        if not isinstance(coverage_limits, list) or any(not isinstance(item, str) for item in coverage_limits):
            raise ReviewRecordsError("stored coverage metadata must be a list of strings")
        return {
            "run_id": row[0], "source_pr": row[1], "channel": row[2], "source_head": row[3],
            "reviewer": row[4], "scope": row[5], "coverage_limits": coverage_limits,
            "outcome": row[7], "attributable": bool(row[8]), "started_at": row[9], "finished_at": row[10],
            "counts": {"found": row[11], "accepted": row[12], "routed": row[13]},
            "finalized": bool(row[14]), "finalized_at": row[15],
        }

    @staticmethod
    def _observation_record(row: Sequence[Any]) -> dict[str, Any]:
        return {
            "run_id": row[0], "finding_id": row[1], "source_pr": row[2], "source_channel": row[3],
            "source_finding_key": row[4], "title": row[5], "detail": row[6], "disposition": row[7],
            "route_id": row[8],
        }

    @staticmethod
    def _record_route_observation(
        connection: sqlite3.Connection,
        *,
        route_id: str,
        finding_id: str,
        source_pr: int,
        channel: str,
        target_pr: int | None,
        observed_at: str,
        route_status: str | None = None,
    ) -> None:
        if route_status is not None and route_status not in {"open", "accepted_fixed", "rejected"}:
            raise ReviewRecordsError("source route status is invalid")
        existing = connection.execute(
            "SELECT finding_id, source_pr, source_channel, target_pr, status FROM routes WHERE route_id = ?",
            (route_id,),
        ).fetchone()
        if existing is None:
            connection.execute(
                "INSERT INTO routes (route_id, finding_id, source_pr, source_channel, target_pr, status, "
                "created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                (route_id, finding_id, source_pr, channel, target_pr, route_status or "open", observed_at, observed_at),
            )
            connection.execute(
                "INSERT INTO route_target_history (route_id, target_pr, changed_at, actor, reason) "
                "VALUES (?, ?, ?, 'source-review', 'source finding was routed')",
                (route_id, target_pr, observed_at),
            )
        elif existing[0] != finding_id or existing[1] != source_pr or existing[2] != channel:
            raise ReviewRecordsError("a source route reference conflicts with another finding")
        elif target_pr is not None and existing[3] != target_pr and route_status is None:
            raise ReviewRecordsError("a repeated finding must be explicitly retargeted")
        elif route_status is not None and (existing[3] != target_pr or existing[4] != route_status):
            connection.execute(
                "UPDATE routes SET target_pr = ?, status = ?, updated_at = ? WHERE route_id = ?",
                (target_pr, route_status, observed_at, route_id),
            )

    @contextlib.contextmanager
    def _write_connection(self):
        self._require_regular_database()
        connection = self._connect(read_only=False)
        try:
            connection.execute("BEGIN IMMEDIATE")
            self._require_compatible(connection)
            yield connection
            connection.commit()
        except BaseException:
            if connection.in_transaction:
                connection.rollback()
            raise
        finally:
            connection.close()

    def _connect(self, *, read_only: bool) -> sqlite3.Connection:
        if self.path.is_symlink():
            raise ReviewRecordsError("SQLite controller database path must not be a symlink")
        mode = "ro" if read_only else "rw"
        uri = f"{self.path.resolve().as_uri()}?mode={mode}"
        connection = sqlite3.connect(uri, uri=True, timeout=self.timeout, isolation_level=None)
        connection.execute(f"PRAGMA busy_timeout = {int(self.timeout * 1000)}")
        connection.execute("PRAGMA foreign_keys = ON")
        if read_only:
            connection.execute("PRAGMA query_only = ON")
        return connection

    def _require_regular_database(self) -> None:
        if self.path.is_symlink():
            raise ReviewRecordsError("SQLite controller database path must not be a symlink")
        if not self.path.is_file():
            raise ReviewRecordsError("controller SQLite database must already exist")

    @staticmethod
    def _table_names(connection: sqlite3.Connection) -> set[str]:
        return {
            row[0]
            for row in connection.execute("SELECT name FROM sqlite_master WHERE type = 'table'")
        }

    def _require_controller_compatible(self, connection: sqlite3.Connection) -> None:
        schema_version = int(connection.execute("PRAGMA user_version").fetchone()[0])
        if schema_version != SQLITE_SCHEMA_VERSION:
            raise RecordsSchemaIncompatible(f"unsupported controller SQLite schema version {schema_version}")
        if "controller_metadata" not in self._table_names(connection):
            raise RecordsSchemaIncompatible("controller SQLite metadata is missing")
        try:
            row = connection.execute(
                "SELECT data_model_version, min_writer_build FROM controller_metadata WHERE singleton = 1"
            ).fetchone()
        except sqlite3.DatabaseError as exc:
            raise RecordsSchemaIncompatible("controller SQLite metadata has an incompatible shape") from exc
        if row is None:
            raise RecordsSchemaIncompatible("controller SQLite metadata row is missing")
        data_model_version, min_writer_build = row
        if data_model_version != ReviewState().schema_version:
            raise RecordsSchemaIncompatible(f"unsupported controller data-model version {data_model_version}")
        if isinstance(min_writer_build, bool) or not isinstance(min_writer_build, int) or min_writer_build <= 0:
            raise RecordsSchemaIncompatible("controller SQLite minimum writer build metadata is invalid")
        if self.writer_build < min_writer_build:
            raise RecordsSchemaIncompatible(f"controller SQLite database requires writer build {min_writer_build}")

    @staticmethod
    def _raise_controller_writer_fence(connection: sqlite3.Connection) -> None:
        connection.execute(
            "UPDATE controller_metadata SET min_writer_build = "
            "CASE WHEN min_writer_build < ? THEN ? ELSE min_writer_build END "
            "WHERE singleton = 1",
            (WRITER_BUILD, WRITER_BUILD),
        )

    def _require_compatible(self, connection: sqlite3.Connection) -> None:
        self._require_controller_compatible(connection)
        tables = self._table_names(connection)
        if _RECORDS_METADATA_TABLE not in tables:
            raise RecordsNotBootstrapped(
                "review-records schema is not bootstrapped; call bootstrap() explicitly"
            )
        try:
            row = connection.execute(
                f"SELECT records_schema_version, controller_schema_version, controller_data_model_version, "
                f"min_writer_build FROM {_RECORDS_METADATA_TABLE} WHERE singleton = 1"
            ).fetchone()
        except sqlite3.DatabaseError as exc:
            raise RecordsSchemaIncompatible("review-records metadata has an incompatible shape") from exc
        if row is None:
            raise RecordsSchemaIncompatible("review-records metadata row is missing")
        records_version, controller_version, data_model_version, min_writer_build = row
        if records_version != _RECORDS_SCHEMA_VERSION:
            raise RecordsSchemaIncompatible(f"unsupported review-records schema version {records_version}")
        if controller_version != SQLITE_SCHEMA_VERSION:
            raise RecordsSchemaIncompatible(f"review records require controller schema {controller_version}")
        if data_model_version != ReviewState().schema_version:
            raise RecordsSchemaIncompatible(f"review records require controller data model {data_model_version}")
        if isinstance(min_writer_build, bool) or not isinstance(min_writer_build, int) or min_writer_build <= 0:
            raise RecordsSchemaIncompatible("review-records minimum writer build metadata is invalid")
        if self.writer_build < min_writer_build:
            raise RecordsSchemaIncompatible(f"review records require writer build {min_writer_build}")
        missing = _RECORDS_TABLES - tables
        if missing:
            raise RecordsSchemaIncompatible("review-records schema is incomplete")

    @staticmethod
    def _create_schema(connection: sqlite3.Connection) -> None:
        connection.execute(
            "CREATE TABLE review_records_metadata ("
            "singleton INTEGER PRIMARY KEY CHECK (singleton = 1), "
            "records_schema_version INTEGER NOT NULL, "
            "controller_schema_version INTEGER NOT NULL, "
            "controller_data_model_version INTEGER NOT NULL, "
            "min_writer_build INTEGER NOT NULL CHECK (min_writer_build > 0))"
        )
        connection.execute(
            "CREATE TABLE review_runs ("
            "run_id TEXT PRIMARY KEY, source_pr INTEGER NOT NULL CHECK (source_pr > 0), "
            "channel TEXT NOT NULL CHECK (channel IN ('hosted', 'cli', 'manual', 'subagent')), "
            "source_head TEXT, reviewer TEXT NOT NULL, scope TEXT NOT NULL CHECK (scope IN ('broad', 'narrow')), "
            "coverage_limits_json TEXT NOT NULL, import_payload_json TEXT NOT NULL, "
            "outcome TEXT NOT NULL CHECK (outcome IN ('completed', 'incomplete', 'failed')), "
            "attributable INTEGER NOT NULL CHECK (attributable IN (0, 1)), started_at TEXT NOT NULL, finished_at TEXT, "
            "found_count INTEGER NOT NULL CHECK (found_count >= 0), "
            "accepted_count INTEGER NOT NULL CHECK (accepted_count >= 0), "
            "routed_count INTEGER NOT NULL CHECK (routed_count >= 0), "
            "finalized INTEGER NOT NULL CHECK (finalized IN (0, 1)), finalized_at TEXT, "
            "CHECK (accepted_count + routed_count <= found_count), "
            "CHECK ((finalized = 0 AND finalized_at IS NULL) OR (finalized = 1 AND finalized_at IS NOT NULL)), "
            "UNIQUE (run_id, source_pr, channel))"
        )
        connection.execute(
            "CREATE TABLE findings ("
            "finding_id TEXT PRIMARY KEY, source_pr INTEGER NOT NULL CHECK (source_pr > 0), "
            "source_channel TEXT NOT NULL CHECK (source_channel IN ('hosted', 'cli', 'manual', 'subagent')), "
            "source_finding_key TEXT NOT NULL, first_seen_at TEXT NOT NULL, "
            "UNIQUE (source_pr, source_channel, source_finding_key), "
            "UNIQUE (finding_id, source_pr, source_channel))"
        )
        connection.execute(
            "CREATE TABLE finding_observations ("
            "run_id TEXT NOT NULL, finding_id TEXT NOT NULL, source_pr INTEGER NOT NULL, source_channel TEXT NOT NULL, "
            "title TEXT NOT NULL, detail TEXT NOT NULL, "
            "disposition TEXT NOT NULL CHECK (disposition IN ('accepted', 'routed', 'rejected', 'unresolved')), "
            "route_id TEXT, PRIMARY KEY (run_id, finding_id), "
            "FOREIGN KEY (run_id, source_pr, source_channel) REFERENCES review_runs(run_id, source_pr, channel), "
            "FOREIGN KEY (finding_id, source_pr, source_channel) REFERENCES findings(finding_id, source_pr, source_channel), "
            "FOREIGN KEY (route_id, finding_id) REFERENCES routes(route_id, finding_id))"
        )
        connection.execute(
            "CREATE TABLE routes ("
            "route_id TEXT PRIMARY KEY, finding_id TEXT NOT NULL UNIQUE, source_pr INTEGER NOT NULL, "
            "source_channel TEXT NOT NULL, target_pr INTEGER CHECK (target_pr IS NULL OR target_pr > 0), "
            "status TEXT NOT NULL CHECK (status IN ('open', 'accepted_fixed', 'rejected')), "
            "created_at TEXT NOT NULL, updated_at TEXT NOT NULL, UNIQUE (route_id, finding_id), "
            "FOREIGN KEY (finding_id, source_pr, source_channel) REFERENCES findings(finding_id, source_pr, source_channel))"
        )
        connection.execute(
            "CREATE TABLE route_target_history ("
            "sequence INTEGER PRIMARY KEY AUTOINCREMENT, route_id TEXT NOT NULL, "
            "target_pr INTEGER CHECK (target_pr IS NULL OR target_pr > 0), changed_at TEXT NOT NULL, "
            "actor TEXT NOT NULL, reason TEXT NOT NULL, FOREIGN KEY (route_id) REFERENCES routes(route_id))"
        )
        connection.execute(
            "CREATE TABLE decisions ("
            "decision_id TEXT PRIMARY KEY, decision_scope TEXT NOT NULL CHECK (decision_scope IN ('source', 'target')), "
            "run_id TEXT, finding_id TEXT NOT NULL, route_id TEXT, decision_pr INTEGER NOT NULL CHECK (decision_pr > 0), "
            "target_pr INTEGER CHECK (target_pr IS NULL OR target_pr > 0), "
            "decision TEXT NOT NULL CHECK (decision IN ('accepted', 'routed', 'rejected', 'deferred')), "
            "actor TEXT NOT NULL, reason TEXT NOT NULL, decided_at TEXT NOT NULL, "
            "CHECK ((decision_scope = 'source' AND run_id IS NOT NULL) OR "
            "(decision_scope = 'target' AND run_id IS NULL AND route_id IS NOT NULL)), "
            "FOREIGN KEY (run_id, finding_id) REFERENCES finding_observations(run_id, finding_id), "
            "FOREIGN KEY (route_id, finding_id) REFERENCES routes(route_id, finding_id))"
        )
        connection.execute(
            "CREATE TABLE resolutions ("
            "resolution_id TEXT PRIMARY KEY, route_id TEXT NOT NULL, resolution_pr INTEGER NOT NULL CHECK (resolution_pr > 0), "
            "outcome TEXT NOT NULL CHECK (outcome IN ('accepted_fixed', 'rejected')), actor TEXT NOT NULL, "
            "proof_or_reason TEXT NOT NULL, resolved_at TEXT NOT NULL, "
            "FOREIGN KEY (route_id) REFERENCES routes(route_id))"
        )
        connection.execute(
            "CREATE INDEX review_runs_source_pr_idx ON review_runs(source_pr, started_at)"
        )
        connection.execute(
            "CREATE INDEX routes_target_status_idx ON routes(target_pr, status, source_pr)"
        )
        SqliteReviewRecords._create_attempt_schema(connection)
        SqliteReviewRecords._create_origin_schema(connection)
        SqliteReviewRecords._create_historical_gap_schema(connection)
        connection.execute(
            "INSERT INTO review_records_metadata VALUES (1, ?, ?, ?, ?)",
            (_RECORDS_SCHEMA_VERSION, SQLITE_SCHEMA_VERSION, ReviewState().schema_version, WRITER_BUILD),
        )

    @staticmethod
    def _create_attempt_schema(connection: sqlite3.Connection) -> None:
        connection.execute(
            "CREATE TABLE review_attempts ("
            "attempt_id TEXT PRIMARY KEY, source_pr INTEGER NOT NULL CHECK (source_pr > 0), "
            "channel TEXT NOT NULL CHECK (channel IN ('hosted', 'cli', 'subagent')), "
            "candidate_sha TEXT, state TEXT NOT NULL CHECK (state IN "
            "('started', 'completed', 'failed', 'rate_limited', 'timed_out', 'ambiguous')), "
            "started_at TEXT NOT NULL, finished_at TEXT, duration_seconds INTEGER "
            "CHECK (duration_seconds IS NULL OR duration_seconds >= 0), "
            "exit_status INTEGER, trigger_id TEXT, provider_review_id TEXT, checkpoint_id TEXT, "
            "run_id TEXT, diagnostic TEXT NOT NULL DEFAULT '', metadata_json TEXT NOT NULL, "
            "CHECK ((state = 'started' AND finished_at IS NULL) OR "
            "(state != 'started' AND finished_at IS NOT NULL)), "
            "FOREIGN KEY (run_id) REFERENCES review_runs(run_id))"
        )
        connection.execute(
            "CREATE INDEX review_attempts_pr_idx ON review_attempts(source_pr, started_at)"
        )
        connection.execute(
            "CREATE TABLE review_artifacts ("
            "attempt_id TEXT NOT NULL, kind TEXT NOT NULL CHECK (kind IN "
            "('cli_events', 'cli_raw_output', 'cli_diagnostic', 'hosted_review', 'hosted_comments', 'metadata')), "
            "content TEXT NOT NULL, source_sha256 TEXT NOT NULL, redactions INTEGER NOT NULL "
            "CHECK (redactions >= 0), PRIMARY KEY (attempt_id, kind), "
            "FOREIGN KEY (attempt_id) REFERENCES review_attempts(attempt_id))"
        )
        connection.execute(
            "CREATE TABLE source_decision_corrections ("
            "sequence INTEGER PRIMARY KEY AUTOINCREMENT, correction_id TEXT NOT NULL UNIQUE, "
            "supersedes_id TEXT NOT NULL, run_id TEXT NOT NULL, finding_id TEXT NOT NULL, "
            "decision TEXT NOT NULL CHECK (decision IN ('accepted', 'routed', 'rejected')), "
            "target_pr INTEGER CHECK (target_pr IS NULL OR target_pr > 0), "
            "actor TEXT NOT NULL, reason TEXT NOT NULL, decided_at TEXT NOT NULL, "
            "FOREIGN KEY (run_id, finding_id) REFERENCES finding_observations(run_id, finding_id))"
        )
        connection.execute(
            "CREATE INDEX source_corrections_finding_idx "
            "ON source_decision_corrections(run_id, finding_id, sequence)"
        )

    @staticmethod
    def _create_origin_schema(connection: sqlite3.Connection) -> None:
        connection.execute(
            "CREATE TABLE provider_origins ("
            "repository TEXT NOT NULL, source_pr INTEGER NOT NULL CHECK (source_pr > 0), "
            "channel TEXT NOT NULL CHECK (channel IN ('hosted', 'cli')), "
            "provider_id TEXT NOT NULL, checkpoint_id INTEGER NOT NULL CHECK (checkpoint_id > 0), "
            "checkpoint_fingerprint TEXT NOT NULL, run_id TEXT NOT NULL UNIQUE, "
            "PRIMARY KEY (repository, source_pr, checkpoint_id), "
            "UNIQUE (repository, source_pr, channel, provider_id), "
            "FOREIGN KEY (run_id) REFERENCES review_runs(run_id))"
        )
        connection.execute(
            "CREATE INDEX provider_origins_source_idx ON provider_origins(source_pr, checkpoint_id)"
        )
        connection.execute(
            "CREATE TABLE imported_artifacts ("
            "run_id TEXT NOT NULL, kind TEXT NOT NULL CHECK (kind IN "
            "('cli_events', 'cli_raw_output', 'cli_diagnostic', 'hosted_review', 'hosted_comments', 'metadata')), "
            "content TEXT NOT NULL, source_sha256 TEXT NOT NULL, redactions INTEGER NOT NULL "
            "CHECK (redactions >= 0), PRIMARY KEY (run_id, kind), "
            "FOREIGN KEY (run_id) REFERENCES review_runs(run_id))"
        )

    @staticmethod
    def _create_historical_gap_schema(connection: sqlite3.Connection) -> None:
        connection.execute(
            "CREATE TABLE historical_provider_gaps ("
            "repository TEXT NOT NULL, source_pr INTEGER NOT NULL CHECK (source_pr > 0), "
            "channel TEXT NOT NULL CHECK (channel IN ('hosted', 'cli')), "
            "checkpoint_id INTEGER NOT NULL CHECK (checkpoint_id > 0), "
            "checkpoint_fingerprint TEXT NOT NULL, checkpoint_json TEXT NOT NULL, "
            "checkpoint_source_sha256 TEXT NOT NULL, checkpoint_redactions INTEGER NOT NULL "
            "CHECK (checkpoint_redactions >= 0), missing_reason TEXT NOT NULL, "
            "PRIMARY KEY (repository, source_pr, checkpoint_id))"
        )
        connection.execute(
            "CREATE INDEX historical_gaps_source_idx ON historical_provider_gaps(source_pr, checkpoint_id)"
        )
        connection.execute(
            "CREATE TABLE historical_gap_artifacts ("
            "repository TEXT NOT NULL, source_pr INTEGER NOT NULL, checkpoint_id INTEGER NOT NULL, "
            "kind TEXT NOT NULL CHECK (kind IN "
            "('cli_events', 'cli_raw_output', 'cli_diagnostic', 'hosted_review', 'hosted_comments', 'metadata')), "
            "content TEXT NOT NULL, source_sha256 TEXT NOT NULL, redactions INTEGER NOT NULL "
            "CHECK (redactions >= 0), "
            "PRIMARY KEY (repository, source_pr, checkpoint_id, kind), "
            "FOREIGN KEY (repository, source_pr, checkpoint_id) "
            "REFERENCES historical_provider_gaps(repository, source_pr, checkpoint_id))"
        )


__all__ = ["FindingObservation", "ReviewRecordsError", "SqliteReviewRecords"]
