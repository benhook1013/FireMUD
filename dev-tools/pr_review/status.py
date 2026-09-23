"""Fail-closed, read-only pull-request status aggregation.

The report remains an evidence composition: GitHub is fetched through the
paginated adapter, historical checkpoint comments are parsed by ``evidence``,
and private trigger/capture records are only used when their identity matches
the live pull request.  No live observation is persisted here.
"""

from __future__ import annotations

import json
import os
import re
import subprocess
from collections import defaultdict
from collections.abc import Mapping
from datetime import datetime
from typing import Any

try:  # package import (the normal controller path)
    from . import evidence, github, hosted
except ImportError:  # direct loading by repository contract tests
    import evidence  # type: ignore[no-redef]
    import github  # type: ignore[no-redef]
    import hosted  # type: ignore[no-redef]


EXACT_SHA = re.compile(r"^[0-9a-fA-F]{40}$")
REVIEWED_SHA = re.compile(r"^[0-9a-fA-F]{7,40}$")
SUCCESS = {"SUCCESS", "SKIPPED", "NEUTRAL"}
FAILURE = {"ACTION_REQUIRED", "CANCELLED", "ERROR", "FAILURE", "STARTUP_FAILURE", "STALE", "TIMED_OUT"}
PENDING = {"EXPECTED", "IN_PROGRESS", "PENDING", "QUEUED", "REQUESTED", "RUNNING", "WAITING"}
KNOWN = SUCCESS | FAILURE | PENDING | {"COMPLETED"}
MERGEABLE = {"MERGEABLE", "CONFLICTING", "UNKNOWN"}
MERGE_STATE = {"BEHIND", "BLOCKED", "CLEAN", "DIRTY", "DRAFT", "HAS_HOOKS", "UNKNOWN", "UNSTABLE"}
LOC_METADATA = re.compile(r"^<!-- firemud:cloc-report:metadata (?P<payload>\{.*\}) -->$")


class StatusError(ValueError):
    """Raised when required live or historical evidence is malformed."""


ReportError = StatusError  # compatibility name used by the retired reporter


def _timestamp(value: Any, field: str) -> datetime:
    if not isinstance(value, str) or not value:
        raise StatusError(f"{field} must be a non-empty timestamp string")
    try:
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError as exc:
        raise StatusError(f"{field} is not a valid timestamp") from exc
    if parsed.tzinfo is None:
        raise StatusError(f"{field} has no timezone")
    return parsed


def _nonnegative(value: Any, field: str) -> int:
    if isinstance(value, bool) or not isinstance(value, int) or value < 0:
        raise StatusError(f"{field} must be a non-negative integer")
    return value


def _sha(value: Any, field: str, *, short: bool = False) -> str:
    pattern = REVIEWED_SHA if short else EXACT_SHA
    if not isinstance(value, str) or not pattern.fullmatch(value):
        raise StatusError(f"{field} has an invalid commit SHA")
    return value


def _check_checkpoint(item: Any, index: int) -> dict[str, Any]:
    if not isinstance(item, dict):
        raise StatusError(f"checkpoint {index} is not an object")
    required = ("comment_id", "created_at", "type", "raw_found", "accepted", "reviewed_sha", "file_count", "correction")
    missing = [key for key in required if key not in item]
    if missing:
        raise StatusError(f"checkpoint {index} is missing {missing[0]}")
    if item["type"] not in {"Hosted", "CLI"}:
        raise StatusError(f"checkpoint {index} has an invalid type")
    _timestamp(item["created_at"], f"checkpoint {index} created_at")
    raw = _nonnegative(item["raw_found"], f"checkpoint {index} raw_found")
    accepted = _nonnegative(item["accepted"], f"checkpoint {index} accepted")
    if accepted > raw:
        raise StatusError(f"checkpoint {index} accepted exceeds raw_found")
    if item["comment_id"] is not None and (_nonnegative(item["comment_id"], f"checkpoint {index} comment_id") == 0):
        raise StatusError(f"checkpoint {index} comment_id must be positive")
    if item["reviewed_sha"] is not None:
        _sha(item["reviewed_sha"], f"checkpoint {index} reviewed_sha", short=True)
    if item["file_count"] is not None:
        _nonnegative(item["file_count"], f"checkpoint {index} file_count")
    if not isinstance(item["correction"], bool):
        raise StatusError(f"checkpoint {index} correction must be boolean")
    for key in ("updated_at",):
        if item.get(key) is not None:
            _timestamp(item[key], f"checkpoint {index} {key}")
    return item


