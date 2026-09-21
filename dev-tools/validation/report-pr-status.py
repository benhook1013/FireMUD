#!/usr/bin/env python3
"""Compose the canonical read-only evidence for one pull-request status report."""

from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
from collections.abc import Sequence
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path
from typing import Any
from zoneinfo import ZoneInfo

from display_sanitization import display as _display

ROOT = Path(__file__).resolve().parents[2]
CHECKPOINT_REPORTER = ROOT / "dev-tools" / "validation" / "report-pr-review-checkpoints.py"
CODERABBIT_CHECKER = ROOT / "dev-tools" / "validation" / "check-coderabbit-review.py"
PROVIDER_TIMEOUT_SECONDS = 180
HUMAN_TIME_ZONE = ZoneInfo("Pacific/Auckland")
EXACT_SHA = re.compile(r"^[0-9a-fA-F]{40}$")
REVIEWED_SHA = re.compile(r"^[0-9a-fA-F]{7,40}$")
ARCHIVED_TRIGGER_RECORD = re.compile(r"^trigger-([1-9][0-9]*)\.json$")
RUN_ID = re.compile(r"^run\.[A-Za-z0-9]{1,32}$")
LOC_METADATA_LINE = re.compile(
    r"^<!-- firemud:cloc-report:metadata (?P<payload>\{.*\}) -->$"
)
LOC_METADATA_PREFIX = "<!-- firemud:cloc-report:metadata "
TRIGGER_STATES = {
    "active",
    "ambiguous",
    "awaiting_response",
    "completed",
    "failed",
    "noop",
    "rate_limited",
    "retired",
    "timed_out",
    "unattributed",
}

SUCCESS_VALUES = {"SUCCESS", "SKIPPED", "NEUTRAL"}
FAILURE_VALUES = {
    "ACTION_REQUIRED",
    "CANCELLED",
    "ERROR",
    "FAILURE",
    "STARTUP_FAILURE",
    "STALE",
    "TIMED_OUT",
}
PENDING_VALUES = {"EXPECTED", "IN_PROGRESS", "PENDING", "QUEUED", "REQUESTED", "RUNNING", "WAITING"}
KNOWN_LIFECYCLE_VALUES = PENDING_VALUES | SUCCESS_VALUES | FAILURE_VALUES | {"COMPLETED"}
MERGEABLE_VALUES = {"MERGEABLE", "CONFLICTING", "UNKNOWN"}
MERGE_STATE_VALUES = {
    "BEHIND",
    "BLOCKED",
    "CLEAN",
    "DIRTY",
    "DRAFT",
    "HAS_HOOKS",
    "UNKNOWN",
    "UNSTABLE",
}

GH_PR_IDENTITY_FIELDS = (
    "number,title,headRefName,headRefOid,baseRefName,baseRefOid,changedFiles,body"
)
GH_PR_STATE_FIELDS = "statusCheckRollup,mergeable,mergeStateStatus,isDraft,url"
GH_PR_FIELDS = f"{GH_PR_IDENTITY_FIELDS},{GH_PR_STATE_FIELDS}"


class ReportError(ValueError):
    """Raised when one of the required report evidence providers is unusable."""


@dataclass(frozen=True)
class ProviderEvidence:
    name: str
    payload: dict[str, Any]
    exit_code: int


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Compose a read-only complete pull-request status report."
    )
    parser.add_argument("--repo", required=True, help="GitHub repository in OWNER/REPO form")
    parser.add_argument("--pr", required=True, type=int, help="Pull request number")
    parser.add_argument("--json", action="store_true", help="Emit machine-readable JSON")
    return parser.parse_args()


def _timestamp(value: Any, field: str) -> datetime:
    if not isinstance(value, str) or not value:
        raise ReportError(f"{field} must be a non-empty timestamp string")
    try:
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError as exc:
        raise ReportError(f"{field} is not a valid timestamp") from exc
    if parsed.tzinfo is None:
        raise ReportError(f"{field} has no timezone")
    return parsed


def format_human_timestamp(value: str) -> str:
    return _timestamp(value, "review timestamp").astimezone(HUMAN_TIME_ZONE).strftime(
        "%Y-%m-%d %H:%M:%S %Z"
    )


def _run_json_provider(
    command: Sequence[str],
    name: str,
    *,
    allow_nonzero: bool = False,
) -> ProviderEvidence:
    try:
        completed = subprocess.run(
            list(command),
            check=False,
            capture_output=True,
            text=True,
            timeout=PROVIDER_TIMEOUT_SECONDS,
        )
    except FileNotFoundError as exc:
        raise ReportError(f"{name} provider is unavailable: {command[0]}") from exc
    except subprocess.TimeoutExpired as exc:
        raise ReportError(f"{name} provider timed out") from exc
    except OSError as exc:
        raise ReportError(f"{name} provider failed to start: {exc}") from exc

    stdout = (completed.stdout or "").strip()
    if completed.returncode != 0 and not allow_nonzero:
        stderr = (completed.stderr or "").strip()
        detail = stderr or f"exited with status {completed.returncode}"
        raise ReportError(f"{name} provider failed: {detail}")
    if not stdout:
        raise ReportError(f"{name} provider returned no JSON evidence")
    try:
        payload = json.loads(stdout)
    except json.JSONDecodeError as exc:
        raise ReportError(f"{name} provider returned malformed JSON") from exc
    if not isinstance(payload, dict):
        raise ReportError(f"{name} provider returned a JSON value instead of an object")
    return ProviderEvidence(name=name, payload=payload, exit_code=completed.returncode)


def _require(payload: dict[str, Any], key: str, expected: type | tuple[type, ...], name: str) -> Any:
    if key not in payload or not isinstance(payload[key], expected):
        raise ReportError(f"{name} evidence is missing a valid {key}")
    return payload[key]


def _nonnegative_int(value: Any, field: str) -> int:
    if isinstance(value, bool) or not isinstance(value, int) or value < 0:
        raise ReportError(f"{field} must be a non-negative integer")
    return value


def _validate_checkpoint_record(checkpoint: Any, index: int, name: str) -> dict[str, Any]:
    if not isinstance(checkpoint, dict):
        raise ReportError(f"{name} checkpoint {index} is not an object")
    required_fields = (
        "comment_id",
        "created_at",
        "type",
        "raw_found",
        "accepted",
        "reviewed_sha",
        "file_count",
        "correction",
    )
    missing = [field for field in required_fields if field not in checkpoint]
    if missing:
        raise ReportError(f"{name} checkpoint {index} is missing {missing[0]}")
    source = checkpoint.get("type")
    if not isinstance(source, str) or source not in {"Hosted", "CLI"}:
        raise ReportError(f"{name} checkpoint {index} has an invalid type")
    created_at = checkpoint.get("created_at")
    _timestamp(created_at, f"{name} checkpoint {index} created_at")
    raw_found = _nonnegative_int(checkpoint.get("raw_found"), f"{name} checkpoint {index} raw_found")
    accepted = _nonnegative_int(checkpoint.get("accepted"), f"{name} checkpoint {index} accepted")
    if accepted > raw_found:
        raise ReportError(f"{name} checkpoint {index} accepted exceeds raw_found")
    comment_id = checkpoint.get("comment_id")
    if comment_id is not None:
        _nonnegative_int(comment_id, f"{name} checkpoint {index} comment_id")
        if comment_id == 0:
            raise ReportError(f"{name} checkpoint {index} comment_id must be positive")
    file_count = checkpoint.get("file_count")
    if file_count is not None:
        _nonnegative_int(file_count, f"{name} checkpoint {index} file_count")
    reviewed_sha = checkpoint.get("reviewed_sha")
    if reviewed_sha is not None and (
        not isinstance(reviewed_sha, str) or not REVIEWED_SHA.fullmatch(reviewed_sha)
    ):
        raise ReportError(f"{name} checkpoint {index} has an invalid reviewed_sha")
    correction = checkpoint.get("correction")
    if not isinstance(correction, bool):
        raise ReportError(f"{name} checkpoint {index} correction must be boolean")
    updated_at = checkpoint.get("updated_at")
    if updated_at is not None:
        _timestamp(updated_at, f"{name} checkpoint {index} updated_at")
    run_id = checkpoint.get("run_id")
    if run_id is not None and (not isinstance(run_id, str) or not RUN_ID.fullmatch(run_id)):
        raise ReportError(f"{name} checkpoint {index} has an invalid run_id")
    hosted_review_id = checkpoint.get("hosted_review_id")
    if hosted_review_id is not None:
        _nonnegative_int(hosted_review_id, f"{name} checkpoint {index} hosted_review_id")
        if hosted_review_id == 0:
            raise ReportError(f"{name} checkpoint {index} hosted_review_id must be positive")
    return checkpoint


