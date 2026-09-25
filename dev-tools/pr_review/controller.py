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

import contextlib
import dataclasses
import fcntl
import re
import subprocess
from collections.abc import Callable, Iterable, Mapping, Sequence
from pathlib import Path
from typing import Any, Protocol

from . import policy, stack
from .cli_runner import (
    EffectiveParent,
    PullRequestSnapshot,
    ReviewTarget,
    StaleReviewTargetError,
)
from .git_merge import TestMergeError, test_merge_tree
from .hosted import default_trigger_record_path, parse_timestamp, prepare_full_trigger
from .patch_identity import patch_identity
from .state import (
    Judgment,
    LegacyEvidenceTransition,
    PolicyOverride,
    ReviewAllocation,
    ReviewState,
    StackReconciliationDecision,
    StateStore,
    observation_fingerprint,
)


class ControllerError(RuntimeError):
    """A fail-closed controller preflight or decision error."""


class WrongStackTarget(ControllerError):
    """The requested PR is not the target selected by the configured stack."""


class StaleReviewTarget(ControllerError):
    """A direct-to-default target's base advanced before review could begin."""


GIT_TIMEOUT_SECONDS = 30
MAX_BASE_RESELECTIONS = 2
LEGACY_UNCHECKPOINTED = re.compile(r"^trigger-uncheckpointed:[1-9][0-9]*$")


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
    ) -> subprocess.CompletedProcess[str] | subprocess.CompletedProcess[bytes]:
        try:
            return subprocess.run(
                list(args),
                check=check,
                capture_output=capture_output,
                text=text,
                timeout=self.timeout_seconds,
            )
        except subprocess.TimeoutExpired as error:
            raise ControllerError(f"git command timed out after {self.timeout_seconds} seconds") from error

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
            del timeout
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
        )


