#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
readonly ROOT_DIR
readonly MODULE="${1:-}"
if [[ $# -ne 1 || ! "$MODULE" =~ ^[a-z0-9]+(-[a-z0-9]+)*$ ]]; then
  echo "usage: run-gradle-module-checks.sh <module-name>" >&2
  exit 64
fi

# Keep the original recovery policy and complete check; diagnostics never retry it.
CHECK_LOG="$(mktemp "${RUNNER_TEMP:-${TMPDIR:-/tmp}}/firemud-module-check.XXXXXX")" || CHECK_LOG=""
readonly CHECK_LOG
trap 'rm -f -- "$CHECK_LOG"' EXIT
if [[ -z "$CHECK_LOG" ]]; then
  echo "Compile-failure log unavailable; running the original check without capture" >&2
  exec bash "$ROOT_DIR/dev-tools/validation/run-gradle-with-dependency-recovery.sh" \
    --runner "$ROOT_DIR/gradlew" -- \
    --max-workers=2 \
    '-Dorg.gradle.jvmargs=-Xmx2500m -Dfile.encoding=UTF-8' \
    ":${MODULE}:check" -PfullCheck --profile
fi
set +e
bash "$ROOT_DIR/dev-tools/validation/run-gradle-with-dependency-recovery.sh" \
  --runner "$ROOT_DIR/gradlew" -- \
  --max-workers=2 \
  '-Dorg.gradle.jvmargs=-Xmx2500m -Dfile.encoding=UTF-8' \
  ":${MODULE}:check" \
  -PfullCheck \
  --profile --info --console=plain 2>&1 | \
  {
    python3 "$ROOT_DIR/dev-tools/validation/collect-gradle-compile-failure.py" --capture-log "$CHECK_LOG"
    capture_status=$?
    if (( capture_status != 0 )); then
      cat >/dev/null
    fi
    exit "$capture_status"
  }
check_status=${PIPESTATUS[0]}
set -e
if (( check_status > 0 && check_status < 128 )); then
  # Diagnostic errors are visible but cannot replace the original check result.
  python3 "$ROOT_DIR/dev-tools/validation/collect-gradle-compile-failure.py" \
    --root "$ROOT_DIR" --log "$CHECK_LOG" --check-exit "$check_status" \
    --output "$ROOT_DIR/build/reports/compile-failure/$MODULE" || \
    echo "Compile-failure evidence collection unavailable; preserving check exit $check_status" >&2
fi
exit "$check_status"
