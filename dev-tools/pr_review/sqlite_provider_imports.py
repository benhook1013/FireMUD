"""Import completed provider review evidence into SQLite review records.

Provider captures remain in their private capture directories. This module
stores only bounded finding headlines and source decisions after the existing
checkpoint and capture readers prove one completed, attributable run.
"""

from __future__ import annotations

import re
import uuid
from collections.abc import Mapping, Sequence
from pathlib import Path
from typing import Any, Literal

from . import evidence
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

    return _persist_import(
        records,
        repo=repo,
        pr_number=pr_number,
        channel="hosted",
        origin=review_id,
        source_head=source_head,
        reviewer=capture.review["user"]["login"],
        scope=scope,
        coverage_limits=coverage_limits,
        started_at=None,
        finished_at=capture.review["submitted_at"],
        observations=observations,
        decisions=decisions,
        actor=actor,
    )


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
    Captured stdout and metadata are read only and are never copied into SQLite.
    Routed findings keep their recorded reason but remain unassigned; the free-
    text reason is not treated as a structured target PR identity.
    """

    _validate_checkpoint(checkpoint, "CLI")
    if checkpoint.run_id is None:
        raise ProviderImportError("CLI checkpoint has no exact run marker")
    capture = evidence.load_cli_capture(checkpoint, repo, pr_number, common)
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

    return _persist_import(
        records,
        repo=repo,
        pr_number=pr_number,
        channel="cli",
        origin=checkpoint.run_id,
        source_head=source_head,
        reviewer="CodeRabbit CLI",
        scope=scope,
        coverage_limits=coverage_limits,
        started_at=None,
        finished_at=None,
        observations=observations,
        decisions=decisions,
        actor=actor,
    )


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
                raise ProviderImportError(
                    f"summary disposition references unavailable legacy route {route_id}"
                )
            if (
                route.source_pr != disposition.pr
                or route.source_channel != "hosted"
                or route.source_review != f"summary:{disposition.source}:{disposition.summary_id}"
                or not route.source_finding.startswith(f"{disposition.kind}:")
            ):
                raise ProviderImportError(
                    f"legacy route {route_id} does not match its exact summary disposition"
                )
            reference = route.source_finding[len(disposition.kind) + 1 :]
            if reference.startswith("ref:"):
                reference_valid = bool(reference[4:].strip())
            else:
                match = re.fullmatch(r"(?P<count>[1-9][0-9]*):(?P<reference>.+)", reference)
                reference_valid = bool(
                    match
                    and int(match.group("count")) == disposition.count
                    and match.group("reference").strip()
                )
            if not reference_valid:
                raise ProviderImportError(
                    f"legacy route {route_id} does not match its exact summary finding bucket"
                )
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
        raise ProviderImportError(
            f"summary-only {kind} bucket ({count}) has no exact structural disposition"
        )
    disposition = candidates[0]
    if disposition.count != count:
        raise ProviderImportError(
            f"summary-only {kind} disposition count {disposition.count} does not match captured count {count}"
        )
    if disposition.decision == "routed" and len(disposition.route_ids) != count:
        raise ProviderImportError(
            f"routed summary-only {kind} bucket requires one stable route ID per item"
        )
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
        title = _first_line(comment.get("body"))
        if title is None:
            raise ProviderImportError("Hosted finding has no bounded title")
        if len(title) > 300:
            raise ProviderImportError("Hosted finding headline exceeds the SQLite title limit")
        result.append({"key": f"hosted-comment:{comment['id']}", "title": title, "id": comment["id"]})
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
                        "legacy_route_id": (
                            disposition.route_ids[ordinal - 1]
                            if decision[0] == "routed"
                            else None
                        ),
                        "legacy_route": (
                            legacy_routes[disposition.route_ids[ordinal - 1]]
                            if decision[0] == "routed"
                            else None
                        ),
                    }
                )
                ordered_decisions[len(result)] = decision
    return result, ordered_decisions


def _cli_findings(capture: evidence.CaptureData) -> list[dict[str, Any]]:
    result: list[dict[str, Any]] = []
    for index, finding in enumerate(capture.findings, 1):
        title = _cli_headline(finding.get("codegenInstructions"))
        if title is None:
            raise ProviderImportError(f"CLI finding {index} has no bounded title")
        if len(title) > 300:
            raise ProviderImportError(f"CLI finding {index} headline exceeds the SQLite title limit")
        # The existing decision file addresses findings by this 1-based event
        # ordinal; retain that canonical identity instead of hashing content.
        result.append({"key": f"cli-run:{capture.metadata['run_id']}:finding:{index}", "title": title})
    if capture.unlinked_decisions:
        raise ProviderImportError("CLI capture contains unlinked source decisions")
    return result


def _cli_headline(value: Any) -> str | None:
    """Extract a short headline from CLI instructions without storing the prompt."""

    if isinstance(value, str):
        candidates = value.splitlines()
    elif isinstance(value, list):
        candidates = [line for item in value if isinstance(item, str) for line in item.splitlines()]
    else:
        return None
    headline = next((line.strip() for line in candidates if line.strip()), None)
    if headline is None:
        return None
    # CLI codegen instructions are full provider prompts. Store only their
    # first bounded headline; never persist the remaining raw instructions.
    return headline[:180].rstrip()


def _first_line(value: Any) -> str | None:
    if not isinstance(value, str):
        return None
    return next((line.strip() for line in value.splitlines() if line.strip()), None)


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
                "target_pr": (
                    finding["legacy_route"].target_pr
                    if finding.get("legacy_route") is not None
                    else None
                ),
                "route_id": finding.get("legacy_route_id"),
                "route_status": (
                    finding["legacy_route"].status
                    if finding.get("legacy_route") is not None
                    else None
                ),
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