def _validate_checkpoint_evidence(payload: dict[str, Any]) -> list[dict[str, Any]]:
    name = "checkpoint reporter"
    checkpoints = _require(payload, "checkpoints", list, name)
    timeline = _require(payload, "timeline", list, name)
    warnings = _require(payload, "warnings", list, name)
    if any(not isinstance(warning, str) for warning in warnings):
        raise ReportError(f"{name} evidence has an invalid warning")
    hosted_reviews = payload.get("hosted_review_checkpoint")
    if hosted_reviews is not None:
        if not isinstance(hosted_reviews, dict):
            raise ReportError(f"{name} hosted review checkpoint evidence is not an object")
        if not isinstance(hosted_reviews.get("available"), bool):
            raise ReportError(f"{name} hosted review checkpoint evidence has invalid availability")
        for key in ("completed_count", "marked_count", "missing_count"):
            value = hosted_reviews.get(key)
            if value is not None:
                _nonnegative_int(value, f"{name} hosted review {key}")
        missing_ids = hosted_reviews.get("missing_review_ids")
        if not isinstance(missing_ids, list) or any(
            isinstance(value, bool) or not isinstance(value, int) or value <= 0
            for value in missing_ids
        ):
            raise ReportError(f"{name} hosted review missing IDs are invalid")
        if hosted_reviews.get("missing_count") is not None and hosted_reviews["missing_count"] != len(missing_ids):
            raise ReportError(f"{name} hosted review missing count is inconsistent")
        for key in ("malformed_count", "duplicate_count", "wrong_id_count"):
            value = hosted_reviews.get(key)
            if value is not None:
                _nonnegative_int(value, f"{name} hosted review {key}")
        wrong_ids = hosted_reviews.get("wrong_marker_ids")
        if not isinstance(wrong_ids, list) or any(
            isinstance(value, bool) or not isinstance(value, int) or value <= 0
            for value in wrong_ids
        ):
            raise ReportError(f"{name} hosted review wrong marker IDs are invalid")
        if hosted_reviews.get("wrong_id_count") is not None and hosted_reviews["wrong_id_count"] != len(wrong_ids):
            raise ReportError(f"{name} hosted review wrong ID count is inconsistent")
        completed_reviews = hosted_reviews.get("completed_reviews")
        if not isinstance(completed_reviews, list):
            raise ReportError(f"{name} hosted review details are invalid")
        for review in completed_reviews:
            if not isinstance(review, dict):
                raise ReportError(f"{name} hosted review detail is not an object")
            review_id = review.get("review_id")
            if isinstance(review_id, bool) or not isinstance(review_id, int) or review_id <= 0:
                raise ReportError(f"{name} hosted review detail has an invalid ID")
            _timestamp(review.get("submitted_at"), f"{name} hosted review submitted_at")
            commit_id = review.get("commit_id")
            if not isinstance(commit_id, str) or not EXACT_SHA.fullmatch(commit_id):
                raise ReportError(f"{name} hosted review detail has an invalid commit ID")
    unparsed_candidates = payload.get("unparsed_candidates")
    _nonnegative_int(unparsed_candidates, f"{name} unparsed_candidates")
    for index, event in enumerate(timeline, 1):
        if not isinstance(event, dict):
            raise ReportError(f"{name} timeline event {index} is not an object")
        if not isinstance(event.get("kind"), str) or event["kind"] not in {"checkpoint", "scope_change"}:
            raise ReportError(f"{name} timeline event {index} has an invalid kind")
        if event["kind"] == "checkpoint":
            _validate_checkpoint_record(event, index, name)
        else:
            _timestamp(event.get("created_at"), f"{name} timeline event {index} created_at")
        if event["kind"] == "scope_change" and not isinstance(event.get("description"), str):
            raise ReportError(f"{name} scope-change event {index} has no valid description")
        if event["kind"] == "scope_change" and event.get("comment_id") is not None:
            comment_id = event["comment_id"]
            _nonnegative_int(comment_id, f"{name} scope-change event {index} comment_id")
            if comment_id == 0:
                raise ReportError(f"{name} scope-change event {index} comment_id must be positive")
        if event["kind"] == "scope_change" and event.get("updated_at") is not None:
            _timestamp(event["updated_at"], f"{name} scope-change event {index} updated_at")

    validated: list[dict[str, Any]] = []
    for index, checkpoint in enumerate(checkpoints, 1):
        validated.append(_validate_checkpoint_record(checkpoint, index, name))
    return sorted(validated, key=lambda item: _timestamp(item["created_at"], "checkpoint timestamp"))


def _validate_checker_evidence(payload: dict[str, Any]) -> None:
    name = "CodeRabbit checker"
    required_types: dict[str, type | tuple[type, ...]] = {
        "repo": str,
        "pr_number": int,
        "head_sha": str,
        "ok": bool,
        "unresolved_non_outdated": int,
        "unresolved_outdated": int,
        "unresolved_total": int,
        "explicit_review_after_latest_commit": bool,
        "review_finished_after_latest_request": bool,
        "substantive_review_after_latest_commit": bool,
        "retrigger_review_allowed": bool,
        "manual_thread_resolution_required": bool,
        "must_resolve_outdated_threads": bool,
        "latest_review_request_rate_limited": bool,
        "latest_review_request_noop": bool,
        "latest_review_request_failed": bool,
        "review_rate_limit_until": (str, type(None)),
        "latest_explicit_review_request_type": (str, type(None)),
        "incremental_review_exception_allowed": bool,
        "outside_diff_actionable_comments": int,
        "duplicate_actionable_comments": int,
        "reasons": list,
    }
    for key, expected in required_types.items():
        _require(payload, key, expected, name)
    if not payload["repo"]:
        raise ReportError(f"{name} evidence has an empty repo")
    if isinstance(payload["pr_number"], bool) or payload["pr_number"] <= 0:
        raise ReportError(f"{name} evidence has an invalid pr_number")
    if not EXACT_SHA.fullmatch(payload["head_sha"]):
        raise ReportError(f"{name} evidence head_sha must be exactly 40 hexadecimal characters")
    for key in (
        "unresolved_non_outdated",
        "unresolved_outdated",
        "unresolved_total",
        "outside_diff_actionable_comments",
        "duplicate_actionable_comments",
    ):
        _nonnegative_int(payload[key], f"{name} {key}")
    if any(not isinstance(reason, str) for reason in payload["reasons"]):
        raise ReportError(f"{name} evidence has an invalid reason")
    if len(payload["reasons"]) != len(set(payload["reasons"])):
        raise ReportError(f"{name} evidence has duplicate reasons")
    request_type = payload["latest_explicit_review_request_type"]
    if request_type not in {None, "full", "incremental"}:
        raise ReportError(f"{name} evidence has an invalid latest review request type")
    request_id = payload.get("latest_explicit_review_request_id")
    if request_id is not None and (
        isinstance(request_id, bool) or not isinstance(request_id, int) or request_id <= 0
    ):
        raise ReportError(f"{name} evidence has an invalid latest review request id")
    request_url = payload.get("latest_explicit_review_request_url")
    if request_url is not None and (not isinstance(request_url, str) or not request_url):
        raise ReportError(f"{name} evidence has an invalid latest review request url")
    request_at = payload.get("latest_explicit_review_request_at")
    if request_at is not None:
        _timestamp(request_at, f"{name} latest_explicit_review_request_at")
    request_command = payload.get("latest_explicit_review_request_command")
    if request_command is not None and request_command not in {
        "@coderabbitai full review",
        "@coderabbitai review",
    }:
        raise ReportError(f"{name} evidence has an invalid latest review request command")


