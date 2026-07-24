#!/usr/bin/env python3
"""Repository-aware cloc reports for FireMUD.

The tool scans a Git inventory once with cloc's by-file JSON output, then builds
all repository, source, test, documentation, design, and module views from that
single result. Generated and ignored files never enter the inventory.
"""

from __future__ import annotations

import argparse
import json
import shutil
import subprocess
import sys
import tempfile
from dataclasses import dataclass, field
from pathlib import Path, PurePosixPath
from typing import Iterable, Sequence


SOURCE_ROOTS = ("buildSrc", "dev-tools", "gradle", "protos", "services", "web-client")
SCOPE_NAMES = (
    "repo",
    "source",
    "prod",
    "tests",
    "markdown",
    "design",
    "architecture",
    "service-docs",
)
DESIGN_SECTIONS = (
    ("architecture", "architecture", "design/architecture/"),
    ("project_management", "project management", "design/project-management/"),
    ("observability", "observability", "design/observability/"),
    ("operations", "operations", "design/operations/"),
    ("other_design", "other design", None),
)
DEFAULT_BAR_WIDTH = 16


class ReportError(RuntimeError):
    """A user-facing report failure."""


@dataclass(frozen=True)
class FileStats:
    path: str
    language: str
    blank: int
    comments: int
    lines: int


@dataclass(frozen=True)
class Counts:
    files: int = 0
    blank: int = 0
    comments: int = 0
    lines: int = 0

    def as_dict(self) -> dict[str, int]:
        return {
            "files": self.files,
            "blank": self.blank,
            "comments": self.comments,
            "lines": self.lines,
        }


@dataclass
class ReportNode:
    key: str
    label: str
    counts: Counts
    children: list[ReportNode] = field(default_factory=list)
    overlaps: tuple[str, ...] = ()


def run_command(args: Sequence[str], cwd: Path) -> subprocess.CompletedProcess[bytes]:
    try:
        return subprocess.run(args, cwd=cwd, capture_output=True, check=True)
    except subprocess.CalledProcessError as error:
        stderr = error.stderr.decode(errors="replace").strip()
        detail = f": {stderr}" if stderr else ""
        raise ReportError(f"Command failed ({' '.join(args)}){detail}") from error


def require_tool(name: str) -> None:
    if shutil.which(name) is None:
        raise ReportError(f"{name} is not installed or not on PATH")


def repository_root() -> Path:
    require_tool("git")
    result = run_command(("git", "rev-parse", "--show-toplevel"), Path.cwd())
    return Path(result.stdout.decode().strip())


def decode_nul_paths(raw: bytes) -> list[str]:
    return [part.decode(errors="surrogateescape") for part in raw.split(b"\0") if part]


def tracked_inventory(root: Path) -> list[str]:
    result = run_command(("git", "ls-files", "-z"), root)
    return decode_nul_paths(result.stdout)


def diff_inventory(root: Path, git_range: str) -> tuple[list[str], int]:
    result = run_command(
        ("git", "diff", "--name-only", "--diff-filter=ACMRD", "-z", git_range, "--"),
        root,
    )
    present: list[str] = []
    omitted = 0
    for path in decode_nul_paths(result.stdout):
        if (root / path).exists():
            present.append(path)
        else:
            omitted += 1
    return present, omitted


def scan_cloc(root: Path, inventory: Iterable[str]) -> list[FileStats]:
    require_tool("cloc")
    paths = list(inventory)
    if not paths:
        return []

    for path in paths:
        if "\n" in path:
            raise ReportError(f"cloc list files cannot represent a newline in path: {path!r}")

    list_path: Path | None = None
    try:
        with tempfile.NamedTemporaryFile("w", encoding="utf-8", delete=False) as file_list:
            list_path = Path(file_list.name)
            file_list.write("".join(f"{path}\n" for path in paths))

        result = run_command(
            (
                "cloc",
                "--quiet",
                "--skip-uniqueness",
                "--json",
                "--by-file",
                f"--list-file={list_path}",
            ),
            root,
        )
        payload = json.loads(result.stdout)
    except json.JSONDecodeError as error:
        raise ReportError(f"cloc returned invalid JSON: {error}") from error
    finally:
        if list_path is not None:
            list_path.unlink(missing_ok=True)

    files: list[FileStats] = []
    for raw_path, stats in payload.items():
        if raw_path in {"header", "SUM"}:
            continue
        path = raw_path[2:] if raw_path.startswith("./") else raw_path
        files.append(
            FileStats(
                path=path,
                language=str(stats.get("language", "unknown")),
                blank=int(stats.get("blank", 0)),
                comments=int(stats.get("comment", 0)),
                lines=int(stats.get("code", 0)),
            )
        )
    return sorted(files, key=lambda item: item.path)


