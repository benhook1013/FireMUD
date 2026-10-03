"""SQLite-backed mutable state for the repository-curated FireMUD project map."""

from __future__ import annotations

import hashlib
import json
import math
import os
import re
import sqlite3
import unicodedata
from collections.abc import Iterator, Mapping, Sequence
from contextlib import closing, contextmanager
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from pr_review.sqlite_store import SQLITE_SCHEMA_VERSION, WRITER_BUILD, SqliteStateStore
from pr_review.state import ReviewState, StateError

from .jobs import (
    JobError,
    JobsNotBootstrapped,
    JobsSchemaIncompatible,
    RevisionConflict,
    _json,
    _loads,
    _positive_revision,
    _text,
)

MAP_SCHEMA_VERSION = 1
MAP_TABLES = (
    "map_metadata",
    "map_workstreams",
    "map_return_points",
    "map_revisions",
    "map_imports",
)
MAP_COLUMNS: dict[str, tuple[str, ...]] = {
    "map_metadata": (
        "singleton", "map_schema_version", "controller_schema_version", "controller_data_model_version",
    ),
    "map_workstreams": (
        "id", "revision", "state", "now_text", "milestone", "phase_states_json", "updated_at",
    ),
    "map_return_points": ("id", "revision", "state", "updated_at"),
    "map_revisions": ("record_type", "record_id", "revision", "created_at", "snapshot_json"),
    "map_imports": (
        "source_fingerprint", "created_at", "status_source", "status_sha256", "progress_source",
        "progress_sha256", "original_values_json", "manifest_json", "provenance_json",
    ),
}
MAP_INDEXES: dict[str, str] = {
    "map_revisions_record_idx": (
        "CREATE INDEX map_revisions_record_idx ON map_revisions(record_type, record_id, revision DESC)"
    ),
    "map_revisions_created_idx": "CREATE INDEX map_revisions_created_idx ON map_revisions(created_at DESC)",
}
MAP_TEXT_COLUMNS: dict[str, tuple[str, ...]] = {
    "map_metadata": (),
    "map_workstreams": ("id", "state", "now_text", "milestone", "phase_states_json", "updated_at"),
    "map_return_points": ("id", "state", "updated_at"),
    "map_revisions": ("record_id", "created_at", "snapshot_json"),
    "map_imports": (
        "source_fingerprint", "created_at", "status_source", "status_sha256", "progress_source",
        "progress_sha256", "original_values_json", "manifest_json", "provenance_json",
    ),
}

_ID = re.compile(r"[a-z0-9][a-z0-9-]{0,99}\Z")
_SHA256 = re.compile(r"[a-f0-9]{64}\Z")
_SITE_STATUSES = {"RUNNING": ("active", False), "PAUSED": ("active", True),
                  "COMPLETED": ("completed", False), "IDLE": ("completed", False)}
_MAX_SOURCE_BYTES = 2_000_000
_MAX_HISTORY_LIMIT = 1000
_MAX_HISTORY_OFFSET = 1_000_000


class MapError(JobError):
    """Raised when project-map data or its SQLite schema is invalid."""


class MapNotImported(MapError):
    """Raised when mutable project-map state has not been imported."""


class MapImportConflict(MapError):
    """Raised when a legacy source import would replace current SQL state."""


def _now() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z")


def _bounded_integer(value: Any, label: str, *, minimum: int, maximum: int) -> int:
    if isinstance(value, bool) or not isinstance(value, int) or not minimum <= value <= maximum:
        raise MapError(f"{label} must be an integer from {minimum} to {maximum}")
    return value


def _map_text(value: Any, label: str, *, maximum: int, allow_empty: bool = True) -> str:
    try:
        return _text(value, label, maximum=maximum, allow_empty=allow_empty)
    except JobError as exc:
        raise MapError(str(exc)) from exc


def _strict_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise MapError(f"duplicate JSON key: {key}")
        result[key] = value
    return result


def _read_site_file(value: str | os.PathLike[str], label: str) -> tuple[Path, bytes, dict[str, Any]]:
    path = Path(value).expanduser()
    try:
        if path.is_symlink() or not path.is_file():
            raise MapError(f"{label} must be a regular non-symlink file")
        data = path.read_bytes()
    except MapError:
        raise
    except OSError as exc:
        raise MapError(f"cannot read {label}") from exc
    if len(data) > _MAX_SOURCE_BYTES:
        raise MapError(f"{label} exceeds {_MAX_SOURCE_BYTES} bytes")
    try:
        text = data.decode("utf-8")
        document = json.loads(
            text,
            object_pairs_hook=_strict_object,
            parse_constant=lambda value: (_ for _ in ()).throw(MapError(f"invalid JSON number {value}")),
        )
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise MapError(f"{label} must contain valid UTF-8 JSON") from exc
    if not isinstance(document, dict):
        raise MapError(f"{label} must contain a JSON object")
    return path.resolve(), data, document


def return_point_id(name: str) -> str:
    """Create the stable return-point key from its existing editorial heading."""

    if not isinstance(name, str) or not name.strip():
        raise MapError("return-point name must be non-empty text")
    normalized = unicodedata.normalize("NFKD", name.casefold())
    ascii_name = normalized.encode("ascii", "ignore").decode("ascii")
    identifier = re.sub(r"[^a-z0-9]+", "-", ascii_name).strip("-")
    if not identifier or len(identifier) > 100:
        raise MapError("return-point name does not produce a stable heading id")
    return identifier


