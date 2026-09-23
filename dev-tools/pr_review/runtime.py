"""Live GitHub, evidence, and review adapters for the unified controller."""

from __future__ import annotations

import fcntl
import json
import os
import subprocess
from collections.abc import Sequence
from datetime import datetime, timezone
from typing import Any

from . import evidence, github, hosted
from .cli_runner import PullRequestSnapshot, ReviewRunnerError, ReviewTarget, run_cli_review
from .controller import ControllerError, DefaultGitProvider, ReviewController
from .state import StateStore


class LiveGitHub:
    """The exact live GitHub boundary shared by selection and review preflight."""

    def __init__(self, repo: str) -> None:
        self.repo = github.infer_repo(repo)

    def metadata(self, number: int) -> dict[str, Any]:
        return github.fetch_pr_metadata(self.repo, number)

    def pull_request(self, number: int) -> PullRequestSnapshot:
        value = self.metadata(number)
        return PullRequestSnapshot(
            number=int(value["number"]),
            state=str(value["state"]),
            base_ref_name=str(value["baseRefName"]),
            base_sha=str(value["baseRefOid"]),
            head_sha=str(value["headRefOid"]),
            head_ref_name=str(value["headRefName"]),
            changed_files=int(value["changedFiles"]),
            mergeable=str(value["mergeable"]),
            merged=value.get("mergedAt") is not None,
            base_exists=True,
        )

    # cli_runner.GitHubReader
    def pull_request_files(self, number: int) -> Sequence[str]:
        values = github.fetch_api_endpoint(f"repos/{self.repo}/pulls/{number}/files?per_page=100")
        paths = [value.get("filename") for value in values]
        if any(not isinstance(path, str) or not path for path in paths):
            raise ReviewRunnerError("GitHub pull-request file list is malformed")
        return [str(path) for path in paths]

    def branch_head(self, ref_name: str) -> str:
        return github.branch_head(self.repo, ref_name)


