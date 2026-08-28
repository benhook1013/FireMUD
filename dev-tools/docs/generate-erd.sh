#!/usr/bin/env bash
# Generate ERD diagrams and verify Flyway migrations for all services.
set -euo pipefail

# Check for Docker since ERD generation requires it
if ! command -v docker >/dev/null 2>&1; then
  echo "Error: Docker is not installed or not in PATH" >&2
  exit 1
fi

SERVICES=(
  account-service
  automation-scripting-service
  entity-management-service
  game-design-service
  game-logic-service
  game-session-service
  logging-admin-service
  social-groups-service
  spring-cloud-gateway
  tcp-proxy-service
  world-management-service
)

OUT_DIR="design/erd"
mkdir -p "$OUT_DIR"

POSTGRES_CONTAINER="firemud-erd-postgres"

docker run --name "$POSTGRES_CONTAINER" -e POSTGRES_USER=firemud \
  -e POSTGRES_PASSWORD=firemud -e POSTGRES_DB=firemud -p 5432:5432 -d postgres:16
trap 'docker rm -f "$POSTGRES_CONTAINER" >/dev/null' EXIT

POSTGRES_READY_ATTEMPTS=60
POSTGRES_STABLE_PROBES=2
POSTGRES_PROBE_TIMEOUT_SECONDS=5
postgres_ready=false
for attempt in $(seq 1 "$POSTGRES_READY_ATTEMPTS"); do
  if timeout "$POSTGRES_PROBE_TIMEOUT_SECONDS" docker exec "$POSTGRES_CONTAINER" psql -U firemud -d firemud \
      -v ON_ERROR_STOP=1 -Atqc 'SELECT 1' >/dev/null 2>&1; then
    sleep 1
    if timeout "$POSTGRES_PROBE_TIMEOUT_SECONDS" docker exec "$POSTGRES_CONTAINER" psql -U firemud -d firemud \
        -v ON_ERROR_STOP=1 -Atqc 'SELECT 1' >/dev/null 2>&1; then
      postgres_ready=true
      echo "PostgreSQL passed $POSTGRES_STABLE_PROBES stable SQL readiness probes on attempt $attempt."
      break
    fi
  fi
  sleep 1
done

if [[ "$postgres_ready" != true ]]; then
  echo "PostgreSQL did not pass stable SQL readiness probes within ${POSTGRES_READY_ATTEMPTS} attempts." >&2
  docker logs --tail 100 "$POSTGRES_CONTAINER" >&2 || true
  exit 1
fi

export FIREMUD_POSTGRES_HOST=localhost
export FIREMUD_POSTGRES_PORT=5432
export FIREMUD_POSTGRES_DB=firemud
export FIREMUD_POSTGRES_USER=firemud
export FIREMUD_POSTGRES_PASSWORD=firemud

# Configure Flyway to use the temporary Postgres instance. The Gradle Flyway plugin
# reads standard `FLYWAY_*` environment variables for connection details, so map
# the FireMUD-specific settings accordingly.
export FLYWAY_URL="jdbc:postgresql://${FIREMUD_POSTGRES_HOST}:${FIREMUD_POSTGRES_PORT}/${FIREMUD_POSTGRES_DB}"
export FLYWAY_USER="$FIREMUD_POSTGRES_USER"
export FLYWAY_PASSWORD="$FIREMUD_POSTGRES_PASSWORD"

for service in "${SERVICES[@]}"; do
  MIGRATION_DIR="services/$service/src/main/resources/db/migration"
  if [ -d "$MIGRATION_DIR" ]; then
    docker exec "$POSTGRES_CONTAINER" psql -U firemud -d firemud -v ON_ERROR_STOP=1 -c \
      "DROP SCHEMA IF EXISTS public CASCADE; CREATE SCHEMA public; DROP SCHEMA IF EXISTS saga CASCADE; CREATE SCHEMA saga;"

    echo "Running Flyway migrations for $service"
    ./gradlew --no-configuration-cache ":$service:flywayMigrate"

    echo "Generating ERD for $service"
    docker run --rm \
      --network host \
      --user "$(id -u):$(id -g)" \
      -v "$(pwd)/$OUT_DIR":/output \
      schemacrawler/schemacrawler:latest \
      /opt/schemacrawler/bin/schemacrawler.sh \
      --server=postgresql \
      --host=localhost --port=5432 \
      --user=firemud --password=firemud \
      --schemas=public \
      --command=schema \
      --info-level=standard \
      --output-format=png \
      --output-file="/output/${service}.png"
  fi

done

echo "Generating ERD for saga schema"
docker run --rm \
  --network host \
  --user "$(id -u):$(id -g)" \
  -v "$(pwd)/$OUT_DIR":/output \
  schemacrawler/schemacrawler:latest \
  /opt/schemacrawler/bin/schemacrawler.sh \
  --server=postgresql \
  --host=localhost --port=5432 \
  --user=firemud --password=firemud \
  --schemas=saga \
  --command=schema \
  --info-level=standard \
  --output-format=svg \
  --output-file="/output/erd_saga.svg"

echo "ERD diagrams saved to $OUT_DIR"
