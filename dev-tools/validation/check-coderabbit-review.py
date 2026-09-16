#!/usr/bin/env python3
"""Fail-closed CodeRabbit PR review gate.

This script does not trust the top-level CodeRabbit status badge. It verifies:
1. unresolved non-outdated review threads are zero
2. unresolved outdated review threads are zero
3. an explicit CodeRabbit review command was posted after the latest PR commit
4. CodeRabbit published a substantive review summary after that command
5. an incremental review is accepted only with evidence for the documented file-ceiling exception
"""

from __future__ import annotations

import argparse
import fcntl
import json
import math
import os
import re
import stat
import subprocess
import sys
import tempfile
import time
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Any

GH_TIMEOUT_SECONDS = 30

REVIEW_COMMAND_TYPES = {
    "@coderabbitai review": "incremental",
    "@coderabbitai full review": "full",
}

# A command acknowledgement can say "Review finished." while explicitly stating that no commits
# were reviewed. The walkthrough is emitted only by CodeRabbit's actual review summary.
SUBSTANTIVE_REVIEW_MARKER = "<!-- walkthrough_start -->"
PLAN_REVIEW_SKIP_MARKER = (
    "<!-- This is an auto-generated comment: skip review by coderabbit.ai -->"
)
REVIEW_LIMIT_MARKER = (
    "<!-- This is an auto-generated comment: rate limited by coderabbit.ai -->"
)
REVIEW_LIMIT_STATUS_PATTERN = re.compile(
    r"^[ \t]*(?:[*_`#-]+[ \t]*)*review\s+rate\s+limited\b",
    re.IGNORECASE | re.MULTILINE,
)
REVIEW_LIMIT_COMPLETE_MESSAGE_PATTERN = re.compile(
    r"^[ \t]*(?:full\s+review\s+finished\.\s*)?"
    r"(?:(?:your\s+)?next\s+(?:included\s+)?reviews?\s+(?:will\s+be\s+)?available\s+in"
    r"|more\s+reviews\s+will\s+be\s+available\s+in"
    r"|next\s+review\s+available\s+in)\b",
    re.IGNORECASE | re.MULTILINE,
)
REVIEW_LIMIT_WINDOW_PATTERN = re.compile(
    r"(?:(?:your\s+)?next\s+(?:included\s+)?reviews?\s+(?:will\s+be\s+)?available\s+in"
    r"|more\s+reviews\s+will\s+be\s+available\s+in"
    r"|next\s+review\s+available\s+in)\s*:?[\s*]*"
    r"(\d+)\s+(seconds?|minutes?|hours?)(?:\*\*)?",
    re.IGNORECASE,
)
NOOP_REVIEW_MARKER = "does not re-review already reviewed commits"
ACTIVE_REVIEW_PATTERN = re.compile(r"\bfull\s+review\s+triggered\b", re.IGNORECASE)
FINISHED_REVIEW_PATTERN = re.compile(
    r"^[ \t]*full\s+review\s+finished\.\s*$", re.IGNORECASE | re.MULTILINE
)
COMMAND_INVOCATION_MARKER = "<!-- CodeRabbit review command invocation:"
RECENT_REVIEW_MARKER = "<!-- recent_review_start -->"
NO_ACTIONABLE_REVIEW_MARKER = (
    "No actionable comments were generated in the recent review."
)
FINAL_REVIEW_RISK_COVERAGE_MARKER = "final_review_risk_coverage"
FAILED_REVIEW_PATTERN = re.compile(
    r"(?:\breview\b.{0,80}\b(?:failed|failure)\b|\b(?:failed|unable)\b.{0,80}\breview\b|"
    r"\bsomething went wrong\b)",
    re.IGNORECASE | re.DOTALL,
)
ACTIONABLE_COMMENTS_MARKER = "**Actionable comments posted:"
OUTSIDE_DIFF_MARKER = "Outside diff range comments"
DUPLICATE_COMMENTS_MARKER = "Duplicate comments"
REVIEW_SCOPE_PATTERN = re.compile(
    r"Reviewing files that changed from the base of the PR and between\s+"
    r"`?([0-9a-f]{6,40})`?\s+and\s+`?([0-9a-f]{6,40})`?",
    re.IGNORECASE,
)
REVIEW_FILE_COUNT_PATTERN = re.compile(
    r"Files selected for processing \((\d+)\)",
    re.IGNORECASE,
)
PLAN_CEILING_PATTERN = re.compile(
    r"(?is)(?:"
    r"(?:exceed\w*|too many|over|reject\w*|skip\w*).{0,120}"
    r"(?:file|files).{0,120}(?:plan|limit|ceiling|maximum|cap)"
    r"|(?:plan|review).{0,120}(?:file|files).{0,120}"
    r"(?:limit|ceiling|maximum|cap).{0,120}"
    r"(?:exceed\w*|too many|over|reject\w*|skip\w*)"
    r")"
)


@dataclass
class ReviewSummary:
    repo: str
    pr_number: int
    head_sha: str
    latest_commit_at: str
    unresolved_non_outdated: int
    unresolved_outdated: int
    unresolved_total: int
    latest_explicit_review_request_at: str | None
    latest_explicit_review_request_type: str | None
    latest_coderabbit_review_finished_at: str | None
    explicit_review_after_latest_commit: bool
    review_finished_after_latest_request: bool
    substantive_review_after_latest_commit: bool
    latest_review_request_rate_limited: bool
    review_rate_limit_until: str | None
    latest_review_request_noop: bool
    latest_review_request_failed: bool
    retrigger_review_allowed: bool
    manual_thread_resolution_required: bool
    must_resolve_outdated_threads: bool
    outside_diff_actionable_comments: int
    duplicate_actionable_comments: int
    latest_actionable_comment_url: str | None
    prior_substantive_review_checkpoint: bool
    plan_ceiling_rejection_evidence: bool
    reviewed_commit_range_after_latest_commit: bool
    incremental_review_exception_allowed: bool
    ok: bool
    reasons: list[str]


@dataclass
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
    age_seconds: int | None = None
    manual_adjudication_required: bool = False


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Validate live CodeRabbit PR review state without trusting the badge."
    )
    parser.add_argument("--repo", required=True, help="GitHub repo in owner/name form")
    parser.add_argument("--pr", type=int, required=True, help="Pull request number")
    parser.add_argument(
        "--input",
        help="Read GraphQL payload from a local JSON file instead of gh api (for tests)",
    )
    parser.add_argument(
        "--json", action="store_true", help="Emit machine-readable JSON"
    )
    parser.add_argument(
        "--trigger-record",
        help="Classify responses attributable to a persisted hosted-review trigger record",
    )
    parser.add_argument(
        "--wait",
        action="store_true",
        help="Block until the trigger reaches a terminal state",
    )
    parser.add_argument(
        "--timeout",
        type=float,
        default=1800,
        help="Maximum seconds to wait for a terminal trigger state (default: 1800)",
    )
    parser.add_argument(
        "--poll-interval",
        type=float,
        default=20,
        help="Seconds between live trigger-state checks (default: 20)",
    )
    parser.add_argument(
        "--retire-trigger",
        type=int,
        help="Explicitly retire the identified timed-out trigger record",
    )
    parser.add_argument(
        "--expected-head-sha",
        help="Exact current pull-request head required for trigger retirement",
    )
    parser.add_argument(
        "--reason",
        help="Bounded operator reason for trigger retirement",
    )
    return parser.parse_args()


def parse_repo(repo: str) -> tuple[str, str]:
    owner, _, name = repo.strip().partition("/")
    if not owner or not name:
        raise ValueError("repo must be in owner/name form")
    return owner, name


def run_gh_query(query: str, variables: dict[str, str | int]) -> dict[str, Any]:
    args = ["gh", "api", "graphql", "-f", f"query={query}"]
    for key, value in variables.items():
        args.extend(["-F", f"{key}={value}"])
    try:
        completed = subprocess.run(
            args,
            check=True,
            capture_output=True,
            text=True,
            timeout=GH_TIMEOUT_SECONDS,
        )
    except subprocess.TimeoutExpired as ex:
        raise RuntimeError(
            f"gh api graphql timed out after {GH_TIMEOUT_SECONDS} seconds"
        ) from ex
    except subprocess.CalledProcessError as ex:
        stderr = ex.stderr.strip() if ex.stderr else "gh api graphql failed"
        raise RuntimeError(stderr) from ex
    return json.loads(completed.stdout)


