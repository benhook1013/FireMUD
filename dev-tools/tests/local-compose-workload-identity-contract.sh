#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
CERT_DIR="$(mktemp -d)"
trap 'rm -rf "$CERT_DIR"' EXIT
generic_bundle_files=(
  ca.crt ca.key client.crt client.key dev-ca.pem dev-cert.pem dev-key.pem server.crt server.key
)

assert_mode() {
  local expected_mode="$1"
  local path="$2"
  local actual_mode
  actual_mode="$(stat -c '%a' "$path")"
  [[ "$actual_mode" == "$expected_mode" ]] || {
    echo "expected mode $expected_mode for $path, got $actual_mode" >&2
    exit 1
  }
}

copy_generic_bundle() {
  local source_dir="$1"
  local target_dir="$2"
  mkdir -p "$target_dir"
  for file in "${generic_bundle_files[@]}"; do
    cp "$source_dir/$file" "$target_dir/$file"
  done
}

assert_invalid_existing_bundle_is_preserved() {
  local bundle_dir="$1"
  local case_name="$2"
  local manifest="$CERT_DIR/$case_name.bundle.sha256"
  local output="$CERT_DIR/$case_name.output"
  local unmanaged_file="$bundle_dir/unmanaged-local.key"
  local file

  printf 'unmanaged certificate sentinel\n' >"$unmanaged_file"
  chmod 640 "$unmanaged_file"
  for file in "${generic_bundle_files[@]}"; do
    sha256sum "$bundle_dir/$file"
  done >"$manifest"
  sha256sum "$unmanaged_file" >>"$manifest"

  if bash "$ROOT_DIR/dev-tools/certs/generate-dev-certs.sh" "$bundle_dir" >"$output" 2>&1; then
    echo "generate-dev-certs accepted an invalid complete bundle: $case_name" >&2
    exit 1
  fi
  rg -Fq 'no files were changed' "$output" || {
    echo "generate-dev-certs did not report fail-closed preservation for $case_name" >&2
    cat "$output" >&2
    exit 1
  }
  sha256sum -c "$manifest" >/dev/null || {
    echo "generate-dev-certs changed invalid bundle material: $case_name" >&2
    exit 1
  }

  if bash "$ROOT_DIR/dev-tools/certs/ensure-dev-certs.sh" "$bundle_dir" >"$output" 2>&1; then
    echo "ensure-dev-certs accepted an invalid complete bundle: $case_name" >&2
    exit 1
  fi
  rg -Fq 'no files were changed' "$output" || {
    echo "ensure-dev-certs did not report fail-closed preservation for $case_name" >&2
    cat "$output" >&2
    exit 1
  }
  sha256sum -c "$manifest" >/dev/null || {
    echo "ensure-dev-certs changed invalid bundle material: $case_name" >&2
    exit 1
  }
  [[ "$(<"$unmanaged_file")" == 'unmanaged certificate sentinel' ]] || {
    echo "an invalid bundle path changed or removed an unmanaged file: $case_name" >&2
    exit 1
  }
  assert_mode 640 "$unmanaged_file"
}

assert_workload_certificate() {
  local workload="$1"
  local certificate="$CERT_DIR/local-runtime/$workload/workloads/$workload.crt"
  local private_key="$CERT_DIR/local-runtime/$workload/workloads/$workload.key"
  local subject_alt_names uri_sans extended_key_usage expected_uri certificate_public_key private_key_public_key
  expected_uri="spiffe://firemud/ns/local/sa/$workload"

  openssl verify -purpose sslclient -CAfile "$CERT_DIR/local-runtime/$workload/ca.crt" "$certificate" >/dev/null
  openssl verify -purpose sslserver -CAfile "$CERT_DIR/local-runtime/$workload/ca.crt" "$certificate" >/dev/null
  subject_alt_names="$(openssl x509 -in "$certificate" -noout -ext subjectAltName)"
  uri_sans="$(printf '%s\n' "$subject_alt_names" | grep -oE 'URI:[^,[:space:]]+' || true)"
  [[ "$uri_sans" == "URI:$expected_uri" \
    && "$subject_alt_names" == *"DNS:$workload"* \
    && "$subject_alt_names" == *"DNS:$workload.local"* \
    && "$subject_alt_names" == *"DNS:$workload.local.svc"* \
    && "$subject_alt_names" == *"DNS:$workload.local.svc.cluster.local"* ]] || {
    echo "workload certificate has incorrect SANs: $workload" >&2
    printf '%s\n' "$subject_alt_names" >&2
    exit 1
  }
  extended_key_usage="$(openssl x509 -in "$certificate" -noout -ext extendedKeyUsage)"
  [[ "$extended_key_usage" == *"TLS Web Server Authentication"* \
    && "$extended_key_usage" == *"TLS Web Client Authentication"* ]] || {
    echo "workload certificate does not support both gRPC mTLS roles: $workload" >&2
    exit 1
  }
  certificate_public_key="$(openssl x509 -in "$certificate" -pubkey -noout \
    | openssl pkey -pubin -outform DER | openssl dgst -sha256)"
  private_key_public_key="$(openssl pkey -in "$private_key" -pubout -outform DER | openssl dgst -sha256)"
  [[ "$certificate_public_key" == "$private_key_public_key" ]] || {
    echo "workload certificate and private key do not match: $workload" >&2
    exit 1
  }
}