def _validate_checkpoint_report(payload: Mapping[str, Any]) -> list[dict[str, Any]]:
    checkpoints = payload.get("checkpoints")
    timeline = payload.get("timeline")
    warnings = payload.get("warnings")
    if not isinstance(checkpoints, list) or not isinstance(timeline, list) or not isinstance(warnings, list):
        raise StatusError("checkpoint evidence must contain checkpoints, timeline, and warnings")
    if any(not isinstance(value, str) for value in warnings):
        raise StatusError("checkpoint evidence has an invalid warning")
    validated = [_check_checkpoint(value, index) for index, value in enumerate(checkpoints, 1)]
    if any(not isinstance(value, dict) for value in timeline):
        raise StatusError("checkpoint timeline contains a non-object event")
    for index, event in enumerate(timeline, 1):
        if event.get("kind") not in {"checkpoint", "scope_change"}:
            raise StatusError(f"timeline event {index} has an invalid kind")
        _timestamp(event.get("created_at"), f"timeline event {index} created_at")
        if event["kind"] == "checkpoint":
            _check_checkpoint(event, index)
        elif not isinstance(event.get("description"), str):
            raise StatusError(f"scope-change event {index} has no description")
    return sorted(validated, key=lambda item: _timestamp(item["created_at"], "checkpoint timestamp"))


def _validate_github(pr: Mapping[str, Any], number: int) -> None:
    if pr.get("number") != number:
        raise StatusError("GitHub PR number does not match the requested PR")
    for key in ("title", "headRefName", "baseRefName", "headRefOid", "baseRefOid"):
        if not isinstance(pr.get(key), str) or not pr[key]:
            raise StatusError(f"GitHub PR evidence has an invalid {key}")
    _sha(pr["headRefOid"], "GitHub head")
    _sha(pr["baseRefOid"], "GitHub base")
    if not isinstance(pr.get("statusCheckRollup"), list):
        raise StatusError("GitHub PR evidence has no statusCheckRollup")
    if pr.get("mergeable", "").upper() not in MERGEABLE:
        raise StatusError("GitHub PR evidence has an invalid mergeable value")
    if pr.get("mergeStateStatus", "").upper() not in MERGE_STATE:
        raise StatusError("GitHub PR evidence has an invalid mergeStateStatus value")
    _nonnegative(pr.get("changedFiles"), "GitHub changedFiles")
    if not isinstance(pr.get("isDraft"), bool):
        raise StatusError("GitHub PR evidence has an invalid isDraft")
    if pr.get("body") is not None and not isinstance(pr["body"], str):
        raise StatusError("GitHub PR body must be a string or null")


def _check_text(check: Mapping[str, Any], key: str) -> str | None:
    value = check.get(key)
    if value is not None and not isinstance(value, str):
        raise StatusError(f"GitHub status check has an invalid {key}")
    return value.upper() if isinstance(value, str) else None


