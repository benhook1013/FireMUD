#!/usr/bin/env python3
"""Generate and verify lossless domain implementation-tracker transposition."""

from __future__ import annotations

import argparse
import hashlib
import re
import sys
from collections import defaultdict
from dataclasses import dataclass
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
TRACKING_DIR = ROOT / "design/project-management/implementation-tracking"
MAP_PATH = TRACKING_DIR / "migration-ledgers/SOURCE_ALLOCATION_MAP.md"
SOURCES_DIR = ROOT / "design/project-management/vertical-slices"
ALLOCATION_HEADING = "## Allocation Rows\n"
SOURCE_MARKER = re.compile(
    r'<!-- migration-source path="(?P<path>[^"]+)" lines="(?P<lines>[^"]+)" '
    r'sha256="(?P<sha>[a-f0-9]{64})" heading-offset="3" -->\n'
    r'(?P<body>.*?)\n<!-- /migration-source -->',
    re.DOTALL,
)
SOURCE_ROW = re.compile(
    r"^\| \[(?P<source>[^]]+)\]\(\.\./\.\./vertical-slices/(?P=source)\) "
    r"\| (?P<lines>[^|]+) \| (?P<tracker>[^|]+) "
    r"\| \[(?P<ledger>[^]]+)\]\(\./(?P=ledger)\) "
    r"\| (?P<rationale>[^|]+) \| (?P<review>[^|]+) \|$"
)
INVENTORY_ROW = re.compile(
    r"^\| \[(?P<source>[^]]+)\]\(\.\./\.\./vertical-slices/(?P=source)\) "
    r"\| (?P<total>[^|]+) \| (?P<state>[^|]+) \| (?P<coverage>[^|]+) "
    r"\| (?P<targets>[^|]+) \| (?P<review>[^|]+) \|$"
)


@dataclass(frozen=True)
class Allocation:
    source: str
    ranges: str
    tracker: str
    ledger: str
    rationale: str
    review: str

    @property
    def tracker_path(self) -> Path:
        return TRACKING_DIR / self.ledger

    @property
    def ledger_path(self) -> Path:
        return TRACKING_DIR / "migration-ledgers" / self.ledger

    @property
    def anchor(self) -> str:
        stem = re.sub(r"[^a-z0-9]+", "-", self.source.lower().removesuffix(".md")).strip("-")
        ranges = re.sub(r"[^a-z0-9]+", "-", self.ranges.lower()).strip("-")
        return f"source-{stem}-{ranges}"


def parse_ranges(specification: str) -> list[tuple[int, int]]:
    ranges: list[tuple[int, int]] = []
    for segment in specification.split(","):
        segment = segment.strip()
        if not segment:
            raise ValueError(f"empty source-line segment in {specification!r}")
        if "-" in segment:
            start_text, end_text = segment.split("-", 1)
            start, end = int(start_text), int(end_text)
        else:
            start = end = int(segment)
        if start < 1 or end < start:
            raise ValueError(f"invalid source-line range {segment!r}")
        ranges.append((start, end))
    return ranges


def source_lines(source: str) -> list[str]:
    path = SOURCES_DIR / source
    if not path.is_file():
        raise ValueError(f"missing legacy source record: {path.relative_to(ROOT)}")
    return path.read_text().splitlines()


def extract_source_status(lines: list[str]) -> str:
    for line in lines[:16]:
        match = re.search(r"\bStatus:\s*(.+)", line, flags=re.IGNORECASE)
        if match:
            status = match.group(1).strip()
            return status[:160].rstrip(".")
    return "Status retained in detailed source evidence"


def source_title(lines: list[str]) -> str:
    for line in lines:
        if line.startswith("# "):
            return line[2:].strip()
    raise ValueError("legacy source record is missing a top-level title")


def shifted_markdown(lines: list[str], heading_prefix: str) -> list[str]:
    shifted: list[str] = []
    for line in lines:
        heading = re.match(r"^(#{1,3})\s", line)
        if heading:
            title = line[heading.end() :]
            shifted.append(
                "#" * (len(heading.group(1)) + 3) + f" {heading_prefix}: {title}"
            )
        else:
            shifted.append(line)
    return shifted


