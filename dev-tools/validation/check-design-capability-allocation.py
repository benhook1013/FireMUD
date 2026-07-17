#!/usr/bin/env python3
"""Validate the architecture design-allocation ledgers against repository paths."""

from __future__ import annotations

import argparse
import re
from collections import Counter
from dataclasses import dataclass
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
ALIGNMENT_DIR = Path("design/project-management/design-alignment")
ARCHITECTURE_DIR = Path("design/architecture")
TAXONOMY = Path("design/architecture/product-capability-taxonomy.md")
TOP_ALLOCATION = ALIGNMENT_DIR / "design-capability-allocation.md"
SYSTEM_ALLOCATION = ALIGNMENT_DIR / "design-capability-allocation-system.md"
MICROSERVICE_ALLOCATION = ALIGNMENT_DIR / "design-capability-allocation-microservices.md"
MARKDOWN_LINK_RE = re.compile(r"\[[^]]+\]\(([^)]+)\)")
GROUP_ID_RE = re.compile(r"[A-Z]{2}-\d+")
SYSTEM_CLASSIFICATIONS = {"normative design", "runbook", "reference", "index"}
MICROSERVICE_STANDARD_CLASSIFICATIONS = {
    "README.md": "Service overview",
    "api-contracts.md": "API contract",
    "configuration.md": "Configuration contract",
    "operations.md": "Operations contract",
    "runtime-and-data.md": "Runtime/data contract",
}
MICROSERVICE_STANDARD_OVERRIDES = {
    "design/architecture/microservices/game-logic-service/configuration.md": (
        "AR-2",
        "Runtime-policy/configuration contract",
    ),
}
MICROSERVICE_SERVICE_PRIMARY = {
    "account-service": "AA-1",
    "automation-scripting-service": "AS-1",
    "entity-management-service": "GR-3",
    "game-design-service": "AR-1",
    "game-logic-service": "GR-4",
    "game-session-service": "AA-2",
    "logging-admin-service": "PO-1",
    "social-groups-service": "EA-2",
    "spring-cloud-gateway": "PO-2",
    "tcp-proxy-service": "PO-2",
    "world-management-service": "GR-2",
}
MICROSERVICE_APPENDIX_ALLOCATIONS = {
    "design/architecture/microservices/account-service/stripe-integration.md": ("AA-1", "Commerce design"),
    "design/architecture/microservices/account-service/subscription-management.md": ("AA-1", "Entitlement design"),
    "design/architecture/microservices/automation-scripting-service/sandbox-runtime-design.md": (
        "AS-1",
        "Sandbox/runtime design",
    ),
    "design/architecture/microservices/game-design-service/ability-action-tools.md": ("AR-1", "Authoring design"),
    "design/architecture/microservices/game-design-service/asset-storage.md": ("AR-1", "Release-asset design"),
    "design/architecture/microservices/game-design-service/feature-flags.md": ("AR-2", "Runtime-policy design"),
    "design/architecture/microservices/game-design-service/game-templates.md": ("AR-1", "Template/launch design"),
    "design/architecture/microservices/game-design-service/item-equipment-balancing.md": (
        "AR-1",
        "Authoring design",
    ),
    "design/architecture/microservices/game-design-service/modding-framework.md": ("AR-1", "Plugin/mod design"),
    "design/architecture/microservices/game-design-service/version-control.md": ("AR-1", "Version/publish design"),
    "design/architecture/microservices/game-design-service/web-visual-interface.md": (
        "EA-3",
        "First-party creator UX",
    ),
    "design/architecture/microservices/game-design-service/world-editing-tools.md": ("AR-1", "Authoring workflow"),
    "design/architecture/microservices/game-session-service/protocols.md": (
        "EA-1",
        "Gameplay protocol contract",
    ),
    "design/architecture/microservices/logging-admin-service/admin-ui.md": ("EA-3", "First-party operator UX"),
    "design/architecture/microservices/logging-admin-service/analytics-dashboards.md": (
        "PO-4",
        "Observability design",
    ),
    "design/architecture/microservices/logging-admin-service/moderation-policies.md": (
        "PO-1",
        "Moderation-policy design",
    ),
    "design/architecture/microservices/spring-cloud-gateway/client-behavior.md": (
        "PO-2",
        "Edge client-behavior contract",
    ),
    "design/architecture/microservices/tcp-proxy-service/protocols.md": ("PO-2", "Edge protocol contract"),
    "design/architecture/microservices/world-management-service/procedural-generation-control.md": (
        "AR-1",
        "Procedural-authoring design",
    ),
    "design/architecture/microservices/world-management-service/world-creation-workflow.md": (
        "AR-3",
        "Activation workflow",
    ),
}
MICROSERVICE_EXEMPT_ALLOCATIONS = {
    "design/architecture/microservices/service-documentation-structure.md": ("Exempt", "Governance guide"),
    "design/architecture/microservices/service-template.md": ("Exempt", "Template"),
}
TOP_ALLOCATION_ROWS = {
    "design/project-management/design-alignment/design-capability-allocation-microservices.md": (
        "All 76 files under `design/architecture/microservices/**`",
        "Per-source allocation",
    ),
    "design/architecture/decisions/README.md": ("Registry plus 11 ADRs", "Per-record allocation"),
    "design/project-management/design-alignment/design-capability-allocation-system.md": (
        "All 89 direct architecture, 6 infrastructure, and 1 generated source",
        "Per-source allocation",
    ),
}