def normalize_checks(raw: list[Any]) -> tuple[list[dict[str, Any]], list[dict[str, Any]], int]:
    """Return pending and failed checks, coalescing only safely ordered CheckRuns."""
    validated: list[tuple[dict[str, Any], str, str | None, str | None, str | None]] = []
    groups: defaultdict[tuple[str, str], list[tuple[int, datetime]]] = defaultdict(list)
    blocked: set[tuple[str, str]] = set()
    for index, value in enumerate(raw):
        if not isinstance(value, dict):
            raise StatusError(f"GitHub status check {index + 1} is not an object")
        name = value.get("name", value.get("context"))
        if not isinstance(name, str) or not name:
            raise StatusError(f"GitHub status check {index + 1} has no valid name")
        conclusion = _check_text(value, "conclusion")
        state = _check_text(value, "state")
        status = _check_text(value, "status")
        for key in ("detailsUrl", "targetUrl"):
            if value.get(key) is not None and not isinstance(value[key], str):
                raise StatusError(f"GitHub status check {index + 1} has an invalid {key}")
        validated.append((value, name, conclusion, state, status))
        if value.get("__typename") == "CheckRun" and value.get("workflowName"):
            identity = (value["workflowName"], name)
            started = value.get("startedAt")
            if not isinstance(started, str):
                blocked.add(identity)
            else:
                try:
                    groups[identity].append((index, _timestamp(started, "CheckRun startedAt")))
                except StatusError:
                    blocked.add(identity)
    superseded: set[int] = set()
    for identity, entries in groups.items():
        if identity in blocked:
            continue
        latest = max(value for _, value in entries)
        selected = [index for index, value in entries if value == latest]
        if len(selected) == 1:
            superseded.update(index for index, _ in entries if index != selected[0])
    pending: list[dict[str, Any]] = []
    failed: list[dict[str, Any]] = []
    for index, (value, name, conclusion, state, status) in enumerate(validated):
        if index in superseded:
            continue
        lifecycle = {candidate for candidate in (state, status) if candidate is not None}
        if lifecycle & PENDING:
            category = "pending"
        elif lifecycle & FAILURE or state in FAILURE or status in FAILURE or conclusion in FAILURE:
            category = "failed"
        elif lifecycle & SUCCESS or state in SUCCESS or status in SUCCESS or conclusion in SUCCESS:
            continue
        else:
            category = "pending"
        rendered = {"name": name, "status": status, "state": state, "conclusion": conclusion}
        rendered["url"] = next((value[key] for key in ("detailsUrl", "targetUrl") if value.get(key)), None)
        (pending if category == "pending" else failed).append(rendered)
    return pending, failed, len(raw)


def _loc_status(pr: Mapping[str, Any]) -> dict[str, Any]:
    body = pr.get("body") or ""
    start = "<!-- firemud:cloc-report:start -->"
    end = "<!-- firemud:cloc-report:end -->"
    markers = [
        (offset, match)
        for offset, line in enumerate(body.splitlines())
        if (match := LOC_METADATA.fullmatch(line.strip())) is not None
    ]
    if not markers:
        return {"status": "missing", "merge_base_checked": False, "reason": "LOC metadata is absent"}
    if len(markers) != 1 or body.count(start) != 1 or body.count(end) != 1:
        return {
            "status": "ambiguous",
            "merge_base_checked": False,
            "reason": "LOC report markers are duplicated or incomplete",
        }
    marker_line, match = markers[0]
    lines = [line.strip() for line in body.splitlines()]
    if not (lines.index(start) < marker_line < lines.index(end)):
        return {
            "status": "ambiguous",
            "merge_base_checked": False,
            "reason": "LOC metadata is outside the marked report block",
        }
    try:
        metadata = json.loads(match.group("payload"))
    except json.JSONDecodeError:
        return {"status": "ambiguous", "merge_base_checked": False, "reason": "LOC metadata is malformed"}
    if not isinstance(metadata, dict):
        return {"status": "ambiguous", "merge_base_checked": False, "reason": "LOC metadata is not an object"}
    for key in ("base_oid", "head_oid", "merge_base"):
        if not isinstance(metadata.get(key), str) or not EXACT_SHA.fullmatch(metadata[key]):
            return {"status": "ambiguous", "merge_base_checked": False, "reason": f"LOC metadata has no exact {key}"}
    classifier = metadata.get("classifier_sha256")
    if not isinstance(classifier, str) or not re.fullmatch(r"[0-9a-fA-F]{64}", classifier):
        return {
            "status": "ambiguous",
            "merge_base_checked": False,
            "reason": "LOC metadata has an invalid classifier digest",
        }
    expected = {"base_oid": pr["baseRefOid"], "head_oid": pr["headRefOid"]}
    if any(metadata.get(key, "").casefold() != value.casefold() for key, value in expected.items()):
        return {"status": "stale", "merge_base_checked": False, "reason": "LOC metadata does not match the current PR"}
    merge_base = metadata.get("merge_base")
    if not isinstance(merge_base, str) or not EXACT_SHA.fullmatch(merge_base):
        return {"status": "invalid", "merge_base_checked": False, "reason": "LOC merge base is missing or malformed"}
    try:
        result = subprocess.run(
            ["git", "merge-base", pr["baseRefOid"], pr["headRefOid"]],
            check=True,
            capture_output=True,
            text=True,
            timeout=30,
        )
    except (OSError, subprocess.SubprocessError) as exc:
        return {
            "status": "unverified",
            "merge_base_checked": False,
            "reason": f"LOC merge base could not be checked: {exc}",
        }
    actual = result.stdout.strip()
    if actual.casefold() != merge_base.casefold():
        return {"status": "stale", "merge_base_checked": True, "reason": "LOC merge base does not match the current PR"}
    return {
        "status": "fresh",
        "merge_base_checked": True,
        "merge_base": merge_base,
        "classifier_sha256": classifier,
    }


