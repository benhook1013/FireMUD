#!/usr/bin/env bash
set -euo pipefail
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
cp "$ROOT_DIR/config/workflow-tool-versions.env" "$tmp/authority.env"
cp "$ROOT_DIR/k8s/velero/verify-backups-cronjob.yaml" "$tmp/velero.yaml"
chmod 0640 "$tmp/authority.env"
chmod 0600 "$tmp/velero.yaml"
checksum=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
image_digest=sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb
printf 'version=9.8.7\n%s  velero-v9.8.7-linux-amd64.tar.gz\n' "$checksum" > "$tmp/checksums"
echo "velero/velero:v9.8.7@$image_digest" > "$tmp/image-evidence"
python3 - "$ROOT_DIR" "$tmp/authority.env" "$tmp/checksums" "$tmp/image-evidence" "$tmp/velero.yaml" "$image_digest" <<'PY'
import importlib.util
import sys
from pathlib import Path

root, authority, checksum_file, evidence_file, manifest = map(Path, sys.argv[1:6])
expected_digest = sys.argv[6]
spec = importlib.util.spec_from_file_location("updater", root / "dev-tools/maintenance/update-workflow-tool.py")
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)

def resolve(repository, tag):
    if (repository, tag) != ("velero/velero", "v9.8.7"):
        raise SystemExit(f"unexpected Docker Hub lookup: {repository}:{tag}")
    return expected_digest

module.dockerhub_digest = resolve
sys.argv = [
    str(root / "dev-tools/maintenance/update-workflow-tool.py"),
    "velero",
    "9.8.7",
    "--checksum-file",
    str(checksum_file),
    "--image-evidence-file",
    str(evidence_file),
    "--authority",
    str(authority),
    "--velero-manifest",
    str(manifest),
]
module.main()
PY
grep -Fx 'VELERO_VERSION=9.8.7' "$tmp/authority.env" >/dev/null
grep -Fx 'VELERO_LINUX_AMD64_CHECKSUM_VERSION=9.8.7' "$tmp/authority.env" >/dev/null
grep -Fx "VELERO_LINUX_AMD64_SHA256=$checksum" "$tmp/authority.env" >/dev/null
grep -Fx "VELERO_IMAGE_DIGEST=$image_digest" "$tmp/authority.env" >/dev/null
grep -F "image: velero/velero:v9.8.7@$image_digest" "$tmp/velero.yaml" >/dev/null
test "$(stat -c '%a' "$tmp/authority.env")" = 640
test "$(stat -c '%a' "$tmp/velero.yaml")" = 600
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

kubectl_checksum=dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd
printf 'version=9.8.7\n%s\n' "$kubectl_checksum" > "$tmp/kubectl-checksum"
cp "$ROOT_DIR/config/workflow-tool-versions.env" "$tmp/kubectl-authority.env"
python3 "$ROOT_DIR/dev-tools/maintenance/update-workflow-tool.py" kubectl 9.8.7 \
  --checksum-file "$tmp/kubectl-checksum" --authority "$tmp/kubectl-authority.env"
grep -Fx 'KUBECTL_VERSION=9.8.7' "$tmp/kubectl-authority.env" >/dev/null
grep -Fx 'KUBECTL_LINUX_AMD64_CHECKSUM_VERSION=9.8.7' "$tmp/kubectl-authority.env" >/dev/null
grep -Fx "KUBECTL_LINUX_AMD64_SHA256=$kubectl_checksum" "$tmp/kubectl-authority.env" >/dev/null

python3 - "$ROOT_DIR" "$tmp" <<'PY'
import importlib.util
import sys
from pathlib import Path

root, tmp = map(Path, sys.argv[1:])
spec = importlib.util.spec_from_file_location("updater", root / "dev-tools/maintenance/update-workflow-tool.py")
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)

for tool, (_, asset_template, _) in module.SPECS.items():
    asset = asset_template.format(v="9.8.7")
    positive = tmp / f"{tool}-versioned-checksum"
    positive.write_text(f"version=9.8.7\n{'a' * 64}  {asset}\n", encoding="utf-8")
    if not module.checksum_text_from_evidence(positive, "9.8.7").startswith("a" * 64):
        raise SystemExit(f"{tool} checksum evidence metadata was not removed before extraction")

    for name, contents, diagnostic in (
        (
            "checksum-only",
            f"{'a' * 64}  {asset}\n",
            "checksum evidence must begin with exact version metadata: version=9.8.7",
        ),
        (
            "mismatched-version",
            f"version=9.8.8\n{'a' * 64}  {asset}\n",
            "checksum evidence version does not match requested version 9.8.7",
        ),
    ):
        invalid = tmp / f"{tool}-{name}"
        invalid.write_text(contents, encoding="utf-8")
        try:
            module.checksum_text_from_evidence(invalid, "9.8.7")
        except SystemExit as exc:
            if str(exc) != diagnostic:
                raise SystemExit(f"{tool} {name} had unexpected diagnostic: {exc}") from exc
        else:
            raise SystemExit(f"{tool} {name} checksum evidence was accepted")
