"""Read bounded, non-counting CLI attempts from existing private captures."""

from __future__ import annotations

import fcntl
import hashlib
import json
import os
import re
import sqlite3
import stat
import time
import unicodedata
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from . import evidence
from .sqlite_provider_imports import _cli_finding_title
from .sqlite_review_records import (
    AttemptNotFound,
    FindingObservation,
    ReviewRecordsError,
    _redact_archive_text,
)

RUN_NAME = re.compile(r"run\.[0-9a-f]{32}\Z")
MAX_METADATA_BYTES = 16_384
MAX_ATTEMPTS = 5
MAX_CAPTURE_DIRECTORIES = 10_000
MAX_EXIT_STATUS_BYTES = 33
MAX_STDERR_BYTES = 4096
MAX_ERROR_BYTES = 4096
MAX_RECOVERY_STDOUT_BYTES = 8 * 1024 * 1024
MAX_RECOVERY_STDERR_BYTES = 128 * 1024
ORPHANED_CAPTURE_MIN_AGE_SECONDS = 10 * 60


class _UnrecordableCapture(ValueError):
    """A provider capture cannot prove one exact completed CLI run."""


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
    """Reconcile captures while excluding an active CLI review process."""

    root = database.parent / "pr-review" / "runs"
    try:
        root.lstat()
    except FileNotFoundError:
        return {
            "available": True,
            "imported": [],
            "already_imported": [],
            "recovered": [],
            "terminally_classified": [],
            "conflicts": [],
            "skipped": 0,
        }
    except OSError:
        return {
            "available": False,
            "reason": "legacy CLI capture directory could not be inspected",
            "imported": [], "already_imported": [], "recovered": [],
            "terminally_classified": [], "conflicts": [], "skipped": 0,
        }

    lock_path = database.parent / "pr-review" / "cli.lock"
    try:
        lock_path.touch(mode=0o600, exist_ok=True)
        lock_handle = lock_path.open("r+")
    except OSError:
        return {
            "available": False,
            "reason": "CLI review lock could not be opened or acquired",
            "imported": [], "already_imported": [], "recovered": [],
            "terminally_classified": [], "conflicts": [], "skipped": 0,
        }
    acquired = False
    try:
        try:
            fcntl.flock(lock_handle.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            return {
                "available": False,
                "reason": "a CLI review is active; legacy captures were not reconciled",
                "imported": [], "already_imported": [], "recovered": [],
                "terminally_classified": [], "conflicts": [], "skipped": 0,
            }
        except OSError:
            return {
                "available": False,
                "reason": "CLI review lock could not be opened or acquired",
                "imported": [], "already_imported": [], "recovered": [],
                "terminally_classified": [], "conflicts": [], "skipped": 0,
            }
        acquired = True
        return _reconcile_legacy_failed_attempts_locked(records, database)
    finally:
        if acquired:
            fcntl.flock(lock_handle.fileno(), fcntl.LOCK_UN)
        lock_handle.close()


def _reconcile_legacy_failed_attempts_locked(records: Any, database: Path) -> dict[str, Any]:
    """Reconcile legacy CLI captures, preserving each exact run identity.

    Failed captures are imported as non-counting attempts. A successful capture
    is recovered only when its original SQL attempt is still started and its
    complete provider output proves one exact completed run. Raw provider output
    is never exposed by the migration report.
    """

    root = database.parent / "pr-review" / "runs"
    try:
        root.lstat()
    except FileNotFoundError:
        return {
            "available": True,
            "imported": [],
            "already_imported": [],
            "recovered": [],
            "terminally_classified": [],
            "conflicts": [],
            "skipped": 0,
        }
    except OSError:
        return {
            "available": False,
            "reason": "legacy CLI capture directory could not be inspected",
            "imported": [], "already_imported": [], "recovered": [],
            "terminally_classified": [], "conflicts": [], "skipped": 0,
        }
    if root.is_symlink() or not root.is_dir():
        return {
            "available": False,
            "reason": "legacy CLI capture directory is not a regular directory",
            "imported": [], "already_imported": [], "recovered": [],
            "terminally_classified": [], "conflicts": [], "skipped": 0,
        }
    try:
        directories = []
        for index, directory in enumerate(root.iterdir()):
            if index >= MAX_CAPTURE_DIRECTORIES:
                return {
                    "available": False,
                    "reason": "legacy CLI capture directory exceeds the migration scan limit",
                    "imported": [], "already_imported": [], "recovered": [],
                    "terminally_classified": [], "conflicts": [], "skipped": 0,
                }
            if RUN_NAME.fullmatch(directory.name) and not directory.is_symlink() and directory.is_dir():
                directories.append(directory)
    except OSError:
        return {
            "available": False,
            "reason": "legacy CLI captures could not be enumerated",
            "imported": [], "already_imported": [], "recovered": [],
            "terminally_classified": [], "conflicts": [], "skipped": 0,
        }
    directories.sort(key=lambda path: path.name)

    imported: list[dict[str, str]] = []
    already_imported: list[dict[str, str]] = []
    recovered: list[dict[str, str]] = []
    terminally_classified: list[dict[str, str]] = []
    conflicts: list[dict[str, str]] = []
    skipped = 0
    for directory in directories:
        try:
            recovery = _reconcile_successful_capture(records, directory)
        except (OSError, ValueError, UnicodeError, OverflowError):
            recovery = None
        if recovery is None:
            try:
                recovery = _reconcile_failed_capture(records, directory)
            except (OSError, ValueError, UnicodeError, OverflowError):
                recovery = None
        if recovery is None:
            try:
                recovery = _reconcile_incomplete_native_capture(records, directory)
            except (OSError, ValueError, UnicodeError, OverflowError):
                recovery = None
        if recovery is not None:
            action = recovery["action"]
            if action == "conflict":
                conflicts.append({"run_id": directory.name, "reason": recovery["reason"]})
            elif action == "recovered":
                item = {"run_id": directory.name, "pr": str(recovery["pr"])}
                recovered.append(item)
            elif action == "terminally_classified":
                item = {"run_id": directory.name, "pr": str(recovery["pr"])}
                terminally_classified.append(item)
            continue
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
        except AttemptNotFound:
            existing = None
        except ReviewRecordsError:
            conflicts.append({"run_id": run_id, "reason": "existing attempt could not be read"})
            continue

        if existing is not None:
            if existing.get("source_pr") != pr or existing.get("channel") != "cli":
                conflicts.append({"run_id": run_id, "reason": "attempt identity or source fingerprint conflicts"})
                continue
            if (
                existing.get("state") in {"completed", "failed", "rate_limited", "timed_out", "ambiguous"}
                and existing.get("metadata", {}).get("origin") != "legacy_private_capture"
            ):
                already_imported.append({"run_id": run_id, "pr": str(pr)})
                continue
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
        "recovered": recovered,
        "terminally_classified": terminally_classified,
        "conflicts": conflicts,
        "skipped": skipped,
    }


