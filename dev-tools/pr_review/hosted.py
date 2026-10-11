"""Durable Hosted CodeRabbit trigger attribution and retirement mechanics."""

from __future__ import annotations

import fcntl
import hashlib
import json
import math
import os
import re
import stat
import tempfile
from collections.abc import Callable, Mapping, Sequence
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Any

try:
    from .github import immutable_database_id, is_coderabbit_login, parse_repo
    from .sqlite_review_records import AttemptNotFound, SqliteReviewRecords
    from .state import sqlite_state_path, state_path
except ImportError:  # Loaded directly by repository validation tests.
    from github import immutable_database_id, is_coderabbit_login, parse_repo
    from sqlite_review_records import AttemptNotFound, SqliteReviewRecords
    from state import sqlite_state_path, state_path

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
PROVIDER_FILE_CEILING_SKIP_PATTERN = re.compile(
    r"\breview\s+skipped:\s*(\d+)\s+files?\s+exceed(?:s)?\s+the\s+limit\s+of\s*(\d+)\b",
    re.IGNORECASE,
)
RATE_LIMIT_PATTERN = re.compile(
    r"(?:next|more)\s+(?:included\s+)?reviews?\s+(?:will\s+be\s+)?available\s+in\s*:?\s*(\d+)\s+(seconds?|minutes?|hours?)",
    re.IGNORECASE,
)
WRAPPED_RATE_LIMIT_REPLY_PATTERN = re.compile(
    r"(?:[ \t]*\r?\n)*(?:(?:<!-- This is an auto-generated reply by CodeRabbit -->"
    r"|<!-- CodeRabbit review command invocation:[^>]* -->)[ \t]*\r?\n(?:[ \t]*\r?\n)*)*"
    r"<details\b[^>]*>\s*<summary\b[^>]*>[^<]*\bAction\s+not\s+completed\b[^<]*</summary>"
    r"\s*Review\s+rate\s+limited\.?\s*</details>[ \t]*(?:\r?\n[ \t]*)*",
    re.IGNORECASE,
)
EXACT_SHA = re.compile(r"^[0-9a-fA-F]{40}$")
COMPLETE_FILE_COVERAGE_PATTERNS = (
    re.compile(
        r"\b(?:all|every)\s+(?:(?:of\s+the)\s+)?(?:\d+\s+)?(?:changed\s+)?files?\s+"
        r"(?:have\s+been\s+|were\s+|are\s+)?reviewed\b",
        re.IGNORECASE,
    ),
    re.compile(
        r"\b(?:reviewed|processed)\s+(?:all|every)\s+(?:(?:of\s+the)\s+)?"
        r"(?:\d+\s+)?(?:changed\s+)?files?\b",
        re.IGNORECASE,
    ),
)
FILE_COVERAGE_COUNT = re.compile(
    r"\b(\d+)\s*(?:of|/)\s*(\d+)\s+(?:changed\s+)?files?\s+"
    r"(?:have\s+been\s+|were\s+|are\s+)?reviewed\b",
    re.IGNORECASE,
)
FILE_SELECTED_COUNT = re.compile(r"\bselected\s*[:=]\s*(\d+)\b", re.IGNORECASE)
FILE_REVIEWED_LABEL_COUNT = re.compile(r"\breviewed\s*[:=]\s*(\d+)\b", re.IGNORECASE)
FILE_REVIEWED_RATIO = re.compile(r"\breviewed\s*[:=]\s*(\d+)\s*/\s*(\d+)\b", re.IGNORECASE)
FILE_NOT_REVIEWED_COUNT = re.compile(
    r"\bnot[\s-]+reviewed\b(?:\s+due\b[^:\n]*?)?\s*(?::|=|\()\s*(\d+)\s*\)?",
    re.IGNORECASE,
)
ZERO_FINDING_PATTERNS = (
    re.compile(
        r"\b(?:actionable\s+)?comments?\s+(?:posted|generated|found)\s*[:\-]?\s*0\b",
        re.IGNORECASE,
    ),
    re.compile(r"\b0\s+(?:actionable\s+)?(?:comments?|findings?|issues?)\b", re.IGNORECASE),
    re.compile(r"\bno\s+(?:actionable\s+)?(?:comments?|findings?|issues?)\b", re.IGNORECASE),
)
POSITIVE_FINDING_COUNT_PATTERNS = (
    re.compile(
        r"\b(?:actionable\s+)?comments?\s+(?:posted|generated|found)\s*[:=\-]?\s*0*[1-9]\d*\b",
        re.IGNORECASE,
    ),
    re.compile(r"\b0*[1-9]\d*\s+(?:actionable\s+)?(?:comments?|findings?|issues?)\b", re.IGNORECASE),
    re.compile(r"\b(?:findings?|issues?)\s+(?:posted|generated|found)\s*[:=\-]?\s*0*[1-9]\d*\b", re.IGNORECASE),
)
INCOMPLETE_FILE_COVERAGE = re.compile(
    r"\b(?:\d+\s*(?:of|/)\s*\d+\s+)?(?:changed\s+)?files?\b.{0,100}"
    r"\b(?:not\s+reviewed|not\s+processed|skipped|omitted|could\s+not\s+be\s+reviewed|"
    r"unable\s+to\s+review|moderation|processing\s+errors?)\b",
    re.IGNORECASE | re.DOTALL,
)
FILE_PROCESSING_NONCOVERAGE = re.compile(
    r"\bfiles?\b.{0,100}\b(?:not\s+processed|moderation|processing\s+errors?)\b"
    r"(?:\s*(?::|=|\()\s*(\d+)\s*\)?)?",
    re.IGNORECASE | re.DOTALL,
)
EXPLICIT_FILE_OMISSION = re.compile(
    r"\b(?:(\d+)\s+)?files?\b(?:\s*\(\s*(\d+)\s*\))?\s+"
    r"(?:(?:were|are)\s+)?(?:skipped|omitted)\b(?:\s*[:=]\s*(\d+))?",
    re.IGNORECASE,
)
OPEN_ISSUE_CLAIM = re.compile(
    r"\b(?:\d+\s+)?(?:reported\s+)?issues?\s+(?:remain|remains|are|is|stay|stays|still)\s+open\b",
    re.IGNORECASE,
)
ARCHIVED = re.compile(r"^trigger-([1-9][0-9]*)\.json$")
TIMEOUT_REASON = "bounded wait expired before a terminal CodeRabbit response"
LATER_TRIGGER_AMBIGUITY_REASON = "a later or concurrent full-review trigger prevents attribution"
UNKNOWN_RATE_LIMIT_BACKOFF = timedelta(seconds=3600)
UNKNOWN_RATE_LIMIT_REASON = (
    "CodeRabbit rate limited; response creation time is unavailable or invalid, so cooldown remains unresolved"
)


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
    cooldown_basis: str | None = None
    trigger_command: str | None = None
    age_seconds: int | None = None
    manual_adjudication_required: bool = False
    duration_seconds: int | None = None
    publication_review_ids: tuple[int, ...] = ()
    publication_finished_at: str | None = None

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


def provider_file_ceiling_skip(body: Any) -> bool:
    """Recognize CodeRabbit's explicit over-limit skip response, not generic skipped-file prose."""

    if not isinstance(body, str):
        return False
    match = PROVIDER_FILE_CEILING_SKIP_PATTERN.search(body)
    return match is not None and int(match.group(1)) > int(match.group(2))


def _comment_id_floor(payload: dict[str, Any]) -> int:
    """Return the highest immutable issue-comment ID in a complete PR payload."""

    try:
        comments = payload["data"]["repository"]["pullRequest"]["comments"]["nodes"]
    except (KeyError, TypeError) as exc:
        raise TypeError("complete pull-request comment history is unavailable") from exc
    if not isinstance(comments, list):
        raise TypeError("complete pull-request comment history is unavailable")
    floor = 0
    for item in comments:
        if not isinstance(item, dict):
            raise TypeError("live comment history contains an invalid comment")
        comment_id = immutable_database_id(item)
        if comment_id is None:
            raise ValueError("live comment history contains a comment without immutable identity")
        floor = max(floor, comment_id)
    return floor


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


def _trigger_record_common_for_path(path: Path, repo: str, pr_number: int) -> Path | None:
    """Return the record root only for a canonical current/archive record path."""

    if ".." in path.parts or (path.name != "trigger.json" and not ARCHIVED.fullmatch(path.name)):
        return None
    record_dir = path.parent
    repository_dir = record_dir.parent
    hosted_dir = repository_dir.parent
    namespace_dir = hosted_dir.parent
    if (
        record_dir.name != f"pr-{pr_number}"
        or repository_dir.name != _safe_repo(repo)
        or hosted_dir.name != "hosted"
        or namespace_dir.name not in {"firemud", "coderabbit-review-logs"}
    ):
        return None
    return namespace_dir.parent


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


def _write_json_exclusive(path: Path, payload: dict[str, Any]) -> None:
    """Create a private JSON file without replacing any existing audit."""

    if path.is_symlink() or path.parent.is_symlink():
        raise OSError("audit path must not be a symbolic link")
    path.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    path.parent.chmod(0o700)
    descriptor = -1
    created = False
    try:
        descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        created = True
        os.fchmod(descriptor, stat.S_IRUSR | stat.S_IWUSR)
        with os.fdopen(descriptor, "w", encoding="utf-8") as stream:
            descriptor = -1
            json.dump(payload, stream, indent=2, sort_keys=True)
            stream.write("\n")
            stream.flush()
            os.fsync(stream.fileno())
        directory = os.open(path.parent, os.O_RDONLY | os.O_DIRECTORY)
        try:
            os.fsync(directory)
        finally:
            os.close(directory)
    except BaseException:
        if descriptor != -1:
            try:
                os.close(descriptor)
            except OSError:
                pass
        if created:
            try:
                os.unlink(path)
            except OSError:
                pass
        raise


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


def _comment_author_login(comment: dict[str, Any]) -> Any:
    author = comment.get("author") or comment.get("user") or {}
    return author.get("login") if isinstance(author, dict) else None


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
    author_login = _comment_author_login(comment)
    if (
        trigger_id is None
        or not isinstance(created_at, str)
        or parse_timestamp(created_at) is None
        or not isinstance(url, str)
        or not url
        or not isinstance(author_login, str)
        or not author_login.strip()
    ):
        raise ValueError("posted trigger response has incomplete immutable identity")
    if is_coderabbit_login(author_login) or normalize_command(comment.get("body") or "") != FULL_COMMAND:
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
                    "author_login": author_login,
                    "type": "full",
                    "command": FULL_COMMAND,
                },
            },
        )
    finally:
        fcntl.flock(descriptor, fcntl.LOCK_UN)
        os.close(descriptor)
    return record_path


