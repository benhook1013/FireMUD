#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 1 ]]; then
  echo "usage: $0 <source-kubeconfig>" >&2
  exit 2
fi

SOURCE_KUBECONFIG="$1"
TARGET_DIR="${HOME}/.kube"
TARGET_KUBECONFIG="${TARGET_DIR}/config"

mkdir -p "${TARGET_DIR}"
install -m 600 "${SOURCE_KUBECONFIG}" "${TARGET_KUBECONFIG}"
echo "Persisted runner kubeconfig to ${TARGET_KUBECONFIG}"
