#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
POSTGRES_LAYOUT_ENTRYPOINT="$ROOT_DIR/docker/postgres-data-layout-entrypoint.sh"
POSTGRES16_IMAGE='public.ecr.aws/docker/library/postgres:16@sha256:65b16a8b326e0cfbdf33fa7e783f2a0cb352a61448616ccccfd616ef42aa0f65'
POSTGRES_DUMP_DOCKERFILE="$ROOT_DIR/docker/pg-dump-cron.Dockerfile"

fail() {
  echo "$1" >&2
  exit 1
}

POSTGRES_DUMP_CLIENT_IMAGE="$(awk '$1 == "FROM" { print $2 }' "$POSTGRES_DUMP_DOCKERFILE")"
[[ -n "$POSTGRES_DUMP_CLIENT_IMAGE" ]] || fail "the Compose dump Dockerfile must declare its pinned PostgreSQL base image"

[[ -x "$POSTGRES_LAYOUT_ENTRYPOINT" ]] || fail "PostgreSQL layout entrypoint must be executable"

POSTGRES18_IMAGE="$(python3 - "$ROOT_DIR" "$POSTGRES_DUMP_CLIENT_IMAGE" <<'PY'
from pathlib import Path
import re
import sys

import yaml

root = Path(sys.argv[1])
expected_dump_image = sys.argv[2]

compose = yaml.safe_load((root / "docker/docker-compose.yml").read_text())
postgres = compose["services"]["postgres"]
expected_image = postgres.get("image")
if not isinstance(expected_image, str) or not re.fullmatch(r"public\.ecr\.aws/docker/library/postgres:18@sha256:[0-9a-f]{64}", expected_image):
    raise SystemExit("local Compose PostgreSQL image must be an exact digest-pinned PostgreSQL 18 reference")
if postgres.get("entrypoint") != ["/usr/local/bin/firemud-postgres-data-layout-entrypoint.sh"]:
    raise SystemExit("local Compose PostgreSQL must run its pre-entrypoint data-layout guard")
if postgres.get("environment", {}).get("PGDATA") != "/var/lib/postgresql/18/docker":
    raise SystemExit("local Compose PGDATA must use the PostgreSQL 18 image layout")
if "postgres-data:/var/lib/postgresql" not in postgres.get("volumes", []):
    raise SystemExit("local Compose must preserve and remount its postgres-data volume at /var/lib/postgresql")
if "./postgres-data-layout-entrypoint.sh:/usr/local/bin/firemud-postgres-data-layout-entrypoint.sh:ro" not in postgres.get("volumes", []):
    raise SystemExit("local Compose must mount the read-only PostgreSQL layout entrypoint")
if "postgres-data" not in compose.get("volumes", {}):
    raise SystemExit("local Compose must retain the existing postgres-data volume identity")

hosted_values = yaml.safe_load(
    (root / "k8s/helm/firemud/values-hosted-shared.example.yaml").read_text()
)["previewStack"]
if hosted_values["postgres"]["image"] != expected_image:
    raise SystemExit("hosted preview PostgreSQL image does not use the pinned PostgreSQL 18 proposal")
if hosted_values["seed"]["image"] != expected_image:
    raise SystemExit("hosted preview seed PostgreSQL image does not use the pinned PostgreSQL 18 proposal")

cronjob = next(
    document
    for document in yaml.safe_load_all((root / "k8s/postgres/pg-dump-cronjob.yaml").read_text())
    if document.get("kind") == "CronJob"
)
cronjob_container = cronjob["spec"]["jobTemplate"]["spec"]["template"]["spec"]["containers"][0]
cron_image = cronjob_container["image"]
if cron_image != expected_image:
    raise SystemExit("scheduled PostgreSQL dump client does not use the pinned PostgreSQL 18 proposal")
if cronjob_container.get("command") != ["/bin/bash", "/scripts/pg-dump.sh"]:
    raise SystemExit("scheduled PostgreSQL dump client must invoke its mounted ConfigMap script with bash")
