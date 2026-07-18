#!/usr/bin/env python3
"""Validate complete-product capability allocation and implementation tracking."""

from __future__ import annotations

import re
from collections import Counter
from pathlib import Path
from urllib.parse import unquote


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
MARKDOWN_SUFFIXES = {".md", ".markdown", ".mdx"}
AUDIT_CONTEXT_NAMES = {"README.md", "package.json"}
CANONICAL_PROOF_TOOLS = {
    "dev-tools/backups/verify-backups.sh",
    "dev-tools/deploy/preflight.py",
    "dev-tools/deploy/validate-kustomize-overlays.sh",
    "dev-tools/hosted/shared/hosted-login-look-smoke.sh",
    "dev-tools/observability/check-metrics-cardinality.py",
    "dev-tools/observability/run-player-experience-smoke.py",
    "dev-tools/observability/validate-observability-contract.py",
    "dev-tools/observability/validate-player-experience-smoke-evidence.py",
    "dev-tools/tests/architecture-doc-contracts.sh",
    "dev-tools/tests/gradle-proof-tooling-contract.sh",
    "dev-tools/tests/player-experience-smoke-evidence-contract.sh",
    "dev-tools/tests/player-experience-smoke-runner-contract.sh",
    "dev-tools/tests/preflight-contract.sh",
    "dev-tools/tests/preview-eligibility-contract.sh",
    "dev-tools/tests/reset-service-db-contract.sh",
    "dev-tools/tests/secret-compliance-contract.sh",
    "dev-tools/tests/smoke-image-env-contract.sh",
    "dev-tools/tests/smoke-transport-contract.sh",
    "dev-tools/tests/static-analysis-summary-contract.sh",
    "dev-tools/validation/check-flyway-versions.py",
    "dev-tools/validation/check-grpc-public-methods.py",
    "dev-tools/validation/check-grpc-transport-config.py",
    "dev-tools/validation/check-grpc-transport-config.sh",
    "dev-tools/validation/check-implementation-capability-tracking.py",
    "dev-tools/validation/test_check_proto_time_fields.py",
    "dev-tools/validation/validate-helm.sh",
    "dev-tools/verify-fresh-bootstrap.sh",
    "dev-tools/verify-restart-state.sh",
    "dev-tools/verify-smoke-images.sh",
}


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
    names: set[str] = set()
    canonical_targets = {f"./{tracker}" for tracker in TRACKERS}
    for target in MARKDOWN_LINK_RE.findall(cell):
        if target not in canonical_targets:
            fail(
                "implementation-tracker link must use canonical relative target "
                f"from declared trackers, got {target!r}"
            )
        names.add(target.removeprefix("./"))
    return names


def canonical_tracker_names(cell: str, trackers: set[str], context: str) -> set[str]:
    names: set[str] = set()
    canonical_targets = {f"./{tracker}" for tracker in trackers}
    for target in MARKDOWN_LINK_RE.findall(cell):
        if target not in canonical_targets:
            fail(
                f"{context}: implementation-tracker link must use canonical relative target "
                f"from declared trackers, got {target!r}"
            )
        names.add(target.removeprefix("./"))
    return names


def relative_path(path: Path, root: Path) -> str:
    try:
        return path.resolve().relative_to(root.resolve()).as_posix()
    except ValueError:
        return str(path)


def is_external_target(target: str) -> bool:
    return bool(re.match(r"^[A-Za-z][A-Za-z0-9+.-]*:", target) or target.startswith("//"))


def markdown_anchor_ids(path: Path) -> set[str]:
    headings: set[str] = set()
    occurrences: Counter[str] = Counter()
    for line in path.read_text(encoding="utf-8").splitlines():
        match = re.match(r"^#{1,6}\s+(.+?)\s*#*\s*$", line)
        if not match:
            continue
        heading = re.sub(r"`([^`]*)`", r"\1", match.group(1).lower())
        slug = re.sub(r"[^\w\s-]", "", heading)
        slug = re.sub(r"[\s-]+", "-", slug).strip("-")
        ordinal = occurrences[slug]
        occurrences[slug] += 1
        headings.add(slug if ordinal == 0 else f"{slug}-{ordinal}")
    return headings


