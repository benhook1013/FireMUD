#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
ROOT_PATH="${ROOT_PATH:-$ROOT_DIR}" python3 - <<'PY'
import os
import re
import subprocess
import tempfile
from pathlib import Path
from urllib.parse import unquote

root = Path(os.environ["ROOT_PATH"]).resolve()

readmes = (root / "dev-tools/README.md", root / "dev-tools/hosted/README.md")


def tracked_file(path: Path, source: Path) -> None:
    try:
        relative = path.relative_to(root)
    except ValueError as exc:
        raise SystemExit(f"{source}: documented path escapes the repository: {path}") from exc
    if not path.is_file():
        raise SystemExit(f"{source}: documented path does not resolve to a file: {relative}")
    result = subprocess.run(
        ["git", "ls-files", "--error-unmatch", "--", relative.as_posix()],
        cwd=root,
        check=False,
        capture_output=True,
        text=True,
    )
    if result.returncode != 0:
        raise SystemExit(f"{source}: documented path is not tracked: {relative}")


def markdown_outside_fences(text: str) -> list[str]:
    active_lines: list[str] = []
    fence_pattern = re.compile(r"^ {0,3}(`{3,}|~{3,})(.*)$")
    fence_marker: tuple[str, int] | None = None
    for line in text.splitlines():
        fence_match = fence_pattern.match(line)
        if fence_marker is not None:
            if (
                fence_match is not None
                and fence_match.group(1)[0] == fence_marker[0]
                and len(fence_match.group(1)) >= fence_marker[1]
                and not fence_match.group(2).strip()
            ):
                fence_marker = None
            continue
        if fence_match is not None:
            fence_marker = (fence_match.group(1)[0], len(fence_match.group(1)))
            continue
        active_lines.append(line)
    return active_lines


def github_anchors(markdown: Path) -> set[str]:
    anchors: set[str] = set()
    occurrences: dict[str, int] = {}
    heading_pattern = re.compile(r"^ {0,3}#{1,6}\s+(.+?)\s*$")
    for line in markdown_outside_fences(markdown.read_text(encoding="utf-8")):
        match = heading_pattern.match(line)
        if match is None:
            continue
        heading = re.sub(r"\s+#+\s*$", "", match.group(1)).strip().lower()
        base = re.sub(r"[^\w\s-]", "", heading, flags=re.UNICODE)
        base = base.replace(" ", "-")
        base = re.sub(r"\s", "", base)
        occurrence = occurrences.get(base, 0)
        occurrences[base] = occurrence + 1
        anchors.add(base if occurrence == 0 else f"{base}-{occurrence}")
    return anchors


def check_fragment(readme: Path, markdown: Path, fragment: str) -> None:
    anchor = unquote(fragment)
    if anchor not in github_anchors(markdown):
        raise SystemExit(
            f"{readme}: documented link anchor does not resolve: {markdown}#{fragment}"
        )


LINK_TITLE = r'''(?:"[^"]*"|'[^']*'|\([^)]*\))'''
MARKDOWN_LINK_PATTERN = re.compile(
    rf"\[[^]]+\]\(((?:<[^>]*>|[^)\s]+)(?:\s+{LINK_TITLE})?)\)"
)


def markdown_destination(target: str) -> str:
    target = target.strip()
    angle_match = re.fullmatch(rf"<([^>]*)>(?:\s+{LINK_TITLE})?", target)
    if angle_match is not None:
        return angle_match.group(1)
    title_match = re.search(rf"\s+{LINK_TITLE}\s*$", target)
    return target[: title_match.start()] if title_match is not None else target


def link_target(readme: Path, target: str) -> None:
    target = markdown_destination(target)
    path_target, separator, fragment = target.partition("#")
    path_target = path_target.split("?", 1)[0]
    if not path_target and not separator:
        return
    if path_target.startswith(("http://", "https://", "mailto:")):
        return
    path = readme if not path_target else (readme.parent / path_target).resolve()
    tracked_file(path, readme)
    if separator and path.suffix.lower() in {".md", ".markdown"}:
        check_fragment(readme, path, fragment)


def canonical_section(readme: Path, text: str) -> str:
    heading = "## Canonical root entrypoints"
    end_heading = "## Folder map"
    if heading not in text:
        raise SystemExit(f"{readme}: missing the '{heading}' section")
    if end_heading not in text:
        raise SystemExit(f"{readme}: missing the '{end_heading}' heading after '{heading}'")
    return text.split(heading, 1)[1].split(end_heading, 1)[0]


