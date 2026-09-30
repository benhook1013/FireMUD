"""Locked, atomic persistence for the one private repository review stack.

Only operator configuration and explicit, identity-bound decisions are stored here.
Live GitHub heads, bases, evidence, cooldowns, and merge state deliberately do not
have a representation in :class:`ReviewState`.
"""

from __future__ import annotations

import contextlib
import dataclasses
import hashlib
import json
import os
import re
import subprocess
import tempfile
from collections.abc import Callable, Iterator, Mapping, Sequence
from pathlib import Path
from typing import Any

SCHEMA_VERSION = 1
EXACT_SHA = re.compile(r"^[0-9a-fA-F]{40}$")
FINGERPRINT = re.compile(r"^[0-9a-f]{64}$")


class StateError(ValueError):
    """Raised when private review-stack state is invalid or unavailable."""


def _fingerprint_value(value: Any) -> Any:
    """Return a deterministic JSON-compatible representation for one observation."""

    if dataclasses.is_dataclass(value) and not isinstance(value, type):
        return _fingerprint_value(dataclasses.asdict(value))
    if isinstance(value, Mapping):
        return {
            str(key): _fingerprint_value(item) for key, item in sorted(value.items(), key=lambda pair: str(pair[0]))
        }
    if isinstance(value, (list, tuple)):
        return [_fingerprint_value(item) for item in value]
    if value is None or isinstance(value, (str, int, float, bool)):
        return value
    raise StateError("legacy transition evidence contains an unsupported value")


def observation_fingerprint(value: Any) -> str:
    """Hash one immutable evidence observation for a legacy transition."""

    try:
        normalized = _fingerprint_value(value)
        # Historical two-count checkpoints had no routed field. Runtime
        # projections expose that unknown count as None; keep their identity
        # stable while retaining explicit modern routed counts in the hash.
        if isinstance(normalized, dict) and "routed" in normalized and normalized["routed"] is None:
            normalized.pop("routed")
        encoded = json.dumps(
            normalized,
            ensure_ascii=True,
            sort_keys=True,
            separators=(",", ":"),
            allow_nan=False,
        ).encode("utf-8")
    except (TypeError, ValueError) as exc:
        raise StateError("legacy transition evidence is not fingerprintable") from exc
    return hashlib.sha256(encoded).hexdigest()


@dataclasses.dataclass(frozen=True)
class PolicyOverride:
    """An optional taper override bound to one exact head/checkpoint/patch identity."""

    hosted_zero_useful: int | None = None
    cli_zero_useful: int | None = None
    head: str | None = None
    checkpoint: str | None = None
    reason: str = ""
    patch_id: str | None = None

    def __post_init__(self) -> None:
        for name in ("hosted_zero_useful", "cli_zero_useful"):
            value = getattr(self, name)
            if value is not None and (not isinstance(value, int) or isinstance(value, bool) or value <= 0):
                raise StateError(f"{name} must be a positive integer or null")
        if self.hosted_zero_useful is not None and self.cli_zero_useful is not None:
            raise StateError("policy overrides must set exactly one channel")
        if (
            self.hosted_zero_useful is None
            and self.cli_zero_useful is None
            and any(value is not None for value in (self.head, self.checkpoint, self.patch_id))
        ):
            raise StateError("policy overrides require one channel value")
        if (self.head is None) != (self.checkpoint is None):
            raise StateError("policy overrides require both exact head and checkpoint")
        for name in ("head", "checkpoint"):
            value = getattr(self, name)
            if value is not None and (not isinstance(value, str) or not value.strip()):
                raise StateError(f"policy override {name} must be a non-empty string or null")
        if self.patch_id is not None and (not isinstance(self.patch_id, str) or not self.patch_id.strip()):
            raise StateError("policy override patch identity must be a non-empty string or null")
        if self.patch_id is not None and self.head is None:
            raise StateError("policy override patch identity requires exact head and checkpoint")
        if not isinstance(self.reason, str):
            raise StateError("policy overrides require a textual reason")
        if not self.reason.strip() and (
            self.head is not None or self.hosted_zero_useful is not None or self.cli_zero_useful is not None
        ):
            raise StateError("policy overrides require a reason")

    def applies(self, head: str, checkpoint: str, patch_id: str | None = None) -> bool:
        # Overrides written before patch binding remain readable, but are never
        # eligible to change taper policy.
        return (
            self.patch_id is not None
            and self.head == head
            and self.checkpoint == checkpoint
            and self.patch_id == patch_id
        )

    def to_dict(self) -> dict[str, Any]:
        return {
            "hosted_zero_useful": self.hosted_zero_useful,
            "cli_zero_useful": self.cli_zero_useful,
            "head": self.head,
            "checkpoint": self.checkpoint,
            "reason": self.reason,
            "patch_id": self.patch_id,
        }

    @classmethod
    def from_dict(cls, value: Mapping[str, Any]) -> PolicyOverride:
        allowed = {"hosted_zero_useful", "cli_zero_useful", "head", "checkpoint", "reason", "patch_id"}
        if set(value) - allowed:
            raise StateError("policy override contains fields outside the private schema")
        return cls(
            hosted_zero_useful=value.get("hosted_zero_useful"),
            cli_zero_useful=value.get("cli_zero_useful"),
            head=value.get("head"),
            checkpoint=value.get("checkpoint"),
            reason=value.get("reason", ""),
            patch_id=value.get("patch_id"),
        )


