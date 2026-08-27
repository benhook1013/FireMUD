#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SCRIPT="$ROOT_DIR/dev-tools/docs/generate-erd.sh"

require_contains() {
  local expected="$1"
  if ! grep -Fq -- "$expected" "$SCRIPT"; then
    echo "generate-erd.sh must contain: $expected" >&2
    exit 1
  fi
}

require_contains 'POSTGRES_READY_ATTEMPTS=60'
require_contains 'POSTGRES_STABLE_PROBES=2'
require_contains "-Atqc 'SELECT 1'"
require_contains 'PostgreSQL did not pass stable SQL readiness probes'
require_contains "docker logs --tail 100 \"\$POSTGRES_CONTAINER\""

if grep -Fq "until docker exec \"\$POSTGRES_CONTAINER\" pg_isready" "$SCRIPT"; then
  echo "generate-erd.sh must not hand off on a single pg_isready result" >&2
  exit 1
fi

echo "ERD workflow contract checks passed"
