"""Review taper and target-selection policy over caller-supplied live evidence."""

from __future__ import annotations

import dataclasses
from collections.abc import Iterable, Mapping, Sequence
from enum import Enum
from typing import Any

from .stack import ReconciliationStatus
from .state import Judgment, PolicyOverride, ReviewState


class Channel(str, Enum):
    HOSTED = "hosted"
    CLI = "cli"


class ReviewStatus(str, Enum):
    READY = "READY"
    COMPLETE = "COMPLETE"
    RATE_LIMITED = "RATE_LIMITED"
    MISSING_EVIDENCE = "MISSING_EVIDENCE"
    HELD = "HELD"
    UNSTABLE = "UNSTABLE"
    UNRECONCILED = "UNRECONCILED"
    PARENT_MOVED = "PARENT_MOVED"
    OVER_CEILING = "OVER_CEILING"
    JUDGMENT_REQUIRED = "JUDGMENT_REQUIRED"
    PROVISIONAL = "PROVISIONAL"
    ALLOCATION_EXHAUSTED = "ALLOCATION_EXHAUSTED"
    HUMAN_STOPPED = "HUMAN_STOPPED"


@dataclasses.dataclass(frozen=True)
class Evidence:
    """Small adapter model for live checkpoint/PR observations."""

    pr: int
    head: str
    checkpoint: str
    patch_id: str | None = None
    completed: bool = False
    attributable: bool = False
    anchored: bool | None = None
    accepted: int = 0
    raw: int = 0
    correction: bool = False
    corrected_state: bool = False
    provisional: bool = False
    rate_limited: bool = False
    held: bool = False
    unstable: bool = False
    unreconciled: bool = False
    over_ceiling: bool = False
    parent_moved: bool = False
    non_counting: bool = False
    streak_break_before: bool = False
    lineage_proven_to_next: bool = False
    current_candidate_descendant_proven: bool = False
    scope_timeline: bool = False
    scope_timeline_complete: bool = False
    active_review: bool = False
    active_reservation: bool = False

    @classmethod
    def from_value(cls, value: Evidence | Mapping[str, Any]) -> Evidence:
        if isinstance(value, cls):
            return value
        aliases = {
            "number": "pr",
            "reviewed_head": "head",
            "checkpoint_id": "checkpoint",
            "patch_identity": "patch_id",
        }
        data = {aliases.get(key, key): item for key, item in value.items()}
        return cls(**{field.name: data[field.name] for field in dataclasses.fields(cls) if field.name in data})


@dataclasses.dataclass(frozen=True)
class ChannelDecision:
    channel: Channel
    target: int | None
    status: ReviewStatus
    reason: str
    provisional: bool = False
    taper_complete: bool = False
    deferred_terminal: bool = False

    def to_dict(self) -> dict[str, Any]:
        return {
            "channel": self.channel.value,
            "target": self.target,
            "status": self.status.value,
            "reason": self.reason,
            "provisional": self.provisional,
            "taper_complete": self.taper_complete,
        }


def _override(state: ReviewState, pr: int, channel: Channel, evidence: Evidence) -> PolicyOverride | None:
    candidate = state.policy_overrides.get(f"{pr}:{channel.value}")
    if not candidate or not candidate.applies(evidence.head, evidence.checkpoint, evidence.patch_id):
        return None
    # The decision was validated against corrected evidence when recorded. Later
    # head movement may change current correction state without revoking that credit.
    if not _valid_complete(evidence, channel, require_corrected_state=False) or evidence.accepted != 0:
        return None
    return candidate


def _judgment(state: ReviewState, channel: Channel, evidence: Evidence) -> Judgment | None:
    for candidate in reversed(state.judgments):
        if candidate.applies(evidence.pr, channel.value, evidence.head, evidence.checkpoint, evidence.patch_id):
            return candidate
    return None


def _valid_complete(
    evidence: Evidence,
    channel: Channel | str | None = None,
    *,
    require_corrected_state: bool = True,
) -> bool:
    """Return whether one checkpoint is eligible to contribute to taper."""

    return (
        evidence.completed is True
        and evidence.attributable is True
        and evidence.anchored is True
        and evidence.provisional is False
        and (not require_corrected_state or evidence.corrected_state is True)
    )


