#!/usr/bin/env bash
set -euo pipefail
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
work="$(mktemp -d)"
trap 'rm -rf -- "$work"' EXIT
export SNAPSHOT="$work/snapshot"
cat >"$work/kubectl" <<'MOCK'
#!/usr/bin/env bash
set -euo pipefail
[[ "$*" == "get namespace pr-42 --ignore-not-found -o json --request-timeout=10s" ]]
cat "$SNAPSHOT"
MOCK
chmod +x "$work/kubectl"
export PATH="$work:$PATH"
uid=exact-uid
base=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
head=bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb
merge=cccccccccccccccccccccccccccccccccccccccc
image=fixture
jq -n --arg uid "$uid" --arg base "$base" --arg head "$head" --arg merge "$merge" --arg image "$image" '
  {apiVersion:"v1",kind:"Namespace",metadata:{name:"pr-42",uid:$uid,annotations:{
    "firemud.dev/last-preview-base-sha":$base, "firemud.dev/last-preview-head-sha":$head,
    "firemud.dev/last-preview-merge-sha":$merge, "firemud.dev/last-preview-image-tag":$image,
    "firemud.dev/proof-preview-base-sha":$base, "firemud.dev/proof-preview-head-sha":$head,
    "firemud.dev/proof-preview-merge-sha":$merge, "firemud.dev/proof-preview-image-tag":$image,
    "firemud.dev/proof-preview-namespace-uid":$uid
  }}}
' >"$work/proof"
check() { bash "$repo_root/dev-tools/hosted/preview/check-preview-proof-retry.sh" pr-42 "$uid" "$base" "$head" "$merge" "$image"; }
cp "$work/proof" "$SNAPSHOT"
check_output="$(check)"
[[ "$check_output" == proof-complete ]]
jq 'del(.metadata.annotations["firemud.dev/proof-preview-base-sha"])' "$work/proof" >"$SNAPSHOT"
check_output="$(check)"
[[ "$check_output" == proof-missing ]]
jq '.metadata.uid = "replacement-uid"' "$work/proof" >"$SNAPSHOT"
check_output="$(check)"
[[ "$check_output" == namespace-changed ]]
: >"$SNAPSHOT"
check_output="$(check)"
[[ "$check_output" == namespace-changed ]]
printf '%s\n' 'Read-only exact preview proof retry checks passed'

bash "$repo_root/dev-tools/tests/preview-proof-retry-capacity-contract.sh"
