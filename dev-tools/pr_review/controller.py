"""Orchestration boundary for the unified pull-request review controller.

The lower-level modules in :mod:`pr_review` deliberately have small contracts:
``stack`` reconciles identities, ``policy`` decides whether evidence is complete,
and the Hosted/CLI adapters perform one review.  This module is the only place
that composes those contracts.  GitHub, Git, and private-evidence access are
injected so selection and decision tests never need a network, a checkout, or a
review process.

Only configuration and explicit operator decisions are written through
``StateStore``.  Live PRs, heads, merge bases, patch identities, cooldowns, and
checkpoint observations remain provider data and are never copied into state.
"""

from __future__ import annotations

import copy
import dataclasses
import re
import subprocess
import time
from collections.abc import Callable, Iterable, Mapping, Sequence
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from typing import Any, Protocol

from . import github, policy, stack
from .cli_runner import (
    HOSTED_ACTIVE_RESPONSE_REASON,
    HOSTED_CLI_OVERLAP_HOLD_REASON,
    EffectiveParent,
    PullRequestSnapshot,
    ReviewTarget,
    StaleReviewTargetError,
)
from .git_merge import TestMergeError, test_merge_tree
from .hosted import parse_timestamp, prepare_full_trigger
from .patch_identity import patch_identity
from .sqlite_store import SqliteStateStore
from .state import (
    ControllerStateStore,
    FindingRoute,
    Judgment,
    LegacyEvidenceTransition,
    PolicyOverride,
    ReviewAllocation,
    ReviewState,
    StackReconciliationDecision,
    StateError,
    StateLockTimeout,
    StateStore,
    merge_open_route,
    observation_fingerprint,
)


class ControllerError(RuntimeError):
    """A fail-closed controller preflight or decision error."""


class _FindingOnlyStopEvidence(ControllerError):
    """A complete audit found only typed finding obligations, with no safety blocker."""


class _RequestPreparationError(ControllerError):
    """A complete safety audit proved only known request-preparation movement."""


class WrongStackTarget(ControllerError):
    """The requested PR is not the target selected by the configured stack."""


class StaleReviewTarget(ControllerError):
    """A direct-to-default target's base advanced before review could begin."""


class HostedAdmissionBusy(ControllerError):
    """A Hosted admission lock is held; provider activity is not implied."""


class _SelectionChanged(ControllerError):
    """Durable selection inputs changed before provider admission."""


GIT_TIMEOUT_SECONDS = 30
MAX_BASE_RESELECTIONS = 2
HOSTED_ADMISSION_RETRY_BASE_SECONDS = 0.05
HOSTED_ADMISSION_RETRY_MAX_SECONDS = 0.2
LEGACY_UNCHECKPOINTED = re.compile(r"^trigger-uncheckpointed:[1-9][0-9]*$")
DRAFT_PR_NOTICE = "Draft PR — mark ready for review if preparation is complete."


class GitProvider(Protocol):
    """Git facts needed to anchor a review to one exact candidate."""

    def remote_heads(self) -> Mapping[str, str]: ...

    def branch_head(self, ref_name: str) -> str: ...

    def branch_exists(self, ref_name: str) -> bool: ...

    def is_ancestor(self, ancestor: str, descendant: str) -> bool: ...

    def merge_base(self, left: str, right: str) -> str: ...

    def patch_identity(self, merge_base: str, head: str) -> str: ...

    def test_merge_tree(self, base: str, head: str) -> str: ...


class GitHubProvider(Protocol):
    """Read-only live PR facts.

    Implementations may return mappings or :class:`LivePullRequest` values.  A
    provider is intentionally narrower than the raw GraphQL adapter so the
    controller is easy to use with a hermetic fixture.
    """

    def pull_request(self, number: int) -> Any: ...


class EvidenceProvider(Protocol):
    """Historical channel evidence, optionally including review anchors."""

    def history(self, pr: int, channel: str) -> Sequence[Any]: ...


class DefaultGitProvider:
    """Small subprocess-backed Git implementation used by the real CLI."""

    def __init__(self, root: str | Path | None = None, *, timeout_seconds: float = GIT_TIMEOUT_SECONDS) -> None:
        self.root = Path(root or Path.cwd()).resolve()
        self.timeout_seconds = timeout_seconds

    def _run_process(
        self,
        args: Sequence[str],
        *,
        check: bool = True,
        capture_output: bool = True,
        text: bool = False,
        timeout_seconds: float | None = None,
        enforce_preflight_budget: bool = True,
    ) -> subprocess.CompletedProcess[str] | subprocess.CompletedProcess[bytes]:
        timeout_limit = self.timeout_seconds if timeout_seconds is None else min(self.timeout_seconds, timeout_seconds)
        budget = github.active_hosted_preflight_budget() if enforce_preflight_budget else None
        timeout = budget.request_timeout(timeout_limit) if budget is not None else timeout_limit
        try:
            completed = subprocess.run(
                list(args),
                check=check,
                capture_output=capture_output,
                text=text,
                timeout=timeout,
            )
        except subprocess.TimeoutExpired as error:
            if budget is not None:
                try:
                    budget.remaining_seconds()
                except github.HostedPreflightDeadlineExceeded as deadline_error:
                    raise deadline_error from error
            raise ControllerError(f"git command timed out after {timeout_limit} seconds") from error
        if budget is not None:
            budget.remaining_seconds()
        return completed

    def _run(self, *args: str, check: bool = True) -> str:
        result = self._run_process(
            ["git", "-C", str(self.root), *args],
            check=check,
            text=True,
        )
        return result.stdout.strip()

    def remote_heads(self) -> Mapping[str, str]:
        """Read one unambiguous snapshot of every remote branch head."""

        heads: dict[str, str] = {}
        output = self._run("ls-remote", "--heads", "origin")
        for line_number, line in enumerate(output.splitlines(), 1):
            fields = line.split()
            if len(fields) != 2 or not fields[1].startswith("refs/heads/"):
                raise ControllerError(f"remote branch snapshot line {line_number} is malformed")
            ref_name = fields[1][len("refs/heads/") :]
            if not ref_name or ref_name in heads:
                raise ControllerError(f"remote branch snapshot has an ambiguous {ref_name!r} ref")
            heads[ref_name] = _sha(fields[0], f"remote branch {ref_name!r} head")
        return heads

    def branch_head(self, ref_name: str) -> str:
        heads = self.remote_heads()
        if ref_name not in heads:
            raise ControllerError(f"branch {ref_name!r} does not resolve to one remote head")
        return heads[ref_name]

    def branch_exists(self, ref_name: str) -> bool:
        return ref_name in self.remote_heads()

    def _ensure_commit(self, commit: str) -> None:
        present = self._run_process(
            ["git", "-C", str(self.root), "cat-file", "-e", f"{commit}^{{commit}}"],
            check=False,
        )
        if present.returncode != 0:
            self._run("fetch", "--no-tags", "origin", commit)

    def is_ancestor(self, ancestor: str, descendant: str) -> bool:
        self._ensure_commit(ancestor)
        self._ensure_commit(descendant)
        return (
            self._run_process(
                ["git", "-C", str(self.root), "merge-base", "--is-ancestor", ancestor, descendant],
                check=False,
            ).returncode
            == 0
        )

    def merge_base(self, left: str, right: str) -> str:
        self._ensure_commit(left)
        self._ensure_commit(right)
        values = self._run("merge-base", "--all", left, right).splitlines()
        if len(values) != 1:
            raise ControllerError("candidate has no unique merge base")
        return values[0]

    def patch_identity(self, merge_base: str, head: str) -> str:
        self._ensure_commit(merge_base)
        self._ensure_commit(head)

        def run_diff(args: Sequence[str]) -> bytes | str:
            result = self._run_process(
                ["git", "-C", str(self.root), *args],
                check=True,
            )
            return result.stdout

        return patch_identity(run_diff, merge_base, head)

    def test_merge_tree(self, base: str, head: str) -> str:
        """Return the clean merge tree for one exact base/head tuple."""

        normalized_base = _sha(base, "test-merge base")
        normalized_head = _sha(head, "test-merge head")
        self._ensure_commit(normalized_base)
        self._ensure_commit(normalized_head)

        def run_git(args, *, check, text, timeout):
            command = args[args.index("-C") + 2 :] if "-C" in args else []
            if len(command) == 4 and command[:3] == ["worktree", "remove", "--force"]:
                return self._run_process(
                    args,
                    check=check,
                    text=text,
                    timeout_seconds=timeout,
                    enforce_preflight_budget=False,
                )
            return self._run_process(args, check=check, text=text)

        try:
            return test_merge_tree(
                self.root,
                normalized_base,
                normalized_head,
                run=run_git,
                timeout_seconds=self.timeout_seconds,
            )
        except TestMergeError as error:
            raise ControllerError(str(error)) from error


class EmptyEvidence:
    def history(self, _pr: int, _channel: str) -> Sequence[Any]:
        return ()


@dataclasses.dataclass(frozen=True)
class LivePullRequest:
    """Canonical live facts consumed by stack reconciliation."""

    number: int
    head: str
    base_ref: str
    base_tip: str
    head_ref: str = ""
    merged: bool = False
    state: str = "OPEN"
    mergeable: str = "MERGEABLE"
    base_exists: bool = True
    changed_files: int = 0
    head_repository: str | None = None
    is_draft: bool = False

    def snapshot(self) -> stack.PRSnapshot:
        return stack.PRSnapshot(
            self.number,
            self.head,
            self.base_ref,
            self.base_tip,
            self.merged,
            self.head_ref or str(self.number),
        )

    def runner_snapshot(self) -> PullRequestSnapshot:
        return PullRequestSnapshot(
            self.number,
            self.state,
            self.base_ref,
            self.base_tip,
            self.head,
            self.head_ref,
            self.changed_files,
            self.mergeable,
            self.merged,
            self.base_exists,
            self.head_repository,
            self.is_draft,
        )


@dataclasses.dataclass(frozen=True)
class AnchorFacts:
    """Current identity used to bind policy evidence to the stack."""

    pr: int
    child_head: str
    parent_identity: str
    parent_head: str
    merge_base: str | None
    patch_id: str | None
    stop_audit_pr_base_oid: str | None = dataclasses.field(default=None, compare=False, repr=False)
    stop_audit_base_ref: str | None = dataclasses.field(default=None, compare=False, repr=False)
    stop_audit_effective_parent_head: str | None = dataclasses.field(default=None, compare=False, repr=False)
    stop_audit_enforce_parent_identity_ref: bool = dataclasses.field(default=True, compare=False, repr=False)

    def as_dict(self) -> dict[str, Any]:
        # Persisted review anchors describe the stack identity. The retained PR
        # base and selected branch ref are transient stop-audit inputs, not part
        # of reconciliation, taper, or review-credit identity.
        return {
            "pr": self.pr,
            "child_head": self.child_head,
            "parent_identity": self.parent_identity,
            "parent_head": self.parent_head,
            "merge_base": self.merge_base,
            "patch_id": self.patch_id,
        }

    def as_stop_audit_dict(self) -> dict[str, Any]:
        return {
            **self.as_dict(),
            "pr_base_oid": self.stop_audit_pr_base_oid,
            "base_ref": self.stop_audit_base_ref,
            "effective_parent_head": self.stop_audit_effective_parent_head,
            "enforce_parent_identity_ref": self.stop_audit_enforce_parent_identity_ref,
        }

    def as_anchor(self) -> stack.ReviewAnchor:
        return stack.ReviewAnchor(
            self.pr,
            self.child_head,
            self.parent_identity,
            self.parent_head,
            self.merge_base,
            self.patch_id,
        )


@dataclasses.dataclass(frozen=True)
class Target:
    """A selected channel target and all identity facts used to select it."""

    channel: policy.Channel
    pr: int
    status: policy.ReviewStatus
    reason: str
    target: ReviewTarget
    anchor: AnchorFacts
    provisional: bool = False
    selection_inputs: tuple[Any, ...] | None = None
    is_draft: bool = False

    def as_dict(self) -> dict[str, Any]:
        return {
            "channel": self.channel.value,
            "pr": self.pr,
            "status": self.status.value,
            "reason": self.reason,
            "provisional": self.provisional,
            "anchor": self.anchor.as_dict(),
            "is_draft": self.is_draft,
            "draft_notice": DRAFT_PR_NOTICE if self.is_draft else None,
        }


def _sha(value: Any, label: str) -> str:
    if not isinstance(value, str) or len(value) != 40 or any(c not in "0123456789abcdefABCDEF" for c in value):
        raise ControllerError(f"{label} must be a full commit SHA")
    return value.lower()


def _as_bool(value: Any, default: bool = False) -> bool:
    return value if isinstance(value, bool) else default


def _head_repository(value: Any) -> str | None:
    """Normalize GitHub's source repository identity without guessing when absent."""

    if value is None:
        return None
    if isinstance(value, Mapping):
        value = value.get("nameWithOwner")
    if (
        not isinstance(value, str)
        or value.count("/") != 1
        or any(not part or any(character.isspace() for character in part) for part in value.split("/"))
    ):
        raise ControllerError("pull request has a malformed head repository identity")
    return value


def _live(value: Any, number: int) -> LivePullRequest:
    if isinstance(value, LivePullRequest):
        if value.number != number:
            raise ControllerError("provider returned the wrong pull request")
        return value
    if isinstance(value, PullRequestSnapshot):
        if value.number != number:
            raise ControllerError("provider returned the wrong pull request")
        return LivePullRequest(
            value.number,
            _sha(value.head_sha, "head"),
            value.base_ref_name,
            _sha(value.base_sha, "base"),
            value.head_ref_name,
            value.merged or value.state.upper() == "MERGED",
            value.state.upper(),
            value.mergeable.upper(),
            value.base_exists,
            value.changed_files,
            value.head_repository,
            value.is_draft,
        )
    if not isinstance(value, Mapping):
        raise ControllerError("pull-request provider returned an unsupported value")
    data = value.get("data", {}).get("repository", {}).get("pullRequest", value)
    if not isinstance(data, Mapping):
        raise ControllerError("pull-request provider returned no pull request")
    actual = int(data.get("number", number))
    if actual != number:
        raise ControllerError("provider returned the wrong pull request")
    head = data.get("head", data.get("headRefOid", data.get("head_sha")))
    base = data.get("base", data.get("baseRefOid", data.get("base_sha")))
    head_ref = data.get("head_ref", data.get("headRefName", ""))
    head_repository = _head_repository(data.get("head_repository", data.get("headRepository")))
    base_ref = data.get("base_ref", data.get("baseRefName"))
    if not isinstance(base_ref, str) or not base_ref:
        raise ControllerError("pull request has no base ref")
    state = str(data.get("state", "OPEN")).upper()
    mergeable = str(data.get("mergeable", "MERGEABLE")).upper()
    return LivePullRequest(
        actual,
        _sha(head, "head"),
        base_ref,
        _sha(base, "base"),
        str(head_ref or ""),
        _as_bool(data.get("merged"), bool(data.get("mergedAt"))),
        state,
        mergeable,
        _as_bool(data.get("base_exists"), True),
        int(data.get("changed_files", data.get("changedFiles", 0))),
        head_repository,
        _as_bool(data.get("isDraft", data.get("is_draft")), False),
    )


def _history(provider: Any, pr: int, channel: policy.Channel) -> list[Any]:
    if provider is None:
        return []
    if hasattr(provider, "history"):
        value = provider.history(pr, channel.value)
    elif callable(provider):
        value = provider(pr, channel.value)
    elif isinstance(provider, Mapping):
        value = provider.get((pr, channel.value), provider.get(pr, ()))
        if isinstance(value, Mapping):
            value = value.get(channel.value, ())
    else:
        raise ControllerError("evidence provider has no history interface")
    if value is None:
        return []
    if not isinstance(value, Sequence) or isinstance(value, (str, bytes)):
        raise ControllerError("evidence history must be a sequence")
    return list(value)


def _latest_review(history: Sequence[Any]) -> Any | None:
    """Return the latest policy-effective completed, attributable review."""

    for item in reversed(history):
        if (
            _field(item, "completed") is True
            and _field(item, "attributable") is True
            and _field(item, "non_counting") is not True
            and _field(item, "provisional") is not True
            and _field(item, "correction") is not True
        ):
            return item
    return None


def _review_activity(history: Sequence[Any], current_head: str) -> dict[str, Any]:
    """Summarize observed review rounds for display, never for policy decisions."""

    results: list[dict[str, Any]] = []
    for item in history:
        if (
            _field(item, "completed") is not True
            or _field(item, "provisional") is True
            or _field(item, "correction") is True
            or _field(item, "rate_limited") is True
        ):
            continue
        raw = _field(item, "raw", "raw_found")
        accepted = _field(item, "accepted")
        routed = _field(item, "routed")
        if (
            type(raw) is not int
            or type(accepted) is not int
            or raw < 0
            or not 0 <= accepted <= raw
            or (routed is not None and (type(routed) is not int or routed < 0 or accepted + routed > raw))
        ):
            continue
        reviewed_head = _field(item, "head", "reviewed_head")
        observed_at = parse_timestamp(_field(item, "observed_at"))
        results.append(
            {
                "raw": raw,
                "accepted": accepted,
                "routed": routed,
                "completed_at": observed_at.isoformat().replace("+00:00", "Z") if observed_at else None,
                "attributable": _field(item, "attributable") is True,
                "current_head": isinstance(reviewed_head, str) and reviewed_head == current_head,
                "non_counting": _field(item, "non_counting") is True,
            }
        )
    active = [
        {"checkpoint": _field(item, "checkpoint"), "head": _field(item, "head"), "reason": _field(item, "reason")}
        for item in history
        if _field(item, "active_review") is True or _field(item, "active_reservation") is True
    ]
    return {"total": len(results), "recent": results[-5:], "active": active}


def _channel_reasons(history: Sequence[Any], current_head: str) -> list[dict[str, Any]]:
    """Expose current-head provider coverage limits as visible non-counting status."""

    results: list[dict[str, Any]] = []
    for item in history:
        checkpoint = _field(item, "checkpoint", "checkpoint_id")
        if (
            _field(item, "over_ceiling") is not True
            or not isinstance(checkpoint, str)
            or not checkpoint.startswith("over-ceiling:")
            or not isinstance(_field(item, "head", "reviewed_head"), str)
            or _field(item, "head", "reviewed_head").casefold() != current_head.casefold()
        ):
            continue
        reason = _field(item, "reason")
        results.append(
            {
                "reason": reason
                if isinstance(reason, str) and reason.strip()
                else "provider file ceiling limits review coverage",
                "source": checkpoint,
                "non_counting": True,
            }
        )
    return results


def _field(value: Any, name: str, *aliases: str) -> Any:
    if isinstance(value, Mapping):
        for key in (name, *aliases):
            if key in value:
                return value[key]
    return getattr(value, name, None)


def _allocation_reopens_selection(view: Mapping[str, Any]) -> bool:
    """Return whether an allocation keeps an otherwise complete channel open."""

    status = view.get("status")
    remaining = view.get("remaining")
    has_capacity = remaining is None or (isinstance(remaining, int) and remaining > 0)
    return (
        (status == "CAP_ACTIVE" and view.get("selection_control") == "minimum" and has_capacity)
        or (
            status == "CAP_ACTIVE"
            and view.get("selection_control") == "taper"
            and view.get("completed_count", 0) > 0
            and has_capacity
        )
        or (view.get("reopens_taper") is True and status in {"PROMISED", "CAP_ACTIVE"} and has_capacity)
        or status == "PROMISED"
    )