def unresolved_preceding_full_trigger(
    repo: str, pr_number: int, payload: dict[str, Any], trigger_id: int, common: Path | None = None
) -> bool:
    """Require a separately audited terminal record for every earlier command.

    Bot output alone cannot prove which of two overlapping manual requests it
    answered. This guards attribution and admission, never taper history.
    """

    try:
        pr = payload["data"]["repository"]["pullRequest"]
        comments = pr["comments"]["nodes"]
    except (KeyError, TypeError) as exc:
        raise ValueError("complete public command history is required") from exc
    if not isinstance(comments, list):
        raise TypeError("complete public command history is required")
    commands: list[tuple[datetime, int]] = []
    for item in comments:
        if not isinstance(item, dict):
            raise TypeError("public command history contains a malformed comment")
        if (
            is_coderabbit_login(_comment_author_login(item))
            or normalize_command(item.get("body") or "") != FULL_COMMAND
        ):
            continue
        identity = immutable_database_id(item)
        created = parse_timestamp(item.get("createdAt"))
        if identity is None or created is None:
            raise ValueError("public full-review command has incomplete identity")
        commands.append((created, identity))
    commands.sort()
    positions = [index for index, (_, identity) in enumerate(commands) if identity == trigger_id]
    if len(positions) != 1:
        raise ValueError("public full-review command is missing or duplicated")
    records: dict[int, tuple[dict[str, Any], Path]] = {}
    for path in trigger_record_paths(repo, pr_number, common):
        try:
            record = load_trigger_record(path, repo, pr_number)
        except (OSError, ValueError, TypeError) as exc:
            raise ValueError(f"cannot inspect existing trigger record {path}: {exc}") from exc
        recorded_id = (record.get("trigger") or {}).get("id")
        if isinstance(recorded_id, int) and not isinstance(recorded_id, bool):
            if recorded_id in records:
                raise ValueError("public full-review command has duplicate private records")
            records[recorded_id] = (record, path)

    def unrecorded_command_has_terminal_result(index: int) -> bool:
        command_at, command_id = commands[index]
        end = commands[index + 1][0]
        reviews = (pr.get("reviews") or {}).get("nodes")
        threads = (pr.get("reviewThreads") or {}).get("nodes")
        if not isinstance(reviews, list) or not isinstance(threads, list):
            raise TypeError("complete public review history is required")
        if any(not isinstance(item, dict) for item in reviews + threads):
            raise ValueError("public review history contains a malformed item")
        for thread in threads:
            thread_comments = (thread.get("comments") or {}).get("nodes")
            if not isinstance(thread_comments, list) or any(not isinstance(item, dict) for item in thread_comments):
                raise ValueError("complete public review-thread history is required")

        def in_window(value: Any) -> datetime | None:
            timestamp = parse_timestamp(value)
            return timestamp if timestamp is not None and command_at < timestamp < end else None

        response_items: list[tuple[dict[str, Any], str, datetime, int, str | None]] = []
        all_response_ids: set[int] = set()
        for item, timestamp_field in (
            *((comment, "createdAt") for comment in comments),
            *((review, "submittedAt") for review in reviews),
        ):
            if not is_coderabbit_login(_comment_author_login(item)):
                continue
            body = item.get("body")
            if timestamp_field == "createdAt":
                comment_created = parse_timestamp(item.get("createdAt"))
                comment_updated = parse_timestamp(item.get("updatedAt"))
                if comment_created is None or comment_updated is None:
                    return False
                if comment_created <= command_at < comment_updated < end:
                    return False
                if (
                    isinstance(body, str)
                    and "<!-- This is an auto-generated comment: summarize by coderabbit.ai -->" in body
                ):
                    if comment_created <= command_at and comment_updated <= command_at:
                        continue
                    if in_window(comment_created) is not None or in_window(comment_updated) is not None:
                        return False
            identity = immutable_database_id(item)
            response_at = parse_timestamp(item.get(timestamp_field))
            if identity is None or response_at is None or identity in all_response_ids:
                return False
            all_response_ids.add(identity)
            if command_at < response_at < end:
                response_items.append(
                    (item, timestamp_field, response_at, identity, public_response_state(item, timestamp_field, {}))
                )

        exact_heads: set[str] = set()
        for review in reviews:
            if not is_coderabbit_login(_comment_author_login(review)) or review.get("state") == "DISMISSED":
                continue
            if in_window(review.get("submittedAt")) is None:
                continue
            commit = (review.get("commit") or {}).get("oid")
            if isinstance(commit, str) and EXACT_SHA.fullmatch(commit):
                exact_heads.add(commit.casefold())
        for item in comments:
            if not is_coderabbit_login(_comment_author_login(item)):
                continue
            body = item.get("body")
            if not isinstance(body, str):
                continue
            created_in_window = in_window(item.get("createdAt")) is not None
            updated_in_window = in_window(item.get("updatedAt")) is not None
            reported_head = _scope_head(body)
            if (
                (created_in_window or updated_in_window)
                and isinstance(reported_head, str)
                and EXACT_SHA.fullmatch(reported_head)
            ):
                exact_heads.add(reported_head.casefold())

        if len(exact_heads) > 1:
            return False
        if exact_heads:
            head = next(iter(exact_heads))
            trigger_comment = next(item for item in comments if immutable_database_id(item) == command_id)
            synthetic_record = {
                "schema_version": 2,
                "status": "posted",
                "repository": repo,
                "pr_number": pr_number,
                "head_sha": head,
                "trigger": {
                    "id": command_id,
                    "created_at": trigger_comment.get("createdAt"),
                    "url": trigger_comment.get("url"),
                    "author_login": _comment_author_login(trigger_comment),
                    "type": "full",
                    "command": FULL_COMMAND,
                },
            }
            state = trigger_state(repo, pr_number, payload, synthetic_record)
            if (
                state.state != "completed"
                or state.terminal is not True
                or state.attributed is not True
                or state.response_id is None
                or state.trigger_comment_id != command_id
                or state.head_sha.casefold() != head
            ):
                return False
            response_items = [
                item for item in (*comments, *reviews) if immutable_database_id(item) == state.response_id
            ]
            if len(response_items) != 1:
                return False
            response = response_items[0]
            response_at = parse_timestamp(response.get("createdAt") or response.get("submittedAt"))
            terminal_at = parse_timestamp(
                response.get("updatedAt") if response in comments else response.get("submittedAt")
            )
            if response_at is None or terminal_at is None or not command_at < response_at < end or terminal_at >= end:
                return False
            if response in reviews:
                commit = (response.get("commit") or {}).get("oid")
                return isinstance(commit, str) and EXACT_SHA.fullmatch(commit) and commit.casefold() == head
            body = response.get("body")
            if isinstance(body, str) and (_scope_head(body) or "").casefold() == head:
                return True
            if isinstance(body, str) and _is_finished_action_response(body, allow_action_wrapper=True):
                summary = _zero_finding_summary(payload, head, command_at, state.response_id, end)
                if summary is None or (_scope_head(summary.get("body", "")) or "").casefold() != head:
                    summary = provider_format_zero_finding_summary(payload, head, command_at, state.response_id, end)
                return summary is not None
            return False

        # Terminal failures, no-ops, and rate limits can release the overlap
        # without a reviewed-head claim. Use the public classifier directly;
        # never synthesize a review head from the live branch tip.
        terminal_events = [
            (response_at, identity, response_state, item, timestamp_field)
            for item, timestamp_field, response_at, identity, response_state in response_items
            if response_state in {"failed", "noop", "rate_limited"}
        ]
        ambiguous_events = [item for item in response_items if item[4] == "ambiguous"]
        active_events = [item[2] for item in response_items if item[4] == "active"]
        if ambiguous_events:
            return False
        if len(terminal_events) != 1:
            return False
        response_at, _, response_state, response, timestamp_field = terminal_events[0]
        if any(active_at > response_at for active_at in active_events):
            return False
        response_body = response.get("body")
        if (
            response_state == "failed"
            and isinstance(response_body, str)
            and (provider_file_ceiling_skip(response_body) or _summary_has_explicit_incompleteness(response_body))
        ):
            return False
        terminal_at = parse_timestamp(
            response.get("updatedAt") if timestamp_field == "createdAt" else response.get("submittedAt")
        )
        return terminal_at is not None and command_at < terminal_at < end

    for index in range(positions[0]):
        _, preceding_id = commands[index]
        end, _ = commands[index + 1]
        recorded = records.get(preceding_id)
        if recorded is None:
            if not unrecorded_command_has_terminal_result(index):
                return True
            continue
        record, path = recorded
        if record.get("status") == "retired":
            retirement = record.get("retirement")
            if not isinstance(retirement, dict) or retirement.get("observed_live_state") not in {
                "completed",
                "rate_limited",
                "noop",
                "failed",
            }:
                return True
            continue
        state = trigger_state(repo, pr_number, payload, record, path)
        response_at = parse_timestamp(state.response_created_at)
        response_comments = [item for item in comments if immutable_database_id(item) == state.response_id]
        if len(response_comments) == 1:
            terminal_at = parse_timestamp(response_comments[0].get("updatedAt"))
        else:
            reviews = (pr.get("reviews") or {}).get("nodes")
            response_reviews = (
                [item for item in reviews if immutable_database_id(item) == state.response_id]
                if isinstance(reviews, list)
                else []
            )
            terminal_at = (
                parse_timestamp(response_reviews[0].get("submittedAt")) if len(response_reviews) == 1 else None
            )
        if state.terminal is not True or response_at is None or terminal_at is None or terminal_at >= end:
            return True
    return False


def _unresolved_retired_predecessor(
    repo: str, pr_number: int, trigger_at: datetime, current_record_path: str | Path | None
) -> bool:
    """A retired in-flight request can still emit a late headless reply."""

    if current_record_path is None:
        return False
    current = Path(current_record_path)
    common = _trigger_record_common_for_path(current, repo, pr_number)
    if common is None:
        return False
    for path in trigger_record_paths(repo, pr_number, common):
        if path == current:
            continue
        record = load_trigger_record(path, repo, pr_number)
        previous_at = parse_timestamp((record.get("trigger") or {}).get("created_at"))
        if record.get("status") != "retired" or previous_at is None or previous_at >= trigger_at:
            continue
        retirement = record.get("retirement")
        if not isinstance(retirement, dict) or retirement.get("observed_live_state") not in {
            "completed",
            "rate_limited",
            "noop",
            "failed",
        }:
            return True
    return False


def public_response_state(
    item: dict[str, Any], timestamp_field: str, checkpoint_by_response: dict[int, Any]
) -> str | None:
    """Classify one public Hosted response when its private trigger record is absent."""

    identity = immutable_database_id(item)
    checkpoint = checkpoint_by_response.get(identity) if identity is not None else None
    raw_body = item.get("body")
    body = raw_body if isinstance(raw_body, str) else ""
    if timestamp_field == "submittedAt":
        if item.get("state") == "DISMISSED":
            return None
        if item.get("state") not in {"COMMENTED", "APPROVED", "CHANGES_REQUESTED"}:
            return "ambiguous"
        commit = (item.get("commit") or {}).get("oid")
        if isinstance(commit, str) and EXACT_SHA.fullmatch(commit):
            return "completed"
        if _substantive(body):
            return "completed" if _scope_head(body) else "ambiguous"
        if checkpoint is not None and isinstance(checkpoint.reviewed_sha, str):
            return "completed"
        return None

    if is_rate_limit_reply_body(body):
        return "rate_limited"
    if provider_file_ceiling_skip(body):
        return "failed"
    if NOOP_MARKER in body:
        return "noop"
    if ACTIVE_PATTERN.search(_unquoted(body)):
        return "active"
    if FAILED_PATTERN.search(_unquoted(body)):
        return "failed"
    if _substantive(body) or FINISHED_REVIEW_PATTERN.search(_unquoted(body)):
        if _scope_head(body):
            return "completed"
        if checkpoint is not None and isinstance(checkpoint.reviewed_sha, str):
            return "completed"
        return "ambiguous"
    return None


