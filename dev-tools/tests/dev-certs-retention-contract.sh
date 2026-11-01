#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
GENERATOR="$REPO_ROOT/dev-tools/certs/generate-dev-certs.sh"
ENSURE="$REPO_ROOT/dev-tools/certs/ensure-dev-certs.sh"
CLEAN="$REPO_ROOT/dev-tools/certs/clean-dev-certs.sh"
TEST_ROOT="$(cd "$(mktemp -d)" && pwd -P)"
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
  local fixture="$1" marker="$2" before after
  shift 2
  before="$(snapshot "$fixture")"
  if "$@" >"$TEST_ROOT/failure-stdout" 2>"$TEST_ROOT/failure-stderr"; then
    echo "Expected certificate operation to refuse retained fixture: $*" >&2
    exit 1
  fi
  if ! grep -Fq -- "$marker" "$TEST_ROOT/failure-stderr"; then
    echo "Missing refusal marker '$marker' for: $*" >&2
    exit 1
  fi
  after="$(snapshot "$fixture")"
  if [[ "$before" != "$after" ]]; then
    echo "Fixture changed during refused operation: $fixture" >&2
    exit 1
  fi
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
for command in "$GENERATOR" "$ENSURE"; do
  expect_unchanged_failure "$partial" "Incomplete retained development certificate bundle" bash "$command" "$partial"
  grep -Fq 'Only for disposable generated local material' "$TEST_ROOT/failure-stderr"
  grep -Fq 'clean-dev-certs.sh ' "$TEST_ROOT/failure-stderr"
  grep -Fq 'Preserve externally managed material' "$TEST_ROOT/failure-stderr"
done

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
  for command in "$GENERATOR" "$ENSURE"; do
    expect_unchanged_failure "$fixture" "Invalid retained development certificate bundle" bash "$command" "$fixture"
    grep -Fq 'Only for disposable generated local material' "$TEST_ROOT/failure-stderr"
    grep -Fq 'clean-dev-certs.sh ' "$TEST_ROOT/failure-stderr"
    grep -Fq 'Preserve externally managed material' "$TEST_ROOT/failure-stderr"
  done
done

links="$TEST_ROOT/links"
mkdir "$links" "$links/outside" "$links/partial" "$links/hard" "$links/nonregular"
printf 'outside retained material\n' >"$links/outside/material"
ln -s "$links/outside/material" "$links/partial/dev-key.pem"
ln "$links/outside/material" "$links/hard/ca.key"
mkdir "$links/nonregular/ca.crt"
ln -s "$links/outside" "$links/directory"
for target in "$links/partial" "$links/hard" "$links/nonregular" "$links/directory/" "$links/directory/new"; do
  marker="Certificate paths must be regular files without symlinks or hardlinks"
  case "$target" in
    "$links/directory/"|"$links/directory/new") marker="Certificate directory and ancestors must be real directories" ;;
  esac
  expect_unchanged_failure "$links" "$marker" bash "$GENERATOR" "$target"
  expect_unchanged_failure "$links" "$marker" bash "$ENSURE" "$target"
done
# A link in an otherwise complete bundle is also refused without a mode change.
linked_complete="$TEST_ROOT/linked-complete"
cp -a -- "$fresh" "$linked_complete"
rm -- "$linked_complete/ca.key"
ln -s "$fresh/ca.key" "$linked_complete/ca.key"
expect_unchanged_failure "$linked_complete" "Certificate paths must be regular files without symlinks or hardlinks" bash "$ENSURE" "$linked_complete"

workloads="$TEST_ROOT/workloads"
mkdir "$workloads"
printf 'retained output\n' >"$workloads/existing.crt"
ln -s "$links/outside/material" "$workloads/linked.key"
ln "$links/outside/material" "$workloads/hard.key"
for key in linked.key hard.key; do
  expect_unchanged_failure "$workloads" "Certificate paths must be regular files without symlinks or hardlinks" bash "$GENERATOR" --workload \
    "$fresh/ca.crt" "$fresh/ca.key" "$workloads/new/client.crt" "$workloads/$key" dev world-management-service
done
expect_unchanged_failure "$workloads" "Refusing to overwrite retained workload output" bash "$GENERATOR" --workload \
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
  expect_unchanged_failure "$links" "Certificate cleanup refuses linked or non-directory paths" bash "$CLEAN" "$target"
done

# Resolve the implicit script root physically while refusing explicit linked paths.
physical_root="$TEST_ROOT/physical-root"
mkdir -p "$physical_root/certs"
cp -p -- "$GENERATOR" "$ENSURE" "$CLEAN" "$physical_root/certs/"
ln -s "$physical_root" "$TEST_ROOT/workspace-link"
printf 'retained checkout material\n' >"$physical_root/certs/foreign.pem"
(
  unset CERT_DIR
  cd "$TEST_ROOT/workspace-link"
  bash certs/generate-dev-certs.sh >"$TEST_ROOT/default-generation-output" 2>&1
)
[[ -s "$physical_root/certs/ca.crt" && -s "$physical_root/certs/server.key" ]]
before="$(snapshot "$physical_root")"
(
  unset CERT_DIR
  cd "$TEST_ROOT/workspace-link"
  bash certs/ensure-dev-certs.sh >"$TEST_ROOT/default-ensure-output" 2>&1
)
[[ "$before" == "$(snapshot "$physical_root")" ]]
for operation in "$GENERATOR" "$ENSURE" "$CLEAN"; do
  marker="Certificate directory and ancestors must be real directories"
  if [[ "$operation" == "$CLEAN" ]]; then
    marker="Certificate cleanup refuses linked or non-directory paths"
  fi
  expect_unchanged_failure "$physical_root" "$marker" bash "$operation" "$TEST_ROOT/workspace-link/certs"
done
(
  unset CERT_DIR
  cd "$TEST_ROOT/workspace-link"
  bash certs/clean-dev-certs.sh >"$TEST_ROOT/default-cleanup-output" 2>&1
)
[[ ! -e "$physical_root/certs/ca.crt" && ! -e "$physical_root/certs/server.key" ]]
[[ "$(cat "$physical_root/certs/foreign.pem")" == 'retained checkout material' ]]
for script in generate-dev-certs.sh ensure-dev-certs.sh clean-dev-certs.sh; do
  cmp -s "$physical_root/certs/$script" "$REPO_ROOT/dev-tools/certs/$script"
done

echo 'Development certificate retention and physical-default contracts passed.'