@dataclasses.dataclass(frozen=True)
class SummaryFindingDisposition:
    """An operator disposition for one exact CodeRabbit summary count bucket."""

    pr: int
    head: str
    source: str
    summary_id: int
    kind: str
    count: int
    decision: str
    reason: str
    corrected_head: str | None = None
    route_ids: tuple[str, ...] = ()

    def __post_init__(self) -> None:
        if isinstance(self.pr, bool) or not isinstance(self.pr, int) or self.pr <= 0:
            raise StateError("summary disposition PR must be a positive integer")
        if not isinstance(self.head, str) or not EXACT_SHA.fullmatch(self.head):
            raise StateError("summary disposition requires an exact reviewed head")
        if self.source not in {"review", "comment"}:
            raise StateError("summary disposition source must be review or comment")
        if isinstance(self.summary_id, bool) or not isinstance(self.summary_id, int) or self.summary_id <= 0:
            raise StateError("summary disposition requires an immutable summary ID")
        if self.kind not in {"outside_diff", "duplicate"}:
            raise StateError("summary disposition kind must be outside_diff or duplicate")
        if isinstance(self.count, bool) or not isinstance(self.count, int) or self.count <= 0:
            raise StateError("summary disposition count must be a positive integer")
        if self.decision not in {"rejected", "accepted_unfixed", "accepted_fixed", "routed"}:
            raise StateError("summary disposition decision is invalid")
        if not isinstance(self.reason, str) or not self.reason.strip() or len(self.reason) > 500:
            raise StateError("summary disposition requires a reason of at most 500 characters")
        if any(ord(char) < 0x20 for char in self.reason):
            raise StateError("summary disposition reason must not contain control characters")
        if self.decision == "accepted_fixed":
            if not isinstance(self.corrected_head, str) or not EXACT_SHA.fullmatch(self.corrected_head):
                raise StateError("accepted-fixed summary disposition requires an exact corrected head")
            if self.corrected_head.casefold() == self.head.casefold():
                raise StateError("accepted-fixed summary disposition requires a changed head")
        elif self.corrected_head is not None:
            raise StateError("only accepted-fixed summary dispositions may set a corrected head")
        if self.decision == "routed":
            if (
                not isinstance(self.route_ids, tuple)
                or not self.route_ids
                or any(
                    not isinstance(route_id, str) or not re.fullmatch(r"[0-9a-f]{24}", route_id)
                    for route_id in self.route_ids
                )
            ):
                raise StateError("routed summary disposition requires stable route IDs")
        elif self.route_ids:
            raise StateError("only routed summary dispositions may set route IDs")

    @property
    def identity(self) -> tuple[int, str, str, int, str, int]:
        return (self.pr, self.head.casefold(), self.source, self.summary_id, self.kind, self.count)

    def to_dict(self) -> dict[str, Any]:
        value = dataclasses.asdict(self)
        value["route_ids"] = list(self.route_ids)
        return value

    @classmethod
    def from_dict(cls, value: Mapping[str, Any]) -> SummaryFindingDisposition:
        allowed = {
            "pr",
            "head",
            "source",
            "summary_id",
            "kind",
            "count",
            "decision",
            "reason",
            "corrected_head",
            "route_ids",
        }
        if set(value) - allowed:
            raise StateError("summary disposition contains fields outside the private schema")
        fields = {key: value[key] for key in allowed if key in value}
        if "route_ids" in fields:
            if not isinstance(fields["route_ids"], list):
                raise StateError("routed summary route IDs must be a JSON array")
            fields["route_ids"] = tuple(fields["route_ids"])
        return cls(**fields)


@dataclasses.dataclass(frozen=True)
class FindingRoute:
    """One durable route from a source review finding to its owning PR."""

    source_pr: int
    source_channel: str
    source_review: str
    source_finding: str
    observations: tuple[str, ...]
    target_pr: int | None = None
    status: str = "open"
    disposition: str | None = None
    proof: str | None = None
    target_history: tuple[int | None, ...] = ()

    def __post_init__(self) -> None:
        if isinstance(self.source_pr, bool) or not isinstance(self.source_pr, int) or self.source_pr <= 0:
            raise StateError("route source PR must be a positive integer")
        if self.source_channel not in {"hosted", "cli"}:
            raise StateError("route source channel must be hosted or cli")
        for name in ("source_review", "source_finding"):
            value = getattr(self, name)
            if not isinstance(value, str) or not value.strip() or len(value) > 200:
                raise StateError(f"route {name.replace('_', ' ')} must be non-empty and at most 200 characters")
        if not isinstance(self.observations, tuple) or not self.observations:
            raise StateError("route must retain at least one observation")
        if any(
            not isinstance(value, str)
            or not value.strip()
            or len(value) > 500
            or any(ord(char) < 0x20 for char in value)
            for value in self.observations
        ):
            raise StateError("route observations must be non-empty text of at most 500 characters")
        if self.target_pr is not None and (
            isinstance(self.target_pr, bool) or not isinstance(self.target_pr, int) or self.target_pr <= 0
        ):
            raise StateError("route target PR must be a positive integer or null")
        if self.status not in {"open", "accepted_fixed", "rejected"}:
            raise StateError("route status is invalid")
        if self.status == "accepted_fixed":
            if not isinstance(self.proof, str) or not self.proof.strip() or len(self.proof) > 500:
                raise StateError("accepted-fixed route requires proof")
            if self.disposition != "accepted and fixed":
                raise StateError("accepted-fixed route requires its disposition")
        elif self.status == "rejected":
            if not isinstance(self.disposition, str) or not self.disposition.strip() or len(self.disposition) > 500:
                raise StateError("rejected route requires a reason")
            if self.proof is not None:
                raise StateError("rejected route cannot have fix proof")
        elif self.disposition is not None or self.proof is not None:
            raise StateError("open route cannot have a terminal disposition")
        if not isinstance(self.target_history, tuple) or any(
            item is not None and (isinstance(item, bool) or not isinstance(item, int) or item <= 0)
            for item in self.target_history
        ):
            raise StateError("route target history is invalid")

    @property
    def identity(self) -> tuple[int, str, str, str]:
        return (self.source_pr, self.source_channel, self.source_review, self.source_finding)

    @property
    def route_id(self) -> str:
        encoded = "\0".join((str(self.source_pr), self.source_channel, self.source_review, self.source_finding))
        return hashlib.sha256(encoded.encode("utf-8")).hexdigest()[:24]

    def to_dict(self) -> dict[str, Any]:
        return {
            "route_id": self.route_id,
            "source_pr": self.source_pr,
            "source_channel": self.source_channel,
            "source_review": self.source_review,
            "source_finding": self.source_finding,
            "observations": list(self.observations),
            "target_pr": self.target_pr,
            "status": self.status,
            "disposition": self.disposition,
            "proof": self.proof,
            "target_history": list(self.target_history),
        }

    @classmethod
    def from_dict(cls, value: Mapping[str, Any]) -> FindingRoute:
        allowed = {
            "route_id",
            "source_pr",
            "source_channel",
            "source_review",
            "source_finding",
            "observations",
            "target_pr",
            "status",
            "disposition",
            "proof",
            "target_history",
        }
        target_history = value.get("target_history", [])
        if (
            set(value) - allowed
            or not isinstance(value.get("observations"), list)
            or not isinstance(target_history, list)
        ):
            raise StateError("route record contains fields outside its schema")
        route = cls(
            source_pr=value.get("source_pr"),
            source_channel=value.get("source_channel"),
            source_review=value.get("source_review"),
            source_finding=value.get("source_finding"),
            observations=tuple(value["observations"]),
            target_pr=value.get("target_pr"),
            status=value.get("status", "open"),
            disposition=value.get("disposition"),
            proof=value.get("proof"),
            target_history=tuple(target_history),
        )
        if value.get("route_id") not in (None, route.route_id):
            raise StateError("route ID does not match its stable source finding identity")
        return route


def merge_open_route(existing: FindingRoute, incoming: FindingRoute) -> FindingRoute:
    """Merge an observation into an open route without changing its identity."""

    if not isinstance(existing, FindingRoute) or not isinstance(incoming, FindingRoute):
        raise StateError("route merge requires validated route records")
    if existing.identity != incoming.identity:
        raise StateError("route merge requires the same stable source finding identity")
    if existing.status != "open" or incoming.status != "open":
        raise StateError("only open routes can merge observations")
    if existing.target_pr is not None and incoming.target_pr not in (None, existing.target_pr):
        raise StateError("an existing route must be explicitly retargeted")
    observations = list(existing.observations)
    observations.extend(value for value in incoming.observations if value not in observations)
    return dataclasses.replace(
        existing,
        observations=tuple(observations),
        target_pr=existing.target_pr if existing.target_pr is not None else incoming.target_pr,
    )