@dataclasses.dataclass(frozen=True)
class AnchorFacts:
    """Current identity used to bind policy evidence to the stack."""

    pr: int
    child_head: str
    parent_identity: str
    parent_head: str
    merge_base: str
    patch_id: str

    def as_dict(self) -> dict[str, Any]:
        return dataclasses.asdict(self)

    def as_anchor(self) -> stack.ReviewAnchor:
        return stack.ReviewAnchor(**self.as_dict())


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

    def as_dict(self) -> dict[str, Any]:
        return {
            "channel": self.channel.value,
            "pr": self.pr,
            "status": self.status.value,
            "reason": self.reason,
            "provisional": self.provisional,
            "anchor": self.anchor.as_dict(),
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
        if type(raw) is not int or type(accepted) is not int or raw < 0 or not 0 <= accepted <= raw:
            continue
        reviewed_head = _field(item, "head", "reviewed_head")
        results.append(
            {
                "raw": raw,
                "accepted": accepted,
                "attributable": _field(item, "attributable") is True,
                "current_head": isinstance(reviewed_head, str) and reviewed_head == current_head,
                "non_counting": _field(item, "non_counting") is True,
            }
        )
    return {"total": len(results), "recent": results[-5:]}


def _field(value: Any, name: str, *aliases: str) -> Any:
    if isinstance(value, Mapping):
        for key in (name, *aliases):
            if key in value:
                return value[key]
    return getattr(value, name, None)


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
        return self.store.load()

    @staticmethod
    def _legacy_transition_for(
        state: ReviewState, pr: int, anchor: AnchorFacts
    ) -> LegacyEvidenceTransition | None:
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
        """Do not trust a caller-supplied policy projection marker."""

        if isinstance(value, Mapping):
            cleared = dict(value)
            cleared.pop("non_counting", None)
            return cleared
        if isinstance(value, policy.Evidence):
            return dataclasses.replace(value, non_counting=False)
        if dataclasses.is_dataclass(value) and hasattr(value, "non_counting"):
            return dataclasses.replace(value, non_counting=False)
        return value

    def _counting_history_for_anchor(
        self, state: ReviewState, pr: int, channel: policy.Channel, anchor: AnchorFacts
    ) -> list[Any]:
        """Return only observations not captured by the exact legacy transition."""

        history = _history(self._evidence_provider, pr, channel)
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
    ) -> list[Any]:
        """Project exact legacy observations as non-counting policy history.

        The raw evidence remains available through ``evidence``.  A transition
        only marks records whose immutable fingerprint was captured by that
        exact current anchor; a later, altered, or otherwise new observation is
        deliberately left visible to policy and can still block review.
        """

        history = _history(self._evidence_provider, pr, channel)
        selected = set(
            reconciliation.legacy_transition_fingerprints.get(pr, {}).get(channel.value, ())
        )
        if not selected:
            return [self._clear_untrusted_non_counting(value) for value in history]
        projected: list[Any] = []
        for value in history:
            if observation_fingerprint(value) in selected:
                projected.append(self._mark_non_counting(value))
            else:
                projected.append(self._clear_untrusted_non_counting(value))
        return projected

    def set_stack(self, pr_numbers: Iterable[int]) -> dict[str, Any]:
        numbers = tuple(pr_numbers)
        if not numbers or any(isinstance(pr, bool) or not isinstance(pr, int) or pr <= 0 for pr in numbers):
            raise ControllerError("stack must contain positive pull-request numbers")
        if len(set(numbers)) != len(numbers):
            raise ControllerError("stack pull requests must be unique")
        if self.github is not None:
            if not self.repository:
                raise ControllerError("repository identity is required to validate stack head repositories")
            for pr in numbers:
                item = _live(self.github.pull_request(pr), pr)
                problem = self._head_repository_problem(item)
                if problem:
                    raise ControllerError(f"PR #{pr} {problem}")
        state = self.store.update(lambda current: dataclasses.replace(current, ordered_prs=numbers))
        return {"ordered_prs": list(state.ordered_prs), "schema_version": state.schema_version}

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
    ) -> tuple[dict[int, LivePullRequest], stack.Reconciliation]:
        remote_heads = self.git.remote_heads()
        live, snapshots, default_tip = self._live_snapshots(
            state,
            remote_heads,
            live_identities=live_identities,
            refresh_prs=refresh_prs,
        )
        selected_evidence_prs = set(state.ordered_prs if evidence_prs is None else evidence_prs)
        ancestry_errors: set[tuple[str, str]] = set()

        def is_ancestor(parent: str, child: str) -> bool:
            try:
                return self.git.is_ancestor(parent, child)
            except (ControllerError, OSError, subprocess.SubprocessError, ValueError):
                ancestry_errors.add((parent.casefold(), child.casefold()))
                return False

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
                merge_tree = self._test_merge_tree(item.base_tip, item.head)
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
        for pr in state.ordered_prs:
            if live[pr].merged or baseline.status_for(pr) != stack.ReconciliationStatus.COHERENT:
                continue
            link = baseline.links[pr]
            if not self._current_branches_match(state, live, baseline, pr, remote_heads):
                continue
            try:
                current = self._anchor(pr, live[pr], link)
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
            decision = (
                self._active_stack_reconciliation(state, pr, current)
                if pr in selected_evidence_prs
                else None
            )
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
                latest = _latest_review(self._counting_history_for_anchor(state, pr, channel, current))
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
                    current = self._anchor(pr, live[pr], link)
                except (ControllerError, OSError, subprocess.SubprocessError, ValueError) as error:
                    reason = f"could not compute current Git anchor: {type(error).__name__}: {error}"
                    anchor_failures[pr] = reason
                    set_anchor_status(pr, stack.ReconciliationStatus.UNRECONCILED, reason)
                    continue
                anchors[pr] = current
            if pr not in selected_evidence_prs:
                continue
            for channel in (policy.Channel.HOSTED, policy.Channel.CLI):
                latest = _latest_review(self._counting_history_for_anchor(state, pr, channel, current))
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
        return live, dataclasses.replace(
            result,
            status=overall,
            affected_descendants=tuple(pr for pr in state.ordered_prs if pr in moved),
            reasons=reasons,
            statuses=statuses,
            channel_statuses=channel_statuses,
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
                if (
                    item.head_repository is None
                    or item.head_repository.casefold() != self.repository.casefold()
                ):
                    return False
                if item.head_ref not in remote_heads:
                    return False
                if _sha(remote_heads[item.head_ref], f"PR #{candidate} head branch") != item.head:
                    return False
        return True

    def _checkpoint_for_reconciliation(
        self, pr: int, channel: policy.Channel, checkpoint: str, prior_head: str, current: AnchorFacts
    ) -> Any:
        for item in _history(self._evidence_provider, pr, channel):
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
        self, state: ReviewState, pr: int, current: AnchorFacts
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
        return AnchorFacts(pr, item.head, link.identity, link.parent_head, merge_base, patch_id)

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
            if (
                LEGACY_UNCHECKPOINTED.fullmatch(item.checkpoint) is None
                or item.completed
                or item.attributable
            ):
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
            raise ControllerError("legacy transition reauthorization requires exact modern evidence identity") from error
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
            raise ControllerError(
                "missing Hosted fingerprint retirement requires a complete paginated evidence audit"
            )
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
            values = tuple(_history(self._evidence_provider, pr, channel))
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
            if (
                retire_missing_hosted_fingerprint is None
                or newly_missing_hosted != {retire_missing_hosted_fingerprint}
            ):
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

    def _assert_transition_anchor_still_current(
        self, state: ReviewState, pr: int, expected: AnchorFacts
    ) -> None:
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
            raise ControllerError("PR head, parent, merge-base, or patch changed during missing Hosted fingerprint retirement")

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

    def _stop_lock_paths(self, pr: int) -> tuple[Path, Path]:
        """Return the same CLI and Hosted request locks used by review runners."""

        if self.store.path.name == "pr-review-stack.json" and self.store.path.parent.name == "firemud":
            common = self.store.path.parent.parent
            cli_path = common / "firemud" / "pr-review" / "cli.lock"
            hosted_path = default_trigger_record_path(self.repository, pr, common).parent / "request.lock"
        else:
            lock_root = self.store.path.parent / ".pr-review-stop-locks"
            cli_path = lock_root / "cli.lock"
            hosted_path = lock_root / f"hosted-pr-{pr}.lock"
        return cli_path, hosted_path

    @contextlib.contextmanager
    def _stop_review_locks(self, pr: int):
        """Prevent the stop decision from racing an active channel review."""

        handles = []
        try:
            for path in self._stop_lock_paths(pr):
                path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
                handle = path.open("a+")
                handles.append(handle)
                try:
                    fcntl.flock(handle.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
                except BlockingIOError as exc:
                    raise ControllerError(f"cannot stop review discovery while a review is active ({path.name})") from exc
            yield
        finally:
            for handle in reversed(handles):
                try:
                    fcntl.flock(handle.fileno(), fcntl.LOCK_UN)
                finally:
                    handle.close()

    @staticmethod
    def _stop_reason(reason: Any) -> str:
        if not isinstance(reason, str) or not reason.strip() or len(reason) > 500:
            raise ControllerError("review stop requires a non-empty reason of at most 500 characters")
        if any(ord(character) < 0x20 for character in reason):
            raise ControllerError("review stop reason must not contain control characters")
        return reason.strip()

    @staticmethod
    def _stop_reason_acknowledges_over_ceiling(reason: str | None) -> bool:
        return isinstance(reason, str) and reason.startswith(
            "[acknowledged current over-ceiling limitation] "
        )

    @staticmethod
    def _stop_summary_fingerprints(state: ReviewState, pr: int) -> tuple[str, ...]:
        return tuple(
            sorted(
                observation_fingerprint(item.to_dict())
                for item in state.summary_dispositions
                if item.pr == pr
            )
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
        acknowledged_over_ceiling_checkpoints: Sequence[str] = (),
    ) -> Mapping[str, Any]:
        provider = self._evidence_provider
        review_audit = getattr(provider, "review_stop_audit", None)
        if callable(review_audit):
            try:
                audit = review_audit(
                    pr,
                    current.as_dict(),
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
                audit = provider.legacy_transition_reauthorization_audit(
                    pr, (), current.as_dict()
                )
            except (OSError, ValueError, TypeError, KeyError) as exc:
                raise ControllerError(f"complete review-stop evidence is unavailable: {exc}") from exc
        else:
            raise ControllerError("review stop requires complete paginated evidence for both review channels")
        if not isinstance(audit, Mapping) or audit.get("complete") is not True:
            raise ControllerError("review stop requires complete paginated evidence for both review channels")
        if audit.get("head") is not None and str(audit.get("head")).casefold() != current.child_head.casefold():
            raise ControllerError("review-stop evidence audit observed a stale pull-request head")
        if audit.get("anchor") is not None and audit.get("anchor") != current.as_dict():
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
                or parse_timestamp(item.get("cooldown_until")) is None
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
        for field, description in (
            ("active_reservations", "active review or reservation"),
            ("unmatched_responses", "unmatched review response"),
            ("ambiguous_responses", "unresolved evidence ambiguity"),
            ("unresolved_findings", "unresolved actionable finding or thread"),
        ):
            values = normalized_audit.get(field, [])
            if not isinstance(values, Sequence) or isinstance(values, (str, bytes)):
                raise ControllerError(f"review-stop audit has malformed {field}")
            if field == "unresolved_findings" and acknowledged_over_ceiling_checkpoints:
                allowed = set(acknowledged_over_ceiling_checkpoints)
                if any(not isinstance(value, str) for value in values):
                    raise ControllerError("review-stop audit has malformed unresolved findings")
                values = [value for value in values if value not in allowed]
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
        historical_unmatched = audit.get("historical_unmatched_responses", [])
        if not isinstance(historical_unmatched, Sequence) or isinstance(historical_unmatched, (str, bytes)):
            raise ControllerError("review-stop audit has malformed historical unmatched-response evidence")
        if historical_unmatched and not allow_historical_unmatched:
            raise ControllerError("review stop is blocked by historical unmatched evidence outside a direct Hosted stop")
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
            value
            for history in histories.values()
            for value in history
            if _field(value, "terminal_ambiguous") is True
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
            any(not isinstance(value, str) or not re.fullmatch(r"[0-9a-f]{64}", value) for value in candidate_fingerprints)
            or len(set(candidate_fingerprints)) != len(candidate_fingerprints)
            or not set(candidate_fingerprints).issubset(set(fingerprints) | historical_fingerprints)
        ):
            raise ControllerError("retained ambiguity fingerprints must identify every current terminal response")
        audit_fingerprints = [
            item.get("fingerprint")
            for item in audit.get("retained_ambiguous", ())
            if isinstance(item, Mapping)
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
        stop_audit_cache: dict[tuple[Any, ...], Mapping[str, Any]] | None = None,
    ) -> tuple[Any, tuple[tuple[str, ...], str] | None, dict[policy.Channel, list[Any]]]:
        histories = {
            selected: self._policy_history(state, pr, selected, reconciliation)
            for selected in (policy.Channel.HOSTED, policy.Channel.CLI)
        }
        acknowledged_over_ceiling_checkpoints: tuple[str, ...] = ()
        if acknowledge_over_ceiling:
            acknowledged: list[str] = []
            for history in histories.values():
                for value in history:
                    checkpoint = _field(value, "checkpoint", "checkpoint_id")
                    if (
                        _field(value, "over_ceiling") is True
                        and isinstance(checkpoint, str)
                        and checkpoint.startswith("over-ceiling:")
                        and _field(value, "head", "reviewed_head") == current.child_head
                        and not any(
                            _field(value, flag) is True
                            for flag in (
                                "held", "unstable", "unreconciled", "parent_moved",
                                "rate_limited", "active_review", "active_reservation", "actionable",
                            )
                        )
                    ):
                        acknowledged.append(checkpoint)
            if not acknowledged or len(set(acknowledged)) != len(acknowledged):
                raise ControllerError(
                    "over-ceiling acknowledgment requires unique current-head over-ceiling evidence"
                )
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
        )
        if stop_audit_cache is not None and cache_key in stop_audit_cache:
            audit = stop_audit_cache[cache_key]
        else:
            audit = self._stop_audit(
                pr,
                current,
                retained_ambiguous_fingerprints,
                allow_historical_unmatched=allow_historical_unmatched,
                allow_historical_terminal_ambiguity=allow_historical_terminal_ambiguity,
                acknowledged_over_ceiling_checkpoints=acknowledged_over_ceiling_checkpoints,
            )
            if stop_audit_cache is not None:
                stop_audit_cache[cache_key] = audit
        retained_fingerprints, retained_reason = self._stop_ambiguity_pins(
            histories, retained_ambiguous_fingerprints, ambiguity_reason, audit
        )
        non_blocking_ambiguity_fingerprints = set(retained_fingerprints) | set(
            audit.get("historical_terminal_fingerprints", ())
        )
        non_blocking_rate_limits = {
            item["trigger_id"]: item
            for item in audit.get("terminal_rate_limits", ())
        } if allow_historical_terminal_ambiguity else {}
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
                parsed_history[last_valid_review_index].head
                if last_valid_review_index >= 0
                else None
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
                    relevant_heads = {current.child_head.casefold()}
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
                        reviewed_head = _field(value, "head", "reviewed_head")
                        try:
                            _sha(reviewed_head, "accepted finding reviewed head")
                            has_corrected_descendant = self.git.is_ancestor(reviewed_head, current.child_head)
                        except (ControllerError, OSError, subprocess.SubprocessError, ValueError) as exc:
                            raise ControllerError("could not verify corrected-head ancestry for accepted findings") from exc
                        if not has_corrected_descendant or reviewed_head.casefold() == current.child_head.casefold():
                            raise ControllerError(
                                "accepted findings need a published corrected head before review can stop"
                            )
                blocker_flags = (
                    "held",
                    "unstable",
                    "unreconciled",
                    "parent_moved",
                    "over_ceiling",
                )
                if any(_field(value, flag) is True for flag in blocker_flags) or (
                    not audited_terminal_rate_limit and _field(value, "rate_limited") is True
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
                                "held", "unstable", "unreconciled", "parent_moved",
                                "rate_limited", "active_review", "active_reservation", "actionable",
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
        try:
            is_ancestor = self.git.is_ancestor(reviewed_head, current.child_head)
        except (OSError, subprocess.SubprocessError, ValueError) as exc:
            raise ControllerError("could not verify latest reviewed-head ancestry") from exc
        equivalent_retain = self._has_stop_retain_judgment(
            state,
            pr,
            channel.value,
            _field(latest, "checkpoint", "checkpoint_id"),
            reviewed_head,
            current.patch_id,
            reconciliation.status_for(pr, channel.value),
        ) and _field(latest, "patch_id", "patch_identity") == current.patch_id
        if not is_ancestor and not equivalent_retain:
            raise ControllerError("latest reviewed head is not an ancestor of the live stop head")
        accepted = _field(latest, "accepted")
        if type(accepted) is not int or accepted < 0:
            raise ControllerError("latest attributable checkpoint has a malformed accepted count")
        reviewed_head = _field(latest, "head", "reviewed_head")
        if accepted > 0 and reviewed_head.casefold() == current.child_head.casefold():
            raise ControllerError("accepted findings need a published corrected head before review can stop")
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
        if current is None or reconciliation in {
            stack.ReconciliationStatus.PARENT_MOVED,
            stack.ReconciliationStatus.UNRECONCILED,
        }:
            return invalid("the stopped stack identity is no longer coherent")
        equivalent_retain = self._has_stop_retain_judgment(
            state,
            allocation.pr,
            allocation.channel,
            allocation.stop_checkpoint,
            allocation.stop_reviewed_head,
            current.patch_id,
            reconciliation,
        )
        if current.patch_id != allocation.stop_patch_id or (
            current.child_head != allocation.stop_head and not equivalent_retain
        ):
            return invalid("the stopped head or owned patch changed after the decision")
        topology_matches = (
            current.parent_identity == allocation.stop_parent_identity
            and current.parent_head == allocation.stop_parent_head
            and current.merge_base == allocation.stop_merge_base
        )
        if not topology_matches and not equivalent_retain:
            return invalid("parent reconciliation needs an explicit unchanged-patch retain judgment")
        if self._stop_summary_fingerprints(state, allocation.pr) != allocation.stop_summary_disposition_fingerprints:
            return invalid("new summary-finding decisions require a fresh review-stop judgment")
        if reconciliation_result is None:
            return invalid("the complete reconciled stack identity is unavailable")
        try:
            latest, _, _ = self._check_stop_evidence(
                state,
                allocation.pr,
                policy.Channel(allocation.channel),
                current,
                reconciliation_result,
                checkpoint_pin=allocation.stop_checkpoint,
                allow_historical_unmatched=allocation.stop_basis == "direct_human",
                allow_historical_terminal_ambiguity=allocation.stop_basis == "direct_human",
                retained_ambiguous_fingerprints=allocation.retained_ambiguous_fingerprints,
                ambiguity_reason=allocation.retained_ambiguous_reason,
                acknowledge_over_ceiling=self._stop_reason_acknowledges_over_ceiling(
                    allocation.stop_reason
                ),
                stop_audit_cache=stop_audit_cache,
            )
        except ControllerError as exc:
            return invalid(str(exc))
        if (
            _field(latest, "head", "reviewed_head") != allocation.stop_reviewed_head
            or _field(latest, "patch_id", "patch_identity") != allocation.stop_reviewed_patch_id
        ):
            return invalid("the recorded review checkpoint identity changed")
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

        return (
            reconciliation == stack.ReconciliationStatus.EQUIVALENT_HISTORY
            and any(
                item.pr == pr
                and item.channel == channel
                and item.decision == "retain"
                and item.head == reviewed_head
                and item.checkpoint == checkpoint
                and item.patch_id == current_patch_id
                for item in state.judgments
            )
        )

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
    ) -> dict[str, Any]:
        """Derive consumption from immutable evidence; never persist a live observation."""

        def result(status: str, reason: str, checkpoint: str | None = None, accepted: int | None = None) -> dict[str, Any]:
            return {
                "status": status,
                "reason": reason,
                "promised_head": allocation.head,
                "checkpoint": checkpoint,
                "accepted": accepted,
                "handoff_head": allocation.handoff_head,
                "stop_basis": allocation.stop_basis or ("allocated" if allocation.handoff_checkpoint else None),
            }

        if allocation.stop_basis == "direct_human":
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
            )

        if current is None or reconciliation in {
            stack.ReconciliationStatus.PARENT_MOVED,
            stack.ReconciliationStatus.UNRECONCILED,
        }:
            return result("INVALID", "the current stack identity is not coherent; renew or cancel the allocation")
        if (
            current.parent_identity != allocation.parent_identity
            or current.parent_head != allocation.parent_head
            or current.merge_base != allocation.merge_base
        ):
            return result("INVALID", "the allocated parent or merge base moved; renewed judgment is required")

        baseline = set(allocation.baseline_checkpoints)
        subsequent = [
            item for item in history
            if _field(item, "correction") is not True
            and _field(item, "checkpoint", "checkpoint_id") not in baseline
        ]
        checkpoints = [_field(item, "checkpoint", "checkpoint_id") for item in subsequent]
        if any(not isinstance(value, str) or not value for value in checkpoints) or len(checkpoints) != len(set(checkpoints)):
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
                    for flag in ("rate_limited", "held", "unstable", "unreconciled", "parent_moved", "over_ceiling")
                )
            ):
                continue
            if (
                _field(item, "pr") == allocation.pr
                and _field(item, "channel") in (None, allocation.channel)
                and _field(item, "head", "reviewed_head") == allocation.head
                and _field(item, "child_head") == allocation.head
                and _field(item, "parent_identity") == allocation.parent_identity
                and _field(item, "parent_head") == allocation.parent_head
                and _field(item, "merge_base") == allocation.merge_base
                and _field(item, "patch_id", "patch_identity") == allocation.patch_id
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
            if current.child_head != allocation.head or current.patch_id != allocation.patch_id:
                return result("INVALID", "the promised head or patch changed before a matching review")
            return result("PROMISED", "waiting for one completed attributable review of the promised head")

        review = matching[0]
        checkpoint = _field(review, "checkpoint", "checkpoint_id")
        accepted = _field(review, "accepted")
        if type(accepted) is not int or accepted < 0:
            return result("INVALID", "completed review has an invalid accepted-finding count")
        if current.child_head != allocation.head:
            try:
                descends_from_reviewed_head = self.git.is_ancestor(allocation.head, current.child_head)
            except (ControllerError, OSError, subprocess.SubprocessError, ValueError):
                return result(
                    "INVALID",
                    "could not verify corrected-head ancestry; renew or cancel the allocation",
                    checkpoint,
                    accepted,
                )
            if not descends_from_reviewed_head:
                return result("INVALID", "the corrected head does not descend from the reviewed head", checkpoint, accepted)
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
            return result("EXHAUSTED_PENDING", "current review, finding, or thread obligations remain", checkpoint, accepted)
        if any(
            _field(item, "completed") is True
            and _field(item, "attributable") is True
            and _field(item, "provisional") is not True
            and _field(item, "correction") is not True
            and _field(item, "head", "reviewed_head") == current.child_head
            and type(_field(item, "accepted")) is int
            and _field(item, "accepted") > 0
            for item in history
        ):
            return result("EXHAUSTED_PENDING", "accepted findings need a published corrected head", checkpoint, accepted)
        if allocation.stop_basis == "allocated":
            if state is None or reconciliation_result is None:
                return result("INVALID", "the persisted review-stop state is unavailable", checkpoint, accepted)
            if allocation.stop_checkpoint != checkpoint:
                return result("INVALID", "the consumed checkpoint no longer matches the recorded stop", checkpoint, accepted)
            if (
                current.child_head != allocation.stop_head
                or current.patch_id != allocation.stop_patch_id
                or current.parent_identity != allocation.stop_parent_identity
                or current.parent_head != allocation.stop_parent_head
                or current.merge_base != allocation.stop_merge_base
            ):
                return result("INVALID", "the stopped head or stack identity changed", checkpoint, accepted)
            if self._stop_summary_fingerprints(state, allocation.pr) != allocation.stop_summary_disposition_fingerprints:
                return result("INVALID", "new summary-finding decisions require a fresh stop judgment", checkpoint, accepted)
            try:
                latest, _, _ = self._check_stop_evidence(
                    state,
                    allocation.pr,
                    policy.Channel(allocation.channel),
                    current,
                    reconciliation_result,
                    checkpoint_pin=allocation.stop_checkpoint,
                    retained_ambiguous_fingerprints=allocation.retained_ambiguous_fingerprints,
                    ambiguity_reason=allocation.retained_ambiguous_reason,
                    stop_audit_cache=stop_audit_cache,
                )
            except ControllerError as error:
                return result("INVALID", str(error), checkpoint, accepted)
            if (
                _field(latest, "head", "reviewed_head") != allocation.stop_reviewed_head
                or _field(latest, "patch_id", "patch_identity") != allocation.stop_reviewed_patch_id
            ):
                return result("INVALID", "the stopped review checkpoint identity changed", checkpoint, accepted)
            return {
                **result("STOPPED", "review discovery explicitly stopped; merge readiness remains separate", checkpoint, accepted),
                "stop_reviewed_head": allocation.stop_reviewed_head,
                "stop_head": allocation.stop_head,
                "stop_reason": allocation.stop_reason,
                "retained_ambiguous_fingerprints": list(allocation.retained_ambiguous_fingerprints),
            }
        if allocation.handoff_checkpoint is None:
            return result("EXHAUSTED_PENDING", "review allocation exhausted; an explicit stop decision is required", checkpoint, accepted)
        if allocation.handoff_checkpoint != checkpoint or allocation.handoff_head != current.child_head:
            return result("INVALID", "recorded handoff no longer matches the consumed review and live head", checkpoint, accepted)
        return result("HANDED_OFF", "review capacity handed off; merge readiness remains a separate judgment", checkpoint, accepted)

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
    ) -> dict[int, dict[str, Any]]:
        views: dict[int, dict[str, Any]] = {}
        selected_prs = state.ordered_prs if pr_numbers is None else pr_numbers
        for pr in selected_prs:
            allocation = state.allocations.get(f"{pr}:{channel.value}")
            if allocation is None or live[pr].merged:
                continue
            try:
                current = self._anchor(pr, live[pr], reconciliation.links[pr])
            except (ControllerError, OSError, subprocess.SubprocessError, ValueError):
                current = None
            views[pr] = self._allocation_progress(
                allocation,
                histories[pr],
                current,
                reconciliation.status_for(pr, channel.value),
                state=state,
                reconciliation_result=reconciliation,
                stop_audit_cache=stop_audit_cache,
            )
        return views

    def _target(self, channel: policy.Channel | str, expected_pr: int | None = None) -> Target:
        selected = policy.Channel(channel)
        state = self._state()
        if not state.ordered_prs:
            raise ControllerError("review stack is empty")
        live, reconciliation = self._reconciliation(state)
        for pr in state.ordered_prs:
            problem = self._head_repository_problem(live[pr])
            if problem:
                raise ControllerError(f"PR #{pr} {problem}")
        history = {
            pr: self._policy_history(state, pr, selected, reconciliation)
            for pr in state.ordered_prs
        }
        allocations = self._allocation_views(state, live, reconciliation, selected, history)
        other = policy.Channel.CLI if selected == policy.Channel.HOSTED else policy.Channel.HOSTED
        other_heads = {}
        for pr in state.ordered_prs:
            values = self._policy_history(state, pr, other, reconciliation)
            latest = _latest_review(values)
            other_anchor_status = reconciliation.status_for(pr, other.value)
            if latest is not None and other_anchor_status not in {
                stack.ReconciliationStatus.PATCH_CHANGED,
                stack.ReconciliationStatus.EQUIVALENT_HISTORY,
            }:
                value = _field(latest, "head", "reviewed_head")
                if isinstance(value, str):
                    other_heads[pr] = value
        decision = policy.select_review_target(
            state,
            selected,
            tuple(pr for pr in state.ordered_prs if not live[pr].merged),
            history,
            reconciliation_by_pr={pr: reconciliation.status_for(pr, selected.value) for pr in state.ordered_prs},
            other_channel_heads=other_heads,
            handed_off_prs=(pr for pr, view in allocations.items() if view["status"] == "HANDED_OFF"),
            human_stopped_prs=(pr for pr, view in allocations.items() if view["status"] == "STOPPED"),
            exhausted_prs=(pr for pr, view in allocations.items() if view["status"] == "EXHAUSTED_PENDING"),
            allocation_blocks={
                pr: view["reason"] for pr, view in allocations.items() if view["status"] == "INVALID"
            },
        )
        if decision.target is None:
            raise ControllerError(decision.reason)
        pr = decision.target
        if expected_pr is not None and expected_pr != pr:
            raise WrongStackTarget(f"expected PR #{expected_pr}, but selected PR #{pr}")
        item = live[pr]
        link = reconciliation.links[pr]
        anchor = self._anchor(pr, item, link)
        test_merge = self._default_test_merge_proofs.get(pr)
        if test_merge is not None and (
            test_merge[0].casefold() != item.base_tip.casefold()
            or test_merge[1].casefold() != item.head.casefold()
        ):
            test_merge = None
        selected_target = ReviewTarget(
            item.runner_snapshot(),
            EffectiveParent(link.parent_ref, link.parent_head, link.parent_pr),
            reconciled=reconciliation.status_for(pr)
            in {
                stack.ReconciliationStatus.COHERENT,
                stack.ReconciliationStatus.PATCH_CHANGED,
                stack.ReconciliationStatus.EQUIVALENT_HISTORY,
            },
            ancestor_links_valid=reconciliation.status_for(pr)
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
        )
        return Target(selected, pr, decision.status, decision.reason, selected_target, anchor, decision.provisional)

    @staticmethod
    def _ensure_runnable(selected: Target, *, provisional: bool = False) -> None:
        blocked = {
            policy.ReviewStatus.COMPLETE,
            policy.ReviewStatus.RATE_LIMITED,
            policy.ReviewStatus.HELD,
            policy.ReviewStatus.UNSTABLE,
            policy.ReviewStatus.UNRECONCILED,
            policy.ReviewStatus.PARENT_MOVED,
            policy.ReviewStatus.OVER_CEILING,
            policy.ReviewStatus.JUDGMENT_REQUIRED,
            policy.ReviewStatus.PROVISIONAL,
            policy.ReviewStatus.ALLOCATION_EXHAUSTED,
        }
        if selected.status in blocked and not provisional:
            raise ControllerError(f"{selected.channel.value} review cannot run: {selected.status.value}")

    def status(self) -> dict[str, Any]:
        return self._status_from_state(self._state())

    def _status_from_state(
        self,
        state: ReviewState,
        *,
        evidence_prs: set[int] | None = None,
        live_identities: Mapping[int, Any] | None = None,
    ) -> dict[str, Any]:
        if not state.ordered_prs:
            return {
                "ordered_prs": [],
                "status": "EMPTY",
                "legacy_transitions": [item.to_dict() for item in state.legacy_transitions],
            }
        live, reconciliation = self._reconciliation(
            state,
            live_identities=live_identities,
            refresh_prs=evidence_prs,
            evidence_prs=evidence_prs,
        )
        values: list[dict[str, Any]] = []
        histories = {
            channel: {
                pr: (
                    self._policy_history(state, pr, channel, reconciliation)
                    if evidence_prs is None or pr in evidence_prs
                    else []
                )
                for pr in state.ordered_prs
            }
            for channel in (policy.Channel.HOSTED, policy.Channel.CLI)
        }
        stop_audit_cache: dict[tuple[Any, ...], Mapping[str, Any]] = {}
        allocations = {
            channel: self._allocation_views(
                state,
                live,
                reconciliation,
                channel,
                histories[channel],
                stop_audit_cache=stop_audit_cache,
            )
            for channel in (policy.Channel.HOSTED, policy.Channel.CLI)
        }
        for pr in state.ordered_prs:
            item = live[pr]
            reconciliation_status = reconciliation.status_for(pr)
            channel_status: dict[str, str] = {}
            for channel in (policy.Channel.HOSTED, policy.Channel.CLI):
                other = policy.Channel.CLI if channel == policy.Channel.HOSTED else policy.Channel.HOSTED
                other_history = histories[other][pr]
                latest = _latest_review(other_history)
                other_head = _field(latest, "head", "reviewed_head") if latest is not None else None
                channel_reconciliation = reconciliation.status_for(pr, channel.value)
                if allocations[channel].get(pr, {}).get("status") == "STOPPED":
                    channel_status[channel.value] = policy.ReviewStatus.HUMAN_STOPPED.value
                elif channel_reconciliation in {
                    stack.ReconciliationStatus.PARENT_MOVED,
                    stack.ReconciliationStatus.UNRECONCILED,
                }:
                    channel_status[channel.value] = channel_reconciliation.value
                else:
                    channel_status[channel.value] = policy.completion_status(
                        state,
                        channel,
                        histories[channel][pr],
                        reconciliation=channel_reconciliation,
                        other_channel_head=(
                            other_head
                            if isinstance(other_head, str)
                            and reconciliation.status_for(pr, other.value)
                            not in {
                                stack.ReconciliationStatus.PATCH_CHANGED,
                                stack.ReconciliationStatus.EQUIVALENT_HISTORY,
                            }
                            else None
                        ),
                    ).value
            values.append(
                {
                    "pr": pr,
                    "head": item.head,
                    "base": item.base_ref,
                    "parent": reconciliation.links[pr].identity,
                    "parent_head": reconciliation.links[pr].parent_head,
                    "state": item.state,
                    "merged": item.merged,
                    "reconciliation": reconciliation_status.value,
                    "reason": reconciliation.reasons.get(pr, ""),
                    "channels": channel_status,
                    "review_activity": {
                        channel.value: _review_activity(histories[channel][pr], item.head)
                        for channel in (policy.Channel.HOSTED, policy.Channel.CLI)
                    },
                    "allocations": {
                        channel.value: allocations[channel][pr]
                        for channel in (policy.Channel.HOSTED, policy.Channel.CLI)
                        if pr in allocations[channel]
                    },
                }
            )
        return {
            "ordered_prs": list(state.ordered_prs),
            "status": reconciliation.status.value,
            "prs": values,
            "legacy_transitions": [item.to_dict() for item in state.legacy_transitions],
        }

    @staticmethod
    def _unknown_overview(state: ReviewState, reason: str) -> dict[str, Any]:
        prs = [
            {
                "pr": pr,
                "head": None,
                "base": None,
                "parent": None,
                "parent_head": None,
                "state": "UNKNOWN",
                "merged": None,
                "reconciliation": "UNKNOWN",
                "reason": reason,
                "channels": {"hosted": "UNKNOWN", "cli": "UNKNOWN"},
                "review_activity": {},
                "allocations": {},
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
            "legacy_transitions": [item.to_dict() for item in state.legacy_transitions],
        }

    @staticmethod
    def _batch_live_pull_requests(
        provider: Any, numbers: Sequence[int]
    ) -> tuple[dict[int, LivePullRequest], dict[int, Mapping[str, Any] | None]]:
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

    def status_for_pr(self, pr: int) -> dict[str, Any]:
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
            }
        scoped = dataclasses.replace(state, ordered_prs=state.ordered_prs[: index + 1])
        report = self._status_from_state(scoped)
        report["ordered_prs"] = list(state.ordered_prs)
        report["selected_pr"] = pr
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
                "legacy_transitions": [item.to_dict() for item in state.legacy_transitions],
            }

        try:
            batch_live, raw_identities = self._batch_live_pull_requests(self.github, state.ordered_prs)
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

        active_targets = {
            allocation.pr
            for allocation in state.allocations.values()
            if allocation.handoff_checkpoint is None and allocation.stop_basis is None
        }
        active_scan_status = "unknown"
        active_scan_error = None
        discover_active = getattr(self._evidence_provider, "active_review_targets", None)
        if callable(discover_active):
            try:
                found = discover_active(state.ordered_prs, raw_identities)
                active_scan_status = "complete"
            except (ControllerError, OSError, RuntimeError, TypeError, ValueError, KeyError) as error:
                found = set()
                active_scan_status = "unknown"
                active_scan_error = str(error)
            if not isinstance(found, (set, frozenset)) or any(
                isinstance(pr, bool) or not isinstance(pr, int) or pr not in state.ordered_prs for pr in found
            ):
                active_scan_status = "unknown"
                active_scan_error = "active review target probe returned malformed PR identities"
            else:
                active_targets.update(found)

        first_four = []
        for pr in state.ordered_prs:
            if not batch_live[pr].merged:
                first_four.append(pr)
                if len(first_four) == 4:
                    break
        target_prs = set(first_four) | active_targets
        target_indexes = [state.ordered_prs.index(pr) for pr in target_prs if pr in state.ordered_prs]
        deep_prs = target_prs.intersection(state.ordered_prs)
        frontier = max(target_indexes, default=-1)
        scoped_prs = state.ordered_prs[: frontier + 1] if frontier >= 0 else ()
        scoped_state = dataclasses.replace(state, ordered_prs=tuple(scoped_prs))
        scoped_report: dict[str, Any] | None = None
        deep_error = None
        if scoped_prs:
            try:
                scoped_report = self._status_from_state(
                    scoped_state,
                    evidence_prs=deep_prs,
                    live_identities=raw_identities,
                )
            except (ControllerError, OSError, subprocess.SubprocessError, RuntimeError, TypeError, ValueError) as error:
                deep_error = str(error)

        deep_by_pr = {
            item.get("pr"): item
            for item in (scoped_report or {}).get("prs", [])
            if isinstance(item, Mapping)
        }
        mismatch: set[int] = set()
        for pr in scoped_prs:
            if pr not in deep_prs:
                continue
            detailed = deep_by_pr.get(pr)
            if detailed is None:
                continue
            item = batch_live[pr]
            if (
                not isinstance(detailed.get("head"), str)
                or detailed["head"].casefold() != item.head.casefold()
                or detailed.get("base") != item.base_ref
                or not isinstance(detailed.get("parent_head"), str)
                or detailed["parent_head"].casefold() != item.base_tip.casefold()
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

        values: list[dict[str, Any]] = []
        for pr in state.ordered_prs:
            item = batch_live[pr]
            parent = links[pr]
            detailed = deep_by_pr.get(pr) if pr in deep_prs else None
            batch_topology_moved = (
                not item.merged
                and (item.base_ref != parent.parent_ref or item.base_tip.casefold() != parent.parent_head.casefold())
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
                if pr in mismatch or batch_topology_moved:
                    row["reconciliation"] = "UNRECONCILED"
                    row["reason"] = "live PR identity changed between batch overview and deep reconciliation"
                    row["channels"] = {"hosted": "UNRECONCILED", "cli": "UNRECONCILED"}
                    row["allocations"] = {}
                    row["evidence_status"] = "stale"
                values.append(row)
                continue

            stale = pr in mismatch or batch_topology_moved or saved_identity_moved
            reason = (
                "live parent topology or saved review identity moved"
                if stale
                else "tail review evidence was not deeply checked in this status invocation"
            )
            if deep_error is not None and pr in scoped_prs:
                stale = True
                reason = f"deep review evidence is unavailable: {deep_error}"
            values.append(
                {
                    "pr": pr,
                    "head": item.head,
                    "base": item.base_ref,
                    "parent": parent.identity,
                    "parent_head": parent.parent_head,
                    "state": item.state,
                    "merged": item.merged,
                    "reconciliation": "UNKNOWN" if not stale else "UNRECONCILED",
                    "reason": reason,
                    "channels": {"hosted": "NOT_CHECKED", "cli": "NOT_CHECKED"},
                    "review_activity": {},
                    "allocations": {},
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
                "unmerged_limit": 4,
                "batch_status": "complete",
                "deep_prs": [
                    pr for pr in state.ordered_prs if pr in deep_prs and not batch_live[pr].merged
                ] if deep_error is None else [],
                "tail_evidence": "not fetched; identity-only rows are informational and never indicate completion",
                "active_targets": sorted(active_targets),
                "active_target_scan": active_scan_status,
                **({"active_target_error": active_scan_error} if active_scan_error else {}),
                **({"deep_error": deep_error} if deep_error else {}),
            },
            "prs": values,
            "legacy_transitions": [item.to_dict() for item in state.legacy_transitions],
        }
        return report

    def resolve_cli_target(self, expected_pr: int | None = None) -> ReviewTarget:
        selected = self._target(policy.Channel.CLI, expected_pr)
        self._ensure_runnable(selected)
        return selected.target

    def resolve_hosted_target(self, expected_pr: int | None = None) -> ReviewTarget:
        selected = self._target(policy.Channel.HOSTED, expected_pr)
        self._ensure_runnable(selected)
        return selected.target

    def select_target(self, channel: policy.Channel | str, expected_pr: int | None = None) -> dict[str, Any]:
        return self._target(channel, expected_pr).as_dict()

    def run_hosted(self, *, expected_pr: int | None = None, **kwargs: Any) -> Any:
        selected = self._target(policy.Channel.HOSTED, expected_pr)
        self._ensure_runnable(selected)
        if self.hosted_adapter is None:
            raise ControllerError("Hosted adapter is not configured")
        for attempt in range(MAX_BASE_RESELECTIONS + 1):
            if not self.isolated_fixture:
                prepare_full_trigger(selected.pr, expected_pr)
            try:
                return self.hosted_adapter(selected.target, expect_pr=expected_pr, **kwargs)
            except StaleReviewTarget:
                if not selected.target.default_base_front or attempt == MAX_BASE_RESELECTIONS:
                    raise
                selected = self._target(policy.Channel.HOSTED, selected.pr)
                self._ensure_runnable(selected)
        raise AssertionError("bounded Hosted reselection loop exhausted unexpectedly")

    def _provisional_duplicate(self, selected: Target) -> bool:
        for value in _history(self._evidence_provider, selected.pr, policy.Channel.CLI):
            if not bool(_field(value, "provisional")):
                continue
            head = _field(value, "head", "reviewed_head")
            parent = _field(value, "parent_head")
            if head == selected.anchor.child_head and parent == selected.anchor.parent_head:
                return True
        return False

    def run_cli(
        self,
        *,
        expected_pr: int | None = None,
        allow_unreconciled: bool = False,
        reason: str | None = None,
        **kwargs: Any,
    ) -> Any:
        selected = self._target(policy.Channel.CLI, expected_pr)
        for attempt in range(MAX_BASE_RESELECTIONS + 1):
            if allow_unreconciled:
                if not reason or selected.status not in {
                    policy.ReviewStatus.UNRECONCILED,
                    policy.ReviewStatus.PARENT_MOVED,
                }:
                    raise ControllerError("provisional CLI requires an unreconciled target and a reason")
                if self._provisional_duplicate(selected):
                    raise ControllerError(
                        "one provisional CLI discovery is already recorded for this exact child/parent identity"
                    )
            elif selected.status in {
                policy.ReviewStatus.RATE_LIMITED,
                policy.ReviewStatus.COMPLETE,
                policy.ReviewStatus.JUDGMENT_REQUIRED,
            }:
                raise ControllerError(f"CLI review cannot run: {selected.status.value}")
            self._ensure_runnable(selected, provisional=allow_unreconciled)
            if self.cli_adapter is None:
                raise ControllerError("CLI adapter is not configured")
            try:
                return self.cli_adapter(
                    selected.target,
                    allow_unreconciled=allow_unreconciled,
                    reason=reason,
                    **kwargs,
                )
            except StaleReviewTargetError as error:
                if not selected.target.default_base_front or attempt == MAX_BASE_RESELECTIONS:
                    raise ControllerError(str(error)) from error
                selected = self._target(policy.Channel.CLI, selected.pr)
        raise AssertionError("bounded CLI reselection loop exhausted unexpectedly")

    def evidence(self, pr: int | None = None) -> dict[str, Any]:
        state = self._state()
        numbers = (pr,) if pr is not None else state.ordered_prs
        result: dict[str, Any] = {}
        for number in numbers:
            result[str(number)] = {
                channel.value: [
                    dict(item)
                    if isinstance(item, Mapping)
                    else dataclasses.asdict(item)
                    if dataclasses.is_dataclass(item)
                    else policy.Evidence.from_value(item).__dict__
                    for item in _history(self._evidence_provider, number, channel)
                ]
                for channel in (policy.Channel.HOSTED, policy.Channel.CLI)
            }
        return result

    def decide_judgment(
        self, *, pr: int, channel: str, decision: str, head: str, checkpoint: str, reason: str
    ) -> dict[str, Any]:
        checkpoint_head, patch_id = self._validate_decision_identity(
            pr, channel, head, checkpoint, allow_equivalent_history=True
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
    ) -> dict[str, Any]:
        """Grant, renew, or cancel a pre-authorized one-result review allocation."""

        if action not in {"grant", "renew", "cancel"}:
            raise ControllerError("allocation action must be grant, renew, or cancel")
        try:
            selected = policy.Channel(channel)
        except ValueError as exc:
            raise ControllerError("allocation channel must be hosted or cli") from exc
        if not isinstance(reason, str) or not reason.strip():
            raise ControllerError("review allocation decisions require a reason")
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
        live, reconciliation = self._reconciliation(state)
        item = live[pr]
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

        if reconciliation.status_for(pr) in {
            stack.ReconciliationStatus.UNRECONCILED,
            stack.ReconciliationStatus.PARENT_MOVED,
        }:
            raise ControllerError("allocation requires a coherent current stack identity")
        current = self._anchor(pr, item, reconciliation.links[pr])
        history = _history(self._evidence_provider, pr, selected)
        if action == "grant":
            target = self._target(selected, expected_pr=pr)
            self._ensure_runnable(target)
        else:
            assert previous is not None
            progress = self._allocation_progress(
                previous,
                history,
                current,
                reconciliation.status_for(pr, selected.value),
                state=state,
                reconciliation_result=reconciliation,
            )
            if (
                progress["status"] in {"EXHAUSTED_PENDING", "HANDED_OFF", "STOPPED"}
                or progress["checkpoint"] is not None
            ):
                raise ControllerError("consumed or stopped review allocations cannot be renewed")
        if any(
            _field(value, flag) is True
            for value in history
            for flag in ("held", "unstable", "rate_limited", "unreconciled", "parent_moved", "over_ceiling")
            if _field(value, "head", "reviewed_head") in (None, "", item.head)
        ):
            raise ControllerError("current review evidence is blocked; allocation cannot be promised")
        baseline = tuple(
            _field(value, "checkpoint", "checkpoint_id")
            for value in history if _field(value, "correction") is not True
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
        )
        updated = self.store.update(lambda current_state: update_allocation(current_state, allocation))
        return {
            "action": action,
            "allocation": allocation.to_dict(),
            "allocations": list(updated.allocations),
        }

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
        state = self._state()
        if pr not in state.ordered_prs:
            raise ControllerError(f"PR #{pr} is not in the configured review stack")
        identity = f"{pr}:{selected.value}"
        previous = state.allocations.get(identity)
        with self._stop_review_locks(pr):
            # Refresh everything inside both channel locks so an in-flight review
            # cannot be mistaken for a terminal result.
            state = self._state()
            if pr not in state.ordered_prs:
                raise ControllerError(f"PR #{pr} is not in the configured review stack")
            previous = state.allocations.get(identity)
            if acknowledge_over_ceiling and previous is not None:
                raise ControllerError("over-ceiling acknowledgment is available only to a direct human stop")
            if previous is not None and previous.handoff_checkpoint is not None:
                raise ControllerError("this channel already has a legacy completed handoff")
            live, reconciliation = self._reconciliation(state)
            item = live[pr]
            if normalized_head is not None and normalized_head.casefold() != item.head.casefold():
                raise ControllerError("review stop head does not match the live pull-request head")
            if self._head_repository_problem(item):
                raise ControllerError(f"PR #{pr} has an unsupported head repository")
            remote_heads = self.git.remote_heads()
            if not self._current_branches_match(state, live, reconciliation, pr, remote_heads):
                raise ControllerError("review stop requires a coherent exact-current stack topology")
            current = self._anchor(pr, item, reconciliation.links[pr])
            basis = previous.stop_basis if previous is not None and previous.stop_basis is not None else "direct_human"
            if acknowledge_over_ceiling and basis != "direct_human":
                raise ControllerError("over-ceiling acknowledgment is available only to a direct human stop")
            candidate_history = self._policy_history(state, pr, selected, reconciliation)
            if previous is not None and previous.stop_basis is None and previous.handoff_checkpoint is None:
                prior_view = self._allocation_progress(
                    previous,
                    candidate_history,
                    current,
                    reconciliation.status_for(pr, selected.value),
                    state=state,
                    reconciliation_result=reconciliation,
                )
                candidate_latest, _ = self._latest_stop_checkpoint(
                    pr, selected, candidate_history, checkpoint
                )
                if (
                    prior_view["status"] == "EXHAUSTED_PENDING"
                    and prior_view["reason"] == "review allocation exhausted; an explicit stop decision is required"
                    and prior_view["checkpoint"] == _field(candidate_latest, "checkpoint", "checkpoint_id")
                ):
                    basis = "allocated"
            latest, retained, checked_histories = self._check_stop_evidence(
                state,
                pr,
                selected,
                current,
                reconciliation,
                checkpoint_pin=checkpoint,
                allow_historical_unmatched=basis == "direct_human",
                allow_historical_terminal_ambiguity=basis == "direct_human",
                retained_ambiguous_fingerprints=retained_fingerprint_values,
                ambiguity_reason=ambiguity_reason,
                acknowledge_over_ceiling=acknowledge_over_ceiling,
            )
            retained_fingerprints, retained_reason = retained or ((), None)
            history = checked_histories[selected]
            if previous is not None and previous.stop_basis is not None:
                prior_view = self._allocation_progress(
                    previous,
                    history,
                    current,
                    reconciliation.status_for(pr, selected.value),
                    state=state,
                    reconciliation_result=reconciliation,
                )
                if prior_view["status"] == "STOPPED":
                    raise ControllerError("review discovery is already stopped for this PR and channel")

            if previous is not None:
                original = previous
            else:
                checkpoints = tuple(
                    _field(value, "checkpoint", "checkpoint_id")
                    for value in history
                    if _field(value, "correction") is not True
                )
                if any(not isinstance(value, str) or not value for value in checkpoints):
                    raise ControllerError("current review evidence lacks immutable checkpoint identities")
                if len(checkpoints) != len(set(checkpoints)):
                    raise ControllerError("current review evidence has ambiguous checkpoint identities")
                original = ReviewAllocation(
                    pr=pr,
                    channel=selected.value,
                    head=current.child_head,
                    parent_identity=current.parent_identity,
                    parent_head=current.parent_head,
                    merge_base=current.merge_base,
                    patch_id=current.patch_id,
                    baseline_checkpoints=checkpoints,
                    reason=normalized_reason,
                )
            stopped = dataclasses.replace(
                original,
                stop_basis=basis,
                stop_checkpoint=_field(latest, "checkpoint", "checkpoint_id"),
                stop_reviewed_head=_field(latest, "head", "reviewed_head"),
                stop_reviewed_patch_id=_field(latest, "patch_id", "patch_identity"),
                stop_head=current.child_head,
                stop_parent_identity=current.parent_identity,
                stop_parent_head=current.parent_head,
                stop_merge_base=current.merge_base,
                stop_patch_id=current.patch_id,
                stop_reason=(
                    f"[acknowledged current over-ceiling limitation] {normalized_reason}"
                    if acknowledge_over_ceiling
                    else normalized_reason
                ),
                stop_summary_disposition_fingerprints=self._stop_summary_fingerprints(state, pr),
                retained_ambiguous_fingerprints=retained_fingerprints,
                retained_ambiguous_reason=retained_reason,
            )

            def persist(current_state: ReviewState) -> ReviewState:
                if current_state.allocations.get(identity) != previous:
                    raise ControllerError("review allocation changed concurrently; reread before stopping")
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
        if checkpoint_head != normalized_head:
            if reconciliation_status in {
                stack.ReconciliationStatus.PARENT_MOVED,
                stack.ReconciliationStatus.UNRECONCILED,
            }:
                raise ControllerError("equivalent-history judgment requires coherent live topology")
            if not allow_equivalent_history or reconciliation_status != stack.ReconciliationStatus.EQUIVALENT_HISTORY:
                raise ControllerError("decision checkpoint head must match the live head or equivalent history")
        if checkpoint_evidence.patch_id != anchor.patch_id:
            raise ControllerError("decision checkpoint patch identity does not match the live stack anchor")
        if require_zero_useful and (
            checkpoint_evidence.corrected_state is not True or checkpoint_evidence.accepted != 0
        ):
            raise ControllerError("policy override requires a corrected-state zero-useful checkpoint")
        return checkpoint_head, anchor.patch_id

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
