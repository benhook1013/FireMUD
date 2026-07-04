#!/usr/bin/env python3
"""Fail-closed CodeRabbit PR review gate.

This script does not trust the top-level CodeRabbit status badge. It verifies:
1. unresolved non-outdated review threads are zero
2. unresolved outdated review threads are zero
3. an explicit CodeRabbit review command was posted after the latest PR commit
4. CodeRabbit posted a "Review finished." reply after that command
"""

from __future__ import annotations

import argparse
import json
import subprocess
import sys
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any


REVIEW_COMMANDS = {
    "@coderabbitai review",
    "@coderabbitai full review",
}

REVIEW_FINISHED_MARKER = "Review finished."


@dataclass
class ReviewSummary:
    repo: str
    pr_number: int
    head_sha: str
    latest_commit_at: str
    unresolved_non_outdated: int
    unresolved_outdated: int
    latest_explicit_review_request_at: str | None
    latest_coderabbit_review_finished_at: str | None
    explicit_review_after_latest_commit: bool
    review_finished_after_latest_request: bool
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


def run_gh_graphql(repo: str, pr_number: int) -> dict[str, Any]:
    owner, name = repo.split("/", 1)
    query = """
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
      }
      comments(last:100) {
        nodes {
          author { login }
          body
          createdAt
          url
        }
      }
    }
  }
}
""".strip()
    completed = subprocess.run(
        [
            "gh",
            "api",
            "graphql",
            "-f",
            f"query={query}",
            "-F",
            f"owner={owner}",
            "-F",
            f"repo={name}",
            "-F",
            f"number={pr_number}",
        ],
        check=True,
        capture_output=True,
        text=True,
    )
    return json.loads(completed.stdout)


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
        if author == "coderabbitai" and REVIEW_FINISHED_MARKER in body:
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

    reasons: list[str] = []
    if unresolved_non_outdated:
        reasons.append(
            f"{unresolved_non_outdated} unresolved non-outdated CodeRabbit thread(s) remain"
        )
    if unresolved_outdated:
        reasons.append(
            f"{unresolved_outdated} unresolved outdated CodeRabbit thread(s) remain"
        )
    if not explicit_review_after_latest_commit:
        reasons.append(
            "no explicit CodeRabbit review request found after the latest PR commit"
        )
    if not review_finished_after_latest_request:
        reasons.append(
            "no completed CodeRabbit review found after the latest explicit review request"
        )

    return ReviewSummary(
        repo=repo,
        pr_number=pr_number,
        head_sha=pr["headRefOid"],
        latest_commit_at=latest_commit_at,
        unresolved_non_outdated=unresolved_non_outdated,
        unresolved_outdated=unresolved_outdated,
        latest_explicit_review_request_at=latest_explicit_review_request_at,
        latest_coderabbit_review_finished_at=latest_coderabbit_review_finished_at,
        explicit_review_after_latest_commit=explicit_review_after_latest_commit,
        review_finished_after_latest_request=review_finished_after_latest_request,
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
    print(f"ok={str(summary.ok).lower()}")
    if summary.reasons:
        for reason in summary.reasons:
            print(f"reason={reason}")


def main() -> int:
    args = parse_args()
    payload = load_payload(args.input, args.repo, args.pr)
    summary = summarize(args.repo, args.pr, payload)

    if args.json:
        print(json.dumps(summary.__dict__, indent=2, sort_keys=True))
    else:
        emit_text(summary)

    return 0 if summary.ok else 1


if __name__ == "__main__":
    sys.exit(main())
