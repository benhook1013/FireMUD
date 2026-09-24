#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd -- "$SCRIPT_DIR/../.." && pwd)"
HELPER="$REPO_ROOT/dev-tools/hosted/shared/ensure-grpc-tls-secret.sh"
fixture_dir="$(mktemp -d)"
trap 'rm -rf "$fixture_dir"' EXIT

for required_command in openssl base64 awk grep; do
  command -v "$required_command" >/dev/null 2>&1 || {
    echo "$required_command is required for the gRPC TLS Secret contract." >&2
    exit 1
  }
done

workloads=(
  game-design-service
  world-management-service
  entity-management-service
  game-logic-service
  automation-scripting-service
)
data_dir="$fixture_dir/data"
mock_bin="$fixture_dir/bin"
mkdir -p "$data_dir" "$mock_bin"

# Build one shared issuer and five distinct workload identities. The helper
# validates the real X.509 extensions, SANs, key pairs, and issuer chains.
openssl ecparam -genkey -name prime256v1 -noout -out "$fixture_dir/issuer.key"
openssl req -x509 -new -key "$fixture_dir/issuer.key" -sha256 -days 30 \
  -subj /CN=FireMUD-Contract-Issuer \
  -addext 'basicConstraints=critical,CA:TRUE' \
  -addext 'keyUsage=critical,keyCertSign,cRLSign' \
  -out "$data_dir/issuer.crt"
openssl ecparam -genkey -name prime256v1 -noout -out "$fixture_dir/shared.key"
openssl req -new -key "$fixture_dir/shared.key" -subj /CN=FireMUD-Shared-Client \
  -out "$fixture_dir/shared.csr"
openssl x509 -req -in "$fixture_dir/shared.csr" -CA "$data_dir/issuer.crt" \
  -CAkey "$fixture_dir/issuer.key" -CAcreateserial -days 30 -sha256 \
  -out "$data_dir/shared.crt"
cp "$data_dir/issuer.crt" "$data_dir/shared-ca.crt"
cp "$fixture_dir/shared.key" "$data_dir/shared.key"

for workload in "${workloads[@]}"; do
  openssl ecparam -genkey -name prime256v1 -noout -out "$data_dir/$workload.key"
  openssl req -new -key "$data_dir/$workload.key" -subj "/CN=$workload" \
    -out "$fixture_dir/$workload.csr"
  cat >"$fixture_dir/$workload.ext" <<EOF
[leaf]
basicConstraints=critical,CA:FALSE
keyUsage=critical,digitalSignature,keyEncipherment
extendedKeyUsage=serverAuth,clientAuth
subjectAltName=URI:spiffe://firemud/ns/dev/sa/$workload,DNS:$workload,DNS:$workload.dev,DNS:$workload.dev.svc,DNS:$workload.dev.svc.cluster.local
EOF
  openssl x509 -req -in "$fixture_dir/$workload.csr" \
    -CA "$data_dir/issuer.crt" -CAkey "$fixture_dir/issuer.key" \
    -CAcreateserial -days 30 -sha256 -extfile "$fixture_dir/$workload.ext" \
    -extensions leaf -out "$data_dir/$workload.crt"
  cp "$data_dir/issuer.crt" "$data_dir/$workload-ca.crt"
done

