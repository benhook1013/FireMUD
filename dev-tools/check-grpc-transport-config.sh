#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
export FIREMUD_REPO_ROOT="$REPO_ROOT"

exec python3 "$SCRIPT_DIR/check-grpc-transport-config.py" "$@"
