#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
detector="$ROOT_DIR/dev-tools/deploy/detect-hosted-v2-migration-activation.sh"
fixture_dir="$(mktemp -d)"
trap 'rm -rf "$fixture_dir"' EXIT
repo="$fixture_dir/repo"
mkdir -p "$repo"
git -C "$repo" init -q
git -C "$repo" config user.name "Hosted migration contract"
git -C "$repo" config user.email "hosted-migration-contract@example.invalid"

kubectl() {
  [[ "$*" == "get namespace dev --ignore-not-found -o json" ]] || {
    echo "unexpected kubectl arguments: $*" >&2
    return 2
  }
  printf '%s\n' "${FAKE_NAMESPACE_JSON:-}"
}
export -f kubectl

namespace_for_head() {
  FAKE_NAMESPACE_JSON="$(jq -cn --arg head "$1" '{metadata:{name:"dev",labels:{"firemud.dev/dev-demo":"true","firemud.dev/environment-class":"dev-demo-cluster"},annotations:{"firemud.dev/last-dev-demo-head-sha":$head}}}')"
  export FAKE_NAMESPACE_JSON
}

assert_activation() {
  local expected="$1"
  shift
  local output
  output="$(cd "$repo" && bash "$detector" "$@" dev)"
  grep -Fxq "activation=$expected" <<<"$output" || {
    echo "expected migration activation=$expected; got: $output" >&2
    exit 1
  }
}

assert_rejected() {
  local output
  if output="$(cd "$repo" && bash "$detector" "$@" dev 2>&1)"; then
    echo "expected migration-mode classification to fail closed: $*" >&2
    exit 1
  fi
  if grep -q '^activation=' <<<"$output"; then
    echo "rejected migration-mode classification emitted an activation decision: $output" >&2
    exit 1
  fi
}

mkdir -p "$repo/services/example/src/main/resources/db/migration"
printf '%s\n' 'CREATE TABLE baseline (id integer PRIMARY KEY);' \
  >"$repo/services/example/src/main/resources/db/migration/V1__baseline.sql"
git -C "$repo" add .
git -C "$repo" commit -qm "baseline"
baseline_sha="$(git -C "$repo" rev-parse HEAD)"

printf '%s\n' 'ordinary deploy change' >"$repo/ordinary.txt"
git -C "$repo" add ordinary.txt
git -C "$repo" commit -qm "ordinary change"
ordinary_sha="$(git -C "$repo" rev-parse HEAD)"
namespace_for_head "$baseline_sha"
assert_activation false push "$baseline_sha" "$ordinary_sha"
assert_activation false push "" "$ordinary_sha"

mkdir -p "$repo/services/game-session-service/src/main/resources/db/migration"
printf '%s\n' 'CREATE INDEX gameplay_v2 ON gameplay_command (tenant_id, command_id);' \
  >"$repo/services/game-session-service/src/main/resources/db/migration/V2__scope_gameplay_command_identity.sql"
git -C "$repo" add .
git -C "$repo" commit -qm "V2 migration"
game_session_v2_sha="$(git -C "$repo" rev-parse HEAD)"
namespace_for_head "$ordinary_sha"
assert_activation true push "$ordinary_sha" "$game_session_v2_sha"
assert_rejected push "0000000000000000000000000000000000000000" "$game_session_v2_sha"
assert_rejected push "not-a-sha" "$game_session_v2_sha"

mkdir -p "$repo/services/automation-scripting-service/src/main/resources/db/migration"
printf '%s\n' 'CREATE INDEX automation_v2 ON script_work_items (tenant_id, script_id);' \
  >"$repo/services/automation-scripting-service/src/main/resources/db/migration/V2__script_patch_readiness_single_active.sql"
git -C "$repo" add .
git -C "$repo" commit -qm "Automation V2 migration"
supported_v2_sha="$(git -C "$repo" rev-parse HEAD)"
assert_activation true push "$game_session_v2_sha" "$supported_v2_sha"

# The migration commit did not reach deployment, so the trusted namespace head stays
# at Game Session V2. A later push must still classify the intervening Automation V2.
printf '%s\n' 'ordinary change after skipped migration deployment' >"$repo/ordinary.txt"
git -C "$repo" add ordinary.txt
git -C "$repo" commit -qm "ordinary push after skipped migration deployment"
later_push_sha="$(git -C "$repo" rev-parse HEAD)"
namespace_for_head "$game_session_v2_sha"
assert_activation true push "$supported_v2_sha" "$later_push_sha"
assert_activation true push "" "$later_push_sha"