cat >"$mock_bin/kubectl" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
[[ "$1" == -n ]] || { echo "unexpected kubectl arguments: $*" >&2; exit 2; }
namespace="$2"
shift 2
operation="$1"
shift
case "$operation" in
  get)
    [[ "$1" == secret ]] || { echo "unexpected kubectl get: $*" >&2; exit 2; }
    secret="$2"
    shift 2
    if [[ "${1:-}" == --ignore-not-found ]]; then
      printf 'lookup %s/%s\n' "$namespace" "$secret" >>"$KUBECTL_LOG"
      printf 'secret/%s\n' "$secret"
      exit 0
    fi
    [[ "${1:-}" == -o && "${2:-}" == jsonpath=* ]] || {
      echo "unexpected kubectl get options: $*" >&2
      exit 2
    }
    expression="$2"
    key="${expression#jsonpath=}"
    key="${key#\{.data.}"
    key="${key%\}}"
    key="${key//\\./.}"
    printf 'read %s/%s %s\n' "$namespace" "$secret" "$key" >>"$KUBECTL_LOG"
    if [[ "$PROJECTION_MODE" == stale-partial && "$secret" == firemud-grpc-game-design-service ]]; then
      case "$key" in
        tls.crt)
          count_file="$STATE_DIR/cert-reads"
          count=0
          [[ ! -f "$count_file" ]] || count="$(<"$count_file")"
          count=$((count + 1))
          printf '%s\n' "$count" >"$count_file"
          if ((count > 1)); then exit 0; fi
          ;;
        ca.crt)
          count_file="$STATE_DIR/ca-reads"
          count=0
          [[ ! -f "$count_file" ]] || count="$(<"$count_file")"
          count=$((count + 1))
          printf '%s\n' "$count" >"$count_file"
          if ((count == 1)); then exit 0; fi
          ;;
      esac
    fi
    file=''
    if [[ "$secret" == firemud-grpc-tls ]]; then
      case "$key" in
        ca.crt) file="$DATA_DIR/shared-ca.crt" ;;
        client.crt) file="$DATA_DIR/shared.crt" ;;
        client.key) file="$DATA_DIR/shared.key" ;;
      esac
    elif [[ "$secret" == firemud-grpc-* ]]; then
      workload="${secret#firemud-grpc-}"
      case "$key" in
        tls.crt) file="$DATA_DIR/$workload.crt" ;;
        tls.key) file="$DATA_DIR/$workload.key" ;;
        ca.crt) file="$DATA_DIR/$workload-ca.crt" ;;
      esac
    fi
    [[ -n "$file" && -s "$file" ]] || exit 0
    base64 -w0 "$file"
    ;;
  delete)
    [[ "$1" == secret ]] || { echo "unexpected kubectl delete: $*" >&2; exit 2; }
    printf 'delete %s/%s\n' "$namespace" "$2" >>"$KUBECTL_LOG"
    ;;
  *)
    echo "unexpected kubectl operation: $operation" >&2
    exit 2
    ;;
esac
EOF
cat >"$mock_bin/sleep" <<'EOF'
#!/usr/bin/env bash
exit 0
EOF
chmod +x "$mock_bin/kubectl" "$mock_bin/sleep"

run_helper() {
  local mode="$1"
  local log="$2"
  local state="$3"
  mkdir -p "$state"
  PATH="$mock_bin:$PATH" \
    DATA_DIR="$data_dir" \
    KUBECTL_LOG="$log" \
    PROJECTION_MODE="$mode" \
    STATE_DIR="$state" \
    CERTIFICATE_WAIT_TIMEOUT_SECONDS=1 \
    bash "$HELPER" dev
}

success_log="$fixture_dir/success.log"
run_helper complete "$success_log" "$fixture_dir/success-state" \
  >"$fixture_dir/success.out"
grep -Fq '5 distinct publication leaves' "$fixture_dir/success.out" || {
  echo "the helper did not accept five valid cert-manager projections" >&2
  exit 1
}
if grep -Eq '^read dev/firemud-grpc-ca |^create .*firemud-grpc-ca|ca\.key' "$success_log"; then
  echo "the helper read or created a runtime-local CA key/Secret" >&2
  exit 1
fi
last_projection_line="$(grep -n '^read dev/firemud-grpc-automation-scripting-service ca.crt$' "$success_log" | tail -n 1 | cut -d: -f1)"
legacy_delete_line="$(grep -n '^delete dev/firemud-grpc-ca$' "$success_log" | cut -d: -f1)"
[[ -n "$last_projection_line" && -n "$legacy_delete_line" && "$legacy_delete_line" -gt "$last_projection_line" ]] || {
  echo "the legacy runtime CA Secret was not deleted after all projections validated" >&2
  exit 1
}

# The first polling pass sees tls.crt and tls.key but no ca.crt. The next pass
# sees tls.key and ca.crt but no tls.crt. The earlier certificate file must not
# make this incomplete projection look complete.
incomplete_log="$fixture_dir/incomplete.log"
if run_helper stale-partial "$incomplete_log" "$fixture_dir/incomplete-state" \
  >"$fixture_dir/incomplete.out" 2>"$fixture_dir/incomplete.err"; then
  echo "the helper accepted an incomplete projection using stale partial files" >&2
  exit 1
fi
grep -Fq 'did not become key-complete' "$fixture_dir/incomplete.err" || {
  echo "the helper failed for an unexpected reason on an incomplete projection" >&2
  cat "$fixture_dir/incomplete.err" >&2
  exit 1
}
if grep -q '^delete ' "$incomplete_log"; then
  echo "the helper deleted legacy Secrets before every projection validated" >&2
  exit 1
fi

printf 'ensure-grpc-tls-secret runtime contract passed\n'