def _checker_blockers(payload: dict[str, Any]) -> list[str]:
    blockers: list[str] = []
    if payload["unresolved_non_outdated"]:
        blockers.append("unresolved current threads")
    if payload["unresolved_outdated"]:
        blockers.append("unresolved outdated threads")
    if payload["outside_diff_actionable_comments"]:
        blockers.append("outside-diff summary findings")
    if payload["duplicate_actionable_comments"]:
        blockers.append("duplicate summary findings")
    if not payload["explicit_review_after_latest_commit"]:
        blockers.append("missing explicit review after the latest commit")
    if not payload["review_finished_after_latest_request"]:
        blockers.append("missing review after the latest request")
    if not payload["substantive_review_after_latest_commit"]:
        blockers.append("missing substantive review after the latest commit")
    if payload["latest_review_request_rate_limited"]:
        blockers.append("latest review request is rate limited")
    if payload["latest_review_request_noop"]:
        blockers.append("latest review request was a noop")
    if payload["latest_review_request_failed"]:
        blockers.append("latest review request failed")
    if (
        payload["latest_explicit_review_request_type"] == "incremental"
        and not payload["incremental_review_exception_allowed"]
    ):
        blockers.append("incremental review exception is unavailable")
    return blockers


def _validate_checker_semantics(payload: dict[str, Any], repo: str, pr_number: int) -> None:
    name = "CodeRabbit checker"
    if payload["repo"].casefold() != repo.casefold():
        raise ReportError(f"{name} evidence repository does not match the requested repo")
    if payload["pr_number"] != pr_number:
        raise ReportError(f"{name} evidence pull request does not match the requested PR")

    unresolved_current = payload["unresolved_non_outdated"]
    unresolved_outdated = payload["unresolved_outdated"]
    unresolved_total = payload["unresolved_total"]
    if unresolved_total != unresolved_current + unresolved_outdated:
        raise ReportError(f"{name} unresolved totals are inconsistent")
    if payload["manual_thread_resolution_required"] != (unresolved_total > 0):
        raise ReportError(f"{name} manual thread-resolution flag is inconsistent")
    if payload["must_resolve_outdated_threads"] != (unresolved_outdated > 0):
        raise ReportError(f"{name} outdated-thread flag is inconsistent")

    blocking_reasons = _checker_blockers(payload)

    review_still_running = (
        payload["explicit_review_after_latest_commit"]
        and not payload["review_finished_after_latest_request"]
        and not payload["latest_review_request_rate_limited"]
        and not payload["latest_review_request_noop"]
        and not payload["latest_review_request_failed"]
    )
    expected_retrigger = (
        unresolved_total == 0
        and not review_still_running
        and not payload["latest_review_request_rate_limited"]
        and not payload["latest_review_request_noop"]
    )
    if payload["retrigger_review_allowed"] != expected_retrigger:
        raise ReportError(f"{name} retrigger flag is inconsistent")

    reasons = payload["reasons"]
    if payload["ok"] != (not reasons):
        raise ReportError(f"{name} ok flag contradicts its reasons")
    if payload["ok"] == (bool(blocking_reasons)):
        raise ReportError(f"{name} ok flag contradicts its blocking fields")
    if blocking_reasons and not reasons:
        raise ReportError(f"{name} blocking fields have no reasons")
    if not blocking_reasons and reasons:
        raise ReportError(f"{name} reasons have no blocking field")
    if len(reasons) != len(blocking_reasons):
        raise ReportError(f"{name} reason count contradicts its blocking fields")
    expected_reason_markers = []
    if unresolved_current:
        expected_reason_markers.append("unresolved non-outdated")
    if unresolved_outdated:
        expected_reason_markers.append("unresolved outdated")
    if payload["outside_diff_actionable_comments"]:
        expected_reason_markers.append("outside-diff")
    if payload["duplicate_actionable_comments"]:
        expected_reason_markers.append("duplicate")
    if not payload["explicit_review_after_latest_commit"]:
        expected_reason_markers.append("no explicit CodeRabbit review request")
    if not payload["review_finished_after_latest_request"]:
        expected_reason_markers.append("latest explicit review request")
    if not payload["substantive_review_after_latest_commit"]:
        expected_reason_markers.append("latest PR commit")
    if payload["latest_review_request_rate_limited"]:
        expected_reason_markers.append("rate limited")
    if payload["latest_review_request_noop"]:
        expected_reason_markers.append("without reviewing commits")
    if payload["latest_review_request_failed"]:
        expected_reason_markers.append("review request failed")
    if (
        payload["latest_explicit_review_request_type"] == "incremental"
        and not payload["incremental_review_exception_allowed"]
    ):
        expected_reason_markers.append("incremental CodeRabbit review is not accepted")
    for marker in expected_reason_markers:
        if not any(marker in reason for reason in reasons):
            raise ReportError(f"{name} reasons do not explain {marker}")


def _validate_github_evidence(payload: dict[str, Any]) -> None:
    name = "GitHub PR"
    number = _require(payload, "number", int, name)
    if isinstance(number, bool) or number <= 0:
        raise ReportError(f"{name} evidence has an invalid number")
    for key in ("title", "headRefName", "headRefOid", "baseRefName", "baseRefOid"):
        value = _require(payload, key, str, name)
        if not value:
            raise ReportError(f"{name} evidence has an empty {key}")
    if not EXACT_SHA.fullmatch(payload["headRefOid"]):
        raise ReportError(f"{name} evidence headRefOid must be exactly 40 hexadecimal characters")
    if not EXACT_SHA.fullmatch(payload["baseRefOid"]):
        raise ReportError(f"{name} evidence baseRefOid must be exactly 40 hexadecimal characters")
    _require(payload, "statusCheckRollup", list, name)
    mergeable = _require(payload, "mergeable", str, name)
    if mergeable.upper() not in MERGEABLE_VALUES:
        raise ReportError(f"{name} evidence has an invalid mergeable value")
    merge_state = _require(payload, "mergeStateStatus", str, name)
    if merge_state.upper() not in MERGE_STATE_VALUES:
        raise ReportError(f"{name} evidence has an invalid mergeStateStatus value")
    _nonnegative_int(payload.get("changedFiles"), f"{name} changedFiles")
    _require(payload, "isDraft", bool, name)
    body = payload.get("body")
    if body is not None and not isinstance(body, str):
        raise ReportError(f"{name} evidence body must be a string or null")