echo 'retained migration edit' >>"$repo/services/game-session-service/src/main/resources/db/migration/V2__scope_gameplay_command_identity.sql"
git -C "$repo" add .
git -C "$repo" commit -qm "Edit existing Game Session V2 migration"
edited_v2_sha="$(git -C "$repo" rev-parse HEAD)"
namespace_for_head "$supported_v2_sha"
assert_rejected push "$supported_v2_sha" "$edited_v2_sha"

rm "$repo/services/automation-scripting-service/src/main/resources/db/migration/V2__script_patch_readiness_single_active.sql"
git -C "$repo" add .
git -C "$repo" commit -qm "Delete existing Automation V2 migration"
deleted_v2_sha="$(git -C "$repo" rev-parse HEAD)"
namespace_for_head "$edited_v2_sha"
assert_rejected push "$edited_v2_sha" "$deleted_v2_sha"

git -C "$repo" switch -qc account-v25 "$supported_v2_sha"
namespace_for_head "$supported_v2_sha"
mkdir -p "$repo/services/account-service/src/main/resources/db/migration"
printf '%s\n' 'CREATE UNIQUE INDEX profiles_tenant_account_identity ON profiles (tenant_id, account_id);' \
  >"$repo/services/account-service/src/main/resources/db/migration/V25__scope_profile_identity.sql"
git -C "$repo" add .
git -C "$repo" commit -qm "Account V25 migration"
account_v25_sha="$(git -C "$repo" rev-parse HEAD)"
assert_activation true push "$supported_v2_sha" "$account_v25_sha"

namespace_for_head "$account_v25_sha"
echo '-- edited retained migration' >>"$repo/services/account-service/src/main/resources/db/migration/V25__scope_profile_identity.sql"
git -C "$repo" add .
git -C "$repo" commit -qm "Edit existing Account V25 migration"
account_edited_sha="$(git -C "$repo" rev-parse HEAD)"
assert_rejected push "$account_v25_sha" "$account_edited_sha"

namespace_for_head "$account_edited_sha"
rm "$repo/services/account-service/src/main/resources/db/migration/V25__scope_profile_identity.sql"
git -C "$repo" add .
git -C "$repo" commit -qm "Delete existing Account V25 migration"
account_deleted_sha="$(git -C "$repo" rev-parse HEAD)"
assert_rejected push "$account_edited_sha" "$account_deleted_sha"

mkdir -p "$repo/services/example/src/main/resources/db/migration"
printf '%s\n' 'CREATE TABLE unsupported (id integer PRIMARY KEY);' \
  >"$repo/services/example/src/main/resources/db/migration/V2__unsupported.sql"
git -C "$repo" add .
git -C "$repo" commit -qm "unrelated service migration"
unsupported_sha="$(git -C "$repo" rev-parse HEAD)"
assert_rejected push "$ordinary_sha" "$unsupported_sha"

mkdir -p "$repo/services/example/src/main/resources/db/migration/nested"
printf '%s\n' 'CREATE TABLE nested_unsupported (id integer PRIMARY KEY);' \
  >"$repo/services/example/src/main/resources/db/migration/nested/V2__unsupported.sql"
git -C "$repo" add .
git -C "$repo" commit -qm "nested unsupported migration"
nested_unsupported_sha="$(git -C "$repo" rev-parse HEAD)"
assert_rejected push "$ordinary_sha" "$nested_unsupported_sha"

namespace_for_head "$baseline_sha"
assert_activation false repository_dispatch "" "$ordinary_sha"
assert_activation true repository_dispatch "" "$supported_v2_sha"
namespace_for_head "$supported_v2_sha"
assert_rejected repository_dispatch "" "$unsupported_sha"

FAKE_NAMESPACE_JSON=''
export FAKE_NAMESPACE_JSON
assert_rejected repository_dispatch "" "$supported_v2_sha"
assert_rejected push "$ordinary_sha" "$supported_v2_sha"
namespace_for_head "not-a-sha"
assert_rejected repository_dispatch "" "$supported_v2_sha"
FAKE_NAMESPACE_JSON="$(jq -cn --arg head "$baseline_sha" '{metadata:{name:"dev",labels:{"firemud.dev/dev-demo":"false","firemud.dev/environment-class":"dev-demo-cluster"},annotations:{"firemud.dev/last-dev-demo-head-sha":$head}}}')"
export FAKE_NAMESPACE_JSON
assert_rejected repository_dispatch "" "$supported_v2_sha"
assert_rejected push "$ordinary_sha" "$supported_v2_sha"

empty_tree="$(git -C "$repo" mktree </dev/null)"
unrelated_root_sha="$(printf '%s\n' 'unrelated root' | git -C "$repo" commit-tree "$empty_tree")"
namespace_for_head "$unrelated_root_sha"
assert_rejected repository_dispatch "" "$supported_v2_sha"

echo "Hosted V2 migration activation contract passed."
