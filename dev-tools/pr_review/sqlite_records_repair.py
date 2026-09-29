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

from . import evidence, github, sqlite_hosted_capture, sqlite_provider_imports
from .sqlite_review_records import ReviewRecordsError, SqliteReviewRecords

_API_VERSION = 1
_REPOSITORY = re.compile(r"^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$")
_PROVIDER_ID = re.compile(r"^(?:review|trigger|run):[A-Za-z0-9._-]{1,80}$")


class SqliteRecordsRepairError(ValueError):
    """Raised when source evidence or its attribution is not safe to repair."""


def _checkpoint_fingerprint(checkpoint: evidence.Checkpoint) -> str:
    value = dataclasses.asdict(checkpoint)
    encoded = json.dumps(value, ensure_ascii=True, sort_keys=True, separators=(",", ":"), allow_nan=False)
    return hashlib.sha256(encoded.encode("utf-8")).hexdigest()


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

        fingerprint = _checkpoint_fingerprint(checkpoint)
        prior = by_comment.get(comment_id)
        if prior is not None:
            if prior[0] != fingerprint:
                raise SqliteRecordsRepairError(
                    f"public checkpoint comment {comment_id} has conflicting parsed content"
                )
            continue
        by_comment[comment_id] = (fingerprint, checkpoint)

    return repo, [item[1] for item in sorted(by_comment.values(), key=lambda item: item[1].comment_id or 0)]


