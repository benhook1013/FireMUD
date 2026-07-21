#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

while IFS='|' read -r workflow gate; do
  [[ -n "$workflow" ]] || continue
  path="$ROOT_DIR/.github/workflows/$workflow"
  expected="name: \${{ github.event_name == 'pull_request' && github.event.action == 'edited' && github.event.changes.base.ref == null && 'PR Metadata Edit ($gate)' || '$gate' }}"
  if ! grep -Fq "$expected" "$path"; then
    echo "$workflow must isolate metadata-only edits from the required $gate context" >&2
    exit 1
  fi
done <<'EOF'
ci.yml|Validation Gate
security.yml|Security Gate
license-scan.yml|License Gate
smoke.yml|Smoke Gate
codeql.yml|CodeQL Gate
EOF

echo "PR required-gate context contract checks passed"
