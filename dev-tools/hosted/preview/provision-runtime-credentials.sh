#!/usr/bin/env bash
set -euo pipefail

umask 077
if [[ -z "${RUNTIME_NAMESPACE:-}" ]]; then
  echo "runtime namespace is required" >&2
  exit 2
fi
if [[ ! "$RUNTIME_NAMESPACE" =~ ^pr-[1-9][0-9]{0,50}$ ]]; then
  echo "runtime namespace must match pr-[1-9][0-9]{0,50}" >&2
  exit 2
fi
# Canonical Base64 round-trip validation uses GNU coreutils --decode and --wrap=0;
# this trusted workflow step is supported on its Linux preview runner only.
for required_command in jq base64 openssl sha256sum; do
  if ! command -v "$required_command" >/dev/null 2>&1; then
    echo "$required_command is required" >&2
    exit 2
  fi
done
if [[ -z "${RUNNER_TEMP:-}" || ! -d "$RUNNER_TEMP" ]]; then
  echo "RUNNER_TEMP must name an existing directory" >&2
  exit 2
fi
credential_files_dir=""
cleanup_credential_files() {
  if [[ -n "$credential_files_dir" && -d "$credential_files_dir" ]]; then
    rm -rf -- "$credential_files_dir"
  fi
}
trap cleanup_credential_files EXIT
credential_files_dir="$(mktemp -d -- "${RUNNER_TEMP}/firemud-runtime-credentials.XXXXXX")"
chmod 700 "$credential_files_dir"
write_credential_file() {
  local file_name="$1"
  local value="$2"
  printf '%s' "$value" >"$credential_files_dir/$file_name"
  chmod 600 "$credential_files_dir/$file_name"
}
reject_existing_secret() {
  local secret_name="$1"
  local reason="$2"
  echo "::error::Existing Secret ${RUNTIME_NAMESPACE}/${secret_name} is invalid (${reason}). Recycle the disposable preview namespace before retrying; credentials were not changed." >&2
  exit 1
}
reject_secret_create_failure() {
  local secret_name="$1"
  echo "::error::Unable to create Secret ${RUNTIME_NAMESPACE}/${secret_name}, and no concurrent winner could be read; refusing to continue." >&2
  exit 1
}
read_secret_if_present() {
  local secret_name="$1"
  local secret_json
  if ! secret_json="$(kubectl -n "$RUNTIME_NAMESPACE" get secret "$secret_name" --ignore-not-found -o json)"; then
    echo "::error::Unable to read Secret ${RUNTIME_NAMESPACE}/${secret_name}; credentials were not changed." >&2
    return 1
  fi
  printf '%s' "$secret_json"
}
reject_existing_configmap() {
  local configmap_name="$1"
  local reason="$2"
  echo "::error::Existing ConfigMap ${RUNTIME_NAMESPACE}/${configmap_name} is invalid (${reason}). Recycle the disposable preview namespace before retrying; credentials were not changed." >&2
  exit 1
}
reject_configmap_create_failure() {
  local configmap_name="$1"
  echo "::error::Unable to create ConfigMap ${RUNTIME_NAMESPACE}/${configmap_name}, and no concurrent winner could be read; refusing to continue." >&2
  exit 1
}
read_configmap_if_present() {
  local configmap_name="$1"
  local configmap_json
  if ! configmap_json="$(kubectl -n "$RUNTIME_NAMESPACE" get configmap "$configmap_name" --ignore-not-found -o json)"; then
    echo "::error::Unable to read ConfigMap ${RUNTIME_NAMESPACE}/${configmap_name}; credentials were not changed." >&2
    return 1
  fi
  printf '%s' "$configmap_json"
}
validate_secret_shape() {
  local secret_name="$1"
  local expected_keys_json="$2"
  local secret_json="$3"
  jq -e \
    --arg name "$secret_name" \
    --arg namespace "$RUNTIME_NAMESPACE" \
    --argjson expected_keys "$expected_keys_json" '
      .apiVersion == "v1" and
      .kind == "Secret" and
      .metadata.name == $name and
      .metadata.namespace == $namespace and
      .type == "Opaque" and
      (.data | type == "object") and
      ((.data | keys | sort) == ($expected_keys | sort))
    ' <<<"$secret_json" >/dev/null ||
    reject_existing_secret "$secret_name" "expected exactly the canonical keys"
}
decode_secret_key() {
  local secret_name="$1"
  local key="$2"
  local secret_json="$3"
  local encoded_value decoded_secret_value canonical_encoded_value
  # Callers use assignment command substitutions: rejection exits that subshell,
  # and the failed assignment reaches the caller's set -e.
  if ! encoded_value="$(jq -er --arg key "$key" '.data[$key] | select(type == "string" and length > 0)' <<<"$secret_json")" ||
    ! decoded_secret_value="$(printf '%s' "$encoded_value" | base64 --decode 2>/dev/null)" ||
    [[ -z "$decoded_secret_value" ]]; then
    reject_existing_secret "$secret_name" "key ${key} is empty or malformed"
  fi
  # Bash command substitution strips trailing newlines and cannot preserve NUL bytes;
  # canonical re-encoding therefore rejects those decoded values as well as noncanonical Base64.
  canonical_encoded_value="$(printf '%s' "$decoded_secret_value" | base64 --wrap=0)"
  if [[ "$canonical_encoded_value" != "$encoded_value" ]]; then
    reject_existing_secret "$secret_name" "key ${key} is empty or malformed"
  fi
  printf '%s' "$decoded_secret_value"
}
validate_configmap_shape() {
  local configmap_name="$1"
  local expected_keys_json="$2"
  local configmap_json="$3"
  jq -e \
    --arg name "$configmap_name" \
    --arg namespace "$RUNTIME_NAMESPACE" \
    --argjson expected_keys "$expected_keys_json" '
      .apiVersion == "v1" and
      .kind == "ConfigMap" and
      .metadata.name == $name and
      .metadata.namespace == $namespace and
      (.data | type == "object") and
      ((.data | keys | sort) == ($expected_keys | sort)) and
      ((.binaryData // {}) == {})
    ' <<<"$configmap_json" >/dev/null ||
    reject_existing_configmap "$configmap_name" "expected exactly the canonical data keys"
}
validate_diagnostic_jwks() {
  local expected_fingerprint="$1"
  local configmap_json="$2"
  local diagnostic_jwks
  if ! diagnostic_jwks="$(jq -er '.data["jwks.json"] | select(type == "string" and length > 0)' <<<"$configmap_json")" ||
    ! jq -e --arg fingerprint "$expected_fingerprint" '
      type == "object" and
      ((keys | sort) == ["firemudDiagnostic", "keys"]) and
      .keys == [] and
      (.firemudDiagnostic | type == "object") and
      ((.firemudDiagnostic | keys | sort) == ["purpose", "sha256"]) and
      .firemudDiagnostic.purpose == "shared-hmac-secret-path-fingerprint" and
      .firemudDiagnostic.sha256 == $fingerprint
    ' <<<"$diagnostic_jwks" >/dev/null; then
    reject_existing_configmap jwt-jwks "diagnostic content does not match the signing Secret"
  fi
}
load_firemud_secret() {
  local secret_json="$1"
  validate_secret_shape firemud-secret \
    '["FIREMUD_POSTGRES_USER","FIREMUD_POSTGRES_PASSWORD","ASSET_STORE_ACCESS_KEY","ASSET_STORE_SECRET_KEY"]' \
    "$secret_json"
  postgres_user="$(decode_secret_key firemud-secret FIREMUD_POSTGRES_USER "$secret_json")"
  if [[ "$postgres_user" != firemud ]]; then
    reject_existing_secret firemud-secret "PostgreSQL user is not canonical"
  fi
  postgres_password="$(decode_secret_key firemud-secret FIREMUD_POSTGRES_PASSWORD "$secret_json")"
  asset_store_access_key="$(decode_secret_key firemud-secret ASSET_STORE_ACCESS_KEY "$secret_json")"
  asset_store_secret_key="$(decode_secret_key firemud-secret ASSET_STORE_SECRET_KEY "$secret_json")"
  if [[ "$postgres_password" == firemud ||
    "$asset_store_access_key" == minio ||
    "$asset_store_secret_key" == minio123 ]]; then
    reject_existing_secret firemud-secret "legacy weak credential value"
  fi
}
load_minio_secret() {
  local secret_json="$1"
  validate_secret_shape minio-credentials '["accessKey","secretKey"]' "$secret_json"
  minio_access_key="$(decode_secret_key minio-credentials accessKey "$secret_json")"
  minio_secret_key="$(decode_secret_key minio-credentials secretKey "$secret_json")"
  if [[ "$minio_access_key" == minio || "$minio_secret_key" == minio123 ]]; then
    reject_existing_secret minio-credentials "legacy weak credential value"
  fi
}
load_jwt_signing_secret() {
  local secret_json="$1"
  validate_secret_shape jwt-signing-keys '["current.key"]' "$secret_json"
  signing_key="$(decode_secret_key jwt-signing-keys current.key "$secret_json")"
  if [[ ! "$signing_key" =~ ^[0-9a-f]{64}$ ]]; then
    reject_existing_secret jwt-signing-keys "current.key is not canonical"
  fi
}
validate_matching_minio_credentials() {
  if [[ "$asset_store_access_key" != "$minio_access_key" ||
    "$asset_store_secret_key" != "$minio_secret_key" ]]; then
    reject_existing_secret minio-credentials "MinIO credentials do not match the application Secret"
  fi
}

firemud_secret_exists=false
firemud_secret_json="$(read_secret_if_present firemud-secret)"
if [[ -n "$firemud_secret_json" ]]; then
  firemud_secret_exists=true
  load_firemud_secret "$firemud_secret_json"
fi

minio_secret_exists=false
minio_secret_json="$(read_secret_if_present minio-credentials)"
if [[ -n "$minio_secret_json" ]]; then
  minio_secret_exists=true
  load_minio_secret "$minio_secret_json"
fi

if [[ "$firemud_secret_exists" == true && "$minio_secret_exists" == true ]] &&
  [[ "$asset_store_access_key" != "$minio_access_key" ||
    "$asset_store_secret_key" != "$minio_secret_key" ]]; then
  validate_matching_minio_credentials
fi

jwt_signing_secret_exists=false
jwt_signing_secret_json="$(read_secret_if_present jwt-signing-keys)"
if [[ -n "$jwt_signing_secret_json" ]]; then
  jwt_signing_secret_exists=true
  load_jwt_signing_secret "$jwt_signing_secret_json"
fi

jwt_jwks_exists=false
jwt_jwks_json="$(read_configmap_if_present jwt-jwks)"
if [[ -n "$jwt_jwks_json" ]]; then
  jwt_jwks_exists=true
  validate_configmap_shape jwt-jwks '["jwks.json"]' "$jwt_jwks_json"
  if [[ "$jwt_signing_secret_exists" != true ]]; then
    reject_existing_configmap jwt-jwks "signing Secret is absent and cannot be reconstructed"
  fi
fi

if [[ "$firemud_secret_exists" != true ]]; then
  postgres_user=firemud
  postgres_password="$(openssl rand -hex 32)"
  if [[ "$minio_secret_exists" == true ]]; then
    asset_store_access_key="$minio_access_key"
    asset_store_secret_key="$minio_secret_key"
  else
    asset_store_access_key="$(openssl rand -hex 16)"
    asset_store_secret_key="$(openssl rand -hex 32)"
  fi
fi
if [[ "$jwt_signing_secret_exists" != true ]]; then
  signing_key="$(openssl rand -hex 32)"
fi
signing_key_sha256="$(printf '%s' "$signing_key" | sha256sum | awk '{print $1}')"
diagnostic_jwks="$(jq -nc --arg fingerprint "$signing_key_sha256" \
  '{keys:[],firemudDiagnostic:{purpose:"shared-hmac-secret-path-fingerprint",sha256:$fingerprint}}')"
if [[ "$jwt_jwks_exists" == true ]]; then
  validate_diagnostic_jwks "$signing_key_sha256" "$jwt_jwks_json"
fi

if [[ "$firemud_secret_exists" != true ]]; then
  write_credential_file FIREMUD_POSTGRES_USER "$postgres_user"
  write_credential_file FIREMUD_POSTGRES_PASSWORD "$postgres_password"
  write_credential_file ASSET_STORE_ACCESS_KEY "$asset_store_access_key"
  write_credential_file ASSET_STORE_SECRET_KEY "$asset_store_secret_key"
fi
if [[ "$minio_secret_exists" != true ]]; then
  minio_access_key="$asset_store_access_key"
  minio_secret_key="$asset_store_secret_key"
  write_credential_file accessKey "$minio_access_key"
  write_credential_file secretKey "$minio_secret_key"
fi
if [[ "$jwt_signing_secret_exists" != true ]]; then
  write_credential_file current.key "$signing_key"
fi
if [[ "$jwt_jwks_exists" != true ]]; then
  write_credential_file jwks.json "$diagnostic_jwks"
fi

if [[ "$firemud_secret_exists" != true ]]; then
  if ! kubectl -n "$RUNTIME_NAMESPACE" create secret generic firemud-secret \
    --from-file="FIREMUD_POSTGRES_USER=${credential_files_dir}/FIREMUD_POSTGRES_USER" \
    --from-file="FIREMUD_POSTGRES_PASSWORD=${credential_files_dir}/FIREMUD_POSTGRES_PASSWORD" \
    --from-file="ASSET_STORE_ACCESS_KEY=${credential_files_dir}/ASSET_STORE_ACCESS_KEY" \
    --from-file="ASSET_STORE_SECRET_KEY=${credential_files_dir}/ASSET_STORE_SECRET_KEY"; then
    firemud_secret_json="$(read_secret_if_present firemud-secret)" || exit 1
    [[ -n "$firemud_secret_json" ]] || reject_secret_create_failure firemud-secret
    load_firemud_secret "$firemud_secret_json"
    if [[ "$minio_secret_exists" != true ]]; then
      minio_access_key="$asset_store_access_key"
      minio_secret_key="$asset_store_secret_key"
      write_credential_file accessKey "$minio_access_key"
      write_credential_file secretKey "$minio_secret_key"
    fi
  fi
  firemud_secret_exists=true
fi
if [[ "$minio_secret_exists" != true ]]; then
  if ! kubectl -n "$RUNTIME_NAMESPACE" create secret generic minio-credentials \
    --from-file="accessKey=${credential_files_dir}/accessKey" \
    --from-file="secretKey=${credential_files_dir}/secretKey"; then
    minio_secret_json="$(read_secret_if_present minio-credentials)" || exit 1
    [[ -n "$minio_secret_json" ]] || reject_secret_create_failure minio-credentials
    load_minio_secret "$minio_secret_json"
  fi
  minio_secret_exists=true
fi
validate_matching_minio_credentials
if [[ "$jwt_signing_secret_exists" != true ]]; then
  if ! kubectl -n "$RUNTIME_NAMESPACE" create secret generic jwt-signing-keys \
    --from-file="current.key=${credential_files_dir}/current.key"; then
    jwt_signing_secret_json="$(read_secret_if_present jwt-signing-keys)" || exit 1
    [[ -n "$jwt_signing_secret_json" ]] || reject_secret_create_failure jwt-signing-keys
    load_jwt_signing_secret "$jwt_signing_secret_json"
    signing_key_sha256="$(printf '%s' "$signing_key" | sha256sum | awk '{print $1}')"
    diagnostic_jwks="$(jq -nc --arg fingerprint "$signing_key_sha256" \
      '{keys:[],firemudDiagnostic:{purpose:"shared-hmac-secret-path-fingerprint",sha256:$fingerprint}}')"
    if [[ "$jwt_jwks_exists" != true ]]; then
      write_credential_file jwks.json "$diagnostic_jwks"
    fi
  fi
  jwt_signing_secret_exists=true
fi
if [[ "$jwt_jwks_exists" != true ]]; then
  if ! kubectl -n "$RUNTIME_NAMESPACE" create configmap jwt-jwks \
    --from-file="jwks.json=${credential_files_dir}/jwks.json"; then
    jwt_jwks_json="$(read_configmap_if_present jwt-jwks)" || exit 1
    [[ -n "$jwt_jwks_json" ]] || reject_configmap_create_failure jwt-jwks
    validate_configmap_shape jwt-jwks '["jwks.json"]' "$jwt_jwks_json"
    validate_diagnostic_jwks "$signing_key_sha256" "$jwt_jwks_json"
  fi
  jwt_jwks_exists=true
fi

kubectl -n "$RUNTIME_NAMESPACE" create serviceaccount firemud-app --dry-run=client -o yaml |
  kubectl -n "$RUNTIME_NAMESPACE" apply -f -
