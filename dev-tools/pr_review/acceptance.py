"""Hermetic public-CLI adapters for Overseer acceptance runs.

The acceptance mode is deliberately a separate execution boundary.  It reads a
strict JSON fixture, uses an explicitly supplied state file, and never calls the
live GitHub, Git, evidence, Hosted, or CLI adapters.  It exists so an operator
can exercise the real ``dev-tools/pr-review`` command without risking the
repository's private stack state or review quota.
"""

from __future__ import annotations

import fcntl
import json
import os
import stat
import tempfile
from collections.abc import Mapping, Sequence
from pathlib import Path
from typing import Any

from . import state
from .cli_runner import ReviewTarget
from .controller import LivePullRequest, ReviewController


class AcceptanceFixtureError(ValueError):
    """The isolated acceptance fixture is missing or contains unsafe data."""


def _sha(value: Any, label: str) -> str:
    if not isinstance(value, str) or len(value) != 40 or any(
        character not in "0123456789abcdefABCDEF" for character in value
    ):
        raise AcceptanceFixtureError(f"{label} must be a full commit SHA")
    return value.lower()


def _positive_pr(value: Any, label: str) -> int:
    if isinstance(value, bool) or not isinstance(value, int) or value <= 0:
        raise AcceptanceFixtureError(f"{label} must be a positive integer")
    return value


def _repository_identity(value: Any, label: str) -> str:
    if (
        not isinstance(value, str)
        or value.count("/") != 1
        or any(not part or any(character.isspace() for character in part) for part in value.split("/"))
    ):
        raise AcceptanceFixtureError(f"{label} must be an owner/name repository identity")
    return value


def _mapping(value: Any, label: str) -> Mapping[str, Any]:
    if not isinstance(value, Mapping):
        raise AcceptanceFixtureError(f"{label} must be an object")
    return value


def _pair_key(left: str, right: str) -> str:
    return f"{left}...{right}"


class FixtureGitHub:
    """Read-only pull-request provider backed only by the fixture."""

    def __init__(self, pull_requests: Mapping[int, LivePullRequest]) -> None:
        self._pull_requests = dict(pull_requests)

    def pull_request(self, number: int) -> LivePullRequest:
        try:
            return self._pull_requests[number]
        except KeyError as exc:
            raise AcceptanceFixtureError(f"fixture has no pull request #{number}") from exc


class FixtureGit:
    """Deterministic Git facts required by stack reconciliation and decisions."""

    def __init__(self, fixture: Mapping[str, Any]) -> None:
        heads = _mapping(fixture.get("branch_heads"), "branch_heads")
        self._heads = {str(name): _sha(value, f"branch_heads[{name!r}]") for name, value in heads.items()}
        ancestry = fixture.get("ancestors", [])
        if not isinstance(ancestry, list):
            raise AcceptanceFixtureError("ancestors must be an array")
        self._ancestors = {
            (_sha(item[0], "ancestor pair source"), _sha(item[1], "ancestor pair target"))
            for item in ancestry
            if isinstance(item, list) and len(item) == 2
        }
        if len(self._ancestors) != len(ancestry):
            raise AcceptanceFixtureError("ancestors entries must be two full commit SHAs")
        merge_bases = _mapping(fixture.get("merge_bases", {}), "merge_bases")
        self._merge_bases = {
            str(key): _sha(value, f"merge_bases[{key!r}]") for key, value in merge_bases.items()
        }
        patches = _mapping(fixture.get("patch_ids", {}), "patch_ids")
        self._patch_ids = {str(key): value for key, value in patches.items()}
        if any(not isinstance(value, str) or not value for value in self._patch_ids.values()):
            raise AcceptanceFixtureError("patch_ids values must be non-empty strings")

    def branch_head(self, ref_name: str) -> str:
        try:
            return self._heads[ref_name]
        except KeyError as exc:
            raise AcceptanceFixtureError(f"fixture has no head for branch {ref_name!r}") from exc

    def remote_heads(self) -> Mapping[str, str]:
        """Return one immutable-in-practice snapshot of all fixture branch heads."""
        return dict(self._heads)

    def branch_exists(self, ref_name: str) -> bool:
        return ref_name in self._heads

    def is_ancestor(self, ancestor: str, descendant: str) -> bool:
        return ancestor == descendant or (ancestor, descendant) in self._ancestors

    def merge_base(self, left: str, right: str) -> str:
        key = _pair_key(left, right)
        reverse = _pair_key(right, left)
        try:
            return self._merge_bases[key]
        except KeyError:
            try:
                return self._merge_bases[reverse]
            except KeyError as exc:
                raise AcceptanceFixtureError(f"fixture has no merge base for {left} and {right}") from exc

    def patch_identity(self, merge_base: str, head: str) -> str:
        key = _pair_key(merge_base, head)
        try:
            return self._patch_ids[key]
        except KeyError as exc:
            raise AcceptanceFixtureError(f"fixture has no patch identity for {merge_base} and {head}") from exc