def _reconcile_successful_capture(records: Any, directory: Path) -> dict[str, Any] | None:
    """Replay one durable successful capture into its matching started attempt.

    A successful provider exit is migration input only when the SQL attempt with
    the same run ID still exists and is ``started``.  This keeps old standalone
    captures out of the structured history and makes a failed replay safe to
    retry without creating a second source run.
    """

    try:
        metadata = _read_capture_json(directory / "metadata.json", MAX_METADATA_BYTES, "metadata")
    except _UnrecordableCapture:
        return None
    marker_path = directory / "capture-complete"
    marker_required = metadata.get("capture_completion_marker") == "capture-complete"
    if marker_required:
        try:
            marker = _read_capture_text(marker_path, 128, "completion marker")
        except _UnrecordableCapture:
            return None
        if marker != f"{directory.name}\n":
            return None
    elif type(metadata.get("exit_status")) is not int or type(metadata.get("duration_seconds")) is not int:
        # Older writers had no completion marker. Their final metadata was
        # written after exit-status and duration, so an initial metadata file
        # must remain retryable while those writes are still in progress.
        return None

    exit_path = directory / "exit-status"
    if exit_path.is_symlink() or not exit_path.is_file():
        return None
    try:
        exit_text = _read_capture_text(exit_path, MAX_EXIT_STATUS_BYTES, "exit status").strip()
    except _UnrecordableCapture:
        return None
    if exit_text != "0":
        return None

    try:
        attempt = records.attempt(directory.name)
    except AttemptNotFound:
        return None
    except ReviewRecordsError:
        return {"action": "conflict", "pr": 0, "reason": "existing attempt could not be read"}
    if attempt["state"] != "started":
        return None

    try:
        capture = _successful_capture(directory, attempt)
    except _UnrecordableCapture:
        diagnostic = "CLI provider capture could not be reconciled as an exact completed review"
        try:
            try:
                finished_at = _capture_finished_at(exit_path)
                finished_at = _finished_at_not_before(finished_at, attempt["started_at"])
            except _UnrecordableCapture:
                finished_at = None
            records.finish_attempt(
                directory.name,
                state="failed",
                finished_at=finished_at,
                exit_status=0,
                diagnostic=diagnostic,
                artifacts={"cli_diagnostic": diagnostic},
            )
        except (ReviewRecordsError, OSError, sqlite3.DatabaseError):
            return {
                "action": "conflict",
                "pr": attempt["source_pr"],
                "reason": "unrecordable capture could not be terminally classified",
            }
        return {"action": "terminally_classified", "pr": attempt["source_pr"]}

    try:
        records.complete_attempt_run(
            directory.name,
            finish={
                "state": "completed",
                "finished_at": capture["finished_at"],
                "duration_seconds": capture["duration_seconds"],
                "exit_status": 0,
                "diagnostic": "",
                "artifacts": capture["artifacts"],
            },
            run={
                "run_id": directory.name,
                "source_pr": capture["pr"],
                "channel": "cli",
                "findings": capture["findings"],
                "outcome": "completed",
                "attributable": True,
                "source_head": capture["candidate_sha"],
                "reviewer": "CodeRabbit CLI",
                "scope": "broad",
                "started_at": attempt["started_at"],
                "finished_at": capture["finished_at"],
            },
            finalize_empty=not capture["findings"],
        )
    except (ReviewRecordsError, OSError, sqlite3.DatabaseError):
        # A valid capture remains available for a later migration retry when
        # the database itself is unavailable or has a transient conflict.
        return {
            "action": "conflict",
            "pr": capture["pr"],
            "reason": "completed capture could not be archived",
        }
    return {"action": "recovered", "pr": capture["pr"]}


