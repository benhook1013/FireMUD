"""Import completed provider evidence and its bounded archive into SQLite.

The canonical readers prove each checkpoint and capture before this module
stores structured findings, source decisions, and the exact imported evidence.
Reply-only Hosted results use the existing trigger attribution proof and are
limited to clean zero-finding checkpoints.
"""

from __future__ import annotations

import dataclasses
import hashlib
import json
import re
import uuid
from collections.abc import Mapping, Sequence
from pathlib import Path
from typing import Any, Literal

from . import evidence, github, hosted, sqlite_hosted_capture, sqlite_review_records
from .sqlite_finding_text import _first_line, _safe_finding_detail  # noqa: F401
from .sqlite_review_records import FindingObservation, ReviewRecordsError, SqliteReviewRecords
from .state import ControllerStateStore, FindingRoute, StateError, SummaryFindingDisposition, state_path

_IMPORT_NAMESPACE = uuid.UUID("e653ea91-15ea-4744-ae8a-4e122e337feb")


class ProviderImportError(ValueError):
    """Raised when provider evidence is incomplete, ambiguous, or non-counting."""


def import_hosted_checkpoint(
    records: SqliteReviewRecords,
    *,
    repo: str,
    pr_number: int,
    checkpoint: evidence.Checkpoint,
    actor: str,
    common: Path | None = None,
    scope: Literal["broad", "narrow"],
    coverage_limits: Sequence[str] = (),
    summary_dispositions: Sequence[SummaryFindingDisposition] | None = None,
    hosted_payload: dict[str, Any] | None = None,
) -> dict[str, Any]:
    """Import one parsed Hosted checkpoint and its exact private capture.

    ``checkpoint`` must come from :func:`evidence.parse_checkpoint_comments`.
    Hosted checkpoints without one exact completed review, exact head linkage,
    the provider's actual inline findings, and a complete matching decisions
    file are refused. Summary-only buckets are imported only when the captured
    review supplies their exact immutable identity and a matching private
    :class:`SummaryFindingDisposition`; their SQLite observations are explicit
    cardinality slots, not fabricated provider anchors. The exact head is
    review-time linkage to the captured review, not proof of the PR's current
    head. SQLite history does not satisfy current-head request safety or taper
    policy. Inline routed findings preserve their recorded reason but remain
    unassigned because the capture has no structured target PR ID. Routed
    summary-only buckets retain and validate their exact legacy route IDs;
    those routes are mirrored only for SQLite foreign-key integrity while the
    legacy controller record remains authoritative for target/status reads.
    """

    _validate_checkpoint(checkpoint, "Hosted")
    review_id = checkpoint.hosted_review_id
    if review_id is None:
        raise ProviderImportError("Hosted checkpoint has no exact review marker")

    candidates = evidence._hosted_snapshot_candidates(repo, pr_number, review_id, common)
    existing = [path for path in candidates if path.is_file() and not path.is_symlink()]
    if not existing:
        if hosted_payload is None:
            raise ProviderImportError(
                "Hosted checkpoint has no review capture; complete trigger evidence is required for a reply-only result"
            )
        return _import_reply_only_hosted_checkpoint(
            records,
            repo=repo,
            pr_number=pr_number,
            checkpoint=checkpoint,
            actor=actor,
            scope=scope,
            coverage_limits=coverage_limits,
            hosted_payload=hosted_payload,
            common=common,
        )
    if len({path.resolve() for path in existing}) != 1:
        raise ProviderImportError("Hosted checkpoint has multiple candidate review captures")

    capture = evidence.load_hosted_capture(repo, pr_number, review_id, common)
    source_head = capture.review["commit_id"]
    if checkpoint.reviewed_sha is None or not source_head.casefold().startswith(checkpoint.reviewed_sha.casefold()):
        raise ProviderImportError("Hosted checkpoint does not identify the captured review head")
    # Use the captured review commit as the review-time head. Historical runs
    # remain importable after the PR advances; current-head policy stays live.
    completion = evidence.hosted_checkpoint_evidence(checkpoint, [capture.review], source_head)
    if completion.get("status") != "completed":
        raise ProviderImportError("Hosted checkpoint is not attributable to a completed review")

    dispositions, legacy_routes = _summary_dispositions(common, summary_dispositions)
    raw_findings, ordered_decisions = _hosted_findings(
        capture,
        pr_number=pr_number,
        source_head=source_head,
        dispositions=dispositions,
        legacy_routes=legacy_routes,
    )
    if checkpoint.raw_found != len(raw_findings):
        raise ProviderImportError(
            "Hosted checkpoint raw count does not match captured findings; "
            "summary-only identity or cardinality evidence is unavailable"
        )
    _validate_counts(checkpoint, len(raw_findings), ordered_decisions)
    observations, decisions = _observations_and_decisions(
        channel="hosted",
        origin=review_id,
        actor=actor,
        raw_findings=raw_findings,
        source_decisions=ordered_decisions,
    )
    _preflight_text(actor, decisions)
    archive_artifacts = _hosted_archive_artifacts(
        repo=repo,
        pr_number=pr_number,
        checkpoint=checkpoint,
        capture=capture,
        common=common,
    )
    _validate_archive_artifacts(archive_artifacts)

    imported = _persist_import(
        records,
        repo=repo,
        pr_number=pr_number,
        channel="hosted",
        origin=review_id,
        source_head=source_head,
        reviewer=capture.review["user"]["login"],
        scope=scope,
        coverage_limits=coverage_limits,
        # The captured review submission is the provider's exact time anchor.
        # Do not let the records API substitute import time for this run.
        started_at=capture.review["submitted_at"],
        finished_at=capture.review["submitted_at"],
        observations=observations,
        decisions=decisions,
        actor=actor,
    )
    imported["provider_id"] = f"review:{review_id}"
    imported["archive_artifacts"] = archive_artifacts
    return imported


