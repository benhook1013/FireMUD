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
"$ROOT_DIR/gradlew" -PincludeLoadTesting=false "${GRADLE_TASKS[@]}"
