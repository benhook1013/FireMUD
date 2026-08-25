#!/usr/bin/env bash
# Create a plain-SQL pg_dump, enforce retention, and optionally upload to S3/MinIO.
# The scheduled hosted artifact is gzip-compressed SQL and is restored with psql.
set -euo pipefail

BACKUP_DIR=${BACKUP_DIR:-/backups}
BUCKET=${PG_DUMP_BUCKET:-}
ENDPOINT=${PG_DUMP_ENDPOINT:-}

PREFIX=firemud

mkdir -p "$BACKUP_DIR/15min" "$BACKUP_DIR/daily" "$BACKUP_DIR/weekly" "$BACKUP_DIR/monthly"
TS=$(date +%Y%m%d%H%M%S)
DUMP="$BACKUP_DIR/15min/${PREFIX}_${TS}.sql.gz"
PARTIAL_DUMP=$(mktemp "$BACKUP_DIR/15min/.${PREFIX}_${TS}.XXXXXX.sql.gz")

cleanup_partial_dump() {
  rm -f -- "$PARTIAL_DUMP"
}

trap cleanup_partial_dump EXIT

# Install awscli if bucket upload is enabled and aws command missing
if [ -n "$BUCKET" ] && ! command -v aws >/dev/null 2>&1; then
  apt-get update -y >/dev/null && apt-get install -y awscli >/dev/null
fi

if pg_dump -Fp \
  -h "$FIREMUD_POSTGRES_HOST" \
  -U "$FIREMUD_POSTGRES_USER" \
  -d "$FIREMUD_POSTGRES_DB" | gzip > "$PARTIAL_DUMP"; then
  mv -f -- "$PARTIAL_DUMP" "$DUMP"
  trap - EXIT
else
  echo "Failed to create pg_dump artifact" >&2
  exit 1
fi

HOUR=$(date +%H)
# keep last 96 15min dumps
cd "$BACKUP_DIR/15min"
find . -maxdepth 1 -name "${PREFIX}_*.sql.gz" -printf '%T@ %p\n' | sort -nr | tail -n +97 | cut -d' ' -f2- | xargs -r rm --

DOW=$(date +%u) # 1-7 (Mon-Sun)
DOM=$(date +%d)

if [ "$HOUR" = "00" ]; then
  DAILY_DEST="$BACKUP_DIR/daily/${PREFIX}_${TS}.sql.gz"
  cp "$DUMP" "$DAILY_DEST"
  cd "$BACKUP_DIR/daily"
  find . -maxdepth 1 -name "${PREFIX}_*.sql.gz" -printf '%T@ %p\n' | sort -nr | tail -n +11 | cut -d' ' -f2- | xargs -r rm --
fi

if [ "$DOW" = "7" ]; then
  WEEKLY_DEST="$BACKUP_DIR/weekly/${PREFIX}_${TS}.sql.gz"
  cp "$DUMP" "$WEEKLY_DEST"
  cd "$BACKUP_DIR/weekly"
  find . -maxdepth 1 -name "${PREFIX}_*.sql.gz" -printf '%T@ %p\n' | sort -nr | tail -n +4 | cut -d' ' -f2- | xargs -r rm --
fi

if [ "$DOM" = "01" ]; then
  MONTHLY_DEST="$BACKUP_DIR/monthly/${PREFIX}_${TS}.sql.gz"
  cp "$DUMP" "$MONTHLY_DEST"
  cd "$BACKUP_DIR/monthly"
  find . -maxdepth 1 -name "${PREFIX}_*.sql.gz" -printf '%T@ %p\n' | sort -nr | tail -n +4 | cut -d' ' -f2- | xargs -r rm --
fi

if [ -n "$BUCKET" ]; then
  AWS_ENDPOINT_ARGS=()
  if [ -n "$ENDPOINT" ]; then
    AWS_ENDPOINT_ARGS=(--endpoint-url "$ENDPOINT")
  fi
  echo "Uploading $DUMP to s3://$BUCKET"
  if ! aws s3 cp "$DUMP" "s3://$BUCKET/15min/" "${AWS_ENDPOINT_ARGS[@]}"; then
    echo "Failed to upload 15min dump" >&2
    exit 1
  fi
  if [ "$HOUR" = "00" ]; then
    if ! aws s3 cp "$DUMP" "s3://$BUCKET/daily/" "${AWS_ENDPOINT_ARGS[@]}"; then
      echo "Failed to upload daily dump" >&2
      exit 1
    fi
  fi
  if [ "$DOW" = "7" ]; then
    if ! aws s3 cp "$DUMP" "s3://$BUCKET/weekly/" "${AWS_ENDPOINT_ARGS[@]}"; then
      echo "Failed to upload weekly dump" >&2
      exit 1
    fi
  fi
  if [ "$DOM" = "01" ]; then
    if ! aws s3 cp "$DUMP" "s3://$BUCKET/monthly/" "${AWS_ENDPOINT_ARGS[@]}"; then
      echo "Failed to upload monthly dump" >&2
      exit 1
    fi
  fi
fi
