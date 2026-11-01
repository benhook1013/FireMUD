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
BUILD_COMMAND=(bash "$ROOT_DIR/dev-tools/validation/run-gradle-with-dependency-recovery.sh"
  --runner "$ROOT_DIR/dev-tools/validation/run-locked-gradle.sh" -- \
  --no-configuration-cache \
  --max-workers=1 \
  --no-parallel \
  '-Dorg.gradle.jvmargs=-Xmx2560m -Dfile.encoding=UTF-8' \
  -PincludeLoadTesting=false \
  "${GRADLE_TASKS[@]}" --info --console=plain)

# Scratch allocation is best effort, never a prerequisite for the original build.
SCRATCH=""
if mkdir -p "$ROOT_DIR/build"; then
  SCRATCH="$(mktemp -d "$ROOT_DIR/build/runtime-bootjar.XXXXXX")" || SCRATCH=""
fi
if [[ -z "$SCRATCH" ]]; then
  echo "Compile-failure log unavailable; running the original build without capture" >&2
  exec "${BUILD_COMMAND[@]}"
fi
trap 'rm -f -- "$SCRATCH/build.log" || :; rmdir -- "$SCRATCH" || :' EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
trap 'exit 129' HUP
EVIDENCE="$ROOT_DIR/build/reports/compile-failure/runtime-bootjar/${SCRATCH##*/}"
set +e
"${BUILD_COMMAND[@]}" 2>&1 | (
  python3 "$ROOT_DIR/dev-tools/validation/collect-gradle-compile-failure.py" --capture-log "$SCRATCH/build.log"
  capture_status=$?
  if (( capture_status != 0 )); then
    cat >/dev/null
  fi
  exit "$capture_status"
)
build_status=${PIPESTATUS[0]}
set -e
if (( build_status > 0 && build_status < 128 )); then
  # Refuse even a reused scratch-name collision; never upload existing evidence.
  if [[ ! -e "$EVIDENCE" && ! -L "$EVIDENCE" ]] && \
    python3 "$ROOT_DIR/dev-tools/validation/collect-gradle-compile-failure.py" \
    --root "$ROOT_DIR" --log "$SCRATCH/build.log" --check-exit "$build_status" \
    --output "$EVIDENCE"; then
    # Propagate only successfully collected evidence from this invocation.
    if [[ -f "$EVIDENCE/manifest.json" && -n "${GITHUB_OUTPUT:-}" ]]; then
      printf 'runtime_compile_failure_path=%s\n' "$EVIDENCE" >> "$GITHUB_OUTPUT" || \
        echo "Compile-failure artifact path unavailable" >&2
    fi
  else
    echo "Compile-failure evidence collection unavailable; preserving build exit $build_status" >&2
  fi
fi
exit "$build_status"
