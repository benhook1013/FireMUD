"""Live GitHub, evidence, and review adapters for the unified controller."""

from __future__ import annotations

import fcntl
import hashlib
import json
import os
import re
import stat
import subprocess
from collections.abc import Mapping, Sequence
from datetime import datetime, timezone
from typing import Any

from . import evidence, github, hosted
from . import status as status_module
from .cli_runner import PullRequestSnapshot, ReviewRunnerError, ReviewTarget, run_cli_review
from .controller import ControllerError, DefaultGitProvider, ReviewController
from .state import (
    StateError,
    StateStore,
    SummaryFindingDisposition,
    adjudicate_summary_findings,
    observation_fingerprint,
)

_PLAN_CEILING_PATTERN = re.compile(
    r"(?is)(?:(?:exceed\w*|too many|over|reject\w*|skip\w*).{0,120}"
    r"(?:file|files).{0,120}(?:plan|limit|ceiling|maximum|cap)"
    r"|(?:plan|review).{0,120}(?:file|files).{0,120}"
    r"(?:limit|ceiling|maximum|cap).{0,120}(?:exceed\w*|too many|over|reject\w*|skip\w*))"
)
_CODERABBIT_FILE_CEILING = 100
_PREPOST_ABANDONED_PATTERN = re.compile(r"^prepost-abandoned-[0-9a-f]{20}\.json$")


