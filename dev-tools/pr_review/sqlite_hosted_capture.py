"""Capture one Hosted request and its terminal GitHub evidence in SQLite.

This module records observations only. It never chooses a review target or
posts a request. The caller starts an attempt before posting, then supplies the
durable Hosted trigger record and complete GitHub pull-request payload after a
result is observed.
"""

from __future__ import annotations

import json
import re
import sqlite3
import stat
from collections.abc import Mapping
from pathlib import Path
from typing import Any

from . import evidence, github, hosted
from .sqlite_review_records import FindingObservation, ReviewRecordsError, SqliteReviewRecords


class HostedCaptureError(ValueError):
    """Raised when Hosted request identity or result evidence is incomplete."""


def start_hosted_attempt(
    records: SqliteReviewRecords,
    *,
    attempt_id: str,
    source_pr: int,
    candidate_sha: str,
    started_at: str | None = None,
    metadata: Mapping[str, Any] | None = None,
) -> dict[str, Any]:
    """Record the pre-request boundary; the caller remains responsible for POST."""

    if metadata is not None and not isinstance(metadata, Mapping):
        raise HostedCaptureError("Hosted attempt metadata must be an object")
    attempt_metadata = {"request_kind": "full", **dict(metadata or {})}
    if attempt_metadata.get("request_kind") != "full":
        raise HostedCaptureError("Hosted attempt metadata cannot change the request kind")
    return records.start_attempt(
        attempt_id=attempt_id,
        source_pr=source_pr,
        channel="hosted",
        candidate_sha=candidate_sha,
        started_at=started_at,
        metadata=attempt_metadata,
    )