class ReviewController:
    """Single-stack orchestration API used by the command dispatcher."""

    def __init__(
        self,
        *,
        store: StateStore | None = None,
        github: GitHubProvider | None = None,
        git: GitProvider | None = None,
        evidence: EvidenceProvider | Mapping[Any, Any] | Callable[..., Sequence[Any]] | None = None,
        default_base_ref: str = "develop",
        repository: str = "",
        hosted_adapter: Callable[..., Any] | None = None,
        cli_adapter: Callable[..., Any] | None = None,
        isolated_fixture: bool = False,
    ) -> None:
        self.store = store or StateStore()
        self.github = github
        self.git = git or DefaultGitProvider()
        self._evidence_provider = evidence if evidence is not None else EmptyEvidence()
        self.default_base_ref = default_base_ref
        self.repository = repository
        self.hosted_adapter = hosted_adapter
        self.cli_adapter = cli_adapter
        self.isolated_fixture = isolated_fixture
        self._default_test_merge_proofs: dict[int, tuple[str, str, str]] = {}
        self._test_merge_tree_cache: dict[tuple[str, str], str] = {}

    def _require_github(self) -> GitHubProvider:
        if self.github is None:
            raise ControllerError("a GitHub provider is required for live review operations")
        return self.github

    def _state(self) -> ReviewState:
        budget = github.active_hosted_preflight_budget()
        if budget is not None and isinstance(self.store, SqliteStateStore):
            try:
                return self.store.load(deadline=budget.deadline)
            except StateLockTimeout:
                ControllerStateStore._check_hosted_deadline(budget.deadline, lock_timeout=True)
                raise
        return self.store.load()

    @staticmethod
    def _legacy_transition_for(state: ReviewState, pr: int, anchor: AnchorFacts) -> LegacyEvidenceTransition | None:
        """Return the newest exact-anchor legacy transition, if any."""

        for transition in reversed(state.legacy_transitions):
            if transition.matches(pr, anchor.as_dict()):
                return transition
        return None

    @staticmethod
    def _mark_non_counting(value: Any) -> Any:
        if isinstance(value, Mapping):
            marked = dict(value)
            marked["non_counting"] = True
            return marked
        if isinstance(value, policy.Evidence):
            return dataclasses.replace(value, non_counting=True)
        if dataclasses.is_dataclass(value) and hasattr(value, "non_counting"):
            return dataclasses.replace(value, non_counting=True)
        raise ControllerError("legacy transition encountered an unsupported evidence record")

    @staticmethod
    def _clear_untrusted_non_counting(value: Any) -> Any:
        """Do not trust caller-supplied policy projection markers."""

        if isinstance(value, Mapping):
            cleared = dict(value)
            if not ReviewController._intrinsic_scope_projection(value):
                cleared.pop("non_counting", None)
            cleared.pop("streak_break_before", None)
            cleared.pop("lineage_proven_to_next", None)
            cleared.pop("current_candidate_descendant_proven", None)
            return cleared
        if isinstance(value, policy.Evidence):
            return dataclasses.replace(
                value,
                non_counting=False,
                streak_break_before=False,
                lineage_proven_to_next=False,
                current_candidate_descendant_proven=False,
            )
        if dataclasses.is_dataclass(value) and hasattr(value, "non_counting"):
            updates = {"non_counting": False}
            if hasattr(value, "streak_break_before"):
                updates["streak_break_before"] = False
            if hasattr(value, "lineage_proven_to_next"):
                updates["lineage_proven_to_next"] = False
            if hasattr(value, "current_candidate_descendant_proven"):
                updates["current_candidate_descendant_proven"] = False
            return dataclasses.replace(value, **updates)
        return value

    def _counting_history_for_anchor(
        self,
        state: ReviewState,
        pr: int,
        channel: policy.Channel,
        anchor: AnchorFacts,
        *,
        history_cache: dict[tuple[int, str], list[Any]] | None = None,
        phase_timings: dict[str, float] | None = None,
    ) -> list[Any]:
        """Return only observations not captured by the exact legacy transition."""

        history = self._cached_history(pr, channel, history_cache, phase_timings=phase_timings)
        transition = self._legacy_transition_for(state, pr, anchor)
        if transition is None:
            return [self._clear_untrusted_non_counting(value) for value in history]
        selected = set(transition.fingerprints_for(channel.value))
        projected: list[Any] = []
        for value in history:
            if observation_fingerprint(value) not in selected:
                projected.append(self._clear_untrusted_non_counting(value))
        return projected

    def _policy_history(
        self,
        state: ReviewState,
        pr: int,
        channel: policy.Channel,
        reconciliation: stack.Reconciliation,
        *,
        history_cache: dict[tuple[int, str], list[Any]] | None = None,
        phase_timings: dict[str, float] | None = None,
    ) -> list[Any]:
        """Project exact legacy observations as non-counting policy history.

        The raw evidence remains available through ``evidence``.  A transition
        only marks records whose immutable fingerprint was captured by that
        exact current anchor; a later, altered, or otherwise new observation is
        deliberately left visible to policy and can still block review.
        """

        history = self._cached_history(pr, channel, history_cache, phase_timings=phase_timings)
        selected = set(reconciliation.legacy_transition_fingerprints.get(pr, {}).get(channel.value, ()))
        if not selected:
            projected = [self._clear_untrusted_non_counting(value) for value in history]
            return self._project_cli_hosted_ambiguity(channel, projected)
        projected: list[Any] = []
        for value in history:
            if observation_fingerprint(value) in selected:
                projected.append(self._mark_non_counting(value))
            else:
                projected.append(self._clear_untrusted_non_counting(value))
        return self._project_cli_hosted_ambiguity(channel, projected)

    def _cached_history(
        self,
        pr: int,
        channel: policy.Channel,
        history_cache: dict[tuple[int, str], list[Any]] | None,
        *,
        phase_timings: dict[str, float] | None = None,
    ) -> list[Any]:
        """Read one channel history once during a composed status invocation."""

        if history_cache is None:
            return _history(self._evidence_provider, pr, channel)
        key = (pr, channel.value)
        if key not in history_cache:
            started = time.perf_counter() if phase_timings is not None else 0.0
            budget = github.active_hosted_preflight_budget()
            if budget is not None:
                budget.set_phase(f"target_history_pr_{pr}_{channel.value}", total=1)
            try:
                history_cache[key] = _history(self._evidence_provider, pr, channel)
                if budget is not None:
                    budget.set_completed(1)
            finally:
                if phase_timings is not None:
                    phase_timings["deep_evidence_history_ms"] = (
                        phase_timings.get("deep_evidence_history_ms", 0.0) + (time.perf_counter() - started) * 1000
                    )
        return history_cache[key]

    def _prefetch_target_history_payloads(
        self,
        state: ReviewState,
        live: Mapping[int, LivePullRequest],
        anchors: Mapping[int, AnchorFacts],
        selected_evidence_prs: set[int],
        history_cache: dict[tuple[int, str], list[Any]] | None,
    ) -> None:
        """Warm only complete histories this request selection will consume."""

        budget = github.active_hosted_preflight_budget()
        prefetch = getattr(self._evidence_provider, "prefetch_history_payloads", None)
        if budget is None or not callable(prefetch):
            return
        required: list[int] = []
        channels = (policy.Channel.HOSTED, policy.Channel.CLI)
        for pr in state.ordered_prs:
            if pr not in selected_evidence_prs or live[pr].merged:
                continue
            missing_history = history_cache is None or any(
                (pr, channel.value) not in history_cache for channel in channels
            )
            anchor = anchors.get(pr)
            stopped_reconciliation_exception = bool(
                anchor is not None
                and any(
                    decision.pr == pr
                    and decision.matches(pr, anchor.as_dict())
                    and (allocation := state.allocations.get(f"{pr}:{decision.channel}")) is not None
                    and allocation.stop_basis is not None
                    for decision in state.reconciliations
                )
            )
            if missing_history or stopped_reconciliation_exception:
                required.append(pr)
        if required:
            prefetch(tuple(required))

    @staticmethod
    def _project_cli_hosted_ambiguity(channel: policy.Channel, history: Sequence[Any]) -> list[Any]:
        """Keep verified terminal Hosted attribution ambiguity from holding CLI policy."""

        if channel != policy.Channel.CLI:
            return list(history)
        projected: list[Any] = []
        for value in history:
            if not isinstance(value, Mapping):
                projected.append(value)
                continue
            if (
                _field(value, "terminal_ambiguous") is True
                and _field(value, "terminal") is True
                and _field(value, "attributable") is False
                and isinstance(_field(value, "response_id"), int)
                and not isinstance(_field(value, "response_id"), bool)
                and _field(value, "response_id") > 0
                and isinstance(_field(value, "fingerprint"), str)
                and re.fullmatch(r"[0-9a-f]{64}", _field(value, "fingerprint")) is not None
            ):
                value = {key: item for key, item in value.items() if key not in {"held", "unstable"}}
            projected.append(value)
        return projected

    @staticmethod
    def _project_cli_hosted_reservations(
        cli_history: Sequence[Any],
        hosted_history: Sequence[Any],
        expected_pr: int,
        current_head: str,
        current_anchor: AnchorFacts | None,
    ) -> list[Any]:
        """Apply the runner's exact-anchor Hosted overlap fence to CLI policy."""

        projected = list(cli_history)
        for value in hosted_history:
            if not isinstance(value, Mapping):
                continue
            checkpoint = _field(value, "checkpoint", "checkpoint_id")
            if not isinstance(checkpoint, str) or not checkpoint.startswith("trigger:"):
                continue
            if _field(value, "rate_limited") is True:
                continue

            observed_head = _field(value, "head", "reviewed_head")
            trigger_match = re.fullmatch(r"trigger:([1-9][0-9]*)", checkpoint)
            trigger_id = _field(value, "trigger_id")
            exact_trigger = trigger_match is not None and (
                trigger_id is None or (type(trigger_id) is int and trigger_id == int(trigger_match.group(1)))
            )
            terminal_ambiguity = (
                _field(value, "terminal_ambiguous") is True
                and _field(value, "state") == "ambiguous"
                and _field(value, "pr") == expected_pr
                and exact_trigger
                and type(_field(value, "response_id")) is int
                and _field(value, "response_id") > 0
                and type(_field(value, "trigger_id")) is int
                and _field(value, "trigger_id") > 0
                and isinstance(_field(value, "fingerprint"), str)
                and re.fullmatch(r"[0-9a-f]{64}", _field(value, "fingerprint")) is not None
                and isinstance(_field(value, "captured_head"), str)
                and re.fullmatch(r"[0-9a-fA-F]{40}", _field(value, "captured_head")) is not None
            )
            if terminal_ambiguity:
                # Exact terminal ambiguity is historical, non-counting context
                # and remains independent of CLI after the PR head advances.
                continue

            active_response = ReviewController._hosted_active_response_overlaps_cli(
                value,
                expected_pr,
                current_head,
                current_anchor,
            )
            if active_response:
                # LiveEvidence emits this exact reason only after trigger_state
                # verifies an active response and its immutable numeric ID.
                # The runner repeats the full identity check under request.lock.
                continue
            if _field(value, "held") is not True and _field(value, "unstable") is not True:
                continue
            projected.append(
                {
                    "pr": expected_pr,
                    "channel": "hosted",
                    "head": observed_head if isinstance(observed_head, str) else current_head,
                    "checkpoint": checkpoint,
                    "held": True,
                    "unstable": _field(value, "unstable") is True,
                    "reason": HOSTED_CLI_OVERLAP_HOLD_REASON,
                }
            )
        return projected

    @staticmethod
    def _cli_streak_review(value: Any, expected_pr: int) -> tuple[str | None, AnchorFacts | None] | None:
        """Return the reviewed head and complete modern anchor for one CLI result."""

        if (
            _field(value, "correction") is True
            or _field(value, "non_counting") is True
            or _field(value, "completed") is not True
            or _field(value, "attributable") is not True
            or _field(value, "provisional") is True
        ):
            return None
        raw_head = _field(value, "head", "reviewed_head")
        try:
            head = _sha(raw_head, "CLI review head")
        except ControllerError:
            head = None
        if (
            type(_field(value, "pr")) is not int
            or _field(value, "pr") != expected_pr
            or _field(value, "anchored") is not True
        ):
            return head, None
        parent_identity = _field(value, "parent_identity", "parent_ref")
        patch_id = _field(value, "patch_id", "patch_identity")
        if not isinstance(parent_identity, str) or not parent_identity.strip():
            return head, None
        if not isinstance(patch_id, str) or not patch_id.strip():
            return head, None
        try:
            child_head = _sha(_field(value, "child_head", "candidate_sha"), "CLI review child head")
            parent_head = _sha(_field(value, "parent_head", "parent_sha", "base_tip_sha"), "CLI review parent head")
            merge_base = _sha(_field(value, "merge_base"), "CLI review merge base")
        except ControllerError:
            return head, None
        if head is None or child_head != head:
            return head, None
        return head, AnchorFacts(expected_pr, child_head, parent_identity, parent_head, merge_base, patch_id)

    def _same_cli_review_lineage(
        self,
        earlier: AnchorFacts,
        later: AnchorFacts,
        older_review: Any,
        state: ReviewState,
    ) -> bool:
        """Prove child ancestry and, when topology moved, exact retained patch continuity."""

        if earlier.pr != later.pr:
            return False

        child_lineage = earlier.child_head.casefold() == later.child_head.casefold()
        if not child_lineage:
            try:
                child_lineage = self.git.is_ancestor(earlier.child_head, later.child_head) is True
            except (ControllerError, OSError, subprocess.SubprocessError, ValueError):
                child_lineage = False
        if not child_lineage:
            return False

        topology_matches = (
            earlier.parent_identity == later.parent_identity
            and earlier.merge_base.casefold() == later.merge_base.casefold()
        )
        if topology_matches:
            if earlier.parent_head.casefold() == later.parent_head.casefold():
                return True
            try:
                return self.git.is_ancestor(earlier.parent_head, later.parent_head) is True
            except (ControllerError, OSError, subprocess.SubprocessError, ValueError):
                return False

        checkpoint = _field(older_review, "checkpoint", "checkpoint_id")
        return (
            earlier.patch_id == later.patch_id
            and isinstance(checkpoint, str)
            and any(
                judgment.decision == "retain"
                and judgment.applies(
                    earlier.pr,
                    policy.Channel.CLI.value,
                    earlier.child_head,
                    checkpoint,
                    earlier.patch_id,
                )
                for judgment in state.judgments
            )
        )

    def _project_cli_streak_lineage(
        self,
        history: Sequence[Any],
        expected_pr: int,
        state: ReviewState,
        current_anchor: AnchorFacts | None = None,
    ) -> list[Any]:
        """Add transient boundaries where consecutive CLI rounds lack lineage proof."""

        projected: list[Any] = []
        previous_review: tuple[int, Any, tuple[str | None, AnchorFacts | None]] | None = None
        for value in history:
            review = self._cli_streak_review(value, expected_pr)
            break_before = False
            if review is not None and previous_review is not None:
                previous_index, _, (previous_head, previous_anchor) = previous_review
                current_head, current_review_anchor = review
                if previous_head is None or current_head is None:
                    break_before = True
                else:
                    same_head = previous_head.casefold() == current_head.casefold()
                    if previous_anchor is None or current_review_anchor is None:
                        # Legacy same-head history retains its pre-existing behavior.
                        # Cross-head history must carry complete modern anchors.
                        break_before = not same_head
                    elif self._same_cli_review_lineage(
                        previous_anchor, current_review_anchor, previous_review[1], state
                    ):
                        previous_value = projected[previous_index]
                        if isinstance(previous_value, Mapping):
                            previous_value = dict(previous_value)
                            previous_value["lineage_proven_to_next"] = True
                        elif isinstance(previous_value, policy.Evidence) or (
                            dataclasses.is_dataclass(previous_value)
                            and hasattr(previous_value, "lineage_proven_to_next")
                        ):
                            previous_value = dataclasses.replace(previous_value, lineage_proven_to_next=True)
                        projected[previous_index] = previous_value
                    else:
                        break_before = True
            if isinstance(value, Mapping):
                marked = dict(value)
                marked.pop("streak_break_before", None)
                marked.pop("lineage_proven_to_next", None)
                if break_before:
                    marked["streak_break_before"] = True
                projected.append(marked)
            elif isinstance(value, policy.Evidence):
                projected.append(
                    dataclasses.replace(
                        value,
                        streak_break_before=break_before,
                        lineage_proven_to_next=False,
                        current_candidate_descendant_proven=False,
                    )
                )
            elif dataclasses.is_dataclass(value) and hasattr(value, "streak_break_before"):
                updates = {"streak_break_before": break_before}
                if hasattr(value, "lineage_proven_to_next"):
                    updates["lineage_proven_to_next"] = False
                if hasattr(value, "current_candidate_descendant_proven"):
                    updates["current_candidate_descendant_proven"] = False
                projected.append(dataclasses.replace(value, **updates))
            else:
                projected.append(value)
            if review is not None:
                previous_review = (len(projected) - 1, projected[-1], review)
        if previous_review is not None and current_anchor is not None:
            last_index, last_value, (last_head, last_anchor) = previous_review
            if (
                last_head is not None
                and last_anchor is not None
                and last_head.casefold() != current_anchor.child_head.casefold()
                and self._same_cli_review_lineage(last_anchor, current_anchor, last_value, state)
            ):
                current_value = projected[last_index]
                if isinstance(current_value, Mapping):
                    current_value = dict(current_value)
                    current_value["current_candidate_descendant_proven"] = True
                elif isinstance(current_value, policy.Evidence) or (
                    dataclasses.is_dataclass(current_value)
                    and hasattr(current_value, "current_candidate_descendant_proven")
                ):
                    current_value = dataclasses.replace(current_value, current_candidate_descendant_proven=True)
                projected[last_index] = current_value
        return projected

    @staticmethod
    def _hosted_anchor_matches(
        observation: Any,
        expected_pr: int,
        current_anchor: AnchorFacts | None,
    ) -> bool:
        if (
            current_anchor is None
            or current_anchor.pr != expected_pr
            or current_anchor.merge_base is None
            or current_anchor.patch_id is None
        ):
            return False
        anchor = _field(observation, "anchor")
        if not isinstance(anchor, Mapping) or type(anchor.get("pr")) is not int:
            return False
        if anchor.get("pr") != expected_pr:
            return False
        expected = current_anchor.as_dict()
        for name in ("child_head", "parent_head", "merge_base"):
            value = anchor.get(name)
            target = expected[name]
            if (
                not isinstance(value, str)
                or re.fullmatch(r"[0-9a-fA-F]{40}", value) is None
                or value.casefold() != target.casefold()
            ):
                return False
        return (
            isinstance(anchor.get("parent_identity"), str)
            and anchor.get("parent_identity") == expected["parent_identity"]
            and isinstance(anchor.get("patch_id"), str)
            and bool(anchor.get("patch_id"))
            and anchor.get("patch_id") == expected["patch_id"]
        )

    @staticmethod
    def _hosted_active_response_overlaps_cli(
        observation: Any,
        expected_pr: int,
        current_head: str,
        current_anchor: AnchorFacts | None,
        *,
        require_response_identity: bool = False,
    ) -> bool:
        checkpoint = _field(observation, "checkpoint", "checkpoint_id")
        observed_head = _field(observation, "head", "reviewed_head")
        trigger_match = re.fullmatch(r"trigger:([1-9][0-9]*)", checkpoint) if isinstance(checkpoint, str) else None
        trigger_id = _field(observation, "trigger_id")
        exact_trigger = trigger_match is not None and (
            trigger_id is None or (type(trigger_id) is int and trigger_id == int(trigger_match.group(1)))
        )
        if not (
            _field(observation, "reason") == HOSTED_ACTIVE_RESPONSE_REASON
            and _field(observation, "held") is True
            and _field(observation, "unstable") is not True
            and _field(observation, "pr") == expected_pr
            and isinstance(observed_head, str)
            and re.fullmatch(r"[0-9a-fA-F]{40}", observed_head) is not None
            and observed_head.casefold() == current_head.casefold()
            and exact_trigger
            and ReviewController._hosted_anchor_matches(observation, expected_pr, current_anchor)
        ):
            return False
        if not require_response_identity:
            return True
        response_id = _field(observation, "response_id")
        return (
            type(trigger_id) is int
            and trigger_id > 0
            and type(response_id) is int
            and response_id > 0
            and _field(observation, "state") == "active"
            and _field(observation, "active_reservation") is True
            and _field(observation, "attributable") is True
            and _field(observation, "terminal") is False
        )

    @staticmethod
    def _hosted_awaiting_response_matches(
        observation: Any,
        expected_pr: int,
        current_anchor: AnchorFacts | None,
    ) -> bool:
        """Verify a posted request awaiting acknowledgment without counting completion."""
        if current_anchor is None:
            return False
        trigger_id = _field(observation, "trigger_id")
        observed_head = _field(observation, "head", "reviewed_head")
        return (
            _field(observation, "state") == "awaiting_response"
            and _field(observation, "posted") is True
            and _field(observation, "reason") == "no attributable terminal response"
            and _field(observation, "held") is True
            and _field(observation, "active_reservation") is True
            and _field(observation, "unstable") is not True
            and _field(observation, "attributable") is True
            and _field(observation, "terminal") is False
            and _field(observation, "response_id") is None
            and type(_field(observation, "pr")) is int
            and _field(observation, "pr") == expected_pr
            and type(trigger_id) is int
            and trigger_id > 0
            and _field(observation, "checkpoint", "checkpoint_id") == f"trigger:{trigger_id}"
            and isinstance(observed_head, str)
            and observed_head.casefold() == current_anchor.child_head.casefold()
            and not any(
                _field(observation, flag) is True
                for flag in (
                    "rate_limited",
                    "terminal_ambiguous",
                    "unreconciled",
                    "parent_moved",
                    "over_ceiling",
                    "provisional",
                    "correction",
                    "non_counting",
                    "completed",
                    "actionable",
                )
            )
            and ReviewController._hosted_anchor_matches(observation, expected_pr, current_anchor)
        )

    @staticmethod
    def _active_cli_review_overlaps_hosted(
        observation: Any,
        expected_pr: int,
        current_anchor: AnchorFacts | None,
    ) -> bool:
        if (
            current_anchor is None
            or current_anchor.pr != expected_pr
            or current_anchor.merge_base is None
            or current_anchor.patch_id is None
        ):
            return False
        checkpoint = _field(observation, "checkpoint", "checkpoint_id")
        observed_head = _field(observation, "head", "reviewed_head")
        if not (
            isinstance(checkpoint, str)
            and re.fullmatch(r"active-cli:run\.[0-9a-f]{32}", checkpoint) is not None
            and _field(observation, "current_lock_owner") is True
            and _field(observation, "active_review") is True
            and _field(observation, "held") is True
            and _field(observation, "pr") == expected_pr
            and isinstance(observed_head, str)
            and re.fullmatch(r"[0-9a-fA-F]{40}", observed_head) is not None
            and observed_head.casefold() == current_anchor.child_head.casefold()
            and _field(observation, "completed") is not True
            and not any(
                _field(observation, flag) is True
                for flag in (
                    "provisional",
                    "correction",
                    "non_counting",
                    "unstable",
                    "unreconciled",
                    "parent_moved",
                    "rate_limited",
                    "active_reservation",
                    "over_ceiling",
                    "actionable",
                )
            )
        ):
            return False

        expected = current_anchor.as_dict()
        for name in ("child_head", "parent_head", "merge_base"):
            value = _field(observation, name)
            target = expected[name]
            if (
                not isinstance(value, str)
                or re.fullmatch(r"[0-9a-fA-F]{40}", value) is None
                or value.casefold() != target.casefold()
            ):
                return False
        return (
            _field(observation, "child_head").casefold() == observed_head.casefold()
            and _field(observation, "parent_identity") == expected["parent_identity"]
            and _field(observation, "patch_id") == expected["patch_id"]
        )

    @staticmethod
    def _reconciled_anchor(
        pr: int,
        live: LivePullRequest,
        reconciliation: stack.Reconciliation,
    ) -> AnchorFacts | None:
        link = reconciliation.links.get(pr)
        merge_base = reconciliation.merge_bases.get(pr)
        patch_id = reconciliation.patch_ids.get(pr)
        if (
            link is None
            or not isinstance(merge_base, str)
            or re.fullmatch(r"[0-9a-fA-F]{40}", merge_base) is None
            or not isinstance(patch_id, str)
            or not patch_id
        ):
            return None
        return AnchorFacts(
            pr,
            live.head,
            link.identity,
            link.parent_head,
            merge_base,
            patch_id,
            stop_audit_pr_base_oid=live.base_tip,
            stop_audit_base_ref=live.base_ref,
            stop_audit_effective_parent_head=link.parent_head,
        )

    def prepare_stack_update(
        self,
        pr_numbers: Iterable[int],
        *,
        allow_removal: bool = False,
        reason: str | None = None,
        expected_stack: tuple[int, ...] | None = None,
    ) -> tuple[tuple[int, ...], tuple[int, ...]]:
        numbers = tuple(pr_numbers)
        if not numbers or any(isinstance(pr, bool) or not isinstance(pr, int) or pr <= 0 for pr in numbers):
            raise ControllerError("stack must contain positive pull-request numbers")
        if len(set(numbers)) != len(numbers):
            raise ControllerError("stack pull requests must be unique")
        if not isinstance(allow_removal, bool):
            raise ControllerError("allow_removal must be a boolean")

        if allow_removal and (not isinstance(reason, str) or not reason.strip()):
            raise ControllerError("--allow-removal requires a nonblank --reason")
        if not allow_removal and reason is not None:
            raise ControllerError("--reason requires --allow-removal")

        current_stack = self._state().ordered_prs
        if expected_stack is not None and current_stack != expected_stack:
            raise ControllerError("configured stack changed during validation; read the current stack and retry")
        expected_stack = current_stack
        requested = set(numbers)
        removed = tuple(pr for pr in expected_stack if pr not in requested)
        if removed and not allow_removal:
            formatted = ", ".join(f"#{pr}" for pr in removed)
            raise ControllerError(
                f"stack set cannot remove configured PRs by default; omitted {formatted}; "
                "explicit user authorization is required with --allow-removal and --reason"
            )
        if allow_removal and not removed:
            raise ControllerError("--allow-removal requires omitting at least one configured PR")

        return numbers, expected_stack

    def set_stack(
        self,
        pr_numbers: Iterable[int],
        *,
        allow_removal: bool = False,
        reason: str | None = None,
        expected_stack: tuple[int, ...] | None = None,
    ) -> dict[str, Any]:
        numbers, expected_stack = self.prepare_stack_update(
            pr_numbers,
            allow_removal=allow_removal,
            reason=reason,
            expected_stack=expected_stack,
        )
        requested = set(numbers)

        if self.github is not None:
            if not self.repository:
                raise ControllerError("repository identity is required to validate stack head repositories")
            for pr in numbers:
                item = _live(self.github.pull_request(pr), pr)
                problem = self._head_repository_problem(item)
                if problem:
                    raise ControllerError(f"PR #{pr} {problem}")

        def update(current: ReviewState) -> ReviewState:
            if current.ordered_prs != expected_stack:
                raise ControllerError("configured stack changed during validation; read the current stack and retry")
            current_removed = tuple(pr for pr in current.ordered_prs if pr not in requested)
            if current_removed and not allow_removal:
                formatted = ", ".join(f"#{pr}" for pr in current_removed)
                raise ControllerError(
                    f"stack set cannot remove configured PRs by default; omitted {formatted}; "
                    "explicit user authorization is required with --allow-removal and --reason"
                )
            return dataclasses.replace(current, ordered_prs=numbers)

        state = self.store.update(update)
        return {"ordered_prs": list(state.ordered_prs), "schema_version": state.schema_version}

    @staticmethod
    def _stack_change_result(
        state: ReviewState,
        *,
        action: str,
        pr: int,
        position: str,
        changed: bool,
    ) -> dict[str, Any]:
        return {
            "action": action,
            "pr": pr,
            "position": position,
            "changed": changed,
            "ordered_prs": list(state.ordered_prs),
            "schema_version": state.schema_version,
        }

    def add_stack_pr(self, pr: int, *, before: int | None = None, after: int | None = None) -> dict[str, Any]:
        """Add one PR using an exact queue snapshot and a single atomic state update."""

        if isinstance(pr, bool) or not isinstance(pr, int) or pr <= 0:
            raise ControllerError("stack add requires a positive pull-request number")
        if before is not None and after is not None:
            raise ControllerError("stack add accepts either --before or --after, not both")
        for anchor in (before, after):
            if anchor is not None and (isinstance(anchor, bool) or not isinstance(anchor, int) or anchor <= 0):
                raise ControllerError("stack add anchor must be a positive pull-request number")

        expected_stack = self._state().ordered_prs
        if pr in expected_stack:
            raise ControllerError(f"PR #{pr} is already configured in the review stack")
        anchor = before if before is not None else after
        if anchor == pr:
            raise ControllerError(f"PR #{pr} cannot be its own stack anchor")
        if anchor is not None and anchor not in expected_stack:
            raise ControllerError(f"anchor PR #{anchor} is not configured in the review stack")

        if before is not None:
            index = expected_stack.index(before)
            position = f"before #{before}"
        elif after is not None:
            index = expected_stack.index(after) + 1
            position = f"after #{after}"
        else:
            index = len(expected_stack)
            position = "end"
        requested = (*expected_stack[:index], pr, *expected_stack[index:])

        if self.github is not None:
            if not self.repository:
                raise ControllerError("repository identity is required to validate stack head repositories")
            item = _live(self.github.pull_request(pr), pr)
            problem = self._head_repository_problem(item)
            if problem:
                raise ControllerError(f"PR #{pr} {problem}")

        def update(current: ReviewState) -> ReviewState:
            if current.ordered_prs != expected_stack:
                raise ControllerError("configured stack changed during PR validation; read the current stack and retry")
            return dataclasses.replace(current, ordered_prs=requested)

        state = self.store.update(update)
        return self._stack_change_result(state, action="add", pr=pr, position=position, changed=True)

    def move_stack_pr(
        self,
        pr: int,
        *,
        before: int | None = None,
        after: int | None = None,
        first: bool = False,
        last: bool = False,
    ) -> dict[str, Any]:
        """Move one existing PR inside the atomic stack-state transaction."""

        if isinstance(pr, bool) or not isinstance(pr, int) or pr <= 0:
            raise ControllerError("stack move requires a positive pull-request number")
        if not isinstance(first, bool) or not isinstance(last, bool):
            raise ControllerError("stack move position flags must be booleans")
        positions = sum((before is not None, after is not None, first, last))
        if positions != 1:
            raise ControllerError("stack move requires exactly one of --before, --after, --first, or --last")
        for anchor in (before, after):
            if anchor is not None and (isinstance(anchor, bool) or not isinstance(anchor, int) or anchor <= 0):
                raise ControllerError("stack move anchor must be a positive pull-request number")
        anchor = before if before is not None else after
        if anchor == pr:
            raise ControllerError(f"PR #{pr} cannot be its own stack anchor")
        position = (
            "first" if first else "last" if last else f"before #{before}" if before is not None else f"after #{after}"
        )
        change = {"changed": False}

        def update(current: ReviewState) -> ReviewState:
            current_stack = current.ordered_prs
            if pr not in current_stack:
                raise ControllerError(f"PR #{pr} is not configured in the review stack")
            if anchor is not None and anchor not in current_stack:
                raise ControllerError(f"anchor PR #{anchor} is not configured in the review stack")

            remaining = tuple(item for item in current_stack if item != pr)
            if first:
                index = 0
            elif last:
                index = len(remaining)
            elif before is not None:
                index = remaining.index(before)
            else:
                index = remaining.index(after) + 1
            requested = (*remaining[:index], pr, *remaining[index:])
            change["changed"] = requested != current_stack
            return dataclasses.replace(current, ordered_prs=requested) if change["changed"] else current

        state = self.store.update(update)
        return self._stack_change_result(
            state,
            action="move",
            pr=pr,
            position=position,
            changed=change["changed"],
        )

    def _head_repository_problem(self, item: LivePullRequest) -> str | None:
        if item.head_repository is None:
            return "has no head repository identity; same-repository review stacks require GitHub headRepository"
        if item.head_repository.casefold() != self.repository.casefold():
            return (
                f"uses unsupported cross-repository head {item.head_repository!r}; "
                f"the configured repository is {self.repository!r}"
            )
        return None

    def show_stack(self) -> dict[str, Any]:
        state = self._state()
        return {"ordered_prs": list(state.ordered_prs), "schema_version": state.schema_version}

    def _live_snapshots(
        self,
        state: ReviewState,
        remote_heads: Mapping[str, str] | None = None,
        *,
        live_identities: Mapping[int, Any] | None = None,
        refresh_prs: set[int] | None = None,
    ) -> tuple[dict[int, LivePullRequest], dict[int, stack.PRSnapshot], str]:
        github = self._require_github()
        heads = remote_heads if remote_heads is not None else self.git.remote_heads()
        default_tip = _sha(heads.get(self.default_base_ref), "default base tip")
        live = {}
        for pr in state.ordered_prs:
            if refresh_prs is None or pr in refresh_prs:
                value = github.pull_request(pr)
            else:
                if live_identities is None or pr not in live_identities:
                    raise ControllerError(f"batched identity is unavailable for PR #{pr}")
                value = live_identities[pr]
            live[pr] = _live(value, pr)
        snapshots = {pr: item.snapshot() for pr, item in live.items()}
        return live, snapshots, default_tip

    def _reconciliation(
        self,
        state: ReviewState,
        *,
        live_identities: Mapping[int, Any] | None = None,
        refresh_prs: set[int] | None = None,
        evidence_prs: set[int] | None = None,
        history_cache: dict[tuple[int, str], list[Any]] | None = None,
        remote_head_snapshot: Mapping[str, str] | None = None,
        phase_timings: dict[str, float] | None = None,
        prefetch_complete_history: bool = False,
    ) -> tuple[dict[int, LivePullRequest], stack.Reconciliation]:
        remote_heads = remote_head_snapshot if remote_head_snapshot is not None else self.git.remote_heads()
        live, snapshots, default_tip = self._live_snapshots(
            state,
            remote_heads,
            live_identities=live_identities,
            refresh_prs=refresh_prs,
        )
        selected_evidence_prs = set(state.ordered_prs if evidence_prs is None else evidence_prs)
        ancestry_errors: set[tuple[str, str]] = set()
        ancestry_cache: dict[tuple[str, str], bool] = {}

        def is_ancestor(parent: str, child: str) -> bool:
            key = (parent.casefold(), child.casefold())
            if key in ancestry_cache:
                return ancestry_cache[key]
            budget = github.active_hosted_preflight_budget()
            if budget is not None:
                budget.set_phase(f"target_ancestry_{parent[:8]}_{child[:8]}", total=1)
            try:
                result = self.git.is_ancestor(parent, child)
            except github.HostedPreflightDeadlineExceeded:
                raise
            except (ControllerError, OSError, subprocess.SubprocessError, ValueError):
                ancestry_errors.add(key)
                result = False
            ancestry_cache[key] = result
            if budget is not None:
                budget.set_completed(1)
            return result

        baseline = stack.reconcile_stack(
            state.ordered_prs,
            snapshots,
            self.default_base_ref,
            default_tip,
            is_ancestor=is_ancestor,
        )
        direct_default_fronts: set[int] = set()
        default_test_merge_failures: dict[int, str] = {}
        self._default_test_merge_proofs = {}
        baseline_reasons = dict(baseline.reasons)
        baseline_statuses = dict(baseline.statuses)
        for pr in state.ordered_prs:
            item = live[pr]
            link = baseline.links[pr]
            if (
                item.merged
                or link.parent_pr is not None
                or link.parent_ref != self.default_base_ref
                or item.base_ref != self.default_base_ref
                or item.base_tip.casefold() != default_tip.casefold()
                or item.state != "OPEN"
                or item.mergeable != "MERGEABLE"
            ):
                continue
            try:
                budget = github.active_hosted_preflight_budget()
                if budget is not None:
                    budget.set_phase(f"target_test_merge_pr_{pr}", total=1)
                merge_tree = self._test_merge_tree(item.base_tip, item.head)
                if budget is not None:
                    budget.set_completed(1)
            except github.HostedPreflightDeadlineExceeded:
                raise
            except (ControllerError, OSError, subprocess.SubprocessError, ValueError) as error:
                reason = f"current default-base test merge is unproven: {error}"
                default_test_merge_failures[pr] = reason
                baseline_statuses[pr] = stack.ReconciliationStatus.UNRECONCILED
                baseline_reasons[pr] = reason
                continue
            self._default_test_merge_proofs[pr] = (item.base_tip, item.head, merge_tree)
            reason = baseline_reasons.get(pr, "")
            ancestry_pair = (link.parent_head.casefold(), item.head.casefold())
            is_base_advance_ancestry_failure = (
                reason == "effective parent head is not an ancestor of the child head"
                and ancestry_pair not in ancestry_errors
            )
            if is_base_advance_ancestry_failure:
                # The local merge-tree proof above binds this exception to the
                # exact current default-base/PR-head tuple. A direct front need
                # not first rewrite its branch to contain unrelated default commits.
                baseline_statuses[pr] = stack.ReconciliationStatus.COHERENT
                baseline_reasons.pop(pr, None)
            if baseline_statuses.get(pr, stack.ReconciliationStatus.COHERENT) == stack.ReconciliationStatus.COHERENT:
                direct_default_fronts.add(pr)
        baseline = dataclasses.replace(baseline, reasons=baseline_reasons, statuses=baseline_statuses)
        unsupported: set[int] = set()
        reasons = dict(baseline.reasons)
        statuses = dict(baseline.statuses)
        for pr in state.ordered_prs:
            item = live[pr]
            if item.merged:
                continue
            problem = self._head_repository_problem(item)
            parent_pr = baseline.links[pr].parent_pr
            if problem:
                unsupported.add(pr)
                reasons[pr] = problem
                statuses[pr] = stack.ReconciliationStatus.UNRECONCILED
            elif parent_pr in unsupported:
                unsupported.add(pr)
                reasons[pr] = f"effective parent PR #{parent_pr} has an unsupported head repository"
                statuses[pr] = stack.ReconciliationStatus.UNRECONCILED
        if unsupported:
            baseline = dataclasses.replace(
                baseline,
                status=(
                    stack.ReconciliationStatus.PARENT_MOVED
                    if baseline.status == stack.ReconciliationStatus.PARENT_MOVED
                    else stack.ReconciliationStatus.UNRECONCILED
                ),
                reasons=reasons,
                statuses=statuses,
            )
        unsupported_reasons = {pr: reasons[pr] for pr in unsupported}
        reconciled: dict[int, StackReconciliationDecision] = {}
        anchors: dict[int, AnchorFacts] = {}
        legacy_transition_prs: set[int] = set()
        legacy_transition_fingerprints: dict[int, Mapping[str, tuple[str, ...]]] = {}
        anchor_failures: dict[int, str] = {}
        reconciliation_candidates: list[int] = []
        for pr in state.ordered_prs:
            if live[pr].merged or baseline.status_for(pr) != stack.ReconciliationStatus.COHERENT:
                continue
            link = baseline.links[pr]
            if not self._current_branches_match(state, live, baseline, pr, remote_heads):
                continue
            try:
                anchor_started = time.perf_counter() if phase_timings is not None else 0.0
                budget = github.active_hosted_preflight_budget()
                if budget is not None:
                    budget.set_phase(f"target_anchor_pr_{pr}", total=1)
                try:
                    current = self._anchor(pr, live[pr], link)
                    if budget is not None:
                        budget.set_completed(1)
                finally:
                    if phase_timings is not None:
                        phase_timings["local_anchors_ms"] = (
                            phase_timings.get("local_anchors_ms", 0.0) + (time.perf_counter() - anchor_started) * 1000
                        )
            except github.HostedPreflightDeadlineExceeded:
                raise
            except (ControllerError, OSError, subprocess.SubprocessError, ValueError) as error:
                anchor_failures[pr] = f"could not compute current Git anchor: {type(error).__name__}: {error}"
                continue
            anchors[pr] = current
            transition = self._legacy_transition_for(state, pr, current)
            if transition is not None:
                legacy_transition_prs.add(pr)
                legacy_transition_fingerprints[pr] = {
                    "hosted": transition.hosted_fingerprints,
                    "cli": transition.cli_fingerprints,
                }
            if pr in selected_evidence_prs:
                reconciliation_candidates.append(pr)
                if not prefetch_complete_history:
                    decision = self._active_stack_reconciliation(state, pr, current)
                    if decision is not None:
                        reconciled[pr] = decision
        if prefetch_complete_history:
            self._prefetch_target_history_payloads(state, live, anchors, selected_evidence_prs, history_cache)
            for pr in reconciliation_candidates:
                decision = self._active_stack_reconciliation(state, pr, anchors[pr])
                if decision is not None:
                    reconciled[pr] = decision
        anchored: dict[int, str] = {}
        for pr in state.ordered_prs:
            if pr in reconciled or pr not in selected_evidence_prs:
                continue
            current = anchors.get(pr)
            if current is None:
                continue
            for channel in (policy.Channel.HOSTED, policy.Channel.CLI):
                latest = _latest_review(
                    self._counting_history_for_anchor(
                        state,
                        pr,
                        channel,
                        current,
                        history_cache=history_cache,
                        phase_timings=phase_timings,
                    )
                )
                if latest is not None:
                    if (
                        pr in direct_default_fronts
                        and _field(latest, "child_head") == current.child_head
                        and _field(latest, "parent_identity") == current.parent_identity
                        and _field(latest, "merge_base") == current.merge_base
                        and _field(latest, "patch_id", "patch_identity") == current.patch_id
                        and isinstance(_field(latest, "parent_head"), str)
                        and is_ancestor(_field(latest, "parent_head"), current.parent_head)
                    ):
                        # Do not turn an unrelated default-tip advance into an
                        # anchored-parent movement when the exact reviewed head
                        # and owned patch are unchanged. The channel-specific
                        # classification below still checks every review record.
                        continue
                    parent_head = _field(latest, "parent_head")
                    if (
                        isinstance(parent_head, str)
                        and len(parent_head) == 40
                        and all(character in "0123456789abcdefABCDEF" for character in parent_head)
                    ):
                        anchored[pr] = parent_head
                        break
        result = stack.reconcile_stack(
            state.ordered_prs,
            snapshots,
            self.default_base_ref,
            default_tip,
            is_ancestor=is_ancestor,
            anchored_parent_heads=anchored,
        )
        reasons = dict(result.reasons)
        statuses = dict(result.statuses)
        channel_statuses = dict(result.channel_statuses)

        for pr in direct_default_fronts:
            item = live[pr]
            link = result.links[pr]
            if (
                link.parent_pr is None
                and link.parent_ref == self.default_base_ref
                and item.base_ref == self.default_base_ref
                and item.base_tip.casefold() == link.parent_head.casefold()
                and item.state == "OPEN"
                and item.mergeable == "MERGEABLE"
                and reasons.get(pr) == "effective parent head is not an ancestor of the child head"
                and (link.parent_head.casefold(), item.head.casefold()) not in ancestry_errors
            ):
                statuses[pr] = stack.ReconciliationStatus.COHERENT
                reasons.pop(pr, None)
        for pr, reason in default_test_merge_failures.items():
            statuses[pr] = stack.ReconciliationStatus.UNRECONCILED
            reasons[pr] = reason

        def set_anchor_status(pr: int, status: stack.ReconciliationStatus, reason: str) -> None:
            # Topology failures apply to both review channels. Evidence identity
            # changes are recorded separately below.
            priority = {
                stack.ReconciliationStatus.COHERENT: 0,
                stack.ReconciliationStatus.PATCH_CHANGED: 1,
                stack.ReconciliationStatus.EQUIVALENT_HISTORY: 2,
                stack.ReconciliationStatus.UNRECONCILED: 3,
                stack.ReconciliationStatus.PARENT_MOVED: 4,
            }
            previous = statuses.get(pr, stack.ReconciliationStatus.COHERENT)
            if priority[status] >= priority[previous]:
                statuses[pr] = status
                reasons[pr] = reason

        def set_channel_anchor_status(pr: int, channel: policy.Channel, status: stack.ReconciliationStatus) -> None:
            if status in {
                stack.ReconciliationStatus.PARENT_MOVED,
                stack.ReconciliationStatus.UNRECONCILED,
            }:
                set_anchor_status(pr, status, "review evidence is anchored to an unproven parent or merge base")
            elif status in {
                stack.ReconciliationStatus.PATCH_CHANGED,
                stack.ReconciliationStatus.EQUIVALENT_HISTORY,
            }:
                channel_statuses[(pr, channel.value)] = status

        for pr, reason in anchor_failures.items():
            set_anchor_status(pr, stack.ReconciliationStatus.UNRECONCILED, reason)

        moved: set[int] = set(result.affected_descendants)
        drifted_sources: set[int] = set()
        # GitHub's PR head is the candidate identity, while the source branch is
        # the parent ref used by a child.  A branch moving without a child base
        # refresh is therefore a real parent movement, even when the PR payload
        # itself has not changed yet.
        for pr, item in live.items():
            if item.merged or not item.head_ref or item.head_ref not in remote_heads:
                continue
            branch_tip = _sha(remote_heads[item.head_ref], f"PR #{pr} head branch")
            if branch_tip != item.head:
                reasons[pr] = "source branch tip differs from the live PR head"
                drifted_sources.add(pr)
                moved.add(pr)
        changed = True
        while changed:
            changed = False
            for pr, link in result.links.items():
                if link.parent_pr in moved and pr not in moved:
                    moved.add(pr)
                    changed = True
        for pr in moved:
            if pr in drifted_sources:
                statuses[pr] = stack.ReconciliationStatus.UNRECONCILED
            else:
                statuses[pr] = stack.ReconciliationStatus.PARENT_MOVED
        for pr, item in live.items():
            if item.merged:
                continue
            if not item.base_exists or item.base_ref not in remote_heads:
                reasons[pr] = "pull request base branch does not exist"
                statuses[pr] = stack.ReconciliationStatus.UNRECONCILED
            if item.state != "OPEN":
                reasons[pr] = f"pull request is {item.state.lower()}"
                statuses[pr] = stack.ReconciliationStatus.UNRECONCILED
            if item.mergeable != "MERGEABLE":
                reasons[pr] = f"pull request is {item.mergeable.lower()}"
                statuses[pr] = stack.ReconciliationStatus.UNRECONCILED
        # New checkpoint captures carry the complete parent/merge/patch anchor.
        # Historical duration-only checkpoints are intentionally still usable as
        # policy history, but cannot silently establish this stronger identity.
        for pr in state.ordered_prs:
            if live[pr].merged:
                continue
            if pr in anchor_failures:
                continue
            link = result.links[pr]
            current = anchors.get(pr)
            if current is None:
                try:
                    anchor_started = time.perf_counter() if phase_timings is not None else 0.0
                    budget = github.active_hosted_preflight_budget()
                    if budget is not None:
                        budget.set_phase(f"target_anchor_pr_{pr}", total=1)
                    try:
                        current = self._anchor(pr, live[pr], link)
                        if budget is not None:
                            budget.set_completed(1)
                    finally:
                        if phase_timings is not None:
                            phase_timings["local_anchors_ms"] = (
                                phase_timings.get("local_anchors_ms", 0.0)
                                + (time.perf_counter() - anchor_started) * 1000
                            )
                except github.HostedPreflightDeadlineExceeded:
                    raise
                except (ControllerError, OSError, subprocess.SubprocessError, ValueError) as error:
                    reason = f"could not compute current Git anchor: {type(error).__name__}: {error}"
                    anchor_failures[pr] = reason
                    set_anchor_status(pr, stack.ReconciliationStatus.UNRECONCILED, reason)
                    continue
                anchors[pr] = current
            if pr not in selected_evidence_prs:
                continue
            for channel in (policy.Channel.HOSTED, policy.Channel.CLI):
                latest = _latest_review(
                    self._counting_history_for_anchor(
                        state,
                        pr,
                        channel,
                        current,
                        history_cache=history_cache,
                        phase_timings=phase_timings,
                    )
                )
                if latest is None:
                    continue
                previous = latest
                values = {
                    name: _field(previous, name)
                    for name in ("pr", "child_head", "parent_identity", "parent_head", "merge_base", "patch_id")
                }
                if not isinstance(values["pr"], int) or not all(
                    isinstance(values[name], str) and values[name]
                    for name in ("child_head", "parent_identity", "parent_head", "merge_base", "patch_id")
                ):
                    continue
                try:
                    classification = stack.classify_anchor(
                        stack.ReviewAnchor(
                            int(values["pr"]),
                            values["child_head"],
                            values["parent_identity"],
                            values["parent_head"],
                            values["merge_base"],
                            values["patch_id"],
                        ),
                        current.as_anchor(),
                    )
                except (TypeError, ValueError):
                    set_anchor_status(
                        pr,
                        stack.ReconciliationStatus.UNRECONCILED,
                        "review evidence has an invalid identity anchor",
                    )
                    continue
                if classification == stack.ReconciliationStatus.PARENT_MOVED:
                    if (
                        pr in direct_default_fronts
                        and values["child_head"].casefold() == current.child_head.casefold()
                        and values["parent_identity"] == current.parent_identity
                        and values["merge_base"].casefold() == current.merge_base.casefold()
                        and values["patch_id"] == current.patch_id
                        and is_ancestor(values["parent_head"], current.parent_head)
                    ):
                        # Same head, merge base, and owned diff means the only
                        # movement is the default branch tip. Existing review
                        # evidence remains about the exact unchanged PR patch.
                        continue
                    if pr in reconciled:
                        channel_status = (
                            stack.ReconciliationStatus.EQUIVALENT_HISTORY
                            if values["patch_id"] == current.patch_id
                            else stack.ReconciliationStatus.PATCH_CHANGED
                        )
                        set_channel_anchor_status(pr, channel, channel_status)
                    else:
                        set_anchor_status(
                            pr, stack.ReconciliationStatus.PARENT_MOVED, "review evidence is anchored to a moved parent"
                        )
                        moved.add(pr)
                elif classification in {
                    stack.ReconciliationStatus.PATCH_CHANGED,
                    stack.ReconciliationStatus.UNRECONCILED,
                    stack.ReconciliationStatus.EQUIVALENT_HISTORY,
                }:
                    set_channel_anchor_status(pr, channel, classification)
        changed = True
        while changed:
            changed = False
            for pr, link in result.links.items():
                if link.parent_pr in moved and pr not in moved:
                    moved.add(pr)
                    statuses[pr] = stack.ReconciliationStatus.PARENT_MOVED
                    reasons.setdefault(pr, "an earlier parent review identity moved")
                    changed = True
        # The first reconciliation pass rejects unsupported head repositories
        # before the anchored pass runs.  The second stack reconciliation can
        # otherwise replace those fail-closed markings with a fresh coherent
        # result.  Carry them forward, while retaining a stronger genuine
        # parent-movement diagnosis discovered by the later pass.
        for pr in unsupported:
            if statuses.get(pr) == stack.ReconciliationStatus.PARENT_MOVED:
                continue
            statuses[pr] = stack.ReconciliationStatus.UNRECONCILED
            reasons[pr] = unsupported_reasons[pr]
        if any(value == stack.ReconciliationStatus.PARENT_MOVED for value in statuses.values()):
            overall = stack.ReconciliationStatus.PARENT_MOVED
        elif any(value == stack.ReconciliationStatus.UNRECONCILED for value in statuses.values()):
            overall = stack.ReconciliationStatus.UNRECONCILED
        elif result.status in {
            stack.ReconciliationStatus.PATCH_CHANGED,
            stack.ReconciliationStatus.EQUIVALENT_HISTORY,
        }:
            # These statuses are normally channel-specific in channel_statuses;
            # retain them if a future reconciliation provider promotes one.
            overall = result.status
        else:
            overall = stack.ReconciliationStatus.COHERENT
        current_anchors = {
            pr: anchor
            for pr, anchor in anchors.items()
            if (
                (link := result.links.get(pr)) is not None
                and link.identity == anchor.parent_identity
                and link.parent_head.casefold() == anchor.parent_head.casefold()
                and anchor.child_head.casefold() == live[pr].head.casefold()
            )
        }
        return live, dataclasses.replace(
            result,
            status=overall,
            affected_descendants=tuple(pr for pr in state.ordered_prs if pr in moved),
            reasons=reasons,
            statuses=statuses,
            channel_statuses=channel_statuses,
            merge_bases={pr: anchor.merge_base for pr, anchor in current_anchors.items()},
            patch_ids={pr: anchor.patch_id for pr, anchor in current_anchors.items()},
            legacy_transition_prs=tuple(pr for pr in state.ordered_prs if pr in legacy_transition_prs),
            legacy_transition_fingerprints=legacy_transition_fingerprints,
        )

    def _current_branches_match(
        self,
        state: ReviewState,
        live: Mapping[int, LivePullRequest],
        reconciliation: stack.Reconciliation,
        pr: int,
        remote_heads: Mapping[str, str],
    ) -> bool:
        """Require the target and all earlier links to match live remote refs."""

        try:
            last = state.ordered_prs.index(pr)
        except ValueError:
            return False
        for candidate in state.ordered_prs[: last + 1]:
            item = live[candidate]
            if item.merged:
                continue
            if (
                reconciliation.status_for(candidate) != stack.ReconciliationStatus.COHERENT
                or item.state != "OPEN"
                or item.mergeable != "MERGEABLE"
                or not item.base_exists
                or item.base_ref not in remote_heads
            ):
                return False
            if _sha(remote_heads[item.base_ref], f"PR #{candidate} base branch") != item.base_tip:
                return False
            if item.head_ref:
                if item.head_repository is None or item.head_repository.casefold() != self.repository.casefold():
                    return False
                if item.head_ref not in remote_heads:
                    return False
                if _sha(remote_heads[item.head_ref], f"PR #{candidate} head branch") != item.head:
                    return False
        return True

    def _checkpoint_for_reconciliation(
        self,
        pr: int,
        channel: policy.Channel,
        checkpoint: str,
        prior_head: str,
        current: AnchorFacts,
    ) -> Any:
        # This proof requires complete historical evidence. Admission may have
        # preloaded request-only history for a stopped allocation, which cannot
        # establish that an older moved-parent checkpoint exists.
        budget = github.active_hosted_preflight_budget()
        if budget is not None:
            budget.set_phase(f"target_checkpoint_history_pr_{pr}_{channel.value}", total=1)
        history = _history(self._evidence_provider, pr, channel)
        if budget is not None:
            budget.set_completed(1)
        for item in history:
            if (
                _field(item, "checkpoint", "checkpoint_id") != checkpoint
                or _field(item, "head", "reviewed_head") != prior_head
                or _field(item, "pr") != pr
                or not _field(item, "completed")
                or not _field(item, "attributable")
                or _field(item, "provisional")
            ):
                continue
            values = {
                name: _field(item, name)
                for name in ("pr", "child_head", "parent_identity", "parent_head", "merge_base", "patch_id")
            }
            if not isinstance(values["pr"], int) or not all(
                isinstance(values[name], str) and values[name]
                for name in ("child_head", "parent_identity", "parent_head", "merge_base", "patch_id")
            ):
                continue
            previous = stack.ReviewAnchor(**values)
            if previous.child_head != prior_head:
                continue
            if stack.classify_anchor(previous, current.as_anchor()) == stack.ReconciliationStatus.PARENT_MOVED:
                return item
        raise ControllerError("checkpoint must be attributable evidence anchored to a moved parent")

    def _active_stack_reconciliation(
        self,
        state: ReviewState,
        pr: int,
        current: AnchorFacts,
    ) -> StackReconciliationDecision | None:
        for decision in reversed(state.reconciliations):
            if not decision.matches(pr, current.as_dict()):
                continue
            try:
                self._checkpoint_for_reconciliation(
                    pr,
                    policy.Channel(decision.channel),
                    decision.checkpoint,
                    decision.prior_head,
                    current,
                )
            except ControllerError:
                continue
            return decision
        return None

    def _anchor(self, pr: int, item: LivePullRequest, link: stack.ParentLink) -> AnchorFacts:
        merge_base = _sha(self.git.merge_base(link.parent_head, item.head), "merge base")
        patch_id = self.git.patch_identity(merge_base, item.head)
        if not isinstance(patch_id, str) or not patch_id:
            raise ControllerError("Git provider returned an empty patch identity")
        return AnchorFacts(
            pr,
            item.head,
            link.identity,
            link.parent_head,
            merge_base,
            patch_id,
            stop_audit_pr_base_oid=item.base_tip,
            stop_audit_base_ref=item.base_ref,
            stop_audit_effective_parent_head=link.parent_head,
        )

    def _test_merge_tree(self, base: str, head: str) -> str:
        verifier = getattr(self.git, "test_merge_tree", None)
        if not callable(verifier):
            raise ControllerError("Git provider cannot verify a current base/head test merge")
        base_sha = _sha(base, "test-merge base")
        head_sha = _sha(head, "test-merge head")
        identity = (base_sha.casefold(), head_sha.casefold())
        cached = self._test_merge_tree_cache.get(identity)
        if cached is not None:
            return cached
        tree = _sha(verifier(base_sha, head_sha), "test-merge tree")
        self._test_merge_tree_cache[identity] = tree
        return tree

    def _accepted_findings_pending(self, value: Any, current_head: str) -> bool:
        if any(_field(value, name) is True for name in ("correction", "non_counting", "provisional")):
            return False
        accepted = _field(value, "accepted")
        if not (
            _field(value, "completed") is True
            and _field(value, "attributable") is True
            and type(accepted) is int
            and accepted > 0
        ):
            return False
        has_source_resolution = _field(value, "source_resolution_status") is not None
        if has_source_resolution:
            return _field(value, "source_resolution_status") != "resolved"
        reviewed_head = _field(value, "head", "reviewed_head")
        if not isinstance(reviewed_head, str) or reviewed_head.casefold() == current_head.casefold():
            return True
        try:
            return not self.git.is_ancestor(_sha(reviewed_head, "accepted finding head"), current_head)
        except (ControllerError, OSError, ValueError, subprocess.SubprocessError):
            return True

    @staticmethod
    def _accepted_findings_pending_reason(value: Any) -> str:
        status = _field(value, "source_resolution_status")
        if status == "unavailable" or status == "error":
            return "source-resolution records are unavailable; accepted-finding proof remains pending"
        return "accepted findings need a published corrected head before review can stop"

    @staticmethod
    def _legacy_transition_record(value: Any, pr: int, current_head: str) -> None:
        """Validate one observation before allowing it into the legacy set."""

        if not isinstance(value, (Mapping, policy.Evidence)):
            raise ControllerError("legacy transition encountered malformed evidence")
        try:
            item = policy.Evidence.from_value(value)
        except (AttributeError, KeyError, TypeError, ValueError) as error:
            raise ControllerError("legacy transition encountered malformed evidence") from error
        if item.pr != pr:
            raise ControllerError("legacy transition evidence is bound to another pull request")
        try:
            old_head = _sha(item.head, "legacy transition evidence head")
            normalized_current_head = _sha(current_head, "legacy transition current head")
        except ControllerError as error:
            raise ControllerError("legacy transition evidence has no exact candidate identity") from error
        if old_head == normalized_current_head:
            raise ControllerError("legacy transition evidence must target an old head")
        if not isinstance(item.checkpoint, str) or not item.checkpoint.strip():
            raise ControllerError("legacy transition evidence has no immutable checkpoint identity")
        if item.anchored is not None and not isinstance(item.anchored, bool):
            raise ControllerError("legacy transition evidence field 'anchored' is malformed")
        for name in (
            "completed",
            "attributable",
            "correction",
            "corrected_state",
            "provisional",
            "rate_limited",
            "held",
            "unstable",
            "unreconciled",
            "over_ceiling",
            "parent_moved",
            "non_counting",
        ):
            if not isinstance(getattr(item, name), bool):
                raise ControllerError(f"legacy transition evidence field {name!r} is malformed")
        for name in ("accepted", "raw"):
            count = getattr(item, name)
            if isinstance(count, bool) or not isinstance(count, int) or count < 0:
                raise ControllerError(f"legacy transition evidence field {name!r} is malformed")
        if item.non_counting:
            raise ControllerError("legacy transition cannot accept already projected evidence")
        if item.provisional:
            raise ControllerError("legacy transition cannot dismiss provisional evidence")
        if item.rate_limited or item.unstable or item.unreconciled or item.over_ceiling or item.parent_moved:
            raise ControllerError("legacy transition cannot dismiss stale or ambiguous evidence")

        # The only non-completed status that may be retired by this transition
        # is a completed Hosted response that lacks the modern public checkpoint
        # linkage. Active reservations, pending captures, and unresolved
        # findings use different identities and remain hard blockers.
        if item.held:
            if LEGACY_UNCHECKPOINTED.fullmatch(item.checkpoint) is None or item.completed or item.attributable:
                raise ControllerError("legacy transition cannot dismiss an active or actionable reservation")
        elif item.completed is not True or item.attributable is not True:
            raise ControllerError("legacy transition cannot dismiss incomplete or unattributable evidence")

        if item.checkpoint.startswith("pending-capture:"):
            raise ControllerError("legacy transition cannot dismiss an unlinked CLI capture")

        anchor_fields = ("child_head", "parent_identity", "parent_head", "merge_base", "patch_id")
        raw_anchor = {
            "child_head": _field(value, "child_head", "candidate_sha"),
            "parent_identity": _field(value, "parent_identity", "parent_ref"),
            "parent_head": _field(value, "parent_head", "parent_sha", "base_tip_sha"),
            "merge_base": _field(value, "merge_base"),
            "patch_id": _field(value, "patch_id", "patch_identity"),
        }
        present = [raw_anchor[name] for name in anchor_fields if raw_anchor[name] not in (None, "")]
        if item.anchored is True:
            raise ControllerError("legacy transition requires evidence without a modern anchor")
        # Historical CLI records explicitly carried ``anchored=false`` while
        # retaining a few old parent fields, but never a modern patch identity.
        # Preserve that exact legacy shape; an unanchored record with a patch
        # identity, or an implicit/ambiguous partial shape, is not dismissible.
        if present and (item.anchored is not False or raw_anchor["patch_id"] not in (None, "")):
            raise ControllerError("legacy transition evidence has a partial or spoofed identity anchor")

    @staticmethod
    def _intrinsic_scope_projection(value: Any) -> bool:
        """Recognize runtime-owned, non-counting scope observations."""

        if not isinstance(value, Mapping) or value.get("non_counting") is not True:
            return False
        kind = value.get("kind")
        if kind == "scope_timeline":
            return value.get("scope_timeline") is True and value.get("scope_timeline_complete") is True
        if kind == "scope_change":
            return value.get("scope_changed") is True and value.get("scope_change_malformed") is False
        if kind == "scope_change_malformed":
            return value.get("scope_changed") is True and value.get("scope_change_malformed") is True
        return False

    @staticmethod
    def _reauthorization_observation(value: Any, pr: int) -> None:
        """Require every non-legacy observation to remain normal counting evidence."""

        if not isinstance(value, (Mapping, policy.Evidence)):
            raise ControllerError("legacy transition reauthorization encountered malformed evidence")
        try:
            item = policy.Evidence.from_value(value)
        except (AttributeError, KeyError, TypeError, ValueError) as error:
            raise ControllerError("legacy transition reauthorization encountered malformed evidence") from error
        if item.pr != pr:
            raise ControllerError("legacy transition reauthorization evidence is bound to another pull request")
        if item.non_counting:
            raise ControllerError("legacy transition reauthorization cannot accept projected evidence")
        for name in (
            "completed",
            "attributable",
            "correction",
            "corrected_state",
            "provisional",
            "rate_limited",
            "held",
            "unstable",
            "unreconciled",
            "over_ceiling",
            "parent_moved",
            "non_counting",
        ):
            if not isinstance(getattr(item, name), bool):
                raise ControllerError(f"legacy transition reauthorization field {name!r} is malformed")
        for name in ("accepted", "raw"):
            count = getattr(item, name)
            if isinstance(count, bool) or not isinstance(count, int) or count < 0:
                raise ControllerError(f"legacy transition reauthorization field {name!r} is malformed")
        try:
            _sha(item.head, "legacy transition modern evidence head")
        except ControllerError as error:
            raise ControllerError(
                "legacy transition reauthorization requires exact modern evidence identity"
            ) from error
        if not isinstance(item.checkpoint, str) or not item.checkpoint.strip():
            raise ControllerError("legacy transition reauthorization requires an immutable modern checkpoint")
        if item.provisional or item.held or item.rate_limited or item.unstable or item.unreconciled:
            raise ControllerError("legacy transition reauthorization cannot ignore active or ambiguous evidence")
        if item.over_ceiling or item.parent_moved:
            raise ControllerError("legacy transition reauthorization cannot ignore blocked evidence")
        if item.completed is not True or item.attributable is not True or item.anchored is not True:
            raise ControllerError("legacy transition reauthorization requires completed anchored evidence")
        raw_anchor = {
            "child_head": _field(value, "child_head", "candidate_sha"),
            "parent_identity": _field(value, "parent_identity", "parent_ref"),
            "parent_head": _field(value, "parent_head", "parent_sha", "base_tip_sha"),
            "merge_base": _field(value, "merge_base"),
            "patch_id": _field(value, "patch_id", "patch_identity"),
        }
        if not isinstance(raw_anchor["parent_identity"], str) or not raw_anchor["parent_identity"].strip():
            raise ControllerError("legacy transition reauthorization requires a complete modern identity anchor")
        for name in ("child_head", "parent_head", "merge_base"):
            try:
                _sha(raw_anchor[name], f"legacy transition modern evidence {name}")
            except ControllerError as error:
                raise ControllerError(
                    "legacy transition reauthorization requires a complete modern identity anchor"
                ) from error
        if not isinstance(raw_anchor["patch_id"], str) or not raw_anchor["patch_id"].strip():
            raise ControllerError("legacy transition reauthorization requires a complete modern identity anchor")
        if _sha(raw_anchor["child_head"], "legacy transition modern evidence child head") != _sha(
            item.head, "legacy transition modern evidence head"
        ):
            raise ControllerError("legacy transition reauthorization requires matching modern head identity")

    def _require_complete_reauthorization_audit(
        self, pr: int, hosted_fingerprints: Sequence[str], current: Mapping[str, Any]
    ) -> None:
        """Require the live provider to prove complete, attributable Hosted evidence."""

        audit_method = getattr(self._evidence_provider, "legacy_transition_reauthorization_audit", None)
        if not callable(audit_method):
            raise ControllerError("missing Hosted fingerprint retirement requires a complete paginated evidence audit")
        try:
            audit = audit_method(pr, tuple(hosted_fingerprints), current)
        except ControllerError:
            raise
        except Exception as error:
            raise ControllerError(
                "missing Hosted fingerprint retirement could not verify complete paginated evidence"
            ) from error
        if not isinstance(audit, Mapping) or audit.get("complete") is not True:
            raise ControllerError("missing Hosted fingerprint retirement requires complete paginated evidence")
        for field, description in (
            ("active_reservations", "an active or unresolved Hosted reservation"),
            ("unmatched_responses", "an unmatched Hosted response"),
            ("ambiguous_responses", "an ambiguous Hosted response"),
            ("unresolved_findings", "unresolved actionable findings"),
        ):
            values = audit.get(field)
            if not isinstance(values, Sequence) or isinstance(values, (str, bytes)) or values:
                raise ControllerError(f"missing Hosted fingerprint retirement cannot proceed with {description}")

    def decide_legacy_transition(
        self,
        *,
        pr: int,
        head: str,
        reason: str,
        reauthorize: bool = False,
        retire_missing_hosted_fingerprint: str | None = None,
    ) -> dict[str, Any]:
        """Retire only the observed legacy evidence at one exact current anchor."""

        if not isinstance(reason, str) or not reason.strip():
            raise ControllerError("legacy evidence transition requires a reason")
        if len(reason) > 500 or any(ord(character) < 0x20 for character in reason):
            raise ControllerError("legacy evidence transition reason is malformed")
        if not isinstance(reauthorize, bool):
            raise ControllerError("legacy evidence transition reauthorization flag is malformed")
        if retire_missing_hosted_fingerprint is not None:
            if not isinstance(retire_missing_hosted_fingerprint, str) or not re.fullmatch(
                r"[0-9a-f]{64}", retire_missing_hosted_fingerprint
            ):
                raise ControllerError("missing Hosted fingerprint retirement requires an exact SHA-256 fingerprint")
            if not reauthorize:
                raise ControllerError("missing Hosted fingerprint retirement requires explicit reauthorization")
        state = self._state()
        if pr not in state.ordered_prs:
            raise ControllerError(f"PR #{pr} is not in the configured review stack")
        normalized_head = _sha(head, "transition head")
        remote_heads = self.git.remote_heads()
        live, snapshots, default_tip = self._live_snapshots(state, remote_heads)
        baseline = stack.reconcile_stack(
            state.ordered_prs,
            snapshots,
            self.default_base_ref,
            default_tip,
            is_ancestor=self.git.is_ancestor,
        )
        if (
            baseline.status_for(pr) != stack.ReconciliationStatus.COHERENT
            or live[pr].merged
            or self._head_repository_problem(live[pr])
            or not self._current_branches_match(state, live, baseline, pr, remote_heads)
        ):
            raise ControllerError("legacy evidence transition requires a coherent exact-current live topology")
        if normalized_head != live[pr].head:
            raise ControllerError("transition head does not match the live pull-request head")
        link = baseline.links[pr]
        current = self._anchor(pr, live[pr], link)
        existing = next(
            (item for item in reversed(state.legacy_transitions) if item.matches(pr, current.as_dict())),
            None,
        )
        prior = next(
            (
                item
                for item in reversed(state.legacy_transitions)
                if item.pr == pr and not item.matches(pr, current.as_dict())
            ),
            None,
        )
        if prior is not None and not reauthorize and existing is None:
            raise ControllerError("legacy transition requires explicit reauthorization after the anchor changed")
        if reauthorize and prior is None and existing is None:
            raise ControllerError("legacy transition reauthorization requires a prior transition")
        if retire_missing_hosted_fingerprint is not None:
            if prior is None or retire_missing_hosted_fingerprint not in prior.hosted_fingerprints:
                raise ControllerError(
                    "missing Hosted fingerprint must exactly match a fingerprint from the prior transition"
                )
            if retire_missing_hosted_fingerprint in prior.retired_hosted_fingerprints:
                raise ControllerError("the selected Hosted fingerprint is already retired")
        fingerprints: dict[str, tuple[str, ...]] = {}
        retired_hosted = set((existing or prior).retired_hosted_fingerprints) if (existing or prior) else set()
        newly_missing_hosted: set[str] = set()
        for channel in (policy.Channel.HOSTED, policy.Channel.CLI):
            values = tuple(
                value
                for value in _history(self._evidence_provider, pr, channel)
                if not self._intrinsic_scope_projection(value)
            )
            reauthorization_reference = existing or prior
            if reauthorize and reauthorization_reference is not None:
                expected = reauthorization_reference.fingerprints_for(channel.value)
                expected_set = set(expected)
                found: dict[str, Any] = {}
                checkpoints: set[str] = set()
                for value in values:
                    fingerprint = observation_fingerprint(value)
                    checkpoint = _field(value, "checkpoint", "checkpoint_id")
                    if checkpoint in checkpoints:
                        raise ControllerError("legacy transition evidence has an ambiguous duplicate checkpoint")
                    checkpoints.add(checkpoint)
                    if fingerprint in expected_set:
                        if fingerprint in found:
                            raise ControllerError("legacy transition evidence has an ambiguous duplicate observation")
                        if fingerprint not in retired_hosted:
                            self._legacy_transition_record(value, pr, current.child_head)
                        found[fingerprint] = value
                    else:
                        self._reauthorization_observation(value, pr)
                missing = expected_set - set(found)
                if channel == policy.Channel.HOSTED:
                    newly_missing_hosted.update(missing - retired_hosted)
                elif missing:
                    raise ControllerError("legacy transition reauthorization lost an old evidence observation")
                fingerprints[channel.value] = expected
                continue

            channel_fingerprints: list[str] = []
            checkpoints: set[str] = set()
            for value in values:
                self._legacy_transition_record(value, pr, current.child_head)
                checkpoint = _field(value, "checkpoint", "checkpoint_id")
                if checkpoint in checkpoints:
                    raise ControllerError("legacy transition evidence has an ambiguous duplicate checkpoint")
                checkpoints.add(checkpoint)
                channel_fingerprints.append(observation_fingerprint(value))
            fingerprints[channel.value] = tuple(dict.fromkeys(channel_fingerprints))
        if newly_missing_hosted:
            if retire_missing_hosted_fingerprint is None or newly_missing_hosted != {retire_missing_hosted_fingerprint}:
                raise ControllerError(
                    "reauthorization lost Hosted evidence; retirement requires the exact sole missing prior fingerprint"
                )
            audit_anchor = current.as_dict()
            audit_anchor["live_base_ref"] = live[pr].base_ref
            audit_anchor["live_base_tip"] = live[pr].base_tip
            self._require_complete_reauthorization_audit(pr, fingerprints["hosted"], audit_anchor)
            self._assert_transition_anchor_still_current(state, pr, current)
            retired_hosted.add(retire_missing_hosted_fingerprint)
        elif retire_missing_hosted_fingerprint is not None:
            raise ControllerError("selected Hosted fingerprint is present and cannot be retired as missing")
        if not any(fingerprints.values()):
            raise ControllerError("no legacy evidence requires a transition")
        transition = LegacyEvidenceTransition(
            pr,
            current.child_head,
            current.parent_identity,
            current.parent_head,
            current.merge_base,
            current.patch_id,
            fingerprints["hosted"],
            fingerprints["cli"],
            reason.strip(),
            tuple(value for value in fingerprints["hosted"] if value in retired_hosted),
        )
        if existing is not None:
            if (
                existing.hosted_fingerprints != transition.hosted_fingerprints
                or existing.cli_fingerprints != transition.cli_fingerprints
                or existing.retired_hosted_fingerprints != transition.retired_hosted_fingerprints
            ):
                raise ControllerError("an exact-anchor legacy transition already exists with different evidence")
            return {
                "transition": existing.to_dict(),
                "retirement": {
                    "retired_missing_hosted_fingerprints": list(existing.retired_hosted_fingerprints),
                },
                "legacy_transitions": [item.to_dict() for item in state.legacy_transitions],
                "recorded": False,
            }

        def append_transition(current_state: ReviewState) -> ReviewState:
            if current_state.legacy_transitions != state.legacy_transitions:
                raise ControllerError("legacy transitions changed concurrently; reread before deciding")
            return dataclasses.replace(
                current_state,
                legacy_transitions=current_state.legacy_transitions + (transition,),
            )

        updated = self.store.update(append_transition)
        return {
            "transition": transition.to_dict(),
            "retirement": {
                "retired_missing_hosted_fingerprints": list(transition.retired_hosted_fingerprints),
            },
            "legacy_transitions": [item.to_dict() for item in updated.legacy_transitions],
            "recorded": True,
        }

    def _assert_transition_anchor_still_current(self, state: ReviewState, pr: int, expected: AnchorFacts) -> None:
        """Recheck the complete live anchor after a missing-record audit."""

        remote_heads = self.git.remote_heads()
        live, snapshots, default_tip = self._live_snapshots(state, remote_heads)
        baseline = stack.reconcile_stack(
            state.ordered_prs,
            snapshots,
            self.default_base_ref,
            default_tip,
            is_ancestor=self.git.is_ancestor,
        )
        if (
            baseline.status_for(pr) != stack.ReconciliationStatus.COHERENT
            or live[pr].merged
            or self._head_repository_problem(live[pr])
            or not self._current_branches_match(state, live, baseline, pr, remote_heads)
        ):
            raise ControllerError("topology changed during missing Hosted fingerprint retirement")
        after = self._anchor(pr, live[pr], baseline.links[pr])
        if after.as_dict() != expected.as_dict():
            raise ControllerError(
                "PR head, parent, merge-base, or patch changed during missing Hosted fingerprint retirement"
            )

    # Keep the shorter spelling available to callers of the controller API;
    # the CLI's canonical operation is ``decide transition``.
    def decide_transition(self, **kwargs: Any) -> dict[str, Any]:
        return self.decide_legacy_transition(**kwargs)

    def decide_reconciliation(
        self, *, pr: int, channel: str, checkpoint: str, prior_head: str, reason: str
    ) -> dict[str, Any]:
        """Record one exact-current-anchor decision that reopens normal review."""

        selected_channel = policy.Channel(channel)
        if not reason.strip():
            raise ControllerError("stack reconciliation requires a reason")
        state = self._state()
        if pr not in state.ordered_prs:
            raise ControllerError(f"PR #{pr} is not in the configured review stack")
        remote_heads = self.git.remote_heads()
        live, snapshots, default_tip = self._live_snapshots(state, remote_heads)
        baseline = stack.reconcile_stack(
            state.ordered_prs,
            snapshots,
            self.default_base_ref,
            default_tip,
            is_ancestor=self.git.is_ancestor,
        )
        if not self._current_branches_match(state, live, baseline, pr, remote_heads):
            raise ControllerError("stack reconciliation requires a coherent exact-current live topology")
        link = baseline.links[pr]
        current = self._anchor(pr, live[pr], link)
        normalized_prior_head = _sha(prior_head, "prior checkpoint head")
        self._checkpoint_for_reconciliation(pr, selected_channel, checkpoint, normalized_prior_head, current)
        decision = StackReconciliationDecision(
            pr,
            selected_channel.value,
            checkpoint,
            normalized_prior_head,
            current.child_head,
            current.parent_identity,
            current.parent_head,
            current.merge_base,
            current.patch_id,
            reason,
        )
        updated = self.store.update(
            lambda current_state: dataclasses.replace(
                current_state,
                reconciliations=current_state.reconciliations + (decision,),
            )
        )
        return {"decision": decision.to_dict(), "reconciliations": [item.to_dict() for item in updated.reconciliations]}

    @staticmethod
    def _stop_reason(reason: Any) -> str:
        if not isinstance(reason, str) or not reason.strip() or len(reason) > 500:
            raise ControllerError("review stop requires a non-empty reason of at most 500 characters")
        if any(ord(character) < 0x20 for character in reason):
            raise ControllerError("review stop reason must not contain control characters")
        return reason.strip()

    @staticmethod
    def _stop_reason_acknowledges_over_ceiling(reason: str | None) -> bool:
        return isinstance(reason, str) and reason.startswith("[acknowledged current over-ceiling limitation] ")

    @staticmethod
    def _stop_summary_fingerprints(state: ReviewState, pr: int) -> tuple[str, ...]:
        return tuple(
            sorted(observation_fingerprint(item.to_dict()) for item in state.summary_dispositions if item.pr == pr)
        )

    def _latest_stop_checkpoint(
        self,
        pr: int,
        channel: policy.Channel,
        history: Sequence[Any],
        checkpoint_pin: str | None,
    ) -> tuple[Any, int]:
        parsed: list[tuple[policy.Evidence, Any]] = []
        identities: set[str] = set()
        for value in history:
            try:
                item = policy.Evidence.from_value(value)
            except (AttributeError, KeyError, TypeError, ValueError) as exc:
                raise ControllerError("review stop encountered malformed live evidence") from exc
            if item.pr != pr:
                raise ControllerError("review stop evidence is bound to another pull request")
            if not isinstance(item.checkpoint, str) or not item.checkpoint.strip():
                raise ControllerError("review stop evidence lacks an immutable checkpoint identity")
            if item.checkpoint in identities:
                raise ControllerError("review stop evidence has an ambiguous duplicate checkpoint identity")
            identities.add(item.checkpoint)
            parsed.append((item, value))
        attributable = [
            (index, item, source)
            for index, (item, source) in enumerate(parsed)
            if item.completed is True
            and item.attributable is True
            and not item.correction
            and not item.non_counting
            and item.provisional is False
        ]
        if not attributable:
            raise ControllerError("review stop requires a completed attributable checkpoint")
        index, latest, latest_source = attributable[-1]
        if checkpoint_pin is not None and checkpoint_pin != _field(latest_source, "checkpoint", "checkpoint_id"):
            raise ControllerError("review stop checkpoint does not match the latest attributable result")
        if not (
            latest.anchored is True
            and latest.provisional is False
            and _field(latest_source, "channel") in (None, channel.value)
        ):
            raise ControllerError("latest attributable checkpoint is unanchored, provisional, or from another channel")
        try:
            reviewed_head = _sha(_field(latest_source, "head", "reviewed_head"), "latest reviewed head")
            reviewed_patch = _field(latest_source, "patch_id", "patch_identity")
            _sha(_field(latest_source, "child_head"), "checkpoint child head")
            _sha(_field(latest_source, "parent_head"), "checkpoint parent head")
            _sha(_field(latest_source, "merge_base"), "checkpoint merge base")
        except ControllerError as exc:
            raise ControllerError("latest checkpoint has an incomplete exact stack identity") from exc
        if _field(latest_source, "child_head").casefold() != reviewed_head.casefold():
            raise ControllerError("latest checkpoint reviewed head does not match its anchored child head")
        if not isinstance(reviewed_patch, str) or not reviewed_patch.strip():
            raise ControllerError("latest checkpoint has no owned-patch identity")
        for later, _source in parsed[index + 1 :]:
            if (
                later.completed
                and later.attributable
                and not later.correction
                and not later.non_counting
                and not later.provisional
            ):
                raise ControllerError("latest attributable checkpoint is ambiguous")
        return latest_source, index

    def _stop_audit(
        self,
        pr: int,
        current: AnchorFacts,
        retained_ambiguous_fingerprints: tuple[str, ...],
        *,
        allow_historical_unmatched: bool = False,
        allow_historical_terminal_ambiguity: bool = False,
        allow_cli_hosted_overlap: bool = False,
        allow_hosted_cli_overlap: bool = False,
        acknowledged_over_ceiling_checkpoints: Sequence[str] = (),
        audit_snapshot: dict[str, Any] | None = None,
        audit_override: Mapping[str, Any] | None = None,
    ) -> Mapping[str, Any]:
        provider = self._evidence_provider
        review_audit = getattr(provider, "review_stop_audit", None)
        if audit_override is not None:
            audit = audit_override
        elif callable(review_audit):
            try:
                audit = review_audit(
                    pr,
                    current.as_stop_audit_dict(),
                    retained_ambiguous_fingerprints=retained_ambiguous_fingerprints,
                )
            except (OSError, ValueError, TypeError, KeyError) as exc:
                raise ControllerError(f"complete review-stop evidence is unavailable: {exc}") from exc
        elif retained_ambiguous_fingerprints:
            raise ControllerError("exact terminal-ambiguity retention requires the live review-stop evidence audit")
        elif self.isolated_fixture:
            audit = {
                "complete": True,
                "active_reservations": [],
                "unmatched_responses": [],
                "ambiguous_responses": [],
                "unresolved_findings": [],
                "retained_ambiguous": [],
            }
        elif callable(getattr(provider, "legacy_transition_reauthorization_audit", None)):
            try:
                audit = provider.legacy_transition_reauthorization_audit(pr, (), current.as_dict())
            except (OSError, ValueError, TypeError, KeyError) as exc:
                raise ControllerError(f"complete review-stop evidence is unavailable: {exc}") from exc
        else:
            raise ControllerError("review stop requires complete paginated evidence for both review channels")
        if audit_snapshot is not None and isinstance(audit, Mapping):
            audit_snapshot.update(copy.deepcopy(dict(audit)))
        if not isinstance(audit, Mapping) or audit.get("complete") is not True:
            raise ControllerError("review stop requires complete paginated evidence for both review channels")
        if "request_preparation_only" in audit and type(audit["request_preparation_only"]) is not bool:
            raise ControllerError("review-stop audit has malformed preparation movement evidence")
        if (
            not audit.get("request_preparation_only", False)
            and audit.get("head") is not None
            and str(audit.get("head")).casefold() != current.child_head.casefold()
        ):
            raise ControllerError("review-stop evidence audit observed a stale pull-request head")
        audit_anchor = audit.get("anchor")
        if audit_anchor is not None and (
            not isinstance(audit_anchor, Mapping)
            or {name: audit_anchor.get(name) for name in current.as_dict()} != current.as_dict()
        ):
            raise ControllerError("review-stop evidence audit observed a stale stack identity")

        terminal_ambiguities = audit.get("ambiguous_terminal_responses", [])
        terminal_rate_limits = audit.get("terminal_rate_limits", [])
        retained_ambiguities = audit.get("retained_ambiguous", [])
        if not isinstance(terminal_ambiguities, Sequence) or isinstance(terminal_ambiguities, (str, bytes)):
            raise ControllerError("review-stop audit has malformed terminal ambiguity evidence")
        if not isinstance(terminal_rate_limits, Sequence) or isinstance(terminal_rate_limits, (str, bytes)):
            raise ControllerError("review-stop audit has malformed terminal rate-limit evidence")
        if not isinstance(retained_ambiguities, Sequence) or isinstance(retained_ambiguities, (str, bytes)):
            raise ControllerError("review-stop audit has malformed retained ambiguity evidence")
        normalized_rate_limits: list[dict[str, Any]] = []
        rate_limit_trigger_ids: set[int] = set()
        rate_limit_response_ids: set[int] = set()
        for item in terminal_rate_limits:
            if (
                not isinstance(item, Mapping)
                or type(item.get("trigger_id")) is not int
                or item["trigger_id"] <= 0
                or type(item.get("response_id")) is not int
                or item["response_id"] <= 0
                or not isinstance(item.get("captured_head"), str)
                or re.fullmatch(r"[0-9a-fA-F]{40}", item["captured_head"]) is None
                or "cooldown_until" not in item
                or (item["cooldown_until"] is not None and parse_timestamp(item["cooldown_until"]) is None)
                or item.get("terminal") is not True
                or item.get("attributable") is not True
                or item["trigger_id"] in rate_limit_trigger_ids
                or item["response_id"] in rate_limit_response_ids
            ):
                raise ControllerError("review-stop audit has malformed terminal rate-limit identity")
            rate_limit_trigger_ids.add(item["trigger_id"])
            rate_limit_response_ids.add(item["response_id"])
            normalized_rate_limits.append(dict(item))
        terminal_fingerprints = [
            item.get("fingerprint")
            for item in terminal_ambiguities
            if isinstance(item, Mapping) and isinstance(item.get("fingerprint"), str)
        ]
        retained_fingerprints = [
            item.get("fingerprint")
            for item in retained_ambiguities
            if isinstance(item, Mapping) and isinstance(item.get("fingerprint"), str)
        ]
        if (
            len(terminal_fingerprints) != len(terminal_ambiguities)
            or len(retained_fingerprints) != len(retained_ambiguities)
            or len(set(terminal_fingerprints)) != len(terminal_fingerprints)
            or len(set(retained_fingerprints)) != len(retained_fingerprints)
        ):
            raise ControllerError("review-stop audit has malformed terminal ambiguity fingerprints")

        # A terminal response captured against an earlier, proven ancestor head
        # remains visible in the history but is no longer an active reservation.
        # The newest terminal response is never historical: it must be retained
        # by its exact fingerprint, and unknown heads remain blockers.
        historical_terminal_fingerprints: set[str] = set()
        terminal_times = [
            parse_timestamp(item.get("response_at")) if isinstance(item, Mapping) else None
            for item in terminal_ambiguities
        ]
        if (
            allow_historical_terminal_ambiguity
            and terminal_ambiguities
            and all(timestamp is not None for timestamp in terminal_times)
        ):
            newest_time = max(timestamp for timestamp in terminal_times if timestamp is not None)
            newest_fingerprints = {
                fingerprint
                for fingerprint, timestamp in zip(terminal_fingerprints, terminal_times, strict=True)
                if timestamp == newest_time
            }
            if not newest_fingerprints.issubset(retained_fingerprints):
                raise ControllerError("review stop requires exact retention of the latest terminal ambiguity")
            for item, fingerprint, timestamp in zip(
                terminal_ambiguities, terminal_fingerprints, terminal_times, strict=True
            ):
                captured_head = item.get("captured_head") if isinstance(item, Mapping) else None
                if (
                    timestamp is None
                    or timestamp >= newest_time
                    or not isinstance(captured_head, str)
                    or re.fullmatch(r"[0-9a-fA-F]{40}", captured_head) is None
                    or captured_head.casefold() == current.child_head.casefold()
                    or fingerprint in retained_fingerprints
                ):
                    continue
                try:
                    is_historical_ancestor = self.git.is_ancestor(captured_head, current.child_head)
                except (OSError, subprocess.SubprocessError, ValueError):
                    is_historical_ancestor = False
                if is_historical_ancestor:
                    historical_terminal_fingerprints.add(fingerprint)

        if (
            not set(retained_fingerprints).issubset(terminal_fingerprints)
            or set(retained_fingerprints) != set(retained_ambiguous_fingerprints)
            or not (set(terminal_fingerprints) - historical_terminal_fingerprints).issubset(retained_fingerprints)
        ):
            raise ControllerError("review stop is blocked by unretained terminal ambiguity")

        normalized_audit = dict(audit)
        normalized_audit["terminal_rate_limits"] = tuple(normalized_rate_limits)
        normalized_audit["historical_terminal_fingerprints"] = tuple(sorted(historical_terminal_fingerprints))
        allowed_active_hosted_reservations: list[Mapping[str, Any]] = []
        if allow_cli_hosted_overlap:
            active_hosted = audit.get("active_hosted_reservations", ())
            if not isinstance(active_hosted, Sequence) or isinstance(active_hosted, (str, bytes)):
                raise ControllerError("review-stop audit has malformed active Hosted response evidence")
            allowed_active_hosted_reservations = [
                item
                for item in active_hosted
                if self._hosted_active_response_overlaps_cli(
                    item,
                    pr,
                    current.child_head,
                    current,
                    require_response_identity=True,
                )
            ]
            if len(allowed_active_hosted_reservations) > 1:
                raise ControllerError("review-stop audit found multiple active Hosted overlap responses")
            active_identities = [
                (_field(item, "trigger_id"), _field(item, "response_id")) for item in allowed_active_hosted_reservations
            ]
            if len(set(active_identities)) != len(active_identities):
                raise ControllerError("review-stop audit has duplicate active Hosted response identities")
        active_cli_reviews = audit.get("active_cli_reviews", ())
        if not isinstance(active_cli_reviews, Sequence) or isinstance(active_cli_reviews, (str, bytes)):
            raise ControllerError("review-stop audit has malformed active CLI review evidence")
        allowed_active_cli_reviews: list[Mapping[str, Any]] = []
        if active_cli_reviews:
            if not allow_hosted_cli_overlap:
                raise ControllerError("review stop is blocked by an active CLI review")
            if len(active_cli_reviews) != 1:
                raise ControllerError("review-stop audit found multiple active CLI overlap runs")
            observation = active_cli_reviews[0]
            if not self._active_cli_review_overlaps_hosted(observation, pr, current):
                raise ControllerError("review-stop audit has an unverified active CLI overlap run")
            allowed_active_cli_reviews.append(observation)
        for field, description in (
            ("active_reservations", "active review or reservation"),
            ("unmatched_responses", "unmatched review response"),
            ("ambiguous_responses", "unresolved evidence ambiguity"),
            ("unknown_review_evidence", "unknown or unresolved review evidence"),
        ):
            values = normalized_audit.get(field, [])
            if not isinstance(values, Sequence) or isinstance(values, (str, bytes)):
                raise ControllerError(f"review-stop audit has malformed {field}")
            if field == "unknown_review_evidence" and acknowledged_over_ceiling_checkpoints:
                allowed = set(acknowledged_over_ceiling_checkpoints)
                if any(not isinstance(value, str) for value in values):
                    raise ControllerError("review-stop audit has malformed unknown review evidence")
                values = [value for value in values if value not in allowed]
            if field == "active_reservations" and allowed_active_hosted_reservations:
                if values.count("active") < len(allowed_active_hosted_reservations):
                    raise ControllerError("active Hosted overlap evidence is not bound to the complete live audit")
                values = list(values)
                for _item in allowed_active_hosted_reservations:
                    values.remove("active")
                normalized_audit[field] = values
            if field in {"active_reservations", "ambiguous_responses"} and historical_terminal_fingerprints:
                expected = (
                    ("ambiguous", len(historical_terminal_fingerprints))
                    if field == "active_reservations"
                    else ("unattributed trigger response", len(historical_terminal_fingerprints))
                )
                if values.count(expected[0]) == expected[1] and expected[1] > 0:
                    values = list(values)
                    for _ in range(expected[1]):
                        values.remove(expected[0])
                    normalized_audit[field] = values
            if values:
                raise ControllerError(f"review stop is blocked by an {description}")
        normalized_audit["allowed_active_hosted_reservations"] = tuple(allowed_active_hosted_reservations)
        normalized_audit["allowed_active_cli_reviews"] = tuple(allowed_active_cli_reviews)
        historical_unmatched = audit.get("historical_unmatched_responses", [])
        if not isinstance(historical_unmatched, Sequence) or isinstance(historical_unmatched, (str, bytes)):
            raise ControllerError("review-stop audit has malformed historical unmatched-response evidence")
        if historical_unmatched and not allow_historical_unmatched:
            raise ControllerError(
                "review stop is blocked by historical unmatched evidence outside a direct Hosted stop"
            )
        unresolved_findings = audit.get("unresolved_findings", [])
        finding_only_findings = audit.get("finding_only_findings", [])
        unknown_review_evidence = audit.get("unknown_review_evidence", [])
        if any(
            not isinstance(values, Sequence) or isinstance(values, (str, bytes))
            for values in (unresolved_findings, finding_only_findings, unknown_review_evidence)
        ) or any(
            not isinstance(value, str)
            for values in (unresolved_findings, finding_only_findings, unknown_review_evidence)
            for value in values
        ):
            raise ControllerError("review-stop audit has malformed finding classification")
        # Older providers expose one untyped unresolved list. Treat it as
        # unknown; only the canonical runtime adapter's exact split may prove
        # that every blocker is a validated finding obligation.
        if "finding_only_findings" not in audit or "unknown_review_evidence" not in audit:
            if unresolved_findings:
                raise ControllerError("review stop is blocked by an unresolved actionable finding or thread (untyped)")
        else:
            ignored_over_ceiling = set(acknowledged_over_ceiling_checkpoints)
            classified_unresolved = [value for value in unresolved_findings if value not in ignored_over_ceiling]
            classified_unknown = [value for value in unknown_review_evidence if value not in ignored_over_ceiling]
            if sorted(classified_unresolved) != sorted([*finding_only_findings, *classified_unknown]):
                raise ControllerError("review-stop audit has inconsistent finding classification")
        return normalized_audit

    def _stop_ambiguity_pins(
        self,
        histories: Mapping[policy.Channel, Sequence[Any]],
        fingerprints: tuple[str, ...],
        reason: str | None,
        audit: Mapping[str, Any],
    ) -> tuple[tuple[str, ...], str | None]:
        historical_fingerprints = set(audit.get("historical_terminal_fingerprints", ()))
        candidates = [
            value for history in histories.values() for value in history if _field(value, "terminal_ambiguous") is True
        ]
        if not fingerprints:
            if candidates or reason is not None:
                raise ControllerError("terminal ambiguous evidence requires exact fingerprints and a retention reason")
            return (), None
        if any(not re.fullmatch(r"[0-9a-f]{64}", value) for value in fingerprints):
            raise ControllerError("retained ambiguity fingerprints must be exact lowercase SHA-256 values")
        if len(set(fingerprints)) != len(fingerprints):
            raise ControllerError("retained ambiguity fingerprints must be unique")
        if not isinstance(reason, str) or not reason.strip() or len(reason) > 500:
            raise ControllerError("retaining terminal ambiguity requires a reason of at most 500 characters")
        if any(ord(character) < 0x20 for character in reason):
            raise ControllerError("ambiguity retention reason must not contain control characters")
        candidate_fingerprints = [_field(value, "fingerprint") for value in candidates]
        if (
            any(
                not isinstance(value, str) or not re.fullmatch(r"[0-9a-f]{64}", value)
                for value in candidate_fingerprints
            )
            or len(set(candidate_fingerprints)) != len(candidate_fingerprints)
            or not set(candidate_fingerprints).issubset(set(fingerprints) | historical_fingerprints)
        ):
            raise ControllerError("retained ambiguity fingerprints must identify every current terminal response")
        audit_fingerprints = [
            item.get("fingerprint") for item in audit.get("retained_ambiguous", ()) if isinstance(item, Mapping)
        ]
        if (
            len(audit_fingerprints) != len(audit.get("retained_ambiguous", ()))
            or len(set(audit_fingerprints)) != len(audit_fingerprints)
            or set(audit_fingerprints) != set(fingerprints)
        ):
            raise ControllerError("complete evidence audit did not retain every exact terminal response fingerprint")
        return fingerprints, reason.strip()

    def _check_stop_evidence(
        self,
        state: ReviewState,
        pr: int,
        channel: policy.Channel,
        current: AnchorFacts,
        reconciliation: stack.Reconciliation,
        *,
        checkpoint_pin: str | None,
        allow_historical_unmatched: bool = False,
        allow_historical_terminal_ambiguity: bool = False,
        retained_ambiguous_fingerprints: tuple[str, ...] = (),
        ambiguity_reason: str | None = None,
        acknowledge_over_ceiling: bool = False,
        require_checkpoint_ancestry: bool = True,
        allow_cli_hosted_overlap: bool = False,
        allow_hosted_cli_overlap: bool = False,
        stop_audit_cache: dict[tuple[Any, ...], Mapping[str, Any]] | None = None,
        history_cache: dict[tuple[int, str], list[Any]] | None = None,
        audit_override: Mapping[str, Any] | None = None,
    ) -> tuple[Any, tuple[tuple[str, ...], str] | None, dict[policy.Channel, list[Any]]]:
        allow_exact_hosted_overlap = allow_cli_hosted_overlap and channel == policy.Channel.CLI
        allow_exact_cli_overlap = allow_hosted_cli_overlap and channel == policy.Channel.HOSTED
        histories = {
            selected: self._policy_history(state, pr, selected, reconciliation, history_cache=history_cache)
            for selected in (policy.Channel.HOSTED, policy.Channel.CLI)
        }
        acknowledged_over_ceiling_checkpoints: tuple[str, ...] = ()
        if acknowledge_over_ceiling:
            over_ceiling_by_checkpoint: dict[str, Any] = {}
            over_ceiling_fingerprints: dict[str, str] = {}
            for history in histories.values():
                for value in history:
                    checkpoint = _field(value, "checkpoint", "checkpoint_id")
                    if isinstance(checkpoint, str) and checkpoint.startswith("over-ceiling:"):
                        fingerprint = observation_fingerprint(value)
                        previous_fingerprint = over_ceiling_fingerprints.get(checkpoint)
                        if previous_fingerprint is not None and previous_fingerprint != fingerprint:
                            raise ControllerError("conflicting duplicate over-ceiling checkpoint metadata")
                        over_ceiling_fingerprints[checkpoint] = fingerprint
                        over_ceiling_by_checkpoint[checkpoint] = value
            acknowledged = [
                checkpoint
                for checkpoint, value in over_ceiling_by_checkpoint.items()
                if (
                    _field(value, "over_ceiling") is True
                    and _field(value, "head", "reviewed_head") == current.child_head
                    and not any(
                        _field(value, flag) is True
                        for flag in (
                            "held",
                            "unstable",
                            "unreconciled",
                            "parent_moved",
                            "rate_limited",
                            "active_review",
                            "active_reservation",
                            "actionable",
                        )
                    )
                )
            ]
            if not acknowledged:
                raise ControllerError("over-ceiling acknowledgment requires unique current-head over-ceiling evidence")
            acknowledged_over_ceiling_checkpoints = tuple(acknowledged)
        cache_key = (
            pr,
            current.child_head,
            current.parent_identity,
            current.parent_head,
            current.merge_base,
            current.patch_id,
            tuple(retained_ambiguous_fingerprints),
            tuple(acknowledged_over_ceiling_checkpoints),
            allow_historical_unmatched,
            allow_historical_terminal_ambiguity,
            allow_exact_hosted_overlap,
            allow_exact_cli_overlap,
        )
        if audit_override is None and stop_audit_cache is not None and cache_key in stop_audit_cache:
            audit = stop_audit_cache[cache_key]
            snapshot = stop_audit_cache.get(("snapshot", cache_key), {"evidence": audit, "error": None})
            latest_snapshot = stop_audit_cache.get(("latest", pr), snapshot)
            if snapshot["evidence"] != latest_snapshot["evidence"]:
                raise ControllerError("review safety evidence changed during audit; refresh status before clearance")
        else:
            raw_snapshot: dict[str, Any] = {}
            audit_error = None
            try:
                audit = self._stop_audit(
                    pr,
                    current,
                    retained_ambiguous_fingerprints,
                    allow_historical_unmatched=allow_historical_unmatched,
                    allow_historical_terminal_ambiguity=allow_historical_terminal_ambiguity,
                    allow_cli_hosted_overlap=allow_exact_hosted_overlap,
                    allow_hosted_cli_overlap=allow_exact_cli_overlap,
                    acknowledged_over_ceiling_checkpoints=acknowledged_over_ceiling_checkpoints,
                    audit_snapshot=raw_snapshot,
                    audit_override=audit_override,
                )
            except (ControllerError, OSError, subprocess.SubprocessError, ValueError) as error:
                audit = raw_snapshot
                audit_error = str(error)
            snapshot = {
                "evidence": raw_snapshot,
                "error": audit_error,
                "overlap": {
                    "allow_cli_hosted_overlap": allow_exact_hosted_overlap,
                    "allow_hosted_cli_overlap": allow_exact_cli_overlap,
                },
            }
            if stop_audit_cache is not None:
                stop_audit_cache[cache_key] = audit
                stop_audit_cache[("snapshot", cache_key)] = snapshot
                stop_audit_cache[("latest", pr)] = snapshot
        if stop_audit_cache is not None:
            stop_audit_cache[("clearance", pr, channel.value)] = snapshot
        # Authorization consumes the history snapshot refreshed by the audit,
        # rather than lists captured before the provider invalidated its caches.
        refreshed = audit.get("channel_histories")
        if refreshed is not None and (
            not isinstance(refreshed, Mapping)
            or any(
                not isinstance(refreshed.get(selected.value), Sequence)
                or isinstance(refreshed.get(selected.value), (str, bytes))
                for selected in (policy.Channel.HOSTED, policy.Channel.CLI)
            )
        ):
            snapshot["error"] = "review-stop audit has incomplete refreshed channel histories"
            raise ControllerError(snapshot["error"])
        refreshed_cache = history_cache if history_cache is not None else {}
        for selected in (policy.Channel.HOSTED, policy.Channel.CLI):
            if refreshed is None and snapshot["error"] is not None:
                continue
            refreshed_cache[(pr, selected.value)] = list(
                refreshed[selected.value] if refreshed is not None else _history(self._evidence_provider, pr, selected)
            )
        if snapshot["error"] is not None:
            raise ControllerError(snapshot["error"])
        histories = {
            selected: self._policy_history(state, pr, selected, reconciliation, history_cache=refreshed_cache)
            for selected in (policy.Channel.HOSTED, policy.Channel.CLI)
        }
        retained_fingerprints, retained_reason = self._stop_ambiguity_pins(
            histories, retained_ambiguous_fingerprints, ambiguity_reason, audit
        )
        non_blocking_ambiguity_fingerprints = set(retained_fingerprints) | set(
            audit.get("historical_terminal_fingerprints", ())
        )
        # A verified terminal rate limit is a channel cooldown, not unresolved
        # review evidence. The request selector enforces an active Hosted
        # cooldown; it must not hold CLI or finding-clearance decisions here.
        non_blocking_rate_limits = {item["trigger_id"]: item for item in audit.get("terminal_rate_limits", ())}
        allowed_active_hosted_reservations = {
            (_field(item, "trigger_id"), _field(item, "response_id"))
            for item in audit.get("allowed_active_hosted_reservations", ())
        }
        consumed_active_hosted_reservations: set[tuple[int, int]] = set()
        consumed_active_cli_overlap = False
        finding_reasons = list(audit.get("finding_only_findings", ()))
        audited_head = (
            _sha(audit.get("head"), "audited public head")
            if audit.get("request_preparation_only") is True
            else current.child_head
        )
        for selected, history in histories.items():
            parsed_history = [policy.Evidence.from_value(value) for value in history]
            valid_review_indexes = [
                index
                for index, evidence_value in enumerate(parsed_history)
                if evidence_value.completed is True
                and evidence_value.attributable is True
                and evidence_value.anchored is True
                and evidence_value.provisional is False
                and not evidence_value.correction
                and not evidence_value.non_counting
            ]
            last_valid_review_index = max(valid_review_indexes, default=-1)
            last_valid_review_head = (
                parsed_history[last_valid_review_index].head if last_valid_review_index >= 0 else None
            )
            for index, (value, evidence_value) in enumerate(zip(history, parsed_history, strict=True)):
                trigger_id = _field(value, "trigger_id")
                rate_limit_proof = non_blocking_rate_limits.get(trigger_id)
                audited_terminal_rate_limit = (
                    isinstance(rate_limit_proof, Mapping)
                    and _field(value, "checkpoint", "checkpoint_id") == f"trigger:{trigger_id}"
                    and _field(value, "response_id") == rate_limit_proof["response_id"]
                    and _field(value, "head", "reviewed_head") == rate_limit_proof["captured_head"]
                    and _field(value, "cooldown_until") == rate_limit_proof["cooldown_until"]
                    and _field(value, "terminal") is True
                    and _field(value, "attributable") is True
                    and _field(value, "rate_limited") is True
                )
                if evidence_value.provisional and index > last_valid_review_index:
                    provisional_head = evidence_value.head.casefold()
                    relevant_heads = {current.child_head.casefold(), audited_head.casefold()}
                    if last_valid_review_head:
                        relevant_heads.add(last_valid_review_head.casefold())
                    if not provisional_head or provisional_head in relevant_heads:
                        raise ControllerError(f"{selected.value} channel has provisional review evidence")
                if (
                    evidence_value.completed is True
                    and evidence_value.attributable is True
                    and evidence_value.provisional is False
                    and not evidence_value.correction
                    and not evidence_value.non_counting
                ):
                    if type(evidence_value.accepted) is not int or evidence_value.accepted < 0:
                        raise ControllerError(f"{selected.value} channel has a malformed accepted finding count")
                    if evidence_value.accepted > 0:
                        has_source_resolution = _field(value, "source_resolution_status") is not None
                        if has_source_resolution:
                            source_status = _field(value, "source_resolution_status")
                            if source_status != "resolved" and source_status != "finding_pending":
                                raise ControllerError(
                                    f"{self._accepted_findings_pending_reason(value)}; "
                                    "accepted-finding source linkage or resolution proof is uncertain"
                                )
                            if self._accepted_findings_pending(value, current.child_head):
                                finding_reasons.append(self._accepted_findings_pending_reason(value))
                        else:
                            reviewed_head = _field(value, "head", "reviewed_head")
                            try:
                                reviewed_head = _sha(reviewed_head, "accepted finding reviewed head")
                                has_corrected_descendant = self.git.is_ancestor(reviewed_head, current.child_head)
                            except (ControllerError, OSError, subprocess.SubprocessError, ValueError) as exc:
                                raise ControllerError(
                                    "could not verify corrected-head ancestry for accepted findings"
                                ) from exc
                            if not has_corrected_descendant or reviewed_head == current.child_head.casefold():
                                finding_reasons.append(
                                    "accepted findings need a published corrected head before review can stop"
                                )
                active_hosted_identity = (_field(value, "trigger_id"), _field(value, "response_id"))
                if (
                    allow_exact_hosted_overlap
                    and selected == policy.Channel.HOSTED
                    and active_hosted_identity in allowed_active_hosted_reservations
                    and active_hosted_identity not in consumed_active_hosted_reservations
                    and self._hosted_active_response_overlaps_cli(
                        value,
                        pr,
                        current.child_head,
                        current,
                    )
                ):
                    consumed_active_hosted_reservations.add(active_hosted_identity)
                    continue
                if (
                    allow_exact_cli_overlap
                    and selected == policy.Channel.CLI
                    and self._active_cli_review_overlaps_hosted(value, pr, current)
                ):
                    if consumed_active_cli_overlap:
                        raise ControllerError("review-stop audit found multiple active CLI overlap runs")
                    consumed_active_cli_overlap = True
                    continue
                blocker_flags = (
                    "unreconciled",
                    "parent_moved",
                    "over_ceiling",
                    "active_review",
                    "active_reservation",
                )
                # Only the observer's unknown-reset cooldown hold is waived.
                # Other held/unstable evidence retains its ordinary fence.
                audited_unknown_reset_hold = (
                    audited_terminal_rate_limit
                    and rate_limit_proof["cooldown_until"] is None
                    and _field(value, "reason") == "Hosted cooldown has no attributable reset time"
                )
                if (
                    any(_field(value, flag) is True for flag in blocker_flags)
                    or (
                        not audited_unknown_reset_hold
                        and any(_field(value, flag) is True for flag in ("held", "unstable"))
                    )
                    or (not audited_terminal_rate_limit and _field(value, "rate_limited") is True)
                ):
                    checkpoint = _field(value, "checkpoint", "checkpoint_id")
                    if (
                        acknowledge_over_ceiling
                        and checkpoint in acknowledged_over_ceiling_checkpoints
                        and evidence_value.head == current.child_head
                        and _field(value, "over_ceiling") is True
                        and not any(
                            _field(value, flag) is True
                            for flag in (
                                "held",
                                "unstable",
                                "unreconciled",
                                "parent_moved",
                                "rate_limited",
                                "active_review",
                                "active_reservation",
                                "actionable",
                            )
                        )
                    ):
                        continue
                    if (
                        _field(value, "terminal_ambiguous") is True
                        and _field(value, "fingerprint") in non_blocking_ambiguity_fingerprints
                    ):
                        continue
                    checkpoint = _field(value, "checkpoint", "checkpoint_id")
                    historical_unanchored_checkpoint = (
                        evidence_value.completed is True
                        and evidence_value.attributable is True
                        and evidence_value.anchored is False
                        and evidence_value.provisional is False
                        and not evidence_value.correction
                        and isinstance(checkpoint, str)
                        and bool(checkpoint)
                        and not checkpoint.startswith(
                            (
                                "trigger:",
                                "trigger-uncheckpointed:",
                                "pending-capture:",
                                "review-threads:",
                                "summary-actions:",
                                "over-ceiling:",
                            )
                        )
                        and isinstance(evidence_value.head, str)
                        and re.fullmatch(r"[0-9a-fA-F]{40}", evidence_value.head) is not None
                        and evidence_value.head.casefold() != current.child_head.casefold()
                        and _field(value, "unstable") is not True
                        and _field(value, "rate_limited") is not True
                        and _field(value, "active_review") is not True
                        and _field(value, "active_reservation") is not True
                        and _field(value, "actionable") is not True
                    )
                    other_blocker_flags = any(
                        _field(value, flag) is True
                        for flag in (
                            "unstable",
                            "rate_limited",
                            "unreconciled",
                            "parent_moved",
                            "over_ceiling",
                            "active_review",
                            "active_reservation",
                            "actionable",
                        )
                    )
                    if allow_historical_unmatched and historical_unanchored_checkpoint and not other_blocker_flags:
                        continue
                    if (
                        _field(value, "finding_only_hold") is True
                        and _field(value, "held") is True
                        and not any(
                            _field(value, flag) is True
                            for flag in (
                                "unstable",
                                "unreconciled",
                                "parent_moved",
                                "over_ceiling",
                                "rate_limited",
                                "active_review",
                                "active_reservation",
                                "actionable",
                            )
                        )
                    ):
                        finding_reasons.append(f"{selected.value} channel has validated finding-only evidence")
                    else:
                        raise ControllerError(f"{selected.value} channel has unresolved review evidence")
                checkpoint_id = _field(value, "checkpoint", "checkpoint_id")
                if isinstance(checkpoint_id, str) and checkpoint_id.startswith(("trigger:", "pending-capture:")):
                    if audited_terminal_rate_limit:
                        continue
                    if (
                        _field(value, "terminal_ambiguous") is True
                        and _field(value, "fingerprint") in non_blocking_ambiguity_fingerprints
                    ):
                        continue
                    raise ControllerError(f"{selected.value} channel has pending or ambiguous review evidence")
        history = histories[channel]
        latest, index = self._latest_stop_checkpoint(pr, channel, history, checkpoint_pin)
        reviewed_head = _field(latest, "head", "reviewed_head")
        if require_checkpoint_ancestry:
            try:
                is_ancestor = self.git.is_ancestor(reviewed_head, current.child_head)
            except (OSError, subprocess.SubprocessError, ValueError) as exc:
                raise ControllerError("could not verify latest reviewed-head ancestry") from exc
            equivalent_retain = (
                self._has_stop_retain_judgment(
                    state,
                    pr,
                    channel.value,
                    _field(latest, "checkpoint", "checkpoint_id"),
                    reviewed_head,
                    current.patch_id,
                    reconciliation.status_for(pr, channel.value),
                )
                and _field(latest, "patch_id", "patch_identity") == current.patch_id
            )
            if not is_ancestor and not equivalent_retain:
                raise ControllerError("latest reviewed head is not an ancestor of the live stop head")
        accepted = _field(latest, "accepted")
        if type(accepted) is not int or accepted < 0:
            raise ControllerError("latest attributable checkpoint has a malformed accepted count")
        reviewed_head = _field(latest, "head", "reviewed_head")
        has_source_resolution = _field(latest, "source_resolution_status") is not None
        if (
            accepted > 0
            and reviewed_head.casefold() == current.child_head.casefold()
            and (not has_source_resolution or self._accepted_findings_pending(latest, current.child_head))
        ):
            finding_reasons.append(self._accepted_findings_pending_reason(latest))
        # Positive classifications are valid only after every canonical safety,
        # history and latest-checkpoint check above has completed.
        if audit.get("request_preparation_only") is True:
            raise _RequestPreparationError("known pull-request preparation identity moved after complete safety audit")
        if finding_reasons:
            raise _FindingOnlyStopEvidence(finding_reasons[0])
        retained = (retained_fingerprints, retained_reason) if retained_fingerprints else None
        return latest, retained, histories

    def _stop_progress(
        self,
        allocation: ReviewAllocation,
        state: ReviewState,
        history: Sequence[Any],
        current: AnchorFacts | None,
        reconciliation: stack.ReconciliationStatus,
        reconciliation_result: stack.Reconciliation | None,
        stop_audit_cache: dict[tuple[Any, ...], Mapping[str, Any]] | None = None,
        history_cache: dict[tuple[int, str], list[Any]] | None = None,
    ) -> dict[str, Any]:
        def invalid(reason: str) -> dict[str, Any]:
            return {
                "status": "INVALID",
                "reason": reason,
                "stop_basis": allocation.stop_basis,
                "stop_checkpoint": allocation.stop_checkpoint,
                "stop_head": allocation.stop_head,
                "stop_reason": allocation.stop_reason,
                "checkpoint": allocation.stop_checkpoint,
            }

        if allocation.stop_basis is None:
            return invalid("the allocation has no canonical stop decision")
        # decide_stop audits the evidence and exact live identity before it
        # persists this decision. Re-reading live topology or review evidence
        # here would make an audited human stop expire as heads move.
        return {
            "status": "STOPPED",
            "reason": "review discovery explicitly stopped; merge readiness remains separate",
            "stop_basis": allocation.stop_basis,
            "stop_checkpoint": allocation.stop_checkpoint,
            "stop_reviewed_head": allocation.stop_reviewed_head,
            "stop_head": allocation.stop_head,
            "stop_reason": allocation.stop_reason,
            "retained_ambiguous_fingerprints": list(allocation.retained_ambiguous_fingerprints),
            "checkpoint": allocation.stop_checkpoint,
            "accepted": None,
        }

    def _durable_stop_allocations(self, state: ReviewState, pr: int) -> dict[str, dict[str, Any]]:
        """Project persisted channel stops without rechecking moved review identities."""

        stopped = {}
        for channel in (policy.Channel.HOSTED, policy.Channel.CLI):
            allocation = state.allocations.get(f"{pr}:{channel.value}")
            if allocation is None or allocation.stop_basis is None:
                continue
            stopped[channel.value] = self._stop_progress(
                allocation,
                state,
                (),
                None,
                stack.ReconciliationStatus.UNRECONCILED,
                None,
            )
        return stopped

    @staticmethod
    def _has_stop_retain_judgment(
        state: ReviewState,
        pr: int,
        channel: str,
        checkpoint: str,
        reviewed_head: str,
        current_patch_id: str,
        reconciliation: stack.ReconciliationStatus,
    ) -> bool:
        """Allow only a checkpoint-bound retain over unchanged equivalent history."""

        return reconciliation == stack.ReconciliationStatus.EQUIVALENT_HISTORY and any(
            item.pr == pr
            and item.channel == channel
            and item.decision == "retain"
            and item.head == reviewed_head
            and item.checkpoint == checkpoint
            and item.patch_id == current_patch_id
            for item in state.judgments
        )

    @staticmethod
    def _bounded_allocation_evidence(allocation: ReviewAllocation, history: Sequence[Any]) -> dict[str, Any]:
        """Project immutable completed results created after the allocation decision."""

        baseline_checkpoints = set(allocation.baseline_checkpoints)
        baseline_checkpoint = allocation.baseline_checkpoint
        baseline_matches = [
            (index, value)
            for index, value in enumerate(history)
            if baseline_checkpoint is not None
            and _field(value, "checkpoint", "checkpoint_id") == baseline_checkpoint
            and _field(value, "correction") is not True
            and _field(value, "non_counting") is not True
        ]
        if baseline_checkpoint is not None and len(baseline_matches) != 1:
            return {
                "baseline": None,
                "baseline_head": allocation.head,
                "results": [],
                "in_flight": 0,
                "error": "the allocation's pinned baseline checkpoint is missing or ambiguous",
            }
        baseline = (
            baseline_matches[0][1]
            if baseline_matches
            else next(
                (
                    value
                    for value in reversed(history)
                    if _field(value, "checkpoint", "checkpoint_id") in baseline_checkpoints
                    and _field(value, "completed") is True
                    and _field(value, "attributable") is True
                    and _field(value, "anchored") is True
                    and _field(value, "provisional") is not True
                    and _field(value, "correction") is not True
                    and _field(value, "non_counting") is not True
                ),
                None,
            )
        )
        baseline_head = _field(baseline, "head", "reviewed_head") if baseline is not None else allocation.head
        baseline_parent_head = _field(baseline, "parent_head", "parent_sha", "base_tip_sha") if baseline else None
        baseline_merge_base = _field(baseline, "merge_base") if baseline else None
        baseline_patch = _field(baseline, "patch_id", "patch_identity") if baseline else None
        if baseline_checkpoint is not None and not (
            _field(baseline, "pr") == allocation.pr
            and _field(baseline, "channel") in (None, allocation.channel)
            and _field(baseline, "completed") is True
            and _field(baseline, "attributable") is True
            and _field(baseline, "anchored") is True
            and _field(baseline, "provisional") is not True
            and type(_field(baseline, "accepted")) is int
            and _field(baseline, "accepted") >= 0
            and isinstance(baseline_head, str)
            and re.fullmatch(r"[0-9a-fA-F]{40}", baseline_head) is not None
            and _field(baseline, "child_head") == baseline_head
            and isinstance(_field(baseline, "parent_identity"), str)
            and bool(_field(baseline, "parent_identity"))
            and isinstance(baseline_parent_head, str)
            and re.fullmatch(r"[0-9a-fA-F]{40}", baseline_parent_head) is not None
            and isinstance(baseline_merge_base, str)
            and re.fullmatch(r"[0-9a-fA-F]{40}", baseline_merge_base) is not None
            and isinstance(baseline_patch, str)
            and bool(baseline_patch.strip())
        ):
            return {
                "baseline": None,
                "baseline_head": allocation.head,
                "results": [],
                "in_flight": 0,
                "error": "the bounded allocation baseline is not a completed attributable anchored review in this scope",
            }

        timeline_rows = [
            value
            for value in history
            if _field(value, "scope_timeline") is True and _field(value, "channel") == allocation.channel
        ]
        if len(timeline_rows) != 1 or not (
            _field(timeline_rows[0], "pr") == allocation.pr
            and _field(timeline_rows[0], "scope_timeline_complete") is True
            and _field(timeline_rows[0], "completed") is False
            and _field(timeline_rows[0], "attributable") is False
        ):
            return {
                "baseline": baseline,
                "results": [],
                "in_flight": 0,
                "error": "the bounded allocation scope-change timeline is unavailable or ambiguous",
            }

        scope_rows = [
            value
            for value in history
            if _field(value, "scope_changed") is True
            or _field(value, "scope_change_malformed") is True
            or _field(value, "kind") in ("scope_change", "scope_change_malformed")
        ]
        relevant_scope_rows = []
        for value in scope_rows:
            channel = _field(value, "channel")
            if channel not in (None, allocation.channel):
                continue
            if (
                channel != allocation.channel
                or _field(value, "pr") != allocation.pr
                or _field(value, "scope_changed") is not True
                or _field(value, "completed") is not False
                or _field(value, "attributable") is not False
            ):
                return {
                    "baseline": baseline,
                    "results": [],
                    "in_flight": 0,
                    "error": "scope-change evidence is incomplete or ambiguous; renewed human judgment is required",
                }
            relevant_scope_rows.append(value)

        if (
            relevant_scope_rows
            and baseline_checkpoint is None
            and any(
                _field(value, "checkpoint", "checkpoint_id") not in baseline_checkpoints
                for value in relevant_scope_rows
            )
        ):
            return {
                "baseline": baseline,
                "baseline_head": baseline_head,
                "results": [],
                "in_flight": 0,
                "error": "review scope changed after the allocation decision; renewed human judgment is required",
            }
        if relevant_scope_rows and baseline_checkpoint is not None:
            baseline_at = parse_timestamp(_field(baseline, "observed_at"))
            if baseline_at is None:
                return {
                    "baseline": baseline,
                    "results": [],
                    "in_flight": 0,
                    "error": "the bounded allocation baseline has no usable observation time for scope-change proof",
                }
            baseline_comment_id = _field(baseline, "comment_id")
            for value in relevant_scope_rows:
                created_at = _field(value, "created_at")
                updated_at = _field(value, "updated_at")
                observed_at = _field(value, "observed_at")
                effective_at = updated_at or created_at
                event_at = parse_timestamp(effective_at)
                if (
                    event_at is None
                    or observed_at != effective_at
                    or not isinstance(created_at, str)
                    or parse_timestamp(created_at) is None
                    or (
                        updated_at is not None
                        and (not isinstance(updated_at, str) or not updated_at or parse_timestamp(updated_at) is None)
                    )
                ):
                    return {
                        "baseline": baseline,
                        "results": [],
                        "in_flight": 0,
                        "error": "scope-change evidence has no usable effective time; renewed human judgment is required",
                    }
                if event_at > baseline_at:
                    return {
                        "baseline": baseline,
                        "results": [],
                        "in_flight": 0,
                        "error": "review scope changed after the bounded allocation baseline; renewed human judgment is required",
                    }
                if event_at == baseline_at:
                    if updated_at is not None and updated_at != created_at:
                        return {
                            "baseline": baseline,
                            "results": [],
                            "in_flight": 0,
                            "error": "scope-change timing is ambiguous at the bounded allocation baseline; renewed human judgment is required",
                        }
                    comment_id = _field(value, "comment_id")
                    if (
                        type(comment_id) is not int
                        or comment_id <= 0
                        or type(baseline_comment_id) is not int
                        or baseline_comment_id <= 0
                        or comment_id == baseline_comment_id
                    ):
                        return {
                            "baseline": baseline,
                            "results": [],
                            "in_flight": 0,
                            "error": "scope-change ordering is ambiguous at the bounded allocation baseline; renewed human judgment is required",
                        }
                    if comment_id > baseline_comment_id:
                        return {
                            "baseline": baseline,
                            "results": [],
                            "in_flight": 0,
                            "error": "review scope changed at the bounded allocation baseline; renewed human judgment is required",
                        }

        results: list[dict[str, Any]] = []
        in_flight_ids: dict[str, str] = {}
        seen_checkpoints: set[str] = set(baseline_checkpoints)
        for history_index, value in enumerate(history):
            checkpoint = _field(value, "checkpoint", "checkpoint_id")
            channel = _field(value, "channel")
            if channel not in (None, allocation.channel):
                continue
            active_response = (
                _field(value, "reason") == HOSTED_ACTIVE_RESPONSE_REASON
                and _field(value, "held") is True
                and type(_field(value, "pr")) is int
                and _field(value, "pr") == allocation.pr
                and type(_field(value, "trigger_id")) is int
                and _field(value, "trigger_id") > 0
            )
            trigger_checkpoint = (
                re.fullmatch(r"trigger:([1-9][0-9]*)", checkpoint) if isinstance(checkpoint, str) else None
            )
            anchored_active_trigger = (
                _field(value, "held") is True
                and _field(value, "rate_limited") is not True
                and _field(value, "terminal_ambiguous") is not True
                and type(_field(value, "pr")) is int
                and isinstance(_field(value, "anchor"), Mapping)
                and trigger_checkpoint is not None
            )
            if checkpoint in baseline_checkpoints and not (
                _field(value, "active_review") is True
                or _field(value, "active_reservation") is True
                or active_response
                or anchored_active_trigger
            ):
                continue
            if (
                _field(value, "active_review") is True
                or _field(value, "active_reservation") is True
                or active_response
                or anchored_active_trigger
            ):
                anchor = _field(value, "anchor")
                anchor = anchor if isinstance(anchor, Mapping) else {}
                active_head = _field(value, "head", "reviewed_head") or anchor.get("child_head")
                active_child_head = _field(value, "child_head") or anchor.get("child_head")
                active_parent_identity = _field(value, "parent_identity") or anchor.get("parent_identity")
                active_parent_head = _field(value, "parent_head", "parent_sha", "base_tip_sha") or anchor.get(
                    "parent_head"
                )
                active_merge_base = _field(value, "merge_base") or anchor.get("merge_base")
                active_patch_id = _field(value, "patch_id", "patch_identity") or anchor.get("patch_id")
                if not (
                    _field(value, "pr") == allocation.pr
                    and isinstance(active_parent_identity, str)
                    and bool(active_parent_identity)
                    and isinstance(active_head, str)
                    and re.fullmatch(r"[0-9a-fA-F]{40}", active_head) is not None
                    and active_child_head == active_head
                    and isinstance(active_parent_head, str)
                    and re.fullmatch(r"[0-9a-fA-F]{40}", active_parent_head) is not None
                    and isinstance(active_merge_base, str)
                    and re.fullmatch(r"[0-9a-fA-F]{40}", active_merge_base) is not None
                    and isinstance(active_patch_id, str)
                    and bool(active_patch_id.strip())
                ):
                    return {
                        "baseline": baseline,
                        "results": results,
                        "in_flight": len(in_flight_ids),
                        "error": "an in-flight review has an incomplete or changed stack anchor",
                    }
                if (
                    allocation.channel == "hosted"
                    and (
                        _field(value, "state") in {"awaiting_response", "ambiguous"}
                        or _field(value, "reason") == "no attributable terminal response"
                    )
                    and not ReviewController._hosted_awaiting_response_matches(
                        value,
                        allocation.pr,
                        AnchorFacts(
                            allocation.pr,
                            active_head,
                            active_parent_identity,
                            active_parent_head,
                            active_merge_base,
                            active_patch_id,
                        ),
                    )
                ):
                    return {
                        "baseline": baseline,
                        "results": results,
                        "in_flight": len(in_flight_ids),
                        "error": "an awaiting Hosted request lacks verified posted identity",
                    }
                identity = _field(value, "trigger_id", "run_id", "reservation_id", "attempt_id")
                if identity is None and trigger_checkpoint is not None:
                    identity = trigger_checkpoint.group(1)
                if isinstance(identity, (str, int)) and not isinstance(identity, bool):
                    request_id = str(identity)
                elif isinstance(checkpoint, str) and checkpoint:
                    request_id = checkpoint
                else:
                    return {
                        "baseline": baseline,
                        "results": results,
                        "in_flight": len(in_flight_ids) + 1,
                        "error": "an in-flight review has no immutable request identity",
                    }
                previous_head = in_flight_ids.get(request_id)
                if previous_head is not None and previous_head.casefold() != active_head.casefold():
                    return {
                        "baseline": baseline,
                        "results": results,
                        "in_flight": len(in_flight_ids),
                        "error": "one in-flight request has conflicting captured heads",
                    }
                in_flight_ids[request_id] = active_head
                continue
            if (
                _field(value, "correction") is True
                or _field(value, "non_counting") is True
                or _field(value, "provisional") is True
                or _field(value, "completed") is not True
                or _field(value, "attributable") is not True
                or _field(value, "anchored") is not True
                or any(
                    _field(value, flag) is True
                    for flag in (
                        "rate_limited",
                        "connection_failed",
                        "duplicate",
                        "partial",
                        "ambiguous",
                        "over_ceiling",
                    )
                )
            ):
                continue
            if _field(value, "pr") != allocation.pr:
                return {
                    "baseline": baseline,
                    "results": results,
                    "in_flight": len(in_flight_ids),
                    "error": "post-baseline review evidence is bound to another PR",
                }
            if (
                not isinstance(checkpoint, str)
                or not checkpoint.strip()
                or checkpoint.startswith(
                    ("trigger:", "trigger-uncheckpointed:", "pending-capture:", "review-threads:", "summary-actions:")
                )
            ):
                continue
            if checkpoint in seen_checkpoints:
                return {
                    "baseline": baseline,
                    "results": results,
                    "in_flight": len(in_flight_ids),
                    "error": "post-baseline completed reviews have an ambiguous duplicate checkpoint identity",
                }
            head = _field(value, "head", "reviewed_head")
            parent_head = _field(value, "parent_head", "parent_sha", "base_tip_sha")
            merge_base = _field(value, "merge_base")
            patch_id = _field(value, "patch_id", "patch_identity")
            accepted = _field(value, "accepted")
            if not (
                isinstance(head, str)
                and re.fullmatch(r"[0-9a-fA-F]{40}", head) is not None
                and _field(value, "child_head") == head
                and isinstance(_field(value, "parent_identity"), str)
                and bool(_field(value, "parent_identity"))
                and isinstance(parent_head, str)
                and re.fullmatch(r"[0-9a-fA-F]{40}", parent_head) is not None
                and isinstance(merge_base, str)
                and re.fullmatch(r"[0-9a-fA-F]{40}", merge_base) is not None
                and isinstance(patch_id, str)
                and bool(patch_id.strip())
                and type(accepted) is int
                and accepted >= 0
            ):
                return {
                    "baseline": baseline,
                    "results": results,
                    "in_flight": len(in_flight_ids),
                    "error": "post-baseline completed review has an incomplete or changed stack anchor",
                }
            seen_checkpoints.add(checkpoint)
            results.append(
                {
                    "checkpoint": checkpoint,
                    "head": head,
                    "accepted": accepted,
                    "history_index": history_index,
                }
            )

        return {
            "baseline": baseline,
            "baseline_head": baseline_head,
            "results": results,
            "in_flight": len(in_flight_ids),
            "in_flight_heads": list(in_flight_ids.values()),
            "error": None,
        }

    @staticmethod
    def _bounded_allocation_taper_baseline(
        allocation: ReviewAllocation,
        snapshot: Mapping[str, Any],
        history: Sequence[Any] | None = None,
    ) -> tuple[str, ...]:
        """Start an active streak at explicit reopen or after an accepted tranche result."""

        results = snapshot.get("results", ())
        last_accepted = max(
            (result.get("history_index", index) for index, result in enumerate(results) if result["accepted"] > 0),
            default=-1,
        )
        if last_accepted < 0:
            return allocation.baseline_checkpoints if allocation.reopens_taper else ()
        if history is not None:
            checkpoints = (_field(value, "checkpoint", "checkpoint_id") for value in history[: last_accepted + 1])
        else:
            checkpoints = (
                result["checkpoint"]
                for index, result in enumerate(results)
                if result.get("history_index", index) <= last_accepted
            )
        return tuple(
            dict.fromkeys(
                (
                    *allocation.baseline_checkpoints,
                    *(checkpoint for checkpoint in checkpoints if isinstance(checkpoint, str) and checkpoint),
                )
            )
        )

    def _bounded_allocation_progress(
        self,
        allocation: ReviewAllocation,
        history: Sequence[Any],
        current: AnchorFacts | None,
        reconciliation: stack.ReconciliationStatus,
        *,
        state: ReviewState,
        reconciliation_result: stack.Reconciliation | None,
        stop_audit_cache: dict[tuple[Any, ...], Mapping[str, Any]] | None,
        history_cache: dict[tuple[int, str], list[Any]] | None,
        bounded_evidence: Mapping[str, Any] | None = None,
    ) -> dict[str, Any]:
        cap = allocation.max_additional_completed
        minimum = allocation.min_additional_completed or 0
        snapshot = bounded_evidence or self._bounded_allocation_evidence(allocation, history)
        used = len(snapshot["results"])
        in_flight = snapshot["in_flight"]
        latest = snapshot["results"][-1] if snapshot["results"] else None
        baseline = snapshot["baseline"]
        normal_taper_complete = policy.taper_satisfied_for_state(
            state,
            allocation.channel,
            history,
            baseline_checkpoints=self._bounded_allocation_taper_baseline(
                allocation,
                snapshot,
                history,
            ),
        )

        def result(
            status: str,
            reason: str,
            *,
            control: str,
            details: str | None = None,
            finding_only_pending: bool = False,
            request_preparation_only: bool = False,
        ) -> dict[str, Any]:
            cap_stopped = status in {"CAP_AUDITED_STOP", "CAP_EXHAUSTED_PENDING"}
            return {
                "status": status,
                "reason": reason,
                "details": details,
                "finding_only_pending": finding_only_pending,
                "request_preparation_only": request_preparation_only,
                "promised_head": allocation.head,
                "checkpoint": latest["checkpoint"] if latest else None,
                "accepted": latest["accepted"] if latest else None,
                "handoff_head": allocation.handoff_head,
                "stop_basis": "human_cap" if cap_stopped else allocation.stop_basis,
                "baseline_checkpoint": allocation.baseline_checkpoint,
                "min_additional_completed": minimum,
                "max_additional_completed": cap,
                "completed_count": used,
                "taper_complete": normal_taper_complete,
                "historical_taper_complete": policy.taper_satisfied(
                    allocation.channel,
                    history,
                    policy.required_taper(state, allocation.channel, history),
                ),
                "reopens_taper": allocation.reopens_taper,
                "used": used,
                "cap": cap,
                "remaining": max(0, cap - used - in_flight) if cap is not None else None,
                "in_flight": in_flight,
                "selection_control": "maximum" if cap_stopped else control,
                "controlling_reason": reason,
            }

        if snapshot["error"] is not None:
            return result("INVALID", snapshot["error"], control="unresolved_work")
        if cap is not None and (used > cap or used + in_flight > cap):
            return result(
                "INVALID", "completed and in-flight reviews exceed the authorized cap", control="unresolved_work"
            )
        if in_flight:
            return result(
                "CAP_ACTIVE",
                "a posted review is still in flight within the allocated allowance",
                control="unresolved_work",
                details="the in-flight request does not count until it is complete and attributable",
            )

        # Exhausting the human maximum closes discovery independently of
        # finding clearance or topology; those obligations stay visible.
        if cap is not None and used >= cap:
            if current is None or reconciliation_result is None or not latest:
                return result(
                    "CAP_EXHAUSTED_PENDING",
                    "cap exhausted; findings pending",
                    control="unresolved_work",
                    details="complete current stack evidence is unavailable",
                )
            try:
                self._check_stop_evidence(
                    state,
                    allocation.pr,
                    policy.Channel(allocation.channel),
                    current,
                    reconciliation_result,
                    checkpoint_pin=latest["checkpoint"],
                    require_checkpoint_ancestry=False,
                    allow_cli_hosted_overlap=allocation.channel == policy.Channel.CLI.value,
                    allow_hosted_cli_overlap=allocation.channel == policy.Channel.HOSTED.value,
                    stop_audit_cache=stop_audit_cache,
                    history_cache=history_cache,
                )
            except (ControllerError, ValueError) as error:
                return result(
                    "CAP_EXHAUSTED_PENDING",
                    "cap exhausted; findings pending",
                    control="unresolved_work",
                    details=str(error),
                )
            return result(
                "CAP_AUDITED_STOP",
                "the maximum additional completed reviews is reached and review obligations are clear",
                control="maximum",
            )

        # Earlier accepted findings remain obligations after a human allowance;
        # recording a new baseline cannot turn them into request permission.
        pending_baseline = next(
            (
                value
                for value in history
                if _field(value, "checkpoint", "checkpoint_id") in allocation.baseline_checkpoints
                and self._accepted_findings_pending(value, current.child_head if current else allocation.head)
            ),
            None,
        )
        if pending_baseline is not None:
            finding_only_pending = False
            request_preparation_only = False
            stopping_checkpoint = latest["checkpoint"] if latest else _field(baseline, "checkpoint", "checkpoint_id")
            if (
                used >= minimum
                and normal_taper_complete
                and current is not None
                and reconciliation_result is not None
                and stopping_checkpoint
            ):
                try:
                    self._check_stop_evidence(
                        state,
                        allocation.pr,
                        policy.Channel(allocation.channel),
                        current,
                        reconciliation_result,
                        checkpoint_pin=stopping_checkpoint,
                        require_checkpoint_ancestry=False,
                        allow_cli_hosted_overlap=allocation.channel == policy.Channel.CLI.value,
                        allow_hosted_cli_overlap=allocation.channel == policy.Channel.HOSTED.value,
                        stop_audit_cache=stop_audit_cache,
                        history_cache=history_cache,
                    )
                except (ControllerError, ValueError) as error:
                    finding_only_pending = isinstance(error, _FindingOnlyStopEvidence)
                    request_preparation_only = isinstance(error, _RequestPreparationError)
            return result(
                "CAP_EXHAUSTED_PENDING" if cap is not None and used >= cap else "CAP_FINDINGS_PENDING",
                self._accepted_findings_pending_reason(pending_baseline),
                control="unresolved_work",
                finding_only_pending=finding_only_pending,
                request_preparation_only=request_preparation_only,
            )

        if any(completed["accepted"] > 0 for completed in snapshot["results"]):
            if current is None or reconciliation_result is None:
                cap_reached = cap is not None and used >= cap
                return result(
                    "CAP_EXHAUSTED_PENDING" if cap_reached else "CAP_FINDINGS_PENDING",
                    "cap exhausted; findings pending"
                    if cap_reached
                    else "accepted findings remain pending; current fix evidence is unavailable",
                    control="unresolved_work",
                    details="current fix evidence is unavailable" if cap_reached else None,
                )
            checkpoint_pin = latest["checkpoint"] if latest else _field(baseline, "checkpoint", "checkpoint_id")
            try:
                self._check_stop_evidence(
                    state,
                    allocation.pr,
                    policy.Channel(allocation.channel),
                    current,
                    reconciliation_result,
                    checkpoint_pin=checkpoint_pin,
                    require_checkpoint_ancestry=False,
                    allow_cli_hosted_overlap=allocation.channel == policy.Channel.CLI.value,
                    allow_hosted_cli_overlap=allocation.channel == policy.Channel.HOSTED.value,
                    stop_audit_cache=stop_audit_cache,
                    history_cache=history_cache,
                )
            except (ControllerError, ValueError) as error:
                cap_reached = cap is not None and used >= cap
                return result(
                    "CAP_EXHAUSTED_PENDING" if cap_reached else "CAP_FINDINGS_PENDING",
                    "cap exhausted; findings pending"
                    if cap_reached
                    else "accepted findings remain pending; their fixes are mandatory",
                    control="unresolved_work",
                    details=str(error),
                    finding_only_pending=not cap_reached and isinstance(error, _FindingOnlyStopEvidence),
                    request_preparation_only=not cap_reached and isinstance(error, _RequestPreparationError),
                )

        stopping_checkpoint = latest["checkpoint"] if latest else _field(baseline, "checkpoint", "checkpoint_id")
        if used < minimum:
            return result(
                "CAP_ACTIVE",
                f"minimum of {minimum} additional completed reviews is not met",
                control="minimum",
            )
        if normal_taper_complete:
            if current is None or reconciliation_result is None or not stopping_checkpoint:
                return result(
                    "CAP_TAPERED_PENDING",
                    "normal taper is complete but current finding and thread evidence is unavailable",
                    control="unresolved_work",
                )
            try:
                self._check_stop_evidence(
                    state,
                    allocation.pr,
                    policy.Channel(allocation.channel),
                    current,
                    reconciliation_result,
                    checkpoint_pin=stopping_checkpoint,
                    require_checkpoint_ancestry=False,
                    allow_cli_hosted_overlap=allocation.channel == policy.Channel.CLI.value,
                    allow_hosted_cli_overlap=allocation.channel == policy.Channel.HOSTED.value,
                    stop_audit_cache=stop_audit_cache,
                    history_cache=history_cache,
                )
            except (ControllerError, ValueError) as error:
                return result(
                    "CAP_TAPERED_PENDING",
                    "normal taper is complete but review obligations remain pending",
                    control="unresolved_work",
                    details=str(error),
                    finding_only_pending=isinstance(error, _FindingOnlyStopEvidence),
                    request_preparation_only=isinstance(error, _RequestPreparationError),
                )
            return result(
                "CAP_TAPERED",
                "normal channel taper is complete and the minimum allocation is met",
                control="taper",
            )
        if cap is None or used < cap:
            if allocation.reopens_taper and not normal_taper_complete:
                reason = "fresh taper restarted at this post-taper allocation; additional results may be requested"
            else:
                reason = (
                    "normal channel taper is not complete; more allocated results may be requested"
                    if cap is None
                    else "normal channel taper may finish before the maximum additional-review limit"
                )
            return result("CAP_ACTIVE", reason, control="taper")

    def _allocation_progress(
        self,
        allocation: ReviewAllocation,
        history: Sequence[Any],
        current: AnchorFacts | None,
        reconciliation: stack.ReconciliationStatus,
        *,
        state: ReviewState | None = None,
        reconciliation_result: stack.Reconciliation | None = None,
        stop_audit_cache: dict[tuple[Any, ...], Mapping[str, Any]] | None = None,
        history_cache: dict[tuple[int, str], list[Any]] | None = None,
        bounded_evidence: Mapping[str, Any] | None = None,
    ) -> dict[str, Any]:
        """Derive consumption from immutable evidence; never persist a live observation."""

        def result(
            status: str, reason: str, checkpoint: str | None = None, accepted: int | None = None
        ) -> dict[str, Any]:
            completed_count = 1 if checkpoint is not None else 0
            control = (
                "maximum"
                if status == "EXHAUSTED_PENDING"
                and reason == "review allocation exhausted; an explicit stop decision is required"
                else "unresolved_work"
                if status in {"EXHAUSTED_PENDING", "CAP_FINDINGS_PENDING"}
                else "allocation"
            )
            return {
                "status": status,
                "reason": reason,
                "promised_head": allocation.head,
                "checkpoint": checkpoint,
                "accepted": accepted,
                "handoff_head": allocation.handoff_head,
                "stop_basis": allocation.stop_basis or ("allocated" if allocation.handoff_checkpoint else None),
                "min_additional_completed": 1,
                "max_additional_completed": 1,
                "completed_count": completed_count,
                "used": completed_count,
                "cap": 1,
                "remaining": max(0, 1 - completed_count),
                "in_flight": 0,
                "selection_control": control,
                "controlling_reason": reason,
            }

        if allocation.stop_basis is not None:
            if state is None:
                return result("INVALID", "the persisted review-stop state is unavailable")
            return self._stop_progress(
                allocation,
                state,
                history,
                current,
                reconciliation,
                reconciliation_result,
                stop_audit_cache,
                history_cache,
            )

        if allocation.min_additional_completed is not None or allocation.max_additional_completed is not None:
            return self._bounded_allocation_progress(
                allocation,
                history,
                current,
                reconciliation,
                state=state or self._state(),
                reconciliation_result=reconciliation_result,
                stop_audit_cache=stop_audit_cache,
                history_cache=history_cache,
                bounded_evidence=bounded_evidence,
            )

        baseline = set(allocation.baseline_checkpoints)
        pending_baseline = next(
            (
                value
                for value in history
                if _field(value, "checkpoint", "checkpoint_id") in baseline
                and self._accepted_findings_pending(value, current.child_head if current else allocation.head)
            ),
            None,
        )
        subsequent = [
            item
            for item in history
            if _field(item, "correction") is not True and _field(item, "checkpoint", "checkpoint_id") not in baseline
        ]
        checkpoints = [_field(item, "checkpoint", "checkpoint_id") for item in subsequent]
        if any(not isinstance(value, str) or not value for value in checkpoints) or len(checkpoints) != len(
            set(checkpoints)
        ):
            return result("INVALID", "post-allocation evidence has missing or duplicate checkpoint identities")
        matching: list[Any] = []
        for item in subsequent:
            if not (
                _field(item, "completed") is True
                and _field(item, "attributable") is True
                and _field(item, "anchored") is True
                and _field(item, "provisional") is not True
                and not any(
                    _field(item, flag) is True
                    for flag in (
                        "rate_limited",
                        "connection_failed",
                        "duplicate",
                        "partial",
                        "ambiguous",
                        "over_ceiling",
                    )
                )
            ):
                continue
            if (
                _field(item, "pr") == allocation.pr
                and _field(item, "channel") in (None, allocation.channel)
                and isinstance(_field(item, "head", "reviewed_head"), str)
                and re.fullmatch(r"[0-9a-fA-F]{40}", _field(item, "head", "reviewed_head")) is not None
                and _field(item, "child_head") == _field(item, "head", "reviewed_head")
            ):
                matching.append(item)
        if len(matching) > 1:
            checkpoint = _field(matching[0], "checkpoint", "checkpoint_id")
            return result(
                "INVALID",
                "multiple completed results match the one-review allocation",
                checkpoint,
            )
        if not matching:
            if pending_baseline is not None:
                return result("CAP_FINDINGS_PENDING", self._accepted_findings_pending_reason(pending_baseline))
            return result("PROMISED", "waiting for one completed attributable review of this PR and channel")

        review = matching[0]
        checkpoint = _field(review, "checkpoint", "checkpoint_id")
        accepted = _field(review, "accepted")
        if type(accepted) is not int or accepted < 0:
            return result("INVALID", "completed review has an invalid accepted-finding count")
        if pending_baseline is not None:
            return result(
                "EXHAUSTED_PENDING", self._accepted_findings_pending_reason(pending_baseline), checkpoint, accepted
            )
        if current is None:
            return result("EXHAUSTED_PENDING", "current finding and fix evidence is unavailable", checkpoint, accepted)
        if any(
            _field(item, flag) is True
            for item in history
            for flag in ("held", "unstable", "unreconciled", "parent_moved", "over_ceiling", "rate_limited")
            if _field(item, "head", "reviewed_head") in (None, "", current.child_head)
            and not (
                allocation.stop_basis == "allocated"
                and _field(item, "terminal_ambiguous") is True
                and _field(item, "fingerprint") in allocation.retained_ambiguous_fingerprints
            )
        ):
            return result(
                "EXHAUSTED_PENDING", "current review, finding, or thread obligations remain", checkpoint, accepted
            )
        pending_source = next(
            (
                item
                for item in history
                if _field(item, "completed") is True
                and _field(item, "attributable") is True
                and _field(item, "provisional") is not True
                and _field(item, "correction") is not True
                and _field(item, "head", "reviewed_head") == current.child_head
                and type(_field(item, "accepted")) is int
                and _field(item, "accepted") > 0
            ),
            None,
        )
        if pending_source is not None:
            return result(
                "EXHAUSTED_PENDING",
                self._accepted_findings_pending_reason(pending_source),
                checkpoint,
                accepted,
            )
        if allocation.handoff_checkpoint is None:
            return result(
                "EXHAUSTED_PENDING",
                "review allocation exhausted; an explicit stop decision is required",
                checkpoint,
                accepted,
            )
        if allocation.handoff_checkpoint != checkpoint or allocation.handoff_head != current.child_head:
            return result(
                "INVALID", "recorded handoff no longer matches the consumed review and live head", checkpoint, accepted
            )
        return result(
            "HANDED_OFF",
            "review capacity handed off; merge readiness remains a separate judgment",
            checkpoint,
            accepted,
        )

    @staticmethod
    def _review_credit_projection(history: Sequence[Any]) -> tuple[Any, ...]:
        """Retain material count/taper inputs, ignoring non-counting audit additions."""

        fields = (
            ("pr", "number"),
            ("head", "reviewed_head"),
            ("checkpoint", "checkpoint_id"),
            ("patch_id", "patch_identity"),
            ("accepted",),
            ("anchored",),
            ("child_head",),
            ("parent_identity",),
            ("parent_head", "parent_sha", "base_tip_sha"),
            ("merge_base",),
            ("rate_limited",),
            ("connection_failed",),
            ("duplicate",),
            ("partial",),
            ("ambiguous",),
            ("over_ceiling",),
        )
        return tuple(
            tuple(_field(value, *aliases) for aliases in fields)
            for value in history
            if not (item := policy.Evidence.from_value(value)).correction
            and not item.non_counting
            and item.completed is True
            and item.attributable is True
            and item.provisional is False
        )

    def _allocation_views(
        self,
        state: ReviewState,
        live: Mapping[int, LivePullRequest],
        reconciliation: stack.Reconciliation,
        channel: policy.Channel,
        histories: Mapping[int, Sequence[Any]],
        *,
        pr_numbers: Sequence[int] | None = None,
        stop_audit_cache: dict[tuple[Any, ...], Mapping[str, Any]] | None = None,
        history_cache: dict[tuple[int, str], list[Any]] | None = None,
        bounded_evidence_cache: dict[tuple[int, str], Mapping[str, Any]] | None = None,
    ) -> dict[int, dict[str, Any]]:
        views: dict[int, dict[str, Any]] = {}
        selected_prs = state.ordered_prs if pr_numbers is None else pr_numbers
        for pr in selected_prs:
            allocation = state.allocations.get(f"{pr}:{channel.value}")
            if allocation is None or live[pr].merged:
                continue
            cache_key = (pr, channel.value)
            bounded_snapshot = bounded_evidence_cache.get(cache_key) if bounded_evidence_cache is not None else None
            if bounded_snapshot is None:
                bounded_snapshot = self._bounded_allocation_evidence(allocation, histories[pr])
                if bounded_evidence_cache is not None:
                    bounded_evidence_cache[cache_key] = bounded_snapshot
            current = self._reconciled_anchor(pr, live[pr], reconciliation)
            if current is None:
                try:
                    current = self._anchor(pr, live[pr], reconciliation.links[pr])
                except (ControllerError, OSError, subprocess.SubprocessError, ValueError):
                    current = None
            credit_projection = self._review_credit_projection(histories[pr])
            refreshed_history_cache = history_cache if history_cache is not None else {}
            view = self._allocation_progress(
                allocation,
                histories[pr],
                current,
                reconciliation.status_for(pr, channel.value),
                state=state,
                reconciliation_result=reconciliation,
                stop_audit_cache=stop_audit_cache,
                history_cache=refreshed_history_cache,
                bounded_evidence=bounded_snapshot,
            )
            refreshed_history = self._policy_history(
                state,
                pr,
                channel,
                reconciliation,
                history_cache=refreshed_history_cache,
            )
            # Scope markers carry no review credit, but still invalidate a
            # bounded allowance. Assess them again after the clearance audit.
            if refreshed_history != histories[pr]:
                bounded_snapshot = self._bounded_allocation_evidence(allocation, refreshed_history)
                if bounded_evidence_cache is not None:
                    bounded_evidence_cache[cache_key] = bounded_snapshot
            projection_current = credit_projection == self._review_credit_projection(refreshed_history)
            view["history_projection_current"] = projection_current
            if not projection_current:
                reason = "review count or taper evidence changed during audit; refresh status before release"
                view.update(
                    status="INVALID",
                    reason=reason,
                    details=reason,
                    finding_only_pending=False,
                    request_preparation_only=False,
                    selection_control="unresolved_work",
                )
            elif (
                allocation.stop_basis is None
                and (allocation.min_additional_completed is not None or allocation.max_additional_completed is not None)
                and bounded_snapshot["error"] is not None
            ):
                reason = bounded_snapshot["error"]
                view.update(
                    status="INVALID",
                    reason=reason,
                    details=reason,
                    finding_only_pending=False,
                    request_preparation_only=False,
                    selection_control="unresolved_work",
                )
            default_one_result = (
                allocation.min_additional_completed is None
                and allocation.max_additional_completed is None
                and allocation.stop_basis is None
            )
            minimum = 1 if default_one_result else allocation.min_additional_completed
            maximum = 1 if default_one_result else allocation.max_additional_completed
            completed_count = view.get("completed_count", view.get("used", 1 if view.get("checkpoint") else 0))
            view.setdefault("min_additional_completed", minimum)
            view.setdefault("minimum_additional_completed", minimum)
            view.setdefault("max_additional_completed", maximum)
            view.setdefault("maximum_additional_completed", maximum)
            view.setdefault("completed_count", completed_count)
            view.setdefault("controlling_reason", view.get("reason", "allocation"))
            view["minimum_additional_completed"] = minimum
            view["maximum_additional_completed"] = maximum
            view["completed_count"] = completed_count
            view["reopens_taper"] = allocation.reopens_taper
            view["historical_taper_complete"] = policy.taper_satisfied(
                channel,
                histories[pr],
                policy.required_taper(state, channel, histories[pr]),
            )
            taper_values = policy.fresh_taper_history(
                state,
                channel,
                histories[pr],
                baseline_checkpoints=self._bounded_allocation_taper_baseline(
                    allocation,
                    bounded_snapshot,
                    histories[pr],
                ),
            )
            view["taper_complete"] = policy.taper_satisfied(
                channel,
                taper_values,
                policy.required_taper(state, channel, taper_values),
            )
            if not projection_current:
                for key in ("completed_count", "used", "remaining", "taper_complete", "historical_taper_complete"):
                    view[key] = None
            views[pr] = view
        return views

    def _select_review_decision(
        self,
        state: ReviewState,
        channel: policy.Channel,
        live: Mapping[int, LivePullRequest],
        reconciliation: stack.Reconciliation,
        histories: Mapping[policy.Channel, Mapping[int, Sequence[Any]]],
        allocations: Mapping[int, Mapping[str, Any]],
        candidate_prs: Sequence[int],
        *,
        allocation_reopen_prs: Iterable[int] = (),
        bounded_evidence_cache: Mapping[tuple[int, str], Mapping[str, Any]] | None = None,
        stop_audit_cache: dict[tuple[Any, ...], Mapping[str, Any]] | None = None,
        history_cache: dict[tuple[int, str], list[Any]] | None = None,
    ) -> policy.ChannelDecision:
        """Apply the same authoritative selector to already fetched status evidence."""

        channel_allocations = allocations
        taper_history_by_pr: dict[int, Sequence[policy.Evidence]] = {}
        active_review_prs = {
            pr
            for pr in candidate_prs
            if any(
                _field(value, "active_review") is True or _field(value, "active_reservation") is True
                for value in histories[channel].get(pr, ())
            )
        }
        discovery_closed_pending_prs = {
            pr
            for pr, view in channel_allocations.items()
            if view.get("status") in {"CAP_TAPERED_PENDING", "CAP_FINDINGS_PENDING"}
            and (view.get("finding_only_pending") is True or view.get("request_preparation_only") is True)
            and view.get("taper_complete") is True
            and type(view.get("completed_count")) is int
            and type(view.get("min_additional_completed")) is int
            and view["completed_count"] >= view["min_additional_completed"]
        }
        allocation_holds = {
            pr: view.get("details") or view["reason"]
            for pr, view in channel_allocations.items()
            if (
                view["status"] in {"CAP_TAPERED_PENDING", "CAP_FINDINGS_PENDING"}
                and pr not in discovery_closed_pending_prs
            )
            or (view["status"] == "CAP_ACTIVE" and view.get("selection_control") == "unresolved_work")
            or view.get("history_projection_current") is False
        }
        finding_clearance_prs = set(discovery_closed_pending_prs)
        # Unallocated discovery-complete candidates with typed finding holds
        # or pending accepted-source proof need the same canonical audit.
        # Source uncertainty may exist even after every public thread resolves.
        for pr in candidate_prs:
            values = histories[channel].get(pr, ())
            if (
                live[pr].merged
                or f"{pr}:{channel.value}" in state.allocations
                or not any(
                    policy._is_finding_only_hold(policy.Evidence.from_value(value))
                    or self._accepted_findings_pending(value, live[pr].head)
                    for value in values
                )
                or not policy.taper_satisfied(
                    channel,
                    policy.fresh_taper_history(state, channel, values),
                    policy.required_taper(state, channel, values),
                )
            ):
                continue
            credit_projection = self._review_credit_projection(values)
            refreshed_history_cache = history_cache if history_cache is not None else {}
            try:
                current = self._anchor(pr, live[pr], reconciliation.links[pr])
                self._check_stop_evidence(
                    state,
                    pr,
                    channel,
                    current,
                    reconciliation,
                    checkpoint_pin=None,
                    require_checkpoint_ancestry=False,
                    stop_audit_cache=stop_audit_cache,
                    history_cache=refreshed_history_cache,
                )
            except (_FindingOnlyStopEvidence, _RequestPreparationError):
                pass
            except (ControllerError, OSError, subprocess.SubprocessError, ValueError) as error:
                allocation_holds[pr] = str(error)
                continue
            refreshed_history = self._policy_history(
                state,
                pr,
                channel,
                reconciliation,
                history_cache=refreshed_history_cache,
            )
            if credit_projection != self._review_credit_projection(refreshed_history):
                allocation_holds[pr] = (
                    "review count or taper evidence changed during audit; refresh status before release"
                )
            else:
                finding_clearance_prs.add(pr)
        for pr, view in channel_allocations.items():
            allocation = state.allocations.get(f"{pr}:{channel.value}")
            if allocation is None:
                continue
            bounded_snapshot = (
                bounded_evidence_cache.get((pr, channel.value)) if bounded_evidence_cache is not None else None
            )
            if bounded_snapshot is None:
                bounded_snapshot = ReviewController._bounded_allocation_evidence(
                    allocation,
                    histories[channel].get(pr, ()),
                )
            if view.get("reopens_taper") is True or any(
                result["accepted"] > 0 for result in bounded_snapshot["results"]
            ):
                taper_history_by_pr[pr] = policy.fresh_taper_history(
                    state,
                    channel,
                    histories[channel].get(pr, ()),
                    baseline_checkpoints=ReviewController._bounded_allocation_taper_baseline(
                        allocation,
                        bounded_snapshot,
                        histories[channel].get(pr, ()),
                    ),
                )
        decision = policy.select_review_target(
            state,
            channel,
            tuple(pr for pr in candidate_prs if not live[pr].merged),
            histories[channel],
            reconciliation_by_pr={pr: reconciliation.status_for(pr, channel.value) for pr in candidate_prs},
            handed_off_prs=(
                pr
                for pr, view in channel_allocations.items()
                if view["status"] in {"HANDED_OFF", "CAP_TAPERED"} and pr not in active_review_prs
            ),
            human_stopped_prs=(
                pr
                for pr, view in channel_allocations.items()
                if view["status"] in {"STOPPED", "CAP_AUDITED_STOP", "CAP_EXHAUSTED_PENDING"}
            ),
            exhausted_prs=(pr for pr, view in channel_allocations.items() if view["status"] == "EXHAUSTED_PENDING"),
            allocation_blocks={
                pr: view["reason"]
                for pr, view in channel_allocations.items()
                if view["status"] == "INVALID" and view.get("history_projection_current") is not False
            },
            allocation_holds=allocation_holds,
            allocation_reopen_prs=set(allocation_reopen_prs)
            | {pr for pr, view in channel_allocations.items() if _allocation_reopens_selection(view)},
            taper_history_by_pr=taper_history_by_pr,
            active_review_prs=active_review_prs,
            finding_clearance_prs=finding_clearance_prs,
        )
        if (
            channel == policy.Channel.CLI
            and decision.target is not None
            and decision.status == policy.ReviewStatus.HELD
            and any(
                _field(value, "reason") == HOSTED_CLI_OVERLAP_HOLD_REASON and _field(value, "held") is True
                for value in histories[channel].get(decision.target, ())
            )
        ):
            return dataclasses.replace(decision, reason=HOSTED_CLI_OVERLAP_HOLD_REASON)
        return decision

    def _reopen_hosted_cross_channel_judgment(
        self,
        state: ReviewState,
        channel: policy.Channel,
        decision: policy.ChannelDecision,
        live: Mapping[int, LivePullRequest],
        reconciliation: stack.Reconciliation,
        histories: Mapping[policy.Channel, Mapping[int, Sequence[Any]]],
        allocations: Mapping[int, Mapping[str, Any]],
        *,
        stop_audit_cache: dict[tuple[Any, ...], Mapping[str, Any]] | None = None,
        history_cache: dict[tuple[int, str], list[Any]] | None = None,
    ) -> policy.ChannelDecision:
        """Let an audited bounded Hosted allocation reopen its exact old/new-head mismatch."""

        if (
            channel != policy.Channel.HOSTED
            or decision.status not in {policy.ReviewStatus.JUDGMENT_REQUIRED, policy.ReviewStatus.READY}
            or decision.target is None
        ):
            return decision
        pr = decision.target
        allocation = state.allocations.get(f"{pr}:{policy.Channel.HOSTED.value}")
        view = allocations.get(pr, {})
        if (
            allocation is None
            or (allocation.min_additional_completed is None and allocation.max_additional_completed is None)
            or allocation.reopens_taper is not True
            or allocation.stop_basis is not None
            or allocation.handoff_checkpoint is not None
            or view.get("status") != "CAP_ACTIVE"
            or view.get("selection_control") not in {"minimum", "taper"}
            or (view.get("remaining") is not None and view.get("remaining", 0) <= 0)
        ):
            return decision

        def judgment_hold(reason: str) -> policy.ChannelDecision:
            return dataclasses.replace(
                decision,
                status=policy.ReviewStatus.JUDGMENT_REQUIRED,
                reason=reason,
            )

        item = live.get(pr)
        if item is None:
            return judgment_hold("cross-channel review evidence has no live pull-request identity")
        current = self._reconciled_anchor(pr, item, reconciliation)
        if current is None:
            return judgment_hold("cross-channel review evidence has no reconciled current stack anchor")
        hosted_latest = _latest_review(histories[policy.Channel.HOSTED].get(pr, ()))
        cli_latest = _latest_review(histories[policy.Channel.CLI].get(pr, ()))
        if hosted_latest is None or cli_latest is None:
            return decision
        hosted_head = _field(hosted_latest, "head", "reviewed_head")
        cli_head = _field(cli_latest, "head", "reviewed_head")
        if not (
            isinstance(hosted_head, str)
            and hosted_head.casefold() != current.child_head.casefold()
            and cli_head == current.child_head
        ):
            return decision

        if not (
            allocation.head.casefold() == current.child_head.casefold()
            and allocation.parent_identity == current.parent_identity
            and allocation.parent_head.casefold() == current.parent_head.casefold()
            and isinstance(allocation.merge_base, str)
            and allocation.merge_base.casefold() == current.merge_base.casefold()
            and allocation.patch_id == current.patch_id
            and reconciliation.status_for(pr) == stack.ReconciliationStatus.COHERENT
        ):
            return judgment_hold("bounded Hosted allocation no longer matches the current stack identity")

        allowed_channel_statuses = {
            stack.ReconciliationStatus.COHERENT,
            stack.ReconciliationStatus.PATCH_CHANGED,
            stack.ReconciliationStatus.EQUIVALENT_HISTORY,
        }
        if any(
            reconciliation.status_for(pr, selected.value) not in allowed_channel_statuses
            for selected in (policy.Channel.HOSTED, policy.Channel.CLI)
        ):
            return judgment_hold("cross-channel review evidence is not anchored to the current stack identity")

        def shares_current_parent(value: Any) -> bool:
            parent_head = _field(value, "parent_head", "parent_sha", "base_tip_sha")
            merge_base = _field(value, "merge_base")
            return (
                _field(value, "anchored") is True
                and _field(value, "child_head") == _field(value, "head", "reviewed_head")
                and _field(value, "parent_identity") == current.parent_identity
                and isinstance(parent_head, str)
                and parent_head.casefold() == current.parent_head.casefold()
                and isinstance(merge_base, str)
                and merge_base.casefold() == current.merge_base.casefold()
            )

        if not (
            shares_current_parent(hosted_latest)
            and shares_current_parent(cli_latest)
            and _field(cli_latest, "patch_id") == current.patch_id
            and type(_field(hosted_latest, "raw", "raw_found")) is int
            and _field(hosted_latest, "raw", "raw_found") == 0
            and type(_field(hosted_latest, "accepted")) is int
            and _field(hosted_latest, "accepted") == 0
        ):
            return judgment_hold("bounded Hosted allocation cannot reopen a different cross-channel review identity")

        try:
            self._check_stop_evidence(
                state,
                pr,
                policy.Channel.HOSTED,
                current,
                reconciliation,
                checkpoint_pin=None,
                stop_audit_cache=stop_audit_cache,
                history_cache=history_cache,
            )
        except (ControllerError, ValueError, OSError, subprocess.SubprocessError) as error:
            return dataclasses.replace(
                decision,
                status=policy.ReviewStatus.HELD,
                reason=f"cross-channel review evidence could not be verified: {error}",
            )
        return dataclasses.replace(
            decision,
            status=policy.ReviewStatus.READY,
            reason="explicit bounded Hosted allocation reopens the audited cross-channel head mismatch",
        )

    @staticmethod
    def _hosted_head_mismatch_is_proven_cli_descendant(
        pr: int,
        histories: Mapping[policy.Channel, Mapping[int, Sequence[Any]]],
        reconciliation: stack.Reconciliation,
    ) -> bool:
        """Allow proven CLI taper to coexist with Hosted's current-head review."""

        cli_history = histories[policy.Channel.CLI].get(pr, ())
        latest_cli = _latest_review(cli_history)
        if latest_cli is None or _field(latest_cli, "current_candidate_descendant_proven") is not True:
            return False
        for channel in (policy.Channel.HOSTED, policy.Channel.CLI):
            for value in histories[channel].get(pr, ()):
                checkpoint = _field(value, "checkpoint", "checkpoint_id")
                if (
                    _field(value, "active_review") is True
                    or _field(value, "active_reservation") is True
                    or (_field(value, "held") is True and _field(value, "reason") == HOSTED_ACTIVE_RESPONSE_REASON)
                    or (
                        _field(value, "held") is True
                        and isinstance(checkpoint, str)
                        and checkpoint.startswith("trigger:")
                        and _field(value, "rate_limited") is not True
                        and _field(value, "terminal_ambiguous") is not True
                    )
                ):
                    return False
        allowed_statuses = {
            stack.ReconciliationStatus.COHERENT,
            stack.ReconciliationStatus.EQUIVALENT_HISTORY,
            stack.ReconciliationStatus.PATCH_CHANGED,
        }
        return all(
            reconciliation.status_for(pr, channel.value) in allowed_statuses
            for channel in (policy.Channel.HOSTED, policy.Channel.CLI)
        )

    def _refreshed_status_histories(
        self,
        state,
        live,
        reconciliation,
        candidate_prs,
        history_cache,
    ):
        # Clearance audits refresh both cache entries. Reproject that local
        # snapshot for selection and row rendering without another read.
        current_histories = {
            selected: {
                pr: self._policy_history(
                    state,
                    pr,
                    selected,
                    reconciliation,
                    history_cache=history_cache,
                )
                for pr in candidate_prs
            }
            for selected in (policy.Channel.HOSTED, policy.Channel.CLI)
        }
        current_histories[policy.Channel.CLI] = {
            pr: self._project_cli_streak_lineage(
                self._project_cli_hosted_reservations(
                    current_histories[policy.Channel.CLI][pr],
                    current_histories[policy.Channel.HOSTED][pr],
                    pr,
                    live[pr].head,
                    self._reconciled_anchor(pr, live[pr], reconciliation),
                ),
                pr,
                state,
                self._reconciled_anchor(pr, live[pr], reconciliation),
            )
            for pr in candidate_prs
        }
        return current_histories

    def _refreshed_status_allocations(
        self,
        state,
        live,
        reconciliation,
        histories,
        allocations,
        candidate_prs,
        bounded_evidence_cache,
        channel,
        current_histories,
    ):
        current_allocations = dict(allocations[channel])
        for pr in candidate_prs:
            fresh_history = current_histories[channel][pr]
            allocation = state.allocations.get(f"{pr}:{channel.value}")
            reason = None
            if (
                allocation is not None
                and allocation.stop_basis is None
                and (allocation.min_additional_completed is not None or allocation.max_additional_completed is not None)
            ):
                snapshot = bounded_evidence_cache.get((pr, channel.value))
                if snapshot is None or fresh_history != histories[channel].get(pr, ()):
                    snapshot = self._bounded_allocation_evidence(allocation, fresh_history)
                if fresh_history != histories[channel].get(pr, ()):
                    reason = snapshot["error"]
                    if reason is None and snapshot["in_flight"]:
                        # The canonical in-flight branch returns before a
                        # clearance audit; refresh activity counters locally.
                        current_allocations[pr] = dict(
                            current_allocations.get(pr, {}),
                            **self._allocation_progress(
                                allocation,
                                fresh_history,
                                self._reconciled_anchor(pr, live[pr], reconciliation),
                                reconciliation.status_for(pr, channel.value),
                                state=state,
                                reconciliation_result=reconciliation,
                                bounded_evidence=snapshot,
                            ),
                        )
            if self._review_credit_projection(histories[channel].get(pr, ())) != self._review_credit_projection(
                fresh_history
            ):
                reason = "review count or taper evidence changed during audit; refresh status before release"
            if reason is not None:
                current_allocations[pr] = dict(
                    current_allocations.get(pr, {}),
                    status="INVALID",
                    reason=reason,
                    details=reason,
                    history_projection_current=False,
                    finding_only_pending=False,
                    request_preparation_only=False,
                    selection_control="unresolved_work",
                )
                for key in (
                    "completed_count",
                    "used",
                    "remaining",
                    "in_flight",
                    "taper_complete",
                    "historical_taper_complete",
                ):
                    current_allocations[pr][key] = None
        return current_allocations

    def _status_review_targets(
        self,
        state: ReviewState,
        live: Mapping[int, LivePullRequest],
        reconciliation: stack.Reconciliation,
        histories: dict[policy.Channel, dict[int, list[Any]]],
        allocations: dict[policy.Channel, dict[int, dict[str, Any]]],
        candidate_prs: Sequence[int],
        *,
        selection_complete: bool,
        bounded_evidence_cache: Mapping[tuple[int, str], Mapping[str, Any]],
        stop_audit_cache: dict[tuple[Any, ...], Mapping[str, Any]],
        history_cache: dict[tuple[int, str], list[Any]],
    ) -> dict[str, dict[str, Any]]:
        result: dict[str, dict[str, Any]] = {}

        def refreshed_histories():
            return self._refreshed_status_histories(
                state,
                live,
                reconciliation,
                candidate_prs,
                history_cache,
            )

        def refreshed_allocations(channel, current_histories):
            return self._refreshed_status_allocations(
                state,
                live,
                reconciliation,
                histories,
                allocations,
                candidate_prs,
                bounded_evidence_cache,
                channel,
                current_histories,
            )

        for channel in (policy.Channel.HOSTED, policy.Channel.CLI):
            current_histories = refreshed_histories()
            current_allocations = refreshed_allocations(channel, current_histories)
            decision = self._select_review_decision(
                state,
                channel,
                live,
                reconciliation,
                current_histories,
                current_allocations,
                candidate_prs,
                bounded_evidence_cache=bounded_evidence_cache,
                stop_audit_cache=stop_audit_cache,
                history_cache=history_cache,
            )
            decision = self._reopen_hosted_cross_channel_judgment(
                state,
                channel,
                decision,
                live,
                reconciliation,
                current_histories,
                current_allocations,
                stop_audit_cache=stop_audit_cache,
                history_cache=history_cache,
            )
            if (decision.target is None or decision.deferred_terminal) and not selection_complete:
                result[channel.value] = {
                    "channel": channel.value,
                    "pr": None,
                    "status": "UNKNOWN",
                    "reason": "review target selection continues beyond the deeply checked status window",
                    "provisional": False,
                }
                continue
            value = decision.to_dict()
            if decision.target is not None:
                is_draft = live[decision.target].is_draft
                value["is_draft"] = is_draft
                value["draft_notice"] = DRAFT_PR_NOTICE if is_draft else None
            value["pr"] = value.pop("target")
            result[channel.value] = value
        # Both selectors may refresh either channel. Publish one final local
        # snapshot to the row renderer, retaining invalidated projections.
        final_histories = refreshed_histories()
        for channel in (policy.Channel.HOSTED, policy.Channel.CLI):
            final_allocations = refreshed_allocations(channel, final_histories)
            allocations[channel].update(
                {pr: view for pr, view in final_allocations.items() if pr in allocations[channel]}
            )
            for pr in candidate_prs:
                if live[pr].merged:
                    continue
                view = final_allocations.get(pr, {})
                consumed = stop_audit_cache.get(("clearance", pr, channel.value))
                latest = stop_audit_cache.get(("latest", pr))
                clearance_changed = (
                    consumed is not None
                    and latest is not None
                    and consumed["evidence"] != latest["evidence"]
                    and view.get("status") not in {"STOPPED", "CAP_AUDITED_STOP", "CAP_EXHAUSTED_PENDING", "HANDED_OFF"}
                )
                clearance_error = None
                if clearance_changed:
                    try:
                        current = self._reconciled_anchor(pr, live[pr], reconciliation)
                        if current is None:
                            current = self._anchor(pr, live[pr], reconciliation.links[pr])
                        final_audit = dict(latest["evidence"])
                        final_audit.setdefault(
                            "channel_histories",
                            {
                                selected.value: history_cache.get((pr, selected.value), [])
                                for selected in (policy.Channel.HOSTED, policy.Channel.CLI)
                            },
                        )
                        self._check_stop_evidence(
                            state,
                            pr,
                            channel,
                            current,
                            reconciliation,
                            checkpoint_pin=None,
                            require_checkpoint_ancestry=False,
                            history_cache=history_cache,
                            audit_override=final_audit,
                            **consumed.get("overlap", {}),
                        )
                    except (_FindingOnlyStopEvidence, _RequestPreparationError):
                        pass
                    except (ControllerError, OSError, subprocess.SubprocessError, ValueError) as error:
                        clearance_error = str(error)
                clearance_changed = clearance_error is not None
                active = any(
                    _field(value, "active_review") is True or _field(value, "active_reservation") is True
                    for value in final_histories[channel][pr]
                )
                if not clearance_changed and not active and view.get("history_projection_current") is not False:
                    continue
                target = result[channel.value].get("pr")
                if target is not None and candidate_prs.index(target) < candidate_prs.index(pr):
                    break
                reason = (
                    clearance_error
                    if clearance_changed
                    else f"{pr} has an active review or reservation in a review channel"
                    if active
                    else view["reason"]
                )
                if clearance_changed and pr in allocations[channel]:
                    allocations[channel][pr] = dict(
                        allocations[channel][pr],
                        status="INVALID",
                        reason=reason,
                        details=reason,
                        finding_only_pending=False,
                        request_preparation_only=False,
                        selection_control="unresolved_work",
                    )
                held = policy.ChannelDecision(channel, pr, policy.ReviewStatus.HELD, reason).to_dict()
                held["pr"] = held.pop("target")
                held["is_draft"] = live[pr].is_draft
                held["draft_notice"] = DRAFT_PR_NOTICE if live[pr].is_draft else None
                result[channel.value] = held
                break
            histories[channel].update(final_histories[channel])
        return result

    def _target(
        self,
        channel: policy.Channel | str,
        expected_pr: int | None = None,
        *,
        allow_cli_prefix_optimization: bool = True,
        allow_completed_allocation: bool = False,
    ) -> Target:
        selected = policy.Channel(channel)
        state = self._state()
        if not state.ordered_prs:
            raise ControllerError("review stack is empty")
        budget = github.active_hosted_preflight_budget()
        use_current_batch = budget is not None and callable(
            getattr(self._require_github(), "batch_pull_requests", None)
        )
        scoped_expected_cli = (
            allow_cli_prefix_optimization
            and use_current_batch
            and selected == policy.Channel.CLI
            and expected_pr in state.ordered_prs
            and callable(getattr(self._evidence_provider, "active_review_targets", None))
            and callable(getattr(self._evidence_provider, "repository_cli_lock_status", None))
            and callable(getattr(self._evidence_provider, "request_history", None))
        )
        selection_prs = state.ordered_prs
        selection_state = state
        expected_index: int | None = None
        if scoped_expected_cli:
            expected_index = state.ordered_prs.index(expected_pr)
            selection_prs = state.ordered_prs[: expected_index + 1]
            selection_state = dataclasses.replace(state, ordered_prs=selection_prs)
        history_cache: dict[tuple[int, str], list[Any]] = {}
        stop_audit_cache: dict[tuple[Any, ...], Mapping[str, Any]] = {}
        bounded_evidence_cache: dict[tuple[int, str], Mapping[str, Any]] = {}

        def cache_request_history(pr: int, channel: str) -> None:
            if (pr, channel) in history_cache:
                return
            if budget is not None:
                budget.set_phase(f"target_request_history_pr_{pr}_{channel}", total=1)
            request_history = getattr(self._evidence_provider, "request_history", None)
            if callable(request_history):
                values = list(request_history(pr, channel))
            else:
                values = [
                    value
                    for value in _history(self._evidence_provider, pr, policy.Channel(channel))
                    if _field(value, "active_review") is True
                    or _field(value, "active_reservation") is True
                    or _field(value, "rate_limited") is True
                    or (
                        str(_field(value, "checkpoint") or "").startswith("trigger:")
                        and _field(value, "terminal") is not True
                    )
                ]
            history_cache[(pr, channel)] = values
            if budget is not None:
                budget.set_completed(1)

        for allocation in state.allocations.values():
            if allocation.pr in selection_prs and allocation.stop_basis is not None:
                cache_request_history(allocation.pr, allocation.channel)
        live_identities = None
        if use_current_batch:
            identity_batch_count = (len(state.ordered_prs) + 24) // 25
            if budget is not None:
                budget.set_phase("target_identity_batch", total=identity_batch_count)
            live_identities = self._require_github().batch_pull_requests(state.ordered_prs)
            if budget is not None:
                budget.set_completed(identity_batch_count)
            if (
                not isinstance(live_identities, Mapping)
                or set(live_identities) != set(state.ordered_prs)
                or any(not isinstance(live_identities[pr], Mapping) for pr in state.ordered_prs)
                or any(
                    type(live_identities[pr].get("number")) is not int
                    or live_identities[pr]["number"] != pr
                    or type(live_identities[pr].get("changedFiles")) is not int
                    or live_identities[pr]["changedFiles"] < 0
                    for pr in state.ordered_prs
                )
            ):
                raise ControllerError(
                    f"fresh live identity is incomplete for the configured {selected.value.upper()} review stack"
                )
            if scoped_expected_cli:
                validated_identities: dict[int, LivePullRequest] = {}
                for pr in state.ordered_prs:
                    identity = live_identities[pr]
                    if type(identity.get("isDraft")) is not bool or any(
                        not isinstance(identity.get(field), str) or not identity[field]
                        for field in (
                            "state",
                            "baseRefName",
                            "baseRefOid",
                            "headRefName",
                            "headRefOid",
                            "mergeable",
                        )
                    ):
                        raise ControllerError(f"fresh live identity is malformed for PR #{pr}")
                    item = _live(identity, pr)
                    if item.state not in {"OPEN", "CLOSED", "MERGED"} or item.mergeable not in {
                        "MERGEABLE",
                        "CONFLICTING",
                        "UNKNOWN",
                    }:
                        raise ControllerError(f"fresh live identity has an unknown lifecycle for PR #{pr}")
                    validated_identities[pr] = item
                for pr, item in validated_identities.items():
                    problem = self._head_repository_problem(item)
                    if problem:
                        raise ControllerError(f"PR #{pr} {problem}")
                active_candidates = tuple(
                    pr for pr, item in validated_identities.items() if not item.merged and item.state == "OPEN"
                )
                if budget is not None:
                    budget.set_phase("target_repository_cli_lock", total=1)
                try:
                    lock_status = self._evidence_provider.repository_cli_lock_status()
                except github.HostedPreflightDeadlineExceeded:
                    raise
                except Exception as error:
                    raise ControllerError("repository-wide CLI review activity cannot be verified") from error
                if lock_status != "clear":
                    detail = "active" if lock_status == "held" else "unknown or ambiguous"
                    raise ControllerError(f"repository-wide CLI review activity is {detail}")
                if budget is not None:
                    budget.set_completed(1)
                discover_active = getattr(self._evidence_provider, "active_review_targets", None)
                if budget is not None:
                    budget.set_phase("target_active_candidate_scan", total=1)
                if scoped_expected_cli:
                    try:
                        found_candidates = discover_active(active_candidates, live_identities)
                    except github.HostedPreflightDeadlineExceeded:
                        raise
                    except (ControllerError, OSError, RuntimeError, TypeError, ValueError, KeyError):
                        # Candidate discovery can fail because of Hosted or
                        # historical evidence. Keep the former full-stack path
                        # instead of treating that uncertainty as CLI activity.
                        scoped_expected_cli = False
                    else:
                        if not isinstance(found_candidates, (set, frozenset)) or any(
                            isinstance(pr, bool) or not isinstance(pr, int) or pr not in active_candidates
                            for pr in found_candidates
                        ):
                            scoped_expected_cli = False
                        else:
                            for pr in sorted(found_candidates):
                                key = (pr, policy.Channel.CLI.value)
                                if key in history_cache:
                                    current_cli = history_cache[key]
                                else:
                                    if budget is not None:
                                        budget.set_phase(f"target_cli_activity_pr_{pr}", total=1)
                                    try:
                                        current_cli = list(self._evidence_provider.request_history(pr, "cli"))
                                    except github.HostedPreflightDeadlineExceeded:
                                        raise
                                    except Exception as error:
                                        raise ControllerError(
                                            f"current CLI activity for PR #{pr} cannot be verified"
                                        ) from error
                                    if budget is not None:
                                        budget.set_completed(1)
                                if not isinstance(current_cli, Sequence) or isinstance(current_cli, (str, bytes)):
                                    raise ControllerError(f"current CLI activity for PR #{pr} is ambiguous")
                                for item in current_cli:
                                    if not isinstance(item, Mapping):
                                        raise ControllerError(f"current CLI activity for PR #{pr} is ambiguous")
                                    for field in ("active_review", "active_reservation"):
                                        flag = _field(item, field)
                                        if flag is not None and type(flag) is not bool:
                                            raise ControllerError(f"current CLI activity for PR #{pr} is ambiguous")
                                    if (
                                        _field(item, "active_review") is True
                                        or _field(item, "active_reservation") is True
                                    ):
                                        raise ControllerError(
                                            f"repository-wide CLI review activity is active or ambiguous on PR #{pr}"
                                        )
                    if budget is not None:
                        budget.set_phase("target_active_candidate_scan_complete", total=1)
                        budget.set_completed(1)
                if not scoped_expected_cli:
                    selection_prs = state.ordered_prs
                    selection_state = state
                    expected_index = None
                    for allocation in state.allocations.values():
                        if allocation.pr in selection_prs and allocation.stop_basis is not None:
                            cache_request_history(allocation.pr, allocation.channel)
        if budget is not None:
            budget.set_phase("target_remote_heads", total=1)
        selection_remote_heads = self.git.remote_heads()
        if budget is not None:
            budget.set_completed(1)
            budget.set_phase("target_stack_reconciliation", total=1)
        live, reconciliation = self._reconciliation(
            selection_state,
            live_identities=live_identities,
            refresh_prs=set() if live_identities is not None else None,
            history_cache=history_cache,
            remote_head_snapshot=selection_remote_heads,
            prefetch_complete_history=selected == policy.Channel.HOSTED and budget is not None,
        )
        if budget is not None:
            budget.set_phase("target_stack_reconciliation_complete", total=1)
            budget.set_completed(1)
        for pr in selection_prs:
            problem = self._head_repository_problem(live[pr])
            if problem:
                raise ControllerError(f"PR #{pr} {problem}")
            if live[pr].merged:
                # Merged PRs cannot supply selection or taper. Current reservations
                # and repository cooldowns still belong to request admission.
                for channel_name in ("hosted", "cli"):
                    cache_request_history(pr, channel_name)
        other = policy.Channel.CLI if selected == policy.Channel.HOSTED else policy.Channel.HOSTED
        policy_histories: dict[policy.Channel, dict[int, list[Any]]] = {selected: {}, other: {}}
        if budget is not None:
            budget.set_phase("target_policy_history_projection", total=2 * len(selection_prs))
        completed_history_items = 0
        for pr in selection_prs:
            for channel_name in (selected, other):
                policy_histories[channel_name][pr] = self._policy_history(
                    selection_state,
                    pr,
                    channel_name,
                    reconciliation,
                    history_cache=history_cache,
                )
                completed_history_items += 1
                if budget is not None:
                    budget.set_completed(completed_history_items)
        history = policy_histories[selected]
        other_history = policy_histories[other]
        cli_history = history if selected == policy.Channel.CLI else other_history
        hosted_history = other_history if selected == policy.Channel.CLI else history
        projected_cli_history = {
            pr: self._project_cli_streak_lineage(
                self._project_cli_hosted_reservations(
                    cli_history[pr],
                    hosted_history[pr],
                    pr,
                    live[pr].head,
                    self._reconciled_anchor(pr, live[pr], reconciliation),
                ),
                pr,
                selection_state,
                self._reconciled_anchor(pr, live[pr], reconciliation),
            )
            for pr in selection_prs
        }
        if selected == policy.Channel.CLI:
            history = projected_cli_history
        else:
            other_history = projected_cli_history
        allocations = self._allocation_views(
            selection_state,
            live,
            reconciliation,
            selected,
            history,
            stop_audit_cache=stop_audit_cache,
            history_cache=history_cache,
            bounded_evidence_cache=bounded_evidence_cache,
        )
        candidate_histories = {selected: history, other: other_history}
        if budget is not None:
            budget.set_phase("target_policy_selection", total=1)
        decision = self._select_review_decision(
            selection_state,
            selected,
            live,
            reconciliation,
            candidate_histories,
            allocations,
            selection_prs,
            allocation_reopen_prs=((expected_pr,) if allow_completed_allocation and expected_pr is not None else ()),
            bounded_evidence_cache=bounded_evidence_cache,
            stop_audit_cache=stop_audit_cache,
            history_cache=history_cache,
        )
        if budget is not None:
            budget.set_completed(1)
        decision = self._reopen_hosted_cross_channel_judgment(
            selection_state,
            selected,
            decision,
            live,
            reconciliation,
            candidate_histories,
            allocations,
            stop_audit_cache=stop_audit_cache,
            history_cache=history_cache,
        )
        if selected == policy.Channel.HOSTED and not allow_completed_allocation and decision.target is not None:
            for number, values in history.items():
                if any(_field(value, "rate_limited") is True for value in values):
                    decision = dataclasses.replace(
                        decision,
                        status=policy.ReviewStatus.RATE_LIMITED,
                        reason=f"Hosted repository cooldown remains active on PR #{number}",
                    )
                    break
        completed_allocation_override = False
        if decision.target is None:
            if not (
                allow_completed_allocation
                and decision.status == policy.ReviewStatus.COMPLETE
                and expected_pr in state.ordered_prs
                and not live[expected_pr].merged
            ):
                raise ControllerError(decision.reason)
            pr = expected_pr
            completed_allocation_override = True
        else:
            pr = decision.target
        if expected_pr is not None and expected_pr != pr:
            raise WrongStackTarget(f"expected PR #{expected_pr}, but selected PR #{pr}")
        item = live[pr]
        link = reconciliation.links[pr]
        anchor = self._reconciled_anchor(pr, item, reconciliation)
        if anchor is None:
            anchor = self._anchor(pr, item, link)
        target_reconciliation = reconciliation.status_for(pr, selected.value)
        candidate_warnings = ()
        if target_reconciliation in {
            stack.ReconciliationStatus.PARENT_MOVED,
            stack.ReconciliationStatus.UNRECONCILED,
        }:
            warning = f"stack reconciliation is {target_reconciliation.value}"
            detail = reconciliation.reasons.get(pr)
            candidate_warnings = (warning, detail) if detail and detail != warning else (warning,)
        test_merge = self._default_test_merge_proofs.get(pr)
        if test_merge is not None and (
            test_merge[0].casefold() != item.base_tip.casefold() or test_merge[1].casefold() != item.head.casefold()
        ):
            test_merge = None
        selected_base_ref_tip = selection_remote_heads.get(item.base_ref)
        if (
            not isinstance(selected_base_ref_tip, str)
            or re.fullmatch(r"[0-9a-fA-F]{40}", selected_base_ref_tip) is None
        ):
            selected_base_ref_tip = None
        else:
            selected_base_ref_tip = selected_base_ref_tip.casefold()
        selected_target = ReviewTarget(
            item.runner_snapshot(),
            EffectiveParent(link.parent_ref, link.parent_head, link.parent_pr),
            reconciled=target_reconciliation
            in {
                stack.ReconciliationStatus.COHERENT,
                stack.ReconciliationStatus.PATCH_CHANGED,
                stack.ReconciliationStatus.EQUIVALENT_HISTORY,
            },
            ancestor_links_valid=target_reconciliation
            in {
                stack.ReconciliationStatus.COHERENT,
                stack.ReconciliationStatus.PATCH_CHANGED,
                stack.ReconciliationStatus.EQUIVALENT_HISTORY,
            },
            patch_identity=anchor.patch_id,
            merge_base=anchor.merge_base,
            repository=self.repository,
            default_base_front=(
                link.parent_pr is None
                and link.parent_ref == self.default_base_ref
                and item.base_ref == self.default_base_ref
                and item.base_tip.casefold() == link.parent_head.casefold()
                and item.mergeable == "MERGEABLE"
                and test_merge is not None
            ),
            default_test_merge_base_sha=test_merge[0] if test_merge is not None else "",
            default_test_merge_head_sha=test_merge[1] if test_merge is not None else "",
            default_test_merge_tree_sha=test_merge[2] if test_merge is not None else "",
            candidate_warnings=candidate_warnings,
            selected_base_ref_tip=selected_base_ref_tip,
        )
        return Target(
            selected,
            pr,
            policy.ReviewStatus.READY if completed_allocation_override else decision.status,
            "explicit allocation reopens review selection after completion"
            if completed_allocation_override
            else decision.reason,
            selected_target,
            anchor,
            decision.provisional,
            self._selection_inputs(state, pr, selected.value),
            item.is_draft,
        )

    @staticmethod
    def _ensure_runnable(selected: Target, *, force: bool = False, require_force_for_warnings: bool = False) -> None:
        blocked = {
            policy.ReviewStatus.COMPLETE,
            policy.ReviewStatus.RATE_LIMITED,
            policy.ReviewStatus.HELD,
            policy.ReviewStatus.UNSTABLE,
            policy.ReviewStatus.OVER_CEILING,
            policy.ReviewStatus.JUDGMENT_REQUIRED,
            policy.ReviewStatus.PROVISIONAL,
            policy.ReviewStatus.ALLOCATION_EXHAUSTED,
        }
        if selected.status in blocked:
            if selected.status == policy.ReviewStatus.HELD:
                raise ControllerError(f"{selected.channel.value} review cannot run: {selected.reason}")
            raise ControllerError(f"{selected.channel.value} review cannot run: {selected.status.value}")
        warning_statuses = {policy.ReviewStatus.UNRECONCILED, policy.ReviewStatus.PARENT_MOVED}
        if require_force_for_warnings and selected.status in warning_statuses and not force:
            raise ControllerError(f"{selected.channel.value} review cannot run: {selected.status.value}; use --force")
        if (
            require_force_for_warnings
            and not force
            and (not selected.target.reconciled or not selected.target.ancestor_links_valid)
        ):
            raise ControllerError(f"{selected.channel.value} review has candidate identity warnings; use --force")

    def status(self) -> dict[str, Any]:
        state = self._state()
        return self._status_from_state(
            state,
            review_target_prs=state.ordered_prs,
            review_target_selection_complete=True,
        )

    def list_routes(self, *, target_pr: int | None = None, unassigned: bool = False) -> dict[str, Any]:
        """Return open incoming or unassigned routes without consulting live PR state."""

        if (target_pr is None) == (not unassigned):
            raise ControllerError("route query requires exactly one of target_pr or unassigned")
        routes = [
            item.to_dict()
            for item in self._state().routes
            if item.status == "open"
            and ((unassigned and item.target_pr is None) or (target_pr is not None and item.target_pr == target_pr))
        ]
        return {
            "query": "unassigned" if unassigned else "target_pr",
            "target_pr": target_pr,
            "routes": routes,
            "count": len(routes),
        }

    def record_route(
        self,
        *,
        source_pr: int,
        source_channel: str,
        source_review: str,
        source_finding: str,
        observation: str,
        target_pr: int | None = None,
    ) -> dict[str, Any]:
        """Create one stable route or append an observation to its existing identity."""

        candidate = FindingRoute(
            source_pr,
            source_channel,
            source_review,
            source_finding,
            (observation,),
            target_pr=target_pr,
        )
        selected: FindingRoute | None = None

        def mutate(state: ReviewState) -> ReviewState:
            nonlocal selected
            existing = next((item for item in state.routes if item.route_id == candidate.route_id), None)
            if existing is None:
                selected = candidate
                return dataclasses.replace(state, routes=(*state.routes, candidate))
            if existing.status != "open":
                raise ControllerError(f"route {existing.route_id} is already dispositioned")
            try:
                selected = merge_open_route(existing, candidate)
            except StateError as exc:
                raise ControllerError(str(exc)) from exc
            return dataclasses.replace(
                state,
                routes=tuple(selected if item.route_id == selected.route_id else item for item in state.routes),
            )

        self.store.update(mutate)
        assert selected is not None
        return {"status": "recorded", "route": selected.to_dict()}

    def decide_route(
        self,
        *,
        route_id: str,
        decision: str,
        reason: str | None = None,
        proof: str | None = None,
        target_pr: int | None = None,
    ) -> dict[str, Any]:
        """Disposition one target-owned route while preserving its stable identity."""

        if not re.fullmatch(r"[0-9a-f]{24}", route_id):
            raise ControllerError("route ID must be a 24-character stable route identity")
        if decision == "accepted-fixed":
            if not isinstance(proof, str) or not proof.strip() or reason is not None or target_pr is not None:
                raise ControllerError("accepted-fixed route disposition requires only --proof")
            if len(proof) > 500:
                raise ControllerError("accepted-fixed route proof must be at most 500 characters")
            if any(ord(character) < 0x20 for character in proof):
                raise ControllerError("accepted-fixed route proof must not contain control characters")
        elif decision == "rejected":
            if not isinstance(reason, str) or not reason.strip() or proof is not None or target_pr is not None:
                raise ControllerError("rejected route disposition requires only --reason")
            if len(reason) > 500:
                raise ControllerError("rejected route reason must be at most 500 characters")
            if any(ord(character) < 0x20 for character in reason):
                raise ControllerError("rejected route reason must not contain control characters")
        elif decision == "retargeted":
            if (
                isinstance(target_pr, bool)
                or not isinstance(target_pr, int)
                or target_pr <= 0
                or not isinstance(reason, str)
                or not reason.strip()
                or proof is not None
            ):
                raise ControllerError("retargeted route disposition requires --target-pr and --reason")
            if len(f"Retargeted: {reason}") > 500 or any(ord(character) < 0x20 for character in reason):
                raise ControllerError("retarget reason must fit within a 500-character route observation")
        else:
            raise ControllerError("route disposition must be accepted-fixed, rejected, or retargeted")
        selected: FindingRoute | None = None

        def mutate(state: ReviewState) -> ReviewState:
            nonlocal selected
            existing = next((item for item in state.routes if item.route_id == route_id), None)
            if existing is None:
                raise ControllerError(f"route {route_id} does not exist")
            if existing.status != "open":
                raise ControllerError(f"route {route_id} is already dispositioned")
            if decision == "accepted-fixed":
                selected = dataclasses.replace(
                    existing, status="accepted_fixed", disposition="accepted and fixed", proof=proof
                )
            elif decision == "rejected":
                selected = dataclasses.replace(existing, status="rejected", disposition=reason)
            else:
                if existing.target_pr == target_pr:
                    raise ControllerError("retargeted route must change its target PR")
                selected = dataclasses.replace(
                    existing,
                    target_pr=target_pr,
                    observations=(*existing.observations, f"Retargeted: {reason}"),
                    target_history=(*existing.target_history, existing.target_pr),
                )
            return dataclasses.replace(
                state,
                routes=tuple(selected if item.route_id == route_id else item for item in state.routes),
            )

        self.store.update(mutate)
        assert selected is not None
        if decision == "retargeted":
            return {"status": "recorded", "decision": decision, "route": selected.to_dict()}
        return {"status": selected.status, "route": selected.to_dict()}

    @staticmethod
    def _review_progress_view(
        allocation: ReviewAllocation | None,
        view: Mapping[str, Any],
        history: Sequence[Any],
        status: str,
        *,
        pending_findings: bool = False,
        checked: bool = True,
    ) -> dict[str, Any]:
        """Present existing policy/counters separately from request preparation."""
        minimum = view.get("minimum_additional_completed")
        maximum = view.get("maximum_additional_completed")
        if allocation is not None:
            default_one = (
                allocation.min_additional_completed is None
                and allocation.max_additional_completed is None
                and allocation.stop_basis is None
            )
            minimum = 1 if default_one else allocation.min_additional_completed
            maximum = 1 if default_one else allocation.max_additional_completed
        counters_checked = checked and view.get("status") != "INVALID"
        completed = view.get("completed_count") if counters_checked else None
        in_flight = view.get("in_flight") if counters_checked else None
        slots = view.get("remaining") if counters_checked else None
        required_remaining = max(0, minimum - completed) if minimum is not None and completed is not None else None
        maximum_remaining = max(0, maximum - completed) if maximum is not None and completed is not None else None
        allocation_status = view.get("status")
        if (
            not checked
            and allocation is not None
            and allocation_status in {"CAP_AUDITED_STOP", "CAP_TAPERED", "HANDED_OFF"}
            and (
                view.get("minimum_additional_completed") != minimum
                or view.get("maximum_additional_completed") != maximum
                or view.get("reopens_taper") != allocation.reopens_taper
                or view.get("baseline_checkpoint") != allocation.baseline_checkpoint
                or view.get("promised_head") != allocation.head
            )
        ):
            # A prior closed view cannot satisfy a replacement allocation.
            allocation_status = "NOT_CHECKED"
        rule_kind = (
            "required"
            if minimum and (maximum == minimum or required_remaining != 0)
            else ("maximum" if maximum is not None else "normal_taper")
        )
        description = "Remaining rounds include any running or reserved round."
        if maximum is not None and maximum != minimum:
            description += " Normal taper may finish before the maximum after any required minimum is met."

        def rounds(number: int) -> str:
            return f"{number} round{'s' if number != 1 else ''}"

        if rule_kind == "required":
            label = (
                f"{required_remaining} required round{'s' if required_remaining != 1 else ''} remaining"
                if required_remaining is not None
                else f"{minimum} required round{'s' if minimum != 1 else ''} configured"
            )
            if maximum is not None and maximum != minimum:
                label += (
                    f" · Maximum {rounds(maximum_remaining)} remaining"
                    if maximum_remaining is not None
                    else f" · Maximum {rounds(maximum)} configured"
                )
        elif rule_kind == "maximum":
            label = (
                f"Maximum {rounds(maximum_remaining)} remaining"
                if maximum_remaining is not None
                else f"Maximum {rounds(maximum)} configured"
            )
        else:
            label = "Normal taper"

        if not checked and (
            status not in {"COMPLETE", "HUMAN_STOPPED"}
            or (
                allocation is not None
                and allocation_status not in {"STOPPED", "CAP_AUDITED_STOP", "CAP_TAPERED", "HANDED_OFF"}
            )
        ):
            status = "NOT_CHECKED"
        progress_label = {
            "READY": "Needs review",
            "MISSING_EVIDENCE": "Needs review",
            "COMPLETE": "Review complete",
            "HELD": "Pending adjudication",
            "RATE_LIMITED": "Cooldown active",
            "UNSTABLE": "Evidence unclear",
            "OVER_CEILING": "File limit",
            "JUDGMENT_REQUIRED": "Needs decision",
            "NOT_CHECKED": "Progress not checked",
            "UNKNOWN": "Progress not checked",
        }.get(status, "Progress not checked")
        active = checked and any(
            _field(item, "active_review") is True
            or (_field(item, "active_reservation") is True and _field(item, "reason") == HOSTED_ACTIVE_RESPONSE_REASON)
            for item in history
        )
        reserved = checked and any(_field(item, "active_reservation") is True for item in history)
        if allocation_status == "STOPPED":
            rule_kind, label = "human_stop", "Human stop"
            status, progress_label = "HUMAN_STOPPED", "Stopped"
        elif checked and pending_findings:
            status, progress_label = "PENDING_FIXES", "Pending fixes"
        elif checked and allocation_status in {
            "CAP_FINDINGS_PENDING",
            "CAP_TAPERED_PENDING",
            "CAP_EXHAUSTED_PENDING",
            "EXHAUSTED_PENDING",
        }:
            status, progress_label = "JUDGMENT_REQUIRED", "Pending adjudication"
            if view.get("taper_complete") is True:
                label = "Taper met" + (f" · Maximum {rounds(maximum)}" if maximum is not None else "")
        elif checked and allocation_status == "INVALID":
            status, progress_label = "UNSTABLE", "Evidence unclear"
        elif allocation_status in {"CAP_AUDITED_STOP", "CAP_TAPERED", "HANDED_OFF"}:
            status, progress_label = "COMPLETE", "Review complete"
            if allocation_status == "CAP_TAPERED":
                label = "Taper met" + (f" · Maximum {rounds(maximum)}" if maximum is not None else "")
            elif minimum and minimum == maximum:
                label = "Required rounds complete"
            else:
                label = "Maximum reached" if maximum is not None else "Review complete"
        elif status == "COMPLETE":
            label = "Taper met" + (f" · Maximum {rounds(maximum)}" if maximum is not None else "")
        elif maximum_remaining == 0:
            label = "Required rounds complete" if minimum == maximum else "Maximum reached"
            if status in {"READY", "MISSING_EVIDENCE"}:
                progress_label = "Needs stop decision"
        elif minimum and required_remaining == 0 and maximum is None:
            label = "Required rounds complete · Normal taper"
        if active or reserved:
            status, progress_label = "HELD", "Reviewing" if active else "Request reserved"
        return {
            "status": status,
            "label": progress_label,
            "rule": {
                "kind": rule_kind,
                "label": label,
                "description": description,
                "minimum": minimum,
                "maximum": maximum,
                "completed": completed,
                "required_remaining": required_remaining,
                "maximum_remaining": maximum_remaining,
                "in_flight": in_flight,
                "request_slots_remaining": slots,
            },
        }

    @staticmethod
    def _present_review_turns(report: Mapping[str, Any]) -> None:
        """Use the already selected channel fronts; never select a display target."""
        targets = report.get("review_fronts", {})
        for row in report.get("prs", []):
            for channel, progress in row.get("review_progress", {}).items():
                target = targets.get(channel, {})
                if progress.pop("waiting_for_pr", None) is not None and progress.get("label") == "Waiting turn":
                    progress["label"] = "Needs review"
                if (
                    target.get("pr") == row["pr"]
                    and progress.get("status") in {"READY", "MISSING_EVIDENCE"}
                    and target.get("status")
                    in {"HELD", "JUDGMENT_REQUIRED", "RATE_LIMITED", "UNSTABLE", "OVER_CEILING"}
                ):
                    # Typed request blockers already owned by the selector.
                    # Ancestry statuses deliberately remain request-only.
                    progress["status"] = target["status"]
                    progress["label"] = {
                        "HELD": "Review held",
                        "JUDGMENT_REQUIRED": "Needs decision",
                        "RATE_LIMITED": "Cooldown active",
                        "UNSTABLE": "Evidence unclear",
                        "OVER_CEILING": "File limit",
                    }[target["status"]]
                if (
                    progress.get("status") in {"READY", "MISSING_EVIDENCE"}
                    and progress.get("label") == "Needs review"
                    and target.get("pr") is not None
                    and target["pr"] != row["pr"]
                    and target.get("status") != "UNKNOWN"
                ):
                    progress["label"] = "Waiting turn"
                    progress["waiting_for_pr"] = target["pr"]

    def _initial_status_allocations(
        self,
        state: ReviewState,
        live: Mapping[int, LivePullRequest],
        reconciliation: stack.Reconciliation,
        histories: Mapping[policy.Channel, Mapping[int, Sequence[Any]]],
        *,
        stop_audit_cache: dict[tuple[Any, ...], Mapping[str, Any]],
        history_cache: dict[tuple[int, str], list[Any]],
        bounded_evidence_cache: dict[tuple[int, str], Mapping[str, Any]],
        concurrent_prs: bool = False,
    ) -> dict[policy.Channel, dict[int, dict[str, Any]]]:
        channels = (policy.Channel.HOSTED, policy.Channel.CLI)
        allocated_prs = tuple(
            pr for pr in state.ordered_prs
            if not live[pr].merged and any(f"{pr}:{channel.value}" in state.allocations for channel in channels)
        )
        if not concurrent_prs or len(allocated_prs) < 2:
            return {
                channel: self._allocation_views(
                    state, live, reconciliation, channel, histories[channel],
                    stop_audit_cache=stop_audit_cache,
                    history_cache=history_cache,
                    bounded_evidence_cache=bounded_evidence_cache,
                )
                for channel in channels
            }

        def audit_pr(key):
            if isinstance(key[0], int):
                return key[0]
            return key[1][0] if key[0] == "snapshot" else key[1]

        # Each PR owns both channel caches. Only the caller publishes results,
        # after every worker has joined; target reconciliation remains serial.
        inputs = {
            pr: (
                copy.deepcopy({key: value for key, value in stop_audit_cache.items() if audit_pr(key) == pr}),
                copy.deepcopy({key: value for key, value in history_cache.items() if key[0] == pr}),
                copy.deepcopy({key: value for key, value in bounded_evidence_cache.items() if key[0] == pr}),
            )
            for pr in allocated_prs
        }
        budget = github.active_hosted_preflight_budget()

        def project(pr):
            audits, history, bounded = inputs[pr]
            views = {
                channel: self._allocation_views(
                    state, live, reconciliation, channel, histories[channel], pr_numbers=(pr,),
                    stop_audit_cache=audits, history_cache=history, bounded_evidence_cache=bounded,
                )
                for channel in channels
            }
            return views, audits, history, bounded

        def project_bound(pr):
            if budget is None:
                return project(pr)
            with github.bind_hosted_preflight_budget(budget):
                budget.remaining_seconds()
                return project(pr)

        with ThreadPoolExecutor(max_workers=min(4, len(allocated_prs))) as pool:
            results = list(pool.map(project_bound, allocated_prs))
        allocations: dict[policy.Channel, dict[int, dict[str, Any]]] = {channel: {} for channel in channels}
        for pr, (views, audits, history, bounded) in zip(allocated_prs, results):
            for channel in channels:
                allocations[channel].update(views[channel])
            for cache, refreshed, owner in (
                (stop_audit_cache, audits, audit_pr),
                (history_cache, history, lambda key: key[0]),
                (bounded_evidence_cache, bounded, lambda key: key[0]),
            ):
                for key in tuple(cache):
                    if owner(key) == pr:
                        del cache[key]
                cache.update(refreshed)
        return allocations

    def _status_from_state(
        self,
        state: ReviewState,
        *,
        evidence_prs: set[int] | None = None,
        live_identities: Mapping[int, Any] | None = None,
        history_cache: dict[tuple[int, str], list[Any]] | None = None,
        live_identity_cache: dict[int, Any] | None = None,
        stop_audit_cache: dict[tuple[Any, ...], Mapping[str, Any]] | None = None,
        review_target_prs: Sequence[int] | None = None,
        review_target_selection_complete: bool = False,
        remote_head_snapshot: Mapping[str, str] | None = None,
        phase_timings: dict[str, float] | None = None,
        concurrent_allocation_prs: bool = False,
    ) -> dict[str, Any]:
        if history_cache is None:
            history_cache = {}
        if not state.ordered_prs:
            report = {
                "ordered_prs": [],
                "status": "EMPTY",
                "legacy_transitions": [item.to_dict() for item in state.legacy_transitions],
            }
            if review_target_prs is not None:
                report["review_targets"] = self._empty_review_targets(state)
                report["review_fronts"] = self._empty_review_targets(state)
            return report
        refresh_prs = evidence_prs
        selected_live_identities = live_identities
        if live_identity_cache is not None:
            for pr in evidence_prs or ():
                if pr not in live_identity_cache:
                    identity_started = time.perf_counter() if phase_timings is not None else 0.0
                    try:
                        live_identity_cache[pr] = self._require_github().pull_request(pr)
                    finally:
                        if phase_timings is not None:
                            phase_timings["deep_pr_identity_ms"] = (
                                phase_timings.get("deep_pr_identity_ms", 0.0)
                                + (time.perf_counter() - identity_started) * 1000
                            )
            selected_live_identities = dict(live_identities or {})
            selected_live_identities.update(live_identity_cache)
            refresh_prs = set()
        live, reconciliation = self._reconciliation(
            state,
            live_identities=selected_live_identities,
            refresh_prs=refresh_prs,
            evidence_prs=evidence_prs,
            history_cache=history_cache,
            remote_head_snapshot=remote_head_snapshot,
            phase_timings=phase_timings,
        )
        values: list[dict[str, Any]] = []
        histories = {
            channel: {
                pr: (
                    self._policy_history(
                        state,
                        pr,
                        channel,
                        reconciliation,
                        history_cache=history_cache,
                        phase_timings=phase_timings,
                    )
                    if evidence_prs is None or pr in evidence_prs
                    else []
                )
                for pr in state.ordered_prs
            }
            for channel in (policy.Channel.HOSTED, policy.Channel.CLI)
        }
        histories[policy.Channel.CLI] = {
            pr: self._project_cli_streak_lineage(
                self._project_cli_hosted_reservations(
                    histories[policy.Channel.CLI][pr],
                    histories[policy.Channel.HOSTED][pr],
                    pr,
                    live[pr].head,
                    self._reconciled_anchor(pr, live[pr], reconciliation),
                ),
                pr,
                state,
                self._reconciled_anchor(pr, live[pr], reconciliation),
            )
            for pr in state.ordered_prs
        }
        if stop_audit_cache is None:
            stop_audit_cache = {}
        bounded_evidence_cache: dict[tuple[int, str], Mapping[str, Any]] = {}
        allocations = self._initial_status_allocations(
            state, live, reconciliation, histories,
            stop_audit_cache=stop_audit_cache,
            history_cache=history_cache,
            bounded_evidence_cache=bounded_evidence_cache,
            concurrent_prs=concurrent_allocation_prs,
        )
        review_targets = None
        if review_target_prs is not None:
            review_targets = self._status_review_targets(
                state,
                live,
                reconciliation,
                histories,
                allocations,
                review_target_prs,
                selection_complete=review_target_selection_complete,
                bounded_evidence_cache=bounded_evidence_cache,
                stop_audit_cache=stop_audit_cache,
                history_cache=history_cache,
            )
        else:
            # Allocation audits refresh both operation-local histories even
            # when this read does not select a queue target. Render those final
            # snapshots through the same projection used by target status.
            checked_prs = tuple(pr for pr in state.ordered_prs if evidence_prs is None or pr in evidence_prs)
            final_histories = self._refreshed_status_histories(
                state,
                live,
                reconciliation,
                checked_prs,
                history_cache,
            )
            for channel in (policy.Channel.HOSTED, policy.Channel.CLI):
                final_allocations = self._refreshed_status_allocations(
                    state,
                    live,
                    reconciliation,
                    histories,
                    allocations,
                    checked_prs,
                    bounded_evidence_cache,
                    channel,
                    final_histories,
                )
                allocations[channel].update(
                    {pr: view for pr, view in final_allocations.items() if pr in allocations[channel]}
                )
                histories[channel].update(final_histories[channel])
        for pr in state.ordered_prs:
            item = live[pr]
            reconciliation_status = reconciliation.status_for(pr)
            channel_status: dict[str, str] = {}
            progress_views: dict[str, Any] = {}
            pending_by_channel = {
                channel: {
                    index
                    for index, value in enumerate(histories[channel][pr])
                    if self._accepted_findings_pending(value, item.head)
                }
                for channel in (policy.Channel.HOSTED, policy.Channel.CLI)
            }
            for channel in (policy.Channel.HOSTED, policy.Channel.CLI):
                channel_reconciliation = reconciliation.status_for(pr, channel.value)
                allocation = state.allocations.get(f"{pr}:{channel.value}")
                allocation_view = allocations[channel].get(pr, {})
                taper_history = None
                if allocations[channel].get(pr, {}).get("status") == "STOPPED":
                    channel_status[channel.value] = policy.ReviewStatus.HUMAN_STOPPED.value
                else:
                    bounded_snapshot = bounded_evidence_cache.get((pr, channel.value))
                    if (
                        allocation is not None
                        and bounded_snapshot is not None
                        and (
                            allocations[channel].get(pr, {}).get("reopens_taper") is True
                            or any(result["accepted"] > 0 for result in bounded_snapshot["results"])
                        )
                    ):
                        taper_history = policy.fresh_taper_history(
                            state,
                            channel,
                            histories[channel][pr],
                            baseline_checkpoints=self._bounded_allocation_taper_baseline(
                                allocation,
                                bounded_snapshot,
                                histories[channel][pr],
                            ),
                        )
                    projected_status = policy.completion_status(
                        state,
                        channel,
                        histories[channel][pr],
                        taper_history=taper_history,
                        reconciliation=channel_reconciliation,
                        allocation_reopened=_allocation_reopens_selection(allocations[channel].get(pr, {})),
                    )
                    if projected_status != policy.ReviewStatus.COMPLETE and channel_reconciliation in {
                        stack.ReconciliationStatus.PARENT_MOVED,
                        stack.ReconciliationStatus.UNRECONCILED,
                    }:
                        projected_status = (
                            policy.ReviewStatus.PARENT_MOVED
                            if channel_reconciliation == stack.ReconciliationStatus.PARENT_MOVED
                            else policy.ReviewStatus.UNRECONCILED
                        )
                    channel_status[channel.value] = projected_status.value
                # Request evidence and statuses remain untouched. Only this
                # presentation copy omits ancestry preparation flags.
                progress_evidence = [
                    dataclasses.replace(policy.Evidence.from_value(value), parent_moved=False, unreconciled=False)
                    for value in histories[channel][pr]
                ]
                progress_status = policy.completion_status(
                    state,
                    channel,
                    progress_evidence,
                    taper_history=taper_history,
                    allocation_reopened=_allocation_reopens_selection(allocation_view),
                )
                progress_views[channel.value] = self._review_progress_view(
                    allocation,
                    allocation_view,
                    histories[channel][pr],
                    progress_status.value,
                    pending_findings=bool(pending_by_channel[channel]),
                    checked=evidence_prs is None or pr in evidence_prs,
                )
            values.append(
                {
                    "pr": pr,
                    "head": item.head,
                    "base": item.base_ref,
                    "pr_base_oid": item.base_tip,
                    "parent": reconciliation.links[pr].identity,
                    "parent_head": reconciliation.links[pr].parent_head,
                    "state": item.state,
                    "merged": item.merged,
                    "is_draft": item.is_draft,
                    "draft_notice": DRAFT_PR_NOTICE if item.is_draft else None,
                    "reconciliation": reconciliation_status.value,
                    "reason": reconciliation.reasons.get(pr, ""),
                    "channels": channel_status,
                    "review_progress": progress_views,
                    "review_obligations": {
                        channel.value: [
                            _field(value, "reason")
                            or _field(value, "checkpoint")
                            or "review evidence requires adjudication"
                            for index, value in enumerate(histories[channel][pr])
                            if (
                                _field(value, "active_review") is True
                                or _field(value, "active_reservation") is True
                                or (_field(value, "held") is True and _field(value, "over_ceiling") is not True)
                                or _field(value, "unstable") is True
                                or (
                                    _field(value, "provisional") is True
                                    and _field(value, "head") == item.head
                                    and not any(
                                        _field(later, "completed") is True
                                        and _field(later, "attributable") is True
                                        and _field(later, "anchored") is True
                                        and _field(later, "provisional") is not True
                                        and _field(later, "non_counting") is not True
                                        and _field(later, "correction") is not True
                                        for later in histories[channel][pr][index + 1 :]
                                    )
                                )
                                or str(_field(value, "checkpoint") or "").startswith("pending-capture:")
                                or index in pending_by_channel[channel]
                            )
                        ]
                        + (
                            [stop_audit_cache[("latest", pr)]["error"]]
                            if stop_audit_cache.get(("latest", pr), {}).get("error")
                            else []
                        )
                        for channel in (policy.Channel.HOSTED, policy.Channel.CLI)
                    },
                    "review_activity": {
                        channel.value: _review_activity(histories[channel][pr], item.head)
                        for channel in (policy.Channel.HOSTED, policy.Channel.CLI)
                    },
                    "channel_reasons": {
                        channel.value: reasons
                        for channel in (policy.Channel.HOSTED, policy.Channel.CLI)
                        if (reasons := _channel_reasons(histories[channel][pr], item.head))
                    },
                    "allocations": {
                        channel.value: allocations[channel][pr]
                        for channel in (policy.Channel.HOSTED, policy.Channel.CLI)
                        if pr in allocations[channel]
                    },
                    "incoming_routes": [
                        route.to_dict() for route in state.routes if route.status == "open" and route.target_pr == pr
                    ],
                    "routes_out": [route.to_dict() for route in state.routes if route.source_pr == pr],
                }
            )
        report = {
            "ordered_prs": list(state.ordered_prs),
            "status": reconciliation.status.value,
            "prs": values,
            "legacy_transitions": [item.to_dict() for item in state.legacy_transitions],
        }
        if review_target_prs is not None:
            report["review_targets"] = review_targets
            # PR-level queue selection survives request-only identity warnings.
            # Keep a separate snapshot; neither presentation nor later request
            # invalidation may select a replacement or change policy admission.
            report["review_fronts"] = {channel: dict(target) for channel, target in report["review_targets"].items()}
        self._present_review_turns(report)
        return report

    @staticmethod
    def _unknown_review_targets(reason: str) -> dict[str, dict[str, Any]]:
        return {
            channel.value: {
                "channel": channel.value,
                "pr": None,
                "status": "UNKNOWN",
                "reason": reason,
                "provisional": False,
            }
            for channel in (policy.Channel.HOSTED, policy.Channel.CLI)
        }

    @staticmethod
    def _empty_review_targets(state: ReviewState) -> dict[str, dict[str, Any]]:
        result = {}
        for channel in (policy.Channel.HOSTED, policy.Channel.CLI):
            decision = policy.select_review_target(state, channel, (), {})
            value = decision.to_dict()
            value["pr"] = value.pop("target")
            result[channel.value] = value
        return result

    @staticmethod
    def _unknown_overview(state: ReviewState, reason: str) -> dict[str, Any]:
        prs = [
            {
                "pr": pr,
                "head": None,
                "base": None,
                "pr_base_oid": None,
                "parent": None,
                "parent_head": None,
                "state": "UNKNOWN",
                "merged": None,
                "is_draft": None,
                "draft_notice": None,
                "reconciliation": "UNKNOWN",
                "reason": reason,
                "channels": {"hosted": "UNKNOWN", "cli": "UNKNOWN"},
                "review_activity": {},
                "allocations": {},
                "incoming_routes": [
                    route.to_dict() for route in state.routes if route.status == "open" and route.target_pr == pr
                ],
                "routes_out": [route.to_dict() for route in state.routes if route.source_pr == pr],
                "detail_level": "unknown",
                "evidence_status": "unknown",
                "evidence_last_checked_at": None,
            }
            for pr in state.ordered_prs
        ]
        return {
            "ordered_prs": list(state.ordered_prs),
            "status": "UNKNOWN",
            "mode": "windowed",
            "detail_window": {
                "unmerged_limit": 4,
                "batch_status": "failed",
                "deep_prs": [],
                "active_targets": [],
                "reason": reason,
            },
            "prs": prs,
            "review_targets": ReviewController._unknown_review_targets(reason),
            "review_fronts": ReviewController._unknown_review_targets(reason),
            "legacy_transitions": [item.to_dict() for item in state.legacy_transitions],
        }

    @staticmethod
    def _batch_live_pull_requests(
        provider: Any, numbers: Sequence[int], *, overview: bool = False
    ) -> tuple[dict[int, LivePullRequest], dict[int, Mapping[str, Any] | None]]:
        fetch = getattr(provider, "batch_pull_requests_overview", None) if overview else None
        if not callable(fetch):
            fetch = getattr(provider, "batch_pull_requests", None)
        if not callable(fetch):
            raise ControllerError("GitHub provider does not support batched PR identities")
        values = fetch(tuple(numbers))
        if not isinstance(values, Mapping):
            raise ControllerError("GitHub PR identity batch is malformed")
        result: dict[int, LivePullRequest] = {}
        raw_values: dict[int, Mapping[str, Any] | None] = {}
        for number in numbers:
            value = values.get(number)
            if value is None:
                raise ControllerError(f"GitHub PR identity batch is missing PR #{number}")
            try:
                result[number] = _live(value, number)
            except (ControllerError, TypeError, ValueError, KeyError) as error:
                raise ControllerError(f"GitHub PR identity batch is ambiguous for PR #{number}") from error
            raw_values[number] = value if isinstance(value, Mapping) else None
        return result, raw_values

    @staticmethod
    def _saved_identity_moved(
        state: ReviewState,
        pr: int,
        item: LivePullRequest,
        parent: stack.ParentLink,
    ) -> bool:
        for allocation in state.allocations.values():
            if allocation.pr == pr and (
                allocation.head.casefold() != item.head.casefold()
                or allocation.parent_identity != parent.identity
                or allocation.parent_head.casefold() != parent.parent_head.casefold()
            ):
                return True
        for decision in state.reconciliations:
            if decision.pr == pr and (
                decision.child_head.casefold() != item.head.casefold()
                or decision.parent_identity != parent.identity
                or decision.parent_head.casefold() != parent.parent_head.casefold()
            ):
                return True
        for transition in state.legacy_transitions:
            if transition.pr == pr and (
                transition.child_head.casefold() != item.head.casefold()
                or transition.parent_identity != parent.identity
                or transition.parent_head.casefold() != parent.parent_head.casefold()
            ):
                return True
        return False

    def status_for_pr(
        self,
        pr: int,
        *,
        conversation_payload: dict[str, Any] | None = None,
        summary_only: bool = False,
    ) -> dict[str, Any]:
        """Deeply verify one selected PR and its configured ancestors only."""

        state = self._state()
        try:
            index = state.ordered_prs.index(pr)
        except ValueError:
            return {
                "ordered_prs": list(state.ordered_prs),
                "status": "UNKNOWN",
                "prs": [],
                "legacy_transitions": [item.to_dict() for item in state.legacy_transitions],
                "selected_pr": pr,
                "reason": "PR is not configured in the repository review stack",
                "incoming_routes": [
                    item.to_dict() for item in state.routes if item.status == "open" and item.target_pr == pr
                ],
                "routes_out": [item.to_dict() for item in state.routes if item.source_pr == pr],
            }
        scoped = dataclasses.replace(state, ordered_prs=state.ordered_prs[: index + 1])
        if conversation_payload is not None:
            prefetch_payload = getattr(self._evidence_provider, "prefetch_payload", None)
            if callable(prefetch_payload):
                prefetch_payload(pr, conversation_payload)
        live_identities, _ = self._batch_live_pull_requests(self._require_github(), scoped.ordered_prs)
        evidence_prs = None
        selected_identity = self._require_github().pull_request(pr)
        live_identity_cache = {pr: selected_identity}
        if summary_only:
            # The summary still needs the complete fresh identity prefix to
            # select effective parents and validate every PR lifecycle. Only
            # selected and unmerged PRs need full conversation evidence.
            evidence_prs = {
                number for number, item in live_identities.items() if number == pr or not item.merged
            }
            # Feed the validated batch back through reconciliation rather than
            # issuing per-ancestor identity reads for this smaller evidence set.
            live_identity_cache = dict(live_identities)
            # Preserve the independent later selected-PR identity observation.
            live_identity_cache[pr] = selected_identity
        report = self._status_from_state(
            scoped,
            evidence_prs=evidence_prs,
            live_identities=live_identities,
            live_identity_cache=live_identity_cache,
        )
        report["ordered_prs"] = list(state.ordered_prs)
        report["selected_pr"] = pr
        if summary_only:
            report["prs"] = [item for item in report["prs"] if item["pr"] in evidence_prs]
            report["scope"] = "selected PR and unmerged configured ancestors; merged ancestors identity-only"
        else:
            report["scope"] = "selected PR and configured ancestors"
        return report

    def status_overview(self) -> dict[str, Any]:
        """Show a fresh lightweight queue with deep evidence for its active front."""

        state = self._state()
        if not state.ordered_prs:
            return {
                "ordered_prs": [],
                "status": "EMPTY",
                "mode": "windowed",
                "detail_window": {"unmerged_limit": 4, "batch_status": "complete", "deep_prs": []},
                "prs": [],
                "review_targets": self._empty_review_targets(state),
                "review_fronts": self._empty_review_targets(state),
                "legacy_transitions": [item.to_dict() for item in state.legacy_transitions],
            }

        try:
            batch_live, raw_identities = self._batch_live_pull_requests(self.github, state.ordered_prs, overview=True)
            remote_heads = self.git.remote_heads()
            default_tip = _sha(remote_heads.get(self.default_base_ref), "default base tip")
            batch_snapshots = {pr: item.snapshot() for pr, item in batch_live.items()}
            links = stack.effective_parent_links(
                state.ordered_prs,
                batch_snapshots,
                self.default_base_ref,
                default_tip,
            )
        except (ControllerError, OSError, subprocess.SubprocessError, RuntimeError, TypeError, ValueError) as error:
            return self._unknown_overview(state, str(error))

        # Closed PRs remain in the queue for history, but old manual trigger
        # comments and reservations on them cannot be active review targets.
        # Deep-reading those records on every display refresh is expensive;
        # request admission still performs its own fresh repository-wide check.
        open_prs = tuple(pr for pr in state.ordered_prs if batch_live[pr].state == "OPEN")
        active_targets = {
            allocation.pr
            for allocation in state.allocations.values()
            if allocation.pr in open_prs and allocation.handoff_checkpoint is None and allocation.stop_basis is None
        }
        active_scan_status = "unknown"
        active_scan_error = None
        discover_active = getattr(self._evidence_provider, "active_review_targets", None)
        if callable(discover_active):
            try:
                found = discover_active(open_prs, raw_identities)
                active_scan_status = "complete"
            except (ControllerError, OSError, RuntimeError, TypeError, ValueError, KeyError) as error:
                found = set()
                active_scan_status = "unknown"
                active_scan_error = str(error)
            if not isinstance(found, (set, frozenset)) or any(
                isinstance(pr, bool) or not isinstance(pr, int) or pr not in open_prs for pr in found
            ):
                active_scan_status = "unknown"
                active_scan_error = "active review target probe returned malformed PR identities"
            else:
                active_targets.update(found)

        first_four = []
        for pr in state.ordered_prs:
            if batch_live[pr].state == "OPEN":
                first_four.append(pr)
                if len(first_four) == 4:
                    break
        candidate_prs = [pr for pr in state.ordered_prs if batch_live[pr].state == "OPEN"]
        target_prs = set(first_four) | active_targets
        target_indexes = [state.ordered_prs.index(pr) for pr in target_prs if pr in state.ordered_prs]
        deep_prs = target_prs.intersection(state.ordered_prs)
        frontier = max(target_indexes, default=-1)
        scoped_report: dict[str, Any] | None = None
        deep_error = None
        history_cache: dict[tuple[int, str], list[Any]] = {}
        live_identity_cache: dict[int, Any] = {}
        stop_audit_cache: dict[tuple[Any, ...], Mapping[str, Any]] = {}
        phase_timings: dict[str, float] = {}
        target_scan_prs: list[int] = []
        while deep_prs:
            frontier = max(state.ordered_prs.index(pr) for pr in deep_prs)
            scoped_prs = state.ordered_prs[: frontier + 1]
            scoped_state = dataclasses.replace(state, ordered_prs=tuple(scoped_prs))
            target_scan_prs: list[int] = []
            for pr in state.ordered_prs:
                if batch_live[pr].state != "OPEN":
                    continue
                if pr not in deep_prs:
                    break
                target_scan_prs.append(pr)
            target_scan_complete = all(batch_live[pr].state != "OPEN" or pr in deep_prs for pr in state.ordered_prs)
            try:
                # Deep PR metadata and complete review snapshots are independent
                # reads. Fetch the newly selected window together instead of
                # serializing each GitHub round trip. New-request preflight is
                # separate and still reads fresh evidence at request time.
                new_deep_prs = [pr for pr in state.ordered_prs if pr in deep_prs and pr not in live_identity_cache]
                if new_deep_prs:
                    fetch_started = time.perf_counter()
                    prefetch_payload = getattr(self._evidence_provider, "prefetch_payload", None)
                    with ThreadPoolExecutor(max_workers=min(8, len(new_deep_prs) * 2)) as pool:
                        identities = {pr: pool.submit(self._require_github().pull_request, pr) for pr in new_deep_prs}
                        evidence_reads = (
                            [pool.submit(prefetch_payload, pr) for pr in new_deep_prs]
                            if callable(prefetch_payload)
                            else []
                        )
                        for pr in new_deep_prs:
                            live_identity_cache[pr] = identities[pr].result()
                        for future in evidence_reads:
                            future.result()
                    phase_timings["deep_pr_identity_ms"] = (
                        phase_timings.get("deep_pr_identity_ms", 0.0) + (time.perf_counter() - fetch_started) * 1000
                    )
                scoped_report = self._status_from_state(
                    scoped_state,
                    evidence_prs=deep_prs,
                    live_identities=raw_identities,
                    history_cache=history_cache,
                    live_identity_cache=live_identity_cache,
                    stop_audit_cache=stop_audit_cache,
                    review_target_prs=target_scan_prs,
                    review_target_selection_complete=target_scan_complete,
                    remote_head_snapshot=remote_heads,
                    phase_timings=phase_timings,
                    concurrent_allocation_prs=True,
                )
            except (ControllerError, OSError, subprocess.SubprocessError, RuntimeError, TypeError, ValueError) as error:
                deep_error = str(error)
                break

            review_targets = scoped_report.get("review_targets", {})
            if not any(
                isinstance(value, Mapping) and value.get("status") == "UNKNOWN" for value in review_targets.values()
            ):
                break
            scanned_count = len(target_scan_prs)
            growth_count = max(4, scanned_count)
            next_target_prs = candidate_prs[scanned_count : scanned_count + growth_count]
            if not next_target_prs:
                break
            deep_prs.update(next_target_prs)

        remote_recheck_error = None
        remote_affected: set[int] = set()
        if deep_error is None and deep_prs:
            try:
                verified_remote_heads = self.git.remote_heads()
            except (ControllerError, OSError, subprocess.SubprocessError, RuntimeError, TypeError, ValueError) as error:
                remote_recheck_error = str(error)
                remote_affected.update(deep_prs)
            else:
                relevant_refs: set[str] = set()
                for pr in state.ordered_prs:
                    item = batch_live[pr]
                    if item.state != "OPEN":
                        continue
                    relevant_refs.add(item.base_ref)
                    if item.head_ref:
                        relevant_refs.add(item.head_ref)
                changed_refs = {ref for ref in relevant_refs if remote_heads.get(ref) != verified_remote_heads.get(ref)}
                for pr in state.ordered_prs:
                    item = batch_live[pr]
                    if item.state != "OPEN":
                        continue
                    if item.base_ref in changed_refs or item.head_ref in changed_refs:
                        remote_affected.add(pr)
            changed = True
            while changed:
                changed = False
                for pr in state.ordered_prs:
                    if links[pr].parent_pr in remote_affected and pr not in remote_affected:
                        remote_affected.add(pr)
                        changed = True

        scoped_prs = state.ordered_prs[: frontier + 1] if frontier >= 0 else ()
        if not scoped_prs and deep_error is None:
            # The configured stack may contain only merged PRs; policy selection
            # then has no live candidate and returns its canonical no-target result.
            scoped_report = {
                "review_targets": self._empty_review_targets(state),
                "review_fronts": self._empty_review_targets(state),
            }

        deep_by_pr = {
            item.get("pr"): item for item in (scoped_report or {}).get("prs", []) if isinstance(item, Mapping)
        }
        mismatch: set[int] = set(remote_affected)
        lifecycle_mismatch: set[int] = set()
        for pr in scoped_prs:
            if pr not in deep_prs:
                continue
            detailed = deep_by_pr.get(pr)
            if detailed is None:
                continue
            item = batch_live[pr]
            if detailed.get("state") != item.state or detailed.get("merged") != item.merged:
                lifecycle_mismatch.add(pr)
            if (
                not isinstance(detailed.get("head"), str)
                or detailed["head"].casefold() != item.head.casefold()
                or detailed.get("base") != item.base_ref
                or not isinstance(detailed.get("pr_base_oid"), str)
                or detailed["pr_base_oid"].casefold() != item.base_tip.casefold()
                or detailed.get("state") != item.state
                or detailed.get("merged") != item.merged
            ):
                mismatch.add(pr)
        changed = True
        while changed:
            changed = False
            for pr in state.ordered_prs:
                if links[pr].parent_pr in mismatch and pr not in mismatch:
                    mismatch.add(pr)
                    changed = True

        # A retained PR base OID may differ from the current parent tip in
        # both reads. Canonical reconciliation already reports that condition;
        # only an inconsistent snapshot invalidates this display selection.
        changed_target_pr = next(
            (
                pr
                for pr in target_scan_prs
                if pr in mismatch
                or (pr not in deep_by_pr and self._saved_identity_moved(state, pr, batch_live[pr], links[pr]))
            ),
            None,
        )

        values: list[dict[str, Any]] = []
        closed_channel_states = {
            policy.ReviewStatus.COMPLETE.value,
            policy.ReviewStatus.HUMAN_STOPPED.value,
            "CAP_AUDITED_STOP",
        }
        closed_allocation_states = {"STOPPED", "CAP_AUDITED_STOP", "CAP_TAPERED"}
        for pr in state.ordered_prs:
            item = batch_live[pr]
            parent = links[pr]
            detailed = deep_by_pr.get(pr) if pr in deep_prs else None
            batch_topology_moved = item.state == "OPEN" and (
                item.base_ref != parent.parent_ref or item.base_tip.casefold() != parent.parent_head.casefold()
            )
            saved_identity_moved = self._saved_identity_moved(state, pr, item, parent)
            if detailed is not None and deep_error is None:
                row = dict(detailed)
                row.update(
                    {
                        "state": item.state,
                        "merged": item.merged,
                        "detail_level": "deep",
                        "evidence_status": "current",
                        "evidence_last_checked_at": None,
                    }
                )
                if pr in mismatch:
                    row["reconciliation"] = "UNRECONCILED"
                    row["reason"] = (
                        "remote parent or head branch changed during deep reconciliation"
                        if pr in remote_affected
                        else "live PR identity changed between batch overview and deep reconciliation"
                    )
                    channels = row.get("channels")
                    allocations = row.get("allocations")
                    channels = channels if isinstance(channels, Mapping) else {}
                    allocations = allocations if isinstance(allocations, Mapping) else {}
                    row_channels = {}
                    row_allocations = {}
                    for channel in (policy.Channel.HOSTED.value, policy.Channel.CLI.value):
                        channel_state = channels.get(channel)
                        allocation_view = allocations.get(channel)
                        allocation_status = (
                            allocation_view.get("status") if isinstance(allocation_view, Mapping) else None
                        )
                        if channel_state in closed_channel_states:
                            row_channels[channel] = channel_state
                            if allocation_status in closed_allocation_states:
                                row_allocations[channel] = allocation_view
                        elif allocation_status == "CAP_AUDITED_STOP":
                            row_channels[channel] = "CAP_AUDITED_STOP"
                            row_allocations[channel] = allocation_view
                        else:
                            row_channels[channel] = "UNRECONCILED"
                            if allocation_status in {"CAP_EXHAUSTED_PENDING", "CAP_TAPERED_PENDING"}:
                                row_allocations[channel] = allocation_view
                    for channel, stop_view in self._durable_stop_allocations(state, pr).items():
                        row_channels[channel] = policy.ReviewStatus.HUMAN_STOPPED.value
                        row_allocations[channel] = stop_view
                    row["channels"] = row_channels
                    row["allocations"] = row_allocations
                    row["evidence_status"] = "stale"
                    row["review_progress"] = {
                        channel: self._review_progress_view(
                            state.allocations.get(f"{pr}:{channel}"),
                            row_allocations.get(channel, {}),
                            [],
                            row_channels.get(channel, "NOT_CHECKED"),
                            checked=False,
                        )
                        for channel in (policy.Channel.HOSTED.value, policy.Channel.CLI.value)
                    }
                values.append(row)
                continue

            stale = pr in mismatch or batch_topology_moved or saved_identity_moved
            reason = (
                (
                    "remote parent or head branch changed during deep reconciliation"
                    if pr in remote_affected
                    else "live parent topology or saved review identity moved"
                )
                if stale
                else "tail review evidence was not deeply checked in this status invocation"
            )
            if deep_error is not None and pr in scoped_prs:
                stale = True
                reason = f"deep review evidence is unavailable: {deep_error}"
            channels = {"hosted": "NOT_CHECKED", "cli": "NOT_CHECKED"}
            durable_stops = self._durable_stop_allocations(state, pr)
            allocations = dict(durable_stops)
            if deep_error is not None and detailed is not None:
                prior_channels = detailed.get("channels")
                prior_allocations = detailed.get("allocations")
                prior_channels = prior_channels if isinstance(prior_channels, Mapping) else {}
                prior_allocations = prior_allocations if isinstance(prior_allocations, Mapping) else {}
                for channel in (policy.Channel.HOSTED.value, policy.Channel.CLI.value):
                    prior_status = prior_channels.get(channel)
                    prior_allocation = prior_allocations.get(channel)
                    allocation_status = (
                        prior_allocation.get("status") if isinstance(prior_allocation, Mapping) else None
                    )
                    if prior_status in closed_channel_states:
                        channels[channel] = prior_status
                        if allocation_status in closed_allocation_states:
                            allocations[channel] = prior_allocation
                    elif allocation_status == "CAP_AUDITED_STOP":
                        channels[channel] = "CAP_AUDITED_STOP"
                        allocations[channel] = prior_allocation
            channels.update({channel: policy.ReviewStatus.HUMAN_STOPPED.value for channel in durable_stops})
            values.append(
                {
                    "pr": pr,
                    "head": item.head,
                    "base": item.base_ref,
                    "pr_base_oid": item.base_tip,
                    "parent": parent.identity,
                    "parent_head": parent.parent_head,
                    "state": item.state,
                    "merged": item.merged,
                    "is_draft": item.is_draft,
                    "draft_notice": DRAFT_PR_NOTICE if item.is_draft else None,
                    "reconciliation": "UNKNOWN" if not stale else "UNRECONCILED",
                    "reason": reason,
                    "channels": channels,
                    "review_activity": {},
                    "review_progress": {
                        channel: self._review_progress_view(
                            state.allocations.get(f"{pr}:{channel}"),
                            allocations.get(channel, {}),
                            [],
                            channels[channel],
                            checked=False,
                        )
                        for channel in (policy.Channel.HOSTED.value, policy.Channel.CLI.value)
                    },
                    "allocations": allocations,
                    "incoming_routes": [
                        route.to_dict() for route in state.routes if route.status == "open" and route.target_pr == pr
                    ],
                    "routes_out": [route.to_dict() for route in state.routes if route.source_pr == pr],
                    "detail_level": "identity_only",
                    "evidence_status": "stale" if stale else "unknown",
                    "evidence_last_checked_at": None,
                }
            )

        report = {
            "ordered_prs": list(state.ordered_prs),
            "status": "PARTIAL",
            "mode": "windowed",
            "detail_window": {
                "unmerged_limit": max(4, len(target_scan_prs)),
                "batch_status": "complete",
                "deep_prs": [pr for pr in state.ordered_prs if pr in deep_prs and batch_live[pr].state == "OPEN"]
                if deep_error is None
                else [],
                "tail_evidence": "not fetched; identity-only rows are informational and never indicate completion",
                "active_targets": sorted(active_targets),
                "active_target_scan": active_scan_status,
                "timing_ms": {
                    "deep_pr_fetch": round(
                        phase_timings.get("deep_pr_identity_ms", 0.0)
                        + phase_timings.get("deep_evidence_history_ms", 0.0),
                        1,
                    ),
                    "local_anchors": round(phase_timings.get("local_anchors_ms", 0.0), 1),
                },
                **({"active_target_error": active_scan_error} if active_scan_error else {}),
                **({"deep_error": deep_error} if deep_error else {}),
                **({"remote_head_recheck_error": remote_recheck_error} if remote_recheck_error else {}),
            },
            "prs": values,
            "review_targets": (scoped_report or {}).get("review_targets", self._empty_review_targets(state)),
            "review_fronts": (scoped_report or {}).get(
                "review_fronts", self._unknown_review_targets("review policy selection is unavailable")
            ),
            "legacy_transitions": [item.to_dict() for item in state.legacy_transitions],
        }
        if deep_error is not None:
            report["review_targets"] = self._unknown_review_targets(
                f"deep review evidence is unavailable: {deep_error}"
            )
            report["review_fronts"] = self._unknown_review_targets(f"deep review evidence is unavailable: {deep_error}")
        elif remote_recheck_error is not None:
            report["review_targets"] = self._unknown_review_targets(
                f"live remote branch heads could not be rechecked after deep reconciliation: {remote_recheck_error}"
            )
        elif changed_target_pr is not None:
            changed_reason = (
                "remote parent or head branch changed during deep reconciliation"
                if changed_target_pr in remote_affected
                else "live PR identity changed between batch overview and deep reconciliation"
            )
            unknown_targets = self._unknown_review_targets(changed_reason)
            positions = {pr: index for index, pr in enumerate(state.ordered_prs)}
            changed_position = positions[changed_target_pr]
            selected_targets = report["review_targets"]
            stable_targets = {}
            # A later child cannot invalidate a target already selected earlier
            # in the queue; a changed selected PR or earlier dependency still can.
            for channel in (policy.Channel.HOSTED.value, policy.Channel.CLI.value):
                target = selected_targets.get(channel)
                selected_pr = target.get("pr") if isinstance(target, Mapping) else None
                selected_position = (
                    positions.get(selected_pr)
                    if isinstance(selected_pr, int) and not isinstance(selected_pr, bool)
                    else None
                )
                if selected_position is not None and selected_position < changed_position:
                    stable_targets[channel] = target
                else:
                    stable_targets[channel] = unknown_targets[channel]
            report["review_targets"] = stable_targets
        if lifecycle_mismatch:
            # Opening/closing/merging a PR can change queue membership. Unlike
            # request-only ancestry movement, that makes the logical choice
            # uncertain from this inconsistent snapshot; never reselect here.
            positions = {pr: index for index, pr in enumerate(state.ordered_prs)}
            changed_position = min(positions[pr] for pr in lifecycle_mismatch)
            unknown_fronts = self._unknown_review_targets(
                "live PR lifecycle changed between batch overview and deep reconciliation"
            )
            for channel, front in report["review_fronts"].items():
                selected_position = positions.get(front.get("pr"))
                if selected_position is None or selected_position >= changed_position:
                    report["review_fronts"][channel] = unknown_fronts[channel]
        self._present_review_turns(report)
        return report

    def resolve_cli_target(self, expected_pr: int | None = None) -> ReviewTarget:
        selected = self._target(policy.Channel.CLI, expected_pr)
        self._ensure_runnable(selected, require_force_for_warnings=True)
        return selected.target

    def resolve_hosted_target(self, expected_pr: int | None = None) -> ReviewTarget:
        selected = self._target(policy.Channel.HOSTED, expected_pr)
        self._ensure_runnable(selected, require_force_for_warnings=True)
        return selected.target

    def select_target(self, channel: policy.Channel | str, expected_pr: int | None = None) -> dict[str, Any]:
        return self._target(channel, expected_pr).as_dict()

    @staticmethod
    def _selection_inputs(state: ReviewState, pr: int, channel: str) -> tuple[Any, ...]:
        """Snapshot only durable selection inputs through the chosen PR.

        Later PR bookkeeping cannot change which earlier PR should be admitted.
        Both channels' allocations affect the cross-channel evidence projection.
        This snapshot is ephemeral; the existing state remains the only authority.
        """

        prefix = state.ordered_prs[: state.ordered_prs.index(pr) + 1] if pr in state.ordered_prs else ()
        numbers = set(prefix)
        return (
            prefix,
            {key: value for key, value in state.allocations.items() if value.pr in numbers},
            {key: value for key, value in state.policy_overrides.items() if key in {f"{n}:{channel}" for n in numbers}},
            tuple(item for item in state.judgments if item.pr in numbers),
            tuple(item for item in state.reconciliations if item.pr in numbers),
            tuple(item for item in state.legacy_transitions if item.pr in numbers),
            tuple(item for item in state.summary_dispositions if item.pr in numbers and item.decision != "routed"),
        )

    def _admit_review(
        self, pr: int, channel: str, reserve: Callable[[], None], *, selection_inputs: tuple[Any, ...] | None = None
    ) -> None:
        """Serialize admission with human decisions, releasing before execution."""

        observed_allocation = self._state().allocations.get(f"{pr}:{channel}")
        cap_history = None
        if (
            observed_allocation is not None
            and observed_allocation.stop_basis is None
            and observed_allocation.max_additional_completed is not None
        ):
            # The adapter owns provider exclusion here; refresh outside the short mutation lock.
            refresh = getattr(self._evidence_provider, "admission_history", None)
            cap_history = (
                list(refresh(pr, channel))
                if callable(refresh)
                else _history(self._evidence_provider, pr, policy.Channel(channel))
            )

        def admit(state: ReviewState) -> ReviewState:
            allocation = state.allocations.get(f"{pr}:{channel}")
            if pr not in state.ordered_prs or (allocation is not None and allocation.stop_basis is not None):
                raise ControllerError(f"{channel} review discovery is stopped for PR #{pr}")
            if selection_inputs is not None and self._selection_inputs(state, pr, channel) != selection_inputs:
                raise _SelectionChanged("review selection changed before admission")
            if allocation != observed_allocation:
                raise _SelectionChanged("review allocation changed before admission")
            if cap_history is not None:
                snapshot = self._bounded_allocation_evidence(allocation, cap_history)
                if snapshot["error"] is not None or len(snapshot["results"]) >= allocation.max_additional_completed:
                    raise _SelectionChanged("review cap evidence changed before admission")
            reserve()
            return state

        budget = github.active_hosted_preflight_budget()
        try:
            if budget is None:
                self.store.update(admit)
            elif isinstance(self.store, (StateStore, ControllerStateStore)):
                self.store.update(admit, lock_deadline=budget.deadline)
            elif isinstance(self.store, SqliteStateStore):
                self.store.update(
                    admit,
                    deadline=budget.deadline,
                    deadline_active=lambda: budget.active,
                )
            else:
                self.store.update(admit)
        except StateLockTimeout as error:
            if budget is not None:
                try:
                    budget.remaining_seconds()
                except github.HostedPreflightDeadlineExceeded as deadline_error:
                    raise ControllerError(str(deadline_error)) from error
            raise ControllerError("could not acquire the review-stack state lock before Hosted admission") from error

    @staticmethod
    def _validate_force_options(force: bool, reason: str | None) -> None:
        if not isinstance(force, bool):
            raise ControllerError("--force must be a boolean acknowledgment")
        if reason is not None and not force:
            raise ControllerError("--reason is only valid with --force")
        if reason is not None and (not isinstance(reason, str) or len(reason) > 240):
            raise ControllerError("--reason must be 240 characters or fewer")
        if reason is not None and any(ord(character) < 32 for character in reason):
            raise ControllerError("--reason must not contain control characters")

    def run_hosted(
        self,
        *,
        expected_pr: int | None = None,
        force: bool = False,
        reason: str | None = None,
        **kwargs: Any,
    ) -> Any:
        self._validate_force_options(force, reason)
        with github.hosted_preflight_budget() as budget:
            try:
                budget.set_phase("target_selection")
                selected = self._target(policy.Channel.HOSTED, expected_pr)
                budget.set_phase("runnable_check")
                self._ensure_runnable(selected, force=force, require_force_for_warnings=True)
                if self.hosted_adapter is None:
                    raise ControllerError("Hosted adapter is not configured")
                for attempt in range(MAX_BASE_RESELECTIONS + 1):
                    if not self.isolated_fixture:
                        prepare_full_trigger(selected.pr, expected_pr)
                    try:
                        return self.hosted_adapter(
                            selected.target,
                            expect_pr=expected_pr,
                            force=force,
                            reason=reason,
                            admit=lambda reserve, selected=selected: self._admit_review(
                                selected.pr, "hosted", reserve, selection_inputs=selected.selection_inputs
                            ),
                            **kwargs,
                        )
                    except _SelectionChanged:
                        if attempt == MAX_BASE_RESELECTIONS:
                            raise
                        budget.set_phase("target_reselection")
                        selected = self._target(policy.Channel.HOSTED, expected_pr)
                        self._ensure_runnable(selected, force=force, require_force_for_warnings=True)
                    except StaleReviewTarget:
                        if not selected.target.default_base_front or attempt == MAX_BASE_RESELECTIONS:
                            raise
                        budget.set_phase("target_reselection")
                        selected = self._target(policy.Channel.HOSTED, selected.pr)
                        self._ensure_runnable(selected, force=force, require_force_for_warnings=True)
                    except HostedAdmissionBusy:
                        if attempt == MAX_BASE_RESELECTIONS:
                            raise
                        budget.set_phase("admission_retry")
                        time.sleep(
                            min(HOSTED_ADMISSION_RETRY_BASE_SECONDS * (attempt + 1), HOSTED_ADMISSION_RETRY_MAX_SECONDS)
                        )
                        selected = self._target(policy.Channel.HOSTED, expected_pr)
                        self._ensure_runnable(selected, force=force, require_force_for_warnings=True)
            except github.HostedPreflightDeadlineExceeded as error:
                raise ControllerError(str(error)) from error
        raise AssertionError("bounded Hosted reselection loop exhausted unexpectedly")

    def run_cli(
        self,
        *,
        expected_pr: int | None = None,
        force: bool = False,
        reason: str | None = None,
        **kwargs: Any,
    ) -> Any:
        self._validate_force_options(force, reason)
        with github.cli_preflight_budget() as budget:

            def preflight_phase(phase: str, operation: Callable[[], Any]) -> Any:
                budget.set_phase(phase, total=1)
                try:
                    result = operation()
                except github.HostedPreflightDeadlineExceeded:
                    raise
                except Exception as error:
                    try:
                        budget.remaining_seconds()
                    except github.HostedPreflightDeadlineExceeded as deadline_error:
                        raise deadline_error from error
                    raise
                budget.set_completed(1)
                return result

            try:
                selected = preflight_phase("target_selection", lambda: self._target(policy.Channel.CLI, expected_pr))
                for attempt in range(MAX_BASE_RESELECTIONS + 1):
                    preflight_phase(
                        "runnable_check",
                        lambda selected=selected: self._ensure_runnable(
                            selected, force=force, require_force_for_warnings=True
                        ),
                    )
                    if self.cli_adapter is None:
                        raise ControllerError("CLI adapter is not configured")
                    try:
                        return self.cli_adapter(
                            selected.target,
                            force=force,
                            reason=reason,
                            admit=lambda reserve, selected=selected: self._admit_review(
                                selected.pr, "cli", reserve, selection_inputs=selected.selection_inputs
                            ),
                            **kwargs,
                        )
                    except _SelectionChanged:
                        if attempt == MAX_BASE_RESELECTIONS:
                            raise
                        selected = preflight_phase(
                            "target_reselection", lambda: self._target(policy.Channel.CLI, expected_pr)
                        )
                    except StaleReviewTargetError as error:
                        if not selected.target.default_base_front or attempt == MAX_BASE_RESELECTIONS:
                            raise ControllerError(str(error)) from error
                        selected = preflight_phase(
                            "target_reselection",
                            lambda selected=selected: self._target(
                                policy.Channel.CLI,
                                selected.pr,
                                allow_cli_prefix_optimization=expected_pr is not None,
                            ),
                        )
            except github.HostedPreflightDeadlineExceeded as error:
                raise ControllerError(str(error)) from error
        raise AssertionError("bounded CLI reselection loop exhausted unexpectedly")

    def evidence(self, pr: int | None = None) -> dict[str, Any]:
        state = self._state()
        numbers = (pr,) if pr is not None else state.ordered_prs
        history_cache: dict[tuple[int, str], list[Any]] = {}
        eligible_prs = tuple(number for number in numbers if number in state.ordered_prs)
        canonical_allocations: dict[int, dict[str, Any]] = {}
        if any(
            f"{number}:{channel.value}" in state.allocations
            for number in eligible_prs
            for channel in (policy.Channel.HOSTED, policy.Channel.CLI)
        ):
            status_state = state
            selected_indexes = [index for index, number in enumerate(state.ordered_prs) if number in eligible_prs]
            if selected_indexes and len(eligible_prs) != len(state.ordered_prs):
                # A selected evidence read still needs the selected PR's complete
                # ancestor identity chain, but it must not ask _live_snapshots
                # to reconcile unrelated tail PRs without their batched IDs.
                status_state = dataclasses.replace(
                    state,
                    ordered_prs=state.ordered_prs[: max(selected_indexes) + 1],
                )
            status = self._status_from_state(
                status_state,
                history_cache=history_cache,
            )
            canonical_allocations = {
                row["pr"]: row.get("allocations", {})
                for row in status.get("prs", ())
                if isinstance(row, Mapping) and isinstance(row.get("pr"), int)
            }
        result: dict[str, Any] = {}
        for number in numbers:
            histories = {
                channel: [
                    dict(item)
                    if isinstance(item, Mapping)
                    else dataclasses.asdict(item)
                    if dataclasses.is_dataclass(item)
                    else policy.Evidence.from_value(item).__dict__
                    for item in self._cached_history(number, channel, history_cache)
                ]
                for channel in (policy.Channel.HOSTED, policy.Channel.CLI)
            }
            row: dict[str, Any] = {
                channel.value: histories[channel] for channel in (policy.Channel.HOSTED, policy.Channel.CLI)
            }
            allocations: dict[str, Any] = {}
            for channel in (policy.Channel.HOSTED, policy.Channel.CLI):
                allocation = state.allocations.get(f"{number}:{channel.value}")
                if allocation is None:
                    continue
                view = canonical_allocations.get(number, {}).get(channel.value)
                if view is None:
                    continue
                allocations[channel.value] = {
                    **view,
                    "baseline_checkpoint": allocation.baseline_checkpoint,
                    "reason": allocation.reason,
                    "allocation_reason": allocation.reason,
                    "progress_reason": view.get("reason"),
                    "evidence_error": view.get("reason") if view.get("status") == "INVALID" else None,
                }
            if allocations:
                row["allocations"] = allocations
            result[str(number)] = row
        return result

    def decide_judgment(
        self, *, pr: int, channel: str, decision: str, head: str, checkpoint: str, reason: str
    ) -> dict[str, Any]:
        checkpoint_head, patch_id = self._validate_decision_identity(
            pr,
            channel,
            head,
            checkpoint,
            allow_equivalent_history=True,
            allow_reopen_after_advance=decision == "reopen",
        )
        judgment = Judgment(pr, channel, decision, checkpoint_head, checkpoint, reason, patch_id)
        state = self.store.update(
            lambda current: dataclasses.replace(current, judgments=current.judgments + (judgment,))
        )
        return {"decision": judgment.to_dict(), "judgments": [item.to_dict() for item in state.judgments]}

    def decide_policy(
        self,
        *,
        pr: int,
        head: str,
        checkpoint: str,
        reason: str,
        hosted_zero_useful: int | None = None,
        cli_zero_useful: int | None = None,
    ) -> dict[str, Any]:
        channels = [
            name for name, value in (("hosted", hosted_zero_useful), ("cli", cli_zero_useful)) if value is not None
        ]
        if len(channels) != 1:
            raise ControllerError("a policy decision must set exactly one channel")
        selected_channel = channels[0]
        value = hosted_zero_useful if selected_channel == "hosted" else cli_zero_useful
        if value is None or not isinstance(value, int) or isinstance(value, bool) or value <= 0:
            raise ControllerError("a policy override must require at least one zero-useful review")
        normalized_head, patch_id = self._validate_decision_identity(
            pr, selected_channel, head, checkpoint, require_zero_useful=True
        )
        override = PolicyOverride(
            hosted_zero_useful,
            cli_zero_useful,
            normalized_head,
            checkpoint,
            reason,
            patch_id,
        )
        identity = f"{pr}:{channels[0]}"
        state = self.store.update(
            lambda current: dataclasses.replace(
                current, policy_overrides={**current.policy_overrides, identity: override}
            )
        )
        return {
            "pr": pr,
            "channel": channels[0],
            "policy_override": override.to_dict(),
            "policy_overrides": {k: v.to_dict() for k, v in state.policy_overrides.items()},
        }

    def decide_allocation(
        self,
        *,
        action: str,
        pr: int,
        channel: str,
        head: str,
        reason: str,
        checkpoint: str | None = None,
        min_additional_completed: int | None = None,
        max_additional_completed: int | None = None,
        exact_additional_completed: int | None = None,
        fresh_taper: bool = False,
    ) -> dict[str, Any]:
        """Grant, replace, or cancel a one-result or bounded review allocation."""

        if action not in {"grant", "renew", "cancel"}:
            raise ControllerError("allocation action must be grant, renew, or cancel")
        try:
            selected = policy.Channel(channel)
        except ValueError as exc:
            raise ControllerError("allocation channel must be hosted or cli") from exc
        if not isinstance(reason, str) or not reason.strip():
            raise ControllerError("review allocation decisions require a reason")
        if not isinstance(fresh_taper, bool):
            raise ControllerError("fresh taper selection must be boolean")
        if checkpoint is not None and (not isinstance(checkpoint, str) or not checkpoint.strip()):
            raise ControllerError("allocation baseline checkpoint must be a non-empty immutable identity")
        if exact_additional_completed is not None and (
            isinstance(exact_additional_completed, bool)
            or not isinstance(exact_additional_completed, int)
            or exact_additional_completed <= 0
        ):
            raise ControllerError("exact additional completed reviews must be a positive integer")
        if exact_additional_completed is not None and (
            min_additional_completed is not None or max_additional_completed is not None
        ):
            raise ControllerError("exact additional completed reviews cannot be combined with minimum or maximum")
        if exact_additional_completed is not None and fresh_taper:
            raise ControllerError("exact additional completed reviews preserve the existing taper")
        exact_replacement = exact_additional_completed is not None
        if exact_replacement:
            min_additional_completed = exact_additional_completed
            max_additional_completed = exact_additional_completed
        if min_additional_completed is not None and (
            isinstance(min_additional_completed, bool)
            or not isinstance(min_additional_completed, int)
            or min_additional_completed < 0
        ):
            raise ControllerError("minimum additional completed reviews must be a non-negative integer")
        if max_additional_completed is not None and (
            isinstance(max_additional_completed, bool)
            or not isinstance(max_additional_completed, int)
            or max_additional_completed <= 0
        ):
            raise ControllerError("maximum additional completed reviews must be a positive integer")
        if (
            min_additional_completed is not None
            and max_additional_completed is not None
            and min_additional_completed > max_additional_completed
        ):
            raise ControllerError("minimum additional completed reviews cannot exceed the maximum")
        bounded_replacement = min_additional_completed is not None or max_additional_completed is not None
        if checkpoint is not None and not bounded_replacement:
            raise ControllerError("--checkpoint requires a minimum or maximum allocation")
        if action == "cancel" and (checkpoint is not None or bounded_replacement or fresh_taper):
            raise ControllerError("cancel removes the existing allocation without a replacement policy")
        normalized_head = _sha(head, "allocation head")
        state = self._state()
        if pr not in state.ordered_prs:
            raise ControllerError(f"PR #{pr} is not in the configured review stack")
        identity = f"{pr}:{selected.value}"
        previous = state.allocations.get(identity)
        if action == "grant" and previous is not None:
            raise ControllerError("allocation already exists; use renew or cancel explicitly")
        if action != "grant" and previous is None:
            raise ControllerError("no allocation exists for this PR and channel")
        previous_is_bounded = previous is not None and (
            previous.min_additional_completed is not None or previous.max_additional_completed is not None
        )
        if action == "renew" and previous_is_bounded and not bounded_replacement:
            raise ControllerError("renewing a bounded allocation requires a new minimum or maximum")
        # Human allowance recording is policy, not request admission. Capture
        # the actual PR identity without requiring stack or local Git proof.
        item = _live(self._require_github().pull_request(pr), pr)
        try:
            merge_base = _sha(self.git.merge_base(item.base_tip, item.head), "merge base")
            patch_id = self.git.patch_identity(merge_base, item.head)
            if not isinstance(patch_id, str) or not patch_id.strip():
                raise ControllerError("Git provider returned an empty patch identity")
        except (ControllerError, OSError, ValueError, subprocess.SubprocessError):
            merge_base, patch_id = None, None
        parent_identity = item.base_ref
        try:
            for parent_pr in reversed(state.ordered_prs[: state.ordered_prs.index(pr)]):
                parent = _live(self._require_github().pull_request(parent_pr), parent_pr)
                if not parent.merged:
                    parent_identity = stack.ParentLink(pr, parent_pr, parent.head_ref, parent.head).identity
                    break
        except (ControllerError, KeyError, OSError, RuntimeError, TypeError, ValueError, subprocess.SubprocessError):
            pass
        current = AnchorFacts(pr, item.head, parent_identity, item.base_tip, merge_base, patch_id)
        if normalized_head != item.head:
            raise ControllerError("allocation head does not match the live pull-request head")
        if self._head_repository_problem(item):
            raise ControllerError(f"PR #{pr} has an unsupported head repository")

        def update_allocation(current_state: ReviewState, replacement: ReviewAllocation | None) -> ReviewState:
            if current_state.allocations.get(identity) != previous:
                raise ControllerError("review allocation changed concurrently; reread before deciding")
            values = dict(current_state.allocations)
            if replacement is None:
                values.pop(identity, None)
            else:
                values[identity] = replacement
            return dataclasses.replace(current_state, allocations=values)

        if action == "cancel":
            updated = self.store.update(lambda current_state: update_allocation(current_state, None))
            return {"action": action, "pr": pr, "channel": selected.value, "allocations": list(updated.allocations)}

        history = _history(self._evidence_provider, pr, selected)
        reopens_taper = fresh_taper

        def admitted_in_flight_request(value: Any) -> bool:
            if _field(value, "channel") not in (None, selected.value):
                return False
            # Counting retains the immutable identity captured at admission,
            # even when the live head or parent has since moved. Execution
            # still requires its separate current identity/admission checks.
            captured = _field(value, "anchor") if selected == policy.Channel.HOSTED else value
            request_anchor = AnchorFacts(
                pr,
                _field(captured, "child_head"),
                _field(captured, "parent_identity"),
                _field(captured, "parent_head"),
                _field(captured, "merge_base"),
                _field(captured, "patch_id"),
            )
            if not (
                all(
                    isinstance(value, str) and re.fullmatch(r"[0-9a-fA-F]{40}", value) is not None
                    for value in (request_anchor.child_head, request_anchor.parent_head, request_anchor.merge_base)
                )
                and isinstance(request_anchor.parent_identity, str)
                and bool(request_anchor.parent_identity.strip())
                and isinstance(request_anchor.patch_id, str)
                and bool(request_anchor.patch_id.strip())
            ):
                return False
            if selected == policy.Channel.HOSTED:
                if self._hosted_active_response_overlaps_cli(
                    value,
                    pr,
                    request_anchor.child_head,
                    request_anchor,
                    require_response_identity=True,
                ):
                    return True
                return self._hosted_awaiting_response_matches(value, pr, request_anchor)
            return self._active_cli_review_overlaps_hosted(value, pr, request_anchor)

        baseline = tuple(
            _field(value, "checkpoint", "checkpoint_id")
            for value in history
            if _field(value, "correction") is not True and not admitted_in_flight_request(value)
        )
        if any(not isinstance(value, str) or not value for value in baseline):
            raise ControllerError("existing review evidence lacks a checkpoint identity")
        if len(baseline) != len(set(baseline)):
            raise ControllerError("existing checkpoint identities are ambiguous")
        allocation = ReviewAllocation(
            pr=pr,
            channel=selected.value,
            head=item.head,
            parent_identity=current.parent_identity,
            parent_head=current.parent_head,
            merge_base=current.merge_base,
            patch_id=current.patch_id,
            baseline_checkpoints=baseline,
            reason=reason,
            baseline_checkpoint=checkpoint,
            min_additional_completed=min_additional_completed,
            max_additional_completed=max_additional_completed,
            reopens_taper=reopens_taper,
        )
        progress = None
        if bounded_replacement:
            progress = self._bounded_allocation_progress(
                allocation,
                history,
                current,
                stack.ReconciliationStatus.UNRECONCILED,
                state=state,
                reconciliation_result=None,
                stop_audit_cache={},
                history_cache={},
            )
        updated = self.store.update(lambda current_state: update_allocation(current_state, allocation))
        result = {
            "action": action,
            "allocation": allocation.to_dict(),
            "allocations": list(updated.allocations),
        }
        if progress is not None:
            result["progress"] = progress
        return result

    def decide_stop(
        self,
        *,
        pr: int,
        channel: str,
        reason: str,
        head: str | None = None,
        checkpoint: str | None = None,
        retain_ambiguous_fingerprints: Sequence[str] = (),
        ambiguity_reason: str | None = None,
        acknowledge_over_ceiling: bool = False,
    ) -> dict[str, Any]:
        """Record a direct human stop or consume a granted one-result allocation."""

        try:
            selected = policy.Channel(channel)
        except ValueError as exc:
            raise ControllerError("review stop channel must be hosted or cli") from exc
        normalized_reason = self._stop_reason(reason)
        normalized_head = _sha(head, "review stop head") if head is not None else None
        if normalized_reason.startswith("[acknowledged current over-ceiling limitation] "):
            raise ControllerError("review stop reason uses a reserved acknowledgment prefix")
        if not isinstance(acknowledge_over_ceiling, bool):
            raise ControllerError("over-ceiling acknowledgment must be explicit")
        if acknowledge_over_ceiling and normalized_head is None:
            raise ControllerError("over-ceiling acknowledgment requires the exact live head")
        if not isinstance(retain_ambiguous_fingerprints, Sequence) or isinstance(
            retain_ambiguous_fingerprints, (str, bytes)
        ):
            raise ControllerError("retained ambiguity fingerprints must be a sequence")
        retained_fingerprint_values = tuple(retain_ambiguous_fingerprints)
        if bool(retained_fingerprint_values) != (ambiguity_reason is not None):
            raise ControllerError("terminal ambiguity retention requires exact fingerprints and a reason")
        # Stop recording changes future eligibility only. Provider execution
        # and capture retain their own locks; store.update serializes this decision.
        state = self._state()
        if pr not in state.ordered_prs:
            raise ControllerError(f"PR #{pr} is not in the configured review stack")
        identity = f"{pr}:{selected.value}"
        previous = state.allocations.get(identity)
        if acknowledge_over_ceiling and previous is not None:
            raise ControllerError("over-ceiling acknowledgment is available only to a direct human stop")
        if previous is not None and previous.handoff_checkpoint is not None:
            raise ControllerError("this channel already has a legacy completed handoff")
        item = _live(self._require_github().pull_request(pr), pr)
        if normalized_head is not None and normalized_head.casefold() != item.head.casefold():
            raise ControllerError("review stop head does not match the live pull-request head")
        # Record the actual PR base even when the configured parent has moved.
        # Local Git proof is optional audit context, never a stop prerequisite.
        try:
            merge_base = self.git.merge_base(item.base_tip, item.head)
            patch_id = self.git.patch_identity(merge_base, item.head)
        except (ControllerError, OSError, ValueError, subprocess.SubprocessError):
            merge_base, patch_id = None, None
        parent_link = None
        try:
            _, reconciliation = self._reconciliation(state, evidence_prs=set())
            parent_link = reconciliation.links.get(pr)
        except (
            ControllerError,
            KeyError,
            OSError,
            RuntimeError,
            TypeError,
            ValueError,
            subprocess.SubprocessError,
        ):
            pass
        current = AnchorFacts(
            pr,
            item.head,
            parent_link.identity if parent_link is not None else item.base_ref,
            item.base_tip,
            merge_base,
            patch_id,
            stop_audit_pr_base_oid=item.base_tip,
            stop_audit_base_ref=item.base_ref,
        )
        basis = previous.stop_basis if previous is not None and previous.stop_basis is not None else "direct_human"
        if acknowledge_over_ceiling and basis != "direct_human":
            raise ControllerError("over-ceiling acknowledgment is available only to a direct human stop")
        histories = {
            selected_channel: _history(self._evidence_provider, pr, selected_channel)
            for selected_channel in (policy.Channel.HOSTED, policy.Channel.CLI)
        }
        history = histories[selected]
        # A prior result is optional audit context. Never label an active or
        # unattributable review as completed just to record the human override.
        try:
            latest, _ = self._latest_stop_checkpoint(pr, selected, history, checkpoint)
        except ControllerError:
            if checkpoint is not None:
                raise
            latest = None
        retained_fingerprints, retained_reason = (), None
        if retained_fingerprint_values:
            try:
                selected_base_ref_tip = _sha(self.git.branch_head(item.base_ref), "selected pull-request base ref tip")
            except (ControllerError, OSError, RuntimeError, ValueError, subprocess.SubprocessError) as error:
                raise ControllerError(
                    "selected pull-request base ref tip is unavailable for ambiguity audit"
                ) from error
            current = dataclasses.replace(
                current,
                stop_audit_effective_parent_head=selected_base_ref_tip,
                stop_audit_enforce_parent_identity_ref=False,
            )
            audit = self._stop_audit(
                pr,
                current,
                retained_fingerprint_values,
                allow_historical_unmatched=True,
                allow_historical_terminal_ambiguity=True,
            )
            retained_fingerprints, retained_reason = self._stop_ambiguity_pins(
                histories, retained_fingerprint_values, ambiguity_reason, audit
            )
        if acknowledge_over_ceiling:
            markers = {}
            for values in histories.values():
                for value in values:
                    checkpoint_id = _field(value, "checkpoint")
                    if isinstance(checkpoint_id, str) and checkpoint_id.startswith("over-ceiling:"):
                        fingerprint = observation_fingerprint(value)
                        if checkpoint_id in markers and markers[checkpoint_id] != fingerprint:
                            raise ControllerError("conflicting duplicate over-ceiling checkpoint metadata")
                        markers[checkpoint_id] = fingerprint
            if not any(
                _field(value, "over_ceiling") is True and _field(value, "head", "reviewed_head") == current.child_head
                for values in histories.values()
                for value in values
            ):
                raise ControllerError("over-ceiling acknowledgment requires current-head over-ceiling evidence")
        if previous is not None and previous.stop_basis is not None:
            raise ControllerError("review discovery is already stopped for this PR and channel")
        if (
            previous is not None
            and previous.min_additional_completed is None
            and previous.max_additional_completed is None
        ):
            completed_since_grant = [
                value
                for value in history
                if _field(value, "checkpoint") not in previous.baseline_checkpoints
                and _field(value, "completed") is True
                and _field(value, "attributable") is True
                and _field(value, "anchored") is True
                and _field(value, "provisional") is not True
                and _field(value, "non_counting") is not True
                and _field(value, "correction") is not True
            ]
            if (
                len(completed_since_grant) == 1
                and latest is not None
                and merge_base is not None
                and patch_id is not None
            ):
                basis = "allocated"

        if previous is not None:
            original_values = dataclasses.asdict(previous)
        else:
            checkpoints = (_field(latest, "checkpoint", "checkpoint_id"),) if latest is not None else ()
            original_values = {
                "pr": pr,
                "channel": selected.value,
                "head": current.child_head,
                "parent_identity": current.parent_identity,
                "parent_head": current.parent_head,
                "merge_base": current.merge_base,
                "patch_id": current.patch_id,
                "baseline_checkpoints": checkpoints,
                "reason": normalized_reason,
            }
        stopped = ReviewAllocation(
            **{
                **original_values,
                "stop_basis": basis,
                "stop_checkpoint": _field(latest, "checkpoint", "checkpoint_id"),
                "stop_reviewed_head": _field(latest, "head", "reviewed_head"),
                "stop_reviewed_patch_id": _field(latest, "patch_id", "patch_identity"),
                "stop_head": current.child_head,
                "stop_parent_identity": current.parent_identity,
                "stop_parent_head": current.parent_head,
                "stop_merge_base": current.merge_base,
                "stop_patch_id": current.patch_id,
                "stop_reason": (
                    f"[acknowledged current over-ceiling limitation] {normalized_reason}"
                    if acknowledge_over_ceiling
                    else normalized_reason
                ),
                "stop_summary_disposition_fingerprints": self._stop_summary_fingerprints(state, pr),
                "retained_ambiguous_fingerprints": retained_fingerprints,
                "retained_ambiguous_reason": retained_reason,
            }
        )

        def persist(current_state: ReviewState) -> ReviewState:
            if current_state.ordered_prs != state.ordered_prs or current_state.allocations.get(identity) != previous:
                raise ControllerError("review stack or allocation changed concurrently; reread before stopping")
            values = dict(current_state.allocations)
            values[identity] = stopped
            return dataclasses.replace(current_state, allocations=values)

        updated = self.store.update(persist)
        return {
            "action": "stop",
            "allocation": stopped.to_dict(),
            "allocations": list(updated.allocations),
            "checkpoint": _field(latest, "checkpoint", "checkpoint_id"),
            "reviewed_head": _field(latest, "head", "reviewed_head"),
            "stop_head": current.child_head,
            "stop_basis": basis,
            "reason": normalized_reason,
            "retained_ambiguous_fingerprints": list(retained_fingerprints),
        }

    def _validate_decision_identity(
        self,
        pr: int,
        channel: str,
        head: str,
        checkpoint: str,
        *,
        require_zero_useful: bool = False,
        allow_equivalent_history: bool = False,
        allow_reopen_after_advance: bool = False,
    ) -> tuple[str, str]:
        try:
            selected_channel = policy.Channel(channel)
        except ValueError as exc:
            raise ControllerError("decision channel must be hosted or cli") from exc
        state = self._state()
        if pr not in state.ordered_prs:
            raise ControllerError(f"PR #{pr} is not in the configured review stack")
        live, reconciliation = self._reconciliation(state)
        current = live[pr]
        normalized_head = _sha(head, "decision head")
        if normalized_head != current.head:
            raise ControllerError("decision head does not match the live pull-request head")
        link = reconciliation.links[pr]
        anchor = self._anchor(pr, current, link)
        reconciliation_status = reconciliation.status_for(pr, selected_channel.value)
        history = self._policy_history(state, pr, selected_channel, reconciliation)
        parsed_history = [policy.Evidence.from_value(item) for item in history]
        effective_reviews: list[tuple[int, policy.Evidence]] = []
        for index, item in enumerate(parsed_history):
            if (
                item.non_counting
                or item.correction
                or item.completed is not True
                or item.attributable is not True
                or item.provisional
            ):
                continue
            if item.pr != pr:
                raise ControllerError("decision evidence is attributable to another pull request")
            effective_reviews.append((index, item))
        matching = [
            item
            for item in parsed_history
            if item.checkpoint == checkpoint and item.pr == pr and not item.correction and not item.non_counting
        ]
        if not matching:
            raise ControllerError("decision checkpoint is not attributable to the pull request")
        checkpoint_evidence = matching[-1]
        if not (
            checkpoint_evidence.completed is True
            and checkpoint_evidence.attributable is True
            and checkpoint_evidence.anchored is True
            and checkpoint_evidence.provisional is False
        ):
            raise ControllerError("decision checkpoint must be completed attributable anchored evidence")
        if not effective_reviews or checkpoint_evidence.checkpoint != effective_reviews[-1][1].checkpoint:
            raise ControllerError("decision checkpoint must be the latest policy-effective completed review")
        latest_effective_index = effective_reviews[-1][0]
        if any(
            item.provisional and item.head == checkpoint_evidence.head
            for item in parsed_history[latest_effective_index + 1 :]
        ):
            raise ControllerError("decision checkpoint is blocked by a later provisional review")
        checkpoint_head = _sha(checkpoint_evidence.head, "decision checkpoint head")
        reopen_after_advance = (
            allow_reopen_after_advance and selected_channel == policy.Channel.CLI and checkpoint_head != normalized_head
        )
        if checkpoint_head != normalized_head:
            if reopen_after_advance:
                if reconciliation_status in {
                    stack.ReconciliationStatus.PARENT_MOVED,
                    stack.ReconciliationStatus.UNRECONCILED,
                }:
                    raise ControllerError("reopen judgment requires coherent live topology")
                raw_checkpoint = next(
                    (item for item in reversed(history) if _field(item, "checkpoint", "checkpoint_id") == checkpoint),
                    None,
                )
                anchored_review = (
                    self._cli_streak_review(raw_checkpoint, pr)
                    if selected_channel == policy.Channel.CLI and raw_checkpoint is not None
                    else None
                )
                if anchored_review is None or anchored_review[1] is None:
                    raise ControllerError("reopen judgment requires a complete modern CLI checkpoint anchor")
                try:
                    is_descendant = self.git.is_ancestor(checkpoint_head, current.head)
                except (ControllerError, OSError, subprocess.SubprocessError, ValueError) as error:
                    raise ControllerError("could not verify the live reopen head's checkpoint ancestry") from error
                if is_descendant is not True:
                    raise ControllerError("reopen judgment requires the live head to descend from its checkpoint")
            else:
                if reconciliation_status in {
                    stack.ReconciliationStatus.PARENT_MOVED,
                    stack.ReconciliationStatus.UNRECONCILED,
                }:
                    raise ControllerError("equivalent-history judgment requires coherent live topology")
                if (
                    not allow_equivalent_history
                    or reconciliation_status != stack.ReconciliationStatus.EQUIVALENT_HISTORY
                ):
                    raise ControllerError("decision checkpoint head must match the live head or equivalent history")
        if checkpoint_evidence.patch_id != anchor.patch_id and not reopen_after_advance:
            raise ControllerError("decision checkpoint patch identity does not match the live stack anchor")
        if require_zero_useful and (
            checkpoint_evidence.corrected_state is not True or checkpoint_evidence.accepted != 0
        ):
            raise ControllerError("policy override requires a corrected-state zero-useful checkpoint")
        return checkpoint_head, checkpoint_evidence.patch_id if reopen_after_advance else anchor.patch_id

    def decide(self, operation: str, **kwargs: Any) -> dict[str, Any]:
        if operation in {"retain", "reopen"}:
            return self.decide_judgment(decision=operation, **kwargs)
        if operation == "policy":
            return self.decide_policy(**kwargs)
        if operation in {"transition", "legacy-transition"}:
            return self.decide_legacy_transition(**kwargs)
        if operation == "reconcile":
            return self.decide_reconciliation(**kwargs)
        raise ControllerError("decision must be retain, reopen, policy, transition, or reconcile")


def compact_result(value: Mapping[str, Any]) -> str:
    """Render a deterministic operator-friendly result without losing structure."""

    return "\n".join(f"{key}={value[key]}" for key in sorted(value))


def json_result(value: Mapping[str, Any]) -> dict[str, Any]:
    """Return a JSON-compatible copy of a controller result."""

    return {str(key): item for key, item in value.items()}
