#!/usr/bin/env python3
"""Repository-aware cloc reports for FireMUD.

The tool scans a Git inventory once with cloc's by-file JSON output, then builds
all repository, source, test, documentation, design, and module views from that
single result. Untracked and ignored files never enter the inventory; tracked
generated files may be counted.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import shutil
import subprocess
import sys
import tempfile
from collections.abc import Iterable, Sequence
from contextlib import contextmanager
from dataclasses import dataclass, field
from pathlib import Path, PurePosixPath
from urllib.parse import urlparse

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
PR_COMMAND_DESCRIPTION = "compare merge-base and immutable head snapshots for a GitHub PR"
PR_METADATA_FIELDS = "baseRefName,baseRefOid,headRefName,headRefOid"
PR_UPDATE_FIELDS = "baseRefOid,headRefOid,body"
PR_REPORT_START = "<!-- firemud:cloc-report:start -->"
PR_REPORT_END = "<!-- firemud:cloc-report:end -->"


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


@dataclass(frozen=True)
class PullRequestMetadata:
    number: int
    repository: str
    base_ref: str
    base_oid: str
    head_ref: str
    head_oid: str


@dataclass(frozen=True)
class ImpactRow:
    name: str
    label: str
    parent: str | None
    depth: int
    base: Counts
    head: Counts
    delta: Counts
    change_percent: float | None


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


def command_text(result: subprocess.CompletedProcess[bytes], label: str) -> str:
    value = result.stdout.decode(errors="replace").strip()
    if not value:
        raise ReportError(f"{label} returned no output")
    return value


def confined_path(root: Path, path: str) -> Path:
    root_resolved = root.resolve()
    candidate = root / path
    try:
        resolved = candidate.resolve(strict=False)
    except OSError as error:
        raise ReportError(f"could not resolve tracked path {path!r}: {error}") from error
    if resolved != root_resolved and root_resolved not in resolved.parents:
        raise ReportError(f"tracked path escapes the snapshot through a symlink: {path!r}")
    return candidate


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


def normalize_repository(value: str) -> str:
    raw = value.strip()
    if raw.startswith("git@") and ":" in raw:
        host, path = raw[4:].split(":", 1)
    elif "://" in raw:
        parsed = urlparse(raw)
        host = parsed.hostname or ""
        path = parsed.path.lstrip("/")
    else:
        host = "github.com"
        path = raw
    path = path.removesuffix(".git").strip("/")
    parts = path.split("/")
    if host.lower() != "github.com" or len(parts) != 2 or not all(parts):
        raise ReportError(f"expected a GitHub repository in owner/name form, got {value!r}")
    return "/".join(part.lower() for part in parts)


def valid_object_id(value: object, label: str) -> str:
    if not isinstance(value, str) or len(value) != 40:
        raise ReportError(f"PR metadata has an invalid {label} commit SHA")
    lowered = value.lower()
    if any(character not in "0123456789abcdef" for character in lowered):
        raise ReportError(f"PR metadata has an invalid {label} commit SHA")
    return lowered


def resolve_pull_request(root: Path, number: int, requested_repository: str | None) -> PullRequestMetadata:
    origin_url = command_text(
        run_command(("git", "remote", "get-url", "origin"), root),
        "origin remote",
    )
    origin_repository = normalize_repository(origin_url)
    repository = normalize_repository(requested_repository) if requested_repository else origin_repository
    if repository != origin_repository:
        raise ReportError(f"requested repository {repository!r} does not match origin repository {origin_repository!r}")

    require_tool("gh")
    result = run_command(
        (
            "gh",
            "pr",
            "view",
            str(number),
            "--repo",
            repository,
            "--json",
            PR_METADATA_FIELDS,
        ),
        root,
    )
    try:
        payload = json.loads(command_text(result, "GitHub PR metadata"))
    except json.JSONDecodeError as error:
        raise ReportError(f"GitHub PR metadata was not valid JSON: {error}") from error
    if not isinstance(payload, dict):
        raise ReportError("GitHub PR metadata was not an object")

    base_ref = payload.get("baseRefName")
    head_ref = payload.get("headRefName")
    if not isinstance(base_ref, str) or not base_ref:
        raise ReportError("GitHub PR metadata has no base branch")
    if not isinstance(head_ref, str) or not head_ref:
        raise ReportError("GitHub PR metadata has no head branch")
    return PullRequestMetadata(
        number=number,
        repository=repository,
        base_ref=base_ref,
        base_oid=valid_object_id(payload.get("baseRefOid"), "base"),
        head_ref=head_ref,
        head_oid=valid_object_id(payload.get("headRefOid"), "head"),
    )


def commit_object_exists(root: Path, object_id: str) -> bool:
    try:
        run_command(("git", "cat-file", "-e", f"{object_id}^{{commit}}"), root)
    except ReportError:
        return False
    return True


def ensure_commit_object(root: Path, remote: str, object_id: str, refspec: str, label: str) -> None:
    if not commit_object_exists(root, object_id):
        run_command(
            (
                "git",
                "-c",
                "core.hooksPath=/dev/null",
                "fetch",
                "--no-tags",
                remote,
                refspec,
            ),
            root,
        )
    if not commit_object_exists(root, object_id):
        raise ReportError(f"could not obtain the exact PR {label} commit {object_id}")


def pull_request_merge_base(root: Path, metadata: PullRequestMetadata) -> str:
    ensure_commit_object(
        root,
        "origin",
        metadata.base_oid,
        f"refs/heads/{metadata.base_ref}",
        "base",
    )
    ensure_commit_object(
        root,
        "origin",
        metadata.head_oid,
        f"refs/pull/{metadata.number}/head",
        "head",
    )
    result = run_command(("git", "merge-base", metadata.base_oid, metadata.head_oid), root)
    return valid_object_id(command_text(result, "Git merge-base"), "merge-base")


@contextmanager
def snapshot_worktree(root: Path, revision: str) -> Iterable[Path]:
    temp_root = Path(tempfile.mkdtemp(prefix="firemud-cloc-snapshot-"))
    snapshot = temp_root / "worktree"
    added = False
    original_error: BaseException | None = None
    try:
        run_command(
            (
                "git",
                "-c",
                "core.hooksPath=/dev/null",
                "worktree",
                "add",
                "--detach",
                str(snapshot),
                revision,
            ),
            root,
        )
        added = True
        yield snapshot
    except BaseException as error:
        original_error = error
        raise
    finally:
        cleanup_errors: list[Exception] = []
        if added:
            try:
                run_command(
                    (
                        "git",
                        "-c",
                        "core.hooksPath=/dev/null",
                        "worktree",
                        "remove",
                        "--force",
                        str(snapshot),
                    ),
                    root,
                )
            except (OSError, ReportError) as error:
                cleanup_errors.append(error)
        try:
            shutil.rmtree(temp_root, ignore_errors=False)
        except OSError as error:
            cleanup_errors.append(error)
        if added:
            try:
                run_command(
                    (
                        "git",
                        "-c",
                        "core.hooksPath=/dev/null",
                        "worktree",
                        "prune",
                        "--expire",
                        "now",
                    ),
                    root,
                )
            except (OSError, ReportError):
                pass

        if original_error is None and cleanup_errors:
            if len(cleanup_errors) == 1:
                raise cleanup_errors[0]
            raise cleanup_errors[-1] from cleanup_errors[0]


def scan_cloc(root: Path, inventory: Iterable[str]) -> list[FileStats]:
    require_tool("cloc")
    paths = list(inventory)
    if not paths:
        return []

    for path in paths:
        if "\n" in path:
            raise ReportError(f"cloc list files cannot represent a newline in path: {path!r}")
        confined_path(root, path)

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
        raw_payload = result.stdout.strip()
        payload = {} if not raw_payload else json.loads(raw_payload)
    except json.JSONDecodeError as error:
        raise ReportError(f"cloc returned invalid JSON: {error}") from error
    finally:
        if list_path is not None:
            list_path.unlink(missing_ok=True)

    files: list[FileStats] = []
    for raw_path, stats in payload.items():
        if raw_path in {"header", "SUM"}:
            continue
        path = raw_path.removeprefix("./")
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
    return (len(parts) == 3 and parts[0] == "services" and parts[2] == "README.md") or (
        len(parts) >= 4 and parts[0] == "services" and parts[2] == "design"
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
        key: aggregate(item for item in scope_files(files, "design") if design_section(item.path) == key)
        for key, _label, _prefix in DESIGN_SECTIONS
    }
    assert_partition(totals["source"], (totals["prod"], totals["tests"]), "source")
    assert_partition(totals["design"], section_counts.values(), "design")

    design_children = [
        ReportNode(key=key, label=label, counts=section_counts[key]) for key, label, _prefix in DESIGN_SECTIONS
    ]
    design = ReportNode(
        key="design",
        label="design (= sections below)",
        counts=totals["design"],
        children=design_children,
    )
    markdown = ReportNode(
        key="markdown",
        label="markdown (overlaps design)",
        counts=totals["markdown"],
        overlaps=("design",),
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
        key: max(len(label), max((len(str(row[key])) for row in rows), default=0)) for key, label, _alignment in columns
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
            "Additive branches: source = prod + tests; design = its listed sections. Markdown is excluded from source and overlaps design where paths match.",
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


def summary_for_root(root: Path) -> ReportNode:
    return build_summary_tree(scan_cloc(root, tracked_inventory(root)))


def classifier_digest() -> str:
    classifier = Path(__file__).resolve()
    try:
        return hashlib.sha256(classifier.read_bytes()).hexdigest()
    except OSError as error:
        raise ReportError(f"could not read the invoking classifier: {error}") from error


def counts_delta(base: Counts, head: Counts) -> Counts:
    return Counts(
        files=head.files - base.files,
        blank=head.blank - base.blank,
        comments=head.comments - base.comments,
        lines=head.lines - base.lines,
    )


def change_percent(base_lines: int, head_lines: int) -> float | None:
    if base_lines == 0:
        return 0.0 if head_lines == 0 else None
    return 100.0 * (head_lines - base_lines) / base_lines


def impact_rows(base: ReportNode, head: ReportNode) -> list[ImpactRow]:
    rows: list[ImpactRow] = []

    def visit(
        base_node: ReportNode,
        head_node: ReportNode,
        depth: int,
        parent: str | None,
    ) -> None:
        if base_node.key != head_node.key:
            raise ReportError(f"base/head summary categories differ at {base_node.key!r}/{head_node.key!r}")
        rows.append(
            ImpactRow(
                name=head_node.key,
                label=head_node.label,
                parent=parent,
                depth=depth,
                base=base_node.counts,
                head=head_node.counts,
                delta=counts_delta(base_node.counts, head_node.counts),
                change_percent=change_percent(base_node.counts.lines, head_node.counts.lines),
            )
        )
        if len(base_node.children) != len(head_node.children):
            raise ReportError(f"base/head summary children differ at {head_node.key!r}")
        for base_child, head_child in zip(base_node.children, head_node.children):
            visit(base_child, head_child, depth + 1, head_node.key)

    visit(base, head, 0, None)
    return rows


def impact_row_json(row: ImpactRow) -> dict[str, object]:
    return {
        "name": row.name,
        "label": row.label,
        "parent": row.parent,
        "depth": row.depth,
        "base": row.base.as_dict(),
        "head": row.head.as_dict(),
        "delta": row.delta.as_dict(),
        "change_percent": None if row.change_percent is None else round(row.change_percent, 4),
    }


def build_pr_report(root: Path, number: int, requested_repository: str | None) -> dict[str, object]:
    metadata = resolve_pull_request(root, number, requested_repository)
    merge_base = pull_request_merge_base(root, metadata)
    classifier_sha256 = classifier_digest()
    print(f"Scanning merge-base {merge_base} with cloc...", file=sys.stderr)
    with snapshot_worktree(root, merge_base) as base_root:
        base_summary = summary_for_root(base_root)
    print(f"Scanning PR head {metadata.head_oid} with cloc...", file=sys.stderr)
    with snapshot_worktree(root, metadata.head_oid) as head_root:
        head_summary = summary_for_root(head_root)
    rows = impact_rows(base_summary, head_summary)
    return {
        "line_metric": "cloc code lines; blank and comment-only lines excluded",
        "classifier_sha256": classifier_sha256,
        "repository": metadata.repository,
        "pull_request": metadata.number,
        "base": {
            "ref": metadata.base_ref,
            "oid": metadata.base_oid,
            "merge_base": merge_base,
            "summary": summary_json(base_summary),
        },
        "head": {
            "ref": metadata.head_ref,
            "oid": metadata.head_oid,
            "summary": summary_json(head_summary),
        },
        "sections": [impact_row_json(row) for row in rows],
    }


def format_count(value: int, signed: bool = False) -> str:
    if signed:
        return f"{value:+,}" if value else "0"
    return f"{value:,}"


def format_change(percent: object, delta_lines: int, head_lines: int) -> str:
    if percent is None:
        return "new" if head_lines > 0 else "n/a"
    numeric = float(percent)
    if numeric == 0.0:
        if delta_lines > 0:
            return "+<0.1%"
        if delta_lines < 0:
            return "-<0.1%"
        return "0.0%"
    if abs(numeric) < 0.05:
        return "+<0.1%" if numeric > 0 else "-<0.1%"
    return f"{numeric:+.1f}%"


def render_pr_report(report: dict[str, object]) -> str:
    base = report["base"]
    head = report["head"]
    if not isinstance(base, dict) or not isinstance(head, dict):
        raise ReportError("PR report snapshots were malformed")
    rows = report["sections"]
    if not isinstance(rows, list):
        raise ReportError("PR report sections were malformed")

    output = [
        PR_REPORT_START,
        "### FireMUD LOC impact",
        "",
        f"Compared `{str(base['merge_base'])[:12]}` → `{str(head['oid'])[:12]}` (PR merge-base → head).",
        "",
        "| Section | Base LOC | Head LOC | Δ LOC | Change |",
        "|---|---:|---:|---:|---:|",
    ]
    for raw_row in rows:
        if not isinstance(raw_row, dict):
            raise ReportError("PR report contained a malformed section")
        base_counts = raw_row.get("base")
        head_counts = raw_row.get("head")
        delta_counts = raw_row.get("delta")
        if not all(isinstance(value, dict) for value in (base_counts, head_counts, delta_counts)):
            raise ReportError("PR report contained malformed section counts")
        name = str(raw_row.get("name", ""))
        depth = int(raw_row.get("depth", 0))
        section_name = "Production" if name == "prod" else name.replace("_", " ").capitalize()
        label = "Overall" if name == "repo" else f"{'↳ ' if depth >= 2 else ''}{section_name}"
        percent = raw_row.get("change_percent")
        change = format_change(percent, int(delta_counts["lines"]), int(head_counts["lines"]))
        output.append(
            f"| {label} | {format_count(int(base_counts['lines']))} | "
            f"{format_count(int(head_counts['lines']))} | {format_count(int(delta_counts['lines']), signed=True)} | {change} |"
        )
    output.extend(
        (
            "",
            "Rows are hierarchical and overlapping views; do not add them together.",
            "LOC means cloc code lines; blank and comment-only lines are excluded.",
            PR_REPORT_END,
        )
    )
    return "\n".join(output)


def replace_pr_report_block(body: str, snippet: str) -> str:
    start_count = body.count(PR_REPORT_START)
    end_count = body.count(PR_REPORT_END)
    if start_count == 0 and end_count == 0:
        if not body:
            return snippet
        separator = "" if body.endswith("\n\n") else "\n" if body.endswith("\n") else "\n\n"
        return f"{body}{separator}{snippet}"
    if start_count != 1 or end_count != 1:
        raise ReportError("PR body must contain exactly one complete LOC marker block")
    start = body.find(PR_REPORT_START)
    end = body.find(PR_REPORT_END)
    if end < start:
        raise ReportError("PR body LOC markers are reversed")
    end += len(PR_REPORT_END)
    return f"{body[:start]}{snippet}{body[end:]}"


def pull_request_update_state(root: Path, number: int, repository: str) -> tuple[str, str, str]:
    require_tool("gh")
    result = run_command(
        (
            "gh",
            "pr",
            "view",
            str(number),
            "--repo",
            repository,
            "--json",
            PR_UPDATE_FIELDS,
        ),
        root,
    )
    try:
        payload = json.loads(command_text(result, "GitHub PR update metadata"))
    except json.JSONDecodeError as error:
        raise ReportError(f"GitHub PR update metadata was not valid JSON: {error}") from error
    if not isinstance(payload, dict):
        raise ReportError("GitHub PR update metadata was not an object")
    body = payload.get("body")
    if body is None:
        body = ""
    if not isinstance(body, str):
        raise ReportError("GitHub PR body was not text")
    return body, valid_object_id(payload.get("baseRefOid"), "base"), valid_object_id(payload.get("headRefOid"), "head")


def update_pull_request_body(root: Path, number: int, report: dict[str, object]) -> bool:
    repository = report.get("repository")
    base = report.get("base")
    head = report.get("head")
    if not isinstance(repository, str) or not isinstance(base, dict) or not isinstance(head, dict):
        raise ReportError("PR report metadata was malformed before body update")
    counted_base = base.get("oid")
    counted_head = head.get("oid")
    if not isinstance(counted_base, str) or not isinstance(counted_head, str):
        raise ReportError("PR report revisions were malformed before body update")

    body, current_base, current_head = pull_request_update_state(root, number, repository)
    if current_base != counted_base or current_head != counted_head:
        raise ReportError("PR base or head changed while the LOC report was being generated; refusing body update")

    updated_body = replace_pr_report_block(body, render_pr_report(report))
    if updated_body == body:
        return False
    body_path: Path | None = None
    try:
        with tempfile.NamedTemporaryFile("w", encoding="utf-8", delete=False) as body_file:
            body_path = Path(body_file.name)
            body_file.write(updated_body)
        run_command(
            (
                "gh",
                "pr",
                "edit",
                str(number),
                "--repo",
                repository,
                "--body-file",
                str(body_path),
            ),
            root,
        )
    finally:
        if body_path is not None:
            body_path.unlink(missing_ok=True)
    return True


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
        parts = path.split("/", 2)
        return "/".join(parts[:2]) if len(parts) >= 3 else "services"
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
  python3 dev-tools/maintenance/cloc-report.py pr 123 --repo owner/name
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

    pr = commands.add_parser(
        "pr",
        help=PR_COMMAND_DESCRIPTION,
        description=PR_COMMAND_DESCRIPTION,
    )
    pr.add_argument("number", type=int, help="GitHub pull request number")
    pr.add_argument("--repo", help="GitHub repository in owner/name form (defaults to origin)")
    pr.add_argument("--json", action="store_true", help="emit structured JSON")
    pr.add_argument("--update-pr", action="store_true", help="replace the marked LOC section in the PR body")

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


def main(argv: Sequence[str] | None = None) -> int:
    if argv is None:
        argv = sys.argv[1:]
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

        if args.command == "pr":
            if args.number <= 0:
                raise ReportError("pull request number must be positive")
            report = build_pr_report(root, args.number, args.repo)
            if args.update_pr:
                updated = update_pull_request_body(root, args.number, report)
                status = "Updated the marked LOC section" if updated else "The marked LOC section is already up to date"
                print(f"{status} in PR #{args.number}.", file=sys.stderr)
            print_json(report) if args.json else print(render_pr_report(report))
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