def _unique_mapping(value: Any, label: str, keys: set[str]) -> dict[str, str]:
    if not isinstance(value, Mapping):
        raise MapError(f"{label} must be an object")
    if set(value) != keys:
        raise MapError(f"{label} must contain exactly the supplied editorial names")
    return {
        key: _map_text(text, f"{label} state", maximum=500, allow_empty=False)
        for key, text in value.items()
    }


def editorial_mapping(progress: Mapping[str, Any]) -> dict[str, Any]:
    """Validate and return stable IDs/names without treating prose as live state."""

    tracks = progress.get("tracks")
    domains = progress.get("domains")
    return_points = progress.get("return_points")
    if not isinstance(tracks, list) or not isinstance(domains, list) or not isinstance(return_points, list):
        raise MapError("progress editorial data needs tracks, domains, and return_points lists")
    track_map: dict[str, dict[str, Any]] = {}
    for track in tracks:
        if not isinstance(track, dict):
            raise MapError("each project-map track must be an object")
        workstream_id = track.get("id")
        if not isinstance(workstream_id, str) or not _ID.fullmatch(workstream_id):
            raise MapError("each track needs its existing stable lowercase id")
        name = _map_text(track.get("name"), "track name", maximum=300, allow_empty=False)
        phases = track.get("phases")
        if not isinstance(phases, list):
            raise MapError(f"track {workstream_id} phases must be a list")
        phase_names: set[str] = set()
        for phase in phases:
            if not isinstance(phase, dict):
                raise MapError(f"track {workstream_id} phases must be objects")
            phase_name = _map_text(phase.get("name"), "phase name", maximum=300, allow_empty=False)
            if phase_name in phase_names:
                raise MapError(f"track {workstream_id} has duplicate phase names")
            phase_names.add(phase_name)
        if workstream_id in track_map:
            raise MapError(f"duplicate workstream id {workstream_id}")
        track_map[workstream_id] = {"id": workstream_id, "name": name, "phase_names": sorted(phase_names)}

    domain_names = set()
    for domain in domains:
        if not isinstance(domain, dict):
            raise MapError("each project-map domain must be an object")
        name = _map_text(domain.get("name"), "domain name", maximum=300, allow_empty=False)
        filename = domain.get("file")
        note = domain.get("note")
        if not isinstance(filename, str) or Path(filename).name != filename or not filename.endswith(".md"):
            raise MapError("domain file must be a Markdown basename")
        _map_text(note, "domain note", maximum=5000)
        if name in domain_names:
            raise MapError("project-map domain names must be unique")
        domain_names.add(name)

    point_map: dict[str, dict[str, str]] = {}
    for point in return_points:
        if not isinstance(point, dict):
            raise MapError("each return point must be an object")
        name = _map_text(point.get("name"), "return-point name", maximum=300, allow_empty=False)
        point_id = return_point_id(name)
        if point_id in point_map:
            raise MapError(f"return-point headings produce duplicate id {point_id}")
        trigger = _map_text(point.get("trigger"), "return-point trigger", maximum=5000)
        point_map[point_id] = {"id": point_id, "name": name, "trigger": trigger}
    if len(track_map) != len(tracks) or len(point_map) != len(return_points):
        raise MapError("project-map editorial identifiers are not unique")
    return {"workstreams": track_map, "domains": sorted(domain_names), "return_points": point_map}


def _site_lane_items(value: Any, label: str) -> list[str]:
    if isinstance(value, str):
        rows = [value] if value.strip() else []
    elif isinstance(value, list) and all(isinstance(item, str) for item in value):
        rows = [item for item in value if item.strip()]
    else:
        raise MapError(f"{label} must be text or a list of text")
    for item in rows:
        _map_text(item, label, maximum=1000, allow_empty=False)
    return rows


def _checklist_id(text: str, duplicate_number: int) -> str:
    normalized = unicodedata.normalize("NFKD", text.casefold()).encode("ascii", "ignore").decode("ascii")
    slug = re.sub(r"[^a-z0-9]+", "-", normalized).strip("-")[:70].strip("-") or "task"
    digest = hashlib.sha256(text.encode("utf-8")).hexdigest()[:10]
    suffix = f"-{duplicate_number}" if duplicate_number > 1 else ""
    return f"task-{slug}-{digest}{suffix}"


def _site_job_names(workers: list[str]) -> dict[str, str]:
    slugs = {}
    for worker in workers:
        normalized = unicodedata.normalize("NFKD", worker.casefold()).encode("ascii", "ignore").decode("ascii")
        slug = re.sub(r"[^a-z0-9]+", "-", normalized).strip("-") or "worker"
        slugs[worker] = slug
    counts: dict[str, int] = {}
    for slug in slugs.values():
        counts[slug] = counts.get(slug, 0) + 1
    names = {}
    for worker, slug in slugs.items():
        if counts[slug] > 1:
            suffix = hashlib.sha256(worker.encode("utf-8")).hexdigest()[:8]
            slug = f"{slug[:60].rstrip('-')}-{suffix}"
        else:
            slug = slug[:70].rstrip("-")
        names[worker] = f"site-{slug}"
    return names