assert_invalid_workload_uri_is_reissued() {
  local case_name="$1"
  local subject_alt_name="$2"
  local certificate="$CERT_DIR/workloads/account-service.crt"
  local private_key="$CERT_DIR/workloads/account-service.key"
  local fixture_dir="$CERT_DIR/workload-uri-$case_name"
  local config="$fixture_dir/extensions.cnf"
  local request="$fixture_dir/workload.csr"
  local before_fingerprint after_fingerprint certificate_public_key private_key_public_key
  mkdir -p "$fixture_dir"
  openssl req -new -key "$private_key" -subj "/CN=firemud-grpc-account-service" \
    -out "$request" >/dev/null 2>&1
  printf '%s\n' \
    '[leaf]' \
    'basicConstraints=critical,CA:FALSE' \
    'keyUsage=critical,digitalSignature,keyEncipherment' \
    'extendedKeyUsage=serverAuth,clientAuth' \
    "subjectAltName=$subject_alt_name" \
    >"$config"
  openssl x509 -req -in "$request" -CA "$CERT_DIR/ca.crt" -CAkey "$CERT_DIR/ca.key" \
    -set_serial "0x$(openssl rand -hex 16)" -out "$certificate" -days 30 -sha256 \
    -extfile "$config" -extensions leaf >/dev/null 2>&1

  # Keep CA trust, both mTLS usages, and key pairing valid so only the URI identity is wrong.
  openssl verify -purpose sslclient -CAfile "$CERT_DIR/ca.crt" "$certificate" >/dev/null
  openssl verify -purpose sslserver -CAfile "$CERT_DIR/ca.crt" "$certificate" >/dev/null
  certificate_public_key="$(openssl x509 -in "$certificate" -pubkey -noout \
    | openssl pkey -pubin -outform DER | openssl dgst -sha256)"
  private_key_public_key="$(openssl pkey -in "$private_key" -pubout -outform DER \
    | openssl dgst -sha256)"
  [[ "$certificate_public_key" == "$private_key_public_key" ]] || {
    echo "invalid URI fixture does not match the account-service key: $case_name" >&2
    exit 1
  }

  before_fingerprint="$(openssl x509 -in "$certificate" -noout -fingerprint -sha256 | cut -d= -f2)"
  bash "$ROOT_DIR/dev-tools/certs/ensure-dev-certs.sh" "$CERT_DIR"
  after_fingerprint="$(openssl x509 -in "$certificate" -noout -fingerprint -sha256 | cut -d= -f2)"
  [[ "$after_fingerprint" != "$before_fingerprint" ]] || {
    echo "ensure-dev-certs retained an incorrect workload URI identity: $case_name" >&2
    exit 1
  }
  assert_workload_certificate account-service
}

bash "$ROOT_DIR/dev-tools/certs/ensure-dev-certs.sh" "$CERT_DIR"

runtime_dir="$CERT_DIR/local-runtime"
[[ -z "$(find "$runtime_dir" -name ca.key -print -quit)" ]] || {
  echo "CA private key was copied into the Compose runtime projection" >&2
  exit 1
}
assert_mode 600 "$CERT_DIR/ca.key"
assert_mode 700 "$CERT_DIR/workloads"
assert_mode 755 "$runtime_dir"
for profile in default account-service game-session-service social-groups-service; do
  assert_mode 755 "$runtime_dir/$profile"
  assert_mode 644 "$runtime_dir/$profile/client.key"
  assert_mode 644 "$runtime_dir/$profile/server.key"
  assert_mode 644 "$runtime_dir/$profile/dev-key.pem"
done
for workload in account-service game-session-service social-groups-service; do
  assert_mode 644 "$CERT_DIR/workloads/$workload.crt"
  assert_mode 600 "$CERT_DIR/workloads/$workload.key"
  assert_mode 755 "$runtime_dir/$workload/workloads"
  assert_mode 644 "$runtime_dir/$workload/workloads/$workload.key"
done

# A hard-linked managed projection destination must be rejected before any
# other profile's projection is copied or chmodded.
hardlink_case="$CERT_DIR/hardlink-projection-case"
copy_generic_bundle "$CERT_DIR" "$hardlink_case"
bash "$ROOT_DIR/dev-tools/certs/ensure-dev-certs.sh" "$hardlink_case"
hardlink_runtime="$hardlink_case/local-runtime"
hardlink_target="$hardlink_runtime/social-groups-service/workloads/social-groups-service.key"
hardlink_sentinel="$hardlink_case/runtime-projection-alias-sentinel.txt"
hardlink_output="$CERT_DIR/hardlink-projection-output"
hardlink_before="$CERT_DIR/hardlink-projection-before.sha256"
hardlink_after="$CERT_DIR/hardlink-projection-after.sha256"

