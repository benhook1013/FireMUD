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
from datetime import datetime, timezone
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
REVIEW_DECISIONS = {"", "APPROVED", "CHANGES_REQUESTED", "COMMENTED", "REVIEW_REQUIRED"}
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
    if pr.get("reviewDecision") is not None and (
        not isinstance(pr["reviewDecision"], str) or pr["reviewDecision"].upper() not in REVIEW_DECISIONS
    ):
        raise StatusError("GitHub PR evidence has an invalid reviewDecision")
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


def _check_outcome(value: Mapping[str, Any]) -> str:
    lifecycle = {
        candidate
        for key in ("state", "status", "conclusion")
        for candidate in [_check_text(value, key)]
        if candidate is not None
    }
    if lifecycle & PENDING:
        return "PENDING"
    if lifecycle & FAILURE:
        return "FAILURE"
    if lifecycle & SUCCESS:
        return "SUCCESS"
    return "UNKNOWN"


def _app_identity(value: Mapping[str, Any]) -> dict[str, Any] | None:
    app = value.get("app")
    if app is None:
        app = value.get("creator")
    if not isinstance(app, Mapping):
        return None
    app_id = app.get("id", app.get("databaseId"))
    if isinstance(app_id, bool) or (app_id is not None and not isinstance(app_id, (int, str))):
        return None
    if isinstance(app_id, str) and app_id.isdigit():
        app_id = int(app_id)
    identity: dict[str, Any] = {}
    if app_id is not None:
        identity["id"] = app_id
    for key in ("slug", "name", "login"):
        if isinstance(app.get(key), str) and app[key]:
            identity[key] = app[key]
    return identity or None


def _check_timestamp(value: Mapping[str, Any], index: int) -> tuple[str | None, datetime | None]:
    for key in (
        "completed_at",
        "completedAt",
        "updated_at",
        "updatedAt",
        "started_at",
        "startedAt",
        "created_at",
        "createdAt",
    ):
        candidate = value.get(key)
        if candidate is None:
            continue
        if not isinstance(candidate, str):
            raise StatusError(f"GitHub status check {index} has an invalid {key}")
        return candidate, _timestamp(candidate, f"GitHub status check {index} {key}")
    return None, None


def _inventory_entries(raw: list[Any], current_head: str) -> list[dict[str, Any]]:
    entries: list[dict[str, Any]] = []
    for index, value in enumerate(raw, 1):
        if not isinstance(value, Mapping):
            raise StatusError(f"GitHub status check {index} is not an object")
        name = value.get("name", value.get("context"))
        if not isinstance(name, str) or not name:
            raise StatusError(f"GitHub status check {index} has no valid name")
        # REST commit statuses are fetched against the exact requested commit;
        # their endpoint does not repeat the SHA, so the adapter marks it.
        head = value.get("head_sha", value.get("headSha"))
        if head is not None and (not isinstance(head, str) or not EXACT_SHA.fullmatch(head)):
            raise StatusError(f"GitHub status check {index} has an invalid head SHA")
        timestamp, parsed_timestamp = _check_timestamp(value, index)
        app = _app_identity(value)
        entry = {
            "name": name,
            "kind": value.get("__typename", "CheckRun" if "conclusion" in value else "StatusContext"),
            "status": _check_text(value, "status"),
            "state": _check_text(value, "state"),
            "conclusion": _check_text(value, "conclusion"),
            "head_sha": head,
            "app": app,
            "timestamp": timestamp,
            "outcome": _check_outcome(value),
            "url": next((value[key] for key in ("details_url", "detailsUrl", "target_url", "targetUrl") if value.get(key)), None),
            "_timestamp": parsed_timestamp,
            "_index": index,
        }
        entry["exact_head"] = isinstance(head, str) and head.casefold() == current_head.casefold()
        entries.append(entry)
    return entries


