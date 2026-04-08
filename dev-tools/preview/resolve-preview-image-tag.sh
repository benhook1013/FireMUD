#!/usr/bin/env bash
set -euo pipefail

if [[ $# -lt 1 ]]; then
  echo "usage: $0 <requested_image_tag>" >&2
  exit 1
fi

requested_image_tag="$1"

if [[ -z "${requested_image_tag}" ]]; then
  echo "requested image tag must not be empty" >&2
  exit 1
fi

echo "${requested_image_tag}"