@dataclass(frozen=True)
class LedgerRow:
    path: str
    primary: str
    classification: str


def fail(message: str) -> None:
    raise SystemExit(message)


def table_cells(line: str) -> list[str]:
    return [cell.strip() for cell in line.strip().strip("|").split("|")]


def is_table_separator(line: str) -> bool:
    cells = table_cells(line)
    return bool(cells) and all(re.fullmatch(r":?-{3,}:?", cell) for cell in cells)


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
        if len(block) < 2 or not is_table_separator(block[1]):
            continue
        headers = table_cells(block[0])
        rows = [table_cells(line) for line in block[2:]]
        if any(len(row) != len(headers) for row in rows):
            fail(f"malformed Markdown table with headers {headers}")
        tables.append((headers, rows))
    return tables


def section(text: str, heading: str) -> str:
    match = re.search(rf"^## {re.escape(heading)}\s*$", text, re.MULTILINE)
    if not match:
        fail(f"missing section {heading!r}")
    following = re.search(r"^## ", text[match.end() :], re.MULTILINE)
    end = match.end() + following.start() if following else len(text)
    return text[match.end() : end]


def table_in_section(text: str, heading: str, required_headers: set[str]) -> tuple[list[str], list[list[str]]]:
    for headers, rows in markdown_tables(section(text, heading)):
        if required_headers <= set(headers):
            return headers, rows
    fail(f"{heading}: missing table with headers {sorted(required_headers)}")


def clean_cell(cell: str) -> str:
    return cell.strip().strip("*").strip().strip("`").strip("*").strip()


def declared_int(cell: str, context: str) -> int:
    match = re.fullmatch(r"\*{0,2}(\d+)\*{0,2}", cell.strip())
    if not match:
        fail(f"{context}: expected an integer, got {cell!r}")
    return int(match.group(1))


def declared_coverage(cell: str, context: str, expected: str) -> None:
    actual = clean_cell(cell)
    if actual != expected:
        fail(f"{context}: expected coverage {expected!r}, got {actual!r}")


