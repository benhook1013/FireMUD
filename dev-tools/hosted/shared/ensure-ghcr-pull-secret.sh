#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 1 ]]; then
  echo "usage: $0 <namespace>" >&2
  exit 1
fi

if [[ -z "${PREVIEW_GHCR_USERNAME:-}" ]]; then
  echo "PREVIEW_GHCR_USERNAME is required" >&2
  exit 1
fi

if [[ -z "${PREVIEW_GHCR_TOKEN:-}" ]]; then
  echo "PREVIEW_GHCR_TOKEN is required" >&2
  exit 1
fi

namespace="$1"
secret_name="${PREVIEW_GHCR_SECRET_NAME:-ghcr-preview-pull}"

kubectl -n "$namespace" create secret docker-registry "$secret_name" \
  --docker-server=ghcr.io \
  --docker-username="$PREVIEW_GHCR_USERNAME" \
  --docker-password="$PREVIEW_GHCR_TOKEN" \
  --dry-run=client \
  -o yaml | kubectl apply -f -
