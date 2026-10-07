#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
POSTGRES_LAYOUT_ENTRYPOINT="$ROOT_DIR/docker/postgres-data-layout-entrypoint.sh"
POSTGRES18_IMAGE='postgres:18@sha256:74935e72241653ca55e0414067e6d8763aceb8a810eb51b452253ec3dcfc4336'
POSTGRES16_IMAGE='postgres:16@sha256:65b16a8b326e0cfbdf33fa7e783f2a0cb352a61448616ccccfd616ef42aa0f65'
POSTGRES_DUMP_CLIENT_IMAGE='postgres:18@sha256:fc973eb97c9fd04bfa1840e0f510719a584ccb3be8debfe6a4144637a9dfe8cf'

fail() {
  echo "$1" >&2
  exit 1
}

[[ -x "$POSTGRES_LAYOUT_ENTRYPOINT" ]] || fail "PostgreSQL layout entrypoint must be executable"

python3 - "$ROOT_DIR" "$POSTGRES18_IMAGE" <<'PY'
from pathlib import Path
import sys

import yaml

root = Path(sys.argv[1])
expected_image = sys.argv[2]

compose = yaml.safe_load((root / "docker/docker-compose.yml").read_text())
postgres = compose["services"]["postgres"]
if postgres.get("image") != expected_image:
    raise SystemExit("local Compose PostgreSQL image does not use the pinned PostgreSQL 18 proposal")
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
cron_image = cronjob["spec"]["jobTemplate"]["spec"]["template"]["spec"]["containers"][0]["image"]
if cron_image != expected_image:
    raise SystemExit("scheduled PostgreSQL dump client does not use the pinned PostgreSQL 18 proposal")

dump_dockerfile = (root / "docker/pg-dump-cron.Dockerfile").read_text()
if "FROM postgres:18@sha256:" not in dump_dockerfile:
    raise SystemExit("the existing PostgreSQL dump client must remain on a pinned PostgreSQL 18 image")

for path in (
    "docker/docker-compose.yml",
    "k8s/helm/firemud/values-hosted-shared.example.yaml",
    "k8s/postgres/pg-dump-cronjob.yaml",
):
    content = (root / path).read_text()
    if "postgres:16@sha256:" in content:
        raise SystemExit(f"{path} retains a PostgreSQL 16 image ref in the converged local/preview slice")
PY

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
    wrong-major|incomplete-postgres18|postgres18|postgres18-with-sibling)
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
      ;;
    *) fail "unknown PostgreSQL data layout fixture: $fixture" ;;
  esac

  if sh "$POSTGRES_LAYOUT_ENTRYPOINT" --check-layout "$fixture_dir" >"$output_file" 2>&1; then
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
assert_layout "valid PostgreSQL 18 child" 0 postgres18 ""

