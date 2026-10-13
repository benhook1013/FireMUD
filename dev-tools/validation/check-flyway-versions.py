#!/usr/bin/env python3
from __future__ import annotations

import argparse
import dataclasses
import json
import os
import re
import subprocess
import sys
import tempfile
from pathlib import Path

VERSION_RE = re.compile(r"^V(?P<version>[0-9]+(?:[._][0-9]+)*)__.+\.(?:java|sql)$")
MIGRATION_PATH_RE = re.compile(r"^services/([^/]+)/src/main/(?:resources|java)/db/migration/")
RUNTIME_DEPENDENCY_RE = re.compile(
    r'(?:implementation|api|runtimeOnly)\s*\(\s*project\(\s*":([^\"]+)"'
    r'|dependencies\.add\(\s*"(?:implementation|api|runtimeOnly)"\s*,\s*project\.project\(\s*":([^\"]+)"'
)
PLUGIN_RE = re.compile(r'(?:\bid|pluginManager\.apply)\(\s*"([^\"]+)"\s*\)')
Snapshot = dict[str, bytes]


@dataclasses.dataclass(frozen=True)
class MigrationFile:
    path: str
    project: str
    version_text: str
    version_key: tuple[int, ...]


def normalize_version(version_text: str) -> tuple[int, ...]:
    parts = [int(part) for part in re.split(r"[._]", version_text)]
    while len(parts) > 1 and parts[-1] == 0:
        parts.pop()
    return tuple(parts)


def version_label(version_key: tuple[int, ...]) -> str:
    return ".".join(str(part) for part in version_key)


def relevant_path(path: str) -> bool:
    return bool(MIGRATION_PATH_RE.match(path)) or (
        path.startswith("services/")
        and (path.endswith("/build.gradle.kts") or re.search(r"/src/main/resources/application[^/]*\.ya?ml$", path))
    ) or (path.startswith("buildSrc/") and path.endswith((".kt", ".kts")))


def current_snapshot(repo_root: Path) -> Snapshot:
    paths = [*repo_root.glob("services/*/build.gradle.kts"),
             *repo_root.glob("services/*/src/main/resources/application*.y*ml"),
             *repo_root.glob("services/*/src/main/resources/db/migration/**/*"),
             *repo_root.glob("services/*/src/main/java/db/migration/**/*"),
             *repo_root.glob("buildSrc/**/*.kt"), *repo_root.glob("buildSrc/**/*.kts")]
    return {path.relative_to(repo_root).as_posix(): path.read_bytes() for path in paths if path.is_file()}


def git(repo_root: Path, *args: str) -> bytes:
    result = subprocess.run(["git", "-C", str(repo_root), *args], capture_output=True, check=False)
    if result.returncode:
        raise ValueError(result.stderr.decode().strip() or f"git {' '.join(args)} failed")
    return result.stdout


def parent_snapshot(repo_root: Path, base_ref: str) -> Snapshot:
    # A branch name can move while validation runs. Require the caller's pinned commit.
    if not re.fullmatch(r"[0-9a-fA-F]{40}", base_ref):
        raise ValueError("--base-ref must be a full immutable 40-character commit SHA")
    commit = git(repo_root, "rev-parse", "--verify", f"{base_ref}^{{commit}}").decode().strip()
    if commit.lower() != base_ref.lower():
        raise ValueError("--base-ref must identify a commit directly")
    paths = git(repo_root, "ls-tree", "-r", "--name-only", commit, "services", "buildSrc").decode().splitlines()
    return {path: git(repo_root, "show", f"{commit}:{path}") for path in paths if relevant_path(path)}


def discover_migration_files(snapshot: Snapshot) -> list[MigrationFile]:
    discovered = []
    for path in sorted(snapshot):
        project_match = MIGRATION_PATH_RE.match(path)
        version_match = VERSION_RE.match(Path(path).name)
        if project_match and version_match:
            text = version_match.group("version")
            discovered.append(MigrationFile(path, project_match.group(1), text, normalize_version(text)))
    return discovered


