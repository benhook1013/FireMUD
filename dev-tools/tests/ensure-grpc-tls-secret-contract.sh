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
openssl ecparam -genkey -name prime256v1 -noout -out "$fixture_dir/shared-issuer.key"
openssl req -x509 -new -key "$fixture_dir/shared-issuer.key" -sha256 -days 30 \
  -subj /CN=FireMUD-Shared-Contract-Issuer \
  -addext 'basicConstraints=critical,CA:TRUE' \
  -addext 'keyUsage=critical,keyCertSign,cRLSign' \
  -out "$data_dir/shared-issuer.crt"
openssl ecparam -genkey -name prime256v1 -noout -out "$fixture_dir/publication-issuer.key"
openssl req -x509 -new -key "$fixture_dir/publication-issuer.key" -sha256 -days 30 \
  -subj /CN=FireMUD-Publication-Contract-Issuer \
  -addext 'basicConstraints=critical,CA:TRUE' \
  -addext 'keyUsage=critical,keyCertSign,cRLSign' \
  -out "$data_dir/publication-issuer.crt"
openssl ecparam -genkey -name prime256v1 -noout -out "$fixture_dir/shared.key"
openssl req -new -key "$fixture_dir/shared.key" -subj /CN=FireMUD-Shared-Client \
  -out "$fixture_dir/shared.csr"
openssl x509 -req -in "$fixture_dir/shared.csr" -CA "$data_dir/shared-issuer.crt" \
  -CAkey "$fixture_dir/shared-issuer.key" -CAcreateserial -days 30 -sha256 \
  -out "$data_dir/shared.crt"
cp "$data_dir/shared-issuer.crt" "$data_dir/shared-ca.crt"
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
    -CA "$data_dir/publication-issuer.crt" -CAkey "$fixture_dir/publication-issuer.key" \
    -CAcreateserial -days 30 -sha256 -extfile "$fixture_dir/$workload.ext" \
    -extensions leaf -out "$data_dir/$workload.crt"
  cp "$data_dir/publication-issuer.crt" "$data_dir/$workload-ca.crt"
done

cat >"$mock_bin/kubectl" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
if [[ "$1" == -n ]]; then
  namespace="$2"
  shift 2
else
  namespace=dev
