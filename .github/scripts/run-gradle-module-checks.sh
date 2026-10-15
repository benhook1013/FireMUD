#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
readonly ROOT_DIR
readonly MODULE="${1:-}"
if [[ $# -ne 1 || ! "$MODULE" =~ ^[a-z0-9]+(-[a-z0-9]+)*$ ]]; then
  echo "usage: run-gradle-module-checks.sh <module-name>" >&2
  exit 64
fi

bash "$ROOT_DIR/dev-tools/validation/run-gradle-with-dependency-recovery.sh" \
  --runner "$ROOT_DIR/gradlew" -- \
  --max-workers=2 \
  '-Dorg.gradle.jvmargs=-Xmx2500m -Dfile.encoding=UTF-8' \
  ":${MODULE}:check" \
  -PfullCheck
