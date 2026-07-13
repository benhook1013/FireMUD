#!/usr/bin/env python3
"""Fail-closed CodeRabbit PR review gate.

This script does not trust the top-level CodeRabbit status badge. It verifies:
1. unresolved non-outdated review threads are zero
2. unresolved outdated review threads are zero
3. an explicit CodeRabbit review command was posted after the latest PR commit
4. CodeRabbit published a substantive review summary after that command
"""

from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any


GH_TIMEOUT_SECONDS = 30

REVIEW_COMMANDS = {
    "@coderabbitai review",
    "@coderabbitai full review",
}

# A command acknowledgement can say "Review finished." while explicitly stating that no commits
# were reviewed. The walkthrough is emitted only by CodeRabbit's actual review summary.
SUBSTANTIVE_REVIEW_MARKER = "<!-- walkthrough_start -->"
REVIEW_LIMIT_MARKER = "<!-- This is an auto-generated comment: rate limited by coderabbit.ai -->"
NOOP_REVIEW_MARKER = "does not re-review already reviewed commits"
ACTIONABLE_COMMENTS_MARKER = "**Actionable comments posted:"
OUTSIDE_DIFF_MARKER = "Outside diff range comments"
DUPLICATE_COMMENTS_MARKER = "Duplicate comments"


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
    latest_coderabbit_review_finished_at: str | None
    explicit_review_after_latest_commit: bool
    review_finished_after_latest_request: bool
    substantive_review_after_latest_commit: bool
    latest_review_request_rate_limited: bool
    latest_review_request_noop: bool
    retrigger_review_allowed: bool
    requires_coderabbit_self_resolution: bool
    must_resolve_outdated_threads: bool
    outside_diff_actionable_comments: int
    duplicate_actionable_comments: int
    latest_actionable_comment_url: str | None
    ok: bool
    reasons: list[str]


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
    parser.add_argument("--json", action="store_true", help="Emit machine-readable JSON")
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
              author { login }
              body
              url
              createdAt
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
          author { login }
          body
          createdAt
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
              author { login }
              body
              url
              createdAt
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
          author { login }
          body
          createdAt
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

    payload = run_gh_query(
        base_query,
        {"owner": owner, "repo": name, "number": pr_number},
    )
    pr = payload["data"]["repository"]["pullRequest"]
    review_threads = list(pr["reviewThreads"]["nodes"])
    comments = list(pr["comments"]["nodes"])

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
        review_threads_connection = review_threads_payload["data"]["repository"]["pullRequest"][
            "reviewThreads"
        ]
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
        comments_connection = comments_payload["data"]["repository"]["pullRequest"]["comments"]
        comments.extend(comments_connection["nodes"])
        comments_page = comments_connection.get("pageInfo", {})

    pr["reviewThreads"] = {"nodes": review_threads}
    pr["comments"] = {"nodes": comments}
    return payload


def load_payload(input_path: str | None, repo: str, pr_number: int) -> dict[str, Any]:
    if input_path:
        return json.loads(Path(input_path).read_text(encoding="utf-8"))
    return run_gh_graphql(repo, pr_number)


def parse_timestamp(value: str | None) -> datetime | None:
    if not value:
        return None
    return datetime.fromisoformat(value.replace("Z", "+00:00")).astimezone(timezone.utc)


def normalize_command(body: str) -> str:
    return " ".join(body.strip().split()).lower()


