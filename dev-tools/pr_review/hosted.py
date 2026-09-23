"""Durable Hosted CodeRabbit trigger attribution and retirement mechanics."""

from __future__ import annotations

import fcntl
import json
import math
import os
import re
import stat
import tempfile
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Any

try:
    from .github import immutable_database_id, is_coderabbit_login, parse_repo
except ImportError:  # Loaded directly by repository validation tests.
    from github import immutable_database_id, is_coderabbit_login, parse_repo

FULL_COMMAND = "@coderabbitai full review"
REVIEW_LIMIT_MARKER = "<!-- This is an auto-generated comment: rate limited by coderabbit.ai -->"
NOOP_MARKER = "does not re-review already reviewed commits"
SUBSTANTIVE_MARKERS = (
    "<!-- walkthrough_start -->",
    "**Actionable comments posted:",
    "Outside diff range comments",
    "Outside the diff",
    "Duplicate comments",
    "final_review_risk_coverage",
)
COMMAND_INVOCATION_MARKER = "<!-- CodeRabbit review command invocation:"
FINISHED_REVIEW_PATTERN = re.compile(r"^[ \t]*full\s+review\s+finished\.\s*$", re.IGNORECASE | re.MULTILINE)
ACTIVE_PATTERN = re.compile(r"\bfull\s+review\s+triggered\b", re.IGNORECASE)
FAILED_PATTERN = re.compile(
    r"(?:\breview\b.{0,80}\b(?:failed|failure)\b|\b(?:failed|unable)\b.{0,80}\breview\b|\bsomething went wrong\b)",
    re.IGNORECASE | re.DOTALL,
)
RATE_LIMIT_PATTERN = re.compile(
    r"(?:next|more)\s+(?:included\s+)?reviews?\s+(?:will\s+be\s+)?available\s+in\s*:?\s*(\d+)\s+(seconds?|minutes?|hours?)",
    re.IGNORECASE,
)
EXACT_SHA = re.compile(r"^[0-9a-fA-F]{40}$")
ARCHIVED = re.compile(r"^trigger-([1-9][0-9]*)\.json$")
TIMEOUT_REASON = "bounded wait expired before a terminal CodeRabbit response"


@dataclass(frozen=True)
class TriggerState:
    state: str
    terminal: bool
    attributed: bool
    repository: str
    pr_number: int
    head_sha: str
    current_head_sha: str
    trigger_comment_id: int | None
    trigger_created_at: str | None
    trigger_url: str | None
    trigger_type: str | None
    response_id: int | None
    response_created_at: str | None
    response_url: str | None
    cooldown_until: str | None
    reason: str
    trigger_command: str | None = None
    age_seconds: int | None = None
    manual_adjudication_required: bool = False
    duration_seconds: int | None = None

    def as_dict(self) -> dict[str, Any]:
        return self.__dict__.copy()


def parse_timestamp(value: str | None) -> datetime | None:
    if not isinstance(value, str) or not value:
        return None
    try:
        return datetime.fromisoformat(value.replace("Z", "+00:00")).astimezone(timezone.utc)
    except ValueError:
        return None


