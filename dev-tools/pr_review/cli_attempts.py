"""Read bounded, non-counting CLI attempts from existing private captures."""

from __future__ import annotations

import hashlib
import json
import re
import sqlite3
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from .sqlite_review_records import ReviewRecordsError

RUN_NAME = re.compile(r"run\.[0-9a-f]{32}\Z")
MAX_METADATA_BYTES = 16_384
MAX_ATTEMPTS = 5
MAX_CAPTURE_DIRECTORIES = 10_000
MAX_EXIT_STATUS_BYTES = 33
MAX_STDERR_BYTES = 4096
MAX_ERROR_BYTES = 4096


def failed_attempts(database: Path, pr: int) -> dict[str, Any]:
    """Expose coarse failure reasons without copying provider output or changing review counts."""
    root = database.parent / "pr-review" / "runs"
    if not root.exists():
        return {"available": True, "attempts": []}
    try:
        directories = list(root.iterdir())
    except OSError:
        return {"available": False, "attempts": []}
    attempts = []
    for directory in directories:
        if not RUN_NAME.fullmatch(directory.name) or directory.is_symlink() or not directory.is_dir():
            continue
        metadata_path = directory / "metadata.json"
        try:
            if metadata_path.is_symlink():
                continue
            with metadata_path.open("rb") as metadata_file:
                metadata_bytes = metadata_file.read(MAX_METADATA_BYTES + 1)
            if len(metadata_bytes) > MAX_METADATA_BYTES:
                continue
            metadata = json.loads(metadata_bytes.decode("utf-8"))
            if not isinstance(metadata, dict) or str(metadata.get("pull_request")) != str(pr):
                continue
            exit_path = directory / "exit-status"
            stderr_path = directory / "stderr"
            error_path = directory / "error"
            if any(path.is_symlink() for path in (exit_path, stderr_path, error_path)):
                continue
            if exit_path.exists():
                with exit_path.open("rb") as exit_file:
                    exit_status = exit_file.read(MAX_EXIT_STATUS_BYTES).decode("utf-8")[:32].strip()
                if exit_status == "0":
                    continue
                when = exit_path.stat().st_mtime
                if exit_status == "timeout":
                    outcome = "timed_out"
                else:
                    if stderr_path.exists():
                        with stderr_path.open("rb") as stderr_file:
                            stderr = stderr_file.read(MAX_STDERR_BYTES).decode("utf-8", errors="replace")
                    else:
                        stderr = ""
                    outcome = "rate_limited" if "rate limit exceeded" in stderr.casefold() else "provider_failed"
            elif error_path.exists():
                when = error_path.stat().st_mtime
                outcome = "setup_failed"
            else:
                continue
        except (OSError, ValueError, UnicodeError):
            continue
        attempts.append({
            "run_id": directory.name,
            "finished_at": datetime.fromtimestamp(when, timezone.utc).isoformat().replace("+00:00", "Z"),
            "outcome": outcome,
        })
    attempts.sort(key=lambda item: (item["finished_at"], item["run_id"]), reverse=True)
    return {"available": True, "attempts": attempts[:MAX_ATTEMPTS]}