def is_root_file(path: str) -> bool:
    return "/" not in path


def is_markdown(path: str) -> bool:
    return PurePosixPath(path).name.endswith(".md")


def is_under_source_root(path: str) -> bool:
    return any(path.startswith(f"{root}/") for root in SOURCE_ROOTS)


def test_rule(path: str) -> str | None:
    base = PurePosixPath(path).name
    wrapped = f"/{path}"
    path_rules = (
        ("/src/test/", "gradle_src_test"),
        ("/src/testFixtures/", "gradle_test_fixtures"),
        ("/src/integrationTest/", "gradle_integration_test"),
        ("/src/e2e/", "gradle_e2e_test"),
        ("/src/e2eTest/", "gradle_e2e_test"),
        ("/__tests__/", "js_dunder_tests"),
    )
    for marker, rule in path_rules:
        if marker in wrapped:
            return rule
    if path.startswith("dev-tools/tests/"):
        return "dev_tools_contract_tests"
    if path.startswith("dev-tools/validation/") and base.startswith("test_") and base.endswith(".py"):
        return "dev_tools_validation_test"

    test_suffixes = (
        ".test.js",
        ".test.jsx",
        ".test.ts",
        ".test.tsx",
        ".spec.js",
        ".spec.jsx",
        ".spec.ts",
        ".spec.tsx",
    )
    if base.endswith(test_suffixes):
        return "js_test_name"
    if base in {
        "playwright.config.js",
        "playwright.config.ts",
        "playwright.config.cjs",
        "playwright.config.mjs",
    }:
        return "playwright_config"
    if base in {
        "cypress.config.js",
        "cypress.config.ts",
        "cypress.config.cjs",
        "cypress.config.mjs",
    }:
        return "cypress_config"
    return None


def source_classification(path: str) -> tuple[str, str] | None:
    is_source = (is_under_source_root(path) or is_root_file(path)) and not is_markdown(path)
    if not is_source:
        return None
    rule = test_rule(path)
    if rule is not None:
        return "tests", rule
    if is_root_file(path):
        return "prod", "root_non_markdown"
    root = path.split("/", 1)[0]
    return "prod", f"source_root:{root}"


def is_service_doc(path: str) -> bool:
    parts = path.split("/")
    return (
        len(parts) == 3
        and parts[0] == "services"
        and parts[2] == "README.md"
    ) or (
        len(parts) >= 4
        and parts[0] == "services"
        and parts[2] == "design"
    )


def in_scope(path: str, scope: str) -> bool:
    classification = source_classification(path)
    if scope == "repo":
        return True
    if scope == "source":
        return classification is not None
    if scope in {"prod", "tests"}:
        return classification is not None and classification[0] == scope
    if scope == "markdown":
        return is_markdown(path)
    if scope == "design":
        return path.startswith("design/")
    if scope == "architecture":
        return path.startswith("design/architecture/")
    if scope == "service-docs":
        return is_service_doc(path)
    raise ReportError(f"Unknown scope: {scope}")


def aggregate(files: Iterable[FileStats]) -> Counts:
    selected = list(files)
    return Counts(
        files=len(selected),
        blank=sum(item.blank for item in selected),
        comments=sum(item.comments for item in selected),
        lines=sum(item.lines for item in selected),
    )


def scope_files(files: Iterable[FileStats], scope: str) -> list[FileStats]:
    return [item for item in files if in_scope(item.path, scope)]


def design_section(path: str) -> str:
    for key, _label, prefix in DESIGN_SECTIONS:
        if prefix is not None and path.startswith(prefix):
            return key
    return "other_design"


def assert_partition(parent: Counts, children: Iterable[Counts], name: str) -> None:
    parts = list(children)
    if sum(item.files for item in parts) != parent.files:
        raise ReportError(f"{name} child file counts do not equal the parent total")
    if sum(item.lines for item in parts) != parent.lines:
        raise ReportError(f"{name} child line counts do not equal the parent total")