def utc_now() -> str:
    return datetime.now(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")


def normalize_command(body: str) -> str:
    return " ".join(body.strip().split()).lower()


def _git_common_dir() -> Path:
    try:
        from .evidence import git_common_dir
    except ImportError:  # Direct loading by repository validation tests.
        from evidence import git_common_dir

    return git_common_dir()


def _safe_repo(repo: str) -> str:
    parse_repo(repo)
    return repo.replace("/", "_")


def trigger_record_paths(repo: str, pr_number: int, common: Path | None = None) -> list[Path]:
    """Discover new and legacy current/archived records without mutating state."""

    root = common or _git_common_dir()
    directories = (
        root / "firemud" / "hosted" / _safe_repo(repo) / f"pr-{pr_number}",
        root / "coderabbit-review-logs" / "hosted" / _safe_repo(repo) / f"pr-{pr_number}",
    )
    found: list[Path] = []
    for directory in directories:
        try:
            if directory.is_symlink() or not directory.is_dir():
                continue
            current = directory / "trigger.json"
            if current.is_file() and not current.is_symlink():
                found.append(current)
            archived = [
                (int(match.group(1)), path)
                for path in directory.iterdir()
                if (match := ARCHIVED.fullmatch(path.name)) and path.is_file() and not path.is_symlink()
            ]
            found.extend(path for _, path in sorted(archived, reverse=True))
        except OSError:
            continue
    return found


def default_trigger_record_path(repo: str, pr_number: int, common: Path | None = None) -> Path:
    root = common or _git_common_dir()
    return root / "firemud" / "hosted" / _safe_repo(repo) / f"pr-{pr_number}" / "trigger.json"


def current_trigger_record_paths(repo: str, pr_number: int, common: Path | None = None) -> list[Path]:
    """Return every current reservation path, including legacy locations."""

    root = common or _git_common_dir()
    return [
        path
        for directory in (
            root / "firemud" / "hosted" / _safe_repo(repo) / f"pr-{pr_number}",
            root / "coderabbit-review-logs" / "hosted" / _safe_repo(repo) / f"pr-{pr_number}",
        )
        if not directory.is_symlink()
        for path in [directory / "trigger.json"]
        if path.is_file() and not path.is_symlink()
    ]


def load_trigger_record(path: str | Path, repo: str, pr_number: int) -> dict[str, Any]:
    record_path = Path(path)
    if record_path.is_symlink():
        raise ValueError("trigger record must not be a symbolic link")
    record = json.loads(record_path.read_text(encoding="utf-8"))
    if not isinstance(record, dict) or record.get("repository") != repo or record.get("pr_number") != pr_number:
        raise ValueError("trigger record repository or pull request does not match")
    head = record.get("head_sha")
    trigger = record.get("trigger")
    if not isinstance(head, str) or not EXACT_SHA.fullmatch(head) or not isinstance(trigger, dict):
        raise ValueError("trigger record has invalid identity")
    return record


def load_trigger_reservation(path: str | Path, repo: str, pr_number: int) -> dict[str, Any]:
    """Read a valid trigger record or a legacy ambiguous posting reservation."""

    record_path = Path(path)
    if record_path.is_symlink():
        raise ValueError("trigger record must not be a symbolic link")
    record = json.loads(record_path.read_text(encoding="utf-8"))
    if not isinstance(record, dict) or record.get("repository") != repo or record.get("pr_number") != pr_number:
        raise ValueError("trigger record repository or pull request does not match")
    trigger = record.get("trigger")
    if record.get("status") == "posting" and not isinstance(trigger, dict):
        head = record.get("head_sha")
        if not isinstance(head, str) or not EXACT_SHA.fullmatch(head):
            raise ValueError("legacy posting reservation has invalid head identity")
        return record
    return load_trigger_record(record_path, repo, pr_number)


def atomic_write_json(path: Path, payload: dict[str, Any]) -> None:
    if path.is_symlink():
        raise OSError("trigger record must not be a symbolic link")
    path.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    path.parent.chmod(0o700)
    descriptor, temporary = tempfile.mkstemp(prefix=f".{path.name}.", dir=path.parent)
    try:
        os.fchmod(descriptor, stat.S_IRUSR | stat.S_IWUSR)
        with os.fdopen(descriptor, "w", encoding="utf-8") as stream:
            descriptor = -1
            json.dump(payload, stream, indent=2, sort_keys=True)
            stream.write("\n")
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, path)
        directory = os.open(path.parent, os.O_RDONLY | os.O_DIRECTORY)
        try:
            os.fsync(directory)
        finally:
            os.close(directory)
    finally:
        if descriptor != -1:
            os.close(descriptor)
        try:
            os.unlink(temporary)
        except FileNotFoundError:
            pass


def _with_lock(path: Path):
    path.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    descriptor = os.open(path.parent / "request.lock", os.O_RDWR | os.O_CREAT, 0o600)
    fcntl.flock(descriptor, fcntl.LOCK_EX)
    return descriptor


def assert_expected_pr(derived_pr: int, expected_pr: int | None) -> None:
    """Assert the target before any caller consumes Hosted request quota."""

    if expected_pr is not None and derived_pr != expected_pr:
        raise ValueError(f"expected PR #{expected_pr}, but derived PR #{derived_pr}")


def prepare_full_trigger(derived_pr: int, expected_pr: int | None = None) -> dict[str, Any]:
    assert_expected_pr(derived_pr, expected_pr)
    return {"pr_number": derived_pr, "command": FULL_COMMAND, "type": "full"}


