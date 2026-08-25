#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
TEMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TEMP_DIR"' EXIT

BIN_DIR="$TEMP_DIR/bin"
EMBEDDED_SCRIPT="$TEMP_DIR/verify-backups.sh"
QUERY_LOG="$TEMP_DIR/aws-query"
mkdir -p "$BIN_DIR"

python3 - "$ROOT_DIR" "$EMBEDDED_SCRIPT" <<'PY'
import pathlib
import sys

import yaml

root = pathlib.Path(sys.argv[1])
output = pathlib.Path(sys.argv[2])
documents = yaml.safe_load_all((root / "k8s/velero/verify-backups-cronjob.yaml").read_text())
for document in documents:
    if isinstance(document, dict) and document.get("kind") == "ConfigMap":
        output.write_text(document["data"]["verify-backups.sh"])
        output.chmod(0o755)
        break
else:
    raise SystemExit("verify-backups ConfigMap was not found")
PY

cat > "$BIN_DIR/velero" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
case "${FAKE_AWS_SCENARIO:-}" in
  velero-error)
    echo 'simulated Velero listing failure' >&2
    exit 42
    ;;
  velero-empty)
    exit 0
    ;;
  *)
    printf '%s\n' 'backup-1 Completed'
    ;;
esac
EOF

cat > "$BIN_DIR/aws" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail

query=''
while (($#)); do
  if [[ "$1" == '--query' ]]; then
    query="$2"
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
printf '%s\n' "$query" > "$FAKE_QUERY_LOG"

case "$FAKE_AWS_SCENARIO" in
  global-latest)
    # Page one has an older match and an unrelated object; page two has the
    # global latest match and another unrelated object.
    printf '2026-08-25T00:01:00Z\t15min/firemud_20260825000100.sql.gz\n'
    printf '2026-08-25T00:02:00Z\t15min/not-a-dump.txt\n'
    printf '2026-08-25T00:03:00Z\t15min/firemud_20260825000300.sql.gz\n'
    printf '2026-08-25T00:05:00Z\t15min/unrelated-newer.sql.gz\n'
    printf '2026-08-25T00:04:00Z\t15min/not-a-dump.dump\n'
    ;;
  earlier-match-later-empty)
    printf '2026-08-25T00:05:00Z\t15min/firemud_20260825000500.sql.gz\n'
    printf '%s\n' 'None'
    ;;
  no-match)
    printf '2026-08-25T00:06:00Z\t15min/not-a-dump.dump\n'
    printf '%s\n' 'None'
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
  local output
  local status

  set +e
  output=$(
    PATH="$BIN_DIR:$PATH" \
      PG_DUMP_BUCKET='firemud-test' \
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
}

for script in "$ROOT_DIR/dev-tools/backups/verify-backups.sh" "$EMBEDDED_SCRIPT"; do
  run_case "$script" global-latest 0 'Latest pg_dump: 15min/firemud_20260825000300.sql.gz'
  run_case "$script" earlier-match-later-empty 0 'Latest pg_dump: 15min/firemud_20260825000500.sql.gz'
  run_case "$script" no-match 1 'No valid .sql.gz pg_dump files found'
  run_case "$script" aws-error 1 'simulated AWS listing failure' 'No valid .sql.gz pg_dump files found'
  run_case "$script" velero-error 1 'simulated Velero listing failure' 'No Velero backups found'
  run_case "$script" velero-empty 1 'No Velero backups found'
done

echo 'verify-backups Velero pagination contract checks passed'
