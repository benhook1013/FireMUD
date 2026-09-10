#!/usr/bin/env python3
"""Extract the canonical review checkpoint comments from a pull request."""

from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
from dataclasses import dataclass
from typing import Any

CHECKPOINT_HEADING = re.compile(
    r"^\*\*(?P<correction>Correction — )?(?P<type>Hosted|CLI): "
    r"(?P<raw_found>\d+) found / (?P<accepted>\d+) accepted\*\*"
    r"(?P<suffix>.*)$"
)
CHECKPOINT_SUFFIX = re.compile(
    r"^(?: · (?P<sha>`?[0-9a-fA-F]{7,40}`?))?"
    r"(?: · (?P<files>\d+) files)?$"
)
CHECKPOINT_CANDIDATE = re.compile(r"^\*\*(?:Correction — )?(?:Hosted|CLI):")
REPO_NAME = re.compile(r"^[^/\s]+/[^/\s]+$")


@dataclass(frozen=True)
class Checkpoint:
    created_at: str
    type: str
    raw_found: int
    accepted: int
    reviewed_sha: str | None
    file_count: int | None
    correction: bool
    updated_at: str | None

    def as_json(self) -> dict[str, Any]:
        result: dict[str, Any] = {
            "created_at": self.created_at,
            "type": self.type,
            "raw_found": self.raw_found,
            "accepted": self.accepted,
            "reviewed_sha": self.reviewed_sha,
            "file_count": self.file_count,
            "correction": self.correction,
        }
        if self.updated_at is not None:
            result["updated_at"] = self.updated_at
        return result


class CheckpointError(ValueError):
    """Raised when API data or command arguments cannot be interpreted safely."""


def parse_limit(value: str) -> int:
    try:
        limit = int(value)
    except ValueError as exc:
        raise argparse.ArgumentTypeError("limit must be a non-negative integer") from exc
    if limit < 0:
        raise argparse.ArgumentTypeError("limit must be a non-negative integer")
    return limit


def parse_repo(repo: str) -> tuple[str, str]:
    if not REPO_NAME.fullmatch(repo):
        raise CheckpointError("repo must be in OWNER/REPO form")
    owner, name = repo.split("/", 1)
    return owner, name


def parse_checkpoint_comments(
    comments: list[dict[str, Any]],
) -> tuple[list[Checkpoint], int]:
    checkpoints: list[Checkpoint] = []
    unparsed_candidates = 0
    for position, comment in enumerate(comments, 1):
        if not isinstance(comment, dict):
            raise CheckpointError(f"comment {position} is not an object")
        body = comment.get("body")
        created_at = comment.get("created_at")
        updated_at = comment.get("updated_at")
        if not isinstance(body, str) or not isinstance(created_at, str):
            raise CheckpointError(f"comment {position} is missing body or created_at")
        if updated_at is not None and not isinstance(updated_at, str):
            raise CheckpointError(f"comment {position} has an invalid updated_at")
        first_line = next(
            (line for line in body.splitlines() if line.strip() and not line[0].isspace()),
            None,
        )
        if first_line is None:
            continue
        match = CHECKPOINT_HEADING.fullmatch(first_line)
        if match is None:
            if CHECKPOINT_CANDIDATE.match(first_line):
                unparsed_candidates += 1
            continue
        # One observed correction was posted with literal "\\n" separators in its body.
        suffix_text = match.group("suffix").split(r"\n", 1)[0]
        suffix = CHECKPOINT_SUFFIX.fullmatch(suffix_text)
        if suffix is None:
            unparsed_candidates += 1
            continue
        sha = suffix.group("sha")
        if sha is not None:
            sha = sha.strip("`")
        file_count = suffix.group("files")
        checkpoints.append(
            Checkpoint(
                created_at=created_at,
                type=match.group("type"),
                raw_found=int(match.group("raw_found")),
                accepted=int(match.group("accepted")),
                reviewed_sha=sha,
                file_count=int(file_count) if file_count is not None else None,
                correction=match.group("correction") is not None,
                updated_at=(updated_at if updated_at is not None and updated_at != created_at else None),
            )
        )
    return checkpoints, unparsed_candidates


