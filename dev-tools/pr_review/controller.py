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

import dataclasses
import subprocess
from collections.abc import Callable, Iterable, Mapping, Sequence
from pathlib import Path
from typing import Any, Protocol

from . import policy, stack
from .cli_runner import (
    EffectiveParent,
    PullRequestSnapshot,
    ReviewTarget,
)
from .hosted import prepare_full_trigger
from .patch_identity import patch_identity
from .state import Judgment, PolicyOverride, ReviewState, StackReconciliationDecision, StateStore


class ControllerError(RuntimeError):
    """A fail-closed controller preflight or decision error."""


class WrongStackTarget(ControllerError):
    """The requested PR is not the target selected by the configured stack."""


GIT_TIMEOUT_SECONDS = 30


class GitProvider(Protocol):
    """Git facts needed to anchor a review to one exact candidate."""

    def remote_heads(self) -> Mapping[str, str]: ...

    def branch_head(self, ref_name: str) -> str: ...

    def branch_exists(self, ref_name: str) -> bool: ...

    def is_ancestor(self, ancestor: str, descendant: str) -> bool: ...

    def merge_base(self, left: str, right: str) -> str: ...

    def patch_identity(self, merge_base: str, head: str) -> str: ...


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
            and _field(item, "provisional") is not True
            and _field(item, "correction") is not True
        ):
            return item
    return None


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
    ) -> None:
        self.store = store or StateStore()
        self.github = github
        self.git = git or DefaultGitProvider()
        self._evidence_provider = evidence or EmptyEvidence()
        self.default_base_ref = default_base_ref
        self.repository = repository
        self.hosted_adapter = hosted_adapter
        self.cli_adapter = cli_adapter

    def _require_github(self) -> GitHubProvider:
        if self.github is None:
            raise ControllerError("a GitHub provider is required for live review operations")
        return self.github

    def _state(self) -> ReviewState:
        return self.store.load()

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

    # Command-layer friendly spellings.  They intentionally delegate to the
    # same methods so there is one stack mutation and one target-selection path.
    stack_set = set_stack
    stack_show = show_stack

    def _live_snapshots(
        self, state: ReviewState, remote_heads: Mapping[str, str] | None = None
    ) -> tuple[dict[int, LivePullRequest], dict[int, stack.PRSnapshot], str]:
        github = self._require_github()
        heads = remote_heads if remote_heads is not None else self.git.remote_heads()
        default_tip = _sha(heads.get(self.default_base_ref), "default base tip")
        live = {pr: _live(github.pull_request(pr), pr) for pr in state.ordered_prs}
        snapshots = {pr: item.snapshot() for pr, item in live.items()}
        return live, snapshots, default_tip

    def _reconciliation(self, state: ReviewState) -> tuple[dict[int, LivePullRequest], stack.Reconciliation]:
        remote_heads = self.git.remote_heads()
        live, snapshots, default_tip = self._live_snapshots(state, remote_heads)
        baseline = stack.reconcile_stack(
            state.ordered_prs,
            snapshots,
            self.default_base_ref,
            default_tip,
            is_ancestor=self.git.is_ancestor,
        )
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
        reconciled: dict[int, StackReconciliationDecision] = {}
        anchors: dict[int, AnchorFacts] = {}
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
            decision = self._active_stack_reconciliation(state, pr, current)
            if decision is not None:
                reconciled[pr] = decision
        anchored: dict[int, str] = {}
        for pr in state.ordered_prs:
            if pr in reconciled:
                continue
            for channel in (policy.Channel.HOSTED, policy.Channel.CLI):
                latest = _latest_review(_history(self._evidence_provider, pr, channel))
                if latest is not None:
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
            is_ancestor=self.git.is_ancestor,
            anchored_parent_heads=anchored,
        )
        reasons = dict(result.reasons)
        statuses = dict(result.statuses)
        channel_statuses = dict(result.channel_statuses)

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
        # GitHub's PR head is the candidate identity, while the source branch is
        # the parent ref used by a child.  A branch moving without a child base
        # refresh is therefore a real parent movement, even when the PR payload
        # itself has not changed yet.
        for pr, item in live.items():
            if item.merged or not item.head_ref or item.head_ref not in remote_heads:
                continue
            branch_tip = _sha(remote_heads[item.head_ref], f"PR #{pr} head branch")
            if branch_tip != item.head:
                reasons[pr] = "parent source branch moved since the live PR head"
                moved.add(pr)
        changed = True
        while changed:
            changed = False
            for pr, link in result.links.items():
                if link.parent_pr in moved and pr not in moved:
                    moved.add(pr)
                    changed = True
        for pr in moved:
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
            for channel in (policy.Channel.HOSTED, policy.Channel.CLI):
                latest = _latest_review(_history(self._evidence_provider, pr, channel))
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
        if any(value == stack.ReconciliationStatus.PARENT_MOVED for value in statuses.values()):
            overall = stack.ReconciliationStatus.PARENT_MOVED
        elif any(value == stack.ReconciliationStatus.UNRECONCILED for value in statuses.values()):
            overall = stack.ReconciliationStatus.UNRECONCILED
        else:
            overall = result.status
        return live, dataclasses.replace(
            result,
            status=overall,
            affected_descendants=tuple(pr for pr in state.ordered_prs if pr in moved),
            reasons=reasons,
            statuses=statuses,
            channel_statuses=channel_statuses,
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
        history = {pr: _history(self._evidence_provider, pr, selected) for pr in state.ordered_prs}
        other = policy.Channel.CLI if selected == policy.Channel.HOSTED else policy.Channel.HOSTED
        other_heads = {}
        for pr in state.ordered_prs:
            values = _history(self._evidence_provider, pr, other)
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
        )
        if decision.target is None:
            raise ControllerError(decision.reason)
        pr = decision.target
        if expected_pr is not None and expected_pr != pr:
            raise WrongStackTarget(f"expected PR #{expected_pr}, but selected PR #{pr}")
        item = live[pr]
        link = reconciliation.links[pr]
        anchor = self._anchor(pr, item, link)
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
        }
        if selected.status in blocked and not provisional:
            raise ControllerError(f"{selected.channel.value} review cannot run: {selected.status.value}")

    def status(self) -> dict[str, Any]:
        state = self._state()
        if not state.ordered_prs:
            return {"ordered_prs": [], "status": "EMPTY"}
        live, reconciliation = self._reconciliation(state)
        values: list[dict[str, Any]] = []
        for pr in state.ordered_prs:
            item = live[pr]
            reconciliation_status = reconciliation.status_for(pr)
            channel_status: dict[str, str] = {}
            for channel in (policy.Channel.HOSTED, policy.Channel.CLI):
                other = policy.Channel.CLI if channel == policy.Channel.HOSTED else policy.Channel.HOSTED
                other_history = _history(self._evidence_provider, pr, other)
                latest = _latest_review(other_history)
                other_head = _field(latest, "head", "reviewed_head") if latest is not None else None
                channel_reconciliation = reconciliation.status_for(pr, channel.value)
                if channel_reconciliation in {
                    stack.ReconciliationStatus.PARENT_MOVED,
                    stack.ReconciliationStatus.UNRECONCILED,
                }:
                    channel_status[channel.value] = channel_reconciliation.value
                else:
                    channel_status[channel.value] = policy.completion_status(
                        state,
                        channel,
                        _history(self._evidence_provider, pr, channel),
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
                    "reconciliation": reconciliation_status.value,
                    "reason": reconciliation.reasons.get(pr, ""),
                    "channels": channel_status,
                }
            )
        return {"ordered_prs": list(state.ordered_prs), "status": reconciliation.status.value, "prs": values}

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

    review_target = select_target

    def run_hosted(self, *, expected_pr: int | None = None, **kwargs: Any) -> Any:
        selected = self._target(policy.Channel.HOSTED, expected_pr)
        self._ensure_runnable(selected)
        prepare_full_trigger(selected.pr, expected_pr)
        if self.hosted_adapter is None:
            raise ControllerError("Hosted adapter is not configured")
        return self.hosted_adapter(selected.target, expect_pr=expected_pr, **kwargs)

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
        if allow_unreconciled:
            if not reason or selected.status != policy.ReviewStatus.UNRECONCILED:
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
        return self.cli_adapter(selected.target, allow_unreconciled=allow_unreconciled, reason=reason, **kwargs)

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

    evidence_report = evidence
    status_report = status

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
        history = _history(self._evidence_provider, pr, selected_channel)
        parsed_history = [policy.Evidence.from_value(item) for item in history]
        effective_reviews: list[tuple[int, policy.Evidence]] = []
        for index, item in enumerate(parsed_history):
            if item.correction or item.completed is not True or item.attributable is not True or item.provisional:
                continue
            if item.pr != pr:
                raise ControllerError("decision evidence is attributable to another pull request")
            effective_reviews.append((index, item))
        matching = [
            item
            for item in parsed_history
            if item.checkpoint == checkpoint and item.pr == pr and not item.correction
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
        if operation == "reconcile":
            return self.decide_reconciliation(**kwargs)
        raise ControllerError("decision must be retain, reopen, policy, or reconcile")


# Stable aliases make the integration seam discoverable to the thin command layer.
Controller = ReviewController
UnifiedReviewController = ReviewController


def compact_result(value: Mapping[str, Any]) -> str:
    """Render a deterministic operator-friendly result without losing structure."""

    return "\n".join(f"{key}={value[key]}" for key in sorted(value))


def json_result(value: Mapping[str, Any]) -> dict[str, Any]:
    """Return a JSON-compatible copy of a controller result."""

    return {str(key): item for key, item in value.items()}