def _reconcile_failed_capture(records: Any, directory: Path) -> dict[str, Any] | None:
    """Finish a native started attempt from its exact durable failed capture."""

    try:
        attempt = records.attempt(directory.name)
    except AttemptNotFound:
        return None
    except ReviewRecordsError:
        return {"action": "conflict", "pr": 0, "reason": "existing attempt could not be read"}
    if attempt["state"] != "started" or attempt["channel"] != "cli":
        return None

    try:
        metadata = _read_capture_json(directory / "metadata.json", MAX_METADATA_BYTES, "metadata")
        if metadata.get("run_id") != directory.name or metadata.get("kind") != "cli":
            return None
        if metadata.get("capture_completion_marker") != "capture-complete":
            return None
        original_metadata = {
            key: value for key, value in metadata.items()
            if key not in {"duration_seconds", "exit_status", "timed_out"}
        }
        if original_metadata != attempt["metadata"]:
            return None
        pr = _capture_pr(metadata.get("pull_request"))
        candidate_sha = metadata.get("candidate_sha")
        if (
            pr != attempt["source_pr"]
            or candidate_sha != attempt["candidate_sha"]
        ):
            return None
        marker = _read_capture_text(directory / "capture-complete", 128, "completion marker")
        if marker != f"{directory.name}\n":
            return None
        exit_path = directory / "exit-status"
        exit_text = _read_capture_text(exit_path, MAX_EXIT_STATUS_BYTES, "exit status").strip()
        if exit_text == "0":
            return None
        if exit_text == "timeout":
            state = "timed_out"
            exit_status = None
            if metadata.get("exit_status") is not None:
                return None
        elif re.fullmatch(r"-?[0-9]{1,9}", exit_text):
            exit_status = int(exit_text)
            if exit_status == 0 or metadata.get("exit_status") != exit_status:
                return None
            state = "failed"
        else:
            return None
        if (state == "timed_out" and metadata.get("timed_out") is not True) or (
            state != "timed_out" and metadata.get("timed_out") not in (None, False)
        ):
            return None
        stderr = _read_diagnostic_prefix(directory / "stderr")
        if metadata.get("duration_seconds") is not None:
            duration_text = _read_capture_text(
                directory / "review-duration-seconds", MAX_EXIT_STATUS_BYTES, "review duration"
            ).strip()
            if not re.fullmatch(r"[0-9]{1,9}", duration_text):
                return None
            duration = int(duration_text)
            if metadata["duration_seconds"] != duration:
                return None
        else:
            duration = None
        finished_at = _capture_finished_at(exit_path)
        finished_at = _finished_at_not_before(finished_at, attempt["started_at"])
    except _UnrecordableCapture:
        return None

    if state != "timed_out" and "rate limit exceeded" in stderr.casefold():
        state = "rate_limited"
        summary = "CodeRabbit CLI was rate limited"
    elif state == "timed_out":
        summary = "CodeRabbit CLI timed out before a complete result"
    else:
        summary = "CodeRabbit CLI exited nonzero"
    diagnostic = _archive_diagnostic(summary, stderr)
    try:
        records.finish_attempt(
            directory.name,
            state=state,
            finished_at=finished_at,
            duration_seconds=duration,
            exit_status=exit_status,
            diagnostic=diagnostic,
        )
    except (ReviewRecordsError, OSError, sqlite3.DatabaseError):
        return {
            "action": "conflict",
            "pr": attempt["source_pr"],
            "reason": "failed capture could not finish the exact started attempt",
        }
    return {"action": "terminally_classified", "pr": attempt["source_pr"]}


