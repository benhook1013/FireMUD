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

ROOT = Path(__file__).resolve().parents[2]
CHECKPOINT_REPORTER = ROOT / "dev-tools" / "validation" / "report-pr-review-checkpoints.py"
CODERABBIT_CHECKER = ROOT / "dev-tools" / "validation" / "check-coderabbit-review.py"
PROVIDER_TIMEOUT_SECONDS = 180
HUMAN_TIME_ZONE = ZoneInfo("Pacific/Auckland")
TERMINAL_CONTROLS = re.compile(r"[\x00-\x1f\x7f-\x9f]+")
EXACT_SHA = re.compile(r"^[0-9a-fA-F]{40}$")
REVIEWED_SHA = re.compile(r"^[0-9a-fA-F]{7,40}$")
RUN_ID = re.compile(r"^run\.[A-Za-z0-9]{1,32}$")

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

GH_PR_IDENTITY_FIELDS = "number,title,headRefName,headRefOid,baseRefName,changedFiles"
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


def _display(value: Any) -> str:
    """Render provider-controlled text without allowing it to alter report layout."""

    text = TERMINAL_CONTROLS.sub(" ", str(value))
    return " ".join(text.split()) or "-"


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
    for key in ("title", "headRefName", "headRefOid", "baseRefName"):
        value = _require(payload, key, str, name)
        if not value:
            raise ReportError(f"{name} evidence has an empty {key}")
    if not EXACT_SHA.fullmatch(payload["headRefOid"]):
        raise ReportError(f"{name} evidence headRefOid must be exactly 40 hexadecimal characters")
    _require(payload, "statusCheckRollup", list, name)
    mergeable = _require(payload, "mergeable", str, name)
    if mergeable.upper() not in MERGEABLE_VALUES:
        raise ReportError(f"{name} evidence has an invalid mergeable value")
    merge_state = _require(payload, "mergeStateStatus", str, name)
    if merge_state.upper() not in MERGE_STATE_VALUES:
        raise ReportError(f"{name} evidence has an invalid mergeStateStatus value")
    _nonnegative_int(payload.get("changedFiles"), f"{name} changedFiles")
    _require(payload, "isDraft", bool, name)


def _check_text(check: dict[str, Any], key: str) -> str | None:
    value = check.get(key)
    if value is None:
        return None
    if not isinstance(value, str):
        raise ReportError(f"GitHub PR status check has an invalid {key}")
    return value.upper()


def _normalize_ci_checks(raw_checks: list[Any]) -> tuple[list[dict[str, Any]], list[dict[str, Any]], int]:
    pending: list[dict[str, Any]] = []
    failed: list[dict[str, Any]] = []
    for index, raw_check in enumerate(raw_checks, 1):
        if not isinstance(raw_check, dict):
            raise ReportError(f"GitHub PR status check {index} is not an object")
        name = raw_check.get("name") or raw_check.get("context")
        if not isinstance(name, str) or not name:
            raise ReportError(f"GitHub PR status check {index} has no valid name")
        conclusion = _check_text(raw_check, "conclusion")
        state = _check_text(raw_check, "state")
        status = _check_text(raw_check, "status")
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
                if not isinstance(raw_check[key], str):
                    raise ReportError(f"GitHub PR status check {index} has an invalid {key}")
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
    checker_evidence = _run_json_provider(
        [sys.executable, str(CODERABBIT_CHECKER), "--repo", repo, "--pr", str(pr_number), "--json"],
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
    if checker_evidence.exit_code != (0 if checker_evidence.payload["ok"] else 1):
        raise ReportError(
            "CodeRabbit checker exit status contradicts its ok value "
            f"(status {checker_evidence.exit_code}, ok={str(checker_evidence.payload['ok']).lower()})"
        )
    if github_evidence.payload["number"] != pr_number:
        raise ReportError("GitHub PR number does not match the requested PR")
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