def repository_files(root: Path) -> dict[str, set[str]]:
    root = root.resolve()
    sets = {
        "Top-level architecture": {
            path.relative_to(root).as_posix()
            for path in (root / ARCHITECTURE_DIR).glob("*.md")
            if path.is_file()
        },
        "Infrastructure": {
            path.relative_to(root).as_posix()
            for path in (root / ARCHITECTURE_DIR / "infrastructure").rglob("*.md")
            if path.is_file()
        },
        "Generated references": {
            path.relative_to(root).as_posix()
            for path in (root / ARCHITECTURE_DIR / "generated").rglob("*.md")
            if path.is_file()
        },
        "Microservice architecture": {
            path.relative_to(root).as_posix()
            for path in (root / ARCHITECTURE_DIR / "microservices").rglob("*.md")
            if path.is_file()
        },
        "Architecture decisions": {
            path.relative_to(root).as_posix()
            for path in (root / ARCHITECTURE_DIR / "decisions").rglob("*.md")
            if path.is_file()
        },
    }
    all_paths: list[str] = []
    for name, paths in sets.items():
        all_paths.extend(paths)
        if len(paths) != len(set(paths)):
            fail(f"{name}: duplicate repository path")
    duplicates = [path for path, count in Counter(all_paths).items() if count > 1]
    if duplicates:
        fail(f"architecture source sets overlap: {sorted(duplicates)}")
    return sets


def group_ids(root: Path) -> set[str]:
    text = (root / TAXONOMY).read_text(encoding="utf-8")
    groups = set(re.findall(r"^#### `([A-Z]{2}-\d+)`", text, re.MULTILINE))
    if not groups:
        fail(f"{TAXONOMY}: no capability groups found")
    return groups


def relative_target(owner: Path, target: str, root: Path) -> str:
    target = target.strip()
    if target.startswith("<") and target.endswith(">"):
        target = target[1:-1]
    if re.match(r"^[A-Za-z][A-Za-z0-9+.-]*:", target) or target.startswith("//"):
        fail(f"{owner.relative_to(root)}: external allocation target {target!r}")
    bare_target = target.split("#", 1)[0]
    resolved = owner.resolve() if not bare_target else (owner.parent / bare_target).resolve()
    try:
        relative = resolved.relative_to(root.resolve()).as_posix()
    except ValueError:
        fail(f"{owner.relative_to(root)}: allocation target escapes repository: {target}")
    if not resolved.is_file():
        fail(f"{owner.relative_to(root)}: missing allocation target {target}")
    return relative


def path_from_code_cell(owner: Path, cell: str, root: Path) -> str:
    match = re.fullmatch(r"`([^`]+)`", cell.strip())
    if not match:
        fail(f"{owner.relative_to(root)}: path cell must contain one repository-relative code path: {cell!r}")
    value = match.group(1)
    resolved = (root / value).resolve() if value.startswith("design/") else (owner.parent / value).resolve()
    try:
        relative = resolved.relative_to(root.resolve()).as_posix()
    except ValueError:
        fail(f"{owner.relative_to(root)}: declared path escapes repository: {value}")
    if not resolved.is_file():
        fail(f"{owner.relative_to(root)}: missing declared source {value}")
    return relative


def primary_from_cell(owner: Path, cell: str, groups: set[str], root: Path) -> str:
    primary = clean_cell(cell)
    if primary not in groups:
        fail(f"{owner.relative_to(root)}: invalid primary capability {cell!r}")
    return primary


def expected_microservice_allocation(source_path: str) -> tuple[str, str]:
    if source_path in MICROSERVICE_EXEMPT_ALLOCATIONS:
        return MICROSERVICE_EXEMPT_ALLOCATIONS[source_path]
    if source_path in MICROSERVICE_APPENDIX_ALLOCATIONS:
        return MICROSERVICE_APPENDIX_ALLOCATIONS[source_path]
    if source_path in MICROSERVICE_STANDARD_OVERRIDES:
        return MICROSERVICE_STANDARD_OVERRIDES[source_path]

    path = Path(source_path)
    if path.parent.name == "microservices" and path.name == "README.md":
        return "PO-2", MICROSERVICE_STANDARD_CLASSIFICATIONS[path.name]
    if path.parent.name not in MICROSERVICE_SERVICE_PRIMARY:
        fail(f"{source_path}: no expected microservice allocation")
    if path.name not in MICROSERVICE_STANDARD_CLASSIFICATIONS:
        fail(f"{source_path}: no expected microservice allocation")

    primary = MICROSERVICE_SERVICE_PRIMARY[path.parent.name]
    if path.name == "configuration.md":
        primary = "AR-2" if path.parent.name == "game-logic-service" else "PO-3"
    elif path.name == "operations.md":
        primary = "PO-4"
    elif path.name == "runtime-and-data.md" and path.parent.name == "game-session-service":
        primary = "GR-1"
    return primary, MICROSERVICE_STANDARD_CLASSIFICATIONS[path.name]


