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

import yaml

VERSION_RE = re.compile(r"^V(?P<version>[0-9]+(?:[._][0-9]+)*)__.+\.(?:java|sql)$")
MIGRATION_PATH_RE = re.compile(r"^services/([^/]+)/src/main/(?:resources|java)/db/(?:migration|recovery-control-migration)/")
RECOVERY_MIGRATION_DIR = "/db/recovery-control-migration/"
PACKAGED_CONFIG_RE = re.compile(r"/src/main/resources/(?:config/)?application[^/]*\.(?:ya?ml|properties)$")
RUNTIME_DEPENDENCY_RE = re.compile(
    r'(?:implementation|api|runtimeOnly)\s*\(\s*project\(\s*":([^\"]+)"'
    r'|dependencies\.add\(\s*"(?:implementation|api|runtimeOnly)"\s*,\s*project\.project\(\s*":([^\"]+)"'
)
PLUGIN_RE = re.compile(r'(?:\bid|pluginManager\.apply)\(\s*"([^\"]+)"\s*\)')
Snapshot = dict[str, bytes]


class UniqueKeyLoader(yaml.SafeLoader):
    """Reject repeated keys authored in a mapping before YAML merge expansion."""

    def __init__(self, stream: object) -> None:
        super().__init__(stream)
        self._authored_mappings_checked: set[yaml.MappingNode] = set()

    def flatten_mapping(self, node: yaml.MappingNode) -> None:
        # PyYAML mutates reused alias targets during the first merge expansion.
        if node in self._authored_mappings_checked:
            super().flatten_mapping(node)
            return
        self._authored_mappings_checked.add(node)
        seen: set[object] = set()
        for key_node, _ in node.value:
            key = (key_node.tag, key_node.value) if key_node.tag in {
                "tag:yaml.org,2002:merge", "tag:yaml.org,2002:value",
            } else self.construct_object(key_node, deep=False)
            try:
                if key in seen:
                    raise yaml.constructor.ConstructorError(
                        "while constructing a mapping", node.start_mark,
                        "found duplicate YAML key", key_node.start_mark,
                    )
                seen.add(key)
            except TypeError as error:
                raise yaml.constructor.ConstructorError(
                    "while constructing a mapping", node.start_mark,
                    "found unhashable YAML key", key_node.start_mark,
                ) from error
        super().flatten_mapping(node)


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
        and (path.endswith("/build.gradle.kts")
             or PACKAGED_CONFIG_RE.search(path))
    ) or (path.startswith("buildSrc/") and path.endswith((".kt", ".kts")))


