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
FILES=$(find design -name '*.md' -type f -print; find . -maxdepth 1 -name '*.md' -type f)
FILES=$(echo "$FILES" | grep -v '/node_modules/' | grep -v '/build/' | grep -v '/\.gradle/')

OPTIONS="--scheme https --scheme http"
"$BIN" --no-progress --verbose $OPTIONS $FILES
