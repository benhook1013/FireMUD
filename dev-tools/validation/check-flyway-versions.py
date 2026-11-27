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

try:
    import yaml
except ModuleNotFoundError as error:
    if error.name != "yaml":
        raise
    raise SystemExit(
        "Flyway migration sanity check requires PyYAML. From the repository root, install the pinned "
        "YAML profile in the active Python environment with: "
        "python3 -m pip install --require-hashes -r config/python/yaml-requirements.txt"
    ) from None

VERSION_RE = re.compile(r"^V(?P<version>[0-9]+(?:[._][0-9]+)*)__.+\.(?:java|sql)$")
MIGRATION_PATH_RE = re.compile(r"^services/([^/]+)/src/main/(?:resources|java)/db/(migration|recovery-control-migration)/")
RECOVERY_PROJECT = "recovery-controller-service"
RECOVERY_LOCATION = "classpath:db/recovery-control-migration"
RECOVERY_SQL_ROOT = f"services/{RECOVERY_PROJECT}/src/main/resources/db/recovery-control-migration/"
RECOVERY_HISTORY_TABLE = "flyway_schema_history_recovery_control"
RECOVERY_SCHEMA = "recovery_control"
PACKAGED_CONFIG_RE = re.compile(r"/src/main/resources/(?:config/)?application[^/]*\.(?:ya?ml|properties|xml)$")
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
    location: str


def normalize_version(version_text: str) -> tuple[int, ...]:
    parts = [int(part) for part in re.split(r"[._]", version_text)]
    while len(parts) > 1 and parts[-1] == 0:
        parts.pop()
    return tuple(parts)


def version_label(version_key: tuple[int, ...]) -> str:
    return ".".join(str(part) for part in version_key)


def migration_path(path: str) -> re.Match[str] | None:
    match = MIGRATION_PATH_RE.match(path)
    if match and match.group(2) == "recovery-control-migration" and match.group(1) != RECOVERY_PROJECT:
        return None
    return match


