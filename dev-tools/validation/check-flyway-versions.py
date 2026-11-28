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
from collections.abc import Iterator
from pathlib import Path

try:
    import yaml
except ModuleNotFoundError as error:
    if error.name != "yaml":
        raise
    raise SystemExit(
        "Flyway migration sanity check requires PyYAML. Follow Python Dependencies for Gradle "
        "Checks in DEVELOPER_SETUP.md to install the pinned dependencies and activate the virtual "
        "environment."
    ) from None

VERSION_RE = re.compile(r"^V(?P<version>[0-9]+(?:[._][0-9]+)*)__.*\.(?:java|sql)$")
MIGRATION_PATH_RE = re.compile(r"^services/([^/]+)/src/main/(?:resources|java)/db/(migration|recovery-control-migration)/")
RECOVERY_PROJECT = "recovery-controller-service"
RECOVERY_LOCATION = "classpath:db/recovery-control-migration"
RECOVERY_RESOURCE_ROOT = f"services/{RECOVERY_PROJECT}/src/main/resources"
RECOVERY_CONFIG_DIRECTORY = f"{RECOVERY_RESOURCE_ROOT}/config"
RECOVERY_OWNER_CONFIGURATION = "net.firedevops.firemud.recovery.config.RecoveryControllerOwnerConfiguration"
RECOVERY_SQL_ROOT = f"{RECOVERY_RESOURCE_ROOT}/db/recovery-control-migration/"
RECOVERY_JAVA_ROOT = f"services/{RECOVERY_PROJECT}/src/main/java/db/recovery-control-migration/"
RECOVERY_MAIN_JAVA_ROOT = f"services/{RECOVERY_PROJECT}/src/main/java/"
RECOVERY_CONFIG_JAVA_ROOT = RECOVERY_MAIN_JAVA_ROOT + "net/firedevops/firemud/recovery/config/"
RECOVERY_CALLBACK_SOURCES = {
    RECOVERY_CONFIG_JAVA_ROOT + "RecoveryControllerOwnerConfiguration.java",
    RECOVERY_CONFIG_JAVA_ROOT + "RecoveryTransactionalMigrationCallback.java",
}
RECOVERY_MIGRATION_ROOTS = (RECOVERY_SQL_ROOT, RECOVERY_JAVA_ROOT)
RECOVERY_MIGRATION_ANCESTORS = {
    path.as_posix()
    for root in RECOVERY_MIGRATION_ROOTS
    for path in (Path(root), *Path(root).parents)
    if path.parts
}
RECOVERY_HISTORY_VERSION_LIMIT = 50
RECOVERY_RESERVED_FLYWAY_VERSION = "9223372036854775807"
RECOVERY_YAML_ENTRY_LIMIT = 4096
RECOVERY_HISTORY_TABLE = "flyway_schema_history_recovery_control"
RECOVERY_SCHEMA = "recovery_control"
POSTGRESQL_DRIVER = "org.postgresql.Driver"
JAVA_TRIM_CHARACTERS = "".join(map(chr, range(0x21)))
JAVA_UNICODE_ESCAPE_RE = re.compile(r"\\u+([0-9a-fA-F]{4})")
RECOVERY_DATASOURCE_URL = (
    "jdbc:postgresql://${FIREMUD_RECOVERY_CONTROL_DB_HOST}:${FIREMUD_RECOVERY_CONTROL_DB_PORT:5432}"
    "/firemud_recovery_control?logServerErrorDetail=false&preferQueryMode=extended&options=-c%20lock_timeout%3D10s"
)
RECOVERY_DATABASE_PORT = "firemudrecoverycontroldbport"
RECOVERY_DATABASE_CREDENTIAL_SELECTORS = {
    "firemudrecoverycontroldbuser",
    "firemudrecoverycontroldbpassword",
}
PACKAGED_CONFIG_RE = re.compile(r"/src/main/resources/(?:config/)?application[^/]*\.(?:ya?ml|properties|xml)$")
BOOT_CONFIG_NAME_RE = re.compile(r"/src/main/resources/(?:config/)?application(?:-[^/]+)?\.(?:ya?ml|properties|xml)$")
RUNTIME_CONFIGURATIONS = r"(?:implementation|api|runtimeOnly)"
RUNTIME_DEPENDENCY_CALL_RE = re.compile(
    rf'(?<![\w$])(?:{RUNTIME_CONFIGURATIONS}|"{RUNTIME_CONFIGURATIONS}")\s*\(\s*'
    rf'|\b(?:dependencies\.)?add\s*\(\s*"{RUNTIME_CONFIGURATIONS}"\s*,\s*'
)
RUNTIME_PROJECT_ARGUMENT_RE = re.compile(
    r'(?:project\.)?project\s*\(\s*":([\w-]+)"\s*\)\s*\)'
)
EXTERNAL_RUNTIME_DEPENDENCY_RE = re.compile(
    r'(?:"[\w.+-]+:[\w.+-]+(?::[\w.+-]+)?"|libs\.[\w.]+'
    r'|libs\.findLibrary\("[\w.-]+"\)\.get\(\))\s*\)'
)
RUNTIME_DEPENDENCY_OPTIONS_RE = re.compile(r"\s*\{")
LEGACY_RECOVERY_LOCATION_RE = re.compile(r"classpath:db/migration(?:/[\w-]+)*")
REPLACEMENT_FLYWAY_RE = re.compile(
    r'(?<![\w$])(?:FlywayMigrationInitializer\b|(?:new\s+(?:org\.flywaydb\.core\.)?Flyway\s*\()'
    r'|(?:Flyway\s*\.\s*configure\s*\()'
    r'|(?:registerBean\s*\([^;]*?\bFlyway\s*\.\s*class\b)'
    r'|(?:@(?:org\.springframework\.context\.annotation\.)?Bean\b'
    r'[^{};]*?\bFlyway\s+[\w$]+\s*\())'
)


def decode_indexed_string_list(
    properties: dict[str, object], ambiguous: set[str], property_name: str
) -> bool:
    """Decode one Boot indexed string list into its canonical list property."""
    indexed_paths = {
        path for path in properties
        if path.startswith((property_name + "[", property_name + "."))
    }
    value = properties.get(property_name)
    if not indexed_paths and not isinstance(value, dict):
        return True
    if property_name in ambiguous:
        return False

    indexed_values: dict[int, object] = {}
    if isinstance(value, dict):
        # Integer YAML keys are handled here. Explicit bracket keys were
        # flattened into indexed_paths by flatten_properties.
        if not value:
            return False
        for key, item in value.items():
            if type(key) is int:
                indexed_values[key] = item
            elif not isinstance(key, str) or property_name + key not in indexed_paths:
                return False
    elif property_name in properties:
        return False

    index_pattern = re.compile(
        r"^" + re.escape(property_name) + r"\[(0|[1-9][0-9]*)\]$"
    )
    for path in indexed_paths:
        match = index_pattern.fullmatch(path)
        if match is None or path in ambiguous:
            return False
        index = int(match.group(1))
        if index in indexed_values:
            return False
        indexed_values[index] = properties[path]
    if set(indexed_values) != set(range(len(indexed_values))) or not all(
        isinstance(item, str) for item in indexed_values.values()
    ):
        return False

    properties[property_name] = [indexed_values[index] for index in range(len(indexed_values))]
    for path in indexed_paths:
        properties.pop(path)
        ambiguous.discard(path)
    return True
PLUGIN_RE = re.compile(r'(?:\bid|pluginManager\.apply)\(\s*"([^\"]+)"\s*\)')
KOTLIN_COMMENT_OR_LITERAL_RE = re.compile(
    r'//[^\r\n]*|/\*|"""[\s\S]*?"""|"(?:\\.|[^"\\\r\n])*"'
    r"|'(?:\\.|[^'\\\r\n])*'"
)
KOTLIN_BLOCK_COMMENT_RE = re.compile(r"/\*|\*/")
Snapshot = dict[str, bytes]
SYMLINK_SNAPSHOT = b"\0recovery-migration-symlink\0"


class RuntimeLiteralLoader(yaml.SafeLoader):
    """Bound merge expansion and reject repeated keys authored in Recovery YAML."""

    def __init__(self, stream: object) -> None:
        super().__init__(stream)
        self._authored_mappings_checked: set[yaml.MappingNode] = set()
        self._flattening_mappings: set[yaml.MappingNode] = set()
        self._merge_entries = 0

    def construct_document(self, node: yaml.Node) -> object:
        self._authored_mappings_checked.clear()
        self._merge_entries = 0
        return super().construct_document(node)

    def flatten_mapping(self, node: yaml.MappingNode) -> None:
        if node in self._flattening_mappings:
            raise yaml.constructor.ConstructorError(
                "while constructing a Recovery mapping", node.start_mark,
                "recursive YAML merge aliases are unsupported", node.start_mark,
            )
        self._flattening_mappings.add(node)
        try:
            self._flatten_authored_mapping(node)
        finally:
            self._flattening_mappings.remove(node)

    def _flatten_authored_mapping(self, node: yaml.MappingNode) -> None:
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
        for key_node, source in node.value:
            if key_node.tag != "tag:yaml.org,2002:merge":
                continue
            if isinstance(source, yaml.SequenceNode):
                if len(source.value) > 1:
                    raise yaml.constructor.ConstructorError(
                        "while constructing a Recovery mapping", node.start_mark,
                        "multi-source YAML merges are unsupported in packaged Recovery YAML",
                        key_node.start_mark,
                    )
                if not source.value:
                    continue
                source = source.value[0]
            if isinstance(source, yaml.MappingNode):
                self.flatten_mapping(source)
                # Charge every copied entry, including defaults shadowed by a local key.
                self._merge_entries += len(source.value)
                if self._merge_entries > RECOVERY_YAML_ENTRY_LIMIT:
                    raise yaml.constructor.ConstructorError(
                        "while constructing a Recovery mapping", node.start_mark,
                        f"packaged Spring YAML exceeds {RECOVERY_YAML_ENTRY_LIMIT} expanded entries per document",
                        key_node.start_mark,
                    )
        super().flatten_mapping(node)


@dataclasses.dataclass(frozen=True)
class RecoveryFlywayConfig:
    locations: set[str]
    placeholder_delimiters: tuple[bytes, bytes] | None


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


def recovery_config_path(path: str) -> bool:
    return path.startswith(RECOVERY_RESOURCE_ROOT + "/") and bool(PACKAGED_CONFIG_RE.search(path))


def relevant_path(path: str) -> bool:
    return path == RECOVERY_CONFIG_DIRECTORY or path in RECOVERY_MIGRATION_ANCESTORS or bool(MIGRATION_PATH_RE.match(path)) or (
        path.startswith(RECOVERY_MAIN_JAVA_ROOT) and path.endswith(".java")
    ) or (
        path.startswith("services/")
        and (path.endswith("/build.gradle.kts")
             or PACKAGED_CONFIG_RE.search(path))
    ) or (path.startswith("buildSrc/") and path.endswith((".kt", ".kts")))


def current_snapshot(repo_root: Path) -> Snapshot:
    symlinks = current_recovery_migration_symlinks(repo_root)
    config_directory = repo_root / RECOVERY_CONFIG_DIRECTORY
    if (not RECOVERY_CONFIG_DIRECTORY.startswith(tuple(path + "/" for path in symlinks))
            and config_directory.is_symlink()):
        symlinks.add(RECOVERY_CONFIG_DIRECTORY)
    linked_prefixes = tuple(path + "/" for path in symlinks)
    paths = [*repo_root.glob("services/*/build.gradle.kts"),
             *repo_root.glob("services/*/src/main/resources/application*.y*ml"),
             *repo_root.glob("services/*/src/main/resources/config/application*.y*ml"),
             *repo_root.glob("services/*/src/main/resources/application*.properties"),
             *repo_root.glob("services/*/src/main/resources/config/application*.properties"),
             *repo_root.glob("services/*/src/main/resources/application*.xml"),
             *repo_root.glob("services/*/src/main/resources/config/application*.xml"),
             *repo_root.glob("services/*/src/main/resources/db/migration/**/*"),
             *repo_root.glob("services/*/src/main/java/db/migration/**/*"),
             *repo_root.glob("services/*/src/main/resources/db/recovery-control-migration/**/*"),
             *repo_root.glob("services/*/src/main/java/db/recovery-control-migration/**/*"),
             *repo_root.glob(f"{RECOVERY_MAIN_JAVA_ROOT}**/*.java"),
             *repo_root.glob("buildSrc/**/*.kt"), *repo_root.glob("buildSrc/**/*.kts")]
    snapshot = {}
    for path in paths:
        relative = path.relative_to(repo_root).as_posix()
        if relative in symlinks or relative.startswith(linked_prefixes):
            continue
        if recovery_config_path(relative) and path.is_symlink():
            snapshot[relative] = SYMLINK_SNAPSHOT
        elif path.is_file():
            snapshot[relative] = path.read_bytes()
    snapshot.update({path: SYMLINK_SNAPSHOT for path in symlinks})
    return snapshot


def current_recovery_migration_symlinks(repo_root: Path) -> set[str]:
    symlinks = set()
    for root_path in RECOVERY_MIGRATION_ROOTS:
        relative_root = Path(root_path)
        root = repo_root / relative_root
        for relative in (*reversed(relative_root.parents), relative_root):
            if relative.parts and (repo_root / relative).is_symlink():
                symlinks.add(relative.as_posix())
                break
        else:
            if root.is_dir():
                for directory, dirs, files in os.walk(root, followlinks=False):
                    for name in [*dirs, *files]:
                        path = Path(directory) / name
                        if path.is_symlink():
                            symlinks.add(path.relative_to(repo_root).as_posix())
    return symlinks


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
    entries = git(repo_root, "ls-tree", "-r", "-z", commit, "services", "buildSrc")
    # Git paths are arbitrary bytes. Keep them round-trippable until the scoped
    # policy filter has established whether the path is a supported input.
    snapshot = {}
    for entry in entries.split(b"\0"):
        if not entry:
            continue
        metadata, raw_path = entry.split(b"\t", 1)
        mode = metadata.split(b" ", 1)[0]
        path = raw_path.decode("utf-8", errors="surrogateescape")
        if relevant_path(path) and (
            mode == b"120000" or (
                path not in RECOVERY_MIGRATION_ANCESTORS and path != RECOVERY_CONFIG_DIRECTORY
            )
        ):
            snapshot[path] = SYMLINK_SNAPSHOT if mode == b"120000" else git(
                repo_root, "show", f"{commit}:{path}"
            )
    return snapshot