PY

cp "$ROOT_DIR/config/workflow-tool-versions.env" "$tmp/lock-authority.env"
cp "$ROOT_DIR/k8s/velero/verify-backups-cronjob.yaml" "$tmp/lock-velero.yaml"
lock_checksum=cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc
lock_image_digest=sha256:eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee
printf 'version=9.8.7\n%s  velero-v9.8.7-linux-amd64.tar.gz\n' "$lock_checksum" > "$tmp/lock-checksum"
echo "velero/velero:v9.8.7@$lock_image_digest" > "$tmp/lock-image-evidence"
python3 - "$ROOT_DIR" "$tmp/lock-authority.env" "$tmp/lock-checksum" "$tmp/lock-image-evidence" "$tmp/lock-velero.yaml" "$lock_image_digest" <<'PY'
import importlib.util
import subprocess
import sys
from pathlib import Path

root, authority, checksum_file, evidence_file, manifest = map(Path, sys.argv[1:6])
expected_digest = sys.argv[6]
spec = importlib.util.spec_from_file_location("updater", root / "dev-tools/maintenance/update-workflow-tool.py")
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)
lock_attempt_code = """
import fcntl
import os
import sys
fd = os.open(sys.argv[1], os.O_RDONLY)
try:
    try:
        fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
    except BlockingIOError:
        raise SystemExit(1)
    raise SystemExit(0)
finally:
    os.close(fd)
"""

def lock_attempt(parent):
    return subprocess.run(
        [sys.executable, "-c", lock_attempt_code, str(parent)],
        check=False,
    ).returncode

with module.authority_lock(authority):
    if lock_attempt(authority.resolve().parent) != 1:
        raise SystemExit("competing process acquired the held authority lock")
if lock_attempt(authority.resolve().parent) != 0:
    raise SystemExit("competing process could not acquire the released authority lock")

real_reconcile = module.reconcile_recovery_journal
real_transaction = module.transactional_write
real_read_text = module.Path.read_text
real_image_evidence = module.velero_image_digest_from_evidence
events = []

def instrumented_reconcile(lock_authority, allowed_target_sets):
    if lock_attempt(Path(lock_authority).resolve().parent) != 1:
        raise SystemExit("main reached recovery reconciliation without the authority lock")
    events.append("reconcile")
    return real_reconcile(lock_authority, allowed_target_sets)

def instrumented_read_text(path, *args, **kwargs):
    if Path(path).resolve() == checksum_file.resolve():
        if lock_attempt(authority.resolve().parent) != 0:
            raise SystemExit("main read the checksum file while the authority lock was held")
        events.append("checksum")
    return real_read_text(path, *args, **kwargs)

def instrumented_image_evidence(path, version):
    if lock_attempt(authority.resolve().parent) != 0:
        raise SystemExit("main read Velero image evidence while the authority lock was held")
    events.append("evidence")
    return real_image_evidence(path, version)

def instrumented_dockerhub_digest(repository, tag):
    if lock_attempt(authority.resolve().parent) != 0:
        raise SystemExit("main resolved Docker Hub image while the authority lock was held")
    if (repository, tag) != ("velero/velero", "v9.8.7"):
        raise SystemExit(f"unexpected Docker Hub lookup: {repository}:{tag}")
    events.append("resolve")
    return expected_digest

def instrumented_transaction(*args, **kwargs):
    updates = args[0]
    if lock_attempt(Path(updates[0][0]).resolve().parent) != 1:
        raise SystemExit("main reached transaction without the authority lock")
    if not events or events[-1] != "reconcile":
        raise SystemExit("main did not reconcile immediately before the final transaction")
    events.append("transaction")
    return real_transaction(*args, **kwargs)