def record_hosted_terminal_result(
    records: SqliteReviewRecords,
    *,
    attempt_id: str,
    repo: str,
    source_pr: int,
    trigger_record: Mapping[str, Any],
    payload: dict[str, Any],
    current_record_path: str | Path | None = None,
    observed_at: str | None = None,
) -> dict[str, Any]:
    """Persist a Hosted result after existing evidence rules prove its state.

    A nonterminal observation leaves the attempt open so a later complete
    history can be supplied. Completed attributable results receive a source
    run linked to the attempt; zero-finding runs are finalized immediately.
    Terminal replays are keyed by immutable trigger and provider response IDs;
    they return the stored source run without rewriting archived terminal data.
    """

    record = _validate_trigger_record(repo, source_pr, trigger_record)
    attempt = _matching_attempt(records, attempt_id, source_pr, record["head_sha"])
    if attempt["state"] == "completed" and attempt["run_id"] is None:
        return _recover_archived_completed_attempt(
            records, attempt_id, repo, source_pr, record, current_record_path
        )
    pull_request = _complete_pull_request(payload, source_pr)
    try:
        result = hosted.trigger_state(repo, source_pr, payload, record, current_record_path)
    except (KeyError, TypeError, ValueError) as exc:
        raise HostedCaptureError(f"Hosted result attribution failed: {exc}") from exc

    if not result.terminal:
        if attempt["state"] != "started":
            raise HostedCaptureError("stored terminal Hosted result is no longer verifiable by current history")
        return {
            "attempt_id": attempt_id,
            "state": result.state,
            "terminal": False,
            "attributable": result.attributed,
            "reason": result.reason,
            "response_id": result.response_id,
            "run_id": None,
        }

    completed = result.state == "completed" and result.attributed is True
    attempt_state = (
        "completed"
        if completed
        else "rate_limited"
        if result.state == "rate_limited"
        else "failed"
        if result.state in {"failed", "noop"}
        else "ambiguous"
    )
    finish_time, response_review, response_comment = _terminal_event(
        result, pull_request, observed_at=observed_at
    )
    trigger_id = str(record["trigger"]["id"])
    # Schema v5 has one provider-result identity field for both GitHub review
    # objects and finished reply comments.
    provider_response_id = str(result.response_id) if result.response_id is not None else None
    if attempt["state"] != "started":
        if (
            attempt["state"] != attempt_state
            or attempt["trigger_id"] != trigger_id
            or attempt["provider_review_id"] != provider_response_id
        ):
            raise HostedCaptureError("terminal Hosted replay conflicts with its stored immutable identity")
        if completed:
            if attempt["run_id"] not in (None, attempt_id):
                raise HostedCaptureError("terminal Hosted attempt links to a different source run")
            runs = [
                item for item in records.history(source_pr)["runs"] if item["run_id"] == attempt_id
            ]
            if len(runs) != 1:
                raise HostedCaptureError("completed Hosted attempt has no unique stored source run")
            if attempt["run_id"] is None:
                # Recover only the exact already-created run after a crash
                # between record_run and link_attempt_run.
                records.link_attempt_run(attempt_id, attempt_id)
            return {
                "attempt_id": attempt_id,
                "state": result.state,
                "terminal": True,
                "attributable": result.attributed,
                "response_id": result.response_id,
                "run_id": attempt_id,
                "counts": runs[0]["counts"],
                "idempotent_replay": True,
            }
        return {
            "attempt_id": attempt_id,
            "state": result.state,
            "terminal": True,
            "attributable": result.attributed,
            "response_id": result.response_id,
            "run_id": None,
            "counts": None,
            "idempotent_replay": True,
        }

    findings: tuple[FindingObservation, ...] = ()
    if completed:
        findings = _findings_for_completed_result(
            result,
            pull_request,
            record,
            response_review=response_review,
            response_comment=response_comment,
            finished_at=finish_time,
        )

    trigger = record["trigger"]
    checkpoint_id = (
        _checkpoint_id(pull_request, result.response_id) if provider_response_id is not None else None
    )
    capture_metadata = {
        "state": result.state,
        "terminal": result.terminal,
        "attributable": result.attributed,
        "reason": result.reason,
        "repository": repo,
        "pull_request": source_pr,
        "head_sha": record["head_sha"],
        "trigger_id": trigger["id"],
        "response_id": result.response_id,
        "observed_at": finish_time,
    }
    artifacts = {
        "hosted_review": _json(pull_request["reviews"]["nodes"]),
        "hosted_comments": _json(
            {
                "comments": pull_request["comments"]["nodes"],
                "review_threads": pull_request["reviewThreads"]["nodes"],
            }
        ),
        "metadata": _json(capture_metadata),
    }
    finish_fields = {
        "state": attempt_state,
        "finished_at": finish_time,
        "duration_seconds": result.duration_seconds,
        "trigger_id": str(trigger["id"]),
        "provider_review_id": provider_response_id,
        "checkpoint_id": checkpoint_id,
        "diagnostic": "" if completed else result.reason,
        "artifacts": artifacts,
    }
    run_result: dict[str, Any] | None = None
    if completed:
        completion = records.complete_attempt_run(
            attempt_id,
            finish=finish_fields,
            run={
                "run_id": attempt_id,
                "source_pr": source_pr,
                "channel": "hosted",
                "findings": findings,
                "outcome": "completed",
                "attributable": True,
                "source_head": record["head_sha"],
                "reviewer": _reviewer_name(response_review, response_comment),
                "scope": "broad",
                "started_at": trigger["created_at"],
                "finished_at": finish_time,
            },
            finalize_empty=not findings,
        )
        finished = completion["attempt"]
        run_result = completion["run"]
    else:
        finished = records.finish_attempt(attempt_id, **finish_fields)

    return {
        "attempt_id": attempt_id,
        "state": result.state,
        "terminal": True,
        "attributable": result.attributed,
        "response_id": result.response_id,
        "run_id": attempt_id if run_result is not None else None,
        "counts": run_result["counts"] if run_result is not None else None,
        "idempotent_replay": bool(finished["idempotent_replay"])
        and (run_result is None or bool(run_result["idempotent_replay"])),
    }