def record_posted_trigger(
    repo: str,
    pr_number: int,
    head_sha: str,
    comment: dict[str, Any],
    *,
    expected_pr: int | None = None,
    path: str | Path | None = None,
) -> Path:
    """Persist one verified full-review trigger after the GitHub post succeeds.

    The caller must perform the actual post only after ``prepare_full_trigger``;
    this function accepts the returned GitHub identity and never posts itself.
    """

    assert_expected_pr(pr_number, expected_pr)
    if not EXACT_SHA.fullmatch(head_sha):
        raise ValueError("trigger head must be an exact SHA")
    trigger_id = immutable_database_id(comment)
    created_at = comment.get("createdAt")
    url = comment.get("url")
    if (
        trigger_id is None
        or not isinstance(created_at, str)
        or parse_timestamp(created_at) is None
        or not isinstance(url, str)
        or not url
    ):
        raise ValueError("posted trigger response has incomplete immutable identity")
    if (
        is_coderabbit_login((comment.get("author") or {}).get("login"))
        or normalize_command(comment.get("body") or "") != FULL_COMMAND
    ):
        raise ValueError("posted trigger response is not an externally authored full-review command")
    record_path = Path(path) if path is not None else default_trigger_record_path(repo, pr_number)
    descriptor = _with_lock(record_path)
    try:
        atomic_write_json(
            record_path,
            {
                "schema_version": 1,
                "status": "posted",
                "repository": repo,
                "pr_number": pr_number,
                "head_sha": head_sha,
                "trigger": {
                    "id": trigger_id,
                    "created_at": created_at,
                    "url": url,
                    "type": "full",
                    "command": FULL_COMMAND,
                },
            },
        )
    finally:
        fcntl.flock(descriptor, fcntl.LOCK_UN)
        os.close(descriptor)
    return record_path


def _substantive(body: str) -> bool:
    return any(marker in body for marker in SUBSTANTIVE_MARKERS)


def _unquoted(body: str) -> str:
    return "\n".join(line for line in body.splitlines() if not line.lstrip().startswith(">"))


def _scope_head(body: str) -> str | None:
    match = re.search(
        r"Reviewing files that changed from the base of the PR and between\s+`?([0-9a-f]{6,40})`?\s+and\s+`?([0-9a-f]{6,40})`?",
        body,
        re.IGNORECASE,
    )
    return match.group(2) if match else None


def _matches_head(body: str, head: str) -> bool:
    reported = _scope_head(body)
    return isinstance(reported, str) and head.casefold().startswith(reported.casefold())


def _rate_limit(body: str, created: datetime) -> datetime | None:
    match = RATE_LIMIT_PATTERN.search(_unquoted(body))
    if not match:
        return None
    amount, unit = int(match.group(1)), match.group(2).lower()
    return created + timedelta(
        **({"seconds" if unit.startswith("second") else "minutes" if unit.startswith("minute") else "hours": amount})
    )


def _zero_finding_summary(comments: list[dict[str, Any]], head: str, after: datetime) -> dict[str, Any] | None:
    matches: list[tuple[datetime, dict[str, Any]]] = []
    for comment in comments:
        if not is_coderabbit_login((comment.get("author") or {}).get("login")):
            continue
        body = comment.get("body") or ""
        if "No actionable comments were generated in the recent review." not in body or not _matches_head(body, head):
            continue
        if _rate_limit(body, parse_timestamp(comment.get("updatedAt")) or datetime.min.replace(tzinfo=timezone.utc)):
            continue
        updated = parse_timestamp(comment.get("updatedAt"))
        if updated and updated > after:
            matches.append((updated, comment))
    return max(matches, key=lambda value: value[0])[1] if matches else None


def _state_base(repo: str, pr: int, record: dict[str, Any], current_head: str) -> dict[str, Any]:
    trigger = record.get("trigger") or {}
    return {
        "repository": repo,
        "pr_number": pr,
        "head_sha": record.get("head_sha", ""),
        "current_head_sha": current_head,
        "trigger_comment_id": trigger.get("id"),
        "trigger_created_at": trigger.get("created_at"),
        "trigger_url": trigger.get("url"),
        "trigger_type": trigger.get("type"),
        "trigger_command": normalize_command(trigger.get("command") or ""),
    }