def resolve_evidence_target(root: Path, owner: Path, target: str, context: str) -> tuple[Path, str, str]:
    target = target.strip()
    if target.startswith("<") and target.endswith(">"):
        target = target[1:-1]
    if is_external_target(target):
        fail(f"{context}: evidence anchor must be repository-local, got {target!r}")
    bare, separator, fragment = target.partition("#")
    if not bare:
        resolved = owner.resolve()
    else:
        resolved = (owner.parent / unquote(bare)).resolve()
    try:
        relative = resolved.relative_to(root.resolve()).as_posix()
    except ValueError:
        fail(f"{context}: evidence anchor escapes repository: {target}")
    if not resolved.is_file():
        fail(f"{context}: missing evidence anchor target {target}")
    if separator and fragment and resolved.suffix.lower() in MARKDOWN_SUFFIXES:
        if unquote(fragment) not in markdown_anchor_ids(resolved):
            fail(f"{context}: missing Markdown anchor {target}")
    return resolved, relative, unquote(fragment)


def is_test_target(relative: str) -> bool:
    return (
        relative.startswith("dev-tools/tests/")
        or "/src/test/" in f"/{relative}/"
        or "/src/testFixtures/" in f"/{relative}/"
    )


def is_canonical_proof_tool(relative: str) -> bool:
    if relative.startswith(".github/workflows/"):
        return True
    return relative in CANONICAL_PROOF_TOOLS or relative == "k8s/velero/verify-backups-cronjob.yaml"


def is_docs_only_target(relative: str) -> bool:
    path = Path(relative)
    return (
        relative.startswith(("design/architecture/", "docs/"))
        or path.suffix.lower() in MARKDOWN_SUFFIXES
        or path.name.lower().startswith("readme")
    )


def is_audit_context(verification: str, cell: str, relative: str) -> bool:
    if verification != "audited":
        return False
    if Path(relative).name not in AUDIT_CONTEXT_NAMES or relative.startswith("design/"):
        return False
    lowered = cell.lower()
    return (
        re.search(r"\bno\b[^.]{0,120}\b(?:test|proof|executable)\b", lowered) is not None
        or re.search(r"\brather than\b[^.]{0,100}\bproof\b", lowered) is not None
    )


def validate_evidence_anchor(
    root: Path,
    owner: Path,
    target: str,
    category: str,
    verification: str,
    cell: str,
    context: str,
) -> None:
    _, relative, _ = resolve_evidence_target(root, owner, target, context)
    if category == "design":
        if not relative.startswith("design/architecture/"):
            fail(f"{context}: design evidence must target design/architecture, got {relative}")
    elif category == "production":
        if is_docs_only_target(relative) or is_test_target(relative):
            # This contract script is a declared implementation anchor for the
            # verification capability itself, not a service test or fixture.
            if relative != "dev-tools/tests/architecture-doc-contracts.sh":
                fail(f"{context}: production evidence must not target test-only/docs-only surfaces, got {relative}")
    elif category == "proof":
        if not (is_test_target(relative) or is_canonical_proof_tool(relative) or is_audit_context(verification, cell, relative)):
            fail(f"{context}: proof evidence must target tests or canonical validation/smoke tooling, got {relative}")
    else:
        fail(f"{context}: unknown evidence category {category}")


def validate_evidence_links(
    root: Path,
    path: Path,
    capability_id: str,
    verification: str,
    cells: list[str],
) -> None:
    for category, cell in zip(("design", "production", "proof"), cells, strict=True):
        for target in MARKDOWN_LINK_RE.findall(cell):
            validate_evidence_anchor(
                root,
                path,
                target,
                category,
                verification,
                cell,
                f"{relative_path(path, root)}:{capability_id}:{category}",
            )