dump_script = next(
    document
    for document in yaml.safe_load_all((root / "k8s/postgres/pg-dump-cronjob.yaml").read_text())
    if document.get("kind") == "ConfigMap" and document.get("metadata", {}).get("name") == "pg-dump-script"
)["data"]["pg-dump.sh"]
if "pg_dump -h \"$FIREMUD_POSTGRES_HOST\"" not in dump_script or "| gzip > \"$DUMP\"" not in dump_script:
    raise SystemExit("scheduled PostgreSQL dump ConfigMap must retain its plain-SQL gzip pipeline")

dump_dockerfile = (root / "docker/pg-dump-cron.Dockerfile").read_text()
from_images = [
    line.split()[1]
    for line in dump_dockerfile.splitlines()
    if line.split() and line.split()[0] == "FROM"
]
if len(from_images) != 1:
    raise SystemExit("the Compose dump Dockerfile must have one unambiguous PostgreSQL base image")
if from_images[0] != expected_dump_image:
    raise SystemExit("the Compose dump proof image must derive from the Dockerfile's exact FROM reference")
if not re.fullmatch(r"public\.ecr\.aws/docker/library/postgres:18@sha256:[0-9a-f]{64}", expected_dump_image):
    raise SystemExit("the Compose dump Dockerfile must pin its PostgreSQL 18 base image by digest")

for path in (
    "docker/docker-compose.yml",
    "k8s/helm/firemud/values-hosted-shared.example.yaml",
    "k8s/postgres/pg-dump-cronjob.yaml",
):
    content = (root / path).read_text()
    if "postgres:16@sha256:" in content:
        raise SystemExit(f"{path} retains a PostgreSQL 16 image ref in the converged local/preview slice")

print(expected_image)
PY
)"

assert_layout() {
  local name="$1"
  local expected_status="$2"
  local fixture="$3"
  local expected_message="${4:-}"
  local fixture_dir
  local output_file
  local status

  fixture_dir="$(mktemp -d)"
  output_file="$(mktemp)"
  case "$fixture" in
    empty) ;;
    legacy-root) printf '16\n' >"$fixture_dir/PG_VERSION" ;;
    legacy-preview)
      mkdir -p "$fixture_dir/pgdata"
      printf '16\n' >"$fixture_dir/pgdata/PG_VERSION"
      ;;
    unknown) mkdir -p "$fixture_dir/lost-data" ;;
    wrong-major|incomplete-postgres18|postgres18|postgres18-with-sibling|postgres18-with-global-symlink)
      mkdir -p "$fixture_dir/18/docker"
      printf '%s\n' "$([[ "$fixture" == wrong-major ]] && printf '16' || printf '18')" \
        >"$fixture_dir/18/docker/PG_VERSION"
      if [[ "$fixture" != incomplete-postgres18 ]]; then
        mkdir -p "$fixture_dir/18/docker/base" "$fixture_dir/18/docker/global"
        printf 'control\n' >"$fixture_dir/18/docker/global/pg_control"
      fi
      if [[ "$fixture" == postgres18-with-sibling ]]; then
        touch "$fixture_dir/unexpected"
      fi
      if [[ "$fixture" == postgres18-with-global-symlink ]]; then
        mkdir -p "$fixture_dir/18/docker/other-global"
        printf 'control\n' >"$fixture_dir/18/docker/other-global/pg_control"
        rm -rf -- "$fixture_dir/18/docker/global"
        ln -s other-global "$fixture_dir/18/docker/global"
      fi
      ;;
    *) fail "unknown PostgreSQL data layout fixture: $fixture" ;;
  esac

  if "$POSTGRES_LAYOUT_ENTRYPOINT" --check-layout "$fixture_dir" >"$output_file" 2>&1; then
    status=0
  else
    status=$?
  fi
  if [[ "$status" -ne "$expected_status" ]]; then
    rm -rf -- "$fixture_dir" "$output_file"
    fail "PostgreSQL layout guard returned $status for $name; expected $expected_status"
  fi
  if [[ -n "$expected_message" ]] && ! grep -Fq -- "$expected_message" "$output_file"; then
    rm -rf -- "$fixture_dir" "$output_file"
    fail "PostgreSQL layout guard did not identify the rejected $name layout"
  fi
  rm -rf -- "$fixture_dir" "$output_file"
}