def parse_linked_ledger(
    root: Path,
    document: Path,
    heading: str,
    expected_paths: set[str],
    groups: set[str],
    allowed_classifications: set[str],
) -> list[LedgerRow]:
    text = document.read_text(encoding="utf-8")
    headers, rows = table_in_section(text, heading, {"Path", "Primary", "Classification"})
    path_index = headers.index("Path")
    primary_index = headers.index("Primary")
    classification_index = headers.index("Classification")
    parsed: list[LedgerRow] = []
    for row in rows:
        path_cell = row[path_index]
        targets = MARKDOWN_LINK_RE.findall(path_cell)
        if len(targets) != 1:
            fail(f"{document.relative_to(root)}: path cell must contain one local link: {path_cell!r}")
        source_path = relative_target(document, targets[0], root)
        primary = primary_from_cell(document, row[primary_index], groups, root)
        classification = row[classification_index].strip()
        if classification not in allowed_classifications:
            fail(
                f"{document.relative_to(root)}:{source_path}: invalid source classification "
                f"{classification!r}; expected one of {sorted(allowed_classifications)}"
            )
        parsed.append(LedgerRow(source_path, primary, classification))
    paths = [row.path for row in parsed]
    if len(paths) != len(set(paths)):
        duplicates = sorted(path for path, count in Counter(paths).items() if count > 1)
        fail(f"{document.relative_to(root)}: duplicate allocation rows: {duplicates}")
    actual = set(paths)
    if actual != expected_paths:
        fail(
            f"{document.relative_to(root)}: source manifest mismatch: "
            f"missing={sorted(expected_paths - actual)}, extra={sorted(actual - expected_paths)}"
        )
    return parsed


def parse_microservice_ledger(root: Path, groups: set[str], expected_paths: set[str]) -> list[LedgerRow]:
    document = root / MICROSERVICE_ALLOCATION
    text = document.read_text(encoding="utf-8")
    parsed: list[LedgerRow] = []
    for headers, rows in markdown_tables(text):
        if {"Design source", "Primary capability", "Source classification"} - set(headers):
            continue
        path_index = headers.index("Design source")
        primary_index = headers.index("Primary capability")
        classification_index = headers.index("Source classification")
        for row in rows:
            source_path = path_from_code_cell(document, row[path_index], root)
            primary_cell = clean_cell(row[primary_index])
            if primary_cell == "Exempt":
                primary = primary_cell
            else:
                primary = primary_from_cell(document, row[primary_index], groups, root)
            classification = row[classification_index].strip()
            expected_primary, expected_classification = expected_microservice_allocation(source_path)
            if primary != expected_primary:
                fail(
                    f"{document.relative_to(root)}:{source_path}: unexpected primary capability "
                    f"{primary!r}; expected {expected_primary!r}"
                )
            if classification != expected_classification:
                fail(
                    f"{document.relative_to(root)}:{source_path}: unexpected source classification "
                    f"{classification!r}; expected {expected_classification!r}"
                )
            parsed.append(LedgerRow(source_path, primary, classification))
    if not parsed:
        fail(f"{document.relative_to(root)}: no allocation rows found")
    paths = [row.path for row in parsed]
    if len(paths) != len(set(paths)):
        duplicates = sorted(path for path, count in Counter(paths).items() if count > 1)
        fail(f"{document.relative_to(root)}: duplicate allocation rows: {duplicates}")
    actual = set(paths)
    if actual != expected_paths:
        fail(
            f"{document.relative_to(root)}: source manifest mismatch: "
            f"missing={sorted(expected_paths - actual)}, extra={sorted(actual - expected_paths)}"
        )
    return parsed