def _counts(checkpoints: list[dict[str, Any]]) -> dict[str, Any]:
    by_type: dict[str, dict[str, Any]] = {
        kind: {"count": 0, "raw_found": 0, "accepted": 0} for kind in ("Hosted", "CLI")
    }
    for item in checkpoints:
        bucket = by_type[item["type"]]
        bucket["count"] += 1
        bucket["raw_found"] += item["raw_found"]
        bucket["accepted"] += item["accepted"]
    cli_streak = 0
    since = None
    last_accepted = None
    for item in reversed(checkpoints):
        if item["type"] != "CLI":
            continue
        if item["correction"]:
            continue
        if item["accepted"]:
            last_accepted = item
            break
        cli_streak += 1
        since = item["created_at"]
    hosted = [item for item in checkpoints if item["type"] == "Hosted" and not item["correction"]]
    raw_positive = 0
    zero_zero = 0
    for item in reversed(hosted):
        if item["accepted"]:
            break
        if item["raw_found"] == 0:
            zero_zero += 1
        elif raw_positive == zero_zero:
            raw_positive += 1
    return {
        "by_type": by_type,
        "cli_zero_streak": {"count": cli_streak, "since": since, "last_accepted_finding": last_accepted},
        "taper_evidence": {
            "hosted_zero_zero_streak": zero_zero,
            "hosted_raw_positive_accepted_zero_streak": raw_positive,
            "hosted_correction_exclusions": len(
                [item for item in checkpoints if item["type"] == "Hosted" and item["correction"]]
            ),
            "hosted_completed_zero_zero_observed": zero_zero,
            "hosted_raw_found": sum(item["raw_found"] for item in hosted),
            "hosted_accepted": sum(item["accepted"] for item in hosted),
        },
    }


def _thread_summary(payload: Mapping[str, Any]) -> dict[str, int]:
    nodes = (
        ((payload.get("data") or {}).get("repository") or {})
        .get("pullRequest", {})
        .get("reviewThreads", {})
        .get("nodes", [])
    )
    current = outdated = 0
    if not isinstance(nodes, list):
        raise StatusError("GitHub reviewThreads evidence is malformed")
    for thread in nodes:
        if (
            not isinstance(thread, dict)
            or not isinstance(thread.get("isResolved"), bool)
            or not isinstance(thread.get("isOutdated"), bool)
        ):
            raise StatusError("GitHub review thread evidence is malformed")
        if not thread["isResolved"]:
            if thread["isOutdated"]:
                outdated += 1
            else:
                current += 1
    return {"current": current, "outdated": outdated, "total": current + outdated}