def _recover_archived_completed_attempt(
    records: SqliteReviewRecords,
    attempt_id: str,
    repo: str,
    source_pr: int,
    record: dict[str, Any],
    current_record_path: Path | None,
) -> dict[str, Any]:
    """Recover an old partial completion from its immutable stored snapshot."""

    attempt = records.attempt(attempt_id)
    attempt_row = next(
        item for item in records.attempt_history(source_pr) if item["attempt_id"] == attempt_id
    )
    archive = records.attempt_artifacts(attempt_id)
    if not {"hosted_review", "hosted_comments", "metadata"} <= archive.keys():
        raise HostedCaptureError("completed Hosted attempt lacks exact archived evidence for recovery")
    try:
        reviews = json.loads(archive["hosted_review"])
        comments = json.loads(archive["hosted_comments"])
        metadata = json.loads(archive["metadata"])
    except (TypeError, ValueError, json.JSONDecodeError) as exc:
        raise HostedCaptureError("completed Hosted attempt has malformed archived evidence") from exc
    if (
        not isinstance(metadata, dict) or metadata.get("state") != "completed"
        or metadata.get("attributable") is not True
        or metadata.get("repository") != repo
        or metadata.get("pull_request") != source_pr
        or metadata.get("head_sha") != record["head_sha"]
        or str(metadata.get("trigger_id")) != str(record["trigger"]["id"])
        or str(metadata.get("response_id")) != attempt_row["provider_review_id"]
        or attempt_row["trigger_id"] != str(record["trigger"]["id"])
        or not isinstance(reviews, list) or not isinstance(comments, dict)
        or not isinstance(comments.get("comments"), list)
        or not isinstance(comments.get("review_threads"), list)
    ):
        raise HostedCaptureError("completed Hosted archive conflicts with its immutable request identity")
    archived_payload = {
        "data": {"repository": {"pullRequest": {
            "number": source_pr,
            "headRefOid": record["head_sha"],
            "reviews": {"nodes": reviews},
            "comments": {"nodes": comments["comments"]},
            "reviewThreads": {"nodes": comments["review_threads"]},
        }}}
    }
    pull_request = _complete_pull_request(archived_payload, source_pr)
    try:
        result = hosted.trigger_state(
            repo, source_pr, archived_payload, record, current_record_path
        )
    except (KeyError, TypeError, ValueError) as exc:
        raise HostedCaptureError(f"archived Hosted attribution failed: {exc}") from exc
    if (
        result.state != "completed" or result.attributed is not True
        or str(result.response_id) != attempt_row["provider_review_id"]
    ):
        raise HostedCaptureError("archived Hosted result does not prove the stored completion")
    finished_at = attempt_row["finished_at"]
    if not isinstance(finished_at, str) or not finished_at:
        raise HostedCaptureError("completed Hosted attempt lacks its terminal timestamp")
    _event_at, response_review, response_comment = _terminal_event(
        result, pull_request, observed_at=finished_at
    )
    findings = _findings_for_completed_result(
        result, pull_request, record,
        response_review=response_review, response_comment=response_comment,
        finished_at=finished_at,
    )
    recorded = records.recover_completed_attempt_run(
        attempt_id,
        run={
            "run_id": attempt_id,
            "source_pr": source_pr,
            "channel": "hosted",
            "findings": findings,
            "outcome": "completed",
            "attributable": True,
            "source_head": attempt["candidate_sha"],
            "reviewer": _reviewer_name(response_review, response_comment),
            "scope": "broad",
            "started_at": record["trigger"]["created_at"],
            "finished_at": finished_at,
        },
        finalize_empty=not findings,
    )
    return {
        "attempt_id": attempt_id,
        "state": "completed",
        "terminal": True,
        "attributable": True,
        "response_id": result.response_id,
        "run_id": attempt_id,
        "counts": recorded["counts"],
        "idempotent_replay": False,
        "recovered_from_archive": True,
    }