def run_gh_graphql(repo: str, pr_number: int) -> dict[str, Any]:
    owner, name = parse_repo(repo)
    base_query = """
query($owner:String!, $repo:String!, $number:Int!) {
  repository(owner:$owner, name:$repo) {
    pullRequest(number:$number) {
      headRefOid
      commits(last:1) {
        nodes {
          commit {
            oid
            committedDate
          }
        }
      }
      reviewThreads(first:100) {
        nodes {
          isResolved
          isOutdated
          path
          line
          comments(first:20) {
            nodes {
              id
              databaseId
              author { login }
              body
              url
              createdAt
              updatedAt
            }
          }
        }
        pageInfo {
          hasNextPage
          endCursor
        }
      }
      comments(first:100) {
        nodes {
          id
          databaseId
          author { login }
          body
          createdAt
          updatedAt
          url
        }
        pageInfo {
          hasNextPage
          endCursor
        }
      }
      reviews(first:100) {
        nodes {
          id
          databaseId
          author { login }
          body
          state
          submittedAt
          url
          commit { oid }
        }
        pageInfo {
          hasNextPage
          endCursor
        }
      }
    }
  }
}
""".strip()
    review_threads_query = """
query($owner:String!, $repo:String!, $number:Int!, $after:String!) {
  repository(owner:$owner, name:$repo) {
    pullRequest(number:$number) {
      reviewThreads(first:100, after:$after) {
        nodes {
          isResolved
          isOutdated
          path
          line
          comments(first:20) {
            nodes {
              id
              databaseId
              author { login }
              body
              url
              createdAt
              updatedAt
            }
          }
        }
        pageInfo {
          hasNextPage
          endCursor
        }
      }
    }
  }
}
""".strip()
    comments_query = """
query($owner:String!, $repo:String!, $number:Int!, $after:String!) {
  repository(owner:$owner, name:$repo) {
    pullRequest(number:$number) {
      comments(first:100, after:$after) {
        nodes {
          id
          databaseId
          author { login }
          body
          createdAt
          updatedAt
          url
        }
        pageInfo {
          hasNextPage
          endCursor
        }
      }
    }
  }
}
""".strip()
    reviews_query = """
query($owner:String!, $repo:String!, $number:Int!, $after:String!) {
  repository(owner:$owner, name:$repo) {
    pullRequest(number:$number) {
      reviews(first:100, after:$after) {
        nodes {
          id
          databaseId
          author { login }
          body
          state
          submittedAt
          url
          commit { oid }
        }
        pageInfo {
          hasNextPage
          endCursor
        }
      }
    }
  }
}
""".strip()

    payload = run_gh_query(
        base_query,
        {"owner": owner, "repo": name, "number": pr_number},
    )
    pr = payload["data"]["repository"]["pullRequest"]
    review_threads = list(pr["reviewThreads"]["nodes"])
    comments = list(pr["comments"]["nodes"])
    reviews = list((pr.get("reviews") or {}).get("nodes", []))

    review_threads_page = pr["reviewThreads"].get("pageInfo", {})
    while review_threads_page.get("hasNextPage"):
        review_threads_payload = run_gh_query(
            review_threads_query,
            {
                "owner": owner,
                "repo": name,
                "number": pr_number,
                "after": review_threads_page["endCursor"],
            },
        )
        review_threads_connection = review_threads_payload["data"]["repository"][
            "pullRequest"
        ]["reviewThreads"]
        review_threads.extend(review_threads_connection["nodes"])
        review_threads_page = review_threads_connection.get("pageInfo", {})

    comments_page = pr["comments"].get("pageInfo", {})
    while comments_page.get("hasNextPage"):
        comments_payload = run_gh_query(
            comments_query,
            {
                "owner": owner,
                "repo": name,
                "number": pr_number,
                "after": comments_page["endCursor"],
            },
        )
        comments_connection = comments_payload["data"]["repository"]["pullRequest"][
            "comments"
        ]
        comments.extend(comments_connection["nodes"])
        comments_page = comments_connection.get("pageInfo", {})

    reviews_page = (pr.get("reviews") or {}).get("pageInfo", {})
    while reviews_page.get("hasNextPage"):
        reviews_payload = run_gh_query(
            reviews_query,
            {
                "owner": owner,
                "repo": name,
                "number": pr_number,
                "after": reviews_page["endCursor"],
            },
        )
        reviews_connection = reviews_payload["data"]["repository"]["pullRequest"][
            "reviews"
        ]
        reviews.extend(reviews_connection["nodes"])
        reviews_page = reviews_connection.get("pageInfo", {})

    pr["reviewThreads"] = {"nodes": review_threads}
    pr["comments"] = {"nodes": comments}
    pr["reviews"] = {"nodes": reviews}
    return payload


def load_payload(input_path: str | None, repo: str, pr_number: int) -> dict[str, Any]:
    if input_path:
        return json.loads(Path(input_path).read_text(encoding="utf-8"))
    return run_gh_graphql(repo, pr_number)


def parse_timestamp(value: str | None) -> datetime | None:
    if not value:
        return None
    return datetime.fromisoformat(value.replace("Z", "+00:00")).astimezone(timezone.utc)


def age_seconds(created_at: datetime) -> int:
    return max(0, int((datetime.now(timezone.utc) - created_at).total_seconds()))


def utc_now() -> str:
    return datetime.now(timezone.utc).replace(microsecond=0).isoformat().replace(
        "+00:00", "Z"
    )


def atomic_write_json(path: Path, payload: dict[str, Any]) -> None:
    """Replace one private trigger record without exposing a partial JSON file."""

    if path.is_symlink():
        raise OSError("trigger record must not be a symbolic link")
    path.parent.mkdir(parents=True, exist_ok=True)
    file_descriptor, temporary_name = tempfile.mkstemp(
        prefix=f".{path.name}.", dir=path.parent
    )
    try:
        os.fchmod(file_descriptor, stat.S_IRUSR | stat.S_IWUSR)
        with os.fdopen(file_descriptor, "w", encoding="utf-8") as temporary_file:
            file_descriptor = -1
            json.dump(payload, temporary_file, indent=2, sort_keys=True)
            temporary_file.write("\n")
            temporary_file.flush()
            os.fsync(temporary_file.fileno())
        os.replace(temporary_name, path)
        directory_descriptor = os.open(path.parent, os.O_RDONLY | os.O_DIRECTORY)
        try:
            os.fsync(directory_descriptor)
        finally:
            os.close(directory_descriptor)
    finally:
        if file_descriptor != -1:
            os.close(file_descriptor)
        try:
            os.unlink(temporary_name)
        except FileNotFoundError:
            pass


