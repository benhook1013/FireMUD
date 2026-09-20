#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
dockerfile="$ROOT_DIR/docker/backup-verifier.Dockerfile"
authority="$ROOT_DIR/config/workflow-tool-versions.env"
smoke="$ROOT_DIR/dev-tools/backups/smoke-backup-verifier-image.sh"
runtime="$ROOT_DIR/.github/workflows/runtime-images.yml"
publisher="$ROOT_DIR/.github/workflows/publish-pr-runtime-images.yml"
dockerignore="$ROOT_DIR/.dockerignore"
push_verified_image="$ROOT_DIR/dev-tools/hosted/shared/push-verified-image.sh"

require_contains() {
  local path="$1"
  local expected="$2"
  grep -Fq -- "$expected" "$path" || {
    echo "$path must contain: $expected" >&2
    exit 1
  }
}

require_count() {
  local path="$1"
  local expected="$2"
  local required_count="$3"
  local actual_count
  actual_count="$(grep -Fc -- "$expected" "$path" || true)"
  [[ "$actual_count" == "$required_count" ]] || {
    echo "$path must contain $required_count occurrence(s) of: $expected (found $actual_count)" >&2
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

require_contains "$smoke" 'docker run --rm --read-only --entrypoint /bin/bash'
# shellcheck disable=SC2016 # Assert literal shell syntax in the smoke helper.
require_contains "$smoke" '[[ $# -ne 2 || -z "$1" || -z "$2" ]]'
# shellcheck disable=SC2016 # Assert literal shell syntax in the smoke helper.
require_contains "$smoke" 'expected_velero_version="$2"'
require_contains "$smoke" 'EXPECTED_VELERO_VERSION='
require_contains "$smoke" 'command -v bash'
require_contains "$smoke" 'command -v aws'
require_contains "$smoke" 'command -v velero'
require_contains "$smoke" 'velero version --client-only'
require_contains "$smoke" 'Velero client version output was empty'
require_contains "$smoke" 'Velero client version mismatch'
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
[[ -x "$push_verified_image" ]] || {
  echo "$push_verified_image must be executable" >&2
  exit 1
}
# shellcheck disable=SC2016 # These assertions intentionally match literal workflow/helper shell.
require_contains "$runtime" 'bash ./dev-tools/hosted/shared/push-verified-image.sh "$BACKUP_VERIFIER_IMAGE"'
# shellcheck disable=SC2016 # This assertion intentionally matches literal workflow shell.
if grep -Fq -- 'docker push "$BACKUP_VERIFIER_IMAGE"' "$runtime"; then
  echo "backup verifier publication must use the shared verified-image push helper" >&2
  exit 1
fi
# shellcheck disable=SC2016 # These assertions intentionally match literal helper shell.
for required in \
  'max_push_attempts=3' \
  'backoff_seconds=$((5 * 2 ** (push_attempt - 1)))' \
  'sleep "$backoff_seconds"' \
  'pushed_digests=()' \
  'if ((${#pushed_digests[@]} != 1)); then' \
  'echo "digest=$pushed_digest" >> "${GITHUB_OUTPUT:?GITHUB_OUTPUT must point to a step output file}"'; do
  require_contains "$push_verified_image" "$required"
done
python3 - "$runtime" <<'PY'
import sys
from pathlib import Path

import yaml

workflow = yaml.safe_load(Path(sys.argv[1]).read_text(encoding="utf-8"))
job = workflow["jobs"]["publish-backup-verifier"]
steps = job["steps"]
checkout_index = next(
    index
    for index, step in enumerate(steps)
    if step.get("name") == "Checkout trusted publication commit"
)
download_index = next(
    index
    for index, step in enumerate(steps)
    if step.get("name") == "Download exact verified backup verifier image artifact"
)
load_index = next(
    index
    for index, step in enumerate(steps)
    if step.get("name") == "Load and verify exact backup verifier image"
)
login_index = next(
    index for index, step in enumerate(steps) if step.get("name") == "Login to GHCR"
)
publish_index = next(
    index
    for index, step in enumerate(steps)
    if step.get("name") == "Publish exact verified backup verifier image"
)
if not checkout_index < download_index < load_index < login_index < publish_index:
    raise SystemExit("Backup verifier publisher must checkout before artifact load and helper invocation")
checkout = steps[checkout_index]
if checkout.get("uses") != "actions/checkout@de0fac2e4500dabe0009e67214ff5f5447ce83dd":
    raise SystemExit("Backup verifier publisher must use the pinned checkout action")
if checkout.get("with") != {
    "ref": "${{ needs.image-meta.outputs.checkout_ref }}",
    "persist-credentials": False,
}:
    raise SystemExit("Backup verifier publisher checkout must use the trusted exact commit without persisted credentials")
condition = job.get("if", "")
for required in (
    "github.event_name != 'pull_request'",
    "needs.image-meta.outputs.head_branch == 'main'",
    "needs.image-meta.outputs.head_branch == 'develop'",
):
    if required not in condition:
        raise SystemExit("Backup verifier publisher must remain default-branch-only")
PY
require_count "$runtime" 'uses: ./.github/actions/load-workflow-tool-versions' 2
require_count "$runtime" 'config/workflow-tool-versions.env' 2
# shellcheck disable=SC2016 # Assert literal workflow expressions and shell fragments.
require_count "$runtime" 'VELERO_VERSION: ${{ steps.workflow-tool-versions.outputs.velero-version }}' 2
# shellcheck disable=SC2016 # Assert literal workflow expressions and shell fragments.
require_count "$runtime" 'bash ./dev-tools/backups/smoke-backup-verifier-image.sh "$BACKUP_VERIFIER_IMAGE" "$VELERO_VERSION"' 2
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
