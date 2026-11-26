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
  [[ "$1" == 30 && "$2" == "repos/$GITHUB_REPOSITORY/actions/runs/$run/artifacts?name=$name&per_page=100" ]] || return 1
  case "$artifact_state" in
    missing) printf '%s' '{"total_count":0,"artifacts":[]}' ;;
    unreadable) return 1 ;;
    present|expired)
      local expired=false
      [[ "$artifact_state" != expired ]] || expired=true
      jq -cn --arg name "$name" --argjson run "$run" --argjson expired "$expired" '
        {total_count:1,artifacts:[{id:4,name:$name,expired:$expired,
          workflow_run:{id:$run},digest:("sha256:"+("a"*64))}]}'
      ;;
  esac
}

artifact_state=present
read_exact_run_artifact 30 "$run" "$name" >"$tmp/active" 2>"$tmp/active.err"
jq -e '.id == 4 and .digest == ("sha256:"+("a"*64))' "$tmp/active" >/dev/null
[[ ! -s "$tmp/active.err" ]]

artifact_state=expired
if read_exact_run_artifact 30 "$run" "$name"; then exit 1; fi
read_exact_run_artifact 30 "$run" "$name" true |
  jq -e '.id == 4 and .digest == ("sha256:"+("a"*64))' >/dev/null

artifact_state=missing
if read_exact_run_artifact 30 "$run" "$name"; then exit 1; fi
read_exact_run_artifact 30 "$run" "$name" true |
  jq -e '. == {id:null,digest:null}' >/dev/null

artifact_state=unreadable
if read_exact_run_artifact 30 "$run" "$name" true; then exit 1; fi
