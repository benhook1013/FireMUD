#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

GRADLE_TASKS=(
  :account-service:bootJar
  :automation-scripting-service:bootJar
  :entity-management-service:bootJar
  :game-design-service:bootJar
  :game-logic-service:bootJar
  :game-session-service:bootJar
  :logging-admin-service:bootJar
  :social-groups-service:bootJar
  :spring-cloud-gateway:bootJar
  :tcp-proxy-service:bootJar
  :world-management-service:bootJar
)

echo "Building current boot jars for source-built Docker compose services."
# Bound compiler memory for this all-module image build and avoid protobuf task cache-serialization failures.
bash "$ROOT_DIR/dev-tools/validation/run-locked-gradle.sh" \
  --no-configuration-cache \
  --max-workers=1 \
  --no-parallel \
  '-Dorg.gradle.jvmargs=-Xmx2560m -Dfile.encoding=UTF-8' \
  -PincludeLoadTesting=false \
  "${GRADLE_TASKS[@]}"