def _git_common_dir() -> Path | None:
    """Resolve the shared Git directory without invoking a mutating Git command."""

    dot_git = ROOT / ".git"
    if dot_git.is_dir():
        return dot_git
    try:
        pointer = dot_git.read_text(encoding="utf-8").strip()
    except (OSError, UnicodeError):
        return None
    if not pointer.startswith("gitdir:"):
        return None
    worktree_git_dir = Path(pointer.partition(":")[2].strip())
    if not worktree_git_dir.is_absolute():
        worktree_git_dir = (ROOT / worktree_git_dir).resolve()
    try:
        common_pointer = (worktree_git_dir / "commondir").read_text(encoding="utf-8").strip()
    except (OSError, UnicodeError):
        return None
    common_dir = Path(common_pointer)
    if not common_dir.is_absolute():
        common_dir = (worktree_git_dir / common_dir).resolve()
    return common_dir if common_dir.is_dir() else None


def hosted_trigger_record_path(repo: str, pr_number: int) -> Path | None:
    common_dir = _git_common_dir()
    if common_dir is None:
        return None
    candidate = (
        common_dir
        / "coderabbit-review-logs"
        / "hosted"
        / repo.replace("/", "_")
        / f"pr-{pr_number}"
        / "trigger.json"
    )
    try:
        if candidate.is_file():
            return candidate
        if not candidate.parent.is_dir() or candidate.parent.is_symlink():
            return None
        archived: list[tuple[int, Path]] = []
        for path in candidate.parent.iterdir():
            match = ARCHIVED_TRIGGER_RECORD.fullmatch(path.name)
            if match is None or path.is_symlink() or not path.is_file():
                continue
            trigger_id = int(match.group(1))
            try:
                record = json.loads(path.read_text(encoding="utf-8"))
                trigger = record.get("trigger") if isinstance(record, dict) else None
                retirement = record.get("retirement") if isinstance(record, dict) else None
                evidence = retirement.get("evidence") if isinstance(retirement, dict) else None
                if (
                    not isinstance(record, dict)
                    or record.get("schema_version") != 1
                    or record.get("status") != "retired"
                    or record.get("repository") != repo
                    or record.get("pr_number") != pr_number
                    or not isinstance(record.get("head_sha"), str)
                    or EXACT_SHA.fullmatch(record["head_sha"]) is None
                    or not isinstance(trigger, dict)
                    or trigger.get("id") != trigger_id
                    or trigger.get("type") != "full"
                    or trigger.get("command") != "@coderabbitai full review"
                    or not isinstance(trigger.get("created_at"), str)
                    or _timestamp(trigger["created_at"], "archived trigger created_at") is None
                    or not isinstance(trigger.get("url"), str)
                    or not trigger["url"]
                    or not isinstance(retirement, dict)
                    or retirement.get("action") != "operator_retire"
                    or not isinstance(retirement.get("retired_at"), str)
                    or _timestamp(retirement["retired_at"], "archived retirement timestamp") is None
                    or not isinstance(retirement.get("reason"), str)
                    or not retirement["reason"]
                    or retirement.get("trigger_comment_id") != trigger_id
                    or not isinstance(retirement.get("expected_head_sha"), str)
                    or EXACT_SHA.fullmatch(retirement["expected_head_sha"]) is None
                    or not isinstance(evidence, dict)
                    or evidence.get("state") not in {"active", "timed_out"}
                    or evidence.get("captured_head_sha") != record["head_sha"]
                    or evidence.get("current_head_sha") != retirement["expected_head_sha"]
                ):
                    continue
            except (OSError, TypeError, ValueError, json.JSONDecodeError):
                continue
            archived.append((trigger_id, path))
        return max(archived, key=lambda item: item[0])[1] if archived else None
    except OSError:
        return None


def _validate_trigger_state(payload: dict[str, Any], repo: str, pr_number: int) -> dict[str, Any]:
    name = "Hosted trigger checker"
    state = _require(payload, "trigger_state", dict, name)
    for key in ("state", "repository", "pr_number", "head_sha", "current_head_sha"):
        if key not in state:
            raise ReportError(f"{name} evidence is missing a valid {key}")
    if not isinstance(state["state"], str) or state["state"] not in TRIGGER_STATES:
        raise ReportError(f"{name} evidence has an invalid state")
    if not isinstance(state["repository"], str) or not state["repository"]:
        raise ReportError(f"{name} evidence has an invalid repository")
    state_pr_number = state["pr_number"]
    if isinstance(state_pr_number, bool) or not isinstance(state_pr_number, int) or state_pr_number <= 0:
        raise ReportError(f"{name} evidence has an invalid pr_number")
    if state["repository"].casefold() != repo.casefold() or state_pr_number != pr_number:
        raise ReportError(f"{name} evidence does not match the requested PR")
    for key in ("head_sha", "current_head_sha"):
        if not isinstance(state[key], str) or not EXACT_SHA.fullmatch(state[key]):
            raise ReportError(f"{name} evidence {key} must be exactly 40 hexadecimal characters")
    for key in ("terminal", "attributed", "manual_adjudication_required"):
        if key in state and not isinstance(state[key], bool):
            raise ReportError(f"{name} evidence has an invalid {key}")
    for key in ("trigger_comment_id", "response_id"):
        value = state.get(key)
        if value is not None and (isinstance(value, bool) or not isinstance(value, int) or value <= 0):
            raise ReportError(f"{name} evidence has an invalid {key}")
    for key in ("trigger_created_at", "response_created_at", "cooldown_until"):
        value = state.get(key)
        if value is not None:
            _timestamp(value, f"{name} {key}")
    for key in ("trigger_url", "response_url", "reason"):
        value = state.get(key)
        if key == "reason":
            valid = isinstance(value, str) and bool(value)
        else:
            valid = value is None or (isinstance(value, str) and bool(value))
        if not valid:
            raise ReportError(f"{name} evidence has an invalid {key}")
    trigger_command = state.get("trigger_command")
    if trigger_command is not None and trigger_command not in {
        "@coderabbitai full review",
        "@coderabbitai review",
    }:
        raise ReportError(f"{name} evidence has an invalid trigger_command")
    return state


