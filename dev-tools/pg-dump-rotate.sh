#!/usr/bin/env bash
# Create a pg_dump, enforce retention, and optionally upload to S3/MinIO
set -euo pipefail

BACKUP_DIR=${BACKUP_DIR:-/backups}
BUCKET=${PG_DUMP_BUCKET:-}
ENDPOINT=${PG_DUMP_ENDPOINT:-}

# Validate upload configuration
if [[ -n "$BUCKET" || -n "$ENDPOINT" ]]; then
  if [[ -z "$BUCKET" || -z "$ENDPOINT" ]]; then
    echo "Both PG_DUMP_BUCKET and PG_DUMP_ENDPOINT must be set to upload backups" >&2
    exit 1
  fi
fi
PREFIX=firemud

mkdir -p "$BACKUP_DIR/daily" "$BACKUP_DIR/weekly" "$BACKUP_DIR/monthly"
TS=$(date +%Y%m%d%H%M%S)
DUMP="$BACKUP_DIR/daily/${PREFIX}_${TS}.sql.gz"

# Install awscli if bucket upload is enabled and aws command missing
if [ -n "$BUCKET" ] && ! command -v aws >/dev/null 2>&1; then
  apt-get update -y >/dev/null && apt-get install -y awscli >/dev/null
fi

pg_dump -h "$FIREMUD_POSTGRES_HOST" -U "$FIREMUD_POSTGRES_USER" -d "$FIREMUD_POSTGRES_DB" | gzip > "$DUMP"

# keep last 96 daily dumps
cd "$BACKUP_DIR/daily"
ls -1t ${PREFIX}_*.sql.gz | tail -n +97 | xargs -r rm --

DOW=$(date +%u) # 1-7 (Mon-Sun)
DOM=$(date +%d)

if [ "$DOW" = "7" ]; then
  WEEKLY_DEST="$BACKUP_DIR/weekly/${PREFIX}_${TS}.sql.gz"
  cp "$DUMP" "$WEEKLY_DEST"
  cd "$BACKUP_DIR/weekly"
  ls -1t ${PREFIX}_*.sql.gz | tail -n +4 | xargs -r rm --
fi

if [ "$DOM" = "01" ]; then
  MONTHLY_DEST="$BACKUP_DIR/monthly/${PREFIX}_${TS}.sql.gz"
  cp "$DUMP" "$MONTHLY_DEST"
  cd "$BACKUP_DIR/monthly"
  ls -1t ${PREFIX}_*.sql.gz | tail -n +4 | xargs -r rm --
fi

if [ -n "$BUCKET" ]; then
  AWS_ARGS=""
  if [ -n "$ENDPOINT" ]; then
    AWS_ARGS="--endpoint-url $ENDPOINT"
  fi
  echo "Uploading $DUMP to s3://$BUCKET"
  if ! aws s3 cp "$DUMP" "s3://$BUCKET/daily/" "$AWS_ARGS"; then
    echo "Failed to upload daily dump" >&2
    exit 1
  fi
  if [ "$DOW" = "7" ]; then
    if ! aws s3 cp "$DUMP" "s3://$BUCKET/weekly/" "$AWS_ARGS"; then
      echo "Failed to upload weekly dump" >&2
      exit 1
    fi
  fi
  if [ "$DOM" = "01" ]; then
    if ! aws s3 cp "$DUMP" "s3://$BUCKET/monthly/" "$AWS_ARGS"; then
      echo "Failed to upload monthly dump" >&2
      exit 1
    fi
  fi
fi