def rebase_source_local_links(lines: list[str]) -> list[str]:
    rebased: list[str] = []
    for line in lines:
        # The source and tracker directories are siblings. Preserve the source target while
        # rebasing only Markdown links that originally named a sibling vertical-slice file.
        line = re.sub(
            r"(\]\()\./([^\s)]+\.md(?:#[^\s)]+)?)(\))",
            r"\1../vertical-slices/\2\3",
            line,
        )
        line = re.sub(
            r"^(\[[^]]+\]:\s+)\./(\S+\.md(?:#\S+)?)",
            r"\1../vertical-slices/\2",
            line,
        )
        rebased.append(line)
    return rebased


def normalize_fragment_start_list(lines: list[str]) -> list[str]:
    if not lines:
        return lines
    first_item = re.match(r"^(?P<indent> {2,})(?:[-+*]|\d+\.)\s", lines[0])
    if not first_item:
        return lines
    indent = first_item.group("indent")
    normalized = list(lines)
    for index, line in enumerate(normalized):
        if not line:
            continue
        if line.startswith(indent):
            normalized[index] = line[len(indent) :]
            continue
        break
    return normalized


def expected_body(all_lines: list[str], ranges: str, heading_prefix: str) -> str:
    parts: list[str] = []
    parsed = parse_ranges(ranges)
    for index, (start, end) in enumerate(parsed):
        if end > len(all_lines):
            raise ValueError(
                f"source-line range {start}-{end} exceeds source length {len(all_lines)}"
            )
        if index:
            previous_start, previous_end = parsed[index - 1]
            if start <= previous_end:
                raise ValueError(f"overlapping source-line ranges in {ranges!r}")
            parts.append(f"<!-- source-gap: lines {previous_end + 1}-{start - 1} -->")
        fragment = rebase_source_local_links(
            shifted_markdown(all_lines[start - 1 : end], heading_prefix)
        )
        parts.extend(normalize_fragment_start_list(fragment))
    return "\n".join(parts)


def parse_map() -> tuple[str, list[Allocation], dict[str, int]]:
    text = MAP_PATH.read_text()
    if ALLOCATION_HEADING not in text:
        raise ValueError("source allocation map has no allocation table")
    inventory_text, allocation_text = text.split(ALLOCATION_HEADING, 1)
    inventory_totals: dict[str, int] = {}
    for line in inventory_text.splitlines():
        match = INVENTORY_ROW.match(line)
        if match:
            start, end = parse_ranges(match.group("total"))[0]
            if start != 1:
                raise ValueError(f"inventory range must start at line 1: {match.group('source')}")
            inventory_totals[match.group("source")] = end

    allocations: list[Allocation] = []
    for line in allocation_text.splitlines():
        match = SOURCE_ROW.match(line)
        if match:
            values = match.groupdict()
            allocations.append(
                Allocation(
                    source=values["source"],
                    ranges=values["lines"],
                    tracker=values["tracker"],
                    ledger=values["ledger"],
                    rationale=values["rationale"],
                    review=values["review"],
                )
            )
    if not allocations:
        raise ValueError("source allocation map has no allocation rows")
    return inventory_text, allocations, inventory_totals


def validate_allocations(allocations: list[Allocation], inventory_totals: dict[str, int]) -> None:
    by_source: dict[str, list[Allocation]] = defaultdict(list)
    for allocation in allocations:
        by_source[allocation.source].append(allocation)

    missing_inventory = set(by_source) - set(inventory_totals)
    if missing_inventory:
        raise ValueError(f"allocation rows missing from inventory: {sorted(missing_inventory)}")
    missing_allocations = set(inventory_totals) - set(by_source)
    if missing_allocations:
        raise ValueError(f"inventory rows without allocation: {sorted(missing_allocations)}")

    for source, total in inventory_totals.items():
        actual_lines = source_lines(source)
        if len(actual_lines) != total:
            raise ValueError(
                f"inventory line count drift for {source}: map={total}, source={len(actual_lines)}"
            )
        claimed: list[int] = []
        for allocation in by_source[source]:
            for start, end in parse_ranges(allocation.ranges):
                claimed.extend(range(start, end + 1))
        expected = list(range(1, total + 1))
        if sorted(claimed) != expected:
            raise ValueError(
                f"allocation coverage is not exact for {source}: expected 1-{total}, got {claimed}"
            )