assert_layout "empty fresh storage" 0 empty ""
assert_layout "legacy Compose root cluster" 1 legacy-root "legacy root PG_VERSION"
assert_layout "legacy preview pgdata cluster" 1 legacy-preview "legacy nested PG_VERSION"
assert_layout "unknown nonempty layout" 1 unknown "unrecognized nonempty data layout"
assert_layout "wrong-major versioned child" 1 wrong-major "expected PostgreSQL major 18"
assert_layout "incomplete PostgreSQL 18 child" 1 incomplete-postgres18 "is incomplete"
assert_layout "PostgreSQL 18 child with unknown sibling" 1 postgres18-with-sibling "unrecognized nonempty data layout"
assert_layout "PostgreSQL 18 child with symlinked global directory" 1 postgres18-with-global-symlink "unsafe cluster markers"
assert_layout "valid PostgreSQL 18 child" 0 postgres18 ""

run_docker_proof() (
  run_token=
  network_name=
  source_volume=
  target_volume=
  source_container=
  target_container=
  backup_dir=
  bad_dump=
  bad_key=
  good_dump=
  good_key=
  aws_stub=
  marker=
  password=
  source_version=
  target_data_directory=
  actual_marker=
  failed_table_missing=
  cleanup_dir=
  helper_backup_dir=
  proof_user=
  helper_dump=
  helper_sql=
  cronjob_script=
  cronjob_sql=

  run_token="${GITHUB_RUN_ID:-local}-a${GITHUB_RUN_ATTEMPT:-0}-$(date -u +%Y%m%d%H%M%S)-$$-${RANDOM:-0}"
  run_token="$(printf '%s' "$run_token" | tr -cd 'A-Za-z0-9_.-')"
  network_name="firemud-pg18-upgrade-${run_token}"
  source_volume="firemud-pg18-source-${run_token}"
  target_volume="firemud-pg18-target-${run_token}"
  source_container="firemud-pg16-source-${run_token}"
  target_container="firemud-pg18-target-${run_token}"
  cleanup_dir="$(mktemp -d)"
  backup_dir="$cleanup_dir/backups"
  helper_backup_dir="$cleanup_dir/helper-backups"
  helper_sql="$cleanup_dir/helper.sql"
  cronjob_sql="$cleanup_dir/k8s-cronjob.sql"
  bad_dump="$backup_dir/15min/firemud_99999999999999.sql.gz"
  bad_key="15min/firemud_99999999999999.sql.gz"
  aws_stub="$cleanup_dir/aws"
  marker="retained-${run_token}"
  password="firemud-ci-${run_token}"

  # shellcheck disable=SC2329 # The cleanup handler is invoked through the proof's EXIT/INT/TERM traps.
  cleanup() {
    docker rm -f "$source_container" "$target_container" >/dev/null 2>&1 || true
    docker network rm "$network_name" >/dev/null 2>&1 || true
    docker volume rm "$source_volume" "$target_volume" >/dev/null 2>&1 || true
    rm -rf -- "$cleanup_dir"
  }
  trap 'cleanup' EXIT INT TERM

  report_client_failure() {
    local operation="$1"
    local log_path="$2"
    printf 'PostgreSQL proof operation failed: %s; bounded log excerpt follows.\n' "$operation" >&2
    FIREMUD_POSTGRES_PROOF_PASSWORD="$password" python3 - "$log_path" <<'PY'
from pathlib import Path
import os
import sys

max_bytes = 16 * 1024
max_lines = 40
log_path = Path(sys.argv[1])
secret = os.environ.get("FIREMUD_POSTGRES_PROOF_PASSWORD", "").encode()

try:
    with log_path.open("rb") as log_file:
        log_file.seek(0, os.SEEK_END)
        log_size = log_file.tell()
        start = max(0, log_size - max_bytes - len(secret))
        log_file.seek(start)
        excerpt = log_file.read(max_bytes + len(secret))
except OSError:
    print("[operation log unavailable]", file=sys.stderr)
    raise SystemExit(0)

truncated = start > 0
if secret:
    excerpt = excerpt.replace(secret, b"[REDACTED]")
if len(excerpt) > max_bytes:
    excerpt = excerpt[-max_bytes:]
    truncated = True

lines = excerpt.decode("utf-8", errors="replace").splitlines(keepends=True)
if len(lines) > max_lines:
    lines = lines[-max_lines:]
    truncated = True
excerpt = "".join(lines).encode("utf-8")
if len(excerpt) > max_bytes:
    excerpt = excerpt[-max_bytes:].decode("utf-8", errors="ignore").encode("utf-8")
    truncated = True

if truncated:
    print("[log excerpt truncated to at most 16 KiB and 40 lines]", file=sys.stderr)
sys.stderr.buffer.write(excerpt)
if excerpt and not excerpt.endswith(b"\n"):
    sys.stderr.write("\n")
PY
  }

  case "${1:-}" in
    --lifecycle-success) exit 0 ;;
    --lifecycle-failure) exit 23 ;;
    --lifecycle-diagnostic)
      mkdir -p "$cleanup_dir"
      FIREMUD_POSTGRES_PROOF_PASSWORD="$password" python3 - "$cleanup_dir/diagnostic.log" <<'PY'
