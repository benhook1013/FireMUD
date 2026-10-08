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
import math
import os
import re
import sqlite3
import time
from collections.abc import Mapping, Sequence
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Literal

from .sqlite_store import SQLITE_SCHEMA_VERSION, WRITER_BUILD
from .state import FindingRoute, ReviewState, StateLockTimeout

ReviewChannel = Literal["hosted", "cli", "manual", "subagent"]
FindingDisposition = Literal["accepted", "routed", "rejected", "unresolved"]
_RECORDS_SCHEMA_VERSION = 9
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
    "source_finding_resolutions",
    "source_finding_resolution_corrections",
}


def _model_metadata(metadata: Mapping[str, Any]) -> None:
    """Validate declared tool identifiers without guessing or pinning versions."""
    model = metadata.get("model")
    if (
        not isinstance(model, str)
        or not model.strip()
        or len(model) > 200
        or any(character.isspace() or ord(character) < 32 for character in model)
    ):
        raise ReviewRecordsError("new subagent attempts require a nonblank model identifier of at most 200 characters")
    effort = metadata.get("reasoning_effort")
    if effort is not None and (
        not isinstance(effort, str)
        or not effort.strip()
        or len(effort) > 32
        or any(character.isspace() or ord(character) < 32 for character in effort)
    ):
        raise ReviewRecordsError("reasoning effort must be a bounded nonblank identifier when supplied")


class ReviewRecordsError(ValueError):
    """Raised when review records are invalid or the database is incompatible."""


class AttemptNotFound(ReviewRecordsError):
    """Raised when an exact attempt ID is not present in the records store."""


class CliCaptureTerminalFailure(ReviewRecordsError):
    """A CLI attempt is terminal but did not produce a completed source run."""


class CliCaptureInProgress(ReviewRecordsError):
    """A CLI attempt is still running and has no countable completed capture."""


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
            if args and isinstance(args[0], SqliteReviewRecords):
                args[0]._raise_hosted_deadline_if_expired(kwargs.get("deadline"), error=exc)
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
    display_severity: str | None = None

    def __post_init__(self) -> None:
        _safe_identifier(self.source_finding_key, "source_finding_key", maximum=200)
        _bounded_text(self.title, "title", maximum=300)
        _bounded_text(self.detail, "detail", maximum=1000, allow_empty=True)
        if not isinstance(self.disposition, str) or self.disposition not in {
            "accepted",
            "routed",
            "rejected",
            "unresolved",
        }:
            raise ReviewRecordsError("finding disposition is invalid")
        if self.target_pr is not None:
            _positive_pr(self.target_pr, "finding target PR")
        if self.disposition != "routed" and self.target_pr is not None:
            raise ReviewRecordsError("only routed findings may set a target PR")
        if self.display_severity is not None and (
            not isinstance(self.display_severity, str)
            or self.display_severity not in {"Critical", "Major", "Minor", "Trivial"}
        ):
            raise ReviewRecordsError("finding display severity is invalid")


_SECRET_PATTERNS = (
    re.compile(r"-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----", re.IGNORECASE),
    re.compile(r"\b(?:gh[pousr]_[A-Za-z0-9_]{20,}|github_pat_[A-Za-z0-9_]{20,})\b"),
    re.compile(r"\bAKIA[0-9A-Z]{16}\b"),
    re.compile(r"\bBearer\s+\S+", re.IGNORECASE),
    re.compile(r"\b(?:password|passwd|secret|api[_-]?key|access[_-]?token)\s*[:=]\s*\S+", re.IGNORECASE),
    re.compile(r"\beyJ[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\b"),
)
_SECRET_CATEGORIES = (
    "private_key",
    "github_token",
    "aws_access_key",
    "bearer_authorization",
    "credential_assignment",
    "jwt",
)


def _credential_location(
    value: str,
    patterns: Sequence[re.Pattern[str]] = _SECRET_PATTERNS,
) -> tuple[str, int] | None:
    """Locate the earliest existing rule match without returning submitted text.

    Ties use rule order. LF and CRLF each advance one line.
    """
    matches = (
        (match.start(), category)
        for category, pattern in zip(_SECRET_CATEGORIES, patterns, strict=True)
        if (match := pattern.search(value)) is not None
    )
    first = min(matches, key=lambda item: item[0], default=None)
    if first is None:
        return None
    return first[1], value.count("\n", 0, first[0]) + 1


