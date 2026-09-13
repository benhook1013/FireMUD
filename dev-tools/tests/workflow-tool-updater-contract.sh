#!/usr/bin/env bash
set -euo pipefail
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
cp "$ROOT_DIR/config/workflow-tool-versions.env" "$tmp/authority.env"
cp "$ROOT_DIR/k8s/velero/verify-backups-cronjob.yaml" "$tmp/velero.yaml"
checksum=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
echo "$checksum  velero-v9.8.7-linux-amd64.tar.gz" > "$tmp/checksums"
python3 "$ROOT_DIR/dev-tools/maintenance/update-workflow-tool.py" velero 9.8.7 \
  --checksum-file "$tmp/checksums" --authority "$tmp/authority.env" --velero-manifest "$tmp/velero.yaml"
grep -Fx 'VELERO_VERSION=9.8.7' "$tmp/authority.env" >/dev/null
grep -Fx 'VELERO_LINUX_AMD64_CHECKSUM_VERSION=9.8.7' "$tmp/authority.env" >/dev/null
grep -Fx "VELERO_LINUX_AMD64_SHA256=$checksum" "$tmp/authority.env" >/dev/null
grep -F 'image: velero/velero:v9.8.7' "$tmp/velero.yaml" >/dev/null

cp "$ROOT_DIR/config/workflow-tool-versions.env" "$tmp/unchanged.env"
cp "$tmp/unchanged.env" "$tmp/before.env"
echo 'no Velero image projection' > "$tmp/invalid-velero.yaml"
if python3 "$ROOT_DIR/dev-tools/maintenance/update-workflow-tool.py" velero 9.8.7 \
  --checksum-file "$tmp/checksums" --authority "$tmp/unchanged.env" --velero-manifest "$tmp/invalid-velero.yaml"; then
  echo 'updater accepted a missing Velero image projection' >&2
  exit 1
fi
cmp "$tmp/before.env" "$tmp/unchanged.env"
echo 'Workflow tool updater contract passed'