module.reconcile_recovery_journal = instrumented_reconcile
module.Path.read_text = instrumented_read_text
module.velero_image_digest_from_evidence = instrumented_image_evidence
module.dockerhub_digest = instrumented_dockerhub_digest
module.transactional_write = instrumented_transaction
sys.argv = [
    str(root / "dev-tools/maintenance/update-workflow-tool.py"),
    "velero",
    "9.8.7",
    "--checksum-file",
    str(checksum_file),
    "--image-evidence-file",
    str(evidence_file),
    "--authority",
    str(authority),
    "--velero-manifest",
    str(manifest),
]
module.main()
if events[:6] != ["reconcile", "evidence", "resolve", "checksum", "reconcile", "transaction"]:
    raise SystemExit(f"unexpected lock and resolution order: {events}")
PY
grep -Fx "VELERO_LINUX_AMD64_SHA256=$lock_checksum" "$tmp/lock-authority.env" >/dev/null
grep -Fx "VELERO_IMAGE_DIGEST=$lock_image_digest" "$tmp/lock-authority.env" >/dev/null
grep -F "image: velero/velero:v9.8.7@$lock_image_digest" "$tmp/lock-velero.yaml" >/dev/null

cp "$ROOT_DIR/config/workflow-tool-versions.env" "$tmp/unchanged.env"
cp "$tmp/unchanged.env" "$tmp/before.env"
echo 'no Velero image projection' > "$tmp/invalid-velero.yaml"
python3 - "$ROOT_DIR" "$tmp/unchanged.env" "$tmp/checksums" "$tmp/image-evidence" "$tmp/invalid-velero.yaml" "$image_digest" <<'PY'
import importlib.util
import sys
from pathlib import Path

root, authority, checksum_file, evidence_file, manifest = map(Path, sys.argv[1:6])
expected_digest = sys.argv[6]
spec = importlib.util.spec_from_file_location("updater", root / "dev-tools/maintenance/update-workflow-tool.py")
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)
module.dockerhub_digest = lambda repository, tag: expected_digest
sys.argv = [
    str(root / "dev-tools/maintenance/update-workflow-tool.py"),
    "velero",
    "9.8.7",
    "--checksum-file",
    str(checksum_file),
    "--image-evidence-file",
    str(evidence_file),
    "--authority",
    str(authority),
    "--velero-manifest",
    str(manifest),
]
try:
    module.main()
except SystemExit as exc:
    if "expected one Velero image projection" not in str(exc):
        raise SystemExit(f"unexpected missing projection diagnostic: {exc}") from exc
else:
    raise SystemExit("updater accepted a missing Velero image projection")
PY
cmp "$tmp/before.env" "$tmp/unchanged.env"

cp "$ROOT_DIR/k8s/velero/verify-backups-cronjob.yaml" "$tmp/unchanged.yaml"
cp "$tmp/before.env" "$tmp/no-image-evidence.env"
cp "$tmp/unchanged.yaml" "$tmp/no-image-evidence.yaml"
python3 - "$ROOT_DIR" "$tmp/no-image-evidence.env" "$tmp/checksums" "$tmp/no-image-evidence.yaml" <<'PY'
import contextlib
import importlib.util
import io
import sys
from pathlib import Path

root, authority, checksum_file, manifest = map(Path, sys.argv[1:])
spec = importlib.util.spec_from_file_location("updater", root / "dev-tools/maintenance/update-workflow-tool.py")
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)

def unexpected_resolution(repository, tag):
    raise SystemExit(f"no-evidence update resolved Docker Hub tag: {repository}:{tag}")

module.dockerhub_digest = unexpected_resolution
sys.argv = [
    str(root / "dev-tools/maintenance/update-workflow-tool.py"),
    "velero",
    "9.8.7",
    "--checksum-file",
    str(checksum_file),
    "--authority",
    str(authority),
    "--velero-manifest",
    str(manifest),
]
stderr = io.StringIO()
with contextlib.redirect_stderr(stderr):
    try:
        module.main()
    except SystemExit as exc:
        if exc.code != 2:
            raise SystemExit(f"missing Velero evidence had unexpected exit code: {exc.code}") from exc
    else:
        raise SystemExit("updater accepted a Velero update without image evidence")
if "--image-evidence-file is required for velero updates" not in stderr.getvalue():
    raise SystemExit(f"missing Velero evidence had unexpected diagnostic: {stderr.getvalue()}")
PY
cmp "$tmp/before.env" "$tmp/no-image-evidence.env"
cmp "$tmp/unchanged.yaml" "$tmp/no-image-evidence.yaml"

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

cp "$tmp/before.env" "$tmp/mismatched-digest.env"
cp "$tmp/unchanged.yaml" "$tmp/mismatched-digest.yaml"
printf '%s\n' "velero/velero:v9.8.7@$image_digest" > "$tmp/mismatched-digest.evidence"
python3 - "$ROOT_DIR" "$tmp/mismatched-digest.env" "$tmp/checksums" "$tmp/mismatched-digest.evidence" "$tmp/mismatched-digest.yaml" <<'PY'
import importlib.util
import sys
from pathlib import Path