def validate_system(root: Path, source_sets: dict[str, set[str]], groups: set[str]) -> list[LedgerRow]:
    document = root / SYSTEM_ALLOCATION
    direct = parse_linked_ledger(
        root,
        document,
        "Direct Architecture Ledger",
        source_sets["Top-level architecture"],
        groups,
        SYSTEM_CLASSIFICATIONS,
    )
    infrastructure = parse_linked_ledger(
        root,
        document,
        "Infrastructure Ledger",
        source_sets["Infrastructure"],
        groups,
        SYSTEM_CLASSIFICATIONS,
    )
    generated = parse_linked_ledger(
        root,
        document,
        "Generated Ledger",
        source_sets["Generated references"],
        groups,
        {"generated"},
    )
    rows_by_name = {
        "Direct architecture": direct,
        "Infrastructure": infrastructure,
        "Generated": generated,
    }
    headers, rows = table_in_section(document.read_text(encoding="utf-8"), "Coverage Proof", {"Set", "Source files", "Ledger rows"})
    name_index = headers.index("Set")
    source_index = headers.index("Source files")
    ledger_index = headers.index("Ledger rows")
    expected_names = set(rows_by_name)
    declared_names: set[str] = set()
    for row in rows:
        name = clean_cell(row[name_index])
        if name not in rows_by_name:
            if name == "Total":
                continue
            fail(f"{document.relative_to(root)}: unknown coverage set {name!r}")
        if name in declared_names:
            fail(f"{document.relative_to(root)}: duplicate coverage set {name}")
        declared_names.add(name)
        expected_count = len(rows_by_name[name])
        if declared_int(row[source_index], f"{document.relative_to(root)}:{name}: source files") != expected_count:
            fail(f"{document.relative_to(root)}:{name}: source-file summary drift")
        if declared_int(row[ledger_index], f"{document.relative_to(root)}:{name}: ledger rows") != len(rows_by_name[name]):
            fail(f"{document.relative_to(root)}:{name}: ledger-row summary drift")
    if declared_names != expected_names:
        fail(f"{document.relative_to(root)}: coverage proof sets mismatch")
    total = sum(len(rows) for rows in rows_by_name.values())
    total_rows = [row for row in rows if clean_cell(row[name_index]) == "Total"]
    if len(total_rows) != 1:
        fail(f"{document.relative_to(root)}: coverage proof must contain one Total row")
    if declared_int(total_rows[0][source_index], f"{document.relative_to(root)}: total source files") != total:
        fail(f"{document.relative_to(root)}: total source-file summary drift")
    if declared_int(total_rows[0][ledger_index], f"{document.relative_to(root)}: total ledger rows") != total:
        fail(f"{document.relative_to(root)}: total ledger-row summary drift")

    text = document.read_text(encoding="utf-8")
    for pattern in (r"\|P_ledger\|\s*=\s*(\d+)", r"\|unique\(P_ledger\)\|\s*=\s*(\d+)", r"The\s+(\d+)\s+source files"):
        match = re.search(pattern, text)
        if not match or int(match.group(1)) != total:
            fail(f"{document.relative_to(root)}: source-count prose claim does not match {total}")
    primary_counts = Counter(row.primary for rows in rows_by_name.values() for row in rows)
    primary_claim = re.search(r"Primary allocation counts are:\s*(.*?)\.\s+These sum", text, re.DOTALL)
    if not primary_claim:
        fail(f"{document.relative_to(root)}: missing primary allocation count claim")
    claimed_pairs = {capability: int(count) for capability, count in re.findall(r"([A-Z]{2}-\d+)\s+(\d+)", primary_claim.group(1))}
    expected_primary_counts = {group: primary_counts.get(group, 0) for group in groups}
    sum_match = re.search(r"These sum to\s+(\d+)", text)
    if claimed_pairs != expected_primary_counts or sum(claimed_pairs.values()) != total or not sum_match or int(sum_match.group(1)) != total:
        fail(f"{document.relative_to(root)}: primary allocation count claim does not match ledger rows")

    classification_claim = re.search(r"Classification counts are:\s*(.*?)\.\s+Primary allocation", text, re.DOTALL)
    if not classification_claim:
        fail(f"{document.relative_to(root)}: missing classification count claim")
    claimed_classifications = {
        label.strip().removeprefix("and "): int(count)
        for count, label in re.findall(r"`(\d+)`\s+([^,]+)", classification_claim.group(1))
    }
    actual_classifications = Counter(
        row.classification for ledger_rows in rows_by_name.values() for row in ledger_rows
    )
    if claimed_classifications != dict(actual_classifications):
        fail(f"{document.relative_to(root)}: classification count claim does not match ledger rows")
    return [*direct, *infrastructure, *generated]