def update_inventory_states(text: str) -> str:
    inventory_text, allocation_text = text.split(ALLOCATION_HEADING, 1)
    allocation_text, _, _ = allocation_text.partition("\n## Mapping Status\n")
    updated: list[str] = []
    for line in inventory_text.splitlines():
        match = INVENTORY_ROW.match(line)
        if not match:
            updated.append(line)
            continue
        values = match.groupdict()
        updated.append(
            "| [{source}](../../vertical-slices/{source}) | {total} | "
            "Migrated; Spark coverage audit pending | {coverage} | {targets} | "
            "Pending Spark post-transposition audit |".format(**values)
        )
    return (
        "\n".join(updated)
        + "\n"
        + ALLOCATION_HEADING
        + allocation_text.rstrip()
        + "\n\n## Mapping Status\n\n"
        + "Mapping and lossless transposition are complete. Luna audited the allocation map; "
        + "Spark source-coverage audits remain pending for each generated domain tracker.\n"
    )


def table_cell(value: str) -> str:
    return re.sub(r"\s+", " ", value).strip().replace("|", "\\|")


def write_tracker(allocation_group: list[Allocation]) -> None:
    first = allocation_group[0]
    title = first.tracker
    tracker_path = first.tracker_path
    records: list[tuple[Allocation, list[str], str, str]] = []
    for allocation in sorted(allocation_group, key=lambda item: (item.source, parse_ranges(item.ranges))):
        lines = source_lines(allocation.source)
        records.append(
            (allocation, lines, source_title(lines), extract_source_status(lines))
        )

    output = [
        f"# {title}",
        "",
        "## Current Status",
        "",
        "Lossless domain transposition is complete. The implementation claims, open gaps, and discussion items below remain source-backed until the required Spark coverage audit verifies each migrated range.",
        "",
        "## Implementation Record Index",
        "",
        "Use this index to locate the current domain capability. The detailed evidence preserves every allocated legacy source line and is intentionally kept in the same document for comparison.",
        "",
        "| Capability and ownership focus | Source-declared status | Source range | Evidence |",
        "| --- | --- | --- | --- |",
    ]
    for allocation, _, source_record_title, status in records:
        source_link = f"../vertical-slices/{allocation.source}"
        output.append(
            f"| [{table_cell(source_record_title)}]({source_link}) - {table_cell(allocation.rationale)} | {table_cell(status)} | {allocation.ranges} | [source evidence](#{allocation.anchor}) |"
        )
    output.extend(
        [
            "",
            "## Canonical Design Sources",
            "",
            "Canonical target-state design remains under [design/architecture](../../architecture/README.md). The migrated evidence links to the exact source records that previously carried implementation-tracking detail.",
            "",
            "## Verified Live Implementation",
            "",
            "The source-backed claims are indexed above. Spark coverage review is pending before they are promoted from migrated evidence to independently verified live status.",
            "",
            "## Active Gaps",
            "",
            "Source-declared active gaps remain in the detailed evidence below. The post-transposition review will extract any live gaps into this section without losing their original context.",
            "",
            "## To Discuss",
            "",
            "Source-declared unresolved design or implementation questions remain in the detailed evidence below until they are consolidated into this domain tracker.",
            "",
            "## Service and Contract Map",
            "",
            "The detailed evidence identifies the public contracts, owning services, and focused proof for each capability. The Spark review produces the service-level audit queue for this tracker.",
            "",
            "## Source Evidence",
            "",
            "The following records are a line-preserving transposition. Heading depth is shifted by three levels and same-directory Markdown links are rebased only so the combined tracker remains valid and navigable.",
        ]
    )
    for allocation, lines, source_record_title, _ in records:
        source_path = f"design/project-management/vertical-slices/{allocation.source}"
        digest = hashlib.sha256((SOURCES_DIR / allocation.source).read_bytes()).hexdigest()
        output.extend(
            [
                "",
                f"### {allocation.anchor}",
                "",
                f"#### {source_record_title} - {allocation.rationale} (source lines {allocation.ranges})",
                "",
                f"##### Preserved Source Text: {allocation.anchor}",
                "",
                f'<!-- migration-source path="{source_path}" lines="{allocation.ranges}" sha256="{digest}" heading-offset="3" -->',
                expected_body(lines, allocation.ranges, allocation.anchor),
                "<!-- /migration-source -->",
            ]
        )
    tracker_path.write_text("\n".join(output) + "\n")


