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
_RECORDS_SCHEMA_VERSION = 3
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
}


class ReviewRecordsError(ValueError):
    """Raised when review records are invalid or the database is incompatible."""


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
    re.compile(r"\b[A-Za-z0-9_-]{40,}\b"),
)
_FULL_COMMIT_SHA = re.compile(r"(?:[0-9a-fA-F]{40}|[0-9a-fA-F]{64})")


def _text(value: Any, label: str, *, maximum: int, allow_empty: bool = False) -> str:
    if not isinstance(value, str) or len(value) > maximum:
        raise ReviewRecordsError(f"{label} must be text of at most {maximum} characters")
    if (not allow_empty and not value.strip()) or any(ord(char) < 0x20 for char in value):
        raise ReviewRecordsError(f"{label} must be non-empty text without control characters")
    return value


def _bounded_text(value: Any, label: str, *, maximum: int, allow_empty: bool = False) -> str:
    value = _text(value, label, maximum=maximum, allow_empty=allow_empty)
    # A full commit identifier is useful bounded review context, including in
    # a sentence. Permit it only as a standalone long-token match; prefixes,
    # suffixes, and all other token-like strings remain rejected.
    if any(pattern.search(value) for pattern in _SECRET_PATTERNS[:-1]) or any(
        not _FULL_COMMIT_SHA.fullmatch(match.group()) for match in _SECRET_PATTERNS[-1].finditer(value)
    ):
        raise ReviewRecordsError(f"{label} resembles credential or raw secret material")
    return value


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
                    connection.commit()
                    return
                partial = tables & _RECORDS_TABLES
                if partial:
                    raise ReviewRecordsError("review-records schema is partial and cannot be bootstrapped")
                self._create_schema(connection)
                connection.commit()
        except ReviewRecordsError:
            raise
        except sqlite3.DatabaseError as exc:
            raise ReviewRecordsError("cannot bootstrap SQLite review records") from exc

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
            with self._write_connection() as connection:
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
                    "decision_pr, decision, actor, reason, decided_at) "
                    "VALUES (?, 'source', ?, ?, ?, ?, ?, ?, ?, ?)",
                    (
                        decision_id,
                        run_id,
                        finding_id,
                        route_id,
                        source_pr,
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
                        "d.decided_at, d.route_id, route_history.target_pr "
                        "FROM decisions d JOIN findings f USING (finding_id) "
                        "LEFT JOIN route_target_history route_history ON route_history.route_id = d.route_id "
                        "AND route_history.sequence = (SELECT MIN(sequence) FROM route_target_history first_history "
                        "WHERE first_history.route_id = d.route_id) "
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
                            # An externally identified legacy route can be
                            # retargeted after import. Its first recorded target
                            # remains part of the source decision; the current
                            # target is mutable route state and must not change
                            # the completed run's immutable identity on replay.
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
                        "decision_pr, decision, actor, reason, decided_at) "
                        "VALUES (?, 'source', ?, ?, ?, ?, ?, ?, ?, ?)",
                        (
                            item["decision_id"],
                            run_id,
                            finding_id,
                            route_id,
                            observed_pr,
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

    def finalize_run(self, run_id: str, *, finalized_at: str | None = None) -> dict[str, Any]:
        """Freeze the source counts after every finding has a source disposition."""

        run_id = _safe_identifier(run_id, "run_id", maximum=100)
        finalized_at = _timestamp(finalized_at, "finalized_at")
        try:
            with self._write_connection() as connection:
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
                    "decision_pr, decision, actor, reason, decided_at) "
                    "VALUES (?, 'target', NULL, ?, ?, ?, ?, ?, ?, ?)",
                    (decision_id, route[0], route_id, decision_pr, decision, actor, reason, decided_at),
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

    def history(self, pr: int, *, include_legacy_routes: bool = False) -> dict[str, Any]:
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
            with contextlib.closing(self._connect(read_only=True)) as connection:
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
                        "decision": row[6],
                        "actor": row[7],
                        "reason": row[8],
                        "decided_at": row[9],
                    }
                    for row in connection.execute(
                        "SELECT decision_id, decision_scope, run_id, finding_id, route_id, decision_pr, "
                        "decision, actor, reason, decided_at FROM decisions WHERE decision_pr = ? "
                        "ORDER BY decided_at, decision_id",
                        (pr,),
                    )
                ]
                return {
                    "pr": pr,
                    "runs": runs,
                    "findings": observations,
                    "routes": routes,
                    "decisions": decisions,
                }
        except ReviewRecordsError:
            raise
        except (OSError, sqlite3.DatabaseError, json.JSONDecodeError) as exc:
            raise ReviewRecordsError("cannot read SQLite review history") from exc

    def open_routes(
        self,
        *,
        target_pr: int | None = None,
        include_legacy_routes: bool = False,
    ) -> list[dict[str, Any]]:
        """Read open incoming and unassigned routes without filtering merged sources.

        With ``target_pr``, only routes currently incoming to that PR are
        returned. Without it, both assigned incoming routes and unassigned
        routes are returned, each marked with its assignment state. Legacy
        controller routes join the result only when ``include_legacy_routes``
        is true.
        """

        if target_pr is not None:
            target_pr = _positive_pr(target_pr, "target PR")
        if not isinstance(include_legacy_routes, bool):
            raise ReviewRecordsError("include_legacy_routes must be boolean")
        try:
            with contextlib.closing(self._connect(read_only=True)) as connection:
                connection.execute("BEGIN")
                self._require_compatible(connection)
                controller_state = self._controller_state(connection) if include_legacy_routes else None
                if include_legacy_routes:
                    # Read every shadow row before filtering. A legacy route
                    # with the same stable ID may have moved or reached a
                    # terminal status since the SQLite import; deduplication
                    # must see the authoritative legacy record first.
                    rows = connection.execute(
                        "SELECT routes.route_id, routes.finding_id, routes.source_pr, routes.source_channel, "
                        "findings.source_finding_key, routes.target_pr, routes.status, routes.created_at, "
                        "routes.updated_at FROM routes JOIN findings USING (finding_id)"
                    )
                elif target_pr is None:
                    rows = connection.execute(
                        "SELECT routes.route_id, routes.finding_id, routes.source_pr, routes.source_channel, "
                        "findings.source_finding_key, routes.target_pr, routes.status, routes.created_at, "
                        "routes.updated_at FROM routes JOIN findings USING (finding_id) "
                        "WHERE routes.status = 'open' ORDER BY COALESCE(routes.target_pr, 0), routes.source_pr, routes.route_id"
                    )
                else:
                    rows = connection.execute(
                        "SELECT routes.route_id, routes.finding_id, routes.source_pr, routes.source_channel, "
                        "findings.source_finding_key, routes.target_pr, routes.status, routes.created_at, "
                        "routes.updated_at FROM routes JOIN findings USING (finding_id) "
                        "WHERE routes.status = 'open' AND routes.target_pr = ? ORDER BY routes.source_pr, routes.route_id",
                        (target_pr,),
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
                if include_legacy_routes:
                    routes = [
                        route
                        for route in routes
                        if route["status"] == "open" and (target_pr is None or route["target_pr"] == target_pr)
                    ]
                return routes
        except ReviewRecordsError:
            raise
        except (OSError, sqlite3.DatabaseError) as exc:
            raise ReviewRecordsError("cannot read open SQLite review routes") from exc

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
            raise ReviewRecordsError(f"unsupported controller SQLite schema version {schema_version}")
        if "controller_metadata" not in self._table_names(connection):
            raise ReviewRecordsError("controller SQLite metadata is missing")
        try:
            row = connection.execute(
                "SELECT data_model_version, min_writer_build FROM controller_metadata WHERE singleton = 1"
            ).fetchone()
        except sqlite3.DatabaseError as exc:
            raise ReviewRecordsError("controller SQLite metadata has an incompatible shape") from exc
        if row is None:
            raise ReviewRecordsError("controller SQLite metadata row is missing")
        data_model_version, min_writer_build = row
        if data_model_version != ReviewState().schema_version:
            raise ReviewRecordsError(f"unsupported controller data-model version {data_model_version}")
        if isinstance(min_writer_build, bool) or not isinstance(min_writer_build, int) or min_writer_build <= 0:
            raise ReviewRecordsError("controller SQLite minimum writer build metadata is invalid")
        if self.writer_build < min_writer_build:
            raise ReviewRecordsError(f"controller SQLite database requires writer build {min_writer_build}")

    def _require_compatible(self, connection: sqlite3.Connection) -> None:
        self._require_controller_compatible(connection)
        tables = self._table_names(connection)
        if _RECORDS_METADATA_TABLE not in tables:
            raise ReviewRecordsError("review-records schema is not bootstrapped; call bootstrap() explicitly")
        try:
            row = connection.execute(
                f"SELECT records_schema_version, controller_schema_version, controller_data_model_version, "
                f"min_writer_build FROM {_RECORDS_METADATA_TABLE} WHERE singleton = 1"
            ).fetchone()
        except sqlite3.DatabaseError as exc:
            raise ReviewRecordsError("review-records metadata has an incompatible shape") from exc
        if row is None:
            raise ReviewRecordsError("review-records metadata row is missing")
        records_version, controller_version, data_model_version, min_writer_build = row
        if records_version != _RECORDS_SCHEMA_VERSION:
            raise ReviewRecordsError(f"unsupported review-records schema version {records_version}")
        if controller_version != SQLITE_SCHEMA_VERSION:
            raise ReviewRecordsError(f"review records require controller schema {controller_version}")
        if data_model_version != ReviewState().schema_version:
            raise ReviewRecordsError(f"review records require controller data model {data_model_version}")
        if isinstance(min_writer_build, bool) or not isinstance(min_writer_build, int) or min_writer_build <= 0:
            raise ReviewRecordsError("review-records minimum writer build metadata is invalid")
        if self.writer_build < min_writer_build:
            raise ReviewRecordsError(f"review records require writer build {min_writer_build}")
        missing = _RECORDS_TABLES - tables
        if missing:
            raise ReviewRecordsError("review-records schema is incomplete")

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
        connection.execute(
            "INSERT INTO review_records_metadata VALUES (1, ?, ?, ?, ?)",
            (_RECORDS_SCHEMA_VERSION, SQLITE_SCHEMA_VERSION, ReviewState().schema_version, WRITER_BUILD),
        )


__all__ = ["FindingObservation", "ReviewRecordsError", "SqliteReviewRecords"]
