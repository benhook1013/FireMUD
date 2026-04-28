#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# Remove generated certificates so tooling can start from scratch.
CERT_DIR="${CERT_DIR:-$SCRIPT_DIR}"

if [ -d "$CERT_DIR" ]; then
  rm -rf "$CERT_DIR"
  echo "Removed certificates from $CERT_DIR"
else
  echo "No certificate directory at $CERT_DIR"
fi
