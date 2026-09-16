#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
ROOT_PATH="${ROOT_PATH:-$ROOT_DIR}" python3 - <<'PY'
import os
import re
import subprocess
from pathlib import Path

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


def link_target(readme: Path, target: str) -> None:
    target = target.split("#", 1)[0].split("?", 1)[0]
    if not target or target.startswith(("http://", "https://", "mailto:")):
        return
    tracked_file((readme.parent / target).resolve(), readme)


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

print("dev-tools README path and link contract checks passed")
PY

if [[ "${DEV_TOOLS_README_CONTRACT_SYMLINK_TEST:-0}" != "1" ]]; then
  symlink_test_dir="$(mktemp -d)"
  ln -s "$ROOT_DIR" "$symlink_test_dir/root"
  DEV_TOOLS_README_CONTRACT_SYMLINK_TEST=1 ROOT_PATH="$symlink_test_dir/root" bash "$0"
  rm -rf "$symlink_test_dir"
fi
