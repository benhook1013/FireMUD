#!/usr/bin/env bash
set -euo pipefail
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
cp "$ROOT_DIR/config/workflow-tool-versions.env" "$tmp/authority.env"
cp "$ROOT_DIR/k8s/velero/verify-backups-cronjob.yaml" "$tmp/velero.yaml"
checksum=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
image_digest=sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb
echo "$checksum  velero-v9.8.7-linux-amd64.tar.gz" > "$tmp/checksums"
echo "velero/velero:v9.8.7@$image_digest" > "$tmp/image-evidence"
python3 "$ROOT_DIR/dev-tools/maintenance/update-workflow-tool.py" velero 9.8.7 \
  --checksum-file "$tmp/checksums" --image-evidence-file "$tmp/image-evidence" \
  --authority "$tmp/authority.env" --velero-manifest "$tmp/velero.yaml"
grep -Fx 'VELERO_VERSION=9.8.7' "$tmp/authority.env" >/dev/null
grep -Fx 'VELERO_LINUX_AMD64_CHECKSUM_VERSION=9.8.7' "$tmp/authority.env" >/dev/null
grep -Fx "VELERO_LINUX_AMD64_SHA256=$checksum" "$tmp/authority.env" >/dev/null
grep -Fx "VELERO_IMAGE_DIGEST=$image_digest" "$tmp/authority.env" >/dev/null
grep -F "image: velero/velero:v9.8.7@$image_digest" "$tmp/velero.yaml" >/dev/null

cp "$ROOT_DIR/config/workflow-tool-versions.env" "$tmp/unchanged.env"
cp "$tmp/unchanged.env" "$tmp/before.env"
echo 'no Velero image projection' > "$tmp/invalid-velero.yaml"
if python3 "$ROOT_DIR/dev-tools/maintenance/update-workflow-tool.py" velero 9.8.7 \
  --checksum-file "$tmp/checksums" --image-evidence-file "$tmp/image-evidence" \
  --authority "$tmp/unchanged.env" --velero-manifest "$tmp/invalid-velero.yaml"; then
  echo 'updater accepted a missing Velero image projection' >&2
  exit 1
fi
cmp "$tmp/before.env" "$tmp/unchanged.env"

assert_rejected_evidence() {
  local name="$1"
  local evidence="$2"
  local authority="$tmp/${name}.env"
  local manifest="$tmp/${name}.yaml"
  local evidence_file="$tmp/${name}.evidence"

  cp "$tmp/before.env" "$authority"
  cp "$ROOT_DIR/k8s/velero/verify-backups-cronjob.yaml" "$manifest"
  printf '%s\n' "$evidence" > "$evidence_file"
  if python3 "$ROOT_DIR/dev-tools/maintenance/update-workflow-tool.py" velero 9.8.7 \
    --checksum-file "$tmp/checksums" --image-evidence-file "$evidence_file" \
    --authority "$authority" --velero-manifest "$manifest"; then
    echo "updater accepted invalid Velero image evidence: $name" >&2
    exit 1
  fi
  cmp "$tmp/before.env" "$authority"
  cmp "$tmp/unchanged.yaml" "$manifest"
}

cp "$ROOT_DIR/k8s/velero/verify-backups-cronjob.yaml" "$tmp/unchanged.yaml"
assert_rejected_evidence missing-entry ""
assert_rejected_evidence mismatched-version \
  "velero/velero:v9.8.8@$image_digest"
assert_rejected_evidence mismatched-repository \
  "example/velero:v9.8.7@$image_digest"
assert_rejected_evidence malformed-digest \
  "velero/velero:v9.8.7@sha256:BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB"
assert_rejected_evidence duplicate-entry \
  "velero/velero:v9.8.7@$image_digest
velero/velero:v9.8.7@$image_digest"

cp "$tmp/before.env" "$tmp/raw-digest.env"
cp "$tmp/unchanged.yaml" "$tmp/raw-digest.yaml"
if python3 "$ROOT_DIR/dev-tools/maintenance/update-workflow-tool.py" velero 9.8.7 \
  --checksum-file "$tmp/checksums" --image-digest "$image_digest" \
  --authority "$tmp/raw-digest.env" --velero-manifest "$tmp/raw-digest.yaml" 2>"$tmp/raw-digest.stderr"; then
  echo 'updater retained the raw --image-digest override' >&2
  exit 1
fi
grep -F -- 'unrecognized arguments: --image-digest' "$tmp/raw-digest.stderr" >/dev/null
cmp "$tmp/before.env" "$tmp/raw-digest.env"
cmp "$tmp/unchanged.yaml" "$tmp/raw-digest.yaml"

cp "$ROOT_DIR/config/workflow-tool-versions.env" "$tmp/transaction-authority.env"
cp "$ROOT_DIR/k8s/velero/verify-backups-cronjob.yaml" "$tmp/transaction-velero.yaml"
cp "$tmp/transaction-authority.env" "$tmp/transaction-authority-before.env"
cp "$tmp/transaction-velero.yaml" "$tmp/transaction-velero-before.yaml"
python3 - "$ROOT_DIR" "$tmp/transaction-authority.env" "$tmp/transaction-velero.yaml" <<'PY'
import importlib.util
import sys
from pathlib import Path

root, authority, manifest = map(Path, sys.argv[1:])
spec = importlib.util.spec_from_file_location("updater", root / "dev-tools/maintenance/update-workflow-tool.py")
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)
real_replace = module.os.replace
calls = 0

def fail_between(source, destination):
    global calls
    calls += 1
    if calls == 2:
        raise OSError("simulated second replacement failure")
    real_replace(source, destination)

module.os.replace = fail_between
try:
    module.transactional_write([(authority, "changed authority\n"), (manifest, "changed manifest\n")])
except OSError:
    pass
else:
    raise SystemExit("transaction accepted a failed second replacement")
PY
cmp "$tmp/transaction-authority-before.env" "$tmp/transaction-authority.env"
cmp "$tmp/transaction-velero-before.yaml" "$tmp/transaction-velero.yaml"
echo 'Workflow tool updater contract passed'