def _hosted_trigger_evidence(
    repo: str,
    pr_number: int,
    record_available: bool,
    checker_payload: dict[str, Any],
    current_head: str,
) -> dict[str, Any]:
    latest_request_at = checker_payload.get("latest_explicit_review_request_at")
    latest_request_type = checker_payload.get("latest_explicit_review_request_type")
    latest_request_id = checker_payload.get("latest_explicit_review_request_id")
    latest_request_url = checker_payload.get("latest_explicit_review_request_url")
    latest_request_command = checker_payload.get("latest_explicit_review_request_command")
    has_latest_request = isinstance(latest_request_at, str) and bool(latest_request_at)

    def request_state() -> str:
        if not has_latest_request:
            return "unavailable"
        if checker_payload.get("latest_review_request_rate_limited"):
            return "rate_limited"
        if checker_payload.get("latest_review_request_noop"):
            return "noop"
        if checker_payload.get("latest_review_request_failed"):
            return "failed"
        if checker_payload.get("review_finished_after_latest_request"):
            return "completed"
        if checker_payload.get("explicit_review_after_latest_commit"):
            return "active"
        return "awaiting_response"

    request_fields = {
        "latest_request_id": latest_request_id,
        "latest_request_created_at": latest_request_at,
        "latest_request_type": latest_request_type,
        "latest_request_url": latest_request_url,
        "latest_request_command": latest_request_command,
    }
    if not record_available:
        if has_latest_request:
            state = request_state()
            warning = None
            if state in {"active", "rate_limited"}:
                warning = (
                    "latest Hosted request is manual/unrecorded while it is "
                    f"{state.replace('_', ' ')}; use the manual-request waiter and do not overlap it"
                )
            return {
                "available": False,
                "record_available": False,
                "classification": "manual/unrecorded",
                "state": state,
                "reason": "the latest explicit Hosted request has no canonical durable trigger record",
                "validation_outcome": "unavailable",
                "warning": warning,
                **request_fields,
            }
        return {
            "available": False,
            "record_available": False,
            "classification": "unavailable/no-request",
            "state": "unavailable",
            "reason": "no explicit Hosted request is present",
            "validation_outcome": "unavailable",
            **request_fields,
        }
    try:
        state = _validate_trigger_state(checker_payload, repo, pr_number)
    except ReportError as exc:
        provider_state = checker_payload.get("trigger_state")
        evidence = {
            "available": True,
            "record_available": True,
            "classification": "malformed/unknown",
            "state": "ambiguous",
            "reason": str(exc),
            "validation_outcome": "invalid",
            **request_fields,
        }
        safe_state = (
            provider_state.get("state")
            if isinstance(provider_state, dict)
            and isinstance(provider_state.get("state"), str)
            and provider_state["state"] in TRIGGER_STATES
            else None
        )
        safe_reason = (
            provider_state.get("reason")
            if isinstance(provider_state, dict)
            and isinstance(provider_state.get("reason"), str)
            and provider_state["reason"]
            else None
        )
        if safe_state is not None:
            evidence["provider_state"] = safe_state
        if safe_reason is not None:
            evidence["provider_reason"] = safe_reason
        return evidence
    if state["current_head_sha"].casefold() != current_head.casefold():
        return {
            "available": True,
            "record_available": True,
            "classification": "ambiguous/overlapping",
            "state": "ambiguous",
            "reason": "Hosted trigger evidence does not match the current GitHub PR head",
            "validation_outcome": "invalid",
            **request_fields,
        }
    record_id = state.get("trigger_comment_id")
    same_request = (
        isinstance(latest_request_id, int)
        and isinstance(record_id, int)
        and latest_request_id == record_id
        and latest_request_at == state.get("trigger_created_at")
        and latest_request_url == state.get("trigger_url")
        and latest_request_type == state.get("trigger_type")
        and latest_request_command == state.get("trigger_command")
    )
    if latest_request_id is None and latest_request_at is not None:
        same_request = (
            latest_request_at == state.get("trigger_created_at")
            and (latest_request_url is None or latest_request_url == state.get("trigger_url"))
            and (latest_request_type is None or latest_request_type == state.get("trigger_type"))
        )

    if not has_latest_request:
        classification = "retired-archive" if state.get("state") == "retired" else "unavailable/no-request"
        provenance_reason = "the durable record exists but no explicit Hosted request is present in live PR evidence"
    elif same_request and state.get("state") == "ambiguous":
        classification = "ambiguous/overlapping"
        provenance_reason = "canonical trigger evidence is ambiguous"
    elif same_request and state.get("state") == "retired":
        classification = "retired-archive"
        provenance_reason = "latest explicit Hosted request matches a trigger retained in the retired archive"
    elif same_request and state.get("state") == "unattributed":
        classification = "reservation/unverified"
        provenance_reason = state.get("reason") or "canonical trigger reservation is not verified"
    elif same_request:
        classification = "canonical-recorded"
        provenance_reason = "latest explicit Hosted request matches the canonical durable trigger record"
    elif state.get("state") == "ambiguous":
        classification = "ambiguous/overlapping"
        provenance_reason = "durable trigger evidence overlaps another explicit Hosted request"
    elif state.get("state") == "unattributed":
        classification = "reservation/unverified"
        provenance_reason = state.get("reason") or "durable trigger reservation has not been verified"
    else:
        classification = "manual/unrecorded"
        provenance_reason = "a newer explicit Hosted request is not covered by the canonical durable trigger record"

    warning = None
    if classification == "manual/unrecorded" and request_state() in {"active", "rate_limited"}:
        warning = (
            "latest Hosted request is manual/unrecorded while it is "
            f"{request_state().replace('_', ' ')}; use the manual-request waiter and do not overlap it"
        )
    return {
        **state,
        "available": True,
        "record_available": True,
        "classification": classification,
        "provenance_reason": provenance_reason,
        "validation_outcome": "valid",
        "warning": warning,
        **request_fields,
    }


def _checkpoint_summaries(checkpoints: list[dict[str, Any]]) -> dict[str, Any]:
    by_type: dict[str, list[dict[str, Any]]] = {"Hosted": [], "CLI": []}
    for checkpoint in checkpoints:
        by_type[checkpoint["type"]].append(checkpoint)

    def summary(items: list[dict[str, Any]]) -> dict[str, Any]:
        return {
            "count": len(items),
            "raw_found": sum(item["raw_found"] for item in items),
            "accepted": sum(item["accepted"] for item in items),
            "latest": items[-1] if items else None,
        }

    cli = by_type["CLI"]
    cli_substantive = [checkpoint for checkpoint in cli if not checkpoint["correction"]]
    cli_zero_streak: list[dict[str, Any]] = []
    for checkpoint in reversed(cli_substantive):
        if checkpoint["accepted"] != 0:
            break
        cli_zero_streak.append(checkpoint)
    last_cli_accepted = next(
        (item for item in reversed(cli_substantive) if item["accepted"] > 0), None
    )

    hosted = by_type["Hosted"]
    hosted_completed = [checkpoint for checkpoint in hosted if not checkpoint["correction"]]

    def trailing_count(predicate: Any) -> int:
        count = 0
        for checkpoint in reversed(hosted_completed):
            if not predicate(checkpoint):
                break
            count += 1
        return count

    hosted_correction_exclusions = sum(1 for checkpoint in hosted if checkpoint["correction"])
    hosted_zero_zero_streak = trailing_count(
        lambda checkpoint: (
            checkpoint["raw_found"] == 0
            and checkpoint["accepted"] == 0
        )
    )
    hosted_raw_positive_accepted_zero_streak = trailing_count(
        lambda checkpoint: (
            checkpoint["raw_found"] > 0
            and checkpoint["accepted"] == 0
        )
    )
    hosted_completed_zero_zero_observed = sum(
        1
        for checkpoint in hosted_completed
        if checkpoint["raw_found"] == 0
        and checkpoint["accepted"] == 0
    )

    return {
        "observed": len(checkpoints),
        "by_type": {source: summary(items) for source, items in by_type.items()},
        "cli_zero_streak": {
            "count": len(cli_zero_streak),
            "since": cli_zero_streak[-1]["created_at"] if cli_zero_streak else None,
            "last_accepted_finding": (
                {
                    "comment_id": last_cli_accepted.get("comment_id"),
                    "created_at": last_cli_accepted["created_at"],
                    "accepted": last_cli_accepted["accepted"],
                }
                if last_cli_accepted
                else None
            ),
        },
        "taper_evidence": {
            "hosted_zero_zero_streak": hosted_zero_zero_streak,
            "hosted_raw_positive_accepted_zero_streak": hosted_raw_positive_accepted_zero_streak,
            "hosted_completed_zero_zero_observed": hosted_completed_zero_zero_observed,
            "hosted_raw_found": sum(item["raw_found"] for item in hosted),
            "hosted_accepted": sum(item["accepted"] for item in hosted),
            "hosted_correction_exclusions": hosted_correction_exclusions,
            "hosted_checkpoints_observed": len(hosted),
        },
    }