def import_cli_checkpoint(
    records: SqliteReviewRecords,
    *,
    repo: str,
    pr_number: int,
    checkpoint: evidence.Checkpoint,
    actor: str,
    common: Path | None = None,
    scope: Literal["broad", "narrow"],
    coverage_limits: Sequence[str] = (),
) -> dict[str, Any]:
    """Import one parsed CLI checkpoint and its exact private capture.

    The canonical CLI capture reader verifies the success event, checkpoint
    counts, exact candidate commit, and one recorded decision per raw finding.
    Captured stdout, metadata, decisions, and diagnostics are returned as
    archive artifacts for SQLite retention. Routed findings keep their
    recorded reason but remain unassigned; the free-text reason is not treated
    as a structured target PR identity.
    """

    _validate_checkpoint(checkpoint, "CLI")
    if checkpoint.run_id is None:
        raise ProviderImportError("CLI checkpoint has no exact run marker")
    capture = evidence.load_cli_capture_for_repair(checkpoint, repo, pr_number, common, records=records)
    source_head = capture.metadata.get("candidate_sha", "")
    if not evidence.EXACT_SHA.fullmatch(source_head):
        raise ProviderImportError("CLI capture has no exact candidate head")

    raw_findings = _cli_findings(capture)
    _validate_counts(checkpoint, len(raw_findings), capture.decisions)
    observations, decisions = _observations_and_decisions(
        channel="cli",
        origin=checkpoint.run_id,
        actor=actor,
        raw_findings=raw_findings,
        source_decisions=capture.decisions,
    )
    _preflight_text(actor, decisions)
    archive_artifacts = _cli_archive_artifacts(
        repo=repo,
        pr_number=pr_number,
        checkpoint=checkpoint,
        capture=capture,
    )
    _validate_archive_artifacts(archive_artifacts)

    imported = _persist_import(
        records,
        repo=repo,
        pr_number=pr_number,
        channel="cli",
        origin=checkpoint.run_id,
        source_head=source_head,
        reviewer="CodeRabbit CLI",
        scope=scope,
        coverage_limits=coverage_limits,
        # CLI captures do not contain a separate run timestamp; the parsed
        # completion checkpoint is the exact available event time.
        started_at=checkpoint.created_at,
        finished_at=checkpoint.created_at,
        observations=observations,
        decisions=decisions,
        actor=actor,
    )
    imported["provider_id"] = f"run:{checkpoint.run_id}"
    imported["archive_artifacts"] = archive_artifacts
    return imported


def _artifact_json(value: Any) -> str:
    return json.dumps(value, ensure_ascii=True, sort_keys=True, separators=(",", ":"), allow_nan=False)


def _checkpoint_fingerprint(checkpoint: evidence.Checkpoint) -> str:
    payload = _artifact_json(dataclasses.asdict(checkpoint)).encode("utf-8")
    return hashlib.sha256(payload).hexdigest()


def _validate_archive_artifacts(artifacts: Mapping[str, str]) -> None:
    try:
        for kind, content in artifacts.items():
            sqlite_review_records._archive_artifact(kind, content)
    except ReviewRecordsError as exc:
        raise ProviderImportError(f"historical evidence cannot be archived safely: {exc}") from exc


def _read_artifact(directory: Path, name: str, maximum: int, *, required: bool = False) -> str | None:
    path = evidence._contained_file(directory, name, required=required)
    if path is None:
        return None
    try:
        content = path.read_bytes()
    except OSError as exc:
        raise ProviderImportError(f"cannot read historical provider artifact {name}") from exc
    if len(content) > maximum:
        raise ProviderImportError(f"historical provider artifact {name} exceeds its archive limit")
    try:
        return content.decode("utf-8")
    except UnicodeDecodeError as exc:
        raise ProviderImportError(f"historical provider artifact {name} is not valid UTF-8") from exc


