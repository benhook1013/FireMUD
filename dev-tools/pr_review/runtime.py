"""Live GitHub, evidence, and review adapters for the unified controller."""

from __future__ import annotations

import fcntl
import hashlib
import json
import os
import re
import stat
import subprocess
import uuid
from collections.abc import Callable, Mapping, Sequence
from contextlib import ExitStack
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from . import evidence, github, hosted, sqlite_hosted_capture
from . import status as status_module
from .cli_runner import PullRequestSnapshot, ReviewRunnerError, ReviewTarget, run_cli_review
from .controller import ControllerError, DefaultGitProvider, ReviewController, StaleReviewTarget
from .sqlite_review_records import SqliteReviewRecords
from .state import (
    ControllerStateStore,
    StateError,
    StateStore,
    SummaryFindingDisposition,
    adjudicate_summary_findings,
    observation_fingerprint,
    sqlite_state_path,
)

_PLAN_CEILING_PATTERN = re.compile(
    r"(?is)(?:(?:exceed\w*|too many|over|reject\w*|skip\w*).{0,120}"
    r"(?:file|files).{0,120}(?:plan|limit|ceiling|maximum|cap)"
    r"|(?:plan|review).{0,120}(?:file|files).{0,120}"
    r"(?:limit|ceiling|maximum|cap).{0,120}(?:exceed\w*|too many|over|reject\w*|skip\w*))"
)
_HOSTED_CODERABBIT_FILE_CEILING = 100
_PREPOST_ABANDONED_PATTERN = re.compile(r"^prepost-abandoned-[0-9a-f]{20}\.json$")


