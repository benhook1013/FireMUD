"""One-off, evidence-bound backfill of provider runs into SQLite records.

This helper repairs structured history from already parsed public checkpoints
and their existing private captures. It never requests a review or changes
controller taper. Preview mode imports into a private clone of the selected
compatible SQLite database and leaves the supplied database and capture evidence
untouched.

Checkpoint provenance is linked through ``SqliteReviewRecords.link_provider_origin``.
That origin-link API must be present before this helper can write; the helper
does not create tables or synthesize attempt rows to work around a missing
origin-link contract.
"""

from __future__ import annotations

import contextlib
import dataclasses
import hashlib
import json
import os
import re
import sqlite3
import tempfile
from collections.abc import Mapping, Sequence
from pathlib import Path
from typing import Any

from . import evidence, github, hosted, sqlite_hosted_capture, sqlite_provider_imports, sqlite_review_records
from .sqlite_review_records import ReviewRecordsError, SqliteReviewRecords

_API_VERSION = 1
_MAX_PARTIAL_DIAGNOSTIC = 1000
_REPOSITORY = re.compile(r"^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$")
_PROVIDER_ID = re.compile(r"^(?:review|trigger|run):[A-Za-z0-9._-]{1,80}$")


class SqliteRecordsRepairError(ValueError):
    """Raised when source evidence or its attribution is not safe to repair."""

    code = "unavailable"


class MissingHistoricalEvidenceError(SqliteRecordsRepairError):
    """Raised when the linked historical capture is genuinely absent."""

    code = "missing_historical_evidence"


def _partial_diagnostic(exc: OSError | sqlite3.DatabaseError) -> str:
    detail = str(exc).strip()
    if not detail:
        detail = type(exc).__name__
    return f"{type(exc).__name__}: {detail}"[:_MAX_PARTIAL_DIAGNOSTIC].rstrip()


def _validate_request(
    records: SqliteReviewRecords,
    *,
    repo: str,
    pr_number: int,
    checkpoints: Sequence[evidence.Checkpoint],
    actor: str,
    scope: str,
    coverage_limits: Sequence[str],
) -> tuple[str, list[evidence.Checkpoint]]:
    if not isinstance(records, SqliteReviewRecords):
        raise SqliteRecordsRepairError("repair requires a SqliteReviewRecords store")
    if not isinstance(repo, str) or not _REPOSITORY.fullmatch(repo):
        raise SqliteRecordsRepairError("repository must be an exact owner/name pair")
    if isinstance(pr_number, bool) or not isinstance(pr_number, int) or pr_number <= 0:
        raise SqliteRecordsRepairError("PR number must be a positive integer")
    if isinstance(checkpoints, (str, bytes)) or not isinstance(checkpoints, Sequence):
        raise SqliteRecordsRepairError("checkpoints must be a sequence of parsed checkpoint records")
    if not isinstance(actor, str) or not actor.strip() or len(actor) > 100:
        raise SqliteRecordsRepairError("repair actor must be non-empty text of at most 100 characters")
    if scope not in {"broad", "narrow"}:
        raise SqliteRecordsRepairError("scope must be broad or narrow")
    if isinstance(coverage_limits, (str, bytes)) or not isinstance(coverage_limits, Sequence):
        raise SqliteRecordsRepairError("coverage limits must be a sequence of text values")

    by_comment: dict[int, tuple[str, evidence.Checkpoint]] = {}
    for checkpoint in checkpoints:
        if not isinstance(checkpoint, evidence.Checkpoint):
            raise SqliteRecordsRepairError("every checkpoint must come from the canonical evidence parser")
        comment_id = checkpoint.comment_id
        if isinstance(comment_id, bool) or not isinstance(comment_id, int) or comment_id <= 0:
            raise SqliteRecordsRepairError("every checkpoint needs an exact positive public comment ID")
        if checkpoint.type not in {"Hosted", "CLI"}:
            raise SqliteRecordsRepairError("checkpoint channel must be Hosted or CLI")
        if checkpoint.correction:
            raise SqliteRecordsRepairError("correction checkpoints are not provider review runs")
        if checkpoint.duration_invalid:
            raise SqliteRecordsRepairError("checkpoint duration evidence is invalid")
        if checkpoint.reviewed_sha is None:
            raise SqliteRecordsRepairError("checkpoint has no reviewed-head identity")
        for name, number in (("found", checkpoint.raw_found), ("accepted", checkpoint.accepted)):
            if isinstance(number, bool) or not isinstance(number, int) or number < 0:
                raise SqliteRecordsRepairError(f"checkpoint {name} count is invalid")
        if checkpoint.accepted > checkpoint.raw_found:
            raise SqliteRecordsRepairError("checkpoint accepted count exceeds its raw finding count")
        if checkpoint.routed is not None and (
            isinstance(checkpoint.routed, bool)
            or not isinstance(checkpoint.routed, int)
            or checkpoint.routed < 0
            or checkpoint.accepted + checkpoint.routed > checkpoint.raw_found
        ):
            raise SqliteRecordsRepairError("checkpoint routed count is invalid")

        fingerprint = sqlite_provider_imports._checkpoint_fingerprint(checkpoint)
        prior = by_comment.get(comment_id)
        if prior is not None:
            if prior[0] != fingerprint:
                raise SqliteRecordsRepairError(f"public checkpoint comment {comment_id} has conflicting parsed content")
            continue
        by_comment[comment_id] = (fingerprint, checkpoint)

    return repo, [item[1] for item in sorted(by_comment.values(), key=lambda item: item[1].comment_id or 0)]


def _require_origin_link_api(records: SqliteReviewRecords) -> None:
    if not callable(getattr(records, "link_provider_origin", None)):
        raise SqliteRecordsRepairError("provider origin-link API is unavailable; no repair was written")
    if not callable(getattr(records, "archive_imported_artifacts", None)):
        raise SqliteRecordsRepairError("historical artifact API is unavailable; no repair was written")


def _clone_records(records: SqliteReviewRecords, destination: Path) -> SqliteReviewRecords:
    """Take a consistent read-only snapshot for a full dry-run preflight."""

    try:
        records._require_regular_database()
        with contextlib.closing(records._connect(read_only=True)) as source:
            records._require_compatible(source)
            with contextlib.closing(sqlite3.connect(destination)) as target:
                source.backup(target)
        os.chmod(destination, 0o600)
        clone = SqliteReviewRecords(destination, writer_build=records.writer_build, timeout=records.timeout)
        with contextlib.closing(clone._connect(read_only=True)) as connection:
            clone._require_compatible(connection)
        return clone
    except (OSError, sqlite3.DatabaseError, ReviewRecordsError) as exc:
        raise SqliteRecordsRepairError(f"cannot create a compatible preflight snapshot: {exc}") from exc


def _provider_id(checkpoint: evidence.Checkpoint, imported: dict[str, Any]) -> str:
    selected = imported.get("provider_id")
    if selected is None:
        if checkpoint.type == "Hosted" and checkpoint.hosted_review_id is not None:
            selected = f"review:{checkpoint.hosted_review_id}"
        elif checkpoint.type == "CLI" and checkpoint.run_id is not None:
            selected = f"run:{checkpoint.run_id}"
    if not isinstance(selected, str) or not _PROVIDER_ID.fullmatch(selected):
        raise SqliteRecordsRepairError(
            "canonical provider import did not prove a typed provider identity for this checkpoint"
        )
    if checkpoint.type == "CLI" and not selected.startswith("run:"):
        raise SqliteRecordsRepairError("CLI provider identity must use the exact run ID")
    if checkpoint.type == "Hosted" and not selected.startswith(("review:", "trigger:")):
        raise SqliteRecordsRepairError("Hosted provider identity must name its exact review or trigger")
    return selected