def _hosted_archive_artifacts(
    *,
    repo: str,
    pr_number: int,
    checkpoint: evidence.Checkpoint,
    capture: evidence.HostedCapture,
    common: Path | None,
) -> dict[str, str]:
    review_id = checkpoint.hosted_review_id
    if review_id is None:
        raise ProviderImportError("Hosted archive has no exact review marker")
    paths = {
        path.resolve()
        for path in evidence._hosted_snapshot_candidates(repo, pr_number, review_id, common)
        if path.is_file() and not path.is_symlink()
    }
    if len(paths) != 1:
        raise ProviderImportError("Hosted archive needs exactly one validated capture snapshot")
    snapshot_path = next(iter(paths))
    source = _read_artifact(snapshot_path.parent, snapshot_path.name, 8 * 1024 * 1024, required=True)
    assert source is not None
    try:
        snapshot = json.loads(source)
    except json.JSONDecodeError as exc:
        raise ProviderImportError("Hosted capture snapshot is malformed") from exc
    if not isinstance(snapshot, dict):
        raise ProviderImportError("Hosted capture snapshot is not an object")
    if snapshot.get("review") != capture.review or snapshot.get("comments") != capture.comments:
        raise ProviderImportError("Hosted capture changed after canonical validation")
    decisions_text = _read_artifact(snapshot_path.parent, "decisions.tsv", 128 * 1024)
    metadata = {
        "capture_format": "firemud-hosted-capture/v1",
        "repository": repo.casefold(),
        "pull_request": pr_number,
        "review_id": review_id,
        "checkpoint": checkpoint.as_json(),
        "checkpoint_fields": dataclasses.asdict(checkpoint),
        "checkpoint_fingerprint": _checkpoint_fingerprint(checkpoint),
        "snapshot_sha256": hashlib.sha256(source.encode("utf-8")).hexdigest(),
        "decision_file_present": capture.decision_file_present,
        "decisions_tsv": decisions_text,
        "decisions": {
            str(key): {"disposition": value[0], "reason": value[1]} for key, value in sorted(capture.decisions.items())
        },
        "unlinked_decisions": capture.unlinked_decisions,
    }
    return {
        "hosted_review": _artifact_json(snapshot["review"]),
        # Store the full source snapshot here, including comments and any
        # provider-supplied fields the structured reader does not interpret.
        "hosted_comments": _artifact_json(snapshot),
        "metadata": _artifact_json(metadata),
    }


def _cli_archive_artifacts(
    *, repo: str, pr_number: int, checkpoint: evidence.Checkpoint, capture: evidence.CaptureData
) -> dict[str, str]:
    if not capture.source_identity or checkpoint.run_id is None:
        raise ProviderImportError("CLI archive has no exact capture directory or run marker")
    directory = Path(capture.source_identity)
    stdout = _read_artifact(directory, "stdout", 8 * 1024 * 1024, required=True)
    legacy_metadata = _read_artifact(directory, "metadata", 128 * 1024, required=True)
    metadata_json = _read_artifact(directory, "metadata.json", 128 * 1024)
    exit_status = _read_artifact(directory, "exit-status", 64, required=True)
    stderr = _read_artifact(directory, "stderr", 128 * 1024)
    duration = _read_artifact(directory, "review-duration-seconds", 64)
    decisions = _read_artifact(directory, "decisions.tsv", 128 * 1024)
    rejections = _read_artifact(directory, "rejections.tsv", 128 * 1024)
    if stdout is None or legacy_metadata is None or exit_status is None:
        raise ProviderImportError("validated CLI capture is missing a required archive artifact")
    parsed_metadata: Any = None
    if metadata_json is not None:
        try:
            parsed_metadata = json.loads(metadata_json)
        except json.JSONDecodeError as exc:
            raise ProviderImportError("CLI metadata.json is malformed") from exc
    metadata = {
        "capture_format": "firemud-cli-capture/v1",
        "run_id": checkpoint.run_id,
        "repository": repo.casefold(),
        "pull_request": pr_number,
        "checkpoint": checkpoint.as_json(),
        "checkpoint_fields": dataclasses.asdict(checkpoint),
        "checkpoint_fingerprint": _checkpoint_fingerprint(checkpoint),
        "metadata_json": parsed_metadata,
        "metadata_json_text": metadata_json,
        "metadata_json_sha256": (
            hashlib.sha256(metadata_json.encode("utf-8")).hexdigest() if metadata_json is not None else None
        ),
        "legacy_metadata_text": legacy_metadata,
        "legacy_metadata_sha256": hashlib.sha256(legacy_metadata.encode("utf-8")).hexdigest(),
        "exit_status": exit_status,
        "review_duration_seconds": duration,
        "decisions_tsv": decisions,
        "rejections_tsv": rejections,
        "capture_source_directory": directory.name,
    }
    artifacts = {"cli_events": stdout, "metadata": _artifact_json(metadata)}
    if stderr is not None:
        artifacts["cli_diagnostic"] = stderr
    return artifacts