def _summary_sections(body: str) -> list[dict[str, str | int]]:
    """Extract canonical summary-only counts from a substantive review body."""

    try:
        outside, duplicate = evidence.summary_action_counts(body)
    except evidence.EvidenceError as exc:
        raise StatusError(str(exc)) from exc
    findings: list[dict[str, str | int]] = []
    if outside:
        findings.append({"kind": "outside_diff", "count": outside})
    if duplicate:
        findings.append({"kind": "duplicate", "count": duplicate})
    return findings


def _summary_evidence(payload: Mapping[str, Any], current_head: str) -> dict[str, Any]:
    """Select and inspect the latest CodeRabbit summary attributable to this head."""
    pr = ((payload.get("data") or {}).get("repository") or {}).get("pullRequest", {})
    reviews = (pr.get("reviews") or {}).get("nodes")
    comments = (pr.get("comments") or {}).get("nodes")
    if not isinstance(reviews, list) or not isinstance(comments, list):
        raise StatusError("GitHub review summary evidence is malformed or missing")

    candidates: list[tuple[datetime, int, str, str, int | None]] = []
    for index, review in enumerate(reviews, 1):
        if not isinstance(review, dict):
            raise StatusError(f"GitHub review {index} is not an object")
        author = review.get("author") or {}
        if not isinstance(author, dict):
            raise StatusError(f"GitHub review {index} has a malformed author")
        if not github.is_coderabbit_login(author.get("login")) or review.get("state") == "DISMISSED":
            continue
        body = review.get("body")
        commit = (review.get("commit") or {}).get("oid")
        submitted_at = review.get("submittedAt")
        if body is None:
            body = ""
        if not isinstance(body, str):
            raise StatusError(f"CodeRabbit review {index} body is malformed")
        if not hosted._substantive(body):
            continue
        if not isinstance(commit, str) or not EXACT_SHA.fullmatch(commit):
            if _summary_sections(body):
                raise StatusError(f"CodeRabbit review {index} summary has no exact commit identity")
            continue
        if commit.casefold() != current_head.casefold():
            continue
        submitted = _timestamp(submitted_at, f"CodeRabbit review {index} submittedAt")
        candidates.append((submitted, index, body, "review", github.immutable_database_id(review)))

    for index, comment in enumerate(comments, 1):
        if not isinstance(comment, dict):
            raise StatusError(f"GitHub comment {index} is not an object")
        author = comment.get("author") or {}
        if not isinstance(author, dict):
            raise StatusError(f"GitHub comment {index} has a malformed author")
        if not github.is_coderabbit_login(author.get("login")):
            continue
        body = comment.get("body")
        if not isinstance(body, str):
            raise StatusError(f"CodeRabbit comment {index} body is malformed")
        if not hosted._substantive(body) or not hosted._matches_head(body, current_head):
            continue
        # A later exact-head summary with no duplicate/outside-diff section
        # supersedes an older summary that did report one.
        created = _timestamp(
            comment.get("updatedAt") or comment.get("createdAt"), f"CodeRabbit comment {index} timestamp"
        )
        candidates.append((created, index, body, "comment", github.immutable_database_id(comment)))

    if not candidates:
        return {"status": "missing", "head_sha": current_head, "findings": []}
    latest_at = max(candidate[0] for candidate in candidates)
    latest = [candidate for candidate in candidates if candidate[0] == latest_at]
    if len(latest) != 1:
        raise StatusError("latest CodeRabbit summary for the current head is ambiguous")
    _, _, body, source, identity = latest[0]
    return {
        "status": "current",
        "head_sha": current_head,
        "source": source,
        "identity": identity,
        "submitted_at": latest_at.isoformat(),
        "findings": _summary_sections(body),
    }