def _import_one(
    records: SqliteReviewRecords,
    *,
    repo: str,
    pr_number: int,
    checkpoint: evidence.Checkpoint,
    actor: str,
    common: Path | None,
    scope: str,
    coverage_limits: Sequence[str],
    summary_dispositions: Sequence[Any] | None,
    hosted_payload: dict[str, Any] | None,
) -> tuple[dict[str, Any], str]:
    def import_checkpoint(target: SqliteReviewRecords) -> dict[str, Any]:
        if checkpoint.type == "Hosted":
            return sqlite_provider_imports.import_hosted_checkpoint(
                target,
                repo=repo,
                pr_number=pr_number,
                checkpoint=checkpoint,
                actor=actor,
                common=common,
                scope=scope,  # type: ignore[arg-type]
                coverage_limits=coverage_limits,
                summary_dispositions=summary_dispositions,
                hosted_payload=hosted_payload,
            )
        return sqlite_provider_imports.import_cli_checkpoint(
            target,
            repo=repo,
            pr_number=pr_number,
            checkpoint=checkpoint,
            actor=actor,
            common=common,
            scope=scope,  # type: ignore[arg-type]
            coverage_limits=coverage_limits,
        )

    try:
        matching_attempts = [
            item
            for item in records.attempt_history(pr_number)
            if item["channel"] == ("hosted" if checkpoint.type == "Hosted" else "cli")
            and (
                item["attempt_id"] == checkpoint.run_id
                if checkpoint.type == "CLI"
                else item["provider_review_id"] == str(checkpoint.hosted_review_id)
            )
        ]
        if len(matching_attempts) > 1:
            raise SqliteRecordsRepairError(
                "provider checkpoint matches multiple completed attempts; exact run is uncertain"
            )
        if matching_attempts:
            attempt = matching_attempts[0]
            if checkpoint.type == "Hosted" and attempt["state"] == "ambiguous" and attempt["run_id"] is None:
                # A reply may be edited to completion after the first immutable
                # observation. Retain that observation and append only the
                # canonical, independently verified reply-only source import.
                with tempfile.TemporaryDirectory(prefix="pr-review-late-reply-") as scratch:
                    probe = _clone_records(records, Path(scratch) / "records.sqlite3")
                    verified = import_checkpoint(probe)
                    metadata = json.loads(verified.get("archive_artifacts", {}).get("metadata", "{}"))
                trigger = metadata.get("trigger_record", {})
                attempt_repo = records.attempt(attempt["attempt_id"])["metadata"].get("repository")
                proof = metadata.get("zero_reply_proof", {})
                if (
                    metadata.get("capture_format") != "firemud-hosted-trigger-reply/v1"
                    or metadata.get("repository") != repo.casefold()
                    or not isinstance(attempt_repo, str)
                    or attempt_repo.casefold() != repo.casefold()
                    or metadata.get("pull_request") != pr_number
                    or trigger.get("head_sha") != attempt["candidate_sha"]
                    or proof.get("commit_id") != attempt["candidate_sha"]
                    or str(metadata.get("trigger_id")) != attempt["trigger_id"]
                    or str(metadata.get("response_id")) != attempt["provider_review_id"]
                    or trigger.get("sqlite_attempt_id") != attempt["attempt_id"]
                    or attempt["checkpoint_id"] not in (None, str(checkpoint.comment_id))
                    or verified.get("counts") != {"found": 0, "accepted": 0, "routed": 0}
                ):
                    raise SqliteRecordsRepairError("late Hosted reply conflicts with its immutable ambiguous attempt")
                imported = import_checkpoint(records)
                return imported, _provider_id(checkpoint, imported)
            if attempt["state"] != "completed" or attempt["run_id"] != attempt["attempt_id"]:
                raise SqliteRecordsRepairError(
                    "matching provider attempt is not linked to its exact completed source run"
                )
            if (
                attempt["checkpoint_id"] is not None
                and checkpoint.comment_id is not None
                and (attempt["checkpoint_id"] != str(checkpoint.comment_id))
            ):
                raise SqliteRecordsRepairError("matching provider attempt names a different public checkpoint")

            if checkpoint.type == "Hosted" and not any(
                path.exists() or path.is_symlink()
                for path in evidence._hosted_snapshot_candidates(
                    repo, pr_number, checkpoint.hosted_review_id or 0, common
                )
            ):
                attempt_artifacts = records.attempt_artifacts(attempt["attempt_id"])
                try:
                    retained_reviews = json.loads(attempt_artifacts["hosted_review"])
                except (KeyError, TypeError, ValueError, json.JSONDecodeError) as exc:
                    raise SqliteRecordsRepairError(
                        "completed Hosted attempt has malformed retained review evidence"
                    ) from exc
                if not isinstance(retained_reviews, list):
                    raise SqliteRecordsRepairError("completed Hosted attempt has malformed retained review evidence")
                if any(not isinstance(review, dict) for review in retained_reviews):
                    raise SqliteRecordsRepairError("completed Hosted attempt has malformed retained review evidence")
                matching_reviews = [
                    review
                    for review in retained_reviews
                    if github.immutable_database_id(review) == checkpoint.hosted_review_id
                ]
                if len(matching_reviews) > 1:
                    raise SqliteRecordsRepairError("retained Hosted evidence has duplicate exact provider reviews")
                if matching_reviews:
                    retained = _retained_hosted_attempt_import(
                        records,
                        repo=repo,
                        pr_number=pr_number,
                        checkpoint=checkpoint,
                        attempt=attempt,
                        hosted_payload=hosted_payload,
                    )
                    return retained, _provider_id(checkpoint, retained)

            # Validate the supplied capture with the canonical importer on a
            # snapshot, then compare it to the automatic attempt's existing
            # projection before linking any public origin or archive rows.
            with tempfile.TemporaryDirectory(prefix="pr-review-attempt-repair-") as scratch:
                probe = _clone_records(records, Path(scratch) / "records.sqlite3")
                if checkpoint.type == "CLI" and checkpoint.run_id is not None:
                    # The legacy capture is the immutable provider evidence.
                    # Do not let a later source correction replace its decision
                    # projection while the scratch importer validates it.
                    with contextlib.closing(sqlite3.connect(probe.path)) as connection:
                        connection.execute(
                            "DELETE FROM source_decision_corrections WHERE run_id = ?",
                            (checkpoint.run_id,),
                        )
                        connection.commit()
                imported = import_checkpoint(probe)
                probe_history = probe.history(pr_number)
            expected_run = next(
                (item for item in probe_history["runs"] if item["run_id"] == imported["run_id"]),
                None,
            )
            stored_history = records.history(pr_number)
            stored_run = next(
                (item for item in stored_history["runs"] if item["run_id"] == attempt["run_id"]),
                None,
            )
            if expected_run is None or stored_run is None:
                raise SqliteRecordsRepairError("matching attempt has no verifiable completed source run")

            def observation_projection(
                history: Mapping[str, Any], run_id: str, *, original: bool = False
            ) -> dict[str, tuple[Any, ...]]:
                routes = {item["route_id"]: item for item in history["routes"]}
                original_decisions = {
                    item["finding_id"]: item
                    for item in history["decisions"]
                    if item["run_id"] == run_id and item["scope"] == "source"
                }
                return {
                    item["source_finding_key"]: (
                        original_decisions[item["finding_id"]]["decision"]
                        if original and item["finding_id"] in original_decisions
                        else item["disposition"],
                        original_decisions[item["finding_id"]]["target_pr"]
                        if original and item["finding_id"] in original_decisions
                        else routes.get(item["route_id"], {}).get("target_pr")
                        if item["route_id"] is not None
                        else None,
                    )
                    for item in history["findings"]
                    if item["run_id"] == run_id
                }

            def decision_projection(
                history: Mapping[str, Any], run_id: str, *, corrected: bool = True
            ) -> dict[str, tuple[Any, ...]]:
                keys = {
                    item["finding_id"]: item["source_finding_key"]
                    for item in history["findings"]
                    if item["run_id"] == run_id
                }
                result = {
                    keys[item["finding_id"]]: (item["decision"], item["target_pr"], item["reason"])
                    for item in history["decisions"]
                    if item["run_id"] == run_id and item["scope"] == "source" and item["finding_id"] in keys
                }
                if corrected:
                    for correction in history["corrections"]:
                        if correction["run_id"] == run_id and correction["finding_id"] in keys:
                            result[keys[correction["finding_id"]]] = (
                                correction["decision"],
                                correction["target_pr"],
                                correction["reason"],
                            )
                return result

            def decision_target_projection(history: Mapping[str, Any], run_id: str) -> dict[str, tuple[Any, ...]]:
                keys = {
                    item["finding_id"]: item["source_finding_key"]
                    for item in history["findings"]
                    if item["run_id"] == run_id
                }
                routes = {item["route_id"]: item for item in history["routes"]}
                result: dict[str, tuple[Any, ...]] = {}
                for decision in history["decisions"]:
                    if (
                        decision["run_id"] != run_id
                        or decision["scope"] != "source"
                        or decision["finding_id"] not in keys
                    ):
                        continue
                    route_id = decision["route_id"]
                    route = routes.get(route_id) if route_id is not None else None
                    result[keys[decision["finding_id"]]] = (
                        decision["target_pr"],
                        route["target_pr"] if route is not None else None,
                        route_id is None or route is not None,
                    )
                return result

            expected_observations = observation_projection(probe_history, imported["run_id"])
            expected_decisions = decision_projection(probe_history, imported["run_id"], corrected=False)
            original_observations = observation_projection(stored_history, attempt["run_id"], original=True)
            original_decisions = decision_projection(stored_history, attempt["run_id"], corrected=False)
            current_observations = observation_projection(stored_history, attempt["run_id"])
            current_decisions = decision_projection(stored_history, attempt["run_id"])
            stored_decision_targets = decision_target_projection(stored_history, attempt["run_id"])
            current_counts = {
                "found": len(current_observations),
                "accepted": sum(item[0] == "accepted" for item in current_observations.values()),
                "routed": sum(item[0] == "routed" for item in current_observations.values()),
            }
            if (
                stored_run["source_pr"] != pr_number
                or stored_run["channel"] != expected_run["channel"]
                or stored_run["source_head"] != expected_run["source_head"]
                or attempt["candidate_sha"] != expected_run["source_head"]
                or stored_run["outcome"] != "completed"
                or not stored_run["attributable"]
                or stored_run["counts"] != current_counts
            ):
                raise SqliteRecordsRepairError(
                    "matching provider attempt conflicts with captured run identity or counts"
                )
            if (
                {key: value[0] for key, value in original_observations.items()}
                != {key: value[0] for key, value in expected_observations.items()}
                or {key: (value[0], value[2]) for key, value in original_decisions.items()}
                != {key: (value[0], value[2]) for key, value in expected_decisions.items()}
                or set(current_observations) != set(current_decisions)
                or any(
                    current_observations[key][0] != current_decisions[key][0]
                    for key in current_observations
                    if key in current_decisions
                )
                or any(
                    not route_present or decision_target != route_target
                    for decision_target, route_target, route_present in stored_decision_targets.values()
                )
            ):
                raise SqliteRecordsRepairError(
                    "matching provider attempt conflicts with captured finding or decision evidence"
                )
            imported["run_id"] = attempt["run_id"]
            imported["counts"] = stored_run["counts"]
            imported["idempotent_replay"] = True
        else:
            imported = import_checkpoint(records)
    except evidence.CaptureUnavailable as exc:
        raise MissingHistoricalEvidenceError(str(exc)) from exc
    except (sqlite_provider_imports.ProviderImportError, ReviewRecordsError, evidence.EvidenceError) as exc:
        raise SqliteRecordsRepairError(str(exc)) from exc
    if not isinstance(imported, dict) or not isinstance(imported.get("run_id"), str):
        raise SqliteRecordsRepairError("canonical provider importer returned no exact structured run identity")
    if not isinstance(imported.get("counts"), dict):
        raise SqliteRecordsRepairError("canonical provider importer returned no validated finding counts")
    return imported, _provider_id(checkpoint, imported)