root, authority, checksum_file, evidence_file, manifest = map(Path, sys.argv[1:])
spec = importlib.util.spec_from_file_location("updater", root / "dev-tools/maintenance/update-workflow-tool.py")
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)

def resolve(repository, tag):
    if (repository, tag) != ("velero/velero", "v9.8.7"):
        raise SystemExit(f"unexpected Docker Hub lookup: {repository}:{tag}")
    return "sha256:" + "c" * 64

module.dockerhub_digest = resolve
sys.argv = [
    str(root / "dev-tools/maintenance/update-workflow-tool.py"),
    "velero",
    "9.8.7",
    "--checksum-file",
    str(checksum_file),
    "--image-evidence-file",
    str(evidence_file),
    "--authority",
    str(authority),
    "--velero-manifest",
    str(manifest),
]
try:
    module.main()
except SystemExit as exc:
    if "does not match Docker Hub tag v9.8.7" not in str(exc):
        raise SystemExit(f"unexpected digest mismatch diagnostic: {exc}") from exc
else:
    raise SystemExit("updater accepted a Velero evidence digest for the wrong Docker Hub tag")
PY
cmp "$tmp/before.env" "$tmp/mismatched-digest.env"
cmp "$tmp/unchanged.yaml" "$tmp/mismatched-digest.yaml"

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

cp "$ROOT_DIR/config/workflow-tool-versions.env" "$tmp/commit-marker-authority.env"
cp "$ROOT_DIR/k8s/velero/verify-backups-cronjob.yaml" "$tmp/commit-marker-velero.yaml"
python3 - "$ROOT_DIR" "$tmp/commit-marker-authority.env" "$tmp/commit-marker-velero.yaml" <<'PY'
import importlib.util
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
real_atomic_text_replace = module.atomic_text_replace

def fail_committed_marker(path, text):
    if Path(path).resolve() == journal and '"state":"committed"' in text:
        raise OSError("simulated committed marker write failure")
    real_atomic_text_replace(path, text)

module.atomic_text_replace = fail_committed_marker
try:
    module.transactional_write(
        [(authority, "committed-marker authority\n"), (manifest, "committed-marker manifest\n")]
    )
except OSError as exc:
    if str(exc) != "simulated committed marker write failure":
        raise SystemExit(f"unexpected committed marker failure: {exc}") from exc
else:
    raise SystemExit("transaction accepted a failed committed marker write")
if authority.read_text(encoding="utf-8") != authority_before:
    raise SystemExit("committed marker failure did not restore the authority")
if manifest.read_text(encoding="utf-8") != manifest_before:
    raise SystemExit("committed marker failure did not restore the manifest")
if journal.exists():
    raise SystemExit("committed marker failure left recovery state")

module.atomic_text_replace = real_atomic_text_replace
module.transactional_write(
    [(authority, "recovered authority\n"), (manifest, "recovered manifest\n")]
)
if authority.read_text(encoding="utf-8") != "recovered authority\n":
    raise SystemExit("transaction did not remain recoverable after committed marker failure")
if manifest.read_text(encoding="utf-8") != "recovered manifest\n":
    raise SystemExit("manifest transaction did not remain recoverable after committed marker failure")
if journal.exists():
    raise SystemExit("recoverable transaction left recovery state")
PY

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

cp "$ROOT_DIR/config/workflow-tool-versions.env" "$tmp/commit-fsync-authority.env"
cp "$ROOT_DIR/k8s/velero/verify-backups-cronjob.yaml" "$tmp/commit-fsync-velero.yaml"
python3 - "$ROOT_DIR" "$tmp/commit-fsync-authority.env" "$tmp/commit-fsync-velero.yaml" <<'PY'
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
real_atomic_text_replace = module.atomic_text_replace
real_fsync = module.fsync_directory
real_replace = module.os.replace
phase = "forward"
commit_fsync_failed = False

def track_atomic_text_replace(path, text):
    global phase
    if Path(path).resolve() == journal and '"state":"committed"' in text:
        phase = "committing"
    return real_atomic_text_replace(path, text)

def fail_commit_fsync(path):
    global commit_fsync_failed, phase
    if phase == "committing" and not commit_fsync_failed:
        commit_fsync_failed = True
        phase = "rollback"
        raise OSError("simulated committed marker fsync failure")
    return real_fsync(path)

