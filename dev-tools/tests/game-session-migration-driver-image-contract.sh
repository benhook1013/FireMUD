#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

python3 - "$repo_root" <<'PY'
from pathlib import Path
import re
import sys
import zipfile


root = Path(sys.argv[1])
dockerfile = root / "k8s/game-session-migration-driver/Dockerfile"
launcher_rel = "bin/game-session-migration-driver"
launcher_path = "/opt/firemud/game-session-migration-driver/" + launcher_rel
distribution = root / "services/game-session-service/build/distributions/game-session-migration-driver"


def require(condition: bool, message: str) -> None:
    if not condition:
        raise SystemExit(f"FAIL: {message}")


def compare_resource_entries(expected_entries: list[tuple[str, bytes]], packaged_entries: list[tuple[str, bytes]], description: str) -> None:
    def indexed(entries: list[tuple[str, bytes]], source: str) -> dict[str, bytes]:
        result: dict[str, bytes] = {}
        for name, content in entries:
            if name in result:
                raise ValueError(f"{description}: duplicate {source} entry {name}")
            result[name] = content
        return result

    expected = indexed(expected_entries, "source")
    packaged = indexed(packaged_entries, "packaged")
    missing = sorted(expected.keys() - packaged.keys())
    extra = sorted(packaged.keys() - expected.keys())
    if missing or extra:
        raise ValueError(f"{description}: resource set differs (missing={missing}, extra={extra})")
    mismatched = sorted(name for name in expected if expected[name] != packaged[name])
    if mismatched:
        raise ValueError(f"{description}: resource bytes differ for {mismatched}")


def expect_resource_comparison_failure(expected: list[tuple[str, bytes]], packaged: list[tuple[str, bytes]], case: str) -> None:
    try:
        compare_resource_entries(expected, packaged, case)
    except ValueError:
        return
    raise SystemExit(f"FAIL: synthetic {case} packaging defect was accepted")


# Keep the comparison's failure modes covered without mutating packaged artifacts.
synthetic_resource = "db/migration/V1__baseline.sql"
compare_resource_entries([(synthetic_resource, b"migration")], [(synthetic_resource, b"migration")], "synthetic matching resources")
expect_resource_comparison_failure([(synthetic_resource, b"migration")], [], "missing SQL")
expect_resource_comparison_failure([(synthetic_resource, b"migration")], [(synthetic_resource, b"changed")], "changed bytes")
expect_resource_comparison_failure(
    [(synthetic_resource, b"migration")],
    [(synthetic_resource, b"migration"), (synthetic_resource, b"migration")],
    "duplicate jar entry",
)


docker_text = dockerfile.read_text(encoding="utf-8")
docker_lines = [line.strip() for line in docker_text.splitlines() if line.strip()]
service_dockerfile = root / "services/game-session-service/Dockerfile"
service_text = service_dockerfile.read_text(encoding="utf-8")
service_base_match = re.search(r"^ARG BASE_IMAGE=(\S+)$", service_text, re.MULTILINE)
require(service_base_match is not None, "game-session service Dockerfile must declare its BASE_IMAGE")
service_base = service_base_match.group(1)
require(re.search(r"@sha256:[0-9a-f]{64}$", service_base) is not None, "game-session service BASE_IMAGE must use a lowercase SHA-256 digest pin")
migration_base_match = re.search(r"^ARG BASE_IMAGE=(\S+)$", docker_text, re.MULTILINE)
require(migration_base_match is not None, "migration-driver Dockerfile must declare its BASE_IMAGE")
require(migration_base_match.group(1) == service_base, "migration-driver Dockerfile base image must match the game-session service pin")
require("FROM ${BASE_IMAGE}" in docker_lines, "Dockerfile must use the BASE_IMAGE argument")
require(
    "COPY --chown=0:0 services/game-session-service/build/distributions/game-session-migration-driver/ "
    "/opt/firemud/game-session-migration-driver/" in docker_lines,
    "Dockerfile must copy the packaged distribution from the repository-root build context with root ownership",
)
permission_step_lines = [
    "RUN chmod -R a+rX /opt/firemud/game-session-migration-driver \\",
    "&& chmod -R a-w /opt/firemud/game-session-migration-driver \\",
    "&& chmod a+x /opt/firemud/game-session-migration-driver/bin/game-session-migration-driver",
]
require(
    [line for line in docker_lines if line.startswith(("RUN ", "&& "))] == permission_step_lines,
    "Dockerfile must make the root-owned distribution readable/executable and non-writable before runtime",
)
require("USER 999:999" in docker_lines, "image default user must be uid/gid 999")
require(
    f'ENTRYPOINT ["{launcher_path}"]' in docker_lines,
    "image entrypoint must be the fixed finite migration-driver launcher",
)
require(
    not any(line.startswith(("EXPOSE ", "CMD ")) for line in docker_lines),
    "image must not add a startup command, port, or second default command",
)
require(not re.search(r"\b(?:PGDATA|serviceAccount|kubeconfig|password|secret)\b", docker_text, re.IGNORECASE), "Dockerfile must not configure storage, cluster credentials, or secrets")