def _lane_job_plan(status_data: Mapping[str, Any]) -> list[dict[str, Any]]:
    lanes = status_data.get("lanes")
    if not isinstance(lanes, list) or not lanes:
        raise MapError("status source needs a non-empty lanes list")
    plans = []
    workers = set()
    worker_rows = []
    for index, lane in enumerate(lanes):
        if not isinstance(lane, dict):
            raise MapError(f"status lane {index} must be an object")
        worker = _map_text(lane.get("name"), f"status lane {index} worker", maximum=100, allow_empty=False)
        if worker != worker.strip():
            raise MapError(f"status lane {index} worker alias must not start or end with whitespace")
        if worker in workers:
            raise MapError(f"status source repeats worker lane {worker}")
        workers.add(worker)
        worker_rows.append((worker, lane))
    names = _site_job_names([worker for worker, _ in worker_rows])
    for worker, lane in worker_rows:
        lane_status = lane.get("status", "RUNNING")
        if not isinstance(lane_status, str) or lane_status not in _SITE_STATUSES:
            raise MapError(f"status lane {worker} has an unsupported status")
        job_status, worker_paused = _SITE_STATUSES[lane_status]
        tasks = _site_lane_items(lane.get("task"), f"status lane {worker} task")
        if not tasks:
            raise MapError(f"status lane {worker} needs at least one task")
        task_summary = "\n".join(tasks)
        _map_text(task_summary, f"status lane {worker} summary", maximum=2000, allow_empty=False)
        up_next = _site_lane_items(lane.get("up_next", []), f"status lane {worker} up_next")
        blockers = _site_lane_items(lane.get("blocker", []), f"status lane {worker} blocker")
        duplicate_counts: dict[str, int] = {}
        checklist = []
        for task in tasks:
            duplicate_counts[task] = duplicate_counts.get(task, 0) + 1
            checklist.append({"id": _checklist_id(task, duplicate_counts[task]), "text": task, "done": False})
        brief_source = lane.get("brief")
        if brief_source is not None and not isinstance(brief_source, str):
            raise MapError(f"status lane {worker} brief must be a provenance path string")
        if brief_source is not None:
            _map_text(brief_source, f"status lane {worker} brief provenance", maximum=1000)
        verified_at = lane.get("verified_at")
        if verified_at is not None and not isinstance(verified_at, str):
            raise MapError(f"status lane {worker} verified_at must be text")
        if verified_at is not None:
            _map_text(verified_at, f"status lane {worker} verified_at", maximum=100)
        plan = {
            "worker": worker,
            "name": names[worker],
            "title": f"{worker} assignment",
            "status": job_status,
            "primary": job_status != "completed",
            "summary": task_summary,
            "progress": "\n".join(up_next),
            "blocker": "\n".join(blockers),
            "checklist": checklist,
            "worker_paused": worker_paused,
            "brief_source": brief_source,
            "source_verified_at": verified_at,
        }
        _map_text(plan["progress"], f"status lane {worker} progress", maximum=2000)
        _map_text(plan["blocker"], f"status lane {worker} blocker", maximum=2000)
        plans.append(plan)
    return plans


def _site_manifest(status_data: Mapping[str, Any], progress_data: Mapping[str, Any]) -> dict[str, Any]:
    editorial = editorial_mapping(progress_data)
    workstreams = []
    for track in progress_data["tracks"]:
        workstream_id = track["id"]
        phases = track["phases"]
        phase_states = {
            phase["name"]: _map_text(phase.get("state"), f"{workstream_id} phase state", maximum=500, allow_empty=False)
            for phase in phases
        }
        workstreams.append({
            "id": workstream_id,
            "state": _map_text(track.get("state"), f"{workstream_id} state", maximum=500, allow_empty=False),
            "now": _map_text(track.get("now"), f"{workstream_id} now", maximum=5000),
            "milestone": _map_text(track.get("milestone"), f"{workstream_id} milestone", maximum=5000),
            "phase_states": phase_states,
        })
    return_points = []
    for point in progress_data["return_points"]:
        return_points.append({
            "id": return_point_id(point["name"]),
            "state": _map_text(point.get("state"), "return-point state", maximum=500, allow_empty=False),
        })
    return {
        "version": 1,
        "workstreams": workstreams,
        "return_points": return_points,
        "lane_jobs": _lane_job_plan(status_data),
        "editorial": {
            "workstreams": editorial["workstreams"],
            "domains": editorial["domains"],
            "return_points": editorial["return_points"],
        },
    }


def _snapshot_workstream(row: Mapping[str, Any]) -> dict[str, Any]:
    phase_states = _loads(row["phase_states_json"], "workstream phase states")
    if not isinstance(phase_states, dict) or any(
        not isinstance(name, str) or not isinstance(state, str) for name, state in phase_states.items()
    ):
        raise JobsSchemaIncompatible("stored workstream phase states are malformed")
    return {
        "id": row["id"],
        "revision": row["revision"],
        "state": row["state"],
        "now": row["now_text"],
        "milestone": row["milestone"],
        "phase_states": phase_states,
        "updated_at": row["updated_at"],
    }


def _snapshot_return_point(row: Mapping[str, Any]) -> dict[str, Any]:
    return {
        "id": row["id"],
        "revision": row["revision"],
        "state": row["state"],
        "updated_at": row["updated_at"],
    }