def fail_first_rollback(source, destination):
    if phase == "rollback" and Path(destination).resolve() == authority:
        raise OSError("simulated rollback replacement failure")
    return real_replace(source, destination)

module.atomic_text_replace = track_atomic_text_replace
module.fsync_directory = fail_commit_fsync
module.os.replace = fail_first_rollback
try:
    module.transactional_write(
        [(authority, "mixed authority\n"), (manifest, "mixed manifest\n")]
    )
except OSError as exc:
    if str(exc) != "simulated rollback replacement failure":
        raise SystemExit(f"unexpected commit-fsync rollback diagnostic: {exc}") from exc
else:
    raise SystemExit("transaction accepted a failed rollback after committed marker fsync failure")
if authority.read_text(encoding="utf-8") != "mixed authority\n":
    raise SystemExit("commit-fsync failure did not leave the failed rollback target observable")
if manifest.read_text(encoding="utf-8") != manifest_before:
    raise SystemExit("commit-fsync failure did not restore the successful rollback target")
if not journal.exists():
    raise SystemExit("commit-fsync rollback failure discarded recovery state")
payload = json.loads(journal.read_text(encoding="utf-8"))
if payload.get("state") != "prepared":
    raise SystemExit("commit-fsync rollback failure left a committed recovery journal")

module.atomic_text_replace = real_atomic_text_replace
module.fsync_directory = real_fsync
module.os.replace = real_replace
module.transactional_write(
    [(authority, "converged authority\n"), (manifest, "converged manifest\n")]
)
if authority.read_text(encoding="utf-8") != "converged authority\n":
    raise SystemExit("next invocation did not converge the authority after recovery")
if manifest.read_text(encoding="utf-8") != "converged manifest\n":
    raise SystemExit("next invocation did not converge the manifest after recovery")
if journal.exists():
    raise SystemExit("recovered commit-fsync transaction left recovery state")
PY

cp "$ROOT_DIR/config/workflow-tool-versions.env" "$tmp/recovery-authority.env"
cp "$ROOT_DIR/k8s/velero/verify-backups-cronjob.yaml" "$tmp/recovery-velero.yaml"
chmod 0640 "$tmp/recovery-authority.env"
chmod 0600 "$tmp/recovery-velero.yaml"
python3 - "$ROOT_DIR" "$tmp/recovery-authority.env" "$tmp/recovery-velero.yaml" <<'PY'
import importlib.util
import json
import stat
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
authority_before = authority.read_text(encoding="utf-8")
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
if journal.stat().st_mode & 0o077:
    raise SystemExit("prepared recovery journal is accessible to group or world")

module.os.replace = real_replace
unavailable_evidence = authority.with_name("unavailable-evidence")
velero_checksum_file = authority.with_name("velero-checksum")
velero_checksum_file.write_text(
    "version=9.8.7\n" + "d" * 64 + "  velero-v9.8.7-linux-amd64.tar.gz\n", encoding="utf-8"
)
sys.argv = [
    str(root / "dev-tools/maintenance/update-workflow-tool.py"),
    "velero",
    "9.8.7",
    "--checksum-file",
    str(velero_checksum_file),
    "--image-evidence-file",
    str(unavailable_evidence),
    "--authority",
    str(authority),
    "--velero-manifest",
    str(manifest),
]
try:
    module.main()
except SystemExit as exc:
    expected = (
        f"could not read Velero image evidence file {unavailable_evidence}: "
        f"[Errno 2] No such file or directory: '{unavailable_evidence}'"
    )
    if str(exc) != expected:
        raise SystemExit(f"unexpected unavailable evidence diagnostic: {exc}") from exc
else:
    raise SystemExit("unavailable Velero image evidence was accepted")
if authority.read_text(encoding="utf-8") != authority_before or manifest.read_text(encoding="utf-8") != manifest_before:
    raise SystemExit("unavailable later evidence left recovered targets inconsistent")
if journal.exists():
    raise SystemExit("initial recovery did not clear the prepared journal before evidence failure")

checksum_file = authority.with_name("helm-checksum")
checksum_file.write_text(
    "version=9.8.7\n" + "c" * 64 + "  helm-v9.8.7-linux-amd64.tar.gz\n", encoding="utf-8"
)
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
if stat.S_IMODE(authority.stat().st_mode) != 0o640:
    raise SystemExit("recovery restore changed the authority mode")
if stat.S_IMODE(manifest.stat().st_mode) != 0o600:
    raise SystemExit("recovery restore changed the manifest mode")
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
