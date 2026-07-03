#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SCRIPT="$ROOT_DIR/dev-tools/hosted/preview/preview-eligibility.py"

deploy_open="$(python3 "$SCRIPT" --operation deploy --state open --base-ref develop --author benhook1013)"
grep -q '^eligible=true$' <<<"$deploy_open"
grep -q '^reason=eligible$' <<<"$deploy_open"

deploy_stacked="$(python3 "$SCRIPT" --operation deploy --state open --base-ref feature/design-and-vip --author benhook1013)"
grep -q '^eligible=false$' <<<"$deploy_stacked"
grep -q '^reason=unsupported-base-branch$' <<<"$deploy_stacked"

deploy_dependency_bot="$(python3 "$SCRIPT" --operation deploy --state open --base-ref develop --author 'renovate[bot]')"
grep -q '^eligible=false$' <<<"$deploy_dependency_bot"
grep -q '^reason=dependency-bot$' <<<"$deploy_dependency_bot"

retain_closed="$(python3 "$SCRIPT" --operation retain --state closed --base-ref develop --author benhook1013)"
grep -q '^eligible=false$' <<<"$retain_closed"
grep -q '^reason=pr-not-open$' <<<"$retain_closed"

destroy_closed="$(python3 "$SCRIPT" --operation destroy --state closed --base-ref develop --author benhook1013)"
grep -q '^eligible=true$' <<<"$destroy_closed"
grep -q '^reason=eligible$' <<<"$destroy_closed"

destroy_unsupported_base="$(python3 "$SCRIPT" --operation destroy --state closed --base-ref feature/design-and-vip --author benhook1013)"
grep -q '^eligible=false$' <<<"$destroy_unsupported_base"
grep -q '^reason=unsupported-base-branch$' <<<"$destroy_unsupported_base"

destroy_dependency_bot="$(python3 "$SCRIPT" --operation destroy --state closed --base-ref develop --author 'renovate[bot]')"
grep -q '^eligible=false$' <<<"$destroy_dependency_bot"
grep -q '^reason=dependency-bot$' <<<"$destroy_dependency_bot"

echo "preview eligibility contract checks passed"