def _normalize_editorial(value: Mapping[str, Any]) -> dict[str, Any]:
    if not isinstance(value, Mapping):
        raise MapError("editorial mapping must be an object")
    if "tracks" in value and "domains" in value and "return_points" in value:
        return editorial_mapping(value)
    workstreams = value.get("workstreams")
    returns = value.get("return_points")
    if not isinstance(workstreams, Mapping) or not isinstance(returns, Mapping):
        raise MapError("editorial mapping must supply workstreams and return_points")
    normalized_workstreams = {}
    for workstream_id, item in workstreams.items():
        if not isinstance(workstream_id, str) or not _ID.fullmatch(workstream_id) or not isinstance(item, Mapping):
            raise MapError("editorial workstream mapping is malformed")
        phases = item.get("phase_names")
        if isinstance(phases, (str, bytes)) or not isinstance(phases, Sequence):
            raise MapError(f"editorial phases for {workstream_id} must be a list")
        names = [_map_text(phase, "phase name", maximum=300, allow_empty=False) for phase in phases]
        if len(set(names)) != len(names):
            raise MapError(f"editorial phases for {workstream_id} are not unique")
        normalized_workstreams[workstream_id] = {
            "id": workstream_id,
            "name": _map_text(item.get("name", workstream_id), "track name", maximum=300, allow_empty=False),
            "phase_names": sorted(names),
        }
    normalized_returns = {}
    for point_id, item in returns.items():
        if not isinstance(point_id, str) or not _ID.fullmatch(point_id) or not isinstance(item, Mapping):
            raise MapError("editorial return-point mapping is malformed")
        name = _map_text(item.get("name"), "return-point name", maximum=300, allow_empty=False)
        if return_point_id(name) != point_id:
            raise MapError(f"return-point id {point_id} does not match its editorial heading")
        normalized_returns[point_id] = {
            "id": point_id,
            "name": name,
            "trigger": _map_text(item.get("trigger", ""), "return-point trigger", maximum=5000),
        }
    return {"workstreams": normalized_workstreams, "domains": [], "return_points": normalized_returns}