def trigger_record_version(record: dict[str, Any]) -> str:
    """Return a stable snapshot used to compare a waiter's record ownership."""

    return json.dumps(record, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def normalize_command(body: str) -> str:
    return " ".join(body.strip().split()).lower()


def extract_section_count(body: str, marker: str) -> int:
    match = re.search(rf"{re.escape(marker)} \((\d+)\)", body)
    return int(match.group(1)) if match else 0


def actionable_summary_candidate(
    author: str,
    body: str,
    timestamp: str | None,
    url: str | None,
    state: str | None,
    latest_explicit_review_request_dt: datetime | None,
) -> tuple[datetime, str | None, int, int] | None:
    if state == "DISMISSED":
        return None
    if author != "coderabbitai" or not any(
        marker in body
        for marker in (
            ACTIONABLE_COMMENTS_MARKER,
            OUTSIDE_DIFF_MARKER,
            DUPLICATE_COMMENTS_MARKER,
        )
    ):
        return None
    timestamp_dt = parse_timestamp(timestamp)
    if timestamp_dt is None:
        return None
    if (
        latest_explicit_review_request_dt is not None
        and timestamp_dt < latest_explicit_review_request_dt
    ):
        return None
    return (
        timestamp_dt,
        url,
        extract_section_count(body, OUTSIDE_DIFF_MARKER),
        extract_section_count(body, DUPLICATE_COMMENTS_MARKER),
    )


def parse_review_rate_limit_until(body: str, created_at: datetime) -> datetime | None:
    match = REVIEW_LIMIT_WINDOW_PATTERN.search(body)
    if not match:
        return None
    amount = int(match.group(1))
    unit = match.group(2).lower()
    return created_at + timedelta(
        **{
            "seconds"
            if unit.startswith("second")
            else "minutes"
            if unit.startswith("minute")
            else "hours": amount
        }
    )


def unquoted_body(body: str) -> str:
    return "\n".join(
        line for line in body.splitlines() if not line.lstrip().startswith(">")
    )


def is_substantive_review_body(body: str) -> bool:
    return any(
        marker in body
        for marker in (
            SUBSTANTIVE_REVIEW_MARKER,
            ACTIONABLE_COMMENTS_MARKER,
            OUTSIDE_DIFF_MARKER,
            DUPLICATE_COMMENTS_MARKER,
            FINAL_REVIEW_RISK_COVERAGE_MARKER,
        )
    )


def review_scope(body: str) -> tuple[str, str, int] | None:
    range_match = REVIEW_SCOPE_PATTERN.search(body)
    file_count_match = REVIEW_FILE_COUNT_PATTERN.search(body)
    if range_match is None or file_count_match is None:
        return None
    file_count = int(file_count_match.group(1))
    if file_count <= 0:
        return None
    return range_match.group(1), range_match.group(2), file_count


def oid_matches(actual: str, reported: str) -> bool:
    actual_normalized = actual.lower()
    reported_normalized = reported.lower()
    return actual_normalized == reported_normalized or actual_normalized.startswith(
        reported_normalized
    )


def is_finished_review_reply(body: str) -> bool:
    return (
        COMMAND_INVOCATION_MARKER in body
        and FINISHED_REVIEW_PATTERN.search(unquoted_body(body)) is not None
    )


def matching_zero_finding_summary(
    pr: dict[str, Any], head_sha: str, after: datetime
) -> tuple[datetime, dict[str, Any]] | None:
    matches: list[tuple[datetime, dict[str, Any]]] = []
    for comment in pr["comments"]["nodes"]:
        if (comment.get("author") or {}).get("login", "") != "coderabbitai":
            continue
        body = comment.get("body") or ""
        legacy_zero_layout = (
            RECENT_REVIEW_MARKER in body
            and NO_ACTIONABLE_REVIEW_MARKER in body
        )
        risk_coverage_zero_layout = (
            SUBSTANTIVE_REVIEW_MARKER in body
            and FINAL_REVIEW_RISK_COVERAGE_MARKER in body
        )
        if not (legacy_zero_layout or risk_coverage_zero_layout):
            continue
        if (
            extract_section_count(body, OUTSIDE_DIFF_MARKER) > 0
            or extract_section_count(body, DUPLICATE_COMMENTS_MARKER) > 0
        ):
            continue
        updated_dt = parse_timestamp(comment.get("updatedAt"))
        scope = review_scope(body)
        if (
            updated_dt is None
            or updated_dt <= after
            or scope is None
            or not oid_matches(head_sha, scope[1])
        ):
            continue
        matches.append((updated_dt, comment))
    return max(matches, key=lambda match: match[0]) if matches else None


def summarize(repo: str, pr_number: int, payload: dict[str, Any]) -> ReviewSummary:
    pr = payload["data"]["repository"]["pullRequest"]
    latest_commit = pr["commits"]["nodes"][-1]["commit"]
    latest_commit_at = latest_commit["committedDate"]
    latest_commit_at_dt = parse_timestamp(latest_commit_at)

    unresolved_non_outdated = 0
    unresolved_outdated = 0
    for thread in pr["reviewThreads"]["nodes"]:
        if thread["isResolved"]:
            continue
        if thread["isOutdated"]:
            unresolved_outdated += 1
        else:
            unresolved_non_outdated += 1

    latest_explicit_review_request_at: str | None = None
    latest_explicit_review_request_dt: datetime | None = None
    latest_explicit_review_request_type: str | None = None
    review_requests: list[tuple[datetime, str]] = []
    substantive_review_evidence: list[tuple[datetime, str]] = []
    plan_ceiling_evidence: list[datetime] = []
    latest_coderabbit_review_finished_at: str | None = None
    latest_coderabbit_review_finished_dt: datetime | None = None
    latest_review_outcome_dt: datetime | None = None
    latest_review_outcome: str | None = None
    latest_rate_limit_at_dt: datetime | None = None
    latest_rate_limit_until_dt: datetime | None = None
    latest_rate_limit_without_expiry = False
    latest_actionable_comment_dt: datetime | None = None
    latest_actionable_comment_url: str | None = None
    outside_diff_actionable_comments = 0
    duplicate_actionable_comments = 0

    for comment in pr["comments"]["nodes"]:
        author = (comment.get("author") or {}).get("login", "")
        body = comment.get("body", "")
        created_at = comment.get("createdAt")
        created_at_dt = parse_timestamp(created_at)
        command_type = REVIEW_COMMAND_TYPES.get(normalize_command(body))
        if (
            author != "coderabbitai"
            and command_type is not None
            and created_at_dt is not None
        ):
            review_requests.append((created_at_dt, command_type))
            if (
                latest_explicit_review_request_dt is None
                or created_at_dt > latest_explicit_review_request_dt
            ):
                latest_explicit_review_request_dt = created_at_dt
                latest_explicit_review_request_at = created_at
                latest_explicit_review_request_type = command_type
        if author == "coderabbitai" and created_at_dt is not None:
            if is_substantive_review_body(body):
                substantive_review_evidence.append((created_at_dt, body))
            if PLAN_REVIEW_SKIP_MARKER in body and PLAN_CEILING_PATTERN.search(body):
                plan_ceiling_evidence.append(created_at_dt)
        if (
            author == "coderabbitai"
            and created_at_dt is not None
            and SUBSTANTIVE_REVIEW_MARKER in body
            and (
                latest_coderabbit_review_finished_dt is None
                or created_at_dt > latest_coderabbit_review_finished_dt
            )
        ):
            latest_coderabbit_review_finished_dt = created_at_dt
            latest_coderabbit_review_finished_at = created_at

    for review in (pr.get("reviews") or {}).get("nodes", []):
        if review.get("state") == "DISMISSED":
            continue
        author = (review.get("author") or {}).get("login", "")
        submitted_at = review.get("submittedAt")
        submitted_at_dt = parse_timestamp(submitted_at)
        if (
            author != "coderabbitai"
            or submitted_at_dt is None
            or not is_substantive_review_body(review.get("body") or "")
        ):
            continue
        substantive_review_evidence.append((submitted_at_dt, review.get("body") or ""))
        if (
            latest_coderabbit_review_finished_dt is None
            or submitted_at_dt > latest_coderabbit_review_finished_dt
        ):
            latest_coderabbit_review_finished_dt = submitted_at_dt
            latest_coderabbit_review_finished_at = submitted_at
        if (
            latest_commit_at_dt is not None
            and submitted_at_dt >= latest_commit_at_dt
            and (
                latest_review_outcome_dt is None
                or submitted_at_dt >= latest_review_outcome_dt
            )
        ):
            latest_review_outcome_dt = submitted_at_dt
            latest_review_outcome = "substantive"

    for review in (pr.get("reviews") or {}).get("nodes", []):
        if review.get("state") == "DISMISSED":
            continue
        author = (review.get("author") or {}).get("login", "")
        submitted_at_dt = parse_timestamp(review.get("submittedAt"))
        if (
            author == "coderabbitai"
            and submitted_at_dt is not None
            and PLAN_REVIEW_SKIP_MARKER in (review.get("body") or "")
            and PLAN_CEILING_PATTERN.search(review.get("body") or "")
        ):
            plan_ceiling_evidence.append(submitted_at_dt)

    if review_requests:
        latest_request_dt, latest_explicit_review_request_type = max(
            review_requests, key=lambda request: request[0]
        )
        latest_explicit_review_request_dt = latest_request_dt

    if latest_explicit_review_request_dt is not None:
        zero_finding_summary = matching_zero_finding_summary(
            pr, pr["headRefOid"], latest_explicit_review_request_dt
        )
        finished_replies = [
            comment
            for comment in pr["comments"]["nodes"]
            if (comment.get("author") or {}).get("login", "") == "coderabbitai"
            and (created_dt := parse_timestamp(comment.get("createdAt"))) is not None
            and created_dt > latest_explicit_review_request_dt
            and is_finished_review_reply(comment.get("body") or "")
        ]
        if zero_finding_summary is not None and finished_replies:
            completed_dt, summary_comment = zero_finding_summary
            substantive_review_evidence.append(
                (completed_dt, summary_comment.get("body") or "")
            )
            if (
                latest_coderabbit_review_finished_dt is None
                or completed_dt > latest_coderabbit_review_finished_dt
            ):
                latest_coderabbit_review_finished_dt = completed_dt
                latest_coderabbit_review_finished_at = completed_dt.isoformat()
            if (
                latest_commit_at_dt is not None
                and completed_dt >= latest_commit_at_dt
                and (
                    latest_review_outcome_dt is None
                    or completed_dt >= latest_review_outcome_dt
                )
            ):
                latest_review_outcome_dt = completed_dt
                latest_review_outcome = "substantive"

    prior_substantive_review_checkpoint = False
    plan_ceiling_rejection_evidence = False
    reviewed_commit_range_after_latest_commit = False
    incremental_review_exception_allowed = False
    if latest_explicit_review_request_type == "incremental":
        latest_incremental_request_dt = latest_explicit_review_request_dt
        prior_full_requests = [
            request_dt
            for request_dt, request_type in review_requests
            if request_type == "full" and request_dt < latest_incremental_request_dt
        ]
        latest_full_request_dt = max(prior_full_requests, default=None)
        if latest_full_request_dt is not None:
            prior_substantive_review_checkpoint = any(
                latest_full_request_dt < evidence_dt < latest_incremental_request_dt
                and SUBSTANTIVE_REVIEW_MARKER in body
                for evidence_dt, body in substantive_review_evidence
            )
            plan_ceiling_rejection_evidence = any(
                latest_full_request_dt <= evidence_dt <= latest_incremental_request_dt
                for evidence_dt in plan_ceiling_evidence
            )
        for evidence_dt, body in substantive_review_evidence:
            if evidence_dt < latest_incremental_request_dt or (
                latest_commit_at_dt is not None and evidence_dt < latest_commit_at_dt
            ):
                continue
            scope = review_scope(body)
            if scope is None or SUBSTANTIVE_REVIEW_MARKER not in body:
                continue
            reviewed_base_sha, reviewed_head_sha, _ = scope
            if reviewed_base_sha.lower() != reviewed_head_sha.lower() and oid_matches(
                pr["headRefOid"], reviewed_head_sha
            ):
                reviewed_commit_range_after_latest_commit = True
                break
        incremental_review_exception_allowed = (
            prior_substantive_review_checkpoint
            and plan_ceiling_rejection_evidence
            and reviewed_commit_range_after_latest_commit
        )

    explicit_review_after_latest_commit = (
        latest_explicit_review_request_dt is not None
        and latest_commit_at_dt is not None
        and latest_explicit_review_request_dt > latest_commit_at_dt
    )
    review_finished_after_latest_request = (
        latest_explicit_review_request_dt is not None
        and latest_coderabbit_review_finished_dt is not None
        and latest_coderabbit_review_finished_dt >= latest_explicit_review_request_dt
    )
    substantive_review_after_latest_commit = (
        latest_coderabbit_review_finished_dt is not None
        and latest_commit_at_dt is not None
        and latest_coderabbit_review_finished_dt >= latest_commit_at_dt
    )
    latest_review_trigger_dt = latest_explicit_review_request_dt
    if latest_commit_at_dt is not None and (
        latest_review_trigger_dt is None
        or latest_commit_at_dt > latest_review_trigger_dt
    ):
        latest_review_trigger_dt = latest_commit_at_dt
    for comment in pr["comments"]["nodes"]:
        if (comment.get("author") or {}).get("login", "") != "coderabbitai":
            continue
        created_at_dt = parse_timestamp(comment.get("createdAt"))
        if created_at_dt is None:
            continue
        body = comment.get("body", "")
        if is_substantive_review_body(body):
            continue
        has_rate_limit_marker = REVIEW_LIMIT_MARKER in body
        detection_body = body if has_rate_limit_marker else unquoted_body(body)
        if (
            not has_rate_limit_marker
            and REVIEW_LIMIT_STATUS_PATTERN.search(detection_body) is None
            and REVIEW_LIMIT_COMPLETE_MESSAGE_PATTERN.search(detection_body) is None
        ):
            continue
        if (
            latest_review_trigger_dt is not None
            and created_at_dt < latest_review_trigger_dt
        ):
            continue
        if (
            latest_rate_limit_at_dt is not None
            and created_at_dt < latest_rate_limit_at_dt
        ):
            continue
        latest_rate_limit_at_dt = created_at_dt
        latest_rate_limit_until_dt = parse_review_rate_limit_until(
            detection_body, created_at_dt
        )
        latest_rate_limit_without_expiry = latest_rate_limit_until_dt is None

    for comment in pr["comments"]["nodes"]:
        if (comment.get("author") or {}).get("login", "") != "coderabbitai":
            continue
        created_at_dt = parse_timestamp(comment.get("createdAt"))
        if created_at_dt is None:
            continue
        body = comment.get("body", "")
        if latest_commit_at_dt is None or created_at_dt < latest_commit_at_dt:
            continue

        outcome: str | None = None
        if SUBSTANTIVE_REVIEW_MARKER in body:
            outcome = "substantive"
        elif (
            latest_explicit_review_request_dt is not None
            and created_at_dt >= latest_explicit_review_request_dt
            and NOOP_REVIEW_MARKER in body
        ):
            outcome = "noop"
        elif (
            latest_explicit_review_request_dt is not None
            and created_at_dt >= latest_explicit_review_request_dt
            and FAILED_REVIEW_PATTERN.search(unquoted_body(body))
        ):
            outcome = "failed"

        if outcome is not None and (
            latest_review_outcome_dt is None
            or created_at_dt >= latest_review_outcome_dt
        ):
            latest_review_outcome_dt = created_at_dt
            latest_review_outcome = outcome

    rate_limit_superseded_by_substantive_review = (
        latest_rate_limit_at_dt is not None
        and latest_coderabbit_review_finished_dt is not None
        and latest_coderabbit_review_finished_dt > latest_rate_limit_at_dt
    )
    latest_review_request_rate_limited = (
        not rate_limit_superseded_by_substantive_review
        and (
            latest_rate_limit_without_expiry
            or (
                latest_rate_limit_until_dt is not None
                and latest_rate_limit_until_dt > datetime.now(timezone.utc)
            )
        )
    )
    latest_review_request_noop = latest_review_outcome == "noop"
    latest_review_request_failed = latest_review_outcome == "failed"
    actionable_items = [
        (
            (comment.get("author") or {}).get("login", ""),
            comment.get("body") or "",
            comment.get("createdAt"),
            comment.get("url"),
            None,
        )
        for comment in pr["comments"]["nodes"]
    ]
    actionable_items.extend(
        (
            (review.get("author") or {}).get("login", ""),
            review.get("body") or "",
            review.get("submittedAt"),
            review.get("url"),
            review.get("state"),
        )
        for review in (pr.get("reviews") or {}).get("nodes", [])
    )
    for author, body, timestamp, url, state in actionable_items:
        candidate = actionable_summary_candidate(
            author,
            body,
            timestamp,
            url,
            state,
            latest_explicit_review_request_dt,
        )
        if candidate is None:
            continue
        timestamp_dt, candidate_url, outside_count, duplicate_count = candidate
        if (
            latest_actionable_comment_dt is None
            or timestamp_dt > latest_actionable_comment_dt
        ):
            latest_actionable_comment_dt = timestamp_dt
            latest_actionable_comment_url = candidate_url
            outside_diff_actionable_comments = outside_count
            duplicate_actionable_comments = duplicate_count

    unresolved_total = unresolved_non_outdated + unresolved_outdated
    latest_review_request_still_running = (
        explicit_review_after_latest_commit
        and not review_finished_after_latest_request
        and latest_rate_limit_at_dt is None
        and not latest_review_request_noop
        and not latest_review_request_failed
    )
    retrigger_review_allowed = (
        unresolved_total == 0
        and not latest_review_request_still_running
        and not latest_review_request_rate_limited
        and not latest_review_request_noop
    )
    manual_thread_resolution_required = unresolved_total > 0
    must_resolve_outdated_threads = unresolved_outdated > 0

    reasons: list[str] = []
    if unresolved_non_outdated:
        reasons.append(
            f"{unresolved_non_outdated} unresolved non-outdated CodeRabbit thread(s) remain; "
            "verify their fixes in HEAD, then manually resolve only verified-addressed threads"
        )
    if unresolved_outdated:
        reasons.append(
            f"{unresolved_outdated} unresolved outdated CodeRabbit thread(s) remain; "
            "verify their fixes in HEAD, then manually resolve only verified-addressed threads"
        )
    if outside_diff_actionable_comments:
        reasons.append(
            f"{outside_diff_actionable_comments} top-level outside-diff CodeRabbit comment(s) "
            "remain from the latest review; verify and fix them before calling the PR review-clean"
        )
    if duplicate_actionable_comments:
        reasons.append(
            f"{duplicate_actionable_comments} top-level duplicate CodeRabbit comment(s) remain "
            "from the latest review; verify and fix them before calling the PR review-clean"
        )
    if not explicit_review_after_latest_commit:
        reasons.append(
            "no explicit CodeRabbit review request found after the latest PR commit"
        )
    if not review_finished_after_latest_request:
        reasons.append(
            "no substantive CodeRabbit review summary found after the latest explicit review request"
        )
    if not substantive_review_after_latest_commit:
        reasons.append(
            "no substantive CodeRabbit review summary found after the latest PR commit"
        )
    if latest_review_request_rate_limited:
        reasons.append(
            "latest CodeRabbit review attempt after the PR commit was rate limited; do not retrigger yet"
        )
    if latest_review_request_noop:
        reasons.append(
            "latest explicit CodeRabbit review request was acknowledged without reviewing commits"
        )
    if latest_review_request_failed:
        reasons.append("latest explicit CodeRabbit review request failed")
    if (
        latest_explicit_review_request_type == "incremental"
        and not incremental_review_exception_allowed
    ):
        reasons.append(
            "incremental CodeRabbit review is not accepted without machine-readable evidence of "
            "a prior substantive checkpoint, a plan-ceiling rejection, and a reviewed commit/file "
            "range after the latest PR commit"
        )

    return ReviewSummary(
        repo=repo,
        pr_number=pr_number,
        head_sha=pr["headRefOid"],
        latest_commit_at=latest_commit_at,
        unresolved_non_outdated=unresolved_non_outdated,
        unresolved_outdated=unresolved_outdated,
        unresolved_total=unresolved_total,
        latest_explicit_review_request_at=latest_explicit_review_request_at,
        latest_explicit_review_request_type=latest_explicit_review_request_type,
        latest_coderabbit_review_finished_at=latest_coderabbit_review_finished_at,
        explicit_review_after_latest_commit=explicit_review_after_latest_commit,
        review_finished_after_latest_request=review_finished_after_latest_request,
        substantive_review_after_latest_commit=substantive_review_after_latest_commit,
        latest_review_request_rate_limited=latest_review_request_rate_limited,
        review_rate_limit_until=(
            latest_rate_limit_until_dt.isoformat()
            if latest_rate_limit_until_dt is not None
            else None
        ),
        latest_review_request_noop=latest_review_request_noop,
        latest_review_request_failed=latest_review_request_failed,
        retrigger_review_allowed=retrigger_review_allowed,
        manual_thread_resolution_required=manual_thread_resolution_required,
        must_resolve_outdated_threads=must_resolve_outdated_threads,
        outside_diff_actionable_comments=outside_diff_actionable_comments,
        duplicate_actionable_comments=duplicate_actionable_comments,
        latest_actionable_comment_url=latest_actionable_comment_url,
        prior_substantive_review_checkpoint=prior_substantive_review_checkpoint,
        plan_ceiling_rejection_evidence=plan_ceiling_rejection_evidence,
        reviewed_commit_range_after_latest_commit=reviewed_commit_range_after_latest_commit,
        incremental_review_exception_allowed=incremental_review_exception_allowed,
        ok=not reasons,
        reasons=reasons,
    )


def immutable_database_id(item: dict[str, Any]) -> int | None:
    value = item.get("databaseId")
    if isinstance(value, int) and value > 0:
        return value
    value = item.get("id")
    if isinstance(value, int) and value > 0:
        return value
    if isinstance(value, str) and value.isdigit() and int(value) > 0:
        return int(value)
    return None


def load_trigger_record(path: str, repo: str, pr_number: int) -> dict[str, Any]:
    record = json.loads(Path(path).read_text(encoding="utf-8"))
    if not isinstance(record, dict):
        raise TypeError("trigger record must be a JSON object")
    if record.get("repository") != repo or record.get("pr_number") != pr_number:
        raise ValueError(
            "trigger record repository or pull request does not match the request"
        )
    head_sha = record.get("head_sha")
    if not isinstance(head_sha, str) or not re.fullmatch(r"[0-9a-fA-F]{40}", head_sha):
        raise ValueError("trigger record has no exact 40-character head SHA")
    return record


def trigger_state(
    repo: str,
    pr_number: int,
    payload: dict[str, Any],
    record: dict[str, Any],
) -> TriggerState:
    pr = payload["data"]["repository"]["pullRequest"]
    current_head = pr["headRefOid"]
    captured_head = record["head_sha"]
    trigger = record.get("trigger")
    if not isinstance(trigger, dict):
        return TriggerState(
            "unattributed",
            True,
            False,
            repo,
            pr_number,
            captured_head,
            current_head,
            None,
            None,
            None,
            None,
            None,
            None,
            None,
            None,
            "the durable posting reservation has no captured GitHub comment response; adjudicate before retrying",
        )

    trigger_id = trigger.get("id")
    trigger_created_at = trigger.get("created_at")
    trigger_url = trigger.get("url")
    trigger_type = trigger.get("type")
    if (
        not isinstance(trigger_id, int)
        or trigger_id <= 0
        or parse_timestamp(trigger_created_at) is None
        or not isinstance(trigger_url, str)
        or not trigger_url
        or trigger_type != "full"
    ):
        raise ValueError("trigger record has invalid immutable trigger fields")
    trigger_dt = parse_timestamp(trigger_created_at)
    assert trigger_dt is not None

    comments = pr["comments"]["nodes"]
    captured_comment: dict[str, Any] | None = None
    other_triggers: list[tuple[datetime, dict[str, Any]]] = []
    prior_triggers: list[tuple[datetime, dict[str, Any]]] = []
    for comment in comments:
        comment_id = immutable_database_id(comment)
        created_dt = parse_timestamp(comment.get("createdAt"))
        command_type = REVIEW_COMMAND_TYPES.get(
            normalize_command(comment.get("body") or "")
        )
        author = (comment.get("author") or {}).get("login", "")
        if comment_id == trigger_id:
            captured_comment = comment
        if (
            author != "coderabbitai"
            and command_type is not None
            and created_dt is not None
            and created_dt >= trigger_dt
            and comment_id != trigger_id
        ):
            other_triggers.append((created_dt, comment))
        if (
            author != "coderabbitai"
            and command_type is not None
            and created_dt is not None
            and created_dt < trigger_dt
        ):
            prior_triggers.append((created_dt, comment))

    base = {
        "repository": repo,
        "pr_number": pr_number,
        "head_sha": captured_head,
        "current_head_sha": current_head,
        "trigger_comment_id": trigger_id,
        "trigger_created_at": trigger_created_at,
        "trigger_url": trigger_url,
        "trigger_type": trigger_type,
    }
    if record.get("status") in {
        "posted_boundary_changed",
        "posted_boundary_unverified",
    }:
        return TriggerState(
            "ambiguous",
            True,
            False,
            **base,
            response_id=None,
            response_created_at=None,
            response_url=None,
            cooldown_until=None,
            reason="the pull request state or head could not be held stable across the posting boundary",
        )
    if captured_comment is None:
        return TriggerState(
            "unattributed",
            True,
            False,
            **base,
            response_id=None,
            response_created_at=None,
            response_url=None,
            cooldown_until=None,
            reason="the captured trigger comment ID is absent from the complete GitHub comment history",
        )
    if (
        (captured_comment.get("author") or {}).get("login", "") == "coderabbitai"
        or REVIEW_COMMAND_TYPES.get(
            normalize_command(captured_comment.get("body") or "")
        )
        != "full"
        or captured_comment.get("createdAt") != trigger_created_at
        or captured_comment.get("url") != trigger_url
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
            reason="the captured trigger comment no longer matches its immutable record",
        )
    if record.get("status") == "retired":
        retirement = record.get("retirement")
        if not isinstance(retirement, dict):
            raise ValueError("retired trigger record has no retirement evidence")
        retired_at = retirement.get("retired_at")
        retirement_reason = retirement.get("reason")
        if (
            retirement.get("action") != "operator_retire"
            or parse_timestamp(retired_at) is None
            or not isinstance(retirement_reason, str)
            or not retirement_reason
        ):
            raise ValueError("retired trigger record has invalid retirement evidence")
        return TriggerState(
            "retired",
            True,
            True,
            **base,
            response_id=None,
            response_created_at=None,
            response_url=None,
            cooldown_until=None,
            reason="the stale trigger was explicitly retired by an operator",
            age_seconds=age_seconds(trigger_dt),
        )
    if any(created_dt == trigger_dt for created_dt, _ in other_triggers):
        return TriggerState(
            "ambiguous",
            True,
            False,
            **base,
            response_id=None,
            response_created_at=None,
            response_url=None,
            cooldown_until=None,
            reason="a concurrent review trigger prevents unique response attribution",
        )
    next_trigger_dt = min(
        (created_dt for created_dt, _ in other_triggers if created_dt > trigger_dt),
        default=None,
    )

    if prior_triggers:
        prior_dt = max(prior_triggers, key=lambda item: item[0])[0]
        prior_terminal = False
        for comment in comments:
            if (comment.get("author") or {}).get("login", "") != "coderabbitai":
                continue
            created_dt = parse_timestamp(comment.get("createdAt"))
            if created_dt is None or not (prior_dt < created_dt < trigger_dt):
                continue
            body = comment.get("body") or ""
            detection_body = (
                body if REVIEW_LIMIT_MARKER in body else unquoted_body(body)
            )
            cooldown = parse_review_rate_limit_until(detection_body, created_dt)
            if (
                is_substantive_review_body(body)
                or NOOP_REVIEW_MARKER in body
                or FAILED_REVIEW_PATTERN.search(unquoted_body(body))
                or cooldown is not None
                or is_finished_review_reply(body)
            ):
                prior_terminal = True
        for review in (pr.get("reviews") or {}).get("nodes", []):
            submitted_dt = parse_timestamp(review.get("submittedAt"))
            if (
                (review.get("author") or {}).get("login", "") == "coderabbitai"
                and review.get("state") != "DISMISSED"
                and submitted_dt is not None
                and prior_dt < submitted_dt < trigger_dt
                and is_substantive_review_body(review.get("body") or "")
            ):
                prior_terminal = True
        if not prior_terminal:
            return TriggerState(
                "ambiguous",
                True,
                False,
                **base,
                response_id=None,
                response_created_at=None,
                response_url=None,
                cooldown_until=None,
                reason="an earlier review trigger has no terminal response before the captured trigger",
            )

    candidates: list[tuple[datetime, str, dict[str, Any], str | None]] = []
    zero_finding_summary = matching_zero_finding_summary(pr, captured_head, trigger_dt)
    for comment in comments:
        if (comment.get("author") or {}).get("login", "") != "coderabbitai":
            continue
        created_dt = parse_timestamp(comment.get("createdAt"))
        if (
            created_dt is None
            or created_dt <= trigger_dt
            or (next_trigger_dt is not None and created_dt >= next_trigger_dt)
        ):
            continue
        body = comment.get("body") or ""
        detection_body = body if REVIEW_LIMIT_MARKER in body else unquoted_body(body)
        state: str | None = None
        cooldown: str | None = None
        if (
            REVIEW_LIMIT_MARKER in body
            or REVIEW_LIMIT_STATUS_PATTERN.search(detection_body)
            or REVIEW_LIMIT_COMPLETE_MESSAGE_PATTERN.search(detection_body)
        ):
            until = parse_review_rate_limit_until(detection_body, created_dt)
            if until is not None:
                state = "rate_limited"
                cooldown = until.isoformat()
        elif NOOP_REVIEW_MARKER in body:
            state = "noop"
        elif is_substantive_review_body(body):
            scope = review_scope(body)
            state = (
                "completed"
                if scope is not None and oid_matches(captured_head, scope[1])
                else "ambiguous"
            )
        elif is_finished_review_reply(body) and zero_finding_summary is not None:
            state = "completed"
        elif FAILED_REVIEW_PATTERN.search(unquoted_body(body)):
            state = "failed"
        elif ACTIVE_REVIEW_PATTERN.search(unquoted_body(body)):
            state = "active"
        if state is not None:
            candidates.append((created_dt, state, comment, cooldown))

    for review in (pr.get("reviews") or {}).get("nodes", []):
        if (
            review.get("state") == "DISMISSED"
            or (review.get("author") or {}).get("login", "") != "coderabbitai"
        ):
            continue
        submitted_dt = parse_timestamp(review.get("submittedAt"))
        body = review.get("body") or ""
        if (
            submitted_dt is None
            or submitted_dt <= trigger_dt
            or (next_trigger_dt is not None and submitted_dt >= next_trigger_dt)
            or not is_substantive_review_body(body)
        ):
            continue
        reviewed_oid = (review.get("commit") or {}).get("oid")
        scope = review_scope(body)
        matches = (
            isinstance(reviewed_oid, str)
            and reviewed_oid.casefold() == captured_head.casefold()
        ) or (scope is not None and oid_matches(captured_head, scope[1]))
        candidates.append(
            (submitted_dt, "completed" if matches else "ambiguous", review, None)
        )

    if not candidates:
        if next_trigger_dt is not None:
            return TriggerState(
                "ambiguous",
                True,
                False,
                **base,
                response_id=None,
                response_created_at=None,
                response_url=None,
                cooldown_until=None,
                reason="a later review trigger arrived before an attributable response",
            )
        if record.get("status") == "timed_out":
            timeout = record.get("timeout")
            if (
                not isinstance(timeout, dict)
                or parse_timestamp(timeout.get("at")) is None
                or timeout.get("observed_state") not in {"awaiting_response", "active"}
                or timeout.get("reason")
                != "bounded wait expired before a terminal CodeRabbit response"
            ):
                raise ValueError("timed-out trigger record has invalid timeout evidence")
            timeout_reason = (
                timeout.get("reason")
                if isinstance(timeout, dict)
                else None
            )
            return TriggerState(
                "timed_out",
                True,
                True,
                **base,
                response_id=None,
                response_created_at=None,
                response_url=None,
                cooldown_until=None,
                reason=(
                    timeout_reason
                    if isinstance(timeout_reason, str) and timeout_reason
                    else "bounded wait expired before a terminal CodeRabbit response"
                ),
                age_seconds=age_seconds(trigger_dt),
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
            reason="no qualifying CodeRabbit response is strictly newer than the captured trigger",
            age_seconds=age_seconds(trigger_dt),
        )

    terminal_candidates = [
        candidate for candidate in candidates if candidate[1] != "active"
    ]
    if not terminal_candidates and next_trigger_dt is not None:
        return TriggerState(
            "ambiguous",
            True,
            False,
            **base,
            response_id=None,
            response_created_at=None,
            response_url=None,
            cooldown_until=None,
            reason="a later review trigger arrived while the captured request was active",
        )
    relevant = terminal_candidates or candidates
    latest_dt = max(candidate[0] for candidate in relevant)
    latest = [candidate for candidate in relevant if candidate[0] == latest_dt]
    states = {candidate[1] for candidate in latest}
    if len(states) != 1 or len(latest) != 1:
        return TriggerState(
            "ambiguous",
            True,
            False,
            **base,
            response_id=None,
            response_created_at=latest_dt.isoformat(),
            response_url=None,
            cooldown_until=None,
            reason="multiple same-time responses prevent unique outcome attribution",
        )
    response_dt, state, response, cooldown = latest[0]
    response_id = immutable_database_id(response)
    if response_id is None:
        return TriggerState(
            "unattributed",
            True,
            False,
            **base,
            response_id=None,
            response_created_at=response_dt.isoformat(),
            response_url=response.get("url"),
            cooldown_until=cooldown,
            reason="the qualifying CodeRabbit response has no immutable numeric GitHub ID",
        )
    reason = {
        "active": "CodeRabbit acknowledged that the full review is active",
        "completed": "a substantive CodeRabbit review matching the captured head completed",
        "rate_limited": "CodeRabbit explicitly rate limited the request",
        "failed": "CodeRabbit explicitly reported a review failure",
        "noop": "CodeRabbit acknowledged the request without reviewing commits",
        "ambiguous": "the substantive response does not identify the captured head",
    }[state]
    return TriggerState(
        state,
        state != "active",
        state != "ambiguous",
        **base,
        response_id=response_id,
        response_created_at=response.get("createdAt") or response.get("submittedAt"),
        response_url=response.get("url"),
        cooldown_until=cooldown,
        reason=reason,
        age_seconds=age_seconds(trigger_dt),
    )


RETIREMENT_REASON_MAX_LENGTH = 240
LINE_SEPARATOR_CODEPOINTS = {0x7F, 0x85, 0x2028, 0x2029}


def validate_retirement_reason(reason: str) -> None:
    if not isinstance(reason, str) or not reason.strip():
        raise ValueError("retirement reason must be non-empty")
    if len(reason) > RETIREMENT_REASON_MAX_LENGTH:
        raise ValueError(
            f"retirement reason must be at most {RETIREMENT_REASON_MAX_LENGTH} characters"
        )
    if any(
        ord(character) < 0x20 or ord(character) in LINE_SEPARATOR_CODEPOINTS
        for character in reason
    ):
        raise ValueError("retirement reason must be one line without control characters")


def retire_trigger_record(
    path: str,
    repo: str,
    pr_number: int,
    trigger_id: int,
    expected_head_sha: str,
    reason: str,
    payload: dict[str, Any],
) -> dict[str, Any]:
    """Retire one stale record after checking its live identity and evidence."""

    if trigger_id <= 0:
        raise ValueError("trigger identity must be a positive integer")
    if not re.fullmatch(r"[0-9a-fA-F]{40}", expected_head_sha):
        raise ValueError("expected head SHA must be exactly 40 hexadecimal characters")
    validate_retirement_reason(reason)

    record = load_trigger_record(path, repo, pr_number)
    record_status = record.get("status")
    if record_status not in {"timed_out", "posted"}:
        raise ValueError(
            "trigger record is not a posted or persisted bounded-wait trigger; retirement is manual"
        )
    if record_status == "timed_out":
        timeout = record.get("timeout")
        if (
            not isinstance(timeout, dict)
            or parse_timestamp(timeout.get("at")) is None
            or timeout.get("observed_state") not in {"awaiting_response", "active"}
            or timeout.get("reason")
            != "bounded wait expired before a terminal CodeRabbit response"
        ):
            raise ValueError("timed-out trigger record has invalid timeout evidence")
    summary = summarize(repo, pr_number, payload)
    state = trigger_state(repo, pr_number, payload, record)
    if state.trigger_comment_id != trigger_id:
        raise ValueError("trigger identity does not match the durable record")
    current_head = payload["data"]["repository"]["pullRequest"].get("headRefOid")
    if not isinstance(current_head, str) or not re.fullmatch(
        r"[0-9a-fA-F]{40}", current_head
    ):
        raise ValueError("current pull-request head is not an exact SHA")
    if current_head.casefold() != expected_head_sha.casefold():
        raise ValueError("expected head SHA does not match the current pull-request head")
    if state.current_head_sha.casefold() != expected_head_sha.casefold():
        raise ValueError("checker head evidence does not match the expected head SHA")
    if (
        state.state == "active"
        and state.head_sha.casefold() == expected_head_sha.casefold()
    ):
        raise ValueError("cannot retire a trigger with an attributable active review")
    if state.state == "ambiguous":
        raise ValueError("cannot retire a trigger with ambiguous response evidence")
    if state.state not in {"timed_out", "active"}:
        if record_status == "posted" and state.state == "awaiting_response":
            raise ValueError(
                "posted trigger has no active response or persisted timeout evidence"
            )
        raise ValueError(
            f"cannot retire trigger with current evidence state {state.state}"
        )
    if not summary.retrigger_review_allowed:
        raise ValueError(
            "current pull-request review evidence does not permit a deterministic retry"
        )

    retired_at = utc_now()
    retired_record = dict(record)
    retired_record["status"] = "retired"
    retired_record["retirement"] = {
        "action": "operator_retire",
        "retired_at": retired_at,
        "reason": reason.strip(),
        "trigger_comment_id": trigger_id,
        "expected_head_sha": expected_head_sha,
        "evidence": {
            "state": state.state,
            "captured_head_sha": record["head_sha"],
            "current_head_sha": state.current_head_sha,
        },
    }
    atomic_write_json(Path(path), retired_record)
    return {
        "operation": "retire_trigger",
        "status": "retired",
        "repository": repo,
        "pr_number": pr_number,
        "trigger_comment_id": trigger_id,
        "captured_head_sha": record["head_sha"],
        "expected_head_sha": expected_head_sha,
        "retired_at": retired_at,
        "reason": reason.strip(),
        "message": (
            "Hosted CodeRabbit trigger "
            f"{trigger_id} retired for current head {expected_head_sha}"
        ),
    }


def emit_retirement_text(result: dict[str, Any]) -> None:
    for key, value in result.items():
        print(f"{key}={value}")


def persist_timeout_if_current(
    path: str, expected_record: dict[str, Any], state: TriggerState
) -> bool:
    """Persist timeout evidence only if the waiter still owns its record version."""

    record_path = Path(path)
    lock_path = record_path.parent / "request.lock"
    lock_path.parent.mkdir(parents=True, exist_ok=True)
    lock_descriptor = os.open(lock_path, os.O_RDWR | os.O_CREAT, stat.S_IRUSR | stat.S_IWUSR)
    try:
        os.fchmod(lock_descriptor, stat.S_IRUSR | stat.S_IWUSR)
        fcntl.flock(lock_descriptor, fcntl.LOCK_EX)
        try:
            current_record = load_trigger_record(
                path,
                expected_record["repository"],
                expected_record["pr_number"],
            )
        except (OSError, TypeError, ValueError, json.JSONDecodeError):
            return False
        if trigger_record_version(current_record) != trigger_record_version(expected_record):
            return False
        timed_out_record = dict(expected_record)
        timed_out_record["status"] = "timed_out"
        timed_out_record["timeout"] = {
            "at": utc_now(),
            "observed_state": state.state,
            "observed_response_id": state.response_id,
            "reason": "bounded wait expired before a terminal CodeRabbit response",
        }
        atomic_write_json(record_path, timed_out_record)
        return True
    finally:
        fcntl.flock(lock_descriptor, fcntl.LOCK_UN)
        os.close(lock_descriptor)


def emit_trigger_text(state: TriggerState) -> None:
    if state.manual_adjudication_required:
        trigger_id = state.trigger_comment_id or "unknown"
        age = (
            f"{state.age_seconds} seconds"
            if state.age_seconds is not None
            else "unknown age"
        )
        print(
            "warning=HOSTED CODERABBIT TRIGGER "
            f"{trigger_id} HAS NO ATTRIBUTABLE TERMINAL RESPONSE; "
            f"AGE={age.upper()}; MANUAL OVERSEER ADJUDICATION REQUIRED BEFORE RETRYING"
        )
    for key, value in state.__dict__.items():
        if isinstance(value, bool):
            value = str(value).lower()
        elif value is None:
            value = "none"
        print(f"trigger_{key}={value}")


def emit_text(summary: ReviewSummary) -> None:
    print(f"repo={summary.repo}")
    print(f"pr_number={summary.pr_number}")
    print(f"head_sha={summary.head_sha}")
    print(f"latest_commit_at={summary.latest_commit_at}")
    print(f"unresolved_non_outdated={summary.unresolved_non_outdated}")
    print(f"unresolved_outdated={summary.unresolved_outdated}")
    print(f"unresolved_total={summary.unresolved_total}")
    print(
        f"latest_explicit_review_request_at={summary.latest_explicit_review_request_at or 'none'}"
    )
    print(
        f"latest_explicit_review_request_type={summary.latest_explicit_review_request_type or 'none'}"
    )
    print(
        f"latest_coderabbit_review_finished_at={summary.latest_coderabbit_review_finished_at or 'none'}"
    )
    print(
        f"explicit_review_after_latest_commit={str(summary.explicit_review_after_latest_commit).lower()}"
    )
    print(
        f"review_finished_after_latest_request={str(summary.review_finished_after_latest_request).lower()}"
    )
    print(
        f"substantive_review_after_latest_commit={str(summary.substantive_review_after_latest_commit).lower()}"
    )
    print(
        f"latest_review_request_rate_limited={str(summary.latest_review_request_rate_limited).lower()}"
    )
    print(f"review_rate_limit_until={summary.review_rate_limit_until or 'unknown'}")
    print(
        f"latest_review_request_noop={str(summary.latest_review_request_noop).lower()}"
    )
    print(
        f"latest_review_request_failed={str(summary.latest_review_request_failed).lower()}"
    )
    print(f"retrigger_review_allowed={str(summary.retrigger_review_allowed).lower()}")
    print(
        f"manual_thread_resolution_required={str(summary.manual_thread_resolution_required).lower()}"
    )
    print(
        f"must_resolve_outdated_threads={str(summary.must_resolve_outdated_threads).lower()}"
    )
    print(
        f"outside_diff_actionable_comments={summary.outside_diff_actionable_comments}"
    )
    print(f"duplicate_actionable_comments={summary.duplicate_actionable_comments}")
    print(
        f"latest_actionable_comment_url={summary.latest_actionable_comment_url or 'none'}"
    )
    print(
        f"prior_substantive_review_checkpoint={str(summary.prior_substantive_review_checkpoint).lower()}"
    )
    print(
        f"plan_ceiling_rejection_evidence={str(summary.plan_ceiling_rejection_evidence).lower()}"
    )
    print(
        f"reviewed_commit_range_after_latest_commit={str(summary.reviewed_commit_range_after_latest_commit).lower()}"
    )
    print(
        f"incremental_review_exception_allowed={str(summary.incremental_review_exception_allowed).lower()}"
    )
    print(f"ok={str(summary.ok).lower()}")
    if summary.unresolved_outdated:
        print(
            "warning=UNRESOLVED OUTDATED CODERABBIT THREADS BLOCK MERGE. VERIFY "
            "EACH FINDING AGAINST HEAD, FIX LIVE ISSUES, THEN MANUALLY RESOLVE "
            "ONLY VERIFIED-ADDRESSED THREADS"
        )
    if (
        summary.outside_diff_actionable_comments
        or summary.duplicate_actionable_comments
    ):
        print(
            "warning=TOP-LEVEL CODERABBIT ACTIONABLE COMMENTS CAN EXIST OUTSIDE "
            "INLINE REVIEW THREADS; VERIFY THE LATEST CODERABBIT SUMMARY COMMENT "
            "BEFORE CALLING THE PR REVIEW-CLEAN"
        )
    if summary.unresolved_non_outdated or summary.unresolved_outdated:
        print(
            "warning=UNRESOLVED CODERABBIT THREADS BLOCK MERGE. VERIFY EACH "
            "CURRENT AND OUTDATED FINDING AGAINST HEAD, FIX LIVE ISSUES, THEN "
            "MANUALLY RESOLVE ONLY VERIFIED-ADDRESSED THREADS"
        )
    if (
        summary.unresolved_total == 0
        and summary.latest_coderabbit_review_finished_at is not None
        and not summary.substantive_review_after_latest_commit
    ):
        print(
            "warning=A SMALL FOLLOW-UP MAY MERGE WITHOUT A FRESH CODERABBIT RUN ONLY WHEN "
            "EVERY POST-REVIEW COMMIT DIRECTLY ADDRESSES REVIEW FINDINGS; VERIFY THAT SCOPE "
            "AND GREEN CI MANUALLY"
        )
    if summary.reasons:
        for reason in summary.reasons:
            print(f"reason={reason}")


def main() -> int:
    args = parse_args()
    retirement_flags = (
        args.retire_trigger is not None,
        args.expected_head_sha is not None,
        args.reason is not None,
    )
    if any(retirement_flags) and not all(retirement_flags):
        print(
            "error=--retire-trigger, --expected-head-sha, and --reason must be supplied together",
            file=sys.stderr,
        )
        return 2
    if args.retire_trigger is not None and not args.trigger_record:
        print("error=--retire-trigger requires --trigger-record", file=sys.stderr)
        return 2
    if args.retire_trigger is not None and args.wait:
        print("error=--retire-trigger cannot be combined with --wait", file=sys.stderr)
        return 2
    if args.wait and not args.trigger_record:
        print("error=--wait requires --trigger-record", file=sys.stderr)
        return 2
    valid_wait_values = (
        math.isfinite(args.timeout)
        and math.isfinite(args.poll_interval)
        and 0 <= args.timeout <= 86400
        and 0.1 <= args.poll_interval <= 300
        and (args.timeout == 0 or args.poll_interval <= args.timeout)
    )
    if not valid_wait_values:
        print("error=invalid bounded wait values", file=sys.stderr)
        return 2
    try:
        record = (
            load_trigger_record(args.trigger_record, args.repo, args.pr)
            if args.trigger_record
            else None
        )
        if args.retire_trigger is not None:
            payload = load_payload(args.input, args.repo, args.pr)
            result = retire_trigger_record(
                args.trigger_record,
                args.repo,
                args.pr,
                args.retire_trigger,
                args.expected_head_sha,
                args.reason,
                payload,
            )
            if args.json:
                print(json.dumps(result, indent=2, sort_keys=True))
            else:
                emit_retirement_text(result)
            return 0
        deadline = time.monotonic() + args.timeout
        while True:
            payload = load_payload(args.input, args.repo, args.pr)
            summary = summarize(args.repo, args.pr, payload)
            state = (
                trigger_state(args.repo, args.pr, payload, record) if record else None
            )
            if not args.wait or state is None or state.terminal:
                break
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                if args.trigger_record and record is not None and state is not None:
                    persisted = persist_timeout_if_current(
                        args.trigger_record, record, state
                    )
                else:
                    persisted = True
                state.state = "timed_out" if persisted else "superseded"
                state.terminal = True
                state.manual_adjudication_required = True
                state.reason = (
                    "bounded wait expired before a terminal CodeRabbit response"
                    if persisted
                    else "durable trigger record changed while waiting; timeout was not persisted"
                )
                break
            time.sleep(min(args.poll_interval, remaining))
    except (
        KeyError,
        OSError,
        RuntimeError,
        TypeError,
        ValueError,
        json.JSONDecodeError,
    ) as ex:
        if args.retire_trigger is not None and args.json:
            print(
                json.dumps(
                    {
                        "operation": "retire_trigger",
                        "status": "refused",
                        "error": str(ex),
                    },
                    sort_keys=True,
                )
            )
            return 1
        print(f"error={ex}", file=sys.stderr)
        return 1

    if args.json:
        output = summary.__dict__.copy()
        if state is not None:
            output["trigger_state"] = state.__dict__
        print(json.dumps(output, indent=2, sort_keys=True))
    else:
        emit_text(summary)
        if state is not None:
            emit_trigger_text(state)

    if state is not None:
        if state.state == "completed":
            return 0
        if state.state in {"awaiting_response", "active"}:
            return 2
        return 1
    return 0 if summary.ok else 1


if __name__ == "__main__":
    sys.exit(main())