def _review_entries(history: Iterable[Evidence | Mapping[str, Any]]) -> list[Evidence]:
    """Return attributable completed checkpoints, excluding status observations."""

    return [
        item
        for value in history
        if not (item := Evidence.from_value(value)).correction
        and not item.non_counting
        and item.completed is True
        and item.attributable is True
        and item.provisional is False
    ]


def taper_satisfied(
    channel: Channel | str,
    history: Iterable[Evidence | Mapping[str, Any]],
    required: int,
    *,
    allow_uncorrected_state: bool = False,
    retained_patch_id: str | None = None,
    require_corrected_state: bool = False,
) -> bool:
    """Return whether the required zero-accepted review streak has completed.

    Completion is historical and sticky: later head or base movement, fixes,
    useful allocated results, and non-counting observations cannot erase a
    taper already reached. Review identity protects request safety, not
    per-PR/channel taper credit. Non-counting, incomplete, and otherwise
    ineligible observations do not break a streak; an attributable completed
    result with accepted findings does.
    """

    if required < 0:
        raise ValueError("required taper must be non-negative")
    Channel(channel)
    if allow_uncorrected_state and not retained_patch_id:
        return False
    materialized = [Evidence.from_value(value) for value in history]
    materialized = [item for item in materialized if not item.non_counting]
    values = _review_entries(materialized)
    if not values:
        return required == 0

    streak = 0
    reached = required == 0
    for item in values:
        # Accepted attributable results reopen useful work even when an
        # incomplete anchor or corrected-state marker keeps that row from
        # counting as a taper round.
        if item.accepted != 0:
            streak = 0
            continue
        if not _valid_complete(item, channel, require_corrected_state=require_corrected_state):
            continue
        if allow_uncorrected_state and item.corrected_state is not True and item.patch_id != retained_patch_id:
            # A retain judgment only makes uncorrected evidence eligible on
            # its exact patch. A mismatched row is ignored, not a streak break.
            continue
        streak += 1
        if streak >= required:
            reached = True
    return reached


def fresh_taper_history(
    state: ReviewState,
    channel: Channel | str,
    history: Iterable[Evidence | Mapping[str, Any]],
    *,
    baseline_checkpoints: Iterable[str] = (),
) -> list[Evidence]:
    """Return only review evidence eligible for the current fresh taper.

    Completed taper remains durable until a human explicitly reopens a channel
    or grants/renews an allocation after completion. Those decisions establish
    a new baseline; older dry results remain historical but cannot count toward
    the fresh streak.
    """

    selected = Channel(channel)
    materialized = [Evidence.from_value(value) for value in history]
    relevant = [item for item in materialized if not item.correction and not item.non_counting]
    pr_numbers = {item.pr for item in relevant}
    if len(pr_numbers) != 1:
        return relevant
    pr = next(iter(pr_numbers))

    reopen = next(
        (
            judgment
            for judgment in reversed(state.judgments)
            if judgment.pr == pr and judgment.channel == selected.value and judgment.decision == "reopen"
        ),
        None,
    )
    if reopen is not None:
        matches = [
            index
            for index, item in enumerate(relevant)
            if item.pr == reopen.pr
            and item.head == reopen.head
            and item.checkpoint == reopen.checkpoint
            and item.patch_id == reopen.patch_id
        ]
        # A missing or ambiguous decision anchor must not let old evidence
        # satisfy the fresh streak.
        if len(matches) != 1:
            relevant = []
        else:
            relevant = relevant[matches[0] + 1 :]

    baseline = set(baseline_checkpoints)
    if baseline:
        relevant = [item for item in relevant if item.checkpoint not in baseline]
    return relevant


