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
python3 - "$ROOT_DIR" "$tmp/authority.env" <<'PY'
import importlib.util
import sys
from pathlib import Path

root, authority = map(Path, sys.argv[1:])
spec = importlib.util.spec_from_file_location("updater", root / "dev-tools/maintenance/update-workflow-tool.py")
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)
if module.recovery_journal_path(authority).exists():
    raise SystemExit("successful transaction left recovery state")
PY

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
manifest = manifest.resolve()

def fail_second_target(source, destination):
    if Path(destination).resolve() == manifest:
        raise OSError("simulated second replacement failure")
    real_replace(source, destination)

module.os.replace = fail_second_target
try:
    module.transactional_write([(authority, "changed authority\n"), (manifest, "changed manifest\n")])
except OSError:
    pass
else:
    raise SystemExit("transaction accepted a failed second replacement")
if module.recovery_journal_path(authority).exists():
    raise SystemExit("successful rollback left recovery state")
PY
cmp "$tmp/transaction-authority-before.env" "$tmp/transaction-authority.env"
cmp "$tmp/transaction-velero-before.yaml" "$tmp/transaction-velero.yaml"

cp "$ROOT_DIR/config/workflow-tool-versions.env" "$tmp/fsync-authority.env"
cp "$ROOT_DIR/k8s/velero/verify-backups-cronjob.yaml" "$tmp/fsync-velero.yaml"
cp "$tmp/fsync-authority.env" "$tmp/fsync-authority-before.env"
cp "$tmp/fsync-velero.yaml" "$tmp/fsync-velero-before.yaml"
python3 - "$ROOT_DIR" "$tmp/fsync-authority.env" "$tmp/fsync-velero.yaml" <<'PY'
import importlib.util
import sys
from pathlib import Path

root, authority, manifest = map(Path, sys.argv[1:])
spec = importlib.util.spec_from_file_location("updater", root / "dev-tools/maintenance/update-workflow-tool.py")
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)
real_fsync = module.fsync_directory
real_replace = module.os.replace
authority = authority.resolve()
authority_replaced = False
phase = "forward"

def track_authority_replacement(source, destination):
    global authority_replaced
    real_replace(source, destination)
    if Path(destination).resolve() == authority:
        authority_replaced = True

def fail_forward_fsync(path):
    global phase
    if phase == "forward" and authority_replaced:
        phase = "rollback"
        raise OSError("simulated forward directory fsync failure")
    real_fsync(path)

module.os.replace = track_authority_replacement
module.fsync_directory = fail_forward_fsync
authority_before = authority.read_text(encoding="utf-8")
try:
    module.transactional_write([(authority, "changed authority\n"), (manifest, "changed manifest\n")])
except OSError:
    pass
else:
    raise SystemExit("transaction accepted a failed forward directory fsync")
if authority.read_text(encoding="utf-8") != authority_before:
    raise SystemExit("forward fsync failure did not roll back the replaced authority")
if module.recovery_journal_path(authority).exists():
    raise SystemExit("successful rollback left recovery state")
PY
cmp "$tmp/fsync-authority-before.env" "$tmp/fsync-authority.env"
cmp "$tmp/fsync-velero-before.yaml" "$tmp/fsync-velero.yaml"

cp "$ROOT_DIR/config/workflow-tool-versions.env" "$tmp/recovery-authority.env"
cp "$ROOT_DIR/k8s/velero/verify-backups-cronjob.yaml" "$tmp/recovery-velero.yaml"
python3 - "$ROOT_DIR" "$tmp/recovery-authority.env" "$tmp/recovery-velero.yaml" <<'PY'
import importlib.util
import json
import sys
from pathlib import Path

root, authority, manifest = map(Path, sys.argv[1:])
spec = importlib.util.spec_from_file_location("updater", root / "dev-tools/maintenance/update-workflow-tool.py")
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)
real_replace = module.os.replace
phase = "forward"
manifest = manifest.resolve()
authority = authority.resolve()
manifest_before = manifest.read_text(encoding="utf-8")

def fail_forward_and_rollback(source, destination):
    global phase
    destination = Path(destination).resolve()
    if destination == manifest and phase == "forward":
        phase = "rollback"
        raise OSError("simulated second replacement failure")
    if destination == authority and phase == "rollback":
        raise OSError("simulated rollback replacement failure")
    real_replace(source, destination)

module.os.replace = fail_forward_and_rollback
try:
    module.transactional_write([(authority, "changed authority\n"), (manifest, "changed manifest\n")])
except OSError:
    pass
else:
    raise SystemExit("transaction accepted a failed rollback")

journal = module.recovery_journal_path(authority)
if not journal.exists():
    raise SystemExit("failed rollback did not retain recovery state")
payload = json.loads(journal.read_text(encoding="utf-8"))
if payload.get("schema") != module.RECOVERY_SCHEMA or payload.get("version") != module.RECOVERY_VERSION:
    raise SystemExit("recovery state schema is not durable")
if payload.get("state") != "prepared":
    raise SystemExit("failed rollback did not retain prepared recovery state")
if {entry.get("path") for entry in payload.get("targets", [])} != {str(authority), str(manifest)}:
    raise SystemExit("recovery state target set is incomplete")