def _import_reply_only_hosted_checkpoint(
    records: SqliteReviewRecords,
    *,
    repo: str,
    pr_number: int,
    checkpoint: evidence.Checkpoint,
    actor: str,
    scope: Literal["broad", "narrow"],
    coverage_limits: Sequence[str],
    hosted_payload: dict[str, Any],
    common: Path | None,
) -> dict[str, Any]:
    """Import a zero-result edited bot reply through the controller's trigger proof."""

    from . import runtime

    if checkpoint.raw_found != 0 or checkpoint.accepted != 0 or checkpoint.routed not in (None, 0):
        raise ProviderImportError("reply-only Hosted imports are limited to exact zero-finding checkpoints")
    try:
        pull_request = sqlite_hosted_capture._complete_pull_request(hosted_payload, pr_number)
    except (sqlite_hosted_capture.HostedCaptureError, KeyError, TypeError, ValueError) as exc:
        raise ProviderImportError(f"complete Hosted history is unavailable: {exc}") from exc
    current_head = pull_request.get("headRefOid")
    if not isinstance(current_head, str) or not evidence.EXACT_SHA.fullmatch(current_head):
        raise ProviderImportError("Hosted checkpoint payload has no exact current head")
    if checkpoint.reviewed_sha is None:
        raise ProviderImportError("Hosted zero checkpoint has no reviewed head")

    public_comments = pull_request["comments"]["nodes"]
    checkpoint_matches: list[evidence.Checkpoint] = []
    raw_checkpoint_comments: list[dict[str, Any]] = []
    checkpoint_id = checkpoint.comment_id
    for item in public_comments:
        if github.immutable_database_id(item) != checkpoint_id:
            continue
        author = (item.get("author") or {}).get("login")
        normalized = {
            "id": checkpoint_id,
            "body": item.get("body"),
            "created_at": item.get("createdAt"),
            "updated_at": item.get("updatedAt"),
            "author_login": author,
        }
        parsed, _ = evidence.parse_checkpoint_comments([normalized])
        checkpoint_matches.extend(parsed)
        raw_checkpoint_comments.append(item)
    if (
        len(raw_checkpoint_comments) != 1
        or len(checkpoint_matches) != 1
        or _checkpoint_fingerprint(checkpoint_matches[0]) != _checkpoint_fingerprint(checkpoint)
    ):
        raise ProviderImportError("Hosted checkpoint does not exactly match one public pull-request comment")

    paths = hosted.trigger_record_paths(repo, pr_number, common)
    if not paths:
        raise ProviderImportError("reply-only Hosted checkpoint has no durable full-review trigger record")
    matches: list[tuple[dict[str, Any], dict[str, Any], dict[str, Any], str]] = []
    for path in paths:
        try:
            record = hosted.load_trigger_record(path, repo, pr_number)
            state = hosted.trigger_state(repo, pr_number, hosted_payload, record, path)
        except (OSError, ValueError, KeyError, TypeError, json.JSONDecodeError) as exc:
            raise ProviderImportError(f"Hosted trigger history is ambiguous at {path.name}: {exc}") from exc
        if state.response_id != checkpoint.hosted_review_id:
            continue
        if state.state != "completed" or not state.terminal or not state.attributed:
            raise ProviderImportError("checkpoint reply is not a completed, attributable Hosted trigger result")
        captured_head = record["head_sha"]
        if state.head_sha.casefold() != captured_head.casefold() or not captured_head.casefold().startswith(
            checkpoint.reviewed_sha.casefold()
        ):
            raise ProviderImportError("Hosted trigger checkpoint does not match its reviewed head")
        proof = runtime.LiveEvidence._hosted_zero_reply_proof(
            checkpoint,
            state.response_id,
            state.duration_seconds,
            repo,
            pr_number,
            captured_head,
            record,
            hosted_payload,
            current_record_path=path,
        )
        if proof is None:
            raise ProviderImportError("Hosted reply has no exact trigger-window zero-finding proof")
        anchor = record.get("anchor")
        if (
            not runtime.LiveEvidence._anchor_complete(anchor)
            or anchor.get("child_head", "").casefold() != captured_head.casefold()
        ):
            raise ProviderImportError("Hosted trigger has no complete candidate-head anchor")
        trigger_id = (record.get("trigger") or {}).get("id")
        trigger_comment = next(
            (item for item in public_comments if github.immutable_database_id(item) == trigger_id), None
        )
        trigger_author = ((trigger_comment or {}).get("author") or {}).get("login")
        if (
            not isinstance(trigger_id, int)
            or isinstance(trigger_id, bool)
            or not isinstance(trigger_author, str)
            or not isinstance(checkpoint.author_login, str)
            or github.is_coderabbit_login(checkpoint.author_login)
            or checkpoint.author_login.casefold() != trigger_author.casefold()
        ):
            raise ProviderImportError("Hosted checkpoint author does not match the exact external trigger author")
        response_matches = [
            item for item in public_comments if github.immutable_database_id(item) == checkpoint.hosted_review_id
        ]
        if len(response_matches) != 1:
            raise ProviderImportError("Hosted completion reply is missing or duplicated")
        response = response_matches[0]
        response_author = (response.get("author") or {}).get("login")
        created_at = response.get("createdAt")
        updated_at = response.get("updatedAt")
        created = hosted.parse_timestamp(created_at)
        updated = hosted.parse_timestamp(updated_at)
        checkpoint_at = hosted.parse_timestamp(checkpoint.created_at)
        trigger_at = hosted.parse_timestamp((record.get("trigger") or {}).get("created_at"))
        if (
            not github.is_coderabbit_login(response_author)
            or not isinstance(response.get("body"), str)
            or not hosted.FINISHED_REVIEW_PATTERN.search(hosted._unquoted(response["body"]))
            or created is None
            or updated is None
            or updated <= created
            or trigger_at is None
            or created <= trigger_at
            or checkpoint_at is None
            or checkpoint_at < updated
        ):
            raise ProviderImportError("Hosted checkpoint reply is not an edited completion after its exact trigger")
        matches.append((record, state.as_dict(), proof, response_author))

    if len(matches) != 1:
        raise ProviderImportError("Hosted zero checkpoint does not match exactly one durable trigger record")
    record, trigger_state, proof, reviewer = matches[0]
    trigger = record["trigger"]
    trigger_id = trigger["id"]
    origin = f"trigger:{trigger_id}"
    empty_findings: list[dict[str, Any]] = []
    observations, decisions = _observations_and_decisions(
        channel="hosted",
        origin=origin,
        actor=actor,
        raw_findings=empty_findings,
        source_decisions={},
    )
    _preflight_text(actor, decisions)
    window = sqlite_hosted_capture.archive_window(
        pull_request,
        record,
        finished_at=response["updatedAt"],
        response_id=checkpoint.hosted_review_id,
        checkpoint_id=str(checkpoint.comment_id) if checkpoint.comment_id is not None else None,
    )
    archive_artifacts = {
        "hosted_review": _artifact_json(window["reviews"]),
        "hosted_comments": _artifact_json(
            {
                "comments": window["comments"],
                "review_threads": window["review_threads"],
            }
        ),
        "metadata": _artifact_json(
            {
                "capture_format": "firemud-hosted-trigger-reply/v1",
                "repository": repo.casefold(),
                "pull_request": pr_number,
                "trigger_id": trigger_id,
                "trigger_record": record,
                "trigger_state": trigger_state,
                "zero_reply_proof": proof,
                "checkpoint": checkpoint.as_json(),
                "checkpoint_fields": dataclasses.asdict(checkpoint),
                "checkpoint_fingerprint": _checkpoint_fingerprint(checkpoint),
                "current_head": current_head,
                "response_id": checkpoint.hosted_review_id,
            }
        ),
    }
    _validate_archive_artifacts(archive_artifacts)
    imported = _persist_import(
        records,
        repo=repo,
        pr_number=pr_number,
        channel="hosted",
        origin=origin,
        source_head=record["head_sha"],
        reviewer=reviewer,
        scope=scope,
        coverage_limits=coverage_limits,
        started_at=trigger["created_at"],
        finished_at=response["updatedAt"],
        observations=observations,
        decisions=decisions,
        actor=actor,
    )
    imported["provider_id"] = origin
    imported["archive_artifacts"] = archive_artifacts
    return imported


