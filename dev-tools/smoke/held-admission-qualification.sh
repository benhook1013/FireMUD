#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
if (($# == 0)); then
  echo "Held-admission qualification requires the owning Compose file arguments." >&2
  exit 2
fi

python3 "$ROOT_DIR/dev-tools/smoke/held_admission_qualification.py" "$@"
