#!/usr/bin/env python3
"""Validate complete-product capability allocation and implementation tracking."""

from __future__ import annotations

import re
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
TAXONOMY = ROOT / "design/architecture/product-capability-taxonomy.md"
TRACKING_DIR = ROOT / "design/project-management/implementation-tracking"
ALLOCATION = TRACKING_DIR / "capability-allocation.md"
TRACKERS = {
    "automation-and-scheduler-runtime.md",
    "game-authoring-publishing-and-activation.md",
    "game-session-runtime-and-tick-coordination.md",
    "gameplay-rules-entities-and-effects.md",
    "platform-operations-and-delivery.md",
    "player-access-and-session.md",
    "player-experience-commands-and-communication.md",
    "realm-routing-and-playable-state.md",
    "shared-runtime-contracts-and-persistence.md",
    "world-runtime-and-movement.md",
}
IMPLEMENTATION_STATES = {
    "implemented",
    "partial",
    "not-implemented",
    "design-unresolved",
    "not-applicable",
}
VERIFICATION_STATES = {
    "proven",
    "audited",
    "unverified",
    "drift-found",
    "not-applicable",
}
CAPABILITY_RE = re.compile(r"[A-Z]{2}-\d+\.\d+")
MARKDOWN_LINK_RE = re.compile(r"\[[^]]+\]\(([^)]+)\)")


def fail(message: str) -> None:
    raise SystemExit(message)


def table_cells(line: str) -> list[str]:
    return [cell.strip() for cell in line.strip().strip("|").split("|")]


def state(cell: str, allowed: set[str], context: str) -> str:
    match = re.match(r"^`([^`]+)`(?:\s|:|<br>|$)", cell)
    if not match or match.group(1) not in allowed:
        fail(f"{context}: invalid state cell {cell!r}; expected one of {sorted(allowed)}")
    return match.group(1)


def linked_tracker_names(cell: str) -> set[str]:
    return {
        Path(target.split("#", 1)[0]).name
        for target in MARKDOWN_LINK_RE.findall(cell)
        if target.split("#", 1)[0].endswith(".md")
        and Path(target.split("#", 1)[0]).name in TRACKERS
    }


def validate_local_links(path: Path, text: str) -> None:
    for target in MARKDOWN_LINK_RE.findall(text):
        target = target.split("#", 1)[0]
        if not target or "://" in target or target.startswith("mailto:"):
            continue
        resolved = (path.parent / target).resolve()
        if not resolved.exists():
            fail(f"{path.relative_to(ROOT)}: broken local link {target}")


def main() -> None:
    taxonomy_text = TAXONOMY.read_text(encoding="utf-8")
    leaves = re.findall(r"^- `([A-Z]{2}-\d+\.\d+)` ", taxonomy_text, re.MULTILINE)
    if len(leaves) != len(set(leaves)):
        fail("product capability taxonomy contains duplicate leaf IDs")

    allocation_text = ALLOCATION.read_text(encoding="utf-8")
    allocations: dict[str, tuple[str, set[str]]] = {}
    for line in allocation_text.splitlines():
        if not re.match(r"^\| `[A-Z]{2}-\d+\.\d+` \|", line):
            continue
        cells = table_cells(line)
        if len(cells) != 4:
            fail(f"{ALLOCATION.relative_to(ROOT)}: malformed allocation row: {line}")
        capability = CAPABILITY_RE.fullmatch(cells[0].strip("`"))
        if not capability:
            fail(f"{ALLOCATION.relative_to(ROOT)}: malformed capability cell {cells[0]!r}")
        capability_id = capability.group()
        primary = linked_tracker_names(cells[1])
        if len(primary) != 1:
            fail(f"{ALLOCATION.relative_to(ROOT)}: {capability_id} must name one primary tracker")
        if capability_id in allocations:
            fail(f"{ALLOCATION.relative_to(ROOT)}: duplicate allocation for {capability_id}")
        allocations[capability_id] = (next(iter(primary)), linked_tracker_names(cells[2]))

    leaf_set = set(leaves)
    if set(allocations) != leaf_set:
        fail(
            "capability allocation mismatch: "
            f"missing={sorted(leaf_set - set(allocations))}, extra={sorted(set(allocations) - leaf_set)}"
        )
    if {primary for primary, _ in allocations.values()} != TRACKERS:
        fail("capability allocation must use every declared domain tracker as a primary owner")
    validate_local_links(ALLOCATION, allocation_text)

    tracked: dict[str, str] = {}
    for tracker_name in sorted(TRACKERS):
        path = TRACKING_DIR / tracker_name
        text = path.read_text(encoding="utf-8")
        if "## Capability Status" not in text:
            fail(f"{path.relative_to(ROOT)}: missing Capability Status section")
        section = text.split("## Capability Status", 1)[1].split("\n## ", 1)[0]
        row_count = 0
        for line in section.splitlines():
            match = re.match(r"^\| `?([A-Z]{2}-\d+\.\d+)`?(?:\s+[^|]+)? \|", line)
            if not match:
                continue
            cells = table_cells(line)
            if len(cells) != 8:
                fail(f"{path.relative_to(ROOT)}: malformed capability row for {match.group(1)}")
            capability_id = match.group(1)
            implementation = state(cells[1], IMPLEMENTATION_STATES, f"{tracker_name}:{capability_id}")
            verification = state(cells[2], VERIFICATION_STATES, f"{tracker_name}:{capability_id}")
            if any(not cell for cell in cells[3:]):
                fail(f"{tracker_name}:{capability_id}: design, anchors, handoffs, and gap cells are required")
            for label, cell in zip(("design", "implementation", "proof"), cells[3:6], strict=True):
                if not MARKDOWN_LINK_RE.search(cell):
                    fail(f"{tracker_name}:{capability_id}: {label} evidence must include a repository link")
            if (implementation == "not-applicable") != (verification == "not-applicable"):
                fail(f"{tracker_name}:{capability_id}: not-applicable states must be paired")
            if capability_id in tracked:
                fail(f"capability {capability_id} appears in both {tracked[capability_id]} and {tracker_name}")
            if capability_id not in allocations:
                fail(f"{tracker_name}: unknown capability {capability_id}")
            expected_primary, _ = allocations[capability_id]
            if expected_primary != tracker_name:
                fail(f"{tracker_name}:{capability_id}: allocation primary is {expected_primary}")
            referenced_ids = set(CAPABILITY_RE.findall(cells[6]))
            unknown_ids = referenced_ids - leaf_set
            if unknown_ids:
                fail(f"{tracker_name}:{capability_id}: unknown capability handoffs {sorted(unknown_ids)}")
            tracked[capability_id] = tracker_name
            row_count += 1
        if row_count == 0:
            fail(f"{path.relative_to(ROOT)}: Capability Status has no rows")
        validate_local_links(path, section)

    if set(tracked) != leaf_set:
        fail(
            "tracker capability mismatch: "
            f"missing={sorted(leaf_set - set(tracked))}, extra={sorted(set(tracked) - leaf_set)}"
        )
    print(f"implementation capability tracking passed: {len(leaves)} leaves across {len(TRACKERS)} trackers")


if __name__ == "__main__":
    main()
