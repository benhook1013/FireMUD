#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
verifier="$ROOT_DIR/dev-tools/maintenance/verify-publisher-checksum.sh"
tmp="$(mktemp -d)"
trap 'rm -rf -- "$tmp"' EXIT
artifact="$tmp/tool.tar.gz"
printf 'verified artifact' > "$artifact"
digest="$(sha256sum --binary "$artifact")"
digest="${digest%% *}"

printf '%s\n' "$digest" > "$tmp/digest"
"$verifier" digest "$tmp/digest" "$artifact" tool.tar.gz
printf '%s  %s\n' "$digest" tool.tar.gz > "$tmp/manifest"
"$verifier" manifest "$tmp/manifest" "$artifact" tool.tar.gz

for content in \
  "$digest  another.tar.gz" \
  "$digest  tool.tar.gz
$digest  tool.tar.gz" \
  "$digest  tool.tar.gz
not-a-manifest-row"; do
  printf '%s\n' "$content" > "$tmp/invalid"
  if "$verifier" manifest "$tmp/invalid" "$artifact" tool.tar.gz >/dev/null 2>&1; then
    echo "publisher verifier accepted missing, duplicate, or malformed manifest rows" >&2
    exit 1
  fi
done

for content in "${digest^^}" "$digest
" "$(printf '0%.0s' {1..64})"; do
  printf '%s\n' "$content" > "$tmp/invalid"
  if "$verifier" digest "$tmp/invalid" "$artifact" tool.tar.gz >/dev/null 2>&1; then
    echo "publisher verifier accepted malformed or mismatched digest evidence" >&2
    exit 1
  fi
done

if "$verifier" digest "$tmp/digest" "$artifact" ../tool.tar.gz >/dev/null 2>&1; then
  echo "publisher verifier accepted a non-filename asset name" >&2
  exit 1
fi