module.os.replace = real_replace
checksum_file = authority.with_name("helm-checksum")
checksum_file.write_text("c" * 64 + "  helm-v9.8.7-linux-amd64.tar.gz\n", encoding="utf-8")
sys.argv = [
    str(root / "dev-tools/maintenance/update-workflow-tool.py"),
    "helm",
    "9.8.7",
    "--checksum-file",
    str(checksum_file),
    "--authority",
    str(authority),
    "--velero-manifest",
    str(manifest),
]
module.main()
if "HELM_VERSION=9.8.7" not in authority.read_text(encoding="utf-8"):
    raise SystemExit("later one-target transaction did not produce the authority update")
if manifest.read_text(encoding="utf-8") != manifest_before:
    raise SystemExit("later one-target transaction did not restore the manifest")
if journal.exists():
    raise SystemExit("later transaction did not clear recovered state")

real_remove = module.remove_recovery_journal

def fail_cleanup(path):
    raise OSError("simulated committed journal cleanup failure")

module.remove_recovery_journal = fail_cleanup
try:
    module.transactional_write([(authority, "committed authority\n"), (manifest, "committed manifest\n")])
except OSError:
    pass
else:
    raise SystemExit("transaction hid a committed journal cleanup failure")
payload = json.loads(journal.read_text(encoding="utf-8"))
if payload.get("state") != "committed":
    raise SystemExit("committed cleanup failure did not retain committed state")
if authority.read_text(encoding="utf-8") != "committed authority\n" or manifest.read_text(encoding="utf-8") != "committed manifest\n":
    raise SystemExit("committed cleanup failure rolled back the update")

module.remove_recovery_journal = real_remove
module.transactional_write([(authority, "final authority\n"), (manifest, "final manifest\n")])
if authority.read_text(encoding="utf-8") != "final authority\n":
    raise SystemExit("committed recovery changed the authority unexpectedly")
if manifest.read_text(encoding="utf-8") != "final manifest\n":
    raise SystemExit("committed recovery changed the manifest unexpectedly")
if journal.exists():
    raise SystemExit("committed recovery did not clear state")
PY

cp "$ROOT_DIR/config/workflow-tool-versions.env" "$tmp/state-authority.env"
cp "$ROOT_DIR/k8s/velero/verify-backups-cronjob.yaml" "$tmp/state-velero.yaml"
python3 - "$ROOT_DIR" "$tmp/state-authority.env" "$tmp/state-velero.yaml" <<'PY'
import importlib.util
import json
import sys
from pathlib import Path

root, authority, manifest = map(Path, sys.argv[1:])
spec = importlib.util.spec_from_file_location("updater", root / "dev-tools/maintenance/update-workflow-tool.py")
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)
authority = authority.resolve()
manifest = manifest.resolve()
journal = module.recovery_journal_path(authority)
authority_before = authority.read_text(encoding="utf-8")
manifest_before = manifest.read_text(encoding="utf-8")

journal.write_text("{malformed", encoding="utf-8")
try:
    module.transactional_write([(authority, "must not apply\n"), (manifest, "must not apply\n")])
except OSError:
    pass
else:
    raise SystemExit("updater accepted malformed recovery state")
if authority.read_text(encoding="utf-8") != authority_before or manifest.read_text(encoding="utf-8") != manifest_before:
    raise SystemExit("malformed recovery state changed a target")
if not journal.exists():
    raise SystemExit("malformed recovery state was discarded")

journal.unlink()
journal.write_text(
    json.dumps(
        {
            "schema": module.RECOVERY_SCHEMA,
            "version": module.RECOVERY_VERSION,
            "state": "prepared",
            "targets": [
                {"path": str(authority), "contents": authority_before},
                {"path": str(authority.with_name("unexpected-target")), "contents": manifest_before},
            ],
        }
    ),
    encoding="utf-8",
)
try:
    module.transactional_write([(authority, "must not apply\n"), (manifest, "must not apply\n")])
except OSError:
    pass
else:
    raise SystemExit("updater accepted a mismatched recovery target set")
if authority.read_text(encoding="utf-8") != authority_before or manifest.read_text(encoding="utf-8") != manifest_before:
    raise SystemExit("mismatched recovery state changed a target")
if not journal.exists():
    raise SystemExit("mismatched recovery state was discarded")

journal.unlink()
journal_target = authority.with_name("journal-target")
journal_target.write_text(
    json.dumps(
        {
            "schema": module.RECOVERY_SCHEMA,
            "version": module.RECOVERY_VERSION,
            "state": "prepared",
            "targets": [
                {"path": str(authority), "contents": authority_before},
                {"path": str(manifest), "contents": manifest_before},
            ],
        }
    ),
    encoding="utf-8",
)
journal.symlink_to(journal_target)
try:
    module.transactional_write([(authority, "must not apply\n"), (manifest, "must not apply\n")])
except OSError:
    pass
else:
    raise SystemExit("updater accepted a symlink recovery state")
if authority.read_text(encoding="utf-8") != authority_before or manifest.read_text(encoding="utf-8") != manifest_before:
    raise SystemExit("symlink recovery state changed a target")
if not journal.is_symlink():
    raise SystemExit("symlink recovery state was discarded")
PY
echo 'Workflow tool updater contract passed'
