#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
LINK_CHECK="$ROOT_DIR/dev-tools/docs/link-check.sh"
TEMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TEMP_DIR"' EXIT

make_fixture() {
  local fixture_root="$1"
  mkdir -p "$fixture_root/dev-tools/docs" "$fixture_root/config" "$fixture_root/design"
  cp "$LINK_CHECK" "$fixture_root/dev-tools/docs/link-check.sh"
  cp "$ROOT_DIR/config/workflow-tool-versions.env" "$fixture_root/config/workflow-tool-versions.env"
  chmod +x "$fixture_root/dev-tools/docs/link-check.sh"
}

grep -Fq 'Cached Lychee archive checksum verification failed:' "$LINK_CHECK"
grep -Fq 'Cached Lychee binary checksum verification failed:' "$LINK_CHECK"
grep -Fq 'Downloaded Lychee archive checksum verification failed:' "$LINK_CHECK"

MISMATCH_ROOT="$TEMP_DIR/mismatch"
make_fixture "$MISMATCH_ROOT"
mismatch_authority="$MISMATCH_ROOT/config/workflow-tool-versions.env"
sed 's/^LYCHEE_LINUX_X86_64_MUSL_CHECKSUM_VERSION=.*/LYCHEE_LINUX_X86_64_MUSL_CHECKSUM_VERSION=0.0.0/' \
  "$mismatch_authority" >"$mismatch_authority.tmp"
mv -f "$mismatch_authority.tmp" "$mismatch_authority"
if XDG_CACHE_HOME="$TEMP_DIR/mismatch-cache" "$MISMATCH_ROOT/dev-tools/docs/link-check.sh" \
  >"$TEMP_DIR/mismatch.out" 2>&1; then
  echo "Lychee checksum-version mismatch unexpectedly passed" >&2
  exit 1
fi
grep -q 'checksum authority must match the release version' "$TEMP_DIR/mismatch.out"

CACHE_ROOT="$TEMP_DIR/cache"
# shellcheck disable=SC1091
source "$ROOT_DIR/config/workflow-tool-versions.env"
CACHE_DIR="$CACHE_ROOT/lychee/$LYCHEE_VERSION/x86_64-unknown-linux-musl"
mkdir -p "$CACHE_DIR"
printf '#!/bin/sh\nexit 0\n' > "$CACHE_DIR/lychee"
chmod +x "$CACHE_DIR/lychee"
printf 'archive-sha binary-sha\n' > "$CACHE_DIR/verified.sha256"
printf 'corrupt archive\n' > "$CACHE_DIR/lychee.tar.gz"

FAKE_BIN="$TEMP_DIR/fake-bin"
mkdir -p "$FAKE_BIN"
cat > "$FAKE_BIN/uname" <<'EOF'
#!/usr/bin/env bash
if [[ "$1" == "-s" ]]; then echo Linux; else echo x86_64; fi
EOF
cat > "$FAKE_BIN/sha256sum" <<'EOF'
#!/usr/bin/env bash
if [[ "${1:-}" == "--check" ]]; then
  # Fixture-only control for exercising a failed checksum verification.
  if [[ "${FAKE_SHA256SUM_CHECK_STATUS:-0}" != 0 ]]; then
    echo "sha256sum: checksum verification failed" >&2
  fi
  exit "${FAKE_SHA256SUM_CHECK_STATUS:-0}"
fi
printf 'binary-sha %s\n' "${1:?}"
EOF
cat > "$FAKE_BIN/tar" <<'EOF'
#!/usr/bin/env bash
archive="$2"
destination="$4"
if grep -q 'corrupt archive' "$archive"; then
  exit 1
fi
mkdir -p "$destination/lychee-x86_64-unknown-linux-musl"
printf '#!/bin/sh\nexit 0\n' > "$destination/lychee-x86_64-unknown-linux-musl/lychee"
EOF
cat > "$FAKE_BIN/curl" <<'EOF'
#!/usr/bin/env bash
printf 'downloaded archive\n' > "${@: -1}"
EOF
cat > "$FAKE_BIN/install" <<'EOF'
#!/usr/bin/env bash
cp "${@: -2:1}" "${@: -1}"
chmod 0755 "${@: -1}"
EOF
chmod +x "$FAKE_BIN"/*

CHECKSUM_FAILURE_ROOT="$TEMP_DIR/checksum-failure"
make_fixture "$CHECKSUM_FAILURE_ROOT"
CHECKSUM_FAILURE_CACHE_ROOT="$TEMP_DIR/checksum-failure-cache"
CHECKSUM_FAILURE_CACHE_DIR="$CHECKSUM_FAILURE_CACHE_ROOT/lychee/$LYCHEE_VERSION/x86_64-unknown-linux-musl"
if ! PATH="$FAKE_BIN:$PATH" XDG_CACHE_HOME="$CHECKSUM_FAILURE_CACHE_ROOT" \
  FAKE_SHA256SUM_CHECK_STATUS=1 \
  "$CHECKSUM_FAILURE_ROOT/dev-tools/docs/link-check.sh" \
  >"$TEMP_DIR/checksum-failure.out" 2>&1; then
  :
else
  echo "Lychee checksum failure unexpectedly passed" >&2
  exit 1
fi
grep -Fqx 'sha256sum: checksum verification failed' "$TEMP_DIR/checksum-failure.out"
grep -Fq 'Downloaded Lychee archive checksum verification failed:' "$TEMP_DIR/checksum-failure.out"
test ! -e "$CHECKSUM_FAILURE_CACHE_DIR/lychee"
test ! -e "$CHECKSUM_FAILURE_CACHE_DIR/verified.sha256"
test ! -e "$CHECKSUM_FAILURE_CACHE_DIR/lychee.tar.gz"

if ! PATH="$FAKE_BIN:$PATH" XDG_CACHE_HOME="$CACHE_ROOT" \
  "$LINK_CHECK" >"$TEMP_DIR/cache.out" 2>&1; then
  cat "$TEMP_DIR/cache.out" >&2
  exit 1
fi
test -x "$CACHE_DIR/lychee"
test -f "$CACHE_DIR/verified.sha256"
grep -q 'downloaded archive' "$CACHE_DIR/lychee.tar.gz"
read -r installed_archive_sha installed_binary_sha < "$CACHE_DIR/verified.sha256"
[[ "$installed_archive_sha" == "$LYCHEE_LINUX_X86_64_MUSL_SHA256" ]]
[[ "$installed_binary_sha" == "binary-sha" ]]

echo "link-check contract: PASS"