def _historical_comments(payload: Mapping[str, Any]) -> list[dict[str, Any]]:
    """Convert the paginated GraphQL comment shape to the evidence-reader shape."""
    pr = ((payload.get("data") or {}).get("repository") or {}).get("pullRequest", {})
    nodes = (pr.get("comments") or {}).get("nodes", [])
    if not isinstance(nodes, list):
        raise StatusError("GitHub comments evidence is malformed")
    result: list[dict[str, Any]] = []
    for index, item in enumerate(nodes, 1):
        if not isinstance(item, dict):
            raise StatusError(f"GitHub comment {index} is not an object")
        comment_id = github.immutable_database_id(item)
        if comment_id is None:
            raise StatusError(f"GitHub comment {index} has no immutable ID")
        created = item.get("createdAt")
        if not isinstance(created, str):
            raise StatusError(f"GitHub comment {index} has no creation timestamp")
        author = item.get("author") or {}
        result.append(
            {
                "id": comment_id,
                "body": item.get("body", ""),
                "created_at": created,
                "updated_at": item.get("updatedAt") or created,
                "author": {"login": author.get("login")} if isinstance(author, dict) else {},
                "url": item.get("url"),
            }
        )
    return result


def _repo_name(repo: str | None) -> str:
    selected = repo or os.environ.get("GH_REPO") or os.environ.get("GITHUB_REPOSITORY")
    if selected:
        github.parse_repo(selected)
        return selected
    try:
        result = subprocess.run(
            ["gh", "repo", "view", "--json", "nameWithOwner", "--jq", ".nameWithOwner"],
            check=True,
            capture_output=True,
            text=True,
            timeout=30,
        )
    except (OSError, subprocess.SubprocessError) as exc:
        raise StatusError(f"repository identity is unavailable: {exc}") from exc
    selected = result.stdout.strip()
    github.parse_repo(selected)
    return selected


def _trigger(repo: str, number: int, payload: dict[str, Any], head: str) -> dict[str, Any]:
    try:
        # ``hosted`` also supports direct script loading and historically imports
        # ``evidence`` as a top-level module.  Supplying the resolved common dir
        # keeps the package path hermetic without mutating import state.
        common = evidence.git_common_dir()
        paths = hosted.trigger_record_paths(repo, number, common)
    except (OSError, ValueError, RuntimeError, ModuleNotFoundError):
        paths = []
    if not paths:
        return {"available": False, "classification": "unavailable/no-request", "state": "unavailable"}
    try:
        record = hosted.load_trigger_record(paths[0], repo, number)
        state = hosted.trigger_state(repo, number, payload, record, paths[0])
        value = state.as_dict()
        if value.get("current_head_sha", "").casefold() != head.casefold():
            value.update(
                {"classification": "ambiguous/overlapping", "state": "ambiguous", "validation_outcome": "invalid"}
            )
        else:
            value.update({"available": True, "record_available": True, "validation_outcome": "valid"})
        return value
    except (OSError, ValueError, KeyError, TypeError) as exc:
        return {
            "available": True,
            "record_available": True,
            "classification": "malformed/unknown",
            "state": "ambiguous",
            "validation_outcome": "invalid",
            "reason": str(exc),
        }