def parse_summary_table(
    root: Path,
    document: Path,
    heading: str,
    required_headers: set[str],
) -> tuple[list[str], dict[str, list[str]]]:
    headers, rows = table_in_section(document.read_text(encoding="utf-8"), heading, required_headers)
    key_index = 0
    values: dict[str, list[str]] = {}
    for row in rows:
        key = clean_cell(row[key_index])
        if key in values:
            fail(f"{document.relative_to(root)}:{heading}: duplicate summary row {key}")
        values[key] = row
    return headers, values


def validate_microservice_summary(root: Path, rows: list[LedgerRow], expected_paths: set[str]) -> None:
    document = root / MICROSERVICE_ALLOCATION
    headers, measures = parse_summary_table(root, document, "Coverage Summary", {"Measure", "Count"})
    count_index = headers.index("Count")
    expected_measures = {
        "Markdown sources discovered": len(expected_paths),
        "Capability-allocated sources": sum(row.primary != "Exempt" for row in rows),
        "Explicitly exempt artifacts": sum(row.primary == "Exempt" for row in rows),
        "Unallocated sources": len(expected_paths) - len(rows),
        "Taxonomy gaps": 0,
    }
    if set(measures) != set(expected_measures) | {"Coverage"}:
        fail(f"{document.relative_to(root)}: coverage summary measure rows drifted")
    for name, expected in expected_measures.items():
        if declared_int(measures[name][count_index], f"{document.relative_to(root)}:{name}") != expected:
            fail(f"{document.relative_to(root)}:{name}: coverage summary drift")
    declared_coverage(measures["Coverage"][count_index], f"{document.relative_to(root)}:Coverage", "100%")

    headers, classifications = parse_summary_table(root, document, "Coverage Summary", {"Source classification", "Count"})
    count_index = headers.index("Count")
    actual = Counter(
        {
            "Service overview": sum(
                row.primary != "Exempt" and Path(row.path).name == "README.md" for row in rows
            ),
            "API contract": sum(
                row.primary != "Exempt" and Path(row.path).name == "api-contracts.md" for row in rows
            ),
            "Configuration contract": sum(
                row.primary != "Exempt" and Path(row.path).name == "configuration.md" for row in rows
            ),
            "Operations contract": sum(
                row.primary != "Exempt" and Path(row.path).name == "operations.md" for row in rows
            ),
            "Runtime/data contract": sum(
                row.primary != "Exempt" and Path(row.path).name == "runtime-and-data.md" for row in rows
            ),
        }
    )
    expected_classifications = {
        "Exempt governance/template artifacts": sum(row.primary == "Exempt" for row in rows),
        "Service overviews": actual["Service overview"],
        "API contracts": actual["API contract"],
        "Configuration contracts": actual["Configuration contract"],
        "Operations contracts": actual["Operations contract"],
        "Runtime/data contracts": actual["Runtime/data contract"],
        "Service-specific design, UX, protocol, and workflow appendices": sum(
            1
            for row in rows
            if row.primary != "Exempt"
            and Path(row.path).name
            not in {"README.md", "api-contracts.md", "configuration.md", "operations.md", "runtime-and-data.md"}
        ),
    }
    if set(classifications) != set(expected_classifications) | {"Total"}:
        fail(f"{document.relative_to(root)}: source-classification summary rows drifted")
    for name, expected in expected_classifications.items():
        if declared_int(classifications[name][count_index], f"{document.relative_to(root)}:{name}") != expected:
            fail(f"{document.relative_to(root)}:{name}: classification summary drift")
    if declared_int(classifications["Total"][count_index], f"{document.relative_to(root)}: classification total") != len(rows):
        fail(f"{document.relative_to(root)}: classification total drift")