def adopt_manual_completed_trigger(
    repo: str,
    pr_number: int,
    trigger_id: int,
    head_sha: str,
    anchor: dict[str, Any],
    fetch_payload: Callable[[], dict[str, Any]],
    *,
    path: str | Path | None = None,
    common: Path | None = None,
) -> dict[str, Any]:
    """Audit a public manual request after its unique review has completed.

    The live PR is fetched under the per-PR Hosted request lock so the same
    payload verifies the anchor, public result, and durable attribution. This
    never posts to GitHub.
    """

    if type(trigger_id) is not int or trigger_id <= 0 or not EXACT_SHA.fullmatch(head_sha):
        raise ValueError("manual adoption requires an exact trigger ID and reviewed head")
    if (
        not isinstance(anchor, dict)
        or anchor.get("child_head", "").casefold() != head_sha.casefold()
        or not all(
            isinstance(anchor.get(key), str) and anchor[key]
            for key in ("parent_identity", "parent_head", "merge_base", "patch_id")
        )
        or not EXACT_SHA.fullmatch(anchor.get("parent_head", ""))
    ):
        raise ValueError("manual adoption requires a verified current candidate anchor")
    if not callable(fetch_payload):
        raise TypeError("manual adoption requires a live pull-request fetch callback")
    record_path = Path(path) if path is not None else default_trigger_record_path(repo, pr_number, common)
    record_common = common
    if record_common is None and path is not None:
        record_common = _trigger_record_common_for_path(record_path, repo, pr_number)
    descriptor = _with_lock(record_path)
    try:
        payload = fetch_payload()
        if not isinstance(payload, dict):
            raise TypeError("live pull-request response is not an object")
        try:
            pr = payload["data"]["repository"]["pullRequest"]
        except (KeyError, TypeError) as error:
            raise TypeError("live pull-request data is unavailable for manual adoption") from error
        if not isinstance(pr, dict):
            raise TypeError("live pull-request data is unavailable for manual adoption")
        live_head = pr.get("headRefOid")
        if not isinstance(live_head, str) or not EXACT_SHA.fullmatch(live_head):
            raise ValueError("manual adoption requires an exact live PR head")
        if live_head.casefold() != head_sha.casefold():
            raise ValueError("manual adoption requires the reviewed head to remain current")
        live_base_name = pr.get("baseRefName")
        live_base_oid = pr.get("baseRefOid")
        if (
            not isinstance(live_base_name, str)
            or not live_base_name
            or (not anchor["parent_identity"].isdecimal() and live_base_name != anchor["parent_identity"])
            or not isinstance(live_base_oid, str)
            or not EXACT_SHA.fullmatch(live_base_oid)
            or live_base_oid.casefold() != anchor["parent_head"].casefold()
        ):
            raise ValueError("manual Hosted adoption requires the live PR base to match its verified parent")

        comments = (pr.get("comments") or {}).get("nodes")
        if not isinstance(comments, list):
            raise TypeError("manual adoption requires complete public comments")
        review_connection = pr.get("reviews")
        reviews = review_connection.get("nodes") if isinstance(review_connection, dict) else None
        if not isinstance(reviews, list) or any(not isinstance(item, dict) for item in reviews):
            raise TypeError("manual adoption requires complete public review history")
        matches = [item for item in comments if immutable_database_id(item) == trigger_id]
        if len(matches) != 1:
            raise ValueError("manual trigger identity is missing or duplicated")
        comment = matches[0]
        author = _comment_author_login(comment)
        created = comment.get("createdAt")
        url = comment.get("url")
        if (
            not isinstance(author, str)
            or not author.strip()
            or is_coderabbit_login(author)
            or normalize_command(comment.get("body") or "") != FULL_COMMAND
            or parse_timestamp(created) is None
            or not isinstance(url, str)
            or not url
        ):
            raise ValueError("manual trigger is not an immutable human full-review command")
        record = {
            "schema_version": 2,
            "status": "posted",
            "repository": repo,
            "pr_number": pr_number,
            "head_sha": head_sha,
            "anchor": dict(anchor),
            "trigger": {
                "id": trigger_id,
                "created_at": created,
                "url": url,
                "author_login": author,
                "type": "full",
                "command": FULL_COMMAND,
            },
            "adoption": {
                "source": "public_manual_request",
                "at": utc_now(),
                "response_id": None,
            },
        }
        state = trigger_state(repo, pr_number, payload, record, record_path)
        if state.state != "completed" or state.attributed is not True or state.response_id is None:
            raise ValueError(f"manual request lacks a unique completed review: {state.state}")
        trigger_at = parse_timestamp(created)
        response_reviews = [item for item in reviews if immutable_database_id(item) == state.response_id]
        response_comments = [item for item in comments if immutable_database_id(item) == state.response_id]
        if (
            not response_reviews
            and len(response_comments) == 1
            and isinstance(response_comments[0].get("body"), str)
            and _is_finished_action_response(response_comments[0]["body"], allow_action_wrapper=True)
        ):
            exact_zero_summary = _zero_finding_summary(payload, head_sha, trigger_at, state.response_id, None)
            if (
                exact_zero_summary is None
                or (_scope_head(exact_zero_summary.get("body", "")) or "").casefold() != head_sha.casefold()
            ):
                exact_zero_summary = provider_format_zero_finding_summary(
                    payload, head_sha, trigger_at, state.response_id
                )
            if exact_zero_summary is None:
                raise ValueError("finished-reply-only zero result lacks exact public proof of the reviewed head")
        later_commands = [
            timestamp
            for item in comments
            if immutable_database_id(item) != trigger_id
            and not is_coderabbit_login(_comment_author_login(item))
            and normalize_command(item.get("body") or "") == FULL_COMMAND
            and (timestamp := parse_timestamp(item.get("createdAt"))) is not None
            and timestamp > trigger_at
        ]
        next_trigger = min(later_commands, default=None)
        if next_trigger is not None:
            raise ValueError("manual adoption requires the latest public full-review command")
        in_window = []
        for review in reviews:
            if not is_coderabbit_login((review.get("author") or {}).get("login")) or review.get("state") == "DISMISSED":
                continue
            submitted = parse_timestamp(review.get("submittedAt"))
            if submitted is None:
                raise ValueError("manual adoption found a CodeRabbit review without submission time")
            if submitted > trigger_at and (next_trigger is None or submitted < next_trigger):
                in_window.append(immutable_database_id(review))
        if len(in_window) > 1 or (in_window and in_window[0] != state.response_id):
            raise ValueError("manual request has ambiguous CodeRabbit review responses")
        record["adoption"]["response_id"] = state.response_id

        if unresolved_preceding_full_trigger(repo, pr_number, payload, trigger_id, record_common):
            raise ValueError("an earlier full-review command is unresolved before the manual request")

        for candidate in trigger_record_paths(repo, pr_number, record_common):
            try:
                loaded = load_trigger_record(candidate, repo, pr_number)
            except (OSError, ValueError, TypeError) as error:
                raise ValueError(f"cannot inspect existing trigger record {candidate}: {error}") from error
            if (loaded.get("trigger") or {}).get("id") == trigger_id:
                raise ValueError("manual trigger already has a durable record")
        if record_path.exists():
            previous = load_trigger_record(record_path, repo, pr_number)
            previous_at = parse_timestamp(previous["trigger"].get("created_at"))
            if previous_at is None or previous_at >= trigger_at:
                raise ValueError("current Hosted trigger is not older than the manual request")
            previous_state = trigger_state(repo, pr_number, payload, previous, record_path)
            if previous_state.state not in {"completed", "noop", "failed", "rate_limited", "retired"}:
                raise ValueError("current Hosted trigger must finish before manual adoption")
            old_id = previous["trigger"]["id"]
            archived = record_path.with_name(f"trigger-{old_id}.json")
            if archived.exists():
                raise ValueError("existing Hosted trigger archive already exists")
            os.replace(record_path, archived)
        _write_json_exclusive(record_path, record)
    finally:
        fcntl.flock(descriptor, fcntl.LOCK_UN)
        os.close(descriptor)
    return {
        "status": "adopted",
        "pr": pr_number,
        "trigger_id": trigger_id,
        "response_id": state.response_id,
        "head": head_sha,
        "record": str(record_path),
    }


def _adopt_posting_reservation_locked(
    path: str | Path,
    repo: str,
    pr_number: int,
    expected_head_sha: str,
    payload: dict[str, Any],
) -> dict[str, Any]:
    """Adopt one uniquely observed POST result for a durable pre-POST reservation.

    No comment is posted here. Missing attempt metadata, no matching live comment,
    multiple possible comments, or any identity mismatch leave the reservation
    untouched and fail closed.
    """

    if not EXACT_SHA.fullmatch(expected_head_sha):
        raise ValueError("posting recovery requires an exact expected head")
    record_path = Path(path)
    record = load_trigger_reservation(record_path, repo, pr_number)
    if record.get("status") != "posting" or isinstance(record.get("trigger"), dict):
        raise ValueError("posting recovery requires an unresolved pre-POST reservation")
    captured_head = record.get("head_sha")
    if not isinstance(captured_head, str) or not EXACT_SHA.fullmatch(captured_head):
        raise ValueError("posting reservation has no exact captured head")
    started_at = parse_timestamp(record.get("posting_started_at"))
    actor_login = record.get("posting_actor_login")
    if started_at is None or not isinstance(actor_login, str) or not actor_login.strip():
        raise ValueError("posting reservation has no trusted POST attempt identity")
    comment_id_floor = record.get("posting_comment_id_floor")
    if type(comment_id_floor) is not int or comment_id_floor < 0:
        raise ValueError("posting reservation has no trusted comment-ID floor")

    try:
        pr = payload["data"]["repository"]["pullRequest"]
    except (KeyError, TypeError) as error:
        raise TypeError("live pull-request data is unavailable for posting recovery") from error
    if not isinstance(pr, dict):
        raise TypeError("live pull-request data is unavailable for posting recovery")
    current_head = pr.get("headRefOid")
    if not isinstance(current_head, str) or not EXACT_SHA.fullmatch(current_head):
        raise ValueError("live pull-request head is not an exact SHA during posting recovery")
    # HostedRunner supplies the live head, while direct recovery callers may
    # bind the captured reservation head. Either exact identity is valid here;
    # the reservation's own captured head is always retained below.
    if expected_head_sha.casefold() not in {captured_head.casefold(), current_head.casefold()}:
        raise ValueError("recovery head matches neither the posting reservation nor the live pull request")
    comment_connection = pr.get("comments")
    comments = comment_connection.get("nodes") if isinstance(comment_connection, dict) else None
    if not isinstance(comments, list):
        raise TypeError("complete pull-request comment history is unavailable for posting recovery")

    candidates: dict[int, dict[str, Any]] = {}
    for item in comments:
        if not isinstance(item, dict):
            raise TypeError("live comment history contains an invalid comment")
        comment_id = immutable_database_id(item)
        if comment_id is None:
            raise ValueError("live comment history contains a comment without immutable identity")
        if comment_id <= comment_id_floor:
            continue
        body = item.get("body")
        if not isinstance(body, str) or normalize_command(body) != FULL_COMMAND:
            continue
        created_at = item.get("createdAt")
        if parse_timestamp(created_at) is None:
            raise ValueError("a possible POST result has unknown time")
        author_login_value = _comment_author_login(item)
        if not isinstance(author_login_value, str) or not author_login_value.strip():
            raise ValueError("a possible POST result has unknown actor")
        if author_login_value.casefold() != actor_login.casefold():
            raise ValueError("ambiguous full-review command is present in live history")
        url = item.get("url") or item.get("html_url")
        if not isinstance(created_at, str) or not isinstance(url, str) or not url:
            raise ValueError("a possible POST result has incomplete live identity")
        prior = candidates.get(comment_id)
        if prior is not None and prior != item:
            raise ValueError("live comment history contains conflicting copies of a possible POST result")
        candidates[comment_id] = item
    if len(candidates) != 1:
        raise ValueError("posting recovery requires exactly one live command matching the reserved attempt")

    comment_id, comment = next(iter(candidates.items()))
    trigger = {
        "id": comment_id,
        "created_at": comment["createdAt"],
        "url": comment.get("url") or comment.get("html_url"),
        "author_login": _comment_author_login(comment),
        "type": "full",
        "command": FULL_COMMAND,
    }
    current = load_trigger_reservation(record_path, repo, pr_number)
    if json.dumps(current, sort_keys=True) != json.dumps(record, sort_keys=True):
        raise ValueError("posting reservation changed during recovery")
    advanced = current_head.casefold() != captured_head.casefold()
    updated = {
        **record,
        "status": "posted_boundary_changed" if advanced else "posted",
        "trigger": trigger,
        "recovery": {
            "action": "adopt_observed_post",
            "at": utc_now(),
            "comment_id": comment_id,
            "captured_head_sha": captured_head,
            "live_head_sha": current_head,
        },
    }
    atomic_write_json(record_path, updated)
    return updated


def adopt_posting_reservation(
    path: str | Path,
    repo: str,
    pr_number: int,
    expected_head_sha: str,
    payload: dict[str, Any],
) -> dict[str, Any]:
    """Acquire the Hosted reservation lock and adopt one exact observed POST result."""

    descriptor = _with_lock(default_trigger_record_path(repo, pr_number))
    try:
        return _adopt_posting_reservation_locked(path, repo, pr_number, expected_head_sha, payload)
    finally:
        fcntl.flock(descriptor, fcntl.LOCK_UN)
        os.close(descriptor)


def recover_prepost_reservation(
    path: str | Path,
    repo: str,
    pr_number: int,
    expected_head_sha: str,
    reason: str,
    confirmed_not_posted: bool,
    fetch_payload: Callable[[], dict[str, Any]],
) -> dict[str, Any]:
    """Archive a reservation only after an operator-confirmed, live no-post check.

    The callback is invoked while holding the same per-PR lock as Hosted posting,
    so its result is fresh with respect to this controller's POST path. The
    current record is re-read after the fetch and retained in a private audit
    file; this function never submits a GitHub request.
    """

    if (
        not isinstance(expected_head_sha, str)
        or not EXACT_SHA.fullmatch(expected_head_sha)
        or not isinstance(reason, str)
        or not reason.strip()
        or len(reason) > 240
        or any(ord(char) < 0x20 for char in reason)
    ):
        raise ValueError("invalid pre-POST recovery request")
    if confirmed_not_posted is not True:
        raise ValueError("pre-POST recovery requires --confirmed-not-posted operator assertion")
    if not callable(fetch_payload):
        raise TypeError("pre-POST recovery requires a live pull-request fetch callback")

    record_path = Path(path)
    descriptor = _with_lock(default_trigger_record_path(repo, pr_number))
    try:
        current_paths = current_trigger_record_paths(repo, pr_number)
        if len(current_paths) != 1 or current_paths[0] != record_path:
            raise ValueError("pre-POST recovery requires exactly one current reservation at the selected path")
        record = load_trigger_reservation(record_path, repo, pr_number)
        if record.get("status") != "posting" or isinstance(record.get("trigger"), dict):
            raise ValueError("pre-POST recovery requires an unresolved posting reservation")
        captured_head = record.get("head_sha")
        if not isinstance(captured_head, str) or not EXACT_SHA.fullmatch(captured_head):
            raise ValueError("posting reservation has no exact captured head")
        if captured_head.casefold() != expected_head_sha.casefold():
            raise ValueError("posting reservation head does not match recovery request")

        started_at_text = record.get("posting_started_at")
        started_at = parse_timestamp(started_at_text)
        actor_login = record.get("posting_actor_login")
        if (
            started_at is None
            or not isinstance(started_at_text, str)
            or not isinstance(actor_login, str)
            or not actor_login.strip()
        ):
            raise ValueError("posting reservation has no trusted original actor/time identity")
        comment_id_floor = record.get("posting_comment_id_floor")
        if type(comment_id_floor) is not int or comment_id_floor < 0:
            raise ValueError("posting reservation has no trusted comment-ID floor")

        payload = fetch_payload()
        if not isinstance(payload, dict):
            raise TypeError("live pull-request response is not an object")
        try:
            pr = payload["data"]["repository"]["pullRequest"]
        except (KeyError, TypeError) as error:
            raise TypeError("live pull-request data is unavailable for pre-POST recovery") from error
        if not isinstance(pr, dict):
            raise TypeError("live pull-request data is unavailable for pre-POST recovery")
        current_head = pr.get("headRefOid")
        if not isinstance(current_head, str) or not EXACT_SHA.fullmatch(current_head):
            raise ValueError("live pull-request head is not an exact SHA during pre-POST recovery")
        comment_connection = pr.get("comments")
        comments = comment_connection.get("nodes") if isinstance(comment_connection, dict) else None
        if not isinstance(comments, list):
            raise TypeError("complete paginated pull-request comment history is unavailable")

        for item in comments:
            if not isinstance(item, dict):
                raise TypeError("live comment history contains an invalid comment; refusing recovery")
            comment_id = immutable_database_id(item)
            if comment_id is None:
                raise ValueError("live comment history contains a comment without immutable identity")
            if comment_id <= comment_id_floor:
                continue
            body = item.get("body")
            if not isinstance(body, str) or normalize_command(body) != FULL_COMMAND:
                continue
            created = parse_timestamp(item.get("createdAt"))
            if created is None:
                raise ValueError("a full-review command has unknown time; refusing pre-POST recovery")
            observed_actor = _comment_author_login(item)
            if isinstance(observed_actor, str) and observed_actor.casefold() == actor_login.casefold():
                raise ValueError("matching full-review command is already present in live history")
            raise ValueError("ambiguous full-review command is present in live history")

        current = load_trigger_reservation(record_path, repo, pr_number)
        if json.dumps(current, sort_keys=True) != json.dumps(record, sort_keys=True):
            raise ValueError("posting reservation changed during pre-POST recovery")

        reservation_key = json.dumps(
            {
                "repository": repo,
                "pr_number": pr_number,
                "head_sha": record["head_sha"].casefold(),
                "posting_started_at": started_at_text,
                "posting_actor_login": actor_login.casefold(),
            },
            sort_keys=True,
            separators=(",", ":"),
        )
        archive_id = hashlib.sha256(reservation_key.encode("utf-8")).hexdigest()[:20]
        archive_path = record_path.with_name(f"prepost-abandoned-{archive_id}.json")
        updated = {
            **record,
            "status": "abandoned_prepost",
            "recovery": {
                "action": "operator_confirmed_prepost_abandon",
                "at": utc_now(),
                "reason": reason.strip(),
                "confirmed_not_posted": True,
                "expected_head_sha": expected_head_sha,
                "captured_head_sha": captured_head,
                "live_head_sha": current_head,
                "posting_started_at": started_at_text,
                "posting_actor_login": actor_login,
                "live_comment_history": "complete_paginated_no_candidate",
            },
        }
        # A failed SQL transition or unlink may leave this immutable audit
        # beside the still-active reservation. Reuse only its exact proof on
        # retry; never replace it or accept a conflicting file at this path.
        if archive_path.exists() or archive_path.is_symlink():
            archived = _matching_prepost_audit(archive_path, record, updated)
            audit_reason = archived["recovery"]["reason"]
        else:
            _write_json_exclusive(archive_path, updated)
            audit_reason = reason.strip()
        current = load_trigger_reservation(record_path, repo, pr_number)
        if json.dumps(current, sort_keys=True) != json.dumps(record, sort_keys=True):
            raise ValueError("posting reservation changed before pre-POST archival")
        _finish_recovered_attempt(record_path, repo, pr_number, record)
        os.unlink(record_path)
    finally:
        fcntl.flock(descriptor, fcntl.LOCK_UN)
        os.close(descriptor)
    return {
        "operation": "recover_prepost_reservation",
        "status": "abandoned_no_post",
        "repository": repo,
        "pr_number": pr_number,
        "head_sha": expected_head_sha,
        "captured_head_sha": captured_head,
        "current_head_sha": current_head,
        "audit_path": str(archive_path),
        "reason": audit_reason,
        "confirmed_not_posted": True,
    }


