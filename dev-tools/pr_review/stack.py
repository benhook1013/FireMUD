"""Pure effective-parent derivation and stack reconciliation."""

from __future__ import annotations

import dataclasses
from collections.abc import Callable, Mapping, Sequence
from enum import Enum


class ReconciliationStatus(str, Enum):
    COHERENT = "COHERENT"
    PARENT_MOVED = "PARENT_MOVED"
    UNRECONCILED = "UNRECONCILED"
    EQUIVALENT_HISTORY = "EQUIVALENT_HISTORY"
    PATCH_CHANGED = "PATCH_CHANGED"


@dataclasses.dataclass(frozen=True)
class PRSnapshot:
    number: int
    head: str
    base_ref: str
    base_tip: str
    merged: bool = False
    head_ref: str | None = None


@dataclasses.dataclass(frozen=True)
class ParentLink:
    child_pr: int
    parent_pr: int | None
    parent_ref: str
    parent_head: str

    @property
    def identity(self) -> str:
        return self.parent_ref if self.parent_pr is None else str(self.parent_pr)


@dataclasses.dataclass(frozen=True)
class ReviewAnchor:
    """Immutable identity attached to a completed review checkpoint."""

    pr: int
    child_head: str
    parent_identity: str
    parent_head: str
    merge_base: str
    patch_id: str


def classify_anchor(previous: ReviewAnchor, current: ReviewAnchor) -> ReconciliationStatus:
    """Classify a new live anchor without silently retaining old review evidence."""

    if previous.pr != current.pr:
        return ReconciliationStatus.UNRECONCILED
    if previous.parent_identity != current.parent_identity or previous.parent_head != current.parent_head:
        return ReconciliationStatus.PARENT_MOVED
    if previous.patch_id != current.patch_id:
        return ReconciliationStatus.PATCH_CHANGED
    if previous.child_head != current.child_head:
        return ReconciliationStatus.EQUIVALENT_HISTORY
    if previous.merge_base != current.merge_base:
        return ReconciliationStatus.UNRECONCILED
    return ReconciliationStatus.COHERENT


@dataclasses.dataclass(frozen=True)
class Reconciliation:
    status: ReconciliationStatus
    links: Mapping[int, ParentLink]
    affected_descendants: tuple[int, ...] = ()
    reasons: Mapping[int, str] = dataclasses.field(default_factory=dict)
    merge_bases: Mapping[int, str] = dataclasses.field(default_factory=dict)
    patch_ids: Mapping[int, str] = dataclasses.field(default_factory=dict)
    statuses: Mapping[int, ReconciliationStatus] = dataclasses.field(default_factory=dict)
    channel_statuses: Mapping[tuple[int, str], ReconciliationStatus] = dataclasses.field(default_factory=dict)
    legacy_transition_prs: tuple[int, ...] = ()
    legacy_transition_fingerprints: Mapping[int, Mapping[str, tuple[str, ...]]] = dataclasses.field(default_factory=dict)

    def status_for(self, pr: int, channel: str | None = None) -> ReconciliationStatus:
        status = self.statuses.get(pr)
        if status is None:
            status = (
                ReconciliationStatus.PARENT_MOVED
                if pr in self.affected_descendants
                else self.status if not self.statuses else ReconciliationStatus.COHERENT
            )
        # Parent movement and an unproven merge base describe the PR topology,
        # so neither review channel can proceed. Candidate-patch changes and
        # equivalent history belong only to the channel whose evidence changed.
        if status in {ReconciliationStatus.PARENT_MOVED, ReconciliationStatus.UNRECONCILED}:
            return status
        if channel is not None:
            selected = self.channel_statuses.get((pr, channel))
            if selected is not None:
                return selected
        return status

    def allowed(self, pr: int, *, allow_unreconciled: bool = False) -> bool:
        status = self.status_for(pr)
        return status == ReconciliationStatus.COHERENT or (
            allow_unreconciled and status == ReconciliationStatus.UNRECONCILED
        )