run_docker_proof() {
  command -v docker >/dev/null 2>&1 || fail "required PostgreSQL runtime proof needs Docker on the CI runner"
  docker info >/dev/null 2>&1 || fail "required PostgreSQL runtime proof could not reach the CI Docker daemon"

  local run_token
  local network_name
  local source_volume
  local target_volume
  local source_container
  local target_container
  local backup_dir
  local bad_dump
  local bad_key
  local good_dump
  local good_key
  local aws_stub
  local marker
  local password
  local source_version
  local target_data_directory
  local actual_marker
  local failed_table_missing
  local cleanup_dir

  run_token="${GITHUB_RUN_ID:-local}-a${GITHUB_RUN_ATTEMPT:-0}-$(date -u +%Y%m%d%H%M%S)-$$-${RANDOM:-0}"
  run_token="$(printf '%s' "$run_token" | tr -cd 'A-Za-z0-9_.-')"
  network_name="firemud-pg18-upgrade-${run_token}"
  source_volume="firemud-pg18-source-${run_token}"
  target_volume="firemud-pg18-target-${run_token}"
  source_container="firemud-pg16-source-${run_token}"
  target_container="firemud-pg18-target-${run_token}"
  cleanup_dir="$(mktemp -d)"
  backup_dir="$cleanup_dir/backups"
  bad_dump="$backup_dir/15min/firemud_99999999999999.sql.gz"
  bad_key="15min/firemud_99999999999999.sql.gz"
  aws_stub="$cleanup_dir/aws"
  marker="retained-${run_token}"
  password="firemud-ci-${run_token}"

  cleanup() {
    docker rm -f "$source_container" "$target_container" >/dev/null 2>&1 || true
    docker network rm "$network_name" >/dev/null 2>&1 || true
    docker volume rm "$source_volume" "$target_volume" >/dev/null 2>&1 || true
    rm -rf -- "$cleanup_dir"
  }
  trap cleanup EXIT INT TERM

  mkdir -p "$backup_dir" "$backup_dir/15min"
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
  wait_for_postgres "$source_container"
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

  docker run --rm \
    --network "$network_name" \
    --volume "$ROOT_DIR/dev-tools/backups/pg-dump-rotate.sh:/usr/local/bin/pg-dump-rotate.sh:ro" \
    --volume "$backup_dir:/backups" \
    --env FIREMUD_POSTGRES_HOST=postgres-source \
    --env FIREMUD_POSTGRES_USER=firemud \
    --env FIREMUD_POSTGRES_DB=firemud \
    --env "PGPASSWORD=$password" \
    --env BACKUP_DIR=/backups \
    --entrypoint /bin/bash \
    "$POSTGRES_DUMP_CLIENT_IMAGE" /usr/local/bin/pg-dump-rotate.sh \
    >"$cleanup_dir/backup.log" 2>&1 || fail "the canonical scheduled logical dump did not complete"
  good_dump="$(find "$backup_dir/15min" -maxdepth 1 -type f -name 'firemud_*.sql.gz' -print -quit)"
  [[ -n "$good_dump" && -s "$good_dump" ]] || fail "the canonical scheduled logical dump did not publish a gzip artifact"
  gzip -t "$good_dump" || fail "the canonical scheduled logical dump artifact is not valid gzip"
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
  wait_for_postgres "$target_container"
  target_data_directory="$(docker exec "$target_container" psql -Atq -U firemud -d firemud -c 'SHOW data_directory')"
  [[ "$target_data_directory" == "/var/lib/postgresql/18/docker" ]] || fail "the PostgreSQL 18 target did not use its versioned data directory"

  docker run --rm \
    --volume "$target_volume:/var/lib/postgresql:ro" \
    --volume "$POSTGRES_LAYOUT_ENTRYPOINT:/usr/local/bin/firemud-postgres-data-layout-entrypoint.sh:ro" \
    --entrypoint /usr/local/bin/firemud-postgres-data-layout-entrypoint.sh \
    "$POSTGRES18_IMAGE" --check-layout /var/lib/postgresql \
    >"$cleanup_dir/current-guard.log" 2>&1 || fail "the PostgreSQL 18 guard rejected a complete versioned target cluster"

  if run_logical_restore "$network_name" "$bad_key" "$aws_stub" "$backup_dir" "$cleanup_dir" "$password"; then
    fail "the deliberately invalid target import unexpectedly succeeded"
  fi
  failed_table_missing="$(docker exec "$target_container" psql -Atq -U firemud -d firemud -c "SELECT to_regclass('public.postgres18_failed_import') IS NULL")"
  [[ "$failed_table_missing" == "t" ]] || fail "the failed target import left partial database changes"

  docker start "$source_container" >/dev/null
  wait_for_postgres "$source_container"
  actual_marker="$(docker exec "$source_container" psql -Atq -U firemud -d firemud -c 'SELECT marker FROM public.postgres18_upgrade_probe WHERE id = 1')"
  [[ "$actual_marker" == "$marker" ]] || fail "the original PostgreSQL 16 source no longer contains its seeded data after a failed target import"
  docker stop "$source_container" >/dev/null

  run_logical_restore "$network_name" "$good_key" "$aws_stub" "$backup_dir" "$cleanup_dir" "$password" \
    || fail "the canonical scheduled logical restore did not import into the separate PostgreSQL 18 target"
  actual_marker="$(docker exec "$target_container" psql -Atq -U firemud -d firemud -c 'SELECT marker FROM public.postgres18_upgrade_probe WHERE id = 1')"
  [[ "$actual_marker" == "$marker" ]] || fail "the PostgreSQL 18 target did not retain the logical backup's seeded data"
  [[ "$(docker inspect --format '{{.State.Running}}' "$source_container")" == false ]] \
    || fail "the PostgreSQL 16 source should remain stopped while the PostgreSQL 18 target is validated"

  echo "PostgreSQL runtime proof passed: PostgreSQL 16 source preserved; separate PostgreSQL 18 target started and restored from the canonical gzip/plain-SQL path."
}

wait_for_postgres() {
  local container="$1"
  local attempt
  for ((attempt = 0; attempt < 60; attempt++)); do
    if docker exec "$container" pg_isready -U firemud -d firemud >/dev/null 2>&1; then
      return 0
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