def _matching_prepost_audit(path: Path, record: dict[str, Any], expected: dict[str, Any]) -> dict[str, Any]:
    if path.is_symlink():
        raise ValueError("pre-POST recovery audit path is a symbolic link")
    info = path.stat(follow_symlinks=False)
    if not stat.S_ISREG(info.st_mode) or info.st_size > 64 * 1024:
        raise ValueError("pre-POST recovery audit path is not a bounded regular file")
    try:
        archived = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, UnicodeError, json.JSONDecodeError) as error:
        raise ValueError("existing pre-POST recovery audit is unreadable") from error
    if not isinstance(archived, dict) or archived.get("status") != "abandoned_prepost":
        raise ValueError("existing pre-POST recovery audit conflicts with the reservation")
    archived_record = {key: value for key, value in archived.items() if key not in {"status", "recovery"}}
    expected_record = {key: value for key, value in record.items() if key not in {"status", "recovery"}}
    archived_recovery = archived.get("recovery")
    expected_recovery = expected["recovery"]
    if (
        archived_record != expected_record
        or not isinstance(archived_recovery, dict)
        or not isinstance(archived_recovery.get("live_head_sha"), str)
        or not EXACT_SHA.fullmatch(archived_recovery["live_head_sha"])
        or {key: value for key, value in archived_recovery.items() if key not in {"at", "reason", "live_head_sha"}}
        != {key: value for key, value in expected_recovery.items() if key not in {"at", "reason", "live_head_sha"}}
        or parse_timestamp(archived_recovery.get("at")) is None
        or not isinstance(archived_recovery.get("reason"), str)
        or not archived_recovery["reason"]
    ):
        raise ValueError("existing pre-POST recovery audit conflicts with the reservation")
    return archived


def _finish_recovered_attempt(path: Path, repo: str, pr_number: int, record: dict[str, Any]) -> None:
    """Close the exact SQLite attempt when recovery proves that POST was never issued."""

    attempt_id = record.get("sqlite_attempt_id")
    if not isinstance(attempt_id, str) or not attempt_id:
        return
    common = _trigger_record_common_for_path(path, repo, pr_number)
    if common is None:
        return
    selected_state = state_path(common)
    if not selected_state.is_dir():
        return
    database = sqlite_state_path(selected_state)
    try:
        database_stat = database.stat(follow_symlinks=False)
    except FileNotFoundError:
        # SQLite capture is optional at the POST boundary; the validated
        # reservation and no-POST audit remain the recovery authority.
        return
    if database.is_symlink() or not stat.S_ISREG(database_stat.st_mode):
        raise ValueError("linked Hosted SQLite attempt database is not a regular file")
    records = SqliteReviewRecords(database)
    try:
        attempt = records.attempt(attempt_id)
    except AttemptNotFound:
        return
    if (
        attempt["source_pr"] != pr_number
        or attempt["channel"] != "hosted"
        or attempt["candidate_sha"].casefold() != str(record.get("head_sha", "")).casefold()
    ):
        raise ValueError("linked Hosted SQLite attempt does not match the recovered reservation")
    if attempt["state"] == "failed" and attempt["run_id"] is None:
        return
    if attempt["state"] != "started":
        raise ValueError("linked Hosted SQLite attempt conflicts with confirmed no-POST recovery")
    records.finish_attempt(
        attempt_id,
        state="failed",
        finished_at=utc_now(),
        diagnostic="Hosted POST was confirmed not issued during pre-POST recovery",
    )


def _substantive(body: str) -> bool:
    return any(marker in body for marker in SUBSTANTIVE_MARKERS)


_PUBLICATION_UUID = r"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"
_PUBLICATION_MARKER = re.compile(
    rf"<!-- coderabbit-review-publication v1 publication=({_PUBLICATION_UUID}) "
    rf"attempt=({_PUBLICATION_UUID}) batch=([1-9][0-9]*)/([1-9][0-9]*) -->"
)


def review_publication_marker(body: Any) -> tuple[str, str, int, int] | None:
    """Read one explicit provider marker, excluding quoted and fenced examples."""
    if not isinstance(body, str):
        return None
    visible = _without_fenced_code(_unquoted(body))
    if "coderabbit-review-publication" not in visible:
        return None
    matches = list(_PUBLICATION_MARKER.finditer(visible))
    if len(matches) != 1 or visible.count("coderabbit-review-publication") != 1:
        raise ValueError("Hosted publication marker is malformed or duplicated")
    publication, attempt, ordinal, total = matches[0].groups()
    if not 1 <= int(ordinal) <= int(total) <= 200:
        raise ValueError("Hosted publication batch ordinal is invalid")
    return publication, attempt, int(ordinal), int(total)


def review_publications(
    reviews: Sequence[dict[str, Any]], head: str, after: datetime, before: datetime | None
) -> list[dict[str, Any]]:
    """Validate explicit batches within one authenticated exact-head request window."""
    groups: dict[str, list[tuple[dict[str, Any], tuple[str, str, int, int]]]] = {}
    seen_ids: set[int] = set()
    for review in reviews:
        if not is_coderabbit_login((review.get("author") or {}).get("login")):
            continue
        submitted = strict_provider_timestamp(review.get("submittedAt"))
        if submitted is not None and (submitted <= after or (before is not None and submitted >= before)):
            continue
        marker = review_publication_marker(review.get("body"))
        if marker is None:
            continue
        if submitted is None:
            raise ValueError("Hosted publication batch has no immutable submission time")
        identity = immutable_database_id(review)
        if identity is None or identity in seen_ids:
            raise ValueError("Hosted publication repeats or lacks an immutable review identity")
        seen_ids.add(identity)
        groups.setdefault(marker[0], []).append((review, marker))
    publications = []
    for publication, entries in groups.items():
        attempts = {marker[1] for _, marker in entries}
        totals = {marker[3] for _, marker in entries}
        ordinals = [marker[2] for _, marker in entries]
        identities = [immutable_database_id(review) for review, _ in entries]
        if (
            len(attempts) != 1
            or len(totals) != 1
            or len(ordinals) != len(set(ordinals))
            or None in identities
            or len(identities) != len(set(identities))
            or any(
                review.get("state") not in {"COMMENTED", "APPROVED", "CHANGES_REQUESTED"}
                or not isinstance((review.get("commit") or {}).get("oid"), str)
                or review["commit"]["oid"].casefold() != head.casefold()
                for review, _ in entries
            )
        ):
            raise ValueError("Hosted publication batches have conflicting immutable identities")
        ordered = sorted(entries, key=lambda entry: entry[1][2])
        primary = ordered[0][0]
        total = next(iter(totals))
        complete = set(ordinals) == set(range(1, total + 1))
        if complete and (
            not _substantive(primary.get("body") or "")
            or any(_substantive(review.get("body") or "") for review, marker in ordered if marker[2] != 1)
        ):
            raise ValueError("Hosted publication has no unique substantive primary batch")
        latest = max(
            (review for review, _ in ordered), key=lambda review: strict_provider_timestamp(review["submittedAt"])
        )
        publications.append(
            {
                "publication": publication,
                "attempt": next(iter(attempts)),
                "total": total,
                "complete": complete,
                "primary": primary,
                "reviews": [review for review, _ in ordered],
                "review_ids": tuple(immutable_database_id(review) for review, _ in ordered),
                "finished_at": latest["submittedAt"],
            }
        )
    return publications


def validate_publication_comments(
    publication: dict[str, Any], pull_request: dict[str, Any], head: str, after: datetime
) -> None:
    """Require the primary provider count and every owned actionable root.

    Summary-only outside-diff and duplicate sections are separate evidence and
    do not contribute to the provider's attached actionable-comment count.
    """
    body = _without_fenced_code(_unquoted(publication["primary"].get("body") or ""))
    counts = re.findall(r"^[ \t]*\*\*Actionable comments posted: ([0-9]+)\*\*[ \t]*\r?$", body, re.MULTILINE)
    if len(counts) != 1 or int(counts[0]) > 200:
        raise ValueError("Hosted publication has no unique bounded actionable-comment count")
    expected = int(counts[0])
    finished = strict_provider_timestamp(publication["finished_at"])
    owned_ids: set[int] = set()
    for thread in (pull_request.get("reviewThreads") or {}).get("nodes", []):
        nodes = (thread.get("comments") or {}).get("nodes", [])
        if not nodes:
            continue
        comment = nodes[0]
        parent = immutable_database_id(comment.get("pullRequestReview") or {})
        created = strict_provider_timestamp(comment.get("createdAt"))
        login = (comment.get("author") or {}).get("login")
        if parent not in publication["review_ids"]:
            if parent is None and is_coderabbit_login(login) and (created is None or after < created <= finished):
                raise ValueError("Hosted publication root has no immutable parent review")
            continue
        if not is_coderabbit_login(login):
            raise ValueError("Hosted publication owned root has a different author")
        if created is None or not after < created <= finished:
            raise ValueError("Hosted publication owned root has no valid immutable window timestamp")
        original_commit = (comment.get("originalCommit") or {}).get("oid")
        if not isinstance(original_commit, str) or original_commit.casefold() != head.casefold():
            raise ValueError("Hosted publication owned root has a different or missing original commit")
        identity = immutable_database_id(comment)
        if identity is None or identity in owned_ids or not isinstance(comment.get("body"), str):
            raise ValueError("Hosted publication owned root has incomplete or duplicated immutable identity")
        owned_ids.add(identity)
    if len(owned_ids) != expected:
        raise ValueError("Hosted publication owned actionable-comment evidence is incomplete or conflicting")


def _active_only_acknowledgement(body: str) -> bool:
    visible = _unquoted(body)
    return bool(ACTIVE_PATTERN.search(visible)) and not (
        FINISHED_REVIEW_PATTERN.search(visible)
        or FAILED_PATTERN.search(visible)
        or _substantive(visible)
        or NOOP_MARKER in visible
        or REVIEW_LIMIT_MARKER in visible
        or RATE_LIMIT_PATTERN.search(visible)
        or any(pattern.search(visible) for pattern in POSITIVE_FINDING_COUNT_PATTERNS)
    )


def _unquoted(body: str) -> str:
    return "\n".join(line for line in body.splitlines() if not line.lstrip().startswith(">"))


