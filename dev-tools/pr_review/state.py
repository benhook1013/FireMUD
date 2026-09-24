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
            str(key): _fingerprint_value(item)
            for key, item in sorted(value.items(), key=lambda pair: str(pair[0]))
        }
    if isinstance(value, (list, tuple)):
        return [_fingerprint_value(item) for item in value]
    if value is None or isinstance(value, (str, int, float, bool)):
        return value
    raise StateError("legacy transition evidence contains an unsupported value")


def observation_fingerprint(value: Any) -> str:
    """Hash one immutable evidence observation for a legacy transition."""

    try:
        encoded = json.dumps(
            _fingerprint_value(value),
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
        if self.decision not in {"rejected", "accepted_unfixed", "accepted_fixed"}:
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

    @property
    def identity(self) -> tuple[int, str, str, int, str, int]:
        return (self.pr, self.head.casefold(), self.source, self.summary_id, self.kind, self.count)

    def to_dict(self) -> dict[str, Any]:
        return dataclasses.asdict(self)

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
        }
        if set(value) - allowed:
            raise StateError("summary disposition contains fields outside the private schema")
        return cls(**{key: value[key] for key in allowed if key in value})


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
        if disposition.decision != "rejected":
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
class ReviewState:
    """The complete persisted document, excluding all live review observations."""

    ordered_prs: tuple[int, ...] = ()
    policy_overrides: Mapping[str, PolicyOverride] = dataclasses.field(default_factory=dict)
    judgments: tuple[Judgment, ...] = ()
    reconciliations: tuple[StackReconciliationDecision, ...] = ()
    legacy_transitions: tuple[LegacyEvidenceTransition, ...] = ()
    summary_dispositions: tuple[SummaryFindingDisposition, ...] = ()
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
        if any(not isinstance(item, SummaryFindingDisposition) for item in self.summary_dispositions):
            raise StateError("summary dispositions must contain validated disposition records")
        if any(not isinstance(item, LegacyEvidenceTransition) for item in self.legacy_transitions):
            raise StateError("legacy transitions must contain validated transition records")
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
        }:
            raise StateError("state contains fields outside the private configuration schema")
        if value.get("schema_version") != SCHEMA_VERSION:
            raise StateError("state has an unsupported schema version")
        raw_overrides = value.get("policy_overrides", {})
        if not isinstance(raw_overrides, Mapping):
            raise StateError("policy overrides must be an object")
        if any(not isinstance(item, Mapping) for item in raw_overrides.values()):
            raise StateError("policy override records must be objects")
        try:
            overrides = {identity: PolicyOverride.from_dict(item) for identity, item in raw_overrides.items()}
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
