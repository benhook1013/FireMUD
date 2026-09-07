#!/usr/bin/env python3
"""Evaluate whether a PR is eligible for preview lifecycle actions.

The labels input is required so trusted reconciliation cannot interpret
missing or malformed metadata as an unpaused PR.
"""

from __future__ import annotations

import argparse
import json

DEPENDENCY_BOT_AUTHORS = {
    "app/dependabot",
    "dependabot[bot]",
    "app/renovate",
    "renovate[bot]",
}
SUPPORTED_BASE_REFS = {"main", "develop"}


def parse_labels(labels_json: str) -> tuple[bool, bool, bool]:
    """Return (metadata_valid, is_priority, is_paused) for GitHub labels."""

    try:
        labels = json.loads(labels_json)
    except json.JSONDecodeError:
        return False, False, False
    if not isinstance(labels, list):
        return False, False, False
    names: list[str] = []
    for label in labels:
        if not isinstance(label, dict) or not isinstance(label.get("name"), str):
            return False, False, False
        names.append(label["name"])
    return True, "preview:priority" in names, "preview:paused" in names


def evaluate(
    operation: str,
    state: str,
    base_ref: str,
    author: str,
    labels_json: str,
) -> tuple[bool, str]:
    labels_valid, _, paused = parse_labels(labels_json)
    if operation in {"deploy", "retain"}:
        if not labels_valid:
            return False, "malformed-label-metadata"
        if paused:
            return False, "preview-paused"
    if author in DEPENDENCY_BOT_AUTHORS:
        return False, "dependency-bot"
    if base_ref not in SUPPORTED_BASE_REFS:
        return False, "unsupported-base-branch"
    if operation in {"deploy", "retain"} and state != "open":
        return False, "pr-not-open"
    return True, "eligible"


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--inspect-labels", action="store_true")
    parser.add_argument("--operation", choices=("deploy", "destroy", "retain"))
    parser.add_argument("--state")
    parser.add_argument("--base-ref")
    parser.add_argument("--author")
    parser.add_argument("--labels-json", required=True)
    args = parser.parse_args()

    if args.inspect_labels:
        labels_valid, priority, paused = parse_labels(args.labels_json)
        print(f"labels_valid={'true' if labels_valid else 'false'}")
        print(f"priority={'true' if priority else 'false'}")
        print(f"paused={'true' if paused else 'false'}")
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

    eligible, reason = evaluate(
        args.operation,
        args.state,
        args.base_ref,
        args.author,
        args.labels_json,
    )
    print(f"eligible={'true' if eligible else 'false'}")
    print(f"reason={reason}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
