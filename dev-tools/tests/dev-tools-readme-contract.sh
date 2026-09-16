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


def github_anchors(markdown: Path) -> set[str]:
    anchors: set[str] = set()
    occurrences: dict[str, int] = {}
    heading_pattern = re.compile(r"^ {0,3}#{1,6}\s+(.+?)\s*$")
    for line in markdown.read_text(encoding="utf-8").splitlines():
        match = heading_pattern.match(line)
        if match is None:
            continue
        heading = re.sub(r"\s+#+\s*$", "", match.group(1)).strip().lower()
        base = re.sub(r"[^\w\s-]", "", heading, flags=re.UNICODE)
        base = re.sub(r"[\s-]+", "-", base).strip("-")
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


def link_target(readme: Path, target: str) -> None:
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
    for target in re.findall(r"\[[^]]+\]\(([^)]+)\)", text):
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
        tracked_file(root / token, readme)
    for token in re.findall(r"`((?:validation|maintenance|tests)/[^`\s]+)`", text):
        if not token.endswith("/"):
            tracked_file(readme.parent / token, readme)


with tempfile.TemporaryDirectory() as fixture_dir:
    fixture_root = Path(fixture_dir)
    fixture_readme = fixture_root / "README.md"
    fixture_target = fixture_root / "target.md"
    fixture_readme.write_text("# Fixture\n\nSee [target](target.md#details).\n", encoding="utf-8")
    fixture_target.write_text("# Target\n\n## Details\n", encoding="utf-8")

    original_tracked_file = tracked_file

    def fixture_tracked_file(path: Path, source: Path) -> None:
        if not path.is_file():
            raise SystemExit(f"{source}: fixture path does not resolve to a file: {path}")

    tracked_file = fixture_tracked_file
    link_target(fixture_readme, "target.md#details")
    link_target(fixture_readme, "#fixture")
    try:
        link_target(fixture_readme, "target.md#missing")
    except SystemExit as exc:
        if "documented link anchor does not resolve" not in str(exc):
            raise
    else:
        raise SystemExit("missing Markdown anchor fixture did not fail")
    tracked_file = original_tracked_file

print("dev-tools README path and link contract checks passed")
PY