def saga_adopters(snapshot: Snapshot) -> set[str]:
    """Read existing runtime dependencies and composed convention plugins, not classifications.

    This follows the repository's literal Kotlin DSL wiring. Test dependencies are
    deliberately excluded: they do not put Saga resources on startup classpaths.
    """
    registration = snapshot.get("buildSrc/build.gradle.kts", b"").decode()
    plugin_sources: dict[str, str] = {}
    for plugin_id, class_name in re.findall(
        r'id\s*=\s*"([^\"]+)"\s+implementationClass\s*=\s*"([^\"]+)"', registration
    ):
        path = "buildSrc/src/main/kotlin/" + class_name.replace(".", "/") + ".kt"
        if path not in snapshot:
            raise ValueError(f"missing convention source for {plugin_id}: {path}")
        plugin_sources[plugin_id] = snapshot[path].decode()

    module_sources = {path.split("/")[1]: data.decode() for path, data in snapshot.items()
                      if re.fullmatch(r"services/[^/]+/build.gradle.kts", path)}

    def reaches_saga(source: str, visited: set[str]) -> bool:
        for dependency in RUNTIME_DEPENDENCY_RE.findall(source):
            module = next(part for part in dependency if part)
            if module == "common-saga":
                return True
            key = "module:" + module
            if (key not in visited and module in module_sources
                    and reaches_saga(module_sources[module], visited | {key})):
                return True
        for plugin in PLUGIN_RE.findall(source):
            key = "plugin:" + plugin
            if (key not in visited and plugin in plugin_sources
                    and reaches_saga(plugin_sources[plugin], visited | {key})):
                return True
        return False

    adopters = set()
    for project, source in module_sources.items():
        # Flyway scans nested packages below db/migration, so the parent location
        # exposes Saga resources too, even without a redundant explicit saga location.
        locations = [data.decode() for path, data in snapshot.items()
                     if re.fullmatch(rf"services/{re.escape(project)}/src/main/resources/application[^/]*\.ya?ml", path)]
        if (any(re.search(r"classpath:db/migration(?:/saga)?(?:[\s,\"']|$)", text) for text in locations)
                and reaches_saga(source, {"module:" + project})):
            adopters.add(project)
    return adopters


def effective_sets(snapshot: Snapshot) -> tuple[dict[str, list[MigrationFile]], list[MigrationFile]]:
    migrations = discover_migration_files(snapshot)
    grouped: dict[str, list[MigrationFile]] = {}
    for item in migrations:
        grouped.setdefault(item.project, []).append(item)
    shared = grouped.get("common-saga", [])
    for adopter in saga_adopters(snapshot):
        grouped.setdefault(adopter, []).extend(shared)
    return grouped, migrations


def collect_issues(current: Snapshot, parent: Snapshot | None) -> tuple[list[str], int, int]:
    grouped, migrations = effective_sets(current)
    parent_grouped, parent_migrations = effective_sets(parent) if parent is not None else ({}, [])
    issues = []
    for project, items in sorted(grouped.items()):
        seen: dict[tuple[int, ...], MigrationFile] = {}
        for item in items:
            existing = seen.get(item.version_key)
            if existing:
                issues.append(f"services/{project}: duplicate version V{item.version_text} in {item.path} "
                              f"also appears in {existing.path}")
            else:
                seen[item.version_key] = item

        previous = parent_grouped.get(project, [])
        frontier = max((item.version_key for item in previous), default=None)
        for item in items:
            if parent is not None and item.path not in parent and frontier is not None and item.version_key <= frontier:
                issues.append(f"services/{project}: new migration {item.path} (V{item.version_text}) "
                              f"must exceed effective parent frontier V{version_label(frontier)}")

    # A shared migration remains in the raw repository even if a project stops
    # loading it. Compare each project's effective history so removing either
    # the runtime dependency or a Flyway location cannot silently drop it.
    for project, previous in sorted(parent_grouped.items()):
        current_paths = {item.path for item in grouped.get(project, [])}
        for item in previous:
            if item.path not in current_paths:
                issues.append(f"services/{project}: historical effective migration no longer applied: {item.path}")

    # Treat parent histories conservatively as applied; never rewrite their names/checksums.
    for item in parent_migrations:
        if item.path not in current:
            issues.append(f"historical migration removed or renamed: {item.path}")
        elif parent is not None and current[item.path] != parent[item.path]:
            issues.append(f"historical migration content changed: {item.path}")
    return issues, len(grouped), len(migrations)


def make_fixture(root: Path, relative_paths: list[str]) -> None:
    for rel_path in relative_paths:
        path = root / rel_path
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text("-- flyway fixture\n", encoding="utf-8")