snapshot_runtime_projection() {
  local root="$1"
  local file
  while IFS= read -r -d '' file; do
    printf '%s ' "$(stat -c '%a %h' "$file")"
    sha256sum -- "$file"
  done < <(find "$root" -type f -print0 | sort -z)
}

snapshot_certificate_tree() {
  local root="$1"
  local file
  while IFS= read -r -d '' file; do
    printf '%s ' "$(stat -c '%a' "$file")"
    sha256sum -- "$file"
  done < <(find "$root" -type f -print0 | sort -z)
}

printf 'external hard-link sentinel\n' >"$hardlink_sentinel"
chmod 640 "$hardlink_sentinel"
rm -- "$hardlink_target"
ln -- "$hardlink_sentinel" "$hardlink_target"
snapshot_runtime_projection "$hardlink_runtime" >"$hardlink_before"

if bash "$ROOT_DIR/dev-tools/certs/ensure-dev-certs.sh" "$hardlink_case" >"$hardlink_output" 2>&1; then
  echo "ensure-dev-certs accepted a hard-linked local runtime projection file" >&2
  exit 1
fi
rg -Fq "refusing hard-linked file in local Compose runtime projection: $hardlink_target" \
  "$hardlink_output" || {
  echo "ensure-dev-certs did not identify the hard-linked projection path" >&2
  cat "$hardlink_output" >&2
  exit 1
}
snapshot_runtime_projection "$hardlink_runtime" >"$hardlink_after"
cmp -s "$hardlink_before" "$hardlink_after" || {
  echo "ensure-dev-certs partially changed the local runtime projection before refusal" >&2
  exit 1
}
[[ "$(<"$hardlink_sentinel")" == 'external hard-link sentinel' ]] || {
  echo "ensure-dev-certs changed an external hard-link sentinel" >&2
  exit 1
}
assert_mode 640 "$hardlink_sentinel"

# Ensure preflights every managed source before the generic CA-key chmod or a
# missing workload-pair regeneration can alter either side of a hard link.
source_hardlink_case="$CERT_DIR/hardlink-source-case"
copy_generic_bundle "$CERT_DIR" "$source_hardlink_case"
bash "$ROOT_DIR/dev-tools/certs/ensure-dev-certs.sh" "$source_hardlink_case"
source_hardlink_key="$source_hardlink_case/workloads/game-session-service.key"
source_hardlink_certificate="$source_hardlink_case/workloads/game-session-service.crt"
source_hardlink_sentinel="$source_hardlink_case/source-key-alias-sentinel"
source_hardlink_output="$CERT_DIR/hardlink-source-output"
printf 'external source-key sentinel\n' >"$source_hardlink_sentinel"
chmod 640 "$source_hardlink_sentinel"
rm -- "$source_hardlink_key" "$source_hardlink_certificate"
ln -- "$source_hardlink_sentinel" "$source_hardlink_key"
chmod 644 "$source_hardlink_case/ca.key"
source_hardlink_before="$(snapshot_certificate_tree "$source_hardlink_case")"
if bash "$ROOT_DIR/dev-tools/certs/ensure-dev-certs.sh" "$source_hardlink_case" \
  >"$source_hardlink_output" 2>&1; then
  echo "ensure-dev-certs accepted a hard-linked workload source key." >&2
  exit 1
fi
rg -Fq "refusing hard-linked workload private-key output: $source_hardlink_key" \
  "$source_hardlink_output"
[[ "$source_hardlink_before" == "$(snapshot_certificate_tree "$source_hardlink_case")" \
  && "$(<"$source_hardlink_sentinel")" == 'external source-key sentinel' ]] || {
  echo "ensure-dev-certs partially changed a hard-linked workload source or another managed file." >&2
  exit 1
}
assert_mode 640 "$source_hardlink_sentinel"
assert_mode 644 "$source_hardlink_case/ca.key"

# The standalone generic generator must reject a hard-linked output before an
# incomplete bundle can rotate or overwrite any of its remaining material.
generator_hardlink_case="$CERT_DIR/generator-hardlink-case"
copy_generic_bundle "$CERT_DIR" "$generator_hardlink_case"
generator_hardlink_key="$generator_hardlink_case/server.key"
generator_hardlink_sentinel="$generator_hardlink_case/server-key-alias-sentinel"
generator_hardlink_output="$CERT_DIR/generator-hardlink-output"
cp -- "$generator_hardlink_key" "$generator_hardlink_sentinel"
chmod 640 "$generator_hardlink_sentinel"
rm -- "$generator_hardlink_key" "$generator_hardlink_case/dev-key.pem"
ln -- "$generator_hardlink_sentinel" "$generator_hardlink_key"
generator_hardlink_before="$(snapshot_certificate_tree "$generator_hardlink_case")"
if bash "$ROOT_DIR/dev-tools/certs/generate-dev-certs.sh" "$generator_hardlink_case" \
  >"$generator_hardlink_output" 2>&1; then
  echo "generate-dev-certs accepted a hard-linked managed certificate output." >&2
  exit 1