def _current_merge_base(base_oid: str, head_oid: str) -> str | None:
    try:
        completed = subprocess.run(
            ["git", "merge-base", base_oid, head_oid],
            cwd=ROOT,
            check=False,
            capture_output=True,
            text=True,
            timeout=PROVIDER_TIMEOUT_SECONDS,
        )
    except (OSError, subprocess.TimeoutExpired):
        return None
    if completed.returncode != 0:
        return None
    merge_base = (completed.stdout or "").strip()
    return merge_base.lower() if EXACT_SHA.fullmatch(merge_base) else None


def _loc_metadata_freshness(
    body: str,
    current_base: str,
    current_head: str,
    current_merge_base: str | None,
) -> dict[str, Any]:
    start_marker = "<!-- firemud:cloc-report:start -->"
    end_marker = "<!-- firemud:cloc-report:end -->"
    marker_lines = []
    line_offset = 0
    for line in body.splitlines(keepends=True):
        stripped_line = line.strip()
        if stripped_line.startswith(LOC_METADATA_PREFIX):
            marker_lines.append((line_offset, stripped_line))
        line_offset += len(line)
    if not marker_lines:
        return {"status": "missing", "reason": "PR body has no exact LOC metadata marker"}
    if len(marker_lines) != 1:
        return {"status": "ambiguous", "reason": "PR body has multiple LOC metadata markers"}
    marker_position, marker_text = marker_lines[0]
    marker_line = LOC_METADATA_LINE.fullmatch(marker_text)
    if marker_line is None:
        return {"status": "ambiguous", "reason": "PR body LOC metadata marker is malformed"}
    if body.count(start_marker) != 1 or body.count(end_marker) != 1:
        return {"status": "ambiguous", "reason": "PR body LOC marker block is incomplete or duplicated"}
    if not (body.find(start_marker) < marker_position < body.find(end_marker)):
        return {"status": "ambiguous", "reason": "LOC metadata marker is outside the marked report block"}
    try:
        metadata = json.loads(marker_line.group("payload"))
    except json.JSONDecodeError:
        return {"status": "ambiguous", "reason": "PR body LOC metadata is not valid JSON"}
    if not isinstance(metadata, dict):
        return {"status": "ambiguous", "reason": "PR body LOC metadata is not an object"}
    for key in ("base_oid", "head_oid", "merge_base"):
        value = metadata.get(key)
        if not isinstance(value, str) or not EXACT_SHA.fullmatch(value):
            return {"status": "ambiguous", "reason": f"PR body LOC metadata has no exact {key}"}
    reasons = []
    if metadata["head_oid"].casefold() != current_head.casefold():
        reasons.append("PR body LOC metadata head does not match the current PR head")
    if metadata["base_oid"].casefold() != current_base.casefold():
        reasons.append("PR body LOC metadata base does not match the current PR base")
    merge_base_checked = current_merge_base is not None
    if merge_base_checked and metadata["merge_base"].casefold() != current_merge_base.casefold():
        reasons.append("PR body LOC metadata merge-base does not match the current merge-base")
    result = {
        "status": "stale" if reasons else "fresh",
        "head_oid": metadata["head_oid"],
        "base_oid": metadata["base_oid"],
        "merge_base": metadata["merge_base"],
        "current_head_oid": current_head,
        "current_base_oid": current_base,
        "current_merge_base": current_merge_base,
        "merge_base_checked": merge_base_checked,
    }
    classifier = metadata.get("classifier_sha256")
    if not isinstance(classifier, str) or not re.fullmatch(r"[0-9a-fA-F]{64}", classifier):
        return {"status": "ambiguous", "reason": "PR body LOC metadata has an invalid classifier digest"}
    result["classifier_sha256"] = classifier
    if reasons:
        result["reason"] = "; ".join(reasons)
    return result


def _check_text(check: dict[str, Any], key: str) -> str | None:
    value = check.get(key)
    if value is None:
        return None
    if not isinstance(value, str):
        raise ReportError(f"GitHub PR status check has an invalid {key}")
    return value.upper()


def _coalesce_ci_checks(raw_checks: list[Any]) -> list[int]:
    """Drop superseded, identically named CheckRuns when their ordering is certain."""

    candidates: dict[tuple[str, str], list[tuple[int, datetime]]] = {}
    blocked_identities: set[tuple[str, str]] = set()
    for index, raw_check in enumerate(raw_checks):
        if not isinstance(raw_check, dict) or raw_check.get("__typename") != "CheckRun":
            continue
        workflow_name = raw_check.get("workflowName")
        name = raw_check.get("name")
        if not (isinstance(workflow_name, str) and workflow_name and isinstance(name, str) and name):
            continue
        identity = (workflow_name, name)
        started_at = raw_check.get("startedAt")
        if not isinstance(started_at, str):
            blocked_identities.add(identity)
            continue
        try:
            parsed_started_at = _timestamp(started_at, "CheckRun startedAt")
        except ReportError:
            blocked_identities.add(identity)
            continue
        candidates.setdefault(identity, []).append((index, parsed_started_at))

    superseded: set[int] = set()
    for identity, entries in candidates.items():
        if identity in blocked_identities:
            continue
        latest = max(started_at for _, started_at in entries)
        latest_entries = [index for index, started_at in entries if started_at == latest]
        if len(latest_entries) == 1:
            superseded.update(index for index, _ in entries if index != latest_entries[0])

    return [index for index in range(len(raw_checks)) if index not in superseded]


def _validate_ci_check(raw_check: Any, index: int) -> tuple[dict[str, Any], str, str | None, str | None, str | None]:
    if not isinstance(raw_check, dict):
        raise ReportError(f"GitHub PR status check {index} is not an object")
    name = raw_check.get("name") or raw_check.get("context")
    if not isinstance(name, str) or not name:
        raise ReportError(f"GitHub PR status check {index} has no valid name")
    conclusion = _check_text(raw_check, "conclusion")
    state = _check_text(raw_check, "state")
    status = _check_text(raw_check, "status")
    for key in ("detailsUrl", "targetUrl"):
        if raw_check.get(key) is not None and not isinstance(raw_check[key], str):
            raise ReportError(f"GitHub PR status check {index} has an invalid {key}")
    return raw_check, name, conclusion, state, status


def _normalize_ci_checks(raw_checks: list[Any]) -> tuple[list[dict[str, Any]], list[dict[str, Any]], int]:
    pending: list[dict[str, Any]] = []
    failed: list[dict[str, Any]] = []
    validated_checks = [
        _validate_ci_check(raw_check, index)
        for index, raw_check in enumerate(raw_checks, 1)
    ]
    coalesced_indexes = _coalesce_ci_checks(raw_checks)
    for original_index in coalesced_indexes:
        raw_check, name, conclusion, state, status = validated_checks[original_index]
        lifecycle_values = {value for value in (state, status) if value is not None}
        if lifecycle_values & PENDING_VALUES:
            category = "pending"
        elif state in FAILURE_VALUES or status in FAILURE_VALUES:
            category = "failed"
        elif state in SUCCESS_VALUES or status in SUCCESS_VALUES:
            category = "successful"
        elif lifecycle_values - KNOWN_LIFECYCLE_VALUES:
            category = "failed" if conclusion in FAILURE_VALUES else "pending"
        elif conclusion in FAILURE_VALUES:
            category = "failed"
        elif conclusion in SUCCESS_VALUES:
            category = "successful"
        else:
            category = "pending"
        if category == "successful":
            continue
        rendered = {
            "name": name,
            "status": status,
            "state": state,
            "conclusion": conclusion,
        }
        for key in ("detailsUrl", "targetUrl"):
            if raw_check.get(key) is not None:
                rendered["url"] = raw_check[key]
                break
        (failed if category == "failed" else pending).append(rendered)
    return pending, failed, len(raw_checks)


