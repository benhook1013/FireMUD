#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SCRIPT="$ROOT_DIR/dev-tools/docs/generate-erd.sh"
WORKFLOW="$ROOT_DIR/.github/workflows/erd.yml"

require_contains() {
  local expected="$1"
  if ! grep -Fq -- "$expected" "$SCRIPT"; then
    echo "generate-erd.sh must contain: $expected" >&2
    exit 1
  fi
}

require_workflow_contains() {
  local expected="$1"
  if ! grep -Fq -- "$expected" "$WORKFLOW"; then
    echo "erd.yml must contain: $expected" >&2
    exit 1
  fi
}

require_contains 'POSTGRES_READY_ATTEMPTS=60'
require_contains 'POSTGRES_STABLE_PROBES=2'
require_contains 'POSTGRES_PROBE_TIMEOUT_SECONDS=5'
require_contains "-Atqc 'SELECT 1'"
require_contains 'PostgreSQL did not pass stable SQL readiness probes'
require_contains "docker logs --tail 100 \"\$POSTGRES_CONTAINER\""
require_workflow_contains 'timeout-minutes: 30'

sql_probe_count=$(grep -Fc -- "-Atqc 'SELECT 1'" "$SCRIPT" || true)
if [[ "$sql_probe_count" -ne 2 ]]; then
  echo "generate-erd.sh must contain exactly two SQL readiness probes (found $sql_probe_count)" >&2
  exit 1
fi

timeout_probe_count=$(grep -Fc -- "timeout \"\$POSTGRES_PROBE_TIMEOUT_SECONDS\" docker exec \"\$POSTGRES_CONTAINER\" psql" "$SCRIPT" || true)
if [[ "$timeout_probe_count" -ne "$sql_probe_count" ]]; then
  echo "generate-erd.sh must wrap both SQL readiness probes with the named timeout" >&2
  exit 1
fi

if grep -Fq "until docker exec \"\$POSTGRES_CONTAINER\" pg_isready" "$SCRIPT"; then
  echo "generate-erd.sh must not hand off on a single pg_isready result" >&2
  exit 1
fi

echo "ERD workflow contract checks passed"