class LiveEvidence:
    """Map complete live comments plus private captures into policy evidence."""

    def __init__(self, repo: str, live: LiveGitHub) -> None:
        self.repo = repo
        self.live = live
        self._payloads: dict[int, dict[str, Any]] = {}
        self._histories: dict[tuple[int, str], list[dict[str, Any]]] = {}

    def _payload(self, pr: int) -> dict[str, Any]:
        if pr not in self._payloads:
            self._payloads[pr] = github.fetch_pull_request(self.repo, pr)
        return self._payloads[pr]

    @staticmethod
    def _comments(payload: dict[str, Any]) -> list[dict[str, Any]]:
        values: list[dict[str, Any]] = []
        for item in payload["data"]["repository"]["pullRequest"]["comments"]["nodes"]:
            values.append(
                {
                    "id": github.immutable_database_id(item),
                    "body": item.get("body"),
                    "created_at": item.get("createdAt"),
                    "updated_at": item.get("updatedAt"),
                }
            )
        return values

    @staticmethod
    def _reviews(payload: dict[str, Any]) -> list[dict[str, Any]]:
        result: list[dict[str, Any]] = []
        for item in payload["data"]["repository"]["pullRequest"]["reviews"]["nodes"]:
            result.append(
                {
                    "id": github.immutable_database_id(item),
                    "user": {"login": ((item.get("author") or {}).get("login"))},
                    "state": item.get("state"),
                    "submitted_at": item.get("submittedAt"),
                    "commit_id": ((item.get("commit") or {}).get("oid")),
                }
            )
        return result

    @staticmethod
    def _anchor(metadata: dict[str, str]) -> dict[str, Any]:
        parent_pr = metadata.get("parent_pr")
        parent_ref = metadata.get("parent_ref", metadata.get("base_ref_name", ""))
        parent_identity = parent_ref
        if parent_pr and parent_pr not in {"None", "null"}:
            parent_identity = parent_pr
        patch = metadata.get("patch_identity", metadata.get("patch_id", ""))
        return {
            "child_head": metadata.get("child_head_sha", metadata.get("candidate_sha", "")),
            "parent_identity": parent_identity,
            "parent_head": metadata.get("parent_sha", metadata.get("base_tip_sha", "")),
            "merge_base": metadata.get("merge_base", ""),
            "patch_id": patch,
        }

    def history(self, pr: int, channel: str) -> Sequence[dict[str, Any]]:
        key = (pr, channel)
        if key in self._histories:
            return self._histories[key]
        payload = self._payload(pr)
        live = self.live.pull_request(pr)
        head = live.head_sha
        checkpoints, _ = evidence.parse_checkpoint_comments(self._comments(payload))
        reviews = self._reviews(payload)
        values: list[dict[str, Any]] = []
        for checkpoint in checkpoints:
            if checkpoint.type.casefold() != channel:
                continue
            exact_head = (
                head
                if checkpoint.reviewed_sha and head.casefold().startswith(checkpoint.reviewed_sha.casefold())
                else ""
            )
            completed = False
            attributable = False
            provisional = False
            anchor: dict[str, Any] = {}
            if channel == "cli":
                try:
                    capture = evidence.load_cli_capture(checkpoint, self.repo, pr)
                except evidence.EvidenceError:
                    capture = None
                if capture is not None:
                    exact_head = capture.metadata.get("candidate_sha", exact_head)
                    completed = attributable = True
                    provisional = capture.metadata.get("provisional", "false").casefold() == "true"
                    anchor = self._anchor(capture.metadata)
            else:
                proof = evidence.hosted_checkpoint_evidence(checkpoint, reviews, head)
                completed = attributable = proof.get("status") == "completed"
                if completed:
                    exact_head = str(proof["commit_id"])
                for path in hosted.trigger_record_paths(self.repo, pr):
                    try:
                        record = hosted.load_trigger_record(path, self.repo, pr)
                    except (OSError, ValueError, json.JSONDecodeError):
                        continue
                    if record.get("head_sha") == exact_head and isinstance(record.get("anchor"), dict):
                        anchor = dict(record["anchor"])
                        break
            anchor_complete = all(
                isinstance(anchor.get(name), str) and bool(anchor[name])
                for name in ("child_head", "parent_identity", "parent_head", "merge_base", "patch_id")
            )
            values.append(
                {
                    "pr": pr,
                    "head": exact_head,
                    "checkpoint": str(checkpoint.comment_id or checkpoint.created_at),
                    "completed": completed,
                    "attributable": attributable,
                    "anchored": anchor_complete if completed else None,
                    "accepted": checkpoint.accepted,
                    "raw": checkpoint.raw_found,
                    "corrected_state": completed and exact_head == head,
                    "provisional": provisional,
                    "reason": capture.metadata.get("reason", "") if channel == "cli" and capture is not None else "",
                    **anchor,
                }
            )
        if channel == "hosted":
            paths = hosted.trigger_record_paths(self.repo, pr)
            if paths:
                try:
                    record = hosted.load_trigger_record(paths[0], self.repo, pr)
                    state = hosted.trigger_state(self.repo, pr, payload, record, paths[0])
                except (OSError, ValueError, KeyError, TypeError, json.JSONDecodeError):
                    state = None
                if state and state.state in {"rate_limited", "active", "awaiting_response", "ambiguous", "timed_out"}:
                    values.append(
                        {
                            "pr": pr,
                            "head": state.head_sha,
                            "checkpoint": f"trigger:{state.trigger_comment_id or 'pending'}",
                            "rate_limited": state.state == "rate_limited",
                            "held": state.state in {"active", "awaiting_response"},
                            "unstable": state.state in {"ambiguous", "timed_out"},
                        }
                    )
        self._histories[key] = values
        return values