def _retained_hosted_attempt_import(
    records: SqliteReviewRecords,
    *,
    repo: str,
    pr_number: int,
    checkpoint: evidence.Checkpoint,
    attempt: Mapping[str, Any],
    hosted_payload: dict[str, Any] | None,
) -> dict[str, Any]:
    """Link a synced Hosted run from its immutable SQLite evidence and decisions."""

    review_id = checkpoint.hosted_review_id
    if review_id is None or checkpoint.comment_id is None or hosted_payload is None:
        raise SqliteRecordsRepairError("retained Hosted evidence lacks an exact public checkpoint identity")

    try:
        pull_request = sqlite_hosted_capture._complete_pull_request(hosted_payload, pr_number)
    except (sqlite_hosted_capture.HostedCaptureError, KeyError, TypeError, ValueError) as exc:
        raise SqliteRecordsRepairError(f"complete Hosted history is unavailable: {exc}") from exc
    checkpoint_matches: list[evidence.Checkpoint] = []
    raw_checkpoint_comments: list[dict[str, Any]] = []
    for item in pull_request["comments"]["nodes"]:
        if github.immutable_database_id(item) != checkpoint.comment_id:
            continue
        normalized = {
            "id": checkpoint.comment_id,
            "body": item.get("body"),
            "created_at": item.get("createdAt"),
            "updated_at": item.get("updatedAt"),
            "author_login": (item.get("author") or {}).get("login"),
        }
        parsed, _ = evidence.parse_checkpoint_comments([normalized])
        checkpoint_matches.extend(parsed)
        raw_checkpoint_comments.append(item)
    if (
        len(raw_checkpoint_comments) != 1
        or len(checkpoint_matches) != 1
        or sqlite_provider_imports._checkpoint_fingerprint(checkpoint_matches[0])
        != sqlite_provider_imports._checkpoint_fingerprint(checkpoint)
    ):
        raise SqliteRecordsRepairError("Hosted checkpoint does not exactly match one public pull-request comment")

    attempt_id = attempt.get("attempt_id")
    if not isinstance(attempt_id, str):
        raise SqliteRecordsRepairError("matching Hosted attempt has no exact attempt identity")
    stored_attempt = records.attempt(attempt_id)
    if (
        stored_attempt["source_pr"] != pr_number
        or stored_attempt["channel"] != "hosted"
        or stored_attempt["state"] != "completed"
        or stored_attempt["run_id"] != attempt_id
        or stored_attempt["candidate_sha"] != checkpoint.reviewed_sha
        and not (
            isinstance(stored_attempt["candidate_sha"], str)
            and isinstance(checkpoint.reviewed_sha, str)
            and stored_attempt["candidate_sha"].casefold().startswith(checkpoint.reviewed_sha.casefold())
        )
    ):
        raise SqliteRecordsRepairError("retained Hosted attempt does not match the checkpoint head and completion")

    artifacts = records.attempt_artifacts(attempt_id)
    if set(artifacts) != {"hosted_review", "hosted_comments", "metadata"}:
        raise SqliteRecordsRepairError("completed Hosted attempt lacks its exact retained review evidence")
    try:
        reviews = json.loads(artifacts["hosted_review"])
        archived_comments = json.loads(artifacts["hosted_comments"])
        metadata = json.loads(artifacts["metadata"])
    except (TypeError, ValueError, json.JSONDecodeError) as exc:
        raise SqliteRecordsRepairError("completed Hosted attempt has malformed retained review evidence") from exc
    if (
        not isinstance(reviews, list)
        or any(not isinstance(item, dict) for item in reviews)
        or not isinstance(archived_comments, dict)
        or not isinstance(archived_comments.get("comments"), list)
        or not isinstance(archived_comments.get("review_threads"), list)
        or not isinstance(metadata, dict)
        or metadata.get("state") != "completed"
        or metadata.get("attributable") is not True
        or not isinstance(metadata.get("repository"), str)
        or metadata["repository"].casefold() != repo.casefold()
        or metadata.get("pull_request") != pr_number
        or metadata.get("head_sha") != attempt.get("candidate_sha")
        or str(metadata.get("response_id")) != str(review_id)
        or str(metadata.get("trigger_id")) != attempt.get("trigger_id")
        or attempt.get("provider_review_id") != str(review_id)
        or attempt.get("candidate_sha") != stored_attempt["candidate_sha"]
        or not isinstance(checkpoint.reviewed_sha, str)
        or not stored_attempt["candidate_sha"].casefold().startswith(checkpoint.reviewed_sha.casefold())
    ):
        raise SqliteRecordsRepairError("retained Hosted evidence conflicts with its immutable attempt identity")

    matching_reviews = [review for review in reviews if github.immutable_database_id(review) == review_id]
    if len(matching_reviews) != 1:
        raise SqliteRecordsRepairError("retained Hosted evidence has no unique exact provider review")
    review = matching_reviews[0]
    author = review.get("author")
    commit = review.get("commit")
    review_head = commit.get("oid") if isinstance(commit, dict) else None
    if (
        not github.is_coderabbit_login(author.get("login") if isinstance(author, dict) else None)
        or review.get("state") in {"PENDING", "DISMISSED"}
        or not isinstance(review.get("submittedAt"), str)
        or review_head != stored_attempt["candidate_sha"]
    ):
        raise SqliteRecordsRepairError("retained Hosted provider review does not prove the exact completed head")

    archived_finding_keys: set[str] = set()
    try:
        for thread in archived_comments["review_threads"]:
            if not isinstance(thread, dict):
                raise sqlite_hosted_capture.HostedCaptureError("retained Hosted thread is malformed")
            comments = thread.get("comments")
            nodes = comments.get("nodes") if isinstance(comments, dict) else None
            if not isinstance(nodes, list):
                raise sqlite_hosted_capture.HostedCaptureError("retained Hosted thread comments are malformed")
            if not nodes:
                continue
            comment = nodes[0]
            author = comment.get("author") if isinstance(comment, dict) else None
            if not github.is_coderabbit_login(author.get("login") if isinstance(author, dict) else None):
                continue
            comment_id = github.immutable_database_id(comment)
            body = comment.get("body")
            for finding in sqlite_hosted_capture._hosted_comment_finding_segments(comment_id, body):
                key = finding["key"]
                if key in archived_finding_keys:
                    raise sqlite_hosted_capture.HostedCaptureError("retained Hosted finding identity is duplicated")
                archived_finding_keys.add(key)
    except (sqlite_hosted_capture.HostedCaptureError, TypeError, ValueError) as exc:
        raise SqliteRecordsRepairError(f"retained Hosted finding evidence is invalid: {exc}") from exc

    review_body = review.get("body")
    if isinstance(review_body, str):
        try:
            outside_diff, duplicate = evidence.summary_action_counts(review_body)
            has_sections = evidence.has_summary_action_sections(review_body)
        except evidence.EvidenceError as exc:
            raise SqliteRecordsRepairError(f"retained Hosted summary evidence is invalid: {exc}") from exc
        if has_sections:
            for kind, count in (("outside_diff", outside_diff), ("duplicate", duplicate)):
                archived_finding_keys.update(
                    f"hosted-summary:review:{review_id}:{kind}:{ordinal}" for ordinal in range(1, count + 1)
                )

    history = records.history(pr_number)
    runs = [run for run in history["runs"] if run["run_id"] == attempt_id]
    if len(runs) != 1:
        raise SqliteRecordsRepairError("matching Hosted attempt has no unique retained source run")
    stored_run = runs[0]
    if (
        stored_run["source_pr"] != pr_number
        or stored_run["channel"] != "hosted"
        or stored_run["outcome"] != "completed"
        or stored_run["attributable"] is not True
        or stored_run["finalized"] is not True
        or stored_run["source_head"] != stored_attempt["candidate_sha"]
    ):
        raise SqliteRecordsRepairError("matching Hosted source run is incomplete or conflicts with its attempt")

    stored_findings = [item for item in history["findings"] if item["run_id"] == attempt_id]
    finding_keys = [item["source_finding_key"] for item in stored_findings]
    if len(finding_keys) != len(set(finding_keys)) or set(finding_keys) != archived_finding_keys:
        raise SqliteRecordsRepairError("retained Hosted findings do not match the exact archived provider findings")
    finding_ids = {item["finding_id"]: item["source_finding_key"] for item in stored_findings}
    source_decisions = [
        item for item in history["decisions"] if item["run_id"] == attempt_id and item["scope"] == "source"
    ]
    decisions_by_key: dict[str, str] = {}
    for decision in source_decisions:
        key = finding_ids.get(decision["finding_id"])
        if key is None or key in decisions_by_key:
            raise SqliteRecordsRepairError("retained Hosted decisions do not map one-to-one to findings")
        decisions_by_key[key] = decision["decision"]
    if set(decisions_by_key) != archived_finding_keys:
        raise SqliteRecordsRepairError("retained Hosted source decisions are incomplete")
    observed_counts = {
        "found": len(archived_finding_keys),
        "accepted": sum(decision == "accepted" for decision in decisions_by_key.values()),
        "routed": sum(decision == "routed" for decision in decisions_by_key.values()),
    }
    if (
        checkpoint.raw_found != observed_counts["found"]
        or checkpoint.accepted != observed_counts["accepted"]
        or checkpoint.routed is not None
        and checkpoint.routed != observed_counts["routed"]
    ):
        raise SqliteRecordsRepairError("Hosted checkpoint counts do not match retained source findings and decisions")

    imported_artifacts, archive_reused = _existing_hosted_checkpoint_archive(
        records,
        repo=repo,
        pr_number=pr_number,
        run_id=attempt_id,
        review_id=review_id,
        checkpoint=checkpoint,
    )
    if imported_artifacts is None:
        imported_artifacts = {
            "hosted_review": artifacts["hosted_review"],
            "hosted_comments": artifacts["hosted_comments"],
            "metadata": _artifact_text(
                {
                    "capture_format": "firemud-hosted-synced-attempt/v1",
                    "repository": repo.casefold(),
                    "pull_request": pr_number,
                    "review_id": review_id,
                    "checkpoint": checkpoint.as_json(),
                    "checkpoint_fields": dataclasses.asdict(checkpoint),
                    "checkpoint_fingerprint": sqlite_provider_imports._checkpoint_fingerprint(checkpoint),
                    "retained_attempt_id": attempt_id,
                }
            ),
        }
    sqlite_provider_imports._validate_archive_artifacts(imported_artifacts)
    return {
        "run_id": attempt_id,
        "counts": stored_run["counts"],
        "provider_id": f"review:{review_id}",
        "archive_artifacts": imported_artifacts,
        "idempotent_replay": True,
        "_verified_archive_reuse": archive_reused,
    }


def _existing_hosted_checkpoint_archive(
    records: SqliteReviewRecords,
    *,
    repo: str,
    pr_number: int,
    run_id: str,
    review_id: int,
    checkpoint: evidence.Checkpoint,
) -> tuple[dict[str, str] | None, bool]:
    """Reuse only a valid immutable archive already linked to this checkpoint."""

    fingerprint = sqlite_provider_imports._checkpoint_fingerprint(checkpoint)
    try:
        with contextlib.closing(records._connect(read_only=True)) as connection:
            records._require_compatible(connection)
            origin = connection.execute(
                "SELECT repository, source_pr, channel, provider_id, checkpoint_id, checkpoint_fingerprint "
                "FROM provider_origins WHERE run_id = ?",
                (run_id,),
            ).fetchone()
            rows = connection.execute(
                "SELECT kind, content, source_sha256, redactions FROM imported_artifacts "
                "WHERE run_id = ? ORDER BY kind",
                (run_id,),
            ).fetchall()
            if origin is None:
                if rows:
                    raise SqliteRecordsRepairError("retained Hosted archive exists without its exact checkpoint origin")
                return None, False
            expected_origin = (
                repo.casefold(),
                pr_number,
                "hosted",
                f"review:{review_id}",
                checkpoint.comment_id,
                fingerprint,
            )
            if origin != expected_origin:
                raise SqliteRecordsRepairError("retained Hosted archive is linked to a different checkpoint identity")
            if not rows:
                # Origin linking commits before artifact archiving. Exact origin
                # identity with no artifacts is the documented resumable state.
                return None, False
            if not records._source_checkpoint_matches(connection, run_id, fingerprint, checkpoint):
                raise SqliteRecordsRepairError("retained Hosted archive does not prove the exact checkpoint")
            if {row[0] for row in rows} != {"hosted_review", "hosted_comments", "metadata"}:
                raise SqliteRecordsRepairError("linked Hosted checkpoint has incomplete imported archive evidence")
            archived: dict[str, str] = {}
            for kind, content, source_sha256, redactions in rows:
                try:
                    sanitized, stored_digest, detected_redactions = sqlite_review_records._archive_artifact(
                        kind, content
                    )
                except ReviewRecordsError as exc:
                    raise SqliteRecordsRepairError(f"linked Hosted archive artifact {kind} is invalid") from exc
                if (
                    sanitized != content
                    or detected_redactions != 0
                    or not isinstance(source_sha256, str)
                    or not re.fullmatch(r"[0-9a-f]{64}", source_sha256)
                    or isinstance(redactions, bool)
                    or not isinstance(redactions, int)
                    or redactions < 0
                    or (redactions == 0 and source_sha256 != stored_digest)
                ):
                    raise SqliteRecordsRepairError(
                        f"linked Hosted archive artifact {kind} has invalid sanitized content or receipt"
                    )
                archived[kind] = content
    except (sqlite3.DatabaseError, ReviewRecordsError, OSError) as exc:
        raise SqliteRecordsRepairError(f"cannot validate existing Hosted archive: {exc}") from exc

    try:
        metadata = json.loads(archived["metadata"])
        review = json.loads(archived["hosted_review"])
        comments = json.loads(archived["hosted_comments"])
    except (TypeError, ValueError, json.JSONDecodeError) as exc:
        raise SqliteRecordsRepairError("linked Hosted checkpoint has malformed imported archive evidence") from exc
    capture_format = metadata.get("capture_format") if isinstance(metadata, dict) else None
    if capture_format == "firemud-hosted-synced-attempt/v1":
        if not isinstance(review, list) or any(not isinstance(item, dict) for item in review):
            raise SqliteRecordsRepairError("linked Hosted archive has malformed retained review evidence")
        matching_reviews = [item for item in review if github.immutable_database_id(item) == review_id]
        review = matching_reviews[0] if len(matching_reviews) == 1 else None
    if (
        not isinstance(metadata, dict)
        or capture_format not in {"firemud-hosted-capture/v1", "firemud-hosted-synced-attempt/v1"}
        or metadata.get("repository") != repo.casefold()
        or metadata.get("pull_request") != pr_number
        or metadata.get("review_id") != review_id
        or not isinstance(review, dict)
        or github.immutable_database_id(review) != review_id
        or not isinstance(comments, dict)
    ):
        raise SqliteRecordsRepairError("linked Hosted archive conflicts with its exact provider review")
    review_head = review.get("commit_id")
    if review_head is None and isinstance(review.get("commit"), dict):
        review_head = review["commit"].get("oid")
    if review_head != checkpoint.reviewed_sha and not (
        isinstance(review_head, str)
        and isinstance(checkpoint.reviewed_sha, str)
        and review_head.casefold().startswith(checkpoint.reviewed_sha.casefold())
    ):
        raise SqliteRecordsRepairError("linked Hosted archive does not prove the checkpoint review head")
    sqlite_provider_imports._validate_archive_artifacts(archived)
    return archived, True


