#!/usr/bin/env python3
"""Wait silently for a read-only external transition, then emit one JSON result.

This local helper is intentionally kept under ignored ``tmp/`` while its behavior
is proved.  It performs no GitHub or repository writes.
"""

from __future__ import annotations

import argparse
import json
import subprocess
import sys
import time
from datetime import datetime
from pathlib import Path
from typing import Any


DEFAULT_CHECKER = (
    Path(__file__).resolve().parents[1]
    / "dev-tools"
    / "validation"
    / "check-coderabbit-review.py"
)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Wait for a hosted CodeRabbit transition or a local timer."
    )
    parser.add_argument(
        "--wait",
        required=True,
        choices=("hosted-review", "timer"),
        help="Transition type to await.",
    )
    parser.add_argument("--repo", help="GitHub repository in owner/name form.")
    parser.add_argument("--pr", type=int, help="Pull request number.")
    parser.add_argument("--expected-head", help="Expected complete PR head SHA.")
    parser.add_argument(
        "--until",
        help="ISO-8601 local or offset timestamp for --wait timer.",
    )
    parser.add_argument(
        "--interval-seconds",
        type=int,
        default=60,
        help="Low-frequency poll interval (default: 60).",
    )
    parser.add_argument(
        "--timeout-seconds",
        type=int,
        default=3600,
        help="Maximum hosted-review wait (default: 3600).",
    )
    parser.add_argument(
        "--inspect-reply-after-seconds",
        type=int,
        default=600,
        help="Inspect the direct CodeRabbit command reply after this delay.",
    )
    parser.add_argument(
        "--checker",
        type=Path,
        default=DEFAULT_CHECKER,
        help="Path to check-coderabbit-review.py.",
    )
    args = parser.parse_args()
    if args.interval_seconds < 30:
        parser.error("--interval-seconds must be at least 30")
    if args.wait == "hosted-review":
        if not args.repo or args.pr is None or not args.expected_head:
            parser.error("hosted-review requires --repo, --pr, and --expected-head")
        if len(args.expected_head) != 40:
            parser.error("--expected-head must be a complete 40-character SHA")
        if args.timeout_seconds <= 0:
            parser.error("--timeout-seconds must be positive")
    elif not args.until:
        parser.error("timer requires --until")
    return args


def emit(event: str, **details: Any) -> int:
    payload = {
        "event": event,
        "observedAt": datetime.now().astimezone().isoformat(),
        **details,
    }
    print(json.dumps(payload, sort_keys=True), flush=True)
    return 0


def run_checker(args: argparse.Namespace) -> dict[str, Any]:
    completed = subprocess.run(
        [
            sys.executable,
            str(args.checker),
            "--repo",
            args.repo,
            "--pr",
            str(args.pr),
            "--json",
        ],
        check=False,
        capture_output=True,
        text=True,
        timeout=45,
    )
    try:
        return json.loads(completed.stdout)
    except json.JSONDecodeError as exc:
        message = completed.stderr.strip() or completed.stdout.strip()
        raise RuntimeError(f"CodeRabbit checker did not return JSON: {message}") from exc


def latest_direct_reply(repo: str, pr: int) -> dict[str, str] | None:
    completed = subprocess.run(
        [
            "gh",
            "api",
            "--paginate",
            "--slurp",
            f"repos/{repo}/issues/{pr}/comments?per_page=100",
        ],
        check=True,
        capture_output=True,
        text=True,
        timeout=45,
    )
    pages = json.loads(completed.stdout)
    comments = [comment for page in pages for comment in page]
    requests = [
        comment
        for comment in comments
        if comment.get("body", "").strip().lower()
        in ("@coderabbitai review", "@coderabbitai full review")
    ]
    if not requests:
        return None
    request = max(requests, key=lambda comment: comment.get("created_at", ""))
    replies = [
        comment
        for comment in comments
        if comment.get("created_at", "") > request.get("created_at", "")
        and comment.get("user", {}).get("login", "").lower() == "coderabbitai"
    ]
    if not replies:
        return None
    reply = min(replies, key=lambda comment: comment.get("created_at", ""))
    return {
        "body": reply.get("body", "")[:2000],
        "createdAt": reply.get("created_at", ""),
        "url": reply.get("html_url", ""),
    }