def _without_fenced_code(body: str) -> str:
    visible: list[str] = []
    fence: tuple[str, int] | None = None
    for line in body.splitlines():
        if fence is not None:
            marker, minimum_length = fence
            closing = re.fullmatch(rf" {{0,3}}{re.escape(marker)}{{{minimum_length},}}[ \t]*", line)
            if closing:
                fence = None
            continue
        if line.lstrip().startswith(">"):
            continue
        opening = re.match(r" {0,3}(`{3,}|~{3,})(.*)$", line)
        if opening:
            marker, info = opening.groups()
            if marker[0] != "`" or "`" not in info:
                fence = (marker[0], len(marker))
                continue
        visible.append(line)
    return "\n".join(visible)


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
    match = RATE_LIMIT_PATTERN.search(_without_fenced_code(_unquoted(body)))
    if not match:
        return None
    amount, unit = int(match.group(1)), match.group(2).lower()
    return created + timedelta(
        **({"seconds" if unit.startswith("second") else "minutes" if unit.startswith("minute") else "hours": amount})
    )


def is_rate_limit_reply_body(body: Any) -> bool:
    """Recognize provider rate-limit prose without needing a trusted timestamp."""

    if not isinstance(body, str):
        return False
    unquoted = _without_fenced_code(_unquoted(body))
    return (
        REVIEW_LIMIT_MARKER in unquoted
        or RATE_LIMIT_PATTERN.search(unquoted) is not None
        or unquoted.strip().lower().startswith("review rate limited")
        or WRAPPED_RATE_LIMIT_REPLY_PATTERN.fullmatch(unquoted) is not None
    )


def rate_limit_cooldown(
    body: str,
    response_created_at: str | datetime | None,
    *,
    now: datetime | None = None,
) -> tuple[datetime | None, str]:
    """Return the provider deadline or bounded local fallback for a rate-limit reply.

    The fallback is anchored to the immutable response creation time. Invalid,
    missing, naive, or future timestamps stay unresolved so callers fail closed.
    """

    created = strict_provider_timestamp(response_created_at)
    if created is None:
        return None, "unknown"
    reference = now or datetime.now(timezone.utc)
    if reference.tzinfo is None or reference.utcoffset() is None:
        return None, "unknown"
    reference = reference.astimezone(timezone.utc)
    if created is None or created > reference:
        return None, "unknown"

    provider_reset = _rate_limit(body, created)
    if provider_reset is not None:
        return provider_reset, "provider_reset"
    if is_rate_limit_reply_body(body):
        return created + UNKNOWN_RATE_LIMIT_BACKOFF, "local_retry_backoff"
    return None, "none"


def strict_provider_timestamp(value: str | datetime | None) -> datetime | None:
    """Parse a provider timestamp only when its original value includes a timezone."""

    if isinstance(value, datetime):
        parsed = value
    else:
        try:
            parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
        except (AttributeError, TypeError, ValueError):
            return None
    if parsed.tzinfo is None or parsed.utcoffset() is None:
        return None
    return parsed.astimezone(timezone.utc)


def rate_limit_window_cooldown(
    responses: Sequence[Mapping[str, Any]],
    *,
    now: datetime | None = None,
) -> tuple[datetime | None, str]:
    """Choose the longest cooldown among already-attributed replies in one trigger window."""

    deadlines: list[tuple[datetime, str]] = []
    for response in responses:
        body = response.get("body")
        if not isinstance(body, str) or not is_rate_limit_reply_body(body):
            continue
        deadline, basis = rate_limit_cooldown(body, response.get("createdAt"), now=now)
        if basis == "unknown":
            return None, "unknown"
        if deadline is not None:
            deadlines.append((deadline, basis))
    if not deadlines:
        return None, "none"
    return max(deadlines, key=lambda item: (item[0], item[1] == "provider_reset"))


def _zero_finding_summary(
    payload: dict[str, Any],
    head: str,
    after: datetime,
    response_id: int | None,
    before: datetime | None,
) -> dict[str, Any] | None:
    """Link the legacy zero sentence only within one empty, complete trigger window."""

    if type(response_id) is not int or response_id <= 0:
        return None
    pr = (payload.get("data") or {}).get("repository", {}).get("pullRequest")
    if not isinstance(pr, dict):
        return None
    connections: dict[str, list[dict[str, Any]]] = {}
    for name in ("comments", "reviews", "reviewThreads"):
        connection = pr.get(name)
        nodes = connection.get("nodes") if isinstance(connection, dict) else None
        if not isinstance(nodes, list) or any(not isinstance(item, dict) for item in nodes):
            return None
        connections[name] = nodes
    matches: list[dict[str, Any]] = []
    for comment in connections["comments"]:
        if not is_coderabbit_login((comment.get("author") or {}).get("login")):
            continue
        body = comment.get("body")
        created = parse_timestamp(comment.get("createdAt"))
        updated = parse_timestamp(comment.get("updatedAt"))
        if not isinstance(body, str) or created is None or updated is None:
            return None
        if updated <= after or (before is not None and updated >= before):
            continue
        if created > after and _active_only_acknowledgement(body):
            continue
        # An edited standing summary may describe an earlier same-head run.
        # Its edit time alone cannot attribute it to this trigger.
        if created <= after or (before is not None and created >= before):
            return None
        if immutable_database_id(comment) == response_id:
            continue
        if (
            "No actionable comments were generated in the recent review." in body
            and _matches_head(body, head)
            and not _summary_has_explicit_incompleteness(body)
            and not any(pattern.search(_unquoted(body)) for pattern in POSITIVE_FINDING_COUNT_PATTERNS)
            and _rate_limit(body, updated) is None
        ):
            matches.append(comment)
        else:
            return None
    if len(matches) != 1:
        return None

    def in_window(value: Any) -> bool | None:
        when = parse_timestamp(value)
        return None if when is None else when > after and (before is None or when < before)

    for review in connections["reviews"]:
        if not is_coderabbit_login((review.get("author") or {}).get("login")):
            continue
        submitted = in_window(review.get("submittedAt"))
        if submitted is None or submitted:
            return None
    for thread in connections["reviewThreads"]:
        comments = thread.get("comments")
        nodes = comments.get("nodes") if isinstance(comments, dict) else None
        if not isinstance(nodes, list) or any(not isinstance(item, dict) for item in nodes):
            return None
        for item in nodes:
            if not is_coderabbit_login((item.get("author") or {}).get("login")):
                continue
            created = in_window(item.get("createdAt"))
            updated = in_window(item.get("updatedAt"))
            if created is None or updated is None or created or updated:
                return None
    return matches[0]


def _summary_proves_complete_zero_findings(body: str) -> bool:
    """Require complete file coverage and reject any positive finding count or claim."""

    if _summary_has_explicit_incompleteness(body):
        return False
    text = _unquoted(body)
    if any(pattern.search(text) for pattern in POSITIVE_FINDING_COUNT_PATTERNS):
        return False
    return _summary_proves_complete_file_coverage(text) and any(
        pattern.search(text) for pattern in ZERO_FINDING_PATTERNS
    )


def _reviewed_label_counts(text: str) -> set[int]:
    """Return reviewed counts without treating "not reviewed" as reviewed."""

    reviewed_text = FILE_NOT_REVIEWED_COUNT.sub(" ", text)
    return {int(match.group(1)) for match in FILE_REVIEWED_LABEL_COUNT.finditer(reviewed_text)}


def _summary_has_explicit_incomplete_coverage(body: str) -> bool:
    """Require positive, quantitative evidence that the review omitted files."""

    text = _unquoted(body)
    if any(count > 0 for count in (int(match.group(1)) for match in FILE_NOT_REVIEWED_COUNT.finditer(text))):
        return True

    selected_counts = {int(match.group(1)) for match in FILE_SELECTED_COUNT.finditer(text)}
    reviewed_counts = _reviewed_label_counts(text)
    if len(selected_counts) == len(reviewed_counts) == 1 and next(iter(reviewed_counts)) < next(iter(selected_counts)):
        return True

    ratios = [tuple(map(int, match.groups())) for match in FILE_REVIEWED_RATIO.finditer(text)]
    ratios.extend(tuple(map(int, match.groups())) for match in FILE_COVERAGE_COUNT.finditer(text))
    return any(reviewed < selected for reviewed, selected in ratios)


def _is_finished_action_response(body: str, *, allow_action_wrapper: bool) -> bool:
    """Match the plain finish reply or one exact, provider-format action wrapper."""

    text = _unquoted(body).strip()
    if re.fullmatch(r"full\s+review\s+finished\.", text, re.IGNORECASE):
        return True
    if not allow_action_wrapper:
        return False
    lines = [line.strip() for line in text.splitlines() if line.strip()]
    return (
        len(lines) == 6
        and lines[0] == "<!-- This is an auto-generated reply by CodeRabbit -->"
        and re.fullmatch(
            re.escape(COMMAND_INVOCATION_MARKER) + r"\s+v2:[0-9a-f]{64}\s+-->",
            lines[1],
            re.IGNORECASE,
        )
        is not None
        and lines[2] == "<details>"
        and lines[3] == "<summary>✅ Action performed</summary>"
        and lines[4] == "Full review finished."
        and lines[5] == "</details>"
    )


_ADDRESSED_COMMIT_ANNOTATION = re.compile(
    r"(?:\r?\n){2}✅ Addressed in commits ([0-9a-f]{7,40}) to ([0-9a-f]{7,40})\Z",
    re.IGNORECASE,
)