for readme in readmes:
    text = readme.read_text(encoding="utf-8")
    for target in MARKDOWN_LINK_PATTERN.findall("\n".join(markdown_outside_fences(text))):
        link_target(readme, target)

    if readme == root / "dev-tools/README.md":
        try:
            canonical_section(readme, "## Folder map\n")
        except SystemExit as exc:
            expected = f"{readme}: missing the '## Canonical root entrypoints' section"
            if str(exc) != expected:
                raise
        else:
            raise SystemExit(f"{readme}: missing-heading regression did not fail")
        canonical = canonical_section(readme, text)
        for line in canonical.splitlines():
            if not line.startswith("- "):
                continue
            match = re.search(r"`([^`]+)`", line)
            if match is None:
                raise SystemExit(f"{readme}: canonical entrypoint has no documented path: {line}")
            token = match.group(1)
            path = root / token if token.startswith("dev-tools/") else root / "dev-tools" / token
            tracked_file(path, readme)

    for token in re.findall(r"`(dev-tools/[^`\s]+)`", text):
        if not token.endswith("/"):
            tracked_file(root / token, readme)
    for token in re.findall(r"`((?:validation|maintenance|tests)/[^`\s]+)`", text):
        if not token.endswith("/"):
            tracked_file(readme.parent / token, readme)

    if readme == root / "dev-tools/README.md":
        workflow_versions = root / "config/workflow-tool-versions.env"
        gh_version = None
        for line in workflow_versions.read_text(encoding="utf-8").splitlines():
            if line.startswith("GH_VERSION="):
                gh_version = line.partition("=")[2].strip()
                break
        if not gh_version:
            raise SystemExit(f"{workflow_versions}: GH_VERSION is missing")
        version_parts = gh_version.split(".")
        if len(version_parts) != 3 or any(not part.isdigit() for part in version_parts):
            raise SystemExit(f"{workflow_versions}: GH_VERSION is not numeric")
        if tuple(int(part) for part in version_parts) < (2, 63, 0):
            raise SystemExit(f"{workflow_versions}: GH_VERSION must be >= 2.63.0")
        prerequisite = (
            "The `pr-review`, `report-worktree-pr-topology.sh`, and "
            "`maintenance/cloc-report.py pr` entrypoints "
            "require GitHub CLI `gh` >= 2.63.0 "
            "because they request the `baseRefOid` field; the repository workflow pin is "
            f"`GH_VERSION={gh_version}`."
        )
        if prerequisite not in text:
            raise SystemExit(
                f"{readme}: reporter GitHub CLI/baseRefOid prerequisite note is missing"
            )


with tempfile.TemporaryDirectory() as fixture_dir:
    fixture_root = Path(fixture_dir)
    fixture_readme = fixture_root / "README.md"
    fixture_target = fixture_root / "target.md"
    fixture_readme.write_text(
        "# Fixture\n\n"
        "```markdown\n"
        "See [fake backtick](missing-backtick.md).\n"
        "```\n\n"
        "See [after backtick fence](target.md#after-backtick-fence).\n\n"
        "````markdown\n"
        "See [fake long backtick](missing-long-backtick.md).\n"
        "````\n\n"
        "~~~markdown\n"
        "See [fake tilde](missing-tilde.md).\n"
        "~~~\n\n"
        "See [after tilde fence](target.md#after-tilde-fence).\n\n"
        "~~~~markdown\n"
        "See [fake long tilde](missing-long-tilde.md).\n"
        "~~~~\n\n"
        "See [plain](target.md#details), [double](target.md \"Target\"), "
        "[single](target.md 'Target'), [parenthesized](target.md (Target)), "
        "[angle](<target.md#details> \"Details\"), "
        "[repeated spaces](target.md#double--spaces), "
        "and [edge hyphens](target.md#--edge--).\n",
        encoding="utf-8",
    )
    fixture_target.write_text(
        "# Target\n\n## Details\n\n## Double  Spaces\n\n## - Edge -\n\n"
        "```markdown\n# Fake Details\n```\n\n"
        "## After Backtick Fence\n\n"
        "~~~text\n# Fake Tilde\n~~~\n\n"
        "## After Tilde Fence\n",
        encoding="utf-8",
    )

    original_tracked_file = tracked_file

    def fixture_tracked_file(path: Path, source: Path) -> None:
        if not path.is_file():
            raise SystemExit(f"{source}: fixture path does not resolve to a file: {path}")

    tracked_file = fixture_tracked_file
    extracted_targets = MARKDOWN_LINK_PATTERN.findall(
        "\n".join(markdown_outside_fences(fixture_readme.read_text(encoding="utf-8")))
    )
    for target in extracted_targets:
        link_target(fixture_readme, target)
    if len(extracted_targets) != 9:
        raise SystemExit(f"fixture Markdown link extraction found {len(extracted_targets)} targets")
    for target in (
        "missing-backtick.md",
        "missing-long-backtick.md",
        "missing-tilde.md",
        "missing-long-tilde.md",
    ):
        if target in extracted_targets:
            raise SystemExit(f"fenced Markdown link was incorrectly extracted: {target}")
    link_target(fixture_readme, "target.md#details")
    link_target(fixture_readme, 'target.md "Target"')
    link_target(fixture_readme, "target.md 'Target'")
    link_target(fixture_readme, "target.md (Target)")
    link_target(fixture_readme, '<target.md#details> "Details"')
    link_target(fixture_readme, "#fixture")
    link_target(fixture_readme, "target.md#double--spaces")
    link_target(fixture_readme, "target.md#--edge--")
    link_target(fixture_readme, "target.md#after-backtick-fence")
    link_target(fixture_readme, "target.md#after-tilde-fence")
    try:
        link_target(fixture_readme, "target.md#missing")
    except SystemExit as exc:
        if "documented link anchor does not resolve" not in str(exc):
            raise
    else:
        raise SystemExit("missing Markdown anchor fixture did not fail")
    for target in (
        "target.md#double-spaces",
        "target.md#edge",
        "target.md#fake-details",
        "target.md#fake-tilde",
    ):
        try:
            link_target(fixture_readme, target)
        except SystemExit as exc:
            if "documented link anchor does not resolve" not in str(exc):
                raise
        else:
            raise SystemExit(f"incorrect Markdown anchor fixture did not fail: {target}")
    for target in (
        'target.md#missing "Missing"',
        "target.md#missing 'Missing'",
        "target.md#missing (Missing)",
        '<target.md#missing> "Missing"',
    ):
        try:
            link_target(fixture_readme, target)
        except SystemExit as exc:
            if "documented link anchor does not resolve" not in str(exc):
                raise
        else:
            raise SystemExit(f"missing Markdown anchor fixture did not fail: {target}")
    tracked_file = original_tracked_file

print("dev-tools README path and link contract checks passed")
PY
