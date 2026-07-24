#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 1 || ! "$1" =~ ^[0-9a-f]{40}$ ]]; then
  echo "usage: $0 <40-character-lowercase-commit-sha>" >&2
  exit 1
fi

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
IMAGE_TAG="$1"
BASE_IMAGE="ghcr.io/benhook1013/firemud-base:${IMAGE_TAG}"
SERVICES=(
  account-service
  automation-scripting-service
  entity-management-service
  game-design-service
  game-logic-service
  game-session-service
  logging-admin-service
  social-groups-service
  spring-cloud-gateway
  tcp-proxy-service
  world-management-service
)

bash "$ROOT_DIR/dev-tools/build-compose-service-jars.sh"

docker build \
  --file "$ROOT_DIR/docker/base.Dockerfile" \
  --tag "$BASE_IMAGE" \
  "$ROOT_DIR"

for service in "${SERVICES[@]}"; do
  docker build \
    --build-context "repo_root=$ROOT_DIR" \
    --build-arg "BASE_IMAGE=$BASE_IMAGE" \
    --file "$ROOT_DIR/services/$service/Dockerfile" \
    --tag "ghcr.io/benhook1013/${service}:${IMAGE_TAG}" \
    "$ROOT_DIR/services/$service/build/libs"
done