fi
operation="$1"
shift
case "$operation" in
  create)
    [[ "$1" == secret && "$2" == generic ]] || { echo "unexpected kubectl create: $*" >&2; exit 2; }
    secret="$3"
    shift 3
    printf 'create %s/%s\n' "$namespace" "$secret" >>"$KUBECTL_LOG"
    printf 'apiVersion: v1\nkind: Secret\nmetadata:\n  name: %s\ndata:\n' "$secret"
    while (($#)); do
      case "$1" in
        --from-file=*)
          assignment="${1#--from-file=}"
          key="${assignment%%=*}"
          file="${assignment#*=}"
          [[ -s "$file" ]] || { echo "missing Secret input file: $file" >&2; exit 2; }
          printf '  %s: %s\n' "$key" "$(base64 -w0 "$file")"
          ;;
        --dry-run=client|-o|yaml)
          ;;
        *) echo "unexpected kubectl create option: $1" >&2; exit 2 ;;
      esac
      shift
    done
    ;;
  apply)
    [[ "$1" == -f && "$2" == - ]] || { echo "unexpected kubectl apply: $*" >&2; exit 2; }
    printf 'apply %s/%s\n' "$namespace" "${APPLIED_SECRET_NAME:-firemud-grpc-tls}" >>"$KUBECTL_LOG"
    cat >"$APPLIED_SECRET"
    ;;
  get)
    [[ "$1" == secret ]] || { echo "unexpected kubectl get: $*" >&2; exit 2; }
    secret="$2"
    shift 2
    if [[ "${1:-}" == --ignore-not-found ]]; then
      shift
    fi
    if [[ "${1:-}" == -o && "${2:-}" == name ]]; then
      printf 'lookup %s/%s\n' "$namespace" "$secret" >>"$KUBECTL_LOG"
      case "${LOOKUP_MODE:-present}" in
        warning-on-absent)
          echo "Warning: secrets \"$secret\" not found" >&2
          exit 0
          ;;
        failure)
          echo "mock kubectl lookup failure for $secret" >&2
          exit 7
          ;;
        present)
          printf 'secret/%s\n' "$secret"
          ;;
        *)
          echo "unexpected lookup mode: $LOOKUP_MODE" >&2
          exit 2
          ;;
      esac
      exit 0
    fi
    [[ "${1:-}" == -o && "${2:-}" == jsonpath=* ]] || {
      echo "unexpected kubectl get options: $*" >&2
      exit 2
    }
    expression="${2#jsonpath=}"
    if [[ "$expression" != *'{.metadata.name}'* || "$expression" != *'{.data.'* ]]; then
      echo "unexpected Secret snapshot expression: $expression" >&2
      exit 2
    fi
    if [[ "${LOOKUP_MODE:-present}" != present && "$secret" == firemud-grpc-tls ]]; then
      exit 0
    fi
    workload=''
    fields=(ca.crt client.crt client.key)
    if [[ "$secret" == firemud-grpc-tls ]]; then
      :
    elif [[ "$secret" == firemud-grpc-* ]]; then
      workload="${secret#firemud-grpc-}"
      fields=(tls.crt tls.key ca.crt)
    else
      echo "unexpected Secret snapshot: $secret" >&2
      exit 2
    fi
    for key in "${fields[@]}"; do
      escaped_key="${key//./\\.}"
      [[ "$expression" == *"{.data.${escaped_key}}"* ]] || {
        echo "Secret snapshot omitted required field $key: $expression" >&2
        exit 2
      }
    done
    field_list="$(IFS=,; printf '%s' "${fields[*]}")"
    printf 'snapshot %s/%s fields=%s\n' "$namespace" "$secret" "$field_list" >>"$KUBECTL_LOG"
    count_file="$STATE_DIR/${secret//\//_}-snapshots"
    count=0
    [[ ! -f "$count_file" ]] || count="$(<"$count_file")"
    count=$((count + 1))
    printf '%s\n' "$count" >"$count_file"
    omitted_field=''
    if [[ "$PROJECTION_MODE" == stale-partial && "$secret" == firemud-grpc-game-design-service ]]; then
      if ((count % 2 == 1)); then omitted_field=ca.crt; else omitted_field=tls.crt; fi
    elif [[ "$PROJECTION_MODE" == delayed-each-workload && -n "$workload" && "$count" == 1 ]]; then
      omitted_field=tls.crt
    fi
    response="$secret|"
    for index in "${!fields[@]}"; do
      key="${fields[$index]}"
      file=''
      if [[ "$secret" == firemud-grpc-tls ]]; then
        case "$key" in
          ca.crt) file="$DATA_DIR/shared-ca.crt" ;;
          client.crt) file="$DATA_DIR/shared.crt" ;;
          client.key) file="$DATA_DIR/shared.key" ;;
        esac
      else
        case "$key" in
          tls.crt) file="$DATA_DIR/$workload.crt" ;;
          tls.key) file="$DATA_DIR/$workload.key" ;;
          ca.crt) file="$DATA_DIR/$workload-ca.crt" ;;
        esac
      fi
      if [[ "$key" == "$omitted_field" ]]; then
        encoded=''
      else
        [[ -n "$file" && -s "$file" ]] || { echo "missing fixture field $key" >&2; exit 2; }
        encoded="$(base64 -w0 "$file")"
      fi
      response+="$encoded"
      if ((index < ${#fields[@]} - 1)); then response+='|'; fi
    done
    printf '%s' "$response"
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

applied_secret="$fixture_dir/applied-secret.yaml"

run_helper() {
  local mode="$1"
  local log="$2"
  local state="$3"
  local bash_env_file="${4:-}"
  local timeout_seconds="${5:-1}"
  local lookup_mode="${6:-present}"
  local cert_dir_parent="${7:-}"
  local applied_secret_path="${8:-$applied_secret}"
  mkdir -p "$state"
  PATH="$mock_bin:$PATH" \
    BASH_ENV="$bash_env_file" \
    LOOKUP_MODE="$lookup_mode" \
    DATA_DIR="$data_dir" \
    KUBECTL_LOG="$log" \
    APPLIED_SECRET="$applied_secret_path" \
    PREVIEW_GRPC_TLS_CERT_DIR="$cert_dir_parent" \
    PROJECTION_MODE="$mode" \
    STATE_DIR="$state" \
    CERTIFICATE_WAIT_TIMEOUT_SECONDS="$timeout_seconds" \
    bash "$HELPER" dev
}

cat >"$fixture_dir/advance-shell-clock.sh" <<'EOF'
sleep() {
  SECONDS=$((SECONDS + 1))
}
EOF

success_log="$fixture_dir/success.log"
if ! run_helper complete "$success_log" "$fixture_dir/success-state" "" 30 \
  >"$fixture_dir/success.out" 2>"$fixture_dir/success.err"; then
  cat "$fixture_dir/success.out" "$fixture_dir/success.err" >&2
  exit 1
fi
grep -Fq '5 distinct publication leaves' "$fixture_dir/success.out" || {
  echo "the helper did not accept five valid cert-manager projections" >&2
  exit 1
}
if grep -Eq '^snapshot dev/firemud-grpc-ca |^create .*firemud-grpc-ca|ca\.key' "$success_log"; then
  echo "the helper read or created a runtime-local CA key/Secret" >&2
  exit 1
fi
for secret in firemud-grpc-tls "${workloads[@]/#/firemud-grpc-}"; do
  if [[ "$secret" == firemud-grpc-tls ]]; then
    expected_fields='ca.crt,client.crt,client.key'
  else
    expected_fields='tls.crt,tls.key,ca.crt'
  fi
  snapshot_count="$(grep -Fc "snapshot dev/$secret fields=$expected_fields" "$success_log" || true)"
  [[ "$snapshot_count" == 1 ]] || {
    echo "expected exactly one complete Secret snapshot for dev/$secret, got $snapshot_count" >&2
    exit 1
  }
done
last_projection_line="$(grep -n '^snapshot dev/firemud-grpc-automation-scripting-service fields=tls.crt,tls.key,ca.crt$' "$success_log" | tail -n 1 | cut -d: -f1)"
reapply_line="$(grep -n '^apply dev/firemud-grpc-tls$' "$success_log" | cut -d: -f1)"
legacy_delete_line="$(grep -n '^delete dev/firemud-grpc-ca$' "$success_log" | cut -d: -f1)"
[[ -n "$last_projection_line" && -n "$reapply_line" && -n "$legacy_delete_line" && \
  "$reapply_line" -gt "$last_projection_line" && "$legacy_delete_line" -gt "$reapply_line" ]] || {
  echo "the shared Secret was not reapplied before deleting the legacy runtime CA Secret" >&2
  exit 1
}

# A successful Kubernetes warning for an absent Secret belongs on stderr and
# must not make the lookup result appear present.
warning_lookup_log="$fixture_dir/warning-lookup.log"
if ! run_helper complete "$warning_lookup_log" "$fixture_dir/warning-lookup-state" \
  "" 30 warning-on-absent "" "$fixture_dir/warning-lookup-applied-secret.yaml" \
  >"$fixture_dir/warning-lookup.out" 2>"$fixture_dir/warning-lookup.err"; then
  cat "$fixture_dir/warning-lookup.out" "$fixture_dir/warning-lookup.err" >&2
  exit 1
fi
grep -Fq '5 distinct publication leaves' "$fixture_dir/warning-lookup.out" || {
  echo "a warning on an absent Secret was treated as an existing Secret" >&2
  exit 1
}

# A failed lookup must retain its diagnostic and remove the temporary stderr
# capture along with the helper's certificate workspace.
failed_lookup_cert_parent="$fixture_dir/failed-lookup-certs"
mkdir -p "$failed_lookup_cert_parent"
if run_helper complete "$fixture_dir/failed-lookup.log" "$fixture_dir/failed-lookup-state" \
  "" 30 failure "$failed_lookup_cert_parent" >"$fixture_dir/failed-lookup.out" \
  2>"$fixture_dir/failed-lookup.err"; then
  echo "the helper continued after a failed Kubernetes Secret lookup" >&2
  exit 1
fi
grep -Fq 'mock kubectl lookup failure for firemud-grpc-game-design-service' \
  "$fixture_dir/failed-lookup.err" || {
  echo "the helper did not report the Kubernetes Secret lookup error" >&2
  cat "$fixture_dir/failed-lookup.err" >&2
  exit 1
}
shopt -s nullglob
failed_lookup_captures=("$failed_lookup_cert_parent"/*)
shopt -u nullglob
[[ "${#failed_lookup_captures[@]}" -eq 0 ]] || {
  echo "the helper left temporary certificate or stderr capture files after lookup failure" >&2
  exit 1
}

applied_shared_ca="$fixture_dir/applied-shared-ca.crt"
applied_client_cert="$fixture_dir/applied-client.crt"
applied_client_key="$fixture_dir/applied-client.key"
for key in ca.crt client.crt client.key; do
  encoded="$(sed -n "s/^  ${key//./\\.}: //p" "$applied_secret")"
  [[ -n "$encoded" ]] || { echo "reapplied Secret is missing $key" >&2; exit 1; }
  case "$key" in
    ca.crt) output="$applied_shared_ca" ;;
    client.crt) output="$applied_client_cert" ;;
    client.key) output="$applied_client_key" ;;
  esac
  printf '%s' "$encoded" | base64 --decode >"$output"
done
openssl verify -CAfile "$applied_shared_ca" "$data_dir/shared.crt" >/dev/null || {
  echo "reapplied shared CA bundle no longer trusts the existing shared client" >&2
  exit 1
}
openssl verify -CAfile "$applied_shared_ca" "$data_dir/game-design-service.crt" >/dev/null || {
  echo "reapplied shared CA bundle does not trust the publication CA" >&2
  exit 1
}
cmp -s "$data_dir/shared.crt" "$applied_client_cert" || {
  echo "reapplying the shared Secret changed its existing client certificate" >&2
  exit 1
}
cmp -s "$data_dir/shared.key" "$applied_client_key" || {
  echo "reapplying the shared Secret changed its existing client key" >&2
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

# Each publication projection becomes ready on its second read, one simulated
# second later. The helper must apply one timeout to all five workloads rather
# than resetting a fresh timeout for every workload.
aggregate_timeout_log="$fixture_dir/aggregate-timeout.log"
if run_helper delayed-each-workload "$aggregate_timeout_log" \
  "$fixture_dir/aggregate-timeout-state" "$fixture_dir/advance-shell-clock.sh" 5 \
  >"$fixture_dir/aggregate-timeout.out" 2>"$fixture_dir/aggregate-timeout.err"; then
  echo "the helper reset its certificate wait timeout for each publication workload" >&2
  exit 1
fi
grep -Fq 'aggregate 5s window' "$fixture_dir/aggregate-timeout.err" || {
  echo "the helper failed for an unexpected reason on aggregate certificate timeout" >&2
  cat "$fixture_dir/aggregate-timeout.err" >&2
  exit 1
}
if grep -q '^delete ' "$aggregate_timeout_log"; then
  echo "the helper deleted legacy Secrets before all projections met the aggregate deadline" >&2
  exit 1
fi

printf 'ensure-grpc-tls-secret runtime contract passed\n'
