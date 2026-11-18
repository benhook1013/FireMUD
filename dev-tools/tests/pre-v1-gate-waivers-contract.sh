#!/usr/bin/env bash
# Pre-v1 gate waivers (ADR 0185) may only be switched on in Compose smoke and preview
# environments. Fail if production charts, release tooling or prod profiles set them.
set -euo pipefail
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT_DIR"
pattern='PRE_V1_GATE_WAIVERS|pre-v1\.gate-waivers|pre-v1:'
hits="$(grep -rnIE "$pattern" k8s charts dev-tools/release 2>/dev/null || true)"
prod_hits="$(find services -path '*/src/main/resources/application-prod.yml' -print0 \
  | xargs -0 grep -nIE "$pattern" 2>/dev/null || true)"
if [[ -n "$hits$prod_hits" ]]; then
  echo "Pre-v1 gate waivers must not be set in production configuration:" >&2
  printf '%s\n%s\n' "$hits" "$prod_hits" | sed '/^$/d' >&2
  exit 1
fi
echo "Pre-v1 gate waiver contract passed"