def _bounded_capture_text(directory: Path, name: str, maximum: int, *, required: bool = False) -> str | None:
    path = evidence._contained_file(directory, name, required=required)
    if path is None:
        return None
    try:
        content = path.read_bytes()
    except OSError as exc:
        raise SqliteRecordsRepairError(f"cannot read validated capture artifact {name}") from exc
    if len(content) > maximum:
        raise SqliteRecordsRepairError(f"capture artifact {name} exceeds its retained size limit")
    try:
        return content.decode("utf-8")
    except UnicodeDecodeError as exc:
        raise SqliteRecordsRepairError(f"capture artifact {name} is not valid UTF-8") from exc


def _artifact_text(value: Any) -> str:
    return json.dumps(value, ensure_ascii=True, sort_keys=True, separators=(",", ":"), allow_nan=False)


def _capture_artifacts(
    *,
    checkpoint: evidence.Checkpoint,
    imported: Mapping[str, Any],
) -> dict[str, str]:
    """Collect the validated source capture as bounded imported artifacts."""

    supplied = imported.get("archive_artifacts")
    if not isinstance(supplied, Mapping):
        raise SqliteRecordsRepairError("canonical importer omitted archive artifacts")
    if any(not isinstance(kind, str) or not isinstance(content, str) for kind, content in supplied.items()):
        raise SqliteRecordsRepairError("canonical importer returned malformed archive artifacts")
    artifacts = dict(supplied)
    provider_id = _provider_id(checkpoint, dict(imported))
    allowed = (
        {"cli_events", "cli_raw_output", "cli_diagnostic", "metadata"}
        if checkpoint.type == "CLI"
        else {"hosted_review", "hosted_comments", "metadata"}
    )
    if (
        not artifacts
        or set(artifacts) - allowed
        or "metadata" not in artifacts
        or (checkpoint.type == "CLI" and "cli_events" not in artifacts)
        or (checkpoint.type == "Hosted" and "hosted_comments" not in artifacts)
        or (checkpoint.type == "Hosted" and provider_id.startswith("review:") and "hosted_review" not in artifacts)
    ):
        raise SqliteRecordsRepairError("canonical importer returned incomplete provider archive artifacts")
    return artifacts


def _archive_artifacts(records: SqliteReviewRecords, run_id: str, artifacts: Mapping[str, str]) -> Any:
    try:
        return records.archive_imported_artifacts(run_id, artifacts)
    except (ReviewRecordsError, sqlite3.DatabaseError, OSError) as exc:
        raise SqliteRecordsRepairError(str(exc)) from exc


def _link_origin(
    records: SqliteReviewRecords,
    *,
    repo: str,
    pr_number: int,
    checkpoint: evidence.Checkpoint,
    provider_id: str,
    checkpoint_fingerprint: str,
    run_id: str,
) -> Any:
    try:
        return records.link_provider_origin(
            repository=repo.casefold(),
            source_pr=pr_number,
            channel="hosted" if checkpoint.type == "Hosted" else "cli",
            provider_id=provider_id,
            checkpoint_id=checkpoint.comment_id,
            checkpoint_fingerprint=checkpoint_fingerprint,
            run_id=run_id,
        )
    except (ReviewRecordsError, sqlite3.DatabaseError, OSError) as exc:
        raise SqliteRecordsRepairError(str(exc)) from exc


def _preview(
    records: SqliteReviewRecords,
    *,
    repo: str,
    pr_number: int,
    checkpoints: Sequence[evidence.Checkpoint],
    actor: str,
    common: Path | None,
    scope: str,
    coverage_limits: Sequence[str],
    summary_dispositions: Sequence[Any] | None,
    hosted_payload: dict[str, Any] | None,
) -> list[dict[str, Any]]:
    plan: list[dict[str, Any]] = []
    provider_origins: dict[str, int] = {}
    with tempfile.TemporaryDirectory(prefix="pr-review-records-repair-") as scratch:
        snapshot_path = Path(scratch) / "records.sqlite3"
        clone = _clone_records(records, snapshot_path)
        for checkpoint in checkpoints:
            imported, provider_id = _import_one(
                clone,
                repo=repo,
                pr_number=pr_number,
                checkpoint=checkpoint,
                actor=actor,
                common=common,
                scope=scope,
                coverage_limits=coverage_limits,
                summary_dispositions=summary_dispositions,
                hosted_payload=hosted_payload,
            )
            other_comment = provider_origins.get(provider_id)
            if other_comment is not None and other_comment != checkpoint.comment_id:
                raise SqliteRecordsRepairError(
                    f"provider identity {provider_id} is attributed by multiple checkpoint comments "
                    f"({other_comment}, {checkpoint.comment_id})"
                )
            provider_origins[provider_id] = checkpoint.comment_id or 0
            fingerprint = sqlite_provider_imports._checkpoint_fingerprint(checkpoint)
            _link_origin(
                clone,
                repo=repo,
                pr_number=pr_number,
                checkpoint=checkpoint,
                provider_id=provider_id,
                checkpoint_fingerprint=fingerprint,
                run_id=imported["run_id"],
            )
            artifacts = _capture_artifacts(
                checkpoint=checkpoint,
                imported=imported,
            )
            if not imported.get("_verified_archive_reuse"):
                _archive_artifacts(clone, imported["run_id"], artifacts)
            counts = imported["counts"]
            plan.append(
                {
                    "checkpoint_id": checkpoint.comment_id,
                    "checkpoint_fingerprint": fingerprint,
                    "channel": "hosted" if checkpoint.type == "Hosted" else "cli",
                    "provider_id": provider_id,
                    "run_id": imported["run_id"],
                    "counts": {
                        "found": counts.get("found"),
                        "accepted": counts.get("accepted"),
                        "routed": counts.get("routed"),
                    },
                    "artifact_kinds": sorted(artifacts),
                    "artifact_sha256": {
                        kind: hashlib.sha256(content.encode("utf-8")).hexdigest()
                        for kind, content in sorted(artifacts.items())
                    },
                    "action": "already_imported" if imported.get("idempotent_replay") else "would_import",
                }
            )
    return plan


def repair_provider_checkpoints(
    records: SqliteReviewRecords,
    *,
    repo: str,
    pr_number: int,
    checkpoints: Sequence[evidence.Checkpoint],
    actor: str,
    common: Path | None = None,
    scope: str = "broad",
    coverage_limits: Sequence[str] = (),
    summary_dispositions: Sequence[Any] | None = None,
    hosted_payload: dict[str, Any] | None = None,
    dry_run: bool = True,
) -> dict[str, Any]:
    """Preview or backfill parsed CLI/Hosted checkpoints and their captures.

    The entire supplied batch is first imported into a disposable clone. Any
    invalid capture, exact-identity conflict, duplicate provider origin, or
    unavailable origin-link API stops before a live database write.
    Applied batches are replay-safe; if a process stops partway through, rerun
    the same batch to complete the remaining exact identities.

    This updates structured records only. It does not establish taper credit,
    review completion policy, current-head proof, or merge readiness.
    """

    if not isinstance(dry_run, bool):
        raise SqliteRecordsRepairError("dry_run must be boolean")
    if hosted_payload is not None and not isinstance(hosted_payload, dict):
        raise SqliteRecordsRepairError("Hosted checkpoint payload must be a complete pull-request object")
    repo, normalized = _validate_request(
        records,
        repo=repo,
        pr_number=pr_number,
        checkpoints=checkpoints,
        actor=actor,
        scope=scope,
        coverage_limits=coverage_limits,
    )
    if not normalized:
        return {
            "api_version": _API_VERSION,
            "status": "preview" if dry_run else "complete",
            "dry_run": dry_run,
            "repository": repo.casefold(),
            "source_pr": pr_number,
            "items": [],
        }
    _require_origin_link_api(records)
    plan = _preview(
        records,
        repo=repo,
        pr_number=pr_number,
        checkpoints=normalized,
        actor=actor,
        common=common,
        scope=scope,
        coverage_limits=coverage_limits,
        summary_dispositions=summary_dispositions,
        hosted_payload=hosted_payload,
    )
    if dry_run:
        return {
            "api_version": _API_VERSION,
            "status": "preview",
            "dry_run": True,
            "repository": repo.casefold(),
            "source_pr": pr_number,
            "items": plan,
        }

    applied: list[dict[str, Any]] = []
    for checkpoint, expected in zip(normalized, plan, strict=True):
        try:
            imported, provider_id = _import_one(
                records,
                repo=repo,
                pr_number=pr_number,
                checkpoint=checkpoint,
                actor=actor,
                common=common,
                scope=scope,
                coverage_limits=coverage_limits,
                summary_dispositions=summary_dispositions,
                hosted_payload=hosted_payload,
            )
            fingerprint = sqlite_provider_imports._checkpoint_fingerprint(checkpoint)
            artifacts = _capture_artifacts(
                checkpoint=checkpoint,
                imported=imported,
            )
            actual_counts = {
                "found": imported["counts"].get("found"),
                "accepted": imported["counts"].get("accepted"),
                "routed": imported["counts"].get("routed"),
            }
            actual_artifact_kinds = sorted(artifacts)
            actual_artifact_sha256 = {
                kind: hashlib.sha256(content.encode("utf-8")).hexdigest() for kind, content in sorted(artifacts.items())
            }
            item = {
                **expected,
                "provider_id": provider_id,
                "run_id": imported["run_id"],
                "counts": actual_counts,
                "checkpoint_fingerprint": fingerprint,
                "artifact_kinds": actual_artifact_kinds,
                "artifact_sha256": actual_artifact_sha256,
                "action": "already_imported" if imported.get("idempotent_replay") else "imported",
                "origin_linked": False,
                "artifacts_archived": False,
            }
            applied.append(item)
            if (
                provider_id != expected["provider_id"]
                or imported["run_id"] != expected["run_id"]
                or actual_counts != expected["counts"]
                or fingerprint != expected["checkpoint_fingerprint"]
                or actual_artifact_kinds != expected["artifact_kinds"]
                or actual_artifact_sha256 != expected["artifact_sha256"]
            ):
                item["action"] = "drifted"
                return {
                    "api_version": _API_VERSION,
                    "status": "partial",
                    "dry_run": False,
                    "repository": repo.casefold(),
                    "source_pr": pr_number,
                    "items": applied,
                    "error": "source evidence changed after preflight; exact imported run is retained for audit",
                }
            _link_origin(
                records,
                repo=repo,
                pr_number=pr_number,
                checkpoint=checkpoint,
                provider_id=provider_id,
                checkpoint_fingerprint=fingerprint,
                run_id=imported["run_id"],
            )
            item["origin_linked"] = True
            if not imported.get("_verified_archive_reuse"):
                _archive_artifacts(records, imported["run_id"], artifacts)
            item["artifacts_archived"] = True
        except (OSError, sqlite3.DatabaseError) as exc:
            return {
                "api_version": _API_VERSION,
                "status": "partial",
                "dry_run": False,
                "repository": repo.casefold(),
                "source_pr": pr_number,
                "items": applied,
                "error": _partial_diagnostic(exc),
            }
        except SqliteRecordsRepairError as exc:
            return {
                "api_version": _API_VERSION,
                "status": "partial",
                "dry_run": False,
                "repository": repo.casefold(),
                "source_pr": pr_number,
                "items": applied,
                "error": str(exc),
            }
    return {
        "api_version": _API_VERSION,
        "status": "complete",
        "dry_run": False,
        "repository": repo.casefold(),
        "source_pr": pr_number,
        "items": applied,
    }