def validate_top_allocation_ledger(root: Path, groups: set[str], expected_decisions: set[str]) -> list[LedgerRow]:
    document = root / TOP_ALLOCATION
    text = document.read_text(encoding="utf-8")
    headers, rows = table_in_section(text, "Allocation Ledger", {"Design source", "Heading or scope", "Primary capability"})
    source_index = headers.index("Design source")
    heading_index = headers.index("Heading or scope")
    primary_index = headers.index("Primary capability")
    identities: list[str] = []
    for row in rows:
        targets = MARKDOWN_LINK_RE.findall(row[source_index])
        if len(targets) != 1:
            fail(f"{document.relative_to(root)}: allocation ledger source must have one link")
        source_path = relative_target(document, targets[0], root)
        if source_path in identities:
            fail(f"{document.relative_to(root)}: duplicate allocation ledger row {source_path}")
        identities.append(source_path)
        expected = TOP_ALLOCATION_ROWS.get(source_path)
        if expected is None:
            fail(f"{document.relative_to(root)}: unexpected allocation ledger row {source_path}")
        if (row[heading_index].strip(), clean_cell(row[primary_index])) != expected:
            fail(
                f"{document.relative_to(root)}:{source_path}: allocation row drift; "
                f"expected heading/primary {expected!r}"
            )
    if set(identities) != set(TOP_ALLOCATION_ROWS) or len(identities) != len(TOP_ALLOCATION_ROWS):
        fail(f"{document.relative_to(root)}: allocation ledger references drifted")

    headers, rows = table_in_section(text, "Architecture Decision Allocation", {"Design source", "Primary capability"})
    source_index = headers.index("Design source")
    primary_index = headers.index("Primary capability")
    parsed: list[LedgerRow] = []
    for row in rows:
        source_path = path_from_code_cell(document, row[source_index], root)
        primary_cell = clean_cell(row[primary_index])
        primary = "Exempt" if primary_cell == "Exempt" else primary_from_cell(document, row[primary_index], groups, root)
        parsed.append(LedgerRow(source_path, primary, ""))
    paths = [row.path for row in parsed]
    if len(paths) != len(set(paths)) or set(paths) != expected_decisions:
        fail(f"{document.relative_to(root)}: decision allocation manifest mismatch")
    exemptions = [row.path for row in parsed if row.primary == "Exempt"]
    if exemptions != ["design/architecture/decisions/README.md"]:
        fail(f"{document.relative_to(root)}: decision allocation exemptions drifted: {exemptions}")
    return parsed


def parse_explicit_exemptions(cell: str, context: str) -> tuple[int, int]:
    numbers = re.findall(r"\d+", cell)
    if not numbers:
        fail(f"{context}: missing gap count in {cell!r}")
    gap_count = int(numbers[0])
    exemption_match = re.search(r"(\d+)\s+(?:explicit|registry)\s+", cell)
    exemption_count = int(exemption_match.group(1)) if exemption_match else 0
    return gap_count, exemption_count