def taper_satisfied_for_state(
    state: ReviewState,
    channel: Channel | str,
    history: Iterable[Evidence | Mapping[str, Any]],
    *,
    baseline_checkpoints: Iterable[str] = (),
) -> bool:
    """Evaluate taper after any exact human-reopen or allocation baseline."""

    selected = Channel(channel)
    values = fresh_taper_history(
        state,
        selected,
        history,
        baseline_checkpoints=baseline_checkpoints,
    )
    return taper_satisfied(selected, values, required_taper(state, selected, values))


def required_taper(
    state: ReviewState,
    channel: Channel | str,
    history: Iterable[Evidence | Mapping[str, Any]],
) -> int:
    """Return the latest exact-bound taper threshold still in this history.

    A human override stays in force for later checkpoints in the same fresh
    taper history. Its exact evidence binding is validated when written; an
    explicit reopen or allocation baseline removes that binding from the
    fresh history and returns the channel to its default threshold.
    """

    selected = Channel(channel)
    reviews = _review_entries(history)
    required = 1 if selected == Channel.HOSTED else 3
    if reviews:
        current_pr = reviews[-1].pr
        for item in reversed(reviews):
            if item.pr != current_pr:
                continue
            override = _override(state, current_pr, selected, item)
            if override:
                value = override.hosted_zero_useful if selected == Channel.HOSTED else override.cli_zero_useful
                if value is not None:
                    return value
    return required


def _blocked(evidence: Evidence, reconciliation: ReconciliationStatus | str | None) -> ReviewStatus | None:
    status = reconciliation.value if isinstance(reconciliation, ReconciliationStatus) else reconciliation
    if evidence.active_review or evidence.active_reservation:
        return ReviewStatus.HELD
    if evidence.rate_limited:
        return ReviewStatus.RATE_LIMITED
    if evidence.held:
        return ReviewStatus.HELD
    if evidence.unstable:
        return ReviewStatus.UNSTABLE
    if evidence.parent_moved or status == ReconciliationStatus.PARENT_MOVED.value:
        return ReviewStatus.PARENT_MOVED
    if evidence.unreconciled or status == ReconciliationStatus.UNRECONCILED.value:
        return ReviewStatus.UNRECONCILED
    if evidence.over_ceiling:
        return ReviewStatus.OVER_CEILING
    return None


def _only_verified_terminal_hosted_ambiguity(values: Sequence[Evidence | Mapping[str, Any]]) -> bool:
    """Identify a finished, non-counting result that need not idle later PRs."""

    blockers = [value for value in values if _blocked(Evidence.from_value(value), None)]
    return bool(blockers) and all(
        isinstance(value, Mapping)
        and value.get("terminal_ambiguous") is True
        and value.get("terminal") is True
        and value.get("attributable") is False
        and value.get("state") == "ambiguous"
        and type(value.get("trigger_id")) is int
        and value["trigger_id"] > 0
        and type(value.get("response_id")) is int
        and value["response_id"] > 0
        and isinstance(value.get("fingerprint"), str)
        and len(value["fingerprint"]) == 64
        and all(character in "0123456789abcdef" for character in value["fingerprint"])
        for value in blockers
    )


