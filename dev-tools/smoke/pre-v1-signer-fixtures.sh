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
  unset FIREMUD_SMOKE_FIXTURE_WEBSOCKET_CERTIFICATE_SHA256
}

prepare_run_owned_websocket_client() {
  local cert_root="$1" directory stage cert key ca certificate_public_key private_public_key digest serial
  if [[ "$cert_root" != "$FIREMUD_SMOKE_OWNERSHIP_DIR_RESOLVED/$FIREMUD_SMOKE_PROJECT_KEY.grpc-mtls" ]]; then
    echo "WebSocket fixture certificate root does not match the claimed project." >&2
    return 1
  fi
  directory="$cert_root/websocket-client"
  ca="$cert_root/authority/ca.crt"
  if [[ ! -e "$directory" && ! -L "$directory" ]]; then
    serial="$(openssl rand -hex 16)" || return 1
    stage="$(mktemp -d "$cert_root/.websocket-client.XXXXXX")" || return 1
    (
      umask 077
      trap 'rm -rf -- "$stage"' EXIT
      openssl req -new -newkey rsa:2048 -noenc -keyout "$stage/client.key" \
        -out "$stage/client.csr" -subj /CN=firemud-run-owned-websocket-client >/dev/null 2>&1 &&
        openssl x509 -req -in "$stage/client.csr" -CA "$ca" -CAkey "$cert_root/authority/ca.key" \
          -out "$stage/client.crt" -days 2 -sha256 -set_serial "0x$serial" \
          -extfile /dev/stdin >/dev/null 2>&1 <<'EXT' &&
basicConstraints=critical,CA:false
keyUsage=critical,digitalSignature
extendedKeyUsage=clientAuth
EXT
      rm -- "$stage/client.csr" && mv -T -- "$stage" "$directory"
    ) || return 1
  fi
  cert="$directory/client.crt"
  key="$directory/client.key"
  if [[ -L "$directory" || ! -d "$directory" ||
      "$(stat -Lc '%u %a' "$directory")" != "$(id -u) 700" ||
      -L "$cert" || ! -f "$cert" || -L "$key" || ! -f "$key" ||
      "$(stat -Lc '%u %a' "$key")" != "$(id -u) 600" ]]; then
    echo "The claimed WebSocket fixture client files are unavailable." >&2
    return 1
  fi
  openssl verify -purpose sslclient -CAfile "$ca" "$cert" >/dev/null || return 1
  certificate_public_key="$(openssl x509 -in "$cert" -noout -pubkey)" || return 1
  private_public_key="$(openssl pkey -in "$key" -pubout)" || return 1
  [[ "$certificate_public_key" == "$private_public_key" ]] || return 1
  digest="$(openssl x509 -in "$cert" -outform DER | sha256sum)" || return 1
  FIREMUD_SMOKE_FIXTURE_WEBSOCKET_CERTIFICATE_SHA256="${digest%% *}"
  export FIREMUD_SMOKE_FIXTURE_WEBSOCKET_CERTIFICATE_SHA256
}