def effective_parent_links(
    ordered_prs: Sequence[int], snapshots: Mapping[int, PRSnapshot], default_base_ref: str, default_base_tip: str
) -> dict[int, ParentLink]:
    """Derive each child's nearest preceding unmerged parent, or the default base."""

    links: dict[int, ParentLink] = {}
    for index, pr in enumerate(ordered_prs):
        snapshot = snapshots[pr]
        parent: PRSnapshot | None = None
        for previous in reversed(ordered_prs[:index]):
            candidate = snapshots[previous]
            if not candidate.merged:
                parent = candidate
                break
        if parent is None:
            links[pr] = ParentLink(pr, None, default_base_ref, default_base_tip)
        else:
            links[pr] = ParentLink(pr, parent.number, parent.head_ref or str(parent.number), parent.head)
        if snapshot.number != pr:
            raise ValueError(f"snapshot number mismatch for PR {pr}")
    return links


def _descendants(ordered_prs: Sequence[int], links: Mapping[int, ParentLink], moved: set[int]) -> set[int]:
    changed = True
    while changed:
        changed = False
        for pr in ordered_prs:
            parent = links[pr].parent_pr
            if parent in moved and pr not in moved:
                moved.add(pr)
                changed = True
    return moved


def reconcile_stack(
    ordered_prs: Sequence[int],
    snapshots: Mapping[int, PRSnapshot],
    default_base_ref: str,
    default_base_tip: str,
    *,
    is_ancestor: Callable[[str, str], bool] | None = None,
    anchored_parent_heads: Mapping[int, str] | None = None,
    merge_bases: Mapping[int, str] | None = None,
    patch_ids: Mapping[int, str] | None = None,
) -> Reconciliation:
    """Check the live stack and mark every descendant of a moved parent.

    ``anchored_parent_heads`` is the parent head recorded by prior review evidence.
    It is intentionally supplied by the caller because evidence is live input, not
    persisted controller state.
    """

    links = effective_parent_links(ordered_prs, snapshots, default_base_ref, default_base_tip)
    reasons: dict[int, str] = {}
    moved: set[int] = set()
    unreconciled: set[int] = set()
    for pr in ordered_prs:
        child = snapshots[pr]
        if child.merged:
            continue
        link = links[pr]
        if child.base_ref != link.parent_ref:
            reasons[pr] = f"base {child.base_ref!r} does not match effective parent {link.parent_ref!r}"
        elif child.base_tip != link.parent_head:
            reasons[pr] = "child base tip does not equal the effective parent head"
            moved.add(pr)
        elif is_ancestor is not None and not is_ancestor(link.parent_head, child.head):
            reasons[pr] = "effective parent head is not an ancestor of the child head"
            unreconciled.add(pr)
        elif (
            anchored_parent_heads is not None
            and pr in anchored_parent_heads
            and anchored_parent_heads[pr] != link.parent_head
        ):
            reasons[pr] = "effective parent head moved since the anchored review evidence"
            moved.add(pr)
    affected = tuple(pr for pr in ordered_prs if pr in _descendants(ordered_prs, links, moved))
    statuses = {
        pr: ReconciliationStatus.PARENT_MOVED if pr in affected else ReconciliationStatus.UNRECONCILED
        for pr in set(reasons) | set(affected) | unreconciled
    }
    status = (
        ReconciliationStatus.PARENT_MOVED
        if affected
        else (ReconciliationStatus.UNRECONCILED if reasons else ReconciliationStatus.COHERENT)
    )
    return Reconciliation(status, links, affected, reasons, merge_bases or {}, patch_ids or {}, statuses)


def classify_patch_change(previous_patch_id: str | None, current_patch_id: str | None) -> ReconciliationStatus:
    """Classify review evidence identity changes without silently accepting history rewrites."""

    if previous_patch_id is None or current_patch_id is None:
        return ReconciliationStatus.UNRECONCILED
    if previous_patch_id == current_patch_id:
        return ReconciliationStatus.COHERENT
    return ReconciliationStatus.PATCH_CHANGED