class WorkstreamStore:
    """Persist mutable project-map fields without duplicating job briefs."""

    def __init__(
        self,
        path: str | os.PathLike[str],
        *,
        writer_build: int = WRITER_BUILD,
        timeout: float = 10.0,
    ) -> None:
        if isinstance(timeout, bool) or not isinstance(timeout, (int, float)) or not math.isfinite(timeout) or timeout <= 0:
            raise MapError("timeout must be a positive finite number")
        self.path = Path(path).expanduser().absolute()
        self.writer_build = writer_build
        self.controller = SqliteStateStore(self.path, writer_build=writer_build, timeout=timeout)
        self.timeout = float(timeout)

    def bootstrap(self) -> None:
        """Explicitly bootstrap controller and independent project-map tables."""

        if self.path.is_symlink():
            raise JobsSchemaIncompatible("SQLite controller database path must not be a symlink")
        if self.path.exists() and not self.path.is_file():
            raise JobsSchemaIncompatible("SQLite controller database path must be a regular file")
        if not self.path.exists():
            try:
                self.controller.update(lambda state: state)
            except (StateError, OSError, sqlite3.DatabaseError, ValueError) as exc:
                raise JobsSchemaIncompatible("cannot initialize a compatible controller database") from exc
        else:
            status = self.controller.status()
            if status.get("compatible") is not True:
                raise JobsSchemaIncompatible(f"controller SQLite database is incompatible: {status.get('reason')}")
        try:
            with closing(self._connect(read_only=False)) as connection:
                connection.execute("BEGIN IMMEDIATE")
                self._require_controller_compatible(connection)
                tables = self._table_names(connection)
                present = tables & set(MAP_TABLES)
                if present:
                    if present != set(MAP_TABLES):
                        raise JobsSchemaIncompatible("project-map schema is partial")
                    self.validate(connection)
                    connection.commit()
                    return
                self._create_schema(connection)
                self.validate(connection)
                connection.commit()
        except JobError:
            raise
        except sqlite3.DatabaseError as exc:
            raise JobsSchemaIncompatible("cannot bootstrap SQLite project-map store") from exc

    @classmethod
    def validate(cls, connection: sqlite3.Connection) -> None:
        """Validate controller identity and this module's independent inventory."""

        cls._require_controller_compatible(connection)
        tables = cls._table_names(connection)
        missing = set(MAP_TABLES) - tables
        if missing:
            raise JobsNotBootstrapped("project-map schema is incomplete; call bootstrap() explicitly")
        for table, expected in MAP_COLUMNS.items():
            try:
                actual = tuple(row[1] for row in connection.execute(f'PRAGMA table_info("{table}")'))
            except sqlite3.DatabaseError as exc:
                raise JobsSchemaIncompatible(f"cannot inspect {table} schema") from exc
            if actual != expected:
                raise JobsSchemaIncompatible(f"project-map table {table} has incompatible columns")
        index_rows = {
            name: sql for name, sql in connection.execute(
                "SELECT name, sql FROM sqlite_master WHERE type = 'index' AND name IS NOT NULL"
            )
        }
        for name, expected_sql in MAP_INDEXES.items():
            actual_sql = index_rows.get(name)
            if actual_sql is None or _normalize_sql(actual_sql) != _normalize_sql(expected_sql):
                raise JobsSchemaIncompatible(f"required project-map index {name} is missing or incompatible")
        metadata = connection.execute(
            "SELECT map_schema_version, controller_schema_version, controller_data_model_version "
            "FROM map_metadata WHERE singleton = 1"
        ).fetchone()
        expected_version = (MAP_SCHEMA_VERSION, SQLITE_SCHEMA_VERSION, ReviewState().schema_version)
        if metadata is None or tuple(metadata) != expected_version:
            raise JobsSchemaIncompatible("project-map metadata records incompatible schema versions")
        invalid_workstream = connection.execute(
            "SELECT id FROM map_workstreams WHERE revision < 1 OR json_valid(phase_states_json) = 0 LIMIT 1"
        ).fetchone()
        invalid_return = connection.execute(
            "SELECT id FROM map_return_points WHERE revision < 1 LIMIT 1"
        ).fetchone()
        invalid_revision = connection.execute(
            "SELECT record_id FROM map_revisions WHERE record_type NOT IN ('workstream', 'return_point') "
            "OR revision < 1 OR json_valid(snapshot_json) = 0 LIMIT 1"
        ).fetchone()
        invalid_import = connection.execute(
            "SELECT source_fingerprint FROM map_imports WHERE length(source_fingerprint) <> 64 "
            "OR json_valid(original_values_json) = 0 OR json_valid(manifest_json) = 0 "
            "OR json_valid(provenance_json) = 0 LIMIT 1"
        ).fetchone()
        if invalid_workstream or invalid_return or invalid_revision or invalid_import:
            raise JobsSchemaIncompatible("project-map state contains malformed records")
        for row in connection.execute("SELECT * FROM map_workstreams"):
            row_data = {column: row[index] for index, column in enumerate(MAP_COLUMNS["map_workstreams"])}
            snapshot = _snapshot_workstream(row_data)
            _map_text(snapshot["state"], "stored workstream state", maximum=500, allow_empty=False)
            _map_text(snapshot["now"], "stored workstream now", maximum=5000)
            _map_text(snapshot["milestone"], "stored workstream milestone", maximum=5000)
            for phase, state in snapshot["phase_states"].items():
                _map_text(phase, "stored phase name", maximum=300, allow_empty=False)
                _map_text(state, "stored phase state", maximum=500, allow_empty=False)
        for row in connection.execute("SELECT * FROM map_return_points"):
            row_data = {column: row[index] for index, column in enumerate(MAP_COLUMNS["map_return_points"])}
            _map_text(row_data["state"], "stored return-point state", maximum=500, allow_empty=False)

    def list(self, editorial: Mapping[str, Any]) -> dict[str, Any]:
        mapping = _normalize_editorial(editorial)
        with self._read() as connection:
            workstreams = {
                row["id"]: _snapshot_workstream(row)
                for row in connection.execute("SELECT * FROM map_workstreams ORDER BY id")
            }
            return_points = {
                row["id"]: _snapshot_return_point(row)
                for row in connection.execute("SELECT * FROM map_return_points ORDER BY id")
            }
            self._validate_pairing(mapping, workstreams, return_points)
            return {
                "workstreams": [
                    {**workstreams[workstream_id], "name": item["name"]}
                    for workstream_id, item in mapping["workstreams"].items()
                ],
                "return_points": [
                    {**return_points[point_id], "name": item["name"], "trigger": item["trigger"]}
                    for point_id, item in mapping["return_points"].items()
                ],
            }

    def get(self, workstream_id: str, editorial: Mapping[str, Any]) -> dict[str, Any]:
        mapping = _normalize_editorial(editorial)
        if workstream_id not in mapping["workstreams"]:
            raise MapError(f"unknown editorial workstream {workstream_id}")
        with self._read() as connection:
            row = connection.execute("SELECT * FROM map_workstreams WHERE id = ?", (workstream_id,)).fetchone()
            if row is None:
                raise MapNotImported(f"workstream {workstream_id} has no imported state")
            result = _snapshot_workstream(row)
            self._validate_workstream_phases(result, mapping["workstreams"][workstream_id])
            return {**result, "name": mapping["workstreams"][workstream_id]["name"]}

    def has_workstream(self, workstream_id: str) -> bool:
        """Check an optional job association against imported SQL IDs only."""

        if not isinstance(workstream_id, str) or not _ID.fullmatch(workstream_id):
            return False
        with self._read() as connection:
            return connection.execute(
                "SELECT 1 FROM map_workstreams WHERE id = ?", (workstream_id,)
            ).fetchone() is not None

    def update(
        self,
        workstream_id: str,
        expected_revision: int,
        editorial: Mapping[str, Any],
        *,
        state: str | None = None,
        now: str | None = None,
        milestone: str | None = None,
        phase_states: Mapping[str, str] | None = None,
    ) -> dict[str, Any]:
        expected = _positive_revision(expected_revision)
        mapping = _normalize_editorial(editorial)
        if workstream_id not in mapping["workstreams"]:
            raise MapError(f"unknown editorial workstream {workstream_id}")
        with self._write() as connection:
            row = connection.execute("SELECT * FROM map_workstreams WHERE id = ?", (workstream_id,)).fetchone()
            if row is None:
                raise MapNotImported(f"workstream {workstream_id} has no imported state")
            current = _snapshot_workstream(row)
            self._validate_workstream_phases(current, mapping["workstreams"][workstream_id])
            if current["revision"] != expected:
                raise RevisionConflict(f"workstream {workstream_id} is at revision {current['revision']}, not {expected}")
            values = {
                "state": current["state"] if state is None else _map_text(state, "state", maximum=500, allow_empty=False),
                "now": current["now"] if now is None else _map_text(now, "now", maximum=5000),
                "milestone": current["milestone"] if milestone is None else _map_text(milestone, "milestone", maximum=5000),
                "phase_states": dict(current["phase_states"]),
            }
            if phase_states is not None:
                if not isinstance(phase_states, Mapping):
                    raise MapError("phase_states must be an object")
                unknown = set(phase_states) - set(mapping["workstreams"][workstream_id]["phase_names"])
                if unknown:
                    raise MapError(f"unknown editorial phases: {', '.join(sorted(unknown))}")
                for phase, phase_state in phase_states.items():
                    values["phase_states"][phase] = _map_text(
                        phase_state, f"phase {phase} state", maximum=500, allow_empty=False,
                    )
            if values == {key: current[key] for key in ("state", "now", "milestone", "phase_states")}:
                return {**current, "name": mapping["workstreams"][workstream_id]["name"]}
            updated_at = _now()
            updated = {
                **values,
                "id": workstream_id,
                "revision": current["revision"] + 1,
                "updated_at": updated_at,
            }
            connection.execute(
                "UPDATE map_workstreams SET revision = ?, state = ?, now_text = ?, milestone = ?, "
                "phase_states_json = ?, updated_at = ? WHERE id = ? AND revision = ?",
                (updated["revision"], updated["state"], updated["now"], updated["milestone"],
                 _json(updated["phase_states"]), updated_at, workstream_id, expected),
            )
            self._insert_revision(connection, "workstream", workstream_id, updated)
            return {**updated, "name": mapping["workstreams"][workstream_id]["name"]}

    def update_return_point(
        self,
        point_id: str,
        expected_revision: int,
        editorial: Mapping[str, Any],
        *,
        state: str,
    ) -> dict[str, Any]:
        expected = _positive_revision(expected_revision)
        mapping = _normalize_editorial(editorial)
        if point_id not in mapping["return_points"]:
            raise MapError(f"unknown editorial return point {point_id}")
        selected_state = _map_text(state, "return-point state", maximum=500, allow_empty=False)
        with self._write() as connection:
            row = connection.execute("SELECT * FROM map_return_points WHERE id = ?", (point_id,)).fetchone()
            if row is None:
                raise MapNotImported(f"return point {point_id} has no imported state")
            current = _snapshot_return_point(row)
            if current["revision"] != expected:
                raise RevisionConflict(f"return point {point_id} is at revision {current['revision']}, not {expected}")
            if current["state"] == selected_state:
                return {**current, "name": mapping["return_points"][point_id]["name"]}
            updated_at = _now()
            updated = {
                "id": point_id,
                "revision": expected + 1,
                "state": selected_state,
                "updated_at": updated_at,
            }
            connection.execute(
                "UPDATE map_return_points SET revision = ?, state = ?, updated_at = ? WHERE id = ? AND revision = ?",
                (updated["revision"], selected_state, updated_at, point_id, expected),
            )
            self._insert_revision(connection, "return_point", point_id, updated)
            return {**updated, "name": mapping["return_points"][point_id]["name"]}

    def history(
        self,
        record_type: str,
        record_id: str,
        *,
        revision: int | None = None,
        limit: int = 50,
        offset: int = 0,
    ) -> list[dict[str, Any]] | dict[str, Any]:
        if record_type not in {"workstream", "return_point"}:
            raise MapError("record_type must be workstream or return_point")
        selected_limit = _bounded_integer(limit, "limit", minimum=1, maximum=_MAX_HISTORY_LIMIT)
        selected_offset = _bounded_integer(offset, "offset", minimum=0, maximum=_MAX_HISTORY_OFFSET)
        if revision is not None:
            _positive_revision(revision, "revision")
        with self._read() as connection:
            if revision is not None:
                row = connection.execute(
                    "SELECT revision, created_at, snapshot_json FROM map_revisions "
                    "WHERE record_type = ? AND record_id = ? AND revision = ?",
                    (record_type, record_id, revision),
                ).fetchone()
                if row is None:
                    raise MapError("map history revision not found")
                snapshot = _loads(row["snapshot_json"], "map history snapshot")
                return {**snapshot, "created_at": row["created_at"]}
            rows = connection.execute(
                "SELECT revision, created_at, snapshot_json FROM map_revisions "
                "WHERE record_type = ? AND record_id = ? ORDER BY revision DESC LIMIT ? OFFSET ?",
                (record_type, record_id, selected_limit, selected_offset),
            ).fetchall()
            return [
                {**_loads(row["snapshot_json"], "map history snapshot"), "created_at": row["created_at"]}
                for row in rows
            ]

    def import_site(
        self,
        status_source: str | os.PathLike[str],
        progress_source: str | os.PathLike[str],
        apply: bool = False,
        *,
        expected_fingerprint: str | None = None,
    ) -> dict[str, Any]:
        """Preview or idempotently import mutable legacy website values.

        Lane rows are returned as a lossless-to-public-fields job plan for the
        parent CLI to reconcile through JobStore. This transaction imports
        only project-map records; it does not mutate status/progress files.
        """

        if not isinstance(apply, bool):
            raise MapError("apply must be a boolean")
        if expected_fingerprint is not None and (
            not isinstance(expected_fingerprint, str) or not _SHA256.fullmatch(expected_fingerprint)
        ):
            raise MapError("expected_fingerprint must be a lowercase SHA-256 fingerprint")
        status_path, status_bytes, status_data = _read_site_file(status_source, "status source")
        progress_path, progress_bytes, progress_data = _read_site_file(progress_source, "progress source")
        manifest = _site_manifest(status_data, progress_data)
        status_hash = hashlib.sha256(status_bytes).hexdigest()
        progress_hash = hashlib.sha256(progress_bytes).hexdigest()
        source_fingerprint = hashlib.sha256(
            _json({"status_sha256": status_hash, "progress_sha256": progress_hash}).encode("utf-8")
        ).hexdigest()
        if expected_fingerprint is not None and source_fingerprint != expected_fingerprint:
            raise MapImportConflict("website sources changed after the import preview")
        original_values = {
            "workstreams": [
                {key: item[key] for key in ("id", "state", "now", "milestone", "phase_states")}
                for item in manifest["workstreams"]
            ],
            "return_points": [dict(item) for item in manifest["return_points"]],
            "lane_jobs": [dict(item) for item in manifest["lane_jobs"]],
        }
        provenance = {
            "source": "legacy-private-status-site",
            "status_source": str(status_path),
            "status_sha256": status_hash,
            "progress_source": str(progress_path),
            "progress_sha256": progress_hash,
            "map_schema_version": MAP_SCHEMA_VERSION,
        }
        result = {
            "source_fingerprint": source_fingerprint,
            "provenance": provenance,
            "workstreams": manifest["workstreams"],
            "return_points": manifest["return_points"],
            "lane_jobs": manifest["lane_jobs"],
            "editorial": manifest["editorial"],
            "review_authority": "controller-sqlite",
            "retained_config": {"review_tool": status_data.get("review_tool")},
            "ignored_runtime_mirrors": ["status.stack", "status.review_front"],
            "applied": False,
            "already_applied": False,
            "conflicts": [],
        }
        with self._read() as connection:
            prior = connection.execute("SELECT source_fingerprint FROM map_imports LIMIT 1").fetchone()
            if prior is not None:
                if prior[0] == source_fingerprint:
                    result["already_applied"] = True
                    return result
                result["conflicts"].append("a different website source was already imported")
            if not result["conflicts"]:
                existing_workstreams = connection.execute("SELECT 1 FROM map_workstreams LIMIT 1").fetchone()
                existing_returns = connection.execute("SELECT 1 FROM map_return_points LIMIT 1").fetchone()
                if existing_workstreams or existing_returns:
                    result["conflicts"].append("map state exists without a matching site-import record")
        if not apply:
            return result
        if result["conflicts"]:
            raise MapImportConflict("; ".join(result["conflicts"]))
        with self._write() as connection:
            prior = connection.execute("SELECT source_fingerprint FROM map_imports LIMIT 1").fetchone()
            if prior is not None:
                if prior[0] == source_fingerprint:
                    result["already_applied"] = True
                    return result
                raise MapImportConflict("a different website source was already imported")
            if connection.execute("SELECT 1 FROM map_workstreams UNION ALL SELECT 1 FROM map_return_points LIMIT 1").fetchone():
                raise MapImportConflict("map state exists without a matching site-import record")
            created_at = _now()
            for item in manifest["workstreams"]:
                snapshot = {
                    "id": item["id"], "revision": 1, "state": item["state"], "now": item["now"],
                    "milestone": item["milestone"], "phase_states": item["phase_states"], "updated_at": created_at,
                }
                connection.execute(
                    "INSERT INTO map_workstreams(id, revision, state, now_text, milestone, phase_states_json, updated_at) "
                    "VALUES (?, 1, ?, ?, ?, ?, ?)",
                    (item["id"], item["state"], item["now"], item["milestone"], _json(item["phase_states"]), created_at),
                )
                self._insert_revision(connection, "workstream", item["id"], snapshot, created_at=created_at)
            for item in manifest["return_points"]:
                snapshot = {"id": item["id"], "revision": 1, "state": item["state"], "updated_at": created_at}
                connection.execute(
                    "INSERT INTO map_return_points(id, revision, state, updated_at) VALUES (?, 1, ?, ?)",
                    (item["id"], item["state"], created_at),
                )
                self._insert_revision(connection, "return_point", item["id"], snapshot, created_at=created_at)
            connection.execute(
                "INSERT INTO map_imports(source_fingerprint, created_at, status_source, status_sha256, "
                "progress_source, progress_sha256, original_values_json, manifest_json, provenance_json) "
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                (source_fingerprint, created_at, str(status_path), status_hash, str(progress_path), progress_hash,
                 _json(original_values), _json(manifest), _json(provenance)),
            )
        result["applied"] = True
        return result

    @staticmethod
    def _validate_pairing(
        editorial: Mapping[str, Any],
        workstreams: Mapping[str, Any],
        return_points: Mapping[str, Any],
    ) -> None:
        if set(workstreams) != set(editorial["workstreams"]):
            raise MapNotImported("SQL workstream IDs do not match the supplied editorial mapping")
        if set(return_points) != set(editorial["return_points"]):
            raise MapNotImported("SQL return-point IDs do not match the supplied editorial headings")
        for workstream_id, item in editorial["workstreams"].items():
            WorkstreamStore._validate_workstream_phases(workstreams[workstream_id], item)

    @staticmethod
    def _validate_workstream_phases(record: Mapping[str, Any], editorial: Mapping[str, Any]) -> None:
        if set(record["phase_states"]) != set(editorial["phase_names"]):
            raise MapNotImported(f"SQL phase IDs do not match editorial phases for {record['id']}")

    @staticmethod
    def _insert_revision(
        connection: sqlite3.Connection,
        record_type: str,
        record_id: str,
        snapshot: Mapping[str, Any],
        *,
        created_at: str | None = None,
    ) -> None:
        created = created_at or snapshot.get("updated_at") or _now()
        connection.execute(
            "INSERT INTO map_revisions(record_type, record_id, revision, created_at, snapshot_json) "
            "VALUES (?, ?, ?, ?, ?)",
            (record_type, record_id, snapshot["revision"], created, _json(dict(snapshot))),
        )

    @staticmethod
    def _create_schema(connection: sqlite3.Connection) -> None:
        connection.execute(
            "CREATE TABLE map_metadata (singleton INTEGER PRIMARY KEY CHECK (singleton = 1), "
            "map_schema_version INTEGER NOT NULL, "
            "controller_schema_version INTEGER NOT NULL, controller_data_model_version INTEGER NOT NULL)"
        )
        connection.execute(
            "CREATE TABLE map_workstreams (id TEXT PRIMARY KEY, revision INTEGER NOT NULL CHECK (revision > 0), "
            "state TEXT NOT NULL, now_text TEXT NOT NULL, milestone TEXT NOT NULL, phase_states_json TEXT NOT NULL, "
            "updated_at TEXT NOT NULL)"
        )
        connection.execute(
            "CREATE TABLE map_return_points (id TEXT PRIMARY KEY, revision INTEGER NOT NULL CHECK (revision > 0), "
            "state TEXT NOT NULL, updated_at TEXT NOT NULL)"
        )
        connection.execute(
            "CREATE TABLE map_revisions (record_type TEXT NOT NULL CHECK (record_type IN ('workstream', 'return_point')), "
            "record_id TEXT NOT NULL, revision INTEGER NOT NULL CHECK (revision > 0), created_at TEXT NOT NULL, "
            "snapshot_json TEXT NOT NULL, PRIMARY KEY (record_type, record_id, revision))"
        )
        connection.execute(
            "CREATE TABLE map_imports (source_fingerprint TEXT PRIMARY KEY, created_at TEXT NOT NULL, "
            "status_source TEXT NOT NULL, status_sha256 TEXT NOT NULL, progress_source TEXT NOT NULL, "
            "progress_sha256 TEXT NOT NULL, original_values_json TEXT NOT NULL, manifest_json TEXT NOT NULL, "
            "provenance_json TEXT NOT NULL)"
        )
        for statement in MAP_INDEXES.values():
            connection.execute(statement)
        connection.execute(
            "INSERT INTO map_metadata(singleton, map_schema_version, controller_schema_version, "
            "controller_data_model_version) VALUES (1, ?, ?, ?)",
            (MAP_SCHEMA_VERSION, SQLITE_SCHEMA_VERSION, ReviewState().schema_version),
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
            raise JobsSchemaIncompatible("SQLite project-map read failed") from exc
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
            raise MapError("project-map update conflicts with a schema constraint") from exc
        except sqlite3.DatabaseError as exc:
            if connection.in_transaction:
                connection.rollback()
            raise JobsSchemaIncompatible("SQLite project-map write failed") from exc
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
            raise JobsNotBootstrapped("project-map store is not bootstrapped; call bootstrap() explicitly")
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

    @staticmethod
    def _table_names(connection: sqlite3.Connection) -> set[str]:
        return {row[0] for row in connection.execute("SELECT name FROM sqlite_master WHERE type = 'table'")}

    @staticmethod
    def _require_controller_compatible(
        connection: sqlite3.Connection,
        writer_build: int = WRITER_BUILD,
    ) -> None:
        try:
            schema_version = int(connection.execute("PRAGMA user_version").fetchone()[0])
            if schema_version != SQLITE_SCHEMA_VERSION:
                raise JobsSchemaIncompatible(f"unsupported controller SQLite schema version: {schema_version}")
            row = connection.execute(
                "SELECT data_model_version, min_writer_build FROM controller_metadata WHERE singleton = 1"
            ).fetchone()
            if row is None:
                raise JobsSchemaIncompatible("controller SQLite metadata is missing")
            data_model_version, min_writer_build = row
            if data_model_version != ReviewState().schema_version:
                raise JobsSchemaIncompatible("controller SQLite data model is incompatible")
            if isinstance(min_writer_build, bool) or not isinstance(min_writer_build, int) or min_writer_build <= 0:
                raise JobsSchemaIncompatible("controller SQLite minimum writer build is invalid")
            if writer_build < min_writer_build:
                raise JobsSchemaIncompatible(f"controller SQLite database requires writer build {min_writer_build}")
            if "review_state" not in WorkstreamStore._table_names(connection):
                raise JobsSchemaIncompatible("controller SQLite review-state table is missing")
        except sqlite3.DatabaseError as exc:
            raise JobsSchemaIncompatible("controller SQLite metadata is incomplete or malformed") from exc

    def _require_compatible(self, connection: sqlite3.Connection) -> None:
        self._require_controller_compatible(connection, self.writer_build)
        present = self._table_names(connection) & set(MAP_TABLES)
        if not present:
            raise JobsNotBootstrapped("project-map schema is not bootstrapped; call bootstrap() explicitly")
        if present != set(MAP_TABLES):
            raise JobsSchemaIncompatible("project-map schema is partial")
        row = connection.execute(
            "SELECT map_schema_version, controller_schema_version, "
            "controller_data_model_version FROM map_metadata WHERE singleton = 1"
        ).fetchone()
        expected = (MAP_SCHEMA_VERSION, SQLITE_SCHEMA_VERSION, ReviewState().schema_version)
        if row is None or tuple(row) != expected:
            raise JobsSchemaIncompatible("project-map schema requires a different controller data model")


def _normalize_sql(value: str) -> str:
    return " ".join(value.casefold().split())
