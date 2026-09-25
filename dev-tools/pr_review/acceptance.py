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
import re
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


_SIMULATED_RESULTS = {"dry", "productive", "rate_limited", "partial", "stale", "duplicate"}


def _result_key(pr: int, channel: str) -> str:
    return f"{pr}:{channel}"


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

    def __init__(
        self, fixture: Mapping[str, Any], state_path: Path, repository: str, github: FixtureGitHub
    ) -> None:
        values = _mapping(fixture.get("evidence", {}), "evidence")
        self._values: dict[tuple[int, str], tuple[Any, ...]] = {}
        results = _mapping(fixture.get("review_results", {}), "review_results")
        self._results: dict[tuple[int, str], tuple[Mapping[str, Any], ...]] = {}
        self.repository = repository
        self.github = github
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
        for pr_key, channels in results.items():
            if not isinstance(pr_key, str) or not pr_key.isdecimal():
                raise AcceptanceFixtureError("review_results PR keys must be positive decimal strings")
            pr = _positive_pr(int(pr_key), "review_results PR")
            channel_values = _mapping(channels, f"review_results[{pr_key!r}]")
            for channel in ("hosted", "cli"):
                raw = channel_values.get(channel, [])
                if not isinstance(raw, list):
                    raise AcceptanceFixtureError(
                        f"review_results[{pr_key!r}][{channel!r}] must be an array"
                    )
                checked: list[Mapping[str, Any]] = []
                for index, item in enumerate(raw):
                    if not isinstance(item, Mapping):
                        raise AcceptanceFixtureError(
                            f"review_results[{pr_key!r}][{channel!r}][{index}] must be an object"
                        )
                    status = str(item.get("status", "")).casefold()
                    if status not in _SIMULATED_RESULTS:
                        raise AcceptanceFixtureError(
                            f"review_results[{pr_key!r}][{channel!r}][{index}] has unsupported status {status!r}"
                        )
                    if "record_evidence" in item and not isinstance(item["record_evidence"], bool):
                        raise AcceptanceFixtureError(
                            f"review_results[{pr_key!r}][{channel!r}][{index}] record_evidence must be boolean"
                        )
                    if "evidence" in item and not isinstance(item["evidence"], Mapping):
                        raise AcceptanceFixtureError(
                            f"review_results[{pr_key!r}][{channel!r}][{index}] evidence must be an object"
                        )
                    checked.append(dict(item))
                self._results[(pr, channel)] = tuple(checked)

    def _sidecar(self) -> dict[str, Any]:
        if not self.path.exists():
            return {"schema_version": 1, "repository": self.repository, "evidence": [], "result_positions": {}}
        try:
            payload = json.loads(self.path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError) as exc:
            raise AcceptanceFixtureError("isolated synthetic evidence is unreadable") from exc
        required = {"schema_version", "repository", "evidence"}
        if not isinstance(payload, dict) or not required <= set(payload) or set(payload) - (required | {"result_positions"}):
            raise AcceptanceFixtureError("isolated synthetic evidence has an invalid schema")
        if payload["schema_version"] != 1 or payload["repository"] != self.repository:
            raise AcceptanceFixtureError("isolated synthetic evidence belongs to another fixture repository")
        entries = payload["evidence"]
        if not isinstance(entries, list) or any(not isinstance(item, dict) for item in entries):
            raise AcceptanceFixtureError("isolated synthetic evidence entries are invalid")
        positions = payload.get("result_positions", {})
        if not isinstance(positions, dict) or any(
            not isinstance(key, str)
            or not isinstance(value, int)
            or isinstance(value, bool)
            or value < 0
            for key, value in positions.items()
        ):
            raise AcceptanceFixtureError("isolated synthetic result positions are invalid")
        return {
            "schema_version": 1,
            "repository": self.repository,
            "evidence": entries,
            "result_positions": positions,
        }

    def _recorded(self) -> list[dict[str, Any]]:
        return self._sidecar()["evidence"]

    def _write_sidecar(self, payload: Mapping[str, Any]) -> None:
        with tempfile.NamedTemporaryFile(
            mode="w", encoding="utf-8", dir=self.path.parent, prefix=f".{self.path.name}.", delete=False
        ) as handle:
            temporary = Path(handle.name)
            os.fchmod(handle.fileno(), 0o600)
            json.dump(dict(payload), handle, sort_keys=True)
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

    def history(self, pr: int, channel: str) -> Sequence[Any]:
        baseline = self._values.get((pr, channel), ())
        recorded = tuple(
            item
            for item in self._recorded()
            if item.get("pr") == pr and item.get("channel", "cli") == channel
        )
        return (*baseline, *recorded)

    def review_stop_audit(
        self,
        pr: int,
        expected_anchor: Mapping[str, Any],
        retained_ambiguous_fingerprints: Sequence[str] = (),
    ) -> dict[str, Any]:
        """Expose complete fixture evidence through the live stop-audit contract."""

        live = self.github.pull_request(pr)
        expected_head = expected_anchor.get("child_head")
        expected_parent = expected_anchor.get("parent_head")
        expected_parent_identity = expected_anchor.get("parent_identity")
        if (
            not isinstance(expected_head, str)
            or expected_head.casefold() != live.head.casefold()
            or not isinstance(expected_parent, str)
            or expected_parent.casefold() != live.base_tip.casefold()
            or (
                isinstance(expected_parent_identity, str)
                and not expected_parent_identity.isdecimal()
                and expected_parent_identity != live.base_ref
            )
        ):
            raise AcceptanceFixtureError("fixture PR head or parent moved during review stop")

        pins = tuple(retained_ambiguous_fingerprints)
        if any(not isinstance(value, str) or re.fullmatch(r"[0-9a-f]{64}", value) is None for value in pins):
            raise AcceptanceFixtureError("fixture stop audit received a malformed terminal ambiguity fingerprint")
        if len(set(pins)) != len(pins):
            raise AcceptanceFixtureError("fixture stop audit received duplicate terminal ambiguity fingerprints")

        histories = {channel: tuple(self.history(pr, channel)) for channel in ("hosted", "cli")}
        terminal = [
            {"channel": channel, **dict(value)}
            for channel, values in histories.items()
            for value in values
            if isinstance(value, Mapping) and value.get("terminal_ambiguous") is True
        ]
        retained: list[dict[str, Any]] = []
        for fingerprint in pins:
            matches = [item for item in terminal if item.get("fingerprint") == fingerprint]
            if len(matches) != 1:
                raise AcceptanceFixtureError("fixture stop pin does not identify one terminal ambiguous observation")
            retained.append(matches[0])
        retained_fingerprints = {item["fingerprint"] for item in retained}

        active_reservations: list[Any] = []
        unmatched_responses: list[Any] = []
        ambiguous_responses: list[Any] = []
        unresolved_findings: list[Any] = []
        checkpoints: list[dict[str, Any]] = []
        for channel, values in histories.items():
            for value in values:
                if not isinstance(value, Mapping):
                    raise AcceptanceFixtureError("fixture stop evidence entries must be objects")
                if value.get("active_reservation") is True or value.get("active_review") is True:
                    active_reservations.append(value.get("checkpoint", "active fixture review"))
                if value.get("unmatched_response") is True and value.get("fingerprint") not in retained_fingerprints:
                    unmatched_responses.append(value.get("checkpoint", "unmatched fixture response"))
                if value.get("ambiguous_response") is True and value.get("fingerprint") not in retained_fingerprints:
                    ambiguous_responses.append(value.get("checkpoint", "ambiguous fixture response"))
                if any(
                    value.get(flag) is True
                    for flag in ("held", "unstable", "unreconciled", "parent_moved", "over_ceiling", "rate_limited", "actionable")
                ) and value.get("fingerprint") not in retained_fingerprints:
                    unresolved_findings.append(value.get("reason") or value.get("checkpoint", "unresolved fixture finding"))
                if value.get("completed") is True and value.get("attributable") is True:
                    checkpoints.append({"channel": channel, **dict(value)})

        return {
            "complete": True,
            "head": live.head,
            "anchor": dict(expected_anchor),
            "active_reservations": active_reservations,
            "unmatched_responses": unmatched_responses,
            "ambiguous_responses": ambiguous_responses,
            "unresolved_findings": unresolved_findings,
            "checkpoints": checkpoints,
            "ambiguous_terminal_responses": terminal,
            "retained_ambiguous": retained,
        }

    def legacy_transition_reauthorization_audit(
        self,
        pr: int,
        expected_hosted_fingerprints: tuple[str, ...],
        expected_anchor: Mapping[str, Any],
    ) -> dict[str, Any]:
        """Treat the isolated fixture's explicit evidence arrays as its complete source."""

        if pr not in {number for number, _ in self._values}:
            raise AcceptanceFixtureError(f"fixture has no evidence history for PR #{pr}")
        if any(not isinstance(value, str) or re.fullmatch(r"[0-9a-f]{64}", value) is None for value in expected_hosted_fingerprints):
            raise AcceptanceFixtureError("fixture retirement audit received a malformed Hosted fingerprint")
        live = self.github.pull_request(pr)
        expected_head = expected_anchor.get("child_head")
        expected_base_ref = expected_anchor.get("live_base_ref")
        expected_base_tip = expected_anchor.get("live_base_tip")
        if (
            not isinstance(expected_head, str)
            or expected_head.casefold() != live.head.casefold()
            or not isinstance(expected_base_ref, str)
            or expected_base_ref != live.base_ref
            or not isinstance(expected_base_tip, str)
            or expected_base_tip.casefold() != live.base_tip.casefold()
        ):
            raise AcceptanceFixtureError("fixture PR head or base moved during missing Hosted fingerprint retirement")
        return {
            "complete": True,
            "active_reservations": [],
            "unmatched_responses": [],
            "ambiguous_responses": [],
            "unresolved_findings": [],
        }
    def next_result(self, target: ReviewTarget, channel: str) -> Mapping[str, Any] | None:
        """Consume one configured result exactly once across command invocations."""

        pr = target.snapshot.number
        configured = self._results.get((pr, channel), ())
        if not configured:
            return None
        key = _result_key(pr, channel)
        with self.lock_path.open("a+", encoding="utf-8") as lock:
            os.fchmod(lock.fileno(), 0o600)
            fcntl.flock(lock.fileno(), fcntl.LOCK_EX)
            try:
                payload = self._sidecar()
                positions = payload["result_positions"]
                index = positions.get(key, 0)
                if index >= len(configured):
                    raise AcceptanceFixtureError(
                        f"review_results for PR #{pr} {channel} are exhausted"
                    )
                result = dict(configured[index])
                result["_fixture_result_position"] = index + 1
                positions[key] = index + 1
                self._write_sidecar(payload)
                return result
            finally:
                fcntl.flock(lock.fileno(), fcntl.LOCK_UN)

    def record_result(self, target: ReviewTarget, channel: str, result: Mapping[str, Any]) -> str | None:
        """Persist only explicitly requested terminal evidence from a fixture result."""

        if not result.get("record_evidence", False):
            return None
        status = str(result.get("status", "")).casefold()
        if status not in _SIMULATED_RESULTS:
            raise AcceptanceFixtureError(f"unsupported simulated review result status {status!r}")
        supplied = result.get("evidence", {})
        if not isinstance(supplied, Mapping):
            raise AcceptanceFixtureError("simulated review result evidence must be an object")
        if status in {"rate_limited", "partial", "stale", "duplicate"}:
            completed = False
            attributable = False
        else:
            completed = True
            attributable = True
        checkpoint = supplied.get("checkpoint") or result.get("checkpoint")
        if checkpoint is None:
            result_position = result.get("_fixture_result_position", 1)
            if (
                isinstance(result_position, bool)
                or not isinstance(result_position, int)
                or result_position <= 0
            ):
                raise AcceptanceFixtureError("simulated review result position must be a positive integer")
            checkpoint = (
                f"fixture-{channel}-{target.snapshot.number}-{target.snapshot.head_sha[:12]}"
                f"-result-{result_position}"
            )
        if not isinstance(checkpoint, str) or not checkpoint.strip():
            raise AcceptanceFixtureError("simulated review result checkpoint must be non-empty")
        accepted = supplied.get("accepted", result.get("accepted", 1 if status == "productive" else 0))
        raw = supplied.get("raw", result.get("raw", accepted))
        if isinstance(accepted, bool) or not isinstance(accepted, int) or accepted < 0:
            raise AcceptanceFixtureError("simulated review result accepted count must be non-negative")
        if isinstance(raw, bool) or not isinstance(raw, int) or raw < accepted:
            raise AcceptanceFixtureError("simulated review result raw count must be >= accepted")
        evidence = {
            "pr": target.snapshot.number,
            "head": target.snapshot.head_sha,
            "child_head": target.snapshot.head_sha,
            "parent_identity": str(target.parent.pr_number or target.parent.ref_name),
            "parent_head": target.parent.head_sha,
            "merge_base": target.merge_base,
            "patch_id": target.patch_identity,
            "checkpoint": checkpoint,
            "completed": completed,
            "attributable": attributable,
            "anchored": bool(supplied.get("anchored", completed)),
            "corrected_state": bool(supplied.get("corrected_state", completed)),
            "provisional": bool(supplied.get("provisional", False)),
            "accepted": accepted,
            "raw": raw,
            "rate_limited": status == "rate_limited",
        }
        protected = {
            "pr",
            "head",
            "child_head",
            "parent_identity",
            "parent_head",
            "merge_base",
            "patch_id",
        }
        for key, value in supplied.items():
            if key not in evidence and key not in protected:
                evidence[key] = value
        with self.lock_path.open("a+", encoding="utf-8") as lock:
            os.fchmod(lock.fileno(), 0o600)
            fcntl.flock(lock.fileno(), fcntl.LOCK_EX)
            try:
                payload = self._sidecar()
                evidence_entries = payload["evidence"]
                if any(
                    item.get("pr") == target.snapshot.number
                    and item.get("channel", "cli") == channel
                    and item.get("checkpoint") == checkpoint
                    for item in evidence_entries
                ):
                    return None
                evidence["channel"] = channel
                evidence_entries.append(evidence)
                self._write_sidecar(payload)
            finally:
                fcntl.flock(lock.fileno(), fcntl.LOCK_UN)
        return checkpoint
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
                existing = (
                    *self._values.get((pr, "cli"), ()),
                    *(item for item in recorded if item.get("channel", "cli") == "cli"),
                )
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
                        "channel": "cli",
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
                payload = self._sidecar()
                payload["evidence"] = recorded
                self._write_sidecar(payload)
            finally:
                fcntl.flock(lock.fileno(), fcntl.LOCK_UN)