def relevant_path(path: str) -> bool:
    return bool(migration_path(path)) or (
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
             *repo_root.glob("services/*/src/main/resources/application*.xml"),
             *repo_root.glob("services/*/src/main/resources/config/application*.xml"),
             *repo_root.glob("services/*/src/main/resources/db/migration/**/*"),
             *repo_root.glob("services/*/src/main/java/db/migration/**/*"),
             *repo_root.glob(f"{RECOVERY_SQL_ROOT}**/*"),
             *repo_root.glob(f"services/{RECOVERY_PROJECT}/src/main/java/db/recovery-control-migration/**/*"),
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
    paths = git(repo_root, "ls-tree", "-r", "-z", "--name-only", commit, "services", "buildSrc")
    # Git paths are arbitrary bytes. Keep them round-trippable until the scoped
    # policy filter has established whether the path is a supported input.
    paths = [path.decode("utf-8", errors="surrogateescape") for path in paths.split(b"\0") if path]
    return {path: git(repo_root, "show", f"{commit}:{path}") for path in paths if relevant_path(path)}


def recovery_input_issues(snapshot: Snapshot) -> list[str]:
    """Apply the SQL-only Recovery input policy to either side of a comparison."""
    issues = []
    recovery_java_root = (
        "services/recovery-controller-service/src/main/java/db/recovery-control-migration/"
    )
    for path in sorted(snapshot):
        if path.startswith(recovery_java_root):
            issues.append(f"{path}: Recovery migration sources must be SQL-only to match packaged Flyway and jOOQ inputs")
        if path.startswith(RECOVERY_SQL_ROOT) and path.lower().endswith(".sql"):
            if not path.endswith(".sql"):
                issues.append(f"{path}: recovery migration SQL must use the canonical lowercase .sql suffix")
            if "/" in path.removeprefix(RECOVERY_SQL_ROOT):
                issues.append(f"{path}: recovery migration SQL must be flat so Flyway and jOOQ see the same files")
            elif not VERSION_RE.fullmatch(Path(path).name):
                issues.append(f"{path}: recovery SQL must use Flyway's canonical versioned V* migration filename")
        if path.startswith(RECOVERY_SQL_ROOT) and path.lower().endswith(".java"):
            issues.append(f"{path}: Recovery migration sources must be SQL-only to match packaged Flyway and jOOQ inputs")
        if path.startswith(RECOVERY_SQL_ROOT) and path.lower().endswith(".sql.conf"):
            issues.append(f"{path}: Recovery migration sidecars are unsupported because jOOQ reads only the SQL")
    return issues


def discover_migration_files(snapshot: Snapshot) -> list[MigrationFile]:
    discovered = []
    for path in sorted(snapshot):
        project_match = migration_path(path)
        version_match = VERSION_RE.match(Path(path).name)
        if project_match and version_match:
            text = version_match.group("version")
            project, directory = project_match.groups()
            discovered.append(MigrationFile(path, project, text, normalize_version(text), f"classpath:db/{directory}"))
    return discovered


def saga_adopters(
    snapshot: Snapshot, *, require_placeholder_replacement_disabled: bool = True,
) -> set[str]:
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
        if project == RECOVERY_PROJECT:
            # Recovery has stricter, already-qualified effective config parsing.
            # In particular, a higher-priority /config/application.yml can replace
            # a root location list; root-only scanning would invent Saga adoption.
            locations = recovery_flyway_locations(
                snapshot, project,
                require_placeholder_replacement_disabled=require_placeholder_replacement_disabled,
            )
            loads_saga_location = locations is not None and any(
                location in {"classpath:db/migration", "classpath:db/migration/saga"}
                for location in locations
            )
        else:
            configured_files = [data.decode() for path, data in snapshot.items()
                                if re.fullmatch(
                                    rf"services/{re.escape(project)}/src/main/resources/application[^/]*\.ya?ml",
                                    path,
                                )]
            loads_saga_location = any(
                re.search(r"classpath:db/migration(?:/saga)?(?:[\s,\"']|$)", text)
                for text in configured_files
            )
        if loads_saga_location and reaches_saga(source, {"module:" + project}):
            adopters.add(project)
    return adopters


def is_recovery_sql_migration(item: MigrationFile) -> bool:
    relative_path = item.path.removeprefix(RECOVERY_SQL_ROOT)
    return relative_path != item.path and "/" not in relative_path and relative_path.endswith(".sql")


def effective_sets(
    snapshot: Snapshot,
    migrations: list[MigrationFile],
    recovery_locations: set[str],
    *,
    require_placeholder_replacement_disabled: bool = True,
) -> dict[str, list[MigrationFile]]:
    grouped: dict[str, list[MigrationFile]] = {}
    for item in migrations:
        if item.project == RECOVERY_PROJECT and (
            item.location not in recovery_locations
            or (item.location == RECOVERY_LOCATION and not is_recovery_sql_migration(item))
        ):
            continue
        grouped.setdefault(item.project, []).append(item)
    shared = grouped.get("common-saga", [])
    for adopter in saga_adopters(
            snapshot, require_placeholder_replacement_disabled=require_placeholder_replacement_disabled):
        grouped.setdefault(adopter, []).extend(shared)
    return grouped


def recovery_flyway_locations(
    snapshot: Snapshot,
    project: str,
    *,
    require_placeholder_replacement_disabled: bool = True,
) -> set[str] | None:
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

    def reject_recursive_aliases(value: object, active: set[int], config_path: str) -> None:
        if not isinstance(value, (dict, list)):
            return
        identity = id(value)
        if identity in active:
            raise ValueError(f"{config_path}: recursive YAML aliases are unsupported")
        active.add(identity)
        try:
            children = value.values() if isinstance(value, dict) else value
            for child in children:
                reject_recursive_aliases(child, active, config_path)
        finally:
            active.remove(identity)

    resource_root = f"services/{project}/src/main/resources"
    # Properties and imported files can override the packaged YAML. This static
    # check qualifies only the configuration sources it can evaluate completely.
    properties_pattern = re.compile(rf"{re.escape(resource_root)}/(?:config/)?application(?:-[^/]+)?\.properties")
    if any(properties_pattern.fullmatch(path) for path in snapshot):
        return None
    xml_pattern = re.compile(rf"{re.escape(resource_root)}/(?:config/)?application(?:-[^/]+)?\.xml")
    if any(xml_pattern.fullmatch(path) for path in snapshot):
        return None
    # Config in the packaged config/ directory takes precedence over the root.
    # Within a location Spring Boot gives .yml precedence over .yaml.
    base_paths = [f"{directory}/application.{extension}"
                  for directory in (resource_root, f"{resource_root}/config")
                  for extension in ("yaml", "yml")]
    profile_pattern = re.compile(rf"{re.escape(resource_root)}/(?:config/)?application-[^/]+\.ya?ml")
    profile_paths = sorted(path for path in snapshot if profile_pattern.fullmatch(path))
    effective_settings = {}
    auto_configuration_switch = "spring.boot.enableautoconfiguration"
    datasource_overrides = {"spring.flyway.url", "spring.flyway.user", "spring.flyway.password"}
    init_sqls = "spring.flyway.initsqls"
    callback_locations = "spring.flyway.callbacklocations"
    placeholder_replacement = "spring.flyway.placeholderreplacement"
    effective_exclusions: list[str] | None = None
    for config_path in [*base_paths, *profile_paths]:
        try:
            documents = list(yaml.load_all(snapshot.get(config_path, b""), Loader=UniqueKeyLoader))
        except yaml.YAMLError as error:
            raise ValueError(f"{config_path}: invalid packaged Spring configuration: {error}") from error

        for document in documents:
            if document is None:
                continue
            if not isinstance(document, dict):
                return None
            reject_recursive_aliases(document, set(), config_path)
            properties: dict[str, object] = {}
            ambiguous: set[str] = set()
            flatten_properties(document, (), properties, ambiguous)
            if any(path == override or path.startswith((override + ".", override + "["))
                   for path in properties for override in datasource_overrides):
                return None
            if init_sqls in ambiguous or callback_locations in ambiguous or placeholder_replacement in ambiguous:
                return None
            exclusion_property = "spring.autoconfigure.exclude"
            exclusion_paths = {
                path for path in properties
                if path == exclusion_property or path.startswith(
                    (exclusion_property + "[", exclusion_property + "."))
            }
            if exclusion_paths:
                activation = properties.get("spring.config.activate")
                legacy_profile_activation = (
                    "spring.profiles" in properties
                    and not isinstance(properties["spring.profiles"], dict)
                )
                if config_path in profile_paths or (isinstance(activation, dict) and activation) or any(
                    path.startswith("spring.config.activate.") for path in properties
                ) or legacy_profile_activation:
                    return None
                if exclusion_paths & ambiguous:
                    return None
                # Boot binds bracketed list indices. YAML integer map keys also
                # flatten to bracketed indices, unlike string keys such as "0".
                if exclusion_property in exclusion_paths:
                    exclusion_value = properties[exclusion_property]
                    if exclusion_paths != {exclusion_property}:
                        return None
                    if isinstance(exclusion_value, dict):
                        indices = range(len(exclusion_value))
                        if (not exclusion_value or any(type(key) is not int for key in exclusion_value)
                                or set(exclusion_value) != set(indices)):
                            return None
                        exclusion_value = [exclusion_value[index] for index in indices]
                else:
                    indexed_paths = [f"{exclusion_property}[{index}]" for index in range(len(exclusion_paths))]
                    if exclusion_paths != set(indexed_paths):
                        return None
                    exclusion_value = [properties[path] for path in indexed_paths]
                if isinstance(exclusion_value, str):
                    exclusion_value = exclusion_value.split(",")
                if not isinstance(exclusion_value, list) or not all(
                        isinstance(name, str) and "${" not in name for name in exclusion_value):
                    return None
                effective_exclusions = exclusion_value
            if any(path == "spring.config.import" or
                   path.startswith(("spring.config.import.", "spring.config.import["))
                   for path in properties):
                return None
            exact_guard_paths = {auto_configuration_switch,
                                 "spring.flyway.enabled", "spring.flyway.locations", "spring.flyway.target",
                                 "spring.flyway.table", "spring.flyway.schemas", "spring.flyway.defaultschema",
                                 "spring.flyway.sqlmigrationsuffixes", "spring.flyway.sqlmigrationprefix",
                                 "spring.flyway.sqlmigrationseparator",
                                 "spring.flyway.repeatablesqlmigrationprefix",
                                 "spring.flyway.skipdefaultresolvers",
                                 "spring.flyway.skipexecutingmigrations",
                                 "spring.flyway.dryrunoutput",
                                 "spring.flyway.encoding", "spring.flyway.detectencoding",
                                 "spring.flyway.callbacklocations", "spring.flyway.initsqls",
                                 "spring.flyway.placeholderreplacement",
                                 "spring.flyway.createschemas",
                                 "spring.flyway.baselineonmigrate"}
            guard_paths = {
                path for path in properties
                if path in exact_guard_paths
                or any(path.startswith((guard + ".", guard + "[")) for guard in exact_guard_paths)
            }
            if not guard_paths:
                # Profile configuration with no migration guard properties does not
                # affect this static qualification.
                continue
            if guard_paths & ambiguous or not guard_paths <= exact_guard_paths:
                return None

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
                return None

            for path in guard_paths:
                effective_settings[path] = properties[path]

    auto_configuration_enabled = effective_settings.pop(auto_configuration_switch, True)
    if auto_configuration_enabled is not True and not (
            isinstance(auto_configuration_enabled, str)
            and auto_configuration_enabled.strip().lower() in {"true", "on", "yes", "1"}):
        return None
    if effective_exclusions is not None and any(
        name.strip().rsplit(".", 1)[-1] in {"FlywayAutoConfiguration", "DataSourceAutoConfiguration"}
        for name in effective_exclusions
    ):
        return None

    flyway = {path.rsplit(".", 1)[-1]: value for path, value in effective_settings.items()}
    enabled = flyway.get("enabled", True)
    if enabled is not True and not (isinstance(enabled, str) and enabled.lower() == "true"):
        return None
    if flyway.get("target", "latest") != "latest":
        # Qualify the complete packaged history, not a pinned migration prefix.
        return None
    if "dryrunoutput" in flyway:
        return None
    encoding = flyway.get("encoding", "UTF-8")
    detect_encoding = flyway.get("detectencoding", False)
    if (not isinstance(encoding, str) or encoding.strip().lower() != "utf-8"
            or detect_encoding is not False and not (
                isinstance(detect_encoding, str) and detect_encoding.strip().lower() == "false"
            )):
        return None
    if any(value not in ([], "") for key, value in flyway.items()
           if key in {"initsqls", "callbacklocations"}):
        return None
    if require_placeholder_replacement_disabled:
        replacement = flyway.get("placeholderreplacement")
        if replacement is not False and not (
                isinstance(replacement, str) and replacement.strip().lower() == "false"):
            return None
    locations = flyway.get("locations", ["classpath:db/migration"])
    if isinstance(locations, str):
        locations = locations.split(",")
    if not isinstance(locations, list) or not all(isinstance(location, str) for location in locations):
        return None
    normalized_locations = {location.strip() for location in locations}
    if RECOVERY_LOCATION in normalized_locations:
        create_schemas = flyway.get("createschemas", True)
        if create_schemas is not True and not (
                isinstance(create_schemas, str) and create_schemas.strip().lower() in {"true", "on", "yes", "1"}):
            return None
        schemas = flyway.get("schemas", [])
        if isinstance(schemas, str):
            schemas = [schema.strip() for schema in schemas.split(",")]
        if schemas not in ([], [RECOVERY_SCHEMA]):
            return None
        history_schema = flyway.get("defaultschema", schemas[0] if schemas else None)
        if history_schema != RECOVERY_SCHEMA or flyway.get("table") != RECOVERY_HISTORY_TABLE:
            return None
    suffixes = flyway.get("sqlmigrationsuffixes", [".sql"])
    if isinstance(suffixes, str):
        suffixes = [suffixes]
    if not isinstance(suffixes, list) or suffixes != [".sql"]:
        return None
    if (flyway.get("sqlmigrationprefix", "V") != "V"
            or flyway.get("sqlmigrationseparator", "__") != "__"
            or flyway.get("repeatablesqlmigrationprefix", "R") != "R"):
        return None
    skip_default_resolvers = flyway.get("skipdefaultresolvers", False)
    if skip_default_resolvers is not False and not (
            isinstance(skip_default_resolvers, str) and skip_default_resolvers.lower() == "false"):
        return None
    skip_executing_migrations = flyway.get("skipexecutingmigrations", False)
    if skip_executing_migrations is not False and not (
            isinstance(skip_executing_migrations, str) and skip_executing_migrations.lower() == "false"):
        return None
    baseline_on_migrate = flyway.get("baselineonmigrate", False)
    if baseline_on_migrate is not False and not (
            isinstance(baseline_on_migrate, str) and baseline_on_migrate.lower() == "false"):
        return None
    return normalized_locations


def collect_issues(current: Snapshot, parent: Snapshot | None) -> tuple[list[str], int, int]:
    migrations = discover_migration_files(current)
    recovery_locations = recovery_flyway_locations(current, RECOVERY_PROJECT)
    grouped = effective_sets(current, migrations, recovery_locations or set())
    issues = []
    project_root = f"services/{RECOVERY_PROJECT}"

    parent_migrations = discover_migration_files(parent) if parent is not None else []
    parent_grouped = {}
    if parent is not None:
        issues.extend(recovery_input_issues(parent))
        recovery_parent_migrations = [item for item in parent_migrations if item.project == RECOVERY_PROJECT]
        parent_locations = recovery_flyway_locations(
            parent, RECOVERY_PROJECT, require_placeholder_replacement_disabled=False,
        ) if recovery_parent_migrations else None
        if recovery_parent_migrations and (
            not parent_locations
            or not parent_locations <= {RECOVERY_LOCATION, "classpath:db/migration"}
            or any(
                item.location == RECOVERY_LOCATION
                and (RECOVERY_LOCATION not in parent_locations or not is_recovery_sql_migration(item))
                for item in recovery_parent_migrations
            )
        ):
            issues.append(f"{project_root}: cannot qualify the receiving parent's packaged Flyway configuration "
                          "and effective versioned migration history")
        parent_grouped = effective_sets(
            parent, parent_migrations, parent_locations or set(),
            require_placeholder_replacement_disabled=False,
        )

    if f"{project_root}/build.gradle.kts" in current or any(item.project == RECOVERY_PROJECT for item in migrations):
        if recovery_locations != {RECOVERY_LOCATION}:
            issues.append(f"{project_root}: packaged Spring configuration must enable Flyway with target latest, "
                          f"select the recovery-control location, use history table {RECOVERY_HISTORY_TABLE} "
                          f"in {RECOVERY_SCHEMA}, allow schema creation, share the journal datasource, "
                          "disable placeholder replacement, apply UTF-8 migrations without non-empty connection "
                          "init SQL or callback locations, automatic baselining, or dry-run output")
        if not any(is_recovery_sql_migration(item) for item in migrations):
            issues.append(f"{project_root}: requires at least one versioned migration in the recovery-control location")
    issues.extend(recovery_input_issues(current))
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
    versioned_file_count = (
        sum(item.project != RECOVERY_PROJECT for item in migrations)
        + sum(item.project == RECOVERY_PROJECT for item in grouped.get(RECOVERY_PROJECT, []))
    )
    return issues, len(grouped), versioned_file_count


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

    recovery = RECOVERY_SQL_ROOT
    recovery_config = "services/recovery-controller-service/src/main/resources/application.yml"
    recovery_table_setting = (
        b"    schemas: recovery_control\n"
        b"    default-schema: recovery_control\n"
        b"    placeholder-replacement: false\n"
        b"    table: flyway_schema_history_recovery_control\n"
    )
    recovery_settings = b"spring:\n  flyway:\n    locations: classpath:db/recovery-control-migration\n" + recovery_table_setting
    recovery_history = {recovery + "V1__journal.sql": b"-- journal", recovery_config: recovery_settings}
    check("unchanged isolated recovery history", recovery_history, recovery_history, None)
    check("explicitly disabled Flyway encoding detection preserves fixed UTF-8 migrations", {
        **recovery_history, recovery_config: recovery_settings + b"    detect-encoding: false\n",
    }, recovery_history, None)
    for replacement_config in (
        recovery_settings.replace(b"placeholder-replacement: false", b"placeholder-replacement: true"),
        recovery_settings.replace(b"    placeholder-replacement: false\n", b""),
    ):
        check("Recovery placeholder replacement must be explicitly disabled", {
            **recovery_history, recovery_config: replacement_config,
        }, recovery_history, "historical effective migration no longer applied")
    check("higher-priority placeholder replacement cannot be enabled", {
        **recovery_history,
        recovery_config.replace("/application.yml", "/config/application.yml"):
            b"spring.flyway.placeholder-replacement: true\n",
    }, recovery_history, "historical effective migration no longer applied")
    for config_path, config_data in (
        (recovery_config, recovery_settings + b"    detect-encoding: true\n"),
        (recovery_config.replace("/application.yml", "/config/application.yml"),
         b"spring.flyway.detect-encoding: true\n"),
    ):
        check("effective Flyway encoding detection cannot qualify fixed UTF-8 Recovery migrations", {
            **recovery_history, config_path: config_data,
        }, recovery_history, "historical effective migration no longer applied")
    recovery_saga_override = {
        **recovery_history,
        "services/recovery-controller-service/build.gradle.kts":
            b'dependencies { implementation(project(":common-saga")) }',
        recovery_config: recovery_settings.replace(
            b"classpath:db/recovery-control-migration",
            b"classpath:db/migration,classpath:db/migration/saga,classpath:db/recovery-control-migration",
        ),
        recovery_config.replace("/application.yml", "/config/application.yml"):
            b"spring.flyway.locations: classpath:db/recovery-control-migration\n",
        shared + "V1__saga.sql": b"-- saga\n",
    }
    effective_recovery_locations = recovery_flyway_locations(recovery_saga_override, RECOVERY_PROJECT)
    recovery_adopters = saga_adopters(recovery_saga_override)
    if effective_recovery_locations != {RECOVERY_LOCATION} or RECOVERY_PROJECT in recovery_adopters:
        raise AssertionError(
            "higher-priority Recovery Flyway locations must replace root Saga locations for adoption: "
            f"{effective_recovery_locations}, {recovery_adopters}"
        )
    recovery_saga_sets = effective_sets(
        recovery_saga_override,
        discover_migration_files(recovery_saga_override),
        effective_recovery_locations,
    )
    if [item.path for item in recovery_saga_sets[RECOVERY_PROJECT]] != [recovery + "V1__journal.sql"]:
        raise AssertionError("overridden Recovery locations must not add common Saga migrations")
    check("explicitly empty Flyway connection init SQL qualifies recovery history", {
        **recovery_history,
        recovery_config: recovery_settings + b"    init-sqls: []\n",
    }, recovery_history, None)
    recovery_config_override = recovery_config.replace("/application.yml", "/config/application.yml")
    for setting in ("init-sqls", "callback-locations"):
        check(f"higher-priority empty Flyway {setting} replaces a non-empty root list", {
            **recovery_history,
            recovery_config: recovery_settings + f"    {setting}: [unsafe]\n".encode(),
            recovery_config_override: f"spring.flyway.{setting}: []\n".encode(),
        }, recovery_history, None)
        check(f"effective non-empty Flyway {setting} remains unsupported", {
            **recovery_history,
            recovery_config: recovery_settings + f"    {setting}: []\n".encode(),
            recovery_config_override: f"spring.flyway.{setting}: [unsafe]\n".encode(),
        }, recovery_history, "non-empty connection init SQL")
    check("effective empty Flyway callback list qualifies recovery history", {
        **recovery_history,
        recovery_config: recovery_settings + b"    callback-locations: []\n",
    }, recovery_history, None)
    for override in (
        b"    init-sqls: [SET application_name = fixture]\n",
        b"    init-sqls: SET application_name = fixture\n",
        b"spring.flyway.init-sqls[0]: SET application_name = fixture\n",
    ):
        check("Flyway connection init SQL is unsupported", {
            **recovery_history, recovery_config: recovery_settings + override,
        }, recovery_history, "non-empty connection init SQL")
    for baseline_disabled in (b"false", b"'FaLsE'"):
        check("explicitly disabled recovery auto-baselining", {
            **recovery_history,
            recovery_config: recovery_settings + b"    baseline-on-migrate: " + baseline_disabled + b"\n",
        }, recovery_history, None)
    for baseline_enabled in (
        recovery_settings + b"    baseline-on-migrate: true\n    baseline-version: 5\n",
        recovery_settings + b"    baseline-on-migrate: ${RECOVERY_BASELINE:false}\n",
        recovery_settings + (
            b"---\nspring.config.activate.on-profile: recovery\n"
            b"spring.flyway.baseline-on-migrate: true\n"
        ),
    ):
        check("reject unqualified recovery auto-baselining", {
            **recovery_history, recovery_config: baseline_enabled,
        }, recovery_history, "historical effective migration no longer applied")
    check("absent history table falls back to a table Recovery readiness does not read", {
        **recovery_history,
        recovery_config: recovery_settings.removesuffix(b"    table: flyway_schema_history_recovery_control\n"),
    }, recovery_history, f"use history table {RECOVERY_HISTORY_TABLE}")
    recovery_override_config = recovery_config.replace("/application.yml", "/config/application.yml")
    for setting in ("default-schema", "schemas"):
        check(f"higher-priority recovery history {setting} cannot point at public", {
            **recovery_history,
            recovery_override_config: f"spring.flyway.{setting}: public\n".encode(),
        }, recovery_history, "historical effective migration no longer applied")
    for setting in ("url", "user", "password"):
        check(f"Flyway {setting} cannot separate Recovery migration from the journal datasource", {
            **recovery_history,
            recovery_override_config: f"spring.flyway.{setting}: unexpected\n".encode(),
        }, recovery_history, "share the journal datasource")
    recovery_build = "services/recovery-controller-service/build.gradle.kts"
    first_recovery = {**recovery_history, recovery_build: b"plugins {}"}
    check("first introduction with isolated Recovery migration", first_recovery, {}, None)
    check("first introduction does not require a parent config without migrations", first_recovery, {
        recovery_config: b"spring.flyway.enabled: false\n",
    }, None)
    qualified_parent = {**first_recovery, recovery + "V3__prior.sql": b"-- prior"}
    check("qualified Recovery parent sets the forward frontier", {
        **qualified_parent, recovery + "V2__too_low.sql": b"-- too low",
    }, qualified_parent, "must exceed effective parent frontier V3")
    for unqualified_parent in (
        {recovery + "V1__journal.sql": b"-- journal"},
        {**recovery_history, recovery_config: recovery_settings + b"    enabled: false\n"},
        {**recovery_history, recovery_config: recovery_settings + b"    target: '1'\n"},
        {**recovery_history, recovery_config: recovery_settings + b"spring.config.import: classpath:extra.yml\n"},
        {**recovery_history, recovery_config: recovery_settings.replace(
            b"locations: classpath:db/recovery-control-migration",
            b"locations: [classpath:db/recovery-control-migration, classpath:unknown]",
        )},
    ):
        check("existing Recovery migrations require a qualified receiving parent", first_recovery,
              unqualified_parent, "cannot qualify the receiving parent's packaged Flyway configuration")

    archive = recovery.replace("db/recovery-control-migration/", "db/migration/archive/db/recovery-control-migration/")
    archive_parent = {**first_recovery, archive + "V99__archived.sql": b"-- archive"}
    check("lookalike archive cannot satisfy required Recovery migration", {
        recovery_config: recovery_settings, archive + "V99__archived.sql": b"-- archive",
    }, {}, "requires at least one versioned migration in the recovery-control location")
    archive_candidate = {**archive_parent, recovery + "V2__next.sql": b"-- next"}
    result = collect_issues(archive_candidate, archive_parent)
    if result != ([], 1, 2):
        raise AssertionError(f"lookalike archive must not affect effective Recovery history or count: {result}")
    check("nested file inside the Recovery root cannot satisfy required migration", {
        recovery_config: recovery_settings, recovery + "archive/V99__archived.sql": b"-- archive",
    }, {}, "requires at least one versioned migration in the recovery-control location")
    check("first introduction with only an ignored default-location migration", {
        recovery_build: first_recovery[recovery_build],
        recovery_config: recovery_settings,
        recovery.replace("recovery-control-migration", "migration") + "V1__journal.sql": b"-- misplaced",
    }, {}, "requires at least one versioned migration in the recovery-control location")
    check("first introduction with no Recovery migration scripts", {
        recovery_build: first_recovery[recovery_build],
        recovery_config: recovery_settings,
    }, {}, "requires at least one versioned migration in the recovery-control location")
    check("higher-priority history table override does not qualify recovery history", {
        **recovery_history,
        recovery_config.replace("/application.yml", "/config/application.yml"):
            b"spring.flyway.table: other_history\n",
    }, recovery_history, f"use history table {RECOVERY_HISTORY_TABLE}")
    for setting, override in (("sql-migration-prefix", "M"),
                              ("sql-migration-separator", "_"),
                              ("repeatable-sql-migration-prefix", "Q")):
        check(f"unsupported Flyway {setting} override", {
            **recovery_history,
            recovery_config: recovery_settings + f"    {setting}: {override}\n".encode(),
        }, recovery_history, "packaged Spring configuration must enable Flyway")
    check("packaged property override cannot qualify recovery history", {
        **recovery_history,
        recovery_config.removesuffix(".yml") + ".properties": b"spring.flyway.enabled=false\n",
    }, recovery_history, "historical effective migration no longer applied")
    for xml_path in (
        recovery_config.removesuffix(".yml") + ".xml",
        recovery_config.replace("/application.yml", "/config/application.xml"),
        recovery_config.replace("/application.yml", "/application-dev.xml"),
        recovery_config.replace("/application.yml", "/config/application-dev.xml"),
    ):
        check("unqualified packaged XML cannot qualify Recovery migration inputs", {
            **recovery_history, xml_path: b"<beans/>\n",
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
        b"      - classpath:db/recovery-control-migration\n" + recovery_table_setting
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
            b"      - path: classpath:db/migration\n" + recovery_table_setting
        ),
    }, recovery_history, "historical effective migration no longer applied")
    check("mixed comma-separated locations cannot qualify the isolated recovery journal", {
        **recovery_history,
        recovery_config: recovery_settings.replace(
            b"locations: classpath:db/recovery-control-migration",
            b"locations: classpath:db/migration,classpath:db/recovery-control-migration",
        ),
    }, recovery_history, "packaged Spring configuration must enable Flyway")
    redis_exclusion_settings = (
        b"spring:\n  autoconfigure:\n    exclude:\n"
        b"      - org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration\n"
        b"  flyway:\n    locations: classpath:db/recovery-control-migration\n" + recovery_table_setting
    )
    check("Redis-only autoconfiguration exclusion preserves recovery Flyway", {
        **recovery_history, recovery_config: redis_exclusion_settings,
    }, recovery_history, None)
    check("higher-priority Redis-only exclusion replaces root Flyway exclusion", {
        **recovery_history,
        recovery_config: (
            b"spring:\n  autoconfigure:\n    exclude:\n"
            b"      - org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration\n"
            b"  flyway:\n    locations: classpath:db/recovery-control-migration\n" + recovery_table_setting
        ),
        recovery_config.replace("/application.yml", "/config/application.yml"): (
            b"spring:\n  autoconfigure:\n    exclude:\n"
            b"      - org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration\n"
        ),
    }, recovery_history, None)
    root_flyway_exclusion = (
        recovery_settings
        + b"spring.autoconfigure.exclude[0]: org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration\n"
    )
    redis_auto_configuration = b"org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration"
    for exclusion_key, expected in (
        (b"spring.autoconfigure.exclude[0]: ", None),
        (b"spring.autoconfigure.exclude.0: ", "historical effective migration no longer applied"),
        (b"spring.autoconfigure.exclude:\n  '0': ", "historical effective migration no longer applied"),
    ):
        check(f"higher-priority exclusions with {exclusion_key!r}", {
            **recovery_history,
            recovery_config: root_flyway_exclusion,
            recovery_config.replace("/application.yml", "/config/application.yml"):
                exclusion_key + redis_auto_configuration + b"\n",
        }, recovery_history, expected)
    for exclusion_form, exclusion_yaml in (
        ("indexed Redis-only", (
            b"spring.autoconfigure.exclude[0]: org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration\n"
            + recovery_settings
        )),
        ("nested Redis-only", (
            b"spring:\n  autoconfigure:\n    exclude:\n"
            b"      0: org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration\n"
            b"  flyway:\n    locations: classpath:db/recovery-control-migration\n" + recovery_table_setting
        )),
    ):
        check(f"{exclusion_form} autoconfiguration exclusion preserves recovery Flyway", {
            **recovery_history, recovery_config: exclusion_yaml,
        }, recovery_history, None)
    check("excluding Flyway autoconfiguration cannot qualify recovery history", {
        **recovery_history,
        recovery_config: (
            b"spring:\n  autoconfigure:\n    exclude:\n"
            b"      - org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration\n"
            b"  flyway:\n    locations: classpath:db/recovery-control-migration\n" + recovery_table_setting
        ),
    }, recovery_history, "packaged Spring configuration must enable Flyway")
    for exclusion_form, exclusion_yaml in (
        ("indexed", (
            b"spring.autoconfigure.exclude[0]: org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration\n"
            + recovery_settings
        )),
        ("nested numeric", (
            b"spring:\n  autoconfigure:\n    exclude:\n"
            b"      0: org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration\n"
            b"  flyway:\n    locations: classpath:db/recovery-control-migration\n" + recovery_table_setting
        )),
    ):
        check(f"{exclusion_form} Flyway autoconfiguration exclusion cannot qualify recovery history", {
            **recovery_history,
            recovery_config: exclusion_yaml,
        }, recovery_history, "packaged Spring configuration must enable Flyway")
    for exclusion in (
        b"spring.autoconfigure.exclude: '${TEST_AUTO_CONFIGURATION_EXCLUDES}'\n",
        b"spring.autoconfigure.exclude: ['${TEST_AUTO_CONFIGURATION_EXCLUDES}']\n",
        b"spring.autoconfigure.exclude[0]: '${TEST_AUTO_CONFIGURATION_EXCLUDES}'\n",
    ):
        check(f"unresolved auto-configuration exclusion {exclusion!r} fails closed", {
            **recovery_history, recovery_config: recovery_settings + exclusion,
        }, recovery_history, "historical effective migration no longer applied")
    for exclusion in (
        b"spring.autoconfigure.exclude: ",
        b"spring.autoconfigure.exclude[0]: ",
        b"spring.autoconfigure.exclude:\n  - ",
    ):
        check(f"datasource autoconfiguration exclusion via {exclusion!r} fails closed", {
            **recovery_history,
            recovery_config: recovery_settings + exclusion
                + b"org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration\n",
        }, recovery_history, "historical effective migration no longer applied")
    disabled_auto_configuration = b"spring.boot.enableautoconfiguration: false\n"
    check("global Spring Boot auto-configuration disable cannot qualify recovery history", {
        **recovery_history, recovery_config: recovery_settings + disabled_auto_configuration,
    }, recovery_history, "historical effective migration no longer applied")
    check("higher-priority global Spring Boot auto-configuration enable qualifies recovery history", {
        **recovery_history,
        recovery_config: recovery_settings + disabled_auto_configuration,
        recovery_config.replace("/application.yml", "/config/application.yml"):
            b"spring:\n  boot:\n    enableautoconfiguration: 'TrUe'\n",
    }, recovery_history, None)
    check("profile-only global Spring Boot switch cannot qualify recovery history", {
        **recovery_history,
        recovery_config.replace("/application.yml", "/application-dev.yml"): disabled_auto_configuration,
    }, recovery_history, "historical effective migration no longer applied")
    check("Flyway SQL suffix override cannot disconnect the recovery journal", {
        **recovery_history,
        recovery_config: recovery_settings + b"    sql-migration-suffixes: [.ddl]\n",
    }, recovery_history, "packaged Spring configuration must enable Flyway")
    check("default Flyway SQL resolvers remain enabled for the recovery journal", {
        **recovery_history,
        recovery_config: recovery_settings + b"    skip-default-resolvers: true\n",
    }, recovery_history, "packaged Spring configuration must enable Flyway")
    check("explicitly retaining Flyway SQL resolvers qualifies recovery history", {
        **recovery_history,
        recovery_config: recovery_settings + b"    skip-default-resolvers: false\n",
    }, recovery_history, None)
    check("skipping Flyway execution cannot qualify recovery history", {
        **recovery_history,
        recovery_config: recovery_settings + b"    skip-executing-migrations: true\n",
    }, recovery_history, "packaged Spring configuration must enable Flyway")
    check("explicitly executing Flyway migrations qualifies recovery history", {
        **recovery_history,
        recovery_config: recovery_settings + b"    skip-executing-migrations: false\n",
    }, recovery_history, None)
    check("Flyway dry-run output cannot qualify recovery history", {
        **recovery_history,
        recovery_config: recovery_settings + b"    dry-run-output: recovery-dry-run.sql\n",
    }, recovery_history, "historical effective migration no longer applied")
    check("higher-priority Flyway dry-run output cannot qualify recovery history", {
        **recovery_history,
        recovery_config.replace("/application.yml", "/config/application.yml"):
            b"spring.flyway.dryRunOutput: recovery-dry-run.sql\n",
    }, recovery_history, "historical effective migration no longer applied")
    check("incompatible Flyway encoding cannot qualify recovery history", {
        **recovery_history, recovery_config: recovery_settings + b"    encoding: UTF-16\n",
    }, recovery_history, "historical effective migration no longer applied")
    check("explicit UTF-8 Flyway encoding qualifies recovery history", {
        **recovery_history, recovery_config: recovery_settings + b"    encoding: UTF-8\n",
    }, recovery_history, None)
    for callbacks in (b"classpath:db/recovery-callbacks", b"[classpath:db/recovery-callbacks]"):
        check("explicit Flyway callback locations cannot qualify recovery history", {
            **recovery_history,
            recovery_config: recovery_settings + b"    callback-locations: " + callbacks + b"\n",
        }, recovery_history, "historical effective migration no longer applied")
    check("disabling Flyway schema creation cannot bootstrap the recovery journal", {
        **recovery_history, recovery_config: recovery_settings + b"    create-schemas: false\n",
    }, recovery_history, "packaged Spring configuration must enable Flyway")
    check("higher-priority Flyway schema creation enable qualifies recovery history", {
        **recovery_history,
        recovery_config: recovery_settings + b"    create-schemas: false\n",
        recovery_config.replace("/application.yml", "/config/application.yml"):
            b"spring.flyway.create-schemas: 'true'\n",
    }, recovery_history, None)
    check("unrecognized recovery SQL filename is rejected", {
        **recovery_history, recovery + "journal_extension.sql": b"-- extension",
    }, recovery_history, "recovery SQL must use Flyway's canonical versioned V* migration filename")
    check("repeatable recovery SQL is unsupported until Flyway and jOOQ ordering agree", {
        **recovery_history, recovery + "R__refresh_journal.sql": b"-- repeatable",
    }, recovery_history, "recovery SQL must use Flyway's canonical versioned V* migration filename")
    check("Java recovery migrations are unsupported by the SQL-only packaged schema path", {
        **recovery_history,
        "services/recovery-controller-service/src/main/java/db/recovery-control-migration/V2__journal.java": b"// java migration",
    }, recovery_history, "Recovery migration sources must be SQL-only")
    check("Java migrations in the recovery resource root are not effective Flyway SQL", {
        **recovery_history,
        recovery + "V2__journal.java": b"// java migration",
    }, recovery_history, "Recovery migration sources must be SQL-only")
    check("Flyway migration sidecars cannot change only the packaged SQL execution", {
        **recovery_history,
        recovery + "V1__journal.sql.conf": b"shouldExecute=false",
    }, recovery_history, "Recovery migration sidecars are unsupported")
    recovery_sidecar = recovery + "V1__journal.sql.conf"
    check("receiving-parent Recovery sidecars are rejected even when removed", recovery_history,
          {**recovery_history, recovery_sidecar: b"shouldExecute=false"},
          "Recovery migration sidecars are unsupported")
    ordinary_without_locations = {
        **recovery_history,
        recovery_config: b"spring:\n  flyway:\n    enabled: true\n    placeholder-replacement: false\n",
        "services/recovery-controller-service/src/main/resources/db/migration/V1__first.sql": b"-- first",
        "services/recovery-controller-service/src/main/resources/db/migration/V1__duplicate.sql": b"-- duplicate",
    }
    check("absent Flyway locations default to ordinary migration directory",
          ordinary_without_locations, None, "duplicate version V1")
    ordinary = "services/recovery-controller-service/src/main/resources/db/migration/V2__ordinary.sql"
    combined_history = {**recovery_history, ordinary: b"-- ordinary"}
    combined_locations = recovery_settings.replace(
        b"locations: classpath:db/recovery-control-migration",
        b"locations: [classpath:db/migration, classpath:db/recovery-control-migration]",
    )
    combined_parent = {**combined_history, recovery_config: combined_locations}
    check("Recovery history requires the isolated location alone",
          combined_parent, combined_parent,
          "packaged Spring configuration must enable Flyway")
    recovery_only = {**combined_history, recovery_config: recovery_settings}
    check("removing ordinary Flyway location removes that effective history",
          recovery_only, combined_parent, "historical effective migration no longer applied")
    dotted_recovery_settings = (
        b"spring.flyway.enabled: true\n"
        b"spring.flyway.locations: classpath:db/recovery-control-migration\n"
        b"spring.flyway.schemas: [recovery_control]\n"
        b"spring.flyway.table: flyway_schema_history_recovery_control\n"
        b"spring.flyway.placeholder-replacement: false\n"
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
    check("nested uppercase SQL is detected and rejected as noncanonical", {
        **complete_recovery_history, recovery + "nested/V3__generation.SQL": b"-- generation",
    }, complete_recovery_history, "recovery migration SQL must use the canonical lowercase .sql suffix")
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
            b"    locations: classpath:db/recovery-control-migration\n" + recovery_table_setting
        ),
    }, complete_recovery_history, None)
    reused_alias = {
        **complete_recovery_history,
        recovery_config: (
            b"shared: &shared {setting: value}\n"
            b"first: *shared\nsecond: *shared\n" + recovery_settings
        ),
    }
    check("acyclic YAML aliases may be reused by separate branches", reused_alias,
          complete_recovery_history, None)
    try:
        collect_issues({
            **complete_recovery_history,
            recovery_config: b"recursive: &recursive {child: *recursive}\n" + recovery_settings,
        }, complete_recovery_history)
    except ValueError as error:
        if "recursive YAML aliases" not in str(error) or recovery_config not in str(error):
            raise
    else:
        raise AssertionError("recursive YAML alias did not fail closed")
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
        b"  flyway:\n    locations: classpath:db/recovery-control-migration\n" + recovery_table_setting
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
        check("Flyway naming override in a profile is intentionally unsupported",
              {**recovery_history, profile_path: b"spring.flyway.sql-migration-prefix: M\n"},
              recovery_history, "packaged Spring configuration must enable Flyway")
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
        outside_recovery = {
            "services/example-service/src/main/resources/db/recovery-control-migration/V1003__other.sql":
                b"-- outside Recovery",
            "services/example-service/src/main/java/db/recovery-control-migration/V1003__other.java":
                b"// outside Recovery",
        }
        unicode_migration = "services/unicode-service/src/main/resources/db/migration/V1__café.sql"
        unrelated_non_utf8 = os.fsdecode(b"services/example-service/src/main/resources/unrelated-\xff.txt")
        combined_fixture = {**history, **recovery_history, unicode_migration: "-- café\n".encode()}
        for path, data in {**combined_fixture, **outside_recovery}.items():
            target = root / path
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(data)
        unrelated_target = root / unrelated_non_utf8
        unrelated_target.parent.mkdir(parents=True, exist_ok=True)
        unrelated_target.write_bytes(b"not a migration input\n")
        git(root, "init", "-q")
        git(root, "add", ".")
        git(root, "-c", "user.name=Flyway fixture", "-c", "user.email=fixture@example.invalid", "commit", "-qm", "history")
        base = git(root, "rev-parse", "HEAD").decode().strip()
        parent = parent_snapshot(root, base)
        if current_snapshot(root) != combined_fixture:
            raise AssertionError("current snapshot must remain scoped to migration and configuration inputs")
        if parent != combined_fixture or unicode_migration not in parent or unrelated_non_utf8 in parent:
            raise AssertionError("snapshot migration discovery must scope the Recovery location to its owning service")
        sidecar_path = root / (recovery + "V1__journal.sql.conf")
        sidecar_path.write_text("shouldExecute=false", encoding="utf-8")
        git(root, "add", ".")
        git(root, "-c", "user.name=Flyway fixture", "-c", "user.email=fixture@example.invalid", "commit", "-qm", "sidecar")
        sidecar_base = git(root, "rev-parse", "HEAD").decode().strip()
        sidecar_parent = parent_snapshot(root, sidecar_base)
        sidecar_issues, _, _ = collect_issues(combined_fixture, sidecar_parent)
        if not any("Recovery migration sidecars are unsupported" in issue for issue in sidecar_issues):
            raise AssertionError("receiving-parent Recovery sidecar was not rejected after removal")
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