class LiveGitHub:
    """The exact live GitHub boundary shared by selection and review preflight."""

    def __init__(self, repo: str) -> None:
        self.repo = github.infer_repo(repo)

    def metadata(self, number: int) -> dict[str, Any]:
        return github.fetch_pr_metadata(self.repo, number)

    def batch_pull_requests(self, numbers: Sequence[int]) -> dict[int, dict[str, Any] | None]:
        """Fetch the current lightweight identity/activity view for a whole stack."""

        return github.fetch_pr_identity_batch(self.repo, numbers)

    def pull_request(self, number: int) -> PullRequestSnapshot:
        value = self.metadata(number)
        head_repository_value = value.get("headRepository")
        if not isinstance(head_repository_value, dict):
            raise ReviewRunnerError("GitHub pull-request head repository identity is malformed")
        if "nameWithOwner" in head_repository_value:
            head_repository = head_repository_value.get("nameWithOwner")
            if (
                not isinstance(head_repository, str)
                or head_repository.count("/") != 1
                or any(
                    not part or any(character.isspace() for character in part) for part in head_repository.split("/")
                )
            ):
                raise ReviewRunnerError("GitHub pull-request head repository identity is malformed")
        else:
            owner_value = value.get("headRepositoryOwner")
            owner = owner_value.get("login") if isinstance(owner_value, dict) else None
            name = head_repository_value.get("name")
            if (
                not isinstance(owner, str)
                or not owner
                or any(character.isspace() for character in owner)
                or not isinstance(name, str)
                or not name
                or any(character.isspace() for character in name)
            ):
                raise ReviewRunnerError("GitHub pull-request head repository identity is malformed")
            head_repository = f"{owner}/{name}"
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
            head_repository=head_repository,
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

    def __init__(
        self,
        repo: str,
        live: LiveGitHub,
        state_store: StateStore | ControllerStateStore | None = None,
    ) -> None:
        self.repo = repo
        self.live = live
        self.state_store = state_store
        self._payloads: dict[int, dict[str, Any]] = {}
        self._histories: dict[tuple[int, str], list[dict[str, Any]]] = {}

    def _payload(self, pr: int) -> dict[str, Any]:
        if pr not in self._payloads:
            self._payloads[pr] = github.fetch_pull_request(self.repo, pr)
        return self._payloads[pr]

    def prefetch_payload(self, pr: int) -> None:
        """Warm one independent PR snapshot for a read-only queue overview."""

        self._payload(pr)

    @staticmethod
    def _request_lock_is_held(path: Path) -> bool:
        """Check an existing request lock without creating or changing a file."""

        if not path.exists():
            return False
        try:
            with path.open("r") as handle:
                try:
                    fcntl.flock(handle.fileno(), fcntl.LOCK_SH | fcntl.LOCK_NB)
                except BlockingIOError:
                    return True
                fcntl.flock(handle.fileno(), fcntl.LOCK_UN)
        except OSError:
            # A lock file that cannot be inspected is not safe to treat as idle.
            return True
        return False

    def active_review_targets(
        self,
        pr_numbers: Sequence[int],
        identities: Mapping[int, Mapping[str, Any] | None],
    ) -> set[int]:
        """Find PRs with a live request or a possibly active Hosted trigger.

        The batch identity query carries only the most recent few public events.
        If that bounded sample cannot prove a trigger terminal, the PR is
        conservatively included for the normal complete evidence reconciliation.
        A repository-wide CLI lock does not encode its selected PR, so every
        configured PR becomes a deep candidate while that lock is held.
        """

        active: set[int] = set()
        active_trigger_states = {"active", "awaiting_response", "ambiguous", "unattributed", "timed_out"}
        terminal_trigger_states = {
            "completed",
            "failed",
            "failed_incomplete_coverage",
            "rate_limited",
            "noop",
            "retired",
        }
        if self.state_store is None:
            active.update(pr_numbers)
        else:
            state_path = self.state_store.path
            if state_path.name != "pr-review-stack.json":
                active.update(pr_numbers)
            else:
                cli_lock_path = state_path.parent.parent / "firemud" / "pr-review" / "cli.lock"
                if self._request_lock_is_held(cli_lock_path):
                    active.update(pr_numbers)
        for pr in pr_numbers:
            lock_path = hosted.default_trigger_record_path(self.repo, pr).parent / "request.lock"
            if self._request_lock_is_held(lock_path):
                active.add(pr)
            try:
                identity = identities.get(pr)
                if not isinstance(identity, Mapping):
                    active.add(pr)
                    continue
                comment_connection = identity.get("comments")
                comments = comment_connection.get("nodes") if isinstance(comment_connection, Mapping) else None
                if not isinstance(comments, list):
                    active.add(pr)
                    continue
                manual_trigger_seen = False
                for comment in comments:
                    if not isinstance(comment, Mapping):
                        manual_trigger_seen = True
                        break
                    author = comment.get("author")
                    login = author.get("login") if isinstance(author, Mapping) else None
                    body = comment.get("body")
                    if not isinstance(login, str) or not isinstance(body, str):
                        manual_trigger_seen = True
                        break
                    if not github.is_coderabbit_login(login) and hosted.normalize_command(body) == hosted.FULL_COMMAND:
                        manual_trigger_seen = True
                        break
                if manual_trigger_seen:
                    active.add(pr)
                    continue
                paths = hosted.current_trigger_record_paths(self.repo, pr)
                if len(paths) > 1:
                    active.add(pr)
                    continue
                if not paths:
                    continue
                path = paths[0]
                record = hosted.load_trigger_reservation(path, self.repo, pr)
                if record.get("status") in {"posting", "posted_boundary_changed", "posted_boundary_unverified"}:
                    active.add(pr)
                    continue
                if record.get("status") == "retired":
                    continue
                pull_request = dict(identity)
                if not isinstance(pull_request.get("comments"), Mapping) or not isinstance(
                    pull_request.get("reviews"), Mapping
                ):
                    active.add(pr)
                    continue
                payload = {"data": {"repository": {"pullRequest": pull_request}}}
                state = hosted.trigger_state(self.repo, pr, payload, record, path)
                if state.state in active_trigger_states or state.state not in terminal_trigger_states:
                    active.add(pr)
            except (OSError, ValueError, TypeError, KeyError, json.JSONDecodeError):
                active.add(pr)
        return active

    @staticmethod
    def _comments(payload: dict[str, Any]) -> list[dict[str, Any]]:
        values: list[dict[str, Any]] = []
        for item in payload["data"]["repository"]["pullRequest"]["comments"]["nodes"]:
            body = item.get("body")
            values.append(
                {
                    "id": github.immutable_database_id(item),
                    "body": body if isinstance(body, str) else "",
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
    def _complete_trigger_paths(repo: str, pr: int) -> list[str]:
        """Enumerate every current and archived trigger without hiding read failures."""

        root = hosted._git_common_dir()
        paths: list[str] = []
        for directory in (
            root / "firemud" / "hosted" / hosted._safe_repo(repo) / f"pr-{pr}",
            root / "coderabbit-review-logs" / "hosted" / hosted._safe_repo(repo) / f"pr-{pr}",
        ):
            try:
                directory_stat = directory.lstat()
            except FileNotFoundError:
                continue
            except OSError as error:
                raise ControllerError("Hosted trigger-record history cannot be completely inspected") from error
            if stat.S_ISLNK(directory_stat.st_mode) or not stat.S_ISDIR(directory_stat.st_mode):
                raise ControllerError("Hosted trigger-record history contains an unsafe directory")
            try:
                entries = list(directory.iterdir())
            except OSError as error:
                raise ControllerError("Hosted trigger-record history cannot be completely inspected") from error
            for path in entries:
                is_current = path.name == "trigger.json"
                is_archived = hosted.ARCHIVED.fullmatch(path.name) is not None
                is_prepost_abandoned = _PREPOST_ABANDONED_PATTERN.fullmatch(path.name) is not None
                if not is_current and not is_archived and not is_prepost_abandoned:
                    if path.name.startswith(("trigger-", "prepost-abandoned-")) and path.name.endswith(".json"):
                        raise ControllerError("Hosted trigger-record history contains a malformed archive name")
                    continue
                try:
                    entry_stat = path.lstat()
                except OSError as error:
                    raise ControllerError("a Hosted trigger record cannot be completely inspected") from error
                if stat.S_ISLNK(entry_stat.st_mode) or not stat.S_ISREG(entry_stat.st_mode):
                    raise ControllerError("Hosted trigger-record history contains an unsafe record")
                if is_prepost_abandoned:
                    LiveEvidence._validate_prepost_abandoned(path, repo, pr)
                    continue
                paths.append(str(path))
        return paths

    @staticmethod
    def _validate_prepost_abandoned(path: Any, repo: str, pr: int) -> None:
        """Accept only the existing durable proof that a pre-POST reservation was closed."""

        try:
            record = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError) as error:
            raise ControllerError("an archived Hosted pre-POST reservation is unreadable") from error
        recovery = record.get("recovery") if isinstance(record, dict) else None
        if (
            not isinstance(record, dict)
            or record.get("repository") != repo
            or record.get("pr_number") != pr
            or record.get("status") != "abandoned_prepost"
            or isinstance(record.get("trigger"), dict)
            or not isinstance(record.get("head_sha"), str)
            or not hosted.EXACT_SHA.fullmatch(record["head_sha"])
            or type(record.get("posting_comment_id_floor")) is not int
            or record["posting_comment_id_floor"] < 0
            or hosted.parse_timestamp(record.get("posting_started_at")) is None
            or not isinstance(record.get("posting_actor_login"), str)
            or not record["posting_actor_login"].strip()
            or hosted.is_coderabbit_login(record["posting_actor_login"])
            or not isinstance(recovery, dict)
            or recovery.get("action") != "operator_confirmed_prepost_abandon"
            or recovery.get("confirmed_not_posted") is not True
            or recovery.get("live_comment_history") != "complete_paginated_no_candidate"
            or not isinstance(recovery.get("expected_head_sha"), str)
            or recovery["expected_head_sha"].casefold() != record["head_sha"].casefold()
            or not isinstance(recovery.get("captured_head_sha"), str)
            or recovery["captured_head_sha"].casefold() != record["head_sha"].casefold()
            or hosted.parse_timestamp(recovery.get("at")) is None
            or not isinstance(recovery.get("reason"), str)
            or not recovery["reason"].strip()
        ):
            raise ControllerError("an archived Hosted pre-POST reservation lacks complete closure proof")

    @staticmethod
    def _full_review_commands(comments: list[dict[str, Any]]) -> list[tuple[int, datetime]]:
        commands: list[tuple[int, datetime]] = []
        for item in comments:
            author = (item.get("author") or {}).get("login")
            body = item.get("body")
            if hosted.is_coderabbit_login(author) or not isinstance(body, str):
                continue
            if hosted.normalize_command(body) != hosted.FULL_COMMAND:
                continue
            identity = github.immutable_database_id(item)
            created = hosted.parse_timestamp(item.get("createdAt"))
            if identity is None or created is None:
                raise ControllerError("a full-review trigger has incomplete public identity")
            commands.append((identity, created))
        commands.sort(key=lambda value: (value[1], value[0]))
        return commands

    @staticmethod
    def _bot_response_ids(comments: list[dict[str, Any]], reviews: list[dict[str, Any]]) -> list[tuple[int, datetime]]:
        responses: list[tuple[int, datetime]] = []
        for item, timestamp_field in (
            *((comment, "createdAt") for comment in comments),
            *((review, "submittedAt") for review in reviews),
        ):
            if not github.is_coderabbit_login((item.get("author") or {}).get("login")):
                continue
            body = item.get("body")
            if (
                timestamp_field == "createdAt"
                and isinstance(body, str)
                and "<!-- This is an auto-generated comment: summarize by coderabbit.ai -->" in body
            ):
                continue
            if timestamp_field == "submittedAt" and item.get("state") == "DISMISSED":
                continue
            identity = github.immutable_database_id(item)
            created = hosted.parse_timestamp(item.get(timestamp_field))
            if identity is None or created is None:
                raise ControllerError("a Hosted response has incomplete public identity")
            responses.append((identity, created))
        return responses

    @staticmethod
    def _public_response_state(
        item: dict[str, Any], timestamp_field: str, checkpoint_by_response: dict[int, evidence.Checkpoint]
    ) -> str | None:
        """Classify a public response when its private trigger record is gone."""
        return hosted.public_response_state(item, timestamp_field, checkpoint_by_response)

    @staticmethod
    def _uncheckpointed_hosted_observation(pr: int, state: hosted.TriggerState) -> dict[str, Any]:
        """Construct the held observation for a completed response without a checkpoint."""

        return {
            "pr": pr,
            "head": state.head_sha,
            "checkpoint": f"trigger-uncheckpointed:{state.response_id or 'unknown'}",
            "held": True,
            "reason": "completed Hosted review has no valid public checkpoint and requires adjudication",
        }

    def _terminal_ambiguous_hosted_observation(
        self,
        pr: int,
        record: Mapping[str, Any],
        state: hosted.TriggerState,
        payload: Mapping[str, Any],
    ) -> dict[str, Any] | None:
        """Return immutable identity for one verified terminal ambiguous response."""

        if state.state != "ambiguous" or state.terminal is not True or state.attributed is not False:
            return None
        trigger_id = state.trigger_comment_id
        response_id = state.response_id
        captured_head = record.get("head_sha")
        if (
            isinstance(trigger_id, bool)
            or not isinstance(trigger_id, int)
            or trigger_id <= 0
            or isinstance(response_id, bool)
            or not isinstance(response_id, int)
            or response_id <= 0
            or not isinstance(captured_head, str)
            or hosted.EXACT_SHA.fullmatch(captured_head) is None
        ):
            return None
        try:
            pull = payload["data"]["repository"]["pullRequest"]
            comments = pull["comments"]["nodes"]
            reviews = pull["reviews"]["nodes"]
        except (KeyError, TypeError):
            return None
        if not isinstance(comments, list) or not isinstance(reviews, list):
            return None
        triggers = [item for item in comments if github.immutable_database_id(item) == trigger_id]
        responses = [(item, "createdAt") for item in comments if github.immutable_database_id(item) == response_id] + [
            (item, "submittedAt") for item in reviews if github.immutable_database_id(item) == response_id
        ]
        if len(triggers) != 1 or len(responses) != 1:
            return None
        trigger = triggers[0]
        response, response_time_field = responses[0]
        trigger_at = trigger.get("createdAt")
        response_at = response.get(response_time_field)
        response_body = response.get("body")
        if (
            not isinstance(trigger_at, str)
            or hosted.parse_timestamp(trigger_at) is None
            or trigger_at != state.trigger_created_at
            or not isinstance(response_at, str)
            or hosted.parse_timestamp(response_at) is None
            or response_at != state.response_created_at
            or not isinstance(response_body, str)
            or not github.is_coderabbit_login((response.get("author") or {}).get("login"))
        ):
            return None
        observation = {
            "pr": pr,
            "trigger_id": trigger_id,
            "trigger_at": trigger_at,
            "response_id": response_id,
            "response_at": response_at,
            "response_updated_at": response.get("updatedAt"),
            "response_body_sha256": hashlib.sha256(response_body.encode("utf-8")).hexdigest(),
            "captured_head": captured_head,
            "state": state.state,
        }
        return {
            **observation,
            "reason": state.reason,
            "fingerprint": observation_fingerprint(observation),
        }

    def review_stop_audit(
        self,
        pr: int,
        expected_anchor: Mapping[str, Any],
        retained_ambiguous_fingerprints: Sequence[str] = (),
    ) -> dict[str, Any]:
        """Refresh complete review evidence for a human-directed channel stop.

        Retained ambiguous responses remain explicitly non-counting. Each pin
        must name one verified immutable terminal response; only those exact
        response blockers are removed from the generic audit.
        """

        required_anchor = ("child_head", "parent_identity", "parent_head", "merge_base", "patch_id")
        if not isinstance(expected_anchor, Mapping) or not self._anchor_complete(expected_anchor):
            raise ControllerError("review stop requires a complete current stack anchor")
        if "pr" in expected_anchor and expected_anchor.get("pr") != pr:
            raise ControllerError("review stop anchor belongs to another pull request")
        if any(not isinstance(expected_anchor.get(name), str) or not expected_anchor[name] for name in required_anchor):
            raise ControllerError("review stop requires a complete current stack anchor")
        pins = tuple(retained_ambiguous_fingerprints)
        if any(not isinstance(pin, str) or re.fullmatch(r"[0-9a-f]{64}", pin) is None for pin in pins):
            raise ControllerError("retained Hosted ambiguity requires an exact immutable fingerprint")
        if len(set(pins)) != len(pins):
            raise ControllerError("retained Hosted ambiguity fingerprints must be unique")
        # All earlier commands may have populated these caches. A stop decision
        # is an authorization boundary, so refresh the paginated public snapshot
        # and both channel histories before inspecting the pin.
        self._payloads.pop(pr, None)
        self._histories.pop((pr, "hosted"), None)
        self._histories.pop((pr, "cli"), None)
        payload = self._payload(pr)
        try:
            pull = payload["data"]["repository"]["pullRequest"]
            payload_head = pull.get("headRefOid")
            payload_base_ref = pull.get("baseRefName")
            payload_base_head = pull.get("baseRefOid")
            pull_number = pull.get("number")
        except (KeyError, TypeError) as error:
            raise ControllerError("complete paginated GitHub review evidence is unavailable") from error
        current = self.live.pull_request(pr)
        child_head = expected_anchor["child_head"]
        parent_head = expected_anchor["parent_head"]
        parent_identity = expected_anchor["parent_identity"]
        if (
            pull_number != pr
            or current.number != pr
            or not isinstance(payload_head, str)
            or not isinstance(payload_base_ref, str)
            or not isinstance(payload_base_head, str)
            or current.head_sha.casefold() != child_head.casefold()
            or payload_head.casefold() != child_head.casefold()
            or current.base_sha.casefold() != parent_head.casefold()
            or payload_base_head.casefold() != parent_head.casefold()
            or current.base_ref_name != payload_base_ref
            or (not parent_identity.isdecimal() and current.base_ref_name != parent_identity)
        ):
            raise ControllerError("pull-request head or parent moved from the review stop anchor")

        # The established audit validates paginated identities, trigger to
        # response linkage, public checkpoints, pending captures, and unresolved
        # review findings. Stop mode retains only unmatched records proven to
        # belong to another head as historical; current-head or unknown results
        # remain blockers. No transition fingerprints are reauthorized here.
        audit = self.legacy_transition_reauthorization_audit(
            pr,
            (),
            {
                "child_head": child_head,
                "live_base_ref": current.base_ref_name,
                "live_base_tip": parent_head,
            },
            allow_historical_unmatched=True,
        )
        payload = self._payload(pr)
        channel_history = {channel: list(self.history(pr, channel)) for channel in ("hosted", "cli")}

        ambiguous_terminal_responses: list[dict[str, Any]] = []
        terminal_rate_limits: list[dict[str, Any]] = []
        for path in self._complete_trigger_paths(self.repo, pr):
            try:
                record = hosted.load_trigger_record(path, self.repo, pr)
                state = hosted.trigger_state(self.repo, pr, payload, record, path)
            except (OSError, ValueError, KeyError, TypeError, json.JSONDecodeError) as error:
                raise ControllerError("a Hosted trigger record cannot be completely audited") from error
            observation = self._terminal_ambiguous_hosted_observation(pr, record, state, payload)
            if observation is not None:
                ambiguous_terminal_responses.append(observation)
            cooldown_value = getattr(state, "cooldown_until", None)
            cooldown_until = hosted.parse_timestamp(cooldown_value)
            trigger_id = state.trigger_comment_id
            response_id = state.response_id
            captured_head = record.get("head_sha")
            if (
                state.state == "rate_limited"
                and state.terminal is True
                and state.attributed is True
                and isinstance(trigger_id, int)
                and not isinstance(trigger_id, bool)
                and trigger_id > 0
                and isinstance(response_id, int)
                and not isinstance(response_id, bool)
                and response_id > 0
                and isinstance(captured_head, str)
                and hosted.EXACT_SHA.fullmatch(captured_head) is not None
                and cooldown_until is not None
                and cooldown_until > datetime.now(timezone.utc)
            ):
                terminal_rate_limits.append(
                    {
                        "trigger_id": trigger_id,
                        "response_id": response_id,
                        "captured_head": captured_head,
                        "cooldown_until": cooldown_value,
                        "terminal": True,
                        "attributable": True,
                    }
                )
        fingerprints = [item["fingerprint"] for item in ambiguous_terminal_responses]
        if len(set(fingerprints)) != len(fingerprints):
            raise ControllerError("terminal ambiguous Hosted responses have duplicate immutable fingerprints")
        retained_by_fingerprint: dict[str, dict[str, Any]] = {}
        for fingerprint in pins:
            matching = [item for item in ambiguous_terminal_responses if item["fingerprint"] == fingerprint]
            if len(matching) != 1:
                raise ControllerError("retained Hosted ambiguity does not identify one current immutable response")
            retained_by_fingerprint[fingerprint] = matching[0]

        active_reservations = list(audit["active_reservations"])
        unmatched_responses = list(audit["unmatched_responses"])
        ambiguous_responses = list(audit["ambiguous_responses"])
        if len(terminal_rate_limits) > active_reservations.count("rate_limited"):
            raise ControllerError("terminal Hosted rate-limit evidence cannot be isolated from other reservations")
        for _item in terminal_rate_limits:
            active_reservations.remove("rate_limited")
        if pins:
            terminal_count = len(ambiguous_terminal_responses)
            if (
                active_reservations.count("ambiguous") != terminal_count
                or ambiguous_responses.count("unattributed trigger response") != terminal_count
            ):
                raise ControllerError("terminal Hosted ambiguity cannot be isolated from other response ambiguity")
            for _fingerprint in pins:
                active_reservations.remove("ambiguous")
                ambiguous_responses.remove("unattributed trigger response")

        unresolved_findings = list(audit["unresolved_findings"])
        cli_pending = [
            item
            for item in channel_history["cli"]
            if item.get("held") is True or item.get("unstable") is True or item.get("unreconciled") is True
        ]
        if cli_pending:
            unresolved_findings.extend(
                str(item.get("reason") or item.get("checkpoint") or "unresolved CLI evidence") for item in cli_pending
            )
        checkpoints = [
            {"channel": channel, **item}
            for channel, values in channel_history.items()
            for item in values
            if item.get("completed") is True and item.get("attributable") is True
        ]
        blockers = [
            *(f"active Hosted reservation: {item}" for item in active_reservations),
            *(f"unmatched Hosted response: {item}" for item in unmatched_responses),
            *(f"ambiguous Hosted response: {item}" for item in ambiguous_responses),
            *(f"unresolved finding: {item}" for item in unresolved_findings),
        ]
        return {
            "complete": True,
            "head": current.head_sha,
            "anchor": dict(expected_anchor),
            "blockers": blockers,
            "active_reservations": active_reservations,
            "unmatched_responses": unmatched_responses,
            "historical_unmatched_responses": list(audit.get("historical_unmatched_responses", ())),
            "ambiguous_responses": ambiguous_responses,
            "unresolved_findings": unresolved_findings,
            "checkpoints": checkpoints,
            "ambiguous_terminal_responses": ambiguous_terminal_responses,
            "terminal_rate_limits": terminal_rate_limits,
            "retained_ambiguous": list(retained_by_fingerprint.values()),
        }

    def legacy_transition_reauthorization_audit(
        self,
        pr: int,
        expected_hosted_fingerprints: tuple[str, ...],
        expected_anchor: dict[str, Any],
        *,
        allow_historical_unmatched: bool = False,
    ) -> dict[str, Any]:
        """Audit complete Hosted history, optionally preserving older unmatched results for a stop."""

        # Retirement is the final authorization boundary. Earlier command checks may
        # have populated these caches, so refresh the complete public snapshot and
        # both derived channel histories before making the retirement decision.
        self._payloads.pop(pr, None)
        self._histories.pop((pr, "hosted"), None)
        self._histories.pop((pr, "cli"), None)
        payload = self._payload(pr)
        self.history(pr, "hosted")
        self.history(pr, "cli")
        try:
            pull = payload["data"]["repository"]["pullRequest"]
            comments_connection = pull["comments"]
            reviews_connection = pull["reviews"]
            threads_connection = pull["reviewThreads"]
            comments = comments_connection["nodes"]
            reviews = reviews_connection["nodes"]
            threads = threads_connection["nodes"]
        except (KeyError, TypeError) as error:
            raise ControllerError("complete paginated GitHub review/comment evidence is unavailable") from error
        try:
            expected_head = expected_anchor["child_head"]
            expected_base_ref = expected_anchor["live_base_ref"]
            expected_base_tip = expected_anchor["live_base_tip"]
        except (KeyError, TypeError) as error:
            raise ControllerError("missing Hosted fingerprint retirement lacks a complete current anchor") from error
        if (
            pull.get("number") != pr
            or not isinstance(expected_head, str)
            or not isinstance(pull.get("headRefOid"), str)
            or pull["headRefOid"].casefold() != expected_head.casefold()
            or not isinstance(expected_base_ref, str)
            or pull.get("baseRefName") != expected_base_ref
            or not isinstance(expected_base_tip, str)
            or not isinstance(pull.get("baseRefOid"), str)
            or pull["baseRefOid"].casefold() != expected_base_tip.casefold()
        ):
            raise ControllerError("GitHub PR head or base moved during missing Hosted fingerprint retirement")

        historical_unmatched_responses: list[str] = []

        def record_unmatched(message: str, *, historical: bool = False, ambiguous: bool = False) -> None:
            if allow_historical_unmatched and ambiguous:
                ambiguous_responses.append(message)
            elif allow_historical_unmatched and historical:
                historical_unmatched_responses.append(message)
            else:
                unmatched_responses.append(message)

        def response_matches_expected_head(response_id: int, response_item: Mapping[str, Any]) -> bool | None:
            """Return whether a public result names the stop head, or None if unknown."""

            def checkpoint_names_another_head(reviewed_sha: Any) -> bool | None:
                if not isinstance(reviewed_sha, str) or re.fullmatch(r"[0-9a-fA-F]{7,40}", reviewed_sha) is None:
                    return None
                return not expected_head.casefold().startswith(reviewed_sha.casefold())

            matching = checkpoints_by_response.get(response_id, [])
            if len(matching) == 1:
                reviewed_sha = matching[0].reviewed_sha
                if isinstance(reviewed_sha, str) and reviewed_sha:
                    names_another_head = checkpoint_names_another_head(reviewed_sha)
                    if names_another_head is not None:
                        return not names_another_head
            commit = (response_item.get("commit") or {}).get("oid")
            if isinstance(commit, str) and hosted.EXACT_SHA.fullmatch(commit):
                return commit.casefold() == expected_head.casefold()
            body = response_item.get("body")
            if isinstance(body, str):
                if hosted._matches_head(body, expected_head):
                    return True
                if hosted._scope_head(body) is not None:
                    return False
            return None

        latest = self.live.pull_request(pr)
        if (
            latest.number != pr
            or latest.head_sha.casefold() != expected_head.casefold()
            or latest.base_ref_name != expected_base_ref
            or latest.base_sha.casefold() != expected_base_tip.casefold()
        ):
            raise ControllerError("GitHub PR head or base moved during missing Hosted fingerprint retirement")
        if any(not isinstance(values, list) for values in (comments, reviews, threads)):
            raise ControllerError("complete paginated GitHub review/comment evidence is malformed")
        for item in (*comments, *reviews):
            if not isinstance(item, dict) or github.immutable_database_id(item) is None:
                raise ControllerError("complete paginated GitHub review/comment evidence lacks immutable identities")
        for thread in threads:
            thread_comments = thread.get("comments") if isinstance(thread, dict) else None
            if (
                not isinstance(thread, dict)
                or not isinstance(thread.get("isResolved"), bool)
                or not isinstance(thread.get("isOutdated"), bool)
                or not isinstance(thread_comments, dict)
                or not isinstance(thread_comments.get("nodes"), list)
            ):
                raise ControllerError("complete paginated GitHub review-thread evidence is malformed")
            if any(
                not isinstance(comment, dict) or github.immutable_database_id(comment) is None
                for comment in thread_comments["nodes"]
            ):
                raise ControllerError("complete paginated GitHub review-thread comments lack immutable identities")

        normalized_comments = self._comments(payload)
        checkpoints, unparsed = evidence.parse_checkpoint_comments(normalized_comments)
        if unparsed:
            raise ControllerError("GitHub history contains an unparsed review checkpoint")
        hosted_checkpoints = [item for item in checkpoints if item.type.casefold() == "hosted"]
        hosted_checkpoint_ids = {item.hosted_review_id for item in hosted_checkpoints}
        if any(item.comment_id is None or item.hosted_review_id is None for item in hosted_checkpoints):
            raise ControllerError("a Hosted checkpoint has incomplete immutable identity")
        checkpoints_by_response: dict[int, list[evidence.Checkpoint]] = {}
        for item in hosted_checkpoints:
            if item.hosted_review_id is not None:
                checkpoints_by_response.setdefault(item.hosted_review_id, []).append(item)
        checkpoint_by_response = {
            response_id: matching[0] for response_id, matching in checkpoints_by_response.items() if len(matching) == 1
        }

        records_by_trigger: dict[int, tuple[dict[str, Any], hosted.TriggerState]] = {}
        active_reservations: list[str] = []
        ambiguous_responses: list[str] = []
        for path in self._complete_trigger_paths(self.repo, pr):
            try:
                record = hosted.load_trigger_record(path, self.repo, pr)
                state = hosted.trigger_state(self.repo, pr, payload, record, path)
            except (OSError, ValueError, KeyError, TypeError, json.JSONDecodeError) as error:
                raise ControllerError("an available Hosted trigger record is unreadable or ambiguous") from error
            trigger_id = state.trigger_comment_id
            if not isinstance(trigger_id, int) or trigger_id <= 0:
                raise ControllerError("an available Hosted trigger record has no exact trigger identity")
            if trigger_id in records_by_trigger:
                ambiguous_responses.append("duplicate trigger identity")
                continue
            records_by_trigger[trigger_id] = (record, state)
            if state.state in {"active", "awaiting_response", "ambiguous", "unattributed", "timed_out"}:
                active_reservations.append(state.state)
            elif state.state == "rate_limited":
                reset = hosted.parse_timestamp(state.cooldown_until)
                if reset is None or reset > datetime.now(timezone.utc):
                    active_reservations.append("rate_limited")
            elif state.state not in {"completed", "noop", "failed", "retired"}:
                ambiguous_responses.append("unrecognized trigger state")
            if state.state not in {"retired"} and state.attributed is not True:
                ambiguous_responses.append("unattributed trigger response")

        commands = self._full_review_commands(comments)
        responses = self._bot_response_ids(comments, reviews)
        response_ids = [identity for identity, _ in responses]
        if len(set(response_ids)) != len(response_ids):
            ambiguous_responses.append("duplicate response identity")
        unmatched_responses: list[str] = []
        responses_by_trigger: dict[int, set[int]] = {}
        unrecorded_responses_by_trigger: dict[int, list[tuple[dict[str, Any], str, datetime]]] = {}
        response_by_id = {
            github.immutable_database_id(item): (item, timestamp_field)
            for item, timestamp_field in (
                *((comment, "createdAt") for comment in comments),
                *((review, "submittedAt") for review in reviews),
            )
            if github.immutable_database_id(item) is not None
        }
        for response_id, response_at in responses:
            previous = [item for item in commands if item[1] < response_at]
            if not previous:
                response_item = response_by_id[response_id][0]
                response_is_current = response_matches_expected_head(response_id, response_item)
                record_unmatched(
                    "response has no preceding full-review trigger",
                    historical=response_is_current is False,
                    ambiguous=response_is_current is not False,
                )
                continue
            latest_time = previous[-1][1]
            nearest = [item for item in previous if item[1] == latest_time]
            if len(nearest) != 1:
                ambiguous_responses.append("response has a timestamp-ambiguous trigger window")
                continue
            trigger_id = nearest[0][0]
            matched = records_by_trigger.get(trigger_id)
            if matched is None:
                response_item, timestamp_field = response_by_id[response_id]
                unrecorded_responses_by_trigger.setdefault(trigger_id, []).append(
                    (response_item, timestamp_field, response_at)
                )
                continue
            record, state = matched
            responses_by_trigger.setdefault(trigger_id, set()).add(response_id)
            if state.state == "completed":
                response_item = next(
                    (item for item in (*comments, *reviews) if github.immutable_database_id(item) == response_id),
                    None,
                )
                body = (response_item or {}).get("body")
                if isinstance(body, str) and hosted._substantive(body):
                    if response_item in reviews:
                        commit = (response_item.get("commit") or {}).get("oid")
                        if not isinstance(commit, str) or commit.casefold() != record["head_sha"].casefold():
                            ambiguous_responses.append("substantive review response is bound to another head")
                    elif not hosted._matches_head(body, record["head_sha"]):
                        ambiguous_responses.append("substantive comment response does not identify its captured head")

        for trigger_id, (record, state) in records_by_trigger.items():
            if state.state == "retired" and responses_by_trigger.get(trigger_id):
                ambiguous_responses.append("a retired Hosted trigger has a later public response")
            if state.response_id is not None and state.response_id not in responses_by_trigger.get(trigger_id, set()):
                ambiguous_responses.append("trigger response is absent from complete GitHub history")
            if state.state == "completed" and state.response_id not in hosted_checkpoint_ids:
                observation = self._uncheckpointed_hosted_observation(pr, state)
                fingerprint = observation_fingerprint(observation)
                fingerprinted = fingerprint in expected_hosted_fingerprints
                if not fingerprinted:
                    is_current_head = record["head_sha"].casefold() == expected_head.casefold()
                    record_unmatched(
                        "completed Hosted response has no checkpoint or prior audit",
                        historical=not is_current_head,
                        ambiguous=is_current_head,
                    )

        for trigger_id, _ in commands:
            if trigger_id in records_by_trigger:
                continue
            public_responses = unrecorded_responses_by_trigger.get(trigger_id, [])
            events = [
                (
                    response_at,
                    github.immutable_database_id(item) or 0,
                    self._public_response_state(item, timestamp_field, checkpoint_by_response),
                    item,
                )
                for item, timestamp_field, response_at in public_responses
            ]
            events = [event for event in events if event[2] is not None]
            if not events:
                active_reservations.append("a public full-review trigger has no attributable terminal response")
                continue
            _, _, response_state, response_item = max(events, key=lambda event: (event[0], event[1]))
            if response_state == "active":
                active_reservations.append("a public full-review trigger still has an active response")
            elif response_state == "ambiguous":
                ambiguous_responses.append("an unrecorded public response does not identify its reviewed head")
            elif response_state == "rate_limited":
                response_at = hosted.parse_timestamp(response_item.get("createdAt"))
                cooldown = hosted._rate_limit(response_item.get("body", ""), response_at) if response_at else None
                if cooldown is None or cooldown > datetime.now(timezone.utc):
                    active_reservations.append("an unrecorded public response has an unresolved rate limit")
            elif response_state == "completed":
                response_id = github.immutable_database_id(response_item)
                matching_checkpoints = checkpoints_by_response.get(response_id, []) if response_id is not None else []
                if not matching_checkpoints:
                    response_is_current = (
                        response_matches_expected_head(response_id, response_item) if response_id is not None else None
                    )
                    record_unmatched(
                        "an unrecorded completed Hosted response has no matching public checkpoint",
                        historical=response_is_current is False,
                        ambiguous=response_is_current is not False,
                    )
                elif len(matching_checkpoints) != 1:
                    ambiguous_responses.append(
                        "an unrecorded completed Hosted response has ambiguous public checkpoints"
                    )

        hosted_history = self.history(pr, "hosted")
        represented_checkpoints = {str(item.get("checkpoint")) for item in hosted_history if isinstance(item, dict)}
        legacy_represented_checkpoint_ids: set[str] = set()
        states_by_response: dict[int, list[tuple[dict[str, Any], hosted.TriggerState]]] = {}
        for record, state in records_by_trigger.values():
            if state.response_id is not None:
                states_by_response.setdefault(state.response_id, []).append((record, state))
        for response_id, candidates in checkpoints_by_response.items():
            if len(candidates) != 1:
                continue
            checkpoint = candidates[0]
            matching = states_by_response.get(response_id, [])
            if len(matching) != 1:
                continue
            record, state = matching[0]
            captured_head = record.get("head_sha")
            review_matches = [item for item in reviews if github.immutable_database_id(item) == response_id]
            trigger = next(
                (item for item in comments if github.immutable_database_id(item) == state.trigger_comment_id),
                None,
            )
            trigger_author = ((trigger or {}).get("author") or {}).get("login")
            checkpoint_author = checkpoint.author_login
            review_commit = (
                (review_matches[0].get("commit") or {}).get("oid")
                if len(review_matches) == 1 and isinstance(review_matches[0].get("commit"), dict)
                else None
            )
            if (
                state.state == "completed"
                and state.terminal is True
                and state.attributed is True
                and state.response_id == response_id
                and isinstance(captured_head, str)
                and hosted.EXACT_SHA.fullmatch(captured_head)
                and state.head_sha.casefold() == captured_head.casefold()
                and isinstance(checkpoint.reviewed_sha, str)
                and captured_head.casefold().startswith(checkpoint.reviewed_sha.casefold())
                and len(review_matches) == 1
                and isinstance(review_commit, str)
                and review_commit.casefold() == captured_head.casefold()
                and isinstance(trigger_author, str)
                and isinstance(checkpoint_author, str)
                and trigger_author.casefold() == checkpoint_author.casefold()
                and not github.is_coderabbit_login(trigger_author)
                and checkpoint.comment_id is not None
            ):
                legacy_represented_checkpoint_ids.add(str(checkpoint.comment_id))
        represented_checkpoints.update(legacy_represented_checkpoint_ids)
        for checkpoint in hosted_checkpoints:
            if str(checkpoint.comment_id) not in represented_checkpoints:
                reviewed_sha = checkpoint.reviewed_sha
                names_another_head = (
                    not expected_head.casefold().startswith(reviewed_sha.casefold())
                    if isinstance(reviewed_sha, str) and re.fullmatch(r"[0-9a-fA-F]{7,40}", reviewed_sha) is not None
                    else None
                )
                record_unmatched(
                    "a public Hosted checkpoint has no unique attributable trigger",
                    historical=names_another_head is True,
                    ambiguous=names_another_head is not True,
                )
        unresolved_findings = [
            str(item.get("checkpoint", "Hosted finding"))
            for item in hosted_history
            if str(item.get("checkpoint", "")).startswith(("review-threads:", "summary-actions:", "over-ceiling:"))
            and (item.get("held") is True or item.get("unstable") is True or item.get("over_ceiling") is True)
        ]
        return {
            "complete": True,
            "active_reservations": active_reservations,
            "unmatched_responses": unmatched_responses,
            "historical_unmatched_responses": historical_unmatched_responses,
            "ambiguous_responses": ambiguous_responses,
            "unresolved_findings": unresolved_findings,
        }

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

    @staticmethod
    def _hosted_zero_reply_proof(
        checkpoint: evidence.Checkpoint,
        response_id: int | None,
        response_duration_seconds: int | None,
        captured_head: str,
        record: dict[str, Any],
        payload: dict[str, Any],
    ) -> dict[str, Any] | None:
        """Validate a finished-reply checkpoint when no PR review object exists.

        ``hosted.trigger_state`` supplies the terminal trigger attribution. This
        additional check binds the checkpoint to that exact finished reply and
        to a zero-finding summary inside the same trigger window.
        """

        if (
            checkpoint.duration_invalid
            or checkpoint.type.casefold() != "hosted"
            or checkpoint.raw_found != 0
            or checkpoint.accepted != 0
            or checkpoint.hosted_review_id is None
            or response_id != checkpoint.hosted_review_id
            or (checkpoint.duration_seconds is not None and checkpoint.duration_seconds != response_duration_seconds)
        ):
            return None

        pr = payload["data"]["repository"]["pullRequest"]
        comments = pr["comments"]["nodes"]
        response = next(
            (item for item in comments if github.immutable_database_id(item) == response_id),
            None,
        )
        response_author = ((response or {}).get("author") or {}).get("login")
        response_body = (response or {}).get("body") or ""
        response_at = hosted.parse_timestamp((response or {}).get("createdAt"))
        trigger = record.get("trigger") or {}
        trigger_id = trigger.get("id")
        trigger_at = hosted.parse_timestamp(trigger.get("created_at"))
        if (
            response is None
            or not hosted.is_coderabbit_login(response_author)
            or not hosted.FINISHED_REVIEW_PATTERN.search(hosted._unquoted(response_body))
            or response_at is None
            or trigger_at is None
            or response_at <= trigger_at
        ):
            return None

        later_triggers: list[datetime] = []
        for item in comments:
            author = (item.get("author") or {}).get("login")
            if (
                github.immutable_database_id(item) == trigger_id
                or hosted.is_coderabbit_login(author)
                or hosted.normalize_command(item.get("body") or "") != hosted.FULL_COMMAND
            ):
                continue
            created = hosted.parse_timestamp(item.get("createdAt"))
            if created is None:
                return None
            if created >= trigger_at:
                later_triggers.append(created)
        next_trigger = min(later_triggers, default=None)
        if next_trigger is not None and response_at >= next_trigger:
            return None

        legacy_summary = hosted._zero_finding_summary(payload, captured_head, trigger_at, response_id, next_trigger)
        provider_summary = hosted.provider_format_zero_finding_summary(
            payload,
            captured_head,
            trigger_at,
            response_id,
            next_trigger,
        )
        finished_only = hosted.finished_reply_without_findings(
            payload, captured_head, trigger_at, response_id, next_trigger
        )
        if legacy_summary is None and provider_summary is None and not finished_only:
            return None

        # A reply-only checkpoint is intentionally limited to runs with no PR
        # review object in the captured trigger window. This avoids choosing a
        # reply over a conflicting or mismatched immutable review commit.
        for review in pr["reviews"]["nodes"]:
            author = (review.get("author") or {}).get("login")
            if not hosted.is_coderabbit_login(author) or review.get("state") == "DISMISSED":
                continue
            submitted = hosted.parse_timestamp(review.get("submittedAt"))
            if submitted is None:
                return None
            if submitted > trigger_at and (next_trigger is None or submitted < next_trigger):
                return None

        return {
            "status": "completed",
            "review_id": response_id,
            "commit_id": captured_head,
            "submitted_at": response.get("createdAt"),
        }

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
                proof = (
                    self._hosted_zero_reply_proof(
                        checkpoint,
                        state.response_id,
                        state.duration_seconds,
                        captured_head,
                        record,
                        payload,
                    )
                    or proof
                )
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
        include_hosted_findings: bool = True,
        changed_file_count: int | None = None,
    ) -> list[dict[str, Any]]:
        """Return blockers shared by review channels and Hosted-only obligations.

        Review threads and CodeRabbit summary actions are Hosted findings: they
        remain merge-readiness obligations, but must not suppress independent CLI
        discovery. A current provider skip is a Hosted limitation and does not
        suppress the independent CLI path.
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
        bound_failed_response_ids: set[int] = set()
        bound_legacy_skip_response_ids: set[int] = set()
        if (
            include_hosted_findings
            and isinstance(changed_file_count, int)
            and not isinstance(changed_file_count, bool)
            and changed_file_count > _HOSTED_CODERABBIT_FILE_CEILING
        ):
            for path in hosted.trigger_record_paths(self.repo, pr):
                try:
                    record = hosted.load_trigger_record(path, self.repo, pr)
                    state = hosted.trigger_state(self.repo, pr, payload, record, path)
                except (OSError, ValueError, KeyError, TypeError, json.JSONDecodeError):
                    continue
                if (
                    record["head_sha"].casefold() == head.casefold()
                    and state.current_head_sha.casefold() == head.casefold()
                ):
                    if (
                        state.state == "failed"
                        and isinstance(state.response_id, int)
                        and not isinstance(state.response_id, bool)
                    ):
                        bound_failed_response_ids.add(state.response_id)
                    elif state.state == "awaiting_response":
                        trigger_at = hosted.parse_timestamp(record["trigger"].get("created_at"))
                        if trigger_at is None:
                            continue
                        for response in (pull.get("comments") or {}).get("nodes", []):
                            response_body = response.get("body") or ""
                            response_time = hosted.parse_timestamp(response.get("createdAt"))
                            if (
                                github.is_coderabbit_login((response.get("author") or {}).get("login", ""))
                                and "<!-- This is an auto-generated comment: skip review by coderabbit.ai -->"
                                in response_body
                                and _PLAN_CEILING_PATTERN.search(response_body)
                                and response_time is not None
                                and response_time > trigger_at
                                and (response_id := github.immutable_database_id(response)) is not None
                            ):
                                bound_legacy_skip_response_ids.add(response_id)
        # Hosted and CLI use different provider paths. The Hosted endpoint has
        # a verified hard cap; the CLI has completed successfully above 100
        # files, so a Hosted skip must not become a global CLI blocker.
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
            provider_skip = hosted.provider_file_ceiling_skip(body)
            legacy_skip = (
                "<!-- This is an auto-generated comment: skip review by coderabbit.ai -->" in body
                and _PLAN_CEILING_PATTERN.search(body)
            )
            response_id = github.immutable_database_id(item)
            if (
                (
                    provider_skip
                    and response_id in bound_failed_response_ids
                    or legacy_skip
                    and response_id in bound_legacy_skip_response_ids
                )
                and timestamp is not None
                and timestamp >= latest_exact_completion
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

    def _active_cli_history(self, pr: int, cli_common: Path, *, operational_only: bool = False) -> list[dict[str, Any]]:
        values: list[dict[str, Any]] = []
        cli_root = cli_common / "firemud" / "pr-review"
        if self._request_lock_is_held(cli_root / "cli.lock"):
            for metadata_path in (cli_root / "runs").glob("*/metadata.json"):
                if any((metadata_path.parent / name).exists() for name in ("capture-complete", "error", "exit-status")):
                    continue
                try:
                    active_metadata = json.loads(metadata_path.read_text(encoding="utf-8"))
                except (OSError, ValueError):
                    continue
                if not isinstance(active_metadata, Mapping) or active_metadata.get("pull_request") != pr:
                    continue
                anchor_metadata = {
                    key: value
                    for key, value in active_metadata.items()
                    if isinstance(key, str) and isinstance(value, str)
                }
                run_id = active_metadata.get("run_id")
                if not isinstance(run_id, str) or not run_id:
                    run_id = metadata_path.parent.name
                candidate_sha = active_metadata.get("candidate_sha")
                values.append(
                    {
                        "pr": pr,
                        "head": candidate_sha if isinstance(candidate_sha, str) else "",
                        "checkpoint": f"active-cli:{run_id}",
                        "active_review": True,
                        "held": True,
                        "reason": "CLI review is running; its eventual findings still require adjudication",
                        **self._anchor(anchor_metadata),
                    }
                )
            if operational_only and not values:
                values.append(
                    {
                        "pr": pr,
                        "head": "",
                        "checkpoint": "active-cli:unidentified",
                        "active_review": True,
                        "held": True,
                        "reason": "CLI process lock is held; its current reservation cannot be identified",
                    }
                )
        return values

    def _current_hosted_history(
        self,
        pr: int,
        head: str,
        payload: dict[str, Any],
        emitted_hosted_response_ids: set[int],
        *,
        operational_only: bool = False,
    ) -> list[dict[str, Any]]:
        values: list[dict[str, Any]] = []
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
                    **({"active_reservation": True} if operational_only else {}),
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
                        **({"active_reservation": True} if operational_only else {}),
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
                        "trigger_id": state.trigger_comment_id,
                        "response_id": state.response_id,
                        "terminal": state.terminal,
                        "attributable": state.attributed,
                        "held": reset is None,
                        "unstable": reset is None,
                        "reason": "Hosted cooldown remains active"
                        if reset
                        else "Hosted cooldown has no attributable reset time",
                        "cooldown_until": state.cooldown_until,
                    }
                )
            elif (
                not operational_only
                and state.state == "completed"
                and state.response_id not in emitted_hosted_response_ids
            ):
                values.append(self._uncheckpointed_hosted_observation(pr, state))
            elif state.state in {"active", "awaiting_response", "ambiguous", "unattributed", "timed_out"}:
                if (
                    operational_only
                    and state.state == "ambiguous"
                    and state.terminal is True
                    and type(state.response_id) is int
                    and state.response_id > 0
                ):
                    # The existing admission guard releases this provider slot.
                    # Missing counted findings remain visible in full history.
                    continue
                observation = {
                    "pr": pr,
                    "head": state.head_sha,
                    "checkpoint": f"trigger:{state.trigger_comment_id or 'pending'}",
                    "held": state.state in {"active", "awaiting_response", "ambiguous", "unattributed"},
                    "unstable": state.state in {"ambiguous", "unattributed", "timed_out"},
                    "active_reservation": state.state in {"active", "awaiting_response"} or operational_only,
                    "reason": state.reason,
                }
                if state.state == "active":
                    anchor = record.get("anchor")
                    observation["anchor"] = dict(anchor) if isinstance(anchor, Mapping) else None
                    observation["trigger_id"] = state.trigger_comment_id
                    observation["response_id"] = state.response_id
                if state.state == "ambiguous" and not operational_only:
                    terminal_observation = self._terminal_ambiguous_hosted_observation(pr, record, state, payload)
                    if terminal_observation is not None:
                        observation.update(
                            {
                                "terminal_ambiguous": True,
                                "terminal": state.terminal,
                                "attributable": state.attributed,
                                **terminal_observation,
                            }
                        )
                values.append(observation)
        return values

    def request_history(self, pr: int, channel: str) -> Sequence[dict[str, Any]]:
        """Read only current provider safety after a human has stopped discovery.

        Completed checkpoints, capture archives, findings and scope timelines are
        still read by history/status. They do not control the next PR's request.
        A current reservation still needs public attribution before it is idle.
        """

        if channel == "cli":
            try:
                common, _ = evidence.resolve_cli_capture_context()
            except evidence.EvidenceError as exc:
                raise ControllerError("current CLI admission state cannot be verified") from exc
            return self._active_cli_history(pr, common, operational_only=True)
        paths = hosted.current_trigger_record_paths(self.repo, pr)
        lock = hosted.default_trigger_record_path(self.repo, pr).parent / "request.lock"
        if self._request_lock_is_held(lock):
            return [
                {
                    "pr": pr,
                    "head": "",
                    "checkpoint": "trigger:request-lock",
                    "active_reservation": True,
                    "held": True,
                    "reason": "Hosted reservation is being updated",
                }
            ]
        if not paths:
            return []
        # The bounded identity/activity view can prove a current trigger finished
        # without touching old checkpoints or archived evidence. If it cannot,
        # complete current-trigger attribution remains an operational dependency.
        identity = self.live.batch_pull_requests([pr]).get(pr)
        if not isinstance(identity, Mapping):
            raise ControllerError("current Hosted admission state cannot be verified")
        payload = {"data": {"repository": {"pullRequest": dict(identity)}}}
        head = identity["headRefOid"]
        values = self._current_hosted_history(pr, head, payload, set(), operational_only=True)
        if any(value.get("active_reservation") is True for value in values):
            payload = self._payload(pr)
            values = self._current_hosted_history(pr, head, payload, set(), operational_only=True)
        return values

    def history(self, pr: int, channel: str) -> Sequence[dict[str, Any]]:
        key = (pr, channel)
        if key in self._histories:
            return self._histories[key]
        payload = self._payload(pr)
        pull = payload.get("data", {}).get("repository", {}).get("pullRequest")
        payload_head = pull.get("headRefOid") if isinstance(pull, Mapping) else None
        payload_files = pull.get("changedFiles") if isinstance(pull, Mapping) else None
        # The complete paginated review snapshot already carries its head. Use
        # that same snapshot for historical attribution instead of fetching a
        # second PR/CI metadata response for every history read. Request-time
        # selection still obtains its own fresh identity separately.
        if (
            isinstance(payload_head, str)
            and evidence.EXACT_SHA.fullmatch(payload_head)
            and type(payload_files) is int
            and payload_files >= 0
        ):
            head = payload_head.lower()
            changed_files = payload_files
        else:
            live = self.live.pull_request(pr)
            head = live.head_sha.lower()
            changed_files = live.changed_files
        comments = self._comments(payload)
        checkpoints, _ = evidence.parse_checkpoint_comments(comments)
        scope_changes = evidence.parse_scope_changes(comments)
        checkpoints.sort(key=lambda item: (item.created_at, item.comment_id or 0))
        reviews = self._reviews(payload)
        values: list[dict[str, Any]] = []
        public_cli_run_ids: set[str] = set()
        emitted_cli_sources: set[tuple[str, str]] = set()
        emitted_hosted_review_ids: set[int] = set()
        emitted_hosted_response_ids: set[int] = set()
        cli_common: Path | None = None
        cli_records: Any = None
        cli_context_error: evidence.EvidenceError | None = None
        if channel == "cli":
            try:
                cli_common, cli_records = evidence.resolve_cli_capture_context()
            except evidence.EvidenceError as error:
                cli_context_error = error
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
                if cli_context_error is None:
                    try:
                        capture = evidence.load_cli_capture(checkpoint, self.repo, pr, cli_common, records=cli_records)
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
                if state_response_id := proof.get("review_id"):
                    emitted_hosted_response_ids.add(state_response_id)
                completed = attributable = True
                exact_head = str(proof["commit_id"])
                anchor = dict(record["anchor"])
            anchor_complete = self._anchor_complete(anchor)
            values.append(
                {
                    "pr": pr,
                    "head": exact_head,
                    "reviewed_head": exact_head,
                    "comment_id": checkpoint.comment_id,
                    "observed_at": checkpoint.created_at,
                    "checkpoint": str(checkpoint.comment_id or checkpoint.created_at),
                    "completed": completed,
                    "attributable": attributable,
                    "anchored": anchor_complete if completed else None,
                    "accepted": checkpoint.accepted,
                    "raw": checkpoint.raw_found,
                    "routed": checkpoint.routed,
                    "correction": checkpoint.correction,
                    "corrected_state": completed and exact_head == head,
                    "provisional": provisional,
                    "reason": (
                        "CLI capture context is unavailable; checkpoint attribution cannot be verified"
                        if cli_context_error is not None
                        else capture.metadata.get("reason", "")
                        if channel == "cli" and capture is not None
                        else ""
                    ),
                    **({"held": True, "capture_context_available": False} if cli_context_error is not None else {}),
                    **anchor,
                }
            )
        if channel == "cli" and cli_context_error is None:
            # CLI already holds its process lock and pins canonical metadata
            # before execution. Read that reservation; no second activity store
            # or fabricated completed checkpoint is needed.
            values.extend(self._active_cli_history(pr, cli_common))
            for capture in evidence.discover_cli_captures(self.repo, pr, cli_common, records=cli_records):
                run_id = capture.metadata.get("run_id", "")
                if run_id in public_cli_run_ids:
                    continue
                identity = (run_id, capture.source_identity or run_id)
                if identity in emitted_cli_sources:
                    continue
                emitted_cli_sources.add(identity)
                candidate_sha = capture.metadata.get("candidate_sha", "")
                published_head_sha = capture.metadata.get("published_head_sha", candidate_sha)
                current_head = any(
                    value.casefold() == head.casefold() for value in (candidate_sha, published_head_sha) if value
                )
                values.append(
                    {
                        "pr": pr,
                        "head": candidate_sha,
                        "reviewed_head": candidate_sha,
                        "observed_at": capture.metadata.get("completed_at", ""),
                        "checkpoint": f"pending-capture:{run_id}",
                        "completed": False,
                        "attributable": False,
                        "anchored": False,
                        "accepted": 0,
                        "raw": len(capture.findings),
                        "corrected_state": False,
                        "provisional": capture.metadata.get("provisional", "false").casefold() == "true",
                        "held": current_head,
                        "reason": (
                            "a successful private CLI capture has no public checkpoint and requires adjudication"
                            if current_head
                            else "a successful private CLI capture belongs to an older head"
                        ),
                        **self._anchor(capture.metadata),
                    }
                )
        if channel == "hosted":
            values.extend(self._current_hosted_history(pr, head, payload, emitted_hosted_response_ids))
        parsed_scope_changes = {
            (item.comment_id, item.created_at, item.description, item.updated_at) for item in scope_changes
        }
        for comment in comments:
            author_login = comment.get("author_login")
            if github.is_coderabbit_login(author_login):
                continue
            body = comment["body"]
            if not isinstance(body, str):
                continue
            lines = body.splitlines()
            first = next((line for line in lines if line.strip() and not line[0].isspace()), None)
            scope_heading = evidence.SCOPE_CHANGE.fullmatch(first or "")
            exact_marker_lines = [line for line in lines[1:] if line == evidence.SCOPE_MARKER]
            looks_like_scope_change = bool(
                re.match(r"^\*{0,2}review\s+scope\s+changed\b", (first or "").strip(), re.IGNORECASE)
                or any(line == evidence.SCOPE_MARKER for line in lines)
            )
            if not looks_like_scope_change:
                continue
            comment_id = comment["id"]
            created_at = comment["created_at"]
            updated_at = comment["updated_at"]
            description = scope_heading.group("description") if scope_heading is not None else None
            valid = (
                len(exact_marker_lines) == 1
                and (comment_id, created_at, description, updated_at if updated_at != created_at else None)
                in parsed_scope_changes
            )
            values.append(
                {
                    "pr": pr,
                    "head": head,
                    "channel": channel,
                    "kind": "scope_change" if valid else "scope_change_malformed",
                    "scope_changed": True,
                    "scope_change_malformed": not valid,
                    "checkpoint": f"scope-change:{comment_id or created_at}",
                    "comment_id": comment_id,
                    "created_at": created_at,
                    "updated_at": updated_at,
                    "observed_at": updated_at or created_at,
                    "description": description,
                    "completed": False,
                    "attributable": False,
                    "anchored": False,
                    "accepted": 0,
                    "raw": 0,
                    "non_counting": True,
                }
            )
        global_blockers = self._global_blockers(
            pr,
            head,
            payload,
            include_hosted_findings=channel == "hosted",
            changed_file_count=changed_files,
        )
        values.append(
            {
                "pr": pr,
                "head": head,
                "channel": channel,
                "kind": "scope_timeline",
                "scope_timeline": True,
                "scope_timeline_complete": True,
                "checkpoint": "scope-timeline:complete",
                "completed": False,
                "attributable": False,
                "anchored": False,
                "accepted": 0,
                "raw": 0,
                "non_counting": True,
            }
        )
        values.extend(global_blockers)
        self._histories[key] = values
        return values


class HostedRunner:
    """Post one full Hosted request with a durable pre/post identity boundary."""

    def __init__(
        self,
        repo: str,
        live: LiveGitHub,
        state_store: StateStore | ControllerStateStore | None = None,
        records: SqliteReviewRecords | None = None,
    ) -> None:
        self.repo = repo
        self.live = live
        self.state_store = state_store
        self.records = records

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

    def _capture_terminal_attempt(
        self,
        pr: int,
        record: Mapping[str, Any],
        payload: dict[str, Any],
        record_path: Path,
    ) -> str | None:
        """Best-effort archive of a terminal result already read for admission."""

        attempt_id = record.get("sqlite_attempt_id")
        if self.records is None or not isinstance(attempt_id, str) or not attempt_id:
            return None
        try:
            sqlite_hosted_capture.record_hosted_terminal_result(
                self.records,
                attempt_id=attempt_id,
                repo=self.repo,
                source_pr=pr,
                trigger_record=record,
                payload=payload,
                current_record_path=record_path,
            )
        except Exception as exc:  # noqa: BLE001 - archive failures cannot block review admission
            # This history write is secondary to the terminal observation
            # already used for admission and must not block the next request.
            return f"SQLite Hosted terminal capture failed ({type(exc).__name__})."
        return None

    def _finish_unposted_attempt(self, attempt_id: str) -> None:
        """Best-effort close a registered attempt if its reservation was not written."""

        if self.records is None:
            return
        try:
            attempt = self.records.attempt(attempt_id)
            if attempt["state"] != "started":
                return
            self.records.finish_attempt(
                attempt_id,
                state="failed",
                finished_at=hosted.utc_now(),
                diagnostic="Hosted POST was not issued because its durable reservation could not be written",
            )
        except Exception:  # noqa: BLE001 - preserve the primary reservation failure
            # The reservation write failure remains primary. A later explicit
            # records sync can inspect this attempt and its durable trigger.
            return

    @staticmethod
    def _base_advanced(target: ReviewTarget, current: PullRequestSnapshot) -> bool:
        return (
            target.default_base_front
            and current.number == target.snapshot.number
            and current.head_sha.casefold() == target.snapshot.head_sha.casefold()
            and current.base_ref_name == target.parent.ref_name
            and current.base_sha.casefold() != target.snapshot.base_sha.casefold()
            and current.state.upper() == "OPEN"
            and current.base_exists
            and target.has_current_default_test_merge_proof()
        )

    def _latest_untracked_manual_trigger(self, pr: int, payload: Mapping[str, Any]) -> Mapping[str, Any] | None:
        """Find the latest public full-review command if it has no private record."""

        try:
            comments = payload["data"]["repository"]["pullRequest"]["comments"]["nodes"]
        except (KeyError, TypeError) as exc:
            raise ControllerError("complete paginated Hosted comment history is unavailable before posting") from exc
        if not isinstance(comments, list):
            raise ControllerError("complete paginated Hosted comment history is malformed before posting")

        commands: list[tuple[datetime, int, Mapping[str, Any]]] = []
        for item in comments:
            if not isinstance(item, Mapping):
                raise ControllerError("complete paginated Hosted comment history contains a malformed comment")
            author = item.get("author")
            login = author.get("login") if isinstance(author, Mapping) else None
            body = item.get("body")
            if github.is_coderabbit_login(login) or not isinstance(body, str):
                continue
            if hosted.normalize_command(body) != hosted.FULL_COMMAND:
                continue
            comment_id = github.immutable_database_id(dict(item))
            created = hosted.parse_timestamp(item.get("createdAt"))
            if comment_id is None or created is None:
                raise ControllerError("a public full-review trigger has incomplete immutable identity")
            commands.append((created, comment_id, item))
        if not commands:
            return None

        latest_time = max(created for created, _, _ in commands)
        latest = [(comment_id, item) for created, comment_id, item in commands if created == latest_time]
        if len(latest) != 1:
            raise ControllerError("the latest public full-review trigger identity is ambiguous")
        tracked_ids: set[int] = set()
        try:
            for record_path in hosted.trigger_record_paths(self.repo, pr):
                record = hosted.load_trigger_reservation(record_path, self.repo, pr)
                trigger = record.get("trigger")
                if not isinstance(trigger, Mapping):
                    continue
                trigger_id = trigger.get("id")
                if (
                    isinstance(trigger_id, int)
                    and not isinstance(trigger_id, bool)
                    and trigger_id > 0
                    and trigger.get("type") == "full"
                    and hosted.normalize_command(str(trigger.get("command") or "")) == hosted.FULL_COMMAND
                ):
                    tracked_ids.add(trigger_id)
        except (OSError, ValueError, KeyError, TypeError, json.JSONDecodeError) as exc:
            raise ControllerError("private Hosted trigger records cannot be verified before posting") from exc

        comment_id, comment = latest[0]
        return None if comment_id in tracked_ids else comment

    @staticmethod
    def _manual_trigger_reviewed_head(payload: Mapping[str, Any], command: Mapping[str, Any]) -> str | None:
        """Return the unique immutable review head attributable to a manual command."""

        try:
            pull = payload["data"]["repository"]["pullRequest"]
            comments = pull["comments"]["nodes"]
            reviews = pull["reviews"]["nodes"]
        except (KeyError, TypeError):
            return None
        if not isinstance(comments, list) or not isinstance(reviews, list):
            return None

        command_at = hosted.parse_timestamp(command.get("createdAt"))
        if command_at is None:
            return None
        later_commands: list[datetime] = []
        for item in comments:
            if not isinstance(item, Mapping):
                return None
            author = item.get("author")
            login = author.get("login") if isinstance(author, Mapping) else None
            body = item.get("body")
            created = hosted.parse_timestamp(item.get("createdAt"))
            if (
                not github.is_coderabbit_login(login)
                and isinstance(body, str)
                and hosted.normalize_command(body) == hosted.FULL_COMMAND
                and created is not None
                and created > command_at
            ):
                later_commands.append(created)
        next_command_at = min(later_commands, default=None)

        candidate_heads: list[str | None] = []
        for review in reviews:
            if not isinstance(review, Mapping):
                return None
            author = review.get("author")
            login = author.get("login") if isinstance(author, Mapping) else None
            if not github.is_coderabbit_login(login) or review.get("state") == "DISMISSED":
                continue
            submitted = hosted.parse_timestamp(review.get("submittedAt"))
            if (
                submitted is None
                or submitted <= command_at
                or (next_command_at is not None and submitted >= next_command_at)
            ):
                continue
            body = review.get("body")
            if not isinstance(body, str) or not hosted._substantive(body):
                continue
            commit = review.get("commit")
            oid = commit.get("oid") if isinstance(commit, Mapping) else None
            candidate_heads.append(oid if isinstance(oid, str) and hosted.EXACT_SHA.fullmatch(oid) else None)

        if len(candidate_heads) != 1 or candidate_heads[0] is None:
            return None
        return candidate_heads[0]

    @staticmethod
    def _manual_terminal_without_head(
        repo: str,
        pr: int,
        payload: Mapping[str, Any],
        command: Mapping[str, Any],
        common: Path,
    ) -> bool:
        """Release admission on one immutable terminal result without assigning a review head."""
        author = command.get("author")
        trigger_id = github.immutable_database_id(dict(command))
        trigger_url = command.get("url")
        command_at = hosted.parse_timestamp(command.get("createdAt"))
        if trigger_id is None or command_at is None or not isinstance(trigger_url, str) or not trigger_url:
            return False
        try:
            pull = payload["data"]["repository"]["pullRequest"]
            connections = [pull[name]["nodes"] for name in ("comments", "reviews", "reviewThreads")]
        except (KeyError, TypeError):
            return False
        if (
            not isinstance(pull, Mapping)
            or any(not isinstance(nodes, list) for nodes in connections)
            or any(not isinstance(item, Mapping) for nodes in connections for item in nodes)
        ):
            return False
        state = hosted.trigger_state(
            repo,
            pr,
            dict(payload),
            {
                "status": "posted",
                # Empty is an explicit no-head sentinel; this ephemeral record is never persisted.
                "head_sha": "",
                "trigger": {
                    "id": trigger_id,
                    "created_at": command.get("createdAt"),
                    "url": trigger_url,
                    "author_login": author.get("login") if isinstance(author, Mapping) else None,
                    "type": "full",
                    "command": hosted.FULL_COMMAND,
                },
            },
            hosted.default_trigger_record_path(repo, pr, common),
        )
        if state.terminal is not True or state.attributed is not True or state.response_id is None:
            return False

        try:
            pull = payload["data"]["repository"]["pullRequest"]
            comments = pull["comments"]["nodes"]
        except (KeyError, TypeError):
            return False
        if not isinstance(comments, list) or any(not isinstance(item, Mapping) for item in comments):
            return False
        if state.state == "completed":
            # A reply-only zero result is operationally terminal but remains non-counting without a head/checkpoint.
            matches = [item for item in comments if github.immutable_database_id(dict(item)) == state.response_id]
            return (
                len(matches) == 1
                and isinstance(matches[0].get("body"), str)
                and hosted._is_finished_action_response(matches[0]["body"], allow_action_wrapper=True)
                and hosted.finished_reply_without_findings(dict(payload), "", command_at, state.response_id)
            )
        if (
            state.state not in {"rate_limited", "noop", "failed"}
            or state.reason == "CodeRabbit finished after explicitly reporting incomplete file coverage"
        ):
            return False

        terminal_ids: list[int] = []
        active: list[datetime] = []
        for item in comments:
            author = item.get("author")
            if not github.is_coderabbit_login(author.get("login") if isinstance(author, Mapping) else None):
                continue
            created = hosted.parse_timestamp(item.get("createdAt"))
            updated = hosted.parse_timestamp(item.get("updatedAt"))
            if created is None or updated is None or updated < created:
                return False
            if created <= command_at and updated <= command_at:
                continue
            if created <= command_at or created != updated:
                return False
            response_id = github.immutable_database_id(dict(item))
            response_state = LiveEvidence._public_response_state(dict(item), "createdAt", {})
            if response_id is None:
                return False
            if response_state == "active":
                active.append(created)
            elif response_state in {"rate_limited", "noop", "failed"}:
                body = item.get("body")
                if isinstance(body, str) and hosted._summary_has_explicit_incomplete_coverage(body):
                    return False
                terminal_ids.append(response_id)
            else:
                return False
        return len(terminal_ids) == 1 and terminal_ids[0] == state.response_id and not active

    @staticmethod
    def _normalize_rest_issue_comments(pr: int, comments: Sequence[Mapping[str, Any]]) -> dict[str, Any]:
        """Project a complete REST issue-comment history into the matcher shape."""

        nodes: list[dict[str, Any]] = []
        for item in comments:
            if not isinstance(item, Mapping):
                raise ControllerError(f"issue-comment history for PR #{pr} contains a malformed comment")
            comment_id = github.immutable_database_id(dict(item))
            body = item.get("body")
            created_at = item.get("created_at")
            if not isinstance(body, str):
                raise ControllerError(f"issue-comment history for PR #{pr} has an unreadable comment body")
            user = item.get("user")
            login = user.get("login") if isinstance(user, Mapping) else None
            if not isinstance(login, str):
                login = None
            nodes.append(
                {
                    "databaseId": comment_id,
                    "author": {"login": login},
                    "body": body,
                    "createdAt": created_at,
                    "updatedAt": item.get("updated_at"),
                    "url": item.get("html_url"),
                }
            )
        return {"data": {"repository": {"pullRequest": {"comments": {"nodes": nodes}}}}}

    def _assert_latest_manual_trigger_is_tracked(self, pr: int, payload: Mapping[str, Any]) -> None:
        """Refuse a target-PR request until its latest manual trigger is adopted."""

        if self._latest_untracked_manual_trigger(pr, payload) is not None:
            raise ControllerError(
                "the latest public full-review trigger is not tracked privately; resolve or adopt it before posting"
            )

    @staticmethod
    def _repository_current_trigger_paths(repo: str, common: Path) -> dict[int, list[Path]]:
        """Discover current reservations in both supported private record locations."""

        safe_repo = repo.replace("/", "_")
        current: dict[int, list[Path]] = {}
        for namespace in ("firemud", "coderabbit-review-logs"):
            repository_dir = common / namespace / "hosted" / safe_repo
            if repository_dir.is_symlink() or not repository_dir.is_dir():
                continue
            try:
                pr_dirs = list(repository_dir.iterdir())
            except OSError as exc:
                raise ControllerError("current Hosted reservations cannot be inspected repository-wide") from exc
            for pr_dir in pr_dirs:
                match = re.fullmatch(r"pr-([1-9][0-9]*)", pr_dir.name)
                if match is None:
                    continue
                if pr_dir.is_symlink() or not pr_dir.is_dir():
                    raise ControllerError("a current Hosted reservation directory is ambiguous")
                trigger_path = pr_dir / "trigger.json"
                if trigger_path.is_symlink():
                    raise ControllerError("a current Hosted reservation is a symbolic link")
                if trigger_path.is_file():
                    current.setdefault(int(match.group(1)), []).append(trigger_path)
        return current

    def _assert_no_other_active_reservations(
        self,
        pr: int,
        common: Path,
    ) -> None:
        terminal_states = {"completed", "failed", "failed_incomplete_coverage", "rate_limited", "noop", "retired"}
        current = self._repository_current_trigger_paths(self.repo, common)
        try:
            open_pull_requests = github.fetch_api_endpoint(f"repos/{self.repo}/pulls?state=open&per_page=100")
        except (OSError, RuntimeError, ValueError, TypeError) as exc:
            raise ControllerError("open repository pull requests cannot be checked before posting") from exc
        open_prs: set[int] = set()
        for pull_request in open_pull_requests:
            number = pull_request.get("number")
            state = pull_request.get("state")
            if (
                isinstance(number, bool)
                or not isinstance(number, int)
                or number <= 0
                or not isinstance(state, str)
                or state.casefold() != "open"
                or number in open_prs
            ):
                raise ControllerError("open repository pull-request listing is malformed or ambiguous")
            open_prs.add(number)
        open_prs.discard(pr)

        # Closing a PR releases its execution slot, including unknown requests.
        # A positively attributed provider cooldown still applies repository-wide.
        for closed_pr, paths in current.items():
            if closed_pr == pr or closed_pr in open_prs:
                continue
            for path in paths:
                try:
                    record = hosted.load_trigger_reservation(path, self.repo, closed_pr)
                    payload = github.fetch_pull_request(self.repo, closed_pr)
                    state = hosted.trigger_state(self.repo, closed_pr, payload, record, path)
                    reset = hosted.parse_timestamp(state.cooldown_until) if state.state == "rate_limited" else None
                except (OSError, RuntimeError, ValueError, KeyError, TypeError, json.JSONDecodeError):
                    continue
                if (
                    state.state == "rate_limited"
                    and state.terminal is True
                    and state.attributed is True
                    and reset is not None
                    and reset > datetime.now(timezone.utc)
                ):
                    raise ControllerError(f"Hosted repository cooldown remains active on closed PR #{closed_pr}")

        comments_by_pr: dict[int, dict[str, Any]] = {}
        for other_pr in sorted(open_prs):
            try:
                comments = github.fetch_api_endpoint(f"repos/{self.repo}/issues/{other_pr}/comments?per_page=100")
                comments_by_pr[other_pr] = self._normalize_rest_issue_comments(other_pr, comments)
            except ControllerError:
                raise
            except (OSError, RuntimeError, ValueError, TypeError) as exc:
                raise ControllerError(f"issue-comment history for PR #{other_pr} cannot be verified") from exc

        payloads: dict[int, dict[str, Any]] = {}
        for other_pr, paths in current.items():
            if other_pr == pr or other_pr not in open_prs:
                continue
            if len(paths) != 1:
                raise ControllerError(f"multiple current Hosted reservations for PR #{other_pr} require resolution")
            path = paths[0]
            with ExitStack() as reservation_lock:
                try:
                    other_lock = reservation_lock.enter_context(
                        (path.parent / "request.lock").open("a+", encoding="utf-8")
                    )
                    fcntl.flock(other_lock.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
                except BlockingIOError as exc:
                    raise ControllerError(f"another Hosted reservation is being updated for PR #{other_pr}") from exc
                except OSError as exc:
                    raise ControllerError(f"could not lock the current Hosted reservation for PR #{other_pr}") from exc
                try:
                    record = hosted.load_trigger_reservation(path, self.repo, other_pr)
                    status = record.get("status")
                    if status == "retired":
                        continue
                    if status in {"posting", "posted_boundary_changed", "posted_boundary_unverified"}:
                        raise ControllerError(f"another Hosted request is unresolved for PR #{other_pr}: ambiguous")
                    payload = github.fetch_pull_request(self.repo, other_pr)
                    payloads[other_pr] = payload
                    state = hosted.trigger_state(self.repo, other_pr, payload, record, path)
                except ControllerError:
                    raise
                except (OSError, RuntimeError, ValueError, KeyError, TypeError, json.JSONDecodeError) as exc:
                    raise ControllerError(f"current Hosted reservation for PR #{other_pr} cannot be verified") from exc
                if state.state in {"active", "awaiting_response"}:
                    raise ControllerError(f"another Hosted request is unresolved for PR #{other_pr}: {state.state}")
                if state.state == "rate_limited":
                    reset = hosted.parse_timestamp(state.cooldown_until)
                    if reset is None or reset > datetime.now(timezone.utc):
                        raise ControllerError(f"Hosted repository cooldown remains unresolved on PR #{other_pr}")
                if state.state == "ambiguous":
                    response_id = state.response_id
                    if (
                        state.terminal is not True
                        or isinstance(response_id, bool)
                        or not isinstance(response_id, int)
                        or response_id <= 0
                    ):
                        raise ControllerError(f"another Hosted request is unresolved for PR #{other_pr}: {state.state}")
                    continue
                if state.state not in terminal_states:
                    raise ControllerError(f"another Hosted request is unresolved for PR #{other_pr}: {state.state}")

        for other_pr, comments_payload in comments_by_pr.items():
            try:
                command = self._latest_untracked_manual_trigger(other_pr, comments_payload)
                if command is None:
                    continue
                payload = payloads.get(other_pr)
                if payload is None:
                    payload = github.fetch_pull_request(self.repo, other_pr)
                command = self._latest_untracked_manual_trigger(other_pr, payload)
                if command is None:
                    raise ControllerError("manual command history changed during the pre-POST check")
                trigger_id = github.immutable_database_id(dict(command))
                if trigger_id is None:
                    raise ControllerError("manual full-review command has incomplete immutable identity")
                if hosted.unresolved_preceding_full_trigger(self.repo, other_pr, payload, trigger_id, common):
                    raise ControllerError(
                        f"another manual Hosted request is unresolved for PR #{other_pr}: "
                        "an earlier full-review command has no terminal response"
                    )
                reviewed_head = self._manual_trigger_reviewed_head(payload, command)
                if reviewed_head is None:
                    if self._manual_terminal_without_head(self.repo, other_pr, payload, command, common):
                        # Public terminality releases only the operational request slot; it is never review credit.
                        continue
                    raise ControllerError(
                        f"another manual Hosted request is unresolved for PR #{other_pr}: "
                        "its command-time head cannot be verified"
                    )
                author = command.get("author")
                record = {
                    "status": "posted",
                    "head_sha": reviewed_head,
                    "trigger": {
                        "id": github.immutable_database_id(dict(command)),
                        "created_at": command.get("createdAt"),
                        "url": command.get("url"),
                        "author_login": author.get("login") if isinstance(author, Mapping) else None,
                        "type": "full",
                        "command": hosted.FULL_COMMAND,
                    },
                }
                state = hosted.trigger_state(
                    self.repo,
                    other_pr,
                    payload,
                    record,
                    hosted.default_trigger_record_path(self.repo, other_pr, common),
                )
            except ControllerError:
                raise
            except (
                OSError,
                RuntimeError,
                ValueError,
                KeyError,
                TypeError,
                AttributeError,
                json.JSONDecodeError,
            ) as exc:
                raise ControllerError(f"manual Hosted request on PR #{other_pr} cannot be verified") from exc
            if (
                state.state not in {"completed", "failed", "rate_limited", "noop"}
                or state.terminal is not True
                or state.attributed is not True
                or state.response_id is None
                or state.reason == "CodeRabbit finished after explicitly reporting incomplete file coverage"
            ):
                raise ControllerError(f"another manual Hosted request is unresolved for PR #{other_pr}: {state.state}")

    def __call__(
        self,
        target: ReviewTarget,
        *,
        expect_pr: int | None = None,
        admit: Callable[[Callable[[], None]], None] | None = None,
        **_: Any,
    ) -> dict[str, Any]:
        if target.default_base_front and not target.has_current_default_test_merge_proof():
            raise ControllerError("direct default-base target has no verified current base/head test merge")
        pr = target.snapshot.number
        hosted.assert_expected_pr(pr, expect_pr)
        before = self.live.pull_request(pr)
        if before != target.snapshot:
            if self._base_advanced(target, before):
                raise StaleReviewTarget("default base advanced after Hosted target selection")
            raise ControllerError("pull request changed after Hosted target selection")
        if before.changed_files > _HOSTED_CODERABBIT_FILE_CEILING:
            raise ControllerError(
                f"Hosted review supports at most {_HOSTED_CODERABBIT_FILE_CEILING} changed files; "
                "use the CLI review path for a larger diff"
            )
        parent_tip = self.live.branch_head(target.parent.ref_name)
        if parent_tip != target.parent.head_sha:
            if (
                target.default_base_front
                and before.head_sha.casefold() == target.snapshot.head_sha.casefold()
                and before.base_ref_name == target.parent.ref_name
                and target.has_current_default_test_merge_proof()
            ):
                raise StaleReviewTarget("default base advanced after Hosted target selection")
            raise ControllerError("effective parent changed after Hosted target selection")
        path = hosted.default_trigger_record_path(self.repo, pr)
        common = evidence.git_common_dir()
        repository_dir = common / "firemud" / "hosted" / self.repo.replace("/", "_")
        repository_dir.mkdir(parents=True, exist_ok=True, mode=0o700)
        path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
        repo_lock_path = repository_dir / "repository.request.lock"
        lock_path = path.parent / "request.lock"
        with ExitStack() as locks:
            try:
                repo_lock = locks.enter_context(repo_lock_path.open("a+", encoding="utf-8"))
                fcntl.flock(repo_lock.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
            except BlockingIOError as exc:
                raise ControllerError(f"another Hosted request is active for repository {self.repo}") from exc
            except OSError as exc:
                raise ControllerError(
                    f"could not acquire the Hosted repository admission lock for {self.repo}"
                ) from exc
            try:
                lock = locks.enter_context(lock_path.open("a+", encoding="utf-8"))
                fcntl.flock(lock.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
            except BlockingIOError as exc:
                raise ControllerError(f"another Hosted request is active for PR #{pr}") from exc
            except OSError as exc:
                raise ControllerError(f"could not acquire the Hosted request lock for PR #{pr}") from exc
            archive_current_path: Path | None = None
            sqlite_capture_warnings: list[str] = []
            current_records = hosted.current_trigger_record_paths(self.repo, pr)
            if len(current_records) > 1:
                raise ControllerError("multiple current Hosted reservations require operator resolution")
            payload = github.fetch_pull_request(self.repo, pr)
            if current_records:
                current_path = current_records[0]
                record = hosted.load_trigger_reservation(current_path, self.repo, pr)
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
                if state.terminal is True:
                    capture_warning = self._capture_terminal_attempt(pr, record, payload, current_path)
                    if capture_warning is not None:
                        sqlite_capture_warnings.append(capture_warning)
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
                    archive_current_path = current_path
                else:
                    raise ControllerError("existing Hosted trigger has no archivable identity")
            self._assert_latest_manual_trigger_is_tracked(pr, payload)
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
            sqlite_attempt_id = uuid.uuid4().hex if self.records is not None else None
            if sqlite_attempt_id is not None:
                posting["sqlite_attempt_id"] = sqlite_attempt_id
            # Establish a durable post-reservation boundary before issuing
            # POST.  Do all live identity and comment-floor checks while the
            # request lock is held, then write one complete reservation.  A
            # floor-fetch failure must leave no trigger.json and must never
            # reach the POST below.
            try:
                # The repository-wide sweep can take seconds. Complete it
                # before the final target identity/comment check so a manual
                # target command during that sweep cannot race our POST.
                self._assert_no_other_active_reservations(pr, common)
                reservation_payload = github.fetch_pull_request(self.repo, pr)
                self._assert_latest_manual_trigger_is_tracked(pr, reservation_payload)
                reservation_pr = reservation_payload["data"]["repository"]["pullRequest"]
                reservation_head = reservation_pr.get("headRefOid") if isinstance(reservation_pr, dict) else None
                reservation_base_ref = reservation_pr.get("baseRefName") if isinstance(reservation_pr, dict) else None
                reservation_base = reservation_pr.get("baseRefOid") if isinstance(reservation_pr, dict) else None
                if not isinstance(reservation_pr, dict) or not isinstance(reservation_head, str):
                    raise ControllerError("pull request identity is incomplete before the Hosted posting boundary")
                if (
                    reservation_head.casefold() != target.snapshot.head_sha.casefold()
                    or reservation_base_ref != target.snapshot.base_ref_name
                    or not isinstance(reservation_base, str)
                    or reservation_base.casefold() != target.snapshot.base_sha.casefold()
                ):
                    if (
                        target.default_base_front
                        and reservation_head.casefold() == target.snapshot.head_sha.casefold()
                        and reservation_base_ref == target.parent.ref_name
                        and isinstance(reservation_base, str)
                        and reservation_base.casefold() != target.snapshot.base_sha.casefold()
                        and target.has_current_default_test_merge_proof()
                    ):
                        raise StaleReviewTarget("default base advanced before the Hosted posting boundary")
                    raise ControllerError("pull request changed before the Hosted posting boundary")
                current_parent_tip = self.live.branch_head(target.parent.ref_name)
                if current_parent_tip != target.parent.head_sha:
                    if (
                        target.default_base_front
                        and reservation_head.casefold() == target.snapshot.head_sha.casefold()
                        and target.has_current_default_test_merge_proof()
                    ):
                        raise StaleReviewTarget("default base advanced before the Hosted posting boundary")
                    raise ControllerError("effective parent changed before the Hosted posting boundary")
                posting["posting_comment_id_floor"] = hosted._comment_id_floor(reservation_payload)
            except (OSError, KeyError, TypeError, ValueError, json.JSONDecodeError) as exc:
                if isinstance(exc, ControllerError):
                    raise
                raise ControllerError(f"could not establish the Hosted pre-POST comment identity floor: {exc}") from exc
            sqlite_attempt_started = False
            if self.records is not None and sqlite_attempt_id is not None:
                try:
                    sqlite_hosted_capture.start_hosted_attempt(
                        self.records,
                        attempt_id=sqlite_attempt_id,
                        source_pr=pr,
                        candidate_sha=target.snapshot.head_sha,
                        started_at=posting_started_at,
                        metadata={
                            "repository": self.repo,
                            "anchor": anchor,
                            "posting_actor": posting_actor,
                        },
                    )
                    sqlite_attempt_started = True
                except Exception as exc:  # noqa: BLE001 - optional capture cannot block provider POST
                    # SQLite is optional for provider posting. Keep the ID in
                    # the reservation so explicit sync can adopt or backfill it.
                    sqlite_capture_warnings.append(f"SQLite Hosted attempt start failed ({type(exc).__name__}).")

            def reserve() -> None:
                if archive_current_path is not None:
                    trigger_id = (record.get("trigger") or {}).get("id")
                    archive = archive_current_path.with_name(f"trigger-{trigger_id}.json")
                    try:
                        os.replace(archive_current_path, archive)
                        hosted.atomic_write_json(path, posting)
                    except Exception as exc:
                        if sqlite_attempt_started and sqlite_attempt_id is not None:
                            self._finish_unposted_attempt(sqlite_attempt_id)
                        raise ControllerError("could not establish the durable Hosted posting reservation") from exc
                else:
                    try:
                        hosted.atomic_write_json(path, posting)
                    except Exception as exc:
                        if sqlite_attempt_started and sqlite_attempt_id is not None:
                            self._finish_unposted_attempt(sqlite_attempt_id)
                        raise ControllerError("could not establish the durable Hosted posting reservation") from exc

            if admit is None:
                reserve()
            else:
                try:
                    admit(reserve)
                except Exception:
                    if sqlite_attempt_started and sqlite_attempt_id is not None:
                        self._finish_unposted_attempt(sqlite_attempt_id)
                    raise
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
                    timeout=github.GH_API_TIMEOUT_SECONDS,
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
            before_identity = (before.head_sha.casefold(), before.base_ref_name, before.base_sha.casefold())
            after_identity: tuple[str, str, str] | None = None
            after_parent_tip: str | None = None
            boundary_verification_errors: list[str] = []
            try:
                after = self.live.pull_request(pr)
                after_identity = (after.head_sha.casefold(), after.base_ref_name, after.base_sha.casefold())
            except (
                OSError,
                subprocess.SubprocessError,
                RuntimeError,
                ValueError,
                TypeError,
                KeyError,
                AttributeError,
            ) as exc:
                boundary_verification_errors.append(f"PR refresh failed: {type(exc).__name__}: {exc}")
            try:
                observed_parent_tip = self.live.branch_head(target.parent.ref_name)
                if not isinstance(observed_parent_tip, str) or not re.fullmatch(
                    r"[0-9a-fA-F]{40}", observed_parent_tip
                ):
                    raise ValueError("effective parent tip is malformed")
                after_parent_tip = observed_parent_tip.casefold()
            except (
                OSError,
                subprocess.SubprocessError,
                RuntimeError,
                ValueError,
                TypeError,
                KeyError,
                AttributeError,
            ) as exc:
                boundary_verification_errors.append(f"parent-tip refresh failed: {type(exc).__name__}: {exc}")
            boundary_changed = (after_identity is not None and after_identity != before_identity) or (
                after_parent_tip is not None and after_parent_tip != target.parent.head_sha.casefold()
            )
            if boundary_changed:
                status = "posted_boundary_changed"
            elif boundary_verification_errors:
                status = "posted_boundary_unverified"
            else:
                status = "posted"
            record = {
                **posting,
                "status": status,
                **(
                    {"posting_boundary_verification_errors": boundary_verification_errors}
                    if boundary_verification_errors
                    else {}
                ),
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
            if status == "posted_boundary_changed":
                raise ControllerError("pull request or effective parent changed across the Hosted posting boundary")
            if status == "posted_boundary_unverified":
                raise ControllerError("Hosted posting boundary could not be fully verified after POST")
            result = {
                "channel": "hosted",
                "pr": pr,
                "head": before.head_sha,
                "trigger_comment_id": trigger_id,
                "trigger_url": normalized["url"],
                "status": status,
                "anchor": anchor,
            }
            if sqlite_capture_warnings:
                result["sqlite_capture_warnings"] = sqlite_capture_warnings
            return result


def default_controller(repo: str | None = None) -> ReviewController:
    selected = github.infer_repo(repo)
    repository = github.repository_metadata(selected)
    live = LiveGitHub(selected)
    store = ControllerStateStore()
    records = SqliteReviewRecords(sqlite_state_path(store.path)) if store.path.is_dir() else None
    observations = LiveEvidence(selected, live, store)
    git_provider = DefaultGitProvider()
    hosted_runner = HostedRunner(selected, live, store, records=records)

    def cli_adapter(target: ReviewTarget, **kwargs: Any) -> Any:
        return run_cli_review(target, github=live, records=records, **kwargs)

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
