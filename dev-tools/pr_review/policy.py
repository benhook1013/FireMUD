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

    def to_dict(self) -> dict[str, Any]:
        return {
            "channel": self.channel.value,
            "target": self.target,
            "status": self.status.value,
            "reason": self.reason,
            "provisional": self.provisional,
        }


def _override(state: ReviewState, pr: int, channel: Channel, evidence: Evidence) -> PolicyOverride | None:
    candidate = state.policy_overrides.get(f"{pr}:{channel.value}")
    if not candidate or not candidate.applies(evidence.head, evidence.checkpoint, evidence.patch_id):
        return None
    # An override can shorten the required dry streak, never turn malformed,
    # accepted, provisional, or uncorrected evidence into a completed round.
    if not _valid_complete(evidence, channel) or evidence.corrected_state is not True or evidence.accepted != 0:
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


def _same_head_provisional_barrier(history: Sequence[Evidence], reviews: Sequence[Evidence]) -> bool:
    """Return whether a newer provisional checkpoint blocks an older taper."""

    if not reviews:
        return False
    last_review = max(
        (
            index
            for index, item in enumerate(history)
            if not item.correction
            and not item.non_counting
            and item.completed is True
            and item.attributable is True
            and item.provisional is False
        ),
        default=-1,
    )
    last_provisional = max(
        (
            index
            for index, item in enumerate(history)
            if not item.correction and not item.non_counting and item.provisional
        ),
        default=-1,
    )
    return (
        last_provisional > last_review
        and history[last_provisional].head == reviews[-1].head
    )


def taper_satisfied(
    channel: Channel | str,
    history: Iterable[Evidence | Mapping[str, Any]],
    required: int,
    *,
    allow_uncorrected_state: bool = False,
    retained_patch_id: str | None = None,
) -> bool:
    """Return true only for a trailing run of completed, non-provisional zero-accepted reviews."""

    if required < 0:
        raise ValueError("required taper must be non-negative")
    if allow_uncorrected_state and not retained_patch_id:
        return False
    materialized = [Evidence.from_value(value) for value in history]
    materialized = [item for item in materialized if not item.non_counting]
    values = _review_entries(materialized)
    if _same_head_provisional_barrier(materialized, values):
        return False
    if not values:
        return required == 0
    latest_head = values[-1].head
    for count, item in enumerate(reversed(values), start=1):
        if item.head != latest_head:
            break
        if allow_uncorrected_state and item.patch_id != retained_patch_id:
            break
        if not _valid_complete(item, channel, require_corrected_state=not allow_uncorrected_state):
            break
        if item.accepted != 0:
            break
        if count >= required:
            return True
    return required == 0


def _blocked(evidence: Evidence, reconciliation: ReconciliationStatus | str | None) -> ReviewStatus | None:
    status = reconciliation.value if isinstance(reconciliation, ReconciliationStatus) else reconciliation
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


def completion_status(
    state: ReviewState,
    channel: Channel | str,
    evidence: Sequence[Evidence | Mapping[str, Any]],
    *,
    reconciliation: ReconciliationStatus | str | None = None,
    other_channel_head: str | None = None,
) -> ReviewStatus:
    """Classify one channel without consulting GitHub or copying live state."""

    selected = Channel(channel)
    all_items = [Evidence.from_value(value) for value in evidence]
    if len({item.pr for item in all_items}) > 1:
        return ReviewStatus.MISSING_EVIDENCE
    history = [item for item in all_items if not item.correction and not item.non_counting]
    if not history:
        if all_items and any(item.non_counting for item in all_items):
            return ReviewStatus.READY
        return ReviewStatus.MISSING_EVIDENCE
    for item in history:
        blocked = _blocked(item, None)
        if blocked:
            return blocked
    reviews = _review_entries(history)
    blocked = _blocked(Evidence(history[0].pr, "", ""), reconciliation)
    if blocked:
        return blocked
    if not reviews:
        return ReviewStatus.READY
    if _same_head_provisional_barrier(history, reviews):
        return ReviewStatus.READY
    latest = reviews[-1]
    reconciliation_value = reconciliation.value if isinstance(reconciliation, ReconciliationStatus) else reconciliation
    if reconciliation_value == ReconciliationStatus.PATCH_CHANGED.value:
        return ReviewStatus.READY
    if latest.anchored is not True:
        return ReviewStatus.READY
    override = _override(state, latest.pr, selected, latest)
    required = 2 if selected == Channel.HOSTED else 3
    if override:
        value = override.hosted_zero_useful if selected == Channel.HOSTED else override.cli_zero_useful
        if value is not None:
            required = value
    retained_equivalent_history = False
    if reconciliation_value == ReconciliationStatus.EQUIVALENT_HISTORY.value:
        judgment = _judgment(state, selected, latest)
        if judgment is None:
            return ReviewStatus.JUDGMENT_REQUIRED
        if judgment.decision == "reopen":
            return ReviewStatus.READY
        # A retain judgment explicitly binds the latest reviewed checkpoint to
        # the live, equivalent patch.  It may preserve a valid review streak
        # whose old-head evidence predates the corrected-state annotation; it
        # must not manufacture corrected evidence for unrelated histories.
        retained_equivalent_history = judgment.decision == "retain"
    if not latest.corrected_state and not retained_equivalent_history:
        return ReviewStatus.MISSING_EVIDENCE
    if taper_satisfied(
        selected,
        history,
        required,
        allow_uncorrected_state=retained_equivalent_history,
        retained_patch_id=latest.patch_id if retained_equivalent_history else None,
    ):
        judgment = _judgment(state, selected, latest)
        if judgment and judgment.decision == "reopen":
            return ReviewStatus.READY
        if other_channel_head is not None and other_channel_head != latest.head and not judgment:
            return ReviewStatus.JUDGMENT_REQUIRED
        return ReviewStatus.COMPLETE
    return ReviewStatus.READY


def select_review_target(
    state: ReviewState,
    channel: Channel | str,
    live_prs: Sequence[int],
    evidence_by_pr: Mapping[int, Sequence[Evidence | Mapping[str, Any]]],
    *,
    reconciliation_by_pr: Mapping[int, ReconciliationStatus | str] | None = None,
    other_channel_heads: Mapping[int, str] | None = None,
) -> ChannelDecision:
    """Derive the next target; callers still perform live GitHub/quota operations."""

    selected = Channel(channel)
    reconciliation_by_pr = reconciliation_by_pr or {}
    other_channel_heads = other_channel_heads or {}
    for pr in live_prs:
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
        blocked = None
        for item in evidence:
            blocked = _blocked(item, None)
            if blocked:
                break
        if blocked is None:
            blocked = _blocked(latest, reconciliation_by_pr.get(pr))
        if blocked:
            return ChannelDecision(selected, pr, blocked, f"{pr} is {blocked.value.lower()}", latest.provisional)
        status = completion_status(
            state,
            selected,
            history,
            reconciliation=reconciliation_by_pr.get(pr),
            other_channel_head=other_channel_heads.get(pr),
        )
        if status == ReviewStatus.COMPLETE:
            continue
        return ChannelDecision(
            selected,
            pr,
            status,
            f"{pr} is the earliest incomplete {selected.value} target",
            latest.provisional or status == ReviewStatus.PROVISIONAL,
        )
    return ChannelDecision(selected, None, ReviewStatus.COMPLETE, f"all {selected.value} targets are complete")
