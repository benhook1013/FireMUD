#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 4 ]]; then
  echo "usage: $0 <namespace> <head_sha> <image_tag> <telnet_port>" >&2
  exit 1
fi

namespace="$1"
head_sha="$2"
image_tag="$3"
telnet_port="$4"
sync_timestamp="$(date -u +%Y-%m-%dT%H:%M:%SZ)"

kubectl annotate namespace "$namespace" \
  "firemud.dev/last-dev-demo-head-sha=${head_sha}" \
  "firemud.dev/last-dev-demo-image-tag=${image_tag}" \
  "firemud.dev/last-dev-demo-telnet-port=${telnet_port}" \
  "firemud.dev/last-dev-demo-sync-at=${sync_timestamp}" \
  --overwrite >/dev/null

kubectl label namespace "$namespace" \
  firemud.dev/dev-demo=true \
  firemud.dev/environment-class=dev-demo-cluster \
  --overwrite >/dev/null
