#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
dockerfile="$ROOT_DIR/docker/backup-verifier.Dockerfile"
authority="$ROOT_DIR/config/workflow-tool-versions.env"
smoke="$ROOT_DIR/dev-tools/backups/smoke-backup-verifier-image.sh"
runtime="$ROOT_DIR/.github/workflows/runtime-images.yml"
publisher="$ROOT_DIR/.github/workflows/publish-pr-runtime-images.yml"
dockerignore="$ROOT_DIR/.dockerignore"

require_contains() {
  local path="$1"
  local expected="$2"
  grep -Fq -- "$expected" "$path" || {
    echo "$path must contain: $expected" >&2
    exit 1
  }
}

velero_version="$(awk -F= '$1 == "VELERO_VERSION" { print $2 }' "$authority")"
velero_digest="$(awk -F= '$1 == "VELERO_IMAGE_DIGEST" { print $2 }' "$authority")"
if [[ -z "$velero_version" || -z "$velero_digest" ]]; then
  echo "workflow tool authority must define Velero version and image digest" >&2
  exit 1
fi
require_contains "$dockerfile" "FROM velero/velero:v${velero_version}@${velero_digest} AS velero-cli"
require_contains "$dockerfile" 'FROM public.ecr.aws/aws-cli/aws-cli:2.31.23@sha256:668ffb01408e03e1002b36886797c1a97b09f7d0f02fba3123f9fcd68a081dc5'
require_contains "$dockerfile" 'COPY --from=velero-cli /velero /usr/local/bin/velero'
require_contains "$dockerfile" 'COPY dev-tools/backups/verify-backups.sh /opt/firemud/backups/verify-backups.sh'
require_contains "$dockerfile" 'COPY dev-tools/backups/pg-dump-s3-selection.shlib /opt/firemud/backups/pg-dump-s3-selection.shlib'
require_contains "$dockerfile" 'USER 65532:65532'
require_contains "$dockerfile" 'ENTRYPOINT ["/bin/bash", "/opt/firemud/backups/verify-backups.sh"]'
require_contains "$dockerignore" '!docker/backup-verifier.Dockerfile'
require_contains "$dockerignore" '!dev-tools/backups/verify-backups.sh'
require_contains "$dockerignore" '!dev-tools/backups/pg-dump-s3-selection.shlib'

require_contains "$smoke" 'docker run --rm --entrypoint /bin/bash'
require_contains "$smoke" 'command -v bash'
require_contains "$smoke" 'command -v aws'
require_contains "$smoke" 'command -v velero'
require_contains "$smoke" 'velero version --client-only'
require_contains "$smoke" 'aws --version 2>&1'
require_contains "$smoke" 'bash -n /opt/firemud/backups/verify-backups.sh'
# shellcheck disable=SC2016 # Assert the literal command embedded in the smoke helper.
require_contains "$smoke" '[[ "$(id -u)" != 0 ]]'

for path in \
  'docker/backup-verifier.Dockerfile' \
  'dev-tools/backups/verify-backups.sh' \
  'dev-tools/backups/pg-dump-s3-selection.shlib' \
  'dev-tools/backups/smoke-backup-verifier-image.sh'; do
  require_contains "$runtime" "$path"
done
if [[ ! -f "$publisher" ]]; then
  echo "trusted publisher workflow is required for backup verifier publication" >&2
  exit 1
fi
require_contains "$runtime" 'BACKUP_VERIFIER_IMAGE'
require_contains "$runtime" 'smoke-backup-verifier-image.sh'
require_contains "$runtime" "backup-verifier' >> \"\$service_manifest\""
# shellcheck disable=SC2016 # Assert the literal workflow shell fragment.
require_contains "$runtime" 'images+=("$BACKUP_VERIFIER_IMAGE")'
if grep -Fq -- 'pr-backup-verifier-image.txt' "$runtime" "$publisher"; then
  echo 'backup verifier must use the canonical PR runtime service manifest' >&2
  exit 1
fi
if grep -Fq -- 'Publish fixed PR backup verifier image' "$publisher"; then
  echo 'backup verifier must use the existing trusted publisher loop' >&2
  exit 1
fi

bash -n "$smoke"
bash -n "$ROOT_DIR/dev-tools/backups/verify-backups.sh"
bash -n "$ROOT_DIR/dev-tools/backups/pg-dump-s3-selection.shlib"

echo 'backup verifier image contract checks passed (Docker image smoke runs in CI)'