def completion_status(
    state: ReviewState,
    channel: Channel | str,
    evidence: Sequence[Evidence | Mapping[str, Any]],
    *,
    taper_history: Sequence[Evidence | Mapping[str, Any]] | None = None,
    reconciliation: ReconciliationStatus | str | None = None,
) -> ReviewStatus:
    """Classify one channel without consulting GitHub or copying live state."""

    selected = Channel(channel)
    all_items = [Evidence.from_value(value) for value in evidence]
    if len({item.pr for item in all_items}) > 1:
        return ReviewStatus.MISSING_EVIDENCE
    history = [item for item in all_items if not item.correction and not item.non_counting]
    if not history:
        if all_items:
            reconciliation_blocker = _blocked(Evidence(all_items[0].pr, "", ""), reconciliation)
            if reconciliation_blocker:
                return reconciliation_blocker
        if all_items and all(
            item.non_counting and item.scope_timeline and item.scope_timeline_complete for item in all_items
        ):
            return ReviewStatus.MISSING_EVIDENCE
        if all_items and any(item.non_counting for item in all_items):
            return ReviewStatus.READY
        return ReviewStatus.MISSING_EVIDENCE
    reviews = _review_entries(history)
    reconciliation_blocker = _blocked(Evidence(history[0].pr, "", ""), reconciliation)
    if not reviews:
        for item in history:
            blocked = _blocked(item, None)
            if blocked:
                return blocked
        return reconciliation_blocker or ReviewStatus.READY
    latest = reviews[-1]
    latest_judgment = _judgment(state, selected, latest)
    effective_taper_history = (
        taper_history if taper_history is not None else fresh_taper_history(state, selected, history)
    )
    required = required_taper(state, selected, effective_taper_history)
    reopened = latest_judgment is not None and latest_judgment.decision == "reopen"
    taper_complete = not reopened and taper_satisfied(selected, effective_taper_history, required)
    request_blockers = {
        ReviewStatus.RATE_LIMITED,
        ReviewStatus.UNSTABLE,
        ReviewStatus.UNRECONCILED,
        ReviewStatus.PARENT_MOVED,
        ReviewStatus.OVER_CEILING,
    }
    for item in history:
        blocked = _blocked(item, None)
        if blocked and not (taper_complete and blocked in request_blockers):
            return blocked
    if reopened:
        return ReviewStatus.READY
    if taper_complete:
        return ReviewStatus.COMPLETE
    if reconciliation_blocker:
        return reconciliation_blocker
    return ReviewStatus.READY


