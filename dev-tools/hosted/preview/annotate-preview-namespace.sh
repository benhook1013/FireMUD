#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 6 ]]; then
  echo "usage: $0 <namespace> <pr_number> <head_sha> <image_tag> <telnet_port> <allocation_timestamp>" >&2
  exit 1
fi

namespace="$1"
pr_number="$2"
head_sha="$3"
image_tag="$4"
telnet_port="$5"
allocation_timestamp="$6"

if ! [[ "$pr_number" =~ ^[1-9][0-9]{0,50}$ ]]; then
  echo "pr_number must match [1-9][0-9]{0,50}" >&2
  exit 1
fi
if [[ "$namespace" != "pr-${pr_number}" ]]; then
  echo "namespace must equal pr-${pr_number}" >&2
  exit 1
fi
if ! [[ "$telnet_port" =~ ^32(00[0-9]|01[0-5])$ ]]; then
  echo "telnet_port must be an allocated port from 32000 through 32015" >&2
  exit 1
fi
if ! [[ "$allocation_timestamp" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$ ]] ||
  [[ "$(date -u -d "$allocation_timestamp" +%Y-%m-%dT%H:%M:%SZ 2>/dev/null || true)" != "$allocation_timestamp" ]]; then
  echo "allocation_timestamp must be a valid UTC timestamp in YYYY-MM-DDTHH:MM:SSZ format" >&2
  exit 1
fi

sync_timestamp="$(date -u +%Y-%m-%dT%H:%M:%SZ)"

kubectl annotate namespace "$namespace" \
  "firemud.dev/requested-preview-head-sha=${head_sha}" \
  "firemud.dev/last-preview-image-tag=${image_tag}" \
  "firemud.dev/last-preview-telnet-port=${telnet_port}" \
  "firemud.dev/preview-allocated-at=${allocation_timestamp}" \
  "firemud.dev/last-preview-sync-at=${sync_timestamp}" \
  --overwrite >/dev/null

kubectl label namespace "$namespace" \
  firemud.dev/preview=true \
  "firemud.dev/pr-number=${pr_number}" \
  --overwrite >/dev/null
