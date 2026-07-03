#!/usr/bin/env python3
"""Evaluate whether a PR is eligible for preview deploy/retain/destroy actions."""

from __future__ import annotations

import argparse


DEPENDENCY_BOT_AUTHORS = {
    "app/dependabot",
    "dependabot[bot]",
    "app/renovate",
    "renovate[bot]",
}
SUPPORTED_BASE_REFS = {"main", "develop"}


def evaluate(operation: str, state: str, base_ref: str, author: str) -> tuple[bool, str]:
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
    args = parser.parse_args()

    eligible, reason = evaluate(args.operation, args.state, args.base_ref, args.author)
    print(f"eligible={'true' if eligible else 'false'}")
    print(f"reason={reason}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