fi
rg -Fq "refusing hard-linked certificate material: $generator_hardlink_key" \
  "$generator_hardlink_output"
[[ "$generator_hardlink_before" == "$(snapshot_certificate_tree "$generator_hardlink_case")" ]] || {
  echo "generate-dev-certs partially changed an incomplete hard-linked bundle." >&2
  exit 1
}
assert_mode 640 "$generator_hardlink_sentinel"

# The standalone workload signer preflights both read-only CA inputs and its
# certificate/key outputs before it creates either output.
workload_source_case="$CERT_DIR/generator-workload-source-case"
mkdir -p "$workload_source_case/output"
cp -- "$CERT_DIR/ca.crt" "$workload_source_case/ca.crt"
cp -- "$CERT_DIR/ca.key" "$workload_source_case/ca-key-sentinel"
chmod 640 "$workload_source_case/ca-key-sentinel"
ln -- "$workload_source_case/ca-key-sentinel" "$workload_source_case/ca.key"
workload_source_before="$(snapshot_certificate_tree "$workload_source_case")"
workload_source_output="$CERT_DIR/generator-workload-source-output"
if bash "$ROOT_DIR/dev-tools/certs/generate-dev-certs.sh" --workload \
  "$workload_source_case/ca.crt" "$workload_source_case/ca.key" \
  "$workload_source_case/output/client.crt" "$workload_source_case/output/client.key" \
  local account-service >"$workload_source_output" 2>&1; then
  echo "generate-dev-certs accepted a hard-linked workload CA source." >&2
  exit 1
fi
rg -Fq "refusing hard-linked certificate authority source: $workload_source_case/ca.key" \
  "$workload_source_output"
[[ "$workload_source_before" == "$(snapshot_certificate_tree "$workload_source_case")" \
  && ! -e "$workload_source_case/output/client.crt" \
  && ! -e "$workload_source_case/output/client.key" ]] || {
  echo "generate-dev-certs partially changed output after rejecting a hard-linked CA source." >&2
  exit 1
}
assert_mode 640 "$workload_source_case/ca-key-sentinel"

workload_output_case="$CERT_DIR/generator-workload-output-case"
mkdir -p "$workload_output_case/ca" "$workload_output_case/output"
cp -- "$CERT_DIR/ca.crt" "$workload_output_case/ca/ca.crt"
cp -- "$CERT_DIR/ca.key" "$workload_output_case/ca/ca.key"
workload_output_sentinel="$workload_output_case/output/key-alias-sentinel"
workload_output_key="$workload_output_case/output/client.key"
printf 'external workload-output sentinel\n' >"$workload_output_sentinel"
chmod 640 "$workload_output_sentinel"
ln -- "$workload_output_sentinel" "$workload_output_key"
workload_output_before="$(snapshot_certificate_tree "$workload_output_case")"
workload_output_output="$CERT_DIR/generator-workload-output-output"
if bash "$ROOT_DIR/dev-tools/certs/generate-dev-certs.sh" --workload \
  "$workload_output_case/ca/ca.crt" "$workload_output_case/ca/ca.key" \
  "$workload_output_case/output/client.crt" "$workload_output_key" \
  local account-service >"$workload_output_output" 2>&1; then
  echo "generate-dev-certs accepted a hard-linked workload private-key output." >&2
  exit 1
fi
rg -Fq "refusing hard-linked workload private-key output: $workload_output_key" \
  "$workload_output_output"
[[ "$workload_output_before" == "$(snapshot_certificate_tree "$workload_output_case")" \
  && ! -e "$workload_output_case/output/client.crt" \
  && "$(<"$workload_output_sentinel")" == 'external workload-output sentinel' ]] || {
  echo "generate-dev-certs partially changed a hard-linked workload output." >&2
  exit 1
}
assert_mode 640 "$workload_output_sentinel"

# Present managed paths must be regular files, not directories or other
# special entries that a later generator would partially replace.
invalid_kind_case="$CERT_DIR/non-regular-source-case"
copy_generic_bundle "$CERT_DIR" "$invalid_kind_case"
rm -- "$invalid_kind_case/ca.crt"
mkdir -- "$invalid_kind_case/ca.crt"
invalid_kind_before="$(snapshot_certificate_tree "$invalid_kind_case")"
invalid_kind_output="$CERT_DIR/non-regular-source-output"
if bash "$ROOT_DIR/dev-tools/certs/ensure-dev-certs.sh" "$invalid_kind_case" \
  >"$invalid_kind_output" 2>&1; then
  echo "ensure-dev-certs accepted a non-regular managed certificate path." >&2
  exit 1