from pathlib import Path
import os
import sys

with Path(sys.argv[1]).open("w") as log_file:
    for index in range(1, 81):
        log_file.write(f"large-line-{index:03d}: {'x' * 500}\n")
    for index in range(1, 101):
        suffix = f" password={os.environ['FIREMUD_POSTGRES_PROOF_PASSWORD']}" if index == 100 else ""
        log_file.write(f"fixture-line-{index:03d}{suffix}\n")
PY
      report_client_failure "bounded diagnostic fixture" "$cleanup_dir/diagnostic.log"
      exit 0
      ;;
  esac

  command -v docker >/dev/null 2>&1 || fail "required PostgreSQL runtime proof needs Docker on the CI runner"
  docker info >/dev/null 2>&1 || fail "required PostgreSQL runtime proof could not reach the CI Docker daemon"

  mkdir -p "$backup_dir/15min" "$helper_backup_dir"
  proof_user="$(id -u):$(id -g)"
  cronjob_script="$cleanup_dir/k8s-pg-dump.sh"
  python3 - "$ROOT_DIR/k8s/postgres/pg-dump-cronjob.yaml" "$cronjob_script" <<'PY'
from pathlib import Path
import sys

import yaml

source = Path(sys.argv[1])
destination = Path(sys.argv[2])
script = next(
    document
    for document in yaml.safe_load_all(source.read_text())
    if document.get("kind") == "ConfigMap" and document.get("metadata", {}).get("name") == "pg-dump-script"
)["data"]["pg-dump.sh"]
destination.write_text(script)
PY
  chmod 0444 "$cronjob_script"
  docker network create "$network_name" >/dev/null
  docker volume create "$source_volume" >/dev/null
  docker volume create "$target_volume" >/dev/null

  docker run -d \
    --name "$source_container" \
    --network "$network_name" \
    --network-alias postgres-source \
    --volume "$source_volume:/var/lib/postgresql/data" \
    --env POSTGRES_DB=firemud \
    --env POSTGRES_USER=firemud \
    --env "POSTGRES_PASSWORD=$password" \
    "$POSTGRES16_IMAGE" >/dev/null
  wait_for_postgres "$source_container" "$password"
  docker exec "$source_container" psql -v ON_ERROR_STOP=1 -U firemud -d firemud \
    -c 'CREATE TABLE public.postgres18_upgrade_probe (id integer PRIMARY KEY, marker text NOT NULL)' \
    -c "INSERT INTO public.postgres18_upgrade_probe (id, marker) VALUES (1, '$marker')" \
    >/dev/null
  source_version="$(docker exec "$source_container" psql -Atq -U firemud -d firemud -c 'SHOW server_version_num')"
  [[ "$source_version" == 16* ]] || fail "the retained source container did not start PostgreSQL 16"

  docker run --rm \
    --volume "$source_volume:/var/lib/postgresql:ro" \
    --volume "$POSTGRES_LAYOUT_ENTRYPOINT:/usr/local/bin/firemud-postgres-data-layout-entrypoint.sh:ro" \
    --entrypoint /usr/local/bin/firemud-postgres-data-layout-entrypoint.sh \
    "$POSTGRES18_IMAGE" --check-layout /var/lib/postgresql \
    >"$cleanup_dir/legacy-guard.log" 2>&1 && fail "the PostgreSQL 18 guard accepted a retained PostgreSQL 16 root cluster"
  grep -Fq "legacy root PG_VERSION" "$cleanup_dir/legacy-guard.log" || fail "the PostgreSQL 18 guard did not identify the retained root cluster"

  # This executes the helper against the Dockerfile's exact PostgreSQL base image; it does not build or prove the cron image's packaging layers.
  # Both dump-only clients use the runner identity so run-owned artifacts remain host-readable and removable.
  docker run --rm \
    --user "$proof_user" \
    --network "$network_name" \
    --volume "$ROOT_DIR/dev-tools/backups/pg-dump-rotate.sh:/usr/local/bin/pg-dump-rotate.sh:ro" \
    --volume "$helper_backup_dir:/backups" \
    --env FIREMUD_POSTGRES_HOST=postgres-source \
    --env FIREMUD_POSTGRES_USER=firemud \
    --env FIREMUD_POSTGRES_DB=firemud \
    --env "PGPASSWORD=$password" \
    --env BACKUP_DIR=/backups \
    --entrypoint /bin/bash \
    "$POSTGRES_DUMP_CLIENT_IMAGE" /usr/local/bin/pg-dump-rotate.sh \
    >"$cleanup_dir/backup.log" 2>&1 || {
      report_client_failure "Compose PostgreSQL dump" "$cleanup_dir/backup.log"
      fail "the Compose dump helper did not complete"
    }
  helper_dump="$(find "$helper_backup_dir/15min" -maxdepth 1 -type f -name 'firemud_*.sql.gz' -print -quit)"
  [[ -n "$helper_dump" && -s "$helper_dump" ]] || fail "the Compose dump helper did not publish a gzip artifact"
  gzip -t "$helper_dump" || fail "the Compose dump helper artifact is not valid gzip"
  gzip -cd "$helper_dump" >"$helper_sql"
  grep -Fq -- "$marker" "$helper_sql" || fail "the Compose dump helper artifact omitted the seeded PostgreSQL 16 row"

  docker run --rm \
    --user "$proof_user" \
    --network "$network_name" \
    --volume "$cronjob_script:/scripts/pg-dump.sh:ro" \
    --volume "$backup_dir:/backups" \
    --env FIREMUD_POSTGRES_HOST=postgres-source \
    --env FIREMUD_POSTGRES_USER=firemud \
    --env FIREMUD_POSTGRES_DB=firemud \
    --env "PGPASSWORD=$password" \
    --entrypoint /bin/bash \
    "$POSTGRES18_IMAGE" /scripts/pg-dump.sh \
    >"$cleanup_dir/k8s-cronjob-backup.log" 2>&1 || {
      report_client_failure "Kubernetes PostgreSQL dump" "$cleanup_dir/k8s-cronjob-backup.log"
      fail "the actual Kubernetes PostgreSQL dump ConfigMap script did not complete in its pinned image"
    }
  good_dump="$(find "$backup_dir/15min" -maxdepth 1 -type f -name 'firemud_*.sql.gz' -print -quit)"
  [[ -n "$good_dump" && -s "$good_dump" ]] || fail "the Kubernetes dump script did not publish a gzip artifact"
  gzip -t "$good_dump" || fail "the Kubernetes dump script artifact is not valid gzip"
  gzip -cd "$good_dump" >"$cronjob_sql"
  grep -Fq -- "$marker" "$cronjob_sql" || fail "the Kubernetes dump script artifact omitted the seeded PostgreSQL 16 row"
  good_key="15min/${good_dump##*/}"

  docker stop "$source_container" >/dev/null

  cat >"$cleanup_dir/failed-import.sql" <<'SQL'