def _ambiguous_reply_proof(
    records: SqliteReviewRecords,
    *,
    repo: str,
    pr_number: int,
    attempt_id: str,
    response_id: int,
    hosted_payload: dict[str, Any],
) -> dict[str, Any]:
    """Validate one exact stored ambiguous reply against complete public history."""

    if not isinstance(repo, str) or not _REPOSITORY.fullmatch(repo):
        raise SqliteRecordsRepairError("repository must be an exact owner/name pair")
    if not isinstance(attempt_id, str) or not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._:-]{0,99}", attempt_id):
        raise SqliteRecordsRepairError("ambiguous reply recovery requires one exact attempt ID")
    if type(response_id) is not int or response_id <= 0:
        raise SqliteRecordsRepairError("ambiguous reply recovery requires one exact response ID")
    try:
        pull = sqlite_hosted_capture._complete_pull_request(hosted_payload, pr_number)
    except (sqlite_hosted_capture.HostedCaptureError, KeyError, TypeError, ValueError) as exc:
        raise SqliteRecordsRepairError(f"complete Hosted history is unavailable: {exc}") from exc

    attempt_matches = [
        item
        for item in records.attempt_history(pr_number)
        if item.get("attempt_id") == attempt_id and item.get("channel") == "hosted"
    ]
    if len(attempt_matches) != 1:
        raise SqliteRecordsRepairError("exact Hosted attempt is missing or duplicated")
    attempt = records.attempt(attempt_id)
    history_attempt = attempt_matches[0]
    metadata = attempt.get("metadata")
    stored_response_id = history_attempt.get("provider_review_id")
    trigger_id_text = history_attempt.get("trigger_id")
    candidate_sha = attempt.get("candidate_sha")
    if (
        attempt.get("source_pr") != pr_number
        or attempt.get("state") != "ambiguous"
        or attempt.get("run_id") is not None
        or history_attempt.get("state") != "ambiguous"
        or history_attempt.get("run_id") is not None
        or stored_response_id != str(response_id)
        or not isinstance(trigger_id_text, str)
        or not trigger_id_text.isdecimal()
        or not isinstance(candidate_sha, str)
        or hosted.EXACT_SHA.fullmatch(candidate_sha) is None
        or not isinstance(metadata, dict)
        or metadata.get("request_kind") != "full"
        or not isinstance(metadata.get("repository"), str)
        or metadata["repository"].casefold() != repo.casefold()
    ):
        raise SqliteRecordsRepairError("stored attempt is not the exact unresolved Hosted response")
    anchor = metadata.get("anchor")
    if (
        not isinstance(anchor, dict)
        or anchor.get("pr") != pr_number
        or anchor.get("child_head") != candidate_sha
        or not isinstance(anchor.get("parent_identity"), str)
        or not anchor["parent_identity"]
        or not isinstance(anchor.get("parent_head"), str)
        or hosted.EXACT_SHA.fullmatch(anchor["parent_head"]) is None
        or not isinstance(anchor.get("merge_base"), str)
        or hosted.EXACT_SHA.fullmatch(anchor["merge_base"]) is None
        or not isinstance(anchor.get("patch_id"), str)
        or re.fullmatch(r"[0-9a-f]{64}", anchor["patch_id"]) is None
        or metadata.get("actual_base_ref") != anchor["parent_identity"]
        or metadata.get("actual_base_sha") != anchor["parent_head"]
    ):
        raise SqliteRecordsRepairError("stored attempt lacks its complete immutable candidate anchor")

    artifacts = records.attempt_artifacts(attempt_id)
    if set(artifacts) != {"hosted_comments", "hosted_review", "metadata"}:
        raise SqliteRecordsRepairError("ambiguous Hosted attempt lacks its complete original capture")
    try:
        archived_comments = json.loads(artifacts["hosted_comments"])
        archived_reviews = json.loads(artifacts["hosted_review"])
        capture = json.loads(artifacts["metadata"])
    except (TypeError, ValueError, json.JSONDecodeError) as exc:
        raise SqliteRecordsRepairError("ambiguous Hosted attempt capture is malformed") from exc
    record = capture.get("trigger_record") if isinstance(capture, dict) else None
    if (
        not isinstance(capture, dict)
        or capture.get("state") != "ambiguous"
        or capture.get("terminal") is not True
        or capture.get("attributable") is not False
        or not isinstance(capture.get("repository"), str)
        or capture["repository"].casefold() != repo.casefold()
        or capture.get("pull_request") != pr_number
        or capture.get("head_sha") != candidate_sha
        or capture.get("trigger_id") != int(trigger_id_text)
        or capture.get("response_id") != response_id
        or not isinstance(record, dict)
        or record.get("sqlite_attempt_id") != attempt_id
        or not isinstance(record.get("repository"), str)
        or record["repository"].casefold() != repo.casefold()
        or record.get("pr_number") != pr_number
        or record.get("head_sha") != candidate_sha
        or record.get("anchor") != anchor
        or not isinstance(archived_comments, dict)
        or not isinstance(archived_comments.get("comments"), list)
        or not isinstance(archived_comments.get("review_threads"), list)
        or not isinstance(archived_reviews, list)
        or archived_reviews
    ):
        raise SqliteRecordsRepairError("original Hosted capture conflicts with the immutable attempt")
    try:
        record = sqlite_hosted_capture._validate_trigger_record(record["repository"], pr_number, record)
    except sqlite_hosted_capture.HostedCaptureError as exc:
        raise SqliteRecordsRepairError(f"stored Hosted trigger identity is invalid: {exc}") from exc
    trigger_id = int(trigger_id_text)
    if record["trigger"].get("id") != trigger_id:
        raise SqliteRecordsRepairError("stored Hosted trigger differs from the ambiguous attempt")

    comments = pull["comments"]["nodes"]
    public_triggers = [item for item in comments if github.immutable_database_id(item) == trigger_id]
    public_responses = [item for item in comments if github.immutable_database_id(item) == response_id]
    captured_triggers = [
        item
        for item in archived_comments["comments"]
        if isinstance(item, dict) and github.immutable_database_id(item) == trigger_id
    ]
    captured_responses = [
        item
        for item in archived_comments["comments"]
        if isinstance(item, dict) and github.immutable_database_id(item) == response_id
    ]
    if (
        len(public_triggers) != 1
        or len(public_responses) != 1
        or len(captured_triggers) != 1
        or len(captured_responses) != 1
    ):
        raise SqliteRecordsRepairError("trigger or reply is missing or duplicated in retained/public history")
    public_trigger, captured_trigger = public_triggers[0], captured_triggers[0]
    public_response, captured_response = public_responses[0], captured_responses[0]
    if (
        public_trigger.get("body") != captured_trigger.get("body")
        or public_trigger.get("createdAt") != captured_trigger.get("createdAt")
        or public_trigger.get("url") != captured_trigger.get("url")
        or (public_trigger.get("author") or {}).get("login", "").casefold()
        != (captured_trigger.get("author") or {}).get("login", "").casefold()
        or public_response.get("body") != captured_response.get("body")
        or public_response.get("createdAt") != captured_response.get("createdAt")
        or public_response.get("updatedAt") != captured_response.get("updatedAt")
        or not github.is_coderabbit_login((public_response.get("author") or {}).get("login"))
    ):
        raise SqliteRecordsRepairError("public trigger/reply changed from the immutable terminal capture")

    attempt_observed_at = hosted.parse_timestamp(history_attempt.get("finished_at"))
    capture_observed_at = hosted.parse_timestamp(capture.get("observed_at"))
    response_created_at = hosted.parse_timestamp(captured_response.get("createdAt"))
    response_updated_at = hosted.parse_timestamp(captured_response.get("updatedAt"))
    if (
        attempt_observed_at is None
        or capture_observed_at != attempt_observed_at
        or response_created_at is None
        or response_updated_at is None
        or response_created_at > attempt_observed_at
        or response_updated_at < response_created_at
    ):
        raise SqliteRecordsRepairError("retained Hosted response timestamps conflict with the original observation")

    try:
        state = hosted.recover_ambiguous_reply_state(
            repo,
            pr_number,
            hosted_payload,
            record,
            attempt_id=attempt_id,
            response_id=response_id,
            records_reader=records,
            current_record_path=hosted.default_trigger_record_path(repo, pr_number, records.path.parent.parent),
        )
    except (KeyError, TypeError, ValueError) as exc:
        raise SqliteRecordsRepairError(f"Hosted reply recovery proof is unavailable: {exc}") from exc
    if (
        state.state != "completed"
        or state.terminal is not True
        or state.attributed is not True
        or state.trigger_comment_id != trigger_id
        or state.response_id != response_id
        or state.head_sha != candidate_sha
        or not isinstance(state.duration_seconds, int)
        or state.duration_seconds < 0
    ):
        raise SqliteRecordsRepairError("exact Hosted response does not prove one complete zero-result window")
    finished_at = captured_response.get("updatedAt")
    started_at = record["trigger"].get("created_at")
    if hosted.parse_timestamp(finished_at) is None or hosted.parse_timestamp(started_at) is None:
        raise SqliteRecordsRepairError("Hosted response or trigger has no valid immutable completion time")
    reviewed_file_count = metadata.get("reviewed_file_count")
    if type(reviewed_file_count) is not int or reviewed_file_count <= 0:
        raise SqliteRecordsRepairError("ambiguous Hosted attempt lacks its captured reviewed file count")

    window = sqlite_hosted_capture.archive_window(
        pull,
        record,
        finished_at=finished_at,
        response_id=response_id,
    )
    # The successful proof permits an old finding thread only when its
    # immutable creation predates this trigger and its exact addressed footer
    # edit falls inside this trigger's response window. Retain that proof thread.
    trigger_at = hosted.parse_timestamp(started_at)
    finished_dt = hosted.parse_timestamp(finished_at)
    assert trigger_at is not None and finished_dt is not None
    captured_thread_ids = {thread.get("id") for thread in window["review_threads"] if isinstance(thread, dict)}
    for thread in pull["reviewThreads"]["nodes"]:
        nodes = thread.get("comments", {}).get("nodes", [])
        has_in_window_edit = any(
            github.is_coderabbit_login((item.get("author") or {}).get("login"))
            and (created := hosted.parse_timestamp(item.get("createdAt"))) is not None
            and created <= trigger_at
            and (updated := hosted.parse_timestamp(item.get("updatedAt"))) is not None
            and trigger_at < updated <= finished_dt
            for item in nodes
        )
        if has_in_window_edit and thread.get("id") not in captured_thread_ids:
            window["review_threads"].append(thread)
            captured_thread_ids.add(thread.get("id"))

    proof = {
        "attempt_id": attempt_id,
        "trigger_id": trigger_id,
        "response_id": response_id,
        "candidate_sha": candidate_sha,
        "anchor": anchor,
        "response_created_at": public_response.get("createdAt"),
        "response_updated_at": finished_at,
        "duration_seconds": state.duration_seconds,
        "counts": {"found": 0, "accepted": 0, "routed": 0},
        "classifier": "hosted finished-reply complete-window proof",
    }
    checkpoint = evidence.Checkpoint(
        comment_id=response_id,
        created_at=public_response["createdAt"],
        type="Hosted",
        raw_found=0,
        accepted=0,
        reviewed_sha=candidate_sha,
        file_count=reviewed_file_count,
        correction=False,
        updated_at=finished_at,
        run_id=None,
        hosted_review_id=response_id,
        duration_seconds=state.duration_seconds,
        duration_invalid=False,
        author_login=(public_response.get("author") or {}).get("login"),
        routed=0,
    )
    checkpoint_fingerprint = sqlite_provider_imports._checkpoint_fingerprint(checkpoint)
    capture_metadata = {
        "capture_format": "firemud-hosted-ambiguous-reply-recovery/v1",
        "repository": repo.casefold(),
        "pull_request": pr_number,
        "checkpoint": checkpoint.as_json(),
        "checkpoint_fields": dataclasses.asdict(checkpoint),
        "checkpoint_fingerprint": checkpoint_fingerprint,
        "attempt_id": attempt_id,
        "head_sha": candidate_sha,
        "anchor": anchor,
        "trigger_id": trigger_id,
        "trigger_record": record,
        "response_id": response_id,
        "state": "completed",
        "terminal": True,
        "attributable": True,
        "observed_at": finished_at,
        "trigger_state": state.as_dict(),
        "zero_reply_proof": proof,
    }
    archive_artifacts = {
        "hosted_review": _artifact_text(window["reviews"]),
        "hosted_comments": _artifact_text({"comments": window["comments"], "review_threads": window["review_threads"]}),
        "metadata": _artifact_text(capture_metadata),
    }
    try:
        sqlite_provider_imports._validate_archive_artifacts(archive_artifacts)
    except sqlite_provider_imports.ProviderImportError as exc:
        raise SqliteRecordsRepairError(str(exc)) from exc
    fingerprint = checkpoint_fingerprint
    run_id = (
        "hosted-reply-"
        + hashlib.sha256(f"{repo.casefold()}\0{pr_number}\0{attempt_id}\0{response_id}".encode()).hexdigest()[:48]
    )
    return {
        "attempt": attempt,
        "attempt_artifacts": artifacts,
        "record": record,
        "state": state,
        "run_id": run_id,
        "provider_id": f"trigger:{trigger_id}",
        "checkpoint_id": response_id,
        "checkpoint_fingerprint": fingerprint,
        "started_at": started_at,
        "finished_at": finished_at,
        "reviewer": (public_response.get("author") or {}).get("login"),
        "artifacts": archive_artifacts,
    }