def _summary_route_matches_disposition(
    route: FindingRoute,
    disposition: SummaryFindingDisposition,
) -> bool:
    if (
        route.source_pr != disposition.pr
        or route.source_channel != "hosted"
        or route.source_review != f"summary:{disposition.source}:{disposition.summary_id}"
    ):
        return False
    prefix = f"{disposition.kind}:"
    if not route.source_finding.startswith(prefix):
        return False
    reference = route.source_finding[len(prefix) :]
    if reference.startswith("ref:"):
        return bool(reference[4:].strip())
    legacy = re.fullmatch(r"(?P<count>[1-9][0-9]*):(?P<reference>.+)", reference)
    return bool(legacy and int(legacy.group("count")) == disposition.count and legacy.group("reference").strip())


def adjudicate_summary_findings(
    pr: int,
    current_head: str,
    summary: Mapping[str, Any],
    dispositions: Sequence[SummaryFindingDisposition],
) -> tuple[list[dict[str, Any]], list[dict[str, Any]]]:
    """Apply exact-bucket dispositions while preserving raw findings and decisions.

    A rejection clears only its exact summary identity, reviewed head, finding
    kind, and count. Accepted-unfixed remains unresolved. Accepted-fixed is an
    audit disposition for an older head and cannot clear a finding still
    reported on the live head.
    """

    raw = summary.get("findings")
    identity = summary.get("identity")
    source = summary.get("source")
    reviewed_head = summary.get("head_sha")
    if (
        summary.get("status") != "current"
        or not isinstance(raw, list)
        or not isinstance(identity, int)
        or isinstance(identity, bool)
        or not isinstance(source, str)
        or not isinstance(reviewed_head, str)
        or reviewed_head.casefold() != current_head.casefold()
    ):
        return list(raw) if isinstance(raw, list) else [], []

    remaining: list[dict[str, Any]] = []
    matched: list[dict[str, Any]] = []
    for finding in raw:
        if not isinstance(finding, Mapping):
            remaining.append(dict(finding) if isinstance(finding, Mapping) else {"malformed": True})
            continue
        kind, count = finding.get("kind"), finding.get("count")
        candidates = [
            item
            for item in dispositions
            if item.identity == (pr, reviewed_head.casefold(), source, identity, kind, count)
        ]
        if len(candidates) > 1:
            raise StateError("multiple summary dispositions match one exact finding bucket")
        disposition = candidates[0] if candidates else None
        if disposition is None:
            remaining.append(dict(finding))
            continue
        matched.append(disposition.to_dict())
        # A fixed disposition refers to a prior head and can never clear a
        # finding in a current-head summary. It remains in state as a historical
        # operator decision; any new summary has a distinct identity.
        if disposition.decision not in {"rejected", "routed"}:
            remaining.append(dict(finding))
    return remaining, matched


@dataclasses.dataclass(frozen=True)
class Judgment:
    """A human decision for one channel and exact head/checkpoint/patch identity."""

    pr: int
    channel: str
    decision: str
    head: str
    checkpoint: str
    reason: str
    patch_id: str | None = None

    def __post_init__(self) -> None:
        if isinstance(self.pr, bool) or not isinstance(self.pr, int) or self.pr <= 0:
            raise StateError("judgment PR must be a positive integer")
        if self.channel not in {"hosted", "cli"}:
            raise StateError("judgment channel must be hosted or cli")
        if self.decision not in {"reopen", "retain"}:
            raise StateError("judgment decision must be reopen or retain")
        if (
            not isinstance(self.head, str)
            or not self.head.strip()
            or not isinstance(self.checkpoint, str)
            or not self.checkpoint.strip()
            or not isinstance(self.reason, str)
            or not self.reason.strip()
        ):
            raise StateError("judgments require head, checkpoint, and reason")
        if self.patch_id is not None and (not isinstance(self.patch_id, str) or not self.patch_id.strip()):
            raise StateError("judgment patch identity must be a non-empty string or null")

    def applies(self, pr: int, channel: str, head: str, checkpoint: str, patch_id: str | None = None) -> bool:
        # A legacy judgment without a patch identity cannot retain or reopen
        # evidence after the patch-bound policy was introduced.
        return (
            self.patch_id is not None
            and self.patch_id == patch_id
            and self.pr == pr
            and self.channel == channel
            and self.head == head
            and self.checkpoint == checkpoint
        )

    def to_dict(self) -> dict[str, str]:
        return {
            "pr": self.pr,
            "channel": self.channel,
            "decision": self.decision,
            "head": self.head,
            "checkpoint": self.checkpoint,
            "reason": self.reason,
            "patch_id": self.patch_id,
        }

    @classmethod
    def from_dict(cls, value: Mapping[str, Any]) -> Judgment:
        allowed = {"pr", "channel", "decision", "head", "checkpoint", "reason", "patch_id"}
        if set(value) - allowed:
            raise StateError("judgment contains fields outside the private schema")
        return cls(
            pr=value["pr"],
            channel=value["channel"],
            decision=value["decision"],
            head=value["head"],
            checkpoint=value["checkpoint"],
            reason=value["reason"],
            patch_id=value.get("patch_id"),
        )


@dataclasses.dataclass(frozen=True)
class StackReconciliationDecision:
    """Explicitly reopen review after one checkpoint's parent moved."""

    pr: int
    channel: str
    checkpoint: str
    prior_head: str
    child_head: str
    parent_identity: str
    parent_head: str
    merge_base: str
    patch_id: str
    reason: str

    def __post_init__(self) -> None:
        if isinstance(self.pr, bool) or not isinstance(self.pr, int) or self.pr <= 0:
            raise StateError("reconciliation PR must be a positive integer")
        if self.channel not in {"hosted", "cli"}:
            raise StateError("reconciliation channel must be hosted or cli")
        if not all(
            isinstance(value, str) and value.strip()
            for value in (
                self.checkpoint,
                self.prior_head,
                self.child_head,
                self.parent_identity,
                self.parent_head,
                self.merge_base,
                self.patch_id,
                self.reason,
            )
        ):
            raise StateError("reconciliation decisions require complete identities and a reason")

    def matches(self, pr: int, anchor: Mapping[str, Any]) -> bool:
        return self.pr == pr and all(
            getattr(self, key) == anchor.get(field)
            for key, field in (
                ("child_head", "child_head"),
                ("parent_identity", "parent_identity"),
                ("parent_head", "parent_head"),
                ("merge_base", "merge_base"),
                ("patch_id", "patch_id"),
            )
        )

    def to_dict(self) -> dict[str, Any]:
        return dataclasses.asdict(self)

    @classmethod
    def from_dict(cls, value: Mapping[str, Any]) -> StackReconciliationDecision:
        allowed = {
            "pr",
            "channel",
            "checkpoint",
            "prior_head",
            "child_head",
            "parent_identity",
            "parent_head",
            "merge_base",
            "patch_id",
            "reason",
        }
        if set(value) - allowed:
            raise StateError("stack reconciliation contains fields outside the private schema")
        return cls(**{key: value[key] for key in allowed})