fi
rg -Fq "refusing non-regular certificate material: $invalid_kind_case/ca.crt" \
  "$invalid_kind_output"
[[ "$invalid_kind_before" == "$(snapshot_certificate_tree "$invalid_kind_case")" \
  && -d "$invalid_kind_case/ca.crt" ]] || {
  echo "ensure-dev-certs partially changed an invalid managed source path." >&2
  exit 1
}

# Unmanaged regular files under workloads/ retain their own bytes and modes;
# only the three named local Compose identities are normalized by ensure.
mode_case="$CERT_DIR/unmanaged-workload-modes"
copy_generic_bundle "$CERT_DIR" "$mode_case"
mkdir -p "$mode_case/workloads"
printf 'unmanaged certificate sentinel\n' >"$mode_case/workloads/keep-me.crt"
printf 'unmanaged key sentinel\n' >"$mode_case/workloads/keep-me.key"
chmod 711 "$mode_case/workloads/keep-me.crt"
chmod 640 "$mode_case/workloads/keep-me.key"
bash "$ROOT_DIR/dev-tools/certs/ensure-dev-certs.sh" "$mode_case"
[[ "$(<"$mode_case/workloads/keep-me.crt")" == 'unmanaged certificate sentinel' \
  && "$(<"$mode_case/workloads/keep-me.key")" == 'unmanaged key sentinel' ]] || {
  echo "ensure-dev-certs changed unmanaged workload certificate contents" >&2
  exit 1
}
assert_mode 711 "$mode_case/workloads/keep-me.crt"
assert_mode 640 "$mode_case/workloads/keep-me.key"

# Complete but unhealthy bundles fail closed through both public entrypoints.
# Their CA and issued leaves are left byte-for-byte unchanged for deliberate
# operator diagnosis or full local reissue.
malformed_case="$CERT_DIR/invalid-malformed-bundle"
copy_generic_bundle "$CERT_DIR" "$malformed_case"
printf 'malformed certificate\n' >"$malformed_case/ca.crt"
assert_invalid_existing_bundle_is_preserved "$malformed_case" malformed

key_mismatch_case="$CERT_DIR/invalid-key-mismatch-bundle"
copy_generic_bundle "$CERT_DIR" "$key_mismatch_case"
cp "$CERT_DIR/workloads/account-service.key" "$key_mismatch_case/client.key"
cp "$key_mismatch_case/client.key" "$key_mismatch_case/dev-key.pem"
assert_invalid_existing_bundle_is_preserved "$key_mismatch_case" key-mismatch

other_authority="$CERT_DIR/other-authority"
bash "$ROOT_DIR/dev-tools/certs/generate-dev-certs.sh" "$other_authority" >/dev/null
wrong_issuer_case="$CERT_DIR/invalid-wrong-issuer-bundle"
copy_generic_bundle "$CERT_DIR" "$wrong_issuer_case"
for file in server.crt server.key client.crt client.key dev-cert.pem dev-key.pem; do
  cp "$other_authority/$file" "$wrong_issuer_case/$file"
done
assert_invalid_existing_bundle_is_preserved "$wrong_issuer_case" wrong-issuer

expired_case="$CERT_DIR/invalid-expired-bundle"
copy_generic_bundle "$CERT_DIR" "$expired_case"
cat >"$expired_case/expired.cnf" <<'EOF'
[req]
distinguished_name = req_distinguished_name
req_extensions = v3_req
prompt = no

[req_distinguished_name]
CN = firemud-grpc

[v3_req]
basicConstraints = critical,CA:false
keyUsage = critical,digitalSignature,keyEncipherment
extendedKeyUsage = serverAuth,clientAuth
subjectAltName = @alt_names

[alt_names]
DNS.1 = localhost
DNS.2 = account-service
DNS.3 = automation-scripting-service
DNS.4 = entity-management-service
DNS.5 = game-design-service
DNS.6 = game-logic-service
DNS.7 = game-session-service
DNS.8 = logging-admin-service
DNS.9 = social-groups-service
DNS.10 = spring-cloud-gateway
DNS.11 = tcp-proxy-service
DNS.12 = world-management-service
IP.1 = 127.0.0.1
EOF
openssl req -new -key "$expired_case/server.key" -config "$expired_case/expired.cnf" \
  -out "$expired_case/expired.csr"
openssl x509 -req -in "$expired_case/expired.csr" -CA "$expired_case/ca.crt" \
  -CAkey "$expired_case/ca.key" -set_serial 424242 -out "$expired_case/expired.crt" \
  -days 0 -sha256 -extensions v3_req -extfile "$expired_case/expired.cnf" >/dev/null
cp "$expired_case/expired.crt" "$expired_case/server.crt"
cp "$expired_case/expired.crt" "$expired_case/client.crt"
cp "$expired_case/expired.crt" "$expired_case/dev-cert.pem"
assert_invalid_existing_bundle_is_preserved "$expired_case" expired