class FixtureEvidence:
    """Fixture evidence plus atomically persisted, isolated simulated discoveries."""

    def __init__(self, fixture: Mapping[str, Any], state_path: Path, repository: str) -> None:
        values = _mapping(fixture.get("evidence", {}), "evidence")
        self._values: dict[tuple[int, str], tuple[Any, ...]] = {}
        self.repository = repository
        self.path = state_path.with_name(f"{state_path.name}.fixture-evidence.json")
        self.lock_path = state_path.with_name(f".{state_path.name}.fixture-evidence.lock")
        for pr_key, channels in values.items():
            if not isinstance(pr_key, str) or not pr_key.isdecimal():
                raise AcceptanceFixtureError("evidence PR keys must be positive decimal strings")
            pr = _positive_pr(int(pr_key), "evidence PR")
            channel_values = _mapping(channels, f"evidence[{pr_key!r}]")
            for channel in ("hosted", "cli"):
                raw = channel_values.get(channel, [])
                if not isinstance(raw, list):
                    raise AcceptanceFixtureError(f"evidence[{pr_key!r}][{channel!r}] must be an array")
                if any(not isinstance(item, Mapping) for item in raw):
                    raise AcceptanceFixtureError(f"evidence[{pr_key!r}][{channel!r}] entries must be objects")
                self._values[(pr, channel)] = tuple(raw)

    def _recorded(self) -> list[dict[str, Any]]:
        if not self.path.exists():
            return []
        try:
            payload = json.loads(self.path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError) as exc:
            raise AcceptanceFixtureError("isolated synthetic evidence is unreadable") from exc
        if not isinstance(payload, dict) or set(payload) != {"schema_version", "repository", "evidence"}:
            raise AcceptanceFixtureError("isolated synthetic evidence has an invalid schema")
        if payload["schema_version"] != 1 or payload["repository"] != self.repository:
            raise AcceptanceFixtureError("isolated synthetic evidence belongs to another fixture repository")
        entries = payload["evidence"]
        if not isinstance(entries, list) or any(not isinstance(item, dict) for item in entries):
            raise AcceptanceFixtureError("isolated synthetic evidence entries are invalid")
        return entries

    def history(self, pr: int, channel: str) -> Sequence[Any]:
        baseline = self._values.get((pr, channel), ())
        if channel != "cli":
            return baseline
        recorded = tuple(item for item in self._recorded() if item.get("pr") == pr)
        return (*baseline, *recorded)

    def record_provisional(self, target: ReviewTarget, reason: str) -> None:
        """Atomically reserve one synthetic child/parent discovery identity."""

        pr = target.snapshot.number
        head = target.snapshot.head_sha
        parent_head = target.parent.head_sha
        with self.lock_path.open("a+", encoding="utf-8") as lock:
            os.fchmod(lock.fileno(), 0o600)
            fcntl.flock(lock.fileno(), fcntl.LOCK_EX)
            try:
                recorded = self._recorded()
                existing = (*self._values.get((pr, "cli"), ()), *recorded)
                if any(
                    item.get("provisional") is True
                    and item.get("pr") == pr
                    and item.get("head") == head
                    and item.get("parent_head") == parent_head
                    for item in existing
                ):
                    raise AcceptanceFixtureError(
                        "one provisional CLI discovery is already recorded for this exact child/parent identity"
                    )
                recorded.append(
                    {
                        "pr": pr,
                        "head": head,
                        "child_head": head,
                        "parent_head": parent_head,
                        "parent_identity": str(target.parent.pr_number or target.parent.ref_name),
                        "merge_base": target.merge_base,
                        "patch_id": target.patch_identity,
                        "checkpoint": f"fixture-provisional-{pr}-{head[:12]}-{parent_head[:12]}",
                        "completed": False,
                        "attributable": False,
                        "anchored": True,
                        "corrected_state": False,
                        "provisional": True,
                        "accepted": 0,
                        "raw": 0,
                        "reason": reason,
                    }
                )
                with tempfile.NamedTemporaryFile(
                    mode="w", encoding="utf-8", dir=self.path.parent, prefix=f".{self.path.name}.", delete=False
                ) as handle:
                    temporary = Path(handle.name)
                    os.fchmod(handle.fileno(), 0o600)
                    json.dump(
                        {"schema_version": 1, "repository": self.repository, "evidence": recorded},
                        handle,
                        sort_keys=True,
                    )
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
            finally:
                fcntl.flock(lock.fileno(), fcntl.LOCK_UN)


