#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
tmp="$(mktemp -d)"
trap 'rm -rf -- "$tmp"' EXIT

cp "$ROOT_DIR/config/workflow-tool-versions.env" "$tmp/authority.env"
chmod 0640 "$tmp/authority.env"
checksum=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
printf 'version=9.8.7\n%s  gh_9.8.7_linux_amd64.tar.gz\n' "$checksum" > "$tmp/checksums"

python3 "$ROOT_DIR/dev-tools/maintenance/update-workflow-tool.py" gh 9.8.7 \
  --checksum-file "$tmp/checksums" --authority "$tmp/authority.env"
grep -Fx 'GH_VERSION=9.8.7' "$tmp/authority.env" >/dev/null
grep -Fx 'GH_LINUX_AMD64_CHECKSUM_VERSION=9.8.7' "$tmp/authority.env" >/dev/null
grep -Fx "GH_LINUX_AMD64_SHA256=$checksum" "$tmp/authority.env" >/dev/null
test "$(stat -c '%a' "$tmp/authority.env")" = 640

cp "$ROOT_DIR/config/workflow-tool-versions.env" "$tmp/unchanged.env"
cp "$tmp/unchanged.env" "$tmp/unchanged-before.env"
for evidence in \
  'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa  wrong-name.tar.gz' \
  'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa  gh_9.8.7_linux_amd64.tar.gz
bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb  gh_9.8.7_linux_amd64.tar.gz'; do
  printf 'version=9.8.7\n%b\n' "$evidence" > "$tmp/invalid-checksums"
  if python3 "$ROOT_DIR/dev-tools/maintenance/update-workflow-tool.py" gh 9.8.7 \
    --checksum-file "$tmp/invalid-checksums" --authority "$tmp/unchanged.env" \
    >"$tmp/invalid.out" 2>"$tmp/invalid.err"; then
    echo "updater accepted missing or duplicate exact-asset checksum evidence" >&2
    exit 1
  fi
  grep -F 'could not identify exactly one checksum for gh_9.8.7_linux_amd64.tar.gz' "$tmp/invalid.err" >/dev/null
  cmp "$tmp/unchanged-before.env" "$tmp/unchanged.env"
done

for removed_tool in kubectl helm velero; do
  if python3 "$ROOT_DIR/dev-tools/maintenance/update-workflow-tool.py" "$removed_tool" 9.8.7 \
    --checksum-file "$tmp/checksums" --authority "$tmp/unchanged.env" \
    >"$tmp/removed.out" 2>"$tmp/removed.err"; then
    echo "updater retained obsolete $removed_tool checksum mutation" >&2
    exit 1
  fi
  grep -F 'invalid choice' "$tmp/removed.err" >/dev/null
done

python3 - "$ROOT_DIR" "$tmp/authority.env" <<'PY'
import importlib.util
import sys
from pathlib import Path

root = Path(sys.argv[1])
authority = Path(sys.argv[2])
spec = importlib.util.spec_from_file_location(
    "updater", root / "dev-tools/maintenance/update-workflow-tool.py"
)
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)

before = authority.read_text(encoding="utf-8")
module.transactional_write(
    [(authority, before.replace("GH_VERSION=9.8.7", "GH_VERSION=9.8.8"))],
    authority=authority,
)
if "GH_VERSION=9.8.8" not in authority.read_text(encoding="utf-8"):
    raise SystemExit("single-authority transaction did not commit")
PY
