#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
GENERATOR="$REPO_ROOT/dev-tools/certs/generate-dev-certs.sh"
ENSURE="$REPO_ROOT/dev-tools/certs/ensure-dev-certs.sh"
CLEAN="$REPO_ROOT/dev-tools/certs/clean-dev-certs.sh"
TEST_ROOT="$(mktemp -d)"
trap 'rm -rf -- "$TEST_ROOT"' EXIT

# Only inspect this test's own fixture; record links without following targets.
snapshot() {
  python3 - "$1" <<'PY'
import hashlib
import os
import pathlib
import stat
import sys

root = pathlib.Path(sys.argv[1])
for directory, subdirs, files in os.walk(root, followlinks=False):
    for path in [pathlib.Path(directory)] + sorted(pathlib.Path(directory) / name for name in subdirs + files):
        info = path.lstat()
        value = ""
        if stat.S_ISLNK(info.st_mode):
            value = os.readlink(path)
        elif stat.S_ISREG(info.st_mode):
            value = hashlib.sha256(path.read_bytes()).hexdigest()
        print(path.relative_to(root), info.st_mode, info.st_nlink, value)
PY
}

expect_unchanged_failure() {
  local fixture="$1" before after
  shift
  before="$(snapshot "$fixture")"
  if "$@" >"$TEST_ROOT/failure-output" 2>&1; then
    echo "Expected certificate operation to refuse retained fixture: $*" >&2
    exit 1
  fi
  after="$(snapshot "$fixture")"
  [[ "$before" == "$after" ]]
}

fresh="$TEST_ROOT/fresh"
mkdir "$fresh"
printf 'foreign material\n' >"$fresh/foreign.pem"
chmod 600 "$fresh/foreign.pem"
bash "$GENERATOR" "$fresh" >"$TEST_ROOT/generation-output" 2>&1
[[ "$(stat -c %a "$fresh/foreign.pem")" == 600 ]]
[[ "$(cat "$fresh/foreign.pem")" == 'foreign material' ]]
for name in ca.crt ca.key server.crt server.key client.crt client.key dev-ca.pem dev-cert.pem dev-key.pem; do
  [[ -s "$fresh/$name" ]]
  chmod 640 "$fresh/$name"
done
chmod 750 "$fresh"
before="$(snapshot "$fresh")"
bash "$GENERATOR" "$fresh" >/dev/null
bash "$ENSURE" "$fresh" >/dev/null
[[ "$before" == "$(snapshot "$fresh")" ]]

partial="$TEST_ROOT/partial"
mkdir "$partial"
printf 'retained CA\n' >"$partial/ca.crt"
expect_unchanged_failure "$partial" bash "$GENERATOR" "$partial"
expect_unchanged_failure "$partial" bash "$ENSURE" "$partial"

other="$TEST_ROOT/other"
bash "$GENERATOR" "$other" >"$TEST_ROOT/other-output" 2>&1
for corruption in malformed key ca ca-key; do
  fixture="$TEST_ROOT/$corruption"
  cp -a -- "$fresh" "$fixture"
  case "$corruption" in
    malformed) printf 'invalid certificate\n' >"$fixture/client.crt" ;;
    key) cp -- "$other/client.key" "$fixture/dev-key.pem" ;;
    ca) cp -- "$other/ca.crt" "$fixture/ca.crt"; cp -- "$other/ca.key" "$fixture/ca.key"; cp -- "$other/dev-ca.pem" "$fixture/dev-ca.pem" ;;
    ca-key) cp -- "$other/ca.key" "$fixture/ca.key" ;;
  esac
  expect_unchanged_failure "$fixture" bash "$GENERATOR" "$fixture"
  expect_unchanged_failure "$fixture" bash "$ENSURE" "$fixture"
done

links="$TEST_ROOT/links"
mkdir "$links" "$links/outside" "$links/partial" "$links/hard" "$links/nonregular"
printf 'outside retained material\n' >"$links/outside/material"
ln -s "$links/outside/material" "$links/partial/dev-key.pem"
ln "$links/outside/material" "$links/hard/ca.key"
mkdir "$links/nonregular/ca.crt"
ln -s "$links/outside" "$links/directory"
for target in "$links/partial" "$links/hard" "$links/nonregular" "$links/directory/" "$links/directory/new"; do
  expect_unchanged_failure "$links" bash "$GENERATOR" "$target"
  expect_unchanged_failure "$links" bash "$ENSURE" "$target"
done
# A link in an otherwise complete bundle is also refused without a mode change.
linked_complete="$TEST_ROOT/linked-complete"
cp -a -- "$fresh" "$linked_complete"
rm -- "$linked_complete/ca.key"
ln -s "$fresh/ca.key" "$linked_complete/ca.key"
expect_unchanged_failure "$linked_complete" bash "$ENSURE" "$linked_complete"

workloads="$TEST_ROOT/workloads"
mkdir "$workloads"
printf 'retained output\n' >"$workloads/existing.crt"
ln -s "$links/outside/material" "$workloads/linked.key"
ln "$links/outside/material" "$workloads/hard.key"
for key in linked.key hard.key; do
  expect_unchanged_failure "$workloads" bash "$GENERATOR" --workload \
    "$fresh/ca.crt" "$fresh/ca.key" "$workloads/new/client.crt" "$workloads/$key" dev world-management-service
done
expect_unchanged_failure "$workloads" bash "$GENERATOR" --workload \
  "$fresh/ca.crt" "$fresh/ca.key" "$workloads/existing.crt" "$workloads/new/client.key" dev world-management-service

cleanup="$TEST_ROOT/cleanup"
cp -a -- "$fresh" "$cleanup"
rm -- "$cleanup/client.key" "$cleanup/server.key"
ln -s "$links/outside/material" "$cleanup/client.key"
ln "$links/outside/material" "$cleanup/server.key"
printf 'foreign certificate\n' >"$cleanup/foreign.crt"
mkdir "$cleanup/foreign-directory"
outside_before="$(snapshot "$links/outside")"
bash "$CLEAN" "$cleanup" >"$TEST_ROOT/cleanup-output"
[[ ! -e "$cleanup/ca.crt" && ! -e "$cleanup/dev-key.pem" ]]
[[ -L "$cleanup/client.key" && -f "$cleanup/server.key" && -d "$cleanup/foreign-directory" ]]
[[ "$(cat "$cleanup/foreign.crt")" == 'foreign certificate' ]]
[[ "$(cat "$cleanup/foreign.pem")" == 'foreign material' ]]
[[ "$outside_before" == "$(snapshot "$links/outside")" ]]
for entry in client.key server.key foreign.crt foreign-directory; do
  grep -Fq "Preserved certificate-directory entry: $cleanup/$entry" "$TEST_ROOT/cleanup-output"
done
for target in "$links/directory/" "$links/directory/new"; do
  expect_unchanged_failure "$links" bash "$CLEAN" "$target"
done

echo 'Development certificate retention contract passed.'
