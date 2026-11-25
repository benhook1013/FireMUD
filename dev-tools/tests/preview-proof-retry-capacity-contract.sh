#!/usr/bin/env bash
set -euo pipefail
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
# shellcheck source=/dev/null
source "$ROOT_DIR/dev-tools/hosted/preview/preview-proof-retry-record.shlib"
TEMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TEMP_DIR"' EXIT
cd "$TEMP_DIR"
export GITHUB_REPOSITORY=benhook1013/FireMUD
ns=pr-123
record=$(jq -cn --arg ns "$ns" '{base:("a"*40),count:1,head:("b"*40),image:"image1",merge:("c"*40),namespace:$ns,namespaceUid:"",nonce:"",repository:env.GITHUB_REPOSITORY}')
holder() {
 jq -cn --argjson filler "$1" '{apiVersion:"v1",kind:"ConfigMap",metadata:{namespace:"firemud-system",name:"firemud-preview-proof-retries",uid:"holder",resourceVersion:"rv-1"},data:{schemaVersion:"1","pr-789":("é"*$filler)}}'
}
kubectl() {
 local patch=
 while (( $# )); do
   if [[ $1 == --patch ]]; then patch=$2; shift; fi
   shift
 done
 test -n "$patch"
 printf '%s\n' "$patch" >>calls
 jq -c --argjson patch "$patch" --arg ns "$ns" '
  .metadata.resourceVersion="rv-2"
  | if $patch[2].op=="remove" then del(.data[$ns])
    else .data[$ns]=$patch[2].value end' <<<"$snapshot"
}
: >calls
snapshot=$(holder 30)
write_preview_retry_entry "$snapshot" "$ns" "$record" > admitted
jq -e '.data["pr-123"] and .data["pr-789"]' admitted >/dev/null
jq -e '.[0].path=="/metadata/uid" and .[1].path=="/metadata/resourceVersion"' calls >/dev/null
snapshot=$(holder 524287)
if write_preview_retry_entry "$snapshot" "$ns" "$record" > blocked 2>diagnostic; then exit 1; fi
test "$(wc -l <calls)" = 1
grep -F 'would exceed the Kubernetes 1 MiB data limit' diagnostic >/dev/null
snapshot=$(holder 524287 | jq -c --arg rec "$record" '.data["pr-123"]=$rec')
write_preview_retry_entry "$snapshot" "$ns" null > retired
jq -e '.data["pr-123"]|not' retired >/dev/null
test "$(wc -l <calls)" = 2
echo "Read-only preview proof retry capacity checks passed"
