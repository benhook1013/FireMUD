#!/usr/bin/env bash
# Sourceable helper: prepares the pre-v1 (ADR 0185 gate waiver) Account JWT signer stand-in
# fixtures beside a run-owned Compose mTLS root and exports what
# docker/docker-compose.pre-v1-login.override.yml needs.

prepare_pre_v1_signer_fixtures() {
  local cert_root="$1" root_dir out
  root_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
  out="$cert_root.pre-v1-signer"
  if [[ ! -f "$out/identity.json" ]]; then
    rm -rf -- "$out"
    python3 "$root_dir/dev-tools/smoke/pre-v1-signer-fixtures.py" "$out" "$cert_root/authority" \
      --validator-leaf "$cert_root/workloads/game-session-service/client.crt" \
      --validator-ip 172.30.0.20 \
      --account-leaf "$cert_root/workloads/account-service/client.crt" || return 1
  fi
  FIREMUD_PRE_V1_SIGNER_FIXTURES="$out"
  FIREMUD_PRE_V1_NAMESPACE_UID="$(python3 -c 'import json,sys;print(json.load(open(sys.argv[1]))["expectedNamespaceUid"])' "$out/identity.json")"
  FIREMUD_PRE_V1_CLUSTER_UID="$(python3 -c 'import json,sys;print(json.load(open(sys.argv[1]))["expectedClusterIncarnationUid"])' "$out/identity.json")"
  FIREMUD_PRE_V1_CONFIG_MAP_UID="$(python3 -c 'import json,sys;print(json.load(open(sys.argv[1]))["configMapUid"])' "$out/identity.json")"
  export FIREMUD_PRE_V1_SIGNER_FIXTURES FIREMUD_PRE_V1_NAMESPACE_UID FIREMUD_PRE_V1_CLUSTER_UID \
    FIREMUD_PRE_V1_CONFIG_MAP_UID
}