def validate_top_summary(
    root: Path,
    source_sets: dict[str, set[str]],
    system_rows: list[LedgerRow],
    micro_rows: list[LedgerRow],
    decision_rows: list[LedgerRow],
) -> None:
    document = root / TOP_ALLOCATION
    headers, values = parse_summary_table(root, document, "Coverage Summary", {"Source class", "Discovered", "Allocated", "Ambiguous or gap", "Coverage"})
    discovered_index = headers.index("Discovered")
    allocated_index = headers.index("Allocated")
    gap_index = headers.index("Ambiguous or gap")
    coverage_index = headers.index("Coverage")
    allocated_counts = {
        "Top-level architecture": sum(row.primary != "Exempt" for row in system_rows if row.path in source_sets["Top-level architecture"]),
        "Infrastructure": sum(row.primary != "Exempt" for row in system_rows if row.path in source_sets["Infrastructure"]),
        "Generated references": sum(row.primary != "Exempt" for row in system_rows if row.path in source_sets["Generated references"]),
        "Microservice architecture": sum(row.primary != "Exempt" for row in micro_rows),
        "Architecture decisions": sum(row.primary != "Exempt" for row in decision_rows),
    }
    exemption_counts = {
        "Top-level architecture": 0,
        "Infrastructure": 0,
        "Generated references": 0,
        "Microservice architecture": sum(row.primary == "Exempt" for row in micro_rows),
        "Architecture decisions": sum(row.primary == "Exempt" for row in decision_rows),
    }
    if set(values) != set(source_sets) | {"Total"}:
        fail(f"{document.relative_to(root)}: top-level coverage summary rows drifted")
    for name in source_sets:
        expected_discovered = len(source_sets[name])
        actual_discovered = declared_int(values[name][discovered_index], f"{document.relative_to(root)}:{name}: discovered")
        actual_allocated = declared_int(values[name][allocated_index], f"{document.relative_to(root)}:{name}: allocated")
        if actual_discovered != expected_discovered or actual_allocated != allocated_counts[name]:
            fail(f"{document.relative_to(root)}:{name}: coverage summary drift")
        gap_count, exemption_count = parse_explicit_exemptions(values[name][gap_index], f"{document.relative_to(root)}:{name}")
        if gap_count != 0 or exemption_count != exemption_counts[name]:
            fail(f"{document.relative_to(root)}:{name}: gap/exemption summary drift")
        declared_coverage(values[name][coverage_index], f"{document.relative_to(root)}:{name}: coverage", "100% classified")

    total = values["Total"]
    discovered = sum(len(paths) for paths in source_sets.values())
    allocated = sum(allocated_counts.values())
    exemptions = sum(exemption_counts.values())
    if declared_int(total[discovered_index], f"{document.relative_to(root)}: total discovered") != discovered:
        fail(f"{document.relative_to(root)}: total discovered summary drift")
    if declared_int(total[allocated_index], f"{document.relative_to(root)}: total allocated") != allocated:
        fail(f"{document.relative_to(root)}: total allocated summary drift")
    gap_count, exemption_count = parse_explicit_exemptions(total[gap_index], f"{document.relative_to(root)}: total")
    if gap_count != 0 or exemption_count != exemptions:
        fail(f"{document.relative_to(root)}: total gap/exemption summary drift")
    declared_coverage(total[coverage_index], f"{document.relative_to(root)}: total coverage", "100% classified")


def validate(root: Path = ROOT) -> None:
    root = root.resolve()
    source_sets = repository_files(root)
    groups = group_ids(root)
    system_rows = validate_system(root, source_sets, groups)
    micro_rows = parse_microservice_ledger(root, groups, source_sets["Microservice architecture"])
    validate_microservice_summary(root, micro_rows, source_sets["Microservice architecture"])
    decision_rows = validate_top_allocation_ledger(root, groups, source_sets["Architecture decisions"])
    validate_top_summary(root, source_sets, system_rows, micro_rows, decision_rows)
    print(
        "design capability allocation passed: "
        f"{sum(len(paths) for paths in source_sets.values())} sources "
        f"({sum(row.primary != 'Exempt' for row in [*system_rows, *micro_rows, *decision_rows])} allocated, "
        f"{sum(row.primary == 'Exempt' for row in [*system_rows, *micro_rows, *decision_rows])} explicit exemptions)"
    )


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=ROOT, help="repository root to validate")
    args = parser.parse_args()
    validate(args.root)


if __name__ == "__main__":
    main()