def _verify_ambiguous_reply_import(
    records: SqliteReviewRecords,
    *,
    repo: str,
    pr_number: int,
    proof: Mapping[str, Any],
) -> dict[str, Any]:
    history = records.history(pr_number)
    run_id = proof["run_id"]
    trigger_id = int(proof["provider_id"].split(":", 1)[1])
    runs = [item for item in history["runs"] if item.get("run_id") == run_id]
    origins = [
        item
        for item in history["provider_origins"]
        if item.get("repository") == repo.casefold()
        and item.get("source_pr") == pr_number
        and item.get("channel") == "hosted"
        and item.get("provider_id") == proof["provider_id"]
    ]
    artifacts = [item for item in history["imported_artifacts"] if item.get("run_id") == run_id]
    expected_artifacts = {
        kind: sqlite_review_records._archive_artifact(kind, content)[1:] for kind, content in proof["artifacts"].items()
    }
    actual_artifacts = {item["kind"]: (item["source_sha256"], item["redactions"]) for item in artifacts}
    attempts = [item for item in history["attempts"] if item.get("attempt_id") == proof["attempt"]["attempt_id"]]
    if len(attempts) != 1 or attempts[0].get("state") != "ambiguous" or attempts[0].get("run_id") is not None:
        raise SqliteRecordsRepairError("original ambiguous attempt changed during reply recovery")
    if proof["attempt_artifacts"] != records.attempt_artifacts(proof["attempt"]["attempt_id"]):
        raise SqliteRecordsRepairError("original ambiguous capture changed during reply recovery")
    if len(runs) != 1:
        raise SqliteRecordsRepairError("recovered Hosted source run is missing or duplicated")
    run = runs[0]
    if (
        run.get("source_pr") != pr_number
        or run.get("channel") != "hosted"
        or run.get("outcome") != "completed"
        or run.get("attributable") is not True
        or run.get("finalized") is not True
        or run.get("source_head") != proof["attempt"]["candidate_sha"]
        or run.get("started_at") != proof["started_at"]
        or run.get("finished_at") != proof["finished_at"]
        or run.get("counts") != {"found": 0, "accepted": 0, "routed": 0}
    ):
        raise SqliteRecordsRepairError("recovered source run does not reproduce the exact zero-result identity")
    if (
        len(origins) != 1
        or origins[0].get("run_id") != run_id
        or origins[0].get("checkpoint_id") != proof["checkpoint_id"]
        or origins[0].get("checkpoint_fingerprint") != proof["checkpoint_fingerprint"]
        or actual_artifacts != expected_artifacts
        or set(actual_artifacts) != set(proof["artifacts"])
    ):
        raise SqliteRecordsRepairError("recovered reply origin or archived artifacts do not match proof")
    linked = sqlite_hosted_capture._linked_ambiguous_reply_recovery(
        history,
        repo,
        pr_number,
        attempts[0],
        str(trigger_id),
    )
    if linked is None or linked.get("run_id") != run_id or linked.get("counts") != run["counts"]:
        raise SqliteRecordsRepairError("recovered reply is not visible through the canonical sync reader")
    return {"run": run, "origin": origins[0], "artifacts": sorted(actual_artifacts)}