@dataclasses.dataclass(frozen=True)
class LegacyEvidenceTransition:
    """Explicitly make one exact set of legacy observations non-counting."""

    pr: int
    child_head: str
    parent_identity: str
    parent_head: str
    merge_base: str
    patch_id: str
    hosted_fingerprints: tuple[str, ...] = ()
    cli_fingerprints: tuple[str, ...] = ()
    reason: str = ""
    retired_hosted_fingerprints: tuple[str, ...] = ()

    def __post_init__(self) -> None:
        if isinstance(self.pr, bool) or not isinstance(self.pr, int) or self.pr <= 0:
            raise StateError("legacy transition PR must be a positive integer")
        if not all(
            isinstance(value, str) and value.strip()
            for value in (
                self.child_head,
                self.parent_identity,
                self.parent_head,
                self.merge_base,
                self.patch_id,
                self.reason,
            )
        ):
            raise StateError("legacy transitions require complete identities and a reason")
        for name in ("hosted_fingerprints", "cli_fingerprints"):
            values = getattr(self, name)
            if not isinstance(values, tuple) or any(
                not isinstance(value, str) or FINGERPRINT.fullmatch(value) is None for value in values
            ):
                raise StateError(f"legacy transition {name} must contain SHA-256 fingerprints")
            if len(set(values)) != len(values):
                raise StateError(f"legacy transition {name} fingerprints must be unique")
        retired = self.retired_hosted_fingerprints
        if not isinstance(retired, tuple) or any(
            not isinstance(value, str) or FINGERPRINT.fullmatch(value) is None for value in retired
        ):
            raise StateError("legacy transition retired Hosted fingerprints must be SHA-256 fingerprints")
        if len(set(retired)) != len(retired) or not set(retired).issubset(self.hosted_fingerprints):
            raise StateError("retired Hosted fingerprints must be unique members of the audited Hosted set")
        if not self.hosted_fingerprints and not self.cli_fingerprints:
            raise StateError("legacy transition requires at least one legacy observation")

    def matches(self, pr: int, anchor: Mapping[str, Any]) -> bool:
        return self.pr == pr and all(
            getattr(self, field) == anchor.get(field)
            for field in ("child_head", "parent_identity", "parent_head", "merge_base", "patch_id")
        )

    def fingerprints_for(self, channel: str) -> tuple[str, ...]:
        if channel == "hosted":
            return self.hosted_fingerprints
        if channel == "cli":
            return self.cli_fingerprints
        raise StateError("legacy transition channel must be hosted or cli")

    def to_dict(self) -> dict[str, Any]:
        return dataclasses.asdict(self)

    @classmethod
    def from_dict(cls, value: Mapping[str, Any]) -> LegacyEvidenceTransition:
        allowed = {
            "pr",
            "child_head",
            "parent_identity",
            "parent_head",
            "merge_base",
            "patch_id",
            "hosted_fingerprints",
            "cli_fingerprints",
            "retired_hosted_fingerprints",
            "reason",
        }
        if set(value) - allowed:
            raise StateError("legacy transition contains fields outside the private schema")
        try:
            hosted = value.get("hosted_fingerprints", ())
            cli = value.get("cli_fingerprints", ())
            retired_hosted = value.get("retired_hosted_fingerprints", ())
            if isinstance(hosted, list):
                hosted = tuple(hosted)
            if isinstance(cli, list):
                cli = tuple(cli)
            if isinstance(retired_hosted, list):
                retired_hosted = tuple(retired_hosted)
            return cls(
                pr=value["pr"],
                child_head=value["child_head"],
                parent_identity=value["parent_identity"],
                parent_head=value["parent_head"],
                merge_base=value["merge_base"],
                patch_id=value["patch_id"],
                hosted_fingerprints=hosted,
                cli_fingerprints=cli,
                retired_hosted_fingerprints=retired_hosted,
                reason=value["reason"],
            )
        except StateError:
            raise
        except (KeyError, AttributeError, TypeError) as exc:
            raise StateError("legacy transition record is malformed") from exc