def write_ledger(allocation_group: list[Allocation]) -> None:
    first = allocation_group[0]
    records: list[tuple[Allocation, list[str], str, str]] = []
    for allocation in sorted(allocation_group, key=lambda item: (item.source, parse_ranges(item.ranges))):
        lines = source_lines(allocation.source)
        records.append(
            (allocation, lines, source_title(lines), extract_source_status(lines))
        )

    output = [
        f"# {first.tracker} Migration Ledger",
        "",
        f"Tracker: [{first.tracker}](../{first.ledger})",
        "",
        "Status: Lossless source transposition complete. Every allocated source range is copied into the tracker; Spark source-coverage audit is pending.",
        "",
        "| Legacy source record | Source lines | Disposition | Destination heading or anchor | Preserved facts or explicit rationale | Spark review |",
        "| --- | --- | --- | --- | --- | --- |",
    ]
    for allocation, _, source_record_title, status in records:
        source_link = f"../../vertical-slices/{allocation.source}"
        output.append(
            f"| [{source_record_title}]({source_link}) | {allocation.ranges} | copied | [Source evidence](../{allocation.ledger}#{allocation.anchor}) | Verbatim range; source-declared status: {status}. Focus: {allocation.rationale}. | Pending Spark coverage audit |"
        )
    first.ledger_path.write_text("\n".join(output) + "\n")


def write_transposition(allocations: list[Allocation]) -> None:
    by_tracker: dict[str, list[Allocation]] = defaultdict(list)
    for allocation in allocations:
        by_tracker[allocation.ledger].append(allocation)
    for allocation_group in by_tracker.values():
        write_tracker(allocation_group)
        write_ledger(allocation_group)
    MAP_PATH.write_text(update_inventory_states(MAP_PATH.read_text()))


def check_transposition(allocations: list[Allocation]) -> None:
    expected_markers: dict[tuple[str, str, str], Allocation] = {
        (allocation.ledger, allocation.source, allocation.ranges): allocation
        for allocation in allocations
    }
    actual_markers: set[tuple[str, str, str]] = set()
    for ledger, allocation_group in defaultdict(list, {
        key: [item for item in allocations if item.ledger == key]
        for key in {item.ledger for item in allocations}
    }).items():
        tracker_text = (TRACKING_DIR / ledger).read_text()
        for marker in SOURCE_MARKER.finditer(tracker_text):
            source = Path(marker.group("path")).name
            key = (ledger, source, marker.group("lines"))
            if key not in expected_markers:
                raise ValueError(f"unexpected transposition marker: {key}")
            allocation = expected_markers[key]
            source_path = SOURCES_DIR / source
            digest = hashlib.sha256(source_path.read_bytes()).hexdigest()
            if marker.group("sha") != digest:
                raise ValueError(f"source checksum drift for marker: {key}")
            expected = expected_body(
                source_lines(source), marker.group("lines"), allocation.anchor
            )
            if marker.group("body") != expected:
                raise ValueError(f"transposed text differs from source for marker: {key}")
            actual_markers.add(key)
        ledger_path = TRACKING_DIR / "migration-ledgers" / ledger
        ledger_text = ledger_path.read_text()
        for allocation in allocation_group:
            if allocation.ranges not in ledger_text or allocation.anchor not in ledger_text:
                raise ValueError(
                    f"ledger lacks destination reference for {allocation.source} {allocation.ranges}"
                )
    if actual_markers != set(expected_markers):
        missing = set(expected_markers) - actual_markers
        raise ValueError(f"missing transposition markers: {sorted(missing)}")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--write", action="store_true", help="write the tracker and ledger transposition")
    parser.add_argument("--check", action="store_true", help="verify map coverage and tracker transposition")
    args = parser.parse_args()
    if not args.write and not args.check:
        parser.error("one of --write or --check is required")
    try:
        _, allocations, inventory_totals = parse_map()
        validate_allocations(allocations, inventory_totals)
        if args.write:
            write_transposition(allocations)
        if args.check:
            check_transposition(allocations)
    except ValueError as error:
        print(f"implementation tracker migration validation failed: {error}", file=sys.stderr)
        return 1
    print(
        f"implementation tracker migration validated: {len(allocations)} allocation ranges across "
        f"{len(inventory_totals)} legacy source records"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