def trigger_state(
    repo: str,
    pr_number: int,
    payload: dict[str, Any],
    record: dict[str, Any],
    current_record_path: str | Path | None = None,
) -> TriggerState:
    pr = payload["data"]["repository"]["pullRequest"]
    current_head = pr.get("headRefOid", "")
    base = _state_base(repo, pr_number, record, current_head)
    trigger = record.get("trigger")
    trigger_id, trigger_at = (trigger or {}).get("id"), (trigger or {}).get("created_at")
    trigger_dt = parse_timestamp(trigger_at)
    if record.get("status") in {"posting", "posted_boundary_changed", "posted_boundary_unverified"} or not isinstance(
        trigger, dict
    ):
        return TriggerState(
            "ambiguous",
            True,
            False,
            **base,
            response_id=None,
            response_created_at=None,
            response_url=None,
            cooldown_until=None,
            reason="trigger posting boundary is not verified",
        )
    if (
        trigger.get("type") != "full"
        or normalize_command(trigger.get("command") or "") != FULL_COMMAND
        or not isinstance(trigger_id, int)
        or trigger_id <= 0
        or trigger_dt is None
        or not isinstance(trigger.get("url"), str)
    ):
        raise ValueError("trigger record has invalid full-review identity")
    comments = list((pr.get("comments") or {}).get("nodes", []))
    reviews = list((pr.get("reviews") or {}).get("nodes", []))
    captured = next((item for item in comments if immutable_database_id(item) == trigger_id), None)
    if captured is None:
        return TriggerState(
            "unattributed",
            True,
            False,
            **base,
            response_id=None,
            response_created_at=None,
            response_url=None,
            cooldown_until=None,
            reason="captured trigger comment is absent from complete GitHub history",
        )
    if (
        is_coderabbit_login((captured.get("author") or {}).get("login"))
        or normalize_command(captured.get("body") or "") != FULL_COMMAND
        or captured.get("createdAt") != trigger_at
        or captured.get("url") != trigger.get("url")
    ):
        return TriggerState(
            "ambiguous",
            True,
            False,
            **base,
            response_id=None,
            response_created_at=None,
            response_url=None,
            cooldown_until=None,
            reason="captured trigger identity changed",
        )
    if record.get("status") == "retired":
        return TriggerState(
            "retired",
            True,
            True,
            **base,
            response_id=None,
            response_created_at=None,
            response_url=None,
            cooldown_until=None,
            reason="trigger was explicitly retired",
            age_seconds=max(0, int((datetime.now(timezone.utc) - trigger_dt).total_seconds())),
        )
    newer = [
        item
        for item in comments
        if (
            not is_coderabbit_login((item.get("author") or {}).get("login"))
            and normalize_command(item.get("body") or "") == FULL_COMMAND
            and (dt := parse_timestamp(item.get("createdAt")))
            and dt >= trigger_dt
            and immutable_database_id(item) != trigger_id
        )
    ]
    next_dt = min((parse_timestamp(item.get("createdAt")) for item in newer), default=None)
    candidates: list[tuple[datetime, str, dict[str, Any], datetime | None]] = []
    for item in comments:
        if not is_coderabbit_login((item.get("author") or {}).get("login")):
            continue
        created = parse_timestamp(item.get("createdAt"))
        if created is None or created <= trigger_dt or (next_dt and created >= next_dt):
            continue
        body = item.get("body") or ""
        cooldown = _rate_limit(body, created)
        # Rate-limit evidence is classified before all other prose in a reply.
        if (
            REVIEW_LIMIT_MARKER in body
            or cooldown is not None
            or body.strip().lower().startswith("review rate limited")
        ):
            candidates.append((created, "rate_limited", item, cooldown))
        elif NOOP_MARKER in body:
            candidates.append((created, "noop", item, None))
        elif FAILED_PATTERN.search(_unquoted(body)):
            candidates.append((created, "failed", item, None))
        elif _substantive(body) and _matches_head(body, record["head_sha"]):
            candidates.append((created, "completed", item, None))
        elif ACTIVE_PATTERN.search(_unquoted(body)):
            candidates.append((created, "active", item, None))
        elif FINISHED_REVIEW_PATTERN.search(_unquoted(body)) and _zero_finding_summary(
            comments, record["head_sha"], trigger_dt
        ):
            candidates.append((created, "completed", item, None))
    for review in reviews:
        if not is_coderabbit_login((review.get("author") or {}).get("login")) or review.get("state") == "DISMISSED":
            continue
        submitted = parse_timestamp(review.get("submittedAt"))
        if (
            submitted is None
            or submitted <= trigger_dt
            or (next_dt and submitted >= next_dt)
            or not _substantive(review.get("body") or "")
        ):
            continue
        commit = (review.get("commit") or {}).get("oid")
        matched = (
            isinstance(commit, str)
            and commit.casefold() == record["head_sha"].casefold()
            or _matches_head(review.get("body") or "", record["head_sha"])
        )
        candidates.append((submitted, "completed" if matched else "ambiguous", review, None))
    if not candidates:
        if newer:
            return TriggerState(
                "ambiguous",
                True,
                False,
                **base,
                response_id=None,
                response_created_at=None,
                response_url=None,
                cooldown_until=None,
                reason="a later or concurrent full-review trigger prevents attribution",
            )
        if record.get("status") == "timed_out":
            return TriggerState(
                "timed_out",
                True,
                True,
                **base,
                response_id=None,
                response_created_at=None,
                response_url=None,
                cooldown_until=None,
                reason=TIMEOUT_REASON,
                manual_adjudication_required=True,
            )
        return TriggerState(
            "awaiting_response",
            False,
            True,
            **base,
            response_id=None,
            response_created_at=None,
            response_url=None,
            cooldown_until=None,
            reason="no attributable terminal response",
        )
    _, state, response, cooldown = max(candidates, key=lambda item: (item[0], immutable_database_id(item[2]) or -1))
    response_at = response.get("createdAt") or response.get("submittedAt")
    response_id = immutable_database_id(response)
    if state == "active" and response_id is None:
        return TriggerState(
            "unattributed",
            True,
            False,
            **base,
            response_id=None,
            response_created_at=response_at,
            response_url=response.get("url"),
            cooldown_until=None,
            reason="active response has no immutable numeric GitHub identity",
        )
    if state == "active":
        return TriggerState(
            "active",
            False,
            True,
            **base,
            response_id=response_id,
            response_created_at=response_at,
            response_url=response.get("url"),
            cooldown_until=None,
            reason="CodeRabbit acknowledged that the full review is active",
        )
    if state == "ambiguous":
        return TriggerState(
            "ambiguous",
            True,
            False,
            **base,
            response_id=response_id,
            response_created_at=response_at,
            response_url=response.get("url"),
            cooldown_until=None,
            reason="response does not identify the captured head",
        )
    response_dt = parse_timestamp(response_at)
    elapsed = math.ceil((response_dt - trigger_dt).total_seconds()) if response_dt and trigger_dt else None
    if response_id is None:
        return TriggerState(
            "unattributed",
            True,
            False,
            **base,
            response_id=None,
            response_created_at=response_at,
            response_url=response.get("url"),
            cooldown_until=cooldown.isoformat() if cooldown else None,
            reason="terminal response has no immutable numeric GitHub identity",
        )
    reason = {
        "completed": "attributable substantive review completed",
        "rate_limited": "CodeRabbit explicitly rate limited the request",
        "noop": "request acknowledged without reviewing commits",
        "failed": "CodeRabbit explicitly reported a review failure",
    }[state]
    return TriggerState(
        state,
        True,
        True,
        **base,
        response_id=response_id,
        response_created_at=response_at,
        response_url=response.get("url"),
        cooldown_until=cooldown.isoformat() if cooldown else None,
        reason=reason,
        duration_seconds=elapsed if elapsed is not None and elapsed >= 0 else None,
    )