def reconcile_legacy_failed_attempts(records: Any, database: Path) -> dict[str, Any]:
    """Import old failed CLI captures into SQL, preserving their exact run identity.

    The legacy capture is treated as migration input only. Raw provider output is
    never copied; SQL stores a coarse outcome and a fingerprint of the bounded
    source evidence so an exact replay is safe and changed evidence is reported.
    """

    root = database.parent / "pr-review" / "runs"
    try:
        root.lstat()
    except FileNotFoundError:
        return {"available": True, "imported": [], "already_imported": [], "conflicts": [], "skipped": 0}
    except OSError:
        return {
            "available": False,
            "reason": "legacy CLI capture directory could not be inspected",
            "imported": [], "already_imported": [], "conflicts": [], "skipped": 0,
        }
    if root.is_symlink() or not root.is_dir():
        return {
            "available": False,
            "reason": "legacy CLI capture directory is not a regular directory",
            "imported": [], "already_imported": [], "conflicts": [], "skipped": 0,
        }
    try:
        directories = []
        for index, directory in enumerate(root.iterdir()):
            if index >= MAX_CAPTURE_DIRECTORIES:
                return {
                    "available": False,
                    "reason": "legacy CLI capture directory exceeds the migration scan limit",
                    "imported": [], "already_imported": [], "conflicts": [], "skipped": 0,
                }
            if RUN_NAME.fullmatch(directory.name) and not directory.is_symlink() and directory.is_dir():
                directories.append(directory)
    except OSError:
        return {
            "available": False,
            "reason": "legacy CLI captures could not be enumerated",
            "imported": [], "already_imported": [], "conflicts": [], "skipped": 0,
        }
    directories.sort(key=lambda path: path.name)

    imported: list[dict[str, str]] = []
    already_imported: list[dict[str, str]] = []
    conflicts: list[dict[str, str]] = []
    skipped = 0
    for directory in directories:
        try:
            candidate = _legacy_failure(directory)
        except (OSError, ValueError, UnicodeError, OverflowError):
            skipped += 1
            continue
        if candidate is None:
            continue
        pr = candidate["pr"]
        run_id = directory.name
        attempt_metadata = {
            "origin": "legacy_private_capture",
            "capture_format": "pr-review/runs",
            "run_id": run_id,
            "legacy_outcome": candidate["outcome"],
            "source_fingerprint": candidate["source_fingerprint"],
        }
        expected = {
            "source_pr": pr,
            "channel": "cli",
            "candidate_sha": candidate["candidate_sha"],
            "state": candidate["state"],
            "started_at": candidate["started_at"],
            "finished_at": candidate["finished_at"],
            "exit_status": candidate["exit_status"],
            "diagnostic": candidate["diagnostic"],
            "metadata": attempt_metadata,
        }
        try:
            existing = records.attempt(run_id)
        except ReviewRecordsError as error:
            if str(error) != "review attempt does not exist":
                conflicts.append({"run_id": run_id, "reason": "existing attempt could not be read"})
                continue
            existing = None

        if existing is not None:
            if any(existing.get(key) != expected[key] for key in ("source_pr", "channel", "candidate_sha", "metadata")):
                conflicts.append({"run_id": run_id, "reason": "attempt identity or source fingerprint conflicts"})
                continue
            if existing["state"] not in {"started", expected["state"]} or existing["started_at"] != expected["started_at"]:
                conflicts.append({"run_id": run_id, "reason": "attempt outcome conflicts with captured evidence"})
                continue
            try:
                result = records.finish_attempt(
                    run_id,
                    state=expected["state"],
                    finished_at=expected["finished_at"],
                    exit_status=expected["exit_status"],
                    diagnostic=expected["diagnostic"],
                )
            except (ReviewRecordsError, OSError, sqlite3.DatabaseError):
                conflicts.append({"run_id": run_id, "reason": "attempt outcome conflicts with captured evidence"})
                continue
            if result["idempotent_replay"]:
                already_imported.append({"run_id": run_id, "pr": str(pr)})
            else:
                imported.append({"run_id": run_id, "pr": str(pr)})
            continue

        try:
            records.start_attempt(
                attempt_id=run_id,
                source_pr=pr,
                channel="cli",
                candidate_sha=candidate["candidate_sha"],
                started_at=candidate["started_at"],
                metadata=attempt_metadata,
            )
            records.finish_attempt(
                run_id,
                state=candidate["state"],
                finished_at=candidate["finished_at"],
                exit_status=candidate["exit_status"],
                diagnostic=candidate["diagnostic"],
            )
        except (ReviewRecordsError, OSError, sqlite3.DatabaseError):
            conflicts.append({"run_id": run_id, "reason": "attempt identity conflicts with existing SQL history"})
            continue
        imported.append({"run_id": run_id, "pr": str(pr)})

    return {
        "available": True,
        "imported": imported,
        "already_imported": already_imported,
        "conflicts": conflicts,
        "skipped": skipped,
    }