def _validate_checkpoint(checkpoint: evidence.Checkpoint, channel: str) -> None:
    if not isinstance(checkpoint, evidence.Checkpoint) or checkpoint.type != channel:
        raise ProviderImportError(f"import requires one parsed {channel} checkpoint")
    if checkpoint.correction:
        raise ProviderImportError("correction checkpoints are not source review runs")
    if checkpoint.duration_invalid:
        raise ProviderImportError("checkpoint duration evidence is invalid")
    if checkpoint.reviewed_sha is None:
        raise ProviderImportError("checkpoint has no reviewed head identity")


def _summary_dispositions(
    common: Path | None,
    supplied: Sequence[SummaryFindingDisposition] | None,
) -> tuple[tuple[SummaryFindingDisposition, ...], dict[str, FindingRoute]]:
    """Read exact summary decisions from private state unless explicitly supplied."""

    state: Any
    if supplied is None:
        try:
            state = ControllerStateStore(state_path(common)).load()
        except (OSError, StateError, TypeError, ValueError) as exc:
            raise ProviderImportError(f"summary disposition state is unavailable: {exc}") from exc
        supplied = state.summary_dispositions
    else:
        try:
            state = ControllerStateStore(state_path(common)).load()
        except (OSError, StateError, TypeError, ValueError) as exc:
            raise ProviderImportError(f"legacy route state is unavailable: {exc}") from exc
    if isinstance(supplied, (str, bytes)) or not isinstance(supplied, Sequence):
        raise ProviderImportError("summary dispositions must be a sequence")
    dispositions = tuple(supplied)
    if any(not isinstance(item, SummaryFindingDisposition) for item in dispositions):
        raise ProviderImportError("summary dispositions contain an invalid record")
    routes = {route.route_id: route for route in state.routes}
    for disposition in dispositions:
        if disposition.decision != "routed":
            continue
        for route_id in disposition.route_ids:
            route = routes.get(route_id)
            if route is None:
                raise ProviderImportError(f"summary disposition references unavailable legacy route {route_id}")
            if (
                route.source_pr != disposition.pr
                or route.source_channel != "hosted"
                or route.source_review != f"summary:{disposition.source}:{disposition.summary_id}"
                or not route.source_finding.startswith(f"{disposition.kind}:")
            ):
                raise ProviderImportError(f"legacy route {route_id} does not match its exact summary disposition")
            reference = route.source_finding[len(disposition.kind) + 1 :]
            if reference.startswith("ref:"):
                reference_valid = bool(reference[4:].strip())
            else:
                match = re.fullmatch(r"(?P<count>[1-9][0-9]*):(?P<reference>.+)", reference)
                reference_valid = bool(
                    match and int(match.group("count")) == disposition.count and match.group("reference").strip()
                )
            if not reference_valid:
                raise ProviderImportError(f"legacy route {route_id} does not match its exact summary finding bucket")
    return dispositions, routes


def _summary_sections(body: Any) -> tuple[dict[str, int], bool]:
    if not isinstance(body, str):
        raise ProviderImportError("Hosted summary body is not text")
    try:
        outside, duplicate = evidence.summary_action_counts(body)
        has_sections = evidence.has_summary_action_sections(body)
    except evidence.EvidenceError as exc:
        raise ProviderImportError(f"Hosted summary evidence is malformed: {exc}") from exc
    return {"outside_diff": outside, "duplicate": duplicate}, has_sections


