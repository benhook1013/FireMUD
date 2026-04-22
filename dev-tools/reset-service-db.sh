#!/usr/bin/env bash
# Reset one service's local PostgreSQL state by dropping the tables declared in
# that service's migrations plus the service's Flyway history table, then rerun
# the service's Flyway migrations.
#
# Usage:
#   reset-service-db.sh <service> [--dry-run]
#
# The script is local-development tooling for the shared docker compose Postgres
# stack. It does not touch other services' history tables.

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
COMPOSE_FILES=(-f "$ROOT_DIR/docker/docker-compose.yml" -f "$ROOT_DIR/docker/docker-compose.override.yml")

declare -A SERVICE_PROJECT=(
  [account-service]=account-service
  [automation-scripting-service]=automation-scripting-service
  [entity-management-service]=entity-management-service
  [game-design-service]=game-design-service
  [game-logic-service]=game-logic-service
  [game-session-service]=game-session-service
  [gateway]=spring-cloud-gateway
  [logging-admin-service]=logging-admin-service
  [social-groups-service]=social-groups-service
  [spring-cloud-gateway]=spring-cloud-gateway
  [tcp-proxy-service]=tcp-proxy-service
  [world-management-service]=world-management-service
)

declare -A SERVICE_COMPOSE=(
  [account-service]=account-service
  [automation-scripting-service]=automation-scripting-service
  [entity-management-service]=entity-management-service
  [game-design-service]=game-design-service
  [game-logic-service]=game-logic-service
  [game-session-service]=game-session-service
  [gateway]=gateway
  [logging-admin-service]=logging-admin-service
  [social-groups-service]=social-groups-service
  [spring-cloud-gateway]=gateway
  [tcp-proxy-service]=tcp-proxy-service
  [world-management-service]=world-management-service
)

declare -A SERVICE_FLYWAY_TABLE=(
  [account-service]=flyway_schema_history_account_service
  [automation-scripting-service]=flyway_schema_history_automation_scripting_service
  [entity-management-service]=flyway_schema_history_entity_management_service
  [game-design-service]=flyway_schema_history_game_design_service
  [game-logic-service]=flyway_schema_history_game_logic_service
  [game-session-service]=flyway_schema_history_game_session_service
  [gateway]=flyway_schema_history_gateway
  [logging-admin-service]=flyway_schema_history_logging_admin_service
  [social-groups-service]=flyway_schema_history_social_groups_service
  [spring-cloud-gateway]=flyway_schema_history_gateway
  [tcp-proxy-service]=flyway_schema_history_tcp_proxy_service
  [world-management-service]=flyway_schema_history_world_management_service
)

usage() {
  cat <<'EOF'
Usage: reset-service-db.sh <service> [--dry-run]

Known services:
  account-service
  automation-scripting-service
  entity-management-service
  game-design-service
  game-logic-service
  game-session-service
  gateway
  logging-admin-service
  social-groups-service
  spring-cloud-gateway
  tcp-proxy-service
  world-management-service

--dry-run prints the derived destructive scope without touching the database.
EOF
}

quote_identifier() {
  local identifier="$1"
  local quoted=()
  IFS='.' read -r -a quoted <<<"$identifier"

  local part
  local result=""
  for part in "${quoted[@]}"; do
    if [[ -z "$result" ]]; then
      result="\"$part\""
    else
      result+=".\"$part\""
    fi
  done
  printf '%s' "$result"
}

discover_owned_tables() {
  local migration_dir="$1"
  python3 - "$migration_dir" <<'PY'
import pathlib
import re
import sys

migration_dir = pathlib.Path(sys.argv[1])
pattern = re.compile(
    r"^\s*CREATE\s+TABLE(?:\s+IF\s+NOT\s+EXISTS)?\s+"
    r"(?:(?P<schema>[A-Za-z_][A-Za-z0-9_]*)\.)?"
    r"(?P<table>[A-Za-z_][A-Za-z0-9_]*)\b",
    re.IGNORECASE,
)

tables = []
seen = set()
for path in sorted(migration_dir.rglob("*.sql")):
    for line in path.read_text(encoding="utf-8").splitlines():
        match = pattern.match(line)
        if match is None:
            continue
        if match.group("schema"):
            table = f'{match.group("schema")}.{match.group("table")}'
        else:
            table = match.group("table")
        if table in seen:
            continue
        seen.add(table)
        tables.append(table)

for table in tables:
    print(table)
PY
}