CREATE TABLE public.postgres18_failed_import (marker text NOT NULL);
INSERT INTO public.postgres18_failed_import (marker) VALUES ('partial');
SELECT 1 / 0;
SQL
  gzip -c "$cleanup_dir/failed-import.sql" >"$bad_dump"
  cat >"$aws_stub" <<'AWS'
#!/bin/sh
set -eu
case "${1:-} ${2:-}" in
  "s3api list-objects-v2")
    key=${FIREMUD_TEST_BACKUP_KEY:?}
    printf '2026-10-08T00:00:00Z\t%s\n' "$key"
    ;;
  "s3 cp")
    key=${FIREMUD_TEST_BACKUP_KEY:?}
    destination=${4:?}
    source="${FIREMUD_TEST_BACKUP_DIR}/${key#15min/}"
    cp -- "$source" "$destination"
    ;;
  *)
    exit 2
    ;;
esac
AWS
  chmod +x "$aws_stub"

  docker run -d \
    --name "$target_container" \
    --network "$network_name" \
    --network-alias postgres-target \
    --volume "$target_volume:/var/lib/postgresql" \
    --volume "$POSTGRES_LAYOUT_ENTRYPOINT:/usr/local/bin/firemud-postgres-data-layout-entrypoint.sh:ro" \
    --entrypoint /usr/local/bin/firemud-postgres-data-layout-entrypoint.sh \
    --env POSTGRES_DB=firemud \
    --env POSTGRES_USER=firemud \
    --env "POSTGRES_PASSWORD=$password" \
    "$POSTGRES18_IMAGE" postgres -c max_connections=200 >/dev/null
  wait_for_postgres "$target_container" "$password"
  target_data_directory="$(docker exec "$target_container" psql -Atq -U firemud -d firemud -c 'SHOW data_directory')"
  [[ "$target_data_directory" == "/var/lib/postgresql/18/docker" ]] || fail "the PostgreSQL 18 target did not use its versioned data directory"

  docker run --rm \
    --volume "$target_volume:/var/lib/postgresql:ro" \
    --volume "$POSTGRES_LAYOUT_ENTRYPOINT:/usr/local/bin/firemud-postgres-data-layout-entrypoint.sh:ro" \
    --entrypoint /usr/local/bin/firemud-postgres-data-layout-entrypoint.sh \
    "$POSTGRES18_IMAGE" --check-layout /var/lib/postgresql \
    >"$cleanup_dir/current-guard.log" 2>&1 || fail "the PostgreSQL 18 guard rejected a complete versioned target cluster"

  bad_restore_log="$cleanup_dir/restore-${bad_key##*/}.log"
  if run_logical_restore "$network_name" "$bad_key" "$aws_stub" "$backup_dir" "$cleanup_dir" "$password"; then
    fail "the deliberately invalid target import unexpectedly succeeded"
  fi
  if ! grep -Fq "ERROR:  division by zero" "$bad_restore_log"; then
    report_client_failure "deliberately invalid PostgreSQL import" "$bad_restore_log"
    fail "the invalid-import proof did not reach its intended PostgreSQL SQL error"
  fi
  failed_table_missing="$(docker exec "$target_container" psql -Atq -U firemud -d firemud -c "SELECT to_regclass('public.postgres18_failed_import') IS NULL")"
  [[ "$failed_table_missing" == "t" ]] || fail "the failed target import left partial database changes"

  docker start "$source_container" >/dev/null
  wait_for_postgres "$source_container" "$password"
  actual_marker="$(docker exec "$source_container" psql -Atq -U firemud -d firemud -c 'SELECT marker FROM public.postgres18_upgrade_probe WHERE id = 1')"
  [[ "$actual_marker" == "$marker" ]] || fail "the original PostgreSQL 16 source no longer contains its seeded data after a failed target import"
  docker stop "$source_container" >/dev/null

  good_restore_log="$cleanup_dir/restore-${good_key##*/}.log"
  if ! run_logical_restore "$network_name" "$good_key" "$aws_stub" "$backup_dir" "$cleanup_dir" "$password"; then
    report_client_failure "canonical scheduled PostgreSQL logical restore" "$good_restore_log"
    fail "the canonical scheduled logical restore did not import into the separate PostgreSQL 18 target"
  fi
  actual_marker="$(docker exec "$target_container" psql -Atq -U firemud -d firemud -c 'SELECT marker FROM public.postgres18_upgrade_probe WHERE id = 1')"
  [[ "$actual_marker" == "$marker" ]] || fail "the PostgreSQL 18 target did not retain the logical backup's seeded data"
  [[ "$(docker inspect --format '{{.State.Running}}' "$source_container")" == false ]] \
    || fail "the PostgreSQL 16 source should remain stopped while the PostgreSQL 18 target is validated"

  echo "PostgreSQL runtime proof passed: the Compose dump helper on its pinned PostgreSQL base image and the Kubernetes PostgreSQL 18 client produced seeded gzip/plain-SQL backups; the original PostgreSQL 16 source stayed intact while the separate PostgreSQL 18 target was restored."
)