legacy_alias_case="$CERT_DIR/invalid-legacy-alias-bundle"
copy_generic_bundle "$CERT_DIR" "$legacy_alias_case"
printf 'wrong legacy alias\n' >"$legacy_alias_case/dev-cert.pem"
assert_invalid_existing_bundle_is_preserved "$legacy_alias_case" legacy-alias

expected_files=(
  default/ca.crt
  default/client.crt
  default/client.key
  default/dev-ca.pem
  default/dev-cert.pem
  default/dev-key.pem
  default/server.crt
  default/server.key
  account-service/ca.crt
  account-service/client.crt
  account-service/client.key
  account-service/dev-ca.pem
  account-service/dev-cert.pem
  account-service/dev-key.pem
  account-service/server.crt
  account-service/server.key
  account-service/workloads/account-service.crt
  account-service/workloads/account-service.key
  game-session-service/ca.crt
  game-session-service/client.crt
  game-session-service/client.key
  game-session-service/dev-ca.pem
  game-session-service/dev-cert.pem
  game-session-service/dev-key.pem
  game-session-service/server.crt
  game-session-service/server.key
  game-session-service/workloads/game-session-service.crt
  game-session-service/workloads/game-session-service.key
  social-groups-service/ca.crt
  social-groups-service/client.crt
  social-groups-service/client.key
  social-groups-service/dev-ca.pem
  social-groups-service/dev-cert.pem
  social-groups-service/dev-key.pem
  social-groups-service/server.crt
  social-groups-service/server.key
  social-groups-service/workloads/social-groups-service.crt
  social-groups-service/workloads/social-groups-service.key
)
mapfile -t expected_files < <(printf '%s\n' "${expected_files[@]}" | sort)
mapfile -t actual_files < <(cd "$runtime_dir" && find . -type f -printf '%P\n' | sort)
[[ "${actual_files[*]}" == "${expected_files[*]}" ]] || {
  echo "local runtime projection does not match the approved file allowlist" >&2
  printf 'expected: %s\n' "${expected_files[*]}" >&2
  printf 'actual:   %s\n' "${actual_files[*]}" >&2
  exit 1
}

declare -A seen_certificates=()
for workload in account-service game-session-service social-groups-service; do
  assert_workload_certificate "$workload"
  fingerprint="$(openssl x509 -in "$runtime_dir/$workload/workloads/$workload.crt" -noout -fingerprint -sha256 | cut -d= -f2)"
  [[ -z "${seen_certificates[$fingerprint]:-}" ]] || {
    echo "local workloads share a certificate: $workload" >&2
    exit 1
  }
  seen_certificates[$fingerprint]=1
done

expected_account_uri="spiffe://firemud/ns/local/sa/account-service"
account_dns_sans='DNS:account-service,DNS:account-service.local,DNS:account-service.local.svc,DNS:account-service.local.svc.cluster.local'
assert_invalid_workload_uri_is_reissued \
  duplicate-uri "URI:$expected_account_uri,URI:spiffe://firemud/ns/local/sa/other,$account_dns_sans"
assert_invalid_workload_uri_is_reissued \
  prefix-uri "URI:xURI:$expected_account_uri,$account_dns_sans"
assert_invalid_workload_uri_is_reissued \
  suffix-uri "URI:${expected_account_uri}-shadow,$account_dns_sans"

# A malformed or stale local leaf is replaced only after it fails closed
# identity/issuer/key validation; the generic workload generator remains 0600.
printf 'invalid local leaf\n' >"$CERT_DIR/workloads/account-service.crt"
bash "$ROOT_DIR/dev-tools/certs/ensure-dev-certs.sh" "$CERT_DIR"
assert_workload_certificate account-service
[[ ! -e "$runtime_dir/game-session-service/workloads/account-service.key" \
  && ! -e "$runtime_dir/social-groups-service/workloads/account-service.key" \
  && ! -e "$runtime_dir/account-service/workloads/game-session-service.key" \
  && ! -e "$runtime_dir/account-service/workloads/social-groups-service.key" ]] || {
  echo "local runtime projection exposes another workload's private key" >&2
  exit 1
}

# Certificate authority sources and workload outputs must not follow symlinks;
# verify rejected paths leave their target content untouched.
symlink_ensure_dir="$CERT_DIR/symlink-ensure"
mkdir -p "$symlink_ensure_dir/workloads"
for file in ca.crt ca.key client.crt client.key dev-ca.pem dev-cert.pem dev-key.pem server.crt server.key; do
  cp "$CERT_DIR/$file" "$symlink_ensure_dir/$file"
done
printf 'protected CA source\n' >"$symlink_ensure_dir/ca-key-target"
rm "$symlink_ensure_dir/ca.key"
ln -s "$symlink_ensure_dir/ca-key-target" "$symlink_ensure_dir/ca.key"
if bash "$ROOT_DIR/dev-tools/certs/ensure-dev-certs.sh" "$symlink_ensure_dir"; then
  echo "ensure-dev-certs accepted a symlinked CA source" >&2
  exit 1