def _hosted_summary(capture: evidence.HostedCapture) -> tuple[str, int, dict[str, int], set[int]] | None:
    """Return one exact captured summary anchor, if it contains positive buckets."""

    candidates: list[tuple[str, int, dict[str, int], set[int]]] = []
    review_body = capture.review.get("body")
    if review_body is not None:
        counts, has_sections = _summary_sections(review_body)
        if has_sections:
            candidates.append(("review", capture.review["id"], counts, set()))

    summary_comment_ids: set[int] = set()
    for comment in capture.comments:
        if not evidence._is_coderabbit((comment.get("user") or {}).get("login")):
            continue
        if comment.get("in_reply_to_id") is not None:
            continue
        # Review-comment captures have an immutable parent review identity. A
        # detached issue comment cannot be proven to belong to this review.
        if comment.get("pull_request_review_id") != capture.review["id"]:
            continue
        if not isinstance(comment.get("body"), str):
            continue
        counts, has_sections = _summary_sections(comment.get("body"))
        if has_sections:
            comment_id = comment["id"]
            summary_comment_ids.add(comment_id)
            candidates.append(("comment", comment_id, counts, {comment_id}))

    positive = [candidate for candidate in candidates if any(candidate[2].values())]
    if len(positive) > 1:
        raise ProviderImportError("Hosted capture contains multiple summary-only anchors")
    if not positive:
        return None
    return positive[0]


def _summary_disposition(
    dispositions: Sequence[SummaryFindingDisposition],
    *,
    pr_number: int,
    source_head: str,
    source: str,
    summary_id: int,
    kind: str,
    count: int,
) -> SummaryFindingDisposition:
    candidates = [
        item
        for item in dispositions
        if item.pr == pr_number
        and item.head.casefold() == source_head.casefold()
        and item.source == source
        and item.summary_id == summary_id
        and item.kind == kind
    ]
    if len(candidates) > 1:
        raise ProviderImportError("multiple summary dispositions match one exact summary bucket")
    if not candidates:
        raise ProviderImportError(f"summary-only {kind} bucket ({count}) has no exact structural disposition")
    disposition = candidates[0]
    if disposition.count != count:
        raise ProviderImportError(
            f"summary-only {kind} disposition count {disposition.count} does not match captured count {count}"
        )
    if disposition.decision == "routed" and len(disposition.route_ids) != count:
        raise ProviderImportError(f"routed summary-only {kind} bucket requires one stable route ID per item")
    return disposition


def _summary_decision(disposition: SummaryFindingDisposition) -> tuple[str, str]:
    decision = "accepted" if disposition.decision in {"accepted_unfixed", "accepted_fixed"} else disposition.decision
    return decision, disposition.reason


def _hosted_findings(
    capture: evidence.HostedCapture,
    *,
    pr_number: int,
    source_head: str,
    dispositions: Sequence[SummaryFindingDisposition],
    legacy_routes: Mapping[str, FindingRoute],
) -> tuple[list[dict[str, Any]], dict[int, tuple[str, str]]]:
    summary = _hosted_summary(capture)
    summary_comment_ids = summary[3] if summary is not None else set()
    findings = [
        comment
        for comment in capture.comments
        if evidence._is_coderabbit((comment.get("user") or {}).get("login"))
        and comment.get("in_reply_to_id") is None
        and comment.get("id") not in summary_comment_ids
    ]
    if capture.unlinked_decisions or len(capture.decisions) != len(findings):
        raise ProviderImportError("Hosted capture does not contain a complete linked decision for every finding")
    result: list[dict[str, Any]] = []
    ordered_decisions: dict[int, tuple[str, str]] = {}
    for comment in findings:
        try:
            comment_findings = sqlite_hosted_capture._hosted_comment_finding_segments(
                comment["id"], comment.get("body")
            )
        except sqlite_hosted_capture.HostedCaptureError as exc:
            raise ProviderImportError(f"Hosted finding identity is malformed: {exc}") from exc
        for finding in comment_findings:
            title = finding["title"]
            if not title:
                raise ProviderImportError("Hosted finding has no bounded title")
            if len(title) > 300:
                raise ProviderImportError("Hosted finding headline exceeds the SQLite title limit")
            result.append(
                {
                    "key": finding["key"],
                    "title": title,
                    "detail": finding["detail"],
                    "id": comment["id"],
                }
            )
            # Legacy decisions.tsv records one disposition per immutable GitHub
            # comment. When that comment now has several independent provider
            # fingerprints, the captured disposition applies to each atom.
            ordered_decisions[len(result)] = capture.decisions[comment["id"]]

    if summary is not None:
        source, summary_id, counts, _ = summary
        for kind, count in counts.items():
            if not count:
                continue
            disposition = _summary_disposition(
                dispositions,
                pr_number=pr_number,
                source_head=source_head,
                source=source,
                summary_id=summary_id,
                kind=kind,
                count=count,
            )
            decision = _summary_decision(disposition)
            for ordinal in range(1, count + 1):
                result.append(
                    {
                        "key": f"hosted-summary:{source}:{summary_id}:{kind}:{ordinal}",
                        "title": (
                            f"CodeRabbit summary-only {kind.replace('_', '-')} finding "
                            f"{ordinal} of {count} (aggregate; no individual detail)"
                        ),
                        "detail": "summary-only aggregate; provider supplied no individual identity or detail",
                        "legacy_route_id": (disposition.route_ids[ordinal - 1] if decision[0] == "routed" else None),
                        "legacy_route": (
                            legacy_routes[disposition.route_ids[ordinal - 1]] if decision[0] == "routed" else None
                        ),
                    }
                )
                ordered_decisions[len(result)] = decision
    return result, ordered_decisions


