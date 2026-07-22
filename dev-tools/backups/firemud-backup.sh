#!/usr/bin/env bash
# Run an online PostgreSQL snapshot without pausing gameplay ticks.
set -euo pipefail

timestamp="$(date +%Y%m%d%H%M%S)"
tmp_file="$(mktemp "firemud_${timestamp}.XXXXXX.sql.gz.tmp")"
target_file="${tmp_file%.tmp}"

cleanup() {
  rm -f -- "$tmp_file"
}

trap cleanup EXIT

pg_dump -h "${FIREMUD_POSTGRES_HOST}" -U "${FIREMUD_POSTGRES_USER}" -d "${FIREMUD_POSTGRES_DB}" | gzip > "$tmp_file"
mv -- "$tmp_file" "$target_file"
trap - EXIT
