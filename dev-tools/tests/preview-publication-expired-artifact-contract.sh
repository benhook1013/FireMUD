#!/usr/bin/env bash
set -euo pipefail

root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
source_file="${1:-$root/dev-tools/hosted/shared/wait-for-runtime-images.sh}"
tmp="$(mktemp -d)"
trap 'rm -rf -- "$tmp"' EXIT

# Extract only the lookup from the fixed source; never start the deployment waiter.
sed -n '/^read_exact_run_artifact() {/,/^}/p' "$source_file" >"$tmp/lookup"
[[ -s "$tmp/lookup" ]]
# shellcheck source=/dev/null
source "$tmp/lookup"
export GITHUB_REPOSITORY=example/FireMUD
run=10
name="pr-runtime-images-pr-merge-$(printf '%040d' 0)-attempt-1"

fetch_alternate_run_metadata() {
  [[ "$1" == 30 && "$2" == "repos/$GITHUB_REPOSITORY/actions/runs/$run/artifacts?name=$name&per_page=100" ]]
  jq -cn --arg name "$name" --argjson run "$run" --argjson expired "$fixture_expired" '
    {total_count:1,artifacts:[{id:4,name:$name,expired:$expired,
      workflow_run:{id:$run},digest:("sha256:"+("a"*64))}]}'
}

fixture_expired=false
read_exact_run_artifact 30 "$run" "$name" >"$tmp/active" 2>"$tmp/active.err"
jq -e '.id == 4 and .digest == ("sha256:"+("a"*64))' "$tmp/active" >/dev/null
[[ ! -s "$tmp/active.err" ]]

# This is an ordinary ended artifact lifecycle, not a publication or retry.
fixture_expired=true
if read_exact_run_artifact 30 "$run" "$name" >"$tmp/expired" 2>"$tmp/expired.err"; then
  echo 'An expired metadata row cannot qualify publication' >&2
  exit 1
fi
[[ ! -s "$tmp/expired" ]]
grep -Fq 'Exact producer artifact has expired; publication proof remains unverified' "$tmp/expired.err"