def build_summary_tree(files: list[FileStats]) -> ReportNode:
    totals = {scope: aggregate(scope_files(files, scope)) for scope in SCOPE_NAMES}
    section_counts = {
        key: aggregate(
            item
            for item in scope_files(files, "design")
            if design_section(item.path) == key
        )
        for key, _label, _prefix in DESIGN_SECTIONS
    }
    assert_partition(totals["source"], (totals["prod"], totals["tests"]), "source")
    assert_partition(totals["design"], section_counts.values(), "design")

    design_children = [
        ReportNode(key=key, label=label, counts=section_counts[key])
        for key, label, _prefix in DESIGN_SECTIONS
    ]
    design = ReportNode(
        key="design",
        label="design (= sections below)",
        counts=totals["design"],
        children=design_children,
    )
    markdown = ReportNode(
        key="markdown",
        label="markdown (overlaps source and design)",
        counts=totals["markdown"],
        overlaps=("source", "design"),
    )
    source = ReportNode(
        key="source",
        label="source (= prod + tests)",
        counts=totals["source"],
        children=[
            ReportNode(key="prod", label="prod", counts=totals["prod"]),
            ReportNode(key="tests", label="tests", counts=totals["tests"]),
        ],
    )
    return ReportNode(
        key="repo",
        label="repo",
        counts=totals["repo"],
        children=[source, markdown, design],
    )


def line_share(lines: int, parent_lines: int | None) -> float | None:
    if parent_lines is None:
        return 100.0 if lines > 0 else None
    if parent_lines == 0:
        return None
    return 100.0 * lines / parent_lines


def serialize_node(node: ReportNode, parent: ReportNode | None = None) -> dict[str, object]:
    share = line_share(node.counts.lines, None if parent is None else parent.counts.lines)
    return {
        "name": node.key,
        "label": node.label,
        "parent": None if parent is None else parent.key,
        **node.counts.as_dict(),
        "line_share_of_parent": None if share is None else round(share, 4),
        "overlaps": list(node.overlaps),
        "children": [serialize_node(child, node) for child in node.children],
    }


def format_bar(percentage: float | None, width: int) -> str:
    if percentage is None:
        return f"[{'-' * width}]   n/a"
    filled = min(width, int(percentage * width / 100.0 + 0.5))
    if percentage > 0 and filled == 0:
        filled = 1
    return f"[{'#' * filled}{'-' * (width - filled)}] {percentage:5.1f}%"


def table_lines(
    rows: list[dict[str, object]],
    columns: Sequence[tuple[str, str, str]],
) -> list[str]:
    widths = {
        key: max(len(label), max((len(str(row[key])) for row in rows), default=0))
        for key, label, _alignment in columns
    }

    def render(values: dict[str, object]) -> str:
        cells: list[str] = []
        for index, (key, _label, alignment) in enumerate(columns):
            value = str(values[key])
            if alignment == "right":
                cells.append(value.rjust(widths[key]))
            elif index == len(columns) - 1:
                cells.append(value)
            else:
                cells.append(value.ljust(widths[key]))
        return "  ".join(cells)

    header = {key: label for key, label, _alignment in columns}
    return [render(header), *(render(row) for row in rows)]


def summary_rows(root: ReportNode, bar_width: int) -> list[dict[str, object]]:
    rows: list[dict[str, object]] = []

    def visit(
        node: ReportNode,
        parent: ReportNode | None,
        prefix: str,
        is_last: bool,
    ) -> None:
        if parent is None:
            rendered_label = node.label
            child_prefix = ""
        else:
            connector = "`-- " if is_last else "|-- "
            rendered_label = f"{prefix}{connector}{node.label}"
            child_prefix = f"{prefix}{'    ' if is_last else '|   '}"
        share = line_share(node.counts.lines, None if parent is None else parent.counts.lines)
        rows.append(
            {
                "scope": rendered_label,
                "files": f"{node.counts.files:,}",
                "lines": f"{node.counts.lines:,}",
                "share": format_bar(share, bar_width),
            }
        )
        for index, child in enumerate(node.children):
            visit(child, node, child_prefix, index == len(node.children) - 1)

    visit(root, None, "", True)
    return rows


def render_summary(root: ReportNode, bar_width: int) -> str:
    rows = summary_rows(root, bar_width)
    lines = table_lines(
        rows,
        (
            ("scope", "scope / relationship", "left"),
            ("files", "files", "right"),
            ("lines", "lines", "right"),
            ("share", "share of parent (lines)", "left"),
        ),
    )
    lines.extend(
        (
            "",
            "Additive branches: source = prod + tests; design = its listed sections. Markdown overlaps source and design.",
            "Bars compare each row's lines with its immediate parent; repo is the 100% root.",
            "Lines exclude blank and comment-only lines.",
        )
    )
    return "\n".join(lines)


