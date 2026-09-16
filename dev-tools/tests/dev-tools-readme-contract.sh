#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
ROOT_PATH="$ROOT_DIR" python3 - <<'PY'
import os
import re
import subprocess
from pathlib import Path

root = Path(os.environ["ROOT_PATH"])

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


for readme in readmes:
    text = readme.read_text(encoding="utf-8")
    for target in re.findall(r"\[[^]]+\]\(([^)]+)\)", text):
        link_target(readme, target)

    if readme == root / "dev-tools/README.md":
        canonical = text.split("## Canonical root entrypoints", 1)[1].split("## Folder map", 1)[0]
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
