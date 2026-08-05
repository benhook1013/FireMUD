#!/usr/bin/env python3

from __future__ import annotations

import shutil
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
BUILD_DIR = REPO_ROOT / "build"
STAGING_DIR = BUILD_DIR / "pages-docs"
INCLUDE_ROOT_DOCS = {
    "README.md": "index.md",
    "AGENTS.md": "AGENTS.md",
    "FAQ.md": "FAQ.md",
    "LICENSE.md": "LICENSE.md",
    "NOTICE.md": "NOTICE.md",
    "CONTRIBUTING.md": "CONTRIBUTING.md",
    "CODE_OF_CONDUCT.md": "CODE_OF_CONDUCT.md",
    "DEVELOPER_SETUP.md": "DEVELOPER_SETUP.md",
    "SECURITY.md": "SECURITY.md",
}
INCLUDE_DIRS = ("design",)
EXCLUDED_NAME_SUFFIXES = (
    ".pre-doc-refactor-backup.md",
)
EXCLUDED_PATH_PARTS = {
    ".git",
    ".gradle",
    "build",
    "node_modules",
}


def reset_staging_dir() -> None:
    if STAGING_DIR.exists():
        shutil.rmtree(STAGING_DIR)
    BUILD_DIR.mkdir(parents=True, exist_ok=True)
    STAGING_DIR.mkdir(parents=True)


def should_copy(path: Path) -> bool:
    if any(part in EXCLUDED_PATH_PARTS for part in path.parts):
        return False
    return not path.name.endswith(EXCLUDED_NAME_SUFFIXES)


def copy_root_docs() -> None:
    for source_name, target_name in INCLUDE_ROOT_DOCS.items():
        source = REPO_ROOT / source_name
        if not source.exists():
            continue
        target = STAGING_DIR / target_name
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(source, target)


def copy_tree(source_root: Path) -> None:
    for source in source_root.rglob("*"):
        if source.is_dir() or not should_copy(source):
            continue
        relative_path = source.relative_to(REPO_ROOT)
        target = STAGING_DIR / relative_path
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(source, target)


def main() -> None:
    reset_staging_dir()
    copy_root_docs()
    for directory in INCLUDE_DIRS:
        copy_tree(REPO_ROOT / directory)


if __name__ == "__main__":
    main()