class FixtureReviewAdapter:
    """Return deterministic review results without posting or consuming quota."""

    def __init__(self, channel: str, evidence: FixtureEvidence) -> None:
        self.channel = channel
        self.evidence = evidence

    def __call__(self, target: ReviewTarget, **kwargs: Any) -> dict[str, Any]:
        if self.channel == "cli" and kwargs.get("allow_unreconciled"):
            self.evidence.record_provisional(target, str(kwargs.get("reason", "")))
        return {
            "acceptance_fixture": True,
            "simulated": True,
            "channel": self.channel,
            "pr": target.snapshot.number,
            "head": target.snapshot.head_sha,
            "parent": target.parent.pr_number or target.parent.ref_name,
            "parent_head": target.parent.head_sha,
            "provisional": bool(kwargs.get("allow_unreconciled", False)),
            "review_started": False,
            "quota_consumed": False,
        }


class AcceptanceFixture:
    """Validated fixture plus a controller wired entirely to its adapters."""

    def __init__(self, payload: Mapping[str, Any], *, fixture_path: Path, state_path: Path) -> None:
        allowed = {
            "repository",
            "default_base_ref",
            "default_base_tip",
            "ordered_prs",
            "branch_heads",
            "ancestors",
            "merge_bases",
            "patch_ids",
            "pull_requests",
            "evidence",
        }
        unexpected = set(payload) - allowed
        if unexpected:
            raise AcceptanceFixtureError(f"fixture contains unsupported fields: {sorted(unexpected)}")
        self.payload = payload
        self.fixture_path = fixture_path
        self.state_path = state_path
        self.repository = _repository_identity(payload.get("repository", "acceptance/fixture"), "repository")
        self.default_base_ref = str(payload.get("default_base_ref", "develop"))
        if not self.default_base_ref:
            raise AcceptanceFixtureError("default_base_ref must be non-empty")
        default_tip = _sha(payload.get("default_base_tip"), "default_base_tip")
        branch_heads = {
            str(name): _sha(value, f"branch_heads[{name!r}]")
            for name, value in _mapping(payload.get("branch_heads"), "branch_heads").items()
        }
        if branch_heads.get(self.default_base_ref) != default_tip:
            raise AcceptanceFixtureError("default_base_tip must equal branch_heads[default_base_ref]")
        raw_prs = payload.get("pull_requests")
        if not isinstance(raw_prs, list) or not raw_prs:
            raise AcceptanceFixtureError("pull_requests must be a non-empty array")
        pull_requests: dict[int, LivePullRequest] = {}
        for raw in raw_prs:
            item = _mapping(raw, "pull_requests entry")
            number = _positive_pr(item.get("number"), "pull request number")
            if number in pull_requests:
                raise AcceptanceFixtureError(f"duplicate pull request #{number}")
            base_ref = item.get("base_ref")
            head_ref = item.get("head_ref", "")
            head_repository = _repository_identity(item.get("head_repository"), f"pull request #{number} head_repository")
            if not isinstance(base_ref, str) or not base_ref or not isinstance(head_ref, str):
                raise AcceptanceFixtureError(f"pull request #{number} has invalid branch names")
            allowed_pr_fields = {
                "number",
                "base_ref",
                "base_tip",
                "head",
                "head_ref",
                "head_repository",
                "merged",
                "state",
                "mergeable",
                "base_exists",
                "changed_files",
            }
            unexpected_pr_fields = set(item) - allowed_pr_fields
            if unexpected_pr_fields:
                raise AcceptanceFixtureError(
                    f"pull request #{number} contains unsupported fields: {sorted(unexpected_pr_fields)}"
                )
            for name in ("merged", "base_exists"):
                if name in item and not isinstance(item[name], bool):
                    raise AcceptanceFixtureError(f"pull request #{number} field {name!r} must be boolean")
            state_value = str(item.get("state", "OPEN")).upper()
            mergeable_value = str(item.get("mergeable", "MERGEABLE")).upper()
            if state_value not in {"OPEN", "CLOSED", "MERGED"}:
                raise AcceptanceFixtureError(f"pull request #{number} has unsupported state {state_value!r}")
            if mergeable_value not in {"MERGEABLE", "CONFLICTING", "UNKNOWN"}:
                raise AcceptanceFixtureError(
                    f"pull request #{number} has unsupported mergeable state {mergeable_value!r}"
                )
            changed_files = item.get("changed_files", 0)
            if isinstance(changed_files, bool) or not isinstance(changed_files, int) or changed_files < 0:
                raise AcceptanceFixtureError(f"pull request #{number} changed_files must be non-negative")
            pull_requests[number] = LivePullRequest(
                number=number,
                head=_sha(item.get("head"), f"pull request #{number} head"),
                base_ref=base_ref,
                base_tip=_sha(item.get("base_tip"), f"pull request #{number} base"),
                head_ref=head_ref,
                merged=bool(item.get("merged", False)),
                state=state_value,
                mergeable=mergeable_value,
                base_exists=bool(item.get("base_exists", True)),
                changed_files=changed_files,
                head_repository=head_repository,
            )
        ordered = payload.get("ordered_prs", list(pull_requests))
        if not isinstance(ordered, list) or tuple(ordered) != tuple(dict.fromkeys(ordered)):
            raise AcceptanceFixtureError("ordered_prs must be a list of unique PR numbers")
        if any(number not in pull_requests for number in ordered):
            raise AcceptanceFixtureError("ordered_prs references an unknown pull request")
        payload = dict(payload)
        payload["branch_heads"] = dict(branch_heads)
        payload["default_base_tip"] = default_tip
        self.github = FixtureGitHub(pull_requests)
        self.git = FixtureGit(payload)
        self.evidence = FixtureEvidence(payload, self.state_path, self.repository)
        self.pull_requests = pull_requests
        self.initial_stack = tuple(ordered)

    def controller(self) -> ReviewController:
        controller = ReviewController(
            store=state.StateStore(self.state_path),
            github=self.github,
            git=self.git,
            evidence=self.evidence,
            default_base_ref=self.default_base_ref,
            repository=self.repository,
            hosted_adapter=FixtureReviewAdapter("hosted", self.evidence),
            cli_adapter=FixtureReviewAdapter("cli", self.evidence),
        )
        return controller

    def status(self, controller: ReviewController, pr: int | None = None) -> dict[str, Any]:
        report = controller.status()
        if pr is not None:
            if pr not in self.pull_requests:
                raise AcceptanceFixtureError(f"fixture has no pull request #{pr}")
            report = {"pr": pr, "pull_request": self.pull_requests[pr].runner_snapshot().__dict__, **report}
        report["acceptance_fixture"] = {"isolated": True, "network": False, "review_quota": False}
        return report