class HostedRunner:
    """Post one full Hosted request with a durable pre/post identity boundary."""

    def __init__(self, repo: str, live: LiveGitHub) -> None:
        self.repo = repo
        self.live = live

    @staticmethod
    def _timestamp(value: str) -> datetime:
        return datetime.fromisoformat(value.replace("Z", "+00:00")).astimezone(timezone.utc)

    def __call__(self, target: ReviewTarget, *, expect_pr: int | None = None, **_: Any) -> dict[str, Any]:
        pr = target.snapshot.number
        hosted.assert_expected_pr(pr, expect_pr)
        before = self.live.pull_request(pr)
        if before != target.snapshot:
            raise ControllerError("pull request changed after Hosted target selection")
        if self.live.branch_head(target.parent.ref_name) != target.parent.head_sha:
            raise ControllerError("effective parent changed after Hosted target selection")
        path = hosted.default_trigger_record_path(self.repo, pr)
        path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
        lock_path = path.parent / "request.lock"
        with lock_path.open("a+", encoding="utf-8") as lock:
            try:
                fcntl.flock(lock.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
            except BlockingIOError as exc:
                raise ControllerError(f"another Hosted request is active for PR #{pr}") from exc
            if path.exists():
                record = hosted.load_trigger_record(path, self.repo, pr)
                payload = github.fetch_pull_request(self.repo, pr)
                state = hosted.trigger_state(self.repo, pr, payload, record, path)
                if state.state in {"active", "awaiting_response", "ambiguous", "unattributed", "timed_out"}:
                    raise ControllerError(f"existing Hosted trigger requires resolution: {state.state}")
                if state.state == "rate_limited":
                    if not state.cooldown_until:
                        raise ControllerError("Hosted review is rate limited with no attributable reset time")
                    if self._timestamp(state.cooldown_until) > datetime.now(timezone.utc):
                        raise ControllerError(f"Hosted review is rate limited until {state.cooldown_until}")
                trigger_id = (record.get("trigger") or {}).get("id")
                if isinstance(trigger_id, int) and trigger_id > 0:
                    archive = path.with_name(f"trigger-{trigger_id}.json")
                    if archive.exists():
                        raise ControllerError(f"Hosted trigger archive already exists: {archive}")
                    os.replace(path, archive)
                else:
                    raise ControllerError("existing Hosted trigger has no archivable identity")
            anchor = {
                "pr": pr,
                "child_head": target.snapshot.head_sha,
                "parent_identity": str(target.parent.pr_number or target.parent.ref_name),
                "parent_head": target.parent.head_sha,
                "merge_base": target.merge_base,
                "patch_id": target.patch_identity,
            }
            posting = {
                "schema_version": 2,
                "status": "posting",
                "repository": self.repo,
                "pr_number": pr,
                "head_sha": target.snapshot.head_sha,
                "anchor": anchor,
            }
            hosted.atomic_write_json(path, posting)
            try:
                completed = subprocess.run(
                    [
                        "gh",
                        "api",
                        f"repos/{self.repo}/issues/{pr}/comments",
                        "--method",
                        "POST",
                        "-f",
                        f"body={hosted.FULL_COMMAND}",
                    ],
                    check=True,
                    capture_output=True,
                    text=True,
                    timeout=hosted.GH_API_TIMEOUT_SECONDS if hasattr(hosted, "GH_API_TIMEOUT_SECONDS") else 120,
                )
                comment = json.loads(completed.stdout)
            except (OSError, subprocess.SubprocessError, json.JSONDecodeError) as exc:
                raise ControllerError("Hosted full-review request did not return a verified comment") from exc
            normalized = {
                "databaseId": comment.get("id"),
                "createdAt": comment.get("created_at"),
                "url": comment.get("html_url"),
                "body": comment.get("body"),
                "author": {"login": ((comment.get("user") or {}).get("login"))},
            }
            trigger_id = github.immutable_database_id(normalized)
            if trigger_id is None or hosted.normalize_command(str(normalized.get("body") or "")) != hosted.FULL_COMMAND:
                raise ControllerError("Hosted request response has no immutable full-review identity")
            after = self.live.pull_request(pr)
            status = "posted" if after == before else "posted_boundary_changed"
            record = {
                **posting,
                "status": status,
                "trigger": {
                    "id": trigger_id,
                    "created_at": normalized["createdAt"],
                    "url": normalized["url"],
                    "type": "full",
                    "command": hosted.FULL_COMMAND,
                },
            }
            hosted.atomic_write_json(path, record)
            if status != "posted":
                raise ControllerError("pull request changed across the Hosted posting boundary")
            return {
                "channel": "hosted",
                "pr": pr,
                "head": before.head_sha,
                "trigger_comment_id": trigger_id,
                "trigger_url": normalized["url"],
                "status": status,
                "anchor": anchor,
            }


def default_controller(repo: str | None = None) -> ReviewController:
    selected = github.infer_repo(repo)
    repository = github.repository_metadata(selected)
    live = LiveGitHub(selected)
    observations = LiveEvidence(selected, live)
    git_provider = DefaultGitProvider()
    hosted_runner = HostedRunner(selected, live)

    def cli_adapter(target: ReviewTarget, **kwargs: Any) -> Any:
        return run_cli_review(target, github=live, **kwargs)

    return ReviewController(
        store=StateStore(),
        github=live,
        git=git_provider,
        evidence=observations,
        default_base_ref=str(repository["ref_name"]),
        repository=selected,
        hosted_adapter=hosted_runner,
        cli_adapter=cli_adapter,
    )


__all__ = ["HostedRunner", "LiveEvidence", "LiveGitHub", "default_controller"]