def persist_timeout_if_current(path: str | Path, expected_record: dict[str, Any], state: TriggerState) -> bool:
    record_path = Path(path)
    descriptor = _with_lock(record_path)
    try:
        current = load_trigger_record(record_path, expected_record["repository"], expected_record["pr_number"])
        if json.dumps(current, sort_keys=True) != json.dumps(expected_record, sort_keys=True):
            return False
        updated = dict(expected_record)
        updated["status"] = "timed_out"
        updated["timeout"] = {
            "at": utc_now(),
            "observed_state": state.state,
            "observed_response_id": state.response_id,
            "reason": TIMEOUT_REASON,
        }
        atomic_write_json(record_path, updated)
        return True
    finally:
        fcntl.flock(descriptor, fcntl.LOCK_UN)
        os.close(descriptor)


def retire_trigger_record(
    path: str | Path,
    repo: str,
    pr_number: int,
    trigger_id: int,
    expected_head_sha: str,
    reason: str,
    payload: dict[str, Any],
) -> dict[str, Any]:
    if (
        not isinstance(trigger_id, int)
        or trigger_id <= 0
        or not EXACT_SHA.fullmatch(expected_head_sha)
        or not isinstance(reason, str)
        or not reason.strip()
        or len(reason) > 240
        or any(ord(char) < 0x20 for char in reason)
    ):
        raise ValueError("invalid trigger retirement request")
    record_path = Path(path)
    descriptor = _with_lock(default_trigger_record_path(repo, pr_number))
    try:
        # Re-read after taking the same per-PR lock used by Hosted posting.
        record = load_trigger_record(record_path, repo, pr_number)
        state = trigger_state(repo, pr_number, payload, record, record_path)
        current = payload["data"]["repository"]["pullRequest"].get("headRefOid")
        if (
            state.trigger_comment_id != trigger_id
            or not isinstance(current, str)
            or current.casefold() != expected_head_sha.casefold()
        ):
            raise ValueError("trigger identity or current head does not match retirement request")
        if state.state == "active":
            raise ValueError("cannot retire a Hosted review while it is active")
        superseded = state.state in {"ambiguous", "unattributed"} and _has_later_completed_exact_head(
            repo, pr_number, record, expected_head_sha, payload
        )
        if state.state != "timed_out" and not superseded:
            raise ValueError(
                f"cannot retire trigger in state {state.state} without a later completed exact-head review"
            )
        updated = dict(record)
        updated["status"] = "retired"
        updated["retirement"] = {
            "action": "operator_retire",
            "retired_at": utc_now(),
            "reason": reason.strip(),
            "trigger_comment_id": trigger_id,
            "expected_head_sha": expected_head_sha,
            "evidence": {"state": state.state, "captured_head_sha": record["head_sha"], "current_head_sha": current},
        }
        atomic_write_json(record_path, updated)
    finally:
        fcntl.flock(descriptor, fcntl.LOCK_UN)
        os.close(descriptor)
    return {
        "operation": "retire_trigger",
        "status": "retired",
        "repository": repo,
        "pr_number": pr_number,
        "trigger_comment_id": trigger_id,
        "expected_head_sha": expected_head_sha,
        "reason": reason.strip(),
    }


