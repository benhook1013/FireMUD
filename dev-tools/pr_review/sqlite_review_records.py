"""Explicit SQLite persistence for structured PR-review records.

This repository extends the controller's existing SQLite database. It is not
used by the live controller yet: callers must invoke :meth:`bootstrap` once,
after the controller database itself has been initialized. Review runs keep
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
import sqlite3
from collections.abc import Mapping, Sequence
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Literal

from .sqlite_store import SQLITE_SCHEMA_VERSION, WRITER_BUILD
from .state import ReviewState

ReviewChannel = Literal["hosted", "cli", "manual", "subagent"]
FindingDisposition = Literal["accepted", "routed", "rejected", "unresolved"]
_RECORDS_SCHEMA_VERSION = 2
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
    payload: Mapping[str, Any] = dataclasses.field(default_factory=dict)

    def __post_init__(self) -> None:
        _text(self.source_finding_key, "source_finding_key", maximum=200)
        _text(self.title, "title", maximum=500)
        _text(self.detail, "detail", maximum=4000, allow_empty=True)
        if self.disposition not in {"accepted", "routed", "rejected", "unresolved"}:
            raise ReviewRecordsError("finding disposition is invalid")
        if self.target_pr is not None:
            _positive_pr(self.target_pr, "finding target PR")
        if self.disposition != "routed" and self.target_pr is not None:
            raise ReviewRecordsError("only routed findings may set a target PR")
        if not isinstance(self.payload, Mapping):
            raise ReviewRecordsError("finding payload must be a JSON object")
        try:
            _json(self.payload)
        except (TypeError, ValueError) as exc:
            raise ReviewRecordsError("finding payload must contain finite JSON values") from exc


def _text(value: Any, label: str, *, maximum: int, allow_empty: bool = False) -> str:
    if not isinstance(value, str) or len(value) > maximum:
        raise ReviewRecordsError(f"{label} must be text of at most {maximum} characters")
    if (not allow_empty and not value.strip()) or any(ord(char) < 0x20 for char in value):
        raise ReviewRecordsError(f"{label} must be non-empty text without control characters")
    return value


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
        started_at: str | None = None,
        finished_at: str | None = None,
    ) -> dict[str, Any]:
        """Persist one immutable source run and its findings atomically."""

        run_id = _text(run_id, "run_id", maximum=200)
        source_pr = _positive_pr(source_pr, "source PR")
        if channel not in {"hosted", "cli", "manual", "subagent"}:
            raise ReviewRecordsError("run channel must be hosted, cli, manual, or subagent")
        if outcome not in {"completed", "incomplete", "failed"}:
            raise ReviewRecordsError("run outcome is invalid")
        if not isinstance(attributable, bool):
            raise ReviewRecordsError("attributable must be boolean")
        if source_head is not None:
            source_head = _text(source_head, "source_head", maximum=200)
        started_at = _text(started_at or _now(), "started_at", maximum=100)
        if outcome == "completed":
            finished_at = _text(finished_at or _now(), "finished_at", maximum=100)
        elif finished_at is not None:
            finished_at = _text(finished_at, "finished_at", maximum=100)
        if isinstance(findings, (str, bytes)) or not isinstance(findings, Sequence):
            raise ReviewRecordsError("findings must be a sequence of FindingObservation values")
        observations = tuple(findings)
        if any(not isinstance(item, FindingObservation) for item in observations):
            raise ReviewRecordsError("findings must contain only FindingObservation values")
        finding_keys = [item.source_finding_key for item in observations]
        if len(finding_keys) != len(set(finding_keys)):
            raise ReviewRecordsError("a run cannot contain the same stable finding more than once")

        counts = {
            "found_count": len(observations),
            "accepted_count": sum(item.disposition == "accepted" for item in observations),
            "routed_count": sum(item.disposition == "routed" for item in observations),
        }
        try:
            with self._write_connection() as connection:
                connection.execute(
                    "INSERT INTO review_runs "
                    "(run_id, source_pr, channel, source_head, outcome, attributable, started_at, finished_at, "
                    "found_count, accepted_count, routed_count, finalized, finalized_at) "
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, NULL)",
                    (
                        run_id,
                        source_pr,
                        channel,
                        source_head,
                        outcome,
                        int(attributable),
                        started_at,
                        finished_at,
                        counts["found_count"],
                        counts["accepted_count"],
                        counts["routed_count"],
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
                        "(run_id, finding_id, source_pr, source_channel, title, detail, disposition, route_id, payload_json) "
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                        (
                            run_id,
                            finding_id,
                            source_pr,
                            channel,
                            item.title,
                            item.detail,
                            item.disposition,
                            route_id,
                            _json(item.payload),
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

        run_id = _text(run_id, "run_id", maximum=200)
        source_finding_key = _text(source_finding_key, "source_finding_key", maximum=200)
        decision_id = _text(decision_id, "decision_id", maximum=200)
        if decision not in {"accepted", "routed", "rejected"}:
            raise ReviewRecordsError("source decision must be accepted, routed, or rejected")
        if target_pr is not None:
            target_pr = _positive_pr(target_pr, "target PR")
        if decision != "routed" and target_pr is not None:
            raise ReviewRecordsError("only routed source decisions may set a target PR")
        actor = _text(actor, "actor", maximum=200)
        reason = _text(reason, "reason", maximum=500)
        decided_at = _text(decided_at or _now(), "decided_at", maximum=100)
        route_id: str | None = None
        try:
            with self._write_connection() as connection:
                finding = connection.execute(
                    "SELECT f.finding_id, o.source_pr, o.source_channel, r.finalized "
                    "FROM finding_observations o JOIN findings f USING (finding_id) "
                    "JOIN review_runs r USING (run_id) "
                    "WHERE o.run_id = ? AND f.source_finding_key = ?",
                    (run_id, source_finding_key),
                ).fetchone()
                if finding is None:
                    raise ReviewRecordsError("source finding was not observed in that run")
                finding_id, source_pr, channel, finalized = finding
                if finalized:
                    raise ReviewRecordsError("finalized source-run counts cannot be changed")
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

    def finalize_run(self, run_id: str, *, finalized_at: str | None = None) -> dict[str, Any]:
        """Freeze the source counts after every finding has a source disposition."""

        run_id = _text(run_id, "run_id", maximum=200)
        finalized_at = _text(finalized_at or _now(), "finalized_at", maximum=100)
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
        actor = _text(actor, "actor", maximum=200)
        reason = _text(reason, "reason", maximum=500)
        changed_at = _text(changed_at or _now(), "changed_at", maximum=100)
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
        if decision not in {"accepted", "rejected", "deferred"}:
            raise ReviewRecordsError("target decision is invalid")
        actor = _text(actor, "actor", maximum=200)
        reason = _text(reason, "reason", maximum=500)
        decided_at = _text(decided_at or _now(), "decided_at", maximum=100)
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
        if outcome not in {"accepted_fixed", "rejected"}:
            raise ReviewRecordsError("resolution outcome is invalid")
        actor = _text(actor, "actor", maximum=200)
        proof_or_reason = _text(proof_or_reason, "proof_or_reason", maximum=500)
        resolved_at = _text(resolved_at or _now(), "resolved_at", maximum=100)
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

    def history(self, pr: int) -> dict[str, Any]:
        """Return machine-readable source and incoming route history for one PR.

        The query includes routes whose source PR is no longer active or has
        merged. Merge state is intentionally not consulted or stored here.
        """

        pr = _positive_pr(pr)
        try:
            with contextlib.closing(self._connect(read_only=True)) as connection:
                self._require_compatible(connection)
                runs = [
                    self._run_record(row)
                    for row in connection.execute(
                        "SELECT run_id, source_pr, channel, source_head, outcome, attributable, started_at, "
                        "finished_at, found_count, accepted_count, routed_count, finalized, finalized_at "
                        "FROM review_runs WHERE source_pr = ? ORDER BY started_at, run_id",
                        (pr,),
                    )
                ]
                observations = [
                    self._observation_record(row)
                    for row in connection.execute(
                        "SELECT o.run_id, o.finding_id, o.source_pr, o.source_channel, f.source_finding_key, "
                        "o.title, o.detail, o.disposition, o.route_id, o.payload_json "
                        "FROM finding_observations o JOIN findings f USING (finding_id) "
                        "WHERE o.source_pr = ? ORDER BY o.run_id, o.finding_id",
                        (pr,),
                    )
                ]
                routes = self._routes_for_pr(connection, pr)
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

    def open_routes(self, *, target_pr: int | None = None) -> list[dict[str, Any]]:
        """Read open incoming and unassigned routes without filtering merged sources.

        With ``target_pr``, only routes currently incoming to that PR are
        returned. Without it, both assigned incoming routes and unassigned
        routes are returned, each marked with its assignment state.
        """

        if target_pr is not None:
            target_pr = _positive_pr(target_pr, "target PR")
        try:
            with contextlib.closing(self._connect(read_only=True)) as connection:
                self._require_compatible(connection)
                if target_pr is None:
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
                return [
                    {
                        "route_id": row[0],
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
    def _run_record(row: Sequence[Any]) -> dict[str, Any]:
        return {
            "run_id": row[0], "source_pr": row[1], "channel": row[2], "source_head": row[3],
            "outcome": row[4], "attributable": bool(row[5]), "started_at": row[6], "finished_at": row[7],
            "counts": {"found": row[8], "accepted": row[9], "routed": row[10]},
            "finalized": bool(row[11]), "finalized_at": row[12],
        }

    @staticmethod
    def _observation_record(row: Sequence[Any]) -> dict[str, Any]:
        try:
            payload = json.loads(row[9])
        except (TypeError, json.JSONDecodeError) as exc:
            raise ReviewRecordsError("stored finding payload is malformed") from exc
        if not isinstance(payload, dict):
            raise ReviewRecordsError("stored finding payload must be a JSON object")
        return {
            "run_id": row[0], "finding_id": row[1], "source_pr": row[2], "source_channel": row[3],
            "source_finding_key": row[4], "title": row[5], "detail": row[6], "disposition": row[7],
            "route_id": row[8], "payload": payload,
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
    ) -> None:
        existing = connection.execute(
            "SELECT target_pr FROM routes WHERE route_id = ?", (route_id,)
        ).fetchone()
        if existing is None:
            connection.execute(
                "INSERT INTO routes (route_id, finding_id, source_pr, source_channel, target_pr, status, "
                "created_at, updated_at) VALUES (?, ?, ?, ?, ?, 'open', ?, ?)",
                (route_id, finding_id, source_pr, channel, target_pr, observed_at, observed_at),
            )
            connection.execute(
                "INSERT INTO route_target_history (route_id, target_pr, changed_at, actor, reason) "
                "VALUES (?, ?, ?, 'source-review', 'source finding was routed')",
                (route_id, target_pr, observed_at),
            )
        elif target_pr is not None and existing[0] != target_pr:
            raise ReviewRecordsError("a repeated finding must be explicitly retargeted")

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
            "source_head TEXT, outcome TEXT NOT NULL CHECK (outcome IN ('completed', 'incomplete', 'failed')), "
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
            "route_id TEXT, payload_json TEXT NOT NULL, PRIMARY KEY (run_id, finding_id), "
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