def _reconcile_incomplete_native_capture(records: Any, directory: Path) -> dict[str, Any] | None:
    """Fail closed on an exact native attempt whose capture never completed."""

    try:
        attempt = records.attempt(directory.name)
    except AttemptNotFound:
        return None
    except ReviewRecordsError:
        return {"action": "conflict", "pr": 0, "reason": "existing attempt could not be read"}
    if attempt["state"] != "started" or attempt["channel"] != "cli":
        return None

    metadata_path = directory / "metadata.json"
    try:
        metadata = _read_capture_json(metadata_path, MAX_METADATA_BYTES, "metadata")
        if metadata.get("run_id") != directory.name or metadata.get("kind") != "cli":
            return None
        original_metadata = {
            key: value for key, value in metadata.items()
            if key not in {"duration_seconds", "exit_status", "timed_out"}
        }
        if original_metadata != attempt["metadata"]:
            return None
        pr = _capture_pr(metadata.get("pull_request"))
        candidate_sha = metadata.get("candidate_sha")
        if pr != attempt["source_pr"] or candidate_sha != attempt["candidate_sha"]:
            return None

        marker_path = directory / "capture-complete"
        if marker_path.exists() or marker_path.is_symlink():
            return None
        error_path = directory / "error"
        exit_path = directory / "exit-status"
        if error_path.is_symlink() or exit_path.is_symlink():
            return None
        terminal_path = error_path if error_path.is_file() else exit_path if exit_path.is_file() else None
        if terminal_path is None:
            metadata_age = time.time() - metadata_path.stat().st_mtime
            if metadata_age < ORPHANED_CAPTURE_MIN_AGE_SECONDS:
                return None
            finished_at = _capture_finished_at(metadata_path)
            finished_at = _finished_at_not_before(finished_at, attempt["started_at"])
            summary = "CLI runner ended without a terminal capture record"
            exit_status = None
            state = "failed"
            detail = ""
        elif terminal_path == error_path:
            finished_at = _capture_finished_at(error_path)
            finished_at = _finished_at_not_before(finished_at, attempt["started_at"])
            summary = "CLI setup or capture failed"
            exit_status = None
            state = "failed"
            detail = _read_diagnostic_prefix(error_path)
        else:
            exit_text = _read_capture_text(exit_path, MAX_EXIT_STATUS_BYTES, "exit status").strip()
            finished_at = _capture_finished_at(exit_path)
            finished_at = _finished_at_not_before(finished_at, attempt["started_at"])
            if exit_text == "timeout":
                state = "timed_out"
                exit_status = None
                summary = "CodeRabbit CLI timed out before a complete result"
            elif re.fullmatch(r"-?[0-9]{1,9}", exit_text):
                exit_status = int(exit_text)
                state = "failed"
                summary = (
                    "CodeRabbit CLI exited successfully without a complete capture"
                    if exit_status == 0
                    else "CodeRabbit CLI exited before a complete capture"
                )
            else:
                return None
            detail = _read_diagnostic_prefix(directory / "stderr")
    except _UnrecordableCapture:
        return None

    diagnostic = _archive_diagnostic(summary, detail)
    try:
        records.finish_attempt(
            directory.name,
            state=state,
            finished_at=finished_at,
            exit_status=exit_status,
            diagnostic=diagnostic,
        )
    except (ReviewRecordsError, OSError, sqlite3.DatabaseError):
        return {
            "action": "conflict",
            "pr": attempt["source_pr"],
            "reason": "incomplete capture could not finish the exact started attempt",
        }
    return {"action": "terminally_classified", "pr": attempt["source_pr"]}