_SECRET_FIELD_SUFFIXES = (
    "password",
    "passwd",
    "secret",
    "token",
    "credential",
    "credentials",
    "apikey",
    "accesskey",
    "secretkey",
    "privatekey",
    "signingkey",
    "encryptionkey",
    "accesstoken",
    "refreshtoken",
    "authtoken",
    "oauthtoken",
    "clientsecret",
    "clienttoken",
    "githubtoken",
    "bearertoken",
    "sessiontoken",
    "idtoken",
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
    if location := _credential_location(value):
        category, line = location
        raise ReviewRecordsError(
            f"{label} resembles credential or raw secret material (category={category}; line={line})"
        )
    return value


def _coverage_limits(values: Sequence[str], *, retained: Sequence[str] = ()) -> tuple[str, ...]:
    """Validate coverage text while retaining bounded exact historical notes."""

    if isinstance(values, (str, bytes)) or not isinstance(values, Sequence):
        raise ReviewRecordsError("coverage_limits must be a sequence of bounded text values")
    if len(values) > 20:
        raise ReviewRecordsError("coverage_limits may contain at most 20 entries")
    matches_retained = tuple(values) == tuple(retained)
    return tuple(
        _bounded_text(
            item,
            "coverage limit",
            maximum=1000 if matches_retained else 200,
        )
        for item in values
    )


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
        normalized = normalized[: -len(qualifier)]
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
        if len(stored.encode("utf-8")) > limits[kind]:
            raise ReviewRecordsError(f"{kind} exceeds its evidence size limit")
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
    if len(stored.encode("utf-8")) > limits[kind]:
        raise ReviewRecordsError(f"{kind} exceeds its evidence size limit")
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
        """Upgrade existing v4-v8 records atomically and fence older state writers.

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
                if row[0] not in {4, 5, 6, 7, 8}:
                    raise ReviewRecordsError(f"unsupported review-records schema version {row[0]}")
                existing = self._table_names(connection)
                if row[0] == 8:
                    added = set()
                    required = _RECORDS_TABLES
                elif row[0] == 7:
                    added = {"source_finding_resolution_corrections"}
                    required = _RECORDS_TABLES - added
                elif row[0] == 6:
                    added = {"source_finding_resolutions", "source_finding_resolution_corrections"}
                    required = _RECORDS_TABLES - added
                else:
                    added = (
                        {
                            "review_attempts",
                            "review_artifacts",
                            "source_decision_corrections",
                            "provider_origins",
                            "imported_artifacts",
                            "historical_provider_gaps",
                            "historical_gap_artifacts",
                        }
                        if row[0] == 4
                        else {
                            "provider_origins",
                            "imported_artifacts",
                            "historical_provider_gaps",
                            "historical_gap_artifacts",
                        }
                    )
                    added.add("source_finding_resolutions")
                    added.add("source_finding_resolution_corrections")
                    required = _RECORDS_TABLES - added
                if not required <= existing or added & existing:
                    raise ReviewRecordsError("review-records schema is incomplete or partially upgraded")
                if row[0] == 4:
                    self._create_attempt_schema(connection)
                if row[0] in {4, 5}:
                    self._create_origin_schema(connection)
                    self._create_historical_gap_schema(connection)
                if row[0] < 7:
                    self._create_source_finding_resolution_schema(connection)
                if row[0] < 8:
                    self._create_source_finding_resolution_correction_schema(connection)
                connection.execute(
                    "ALTER TABLE finding_observations ADD COLUMN display_severity TEXT "
                    "CHECK (display_severity IS NULL OR display_severity IN "
                    "('Critical', 'Major', 'Minor', 'Trivial'))"
                )
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
        deadline: float | None = None,
    ) -> dict[str, Any]:
        """Record an attempt before a provider request or independent pass starts."""

        attempt_id = _safe_identifier(attempt_id, "attempt ID", maximum=100)
        source_pr = _positive_pr(source_pr)
        if channel not in {"hosted", "cli", "subagent"}:
            raise ReviewRecordsError("attempt channel is invalid")
        if candidate_sha is not None and not re.fullmatch(r"[0-9a-fA-F]{40}|[0-9a-fA-F]{64}", candidate_sha):
            raise ReviewRecordsError("attempt candidate SHA is invalid")
        if channel == "subagent":
            if not isinstance(metadata, Mapping):
                raise ReviewRecordsError("new subagent attempts require actual model metadata")
            _model_metadata(metadata)
            if "coverage_limits" in metadata:
                _coverage_limits(metadata["coverage_limits"])
        started_at_was_supplied = started_at is not None
        started_at = _timestamp(started_at, "attempt start")
        try:
            serialized_metadata = _json(dict(metadata or {}))
        except (TypeError, ValueError) as exc:
            raise ReviewRecordsError("attempt metadata must be JSON") from exc
        metadata_json, _, redactions = _archive_artifact("metadata", serialized_metadata)
        if redactions:
            raise ReviewRecordsError("attempt metadata contains credential-shaped material")
        with self._write_connection(deadline=deadline) as connection:
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
        deadline: float | None = None,
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
        for label, value in (
            ("trigger ID", trigger_id),
            ("provider review ID", provider_review_id),
            ("checkpoint ID", checkpoint_id),
        ):
            identifiers.append(None if value is None else _safe_identifier(value, label, maximum=100))
        trigger_id, provider_review_id, checkpoint_id = identifiers
        diagnostic = _bounded_text(diagnostic, "attempt diagnostic", maximum=1000, allow_empty=True)
        archived = {kind: _archive_artifact(kind, content) for kind, content in (artifacts or {}).items()}
        with (
            self._write_connection(deadline=deadline) if _connection is None else contextlib.nullcontext(_connection)
        ) as connection:
            existing = connection.execute(
                "SELECT state, finished_at, duration_seconds, exit_status, trigger_id, provider_review_id, "
                "checkpoint_id, diagnostic FROM review_attempts WHERE attempt_id = ?",
                (attempt_id,),
            ).fetchone()
            if existing is None:
                raise ReviewRecordsError("attempt ID is not registered")
            expected = (
                state,
                finished_at,
                duration_seconds,
                exit_status,
                trigger_id,
                provider_review_id,
                checkpoint_id,
                diagnostic,
            )
            if existing[0] != "started":
                stored = {
                    row[0]: (row[1], row[2], row[3])
                    for row in connection.execute(
                        "SELECT kind, content, source_sha256, redactions FROM review_artifacts WHERE attempt_id = ?",
                        (attempt_id,),
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
    def attempt_history(self, pr: int, *, deadline: float | None = None) -> list[dict[str, Any]]:
        """Return bounded metadata for all attempts on one PR, without private artifacts."""

        pr = _positive_pr(pr)
        self._require_regular_database()
        deadline = self._effective_deadline(deadline)
        self._check_deadline(deadline)
        with contextlib.closing(self._connect(read_only=True, deadline=deadline)) as connection:
            self._require_compatible(connection, deadline=deadline)
            self._set_busy_timeout(connection, deadline)
            rows = connection.execute(
                "SELECT attempt_id, channel, candidate_sha, state, started_at, finished_at, "
                "duration_seconds, exit_status, trigger_id, provider_review_id, checkpoint_id, run_id, "
                "diagnostic FROM review_attempts WHERE source_pr = ? ORDER BY started_at, attempt_id",
                (pr,),
            ).fetchall()
        self._check_deadline(deadline)
        return [
            {
                "attempt_id": row[0],
                "channel": row[1],
                "candidate_sha": row[2],
                "state": row[3],
                "started_at": row[4],
                "finished_at": row[5],
                "duration_seconds": row[6],
                "exit_status": row[7],
                "trigger_id": row[8],
                "provider_review_id": row[9],
                "checkpoint_id": row[10],
                "run_id": row[11],
                "diagnostic": row[12],
            }
            for row in rows
        ]

    @_translate_database_errors
    def attempt(self, attempt_id: str, *, deadline: float | None = None) -> dict[str, Any]:
        """Read one attempt for exact retry or a direct subagent-pass command."""

        attempt_id = _safe_identifier(attempt_id, "attempt ID", maximum=100)
        self._require_regular_database()
        deadline = self._effective_deadline(deadline)
        self._check_deadline(deadline)
        with contextlib.closing(self._connect(read_only=True, deadline=deadline)) as connection:
            self._require_compatible(connection, deadline=deadline)
            self._set_busy_timeout(connection, deadline)
            row = connection.execute(
                "SELECT source_pr, channel, candidate_sha, state, started_at, finished_at, "
                "run_id, metadata_json FROM review_attempts WHERE attempt_id = ?",
                (attempt_id,),
            ).fetchone()
        self._check_deadline(deadline)
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
    def attempt_artifacts(
        self,
        attempt_id: str,
        *,
        deadline: float | None = None,
        _connection: sqlite3.Connection | None = None,
    ) -> dict[str, str]:
        """Read private archived evidence for exact recovery, outside ordinary history."""

        attempt_id = _safe_identifier(attempt_id, "attempt ID", maximum=100)
        self._require_regular_database()
        deadline = self._effective_deadline(deadline)
        self._check_deadline(deadline)
        with (
            contextlib.closing(self._connect(read_only=True, deadline=deadline))
            if _connection is None
            else contextlib.nullcontext(_connection)
        ) as connection:
            self._require_compatible(connection, deadline=deadline)
            self._set_busy_timeout(connection, deadline)
            rows = connection.execute(
                "SELECT kind, content FROM review_artifacts WHERE attempt_id = ?",
                (attempt_id,),
            ).fetchall()
        self._check_deadline(deadline)
        return {kind: content for kind, content in rows}

    @_translate_database_errors
    def link_attempt_run(self, attempt_id: str, run_id: str, *, _connection: sqlite3.Connection | None = None) -> None:
        """Bind one completed, attributable source run to its exact attempt."""

        attempt_id = _safe_identifier(attempt_id, "attempt ID", maximum=100)
        run_id = _safe_identifier(run_id, "run ID", maximum=100)
        with self._write_connection() if _connection is None else contextlib.nullcontext(_connection) as connection:
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
            connection.execute("UPDATE review_attempts SET run_id = ? WHERE attempt_id = ?", (run_id, attempt_id))

    @_translate_database_errors
    def record_failed_cli_observations(
        self,
        attempt_id: str,
        *,
        finish: Mapping[str, Any],
        run: Mapping[str, Any],
    ) -> dict[str, Any]:
        """Atomically retain valid findings from one exact terminal failed CLI attempt.

        The source run remains failed and non-attributable. This path is
        intentionally separate from ``link_attempt_run`` and
        ``complete_attempt_run`` so partial provider output cannot become a
        completed or countable review.
        """

        attempt_id = _safe_identifier(attempt_id, "attempt ID", maximum=100)
        if not isinstance(finish, Mapping) or not isinstance(run, Mapping):
            raise ReviewRecordsError("failed CLI observations require finish and source-run objects")
        finish_values = dict(finish)
        run_values = dict(run)
        if set(finish_values) - {"state", "finished_at", "duration_seconds", "exit_status", "diagnostic", "artifacts"}:
            raise ReviewRecordsError("failed CLI attempt finish contains unsupported fields")
        if set(run_values) - {
            "run_id",
            "source_pr",
            "channel",
            "outcome",
            "attributable",
            "source_head",
            "reviewer",
            "scope",
            "coverage_limits",
            "started_at",
            "finished_at",
        }:
            raise ReviewRecordsError("failed CLI source run contains unsupported fields")
        if finish_values.get("state") not in {"failed", "rate_limited", "timed_out"}:
            raise ReviewRecordsError("partial CLI observations require a terminal failed attempt")
        artifacts = finish_values.get("artifacts")
        if not isinstance(artifacts, Mapping) or set(artifacts) - {"cli_events", "cli_diagnostic", "metadata"}:
            raise ReviewRecordsError("failed CLI observation artifacts are invalid")
        if (
            run_values.get("run_id") != attempt_id
            or run_values.get("channel") != "cli"
            or run_values.get("outcome") != "failed"
            or run_values.get("attributable") is not False
        ):
            raise ReviewRecordsError("partial CLI source run must remain failed and non-attributable")
        events_text = artifacts.get("cli_events")
        if not isinstance(events_text, str):
            raise ReviewRecordsError("failed CLI observations require validated CLI event evidence")
        from . import evidence

        try:
            parsed_findings = evidence.parse_failed_capture_events(events_text)
        except evidence.EvidenceError as exc:
            raise ReviewRecordsError("failed CLI event evidence is malformed or incomplete") from exc
        if not parsed_findings:
            raise ReviewRecordsError("failed CLI event evidence contains no findings")
        from .sqlite_finding_text import _safe_finding_detail
        from .sqlite_provider_imports import _cli_detail, _cli_finding_title

        observations = []
        for index, finding in enumerate(parsed_findings, 1):
            instructions = finding.get("codegenInstructions")
            observations.append(
                FindingObservation(
                    source_finding_key=f"cli-run:{attempt_id}:finding:{index}",
                    title=_cli_finding_title(instructions, f"CodeRabbit CLI finding {index}"),
                    detail=_safe_finding_detail(_cli_detail(instructions)),
                )
            )
        run_values["findings"] = tuple(observations)
        finished_at = finish_values.get("finished_at")
        if not isinstance(finished_at, str) or run_values.get("finished_at") != finished_at:
            raise ReviewRecordsError("failed CLI source run must use the attempt's exact terminal time")

        try:
            with self._write_connection() as connection:
                attempt = connection.execute(
                    "SELECT source_pr, channel, candidate_sha, state, started_at, finished_at, duration_seconds, "
                    "exit_status, run_id, diagnostic FROM review_attempts WHERE attempt_id = ?",
                    (attempt_id,),
                ).fetchone()
                if attempt is None:
                    raise ReviewRecordsError("failed CLI attempt does not exist")
                (
                    source_pr,
                    channel,
                    candidate_sha,
                    state,
                    started_at,
                    prior_finished,
                    duration,
                    exit_status,
                    linked,
                    diagnostic,
                ) = attempt
                if (
                    channel != "cli"
                    or source_pr != run_values.get("source_pr")
                    or candidate_sha != run_values.get("source_head")
                    or run_values.get("started_at") != started_at
                ):
                    raise ReviewRecordsError("failed CLI source identity does not match its exact attempt")
                if state not in {"started", "failed", "rate_limited", "timed_out"}:
                    raise ReviewRecordsError("CLI attempt is not in a recoverable failed terminal state")
                if linked not in (None, attempt_id):
                    raise ReviewRecordsError("CLI attempt is already linked to a different source run")
                other_link = connection.execute(
                    "SELECT 1 FROM review_attempts WHERE run_id = ? AND attempt_id != ? LIMIT 1",
                    (attempt_id, attempt_id),
                ).fetchone()
                if other_link is not None:
                    raise ReviewRecordsError("failed CLI source run is already linked to another attempt")

                event_artifact = _archive_artifact("cli_events", events_text)
                if state == "started":
                    self.finish_attempt(attempt_id, _connection=connection, **finish_values)
                else:
                    expected_terminal = (
                        finish_values.get("state"),
                        finished_at,
                        finish_values.get("duration_seconds"),
                        finish_values.get("exit_status"),
                        finish_values.get("diagnostic", ""),
                    )
                    if (state, prior_finished, duration, exit_status, diagnostic) != expected_terminal:
                        raise ReviewRecordsError("failed CLI capture conflicts with its terminal attempt")
                    existing_artifacts = {
                        row[0]: row[1]
                        for row in connection.execute(
                            "SELECT kind, source_sha256 FROM review_artifacts WHERE attempt_id = ?",
                            (attempt_id,),
                        )
                    }
                    event_digest = event_artifact[1]
                    if existing_artifacts.get("cli_events") not in (None, event_digest):
                        raise ReviewRecordsError("failed CLI event capture conflicts with its archived digest")
                    if existing_artifacts.get("cli_raw_output") not in (None, event_digest):
                        raise ReviewRecordsError("failed CLI raw output conflicts with its event capture digest")
                    if not {"cli_events", "cli_raw_output"} & existing_artifacts.keys():
                        raise ReviewRecordsError("failed CLI attempt has no matching archived stdout digest")
                    if "cli_events" not in existing_artifacts:
                        content, source_sha256, redactions = event_artifact
                        connection.execute(
                            "INSERT INTO review_artifacts (attempt_id, kind, content, source_sha256, redactions) "
                            "VALUES (?, 'cli_events', ?, ?, ?)",
                            (attempt_id, content, source_sha256, redactions),
                        )

                recorded = self.record_run(_connection=connection, **run_values)
                self._link_failed_cli_run(connection, attempt_id)
        except ReviewRecordsError:
            raise
        except sqlite3.IntegrityError as exc:
            raise ReviewRecordsError("failed CLI observations conflict with existing immutable records") from exc
        except sqlite3.DatabaseError as exc:
            raise ReviewRecordsError("cannot record failed CLI observations") from exc
        return {
            "attempt_id": attempt_id,
            "state": finish_values["state"],
            "run": recorded,
            "idempotent_replay": bool(recorded.get("idempotent_replay")),
        }

    @staticmethod
    def _link_failed_cli_run(connection: sqlite3.Connection, attempt_id: str) -> None:
        attempt = connection.execute(
            "SELECT source_pr, channel, candidate_sha, state, run_id FROM review_attempts WHERE attempt_id = ?",
            (attempt_id,),
        ).fetchone()
        run = connection.execute(
            "SELECT source_pr, channel, source_head, outcome, attributable FROM review_runs WHERE run_id = ?",
            (attempt_id,),
        ).fetchone()
        if (
            attempt is None
            or run is None
            or attempt[1] != "cli"
            or attempt[3] not in {"failed", "rate_limited", "timed_out"}
            or run != (attempt[0], "cli", attempt[2], "failed", 0)
        ):
            raise ReviewRecordsError("failed CLI attempt and non-attributable source run do not match")
        if attempt[4] not in (None, attempt_id):
            raise ReviewRecordsError("CLI attempt is already linked to a different source run")
        connection.execute("UPDATE review_attempts SET run_id = ? WHERE attempt_id = ?", (attempt_id, attempt_id))

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
                self.finalize_run(attempt_id, finalized_at=run.get("finished_at"), _connection=connection)
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
                "provider_review_id FROM review_attempts WHERE attempt_id = ?",
                (attempt_id,),
            ).fetchone()
            if (
                attempt is None
                or attempt[1] != "hosted"
                or attempt[3] != "completed"
                or attempt[4] is not None
                or attempt[5] is None
                or attempt[6] is None
                or attempt[0] != run.get("source_pr")
                or attempt[2] != run.get("source_head")
            ):
                raise ReviewRecordsError("completed Hosted attempt is not recoverable by this run")
            archived = {
                row[0]
                for row in connection.execute("SELECT kind FROM review_artifacts WHERE attempt_id = ?", (attempt_id,))
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
                columns = "repository, source_pr, channel, provider_id, checkpoint_id, checkpoint_fingerprint, run_id"
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
                    connection.execute("INSERT INTO provider_origins VALUES (?, ?, ?, ?, ?, ?, ?)", expected)
                    replay = False
        except ReviewRecordsError:
            raise
        except sqlite3.Error as exc:
            raise ReviewRecordsError("cannot link provider origin") from exc
        return {
            "run_id": run_id,
            "checkpoint_id": checkpoint_id,
            "provider_id": provider_id,
            "idempotent_replay": replay,
        }

    @_translate_database_errors
    def archive_imported_artifacts(self, run_id: str, artifacts: Mapping[str, str]) -> dict[str, Any]:
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
                row[0]: (row[1], row[2], row[3])
                for row in connection.execute(
                    "SELECT kind, content, source_sha256, redactions FROM imported_artifacts WHERE run_id = ?",
                    (run_id,),
                )
            }
            if existing:
                if (
                    "metadata" in existing
                    and "metadata" in archived
                    and self._same_checkpoint_projection(existing["metadata"][0], archived["metadata"][0])
                ):
                    # The added parsed projection must not rewrite any prior
                    # immutable capture bytes, source hash or redaction count.
                    archived["metadata"] = existing["metadata"]
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

    @staticmethod
    def _same_checkpoint_projection(stored_content: str, incoming_content: str) -> bool:
        """Recognize only the hash-verified projection added to older metadata."""

        from .evidence import Checkpoint
        from .sqlite_provider_imports import _checkpoint_fingerprint

        try:
            stored = json.loads(stored_content)
            incoming = json.loads(incoming_content)
            if not isinstance(stored, dict) or not isinstance(incoming, dict) or "checkpoint_fields" in stored:
                return False
            fields = incoming.pop("checkpoint_fields")
            checkpoint = Checkpoint(**fields)
            return (
                incoming == stored
                and checkpoint.as_json() == stored["checkpoint"]
                and _checkpoint_fingerprint(checkpoint) == stored["checkpoint_fingerprint"]
            )
        except (KeyError, TypeError, ValueError):
            return False

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
        checkpoint_json, checkpoint_digest, checkpoint_redactions = _archive_artifact("metadata", serialized_checkpoint)
        archived = {kind: _archive_artifact(kind, content) for kind, content in artifacts.items()}
        expected = (
            repository,
            source_pr,
            channel,
            checkpoint_id,
            checkpoint_fingerprint,
            checkpoint_json,
            checkpoint_digest,
            checkpoint_redactions,
            missing_reason,
        )
        try:
            with self._write_connection() as connection:
                if connection.execute(
                    "SELECT 1 FROM provider_origins WHERE repository = ? AND source_pr = ? AND checkpoint_id = ?",
                    (repository, source_pr, checkpoint_id),
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
                        row[0]: (row[1], row[2], row[3])
                        for row in connection.execute(
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
            "repository": repository,
            "source_pr": source_pr,
            "channel": channel,
            "checkpoint_id": checkpoint_id,
            "kinds": sorted(archived),
            "idempotent_replay": replay,
        }

    @_translate_database_errors
    def cli_capture_snapshot(self, attempt_id: str, *, source_pr: int) -> dict[str, Any] | None:
        """Read one native CLI attempt, linked source run, artifacts, and decisions atomically.

        ``None`` means the run ID has no structured association and may use the
        retained historical capture path. Any partial or conflicting SQL
        association raises instead of allowing a raw-file fallback.
        """

        attempt_id = _safe_identifier(attempt_id, "attempt ID", maximum=100)
        source_pr = _positive_pr(source_pr, "source PR")
        self._require_regular_database()
        with contextlib.closing(self._connect(read_only=True)) as connection:
            connection.execute("BEGIN")
            self._require_compatible(connection)
            return self._cli_capture_snapshot(connection, attempt_id, source_pr=source_pr)

    @_translate_database_errors
    def completed_cli_capture_snapshots(self, source_pr: int) -> list[dict[str, Any]]:
        """Read all completed native CLI captures for one PR from one snapshot."""

        source_pr = _positive_pr(source_pr, "source PR")
        self._require_regular_database()
        with contextlib.closing(self._connect(read_only=True)) as connection:
            connection.execute("BEGIN")
            self._require_compatible(connection)
            attempt_ids = [
                row[0]
                for row in connection.execute(
                    "SELECT attempt_id AS run_id FROM review_attempts "
                    "WHERE source_pr = ? AND channel = 'cli' AND state = 'completed' "
                    "UNION SELECT r.run_id FROM review_runs r WHERE r.source_pr = ? AND r.channel = 'cli' "
                    "AND r.outcome = 'completed' AND NOT EXISTS ("
                    "SELECT 1 FROM review_attempts a WHERE a.attempt_id = r.run_id) "
                    "AND NOT EXISTS (SELECT 1 FROM imported_artifacts i WHERE i.run_id = r.run_id) "
                    "AND NOT EXISTS (SELECT 1 FROM provider_origins o WHERE o.run_id = r.run_id) "
                    "AND EXISTS (SELECT 1 FROM finding_observations o JOIN findings f USING (finding_id) "
                    "WHERE o.run_id = r.run_id AND "
                    "substr(f.source_finding_key, 1, length('cli-run:' || r.run_id || ':finding:')) = "
                    "'cli-run:' || r.run_id || ':finding:') "
                    "ORDER BY run_id",
                    (source_pr, source_pr),
                )
            ]
            snapshots = []
            for attempt_id in attempt_ids:
                snapshot = self._cli_capture_snapshot(connection, attempt_id, source_pr=source_pr)
                if snapshot is None:
                    raise ReviewRecordsError("completed CLI attempt has no structured association")
                snapshots.append(snapshot)
            return snapshots

    @staticmethod
    def _cli_capture_snapshot(
        connection: sqlite3.Connection, attempt_id: str, *, source_pr: int
    ) -> dict[str, Any] | None:
        attempt = connection.execute(
            "SELECT source_pr, channel, candidate_sha, state, started_at, finished_at, duration_seconds, "
            "exit_status, run_id, metadata_json FROM review_attempts WHERE attempt_id = ?",
            (attempt_id,),
        ).fetchone()
        linked_attempt = connection.execute(
            "SELECT attempt_id FROM review_attempts WHERE run_id = ? AND attempt_id != ? LIMIT 1",
            (attempt_id, attempt_id),
        ).fetchone()
        run = connection.execute(
            "SELECT source_pr, channel, source_head, outcome, attributable, started_at, finished_at, "
            "found_count, accepted_count, routed_count, finalized, import_payload_json FROM review_runs WHERE run_id = ?",
            (attempt_id,),
        ).fetchone()
        origin = connection.execute(
            "SELECT repository, source_pr, channel, run_id FROM provider_origins WHERE run_id = ? LIMIT 1",
            (attempt_id,),
        ).fetchone()
        imported = connection.execute(
            "SELECT 1 FROM imported_artifacts WHERE run_id = ? LIMIT 1", (attempt_id,)
        ).fetchone()
        artifact_rows = connection.execute(
            "SELECT kind, content, redactions FROM review_artifacts WHERE attempt_id = ?", (attempt_id,)
        ).fetchall()
        artifacts = {row[0]: row[1] for row in artifact_rows}
        artifact_redactions = {row[0]: row[2] for row in artifact_rows}
        if not any((attempt, linked_attempt, run, origin, imported, artifacts)):
            return None
        if attempt is None:
            raise ReviewRecordsError("CLI SQL association is missing its attempt")
        if linked_attempt is not None:
            raise ReviewRecordsError("CLI SQL run ID is linked to a different attempt")
        if attempt[0] != source_pr or attempt[1] != "cli" or attempt[8] not in (None, attempt_id):
            raise ReviewRecordsError("CLI SQL attempt does not match its exact source run and PR")
        if attempt[3] in {"failed", "rate_limited", "timed_out", "ambiguous"}:
            raise CliCaptureTerminalFailure("CLI SQL attempt ended without a completed source run")
        if attempt[3] == "started":
            raise CliCaptureInProgress("CLI SQL attempt is still in progress")
        if attempt[3] != "completed":
            raise ReviewRecordsError("CLI SQL attempt is not terminally completed")
        if attempt[8] != attempt_id or run is None:
            raise ReviewRecordsError("CLI SQL association is missing its exact linked source run")
        if run[0] != source_pr or run[1] != "cli":
            raise ReviewRecordsError("CLI SQL source run does not match its exact attempt and PR")
        if origin is not None and (origin[1] != source_pr or origin[2] != "cli"):
            raise ReviewRecordsError("CLI provider origin conflicts with its source run")
        try:
            attempt_metadata = json.loads(attempt[9])
        except (TypeError, json.JSONDecodeError) as exc:
            raise ReviewRecordsError("CLI SQL attempt metadata is malformed") from exc
        if not isinstance(attempt_metadata, dict):
            raise ReviewRecordsError("CLI SQL attempt metadata is not an object")
        if "cli_raw_output" in artifacts:
            raise ReviewRecordsError("completed CLI SQL attempt contains conflicting raw output")
        if not {"cli_events", "metadata"} <= artifacts.keys():
            raise ReviewRecordsError("completed CLI SQL attempt lacks complete archived events or metadata")
        if attempt[3] != "completed" or attempt[7] != 0:
            raise ReviewRecordsError("CLI SQL attempt is not a successful terminal completion")
        if run[3] != "completed" or not run[4] or run[2] != attempt[2]:
            raise ReviewRecordsError("CLI SQL source run is not completed, attributable, and linked to its head")

        try:
            original_findings = json.loads(run[11])["findings"]
            if not isinstance(original_findings, list) or not all(isinstance(item, dict) for item in original_findings):
                raise ValueError("invalid original findings")
            original_by_key = {item["source_finding_key"]: item for item in original_findings}
            if len(original_by_key) != run[7] or len(original_findings) != run[7]:
                raise ValueError("invalid original finding count")
        except (KeyError, TypeError, ValueError) as exc:
            raise ReviewRecordsError("CLI SQL immutable source projection is incomplete") from exc

        rows = connection.execute(
            "SELECT f.source_finding_key, o.disposition, d.decision, d.reason, c.decision, c.reason, o.title, o.detail, "
            "(SELECT COUNT(*) FROM decisions d2 WHERE d2.run_id = o.run_id "
            "AND d2.finding_id = o.finding_id AND d2.decision_scope = 'source') "
            "FROM finding_observations o JOIN findings f USING (finding_id) "
            "LEFT JOIN decisions d ON d.run_id = o.run_id AND d.finding_id = o.finding_id "
            "AND d.decision_scope = 'source' "
            "LEFT JOIN source_decision_corrections c ON c.sequence = ("
            "SELECT MAX(sequence) FROM source_decision_corrections "
            "WHERE run_id = o.run_id AND finding_id = o.finding_id) "
            "WHERE o.run_id = ? ORDER BY f.source_finding_key",
            (attempt_id,),
        ).fetchall()
        observations: list[dict[str, Any]] = []
        decisions: dict[int, tuple[str, str]] = {}
        prefix = f"cli-run:{attempt_id}:finding:"
        for key, disposition, decision, reason, correction, correction_reason, title, detail, decision_count in rows:
            if not isinstance(key, str) or not key.startswith(prefix) or not key[len(prefix) :].isdigit():
                raise ReviewRecordsError("CLI source finding has an invalid finding key")
            suffix = key[len(prefix) :]
            index = int(suffix)
            if str(index) != suffix:
                raise ReviewRecordsError("CLI source finding index is not canonical")
            if index in decisions or any(item["index"] == index for item in observations):
                raise ReviewRecordsError("CLI source finding index is duplicated")
            if decision_count > 1:
                raise ReviewRecordsError("CLI source finding has duplicate decisions")
            if correction is not None and decision_count != 1:
                raise ReviewRecordsError("CLI source correction has no exact original decision")
            effective_decision = correction or decision
            effective_reason = correction_reason if correction is not None else reason
            if disposition == "unresolved":
                if effective_decision is not None:
                    raise ReviewRecordsError("unresolved CLI source finding has a stored decision")
            elif (
                disposition not in {"accepted", "routed", "rejected"}
                or effective_decision != disposition
                or not isinstance(effective_reason, str)
            ):
                raise ReviewRecordsError("CLI source decisions conflict with stored finding dispositions")
            else:
                decisions[index] = (effective_decision, effective_reason)
            original = original_by_key.get(key)
            if original is None or original.get("title") != title or original.get("detail") != detail:
                raise ReviewRecordsError("CLI SQL finding conflicts with its immutable source projection")
            observations.append(
                {
                    "index": index,
                    "source_finding_key": key,
                    "disposition": disposition,
                    "title": title,
                    "detail": detail,
                    "detail_recorded": detail != "",
                }
            )
        if len(observations) != run[7] or sorted(item["index"] for item in observations) != list(range(1, run[7] + 1)):
            raise ReviewRecordsError("CLI SQL source findings do not match the stored run count")
        accepted = sum(item["disposition"] == "accepted" for item in observations)
        routed = sum(item["disposition"] == "routed" for item in observations)
        if (run[8], run[9]) != (accepted, routed):
            raise ReviewRecordsError("CLI SQL decisions do not match stored source counts")
        if run[10] and (len(decisions) != run[7] or any(item["disposition"] == "unresolved" for item in observations)):
            raise ReviewRecordsError("finalized CLI SQL source run has incomplete decisions")
        return {
            "attempt": {
                "attempt_id": attempt_id,
                "source_pr": attempt[0],
                "channel": attempt[1],
                "candidate_sha": attempt[2],
                "state": attempt[3],
                "started_at": attempt[4],
                "finished_at": attempt[5],
                "duration_seconds": attempt[6],
                "exit_status": attempt[7],
                "run_id": attempt[8],
                "metadata": attempt_metadata,
            },
            "run": {
                "run_id": attempt_id,
                "source_pr": run[0],
                "channel": run[1],
                "source_head": run[2],
                "outcome": run[3],
                "attributable": bool(run[4]),
                "started_at": run[5],
                "finished_at": run[6],
                "counts": {"found": run[7], "accepted": run[8], "routed": run[9]},
                "finalized": bool(run[10]),
            },
            "artifacts": artifacts,
            "artifact_redactions": artifact_redactions,
            "observations": observations,
            "decisions": decisions,
            "provider_origin": origin,
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
            if not key.startswith(prefix) or not key[len(prefix) :].isdigit():
                raise ReviewRecordsError("CLI source decision has an invalid finding key")
            index = int(key[len(prefix) :])
            if index in result:
                raise ReviewRecordsError("CLI source decision index is duplicated")
            result[index] = (decision, reason)
        return result

    @_translate_database_errors
    def correct_subagent_record(
        self, run_id: str, source_finding_key: str, *, actor: str, reason: str
    ) -> dict[str, Any]:
        """Retain a rejected non-finding as a note without rewriting its evidence."""

        run_id = _safe_identifier(run_id, "run ID", maximum=100)
        source_finding_key = _safe_identifier(source_finding_key, "finding key", maximum=200)
        actor = _bounded_text(actor, "actor", maximum=100)
        reason = _bounded_text(reason, "correction reason", maximum=300)
        with self._write_connection() as connection:
            row = connection.execute(
                "SELECT a.metadata_json FROM review_attempts a JOIN review_runs r ON r.run_id = a.run_id "
                "WHERE a.attempt_id = ? AND a.run_id = ? AND a.channel = 'subagent' "
                "AND a.state = 'completed' AND r.channel = 'subagent' AND r.outcome = 'completed' "
                "AND r.finalized = 1 AND a.source_pr = r.source_pr",
                (run_id, run_id),
            ).fetchone()
            if row is None:
                raise ReviewRecordsError("correction requires a completed linked finalized subagent run")
            metadata = json.loads(row[0])
            if not isinstance(metadata, dict):
                raise ReviewRecordsError("review attempt metadata is not an object")
            existing = self._subagent_record_corrections(connection, run_id)
            prior = next((item for item in existing if item["source_finding_key"] == source_finding_key), None)
            if prior is not None:
                if prior["actor"] != actor or prior["reason"] != reason:
                    raise ReviewRecordsError("non-finding correction already has different provenance")
                replay = True
            else:
                self._subagent_non_finding_observation(connection, run_id, source_finding_key)
                corrections = metadata.setdefault("record_corrections", [])
                if len(corrections) >= 200:
                    raise ReviewRecordsError("a subagent run may record at most 200 non-finding corrections")
                correction = {
                    "source_finding_key": source_finding_key,
                    "actor": actor,
                    "reason": reason,
                    "corrected_at": _timestamp(None, "correction time"),
                }
                corrections.append(correction)
                serialized = _json(metadata)
                _, _, redactions = _archive_artifact("metadata", serialized)
                if redactions:
                    raise ReviewRecordsError("correction provenance contains credential-shaped material")
                connection.execute(
                    "UPDATE review_attempts SET metadata_json = ? WHERE attempt_id = ?", (serialized, run_id)
                )
                replay = False
            source_pr = connection.execute("SELECT source_pr FROM review_runs WHERE run_id = ?", (run_id,)).fetchone()[
                0
            ]
            run = next(
                item for item in self.history(source_pr, _connection=connection)["runs"] if item["run_id"] == run_id
            )
        return {
            "run_id": run_id,
            "source_finding_key": source_finding_key,
            "counts": run["counts"],
            "original_counts": run["original_counts"],
            "idempotent_replay": replay,
        }

    @_translate_database_errors
    def correct_subagent_run_count(
        self,
        run_id: str,
        *,
        correction_id: str,
        excluded_from_review_counts: bool,
        actor: str,
        reason: str,
    ) -> dict[str, Any]:
        """Append an audited correction to one zero-finding subagent run's count status."""

        run_id = _safe_identifier(run_id, "run ID", maximum=100)
        correction_id = _safe_identifier(correction_id, "correction ID", maximum=200)
        if type(excluded_from_review_counts) is not bool:
            raise ReviewRecordsError("review-count correction action must be explicit")
        action = "exclude_from_review_counts" if excluded_from_review_counts else "restore_to_review_counts"
        actor = _bounded_text(actor, "actor", maximum=100)
        reason = _bounded_text(reason, "correction reason", maximum=300)
        with self._write_connection() as connection:
            metadata = self._require_zero_finding_subagent_run(connection, run_id)
            corrections = self._subagent_run_count_corrections(connection, run_id, metadata=metadata)
            prior = next((item for item in corrections if item["correction_id"] == correction_id), None)
            if prior is not None:
                if prior["action"] != action or prior["actor"] != actor or prior["reason"] != reason:
                    raise ReviewRecordsError("run-count correction ID already has different provenance")
                replay = True
            else:
                currently_excluded = bool(corrections and corrections[-1]["action"] == "exclude_from_review_counts")
                if currently_excluded == excluded_from_review_counts:
                    raise ReviewRecordsError("run-count correction must change the current review-count status")
                if len(corrections) >= 200:
                    raise ReviewRecordsError("a subagent run may record at most 200 review-count corrections")
                correction = {
                    "correction_id": correction_id,
                    "action": action,
                    "actor": actor,
                    "reason": reason,
                    "corrected_at": _timestamp(None, "correction time"),
                }
                metadata.setdefault("review_count_corrections", []).append(correction)
                serialized = _json(metadata)
                _, _, redactions = _archive_artifact("metadata", serialized)
                if redactions:
                    raise ReviewRecordsError("correction provenance contains credential-shaped material")
                connection.execute(
                    "UPDATE review_attempts SET metadata_json = ? WHERE attempt_id = ?", (serialized, run_id)
                )
                corrections.append(correction)
                replay = False
            latest = corrections[-1]
        return {
            "run_id": run_id,
            "excluded_from_review_counts": latest["action"] == "exclude_from_review_counts",
            "review_count_corrections": corrections,
            "idempotent_replay": replay,
        }

    def _require_zero_finding_subagent_run(self, connection: sqlite3.Connection, run_id: str) -> dict[str, Any]:
        row = connection.execute(
            "SELECT a.metadata_json, a.state, a.source_pr, a.channel, a.run_id, r.source_pr, r.channel, "
            "r.outcome, r.finalized, r.found_count, r.accepted_count, r.routed_count "
            "FROM review_attempts a LEFT JOIN review_runs r ON r.run_id = a.run_id WHERE a.attempt_id = ?",
            (run_id,),
        ).fetchone()
        if (
            row is None
            or row[1] != "completed"
            or row[2] != row[5]
            or row[3] != "subagent"
            or row[4] != run_id
            or row[6] != "subagent"
            or row[7] != "completed"
            or row[8] != 1
        ):
            raise ReviewRecordsError(
                "review-count correction requires an exact completed linked finalized subagent run"
            )
        if (row[9], row[10], row[11]) != (0, 0, 0):
            raise ReviewRecordsError("review-count correction requires zero source counts")
        evidence = connection.execute(
            "SELECT "
            "(SELECT COUNT(*) FROM finding_observations WHERE run_id = ?), "
            "(SELECT COUNT(*) FROM decisions WHERE run_id = ?), "
            "(SELECT COUNT(*) FROM routes r JOIN finding_observations o USING (finding_id) WHERE o.run_id = ?), "
            "(SELECT COUNT(*) FROM source_finding_resolutions WHERE run_id = ?)",
            (run_id, run_id, run_id, run_id),
        ).fetchone()
        if evidence != (0, 0, 0, 0):
            raise ReviewRecordsError(
                "review-count correction cannot hide observations, decisions, routes, or fix evidence"
            )
        metadata = json.loads(row[0])
        if not isinstance(metadata, dict):
            raise ReviewRecordsError("review attempt metadata is not an object")
        return metadata

    def _subagent_run_count_corrections(
        self,
        connection: sqlite3.Connection,
        run_id: str,
        *,
        metadata: dict[str, Any] | None = None,
    ) -> list[dict[str, Any]]:
        if metadata is None:
            row = connection.execute(
                "SELECT metadata_json FROM review_attempts WHERE attempt_id = ?", (run_id,)
            ).fetchone()
            if row is None:
                return []
            try:
                metadata = json.loads(row[0])
            except json.JSONDecodeError as exc:
                raise ReviewRecordsError("review attempt metadata is malformed") from exc
        if not isinstance(metadata, dict):
            raise ReviewRecordsError("review attempt metadata is not an object")
        if "review_count_corrections" not in metadata:
            return []
        corrections = metadata["review_count_corrections"]
        if not isinstance(corrections, list) or len(corrections) > 200:
            raise ReviewRecordsError("subagent review-count correction history is malformed")
        if not corrections:
            raise ReviewRecordsError("subagent review-count correction history is malformed")
        self._require_zero_finding_subagent_run(connection, run_id)
        result = []
        correction_ids = set()
        currently_excluded = False
        for correction in corrections:
            if not isinstance(correction, dict) or set(correction) != {
                "correction_id",
                "action",
                "actor",
                "reason",
                "corrected_at",
            }:
                raise ReviewRecordsError("subagent review-count correction history is malformed")
            correction_id = _safe_identifier(correction["correction_id"], "correction ID", maximum=200)
            if correction_id in correction_ids:
                raise ReviewRecordsError("subagent review-count correction history repeats a correction ID")
            correction_ids.add(correction_id)
            action = correction["action"]
            if action == "exclude_from_review_counts":
                if currently_excluded:
                    raise ReviewRecordsError("subagent review-count correction history repeats an exclusion")
                currently_excluded = True
            elif action == "restore_to_review_counts":
                if not currently_excluded:
                    raise ReviewRecordsError("subagent review-count correction history restores a counted run")
                currently_excluded = False
            else:
                raise ReviewRecordsError("subagent review-count correction action is invalid")
            actor = _bounded_text(correction["actor"], "actor", maximum=100)
            reason = _bounded_text(correction["reason"], "correction reason", maximum=300)
            corrected_at = correction["corrected_at"]
            if not isinstance(corrected_at, str):
                raise ReviewRecordsError("stored review-count correction time is malformed")
            _timestamp(corrected_at, "correction time")
            result.append(
                {
                    "correction_id": correction_id,
                    "action": action,
                    "actor": actor,
                    "reason": reason,
                    "corrected_at": corrected_at,
                }
            )
        return result

    def _subagent_non_finding_observation(
        self, connection: sqlite3.Connection, run_id: str, source_finding_key: str
    ) -> dict[str, Any]:
        row = connection.execute(
            "SELECT o.run_id, o.finding_id, o.source_pr, o.source_channel, f.source_finding_key, "
            "o.title, o.detail, o.disposition, o.route_id, o.display_severity "
            "FROM finding_observations o JOIN findings f USING (finding_id) JOIN review_runs r USING (run_id) "
            "WHERE o.run_id = ? AND f.source_finding_key = ? "
            "AND o.source_pr = r.source_pr AND o.source_channel = r.channel",
            (run_id, source_finding_key),
        ).fetchone()
        if row is None or row[3] != "subagent" or row[7] != "rejected" or row[8] is not None:
            raise ReviewRecordsError("only an exact rejected subagent observation may become a non-finding note")
        if connection.execute(
            "SELECT 1 FROM routes WHERE finding_id = ? UNION ALL "
            "SELECT 1 FROM source_finding_resolutions WHERE finding_id = ? UNION ALL "
            "SELECT 1 FROM decisions WHERE run_id = ? AND finding_id = ? AND decision != 'rejected' UNION ALL "
            "SELECT 1 FROM source_decision_corrections WHERE run_id = ? AND finding_id = ? "
            "AND decision != 'rejected'",
            (row[1], row[1], run_id, row[1], run_id, row[1]),
        ).fetchone():
            raise ReviewRecordsError("a finding with route, accepted decision, or fix evidence cannot become a note")
        if (
            connection.execute(
                "SELECT COUNT(*) FROM decisions WHERE decision_scope = 'source' AND run_id = ? AND finding_id = ? "
                "AND decision = 'rejected'",
                (run_id, row[1]),
            ).fetchone()[0]
            != 1
        ):
            raise ReviewRecordsError("non-finding correction requires the exact rejected source decision")
        return self._observation_record(row)

    def _subagent_record_corrections(self, connection: sqlite3.Connection, run_id: str) -> list[dict[str, Any]]:
        row = connection.execute(
            "SELECT a.metadata_json, a.state, a.source_pr, r.source_pr, r.channel, r.finalized "
            "FROM review_attempts a JOIN review_runs r ON r.run_id = a.run_id "
            "WHERE a.attempt_id = ? AND a.run_id = ? AND a.channel = 'subagent'",
            (run_id, run_id),
        ).fetchone()
        if row is None:
            return []
        metadata = json.loads(row[0])
        if not isinstance(metadata, dict):
            raise ReviewRecordsError("review attempt metadata is not an object")
        corrections = metadata.get("record_corrections", [])
        if not isinstance(corrections, list) or len(corrections) > 200:
            raise ReviewRecordsError("subagent record correction history is malformed")
        if corrections and (row[1] != "completed" or row[2] != row[3] or row[4] != "subagent" or not row[5]):
            raise ReviewRecordsError("subagent record corrections require a completed finalized association")
        result = []
        keys = set()
        for correction in corrections:
            if not isinstance(correction, dict) or set(correction) != {
                "source_finding_key",
                "actor",
                "reason",
                "corrected_at",
            }:
                raise ReviewRecordsError("subagent record correction history is malformed")
            key = _safe_identifier(correction["source_finding_key"], "finding key", maximum=200)
            if key in keys:
                raise ReviewRecordsError("subagent record correction history repeats a finding")
            keys.add(key)
            _bounded_text(correction["actor"], "actor", maximum=100)
            _bounded_text(correction["reason"], "correction reason", maximum=300)
            if not isinstance(correction["corrected_at"], str):
                raise ReviewRecordsError("stored subagent correction time is malformed")
            _timestamp(correction["corrected_at"], "correction time")
            observation = self._subagent_non_finding_observation(connection, run_id, key)
            result.append({"run_id": run_id, **correction, "original_observation": observation})
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
                if any(
                    item["source_finding_key"] == source_finding_key
                    for item in self._subagent_record_corrections(connection, run_id)
                ):
                    raise ReviewRecordsError("a retained non-finding note cannot change its source decision")
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
                    "ORDER BY sequence DESC LIMIT 1",
                    (run_id, finding_id),
                ).fetchone()
                if initial is None or supersedes_id != (latest or initial)[0]:
                    raise ReviewRecordsError("correction does not name the latest exact decision")
                if (
                    decision != "accepted"
                    and connection.execute(
                        "SELECT 1 FROM source_finding_resolutions WHERE run_id = ? AND finding_id = ?",
                        (run_id, finding_id),
                    ).fetchone()
                    is not None
                ):
                    raise ReviewRecordsError("a resolved source finding cannot be corrected away from accepted")
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
            "correction_id": correction_id,
            "run_id": run_id,
            "prior_decision_id": supersedes_id,
            "decision": decision,
            "counts": {"found": counts[0], "accepted": counts[1], "routed": counts[2]},
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
        coverage = _coverage_limits(coverage_limits)
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
                    **({"display_severity": item.display_severity} if item.display_severity is not None else {}),
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
            with self._write_connection() if _connection is None else contextlib.nullcontext(_connection) as connection:
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
                        "(run_id, finding_id, source_pr, source_channel, title, detail, disposition, route_id, "
                        "display_severity) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                        (
                            run_id,
                            finding_id,
                            source_pr,
                            channel,
                            item.title,
                            item.detail,
                            item.disposition,
                            route_id,
                            item.display_severity,
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
                    "AND run_id = ? AND finding_id = ?",
                    (run_id, finding_id),
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
                    "UPDATE finding_observations SET disposition = ?, route_id = ? WHERE run_id = ? AND finding_id = ?",
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

    def record_source_resolution(
        self,
        run_id: str,
        source_finding_key: str,
        *,
        source_pr: int,
        resolution_id: str,
        fix_sha: str,
        actor: str,
        proof_note: str,
        resolved_at: str | None = None,
    ) -> dict[str, Any]:
        """Record one immutable accepted-fix proof for an exact source finding."""

        run_id = _safe_identifier(run_id, "run_id", maximum=100)
        source_finding_key = _safe_identifier(source_finding_key, "source_finding_key", maximum=200)
        source_pr = _positive_pr(source_pr, "source PR")
        resolution_id = _safe_identifier(resolution_id, "resolution_id", maximum=200)
        fix_sha = _text(fix_sha, "fix SHA", maximum=64)
        if not re.fullmatch(r"[0-9a-fA-F]{40}|[0-9a-fA-F]{64}", fix_sha):
            raise ReviewRecordsError("fix SHA must be a full 40- or 64-character commit identifier")
        actor = _bounded_text(actor, "actor", maximum=100)
        proof_note = _bounded_text(proof_note, "proof note", maximum=300)
        resolved_at = _timestamp(resolved_at, "resolved_at")
        try:
            with self._write_connection() as connection:
                run = connection.execute(
                    "SELECT source_pr, channel, outcome, attributable, finalized FROM review_runs WHERE run_id = ?",
                    (run_id,),
                ).fetchone()
                if run is None:
                    raise ReviewRecordsError("source run does not exist")
                observed_pr, channel, outcome, attributable, finalized = run
                if observed_pr != source_pr:
                    raise ReviewRecordsError("source PR does not match the exact source run")
                if outcome != "completed" or not attributable or not finalized:
                    raise ReviewRecordsError("source resolution requires a completed attributable finalized run")
                finding = connection.execute(
                    "SELECT f.finding_id, o.disposition FROM finding_observations o "
                    "JOIN findings f USING (finding_id) "
                    "WHERE o.run_id = ? AND o.source_pr = ? AND o.source_channel = ? "
                    "AND f.source_finding_key = ?",
                    (run_id, source_pr, channel, source_finding_key),
                ).fetchone()
                if finding is None:
                    raise ReviewRecordsError("source finding was not observed in that exact run and PR")
                finding_id, disposition = finding
                if disposition != "accepted":
                    raise ReviewRecordsError("only an effectively accepted source finding can be resolved")
                if (
                    connection.execute(
                        "SELECT 1 FROM decisions WHERE decision_scope = 'source' AND run_id = ? AND finding_id = ?",
                        (run_id, finding_id),
                    ).fetchone()
                    is None
                ):
                    raise ReviewRecordsError("accepted source finding has no source decision evidence")

                expected = (
                    run_id,
                    finding_id,
                    source_pr,
                    channel,
                    "accepted_fixed",
                    fix_sha,
                    actor,
                    proof_note,
                )
                existing = connection.execute(
                    "SELECT resolution_id, run_id, finding_id, source_pr, source_channel, outcome, fix_sha, "
                    "actor, proof_note, resolved_at FROM source_finding_resolutions "
                    "WHERE run_id = ? AND finding_id = ?",
                    (run_id, finding_id),
                ).fetchone()
                if existing is not None:
                    same_claim = tuple(existing[:9]) == (resolution_id, *expected)
                    if not same_claim:
                        raise ReviewRecordsError("source finding already has a different immutable resolution")
                    correction_chain = self._source_resolution_corrections(connection, resolution_id, existing[6])
                    if correction_chain is None:
                        raise ReviewRecordsError("source finding resolution correction history is invalid")
                    corrections, effective_fix_sha = correction_chain
                    return {
                        "resolution_id": existing[0],
                        "run_id": existing[1],
                        "finding_id": existing[2],
                        "source_pr": existing[3],
                        "source_channel": existing[4],
                        "outcome": existing[5],
                        "fix_sha": existing[6],
                        "effective_fix_sha": effective_fix_sha,
                        "corrections": corrections,
                        "actor": existing[7],
                        "proof_note": existing[8],
                        "resolved_at": existing[9],
                        "idempotent_replay": True,
                    }
                if (
                    connection.execute(
                        "SELECT 1 FROM source_finding_resolutions WHERE resolution_id = ?", (resolution_id,)
                    ).fetchone()
                    is not None
                ):
                    raise ReviewRecordsError("source resolution ID is already used")
                connection.execute(
                    "INSERT INTO source_finding_resolutions "
                    "(resolution_id, run_id, finding_id, source_pr, source_channel, outcome, fix_sha, actor, "
                    "proof_note, resolved_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    (resolution_id, *expected, resolved_at),
                )
        except ReviewRecordsError:
            raise
        except sqlite3.IntegrityError as exc:
            raise ReviewRecordsError("source finding resolution conflicts with existing immutable records") from exc
        except sqlite3.DatabaseError as exc:
            raise ReviewRecordsError("cannot record SQLite source finding resolution") from exc
        return {
            "resolution_id": resolution_id,
            "run_id": run_id,
            "finding_id": finding_id,
            "source_pr": source_pr,
            "source_channel": channel,
            "outcome": "accepted_fixed",
            "fix_sha": fix_sha,
            "effective_fix_sha": fix_sha,
            "corrections": [],
            "actor": actor,
            "proof_note": proof_note,
            "resolved_at": resolved_at,
            "idempotent_replay": False,
        }

    def correct_source_resolution(
        self,
        run_id: str,
        source_finding_key: str,
        *,
        source_pr: int,
        resolution_id: str,
        expected_fix_sha: str,
        fix_sha: str,
        correction_id: str,
        actor: str,
        reason: str,
        proof_note: str,
        corrected_at: str | None = None,
    ) -> dict[str, Any]:
        """Append a proof-SHA correction without changing the original resolution."""

        run_id = _safe_identifier(run_id, "run_id", maximum=100)
        source_finding_key = _safe_identifier(source_finding_key, "source_finding_key", maximum=200)
        source_pr = _positive_pr(source_pr, "source PR")
        resolution_id = _safe_identifier(resolution_id, "resolution_id", maximum=200)
        correction_id = _safe_identifier(correction_id, "correction_id", maximum=200)
        expected_fix_sha = _text(expected_fix_sha, "expected fix SHA", maximum=64)
        fix_sha = _text(fix_sha, "corrected fix SHA", maximum=64)
        for value, label in ((expected_fix_sha, "expected fix SHA"), (fix_sha, "corrected fix SHA")):
            if not re.fullmatch(r"[0-9a-fA-F]{40}|[0-9a-fA-F]{64}", value):
                raise ReviewRecordsError(f"{label} must be a full 40- or 64-character commit identifier")
        expected_fix_sha = expected_fix_sha.casefold()
        fix_sha = fix_sha.casefold()
        actor = _bounded_text(actor, "actor", maximum=100)
        reason = _bounded_text(reason, "correction reason", maximum=300)
        proof_note = _bounded_text(proof_note, "proof note", maximum=300)
        requested_corrected_at = _timestamp(corrected_at, "corrected_at", optional=True)
        corrected_at = _timestamp(corrected_at, "corrected_at")
        try:
            with self._write_connection() as connection:
                original = connection.execute(
                    "SELECT r.run_id, r.finding_id, r.source_pr, r.source_channel, r.outcome, r.fix_sha, "
                    "o.disposition, f.source_finding_key FROM source_finding_resolutions r "
                    "JOIN finding_observations o ON o.run_id = r.run_id AND o.finding_id = r.finding_id "
                    "JOIN findings f USING (finding_id) WHERE r.resolution_id = ?",
                    (resolution_id,),
                ).fetchone()
                if original is None:
                    raise ReviewRecordsError("source resolution does not exist")
                (
                    original_run,
                    _finding_id,
                    original_pr,
                    _source_channel,
                    outcome,
                    original_sha,
                    disposition,
                    original_key,
                ) = original
                if (
                    original_run != run_id
                    or original_pr != source_pr
                    or original_key != source_finding_key
                    or outcome != "accepted_fixed"
                    or disposition != "accepted"
                    or not isinstance(original_sha, str)
                    or not re.fullmatch(r"[0-9a-fA-F]{40}|[0-9a-fA-F]{64}", original_sha)
                ):
                    raise ReviewRecordsError("source resolution does not match the exact accepted finding")

                replay = connection.execute(
                    "SELECT resolution_id, expected_fix_sha, corrected_fix_sha, actor, reason, proof_note, corrected_at "
                    "FROM source_finding_resolution_corrections WHERE correction_id = ?",
                    (correction_id,),
                ).fetchone()
                claim = (resolution_id, expected_fix_sha, fix_sha, actor, reason, proof_note, corrected_at)
                if replay is not None:
                    if tuple(replay[:6]) != claim[:6] or (
                        requested_corrected_at is not None and replay[6] != requested_corrected_at
                    ):
                        raise ReviewRecordsError("correction ID is already used for a different immutable correction")
                    return {
                        "correction_id": correction_id,
                        "resolution_id": resolution_id,
                        "expected_fix_sha": expected_fix_sha,
                        "fix_sha": fix_sha,
                        "actor": actor,
                        "reason": reason,
                        "proof_note": proof_note,
                        "corrected_at": replay[6],
                        "idempotent_replay": True,
                    }

                chain = self._source_resolution_corrections(connection, resolution_id, original_sha)
                if chain is None:
                    raise ReviewRecordsError("existing source resolution correction history is invalid")
                current_sha = chain[1]
                if current_sha.casefold() != expected_fix_sha:
                    raise ReviewRecordsError("expected fix SHA does not match the current effective source proof")
                connection.execute(
                    "INSERT INTO source_finding_resolution_corrections "
                    "(correction_id, resolution_id, expected_fix_sha, corrected_fix_sha, actor, reason, proof_note, "
                    "corrected_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    (correction_id, *claim),
                )
        except ReviewRecordsError:
            raise
        except sqlite3.IntegrityError as exc:
            raise ReviewRecordsError("source resolution correction conflicts with existing immutable records") from exc
        except sqlite3.DatabaseError as exc:
            raise ReviewRecordsError("cannot record source resolution correction") from exc
        return {
            "correction_id": correction_id,
            "resolution_id": resolution_id,
            "expected_fix_sha": expected_fix_sha,
            "fix_sha": fix_sha,
            "actor": actor,
            "reason": reason,
            "proof_note": proof_note,
            "corrected_at": corrected_at,
            "idempotent_replay": False,
        }

    def set_source_severity(
        self,
        run_id: str,
        source_finding_key: str,
        *,
        severity: str,
    ) -> dict[str, Any]:
        """Set display-only severity for one exact persisted finding observation."""

        run_id = _safe_identifier(run_id, "run_id", maximum=100)
        source_finding_key = _safe_identifier(source_finding_key, "source_finding_key", maximum=200)
        if not isinstance(severity, str) or severity not in {"Critical", "Major", "Minor", "Trivial"}:
            raise ReviewRecordsError("severity is invalid")
        try:
            with self._write_connection() as connection:
                observation = connection.execute(
                    "SELECT o.finding_id, o.source_pr, o.source_channel, o.display_severity "
                    "FROM finding_observations o JOIN findings f USING (finding_id) "
                    "WHERE o.run_id = ? AND f.source_finding_key = ?",
                    (run_id, source_finding_key),
                ).fetchone()
                if observation is None:
                    raise ReviewRecordsError("source finding was not observed in that exact run")
                finding_id, source_pr, source_channel, previous_severity = observation
                changed = previous_severity != severity
                if changed:
                    connection.execute(
                        "UPDATE finding_observations SET display_severity = ? WHERE run_id = ? AND finding_id = ?",
                        (severity, run_id, finding_id),
                    )
        except ReviewRecordsError:
            raise
        except sqlite3.DatabaseError as exc:
            raise ReviewRecordsError("cannot set source finding severity") from exc
        return {
            "run_id": run_id,
            "finding_id": finding_id,
            "source_pr": source_pr,
            "source_channel": source_channel,
            "source_finding_key": source_finding_key,
            "previous_severity": previous_severity,
            "severity": severity,
            "changed": changed,
        }

    @staticmethod
    def _source_resolution_corrections(
        connection: sqlite3.Connection, resolution_id: str, original_sha: str
    ) -> tuple[list[dict[str, Any]], str] | None:
        """Validate the append-only correction chain and return its effective SHA."""

        if not re.fullmatch(r"[0-9a-fA-F]{40}|[0-9a-fA-F]{64}", original_sha):
            return None
        effective_sha = original_sha.casefold()
        corrections: list[dict[str, Any]] = []
        rows = connection.execute(
            "SELECT sequence, correction_id, expected_fix_sha, corrected_fix_sha, actor, reason, proof_note, "
            "corrected_at FROM source_finding_resolution_corrections WHERE resolution_id = ? ORDER BY sequence",
            (resolution_id,),
        ).fetchall()
        for row in rows:
            sequence, correction_id, expected_sha, corrected_sha, actor, reason, proof_note, corrected_at = row
            if (
                isinstance(sequence, bool)
                or not isinstance(sequence, int)
                or not isinstance(correction_id, str)
                or not isinstance(expected_sha, str)
                or not re.fullmatch(r"[0-9a-fA-F]{40}|[0-9a-fA-F]{64}", expected_sha)
                or expected_sha.casefold() != effective_sha
                or not isinstance(corrected_sha, str)
                or not re.fullmatch(r"[0-9a-fA-F]{40}|[0-9a-fA-F]{64}", corrected_sha)
            ):
                return None
            try:
                corrections.append(
                    {
                        "sequence": sequence,
                        "correction_id": _safe_identifier(correction_id, "correction_id", maximum=200),
                        "expected_fix_sha": expected_sha,
                        "fix_sha": corrected_sha,
                        "actor": _bounded_text(actor, "actor", maximum=100),
                        "reason": _bounded_text(reason, "correction reason", maximum=300),
                        "proof_note": _bounded_text(proof_note, "proof note", maximum=300),
                        "corrected_at": _timestamp(corrected_at, "corrected_at"),
                    }
                )
            except ReviewRecordsError:
                return None
            effective_sha = corrected_sha.casefold()
        return corrections, effective_sha

    @_translate_database_errors
    def source_resolution_status(
        self,
        run_id: str,
        *,
        source_pr: int,
        source_channel: ReviewChannel,
        source_head: str,
        accepted_count: int,
        source_checkpoint: Any = None,
        source_repository: str | None = None,
        classify_pending: bool = False,
    ) -> str | None:
        """Return proof status; optionally distinguish proven unresolved accepted findings.

        Legacy callers retain ``pending`` for all incomplete proof. A caller
        requesting classification receives ``finding_pending`` only after the
        exact source association, finalized counts and existing fix proofs pass.
        """

        run_id = _safe_identifier(run_id, "run_id", maximum=100)
        source_pr = _positive_pr(source_pr, "source PR")
        if not isinstance(source_channel, str) or source_channel not in {"hosted", "cli", "manual", "subagent"}:
            raise ReviewRecordsError("source channel is invalid")
        source_head = _text(source_head, "source head", maximum=64)
        if not re.fullmatch(r"[0-9a-fA-F]{40}|[0-9a-fA-F]{64}", source_head):
            raise ReviewRecordsError("source head must be a full commit identifier")
        if isinstance(accepted_count, bool) or not isinstance(accepted_count, int) or accepted_count < 0:
            raise ReviewRecordsError("accepted count must be a non-negative integer")
        self._require_regular_database()
        with contextlib.closing(self._connect(read_only=True)) as connection:
            connection.execute("BEGIN")
            self._require_compatible(connection)
            if source_checkpoint is not None:
                from .evidence import Checkpoint

                if not isinstance(source_checkpoint, Checkpoint) or not isinstance(source_repository, str):
                    raise ReviewRecordsError("source checkpoint binding requires parsed checkpoint and repository")
                provider_ids = (
                    (source_checkpoint.run_id, f"run:{source_checkpoint.run_id}")
                    if source_channel == "cli"
                    else (str(source_checkpoint.hosted_review_id), f"review:{source_checkpoint.hosted_review_id}")
                )
                origins = connection.execute(
                    "SELECT repository, source_pr, channel, provider_id, checkpoint_id, checkpoint_fingerprint, run_id "
                    "FROM provider_origins WHERE checkpoint_id = ? OR "
                    "(source_pr = ? AND channel = ? AND provider_id IN (?, ?))",
                    (source_checkpoint.comment_id, source_pr, source_channel, *provider_ids),
                ).fetchall()
                if origins:
                    if len(origins) != 1:
                        return "pending"
                    origin = origins[0]
                    if (
                        origin[0] != source_repository.casefold()
                        or origin[1] != source_pr
                        or origin[2] != source_channel
                        or origin[3] not in provider_ids
                        or origin[4] != source_checkpoint.comment_id
                        or not self._source_checkpoint_matches(connection, origin[6], origin[5], source_checkpoint)
                    ):
                        return "pending"
                    if source_channel == "cli":
                        if (
                            origin[6] != run_id
                            and connection.execute(
                                "SELECT 1 FROM review_runs WHERE run_id = ? "
                                "UNION ALL SELECT 1 FROM review_attempts WHERE attempt_id = ? OR run_id = ? LIMIT 1",
                                (run_id, run_id, run_id),
                            ).fetchone()
                            is not None
                        ):
                            return "pending"
                        run_id = origin[6]
                    elif origin[6] != run_id:
                        return "pending"
            run = connection.execute(
                "SELECT source_pr, channel, source_head, outcome, attributable, accepted_count, finalized "
                "FROM review_runs WHERE run_id = ?",
                (run_id,),
            ).fetchone()
            if run is None and source_channel == "cli":
                # CLI markers predate SQLite. Only an actual source association
                # switches a retained capture from legacy to structured proof.
                # Keep partial attempts/origins pending even if their run or
                # link is missing, rather than silently downgrading to legacy.
                associated = connection.execute(
                    "SELECT 1 FROM review_attempts WHERE attempt_id = ? OR run_id = ? "
                    "UNION ALL SELECT 1 FROM provider_origins WHERE run_id = ? LIMIT 1",
                    (run_id, run_id, run_id),
                ).fetchone()
                if associated is None:
                    return None
            if (
                run is None
                or run[0] != source_pr
                or run[1] != source_channel
                or not isinstance(run[2], str)
                or run[2].casefold() != source_head.casefold()
                or run[3] != "completed"
                or not run[4]
                or run[5] != accepted_count
                or not run[6]
            ):
                return "pending"
            accepted = connection.execute(
                "SELECT o.finding_id, f.source_finding_key, r.resolution_id, r.source_pr, r.source_channel, "
                "r.outcome, r.fix_sha, r.actor, r.proof_note, r.resolved_at, "
                "(SELECT COUNT(*) FROM decisions d WHERE d.decision_scope = 'source' "
                "AND d.run_id = o.run_id AND d.finding_id = o.finding_id) "
                "FROM finding_observations o JOIN findings f USING (finding_id) "
                "LEFT JOIN source_finding_resolutions r ON r.run_id = o.run_id AND r.finding_id = o.finding_id "
                "WHERE o.run_id = ? AND o.source_pr = ? AND o.source_channel = ? AND o.disposition = 'accepted'",
                (run_id, source_pr, source_channel),
            ).fetchall()
            all_resolutions = connection.execute(
                "SELECT COUNT(*) FROM source_finding_resolutions WHERE run_id = ?", (run_id,)
            ).fetchone()[0]
            if len(accepted) != accepted_count or all_resolutions != sum(row[2] is not None for row in accepted):
                return "pending"
            finding_pending = False
            for row in accepted:
                (
                    _,
                    source_finding_key,
                    resolution_id,
                    proof_pr,
                    proof_channel,
                    outcome,
                    fix_sha,
                    actor,
                    note,
                    at,
                    decision_count,
                ) = row
                if not isinstance(source_finding_key, str) or not source_finding_key or decision_count < 1:
                    return "pending"
                if resolution_id is None:
                    # A missing fix is finding-only evidence only after its
                    # accepted source and every existing sibling proof validate.
                    if any(value is not None for value in (proof_pr, proof_channel, outcome, fix_sha, actor, note, at)):
                        return "pending"
                    finding_pending = True
                    continue
                if (
                    not isinstance(resolution_id, str)
                    or proof_pr != source_pr
                    or proof_channel != source_channel
                    or outcome != "accepted_fixed"
                    or not isinstance(fix_sha, str)
                    or not re.fullmatch(r"[0-9a-fA-F]{40}|[0-9a-fA-F]{64}", fix_sha)
                ):
                    return "pending"
                try:
                    _bounded_text(actor, "actor", maximum=100)
                    _bounded_text(note, "proof note", maximum=300)
                    _timestamp(at, "resolved_at")
                except ReviewRecordsError:
                    return "pending"
                correction_chain = self._source_resolution_corrections(connection, resolution_id, fix_sha)
                if correction_chain is None:
                    return "pending"
            if finding_pending:
                return "finding_pending" if classify_pending else "pending"
            return "resolved"

    @staticmethod
    def _source_checkpoint_matches(
        connection: sqlite3.Connection, run_id: str, fingerprint: str, checkpoint: Any
    ) -> bool:
        """Verify retained checkpoint identity while neutralizing only its edit timestamp."""

        from .sqlite_provider_imports import _checkpoint_fingerprint

        row = connection.execute(
            "SELECT content FROM imported_artifacts WHERE run_id = ? AND kind = 'metadata'", (run_id,)
        ).fetchone()
        if row is None:
            return False
        try:
            metadata = json.loads(row[0])
            stored = metadata["checkpoint"]
            if not isinstance(stored, dict) or metadata.get("checkpoint_fingerprint") != fingerprint:
                return False
            if "checkpoint_fields" in metadata:
                recorded = type(checkpoint)(**metadata["checkpoint_fields"])
                if recorded.as_json() != stored:
                    return False
            else:
                required = (
                    "comment_id",
                    "created_at",
                    "type",
                    "raw_found",
                    "accepted",
                    "reviewed_sha",
                    "file_count",
                    "correction",
                )
                recorded = dataclasses.replace(
                    checkpoint,
                    **{key: stored[key] for key in required},
                    updated_at=stored.get("updated_at"),
                    run_id=stored.get("run_id"),
                    hosted_review_id=stored.get("hosted_review_id"),
                    duration_seconds=stored.get("duration_seconds"),
                    duration_invalid=stored.get("duration_invalid", False),
                    routed=stored.get("routed"),
                    author_login=stored.get("author_login", checkpoint.author_login),
                )
            # Only older snapshots without the author use the immutable
            # current GitHub author, and still have to reproduce the hash.
            return (
                _checkpoint_fingerprint(recorded) == fingerprint
                and _checkpoint_fingerprint(dataclasses.replace(checkpoint, updated_at=recorded.updated_at))
                == fingerprint
            )
        except (KeyError, TypeError, ValueError):
            return False

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
        retained_coverage: Sequence[str] = ()
        if (
            channel == "subagent"
            and isinstance(coverage_limits, Sequence)
            and not isinstance(coverage_limits, (str, bytes))
            and any(isinstance(item, str) and len(item) > 200 for item in coverage_limits)
        ):
            try:
                attempt = self.attempt(run_id)
            except AttemptNotFound:
                pass
            else:
                metadata = attempt["metadata"]
                if (
                    attempt["source_pr"] == source_pr
                    and attempt["channel"] == channel
                    and attempt["candidate_sha"] == source_head
                    and attempt["started_at"] == started_at
                    and attempt["state"] in {"started", "completed"}
                    and metadata.get("reviewer") == reviewer
                    and metadata.get("scope") == scope
                    and isinstance(metadata.get("coverage_limits"), list)
                ):
                    retained_coverage = metadata["coverage_limits"]
        coverage = _coverage_limits(coverage_limits, retained=retained_coverage)
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
                "source_finding_key",
                "decision_id",
                "decision",
                "actor",
                "reason",
                "target_pr",
                "decided_at",
                "route_id",
                "route_status",
            }
            if set(item) - allowed or not {"source_finding_key", "decision_id", "decision", "actor", "reason"} <= set(
                item
            ):
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
                    **({"display_severity": item.display_severity} if item.display_severity is not None else {}),
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
                            "(run_id, finding_id, source_pr, source_channel, title, detail, disposition, route_id, "
                            "display_severity) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                            (
                                run_id,
                                finding_id,
                                source_pr,
                                channel,
                                item.title,
                                item.detail,
                                item.disposition,
                                route_id,
                                item.display_severity,
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
                    "SELECT COUNT(*) FROM finding_observations WHERE run_id = ? AND disposition = 'unresolved'",
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
        self,
        run_id: str,
        *,
        finalized_at: str | None = None,
        _connection: sqlite3.Connection | None = None,
    ) -> dict[str, Any]:
        """Freeze the source counts after every finding has a source disposition."""

        run_id = _safe_identifier(run_id, "run_id", maximum=100)
        finalized_at = _timestamp(finalized_at, "finalized_at")
        try:
            with self._write_connection() if _connection is None else contextlib.nullcontext(_connection) as connection:
                run = connection.execute(
                    "SELECT finalized, finalized_at FROM review_runs WHERE run_id = ?", (run_id,)
                ).fetchone()
                if run is None:
                    raise ReviewRecordsError("review run does not exist")
                counts = self._current_run_counts(connection, run_id)
                unresolved = connection.execute(
                    "SELECT COUNT(*) FROM finding_observations WHERE run_id = ? AND disposition = 'unresolved'",
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
                counts = (counts[0] - len(self._subagent_record_corrections(connection, run_id)), counts[1], counts[2])
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
                connection.execute(
                    "UPDATE routes SET target_pr = ?, updated_at = ? WHERE route_id = ?",
                    (target_pr, changed_at, route_id),
                )
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
        return {
            "resolution_id": resolution_id,
            "route_id": route_id,
            "resolution_pr": resolution_pr,
            "outcome": outcome,
        }

    def history(
        self,
        pr: int,
        *,
        include_legacy_routes: bool = False,
        include_display: bool = True,
        deadline: float | None = None,
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
        if not isinstance(include_display, bool):
            raise ReviewRecordsError("include_display must be boolean")
        deadline = self._effective_deadline(deadline)
        self._check_deadline(deadline)
        try:
            with (
                contextlib.closing(self._connect(read_only=True, deadline=deadline))
                if _connection is None
                else contextlib.nullcontext(_connection)
            ) as connection:
                if _connection is None:
                    self._set_busy_timeout(connection, deadline)
                    connection.execute("BEGIN")
                self._require_compatible(connection, deadline=deadline)
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
                        "o.title, o.detail, o.disposition, o.route_id, o.display_severity "
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
                source_resolutions = []
                source_resolution_corrections = []
                for row in connection.execute(
                    "SELECT r.resolution_id, r.run_id, r.finding_id, r.source_pr, r.source_channel, "
                    "f.source_finding_key, r.outcome, r.fix_sha, r.actor, r.proof_note, r.resolved_at, "
                    "o.source_pr, o.source_channel, rr.source_pr, rr.channel "
                    "FROM source_finding_resolutions r JOIN findings f USING (finding_id) "
                    "JOIN finding_observations o ON o.run_id = r.run_id AND o.finding_id = r.finding_id "
                    "JOIN review_runs rr ON rr.run_id = r.run_id "
                    "WHERE r.source_pr = ? ORDER BY r.resolved_at, r.resolution_id",
                    (pr,),
                ):
                    (
                        resolution_id,
                        run_id,
                        finding_id,
                        source_pr,
                        source_channel,
                        source_finding_key,
                        outcome,
                        fix_sha,
                        actor,
                        proof_note,
                        resolved_at,
                        observation_pr,
                        observation_channel,
                        run_pr,
                        run_channel,
                    ) = row
                    if (
                        source_pr != pr
                        or source_channel not in {"hosted", "cli", "manual", "subagent"}
                        or observation_pr != source_pr
                        or observation_channel != source_channel
                        or run_pr != source_pr
                        or run_channel != source_channel
                        or outcome != "accepted_fixed"
                        or not isinstance(fix_sha, str)
                        or not re.fullmatch(r"[0-9a-fA-F]{40}|[0-9a-fA-F]{64}", fix_sha)
                    ):
                        raise ReviewRecordsError("stored source finding resolution is malformed")
                    correction_chain = self._source_resolution_corrections(connection, resolution_id, fix_sha)
                    if correction_chain is None:
                        raise ReviewRecordsError("stored source finding resolution correction history is malformed")
                    corrections, effective_fix_sha = correction_chain
                    source_resolution_corrections.extend(
                        {"resolution_id": resolution_id, **correction} for correction in corrections
                    )
                    source_resolutions.append(
                        {
                            "resolution_id": _safe_identifier(resolution_id, "resolution_id", maximum=200),
                            "run_id": _safe_identifier(run_id, "run_id", maximum=100),
                            "finding_id": _safe_identifier(finding_id, "finding_id", maximum=64),
                            "source_pr": source_pr,
                            "source_channel": source_channel,
                            "source_finding_key": _safe_identifier(
                                source_finding_key, "source_finding_key", maximum=200
                            ),
                            "outcome": outcome,
                            "fix_sha": fix_sha,
                            "effective_fix_sha": effective_fix_sha,
                            "corrections": corrections,
                            "actor": _bounded_text(actor, "actor", maximum=100),
                            "proof_note": _bounded_text(proof_note, "proof note", maximum=300),
                            "resolved_at": _timestamp(resolved_at, "resolved_at"),
                        }
                    )
                attempts = []
                corrected_subagent_runs = set()
                review_count_corrections_by_run = {}
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
                    if (
                        row[1] == "subagent"
                        and metadata.get("record_corrections") != []
                        and "record_corrections" in metadata
                    ):
                        if row[0] != row[11] or row[3] != "completed":
                            raise ReviewRecordsError(
                                "subagent record corrections have no completed exact run association"
                            )
                        corrected_subagent_runs.add(row[11])
                    if "review_count_corrections" in metadata:
                        if row[1] != "subagent":
                            raise ReviewRecordsError("review-count corrections are only valid for subagent attempts")
                        review_count_corrections_by_run[row[0]] = self._subagent_run_count_corrections(
                            connection, row[0], metadata=metadata
                        )
                    attempts.append(
                        {
                            "attempt_id": row[0],
                            "channel": row[1],
                            "candidate_sha": row[2],
                            "state": row[3],
                            "started_at": row[4],
                            "finished_at": row[5],
                            "duration_seconds": row[6],
                            "exit_status": row[7],
                            "trigger_id": row[8],
                            "provider_review_id": row[9],
                            "checkpoint_id": row[10],
                            "run_id": row[11],
                            "diagnostic": row[12],
                            "origin": metadata.get("origin"),
                            "legacy_outcome": metadata.get("legacy_outcome"),
                            "repository": metadata.get("repository"),
                            **(
                                {"model": metadata["model"]}
                                if row[1] == "subagent" and isinstance(metadata.get("model"), str)
                                else {}
                            ),
                            **(
                                {"reasoning_effort": metadata["reasoning_effort"]}
                                if row[1] == "subagent" and isinstance(metadata.get("reasoning_effort"), str)
                                else {}
                            ),
                        }
                    )
                corrections = [
                    {
                        "correction_id": row[0],
                        "supersedes_id": row[1],
                        "run_id": row[2],
                        "finding_id": row[3],
                        "decision": row[4],
                        "target_pr": row[5],
                        "actor": row[6],
                        "reason": row[7],
                        "decided_at": row[8],
                    }
                    for row in connection.execute(
                        "SELECT c.correction_id, c.supersedes_id, c.run_id, c.finding_id, c.decision, "
                        "c.target_pr, c.actor, c.reason, c.decided_at "
                        "FROM source_decision_corrections c JOIN review_runs r USING (run_id) "
                        "WHERE r.source_pr = ? ORDER BY c.sequence",
                        (pr,),
                    )
                ]
                provider_origins = [
                    {
                        "repository": row[0],
                        "source_pr": row[1],
                        "channel": row[2],
                        "provider_id": row[3],
                        "checkpoint_id": row[4],
                        "checkpoint_fingerprint": row[5],
                        "run_id": row[6],
                    }
                    for row in connection.execute(
                        "SELECT repository, source_pr, channel, provider_id, checkpoint_id, "
                        "checkpoint_fingerprint, run_id FROM provider_origins "
                        "WHERE source_pr = ? ORDER BY checkpoint_id",
                        (pr,),
                    )
                ]
                imported_artifacts = [
                    {"run_id": row[0], "kind": row[1], "source_sha256": row[2], "redactions": row[3]}
                    for row in connection.execute(
                        "SELECT a.run_id, a.kind, a.source_sha256, a.redactions "
                        "FROM imported_artifacts a JOIN review_runs r USING (run_id) "
                        "WHERE r.source_pr = ? ORDER BY a.run_id, a.kind",
                        (pr,),
                    )
                ]
                historical_gaps = [
                    {
                        "repository": row[0],
                        "source_pr": row[1],
                        "channel": row[2],
                        "checkpoint_id": row[3],
                        "checkpoint_fingerprint": row[4],
                        "checkpoint": json.loads(row[5]),
                        "checkpoint_source_sha256": row[6],
                        "checkpoint_redactions": row[7],
                        "missing_reason": row[8],
                        "superseded_by_run_id": row[9],
                    }
                    for row in connection.execute(
                        "SELECT g.repository, g.source_pr, g.channel, g.checkpoint_id, "
                        "g.checkpoint_fingerprint, g.checkpoint_json, g.checkpoint_source_sha256, "
                        "g.checkpoint_redactions, g.missing_reason, o.run_id "
                        "FROM historical_provider_gaps g LEFT JOIN provider_origins o "
                        "ON o.repository = g.repository AND o.source_pr = g.source_pr "
                        "AND o.checkpoint_id = g.checkpoint_id "
                        "WHERE g.source_pr = ? ORDER BY g.checkpoint_id",
                        (pr,),
                    )
                ]
                historical_gap_artifacts = [
                    {
                        "repository": row[0],
                        "source_pr": row[1],
                        "checkpoint_id": row[2],
                        "kind": row[3],
                        "source_sha256": row[4],
                        "redactions": row[5],
                    }
                    for row in connection.execute(
                        "SELECT repository, source_pr, checkpoint_id, kind, "
                        "source_sha256, redactions FROM historical_gap_artifacts "
                        "WHERE source_pr = ? ORDER BY checkpoint_id, kind",
                        (pr,),
                    )
                ]
                record_corrections = []
                for run in runs:
                    if run["channel"] != "subagent" or run["run_id"] not in corrected_subagent_runs:
                        continue
                    retained_notes = self._subagent_record_corrections(connection, run["run_id"])
                    if not retained_notes:
                        continue
                    record_corrections.extend(retained_notes)
                    run["original_counts"] = dict(run["counts"])
                    run["counts"] = {**run["counts"], "found": run["counts"]["found"] - len(retained_notes)}
                    if run["counts"]["found"] < run["counts"]["accepted"] + run["counts"]["routed"]:
                        raise ReviewRecordsError("subagent non-finding corrections conflict with retained counts")
                    excluded_keys = {item["source_finding_key"] for item in retained_notes}
                    observations = [
                        item
                        for item in observations
                        if item["run_id"] != run["run_id"] or item["source_finding_key"] not in excluded_keys
                    ]
                for run in runs:
                    count_corrections = review_count_corrections_by_run.get(run["run_id"])
                    if count_corrections is None:
                        continue
                    run["review_count_corrections"] = count_corrections
                    run["excluded_from_review_counts"] = count_corrections[-1]["action"] == "exclude_from_review_counts"
                if include_display:
                    self._add_hosted_display_titles(connection, observations, routes)
                    self._add_run_durations(connection, runs)
                self._check_deadline(deadline)
                return {
                    "pr": pr,
                    "runs": runs,
                    "findings": observations,
                    "routes": routes,
                    "decisions": decisions,
                    "source_resolutions": source_resolutions,
                    "source_resolution_corrections": source_resolution_corrections,
                    "attempts": attempts,
                    "corrections": corrections,
                    "record_corrections": record_corrections,
                    "provider_origins": provider_origins,
                    "imported_artifacts": imported_artifacts,
                    "historical_gaps": historical_gaps,
                    "historical_gap_artifacts": historical_gap_artifacts,
                }
        except ReviewRecordsError:
            raise
        except (OSError, sqlite3.DatabaseError, json.JSONDecodeError) as exc:
            if isinstance(exc, sqlite3.DatabaseError):
                self._raise_hosted_deadline_if_expired(deadline, error=exc)
            else:
                self._raise_hosted_deadline_if_expired(deadline)
            raise ReviewRecordsError("cannot read SQLite review history") from exc

    def history_batch(
        self,
        prs: Sequence[int],
        *,
        include_legacy_routes: bool = False,
        include_display: bool = True,
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
                    pr: self.history(
                        pr,
                        include_legacy_routes=include_legacy_routes,
                        include_display=include_display,
                        _connection=connection,
                    )
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
                    "ORDER BY r.started_at DESC, o.run_id DESC LIMIT 1), "
                    "(SELECT o.display_severity FROM finding_observations o JOIN review_runs r USING (run_id) "
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
                        route_select + " ORDER BY COALESCE(routes.target_pr, 0), routes.source_pr, routes.route_id"
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
                        route_select
                        + where
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
                        **({"display_severity": row[10]} if row[3] == "subagent" or row[10] is not None else {}),
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
                self._add_hosted_display_titles(connection, (), routes)
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

    def _add_run_durations(self, connection: sqlite3.Connection, runs: Sequence[dict[str, Any]]) -> None:
        """Expose recorded provider elapsed time, never infer runtime from import timestamps."""

        from .evidence import Checkpoint

        for run in runs:
            if run["channel"] not in {"hosted", "cli"}:
                continue
            # Presence is authoritative: null blocks legacy UI fallback from
            # resurrecting missing, invalid or conflicting timing evidence.
            run["duration_seconds"] = None
            durations = set()
            attempts = connection.execute(
                "SELECT duration_seconds FROM review_attempts WHERE run_id = ? AND source_pr = ? "
                "AND channel = ? AND state = 'completed'",
                (run["run_id"], run["source_pr"], run["channel"]),
            ).fetchall()
            if len(attempts) > 1:
                continue
            if attempts and attempts[0][0] is not None:
                duration = attempts[0][0]
                if type(duration) is not int or duration < 0:
                    continue
                durations.add(duration)
            row = connection.execute(
                "SELECT i.content, o.repository, o.source_pr, o.channel, o.provider_id, "
                "o.checkpoint_id, o.checkpoint_fingerprint FROM imported_artifacts i "
                "JOIN provider_origins o USING (run_id) WHERE i.run_id = ? AND i.kind = 'metadata'",
                (run["run_id"],),
            ).fetchone()
            if row is not None:
                try:
                    metadata = json.loads(row[0])
                    fields = (
                        dict(metadata["checkpoint_fields"])
                        if "checkpoint_fields" in metadata
                        else {
                            key: value
                            for key, value in metadata["checkpoint"].items()
                            if key in {field.name for field in dataclasses.fields(Checkpoint)}
                        }
                    )
                    for key in ("updated_at", "run_id", "hosted_review_id"):
                        fields.setdefault(key, None)
                    checkpoint = Checkpoint(**fields)
                    provider_id = (
                        f"run:{checkpoint.run_id}"
                        if run["channel"] == "cli"
                        else f"review:{checkpoint.hosted_review_id}"
                    )
                    if row[4].startswith("trigger:") and run["channel"] == "hosted":
                        provider_id = f"trigger:{metadata.get('trigger_id')}"
                    if (
                        metadata.get("repository") != row[1]
                        or metadata.get("pull_request") != run["source_pr"]
                        or row[2:4] != (run["source_pr"], run["channel"])
                        or checkpoint.type.casefold() != run["channel"]
                        or checkpoint.comment_id != row[5]
                        or provider_id != row[4]
                        or not self._source_checkpoint_matches(connection, run["run_id"], row[6], checkpoint)
                        or checkpoint.duration_invalid
                    ):
                        continue
                    if checkpoint.duration_seconds is not None:
                        duration = checkpoint.duration_seconds
                        if type(duration) is not int or duration < 0:
                            continue
                        durations.add(duration)
                    capture_duration = metadata.get("review_duration_seconds") if run["channel"] == "cli" else None
                    if capture_duration is not None:
                        if not isinstance(capture_duration, str) or not re.fullmatch(
                            r"0|[1-9][0-9]*", capture_duration.strip()
                        ):
                            continue
                        durations.add(int(capture_duration.strip()))
                except (KeyError, TypeError, ValueError, AttributeError):
                    continue
            if len(durations) == 1:
                run["duration_seconds"] = durations.pop()

    def _add_hosted_display_titles(
        self,
        connection: sqlite3.Connection,
        observations: Sequence[dict[str, Any]],
        routes: Sequence[dict[str, Any]],
    ) -> None:
        """Enrich malformed Hosted titles without changing any historical projection."""

        from .sqlite_finding_text import _unusable_hosted_title

        cache: dict[tuple[str, int], dict[str, dict[str, Any]]] = {}
        for record in (*observations, *routes):
            if record.get("source_channel") not in {"hosted", "cli"} or record.get("origin") == "legacy_controller":
                continue
            if "run_id" in record:
                run_id, title = record["run_id"], record["title"]
            else:
                # The route's source finding, not its receiving PR, owns the
                # archive. Match the same latest observation used by list_routes.
                row = connection.execute(
                    "SELECT o.run_id, o.title FROM finding_observations o "
                    "JOIN review_runs r USING (run_id) JOIN findings f USING (finding_id) "
                    "WHERE o.finding_id = ? AND o.source_pr = ? AND o.source_channel = ? "
                    "AND f.source_finding_key = ? "
                    "ORDER BY r.started_at DESC, o.run_id DESC LIMIT 1",
                    (record["finding_id"], record["source_pr"], record["source_channel"], record["source_finding_key"]),
                ).fetchone()
                if row is None:
                    continue
                run_id, title = row
            cache_key = (run_id, record["source_pr"])
            if cache_key not in cache:
                reader = (
                    self._hosted_display_titles
                    if record["source_channel"] == "hosted"
                    else self._cli_display_severities
                )
                cache[cache_key] = reader(connection, run_id, record["source_pr"])
            presentation = cache[cache_key].get(record["source_finding_key"])
            if presentation:
                if "display_severity" not in record:
                    record["display_severity"] = presentation["display_severity"]
                if (
                    _unusable_hosted_title(title) or title in presentation.get("classification_titles", ())
                ) and presentation.get("display_title"):
                    record["display_title"] = presentation["display_title"]
                if presentation.get("display_title_is_excerpt") and title == presentation.get("display_title"):
                    record["display_title_is_excerpt"] = True
                if presentation.get("display_detail"):
                    record["display_detail"] = presentation["display_detail"]

    def _cli_display_severities(
        self, connection: sqlite3.Connection, run_id: str, source_pr: int
    ) -> dict[str, dict[str, Any]]:
        """Associate retained provider severity with exact validated CLI finding ordinals."""

        from . import evidence

        try:
            attempts = connection.execute(
                "SELECT attempt_id FROM review_attempts WHERE run_id = ?", (run_id,)
            ).fetchall()
            if attempts:
                if attempts != [(run_id,)]:
                    return {}
                snapshot = self._cli_capture_snapshot(connection, run_id, source_pr=source_pr)
                if snapshot is None:
                    return {}
                repository = snapshot["attempt"]["metadata"].get("repository", "")
                capture = evidence._cli_capture_from_sql(snapshot, repository, source_pr)
                findings, capture_id = capture.findings, run_id
            else:
                rows = connection.execute(
                    "SELECT i.kind, i.content, o.repository, o.source_pr, o.channel, o.provider_id, "
                    "o.checkpoint_id, o.checkpoint_fingerprint FROM imported_artifacts i "
                    "JOIN provider_origins o USING (run_id) WHERE i.run_id = ? "
                    "AND i.kind IN ('metadata', 'cli_events')",
                    (run_id,),
                ).fetchall()
                if len(rows) != 2 or {row[0] for row in rows} != {"metadata", "cli_events"}:
                    return {}
                artifacts = {row[0]: row[1] for row in rows}
                origin = rows[0][2:]
                if any(row[2:] != origin for row in rows):
                    return {}
                metadata = json.loads(artifacts["metadata"])
                checkpoint = evidence.Checkpoint(**metadata["checkpoint_fields"])
                if (
                    origin[1:3] != (source_pr, "cli")
                    or metadata.get("repository") != origin[0]
                    or metadata.get("pull_request") != source_pr
                    or checkpoint.type.casefold() != "cli"
                    or metadata.get("run_id") != checkpoint.run_id
                    or origin[3] != f"run:{checkpoint.run_id}"
                    or origin[4] != checkpoint.comment_id
                    or not self._source_checkpoint_matches(connection, run_id, origin[5], checkpoint)
                ):
                    return {}
                findings, _ = evidence.parse_capture_events(artifacts["cli_events"])
                if len(findings) != checkpoint.raw_found:
                    return {}
                capture_id = checkpoint.run_id
            keys = {
                row[0]
                for row in connection.execute(
                    "SELECT f.source_finding_key FROM finding_observations o JOIN findings f USING (finding_id) "
                    "WHERE o.run_id = ? AND o.source_pr = ? AND o.source_channel = 'cli'",
                    (run_id, source_pr),
                )
            }
            expected = {f"cli-run:{capture_id}:finding:{index}" for index in range(1, len(findings) + 1)}
            if keys != expected:
                return {}
            labels = {
                label.casefold(): label
                for label in ("Critical", "Major", "Minor", "Trivial", "High", "Medium", "Low", "P0", "P1", "P2", "P3")
            }
            return {
                f"cli-run:{capture_id}:finding:{index}": {
                    "display_severity": labels.get(finding["severity"].strip().casefold())
                    if isinstance(finding.get("severity"), str)
                    else None,
                }
                for index, finding in enumerate(findings, 1)
            }
        except (evidence.EvidenceError, ReviewRecordsError, KeyError, TypeError, ValueError, AttributeError):
            return {}

    def _hosted_display_titles(
        self, connection: sqlite3.Connection, run_id: str, source_pr: int
    ) -> dict[str, dict[str, Any]]:
        """Use complete retained comments and canonical finding keys; omit uncertain evidence."""

        from . import github
        from .sqlite_hosted_capture import HostedCaptureError, _hosted_comment_finding_segments

        run = connection.execute("SELECT source_pr, channel FROM review_runs WHERE run_id = ?", (run_id,)).fetchone()
        if run != (source_pr, "hosted"):
            return {}
        archives = [
            row[0]
            for row in connection.execute(
                "SELECT content FROM imported_artifacts WHERE run_id = ? AND kind = 'hosted_comments'", (run_id,)
            )
        ]
        for (attempt_id,) in connection.execute(
            "SELECT attempt_id FROM review_attempts WHERE run_id = ? AND source_pr = ? "
            "AND channel = 'hosted' AND state = 'completed'",
            (run_id, source_pr),
        ):
            content = self.attempt_artifacts(attempt_id, _connection=connection).get("hosted_comments")
            if content is not None:
                archives.append(content)
        bodies: dict[int, str] = {}
        ambiguous_ids: set[int] = set()
        try:
            for content in archives:
                archive = json.loads(content)
                if not isinstance(archive, dict) or archive.get("pull_request", source_pr) != source_pr:
                    return {}
                comments = archive.get("comments", [])
                threads = archive.get("review_threads", [])
                if not isinstance(comments, list) or not isinstance(threads, list):
                    return {}
                for thread in threads:
                    if not isinstance(thread, dict):
                        return {}
                    nodes = thread.get("comments", {}).get("nodes", [])
                    if not isinstance(nodes, list):
                        return {}
                    comments = [*comments, *nodes[:1]]
                for comment in comments:
                    if not isinstance(comment, dict):
                        continue
                    author = comment.get("author", comment.get("user"))
                    if not isinstance(author, dict) or not github.is_coderabbit_login(author.get("login")):
                        continue
                    if comment.get("in_reply_to_id") is not None:
                        continue
                    try:
                        comment_id = github.immutable_database_id(comment)
                    except (ValueError, TypeError, OverflowError):
                        continue
                    body = comment.get("body")
                    if comment_id is None or not isinstance(body, str) or comment_id in ambiguous_ids:
                        continue
                    if comment_id in bodies and bodies[comment_id] != body:
                        # Conflicting exact identity never picks a variant, including later repeats.
                        ambiguous_ids.add(comment_id)
                        bodies.pop(comment_id)
                        continue
                    bodies[comment_id] = body
            titles = {}
            for comment_id, body in bodies.items():
                try:
                    findings = _hosted_comment_finding_segments(comment_id, body)
                except HostedCaptureError:
                    # Invalid individual comments cannot invalidate independent exact-key siblings.
                    continue
                aggregate_key = f"hosted-comment:{comment_id}"
                aggregate = connection.execute(
                    "SELECT o.title FROM finding_observations o JOIN findings f USING (finding_id) "
                    "WHERE o.run_id = ? AND o.source_pr = ? AND f.source_finding_key = ?",
                    (run_id, source_pr, aggregate_key),
                ).fetchone()
                if len(findings) > 1 and aggregate is not None:
                    from .sqlite_finding_text import _hosted_aggregate_display_detail

                    first_title = findings[0]["title"]
                    severity = {finding["display_severity"] for finding in findings}
                    titles[aggregate_key] = {
                        "display_title": first_title,
                        "display_detail": _hosted_aggregate_display_detail(findings, aggregate[0]),
                        "display_severity": next(iter(severity)) if len(severity) == 1 else None,
                    }
                for finding in findings:
                    title = finding["title"]
                    if title.startswith(f"CodeRabbit review comment {comment_id}"):
                        title = None
                    else:
                        # Enforce the same bounded/secret-free title contract as writes.
                        try:
                            FindingObservation(source_finding_key=finding["key"], title=title)
                        except ReviewRecordsError:
                            title = None
                    titles[finding["key"]] = {
                        "display_title": title,
                        "display_detail": finding["display_detail"],
                        "display_severity": finding["display_severity"],
                        "display_title_is_excerpt": finding["display_title_is_excerpt"],
                        "classification_titles": finding["classification_titles"],
                    }
            return titles
        except (json.JSONDecodeError, TypeError, AttributeError, HostedCaptureError, ReviewRecordsError):
            return {}

    def _routes_for_pr(self, connection: sqlite3.Connection, pr: int) -> list[dict[str, Any]]:
        rows = connection.execute(
            "SELECT routes.route_id, routes.finding_id, routes.source_pr, routes.source_channel, "
            "findings.source_finding_key, routes.target_pr, routes.status, routes.created_at, routes.updated_at, "
            "(SELECT o.title FROM finding_observations o JOIN review_runs r USING (run_id) "
            "WHERE o.finding_id = routes.finding_id "
            "ORDER BY r.started_at DESC, o.run_id DESC LIMIT 1), "
            "(SELECT o.display_severity FROM finding_observations o JOIN review_runs r USING (run_id) "
            "WHERE o.finding_id = routes.finding_id "
            "ORDER BY r.started_at DESC, o.run_id DESC LIMIT 1) "
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
                    "decision_id": item[0],
                    "decision_pr": item[1],
                    "decision": item[2],
                    "actor": item[3],
                    "reason": item[4],
                    "decided_at": item[5],
                }
                for item in connection.execute(
                    "SELECT decision_id, decision_pr, decision, actor, reason, decided_at FROM decisions "
                    "WHERE decision_scope = 'target' AND route_id = ? ORDER BY decided_at, decision_id",
                    (route_id,),
                )
            ]
            resolutions = [
                {
                    "resolution_id": item[0],
                    "resolution_pr": item[1],
                    "outcome": item[2],
                    "actor": item[3],
                    "proof_or_reason": item[4],
                    "resolved_at": item[5],
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
                    "title": row[9],
                    **({"display_severity": row[10]} if row[3] == "subagent" or row[10] is not None else {}),
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
            "run_id": row[0],
            "source_pr": row[1],
            "channel": row[2],
            "source_head": row[3],
            "reviewer": row[4],
            "scope": row[5],
            "coverage_limits": coverage_limits,
            "outcome": row[7],
            "attributable": bool(row[8]),
            "started_at": row[9],
            "finished_at": row[10],
            "counts": {"found": row[11], "accepted": row[12], "routed": row[13]},
            "finalized": bool(row[14]),
            "finalized_at": row[15],
        }

    @staticmethod
    def _observation_record(row: Sequence[Any]) -> dict[str, Any]:
        record = {
            "run_id": row[0],
            "finding_id": row[1],
            "source_pr": row[2],
            "source_channel": row[3],
            "source_finding_key": row[4],
            "title": row[5],
            "detail": row[6],
            "disposition": row[7],
            "route_id": row[8],
        }
        if row[3] == "subagent" or row[9] is not None:
            record["display_severity"] = row[9]
        return record

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

    def _effective_deadline(self, deadline: float | None) -> float | None:
        if deadline is not None:
            return deadline
        try:
            from . import github
        except ImportError:  # pragma: no cover - direct script module execution
            import github  # type: ignore[no-redef]

        budget = github.active_hosted_preflight_budget()
        return budget.deadline if budget is not None else None

    def _hosted_budget(self):
        try:
            from . import github
        except ImportError:  # pragma: no cover - direct script module execution
            import github  # type: ignore[no-redef]

        return github.active_hosted_preflight_budget()

    def _raise_hosted_deadline_if_expired(
        self,
        deadline: float | None,
        *,
        error: sqlite3.DatabaseError | None = None,
    ) -> None:
        budget = self._hosted_budget()
        if budget is not None and (deadline is None or deadline == budget.deadline):
            remaining = budget.remaining_seconds()
            if error is not None and self._is_sqlite_lock_error(error) and remaining <= 0.002:
                time.sleep(remaining)
                budget.remaining_seconds()

    def _check_deadline(self, deadline: float | None) -> None:
        deadline = self._effective_deadline(deadline)
        if deadline is not None and time.monotonic() >= deadline:
            self._raise_hosted_deadline_if_expired(deadline)
            raise StateLockTimeout("timed out waiting for SQLite review-records deadline")

    def _remaining_timeout(self, deadline: float | None) -> float:
        deadline = self._effective_deadline(deadline)
        if deadline is None:
            return self.timeout
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            self._raise_hosted_deadline_if_expired(deadline)
            raise StateLockTimeout("timed out waiting for SQLite review-records deadline")
        return min(self.timeout, remaining)

    def _set_busy_timeout(self, connection: sqlite3.Connection, deadline: float | None) -> None:
        deadline = self._effective_deadline(deadline)
        timeout = self._remaining_timeout(deadline)
        milliseconds = math.ceil(timeout * 1000) if deadline is not None else int(timeout * 1000)
        connection.execute(f"PRAGMA busy_timeout = {milliseconds}")

    def _compatibility_query(
        self,
        connection: sqlite3.Connection,
        statement: str,
        *,
        deadline: float | None,
    ) -> sqlite3.Cursor:
        self._set_busy_timeout(connection, deadline)
        try:
            return connection.execute(statement)
        except sqlite3.DatabaseError as exc:
            if deadline is not None and self._is_sqlite_lock_error(exc):
                self._raise_hosted_deadline_if_expired(deadline, error=exc)
                raise StateLockTimeout("timed out waiting for SQLite review-records deadline") from exc
            raise

    @staticmethod
    def _is_sqlite_lock_error(error: sqlite3.DatabaseError) -> bool:
        message = str(error).lower()
        return "locked" in message or "busy" in message

    @contextlib.contextmanager
    def _write_connection(self, *, deadline: float | None = None):
        deadline = self._effective_deadline(deadline)
        self._require_regular_database()
        connection = self._connect(read_only=False, deadline=deadline)
        try:
            self._set_busy_timeout(connection, deadline)
            try:
                connection.execute("BEGIN IMMEDIATE")
            except sqlite3.OperationalError as exc:
                if deadline is not None and self._is_sqlite_lock_error(exc):
                    self._raise_hosted_deadline_if_expired(deadline, error=exc)
                    raise StateLockTimeout("timed out waiting for SQLite review-records transaction lock") from exc
                raise
            self._check_deadline(deadline)
            self._require_compatible(connection, deadline=deadline)
            yield connection
            self._check_deadline(deadline)
            self._set_busy_timeout(connection, deadline)
            connection.commit()
        except BaseException:
            if connection.in_transaction:
                connection.rollback()
            raise
        finally:
            connection.close()

    def _connect(self, *, read_only: bool, deadline: float | None = None) -> sqlite3.Connection:
        deadline = self._effective_deadline(deadline)
        if self.path.is_symlink():
            raise ReviewRecordsError("SQLite controller database path must not be a symlink")
        mode = "ro" if read_only else "rw"
        uri = f"{self.path.resolve().as_uri()}?mode={mode}"
        timeout = self._remaining_timeout(deadline)
        connection = sqlite3.connect(uri, uri=True, timeout=timeout, isolation_level=None)
        try:
            self._set_busy_timeout(connection, deadline)
            connection.execute("PRAGMA foreign_keys = ON")
            if read_only:
                connection.execute("PRAGMA query_only = ON")
            return connection
        except BaseException as setup_error:
            try:
                connection.close()
            except BaseException as close_error:
                raise setup_error from close_error
            raise

    def _require_regular_database(self) -> None:
        if self.path.is_symlink():
            raise ReviewRecordsError("SQLite controller database path must not be a symlink")
        if not self.path.is_file():
            raise ReviewRecordsError("controller SQLite database must already exist")

    def _table_names(
        self,
        connection: sqlite3.Connection,
        *,
        deadline: float | None = None,
    ) -> set[str]:
        rows = self._compatibility_query(
            connection,
            "SELECT name FROM sqlite_master WHERE type = 'table'",
            deadline=deadline,
        )
        return {row[0] for row in rows}

    def _require_controller_compatible(
        self,
        connection: sqlite3.Connection,
        *,
        deadline: float | None = None,
    ) -> None:
        deadline = self._effective_deadline(deadline)
        schema_version = int(
            self._compatibility_query(connection, "PRAGMA user_version", deadline=deadline).fetchone()[0]
        )
        if schema_version != SQLITE_SCHEMA_VERSION:
            raise RecordsSchemaIncompatible(f"unsupported controller SQLite schema version {schema_version}")
        if "controller_metadata" not in self._table_names(connection, deadline=deadline):
            raise RecordsSchemaIncompatible("controller SQLite metadata is missing")
        try:
            row = self._compatibility_query(
                connection,
                "SELECT data_model_version, min_writer_build FROM controller_metadata WHERE singleton = 1",
                deadline=deadline,
            ).fetchone()
        except sqlite3.DatabaseError as exc:
            self._raise_hosted_deadline_if_expired(deadline, error=exc)
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

    def _require_compatible(self, connection: sqlite3.Connection, *, deadline: float | None = None) -> None:
        deadline = self._effective_deadline(deadline)
        self._require_controller_compatible(connection, deadline=deadline)
        tables = self._table_names(connection, deadline=deadline)
        if _RECORDS_METADATA_TABLE not in tables:
            if tables & _RECORDS_TABLES:
                raise RecordsSchemaIncompatible("review-records schema is partial: metadata table is missing")
            raise RecordsNotBootstrapped("review-records schema is not bootstrapped; call bootstrap() explicitly")
        try:
            row = self._compatibility_query(
                connection,
                f"SELECT records_schema_version, controller_schema_version, controller_data_model_version, "
                f"min_writer_build FROM {_RECORDS_METADATA_TABLE} WHERE singleton = 1",
                deadline=deadline,
            ).fetchone()
        except sqlite3.DatabaseError as exc:
            self._raise_hosted_deadline_if_expired(deadline, error=exc)
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
            "route_id TEXT, display_severity TEXT CHECK (display_severity IS NULL OR display_severity IN "
            "('Critical', 'Major', 'Minor', 'Trivial')), PRIMARY KEY (run_id, finding_id), "
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
        SqliteReviewRecords._create_source_finding_resolution_schema(connection)
        SqliteReviewRecords._create_source_finding_resolution_correction_schema(connection)
        connection.execute("CREATE INDEX review_runs_source_pr_idx ON review_runs(source_pr, started_at)")
        connection.execute("CREATE INDEX routes_target_status_idx ON routes(target_pr, status, source_pr)")
        SqliteReviewRecords._create_attempt_schema(connection)
        SqliteReviewRecords._create_origin_schema(connection)
        SqliteReviewRecords._create_historical_gap_schema(connection)
        connection.execute(
            "INSERT INTO review_records_metadata VALUES (1, ?, ?, ?, ?)",
            (_RECORDS_SCHEMA_VERSION, SQLITE_SCHEMA_VERSION, ReviewState().schema_version, WRITER_BUILD),
        )

    @staticmethod
    def _create_source_finding_resolution_schema(connection: sqlite3.Connection) -> None:
        connection.execute(
            "CREATE TABLE source_finding_resolutions ("
            "resolution_id TEXT PRIMARY KEY, run_id TEXT NOT NULL, finding_id TEXT NOT NULL, "
            "source_pr INTEGER NOT NULL CHECK (source_pr > 0), "
            "source_channel TEXT NOT NULL CHECK (source_channel IN ('hosted', 'cli', 'manual', 'subagent')), "
            "outcome TEXT NOT NULL CHECK (outcome = 'accepted_fixed'), "
            "fix_sha TEXT NOT NULL CHECK (length(fix_sha) IN (40, 64)), actor TEXT NOT NULL, "
            "proof_note TEXT NOT NULL, resolved_at TEXT NOT NULL, UNIQUE (run_id, finding_id), "
            "FOREIGN KEY (run_id, source_pr, source_channel) REFERENCES review_runs(run_id, source_pr, channel), "
            "FOREIGN KEY (run_id, finding_id) REFERENCES finding_observations(run_id, finding_id))"
        )
        connection.execute(
            "CREATE INDEX source_finding_resolutions_pr_idx "
            "ON source_finding_resolutions(source_pr, source_channel, run_id)"
        )

    @staticmethod
    def _create_source_finding_resolution_correction_schema(connection: sqlite3.Connection) -> None:
        connection.execute(
            "CREATE TABLE source_finding_resolution_corrections ("
            "sequence INTEGER PRIMARY KEY AUTOINCREMENT, correction_id TEXT NOT NULL UNIQUE, "
            "resolution_id TEXT NOT NULL, expected_fix_sha TEXT NOT NULL CHECK (length(expected_fix_sha) IN (40, 64)), "
            "corrected_fix_sha TEXT NOT NULL CHECK (length(corrected_fix_sha) IN (40, 64)), "
            "actor TEXT NOT NULL, reason TEXT NOT NULL, proof_note TEXT NOT NULL, corrected_at TEXT NOT NULL, "
            "FOREIGN KEY (resolution_id) REFERENCES source_finding_resolutions(resolution_id))"
        )
        connection.execute(
            "CREATE INDEX source_finding_resolution_corrections_resolution_idx "
            "ON source_finding_resolution_corrections(resolution_id, sequence)"
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
        connection.execute("CREATE INDEX review_attempts_pr_idx ON review_attempts(source_pr, started_at)")
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
            "CREATE INDEX source_corrections_finding_idx ON source_decision_corrections(run_id, finding_id, sequence)"
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
        connection.execute("CREATE INDEX provider_origins_source_idx ON provider_origins(source_pr, checkpoint_id)")
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
