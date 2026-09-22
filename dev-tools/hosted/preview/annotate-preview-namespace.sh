#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 9 ]]; then
  echo "usage: $0 <namespace> <pr_number> <base_sha> <head_sha> <merge_sha> <image_tag> <private|public> <telnet_port> <allocation_timestamp>" >&2
  exit 1
fi

namespace="$1"
pr_number="$2"
base_sha="$3"
head_sha="$4"
merge_sha="$5"
image_tag="$6"
exposure_mode="$7"
telnet_port="$8"
allocation_timestamp="$9"

if ! [[ "$pr_number" =~ ^[1-9][0-9]{0,50}$ ]]; then
  echo "pr_number must match [1-9][0-9]{0,50}" >&2
  exit 1
fi
if [[ "$namespace" != "pr-${pr_number}" ]]; then
  echo "namespace must equal pr-${pr_number}" >&2
  exit 1
fi
for sha_name in base_sha head_sha merge_sha; do
  sha_value="${!sha_name}"
  if ! [[ "$sha_value" =~ ^[0-9a-f]{40}$ ]]; then
    echo "${sha_name} must be exactly 40 lowercase hexadecimal characters" >&2
    exit 1
  fi
done
if ! [[ "$image_tag" =~ ^[A-Za-z0-9_][A-Za-z0-9_.-]{0,127}$ ]]; then
  echo "image_tag must be a non-empty canonical image tag of at most 128 safe characters" >&2
  exit 1
fi
if [[ "$exposure_mode" != private && "$exposure_mode" != public ]]; then
  echo "exposure_mode must be private or public" >&2
  exit 1
fi
if [[ "$exposure_mode" == private && "$telnet_port" != 0 ]]; then
  echo "private previews require sentinel telnet_port 0" >&2
  exit 1
fi
if [[ "$exposure_mode" == public && ! "$telnet_port" =~ ^32(00[0-9]|01[0-5])$ ]]; then
  echo "public telnet_port must be an allocated port from 32000 through 32015" >&2
  exit 1
fi
if ! [[ "$allocation_timestamp" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$ ]] ||
  [[ "$(date -u -d "$allocation_timestamp" +%Y-%m-%dT%H:%M:%SZ 2>/dev/null || true)" != "$allocation_timestamp" ]]; then
  echo "allocation_timestamp must be a valid UTC timestamp in YYYY-MM-DDTHH:MM:SSZ format" >&2
  exit 1
fi

sync_timestamp="$(date -u +%Y-%m-%dT%H:%M:%SZ)"

annotations=(
  "firemud.dev/requested-preview-base-sha=${base_sha}" \
  "firemud.dev/requested-preview-head-sha=${head_sha}" \
  "firemud.dev/requested-preview-merge-sha=${merge_sha}" \
  "firemud.dev/requested-preview-image-tag=${image_tag}" \
  "firemud.dev/preview-allocated-at=${allocation_timestamp}" \
  "firemud.dev/last-preview-sync-at=${sync_timestamp}"
)
if [[ "$exposure_mode" == public ]]; then
  annotations+=("firemud.dev/last-preview-telnet-port=${telnet_port}")
else
  annotations+=("firemud.dev/last-preview-telnet-port-")
fi

kubectl annotate namespace "$namespace" \
  "${annotations[@]}" \
  --overwrite >/dev/null

kubectl label namespace "$namespace" \
  firemud.dev/preview=true \
  "firemud.dev/pr-number=${pr_number}" \
  "firemud.dev/preview-exposure-mode=${exposure_mode}" \
  --overwrite >/dev/null
