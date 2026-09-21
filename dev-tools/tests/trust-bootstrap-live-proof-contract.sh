#!/usr/bin/env bash
# shellcheck disable=SC2016 # This contract intentionally asserts literal shell source.
set -euo pipefail

repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
proof="$repo_root/dev-tools/hosted/trust-bootstrap/prove-issuance-boundary.sh"

fail() {
  echo "trust-bootstrap live-proof contract: $1" >&2
  exit 1
}

[[ -f "$proof" ]] || fail 'proof helper is missing'
bash -n "$proof" || fail 'proof helper has invalid shell syntax'

require() {
  local fragment=$1
  grep -Fq -- "$fragment" "$proof" || fail "proof helper is missing: $fragment"
}

require 'usage: prove-issuance-boundary.sh --context CONTEXT --namespace pr-N'
require 'readonly KUBECTL=(kubectl --context "$context")'
require 'system:masters'
require "legacy_identity='system:serviceaccount:kube-system:preview-deployer'"
require "readonly probe_namespace='firemud-system'"
require 'auth can-i'
require '--dry-run=server'
require 'local description=$1 identity=$2 manifest=$3 target_namespace=$4 expected_message=$5'
require 'grep -Fq -- "$expected_message" "$temporary_directory/probe-error"'
require 'firemud-proof-request-no-group'
require 'firemud-proof-request-no-kind'
require 'CA-backed Issuer alias'
require 'CA-backed ClusterIssuer alias'
require 'reserved firemud-system CA Secret mutation'
require 'canonical standalone Gateway Certificate'
require 'canonical standalone TCP Proxy Certificate'
require 'canonical standalone public Telnet Certificate'
require 'standalone writer arbitrary public Certificate'
require 'wrong Gateway SAN'
require 'wrong canonical name'
require 'wrong issuer kind'
require 'cert-manager ServiceAccount is missing'
require 'deployment cert-manager'
require 'DEFERRED later Ready/signing proof'
require 'trap cleanup EXIT'
require 'delete clusterrolebinding "$probe_binding"'
require 'delete clusterrole "$probe_clusterrole"'
require 'delete serviceaccount "$probe_serviceaccount"'
require 'get namespace "$probe_namespace"'
require 'protected namespace $probe_namespace is not present'
require 'namespace: $probe_namespace'
require 'system:serviceaccount:$probe_namespace:$probe_serviceaccount'
require 'resources_created=1'
require 'firemud-grpc-ca'
require 'standalone certificate writer cannot create Certificates'
require 'bind-runtime-roles.sh'

python3 - "$proof" <<'PY'
from __future__ import annotations

import re
import sys


source = open(sys.argv[1], encoding="utf-8").read()
runner = r'(?:\bkubectl\b|"?\$\{KUBECTL\[@\]\}"?)'
secret_create = re.compile(
    runner + r'[^\n]*(?:^|\s)create\s+[^\n]*\bsecrets?\b', re.MULTILINE
)
secret_get = re.compile(
    runner + r'[^\n]*(?:^|\s)get\s+[^\n]*\bsecrets?\b', re.MULTILINE
)
apply = re.compile(runner + r'[^\n]*\bapply\b[^\n]*', re.MULTILINE)
heredoc = re.compile(r'<<-?\s*[\'\"]?([A-Za-z_][A-Za-z0-9_]*)[\'\"]?')


def command_lines(text: str) -> list[tuple[int, str]]:
    lines = text.splitlines()
    commands = []
    index = 0
    while index < len(lines):
        end = index
        parts = [lines[index].rstrip()]
        while parts[-1].endswith(("\\", "|")) and end + 1 < len(lines):
            end += 1
            parts.append(lines[end].rstrip())
        commands.append((end, " ".join(parts)))
        index = end + 1
    return commands


def apply_contains_ca(text: str) -> bool:
    lines = text.splitlines()
    for index, line in command_lines(text):
        if line.lstrip().startswith("#"):
            continue
        if not apply.search(line):
            continue
        if "firemud-grpc-ca" in line:
            return True
        marker = heredoc.search(line)
        if marker:
            for payload in lines[index + 1 :]:
                if payload.strip() == marker.group(1):
                    break
                if "firemud-grpc-ca" in payload:
                    return True
    return False


def assert_forbidden_fixture(fixture: str, *, operation: str) -> None:
    if operation == "create":
        assert secret_create.search(fixture), fixture
    elif operation == "get":
        assert secret_get.search(fixture), fixture
    else:
        assert apply_contains_ca(fixture), fixture


assert 'readonly probe_namespace=\'firemud-system\'' in source
assert 'get namespace "$probe_namespace"' in source
assert 'delete serviceaccount "$probe_serviceaccount"' in source
assert 'namespace: $probe_namespace' in source
assert 'probe_identity="system:serviceaccount:$probe_namespace:$probe_serviceaccount"' in source
assert 'probe_identity="system:serviceaccount:$namespace:$probe_serviceaccount"' not in source


# Deterministic fixtures ensure the guard continues to cover direct kubectl,
# array-based kubectl, optional context/namespace flags, and CA material in an
# apply heredoc even when the production helper itself has no forbidden call.
assert_forbidden_fixture(
    "kubectl --context ctx --namespace firemud-system create secret generic firemud-grpc-ca",
    operation="create",
)
assert_forbidden_fixture(
    '"${KUBECTL[@]}" -n firemud-system get secrets firemud-grpc-ca',
    operation="get",
)
assert_forbidden_fixture(
    "kubectl --context ctx -n firemud-system apply -f - <<EOF\n"
    "metadata:\n  name: firemud-grpc-ca\nEOF\n",
    operation="apply",
)
assert_forbidden_fixture(
    'cat <<EOF | "${KUBECTL[@]}" --namespace firemud-system apply -f -\n'
    "metadata:\n  name: firemud-grpc-ca\nEOF\n",
    operation="apply",
)
assert_forbidden_fixture(
    'cat <<EOF | \\\n'
    '"${KUBECTL[@]}" --context ctx apply -f -\n'
    "metadata:\n  name: firemud-grpc-ca\nEOF\n",
    operation="apply",
)

if any(secret_create.search(line) for _, line in command_lines(source)):
    print('proof helper must not create a Secret', file=sys.stderr)
    raise SystemExit(1)
if any(secret_get.search(line) for _, line in command_lines(source)):
    print('proof helper must not read Secret data', file=sys.stderr)
    raise SystemExit(1)
if apply_contains_ca(source):
    print('proof helper must not apply CA material', file=sys.stderr)
    raise SystemExit(1)
PY

echo 'trust-bootstrap live-proof contract: PASS'
