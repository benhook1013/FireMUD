#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
TEMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TEMP_DIR"' EXIT

BIN_DIR="$TEMP_DIR/bin"
QUERY_LOG="$TEMP_DIR/aws-query"
mkdir -p "$BIN_DIR"

cat > "$BIN_DIR/velero" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
if [[ " $* " == *' --no-headers '* ]]; then
  echo 'simulated Velero CLI does not support --no-headers' >&2
  exit 64
fi
case "${FAKE_AWS_SCENARIO:-}" in
  velero-error)
    echo 'simulated Velero listing failure' >&2
    exit 42
    ;;
  velero-empty)
    printf '%s\n' 'NAME STATUS'
    exit 0
    ;;
  *)
    printf '%s\n' 'NAME STATUS' 'backup-1 Completed' 'backup-2 Completed'
    ;;
esac
EOF

cat > "$BIN_DIR/aws" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail

query=''
endpoint=''
while (($#)); do
  if [[ "$1" == '--query' ]]; then
    query="$2"
    shift 2
  elif [[ "$1" == '--endpoint-url' ]]; then
    endpoint="$2"
    shift 2
  else
    shift
  fi
done

if [[ "$query" != *'starts_with(Key, `15min/firemud_`)'* || "$query" != *'ends_with(Key, `.sql.gz`)'* || "$query" != *'.[LastModified, Key]'* ]]; then
  echo "the aws query did not emit filtered [LastModified, Key] rows: $query" >&2
  exit 1
fi
if [[ "$query" == *'sort_by('* ]]; then
  echo "the aws query still performs a per-page sort: $query" >&2
  exit 1
fi
if [[ -n "${EXPECTED_ENDPOINT:-}" && "$endpoint" != "$EXPECTED_ENDPOINT" ]]; then
  echo "the endpoint argument was not preserved: expected $EXPECTED_ENDPOINT, got $endpoint" >&2
  exit 1
fi
printf '%s\n' "$query" > "$FAKE_QUERY_LOG"

case "$FAKE_AWS_SCENARIO" in
  global-latest)
    # Page one has an older match and an unrelated object; page two has the
    # global latest match and another unrelated object. The 00:04 capture is
    # deliberately older by LastModified so consumers must sort by key.
    printf '2026-08-25T00:01:00Z\t15min/firemud_20260825000100.sql.gz\n'
    printf '2026-08-25T00:02:00Z\t15min/not-a-dump.txt\n'
    printf '2026-08-25T00:03:00Z\t15min/firemud_20260825000300.sql.gz\n'
    printf '2026-08-25T00:05:00Z\t15min/unrelated-newer.sql.gz\n'
    printf '2026-08-25T00:04:00Z\t15min/not-a-dump.dump\n'
    printf '2026-08-25T00:00:30Z\t15min/firemud_20260825000400.sql.gz\n'
    ;;
  earlier-match-later-empty)
    printf '2026-08-25T00:05:00Z\t15min/firemud_20260825000500.sql.gz\n'
    printf '%s\n' 'None'
    ;;
  large-listing)
    # Keep enough sorted output to expose an early consumer exit as sort SIGPIPE under pipefail.
    awk 'BEGIN {
      for (i = 1; i <= 200000; i++) {
        printf "2026-08-25T00:00:00Z\t15min/firemud_%014d.sql.gz\n", i
      }
    }'
    ;;
  no-match)
    printf '2026-08-25T00:06:00Z\t15min/not-a-dump.dump\n'
    printf '%s\n' 'None'
    ;;
  invalid-timestamp)
    printf '2026-08-25T00:06:00Z\t15min/firemud_2026082500010.sql.gz\n'
    printf '2026-08-25T00:07:00Z\t15min/firemud_202608250001000.sql.gz\n'
    ;;
  velero-error|velero-empty)
    printf '2026-08-25T00:01:00Z\t15min/firemud_20260825000100.sql.gz\n'
    ;;
  aws-error)
    echo 'simulated AWS listing failure' >&2
    exit 42
    ;;
  *)
    echo "unexpected fake aws scenario: $FAKE_AWS_SCENARIO" >&2
    exit 1
    ;;
esac
EOF
chmod +x "$BIN_DIR/velero" "$BIN_DIR/aws"

run_case() {
  local script="$1"
  local scenario="$2"
  local expected_status="$3"
  local expected_output="$4"
  local forbidden_output="${5:-}"
  local additional_output="${6:-}"
  local expected_endpoint="${7:-}"
  local output
  local status

  set +e
  output=$(
    PATH="$BIN_DIR:$PATH" \
      PG_DUMP_BUCKET='firemud-test' \
      PG_DUMP_ENDPOINT="$expected_endpoint" \
      EXPECTED_ENDPOINT="$expected_endpoint" \
      FAKE_AWS_SCENARIO="$scenario" \
      FAKE_QUERY_LOG="$QUERY_LOG" \
      "$script" 2>&1
  )
  status=$?
  set -e

  if [[ "$status" -ne "$expected_status" ]]; then
    echo "unexpected status for $scenario: $status; output: $output" >&2
    exit 1
  fi
  if [[ "$output" != *"$expected_output"* ]]; then
    echo "unexpected output for $scenario: $output" >&2
    exit 1
  fi
  if [[ -n "$forbidden_output" && "$output" == *"$forbidden_output"* ]]; then
    echo "forbidden output for $scenario: $output" >&2
    exit 1
  fi
  if [[ -n "$additional_output" && "$output" != *"$additional_output"* ]]; then
    echo "additional expected output missing for $scenario: $output" >&2
    exit 1
  fi
}

script="$ROOT_DIR/dev-tools/backups/verify-backups.sh"
run_case "$script" global-latest 0 'Latest pg_dump: 15min/firemud_20260825000400.sql.gz' '' 'Found 2 Velero backups in firemud'
run_case "$script" global-latest 0 'Latest pg_dump: 15min/firemud_20260825000400.sql.gz' '' '' 'https://s3.example.test'
run_case "$script" earlier-match-later-empty 0 'Latest pg_dump: 15min/firemud_20260825000500.sql.gz'
run_case "$script" large-listing 0 'Latest pg_dump: 15min/firemud_00000000200000.sql.gz'
run_case "$script" no-match 1 'No valid .sql.gz pg_dump files found'
run_case "$script" invalid-timestamp 1 'No valid .sql.gz pg_dump files found'
run_case "$script" aws-error 1 'simulated AWS listing failure' 'No valid .sql.gz pg_dump files found'
run_case "$script" velero-error 1 'simulated Velero listing failure' 'No Velero backups found'
run_case "$script" velero-empty 1 'No Velero backups found'

echo 'verify-backups Velero pagination contract checks passed'