def translate_java_unicode_escapes(source: str) -> str:
    """Translate eligible raw Unicode escapes before Java tokenization (JLS 3.3)."""
    if r"\u" not in source:
        return source

    translated = []
    trailing_backslashes = 0
    previous_was_escape = False
    index = 0
    while index < len(source):
        character = source[index]
        escape = None
        if character == "\\" and (previous_was_escape or trailing_backslashes % 2 == 0):
            escape = JAVA_UNICODE_ESCAPE_RE.match(source, index)
        if escape is None:
            index += 1
        else:
            character = chr(int(escape.group(1), 16))
            index = escape.end()
        translated.append(character)
        trailing_backslashes = trailing_backslashes + 1 if character == "\\" else 0
        previous_was_escape = escape is not None
    return "".join(translated)


def java_code_tokens(source: str) -> str:
    """Blank Java comments and literals so policy checks see code tokens only."""
    source = translate_java_unicode_escapes(source)
    characters = list(source)

    def blank(start: int, end: int) -> None:
        for position in range(start, end):
            if characters[position] not in "\r\n":
                characters[position] = " "

    index = 0
    while index < len(source):
        if source.startswith("//", index):
            end = index + 2
            while end < len(source) and source[end] not in "\r\n":
                end += 1
            blank(index, end)
            index = end
            continue
        if source.startswith("/*", index):
            close = source.find("*/", index + 2)
            end = len(source) if close < 0 else close + 2
            blank(index, end)
            index = end
            continue
        if source.startswith('"""', index):
            end = index + 3
            while end < len(source):
                if source[end] == "\\":
                    end += 2
                elif source.startswith('"""', end):
                    end += 3
                    break
                else:
                    end += 1
            blank(index, min(end, len(source)))
            index = min(end, len(source))
            continue
        if source[index] in {'"', "'"}:
            quote = source[index]
            end = index + 1
            while end < len(source):
                if source[end] == "\\":
                    end += 2
                elif source[end] == quote:
                    end += 1
                    break
                elif source[end] in "\r\n":
                    break
                else:
                    end += 1
            blank(index, min(end, len(source)))
            index = min(end, len(source))
            continue
        index += 1
    return "".join(characters)


def recovery_input_issues(snapshot: Snapshot, dependencies: set[str]) -> list[str]:
    """Apply the SQL-only Recovery input policy to either side of a comparison."""
    issues = []
    for path in sorted(snapshot):
        if BOOT_CONFIG_NAME_RE.search(path) and path.split("/")[1] in dependencies:
            # Classpath config is discovered from the packaged libraries too.
            # The source snapshot cannot establish precedence for those resources.
            issues.append(f"{path}: Recovery runtime dependency publishes default packaged Spring configuration")
        match = MIGRATION_PATH_RE.match(path)
        if match and match.group(2) == "recovery-control-migration" and match.group(1) in dependencies:
            issues.append(f"{path}: Recovery runtime dependency contributes to its reserved migration location")
        if path.startswith(RECOVERY_MAIN_JAVA_ROOT) and path.endswith(".java"):
            source = snapshot[path].decode("utf-8", errors="replace")
            tokens = java_code_tokens(source)
            if re.search(r"(?<![\w$])(?:JavaMigration|BaseJavaMigration)(?![\w$])", tokens):
                issues.append(
                    f"{path}: Recovery JavaMigration classes and beans are unsupported; "
                    "migration version authority must remain SQL-only"
                )
            if re.search(r"(?<![\w$])FlywayMigrationStrategy(?![\w$])", tokens):
                issues.append(
                    f"{path}: Recovery FlywayMigrationStrategy beans are unsupported; "
                    "packaged SQL must use the default Flyway initializer"
                )
            if re.search(r"(?<![\w$])FlywayConfigurationCustomizer(?![\w$])", tokens):
                issues.append(
                    f"{path}: Recovery FlywayConfigurationCustomizer beans are unsupported; "
                    "the packaged migration selection must match the checked SQL"
                )
            if path not in RECOVERY_CALLBACK_SOURCES and re.search(
                r"(?<![\w$])(?:Callback|BaseCallback)(?![\w$])", tokens
            ):
                issues.append(f"{path}: Recovery Flyway only supports its transactional migration callback")
            if REPLACEMENT_FLYWAY_RE.search(tokens):
                issues.append(
                    f"{path}: Recovery replacement Flyway beans are unsupported; "
                    "packaged SQL must use the Boot-managed Flyway and initializer"
                )
        if snapshot[path] == SYMLINK_SNAPSHOT and (
            path in RECOVERY_MIGRATION_ANCESTORS or path.startswith(RECOVERY_MIGRATION_ROOTS)
        ):
            issues.append(f"{path}: Recovery migration symlinks are unsupported")
        if path.startswith(RECOVERY_JAVA_ROOT):
            issues.append(f"{path}: Recovery migration sources must be SQL-only to match packaged Flyway and jOOQ inputs")
        if not path.startswith(RECOVERY_SQL_ROOT):
            continue
        relative = path.removeprefix(RECOVERY_SQL_ROOT)
        if relative.lower().endswith(".sql.conf"):
            issues.append(f"{path}: Recovery migration sidecars are unsupported because jOOQ reads only the SQL")
        elif relative.lower().endswith(".sql"):
            if not relative.endswith(".sql"):
                issues.append(f"{path}: recovery migration SQL must use the canonical lowercase .sql suffix")
            if "/" in relative or "\\" in relative:
                issues.append(f"{path}: recovery migration SQL must be flat so Flyway and jOOQ see the same files")
            else:
                version = VERSION_RE.fullmatch(relative)
                if version is None:
                    issues.append(f"{path}: recovery SQL must use Flyway's canonical versioned V* migration filename")
                elif version.group("version") == RECOVERY_RESERVED_FLYWAY_VERSION:
                    issues.append(f"{path}: Recovery Flyway version {RECOVERY_RESERVED_FLYWAY_VERSION} "
                                  "is reserved for Flyway's LATEST marker")
                elif len(version.group("version")) > RECOVERY_HISTORY_VERSION_LIMIT:
                    issues.append(f"{path}: Recovery Flyway version must fit the "
                                  f"{RECOVERY_HISTORY_VERSION_LIMIT}-character history column")
        else:
            issues.append(f"{path}: Recovery migration sources must be SQL-only to match packaged Flyway and jOOQ inputs")
    return issues


def discover_migration_files(snapshot: Snapshot) -> list[MigrationFile]:
    discovered = []
    for path in sorted(snapshot):
        project_match = migration_path(path)
        version_match = VERSION_RE.match(Path(path).name)
        if project_match and version_match:
            text = version_match.group("version")
            if path.startswith(RECOVERY_SQL_ROOT) and (
                text == RECOVERY_RESERVED_FLYWAY_VERSION or len(text) > RECOVERY_HISTORY_VERSION_LIMIT
            ):
                continue
            project, directory = project_match.groups()
            discovered.append(MigrationFile(path, project, text, normalize_version(text), f"classpath:db/{directory}"))
    return discovered


def kotlin_source(source: str, path: str) -> tuple[str, str, set[int]]:
    """Preserve literal arguments separately from the executable call positions."""
    arguments = list(source)
    code = arguments.copy()
    literal_starts = set()
    cursor = 0
    while match := KOTLIN_COMMENT_OR_LITERAL_RE.search(source, cursor):
        cursor = match.end()
        token = match.group()
        if token == "/*":
            depth = 1
            while depth:
                delimiter = KOTLIN_BLOCK_COMMENT_RE.search(source, cursor)
                if delimiter is None:
                    raise ValueError(f"{path}: unterminated Kotlin block comment")
                depth += 1 if delimiter.group() == "/*" else -1
                cursor = delimiter.end()
        blank = re.sub(r"[^\r\n]", " ", source[match.start():cursor])
        code[match.start():cursor] = blank
        if token.startswith(("//", "/*")):
            arguments[match.start():cursor] = blank
        else:
            literal_starts.add(match.start())
    return "".join(arguments), "".join(code), literal_starts


def runtime_project_dependencies(
    snapshot: Snapshot, recovery_issues: list[str] | None = None,
) -> dict[str, set[str]]:
    """Read existing runtime dependencies and composed convention plugins, not classifications.

    This follows the repository's literal Kotlin DSL wiring. Test dependencies are
    deliberately excluded from startup classpaths.
    """
    registration_path = "buildSrc/build.gradle.kts"
    registration, registration_code, _ = kotlin_source(
        snapshot.get(registration_path, b"").decode(), registration_path,
    )
    plugin_paths: dict[str, str] = {}
    for match in re.finditer(
        r'id\s*=\s*"([^\"]+)"\s+implementationClass\s*=\s*"([^\"]+)"', registration
    ):
        if registration_code[match.start()].isspace():
            continue
        plugin_id, class_name = match.groups()
        path = "buildSrc/src/main/kotlin/" + class_name.replace(".", "/") + ".kt"
        if path not in snapshot:
            raise ValueError(f"missing convention source for {plugin_id}: {path}")
        plugin_paths[plugin_id] = path

    module_paths = {path.split("/")[1]: path for path in snapshot
                    if re.fullmatch(r"services/[^/]+/build.gradle.kts", path)}

    def collect(path: str, visited: set[str], dependencies: set[str], qualifying_recovery: bool) -> None:
        source, code, literal_starts = kotlin_source(snapshot[path].decode(), path)
        for call in RUNTIME_DEPENDENCY_CALL_RE.finditer(source):
            if code[call.start()].isspace() and call.start() not in literal_starts:
                continue
            project_match = RUNTIME_PROJECT_ARGUMENT_RE.match(source, call.end())
            declaration = project_match or EXTERNAL_RUNTIME_DEPENDENCY_RE.match(source, call.end())
            if (qualifying_recovery and recovery_issues is not None
                    and (declaration is None or RUNTIME_DEPENDENCY_OPTIONS_RE.match(source, declaration.end()))):
                line = source.count("\n", 0, call.start()) + 1
                recovery_issues.append(f"{path}:{line}: cannot resolve Recovery production runtime dependency "
                                       "from supported literal Gradle wiring")
            if project_match is None:
                continue
            module = project_match.group(1)
            dependencies.add(module)
            key = "module:" + module
            if key not in visited and module in module_paths:
                visited.add(key)
                collect(module_paths[module], visited, dependencies, qualifying_recovery)
        for match in PLUGIN_RE.finditer(source):
            if code[match.start()].isspace():
                continue
            plugin = match.group(1)
            key = "plugin:" + plugin
            if key not in visited and plugin in plugin_paths:
                visited.add(key)
                collect(plugin_paths[plugin], visited, dependencies, qualifying_recovery)

    projects: dict[str, set[str]] = {}
    for project, path in module_paths.items():
        dependencies: set[str] = set()
        collect(path, {"module:" + project}, dependencies, project == RECOVERY_PROJECT)
        projects[project] = dependencies - {project}
    return projects


def saga_adopters(snapshot: Snapshot, runtime_dependencies: dict[str, set[str]]) -> set[str]:
    adopters = set()
    location_property = "spring.flyway.locations"
    enable_property = "spring.flyway.enabled"
    for project, dependencies in runtime_dependencies.items():
        if project == RECOVERY_PROJECT or "common-saga" not in dependencies:
            continue
        resource_root = f"services/{project}/src/main/resources"
        base_paths = packaged_base_yaml_paths(resource_root)
        profiles = re.compile(rf"{re.escape(resource_root)}/(?:config/)?application-(?P<profile>[^/]+)\.ya?ml")
        profile_paths = sorted(
            (path for path in snapshot if profiles.fullmatch(path)),
            key=lambda path: ("/config/" in path, path.endswith(".yml"), path),
        )
        saga_by_profile: dict[str, bool] = {}
        enabled_by_profile: dict[str, bool] = {}
        for path in [*base_paths, *profile_paths]:
            profile_match = profiles.fullmatch(path)
            profile = profile_match.group("profile") if profile_match else ""
            for configuration in packaged_yaml_documents(snapshot, path):
                if configuration is None:
                    raise ValueError(f"{path}: packaged Spring configuration must be a mapping")
                properties, ambiguous = configuration
                conditional = any(property_name.startswith("spring.config.activate.")
                                  for property_name in properties)
                if enable_property in properties:
                    enabled = spring_boolean(properties[enable_property])
                    if enabled is None or enable_property in ambiguous:
                        raise ValueError(f"{path}: cannot resolve packaged Saga Flyway enabled setting")
                    if enabled or not conditional:
                        enabled_by_profile[profile] = enabled
                if not decode_indexed_string_list(properties, ambiguous, location_property):
                    raise ValueError(f"{path}: cannot resolve packaged Saga Flyway locations")
                if location_property not in properties:
                    continue
                if location_property in ambiguous:
                    raise ValueError(f"{path}: cannot resolve packaged Saga Flyway locations")
                locations = properties[location_property]
                if locations is None:
                    locations = []
                if isinstance(locations, str):
                    locations = [location.strip(JAVA_TRIM_CHARACTERS) for location in locations.split(",")]
                if not isinstance(locations, list) or not all(isinstance(location, str) for location in locations):
                    raise ValueError(f"{path}: cannot resolve packaged Saga Flyway locations")
                # The default migration root also exposes its nested Saga resources.
                loads_saga = any(location.startswith("classpath:") and
                                 location.removeprefix("classpath:").strip("/")
                                 in {"db/migration", "db/migration/saga"} for location in locations)
                if loads_saga or not conditional:
                    # Without runtime profile selection, retain possible Saga history
                    # from conditional documents, and fold unconditional overrides in order.
                    saga_by_profile[profile] = loads_saga
        if any(enabled_by_profile.get(profile, enabled_by_profile.get("", True))
               and saga_by_profile.get(profile, saga_by_profile.get("", True))
               for profile in {""} | saga_by_profile.keys() | enabled_by_profile.keys()):
            adopters.add(project)
    return adopters


def is_recovery_sql_migration(item: MigrationFile) -> bool:
    relative_path = item.path.removeprefix(RECOVERY_SQL_ROOT)
    return relative_path != item.path and "/" not in relative_path and relative_path.endswith(".sql")


def recovery_loads_migration(item: MigrationFile, locations: set[str]) -> bool:
    if item.location == RECOVERY_LOCATION:
        return RECOVERY_LOCATION in locations and is_recovery_sql_migration(item)
    # Flyway scans classpath locations recursively. A narrower legacy location
    # includes files beneath that directory, including service-owned migrations.
    classpath_path = "classpath:" + item.path.split("/", 5)[5]
    return any(classpath_path.startswith(location + "/") for location in locations)