def _successful_capture(directory: Path, attempt: dict[str, Any]) -> dict[str, Any]:
    metadata_path = directory / "metadata.json"
    stdout_path = directory / "stdout"
    stderr_path = directory / "stderr"
    duration_path = directory / "review-duration-seconds"
    metadata = _read_capture_json(metadata_path, MAX_METADATA_BYTES, "metadata")
    run_id = directory.name
    if metadata.get("run_id") != run_id or metadata.get("kind") != "cli":
        raise _UnrecordableCapture("capture identity is invalid")
    pr = _capture_pr(metadata.get("pull_request"))
    candidate_sha = metadata.get("candidate_sha")
    if not isinstance(candidate_sha, str) or not re.fullmatch(
        r"[0-9a-fA-F]{40}|[0-9a-fA-F]{64}", candidate_sha
    ):
        raise _UnrecordableCapture("capture candidate identity is invalid")
    if (
        attempt["channel"] != "cli"
        or attempt["source_pr"] != pr
        or attempt["candidate_sha"] != candidate_sha
    ):
        raise _UnrecordableCapture("capture identity conflicts with the SQL attempt")
    initial_metadata = {
        key: value
        for key, value in metadata.items()
        if key not in {"duration_seconds", "exit_status", "timed_out"}
    }
    if attempt["metadata"] != initial_metadata:
        raise _UnrecordableCapture("capture metadata conflicts with the SQL attempt")
    if type(metadata.get("exit_status")) is not int or metadata["exit_status"] != 0:
        raise _UnrecordableCapture("capture exit status is not successful")
    if metadata.get("timed_out"):
        raise _UnrecordableCapture("capture is marked as timed out")

    stdout = _read_capture_text(stdout_path, MAX_RECOVERY_STDOUT_BYTES, "stdout")
    stderr = _read_capture_text(stderr_path, MAX_RECOVERY_STDERR_BYTES, "stderr")
    duration_text = _read_capture_text(duration_path, MAX_EXIT_STATUS_BYTES, "review duration").strip()
    if not re.fullmatch(r"[0-9]{1,9}", duration_text):
        raise _UnrecordableCapture("capture duration is invalid")
    duration = int(duration_text)
    if type(metadata.get("duration_seconds")) is not int or metadata["duration_seconds"] != duration:
        raise _UnrecordableCapture("capture duration does not match metadata")
    findings = _parse_successful_stdout(stdout)
    observations = []
    for index, finding in enumerate(findings, 1):
        title = _cli_finding_title(
            finding.get("codegenInstructions"), f"CodeRabbit CLI finding {index}"
        )
        try:
            observations.append(FindingObservation(
                source_finding_key=f"cli-run:{run_id}:finding:{index}",
                title=title,
            ))
        except ReviewRecordsError as error:
            raise _UnrecordableCapture("capture finding metadata is invalid") from error
    finished_at = _capture_finished_at(directory / "exit-status")
    _finished_at_not_before(finished_at, attempt["started_at"], fail_closed=True)
    return {
        "pr": pr,
        "candidate_sha": candidate_sha,
        "duration_seconds": duration,
        "finished_at": finished_at,
        "findings": observations,
        "artifacts": {
            "cli_events": stdout,
            "cli_diagnostic": stderr,
            "metadata": json.dumps(metadata, sort_keys=True),
        },
    }


def _capture_pr(value: Any) -> int:
    if isinstance(value, bool) or not isinstance(value, (int, str)):
        raise _UnrecordableCapture("capture PR identity is invalid")
    try:
        pr = int(value)
    except (TypeError, ValueError) as error:
        raise _UnrecordableCapture("capture PR identity is invalid") from error
    if pr <= 0 or str(pr) != str(value).strip():
        raise _UnrecordableCapture("capture PR identity is invalid")
    return pr


