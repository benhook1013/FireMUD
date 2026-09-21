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
require 'resources_created=1'
require 'firemud-grpc-ca'

if grep -Fq -- 'kubectl create secret' "$proof"; then
  fail 'proof helper must not create a Secret'
fi
if grep -Fq -- 'kubectl get secret' "$proof"; then
  fail 'proof helper must not read Secret data'
fi
if grep -Fq -- 'kubectl apply.*firemud-grpc-ca' "$proof"; then
  fail 'proof helper must not apply CA material'
fi

echo 'trust-bootstrap live-proof contract: PASS'