def current_snapshot(repo_root: Path) -> Snapshot:
    paths = [*repo_root.glob("services/*/build.gradle.kts"),
             *repo_root.glob("services/*/src/main/resources/application*.y*ml"),
             *repo_root.glob("services/*/src/main/resources/config/application*.y*ml"),
             *repo_root.glob("services/*/src/main/resources/application*.properties"),
             *repo_root.glob("services/*/src/main/resources/config/application*.properties"),
             *repo_root.glob("services/*/src/main/resources/db/migration/**/*"),
             *repo_root.glob("services/*/src/main/java/db/migration/**/*"),
             *repo_root.glob("services/*/src/main/resources/db/recovery-control-migration/**/*"),
             *repo_root.glob("services/*/src/main/java/db/recovery-control-migration/**/*"),
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
    try:
        commit = git(repo_root, "rev-parse", "--verify", f"{base_ref}^{{commit}}").decode().strip()
    except ValueError as error:
        raise ValueError(f"immutable receiving parent commit is unavailable: {base_ref}; fetch that exact SHA") from error
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
        if RECOVERY_MIGRATION_DIR in item.path and not recovery_location_configured(snapshot, item.project):
            continue
        grouped.setdefault(item.project, []).append(item)
    shared = grouped.get("common-saga", [])
    for adopter in saga_adopters(snapshot):
        grouped.setdefault(adopter, []).extend(shared)
    return grouped, migrations


def recovery_location_configured(snapshot: Snapshot, project: str) -> bool:
    def flatten_properties(
        value: object,
        prefix: tuple[str, ...],
        properties: dict[str, object],
        ambiguous: set[str],
    ) -> None:
        if not isinstance(value, dict):
            return
        for key, child in value.items():
            if not isinstance(key, str):
                continue
            parts = tuple(re.sub(r"[-_]", "", part).lower() for part in key.split("."))
            path = ".".join((*prefix, *parts))
            if path in properties and properties[path] != child:
                ambiguous.add(path)
            properties[path] = child
            flatten_properties(child, (*prefix, *parts), properties, ambiguous)

    resource_root = f"services/{project}/src/main/resources"
    # Properties and imported files can override the packaged YAML. This static
    # check qualifies only the configuration sources it can evaluate completely.
    properties_pattern = re.compile(rf"{re.escape(resource_root)}/(?:config/)?application(?:-[^/]+)?\.properties")
    if any(properties_pattern.fullmatch(path) for path in snapshot):
        return False
    # Config in the packaged config/ directory takes precedence over the root.
    # Within a location Spring Boot gives .yml precedence over .yaml.
    base_paths = [f"{directory}/application.{extension}"
                  for directory in (resource_root, f"{resource_root}/config")
                  for extension in ("yaml", "yml")]
    profile_pattern = re.compile(rf"{re.escape(resource_root)}/(?:config/)?application-[^/]+\.ya?ml")
    profile_paths = sorted(path for path in snapshot if profile_pattern.fullmatch(path))
    flyway = {}
    for config_path in [*base_paths, *profile_paths]:
        try:
            documents = list(yaml.load_all(snapshot.get(config_path, b""), Loader=UniqueKeyLoader))
        except yaml.YAMLError as error:
            raise ValueError(f"{config_path}: invalid packaged Spring configuration: {error}") from error

        for document in documents:
            if document is None:
                continue
            if not isinstance(document, dict):
                return False
            properties: dict[str, object] = {}
            ambiguous: set[str] = set()
            flatten_properties(document, (), properties, ambiguous)
            if any(path == "spring.config.import" or
                   path.startswith(("spring.config.import.", "spring.config.import["))
                   for path in properties):
                return False
            exact_guard_paths = {"spring.flyway.enabled", "spring.flyway.locations", "spring.flyway.target"}
            guard_paths = {
                path for path in properties
                if path in exact_guard_paths
                or any(path.startswith((guard + ".", guard + "[")) for guard in exact_guard_paths)
            }
            if not guard_paths:
                # Profile configuration with no Flyway guard properties does not
                # affect this static qualification.
                continue
            if guard_paths & ambiguous or not guard_paths <= exact_guard_paths:
                return False

            activation = properties.get("spring.config.activate")
            legacy_profile_activation = (
                "spring.profiles" in properties
                and not isinstance(properties["spring.profiles"], dict)
            )
            if config_path in profile_paths or (isinstance(activation, dict) and activation) or any(
                path.startswith("spring.config.activate.") for path in properties
            ) or legacy_profile_activation:
                # The active profile/cloud environment is unknown, so the checker
                # cannot qualify an activation-dependent override of these guards.
                return False

            for path in guard_paths:
                flyway[path.rsplit(".", 1)[-1]] = properties[path]

    enabled = flyway.get("enabled", True)
    if enabled is not True and not (isinstance(enabled, str) and enabled.lower() == "true"):
        return False
    if flyway.get("target", "latest") != "latest":
        # Qualify the complete packaged history, not a pinned migration prefix.
        return False
    locations = flyway.get("locations", [])
    if isinstance(locations, str):
        locations = locations.split(",")
    if not isinstance(locations, list) or not all(isinstance(location, str) for location in locations):
        return False
    return any(location.strip() == "classpath:db/recovery-control-migration" for location in locations)


def collect_issues(current: Snapshot, parent: Snapshot | None) -> tuple[list[str], int, int]:
    grouped, migrations = effective_sets(current)
    parent_grouped, parent_migrations = effective_sets(parent) if parent is not None else ({}, [])
    issues = []
    for project in sorted({item.project for item in migrations if RECOVERY_MIGRATION_DIR in item.path}):
        if not recovery_location_configured(current, project):
            issues.append(f"services/{project}: packaged Spring configuration must enable Flyway with target latest and select the recovery-control location")
    recovery_sql_root = (
        "services/recovery-controller-service/src/main/resources" + RECOVERY_MIGRATION_DIR
    )
    for path in sorted(current):
        if path.startswith(recovery_sql_root) and path.endswith(".sql") and "/" in path.removeprefix(recovery_sql_root):
            issues.append(f"{path}: recovery migration SQL must be flat so Flyway and jOOQ see the same files")
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
        previous_paths = {item.path for item in previous}
        frontier = max((item.version_key for item in previous), default=None)
        for item in items:
            if parent is not None and item.path not in previous_paths and frontier is not None and item.version_key <= frontier:
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

    unadopted_parent = {
        "services/example-service/build.gradle.kts": b"dependencies {}",
        "services/example-service/src/main/resources/application.yml":
            b"spring:\n  flyway:\n    locations: classpath:db/migration\n",
        local + "V2000__init.sql": b"-- local\n",
        shared + "V1000__saga_a.sql": b"-- shared a\n",
        shared + "V1001__saga_b.sql": b"-- shared b\n",
        shared + "V1002__saga_c.sql": b"-- shared c\n",
    }
    newly_adopted = {
        **unadopted_parent,
        "services/example-service/build.gradle.kts": wiring["services/example-service/build.gradle.kts"],
        "services/example-service/src/main/resources/application.yml":
            wiring["services/example-service/src/main/resources/application.yml"],
    }
    check("newly effective shared history below local frontier", newly_adopted, unadopted_parent, "frontier V2000")
    check("unchanged non-adopter with raw shared history", unadopted_parent, unadopted_parent, None)

    forward_parent = {path: data for path, data in unadopted_parent.items()
                      if path != local + "V2000__init.sql"}
    forward_parent[local + "V999__init.sql"] = b"-- local\n"
    forward_adopted = {
        **forward_parent,
        "services/example-service/build.gradle.kts": wiring["services/example-service/build.gradle.kts"],
        "services/example-service/src/main/resources/application.yml":
            wiring["services/example-service/src/main/resources/application.yml"],
        shared + "V1003__saga_forward.sql": b"-- forward shared\n",
    }
    check("newly effective shared history above local frontier", forward_adopted, forward_parent, None)

    test_only_parent = {
        **unadopted_parent,
        "services/example-service/src/main/resources/application.yml": wiring["services/example-service/src/main/resources/application.yml"],
    }
    test_only_forward = {
        **test_only_parent,
        "services/example-service/build.gradle.kts": b'testImplementation(project(":common-saga"))',
        local + "V2001__forward.sql": b"-- new local\n",
    }
    check("test-only saga dependency does not adopt shared migrations", test_only_forward, test_only_parent, None)

    advanced = {**history, local + "V1005__later.sql": b"-- later"}
    check("shared below adopter frontier", {**advanced, shared + "V1004__new.sql": b"-- new"}, advanced, "frontier V1005")
    check("forward shared", {**advanced, shared + "V1006__new.sql": b"-- new"}, advanced, None)
    check("combined duplicate", {**history, local + "V1002__duplicate.sql": b"-- dup"}, history, "duplicate version V1002")
    check("normalized duplicate", {**history, local + "V1002_0__duplicate.sql": b"-- dup"}, history, "duplicate version V1002")
    check("changed history", {**history, local + "V1__init.sql": b"-- rewritten"}, history, "content changed")
    check("removed history", {path: data for path, data in history.items() if path != local + "V1__init.sql"}, history, "removed or renamed")
    check("java/sql duplicate", {**history, "services/example-service/src/main/java/db/migration/V1__dup.java": b"// dup"}, history, "duplicate version V1")

    recovery = "services/recovery-controller-service/src/main/resources/db/recovery-control-migration/"
    recovery_config = "services/recovery-controller-service/src/main/resources/application.yml"
    recovery_settings = b"spring:\n  flyway:\n    locations: classpath:db/recovery-control-migration\n"
    recovery_history = {recovery + "V1__journal.sql": b"-- journal", recovery_config: recovery_settings}
    check("unchanged isolated recovery history", recovery_history, recovery_history, None)
    check("packaged property override cannot qualify recovery history", {
        **recovery_history,
        recovery_config.removesuffix(".yml") + ".properties": b"spring.flyway.enabled=false\n",
    }, recovery_history, "historical effective migration no longer applied")
    check("unchecked packaged import cannot qualify recovery history", {
        **recovery_history,
        recovery_config: recovery_settings + b"spring.config.import: classpath:recovery-overrides.yml\n",
    }, recovery_history, "historical effective migration no longer applied")
    yaml_config = recovery_config.removesuffix(".yml") + ".yaml"
    renamed_recovery_config = {recovery + "V1__journal.sql": b"-- journal", yaml_config: recovery_settings}
    check("equivalent application.yaml recovery history", renamed_recovery_config, recovery_history, None)
    check("application.yml override disables recovery history", {
        **renamed_recovery_config, recovery_config: b"spring:\n  flyway:\n    enabled: false\n",
    }, recovery_history, "historical effective migration no longer applied")
    recovery_list_settings = (
        b"spring:\n  flyway:\n    enabled: true\n    locations:\n"
        b"      - classpath:db/recovery-control-migration\n"
    )
    yml_locations_override = b"spring:\n  flyway:\n    locations:\n      - classpath:db/migration\n"
    check("application.yml replaces application.yaml locations list", {
        recovery + "V1__journal.sql": b"-- journal",
        yaml_config: recovery_list_settings,
        recovery_config: yml_locations_override,
    }, recovery_history, "historical effective migration no longer applied")
    check("equivalent enabled list recovery history",
          {**recovery_history, recovery_config: recovery_list_settings}, recovery_history, None)
    check("malformed late locations entry cannot be hidden by a matching entry", {
        **recovery_history,
        recovery_config: (
            b"spring:\n  flyway:\n    locations:\n"
            b"      - classpath:db/recovery-control-migration\n"
            b"      - path: classpath:db/migration\n"
        ),
    }, recovery_history, "historical effective migration no longer applied")
    check("comma-separated scalar locations still qualify", {
        **recovery_history,
        recovery_config: recovery_settings.replace(
            b"locations: classpath:db/recovery-control-migration",
            b"locations: classpath:db/migration,classpath:db/recovery-control-migration",
        ),
    }, recovery_history, None)
    dotted_recovery_settings = (
        b"spring.flyway.enabled: true\n"
        b"spring.flyway.locations: classpath:db/recovery-control-migration\n"
    )
    check("dotted Spring Flyway recovery properties",
          {**recovery_history, recovery_config: dotted_recovery_settings}, recovery_history, None)
    dotted_disabled_settings = dotted_recovery_settings.replace(
        b"spring.flyway.enabled: true", b"spring.flyway.enabled: false"
    )
    check("dotted Spring Flyway disable fails closed",
          {**recovery_history, recovery_config: dotted_disabled_settings}, recovery_history,
          "historical effective migration no longer applied")
    try:
        collect_issues({**recovery_history, recovery_config:
                        recovery_settings + b"    enabled: true\n    enabled: true\n"}, recovery_history)
    except ValueError as error:
        if "duplicate YAML key" not in str(error) or recovery_config not in str(error):
            raise
    else:
        raise AssertionError("duplicate packaged YAML key did not fail closed")
    check("explicit enablement can override across separate YAML documents",
          {**recovery_history, recovery_config: recovery_settings + (
              b"    enabled: false\n---\nspring.flyway.enabled: 'TrUe'\n"
          )}, recovery_history, None)
    for nonmatching_value in (b"yes", b"on", b"1"):
        check(f"quoted {nonmatching_value!r} is not Spring Boot true", {
            **recovery_history,
            recovery_config: recovery_settings + b"    enabled: '" + nonmatching_value + b"'\n",
        }, recovery_history, "historical effective migration no longer applied")
    complete_recovery_history = {**recovery_history, recovery + "V2__attempt_closure.sql": b"-- closure"}
    check("recovery target latest applies V1 and V2", {
        **complete_recovery_history, recovery_config: recovery_settings + b"    target: latest\n",
    }, complete_recovery_history, None)
    check("recovery target one excludes V2", {
        **complete_recovery_history, recovery_config: recovery_settings + b"    target: '1'\n",
    }, complete_recovery_history, "historical effective migration no longer applied")
    check("nested SQL cannot silently diverge from the recovery jOOQ input", {
        **complete_recovery_history, recovery + "nested/V3__generation.sql": b"-- generation",
    }, complete_recovery_history, "recovery migration SQL must be flat")
    check("nested repeatable SQL is outside the flat recovery input", {
        **complete_recovery_history, recovery + "nested/R__refresh.sql": b"-- refresh",
    }, complete_recovery_history, "recovery migration SQL must be flat")
    check("explicit mapping override of a merged default is not a duplicate", {
        **complete_recovery_history, recovery_config: (
            b"defaults: &fallback\n  enabled: false\n" + recovery_settings
            + b"    <<: *fallback\n    enabled: true\n"
        ),
    }, complete_recovery_history, None)
    check("reused merged mapping preserves its authored override", {
        **complete_recovery_history, recovery_config: (
            b"defaults: &fallback {enabled: false}\n"
            b"qualified: &qualified {<<: *fallback, enabled: true}\n"
            b"spring:\n  flyway:\n    <<: *qualified\n"
            b"    locations: classpath:db/recovery-control-migration\n"
        ),
    }, complete_recovery_history, None)
    harmless_second_document = recovery_settings + b"---\ncustom:\n  setting: value\n"
    check("unrelated second YAML document",
          {**recovery_history, recovery_config: harmless_second_document}, recovery_history, None)
    changed_second_document = recovery_list_settings + (
        b"---\nspring:\n  flyway:\n    locations:\n      - classpath:db/migration\n"
    )
    check("changed second-document Flyway location fails closed",
          {**recovery_history, recovery_config: changed_second_document}, recovery_history,
          "historical effective migration no longer applied")
    conditional_second_document = recovery_settings + (
        b"---\nspring:\n  config:\n    activate:\n      on-profile: production\n"
        b"  flyway:\n    locations: classpath:db/migration\n"
    )
    check("conditional Flyway override fails closed",
          {**recovery_history, recovery_config: conditional_second_document}, recovery_history,
          "historical effective migration no longer applied")
    profile_only_recovery = (
        b"---\nspring:\n  config:\n    activate:\n      on-profile: recovery\n"
        b"  flyway:\n    locations: classpath:db/recovery-control-migration\n"
    )
    check("recovery location requiring active profile fails closed",
          {**recovery_history, recovery_config: profile_only_recovery}, recovery_history,
          "historical effective migration no longer applied")
    recovery_config_root = recovery_config.rsplit("/", 1)[0]
    for profile_path in (
        f"{recovery_config_root}/application-production.yml",
        f"{recovery_config_root}/config/application-production.yaml",
    ):
        check("packaged profile disables recovery history",
              {**recovery_history, profile_path: b"spring.flyway.enabled: false\n"},
              recovery_history, "historical effective migration no longer applied")
        check("packaged profile replaces recovery location",
              {**recovery_history, profile_path: yml_locations_override}, recovery_history,
              "historical effective migration no longer applied")
        check("unrelated packaged profile leaves recovery history unchanged",
              {**recovery_history, profile_path: b"server.port: 9000\n"}, recovery_history, None)
        check("recovery location in a profile cannot qualify the default history",
              {recovery + "V1__journal.sql": b"-- journal", profile_path: recovery_settings},
              recovery_history, "historical effective migration no longer applied")
    for disabled in (
        recovery_settings.replace(b"    locations:", b"    enabled: false\n    locations:"),
        recovery_list_settings.replace(b"enabled: true", b'enabled: "false"'),
    ):
        check("disabled recovery history", {**recovery_history, recovery_config: disabled},
              recovery_history, "historical effective migration no longer applied")
    check("disconnected isolated recovery history", {
        **recovery_history, recovery_config: recovery_settings.replace(b"recovery-control-migration", b"migration"),
    }, recovery_history, "historical effective migration no longer applied")
    check("duplicate recovery version", {**recovery_history, recovery + "V1__duplicate.sql": b"-- duplicate"},
          recovery_history, "duplicate version V1")
    check("changed recovery history", {recovery + "V1__journal.sql": b"-- rewritten"},
          recovery_history, "content changed")
    with tempfile.TemporaryDirectory() as tmp:
        root = Path(tmp)
        (root / recovery).mkdir(parents=True)
        (root / recovery / "V1__journal.sql").write_bytes(b"-- journal")
        (root / recovery_config).write_bytes(recovery_settings)
        profile_fixture = {**recovery_history, profile_path: b"server.port: 9000\n"}
        (root / profile_path).parent.mkdir()
        (root / profile_path).write_bytes(profile_fixture[profile_path])
        if current_snapshot(root) != profile_fixture or not all(map(relevant_path, profile_fixture)):
            raise AssertionError("isolated recovery history is not selected for both snapshots")

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
        git(Path(tmp), "init", "-q")
        event_path = Path(tmp) / "event.json"
        sha = "1" * 40
        for event, expected in [({"pull_request": {"base": {"sha": sha}}}, sha),
                                ({"pull_request": {"base": None}}, None),
                                ({"pull_request": {"base": "invalid"}}, None),
                                ({"before": sha}, sha), ({"before": "0" * 40}, None), ({}, None)]:
            event_path.write_text(json.dumps(event), encoding="utf-8")
            if event_base_ref(str(event_path)) != expected:
                raise AssertionError(f"unexpected event base for {event}")
        manual_event = {"inputs": {"flyway_base_sha": sha}}
        event_path.write_text(json.dumps(manual_event), encoding="utf-8")
        if event_base_ref(str(event_path)) != sha:
            raise AssertionError("manual dispatch base SHA was not selected")

        # CI passes push metadata through the environment; the explicit option
        # must handle the same unborn-branch sentinel without reading Git history.
        for explicit in (False, True):
            environment = dict(os.environ)
            environment.pop("GITHUB_EVENT_PATH", None)
            environment.pop("GITHUB_EVENT_NAME", None)
            environment["FIREMUD_FLYWAY_BASE_REF"] = "0" * 40
            command = [sys.executable, str(Path(__file__).resolve()), "--root", tmp]
            if explicit:
                command.extend(["--base-ref", "0" * 40])
            result = subprocess.run(command, check=False, env=environment, capture_output=True, text=True)
            if result.returncode or "Parent frontier comparison" not in result.stdout:
                raise AssertionError(f"unborn explicit={explicit}: {result.stdout} {result.stderr}")

        manual_environment = dict(os.environ)
        manual_environment["FIREMUD_FLYWAY_BASE_REF"] = "1" * 40
        manual_environment["GITHUB_EVENT_NAME"] = "workflow_dispatch"
        manual_environment["GITHUB_EVENT_PATH"] = str(event_path)
        event_path.write_text(json.dumps({"inputs": {}}), encoding="utf-8")
        result = subprocess.run([sys.executable, str(Path(__file__).resolve()), "--root", tmp],
                                check=False, env=manual_environment, capture_output=True, text=True)
        if result.returncode == 0 or "requires a full immutable" not in result.stderr:
            raise AssertionError(f"manual dispatch without a base was accepted: {result.stdout} {result.stderr}")

        event_path.write_text(json.dumps({"inputs": {"flyway_base_sha": "not-a-sha"}}), encoding="utf-8")
        result = subprocess.run([sys.executable, str(Path(__file__).resolve()), "--root", tmp],
                                check=False, env=manual_environment, capture_output=True, text=True)
        if result.returncode == 0 or "full immutable 40-character" not in result.stderr:
            raise AssertionError(f"malformed manual dispatch base was accepted: {result.stdout} {result.stderr}")

        event_path.write_text(json.dumps({"inputs": {"flyway_base_sha": "0" * 40}}), encoding="utf-8")
        result = subprocess.run([sys.executable, str(Path(__file__).resolve()), "--root", tmp],
                                check=False, env=manual_environment, capture_output=True, text=True)
        if result.returncode == 0 or "requires a full immutable" not in result.stderr:
            raise AssertionError(f"zero-sentinel manual dispatch base was accepted: {result.stdout} {result.stderr}")

        event_path.write_text(json.dumps({"inputs": {"flyway_base_sha": "2" * 40}}), encoding="utf-8")
        result = subprocess.run([sys.executable, str(Path(__file__).resolve()), "--root", tmp],
                                check=False, env=manual_environment, capture_output=True, text=True)
        if result.returncode == 0 or "immutable receiving parent commit is unavailable" not in result.stderr:
            raise AssertionError(f"unavailable manual dispatch parent was accepted: {result.stdout} {result.stderr}")

    print("Flyway migration sanity checker self-test passed.")


def event_base_ref(event_path: str | None) -> str | None:
    if not event_path:
        return None
    event = json.loads(Path(event_path).read_text(encoding="utf-8"))
    pull_request = event.get("pull_request")
    if isinstance(pull_request, dict):
        base = pull_request.get("base")
        return base.get("sha") if isinstance(base, dict) else None
    inputs = event.get("inputs")
    if isinstance(inputs, dict):
        return inputs.get("flyway_base_sha")
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
        event_path = os.environ.get("GITHUB_EVENT_PATH")
        if os.environ.get("GITHUB_EVENT_NAME") == "workflow_dispatch":
            base_ref = event_base_ref(event_path)
            if not base_ref or base_ref == "0" * 40:
                raise ValueError("workflow_dispatch requires a full immutable Flyway base SHA input")
        else:
            base_ref = args.base_ref or event_base_ref(event_path)
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