def build_report(
    repo: str,
    pr_number: int,
    *,
    pull_request_payload: dict[str, Any] | None = None,
    checkpoint_payload: Mapping[str, Any] | None = None,
) -> dict[str, Any]:
    repo = _repo_name(repo)
    if isinstance(pr_number, bool) or not isinstance(pr_number, int) or pr_number <= 0:
        raise StatusError("pr must be a positive integer")
    raw_payload = pull_request_payload or github.fetch_pull_request(repo, pr_number)
    try:
        pr = raw_payload["data"]["repository"]["pullRequest"]
    except (KeyError, TypeError) as exc:
        raise StatusError("GitHub response has no pull request") from exc
    if pull_request_payload is None:
        # Review conversations require GraphQL pagination, while ``gh pr view`` is
        # the stable compact source for the PR, CI, mergeability, and LOC fields.
        pr.update(github.fetch_pr_metadata(repo, pr_number))
    _validate_github(pr, pr_number)
    if checkpoint_payload is None:
        try:
            checkpoints_payload = evidence.collect_evidence(_historical_comments(raw_payload))
        except (evidence.EvidenceError, KeyError, TypeError) as exc:
            raise StatusError(f"historical checkpoint evidence is unusable: {exc}") from exc
    else:
        checkpoints_payload = checkpoint_payload
    checkpoints = _validate_checkpoint_report(checkpoints_payload)
    pending, failed, observed = normalize_checks(pr["statusCheckRollup"])
    threads = _thread_summary(raw_payload)
    summary_evidence = _summary_evidence(raw_payload, pr["headRefOid"])
    loc = _loc_status(pr)
    reasons: list[str] = []
    if threads["total"]:
        reasons.append(f"{threads['total']} unresolved review thread(s)")
    if summary_evidence["findings"]:
        reasons.append(
            f"CodeRabbit summary has {len(summary_evidence['findings'])} actionable duplicate/outside-diff finding(s)"
        )
    if pending:
        reasons.append(f"{len(pending)} CI check(s) are pending")
    if failed:
        reasons.append(f"{len(failed)} CI check(s) are failed")
    if observed == 0:
        reasons.append("no CI checks were returned")
    if pr["mergeStateStatus"].upper() != "CLEAN":
        reasons.append("GitHub mergeStateStatus is not CLEAN")
    if pr["mergeable"].upper() != "MERGEABLE":
        reasons.append("GitHub mergeable is not MERGEABLE")
    if pr["isDraft"]:
        reasons.append("PR is a draft")
    if loc["status"] != "fresh":
        reasons.append(f"LOC metadata is {loc['status']}")
    return {
        "repo": repo,
        "pr_number": pr_number,
        "pull_request": {
            key: pr[key]
            for key in (
                "number",
                "title",
                "headRefName",
                "headRefOid",
                "baseRefName",
                "baseRefOid",
                "changedFiles",
                "mergeable",
                "mergeStateStatus",
                "isDraft",
                "url",
            )
            if key in pr
        },
        "review_sequence": checkpoints,
        "review_timeline": sorted(
            checkpoints_payload.get("timeline", []),
            key=lambda item: _timestamp(item["created_at"], "timeline timestamp"),
        ),
        "checkpoint_report": dict(checkpoints_payload),
        "checkpoint_counts": _counts(checkpoints),
        "threads": threads,
        "coderabbit_summary": summary_evidence,
        "ci": {"observed": observed, "pending": pending, "failed": failed},
        "loc_metadata": loc,
        "hosted_trigger": _trigger(repo, pr_number, raw_payload, pr["headRefOid"]),
        "mergeability": {
            "mergeStateStatus": pr["mergeStateStatus"],
            "mergeable": pr["mergeable"],
            "clean": not reasons,
        },
        "ready": not reasons,
        "verdict": "READY" if not reasons else "NOT READY",
        "reasons": reasons,
    }


def status(pr: int | None = None, as_json: bool = False, *, repo: str | None = None) -> dict[str, Any]:
    """Return the current report; ``as_json`` controls the CLI rendering form."""
    if pr is None:
        raise StatusError("status requires --pr until stack live-target integration is available")
    report = build_report(_repo_name(repo), pr)
    return report


def emit_text(report: Mapping[str, Any]) -> str:
    """Render a compact human report from the same structure emitted as JSON."""
    pr = report["pull_request"]
    lines = [
        f"PR #{report['pr_number']} — {pr['title']}",
        f"head/base: {pr['headRefName']} -> {pr['baseRefName']} · {pr['headRefOid'][:12]} · {pr['changedFiles']} files",
        f"threads: current={report['threads']['current']} · outdated={report['threads']['outdated']} · total={report['threads']['total']}",
        f"CI: pending={len(report['ci']['pending'])} · failed={len(report['ci']['failed'])} · observed={report['ci']['observed']}",
        f"verdict: {report['verdict']}",
    ]
    for reason in report["reasons"]:
        lines.append(f"reason: {reason}")
    return "\n".join(lines)


__all__ = ["ReportError", "StatusError", "build_report", "emit_text", "normalize_checks", "status"]