def sync_hosted_pending(
    records: SqliteReviewRecords,
    repo: str,
    common: str | Path | None = None,
    pr_number: int | None = None,
) -> dict[str, list[dict[str, Any]]]:
    """Sync pending attempts from durable Hosted trigger records and one PR snapshot each.

    This explicit mutation path treats private trigger records as the request
    identity source. It never uses controller history or status observations as
    authority and never posts a Hosted request.
    """

    if not isinstance(repo, str) or not repo.strip():
        raise HostedCaptureError("repository must be a non-empty owner/name")
    try:
        safe_repo = hosted._safe_repo(repo)
    except (TypeError, ValueError) as exc:
        raise HostedCaptureError(f"repository identity is invalid: {exc}") from exc
    if pr_number is not None and (type(pr_number) is not int or pr_number <= 0):
        raise HostedCaptureError("pull-request number must be a positive integer")
    root = Path(common) if common is not None else evidence.git_common_dir()
    report: dict[str, list[dict[str, Any]]] = {
        "synced": [],
        "pending": [],
        "ambiguous": [],
        "errors": [],
    }

    def add(bucket: str, pr: int | None, path: Path | None, **details: Any) -> None:
        item: dict[str, Any] = {"pr": pr, **details}
        if path is not None:
            item["record"] = str(path)
        report[bucket].append(item)

    paths = _sync_hosted_record_paths(root, safe_repo, pr_number, add)
    grouped: dict[int, list[tuple[Path, dict[str, Any]]]] = {}
    attempt_paths: dict[str, list[tuple[int, Path, dict[str, Any]]]] = {}
    for pr, path in paths:
        try:
            record = hosted.load_trigger_reservation(path, repo, pr)
        except (OSError, ValueError, KeyError, TypeError, json.JSONDecodeError) as exc:
            add("errors", pr, path, error=f"trigger record cannot be verified ({type(exc).__name__})")
            continue
        attempt_id = record.get("sqlite_attempt_id")
        if attempt_id is None:
            continue
        if not isinstance(attempt_id, str) or not attempt_id.strip():
            add("errors", pr, path, error="trigger record has an invalid SQLite attempt ID")
            continue
        attempt_paths.setdefault(attempt_id, []).append((pr, path, record))
        grouped.setdefault(pr, []).append((path, record))

    duplicate_attempt_ids = {
        attempt_id for attempt_id, entries in attempt_paths.items() if len(entries) > 1
    }
    for attempt_id in sorted(duplicate_attempt_ids):
        for pr, path, _record in attempt_paths[attempt_id]:
            add(
                "errors",
                pr,
                path,
                attempt_id=attempt_id,
                error="SQLite attempt ID appears in more than one Hosted trigger record",
            )

    payloads: dict[int, dict[str, Any] | None] = {}
    for pr in sorted(grouped):
        attempt_rows: dict[str, dict[str, Any]] = {}
        try:
            for item in records.attempt_history(pr):
                attempt_rows.setdefault(item["attempt_id"], item)
        except (ReviewRecordsError, sqlite3.Error, OSError) as exc:
            for path, record in grouped[pr]:
                attempt_id = record.get("sqlite_attempt_id")
                if attempt_id not in duplicate_attempt_ids:
                    add(
                        "errors",
                        pr,
                        path,
                        attempt_id=attempt_id,
                        error=f"SQLite attempt history is unavailable ({type(exc).__name__})",
                    )
            continue

        ready: list[tuple[Path, dict[str, Any], str]] = []
        for path, record in grouped[pr]:
            attempt_id = record["sqlite_attempt_id"]
            if attempt_id in duplicate_attempt_ids:
                continue
            try:
                request_started_at = _validate_pending_record(repo, pr, record)
                trigger = record.get("trigger")
                trigger_id = str(trigger["id"]) if isinstance(trigger, Mapping) else None
                if trigger_id is None and record.get("status") != "posting":
                    raise HostedCaptureError("Hosted trigger record has no verifiable request identity")

                attempt_history = attempt_rows.get(attempt_id)
                if attempt_history is None:
                    metadata: dict[str, Any] = {"repository": repo}
                    anchor = record.get("anchor")
                    if isinstance(anchor, Mapping):
                        metadata["anchor"] = dict(anchor)
                    actor = record.get("posting_actor_login")
                    if isinstance(actor, str) and actor.strip():
                        metadata["posting_actor"] = actor
                    start_hosted_attempt(
                        records,
                        attempt_id=attempt_id,
                        source_pr=pr,
                        candidate_sha=record["head_sha"],
                        started_at=request_started_at,
                        metadata=metadata,
                    )
                    attempt = records.attempt(attempt_id)
                    attempt_history = next(
                        item
                        for item in records.attempt_history(pr)
                        if item["attempt_id"] == attempt_id
                    )
                    attempt_rows[attempt_id] = attempt_history
                else:
                    attempt = records.attempt(attempt_id)
                for field in ("trigger_id", "provider_review_id", "checkpoint_id"):
                    attempt[field] = attempt_history[field]
                if (
                    attempt["source_pr"] != pr
                    or attempt["channel"] != "hosted"
                    or attempt["candidate_sha"] != record["head_sha"]
                    or attempt["started_at"] != request_started_at
                ):
                    raise HostedCaptureError("SQLite attempt does not match exact Hosted request identity")
                metadata = attempt.get("metadata")
                if not isinstance(metadata, dict) or metadata.get("repository") != repo:
                    raise HostedCaptureError("SQLite attempt metadata does not match the exact repository")

                if record.get("status") == "posting":
                    add(
                        "ambiguous",
                        pr,
                        path,
                        attempt_id=attempt_id,
                        state="posting",
                        reason="durable POST reservation has no verified public trigger comment yet",
                    )
                    continue

                attempt_state = attempt["state"]
                if attempt_state == "completed":
                    if attempt["run_id"] is None:
                        recovered = _recover_archived_completed_attempt(
                            records, attempt_id, repo, pr, record, path
                        )
                        add(
                            "synced", pr, path, attempt_id=attempt_id,
                            trigger_id=trigger_id, state="completed",
                            run_id=recovered["run_id"], counts=recovered["counts"],
                            recovered_from_archive=True,
                        )
                        continue
                    if attempt["trigger_id"] != trigger_id:
                        raise HostedCaptureError("completed Hosted attempt has a different trigger ID")
                    add(
                        "synced",
                        pr,
                        path,
                        attempt_id=attempt_id,
                        trigger_id=trigger_id,
                        state="completed",
                        run_id=attempt["run_id"],
                        idempotent_replay=True,
                    )
                    continue
                if attempt_state != "started":
                    if attempt["trigger_id"] != trigger_id:
                        raise HostedCaptureError("terminal Hosted attempt has a different trigger ID")
                    bucket = "ambiguous" if attempt_state in {"ambiguous", "timed_out"} else "synced"
                    add(
                        bucket,
                        pr,
                        path,
                        attempt_id=attempt_id,
                        trigger_id=trigger_id,
                        state=attempt_state,
                        idempotent_replay=True,
                    )
                    continue
                if trigger_id is None:
                    raise HostedCaptureError("pending Hosted attempt has no public trigger identity")
                ready.append((path, record, attempt_id))
            except (
                HostedCaptureError,
                ReviewRecordsError,
                sqlite3.Error,
                OSError,
                KeyError,
                TypeError,
                ValueError,
                RuntimeError,
            ) as exc:
                add(
                    "errors",
                    pr,
                    path,
                    attempt_id=attempt_id,
                    error=(str(exc) if isinstance(exc, HostedCaptureError) else type(exc).__name__),
                )

        if not ready:
            continue
        try:
            payloads[pr] = github.fetch_pull_request(repo, pr)
        except (RuntimeError, OSError, TypeError, ValueError) as exc:
            payloads[pr] = None
            for path, _record, attempt_id in ready:
                add(
                    "errors",
                    pr,
                    path,
                    attempt_id=attempt_id,
                    error=f"complete GitHub history fetch failed ({type(exc).__name__})",
                )
            continue

        payload = payloads[pr]
        if payload is None:
            continue
        for path, record, attempt_id in ready:
            try:
                result = record_hosted_terminal_result(
                    records,
                    attempt_id=attempt_id,
                    repo=repo,
                    source_pr=pr,
                    trigger_record=record,
                    payload=payload,
                    current_record_path=path,
                )
            except (
                HostedCaptureError,
                ReviewRecordsError,
                sqlite3.Error,
                OSError,
                KeyError,
                TypeError,
                ValueError,
                RuntimeError,
            ) as exc:
                add(
                    "errors",
                    pr,
                    path,
                    attempt_id=attempt_id,
                    error=(str(exc) if isinstance(exc, HostedCaptureError) else type(exc).__name__),
                )
                continue
            trigger_id = str(record["trigger"]["id"])
            entry = {
                "pr": pr,
                "record": str(path),
                "attempt_id": attempt_id,
                "trigger_id": trigger_id,
                "state": result["state"],
                "terminal": result["terminal"],
                "run_id": result["run_id"],
            }
            if result.get("counts") is not None:
                entry["counts"] = result["counts"]
            if not result["terminal"]:
                bucket = "ambiguous" if result["state"] in {"ambiguous", "unattributed"} else "pending"
                entry["reason"] = result.get("reason")
                report[bucket].append(entry)
            elif result["state"] in {"ambiguous", "unattributed", "retired"}:
                report["ambiguous"].append(entry)
            else:
                entry["idempotent_replay"] = result.get("idempotent_replay", False)
                report["synced"].append(entry)

    return report