assert_proof_cleanup_lifecycle() (
  fixture_root="$(mktemp -d)"
  fake_bin="$fixture_root/bin"
  proof_tmpdir="$fixture_root/proof-tmp"
  docker_log="$fixture_root/docker.log"
  # shellcheck disable=SC2329 # The fixture cleanup handler is invoked through its EXIT trap.
  cleanup_fixture() {
    rm -rf -- "$fixture_root"
  }
  trap 'cleanup_fixture' EXIT
  mkdir -p "$fake_bin" "$proof_tmpdir"

  cat >"$fake_bin/docker" <<'DOCKER'
#!/bin/sh
set -eu
printf '%s\n' "$*" >>"${FIREMUD_TEST_DOCKER_LOG:?}"
DOCKER
  cat >"$fake_bin/mktemp" <<'MKTEMP'
#!/bin/sh
set -eu
[ "$#" -eq 1 ] && [ "$1" = "-d" ] || exit 2
directory="${FIREMUD_TEST_PROOF_TMPDIR:?}/proof-$$"
mkdir "$directory"
printf '%s\n' "$directory"
MKTEMP
  chmod +x "$fake_bin/docker" "$fake_bin/mktemp"

  for lifecycle_case in success failure; do
    : >"$docker_log"
    expected_status=0
    [[ "$lifecycle_case" == success ]] || expected_status=23
    if PATH="$fake_bin:$PATH" \
      FIREMUD_TEST_DOCKER_LOG="$docker_log" \
      FIREMUD_TEST_PROOF_TMPDIR="$proof_tmpdir" \
      run_docker_proof "--lifecycle-$lifecycle_case"; then
      actual_status=0
    else
      actual_status=$?
    fi
    [[ "$actual_status" -eq "$expected_status" ]] || fail "PostgreSQL proof $lifecycle_case lifecycle returned $actual_status; expected $expected_status"
    [[ "$(wc -l <"$docker_log")" -eq 3 ]] || fail "PostgreSQL proof $lifecycle_case lifecycle did not run all three cleanup commands"
    grep -Eq '^rm -f firemud-pg16-source-[^ ]+ firemud-pg18-target-[^ ]+$' "$docker_log" || fail "PostgreSQL proof $lifecycle_case lifecycle did not remove both fixture containers"
    grep -Eq '^network rm firemud-pg18-upgrade-[^ ]+$' "$docker_log" || fail "PostgreSQL proof $lifecycle_case lifecycle did not remove its isolated network"
    grep -Eq '^volume rm firemud-pg18-source-[^ ]+ firemud-pg18-target-[^ ]+$' "$docker_log" || fail "PostgreSQL proof $lifecycle_case lifecycle did not remove both fixture volumes"
    [[ -z "$(find "$proof_tmpdir" -mindepth 1 -maxdepth 1 -print -quit)" ]] || fail "PostgreSQL proof $lifecycle_case lifecycle leaked its temporary directory"
  done

  diagnostic_output="$(
    PATH="$fake_bin:$PATH" \
      FIREMUD_TEST_DOCKER_LOG="$docker_log" \
      FIREMUD_TEST_PROOF_TMPDIR="$proof_tmpdir" \
      run_docker_proof --lifecycle-diagnostic 2>&1
  )"
  [[ "$diagnostic_output" == *"bounded log excerpt follows"* ]] || fail "PostgreSQL proof diagnostic fixture omitted its operation label"
  [[ "$diagnostic_output" == *"[log excerpt truncated to at most 16 KiB and 40 lines]"* ]] || fail "PostgreSQL proof diagnostic fixture omitted its truncation marker"
  [[ "$diagnostic_output" == *"fixture-line-100 password=[REDACTED]"* ]] || fail "PostgreSQL proof diagnostic fixture did not redact its generated password"
  [[ "$diagnostic_output" != *"firemud-ci-"* ]] || fail "PostgreSQL proof diagnostic fixture exposed its generated password"
  [[ "$diagnostic_output" == *"fixture-line-061"* && "$diagnostic_output" != *"fixture-line-060"* ]] \
    || fail "PostgreSQL proof diagnostic fixture did not limit the excerpt to the final 40 lines"
  [[ "$(printf '%s\n' "$diagnostic_output" | wc -l)" -le 42 ]] || fail "PostgreSQL proof diagnostic fixture exceeded its line bound"
  [[ "$(printf '%s\n' "$diagnostic_output" | wc -c)" -le 16600 ]] || fail "PostgreSQL proof diagnostic fixture exceeded its byte bound"

  echo "PostgreSQL proof cleanup lifecycle passed for success and failure without Docker."
)