fi
[[ "$(<"$symlink_ensure_dir/ca-key-target")" == "protected CA source" ]] || {
  echo "ensure-dev-certs modified a symlinked CA source target" >&2
  exit 1
}
rm "$symlink_ensure_dir/ca.key"
cp "$CERT_DIR/ca.key" "$symlink_ensure_dir/ca.key"

printf 'protected workload output\n' >"$symlink_ensure_dir/workload-key-target"
ln -s "$symlink_ensure_dir/workload-key-target" \
  "$symlink_ensure_dir/workloads/account-service.key"
if bash "$ROOT_DIR/dev-tools/certs/ensure-dev-certs.sh" "$symlink_ensure_dir"; then
  echo "ensure-dev-certs accepted a symlinked workload output" >&2
  exit 1
fi
[[ "$(<"$symlink_ensure_dir/workload-key-target")" == "protected workload output" ]] || {
  echo "ensure-dev-certs modified a symlinked workload output target" >&2
  exit 1
}

symlink_generator_dir="$CERT_DIR/symlink-generator"
mkdir -p "$symlink_generator_dir"
printf 'protected generator key\n' >"$symlink_generator_dir/key-target"
ln -s "$symlink_generator_dir/key-target" "$symlink_generator_dir/output.key"
if bash "$ROOT_DIR/dev-tools/certs/generate-dev-certs.sh" --workload \
  "$CERT_DIR/ca.crt" "$CERT_DIR/ca.key" \
  "$symlink_generator_dir/output.crt" "$symlink_generator_dir/output.key" \
  local account-service; then
  echo "generate-dev-certs accepted a symlinked workload output" >&2
  exit 1
fi
[[ "$(<"$symlink_generator_dir/key-target")" == "protected generator key" \
  && ! -e "$symlink_generator_dir/output.crt" ]] || {
  echo "generate-dev-certs modified a symlinked output target" >&2
  exit 1
}

for compose_file in docker-compose.override.yml docker-compose.local-images.override.yml; do
  compose_path="$ROOT_DIR/docker/$compose_file"
  rg -Fq 'FIREMUD_GRPC_PLAINTEXT: "false"' "$compose_path"
  rg -Fq 'GRPC_SERVER_TLS_ENABLED: "true"' "$compose_path"
  rg -Fq '../dev-tools/certs/local-runtime/default:/app/certs:ro' "$compose_path"
  rg -Fq 'GATEWAY_WS_URL: ws://gateway:8080/ws/game' "$compose_path"
  if rg -Fq 'FIREMUD_GRPC_PLAINTEXT: "true"' "$compose_path" \
    || rg -Fq 'GRPC_SERVER_TLS_ENABLED: "false"' "$compose_path" \
    || rg -Fq '../dev-tools/certs:/app/certs' "$compose_path"; then
    echo "$compose_file contains an insecure gRPC override or broad certificate mount" >&2
    exit 1
  fi
  [[ "$(rg -c 'volumes: \*local-runtime-volumes' "$compose_path")" == "8" ]] || {
    echo "$compose_file does not project runtime-only certs for every Java service" >&2
    exit 1
  }
  for workload in account-service game-session-service social-groups-service; do
    rg -Fq "../dev-tools/certs/local-runtime/$workload:/app/certs:ro" "$compose_path"
    rg -Fq "FIREMUD_GRPC_CERT_CHAIN_PATH: /app/certs/workloads/$workload.crt" "$compose_path"
    rg -Fq "FIREMUD_GRPC_PRIVATE_KEY_PATH: /app/certs/workloads/$workload.key" "$compose_path"
    rg -Fq 'FIREMUD_GRPC_CA_CERT_PATH: /app/certs/ca.crt' "$compose_path"
  done
  rg -Fq 'FIREMUD_GRPC_WORKLOAD_NAMESPACE: local' "$compose_path"
done

cleanup_output="$(bash "$ROOT_DIR/dev-tools/certs/clean-dev-certs.sh" "$CERT_DIR" 2>&1)"
[[ ! -e "$CERT_DIR/workloads" && ! -e "$CERT_DIR/local-runtime" && ! -e "$CERT_DIR/ca.key" ]] || {
  echo "certificate cleanup left generated workload or runtime material behind" >&2
  printf '%s\n' "$cleanup_output" >&2
  exit 1
}

