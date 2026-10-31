#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"

# Remove generated certificates so tooling can start from scratch without
# deleting the tracked helper scripts in dev-tools/certs/.
TARGET="${1:-}"
if [ -n "$TARGET" ]; then
  CERT_DIR="$TARGET"
else
  CERT_DIR="${CERT_DIR:-$SCRIPT_DIR}"
fi

directory_path="$CERT_DIR"
[[ "$directory_path" == /* ]] || directory_path="$PWD/$directory_path"
while [[ "$directory_path" != / && "$directory_path" == */ ]]; do directory_path="${directory_path%/}"; done
while :; do
  if [[ -L "$directory_path" || ( -e "$directory_path" && ! -d "$directory_path" ) ]]; then
    echo "Certificate cleanup refuses linked or non-directory paths: $directory_path" >&2
    exit 1
  fi
  [[ "$directory_path" != / ]] || break
  directory_path="$(dirname -- "$directory_path")"
done

if [ ! -d "$CERT_DIR" ]; then
  echo "No certificate directory at $CERT_DIR"
  exit 0
fi

generated_files=(
  ca.crt
  ca.key
  ca.srl
  client.crt
  client.key
  dev-ca.pem
  dev-cert.pem
  dev-key.pem
  server.crt
  server.key
  server.csr
  dev-cert.cnf
)

removed_any=false
for filename in "${generated_files[@]}"; do
  if [[ -f "$CERT_DIR/$filename" && ! -L "$CERT_DIR/$filename" && "$(stat -c %h -- "$CERT_DIR/$filename")" == 1 ]]; then
    rm -f -- "$CERT_DIR/$filename"
    removed_any=true
  fi
done

if [ "$removed_any" = true ]; then
  echo "Removed generated certificates from $CERT_DIR"
else
  echo "No generated certificates found in $CERT_DIR"
fi

# Never recurse: scripts, foreign files, directories and links belong to callers.
while IFS= read -r -d '' entry; do
  echo "Preserved certificate-directory entry: $entry"
done < <(find "$CERT_DIR" -mindepth 1 -maxdepth 1 -print0)
