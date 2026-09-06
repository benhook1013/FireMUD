#!/usr/bin/env python3
"""Evaluate whether a PR is eligible for preview lifecycle actions.

The labels input is deliberately required. Trusted reconciliation must never
interpret missing or malformed GitHub label metadata as an unpaused PR.
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


def parse_labels(labels_json: str) -> tuple[bool, bool]:
    """Return (metadata_valid, is_paused) for the GitHub labels array."""

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
    return True, "preview:paused" in names


def evaluate(
    operation: str,
    state: str,
    base_ref: str,
    author: str,
    labels_json: str,
) -> tuple[bool, str]:
    labels_valid, paused = parse_labels(labels_json)
    if not labels_valid:
        return False, "malformed-label-metadata"
    if paused and operation in {"deploy", "retain"}:
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
    parser.add_argument("--operation", required=True, choices=("deploy", "destroy", "retain"))
    parser.add_argument("--state", required=True)
    parser.add_argument("--base-ref", required=True)
    parser.add_argument("--author", required=True)
    parser.add_argument("--labels-json", required=True)
    args = parser.parse_args()

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
