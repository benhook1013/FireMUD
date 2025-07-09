#!/usr/bin/env bash
# Generate ERD diagrams and baseline migration scripts
set -euo pipefail

OUT_DIR="design/erd"
mkdir -p "$OUT_DIR"

for service in services/*-service; do
  MIG_DIR="$service/src/main/resources/db/migration"
  if [ -d "$MIG_DIR" ]; then
    NAME=$(basename "$service")
    BASELINE="$OUT_DIR/${NAME}-baseline.sql"
    DBML_OUT="$OUT_DIR/${NAME}.dbml"
    cat "$MIG_DIR"/*.sql > "$BASELINE"
    npx -y -p @dbml/cli sql2dbml --postgres "$BASELINE" --out-file "$DBML_OUT"
  fi
done

echo "ERD diagrams written to $OUT_DIR"
