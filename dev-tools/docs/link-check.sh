#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
# shellcheck disable=SC1091
source "$ROOT_DIR/config/workflow-tool-versions.env"
[[ "$(uname -s)/$(uname -m)" == "Linux/x86_64" ]] || {
  echo "The pinned Lychee archive supports Linux x86_64 only." >&2
  exit 1
}
CACHE_DIR="${XDG_CACHE_HOME:-$HOME/.cache}/lychee/${LYCHEE_VERSION}/x86_64-unknown-linux-musl"
BIN="$CACHE_DIR/lychee"
URL="https://github.com/lycheeverse/lychee/releases/download/lychee-v${LYCHEE_VERSION}/lychee-x86_64-unknown-linux-musl.tar.gz"

if [ ! -x "$BIN" ]; then
  mkdir -p "$CACHE_DIR"
  archive="$CACHE_DIR/lychee.tar.gz"
  curl -fsSL "$URL" -o "$archive"
  echo "${LYCHEE_LINUX_X86_64_MUSL_SHA256}  ${archive}" | sha256sum --check --status
  tar -xzf "$archive" -C "$CACHE_DIR"
  rm "$archive"
  install -m 0755 "$CACHE_DIR/lychee-x86_64-unknown-linux-musl/lychee" "$BIN"
fi

# Run link check on documentation files only
mapfile -t FILES < <(
  find design -name '*.md' -type f
  find . -maxdepth 1 -name '*.md' -type f
)

OPTIONS=(
  --scheme file
  --exclude-path node_modules
  --exclude-path build
  --exclude-path .gradle
  --exclude-path design/grpc-docs
  --max-retries 3
  --retry-wait-time 2
)

if [[ "${CHECK_EXTERNAL_LINKS:-}" == 1 ]]; then
  OPTIONS+=(--scheme https --scheme http)
fi

# Lychee automatically reads repository-root .lycheeignore entries for link exclusions.
"$BIN" --no-progress "${OPTIONS[@]}" "${FILES[@]}"
