"""Locked, atomic persistence for the one private repository review stack.

Only operator configuration and explicit, identity-bound decisions are stored here.
Live GitHub heads, bases, evidence, cooldowns, and merge state deliberately do not
have a representation in :class:`ReviewState`.
"""

from __future__ import annotations

import contextlib
import dataclasses
import json
import os
import re
import subprocess
import tempfile
from collections.abc import Callable, Iterator, Mapping
from pathlib import Path
from typing import Any

SCHEMA_VERSION = 1


class StateError(ValueError):
    """Raised when private review-stack state is invalid or unavailable."""


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
class ReviewState:
    """The complete persisted document, excluding all live review observations."""

    ordered_prs: tuple[int, ...] = ()
    policy_overrides: Mapping[str, PolicyOverride] = dataclasses.field(default_factory=dict)
    judgments: tuple[Judgment, ...] = ()
    reconciliations: tuple[StackReconciliationDecision, ...] = ()
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

    def to_dict(self) -> dict[str, Any]:
        return {
            "schema_version": self.schema_version,
            "ordered_prs": list(self.ordered_prs),
            "policy_overrides": {identity: item.to_dict() for identity, item in sorted(self.policy_overrides.items())},
            "judgments": [item.to_dict() for item in self.judgments],
            "reconciliations": [item.to_dict() for item in self.reconciliations],
        }

    @classmethod
    def from_dict(cls, value: Mapping[str, Any]) -> ReviewState:
        if set(value) - {"schema_version", "ordered_prs", "policy_overrides", "judgments", "reconciliations"}:
            raise StateError("state contains fields outside the private configuration schema")
        if value.get("schema_version") != SCHEMA_VERSION:
            raise StateError("state has an unsupported schema version")
        overrides = {
            identity: PolicyOverride.from_dict(item) for identity, item in value.get("policy_overrides", {}).items()
        }
        return cls(
            ordered_prs=tuple(value.get("ordered_prs", ())),
            policy_overrides=overrides,
            judgments=tuple(Judgment.from_dict(item) for item in value.get("judgments", ())),
            reconciliations=tuple(
                StackReconciliationDecision.from_dict(item) for item in value.get("reconciliations", ())
            ),
        )


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
