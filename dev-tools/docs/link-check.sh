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
ARCHIVE="$CACHE_DIR/lychee.tar.gz"
VERIFIED_MARKER="$CACHE_DIR/verified.sha256"
URL="https://github.com/lycheeverse/lychee/releases/download/lychee-v${LYCHEE_VERSION}/lychee-x86_64-unknown-linux-musl.tar.gz"

trusted=false
if [[ -x "$BIN" && -f "$ARCHIVE" && -f "$VERIFIED_MARKER" ]]; then
  marker_archive_sha=
  marker_binary_sha=
  read -r marker_archive_sha marker_binary_sha < "$VERIFIED_MARKER" || true
  if [[ "$marker_archive_sha" == "$LYCHEE_LINUX_X86_64_MUSL_SHA256" ]] \
    && printf '%s  %s\n' "$LYCHEE_LINUX_X86_64_MUSL_SHA256" "$ARCHIVE" | sha256sum --check --status \
    && printf '%s  %s\n' "$marker_binary_sha" "$BIN" | sha256sum --check --status; then
    verification="$(mktemp -d "$CACHE_DIR/verify.XXXXXX")"
    trap 'rm -rf "$verification"' EXIT
    tar -xzf "$ARCHIVE" -C "$verification"
    extracted_sha="$(sha256sum "$verification/lychee-x86_64-unknown-linux-musl/lychee" | awk '{print $1}')"
    [[ "$extracted_sha" == "$marker_binary_sha" ]] && trusted=true
    rm -rf "$verification"
    trap - EXIT
  fi
fi

if [[ "$trusted" != true ]]; then
  mkdir -p "$CACHE_DIR"
  staging="$(mktemp -d "$CACHE_DIR/install.XXXXXX")"
  trap 'rm -rf "$staging"' EXIT
  staged_archive="$staging/lychee.tar.gz"
  curl -fsSL "$URL" -o "$staged_archive"
  echo "${LYCHEE_LINUX_X86_64_MUSL_SHA256}  ${staged_archive}" | sha256sum --check --status
  tar -xzf "$staged_archive" -C "$staging"
  install -m 0755 "$staging/lychee-x86_64-unknown-linux-musl/lychee" "$staging/lychee"
  binary_sha="$(sha256sum "$staging/lychee" | awk '{print $1}')"
  printf '%s %s\n' "$LYCHEE_LINUX_X86_64_MUSL_SHA256" "$binary_sha" > "$staging/verified.sha256"
  mv -f "$staging/lychee" "$BIN"
  mv -f "$staged_archive" "$ARCHIVE"
  mv -f "$staging/verified.sha256" "$VERIFIED_MARKER"
  rm -rf "$staging"
  trap - EXIT
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
