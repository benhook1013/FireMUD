#!/usr/bin/env bash
# Validate external credential bindings after a restore before opening traffic.
set -euo pipefail

usage() {
  echo "Usage: validate-external-credentials.sh <hobby-self-hosted|staging|production>" >&2
  exit 1
}

[ $# -eq 1 ] || usage
ENVIRONMENT="$1"
if [[ "$ENVIRONMENT" != "hobby-self-hosted" && "$ENVIRONMENT" != "staging" && "$ENVIRONMENT" != "production" ]]; then
  usage
fi

check_required_var() {
  local key="$1"
  if [[ -z "${!key:-}" ]]; then
    echo "Missing required environment variable: $key" >&2
    exit 1
  fi
}

validate_expected_match() {
  local actual_key="$1"
  local expected_key="$2"
  if [[ -n "${!expected_key:-}" && "${!actual_key:-}" != "${!expected_key}" ]]; then
    echo "Mismatch: $actual_key='${!actual_key}' does not match $expected_key='${!expected_key}'" >&2
    exit 1
  fi
}

echo "Validating external credential bindings for environment: $ENVIRONMENT"

check_required_var "PG_DUMP_BUCKET"
check_required_var "ASSET_STORE_BUCKET"

validate_expected_match "PG_DUMP_BUCKET" "EXPECTED_PG_DUMP_BUCKET"
validate_expected_match "ASSET_STORE_BUCKET" "EXPECTED_ASSET_STORE_BUCKET"
validate_expected_match "ASSET_STORE_ENDPOINT" "EXPECTED_ASSET_STORE_ENDPOINT"
validate_expected_match "SMTP_HOST" "EXPECTED_SMTP_HOST"

if command -v aws >/dev/null 2>&1; then
  AWS_ENDPOINT_ARGS=()
  if [[ -n "${PG_DUMP_ENDPOINT:-}" ]]; then
    AWS_ENDPOINT_ARGS+=(--endpoint-url "$PG_DUMP_ENDPOINT")
  fi
  aws s3api head-bucket --bucket "$PG_DUMP_BUCKET" "${AWS_ENDPOINT_ARGS[@]}" >/dev/null
  echo "Verified backup bucket access: $PG_DUMP_BUCKET"
else
  echo "Skipping backup bucket access check because aws CLI is unavailable."
fi

if [[ "$ENVIRONMENT" == "staging" ]]; then
  if [[ -n "${PRODUCTION_PG_DUMP_BUCKET:-}" && "$PG_DUMP_BUCKET" == "$PRODUCTION_PG_DUMP_BUCKET" ]]; then
    echo "Staging must not use the production backup bucket." >&2
    exit 1
  fi
  if [[ -n "${PRODUCTION_ASSET_STORE_BUCKET:-}" && "$ASSET_STORE_BUCKET" == "$PRODUCTION_ASSET_STORE_BUCKET" ]]; then
    echo "Staging must not use the production asset bucket." >&2
    exit 1
  fi
fi

if [[ "$ENVIRONMENT" == "staging" ]]; then
  check_required_var "SANITIZATION_EVIDENCE_REF"
  if [[ ! "$SANITIZATION_EVIDENCE_REF" =~ ^design/operations/deployments/staging/recovery/ ]]; then
    echo "SANITIZATION_EVIDENCE_REF must point to a staged recovery record under design/operations/deployments/staging/recovery/." >&2
    exit 1
  fi
fi

echo "External credential validation passed for $ENVIRONMENT."
