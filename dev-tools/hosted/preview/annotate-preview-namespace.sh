#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 5 ]]; then
  echo "usage: $0 <namespace> <pr_number> <head_sha> <image_tag> <telnet_port>" >&2
  exit 1
fi

namespace="$1"
pr_number="$2"
head_sha="$3"
image_tag="$4"
telnet_port="$5"
sync_timestamp="$(date -u +%Y-%m-%dT%H:%M:%SZ)"

kubectl annotate namespace "$namespace" \
  "firemud.dev/last-preview-head-sha=${head_sha}" \
  "firemud.dev/last-preview-image-tag=${image_tag}" \
  "firemud.dev/last-preview-telnet-port=${telnet_port}" \
  "firemud.dev/last-preview-sync-at=${sync_timestamp}" \
  --overwrite >/dev/null

kubectl label namespace "$namespace" \
  firemud.dev/preview=true \
  "firemud.dev/pr-number=${pr_number}" \
  --overwrite >/dev/null