def _inventory_report(
    raw: list[Any],
    current_head: str,
    *,
    aggregate: Any = None,
) -> dict[str, Any]:
    pending, failed, observed = normalize_checks(raw)
    entries = _inventory_entries(raw, current_head)
    if isinstance(aggregate, Mapping):
        aggregate = aggregate.get("state")
    if isinstance(aggregate, str) and aggregate:
        aggregate_state = aggregate.upper()
        aggregate_source = "github"
    else:
        effective: dict[tuple[str, str], dict[str, Any]] = {}

        def order(entry: Mapping[str, Any]) -> tuple[bool, datetime, int]:
            return (
                entry["_timestamp"] is not None,
                entry["_timestamp"] or datetime.min.replace(tzinfo=timezone.utc),
                entry["_index"],
            )

        for entry in entries:
            identity = (str(entry["kind"]), str(entry["name"]))
            previous = effective.get(identity)
            if previous is None or order(entry) > order(previous):
                effective[identity] = entry
        outcomes = {entry["outcome"] for entry in effective.values()}
        if "FAILURE" in outcomes:
            aggregate_state = "FAILURE"
        elif "PENDING" in outcomes:
            aggregate_state = "PENDING"
        elif "UNKNOWN" in outcomes or not outcomes:
            aggregate_state = "UNKNOWN"
        else:
            aggregate_state = "SUCCESS"
        aggregate_source = "derived"
    if aggregate_state not in {"SUCCESS", "FAILURE", "PENDING", "UNKNOWN"}:
        raise StatusError(f"GitHub status aggregate has an invalid state: {aggregate_state}")
    return {
        "available": True,
        "head_sha": current_head,
        "observed": observed,
        "inventory": [{key: value for key, value in entry.items() if not key.startswith("_")} for entry in entries],
        "_entries": entries,
        "pending": pending,
        "failed": failed,
        "aggregate": {"state": aggregate_state, "source": aggregate_source},
    }


def _required_authority(value: Any) -> dict[str, Any]:
    if not isinstance(value, Mapping) or value.get("available") is False:
        reason = value.get("reason") if isinstance(value, Mapping) else None
        return {
            "available": False,
            "status": "unavailable",
            "reason": reason or "authoritative branch protection is unavailable",
            "contexts": [],
        }
    contexts = value.get("contexts", [])
    checks = value.get("checks", [])
    if not isinstance(contexts, list) or any(not isinstance(item, str) or not item for item in contexts):
        return {"available": False, "status": "unavailable", "reason": "branch protection contexts are malformed", "contexts": []}
    if not isinstance(checks, list) or any(not isinstance(item, Mapping) for item in checks):
        return {"available": False, "status": "unavailable", "reason": "branch protection checks are malformed", "contexts": []}
    app_by_context: dict[str, Any] = {}
    for item in checks:
        context = item.get("context")
        app_id = item.get("app_id", item.get("appId"))
        if not isinstance(context, str) or not context:
            return {"available": False, "status": "unavailable", "reason": "branch protection check has no context", "contexts": []}
        if app_id is not None and (isinstance(app_id, bool) or not isinstance(app_id, (int, str))):
            return {"available": False, "status": "unavailable", "reason": f"branch protection app for {context} is malformed", "contexts": []}
        if isinstance(app_id, str) and app_id.isdigit():
            app_id = int(app_id)
        if context in app_by_context and app_by_context[context] != app_id:
            return {"available": False, "status": "unavailable", "reason": f"branch protection has conflicting apps for {context}", "contexts": []}
        app_by_context[context] = app_id
    names = list(dict.fromkeys(contexts + list(app_by_context)))
    required = []
    for context in names:
        app_id = app_by_context.get(context)
        required.append(
            {
                "context": context,
                "expected_app": {"id": app_id} if app_id is not None else {"any": True},
            }
        )
    return {"available": True, "status": "available", "strict": value.get("strict"), "contexts": required}