def verify_ambiguous_reply_import_for_publication(
    records: SqliteReviewRecords,
    *,
    repo: str,
    pr_number: int,
    run_id: str,
) -> dict[str, Any]:
    """Re-prove the exact stored zero-reply import without provider reads.

    Publication may use only the deterministic import made by
    ``repair_ambiguous_hosted_reply``. This checks its original unresolved
    attempt, archived recovery proof, imported artifacts and immutable provider
    origin every time publication state is read or changed.
    """

    if not isinstance(records, SqliteReviewRecords):
        raise SqliteRecordsRepairError("reply publication requires a SqliteReviewRecords store")
    if not isinstance(repo, str) or not _REPOSITORY.fullmatch(repo):
        raise SqliteRecordsRepairError("reply publication requires an exact repository")
    if type(pr_number) is not int or pr_number <= 0:
        raise SqliteRecordsRepairError("reply publication requires an exact source PR")
    history = records.history(pr_number)
    runs = [item for item in history["runs"] if item.get("run_id") == run_id]
    if len(runs) != 1:
        raise SqliteRecordsRepairError("recovered Hosted source run is missing or duplicated")
    run = runs[0]
    if run.get("channel") != "hosted" or run.get("source_pr") != pr_number:
        raise SqliteRecordsRepairError("source run is not a recovered Hosted reply")

    imported = records.imported_run_artifacts(run_id)
    if set(imported) != {"hosted_comments", "hosted_review", "metadata"}:
        raise SqliteRecordsRepairError("recovered Hosted reply archive is incomplete")
    contents: dict[str, str] = {}
    for kind, item in imported.items():
        content = item.get("content")
        digest = item.get("source_sha256")
        redactions = item.get("redactions")
        if (
            not isinstance(content, str)
            or not isinstance(digest, str)
            or re.fullmatch(r"[0-9a-f]{64}", digest) is None
            or type(redactions) is not int
            or redactions < 0
        ):
            raise SqliteRecordsRepairError("recovered Hosted reply archive metadata is malformed")
        try:
            stored, stored_digest, stored_redactions = sqlite_review_records._archive_artifact(kind, content)
        except ReviewRecordsError as exc:
            raise SqliteRecordsRepairError("recovered Hosted reply archive is invalid") from exc
        if stored != content or (redactions == 0 and (stored_digest != digest or stored_redactions != redactions)):
            raise SqliteRecordsRepairError("recovered Hosted reply archive digest or redaction state conflicts")
        contents[kind] = content
    try:
        metadata = json.loads(contents["metadata"])
        archived_comments = json.loads(contents["hosted_comments"])
        sqlite_provider_imports._validate_archive_artifacts(contents)
    except (KeyError, TypeError, ValueError, json.JSONDecodeError, sqlite_provider_imports.ProviderImportError) as exc:
        raise SqliteRecordsRepairError("recovered Hosted reply archive is malformed") from exc
    if not isinstance(metadata, dict) or metadata.get("capture_format") != "firemud-hosted-ambiguous-reply-recovery/v1":
        raise SqliteRecordsRepairError("source run is not the canonical ambiguous-reply recovery import")

    attempt_id = metadata.get("attempt_id")
    response_id = metadata.get("response_id")
    trigger_id = metadata.get("trigger_id")
    head = metadata.get("head_sha")
    anchor = metadata.get("anchor")
    trigger_record = metadata.get("trigger_record")
    if (
        metadata.get("repository") != repo.casefold()
        or metadata.get("pull_request") != pr_number
        or metadata.get("state") != "completed"
        or metadata.get("terminal") is not True
        or metadata.get("attributable") is not True
        or not isinstance(attempt_id, str)
        or not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._:-]{0,99}", attempt_id)
        or type(response_id) is not int
        or response_id <= 0
        or type(trigger_id) is not int
        or trigger_id <= 0
        or not isinstance(head, str)
        or hosted.EXACT_SHA.fullmatch(head) is None
        or not isinstance(anchor, dict)
        or not isinstance(trigger_record, dict)
        or metadata.get("trigger_record", {}).get("sqlite_attempt_id") != attempt_id
        or trigger_record.get("anchor") != anchor
        or trigger_record.get("head_sha") != head
        or trigger_record.get("repository", "").casefold() != repo.casefold()
        or trigger_record.get("pr_number") != pr_number
        or (trigger_record.get("trigger") or {}).get("id") != trigger_id
    ):
        raise SqliteRecordsRepairError("recovered Hosted reply identity conflicts with its archive")
    expected_run_id = "hosted-reply-" + hashlib.sha256(
        f"{repo.casefold()}\0{pr_number}\0{attempt_id}\0{response_id}".encode()
    ).hexdigest()[:48]
    if run_id != expected_run_id:
        raise SqliteRecordsRepairError("source run ID is not the deterministic recovery identity")

    attempt = records.attempt(attempt_id)
    attempt_rows = [item for item in history["attempts"] if item.get("attempt_id") == attempt_id]
    if (
        len(attempt_rows) != 1
        or attempt.get("source_pr") != pr_number
        or attempt.get("channel") != "hosted"
        or attempt.get("state") != "ambiguous"
        or attempt.get("run_id") is not None
        or attempt.get("candidate_sha") != head
        or attempt_rows[0].get("state") != "ambiguous"
        or attempt_rows[0].get("run_id") is not None
        or attempt_rows[0].get("trigger_id") != str(trigger_id)
        or attempt_rows[0].get("provider_review_id") != str(response_id)
        or attempt_rows[0].get("candidate_sha") != head
    ):
        raise SqliteRecordsRepairError("original ambiguous attempt identity or terminal state changed")
    attempt_metadata = attempt.get("metadata")
    if (
        not isinstance(attempt_metadata, dict)
        or attempt_metadata.get("request_kind") != "full"
        or attempt_metadata.get("repository", "").casefold() != repo.casefold()
        or type(attempt_metadata.get("reviewed_file_count")) is not int
        or attempt_metadata["reviewed_file_count"] <= 0
        or attempt_metadata.get("anchor") != anchor
    ):
        raise SqliteRecordsRepairError("original Hosted request lacks its immutable scope metadata")
    original_artifacts = records.attempt_artifacts(attempt_id)
    if set(original_artifacts) != {"hosted_comments", "hosted_review", "metadata"}:
        raise SqliteRecordsRepairError("original ambiguous Hosted capture is incomplete")
    try:
        original_capture = json.loads(original_artifacts["metadata"])
        original_comments = json.loads(original_artifacts["hosted_comments"])
        original_reviews = json.loads(original_artifacts["hosted_review"])
    except (TypeError, ValueError, json.JSONDecodeError) as exc:
        raise SqliteRecordsRepairError("original ambiguous Hosted capture is malformed") from exc
    if (
        not isinstance(original_capture, dict)
        or original_capture.get("state") != "ambiguous"
        or original_capture.get("terminal") is not True
        or original_capture.get("attributable") is not False
        or original_capture.get("repository", "").casefold() != repo.casefold()
        or original_capture.get("pull_request") != pr_number
        or original_capture.get("head_sha") != head
        or original_capture.get("trigger_id") != trigger_id
        or original_capture.get("response_id") != response_id
        or original_capture.get("trigger_record") != trigger_record
        or original_capture.get("observed_at") != attempt_rows[0].get("finished_at")
        or not isinstance(original_comments, dict)
        or not isinstance(original_comments.get("comments"), list)
        or not isinstance(original_comments.get("review_threads"), list)
        or original_reviews
    ):
        raise SqliteRecordsRepairError("original ambiguous Hosted capture conflicts with recovered identity")
    try:
        trigger_repository = trigger_record.get("repository")
        if not isinstance(trigger_repository, str) or trigger_repository.casefold() != repo.casefold():
            raise SqliteRecordsRepairError("recovered Hosted trigger repository conflicts with its origin")
        trigger_record = sqlite_hosted_capture._validate_trigger_record(
            trigger_repository, pr_number, trigger_record
        )
    except sqlite_hosted_capture.HostedCaptureError as exc:
        raise SqliteRecordsRepairError("recovered Hosted trigger record is invalid") from exc
    original_trigger = [
        item for item in original_comments["comments"]
        if isinstance(item, dict) and github.immutable_database_id(item) == trigger_id
    ]
    original_response = [
        item for item in original_comments["comments"]
        if isinstance(item, dict) and github.immutable_database_id(item) == response_id
    ]
    recovered_trigger = [
        item for item in archived_comments.get("comments", [])
        if isinstance(item, dict) and github.immutable_database_id(item) == trigger_id
    ] if isinstance(archived_comments, dict) else []
    recovered_response = [
        item for item in archived_comments.get("comments", [])
        if isinstance(item, dict) and github.immutable_database_id(item) == response_id
    ] if isinstance(archived_comments, dict) else []
    if (
        len(original_trigger) != 1
        or len(original_response) != 1
        or len(recovered_trigger) != 1
        or len(recovered_response) != 1
        or original_trigger[0] != recovered_trigger[0]
        or original_response[0] != recovered_response[0]
        or not github.is_coderabbit_login((original_response[0].get("author") or {}).get("login"))
        or original_response[0].get("createdAt") != (metadata.get("zero_reply_proof") or {}).get("response_created_at")
        or original_response[0].get("updatedAt") != (metadata.get("zero_reply_proof") or {}).get("response_updated_at")
    ):
        raise SqliteRecordsRepairError("recovered Hosted response differs from its immutable captured comments")

    trigger_state = metadata.get("trigger_state")
    zero_proof = metadata.get("zero_reply_proof")
    checkpoint_fields = metadata.get("checkpoint_fields")
    try:
        checkpoint = evidence.Checkpoint(**checkpoint_fields)
        checkpoint_fingerprint = sqlite_provider_imports._checkpoint_fingerprint(checkpoint)
    except (TypeError, ValueError, sqlite_provider_imports.ProviderImportError) as exc:
        raise SqliteRecordsRepairError("recovered checkpoint fields are malformed") from exc
    finished_at = metadata.get("observed_at")
    started_at = (trigger_record.get("trigger") or {}).get("created_at")
    if (
        not isinstance(trigger_state, dict)
        or trigger_state.get("state") != "completed"
        or trigger_state.get("terminal") is not True
        or trigger_state.get("attributed") is not True
        or trigger_state.get("trigger_comment_id") != trigger_id
        or trigger_state.get("response_id") != response_id
        or trigger_state.get("head_sha") != head
        or type(trigger_state.get("duration_seconds")) is not int
        or trigger_state["duration_seconds"] < 0
        or (metadata.get("zero_reply_proof") or {}).get("duration_seconds") != trigger_state.get("duration_seconds")
        or not isinstance(zero_proof, dict)
        or zero_proof.get("attempt_id") != attempt_id
        or zero_proof.get("trigger_id") != trigger_id
        or zero_proof.get("response_id") != response_id
        or zero_proof.get("candidate_sha") != head
        or zero_proof.get("anchor") != anchor
        or zero_proof.get("counts") != {"found": 0, "accepted": 0, "routed": 0}
        or type(attempt_metadata["reviewed_file_count"]) is not int
        or checkpoint.comment_id != response_id
        or checkpoint.type != "Hosted"
        or checkpoint.raw_found != 0
        or checkpoint.accepted != 0
        or checkpoint.routed != 0
        or checkpoint.reviewed_sha != head
        or checkpoint.file_count != attempt_metadata["reviewed_file_count"]
        or checkpoint.hosted_review_id != response_id
        or checkpoint.duration_seconds != trigger_state["duration_seconds"]
        or checkpoint.duration_invalid is not False
        or checkpoint.created_at != metadata.get("checkpoint", {}).get("created_at")
        or checkpoint.updated_at != finished_at
        or checkpoint.created_at != original_response[0].get("createdAt")
        or checkpoint.updated_at != original_response[0].get("updatedAt")
        or metadata.get("checkpoint") != checkpoint.as_json()
        or metadata.get("checkpoint_fingerprint") != checkpoint_fingerprint
        or run.get("source_head") != head
        or run.get("started_at") != started_at
        or run.get("finished_at") != finished_at
        or run.get("counts") != {"found": 0, "accepted": 0, "routed": 0}
        or run.get("finalized") is not True
        or run.get("attributable") is not True
        or run.get("outcome") != "completed"
    ):
        raise SqliteRecordsRepairError("recovered Hosted zero-result proof or checkpoint does not match its source")
    trigger_at = hosted.parse_timestamp(started_at)
    finished_at_value = hosted.parse_timestamp(finished_at)
    if (
        trigger_at is None
        or finished_at_value is None
        or (finished_at_value - trigger_at).total_seconds() != trigger_state["duration_seconds"]
    ):
        raise SqliteRecordsRepairError("recovered Hosted duration does not match its immutable trigger window")
    origins = [item for item in history["provider_origins"] if item.get("run_id") == run_id]
    expected_origin = {
        "repository": repo.casefold(),
        "source_pr": pr_number,
        "channel": "hosted",
        "provider_id": f"trigger:{trigger_id}",
        "checkpoint_id": response_id,
        "checkpoint_fingerprint": checkpoint_fingerprint,
        "run_id": run_id,
    }
    if len(origins) != 1 or origins[0] != expected_origin:
        raise SqliteRecordsRepairError("recovered Hosted provider origin conflicts with immutable response")
    linked = sqlite_hosted_capture._linked_ambiguous_reply_recovery(
        history, repo, pr_number, attempt_rows[0], str(trigger_id)
    )
    if linked is None or linked.get("run_id") != run_id or linked.get("checkpoint_id") != response_id:
        raise SqliteRecordsRepairError("recovered Hosted result is not linked by the canonical recovery reader")
    if any(item.get("run_id") == run_id for item in history["findings"] + history["decisions"]):
        raise SqliteRecordsRepairError("recovered zero-result source unexpectedly contains findings or decisions")
    return {
        "run": run,
        "attempt": attempt,
        "attempt_id": attempt_id,
        "checkpoint": checkpoint,
        "checkpoint_fingerprint": checkpoint_fingerprint,
        "trigger_id": trigger_id,
        "response_id": response_id,
        "run_id": run_id,
        "repository": repo.casefold(),
        "source_pr": pr_number,
    }