def _sync_hosted_record_paths(
    root: Path,
    safe_repo: str,
    pr_number: int | None,
    add_report: Any,
) -> list[tuple[int, Path]]:
    namespaces = ("firemud", "coderabbit-review-logs")
    selected_prs: set[int] = set()
    repository_dirs = [root / namespace / "hosted" / safe_repo for namespace in namespaces]
    if pr_number is not None:
        selected_prs.add(pr_number)
    else:
        for repository_dir in repository_dirs:
            try:
                mode = repository_dir.lstat().st_mode
            except FileNotFoundError:
                continue
            except OSError as exc:
                add_report(
                    "errors", None, repository_dir,
                    error=f"Hosted trigger directory cannot be inspected ({type(exc).__name__})",
                )
                continue
            if stat.S_ISLNK(mode) or not stat.S_ISDIR(mode):
                add_report("errors", None, repository_dir, error="Hosted trigger directory is unsafe")
                continue
            try:
                children = list(repository_dir.iterdir())
            except OSError as exc:
                add_report(
                    "errors", None, repository_dir,
                    error=f"Hosted pull-request directories cannot be listed ({type(exc).__name__})",
                )
                continue
            for child in children:
                match = re.fullmatch(r"pr-([1-9][0-9]*)", child.name)
                if match is None:
                    continue
                try:
                    child_mode = child.lstat().st_mode
                except OSError as exc:
                    add_report(
                        "errors", int(match.group(1)), child,
                        error=f"Hosted pull-request directory cannot be inspected ({type(exc).__name__})",
                    )
                    continue
                if stat.S_ISLNK(child_mode) or not stat.S_ISDIR(child_mode):
                    add_report(
                        "errors", int(match.group(1)), child,
                        error="Hosted pull-request directory is unsafe",
                    )
                    continue
                selected_prs.add(int(match.group(1)))

    found: list[tuple[int, Path]] = []
    for pr in sorted(selected_prs):
        for repository_dir in repository_dirs:
            directory = repository_dir / f"pr-{pr}"
            try:
                mode = directory.lstat().st_mode
            except FileNotFoundError:
                continue
            except OSError as exc:
                add_report(
                    "errors", pr, directory,
                    error=f"Hosted pull-request directory cannot be inspected ({type(exc).__name__})",
                )
                continue
            if stat.S_ISLNK(mode) or not stat.S_ISDIR(mode):
                add_report("errors", pr, directory, error="Hosted pull-request directory is unsafe")
                continue
            try:
                children = list(directory.iterdir())
            except OSError as exc:
                add_report(
                    "errors", pr, directory,
                    error=f"Hosted trigger records cannot be listed ({type(exc).__name__})",
                )
                continue
            for child in children:
                archived = hosted.ARCHIVED.fullmatch(child.name) is not None
                if child.name != "trigger.json" and not archived:
                    if child.name.startswith("trigger-") and child.name.endswith(".json"):
                        add_report("errors", pr, child, error="Hosted trigger archive name is malformed")
                    continue
                try:
                    child_mode = child.lstat().st_mode
                except OSError as exc:
                    add_report(
                        "errors", pr, child,
                        error=f"Hosted trigger record cannot be inspected ({type(exc).__name__})",
                    )
                    continue
                if stat.S_ISLNK(child_mode) or not stat.S_ISREG(child_mode):
                    add_report("errors", pr, child, error="Hosted trigger record is unsafe")
                    continue
                found.append((pr, child))
    return sorted(found, key=lambda item: (item[0], str(item[1])))


