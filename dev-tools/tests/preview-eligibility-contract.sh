#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SCRIPT="$ROOT_DIR/dev-tools/hosted/preview/preview-eligibility.py"

deploy_open="$(python3 "$SCRIPT" --operation deploy --state open --base-ref develop --author benhook1013 --labels-json '[]')"
grep -q '^eligible=true$' <<<"$deploy_open"
grep -q '^reason=eligible$' <<<"$deploy_open"

deploy_stacked="$(python3 "$SCRIPT" --operation deploy --state open --base-ref feature/design-and-mvp --author benhook1013 --labels-json '[]')"
grep -q '^eligible=false$' <<<"$deploy_stacked"
grep -q '^reason=unsupported-base-branch$' <<<"$deploy_stacked"

deploy_dependency_bot="$(python3 "$SCRIPT" --operation deploy --state open --base-ref develop --author 'renovate[bot]' --labels-json '[]')"
grep -q '^eligible=false$' <<<"$deploy_dependency_bot"
grep -q '^reason=dependency-bot$' <<<"$deploy_dependency_bot"

retain_closed="$(python3 "$SCRIPT" --operation retain --state closed --base-ref develop --author benhook1013 --labels-json '[]')"
grep -q '^eligible=false$' <<<"$retain_closed"
grep -q '^reason=pr-not-open$' <<<"$retain_closed"

destroy_closed="$(python3 "$SCRIPT" --operation destroy --state closed --base-ref develop --author benhook1013 --labels-json '[]')"
grep -q '^eligible=true$' <<<"$destroy_closed"
grep -q '^reason=eligible$' <<<"$destroy_closed"

destroy_unsupported_base="$(python3 "$SCRIPT" --operation destroy --state closed --base-ref feature/design-and-mvp --author benhook1013 --labels-json '[]')"
grep -q '^eligible=false$' <<<"$destroy_unsupported_base"
grep -q '^reason=unsupported-base-branch$' <<<"$destroy_unsupported_base"

destroy_dependency_bot="$(python3 "$SCRIPT" --operation destroy --state closed --base-ref develop --author 'renovate[bot]' --labels-json '[]')"
grep -q '^eligible=false$' <<<"$destroy_dependency_bot"
grep -q '^reason=dependency-bot$' <<<"$destroy_dependency_bot"

paused_deploy="$(python3 "$SCRIPT" --operation deploy --state open --base-ref develop --author benhook1013 --labels-json '[{"name":"preview:paused"}]')"
grep -q '^eligible=false$' <<<"$paused_deploy"
grep -q '^reason=preview-paused$' <<<"$paused_deploy"

paused_retain="$(python3 "$SCRIPT" --operation retain --state open --base-ref develop --author benhook1013 --labels-json '[{"name":"preview:paused"}]')"
grep -q '^eligible=false$' <<<"$paused_retain"
grep -q '^reason=preview-paused$' <<<"$paused_retain"

paused_destroy="$(python3 "$SCRIPT" --operation destroy --state closed --base-ref develop --author benhook1013 --labels-json '[{"name":"preview:paused"}]')"
grep -q '^eligible=true$' <<<"$paused_destroy"
grep -q '^reason=eligible$' <<<"$paused_destroy"

malformed_deploy="$(python3 "$SCRIPT" --operation deploy --state open --base-ref develop --author benhook1013 --labels-json '{"name":"preview:paused"}')"
grep -q '^eligible=false$' <<<"$malformed_deploy"
grep -q '^reason=malformed-label-metadata$' <<<"$malformed_deploy"

malformed_destroy="$(python3 "$SCRIPT" --operation destroy --state closed --base-ref develop --author benhook1013 --labels-json '{"name":"preview:paused"}')"
grep -q '^eligible=true$' <<<"$malformed_destroy"
grep -q '^reason=eligible$' <<<"$malformed_destroy"

echo "preview eligibility contract checks passed"
