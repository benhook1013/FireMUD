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
 jq -e --arg path "/data/$ns" 'length == 3 and .[2].path == $path' <<<"$patch" >/dev/null || return 1
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
jq -e --argjson snapshot "$snapshot" '
  .[:2] == [
    {op:"test",path:"/metadata/uid",value:$snapshot.metadata.uid},
    {op:"test",path:"/metadata/resourceVersion",value:$snapshot.metadata.resourceVersion}
  ]
' calls >/dev/null
snapshot=$(holder 524287)
if write_preview_retry_entry "$snapshot" "$ns" "$record" > blocked 2>diagnostic; then exit 1; fi
test "$(wc -l <calls)" = 1
grep -F 'would exceed the Kubernetes 1 MiB data limit' diagnostic >/dev/null
snapshot=$(holder 524287 | jq -c --arg rec "$record" '.data["pr-123"]=$rec')
write_preview_retry_entry "$snapshot" "$ns" null > retired
printf '%s\n' "$snapshot" > before-retirement
jq -e --slurpfile before before-retirement '
  (.data["pr-123"] | not) and .data["pr-789"] == $before[0].data["pr-789"]
' retired >/dev/null
test "$(wc -l <calls)" = 2
# Account for decoded binary bytes in addition to UTF-8 stored values.
snapshot=$(holder 30 | jq -c '.binaryData={one:"YQ==",two:"YWI=",three:"YWJj"}')
test_snapshot="$snapshot"
preview_retry_holder_headroom "$test_snapshot" | jq -e '
 . == {usedBytes:67,hardLimitBytes:1048576,remainingBytes:1048509}' >/dev/null
read_fail=false
# Used indirectly by report_preview_retry_holder_headroom from the sourced helper.
# shellcheck disable=SC2329
read_preview_retry_holder() {
  [[ "$read_fail" == false ]] || return 1
  printf '%s\n' "$test_snapshot"
}
ordinary_report=$(report_preview_retry_holder_headroom holder)
grep -F 'uses 67/1048576 stored-value bytes' <<<"$ordinary_report" >/dev/null
if grep -F 'Warning:' <<<"$ordinary_report" >/dev/null; then exit 1; fi

snapshot=$(holder 400000)
before_sha=$(jq -S -c . <<<"$snapshot" | sha256sum)
test_snapshot="$snapshot"
high_report=$(report_preview_retry_holder_headroom holder)
grep -F 'at least 75% used' <<<"$high_report" >/dev/null
after_sha=$(jq -S -c . <<<"$test_snapshot" | sha256sum)
test "$before_sha" = "$after_sha"

snapshot=$(holder 30 | jq -c '.binaryData={one:"not-base64"}')
test_snapshot="$snapshot"
unknown_report=$(report_preview_retry_holder_headroom holder)
grep -F 'headroom: unknown' <<<"$unknown_report" >/dev/null
read_fail=true
unknown_report=$(report_preview_retry_holder_headroom holder)
grep -F 'headroom: unknown' <<<"$unknown_report" >/dev/null

# Leave exactly enough room for the replacement, then add a single binary byte.
record_bytes=$(jq -nr --argjson record "$record" '$record | tojson | utf8bytelength')
snapshot=$(holder 0 | jq -c --argjson bytes "$((1048576 - 1 - record_bytes))" --arg rec "$(jq -c '.count=2' <<<"$record")" '.data["pr-789"]=("a"*$bytes) | .data["pr-123"]=$rec')
write_preview_retry_entry "$snapshot" "$ns" "$record" > boundary
test "$(wc -l <calls)" = 3
snapshot=$(jq -c '.binaryData={one:"YQ=="}' <<<"$snapshot")
if write_preview_retry_entry "$snapshot" "$ns" "$record" > blocked_binary 2>diagnostic_binary; then exit 1; fi
test ! -s blocked_binary
test "$(wc -l <calls)" = 3
grep -F 'prospective stored values: 1048577 bytes' diagnostic_binary >/dev/null

echo "Read-only preview proof retry capacity checks passed"
