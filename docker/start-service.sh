#!/usr/bin/env bash
set -e
/usr/local/bin/wait-for-it.sh "${FIREMUD_POSTGRES_HOST:-postgres}" "${FIREMUD_POSTGRES_PORT:-5432}"
/usr/local/bin/wait-for-it.sh "${FIREMUD_REDIS_COORD_HOST:-redis}" "${FIREMUD_REDIS_COORD_PORT:-6379}"
exec java -jar /app/app.jar
