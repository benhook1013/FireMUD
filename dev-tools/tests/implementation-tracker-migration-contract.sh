#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

python3 "$ROOT_DIR/dev-tools/validation/migrate-implementation-trackers.py" --check

echo "implementation tracker migration contract checks passed"
