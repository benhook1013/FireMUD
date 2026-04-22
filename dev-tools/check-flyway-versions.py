#!/usr/bin/env python3
from __future__ import annotations

import argparse
import dataclasses
import os
import re
import sys
import tempfile
from pathlib import Path

VERSION_RE = re.compile(r"^V(?P<version>[0-9]+(?:\.[0-9]+)*)__.+\.sql$")


@dataclasses.dataclass(frozen=True)
class MigrationFile:
    path: Path
    root: Path
    project: Path
    version_text: str
    version_key: tuple[int, ...]


def normalize_version(version_text: str) -> tuple[int, ...]:
    parts = [int(part) for part in version_text.split(".")]
    while len(parts) > 1 and parts[-1] == 0:
        parts.pop()
    return tuple(parts)


def version_label(version_key: tuple[int, ...]) -> str:
    return ".".join(str(part) for part in version_key)


def project_label(project_root: Path, repo_root: Path) -> str:
    return project_root.relative_to(repo_root).as_posix()


def discover_migration_files(repo_root: Path) -> list[MigrationFile]:
    services_dir = repo_root / "services"
    if not services_dir.is_dir():
        return []

    discovered: list[MigrationFile] = []
    for path in services_dir.glob("*/src/main/resources/db/migration/**/V*__*.sql"):
        if not path.is_file():
            continue
        rel_path = path.relative_to(repo_root).as_posix()
        if "src/main/resources/db/migration" not in rel_path:
            continue

        match = VERSION_RE.match(path.name)
        if match is None:
            continue

        service_name = path.relative_to(repo_root).parts[1]
        project_root = repo_root / "services" / service_name
        version_text = match.group("version")
        discovered.append(
            MigrationFile(
                path=path,
                root=path.parent,
                project=project_root,
                version_text=version_text,
                version_key=normalize_version(version_text),
            )
        )

    return discovered


def collect_issues(repo_root: Path) -> tuple[list[str], int, int]:
    discovered = discover_migration_files(repo_root)
    grouped: dict[Path, dict[Path, list[MigrationFile]]] = {}
    for item in discovered:
        grouped.setdefault(item.project, {}).setdefault(item.root, []).append(item)

    issues: list[str] = []
    for project_root in sorted(grouped, key=lambda path: path.relative_to(repo_root).as_posix()):
        roots = grouped[project_root]
        service_label = project_label(project_root, repo_root)
        seen_versions: dict[tuple[int, ...], MigrationFile] = {}
        highest_seen: tuple[int, ...] | None = None
        for root in sorted(
            roots,
            key=lambda path: (len(path.relative_to(project_root).parts), path.relative_to(project_root).as_posix()),
        ):
            for item in sorted(roots[root], key=lambda migration: (migration.version_key, migration.path.name)):
                existing = seen_versions.get(item.version_key)
                if existing is not None:
                    issues.append(
                        f"{service_label} ({root.relative_to(project_root).as_posix()}): "
                        f"duplicate version V{item.version_text} in {item.path.relative_to(repo_root).as_posix()} "
                        f"also appears in {existing.path.relative_to(repo_root).as_posix()}"
                    )
                    continue

                seen_versions[item.version_key] = item

                if highest_seen is not None and item.version_key <= highest_seen:
                    issues.append(
                        f"{service_label} ({root.relative_to(project_root).as_posix()}): "
                        f"version V{item.version_text} in {item.path.relative_to(repo_root).as_posix()} "
                        f"is out of order after V{version_label(highest_seen)}"
                    )
                    continue

                if highest_seen is None or item.version_key > highest_seen:
                    highest_seen = item.version_key

    return issues, len(grouped), len(discovered)


def make_fixture(root: Path, relative_paths: list[str]) -> None:
    for rel_path in relative_paths:
        path = root / rel_path
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text("-- flyway fixture\n", encoding="utf-8")


def run_self_test() -> None:
    with tempfile.TemporaryDirectory() as tmp:
        repo_root = Path(tmp)
        make_fixture(
            repo_root,
            [
                "services/clean-service/src/main/resources/db/migration/V1__init.sql",
                "services/clean-service/src/main/resources/db/migration/V2__next.sql",
            ],
        )
        issues, _, _ = collect_issues(repo_root)
        if issues:
            raise AssertionError(f"clean fixture unexpectedly failed: {issues}")

    with tempfile.TemporaryDirectory() as tmp:
        repo_root = Path(tmp)
        make_fixture(
            repo_root,
            [
                "services/duplicate-service/src/main/resources/db/migration/V1__init.sql",
                "services/duplicate-service/src/main/resources/db/migration/V1__dup.sql",
            ],
        )
        issues, _, _ = collect_issues(repo_root)
        if not any("duplicate version V1" in issue for issue in issues):
            raise AssertionError(f"duplicate fixture did not fail as expected: {issues}")

    with tempfile.TemporaryDirectory() as tmp:
        repo_root = Path(tmp)
        make_fixture(
            repo_root,
            [
                "services/order-service/src/main/resources/db/migration/V2__init.sql",
                "services/order-service/src/main/resources/db/migration/saga/V1__saga.sql",
            ],
        )
        issues, _, _ = collect_issues(repo_root)
        if not any("out of order" in issue for issue in issues):
            raise AssertionError(f"out-of-order fixture did not fail as expected: {issues}")

    print("Flyway migration sanity checker self-test passed.")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Check Flyway migration versions across FireMUD services.")
    parser.add_argument(
        "--root",
        default=Path(os.environ.get("FIREMUD_REPO_ROOT", Path(__file__).resolve().parent.parent)),
        type=Path,
        help="Repository root to scan. Defaults to the FireMUD checkout root.",
    )
    parser.add_argument(
        "--self-test",
        action="store_true",
        help="Run a small built-in validation suite against temporary fixtures.",
    )
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    if args.self_test:
        run_self_test()
        return 0

    repo_root = args.root.resolve()
    issues, project_count, file_count = collect_issues(repo_root)
    if issues:
        print("Flyway migration sanity check failed:", file=sys.stderr)
        for issue in issues:
            print(f"- {issue}", file=sys.stderr)
        return 1

    print(
        f"Flyway migration sanity check passed: {project_count} migration sets, {file_count} versioned files."
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