def _validate_pending_record(repo: str, source_pr: int, record: Mapping[str, Any]) -> str:
    if (
        record.get("repository") != repo
        or type(record.get("pr_number")) is not int
        or record["pr_number"] != source_pr
    ):
        raise HostedCaptureError("Hosted trigger record does not match the exact repository and PR")
    head = record.get("head_sha")
    if not isinstance(head, str) or not hosted.EXACT_SHA.fullmatch(head):
        raise HostedCaptureError("Hosted trigger record has no exact candidate head")
    started_at = record.get("posting_started_at")
    if not isinstance(started_at, str) or hosted.parse_timestamp(started_at) is None:
        raise HostedCaptureError("Hosted trigger record has no exact pre-POST start time")
    if record.get("status") != "posting":
        _validate_trigger_record(repo, source_pr, record)
    return started_at


def _validate_trigger_record(
    repo: str,
    source_pr: int,
    trigger_record: Mapping[str, Any],
) -> dict[str, Any]:
    if not isinstance(trigger_record, Mapping):
        raise HostedCaptureError("Hosted trigger record must be an object")
    record = dict(trigger_record)
    if record.get("repository") != repo or record.get("pr_number") != source_pr:
        raise HostedCaptureError("Hosted trigger record does not match the requested repository and PR")
    if record.get("status") not in {
        "posted",
        "posted_boundary_changed",
        "posted_boundary_unverified",
        "retired",
    }:
        raise HostedCaptureError("Hosted trigger record has no verified posted request")
    head = record.get("head_sha")
    if not isinstance(head, str) or not hosted.EXACT_SHA.fullmatch(head):
        raise HostedCaptureError("Hosted trigger record has no exact candidate head")
    trigger = record.get("trigger")
    if not isinstance(trigger, Mapping):
        raise HostedCaptureError("Hosted trigger record has no request identity")
    normalized = dict(trigger)
    if (
        normalized.get("type") != "full"
        or hosted.normalize_command(normalized.get("command") or "") != hosted.FULL_COMMAND
        or type(normalized.get("id")) is not int
        or normalized["id"] <= 0
        or not isinstance(normalized.get("created_at"), str)
        or hosted.parse_timestamp(normalized["created_at"]) is None
        or not isinstance(normalized.get("url"), str)
        or not normalized["url"]
    ):
        raise HostedCaptureError("Hosted trigger record has an invalid full-review identity")
    record["trigger"] = normalized
    return record


