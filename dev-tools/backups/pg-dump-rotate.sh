#!/usr/bin/env bash
# Create a plain-SQL pg_dump, enforce retention, and optionally upload to S3/MinIO.
# The scheduled hosted artifact is gzip-compressed SQL and is restored with psql.
set -euo pipefail

BACKUP_DIR=${BACKUP_DIR:-/backups}
BUCKET=${PG_DUMP_BUCKET:-}
ENDPOINT=${PG_DUMP_ENDPOINT:-}

PREFIX=firemud

mkdir -p "$BACKUP_DIR/15min" "$BACKUP_DIR/daily" "$BACKUP_DIR/weekly" "$BACKUP_DIR/monthly"
TS=$(date -u +%Y%m%d%H%M%S)
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
  # A same-second run has the same canonical destination. A hard link is an
  # atomic, no-clobber publication on the same filesystem and cannot replace a
  # complete artifact published by a competing run.
  if ! ln -- "$PARTIAL_DUMP" "$DUMP"; then
    echo "Failed to publish $DUMP; an existing artifact was kept" >&2
    exit 1
  fi
  rm -f -- "$PARTIAL_DUMP"
  trap - EXIT
else
  echo "Failed to create pg_dump artifact" >&2
  exit 1
fi

HOUR=$(date -u +%H)
# keep last 96 15min dumps
cd "$BACKUP_DIR/15min"
find . -maxdepth 1 -name "${PREFIX}_*.sql.gz" -printf '%T@ %p\n' | sort -nr | tail -n +97 | cut -d' ' -f2- | xargs -r rm --

DOW=$(date -u +%u) # 1-7 (Mon-Sun)
DOM=$(date -u +%d)

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

  upload_object() {
    local category=$1
    local key="$category/${PREFIX}_${TS}.sql.gz"

    echo "Uploading $DUMP to s3://$BUCKET/$key"
    if ! aws s3api put-object \
      --bucket "$BUCKET" \
      --key "$key" \
      --body "$DUMP" \
      --if-none-match '*' \
      "${AWS_ENDPOINT_ARGS[@]}"; then
      echo "Failed to upload $category dump; an existing object was kept" >&2
      return 1
    fi
  }

  if ! upload_object 15min; then
    exit 1
  fi
  if [ "$HOUR" = "00" ]; then
    if ! upload_object daily; then
      exit 1
    fi
  fi
  if [ "$DOW" = "7" ]; then
    if ! upload_object weekly; then
      exit 1
    fi
  fi
  if [ "$DOM" = "01" ]; then
    if ! upload_object monthly; then
      exit 1
    fi
  fi
fi