def load(path: str | Path, state_path: str | Path) -> AcceptanceFixture:
    """Load a strict fixture and reject any attempt to use canonical state."""

    fixture_path = Path(path).expanduser().resolve()
    isolated_path = Path(state_path).expanduser().resolve()
    if fixture_path == isolated_path:
        raise AcceptanceFixtureError("acceptance fixture and --state-path must be different files")
    canonical = state.state_path()
    if isolated_path == canonical:
        raise AcceptanceFixtureError("acceptance mode refuses the canonical review-stack state path")
    if isolated_path.parent == canonical.parent:
        raise AcceptanceFixtureError("acceptance mode requires a directory separate from canonical review state")
    try:
        parent = isolated_path.parent.stat()
    except OSError as exc:
        raise AcceptanceFixtureError("acceptance state directory must already exist") from exc
    # StateStore enforces mode 0700 on its lock directory.  Require a dedicated
    # private directory up front so an operator cannot accidentally chmod /tmp,
    # a worktree, or another shared directory while running the fixture.
    if not stat.S_ISDIR(parent.st_mode) or parent.st_uid != os.getuid() or stat.S_IMODE(parent.st_mode) != 0o700:
        raise AcceptanceFixtureError("acceptance state directory must be owned by the caller with mode 0700")
    try:
        if canonical.exists() and isolated_path.exists() and os.path.samefile(isolated_path, canonical):
            raise AcceptanceFixtureError("acceptance mode refuses a state path linked to canonical stack state")
    except OSError as exc:
        raise AcceptanceFixtureError("cannot verify acceptance state-path isolation") from exc
    if not fixture_path.is_file():
        raise AcceptanceFixtureError(f"acceptance fixture is not a file: {fixture_path}")
    try:
        payload = json.loads(fixture_path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise AcceptanceFixtureError(f"cannot read acceptance fixture: {fixture_path}") from exc
    if not isinstance(payload, Mapping):
        raise AcceptanceFixtureError("acceptance fixture must be a JSON object")
    return AcceptanceFixture(payload, fixture_path=fixture_path, state_path=isolated_path)


__all__ = ["AcceptanceFixture", "AcceptanceFixtureError", "FixtureReviewAdapter", "load"]