@dataclasses.dataclass(frozen=True)
class ReviewAllocation:
    """A durable one-result allocation or explicit channel-stop decision.

    The allocation is bound to the complete stack identity observed when an
    operator made the promise. The controller may consume it only after a
    completed result for that identity. New stop decisions record the exact
    optional reviewed checkpoint and available live audit context separately; legacy handoff
    fields remain readable for state migration and are never newly issued.
    """

    pr: int
    channel: str
    head: str
    parent_identity: str
    parent_head: str
    merge_base: str | None
    patch_id: str | None
    baseline_checkpoints: tuple[str, ...]
    reason: str
    handoff_checkpoint: str | None = None
    handoff_head: str | None = None
    handoff_validation: str | None = None
    stop_basis: str | None = None
    stop_checkpoint: str | None = None
    stop_reviewed_head: str | None = None
    stop_reviewed_patch_id: str | None = None
    stop_head: str | None = None
    stop_parent_identity: str | None = None
    stop_parent_head: str | None = None
    stop_merge_base: str | None = None
    stop_patch_id: str | None = None
    stop_reason: str | None = None
    stop_summary_disposition_fingerprints: tuple[str, ...] = ()
    retained_ambiguous_fingerprints: tuple[str, ...] = ()
    retained_ambiguous_reason: str | None = None
    baseline_checkpoint: str | None = None
    min_additional_completed: int | None = None
    max_additional_completed: int | None = None
    reopens_taper: bool = False

    def __post_init__(self) -> None:
        if isinstance(self.pr, bool) or not isinstance(self.pr, int) or self.pr <= 0:
            raise StateError("review allocation PR must be a positive integer")
        if self.channel not in {"hosted", "cli"}:
            raise StateError("review allocation channel must be hosted or cli")
        for name in ("head", "parent_head", "merge_base"):
            value = getattr(self, name)
            if name == "merge_base" and value is None and self.stop_basis == "direct_human":
                continue
            if not isinstance(value, str) or not EXACT_SHA.fullmatch(value):
                raise StateError(f"review allocation {name} must be an exact SHA")
        for name in ("parent_identity", "patch_id", "reason"):
            value = getattr(self, name)
            if name == "patch_id" and value is None and self.stop_basis == "direct_human":
                continue
            if not isinstance(value, str) or not value.strip():
                raise StateError(f"review allocation {name} must be a non-empty string")
        if not isinstance(self.baseline_checkpoints, tuple):
            raise StateError("review allocation baseline checkpoints must be a tuple")
        if any(not isinstance(item, str) or not item.strip() for item in self.baseline_checkpoints):
            raise StateError("review allocation baseline checkpoints must be non-empty strings")
        if len(set(self.baseline_checkpoints)) != len(self.baseline_checkpoints):
            raise StateError("review allocation baseline checkpoints must be unique")
        if self.min_additional_completed is not None and (
            isinstance(self.min_additional_completed, bool)
            or not isinstance(self.min_additional_completed, int)
            or self.min_additional_completed < 0
        ):
            raise StateError("minimum additional completed reviews must be a non-negative integer or null")
        if self.max_additional_completed is not None and (
            isinstance(self.max_additional_completed, bool)
            or not isinstance(self.max_additional_completed, int)
            or self.max_additional_completed <= 0
        ):
            raise StateError("maximum additional completed reviews must be a positive integer")
        if not isinstance(self.reopens_taper, bool):
            raise StateError("review allocation taper-reopen marker must be boolean")
        if self.min_additional_completed is None and self.max_additional_completed is None:
            if self.baseline_checkpoint is not None:
                raise StateError("a bounded review allocation requires a minimum or maximum")
        elif (
            self.min_additional_completed is not None
            and self.max_additional_completed is not None
            and (self.min_additional_completed > self.max_additional_completed)
        ):
            raise StateError("minimum additional completed reviews cannot exceed the maximum")
        if self.baseline_checkpoint is not None and (
            not isinstance(self.baseline_checkpoint, str)
            or not self.baseline_checkpoint.strip()
            or self.baseline_checkpoint not in self.baseline_checkpoints
        ):
            raise StateError("allocation baseline checkpoint must be present in the baseline set")
        handoff_values = (self.handoff_checkpoint, self.handoff_head, self.handoff_validation)
        if any(value is not None for value in handoff_values):
            if not all(isinstance(value, str) and value.strip() for value in handoff_values):
                raise StateError("review allocation handoff proof must be complete")
            if not EXACT_SHA.fullmatch(self.handoff_head or ""):
                raise StateError("review allocation handoff head must be an exact SHA")
        stop_values = (
            self.stop_checkpoint,
            self.stop_reviewed_head,
            self.stop_reviewed_patch_id,
            self.stop_head,
            self.stop_parent_identity,
            self.stop_parent_head,
            self.stop_merge_base,
            self.stop_patch_id,
            self.stop_reason,
        )
        if self.stop_basis is None:
            if any(value is not None for value in stop_values):
                raise StateError("review allocation stop fields require a stop basis")
            if self.stop_summary_disposition_fingerprints or self.retained_ambiguous_fingerprints:
                raise StateError("stop audit details require a stop basis")
        else:
            if self.stop_basis not in {"allocated", "direct_human"}:
                raise StateError("review allocation stop basis must be allocated or direct_human")
            reviewed_values = stop_values[:3]
            no_reviewed_checkpoint = self.stop_basis == "direct_human" and all(
                value is None for value in reviewed_values
            )
            required_stop_values = (self.stop_head, self.stop_parent_identity, self.stop_parent_head, self.stop_reason)
            if self.stop_basis == "allocated":
                required_stop_values += (self.stop_merge_base, self.stop_patch_id)
            if not all(isinstance(value, str) and value.strip() for value in required_stop_values) or (
                not no_reviewed_checkpoint
                and not all(isinstance(value, str) and value.strip() for value in reviewed_values)
            ):
                raise StateError("review allocation stop proof must be complete")
            if self.stop_patch_id is not None and (
                not isinstance(self.stop_patch_id, str) or not self.stop_patch_id.strip()
            ):
                raise StateError("review allocation stop patch identity must be a non-empty string")
            for name in ("stop_reviewed_head", "stop_head", "stop_parent_head", "stop_merge_base"):
                if name == "stop_reviewed_head" and no_reviewed_checkpoint:
                    continue
                if name == "stop_merge_base" and self.stop_basis == "direct_human" and self.stop_merge_base is None:
                    continue
                if not EXACT_SHA.fullmatch(getattr(self, name) or ""):
                    raise StateError(f"review allocation {name} must be an exact SHA")
            if len(self.stop_reason or "") > 500 or any(ord(character) < 0x20 for character in self.stop_reason or ""):
                raise StateError("review allocation stop reason must be at most 500 characters without controls")
            if not isinstance(self.stop_summary_disposition_fingerprints, tuple) or any(
                not isinstance(value, str) or not FINGERPRINT.fullmatch(value)
                for value in self.stop_summary_disposition_fingerprints
            ):
                raise StateError("review allocation stop summary disposition fingerprints are malformed")
            if len(set(self.stop_summary_disposition_fingerprints)) != len(self.stop_summary_disposition_fingerprints):
                raise StateError("review allocation stop summary disposition fingerprints must be unique")
        if not isinstance(self.retained_ambiguous_fingerprints, tuple) or any(
            not isinstance(value, str) or not FINGERPRINT.fullmatch(value)
            for value in self.retained_ambiguous_fingerprints
        ):
            raise StateError("retained ambiguous evidence requires exact fingerprints")
        if len(set(self.retained_ambiguous_fingerprints)) != len(self.retained_ambiguous_fingerprints):
            raise StateError("retained ambiguous fingerprints must be unique")
        if bool(self.retained_ambiguous_fingerprints) != (self.retained_ambiguous_reason is not None):
            raise StateError("retained ambiguous evidence requires both fingerprint and reason")
        if self.retained_ambiguous_fingerprints and (
            not isinstance(self.retained_ambiguous_reason, str)
            or not self.retained_ambiguous_reason.strip()
            or len(self.retained_ambiguous_reason) > 500
            or any(ord(character) < 0x20 for character in self.retained_ambiguous_reason)
        ):
            raise StateError("retained ambiguous evidence requires a bounded reason without controls")

    @property
    def identity(self) -> str:
        return f"{self.pr}:{self.channel}"

    def to_dict(self) -> dict[str, Any]:
        value = {
            "pr": self.pr,
            "channel": self.channel,
            "head": self.head,
            "parent_identity": self.parent_identity,
            "parent_head": self.parent_head,
            "merge_base": self.merge_base,
            "patch_id": self.patch_id,
            "baseline_checkpoints": list(self.baseline_checkpoints),
            "reason": self.reason,
            "handoff_checkpoint": self.handoff_checkpoint,
            "handoff_head": self.handoff_head,
            "handoff_validation": self.handoff_validation,
            "stop_basis": self.stop_basis,
            "stop_checkpoint": self.stop_checkpoint,
            "stop_reviewed_head": self.stop_reviewed_head,
            "stop_reviewed_patch_id": self.stop_reviewed_patch_id,
            "stop_head": self.stop_head,
            "stop_parent_identity": self.stop_parent_identity,
            "stop_parent_head": self.stop_parent_head,
            "stop_merge_base": self.stop_merge_base,
            "stop_patch_id": self.stop_patch_id,
            "stop_reason": self.stop_reason,
            "stop_summary_disposition_fingerprints": list(self.stop_summary_disposition_fingerprints),
            "retained_ambiguous_fingerprints": list(self.retained_ambiguous_fingerprints),
            "retained_ambiguous_reason": self.retained_ambiguous_reason,
        }
        if self.baseline_checkpoint is not None:
            value["baseline_checkpoint"] = self.baseline_checkpoint
        if self.min_additional_completed is not None:
            value["min_additional_completed"] = self.min_additional_completed
        if self.max_additional_completed is not None:
            value["max_additional_completed"] = self.max_additional_completed
        if self.reopens_taper:
            value["reopens_taper"] = True
        return value

    @classmethod
    def from_dict(cls, value: Mapping[str, Any]) -> ReviewAllocation:
        allowed = {
            "pr",
            "channel",
            "head",
            "parent_identity",
            "parent_head",
            "merge_base",
            "patch_id",
            "baseline_checkpoints",
            "reason",
            "baseline_checkpoint",
            "min_additional_completed",
            "max_additional_completed",
            "reopens_taper",
            "handoff_checkpoint",
            "handoff_head",
            "handoff_validation",
            "stop_basis",
            "stop_checkpoint",
            "stop_reviewed_head",
            "stop_reviewed_patch_id",
            "stop_head",
            "stop_parent_identity",
            "stop_parent_head",
            "stop_merge_base",
            "stop_patch_id",
            "stop_reason",
            "stop_summary_disposition_fingerprints",
            "retained_ambiguous_fingerprints",
            "retained_ambiguous_fingerprint",
            "retained_ambiguous_reason",
        }
        if set(value) - allowed:
            raise StateError("review allocation contains fields outside the private schema")
        raw_checkpoints = value.get("baseline_checkpoints")
        if not isinstance(raw_checkpoints, list):
            raise StateError("review allocation baseline checkpoints must be a JSON array")
        raw_stop_dispositions = value.get("stop_summary_disposition_fingerprints", ())
        if isinstance(raw_stop_dispositions, list):
            raw_stop_dispositions = tuple(raw_stop_dispositions)
        raw_retained_fingerprints = value.get("retained_ambiguous_fingerprints")
        legacy_retained_fingerprint = value.get("retained_ambiguous_fingerprint")
        if raw_retained_fingerprints is None:
            raw_retained_fingerprints = () if legacy_retained_fingerprint is None else (legacy_retained_fingerprint,)
        elif isinstance(raw_retained_fingerprints, list):
            raw_retained_fingerprints = tuple(raw_retained_fingerprints)
        else:
            raise StateError("retained ambiguous fingerprints must be a JSON array")
        if legacy_retained_fingerprint is not None and raw_retained_fingerprints != (legacy_retained_fingerprint,):
            raise StateError("legacy retained ambiguous fingerprint conflicts with the fingerprint set")
        try:
            return cls(
                pr=value["pr"],
                channel=value["channel"],
                head=value["head"],
                parent_identity=value["parent_identity"],
                parent_head=value["parent_head"],
                merge_base=value["merge_base"],
                patch_id=value["patch_id"],
                baseline_checkpoints=tuple(raw_checkpoints),
                reason=value["reason"],
                baseline_checkpoint=value.get("baseline_checkpoint"),
                min_additional_completed=value.get("min_additional_completed"),
                max_additional_completed=value.get("max_additional_completed"),
                reopens_taper=value.get("reopens_taper", False),
                handoff_checkpoint=value.get("handoff_checkpoint"),
                handoff_head=value.get("handoff_head"),
                handoff_validation=value.get("handoff_validation"),
                stop_basis=value.get("stop_basis"),
                stop_checkpoint=value.get("stop_checkpoint"),
                stop_reviewed_head=value.get("stop_reviewed_head"),
                stop_reviewed_patch_id=value.get("stop_reviewed_patch_id"),
                stop_head=value.get("stop_head"),
                stop_parent_identity=value.get("stop_parent_identity"),
                stop_parent_head=value.get("stop_parent_head"),
                stop_merge_base=value.get("stop_merge_base"),
                stop_patch_id=value.get("stop_patch_id"),
                stop_reason=value.get("stop_reason"),
                stop_summary_disposition_fingerprints=raw_stop_dispositions,
                retained_ambiguous_fingerprints=raw_retained_fingerprints,
                retained_ambiguous_reason=value.get("retained_ambiguous_reason"),
            )
        except KeyError as exc:
            raise StateError("review allocation contains malformed private records") from exc