def summary_json(root: ReportNode) -> dict[str, object]:
    return {
        "line_metric": "cloc code lines; blank and comment-only lines excluded",
        "root": serialize_node(root),
    }


def language_rows(files: list[FileStats]) -> list[dict[str, object]]:
    languages: dict[str, list[FileStats]] = {}
    for item in files:
        languages.setdefault(item.language, []).append(item)
    rows = []
    for language, language_files in languages.items():
        counts = aggregate(language_files)
        rows.append({"language": language, **counts.as_dict()})
    return sorted(rows, key=lambda row: (-int(row["lines"]), str(row["language"])))


def scope_json(files: list[FileStats], scope: str, include_files: bool) -> dict[str, object]:
    selected = scope_files(files, scope)
    report: dict[str, object] = {
        "scope": scope,
        "totals": aggregate(selected).as_dict(),
        "languages": language_rows(selected),
    }
    if include_files:
        report["file_details"] = [
            {
                "path": item.path,
                "language": item.language,
                "blank": item.blank,
                "comments": item.comments,
                "lines": item.lines,
            }
            for item in selected
        ]
    return report


def render_scope(files: list[FileStats], scope: str, by_file: bool) -> str:
    selected = scope_files(files, scope)
    if by_file:
        rows = [
            {
                "path": item.path,
                "language": item.language,
                "blank": item.blank,
                "comments": item.comments,
                "lines": item.lines,
            }
            for item in selected
        ]
        columns = (
            ("path", "path", "left"),
            ("language", "language", "left"),
            ("blank", "blank", "right"),
            ("comments", "comments", "right"),
            ("lines", "lines", "right"),
        )
    else:
        rows = language_rows(selected)
        total = aggregate(selected)
        rows.append({"language": "TOTAL", **total.as_dict()})
        columns = (
            ("language", "language", "left"),
            ("files", "files", "right"),
            ("blank", "blank", "right"),
            ("comments", "comments", "right"),
            ("lines", "lines", "right"),
        )
    return "\n".join(table_lines(rows, columns))


def module_bucket(path: str) -> str:
    if "/" not in path:
        return "repo-root"
    if path.startswith("services/"):
        return "/".join(path.split("/", 2)[:2])
    return path.split("/", 1)[0]


def module_sort_key(module: str) -> tuple[int, str]:
    root_order = {
        "repo-root": 0,
        "buildSrc": 1,
        "dev-tools": 2,
        "gradle": 3,
        "protos": 4,
        "web-client": 5,
    }
    if module.startswith("services/"):
        return 10, module
    return root_order.get(module, 20), module


def module_report(files: list[FileStats]) -> dict[str, object]:
    selected = scope_files(files, "source")
    buckets: dict[str, list[FileStats]] = {}
    for item in selected:
        buckets.setdefault(module_bucket(item.path), []).append(item)

    rows: list[dict[str, object]] = []
    for module in sorted(buckets, key=module_sort_key):
        module_files = buckets[module]
        prod_files = [item for item in module_files if source_classification(item.path)[0] == "prod"]
        test_files = [item for item in module_files if source_classification(item.path)[0] == "tests"]
        total = aggregate(module_files)
        prod = aggregate(prod_files)
        tests = aggregate(test_files)
        rows.append(
            {
                "module": module,
                "files": total.files,
                "lines": total.lines,
                "prod_files": prod.files,
                "prod_lines": prod.lines,
                "test_files": tests.files,
                "test_lines": tests.lines,
            }
        )

    total = aggregate(selected)
    prod_total = aggregate(scope_files(files, "prod"))
    test_total = aggregate(scope_files(files, "tests"))
    summary = {
        "module": "TOTAL",
        "files": total.files,
        "lines": total.lines,
        "prod_files": prod_total.files,
        "prod_lines": prod_total.lines,
        "test_files": test_total.files,
        "test_lines": test_total.lines,
    }
    return {"modules": rows, "total": summary}


def render_modules(report: dict[str, object]) -> str:
    rows = [*report["modules"], report["total"]]
    columns = (
        ("module", "module", "left"),
        ("files", "files", "right"),
        ("lines", "lines", "right"),
        ("prod_files", "prod files", "right"),
        ("prod_lines", "prod lines", "right"),
        ("test_files", "test files", "right"),
        ("test_lines", "test lines", "right"),
    )
    return "\n".join(table_lines(rows, columns))


def classify_inventory(inventory: Iterable[str]) -> list[dict[str, str]]:
    rows = []
    for path in inventory:
        classification = source_classification(path)
        if classification is not None:
            bucket, rule = classification
            rows.append({"bucket": bucket, "rule": rule, "path": path})
    return rows