def _github_summary(payload: dict[str, Any]) -> dict[str, Any]:
    # Keep the raw status rollup out of the composed JSON; only actionable CI state is exposed.
    result = {
        key: payload[key]
        for key in (
            "number",
            "title",
            "headRefName",
            "headRefOid",
            "baseRefName",
            "mergeable",
            "mergeStateStatus",
            "isDraft",
            "url",
        )
        if key in payload
    }
    result["changedFiles"] = payload["changedFiles"]
    return result


def build_report(repo: str, pr_number: int) -> dict[str, Any]:
    if (
        not isinstance(repo, str)
        or not repo
        or repo.count("/") != 1
        or any(not part or any(character.isspace() for character in part) for part in repo.split("/"))
    ):
        raise ReportError("repo must be in OWNER/REPO form")
    if isinstance(pr_number, bool) or not isinstance(pr_number, int) or pr_number <= 0:
        raise ReportError("pr must be a positive integer")

    checkpoint_evidence = _run_json_provider(
        [sys.executable, str(CHECKPOINT_REPORTER), "--repo", repo, "--pr", str(pr_number), "--limit", "0", "--json"],
        "checkpoint reporter",
    )
    trigger_record = hosted_trigger_record_path(repo, pr_number)
    checker_command = [
        sys.executable,
        str(CODERABBIT_CHECKER),
        "--repo",
        repo,
        "--pr",
        str(pr_number),
    ]
    if trigger_record is not None:
        checker_command.extend(("--trigger-record", str(trigger_record)))
    checker_command.append("--json")
    checker_evidence = _run_json_provider(
        checker_command,
        "CodeRabbit checker",
        allow_nonzero=True,
    )
    github_evidence = _run_json_provider(
        ["gh", "pr", "view", str(pr_number), "--repo", repo, "--json", GH_PR_FIELDS],
        "GitHub PR",
    )

    checkpoints = _validate_checkpoint_evidence(checkpoint_evidence.payload)
    _validate_checker_evidence(checker_evidence.payload)
    _validate_checker_semantics(checker_evidence.payload, repo, pr_number)
    _validate_github_evidence(github_evidence.payload)
    trigger_state = checker_evidence.payload.get("trigger_state")
    if trigger_record is None:
        expected_checker_exit = 0 if checker_evidence.payload["ok"] else 1
    elif isinstance(trigger_state, dict):
        state_value = trigger_state.get("state")
        expected_checker_exit = (
            0
            if state_value == "completed"
            else 2
            if isinstance(state_value, str) and state_value in {"awaiting_response", "active"}
            else 1
        )
    else:
        expected_checker_exit = 1
    if checker_evidence.exit_code != expected_checker_exit:
        raise ReportError(
            "CodeRabbit checker exit status contradicts its evidence "
            f"(status {checker_evidence.exit_code}, expected {expected_checker_exit})"
        )
    if github_evidence.payload["number"] != pr_number:
        raise ReportError("GitHub PR number does not match the requested PR")
    current_merge_base = _current_merge_base(
        github_evidence.payload["baseRefOid"], github_evidence.payload["headRefOid"]
    )
    loc_metadata = _loc_metadata_freshness(
        github_evidence.payload.get("body") or "",
        github_evidence.payload["baseRefOid"],
        github_evidence.payload["headRefOid"],
        current_merge_base,
    )
    review_timeline = sorted(
        checkpoint_evidence.payload["timeline"],
        key=lambda event: _timestamp(event["created_at"], "checkpoint timeline timestamp"),
    )
    pending, failed, total_checks = _normalize_ci_checks(
        github_evidence.payload["statusCheckRollup"]
    )

    github_head = github_evidence.payload["headRefOid"]
    checker_head = checker_evidence.payload["head_sha"]
    head_matches = github_head.casefold() == checker_head.casefold()
    if not head_matches:
        raise ReportError("GitHub PR head does not match the CodeRabbit checker head")
    exact_head = head_matches and checker_evidence.payload["substantive_review_after_latest_commit"]
    merge_state = github_evidence.payload.get("mergeStateStatus")
    mergeable = github_evidence.payload.get("mergeable")
    merge_ready = merge_state.upper() == "CLEAN" and mergeable.upper() == "MERGEABLE"
    draft = github_evidence.payload["isDraft"]
    checker_fields_clear = not _checker_blockers(checker_evidence.payload)

    reasons = list(checker_evidence.payload["reasons"])
    if not exact_head:
        reasons.append("exact-head CodeRabbit review coverage is not established")
    if pending:
        reasons.append(f"{len(pending)} CI check(s) are pending")
    if failed:
        reasons.append(f"{len(failed)} CI check(s) are failed")
    if total_checks == 0:
        reasons.append("no CI checks were returned")
    if merge_state.upper() != "CLEAN":
        reasons.append("GitHub mergeStateStatus is not CLEAN")
    if mergeable.upper() != "MERGEABLE":
        reasons.append("GitHub mergeable is not MERGEABLE")
    if draft:
        reasons.append("PR is a draft")

    ready = bool(
        checker_fields_clear
        and checker_evidence.payload["ok"]
        and exact_head
        and not pending
        and not failed
        and total_checks > 0
        and merge_ready
        and not draft
    )
    return {
        "repo": repo,
        "pr_number": pr_number,
        "providers": {
            "checkpoint_reporter": {"exit_code": checkpoint_evidence.exit_code},
            "coderabbit_checker": {"exit_code": checker_evidence.exit_code},
            "github_pr": {"exit_code": github_evidence.exit_code},
        },
        "pull_request": _github_summary(github_evidence.payload),
        "checkpoint_report": checkpoint_evidence.payload,
        "coderabbit_review": checker_evidence.payload,
        "review_sequence": checkpoints,
        "review_timeline": review_timeline,
        "checkpoint_counts": _checkpoint_summaries(checkpoints),
        "hosted_trigger": _hosted_trigger_evidence(
            repo,
            pr_number,
            trigger_record is not None,
            checker_evidence.payload,
            github_evidence.payload["headRefOid"],
        ),
        "loc_metadata": loc_metadata,
        "threads": {
            "current": checker_evidence.payload["unresolved_non_outdated"],
            "outdated": checker_evidence.payload["unresolved_outdated"],
            "total": checker_evidence.payload["unresolved_total"],
        },
        "summary_only": {
            "outside_diff_actionable": checker_evidence.payload["outside_diff_actionable_comments"],
            "duplicate_actionable": checker_evidence.payload["duplicate_actionable_comments"],
        },
        "review_coverage": {
            "github_head": github_head,
            "checker_head": checker_head,
            "head_matches": head_matches,
            "exact_head": exact_head,
            "explicit_review_after_latest_commit": checker_evidence.payload[
                "explicit_review_after_latest_commit"
            ],
            "review_finished_after_latest_request": checker_evidence.payload[
                "review_finished_after_latest_request"
            ],
            "retrigger_review_allowed": checker_evidence.payload["retrigger_review_allowed"],
            "latest_request_rate_limited": checker_evidence.payload[
                "latest_review_request_rate_limited"
            ],
            "latest_request_noop": checker_evidence.payload["latest_review_request_noop"],
            "latest_request_failed": checker_evidence.payload["latest_review_request_failed"],
            "rate_limit_until": checker_evidence.payload["review_rate_limit_until"],
        },
        "ci": {
            "observed": total_checks,
            "pending": pending,
            "failed": failed,
        },
        "mergeability": {
            "mergeStateStatus": merge_state,
            "mergeable": mergeable,
            "clean": merge_ready,
        },
        "ready": ready,
        "verdict": "READY" if ready else "NOT READY",
        "reasons": reasons,
    }