@dataclasses.dataclass(frozen=True)
class ReviewState:
    """The complete persisted document, excluding all live review observations."""

    ordered_prs: tuple[int, ...] = ()
    policy_overrides: Mapping[str, PolicyOverride] = dataclasses.field(default_factory=dict)
    judgments: tuple[Judgment, ...] = ()
    reconciliations: tuple[StackReconciliationDecision, ...] = ()
    legacy_transitions: tuple[LegacyEvidenceTransition, ...] = ()
    summary_dispositions: tuple[SummaryFindingDisposition, ...] = ()
    routes: tuple[FindingRoute, ...] = ()
    allocations: Mapping[str, ReviewAllocation] = dataclasses.field(default_factory=dict)
    schema_version: int = SCHEMA_VERSION

    def __post_init__(self) -> None:
        if self.schema_version != SCHEMA_VERSION:
            raise StateError(f"unsupported schema version: {self.schema_version}")
        if len(set(self.ordered_prs)) != len(self.ordered_prs) or any(
            not isinstance(pr, int) or isinstance(pr, bool) or pr <= 0 for pr in self.ordered_prs
        ):
            raise StateError("ordered_prs must contain unique positive integers")
        for identity, override in self.policy_overrides.items():
            if (
                not isinstance(identity, str)
                or not re.fullmatch(r"[1-9][0-9]*:(?:hosted|cli)", identity)
                or not isinstance(override, PolicyOverride)
            ):
                raise StateError("policy overrides must be keyed by PR and channel")
        for identity, allocation in self.allocations.items():
            if (
                not isinstance(identity, str)
                or not re.fullmatch(r"[1-9][0-9]*:(?:hosted|cli)", identity)
                or not isinstance(allocation, ReviewAllocation)
                or allocation.identity != identity
            ):
                raise StateError("review allocations must be keyed by matching PR and channel")
        if any(not isinstance(item, SummaryFindingDisposition) for item in self.summary_dispositions):
            raise StateError("summary dispositions must contain validated disposition records")
        if any(not isinstance(item, LegacyEvidenceTransition) for item in self.legacy_transitions):
            raise StateError("legacy transitions must contain validated transition records")
        if any(not isinstance(item, FindingRoute) for item in self.routes):
            raise StateError("routes must contain validated finding route records")
        route_ids = [item.route_id for item in self.routes]
        if len(set(route_ids)) != len(route_ids):
            raise StateError("routes must have unique stable source finding identities")
        routes_by_id = {item.route_id: item for item in self.routes}
        for disposition in self.summary_dispositions:
            if disposition.decision != "routed":
                continue
            if not set(disposition.route_ids) <= routes_by_id.keys():
                raise StateError("routed summary dispositions must link to a durable route")
            if any(
                not _summary_route_matches_disposition(routes_by_id[route_id], disposition)
                for route_id in disposition.route_ids
            ):
                raise StateError(
                    "routed summary disposition routes must match its source PR, hosted summary identity, and finding bucket"
                )
        identities = [item.identity for item in self.summary_dispositions]
        if len(set(identities)) != len(identities):
            raise StateError("summary dispositions must have unique exact finding identities")

    def to_dict(self) -> dict[str, Any]:
        return {
            "schema_version": self.schema_version,
            "ordered_prs": list(self.ordered_prs),
            "policy_overrides": {identity: item.to_dict() for identity, item in sorted(self.policy_overrides.items())},
            "judgments": [item.to_dict() for item in self.judgments],
            "reconciliations": [item.to_dict() for item in self.reconciliations],
            "legacy_transitions": [item.to_dict() for item in self.legacy_transitions],
            "summary_dispositions": [item.to_dict() for item in self.summary_dispositions],
            "routes": [item.to_dict() for item in self.routes],
            "allocations": {identity: item.to_dict() for identity, item in sorted(self.allocations.items())},
        }

    @classmethod
    def from_dict(cls, value: Mapping[str, Any]) -> ReviewState:
        if not isinstance(value, Mapping):
            raise StateError("review-stack state must be an object")
        if set(value) - {
            "schema_version",
            "ordered_prs",
            "policy_overrides",
            "judgments",
            "reconciliations",
            "legacy_transitions",
            "summary_dispositions",
            "routes",
            "allocations",
        }:
            raise StateError("state contains fields outside the private configuration schema")
        if value.get("schema_version") != SCHEMA_VERSION:
            raise StateError("state has an unsupported schema version")
        raw_overrides = value.get("policy_overrides", {})
        if not isinstance(raw_overrides, Mapping):
            raise StateError("policy overrides must be an object")
        if any(not isinstance(item, Mapping) for item in raw_overrides.values()):
            raise StateError("policy override records must be objects")
        raw_allocations = value.get("allocations", {})
        if not isinstance(raw_allocations, Mapping):
            raise StateError("review allocations must be an object")
        if any(not isinstance(item, Mapping) for item in raw_allocations.values()):
            raise StateError("review allocation records must be objects")
        raw_routes = value.get("routes", [])
        if not isinstance(raw_routes, list) or any(not isinstance(item, Mapping) for item in raw_routes):
            raise StateError("routes must be an array of objects")
        try:
            overrides = {identity: PolicyOverride.from_dict(item) for identity, item in raw_overrides.items()}
            allocations = {identity: ReviewAllocation.from_dict(item) for identity, item in raw_allocations.items()}
            return cls(
                ordered_prs=tuple(value.get("ordered_prs", ())),
                policy_overrides=overrides,
                judgments=tuple(Judgment.from_dict(item) for item in value.get("judgments", ())),
                reconciliations=tuple(
                    StackReconciliationDecision.from_dict(item) for item in value.get("reconciliations", ())
                ),
                legacy_transitions=tuple(
                    LegacyEvidenceTransition.from_dict(item) for item in value.get("legacy_transitions", ())
                ),
                summary_dispositions=tuple(
                    SummaryFindingDisposition.from_dict(item) for item in value.get("summary_dispositions", ())
                ),
                routes=tuple(FindingRoute.from_dict(item) for item in raw_routes),
                allocations=allocations,
            )
        except StateError:
            raise
        except (KeyError, AttributeError, TypeError) as exc:
            raise StateError("state contains malformed private records") from exc