def positive_bar_width(value: str) -> int:
    width = int(value)
    if not 4 <= width <= 40:
        raise argparse.ArgumentTypeError("bar width must be between 4 and 40")
    return width


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Count FireMUD's Git-tracked footprint with repository-aware scopes.",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""examples:
  python3 dev-tools/maintenance/cloc-report.py
  python3 dev-tools/maintenance/cloc-report.py summary --bar-width 20
  python3 dev-tools/maintenance/cloc-report.py scope tests --json
  python3 dev-tools/maintenance/cloc-report.py modules
  python3 dev-tools/maintenance/cloc-report.py diff develop...HEAD
  python3 dev-tools/maintenance/cloc-report.py classify
""",
    )
    commands = parser.add_subparsers(dest="command")

    summary = commands.add_parser("summary", help="hierarchical totals and parent-relative bars")
    summary.add_argument("--json", action="store_true", help="emit structured JSON")
    summary.add_argument("--bar-width", type=positive_bar_width, default=DEFAULT_BAR_WIDTH)

    scope = commands.add_parser("scope", help="language or file detail for one scope")
    scope.add_argument("scope", choices=SCOPE_NAMES)
    scope.add_argument("--by-file", action="store_true", help="show per-file rows")
    scope.add_argument("--json", action="store_true", help="emit structured JSON")

    modules = commands.add_parser("modules", help="source, production, and test totals by module")
    modules.add_argument("--json", action="store_true", help="emit structured JSON")

    diff = commands.add_parser("diff", help="report the current-checkout footprint for a Git range")
    diff.add_argument("git_range", help="Git range such as develop...HEAD")
    diff.add_argument("--modules", action="store_true", help="show module totals instead of summary")
    diff.add_argument("--json", action="store_true", help="emit structured JSON")
    diff.add_argument("--bar-width", type=positive_bar_width, default=DEFAULT_BAR_WIDTH)

    classify = commands.add_parser("classify", help="show source/test classification for tracked files")
    classify.add_argument("--json", action="store_true", help="emit structured JSON")
    return parser


def normalized_argv(argv: Sequence[str]) -> list[str]:
    values = list(argv)
    if not values:
        return ["summary"]
    if values[0].startswith("-") and values[0] not in {"-h", "--help"}:
        return ["summary", *values]
    return values


def print_json(payload: object) -> None:
    print(json.dumps(payload, indent=2))


def emit_summary(files: list[FileStats], bar_width: int, as_json: bool) -> None:
    report = build_summary_tree(files)
    print_json(summary_json(report)) if as_json else print(render_summary(report, bar_width))


def emit_modules(files: list[FileStats], as_json: bool) -> None:
    report = module_report(files)
    print_json(report) if as_json else print(render_modules(report))


def main(argv: Sequence[str] = sys.argv[1:]) -> int:
    parser = build_parser()
    args = parser.parse_args(normalized_argv(argv))
    if args.command is None:
        parser.print_help()
        return 0

    try:
        root = repository_root()
        if args.command == "classify":
            rows = classify_inventory(tracked_inventory(root))
            if args.json:
                print_json({"files": rows})
            else:
                print("bucket\trule\tpath")
                for row in rows:
                    print(f"{row['bucket']}\t{row['rule']}\t{row['path']}")
            return 0

        if args.command == "diff":
            inventory, omitted = diff_inventory(root, args.git_range)
            if omitted:
                print(
                    f"Note: omitted {omitted} tracked path(s) that are deleted or missing in {args.git_range}.",
                    file=sys.stderr,
                )
            label = f"changed files in {args.git_range}"
        else:
            inventory = tracked_inventory(root)
            label = "Git-tracked files"

        print(f"Scanning {label} with cloc...", file=sys.stderr)
        files = scan_cloc(root, inventory)

        if args.command == "summary":
            emit_summary(files, args.bar_width, args.json)
        elif args.command == "scope":
            if args.json:
                print_json(scope_json(files, args.scope, args.by_file))
            else:
                print(render_scope(files, args.scope, args.by_file))
        elif args.command == "modules":
            emit_modules(files, args.json)
        elif args.command == "diff":
            if args.modules:
                emit_modules(files, args.json)
            else:
                emit_summary(files, args.bar_width, args.json)
        else:
            raise ReportError(f"Unhandled command: {args.command}")
        return 0
    except ReportError as error:
        print(f"cloc-report: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
