#!/usr/bin/env bash
set -euo pipefail

grpc_tls_operation='complete'
if [[ $# -eq 2 && "$1" == --shared-only ]]; then
  grpc_tls_operation='shared-only'
  namespace="$2"
elif [[ $# -eq 1 ]]; then
  namespace="$1"
else
  echo "usage: $0 [--shared-only] <dev|pr-N-namespace>" >&2
  exit 1
fi

umask 077
[[ "$namespace" == dev || "$namespace" =~ ^pr-[1-9][0-9]{0,50}$ ]] || {
  echo "runtime namespace must be dev or canonical pr-N: $namespace" >&2
  exit 1
}

for required_command in kubectl openssl base64; do
  command -v "$required_command" >/dev/null 2>&1 || {
    echo "missing required command: $required_command" >&2
    exit 1
  }
done

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
legacy_generator="$script_dir/../../certs/generate-dev-certs.sh"
[[ -x "$legacy_generator" ]] || {
  echo "missing development certificate generator: $legacy_generator" >&2
  exit 1
}

shared_secret="${PREVIEW_GRPC_TLS_SECRET_NAME:-firemud-grpc-tls}"
provided_cert_dir="${PREVIEW_GRPC_TLS_CERT_DIR:-}"
if [[ -z "$provided_cert_dir" ]]; then
  cert_dir="$(mktemp -d)"
else
  mkdir -p "$provided_cert_dir"
  cert_dir="$(mktemp -d "${provided_cert_dir%/}/firemud-grpc-tls.XXXXXXXX")"
fi
trap 'rm -rf "$cert_dir"' EXIT

workloads=(
  game-design-service
  world-management-service
  entity-management-service
  game-logic-service
  automation-scripting-service
)

secret_exists() {
  local secret_name="$1"
  local lookup_result

  if ! lookup_result="$(kubectl -n "$namespace" get secret "$secret_name" --ignore-not-found -o name 2>&1)"; then
    echo "failed to look up Kubernetes Secret ${namespace}/${secret_name}: ${lookup_result}" >&2
    exit 1
  fi
  [[ -n "$lookup_result" ]]
}

read_secret_snapshot() {
  local secret_name="$1"
  shift
  local jsonpath='{.metadata.name}'
  local key output escaped_key
  local response actual_name
  local -a keys=() outputs=() snapshot_fields=()

  while (($#)); do
    key="$1"
    output="$2"
    shift 2
    keys+=("$key")
    outputs+=("$output")
    escaped_key="${key//./\\.}"
    jsonpath+="{\"|\"}{.data.${escaped_key}}"
  done

  if ! response="$(kubectl -n "$namespace" get secret "$secret_name" --ignore-not-found -o "jsonpath=${jsonpath}")"; then
    echo "failed to fetch Kubernetes Secret snapshot ${namespace}/${secret_name}: ${response}" >&2
    return 3
  fi
  [[ -n "$response" ]] || return 1
  IFS='|' read -r -a snapshot_fields <<<"$response"
  actual_name="${snapshot_fields[0]:-}"
  [[ "$actual_name" == "$secret_name" && "${#snapshot_fields[@]}" -eq "$((${#keys[@]} + 1))" ]] || return 2

  for output in "${outputs[@]}"; do
    : >"$output"
  done
  local index encoded
  for index in "${!keys[@]}"; do
    encoded="${snapshot_fields[$((index + 1))]}"
    [[ -n "$encoded" ]] || {
      for output in "${outputs[@]}"; do : >"$output"; done
      return 2
    }
    if ! printf '%s' "$encoded" | base64 --decode >"${outputs[$index]}" ||
      [[ ! -s "${outputs[$index]}" ]]; then
      for output in "${outputs[@]}"; do : >"$output"; done
      return 2
    fi
  done
  return 0
}

apply_secret() {
  local secret_name="$1"
  shift
  kubectl -n "$namespace" create secret generic "$secret_name" "$@" \
    --dry-run=client -o yaml | kubectl apply -f - >/dev/null
}

assert_key_matches_certificate() {
  local certificate="$1"
  local key="$2"
  local certificate_public_key
  local key_public_key

  certificate_public_key="$(openssl x509 -in "$certificate" -pubkey -noout | openssl sha256)"
  key_public_key="$(openssl pkey -in "$key" -pubout | openssl sha256)"
  [[ "$certificate_public_key" == "$key_public_key" ]] || {
    echo "certificate and private key do not match: $certificate" >&2
    return 1
  }
}

ca_bundle_contains() {
  local bundle="$1"
  local certificate="$2"
  openssl verify -CAfile "$bundle" "$certificate" >/dev/null 2>&1
}

assert_certificate_unexpired() {
  local certificate="$1"
  local description="$2"
  local rotation_secrets="$3"

  if ! openssl x509 -in "$certificate" -noout >/dev/null 2>&1; then
    return 0
  fi
  if ! openssl x509 -in "$certificate" -checkend 0 -noout >/dev/null 2>&1; then
    echo "$description is expired; for safe rotation, an operator must delete these retained Secrets before rerunning:" \
      "$rotation_secrets" >&2
    return 1
  fi
}

validate_workload_certificate() {
  local certificate="$1"
  local key="$2"
  local workload="$3"
  local expected_uri="spiffe://firemud/ns/${namespace}/sa/${workload}"
  local certificate_text
  local basic_constraints
  local key_usage
  local extended_key_usage
  local san_values
  local expected_dns

  if ! openssl x509 -in "$certificate" -noout >/dev/null 2>&1; then
    echo "workload certificate could not be parsed: $certificate" >&2
    return 1
  fi
  if ! assert_key_matches_certificate "$certificate" "$key"; then
    return 1
  fi
  if ! certificate_text="$(openssl x509 -in "$certificate" -noout -text)"; then
    echo "workload certificate could not be parsed: $certificate" >&2
    return 1
  fi
  basic_constraints="$(printf '%s\n' "$certificate_text" | awk '
    /X509v3 Basic Constraints:/ {
      getline
      gsub(/^[[:space:]]+|[[:space:]]+$/, "")
      print
      exit
    }
  ')"
  [[ "$basic_constraints" == CA:FALSE ]] || {
    echo "workload certificate must be a non-CA leaf: $certificate" >&2
    return 1
  }
  key_usage="$(printf '%s\n' "$certificate_text" | awk '
    /X509v3 Key Usage:/ {
      getline
      gsub(/^[[:space:]]+|[[:space:]]+$/, "")
      print
      exit
    }
  ' | tr -d '[:space:]')"
  [[ "$key_usage" == DigitalSignature,KeyEncipherment ]] || {
    echo "workload certificate key usage must be exactly digitalSignature/keyEncipherment: $certificate" >&2
    return 1
  }
  extended_key_usage="$(printf '%s\n' "$certificate_text" | awk '
    /X509v3 Extended Key Usage:/ {
      getline
      gsub(/^[[:space:]]+|[[:space:]]+$/, "")
      print
      exit
    }
  ' | tr -d '[:space:]')"
  [[ "$extended_key_usage" == 'TLSWebServerAuthentication,TLSWebClientAuthentication' ]] || {
    echo "workload certificate EKU must be exactly serverAuth/clientAuth: $certificate" >&2
    return 1
  }
  if ! san_values="$(openssl x509 -in "$certificate" -noout -ext subjectAltName | awk 'NR > 1 { print }' | tr ',' '\n' | sed 's/^[[:space:]]*//; s/[[:space:]]*$//')"; then
    echo "workload certificate SANs could not be read: $certificate" >&2
    return 1
  fi
  [[ "$(printf '%s\n' "$san_values" | sed '/^$/d' | wc -l)" -eq 5 ]] || {
    echo "workload certificate must contain exactly one URI SAN and four DNS SANs: $certificate" >&2
    return 1
  }
  printf '%s\n' "$san_values" | grep -Fx "URI:${expected_uri}" >/dev/null || {
    echo "workload certificate has the wrong URI SAN: $certificate" >&2
    return 1
  }
  for expected_dns in \
    "$workload" \
    "${workload}.${namespace}" \
    "${workload}.${namespace}.svc" \
    "${workload}.${namespace}.svc.cluster.local"; do
    printf '%s\n' "$san_values" | grep -Fx "DNS:${expected_dns}" >/dev/null || {
      echo "workload certificate is missing DNS SAN ${expected_dns}: $certificate" >&2
      return 1
    }
  done
}

shared_ca="$cert_dir/shared-ca.crt"
shared_cert="$cert_dir/shared-client.crt"
shared_key="$cert_dir/shared-client.key"

shared_snapshot_status=0
if read_secret_snapshot "$shared_secret" \
  ca.crt "$shared_ca" client.crt "$shared_cert" client.key "$shared_key"; then
  shared_snapshot_status=0
else
  shared_snapshot_status=$?
fi

if ((shared_snapshot_status == 1)); then
  for workload in "${workloads[@]}"; do
    if secret_exists "firemud-grpc-${workload}"; then
      echo "shared gRPC TLS Secret is missing while a cert-manager publication Secret exists; refusing fresh replacement" >&2
      exit 1
    fi
  done
elif ((shared_snapshot_status != 0)); then
  echo "existing gRPC TLS Secret lacks a usable certificate snapshot: $shared_secret" >&2
  exit 1
fi

if ((shared_snapshot_status == 0)); then
  openssl x509 -in "$shared_cert" -noout >/dev/null
  shared_rotation_secrets=("${namespace}/${shared_secret}")
  for workload in "${workloads[@]}"; do
    shared_rotation_secrets+=("${namespace}/firemud-grpc-${workload}")
  done
  assert_certificate_unexpired "$shared_cert" \
    "shared gRPC TLS client certificate in Secret ${namespace}/${shared_secret}" \
    "${shared_rotation_secrets[*]}" || exit 1
  assert_key_matches_certificate "$shared_cert" "$shared_key"
else
  # The legacy shared bundle is still required by the six non-publication
  # workloads. Generate it once, then preserve its leaf on every later run.
  "$legacy_generator" "$cert_dir"
  for required_file in ca.crt client.crt client.key ca.key; do
    [[ -s "$cert_dir/$required_file" ]] || {
      echo "shared certificate bootstrap did not produce $required_file" >&2
      exit 1
    }
  done
  cp "$cert_dir/ca.crt" "$shared_ca"
  cp "$cert_dir/client.crt" "$shared_cert"
  cp "$cert_dir/client.key" "$shared_key"
  apply_secret "$shared_secret" \
    --from-file=ca.crt="$shared_ca" \
    --from-file=client.crt="$shared_cert" \
    --from-file=client.key="$shared_key"
fi

if [[ "$grpc_tls_operation" == shared-only ]]; then
  printf 'namespace=%s\nsharedSecret=ready\n' "$namespace"
  exit 0
fi

declare -A fingerprints=()
shared_fingerprint="$(openssl x509 -in "$shared_cert" -outform der | openssl sha256)"
certificate_wait_timeout_seconds="${CERTIFICATE_WAIT_TIMEOUT_SECONDS:-900}"
if [[ ! "$certificate_wait_timeout_seconds" =~ ^[1-9][0-9]{0,3}$ ]] ||
  ((10#$certificate_wait_timeout_seconds > 3600)); then
  echo "CERTIFICATE_WAIT_TIMEOUT_SECONDS must be an integer between 1 and 3600" >&2
  exit 2
fi
issuer_ca=''
issuer_fingerprint=''
certificate_wait_deadline=$((SECONDS + certificate_wait_timeout_seconds))

for workload in "${workloads[@]}"; do
  secret_name="firemud-grpc-${workload}"
  workload_cert="$cert_dir/${workload}.crt"
  workload_key="$cert_dir/${workload}.key"
  workload_ca="$cert_dir/${workload}-ca.crt"
  projection_complete=false

  while ((SECONDS < certificate_wait_deadline)); do
    if read_secret_snapshot "$secret_name" \
      tls.crt "$workload_cert" tls.key "$workload_key" ca.crt "$workload_ca"; then
      projection_complete=true
      break
    fi
    sleep 5
  done
  [[ "$projection_complete" == true ]] || {
    echo "cert-manager Secret ${namespace}/${secret_name} did not become key-complete within the aggregate ${certificate_wait_timeout_seconds}s window" >&2
    exit 1
  }

  validate_workload_certificate "$workload_cert" "$workload_key" "$workload"
  assert_certificate_unexpired "$workload_cert" \
    "cert-manager publication certificate in Secret ${namespace}/${secret_name}" \
    "${namespace}/${secret_name}" || exit 1
  openssl x509 -in "$workload_ca" -noout -text | grep -Fq 'CA:TRUE' || {
    echo "cert-manager CA projection is not a CA certificate: ${namespace}/${secret_name}" >&2
    exit 1
  }
  assert_certificate_unexpired "$workload_ca" \
    "cert-manager CA projection in Secret ${namespace}/${secret_name}" \
    "${namespace}/${secret_name}" || exit 1
  openssl verify -CAfile "$workload_ca" "$workload_cert" >/dev/null || {
    echo "cert-manager publication certificate does not chain to its projected CA: ${namespace}/${secret_name}" >&2
    exit 1
  }
  current_issuer_fingerprint="$(openssl x509 -in "$workload_ca" -outform der | openssl sha256)"
  if [[ -n "$issuer_fingerprint" && "$issuer_fingerprint" != "$current_issuer_fingerprint" ]]; then
    echo "cert-manager publication Secrets do not share one CA projection" >&2
    exit 1
  fi
  issuer_fingerprint="$current_issuer_fingerprint"
  issuer_ca="$workload_ca"

  fingerprint="$(openssl x509 -in "$workload_cert" -outform der | openssl sha256)"
  [[ "$fingerprint" != "$shared_fingerprint" ]] || {
    echo "publication workload certificate must not reuse the shared gRPC leaf: $secret_name" >&2
    exit 1
  }
  [[ -z "${fingerprints[$fingerprint]+present}" ]] || {
    echo "publication workload certificates must have distinct leaf identities: $secret_name" >&2
    exit 1
  }
  fingerprints["$fingerprint"]="$workload"
done

if ! ca_bundle_contains "$shared_ca" "$issuer_ca"; then
  cat "$shared_ca" "$issuer_ca" >"$cert_dir/shared-ca-updated.crt"
  mv "$cert_dir/shared-ca-updated.crt" "$shared_ca"
  apply_secret "$shared_secret" \
    --from-file=ca.crt="$shared_ca" \
    --from-file=client.crt="$shared_cert" \
    --from-file=client.key="$shared_key"
fi

# Remove the former runtime-local CA key only after replacement projections
# have passed validation and the shared trust bundle has the issuer anchor.
kubectl -n "$namespace" delete secret firemud-grpc-ca --ignore-not-found >/dev/null
for workload in "${workloads[@]}"; do
  kubectl -n "$namespace" delete secret "${namespace}-grpc-${workload}" --ignore-not-found >/dev/null
done

echo "cert-manager gRPC TLS material is ready in namespace ${namespace}: shared legacy bundle plus ${#fingerprints[@]} distinct publication leaves"
