#!/usr/bin/env python3
"""Evaluate whether a PR is eligible for preview lifecycle actions.

The labels input is required so trusted reconciliation cannot interpret
missing or malformed metadata as a preview-eligible PR.
"""

from __future__ import annotations

import argparse
import base64
import binascii
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
MAX_PRIORITY_CANDIDATES = 1000


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


def evaluate_priority_candidates(rows_input: str, expected_repository: str) -> tuple[list[tuple[str, str]], str | None]:
    """Return ordered eligible priority PR identities from bounded TSV input."""

    rows = [row for row in rows_input.splitlines() if row]
    if len(rows) > MAX_PRIORITY_CANDIDATES:
        return [], f"candidate limit exceeded ({MAX_PRIORITY_CANDIDATES})"

    priority_candidates: list[tuple[str, str]] = []
    for row_index, row in enumerate(rows, start=1):
        fields = row.split("\t")
        if len(fields) != 7:
            return [], f"candidate row {row_index} has malformed field framing"
        (
            pr_number,
            head_sha,
            head_repository,
            author,
            base_ref,
            state,
            labels_base64,
        ) = fields

        # Fork rows are outside the trusted same-repository preview contract.
        # Skip them before decoding or validating their label payload.
        if head_repository != expected_repository:
            continue
        if not re.fullmatch(r"[1-9][0-9]*", pr_number) or not GIT_COMMIT_SHA_RE.fullmatch(head_sha):
            return [], f"candidate row {row_index} has malformed PR identity"
        try:
            labels_json = base64.b64decode(labels_base64, validate=True).decode("utf-8")
        except (binascii.Error, UnicodeDecodeError):
            return [], f"priority PR #{pr_number} has malformed label transport"

        eligible, reason, is_priority = evaluate("deploy", state, base_ref, author, labels_json)
        if reason == "malformed-label-metadata":
            return [], f"priority PR #{pr_number} has malformed label metadata"
        if eligible and is_priority:
            priority_candidates.append((pr_number, head_sha))

    return priority_candidates, None


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


def _revalidate_target(
    pull_request_json: str,
    expected_repository: str,
    expected_head_sha: str,
    expected_state: str,
) -> tuple[str | None, dict[str, object] | None]:
    """Validate immutable identity and state shared by deploy and cleanup targets."""

    try:
        pull_request = json.loads(pull_request_json)
    except json.JSONDecodeError:
        return "current pull request metadata is malformed", None
    if not isinstance(pull_request, dict):
        return "current pull request metadata is malformed", None

    state = _nested_value(pull_request, "state")
    head_sha = _nested_value(pull_request, "head", "sha")
    head_repository = _nested_value(pull_request, "head", "repo", "full_name")
    if state != expected_state:
        return (
            f"pull request is not {expected_state} (state={_display_value(state)})",
            None,
        )
    if not isinstance(expected_head_sha, str) or not GIT_COMMIT_SHA_RE.fullmatch(expected_head_sha):
        return "expected head SHA must be exactly 40 hexadecimal characters", None
    if (
        not isinstance(head_sha, str)
        or not GIT_COMMIT_SHA_RE.fullmatch(head_sha)
        or head_sha.lower() != expected_head_sha.lower()
    ):
        return (
            f"head is stale (expected={expected_head_sha}, current={_display_value(head_sha)})",
            None,
        )
    if head_repository != expected_repository:
        reason = (
            "head repository is not trusted "
            f"(expected={expected_repository}, current={_display_value(head_repository)})"
        )
        return reason, None
    return None, pull_request


def revalidate_deploy(
    pull_request_json: str,
    expected_repository: str,
    expected_head_sha: str,
) -> str | None:
    """Return the fail-closed refusal reason for a current deploy target, if any."""

    refusal_reason, pull_request = _revalidate_target(
        pull_request_json,
        expected_repository,
        expected_head_sha,
        "open",
    )
    if refusal_reason is not None or pull_request is None:
        return refusal_reason

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
        "open",
        _display_value(base_ref),
        author,
        labels_json,
    )
    if not eligible:
        return f"target is not preview-eligible (reason={reason})"
    return None


def revalidate_cleanup(
    pull_request_json: str,
    expected_repository: str,
    expected_head_sha: str,
) -> str | None:
    """Return the fail-closed refusal reason for a closed cleanup target, if any."""

    refusal_reason, _ = _revalidate_target(
        pull_request_json,
        expected_repository,
        expected_head_sha,
        "closed",
    )
    return refusal_reason


def main() -> int:
    parser = argparse.ArgumentParser()
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--inspect-labels", action="store_true")
    mode.add_argument("--batch-deploy-candidates", action="store_true")
    mode.add_argument("--revalidate-deploy", action="store_true")
    mode.add_argument("--revalidate-cleanup", action="store_true")
    parser.add_argument("--operation", choices=("deploy", "destroy", "retain"))
    parser.add_argument("--state")
    parser.add_argument("--base-ref")
    parser.add_argument("--author")
    parser.add_argument("--labels-json")
    parser.add_argument("--expected-repository")
    parser.add_argument("--expected-head-sha")
    args = parser.parse_args()

    if args.batch_deploy_candidates:
        if args.expected_repository is None:
            parser.error("the following arguments are required: --expected-repository")
        candidates, refusal_reason = evaluate_priority_candidates(sys.stdin.read(), args.expected_repository)
        if refusal_reason is not None:
            print(refusal_reason, file=sys.stderr)
            return 1
        for pr_number, head_sha in candidates:
            print(f"{pr_number}\t{head_sha}")
        return 0

    if args.revalidate_deploy or args.revalidate_cleanup:
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
        evaluator = revalidate_cleanup if args.revalidate_cleanup else revalidate_deploy
        refusal_reason = evaluator(sys.stdin.read(), args.expected_repository, args.expected_head_sha)
        if refusal_reason is not None:
            # Keep this on stdout because revalidate-preview-deploy.sh captures the reason.
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