def _required_results(authority: dict[str, Any], inventory: dict[str, Any]) -> dict[str, Any]:
    if not authority["available"]:
        return authority
    results: list[dict[str, Any]] = []
    entries = inventory.get("_entries", inventory["inventory"])
    for expected in authority["contexts"]:
        context = expected["context"]
        matching = [entry for entry in entries if entry["name"] == context]
        exact = [entry for entry in matching if entry["exact_head"]]

        def newest(values: list[dict[str, Any]]) -> dict[str, Any] | None:
            values.sort(
                key=lambda item: (
                    item["_timestamp"] is not None,
                    item["_timestamp"] or datetime.min.replace(tzinfo=timezone.utc),
                    item["_index"],
                )
            )
            return values[-1] if values else None

        app_id = expected["expected_app"].get("id")
        app_matches = (
            [entry for entry in exact if (entry["app"] or {}).get("id") == app_id]
            if app_id is not None
            else exact
        )
        result = newest(app_matches)
        wrong_app_result = newest(exact) if exact else None
        wrong_app_results = [
            {key: value for key, value in entry.items() if not key.startswith("_")}
            for entry in exact
            if app_id is not None and (entry["app"] or {}).get("id") != app_id
        ]
        if result is None:
            result = wrong_app_result
        if result is None:
            status = "stale" if matching else "missing"
        elif app_id is not None and (result["app"] or {}).get("id") != app_id:
            status = "wrong_app"
        else:
            status = result["outcome"].lower()
        rendered = {key: value for key, value in (result or {}).items() if not key.startswith("_")}
        results.append(
            {
                "context": context,
                "expected_app": expected["expected_app"],
                "status": status,
                "result": rendered or None,
                "latest_exact_head": rendered or None,
                "wrong_app_results": wrong_app_results,
            }
        )
    statuses = {item["status"] for item in results}
    if statuses & {"failed", "wrong_app", "stale", "missing", "unknown"}:
        overall = "failed" if statuses & {"failed", "wrong_app"} else "pending"
    elif statuses & {"pending"}:
        overall = "pending"
    else:
        overall = "passed"
    return {
        "available": True,
        "status": overall,
        "strict": authority.get("strict"),
        "contexts": results,
    }


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


def _thread_summary(payload: Mapping[str, Any]) -> dict[str, Any]:
    nodes = (
        ((payload.get("data") or {}).get("repository") or {})
        .get("pullRequest", {})
        .get("reviewThreads", {})
        .get("nodes", [])
    )
    current = outdated = 0
    unresolved: list[dict[str, Any]] = []
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
            rendered = {
                "id": thread.get("id") if isinstance(thread.get("id"), str) else None,
                "path": thread.get("path"),
                "line": thread.get("line"),
                "outdated": thread["isOutdated"],
            }
            unresolved.append(rendered)
            if thread["isOutdated"]:
                outdated += 1
            else:
                current += 1
    return {"current": current, "outdated": outdated, "total": current + outdated, "_items": unresolved}


