#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT_DIR"
TEMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TEMP_DIR"' EXIT
export MOCK_DIR="$TEMP_DIR"

cat > "$TEMP_DIR/kubectl" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
[[ "$1" == --request-timeout=150s ]]
shift
case "$*" in
  'get secret minio-creds -n minio'|'get deployment minio -n minio')
    [[ -f "$MOCK_DIR/stored-user" ]] || exit 1
    exit 0
    ;;
  'get secret minio-creds -n minio -o jsonpath={.data.accesskey}')
    base64 < "$MOCK_DIR/stored-user"
    exit
    ;;
  'get secret minio-creds -n minio -o jsonpath={.data.secretkey}')
    base64 < "$MOCK_DIR/stored-password"
    exit
    ;;
esac
printf 'kubectl\n' >> "$MOCK_DIR/writes"
case "$*" in
  'create secret generic minio-creds -n minio '*)
    cmp -s "${7#--from-file=accesskey=}" <(printf '%s' "$EXPECTED_USER")
    cmp -s "${8#--from-file=secretkey=}" <(printf '%s' "$EXPECTED_PASSWORD")
    printf 'minio-secret\n' >> "$MOCK_DIR/secrets"
    ;;
  'create secret generic velero-minio-creds -n velero '*)
    cmp -s "${7#--from-file=cloud=}" <(printf '[default]\naws_access_key_id=%s\naws_secret_access_key=%s\n' "$EXPECTED_USER" "$EXPECTED_PASSWORD")
    printf 'velero-secret\n' >> "$MOCK_DIR/secrets"
    ;;
  'apply -f -') cat >/dev/null ;;
  '-n minio create -f - -o name') cat >/dev/null; echo 'job.batch/mock-bucket' ;;
  'create namespace '*|'apply -f '*|'rollout status '*|'-n minio wait '*|'-n minio delete '*) ;;
  *) exit 1 ;;
esac
EOF

cat > "$TEMP_DIR/helm" <<'EOF'
#!/usr/bin/env bash
printf 'helm\n' >> "$MOCK_DIR/writes"
EOF

cat > "$TEMP_DIR/openssl" <<'EOF'
#!/usr/bin/env bash
[[ "$*" == 'rand -hex 24' ]] || exit 1
printf '%048x\n' 42
EOF

chmod +x "$TEMP_DIR/kubectl" "$TEMP_DIR/helm" "$TEMP_DIR/openssl"
export PATH="$TEMP_DIR:$PATH"

run_setup() {
  : > "$TEMP_DIR/writes"
  : > "$TEMP_DIR/secrets"
  MINIO_ROOT_USER="$1" MINIO_ROOT_PASSWORD="$2" \
    bash dev-tools/backups/setup-local-backup.sh > "$TEMP_DIR/output" 2>&1
}

expect_rejected() {
  local label="$1" field="$2" user="$3" password="$4"
  if run_setup "$user" "$password" || [[ -s "$TEMP_DIR/writes" ]] ||
    ! cmp -s "$TEMP_DIR/output" <(
      if [[ -f "$TEMP_DIR/stored-user" ]]; then
        echo 'Reusing existing MinIO credentials from secret minio/minio-creds.'
      fi
      echo "$field cannot be represented unchanged in the shared AWS credentials file for Velero."
    ); then
    echo "Credential rejection contract failed: $label" >&2
    exit 1
  fi
}

expect_success() {
  if ! run_setup "$1" "$2" || ! cmp -s "$TEMP_DIR/secrets" <(printf '%s\n' minio-secret velero-secret); then
    echo 'Expected both secret payloads to contain the exact credential bytes.' >&2
    exit 1
  fi
}

export EXPECTED_USER='firemud-local-minio' EXPECTED_PASSWORD
EXPECTED_PASSWORD="$(printf '%048x' 42)"
expect_success '' ''

EXPECTED_USER='synthetic#user;v1'
EXPECTED_PASSWORD='sample#tag;scope space=+/_-"part"'
expect_success "$EXPECTED_USER" "$EXPECTED_PASSWORD"

cases=('sample #comment' $'sample\t;comment' ' sample' $'sample\t' '"sample"' "'sample'" $'sample\rpart' $'sample\n')
for i in "${!cases[@]}"; do
  expect_rejected "environment case $i" MINIO_ROOT_PASSWORD "$EXPECTED_USER" "${cases[i]}"
done
expect_rejected 'environment user' MINIO_ROOT_USER 'synthetic ;comment' "$EXPECTED_PASSWORD"

printf '%s' "$EXPECTED_USER" > "$TEMP_DIR/stored-user"
printf '%s' "$EXPECTED_PASSWORD" > "$TEMP_DIR/stored-password"
expect_success '' ''
cases+=('')
for i in "${!cases[@]}"; do
  printf '%s' "${cases[i]}" > "$TEMP_DIR/stored-password"
  expect_rejected "stored case $i" MINIO_ROOT_PASSWORD '' ''
done
printf 'sample\0part' > "$TEMP_DIR/stored-password"
expect_rejected 'stored NUL' MINIO_ROOT_PASSWORD '' ''
printf 'synthetic\n' > "$TEMP_DIR/stored-user"
printf '%s' "$EXPECTED_PASSWORD" > "$TEMP_DIR/stored-password"
expect_rejected 'stored user' MINIO_ROOT_USER '' ''

echo 'Local backup credential contract checks passed.'