def _cli_findings(capture: evidence.CaptureData) -> list[dict[str, Any]]:
    result: list[dict[str, Any]] = []
    for index, finding in enumerate(capture.findings, 1):
        instructions = finding.get("codegenInstructions")
        title = _cli_finding_title(instructions, "")
        if not title:
            raise ProviderImportError(f"CLI finding {index} has no bounded title")
        if len(title) > 300:
            raise ProviderImportError(f"CLI finding {index} headline exceeds the SQLite title limit")
        # The existing decision file addresses findings by this 1-based event
        # ordinal; retain that canonical identity instead of hashing content.
        result.append(
            {
                "key": f"cli-run:{capture.metadata['run_id']}:finding:{index}",
                "title": title,
                "detail": _safe_finding_detail(_cli_detail(instructions)),
            }
        )
    if capture.unlinked_decisions:
        raise ProviderImportError("CLI capture contains unlinked source decisions")
    return result


def _cli_headline(value: Any) -> str | None:
    """Extract a short headline from CLI instructions without storing the prompt."""

    candidates, has_locator = _cli_lines_after_locator(value)
    # CodeRabbit often prepends our safety reminder and a file/line locator to
    # its actual finding. Those are poor worklist titles, especially for a
    # routed finding that a different PR owner needs to triage.
    if has_locator:
        headline = next((item.strip() for item in candidates if item.strip()), None)
    else:
        headline = next((line.strip() for line in candidates if line.strip()), None)
    if headline is None:
        return None
    # CLI codegen instructions are full provider prompts. Store only a bounded
    # headline here; the redacted source artifact is archived separately.
    return headline[:180].rstrip()


def _normalize_cli_title_text(value: str) -> str:
    """Replace control characters so projected titles remain valid record text."""

    value = re.sub(
        r"\x1b(?:\[[0-?]*[ -/]*[@-~]|\][^\x07]*(?:\x07|\x1b\\)|[@-_])",
        "",
        value,
    )
    return "".join(" " if ord(character) < 0x20 or 0x7F <= ord(character) <= 0x9F else character for character in value)


def _cli_title_lines(value: str) -> list[str]:
    """Split provider lines without treating non-newline controls as separators."""

    return [_normalize_cli_title_text(line) for line in re.split(r"\r\n?|\n", value)]


def _cli_instruction_text(value: Any) -> str:
    if isinstance(value, str):
        return value
    if isinstance(value, list):
        return "\n".join(item for item in value if isinstance(item, str))
    return ""


def _cli_lines_after_locator(value: Any) -> tuple[list[str], bool]:
    """Return normalized instruction lines after a provider locator, if present."""

    candidates = _cli_title_lines(_cli_instruction_text(value))
    for index, line in enumerate(candidates):
        if line.strip().startswith("Review comment at @"):
            return candidates[index + 1 :], True
    return candidates, False


def _cli_detail(value: Any) -> str:
    """Drop the safety preamble when a CLI finding has a file/line locator."""

    candidates, has_locator = _cli_lines_after_locator(value)
    if has_locator:
        return "\n".join(candidates)
    return _cli_instruction_text(value)


def _cli_finding_title(value: Any, fallback: str) -> str:
    """Project one CLI finding title for runner and recovery persistence."""

    title = _cli_headline(value) or ""
    title = _normalize_cli_title_text(title)
    title = re.sub(r"[ \t\r\n\f\v]+", " ", title).strip()
    title, _ = sqlite_review_records._redact_archive_text(title)
    title = re.sub(r"[ \t\r\n\f\v]+", " ", title).strip()
    title = title[:300].rstrip()
    return title or fallback


def _validate_counts(
    checkpoint: evidence.Checkpoint,
    raw_count: int,
    decisions: dict[int, tuple[str, str]],
) -> None:
    if checkpoint.raw_found != raw_count:
        raise ProviderImportError("checkpoint raw count does not match captured provider findings")
    if len(decisions) != raw_count or set(decisions) != set(range(1, raw_count + 1)):
        raise ProviderImportError("source decisions do not map one-to-one to captured findings")
    accepted = sum(disposition == "accepted" for disposition, _ in decisions.values())
    routed = sum(disposition == "routed" for disposition, _ in decisions.values())
    if accepted != checkpoint.accepted or (checkpoint.routed is not None and routed != checkpoint.routed):
        raise ProviderImportError("checkpoint counts do not match recorded provider decisions")


