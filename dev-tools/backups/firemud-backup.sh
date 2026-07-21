#!/usr/bin/env bash
# Run an online PostgreSQL snapshot without pausing gameplay ticks.
set -euo pipefail

pg_dump -h "${FIREMUD_POSTGRES_HOST}" -U "${FIREMUD_POSTGRES_USER}" -d "${FIREMUD_POSTGRES_DB}" | gzip > "firemud_$(date +%Y%m%d%H%M%S).sql.gz"