def _review_decision(payload: Mapping[str, Any], current_head: str, pull_request: Mapping[str, Any]) -> dict[str, Any]:
    """Report the PR decision or an exact-head review-node fallback."""

    explicit = pull_request.get("reviewDecision")
    if isinstance(explicit, str) and explicit.upper() in REVIEW_DECISIONS - {""}:
        decision = explicit.upper()
        # GitHub exposes reviewDecision at PR scope; it is not an exact-head
        # review-node assertion.  Keep that distinction visible to consumers.
        return {
            "status": decision,
            "scope": "pull_request",
            "source": "github",
            "head_sha": None,
            "exact_head": False,
        }
    else:
        reviews = (
            ((payload.get("data") or {}).get("repository") or {}).get("pullRequest", {}).get("reviews") or {}
        ).get("nodes")
        if not isinstance(reviews, list):
            raise StatusError("GitHub review decision evidence is malformed")
        current: list[tuple[datetime, int, str, int | None]] = []
        for index, review in enumerate(reviews, 1):
            if not isinstance(review, dict):
                raise StatusError(f"GitHub review {index} is not an object")
            state = review.get("state")
            if not isinstance(state, str):
                raise StatusError(f"GitHub review {index} has no state")
            commit = (review.get("commit") or {}).get("oid")
            if not isinstance(commit, str) or not EXACT_SHA.fullmatch(commit) or commit.casefold() != current_head.casefold():
                continue
            submitted_at = review.get("submittedAt")
            if not isinstance(submitted_at, str):
                continue
            current.append(
                (
                    _timestamp(submitted_at, f"GitHub review {index} submittedAt"),
                    index,
                    state.upper(),
                    github.immutable_database_id(review),
                )
            )
        if not current:
            return {
                "status": "UNKNOWN",
                "scope": "exact_head_review_node",
                "head_sha": current_head,
                "source": "unavailable",
                "exact_head": True,
            }
        current.sort(key=lambda item: (item[0], item[1]))
        states = {item[2] for item in current}
        if "CHANGES_REQUESTED" in states:
            decision = "CHANGES_REQUESTED"
        elif current[-1][2] == "APPROVED":
            decision = "APPROVED"
        elif current[-1][2] in {"COMMENTED", "DISMISSED"}:
            decision = "COMMENTED"
        else:
            decision = "UNKNOWN"
        source = "reviews"
    return {
        "status": decision,
        "scope": "exact_head_review_node",
        "head_sha": current_head,
        "source": source,
        "exact_head": True,
    }


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

    current_paths = [path for path in paths if path.name == "trigger.json"]
    archive_paths = [path for path in paths if re.fullmatch(r"trigger-[1-9][0-9]*\.json", path.name)]

    def historical_record() -> dict[str, Any] | None:
        for path in archive_paths:
            try:
                record = hosted.load_trigger_record(path, repo, number)
                value = hosted.trigger_state(repo, number, payload, record, path).as_dict()
                value.update(
                    {
                        "available": True,
                        "record_available": True,
                        "classification": "historical",
                        "validation_outcome": "historical",
                        "path": str(path),
                    }
                )
                return value
            except (OSError, ValueError, KeyError, TypeError, json.JSONDecodeError):
                continue
        return None

    # Archived trigger records are audit history, not a current Hosted
    # reservation.  Keep any readable latest archive separately so callers do
    # not mistake it for an active/current request.
    if not current_paths:
        historical = historical_record()
        result = {
            "available": False,
            "record_available": False,
            "classification": "unavailable/no-current-request",
            "state": "unavailable",
            "validation_outcome": "not-current",
        }
        if historical is not None:
            result["historical"] = historical
        return result
    if len(current_paths) != 1:
        return {
            "available": True,
            "record_available": False,
            "classification": "ambiguous/overlapping",
            "state": "ambiguous",
            "validation_outcome": "invalid",
            "reason": "multiple current Hosted trigger records are present",
        }
    try:
        current_path = current_paths[0]
        record = hosted.load_trigger_record(current_path, repo, number)
        state = hosted.trigger_state(repo, number, payload, record, current_path)
        value = state.as_dict()
        if value.get("current_head_sha", "").casefold() != head.casefold():
            value.update(
                {"classification": "ambiguous/overlapping", "state": "ambiguous", "validation_outcome": "invalid"}
            )
        else:
            value.update({"available": True, "record_available": True, "validation_outcome": "valid"})
        historical = historical_record()
        if historical is not None:
            value["historical"] = historical
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


def _inventory_source(value: Any, current_head: str) -> tuple[list[Any], Any] | None:
    if isinstance(value, list):
        return value, None
    if not isinstance(value, Mapping):
        return None
    if isinstance(value.get("inventory"), list):
        return value["inventory"], value.get("aggregate_state", value.get("aggregate"))
    runs = value.get("check_runs", value.get("checkRuns", []))
    statuses = value.get("status_contexts", value.get("statusContexts", []))
    if not isinstance(runs, list) or not isinstance(statuses, list):
        return None
    combined = list(runs)
    for item in statuses:
        if not isinstance(item, Mapping):
            combined.append(item)
            continue
        marked = dict(item)
        marked.setdefault("__typename", "StatusContext")
        marked.setdefault("head_sha", current_head)
        combined.append(marked)
    return combined, value.get("aggregate_state", value.get("aggregate"))


