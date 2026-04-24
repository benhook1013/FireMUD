#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
if (( $# > 0 )); then
  COMPOSE_FILES=("$@")
else
  COMPOSE_FILES=(-f "$ROOT_DIR/docker/docker-compose.yml" -f "$ROOT_DIR/docker/docker-compose.override.yml")
fi
WAIT_TIMEOUT_SECONDS="${SMOKE_HEALTH_TIMEOUT_SECONDS:-240}"
POLL_INTERVAL_SECONDS="${SMOKE_HEALTH_POLL_INTERVAL_SECONDS:-5}"
REQUIRED_SERVICES=(
  account-service
  automation-scripting-service
  entity-management-service
  game-design-service
  game-logic-service
  game-session-service
  gateway
  logging-admin-service
  minio
  postgres
  redis-cache
  redis-coord
  social-groups-service
  tcp-proxy-service
  world-management-service
)

status_file="$(mktemp)"
trap 'rm -f "$status_file"' EXIT

deadline=$((SECONDS + WAIT_TIMEOUT_SECONDS))

while (( SECONDS < deadline )); do
  if docker compose "${COMPOSE_FILES[@]}" ps --format json >"$status_file"; then
    if python3 - "$status_file" "${REQUIRED_SERVICES[@]}" <<'PY'
import json
import pathlib
import sys

status_path = pathlib.Path(sys.argv[1])
required = sys.argv[2:]

services = {}
for raw_line in status_path.read_text().splitlines():
    line = raw_line.strip()
    if not line:
        continue
    entry = json.loads(line)
    services[entry["Service"]] = entry

missing = []
not_ready = []
for service in required:
    entry = services.get(service)
    if entry is None:
        missing.append(service)
        continue
    state = entry.get("State", "")
    health = entry.get("Health", "")
    if state != "running" or health != "healthy":
        rendered = f"{service}: state={state or 'unknown'}"
        if health:
            rendered += f", health={health}"
        status = entry.get("Status")
        if status:
            rendered += f", status={status}"
        not_ready.append(rendered)

if missing or not_ready:
    if missing:
        print("Missing required services:")
        for service in missing:
            print(f"  - {service}")
    if not_ready:
        print(f"Services not healthy yet ({len(not_ready)} pending):")
        for line in not_ready:
            print(f"  - {line}")
    sys.exit(1)

print("All required compose services are running and healthy.")
PY
    then
      exit 0
    fi
  fi

  sleep "$POLL_INTERVAL_SECONDS"
done

echo "Timed out waiting for required compose services to become healthy."
docker compose "${COMPOSE_FILES[@]}" ps
echo
echo "Recent logs for required services that are missing, not running, or not healthy:"
python3 - "$status_file" "${REQUIRED_SERVICES[@]}" <<'PY' | while IFS= read -r service; do
import json
import pathlib
import sys

status_path = pathlib.Path(sys.argv[1])
required = sys.argv[2:]

services = {}
if status_path.exists():
    for raw_line in status_path.read_text().splitlines():
        line = raw_line.strip()
        if not line:
            continue
        entry = json.loads(line)
        services[entry["Service"]] = entry

for service in required:
    entry = services.get(service)
    if entry is None or entry.get("State") != "running" or entry.get("Health") != "healthy":
        print(service)
PY
  echo
  echo "=== ${service} ==="
  docker compose "${COMPOSE_FILES[@]}" logs --tail 80 "$service" || true
done

exit 1