def run_self_test() -> None:
    local = "services/example-service/src/main/resources/db/migration/"
    shared = "services/common-saga/src/main/resources/db/migration/saga/"
    wiring = {
        "services/example-service/build.gradle.kts": b'dependencies { implementation(project(":common-saga")) }',
        "services/example-service/src/main/resources/application.yml": b'spring:\n  flyway:\n    locations: classpath:db/migration,classpath:db/migration/saga\n',
    }
    history = {**wiring, local + "V1__init.sql": b"-- local\n", shared + "V1002__saga.sql": b"-- shared\n"}

    def check(label: str, current: Snapshot, parent: Snapshot | None, expected: str | None) -> None:
        issues, _, _ = collect_issues(current, parent)
        if (expected is None and issues) or (expected is not None and not any(expected in issue for issue in issues)):
            raise AssertionError(f"{label}: expected {expected!r}, got {issues}")

    check("unchanged legacy history", history, history, None)
    check("duplicate-only without a base", history, None, None)
    check("new low local", {**history, local + "V2__new.sql": b"-- new"}, history, "frontier V1002")
    check("forward local", {**history, local + "V1003__new.sql": b"-- new"}, history, None)
    advanced = {**history, local + "V1005__later.sql": b"-- later"}
    check("shared below adopter frontier", {**advanced, shared + "V1004__new.sql": b"-- new"}, advanced, "frontier V1005")
    check("forward shared", {**advanced, shared + "V1006__new.sql": b"-- new"}, advanced, None)
    check("combined duplicate", {**history, local + "V1002__duplicate.sql": b"-- dup"}, history, "duplicate version V1002")
    check("normalized duplicate", {**history, local + "V1002_0__duplicate.sql": b"-- dup"}, history, "duplicate version V1002")
    check("changed history", {**history, local + "V1__init.sql": b"-- rewritten"}, history, "content changed")
    check("removed history", {path: data for path, data in history.items() if path != local + "V1__init.sql"}, history, "removed or renamed")
    check("java/sql duplicate", {**history, "services/example-service/src/main/java/db/migration/V1__dup.java": b"// dup"}, history, "duplicate version V1")

    # Exercise the actual composed convention wiring and exclude test-only dependencies.
    conventions = {
        "buildSrc/build.gradle.kts": b'id = "base"\nimplementationClass = "example.Base"\nid = "composed"\nimplementationClass = "example.Composed"',
        "buildSrc/src/main/kotlin/example/Base.kt": b'dependencies.add("implementation", project.project(":common-saga"))',
        "buildSrc/src/main/kotlin/example/Composed.kt": b'pluginManager.apply("base")',
    }
    composed = {**history, **conventions, "services/example-service/build.gradle.kts": b'plugins { id("composed") }'}
    check("composed runtime convention", {**composed, local + "V2__new.sql": b"-- new"}, composed, "frontier V1002")
    test_only = {**history, "services/example-service/build.gradle.kts": b'testImplementation(project(":common-saga"))'}
    check("test-only dependency", {**test_only, local + "V2__new.sql": b"-- new"}, test_only, None)
    dependency_removed = {**history, "services/example-service/build.gradle.kts": b"dependencies {}"}
    check("removed runtime dependency", dependency_removed, history, "historical effective migration no longer applied")
    explicit_saga_location = {**history, "services/example-service/src/main/resources/application.yml":
                              b"spring:\n  flyway:\n    locations: classpath:db/migration/saga\n"}
    location_removed = {**explicit_saga_location, "services/example-service/src/main/resources/application.yml":
                        b"spring:\n  flyway:\n    locations: classpath:db/migration/other\n"}
    check("removed Flyway location", location_removed, explicit_saga_location,
          "historical effective migration no longer applied")
    second_local = "services/second-service/src/main/resources/db/migration/"
    multiple = {**history, "services/second-service/build.gradle.kts": wiring["services/example-service/build.gradle.kts"],
                "services/second-service/src/main/resources/application.yml": wiring["services/example-service/src/main/resources/application.yml"],
                second_local + "V2000__later.sql": b"-- later"}
    check("every adopter frontier", {**multiple, shared + "V1003__new.sql": b"-- new"}, multiple, "frontier V2000")
    one_adopter_removed = {**multiple, "services/example-service/build.gradle.kts": b"dependencies {}"}
    membership_issues, _, _ = collect_issues(one_adopter_removed, multiple)
    if (not any("services/example-service: historical effective migration no longer applied" in issue
                for issue in membership_issues)
            or any("services/second-service:" in issue for issue in membership_issues)):
        raise AssertionError(f"one adopter removal affected the wrong project histories: {membership_issues}")

    with tempfile.TemporaryDirectory() as tmp:
        root = Path(tmp)
        for path, data in history.items():
            target = root / path
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(data)
        git(root, "init", "-q")
        git(root, "add", ".")
        git(root, "-c", "user.name=Flyway fixture", "-c", "user.email=fixture@example.invalid", "commit", "-qm", "history")
        base = git(root, "rev-parse", "HEAD").decode().strip()
        make_fixture(root, [local + "V2__new.sql"])
        check("immutable git parent and worktree addition", current_snapshot(root), parent_snapshot(root, base), "frontier V1002")
        try:
            parent_snapshot(root, "HEAD")
        except ValueError:
            pass
        else:
            raise AssertionError("mutable base ref was accepted")

    with tempfile.TemporaryDirectory() as tmp:
        event_path = Path(tmp) / "event.json"
        sha = "1" * 40
        for event, expected in [({"pull_request": {"base": {"sha": sha}}}, sha),
                                ({"before": sha}, sha), ({"before": "0" * 40}, None), ({}, None)]:
            event_path.write_text(json.dumps(event), encoding="utf-8")
            if event_base_ref(str(event_path)) != expected:
                raise AssertionError(f"unexpected event base for {event}")

        # CI passes push metadata through the environment; the explicit option
        # must handle the same unborn-branch sentinel without reading Git history.
        for explicit in (False, True):
            environment = dict(os.environ)
            environment.pop("GITHUB_EVENT_PATH", None)
            environment["FIREMUD_FLYWAY_BASE_REF"] = "0" * 40
            command = [sys.executable, str(Path(__file__).resolve()), "--root", tmp]
            if explicit:
                command.extend(["--base-ref", "0" * 40])
            result = subprocess.run(command, check=False, env=environment, capture_output=True, text=True)
            if result.returncode or "Parent frontier comparison" not in result.stdout:
                raise AssertionError(f"unborn explicit={explicit}: {result.stdout} {result.stderr}")

    print("Flyway migration sanity checker self-test passed.")