def _graphql_aggregate(payload: Mapping[str, Any], current_head: str) -> tuple[str | None, str | None]:
    """Read the aggregate rollup only when its GraphQL commit is the PR head."""

    pr = ((payload.get("data") or {}).get("repository") or {}).get("pullRequest", {})
    commits = pr.get("commits")
    if not isinstance(commits, Mapping) or not isinstance(commits.get("nodes"), list) or len(commits["nodes"]) != 1:
        return None, "GitHub aggregate rollup evidence is missing its exact-head commit"
    commit = commits["nodes"][0].get("commit") if isinstance(commits["nodes"][0], Mapping) else None
    if not isinstance(commit, Mapping):
        return None, "GitHub aggregate rollup evidence has no commit"
    oid = commit.get("oid")
    if not isinstance(oid, str) or not EXACT_SHA.fullmatch(oid):
        return None, "GitHub aggregate rollup evidence has no exact commit OID"
    if oid.casefold() != current_head.casefold():
        return None, "GitHub aggregate rollup commit does not match the current PR head"
    rollup = commit.get("statusCheckRollup")
    state = rollup.get("state") if isinstance(rollup, Mapping) else None
    if not isinstance(state, str) or state.upper() not in {"SUCCESS", "FAILURE", "PENDING", "EXPECTED", "ERROR"}:
        return None, "GitHub aggregate rollup state is missing or malformed"
    normalized = state.upper()
    if normalized in {"EXPECTED", "ERROR"}:
        normalized = "PENDING" if normalized == "EXPECTED" else "FAILURE"
    return normalized, None