def _require_origin_link_api(records: SqliteReviewRecords) -> None:
    if not callable(getattr(records, "link_provider_origin", None)):
        raise SqliteRecordsRepairError(
            "provider origin-link API is unavailable; no repair was written"
        )
    if not callable(getattr(records, "archive_imported_artifacts", None)):
        raise SqliteRecordsRepairError(
            "historical artifact API is unavailable; no repair was written"
        )


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
    try:
        if checkpoint.type == "Hosted":
            imported = sqlite_provider_imports.import_hosted_checkpoint(
                records,
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
        else:
            imported = sqlite_provider_imports.import_cli_checkpoint(
                records,
                repo=repo,
                pr_number=pr_number,
                checkpoint=checkpoint,
                actor=actor,
                common=common,
                scope=scope,  # type: ignore[arg-type]
                coverage_limits=coverage_limits,
            )
    except (sqlite_provider_imports.ProviderImportError, ReviewRecordsError, evidence.EvidenceError) as exc:
        raise SqliteRecordsRepairError(str(exc)) from exc
    if not isinstance(imported, dict) or not isinstance(imported.get("run_id"), str):
        raise SqliteRecordsRepairError("canonical provider importer returned no exact structured run identity")
    if not isinstance(imported.get("counts"), dict):
        raise SqliteRecordsRepairError("canonical provider importer returned no validated finding counts")
    return imported, _provider_id(checkpoint, imported)


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
    repo: str,
    pr_number: int,
    checkpoint: evidence.Checkpoint,
    common: Path | None,
    imported: Mapping[str, Any],
) -> dict[str, str]:
    """Collect the validated source capture as bounded imported artifacts."""

    fingerprint = _checkpoint_fingerprint(checkpoint)
    supplied = imported.get("archive_artifacts")
    if supplied is not None:
        if not isinstance(supplied, Mapping) or any(
            not isinstance(kind, str) or not isinstance(content, str)
            for kind, content in supplied.items()
        ):
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
            or (
                checkpoint.type == "Hosted"
                and "hosted_comments" not in artifacts
            )
            or (
                checkpoint.type == "Hosted"
                and provider_id.startswith("review:")
                and "hosted_review" not in artifacts
            )
        ):
            raise SqliteRecordsRepairError("canonical importer returned incomplete provider archive artifacts")
        return artifacts

    if checkpoint.type == "CLI":
        capture = evidence.load_cli_capture(checkpoint, repo, pr_number, common)
        if not capture.source_identity:
            raise SqliteRecordsRepairError("validated CLI capture has no exact source directory")
        directory = Path(capture.source_identity)
        stdout = _bounded_capture_text(directory, "stdout", 8 * 1024 * 1024, required=True)
        legacy_metadata = _bounded_capture_text(directory, "metadata", 128 * 1024, required=True)
        metadata_json = _bounded_capture_text(directory, "metadata.json", 128 * 1024)
        exit_status = _bounded_capture_text(directory, "exit-status", 64, required=True)
        stderr = _bounded_capture_text(directory, "stderr", 128 * 1024)
        duration = _bounded_capture_text(directory, "review-duration-seconds", 64)
        decisions = _bounded_capture_text(directory, "decisions.tsv", 128 * 1024)
        rejections = _bounded_capture_text(directory, "rejections.tsv", 128 * 1024)
        if stdout is None or legacy_metadata is None or exit_status is None:
            raise SqliteRecordsRepairError("validated CLI capture is missing a required artifact")
        try:
            parsed_metadata: Any = json.loads(metadata_json) if metadata_json is not None else None
        except json.JSONDecodeError as exc:
            raise SqliteRecordsRepairError("CLI metadata.json is malformed") from exc
        metadata_artifact = {
            "capture_format": "firemud-cli-capture/v1",
            "run_id": checkpoint.run_id,
            "repository": repo.casefold(),
            "pull_request": pr_number,
            "checkpoint": checkpoint.as_json(),
            "checkpoint_fingerprint": fingerprint,
            "metadata_json": parsed_metadata,
            "metadata_json_sha256": (
                hashlib.sha256(metadata_json.encode("utf-8")).hexdigest()
                if metadata_json is not None
                else None
            ),
            "legacy_metadata_text": legacy_metadata if metadata_json is None else None,
            "legacy_metadata_sha256": hashlib.sha256(legacy_metadata.encode("utf-8")).hexdigest(),
            "exit_status": exit_status,
            "review_duration_seconds": duration,
            "decisions_tsv": decisions,
            "rejections_tsv": rejections,
            "capture_source_directory": directory.name,
        }
        artifacts = {
            "cli_events": stdout,
            "metadata": _artifact_text(metadata_artifact),
        }
        if stderr is not None:
            artifacts["cli_diagnostic"] = stderr
        return artifacts

    review_id = checkpoint.hosted_review_id
    if review_id is None:
        raise SqliteRecordsRepairError(
            "canonical Hosted import did not expose a review ID for its capture archive"
        )
    capture = evidence.load_hosted_capture(repo, pr_number, review_id, common)
    candidates = evidence._hosted_snapshot_candidates(repo, pr_number, review_id, common)
    existing_paths = list(dict.fromkeys(
        path.resolve() for path in candidates if path.is_file() and not path.is_symlink()
    ))
    if len(existing_paths) != 1:
        raise SqliteRecordsRepairError(
            "Hosted capture archive is missing or has multiple candidate snapshots"
        )
    snapshot_path = existing_paths[0]
    snapshot_text = _bounded_capture_text(snapshot_path.parent, snapshot_path.name, 12 * 1024 * 1024, required=True)
    if snapshot_text is None:
        raise SqliteRecordsRepairError("Hosted capture snapshot is missing")
    try:
        snapshot = json.loads(snapshot_text)
    except json.JSONDecodeError as exc:
        raise SqliteRecordsRepairError("Hosted capture snapshot is malformed") from exc
    if not isinstance(snapshot, dict):
        raise SqliteRecordsRepairError("Hosted capture snapshot is not an object")
    decisions_text = _bounded_capture_text(snapshot_path.parent, "decisions.tsv", 128 * 1024)
    metadata_artifact = {
        "capture_format": "firemud-hosted-capture/v1",
        "repository": repo.casefold(),
        "pull_request": pr_number,
        "review_id": review_id,
        "checkpoint": checkpoint.as_json(),
        "checkpoint_fingerprint": fingerprint,
        "snapshot_sha256": hashlib.sha256(snapshot_text.encode("utf-8")).hexdigest(),
        "decision_file_present": capture.decision_file_present,
        "decisions_tsv": decisions_text,
        "decisions": {
            str(key): {"disposition": value[0], "reason": value[1]}
            for key, value in sorted(capture.decisions.items())
        },
        "unlinked_decisions": capture.unlinked_decisions,
    }
    return {
        "hosted_review": _artifact_text(snapshot.get("review")),
        "hosted_comments": _artifact_text(snapshot.get("comments")),
        "metadata": _artifact_text(metadata_artifact),
    }


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
            fingerprint = _checkpoint_fingerprint(checkpoint)
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
                repo=repo,
                pr_number=pr_number,
                checkpoint=checkpoint,
                common=common,
                imported=imported,
            )
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
            fingerprint = _checkpoint_fingerprint(checkpoint)
            artifacts = _capture_artifacts(
                repo=repo,
                pr_number=pr_number,
                checkpoint=checkpoint,
                common=common,
                imported=imported,
            )
            item = {
                **expected,
                "action": "already_imported" if imported.get("idempotent_replay") else "imported",
                "origin_linked": False,
                "artifacts_archived": False,
            }
            applied.append(item)
            if (
                provider_id != expected["provider_id"]
                or imported["run_id"] != expected["run_id"]
                or imported["counts"] != expected["counts"]
                or fingerprint != expected["checkpoint_fingerprint"]
                or sorted(artifacts) != expected["artifact_kinds"]
                or {
                    kind: hashlib.sha256(content.encode("utf-8")).hexdigest()
                    for kind, content in sorted(artifacts.items())
                }
                != expected["artifact_sha256"]
            ):
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
            _archive_artifacts(records, imported["run_id"], artifacts)
            item["artifacts_archived"] = True
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
        records, repo=repo, pr_number=pr_number, checkpoints=(checkpoint,),
        actor="historical-repair", scope="broad", coverage_limits=(),
    )
    if len(selected) != 1 or not isinstance(missing_reason, str) or not missing_reason.strip():
        raise SqliteRecordsRepairError("incomplete checkpoint requires one source and a reason")
    if not isinstance(dry_run, bool):
        raise SqliteRecordsRepairError("dry_run must be boolean")
    artifacts = {
        "metadata": _artifact_text({
            "checkpoint": checkpoint.as_json(), "missing_reason": missing_reason,
        }),
    }
    if checkpoint.type == "Hosted":
        if hosted_payload is None:
            raise SqliteRecordsRepairError("complete public Hosted history is unavailable")
        try:
            pull = sqlite_hosted_capture._complete_pull_request(hosted_payload, pr_number)
        except (sqlite_hosted_capture.HostedCaptureError, KeyError, TypeError, ValueError) as exc:
            raise SqliteRecordsRepairError(f"complete public Hosted history is unavailable: {exc}") from exc
        matches = [
            item for item in pull["comments"]["nodes"]
            if github.immutable_database_id(item) == checkpoint.comment_id
        ]
        if len(matches) != 1:
            raise SqliteRecordsRepairError("public Hosted checkpoint comment is missing or duplicated")
        item = matches[0]
        parsed, _ = evidence.parse_checkpoint_comments([{
            "id": checkpoint.comment_id, "body": item.get("body"),
            "created_at": item.get("createdAt"), "updated_at": item.get("updatedAt"),
            "author_login": ((item.get("author") or {}).get("login")),
        }])
        if len(parsed) != 1 or _checkpoint_fingerprint(parsed[0]) != _checkpoint_fingerprint(checkpoint):
            raise SqliteRecordsRepairError("public Hosted checkpoint changed during historical repair")
        artifacts["hosted_review"] = _artifact_text(pull["reviews"]["nodes"])
        artifacts["hosted_comments"] = _artifact_text({
            "comments": pull["comments"]["nodes"],
            "review_threads": pull["reviewThreads"]["nodes"],
        })
    else:
        try:
            capture = evidence._load_cli_capture(
                checkpoint, repo, pr_number, common,
                validate_checkpoint_decisions=False, records=records,
            )
        except evidence.EvidenceError:
            capture = None
        if capture is not None and capture.source_identity:
            directory = Path(capture.source_identity)
            stdout = _bounded_capture_text(directory, "stdout", 8 * 1024 * 1024, required=True)
            if stdout is not None:
                artifacts["cli_events"] = stdout
            stderr = _bounded_capture_text(directory, "stderr", 128 * 1024)
            if stderr is not None:
                artifacts["cli_diagnostic"] = stderr

    fingerprint = _checkpoint_fingerprint(checkpoint)
    target = records
    with tempfile.TemporaryDirectory(prefix="pr-review-gap-preview-") as scratch:
        clone = _clone_records(records, Path(scratch) / "records.sqlite3")
        try:
            preview = clone.record_historical_gap(
                repository=repo, source_pr=pr_number,
                channel="hosted" if checkpoint.type == "Hosted" else "cli",
                checkpoint_id=checkpoint.comment_id,
                checkpoint_fingerprint=fingerprint,
                checkpoint=checkpoint.as_json(), artifacts=artifacts,
                missing_reason=missing_reason,
            )
        except (ReviewRecordsError, sqlite3.DatabaseError, OSError) as exc:
            raise SqliteRecordsRepairError(str(exc)) from exc
    if dry_run:
        return {"status": "incomplete_preview", "dry_run": True, **preview}
    try:
        applied = target.record_historical_gap(
            repository=repo, source_pr=pr_number,
            channel="hosted" if checkpoint.type == "Hosted" else "cli",
            checkpoint_id=checkpoint.comment_id,
            checkpoint_fingerprint=fingerprint,
            checkpoint=checkpoint.as_json(), artifacts=artifacts,
            missing_reason=missing_reason,
        )
    except (ReviewRecordsError, sqlite3.DatabaseError, OSError) as exc:
        raise SqliteRecordsRepairError(str(exc)) from exc
    return {"status": "incomplete_archived", "dry_run": False, **applied}


__all__ = ["SqliteRecordsRepairError", "archive_incomplete_checkpoint", "repair_provider_checkpoints"]