def validate_local_links(path: Path, text: str, root: Path = ROOT) -> None:
    for target in MARKDOWN_LINK_RE.findall(text):
        target = target.split("#", 1)[0]
        if not target or is_external_target(target):
            continue
        resolved = (path.parent / target).resolve()
        if not resolved.exists():
            fail(f"{relative_path(path, root)}: broken local link {target}")


def markdown_tables(text: str) -> list[tuple[list[str], list[list[str]]]]:
    tables: list[tuple[list[str], list[list[str]]]] = []
    lines = text.splitlines()
    index = 0
    while index < len(lines):
        if not lines[index].strip().startswith("|"):
            index += 1
            continue
        block: list[str] = []
        while index < len(lines) and lines[index].strip().startswith("|"):
            block.append(lines[index])
            index += 1
        if len(block) < 2:
            continue
        separator = table_cells(block[1])
        if not separator or not all(re.fullmatch(r":?-{3,}:?", cell) for cell in separator):
            continue
        headers = table_cells(block[0])
        rows = [table_cells(line) for line in block[2:]]
        if any(len(row) != len(headers) for row in rows):
            fail(f"malformed Markdown table with headers {headers}")
        tables.append((headers, rows))
    return tables


def summary_key(cell: str) -> str:
    return cell.strip().strip("*").strip()


def summary_integer(cell: str, context: str) -> int:
    match = re.fullmatch(r"\*{0,2}(\d+)\*{0,2}", cell.strip())
    if not match:
        fail(f"{context}: expected integer summary value, got {cell!r}")
    return int(match.group(1))


def validate_coverage_summary(
    path: Path,
    text: str,
    leaves: set[str],
    allocations: dict[str, tuple[str, set[str]]],
    trackers: set[str],
    root: Path = ROOT,
) -> None:
    tables = markdown_tables(text)
    measure_table = next((table for table in tables if {"Measure", "Result"} <= set(table[0])), None)
    tracker_table = next((table for table in tables if {"Primary tracker", "Primary leaves"} <= set(table[0])), None)
    if measure_table is None or tracker_table is None:
        fail(f"{relative_path(path, root)}: Coverage Summary tables are missing or malformed")

    measure_headers, measure_rows = measure_table
    measure_index = measure_headers.index("Result")
    measures: dict[str, list[str]] = {}
    for row in measure_rows:
        name = summary_key(row[0])
        if name in measures:
            fail(f"{relative_path(path, root)}: duplicate Coverage Summary measure {name}")
        measures[name] = row
    expected_measures = {
        "Taxonomy leaf capabilities": len(leaves),
        "Unique allocated capability IDs": len(allocations),
        "Missing or unassigned leaves": len(leaves - set(allocations)),
        "Duplicate primary allocations": 0,
    }
    if set(measures) != set(expected_measures) | {"Primary tracker files represented"}:
        fail(f"{relative_path(path, root)}: Coverage Summary measure rows drifted")
    for name, expected in expected_measures.items():
        if summary_integer(measures[name][measure_index], f"{relative_path(path, root)}:{name}") != expected:
            fail(f"{relative_path(path, root)}:{name}: Coverage Summary drift")
    tracker_count = len({primary for primary, _ in allocations.values()})
    match = re.fullmatch(r"(\d+)\s+of\s+(\d+)", measures["Primary tracker files represented"][measure_index].strip())
    if not match or (int(match.group(1)), int(match.group(2))) != (tracker_count, len(trackers)):
        fail(f"{relative_path(path, root)}: primary-tracker coverage summary drift")

    tracker_headers, tracker_rows = tracker_table
    leaves_index = tracker_headers.index("Primary leaves")
    declared: dict[str, int] = {}
    for row in tracker_rows:
        name = summary_key(row[0])
        if name == "Total":
            continue
        targets = MARKDOWN_LINK_RE.findall(row[0])
        if len(targets) != 1:
            fail(f"{relative_path(path, root)}: primary tracker summary row must link one tracker")
        tracker_names = canonical_tracker_names(
            row[0],
            trackers,
            f"{relative_path(path, root)}: primary tracker summary row",
        )
        tracker = next(iter(tracker_names))
        if tracker in declared:
            fail(f"{relative_path(path, root)}: invalid or duplicate primary tracker summary row {tracker}")
        declared[tracker] = summary_integer(row[leaves_index], f"{relative_path(path, root)}:{tracker}")
    if set(declared) != trackers:
        fail(f"{relative_path(path, root)}: primary tracker summary rows drifted")
    actual_counts = Counter(primary for primary, _ in allocations.values())
    expected_counts = {tracker: actual_counts.get(tracker, 0) for tracker in trackers}
    if declared != expected_counts:
        fail(f"{relative_path(path, root)}: per-tracker allocation totals drifted")
    total_rows = [row for row in tracker_rows if summary_key(row[0]) == "Total"]
    if len(total_rows) != 1 or summary_integer(total_rows[0][leaves_index], f"{relative_path(path, root)}:Total") != len(allocations):
        fail(f"{relative_path(path, root)}: primary tracker total drifted")


