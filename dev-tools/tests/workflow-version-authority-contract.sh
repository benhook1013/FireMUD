#!/usr/bin/env bash
set -euo pipefail
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
export FIREMUD_REPO_ROOT="$ROOT_DIR"
exec python3 "$ROOT_DIR/dev-tools/tests/workflow_version_authority_contract.py"