def _review_line(checkpoint: dict[str, Any]) -> str:
    source = checkpoint["type"]
    correction = " correction" if checkpoint.get("correction") else ""
    details = [
        f"{source} R/A {checkpoint['raw_found']}/{checkpoint['accepted']}{correction}",
    ]
    if checkpoint.get("comment_id") is not None:
        details.append(f"comment {checkpoint['comment_id']}")
    if checkpoint.get("reviewed_sha"):
        details.append(f"head {checkpoint['reviewed_sha']}")
    if checkpoint.get("file_count") is not None:
        details.append(f"{checkpoint['file_count']} files")
    return f"  {format_human_timestamp(checkpoint['created_at'])} " + " · ".join(details)


def emit_text(report: dict[str, Any]) -> None:
    pull_request = report["pull_request"]
    title = _display(pull_request["title"])
    refs = f"{_display(pull_request['headRefName'])} -> {_display(pull_request['baseRefName'])}"
    changed_files = pull_request.get("changedFiles")
    file_suffix = f" · {changed_files} files" if changed_files is not None else ""
    print(f"PR #{report['pr_number']} — {title}")
    print(f"head/base: {refs} · {pull_request['headRefOid'][:12]}{file_suffix}")

    sequence = report["review_sequence"]
    print("reviews: " + ("none recorded" if not sequence else "chronological"))
    timeline = report["review_timeline"]
    if timeline:
        for event in timeline:
            if event["kind"] == "scope_change":
                comment = (
                    f" · comment {event['comment_id']}"
                    if event.get("comment_id") is not None
                    else ""
                )
                print(
                    f"  {format_human_timestamp(event['created_at'])} "
                    f"scope-change{comment}: {_display(event['description'])}"
                )
            else:
                print(_review_line(event))
    else:
        for checkpoint in sequence:
            print(_review_line(checkpoint))
    warnings = report["checkpoint_report"].get("warnings", [])
    for warning in warnings:
        print(f"warning: {_display(warning)}")
    unparsed_candidates = report["checkpoint_report"]["unparsed_candidates"]
    if unparsed_candidates:
        print(
            "warning: "
            f"{unparsed_candidates} checkpoint candidate(s) were not parsed; "
            "inspect the lower-level report"
        )

    threads = report["threads"]
    print(
        f"threads: current={threads['current']} · outdated={threads['outdated']} · total={threads['total']}"
    )
    summary_only = report["summary_only"]
    print(
        "summary-only: "
        f"outside-diff={summary_only['outside_diff_actionable']} · "
        f"duplicate={summary_only['duplicate_actionable']}"
    )
    coverage = report["review_coverage"]
    retrigger = "allowed" if coverage["retrigger_review_allowed"] else "not allowed"
    exact_head = "yes" if coverage["exact_head"] else "no"
    print(f"coverage: exact-head={exact_head} · retrigger={retrigger}")

    counts = report["checkpoint_counts"]
    hosted_counts = counts["by_type"]["Hosted"]
    cli_counts = counts["by_type"]["CLI"]
    print(
        "checkpoint counts: "
        f"Hosted={hosted_counts['count']} ({hosted_counts['raw_found']}/{hosted_counts['accepted']}) · "
        f"CLI={cli_counts['count']} ({cli_counts['raw_found']}/{cli_counts['accepted']})"
    )
    cli_zero = counts["cli_zero_streak"]
    print(
        "CLI zero-useful streak: "
        f"{cli_zero['count']} since "
        f"{format_human_timestamp(cli_zero['since']) if cli_zero['since'] else 'no recorded streak'}"
    )

    ci = report["ci"]
    ci_items = []
    for category in ("pending", "failed"):
        for check in ci[category]:
            if category == "pending":
                state = check.get("status") or check.get("state") or check.get("conclusion") or "unknown"
            else:
                state = check.get("conclusion") or check.get("state") or check.get("status") or "unknown"
            ci_items.append(f"{category} {_display(check['name'])} ({_display(state)})")
    print("CI: " + ("; ".join(ci_items) if ci_items else "no pending or failed checks"))
    mergeability = report["mergeability"]
    print(
        "mergeability: "
        f"mergeStateStatus={_display(mergeability['mergeStateStatus'])} · "
        f"mergeable={_display(mergeability['mergeable'])}"
    )
    print(f"verdict: {report['verdict']}")
    trigger = report["hosted_trigger"]
    classification = trigger.get("classification", "unavailable/no-request")
    request_details = [f"classification={_display(classification)}"]
    if trigger.get("state"):
        request_details.append(f"state={_display(trigger['state'])}")
    if trigger.get("latest_request_id") is not None:
        request_details.append(f"request={_display(trigger['latest_request_id'])}")
    elif trigger.get("trigger_comment_id") is not None:
        request_details.append(f"request={_display(trigger['trigger_comment_id'])}")
    if trigger.get("latest_request_created_at"):
        request_details.append(
            f"posted={format_human_timestamp(trigger['latest_request_created_at'])}"
        )
    print("hosted request: " + " · ".join(request_details))
    if trigger.get("warning"):
        print(f"warning: {_display(trigger['warning'])}")
    if trigger.get("available"):
        trigger_id = trigger.get("trigger_comment_id") or "-"
        trigger_head = str(trigger.get("head_sha") or "")[:12] or "-"
        trigger_details = [
            f"state={_display(trigger.get('state'))}",
            f"id={_display(trigger_id)}",
            f"head={_display(trigger_head)}",
        ]
        if trigger.get("state") != "completed" and trigger.get("reason"):
            trigger_details.append(f"reason={_display(trigger['reason'])}")
        print("trigger: " + " · ".join(trigger_details))
    else:
        print("trigger: unavailable (no canonical durable record)")
    taper = counts["taper_evidence"]
    print(
        "taper evidence: "
        f"Hosted true 0/0 streak={taper['hosted_zero_zero_streak']} · "
        f"raw>0/accepted=0 streak={taper['hosted_raw_positive_accepted_zero_streak']} · "
        f"corrections excluded={taper['hosted_correction_exclusions']} · "
        f"observed raw/accepted={taper['hosted_raw_found']}/{taper['hosted_accepted']}"
    )
    loc = report["loc_metadata"]
    loc_details = []
    if loc.get("merge_base_checked") is False:
        loc_details.append("merge-base not checked")
    if loc.get("reason"):
        loc_details.append(_display(loc["reason"]))
    print(
        "LOC metadata: "
        f"{_display(loc.get('status'))}"
        + (f" ({'; '.join(loc_details)})" if loc_details else "")
    )
    for reason in report["reasons"]:
        print(f"reason: {_display(reason)}")


def main() -> int:
    args = parse_args()
    try:
        report = build_report(args.repo, args.pr)
    except ReportError as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 1
    if args.json:
        print(json.dumps(report, indent=2, sort_keys=True))
    else:
        emit_text(report)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