def _matching_attempt(
    records: SqliteReviewRecords,
    attempt_id: str,
    source_pr: int,
    candidate_sha: str,
) -> dict[str, Any]:
    matches = [item for item in records.attempt_history(source_pr) if item["attempt_id"] == attempt_id]
    if len(matches) != 1:
        raise HostedCaptureError("Hosted attempt is not registered exactly once")
    attempt = matches[0]
    if attempt["channel"] != "hosted" or attempt["candidate_sha"] != candidate_sha:
        raise HostedCaptureError("Hosted attempt does not match the captured request identity")
    if attempt["state"] not in {
        "started",
        "completed",
        "failed",
        "rate_limited",
        "timed_out",
        "ambiguous",
    }:
        raise HostedCaptureError("Hosted attempt has an unsupported state")
    return attempt


def _complete_pull_request(payload: dict[str, Any], source_pr: int) -> dict[str, Any]:
    if not isinstance(payload, dict):
        raise HostedCaptureError("GitHub pull-request payload must be an object")
    try:
        pull_request = github._pull_request_from_graphql_payload(payload)
        if type(pull_request.get("number")) is not int or pull_request["number"] != source_pr:
            raise HostedCaptureError("GitHub pull-request payload has a different PR number")
        for name in github.REVIEW_CONNECTIONS:
            nodes, page_info = github._review_connection(pull_request, name, require_page_info=False)
            github._reject_incomplete_input_connection(name, page_info)
            if any(not isinstance(item, dict) for item in nodes):
                raise HostedCaptureError(f"GitHub {name} history contains a malformed item")
            if name == "reviewThreads":
                for thread in nodes:
                    thread_comments, thread_page = github._review_connection(
                        thread, "comments", require_page_info=False
                    )
                    github._reject_incomplete_input_connection("review-thread comments", thread_page)
                    if any(not isinstance(item, dict) for item in thread_comments):
                        raise HostedCaptureError("GitHub review-thread history contains a malformed comment")
    except HostedCaptureError:
        raise
    except (KeyError, TypeError, RuntimeError) as exc:
        raise HostedCaptureError(f"complete GitHub review history is unavailable: {exc}") from exc
    return pull_request


def _terminal_event(
    result: hosted.TriggerState,
    pull_request: dict[str, Any],
    *,
    observed_at: str | None,
) -> tuple[str, dict[str, Any] | None, dict[str, Any] | None]:
    matching_reviews = [
        item
        for item in pull_request["reviews"]["nodes"]
        if result.response_id is not None and github.immutable_database_id(item) == result.response_id
    ]
    matching_comments = [
        item
        for item in pull_request["comments"]["nodes"]
        if result.response_id is not None and github.immutable_database_id(item) == result.response_id
    ]
    if len(matching_reviews) + len(matching_comments) > 1:
        raise HostedCaptureError("Hosted response ID is duplicated in GitHub history")
    response_review = matching_reviews[0] if matching_reviews else None
    response_comment = matching_comments[0] if matching_comments else None
    event_at: str | None = None
    if response_review is not None:
        event_at = response_review.get("submittedAt")
    elif response_comment is not None:
        created = response_comment.get("createdAt")
        updated = response_comment.get("updatedAt")
        event_at = created
        if result.duration_seconds is not None and hosted.parse_timestamp(updated) is not None:
            created_dt = hosted.parse_timestamp(created)
            updated_dt = hosted.parse_timestamp(updated)
            if created_dt is not None and updated_dt is not None and updated_dt > created_dt:
                # trigger_state only supplies duration when this edit remains
                # inside the captured request window.
                event_at = updated
    if event_at is None:
        event_at = observed_at or hosted.utc_now()
    if hosted.parse_timestamp(event_at) is None:
        raise HostedCaptureError("Hosted terminal event has no valid timestamp")
    return event_at, response_review, response_comment