def _read_capture_text(path: Path, limit: int, description: str) -> str:
    if path.is_symlink() or not path.is_file():
        raise _UnrecordableCapture(f"capture {description} is unavailable")
    try:
        with path.open("rb") as capture_file:
            value = capture_file.read(limit + 1)
    except OSError as error:
        raise _UnrecordableCapture(f"capture {description} is unreadable") from error
    if len(value) > limit:
        raise _UnrecordableCapture(f"capture {description} exceeds its bounded size")
    try:
        return value.decode("utf-8")
    except UnicodeDecodeError as error:
        raise _UnrecordableCapture(f"capture {description} is not UTF-8") from error


def _read_diagnostic_prefix(path: Path) -> str:
    """Read only a safe, bounded stderr prefix; oversized evidence is truncated."""

    descriptor: int | None = None
    try:
        descriptor = os.open(path, os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0) | os.O_NONBLOCK)
        if not stat.S_ISREG(os.fstat(descriptor).st_mode):
            return ""
        with os.fdopen(descriptor, "rb") as diagnostic_file:
            descriptor = None
            return diagnostic_file.read(MAX_STDERR_BYTES).decode("utf-8", errors="replace")
    except OSError:
        return ""
    finally:
        if descriptor is not None:
            os.close(descriptor)


def _archive_diagnostic(summary: str, detail: str = "") -> str:
    """Normalize provider controls before redaction and bounded persistence."""

    diagnostic = f"{summary}: {detail}" if detail else summary
    diagnostic = "".join(
        " " if unicodedata.category(character) == "Cc" else character
        for character in diagnostic
    )
    diagnostic, _ = _redact_archive_text(diagnostic)
    return diagnostic[:1000].rstrip()


def _read_capture_json(path: Path, limit: int, description: str) -> dict[str, Any]:
    try:
        value = json.loads(_read_capture_text(path, limit, description))
    except (TypeError, ValueError, json.JSONDecodeError) as error:
        raise _UnrecordableCapture(f"capture {description} is malformed") from error
    if not isinstance(value, dict):
        raise _UnrecordableCapture(f"capture {description} is not an object")
    return value


def _parse_successful_stdout(stdout: str) -> list[dict[str, Any]]:
    try:
        findings, _ = evidence.parse_capture_events(stdout)
    except evidence.EvidenceError as error:
        message = str(error)
        invalid_json = re.fullmatch(r"linked capture stdout has invalid JSON at line ([0-9]+)", message)
        non_object = re.fullmatch(r"linked capture stdout has a non-object event at line ([0-9]+)", message)
        if invalid_json:
            message = f"capture stdout JSON is invalid at line {invalid_json.group(1)}"
        elif non_object:
            message = f"capture stdout event at line {non_object.group(1)} is not an object"
        else:
            message = message.removeprefix("linked ")
            message = message.replace(
                "completion does not match findings/files",
                "completion does not match its findings",
            )
        raise _UnrecordableCapture(message) from error
    return findings


def _capture_finished_at(path: Path) -> str:
    try:
        finished_at = datetime.fromtimestamp(path.stat().st_mtime, timezone.utc)
    except (OSError, OverflowError, ValueError) as error:
        raise _UnrecordableCapture("capture completion time is unavailable") from error
    return finished_at.isoformat(timespec="seconds").replace("+00:00", "Z")


def _finished_at_not_before(
    finished_at: str,
    started_at: str,
    *,
    fail_closed: bool = False,
) -> str:
    """Keep a linked terminal timestamp at or after its attempt start.

    Legacy failure captures may have stale filesystem mtimes, so their
    terminal timestamp is clamped to the durable attempt start. A successful
    capture must instead fail closed when its completion precedes the linked
    attempt, since clamping would manufacture credited review evidence.
    """

    try:
        finished = datetime.fromisoformat(finished_at.replace("Z", "+00:00"))
        started = datetime.fromisoformat(started_at.replace("Z", "+00:00"))
    except (AttributeError, TypeError, ValueError) as error:
        raise _UnrecordableCapture("capture completion time is invalid") from error
    if finished.tzinfo is None or finished.utcoffset() is None:
        raise _UnrecordableCapture("capture completion time has no timezone")
    if started.tzinfo is None or started.utcoffset() is None:
        raise _UnrecordableCapture("attempt start time has no timezone")
    if finished < started:
        if fail_closed:
            raise _UnrecordableCapture("capture completion precedes the attempt start")
        return started_at
    return finished_at


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
