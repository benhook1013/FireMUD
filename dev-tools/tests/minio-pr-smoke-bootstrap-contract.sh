#!/usr/bin/env bash
# shellcheck disable=SC2016 # Assert literal workflow and shell syntax.
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
WORKFLOW="$ROOT_DIR/.github/workflows/runtime-images.yml"
VERIFY_SMOKE="$ROOT_DIR/dev-tools/verify-smoke-images.sh"
OVERLAY="$ROOT_DIR/docker/docker-compose.pr-local-minio.override.yml"
TRUSTED_PUBLISH_WORKFLOW="$ROOT_DIR/.github/workflows/publish-trusted-minio-source-images.yml"

require_contains() {
  local contents="$1"
  local expected="$2"
  if ! grep -Fq -- "$expected" <<<"$contents"; then
    echo "PR MinIO smoke bootstrap contract is missing: $expected" >&2
    exit 1
  fi
}

require_count() {
  local contents="$1"
  local expected="$2"
  local count
  count="$(grep -Fc -- "$expected" <<<"$contents" || true)"
  if [[ "$count" != "$3" ]]; then
    echo "PR MinIO smoke bootstrap contract expected $3 occurrence(s) of $expected, found $count" >&2
    exit 1
  fi
}

job="$(sed -n '/^  pr-local-smoke:$/,/^  pr-controller-smoke:$/p' "$WORKFLOW")"
[[ -n "$job" ]] || {
  echo "PR Full-Stack Smoke job was not found." >&2
  exit 1
}
require_contains "$job" 'timeout-minutes: 45'
require_contains "$job" "SMOKE_MINIO_LOCAL_ONLY: 'true'"
require_contains "$job" 'SMOKE_MINIO_SERVER_IMAGE: >-'
require_contains "$job" 'firemud-minio-server-smoke:${{ github.run_id }}-${{ github.run_attempt }}'
require_contains "$job" 'SMOKE_MINIO_CLIENT_IMAGE: >-'
require_contains "$job" 'firemud-minio-client-smoke:${{ github.run_id }}-${{ github.run_attempt }}'
require_contains "$job" 'contents: read'
if grep -Eq 'packages:[[:space:]]*write|docker/login-action|docker push' <<<"$job"; then
  echo "PR Full-Stack Smoke must not receive registry publish credentials or publish images." >&2
  exit 1
fi

require_contains "$job" 'id: minio-source'
require_contains "$job" "needs.image-meta.outputs.runtime_smoke_required == 'true'"
require_contains "$job" 'bash ./dev-tools/minio/build-and-smoke-images.sh'
require_contains "$job" '"$SMOKE_MINIO_SERVER_IMAGE" "$SMOKE_MINIO_CLIENT_IMAGE"'
require_count "$job" 'SMOKE_MINIO_SERVER_IMAGE_ID: >-' 3
require_count "$job" 'SMOKE_MINIO_CLIENT_IMAGE_ID: >-' 3
require_count "$job" 'steps.minio-source.outputs.server_image_id' 5
require_count "$job" 'steps.minio-source.outputs.client_image_id' 5
require_count "$job" '-f docker/docker-compose.pr-local-minio.override.yml' 2
require_contains "$job" "steps.minio-source.outputs.server_image_id != ''"
require_contains "$job" "steps.minio-source.outputs.client_image_id != ''"
require_contains "$job" 'run: bash ./dev-tools/verify-smoke-images.sh'
require_contains "$job" 'bash ./dev-tools/verify-smoke-images.sh'

trusted_publisher="$(<"$TRUSTED_PUBLISH_WORKFLOW")"
require_contains "$trusted_publisher" 'docker image rm "$image_ref"'
require_contains "$trusted_publisher" 'docker image inspect "$image_ref"'
require_contains "$trusted_publisher" 'remove_local_image_reference "$SERVER_IMAGE"'
require_contains "$trusted_publisher" 'remove_local_image_reference "$SERVER_IMAGE_NAME:$PUBLISH_TAG"'
require_contains "$trusted_publisher" 'remove_local_image_reference "$SERVER_IMAGE_NAME@$SERVER_DIGEST"'
require_contains "$trusted_publisher" 'remove_local_image_reference "$CLIENT_IMAGE"'
require_contains "$trusted_publisher" 'remove_local_image_reference "$CLIENT_IMAGE_NAME:$PUBLISH_TAG"'
require_contains "$trusted_publisher" 'remove_local_image_reference "$CLIENT_IMAGE_NAME@$CLIENT_DIGEST"'
require_contains "$trusted_publisher" 'DOCKER_CONFIG="$anonymous_docker_config" verify_anonymous_pull "$SERVER_IMAGE_NAME@$SERVER_DIGEST"'
require_contains "$trusted_publisher" 'DOCKER_CONFIG="$anonymous_docker_config" verify_anonymous_pull "$CLIENT_IMAGE_NAME@$CLIENT_DIGEST"'

overlay="$(<"$OVERLAY")"
require_contains "$overlay" 'minio:'
require_contains "$overlay" 'image: "${SMOKE_MINIO_SERVER_IMAGE:?server tag required}"'
require_contains "$overlay" 'minio-setup:'
require_contains "$overlay" 'image: "${SMOKE_MINIO_CLIENT_IMAGE:?client tag required}"'
require_count "$overlay" 'pull_policy: never' 2
require_contains "$overlay" 'busybox wget -q -T 3 -O /dev/null'
require_contains "$overlay" 'http://localhost:9000/minio/health/live'
if grep -Fq 'quay.io/minio' <<<"$overlay"; then
  echo "The optional PR MinIO overlay must not retain a Quay fallback." >&2
  exit 1
fi

require_contains "$(<"$VERIFY_SMOKE")" 'SMOKE_MINIO_LOCAL_ONLY="${SMOKE_MINIO_LOCAL_ONLY:-false}"'
require_contains "$(<"$VERIFY_SMOKE")" 'COMPOSE_FILES+=( -f "$DOCKER_DIR/docker-compose.pr-local-minio.override.yml" )'
require_contains "$(<"$VERIFY_SMOKE")" 'docker image inspect --format '\''{{.Id}}'\'' "$image_ref"'
require_contains "$(<"$VERIFY_SMOKE")" '"$local_image_id" == "$image_id"'
require_contains "$(<"$VERIFY_SMOKE")" 'docker compose "${COMPOSE_FILES[@]}" config --format json'
require_contains "$(<"$VERIFY_SMOKE")" 'service.get("image") != image_ref'
require_contains "$(<"$VERIFY_SMOKE")" 'service.get("pull_policy") != "never"'
require_contains "$(<"$VERIFY_SMOKE")" 'bash "$WS_SMOKE_SCRIPT"'
require_contains "$(<"$VERIFY_SMOKE")" 'bash "$TCP_SMOKE_SCRIPT"'

echo "PR pinned-source MinIO full-stack smoke contract checks passed"