def select_review_target(
    state: ReviewState,
    channel: Channel | str,
    live_prs: Sequence[int],
    evidence_by_pr: Mapping[int, Sequence[Evidence | Mapping[str, Any]]],
    *,
    reconciliation_by_pr: Mapping[int, ReconciliationStatus | str] | None = None,
    handed_off_prs: Iterable[int] = (),
    human_stopped_prs: Iterable[int] = (),
    exhausted_prs: Iterable[int] = (),
    allocation_blocks: Mapping[int, str] | None = None,
    allocation_holds: Mapping[int, str] | None = None,
    allocation_reopen_prs: Iterable[int] = (),
    taper_history_by_pr: Mapping[int, Sequence[Evidence | Mapping[str, Any]]] | None = None,
    active_review_prs: Iterable[int] = (),
) -> ChannelDecision:
    """Derive the next target; callers still perform live GitHub/quota operations."""

    selected = Channel(channel)
    reconciliation_by_pr = reconciliation_by_pr or {}
    handed_off = set(handed_off_prs)
    human_stopped = set(human_stopped_prs)
    exhausted = set(exhausted_prs)
    allocation_blocks = allocation_blocks or {}
    allocation_holds = allocation_holds or {}
    allocation_reopen = set(allocation_reopen_prs)
    active_reviews = set(active_review_prs)
    taper_history_by_pr = taper_history_by_pr or {}
    encountered_human_stop = False
    completed_taper_seen = False
    deferred_hosted_pr: int | None = None

    def completed_taper(pr: int) -> bool:
        values = evidence_by_pr.get(pr, ())
        if pr in taper_history_by_pr:
            taper_values = taper_history_by_pr[pr]
            return taper_satisfied(
                selected,
                taper_values,
                required_taper(state, selected, taper_values),
            )
        return taper_satisfied_for_state(state, selected, values)

    for pr in live_prs:
        if pr in handed_off:
            continue
        if pr in active_reviews:
            return ChannelDecision(
                selected,
                pr,
                ReviewStatus.HELD,
                f"{pr} has an active review or reservation in a review channel",
                taper_complete=completed_taper(pr),
            )
        if pr in human_stopped:
            encountered_human_stop = True
            continue
        if pr in allocation_blocks:
            return ChannelDecision(
                selected,
                pr,
                ReviewStatus.JUDGMENT_REQUIRED,
                allocation_blocks[pr],
                taper_complete=completed_taper(pr),
            )
        if pr in allocation_holds:
            return ChannelDecision(
                selected,
                pr,
                ReviewStatus.HELD,
                allocation_holds[pr],
                taper_complete=completed_taper(pr),
            )
        if pr in exhausted:
            return ChannelDecision(
                selected,
                pr,
                ReviewStatus.ALLOCATION_EXHAUSTED,
                f"{pr} has consumed its {selected.value} allocation; an explicit stop decision is required",
                taper_complete=completed_taper(pr),
            )
        history = evidence_by_pr.get(pr, ())
        all_items = [Evidence.from_value(value) for value in history]
        if any(item.pr != pr for item in all_items):
            return ChannelDecision(
                selected,
                pr,
                ReviewStatus.MISSING_EVIDENCE,
                f"{pr} has evidence bound to another PR",
            )
        evidence = [item for item in all_items if not item.correction and not item.non_counting]
        reviews = _review_entries(evidence)
        latest = reviews[-1] if reviews else Evidence(pr, "", "")
        taper_values = taper_history_by_pr.get(pr)
        if taper_values is None:
            taper_values = fresh_taper_history(state, selected, evidence)
        taper_complete = taper_satisfied(
            selected,
            taper_values,
            required_taper(state, selected, taper_values),
        )
        request_blockers = {
            ReviewStatus.RATE_LIMITED,
            ReviewStatus.UNSTABLE,
            ReviewStatus.UNRECONCILED,
            ReviewStatus.PARENT_MOVED,
            ReviewStatus.OVER_CEILING,
        }
        blocked = None
        for item in evidence:
            if taper_complete and (item.held or item.active_review or item.active_reservation):
                blocked = ReviewStatus.HELD
                break
            blocked = _blocked(item, None)
            if blocked:
                if taper_complete and pr not in allocation_reopen and blocked in request_blockers:
                    blocked = None
                    continue
                break
        if blocked is None:
            blocked = _blocked(latest, reconciliation_by_pr.get(pr))
        if taper_complete and pr not in allocation_reopen and blocked in request_blockers:
            blocked = None
        if blocked:
            if (
                selected == Channel.HOSTED
                and blocked in {ReviewStatus.HELD, ReviewStatus.UNSTABLE}
                and reconciliation_by_pr.get(pr) == ReconciliationStatus.COHERENT
                and _only_verified_terminal_hosted_ambiguity(history)
            ):
                # This PR still needs a counted result or an audited stop for
                # merge readiness. The provider has finished this request,
                # however, so later coherent PRs may use the Hosted window.
                if deferred_hosted_pr is None:
                    deferred_hosted_pr = pr
                continue
            return ChannelDecision(
                selected,
                pr,
                blocked,
                f"{pr} is {blocked.value.lower()}",
                latest.provisional,
                taper_complete,
            )
        if taper_complete and pr not in allocation_reopen:
            # Identity guards protect a new request; they do not undo a
            # completed per-PR/channel taper.
            completed_taper_seen = True
            continue
        status = completion_status(
            state,
            selected,
            history,
            taper_history=taper_values,
            reconciliation=reconciliation_by_pr.get(pr),
        )
        if status == ReviewStatus.COMPLETE:
            if pr in allocation_reopen:
                return ChannelDecision(
                    selected,
                    pr,
                    ReviewStatus.READY,
                    f"{pr} has unused bounded {selected.value} review capacity",
                    False,
                    taper_complete,
                )
            continue
        return ChannelDecision(
            selected,
            pr,
            status,
            f"{pr} is the earliest incomplete {selected.value} target",
            latest.provisional or status == ReviewStatus.PROVISIONAL,
            taper_complete,
        )
    if deferred_hosted_pr is not None:
        return ChannelDecision(
            selected,
            deferred_hosted_pr,
            ReviewStatus.HELD,
            f"{deferred_hosted_pr} has a terminal non-counting Hosted result and no later safe target",
            deferred_terminal=True,
        )
    if encountered_human_stop:
        return ChannelDecision(
            selected,
            None,
            ReviewStatus.HUMAN_STOPPED,
            f"all {selected.value} targets are complete or explicitly stopped",
            taper_complete=completed_taper_seen,
        )
    return ChannelDecision(
        selected,
        None,
        ReviewStatus.COMPLETE,
        f"all {selected.value} targets are complete",
        taper_complete=completed_taper_seen,
    )