def classify_direct_reply(reply: dict[str, str] | None) -> str | None:
    if reply is None:
        return None
    body = reply["body"].lower()
    if "action failed" in body or "action not completed" in body:
        return "request_failed"
    if "rate limited" in body or "more reviews will be available" in body:
        return "rate_limited"
    if "review skipped" in body or "skip review by coderabbit.ai" in body:
        return "review_skipped"
    if "does not re-review already reviewed commits" in body:
        return "review_noop"
    return None


def wait_for_hosted_review(args: argparse.Namespace) -> int:
    started = time.monotonic()
    while True:
        summary = run_checker(args)
        if summary["head_sha"] != args.expected_head:
            return emit(
                "head_changed",
                expectedHead=args.expected_head,
                actualHead=summary["head_sha"],
            )
        if (
            summary["latest_explicit_review_request_at"] is None
            or not summary["explicit_review_after_latest_commit"]
        ):
            return emit("no_explicit_request", head=args.expected_head)
        if summary["latest_review_request_rate_limited"]:
            return emit(
                "rate_limited",
                head=args.expected_head,
                until=summary["review_rate_limit_until"],
            )
        if summary["latest_review_request_noop"]:
            return emit("review_noop", head=args.expected_head)
        if summary["review_finished_after_latest_request"]:
            return emit(
                "review_completed",
                head=args.expected_head,
                gateOk=summary["ok"],
                unresolvedCurrent=summary["unresolved_non_outdated"],
                unresolvedOutdated=summary["unresolved_outdated"],
                outsideDiff=summary["outside_diff_actionable_comments"],
                duplicateComments=summary["duplicate_actionable_comments"],
                finishedAt=summary["latest_coderabbit_review_finished_at"],
            )

        # The checker primarily derives terminal outcomes from CodeRabbit's
        # review-summary history.  The direct command reply is authoritative
        # for request-level failures, however, and can arrive before that
        # history is represented in the summary.  Inspect it on every poll
        # while this explicit request is genuinely pending so a rate-limit (or
        # other terminal command response) is not mistaken for an ordinary
        # pending review until the six-minute delayed inspection.
        reply = latest_direct_reply(args.repo, args.pr)
        classification = classify_direct_reply(reply)
        if classification is not None:
            return emit(classification, head=args.expected_head, reply=reply)

        elapsed = time.monotonic() - started
        if elapsed >= args.timeout_seconds:
            return emit("timeout", head=args.expected_head, reply=reply)
        time.sleep(min(args.interval_seconds, args.timeout_seconds - elapsed))


def wait_for_timer(args: argparse.Namespace) -> int:
    target = datetime.fromisoformat(args.until)
    if target.tzinfo is None:
        target = target.replace(tzinfo=datetime.now().astimezone().tzinfo)
    while True:
        remaining = (target - datetime.now().astimezone()).total_seconds()
        if remaining <= 0:
            return emit("timer_elapsed", target=target.isoformat())
        time.sleep(min(args.interval_seconds, remaining))


def main() -> int:
    args = parse_args()
    try:
        if args.wait == "hosted-review":
            return wait_for_hosted_review(args)
        return wait_for_timer(args)
    except (KeyError, OSError, RuntimeError, subprocess.SubprocessError) as exc:
        print(
            json.dumps(
                {
                    "event": "wait_error",
                    "observedAt": datetime.now().astimezone().isoformat(),
                    "error": str(exc),
                },
                sort_keys=True,
            ),
            flush=True,
        )
        return 1


if __name__ == "__main__":
    sys.exit(main())