def _archived_completed_thread_bodies(
    repo: str,
    pr_number: int,
    head: str,
    response_id: int,
    response_created_at: datetime,
    record: Mapping[str, Any] | None,
    current_record_path: str | Path | None,
) -> dict[int, tuple[str, str]]:
    """Read immutable prior Hosted thread bodies for exact trigger attribution."""

    if not isinstance(record, Mapping):
        return {}
    current_attempt_id = record.get("sqlite_attempt_id")
    trigger = record.get("trigger")
    trigger_id = trigger.get("id") if isinstance(trigger, Mapping) else None
    trigger_at = parse_timestamp(trigger.get("created_at")) if isinstance(trigger, Mapping) else None
    if (
        not isinstance(current_attempt_id, str)
        or not current_attempt_id
        or type(trigger_id) is not int
        or trigger_id <= 0
        or trigger_at is None
        or type(response_id) is not int
        or response_id <= 0
        or not isinstance(record.get("head_sha"), str)
        or record["head_sha"].casefold() != head.casefold()
    ):
        return {}

    try:
        selected_record = (
            Path(current_record_path)
            if current_record_path is not None
            else default_trigger_record_path(repo, pr_number)
        )
        if selected_record.is_symlink():
            return {}
        common = _trigger_record_common_for_path(selected_record, repo, pr_number)
        if common is None:
            return {}
        selected_state = state_path(common)
        if selected_state.is_symlink() or not selected_state.is_dir():
            return {}
        database = sqlite_state_path(selected_state)
        database_stat = database.stat(follow_symlinks=False)
        if database.is_symlink() or not stat.S_ISREG(database_stat.st_mode):
            return {}
    except (OSError, TypeError, ValueError):
        return {}

    try:
        records = SqliteReviewRecords(database)
        current_attempt = records.attempt(current_attempt_id)
        attempt_history = records.attempt_history(pr_number)
        current_history = [item for item in attempt_history if item.get("attempt_id") == current_attempt_id]
        current_head = current_attempt.get("candidate_sha")
        request_metadata = current_attempt.get("metadata")
        current_row = current_history[0] if len(current_history) == 1 else None
        current_started_at = parse_timestamp(current_attempt.get("started_at"))
        current_state = current_attempt.get("state")
        current_trigger_id = current_row.get("trigger_id") if current_row is not None else None
        current_response_id = current_row.get("provider_review_id") if current_row is not None else None
        current_finished_at = parse_timestamp(current_attempt.get("finished_at"))
        if (
            current_attempt.get("source_pr") != pr_number
            or current_attempt.get("channel") != "hosted"
            or not isinstance(current_head, str)
            or current_head.casefold() != head.casefold()
            or not isinstance(request_metadata, dict)
            or not isinstance(request_metadata.get("repository"), str)
            or request_metadata["repository"].casefold() != repo.casefold()
            or not isinstance(response_created_at, datetime)
            or current_started_at is None
            or current_started_at > trigger_at
            or current_row is None
            or current_row.get("state") != current_state
            or current_row.get("started_at") != current_attempt.get("started_at")
            or current_row.get("finished_at") != current_attempt.get("finished_at")
        ):
            return {}

        if current_state == "started":
            if (
                current_trigger_id not in (None, str(trigger_id))
                or current_response_id is not None
                or current_finished_at is not None
            ):
                return {}
            if records.attempt_artifacts(current_attempt_id):
                return {}
        elif current_state == "completed":
            if (
                current_trigger_id != str(trigger_id)
                or current_response_id != str(response_id)
                or current_finished_at is None
                or current_finished_at < response_created_at
                or current_attempt.get("run_id") not in (None, current_attempt_id)
            ):
                return {}
            try:
                current_artifacts = records.attempt_artifacts(current_attempt_id)
                if not {"hosted_review", "hosted_comments", "metadata"} <= current_artifacts.keys():
                    return {}
                current_metadata = json.loads(current_artifacts["metadata"])
            except (TypeError, ValueError, json.JSONDecodeError):
                return {}
            current_observed_at = (
                parse_timestamp(current_metadata.get("observed_at")) if isinstance(current_metadata, dict) else None
            )
            if (
                not isinstance(current_metadata, dict)
                or current_metadata.get("state") != "completed"
                or current_metadata.get("terminal") is not True
                or current_metadata.get("attributable") is not True
                or not isinstance(current_metadata.get("repository"), str)
                or current_metadata["repository"].casefold() != repo.casefold()
                or current_metadata.get("pull_request") != pr_number
                or not isinstance(current_metadata.get("head_sha"), str)
                or current_metadata["head_sha"].casefold() != head.casefold()
                or type(current_metadata.get("trigger_id")) is not int
                or str(current_metadata["trigger_id"]) != str(trigger_id)
                or type(current_metadata.get("response_id")) is not int
                or str(current_metadata["response_id"]) != str(response_id)
                or current_observed_at is None
                or current_observed_at != current_finished_at
            ):
                return {}
        else:
            # A terminal ambiguous or failed capture cannot be retroactively
            # promoted by a later change in the public GitHub response.
            return {}

        candidates: dict[int, list[tuple[str, str]]] = {}
        for attempt in attempt_history:
            candidate_head = attempt.get("candidate_sha")
            started_at = parse_timestamp(attempt.get("started_at"))
            finished_at = parse_timestamp(attempt.get("finished_at"))
            if (
                attempt.get("channel") != "hosted"
                or attempt.get("state") != "completed"
                or not isinstance(candidate_head, str)
                or not EXACT_SHA.fullmatch(candidate_head)
                or started_at is None
                or finished_at is None
                or started_at > finished_at
                or finished_at >= trigger_at
            ):
                continue
            attempt_id = attempt.get("attempt_id")
            provider_review_id = attempt.get("provider_review_id")
            archived_trigger_id = attempt.get("trigger_id")
            if (
                not isinstance(attempt_id, str)
                or not attempt_id
                or not isinstance(provider_review_id, str)
                or not provider_review_id.isdecimal()
                or not isinstance(archived_trigger_id, str)
                or not archived_trigger_id.isdecimal()
            ):
                continue
            artifacts = records.attempt_artifacts(attempt_id)
            try:
                metadata = json.loads(artifacts.get("metadata", ""))
                archived = json.loads(artifacts.get("hosted_comments", ""))
                reviews = json.loads(artifacts.get("hosted_review", ""))
            except (TypeError, ValueError, json.JSONDecodeError):
                continue
            observed_at = parse_timestamp(metadata.get("observed_at")) if isinstance(metadata, dict) else None
            metadata_repository = metadata.get("repository") if isinstance(metadata, dict) else None
            metadata_head = metadata.get("head_sha") if isinstance(metadata, dict) else None
            if (
                not isinstance(metadata, dict)
                or metadata.get("state") != "completed"
                or metadata.get("terminal") is not True
                or metadata.get("attributable") is not True
                or not isinstance(metadata_repository, str)
                or metadata_repository.casefold() != repo.casefold()
                or metadata.get("pull_request") != pr_number
                or not isinstance(metadata_head, str)
                or metadata_head.casefold() != candidate_head.casefold()
                or type(metadata.get("trigger_id")) is not int
                or str(metadata["trigger_id"]) != archived_trigger_id
                or type(metadata.get("response_id")) is not int
                or str(metadata["response_id"]) != provider_review_id
                or observed_at is None
                or observed_at != finished_at
                or not isinstance(archived, dict)
                or not isinstance(archived.get("comments"), list)
                or not isinstance(archived.get("review_threads"), list)
                or not isinstance(reviews, list)
            ):
                continue

            matching_reviews = [
                item
                for item in reviews
                if isinstance(item, dict)
                and immutable_database_id(item) == metadata["response_id"]
                and is_coderabbit_login((item.get("author") or {}).get("login"))
                and item.get("state") != "DISMISSED"
                and isinstance((item.get("commit") or {}).get("oid"), str)
                and (item.get("commit") or {}).get("oid").casefold() == candidate_head.casefold()
            ]
            matching_triggers = [
                item
                for item in archived["comments"]
                if isinstance(item, dict) and immutable_database_id(item) == int(archived_trigger_id)
            ]
            if (
                len(matching_reviews) != 1
                or len(matching_triggers) != 1
                or is_coderabbit_login((matching_triggers[0].get("author") or {}).get("login"))
                or normalize_command(matching_triggers[0].get("body") or "") != FULL_COMMAND
            ):
                continue
            archived_trigger_at = parse_timestamp(matching_triggers[0].get("createdAt"))
            review_submitted_at = parse_timestamp(matching_reviews[0].get("submittedAt"))
            if (
                archived_trigger_at is None
                or archived_trigger_at < started_at
                or archived_trigger_at > finished_at
                or review_submitted_at is None
                or review_submitted_at < archived_trigger_at
                or review_submitted_at > finished_at
            ):
                continue

            for thread in archived["review_threads"]:
                comments = thread.get("comments") if isinstance(thread, dict) else None
                nodes = comments.get("nodes") if isinstance(comments, dict) else None
                if not isinstance(nodes, list):
                    continue
                for item in nodes:
                    if not isinstance(item, dict) or not is_coderabbit_login((item.get("author") or {}).get("login")):
                        continue
                    comment_id = immutable_database_id(item)
                    created_at = item.get("createdAt")
                    created = parse_timestamp(created_at)
                    body = item.get("body")
                    if (
                        comment_id is None
                        or not isinstance(created_at, str)
                        or created is None
                        or created < archived_trigger_at
                        or created > finished_at
                        or not isinstance(body, str)
                    ):
                        continue
                    candidates.setdefault(comment_id, []).append((created_at, body))

        result: dict[int, tuple[str, str]] = {}
        for comment_id, baselines in candidates.items():
            if len(baselines) == 1:
                result[comment_id] = baselines[0]
    except Exception:  # noqa: BLE001 - unavailable or incompatible archive must remain fail closed
        return {}
    return result


def _addressed_thread_update_matches_archive(
    item: dict[str, Any],
    head: str,
    after: datetime,
    before: datetime | None,
    response_id: int,
    archived_bodies: Mapping[int, tuple[str, str]],
) -> bool:
    """Accept only the exact known addressed footer appended to an archived finding."""

    comment_id = immutable_database_id(item)
    if comment_id is None:
        return False
    archived = archived_bodies.get(comment_id)
    body = item.get("body")
    created_at = item.get("createdAt")
    updated = parse_timestamp(item.get("updatedAt"))
    created = parse_timestamp(created_at)
    if (
        archived is None
        or not isinstance(body, str)
        or not isinstance(created_at, str)
        or created_at != archived[0]
        or not isinstance(archived[1], str)
        or created is None
        or created >= after
        or updated is None
        or updated <= after
        or (before is not None and updated >= before)
        or type(response_id) is not int
        or response_id <= 0
    ):
        return False
    annotation = _ADDRESSED_COMMIT_ANNOTATION.search(body)
    return (
        annotation is not None
        and annotation.group(2).casefold() == head[: len(annotation.group(2))].casefold()
        and body[: annotation.start()] == archived[1]
    )


def _summary_has_explicit_incompleteness(body: str) -> bool:
    text = _unquoted(body)
    not_reviewed_counts = [int(match.group(1)) for match in FILE_NOT_REVIEWED_COUNT.finditer(text)]
    if any(count > 0 for count in not_reviewed_counts):
        return True
    for sentence in re.split(r"[.!?\n]+", text):
        for match in OPEN_ISSUE_CLAIM.finditer(sentence):
            prefix = sentence[: match.start()]
            if re.search(r"\bno\s+(?:(?:reported|\d+)\s+)*$", prefix, re.IGNORECASE):
                continue
            return True

    selected_counts = {int(match.group(1)) for match in FILE_SELECTED_COUNT.finditer(text)}
    reviewed_counts = _reviewed_label_counts(text)
    ratios = [tuple(map(int, match.groups())) for match in FILE_REVIEWED_RATIO.finditer(text)]
    if len(selected_counts) > 1 or len(reviewed_counts) > 1 or len(set(ratios)) > 1:
        return True
    if selected_counts and reviewed_counts and next(iter(selected_counts)) != next(iter(reviewed_counts)):
        return True
    if any(reviewed != selected for reviewed, selected in ratios):
        return True
    text_without_explicit_zero_omissions = FILE_NOT_REVIEWED_COUNT.sub(" ", text)
    return any(
        _has_explicit_file_omission(sentence) or _has_explicit_incomplete_file_coverage(sentence)
        for sentence in re.split(r"[.!?\n]+", text_without_explicit_zero_omissions)
    )


def _has_explicit_incomplete_file_coverage(sentence: str) -> bool:
    coverage_text = _without_explicit_zero_file_omissions(sentence)
    return bool(
        INCOMPLETE_FILE_COVERAGE.search(coverage_text)
        and (
            any(
                match.group(1) is None or int(match.group(1)) > 0
                for match in FILE_PROCESSING_NONCOVERAGE.finditer(coverage_text)
            )
            or re.search(r"\breview(?:ed|ing)?\b", coverage_text, re.IGNORECASE)
        )
    )


def _has_explicit_file_omission(sentence: str) -> bool:
    for match in EXPLICIT_FILE_OMISSION.finditer(sentence):
        counts = [int(count) for count in match.groups() if count is not None]
        if not counts or any(count > 0 for count in counts):
            return True
    return False


def _without_explicit_zero_file_omissions(sentence: str) -> str:
    def keep_nonzero_omission(match: re.Match[str]) -> str:
        counts = [int(count) for count in match.groups() if count is not None]
        return " " if counts and all(count == 0 for count in counts) else match.group(0)

    return EXPLICIT_FILE_OMISSION.sub(keep_nonzero_omission, sentence)


def _summary_proves_complete_file_coverage(text: str) -> bool:
    selected_counts = {int(match.group(1)) for match in FILE_SELECTED_COUNT.finditer(text)}
    reviewed_counts = _reviewed_label_counts(text)
    not_reviewed_counts = [int(match.group(1)) for match in FILE_NOT_REVIEWED_COUNT.finditer(text)]
    ratios = [tuple(map(int, match.groups())) for match in FILE_REVIEWED_RATIO.finditer(text)]
    if selected_counts and reviewed_counts:
        return (
            len(selected_counts) == 1
            and len(reviewed_counts) == 1
            and selected_counts == reviewed_counts
            and next(iter(selected_counts)) > 0
            and (not_reviewed_counts and all(count == 0 for count in not_reviewed_counts))
        )
    if ratios:
        return all(reviewed == selected and reviewed > 0 for reviewed, selected in ratios) and (
            not not_reviewed_counts or all(count == 0 for count in not_reviewed_counts)
        )
    if any(pattern.search(text) for pattern in COMPLETE_FILE_COVERAGE_PATTERNS):
        return not not_reviewed_counts or all(count == 0 for count in not_reviewed_counts)
    coverage = FILE_COVERAGE_COUNT.search(text)
    return (
        coverage is not None
        and coverage.group(1) == coverage.group(2)
        and int(coverage.group(1)) > 0
        and (not not_reviewed_counts or all(count == 0 for count in not_reviewed_counts))
    )