def validate_status_row_handoffs(
    tracker_name: str,
    capability_id: str,
    allocation_handoffs: set[str],
    allocations: dict[str, tuple[str, set[str]]],
    cell: str,
) -> None:
    referenced_ids = set(CAPABILITY_RE.findall(cell))
    unknown_ids = referenced_ids - set(allocations)
    if unknown_ids:
        fail(f"{tracker_name}:{capability_id}: unknown capability handoffs {sorted(unknown_ids)}")

    # Status rows may show a smaller operational subset than the exhaustive
    # allocation ledger and may link the owner of a named related capability.
    allowed = allocation_handoffs | {tracker_name} | {allocations[current][0] for current in referenced_ids}
    actual = linked_tracker_names(cell)
    unexpected = sorted(actual - allowed)
    if not unexpected:
        return
    fail(
        f"{tracker_name}:{capability_id}: unexpected status-row secondary handoffs "
        f"not in allocation ledger: {unexpected}"
    )


def parse_allocations(root: Path, allocation_path: Path, text: str) -> dict[str, tuple[str, set[str]]]:
    allocations: dict[str, tuple[str, set[str]]] = {}
    for line in text.splitlines():
        if not line.strip().startswith("|"):
            continue
        cells = table_cells(line)
        if not cells:
            continue
        capability = CAPABILITY_RE.fullmatch(cells[0].strip("`"))
        if not capability:
            continue
        if len(cells) != 4:
            fail(f"{relative_path(allocation_path, root)}: malformed allocation row: {line}")
        capability_id = capability.group()
        primary = canonical_tracker_names(
            cells[1],
            TRACKERS,
            f"{relative_path(allocation_path, root)}:{capability_id}: primary tracker",
        )
        if len(primary) != 1:
            fail(f"{relative_path(allocation_path, root)}: {capability_id} must name one primary tracker")
        if capability_id in allocations:
            fail(f"{relative_path(allocation_path, root)}: duplicate allocation for {capability_id}")
        secondary = canonical_tracker_names(
            cells[2],
            TRACKERS,
            f"{relative_path(allocation_path, root)}:{capability_id}: secondary tracker",
        )
        primary_tracker = next(iter(primary))
        if primary_tracker in secondary:
            fail(
                f"{relative_path(allocation_path, root)}:{capability_id}: "
                "primary tracker must not be repeated as a secondary tracker"
            )
        allocations[capability_id] = (primary_tracker, secondary)
    if not allocations:
        fail(f"{relative_path(allocation_path, root)}: no capability allocation rows found")
    return allocations