def build_report(
    repo: str,
    pr_number: int,
    *,
    pull_request_payload: dict[str, Any] | None = None,
    checkpoint_payload: Mapping[str, Any] | None = None,
    required_status_checks_payload: Mapping[str, Any] | None = None,
    check_inventory_payload: Mapping[str, Any] | list[Any] | None = None,
) -> dict[str, Any]:
    repo = _repo_name(repo)
    if isinstance(pr_number, bool) or not isinstance(pr_number, int) or pr_number <= 0:
        raise StatusError("pr must be a positive integer")
    live = pull_request_payload is None
    raw_payload = github.fetch_pull_request(repo, pr_number) if live else pull_request_payload
    try:
        pr = raw_payload["data"]["repository"]["pullRequest"]
    except (KeyError, TypeError) as exc:
        raise StatusError("GitHub response has no pull request") from exc
    if live:
        # Review conversations require GraphQL pagination, while ``gh pr view`` is
        # the stable compact source for the PR, CI, mergeability, and LOC fields.
        pr.update(github.fetch_pr_metadata(repo, pr_number))
    _validate_github(pr, pr_number)
    if required_status_checks_payload is not None:
        required_source: Any = required_status_checks_payload
    elif isinstance(raw_payload.get("required_status_checks"), Mapping):
        required_source = raw_payload["required_status_checks"]
    elif isinstance(pr.get("required_status_checks"), Mapping):
        required_source = pr["required_status_checks"]
    elif live:
        try:
            required_source = github.fetch_required_status_checks(repo, pr["baseRefName"])
        except (OSError, RuntimeError, TypeError, ValueError) as exc:
            required_source = {"available": False, "reason": f"authoritative branch protection unavailable: {exc}"}
    else:
        required_source = None
    authority = _required_authority(required_source)
    aggregate_state, aggregate_error = _graphql_aggregate(raw_payload, pr["headRefOid"])

    if check_inventory_payload is not None:
        inventory_input: Any = check_inventory_payload
    elif isinstance(raw_payload.get("check_inventory"), (Mapping, list)):
        inventory_input = raw_payload["check_inventory"]
    elif isinstance(pr.get("check_inventory"), (Mapping, list)):
        inventory_input = pr["check_inventory"]
    elif live:
        try:
            inventory_input = github.fetch_check_inventory(repo, pr["headRefOid"])
        except (OSError, RuntimeError, TypeError, ValueError) as exc:
            inventory_input = {"available": False, "reason": f"complete check inventory unavailable: {exc}"}
    else:
        inventory_input = pr["statusCheckRollup"]
    if isinstance(inventory_input, Mapping) and inventory_input.get("available") is False:
        inventory = {
            "available": False,
            "status": "unavailable",
            "reason": inventory_input.get("reason", "complete check inventory is unavailable"),
        }
    else:
        source = _inventory_source(inventory_input, pr["headRefOid"])
        if source is None:
            inventory = {"available": False, "status": "unavailable", "reason": "complete check inventory is malformed"}
        else:
            raw_checks, aggregate = source
            inventory = _inventory_report(
                raw_checks,
                pr["headRefOid"],
                aggregate=aggregate_state if aggregate_state is not None else aggregate,
            )
            if aggregate_error:
                inventory["aggregate"] = {"state": "UNKNOWN", "source": "unavailable"}
    if inventory.get("available"):
        required = _required_results(authority, inventory)
    else:
        required = authority if not authority["available"] else {
            "available": False,
            "status": "unavailable",
            "reason": inventory["reason"],
            "contexts": [],
        }
    if checkpoint_payload is None:
        try:
            checkpoints_payload = evidence.collect_evidence(_historical_comments(raw_payload))
        except (evidence.EvidenceError, KeyError, TypeError) as exc:
            raise StatusError(f"historical checkpoint evidence is unusable: {exc}") from exc
    else:
        checkpoints_payload = checkpoint_payload
    checkpoints = _validate_checkpoint_report(checkpoints_payload)
    if inventory.get("available"):
        pending = inventory["pending"]
        failed = inventory["failed"]
        observed = inventory["observed"]
    else:
        pending, failed, observed = normalize_checks(pr["statusCheckRollup"])
    aggregate = (
        {"state": aggregate_state, "source": "github"}
        if aggregate_state is not None and not aggregate_error
        else inventory.get("aggregate", {"state": "UNKNOWN", "source": "unavailable"})
    )
    threads = _thread_summary(raw_payload)
    summary_evidence = _summary_evidence(raw_payload, pr["headRefOid"])
    review_decision = _review_decision(raw_payload, pr["headRefOid"], pr)
    loc = _loc_status(pr)
    reasons: list[str] = []
    if threads["total"]:
        reasons.append(f"{threads['total']} unresolved review thread(s)")
    if summary_evidence["findings"]:
        reasons.append(
            f"CodeRabbit summary has {len(summary_evidence['findings'])} actionable duplicate/outside-diff finding(s)"
        )
    if review_decision["status"] == "CHANGES_REQUESTED":
        reasons.append("review decision is CHANGES_REQUESTED")
    elif review_decision["status"] == "REVIEW_REQUIRED":
        reasons.append("review decision is REVIEW_REQUIRED")
    if not inventory.get("available"):
        reasons.append(inventory["reason"])
    if aggregate_error:
        reasons.append(aggregate_error)
    elif aggregate["state"] != "SUCCESS":
        reasons.append(f"GitHub aggregate rollup is {aggregate['state']}")
    if not required["available"]:
        reasons.append(required["reason"])
    elif required["status"] == "pending":
        reasons.append("one or more required status checks are pending or missing")
    elif required["status"] == "failed":
        reasons.append("one or more required status checks failed or used the wrong app")
    if observed == 0 and inventory.get("available"):
        reasons.append("no CI checks were returned")
    merge_state = pr["mergeStateStatus"].upper()
    mergeable_state = pr["mergeable"].upper()
    if mergeable_state == "CONFLICTING":
        reasons.append("PR has merge conflicts")
    elif mergeable_state == "UNKNOWN":
        reasons.append("PR mergeability is unknown")
    if pr["mergeable"].upper() != "MERGEABLE" and mergeable_state not in {"CONFLICTING", "UNKNOWN"}:
        reasons.append("GitHub mergeable is not MERGEABLE")
    if pr["isDraft"]:
        reasons.append("PR is a draft")
    if merge_state not in {"CLEAN", "BLOCKED"}:
        reasons.append(f"GitHub mergeStateStatus is {merge_state}")
    if loc["status"] != "fresh":
        reasons.append(f"LOC metadata is {loc['status']}")
    blocked_unknown = merge_state == "BLOCKED" and not reasons
    if blocked_unknown:
        reasons.append("BLOCKED — cause not exposed by available API")
    verdict = "BLOCKED — cause not exposed by available API" if blocked_unknown else ("READY" if not reasons else "NOT READY")
    unresolved_threads = threads.pop("_items", [])
    optional_failed = [item for item in failed if item["name"] not in {item["context"] for item in required.get("contexts", [])}]
    optional_pending = [item for item in pending if item["name"] not in {item["context"] for item in required.get("contexts", [])}]
    ci = {
        "observed": observed,
        "pending": pending,
        "failed": failed,
        "optional": {"pending": optional_pending, "failed": optional_failed},
        "aggregate": aggregate,
        "inventory": inventory.get("inventory", []),
        "required": required,
    }
    rendered_inventory = {key: value for key, value in inventory.items() if key != "_entries"}
    if aggregate_error:
        rendered_inventory["aggregate_error"] = aggregate_error
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
                "reviewDecision",
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
        "unresolved_threads": unresolved_threads,
        "review_decision": review_decision,
        "coderabbit_summary": summary_evidence,
        "check_inventory": rendered_inventory,
        "aggregate_error": aggregate_error,
        "required_checks": required,
        "aggregate": ci["aggregate"],
        "ci": ci,
        "loc_metadata": loc,
        "hosted_trigger": _trigger(repo, pr_number, raw_payload, pr["headRefOid"]),
        "mergeability": {
            "mergeStateStatus": pr["mergeStateStatus"],
            "mergeable": pr["mergeable"],
            "clean": not reasons,
            "diagnosis": verdict,
        },
        "ready": not reasons,
        "verdict": verdict,
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
    required = report["ci"]["required"]
    required_contexts = ", ".join(
        f"{item['context']}[app={item['expected_app'].get('id', 'any')}]={item['status']}/"
        f"{(item.get('latest_exact_head') or {}).get('outcome', 'none')}"
        for item in required.get("contexts", [])
    ) or "none"
    pending_names = ", ".join(item["name"] for item in report["ci"]["pending"]) or "none"
    failed_names = ", ".join(item["name"] for item in report["ci"]["failed"]) or "none"
    lines = [
        f"PR #{report['pr_number']} — {pr['title']}",
        f"head/base: {pr['headRefName']} {pr['headRefOid'][:12]} -> {pr['baseRefName']} {pr['baseRefOid'][:12]} · {pr['changedFiles']} files",
        f"state: draft={pr['isDraft']} · mergeable={pr['mergeable']} · mergeStateStatus={pr['mergeStateStatus']}",
        f"threads: current={report['threads']['current']} · outdated={report['threads']['outdated']} · total={report['threads']['total']}",
        f"review: decision={report['review_decision']['status']} · required={required.get('status')} ({required_contexts})",
        f"CI: aggregate={report['ci']['aggregate']['state']} · pending={len(report['ci']['pending'])} · failed={len(report['ci']['failed'])} · optional_failed={len(report['ci']['optional']['failed'])} · observed={report['ci']['observed']}",
        f"pending checks: {pending_names}",
        f"failed checks: {failed_names}",
        f"verdict: {report['verdict']}",
    ]
    for reason in report["reasons"]:
        lines.append(f"reason: {reason}")
    return "\n".join(lines)


__all__ = ["ReportError", "StatusError", "build_report", "emit_text", "normalize_checks", "status"]
