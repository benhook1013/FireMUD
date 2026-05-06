#!/usr/bin/env bash
set -euo pipefail

LYCHEE_VERSION="lychee-v0.19.1"
CACHE_DIR="${XDG_CACHE_HOME:-$HOME/.cache}/lychee"
BIN="$CACHE_DIR/lychee"
URL="https://github.com/lycheeverse/lychee/releases/download/${LYCHEE_VERSION}/lychee-x86_64-unknown-linux-gnu.tar.gz"

if [ ! -x "$BIN" ]; then
  mkdir -p "$CACHE_DIR"
  curl -sSL "$URL" | tar -xz -C "$CACHE_DIR"
  chmod +x "$BIN"
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