class LiveGitHub:
    """The exact live GitHub boundary shared by selection and review preflight."""

    def __init__(self, repo: str) -> None:
        self.repo = github.infer_repo(repo)

    def metadata(self, number: int) -> dict[str, Any]:
        return github.fetch_pr_metadata(self.repo, number)

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
                or any(not part or any(character.isspace() for character in part) for part in head_repository.split("/"))
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
            author = ((item.get("author") or {}).get("login"))
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

        identity = github.immutable_database_id(item)
        checkpoint = checkpoint_by_response.get(identity) if identity is not None else None
        raw_body = item.get("body")
        body = raw_body if isinstance(raw_body, str) else ""
        if timestamp_field == "submittedAt":
            if item.get("state") == "DISMISSED":
                return None
            if item.get("state") not in {"COMMENTED", "APPROVED", "CHANGES_REQUESTED"}:
                return "ambiguous"
            commit = (item.get("commit") or {}).get("oid")
            if isinstance(commit, str) and hosted.EXACT_SHA.fullmatch(commit):
                return "completed"
            if hosted._substantive(body):
                return "completed" if hosted._scope_head(body) else "ambiguous"
            if checkpoint is not None and isinstance(checkpoint.reviewed_sha, str):
                return "completed"
            return None

        created = hosted.parse_timestamp(item.get("createdAt"))
        if hosted.REVIEW_LIMIT_MARKER in body or (
            created is not None and hosted._rate_limit(body, created) is not None
        ) or body.strip().lower().startswith("review rate limited"):
            return "rate_limited"
        if hosted.NOOP_MARKER in body:
            return "noop"
        if hosted.ACTIVE_PATTERN.search(hosted._unquoted(body)):
            return "active"
        if hosted.FAILED_PATTERN.search(hosted._unquoted(body)):
            return "failed"
        if hosted._substantive(body) or hosted.FINISHED_REVIEW_PATTERN.search(hosted._unquoted(body)):
            if hosted._scope_head(body):
                return "completed"
            if checkpoint is not None and isinstance(checkpoint.reviewed_sha, str):
                return "completed"
            return "ambiguous"
        return None

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
        responses = [
            (item, "createdAt")
            for item in comments
            if github.immutable_database_id(item) == response_id
        ] + [
            (item, "submittedAt")
            for item in reviews
            if github.immutable_database_id(item) == response_id
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

        A retained ambiguous response remains explicitly non-counting. The pin is
        accepted only when it names one verified immutable terminal response and
        removes only that response's matching generic audit blockers.
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

        # The established audit validates all paginated identities, trigger to
        # response linkage, public checkpoints, pending captures, and unresolved
        # review findings. Its anchor records live GitHub parent identity as well
        # as the exact head supplied by the controller's fresh stack selection.
        audit = self.legacy_transition_reauthorization_audit(
            pr,
            (),
            {
                "child_head": child_head,
                "live_base_ref": current.base_ref_name,
                "live_base_tip": parent_head,
            },
        )
        payload = self._payload(pr)
        channel_history = {
            channel: list(self.history(pr, channel))
            for channel in ("hosted", "cli")
        }

        ambiguous_terminal_responses: list[dict[str, Any]] = []
        for path in self._complete_trigger_paths(self.repo, pr):
            try:
                record = hosted.load_trigger_record(path, self.repo, pr)
                state = hosted.trigger_state(self.repo, pr, payload, record, path)
            except (OSError, ValueError, KeyError, TypeError, json.JSONDecodeError) as error:
                raise ControllerError("a Hosted trigger record cannot be completely audited") from error
            observation = self._terminal_ambiguous_hosted_observation(pr, record, state, payload)
            if observation is not None:
                ambiguous_terminal_responses.append(observation)
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
                str(item.get("reason") or item.get("checkpoint") or "unresolved CLI evidence")
                for item in cli_pending
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
            "ambiguous_responses": ambiguous_responses,
            "unresolved_findings": unresolved_findings,
            "checkpoints": checkpoints,
            "ambiguous_terminal_responses": ambiguous_terminal_responses,
            "retained_ambiguous": list(retained_by_fingerprint.values()),
        }

    def legacy_transition_reauthorization_audit(
        self,
        pr: int,
        expected_hosted_fingerprints: tuple[str, ...],
        expected_anchor: dict[str, Any],
    ) -> dict[str, Any]:
        """Prove completeness and attribution of all currently available Hosted evidence."""

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
            response_id: matching[0]
            for response_id, matching in checkpoints_by_response.items()
            if len(matching) == 1
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
                unmatched_responses.append("response has no preceding full-review trigger")
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
                    (
                        item
                        for item in (*comments, *reviews)
                        if github.immutable_database_id(item) == response_id
                    ),
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
                    unmatched_responses.append("completed Hosted response has no checkpoint or prior audit")

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
                    unmatched_responses.append(
                        "an unrecorded completed Hosted response has no matching public checkpoint"
                    )
                elif len(matching_checkpoints) != 1:
                    ambiguous_responses.append(
                        "an unrecorded completed Hosted response has ambiguous public checkpoints"
                    )

        hosted_history = self.history(pr, "hosted")
        represented_checkpoints = {
            str(item.get("checkpoint")) for item in hosted_history if isinstance(item, dict)
        }
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
            review_matches = [
                item for item in reviews
                if github.immutable_database_id(item) == response_id
            ]
            trigger = next(
                (
                    item for item in comments
                    if github.immutable_database_id(item) == state.trigger_comment_id
                ),
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
                not ambiguous_responses
                and state.state == "completed"
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
                unmatched_responses.append("a public Hosted checkpoint has no unique attributable trigger")
        unresolved_findings = [
            str(item.get("checkpoint", "Hosted finding"))
            for item in hosted_history
            if str(item.get("checkpoint", "")).startswith(
                ("review-threads:", "summary-actions:", "over-ceiling:")
            )
            and (item.get("held") is True or item.get("unstable") is True or item.get("over_ceiling") is True)
        ]
        return {
            "complete": True,
            "active_reservations": active_reservations,
            "unmatched_responses": unmatched_responses,
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
            or (
                checkpoint.duration_seconds is not None
                and checkpoint.duration_seconds != response_duration_seconds
            )
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
            author = ((item.get("author") or {}).get("login"))
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

        summaries: list[tuple[datetime, int, bool]] = []
        for item in comments:
            author = ((item.get("author") or {}).get("login"))
            body = item.get("body") or ""
            updated = hosted.parse_timestamp(item.get("updatedAt"))
            if (
                not hosted.is_coderabbit_login(author)
                or updated is None
                or updated <= trigger_at
                or (next_trigger is not None and updated >= next_trigger)
                or hosted._rate_limit(body, updated) is not None
            ):
                continue
            exact_head = hosted._matches_head(body, captured_head)
            zero_finding = (
                "No actionable comments were generated in the recent review." in body and exact_head
            )
            if zero_finding or (hosted._substantive(body) and exact_head):
                summaries.append((updated, github.immutable_database_id(item) or 0, zero_finding))
        provider_summary = hosted.provider_format_zero_finding_summary(
            payload,
            captured_head,
            trigger_at,
            response_id,
            next_trigger,
        )
        if provider_summary is not None:
            provider_updated = hosted.parse_timestamp(provider_summary.get("updatedAt"))
            provider_id = github.immutable_database_id(provider_summary)
            if provider_updated is not None and provider_id is not None:
                summaries.append((provider_updated, provider_id, True))
        if not summaries or not max(summaries)[2]:
            return None

        # A reply-only checkpoint is intentionally limited to runs with no PR
        # review object in the captured trigger window. This avoids choosing a
        # reply over a conflicting or mismatched immutable review commit.
        for review in pr["reviews"]["nodes"]:
            author = ((review.get("author") or {}).get("login"))
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
                proof = self._hosted_zero_reply_proof(
                    checkpoint,
                    state.response_id,
                    state.duration_seconds,
                    captured_head,
                    record,
                    payload,
                ) or proof
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
        emitted_hosted_response_ids: set[int] = set()
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
                    "observed_at": checkpoint.created_at,
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
                candidate_sha = capture.metadata.get("candidate_sha", "")
                published_head_sha = capture.metadata.get("published_head_sha", candidate_sha)
                current_head = any(
                    value.casefold() == head.casefold()
                    for value in (candidate_sha, published_head_sha)
                    if value
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
                elif state.state == "completed" and state.response_id not in emitted_hosted_response_ids:
                    values.append(self._uncheckpointed_hosted_observation(pr, state))
                elif state.state in {"active", "awaiting_response", "ambiguous", "unattributed", "timed_out"}:
                    observation = {
                        "pr": pr,
                        "head": state.head_sha,
                        "checkpoint": f"trigger:{state.trigger_comment_id or 'pending'}",
                        "held": state.state in {"active", "awaiting_response", "ambiguous", "unattributed"},
                        "unstable": state.state in {"ambiguous", "unattributed", "timed_out"},
                        "reason": state.reason,
                    }
                    if state.state == "ambiguous":
                        terminal_observation = self._terminal_ambiguous_hosted_observation(
                            pr, record, state, payload
                        )
                        if terminal_observation is not None:
                            observation.update(
                                {
                                    "terminal_ambiguous": True,
                                    **terminal_observation,
                                }
                            )
                    values.append(observation)
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
            after = self.live.pull_request(pr)
            before_identity = (before.head_sha.casefold(), before.base_ref_name, before.base_sha.casefold())
            after_identity = (after.head_sha.casefold(), after.base_ref_name, after.base_sha.casefold())
            status = "posted" if after_identity == before_identity else "posted_boundary_changed"
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