class FixtureReviewAdapter:
    """Return deterministic review results without posting or consuming quota."""

    simulated = True

    def __init__(self, channel: str, evidence: FixtureEvidence) -> None:
        self.channel = channel
        self.evidence = evidence

    def __call__(self, target: ReviewTarget, **kwargs: Any) -> dict[str, Any]:
        configured = self.evidence.next_result(target, self.channel)
        result = dict(configured) if configured is not None else {"status": "dry"}
        checkpoint = self.evidence.record_result(target, self.channel, result)
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
            "result": result["status"],
            "recorded_checkpoint": checkpoint,
            "recorded_evidence": checkpoint is not None,
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
            "review_results",
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
        self.evidence = FixtureEvidence(payload, self.state_path, self.repository, self.github)
        self.pull_requests = pull_requests
        self.initial_stack = tuple(ordered)

    def controller(self) -> ReviewController:
        store = state.StateStore(self.state_path)
        store.update(
            lambda current: state.ReviewState(ordered_prs=self.initial_stack)
            if not store.path.exists()
            else current
        )
        controller = ReviewController(
            store=store,
            github=self.github,
            git=self.git,
            evidence=self.evidence,
            default_base_ref=self.default_base_ref,
            repository=self.repository,
            hosted_adapter=FixtureReviewAdapter("hosted", self.evidence),
            cli_adapter=FixtureReviewAdapter("cli", self.evidence),
            isolated_fixture=True,
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