SERVICE="${1:-}"
DRY_RUN=0

if [[ $# -lt 1 ]]; then
  usage
  exit 1
fi

if [[ "$SERVICE" == "-h" || "$SERVICE" == "--help" ]]; then
  usage
  exit 0
fi

shift
while [[ $# -gt 0 ]]; do
  case "$1" in
    --dry-run)
      DRY_RUN=1
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "Unknown argument: $1" >&2
      usage
      exit 1
      ;;
  esac
  shift
done

PROJECT="${SERVICE_PROJECT[$SERVICE]:-}"
COMPOSE_SERVICE="${SERVICE_COMPOSE[$SERVICE]:-}"
HISTORY_TABLE="${SERVICE_FLYWAY_TABLE[$SERVICE]:-}"

if [[ -z "$PROJECT" || -z "$COMPOSE_SERVICE" || -z "$HISTORY_TABLE" ]]; then
  echo "Unknown service: $SERVICE" >&2
  usage >&2
  exit 1
fi

MIGRATION_DIR="$ROOT_DIR/services/$PROJECT/src/main/resources/db/migration"
if [[ ! -d "$MIGRATION_DIR" ]]; then
  echo "No migration directory found for $SERVICE: $MIGRATION_DIR" >&2
  exit 1
fi

mapfile -t OWNED_TABLES < <(discover_owned_tables "$MIGRATION_DIR")

if [[ "$DRY_RUN" -eq 1 ]]; then
  echo "Service: $SERVICE"
  echo "Gradle project: $PROJECT"
  echo "Flyway history table: $HISTORY_TABLE"
  echo "Owned tables:"
  if [[ "${#OWNED_TABLES[@]}" -eq 0 ]]; then
    echo "  (none discovered)"
  else
    printf '  %s\n' "${OWNED_TABLES[@]}"
  fi
  exit 0
fi

if ! command -v docker >/dev/null 2>&1; then
  echo "Error: docker is required" >&2
  exit 1
fi

GRADLEW="$ROOT_DIR/gradlew"
if [[ ! -x "$GRADLEW" ]]; then
  echo "Error: gradlew not found or not executable at $GRADLEW" >&2
  exit 1
fi

: "${FIREMUD_POSTGRES_HOST:=localhost}"
: "${FIREMUD_POSTGRES_PORT:=5432}"
: "${FIREMUD_POSTGRES_DB:=firemud}"
: "${FIREMUD_POSTGRES_USER:=firemud}"
: "${FIREMUD_POSTGRES_PASSWORD:=firemud}"

export FIREMUD_POSTGRES_HOST FIREMUD_POSTGRES_PORT FIREMUD_POSTGRES_DB FIREMUD_POSTGRES_USER FIREMUD_POSTGRES_PASSWORD

WAS_RUNNING=0
if [[ -n "$(docker compose "${COMPOSE_FILES[@]}" ps -q "$COMPOSE_SERVICE" 2>/dev/null || true)" ]]; then
  WAS_RUNNING=1
  docker compose "${COMPOSE_FILES[@]}" stop "$COMPOSE_SERVICE" >/dev/null
fi

docker compose "${COMPOSE_FILES[@]}" up -d postgres >/dev/null

SQL="DROP TABLE IF EXISTS $(quote_identifier "$HISTORY_TABLE") CASCADE;"
for table in "${OWNED_TABLES[@]}"; do
  SQL+=$'\n'"DROP TABLE IF EXISTS $(quote_identifier "$table") CASCADE;"
done

docker compose "${COMPOSE_FILES[@]}" exec -T postgres psql \
  -U "$FIREMUD_POSTGRES_USER" \
  -d "$FIREMUD_POSTGRES_DB" \
  -v ON_ERROR_STOP=1 \
  -c "$SQL" >/dev/null

"$GRADLEW" --no-configuration-cache ":$PROJECT:flywayMigrate"

if [[ "$WAS_RUNNING" -eq 1 ]]; then
  docker compose "${COMPOSE_FILES[@]}" up -d --no-deps "$COMPOSE_SERVICE" >/dev/null
fi

echo "Reset complete for $SERVICE"