# Unknown files and symlinks are preserved and reported; only known generated
# leaf/projection names are deleted, and their directories remain non-empty.
mkdir -p "$CERT_DIR/workloads" "$CERT_DIR/local-runtime/default"
printf 'generated\n' >"$CERT_DIR/workloads/account-service.key"
printf 'keep\n' >"$CERT_DIR/workloads/keep.txt"
printf 'generated\n' >"$CERT_DIR/local-runtime/default/client.key"
printf 'keep\n' >"$CERT_DIR/local-runtime/default/keep.txt"
printf 'target\n' >"$CERT_DIR/retained-target"
ln -s "$CERT_DIR/retained-target" "$CERT_DIR/local-runtime/default/server.key"
cleanup_output="$(bash "$ROOT_DIR/dev-tools/certs/clean-dev-certs.sh" "$CERT_DIR" 2>&1)"
[[ ! -e "$CERT_DIR/workloads/account-service.key" \
  && ! -e "$CERT_DIR/local-runtime/default/client.key" \
  && -f "$CERT_DIR/workloads/keep.txt" \
  && -f "$CERT_DIR/local-runtime/default/keep.txt" \
  && -L "$CERT_DIR/local-runtime/default/server.key" \
  && "$(<"$CERT_DIR/retained-target")" == "target" ]] || {
  echo "certificate cleanup did not preserve unexpected files and symlinks safely" >&2
  exit 1
}
[[ "$cleanup_output" == *"$CERT_DIR/workloads/keep.txt"* \
  && "$cleanup_output" == *"$CERT_DIR/local-runtime/default/server.key"* ]] || {
  echo "certificate cleanup did not report preserved entries" >&2
  printf '%s\n' "$cleanup_output" >&2
  exit 1
}

symlink_case="$CERT_DIR/symlink-case"
mkdir -p "$symlink_case/target"
printf 'protected\n' >"$symlink_case/target/social-groups-service.key"
ln -s "$symlink_case/target" "$symlink_case/workloads"
cleanup_output="$(bash "$ROOT_DIR/dev-tools/certs/clean-dev-certs.sh" "$symlink_case" 2>&1)"
[[ -L "$symlink_case/workloads" \
  && "$(<"$symlink_case/target/social-groups-service.key")" == "protected" \
  && "$cleanup_output" == *"$symlink_case/workloads (symlink directory)"* ]] || {
  echo "certificate cleanup followed or failed to report a symlinked leaf directory" >&2
  printf '%s\n' "$cleanup_output" >&2
  exit 1
}

ancestor_symlink_case="$CERT_DIR/ancestor-symlink-case"
ancestor_real_parent="$ancestor_symlink_case/real-parent"
ancestor_real_cert_dir="$ancestor_real_parent/cert-dir"
ancestor_alias="$ancestor_symlink_case/alias"
ancestor_alias_cert_dir="$ancestor_alias/cert-dir"
mkdir -p "$ancestor_real_cert_dir"
printf 'protected generated-name sentinel\n' >"$ancestor_real_cert_dir/ca.key"
chmod 640 "$ancestor_real_cert_dir/ca.key"
printf 'protected workload sentinel\n' >"$ancestor_real_cert_dir/workloads.key"
chmod 644 "$ancestor_real_cert_dir/workloads.key"
ln -s "$ancestor_real_parent" "$ancestor_alias"
if cleanup_output="$(bash "$ROOT_DIR/dev-tools/certs/clean-dev-certs.sh" "$ancestor_alias_cert_dir" 2>&1)"; then
  echo "certificate cleanup accepted a certificate directory beneath a symlinked ancestor" >&2
  exit 1
fi
[[ "$cleanup_output" == *"Refusing symlinked certificate directory; preserving it: $ancestor_alias_cert_dir"* \
  && "$(<"$ancestor_real_cert_dir/ca.key")" == 'protected generated-name sentinel' \
  && "$(<"$ancestor_real_cert_dir/workloads.key")" == 'protected workload sentinel' ]] || {
  echo "certificate cleanup followed an ancestor symlink or changed protected entries" >&2
  printf '%s\n' "$cleanup_output" >&2
  exit 1
}
assert_mode 640 "$ancestor_real_cert_dir/ca.key"
assert_mode 644 "$ancestor_real_cert_dir/workloads.key"

direct_symlink_alias="$ancestor_symlink_case/direct-alias"
ln -s "$ancestor_real_cert_dir" "$direct_symlink_alias"
if cleanup_output="$(bash "$ROOT_DIR/dev-tools/certs/clean-dev-certs.sh" "$direct_symlink_alias/" 2>&1)"; then
  echo "certificate cleanup accepted a symlink directory with a trailing slash" >&2
  exit 1
fi
[[ "$cleanup_output" == *"Refusing symlinked certificate directory; preserving it: $direct_symlink_alias/"* \
  && "$(<"$ancestor_real_cert_dir/ca.key")" == 'protected generated-name sentinel' \
  && "$(<"$ancestor_real_cert_dir/workloads.key")" == 'protected workload sentinel' ]] || {
  echo "certificate cleanup followed a trailing-slash symlink or changed protected entries" >&2
  printf '%s\n' "$cleanup_output" >&2
  exit 1
}
assert_mode 640 "$ancestor_real_cert_dir/ca.key"
assert_mode 644 "$ancestor_real_cert_dir/workloads.key"

echo "Local Compose workload identity contract passed."
