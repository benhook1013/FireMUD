#!/usr/bin/env python3
"""Validate the trusted source artifact for an explicit preview destroy."""

from __future__ import annotations

import argparse
import json
import re
import sys
import typing
from pathlib import Path

SHA_RE = re.compile(r"^[0-9a-f]{40}$")
PR_NUMBER_RE = re.compile(r"^[1-9][0-9]{0,50}$")
SOURCE_WORKFLOW = ".github/workflows/preview.yml"
EXPECTED_FIELDS = {
    "schemaVersion",
    "event",
    "action",
    "repository",
    "sourceWorkflow",
    "sourceRunId",
    "prNumber",
    "headSha",
}


def fail(message: str) -> typing.NoReturn:
    raise ValueError(message)


def validate(
    path: Path,
    repository: str,
    source_run_id: str,
) -> tuple[int, str]:
    try:
        document = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise ValueError("preview intent must be valid JSON") from exc
    if not isinstance(document, dict):
        fail("preview intent must be a JSON object")
    if set(document) != EXPECTED_FIELDS:
        fail("preview intent contains an unexpected or missing field")
    if document["schemaVersion"] != 1:
        fail("preview intent schemaVersion must be 1")
    if document["event"] != "repository_dispatch":
        fail("preview intent event must be repository_dispatch")
    if document["action"] != "destroy":
        fail("preview intent action must be destroy")
    if document["repository"] != repository:
        fail("preview intent repository does not match the trusted repository")
    if document["sourceWorkflow"] != SOURCE_WORKFLOW:
        fail("preview intent sourceWorkflow is not the preview workflow")
    if (
        not isinstance(document["sourceRunId"], int)
        or isinstance(document["sourceRunId"], bool)
        or document["sourceRunId"] <= 0
        or str(document["sourceRunId"]) != source_run_id
    ):
        fail("preview intent sourceRunId does not match the completed source run")
    pr_number = document["prNumber"]
    if (
        not isinstance(pr_number, int)
        or isinstance(pr_number, bool)
        or not PR_NUMBER_RE.fullmatch(str(pr_number))
    ):
        fail("preview intent prNumber must be a positive canonical integer")
    head_sha = document["headSha"]
    if not isinstance(head_sha, str) or not SHA_RE.fullmatch(head_sha):
        fail("preview intent headSha must be exactly 40 lowercase hexadecimal characters")
    return pr_number, head_sha


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("path", type=Path)
    parser.add_argument("repository")
    parser.add_argument("source_run_id")
    args = parser.parse_args()
    try:
        pr_number, head_sha = validate(args.path, args.repository, args.source_run_id)
    except ValueError as exc:
        print(f"preview intent rejected: {exc}", file=sys.stderr)
        return 1
    print(f"pr_number={pr_number}")
    print(f"head_sha={head_sha}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
