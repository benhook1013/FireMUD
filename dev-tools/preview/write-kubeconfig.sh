#!/usr/bin/env bash
set -euo pipefail

if [[ -z "${PREVIEW_KUBECONFIG:-}" ]]; then
  echo "PREVIEW_KUBECONFIG is required" >&2
  exit 1
fi

target_path="${1:-${RUNNER_TEMP:-/tmp}/preview-kubeconfig.yaml}"
local_server="${PREVIEW_KUBECONFIG_LOCAL_SERVER:-}"

mkdir -p "$(dirname "$target_path")"
printf '%s\n' "$PREVIEW_KUBECONFIG" > "$target_path"

if [[ -n "$local_server" ]]; then
  python3 - "$target_path" "$local_server" <<'PY'
import sys
from pathlib import Path
from urllib.parse import urlparse

import yaml

path = Path(sys.argv[1])
local_server = sys.argv[2]
data = yaml.safe_load(path.read_text(encoding="utf-8"))

for cluster_entry in data.get("clusters", []):
    cluster = cluster_entry.get("cluster", {})
    original_server = cluster.get("server", "")
    if not original_server:
        continue
    parsed = urlparse(original_server)
    if parsed.hostname:
        cluster.setdefault("tls-server-name", parsed.hostname)
    cluster["server"] = local_server

path.write_text(yaml.safe_dump(data, sort_keys=False), encoding="utf-8")
PY
fi

chmod 600 "$target_path"

echo "$target_path"