def _has_later_completed_exact_head(
    repo: str,
    pr_number: int,
    retired_record: dict[str, Any],
    expected_head_sha: str,
    payload: dict[str, Any],
) -> bool:
    retired_at = parse_timestamp((retired_record.get("trigger") or {}).get("created_at"))
    retired_id = (retired_record.get("trigger") or {}).get("id")
    for candidate_path in trigger_record_paths(repo, pr_number):
        try:
            candidate = load_trigger_record(candidate_path, repo, pr_number)
            trigger = candidate.get("trigger") or {}
            created = parse_timestamp(trigger.get("created_at"))
            response = trigger_state(repo, pr_number, payload, candidate, candidate_path)
        except (OSError, ValueError, KeyError, TypeError, json.JSONDecodeError):
            continue
        anchor = candidate.get("anchor")
        anchor_complete = isinstance(anchor, dict) and all(
            isinstance(anchor.get(key), str) and anchor.get(key)
            for key in ("child_head", "parent_identity", "parent_head", "merge_base", "patch_id")
        )
        if (
            candidate.get("head_sha", "").casefold() == expected_head_sha.casefold()
            and trigger.get("id") != retired_id
            and created is not None
            and (retired_at is None or created > retired_at)
            and response.state == "completed"
            and response.head_sha.casefold() == expected_head_sha.casefold()
            and response.response_id is not None
            and anchor_complete
            and anchor.get("child_head", "").casefold() == expected_head_sha.casefold()
        ):
            return True
    return False