def _provider_format_terminal_summary(
    payload: dict[str, Any],
    head: str,
    after: datetime,
    response_id: int | None,
    before: datetime | None = None,
    *,
    require_incomplete_coverage: bool = False,
) -> dict[str, Any] | None:
    """Prove a terminal result when CodeRabbit omits its usual result markers.

    The zero-result path requires affirmative complete-file coverage and no
    open-issue claim. The incomplete path instead requires explicit positive
    evidence of omitted file coverage. Both require an exact SHA in an edited
    summary, a generic finish reply with immutable identity, and complete
    issue/review/inline history without other CodeRabbit output in the window.
    """

    if not isinstance(head, str) or EXACT_SHA.fullmatch(head) is None:
        return None
    pr = (payload.get("data") or {}).get("repository", {}).get("pullRequest")
    if not isinstance(pr, dict):
        return None
    connections: dict[str, list[dict[str, Any]]] = {}
    for name in ("comments", "reviews", "reviewThreads"):
        connection = pr.get(name)
        nodes = connection.get("nodes") if isinstance(connection, dict) else None
        if not isinstance(nodes, list) or any(not isinstance(item, dict) for item in nodes):
            return None
        connections[name] = nodes

    comments = connections["comments"]
    if isinstance(response_id, bool) or not isinstance(response_id, int) or response_id <= 0:
        return None
    response_matches = [item for item in comments if immutable_database_id(item) == response_id]
    if len(response_matches) != 1:
        return None
    response = response_matches[0]
    response_body = response.get("body")
    response_at = parse_timestamp(response.get("createdAt"))
    response_updated = parse_timestamp(response.get("updatedAt"))
    response_author = (response.get("author") or {}).get("login")
    if (
        not is_coderabbit_login(response_author)
        or not isinstance(response_body, str)
        or not _is_finished_action_response(
            response_body,
            allow_action_wrapper=require_incomplete_coverage,
        )
        or response_at is None
        or response_updated is None
        or response_at <= after
        or response_updated < response_at
        or response_updated <= after
        or (before is not None and response_updated >= before)
        or _rate_limit(response_body, response_at) is not None
    ):
        return None
    if require_incomplete_coverage and before is not None:
        # A later trigger makes a post-finish summary edit ambiguous even when
        # its body still names the old captured head.
        return None

    summary_candidates: list[tuple[datetime, int, dict[str, Any]]] = []
    for item in comments:
        if not is_coderabbit_login((item.get("author") or {}).get("login")):
            continue
        item_id = immutable_database_id(item)
        if item_id is None or item_id == response_id:
            continue
        body = item.get("body")
        if not isinstance(body, str):
            continue
        reported_head = _scope_head(body)
        if (
            not isinstance(reported_head, str)
            or EXACT_SHA.fullmatch(reported_head) is None
            or reported_head.casefold() != head.casefold()
        ):
            continue
        created = parse_timestamp(item.get("createdAt"))
        updated = parse_timestamp(item.get("updatedAt"))
        if (
            created is None
            or updated is None
            or updated <= after
            or (not require_incomplete_coverage and updated > response_updated)
            or (before is not None and updated >= before)
            or (created >= response_at if require_incomplete_coverage else created > response_updated)
            or _rate_limit(body, updated) is not None
        ):
            continue
        summary_candidates.append((updated, item_id, item))
    if not summary_candidates:
        return None
    summary = max(summary_candidates, key=lambda value: (value[0], value[1]))[2]
    summary_id = immutable_database_id(summary)
    summary_body = summary.get("body") or ""
    if summary_id is None:
        return None
    if require_incomplete_coverage:
        if not _summary_has_explicit_incomplete_coverage(summary_body):
            return None
    elif not _summary_proves_complete_zero_findings(summary_body):
        return None

    def in_window(value: str | None) -> bool | None:
        timestamp = parse_timestamp(value)
        if timestamp is None:
            return None
        return timestamp > after and (before is None or timestamp < before)

    for item in comments:
        if not is_coderabbit_login((item.get("author") or {}).get("login")):
            continue
        item_id = immutable_database_id(item)
        if item_id in {response_id, summary_id}:
            continue
        created_in_window = in_window(item.get("createdAt"))
        updated_in_window = in_window(item.get("updatedAt"))
        if created_in_window is None or updated_in_window is None:
            return None
        if created_in_window and updated_in_window and _active_only_acknowledgement(item.get("body") or ""):
            continue
        if created_in_window or updated_in_window:
            return None

    for review in connections["reviews"]:
        if not is_coderabbit_login((review.get("author") or {}).get("login")):
            continue
        submitted_in_window = in_window(review.get("submittedAt"))
        if submitted_in_window is None or submitted_in_window:
            return None

    for thread in connections["reviewThreads"]:
        connection = thread.get("comments")
        thread_comments = connection.get("nodes") if isinstance(connection, dict) else None
        if not isinstance(thread_comments, list) or any(not isinstance(item, dict) for item in thread_comments):
            return None
        for item in thread_comments:
            if not is_coderabbit_login((item.get("author") or {}).get("login")):
                continue
            created_in_window = in_window(item.get("createdAt"))
            updated_in_window = in_window(item.get("updatedAt"))
            if created_in_window is None or updated_in_window is None:
                return None
            if created_in_window or updated_in_window:
                return None
    return summary


def provider_format_zero_finding_summary(
    payload: dict[str, Any],
    head: str,
    after: datetime,
    response_id: int | None,
    before: datetime | None = None,
) -> dict[str, Any] | None:
    """Prove a clean zero result when CodeRabbit omits its usual zero sentence."""

    return _provider_format_terminal_summary(payload, head, after, response_id, before)


def provider_format_incomplete_coverage_summary(
    payload: dict[str, Any],
    head: str,
    after: datetime,
    response_id: int | None,
    before: datetime | None = None,
) -> dict[str, Any] | None:
    """Prove terminal non-counting status from an exact-head incomplete summary."""

    return _provider_format_terminal_summary(
        payload,
        head,
        after,
        response_id,
        before,
        require_incomplete_coverage=True,
    )


def finished_reply_without_findings(
    payload: dict[str, Any],
    head: str,
    after: datetime,
    response_id: int | None,
    before: datetime | None = None,
    record: Mapping[str, Any] | None = None,
    current_record_path: str | Path | None = None,
    repo: str | None = None,
    pr_number: int | None = None,
) -> bool:
    """Accept CodeRabbit's terminal full-review reply when its complete window is empty.

    The captured trigger supplies the reviewed PR and head. A separate summary
    comment is not required for a zero-finding result, but any other bot output
    in this trigger window must be classified by the normal evidence path.
    """

    if type(response_id) is not int or response_id <= 0:
        return False
    pr = (payload.get("data") or {}).get("repository", {}).get("pullRequest")
    if not isinstance(pr, dict):
        return False
    connections: dict[str, list[dict[str, Any]]] = {}
    for name in ("comments", "reviews", "reviewThreads"):
        connection = pr.get(name)
        nodes = connection.get("nodes") if isinstance(connection, dict) else None
        if not isinstance(nodes, list) or any(not isinstance(item, dict) for item in nodes):
            return False
        connections[name] = nodes
    replies = [item for item in connections["comments"] if immutable_database_id(item) == response_id]
    if len(replies) != 1:
        return False
    reply = replies[0]
    created = parse_timestamp(reply.get("createdAt"))
    updated = parse_timestamp(reply.get("updatedAt"))
    if (
        not is_coderabbit_login((reply.get("author") or {}).get("login"))
        or not isinstance(reply.get("body"), str)
        or not _is_finished_action_response(reply["body"], allow_action_wrapper=True)
        or created is None
        or updated is None
        or created <= after
        or updated < created
        or (before is not None and updated >= before)
    ):
        return False

    def in_window(value: Any) -> bool | None:
        when = parse_timestamp(value)
        return None if when is None else when > after and (before is None or when < before)

    for item in connections["comments"]:
        if not is_coderabbit_login((item.get("author") or {}).get("login")):
            continue
        if immutable_database_id(item) == response_id:
            continue
        created_in_window = in_window(item.get("createdAt"))
        updated_in_window = in_window(item.get("updatedAt"))
        body = item.get("body")
        if (
            created_in_window is False
            and updated_in_window is True
            and isinstance(body, str)
            and "No actionable comments were generated in the recent review." in body
            and _matches_head(body, head)
            and not _summary_has_explicit_incompleteness(body)
            and not any(pattern.search(_unquoted(body)) for pattern in POSITIVE_FINDING_COUNT_PATTERNS)
        ):
            # CodeRabbit reuses its standing PR summary. A benign old zero
            # summary is not attributed to this trigger; the exact finished
            # reply and absence of reviews/threads are the zero proof.
            continue
        if (
            created_in_window is True
            and updated_in_window is True
            and isinstance(body, str)
            and _active_only_acknowledgement(body)
        ):
            continue
        if created_in_window is None or updated_in_window is None or created_in_window or updated_in_window:
            return False
    for item in connections["reviews"]:
        if not is_coderabbit_login((item.get("author") or {}).get("login")):
            continue
        submitted_in_window = in_window(item.get("submittedAt"))
        if submitted_in_window is None or submitted_in_window:
            return False
    archived_bodies: dict[int, tuple[str, str]] | None = None
    for thread in connections["reviewThreads"]:
        comments = thread.get("comments")
        nodes = comments.get("nodes") if isinstance(comments, dict) else None
        if not isinstance(nodes, list) or any(not isinstance(item, dict) for item in nodes):
            return False
        for item in nodes:
            if not is_coderabbit_login((item.get("author") or {}).get("login")):
                continue
            created_in_window = in_window(item.get("createdAt"))
            updated_in_window = in_window(item.get("updatedAt"))
            if created_in_window is None or updated_in_window is None:
                return False
            if created_in_window or updated_in_window:
                if not created_in_window and updated_in_window:
                    if archived_bodies is None:
                        archived_bodies = (
                            _archived_completed_thread_bodies(
                                repo,
                                pr_number,
                                head,
                                response_id,
                                created,
                                record,
                                current_record_path,
                            )
                            if isinstance(repo, str) and type(pr_number) is int and pr_number > 0
                            else {}
                        )
                    if _addressed_thread_update_matches_archive(
                        item, head, after, before, response_id, archived_bodies
                    ):
                        continue
                return False
    return True


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
    *,
    now: datetime | None = None,
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
        or type(trigger_id) is not int
        or trigger_id <= 0
        or trigger_dt is None
        or not isinstance(trigger.get("url"), str)
    ):
        raise ValueError("trigger record has invalid full-review identity")
    comments = list((pr.get("comments") or {}).get("nodes", []))
    reviews = list((pr.get("reviews") or {}).get("nodes", []))
    captured_matches = [item for item in comments if immutable_database_id(item) == trigger_id]
    if len(captured_matches) != 1:
        return TriggerState(
            "unattributed",
            True,
            False,
            **base,
            response_id=None,
            response_created_at=None,
            response_url=None,
            cooldown_until=None,
            reason="captured trigger comment is missing or duplicated in complete GitHub history",
        )
    captured = captured_matches[0]
    captured_author = _comment_author_login(captured)
    recorded_author = trigger.get("author_login")
    if (
        not isinstance(captured_author, str)
        or not captured_author.strip()
        or is_coderabbit_login(captured_author)
        or normalize_command(captured.get("body") or "") != FULL_COMMAND
        or captured.get("createdAt") != trigger_at
        or captured.get("url") != trigger.get("url")
        or (
            recorded_author is not None
            and (
                not isinstance(recorded_author, str)
                or not recorded_author.strip()
                or captured_author.casefold() != recorded_author.casefold()
            )
        )
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
    try:
        publications = review_publications(reviews, record["head_sha"], trigger_dt, next_dt)
        complete_publications = [publication for publication in publications if publication["complete"]]
        if len(complete_publications) > 1:
            raise ValueError("more than one complete Hosted publication is eligible for the captured trigger")
        for publication in complete_publications:
            validate_publication_comments(publication, pr, record["head_sha"], trigger_dt)
    except ValueError as exc:
        return TriggerState(
            "ambiguous",
            False,
            False,
            **base,
            response_id=None,
            response_created_at=None,
            response_url=None,
            cooldown_until=None,
            reason=str(exc),
        )
    incomplete = [publication for publication in publications if not publication["complete"]]
    if incomplete:
        return TriggerState(
            "active",
            False,
            True,
            **base,
            response_id=None,
            response_created_at=None,
            response_url=None,
            cooldown_until=None,
            reason="Hosted publication is awaiting its remaining batches",
        )
    publication_by_response = {
        immutable_database_id(publication["primary"]): publication for publication in publications
    }
    publication_review_ids = {identity for publication in publications for identity in publication["review_ids"]}
    candidates: list[tuple[datetime, str, dict[str, Any], datetime | None]] = []
    window_rate_limit_responses: list[dict[str, Any]] = []
    unresolved_rate_limit_timestamp = False
    for item in comments:
        if not is_coderabbit_login((item.get("author") or {}).get("login")):
            continue
        body = item.get("body") or ""
        if is_rate_limit_reply_body(body) and strict_provider_timestamp(item.get("createdAt")) is None:
            unresolved_rate_limit_timestamp = True
        created = parse_timestamp(item.get("createdAt"))
        if created is None or created <= trigger_dt or (next_dt and created >= next_dt):
            continue
        if is_rate_limit_reply_body(body):
            window_rate_limit_responses.append(item)
        cooldown = _rate_limit(body, created)
        # Rate-limit evidence is classified before all other prose in a reply.
        if is_rate_limit_reply_body(body):
            candidates.append((created, "rate_limited", item, cooldown))
        elif provider_file_ceiling_skip(body):
            candidates.append((created, "failed", item, None))
        elif NOOP_MARKER in body:
            candidates.append((created, "noop", item, None))
        elif _substantive(body) and _matches_head(body, record["head_sha"]):
            candidates.append((created, "completed", item, None))
        elif FAILED_PATTERN.search(_unquoted(body)):
            candidates.append((created, "failed", item, None))
        elif ACTIVE_PATTERN.search(_unquoted(body)):
            candidates.append((created, "active", item, None))
        elif FINISHED_REVIEW_PATTERN.search(_unquoted(body)):
            zero_summary = _zero_finding_summary(
                payload, record["head_sha"], trigger_dt, immutable_database_id(item), next_dt
            )
            if zero_summary is None:
                zero_summary = provider_format_zero_finding_summary(
                    payload,
                    record["head_sha"],
                    trigger_dt,
                    immutable_database_id(item),
                    next_dt,
                )
            if zero_summary is not None:
                state = "completed"
            elif (
                provider_format_incomplete_coverage_summary(
                    payload,
                    record["head_sha"],
                    trigger_dt,
                    immutable_database_id(item),
                    next_dt,
                )
                is not None
            ):
                state = "failed_incomplete_coverage"
            elif finished_reply_without_findings(
                payload,
                record["head_sha"],
                trigger_dt,
                immutable_database_id(item),
                next_dt,
                record,
                current_record_path,
                repo,
                pr_number,
            ):
                state = (
                    "ambiguous_retired_predecessor"
                    if _unresolved_retired_predecessor(repo, pr_number, trigger_dt, current_record_path)
                    else "completed"
                )
            else:
                state = "ambiguous"
            terminal = parse_timestamp(item.get("updatedAt"))
            if terminal is not None and terminal > created and (next_dt is None or terminal < next_dt):
                order_at = terminal
            elif state == "completed" and zero_summary is not None:
                # A later edit does not move this reply into a newer trigger.
                # The exact-head zero summary proves completion in the old
                # window and lets the linked finished reply outrank the
                # summary comment without trusting the later edit for time.
                summary_at = parse_timestamp(zero_summary.get("updatedAt"))
                order_at = (summary_at + timedelta(microseconds=1)) if summary_at else created
            else:
                order_at = created
            candidates.append((order_at, state, item, None))
    review_candidates: list[tuple[datetime, str, dict[str, Any], datetime | None]] = []
    for publication in publications:
        review_candidates.append(
            (parse_timestamp(publication["finished_at"]), "completed", publication["primary"], None)
        )
    for review in reviews:
        if immutable_database_id(review) in publication_review_ids:
            continue
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
        # An explicit review commit is authoritative.  Body prose is only a
        # fallback for older/API responses that omit the commit object; it must
        # never override a mismatched immutable commit.
        matched = (
            commit.casefold() == record["head_sha"].casefold()
            if isinstance(commit, str) and commit.strip()
            else _matches_head(review.get("body") or "", record["head_sha"])
        )
        review_candidates.append((submitted, "completed" if matched else "ambiguous", review, None))
    # A GitHub review has an immutable submitted time and commit. A bot's
    # finished-reply comment may be edited later, so it cannot supersede the
    # review object that actually records this trigger's result. Explicit
    # terminal status comments created after the latest review still describe
    # the final request state and must not be hidden by that review.
    if review_candidates:
        latest_review_at = max(item[0] for item in review_candidates)
        later_terminal_comments = [
            item
            for item in candidates
            if item[1] in {"rate_limited", "noop", "failed"}
            and (created := parse_timestamp(item[2].get("createdAt"))) is not None
            and created > latest_review_at
        ]
        candidates = [*review_candidates, *later_terminal_comments]
    if not candidates:
        if unresolved_rate_limit_timestamp:
            return TriggerState(
                "ambiguous",
                True,
                False,
                **base,
                response_id=None,
                response_created_at=None,
                response_url=None,
                cooldown_until=None,
                reason="a CodeRabbit rate-limit reply has no valid creation timestamp",
            )
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
                reason=LATER_TRIGGER_AMBIGUITY_REASON,
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
    if unresolved_rate_limit_timestamp and state != "rate_limited":
        return TriggerState(
            "ambiguous",
            True,
            False,
            **base,
            response_id=None,
            response_created_at=None,
            response_url=None,
            cooldown_until=None,
            reason="a CodeRabbit rate-limit reply has no valid creation timestamp",
        )
    incomplete_coverage = state == "failed_incomplete_coverage"
    if incomplete_coverage:
        state = "failed"
    # Preserve submittedAt for GitHub review objects, which have no createdAt.
    # A rate-limit cooldown itself may use only the immutable comment timestamp.
    response_at = response.get("createdAt") or response.get("submittedAt")
    response_id = immutable_database_id(response)
    publication = publication_by_response.get(response_id)
    cooldown_basis = None
    if state == "rate_limited":
        if unresolved_rate_limit_timestamp:
            cooldown, cooldown_basis = None, "unknown"
        else:
            cooldown, cooldown_basis = rate_limit_window_cooldown(window_rate_limit_responses, now=now)
    else:
        cooldown = None
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
    if state in {"ambiguous", "ambiguous_retired_predecessor"}:
        # A review on another commit may be an unrelated automatic or older
        # result. It does not prove this captured request has stopped.
        unproved_terminal = response in reviews or state == "ambiguous_retired_predecessor"
        return TriggerState(
            "ambiguous",
            not unproved_terminal,
            False,
            **base,
            response_id=response_id,
            response_created_at=response_at,
            response_url=response.get("url"),
            cooldown_until=None,
            reason=(
                "CodeRabbit reported review finished without a head-attributed result or zero-finding summary"
                if FINISHED_REVIEW_PATTERN.search(_unquoted(response.get("body") or ""))
                else "a retired in-flight predecessor may own this headless reply"
                if state == "ambiguous_retired_predecessor"
                else "a different-head review does not prove the captured request finished"
                if response in reviews
                else "response does not identify the captured head"
            ),
        )
    response_dt = parse_timestamp(response_at)
    terminal_dt = response_dt
    if publication is not None:
        terminal_dt = parse_timestamp(publication["finished_at"])
    if (
        state == "completed"
        and response in comments
        and FINISHED_REVIEW_PATTERN.search(_unquoted(response.get("body") or ""))
    ):
        created_dt = parse_timestamp(response.get("createdAt"))
        updated_dt = parse_timestamp(response.get("updatedAt"))
        if created_dt and updated_dt and updated_dt > created_dt:
            # A finished-review reply can be created as an acknowledgment and
            # edited when the review actually completes. Use that terminal
            # edit for duration only while it remains inside this trigger's
            # window; response identity and displayed creation time stay fixed.
            terminal_dt = updated_dt if next_dt is None or updated_dt < next_dt else None
    elapsed = math.ceil((terminal_dt - trigger_dt).total_seconds()) if terminal_dt and trigger_dt else None
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
            cooldown_basis=cooldown_basis,
            reason="terminal response has no immutable numeric GitHub identity",
        )
    reason = {
        "completed": "attributable substantive review completed",
        "rate_limited": "CodeRabbit explicitly rate limited the request",
        "noop": "request acknowledged without reviewing commits",
        "failed": "CodeRabbit explicitly reported a review failure",
    }[state]
    if incomplete_coverage:
        reason = "CodeRabbit finished after explicitly reporting incomplete file coverage"
    if state == "rate_limited":
        if cooldown_basis == "local_retry_backoff" and cooldown is not None:
            reason = f"CodeRabbit rate limited; local one-hour retry backoff deadline is {cooldown.isoformat()}"
        elif cooldown_basis == "provider_reset" and cooldown is not None:
            reason = f"CodeRabbit rate limited; provider-stated next-review time is {cooldown.isoformat()}"
        elif cooldown_basis == "unknown":
            reason = UNKNOWN_RATE_LIMIT_REASON
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
        cooldown_basis=cooldown_basis,
        duration_seconds=elapsed if elapsed is not None and elapsed >= 0 else None,
        publication_review_ids=publication["review_ids"] if publication is not None else (),
        publication_finished_at=publication["finished_at"] if publication is not None else None,
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
    fetch_payload: Callable[[], dict[str, Any]],
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
    if not callable(fetch_payload):
        raise TypeError("trigger retirement requires a live pull-request fetch callback")
    record_path = Path(path)
    descriptor = _with_lock(default_trigger_record_path(repo, pr_number))
    try:
        # Re-read after taking the same per-PR lock used by Hosted posting.
        record = load_trigger_record(record_path, repo, pr_number)
        payload = fetch_payload()
        if not isinstance(payload, dict):
            raise TypeError("live pull-request response is not an object")
        try:
            pr = payload["data"]["repository"]["pullRequest"]
        except (KeyError, TypeError) as error:
            raise TypeError("live pull-request data is unavailable for trigger retirement") from error
        if not isinstance(pr, dict):
            raise TypeError("live pull-request data is unavailable for trigger retirement")
        state = trigger_state(repo, pr_number, payload, record, record_path)
        current = pr.get("headRefOid")
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
        if state.state == "timed_out" and record["head_sha"].casefold() == current.casefold():
            raise ValueError("cannot retire an unresolved timed-out trigger on the same head")
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