def event_base_ref(event_path: str | None) -> str | None:
    if not event_path:
        return None
    event = json.loads(Path(event_path).read_text(encoding="utf-8"))
    pull_request = event.get("pull_request")
    if isinstance(pull_request, dict):
        return pull_request.get("base", {}).get("sha")
    before = event.get("before")
    # A newly created branch has no parent history in the push event.
    return before if before and before != "0" * 40 else None


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Check combined Flyway histories and forward-only additions against a pinned parent.")
    parser.add_argument("--root", default=Path(os.environ.get("FIREMUD_REPO_ROOT", Path(__file__).resolve().parents[2])), type=Path,
                        help="Repository root to scan. Defaults to the FireMUD checkout root.")
    parser.add_argument("--base-ref", default=os.environ.get("FIREMUD_FLYWAY_BASE_REF"),
                        help="Full immutable parent commit SHA; defaults to FIREMUD_FLYWAY_BASE_REF or GitHub event base metadata.")
    parser.add_argument("--self-test", action="store_true", help="Run focused validation against temporary fixtures.")
    args = parser.parse_args()
    return args


def main() -> int:
    args = parse_args()
    if args.self_test:
        run_self_test()
        return 0
    root = args.root.resolve()
    try:
        base_ref = args.base_ref or event_base_ref(os.environ.get("GITHUB_EVENT_PATH"))
        # All input paths use GitHub's all-zero sentinel for a new branch.
        if base_ref == "0" * 40:
            base_ref = None
        parent = parent_snapshot(root, base_ref) if base_ref else None
        issues, project_count, file_count = collect_issues(current_snapshot(root), parent)
    except (ValueError, OSError, TypeError) as error:
        print(f"Flyway migration sanity check failed: {error}", file=sys.stderr)
        return 1
    if issues:
        print("Flyway migration sanity check failed:", file=sys.stderr)
        for issue in issues:
            print(f"- {issue}", file=sys.stderr)
        return 1
    if base_ref:
        print(f"Flyway migration sanity check passed: {project_count} migration sets, {file_count} versioned files; parent {base_ref}.")
    else:
        print(f"Flyway duplicate-version check passed: {project_count} migration sets, {file_count} versioned files. "
              "Parent frontier comparison and history preservation checks unavailable without --base-ref "
              "or FIREMUD_FLYWAY_BASE_REF (or GitHub event base metadata).")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