def _load_comments(payload: Any) -> list[dict[str, Any]]:
    if not isinstance(payload, list) or any(not isinstance(page, list) for page in payload):
        raise CheckpointError("gh api returned an unexpected paginated response")
    comments = [comment for page in payload for comment in page]
    if any(not isinstance(comment, dict) for comment in comments):
        raise CheckpointError("gh api returned a non-object comment")
    return comments


def fetch_comments(repo: str, pr_number: int) -> list[dict[str, Any]]:
    owner, name = parse_repo(repo)
    endpoint = f"repos/{owner}/{name}/issues/{pr_number}/comments?per_page=100"
    try:
        completed = subprocess.run(
            ["gh", "api", "--method", "GET", "--paginate", "--slurp", endpoint],
            check=True,
            capture_output=True,
            text=True,
        )
    except FileNotFoundError as exc:
        raise RuntimeError("gh CLI is required") from exc
    except subprocess.CalledProcessError as exc:
        detail = exc.stderr.strip() if exc.stderr else "gh api failed"
        raise RuntimeError(detail) from exc
    try:
        payload = json.loads(completed.stdout)
    except json.JSONDecodeError as exc:
        raise RuntimeError("gh api returned invalid JSON") from exc
    return _load_comments(payload)


def collect_report(
    comments: list[dict[str, Any]],
    limit: int,
) -> dict[str, Any]:
    checkpoints, unparsed_candidates = parse_checkpoint_comments(comments)
    checkpoints.sort(key=lambda checkpoint: checkpoint.created_at)
    returned = checkpoints if limit == 0 else checkpoints[-limit:]
    return {
        "total_comments": len(comments),
        "matched_checkpoints": len(checkpoints),
        "returned_checkpoints": len(returned),
        "omitted_checkpoints": len(checkpoints) - len(returned),
        "unparsed_candidates": unparsed_candidates,
        "checkpoints": [checkpoint.as_json() for checkpoint in returned],
    }


def format_marker(value: str | int | None) -> str:
    return "-" if value is None else str(value)


def emit_text(report: dict[str, Any]) -> None:
    print(
        "matched={matched_checkpoints} returned={returned_checkpoints} "
        "omitted={omitted_checkpoints} unparsed={unparsed_candidates}".format(**report)
    )
    print("posted_at_utc type found/accepted sha files")
    for checkpoint in report["checkpoints"]:
        checkpoint_type = checkpoint["type"]
        if checkpoint["correction"]:
            checkpoint_type = f"Correction {checkpoint_type}"
        print(
            f"{checkpoint['created_at']} {checkpoint_type} "
            f"{checkpoint['raw_found']}/{checkpoint['accepted']} "
            f"{format_marker(checkpoint['reviewed_sha'])} "
            f"{format_marker(checkpoint['file_count'])}"
        )


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Extract posted Hosted and CLI review checkpoint comments.")
    parser.add_argument("--repo", required=True, help="GitHub repository in OWNER/REPO form")
    parser.add_argument("--pr", required=True, type=int, help="Pull request number")
    parser.add_argument(
        "--limit",
        type=parse_limit,
        default=20,
        help="Number of newest checkpoints to return; 0 returns all (default: 20)",
    )
    parser.add_argument("--json", action="store_true", help="Emit machine-readable JSON")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    if args.pr <= 0:
        print("error: --pr must be a positive integer", file=sys.stderr)
        return 2
    try:
        comments = fetch_comments(args.repo, args.pr)
        report = collect_report(comments, args.limit)
    except (CheckpointError, RuntimeError) as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 1
    if args.json:
        print(json.dumps(report, indent=2, sort_keys=True))
    else:
        emit_text(report)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
