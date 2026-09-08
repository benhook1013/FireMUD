#!/usr/bin/env python3
"""Render and validate the local whole-corpus review tracking ledger."""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path
from typing import Any


DEFAULT_LEDGER = Path(__file__).with_name("corpus-review-status.json")
WHOLE_SECTION = "whole-section-unrestricted"
NARROW = "narrow-focused"
LEGACY = "legacy-unclassified"
REVIEW_MODES = {WHOLE_SECTION, NARROW, LEGACY}


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("ledger", nargs="?", type=Path, default=DEFAULT_LEDGER)
    parser.add_argument("--section", help="Show one section only, for example 5C.")
    parser.add_argument("--json", action="store_true", help="Emit derived status as JSON.")
    return parser.parse_args()


def fail(message: str) -> None:
    raise ValueError(message)


def review_mode(review: dict[str, Any]) -> str:
    # Older ledger entries predate the explicit mode field and must not silently
    # count as whole-section evidence. Preserve the one historical label whose
    # meaning is explicit; all other untagged entries remain unclassified.
    if "reviewMode" in review:
        return review["reviewMode"]
    if review.get("domain") == "broad-followup":
        return WHOLE_SECTION
    if "domain" in review:
        return NARROW
    return LEGACY


def mode_label(review: dict[str, Any]) -> str:
    return {WHOLE_SECTION: "U", NARROW: "N"}.get(review_mode(review), "?")


def validate_review(section_id: str, review: dict[str, Any], current_revision: int) -> None:
    status = review.get("status")
    if status not in {"complete", "running", "rate-limited", "failed"}:
        fail(f"{section_id}: unsupported review status {status!r}")
    revision = review.get("revision")
    if not isinstance(revision, int) or not 0 <= revision <= current_revision:
        fail(f"{section_id}: invalid review revision {revision!r}")
    mode = review_mode(review)
    if mode not in REVIEW_MODES:
        fail(f"{section_id}: unsupported reviewMode {mode!r}")
    if status == "complete":
        raw = review.get("raw")
        useful = review.get("useful")
        if not isinstance(raw, int) or not isinstance(useful, int):
            fail(f"{section_id}: completed review requires integer raw/useful counts")
        if raw < 0 or useful < 0 or useful > raw:
            fail(f"{section_id}: invalid raw/useful counts {raw}/{useful}")


def current_zero_streak(section: dict[str, Any]) -> int:
    current_revision = section["currentRevision"]
    completed = [
        review
        for review in section["reviews"]
        if review["source"] == "luna"
        and review["status"] == "complete"
        and review["revision"] == current_revision
        and review_mode(review) == WHOLE_SECTION
    ]
    streak = 0
    for review in reversed(completed):
        if review["useful"] != 0:
            break
        streak += 1
    return streak


def derive_section(section: dict[str, Any]) -> dict[str, Any]:
    section_id = section["id"]
    current_revision = section["currentRevision"]
    floor = section["plannedLunaFloor"]
    confirmations = section["requiredZeroConfirmations"]
    revisions = section["revisionEvents"]
    if [event["revision"] for event in revisions] != list(range(current_revision + 1)):
        fail(f"{section_id}: revisionEvents must cover 0..{current_revision} exactly")
    for review in section["reviews"]:
        validate_review(section_id, review, current_revision)

    completed = [
        review
        for review in section["reviews"]
        if review["source"] == "luna" and review["status"] == "complete"
    ]
    whole_section = [review for review in completed if review_mode(review) == WHOLE_SECTION]
    narrow = [review for review in completed if review_mode(review) == NARROW]
    legacy = [review for review in completed if review_mode(review) == LEGACY]
    inflight = [
        review
        for review in section["reviews"]
        if review["status"] == "running"
    ]
    zero_streak = current_zero_streak(section)
    terminal = len(whole_section) >= floor and zero_streak >= confirmations
    trend = [
        f"r{review['revision']}:{mode_label(review)}"
        f"{review['raw']}/{review['useful']}"
        for review in completed
    ]
    trend.extend(
        f"r{review['revision']}:L{review['round']} running" for review in inflight
    )
    return {
        "id": section_id,
        "title": section["title"],
        "state": section["state"],
        "currentRevision": current_revision,
        "completedLunaReviews": len(completed),
        "completedWholeSectionReviews": len(whole_section),
        "completedNarrowReviews": len(narrow),
        "completedLegacyUnclassifiedReviews": len(legacy),
        "plannedLunaFloor": floor,
        "currentRevisionZeroStreak": zero_streak,
        "currentRevisionWholeSectionZeroStreak": zero_streak,
        "requiredZeroConfirmations": confirmations,
        "terminalByCounts": terminal,
        "trend": trend,
        "latestRevisionCause": revisions[-1]["cause"],
    }