def effective_sets(
    snapshot: Snapshot,
    migrations: list[MigrationFile],
    recovery_locations: set[str],
    runtime_dependencies: dict[str, set[str]],
) -> dict[str, list[MigrationFile]]:
    grouped: dict[str, list[MigrationFile]] = {}
    recovery_dependencies = runtime_dependencies.get(RECOVERY_PROJECT, set())
    for item in migrations:
        if item.project != RECOVERY_PROJECT:
            grouped.setdefault(item.project, []).append(item)
        if (item.project == RECOVERY_PROJECT or item.project in recovery_dependencies) and (
            recovery_loads_migration(item, recovery_locations)
        ):
            grouped.setdefault(RECOVERY_PROJECT, []).append(item)
    shared = grouped.get("common-saga", [])
    for adopter in saga_adopters(snapshot, runtime_dependencies):
        grouped.setdefault(adopter, []).extend(shared)
    return grouped


def spring_boolean(value: object) -> bool | None:
    """Bind literal Boolean values without resolving runtime placeholders."""
    if isinstance(value, bool):
        return value
    if isinstance(value, str):
        normalized = value.strip(JAVA_TRIM_CHARACTERS).lower()
        if normalized in {"true", "on", "yes", "1"}:
            return True
        if normalized in {"false", "off", "no", "0"}:
            return False
    return None


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
        parts = []
        for part in key.split("."):
            name, bracket, index = part.partition("[")
            parts.append(re.sub(r"[-_]", "", name).lower() + bracket + index)
        child_prefix = (*prefix, *parts)
        if prefix and key.startswith("["):
            child_prefix = (*prefix[:-1], prefix[-1] + parts[0], *parts[1:])
        path = ".".join(child_prefix)
        if path in properties:
            previous = properties[path]
            if type(previous) is not type(child) or previous != child:
                ambiguous.add(path)
        properties[path] = child
        flatten_properties(child, child_prefix, properties, ambiguous)

def count_yaml_entries(
    value: object, active: set[int], completed: dict[int, int], config_path: str,
) -> int:
    # Count the expanded entries before flattening or comparing properties.
    # Memoization lets each alias contribute its full size without expanding it.
    if not isinstance(value, (dict, list)):
        return 0
    identity = id(value)
    if identity in active:
        raise ValueError(f"{config_path}: recursive YAML aliases are unsupported")
    if identity in completed:
        return completed[identity]
    active.add(identity)
    entries = 0
    try:
        children = value.values() if isinstance(value, dict) else value
        for child in children:
            entries += 1 + count_yaml_entries(child, active, completed, config_path)
            if entries > RECOVERY_YAML_ENTRY_LIMIT:
                raise ValueError(f"{config_path}: packaged Spring YAML exceeds "
                                 f"{RECOVERY_YAML_ENTRY_LIMIT} expanded entries per document")
    finally:
        active.remove(identity)
    completed[identity] = entries
    return entries

def packaged_base_yaml_paths(resource_root: str) -> list[str]:
    # Config in the packaged config/ directory takes precedence over the root.
    return [f"{directory}/application.{extension}"
            for directory in (resource_root, f"{resource_root}/config")
            for extension in ("yaml", "yml")]


def packaged_yaml_documents(
    snapshot: Snapshot, path: str,
) -> Iterator[tuple[dict[str, object], set[str]] | None]:
    try:
        documents = list(yaml.load_all(snapshot.get(path, b""), Loader=RuntimeLiteralLoader))
    except (yaml.YAMLError, RecursionError) as error:
        raise ValueError(f"{path}: invalid packaged Spring configuration: {error}") from error
    for document in documents:
        if document is None:
            continue
        if not isinstance(document, dict):
            yield None
            continue
        properties: dict[str, object] = {}
        ambiguous: set[str] = set()
        try:
            count_yaml_entries(document, set(), {}, path)
            flatten_properties(document, (), properties, ambiguous)
        except RecursionError as error:
            raise ValueError(f"{path}: packaged Spring YAML nesting is too deep") from error
        yield properties, ambiguous