def _observations_and_decisions(
    *,
    channel: Literal["hosted", "cli"],
    origin: int | str,
    actor: str,
    raw_findings: list[dict[str, Any]],
    source_decisions: dict[int, tuple[str, str]],
) -> tuple[tuple[FindingObservation, ...], tuple[dict[str, Any], ...]]:
    observations: list[FindingObservation] = []
    decisions: list[dict[str, Any]] = []
    for index, finding in enumerate(raw_findings, 1):
        decision, reason = source_decisions[index]
        if not reason.strip() or len(reason) > 300:
            raise ProviderImportError(f"recorded decision for finding {index} has no bounded reason")
        key = finding["key"]
        observations.append(
            FindingObservation(
                source_finding_key=key,
                title=finding["title"],
                detail=finding.get("detail", ""),
            )
        )
        decisions.append(
            {
                "source_finding_key": key,
                "decision_id": _stable_id("decision", channel, origin, key),
                "decision": decision,
                "actor": actor,
                "reason": reason,
                "target_pr": (finding["legacy_route"].target_pr if finding.get("legacy_route") is not None else None),
                "route_id": finding.get("legacy_route_id"),
                "route_status": (finding["legacy_route"].status if finding.get("legacy_route") is not None else None),
            }
        )
    return tuple(observations), tuple(decisions)


def _preflight_text(actor: str, decisions: Sequence[dict[str, Any]]) -> None:
    # Reuse the records API's public observation validation to reject secret-like
    # provider text before creating any persistent rows.
    if not isinstance(actor, str) or len(actor) > 100:
        raise ProviderImportError("decision actor must be text of at most 100 characters")
    FindingObservation("preflight-actor", "preflight", detail=actor)
    for decision in decisions:
        FindingObservation("preflight-reason", "preflight", detail=decision["reason"])


def _stable_id(kind: str, channel: str, origin: int | str, finding_key: str) -> str:
    identity = "\0".join((kind, channel, str(origin), finding_key))
    return uuid.uuid5(_IMPORT_NAMESPACE, identity).hex


def _persist_import(
    records: SqliteReviewRecords,
    *,
    repo: str,
    pr_number: int,
    channel: Literal["hosted", "cli"],
    origin: int | str,
    source_head: str,
    reviewer: str,
    scope: Literal["broad", "narrow"],
    coverage_limits: Sequence[str],
    started_at: str | None,
    finished_at: str | None,
    observations: tuple[FindingObservation, ...],
    decisions: tuple[dict[str, Any], ...],
    actor: str,
) -> dict[str, Any]:
    run_id = uuid.uuid5(
        _IMPORT_NAMESPACE,
        "\0".join((repo.casefold(), str(pr_number), channel, str(origin))),
    ).hex
    try:
        # Historical importer versions sometimes stored a less useful title or
        # detail for the same provider finding. Keep that original projection
        # immutable while allowing a newer importer to replay the exact run,
        # decisions, and archived evidence. All other run and decision fields
        # remain subject to import_completed_run's normal conflict checks.
        history = records.history(pr_number)
        existing_run = next((item for item in history["runs"] if item["run_id"] == run_id), None)
        if existing_run is not None:
            persisted = {item["source_finding_key"]: item for item in history["findings"] if item["run_id"] == run_id}
            supplied = {item.source_finding_key: item for item in observations}
            finding_keys = {item["finding_id"]: item["source_finding_key"] for item in persisted.values()}
            original_decisions = {
                finding_keys[item["finding_id"]]: item
                for item in history["decisions"]
                if item["run_id"] == run_id and item["scope"] == "source" and item["finding_id"] in finding_keys
            }
            expected_counts = {
                "found": len(persisted),
                "accepted": sum(item["decision"] == "accepted" for item in original_decisions.values()),
                "routed": sum(item["decision"] == "routed" for item in original_decisions.values()),
            }
            supplied_decisions = {item["source_finding_key"]: item["decision"] for item in decisions}
            if (
                set(persisted) != set(supplied)
                or set(original_decisions) != set(persisted)
                or len(decisions) != len(original_decisions)
                or supplied_decisions != {key: item["decision"] for key, item in original_decisions.items()}
                or {
                    "found": len(observations),
                    "accepted": sum(item["decision"] == "accepted" for item in decisions),
                    "routed": sum(item["decision"] == "routed" for item in decisions),
                }
                != expected_counts
            ):
                raise ProviderImportError("run_id was already imported with different finding identity or decisions")
            current_decisions = {key: item["decision"] for key, item in original_decisions.items()}
            for correction in history["corrections"]:
                if correction["run_id"] != run_id:
                    continue
                finding_key = finding_keys.get(correction["finding_id"])
                if finding_key is not None:
                    current_decisions[finding_key] = correction["decision"]
            current_counts = {
                "found": len(current_decisions),
                "accepted": sum(item == "accepted" for item in current_decisions.values()),
                "routed": sum(item == "routed" for item in current_decisions.values()),
            }
            if existing_run["counts"] != current_counts:
                raise ProviderImportError(
                    "run_id has counts that conflict with its immutable decisions and correction history"
                )
            observations = tuple(
                dataclasses.replace(
                    observation,
                    title=persisted[observation.source_finding_key]["title"],
                    detail=persisted[observation.source_finding_key]["detail"],
                )
                for observation in observations
            )
        imported = records.import_completed_run(
            run_id=run_id,
            source_pr=pr_number,
            channel=channel,
            findings=observations,
            source_decisions=decisions,
            source_head=source_head,
            reviewer=reviewer,
            scope=scope,
            coverage_limits=coverage_limits,
            started_at=started_at,
            finished_at=finished_at,
        )
    except ReviewRecordsError as exc:
        raise ProviderImportError(str(exc)) from exc
    return {**imported, "channel": channel}
