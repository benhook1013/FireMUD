#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
CERT_DIR="$(mktemp -d)"
trap 'rm -rf "$CERT_DIR"' EXIT

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

assert_workload_certificate() {
  local workload="$1"
  local certificate="$CERT_DIR/local-runtime/$workload/workloads/$workload.crt"
  local private_key="$CERT_DIR/local-runtime/$workload/workloads/$workload.key"
  local subject_alt_names extended_key_usage expected_uri certificate_public_key private_key_public_key
  expected_uri="spiffe://firemud/ns/local/sa/$workload"

  openssl verify -purpose sslclient -CAfile "$CERT_DIR/local-runtime/$workload/ca.crt" "$certificate" >/dev/null
  openssl verify -purpose sslserver -CAfile "$CERT_DIR/local-runtime/$workload/ca.crt" "$certificate" >/dev/null
  subject_alt_names="$(openssl x509 -in "$certificate" -noout -ext subjectAltName)"
  [[ "$subject_alt_names" == *"URI:$expected_uri"* \
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
  assert_mode 600 "$CERT_DIR/workloads/$workload.key"
  assert_mode 755 "$runtime_dir/$workload/workloads"
  assert_mode 644 "$runtime_dir/$workload/workloads/$workload.key"
done

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

echo "Local Compose workload identity contract passed."