def _legacy_failure(directory: Path) -> dict[str, Any] | None:
    metadata_path = directory / "metadata.json"
    exit_path = directory / "exit-status"
    stderr_path = directory / "stderr"
    error_path = directory / "error"
    paths = (metadata_path, exit_path, stderr_path, error_path)
    if any(path.is_symlink() for path in paths):
        return None

    with metadata_path.open("rb") as metadata_file:
        metadata_bytes = metadata_file.read(MAX_METADATA_BYTES + 1)
    if len(metadata_bytes) > MAX_METADATA_BYTES:
        return None
    metadata = json.loads(metadata_bytes.decode("utf-8"))
    if not isinstance(metadata, dict):
        return None
    pr_value = metadata.get("pull_request")
    if isinstance(pr_value, bool) or not isinstance(pr_value, (int, str)):
        return None
    try:
        pr = int(pr_value)
    except ValueError:
        return None
    if pr <= 0 or str(pr) != str(pr_value).strip():
        return None
    if metadata.get("run_id", directory.name) != directory.name:
        return None

    metadata_stat = metadata_path.stat()
    if exit_path.exists():
        with exit_path.open("rb") as exit_file:
            exit_bytes = exit_file.read(MAX_EXIT_STATUS_BYTES)
        if len(exit_bytes) >= MAX_EXIT_STATUS_BYTES:
            return None
        exit_text = exit_bytes.decode("utf-8").strip()
        if exit_text == "0":
            return None
        finished_stat = exit_path.stat()
        if exit_text == "timeout":
            state = "timed_out"
            outcome = "timed_out"
            exit_status = None
            diagnostic = "Imported legacy CLI attempt timed out."
            stderr_bytes = b""
        else:
            if stderr_path.exists():
                with stderr_path.open("rb") as stderr_file:
                    stderr_bytes = stderr_file.read(MAX_STDERR_BYTES)
            else:
                stderr_bytes = b""
            state = "rate_limited" if b"rate limit exceeded" in stderr_bytes.lower() else "failed"
            outcome = "rate_limited" if state == "rate_limited" else "provider_failed"
            try:
                exit_status = int(exit_text) if re.fullmatch(r"-?[0-9]{1,9}", exit_text) else None
            except ValueError:
                exit_status = None
            diagnostic = (
                "Imported legacy CLI attempt was rate limited."
                if state == "rate_limited"
                else "Imported legacy CLI provider attempt failed."
            )
        source = {
            "metadata_sha256": hashlib.sha256(metadata_bytes).hexdigest(),
            "exit_status_sha256": hashlib.sha256(exit_bytes).hexdigest(),
            "exit_status_size": len(exit_bytes),
            "stderr_prefix_sha256": hashlib.sha256(stderr_bytes if exit_text != "timeout" else b"").hexdigest(),
            "stderr_size": stderr_path.stat().st_size if stderr_path.exists() else 0,
            "stderr_mtime_ns": stderr_path.stat().st_mtime_ns if stderr_path.exists() else None,
            "completion_mtime_ns": finished_stat.st_mtime_ns,
        }
    elif error_path.exists():
        with error_path.open("rb") as error_file:
            error_bytes = error_file.read(MAX_ERROR_BYTES)
        finished_stat = error_path.stat()
        state = "failed"
        outcome = "setup_failed"
        exit_status = None
        diagnostic = "Imported legacy CLI setup attempt failed."
        source = {
            "metadata_sha256": hashlib.sha256(metadata_bytes).hexdigest(),
            "error_prefix_sha256": hashlib.sha256(error_bytes).hexdigest(),
            "error_size": finished_stat.st_size,
            "error_mtime_ns": finished_stat.st_mtime_ns,
            "completion_mtime_ns": finished_stat.st_mtime_ns,
        }
    else:
        return None

    candidate_sha = metadata.get("candidate_sha")
    if not isinstance(candidate_sha, str) or not re.fullmatch(r"[0-9a-fA-F]{40}|[0-9a-fA-F]{64}", candidate_sha):
        candidate_sha = None
    started_epoch = metadata_stat.st_mtime
    finished_epoch = finished_stat.st_mtime
    started_at = datetime.fromtimestamp(min(started_epoch, finished_epoch), timezone.utc).isoformat().replace("+00:00", "Z")
    finished_at = datetime.fromtimestamp(finished_epoch, timezone.utc).isoformat().replace("+00:00", "Z")
    fingerprint_source = {
        "run_id": directory.name,
        "pr": pr,
        "candidate_sha": candidate_sha,
        "state": state,
        "source": source,
    }
    fingerprint = hashlib.sha256(
        json.dumps(fingerprint_source, sort_keys=True, separators=(",", ":"), allow_nan=False).encode("utf-8")
    ).hexdigest()
    return {
        "pr": pr,
        "candidate_sha": candidate_sha,
        "state": state,
        "outcome": outcome,
        "started_at": started_at,
        "finished_at": finished_at,
        "exit_status": exit_status,
        "diagnostic": diagnostic,
        "source_fingerprint": fingerprint,
    }