def git_common_dir(cwd: str | os.PathLike[str] | None = None) -> Path:
    """Resolve the repository's shared Git common directory."""

    try:
        result = subprocess.run(
            ["git", "rev-parse", "--git-common-dir"],
            cwd=cwd,
            check=True,
            capture_output=True,
            text=True,
            timeout=30,
        )
    except (OSError, subprocess.CalledProcessError, subprocess.TimeoutExpired) as exc:
        raise StateError("cannot resolve the repository Git common directory") from exc
    common = Path(result.stdout.strip())
    if not common.is_absolute():
        common = Path(cwd or os.getcwd()) / common
    return common.resolve()


def state_path(common_dir: str | os.PathLike[str] | None = None) -> Path:
    """Return ``<git-common-dir>/firemud/pr-review-stack.json``."""

    common = Path(common_dir).resolve() if common_dir is not None else git_common_dir()
    return common / "firemud" / "pr-review-stack.json"


@contextlib.contextmanager
def _locked(lock_path: Path) -> Iterator[None]:
    lock_path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    lock_path.parent.chmod(0o700)
    with lock_path.open("a+", encoding="utf-8") as handle:
        os.fchmod(handle.fileno(), 0o600)
        try:
            import fcntl

            fcntl.flock(handle.fileno(), fcntl.LOCK_EX)
        except ImportError:  # pragma: no cover - repository execution is Linux/WSL
            pass
        try:
            yield
        finally:
            try:
                import fcntl

                fcntl.flock(handle.fileno(), fcntl.LOCK_UN)
            except ImportError:  # pragma: no cover
                pass


class StateStore:
    """Read and update state under an exclusive lock with atomic replacement."""

    def __init__(self, path: str | os.PathLike[str] | None = None) -> None:
        self.path = Path(path).resolve() if path is not None else state_path()
        self.lock_path = self.path.with_name(".pr-review-stack.lock")

    def load(self) -> ReviewState:
        if not self.path.exists():
            return ReviewState()
        if self.path.is_dir():
            marker = self.path / "sqlite-cutover.json"
            try:
                with marker.open("r", encoding="utf-8") as handle:
                    cutover = json.load(handle)
            except (OSError, json.JSONDecodeError):
                cutover = None
            if isinstance(cutover, dict) and cutover.get("format") == "firemud-pr-review-sqlite-cutover":
                database = cutover.get("database")
                raise StateError(
                    "review-stack JSON was migrated to SQLite" + (f": {database}" if isinstance(database, str) else "")
                )
            raise StateError("review-stack state path is a directory")
        try:
            with self.path.open("r", encoding="utf-8") as handle:
                value = json.load(handle)
        except (OSError, json.JSONDecodeError) as exc:
            raise StateError(f"cannot read review-stack state: {self.path}") from exc
        if not isinstance(value, dict):
            raise StateError("review-stack state must be a JSON object")
        return ReviewState.from_dict(value)

    def save(self, state: ReviewState) -> None:
        if not isinstance(state, ReviewState):
            raise TypeError("save expects ReviewState")
        with _locked(self.lock_path):
            if self.path.is_dir():
                raise StateError("cannot save review-stack JSON after SQLite cutover")
            self._save_unlocked(state)

    def _save_unlocked(self, state: ReviewState) -> None:
        self.path.parent.mkdir(parents=True, exist_ok=True)
        with tempfile.NamedTemporaryFile(
            mode="w", encoding="utf-8", dir=self.path.parent, prefix=f".{self.path.name}.", delete=False
        ) as handle:
            temporary = Path(handle.name)
            json.dump(state.to_dict(), handle, indent=2, sort_keys=True)
            handle.write("\n")
            handle.flush()
            os.fsync(handle.fileno())
        try:
            os.replace(temporary, self.path)
            directory_fd = os.open(self.path.parent, os.O_RDONLY)
            try:
                os.fsync(directory_fd)
            finally:
                os.close(directory_fd)
        finally:
            temporary.unlink(missing_ok=True)

    def update(self, mutate: Callable[[ReviewState], ReviewState]) -> ReviewState:
        with _locked(self.lock_path):
            updated = mutate(self.load())
            self._save_unlocked(updated)
            return updated