wait_for_postgres() {
  local container="$1"
  local password="$2"
  local attempt
  local query_result
  for ((attempt = 0; attempt < 60; attempt++)); do
    if docker exec --env "PGPASSWORD=$password" "$container" pg_isready -h 127.0.0.1 -U firemud -d firemud >/dev/null 2>&1; then
      if query_result="$(docker exec --env "PGPASSWORD=$password" "$container" \
        psql -h 127.0.0.1 -v ON_ERROR_STOP=1 -Atq -U firemud -d firemud -c 'SELECT 1' 2>/dev/null)" \
        && [[ "$query_result" == "1" ]]; then
        return 0
      fi
    fi
    sleep 1
  done
  fail "PostgreSQL container did not become ready within 60 seconds"
}

run_logical_restore() {
  local network_name="$1"
  local backup_key="$2"
  local aws_stub="$3"
  local backup_dir="$4"
  local output_dir="$5"
  local password="$6"

  docker run --rm \
    --network "$network_name" \
    --volume "$ROOT_DIR/dev-tools/restores:/workspace/dev-tools/restores:ro" \
    --volume "$ROOT_DIR/dev-tools/backups:/workspace/dev-tools/backups:ro" \
    --volume "$backup_dir:/proof-backups:ro" \
    --volume "$aws_stub:/proof-bin/aws:ro" \
    --env FIREMUD_POSTGRES_HOST=postgres-target \
    --env FIREMUD_POSTGRES_USER=firemud \
    --env FIREMUD_POSTGRES_DB=firemud \
    --env "PGPASSWORD=$password" \
    --env PG_DUMP_BUCKET=firemud-proof \
    --env FIREMUD_TEST_BACKUP_DIR=/proof-backups/15min \
    --env "FIREMUD_TEST_BACKUP_KEY=$backup_key" \
    --entrypoint /bin/bash \
    "$POSTGRES_DUMP_CLIENT_IMAGE" \
    -ec 'export PATH="/proof-bin:$PATH"; exec /workspace/dev-tools/restores/restore-latest-db.sh' \
    >"$output_dir/restore-${backup_key##*/}.log" 2>&1
}

assert_proof_cleanup_lifecycle

case "${FIREMUD_POSTGRES_RUNTIME_DOCKER_PROOF:-deferred}" in
  required)
    run_docker_proof
    ;;
  deferred)
    echo "PostgreSQL physical Docker startup/restore proof deferred; the dedicated GitHub CI job runs it in required mode."
    ;;
  *)
    fail "FIREMUD_POSTGRES_RUNTIME_DOCKER_PROOF must be 'deferred' or 'required'"
    ;;
esac

echo "PostgreSQL runtime upgrade fixture contract checks passed."