def retire_stuck_trigger_after_head_advance(
    path: str | Path,
    repo: str,
    pr_number: int,
    trigger_id: int,
    expected_current_head_sha: str,
    reason: str,
    confirmed_wait_expired: bool,
    fetch_payload: Callable[[], dict[str, Any]],
) -> dict[str, Any]:
    """Retire one stuck trigger only after its captured PR head has advanced.

    The operator must confirm that the bounded wait expired. A same-head retry
    is deliberately unsupported because a delayed provider response cannot be
    distinguished from a response to a replacement request on the same SHA.
    The live PR is fetched while holding the normal per-PR Hosted request lock;
    the exact captured trigger and current head are checked before the durable
    record is retired. The retired record makes any later response to the old
    trigger non-counting.
    """

    if (
        not isinstance(trigger_id, int)
        or isinstance(trigger_id, bool)
        or trigger_id <= 0
        or not isinstance(expected_current_head_sha, str)
        or not EXACT_SHA.fullmatch(expected_current_head_sha)
        or not isinstance(reason, str)
        or not reason.strip()
        or len(reason) > 240
        or any(ord(char) < 0x20 for char in reason)
    ):
        raise ValueError("invalid stuck-trigger retirement request")
    if confirmed_wait_expired is not True:
        raise ValueError("stuck-trigger retirement requires --confirmed-wait-expired")
    if not callable(fetch_payload):
        raise TypeError("stuck-trigger retirement requires a live pull-request fetch callback")

    record_path = Path(path)
    descriptor = _with_lock(default_trigger_record_path(repo, pr_number))
    try:
        current_paths = current_trigger_record_paths(repo, pr_number)
        if len(current_paths) != 1 or current_paths[0] != record_path:
            raise ValueError("stuck-trigger retirement requires exactly one current Hosted trigger")
        record = load_trigger_record(record_path, repo, pr_number)
        trigger = record.get("trigger") or {}
        if type(trigger.get("id")) is not int or trigger["id"] != trigger_id:
            raise ValueError("durable Hosted trigger identity does not match retirement request")
        captured_head = record.get("head_sha")
        if not isinstance(captured_head, str) or not EXACT_SHA.fullmatch(captured_head):
            raise ValueError("durable Hosted trigger has no exact captured head")
        if record.get("status") not in {"posted", "posted_boundary_changed", "timed_out"}:
            raise ValueError("stuck-trigger retirement requires a verified posted trigger")
        if record.get("status") == "timed_out":
            timeout = record.get("timeout")
            if (
                not isinstance(timeout, dict)
                or timeout.get("observed_state") not in {"active", "awaiting_response"}
                or parse_timestamp(timeout.get("at")) is None
            ):
                raise ValueError("timed-out trigger has no valid unresolved-state timeout record")
        if captured_head.casefold() == expected_current_head_sha.casefold():
            raise ValueError("same-head stuck-trigger recovery is unsafe; wait for a new exact PR head")

        payload = fetch_payload()
        if not isinstance(payload, dict):
            raise TypeError("live pull-request response is not an object")
        try:
            pr = payload["data"]["repository"]["pullRequest"]
        except (KeyError, TypeError) as error:
            raise TypeError("live pull-request data is unavailable for stuck-trigger retirement") from error
        if not isinstance(pr, dict):
            raise TypeError("live pull-request data is unavailable for stuck-trigger retirement")
        comments = (pr.get("comments") or {}).get("nodes")
        reviews = (pr.get("reviews") or {}).get("nodes")
        if not isinstance(comments, list) or not isinstance(reviews, list):
            raise TypeError("complete paginated Hosted response history is unavailable for stuck-trigger retirement")
        current_head = pr.get("headRefOid")
        if not isinstance(current_head, str) or current_head.casefold() != expected_current_head_sha.casefold():
            raise ValueError("current pull-request head does not match stuck-trigger retirement request")

        # A posting-boundary change is not review evidence.  After the exact PR
        # head advances, inspect its durable trigger as posted only in memory so
        # this locked recovery can verify whether it is still active or awaiting
        # a response.  The record remains boundary-changed unless retired below.
        state_record = record
        if record.get("status") == "posted_boundary_changed":
            state_record = {**record, "status": "posted"}
        state = trigger_state(repo, pr_number, payload, state_record, record_path)
        if state.head_sha.casefold() != captured_head.casefold() or state.trigger_comment_id != trigger_id:
            raise ValueError("live Hosted trigger identity changed during stuck-trigger retirement")
        terminal_boundary_changed = record.get("status") == "posted_boundary_changed" and state.state in {
            "completed",
            "rate_limited",
            "noop",
            "failed",
        }
        retire_after_later_trigger = state.state == "ambiguous" and state.reason == LATER_TRIGGER_AMBIGUITY_REASON
        if (
            state.state not in {"active", "awaiting_response"}
            and not terminal_boundary_changed
            and not retire_after_later_trigger
        ):
            raise ValueError(f"cannot retire stuck trigger in live state {state.state}")

        current = load_trigger_record(record_path, repo, pr_number)
        if json.dumps(current, sort_keys=True) != json.dumps(record, sort_keys=True):
            raise ValueError("Hosted trigger record changed during stuck-trigger retirement")
        updated = dict(record)
        updated["status"] = "retired"
        updated["retirement"] = {
            "action": "operator_retire_stuck_after_head_advance",
            "retired_at": utc_now(),
            "reason": reason.strip(),
            "trigger_comment_id": trigger_id,
            "captured_head_sha": captured_head,
            "expected_current_head_sha": expected_current_head_sha,
            "observed_live_state": state.state,
            "observed_response_id": state.response_id,
            "confirmed_wait_expired": True,
            "late_responses_counted": False,
        }
        atomic_write_json(record_path, updated)
    finally:
        fcntl.flock(descriptor, fcntl.LOCK_UN)
        os.close(descriptor)
    return {
        "operation": "retire_stuck_trigger_after_head_advance",
        "status": "retired",
        "repository": repo,
        "pr_number": pr_number,
        "trigger_comment_id": trigger_id,
        "captured_head_sha": captured_head,
        "current_head_sha": expected_current_head_sha,
        "observed_live_state": state.state,
        "reason": reason.strip(),
        "late_responses_counted": False,
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