def repair_ambiguous_hosted_reply(
    records: SqliteReviewRecords,
    *,
    repo: str,
    pr_number: int,
    attempt_id: str,
    response_id: int,
    actor: str,
    scope: str,
    hosted_payload: dict[str, Any],
    dry_run: bool = True,
) -> dict[str, Any]:
    """Repair one exact captured ambiguous zero reply through existing record APIs."""

    if not isinstance(records, SqliteReviewRecords):
        raise SqliteRecordsRepairError("reply recovery requires a SqliteReviewRecords store")
    if not isinstance(actor, str) or not actor.strip() or len(actor) > 100:
        raise SqliteRecordsRepairError("repair actor must be non-empty text of at most 100 characters")
    if scope not in {"broad", "narrow"}:
        raise SqliteRecordsRepairError("repair scope must be broad or narrow")
    if not isinstance(dry_run, bool):
        raise SqliteRecordsRepairError("dry_run must be boolean")
    proof = _ambiguous_reply_proof(
        records,
        repo=repo,
        pr_number=pr_number,
        attempt_id=attempt_id,
        response_id=response_id,
        hosted_payload=hosted_payload,
    )

    def import_stages(target: SqliteReviewRecords) -> tuple[dict[str, Any], dict[str, Any], dict[str, Any]]:
        imported = target.import_completed_run(
            run_id=proof["run_id"],
            source_pr=pr_number,
            channel="hosted",
            findings=(),
            source_decisions=(),
            source_head=proof["attempt"]["candidate_sha"],
            reviewer=proof["reviewer"],
            scope=scope,
            coverage_limits=(),
            started_at=proof["started_at"],
            finished_at=proof["finished_at"],
            finalized_at=proof["finished_at"],
        )
        archived = target.archive_imported_artifacts(proof["run_id"], proof["artifacts"])
        origin = target.link_provider_origin(
            repository=repo.casefold(),
            source_pr=pr_number,
            channel="hosted",
            provider_id=proof["provider_id"],
            checkpoint_id=proof["checkpoint_id"],
            checkpoint_fingerprint=proof["checkpoint_fingerprint"],
            run_id=proof["run_id"],
        )
        return imported, archived, origin

    with tempfile.TemporaryDirectory(prefix="pr-review-reply-repair-") as scratch:
        clone = _clone_records(records, Path(scratch) / "records.sqlite3")
        try:
            clone_imported, clone_archived, clone_origin = import_stages(clone)
            clone_readback = _verify_ambiguous_reply_import(clone, repo=repo, pr_number=pr_number, proof=proof)
        except (ReviewRecordsError, sqlite3.DatabaseError, OSError) as exc:
            raise SqliteRecordsRepairError(f"reply recovery preflight refused: {exc}") from exc
    expected = {
        "attempt_id": attempt_id,
        "response_id": response_id,
        "trigger_id": int(proof["provider_id"].split(":", 1)[1]),
        "candidate_sha": proof["attempt"]["candidate_sha"],
        "anchor": proof["attempt"]["metadata"]["anchor"],
        "run_id": proof["run_id"],
        "provider_id": proof["provider_id"],
        "checkpoint_id": proof["checkpoint_id"],
        "checkpoint_fingerprint": proof["checkpoint_fingerprint"],
        "started_at": proof["started_at"],
        "finished_at": proof["finished_at"],
        "duration_seconds": proof["state"].duration_seconds,
        "counts": {"found": 0, "accepted": 0, "routed": 0},
        "artifact_sha256": {
            kind: hashlib.sha256(content.encode("utf-8")).hexdigest()
            for kind, content in sorted(proof["artifacts"].items())
        },
        "stages": ["completed_zero_run", "archived_artifacts", "provider_origin_last"],
        "readback": clone_readback,
        "action": "already_recovered"
        if all(item.get("idempotent_replay") is True for item in (clone_imported, clone_archived, clone_origin))
        else "would_recover",
    }
    if dry_run:
        return {
            "api_version": _API_VERSION,
            "status": "preview",
            "dry_run": True,
            "repository": repo.casefold(),
            "source_pr": pr_number,
            "items": [expected],
        }

    try:
        imported, archived, origin = import_stages(records)
        readback = _verify_ambiguous_reply_import(records, repo=repo, pr_number=pr_number, proof=proof)
    except (ReviewRecordsError, sqlite3.DatabaseError, OSError, SqliteRecordsRepairError) as exc:
        return {
            "api_version": _API_VERSION,
            "status": "partial",
            "dry_run": False,
            "repository": repo.casefold(),
            "source_pr": pr_number,
            "items": [{**expected, "status": "partial"}],
            "error": _partial_diagnostic(exc) if isinstance(exc, (OSError, sqlite3.DatabaseError)) else str(exc),
        }
    expected["action"] = (
        "already_recovered"
        if all(item.get("idempotent_replay") is True for item in (imported, archived, origin))
        else "recovered"
    )
    expected["readback"] = readback
    return {
        "api_version": _API_VERSION,
        "status": "complete",
        "dry_run": False,
        "repository": repo.casefold(),
        "source_pr": pr_number,
        "items": [expected],
    }


def archive_incomplete_checkpoint(
    records: SqliteReviewRecords,
    *,
    repo: str,
    pr_number: int,
    checkpoint: evidence.Checkpoint,
    missing_reason: str,
    hosted_payload: dict[str, Any] | None = None,
    common: Path | None = None,
    dry_run: bool = True,
) -> dict[str, Any]:
    """Retain real old evidence and name what prevents a completed import."""

    repo, selected = _validate_request(
        records,
        repo=repo,
        pr_number=pr_number,
        checkpoints=(checkpoint,),
        actor="historical-repair",
        scope="broad",
        coverage_limits=(),
    )
    if len(selected) != 1 or not isinstance(missing_reason, str) or not missing_reason.strip():
        raise SqliteRecordsRepairError("incomplete checkpoint requires one source and a reason")
    if not isinstance(dry_run, bool):
        raise SqliteRecordsRepairError("dry_run must be boolean")
    artifacts = {
        "metadata": _artifact_text(
            {
                "checkpoint": checkpoint.as_json(),
                "missing_reason": missing_reason,
                "archive_scope": "exact-checkpoint-and-linked-review",
            }
        ),
    }
    if checkpoint.type == "Hosted":
        if hosted_payload is None:
            raise SqliteRecordsRepairError("complete public Hosted history is unavailable")
        try:
            pull = sqlite_hosted_capture._complete_pull_request(hosted_payload, pr_number)
        except (sqlite_hosted_capture.HostedCaptureError, KeyError, TypeError, ValueError) as exc:
            raise SqliteRecordsRepairError(f"complete public Hosted history is unavailable: {exc}") from exc
        matches = [
            item for item in pull["comments"]["nodes"] if github.immutable_database_id(item) == checkpoint.comment_id
        ]
        if len(matches) != 1:
            raise SqliteRecordsRepairError("public Hosted checkpoint comment is missing or duplicated")
        item = matches[0]
        parsed, _ = evidence.parse_checkpoint_comments(
            [
                {
                    "id": checkpoint.comment_id,
                    "body": item.get("body"),
                    "created_at": item.get("createdAt"),
                    "updated_at": item.get("updatedAt"),
                    "author_login": ((item.get("author") or {}).get("login")),
                }
            ]
        )
        if len(parsed) != 1 or sqlite_provider_imports._checkpoint_fingerprint(
            parsed[0]
        ) != sqlite_provider_imports._checkpoint_fingerprint(checkpoint):
            raise SqliteRecordsRepairError("public Hosted checkpoint changed during historical repair")
        linked_reviews = [
            review
            for review in pull["reviews"]["nodes"]
            if github.immutable_database_id(review) == checkpoint.hosted_review_id
        ]
        artifacts["hosted_review"] = _artifact_text(linked_reviews)
        artifacts["hosted_comments"] = _artifact_text(
            {
                "comments": [item],
                "review_threads": [],
            }
        )
    else:
        try:
            capture = evidence._load_cli_capture(
                checkpoint,
                repo,
                pr_number,
                common,
                validate_checkpoint_decisions=False,
                records=records,
                sql_first=False,
            )
        except evidence.EvidenceError as exc:
            metadata = {
                "checkpoint": checkpoint.as_json(),
                "missing_reason": missing_reason,
                "archive_scope": "exact-checkpoint-and-linked-review",
                "capture_error": str(exc),
            }
            artifacts["metadata"] = _artifact_text(metadata)
            capture = None
        if capture is not None and capture.source_identity:
            directory = Path(capture.source_identity)
            stdout = _bounded_capture_text(directory, "stdout", 8 * 1024 * 1024, required=True)
            if stdout is not None:
                artifacts["cli_events"] = stdout
            stderr = _bounded_capture_text(directory, "stderr", 128 * 1024)
            if stderr is not None:
                artifacts["cli_diagnostic"] = stderr

    fingerprint = sqlite_provider_imports._checkpoint_fingerprint(checkpoint)
    target = records
    with tempfile.TemporaryDirectory(prefix="pr-review-gap-preview-") as scratch:
        clone = _clone_records(records, Path(scratch) / "records.sqlite3")
        try:
            preview = clone.record_historical_gap(
                repository=repo,
                source_pr=pr_number,
                channel="hosted" if checkpoint.type == "Hosted" else "cli",
                checkpoint_id=checkpoint.comment_id,
                checkpoint_fingerprint=fingerprint,
                checkpoint=checkpoint.as_json(),
                artifacts=artifacts,
                missing_reason=missing_reason,
            )
        except (ReviewRecordsError, sqlite3.DatabaseError, OSError) as exc:
            raise SqliteRecordsRepairError(str(exc)) from exc
    if dry_run:
        return {"status": "incomplete_preview", "dry_run": True, **preview}
    try:
        applied = target.record_historical_gap(
            repository=repo,
            source_pr=pr_number,
            channel="hosted" if checkpoint.type == "Hosted" else "cli",
            checkpoint_id=checkpoint.comment_id,
            checkpoint_fingerprint=fingerprint,
            checkpoint=checkpoint.as_json(),
            artifacts=artifacts,
            missing_reason=missing_reason,
        )
    except (ReviewRecordsError, sqlite3.DatabaseError, OSError) as exc:
        raise SqliteRecordsRepairError(str(exc)) from exc
    return {"status": "incomplete_archived", "dry_run": False, **applied}


__all__ = [
    "MissingHistoricalEvidenceError",
    "SqliteRecordsRepairError",
    "archive_incomplete_checkpoint",
    "repair_ambiguous_hosted_reply",
    "repair_provider_checkpoints",
]
