#!/usr/bin/env bash
set -euo pipefail

if [[ -z "${PREVIEW_KUBECONFIG:-}" ]]; then
  echo "PREVIEW_KUBECONFIG is required" >&2
  exit 1
fi

target_path="${1:-${RUNNER_TEMP:-/tmp}/preview-kubeconfig.yaml}"

mkdir -p "$(dirname "$target_path")"
printf '%s\n' "$PREVIEW_KUBECONFIG" > "$target_path"
chmod 600 "$target_path"

echo "$target_path"