def recovery_flyway_config(
    snapshot: Snapshot,
    project: str,
    *,
    require_current_configuration: bool = True,
) -> RecoveryFlywayConfig | None:
    resource_root = f"services/{project}/src/main/resources"
    # Properties and imported files can override the packaged YAML. This static
    # check qualifies only the configuration sources it can evaluate completely.
    properties_pattern = re.compile(rf"{re.escape(resource_root)}/(?:config/)?application(?:-[^/]+)?\.properties")
    if any(properties_pattern.fullmatch(path) for path in snapshot):
        return None
    xml_pattern = re.compile(rf"{re.escape(resource_root)}/(?:config/)?application(?:-[^/]+)?\.xml")
    if any(xml_pattern.fullmatch(path) for path in snapshot):
        return None
    base_paths = packaged_base_yaml_paths(resource_root)
    profile_pattern = re.compile(rf"{re.escape(resource_root)}/(?:config/)?application-[^/]+\.ya?ml")
    profile_paths = sorted(path for path in snapshot if profile_pattern.fullmatch(path))
    profile_selection = re.compile(r"^spring\.profiles\.(?:active|default|include)(?:\[0\])?$")
    effective_settings = {}
    auto_configuration_switch = "spring.boot.enableautoconfiguration"
    datasource_overrides = {
        "spring.flyway.url", "spring.flyway.user", "spring.flyway.password",
        "spring.datasource.jndiname", "spring.datasource.hikari.datasourcejndi",
        "spring.datasource.hikari.datasourceclassname",
    }
    hikari_properties = "spring.datasource.hikari.datasourceproperties"
    init_sqls = "spring.flyway.initsqls"
    pool_init_sql = "spring.datasource.hikari.connectioninitsql"
    pool_test_query = "spring.datasource.hikari.connectiontestquery"
    datasource_type = "spring.datasource.type"
    driver_paths = {
        "spring.datasource.driverclassname",
        "spring.datasource.hikari.driverclassname",
        "spring.flyway.driverclassname",
    }
    credential_paths = {
        "spring.datasource.username": "${FIREMUD_RECOVERY_CONTROL_DB_USER}",
        "spring.datasource.password": "${FIREMUD_RECOVERY_CONTROL_DB_PASSWORD}",
        "spring.datasource.hikari.username": "${FIREMUD_RECOVERY_CONTROL_DB_USER}",
        "spring.datasource.hikari.password": "${FIREMUD_RECOVERY_CONTROL_DB_PASSWORD}",
    }
    recovery_database_host = "firemudrecoverycontroldbhost"
    callback_locations = "spring.flyway.callbacklocations"
    placeholder_replacement = "spring.flyway.placeholderreplacement"
    effective_exclusions: list[str] | None = None
    for config_path in [*base_paths, *profile_paths]:
        for configuration in packaged_yaml_documents(snapshot, config_path):
            if configuration is None:
                return None
            properties, ambiguous = configuration
            obsolete_profile_index = re.compile(
                r"^spring\.profiles(?:\[[^]]+\]|\.[0-9]+)(?:\.|$)"
            )
            if (("spring.profiles" in properties and not isinstance(properties["spring.profiles"], dict))
                    or "spring.profiles" in ambiguous
                    or (isinstance(properties.get("spring.profiles"), dict)
                        and any(type(key) is int for key in properties["spring.profiles"]))
                    or any(obsolete_profile_index.match(path) for path in properties)):
                return None
            activation = properties.get("spring.config.activate")
            profile_or_activated = (
                config_path in profile_paths
                or (isinstance(activation, dict) and bool(activation))
                or any(path.startswith("spring.config.activate.") for path in properties)
            )
            if profile_or_activated:
                for path, value in properties.items():
                    if profile_selection.fullmatch(path) and (
                        not isinstance(value, dict) or any(type(key) is int and key == 0 for key in value)
                    ):
                        raise ValueError(f"{config_path}: {path} is invalid in profile-specific Spring configuration")
            if any(path == override or path.startswith((override + ".", override + "["))
                   for path in properties for override in datasource_overrides):
                return None
            if (hikari_properties in ambiguous
                    or properties.get(hikari_properties) not in (None, "", {})
                    or any(path.startswith((hikari_properties + ".", hikari_properties + "["))
                           for path in properties)):
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
                if profile_or_activated:
                    return None
                if exclusion_paths & ambiguous:
                    return None
                if not decode_indexed_string_list(properties, ambiguous, exclusion_property):
                    return None
                exclusion_value = properties[exclusion_property]
                if exclusion_value is None:
                    # An explicit null scalar clears a lower-priority exclusion list.
                    exclusion_value = []
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
            locations_property = "spring.flyway.locations"
            if not decode_indexed_string_list(properties, ambiguous, locations_property):
                return None
            suffixes_property = "spring.flyway.sqlmigrationsuffixes"
            if not decode_indexed_string_list(properties, ambiguous, suffixes_property):
                return None
            schemas_property = "spring.flyway.schemas"
            if not decode_indexed_string_list(properties, ambiguous, schemas_property):
                return None
            ignore_patterns_property = "spring.flyway.ignoremigrationpatterns"
            if not decode_indexed_string_list(properties, ambiguous, ignore_patterns_property):
                return None
            if not decode_indexed_string_list(properties, ambiguous, init_sqls):
                return None
            exact_guard_paths = {auto_configuration_switch, pool_init_sql, pool_test_query,
                                 datasource_type, *credential_paths, *driver_paths,
                                 recovery_database_host, RECOVERY_DATABASE_PORT,
                                 *RECOVERY_DATABASE_CREDENTIAL_SELECTORS,
                                 "spring.flyway.validateonmigrate",
                                 "spring.flyway.connectretries",
                                 "spring.flyway.connectretriesinterval",
                                 "spring.flyway.lockretrycount",
                                 "spring.datasource.url", "spring.datasource.hikari.jdbcurl",
                                 "spring.flyway.enabled", "spring.flyway.locations", "spring.flyway.target",
                                 "spring.flyway.table", "spring.flyway.schemas", "spring.flyway.defaultschema",
                                 "spring.flyway.sqlmigrationsuffixes", "spring.flyway.sqlmigrationprefix",
                                 "spring.flyway.sqlmigrationseparator",
                                 "spring.flyway.repeatablesqlmigrationprefix",
                                 "spring.flyway.skipdefaultresolvers", "spring.flyway.executeintransaction",
                                 "spring.flyway.mixed", "spring.flyway.group",
                                 "spring.flyway.skipexecutingmigrations",
                                 "spring.flyway.dryrunoutput",
                                 "spring.flyway.tablespace",
                                 "spring.flyway.encoding", "spring.flyway.detectencoding",
                                 "spring.flyway.ignoremigrationpatterns",
                                 "spring.flyway.callbacklocations", "spring.flyway.initsqls",
                                 placeholder_replacement, "spring.flyway.placeholderprefix",
                                 "spring.flyway.placeholdersuffix",
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

            if profile_or_activated:
                # The active profile/cloud environment is unknown, so the checker
                # cannot qualify an activation-dependent override of these guards.
                return None

            for path in guard_paths:
                effective_settings[path] = properties[path]

    effective_datasource_url = effective_settings.get("spring.datasource.url")
    effective_hikari_jdbc_url = effective_settings.get("spring.datasource.hikari.jdbcurl")
    if ((require_current_configuration or "spring.datasource.url" in effective_settings)
            and effective_datasource_url != RECOVERY_DATASOURCE_URL):
        return None
    if ("spring.datasource.hikari.jdbcurl" in effective_settings
            and not isinstance(effective_hikari_jdbc_url, str)):
        return None
    if effective_hikari_jdbc_url is not None and (
            effective_datasource_url != RECOVERY_DATASOURCE_URL
            or effective_hikari_jdbc_url != effective_datasource_url):
        return None

    if (datasource_type in effective_settings
            and effective_settings[datasource_type] != "com.zaxxer.hikari.HikariDataSource"):
        return None
    if require_current_configuration and any(
        effective_settings[path] != POSTGRESQL_DRIVER for path in driver_paths & effective_settings.keys()
    ):
        return None
    if require_current_configuration and recovery_database_host in effective_settings:
        # The host belongs to the deployment environment. A packaged default
        # can silently redirect the dedicated journal datasource when the
        # operator omits its required environment value.
        return None
    if require_current_configuration and RECOVERY_DATABASE_PORT in effective_settings:
        # The packaged URL keeps the canonical 5432 fallback, while the actual
        # port remains a deployment environment override guarded by the runtime.
        return None
    if (require_current_configuration
            and RECOVERY_DATABASE_CREDENTIAL_SELECTORS & effective_settings.keys()):
        # Recovery credentials must come from the deployment environment, not a
        # packaged default that the runtime guard and datasource would both accept.
        return None
    if any(effective_settings[path] != reference for path, reference in credential_paths.items()
           if path in effective_settings):
        return None
    if require_current_configuration and any(
        f"spring.datasource.{credential}" not in effective_settings
        and f"spring.datasource.hikari.{credential}" not in effective_settings
        for credential in ("username", "password")
    ):
        return None

    auto_configuration_enabled = effective_settings.pop(auto_configuration_switch, True)
    if spring_boolean(auto_configuration_enabled) is not True:
        return None
    if effective_exclusions is not None and any(
        name.strip() == RECOVERY_OWNER_CONFIGURATION
        or name.strip().rsplit(".", 1)[-1] in {"FlywayAutoConfiguration", "DataSourceAutoConfiguration"}
        for name in effective_exclusions
    ):
        return None

    flyway = {path.rsplit(".", 1)[-1]: value for path, value in effective_settings.items()}
    enabled = flyway.get("enabled", True)
    if enabled is not True and not (isinstance(enabled, str) and enabled == "true"):
        return None
    if spring_boolean(flyway.get("validateonmigrate", True)) is not True:
        return None
    for sql_property in (pool_init_sql, pool_test_query):
        pool_sql = effective_settings.get(sql_property)
        if pool_sql is not None and (
                not isinstance(pool_sql, str) or pool_sql.strip(JAVA_TRIM_CHARACTERS)):
            return None
    if spring_boolean(flyway.get("mixed", False)) is not False:
        return None
    if spring_boolean(flyway.get("group", False)) is not False:
        return None
    target = flyway.get("target", "latest")
    if not isinstance(target, str) or target.casefold() != "latest":
        # Qualify the complete packaged history, not a pinned migration prefix.
        return None
    if "dryrunoutput" in flyway:
        return None
    if "tablespace" in flyway:
        # The checker cannot establish that an explicit history tablespace exists.
        return None
    for count in ("connectretries", "lockretrycount"):
        retry_count = flyway.get(count)
        if count not in flyway or count == "connectretries" and retry_count is None:
            continue
        if isinstance(retry_count, str) and re.fullmatch(r"\+?[0-9]+", retry_count):
            digits = retry_count.lstrip("+0")
            if len(digits) > 10:
                return None
            retry_count = int(digits or "0")
        if type(retry_count) is not int or not 0 <= retry_count <= 2**31 - 1:
            return None
    if "connectretriesinterval" in flyway:
        # Only qualify an explicit duration whose whole-second Flyway value is
        # unambiguous. Omission leaves the pinned Spring Boot default in effect.
        interval = flyway["connectretriesinterval"]
        if not isinstance(interval, str) or not re.fullmatch(r"[0-9]{1,10}s", interval):
            return None
        if int(interval[:-1]) > 2**31 - 1:
            return None
    encoding = flyway.get("encoding", "UTF-8")
    # The pinned Java UTF-8 charset deliberately has a different alias set from Python.
    if (not isinstance(encoding, str)
            or encoding.strip(JAVA_TRIM_CHARACTERS).lower()
            not in {"utf-8", "utf8", "unicode-1-1-utf-8"}
            or spring_boolean(flyway.get("detectencoding", False)) is not False):
        return None
    if "callbacklocations" in flyway:
        return None
    ignore_patterns = flyway.get("ignoremigrationpatterns", ["*:future"])
    if isinstance(ignore_patterns, str):
        ignore_patterns = [] if not ignore_patterns else ignore_patterns.split(",")
    if not isinstance(ignore_patterns, list) or any(
            not isinstance(pattern, str)
            or ":".join(token.strip(JAVA_TRIM_CHARACTERS)
                        for token in pattern.lower().split(":"))
            not in {"*:future", "versioned:future", "repeatable:future"}
            for pattern in ignore_patterns):
        return None
    # Spring Boot loads a YAML null and an empty YAML sequence as an empty scalar.
    if flyway.get("initsqls") not in (None, [], ""):
        return None
    replacement = spring_boolean(flyway.get("placeholderreplacement", True))
    if replacement is None or require_current_configuration and replacement:
        return None
    prefix = flyway.get("placeholderprefix", "${")
    suffix = flyway.get("placeholdersuffix", "}")
    # Runtime substitutions in a delimiter make the parent's rules unknown.
    # The standard prefix itself is an unmatched Spring placeholder opener.
    if (not isinstance(prefix, str) or not prefix or prefix != "${" and "${" in prefix
            or not isinstance(suffix, str) or not suffix or "${" in suffix):
        return None
    placeholder_delimiters = None
    if replacement:
        placeholder_delimiters = (prefix.encode(), suffix.encode())
    locations = flyway.get("locations", ["classpath:db/migration"])
    if isinstance(locations, str):
        locations = [location.strip(JAVA_TRIM_CHARACTERS) for location in locations.split(",")]
    if not isinstance(locations, list) or not all(isinstance(location, str) for location in locations):
        return None
    # The binder applies Java String.trim() to comma-delimited scalar elements. YAML list elements are
    # already individual values and must match the runtime guard byte-for-byte.
    normalized_location_list = locations
    # Spring binds Flyway locations as an ordered collection. Preserve and
    # validate that cardinality before using a set for membership checks: a
    # duplicate recovery location makes the packaged service reject startup.
    if len(normalized_location_list) != len(set(normalized_location_list)):
        return None
    normalized_locations = set(normalized_location_list)
    if RECOVERY_LOCATION in normalized_locations:
        if spring_boolean(flyway.get("createschemas", True)) is not True:
            return None
        schemas = flyway.get("schemas", [])
        if isinstance(schemas, str):
            schemas = [schema.strip(JAVA_TRIM_CHARACTERS) for schema in schemas.split(",")]
        if schemas != [RECOVERY_SCHEMA]:
            return None
        history_schema = flyway.get("defaultschema")
        if history_schema != RECOVERY_SCHEMA or flyway.get("table") != RECOVERY_HISTORY_TABLE:
            return None
    suffixes = flyway.get("sqlmigrationsuffixes", [".sql"])
    if isinstance(suffixes, str):
        suffixes = [suffix.strip(JAVA_TRIM_CHARACTERS) for suffix in suffixes.split(",")]
    if not isinstance(suffixes, list) or suffixes != [".sql"]:
        return None
    if (flyway.get("sqlmigrationprefix", "V") != "V"
            or flyway.get("sqlmigrationseparator", "__") != "__"
            or flyway.get("repeatablesqlmigrationprefix", "R") != "R"):
        return None
    for flag in ("skipdefaultresolvers", "skipexecutingmigrations", "baselineonmigrate"):
        if spring_boolean(flyway.get(flag, False)) is not False:
            return None
    if spring_boolean(flyway.get("executeintransaction", True)) is not True:
        return None
    return RecoveryFlywayConfig(normalized_locations, placeholder_delimiters)


def collect_issues(current: Snapshot, parent: Snapshot | None) -> tuple[list[str], int, int]:
    for label, snapshot in (("working tree", current), ("receiving parent", parent)):
        for path, data in (snapshot or {}).items():
            if data == SYMLINK_SNAPSHOT and (
                path == RECOVERY_CONFIG_DIRECTORY or recovery_config_path(path)
            ):
                raise ValueError(f"{path}: Recovery configuration symlinks are unsupported in the {label}")
    migrations = discover_migration_files(current)
    recovery_config = recovery_flyway_config(current, RECOVERY_PROJECT)
    recovery_locations = recovery_config.locations if recovery_config else set()
    issues = []
    current_dependencies = runtime_project_dependencies(current, issues)
    grouped = effective_sets(current, migrations, recovery_locations, current_dependencies)
    project_root = f"services/{RECOVERY_PROJECT}"

    parent_migrations = discover_migration_files(parent) if parent is not None else []
    parent_grouped = {}
    if parent is not None:
        parent_dependencies = runtime_project_dependencies(parent, issues)
        recovery_parent_dependencies = parent_dependencies.get(RECOVERY_PROJECT, set())
        issues.extend(recovery_input_issues(parent, recovery_parent_dependencies))
        recovery_parent_migrations = [item for item in parent_migrations if item.project == RECOVERY_PROJECT]
        has_possible_parent_history = bool(recovery_parent_migrations) or any(
            item.project in recovery_parent_dependencies for item in parent_migrations
        )
        parent_config = recovery_flyway_config(
            parent, RECOVERY_PROJECT, require_current_configuration=False,
        ) if has_possible_parent_history else None
        parent_locations = parent_config.locations if parent_config else set()
        if has_possible_parent_history and (
            not parent_locations
            or any(location != RECOVERY_LOCATION and not LEGACY_RECOVERY_LOCATION_RE.fullmatch(location)
                   for location in parent_locations)
            or any(
                item.location == RECOVERY_LOCATION
                and (RECOVERY_LOCATION not in parent_locations or not is_recovery_sql_migration(item))
                for item in recovery_parent_migrations
            )
        ):
            issues.append(f"{project_root}: cannot qualify the receiving parent's packaged Flyway configuration "
                          "and effective versioned migration history")
        parent_grouped = effective_sets(
            parent, parent_migrations, parent_locations, parent_dependencies,
        )
        if recovery_config and parent_config and parent_config.placeholder_delimiters:
            prefix, suffix = parent_config.placeholder_delimiters
            for item in parent_grouped.get(RECOVERY_PROJECT, []):
                if not item.path.endswith(".sql"):
                    continue
                sql = parent[item.path]
                opening = sql.find(prefix)
                if opening >= 0 and sql.find(suffix, opening + len(prefix)) >= 0:
                    issues.append(f"{project_root}: cannot disable Flyway placeholder replacement while "
                                  f"the receiving parent's migration may substitute a token: {item.path}")

    if f"{project_root}/build.gradle.kts" in current or any(item.project == RECOVERY_PROJECT for item in migrations):
        if recovery_locations != {RECOVERY_LOCATION}:
            issues.append(f"{project_root}: packaged Spring configuration must enable Flyway with target latest, "
                          f"select the recovery-control location, use history table {RECOVERY_HISTORY_TABLE} "
                          f"in {RECOVERY_SCHEMA}, allow schema creation, share the journal datasource, "
                          "disable placeholder replacement, apply UTF-8 migrations in transactions without "
                          "non-empty connection init SQL or callback locations, automatic baselining, or dry-run output")
        if not any(is_recovery_sql_migration(item) for item in migrations):
            issues.append(f"{project_root}: requires at least one versioned migration in the recovery-control location")
    issues.extend(recovery_input_issues(current, current_dependencies.get(RECOVERY_PROJECT, set())))
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
    root_saga_config = "services/example-service/src/main/resources/application.yml"
    nested_saga_config = root_saga_config.replace("/application.yml", "/config/application.yml")
    nested_saga_history = {path: data for path, data in history.items() if path != root_saga_config}
    nested_saga_history[nested_saga_config] = history[root_saga_config]
    check("moving an adopter's configuration to config/ preserves Saga history",
          nested_saga_history, history, None)
    check("Saga history in config/ sets the effective parent frontier",
          {**nested_saga_history, local + "V2__new.sql": b"-- new"},
          nested_saga_history, "frontier V1002")
    for config in (b"spring.flyway.locations: classpath:/db/migration/\n", b"spring.flyway.enabled: true\n"):
        inherited_saga = {**history, root_saga_config: config}
        check("Flyway classpath aliases and default locations include Saga history",
              {**inherited_saga, local + "V2__new.sql": b"-- new"},
              inherited_saga, "frontier V1002")
    for config in (b"spring.flyway.enabled: false\n", b"spring.flyway.locations: classpath:other\n"):
        isolated_history = {**history, root_saga_config: config}
        check("Disabled or explicitly separate Flyway does not adopt Saga history",
              {**isolated_history, local + "V2__new.sql": b"-- new"}, isolated_history, None)
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
    later_local_only = {
        **unadopted_parent,
        "services/example-service/build.gradle.kts": wiring["services/example-service/build.gradle.kts"],
        nested_saga_config: (
            b"spring.flyway.locations: [classpath:db/migration, classpath:db/migration/saga]\n"
            b"---\nspring:\n  flyway:\n    locations: [classpath:db/migration/local]\n"
        ),
    }
    check("a later unconditional YAML document replaces earlier Saga locations",
          later_local_only, unadopted_parent, None)

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
    recovery_config_override = recovery_config.replace("/application.yml", "/config/application.yml")
    recovery_table_setting = (
        b"    schemas: recovery_control\n"
        b"    default-schema: recovery_control\n"
        b"    placeholder-replacement: false\n"
        b"    table: flyway_schema_history_recovery_control\n"
    )
    datasource_url = RECOVERY_DATASOURCE_URL.encode()
    datasource_setting = (
        b"spring.datasource.url: " + datasource_url + b"\n"
        b"spring.datasource.username: ${FIREMUD_RECOVERY_CONTROL_DB_USER}\n"
        b"spring.datasource.password: ${FIREMUD_RECOVERY_CONTROL_DB_PASSWORD}\n"
    )
    recovery_settings = (
        datasource_setting + b"spring:\n  flyway:\n    locations: classpath:db/recovery-control-migration\n"
        + recovery_table_setting
    )
    recovery_history = {recovery + "V1__journal.sql": b"-- journal", recovery_config: recovery_settings}
    check("unchanged isolated recovery history", recovery_history, recovery_history, None)
    foreign_location = "services/common-platform-core/src/main/resources/db/recovery-control-migration/V1__shared.sql"
    foreign_resource = {**recovery_history, foreign_location: b"-- unrelated unless loaded"}
    check("another module does not qualify as Recovery migration history",
          foreign_resource, recovery_history, None)
    check("Recovery runtime dependencies cannot publish the reserved migration location", {
        **foreign_resource,
        f"services/{RECOVERY_PROJECT}/build.gradle.kts":
            b'dependencies { implementation(project(":common-platform-core")) }',
    }, recovery_history, "Recovery runtime dependency contributes to its reserved migration location")
    check("Recovery customizers cannot change the qualifying migration selection", {
        **recovery_history,
        f"{RECOVERY_MAIN_JAVA_ROOT}Override.java":
            b"@Bean FlywayConfigurationCustomizer pinTarget() { return c -> c.target(1); }",
    }, recovery_history, "Recovery FlywayConfigurationCustomizer beans are unsupported")
    for credential in (b"username", b"password"):
        missing_credential = datasource_setting.replace(
            b"spring.datasource." + credential + b": ${FIREMUD_RECOVERY_CONTROL_DB_"
            + (b"USER" if credential == b"username" else b"PASSWORD") + b"}\n",
            b"",
        )
        check("current Recovery datasource requires canonical environment credentials", {
            **recovery_history,
            recovery_config: recovery_settings.replace(datasource_setting, missing_credential),
        }, recovery_history, "historical effective migration no longer applied")
    check("canonical Hikari-only credentials remain eligible", {
        **recovery_history,
        recovery_config: recovery_settings.replace(
            b"spring.datasource.username:", b"spring.datasource.hikari.username:"
        ).replace(b"spring.datasource.password:", b"spring.datasource.hikari.password:"),
    }, recovery_history, None)
    for hikari_credential in (b"username", b"password"):
        check("noncanonical explicit Hikari credential cannot qualify Recovery history", {
            **recovery_history,
            recovery_config: recovery_settings + (
                b"spring.datasource.hikari." + hikari_credential + b": packaged-default\n"
            ),
        }, recovery_history, "historical effective migration no longer applied")
    check("packaged Recovery database host default cannot qualify history", {
        **recovery_history,
        recovery_config: recovery_settings + b"FIREMUD_RECOVERY_CONTROL_DB_HOST: shared-postgres\n",
    }, recovery_history, "historical effective migration no longer applied")
    check("packaged Recovery port override cannot qualify history", {
        **recovery_history,
        recovery_config: recovery_settings + b"FIREMUD_RECOVERY_CONTROL_DB_PORT: 0\n",
    }, recovery_history, "historical effective migration no longer applied")
    for selector, value in (
        ("FIREMUD_RECOVERY_CONTROL_DB_USER", "packaged-user"),
        ("FIREMUD_RECOVERY_CONTROL_DB_PASSWORD", "packaged-password"),
    ):
        check(f"packaged Recovery credential selector {selector} cannot qualify history", {
            **recovery_history,
            recovery_config: recovery_settings + f"{selector}: {value}\n".encode(),
        }, recovery_history, "historical effective migration no longer applied")
    if ":${FIREMUD_RECOVERY_CONTROL_DB_PORT:5432}" not in RECOVERY_DATASOURCE_URL:
        raise AssertionError("Recovery datasource URL must retain its canonical 5432 fallback")
    check("global auto-configuration accepts ordinary Java-trim padding", {
        **recovery_history,
        recovery_config: recovery_settings + b"spring.boot.enable-auto-configuration: ' true '\n",
    }, recovery_history, None)
    check("global auto-configuration rejects non-Java whitespace padding", {
        **recovery_history,
        recovery_config: recovery_settings + "spring.boot.enable-auto-configuration: '\u00a0true\u00a0'\n".encode(),
    }, recovery_history, "historical effective migration no longer applied")
    recovery_java_migration = (
        RECOVERY_MAIN_JAVA_ROOT + "net/firedevops/firemud/recovery/config/RecoveryMigrationConfiguration.java"
    )
    sibling_migration = RECOVERY_MAIN_JAVA_ROOT + "net/firedevops/firemud/sibling/RecoveryMigration.java"
    check("Java comments and literals do not create migration declarations", {
        **recovery_history,
        recovery_java_migration: (
            b"// JavaMigration in a line comment\n"
            b"/* BaseJavaMigration in a block comment */\n"
            b"class RecoveryNotes {\n"
            b"  String ordinary = \"JavaMigration /* not a comment */\";\n"
            b"  String holder$JavaMigration = \"\";\n"
            b"  char character = 'x';\n"
            b"  String block = \"\"\"\n"
            b"      BaseJavaMigration // text block content\n"
            b"      \"\"\";\n"
            b"}\n"
        ),
    }, recovery_history, None)
    for raw, expected in (
        (r"class Na\uu006de {}", "class Name {}"),
        (r"// notes\u000aclass Name {}", "\nclass Name {}"),
        (r'String name = "\\u0022";', "String name =          ;"),
        (r"\u005cu004e", r"\u004e"),
    ):
        actual = java_code_tokens(raw)
        if actual.strip() != expected.strip():
            raise AssertionError(f"unexpected Java tokens for {raw!r}: {actual!r}")
    check("Recovery JavaMigration beans cannot override SQL version authority", {
        **recovery_history,
        recovery_java_migration: (
            b"import org.flywaydb.core.api.migration.JavaMigration;\n"
            b"@Bean JavaMigration recoveryMigration() { return migration; }\n"
        ),
    }, recovery_history, "JavaMigration classes and beans are unsupported")
    check("Recovery migration strategy cannot replace the default Flyway initializer", {
        **recovery_history,
        recovery_java_migration: (
            b"import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;\n"
            b"@Bean FlywayMigrationStrategy recoveryStrategy() { return migrationStrategy; }\n"
        ),
    }, recovery_history, "FlywayMigrationStrategy beans are unsupported")
    check("a Recovery bean cannot replace Boot-managed Flyway", {
        **recovery_history,
        f"{RECOVERY_MAIN_JAVA_ROOT}Override.java": (
            b"@Bean public org.flywaydb.core.Flyway embeddedFlyway() { return flywayFactory(); }"
        ),
    }, recovery_history, "Recovery replacement Flyway beans are unsupported")
    check("Boot-managed Flyway keeps the inspection guard and transactional callback eligible", {
        **recovery_history,
        f"{RECOVERY_CONFIG_JAVA_ROOT}RecoveryControllerOwnerConfiguration.java": (
            b"import org.flywaydb.core.Flyway;\n"
            b"import org.flywaydb.core.api.callback.Callback;\n"
            b"@Bean Callback recoveryTransactionalMigrationCallback() { return new RecoveryTransactionalMigrationCallback(); }\n"
            b"@Bean BeanPostProcessor guard() { return processor(); }\n"
            b"private boolean applies(Object bean) { return bean instanceof Flyway; }"
        ),
    }, recovery_history, None)
    check("extra Recovery Flyway callbacks cannot qualify SQL-only history", {
        **recovery_history,
        f"{RECOVERY_CONFIG_JAVA_ROOT}ExtraCallback.java": (
            b"import org.flywaydb.core.api.callback.BaseCallback;\n"
            b"class ExtraCallback extends BaseCallback {}"
        ),
    }, recovery_history, "only supports its transactional migration callback")
    check("Recovery Java migration in a sibling package cannot override SQL version authority", {
        **recovery_history,
        recovery_java_migration: (
            b"package net.firedevops.firemud.recovery.config;\n"
            b"import net.firedevops.firemud.sibling.RecoveryMigration;\n"
            b"import org.springframework.context.annotation.Bean;\n"
            b"class RecoveryMigrationConfiguration {\n"
            b"  @Bean RecoveryMigration recoveryMigration() { return new RecoveryMigration(); }\n"
            b"}\n"
        ),
        sibling_migration: (
            b"package net.firedevops.firemud.sibling;\n"
            b"import org.flywaydb.core.api.migration.BaseJavaMigration;\n"
            b"import org.flywaydb.core.api.migration.Context;\n"
            b"public class RecoveryMigration extends BaseJavaMigration {\n"
            b"  @Override public void migrate(Context context) {}\n"
            b"}\n"
        ),
    }, recovery_history, f"{sibling_migration}: Recovery JavaMigration classes and beans are unsupported")
    check("comma-delimited Flyway scalar trims its location entry", {
        **recovery_history,
        recovery_config: recovery_settings.replace(
            b"locations: classpath:db/recovery-control-migration",
            b"locations: ' classpath:db/recovery-control-migration '",
        ),
    }, recovery_history, None)
    for padding in ("\u00a0", "\u2003"):
        check("non-Java whitespace in scalar Flyway location is preserved", {
            **recovery_history,
            recovery_config: recovery_settings.replace(
                b"locations: classpath:db/recovery-control-migration",
                f"locations: '{padding}classpath:db/recovery-control-migration{padding}'".encode(),
            ),
        }, recovery_history, "historical effective migration no longer applied")
    for property_name in ("spring.datasource", "spring.datasource.hikari", "spring.flyway"):
        for driver, expected in (
            ("org.postgresql.Driver", None),
            ("org.h2.Driver", "historical effective migration no longer applied"),
            ("' org.postgresql.Driver '", "historical effective migration no longer applied"),
        ):
            check(f"Recovery driver override {property_name} = {driver}", {
                **recovery_history,
                recovery_config: recovery_settings + f"{property_name}.driver-class-name: {driver}\n".encode(),
            }, recovery_history, expected)
    check("Flyway version with empty description participates in duplicate detection", {
        **recovery_history,
        recovery + "V1__.sql": b"-- alternate",
    }, recovery_history, "duplicate version V1")
    empty_description_history = {
        recovery + "V1__.sql": b"-- journal",
        recovery_config: recovery_settings,
    }
    check("Flyway version with empty description participates in retained history", {
        **empty_description_history,
        recovery + "V1__.sql": b"-- changed journal",
    }, empty_description_history, "historical migration content changed")
    check("current recovery must carry its dedicated datasource URL", {
        **recovery_history,
        recovery_config: recovery_settings.removeprefix(b"spring.datasource.url: " + datasource_url + b"\n"),
    }, recovery_history, "historical effective migration no longer applied")
    check("legacy recovery source remains an eligible historical baseline", recovery_history, {
        **recovery_history,
        recovery_config: recovery_settings.removeprefix(b"spring.datasource.url: " + datasource_url + b"\n"),
    }, None)
    for hikari_override in (
        b"spring.datasource.hikari.data-source-properties.applicationName: override\n",
        b"spring:\n  datasource:\n    hikari:\n      data-source-properties:\n        applicationName: override\n",
    ):
        check("nonempty Hikari datasource properties cannot qualify recovery history", {
            **recovery_history,
            recovery_config.replace("/application.yml", "/config/application.yml"): hikari_override,
        }, recovery_history, "historical effective migration no longer applied")
    check("empty Hikari datasource properties leave recovery URL authoritative", {
        **recovery_history,
        recovery_config.replace("/application.yml", "/config/application.yml"):
            b"spring.datasource.hikari.data-source-properties: {}\n",
    }, recovery_history, None)
    for invalid_url in (
        b"jdbc:postgresql://localhost/firemud_recovery_control",
        datasource_url.replace(b"&preferQueryMode=extended", b""),
        datasource_url.replace(b"logServerErrorDetail=false", b"logServerErrorDetail=true"),
    ):
        check("noncanonical packaged URL cannot qualify recovery history", {
            **recovery_history,
            recovery_config: recovery_settings,
            recovery_config.replace("/application.yml", "/config/application.yml"):
                b"spring.datasource.url: " + invalid_url + b"\n",
        }, recovery_history, "historical effective migration no longer applied")
    check("higher-priority Hikari datasource class override cannot qualify recovery history", {
        **recovery_history,
        recovery_config: recovery_settings,
        recovery_config.replace("/application.yml", "/config/application.yml"):
            b"spring.datasource.hikari.data-source-class-name: org.postgresql.ds.PGSimpleDataSource\n",
    }, recovery_history, "historical effective migration no longer applied")
    check("matching Hikari JDBC URL override remains eligible", {
        **recovery_history,
        recovery_config: recovery_settings,
        recovery_config.replace("/application.yml", "/config/application.yml"):
            b"spring.datasource.hikari.jdbc-url: " + datasource_url + b"\n",
    }, recovery_history, None)
    check("divergent Hikari JDBC URL override is rejected", {
        **recovery_history,
        recovery_config: recovery_settings,
        recovery_config.replace("/application.yml", "/config/application.yml"):
            b"spring.datasource.hikari.jdbc-url: jdbc:postgresql://other/firemud_recovery_control\n",
    }, recovery_history, "historical effective migration no longer applied")
    for jndi_override in (
        b"  datasource:\n    jndi-name: java:comp/env/jdbc/override\n",
        b"  datasource:\n    hikari:\n      data-source-j-n-d-i: java:comp/env/jdbc/override\n",
    ):
        check("packaged JNDI datasource override cannot qualify recovery history", {
            **recovery_history,
            recovery_config: recovery_settings + jndi_override,
        }, recovery_history, "historical effective migration no longer applied")
    check("higher-priority JNDI datasource override cannot qualify recovery history", {
        **recovery_history,
        recovery_config.replace("/application.yml", "/config/application.yml"):
            b"spring.datasource.jndi-name: java:comp/env/jdbc/override\n",
    }, recovery_history, "historical effective migration no longer applied")
    invalid_recovery_root = {**recovery_history, recovery + "Vdraft__notes.txt": b"keep notes elsewhere"}
    check("a non-SQL Recovery input can break jOOQ sort before filtering", invalid_recovery_root,
          recovery_history, "Recovery migration sources must be SQL-only")
    check("non-SQL entries in the receiving parent remain invalid", recovery_history,
          invalid_recovery_root, "Recovery migration sources must be SQL-only")
    check("conflicting YAML scalar types at the same normalized path are ambiguous", {
        **recovery_history,
        recovery_config: recovery_settings + b"    enabled: 1\n    ENABLED: true\n",
    }, recovery_history, "historical effective migration no longer applied")
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
    for false_value in ("false", "OFF", "no", "0", " false "):
        check(f"Spring Boolean false placeholder replacement accepts {false_value!r}", {
            **recovery_history,
            recovery_config_override: f"spring.flyway.placeholder-replacement: '{false_value}'\n".encode(),
        }, recovery_history, None)
    for true_value in ("true", "ON", "yes", "1"):
        check(f"Spring Boolean true placeholder replacement is rejected: {true_value!r}", {
            **recovery_history,
            recovery_config_override: f"spring.flyway.placeholder-replacement: '{true_value}'\n".encode(),
        }, recovery_history, "historical effective migration no longer applied")
    check("higher-priority placeholder replacement cannot be enabled", {
        **recovery_history,
        recovery_config.replace("/application.yml", "/config/application.yml"):
            b"spring.flyway.placeholder-replacement: true\n",
    }, recovery_history, "historical effective migration no longer applied")
    for bad_delimiter in (
        b"spring.flyway.placeholder-prefix: '@${runtime}'\n",
        b"spring.flyway.placeholder-suffix: '${runtime}'\n",
    ):
        check("placeholder delimiters are validated even when replacement is disabled", {
            **recovery_history,
            recovery_config_override: bad_delimiter,
        }, recovery_history, "historical effective migration no longer applied")
    legacy_replacement_enabled = recovery_settings.replace(b"    placeholder-replacement: false\n", b"")
    check("disabling placeholder replacement preserves token-free SQL", recovery_history, {
        **recovery_history, recovery_config: legacy_replacement_enabled,
    }, None)
    for sql, delimiters in (
        (b'CREATE TABLE "${serviceSchema}" (id integer);', b""),
        (b'CREATE TABLE "@{serviceSchema}@" (id integer);',
         b"spring.flyway.placeholder-prefix: '@{'\nspring.flyway.placeholder-suffix: '}@'\n"),
    ):
        retained_token = {**recovery_history, recovery + "V1__journal.sql": sql}
        check("disabling placeholder replacement must preserve retained SQL meaning", retained_token, {
            **retained_token, recovery_config: legacy_replacement_enabled,
            recovery_config.replace("/application.yml", "/config/application.yml"): delimiters,
        }, "cannot disable Flyway placeholder replacement")
        check("an already literal token remains eligible", retained_token, retained_token, None)
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
    effective_recovery_config = recovery_flyway_config(recovery_saga_override, RECOVERY_PROJECT)
    effective_recovery_locations = effective_recovery_config.locations if effective_recovery_config else set()
    if effective_recovery_locations != {RECOVERY_LOCATION}:
        raise AssertionError(
            "higher-priority Recovery Flyway locations must replace root Saga locations for adoption: "
            f"{effective_recovery_locations}"
        )
    recovery_saga_sets = effective_sets(
        recovery_saga_override,
        discover_migration_files(recovery_saga_override),
        effective_recovery_locations,
        runtime_project_dependencies(recovery_saga_override),
    )
    if [item.path for item in recovery_saga_sets[RECOVERY_PROJECT]] != [recovery + "V1__journal.sql"]:
        raise AssertionError("overridden Recovery locations must not add common Saga migrations")
    inherited_recovery_parent = {
        "services/recovery-controller-service/build.gradle.kts":
            b'dependencies { implementation(project(":common-saga")) }',
        recovery_config: b"spring.flyway.locations: classpath:db/migration/saga\n",
        shared + "V1002__saga.sql": b"-- saga\n",
    }
    isolated_recovery_candidate = {**recovery_history,
                                   shared + "V1002__saga.sql": b"-- saga\n"}
    check("qualified Saga-only Recovery parent retains inherited versions",
          isolated_recovery_candidate, inherited_recovery_parent,
          "historical effective migration no longer applied")
    saga_root_input = shared.replace("/saga/", "/") + "V1003__root.sql"
    root_only_saga_parent = {
        path: data for path, data in inherited_recovery_parent.items()
        if path != shared + "V1002__saga.sql"
    }
    root_only_saga_parent[saga_root_input] = b"-- outside the selected nested location"
    check("Saga-only Recovery locations do not inherit SQL from the shared parent directory",
          {**root_only_saga_parent, **recovery_history}, root_only_saga_parent, None)
    check("unqualified Saga-only Recovery parent cannot lose inherited versions",
          isolated_recovery_candidate, {
              **inherited_recovery_parent,
              recovery_config.removesuffix(".yml") + ".properties": b"server.port=9000\n",
          }, "cannot qualify the receiving parent's packaged Flyway configuration")
    check("first Recovery migrations may be introduced without parent history",
          isolated_recovery_candidate, {
              **inherited_recovery_parent,
              "services/recovery-controller-service/build.gradle.kts":
                  b'dependencies { testImplementation(project(":common-saga")) }',
              recovery_config.removesuffix(".yml") + ".properties": b"server.port=9000\n",
          }, None)
    legacy_recovery_sql = f"{RECOVERY_RESOURCE_ROOT}/db/migration/"
    sibling_migrations = {
        legacy_recovery_sql + "V8__root.sql": b"-- root\n",
        legacy_recovery_sql + "saga-other/V9__sibling.sql": b"-- sibling\n",
    }
    saga_only_recovery_parent = {
        **sibling_migrations,
        recovery_config: b"spring.flyway.locations: classpath:db/migration/saga\n",
    }
    owned_migration_path = legacy_recovery_sql + "saga/nested/V7__owned.sql"
    owned_migration = {owned_migration_path: b"-- owned\n"}
    parent_with_owned_migration = {**saga_only_recovery_parent, **owned_migration}
    effective_parent = effective_sets(
        parent_with_owned_migration,
        discover_migration_files(parent_with_owned_migration),
        {"classpath:db/migration/saga"},
        {},
    )
    if [item.path for item in effective_parent[RECOVERY_PROJECT]] != [owned_migration_path]:
        raise AssertionError("legacy Saga-only location must load only the owned migration beneath it")
    check("legacy Saga-only location preserves effective Recovery-owned history",
          {**recovery_history, **sibling_migrations, **owned_migration}, parent_with_owned_migration,
          "historical effective migration no longer applied")
    check("legacy Saga-only location has no effective history from sibling migrations",
          {**recovery_history, **sibling_migrations}, saga_only_recovery_parent, None)
    library = "services/shared-journal-inputs/src/main/resources/db/migration/"
    direct_input = library + "internal/V23__direct.sql"
    transitive_input = "services/nested-journal-inputs/src/main/resources/db/migration/internal/V32__nested.sql"
    sibling_input = library + "internal-other/V90__unselected.sql"
    shared_recovery_parent = {
        f"services/{RECOVERY_PROJECT}/build.gradle.kts":
            b'dependencies { runtimeOnly(project(":shared-journal-inputs")) }',
        "services/shared-journal-inputs/build.gradle.kts":
            b'dependencies { api(project(":nested-journal-inputs")) }',
        recovery_config: b"spring.flyway.locations: classpath:db/migration/internal\n",
        direct_input: b"-- direct",
        transitive_input: b"-- transitive",
        sibling_input: b"-- unselected sibling",
    }
    dedicated_candidate = {**shared_recovery_parent, **recovery_history}
    shared_issues, _, _ = collect_issues(dedicated_candidate, shared_recovery_parent)
    if (not any(f"historical effective migration no longer applied: {direct_input}" in issue
                for issue in shared_issues)
            or not any(f"historical effective migration no longer applied: {transitive_input}" in issue
                       for issue in shared_issues)
            or not any("effective parent frontier V32" in issue for issue in shared_issues)
            or any(sibling_input in issue for issue in shared_issues)):
        raise AssertionError(f"selected dependency migrations must define the Recovery parent history: {shared_issues}")
    check("the dedicated location excludes legacy dependency migrations",
          dedicated_candidate, dedicated_candidate, None)
    platform_input = "services/common-platform-core/src/main/resources/db/migration/V1002__shared.sql"
    platform_recovery_parent = {
        f"services/{RECOVERY_PROJECT}/build.gradle.kts":
            b'dependencies { implementation(project(":common-platform-core")) }',
        recovery_config: b"spring.flyway.locations: classpath:db/migration\n",
        platform_input: b"-- platform",
    }
    platform_candidate = {**platform_recovery_parent, **recovery_history}
    platform_issues, _, _ = collect_issues(platform_candidate, platform_recovery_parent)
    if (not any(f"historical effective migration no longer applied: {platform_input}" in issue
                for issue in platform_issues)
            or not any("effective parent frontier V1002" in issue for issue in platform_issues)):
        raise AssertionError(f"platform SQL alone must establish the Recovery parent history: {platform_issues}")
    check("an ambiguous production project declaration cannot qualify the receiving parent's history",
          dedicated_candidate, {
              **shared_recovery_parent,
              f"services/{RECOVERY_PROJECT}/build.gradle.kts":
                  b'dependencies { runtimeOnly(project(path = ":shared-journal-inputs")) }',
          }, "cannot resolve Recovery production runtime dependency")
    check("a customized project dependency cannot qualify transitive Recovery history",
          dedicated_candidate, {
              **shared_recovery_parent,
              f"services/{RECOVERY_PROJECT}/build.gradle.kts":
                  b'dependencies { runtimeOnly(project(":shared-journal-inputs")) { isTransitive = false } }',
          }, "cannot resolve Recovery production runtime dependency")
    commented_dependencies = {
        **shared_recovery_parent,
        f"services/{RECOVERY_PROJECT}/build.gradle.kts": (
            b'val description = "literal // and /* delimiters";\n'
            b'val notes = """Sample: runtimeOnly(project(path = ":other")) and api(project(":common-saga"))""";\n'
            b'val example = "runtimeOnly(project(path = \\":example-service\\"))";\n'
            b'dependencies { /* legal comment */ runtimeOnly(project(":shared-journal-inputs")) }\n'
            b'// implementation(project(path = ":common-saga"))\n'
            b'/* outer /* nested */ implementation(project(":common-saga")) */\n'
        ),
    }
    dependencies = runtime_project_dependencies(commented_dependencies)[RECOVERY_PROJECT]
    if dependencies != {"shared-journal-inputs", "nested-journal-inputs"}:
        raise AssertionError(f"comments changed the resolved Recovery dependency graph: {dependencies}")
    check("commented Recovery project declarations preserve qualification",
          {**commented_dependencies, **recovery_history}, dedicated_candidate, None)
    quoted_dependency_parent = {
        **shared_recovery_parent,
        f"services/{RECOVERY_PROJECT}/build.gradle.kts": (
            b'dependencies {\n'
            b'  "runtimeOnly"(project(":shared-journal-inputs"))\n'
            b'  "testImplementation"(project(":common-saga"))\n'
            b'}\n'
        ),
    }
    check("quoted production runtime wiring preserves the inherited migration frontier",
          dedicated_candidate, quoted_dependency_parent, "effective parent frontier V32")
    for call in (b'add("runtimeOnly",', b'add ("runtimeOnly",'):
        check("Gradle add preserves the inherited migration frontier with optional whitespace",
              dedicated_candidate, {
                  **shared_recovery_parent,
                  f"services/{RECOVERY_PROJECT}/build.gradle.kts":
                      b'dependencies { ' + call + b' project(":shared-journal-inputs")) }',
              }, "effective parent frontier V32")
    check("an unsupported active quoted runtime declaration fails qualification", dedicated_candidate, {
        **shared_recovery_parent,
        f"services/{RECOVERY_PROJECT}/build.gradle.kts":
            b'dependencies { "runtimeOnly"(project(path = ":shared-journal-inputs")) }',
    }, "cannot resolve Recovery production runtime dependency")
    dependency_config = "services/nested-journal-inputs/src/main/resources/config/application.yml"
    dependency_settings = {dependency_config: b"spring.application.name: shared-journal-inputs\n"}
    check("default configuration in a transitive Recovery dependency cannot qualify the package", {
        **dedicated_candidate, **dependency_settings,
    }, dedicated_candidate, "Recovery runtime dependency publishes default packaged Spring configuration")
    check("a test-only project's default configuration is outside the production package", {
        **recovery_history, **dependency_settings,
        f"services/{RECOVERY_PROJECT}/build.gradle.kts":
            b'dependencies { testImplementation(project(":nested-journal-inputs")) }',
    }, recovery_history, None)
    check("explicitly empty Flyway connection init SQL qualifies recovery history", {
        **recovery_history,
        recovery_config: recovery_settings + b"    init-sqls: []\n",
    }, recovery_history, None)
    check("nonblank effective Hikari initialization SQL cannot qualify recovery history", {
        **recovery_history,
        recovery_config_override: b"spring.datasource.hikari.connection-init-sql: SET application_name = fixture\n",
    }, recovery_history, "historical effective migration no longer applied")
    check("blank Hikari initialization SQL does not change recovery migration history", {
        **recovery_history,
        recovery_config_override: b"spring.datasource.hikari.connection-init-sql: '   '\n",
    }, recovery_history, None)
    check("non-Java whitespace Hikari SQL is nonempty to the Java startup guard", {
        **recovery_history,
        recovery_config_override: "spring.datasource.hikari.connection-init-sql: '\u00a0'\n".encode(),
    }, recovery_history, "historical effective migration no longer applied")
    for setting in ("init-sqls", "callback-locations"):
        check(f"higher-priority empty Flyway {setting} replaces a non-empty root list", {
            **recovery_history,
            recovery_config: recovery_settings + f"    {setting}: [unsafe]\n".encode(),
            recovery_config_override: f"spring.flyway.{setting}: []\n".encode(),
        }, recovery_history, None if setting == "init-sqls" else "callback locations")
        check(f"effective non-empty Flyway {setting} remains unsupported", {
            **recovery_history,
            recovery_config: recovery_settings + f"    {setting}: []\n".encode(),
            recovery_config_override: f"spring.flyway.{setting}: [unsafe]\n".encode(),
        }, recovery_history, "non-empty connection init SQL" if setting == "init-sqls" else "callback locations")
    check("higher-priority null Flyway init SQL clears a non-empty root list", {
        **recovery_history,
        recovery_config: recovery_settings + b"    init-sqls: [SET application_name = fixture]\n",
        recovery_config_override: b"spring.flyway.init-sqls:\n",
    }, recovery_history, None)
    check("higher-priority empty Flyway init SQL replaces an indexed root list", {
        **recovery_history,
        recovery_config: recovery_settings + b"    init-sqls[0]: SET application_name = fixture\n",
        recovery_config_override: b"spring.flyway.init-sqls: []\n",
    }, recovery_history, None)
    check("higher-priority indexed Flyway init SQL remains unsupported", {
        **recovery_history,
        recovery_config: recovery_settings + b"    init-sqls: []\n",
        recovery_config_override: b"spring.flyway.init-sqls[0]: SET application_name = fixture\n",
    }, recovery_history, "non-empty connection init SQL")
    for property_name, value, result in (
        ("spring.datasource.type", "com.zaxxer.hikari.HikariDataSource", None),
        ("spring.datasource.type", "org.springframework.jdbc.datasource.DriverManagerDataSource",
         "historical effective migration no longer applied"),
        ("spring.datasource.username", "${FIREMUD_RECOVERY_CONTROL_DB_USER}", None),
        ("spring.datasource.password", "${FIREMUD_RECOVERY_CONTROL_DB_PASSWORD}", None),
        ("spring.datasource.hikari.username", "${FIREMUD_RECOVERY_CONTROL_DB_USER}", None),
        ("spring.datasource.hikari.password", "${FIREMUD_RECOVERY_CONTROL_DB_PASSWORD}", None),
        ("spring.datasource.username", "literal-test-user", "historical effective migration no longer applied"),
        ("spring.datasource.password", "${OTHER_FIXTURE_PASSWORD}", "historical effective migration no longer applied"),
        ("spring.datasource.hikari.username", "${OTHER_FIXTURE_USER}", "historical effective migration no longer applied"),
        ("spring.datasource.hikari.password", "literal-test-value", "historical effective migration no longer applied"),
        ("spring.flyway.validate-on-migrate", "false", "historical effective migration no longer applied"),
        ("spring.flyway.connect-retries", "-1", "historical effective migration no longer applied"),
        ("spring.flyway.connect-retries", "0", None),
        ("spring.flyway.connect-retries", "3", None),
        ("spring.flyway.connect-retries-interval", "-1s", "historical effective migration no longer applied"),
        ("spring.flyway.connect-retries-interval", "3s", None),
        ("spring.flyway.connect-retries-interval", "2147483648s", "historical effective migration no longer applied"),
        ("spring.flyway.lock-retry-count", "-1", "historical effective migration no longer applied"),
        ("spring.flyway.lock-retry-count", "0", None),
        ("spring.flyway.lock-retry-count", "'2147483648'", "historical effective migration no longer applied"),
        ("spring.flyway.lock-retry-count", "true", "historical effective migration no longer applied"),
        ("spring.flyway.tablespace", "recovery_fast", "historical effective migration no longer applied"),
        ("spring.flyway.group", "true", "historical effective migration no longer applied"),
        ("spring.flyway.group", "false", None),
        ("spring.datasource.hikari.connection-test-query", "SELECT 1",
         "historical effective migration no longer applied"),
    ):
        check(f"effective Recovery guard {property_name}", {
            **recovery_history,
            recovery_config_override: f"{property_name}: {value}\n".encode(),
        }, recovery_history, result)
    check("explicitly empty Flyway callback list is unsupported", {
        **recovery_history,
        recovery_config: recovery_settings + b"    callback-locations: []\n",
    }, recovery_history, "callback locations")
    for override in (
        b"    init-sqls: [SET application_name = fixture]\n",
        b"    init-sqls: SET application_name = fixture\n",
        b"spring.flyway.init-sqls[0]: SET application_name = fixture\n",
    ):
        check("Flyway connection init SQL is unsupported", {
            **recovery_history, recovery_config: recovery_settings + override,
        }, recovery_history, "non-empty connection init SQL")
    check("bracketed Flyway location index text is preserved", {
        **recovery_history,
        recovery_config_override: b"spring.flyway.locations[0_]: classpath:db/recovery-control-migration\n",
    }, recovery_history, "historical effective migration no longer applied")
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
    check("scalar Recovery schema uses Java trim semantics", {
        **recovery_history,
        recovery_override_config: b"spring.flyway.schemas: ' \trecovery_control\t '\n",
    }, recovery_history, None)
    check("non-Java whitespace in scalar Recovery schema remains significant", {
        **recovery_history,
        recovery_override_config: "spring.flyway.schemas: '\u00a0recovery_control'\n".encode(),
    }, recovery_history, "historical effective migration no longer applied")
    check("indexed Recovery schema override remains eligible", {
        **recovery_history,
        recovery_override_config: b"spring.flyway.schemas[0]: recovery_control\n",
    }, recovery_history, None)
    for malformed_schema_override in (
        b"spring.flyway.schemas[1]: recovery_control\n",
        b"spring.flyway.schemas[0]: public\nspring.flyway.schemas[1]: recovery_control\n",
        b"spring.flyway.schemas.0: recovery_control\n",
    ):
        check("malformed or noncanonical indexed Recovery schema is rejected", {
            **recovery_history,
            recovery_override_config: malformed_schema_override,
        }, recovery_history, "historical effective migration no longer applied")
    check("integer-key Recovery schema list binds through the indexed-list decoder", {
        **recovery_history,
        recovery_override_config: b"spring.flyway.schemas: {0: recovery_control}\n",
    }, recovery_history, None)
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
        datasource_setting + b"spring:\n  flyway:\n    enabled: true\n    locations:\n"
        b"      - classpath:db/recovery-control-migration\n" + recovery_table_setting
    )
    yml_locations_override = b"spring:\n  flyway:\n    locations:\n      - classpath:db/migration\n"
    check("application.yml replaces application.yaml locations list", {
        recovery + "V1__journal.sql": b"-- journal",
        yaml_config: recovery_list_settings,
        recovery_config: yml_locations_override,
    }, recovery_history, "historical effective migration no longer applied")
    indexed_locations_config = recovery_config.replace("/application.yml", "/config/application.yml")
    ordinary_locations_settings = recovery_settings.replace(
        b"classpath:db/recovery-control-migration", b"classpath:db/migration"
    )
    check("indexed Recovery location replaces lower-priority scalar locations", {
        **recovery_history,
        recovery_config: ordinary_locations_settings,
        indexed_locations_config:
            b"spring.flyway.locations[0]: classpath:db/recovery-control-migration\n",
    }, recovery_history, None)
    check("indexed Recovery SQL suffix binds the canonical single .sql entry", {
        **recovery_history,
        indexed_locations_config: b"spring.flyway.sql-migration-suffixes[0]: .sql\n",
    }, recovery_history, None)
    for malformed_suffixes in (
        b"spring.flyway.sql-migration-suffixes[1]: .sql\n",
        b"spring.flyway.sql-migration-suffixes[0]: .sql\nspring.flyway.sql-migration-suffixes[2]: .sql\n",
        b"spring.flyway.sql-migration-suffixes.0: .sql\n",
        b"spring.flyway.sql-migration-suffixes[0]: .ddl\n",
    ):
        check("malformed or noncanonical indexed Recovery SQL suffix is rejected", {
            **recovery_history,
            indexed_locations_config: malformed_suffixes,
        }, recovery_history, "historical effective migration no longer applied")
    check("numeric YAML Recovery index replaces lower-priority scalar locations", {
        **recovery_history,
        recovery_config: ordinary_locations_settings,
        indexed_locations_config:
            b"spring.flyway.locations: {0: 'classpath:db/recovery-control-migration'}\n",
    }, recovery_history, None)
    check("bracketed YAML Recovery index replaces lower-priority scalar locations", {
        **recovery_history,
        recovery_config: ordinary_locations_settings,
        indexed_locations_config:
            b"spring.flyway.locations: {'[0]': 'classpath:db/recovery-control-migration'}\n",
    }, recovery_history, None)
    mixed_index_sources = {
        **recovery_history,
        indexed_locations_config: (
            b"spring.flyway.locations: {0: classpath:db/recovery-control-migration}\n"
            b"spring.flyway.locations[1]: classpath:db/migration\n"
        ),
    }
    mixed_index_config = recovery_flyway_config(mixed_index_sources, RECOVERY_PROJECT)
    if mixed_index_config is None or mixed_index_config.locations != {RECOVERY_LOCATION, "classpath:db/migration"}:
        raise AssertionError("disjoint numeric YAML and bracketed indices must bind together")
    check("higher-priority scalar replaces lower-priority numeric YAML locations", {
        **recovery_history,
        recovery_config: recovery_settings.replace(
            b"locations: classpath:db/recovery-control-migration",
            b"locations: {0: classpath:db/migration}",
        ),
        indexed_locations_config: b"spring.flyway.locations: classpath:db/recovery-control-migration\n",
    }, recovery_history, None)
    for driver, expected in (
        ("org.postgresql.Driver", None),
        ("org.postgresql.Drvier", "historical effective migration no longer applied"),
    ):
        check("higher-priority explicit Recovery driver qualifies only PostgreSQL", {
            **recovery_history,
            indexed_locations_config: f"spring.datasource.driver-class-name: {driver}\n".encode(),
        }, recovery_history, expected)
    for malformed_indexed_locations in (
        b"spring.flyway.locations: {'0': classpath:db/recovery-control-migration}\n",
        b"spring.flyway.locations: {1: classpath:db/recovery-control-migration}\n",
        b"spring.flyway.locations: {true: classpath:db/recovery-control-migration}\n",
        b"spring.flyway.locations: {0.0: classpath:db/recovery-control-migration}\n",
        b"spring.flyway.locations: {0: {path: classpath:db/recovery-control-migration}}\n",
        b"spring.flyway.locations: {0: classpath:db/recovery-control-migration, true: extra}\n",
        (
            b"spring.flyway.locations: {0: classpath:db/migration}\n"
            b"spring.flyway.locations[0]: classpath:db/recovery-control-migration\n"
        ),
        b"spring.flyway.locations[1]: classpath:db/recovery-control-migration\n",
        (
            b"spring.flyway.locations[0]: classpath:db/recovery-control-migration\n"
            b"spring.flyway.locations[2]: classpath:db/migration\n"
        ),
        b"spring.flyway.locations.0: classpath:db/recovery-control-migration\n",
        (
            b"spring.flyway.locations: classpath:db/recovery-control-migration\n"
            b"spring.flyway.locations[0]: classpath:db/recovery-control-migration\n"
        ),
    ):
        check("ambiguous indexed Recovery locations are rejected", {
            **recovery_history, indexed_locations_config: malformed_indexed_locations,
        }, recovery_history, "historical effective migration no longer applied")
    check("equivalent enabled list recovery history",
          {**recovery_history, recovery_config: recovery_list_settings}, recovery_history, None)
    check("padded list-valued Recovery Flyway location is rejected", {
        **recovery_history,
        recovery_config: recovery_list_settings.replace(
            b"      - classpath:db/recovery-control-migration",
            b"      - ' classpath:db/recovery-control-migration '",
        ),
    }, recovery_history, "historical effective migration no longer applied")
    check("duplicate recovery locations cannot qualify enabled history", {
        **recovery_history,
        recovery_config: recovery_list_settings.replace(
            b"      - classpath:db/recovery-control-migration\n",
            b"      - classpath:db/recovery-control-migration\n"
            b"      - classpath:db/recovery-control-migration\n",
        ),
    }, recovery_history, "historical effective migration no longer applied")
    check("duplicate comma-separated recovery locations cannot qualify enabled history", {
        **recovery_history,
        recovery_config: recovery_settings.replace(
            b"locations: classpath:db/recovery-control-migration",
            b"locations: classpath:db/recovery-control-migration, classpath:db/recovery-control-migration",
        ),
    }, recovery_history, "historical effective migration no longer applied")
    check("malformed late locations entry cannot be hidden by a matching entry", {
        **recovery_history,
        recovery_config: recovery_list_settings.replace(
            b"      - classpath:db/recovery-control-migration\n",
            b"      - classpath:db/recovery-control-migration\n"
            b"      - path: classpath:db/migration\n",
        ),
    }, recovery_history, "historical effective migration no longer applied")
    for flag in ("baseline-on-migrate", "detect-encoding", "skip-default-resolvers", "skip-executing-migrations"):
        for raw, accepted in (
            ("' FaLsE '", True), ("' off '", True), ("'no'", True), ("'0'", True),
            ("'yes'", False), ("0", False), ("'\u00a0off\u00a0'", False), ("${RECOVERY_FLAG:false}", False),
        ):
            check(f"effective Flyway {flag} = {raw}", {
                **recovery_history,
                recovery_config: recovery_settings + f"    {flag}: true\n".encode(),
                indexed_locations_config: f"spring.flyway.{flag}: {raw}\n".encode(),
            }, recovery_history, None if accepted else "historical effective migration no longer applied")
    check("mixed comma-separated locations cannot qualify the isolated recovery journal", {
        **recovery_history,
        recovery_config: recovery_settings.replace(
            b"locations: classpath:db/recovery-control-migration",
            b"locations: classpath:db/migration,classpath:db/recovery-control-migration",
        ),
    }, recovery_history, "packaged Spring configuration must enable Flyway")
    redis_exclusion_settings = (
        datasource_setting + b"spring:\n  autoconfigure:\n    exclude:\n"
        b"      - org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration\n"
        b"  flyway:\n    locations: classpath:db/recovery-control-migration\n" + recovery_table_setting
    )
    check("Redis-only autoconfiguration exclusion preserves recovery Flyway", {
        **recovery_history, recovery_config: redis_exclusion_settings,
    }, recovery_history, None)
    check("excluding the Recovery owner configuration prevents qualification", {
        **recovery_history,
        recovery_config: redis_exclusion_settings.replace(
            b"org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration",
            b"net.firedevops.firemud.recovery.config.RecoveryControllerOwnerConfiguration",
        ),
    }, recovery_history, "packaged Spring configuration must enable Flyway")
    check("higher-priority Redis-only exclusion replaces root Flyway exclusion", {
        **recovery_history,
        recovery_config: (
            datasource_setting + b"spring:\n  autoconfigure:\n    exclude:\n"
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
    check("a direct null exclusion clears the lower-priority Flyway exclusion", {
        **recovery_history,
        recovery_config: root_flyway_exclusion,
        recovery_config.replace("/application.yml", "/config/application.yml"):
            b"spring.autoconfigure.exclude:\n",
    }, recovery_history, None)
    redis_auto_configuration = b"org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration"
    for exclusion_key, expected in (
        (b"spring.autoconfigure.exclude[0]: ", None),
        (b"spring.autoconfigure.exclude:\n  '[0]': ", None),
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
            datasource_setting + b"spring:\n  autoconfigure:\n    exclude:\n"
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
            datasource_setting + b"spring:\n  autoconfigure:\n    exclude:\n"
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
            datasource_setting + b"spring:\n  autoconfigure:\n    exclude:\n"
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
    check("obsolete scalar spring.profiles fails even in an unrelated YAML document", {
        **recovery_history,
        recovery_config: recovery_settings + b"---\nspring.profiles: dev\n",
    }, recovery_history, "historical effective migration no longer applied")
    for obsolete_profiles in (
        b"spring.profiles[0]: dev\n",
        b"spring:\n  profiles:\n    '0': dev\n",
        b"spring:\n  profiles:\n    0: dev\n",
    ):
        check("obsolete indexed spring.profiles fails before unrelated-document skip", {
            **recovery_history,
            recovery_config: recovery_settings + b"---\n" + obsolete_profiles,
        }, recovery_history, "historical effective migration no longer applied")
    check("modern spring.profiles properties and unrelated YAML remain eligible", {
        **recovery_history,
        recovery_config: recovery_settings + (
            b"---\nspring:\n  profiles:\n    active: dev\n    default: local\n"
            b"    include: ops\n    group:\n      dev: local\nserver:\n  port: 9000\n"
        ),
    }, recovery_history, None)
    profile_config = recovery_config.replace("application.yml", "application-dev.yml")
    configured_profile = {
        **recovery_history,
        recovery_config: recovery_settings + b"spring.profiles.active: dev\n",
        profile_config: b"server.port: 9000\n",
    }
    check("base profile selection with an unrelated profile-specific setting",
          configured_profile, recovery_history, None)
    for setting, error_property in (
        (b"spring.profiles.active: ops\n", "active"),
        (b"spring.profiles.default[0]: local\n", "default[0]"),
        (b"spring:\n  profiles:\n    include: [ops]\n", "include"),
        (b"spring.profiles.include:\n  0: ops\n", "include"),
    ):
        base_document = configured_profile[recovery_config]
        for config, data in (
            (profile_config, setting),
            (recovery_config, base_document + b"---\nspring.config.activate.on-profile: dev\n" + setting),
            (recovery_config, base_document + b"---\nspring.config.activate.on-cloud-platform: kubernetes\n" + setting),
        ):
            invalid_profile = {**configured_profile, config: data}
            for current, parent in ((invalid_profile, recovery_history), (recovery_history, invalid_profile)):
                try:
                    collect_issues(current, parent)
                except ValueError as error:
                    if str(error) != (f"{config}: spring.profiles.{error_property} "
                                      "is invalid in profile-specific Spring configuration"):
                        raise
                else:
                    raise AssertionError(f"profile-specific setting was accepted: {setting!r}")
    check("Flyway SQL suffix override cannot disconnect the recovery journal", {
        **recovery_history,
        recovery_config: recovery_settings + b"    sql-migration-suffixes: [.ddl]\n",
    }, recovery_history, "packaged Spring configuration must enable Flyway")
    check("scalar Flyway SQL suffix uses Java trim semantics", {
        **recovery_history,
        recovery_override_config: b"spring.flyway.sql-migration-suffixes: ' \t.sql\t '\n",
    }, recovery_history, None)
    check("non-Java whitespace in scalar Flyway SQL suffix remains significant", {
        **recovery_history,
        recovery_override_config: "spring.flyway.sql-migration-suffixes: '\u00a0.sql'\n".encode(),
    }, recovery_history, "historical effective migration no longer applied")
    check("YAML-list Flyway SQL suffix entries remain exact", {
        **recovery_history,
        recovery_override_config: b"spring.flyway.sql-migration-suffixes: [' .sql ']\n",
    }, recovery_history, "historical effective migration no longer applied")
    check("default Flyway SQL resolvers remain enabled for the recovery journal", {
        **recovery_history,
        recovery_config: recovery_settings + b"    skip-default-resolvers: true\n",
    }, recovery_history, "packaged Spring configuration must enable Flyway")
    check("explicitly retaining Flyway SQL resolvers qualifies recovery history", {
        **recovery_history,
        recovery_config: recovery_settings + b"    skip-default-resolvers: false\n",
    }, recovery_history, None)
    check("Flyway requires transactions for Recovery migrations", {
        **recovery_history,
        recovery_config: recovery_settings + b"    execute-in-transaction: false\n",
    }, recovery_history, "apply UTF-8 migrations in transactions")
    check("explicit Flyway transaction execution qualifies recovery history", {
        **recovery_history,
        recovery_config: recovery_settings + b"    execute-in-transaction: true\n",
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
    for alias in ("UTF8", "unicode-1-1-utf-8"):
        check("Java UTF-8 charset alias qualifies recovery history", {
            **recovery_history,
            recovery_config: recovery_settings + f"    encoding: {alias}\n".encode(),
        }, recovery_history, None)
    for ignored_patterns in ("['versioned:*']", "['*:missing']", "['*:future', '*:pending']"):
        check("custom Flyway ignore patterns cannot disable applied checksum validation", {
            **recovery_history,
            recovery_config: recovery_settings + f"    ignore-migration-patterns: {ignored_patterns}\n".encode(),
        }, recovery_history, "historical effective migration no longer applied")
    for ignored_patterns in ("[]", "['*:future']", "['versioned:future']"):
        check("Flyway ignore pattern leaves applied checksum validation intact", {
            **recovery_history,
            recovery_config: recovery_settings + f"    ignore-migration-patterns: {ignored_patterns}\n".encode(),
        }, recovery_history, None)
    check("indexed default future-only Flyway ignore pattern preserves checksum validation", {
        **recovery_history,
        recovery_config_override: b"spring.flyway.ignore-migration-patterns[0]: '*:future'\n",
    }, recovery_history, None)
    check("integer-key default future-only ignore pattern binds through the indexed-list decoder", {
        **recovery_history,
        recovery_config_override: b"spring.flyway.ignore-migration-patterns: {0: '*:future'}\n",
    }, recovery_history, None)
    for malformed_ignore_patterns in (
        b"spring.flyway.ignore-migration-patterns[1]: '*:future'\n",
        b"spring.flyway.ignore-migration-patterns.0: '*:future'\n",
        b"spring.flyway.ignore-migration-patterns[0]: '*:missing'\n",
    ):
        check("malformed indexed ignore pattern cannot qualify Recovery history", {
            **recovery_history,
            recovery_config_override: malformed_ignore_patterns,
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
        recovery_config: datasource_setting + b"spring:\n  flyway:\n    enabled: true\n    placeholder-replacement: false\n",
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
        datasource_setting + b"spring.flyway.enabled: true\n"
        b"spring.flyway.locations: classpath:db/recovery-control-migration\n"
        b"spring.flyway.schemas: [recovery_control]\n"
        b"spring.flyway.default-schema: recovery_control\n"
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
    for omitted in (b"    schemas: recovery_control\n", b"    default-schema: recovery_control\n"):
        check("Recovery Flyway requires explicit recovery_control schema binding", {
            **recovery_history, recovery_config: recovery_settings.replace(omitted, b""),
        }, recovery_history, "historical effective migration no longer applied")
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
              b"    enabled: false\n---\nspring.flyway.enabled: true\n"
          )}, recovery_history, None)
    check("plain YAML yes enables Flyway", {
        **recovery_history,
        recovery_config: recovery_settings + b"    enabled: yes\n",
    }, recovery_history, None)
    check("quoted mixed-case Flyway enablement is not the runtime literal true", {
        **recovery_history,
        recovery_config: recovery_settings + b"    enabled: 'TrUe'\n",
    }, recovery_history, "historical effective migration no longer applied")
    check("explicitly disabled Flyway mixed mode", {
        **recovery_history,
        recovery_config: recovery_settings + b"    mixed: false\n",
    }, recovery_history, None)
    check("Flyway mixed mode cannot qualify transactional recovery migrations", {
        **recovery_history,
        recovery_config: recovery_settings + b"    mixed: true\n",
    }, recovery_history, "historical effective migration no longer applied")
    check("higher-priority Flyway mixed mode override cannot qualify recovery migrations", {
        **recovery_history,
        recovery_config: recovery_settings + b"    mixed: false\n",
        recovery_config.replace("/application.yml", "/config/application.yml"):
            b"spring.flyway.mixed: true\n",
    }, recovery_history, "historical effective migration no longer applied")
    check("unsupported Flyway mixed mode value fails closed", {
        **recovery_history,
        recovery_config: recovery_settings + b"    mixed: maybe\n",
    }, recovery_history, "historical effective migration no longer applied")
    for nonmatching_value in (b"yes", b"on", b"1", b"TRUE", b"True"):
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
    check("jOOQ-style backslash paths cannot hide flat Recovery migrations", {
        **complete_recovery_history, recovery + r"V3__generation\nested.sql": b"-- generation",
    }, complete_recovery_history, "recovery migration SQL must be flat")
    for length in (RECOVERY_HISTORY_VERSION_LIMIT, RECOVERY_HISTORY_VERSION_LIMIT + 1):
        version = "0" * (length - 3) + "3_1"
        check(f"Recovery version display text has {length} characters", {
            **complete_recovery_history, recovery + f"V{version}__width.sql": b"-- version width",
        }, complete_recovery_history, None if length == RECOVERY_HISTORY_VERSION_LIMIT else
            "Recovery Flyway version must fit the 50-character history column")
    reserved = {**complete_recovery_history,
                recovery + f"V{RECOVERY_RESERVED_FLYWAY_VERSION}__reserved.sql": b"-- version marker"}
    check("Flyway's numeric LATEST marker is reserved", reserved, complete_recovery_history,
          "is reserved for Flyway's LATEST marker")
    check("the receiving parent cannot contain Flyway's numeric LATEST marker",
          complete_recovery_history, reserved, "is reserved for Flyway's LATEST marker")
    for version in ("9223372036854775806", "9223372036854775808", "09223372036854775807"):
        check(f"ordinary numeric version {version} is eligible", {
            **complete_recovery_history, recovery + f"V{version}__ordinary.sql": b"-- numeric version",
        }, complete_recovery_history, None)
    check("ordinary flat underscore Recovery filenames remain supported", {
        **complete_recovery_history, recovery + "V3__generation_notes.sql": b"-- generation",
    }, complete_recovery_history, None)
    check("explicit mapping override of a merged default is not a duplicate", {
        **complete_recovery_history, recovery_config: (
            b"defaults: &fallback\n  enabled: false\n" + recovery_settings
            + b"    <<: *fallback\n    enabled: true\n"
        ),
    }, complete_recovery_history, None)
    check("reused merged mapping preserves its authored override", {
        **complete_recovery_history, recovery_config: (
            datasource_setting + b"defaults: &fallback {enabled: false}\n"
            b"qualified: &qualified {<<: *fallback, enabled: true}\n"
            b"spring:\n  flyway:\n    <<: *qualified\n"
            b"    locations: classpath:db/recovery-control-migration\n" + recovery_table_setting
        ),
    }, complete_recovery_history, None)
    try:
        collect_issues({
            **complete_recovery_history,
            recovery_config: b"loop: &loop {<<: *loop}\n" + recovery_settings,
        }, complete_recovery_history)
    except ValueError as error:
        if "recursive YAML merge aliases are unsupported" not in str(error) or recovery_config not in str(error):
            raise
    else:
        raise AssertionError("recursive YAML merge alias did not fail closed during construction")
    check("a single-source merge sequence preserves explicit overrides", {
        **complete_recovery_history, recovery_config: (
            b"defaults: &fallback {enabled: false}\n" + recovery_settings
            + b"    <<: [*fallback]\n    enabled: true\n"
        ),
    }, complete_recovery_history, None)
    try:
        collect_issues({
            **complete_recovery_history, recovery_config: (
                b"first: &first {enabled: true}\n"
                b"second: &second {target: latest}\n" + recovery_settings
                + b"    <<: [*first, *second]\n"
            ),
        }, complete_recovery_history)
    except ValueError as error:
        if "multi-source YAML merges are unsupported" not in str(error):
            raise
    else:
        raise AssertionError("multi-source Recovery YAML merge was accepted")
    reused_alias = {
        **complete_recovery_history,
        recovery_config: (
            b"shared: &shared {setting: value}\n"
            b"first: *shared\nsecond: *shared\n" + recovery_settings
        ),
    }
    check("acyclic YAML aliases may be reused by separate branches", reused_alias,
          complete_recovery_history, None)
    catalog = b"".join(f"setting{index}: value\n".encode()
                       for index in range(RECOVERY_YAML_ENTRY_LIMIT - 4))
    alias_catalog = b"shared: &shared {setting: value}\ncopy: *shared\n" + catalog
    check("a YAML document at the expanded entry limit is accepted", {
        **complete_recovery_history, recovery_config: recovery_settings + b"---\n" + alias_catalog,
    }, complete_recovery_history, None)
    try:
        collect_issues({
            **complete_recovery_history,
            recovery_config: recovery_settings + b"---\n" + alias_catalog + b"extra: value\n",
        }, complete_recovery_history)
    except ValueError as error:
        if str(error) != (f"{recovery_config}: packaged Spring YAML exceeds "
                          f"{RECOVERY_YAML_ENTRY_LIMIT} expanded entries per document"):
            raise
    else:
        raise AssertionError("YAML expanded entry limit was not enforced")
    defaults = ", ".join(f"setting{index}: value" for index in range(64))
    merge_copies = "".join(f"copy{index}: {{<<: *defaults}}\n"
                           for index in range(RECOVERY_YAML_ENTRY_LIMIT // 64 + 1))
    try:
        collect_issues({
            **complete_recovery_history,
            recovery_config: recovery_settings + f"---\ndefaults: &defaults {{{defaults}}}\n{merge_copies}".encode(),
        }, complete_recovery_history)
    except ValueError as error:
        if (f"{recovery_config}: invalid packaged Spring configuration:" not in str(error)
                or f"exceeds {RECOVERY_YAML_ENTRY_LIMIT} expanded entries per document" not in str(error)):
            raise
    else:
        raise AssertionError("YAML merge entry limit was not enforced during construction")
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
        combined_fixture = {
            **history,
            **recovery_history,
            unicode_migration: "-- café\n".encode(),
            sibling_migration: b"package net.firedevops.firemud.sibling; class RecoveryMigration {}\n",
        }
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
        effective_inputs = {**combined_fixture, **outside_recovery}
        if current_snapshot(root) != effective_inputs:
            raise AssertionError("current snapshot must retain reserved paths for dependency validation")
        if parent != effective_inputs or unicode_migration not in parent or unrelated_non_utf8 in parent:
            raise AssertionError("base snapshot must retain the same migration and dependency inputs")
        if any(item.path in outside_recovery for item in discover_migration_files(parent)):
            raise AssertionError("foreign reserved resources cannot become Recovery history")
        sidecar_path = root / (recovery + "V1__journal.sql.conf")
        sidecar_path.write_text("shouldExecute=false", encoding="utf-8")
        git(root, "add", ".")
        git(root, "-c", "user.name=Flyway fixture", "-c", "user.email=fixture@example.invalid", "commit", "-qm", "sidecar")
        sidecar_base = git(root, "rev-parse", "HEAD").decode().strip()
        sidecar_parent = parent_snapshot(root, sidecar_base)
        sidecar_issues, _, _ = collect_issues(combined_fixture, sidecar_parent)
        if not any("Recovery migration sidecars are unsupported" in issue for issue in sidecar_issues):
            raise AssertionError("receiving-parent Recovery sidecar was not rejected after removal")
        symlink_target = root / "outside-migration.sql"
        symlink_target.write_text("-- symlink target", encoding="utf-8")
        symlink_path = root / (recovery + "V2__linked.sql")
        symlink_path.symlink_to(symlink_target)
        if current_snapshot(root).get(recovery + "V2__linked.sql") != SYMLINK_SNAPSHOT:
            raise AssertionError("current Recovery migration symlink was read as a regular migration")
        symlink_current_issues, _, _ = collect_issues(current_snapshot(root), parent)
        if not any("Recovery migration symlinks are unsupported" in issue for issue in symlink_current_issues):
            raise AssertionError("current Recovery migration symlink was not rejected")
        symlink_path.unlink()
        symlink_path.symlink_to(symlink_target)
        git(root, "add", ".")
        git(root, "-c", "user.name=Flyway fixture", "-c", "user.email=fixture@example.invalid",
            "commit", "-qm", "recovery symlink")
        symlink_base = git(root, "rev-parse", "HEAD").decode().strip()
        symlink_parent = parent_snapshot(root, symlink_base)
        if symlink_parent.get(recovery + "V2__linked.sql") != SYMLINK_SNAPSHOT:
            raise AssertionError("receiving-parent Recovery symlink was read as a regular migration")
        symlink_parent_issues, _, _ = collect_issues(combined_fixture, symlink_parent)
        if not any("Recovery migration symlinks are unsupported" in issue for issue in symlink_parent_issues):
            raise AssertionError("receiving-parent Recovery migration symlink was not rejected")
        for linked_path in (
            RECOVERY_SQL_ROOT.rstrip("/"),
            RECOVERY_SQL_ROOT.rstrip("/").rsplit("/", 1)[0],
            RECOVERY_JAVA_ROOT.rstrip("/"),
            RECOVERY_JAVA_ROOT.rstrip("/").rsplit("/", 1)[0],
        ):
            with tempfile.TemporaryDirectory() as symlink_tmp:
                symlink_repo = Path(symlink_tmp)
                target = symlink_repo / "outside-root"
                target.mkdir()
                root_link = symlink_repo / linked_path
                root_link.parent.mkdir(parents=True, exist_ok=True)
                root_link.symlink_to(target, target_is_directory=True)
                git(symlink_repo, "init", "-q")
                git(symlink_repo, "add", ".")
                git(symlink_repo, "-c", "user.name=Flyway fixture", "-c", "user.email=fixture@example.invalid",
                    "commit", "-qm", "linked migration input")
                link_commit = git(symlink_repo, "rev-parse", "HEAD").decode().strip()
                for snapshot in (current_snapshot(symlink_repo), parent_snapshot(symlink_repo, link_commit)):
                    if snapshot != {linked_path: SYMLINK_SNAPSHOT}:
                        raise AssertionError(f"Recovery migration link was not captured: {linked_path}")
                    if not any("Recovery migration symlinks are unsupported" in issue
                               for issue in recovery_input_issues(snapshot, set())):
                        raise AssertionError(f"Recovery migration link was not rejected: {linked_path}")

        for config_link in (RECOVERY_CONFIG_DIRECTORY, recovery_config_override):
            with tempfile.TemporaryDirectory() as linked_config_tmp:
                linked_config_repo = Path(linked_config_tmp)
                for path, data in recovery_history.items():
                    fixture_path = linked_config_repo / path
                    fixture_path.parent.mkdir(parents=True, exist_ok=True)
                    fixture_path.write_bytes(data)
                link = linked_config_repo / config_link
                link.parent.mkdir(parents=True, exist_ok=True)
                target = linked_config_repo / "override"
                if config_link == RECOVERY_CONFIG_DIRECTORY:
                    target.mkdir()
                    (target / "application.yml").write_bytes(b"server.port: 9000\n")
                else:
                    target.write_bytes(b"server.port: 9000\n")
                link.symlink_to(target, target_is_directory=target.is_dir())
                git(linked_config_repo, "init", "-q")
                git(linked_config_repo, "add", ".")
                git(linked_config_repo, "-c", "user.name=Flyway fixture", "-c",
                    "user.email=fixture@example.invalid", "commit", "-qm", "linked configuration")
                link_commit = git(linked_config_repo, "rev-parse", "HEAD").decode().strip()
                for label, candidate, baseline in (
                    ("working tree", current_snapshot(linked_config_repo), recovery_history),
                    ("receiving parent", recovery_history, parent_snapshot(linked_config_repo, link_commit)),
                ):
                    try:
                        collect_issues(candidate, baseline)
                    except ValueError as error:
                        if str(error) != f"{config_link}: Recovery configuration symlinks are unsupported in the {label}":
                            raise
                    else:
                        raise AssertionError(f"linked Recovery configuration was accepted in the {label}")
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