if not distribution.is_dir():
    print("PASS: Dockerfile source contract; packaged distribution unavailable, artifact checks deferred")
    raise SystemExit(0)

launcher = distribution / launcher_rel
require(launcher.is_file(), f"packaged launcher is missing: {launcher_rel}")
require(launcher.stat().st_mode & 0o111, "packaged launcher must be executable")
launcher_text = launcher.read_text(encoding="utf-8")
launcher_class = (
    "net.firedevops.firemud.gamesession.repository.CanonicalGameplayMigrationDriverLauncher"
)
implementation_main_class = (
    "net.firedevops.firemud.gamesession.repository.CanonicalGameplayMigrationDriverMain"
)
require(
    launcher_class in launcher_text,
    "packaged launcher must invoke the stdout-isolating driver launcher class",
)
for dependency in (
    "flyway-core-",
    "flyway-database-postgresql-",
    "postgresql-",
    "junixsocket-common-",
    "junixsocket-native-common-",
):
    require(dependency in launcher_text, f"packaged launcher classpath must include {dependency.rstrip('-')}")

libraries = distribution / "lib"
require(libraries.is_dir(), "distribution lib directory is missing")
for dependency in (
    "flyway-core-",
    "flyway-database-postgresql-",
    "postgresql-",
    "junixsocket-common-",
    "junixsocket-native-common-",
    "netty-transport-native-unix-common-",
):
    require(any(path.name.startswith(dependency) for path in libraries.glob("*.jar")), f"distribution is missing {dependency.rstrip('-')} jar")

app_jars = list(libraries.glob("game-session-service-*-plain.jar"))
require(len(app_jars) == 1, "distribution must contain one game-session plain application jar")
game_session_migration_root = root / "services/game-session-service/src/main/resources/db/migration"
game_session_source_entries = [
    (path.relative_to(game_session_migration_root.parent.parent).as_posix(), path.read_bytes())
    for path in sorted(game_session_migration_root.rglob("*.sql"))
]
with zipfile.ZipFile(app_jars[0]) as app_jar:
    app_entries = app_jar.namelist()
    require(
        launcher_class.replace(".", "/") + ".class" in app_entries,
        "application jar is missing the stdout-isolating driver launcher class",
    )
    require(
        implementation_main_class.replace(".", "/") + ".class" in app_entries,
        "application jar is missing the finite migration-driver implementation class",
    )
    game_session_packaged_entries = [
        (entry, app_jar.read(entry))
        for entry in app_entries
        if entry.startswith("db/migration/") and entry.endswith(".sql")
    ]
compare_resource_entries(game_session_source_entries, game_session_packaged_entries, "game-session migration resources")

common_saga_jars = list(libraries.glob("common-saga-*.jar"))
require(len(common_saga_jars) == 1, "distribution must contain exactly one common-saga jar")
common_saga_migration_root = root / "services/common-saga/src/main/resources/db/migration"
common_saga_source_entries = [
    (path.relative_to(common_saga_migration_root.parent.parent).as_posix(), path.read_bytes())
    for path in sorted(common_saga_migration_root.rglob("*.sql"))
]
with zipfile.ZipFile(common_saga_jars[0]) as common_saga_jar:
    common_saga_packaged_entries = [
        (entry, common_saga_jar.read(entry))
        for entry in common_saga_jar.namelist()
        if entry.startswith("db/migration/") and entry.endswith(".sql")
    ]
compare_resource_entries(common_saga_source_entries, common_saga_packaged_entries, "common-saga migration resources")

print("PASS: pinned image source contract and packaged finite-driver distribution contents")
PY