def extract_section_count(body: str, marker: str) -> int:
    match = re.search(rf"{re.escape(marker)} \((\d+)\)", body)
    return int(match.group(1)) if match else 0


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
    latest_coderabbit_review_finished_at: str | None = None
    latest_coderabbit_review_finished_dt: datetime | None = None
    latest_review_outcome_dt: datetime | None = None
    latest_review_outcome: str | None = None
    latest_actionable_comment_dt: datetime | None = None
    latest_actionable_comment_url: str | None = None
    outside_diff_actionable_comments = 0
    duplicate_actionable_comments = 0

    for comment in pr["comments"]["nodes"]:
        author = (comment.get("author") or {}).get("login", "")
        body = comment.get("body", "")
        created_at = comment.get("createdAt")
        created_at_dt = parse_timestamp(created_at)
        if author != "coderabbitai" and normalize_command(body) in REVIEW_COMMANDS:
            if (
                latest_explicit_review_request_dt is None
                or created_at_dt > latest_explicit_review_request_dt
            ):
                latest_explicit_review_request_dt = created_at_dt
                latest_explicit_review_request_at = created_at
        if author == "coderabbitai" and SUBSTANTIVE_REVIEW_MARKER in body:
            if (
                latest_coderabbit_review_finished_dt is None
                or created_at_dt > latest_coderabbit_review_finished_dt
            ):
                latest_coderabbit_review_finished_dt = created_at_dt
                latest_coderabbit_review_finished_at = created_at

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
        elif REVIEW_LIMIT_MARKER in body:
            outcome = "rate_limited"
        elif (
            latest_explicit_review_request_dt is not None
            and created_at_dt >= latest_explicit_review_request_dt
            and NOOP_REVIEW_MARKER in body
        ):
            outcome = "noop"

        if outcome is not None and (
            latest_review_outcome_dt is None or created_at_dt >= latest_review_outcome_dt
        ):
            latest_review_outcome_dt = created_at_dt
            latest_review_outcome = outcome

    latest_review_request_rate_limited = latest_review_outcome == "rate_limited"
    latest_review_request_noop = latest_review_outcome == "noop"
    for comment in pr["comments"]["nodes"]:
        author = (comment.get("author") or {}).get("login", "")
        body = comment.get("body", "")
        created_at = comment.get("createdAt")
        created_at_dt = parse_timestamp(created_at)
        if author != "coderabbitai" or ACTIONABLE_COMMENTS_MARKER not in body:
            continue
        if latest_explicit_review_request_dt is not None and (
            created_at_dt is None or created_at_dt < latest_explicit_review_request_dt
        ):
            continue
        if latest_actionable_comment_dt is None or created_at_dt > latest_actionable_comment_dt:
            latest_actionable_comment_dt = created_at_dt
            latest_actionable_comment_url = comment.get("url")
            outside_diff_actionable_comments = extract_section_count(body, OUTSIDE_DIFF_MARKER)
            duplicate_actionable_comments = extract_section_count(body, DUPLICATE_COMMENTS_MARKER)

    unresolved_total = unresolved_non_outdated + unresolved_outdated
    latest_review_request_still_running = (
        explicit_review_after_latest_commit
        and not review_finished_after_latest_request
        and not latest_review_request_rate_limited
        and not latest_review_request_noop
    )
    retrigger_review_allowed = (
        unresolved_total == 0
        and outside_diff_actionable_comments == 0
        and duplicate_actionable_comments == 0
        and not latest_review_request_still_running
        and not latest_review_request_rate_limited
        and not latest_review_request_noop
    )
    requires_coderabbit_self_resolution = (
        unresolved_total > 0
        and not latest_review_request_still_running
        and not latest_review_request_rate_limited
        and not latest_review_request_noop
    )
    must_resolve_outdated_threads = unresolved_outdated > 0

    reasons: list[str] = []
    if unresolved_non_outdated:
        reasons.append(
            f"{unresolved_non_outdated} unresolved non-outdated CodeRabbit thread(s) remain"
        )
    if unresolved_outdated:
        reasons.append(
            f"{unresolved_outdated} unresolved outdated CodeRabbit thread(s) remain; "
            "verify their fixes in HEAD and rerun CodeRabbit so it self-resolves them"
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
        reasons.append("no substantive CodeRabbit review summary found after the latest PR commit")
    if latest_review_request_rate_limited:
        reasons.append("latest CodeRabbit review attempt after the PR commit was rate limited; do not retrigger yet")
    if latest_review_request_noop:
        reasons.append(
            "latest explicit CodeRabbit review request was acknowledged without reviewing commits"
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
        latest_coderabbit_review_finished_at=latest_coderabbit_review_finished_at,
        explicit_review_after_latest_commit=explicit_review_after_latest_commit,
        review_finished_after_latest_request=review_finished_after_latest_request,
        substantive_review_after_latest_commit=substantive_review_after_latest_commit,
        latest_review_request_rate_limited=latest_review_request_rate_limited,
        latest_review_request_noop=latest_review_request_noop,
        retrigger_review_allowed=retrigger_review_allowed,
        requires_coderabbit_self_resolution=requires_coderabbit_self_resolution,
        must_resolve_outdated_threads=must_resolve_outdated_threads,
        outside_diff_actionable_comments=outside_diff_actionable_comments,
        duplicate_actionable_comments=duplicate_actionable_comments,
        latest_actionable_comment_url=latest_actionable_comment_url,
        ok=not reasons,
        reasons=reasons,
    )


def emit_text(summary: ReviewSummary) -> None:
    print(f"repo={summary.repo}")
    print(f"pr_number={summary.pr_number}")
    print(f"head_sha={summary.head_sha}")
    print(f"latest_commit_at={summary.latest_commit_at}")
    print(f"unresolved_non_outdated={summary.unresolved_non_outdated}")
    print(f"unresolved_outdated={summary.unresolved_outdated}")
    print(f"unresolved_total={summary.unresolved_total}")
    print(
        "latest_explicit_review_request_at="
        f"{summary.latest_explicit_review_request_at or 'none'}"
    )
    print(
        "latest_coderabbit_review_finished_at="
        f"{summary.latest_coderabbit_review_finished_at or 'none'}"
    )
    print(
        "explicit_review_after_latest_commit="
        f"{str(summary.explicit_review_after_latest_commit).lower()}"
    )
    print(
        "review_finished_after_latest_request="
        f"{str(summary.review_finished_after_latest_request).lower()}"
    )
    print(
        "substantive_review_after_latest_commit="
        f"{str(summary.substantive_review_after_latest_commit).lower()}"
    )
    print(
        "latest_review_request_rate_limited="
        f"{str(summary.latest_review_request_rate_limited).lower()}"
    )
    print(
        "latest_review_request_noop="
        f"{str(summary.latest_review_request_noop).lower()}"
    )
    print(
        "retrigger_review_allowed="
        f"{str(summary.retrigger_review_allowed).lower()}"
    )
    print(
        "requires_coderabbit_self_resolution="
        f"{str(summary.requires_coderabbit_self_resolution).lower()}"
    )
    print(
        "must_resolve_outdated_threads="
        f"{str(summary.must_resolve_outdated_threads).lower()}"
    )
    print(
        "outside_diff_actionable_comments="
        f"{summary.outside_diff_actionable_comments}"
    )
    print(
        "duplicate_actionable_comments="
        f"{summary.duplicate_actionable_comments}"
    )
    print(
        "latest_actionable_comment_url="
        f"{summary.latest_actionable_comment_url or 'none'}"
    )
    print(f"ok={str(summary.ok).lower()}")
    if summary.unresolved_outdated:
        print(
            "warning=UNRESOLVED OUTDATED CODERABBIT THREADS BLOCK MERGE. NEVER "
            "RESOLVE THEM MANUALLY; VERIFY THEIR FIXES IN HEAD, THEN RERUN "
            "@coderabbitai review SO CODERABBIT SELF-RESOLVES THEM"
        )
    if summary.outside_diff_actionable_comments or summary.duplicate_actionable_comments:
        print(
            "warning=TOP-LEVEL CODERABBIT ACTIONABLE COMMENTS CAN EXIST OUTSIDE "
            "INLINE REVIEW THREADS; VERIFY THE LATEST CODERABBIT SUMMARY COMMENT "
            "BEFORE CALLING THE PR REVIEW-CLEAN"
        )
    if summary.unresolved_non_outdated or summary.unresolved_outdated:
        print(
            "warning=UNRESOLVED CODERABBIT THREADS BLOCK MERGE. NEVER RESOLVE "
            "THREADS MANUALLY; AFTER VERIFYING EVERY FINDING IS FIXED IN HEAD, "
            "RERUN @coderabbitai review SO CODERABBIT SELF-RESOLVES THEM"
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
    try:
        payload = load_payload(args.input, args.repo, args.pr)
        summary = summarize(args.repo, args.pr, payload)
    except (KeyError, RuntimeError, ValueError, json.JSONDecodeError) as ex:
        print(f"error={ex}", file=sys.stderr)
        return 1

    if args.json:
        print(json.dumps(summary.__dict__, indent=2, sort_keys=True))
    else:
        emit_text(summary)

    return 0 if summary.ok else 1


if __name__ == "__main__":
    sys.exit(main())