STATE_STATUS_VERSION = 1
SQLITE_CUTOVER_FORMAT = "firemud-pr-review-sqlite-cutover"


def sqlite_state_path(json_path: str | os.PathLike[str]) -> Path:
    """Return the versioned SQLite sibling selected by an explicit JSON cutover."""

    path = Path(json_path).expanduser().absolute()
    target = path.with_suffix(".sqlite3")
    return target.parent.resolve() / target.name


def _cutover_sqlite_store(path: Path) -> tuple[Any, dict[str, Any]]:
    """Read and validate the immutable pointer installed by JSON-to-SQLite cutover."""

    from .sqlite_store import CUTOVER_VERSION, SQLITE_SCHEMA_VERSION, SqliteStateStore

    try:
        directory_stat = path.stat(follow_symlinks=False)
    except OSError as exc:
        raise StateError("cannot inspect SQLite cutover directory") from exc
    if path.is_symlink() or not path.is_dir():
        raise StateError("SQLite cutover path must be a regular directory")
    if directory_stat.st_mode & 0o077:
        raise StateError("SQLite cutover directory permissions must be private")

    marker_path = path / "sqlite-cutover.json"
    try:
        marker_stat = marker_path.stat(follow_symlinks=False)
        if marker_path.is_symlink() or not marker_path.is_file():
            raise StateError("SQLite cutover marker must be a regular file")
        if marker_stat.st_mode & 0o077:
            raise StateError("SQLite cutover marker permissions must be private")
        with marker_path.open("r", encoding="utf-8") as handle:
            marker = json.load(handle)
    except (OSError, json.JSONDecodeError) as exc:
        raise StateError("cannot read SQLite cutover marker") from exc
    if not isinstance(marker, Mapping):
        raise StateError("SQLite cutover marker must contain an object")
    marker_version = marker.get("cutover_version")
    if (
        marker.get("format") != SQLITE_CUTOVER_FORMAT
        or isinstance(marker_version, bool)
        or not isinstance(marker_version, int)
        or marker_version != CUTOVER_VERSION
    ):
        raise StateError("unsupported SQLite cutover marker version")

    database_value = marker.get("database")
    if not isinstance(database_value, str) or not database_value:
        raise StateError("SQLite cutover marker database path is invalid")
    database = Path(database_value)
    expected = sqlite_state_path(path)
    if not database.is_absolute() or database != expected:
        raise StateError("SQLite cutover marker database path does not match the canonical sibling")

    store = SqliteStateStore(database)
    status = store.status()
    expected_values = {
        "sqlite_schema_version": status.get("schema_version"),
        "state_schema_version": status.get("data_model_version"),
    }
    for key, expected_value in expected_values.items():
        marker_value = marker.get(key)
        if isinstance(marker_value, bool) or not isinstance(marker_value, int) or marker_value != expected_value:
            raise StateError("SQLite cutover marker does not match database metadata")
    marker_writer_build = marker.get("min_writer_build")
    database_writer_build = status.get("min_writer_build")
    if (
        isinstance(marker_writer_build, bool)
        or not isinstance(marker_writer_build, int)
        or marker_writer_build <= 0
        or isinstance(database_writer_build, bool)
        or not isinstance(database_writer_build, int)
        or marker_writer_build > database_writer_build
    ):
        raise StateError("SQLite cutover marker does not match database metadata")
    if marker.get("sqlite_schema_version") != SQLITE_SCHEMA_VERSION:
        raise StateError("SQLite cutover marker has an unsupported schema version")
    return store, status


class ControllerStateStore:
    """Route new controller reads and writes through the explicitly selected state format.

    Before cutover this delegates to the established JSON store. After cutover
    it accepts only a validated, compatible SQLite database. Updates hold the
    legacy state lock while selecting the backend so they serialize with the
    atomic migration and cannot write a stale JSON snapshot across cutover.
    """

    def __init__(self, path: str | os.PathLike[str] | None = None) -> None:
        self.path = Path(path).resolve() if path is not None else state_path()
        self.lock_path = self.path.with_name(".pr-review-stack.lock")

    def _active_store(self) -> Any:
        if self.path.is_dir():
            store, status = _cutover_sqlite_store(self.path)
            if status.get("compatible") is not True:
                raise StateError(f"SQLite review state is incompatible: {status.get('reason')}")
            return store
        if self.path.exists() and not self.path.is_file():
            raise StateError("review-stack state path must be a regular JSON file or SQLite cutover directory")
        return StateStore(self.path)

    def load(self) -> ReviewState:
        return self._active_store().load()

    def save(self, state: ReviewState) -> None:
        if not isinstance(state, ReviewState):
            raise TypeError("save expects ReviewState")
        with _locked(self.lock_path):
            store = self._active_store()
            if isinstance(store, StateStore):
                store._save_unlocked(state)
            else:
                store.update(lambda _: state)

    def update(self, mutate: Callable[[ReviewState], ReviewState]) -> ReviewState:
        if not callable(mutate):
            raise TypeError("mutate must be callable")
        with _locked(self.lock_path):
            store = self._active_store()
            if isinstance(store, StateStore):
                updated = mutate(store.load())
                if not isinstance(updated, ReviewState):
                    raise TypeError("mutate must return ReviewState")
                store._save_unlocked(updated)
                return updated
            return store.update(mutate)


def controller_state_status(path: str | os.PathLike[str] | None = None) -> dict[str, Any]:
    """Return a versioned, read-only view of the controller state format."""

    from .sqlite_store import WRITER_BUILD

    selected = Path(path).expanduser().absolute() if path is not None else state_path()
    base: dict[str, Any] = {
        "status_version": STATE_STATUS_VERSION,
        "format": "missing",
        "state_schema_version": None,
        "sqlite_schema_version": None,
        "min_writer_build": None,
        "running_writer_build": WRITER_BUILD,
        "compatible": False,
        "read_only": True,
        "reason": "review-stack state does not exist",
    }
    if not selected.exists():
        return base
    if selected.is_dir():
        base["format"] = "sqlite"
        try:
            _, sqlite_status = _cutover_sqlite_store(selected)
        except (OSError, StateError) as exc:
            base["format"] = "unknown"
            base["reason"] = str(exc)
            return base
        base.update(
            {
                "state_schema_version": sqlite_status.get("data_model_version"),
                "sqlite_schema_version": sqlite_status.get("schema_version"),
                "min_writer_build": sqlite_status.get("min_writer_build"),
                "compatible": sqlite_status.get("compatible") is True,
                "reason": sqlite_status.get("reason"),
            }
        )
        return base
    if not selected.is_file():
        base["format"] = "unknown"
        base["reason"] = "review-stack state path is not a regular file"
        return base
    base["format"] = "json"
    try:
        state = StateStore(selected).load()
    except (OSError, StateError) as exc:
        base["reason"] = str(exc)
        return base
    base.update(
        {
            "state_schema_version": state.schema_version,
            "compatible": state.schema_version == ReviewState().schema_version,
            "reason": None if state.schema_version == ReviewState().schema_version else "unsupported JSON state schema",
        }
    )
    return base