def validate_pr_review(review: dict[str, Any]) -> None:
    status = review.get("status")
    if status == "complete":
        raw = review.get("raw")
        useful = review.get("useful")
        if not isinstance(raw, int) or not isinstance(useful, int):
            fail("completed PR review requires integer raw/useful counts")
        if raw < 0 or useful < 0 or useful > raw:
            fail(f"invalid PR review raw/useful counts {raw}/{useful}")


def render_table(ledger: dict[str, Any], sections: list[dict[str, Any]]) -> None:
    pr = ledger["pr"]
    print(
        f"PR #{pr['number']} | {pr['family']} | published {pr['publishedHead'][:10]} "
        f"| local {pr['localHead'][:10]}"
    )
    slices = ledger.get("slices", {})
    if slices:
        for name in ("front", "back"):
            split = slices.get(name)
            if split:
                state = split.get("state", "unknown")
                print(
                    f"{name.title()} slice: PR #{split['pr']} | {split['branch']} | "
                    f"{split['fileCount']} files | {state} | sections {','.join(split['sections'])}"
                )
    print("Counts are raw/useful. rN marks the content revision; U/N/? mark review mode.")
    print(
        "A revision bump means accepted fixes or a material external seam changed the "
        "section; only current-revision whole-section-unrestricted zeroes count toward terminal taper."
    )
    print()
    print("Section  Rev  Luna floor  Whole  Narrow  Zeroes  Terminal  Review trend")
    print("-------  ---  ----------  -----  ------  ------  --------  ------------")
    for section in sections:
        terminal = "yes" if section["terminalByCounts"] else "no"
        trend = " -> ".join(section["trend"]) or "none"
        print(
            f"{section['id']:<7}  r{section['currentRevision']:<2}  "
            f"{section['completedWholeSectionReviews']}/{section['plannedLunaFloor']:<5}  "
            f"{section['completedNarrowReviews']:<6}  "
            f"{section['currentRevisionZeroStreak']}/{section['requiredZeroConfirmations']:<4}  "
            f"{terminal:<8}  {trend}"
        )
    print()
    for section in sections:
        print(f"{section['id']} r{section['currentRevision']}: {section['latestRevisionCause']}")

    pr_reviews = ledger.get("prReviews", [])
    if pr_reviews:
        print()
        print("Shared PR review stream:")
        parts = []
        for review in pr_reviews:
            label = f"{review['source']}#{review['round']}"
            if review["status"] == "complete":
                label += f" r/u={review['raw']}/{review['useful']}"
            else:
                label += f" {review['status']}"
            parts.append(label)
        print(" -> ".join(parts))


def main() -> int:
    args = parse_args()
    try:
        ledger = json.loads(args.ledger.read_text(encoding="utf-8"))
        if ledger.get("schemaVersion") != 1:
            fail("unsupported schemaVersion")
        slices = ledger.get("slices", {})
        for name in ("front", "back"):
            split = slices.get(name)
            if split is None:
                continue
            required = {"pr", "branch", "sections", "fileCount", "state"}
            missing = required - split.keys()
            if missing:
                fail(f"{name} slice missing fields: {sorted(missing)}")
            if not isinstance(split["fileCount"], int) or split["fileCount"] < 0:
                fail(f"{name} slice has invalid fileCount")
            if not isinstance(split["sections"], list) or not split["sections"]:
                fail(f"{name} slice must name sections")
        sections = [derive_section(section) for section in ledger["sections"]]
        for review in ledger.get("prReviews", []):
            validate_pr_review(review)
        if args.section:
            sections = [section for section in sections if section["id"] == args.section]
            if not sections:
                fail(f"unknown section {args.section!r}")
    except (OSError, json.JSONDecodeError, KeyError, TypeError, ValueError) as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 1

    if args.json:
        print(json.dumps({"pr": ledger["pr"], "sections": sections}, indent=2))
    else:
        render_table(ledger, sections)
    return 0


if __name__ == "__main__":
    sys.exit(main())
