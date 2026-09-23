"""Live GitHub, evidence, and review adapters for the unified controller."""

from __future__ import annotations

import fcntl
import json
import os
import re
import subprocess
from collections.abc import Sequence
from datetime import datetime, timezone
from typing import Any

from . import evidence, github, hosted
from . import status as status_module
from .cli_runner import PullRequestSnapshot, ReviewRunnerError, ReviewTarget, run_cli_review
from .controller import ControllerError, DefaultGitProvider, ReviewController
from .state import StateError, StateStore, SummaryFindingDisposition, adjudicate_summary_findings

_PLAN_CEILING_PATTERN = re.compile(
    r"(?is)(?:(?:exceed\w*|too many|over|reject\w*|skip\w*).{0,120}"
    r"(?:file|files).{0,120}(?:plan|limit|ceiling|maximum|cap)"
    r"|(?:plan|review).{0,120}(?:file|files).{0,120}"
    r"(?:limit|ceiling|maximum|cap).{0,120}(?:exceed\w*|too many|over|reject\w*|skip\w*))"
)
_CODERABBIT_FILE_CEILING = 100


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

    def __init__(self, repo: str, live: LiveGitHub, state_store: StateStore | None = None) -> None:
        self.repo = repo
        self.live = live
        self.state_store = state_store
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
                    "author_login": ((item.get("author") or {}).get("login")),
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

    @staticmethod
    def _anchor_complete(anchor: Any) -> bool:
        return isinstance(anchor, dict) and all(
            isinstance(anchor.get(name), str) and bool(anchor[name])
            for name in ("child_head", "parent_identity", "parent_head", "merge_base", "patch_id")
        )

    def _hosted_trigger_for_checkpoint(
        self,
        pr: int,
        head: str,
        checkpoint: evidence.Checkpoint,
        reviews: list[dict[str, Any]],
        payload: dict[str, Any],
    ) -> tuple[dict[str, Any], dict[str, Any]] | None:
        for path in hosted.trigger_record_paths(self.repo, pr):
            try:
                record = hosted.load_trigger_record(path, self.repo, pr)
                state = hosted.trigger_state(self.repo, pr, payload, record, path)
            except (OSError, ValueError, KeyError, TypeError, json.JSONDecodeError):
                continue
            captured_head = record["head_sha"]
            proof = evidence.hosted_checkpoint_evidence(checkpoint, reviews, captured_head)
            if proof.get("status") != "completed":
                continue
            anchor = record.get("anchor")
            if (
                state.state == "completed"
                and state.head_sha.casefold() == captured_head.casefold()
                and state.response_id == checkpoint.hosted_review_id
                and state.response_id == proof.get("review_id")
                and captured_head.casefold() == str(proof.get("commit_id", "")).casefold()
                and isinstance(checkpoint.reviewed_sha, str)
                and captured_head.casefold().startswith(checkpoint.reviewed_sha.casefold())
                and self._anchor_complete(anchor)
                and anchor.get("child_head", "").casefold() == captured_head.casefold()
            ):
                trigger_id = state.trigger_comment_id
                trigger = next(
                    (
                        item
                        for item in payload["data"]["repository"]["pullRequest"]["comments"]["nodes"]
                        if github.immutable_database_id(item) == trigger_id
                    ),
                    None,
                )
                trigger_author = ((trigger or {}).get("author") or {}).get("login")
                checkpoint_author = checkpoint.author_login
                if (
                    isinstance(trigger_author, str)
                    and isinstance(checkpoint_author, str)
                    and checkpoint_author.casefold() == trigger_author.casefold()
                    and not github.is_coderabbit_login(checkpoint_author)
                ):
                    return record, proof
        return None

    @staticmethod
    def _summary_action_counts(
        payload: dict[str, Any],
        head: str,
        *,
        pr: int | None = None,
        dispositions: Sequence[SummaryFindingDisposition] = (),
    ) -> tuple[int, int, str | None]:
        try:
            selected = status_module._summary_evidence(payload, head)
        except status_module.StatusError:
            return 1, 1, None
        if selected.get("status") != "current":
            return 0, 0, None
        remaining = list(selected.get("findings", []))
        if pr is not None:
            remaining, _ = adjudicate_summary_findings(pr, head, selected, dispositions)
        counts = {"outside_diff": 0, "duplicate": 0}
        for finding in remaining:
            kind, count = finding.get("kind"), finding.get("count")
            if kind in counts and isinstance(count, int) and not isinstance(count, bool) and count >= 0:
                counts[kind] = count
        identity = selected.get("identity")
        pr = payload["data"]["repository"]["pullRequest"]
        items = (
            pr.get("reviews", {}).get("nodes", [])
            if selected.get("source") == "review"
            else pr.get("comments", {}).get("nodes", [])
        )
        url = next(
            (item.get("url") for item in items if github.immutable_database_id(item) == identity),
            None,
        )
        return counts["outside_diff"], counts["duplicate"], url

    def _global_blockers(
        self,
        pr: int,
        head: str,
        payload: dict[str, Any],
        *,
        changed_files: int | None = None,
        include_hosted_findings: bool = True,
    ) -> list[dict[str, Any]]:
        """Return blockers shared by review channels and Hosted-only obligations.

        Review threads and CodeRabbit summary actions are Hosted findings: they
        remain merge-readiness obligations, but must not suppress independent CLI
        discovery.  File-ceiling evidence remains global because it limits the
        candidate itself rather than one review channel.
        """

        pull = payload["data"]["repository"]["pullRequest"]
        values: list[dict[str, Any]] = []
        if include_hosted_findings:
            threads = (pull.get("reviewThreads") or {}).get("nodes")
            if not isinstance(threads, list):
                values.append(
                    {
                        "pr": pr,
                        "head": head,
                        "checkpoint": "review-threads:unavailable",
                        "unstable": True,
                        "reason": "complete GitHub review-thread evidence is unavailable",
                    }
                )
            else:
                unresolved = [
                    thread for thread in threads if isinstance(thread, dict) and thread.get("isResolved") is False
                ]
                malformed = any(
                    not isinstance(thread, dict)
                    or not isinstance(thread.get("isResolved"), bool)
                    or not isinstance(thread.get("isOutdated"), bool)
                    for thread in threads
                )
                if malformed:
                    values.append(
                        {
                            "pr": pr,
                            "head": head,
                            "checkpoint": "review-threads:malformed",
                            "unstable": True,
                            "reason": "GitHub review-thread evidence is malformed",
                        }
                    )
                if unresolved:
                    current = sum(thread["isOutdated"] is False for thread in unresolved)
                    outdated = sum(thread["isOutdated"] is True for thread in unresolved)
                    values.append(
                        {
                            "pr": pr,
                            "head": head,
                            "checkpoint": f"review-threads:{current}:{outdated}",
                            "held": True,
                            "reason": f"{current} unresolved current and {outdated} unresolved outdated review thread(s)",
                        }
                    )
            try:
                dispositions = self.state_store.load().summary_dispositions if self.state_store is not None else ()
                outside, duplicate, url = self._summary_action_counts(
                    payload,
                    head,
                    pr=pr,
                    dispositions=dispositions,
                )
            except (OSError, StateError, TypeError, ValueError):
                outside, duplicate, url = 1, 1, None
            if outside or duplicate:
                values.append(
                    {
                        "pr": pr,
                        "head": head,
                        "checkpoint": f"summary-actions:{outside}:{duplicate}",
                        "held": True,
                        "reason": f"latest CodeRabbit summary has {outside} outside-diff and {duplicate} duplicate actionable comment(s)",
                        "url": url,
                    }
                )
        all_reviews = [*pull.get("comments", {}).get("nodes", []), *pull.get("reviews", {}).get("nodes", [])]
        latest_exact_completion = datetime.min.replace(tzinfo=timezone.utc)
        current_diff_within_file_ceiling = (
            type(changed_files) is int and 0 <= changed_files <= _CODERABBIT_FILE_CEILING
        )
        for item in all_reviews:
            if not github.is_coderabbit_login((item.get("author") or {}).get("login", "")):
                continue
            body = item.get("body") or ""
            committed = (item.get("commit") or {}).get("oid")
            if not hosted._substantive(body) or item.get("state") == "DISMISSED":
                continue
            if committed != head and not hosted._matches_head(body, head):
                continue
            timestamp = hosted.parse_timestamp(item.get("submittedAt") or item.get("createdAt"))
            if timestamp and timestamp > latest_exact_completion:
                latest_exact_completion = timestamp
        for item in all_reviews:
            if not github.is_coderabbit_login((item.get("author") or {}).get("login", "")):
                continue
            body = item.get("body") or ""
            timestamp = hosted.parse_timestamp(item.get("createdAt") or item.get("submittedAt"))
            if (
                "<!-- This is an auto-generated comment: skip review by coderabbit.ai -->" in body
                and _PLAN_CEILING_PATTERN.search(body)
                and timestamp is not None
                and timestamp >= latest_exact_completion
                and not current_diff_within_file_ceiling
            ):
                values.append(
                    {
                        "pr": pr,
                        "head": head,
                        "checkpoint": f"over-ceiling:{github.immutable_database_id(item) or 'unknown'}",
                        "over_ceiling": True,
                        "reason": "CodeRabbit rejected the review because the PR exceeds its file ceiling; a topology decision is required",
                    }
                )
        return values

    def history(self, pr: int, channel: str) -> Sequence[dict[str, Any]]:
        key = (pr, channel)
        if key in self._histories:
            return self._histories[key]
        payload = self._payload(pr)
        live = self.live.pull_request(pr)
        head = live.head_sha
        checkpoints, _ = evidence.parse_checkpoint_comments(self._comments(payload))
        checkpoints.sort(key=lambda item: (item.created_at, item.comment_id or 0))
        reviews = self._reviews(payload)
        values: list[dict[str, Any]] = []
        public_cli_run_ids: set[str] = set()
        emitted_cli_sources: set[tuple[str, str]] = set()
        emitted_hosted_review_ids: set[int] = set()
        for checkpoint in checkpoints:
            if checkpoint.type.casefold() != channel:
                continue
            capture = None
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
                    if checkpoint.run_id:
                        public_cli_run_ids.add(checkpoint.run_id)
                    identity = (checkpoint.run_id or "", capture.source_identity or checkpoint.run_id or "")
                    if identity in emitted_cli_sources and not checkpoint.correction:
                        continue
                    if not checkpoint.correction:
                        emitted_cli_sources.add(identity)
                    exact_head = capture.metadata.get("candidate_sha", exact_head)
                    completed = attributable = True
                    provisional = capture.metadata.get("provisional", "false").casefold() == "true"
                    anchor = self._anchor(capture.metadata)
            else:
                matched = self._hosted_trigger_for_checkpoint(pr, head, checkpoint, reviews, payload)
                if matched is None:
                    continue
                record, proof = matched
                if checkpoint.hosted_review_id is not None and not checkpoint.correction:
                    if checkpoint.hosted_review_id in emitted_hosted_review_ids:
                        continue
                    emitted_hosted_review_ids.add(checkpoint.hosted_review_id)
                completed = attributable = True
                exact_head = str(proof["commit_id"])
                anchor = dict(record["anchor"])
            anchor_complete = self._anchor_complete(anchor)
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
                    "correction": checkpoint.correction,
                    "corrected_state": completed and exact_head == head,
                    "provisional": provisional,
                    "reason": capture.metadata.get("reason", "") if channel == "cli" and capture is not None else "",
                    **anchor,
                }
            )
        if channel == "cli":
            for capture in evidence.discover_cli_captures(self.repo, pr):
                run_id = capture.metadata.get("run_id", "")
                if run_id in public_cli_run_ids:
                    continue
                identity = (run_id, capture.source_identity or run_id)
                if identity in emitted_cli_sources:
                    continue
                emitted_cli_sources.add(identity)
                values.append(
                    {
                        "pr": pr,
                        "head": capture.metadata.get("candidate_sha", ""),
                        "checkpoint": f"pending-capture:{run_id}",
                        "completed": False,
                        "attributable": False,
                        "anchored": False,
                        "accepted": 0,
                        "raw": len(capture.findings),
                        "corrected_state": False,
                        "provisional": capture.metadata.get("provisional", "false").casefold() == "true",
                        "held": capture.metadata.get("candidate_sha", "").casefold() == head.casefold(),
                        "reason": (
                            "a successful private CLI capture has no public checkpoint and requires adjudication"
                            if capture.metadata.get("candidate_sha", "").casefold() == head.casefold()
                            else "a successful private CLI capture belongs to an older head"
                        ),
                        **self._anchor(capture.metadata),
                    }
                )
        if channel == "hosted":
            paths = hosted.current_trigger_record_paths(self.repo, pr)
            if len(paths) > 1:
                values.append(
                    {
                        "pr": pr,
                        "head": head,
                        "checkpoint": "trigger:multiple-current-reservations",
                        "unstable": True,
                        "held": True,
                        "reason": "multiple current Hosted trigger reservations require operator resolution",
                    }
                )
            for path in paths:
                try:
                    record = hosted.load_trigger_reservation(path, self.repo, pr)
                    state = hosted.trigger_state(self.repo, pr, payload, record, path)
                except (OSError, ValueError, KeyError, TypeError, json.JSONDecodeError):
                    values.append(
                        {
                            "pr": pr,
                            "head": head,
                            "checkpoint": f"trigger:unreadable:{path.name}",
                            "held": True,
                            "unstable": True,
                            "reason": "a discovered current Hosted reservation is unreadable or ambiguous",
                        }
                    )
                    continue
                if state.state == "rate_limited":
                    reset = hosted.parse_timestamp(state.cooldown_until)
                    now = datetime.now(timezone.utc)
                    if reset is not None and reset <= now:
                        continue
                    values.append(
                        {
                            "pr": pr,
                            "head": state.head_sha,
                            "checkpoint": f"trigger:{state.trigger_comment_id or 'pending'}",
                            "rate_limited": True,
                            "held": reset is None,
                            "unstable": reset is None,
                            "reason": "Hosted cooldown remains active"
                            if reset
                            else "Hosted cooldown has no attributable reset time",
                            "cooldown_until": state.cooldown_until,
                        }
                    )
                elif state.state in {"active", "awaiting_response", "ambiguous", "unattributed", "timed_out"}:
                    values.append(
                        {
                            "pr": pr,
                            "head": state.head_sha,
                            "checkpoint": f"trigger:{state.trigger_comment_id or 'pending'}",
                            "held": state.state in {"active", "awaiting_response", "ambiguous", "unattributed"},
                            "unstable": state.state in {"ambiguous", "unattributed", "timed_out"},
                            "reason": state.reason,
                        }
                    )
        values.extend(
            self._global_blockers(
                pr,
                head,
                payload,
                changed_files=live.changed_files,
                include_hosted_findings=channel == "hosted",
            )
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

    @staticmethod
    def _authenticated_login() -> str:
        try:
            completed = subprocess.run(["gh", "api", "user"], check=True, capture_output=True, text=True, timeout=30)
            login = json.loads(completed.stdout).get("login")
        except (OSError, subprocess.SubprocessError, json.JSONDecodeError, AttributeError) as exc:
            raise ControllerError("could not verify the authenticated GitHub user before Hosted posting") from exc
        if not isinstance(login, str) or not login.strip() or github.is_coderabbit_login(login):
            raise ControllerError("authenticated GitHub user has no trusted login identity")
        return login

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
            current_records = hosted.current_trigger_record_paths(self.repo, pr)
            if len(current_records) > 1:
                raise ControllerError("multiple current Hosted reservations require operator resolution")
            if current_records:
                current_path = current_records[0]
                record = hosted.load_trigger_reservation(current_path, self.repo, pr)
                payload = github.fetch_pull_request(self.repo, pr)
                if record.get("status") == "posting" and not isinstance(record.get("trigger"), dict):
                    try:
                        record = hosted._adopt_posting_reservation_locked(
                            current_path, self.repo, pr, before.head_sha, payload
                        )
                    except (OSError, ValueError, KeyError, TypeError, json.JSONDecodeError) as exc:
                        raise ControllerError(
                            f"existing Hosted posting reservation cannot be adopted safely: {exc}"
                        ) from exc
                state = hosted.trigger_state(self.repo, pr, payload, record, current_path)
                if state.state in {"active", "awaiting_response", "ambiguous", "unattributed", "timed_out"}:
                    raise ControllerError(f"existing Hosted trigger requires resolution: {state.state}")
                if state.state == "rate_limited":
                    if not state.cooldown_until:
                        raise ControllerError("Hosted review is rate limited with no attributable reset time")
                    if self._timestamp(state.cooldown_until) > datetime.now(timezone.utc):
                        raise ControllerError(f"Hosted review is rate limited until {state.cooldown_until}")
                trigger_id = (record.get("trigger") or {}).get("id")
                if isinstance(trigger_id, int) and trigger_id > 0:
                    archive = current_path.with_name(f"trigger-{trigger_id}.json")
                    if archive.exists():
                        raise ControllerError(f"Hosted trigger archive already exists: {archive}")
                    os.replace(current_path, archive)
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
            posting_actor = self._authenticated_login()
            posting_started_at = hosted.utc_now()
            posting = {
                "schema_version": 2,
                "status": "posting",
                "repository": self.repo,
                "pr_number": pr,
                "head_sha": target.snapshot.head_sha,
                "anchor": anchor,
                "posting_started_at": posting_started_at,
                "posting_actor_login": posting_actor,
            }
            # Establish a durable post-reservation boundary before issuing
            # POST.  Do all live identity and comment-floor checks while the
            # request lock is held, then write one complete reservation.  A
            # floor-fetch failure must leave no trigger.json and must never
            # reach the POST below.
            try:
                reservation_payload = github.fetch_pull_request(self.repo, pr)
                reservation_pr = reservation_payload["data"]["repository"]["pullRequest"]
                reservation_head = reservation_pr.get("headRefOid") if isinstance(reservation_pr, dict) else None
                if (
                    not isinstance(reservation_pr, dict)
                    or not isinstance(reservation_head, str)
                    or reservation_head.casefold() != target.snapshot.head_sha.casefold()
                ):
                    raise ControllerError("pull request changed before the Hosted posting boundary")
                if self.live.branch_head(target.parent.ref_name) != target.parent.head_sha:
                    raise ControllerError("effective parent changed before the Hosted posting boundary")
                posting["posting_comment_id_floor"] = hosted._comment_id_floor(reservation_payload)
            except (OSError, KeyError, TypeError, ValueError, json.JSONDecodeError) as exc:
                if isinstance(exc, ControllerError):
                    raise
                raise ControllerError(
                    f"could not establish the Hosted pre-POST comment identity floor: {exc}"
                ) from exc
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
            if (
                trigger_id is None
                or not isinstance(normalized["author"].get("login"), str)
                or normalized["author"]["login"].casefold() != posting_actor.casefold()
                or hosted.normalize_command(str(normalized.get("body") or "")) != hosted.FULL_COMMAND
            ):
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
                    "author_login": normalized["author"]["login"],
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
    store = StateStore()
    observations = LiveEvidence(selected, live, store)
    git_provider = DefaultGitProvider()
    hosted_runner = HostedRunner(selected, live)

    def cli_adapter(target: ReviewTarget, **kwargs: Any) -> Any:
        return run_cli_review(target, github=live, **kwargs)

    return ReviewController(
        store=store,
        github=live,
        git=git_provider,
        evidence=observations,
        default_base_ref=str(repository["ref_name"]),
        repository=selected,
        hosted_adapter=hosted_runner,
        cli_adapter=cli_adapter,
    )


__all__ = ["HostedRunner", "LiveEvidence", "LiveGitHub", "default_controller"]