def main() -> None:
    root = ROOT
    taxonomy = root / TAXONOMY.relative_to(ROOT)
    tracking_dir = root / TRACKING_DIR.relative_to(ROOT)
    allocation_path = root / ALLOCATION.relative_to(ROOT)
    taxonomy_text = taxonomy.read_text(encoding="utf-8")
    leaves = re.findall(r"^- `([A-Z]{2}-\d+\.\d+)` ", taxonomy_text, re.MULTILINE)
    if len(leaves) != len(set(leaves)):
        fail("product capability taxonomy contains duplicate leaf IDs")

    allocation_text = allocation_path.read_text(encoding="utf-8")
    allocations = parse_allocations(root, allocation_path, allocation_text)

    leaf_set = set(leaves)
    if set(allocations) != leaf_set:
        fail(
            "capability allocation mismatch: "
            f"missing={sorted(leaf_set - set(allocations))}, extra={sorted(set(allocations) - leaf_set)}"
        )
    if {primary for primary, _ in allocations.values()} != TRACKERS:
        fail("capability allocation must use every declared domain tracker as a primary owner")
    validate_local_links(allocation_path, allocation_text, root)
    validate_coverage_summary(allocation_path, allocation_text, leaf_set, allocations, TRACKERS, root)

    tracked: dict[str, str] = {}
    for tracker_name in sorted(TRACKERS):
        path = tracking_dir / tracker_name
        text = path.read_text(encoding="utf-8")
        if "## Capability Status" not in text:
            fail(f"{relative_path(path, root)}: missing Capability Status section")
        section = text.split("## Capability Status", 1)[1].split("\n## ", 1)[0]
        row_count = 0
        for line in section.splitlines():
            match = re.match(r"^\| `?([A-Z]{2}-\d+\.\d+)`?(?:\s+[^|]+)? \|", line)
            if not match:
                continue
            cells = table_cells(line)
            if len(cells) != 8:
                fail(f"{relative_path(path, root)}: malformed capability row for {match.group(1)}")
            capability_id = match.group(1)
            implementation = state(cells[1], IMPLEMENTATION_STATES, f"{tracker_name}:{capability_id}")
            verification = state(cells[2], VERIFICATION_STATES, f"{tracker_name}:{capability_id}")
            if any(not cell for cell in cells[3:]):
                fail(f"{tracker_name}:{capability_id}: design, anchors, handoffs, and gap cells are required")
            for label, cell in zip(("design", "implementation", "proof"), cells[3:6], strict=True):
                if not MARKDOWN_LINK_RE.search(cell):
                    fail(f"{tracker_name}:{capability_id}: {label} evidence must include a repository link")
            validate_evidence_links(root, path, capability_id, verification, cells[3:6])
            if (implementation == "not-applicable") != (verification == "not-applicable"):
                fail(f"{tracker_name}:{capability_id}: not-applicable states must be paired")
            if capability_id in tracked:
                fail(f"capability {capability_id} appears in both {tracked[capability_id]} and {tracker_name}")
            if capability_id not in allocations:
                fail(f"{tracker_name}: unknown capability {capability_id}")
            expected_primary, expected_handoffs = allocations[capability_id]
            if expected_primary != tracker_name:
                fail(f"{tracker_name}:{capability_id}: allocation primary is {expected_primary}")
            validate_status_row_handoffs(
                tracker_name, capability_id, expected_handoffs, allocations, cells[6]
            )
            tracked[capability_id] = tracker_name
            row_count += 1
        if row_count == 0:
            fail(f"{relative_path(path, root)}: Capability Status has no rows")
        validate_local_links(path, section, root)

    if set(tracked) != leaf_set:
        fail(
            "tracker capability mismatch: "
            f"missing={sorted(leaf_set - set(tracked))}, extra={sorted(set(tracked) - leaf_set)}"
        )
    print(f"implementation capability tracking passed: {len(leaves)} leaves across {len(TRACKERS)} trackers")


if __name__ == "__main__":
    main()
