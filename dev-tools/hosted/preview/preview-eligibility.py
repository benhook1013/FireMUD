#!/usr/bin/env python3
"""Evaluate whether a PR is eligible for preview lifecycle actions.

The labels input is required so trusted reconciliation cannot interpret
missing or malformed metadata as a preview-eligible PR.
"""

from __future__ import annotations

import argparse
import json
import re
import sys

DEPENDENCY_BOT_AUTHORS = {
    "app/dependabot",
    "dependabot[bot]",
    "app/renovate",
    "renovate[bot]",
}
SUPPORTED_BASE_REFS = {"main", "develop"}
GIT_COMMIT_SHA_RE = re.compile(r"^[0-9a-fA-F]{40}$")


def parse_labels(labels_json: str) -> tuple[bool, bool]:
    """Return (metadata_valid, is_priority) for GitHub labels."""

    try:
        labels = json.loads(labels_json)
    except json.JSONDecodeError:
        return False, False
    if not isinstance(labels, list):
        return False, False
    names: list[str] = []
    for label in labels:
        if not isinstance(label, dict) or not isinstance(label.get("name"), str):
            return False, False
        names.append(label["name"])
    return True, "preview:priority" in names


def evaluate(
    operation: str,
    state: str,
    base_ref: str,
    author: str,
    labels_json: str,
) -> tuple[bool, str, bool]:
    labels_valid, is_priority = parse_labels(labels_json)
    if operation in {"deploy", "retain"} and not labels_valid:
        return False, "malformed-label-metadata", is_priority
    if author in DEPENDENCY_BOT_AUTHORS:
        return False, "dependency-bot", is_priority
    if base_ref not in SUPPORTED_BASE_REFS:
        return False, "unsupported-base-branch", is_priority
    if operation in {"deploy", "retain"} and state != "open":
        return False, "pr-not-open", is_priority
    return True, "eligible", is_priority


def _nested_value(payload: dict[str, object], *keys: str) -> object | None:
    value: object = payload
    for key in keys:
        if not isinstance(value, dict):
            return None
        value = value.get(key)
    return value


def _display_value(value: object) -> str:
    if isinstance(value, str):
        return value
    if value is None:
        return "null"
    return json.dumps(value, separators=(",", ":"))


def revalidate_deploy(
    pull_request_json: str,
    expected_repository: str,
    expected_head_sha: str,
) -> str | None:
    """Return the fail-closed refusal reason for a current deploy target, if any."""

    try:
        pull_request = json.loads(pull_request_json)
    except json.JSONDecodeError:
        return "current pull request metadata is malformed"
    if not isinstance(pull_request, dict):
        return "current pull request metadata is malformed"

    state = _nested_value(pull_request, "state")
    head_sha = _nested_value(pull_request, "head", "sha")
    head_repository = _nested_value(pull_request, "head", "repo", "full_name")
    if state != "open":
        return f"pull request is not open (state={_display_value(state)})"
    if (
        not isinstance(expected_head_sha, str)
        or not GIT_COMMIT_SHA_RE.fullmatch(expected_head_sha)
    ):
        return "expected head SHA must be exactly 40 hexadecimal characters"
    if (
        not isinstance(head_sha, str)
        or not GIT_COMMIT_SHA_RE.fullmatch(head_sha)
        or head_sha.lower() != expected_head_sha.lower()
    ):
        return f"head is stale (expected={expected_head_sha}, current={_display_value(head_sha)})"
    if head_repository != expected_repository:
        return (
            "head repository is not trusted "
            f"(expected={expected_repository}, current={_display_value(head_repository)})"
        )

    labels = pull_request.get("labels")
    labels_json = json.dumps(labels, separators=(",", ":"))
    labels_valid, _ = parse_labels(labels_json)
    if not labels_valid:
        return "label metadata is malformed"

    base_ref = _nested_value(pull_request, "base", "ref")
    author = _nested_value(pull_request, "user", "login")
    if not isinstance(author, str) or not author:
        return "current pull request metadata is malformed (user.login must be a non-empty string)"
    eligible, reason, _ = evaluate(
        "deploy",
        _display_value(state),
        _display_value(base_ref),
        author,
        labels_json,
    )
    if not eligible:
        return f"target is not preview-eligible (reason={reason})"
    return None


def main() -> int:
    parser = argparse.ArgumentParser()
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--inspect-labels", action="store_true")
    mode.add_argument("--revalidate-deploy", action="store_true")
    parser.add_argument("--operation", choices=("deploy", "destroy", "retain"))
    parser.add_argument("--state")
    parser.add_argument("--base-ref")
    parser.add_argument("--author")
    parser.add_argument("--labels-json")
    parser.add_argument("--expected-repository")
    parser.add_argument("--expected-head-sha")
    args = parser.parse_args()

    if args.revalidate_deploy:
        missing = [
            name
            for name, value in (
                ("--expected-repository", args.expected_repository),
                ("--expected-head-sha", args.expected_head_sha),
            )
            if value is None
        ]
        if missing:
            parser.error(f"the following arguments are required: {', '.join(missing)}")
        refusal_reason = revalidate_deploy(
            sys.stdin.read(),
            args.expected_repository,
            args.expected_head_sha,
        )
        if refusal_reason is not None:
            print(refusal_reason)
            return 1
        return 0

    if args.labels_json is None:
        parser.error("the following arguments are required: --labels-json")

    if args.inspect_labels:
        labels_valid, priority = parse_labels(args.labels_json)
        print(f"labels_valid={'true' if labels_valid else 'false'}")
        print(f"priority={'true' if priority else 'false'}")
        return 0

    missing = [
        name
        for name, value in (
            ("--operation", args.operation),
            ("--state", args.state),
            ("--base-ref", args.base_ref),
            ("--author", args.author),
        )
        if value is None
    ]
    if missing:
        parser.error(f"the following arguments are required: {', '.join(missing)}")

    eligible, reason, is_priority = evaluate(
        args.operation,
        args.state,
        args.base_ref,
        args.author,
        args.labels_json,
    )
    print(f"eligible={'true' if eligible else 'false'}")
    print(f"reason={reason}")
    if args.operation == "deploy":
        print(f"priority={'true' if is_priority else 'false'}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