def _findings_for_completed_result(
    result: hosted.TriggerState,
    pull_request: dict[str, Any],
    record: dict[str, Any],
    *,
    response_review: dict[str, Any] | None,
    response_comment: dict[str, Any] | None,
    finished_at: str,
) -> tuple[FindingObservation, ...]:
    trigger = record["trigger"]
    started = hosted.parse_timestamp(trigger["created_at"])
    finished = hosted.parse_timestamp(finished_at)
    if started is None or finished is None or finished < started:
        raise HostedCaptureError("Hosted request window has invalid timestamps")
    observations: list[FindingObservation] = []
    seen_ids: set[int] = set()
    for thread in pull_request["reviewThreads"]["nodes"]:
        comments = thread["comments"]["nodes"]
        if not comments:
            continue
        comment = comments[0]
        author = comment.get("author")
        if not github.is_coderabbit_login(author.get("login") if isinstance(author, dict) else None):
            continue
        created = hosted.parse_timestamp(comment.get("createdAt"))
        if created is None or not started < created <= finished:
            continue
        comment_id = github.immutable_database_id(comment)
        body = comment.get("body")
        if comment_id is None or comment_id in seen_ids or not isinstance(body, str):
            raise HostedCaptureError("Hosted review finding has incomplete immutable identity")
        seen_ids.add(comment_id)
        title = next((line.strip() for line in body.splitlines() if line.strip()), "")
        if not title:
            title = f"CodeRabbit review comment {comment_id}"
        title = title[:300]
        try:
            observations.append(
                FindingObservation(
                    source_finding_key=f"hosted-trigger:{trigger['id']}:comment:{comment_id}",
                    title=title,
                )
            )
        except ReviewRecordsError:
            # Raw evidence remains in the scrubbed artifact; structured history
            # uses a safe headline if the provider prose resembles a secret.
            observations.append(
                FindingObservation(
                    source_finding_key=f"hosted-trigger:{trigger['id']}:comment:{comment_id}",
                    title=f"CodeRabbit review comment {comment_id}",
                )
            )

    source_kind = "review" if response_review is not None else "comment"
    source = response_review or response_comment
    body = source.get("body") if isinstance(source, dict) else None
    if isinstance(body, str):
        try:
            outside_diff, duplicate = evidence.summary_action_counts(body)
            has_sections = evidence.has_summary_action_sections(body)
        except evidence.EvidenceError as exc:
            raise HostedCaptureError(f"Hosted summary evidence is malformed: {exc}") from exc
        if has_sections:
            source_id = github.immutable_database_id(source)
            if source_id is None:
                raise HostedCaptureError("Hosted summary has no immutable identity")
            for kind, count in (("outside_diff", outside_diff), ("duplicate", duplicate)):
                for ordinal in range(1, count + 1):
                    observations.append(
                        FindingObservation(
                            source_finding_key=(
                                f"hosted-summary:{source_kind}:{source_id}:{kind}:{ordinal}"
                            ),
                            title=(
                                f"CodeRabbit summary-only {kind.replace('_', '-')} finding "
                                f"{ordinal} of {count} (aggregate; no individual detail)"
                            ),
                            detail="summary-only aggregate; provider supplied no individual detail",
                        )
                    )
    if len(observations) > 200:
        raise HostedCaptureError("Hosted result exceeds the SQLite finding limit")
    return tuple(observations)


def _reviewer_name(
    response_review: dict[str, Any] | None,
    response_comment: dict[str, Any] | None,
) -> str:
    response = response_review or response_comment or {}
    author = response.get("author")
    login = author.get("login") if isinstance(author, dict) else None
    return login if isinstance(login, str) and login.strip() else "CodeRabbit"


def _checkpoint_id(pull_request: dict[str, Any], review_id: int | None) -> str | None:
    if review_id is None:
        return None
    comments = []
    for item in pull_request["comments"]["nodes"]:
        author = item.get("author")
        comments.append(
            {
                "id": github.immutable_database_id(item),
                "body": item.get("body"),
                "created_at": item.get("createdAt"),
                "updated_at": item.get("updatedAt"),
                "author_login": author.get("login") if isinstance(author, dict) else None,
            }
        )
    try:
        checkpoints, _ = evidence.parse_checkpoint_comments(comments)
    except evidence.EvidenceError:
        return None
    matches = [item for item in checkpoints if item.type == "Hosted" and item.hosted_review_id == review_id]
    return str(matches[0].comment_id) if len(matches) == 1 and matches[0].comment_id is not None else None


def _json(value: Any) -> str:
    try:
        return json.dumps(value, ensure_ascii=True, sort_keys=True, separators=(",", ":"), allow_nan=False)
    except (TypeError, ValueError) as exc:
        raise HostedCaptureError("GitHub Hosted evidence is not JSON serializable") from exc


__all__ = [
    "HostedCaptureError",
    "record_hosted_terminal_result",
    "start_hosted_attempt",
    "sync_hosted_pending",
]
